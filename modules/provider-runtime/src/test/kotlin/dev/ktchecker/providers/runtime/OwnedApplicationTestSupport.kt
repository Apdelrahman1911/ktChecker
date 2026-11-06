package dev.ktchecker.providers.runtime

import dev.ktchecker.contracts.CancellationToken
import dev.ktchecker.workerprotocol.ApplicationWire
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal fun applicationTestDigest(ch: Char) = "sha256:" + ch.toString().repeat(64)
internal val applicationTestIdentity = OwnedSupervisorIdentity("fake-owned-live-v2", "fake-target",
    applicationTestDigest('a'), applicationTestDigest('b'))

/** In-memory session only. It never starts a process/JVM, imports a compiler, or proves native custody. */
internal class ApplicationTestRig(
    maxJobs: Int = 4,
    generationMillis: Long = 10_000,
    private val configure: (ApplicationFakeSession) -> Unit = {},
) {
    val owner = OwnedApplicationOwner()
    val deadline = OwnedApplicationDeadline.afterMillis(generationMillis)
    val launch = ApplicationWire.Launch(
        ApplicationWire.Identity("1".repeat(32), owner.identity, applicationTestDigest('a'), applicationTestDigest('b'),
            applicationTestDigest('c'), applicationTestDigest('d'), applicationTestDigest('e'), "fake-adapter-1", "fake-profile-1"),
        ApplicationWire.Limits(maxJobs, ApplicationWire.MAX_STREAM_BYTES, ApplicationWire.MAX_STREAM_BYTES),
        ApplicationWire.Root(Path.of("never-opened-generation").toAbsolutePath().normalize().toString(), "fake-generation-key"),
        Path.of(".").toAbsolutePath().normalize().toString(),
        listOf(Path.of("never-executed-worker").toAbsolutePath().normalize().toString()), emptyMap(), byteArrayOf(1),
    )
    val starts = AtomicInteger()
    lateinit var fake: ApplicationFakeSession
    lateinit var configuration: OwnedSessionConfiguration
    val factory = OwnedApplicationSessionFactory { config ->
        starts.incrementAndGet()
        configuration = config
        ApplicationFakeSession(launch).also { fake = it; configure(it); it.startOutput() }
            .let { OwnedSessionOpenResult.StartedTransport(it) }
    }

    fun generationPermit(
        verifyBeforeStart: () -> Unit = {},
        validateHello: (ApplicationWire.Hello) -> Unit = {},
        verifyLive: () -> Unit = {},
        owner: OwnedApplicationOwner = this.owner,
    ) = OwnedApplicationGenerationPermit(owner, launch, deadline, verifyBeforeStart, validateHello, verifyLive)

    fun open(permit: OwnedApplicationGenerationPermit = generationPermit(), cancellation: CancellationToken = CancellationToken.NONE): OwnedApplicationSession =
        assertIs<OwnedApplicationOpenResult.Ready>(factory.open(permit, cancellation)).session

    fun job(session: OwnedApplicationSession, sequence: Int, deadline: OwnedApplicationDeadline, payload: ByteArray = byteArrayOf(2)) =
        ApplicationWire.Job(launch.identity().generation(), ApplicationWire.helloDigest(session.hello), sequence,
            ApplicationWire.jobId(launch.identity().generation(), sequence), applicationTestDigest('a'), applicationTestDigest('b'),
            deadline.identity, payload)

    fun permit(
        session: OwnedApplicationSession,
        sequence: Int = 1,
        deadline: OwnedApplicationDeadline = OwnedApplicationDeadline.afterMillis(5000),
        payload: ByteArray = byteArrayOf(2),
        beforeWrite: () -> Unit = {},
        validate: (ApplicationWire.Result) -> String = { "detached-result-${it.sequence()}" },
        beforePublication: () -> Unit = {},
    ) = OwnedApplicationJobPermit(session, job(session, sequence, deadline, payload), deadline, beforeWrite, validate, beforePublication)
}

internal class ApplicationFakeSession(val launch: ApplicationWire.Launch) : OwnedProcessSession {
    private val lock = Object()
    private val output = ArrayDeque<OwnedSessionOutput>()
    private var received = 0L
    private var delivered = 0L
    private var input = 0L
    private var sequence = 0L
    private var pending: Long? = null
    private var state = OwnedSessionState.LIVE
    private var termination: OwnedSessionTermination? = null
    private var nativeFinal: OwnedSessionNativeFinal? = null
    private val failures = linkedSetOf<OwnedSessionReason>()
    private val decoder = ApplicationWire.Decoder(launch.limits().maxJobs(), launch.limits().inputLimit().toLong())
    private val jobs = mutableListOf<ApplicationWire.Job>()
    val arms = AtomicInteger()
    val writes = AtomicInteger()
    val disarms = AtomicInteger()
    val closes = AtomicInteger()
    val cancels = AtomicInteger()
    val outputPolls = AtomicInteger()
    val interruptedOutputPolls = AtomicInteger()
    val cancelled = CountDownLatch(1)
    val requestDecoded = CountDownLatch(1)
    @Volatile var armed = false
    @Volatile var lastDeadline: OwnedSessionDeadline? = null
    @Volatile var autoHello = true
    @Volatile var splitSize = OwnedSessionLimits.CHUNK
    @Volatile var blockAckUntilOutputDrained = false
    @Volatile var quarantineOnCancel = false
    @Volatile var beforeWrite: ((Int) -> OwnedSessionDelivery?)? = null
    @Volatile var beforeDisarm: (() -> Unit)? = null
    @Volatile var beforeClose: (() -> Unit)? = null
    @Volatile var onCancel: (() -> Unit)? = null
    @Volatile var onJob: ((ApplicationWire.Job) -> Unit)? = null
    @Volatile var firstOutput: (() -> ByteArray)? = null
    val hello = ApplicationWire.Hello(launch.identity(), ApplicationWire.launchDigest(launch), "2".repeat(32),
        "fake-api-1", true, true)

    fun startOutput() { if (autoHello) emit(firstOutput?.invoke() ?: ApplicationWire.frame(hello)) }
    fun jobs(): List<ApplicationWire.Job> = synchronized(lock) { jobs.toList() }
    fun receivedBytes(): Long = synchronized(lock) { received }
    fun deliveredBytes(): Long = synchronized(lock) { delivered }

    fun result(job: ApplicationWire.Job, payload: ByteArray = byteArrayOf(3)) = ApplicationWire.Result(
        job.generation(), job.helloDigest(), job.sequence(), job.jobId(), ApplicationWire.requestDigest(job),
        job.originalKey(), job.effectiveKey(), hello.loaderToken(), ApplicationWire.ResultState.APPLICATION_SEALED, payload,
    )

    fun emit(bytes: ByteArray, stream: OwnedSessionStream = OwnedSessionStream.STDOUT) = synchronized(lock) {
        check(splitSize in 1..OwnedSessionLimits.CHUNK && bytes.size <= launch.limits().outputLimit() - received)
        var at = 0
        while (at < bytes.size) {
            val end = minOf(bytes.size, at + splitSize)
            output.addLast(OwnedSessionOutput(stream, bytes.copyOfRange(at, end)))
            received += end - at
            at = end
        }
        lock.notifyAll()
    }

    override fun snapshot(): OwnedSessionSnapshot = synchronized(lock) {
        OwnedSessionSnapshot(state, true, applicationTestIdentity, received, delivered, received - delivered,
            0, output.size, input, pending, nativeFinal, termination, failures)
    }

    override fun arm(epoch: Long, deadline: OwnedSessionDeadline): OwnedSessionOperation {
        val seq = synchronized(lock) {
            if (state != OwnedSessionState.LIVE) return OwnedSessionOperation.Rejected(listOf(OwnedSessionReason.NOT_LIVE))
            check(!armed)
            armed = true
            lastDeadline = deadline
            arms.incrementAndGet()
            ++sequence
        }
        return OwnedSessionOperation.Acknowledged(OwnedSessionAck(OwnedSessionControl.ARM, seq, epoch, 0))
    }

    override fun write(bytes: ByteArray): OwnedSessionOperation {
        val seq = synchronized(lock) {
            if (state != OwnedSessionState.LIVE) return OwnedSessionOperation.Rejected(listOf(OwnedSessionReason.NOT_LIVE))
            check(armed && bytes.size in 1..OwnedSessionLimits.CHUNK)
            (++sequence).also { pending = it }
        }
        val call = writes.incrementAndGet()
        val failure = beforeWrite?.invoke(call)
        if (failure != null) {
            synchronized(lock) { pending = null }
            return OwnedSessionOperation.Failed(listOf(OwnedSessionReason.CONTROL_IO), seq, failure)
        }
        decoder.accept(bytes) { message ->
            val job = message as ApplicationWire.Job
            synchronized(lock) { jobs.add(job) }
            requestDecoded.countDown()
            onJob?.invoke(job) ?: emit(ApplicationWire.frame(result(job)))
        }
        if (blockAckUntilOutputDrained && synchronized(lock) { jobs.isNotEmpty() }) awaitDrained()
        return synchronized(lock) {
            input += bytes.size
            pending = null
            lock.notifyAll()
            OwnedSessionOperation.Acknowledged(OwnedSessionAck(OwnedSessionControl.DATA, seq, bytes.size.toLong(), input))
        }
    }

    override fun disarm(epoch: Long): OwnedSessionOperation {
        beforeDisarm?.invoke()
        return synchronized(lock) {
            if (state != OwnedSessionState.LIVE) return OwnedSessionOperation.Rejected(listOf(OwnedSessionReason.NOT_LIVE))
            check(armed)
            armed = false
            disarms.incrementAndGet()
            OwnedSessionOperation.Acknowledged(OwnedSessionAck(OwnedSessionControl.DISARM, ++sequence, epoch, 0))
        }
    }

    override fun closeStdin(): OwnedSessionOperation {
        closes.incrementAndGet()
        beforeClose?.invoke()
        check(!armed)
        val seq = synchronized(lock) { ++sequence }
        end()
        return OwnedSessionOperation.Acknowledged(OwnedSessionAck(OwnedSessionControl.CLOSE_STDIN, seq, 0, 0))
    }

    override fun cancel() {
        cancels.incrementAndGet()
        cancelled.countDown()
        onCancel?.invoke()
        end(quarantine = quarantineOnCancel, reason = OwnedSessionReason.CANCELLED)
    }

    /** A synthetic fake termination; never an actual compiler/native execution observation. */
    fun end(quarantine: Boolean = false, reason: OwnedSessionReason? = null) = synchronized(lock) {
        reason?.let(failures::add)
        state = if (quarantine) OwnedSessionState.QUARANTINED else OwnedSessionState.TERMINAL
        if (quarantine) failures.add(OwnedSessionReason.CLEANUP_UNVERIFIED)
        nativeFinal = OwnedSessionNativeFinal(0, OwnedSessionFlags.COMPLETED, 0, input, input, input,
            received, received, sequence, sequence, arms.get().toLong())
        termination = OwnedSessionTermination(state, failures, nativeFinal, applicationTestIdentity,
            cleanupAcknowledged = !quarantine, captureComplete = !quarantine)
        lock.notifyAll()
    }

    override fun pollOutput(waitMillis: Long): OwnedSessionOutput? {
        outputPolls.incrementAndGet()
        if (Thread.currentThread().isInterrupted) interruptedOutputPolls.incrementAndGet()
        return synchronized(lock) {
            if (output.isEmpty() && termination == null && waitMillis > 0) lock.wait(waitMillis)
            output.pollFirst()?.also { delivered += it.size; lock.notifyAll() }
        }
    }

    override fun awaitTerminal(waitMillis: Long): OwnedSessionTermination? = synchronized(lock) {
        if (termination == null && waitMillis > 0) lock.wait(waitMillis)
        termination
    }

    private fun awaitDrained() = synchronized(lock) {
        val until = System.nanoTime() + 2_000_000_000L
        while (received != delivered && System.nanoTime() - until < 0) lock.wait(10)
        check(received == delivered)
    }
}

internal fun applicationAwait(latch: CountDownLatch) { assertTrue(latch.await(3, TimeUnit.SECONDS)) }
internal fun applicationEventually(predicate: () -> Boolean) {
    val until = System.nanoTime() + 3_000_000_000L
    while (!predicate() && System.nanoTime() - until < 0) Thread.sleep(2)
    assertTrue(predicate())
}

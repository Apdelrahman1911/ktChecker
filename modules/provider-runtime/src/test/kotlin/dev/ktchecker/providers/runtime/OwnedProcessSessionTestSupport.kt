package dev.ktchecker.providers.runtime

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

internal val liveTestNonce: ByteArray get() = ByteArray(16) { (it + 1).toByte() }
internal val liveTestIdentity = OwnedSupervisorIdentity(OwnedLiveSupervisorBundleFormat.VERSION, "linux-x86_64",
    "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64))

internal fun liveTestConfiguration(inputLimit: Int = 32_768, outputLimit: Int = 524_288, millis: Long = 5000) =
    OwnedSessionConfiguration(listOf(Path.of("never-executed-fake-worker").toAbsolutePath().toString()),
        Path.of(".").toAbsolutePath(), emptyMap(), OwnedSessionDeadline.afterMillis(millis), inputLimit, outputLimit)

internal fun liveFrame(tag: Int, payload: ByteArray): ByteArray = byteArrayOf(tag.toByte()) +
    ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size).array() + payload

internal fun liveBound(nonce: ByteArray = liveTestNonce, millis: Long = 5000, inputLimit: Int = 32_768, outputLimit: Int = 524_288): ByteArray =
    liveFrame(1, ByteBuffer.allocate(40).order(ByteOrder.BIG_ENDIAN).put(nonce).putLong(millis)
        .putLong(inputLimit.toLong()).putLong(outputLimit.toLong()).array())

internal fun liveAck(control: OwnedSessionControl, sequence: Long, a: Long = 0, b: Long = 0, reserved: Int = 0): ByteArray =
    liveFrame(4, ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN).putInt(control.tag).putInt(reserved)
        .putLong(sequence).putLong(a).putLong(b).array())

internal fun liveFinal(
    cause: Int = 0, flags: Int = OwnedSessionFlags.COMPLETED, exit: Int = 23,
    accepted: Long = 0, written: Long = accepted, inputAcked: Long = written,
    observed: Long = 0, captured: Long = observed, requests: Long = 0,
    acks: Long = requests, epoch: Long = 0, reserved: Int = 0,
): ByteArray = liveFrame(5, ByteBuffer.allocate(80).order(ByteOrder.BIG_ENDIAN)
    .putInt(cause).putInt(flags).putInt(exit).putInt(reserved).putLong(accepted).putLong(written).putLong(inputAcked)
    .putLong(observed).putLong(captured).putLong(requests).putLong(acks).putLong(epoch).array())

internal class LiveTestImage : SupervisorImageHandle {
    override val executable: Path = Path.of("never-executed-fake-supervisor").toAbsolutePath()
    override val identity = liveTestIdentity
    val verifies = AtomicInteger()
    val closes = AtomicInteger()
    val cleaned = AtomicBoolean()
    @Volatile var failPostExitVerify = false
    @Volatile var cleanupSucceeds = true
    override fun verify() {
        if (verifies.incrementAndGet() > 1 && failPostExitVerify) throw IllegalStateException("not retained as a reason")
    }
    override fun closeSafely(): Boolean {
        closes.incrementAndGet()
        val succeeded = cleanupSucceeds
        if (succeeded) cleaned.set(true)
        return succeeded
    }
}

/** In-memory, bounded fake pipe; never launches/signals an OS process. */
internal class LiveTestPipe : InputStream() {
    private val lock = Object()
    private val chunks = ArrayDeque<ByteArray>()
    private var headOffset = 0
    private var retained = 0
    private var ended = false
    private var closed = false
    @Volatile var closeGate: CountDownLatch? = null
    val closeEntered = CountDownLatch(1)
    @Volatile var deliveryGate: CountDownLatch? = null
    val deliveryEntered = CountDownLatch(1)

    fun emit(bytes: ByteArray) = synchronized(lock) {
        check(!ended && !closed)
        check(bytes.size <= 1_048_576 - retained && chunks.size < 2048)
        chunks.addLast(bytes.clone())
        retained += bytes.size
        lock.notifyAll()
    }

    fun finish() = synchronized(lock) { ended = true; lock.notifyAll() }

    override fun read(): Int {
        val byte = ByteArray(1)
        return if (read(byte, 0, 1) == -1) -1 else byte[0].toInt() and 255
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        val read = readUnderLock(bytes, offset, length)
        if (read > 0) deliveryGate?.let {
            deliveryEntered.countDown()
            check(it.await(10, TimeUnit.SECONDS)) // Outside the fake pipe lock: EOF/exit can still progress.
        }
        return read
    }

    private fun readUnderLock(bytes: ByteArray, offset: Int, length: Int): Int = synchronized(lock) {
        if (length == 0) return@synchronized 0
        val started = System.nanoTime()
        while (chunks.isEmpty() && !ended && !closed) {
            if (System.nanoTime() - started > 10_000_000_000L) throw IllegalStateException("bounded fake-pipe timeout")
            lock.wait(20)
        }
        if (closed || chunks.isEmpty()) return@synchronized -1
        val head = chunks.getFirst()
        val count = minOf(length, head.size - headOffset)
        System.arraycopy(head, headOffset, bytes, offset, count)
        headOffset += count
        retained -= count
        if (headOffset == head.size) { chunks.removeFirst(); headOffset = 0 }
        count
    }

    override fun close() {
        closeEntered.countDown()
        closeGate?.let { check(it.await(10, TimeUnit.SECONDS)) }
        synchronized(lock) { closed = true; chunks.clear(); retained = 0; lock.notifyAll() }
    }
}

/** Tiny deterministic protocol model for JVM tests only, not a native-execution/cleanup qualification. */
internal class LiveTestChannel(
    private val configuration: OwnedSessionConfiguration,
    private val generationMillis: Long,
    private val nonce: ByteArray,
) : SupervisorChannel {
    override val frames = LiveTestPipe()
    override val diagnostics = LiveTestPipe()
    private val lock = Any()
    private val ownerBytes = ByteArrayOutputStream()
    private var offset = 0
    private var preamble = false
    private var boundEmitted = false
    private var requests = 0L
    private var acks = 0L
    private var epoch = 0L
    private var accepted = 0L
    private var written = 0L
    private var inputAcked = 0L
    private var captured = 0L
    private val ended = AtomicBoolean()
    private val exit = AtomicInteger(-1)
    val inputSeen = CountDownLatch(1)
    val inputAckEmitted = CountDownLatch(1)
    val preambleSeen = CountDownLatch(1)
    val cancels = AtomicInteger()
    val controlsClosed = AtomicBoolean()
    @Volatile var withholdDataAck = false
    @Volatile var partialWriteBytes = 0
    @Volatile var blockDataWrite: CountDownLatch? = null
    @Volatile var afterDataAckBeforeWriteReturns: CountDownLatch? = null
    @Volatile var withholdDisarmAck = false
    @Volatile var withholdBound = false
    @Volatile var finishOnCancel = true
    @Volatile var finishOnClose = true
    @Volatile var echoData = false
    @Volatile var afterBound: ((LiveTestChannel) -> Unit)? = null
    @Volatile var beforeFinalExit: (() -> Unit)? = null
    @Volatile var exitObservationGate: CountDownLatch? = null
    val exitObservationEntered = CountDownLatch(1)

    override val control = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()))
        override fun write(bytes: ByteArray, start: Int, length: Int) {
            synchronized(lock) {
                check(length <= 262_144 - ownerBytes.size())
                ownerBytes.write(bytes, start, length)
                consume()
                if (offset > 0) {
                    val remaining = ownerBytes.toByteArray().copyOfRange(offset, ownerBytes.size())
                    ownerBytes.reset()
                    ownerBytes.write(remaining)
                    offset = 0
                }
            }
        }
        override fun close() { controlsClosed.set(true) }
    }

    override fun alive(): Boolean = exit.get() == -1
    override fun exitCode(): Int? {
        exitObservationGate?.let { exitObservationEntered.countDown(); check(it.await(10, TimeUnit.SECONDS)) }
        return exit.get().takeIf { it != -1 }
    }
    override fun awaitExit(millis: Long): Boolean {
        val started = System.nanoTime()
        while (alive() && System.nanoTime() - started < millis * 1_000_000) Thread.sleep(1)
        return !alive()
    }

    private fun consume() {
        val bytes = ownerBytes.toByteArray()
        if (!preamble) {
            if (bytes.size < 24) return
            assertContentEquals("KTCCTL2\n".toByteArray() + nonce, bytes.copyOfRange(0, 24))
            offset = 24
            preamble = true
            preambleSeen.countDown()
            if (!withholdBound) releaseBound()
            afterBound?.invoke(this)
        }
        while (bytes.size - offset >= 5) {
            val tag = bytes[offset].toInt() and 255
            val length = ByteBuffer.wrap(bytes, offset + 1, 4).order(ByteOrder.BIG_ENDIAN).int
            check(length in 0..8200)
            if (bytes.size - offset - 5 < length) return
            val body = ByteBuffer.wrap(bytes, offset + 5, length).order(ByteOrder.BIG_ENDIAN)
            offset += 5 + length
            if (tag == 127) {
                assertEquals(0, length)
                cancels.incrementAndGet()
                if (finishOnCancel) finish(cancelled = true)
                continue
            }
            val control = OwnedSessionControl.entries.single { it.tag == tag }
            val sequence = body.long
            assertEquals(requests + 1, sequence)
            requests++
            when (control) {
                OwnedSessionControl.ARM -> {
                    epoch = body.long
                    assertTrue(body.long in 1..OwnedSessionLimits.MAX_MILLIS)
                    sendAck(control, sequence, epoch, 0)
                }
                OwnedSessionControl.DATA -> {
                    val data = ByteArray(length - 8).also { body.get(it) }
                    accepted += data.size
                    inputSeen.countDown()
                    blockDataWrite?.let { check(it.await(10, TimeUnit.SECONDS)) }
                    if (withholdDataAck) written += minOf(partialWriteBytes, data.size)
                    else {
                        written += data.size
                        sendAck(control, sequence, data.size.toLong(), written)
                        inputAckEmitted.countDown()
                        afterDataAckBeforeWriteReturns?.let { check(it.await(10, TimeUnit.SECONDS)) }
                        if (echoData) emitOutput(data)
                    }
                }
                OwnedSessionControl.DISARM -> {
                    val requestedEpoch = body.long
                    if (!withholdDisarmAck) sendAck(control, sequence, requestedEpoch, 0)
                }
                OwnedSessionControl.CLOSE_STDIN -> {
                    sendAck(control, sequence, 0, 0)
                    if (finishOnClose) finish(cancelled = false)
                }
            }
        }
    }

    private fun sendAck(control: OwnedSessionControl, sequence: Long, a: Long, b: Long) {
        frames.emit(liveAck(control, sequence, a, b))
        acks++
        if (control == OwnedSessionControl.DATA) inputAcked += a
    }

    fun releaseBound() = synchronized(lock) {
        check(preamble)
        if (!boundEmitted) {
            boundEmitted = true
            frames.emit(OwnedSessionWire.outputMagic + liveBound(nonce, generationMillis, configuration.inputLimit, configuration.outputLimit))
        }
    }

    fun acknowledgePendingData() = synchronized(lock) {
        check(!ended.get() && withholdDataAck && requests == acks + 1 && accepted > inputAcked)
        val size = accepted - inputAcked
        written = accepted
        sendAck(OwnedSessionControl.DATA, requests, size, written)
        inputAckEmitted.countDown()
    }

    fun ordinaryRequests(): Long = synchronized(lock) { requests }

    fun releaseTestGates() {
        blockDataWrite?.countDown()
        afterDataAckBeforeWriteReturns?.countDown()
        frames.closeGate?.countDown()
        diagnostics.closeGate?.countDown()
        frames.deliveryGate?.countDown()
        diagnostics.deliveryGate?.countDown()
        exitObservationGate?.countDown()
    }

    /** Test teardown/failure injection only. This is not native cleanup or a valid FINAL receipt. */
    fun endWithoutFinal() {
        ended.set(true)
        exit.set(0)
        frames.finish()
        diagnostics.finish()
    }

    fun emitOutput(bytes: ByteArray) = synchronized(lock) {
        frames.emit(liveFrame(2, bytes))
        captured += bytes.size
    }

    fun finish(cancelled: Boolean) = synchronized(lock) {
        if (!ended.compareAndSet(false, true)) return@synchronized
        var flags = if (!cancelled) OwnedSessionFlags.COMPLETED else
            OwnedSessionFlags.EXIT_OBSERVED or OwnedSessionFlags.LEADER_REAPED or OwnedSessionFlags.QUIESCENT or
                OwnedSessionFlags.GROUP_READY or OwnedSessionFlags.STDIN_CLOSED or OwnedSessionFlags.OUTPUT_EOF or OwnedSessionFlags.CANCELLED
        if (accepted > written) flags = flags or OwnedSessionFlags.INPUT_INCOMPLETE
        frames.emit(liveFinal(if (cancelled) 1 else 0, flags, if (cancelled) 143 else 23,
            accepted, written, inputAcked, captured, captured, requests, acks, epoch))
        frames.finish()
        diagnostics.finish()
        beforeFinalExit?.invoke()
        exit.set(0)
    }
}

internal class LiveTestRig(
    val slots: SupervisorSlots = SupervisorSlots(1),
    val image: LiveTestImage = LiveTestImage(),
    private val grace: Long = 1000,
    private val configure: (LiveTestChannel) -> Unit = {},
) {
    lateinit var channel: LiveTestChannel
        private set
    val transport = OwnedProcessSessionTransport(
        images = OwnedSessionImageSource { OwnedSessionImagePlan { image } },
        launcher = OwnedLiveSupervisorLauncher { _, config, millis, nonce ->
            LiveTestChannel(config, millis, nonce).also { channel = it; configure(it) }
        },
        slots = slots, cleanupGraceMillis = grace, nonceFactory = { liveTestNonce },
    )

    fun open(configuration: OwnedSessionConfiguration = liveTestConfiguration()): OwnedProcessSession {
        val opened = transport.open(configuration)
        if (opened !is OwnedSessionOpenResult.StartedTransport) fail("Fake transport was not started")
        awaitLive(opened.handle)
        return opened.handle
    }
}

internal fun awaitLive(handle: OwnedProcessSession) {
    val started = System.nanoTime()
    while (handle.snapshot().state == OwnedSessionState.OPENING && System.nanoTime() - started < 2_000_000_000L) Thread.sleep(1)
    assertEquals(OwnedSessionState.LIVE, handle.snapshot().state)
}

internal fun awaitCondition(condition: () -> Boolean) {
    val started = System.nanoTime()
    while (!condition() && System.nanoTime() - started < 3_000_000_000L) Thread.sleep(2)
    assertTrue(condition())
}

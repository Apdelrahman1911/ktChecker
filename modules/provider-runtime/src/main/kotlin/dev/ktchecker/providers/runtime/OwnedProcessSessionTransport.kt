package dev.ktchecker.providers.runtime

import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.EnumSet
import java.util.HexFormat
import java.util.concurrent.TimeUnit

/** Default-unused internal entry point. It changes no provider route, permission, or client cancellation. */
internal object OwnedProcessSessions {
    fun open(configuration: OwnedSessionConfiguration): OwnedSessionOpenResult =
        OwnedProcessSessionTransport().open(configuration)
}

/** Same signal-free channel as v1; no PID, destroy, descendant enumeration, or reconnect seam. */
internal fun interface OwnedLiveSupervisorLauncher {
    fun start(image: SupervisorImageHandle, configuration: OwnedSessionConfiguration,
              remainingGenerationMillis: Long, nonce: ByteArray): SupervisorChannel
}

/** Only a launcher that KNOWS no process was started may report this; other failures stay unresolved. */
internal class OwnedSessionNotStarted : Exception()

private object InstalledOwnedLiveSupervisorLauncher : OwnedLiveSupervisorLauncher {
    override fun start(image: SupervisorImageHandle, configuration: OwnedSessionConfiguration,
                       remainingGenerationMillis: Long, nonce: ByteArray): SupervisorChannel {
        val builder = try {
            val command = listOf(image.executable.toString(), "--ktc-owned-live-v2", remainingGenerationMillis.toString(),
                configuration.inputLimit.toString(), configuration.outputLimit.toString(),
                HexFormat.of().formatHex(nonce), "--") + configuration.command
            ProcessBuilder(command).directory(configuration.directory.toFile()).also {
                it.environment().clear()
                it.environment().putAll(configuration.environment)
            }
        } catch (_: Exception) { throw OwnedSessionNotStarted() }
        // Even a start() exception is conservatively unresolved; no numeric PID recovery or guessing.
        val process = builder.start()
        return object : SupervisorChannel {
            override val frames get() = process.inputStream
            override val diagnostics get() = process.errorStream
            override val control get() = process.outputStream
            override fun alive(): Boolean = process.isAlive
            override fun exitCode(): Int? = if (process.isAlive) null else process.exitValue()
            override fun awaitExit(millis: Long): Boolean = process.waitFor(millis, TimeUnit.MILLISECONDS)
        }
    }
}

/**
 * Source/image/launcher injections are internal byte/fake-test seams, never caller-selected production origins.
 * Slots are acquired before preparation. No process is launched by inspection/extraction alone.
 */
internal class OwnedProcessSessionTransport(
    private val images: OwnedSessionImageSource = InstalledOwnedSessionImageSource,
    private val launcher: OwnedLiveSupervisorLauncher = InstalledOwnedLiveSupervisorLauncher,
    private val slots: SupervisorSlots = OwnedSupervisorCapacity.slots,
    private val cleanupGraceMillis: Long = OwnedSessionLimits.CLEANUP_MILLIS,
    private val nonceFactory: () -> ByteArray = {
        ByteArray(16).also { bytes ->
            SecureRandom().nextBytes(bytes) // A zero nonce is rejected below, not an unbounded retry loop.
        }
    },
) {
    init { require(cleanupGraceMillis in 1..OwnedSessionLimits.CLEANUP_MILLIS) }

    fun open(configuration: OwnedSessionConfiguration): OwnedSessionOpenResult {
        fun reject(vararg reasons: OwnedSessionReason) = OwnedSessionOpenResult.Rejected(reasons.toList())
        if (configuration.generationDeadline.expired()) return reject(OwnedSessionReason.GENERATION_TIMEOUT)
        if (Thread.currentThread().isInterrupted) return reject(OwnedSessionReason.INTERRUPTED)
        val slot = slots.acquire() ?: return reject(OwnedSessionReason.CAPACITY)
        val plan: OwnedSessionImagePlan
        val nonce: ByteArray
        try {
            plan = images.load() // Contractually byte-only: no private image or process yet.
            nonce = nonceFactory().clone()
            require(nonce.size == 16 && nonce.any { it != 0.toByte() })
        } catch (_: Exception) {
            slot.close()
            return reject(OwnedSessionReason.BUNDLE_UNAVAILABLE)
        }
        if (configuration.generationDeadline.expired() || Thread.currentThread().isInterrupted) {
            slot.close()
            return reject(if (Thread.currentThread().isInterrupted) OwnedSessionReason.INTERRUPTED else OwnedSessionReason.GENERATION_TIMEOUT)
        }
        val image = try { plan.extract() }
        catch (_: Exception) {
            // Existing extraction exceptions contain no positive receipt that every artifact was removed.
            return reject(OwnedSessionReason.IMAGE_EXTRACTION, OwnedSessionReason.CLEANUP_UNVERIFIED)
        }
        fun rejectBeforeLaunch(reason: OwnedSessionReason): OwnedSessionOpenResult {
            val cleaned = try { image.closeSafely() } catch (_: Exception) { false }
            if (cleaned) slot.close()
            return if (cleaned) reject(reason) else reject(reason, OwnedSessionReason.IMAGE_CLEANUP, OwnedSessionReason.CLEANUP_UNVERIFIED)
        }
        try {
            require(image.identity.protocolVersion == OwnedLiveSupervisorBundleFormat.VERSION &&
                image.identity.target in OwnedLiveSupervisorBundleFormat.targets)
            image.verify()
        } catch (_: Exception) { return rejectBeforeLaunch(OwnedSessionReason.IMAGE_VERIFY) }
        val remaining = configuration.generationDeadline.remainingMillis()
        if (remaining == 0L || Thread.currentThread().isInterrupted) {
            return rejectBeforeLaunch(if (Thread.currentThread().isInterrupted) OwnedSessionReason.INTERRUPTED else OwnedSessionReason.GENERATION_TIMEOUT)
        }
        val channel = try { launcher.start(image, configuration, remaining, nonce.clone()) }
        catch (_: OwnedSessionNotStarted) { return rejectBeforeLaunch(OwnedSessionReason.START_FAILED) }
        catch (_: Throwable) {
            // Unknown post-start state: retain slot AND private image. Never infer no child from an exception.
            return reject(OwnedSessionReason.START_UNKNOWN, OwnedSessionReason.CLEANUP_UNVERIFIED)
        }
        val handle = try { OwnedProcessSessionHandle(configuration, remaining, nonce, channel, image, slot, cleanupGraceMillis) }
        catch (_: Throwable) { return reject(OwnedSessionReason.PUMP_START, OwnedSessionReason.CLEANUP_UNVERIFIED) }
        handle.start()
        return OwnedSessionOpenResult.StartedTransport(handle)
    }
}

private class OwnedProcessSessionHandle(
    private val configuration: OwnedSessionConfiguration,
    private val generationMillis: Long,
    nonce: ByteArray,
    private val channel: SupervisorChannel,
    private val image: SupervisorImageHandle,
    private val slot: SupervisorSlot,
    private val cleanupGraceMillis: Long,
) : OwnedProcessSession, OwnedSessionWireListener {
    private val nonce = nonce.clone()
    private val lock = Object()
    private val reasons = EnumSet.noneOf(OwnedSessionReason::class.java)
    private val ledger = OwnedSessionLedger()
    private val output = ArrayDeque<OwnedSessionOutput>()
    private val workers = ArrayList<Thread>(5)
    private var state = OwnedSessionState.OPENING
    private var bound = false
    private var activeEpoch = 0L
    private var epochDeadline: OwnedSessionDeadline? = null
    private var stdinClosed = false
    private var cleanupDeadlineNanos: Long? = null
    private var cancelRequired = false
    private var stopWriter = false
    private var receivedOutput = 0L
    private var deliveredOutput = 0L
    private var retainedOutput = 0L
    private var discardedOutput = 0L
    private var nativeFinal: OwnedSessionNativeFinal? = null
    private var termination: OwnedSessionTermination? = null
    private var frameEof = false
    private var wireFailed = false
    private var frameDone = false
    private var diagnosticEof = false
    private var diagnosticClean = true
    private var diagnosticDone = false
    private var writerDone = false
    private var controlClosed = false
    private var closeStarted = false
    private var closeDone = false
    private var framesClosed = false
    private var diagnosticsClosed = false
    private var helperExit: Int? = null
    private var startupFinished = false
    private var helperStateReliable = true
    private var imageCleaned = false

    fun start() {
        try {
            // Monitor first: later thread-start failures still retain a deadline/cleanup owner.
            startWorker("monitor", ::monitor)
            startWorker("writer", ::writer)
            startWorker("frames") {
                try { OwnedSessionFrameReader(nonce, generationMillis, configuration.inputLimit, configuration.outputLimit, this).read(channel.frames) }
                catch (_: Throwable) { problem(OwnedSessionReason.FRAME_IO) }
                finally { synchronized(lock) { frameDone = true; lock.notifyAll() } }
            }
            startWorker("diagnostics", ::diagnostics)
        } catch (_: Throwable) {
            synchronized(lock) {
                retireLocked(OwnedSessionReason.PUMP_START)
                quarantineLocked(OwnedSessionReason.PUMP_START)
            }
        } finally { synchronized(lock) { startupFinished = true; lock.notifyAll() } }
    }

    private fun startWorker(role: String, body: () -> Unit): Thread {
        val worker = Thread({ body() }, "ktchecker-owned-live-$role").also { it.isDaemon = true }
        synchronized(lock) {
            check(workers.size < 5)
            workers.add(worker)
        }
        worker.start() // No state lock held across start; a failure never returns the slot.
        return worker
    }

    override fun snapshot(): OwnedSessionSnapshot = synchronized(lock) {
        OwnedSessionSnapshot(state, bound, image.identity, receivedOutput, deliveredOutput, retainedOutput,
            discardedOutput, output.size, ledger.ackedInput, ledger.pending?.sequence, nativeFinal, termination, reasons)
    }

    override fun arm(epoch: Long, deadline: OwnedSessionDeadline): OwnedSessionOperation = ordinary(OwnedSessionControl.ARM, epoch, deadline, null)
    override fun write(bytes: ByteArray): OwnedSessionOperation = ordinary(OwnedSessionControl.DATA, 0, null, bytes)
    override fun disarm(epoch: Long): OwnedSessionOperation = ordinary(OwnedSessionControl.DISARM, epoch, null, null)
    override fun closeStdin(): OwnedSessionOperation = ordinary(OwnedSessionControl.CLOSE_STDIN, 0, null, null)

    private fun ordinary(control: OwnedSessionControl, epoch: Long, requestedDeadline: OwnedSessionDeadline?, bytes: ByteArray?): OwnedSessionOperation {
        var interrupted = false
        val result = synchronized(lock) {
            checkDeadlinesLocked()
            fun reject(reason: OwnedSessionReason): OwnedSessionOperation = OwnedSessionOperation.Rejected(listOf(reason))
            if (state != OwnedSessionState.LIVE) return@synchronized reject(OwnedSessionReason.NOT_LIVE)
            if (ledger.pending != null) return@synchronized reject(OwnedSessionReason.BUSY)
            if (Thread.currentThread().isInterrupted) {
                retireLocked(OwnedSessionReason.INTERRUPTED)
                return@synchronized OwnedSessionOperation.Failed(reasons, null, OwnedSessionDelivery.NOT_SENT)
            }
            if (ledger.ackCount >= OwnedSessionLimits.REQUESTS) {
                retireLocked(OwnedSessionReason.CONTROL_LIMIT)
                return@synchronized OwnedSessionOperation.Failed(reasons, null, OwnedSessionDelivery.NOT_SENT)
            }
            when (control) {
                OwnedSessionControl.ARM -> {
                    if (activeEpoch != 0L) return@synchronized reject(OwnedSessionReason.ALREADY_ARMED)
                    if (ledger.ackedEpoch >= OwnedSessionLimits.EPOCHS) {
                        retireLocked(OwnedSessionReason.CONTROL_LIMIT)
                        return@synchronized OwnedSessionOperation.Failed(reasons, null, OwnedSessionDelivery.NOT_SENT)
                    }
                    if (epoch != ledger.ackedEpoch + 1) return@synchronized reject(OwnedSessionReason.WRONG_EPOCH)
                    if (requestedDeadline == null || requestedDeadline.expired()) {
                        retireLocked(OwnedSessionReason.EPOCH_TIMEOUT, deadline = requestedDeadline)
                        return@synchronized OwnedSessionOperation.Failed(reasons, null, OwnedSessionDelivery.NOT_SENT)
                    }
                }
                OwnedSessionControl.DATA -> {
                    if (stdinClosed) return@synchronized reject(OwnedSessionReason.STDIN_CLOSED)
                    if (activeEpoch == 0L) return@synchronized reject(OwnedSessionReason.NOT_ARMED)
                    if (bytes == null || bytes.size !in 1..OwnedSessionLimits.CHUNK) return@synchronized reject(OwnedSessionReason.INVALID_DATA)
                    if (bytes.size.toLong() > configuration.inputLimit - ledger.ackedInput) {
                        retireLocked(OwnedSessionReason.INPUT_LIMIT)
                        return@synchronized OwnedSessionOperation.Failed(reasons, null, OwnedSessionDelivery.NOT_SENT)
                    }
                }
                OwnedSessionControl.DISARM -> {
                    if (activeEpoch == 0L) return@synchronized reject(OwnedSessionReason.NOT_ARMED)
                    if (epoch != activeEpoch) return@synchronized reject(OwnedSessionReason.WRONG_EPOCH)
                }
                OwnedSessionControl.CLOSE_STDIN -> if (stdinClosed) return@synchronized reject(OwnedSessionReason.STDIN_CLOSED)
            }
            val deadline = requestedDeadline ?: epochDeadline ?: configuration.generationDeadline
            val request = OwnedSessionRequest(control, ledger.ackCount + 1, epoch, deadline, bytes)
            ledger.reserve(request)
            if (control == OwnedSessionControl.ARM) {
                activeEpoch = epoch
                epochDeadline = deadline // Before queueing/native ARM ACK; no budget refresh.
            }
            lock.notifyAll()
            while (request.result == null) {
                checkDeadlinesLocked()
                if (request.result != null) break
                try { lock.wait(5) }
                catch (_: InterruptedException) {
                    interrupted = true
                    retireLocked(OwnedSessionReason.INTERRUPTED, deadline = request.deadline)
                }
            }
            request.result!!
        }
        if (interrupted) Thread.currentThread().interrupt()
        return result
    }

    override fun cancel() {
        synchronized(lock) {
            if (state == OwnedSessionState.TERMINAL || state == OwnedSessionState.QUARANTINED) return
            retireLocked(OwnedSessionReason.CANCELLED)
        }
    }

    override fun pollOutput(waitMillis: Long): OwnedSessionOutput? {
        require(waitMillis in 0..1000)
        val started = System.nanoTime()
        var interrupted = false
        val item = synchronized(lock) {
            while (output.isEmpty() && termination == null && waitMillis > 0) {
                val remaining = waitMillis * 1_000_000 - (System.nanoTime() - started)
                if (remaining <= 0) break
                try { lock.wait(minOf(remaining / 1_000_000 + 1, 1000)) }
                catch (_: InterruptedException) { interrupted = true; break }
            }
            output.pollFirst()?.also {
                retainedOutput -= it.size
                deliveredOutput += it.size
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        return item
    }

    override fun awaitTerminal(waitMillis: Long): OwnedSessionTermination? {
        require(waitMillis in 0..OwnedSessionLimits.CLEANUP_MILLIS)
        val started = System.nanoTime()
        var interrupted = false
        val result = synchronized(lock) {
            while (termination == null && waitMillis > 0) {
                val remaining = waitMillis * 1_000_000 - (System.nanoTime() - started)
                if (remaining <= 0) break
                try { lock.wait(minOf(remaining / 1_000_000 + 1, OwnedSessionLimits.CLEANUP_MILLIS)) }
                catch (_: InterruptedException) { interrupted = true; break }
            }
            termination
        }
        if (interrupted) Thread.currentThread().interrupt()
        return result
    }

    override fun bound() = synchronized(lock) {
        sessionWireRequire(!bound)
        bound = true
        checkDeadlinesLocked()
        if (state == OwnedSessionState.OPENING) state = OwnedSessionState.LIVE
        lock.notifyAll()
    }

    override fun output(stream: OwnedSessionStream, bytes: ByteArray) = synchronized(lock) {
        sessionWireRequire(bytes.size in 1..OwnedSessionLimits.CHUNK && bytes.size.toLong() <= configuration.outputLimit - receivedOutput)
        receivedOutput += bytes.size
        if (state == OwnedSessionState.QUARANTINED || reasons.contains(OwnedSessionReason.OUTPUT_QUEUE) ||
            output.size >= OwnedSessionLimits.QUEUE_FRAMES || bytes.size.toLong() > OwnedSessionLimits.QUEUE_BYTES - retainedOutput) {
            discardedOutput += bytes.size
            retireLocked(OwnedSessionReason.OUTPUT_QUEUE)
        } else {
            output.addLast(OwnedSessionOutput(stream, bytes))
            retainedOutput += bytes.size
        }
        lock.notifyAll()
    }

    override fun ack(ack: OwnedSessionAck) = synchronized(lock) {
        ledger.acceptAck(ack)
        checkDeadlinesLocked()
        maybeCompleteLocked(ledger.pending!!)
        lock.notifyAll()
    }

    override fun terminal(final: OwnedSessionNativeFinal) = synchronized(lock) {
        sessionWireRequire(nativeFinal == null)
        ledger.validateFinal(final)
        // Native relative time may lag the original host budget. Preserve a deadline crossed
        // before this first FINAL observation, even when native cleanup itself was successful.
        checkDeadlinesLocked()
        nativeFinal = final
        addNativeReasonsLocked(final)
        retireLocked(null, sendCancel = false)
        lock.notifyAll()
    }

    override fun eof() = synchronized(lock) { frameEof = true; lock.notifyAll() }

    override fun problem(reason: OwnedSessionReason) = synchronized(lock) {
        wireFailed = true
        retireLocked(reason)
    }

    private fun maybeCompleteLocked(request: OwnedSessionRequest) {
        if (request.result != null || request.ack == null || !request.writeFinished) return
        checkDeadlinesLocked()
        if (state != OwnedSessionState.LIVE || request.result != null) return
        when (request.control) {
            OwnedSessionControl.DISARM -> { activeEpoch = 0; epochDeadline = null }
            OwnedSessionControl.CLOSE_STDIN -> stdinClosed = true
            else -> Unit
        }
        request.result = OwnedSessionOperation.Acknowledged(request.ack!!)
        ledger.releaseAcknowledged(request)
        lock.notifyAll()
    }

    private fun checkDeadlinesLocked(now: Long = System.nanoTime()) {
        if (state != OwnedSessionState.OPENING && state != OwnedSessionState.LIVE) return
        if (configuration.generationDeadline.expired(now)) retireLocked(OwnedSessionReason.GENERATION_TIMEOUT, now = now)
        else if (epochDeadline?.expired(now) == true) retireLocked(OwnedSessionReason.EPOCH_TIMEOUT, now = now)
    }

    private fun retireLocked(reason: OwnedSessionReason?, sendCancel: Boolean = true,
                             deadline: OwnedSessionDeadline? = null, now: Long = System.nanoTime()) {
        if (state == OwnedSessionState.TERMINAL || state == OwnedSessionState.QUARANTINED) return
        reason?.let(reasons::add)
        val grace = cleanupGraceMillis * 1_000_000
        val boundaries = listOfNotNull(cleanupDeadlineNanos, now + grace,
            configuration.generationDeadline.startedNanos + configuration.generationDeadline.durationMillis * 1_000_000 + grace,
            (deadline ?: epochDeadline)?.let { it.startedNanos + it.durationMillis * 1_000_000 + grace })
        cleanupDeadlineNanos = boundaries.minBy { it - now }
        state = OwnedSessionState.RETIRING
        if (sendCancel) cancelRequired = true
        stopWriter = true
        ledger.pending?.let { request ->
            if (request.result == null) request.result = OwnedSessionOperation.Failed(
                if (reasons.isEmpty()) listOf(OwnedSessionReason.NOT_LIVE) else reasons,
                request.sequence, if (request.writingStarted) OwnedSessionDelivery.MAY_HAVE_BEEN_WRITTEN else OwnedSessionDelivery.NOT_SENT,
            )
        }
        lock.notifyAll()
    }

    private fun writer() {
        try {
            OwnedSessionWire.writePreamble(channel.control, nonce)
            channel.control.flush()
            while (true) {
                var shouldCancel = false
                var shouldStop = false
                var armRemaining = 0L
                val request = synchronized(lock) {
                    while (ledger.pending?.writingStarted != false && !stopWriter && !cancelRequired) lock.wait(5)
                    checkDeadlinesLocked()
                    shouldCancel = cancelRequired
                    shouldStop = stopWriter
                    if (shouldCancel || shouldStop) null
                    else ledger.pending?.also {
                        if (it.control == OwnedSessionControl.ARM) armRemaining = it.deadline.remainingMillis()
                        if (it.deadline.expired() || (it.control == OwnedSessionControl.ARM && armRemaining == 0L)) {
                            retireLocked(if (it.deadline === configuration.generationDeadline) OwnedSessionReason.GENERATION_TIMEOUT
                                else OwnedSessionReason.EPOCH_TIMEOUT, deadline = it.deadline)
                            shouldCancel = true
                            shouldStop = true
                        } else it.writingStarted = true // Commit intent BEFORE first pipe write/possible concurrent ACK.
                    }?.takeUnless { shouldStop }
                }
                if (shouldCancel) { OwnedSessionWire.writeCancel(channel.control); channel.control.flush(); break }
                if (shouldStop) break
                if (request != null) {
                    request.writeTo(channel.control, armRemaining)
                    channel.control.flush()
                    synchronized(lock) {
                        request.writeFinished = true
                        maybeCompleteLocked(request)
                        lock.notifyAll()
                    }
                }
            }
        } catch (_: Throwable) {
            synchronized(lock) { retireLocked(OwnedSessionReason.CONTROL_IO) }
        } finally {
            try { channel.control.close(); synchronized(lock) { controlClosed = true } }
            catch (_: Throwable) { synchronized(lock) { retireLocked(OwnedSessionReason.CONTROL_IO) } }
            synchronized(lock) { writerDone = true; lock.notifyAll() }
        }
    }

    private fun diagnostics() {
        try {
            val buffer = ByteArray(OwnedSessionLimits.CHUNK)
            var received = 0
            var empty = 0
            while (received < OwnedSessionLimits.DIAGNOSTIC_BYTES) {
                val read = channel.diagnostics.read(buffer, 0, minOf(buffer.size, OwnedSessionLimits.DIAGNOSTIC_BYTES - received))
                if (read == -1) { synchronized(lock) { diagnosticEof = true }; break }
                if (read < -1 || read > minOf(buffer.size, OwnedSessionLimits.DIAGNOSTIC_BYTES - received)) throw IllegalStateException()
                if (read == 0) { if (++empty > 16) throw IllegalStateException(); continue }
                empty = 0
                received += read
                synchronized(lock) { diagnosticClean = false; retireLocked(OwnedSessionReason.DIAGNOSTICS) }
            }
        } catch (_: Throwable) {
            synchronized(lock) { diagnosticClean = false; retireLocked(OwnedSessionReason.DIAGNOSTIC_IO) }
        } finally { synchronized(lock) { diagnosticDone = true; lock.notifyAll() } }
    }

    private fun monitor() {
        val self = Thread.currentThread()
        try {
            while (true) {
                val observedExit = try { channel.exitCode() }
                catch (_: Throwable) {
                    synchronized(lock) { helperStateReliable = false; retireLocked(OwnedSessionReason.HELPER_STATE) }
                    null
                }
                var startClose = false
                synchronized(lock) {
                    // exitCode() is cooperative and time can also pass between monitor polls.
                    // Check the original host deadline BEFORE exit retirement changes state.
                    checkDeadlinesLocked()
                    if (observedExit != null && helperExit == null) {
                        helperExit = observedExit
                        retireLocked(if (observedExit == 0) null else OwnedSessionReason.HELPER_EXIT, sendCancel = false)
                    } else if (observedExit != null && observedExit != helperExit) {
                        helperStateReliable = false
                        retireLocked(OwnedSessionReason.HELPER_STATE)
                    }
                    checkDeadlinesLocked()
                    if (state == OwnedSessionState.RETIRING && cleanupExpiredLocked()) quarantineLocked(OwnedSessionReason.CLEANUP_UNVERIFIED)
                    if (!closeStarted && ((helperExit != null && frameDone && diagnosticDone && writerDone) || state == OwnedSessionState.QUARANTINED)) {
                        closeStarted = true
                        startClose = true
                    }
                }
                if (startClose) startCloseWorker()
                val allPumpsEnded = synchronized(lock) {
                    startupFinished && workers.none { it !== self && it.isAlive } &&
                        (state == OwnedSessionState.QUARANTINED || (writerDone && frameDone && diagnosticDone && closeDone))
                }
                if (observedExit != null && allPumpsEnded) {
                    val canVerify = synchronized(lock) { state == OwnedSessionState.RETIRING && closureFactsLocked() && !cleanupExpiredLocked() }
                    if (canVerify) {
                        var verified = false
                        try { image.verify(); verified = true }
                        catch (_: Throwable) { synchronized(lock) { reasons.add(OwnedSessionReason.IMAGE_VERIFY) } }
                        if (verified) {
                            val cleaned = try { image.closeSafely() } catch (_: Throwable) { false }
                            if (cleaned) imageCleaned = true
                            synchronized(lock) {
                                if (!cleaned) reasons.add(OwnedSessionReason.IMAGE_CLEANUP)
                                if (cleaned && state == OwnedSessionState.RETIRING && !cleanupExpiredLocked() && closureFactsLocked()) {
                                    val captured = nativeFinal!!.has(OwnedSessionFlags.OUTPUT_EOF) &&
                                        !nativeFinal!!.has(OwnedSessionFlags.OUTPUT_LIMIT) &&
                                        !nativeFinal!!.has(OwnedSessionFlags.IO_FAILED) && discardedOutput == 0L
                                    state = OwnedSessionState.TERMINAL
                                    termination = OwnedSessionTermination(state, reasons, nativeFinal, image.identity, true, captured)
                                    slot.close() // Real closure + all pumps closed + private image removed, not just a FINAL bit.
                                    lock.notifyAll()
                                    return
                                }
                            }
                        }
                    }
                    synchronized(lock) { quarantineLocked(OwnedSessionReason.CLEANUP_UNVERIFIED) }
                    // Late hygiene only. QUARANTINED and its immutable result/slot are NEVER rehabilitated.
                    if (!imageCleaned) imageCleaned = try { image.closeSafely() } catch (_: Throwable) { false }
                    if (imageCleaned) return
                }
                try { Thread.sleep(if (synchronized(lock) { state == OwnedSessionState.QUARANTINED }) 100 else 5) }
                catch (_: InterruptedException) { synchronized(lock) { retireLocked(OwnedSessionReason.INTERRUPTED) } }
            }
        } catch (_: Throwable) {
            synchronized(lock) {
                retireLocked(OwnedSessionReason.UNEXPECTED)
                quarantineLocked(OwnedSessionReason.UNEXPECTED)
            }
        }
    }

    private fun startCloseWorker() {
        try {
            startWorker("close") {
                try { channel.frames.close(); synchronized(lock) { framesClosed = true } }
                catch (_: Throwable) { synchronized(lock) { retireLocked(OwnedSessionReason.FRAME_IO) } }
                try { channel.diagnostics.close(); synchronized(lock) { diagnosticsClosed = true } }
                catch (_: Throwable) { synchronized(lock) { diagnosticClean = false; retireLocked(OwnedSessionReason.DIAGNOSTIC_IO) } }
                synchronized(lock) { closeDone = true; lock.notifyAll() }
            }
        } catch (_: Throwable) { synchronized(lock) { quarantineLocked(OwnedSessionReason.PUMP_START) } }
    }

    private fun cleanupExpiredLocked(now: Long = System.nanoTime()): Boolean =
        cleanupDeadlineNanos?.let { now - it >= 0 } == true

    private fun closureFactsLocked(): Boolean {
        val final = nativeFinal ?: return false
        return bound && frameEof && !wireFailed && helperStateReliable && helperExit == 0 && diagnosticEof && diagnosticClean &&
            controlClosed && framesClosed && diagnosticsClosed && final.has(OwnedSessionFlags.QUIESCENT) &&
            final.has(OwnedSessionFlags.STDIN_CLOSED) && !final.has(OwnedSessionFlags.SHUTDOWN_FAILED) &&
            !final.has(OwnedSessionFlags.OWNERSHIP_LOST)
    }

    private fun quarantineLocked(reason: OwnedSessionReason) {
        if (state == OwnedSessionState.TERMINAL || state == OwnedSessionState.QUARANTINED) return
        retireLocked(reason)
        reasons.add(OwnedSessionReason.CLEANUP_UNVERIFIED)
        state = OwnedSessionState.QUARANTINED
        termination = OwnedSessionTermination(state, reasons, nativeFinal, image.identity, false, false)
        lock.notifyAll()
    }

    private fun addNativeReasonsLocked(final: OwnedSessionNativeFinal) {
        val byFlag = listOf(
            OwnedSessionFlags.CANCELLED to OwnedSessionReason.CANCELLED,
            OwnedSessionFlags.TIMED_OUT to OwnedSessionReason.NATIVE_TIMEOUT,
            OwnedSessionFlags.OUTPUT_LIMIT to OwnedSessionReason.OUTPUT_LIMIT,
            OwnedSessionFlags.PARENT_LOST to OwnedSessionReason.NATIVE_PARENT_LOST,
            OwnedSessionFlags.IO_FAILED to OwnedSessionReason.NATIVE_IO,
            OwnedSessionFlags.EXEC_FAILED to OwnedSessionReason.NATIVE_EXEC,
            OwnedSessionFlags.PROTOCOL_FAILED to OwnedSessionReason.PROTOCOL,
            OwnedSessionFlags.OWNERSHIP_LOST to OwnedSessionReason.NATIVE_OWNERSHIP,
            OwnedSessionFlags.SHUTDOWN_FAILED to OwnedSessionReason.CLEANUP_UNVERIFIED,
            OwnedSessionFlags.INPUT_LIMIT to OwnedSessionReason.INPUT_LIMIT,
            OwnedSessionFlags.CONTROL_LIMIT to OwnedSessionReason.CONTROL_LIMIT,
            OwnedSessionFlags.INPUT_INCOMPLETE to OwnedSessionReason.INPUT_INCOMPLETE,
        )
        byFlag.forEach { (flag, reason) -> if (final.has(flag)) reasons.add(reason) }
        if (final.cause == 7) reasons.add(OwnedSessionReason.NATIVE_SETUP)
    }
}

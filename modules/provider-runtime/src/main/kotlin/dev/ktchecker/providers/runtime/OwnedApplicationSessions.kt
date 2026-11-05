package dev.ktchecker.providers.runtime

import dev.ktchecker.contracts.CancellationToken
import dev.ktchecker.workerprotocol.ApplicationWire
import java.nio.file.Path
import java.util.EnumSet

/** Capability-only entry point. The installed internal v2 transport remains the sole custody owner. */
object OwnedApplicationSessions {
    private val factory = OwnedApplicationSessionFactory()
    fun open(
        permit: OwnedApplicationGenerationPermit,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): OwnedApplicationOpenResult = factory.open(permit, cancellation)
}

/** Byte/fake-session injection is module-internal. There is no caller-selected public launcher. */
internal class OwnedApplicationSessionFactory(
    private val openTransport: (OwnedSessionConfiguration) -> OwnedSessionOpenResult = OwnedProcessSessions::open,
) {
    fun open(permit: OwnedApplicationGenerationPermit, cancellation: CancellationToken): OwnedApplicationOpenResult {
        // Refusing a foreign owner binding cannot consume or affect any legitimate invocation.
        if (permit.launch.identity().ownerDomain() != permit.owner.identity) return unavailable(
            OwnedApplicationReason.OWNER_MISMATCH, OwnedApplicationCleanupDisposition.NOT_REQUESTED)
        // Consume even an admitted prelaunch failure, but replay has no authority over the first consumer.
        if (!permit.consume()) return unavailable(OwnedApplicationReason.PERMIT_USED,
            OwnedApplicationCleanupDisposition.NOT_REQUESTED)
        val configuration: OwnedSessionConfiguration
        try {
            checkGeneration(permit, cancellation)
            ownerHook { permit.verifyBeforeStart() }
            checkGeneration(permit, cancellation)
            ownerHook { permit.verifyLive() }
            checkGeneration(permit, cancellation)
            configuration = OwnedSessionConfiguration(
                permit.launch.command(), Path.of(permit.launch.directory()), permit.launch.environment(),
                permit.originalDeadline.original, permit.launch.limits().inputLimit(), permit.launch.limits().outputLimit(),
            )
            checkGeneration(permit, cancellation)
        } catch (failure: ApplicationFailure) {
            return unavailable(failure.reason, OwnedApplicationCleanupDisposition.NOT_STARTED)
        } catch (_: Throwable) {
            return unavailable(OwnedApplicationReason.BINDING, OwnedApplicationCleanupDisposition.NOT_STARTED)
        }
        val opened = try { openTransport(configuration) }
        catch (_: Throwable) {
            // An exception is not evidence that nothing started. Never reacquire, retry or release custody here.
            return unavailable(OwnedApplicationReason.TRANSPORT_UNAVAILABLE, OwnedApplicationCleanupDisposition.UNVERIFIED)
        }
        return when (opened) {
            is OwnedSessionOpenResult.Rejected -> {
                val unresolved = opened.reasons.any { it in setOf(OwnedSessionReason.CLEANUP_UNVERIFIED,
                    OwnedSessionReason.START_UNKNOWN, OwnedSessionReason.PUMP_START, OwnedSessionReason.IMAGE_EXTRACTION) }
                unavailable(OwnedApplicationReason.TRANSPORT_UNAVAILABLE,
                    if (unresolved) OwnedApplicationCleanupDisposition.UNVERIFIED else OwnedApplicationCleanupDisposition.NOT_STARTED,
                    opened.reasons)
            }
            is OwnedSessionOpenResult.StartedTransport -> {
                try { LiveApplication(permit, cancellation, opened.handle).initialize() }
                catch (_: Throwable) {
                    // Construction/start failure after custody exists: cancel only through the actual handle.
                    try { opened.handle.cancel() } catch (_: Throwable) { }
                    unavailable(OwnedApplicationReason.OUTPUT_DRAIN_FAILED, OwnedApplicationCleanupDisposition.UNVERIFIED)
                }
            }
        }
    }

    private fun unavailable(reason: OwnedApplicationReason, disposition: OwnedApplicationCleanupDisposition,
                            transportReasons: Collection<OwnedSessionReason> = emptyList()): OwnedApplicationOpenResult.Unavailable {
        val reasons = if (disposition == OwnedApplicationCleanupDisposition.UNVERIFIED)
            listOf(reason, OwnedApplicationReason.CLEANUP_UNVERIFIED) else listOf(reason)
        return OwnedApplicationOpenResult.Unavailable(reasons,
            OwnedApplicationRetirement(disposition, reasons, transportReasons.map { it.code }))
    }
}

private enum class ApplicationPhase { OPENING, IDLE, BUSY, RETIRING }

private class ApplicationAttempt(
    val job: ApplicationWire.Job,
    val deadline: OwnedApplicationDeadline,
    val cancellation: CancellationToken,
    val epoch: Long,
) {
    var mayReceive = false
    var result: ApplicationWire.Result? = null
    var writeInProgress = false
    var mayHaveWritten = false
    var acknowledged = false
    fun delivery(): OwnedApplicationDelivery = if (writeInProgress || mayHaveWritten)
        OwnedApplicationDelivery.MAY_HAVE_BEEN_WRITTEN else OwnedApplicationDelivery.NOT_SENT
}

private class LiveApplication(
    private val permit: OwnedApplicationGenerationPermit,
    private val generationCancellation: CancellationToken,
    private val transport: OwnedProcessSession,
) : OwnedApplicationSession {
    private val lock = Object()
    private val launch = permit.launch
    private val decoder = ApplicationWire.Decoder(launch.limits().maxJobs() + 2, launch.limits().outputLimit().toLong())
    private val reasons = EnumSet.noneOf(OwnedApplicationReason::class.java)
    private val transportReasons = EnumSet.noneOf(OwnedSessionReason::class.java)
    private var phase = ApplicationPhase.OPENING
    private var pendingHello: ApplicationWire.Hello? = null
    private var validatedHello: ApplicationWire.Hello? = null
    private var current: ApplicationAttempt? = null
    private var completed = 0
    private var processedOutput = 0L
    private var partialFrame = false
    private var transportIdentity: OwnedSupervisorIdentity? = null
    private var cancelIssued = false
    private var cleanupEndNanos: Long? = null
    private var retirement: OwnedApplicationRetirement? = null
    private var pump: Thread? = null
    private var pumpDone = false

    override val hello: ApplicationWire.Hello
        get() = synchronized(lock) { checkNotNull(validatedHello) }

    fun initialize(): OwnedApplicationOpenResult {
        try {
            val thread = Thread({ drain() }, "ktchecker-owned-application-output").also { it.isDaemon = true }
            synchronized(lock) { pump = thread }
            try { thread.start() } catch (_: Throwable) { throw ApplicationFailure(OwnedApplicationReason.OUTPUT_DRAIN_FAILED) }
            val observed = awaitHello()
            checkActive(null)
            try { permit.validateHello(observed) }
            catch (failure: Throwable) { throw callbackFailure(failure, OwnedApplicationReason.HELLO) }
            checkActive(null)
            ownerHook { permit.verifyLive() }
            checkActive(null)
            awaitBoundary(null)
            val ready = OwnedApplicationOpenResult.Ready(this)
            checkActive(null)
            synchronized(lock) {
                checkStateLocked(null)
                checkFinalClockLocked(null)
                requireBoundaryLocked()
                validatedHello = observed
                phase = ApplicationPhase.IDLE
                lock.notifyAll()
            }
            return ready
        } catch (failure: ApplicationFailure) { poison(failure.reason) }
        catch (_: Throwable) { poison(OwnedApplicationReason.INTERNAL) }
        val cleanup = finishRetirement()
        return OwnedApplicationOpenResult.Unavailable(reasonSnapshot(), cleanup)
    }

    override fun <T : Any> submit(permit: OwnedApplicationJobPermit<T>, cancellation: CancellationToken): OwnedApplicationJobResult<T> {
        // A wrong target has no authority to consume a grant intended for another actual session.
        if (permit.generation !== this) return notAdmitted(OwnedApplicationReason.WRONG_GENERATION)
        if (!permit.consume()) return notAdmitted(OwnedApplicationReason.PERMIT_USED)
        val active = synchronized(lock) {
            val denial = when {
                Thread.currentThread() === pump -> OwnedApplicationReason.CONCURRENT_OPERATION
                phase != ApplicationPhase.IDLE || current != null ->
                    if (phase == ApplicationPhase.BUSY) OwnedApplicationReason.CONCURRENT_OPERATION else OwnedApplicationReason.NOT_READY
                completed >= launch.limits().maxJobs() -> OwnedApplicationReason.JOB_LIMIT
                else -> null
            }
            // A denied invocation neither owns nor retires the current invocation/generation.
            if (denial != null) return notAdmitted(denial)
            ApplicationAttempt(permit.job, permit.originalDeadline, cancellation, completed.toLong() + 1).also {
                current = it
                phase = ApplicationPhase.BUSY
                lock.notifyAll()
            }
        }
        try {
            checkActive(active)
            try { ApplicationWire.matchJob(launch, hello, active.job, active.epoch.toInt()) }
            catch (_: Throwable) { throw ApplicationFailure(OwnedApplicationReason.BINDING) }
            appRequire(active.job.deadlineIdentity() == active.deadline.identity, OwnedApplicationReason.BINDING)
            ownerHook { permit.verifyBeforeWrite() }
            checkActive(active)
            ownerHook { this.permit.verifyLive() }
            checkActive(active)
            val frame = ApplicationWire.frame(active.job)
            awaitBoundary(active)
            checkOperation(transport.arm(active.epoch, active.deadline.original), OwnedSessionControl.ARM, active.epoch)
            checkActive(active)
            var offset = 0
            while (offset < frame.size) {
                checkActive(active)
                val end = minOf(frame.size, offset + OwnedSessionLimits.CHUNK)
                val chunk = frame.copyOfRange(offset, end)
                synchronized(lock) {
                    checkStateLocked(active)
                    // A worker can finish before the final DATA ACK reaches this caller, but never
                    // legitimately before the final request chunk has even been submitted.
                    if (end == frame.size) active.mayReceive = true
                    active.writeInProgress = true
                }
                val operation = try { transport.write(chunk) }
                catch (_: Throwable) {
                    synchronized(lock) { active.mayHaveWritten = true; active.writeInProgress = false }
                    throw ApplicationFailure(OwnedApplicationReason.TRANSPORT_FAILED)
                }
                synchronized(lock) {
                    if (operation is OwnedSessionOperation.Acknowledged ||
                        operation is OwnedSessionOperation.Failed && operation.delivery == OwnedSessionDelivery.MAY_HAVE_BEEN_WRITTEN) {
                        active.mayHaveWritten = true
                    }
                    active.writeInProgress = false
                }
                checkOperation(operation, OwnedSessionControl.DATA, null)
                offset = end
            }
            synchronized(lock) { active.acknowledged = true; lock.notifyAll() }
            val result = awaitResult(active)
            checkActive(active)
            // This callback constructs ALL detached output, including provider diagnostics/coverage.
            // It remains inside the armed original request budget; no renderer runs after Accepted.
            val value = try { permit.validateResult(result) }
            catch (failure: Throwable) { throw callbackFailure(failure, OwnedApplicationReason.RESULT) }
            checkActive(active)
            ownerHook { permit.verifyBeforePublication() }
            checkActive(active)
            ownerHook { this.permit.verifyLive() }
            checkActive(active)
            awaitBoundary(active)
            checkOperation(transport.disarm(active.epoch), OwnedSessionControl.DISARM, active.epoch)
            // Native DISARM clears its epoch clock, NOT our original host deadline or authority.
            checkActive(active)
            ownerHook { permit.verifyBeforePublication() }
            checkActive(active)
            ownerHook { this.permit.verifyLive() }
            checkActive(active)
            awaitBoundary(active)
            val binding = OwnedApplicationJobBinding(result.generation(), result.sequence(), result.jobId(),
                result.helloDigest(), result.requestDigest())
            val accepted = OwnedApplicationJobResult.Accepted(value, binding)
            checkActive(active) // Includes cancellation AFTER complete detached return-object construction.
            synchronized(lock) {
                checkStateLocked(active)
                checkFinalClockLocked(active)
                requireBoundaryLocked()
                appRequire(active.acknowledged && active.result === result, OwnedApplicationReason.RESULT)
                completed++
                current = null
                phase = ApplicationPhase.IDLE // At the cap only retire/cancel is admissible; the worker awaits EOF.
                lock.notifyAll()
            }
            return accepted
        } catch (failure: ApplicationFailure) { poison(failure.reason) }
        catch (_: Throwable) { poison(OwnedApplicationReason.INTERNAL) }
        val delivery = synchronized(lock) { active.delivery() }
        val cleanup = finishRetirement()
        return OwnedApplicationJobResult.Unavailable(reasonSnapshot(), delivery, cleanup)
    }

    /** Per-call refusal only: no session-state mutation, transport query or custody claim. */
    private fun notAdmitted(reason: OwnedApplicationReason): OwnedApplicationJobResult.Unavailable =
        OwnedApplicationJobResult.Unavailable(listOf(reason), OwnedApplicationDelivery.NOT_SENT,
            OwnedApplicationRetirement(OwnedApplicationCleanupDisposition.NOT_REQUESTED, listOf(reason)))

    override fun cancel() { poison(OwnedApplicationReason.CANCELLED) }

    override fun retire(): OwnedApplicationRetirement {
        val close = synchronized(lock) {
            if (phase == ApplicationPhase.IDLE && Thread.currentThread() !== pump) {
                beginRetirementLocked()
                true
            } else false
        }
        if (close) {
            try {
                // Idle EOF, no new epoch/opcode. The drain watchdog bounds an unacknowledged close.
                checkOperation(transport.closeStdin(), OwnedSessionControl.CLOSE_STDIN, null)
            } catch (failure: ApplicationFailure) { poison(failure.reason) }
            catch (_: Throwable) { poison(OwnedApplicationReason.TRANSPORT_FAILED) }
        } else if (synchronized(lock) { phase != ApplicationPhase.RETIRING || Thread.currentThread() === pump }) {
            poison(OwnedApplicationReason.CONCURRENT_OPERATION)
        }
        return finishRetirement()
    }

    private fun awaitHello(): ApplicationWire.Hello {
        while (true) {
            checkActive(null)
            synchronized(lock) {
                checkStateLocked(null)
                val snapshot = inspectLocked()
                if (snapshot.state == OwnedSessionState.LIVE && snapshot.bound && pendingHello != null) return pendingHello!!
                pauseLocked()
            }
        }
    }

    private fun awaitResult(active: ApplicationAttempt): ApplicationWire.Result {
        while (true) {
            checkActive(active)
            synchronized(lock) {
                checkStateLocked(active)
                inspectLocked(requireLive = true)
                active.result?.let { return it }
                pauseLocked()
            }
        }
    }

    private fun awaitBoundary(active: ApplicationAttempt?) {
        while (true) {
            checkActive(active)
            synchronized(lock) {
                checkStateLocked(active)
                val snapshot = inspectLocked(requireLive = true)
                if (boundary(snapshot)) return
                pauseLocked()
            }
        }
    }

    private fun boundary(snapshot: OwnedSessionSnapshot): Boolean = !partialFrame &&
        snapshot.queuedFrames == 0 && snapshot.retainedOutput == 0L && snapshot.discardedOutput == 0L &&
        snapshot.receivedOutput == snapshot.deliveredOutput && snapshot.receivedOutput == processedOutput &&
        snapshot.outstandingSequence == null

    private fun requireBoundaryLocked() {
        appRequire(boundary(inspectLocked(requireLive = true)), OwnedApplicationReason.UNEXPECTED_OUTPUT)
    }

    private fun inspectLocked(requireLive: Boolean = false): OwnedSessionSnapshot {
        val snapshot = try { transport.snapshot() }
        catch (_: Throwable) { throw ApplicationFailure(OwnedApplicationReason.TRANSPORT_FAILED) }
        transportReasons.addAll(snapshot.reasons)
        snapshot.termination?.let { transportReasons.addAll(it.reasons) }
        if (transportIdentity == null) transportIdentity = snapshot.identity
        appRequire(snapshot.identity == transportIdentity, OwnedApplicationReason.TRANSPORT_FAILED)
        appRequire(snapshot.reasons.isEmpty() && snapshot.discardedOutput == 0L && snapshot.provisionalFinal == null &&
            snapshot.termination == null, OwnedApplicationReason.TRANSPORT_FAILED)
        appRequire(snapshot.state == OwnedSessionState.LIVE || !requireLive && snapshot.state == OwnedSessionState.OPENING,
            OwnedApplicationReason.TRANSPORT_NOT_LIVE)
        if (snapshot.state == OwnedSessionState.LIVE) appRequire(snapshot.bound, OwnedApplicationReason.TRANSPORT_NOT_LIVE)
        return snapshot
    }

    private fun checkActive(active: ApplicationAttempt?) {
        checkGeneration(permit, generationCancellation)
        active?.let {
            checkCancellation(it.cancellation)
            appRequire(!it.deadline.isExpired(), OwnedApplicationReason.REQUEST_DEADLINE)
        }
        synchronized(lock) { checkStateLocked(active); checkFinalClockLocked(active) }
    }

    private fun checkStateLocked(active: ApplicationAttempt?) {
        if (reasons.isNotEmpty()) throw ApplicationFailure(reasons.first())
        appRequire(if (active == null) phase == ApplicationPhase.OPENING else phase == ApplicationPhase.BUSY && current === active,
            OwnedApplicationReason.NOT_READY)
    }

    private fun checkFinalClockLocked(active: ApplicationAttempt?) {
        appRequire(!permit.originalDeadline.isExpired(), OwnedApplicationReason.GENERATION_DEADLINE)
        appRequire(active == null || !active.deadline.isExpired(), OwnedApplicationReason.REQUEST_DEADLINE)
        appRequire(!Thread.currentThread().isInterrupted, OwnedApplicationReason.INTERRUPTED)
    }

    private fun checkOperation(operation: OwnedSessionOperation, expected: OwnedSessionControl, epoch: Long?) {
        when (operation) {
            is OwnedSessionOperation.Acknowledged -> appRequire(operation.ack.control == expected &&
                (epoch == null || operation.ack.valueA == epoch && operation.ack.valueB == 0L), OwnedApplicationReason.TRANSPORT_FAILED)
            is OwnedSessionOperation.Rejected -> {
                synchronized(lock) { transportReasons.addAll(operation.reasons) }
                throw ApplicationFailure(OwnedApplicationReason.TRANSPORT_FAILED)
            }
            is OwnedSessionOperation.Failed -> {
                synchronized(lock) { transportReasons.addAll(operation.reasons) }
                throw ApplicationFailure(OwnedApplicationReason.TRANSPORT_FAILED)
            }
        }
    }

    /** Exactly one dedicated parser/drainer; it runs during writes AND trusted validation callbacks. */
    private fun drain() {
        try {
            while (true) {
                val active = synchronized(lock) { current }
                val retiring = synchronized(lock) { phase == ApplicationPhase.RETIRING }
                if (!retiring) {
                    try {
                        checkGeneration(permit, generationCancellation)
                        active?.let {
                            try {
                                // This callback belongs to a sampled request which can become stale.
                                // Classify its failure without synthesizing a pump interrupt first.
                                checkCancellation(it.cancellation, deferCallbackInterrupt = true)
                                appRequire(!it.deadline.isExpired(), OwnedApplicationReason.REQUEST_DEADLINE)
                            } catch (failure: ApplicationFailure) {
                                // This check can finish after its request was accepted or replaced.
                                poison(failure.reason, onlyIfCurrent = it,
                                    restoreInterrupt = failure.restoreInterruptOnPoison)
                            }
                        }
                    } catch (failure: ApplicationFailure) { poison(failure.reason) }
                }
                val output = transport.pollOutput(10)
                if (output != null) {
                    try { receive(output) }
                    catch (failure: ApplicationFailure) { poison(failure.reason) }
                    catch (_: Throwable) { poison(OwnedApplicationReason.PROTOCOL) }
                }
                val snapshot = transport.snapshot()
                synchronized(lock) {
                    transportReasons.addAll(snapshot.reasons)
                    snapshot.termination?.let { transportReasons.addAll(it.reasons) }
                }
                if (synchronized(lock) { phase != ApplicationPhase.RETIRING } &&
                    (snapshot.state !in setOf(OwnedSessionState.OPENING, OwnedSessionState.LIVE) ||
                        snapshot.reasons.isNotEmpty() || snapshot.discardedOutput != 0L || snapshot.provisionalFinal != null)) {
                    poison(OwnedApplicationReason.TRANSPORT_NOT_LIVE)
                }
                if (snapshot.termination != null && snapshot.queuedFrames == 0 && snapshot.retainedOutput == 0L) {
                    synchronized(lock) {
                        try { if (!decoder.isFailed()) decoder.endOfInput() }
                        catch (_: Throwable) { reasons.add(OwnedApplicationReason.PROTOCOL) }
                        partialFrame = decoder.hasPartialFrame()
                    }
                    return
                }
                if (synchronized(lock) { cleanupEndNanos?.let { System.nanoTime() - it >= 0 } == true }) {
                    poison(OwnedApplicationReason.OUTPUT_DRAIN_UNRESOLVED)
                    return
                }
            }
        } catch (_: Throwable) { poison(OwnedApplicationReason.OUTPUT_DRAIN_FAILED) }
        finally { synchronized(lock) { pumpDone = true; lock.notifyAll() } }
    }

    private fun receive(output: OwnedSessionOutput) = synchronized(lock) {
        try { receiveLocked(output) }
        catch (failure: ApplicationFailure) {
            // Poison under the SAME lock that exposed a provisional HELLO/result. Otherwise a
            // valid prefix followed by a rejected second frame could win a publication race.
            reasons.add(failure.reason)
            beginRetirementLocked()
            throw failure
        } catch (_: Throwable) {
            reasons.add(OwnedApplicationReason.PROTOCOL)
            beginRetirementLocked()
            throw ApplicationFailure(OwnedApplicationReason.PROTOCOL)
        }
    }

    private fun receiveLocked(output: OwnedSessionOutput) {
        processedOutput += output.size
        if (phase == ApplicationPhase.RETIRING) {
            // During failed-attempt cancellation drain/discard only. Healthy idle EOF has no output.
            appRequire(cancelIssued, OwnedApplicationReason.UNEXPECTED_OUTPUT)
            lock.notifyAll()
            return
        }
        appRequire(output.stream == OwnedSessionStream.STDOUT, OwnedApplicationReason.UNEXPECTED_OUTPUT)
        appRequire(if (phase == ApplicationPhase.OPENING) pendingHello == null else
            phase == ApplicationPhase.BUSY && current?.mayReceive == true && current?.result == null,
            OwnedApplicationReason.UNEXPECTED_OUTPUT)
        var consumerReason: OwnedApplicationReason? = null
        try {
            decoder.accept(output.bytes) { message ->
                try { receiveMessageLocked(message) }
                catch (failure: ApplicationFailure) { consumerReason = failure.reason; throw failure }
            }
        } catch (_: Throwable) {
            // The wire codec deliberately erases consumer exceptions. Preserve ONLY our fixed code.
            throw ApplicationFailure(consumerReason ?: OwnedApplicationReason.PROTOCOL)
        } finally {
            partialFrame = decoder.hasPartialFrame()
            lock.notifyAll()
        }
        appRequire(!(pendingHello != null && phase == ApplicationPhase.OPENING || current?.result != null) || !partialFrame,
            OwnedApplicationReason.UNEXPECTED_OUTPUT)
    }

    private fun receiveMessageLocked(message: ApplicationWire.Message) {
        when (message) {
            is ApplicationWire.Hello -> {
                appRequire(phase == ApplicationPhase.OPENING && pendingHello == null, OwnedApplicationReason.PROTOCOL)
                try { ApplicationWire.matchHello(launch, message) }
                catch (_: Throwable) { throw ApplicationFailure(OwnedApplicationReason.HELLO) }
                pendingHello = message
            }
            is ApplicationWire.Result -> {
                val active = current
                appRequire(phase == ApplicationPhase.BUSY && active != null && active.mayReceive && active.result == null,
                    OwnedApplicationReason.PROTOCOL)
                try { ApplicationWire.matchResult(launch, checkNotNull(validatedHello), active!!.job, message) }
                catch (_: Throwable) { throw ApplicationFailure(OwnedApplicationReason.RESULT) }
                active!!.result = message
            }
            is ApplicationWire.Fatal -> throw ApplicationFailure(OwnedApplicationReason.WORKER_FATAL)
            else -> throw ApplicationFailure(OwnedApplicationReason.PROTOCOL)
        }
    }

    private fun beginRetirementLocked() {
        if (phase != ApplicationPhase.RETIRING) {
            phase = ApplicationPhase.RETIRING // Revoke before any transport method/callback.
            val now = System.nanoTime()
            val grace = OwnedSessionLimits.CLEANUP_MILLIS * 1_000_000
            val clocks = listOfNotNull(permit.originalDeadline.original, current?.deadline?.original)
            cleanupEndNanos = (listOf(now + grace) + clocks.map { it.startedNanos + it.durationMillis * 1_000_000 + grace })
                .minBy { it - now }
            lock.notifyAll()
        }
    }

    private fun poison(reason: OwnedApplicationReason, onlyIfCurrent: ApplicationAttempt? = null,
                       restoreInterrupt: Boolean = false) {
        val sendCancel = synchronized(lock) {
            // Sampling a request is not authority to revoke a later request or the idle generation.
            // Test identity/phase and record revocation in this SAME critical section.
            if (onlyIfCurrent != null &&
                (phase != ApplicationPhase.BUSY || current !== onlyIfCurrent)) return@synchronized false
            // An obsolete callback cannot contaminate a later request via the shared drainer flag.
            // Never clear an independently present flag; generation-level interruption still fails.
            if (restoreInterrupt) Thread.currentThread().interrupt()
            reasons.add(reason)
            beginRetirementLocked()
            if (cancelIssued) false else { cancelIssued = true; true }
        }
        if (sendCancel) {
            try { transport.cancel() }
            catch (_: Throwable) { synchronized(lock) { reasons.add(OwnedApplicationReason.TRANSPORT_FAILED); lock.notifyAll() } }
        }
    }

    private fun finishRetirement(): OwnedApplicationRetirement {
        synchronized(lock) { retirement?.let { return it }; beginRetirementLocked() }
        val outputPump = synchronized(lock) { pump }
        if (Thread.currentThread() === outputPump) {
            // Re-entry from a cooperative cancellation hook on the drainer cannot join itself.
            synchronized(lock) { reasons.add(OwnedApplicationReason.OUTPUT_DRAIN_UNRESOLVED) }
        } else {
            while (cleanupRemainingMillis() > 0 && !Thread.currentThread().isInterrupted) {
                val terminal = try { transport.awaitTerminal(minOf(20L, cleanupRemainingMillis())) }
                catch (_: Throwable) { poison(OwnedApplicationReason.TRANSPORT_FAILED); break }
                if (terminal != null) {
                    if (synchronized(lock) { pumpDone }) break
                    synchronized(lock) {
                        val wait = minOf(20L, cleanupRemainingMillis())
                        if (!pumpDone && wait > 0) {
                            try { lock.wait(wait) }
                            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                        }
                    }
                }
            }
            val remaining = cleanupRemainingMillis()
            if (outputPump != null && remaining > 0 && !Thread.currentThread().isInterrupted) {
                try { outputPump.join(remaining) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
        }
        if (Thread.currentThread().isInterrupted) poison(OwnedApplicationReason.INTERRUPTED)
        val snapshot = try { transport.snapshot() } catch (_: Throwable) { null }
        return synchronized(lock) {
            retirement?.let { return@synchronized it }
            snapshot?.let { transportReasons.addAll(it.reasons); it.termination?.let { end -> transportReasons.addAll(end.reasons) } }
            val end = snapshot?.termination
            val disposition = when {
                end?.state == OwnedSessionState.QUARANTINED -> OwnedApplicationCleanupDisposition.QUARANTINED
                end?.state == OwnedSessionState.TERMINAL && end.cleanupAcknowledged && pumpDone && outputPump?.isAlive != true ->
                    OwnedApplicationCleanupDisposition.CLOSED
                else -> OwnedApplicationCleanupDisposition.UNVERIFIED
            }
            if (disposition != OwnedApplicationCleanupDisposition.CLOSED) reasons.add(OwnedApplicationReason.CLEANUP_UNVERIFIED)
            if (!pumpDone || outputPump?.isAlive == true) reasons.add(OwnedApplicationReason.OUTPUT_DRAIN_UNRESOLVED)
            OwnedApplicationRetirement(disposition, reasons, transportReasons.map { it.code }).also { retirement = it }
        }
    }

    private fun cleanupRemainingMillis(): Long = synchronized(lock) {
        val remaining = (cleanupEndNanos ?: return@synchronized 0L) - System.nanoTime()
        if (remaining <= 0) 0 else remaining / 1_000_000
    }

    private fun reasonSnapshot(): List<OwnedApplicationReason> = synchronized(lock) { applicationReasons(reasons) }
    private fun pauseLocked() {
        try { lock.wait(5) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw ApplicationFailure(OwnedApplicationReason.INTERRUPTED) }
    }
}

private class ApplicationFailure(val reason: OwnedApplicationReason, val restoreInterruptOnPoison: Boolean = false) :
    RuntimeException(null, null, false, false)
private fun appRequire(condition: Boolean, reason: OwnedApplicationReason) { if (!condition) throw ApplicationFailure(reason) }
private fun callbackFailure(failure: Throwable, reason: OwnedApplicationReason,
                            deferInterrupt: Boolean = false): ApplicationFailure {
    val callbackInterrupted = failure is InterruptedException
    if (callbackInterrupted && !deferInterrupt) Thread.currentThread().interrupt()
    return ApplicationFailure(if (callbackInterrupted || Thread.currentThread().isInterrupted)
        OwnedApplicationReason.INTERRUPTED else reason, restoreInterruptOnPoison = callbackInterrupted && deferInterrupt)
}
private fun ownerHook(body: () -> Unit) {
    try { body() } catch (failure: Throwable) { throw callbackFailure(failure, OwnedApplicationReason.AUTHORITY) }
}
private fun checkCancellation(token: CancellationToken, deferCallbackInterrupt: Boolean = false) {
    appRequire(!Thread.currentThread().isInterrupted, OwnedApplicationReason.INTERRUPTED)
    val cancelled = try { token.isCancelled() }
    catch (failure: Throwable) { throw callbackFailure(failure, OwnedApplicationReason.CANCELLATION_CHECK,
        deferInterrupt = deferCallbackInterrupt) }
    appRequire(!Thread.currentThread().isInterrupted, OwnedApplicationReason.INTERRUPTED)
    appRequire(!cancelled, OwnedApplicationReason.CANCELLED)
}
private fun checkGeneration(permit: OwnedApplicationGenerationPermit, cancellation: CancellationToken) {
    checkCancellation(cancellation)
    appRequire(!permit.originalDeadline.isExpired(), OwnedApplicationReason.GENERATION_DEADLINE)
}

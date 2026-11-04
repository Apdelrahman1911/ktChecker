package dev.ktchecker.providers.runtime

import dev.ktchecker.contracts.CancellationToken
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*

/** All channels/images here are bounded in-memory fakes. Passing these is not native qualification. */
class OwnedProcessSessionTransportTest {
    private val fixtures = ArrayList<Pair<LiveTestRig, OwnedProcessSession>>()
    private val calls = ArrayList<Async<*>>()

    private class Async<T>(body: () -> T) {
        private val result = AtomicReference<Result<T>?>(null)
        val done = CountDownLatch(1)
        val interruptedOnReturn = AtomicBoolean()
        val thread = thread(isDaemon = true, name = "ktchecker-live-test-caller") {
            result.set(runCatching(body))
            interruptedOnReturn.set(Thread.currentThread().isInterrupted)
            done.countDown()
        }
        fun await(): T {
            assertTrue(done.await(4, TimeUnit.SECONDS), "Bounded fake caller did not finish")
            return result.get()!!.getOrThrow()
        }
    }

    private fun <T> async(body: () -> T): Async<T> = Async(body).also { calls.add(it) }

    private fun open(rig: LiveTestRig = LiveTestRig(), configuration: OwnedSessionConfiguration = liveTestConfiguration(),
                     waitForLive: Boolean = true): Pair<LiveTestRig, OwnedProcessSession> {
        val started = assertIs<OwnedSessionOpenResult.StartedTransport>(rig.transport.open(configuration))
        return (rig to started.handle).also {
            fixtures.add(it)
            if (waitForLive) awaitLive(started.handle)
        }
    }

    @AfterTest fun releaseOnlyTestFakes() {
        for ((rig, handle) in fixtures) {
            rig.channel.releaseTestGates()
            rig.image.cleanupSucceeds = true
            handle.cancel()
            rig.channel.endWithoutFinal() // Releases fake streams, never signals/claims real cleanup.
            handle.awaitTerminal()
            awaitCondition { rig.image.cleaned.get() }
        }
        for (call in calls) assertTrue(call.done.await(4, TimeUnit.SECONDS))
    }

    private fun acknowledged(operation: OwnedSessionOperation): OwnedSessionAck =
        assertIs<OwnedSessionOperation.Acknowledged>(operation).ack

    private fun rejected(operation: OwnedSessionOperation, reason: OwnedSessionReason) {
        val result = assertIs<OwnedSessionOperation.Rejected>(operation)
        assertTrue(result.notSent)
        assertEquals(listOf(reason), result.reasons)
    }

    private fun terminal(handle: OwnedProcessSession): OwnedSessionTermination = assertNotNull(handle.awaitTerminal())

    @Test fun `two detached writes and intervening output remain on one live generation until real fake closure`() {
        val (rig, handle) = open(LiveTestRig(configure = { it.echoData = true }))
        assertTrue(handle.snapshot().bound)
        assertNull(handle.snapshot().termination)
        assertEquals(1L, acknowledged(handle.arm(1, OwnedSessionDeadline.afterMillis(3000))).sequence)
        val first = byteArrayOf(1, 2, 3)
        assertEquals(3L, acknowledged(handle.write(first)).valueB)
        first.fill(0)
        val reply1 = assertNotNull(handle.pollOutput(1000))
        assertContentEquals(byteArrayOf(1, 2, 3), reply1.bytes)
        reply1.bytes.fill(9)
        assertContentEquals(byteArrayOf(1, 2, 3), reply1.bytes)
        assertEquals(OwnedSessionState.LIVE, handle.snapshot().state)
        assertNull(handle.snapshot().provisionalFinal)
        val second = reply1.bytes.reversedArray() + byteArrayOf(4)
        assertEquals(7L, acknowledged(handle.write(second)).valueB)
        assertContentEquals(second, assertNotNull(handle.pollOutput(1000)).bytes)
        assertEquals(OwnedSessionState.LIVE, handle.snapshot().state)
        acknowledged(handle.disarm(1))
        val close = handle.closeStdin()
        // A real helper exit/FINAL may retire before its last ACK is consumed. Retirement wins;
        // a valid late CLOSE ACK may still help closure, never rehabilitate that ordinary call.
        assertTrue(close is OwnedSessionOperation.Acknowledged || close is OwnedSessionOperation.Failed)
        val final = terminal(handle)
        assertEquals(OwnedSessionState.TERMINAL, final.state)
        assertTrue(final.cleanupAcknowledged)
        assertTrue(final.captureComplete)
        assertTrue(final.terminalExecutionConfirmed)
        assertEquals(23, final.nativeFinal!!.childExit)
        assertEquals(7L, final.nativeFinal.inputWritten)
        assertEquals(7L, handle.snapshot().deliveredOutput)
        assertEquals(0L, handle.snapshot().discardedOutput)
        assertEquals(1, rig.slots.remaining())
        assertEquals(2, rig.image.verifies.get())
        assertEquals(1, rig.image.closes.get())
    }

    @Test fun `one pending DATA excludes every ordinary kind but CANCEL bypasses it`() {
        val (rig, handle) = open(LiveTestRig(configure = { it.withholdDataAck = true; it.partialWriteBytes = 2 }))
        acknowledged(handle.arm(1, OwnedSessionDeadline.afterMillis(3000)))
        val waiting = async { handle.write(byteArrayOf(1, 2, 3, 4)) }
        assertTrue(rig.channel.inputSeen.await(1, TimeUnit.SECONDS))
        rejected(handle.write(byteArrayOf(9)), OwnedSessionReason.BUSY)
        rejected(handle.arm(2, OwnedSessionDeadline.afterMillis(1000)), OwnedSessionReason.BUSY)
        rejected(handle.disarm(1), OwnedSessionReason.BUSY)
        rejected(handle.closeStdin(), OwnedSessionReason.BUSY)
        repeat(100) { handle.cancel() }
        val failed = assertIs<OwnedSessionOperation.Failed>(waiting.await())
        assertEquals(OwnedSessionDelivery.MAY_HAVE_BEEN_WRITTEN, failed.delivery)
        assertEquals(2L, failed.sequence)
        val final = terminal(handle)
        assertTrue(final.cleanupAcknowledged)
        assertFalse(final.terminalExecutionConfirmed)
        assertEquals(4L, final.nativeFinal!!.inputAccepted)
        assertEquals(2L, final.nativeFinal.inputWritten)
        assertEquals(0L, final.nativeFinal.inputAckFlushed)
        assertTrue(final.nativeFinal.has(OwnedSessionFlags.INPUT_INCOMPLETE))
        assertEquals(1, rig.channel.cancels.get())
        assertEquals(2L, rig.channel.ordinaryRequests())
        assertEquals(1, rig.slots.remaining())
    }

    @Test fun `ordinary caller interruption preserves delivery ledger and permits only late closure evidence`() {
        val (rig, handle) = open(LiveTestRig(configure = {
            it.withholdDataAck = true; it.partialWriteBytes = 4; it.finishOnCancel = false
        }))
        acknowledged(handle.arm(1, OwnedSessionDeadline.afterMillis(3000)))
        val waiting = async { handle.write(byteArrayOf(1, 2, 3, 4)) }
        assertTrue(rig.channel.inputSeen.await(1, TimeUnit.SECONDS))
        waiting.thread.interrupt()
        val failed = assertIs<OwnedSessionOperation.Failed>(waiting.await())
        assertTrue(waiting.interruptedOnReturn.get())
        assertTrue(OwnedSessionReason.INTERRUPTED in failed.reasons)
        assertEquals(OwnedSessionDelivery.MAY_HAVE_BEEN_WRITTEN, failed.delivery)
        assertEquals(2L, handle.snapshot().outstandingSequence)
        awaitCondition { rig.channel.cancels.get() == 1 }
        // The fake had pipe-written these bytes before cancellation; only its ACK was withheld.
        rig.channel.acknowledgePendingData()
        awaitCondition { handle.snapshot().acknowledgedInput == 4L }
        rejected(handle.write(byteArrayOf(9)), OwnedSessionReason.NOT_LIVE)
        rig.channel.finish(cancelled = true)
        assertTrue(terminal(handle).cleanupAcknowledged)
        assertSame(failed, waiting.await())
        assertEquals(1, rig.slots.remaining())
    }

    @Test fun `interrupted output and terminal observers never acquire generation cancellation authority`() {
        val (rig, handle) = open()
        assertNull(handle.awaitTerminal(1))
        val outputObserver = async { handle.pollOutput(1000) }
        awaitCondition { outputObserver.thread.state == Thread.State.TIMED_WAITING }
        outputObserver.thread.interrupt()
        assertNull(outputObserver.await())
        assertTrue(outputObserver.interruptedOnReturn.get())
        val terminalObserver = async { handle.awaitTerminal(1000) }
        awaitCondition { terminalObserver.thread.state == Thread.State.TIMED_WAITING }
        terminalObserver.thread.interrupt()
        assertNull(terminalObserver.await())
        assertTrue(terminalObserver.interruptedOnReturn.get())
        assertEquals(OwnedSessionState.LIVE, handle.snapshot().state)
        assertEquals(0, rig.channel.cancels.get())
    }

    @Test fun `opening rejects ordinary controls but cancellation never waits for BOUND`() {
        val (rig, handle) = open(LiveTestRig(configure = { it.withholdBound = true; it.finishOnCancel = false }), waitForLive = false)
        assertTrue(rig.channel.preambleSeen.await(1, TimeUnit.SECONDS))
        assertEquals(OwnedSessionState.OPENING, handle.snapshot().state)
        rejected(handle.arm(1, OwnedSessionDeadline.afterMillis(1000)), OwnedSessionReason.NOT_LIVE)
        handle.cancel()
        awaitCondition { rig.channel.cancels.get() == 1 }
        assertEquals(0L, rig.channel.ordinaryRequests())
        rig.channel.releaseBound()
        rig.channel.finish(cancelled = true)
        assertTrue(terminal(handle).cleanupAcknowledged)
        assertEquals(OwnedSessionState.TERMINAL, handle.snapshot().state)
    }

    @Test fun `ACK receipt before writer return does not free credit or make a cancelled call succeed`() {
        val gate = CountDownLatch(1)
        val (rig, handle) = open(LiveTestRig(configure = { it.afterDataAckBeforeWriteReturns = gate }))
        acknowledged(handle.arm(1, OwnedSessionDeadline.afterMillis(3000)))
        val waiting = async { handle.write(byteArrayOf(4, 5)) }
        assertTrue(rig.channel.inputAckEmitted.await(1, TimeUnit.SECONDS))
        awaitCondition { handle.snapshot().acknowledgedInput == 2L }
        assertEquals(1L, waiting.done.count)
        rejected(handle.closeStdin(), OwnedSessionReason.BUSY)
        handle.cancel()
        val failed = assertIs<OwnedSessionOperation.Failed>(waiting.await())
        assertEquals(OwnedSessionDelivery.MAY_HAVE_BEEN_WRITTEN, failed.delivery)
        gate.countDown()
        assertTrue(terminal(handle).cleanupAcknowledged)
        assertSame(failed, waiting.await())
    }

    @Test fun `local invalid transitions and sizes reject before writing while closed stdin permits watchdog bookkeeping`() {
        val (rig, handle) = open(LiveTestRig(configure = { it.finishOnClose = false }))
        rejected(handle.write(byteArrayOf(1)), OwnedSessionReason.NOT_ARMED)
        rejected(handle.disarm(1), OwnedSessionReason.NOT_ARMED)
        rejected(handle.arm(2, OwnedSessionDeadline.afterMillis(1000)), OwnedSessionReason.WRONG_EPOCH)
        acknowledged(handle.arm(1, OwnedSessionDeadline.afterMillis(3000)))
        rejected(handle.write(byteArrayOf()), OwnedSessionReason.INVALID_DATA)
        rejected(handle.write(ByteArray(8193)), OwnedSessionReason.INVALID_DATA)
        rejected(handle.arm(2, OwnedSessionDeadline.afterMillis(1000)), OwnedSessionReason.ALREADY_ARMED)
        rejected(handle.disarm(2), OwnedSessionReason.WRONG_EPOCH)
        assertEquals(1L, rig.channel.ordinaryRequests())
        acknowledged(handle.closeStdin())
        assertFalse(rig.channel.controlsClosed.get())
        assertEquals(OwnedSessionState.LIVE, handle.snapshot().state)
        rejected(handle.write(byteArrayOf(1)), OwnedSessionReason.STDIN_CLOSED)
        rejected(handle.closeStdin(), OwnedSessionReason.STDIN_CLOSED)
        acknowledged(handle.disarm(1))
        acknowledged(handle.arm(2, OwnedSessionDeadline.afterMillis(1000)))
        acknowledged(handle.disarm(2))
        assertEquals(5L, rig.channel.ordinaryRequests())
        assertFailsWith<IllegalArgumentException> { handle.pollOutput(1001) }
        assertFailsWith<IllegalArgumentException> { handle.awaitTerminal(4001) }
    }

    @Test fun `exact input allowance succeeds and next attempted byte retires without retention or replay`() {
        val (rig, handle) = open(configuration = liveTestConfiguration(inputLimit = 3))
        acknowledged(handle.arm(1, OwnedSessionDeadline.afterMillis(3000)))
        acknowledged(handle.write(byteArrayOf(1, 2, 3)))
        assertEquals(OwnedSessionState.LIVE, handle.snapshot().state)
        val failed = assertIs<OwnedSessionOperation.Failed>(handle.write(byteArrayOf(4)))
        assertEquals(OwnedSessionDelivery.NOT_SENT, failed.delivery)
        assertNull(failed.sequence)
        assertTrue(OwnedSessionReason.INPUT_LIMIT in failed.reasons)
        val final = terminal(handle)
        assertTrue(final.cleanupAcknowledged)
        assertEquals(3L, final.nativeFinal!!.inputAccepted)
        assertEquals(2L, rig.channel.ordinaryRequests())
        assertFalse(final.nativeFinal.has(OwnedSessionFlags.INPUT_LIMIT)) // Local reason is not a fabricated native flag.
    }

    @Test fun `exact generation control and epoch allowances grant their last ACK but never a new generation`() {
        val (rig, handle) = open(configuration = liveTestConfiguration(millis = 120_000))
        for (epoch in 1L..OwnedSessionLimits.EPOCHS) {
            acknowledged(handle.arm(epoch, OwnedSessionDeadline.afterMillis(30_000)))
            acknowledged(handle.disarm(epoch))
        }
        assertEquals(OwnedSessionLimits.REQUESTS, rig.channel.ordinaryRequests())
        assertEquals(OwnedSessionState.LIVE, handle.snapshot().state)
        val failed = assertIs<OwnedSessionOperation.Failed>(handle.arm(OwnedSessionLimits.EPOCHS + 1, OwnedSessionDeadline.afterMillis(1000)))
        assertEquals(OwnedSessionDelivery.NOT_SENT, failed.delivery)
        assertTrue(OwnedSessionReason.CONTROL_LIMIT in failed.reasons)
        assertTrue(terminal(handle).cleanupAcknowledged)
        assertEquals(OwnedSessionLimits.REQUESTS, rig.channel.ordinaryRequests())
    }

    @Test fun `both application queue count and byte bounds retire without silently losing closure frames`() {
        for ((count, size, retainedFrames, retainedBytes) in listOf(
            listOf(65, 1, 64, 64), listOf(33, 8192, 32, 262_144),
        )) {
            val (rig, handle) = open(LiveTestRig(configure = { it.finishOnCancel = false }))
            repeat(count) { rig.channel.emitOutput(ByteArray(size) { 7 }) }
            awaitCondition { OwnedSessionReason.OUTPUT_QUEUE in handle.snapshot().reasons }
            awaitCondition { handle.snapshot().receivedOutput == count.toLong() * size }
            val snapshot = handle.snapshot()
            assertEquals(retainedFrames, snapshot.queuedFrames)
            assertEquals(retainedBytes.toLong(), snapshot.retainedOutput)
            assertEquals(size.toLong(), snapshot.discardedOutput)
            rig.channel.finish(cancelled = true)
            val final = terminal(handle)
            assertTrue(final.cleanupAcknowledged)
            assertFalse(final.captureComplete)
            assertEquals(count.toLong() * size, final.nativeFinal!!.outputCaptured)
            assertEquals(1, rig.slots.remaining())
            var drained = 0L
            while (true) drained += (handle.pollOutput() ?: break).size
            assertEquals(retainedBytes.toLong(), drained)
            assertEquals(0L, handle.snapshot().retainedOutput)
        }
    }

    @Test fun `blocked writer cannot block caller cancellation and at most five fixed service threads quarantine its slot`() {
        val previous = Thread.getAllStackTraces().keys.toSet()
        val writeGate = CountDownLatch(1)
        val (rig, handle) = open(LiveTestRig(grace = 100, configure = { it.blockDataWrite = writeGate }))
        acknowledged(handle.arm(1, OwnedSessionDeadline.afterMillis(3000)))
        val writing = async { handle.write(byteArrayOf(1, 2, 3)) }
        assertTrue(rig.channel.inputSeen.await(1, TimeUnit.SECONDS))
        val cancelled = async { handle.cancel() }
        cancelled.await()
        assertIs<OwnedSessionOperation.Failed>(writing.await())
        val final = terminal(handle)
        assertEquals(OwnedSessionState.QUARANTINED, final.state)
        assertFalse(final.cleanupAcknowledged)
        assertEquals(0, rig.channel.cancels.get()) // Writer still inside one record; CANCEL delivery is not invented.
        assertEquals(0, rig.slots.remaining())
        repeat(100) { handle.cancel(); rejected(handle.write(byteArrayOf(9)), OwnedSessionReason.NOT_LIVE) }
        val service = Thread.getAllStackTraces().keys.filter { it !in previous && it.name.startsWith("ktchecker-owned-live-") }
        assertTrue(service.size <= 5)
        assertTrue(service.all { it.isDaemon })
        assertEquals(1, service.count { it.name.endsWith("writer") })
        writeGate.countDown()
        rig.channel.endWithoutFinal()
        awaitCondition { rig.image.closes.get() > 0 }
        assertSame(final, handle.awaitTerminal(0))
        assertEquals(0, rig.slots.remaining())
    }

    @Test fun `blocked stream close quarantines even with a valid FINAL and real fake exit`() {
        val closeGate = CountDownLatch(1)
        val (rig, handle) = open(LiveTestRig(grace = 100, configure = { it.frames.closeGate = closeGate }))
        handle.closeStdin()
        assertTrue(rig.channel.frames.closeEntered.await(1, TimeUnit.SECONDS))
        val final = terminal(handle)
        assertNotNull(final.nativeFinal)
        assertEquals(OwnedSessionState.QUARANTINED, final.state)
        assertFalse(final.cleanupAcknowledged)
        assertEquals(0, rig.image.closes.get())
        closeGate.countDown()
        awaitCondition { rig.image.closes.get() > 0 }
        assertEquals(0, rig.slots.remaining())
        assertSame(final, terminal(handle))
    }

    @Test fun `late valid ACK FINAL EOF exit and image hygiene never rehabilitate quarantine or its failed caller`() {
        val closeGate = CountDownLatch(1)
        val (rig, handle) = open(LiveTestRig(grace = 100, configure = {
            it.withholdDataAck = true; it.partialWriteBytes = 4; it.finishOnCancel = false; it.frames.closeGate = closeGate
        }))
        acknowledged(handle.arm(1, OwnedSessionDeadline.afterMillis(3000)))
        val writing = async { handle.write(byteArrayOf(1, 2, 3, 4)) }
        assertTrue(rig.channel.inputSeen.await(1, TimeUnit.SECONDS))
        handle.cancel()
        val failed = assertIs<OwnedSessionOperation.Failed>(writing.await())
        val final = terminal(handle)
        assertEquals(OwnedSessionState.QUARANTINED, final.state)
        assertNull(final.nativeFinal)
        assertTrue(rig.channel.frames.closeEntered.await(1, TimeUnit.SECONDS))
        rig.channel.acknowledgePendingData()
        awaitCondition { handle.snapshot().acknowledgedInput == 4L }
        rig.channel.finish(cancelled = true)
        awaitCondition { handle.snapshot().provisionalFinal != null }
        assertSame(final, handle.snapshot().termination)
        assertFalse(final.cleanupAcknowledged)
        assertFalse(final.terminalExecutionConfirmed)
        closeGate.countDown()
        awaitCondition { rig.image.closes.get() > 0 }
        assertSame(failed, writing.await())
        assertSame(final, terminal(handle))
        assertEquals(0, rig.slots.remaining())
    }

    @Test fun `postexit image drift and failed cleanup are permanent slot poison even after later hygiene`() {
        for (drift in listOf(true, false)) {
            val image = LiveTestImage().also { it.failPostExitVerify = drift; it.cleanupSucceeds = drift }
            val (rig, handle) = open(LiveTestRig(image = image))
            handle.closeStdin()
            val final = terminal(handle)
            assertEquals(OwnedSessionState.QUARANTINED, final.state)
            assertFalse(final.cleanupAcknowledged)
            assertTrue((if (drift) OwnedSessionReason.IMAGE_VERIFY else OwnedSessionReason.IMAGE_CLEANUP) in final.reasons)
            assertEquals(final.reasons.distinct(), final.reasons)
            image.cleanupSucceeds = true
            awaitCondition { image.cleaned.get() }
            assertSame(final, terminal(handle))
            assertEquals(0, rig.slots.remaining())
        }
    }

    @Test fun `invalid native framing unexpected diagnostics and missing FINAL cannot return capacity`() {
        for (failure in 0..2) {
            val (rig, handle) = open(LiveTestRig(grace = 200))
            when (failure) {
                0 -> rig.channel.frames.emit(byteArrayOf(255.toByte(), 0, 0, 0, 0))
                1 -> rig.channel.diagnostics.emit(byteArrayOf(7))
                else -> rig.channel.endWithoutFinal()
            }
            val final = terminal(handle)
            assertEquals(OwnedSessionState.QUARANTINED, final.state)
            assertFalse(final.cleanupAcknowledged)
            assertTrue((if (failure == 1) OwnedSessionReason.DIAGNOSTICS else OwnedSessionReason.PROTOCOL) in final.reasons)
            assertEquals(0, rig.slots.remaining())
        }
    }

    @Test fun `original generation deadline keeps running while ordinary acknowledgement is withheld`() {
        val (rig, handle) = open(LiveTestRig(configure = { it.withholdDataAck = true }), liveTestConfiguration(millis = 300))
        acknowledged(handle.arm(1, OwnedSessionDeadline.afterMillis(3000)))
        val failed = assertIs<OwnedSessionOperation.Failed>(handle.write(byteArrayOf(1)))
        assertTrue(OwnedSessionReason.GENERATION_TIMEOUT in failed.reasons)
        assertEquals(OwnedSessionDelivery.MAY_HAVE_BEEN_WRITTEN, failed.delivery)
        assertTrue(terminal(handle).cleanupAcknowledged)
        assertEquals(1, rig.channel.cancels.get())
    }

    @Test fun `DISARM waits under the original epoch and late receipt cannot erase expiry`() {
        val (rig, handle) = open(LiveTestRig(configure = { it.withholdDisarmAck = true }))
        acknowledged(handle.arm(1, OwnedSessionDeadline.afterMillis(300)))
        val failed = assertIs<OwnedSessionOperation.Failed>(handle.disarm(1))
        assertTrue(OwnedSessionReason.EPOCH_TIMEOUT in failed.reasons)
        assertEquals(OwnedSessionDelivery.MAY_HAVE_BEEN_WRITTEN, failed.delivery)
        assertTrue(terminal(handle).cleanupAcknowledged)
        assertEquals(2L, rig.channel.ordinaryRequests())
    }

    @Test fun `preparation spent before ARM is not refreshed and expired admission is NOT_SENT`() {
        val (rig, handle) = open()
        val deadline = OwnedSessionDeadline.afterMillis(10)
        Thread.sleep(15)
        val failed = assertIs<OwnedSessionOperation.Failed>(handle.arm(1, deadline))
        assertEquals(OwnedSessionDelivery.NOT_SENT, failed.delivery)
        assertNull(failed.sequence)
        assertTrue(OwnedSessionReason.EPOCH_TIMEOUT in failed.reasons)
        assertTrue(terminal(handle).cleanupAcknowledged)
        assertEquals(0L, rig.channel.ordinaryRequests())
    }

    @Test fun `clean FINAL first observed after original generation or epoch expiry preserves local timeout`() {
        for (generationExpires in listOf(true, false)) verifyExpiredFirstObservation(generationExpires, finalFirst = true)
    }

    @Test fun `helper exit first observed after original generation or epoch expiry preserves local timeout`() {
        for (generationExpires in listOf(true, false)) verifyExpiredFirstObservation(generationExpires, finalFirst = false)
    }

    private fun verifyExpiredFirstObservation(generationExpires: Boolean, finalFirst: Boolean) {
        val monitorGate = CountDownLatch(1)
        val finalDeliveryGate = CountDownLatch(1)
        val configuration = liveTestConfiguration(millis = if (generationExpires) 1000 else 5000)
        val (rig, handle) = open(LiveTestRig(grace = 4000, configure = { it.exitObservationGate = monitorGate }), configuration)
        assertTrue(rig.channel.exitObservationEntered.await(1, TimeUnit.SECONDS))
        val deadline = if (generationExpires) configuration.generationDeadline else
            OwnedSessionDeadline.afterMillis(1000).also { acknowledged(handle.arm(1, it)) }
        if (!finalFirst) rig.channel.frames.deliveryGate = finalDeliveryGate

        // The monitor is parked in the fake exitCode(), outside the transport lock. The writer
        // is idle, and BOUND (plus the optional ARM ACK) has already completed. No ordinary API
        // or observer operation here checks transport deadlines. Wait for the ORIGINAL budget,
        // then prove no earlier retirement occurred before releasing the selected event.
        awaitCondition { deadline.expired() }
        assertEquals(OwnedSessionState.LIVE, handle.snapshot().state)
        assertTrue(handle.snapshot().reasons.isEmpty())
        rig.channel.finish(cancelled = false) // A clean native-relative result can lag host expiry.
        val reason = if (generationExpires) OwnedSessionReason.GENERATION_TIMEOUT else OwnedSessionReason.EPOCH_TIMEOUT
        if (finalFirst) {
            awaitCondition { handle.snapshot().provisionalFinal != null }
            assertTrue(reason in handle.snapshot().reasons, "FINAL's first retirement must retain the expired host budget")
            monitorGate.countDown()
        } else {
            assertTrue(rig.channel.frames.deliveryEntered.await(1, TimeUnit.SECONDS))
            assertNull(handle.snapshot().provisionalFinal) // FINAL tag has not yet returned from the fake read.
            monitorGate.countDown() // Helper exit zero is now the first retirement observation.
            awaitCondition { handle.snapshot().state != OwnedSessionState.LIVE }
            assertNull(handle.snapshot().provisionalFinal)
            assertTrue(reason in handle.snapshot().reasons, "Exit's first retirement must retain the expired host budget")
            finalDeliveryGate.countDown()
        }
        val final = terminal(handle)
        assertEquals(OwnedSessionState.TERMINAL, final.state)
        assertTrue(final.cleanupAcknowledged) // Deadline failure and actual owned cleanup are distinct facts.
        assertTrue(reason in final.reasons)
        assertEquals(0, final.nativeFinal!!.cause)
        assertEquals(OwnedSessionFlags.COMPLETED, final.nativeFinal.flags)
        assertFalse(final.nativeFinal.has(OwnedSessionFlags.TIMED_OUT)) // Do not fabricate native flags.
        assertEquals(1, rig.slots.remaining())
    }

    @Test fun `byte loading failure known not-started and unresolved extraction or start have different slot outcomes`() {
        val config = liveTestConfiguration()
        val byteSlots = SupervisorSlots(1)
        val unavailable = OwnedProcessSessionTransport(images = OwnedSessionImageSource { throw IOException("not retained") }, slots = byteSlots)
        assertIs<OwnedSessionOpenResult.Rejected>(unavailable.open(config))
        assertEquals(1, byteSlots.remaining())

        val extractionSlots = SupervisorSlots(1)
        val extraction = OwnedProcessSessionTransport(images = OwnedSessionImageSource {
            OwnedSessionImagePlan { throw IOException("no cleanup receipt") }
        }, slots = extractionSlots, nonceFactory = { liveTestNonce })
        assertTrue(OwnedSessionReason.IMAGE_EXTRACTION in assertIs<OwnedSessionOpenResult.Rejected>(extraction.open(config)).reasons)
        assertEquals(0, extractionSlots.remaining())

        for (knownNotStarted in listOf(true, false)) {
            val slots = SupervisorSlots(1)
            val image = LiveTestImage()
            val transport = OwnedProcessSessionTransport(images = OwnedSessionImageSource { OwnedSessionImagePlan { image } },
                launcher = OwnedLiveSupervisorLauncher { _, _, _, _ ->
                    if (knownNotStarted) throw OwnedSessionNotStarted() else throw IOException("post-start state unknown")
                }, slots = slots, nonceFactory = { liveTestNonce })
            val result = assertIs<OwnedSessionOpenResult.Rejected>(transport.open(liveTestConfiguration()))
            assertTrue((if (knownNotStarted) OwnedSessionReason.START_FAILED else OwnedSessionReason.START_UNKNOWN) in result.reasons)
            assertEquals(if (knownNotStarted) 1 else 0, slots.remaining())
            assertEquals(if (knownNotStarted) 1 else 0, image.closes.get())
        }
    }

    @Test fun `expired open and zero nonce reject without extraction or unbounded randomness retry`() {
        val loads = AtomicInteger()
        val extracts = AtomicInteger()
        val nonces = AtomicInteger()
        val slots = SupervisorSlots(1)
        val transport = OwnedProcessSessionTransport(images = OwnedSessionImageSource {
            loads.incrementAndGet()
            OwnedSessionImagePlan { extracts.incrementAndGet(); LiveTestImage() }
        }, slots = slots, nonceFactory = { nonces.incrementAndGet(); ByteArray(16) })
        val expired = liveTestConfiguration(millis = 1)
        Thread.sleep(2)
        val result = assertIs<OwnedSessionOpenResult.Rejected>(transport.open(expired))
        assertEquals(listOf(OwnedSessionReason.GENERATION_TIMEOUT), result.reasons)
        assertEquals(0, loads.get())
        assertIs<OwnedSessionOpenResult.Rejected>(transport.open(liveTestConfiguration()))
        assertEquals(1, loads.get())
        assertEquals(1, nonces.get())
        assertEquals(0, extracts.get())
        assertEquals(1, slots.remaining())
    }

    @Test fun `wrong-version image cannot launch and a prelaunch slot returns only after safe image removal`() {
        for (cleanupSucceeds in listOf(true, false)) {
            val slots = SupervisorSlots(1)
            val underlying = LiveTestImage().also { it.cleanupSucceeds = cleanupSucceeds }
            val image = object : SupervisorImageHandle by underlying {
                override val identity = liveTestIdentity.copy(protocolVersion = SupervisorBundleFormat.VERSION)
            }
            val launches = AtomicInteger()
            val transport = OwnedProcessSessionTransport(images = OwnedSessionImageSource { OwnedSessionImagePlan { image } },
                launcher = OwnedLiveSupervisorLauncher { _, _, _, _ -> launches.incrementAndGet(); throw OwnedSessionNotStarted() },
                slots = slots, nonceFactory = { liveTestNonce })
            val result = assertIs<OwnedSessionOpenResult.Rejected>(transport.open(liveTestConfiguration()))
            assertTrue(OwnedSessionReason.IMAGE_VERIFY in result.reasons)
            assertEquals(0, launches.get())
            assertEquals(1, underlying.closes.get())
            assertEquals(if (cleanupSucceeds) 1 else 0, slots.remaining())
        }
    }

    @Test fun `independent handles have independent control pipes under one shared capacity owner`() {
        val slots = SupervisorSlots(2)
        val (firstRig, first) = open(LiveTestRig(slots = slots))
        val (secondRig, second) = open(LiveTestRig(slots = slots, configure = { it.echoData = true }))
        assertEquals(0, slots.remaining())
        first.cancel()
        assertTrue(terminal(first).cleanupAcknowledged)
        assertEquals(1, firstRig.channel.cancels.get())
        assertEquals(0, secondRig.channel.cancels.get())
        assertEquals(OwnedSessionState.LIVE, second.snapshot().state)
        acknowledged(second.arm(1, OwnedSessionDeadline.afterMillis(3000)))
        acknowledged(second.write(byteArrayOf(7)))
        assertContentEquals(byteArrayOf(7), assertNotNull(second.pollOutput(1000)).bytes)
        assertEquals(1, slots.remaining())
    }

    @Test fun `idle disarmed retiring and quarantined v2 retain capacity shared with injected v1`() {
        val (rig, handle) = open(LiveTestRig(configure = { it.finishOnCancel = false }))
        val prepares = AtomicInteger()
        fun noPreparation() {
            val config = liveTestConfiguration()
            val v1 = SupervisorTransport(prepare = { prepares.incrementAndGet(); throw IllegalStateException("must not prepare") }, slots = rig.slots)
                .run(config.command, config.directory, config.environment, 1000, 1024, CancellationToken.NONE)
            assertEquals(listOf("process_supervisor_capacity_unavailable"), v1.reasons)
            val v2 = assertIs<OwnedSessionOpenResult.Rejected>(rig.transport.open(config))
            assertEquals(listOf(OwnedSessionReason.CAPACITY), v2.reasons)
            assertEquals(0, prepares.get())
        }
        acknowledged(handle.arm(1, OwnedSessionDeadline.afterMillis(3000)))
        acknowledged(handle.disarm(1))
        assertEquals(OwnedSessionState.LIVE, handle.snapshot().state)
        noPreparation()
        handle.cancel()
        assertEquals(OwnedSessionState.RETIRING, handle.snapshot().state)
        noPreparation()
        assertEquals(OwnedSessionState.QUARANTINED, terminal(handle).state)
        noPreparation()
    }

    @Test fun `both production entrypoints share the same four slots without loading or launching any helper`() {
        val slots = OwnedSupervisorCapacity.slots
        val held = ArrayList<SupervisorSlot>()
        try {
            repeat(4) { held.add(assertNotNull(slots.acquire())) }
            assertEquals(0, slots.remaining())
            val config = liveTestConfiguration()
            val v1 = OwnedProcessSupervisor.run(config.command, config.directory, config.environment, 1000, 1024, CancellationToken.NONE)
            assertEquals(listOf("process_supervisor_capacity_unavailable"), v1.reasons)
            val v2 = assertIs<OwnedSessionOpenResult.Rejected>(OwnedProcessSessions.open(config))
            assertEquals(listOf(OwnedSessionReason.CAPACITY), v2.reasons)
        } finally { held.forEach(SupervisorSlot::close) }
        assertEquals(4, slots.remaining())
    }
}

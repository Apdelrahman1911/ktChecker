package dev.ktchecker.cli

import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*
import kotlin.test.*

/** Dedicated actual reader against LOCAL INERT pipes. Test pipe close/join has no owner child,
 * external process, provider/model application, signal, retry, receipt or cleanup authority. */
class OwnerModelTransportPumpTest {
    @Test fun dedicatedPumpServicesClockReplyWhileSequentialCallerIsWaiting() {
        val state = transportTestState(startupMillis = 5_000)
        val input = PipedInputStream(4096); val peer = PipedOutputStream(input)
        val reader = Thread({ state.pump(input) }, "inert-owner-frame-test-reader").apply { isDaemon = true }
        try {
            reader.start()
            val bytes = transportTestFrame(transportTestRequest())
            for (byte in bytes) peer.write(byte.toInt() and 255)
            peer.flush()
            val request = state.take()
            val phase = state.challenge(request, OwnerModelTransportClockKind.EXPORT, 1, "b".repeat(64))
            assertFalse(phase.accepted)
            peer.write(transportTestFrame(transportTestReply(phase))); peer.flush()
            state.awaitClock(phase)
            assertTrue(phase.accepted); assertFalse(state.poisoned)
        } finally {
            peer.close() // Inert pipe EOF is tested as DENIAL, never a real child-close path.
            reader.join(2_000); input.close()
        }
        assertFalse(reader.isAlive); assertTrue(state.poisoned)
    }

    @Test fun eofPumpPoisonsQueuedSuccessorWhileWriterStillRetainsBlockedActualReturn() {
        val (state, request) = transportTestActive(startupMillis = 5_000)
        val original = Any(); request.returned.set(original)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val output = object : OutputStream() {
            override fun write(value: Int) = error("bulk writes required")
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                entered.countDown()
                check(release.await(4, TimeUnit.SECONDS)) { "inert writer release not delivered" }
            }
        }
        val input = PipedInputStream(4096); val peer = PipedOutputStream(input)
        val reader = Thread({ state.pump(input) }, "inert-owner-eof-test-reader").apply { isDaemon = true }
        val writer = Thread({
            try { ownerModelTransportRespond(state, request, output, JsonObject(emptyMap())) }
            catch (error: Throwable) { failure.set(error) }
        }, "inert-owner-blocked-test-writer").apply { isDaemon = true }
        try {
            reader.start(); writer.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            peer.write(transportTestFrame(transportTestRequest(2))); peer.flush(); peer.close()
            reader.join(2_000)
            assertFalse(reader.isAlive); assertTrue(state.poisoned)
            assertTrue(writer.isAlive) // Input denial was published independently of output progress.
            assertSame(request, state.currentRequest); assertSame(original, request.returned.get())
            assertFailsWith<CliProblem> { state.take() }
        } finally {
            release.countDown(); peer.close(); writer.join(2_000); reader.join(2_000); input.close()
        }
        assertFalse(writer.isAlive); assertIs<CliProblem>(failure.get())
        assertSame(request, state.currentRequest); assertSame(original, request.returned.get())
    }
}

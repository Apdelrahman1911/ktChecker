package dev.ktchecker.providers.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Separate real-execution gate. The owner must first approve/build the reviewed native v2 helper
 * and live_supervisor_probe.c, then explicitly set KTC_RUN_OWNED_LIVE_INTEGRATION=1 and the absolute
 * KTC_OWNED_LIVE_PROBE path. This test never compiles a program or substitutes an installed helper.
 * It exercises opaque duplex bytes, not application HELLO, compiler readiness, warmth, or an SLO.
 */
@Tag("owned-live-integration")
@EnabledIfEnvironmentVariable(named = "KTC_RUN_OWNED_LIVE_INTEGRATION", matches = "1")
class OwnedProcessSessionIntegrationTest {
    @Test fun `reply selects next request on same LIVE native generation before graceful stdin EOF`() {
        assertTrue(SupervisorBundleFormat.target() in OwnedLiveSupervisorBundleFormat.targets,
            "Explicit real v2 integration requires a supported Linux host")
        val selected = assertNotNull(System.getenv("KTC_OWNED_LIVE_PROBE"), "Explicit gate requires an independently approved probe")
        val probe = Path.of(selected)
        assertTrue(probe.isAbsolute && probe.normalize() == probe && Files.isRegularFile(probe, NOFOLLOW_LINKS) && Files.isExecutable(probe))
        val generation = OwnedSessionDeadline.afterMillis(8000) // BEFORE image loading/extraction/launch preparation.
        val configuration = OwnedSessionConfiguration(listOf(probe.toString(), "duplex"), probe.parent, emptyMap(),
            generation, inputLimit = 16_384, outputLimit = 16_384)
        val started = assertIs<OwnedSessionOpenResult.StartedTransport>(OwnedProcessSessions.open(configuration))
        val handle = started.handle
        try {
            awaitLive(handle)
            assertNull(handle.snapshot().provisionalFinal)
            assertIs<OwnedSessionOperation.Acknowledged>(handle.arm(1, OwnedSessionDeadline.afterMillis(5000)))
            val first = byteArrayOf(0, 0xff.toByte(), 0x28, 0xc3.toByte(), 7, 11, 19)
            val firstRecord = encode(first)
            val firstAck = assertIs<OwnedSessionOperation.Acknowledged>(handle.write(firstRecord)).ack
            assertEquals(firstRecord.size.toLong(), firstAck.valueA) // Kernel write only, not probe receipt.
            val reply1 = receiveReplyWhileLive(handle, generation)
            assertContentEquals(first.reversedArray(), reply1)
            assertEquals(OwnedSessionState.LIVE, handle.snapshot().state)
            assertNull(handle.snapshot().provisionalFinal)

            // Request 2 does not exist until response 1 has actually been observed on this handle.
            val second = reply1.map { (it.toInt() xor 0x5a).toByte() }.toByteArray() + byteArrayOf(0x52)
            val secondRecord = encode(second)
            val secondAck = assertIs<OwnedSessionOperation.Acknowledged>(handle.write(secondRecord)).ack
            assertEquals((firstRecord.size + secondRecord.size).toLong(), secondAck.valueB)
            assertContentEquals(second.reversedArray(), receiveReplyWhileLive(handle, generation))
            assertEquals(OwnedSessionState.LIVE, handle.snapshot().state)
            assertNull(handle.snapshot().termination)
            assertIs<OwnedSessionOperation.Acknowledged>(handle.disarm(1))
            val close = handle.closeStdin()
            // Retirement can win the final CLOSE-call race; real closure remains independently required.
            assertTrue(close is OwnedSessionOperation.Acknowledged || close is OwnedSessionOperation.Failed)
            val final = assertNotNull(handle.awaitTerminal())
            assertEquals(OwnedSessionState.TERMINAL, final.state)
            assertTrue(final.cleanupAcknowledged)
            assertTrue(final.captureComplete)
            assertTrue(final.terminalExecutionConfirmed)
            assertEquals(OwnedLiveSupervisorBundleFormat.VERSION, final.identity.protocolVersion)
            assertEquals(handle.snapshot().identity, final.identity)
            val native = assertNotNull(final.nativeFinal)
            assertEquals(0, native.cause)
            assertEquals(OwnedSessionFlags.COMPLETED, native.flags)
            assertEquals(23, native.childExit) // This controlled probe returns 23 only after actual child stdin EOF.
            val expectedBytes = (firstRecord.size + secondRecord.size).toLong()
            assertEquals(expectedBytes, native.inputAccepted)
            assertEquals(expectedBytes, native.inputWritten)
            assertEquals(expectedBytes, native.inputAckFlushed)
            assertEquals(expectedBytes, native.outputCaptured)
            assertEquals(5L, native.requestsAccepted)
            assertEquals(5L, native.acksFlushed)
            assertEquals(0L, handle.snapshot().retainedOutput)
            assertEquals(0L, handle.snapshot().discardedOutput)
        } finally {
            // Only the real transport owner channel may request retirement. Never kill/reconnect/replay.
            handle.cancel()
            val closure = assertNotNull(handle.awaitTerminal(), "No verified closure: retain quarantine; do not force helper cleanup or retry")
            assertTrue(closure.cleanupAcknowledged, "Generation cleanup is unverified; quarantine is not a success or restart proof")
        }
    }

    private fun encode(payload: ByteArray): ByteArray {
        require(payload.size in 1..2048)
        return ByteBuffer.allocate(payload.size + 2).order(ByteOrder.BIG_ENDIAN).putShort(payload.size.toShort()).put(payload).array()
    }

    private fun receiveReplyWhileLive(handle: OwnedProcessSession, generation: OwnedSessionDeadline): ByteArray {
        val buffer = ByteArray(2050)
        var received = 0
        var expected = -1
        while (expected < 0 || received < expected) {
            assertFalse(generation.expired(), "Probe reply exhausted the original generation deadline")
            assertEquals(OwnedSessionState.LIVE, handle.snapshot().state)
            val observation = handle.pollOutput(100) ?: continue
            assertEquals(OwnedSessionStream.STDOUT, observation.stream)
            val bytes = observation.bytes
            assertTrue(bytes.size <= buffer.size - received, "Bounded probe reply overflow")
            bytes.copyInto(buffer, received)
            received += bytes.size
            if (received >= 2 && expected < 0) {
                val size = (buffer[0].toInt() and 255) * 256 + (buffer[1].toInt() and 255)
                assertTrue(size in 1..2048)
                expected = size + 2
            }
            if (expected >= 0) assertTrue(received <= expected, "Unexpected extra probe bytes")
        }
        assertEquals(OwnedSessionState.LIVE, handle.snapshot().state)
        return buffer.copyOfRange(2, expected)
    }
}

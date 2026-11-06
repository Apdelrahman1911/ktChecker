package dev.ktchecker.cli

import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.json.*
import kotlin.test.*

/** Actual private codec/mailbox controls on inert DATA streams; never owner/model execution. */
class OwnerModelTransportFramesTest {
    @Test fun fragmentedLengthBodyAndUnicodeConsumeOnlyTheExactFrame() {
        val first = buildJsonObject { put("text", "é\uD83D\uDE80"); putJsonObject("nested") { put("n", 1) } }
        val second = transportTestRequest()
        val input = object : ByteArrayInputStream(transportTestFrame(first) + transportTestFrame(second)) {
            private var n = 0
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, minOf(length, ++n % 3 + 1))
        }
        assertEquals(first, OwnerModelTransportFrames.read(input))
        assertEquals(second, OwnerModelTransportFrames.read(input))
        assertEquals(-1, input.read())
    }

    @Test fun unsignedZeroOvermaximumAndHighBitLengthsFailBeforeAnyBodyRead() {
        for (length in listOf(0L, OWNER_MODEL_TRANSPORT_FRAME_BYTES + 1L, 0x80000000L, 0xffffffffL)) {
            val header = byteArrayOf((length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte())
            var bodyRead = false
            val input = object : ByteArrayInputStream(header) {
                override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                    if (available() == 0) bodyRead = true
                    return super.read(bytes, offset, count)
                }
            }
            assertEquals("owner_transport_frame_bound", assertFailsWith<CliProblem> { OwnerModelTransportFrames.read(input) }.code)
            assertFalse(bodyRead)
        }
    }

    @Test fun maximumDeclaredLengthIsAllowedButStillRequiresEveryBodyByte() {
        val n = OWNER_MODEL_TRANSPORT_FRAME_BYTES
        val header = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
        assertEquals("owner_transport_eof", assertFailsWith<CliProblem> {
            OwnerModelTransportFrames.read(ByteArrayInputStream(header))
        }.code)
        assertEquals(16_777_216, n); assertEquals(65_536, OWNER_MODEL_TRANSPORT_REQUESTS)
    }

    @Test fun truncatedHeadersBodiesAndZeroProgressNeverResynchronizeOrRetry() {
        val frame = transportTestFrame(buildJsonObject { put("x", "body") })
        for (length in listOf(0, 1, 2, 3, 4, frame.size - 1)) {
            assertEquals("owner_transport_eof", assertFailsWith<CliProblem> {
                OwnerModelTransportFrames.read(ByteArrayInputStream(frame.copyOf(length)))
            }.code)
        }
        val stalled = object : InputStream() {
            override fun read(): Int = error("bulk read required")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = 0
        }
        assertEquals("owner_transport_read_stalled", assertFailsWith<CliProblem> { OwnerModelTransportFrames.read(stalled) }.code)
    }

    @Test fun duplicateEscapedKeysMalformedUtf8SurrogatesBomAndNonobjectsAreRejected() {
        val malformed = listOf("{\"x\":1,\"x\":2}", "{\"outer\":{\"token\":1,\"\\u0074oken\":2}}",
            "{\"x\":\"\\ud800\"}", "{\"x\":\"\\udc00\"}", "{\"x\":1} trailing", "[]", "null", "\uFEFF{}")
        for (source in malformed) assertEquals("owner_transport_frame_invalid", assertFailsWith<CliProblem> {
            OwnerModelTransportFrames.read(ByteArrayInputStream(transportTestFrame(source.toByteArray(Charsets.UTF_8))))
        }.code)
        assertFailsWith<CliProblem> {
            OwnerModelTransportFrames.read(ByteArrayInputStream(transportTestFrame(byteArrayOf(123, 34, 120, 34, 58, 34, 0xc3.toByte(), 0x28, 34, 125))))
        }
    }

    @Test fun malformedForeignExtraFieldsNumericStringAndUnavailableMethodsPoisonPermanently() {
        val valid = transportTestRequest()
        val cases = listOf(transportTestChange(valid, "session", JsonPrimitive("b".repeat(64))),
            transportTestChange(valid, "id", JsonPrimitive("1")), transportTestChange(valid, "id", JsonPrimitive(0)),
            transportTestChange(valid, "id", JsonPrimitive(65_537)), transportTestChange(valid, "id", JsonPrimitive(1.0)),
            transportTestChange(valid, "method", JsonPrimitive("review.submit")),
            transportTestChange(valid, "method", JsonPrimitive("owner.close")),
            transportTestChange(valid, "params", JsonArray(emptyList())), JsonObject(valid + ("extra" to JsonNull)))
        for (frame in cases) {
            val state = transportTestState()
            assertFailsWith<CliProblem> { state.receive(frame) }
            assertTrue(state.poisoned)
            assertFailsWith<CliProblem> { state.receive(valid) }
        }
    }

    @Test fun firstIdMustBeOneAndOnlyOneRequestCanWaitOrExecuteBeforeActualResponse() {
        for (taken in listOf(false, true)) {
            val state = transportTestState()
            state.receive(transportTestRequest())
            if (taken) state.take()
            assertFailsWith<CliProblem> { state.receive(transportTestRequest(2)) }
            assertTrue(state.poisoned)
        }
        val state = transportTestState()
        assertFailsWith<CliProblem> { state.receive(transportTestRequest(2)) }
        assertTrue(state.poisoned)
    }

    @Test fun responseWithoutAnAlreadyRetainedOriginalIsRejectedBeforeWriting() {
        val (state, request) = transportTestActive()
        val output = ByteArrayOutputStream()
        assertFailsWith<CliProblem> { ownerModelTransportRespond(state, request, output, JsonObject(emptyMap())) }
        assertEquals(0, output.size()); assertTrue(state.poisoned); assertSame(request, state.currentRequest)
    }

    @Test fun oneLookaheadBecomesExecutableOnlyAfterTheWholePriorWriteReturns() {
        val (state, request) = transportTestActive()
        val original = Any(); request.returned.set(original)
        var wrote = 0
        val bytes = ByteArrayOutputStream()
        val output = object : OutputStream() {
            override fun write(value: Int) = error("bounded bulk writes required")
            override fun write(value: ByteArray, offset: Int, count: Int) {
                if (wrote++ == 0) state.receive(transportTestRequest(2, "review.events"))
                assertSame(request, state.currentRequest); assertSame(original, request.returned.get())
                bytes.write(value, offset, count)
            }
        }
        val result = buildJsonObject { put("inert", true) }
        ownerModelTransportRespond(state, request, output, result)
        assertFalse(state.poisoned); assertNull(state.currentRequest)
        val returnedFrame = OwnerModelTransportFrames.read(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(result, returnedFrame["result"])
        assertEquals(2, wrote)
        val next = state.take(); assertEquals(2L, next.id); assertEquals("review.events", next.method)
        assertSame(original, request.returned.get())
    }

    @Test fun bodyOrFlushFailurePoisonsLookaheadAndRetainsOriginalUncertainReturn() {
        for (failFlush in listOf(false, true)) {
            val (state, request) = transportTestActive()
            val original = Any(); request.returned.set(original)
            var writes = 0
            val output = object : OutputStream() {
                override fun write(value: Int) = error("bulk writes required")
                override fun write(bytes: ByteArray, offset: Int, count: Int) {
                    if (writes++ == 0) state.receive(transportTestRequest(2))
                    else if (!failFlush) throw IOException("SYNTHETIC inert output failure")
                }
                override fun flush() { if (failFlush) throw IOException("SYNTHETIC inert flush failure") }
            }
            assertFailsWith<IOException> { ownerModelTransportRespond(state, request, output, JsonObject(emptyMap())) }
            assertTrue(state.poisoned); assertSame(request, state.currentRequest)
            assertSame(original, request.returned.get()); assertNotNull(request.response)
            assertFailsWith<CliProblem> { state.take() }
            assertFailsWith<CliProblem> { state.receive(transportTestRequest(3)) }
        }
    }

    @Test fun encodingFailureRetainsTheOriginalBeforeAnyOutputAndNeverRetries() {
        val (state, request) = transportTestActive()
        val original = Any(); request.returned.set(original)
        val output = ByteArrayOutputStream()
        assertFailsWith<CliProblem> {
            ownerModelTransportRespond(state, request, output, buildJsonObject { put("invalid", "\uD800") })
        }
        assertTrue(state.poisoned); assertSame(original, request.returned.get())
        assertSame(request, state.currentRequest); assertEquals(0, output.size())
    }

    @Test fun poisonObservedBetweenHeaderAndBodyPreventsFurtherWritesOrFlush() {
        val (state, request) = transportTestActive()
        request.returned.set(Any()); var writes = 0; var flushes = 0
        val output = object : OutputStream() {
            override fun write(value: Int) = error("bulk writes required")
            override fun write(bytes: ByteArray, offset: Int, count: Int) { writes++; state.poison("SYNTHETIC_inert_EOF") }
            override fun flush() { flushes++ }
        }
        assertFailsWith<CliProblem> { ownerModelTransportRespond(state, request, output, JsonObject(emptyMap())) }
        assertEquals(1, writes); assertEquals(0, flushes); assertSame(request, state.currentRequest)
    }

    @Test fun expiryDuringHeaderOrFlushSynchronouslyRejectsWithoutAnyBackgroundObserver() {
        for (expireAtFlush in listOf(false, true)) {
            // Shortened INERT additional startup denial, never an owner/model phase or new retry.
            val deadline = OwnedApplicationDeadline.afterMillis(1_000)
            val state = OwnerModelTransportState(transportTestSession, deadline)
            state.receive(transportTestRequest()); val request = state.take()
            val original = Any(); request.returned.set(original)
            var writes = 0; var flushes = 0
            fun letOriginalExpire() { while (!deadline.isExpired()) Thread.sleep(1) }
            val output = object : OutputStream() {
                override fun write(value: Int) = error("bulk writes required")
                override fun write(bytes: ByteArray, offset: Int, count: Int) {
                    if (writes++ == 0) {
                        state.receive(transportTestRequest(2))
                        if (!expireAtFlush) letOriginalExpire()
                    }
                }
                override fun flush() { flushes++; if (expireAtFlush) letOriginalExpire() }
            }
            assertEquals("owner_model_original_deadline_expired", assertFailsWith<CliProblem> {
                ownerModelTransportRespond(state, request, output, JsonObject(emptyMap()))
            }.code)
            assertTrue(state.poisoned); assertSame(request, state.currentRequest); assertSame(original, request.returned.get())
            assertEquals(if (expireAtFlush) 2 else 1, writes); assertEquals(if (expireAtFlush) 1 else 0, flushes)
            assertFailsWith<CliProblem> { state.take() } // Queued id2 never executes after expired write.
        }
    }

    @Test fun deliveredIdsRemainTombstonesRatherThanRetriableRequestSlots() {
        val (state, request) = transportTestActive()
        request.returned.set(Any())
        ownerModelTransportRespond(state, request, ByteArrayOutputStream(), JsonObject(emptyMap()))
        assertFailsWith<CliProblem> { state.receive(transportTestRequest()) }
        assertTrue(state.poisoned); assertNull(state.currentRequest)
    }
}

package dev.ktchecker.providers.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

/** Synthetic framing/ledger tests only. No process or worker-application evidence is produced. */
class OwnedProcessSessionWireTest {
    private val magic: ByteArray get() = OwnedSessionWire.outputMagic
    private val prefix: ByteArray get() = magic + liveBound()

    private class Recorded(requests: List<OwnedSessionRequest> = emptyList()) : OwnedSessionWireListener {
        val ledger = OwnedSessionLedger()
        private val requests = requests.iterator()
        var bindings = 0
        val observations = ArrayList<OwnedSessionOutput>()
        var final: OwnedSessionNativeFinal? = null
        var ended = false
        var failure: OwnedSessionReason? = null
        init { reserveNext() }
        private fun reserveNext() {
            if (requests.hasNext()) ledger.reserve(requests.next().also { it.writingStarted = true; it.writeFinished = true })
        }
        override fun bound() { bindings++ }
        override fun output(stream: OwnedSessionStream, bytes: ByteArray) { observations.add(OwnedSessionOutput(stream, bytes)) }
        override fun ack(ack: OwnedSessionAck) {
            ledger.acceptAck(ack)
            ledger.releaseAcknowledged(ledger.pending!!)
            reserveNext()
        }
        override fun terminal(final: OwnedSessionNativeFinal) { ledger.validateFinal(final); this.final = final }
        override fun eof() { ended = true }
        override fun problem(reason: OwnedSessionReason) { failure = reason }
    }

    private fun request(control: OwnedSessionControl, sequence: Long, epoch: Long = 0, data: ByteArray? = null) =
        OwnedSessionRequest(control, sequence, epoch, OwnedSessionDeadline.afterMillis(5000), data)

    private fun parse(bytes: ByteArray, requests: List<OwnedSessionRequest> = emptyList(), outputLimit: Int = 524_288,
                      input: InputStream = ByteArrayInputStream(bytes)): Recorded = Recorded(requests).also {
        OwnedSessionFrameReader(liveTestNonce, 5000, 32_768, outputLimit, it).read(input)
    }

    private fun invalid(bytes: ByteArray, requests: List<OwnedSessionRequest> = emptyList(), outputLimit: Int = 524_288): Recorded =
        parse(bytes, requests, outputLimit).also { assertEquals(OwnedSessionReason.PROTOCOL, it.failure); assertFalse(it.ended) }

    private fun ordinaryRequests() = listOf(
        request(OwnedSessionControl.ARM, 1, 1), request(OwnedSessionControl.DATA, 2, data = byteArrayOf(1, 2, 3)),
        request(OwnedSessionControl.DISARM, 3, 1), request(OwnedSessionControl.CLOSE_STDIN, 4),
    )

    private fun ordinaryWire(): ByteArray = prefix + liveAck(OwnedSessionControl.ARM, 1, 1) +
        liveAck(OwnedSessionControl.DATA, 2, 3, 3) + liveFrame(2, byteArrayOf(7, 8)) +
        liveAck(OwnedSessionControl.DISARM, 3, 1) + liveAck(OwnedSessionControl.CLOSE_STDIN, 4) +
        liveFinal(accepted = 3, observed = 2, requests = 4, epoch = 1)

    @Test fun `control writer uses exact distinct preamble and one detached ordinary record`() {
        val output = ByteArrayOutputStream()
        OwnedSessionWire.writePreamble(output, liveTestNonce)
        request(OwnedSessionControl.ARM, 1, 1).writeTo(output, 23)
        val bytes = byteArrayOf(0, 0xff.toByte(), 0xc3.toByte(), 0x28)
        val data = request(OwnedSessionControl.DATA, 2, data = bytes)
        val saved = bytes.clone()
        bytes.fill(9)
        data.writeTo(output)
        request(OwnedSessionControl.DISARM, 3, 1).writeTo(output)
        request(OwnedSessionControl.CLOSE_STDIN, 4).writeTo(output)
        OwnedSessionWire.writeCancel(output)
        val arm = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN).putLong(1).putLong(1).putLong(23).array()
        val expected = "KTCCTL2\n".toByteArray() + liveTestNonce + liveFrame(17, arm) +
            liveFrame(18, ByteBuffer.allocate(8).putLong(2).array() + saved) +
            liveFrame(19, ByteBuffer.allocate(16).putLong(3).putLong(1).array()) +
            liveFrame(20, ByteBuffer.allocate(8).putLong(4).array()) + byteArrayOf(127, 0, 0, 0, 0)
        assertContentEquals(expected, output.toByteArray())
        assertFailsWith<IllegalArgumentException> { OwnedSessionWire.writePreamble(output, ByteArray(16)) }
        assertFailsWith<IllegalArgumentException> { request(OwnedSessionControl.ARM, 1, 1).writeTo(output, 0) }
        assertFailsWith<IllegalArgumentException> { request(OwnedSessionControl.DATA, 1, data = ByteArray(8193)) }
        assertFailsWith<IllegalArgumentException> { request(OwnedSessionControl.CLOSE_STDIN, 8193) }
    }

    @Test fun `separate opaque observations and provisional final do not require UTF8 or exit zero`() {
        val malformedUtf8 = byteArrayOf(0xc3.toByte(), 0x28)
        val result = parse(prefix + liveFrame(2, malformedUtf8) + liveFrame(3, byteArrayOf(9)) + liveFinal(observed = 3))
        assertNull(result.failure)
        assertTrue(result.ended)
        assertEquals(1, result.bindings)
        assertEquals(23, result.final!!.childExit)
        assertEquals(listOf(OwnedSessionStream.STDOUT, OwnedSessionStream.STDERR), result.observations.map { it.stream })
        assertContentEquals(malformedUtf8, result.observations[0].bytes)
        assertContentEquals(byteArrayOf(9), result.observations[1].bytes)
    }

    @Test fun `all control and final fields tolerate arbitrary small transport fragmentation`() {
        val wire = ordinaryWire()
        for (chunk in listOf(1, 2, 3, 7, 31)) {
            val input = object : ByteArrayInputStream(wire) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, minOf(length, chunk))
            }
            val result = parse(wire, ordinaryRequests(), input = input)
            assertNull(result.failure, "fragment=$chunk")
            assertTrue(result.ended)
            assertEquals(4L, result.ledger.ackCount)
            assertEquals(3L, result.ledger.ackedInput)
            assertEquals(1L, result.ledger.ackedEpoch)
        }
    }

    @Test fun `every truncated prefix fails without inventing a complete final and EOF`() {
        val wire = ordinaryWire()
        for (length in wire.indices) invalid(wire.copyOf(length), ordinaryRequests())
        assertNull(parse(wire, ordinaryRequests()).failure)
    }

    @Test fun `BOUND is unique first exact nonce and launch limits and rejects v1 wire`() {
        val wrongNonce = liveTestNonce.also { it[3] = 0 }
        for (wire in listOf(
            "KTCOWN1\n".toByteArray() + liveBound() + liveFinal(),
            magic + liveBound(wrongNonce), magic + liveBound(millis = 5001),
            magic + liveBound(inputLimit = 32_767), magic + liveBound(outputLimit = 524_287),
            magic + liveFrame(2, byteArrayOf(1)), magic + liveFinal(), prefix + liveBound(),
        )) invalid(wire)
        val v1 = SupervisorFrameReader(32_768)
        v1.read(ByteArrayInputStream(prefix + liveFinal()))
        assertEquals("process_supervisor_protocol_error", v1.snapshot().problem)
    }

    @Test fun `tag and unsigned length bounds fail before attempting invalid bodies`() {
        val headers = listOf(
            byteArrayOf(0, 0, 0, 0, 0), byteArrayOf(255.toByte(), 0, 0, 0, 0),
            byteArrayOf(2, -1, -1, -1, -1), byteArrayOf(2, -128, 0, 0, 0),
            byteArrayOf(2, 0, 0, 0, 0), byteArrayOf(3, 0, 0, 0, 0),
            byteArrayOf(2, 0, 0, 32, 1), byteArrayOf(4, 0, 0, 0, 31),
            byteArrayOf(4, 0, 0, 0, 33), byteArrayOf(5, 0, 0, 0, 79), byteArrayOf(5, 0, 0, 0, 81),
        )
        for (header in headers) {
            val bytes = prefix + header
            val input = object : ByteArrayInputStream(bytes) {
                override fun read(): Int { if (available() == 0) throw IOException("body must not be read"); return super.read() }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (available() == 0) throw IOException("body must not be read")
                    return super.read(bytes, offset, length)
                }
            }
            assertEquals(OwnedSessionReason.PROTOCOL, parse(bytes, input = input).failure)
        }
        invalid(magic + liveFrame(1, ByteArray(39)))
        invalid(magic + liveFrame(1, ByteArray(41)))
    }

    @Test fun `combined output allowance is incremental across descriptors`() {
        val smallPrefix = magic + liveBound(outputLimit = 10)
        val bad = invalid(smallPrefix + liveFrame(2, ByteArray(8)) + liveFrame(3, ByteArray(3)), outputLimit = 10)
        assertEquals(8, bad.observations.single().size)
        assertNull(parse(smallPrefix + liveFrame(2, ByteArray(8)) + liveFrame(3, ByteArray(2)) + liveFinal(observed = 10), outputLimit = 10).failure)
    }

    @Test fun `ACK scalars and exact pending tag sequence epoch size and cumulative count are enforced`() {
        val invalidArmAcks = listOf(
            liveAck(OwnedSessionControl.ARM, 0, 1), liveAck(OwnedSessionControl.ARM, -1, 1),
            liveAck(OwnedSessionControl.ARM, 8193, 1), liveAck(OwnedSessionControl.ARM, 2, 1),
            liveAck(OwnedSessionControl.ARM, 1, 1, reserved = 1), liveAck(OwnedSessionControl.ARM, 1, 0),
            liveAck(OwnedSessionControl.ARM, 1, 4097), liveAck(OwnedSessionControl.ARM, 1, 2),
            liveAck(OwnedSessionControl.ARM, 1, 1, 1), liveAck(OwnedSessionControl.ARM, 1, -1),
            liveAck(OwnedSessionControl.DATA, 1, 1, 1), liveAck(OwnedSessionControl.CLOSE_STDIN, 1),
            liveFrame(4, ByteBuffer.allocate(32).putInt(127).putInt(0).putLong(1).putLong(0).putLong(0).array()),
        )
        for (ack in invalidArmAcks) {
            val result = invalid(prefix + ack, listOf(request(OwnedSessionControl.ARM, 1, 1)))
            assertEquals(0L, result.ledger.ackCount)
        }
        for ((size, cumulative) in listOf(0L to 0L, 8193L to 8193L, 2L to 2L, 3L to 2L, 3L to -1L)) {
            val result = invalid(prefix + liveAck(OwnedSessionControl.DATA, 1, size, cumulative),
                listOf(request(OwnedSessionControl.DATA, 1, data = byteArrayOf(1, 2, 3))))
            assertEquals(0L, result.ledger.ackedInput)
        }
        invalid(prefix + liveAck(OwnedSessionControl.CLOSE_STDIN, 1, 1), listOf(request(OwnedSessionControl.CLOSE_STDIN, 1)))
    }

    @Test fun `unsolicited duplicate and decreasing acknowledgements are never retained as new credit`() {
        invalid(prefix + liveAck(OwnedSessionControl.ARM, 1, 1))
        val result = invalid(prefix + liveAck(OwnedSessionControl.ARM, 1, 1) + liveAck(OwnedSessionControl.ARM, 1, 1),
            listOf(request(OwnedSessionControl.ARM, 1, 1)))
        assertEquals(1L, result.ledger.ackCount)
        assertNull(result.ledger.pending)
        val ledger = OwnedSessionLedger()
        ledger.reserve(request(OwnedSessionControl.DATA, 1, data = byteArrayOf(1)))
        assertFailsWith<InvalidOwnedSessionWire> { ledger.acceptAck(OwnedSessionAck(OwnedSessionControl.DATA, 1, 1, 1)) }
    }

    @Test fun `abandoned current request remains correlatable but late ACK cannot mutate failed operation`() {
        val ledger = OwnedSessionLedger()
        val pending = request(OwnedSessionControl.DATA, 1, data = byteArrayOf(1, 2, 3))
        pending.writingStarted = true
        pending.result = OwnedSessionOperation.Failed(listOf(OwnedSessionReason.INTERRUPTED), 1, OwnedSessionDelivery.MAY_HAVE_BEEN_WRITTEN)
        val failed = pending.result
        ledger.reserve(pending)
        ledger.acceptAck(OwnedSessionAck(OwnedSessionControl.DATA, 1, 3, 3))
        ledger.validateFinal(OwnedSessionNativeFinal(1, OwnedSessionFlags.CANCELLED, null, 3, 3, 3, 0, 0, 1, 1, 0))
        assertSame(failed, pending.result)
        assertSame(pending, ledger.pending)
        assertEquals(3L, ledger.ackedInput)
        assertFailsWith<IllegalStateException> { ledger.reserve(request(OwnedSessionControl.DATA, 2, data = byteArrayOf(9))) }
    }

    @Test fun `final accepted prefix allows exactly the started unacknowledged DATA and partial writes`() {
        val ledger = OwnedSessionLedger()
        val pending = request(OwnedSessionControl.DATA, 1, data = byteArrayOf(1, 2, 3, 4))
        pending.writingStarted = true
        ledger.reserve(pending)
        val final = OwnedSessionNativeFinal(1, OwnedSessionFlags.CANCELLED or OwnedSessionFlags.INPUT_INCOMPLETE,
            null, 4, 2, 0, 0, 0, 1, 0, 0)
        validateOwnedSessionFinal(final, 32_768, 524_288, 0, 0, 0)
        ledger.validateFinal(final)
        assertSame(pending, ledger.pending)
        for (bad in listOf(final.copy(inputAccepted = 3), final.copy(inputWritten = 5), final.copy(inputAckFlushed = 1),
            final.copy(requestsAccepted = 2), final.copy(lastEpoch = 1))) {
            assertFailsWith<InvalidOwnedSessionWire> { ledger.validateFinal(bad) }
        }
        pending.writingStarted = false
        assertFailsWith<InvalidOwnedSessionWire> { ledger.validateFinal(final) }
        assertFailsWith<InvalidOwnedSessionWire> { OwnedSessionLedger().validateFinal(final) }
    }

    @Test fun `one unacknowledged ARM can advance final epoch once and never to an unknown epoch`() {
        val ledger = OwnedSessionLedger()
        ledger.reserve(request(OwnedSessionControl.ARM, 1, 1).also { it.writingStarted = true })
        val final = OwnedSessionNativeFinal(1, OwnedSessionFlags.CANCELLED, null, 0, 0, 0, 0, 0, 1, 0, 1)
        ledger.validateFinal(final)
        assertFailsWith<InvalidOwnedSessionWire> { ledger.validateFinal(final.copy(lastEpoch = 2)) }
        assertFailsWith<InvalidOwnedSessionWire> { ledger.validateFinal(final.copy(requestsAccepted = 0)) }
    }

    @Test fun `FINAL reserved fields unknown masks unsigned counters and impossible ledgers fail closed`() {
        for (bad in listOf(
            liveFinal(reserved = 1), liveFinal(cause = -1), liveFinal(cause = 13), liveFinal(exit = -2), liveFinal(exit = 256),
            liveFinal(flags = OwnedSessionFlags.COMPLETED or (1 shl 20)), liveFinal(exit = -1), liveFinal(exit = 126),
            liveFinal(accepted = -1), liveFinal(written = -1), liveFinal(inputAcked = -1),
            liveFinal(observed = -1), liveFinal(captured = -1), liveFinal(observed = 1, captured = 0),
            liveFinal(requests = -1), liveFinal(requests = 8193), liveFinal(acks = -1), liveFinal(epoch = -1), liveFinal(epoch = 4097),
            liveFinal(requests = 1, epoch = 1), liveFinal(accepted = 1, written = 2), liveFinal(accepted = 32_769),
            liveFinal(cause = 1, flags = OwnedSessionFlags.CANCELLED, exit = -1, requests = 2, acks = 0),
        )) invalid(prefix + bad)
        for (bit in listOf(OwnedSessionFlags.EXIT_OBSERVED, OwnedSessionFlags.LEADER_REAPED, OwnedSessionFlags.QUIESCENT,
            OwnedSessionFlags.GROUP_READY, OwnedSessionFlags.EXEC_CONFIRMED, OwnedSessionFlags.STDIN_CLOSED, OwnedSessionFlags.OUTPUT_EOF)) {
            invalid(prefix + liveFinal(flags = OwnedSessionFlags.COMPLETED xor bit))
        }
        invalid(prefix + liveFinal(flags = OwnedSessionFlags.COMPLETED or OwnedSessionFlags.CANCELLED))
    }

    @Test fun `noncompleted causes require their exact facts without claiming cleanup`() {
        val required = mapOf(1 to OwnedSessionFlags.CANCELLED, 2 to OwnedSessionFlags.TIMED_OUT,
            3 to OwnedSessionFlags.OUTPUT_LIMIT, 4 to OwnedSessionFlags.PARENT_LOST, 5 to OwnedSessionFlags.IO_FAILED,
            6 to OwnedSessionFlags.EXEC_FAILED, 8 to OwnedSessionFlags.SHUTDOWN_FAILED, 9 to OwnedSessionFlags.PROTOCOL_FAILED,
            10 to (OwnedSessionFlags.OWNERSHIP_LOST or OwnedSessionFlags.SHUTDOWN_FAILED),
            11 to OwnedSessionFlags.INPUT_LIMIT, 12 to OwnedSessionFlags.CONTROL_LIMIT)
        for ((cause, bits) in required) {
            val base = if (cause == 6) OwnedSessionFlags.GROUP_READY else 0
            val observed = if (cause == 3) 1L else 0L
            assertNull(parse(prefix + liveFinal(cause, base or bits, -1, observed = observed, captured = 0)).failure)
            for (bit in 0..19) {
                val flag = 1 shl bit
                if (bits and flag != 0) invalid(prefix + liveFinal(cause, base or (bits xor flag), -1, observed = observed, captured = 0))
            }
        }
    }

    @Test fun `NO_CHILD is explicit zero-counter prefork proof not an absent exit inference`() {
        val flags = OwnedSessionFlags.NO_CHILD or OwnedSessionFlags.QUIESCENT or OwnedSessionFlags.STDIN_CLOSED or OwnedSessionFlags.OUTPUT_EOF
        assertNull(parse(prefix + liveFinal(7, flags, -1)).failure)
        for (bit in listOf(OwnedSessionFlags.NO_CHILD, OwnedSessionFlags.QUIESCENT, OwnedSessionFlags.STDIN_CLOSED, OwnedSessionFlags.OUTPUT_EOF)) {
            invalid(prefix + liveFinal(7, flags xor bit, -1))
        }
        invalid(prefix + liveFinal(7, flags or OwnedSessionFlags.GROUP_READY, -1))
        invalid(prefix + liveFinal(7, flags or OwnedSessionFlags.EXIT_OBSERVED, 0))
        invalid(prefix + liveFinal(7, flags, -1, requests = 1, acks = 0))
        invalid(prefix + liveFinal(7, flags, -1, accepted = 1, inputAcked = 0))
        invalid(prefix + liveFinal(7, flags, -1, observed = 1))
    }

    @Test fun `execution quiescence input completeness and observed overflow bits cannot contradict counters`() {
        val cancel = OwnedSessionFlags.CANCELLED
        val exec = OwnedSessionFlags.GROUP_READY or OwnedSessionFlags.EXIT_OBSERVED or OwnedSessionFlags.EXEC_CONFIRMED
        assertNull(parse(prefix + liveFinal(exit = 137)).failure) // A numeric exit alone cannot distinguish signal exit.
        for (bad in listOf(
            liveFinal(1, cancel or exec, 126), liveFinal(1, cancel or (exec xor OwnedSessionFlags.GROUP_READY), 0),
            liveFinal(1, cancel or (exec xor OwnedSessionFlags.EXIT_OBSERVED), -1),
            liveFinal(1, cancel or exec or OwnedSessionFlags.EXEC_FAILED, 0), liveFinal(7, exec, 0),
            liveFinal(6, OwnedSessionFlags.EXEC_FAILED, -1), liveFinal(1, cancel or OwnedSessionFlags.LEADER_REAPED, -1),
            liveFinal(1, cancel or OwnedSessionFlags.OWNERSHIP_LOST, -1),
            liveFinal(1, cancel or OwnedSessionFlags.QUIESCENT or OwnedSessionFlags.SHUTDOWN_FAILED, -1),
            liveFinal(1, cancel or OwnedSessionFlags.INPUT_INCOMPLETE, -1),
            liveFinal(3, OwnedSessionFlags.OUTPUT_LIMIT or OwnedSessionFlags.OUTPUT_EOF, -1, observed = 1, captured = 0),
            liveFinal(3, OwnedSessionFlags.OUTPUT_LIMIT, -1, observed = 0),
            liveFinal(3, OwnedSessionFlags.OUTPUT_LIMIT, -1, observed = 524_288L + 8193, captured = 0),
        )) invalid(prefix + bad)
    }

    @Test fun `duplicate FINAL trailing bytes and failed EOF keep facts provisional not complete`() {
        for (tail in listOf(byteArrayOf(0), liveFinal(), liveFrame(2, byteArrayOf(7)))) {
            val result = invalid(prefix + liveFinal() + tail)
            assertNotNull(result.final)
        }
        val complete = prefix + liveFinal()
        val brokenEof = object : ByteArrayInputStream(complete) {
            override fun read(): Int = if (available() == 0) throw IOException("untrusted detail") else super.read()
        }
        val result = parse(complete, input = brokenEof)
        assertNotNull(result.final)
        assertFalse(result.ended)
        assertEquals(OwnedSessionReason.FRAME_IO, result.failure)
    }

    @Test fun `read exception and repeated no progress are fixed failures not empty success`() {
        val broken = object : InputStream() { override fun read(): Int = throw IOException("untrusted detail") }
        assertEquals(OwnedSessionReason.FRAME_IO, parse(byteArrayOf(), input = broken).failure)
        val stalled = object : InputStream() {
            override fun read(): Int = 0
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = 0
        }
        assertEquals(OwnedSessionReason.PROTOCOL, parse(byteArrayOf(), input = stalled).failure)
    }
}

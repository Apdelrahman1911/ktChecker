package dev.ktchecker.providers.runtime

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object OwnedSessionFlags {
    const val EXIT_OBSERVED = 0x00001
    const val LEADER_REAPED = 0x00002
    const val QUIESCENT = 0x00004
    const val GROUP_READY = 0x00008
    const val EXEC_CONFIRMED = 0x00010
    const val CANCELLED = 0x00020
    const val TIMED_OUT = 0x00040
    const val OUTPUT_LIMIT = 0x00080
    const val PARENT_LOST = 0x00100
    const val IO_FAILED = 0x00200
    const val EXEC_FAILED = 0x00400
    const val PROTOCOL_FAILED = 0x00800
    const val OWNERSHIP_LOST = 0x01000
    const val SHUTDOWN_FAILED = 0x02000
    const val INPUT_LIMIT = 0x04000
    const val CONTROL_LIMIT = 0x08000
    const val INPUT_INCOMPLETE = 0x10000
    const val STDIN_CLOSED = 0x20000
    const val OUTPUT_EOF = 0x40000
    const val NO_CHILD = 0x80000
    const val KNOWN = 0xFFFFF
    const val COMPLETED = 0x6001F
}

/** Provisional until FINAL+EOF+real helper exit+image/quiescence/pump/cleanup gates all validate. */
internal data class OwnedSessionNativeFinal(
    val cause: Int,
    val flags: Int,
    val childExit: Int?,
    val inputAccepted: Long,
    val inputWritten: Long,
    val inputAckFlushed: Long,
    val outputObserved: Long,
    val outputCaptured: Long,
    val requestsAccepted: Long,
    val acksFlushed: Long,
    val lastEpoch: Long,
) {
    fun has(flag: Int): Boolean = flags and flag != 0
}

/** One ordinary request cell. All mutable fields are guarded by the transport's single state lock. */
internal class OwnedSessionRequest(
    val control: OwnedSessionControl,
    val sequence: Long,
    val epoch: Long,
    val deadline: OwnedSessionDeadline,
    bytes: ByteArray? = null,
) {
    private val content = bytes?.clone()
    val dataSize: Int get() = content?.size ?: 0
    var writingStarted = false
    var writeFinished = false
    var ack: OwnedSessionAck? = null
    var result: OwnedSessionOperation? = null

    init {
        require(sequence in 1..OwnedSessionLimits.REQUESTS)
        require((control == OwnedSessionControl.DATA) == (content != null))
        require(content == null || content.size in 1..OwnedSessionLimits.CHUNK)
    }

    /** Header and body form ONE record; only the fixed writer calls this, with no state lock held. */
    fun writeTo(output: OutputStream, remainingArmMillis: Long = 0) {
        val metadata = when (control) {
            OwnedSessionControl.ARM -> 24
            OwnedSessionControl.DATA, OwnedSessionControl.CLOSE_STDIN -> 8
            OwnedSessionControl.DISARM -> 16
        }
        if (control == OwnedSessionControl.ARM) require(remainingArmMillis in 1..OwnedSessionLimits.MAX_MILLIS)
        val header = ByteBuffer.allocate(5 + metadata).order(ByteOrder.BIG_ENDIAN)
        header.put(control.tag.toByte()).putInt(metadata + dataSize).putLong(sequence)
        when (control) {
            OwnedSessionControl.ARM -> header.putLong(epoch).putLong(remainingArmMillis)
            OwnedSessionControl.DISARM -> header.putLong(epoch)
            else -> Unit
        }
        output.write(header.array())
        content?.let(output::write)
    }
}

/**
 * Bounded aggregate ledger plus exactly one outstanding request, including abandoned callers.
 * Caller supplies synchronization. ACK correlation is independent of ordinary-call success.
 */
internal class OwnedSessionLedger {
    var pending: OwnedSessionRequest? = null
        private set
    var ackCount = 0L
        private set
    var ackedInput = 0L
        private set
    var ackedEpoch = 0L
        private set

    fun reserve(request: OwnedSessionRequest) {
        check(pending == null && request.sequence == ackCount + 1)
        pending = request
    }

    fun acceptAck(ack: OwnedSessionAck) {
        val request = pending ?: invalidSessionWire()
        sessionWireRequire(request.writingStarted && request.ack == null && ackCount < OwnedSessionLimits.REQUESTS)
        sessionWireRequire(ack.sequence == request.sequence && ack.sequence == ackCount + 1 && ack.control == request.control)
        when (request.control) {
            OwnedSessionControl.ARM, OwnedSessionControl.DISARM -> {
                sessionWireRequire(ack.valueA == request.epoch && ack.valueB == 0L)
                if (request.control == OwnedSessionControl.ARM) {
                    sessionWireRequire(request.epoch == ackedEpoch + 1 && request.epoch <= OwnedSessionLimits.EPOCHS)
                    ackedEpoch = request.epoch
                }
            }
            OwnedSessionControl.DATA -> {
                sessionWireRequire(request.dataSize.toLong() <= OwnedSessionLimits.MAX_BYTES - ackedInput)
                sessionWireRequire(ack.valueA == request.dataSize.toLong() && ack.valueB == ackedInput + request.dataSize)
                ackedInput += request.dataSize
            }
            OwnedSessionControl.CLOSE_STDIN -> sessionWireRequire(ack.valueA == 0L && ack.valueB == 0L)
        }
        ackCount++
        request.ack = ack
    }

    fun releaseAcknowledged(request: OwnedSessionRequest) {
        check(pending === request && request.ack != null && request.writeFinished)
        pending = null
    }

    fun validateFinal(final: OwnedSessionNativeFinal) {
        sessionWireRequire(final.acksFlushed == ackCount && final.inputAckFlushed == ackedInput)
        var expectedInput = ackedInput
        var expectedEpoch = ackedEpoch
        if (final.requestsAccepted == ackCount + 1) {
            val request = pending ?: invalidSessionWire()
            sessionWireRequire(request.writingStarted && request.ack == null && request.sequence == ackCount + 1)
            if (request.control == OwnedSessionControl.DATA) expectedInput += request.dataSize
            if (request.control == OwnedSessionControl.ARM) expectedEpoch = request.epoch
        } else sessionWireRequire(final.requestsAccepted == ackCount)
        sessionWireRequire(final.inputAccepted == expectedInput && final.lastEpoch == expectedEpoch)
        sessionWireRequire(final.inputWritten in ackedInput..expectedInput)
    }
}

internal interface OwnedSessionWireListener {
    fun bound()
    fun output(stream: OwnedSessionStream, bytes: ByteArray)
    fun ack(ack: OwnedSessionAck)
    fun terminal(final: OwnedSessionNativeFinal)
    fun eof()
    fun problem(reason: OwnedSessionReason)
}

internal object OwnedSessionWire {
    val outputMagic: ByteArray get() = "KTCLIV2\n".toByteArray(Charsets.US_ASCII)
    private val controlMagic: ByteArray get() = "KTCCTL2\n".toByteArray(Charsets.US_ASCII)

    fun writePreamble(output: OutputStream, nonce: ByteArray) {
        require(nonce.size == 16 && nonce.any { it != 0.toByte() })
        output.write(controlMagic + nonce)
    }

    fun writeCancel(output: OutputStream) { output.write(byteArrayOf(127, 0, 0, 0, 0)) }
}

/** Strict incremental framing. Buffers never exceed a single declared protocol-bounded payload. */
internal class OwnedSessionFrameReader(
    nonce: ByteArray,
    private val generationMillis: Long,
    private val inputLimit: Int,
    private val outputLimit: Int,
    private val listener: OwnedSessionWireListener,
) {
    private val nonce = nonce.clone()

    init {
        require(this.nonce.size == 16 && this.nonce.any { it != 0.toByte() })
        require(generationMillis in 1..OwnedSessionLimits.MAX_MILLIS)
        require(inputLimit in 1..OwnedSessionLimits.MAX_BYTES && outputLimit in 1..OwnedSessionLimits.MAX_BYTES)
    }

    fun read(input: InputStream) {
        var bound = false
        var terminal = false
        var outputBytes = 0L
        var ackCount = 0L
        var ackInput = 0L
        try {
            sessionWireRequire(readExact(input, 8).contentEquals(OwnedSessionWire.outputMagic))
            while (true) {
                val tag = input.read()
                if (tag == -1) {
                    sessionWireRequire(terminal)
                    listener.eof()
                    return
                }
                sessionWireRequire(tag in 0..255 && !terminal)
                val length = ByteBuffer.wrap(readExact(input, 4)).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
                if (!bound) {
                    sessionWireRequire(tag == 1 && length == 40L)
                    val bytes = ByteBuffer.wrap(readExact(input, 40)).order(ByteOrder.BIG_ENDIAN)
                    val receivedNonce = ByteArray(16).also { bytes.get(it) }
                    sessionWireRequire(receivedNonce.contentEquals(nonce) && bytes.long == generationMillis &&
                        bytes.long == inputLimit.toLong() && bytes.long == outputLimit.toLong())
                    listener.bound()
                    bound = true
                    continue
                }
                when (tag) {
                    2, 3 -> {
                        sessionWireRequire(length in 1L..OwnedSessionLimits.CHUNK.toLong() && length <= outputLimit - outputBytes)
                        val bytes = readExact(input, length.toInt())
                        outputBytes += length
                        listener.output(if (tag == 2) OwnedSessionStream.STDOUT else OwnedSessionStream.STDERR, bytes)
                    }
                    4 -> {
                        sessionWireRequire(length == 32L && ackCount < OwnedSessionLimits.REQUESTS)
                        val data = ByteBuffer.wrap(readExact(input, 32)).order(ByteOrder.BIG_ENDIAN)
                        val kind = data.int
                        sessionWireRequire(data.int == 0)
                        val control = OwnedSessionControl.entries.singleOrNull { it.tag == kind } ?: invalidSessionWire()
                        val ack = OwnedSessionAck(control, data.long, data.long, data.long)
                        sessionWireRequire(ack.sequence in 1..OwnedSessionLimits.REQUESTS && ack.valueA >= 0 && ack.valueB >= 0)
                        sessionWireRequire(ack.sequence == ackCount + 1)
                        when (control) {
                            OwnedSessionControl.ARM, OwnedSessionControl.DISARM ->
                                sessionWireRequire(ack.valueA in 1..OwnedSessionLimits.EPOCHS && ack.valueB == 0L)
                            OwnedSessionControl.DATA -> sessionWireRequire(ack.valueA in 1L..OwnedSessionLimits.CHUNK.toLong() &&
                                ack.valueA <= inputLimit - ackInput && ack.valueB == ackInput + ack.valueA)
                            OwnedSessionControl.CLOSE_STDIN -> sessionWireRequire(ack.valueA == 0L && ack.valueB == 0L)
                        }
                        listener.ack(ack) // Exact current request and cumulative values, including late ACKs.
                        ackCount++
                        if (control == OwnedSessionControl.DATA) ackInput += ack.valueA
                    }
                    5 -> {
                        sessionWireRequire(length == 80L)
                        val data = ByteBuffer.wrap(readExact(input, 80)).order(ByteOrder.BIG_ENDIAN)
                        val cause = data.int
                        val flags = data.int
                        val childExit = data.int
                        sessionWireRequire(data.int == 0 && childExit in -1..255)
                        val final = OwnedSessionNativeFinal(cause, flags, childExit.takeIf { it >= 0 },
                            data.long, data.long, data.long, data.long, data.long, data.long, data.long, data.long)
                        validateOwnedSessionFinal(final, inputLimit, outputLimit, outputBytes, ackCount, ackInput)
                        listener.terminal(final) // Checks the accepted request prefix/last epoch against the ledger.
                        terminal = true
                    }
                    else -> invalidSessionWire()
                }
            }
        } catch (_: InvalidOwnedSessionWire) {
            listener.problem(OwnedSessionReason.PROTOCOL)
        } catch (_: Exception) {
            listener.problem(OwnedSessionReason.FRAME_IO)
        }
    }

    private fun readExact(input: InputStream, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        var empty = 0
        while (offset < size) {
            val read = input.read(bytes, offset, size - offset)
            sessionWireRequire(read in -1..(size - offset) && read != -1)
            if (read == 0) { if (++empty > 16) invalidSessionWire() }
            else { offset += read; empty = 0 }
        }
        return bytes
    }
}

internal class InvalidOwnedSessionWire : Exception()
internal fun invalidSessionWire(): Nothing = throw InvalidOwnedSessionWire()
internal fun sessionWireRequire(condition: Boolean) { if (!condition) invalidSessionWire() }

internal fun validateOwnedSessionFinal(
    final: OwnedSessionNativeFinal, inputLimit: Int, outputLimit: Int,
    receivedOutput: Long, receivedAcks: Long, acknowledgedInput: Long,
) {
    sessionWireRequire(final.cause in 0..12 && final.flags and OwnedSessionFlags.KNOWN.inv() == 0)
    sessionWireRequire(final.childExit == null || final.childExit in 0..255)
    sessionWireRequire(final.inputAccepted in 0L..inputLimit.toLong() && final.inputWritten in 0..final.inputAccepted &&
        final.inputAckFlushed in 0..final.inputWritten && final.inputAckFlushed == acknowledgedInput)
    sessionWireRequire(final.outputCaptured == receivedOutput && final.outputCaptured in 0L..outputLimit.toLong() &&
        final.outputObserved in final.outputCaptured..(outputLimit.toLong() + OwnedSessionLimits.CHUNK))
    sessionWireRequire(final.requestsAccepted in 0..OwnedSessionLimits.REQUESTS && final.acksFlushed in 0..final.requestsAccepted &&
        final.requestsAccepted - final.acksFlushed <= 1 && final.acksFlushed == receivedAcks)
    sessionWireRequire(final.lastEpoch in 0..OwnedSessionLimits.EPOCHS && final.lastEpoch <= final.requestsAccepted)
    sessionWireRequire(final.has(OwnedSessionFlags.INPUT_INCOMPLETE) == (final.inputAccepted > final.inputWritten))
    sessionWireRequire(final.has(OwnedSessionFlags.EXIT_OBSERVED) == (final.childExit != null))
    if (final.has(OwnedSessionFlags.LEADER_REAPED)) sessionWireRequire(final.has(OwnedSessionFlags.EXIT_OBSERVED))
    if (final.outputObserved != final.outputCaptured) {
        sessionWireRequire(final.has(OwnedSessionFlags.OUTPUT_LIMIT) || final.has(OwnedSessionFlags.IO_FAILED))
    }
    if (final.has(OwnedSessionFlags.OUTPUT_LIMIT)) {
        sessionWireRequire(final.outputObserved > final.outputCaptured && !final.has(OwnedSessionFlags.OUTPUT_EOF))
    }
    if (final.has(OwnedSessionFlags.EXEC_CONFIRMED)) {
        sessionWireRequire(final.has(OwnedSessionFlags.GROUP_READY) && final.has(OwnedSessionFlags.EXIT_OBSERVED) &&
            !final.has(OwnedSessionFlags.EXEC_FAILED) && !final.has(OwnedSessionFlags.NO_CHILD) && final.childExit != 126)
    }
    if (final.has(OwnedSessionFlags.EXEC_FAILED)) {
        sessionWireRequire(final.has(OwnedSessionFlags.GROUP_READY) && !final.has(OwnedSessionFlags.EXEC_CONFIRMED))
    }
    if (final.has(OwnedSessionFlags.OWNERSHIP_LOST)) {
        sessionWireRequire(final.has(OwnedSessionFlags.SHUTDOWN_FAILED) && !final.has(OwnedSessionFlags.QUIESCENT))
    }
    if (final.has(OwnedSessionFlags.QUIESCENT)) {
        sessionWireRequire(final.has(OwnedSessionFlags.STDIN_CLOSED) && !final.has(OwnedSessionFlags.SHUTDOWN_FAILED))
        if (!final.has(OwnedSessionFlags.NO_CHILD)) {
            sessionWireRequire(final.has(OwnedSessionFlags.EXIT_OBSERVED) && final.has(OwnedSessionFlags.LEADER_REAPED))
        }
    }
    if (final.has(OwnedSessionFlags.NO_CHILD)) {
        val impossible = OwnedSessionFlags.GROUP_READY or OwnedSessionFlags.EXIT_OBSERVED or OwnedSessionFlags.LEADER_REAPED or
            OwnedSessionFlags.EXEC_CONFIRMED or OwnedSessionFlags.EXEC_FAILED
        sessionWireRequire(final.cause != 0 && final.childExit == null && final.flags and impossible == 0 &&
            final.has(OwnedSessionFlags.QUIESCENT) && final.has(OwnedSessionFlags.STDIN_CLOSED) && final.has(OwnedSessionFlags.OUTPUT_EOF) &&
            final.inputAccepted == 0L && final.inputWritten == 0L && final.inputAckFlushed == 0L && final.outputObserved == 0L &&
            final.outputCaptured == 0L && final.requestsAccepted == 0L && final.acksFlushed == 0L && final.lastEpoch == 0L)
    }
    val required = when (final.cause) {
        1 -> OwnedSessionFlags.CANCELLED
        2 -> OwnedSessionFlags.TIMED_OUT
        3 -> OwnedSessionFlags.OUTPUT_LIMIT
        4 -> OwnedSessionFlags.PARENT_LOST
        5 -> OwnedSessionFlags.IO_FAILED
        6 -> OwnedSessionFlags.EXEC_FAILED
        8 -> OwnedSessionFlags.SHUTDOWN_FAILED
        9 -> OwnedSessionFlags.PROTOCOL_FAILED
        10 -> OwnedSessionFlags.OWNERSHIP_LOST or OwnedSessionFlags.SHUTDOWN_FAILED
        11 -> OwnedSessionFlags.INPUT_LIMIT
        12 -> OwnedSessionFlags.CONTROL_LIMIT
        else -> 0
    }
    sessionWireRequire(final.flags and required == required)
    if (final.cause == 7) sessionWireRequire(!final.has(OwnedSessionFlags.EXEC_CONFIRMED))
    if (final.cause == 0) {
        sessionWireRequire(final.flags == OwnedSessionFlags.COMPLETED && final.childExit != null && final.childExit != 126 &&
            final.outputObserved == final.outputCaptured && final.inputAccepted == final.inputWritten &&
            final.inputWritten == final.inputAckFlushed && final.requestsAccepted == final.acksFlushed)
    }
}

package dev.ktchecker.cli

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*

internal const val OWNER_MODEL_TRANSPORT_PROTOCOL = "ktchecker-owner-session/1"
internal const val OWNER_MODEL_TRANSPORT_FRAME_BYTES = 16_777_216
internal const val OWNER_MODEL_TRANSPORT_REQUESTS = 65_536

/** Length-framed PRIVATE owner channel. No NDJSON/MCP route or unbounded resynchronization. */
internal object OwnerModelTransportFrames {
    fun read(input: InputStream): JsonObject {
        val header = ByteArray(4)
        readExactly(input, header)
        val length = header.fold(0L) { value, byte -> (value shl 8) or (byte.toInt() and 255).toLong() }
        ownerModelRequire(length in 1..OWNER_MODEL_TRANSPORT_FRAME_BYTES.toLong(), "owner_transport_frame_bound")
        val bytes = ByteArray(length.toInt())
        readExactly(input, bytes)
        return try {
            ContractJson.parseToJsonElement(strictJsonDocument(bytes, "owner_transport_frame_invalid")) as? JsonObject
                ?: throw CliProblem("owner_transport_frame_invalid", "The private frame must be an object.")
        } catch (_: Exception) { throw CliProblem("owner_transport_frame_invalid", "Invalid bounded private frame.") }
    }
    private fun readExactly(input: InputStream, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            ownerModelRequire(count > 0, if (count < 0) "owner_transport_eof" else "owner_transport_read_stalled")
            offset += count
        }
    }
    fun encode(value: JsonObject): ByteArray {
        val bytes = ownerModelStrictBytes(value)
        ownerModelRequire(bytes.size in 1..OWNER_MODEL_TRANSPORT_FRAME_BYTES, "owner_transport_frame_bound")
        strictJsonDocument(bytes, "owner_transport_frame_invalid")
        return bytes
    }
    /** One writer, bounded frame/chunks and NO state monitor across a potentially blocked write.
     * Reader+deadline observer remain able to publish denial while this thread retains custody.
     * A blocked write is NEVER interrupted by closing/signalling a process or retrying the frame.
     */
    fun write(output: OutputStream, bytes: ByteArray, state: OwnerModelTransportState) {
        ownerModelRequire(bytes.size in 1..OWNER_MODEL_TRANSPORT_FRAME_BYTES, "owner_transport_frame_bound")
        state.observeDeadlines()
        val length = bytes.size
        output.write(byteArrayOf((length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte()))
        var offset = 0
        while (offset < bytes.size) {
            state.observeDeadlines()
            val count = minOf(65_536, bytes.size - offset)
            output.write(bytes, offset, count)
            offset += count
        }
        state.observeDeadlines(); output.flush(); state.observeDeadlines()
    }
}

internal fun ownerModelTransportKeys(value: JsonObject, keys: Set<String>) =
    ownerModelRequire(value.keys == keys, "owner_transport_frame_fields")
internal fun ownerModelTransportString(value: JsonObject, name: String): String {
    val raw = value[name] as? JsonPrimitive
    ownerModelRequire(raw != null && raw.isString, "owner_transport_frame_type")
    return requireNotNull(raw).content
}
internal fun ownerModelTransportInteger(value: JsonObject, name: String, min: Long, max: Long): Long {
    val raw = value[name] as? JsonPrimitive
    ownerModelRequire(raw != null && !raw.isString && raw.content.matches(Regex("0|[1-9][0-9]{0,15}")), "owner_transport_frame_type")
    val number = raw?.longOrNull
    ownerModelRequire(number != null && number in min..max, "owner_transport_frame_range")
    return requireNotNull(number)
}
internal fun ownerModelTransportObject(value: JsonObject, name: String): JsonObject = value[name] as? JsonObject
    ?: throw CliProblem("owner_transport_frame_type", "A private object field is required.")

/** Retained BEFORE invocation; its actual returned object is stored BEFORE encoding/writing. */
internal class OwnerModelTransportRequest(val id: Long, val method: String, val params: JsonObject) {
    val returned = AtomicReference<Any?>()
    @Volatile var response: JsonObject? = null
    @Volatile var fences: List<OwnerModelTransportPhase> = emptyList()
}

/** State-only bounded mailbox. Pump calls no owner/provider/config/user functions. */
internal class OwnerModelTransportState(val session: String, private val startup: OwnedApplicationDeadline) {
    private val lock = Object()
    val denied = AtomicBoolean()
    private val firstFailure = AtomicReference<String?>()
    private var accepted = 0L
    @Volatile private var completed = 0L
    private var queued: OwnerModelTransportRequest? = null
    @Volatile private var current: OwnerModelTransportRequest? = null
    private var responseWriting = false
    private var pendingClock: OwnerModelTransportPhase? = null
    private val clocks = mutableListOf<OwnerModelTransportPhase>() // <=1+2*max32; never evicted/rearmed.
    @Volatile private var overall: OwnerModelTransportPhase? = null
    val poisoned: Boolean get() = denied.get()
    val failureCode: String get() = firstFailure.get() ?: "owner_transport_outcome_unknown"
    val currentRequest: OwnerModelTransportRequest? get() = current

    fun poison(code: String) {
        // Publish atomically even if the output writer is indefinitely backpressured.
        firstFailure.compareAndSet(null, code)
        denied.set(true)
        synchronized(lock) { lock.notifyAll() }
    }
    fun checkpoint() { ownerModelRequire(!denied.get(), "owner_transport_poisoned") }
    fun observeDeadlines() {
        checkpoint()
        if (completed == 0L) ownerModelCheckpoint(startup)
        overall?.checkHardOrAccepted()
        current?.fences?.forEach { it.checkHardOrAccepted() }
    }
    /** Actual dedicated reader path. Any EOF/malformed/replay is terminal, not a clean shutdown. */
    fun pump(input: InputStream) {
        try { while (!poisoned) receive(OwnerModelTransportFrames.read(input)) }
        catch (_: Throwable) { poison("owner_transport_input_unknown") }
    }
    fun receive(frame: JsonObject) {
        try { receiveFrame(frame) }
        catch (failure: Throwable) { poison("owner_transport_frame_unknown"); throw failure }
    }
    private fun receiveFrame(frame: JsonObject) {
        checkpoint()
        ownerModelRequire(ownerModelTransportString(frame, "session") == session, "owner_transport_foreign_session")
        when (ownerModelTransportString(frame, "kind")) {
            "request" -> {
                ownerModelTransportKeys(frame, setOf("kind", "session", "id", "method", "params"))
                val id = ownerModelTransportInteger(frame, "id", 1, OWNER_MODEL_TRANSPORT_REQUESTS.toLong())
                val method = ownerModelTransportString(frame, "method")
                ownerModelRequire(method in METHODS, "owner_transport_method_unavailable")
                val request = OwnerModelTransportRequest(id, method, ownerModelTransportObject(frame, "params"))
                synchronized(lock) {
                    checkpoint()
                    ownerModelRequire(id == accepted + 1 && queued == null && (current == null || responseWriting),
                        "owner_transport_request_replayed_or_concurrent")
                    // Exactly ONE lookahead slot is allowed only once an actual result is retained
                    // and its response is being written. It cannot execute before write success.
                    accepted = id; queued = request; lock.notifyAll()
                }
            }
            "clock.reply" -> synchronized(lock) {
                checkpoint()
                ownerModelTransportKeys(frame, setOf("kind", "session", "requestId", "challenge", "phase", "ordinal", "remainingMillis"))
                val selected = pendingClock ?: throw CliProblem("owner_transport_clock_unrequested", "No pending original clock challenge.")
                ownerModelRequire(!responseWriting && current?.id == selected.requestId, "owner_transport_clock_order")
                selected.accept(frame)
                pendingClock = null; lock.notifyAll()
            }
            "deny" -> { ownerModelTransportKeys(frame, setOf("kind", "session")); poison("owner_transport_host_denied") }
            else -> throw CliProblem("owner_transport_frame_kind", "Unknown private frame kind.")
        }
    }
    fun take(): OwnerModelTransportRequest {
        while (true) {
            observeDeadlines()
            synchronized(lock) {
                checkpoint()
                ownerModelRequire(current == null && !responseWriting && pendingClock == null, "owner_transport_request_state")
                val request = queued
                if (request != null) {
                    ownerModelRequire(request.id == completed + 1, "owner_transport_request_order")
                    queued = null; current = request
                    return request
                }
                lock.wait(100) // Fixed original caps above, never a new per-wait budget.
            }
        }
    }
    fun challenge(request: OwnerModelTransportRequest, kind: OwnerModelTransportClockKind, ordinal: Long,
                  nonce: String): OwnerModelTransportPhase = synchronized(lock) {
        checkpoint()
        ownerModelRequire(current === request && !responseWriting && pendingClock == null &&
            clocks.size < 1 + 2 * OWNER_MODEL_MAX_PATCHES && nonce.matches(Regex("[0-9a-f]{64}")) &&
            nonce != session && clocks.none { it.challenge == nonce || it.kind == kind && it.ordinal == ordinal },
            "owner_transport_clock_replayed_or_concurrent")
        ownerModelRequire(if (kind == OwnerModelTransportClockKind.OVERALL) ordinal == 0L && request.id == 1L
            else ordinal in 1..OWNER_MODEL_MAX_PATCHES.toLong(), "owner_transport_clock_ordinal")
        // Capture the actual JVM monotonic start BEFORE creating/writing the challenge frame.
        OwnerModelTransportPhase(kind, ordinal, request.id, nonce, denied).also {
            clocks.add(it); pendingClock = it
            request.fences = request.fences + it
            if (kind == OwnerModelTransportClockKind.OVERALL) overall = it
        }
    }
    fun awaitClock(phase: OwnerModelTransportPhase) {
        while (true) {
            observeDeadlines()
            synchronized(lock) {
                checkpoint()
                if (phase.accepted) { phase.checkpoint(); return }
                ownerModelRequire(pendingClock === phase, "owner_transport_clock_unknown")
                lock.wait(100)
            }
        }
    }
    fun fence(request: OwnerModelTransportRequest, phases: List<OwnerModelTransportPhase>) = synchronized(lock) {
        checkpoint(); ownerModelRequire(current === request && !responseWriting, "owner_transport_request_state")
        request.fences = phases.toList()
    }
    fun beginResponse(request: OwnerModelTransportRequest, response: JsonObject) = synchronized(lock) {
        observeDeadlines()
        ownerModelRequire(current === request && !responseWriting && pendingClock == null && request.returned.get() != null,
            "owner_transport_response_without_original_return")
        request.response = response
        responseWriting = true
    }
    fun finishResponse(request: OwnerModelTransportRequest) = synchronized(lock) {
        observeDeadlines()
        ownerModelRequire(current === request && responseWriting && request.response != null,
            "owner_transport_response_state")
        completed = request.id; responseWriting = false; current = null
        // A delivered read-only status need not accumulate. The real owner retains all actual
        // attempts/admissions; strict counters retain replay tombstones. UNKNOWN returns stay here.
        lock.notifyAll()
    }
    companion object {
        private val METHODS = setOf("owner.open", "owner.configureCompilerPolicy", "owner.submitCompletedPatch", "owner.status",
            "review.events", "review.ack", "review.observe", "gate.prepare", "gate.complete")
    }
}

/** A whole actual return is already retained by the caller. Any encoding/write/late-cap failure
 * poisons BOTH this request and its one queued successor, with no output retry or cleanup.
 */
internal fun ownerModelTransportRespond(state: OwnerModelTransportState, request: OwnerModelTransportRequest,
                                       output: OutputStream, result: JsonObject) {
    try {
        val response = buildJsonObject {
            put("kind", "response"); put("session", state.session); put("id", request.id); put("result", result)
        }
        state.beginResponse(request, response)
        OwnerModelTransportFrames.write(output, OwnerModelTransportFrames.encode(response), state)
        state.finishResponse(request)
    } catch (failure: Throwable) { state.poison("owner_transport_response_unknown"); throw failure }
}

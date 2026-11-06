package dev.ktchecker.cli

import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.*

/** Inert byte/state fixtures ONLY: no Workbench, owner process, model, compiler or receipt grant. */
internal val transportTestSession = "a".repeat(64)
internal fun transportTestState(startupMillis: Long = OWNER_MODEL_ORIGINAL_MILLIS) =
    OwnerModelTransportState(transportTestSession, OwnedApplicationDeadline.afterMillis(startupMillis))
internal fun transportTestRequest(id: Long = 1, method: String = "owner.open", params: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
    put("kind", "request"); put("session", transportTestSession); put("id", id); put("method", method); put("params", params)
}
internal fun transportTestActive(startupMillis: Long = OWNER_MODEL_ORIGINAL_MILLIS): Pair<OwnerModelTransportState, OwnerModelTransportRequest> {
    val state = transportTestState(startupMillis)
    state.receive(transportTestRequest())
    return state to state.take()
}
internal fun transportTestFrame(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
    val count = bytes.size
    output.write(byteArrayOf((count ushr 24).toByte(), (count ushr 16).toByte(), (count ushr 8).toByte(), count.toByte()))
    output.write(bytes)
}.toByteArray()
internal fun transportTestFrame(value: JsonObject): ByteArray = transportTestFrame(OwnerModelTransportFrames.encode(value))
internal fun transportTestReply(phase: OwnerModelTransportPhase, remaining: Long = phase.originalMillis) = buildJsonObject {
    put("kind", "clock.reply"); put("session", transportTestSession); put("requestId", phase.requestId)
    put("challenge", phase.challenge); put("phase", phase.kind.wire); put("ordinal", phase.ordinal); put("remainingMillis", remaining)
}
internal fun transportTestChange(value: JsonObject, key: String, replacement: JsonElement) = JsonObject(value + (key to replacement))

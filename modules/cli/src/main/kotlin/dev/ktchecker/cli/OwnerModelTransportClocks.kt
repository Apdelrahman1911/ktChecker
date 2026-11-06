package dev.ktchecker.cli

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class OwnerModelTransportClockKind(val wire: String, val millis: Long) {
    OVERALL("overall", 900_000L), EXPORT("export", 120_000L), REVIEW("review", 300_000L),
}

/** Pure DENIAL arithmetic. Durations relative to the SAME captured JVM challenge start avoid
 * signed absolute nanoTime overflow. A scalar can only narrow a preexisting local hard cap.
 * No Node/JVM clock origins are compared, and receipt time never becomes a new origin.
 */
internal fun ownerModelTightenedDenialNanos(previous: Long, remainingMillis: Long, originalMillis: Long): Long {
    ownerModelRequire(originalMillis in setOf(120_000L, 300_000L, 900_000L) &&
        previous in 0..originalMillis * 1_000_000L && remainingMillis in 0..originalMillis,
        "owner_transport_clock_range")
    return minOf(previous, remainingMillis * 1_000_000L)
}

/** Pure elapsed-interval predicate; no clock origin, clock object or authority is created. */
internal fun ownerModelBeforeCapturedDenial(elapsedNanos: Long, allowanceNanos: Long): Boolean =
    elapsedNanos >= 0L && elapsedNanos < allowanceNanos

/** Owner-local original phase fence. This is NOT export/model/worker/execution authority.
 * Only the private same-process channel ledger constructs and retains it. It is unusable until
 * its ONE original nonce/request/phase/session-matched reply; all later replies poison the channel.
 */
internal class OwnerModelTransportPhase(
    val kind: OwnerModelTransportClockKind, val ordinal: Long, val requestId: Long, val challenge: String,
    private val denied: AtomicBoolean,
) : OwnerModelPhaseFence {
    private val challengeStartNanos = System.nanoTime()
    override val originalMillis: Long = kind.millis
    @Volatile private var allowanceNanos = originalMillis * 1_000_000L
    @Volatile var accepted: Boolean = false
        private set
    private val consumed = AtomicBoolean()

    fun challengeFrame(session: String): JsonObject = buildJsonObject {
        put("kind", "clock.challenge"); put("session", session); put("requestId", requestId)
        put("challenge", challenge); put("phase", kind.wire); put("ordinal", ordinal)
    }
    internal fun accept(frame: JsonObject) {
        ownerModelRequire(!denied.get() && consumed.compareAndSet(false, true), "owner_transport_clock_replied_twice")
        ownerModelRequire(ownerModelTransportInteger(frame, "requestId", 1, OWNER_MODEL_TRANSPORT_REQUESTS.toLong()) == requestId &&
            ownerModelTransportString(frame, "challenge") == challenge && ownerModelTransportString(frame, "phase") == kind.wire &&
            ownerModelTransportInteger(frame, "ordinal", 0, OWNER_MODEL_MAX_PATCHES.toLong()) == ordinal,
            "owner_transport_clock_response_mismatch")
        val remaining = ownerModelTransportInteger(frame, "remainingMillis", 0, originalMillis)
        allowanceNanos = ownerModelTightenedDenialNanos(allowanceNanos, remaining, originalMillis)
        checkHardOrAccepted() // Late or zero reply is denial; never published as usable.
        accepted = true // volatile publication AFTER the tighter expiry is fixed.
    }
    override fun checkpoint() {
        ownerModelRequire(accepted, "owner_transport_clock_unanswered")
        checkHardOrAccepted()
    }
    internal fun checkHardOrAccepted() {
        val elapsed = System.nanoTime() - challengeStartNanos
        ownerModelRequire(!denied.get() && !Thread.currentThread().isInterrupted &&
            ownerModelBeforeCapturedDenial(elapsed, allowanceNanos), "owner_transport_original_clock_expired")
    }
}

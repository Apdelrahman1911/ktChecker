package dev.ktchecker.providers.runtime

import dev.ktchecker.contracts.CancellationToken
import dev.ktchecker.workerprotocol.ApplicationWire
import java.security.SecureRandom
import java.util.Collections
import java.util.HexFormat

/** The original ingress clock, not a serializable deadline or a refreshable remaining-time budget. */
class OwnedApplicationDeadline private constructor(internal val original: OwnedSessionDeadline) {
    val identity: String = newApplicationIdentity()
    val durationMillis: Long get() = original.durationMillis
    fun isExpired(): Boolean = original.expired()

    companion object {
        /** Call BEFORE owner capture, preparation, queueing and authority callbacks. */
        fun afterMillis(millis: Long): OwnedApplicationDeadline =
            OwnedApplicationDeadline(OwnedSessionDeadline.afterMillis(millis))
    }
}

/** Object identity is the owner scope. Its detached label is not an execution capability. */
class OwnedApplicationOwner {
    val identity: String = newApplicationIdentity()
}

private val applicationRandom = SecureRandom()
private fun newApplicationIdentity(): String =
    HexFormat.of().formatHex(ByteArray(16).also(applicationRandom::nextBytes))

/** No arbitrary exception messages, worker text, paths or polling histories become failure reasons. */
enum class OwnedApplicationReason(val code: String) {
    PERMIT_USED("owned_application_permit_used"),
    OWNER_MISMATCH("owned_application_owner_mismatch"),
    WRONG_GENERATION("owned_application_wrong_generation"),
    BINDING("owned_application_binding_mismatch"),
    AUTHORITY("owned_application_authority_failed"),
    HELLO("owned_application_hello_invalid"),
    RESULT("owned_application_result_invalid"),
    CANCELLED("owned_application_cancelled"),
    CANCELLATION_CHECK("owned_application_cancellation_check_failed"),
    INTERRUPTED("owned_application_interrupted"),
    GENERATION_DEADLINE("owned_application_generation_deadline"),
    REQUEST_DEADLINE("owned_application_request_deadline"),
    CONCURRENT_OPERATION("owned_application_concurrent_operation"),
    NOT_READY("owned_application_not_ready"),
    JOB_LIMIT("owned_application_job_limit"),
    PROTOCOL("owned_application_protocol_error"),
    UNEXPECTED_OUTPUT("owned_application_unexpected_output"),
    WORKER_FATAL("owned_application_worker_fatal"),
    TRANSPORT_UNAVAILABLE("owned_application_transport_unavailable"),
    TRANSPORT_FAILED("owned_application_transport_failed"),
    TRANSPORT_NOT_LIVE("owned_application_transport_not_live"),
    OUTPUT_DRAIN_FAILED("owned_application_output_drain_failed"),
    OUTPUT_DRAIN_UNRESOLVED("owned_application_output_drain_unresolved"),
    CLEANUP_UNVERIFIED("owned_application_cleanup_unverified"),
    INTERNAL("owned_application_internal_failure"),
}

/** Delivery of this submission call, not of another invocation using the same grant. */
enum class OwnedApplicationDelivery { NOT_SENT, MAY_HAVE_BEEN_WRITTEN }

/**
 * CLOSED concerns transport custody/cleanup ONLY. It is never a compiler-job receipt.
 * NOT_REQUESTED means this invocation made no custody query, retirement or cleanup attempt;
 * it makes no statement about another invocation or the generation's live/closed custody.
 */
enum class OwnedApplicationCleanupDisposition { NOT_STARTED, CLOSED, QUARANTINED, UNVERIFIED, NOT_REQUESTED }

class OwnedApplicationRetirement internal constructor(
    val disposition: OwnedApplicationCleanupDisposition,
    reasons: Collection<OwnedApplicationReason>,
    transportReasonCodes: Collection<String> = emptyList(),
) {
    val reasons: List<OwnedApplicationReason> = applicationReasons(reasons)
    // Values originate only in the internal transport's fixed reason enum, never worker text.
    val transportReasonCodes: List<String> = Collections.unmodifiableList(transportReasonCodes.distinct().also {
        require(it.size <= OwnedSessionLimits.REASONS && it.all { code -> OwnedSessionReason.entries.any { r -> r.code == code } })
    })
}

class OwnedApplicationJobBinding internal constructor(
    val generation: String,
    val sequence: Int,
    val jobId: String,
    val helloDigest: String,
    val requestDigest: String,
)

sealed interface OwnedApplicationOpenResult {
    class Ready internal constructor(val session: OwnedApplicationSession) : OwnedApplicationOpenResult
    class Unavailable internal constructor(
        reasons: Collection<OwnedApplicationReason>,
        val cleanup: OwnedApplicationRetirement,
    ) : OwnedApplicationOpenResult {
        val reasons: List<OwnedApplicationReason> = applicationReasons(reasons)
    }
}

sealed interface OwnedApplicationJobResult<out T : Any> {
    class Accepted<T : Any> internal constructor(
        val value: T,
        val binding: OwnedApplicationJobBinding,
    ) : OwnedApplicationJobResult<T>
    class Unavailable internal constructor(
        reasons: Collection<OwnedApplicationReason>,
        val delivery: OwnedApplicationDelivery,
        val cleanup: OwnedApplicationRetirement,
    ) : OwnedApplicationJobResult<Nothing> {
        val reasons: List<OwnedApplicationReason> = applicationReasons(reasons)
    }
}

/** No raw write/control/launcher/transport handle is exposed across modules. */
interface OwnedApplicationSession {
    val hello: ApplicationWire.Hello
    /** Pre-admission denials affect this call only; a wrong target cannot consume a foreign grant. */
    fun <T : Any> submit(
        permit: OwnedApplicationJobPermit<T>,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): OwnedApplicationJobResult<T>
    fun retire(): OwnedApplicationRetirement
    /** Revokes application admission before it requests internal transport cancellation. */
    fun cancel()
}

internal fun applicationReasons(values: Collection<OwnedApplicationReason>): List<OwnedApplicationReason> =
    Collections.unmodifiableList(values.distinct().also { check(it.size <= OwnedApplicationReason.entries.size) })

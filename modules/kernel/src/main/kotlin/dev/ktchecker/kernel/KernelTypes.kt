package dev.ktchecker.kernel

import dev.ktchecker.contracts.AnalysisInput
import dev.ktchecker.contracts.PREVIEW_CONTRACT
import dev.ktchecker.contracts.ProviderResult
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** Preview API, NOT the protocol-1 host/observation certification described in the design. */
@Serializable data class ClientBinding(
    val ownerId: String,
    val workspaceId: String,
    val worktreeId: String,
    val clientId: String,
    val lineageId: String,
    val generation: Long = 1,
    val sessionId: String = lineageId,
)

/** A model-facing capability: deliberately cannot ACK or attest observation. */
@Serializable data class ClientCredential(val clientId: String, val token: String) {
    override fun toString(): String = "ClientCredential(clientId=$clientId, token=<redacted>)"
}
/** Kept by the trusted host adapter, never inserted into model context. */
@Serializable data class AdapterCredential(val clientId: String, val token: String) {
    override fun toString(): String = "AdapterCredential(clientId=$clientId, token=<redacted>)"
}
@Serializable data class ClientRegistration(
    val binding: ClientBinding,
    val client: ClientCredential,
    val adapter: AdapterCredential,
    val contractVersion: String = PREVIEW_CONTRACT,
)

@Serializable data class SubmitRequest(
    val patchId: String,
    val idempotencyKey: String,
    val input: AnalysisInput,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable data class ReviewStatus(
    val reviewId: String,
    val clientId: String,
    val patchId: String,
    val sequence: Long,
    val sourceSnapshotId: String,
    val state: String,
    val result: ProviderResult? = null,
    val reasons: List<String> = emptyList(),
    val cancelled: Boolean = false,
    val contractVersion: String = PREVIEW_CONTRACT,
    /** Present on the required plan_accepted event; binds every child, never an accepted prefix. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val parentPlan: ParentPlanStatus? = null,
)

/** Payload is immutable canonical JSON; its digest binds insertion and observation receipts. */
@Serializable data class ReviewEvent(
    val eventId: String,
    val clientId: String,
    val sequence: Long,
    val reviewId: String,
    val patchId: String,
    val sourceSnapshotId: String,
    val kind: String,
    val deliveryRequired: Boolean,
    val payloadDigest: String,
    val payload: String,
    val generation: Long,
    val contractVersion: String = PREVIEW_CONTRACT,
)
@Serializable data class EventPage(
    val events: List<ReviewEvent>,
    val cursor: String,
    val hasMore: Boolean,
    val contractVersion: String = PREVIEW_CONTRACT,
)

/** ACK proves durable context insertion only, never model observation. */
@Serializable data class ContextReceipt(
    val receiptId: String,
    val contextDigest: String,
    val contextItemId: String,
    val insertedAt: String,
)
/** Trusted adapter attests inclusion in the specified actual model request. */
@Serializable data class ObservationReceipt(
    val observationId: String,
    val modelRequestId: String,
    val contextDigests: List<String>,
)
@Serializable data class ReceiptResult(
    val clientId: String,
    val eventIds: List<String>,
    val state: String,
    val contractVersion: String = PREVIEW_CONTRACT,
)

@Serializable data class GateCut(
    val cutId: String,
    val clientId: String,
    val lineageId: String,
    val generation: Long,
    val lineageEpoch: Long,
    val sourceSnapshotId: String?,
    val eventFrontier: Long,
    val requiredEventIds: List<String>,
    val ready: Boolean,
    val reasons: List<String>,
    val sourceEpoch: Long? = null,
    val analysisEpoch: String? = null,
    val contractVersion: String = PREVIEW_CONTRACT,
)
@Serializable data class GateResult(
    val allowed: Boolean,
    val clientId: String,
    val lineageId: String,
    val generation: Long,
    val cutId: String,
    val reasons: List<String>,
    /** True only if the trusted synchronous guarded action ran while all fences were held. */
    val actionPerformed: Boolean = false,
    val contractVersion: String = PREVIEW_CONTRACT,
)

/**
 * A trusted composite config -> source fence. Implementations MUST hold both authorities through
 * action, include model/dependency/toolchain/config ABA identity in analysisEpoch, and reject active
 * edits or uncertified live state. Immutable overlay identity alone is not current-live authority.
 * No provider analysis runs inside this callback. An unavailable authority must call action with
 * current=false/authoritative=false, rather than silently using a before/after filesystem scan.
 */
interface SnapshotGate {
    fun <T> withCurrentSnapshot(input: AnalysisInput, action: (SnapshotCheck) -> T): T

    /**
     * One composite config -> source fence over the COMPLETE context vector. Implementations must
     * not loop independent scalar fences: none would span the eventual guarded action together.
     * Scalar-only adapters cannot authorize a multi-context plan by accident.
     * The first supplied input is the latest child/scalar representative; independently verify it
     * and supply its scalar SnapshotCheck fields alongside the complete context-indexed vector.
     */
    fun <T> withCurrentSnapshots(inputs: List<AnalysisInput>, action: (SnapshotCheck) -> T): T =
        if (inputs.size == 1) withCurrentSnapshot(inputs.single(), action)
        else action(SnapshotCheck(false, reasons = listOf("snapshot_vector_authority_unavailable")))

    companion object {
        val UNAVAILABLE: SnapshotGate = object : SnapshotGate {
            override fun <T> withCurrentSnapshot(input: AnalysisInput, action: (SnapshotCheck) -> T): T =
                action(SnapshotCheck(false, reasons = listOf("source_and_configuration_authority_unavailable")))
        }
    }
}

@Serializable data class SnapshotCheck(
    val current: Boolean,
    val snapshotId: String? = null,
    val sourceEpoch: Long? = null,
    val configurationDigest: String? = null,
    val modelDigest: String? = null,
    val dependencyDigest: String? = null,
    val toolchainDigest: String? = null,
    val analysisEpoch: String? = null,
    val authoritative: Boolean = false,
    val reasons: List<String> = emptyList(),
    val validUntilEpochMillis: Long? = null,
    /** Trusted vector attestation, not identities copied from unchecked request inputs. */
    val contextIdentities: List<PlanContextIdentity> = emptyList(),
    val sourceBindingDigest: String? = null,
    val generatedInputsDigest: String? = null,
)

@Serializable data class KernelConfig(
    val workerCount: Int = 2,
    val globalParallelism: Int = 2,
    val perWorktreeParallelism: Int = 1,
    val maxQueuedWork: Int = 512,
    val maxPendingPerClient: Int = 256,
    val maxRetainedReviews: Long = 100_000,
    val maxArchiveBytes: Long = 1024L * 1024 * 1024,
    val maxSnapshotBytes: Long = 64L * 1024 * 1024,
    val maxSnapshotFiles: Int = 50_000,
    val maxInputBytes: Int = 4 * 1024 * 1024,
    val maxResultBytes: Int = 4 * 1024 * 1024,
    val maxEventPageSize: Int = 500,
    val maxEventPageBytes: Int = 8 * 1024 * 1024,
    val maxProgressEventsPerWork: Int = 128,
    val maxProgressBytesPerWork: Long = 8L * 1024 * 1024,
    val analysisTimeoutMillis: Long = 120_000,
    val heartbeatMillis: Long = 500,
    val leaseMillis: Long = 10_000,
    val pollMillis: Long = 50,
    val blockingSeverities: Set<String> = setOf("error", "warning"),
) {
    init {
        require(workerCount in 0..64)
        require(globalParallelism in 1..64 && perWorktreeParallelism in 1..globalParallelism)
        require(maxQueuedWork > 0 && maxPendingPerClient > 0 && maxRetainedReviews > 0 && maxArchiveBytes > 0)
        require(maxSnapshotBytes > 0 && maxSnapshotFiles > 0 && maxInputBytes > 0 && maxResultBytes > 0)
        require(maxEventPageSize in 1..10_000 && analysisTimeoutMillis in 1..3_600_000 && pollMillis > 0)
        require(maxProgressEventsPerWork in 1..10_000 && maxProgressBytesPerWork > 0 && maxEventPageBytes > 0)
        require(heartbeatMillis > 0 && leaseMillis >= heartbeatMillis * 3)
        require(blockingSeverities.all { it in setOf("error", "warning", "info", "hint") })
    }
}

/** Deliberately carries no token, SQL text, source contents or terminal diagnostics. */
class KernelException(val code: String, message: String) : RuntimeException(message)

/**
 * Trusted in-process integration only. Used to bind a provider's nonserializable execution authority
 * to the kernel's durable private archive, without treating request JSON as permission. Invoked
 * outside project/config/database locks, on every attempt (including recovered work).
 */
fun interface PreparedInputObserver {
    fun prepared(original: AnalysisInput, effective: AnalysisInput, verifyFrozen: () -> Unit)

    /**
     * Outside all kernel/config/source locks, once for each attempt whose prepared callback began,
     * including partial preparation failure or cancellation before provider entry. Release ONLY
     * this exact effective attempt's permits; implementations must be idempotent and must not
     * revoke a different task's equal-input or later-attempt authority. No-op retains scalar/SAM
     * integrations. Failure preserves the raw terminal result but makes the review incomplete.
     */
    fun finished(effective: AnalysisInput) { }

    companion object { val NONE = PreparedInputObserver { _, _, _ -> } }
}

/**
 * Trusted, pure per-consumer policy over already captured immutable configuration. Called during a
 * short publication transaction: MUST NOT acquire config/source locks, do I/O, or reenter the kernel.
 * Only finding disposition may change. Throwing leaves that consumer's obligation incomplete.
 * Raw execution evidence, coverage failures and other clients' permissions are never rewritten.
 */
fun interface ResultPolicy {
    fun apply(binding: ClientBinding, input: AnalysisInput, raw: ProviderResult): ProviderResult
    companion object { val NONE = ResultPolicy { _, _, raw -> raw } }
}

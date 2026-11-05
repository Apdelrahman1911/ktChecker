package dev.ktchecker.kernel

import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.PREVIEW_CONTRACT
import dev.ktchecker.contracts.RuleContext
import java.util.Collections
import kotlinx.serialization.Serializable

/** The result is limited to this exact declared vector, never an unqualified whole-project claim. */
@Serializable data class IntegratedCoverageRequirement(val context: RuleContext, val ruleId: String)

@Serializable data class IntegratedClientScope(
    val binding: ClientBinding,
    val planId: String,
    val planDigest: String,
    val sourceSnapshotId: String,
    val sourceBindingDigest: String,
    val generatedInputsDigest: String,
)

@Serializable data class IntegratedCompletionScope(
    val origin: ClientBinding,
    val integrationClientId: String,
    val integrationPlanId: String,
    val integrationSnapshotId: String,
    val finalRoutingId: String,
    val finalRoutingDigest: String,
    val catalogDigest: String,
    val members: List<IntegratedClientScope>,
    val requiredCoverage: List<IntegratedCoverageRequirement>,
    val scopeKind: String = "declared_routed_lineages_and_catalog",
)

/** No peer source, diagnostics, patch contents, receipts or private adapter capabilities. */
@Serializable data class IntegratedCompletionCut(
    val cutId: String,
    val completionRef: String,
    val scope: IntegratedCompletionScope,
    val scopeDigest: String,
    val ready: Boolean,
    val reasons: List<String>,
    val contractVersion: String = PREVIEW_CONTRACT,
)

/** allowed is true ONLY for the call that performed and durably recorded the guarded effect. */
@Serializable data class IntegratedCompletionResult(
    val cutId: String,
    val scope: IntegratedCompletionScope,
    val scopeDigest: String,
    val allowed: Boolean,
    val state: String,
    val reasons: List<String>,
    val actionPerformed: Boolean = false,
    val contractVersion: String = PREVIEW_CONTRACT,
)

/** Exact finite audit frontier. ACK/observation control traffic does not extend this frontier. */
@Serializable data class IntegratedClientFrontier(
    val clientId: String,
    val lineageEpoch: Long,
    val reviewFrontier: Long,
    val eventFrontier: Long,
    val requiredEventIds: List<String>,
    val historyDigest: String,
)

/** Includes every retained scalar/child review AND every parent patch, not just current children. */
@Serializable data class IntegratedPatchObligation(
    val clientId: String,
    val kind: String,
    val obligationId: String,
    val patchId: String,
    val requestDigest: String,
    val inputOrPlanDigest: String,
    val sourceSnapshotId: String,
    val sequence: Long,
)

@Serializable data class IntegratedRouteIdentity(val routingId: String, val routingDigest: String)

/** Trusted callback input, not a wire request or a way to acquire any owner capability. */
@Serializable data class IntegratedFenceRequest(
    val scope: IntegratedCompletionScope,
    val scopeDigest: String,
    val frontiers: List<IntegratedClientFrontier>,
    val historicalObligations: List<IntegratedPatchObligation>,
    val routes: List<IntegratedRouteIdentity>,
)

@Serializable data class IntegratedMemberSnapshotCheck(
    val binding: ClientBinding,
    val planDigest: String,
    val check: SnapshotCheck,
)

/** Evidence from actual retained merge/patch provenance, not a caller-selected waiver. */
@Serializable data class IntegratedPatchDisposition(
    val obligationDigest: String,
    val disposition: String,
    val evidenceDigest: String,
)

@Serializable data class IntegratedSnapshotCheck(
    val current: Boolean = false,
    val authoritative: Boolean = false,
    val integrationSnapshotId: String? = null,
    val integrationEpoch: String? = null,
    val compositionProofDigest: String? = null,
    val members: List<IntegratedMemberSnapshotCheck> = emptyList(),
    val patchDispositions: List<IntegratedPatchDisposition> = emptyList(),
    val reasons: List<String> = emptyList(),
    val validUntilEpochMillis: Long? = null,
)

/**
 * ONE composite owner configuration -> source -> integration fence, spanning EVERY member's
 * real current worktree/configuration and the actual latest combined snapshot through action.
 * It must attest the exact per-client projections and disposition of every retained patch from
 * live integration provenance. Equal bytes/digest echoes/immutable captures are not that proof.
 * Independent scalar fences or a before/after scan cannot implement this contract.
 *
 * Called outside database locks. Invoke action synchronously exactly once while all authorities
 * remain held. Missing authority must supply a noncurrent, nonauthoritative check. Never perform
 * provider work, interrupt another task, revoke shared permits or cancel peers to meet a timeout.
 */
interface IntegratedSnapshotFence {
    fun <T> withCurrentIntegratedSnapshot(request: IntegratedFenceRequest, action: (IntegratedSnapshotCheck) -> T): T

    companion object {
        val UNAVAILABLE: IntegratedSnapshotFence = object : IntegratedSnapshotFence {
            override fun <T> withCurrentIntegratedSnapshot(request: IntegratedFenceRequest, action: (IntegratedSnapshotCheck) -> T): T =
                action(IntegratedSnapshotCheck(reasons = listOf("joint_source_configuration_integration_authority_unavailable")))
        }
    }
}

/** An ephemeral owner-only callback argument. A cut/scope DTO is not a transferable send permit. */
class IntegratedCompletionPermit internal constructor(
    val cutId: String,
    val scope: IntegratedCompletionScope,
    val scopeDigest: String,
)

/** The installed host must return evidence of the actual guarded effect, never a queued intent. */
@Serializable data class IntegratedActionReceipt(val cutId: String, val scopeDigest: String, val receiptDigest: String)

/**
 * Owner-installed nonserialized final-effect authority. perform runs OUTSIDE the DB transaction
 * and INSIDE the joint source/config/integration fence. It must be short, synchronous and return
 * only after its exact scoped final effect is verified. A null/late/throwing outcome stays unknown.
 * This SPI does not implement real host delivery; synthetic kernel fixtures are not host evidence.
 */
interface IntegratedCompletionAction {
    val identity: String
    fun perform(permit: IntegratedCompletionPermit): IntegratedActionReceipt?
}

/** Real independent adapter authentication is still performed by the kernel, never by client ID. */
class AuthorizedIntegratedMember(
    val binding: ClientBinding,
    val request: SubmitPlanRequest,
    val planDigest: String,
    adapter: AdapterCredential,
    val readinessIdentity: String,
    val verifyForCompletion: () -> String?,
) {
    val adapter: AdapterCredential = adapter.copy()
}

/**
 * Trusted installation only. No DTO/transport/model method accepts this object. The complete
 * catalog is owner-installed, NOT provider.supportedRuleIds. The actual retained live assemblies,
 * credentials, composite fence and final action must be supplied by the installed owner registry.
 */
class AuthorizedIntegratedCompletion(
    val completionRef: String,
    val origin: ClientBinding,
    val integrationClientId: String,
    val registrationDigest: String,
    val catalogDigest: String,
    requiredRuleIds: Collection<String>,
    members: Collection<AuthorizedIntegratedMember>,
    val fence: IntegratedSnapshotFence,
    val action: IntegratedCompletionAction,
) {
    val members: List<AuthorizedIntegratedMember> = boundedIntegratedCopy(members, 2, 64)
    val requiredRuleIds: List<String> = boundedIntegratedCopy(requiredRuleIds, 1, 50_000)
}

/** Authenticated initiating client + opaque owner reference ONLY. DENY is the default. */
fun interface IntegratedCompletionAuthority {
    fun authorize(binding: ClientBinding, completionRef: String): AuthorizedIntegratedCompletion?

    companion object { val DENY = IntegratedCompletionAuthority { _, _ -> null } }
}

/** Pure identity recipes; calculating/decoding these is never permission to finalize. */
fun integratedObligationDigest(value: IntegratedPatchObligation): String =
    Digests.sha256(canonical(listOf("ktchecker/integrated-patch-obligation/1", canonical(value))))

fun integratedScopeDigest(value: IntegratedCompletionScope): String =
    Digests.sha256(canonical(listOf("ktchecker/integrated-completion-scope/1", canonical(value))))

/** Check size BEFORE iteration/copy; reject an inconsistent collection, never accept a prefix. */
internal fun <T> boundedIntegratedCopy(values: Collection<T>, minimum: Int, maximum: Int): List<T> {
    val count = values.size
    if (count !in minimum..maximum) fail("joint_collection_limit", "The complete joint collection exceeds its explicit bound")
    val result = ArrayList<T>(count)
    val iterator = values.iterator()
    while (iterator.hasNext()) {
        if (result.size == count) fail("joint_collection_changed", "The closed joint collection changed size")
        result.add(iterator.next())
    }
    if (result.size != count) fail("joint_collection_changed", "The closed joint collection changed size")
    return Collections.unmodifiableList(result)
}

/** Hard ceilings protect lock duration and memory. Smaller owner limits only deny more scope. */
data class IntegratedCompletionLimits(
    val maxMembers: Int = 64,
    val maxRoutes: Int = 128,
    val maxReviews: Int = 1024,
    val maxParents: Int = 512,
    val maxEvents: Int = 8192,
    val maxHistoryBytes: Long = 32L * 1024 * 1024,
    val maxCoveragePairs: Int = 65_536,
    val maxAuditBytes: Int = 4 * 1024 * 1024,
    val maxCuts: Int = 1024,
    val maxActionMillis: Long = 5_000,
) {
    init {
        require(maxMembers in 2..64 && maxRoutes in 1..128 && maxReviews in 1..1024 && maxParents in 1..512)
        require(maxEvents in 1..8192 && maxHistoryBytes in 1..32L * 1024 * 1024)
        require(maxCoveragePairs in 1..65_536 && maxAuditBytes in 1..4 * 1024 * 1024 && maxCuts in 1..1024)
        require(maxActionMillis in 1..5_000)
    }
}

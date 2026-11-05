package dev.ktchecker.reviewcontroller

import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.ClientBinding
import dev.ktchecker.kernel.SubmitPlanRequest
import dev.ktchecker.projectmodel.VerifiedProjectModel
import dev.ktchecker.projectmodel.ObservedProjectModel
import java.nio.file.Path
import kotlinx.serialization.Serializable

const val PLAN_ACTIVATION_POLICY_KEY = "ktchecker.activation-plan"
const val PLAN_EXECUTION_POLICY_KEY = "ktchecker.execution-plan"
internal const val CATALOG_POLICY_KEY = "ktchecker.catalog-inventory"

/**
 * Owner-installed COMPLETE catalog inventory, not provider.supportedRuleIds or a request subset.
 * Every row remains required in every assembled context, including disabled/unavailable rows.
 * The owner is responsible for catalog completeness; this class verifies bounded exact identity,
 * not the semantics of an arbitrary digest. It is deliberately not a serializable capability.
 */
class OwnerCatalogInventory(val catalogDigest: String, ruleIds: Collection<String>) {
    val ruleIds: List<String> = immutable(ruleIds.sorted())
    init {
        requireDigest(catalogDigest, "catalog identity")
        if (this.ruleIds.size !in 1..50_000 || this.ruleIds.distinct().size != this.ruleIds.size) {
            problem("catalog_inventory_invalid", "A distinct bounded nonempty complete owner inventory is required.")
        }
        this.ruleIds.forEach { requireIdentifier(it, "catalog rule") }
    }
}

/** Returned only by the owner's registered immutable-capture verifier. NOT live-worktree authority. */
class MaterializationVerification(snapshot: SnapshotManifest, val materializedRoot: String, val authorityDigest: String) {
    val snapshot: SnapshotManifest = freezeSnapshot(snapshot)
}

/**
 * Nonserialized owner capability bound to the actual importer-created model object. The callback
 * must verify this exact registered coherent capture/root and return its immutable registry/ABA
 * identity, or null. Echoing request values or scanning a mutable tree does not establish it.
 */
class OwnerPlanMaterialization internal constructor(
    internal val origin: OwnerPlanModelOrigin,
    val materializedRoot: Path,
    private val verifier: () -> MaterializationVerification?,
) {
    constructor(model: VerifiedProjectModel, materializedRoot: Path, verifier: () -> MaterializationVerification?) :
        this(OwnerPlanModelOrigin.Verified(model), materializedRoot, verifier)
    val model: VerifiedProjectModel get() = origin.verified
    val observedModel: ObservedProjectModel? get() = origin.observed
    init {
        if (!materializedRoot.isAbsolute || materializedRoot.normalize() != materializedRoot) {
            problem("materialization_path_invalid", "Use an exact normalized absolute registered materialization.")
        }
    }

    internal fun verify(expectedModel: OwnerPlanModelOrigin): MaterializationVerification {
        if (!origin.sameModel(expectedModel)) problem("materialization_model_mismatch", "Materialization belongs to another live model capability.")
        origin.observed?.invocation?.verifyLive()
        val checked = verifier() ?: problem("materialization_unavailable", "Registered immutable source authority is unavailable.")
        requireDigest(checked.authorityDigest, "materialization authority")
        if (checked.snapshot != origin.snapshot || checked.materializedRoot != materializedRoot.toString() ||
            digest(checked.snapshot) != origin.identity.sourceDigest) {
            problem("materialization_binding_mismatch", "Materialization verification differs from the exact whole verified model snapshot/root.")
        }
        origin.observed?.invocation?.verifyLive()
        return checked
    }
}

/** Nominal observed materialization entry; never accepted by ordinary assemble(). */
class OwnerObservedPlanMaterialization(model: ObservedProjectModel, materializedRoot: Path,
    verifier: () -> MaterializationVerification?) {
    internal val retained = OwnerPlanMaterialization(OwnerPlanModelOrigin.Observed(model), materializedRoot, verifier)
    val model: ObservedProjectModel = model
    val materializedRoot: Path = materializedRoot
}

/** Wire-compatible activation evidence, not permission to execute or a qualification certificate. */
@Serializable data class PlanRuleActivation(val ruleId: String, val active: Boolean, val origin: String, val reason: String)
@Serializable data class PlanActivation(val policyDigest: String, val decisions: List<PlanRuleActivation>, val schemaVersion: Int = 1)

/** Issued for this callback invocation; returning another request's preparation is rejected. */
class PolicyPreparationRequest internal constructor(
    val binding: ClientBinding,
    val parentRequest: SubmitPlanRequest,
    val compilationId: String?,
    internal val origin: OwnerPlanModelOrigin,
    val inventory: OwnerCatalogInventory,
    val input: AnalysisInput,
) {
    val model: VerifiedProjectModel get() = origin.verified
    val observedModel: ObservedProjectModel? get() = origin.observed
    fun withActivation(activation: PlanActivation): AnalysisInput = freezeInput(input.copy(context = input.context.copy(
        policies = input.context.policies + (PLAN_ACTIVATION_POLICY_KEY to canonical(activation)),
    )))
}

/** The exact client's captured configuration, including a revision/ABA identity beyond its digest. */
data class CapturedPolicyIdentity(val binding: ClientBinding, val configurationDigest: String, val revisionDigest: String)

/**
 * Owner callback result. Only the activation-policy addition is sanctioned; all source, rules,
 * scope, model, config, libraries, ordered arguments and classpath fields are checked unchanged.
 * retainForAdmission must durably retain/restore the captured policy and populate that client's
 * final-input-key cache BEFORE returning its verified identity. No I/O occurs under kernel locks.
 */
class CapturedPlanPolicy(
    val request: PolicyPreparationRequest,
    val identity: CapturedPolicyIdentity,
    input: AnalysisInput,
    internal val retainForAdmission: (AnalysisInput) -> CapturedPolicyIdentity?,
) {
    val input: AnalysisInput = freezeInput(input)
}

fun interface OwnerPolicyPreparer {
    fun prepare(request: PolicyPreparationRequest): CapturedPlanPolicy?
    companion object { val DENY = OwnerPolicyPreparer { null } }
}

class ExecutionPreparationRequest internal constructor(
    val policyRequest: PolicyPreparationRequest,
    val input: AnalysisInput,
) {
    val binding: ClientBinding get() = policyRequest.binding
    val parentRequest: SubmitPlanRequest get() = policyRequest.parentRequest
    val compilationId: String? get() = policyRequest.compilationId
    val model: VerifiedProjectModel get() = policyRequest.model
    val observedModel: ObservedProjectModel? get() = policyRequest.observedModel

    fun withProfiles(profiles: Map<String, TrustedExecution>): AnalysisInput = freezeInput(input.copy(context = input.context.copy(
        policies = input.context.policies + (PLAN_EXECUTION_POLICY_KEY to executionProfilesDigest(profiles)),
    )))
}

/** Bound to the policy-enriched input BEFORE the execution-plan digest addition (no circular key). */
data class CapturedExecutionIdentity(
    val binding: ClientBinding,
    val inputKey: String,
    val toolchainDigest: String,
    val approvalDigest: String,
)

/**
 * Nonserialized owner approval/preparation callback. Profiles/approved booleans are evidence only;
 * the host must still issue provider execution capabilities through PreparedInputObserver on
 * every attempt. retainForAdmission verifies installed/profile/toolchain and captured approval,
 * retains all final-input records, and returns the SAME verified approval identity or null.
 */
class CapturedPlanExecution(
    val request: ExecutionPreparationRequest,
    val identity: CapturedExecutionIdentity,
    profiles: Map<String, TrustedExecution>,
    input: AnalysisInput,
    internal val retainForAdmission: (AnalysisInput) -> CapturedExecutionIdentity?,
) {
    val profiles: Map<String, TrustedExecution> = freezeProfiles(profiles)
    val input: AnalysisInput = freezeInput(input)
}

fun interface OwnerExecutionPreparer {
    fun prepare(request: ExecutionPreparationRequest): CapturedPlanExecution?
    companion object { val DENY = OwnerExecutionPreparer { null } }
}

/** Pure identity calculation, never provider or model authority. Map order is not argument order. */
fun executionProfilesDigest(profiles: Map<String, TrustedExecution>): String = digest(freezeProfiles(profiles))

class ReviewControllerException(val code: String, message: String) : IllegalStateException(message)

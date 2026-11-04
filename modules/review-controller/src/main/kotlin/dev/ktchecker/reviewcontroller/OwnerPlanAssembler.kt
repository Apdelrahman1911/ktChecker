package dev.ktchecker.reviewcontroller

import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.*
import dev.ktchecker.projectmodel.*

/** Nonserialized live provenance; impact widening is evidence, not an executable-slot failure. */
class SlotAssemblyEvidence internal constructor(
    val slotId: String,
    val compilationId: String?,
    val sourceModel: VerifiedProjectModel,
    val verifiedContext: VerifiedCompilationContext?,
    val impactObligation: CompilationObligation?,
    explanations: Collection<String>,
    val policyIdentity: CapturedPolicyIdentity?,
    val executionIdentity: CapturedExecutionIdentity?,
) {
    val explanations: List<String> = immutable(explanations)
}

class PlanAssemblyProvenance internal constructor(
    val before: VerifiedProjectModel?,
    val after: VerifiedProjectModel,
    val impact: ProjectImpactPlan,
    val inventory: OwnerCatalogInventory,
    val materializationAuthorityDigest: String,
    slots: Collection<SlotAssemblyEvidence>,
) {
    val slots: List<SlotAssemblyEvidence> = immutable(slots)
}

internal data class PreparedSlot(val input: AnalysisInput, val policy: CapturedPlanPolicy?, val execution: CapturedPlanExecution?)
private data class ContextSelection(val compilationId: String?, val previousScope: RuleContext? = null)

private fun exportedScope(model: VerifiedProjectModel, compilation: ModelCompilation): RuleContext = RuleContext(
    compilation.target, model.sourceSet(compilation.defaultSourceSetId)?.name ?: "unavailable", compilation.moduleId, compilation.name,
)

/**
 * Only OwnerPlanAssembler constructs this nonserialized capability. The immutable plan DTO is
 * evidence, not a way to reconstruct this object, its live models, or its readiness callbacks.
 */
class AssembledReviewPlan internal constructor(
    val plan: RequiredReviewPlan,
    val provenance: PlanAssemblyProvenance,
    private val materialization: OwnerPlanMaterialization,
    private val verifiedMaterialization: MaterializationVerification,
    private val preparations: List<PreparedSlot>,
) {
    val planDigest: String = digest(plan)
    internal val registrationDigest: String = digest(listOf(planDigest, provenance.before?.ingressCertificate.orEmpty(),
        provenance.after.ingressCertificate, provenance.materializationAuthorityDigest,
        canonical(preparations.map { prepared -> listOf(
            prepared.policy?.identity?.let { canonical(listOf(canonical(it.binding), it.configurationDigest, it.revisionDigest)) }.orEmpty(),
            prepared.execution?.identity?.let { canonical(listOf(canonical(it.binding), it.inputKey, it.toolchainDigest, it.approvalDigest)) }.orEmpty(),
        ) })))

    /** Called only outside registry/kernel locks, before registration and on every resolution. */
    internal fun verifyForAdmission() {
        verifySource()
        preparations.forEach { prepared ->
            prepared.policy?.let { captured ->
                if (captured.retainForAdmission(prepared.input) != captured.identity) {
                    problem("policy_authority_changed", "Captured per-client policy is unavailable or changed while preparing admission.")
                }
            }
            prepared.execution?.let { captured ->
                if (captured.retainForAdmission(prepared.input) != captured.identity) {
                    problem("execution_authority_changed", "Captured execution approval/toolchain is unavailable or changed while preparing admission.")
                }
            }
        }
        verifySource()
    }

    private fun verifySource() {
        val current = materialization.verify(provenance.after)
        if (current.authorityDigest != verifiedMaterialization.authorityDigest || current.snapshot != verifiedMaterialization.snapshot ||
            current.materializedRoot != verifiedMaterialization.materializedRoot) {
            problem("materialization_authority_changed", "Registered source authority changed during assembly or before resolution.")
        }
    }
}

/**
 * Owner-only composition over live importer capabilities. No model JSON, changed-path list, impact
 * JSON, caller-selected compilation subset, or provider inventory is accepted. This deliberately
 * refreshes ALL before/after contexts; the impact planner is not treated as an unaffected-reuse proof.
 */
class OwnerPlanAssembler(
    private val inventory: OwnerCatalogInventory,
    private val policyPreparer: OwnerPolicyPreparer = OwnerPolicyPreparer.DENY,
    private val executionPreparer: OwnerExecutionPreparer = OwnerExecutionPreparer.DENY,
    private val maxPlanBytes: Int = 4 * 1024 * 1024,
) {
    init { require(maxPlanBytes > 0) }

    fun assemble(
        binding: ClientBinding,
        request: SubmitPlanRequest,
        before: VerifiedProjectModel?,
        after: VerifiedProjectModel,
        materialization: OwnerPlanMaterialization,
        requiredRecipientClientIds: Collection<String>,
    ): AssembledReviewPlan {
        validateBinding(binding, request, before, after)
        val recipients = immutable(requiredRecipientClientIds)
        if (recipients.isEmpty() || recipients.distinct().size != recipients.size) {
            problem("plan_recipients_invalid", "A complete distinct nonempty owner recipient set is required.")
        }
        if (recipients != listOf(binding.clientId)) {
            problem("cross_client_routing_unavailable", "Required additional recipients need RoutingGrant support; no plan was registered.")
        }
        val ids = (before?.compilationIds.orEmpty() + after.compilationIds).distinct().sorted()
        if (ids.size > 512) problem("plan_slot_limit", "The complete context vector exceeds 512 slots; it cannot be narrowed or partially registered.")
        val selections = if (ids.isEmpty()) listOf(ContextSelection(null)) else ids.flatMap { id ->
            val old = before?.compilation(id)
            val current = after.compilation(id)
            val previousScope = if (before != null && old != null) exportedScope(before, old) else null
            // A stable compilation ID must not hide a removed rule context when its actual
            // target/module/default-source-set/name changes. No retirement equivalence exists.
            val retained = if (previousScope != null && current != null && previousScope != exportedScope(after, current)) {
                listOf(ContextSelection(id, previousScope))
            } else emptyList()
            listOf(ContextSelection(id)) + retained
        }
        if (selections.size > 512) problem("plan_slot_limit", "The complete current and unresolved previous context vector exceeds 512 slots.")
        val minimumBytes = (canonical(after.snapshot).toByteArray().size.toLong() + canonical(inventory.ruleIds).toByteArray().size) * selections.size
        if (minimumBytes > maxPlanBytes) problem("plan_metadata_limit", "The whole required source/rule vector exceeds the preparation metadata bound.")
        val source = materialization.verify(after) // Before any preparation callback.
        val impact = ProjectImpactPlanner().impact(before, after)
        val snapshot = freezeSnapshot(after.snapshot)
        val slotEvidence = mutableListOf<SlotAssemblyEvidence>()
        val preparedSlots = mutableListOf<PreparedSlot>()
        val slots = selections.map { selection ->
            val id = selection.compilationId
            val historical = selection.previousScope != null
            val current = if (historical) null else id?.let(after::compilation)
            val old = id?.let { before?.compilation(it) }
            val sourceModel = if (current == null && old != null) before!! else after
            val verified = id?.let { sourceModel.context(it, sourceModel.identity.configurationDigest) }
            val obligation = if (historical) null else impact.obligations.singleOrNull { it.compilationId == id }
            val slotId = when {
                historical -> "retired-context:${digest(listOf(id!!, canonical(selection.previousScope!!)))}"
                id != null -> "compilation:${digest(id)}"
                else -> "project-model-unavailable"
            }
            val reasons = mutableListOf<String>()
            val context = if (current != null && verified?.context != null) verified.context!! else {
                // Administrative placeholder only: do not reuse old source membership/artifact paths
                // against the new whole snapshot, guess a main compilation, or invent a certificate.
                val previous = current ?: old
                val scope = selection.previousScope ?: previous?.let { exportedScope(sourceModel, it) }
                    ?: RuleContext("unavailable", "unavailable", ":unavailable", "unavailable")
                AnalysisContext(scope = scope, modelDigest = after.identity.modelDigest,
                    configurationDigest = after.identity.configurationDigest, fullProject = false, compilationFiles = emptyList())
            }
            when {
                historical -> reasons += listOf("required_context_retargeted_unresolved", "consumer_context_changed_without_retirement_evidence")
                id == null -> reasons += listOf("compilation_model_empty", "required_project_scope_unavailable")
                current == null -> reasons += listOf("required_compilation_removed_unresolved", "consumer_removed_without_retirement_evidence")
                verified?.completeness != ModelCompleteness.COMPLETE || verified?.certificateId == null || !context.fullProject ->
                    reasons += "verified_compilation_context_unavailable"
            }
            if (current != null) reasons += unsupportedExecutionInputs(current)
            reasons += verified?.issues.orEmpty().map { it.kind }
            if (id == null) reasons += after.issues.map { it.kind }
            if (snapshot.scopeExclusions.isNotEmpty() || snapshot.unknownInputs.isNotEmpty()) reasons += "source_scope_incomplete"
            if (CATALOG_POLICY_KEY in context.policies || PLAN_ACTIVATION_POLICY_KEY in context.policies || PLAN_EXECUTION_POLICY_KEY in context.policies) {
                problem("model_policy_collision", "Model context already uses a controller-owned preparation key.")
            }
            var input = freezeInput(AnalysisInput(snapshot, materialization.materializedRoot.toString(),
                context.copy(policies = context.policies + (CATALOG_POLICY_KEY to inventory.catalogDigest)), inventory.ruleIds,
                changedPaths = impact.changedFiles, baselineSnapshotId = before?.snapshot?.snapshotId))
            if (current != null && exactPlanContextIdentity(input) == null) reasons += "exact_context_identity_unavailable"
            val policyRequest = PolicyPreparationRequest(binding, request, id, after, inventory, input)
            val policy = policyPreparer.prepare(policyRequest)
            if (policy == null) reasons += "captured_policy_unavailable" else {
                validatePolicy(policyRequest, policy)
                input = policy.input
            }
            var execution: CapturedPlanExecution? = null
            if (reasons.isEmpty()) {
                val executionRequest = ExecutionPreparationRequest(policyRequest, input)
                execution = executionPreparer.prepare(executionRequest)
                if (execution == null) reasons += "execution_preparation_unavailable" else {
                    validateExecution(executionRequest, execution)
                    input = execution.input
                }
            }
            val unsupported = immutable(reasons.distinct().sorted())
            val identity = if (unsupported.isEmpty()) exactPlanContextIdentity(input)
                ?: problem("exact_context_identity_unavailable", "Prepared executable context lost its exact verified identity.") else null
            preparedSlots += PreparedSlot(input, policy, execution)
            slotEvidence += SlotAssemblyEvidence(slotId, id, sourceModel, verified, obligation,
                listOf("conservative_all_before_after_contexts_required") +
                    (if (historical) listOf("previous_context_retained_without_retirement_evidence")
                    else if (id != null && obligation == null) listOf("unaffected_context_rechecked_without_reuse_proof") else emptyList()) +
                    obligation?.reasons.orEmpty(), policy?.identity, execution?.identity)
            RequiredPlanSlot(slotId, input, identity, if (unsupported.isEmpty()) "pending" else "unsupported", unsupported)
        }.sortedBy { it.slotId }
        val executableContexts = slots.filter { it.state == "pending" }.map { it.identity!!.context }
        if (executableContexts.distinct().size != executableContexts.size) {
            problem("plan_context_duplicate", "Actual executable model contexts are ambiguous; they cannot be merged.")
        }
        val plan = RequiredReviewPlan(binding, request, digest(snapshot), digest(after.export.generatedInputs), after.ingressCertificate,
            impact.certificateId, immutable(slots), recipients)
        if (canonical(plan).toByteArray().size > maxPlanBytes) problem("plan_metadata_limit", "The complete prepared vector exceeds its metadata bound; no prefix was registered.")
        return AssembledReviewPlan(plan, PlanAssemblyProvenance(before, after, impact, inventory, source.authorityDigest,
            slotEvidence.sortedBy { it.slotId }), materialization, source, immutable(preparedSlots)).also { it.verifyForAdmission() }
    }

    private fun validateBinding(binding: ClientBinding, request: SubmitPlanRequest, before: VerifiedProjectModel?, after: VerifiedProjectModel) {
        listOf(binding.ownerId, binding.workspaceId, binding.worktreeId, binding.clientId, binding.lineageId, binding.sessionId,
            request.patchId, request.idempotencyKey, request.planRef).forEach { requireIdentifier(it, "owner plan binding") }
        if (binding.generation !in 1..9_007_199_254_740_991L) problem("plan_binding_invalid", "Client generation is invalid.")
        val namespace = ModelNamespace(binding.ownerId, binding.workspaceId, binding.worktreeId, binding.lineageId)
        if (after.namespace != namespace || before != null && before.namespace != namespace) {
            problem("model_namespace_mismatch", "Live model capabilities must belong to this exact owner/workspace/worktree/lineage.")
        }
        if (before != null && before.snapshot.snapshotId != after.snapshot.snapshotId && before.snapshot.snapshotId != after.snapshot.parentSnapshotId) {
            problem("model_ancestry_unavailable", "Only identical-snapshot model changes or a verified direct snapshot successor are supported.")
        }
    }

    private fun validatePolicy(request: PolicyPreparationRequest, captured: CapturedPlanPolicy) {
        if (captured.request !== request || captured.identity.binding != request.binding ||
            captured.identity.configurationDigest != request.input.context.configurationDigest) {
            problem("policy_preparation_binding_mismatch", "Policy preparation belongs to another client, callback request or captured configuration.")
        }
        requireDigest(captured.identity.revisionDigest, "captured policy revision")
        val activationJson = captured.input.context.policies[PLAN_ACTIVATION_POLICY_KEY]
            ?: problem("policy_activation_missing", "Captured policy must explicitly account for every required catalog row.")
        val expected = request.input.copy(context = request.input.context.copy(policies = request.input.context.policies + (PLAN_ACTIVATION_POLICY_KEY to activationJson)))
        if (captured.input != expected) problem("policy_input_mutation", "Policy preparation may add activation evidence only, not retarget or narrow the input.")
        val activation = try { ContractJson.decodeFromString<PlanActivation>(activationJson) }
            catch (_: Exception) { problem("policy_activation_invalid", "Captured activation evidence is malformed.") }
        val ids = activation.decisions.map { it.ruleId }
        if (activation.schemaVersion != 1 || activation.policyDigest != request.input.context.configurationDigest ||
            ids.size != ids.distinct().size || ids.toSet() != request.input.requiredRuleIds.toSet()) {
            problem("activation_inventory_mismatch", "Activation must retain every exact required rule and captured configuration, without duplicates.")
        }
        activation.decisions.forEach { requireIdentifier(it.origin, "activation origin"); requireIdentifier(it.reason, "activation reason") }
    }

    private fun validateExecution(request: ExecutionPreparationRequest, captured: CapturedPlanExecution) {
        if (captured.request !== request || captured.identity.binding != request.binding || captured.identity.inputKey != request.input.analysisKey() ||
            captured.identity.toolchainDigest != request.input.context.toolchainDigest) {
            problem("execution_preparation_binding_mismatch", "Execution preparation belongs to another client, callback request, input or verified toolchain.")
        }
        requireDigest(captured.identity.approvalDigest, "execution approval")
        captured.profiles.keys.forEach { requireIdentifier(it, "execution profile provider") }
        if (captured.input != request.withProfiles(captured.profiles)) {
            problem("execution_input_mutation", "Execution preparation may add its profile digest only; model/compiler/source/rule identity is immutable.")
        }
    }
}

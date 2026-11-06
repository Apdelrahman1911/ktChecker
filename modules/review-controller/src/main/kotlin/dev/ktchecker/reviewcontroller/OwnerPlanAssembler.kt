package dev.ktchecker.reviewcontroller

import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.*
import dev.ktchecker.projectmodel.*

/** Nonserialized live provenance; impact widening is evidence, not an executable-slot failure. */
class SlotAssemblyEvidence internal constructor(
    val slotId: String,
    val compilationId: String?,
    internal val sourceOrigin: OwnerPlanModelOrigin,
    internal val contextOrigin: OwnerPlanContextOrigin?,
    val impactObligation: CompilationObligation?,
    explanations: Collection<String>,
    val policyIdentity: CapturedPolicyIdentity?,
    val executionIdentity: CapturedExecutionIdentity?,
) {
    val sourceModel: VerifiedProjectModel get() = sourceOrigin.verified
    val observedSourceModel: ObservedProjectModel? get() = sourceOrigin.observed
    val verifiedContext: VerifiedCompilationContext? get() = when (val context = contextOrigin) {
        is OwnerPlanContextOrigin.Verified -> context.value
        is OwnerPlanContextOrigin.Observed, null -> null
    }
    val observedContext: ObservedCompilationContext? get() = when (val context = contextOrigin) {
        is OwnerPlanContextOrigin.Observed -> context.value
        is OwnerPlanContextOrigin.Verified, null -> null
    }
    val explanations: List<String> = immutable(explanations)
}

class PlanAssemblyProvenance internal constructor(
    internal val beforeOrigin: OwnerPlanModelOrigin?,
    internal val afterOrigin: OwnerPlanModelOrigin,
    internal val impactOrigin: OwnerPlanImpactOrigin,
    val inventory: OwnerCatalogInventory,
    val materializationAuthorityDigest: String,
    slots: Collection<SlotAssemblyEvidence>,
) {
    val before: VerifiedProjectModel? get() = beforeOrigin?.verified
    val after: VerifiedProjectModel get() = afterOrigin.verified
    val observedBefore: ObservedProjectModel? get() = beforeOrigin?.observed
    val observedAfter: ObservedProjectModel? get() = afterOrigin.observed
    val impact: ProjectImpactPlan get() = when (val value = impactOrigin) {
        is OwnerPlanImpactOrigin.Verified -> value.value
        is OwnerPlanImpactOrigin.Observed -> problem("observed_impact_not_verified", "Observed full obligations are not a verified impact certificate.")
    }
    val observedImpact: ObservedProjectImpactPlan? get() = when (val value = impactOrigin) {
        is OwnerPlanImpactOrigin.Verified -> null
        is OwnerPlanImpactOrigin.Observed -> value.value
    }
    val slots: List<SlotAssemblyEvidence> = immutable(slots)
}

internal data class PreparedSlot(val input: AnalysisInput, val policy: CapturedPlanPolicy?, val execution: CapturedPlanExecution?)
private data class ContextSelection(val compilationId: String?, val previousScope: RuleContext? = null)

private fun exportedScope(model: OwnerPlanModelOrigin, compilation: ModelCompilation): RuleContext = RuleContext(
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
    private val verifyProducedOwner: (() -> Unit)? = null,
) {
    val planDigest: String = digest(plan)
    internal val registrationDigest: String = digest(listOf(planDigest, provenance.beforeOrigin?.authorityEvidenceDigest.orEmpty(),
        provenance.afterOrigin.authorityEvidenceDigest, provenance.materializationAuthorityDigest,
        canonical(preparations.map { prepared -> listOf(
            prepared.policy?.identity?.let { canonical(listOf(canonical(it.binding), it.configurationDigest, it.revisionDigest)) }.orEmpty(),
            prepared.execution?.identity?.let { canonical(listOf(canonical(it.binding), it.inputKey, it.toolchainDigest, it.approvalDigest)) }.orEmpty(),
        ) })))

    /** Called only outside registry/kernel locks, before registration and on every resolution. */
    internal fun verifyForAdmission() {
        verifyProducedOwner?.invoke()
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
        verifyProducedOwner?.invoke()
    }

    /**
     * Additive declaration selection over the ENTIRE assembler-created vector. A caller cannot
     * supply a graph, contexts, a subset, replacement policy or executable P. Every D and captured
     * policy remains identical; ordinary pending/fullProject admission is deliberately unchanged.
     * Returning null leaves every unsupported/retired/extra obligation in the original plan.
     */
    fun declareJvmProduced(intentDigest: String, originalDeadlineIdentity: String,
        originalDeadlineMillis: Long, verifyOwner: () -> Unit): AssembledReviewPlan? {
        if (provenance.afterOrigin !is OwnerPlanModelOrigin.Verified) return null
        return declareJvmProducedChecked(intentDigest, originalDeadlineIdentity, originalDeadlineMillis, verifyOwner, null)
    }

    /** Separate nominal observed selection; the generic verified declaration entry cannot grant it. */
    fun declareObservedJvmProduced(intentDigest: String, originalDeadlineIdentity: String,
        originalDeadlineMillis: Long, verifyOwner: () -> Unit): AssembledReviewPlan? {
        val model = provenance.observedAfter ?: return null
        if (model.invocation.originalDeadlineIdentity != originalDeadlineIdentity ||
            model.invocation.originalDeadlineMillis != originalDeadlineMillis) return null
        model.invocation.verifyLive()
        val evidence = ObservedExportPlanEvidence(plan.binding, model.invocation.identity, model.evidenceIdentity)
        return declareJvmProducedChecked(intentDigest, originalDeadlineIdentity, originalDeadlineMillis, verifyOwner, evidence)
    }

    private fun declareJvmProducedChecked(intentDigest: String, originalDeadlineIdentity: String,
        originalDeadlineMillis: Long, verifyOwner: () -> Unit, observedEvidence: ObservedExportPlanEvidence?): AssembledReviewPlan? {
        requireDigest(intentDigest, "original owner intent")
        requireIdentifier(originalDeadlineIdentity, "original owner deadline")
        if (originalDeadlineMillis !in 1..3_600_000 || plan.jvmProduced != null) return null
        val model = provenance.afterOrigin
        val graph = model.graph() ?: return null
        val allowed = if (observedEvidence == null) setOf("verified_compilation_context_unavailable", "jvm_produced_output_deferred",
            "friend_artifact_rewrite_unavailable", "compiler_arguments_require_verified_rewrite") else
            setOf("observed_compilation_context_unavailable", CONFIGURATION_INPUT_CLOSURE_UNPROVEN, "jvm_produced_output_deferred",
                "friend_artifact_rewrite_unavailable", "compiler_arguments_require_verified_rewrite")
        val additions = setOf(CATALOG_POLICY_KEY, PLAN_ACTIVATION_POLICY_KEY) +
            if (observedEvidence == null) emptySet() else setOf(OBSERVED_PLAN_EVIDENCE_POLICY)
        if ((model is OwnerPlanModelOrigin.Observed) != (observedEvidence != null)) return null
        if (!graph.reference.friend || graph.modelDigest != model.identity.modelDigest ||
            plan.slots.size != 2 || provenance.slots.size != 2 || preparations.size != 2 ||
            !OwnerFullCatalog1665.accepts(provenance.inventory.ruleIds) || model.compilationIds.toSet() != graph.compilationIds.toSet() ||
            provenance.slots.map { it.compilationId }.toSet() != graph.compilationIds.toSet() ||
            provenance.slots.any { !it.sourceOrigin.sameModel(model) || it.policyIdentity == null || it.executionIdentity != null } ||
            preparations.any { it.policy == null || it.execution != null } ||
            plan.requiredRecipientClientIds != listOf(plan.binding.clientId)) return null
        val declared = graph.compilationIds.map { id ->
            val evidence = provenance.slots.single { it.compilationId == id }
            val slot = plan.slots.singleOrNull { it.slotId == evidence.slotId } ?: return null
            val raw = model.context(id).context ?: return null
            val layout = model.jvmArgumentLayout(id) ?: return null
            val expectedName = if (id == graph.producerCompilationId) "main" else "test"
            val prepared = preparations.singleOrNull { it.input == slot.input } ?: return null
            if (evidence.contextOrigin?.context != raw || slot.state != "unsupported" || slot.identity != null ||
                slot.reasons.isEmpty() || slot.reasons.any { it !in allowed } || prepared.policy?.input != slot.input ||
                slot.input.context != raw.copy(policies = slot.input.context.policies) ||
                raw.policies.any { slot.input.context.policies[it.key] != it.value } ||
                slot.input.context.policies.keys - raw.policies.keys != additions ||
                slot.input.context.policies[CATALOG_POLICY_KEY] != provenance.inventory.catalogDigest ||
                slot.input.requiredRuleIds != provenance.inventory.ruleIds || slot.input.snapshot != model.snapshot ||
                slot.input.materializedRoot != materialization.materializedRoot.toString() ||
                slot.input.materializedRoot != layout.originalSourceRoot || slot.input.execution != null || slot.input.outputRoot != null ||
                raw.fullProject || raw.scope.target != "" || raw.scope.compilation != expectedName ||
                raw.modelDigest != model.identity.modelDigest || raw.compilerVersion != "2.4.21") return null
            if (observedEvidence != null && (observedEvidence.policies().any { slot.input.context.policies[it.key] != it.value } ||
                    CONFIGURATION_INPUT_CLOSURE_UNPROVEN !in slot.reasons ||
                    "observed_compilation_context_unavailable" !in slot.reasons || evidence.observedSourceModel !== model.observed)) return null
            ProducedCompilationDeclaration(slot.slotId, id, digest(raw), layout.digest)
        }
        val producer = declared.single { it.compilationId == graph.producerCompilationId }
        val consumer = declared.single { it.compilationId == graph.consumerCompilationId }
        val p = plan.slots.single { it.slotId == producer.slotId }.input
        val c = plan.slots.single { it.slotId == consumer.slotId }.input
        val toolchain = p.context.toolchainDigest ?: return null
        if (p.context.scope.module != c.context.scope.module || toolchain != c.context.toolchainDigest ||
            p.context.configurationDigest != c.context.configurationDigest || p.snapshot != c.snapshot ||
            p.snapshot.scopeExclusions.isNotEmpty() || p.snapshot.unknownInputs.isNotEmpty() ||
            graph.reference.classpathIndex !in 0..c.context.classpath.size) return null
        val declaration = JvmProducedPlanDeclaration(graph.digest, graph.requestDigest, model.identity.modelDigest,
            p.context.scope.module, toolchain, producer, consumer, graph.reference.digest(), graph.reference.classpathIndex,
            graph.reference.friend, provenance.inventory.catalogDigest, digest(provenance.inventory.ruleIds),
            intentDigest, originalDeadlineIdentity, originalDeadlineMillis, observedExport = observedEvidence)
        return AssembledReviewPlan(plan.copy(slots = immutable(plan.slots.map { it.copy(state = "declared") }),
            jvmProduced = declaration), provenance, materialization, verifiedMaterialization, preparations, verifyOwner)
            .also { it.verifyForAdmission() }
    }

    private fun verifySource() {
        val current = materialization.verify(provenance.afterOrigin)
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
    ): AssembledReviewPlan = assembleChecked(binding, request, before?.let { OwnerPlanModelOrigin.Verified(it) },
        OwnerPlanModelOrigin.Verified(after), materialization, requiredRecipientClientIds)

    fun assembleObserved(
        binding: ClientBinding,
        request: SubmitPlanRequest,
        before: ObservedProjectModel?,
        after: ObservedProjectModel,
        materialization: OwnerObservedPlanMaterialization,
        requiredRecipientClientIds: Collection<String>,
    ): AssembledReviewPlan = assembleChecked(binding, request, before?.let { OwnerPlanModelOrigin.Observed(it) },
        OwnerPlanModelOrigin.Observed(after), materialization.retained, requiredRecipientClientIds)

    private fun assembleChecked(binding: ClientBinding, request: SubmitPlanRequest, before: OwnerPlanModelOrigin?,
        after: OwnerPlanModelOrigin, materialization: OwnerPlanMaterialization,
        requiredRecipientClientIds: Collection<String>): AssembledReviewPlan {
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
        val impact = when (after) {
            is OwnerPlanModelOrigin.Verified -> OwnerPlanImpactOrigin.Verified(ProjectImpactPlanner().impact(before?.verified, after.value))
            is OwnerPlanModelOrigin.Observed -> {
                if (before != null && before !is OwnerPlanModelOrigin.Observed) problem("model_origin_mismatch", "Do not mix model assurance origins.")
                OwnerPlanImpactOrigin.Observed(ObservedProjectImpactPlanner().impact(before?.observed, after.value))
            }
        }
        val observedEvidence = after.observed?.let { ObservedExportPlanEvidence(binding, it.invocation.identity, it.evidenceIdentity) }
        val snapshot = freezeSnapshot(after.snapshot)
        val slotEvidence = mutableListOf<SlotAssemblyEvidence>()
        val preparedSlots = mutableListOf<PreparedSlot>()
        val slots = selections.map { selection ->
            val id = selection.compilationId
            val historical = selection.previousScope != null
            val current = if (historical) null else id?.let(after::compilation)
            val old = id?.let { before?.compilation(it) }
            val sourceModel = if (current == null && old != null) before!! else after
            val verified = id?.let { sourceModel.context(it) }
            // Match the exact selected audit label, not ID alone or a null wildcard. A raw
            // exported label cannot upgrade an unavailable context or the old administrative slot.
            val selectedScope = selection.previousScope ?: verified?.context?.scope
                ?: (current ?: old)?.let { exportedScope(sourceModel, it) }
            val obligation = impact.obligations.singleOrNull {
                it.compilationId == id && it.context == selectedScope
            }
            val slotId = when {
                historical -> "retired-context:${digest(listOf(id!!, canonical(selection.previousScope!!)))}"
                id != null -> "compilation:${digest(id)}"
                else -> "project-model-unavailable"
            }
            val reasons = mutableListOf<String>()
            val selectedContext = if (current != null && verified?.context != null) verified.context!! else {
                // Administrative placeholder only: do not reuse old source membership/artifact paths
                // against the new whole snapshot, guess a main compilation, or invent a certificate.
                val previous = current ?: old
                val scope = selection.previousScope ?: previous?.let { exportedScope(sourceModel, it) }
                    ?: RuleContext("unavailable", "unavailable", ":unavailable", "unavailable")
                AnalysisContext(scope = scope, modelDigest = after.identity.modelDigest,
                    configurationDigest = after.identity.configurationDigest, fullProject = false, compilationFiles = emptyList(),
                    policies = observedEvidence?.policies().orEmpty().filterKeys { it != OBSERVED_PLAN_EVIDENCE_POLICY })
            }
            val context = if (observedEvidence == null) selectedContext else {
                if (OBSERVED_PLAN_EVIDENCE_POLICY in selectedContext.policies ||
                    observedEvidence.policies().filterKeys { it != OBSERVED_PLAN_EVIDENCE_POLICY }.any {
                        selectedContext.policies[it.key] != it.value
                    } || selectedContext.fullProject) problem("observed_model_evidence_mismatch", "Raw observed scope/evidence cannot be replaced or upgraded.")
                selectedContext.copy(policies = selectedContext.policies + (OBSERVED_PLAN_EVIDENCE_POLICY to observedEvidence.digest()))
            }
            when {
                historical -> reasons += listOf("required_context_retargeted_unresolved", "consumer_context_changed_without_retirement_evidence")
                id == null -> reasons += listOf("compilation_model_empty", "required_project_scope_unavailable")
                current == null -> reasons += listOf("required_compilation_removed_unresolved", "consumer_removed_without_retirement_evidence")
                verified?.completeness != ModelCompleteness.COMPLETE || verified?.certificateId == null || !context.fullProject ->
                    reasons += if (after is OwnerPlanModelOrigin.Observed) "observed_compilation_context_unavailable"
                        else "verified_compilation_context_unavailable"
            }
            if (observedEvidence != null) reasons += CONFIGURATION_INPUT_CLOSURE_UNPROVEN
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
            if (reasons.isEmpty() && after is OwnerPlanModelOrigin.Verified) {
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
        val plan = RequiredReviewPlan(binding, request, digest(snapshot), digest(after.export.generatedInputs), after.authorityEvidenceDigest,
            impact.evidenceDigest, immutable(slots), recipients)
        if (canonical(plan).toByteArray().size > maxPlanBytes) problem("plan_metadata_limit", "The complete prepared vector exceeds its metadata bound; no prefix was registered.")
        return AssembledReviewPlan(plan, PlanAssemblyProvenance(before, after, impact, inventory, source.authorityDigest,
            slotEvidence.sortedBy { it.slotId }), materialization, source, immutable(preparedSlots)).also { it.verifyForAdmission() }
    }

    private fun validateBinding(binding: ClientBinding, request: SubmitPlanRequest, before: OwnerPlanModelOrigin?, after: OwnerPlanModelOrigin) {
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

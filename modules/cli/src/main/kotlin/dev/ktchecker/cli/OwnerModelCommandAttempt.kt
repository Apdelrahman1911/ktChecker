package dev.ktchecker.cli

import dev.ktchecker.compiler.live.CompilerApplicationNamespace
import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.*
import dev.ktchecker.projectmodel.*
import dev.ktchecker.snapshot.SnapshotCommit
import dev.ktchecker.snapshot.SnapshotIdentity
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*

/**
 * One patch's actual retained objects. Status for an old handle never consults a newer request,
 * snapshot, clock, export, generation or custody. No member is reset for a fix or a failed retry.
 */
internal class OwnerModelCommandAttempt(
    val patchId: String,
    val taskId: String,
    val ordinal: Long,
    val clock: OwnerModelCommandClock,
    private val workbench: Workbench,
    private val runtime: OwnerLiveModelRuntime,
    private val registration: ClientRegistration,
    private val statePin: OwnerModelRootPin,
    val previous: OwnerModelCommandAttempt?,
    private val compilerPolicy: OwnerModelCompilerPolicy,
) {
    private val submitted = AtomicBoolean()
    private val retirementRequested = AtomicBoolean()
    private val parentSubmissionAttempted = AtomicBoolean()
    private val returnedParent = AtomicReference<ParentPlanStatus?>()
    private val retainedAdmission = AtomicReference<OwnerModelPlanAdmission?>()
    private val quarantined = AtomicBoolean()
    private val historyReadUncertain = AtomicBoolean()
    private val custody = OwnerModelCommandCustody(workbench, runtime, clock)
    private val ownerCodeImageDigest = workbench.ownerCodeDigest
    @Volatile private var frozen: OwnerModelFrozenPatch? = null
    @Volatile private var snapshotLineage: OwnerModelSnapshotLineage? = null
    @Volatile private var runPin: OwnerModelRootPin? = null
    @Volatile private var export: OwnerObservedExport? = null
    @Volatile private var observed: ObservedProjectModel? = null
    @Volatile private var prepared: PreparedOwnerModelPlan? = null
    @Volatile private var attemptedPlan: RequiredReviewPlan? = null
    @Volatile private var attemptedReasons: List<String> = emptyList()
    @Volatile private var compilerRuleIds: List<String> = emptyList()
    @Volatile private var generation: OwnerModelGeneration? = null
    @Volatile private var failureCode: String? = null
    @Volatile private var lastStatus: OwnerModelPlanStatus? = null

    val admission: OwnerModelPlanAdmission? get() = retainedAdmission.get()
    val snapshotCommit: SnapshotCommit? get() = snapshotLineage?.commit
    val model: ObservedProjectModel? get() = observed
    val originalDeadline get() = clock.originalDeadline

    fun freeze(input: OwnerModelCompletedPatch): OwnerModelFrozenPatch {
        ownerModelRequire(frozen == null && input.patchId == patchId && input.taskId == taskId && input.ordinal == ordinal,
            "owner_model_patch_changed_before_capture")
        return OwnerModelFrozenPatch.capture(input, registration.binding, clock::checkpoint).also { value ->
            frozen = value
            snapshotLineage = OwnerModelSnapshotLineage(value, previous?.snapshotCommit)
            OwnerModelSnapshotLineage.verifyRequest(value.request, previous?.snapshotCommit)
        }
    }

    fun submit(run: OwnerModelRootPin, cache: OwnerModelCacheSeed, gradle: OwnerModelTreePin,
               request: SubmitPlanRequest? = null): OwnerModelPlanAdmission {
        checkpoint()
        compilerPolicy.requireConfigured()
        ownerModelRequire(submitted.compareAndSet(false, true), "owner_model_command_submission_replayed")
        runPin = run
        verifyAuditRoots(); workbench.verifyOwnerCode()
        val source = requireNotNull(frozen)
        ownerModelRequire(cache.runRoot == run.path, "owner_model_cache_run_mismatch")
        source.write(run.path.resolve("owner-patch.json"), ::checkpoint)
        val selectedRequest = request ?: SubmitPlanRequest(patchId,
            "owner-model:${clock.invocation.identity}", "owner-model:${clock.invocation.identity}")
        ownerModelRequire(selectedRequest.patchId == patchId, "owner_model_parent_request_mismatch")
        listOf(selectedRequest.idempotencyKey, selectedRequest.planRef).forEach(::ownerModelIdentity)
        val intent = workbench.beginModelReview(registration.binding.clientId, selectedRequest, true, originalDeadline)
        compilerPolicy.verifyIntent(intent)
        ownerModelRequire(intent.originalDeadline === originalDeadline && intent.binding == registration.binding &&
            intent.request == selectedRequest, "owner_model_intent_mismatch")
        val captured = requireNotNull(snapshotLineage).capture(workbench.snapshots, ::checkpoint)
        val identity = SnapshotIdentity(registration.binding.ownerId, registration.binding.workspaceId, registration.binding.worktreeId)
        val materialized = workbench.snapshots.materialize(identity, captured.snapshotId, run.path.resolve("snapshot"), readOnly = true)
        checkpoint()
        val actualExport = OwnerObservedExport(workbench, runtime.installation, intent, materialized, run.path,
            gradle, cache, clock)
        export = actualExport
        val imported = actualExport.run()
        observed = imported
        ownerModelRequire(imported.invocation === clock.invocation && imported.invocation.originalDeadline === originalDeadline &&
            imported.snapshot == captured.toContractManifest() && imported.assuranceProfile == OBSERVED_EXPORT_PROFILE &&
            imported.issues.any { it.kind == CONFIGURATION_INPUT_CLOSURE_UNPROVEN && it.scopeId == null },
            "owner_model_observed_return_mismatch")
        val before = previous?.model // DOCUMENTARY only. Do not invoke its grant or original clock.
        ownerModelRequire(previous == null || before != null && before.snapshot == previous.snapshotCommit?.toContractManifest() &&
            before.snapshot.snapshotId == imported.snapshot.parentSnapshotId && before !== imported &&
            before.invocation !== imported.invocation && before.invocation.originalDeadline !== originalDeadline,
            "owner_model_documentary_before_unavailable")
        checkpoint()
        if (imported.jvmProducedGraph != null) {
            val selected = OwnerModelGeneration(workbench, runtime, CompilerApplicationNamespace(registration.binding.ownerId,
                registration.binding.workspaceId, registration.binding.worktreeId), run.path.resolve("generation"), clock, custody)
            generation = selected
            selected.open() // The actual fixed installed worker; never a synthetic HELLO/result.
        }
        val assembled = try {
            // Pass the ENTIRE direct parent, including removed/retargeted contexts. No prefix plan.
            workbench.prepareObservedModelReview(intent, before, imported, materialized)
        } catch (failure: ObservedModelPlanUnavailable) {
            attemptedPlan = failure.attemptedPlan
            attemptedReasons = failure.reasons.toList()
            throw failure
        }
        prepared = assembled
        attemptedPlan = assembled.assembly.plan
        ownerModelRequire(assembled.assembly.provenance.observedAfter === imported &&
            assembled.assembly.provenance.observedBefore === before && assembled.request == intent.request &&
            assembled.assembly.plan.jvmProduced != null, "owner_model_nominal_parent_unavailable")
        val catalog = Catalog()
        val projections = assembled.assembly.plan.slots.map { slot ->
            val activation = validatedActivation(slot.input, catalog)
            slot.input.requiredRuleIds.filter { activation[it]?.active == true && it in runtime.provider.supportedRuleIds }.sorted()
        }
        ownerModelRequire(projections.isNotEmpty() && projections.distinct() == listOf(compilerPolicy.compilerRuleIds),
            "owner_model_whole_compiler_projection_mismatch")
        compilerRuleIds = projections.first().toList()
        custody.retain(assembled, imported) // BEFORE submission can trigger a kernel callback.
        checkpoint()
        ownerModelRequire(parentSubmissionAttempted.compareAndSet(false, true), "owner_model_parent_submission_replayed")
        val accepted = workbench.submitModelPlan(registration.client, assembled.request)
        returnedParent.set(accepted) // Sticky actual return BEFORE verification; never claim false.
        verifyParent(assembled, accepted)
        val handle = OwnerModelPlanAdmission(captured, assembled, imported, accepted,
            OwnerModelDeliveryHandle(workbench.kernel, registration, accepted.planId))
        ownerModelRequire(retainedAdmission.compareAndSet(null, handle), "owner_model_parent_handle_replayed")
        return handle
    }

    /** Audit only, including after expiry or next patch. No earlier source/artifact/grant re-read. */
    fun status(handle: OwnerModelPlanAdmission): OwnerModelPlanStatus {
        ownerModelRequire(retainedAdmission.get() === handle, "owner_model_delivery_handle_mismatch")
        return try {
        verifyAuditRoots()
        val parent = workbench.kernel.planStatus(registration.client, handle.parent.planId)
        verifyParent(handle.prepared, parent)
        val children = parent.slots.map { slot ->
            val childPatch = "plan-child:" + ownerProducedDigest(ContractJson.encodeToJsonElement(listOf(parent.planId, slot.slotId)))
            workbench.kernel.status(registration.client, slot.reviewId).also { child ->
                ownerModelRequire(child.reviewId == slot.reviewId && child.clientId == parent.clientId &&
                    child.sourceSnapshotId == parent.sourceSnapshotId && child.patchId == childPatch,
                    "owner_model_parent_child_mismatch")
            }
        }
        OwnerModelPlanStatus(parent, children).also { lastStatus = it }
        } catch (failure: Throwable) { historyReadUncertain.set(true); throw failure }
    }

    fun await(handle: OwnerModelPlanAdmission): OwnerModelPlanStatus {
        var value = status(handle)
        while (!value.terminal && !clock.cancelled()) {
            Thread.sleep(25)
            value = status(handle)
        }
        return value
    }

    /** Owner-side transition, not status-based fake cleanup. An actual CLOSED return is mandatory. */
    fun retireForNextPatch() {
        val handle = admission ?: throw CliProblem("owner_model_previous_admission_unavailable", "The direct previous whole parent is unknown.")
        ownerModelRequire(status(handle).terminal, "owner_model_previous_parent_nonterminal")
        ownerModelRequire(export?.childGroupQuiescent == true, "owner_model_previous_export_quiescence_unverified")
        if (!retirementRequested.get()) retireOwnedGeneration()
        ownerModelRequire(generation?.wasOpened == true && generation?.retirementVerified == true && safeToClose,
            "owner_model_previous_retirement_unverified")
        clock.deny() // No callbacks from this old export/model/custody can enroll more work.
    }

    /** At most ONE explicit actual retirement, including after expiry. close() never calls this. */
    fun retireOwnedGeneration() {
        ownerModelRequire(!quarantined.get() && retirementRequested.compareAndSet(false, true),
            "owner_model_command_retirement_replayed_or_quarantined")
        generation?.takeIf { it.wasOpened }?.retire()
    }

    val historyUncertain: Boolean get() = historyReadUncertain.get() || snapshotLineage?.historyUncertain == true ||
        parentSubmissionAttempted.get() && admission == null
    val retirementWasRequested: Boolean get() = retirementRequested.get()
    val needsQuarantine: Boolean get() = historyUncertain || export?.safeToClose == false ||
        generation?.let { it.retirementWasAttempted && !it.retirementVerified } == true
    val safeToClose: Boolean get() = !historyUncertain && export?.safeToClose != false &&
        generation?.let { !it.wasOpened || it.retirementVerified } != false

    fun failed(code: String) { failureCode = code }
    fun quarantine() {
        quarantined.set(true); clock.deny()
        export?.quarantine(); generation?.quarantine()
    }

    /** Host DATA projection only. Opaque admission remains JVM-side in the same owner registry. */
    fun admissionData(handle: OwnerModelPlanAdmission): JsonObject {
        ownerModelRequire(admission === handle, "owner_model_delivery_handle_mismatch")
        val current = handle.observedModel
        return buildJsonObject {
            put("snapshotCommit", ContractJson.encodeToJsonElement(handle.snapshotCommit))
            put("declaredPlan", ContractJson.encodeToJsonElement(handle.prepared.assembly.plan))
            put("parent", ContractJson.encodeToJsonElement(handle.parent))
            putJsonObject("observed") {
                put("invocationId", current.invocation.identity); put("evidenceIdentity", current.evidenceIdentity)
                put("observedEvidenceDigest", current.observedEvidenceDigest); put("modelDigest", current.identity.modelDigest)
                put("ownerCodeImageDigest", ownerCodeImageDigest)
                put("originalDeadlineIdentity", current.invocation.originalDeadlineIdentity)
                put("originalDeadlineMillis", current.invocation.originalDeadlineMillis)
                put("assurance", current.assuranceProfile)
                put("reasons", ContractJson.encodeToJsonElement(current.issues.map { it.kind }.distinct().sorted()))
                put("documentaryBefore", documentaryBefore())
            }
            put("compilerRuleIds", ContractJson.encodeToJsonElement(compilerRuleIds))
        }
    }

    fun evidence(status: OwnerModelPlanStatus? = lastStatus): JsonObject = buildJsonObject {
        put("schema", "ktchecker/owner-observed-model-attempt/1")
        put("ordinal", ordinal); put("patchId", patchId); put("taskId", taskId)
        put("requestDigest", frozen?.digest?.let(::JsonPrimitive) ?: JsonNull)
        put("binding", ContractJson.encodeToJsonElement(registration.binding))
        put("originalDeadlineIdentity", originalDeadline.identity); put("originalDeadlineMillis", originalDeadline.durationMillis)
        put("originalDeadlineExpired", originalDeadline.isExpired()); put("observedInvocationId", clock.invocation.identity)
        put("runRoot", runPin?.path?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("snapshotMutationAttempted", snapshotLineage?.mutationAttempted ?: false)
        put("snapshotMutationVerified", snapshotLineage?.mutationVerified ?: false)
        put("historyUncertain", historyUncertain)
        put("snapshotCommit", snapshotCommit?.let { ContractJson.encodeToJsonElement(it) } ?: JsonNull)
        put("parentSubmissionAttempted", parentSubmissionAttempted.get())
        put("parentAccepted", when { admission != null -> JsonPrimitive(true); parentSubmissionAttempted.get() -> JsonNull; else -> JsonPrimitive(false) })
        put("parentSubmissionState", when {
            admission != null -> "verified_accepted"
            parentSubmissionAttempted.get() -> "attempted_outcome_unverified"
            else -> "not_attempted"
        })
        put("submissionReturnedParent", returnedParent.get()?.let { ContractJson.encodeToJsonElement(it) } ?: JsonNull)
        put("originalDeclaredPlan", attemptedPlan?.let { ContractJson.encodeToJsonElement(it) } ?: JsonNull)
        put("preparationReasons", prepared?.let { ContractJson.encodeToJsonElement(it.preparationReasons) }
            ?: ContractJson.encodeToJsonElement(attemptedReasons))
        put("parent", status?.parent?.let { ContractJson.encodeToJsonElement(it) }
            ?: admission?.parent?.let { ContractJson.encodeToJsonElement(it) } ?: JsonNull)
        put("children", status?.let { ContractJson.encodeToJsonElement(it.children) } ?: JsonNull)
        put("reviewVectorTerminal", status?.terminal ?: false)
        put("documentaryBefore", documentaryBefore())
        put("observedEvidenceIdentity", observed?.evidenceIdentity?.let(::JsonPrimitive) ?: JsonNull)
        put("observedExport", export?.evidence() ?: JsonNull)
        put("generation", generation?.evidence() ?: JsonNull); put("custody", custody.evidence())
        put("failureCode", failureCode?.let(::JsonPrimitive) ?: JsonNull)
        put("failureInventory", if (failureCode != null) failureInventory() else JsonNull)
        put("retirementRequested", retirementRequested.get()); put("safeToClose", safeToClose)
        put("quarantined", quarantined.get()); put("qualified", false); put("usefulComplete", false)
    }

    /** Not a fabricated RequiredReviewPlan when strict observation/import could not produce one. */
    private fun failureInventory(): JsonObject = ownerModelUnresolvedInventory(
        snapshotCommit?.takeIf { snapshotLineage?.mutationVerified == true }?.manifest,
        frozen?.entries, documentaryBefore(), previous?.attemptedPlan?.slots, attemptedPlan)

    private fun documentaryBefore(): JsonElement = previous?.model?.let { prior -> buildJsonObject {
        put("snapshotId", prior.snapshot.snapshotId); put("invocationId", prior.invocation.identity)
        put("evidenceIdentity", prior.evidenceIdentity); put("modelDigest", prior.identity.modelDigest)
    } } ?: JsonNull

    private fun verifyParent(plan: PreparedOwnerModelPlan, parent: ParentPlanStatus) {
        val definition = plan.assembly.plan
        ownerModelRequire(parent.clientId == registration.binding.clientId && parent.patchId == patchId &&
            parent.planId.isNotBlank() && parent.planId.length <= 256 &&
            (admission == null || parent.planId == admission?.parent?.planId) &&
            parent.planDigest == plan.assembly.planDigest && parent.sourceSnapshotId == requireNotNull(snapshotCommit).snapshotId &&
            parent.slots.map { it.slotId } == definition.slots.map { it.slotId } &&
            parent.slots.all { it.reviewId.isNotBlank() } && parent.slots.map { it.reviewId }.distinct().size == parent.slots.size &&
            parent.slots.zip(definition.slots).all { (actual, declared) ->
                actual.context == declared.input.context.scope && actual.requiredRuleIds == declared.input.requiredRuleIds
            }, "owner_model_parent_admission_mismatch")
    }
    private fun verifyAuditRoots() {
        statePin.verify(); runPin?.verify()
        ownerModelRequire(workbench.registration(registration.binding.clientId) == registration, "owner_model_registration_changed")
    }
    private fun checkpoint() {
        ownerModelRequire(!quarantined.get(), "owner_model_attempt_quarantined")
        clock.checkpoint(); statePin.verify(); runPin?.verify()
    }
}

package dev.ktchecker.cli

import dev.ktchecker.compiler.KotlinJvmModelException
import dev.ktchecker.compiler.KotlinJvmRuntimeException
import dev.ktchecker.compiler.KotlinJvmToolchain
import dev.ktchecker.compiler.live.CompilerApplicationNamespace
import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.*
import dev.ktchecker.projectmodel.*
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import dev.ktchecker.snapshot.*
import dev.ktchecker.workerprotocol.CompilerModelWire as ModelWire
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

internal const val OWNER_MODEL_ORIGINAL_MILLIS = 120_000L
private const val OWNER_MODEL_REQUEST_BYTES = 8_388_608
// Normally one CLI owner. An already-open concurrent owner must not overwrite/discard an older
// quarantine; this set never authorizes another open and is not a restart/recovery registry.
private val quarantinedOwnerModelCommands = Collections.synchronizedSet(mutableSetOf<OwnerModelCommandSession>())

/** Complete explicit immutable submission, never a scan/diff of a mutable checkout. */
@Serializable internal data class OwnerModelSubmittedFile(
    val path: String,
    val content: String? = null,
    val contentBase64: String? = null,
    val executable: Boolean = false,
)

/** No model, graph, origin, qualification, worker, permit, receipt or cache-reuse assertions. */
@Serializable internal data class OwnerModelCommandDocument(
    val schemaVersion: Int,
    val workspaceId: String,
    val worktreeId: String,
    val clientId: String,
    val sessionId: String,
    val lineageId: String,
    val taskId: String,
    val patchId: String,
    val idempotencyKey: String,
    val planRef: String,
    val gradleHome: String,
    val gradleCacheSeed: String,
    val compilerArtifactPaths: List<String>,
    val files: List<OwnerModelSubmittedFile>,
)

/** In-process owner-only handle. Never serialized, placed in model context or restored from JSON. */
internal class OwnerModelDeliveryHandle internal constructor(
    val broker: KernelService,
    val registration: ClientRegistration,
    val planId: String,
)

/** Actual returned admission and retained capabilities, not a detached same-object assertion. */
internal class OwnerModelPlanAdmission internal constructor(
    val snapshotCommit: SnapshotCommit,
    val prepared: PreparedOwnerModelPlan,
    val observedModel: ObservedProjectModel,
    val parent: ParentPlanStatus,
    val deliveryHandle: OwnerModelDeliveryHandle,
)

internal class OwnerModelPlanStatus internal constructor(
    val parent: ParentPlanStatus,
    children: List<ReviewStatus>,
) {
    val children: List<ReviewStatus> = Collections.unmodifiableList(children.toList())
    // The produced parent remains pending/incomplete even after work finishes. A failed consumer
    // resolution has no compiler result; retain its real unavailable row, never invent a return.
    val terminal: Boolean = parent.slots.zip(this.children).all { (slot, child) ->
        (child.cancelled && child.state == "cancelled") ||
            (slot.state == "unavailable" && child.state == "incomplete" && child.reasons.isNotEmpty()) ||
            (child.result != null && child.state in setOf("complete", "incomplete", "cancelled"))
    }
}

/**
 * Interim one-parent production owner composition. This is NOT yet a patch/fix history service:
 * state must be new and before=null. A subsequent implementation must preserve actual snapshot
 * and ledger lineage, not infer it from caller-supplied previousPatchDigest metadata.
 *
 * One exact original clock covers preparation/export/import/admission/jobs. No ordinary scalar
 * submission, imported capability reuse, new attempt clock, ACK or model observation occurs here.
 * The retained delivery handle lets a trusted in-process adapter use the SAME broker separately.
 */
internal class OwnerModelCommandSession private constructor(
    private val document: OwnerModelCommandDocument,
    val originalDeadline: OwnedApplicationDeadline,
    private val invocation: ObservedExportInvocation,
    private val statePin: OwnerModelRootPin,
    private val runPin: OwnerModelRootPin,
    private val cache: OwnerModelCacheSeed,
    private val workbench: Workbench,
    private val runtime: OwnerLiveModelRuntime,
    private val registration: ClientRegistration,
    private val intent: OwnerModelIntent,
    private val requestDigest: String,
) : AutoCloseable {
    private val submitted = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val quarantined = AtomicBoolean()
    private val retirementRequested = AtomicBoolean()
    private val retainedAdmission = AtomicReference<OwnerModelPlanAdmission?>()
    private val parentSubmissionAttempted = AtomicBoolean()
    private val returnedParent = AtomicReference<ParentPlanStatus?>()
    private val custody = OwnerModelCommandCustody(workbench, runtime, originalDeadline)
    private var commit: SnapshotCommit? = null
    private var export: OwnerObservedExport? = null
    private var model: ObservedProjectModel? = null
    private var prepared: PreparedOwnerModelPlan? = null
    private var attemptedPlan: RequiredReviewPlan? = null
    private var attemptedReasons: List<String> = emptyList()
    private var generation: OwnerModelGeneration? = null

    fun submitCompletedPatch(): OwnerModelPlanAdmission {
        checkpoint()
        ownerModelRequire(submitted.compareAndSet(false, true), "owner_model_command_submission_replayed")
        val captured = workbench.overlay(OverlayDocument(document.workspaceId, document.worktreeId,
            document.clientId, document.sessionId, document.patchId,
            changes = document.files.map { file -> OverlayChange("put", file.path, file.content, file.contentBase64,
                executable = file.executable) }, scope = SnapshotScope("complete explicitly submitted immutable owner files")))
        commit = captured
        ownerModelRequire(!captured.replayed && captured.headRevision == 1L && captured.manifest.parentSnapshotId == null &&
            captured.manifest.producerBaseSnapshotId == null && captured.manifest.patchId == document.patchId &&
            captured.manifest.clientId == registration.binding.clientId && captured.manifest.sessionId == registration.binding.sessionId &&
            captured.manifest.capture.kind == CaptureKind.IMMUTABLE_OVERLAY && captured.manifest.capture.coherent &&
            captured.manifest.entries.size == document.files.size && captured.manifest.scope.exclusions.isEmpty() &&
            captured.manifest.scope.unknownInputs.isEmpty(), "owner_model_snapshot_submission_mismatch")
        checkpoint()
        val identity = SnapshotIdentity(registration.binding.ownerId, document.workspaceId, document.worktreeId)
        val materialized = workbench.snapshots.materialize(identity, captured.snapshotId, runPin.path.resolve("snapshot"), readOnly = true)
        checkpoint()
        val actualExport = OwnerObservedExport(workbench, runtime.installation, intent, materialized, runPin.path,
            Path.of(document.gradleHome), cache, originalDeadline, invocation)
        export = actualExport
        val imported = actualExport.run()
        model = imported
        ownerModelRequire(imported.invocation === invocation && imported.invocation.originalDeadline === originalDeadline &&
            imported.snapshot == captured.toContractManifest() && imported.assuranceProfile == OBSERVED_EXPORT_PROFILE &&
            imported.issues.any { it.kind == CONFIGURATION_INPUT_CLOSURE_UNPROVEN && it.scopeId == null },
            "owner_model_observed_return_mismatch")
        checkpoint()
        if (imported.jvmProducedGraph != null) {
            val selected = OwnerModelGeneration(workbench, runtime, CompilerApplicationNamespace(registration.binding.ownerId,
                document.workspaceId, document.worktreeId), runPin.path.resolve("generation"), originalDeadline, custody)
            generation = selected
            selected.open() // Genuine fixed installed worker, never a fabricated HELLO or compiler return.
        }
        val assembled = try {
            workbench.prepareObservedModelReview(intent, null, imported, materialized)
        } catch (failure: ObservedModelPlanUnavailable) {
            attemptedPlan = failure.attemptedPlan
            attemptedReasons = failure.reasons.toList()
            throw failure // Full unsupported/removed/extra vector is evidence, NOT an admitted prefix.
        }
        prepared = assembled
        attemptedPlan = assembled.assembly.plan
        ownerModelRequire(assembled.assembly.provenance.observedAfter === imported &&
            assembled.assembly.provenance.observedBefore == null && assembled.request == intent.request &&
            assembled.assembly.plan.jvmProduced != null, "owner_model_nominal_parent_unavailable")
        custody.retain(assembled, imported) // BEFORE submission can trigger any kernel callback.
        checkpoint()
        // Sticky BEFORE the call: an exception can occur after a durable kernel commit.
        // Never retry or report false/unaccepted solely because no verified handle came back.
        ownerModelRequire(parentSubmissionAttempted.compareAndSet(false, true), "owner_model_parent_submission_replayed")
        val accepted = workbench.submitModelPlan(registration.client, assembled.request)
        returnedParent.set(accepted) // Retain the actual returned object even if verification throws.
        verifyParent(assembled, accepted)
        val handle = OwnerModelPlanAdmission(captured, assembled, imported, accepted,
            OwnerModelDeliveryHandle(workbench.kernel, registration, accepted.planId))
        ownerModelRequire(retainedAdmission.compareAndSet(null, handle), "owner_model_parent_handle_replayed")
        return handle
    }

    /** Audit reads are allowed after expiry; this never refreshes a clock or launches any work. */
    fun status(handle: OwnerModelPlanAdmission): OwnerModelPlanStatus {
        ownerModelRequire(!closed.get() && retainedAdmission.get() === handle, "owner_model_delivery_handle_mismatch")
        statePin.verify(); runPin.verify()
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
        return OwnerModelPlanStatus(parent, children)
    }

    fun await(handle: OwnerModelPlanAdmission): OwnerModelPlanStatus {
        var observed = status(handle)
        while (!observed.terminal && !originalDeadline.isExpired() && !Thread.currentThread().isInterrupted) {
            Thread.sleep(25)
            observed = status(handle)
        }
        return observed
    }

    /** Explicit command-owner retirement, at most once. close() itself never retires a generation. */
    fun retireOwnedGeneration() {
        ownerModelRequire(retirementRequested.compareAndSet(false, true), "owner_model_command_retirement_replayed")
        generation?.takeIf { it.wasOpened }?.retire()
    }

    // For Gradle this is only the approved child-group-quiescent boundary, NOT a resource-CLOSED
    // receipt. Native helper image/pipe cleanup can remain asynchronous after a successful return.
    val safeToClose: Boolean get() = export?.safeToClose != false &&
        generation?.let { !it.wasOpened || it.retirementVerified } != false

    fun quarantine() {
        quarantined.set(true)
        export?.quarantine()
        generation?.quarantine()
    }

    override fun close() {
        ownerModelRequire(safeToClose && !quarantined.get(), "owner_model_cleanup_unverified_retained")
        if (closed.compareAndSet(false, true)) workbench.close()
    }

    /** No credential, source content, ACK, observation, useful-complete or final gate claim. */
    fun evidence(status: OwnerModelPlanStatus? = null, failureCode: String? = null): JsonObject = buildJsonObject {
        put("contractVersion", PREVIEW_CONTRACT); put("schema", "ktchecker/owner-observed-model-command/1")
        put("state", if (quarantined.get()) "cleanup_unverified_quarantine" else if (failureCode != null) "failed_unaccepted_or_incomplete"
            else if (status?.terminal == true) "observed_preview_terminal" else "observed_preview_pending")
        put("requestDigest", requestDigest); put("taskId", document.taskId)
        put("binding", ContractJson.encodeToJsonElement(registration.binding))
        put("originalDeadlineIdentity", originalDeadline.identity); put("originalDeadlineMillis", originalDeadline.durationMillis)
        put("originalDeadlineExpired", originalDeadline.isExpired()); put("observedInvocationId", invocation.identity)
        put("ownerState", statePin.path.toString()); put("runRoot", runPin.path.toString())
        put("parentSubmissionAttempted", parentSubmissionAttempted.get())
        put("parentAccepted", when {
            retainedAdmission.get() != null -> JsonPrimitive(true)
            parentSubmissionAttempted.get() -> JsonNull
            else -> JsonPrimitive(false)
        })
        put("parentSubmissionState", when {
            retainedAdmission.get() != null -> "verified_accepted"
            parentSubmissionAttempted.get() -> "attempted_outcome_unverified"
            else -> "not_attempted"
        })
        put("submissionReturnedParent", returnedParent.get()?.let { ContractJson.encodeToJsonElement(it) } ?: JsonNull)
        put("snapshotCommit", commit?.let { ContractJson.encodeToJsonElement(it) } ?: JsonNull)
        put("originalDeclaredPlan", attemptedPlan?.let { ContractJson.encodeToJsonElement(it) } ?: JsonNull)
        put("preparationReasons", prepared?.let { ContractJson.encodeToJsonElement(it.preparationReasons) }
            ?: ContractJson.encodeToJsonElement(attemptedReasons))
        put("parent", status?.parent?.let { ContractJson.encodeToJsonElement(it) }
            ?: retainedAdmission.get()?.parent?.let { ContractJson.encodeToJsonElement(it) } ?: JsonNull)
        put("children", status?.let { ContractJson.encodeToJsonElement(it.children) } ?: JsonNull)
        put("reviewVectorTerminal", status?.terminal ?: false)
        put("observedEvidenceIdentity", model?.evidenceIdentity?.let(::JsonPrimitive) ?: JsonNull)
        put("observedExport", export?.evidence() ?: JsonNull)
        put("generation", generation?.evidence() ?: JsonNull); put("custody", custody.evidence())
        put("failureCode", failureCode?.let(::JsonPrimitive) ?: JsonNull)
        put("assurance", OBSERVED_EXPORT_PROFILE); put("configurationInputClosureProven", false)
        put("mandatoryGap", CONFIGURATION_INPUT_CLOSURE_UNPROVEN)
        put("retirementRequested", retirementRequested.get()); put("safeToClose", safeToClose)
        put("patchFixLineageSupported", false); put("hostInsertionOrObservationPerformed", false)
        put("releaseQualified", false); put("usefulComplete", false); put("finalAnswerAuthorized", false)
    }

    private fun verifyParent(plan: PreparedOwnerModelPlan, parent: ParentPlanStatus) {
        val definition = plan.assembly.plan
        ownerModelRequire(parent.clientId == registration.binding.clientId && parent.patchId == document.patchId &&
            parent.planDigest == plan.assembly.planDigest && parent.sourceSnapshotId == requireNotNull(commit).snapshotId &&
            parent.slots.map { it.slotId } == definition.slots.map { it.slotId } &&
            parent.slots.all { it.reviewId.isNotBlank() } && parent.slots.map { it.reviewId }.distinct().size == parent.slots.size &&
            parent.slots.zip(definition.slots).all { (actual, declared) ->
                actual.context == declared.input.context.scope && actual.requiredRuleIds == declared.input.requiredRuleIds
            }, "owner_model_parent_admission_mismatch")
    }

    private fun checkpoint() {
        ownerModelRequire(!closed.get() && !quarantined.get(), "owner_model_command_unavailable")
        ownerModelCheckpoint(originalDeadline); invocation.verifyLive()
        statePin.verify(); runPin.verify()
    }

    companion object {
        /** originalDeadline MUST be allocated by the owner at entry, before reading/validating input. */
        fun open(request: OwnerModelCommandDocument, stateRoot: Path, javaHome: Path, authorizeExecution: Boolean,
                 originalDeadline: OwnedApplicationDeadline): OwnerModelCommandSession {
            ownerModelCheckpoint(originalDeadline)
            ownerModelRequire(quarantinedOwnerModelCommands.isEmpty(), "owner_model_quarantine_retained")
            ownerModelRequire(originalDeadline.durationMillis == OWNER_MODEL_ORIGINAL_MILLIS && authorizeExecution,
                "owner_model_explicit_execution_consent_required")
            val invocation = ObservedExportInvocation(originalDeadline) // Before capture/copy or any launch.
            // Typed callers also get strict Unicode validation: never replace an unpaired
            // surrogate before freezing the complete explicit source/identity document.
            val encoded = try {
                val buffer = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(Digests.canonical(ContractJson.encodeToJsonElement(request))))
                ByteArray(buffer.remaining()).also { buffer.get(it) }
            } catch (_: Exception) { throw CliProblem("owner_model_request_invalid", "Expected exact valid Unicode input.") }
            ownerModelRequire(encoded.size in 1..OWNER_MODEL_REQUEST_BYTES, "owner_model_request_limit")
            val document = ContractJson.decodeFromString<OwnerModelCommandDocument>(strictJsonDocument(encoded, "owner_model_request_invalid"))
            validateOwnerModelDocument(document, originalDeadline)
            val selectedState = ownerModelPath(stateRoot, false)
            val selectedJava = ownerModelPath(javaHome)
            val selectedGradle = ownerModelPath(Path.of(document.gradleHome))
            val selectedSeed = ownerModelPath(Path.of(document.gradleCacheSeed))
            val selectedRoots = listOf(selectedState, selectedJava, selectedGradle, selectedSeed)
            ownerModelRequire(selectedRoots.indices.all { i -> selectedRoots.indices.all { j ->
                i == j || !selectedRoots[i].startsWith(selectedRoots[j])
            } }, "owner_model_selected_roots_overlap")
            ownerModelRequire(!Files.exists(selectedState, NOFOLLOW_LINKS), "owner_model_fresh_state_required")
            val statePin = ownerModelFreshDirectory(selectedState)
            val state = PrivateState(selectedState)
            val runPin = ownerModelFreshDirectory(state.root.resolve("model-command"))
            ownerModelWrite(runPin.path.resolve("owner-request.json"), encoded, OWNER_MODEL_REQUEST_BYTES) {
                ownerModelCheckpoint(originalDeadline)
            }
            val cache = OwnerModelCacheSeed(selectedSeed, runPin.path, originalDeadline)
            val artifacts = cache.selectCompiler(document.compilerArtifactPaths)
            ownerModelCheckpoint(originalDeadline)
            val installation = KotlinJvmToolchain(selectedJava.resolve("bin/java"), artifacts)
            ownerModelCheckpoint(originalDeadline)
            cache.bindInstallation(installation, originalDeadline)
            val home = Workbench.distributionHome() ?: throw CliProblem("owner_model_distribution_unavailable",
                "Use the installed distribution's fixed model worker.")
            val bridge = OwnerModelFilePin.capture(ownerModelPath(home.resolve(OWNER_MODEL_WORKER)), ModelWire.MAX_BRIDGE_BYTES) {
                ownerModelCheckpoint(originalDeadline)
            }
            val runtime = OwnerLiveModelRuntime(state.ownerId, installation, bridge.digest, OWNER_MODEL_ORIGINAL_MILLIS)
            var workbench: Workbench? = null
            try {
                val selected = Workbench(WorkbenchOptions(state = state.root, workerCount = 1,
                    compilerJavaHome = selectedJava, liveModelRuntime = runtime))
                workbench = selected
                ownerModelCheckpoint(originalDeadline)
                val registration = selected.register(document.workspaceId, document.worktreeId, document.clientId,
                    document.sessionId, document.lineageId)
                val intent = selected.beginModelReview(document.clientId,
                    SubmitPlanRequest(document.patchId, document.idempotencyKey, document.planRef), authorizeExecution, originalDeadline)
                ownerModelRequire(intent.originalDeadline === originalDeadline && intent.binding == registration.binding)
                bridge.verify { ownerModelCheckpoint(originalDeadline) }
                return OwnerModelCommandSession(document, originalDeadline, invocation, statePin, runPin, cache,
                    selected, runtime, registration, intent, Digests.sha256(encoded))
            } catch (failure: Throwable) {
                // No application is opened in this factory. Only our own fresh broker can close.
                workbench?.close()
                throw failure
            }
        }
    }
}

private fun validateOwnerModelDocument(document: OwnerModelCommandDocument, deadline: OwnedApplicationDeadline) {
    ownerModelCheckpoint(deadline)
    ownerModelRequire(document.schemaVersion == 1 && listOf(document.workspaceId, document.worktreeId, document.clientId,
        document.sessionId, document.lineageId, document.taskId, document.patchId, document.idempotencyKey, document.planRef).all {
        it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= 256 && it.none { c -> c.code < 32 || c.code == 127 }
    }, "owner_model_request_identity_invalid")
    ownerModelRequire(document.files.size in 1..ModelWire.MAX_SNAPSHOT_FILES &&
        document.files.map { it.path }.distinct().size == document.files.size, "owner_model_snapshot_limit_or_duplicate")
    var bytes = 0L
    document.files.forEach { file ->
        ownerModelCheckpoint(deadline)
        val path = Path.of(file.path)
        ownerModelRequire(file.path.isNotEmpty() && !path.isAbsolute && path.normalize() == path && path.toString() == file.path &&
            file.path.toByteArray(Charsets.UTF_8).size <= ModelWire.MAX_LOGICAL_PATH_BYTES &&
            file.path.split('/').size <= ModelWire.MAX_PATH_DEPTH && file.path.split('/').all { it.isNotEmpty() && it !in setOf(".", "..") } &&
            file.path.none { it.code < 32 || it.code == 127 || it == '\\' } &&
            (file.content == null) != (file.contentBase64 == null), "owner_model_snapshot_file_invalid")
        val content = file.content?.toByteArray(Charsets.UTF_8) ?: try { Base64.getDecoder().decode(requireNotNull(file.contentBase64)) }
            catch (_: IllegalArgumentException) { throw CliProblem("owner_model_snapshot_file_invalid", "Invalid immutable file encoding.") }
        ownerModelRequire(content.size <= ModelWire.MAX_SOURCE_BYTES && content.size.toLong() <= ModelWire.MAX_SNAPSHOT_BYTES - bytes,
            "owner_model_snapshot_limit")
        bytes += content.size
    }
    val names = document.files.map { it.path }.toSet()
    ownerModelRequire(ownerModelDirectories(names).none { it in names }, "owner_model_snapshot_path_collision")
}

internal fun runOwnerModelPlan(options: Arguments): Int {
    val originalDeadline = OwnedApplicationDeadline.afterMillis(OWNER_MODEL_ORIGINAL_MILLIS) // FIRST statement.
    ownerModelRequire(options.words == listOf("model-plan") && options.has("authorize-execution") &&
        listOf("compiler-dir", "detekt-artifacts", "ktlint-artifacts", "workers", "prepared", "wait-ms", "review", "cursor",
            "limit", "finding", "expected", "layer", "rule", "workspace", "worktree", "client", "session", "lineage", "id", "owner-action")
            .none(options::has), "owner_model_command_options_invalid")
    val state = ownerModelPath(Path.of(options.required("state")), false)
    val javaHome = ownerModelPath(Path.of(options.required("compiler-java-home")))
    val out = options["out"]?.let { ownerModelPath(Path.of(it), false) }
    ownerModelRequire(out == null || !Files.exists(out, NOFOLLOW_LINKS), "owner_model_output_in_use")
    val outputParent = out?.let { OwnerModelRootPin.capture(it.parent) }
    val request = ownerModelPath(Path.of(options.required("request")))
    val pin = OwnerModelFilePin.capture(request, OWNER_MODEL_REQUEST_BYTES.toLong()) { ownerModelCheckpoint(originalDeadline) }
    val bytes = pin.read(OWNER_MODEL_REQUEST_BYTES) { ownerModelCheckpoint(originalDeadline) }
    val document = try { ContractJson.decodeFromString<OwnerModelCommandDocument>(strictJsonDocument(bytes, "owner_model_request_invalid")) }
        catch (_: Exception) { throw CliProblem("owner_model_request_invalid", "Expected the bounded explicit immutable-file model command schema.") }
    ownerModelRequire(out == null || listOf(state, javaHome, Path.of(document.gradleHome), Path.of(document.gradleCacheSeed))
        .none { out.startsWith(it) }, "owner_model_output_overlaps_selected_inputs")
    var session: OwnerModelCommandSession? = null
    var status: OwnerModelPlanStatus? = null
    var failureCode: String? = null
    try {
        val owned = OwnerModelCommandSession.open(document, state, javaHome, true, originalDeadline)
        session = owned
        val admitted = owned.submitCompletedPatch()
        status = owned.await(admitted)
    } catch (failure: Throwable) {
        if (failure is InterruptedException) Thread.currentThread().interrupt()
        failureCode = ownerModelCommandFailure(failure)
    }
    val owned = session
    if (owned != null) {
        try { owned.retireOwnedGeneration() }
        catch (failure: Throwable) { failureCode = failureCode ?: ownerModelCommandFailure(failure) }
        if (!owned.safeToClose) {
            // Retain BEFORE evidence construction/serialization/output. Occupancy never drops an
            // already-owned session, and all write failures still reach idle retention below.
            quarantinedOwnerModelCommands.add(owned)
            try {
                owned.quarantine()
                try {
                    val evidence = owned.evidence(status, failureCode ?: "owner_model_cleanup_unverified_retained")
                    outputParent?.verify(); emit(evidence, out?.toString())
                } catch (_: Throwable) {
                    try { writeJsonLine(buildJsonObject {
                        put("state", "cleanup_unverified_quarantine"); put("ownerState", state.toString())
                        put("failureCode", "owner_model_cleanup_evidence_write_failed"); put("releaseQualified", false)
                    }, System.out) } catch (_: Throwable) { /* Output cannot release retained ownership. */ }
                }
            } finally {
                // No polling, process guess, signal, retry, Workbench.close or replacement clock.
                // Interrupts do not authorize cleanup retry or normal owner-process exit.
                val retained = CountDownLatch(1)
                while (true) { try { retained.await() } catch (_: InterruptedException) { /* Stay idle and retain custody. */ } }
            }
        }
        try { owned.close() }
        catch (failure: Throwable) { failureCode = failureCode ?: ownerModelCommandFailure(failure) }
        outputParent?.verify(); emit(owned.evidence(status, failureCode), out?.toString())
    } else {
        outputParent?.verify()
        emit(buildJsonObject {
        put("contractVersion", PREVIEW_CONTRACT); put("state", "failed_unaccepted")
        put("failureCode", failureCode ?: "owner_model_operation_failed"); put("ownerState", state.toString())
        put("originalDeadlineIdentity", originalDeadline.identity); put("originalDeadlineMillis", originalDeadline.durationMillis)
        put("parentAccepted", false); put("releaseQualified", false); put("usefulComplete", false)
        }, out?.toString())
    }
    return if (failureCode == null && status != null) 2 else 3 // NEVER useful-complete/qualified exit 0.
}

private fun ownerModelCommandFailure(failure: Throwable): String = when (failure) {
    is CliProblem -> failure.code
    is KernelException -> failure.code
    is SnapshotException -> failure.code
    is ProjectModelException -> failure.code
    is KotlinJvmModelException -> failure.code
    is KotlinJvmRuntimeException -> failure.code
    is ObservedModelPlanUnavailable -> failure.code
    is InterruptedException -> "owner_model_interrupted"
    else -> "owner_model_operation_failed"
}

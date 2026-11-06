package dev.ktchecker.cli

import dev.ktchecker.compiler.CompilerSnapshot
import dev.ktchecker.compiler.KotlinJvmModelException
import dev.ktchecker.compiler.KotlinJvmRuntimeException
import dev.ktchecker.compiler.KotlinJvmToolchain
import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.*
import dev.ktchecker.projectmodel.*
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import dev.ktchecker.snapshot.*
import dev.ktchecker.workerprotocol.CompilerModelWire as ModelWire
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

internal const val OWNER_MODEL_ORIGINAL_MILLIS = 120_000L
// A retained owner is never overwritten/discarded or a restart/recovery registry.
private val quarantinedOwnerModelCommands = Collections.synchronizedSet(mutableSetOf<OwnerModelCommandSession>())
private val ownerModelQuarantineAnchorStarted = AtomicBoolean()
// Initialize the never-released idle primitive BEFORE any owner can enter an application.
// Neither the fallback nor the newly started anchor allocates its lifetime primitive.
private val ownerModelQuarantineIdle = CountDownLatch(1)
private fun idleRetainingOwnerModelCommands(): Nothing {
    while (true) { try { ownerModelQuarantineIdle.await() } catch (_: Throwable) { /* Idle only; no cleanup retry/exit. */ } }
}
private fun startOwnerModelQuarantineAnchor() {
    if (ownerModelQuarantineAnchorStarted.compareAndSet(false, true)) {
        Thread({ idleRetainingOwnerModelCommands() }, "ktchecker-owner-model-quarantine").apply {
            isDaemon = false // In-process adapter failure cannot silently drop its last owner.
        }.start()
    }
}

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
    val terminal: Boolean = parent.slots.isNotEmpty() && parent.slots.size == this.children.size &&
        parent.slots.zip(this.children).all { (slot, child) ->
        slot.reviewId == child.reviewId && child.clientId == parent.clientId && child.sourceSnapshotId == parent.sourceSnapshotId && (
            (child.cancelled && child.state == "cancelled") ||
            (slot.state == "unavailable" && child.state == "incomplete" && child.reasons.isNotEmpty()) ||
            (child.result != null && child.state in setOf("complete", "incomplete", "cancelled")))
    }
}

/** Host-facing DATA plus a nonserialized capability retained by the exact same owner service. */
internal class OwnerModelNominalSubmission internal constructor(
    val data: JsonObject,
    val deliveryHandle: OwnerModelDeliveryHandle,
)

/**
 * Same Workbench/registration/snapshot ledger for a bounded patch/fix lineage. Each patch has its
 * own immutable original120s, observed invocation, run/output directories, intent, import, worker
 * generation and whole D/P/K/C/E custody. Only preselected artifact bytes/private Gradle metadata
 * persist. The per-owner gate never holds a monitor across IO, export, retire or kernel callbacks.
 *
 * No ordinary RPC/model DTO creates this service or a delivery handle. A trusted adapter may use
 * broker/registration for actual delivery separately; this service never ACKs or observes a model.
 */
internal class OwnerModelCommandSession private constructor(
    private val statePin: OwnerModelRootPin,
    private val commandPin: OwnerModelRootPin,
    private val runsPin: OwnerModelRootPin,
    private val firstRun: OwnerModelRootPin,
    private val firstClock: OwnerModelCommandClock,
    private val selected: OwnerModelInvocationSelection,
    private val gradle: OwnerModelTreePin,
    private val firstCache: OwnerModelCacheSeed,
    private val workbench: Workbench,
    private val runtime: OwnerLiveModelRuntime,
    val registration: ClientRegistration,
    private val initialDocument: OwnerModelCommandDocument?,
) : AutoCloseable {
    val profile: String get() = OWNER_MODEL_LINEAGE_PROFILE
    val broker: KernelService get() = workbench.kernel
    // Compatibility audit accessor: first entry's ORIGINAL clock, never newest/remaining time.
    val originalDeadline: OwnedApplicationDeadline get() = firstClock.originalDeadline
    private val gate = OwnerModelLineageGate()
    private val historyLock = Any()
    private val attempts = mutableListOf<OwnerModelCommandAttempt>()
    private val admissions = OwnerModelExactHandles<OwnerModelPlanAdmission, OwnerModelCommandAttempt>()
    private val handles = OwnerModelExactHandles<OwnerModelDeliveryHandle, OwnerModelCommandAttempt>()
    private val closed = AtomicBoolean()
    private val quarantined = AtomicBoolean()
    private val unknownHistory = AtomicBoolean()
    private val compilerPolicy = OwnerModelCompilerPolicy(workbench, registration, runtime.provider.supportedRuleIds, commandPin, firstClock)
    val isQuarantined: Boolean get() = quarantined.get()
    private var lastCache = firstCache
    // Allocate this protected callsite and its callbacks during owner construction, BEFORE any
    // source/export/application attempt. quarantine() itself does not allocate callback arguments
    // or perform an unguarded initial retain that can bypass the lifetime fallback.
    private val lifetime = OwnerModelLifetimeQuarantine(
        fence = { quarantined.set(true); gate.stop() },
        retain = { quarantinedOwnerModelCommands.add(this) },
        deny = { firstClock.deny(); retainedAttempts().forEach { it.quarantine() } },
        startAnchor = ::startOwnerModelQuarantineAnchor,
        idle = ::idleRetainingOwnerModelCommands,
    )

    /** Bridge DATA binding only: compares the ACTUAL copied installation and owner image before
     * explicit policy/review. It returns no installation, execution permit, model or receipt. */
    internal fun bindTransportInstallation(transport: OwnerModelTransportPreparedSelection) {
        ownerModelRequire(gate.enteredCount == 0 && !closed.get() && !quarantined.get() &&
            selected == transport.invocation, "owner_transport_installation_binding_unavailable")
        firstClock.checkpoint(); workbench.verifyOwnerCode()
        ownerModelRequire(workbench.ownerCodeDigest == transport.selection.ownerCodeImageDigest,
            "owner_transport_owner_image_changed")
        transport.verifyInitialCopies(runtime.installation, firstClock::checkpoint)
        firstClock.checkpoint()
    }

    /** Explicit trusted owner/config surface, NOT an effect of review/submit/authorize-execution. */
    fun configureCompilerPolicy(selection: OwnerModelCompilerPolicySelection): JsonObject {
        ownerModelRequire(!closed.get() && !quarantined.get(), "owner_model_command_unavailable")
        val operation = gate.enterPolicy()
        var failed = true
        try {
            // The helper freezes the caller's whole explicit choice BEFORE its first IO.
            val actual = compilerPolicy.configure(selection)
            failed = false
            return actual
        } catch (failure: Throwable) {
            if (compilerPolicy.historyUncertain) { unknownHistory.set(true); quarantine() }
            throw failure
        } finally { gate.leave(operation, failed) }
    }

    /** Legacy single-document entry is the first patch of this SAME service, not another route. */
    fun submitCompletedPatch(): OwnerModelPlanAdmission {
        val document = initialDocument ?: throw CliProblem("owner_model_initial_document_unavailable", "Use the complete typed patch entry.")
        val patch = OwnerModelCompletedPatch(document.patchId, document.taskId, 1L, registration.binding, null, 0L,
            listOf(OwnerModelPatchRoot("project", "")), document.files.sortedBy { it.path }.map { file ->
                OwnerModelPatchFile("project", file.path, file.contentBase64 ?: Base64.getEncoder()
                    .encodeToString(requireNotNull(file.content).toByteArray(Charsets.UTF_8)), file.executable)
            })
        return submit(patch, selected, null, SubmitPlanRequest(document.patchId, document.idempotencyKey, document.planRef))
    }

    /** Exact host contract. Phase views deny work; only the fresh per-patch nominal clock grants it. */
    fun submitCompletedPatch(patch: OwnerModelCompletedPatch, selection: OwnerModelInvocationSelection,
                             clockScope: OwnerModelClockScope): OwnerModelNominalSubmission {
        val accepted = submit(patch, selection, clockScope)
        return try {
            val attempt = admissions.exact(accepted)
            OwnerModelNominalSubmission(attempt.admissionData(accepted), accepted.deliveryHandle)
        } catch (failure: Throwable) {
            unknownHistory.set(true); quarantine(); throw failure
        }
    }

    private fun submit(patch: OwnerModelCompletedPatch, selection: OwnerModelInvocationSelection,
                       scope: OwnerModelClockScope?, request: SubmitPlanRequest? = null): OwnerModelPlanAdmission {
        // First owner installation already spent this first120s. Later genuinely new patches get
        // a NEW original before copy/validation/retirement/capture. No old attempt is renewed.
        val clock = if (gate.enteredCount == 0) firstClock else
            OwnerModelCommandClock(OwnedApplicationDeadline.afterMillis(OWNER_MODEL_ORIGINAL_MILLIS), scope)
        ownerModelRequire(!closed.get() && !quarantined.get(), "owner_model_command_unavailable")
        val operation = gate.enter(patch.patchId, patch.ordinal, clock, scope)
        var retainedAttempt: OwnerModelCommandAttempt? = null
        var failed = true
        var registeringHandle = false
        try {
            val previous = synchronized(historyLock) { attempts.lastOrNull() }
            val attempt = OwnerModelCommandAttempt(patch.patchId, patch.taskId, patch.ordinal, clock, workbench, runtime,
                registration, statePin, previous, compilerPolicy)
            retainedAttempt = attempt
            synchronized(historyLock) { attempts.add(attempt) } // Failed entries are retained too.
            attempt.freeze(patch) // Copy the ENTIRE caller patch before owner-root/registration IO.
            if (scope != null) clock.attachOriginalPhases(scope)
            clock.checkpoint(); verifyRoots()
            ownerModelRequire(workbench.registration(registration.binding.clientId) == registration,
                "owner_model_registration_changed")
            ownerModelRequire(freezeSelection(selection, clock) == selected, "owner_model_lineage_selection_changed")
            compilerPolicy.verifyCurrent(clock::checkpoint) // READ ONLY. Never enable rules during a review.
            // This transition explicitly owns the one retirement. Status alone never means CLOSED.
            if (previous != null) {
                ownerModelRequire(previous.admission != null && previous.model != null && previous.snapshotCommit != null,
                    "owner_model_previous_history_unavailable")
                previous.retireForNextPatch()
                clock.checkpoint()
            }
            val run = if (previous == null) firstRun else ownerModelFreshDirectory(runsPin.path.resolve(patch.ordinal.toString()))
            val cache = if (previous == null) firstCache else lastCache.nextUse(run.path, clock, runtime.installation)
            lastCache = cache
            clock.checkpoint()
            val accepted = attempt.submit(run, cache, gradle, request)
            registeringHandle = true
            admissions.retain(accepted, attempt); handles.retain(accepted.deliveryHandle, attempt)
            registeringHandle = false
            failed = false
            return accepted
        } catch (failure: Throwable) {
            retainedAttempt?.failed(ownerModelCommandFailure(failure))
            if (registeringHandle) unknownHistory.set(true)
            if (needsQuarantine) quarantine()
            throw failure
        } finally { gate.leave(operation, failed) }
    }

    /** Exact object identity map, including old admissions. JSON/digest equality cannot look up it. */
    fun status(handle: OwnerModelPlanAdmission): OwnerModelPlanStatus = audit {
        val attempt = admissions.exact(handle)
        attempt.status(handle)
    }
    fun status(handle: OwnerModelDeliveryHandle): OwnerModelPlanStatus = audit {
        val attempt = handles.exact(handle)
        ownerModelRequire(handle.broker === broker && handle.registration === registration, "owner_model_delivery_owner_mismatch")
        attempt.status(requireNotNull(attempt.admission))
    }
    fun await(handle: OwnerModelPlanAdmission): OwnerModelPlanStatus = audit {
        val attempt = admissions.exact(handle)
        attempt.await(handle)
    }
    private fun <T> audit(block: () -> T): T {
        ownerModelRequire(!closed.get(), "owner_model_command_closed")
        return try { verifyRoots(); block() }
        catch (failure: Throwable) {
            // An unknown actual history read may not be skipped to open a new patch/generation.
            unknownHistory.set(true); quarantine(); throw failure
        }
    }

    /** Explicit owner close preparation only. Never retry a retirement already requested. */
    fun retireOwnedGeneration() {
        val operation = gate.enterMaintenance()
        var failed = true
        try {
            ownerModelRequire(!quarantined.get() && !closed.get(), "owner_model_retirement_not_available")
            retainedAttempts().forEach { if (!it.retirementWasRequested) it.retireOwnedGeneration() }
            if (needsQuarantine) quarantine()
            failed = false
        } catch (failure: Throwable) { if (needsQuarantine) quarantine(); throw failure }
        finally { gate.leave(operation, failed) }
    }
    private val needsQuarantine: Boolean get() = unknownHistory.get() || compilerPolicy.historyUncertain || retainedAttempts().any { it.needsQuarantine }
    val safeToClose: Boolean get() = gate.idle && !quarantined.get() && !unknownHistory.get() &&
        !compilerPolicy.historyUncertain && retainedAttempts().all { it.safeToClose }
    fun quarantine() = lifetime.enter()
    override fun close() {
        val operation = gate.enterMaintenance()
        try {
            if (quarantined.get() || unknownHistory.get() || compilerPolicy.historyUncertain || retainedAttempts().any { !it.safeToClose }) {
                quarantine()
                throw CliProblem("owner_model_cleanup_unverified_retained", "The exact owner is retained; close never retires or drops live custody.")
            }
            if (closed.compareAndSet(false, true)) {
                try { workbench.close() }
                catch (failure: Throwable) { unknownHistory.set(true); quarantine(); throw failure }
            }
        } finally { gate.leave(operation, failed = true) }
    }

    /** Whole cumulative evidence, not a fresh-state snapshot that forgets failed/removed contexts. */
    fun evidence(status: OwnerModelPlanStatus? = null, failureCode: String? = null): JsonObject {
        val retained = retainedAttempts()
        val latest = retained.lastOrNull()?.let { if (status == null) it.evidence() else it.evidence(status) }
        val effectiveFailure = failureCode?.let(::JsonPrimitive) ?: latest?.get("failureCode") ?: JsonNull
        return buildJsonObject {
            latest?.forEach { (key, value) -> put(key, value) }
            if (latest == null) {
                put("originalDeadlineIdentity", originalDeadline.identity); put("originalDeadlineMillis", originalDeadline.durationMillis)
                put("parentAccepted", false); put("parentSubmissionAttempted", false)
            }
            put("contractVersion", PREVIEW_CONTRACT); put("schema", "ktchecker/owner-observed-model-command/2")
            put("profile", profile); put("binding", ContractJson.encodeToJsonElement(registration.binding))
            put("state", if (quarantined.get()) "cleanup_or_history_unverified_quarantine"
                else if (effectiveFailure != JsonNull) "failed_unaccepted_or_incomplete"
                else if (status?.terminal == true) "observed_preview_terminal" else "observed_preview_pending")
            put("ownerState", statePin.path.toString()); put("safeToClose", safeToClose)
            put("failureCode", effectiveFailure)
            put("unknownHistory", unknownHistory.get())
            put("ownerCompilerPolicy", compilerPolicy.evidence())
            put("attemptHistory", JsonArray(retained.map { it.evidence() }))
            put("retainedAttemptCount", retained.size); put("maximumRetainedPatches", OWNER_MODEL_MAX_PATCHES)
            put("patchFixLineageSupported", true); put("restartLineageRecoverySupported", false)
            put("supportedObservedDomain", "whole_declared_main_test_only_unsupported_shapes_unaccepted")
            put("assurance", OBSERVED_EXPORT_PROFILE); put("configurationInputClosureProven", false)
            put("mandatoryGap", CONFIGURATION_INPUT_CLOSURE_UNPROVEN)
            put("fullResourceCleanupReceiptAvailable", false); put("hostInsertionOrObservationPerformed", false)
            put("fullProject", false); put("releaseQualified", false); put("usefulComplete", false); put("finalAnswerAuthorized", false)
        }
    }
    private fun retainedAttempts(): List<OwnerModelCommandAttempt> = synchronized(historyLock) { attempts.toList() }
    private fun verifyRoots() { statePin.verify(); commandPin.verify(); runsPin.verify() }

    companion object {
        /** Explicit command factory. Caller starts its ORIGINAL clock BEFORE reading the request. */
        fun open(request: OwnerModelCommandDocument, stateRoot: Path, javaHome: Path, authorizeExecution: Boolean,
                 originalDeadline: OwnedApplicationDeadline): OwnerModelCommandSession {
            val clock = OwnerModelCommandClock(originalDeadline)
            clock.checkpoint()
            val encoded = ownerModelStrictBytes(ContractJson.encodeToJsonElement(request))
            ownerModelRequire(encoded.size in 1..OWNER_MODEL_REQUEST_BYTES, "owner_model_request_limit")
            val document = ContractJson.decodeFromString<OwnerModelCommandDocument>(strictJsonDocument(encoded, "owner_model_request_invalid"))
            validateOwnerModelDocument(document, originalDeadline)
            val selection = OwnerModelInvocationSelection(document.gradleHome, document.gradleCacheSeed, javaHome.toString(),
                document.compilerArtifactPaths, authorizeExecution)
            return create(OwnerModelLineageRegistration(document.workspaceId, document.worktreeId, document.clientId,
                document.sessionId, document.lineageId), selection, stateRoot, clock, document, encoded)
        }

        /**
         * Owner/host factory, before the first completed-patch capture. Construction/install time
         * belongs to the first original120s; submit cannot reset it. Initial phase views, when
         * already available, also cover construction. No existing state/registration is reopened.
         */
        fun openLineage(registration: OwnerModelLineageRegistration, selection: OwnerModelInvocationSelection,
                        stateRoot: Path, originalOverall: OwnerModelPhaseFence? = null,
                        originalPhases: OwnerModelClockScope? = null,
                        invocationRoot: Path = stateRoot.resolve("model-command/runs"),
                        originalExportFence: OwnerModelPhaseFence? = null): OwnerModelCommandSession {
            val deadline = OwnedApplicationDeadline.afterMillis(OWNER_MODEL_ORIGINAL_MILLIS) // FIRST statement.
            val clock = OwnerModelCommandClock(deadline, originalPhases, originalOverall ?: originalPhases?.overall,
                originalExportFence ?: originalPhases?.originalExport)
            ownerModelRequire(invocationRoot == stateRoot.resolve("model-command/runs"), "owner_model_invocation_root_mismatch")
            return create(registration, selection, stateRoot, clock, null, null)
        }

        private fun create(request: OwnerModelLineageRegistration, selection: OwnerModelInvocationSelection,
                           stateRoot: Path, clock: OwnerModelCommandClock, document: OwnerModelCommandDocument?,
                           documentBytes: ByteArray?): OwnerModelCommandSession {
            clock.checkpoint()
            ownerModelRequire(quarantinedOwnerModelCommands.isEmpty(), "owner_model_quarantine_retained")
            val bytes = ownerModelStrictBytes(ContractJson.encodeToJsonElement(request))
            ownerModelRequire(bytes.size in 1..OWNER_MODEL_REQUEST_BYTES, "owner_model_request_limit")
            val registrationRequest = ContractJson.decodeFromString<OwnerModelLineageRegistration>(
                strictJsonDocument(bytes, "owner_model_request_invalid"))
            listOf(registrationRequest.workspaceId, registrationRequest.worktreeId, registrationRequest.clientId,
                registrationRequest.sessionId, registrationRequest.lineageId).forEach(::ownerModelIdentity)
            val chosen = freezeSelection(selection, clock)
            val selectedState = ownerModelPath(stateRoot, false)
            val selectedJava = ownerModelPath(Path.of(chosen.compilerJavaHome))
            val selectedGradle = ownerModelPath(Path.of(chosen.gradleHome))
            val selectedSeed = ownerModelPath(Path.of(chosen.gradleCacheSeed))
            val selectedRoots = listOf(selectedState, selectedJava, selectedGradle, selectedSeed)
            ownerModelRequire(selectedRoots.indices.all { i -> selectedRoots.indices.all { j ->
                i == j || !selectedRoots[i].startsWith(selectedRoots[j])
            } }, "owner_model_selected_roots_overlap")
            ownerModelRequire(!Files.exists(selectedState, NOFOLLOW_LINKS), "owner_model_fresh_state_required")
            val statePin = ownerModelFreshDirectory(selectedState)
            val state = PrivateState(selectedState)
            val command = ownerModelFreshDirectory(state.root.resolve("model-command"))
            val installationRoot = ownerModelFreshDirectory(command.path.resolve("artifacts"))
            val runs = ownerModelFreshDirectory(command.path.resolve("runs"))
            val firstRun = ownerModelFreshDirectory(runs.path.resolve("1"))
            val bootstrap = documentBytes ?: ownerModelStrictBytes(buildJsonObject {
                put("registration", ContractJson.encodeToJsonElement(registrationRequest))
                put("selection", ContractJson.encodeToJsonElement(chosen))
            })
            ownerModelWrite(command.path.resolve("owner-request.json"), bootstrap, OWNER_MODEL_REQUEST_BYTES, clock::checkpoint)
            val cache = OwnerModelCacheSeed(selectedSeed, installationRoot.path, firstRun.path, clock)
            val artifacts = cache.selectCompiler(chosen.compilerArtifactPaths)
            clock.checkpoint()
            val installation = KotlinJvmToolchain(selectedJava.resolve("bin/java"), artifacts)
            clock.checkpoint(); cache.bindInstallation(installation, clock.originalDeadline)
            val gradle = OwnerModelTreePin.capture(selectedGradle, 50_000, CompilerSnapshot.MAX_TOTAL_ARTIFACT_BYTES,
                checkpoint = clock::checkpoint)
            val home = Workbench.distributionHome() ?: throw CliProblem("owner_model_distribution_unavailable",
                "Use the installed distribution's fixed model worker.")
            val bridge = OwnerModelFilePin.capture(ownerModelPath(home.resolve(OWNER_MODEL_WORKER)), ModelWire.MAX_BRIDGE_BYTES,
                clock::checkpoint)
            val runtime = OwnerLiveModelRuntime(state.ownerId, installation, bridge.digest, OWNER_MODEL_ORIGINAL_MILLIS)
            var workbench: Workbench? = null
            try {
                val selected = Workbench(WorkbenchOptions(state = state.root, workerCount = 1,
                    compilerJavaHome = selectedJava, liveModelRuntime = runtime))
                workbench = selected
                clock.checkpoint()
                val registration = selected.register(registrationRequest.workspaceId, registrationRequest.worktreeId,
                    registrationRequest.clientId, registrationRequest.sessionId, registrationRequest.lineageId)
                val identity = SnapshotIdentity(registration.binding.ownerId, registration.binding.workspaceId, registration.binding.worktreeId)
                val head = selected.snapshots.registerWorktree(identity)
                ownerModelRequire(head == WorktreeState(identity, listOf(SnapshotRoot()), 0L, null, 0L,
                    LiveState.UNVERIFIED, null, 0), "owner_model_fresh_snapshot_lineage_required")
                bridge.verify(clock::checkpoint)
                return OwnerModelCommandSession(statePin, command, runs, firstRun, clock, chosen, gradle, cache,
                    selected, runtime, registration, document)
            } catch (failure: Throwable) {
                // This factory NEVER enters an export/application or mutates a source snapshot.
                workbench?.close()
                throw failure
            }
        }
        private fun freezeSelection(input: OwnerModelInvocationSelection, clock: OwnerModelCommandClock): OwnerModelInvocationSelection {
            clock.checkpoint()
            val bytes = ownerModelStrictBytes(ContractJson.encodeToJsonElement(input))
            ownerModelRequire(bytes.size in 1..OWNER_MODEL_REQUEST_BYTES, "owner_model_selection_limit")
            val value = ContractJson.decodeFromString<OwnerModelInvocationSelection>(strictJsonDocument(bytes, "owner_model_selection_invalid"))
            ownerModelRequire(value.authorizeExecution && value.compilerArtifactPaths.size in 1..ModelWire.MAX_ARTIFACTS &&
                value.compilerArtifactPaths.distinct().size == value.compilerArtifactPaths.size,
                "owner_model_explicit_execution_consent_required")
            return value.copy(compilerArtifactPaths = Collections.unmodifiableList(value.compilerArtifactPaths.toList()))
        }
    }
}

private fun validateOwnerModelDocument(document: OwnerModelCommandDocument, deadline: OwnedApplicationDeadline) {
    ownerModelCheckpoint(deadline)
    ownerModelRequire(document.schemaVersion == 1 && listOf(document.workspaceId, document.worktreeId, document.clientId,
        document.sessionId, document.lineageId, document.taskId, document.patchId, document.idempotencyKey, document.planRef).all {
        it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= 256 && it.none { c -> c.isISOControl() }
    }, "owner_model_request_identity_invalid")
    ownerModelRequire(document.files.size in 1..ModelWire.MAX_SNAPSHOT_FILES &&
        document.files.map { it.path }.distinct().size == document.files.size, "owner_model_snapshot_limit_or_duplicate")
    var bytes = 0L
    document.files.forEach { file ->
        ownerModelCheckpoint(deadline)
        ownerModelLogicalPath(file.path)
        ownerModelRequire((file.content == null) != (file.contentBase64 == null), "owner_model_snapshot_file_invalid")
        val content = file.content?.toByteArray(Charsets.UTF_8) ?: try { Base64.getDecoder().decode(requireNotNull(file.contentBase64)) }
            catch (_: IllegalArgumentException) { throw CliProblem("owner_model_snapshot_file_invalid", "Invalid immutable file encoding.") }
        ownerModelRequire(file.contentBase64 == null || Base64.getEncoder().encodeToString(content) == file.contentBase64,
            "owner_model_snapshot_encoding_or_limit")
        ownerModelRequire(content.size <= ModelWire.MAX_SOURCE_BYTES && content.size.toLong() <= ModelWire.MAX_SNAPSHOT_BYTES - bytes,
            "owner_model_snapshot_limit")
        bytes += content.size
    }
    val names = document.files.map { it.path }.toSet()
    ownerModelPortableInventory(names)
}

/**
 * Interim legacy CLI limitation: its review document has NO explicit owner-policy surface.
 * Reject BEFORE path reads/state/install/export instead of inferring rules from execution consent.
 * The executable typed-host route is openLineage -> configureCompilerPolicy -> completed patch.
 * A separate audited CLI owner/config operation remains a release gate, not an automatic default.
 */
internal fun runOwnerModelPlan(options: Arguments): Int {
    val originalDeadline = OwnedApplicationDeadline.afterMillis(OWNER_MODEL_ORIGINAL_MILLIS) // FIRST statement.
    ownerModelRequire(options.words == listOf("model-plan") && options.has("authorize-execution") &&
        listOf("compiler-dir", "detekt-artifacts", "ktlint-artifacts", "workers", "prepared", "wait-ms", "review", "cursor",
            "limit", "finding", "expected", "layer", "rule", "workspace", "worktree", "client", "session", "lineage", "id", "owner-action")
            .none(options::has), "owner_model_command_options_invalid")
    ownerModelCheckpoint(originalDeadline)
    throw CliProblem("owner_model_explicit_policy_setup_required",
        "Legacy model-plan has no owner-policy setup operation yet. Use the trusted typed owner host: " +
            "openLineage, explicitly configureCompilerPolicy, then submitCompletedPatch. " +
            "Execution consent never selects or enables compiler rules; no state or export was started.")
}

internal fun ownerModelCommandFailure(failure: Throwable): String = when (failure) {
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

package dev.ktchecker.cli

import dev.ktchecker.compiler.KotlinJvmToolchain
import dev.ktchecker.compiler.live.CompilerApplicationNamespace
import dev.ktchecker.compiler.live.model.*
import dev.ktchecker.config.*
import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.*
import dev.ktchecker.projectmodel.*
import dev.ktchecker.providers.runtime.*
import dev.ktchecker.snapshot.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable internal data class RealModelRunSelection(
    val schemaVersion: Int,
    val fixture: String,
    val runRoot: String,
    val ownerInputs: String,
    val modelWorkerBridge: String,
    val modelWorkerBridgeDigest: String,
    val modelWorkerBridgeBytes: Long,
    /** Actual owner-selected JVM launch, used by the companion, never supplied through model RPC. */
    val ownerJava: String,
    val ownerClasspath: String,
)

/** Deliberately opt-in TEST MAIN, never included in normal tests or public product CLI/RPC.
 * Failed/unknown attempts retain custody: no finally-close, retry, cancel, kill or restart path.
 * The sole normal exit follows an independently VERIFIED explicit retirement AND later stdio EOF.
 */
object RealModelOwnerMain {
    const val GENERATION_MILLIS = 900_000L
    const val REVIEW_MILLIS = 300_000L
    @JvmStatic fun main(args: Array<String>) {
        val entered = System.nanoTime()
        val originalGenerationDeadline = OwnedApplicationDeadline.afterMillis(GENERATION_MILLIS)
        var owner: RealModelOwner? = null
        var evidence: Path? = null
        try {
            realExpect(args.size == 5 && args[0] == "--selection" && args[2] == "--selection-sha256" &&
                args[4] == "--approve-real-no-test-feasibility", "real_model_explicit_selection_required")
            val selectedPath = realPath(args[1]); realDigest(args[3])
            val selectedBytes = realRead(selectedPath, 64 * 1024)
            realExpect(Digests.sha256(selectedBytes) == args[3], "real_model_selection_changed")
            val selection = ContractJson.decodeFromString<RealModelRunSelection>(strictJsonDocument(selectedBytes, "real_model_selection_json"))
            realExpect(selection.schemaVersion == 1 && selection.fixture == RealModelFixture.CASE,
                "real_model_selection_schema")
            // Current catalog contract is checked BEFORE startup, generation launch or owner mutation.
            realExpect(OwnerFullCatalog1665.accepts(Catalog().ids.sorted()), "real_model_catalog_contract_changed")
            val run = realPath(selection.runRoot)
            realKey(run, true)
            val privateOwner = run.resolve("owner")
            realExpect(!Files.exists(privateOwner, NOFOLLOW_LINKS), "real_model_owner_attempt_not_fresh")
            realDirectory(privateOwner)
            val receipts = privateOwner.resolve("evidence"); realDirectory(receipts); evidence = receipts
            val freeBytes = Files.getFileStore(run).usableSpace
            realWriteJson(receipts.resolve("start.json"), buildJsonObject {
                put("state", "initializing"); put("fixture", selection.fixture); put("selectionDigest", args[3])
                put("ownerEntryMonotonicNanos", entered); put("capturedAt", Instant.now().toString())
                put("originalGenerationDeadlineIdentity", originalGenerationDeadline.identity)
                put("originalGenerationDeadlineMillis", originalGenerationDeadline.durationMillis)
                put("originalReviewBudgetMillis", REVIEW_MILLIS); put("usableBytesBeforeSeedPreparation", freeBytes)
                put("seedCopyLimitBytes", 512L * 1024 * 1024); put("runBoundaryLimitBytes", 1024L * 1024 * 1024)
                put("runBoundaryEntryLimit", 40_000); put("continuousFilesystemQuota", false)
                put("qualified", false); put("cpuMillis", JsonNull); put("peakRssBytes", JsonNull)
            })
            realExpect(freeBytes >= 1536L * 1024 * 1024, "real_model_disk_headroom_unavailable")
            val startup = RealModelStartup.capture()
            realWriteJson(receipts.resolve("startup-image.json"), startup.evidence())
            val inputs = RealModelOwnerInputs.load(realPath(selection.ownerInputs))
            realExpect(realPath(selection.ownerJava) == inputs.javaHome.resolve("bin/java") &&
                realPath(System.getProperty("java.home")) == inputs.javaHome, "real_model_same_selected_jdk_required")
            val home = inputs.deriveExecutionHome(privateOwner)
            realExpect(!originalGenerationDeadline.isExpired(), "real_model_generation_original_deadline")
            val installation = KotlinJvmToolchain(inputs.javaHome.resolve("bin/java"), inputs.compilerClasspath())
            val state = PrivateState(privateOwner.resolve("state"))
            val bridge = RealModelFilePin.capture(realPath(selection.modelWorkerBridge), selection.modelWorkerBridgeDigest,
                selection.modelWorkerBridgeBytes)
            val runtime = OwnerLiveModelRuntime(state.ownerId, installation, bridge.digest, REVIEW_MILLIS,
                maximumGenerations = 1, maximumActiveAttempts = 2, maximumRetainedAttempts = 2)
            val namespace = CompilerApplicationNamespace(state.ownerId, RealModelFixture.CASE + "-workspace", RealModelFixture.CASE + "-worktree")
            val generation = RealModelGeneration(runtime, namespace, privateOwner.resolve("generation"), bridge, startup,
                args[3], originalGenerationDeadline, run)
            val custody = RealModelCustody({ generation }, state.root.resolve("broker"), receipts)
            val opened = generation.open(custody)
            realWriteJson(receipts.resolve("generation-open.json"), generation.evidence(opened))
            realExpect(opened.state == "ready" && opened.opened is ModelCompilerApplicationOpenResult.Ready,
                "real_model_generation_unavailable")
            val workbench = Workbench(WorkbenchOptions(state = state.root, workerCount = 1, liveModelRuntime = runtime))
            owner = RealModelOwner(selection, args[3], privateOwner, receipts, inputs, home, startup, generation, custody, workbench, namespace)
            realWriteJson(receipts.resolve("owner-ready.json"), buildJsonObject {
                put("state", "owner_ready"); put("entryToReadyMillis", (System.nanoTime() - entered) / 1_000_000)
                put("sameSelectedInstallationObject", true); put("compilerRosterCount", installation.compilerClasspath.size)
                put("compilerRosterDigest", Digests.sha256(Digests.canonical(ContractJson.encodeToJsonElement(installation.compilerClasspath))))
                put("ownerInputsDigest", inputs.ownerManifestDigest); put("runtimeInputsDigest", inputs.runtimeDigest)
                put("executionHomeDigest", home.digest); put("qualified", false)
                put("catalogContract", OwnerFullCatalog1665.ID); put("catalogRuleIdsDigest", OwnerFullCatalog1665.RULE_IDS_DIGEST)
            })
            RpcServer(owner).serve(System.`in`, System.out)
            // Closing the transport is NEVER the retirement operation. It happens afterwards only.
            if (owner.retirementVerified) {
                realWriteJson(receipts.resolve("transport-eof-after-verified-retirement.json"), buildJsonObject {
                    put("state", "owner_eof_after_independent_verified_retirement")
                    put("retirementVerifiedBeforeEof", true); put("generationId", generation.request.launch.identity().generation())
                })
                workbench.close()
                return
            }
            owner.retainFailure("real_model_owner_eof_before_verified_retirement")
        } catch (error: Throwable) {
            val code = realModelFailureCode(error)
            try {
                if (owner != null) owner.retainFailure(code)
                else evidence?.let { root -> realWriteJson(root.resolve("initialization-unavailable.json"), buildJsonObject {
                    put("state", "unavailable_retained"); put("reason", code); put("custodyDisposition", "not_independently_verified")
                    put("qualified", false); put("retryPermitted", false)
                }) }
            } catch (_: Throwable) { /* No replacement file, retry, signaling or custody inference. */ }
        }
        // No process inspection, implicit Workbench.close/cancel/retire, or JVM System.exit here.
        // Original runtime deadlines keep their normal semantics; this wait creates no new clock.
        val retained = CountDownLatch(1)
        while (true) { try { retained.await() } catch (_: InterruptedException) { /* retain; never use interruption as cleanup evidence */ } }
    }
}

private class RealModelPhase(val number: Int, val enteredNanos: Long, val enteredAt: String, val intent: OwnerModelIntent) {
    var model: VerifiedProjectModel? = null
    var commit: SnapshotCommit? = null
    var prepared: PreparedOwnerModelPlan? = null
    var accepted: ParentPlanStatus? = null
    var acceptanceReturnedNanos: Long? = null
    var firstActionableObservedNanos: Long? = null
    var completionObservedNanos: Long? = null
    var completeEvidence: JsonObject? = null
    var checked = false
}

private class RealModelOwner(
    private val selection: RealModelRunSelection,
    private val selectionDigest: String,
    private val root: Path,
    private val receipts: Path,
    private val inputs: RealModelOwnerInputs,
    private val home: RealModelExecutionHome,
    private val startup: RealModelStartup,
    private val generation: RealModelGeneration,
    private val custody: RealModelCustody,
    private val work: Workbench,
    private val namespace: CompilerApplicationNamespace,
) : RpcHandler {
    private val phases = mutableListOf<RealModelPhase>()
    private val attempted = mutableSetOf<Int>()
    private var failure: String? = null
    private var retireAttempted = false
    var retirementVerified = false
        private set
    private val registration = work.register(namespace.workspaceId, namespace.worktreeId, "real-no-test-feasibility-client",
        "real-no-test-feasibility-session", "real-no-test-feasibility-lineage")
    init {
        realExpect(OwnerFullCatalog1665.accepts(Catalog().ids.sorted()), "real_model_catalog_contract_changed")
        val config = work.configs.capture(modelConfigBinding(registration.binding))
        work.mutateConfig(registration.binding.clientId, ConfigLayer.SHARED, config.revisionToken,
            ConfigAction.SetRuleEnabled(RealModelFixture.RULE, true))
    }
    override fun call(method: String, params: JsonObject): JsonObject {
        if (!method.startsWith("owner.")) return work.call(method, params)
        return try {
            when (method) {
                "owner.hello" -> {
                    requireKeys(params, emptySet())
                    buildJsonObject {
                        put("fixture", RealModelFixture.CASE); put("selectionDigest", selectionDigest)
                        put("registration", ContractJson.encodeToJsonElement(registration))
                        put("generation", generation.evidence()); put("fullRequiredRuleIds", JsonArray(Catalog().ids.sorted().map(::JsonPrimitive)))
                        put("catalogContract", OwnerFullCatalog1665.ID); put("catalogRuleIdsDigest", OwnerFullCatalog1665.RULE_IDS_DIGEST)
                        put("qualified", false)
                    }
                }
                "owner.phase.begin" -> {
                    requireKeys(params, setOf("phase")); val phase = params.decode<Int>("phase")
                    realExpect(failure == null && !retireAttempted && phase in 1..2 && phase == phases.size + 1 && phase !in attempted &&
                        phases.all { it.checked }, "real_model_phase_replayed_or_previous_unsettled")
                    attempted += phase // Failed starts cannot obtain a refreshed deadline on another RPC.
                    begin(phase)
                }
                "owner.phase.status" -> {
                    requireKeys(params, setOf("phase")); status(params.decode("phase"))
                }
                "owner.retire" -> {
                    requireKeys(params, emptySet())
                    realExpect(!retireAttempted && failure == null && phases.size == 2 && phases.all { it.checked },
                        "real_model_retirement_requires_two_settled_phases")
                    retireAttempted = true
                    realWriteJson(receipts.resolve("retirement-intent.json"), buildJsonObject {
                        put("state", "explicit_owner_retirement_requested"); put("generationId", generation.request.launch.identity().generation())
                        put("transportInputStillOpen", true); put("requestedAt", Instant.now().toString())
                    })
                    val retired = generation.runtime.retire(generation.request)
                    val actual = generation.runtime.status(generation.request)
                    realExpect(retired.generationId == actual.generationId && actual.state == "retired" &&
                        retired.retirement === actual.retirement && actual.retirement?.disposition == OwnedApplicationCleanupDisposition.CLOSED,
                        "real_model_independent_retirement_not_verified")
                    realTreeBoundary(realPath(selection.runRoot))
                    val receipt = buildJsonObject {
                        put("state", "explicit_retirement_verified"); put("generation", generation.evidence(actual))
                        put("verifiedBeforeTransportEof", true); put("verifiedAt", Instant.now().toString())
                        put("qualified", false); put("usefulClean", false)
                    }
                    realWriteJson(receipts.resolve("retirement-verified.json"), receipt)
                    retirementVerified = true
                    receipt
                }
                else -> throw CliProblem("unknown_method", "No such fixed owner operation.")
            }
        } catch (error: Throwable) {
            val code = realModelFailureCode(error); retainFailure(code)
            buildJsonObject { put("state", "unavailable_retained"); put("reason", code); put("retryPermitted", false); put("retirementVerified", retirementVerified) }
        }
    }
    private fun begin(number: Int): JsonObject {
        val entered = System.nanoTime(); val at = Instant.now().toString()
        // FIRST phase operation with IO: owner API captures its original clock before registration IO.
        val intent = work.beginModelReview(registration.binding.clientId,
            SubmitPlanRequest("no-test-feasibility-patch-$number", "no-test-feasibility-idempotency-$number", "no-test-feasibility-plan-$number"), true)
        val phase = RealModelPhase(number, entered, at, intent); phases += phase
        val deadline = requireNotNull(intent.originalDeadline)
        realExpect(deadline.durationMillis == RealModelOwnerMain.REVIEW_MILLIS, "real_model_review_clock_changed")
        realWriteJson(receipts.resolve("phase-$number-intent.json"), phaseTimes(phase))
        val previous = phases.getOrNull(number - 2)
        val oldCommit = previous?.commit
        val files = RealModelFixture.files(number)
        val changes = if (number == 1) files.map { (path, text) -> OverlayChange("put", path, text) }
            else listOf(OverlayChange("put", RealModelFixture.DECLARATIONS, RealModelFixture.AFTER_TEXT))
        checkpoint(phase)
        val commit = work.overlay(OverlayDocument(namespace.workspaceId, namespace.worktreeId, registration.binding.clientId,
            registration.binding.sessionId, intent.request.patchId, oldCommit?.snapshotId, oldCommit?.headRevision ?: 0,
            changes = changes, scope = SnapshotScope("NEW genuine no-test feasibility fixture; no ordinary main/test or full-project product claim")))
        phase.commit = commit
        val snapshot = commit.toContractManifest(); RealModelFixture.verifySnapshot(snapshot, number)
        val phaseRoot = root.resolve("phase-$number"); realDirectory(phaseRoot)
        val captured = work.snapshots.materialize(SnapshotIdentity(namespace.ownerId, namespace.workspaceId, namespace.worktreeId),
            commit.snapshotId, phaseRoot.resolve("source"))
        fun verifyCapture() {
            checkpoint(phase)
            RealModelFixture.verifySnapshot(captured.manifest.toContractManifest(), number)
            realExpect(work.snapshots.verifyMaterialization(captured).valid, "real_model_registered_capture_changed")
            checkpoint(phase)
        }
        val profile = GradleModelExporter.profile(inputs.gradleHome.resolve("bin/gradle"),
            requireNotNull(inputs.artifact(inputs.gradleHome.resolve("bin/gradle")).digest), inputs.javaHome, home.root)
        val exportInput = AnalysisInput(snapshot, captured.root.toString(),
            context = AnalysisContext(configurationDigest = intent.configuration.policyDigest), execution = profile,
            outputRoot = phaseRoot.resolve("export-output").toString())
        var completed: ProcessOutcome? = null
        val permit = ExecutionPermit(profile, snapshot.snapshotId, captured.root, exportInput.analysisKey(),
            verifySnapshot = { verifyCapture(); inputs.verifyRuntimeInputs(); startup.verify(); checkpoint(phase) },
            prepareOutput = { output ->
                checkpoint(phase); GradleModelExporter.prepareOutput(output); inputs.registerExporter(output, number)
                inputs.verifyRuntimeInputs(); startup.verify(); home.prepareForLaunch(number)
                realTreeBoundary(realPath(selection.runRoot)); checkpoint(phase)
            })
        val actual = IsolatedProcessRunner { outcome ->
            completed = outcome
            realWriteJson(receipts.resolve("phase-$number-export-process.json"), processEvidence(outcome))
            realWrite(receipts.resolve("phase-$number-private-export-stdout.txt"), outcome.stdout.toByteArray(Charsets.UTF_8), 8 * 1024 * 1024)
            realWrite(receipts.resolve("phase-$number-private-export-stderr.txt"), outcome.stderr.toByteArray(Charsets.UTF_8), 8 * 1024 * 1024)
        }.run(exportInput, ExactExecutionAuthority(permit), CancellationToken { deadline.isExpired() })
        realExpect(completed === actual, "real_model_actual_export_completion_missing")
        val raw = actual.report(GradleModelExporter.REPORT, 16 * 1024 * 1024)
        // Retain the ENTIRE actual report before any admission/fixture/layout decision.
        realWrite(receipts.resolve("phase-$number-raw-model-export.json"), raw)
        val request = ModelImportRequest(ModelNamespace(namespace.ownerId, namespace.workspaceId, namespace.worktreeId, registration.binding.lineageId),
            snapshot, captured.root.toString(), intent.configuration.policyDigest, Digests.sha256(raw), GradleModelExporter.scriptDigest(),
            "no-test-feasibility-export-$number")
        val output = requireNotNull(actual.outputDirectory)
        fun verifyExecuted() {
            checkpoint(phase); startup.verifyCompletion(actual)
            realExpect(completed === actual && request.snapshot == exportInput.snapshot && request.materializedRoot == exportInput.materializedRoot &&
                request.configurationDigest == exportInput.context.configurationDigest && request.exporterDigest == GradleModelExporter.scriptDigest() &&
                actual.successfulExecution && actual.exitCode == 0 && !actual.cancelled && !actual.timedOut && !actual.truncated && actual.reasons.isEmpty(),
                "real_model_exporter_invocation_unverified")
            realExpect(actual.command == listOf(profile.executable) + profile.arguments.map {
                it.replace("{snapshot}", captured.root.toString()).replace("{output}", output.toString())
            }, "real_model_exporter_command_changed")
            realExpect(Digests.sha256(actual.report(GradleModelExporter.REPORT, 16 * 1024 * 1024)) == request.exportDigest,
                "real_model_actual_export_report_changed")
            inputs.verifyRuntimeInputs(); verifyCapture(); checkpoint(phase)
        }
        verifyExecuted(); home.confirmCompletedExport(number)
        // Captured owner constants + actual invocation output, NEVER report-selected paths/task/JDK.
        val origin = ModelJvmArgumentOrigin(request, RealModelFixture.COMPILATION, RealModelFixture.TASK, ":", "main", output, inputs.javaHome)
        var observedGraph: GradleProjectExport? = null
        val ingress = ModelIngressPermit(request,
            Digests.sha256(Digests.canonical(JsonArray(listOf(inputs.runtimeDigest, home.digest, startup.image.digest).map(::JsonPrimitive)))),
            verifySnapshot = { verifyCapture(); inputs.verifyRuntimeInputs(); startup.verify(); checkpoint(phase) },
            verifyExecutedExport = ::verifyExecuted, verifyArtifact = inputs::verifyArtifact,
            verifyModelInputs = { graph ->
                verifyCapture(); inputs.verifyRuntimeInputs(); startup.verify()
                RealModelFixture.verifyGraph(snapshot, number, graph, inputs, generation.runtime.installation, output)
                inputs.verifyJvmProducerArtifacts(graph)
                observedGraph = graph; checkpoint(phase)
            }, verifyJvmArgumentOrigin = { compilation ->
                verifyExecuted()
                val graph = observedGraph ?: throw RealModelProblem("real_model_origin_graph_not_observed")
                inputs.verifyJvmProducerArtifacts(graph)
                if (compilation.id == RealModelFixture.COMPILATION) origin else null
            })
        val model = ProjectModelImporter(ExactModelIngressAuthority(ingress)).import(request, raw)
        phase.model = model
        realWriteJson(receipts.resolve("phase-$number-imported-model.json"), buildJsonObject {
            put("identity", ContractJson.encodeToJsonElement(model.identity)); put("certificate", model.ingressCertificate)
            put("request", ContractJson.encodeToJsonElement(request)); put("issues", ContractJson.encodeToJsonElement(model.issues))
            putJsonArray("contexts") { model.compilationIds.forEach { id -> add(buildJsonObject {
                val context = model.context(id, intent.configuration.policyDigest)
                put("compilationId", id); put("completeness", context.completeness.name)
                put("context", context.context?.let { ContractJson.encodeToJsonElement(it) } ?: JsonNull)
                put("issues", ContractJson.encodeToJsonElement(context.issues))
            }) } }
            put("qualified", false); put("typicalMainTestSupported", false); put("producedTreesFriendsReleaseGap", true)
        })
        checkpoint(phase)
        val prepared = work.prepareModelReview(intent, previous?.model, model, captured)
        phase.prepared = prepared
        realWriteJson(receipts.resolve("phase-$number-whole-parent-prepared.json"), buildJsonObject {
            put("plan", ContractJson.encodeToJsonElement(prepared.assembly.plan))
            put("preparationReasons", ContractJson.encodeToJsonElement(prepared.preparationReasons))
            put("qualified", false)
        })
        realExpect(prepared.assembly.plan.slots.isNotEmpty() && prepared.assembly.plan.slots.all {
            it.input.requiredRuleIds == Catalog().ids.sorted() && OwnerFullCatalog1665.accepts(it.input.requiredRuleIds)
        }, "real_model_full_parent_vector_changed")
        // Residual gaps retain the entire unavailable parent. They do not gain a live grant.
        if (prepared.preparationReasons.isEmpty() && model.issues.isEmpty() && model.compilationIds == listOf(RealModelFixture.COMPILATION)) {
            custody.beforeSubmit(number, prepared, model, deadline)
        }
        checkpoint(phase)
        val accepted = work.submitModelPlan(registration.client, prepared.request)
        phase.accepted = accepted; phase.acceptanceReturnedNanos = System.nanoTime()
        realWriteJson(receipts.resolve("phase-$number-parent-accepted.json"), ContractJson.encodeToJsonElement(accepted))
        return buildJsonObject {
            put("state", "parent_accepted"); put("phase", number); put("parent", ContractJson.encodeToJsonElement(accepted))
            put("timing", phaseTimes(phase)); put("qualified", false)
        }
    }
    private fun status(number: Int): JsonObject {
        realExpect(number in 1..phases.size && failure == null, "real_model_phase_unavailable")
        val phase = phases[number - 1]; val accepted = requireNotNull(phase.accepted)
        val parent = work.kernel.planStatus(registration.client, accepted.planId)
        val children = parent.slots.map { work.kernel.status(registration.client, it.reviewId) }
        if (phase.firstActionableObservedNanos == null && children.any { child -> child.result?.findings?.any { it.ruleId == RealModelFixture.RULE } == true }) {
            phase.firstActionableObservedNanos = System.nanoTime()
        }
        val terminal = children.isNotEmpty() && children.none { it.state in setOf("queued", "running") }
        if (terminal && !phase.checked && phase.completeEvidence == null) {
            phase.completionObservedNanos = System.nanoTime()
            val actual = buildJsonObject {
                put("parent", ContractJson.encodeToJsonElement(parent)); put("children", ContractJson.encodeToJsonElement(children))
                put("timing", phaseTimes(phase)); put("qualified", false)
            }
            realWriteJson(receipts.resolve("phase-$number-required-completion.json"), actual)
            realExpect(parent.slots.size == 1 && parent.slots.single().requiredRuleIds == Catalog().ids.sorted() &&
                children.size == 1 && parent.state != "complete", "real_model_required_completion_not_full_unqualified")
            val result = requireNotNull(children.single().result)
            val receipt = custody.complete(number, result)
            realTreeBoundary(realPath(selection.runRoot)); generation.verify()
            phase.completeEvidence = receipt; phase.checked = true
            realWriteJson(receipts.resolve("phase-$number-timing.json"), phaseTimes(phase))
        }
        return buildJsonObject {
            put("state", if (phase.checked) "phase_verified_unqualified" else "phase_pending")
            put("phase", number); put("terminal", terminal); put("phaseVerified", phase.checked)
            put("parent", ContractJson.encodeToJsonElement(parent)); put("children", ContractJson.encodeToJsonElement(children))
            put("timing", phaseTimes(phase)); put("qualified", false); put("usefulClean", false)
        }
    }
    fun retainFailure(code: String) {
        if (failure != null) return
        failure = code
        realWriteJson(receipts.resolve("unavailable-retained.json"), buildJsonObject {
            put("state", "unavailable_retained"); put("reason", code); put("phaseAttempts", JsonArray(attempted.sorted().map(::JsonPrimitive)))
            put("generation", generation.evidence()); put("retirementVerified", retirementVerified)
            put("ownerTransportAutoClosed", false); put("retryPermitted", false); put("qualified", false)
        })
    }
    private fun checkpoint(phase: RealModelPhase) {
        realExpect(!requireNotNull(phase.intent.originalDeadline).isExpired() && !Thread.currentThread().isInterrupted,
            "real_model_original_review_deadline")
    }
}

private fun phaseTimes(phase: RealModelPhase): JsonObject = buildJsonObject {
    put("phase", phase.number); put("ownerBeginEnteredAt", phase.enteredAt); put("ownerBeginMonotonicNanos", phase.enteredNanos)
    val deadline = requireNotNull(phase.intent.originalDeadline)
    put("originalDeadlineIdentity", deadline.identity); put("originalDeadlineMillis", deadline.durationMillis)
    fun elapsed(value: Long?): JsonElement = value?.let { JsonPrimitive((it - phase.enteredNanos) / 1_000_000) } ?: JsonNull
    put("acceptanceReturnObservedMillis", elapsed(phase.acceptanceReturnedNanos))
    put("firstActionableFindingObservedMillis", elapsed(phase.firstActionableObservedNanos))
    put("requiredCompletionObservedMillis", elapsed(phase.completionObservedNanos))
    put("contextInsertionMillis", JsonNull); put("modelRequestObservedMillis", JsonNull)
    put("hostTimingLocation", "Separate companion record measures actual insertion/ACK and subsequent application request/public response")
    put("cpuMillis", JsonNull); put("peakRssBytes", JsonNull); put("usefulClean", false); put("speedTargetClaimed", false)
}
private fun processEvidence(actual: ProcessOutcome): JsonObject = buildJsonObject {
    put("status", actual.status); put("reasons", JsonArray(actual.reasons.map(::JsonPrimitive)))
    put("exitCode", actual.exitCode?.let(::JsonPrimitive) ?: JsonNull); put("executionConfirmed", actual.executionConfirmed)
    put("cancelled", actual.cancelled); put("timedOut", actual.timedOut); put("truncated", actual.truncated)
    put("command", JsonArray(actual.command.map(::JsonPrimitive))); put("outputDirectory", actual.outputDirectory?.toString()?.let(::JsonPrimitive) ?: JsonNull)
    put("stdoutDigest", Digests.sha256(actual.stdout)); put("stderrDigest", Digests.sha256(actual.stderr))
    put("elapsedMillis", actual.metrics.elapsedMillis); put("cpuMillis", JsonNull); put("peakRssBytes", JsonNull)
}
private fun realModelFailureCode(error: Throwable): String = when (error) {
    is RealModelProblem -> error.code
    is ProjectModelException -> error.code
    is dev.ktchecker.compiler.KotlinJvmModelException -> error.code
    is dev.ktchecker.compiler.KotlinJvmRuntimeException -> error.code
    is ExecutionProblem -> error.code
    is KernelException -> error.code
    is CliProblem -> error.code
    else -> "real_model_owner_operation_failed"
}

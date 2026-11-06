package dev.ktchecker.cli

import dev.ktchecker.compiler.KotlinJvmModelBinding
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

@Serializable internal data class RealProducedJvmSelection(
    val schemaVersion: Int,
    val fixture: String,
    val case: String,
    val runRoot: String,
    val ownerInputs: String,
    val modelWorkerBridge: String,
    val modelWorkerBridgeDigest: String,
    val modelWorkerBridgeBytes: Long,
    val ownerJava: String,
    val ownerClasspath: String,
)

/** Opt-in TEST MAIN ONLY. The actual public KernelService supplies K and PreparedInputObserver.
 * This is not a normal CLI/Workbench/host-model route, delivered final review or release claim.
 * One fresh selected case, original clocks, no retry/kill/restart/old-attempt inspection. Unknown
 * attempts park and retain custody. Explicit verified retirement must precede normal stdio EOF.
 */
object RealProducedJvmOwnerMain {
    const val FIXTURE = "ktc_real_produced_jvm"
    const val GENERATION_MILLIS = 900_000L
    const val REVIEW_MILLIS = 300_000L
    @JvmStatic fun main(args: Array<String>) {
        val entered = System.nanoTime()
        val originalGenerationDeadline = OwnedApplicationDeadline.afterMillis(GENERATION_MILLIS)
        var owner: RealProducedJvmOwner? = null
        var evidence: Path? = null
        try {
            realExpect(args.size == 5 && args[0] == "--selection" && args[2] == "--selection-sha256" &&
                args[4] == "--approve-real-produced-jvm-preview", "real_produced_explicit_selection_required")
            val selected = realRead(realPath(args[1]), 64 * 1024); realDigest(args[3])
            realExpect(Digests.sha256(selected) == args[3], "real_produced_selection_changed")
            val selection = ContractJson.decodeFromString<RealProducedJvmSelection>(strictJsonDocument(selected, "real_produced_selection_json"))
            realExpect(selection.schemaVersion == 1 && selection.fixture == FIXTURE, "real_produced_selection_schema")
            // No catalog-generation mismatch may reach a real compiler application launch.
            realExpect(OwnerFullCatalog1665.accepts(Catalog().ids.sorted()), "real_produced_catalog_contract_changed")
            val fixture = RealProducedJvmFixture(selection.case)
            val run = realPath(selection.runRoot); realKey(run, true)
            val root = run.resolve("produced-owner")
            realExpect(!Files.exists(root, NOFOLLOW_LINKS), "real_produced_owner_attempt_not_fresh")
            realDirectory(root)
            val receipts = root.resolve("evidence"); realDirectory(receipts); evidence = receipts
            val freeBytes = Files.getFileStore(run).usableSpace
            realWriteJson(receipts.resolve("start.json"), buildJsonObject {
                put("state", "initializing"); put("fixture", FIXTURE); put("case", fixture.selection); put("selectionDigest", args[3])
                put("ownerEntryMonotonicNanos", entered); put("capturedAt", Instant.now().toString())
                put("originalGenerationDeadlineIdentity", originalGenerationDeadline.identity)
                put("originalGenerationDeadlineMillis", GENERATION_MILLIS); put("originalReviewBudgetMillis", REVIEW_MILLIS)
                put("usableBytesBeforeSeedPreparation", freeBytes); put("seedCopyLimitBytes", 512L * 1024 * 1024)
                put("runBoundaryLimitBytes", 1024L * 1024 * 1024); put("runBoundaryEntryLimit", 40_000)
                put("continuousFilesystemQuota", false); put("qualified", false)
                put("productionWorkbenchActivated", false); put("hostInsertionOrModelObservationTested", false)
            })
            realExpect(freeBytes >= 1536L * 1024 * 1024, "real_produced_disk_headroom_unavailable")
            val startup = RealModelStartup.capture()
            realWriteJson(receipts.resolve("startup-image.json"), startup.evidence())
            val inputs = RealModelOwnerInputs.load(realPath(selection.ownerInputs))
            realExpect(realPath(selection.ownerJava) == inputs.javaHome.resolve("bin/java") &&
                realPath(System.getProperty("java.home")) == inputs.javaHome &&
                System.getProperty("java.class.path") == selection.ownerClasspath, "real_produced_selected_owner_launch_changed")
            val home = inputs.deriveExecutionHome(root)
            realExpect(!originalGenerationDeadline.isExpired(), "real_produced_generation_original_deadline")
            val installation = KotlinJvmToolchain(inputs.javaHome.resolve("bin/java"), inputs.compilerClasspath())
            val state = PrivateState(root.resolve("state"))
            val namespace = CompilerApplicationNamespace(state.ownerId, "produced-${fixture.selection}-workspace", "produced-${fixture.selection}-worktree")
            val authority = RealProducedJvmAuthority()
            val bridge = RealModelFilePin.capture(realPath(selection.modelWorkerBridge), selection.modelWorkerBridgeDigest, selection.modelWorkerBridgeBytes)
            val generation = RealProducedJvmGeneration(installation, namespace, root.resolve("generation"), bridge, startup,
                args[3], originalGenerationDeadline, run, authority)
            val opened = generation.open()
            realWriteJson(receipts.resolve("generation-open.json"), generation.evidence())
            realExpect(opened is ModelCompilerApplicationOpenResult.Ready, "real_produced_generation_unavailable")
            val broker = state.root.resolve("broker")
            val custody = RealProducedJvmCustody(generation, broker, receipts)
            val kernel = KernelService.open(broker, custody,
                KernelConfig(workerCount = 1, globalParallelism = 1, perWorktreeParallelism = 1, analysisTimeoutMillis = REVIEW_MILLIS),
                preparedInputObserver = custody,
                findingProviders = custody.supportedRuleIds.associateWith { setOf(KotlinJvmModelLiveCompilerProvider.PROVIDER_ID) })
            owner = RealProducedJvmOwner(selection, args[3], fixture, root, receipts, inputs, home, startup, generation,
                custody, kernel, SnapshotStore(state.root.resolve("snapshots"), state.ownerId), ConfigStore(state.root.resolve("configuration")))
            realWriteJson(receipts.resolve("owner-ready.json"), buildJsonObject {
                put("state", "owner_ready"); put("entryToReadyMillis", (System.nanoTime() - entered) / 1_000_000)
                put("sameSelectedInstallationObject", true); put("compilerRosterCount", installation.compilerClasspath.size)
                put("ownerInputsDigest", inputs.ownerManifestDigest); put("runtimeInputsDigest", inputs.runtimeDigest)
                put("executionHomeDigest", home.digest); put("requiredParentRules", OwnerFullCatalog1665.RULE_COUNT); put("qualified", false)
                put("catalogContract", OwnerFullCatalog1665.ID); put("catalogRuleIdsDigest", OwnerFullCatalog1665.RULE_IDS_DIGEST)
            })
            RpcServer(owner).serve(System.`in`, System.out)
            if (owner.retirementVerified) {
                realWriteJson(receipts.resolve("transport-eof-after-verified-retirement.json"), buildJsonObject {
                    put("state", "owner_eof_after_explicit_verified_retirement"); put("retirementVerifiedBeforeEof", true)
                    put("generationId", generation.request.launch.identity().generation())
                })
                kernel.close()
                return
            }
            owner.retainFailure("real_produced_eof_before_verified_retirement")
        } catch (failure: Throwable) {
            val code = realProducedFailureCode(failure)
            try {
                if (owner != null) owner.retainFailure(code)
                else evidence?.let { realWriteJson(it.resolve("initialization-unavailable.json"), buildJsonObject {
                    put("state", "unavailable_retained"); put("reason", code); put("custodyDisposition", "not_independently_verified")
                    put("retryPermitted", false); put("qualified", false)
                }) }
            } catch (_: Throwable) { /* Do not replace evidence, infer closure, signal or retry. */ }
        }
        val retained = CountDownLatch(1)
        while (true) { try { retained.await() } catch (_: InterruptedException) { /* custody remains retained */ } }
    }
}

private class RealProducedJvmPhase(val number: Int, val entered: Long, val startedAt: String, val deadline: OwnedApplicationDeadline) {
    var configuration: ConfigSnapshot? = null
    var commit: SnapshotCommit? = null
    var captured: MaterializedSnapshot? = null
    var model: VerifiedProjectModel? = null
    var preview: KotlinJvmProducedOutputPreview? = null
    var provider: KotlinJvmModelLiveCompilerProvider? = null
    var impact: ProjectImpactPlan? = null
    var producer: RealProducedJvmEntry? = null
    var consumer: RealProducedJvmEntry? = null
    var producerReview: ReviewStatus? = null
    var consumerReview: ReviewStatus? = null
    var artifact: ModelProducedOutputArtifact? = null
    var consumerAttempted = false
    var checked = false
    var completedNanos: Long? = null
}

private class RealProducedJvmOwner(
    private val selection: RealProducedJvmSelection,
    private val selectionDigest: String,
    private val fixture: RealProducedJvmFixture,
    private val root: Path,
    private val evidence: Path,
    private val inputs: RealModelOwnerInputs,
    private val home: RealModelExecutionHome,
    private val startup: RealModelStartup,
    private val generation: RealProducedJvmGeneration,
    private val custody: RealProducedJvmCustody,
    private val kernel: KernelService,
    private val snapshots: SnapshotStore,
    private val configs: ConfigStore,
) : RpcHandler {
    private val namespace = generation.namespace
    private val identity = SnapshotIdentity(namespace.ownerId, namespace.workspaceId, namespace.worktreeId)
    private val registration = kernel.registerClient(ClientBinding(namespace.ownerId, namespace.workspaceId, namespace.worktreeId,
        "real-produced-client", "real-produced-lineage", sessionId = "real-produced-session"))
    private val configBinding = ConfigBinding(namespace.ownerId, namespace.workspaceId, namespace.worktreeId, registration.binding.lineageId)
    private val catalog = Catalog()
    private val phases = mutableListOf<RealProducedJvmPhase>()
    private val attempted = mutableSetOf<Int>()
    private var failure: String? = null
    private var retireAttempted = false
    var retirementVerified = false
        private set
    init {
        realExpect(OwnerFullCatalog1665.accepts(catalog.ids.sorted()), "real_produced_catalog_contract_changed")
        snapshots.registerWorktree(identity)
    }

    override fun call(method: String, params: JsonObject): JsonObject = try {
        when (method) {
            "owner.hello" -> {
                requireKeys(params, emptySet())
                buildJsonObject {
                    put("fixture", selection.fixture); put("case", fixture.selection); put("selectionDigest", selectionDigest)
                    put("binding", ContractJson.encodeToJsonElement(registration.binding)); put("generation", generation.evidence())
                    put("requiredParentRules", JsonArray(catalog.ids.sorted().map(::JsonPrimitive)))
                    put("catalogContract", OwnerFullCatalog1665.ID); put("catalogRuleIdsDigest", OwnerFullCatalog1665.RULE_IDS_DIGEST)
                    put("phaseCount", fixture.phases); put("qualified", false); put("productionWorkbenchActivated", false)
                }
            }
            "owner.phase.begin" -> {
                requireKeys(params, setOf("phase")); val number = params.decode<Int>("phase")
                realExpect(failure == null && !retireAttempted && number in 1..fixture.phases &&
                    number == phases.size + 1 && number !in attempted && phases.all { it.checked }, "real_produced_phase_replay_or_unsettled")
                attempted += number
                begin(number)
            }
            "owner.consumer.begin" -> {
                requireKeys(params, setOf("phase")); consumer(params.decode("phase"))
            }
            "owner.phase.status" -> {
                requireKeys(params, setOf("phase")); status(params.decode("phase"))
            }
            "owner.retire" -> {
                requireKeys(params, emptySet())
                realExpect(failure == null && !retireAttempted && phases.size == fixture.phases && phases.all { it.checked },
                    "real_produced_retirement_requires_all_selected_checks")
                retireAttempted = true
                realWriteJson(evidence.resolve("retirement-intent.json"), buildJsonObject {
                    put("state", "explicit_owner_retirement_requested"); put("generationId", generation.request.launch.identity().generation())
                    put("transportInputStillOpen", true); put("requestedAt", Instant.now().toString())
                })
                val receipt = generation.retire()
                realExpect(receipt.disposition == OwnedApplicationCleanupDisposition.CLOSED && generation.retirementVerified,
                    "real_produced_independent_retirement_not_verified")
                realTreeBoundary(realPath(selection.runRoot))
                val complete = buildJsonObject {
                    put("state", "explicit_retirement_verified"); put("generation", generation.evidence())
                    put("verifiedBeforeTransportEof", true); put("verifiedAt", Instant.now().toString())
                    put("selectedCaseVerified", fixture.selection); put("qualified", false); put("productionWorkbenchActivated", false)
                }
                realWriteJson(evidence.resolve("retirement-verified.json"), complete)
                retirementVerified = true
                complete
            }
            else -> throw CliProblem("unknown_method", "No such fixed produced-preview test-owner operation.")
        }
    } catch (error: Throwable) {
        val code = realProducedFailureCode(error); retainFailure(code)
        buildJsonObject { put("state", "unavailable_retained"); put("reason", code); put("retryPermitted", false); put("qualified", false) }
    }

    private fun begin(number: Int): JsonObject {
        // FIRST operation creating a phase authority: before config/capture/export/preparation IO.
        val phase = RealProducedJvmPhase(number, System.nanoTime(), Instant.now().toString(), OwnedApplicationDeadline.afterMillis(RealProducedJvmOwnerMain.REVIEW_MILLIS))
        phases += phase
        realWriteJson(evidence.resolve("phase-$number-intent.json"), timing(phase))
        val configuration = configs.capture(configBinding); phase.configuration = configuration
        realWriteJson(evidence.resolve("phase-$number-captured-config.json"), configs.exportSnapshot(configuration))
        val previous = phases.getOrNull(number - 2)
        val oldFiles = if (previous == null) emptyMap() else fixture.files(previous.number)
        val files = fixture.files(number)
        val changes = files.filter { (path, text) -> oldFiles[path] != text }.map { (path, text) ->
            SnapshotChange.PutFile(SnapshotPath(path = path), ImmutableBlob.utf8(text))
        }
        checkpoint(phase)
        val commit = snapshots.sealOverlay(OverlayRequest(identity, registration.binding.clientId, registration.binding.sessionId,
            "produced-${fixture.selection}-phase-$number", previous?.commit?.snapshotId, previous?.commit?.headRevision ?: 0,
            changes, scope = SnapshotScope("NEW explicit Kotlin-only produced-output preview; no Java/resource/generated/plugin/Native or general main+test activation")))
        phase.commit = commit
        val snapshot = commit.toContractManifest(); fixture.verifySnapshot(snapshot, number)
        val phaseRoot = root.resolve("phase-$number"); realDirectory(phaseRoot)
        val captured = snapshots.materialize(identity, commit.snapshotId, phaseRoot.resolve("source")); phase.captured = captured
        fun verifyCapture() {
            checkpoint(phase); fixture.verifySnapshot(captured.manifest.toContractManifest(), number)
            realExpect(snapshots.verifyMaterialization(captured).valid, "real_produced_registered_capture_changed")
            checkpoint(phase)
        }
        val profile = GradleModelExporter.profile(inputs.gradleHome.resolve("bin/gradle"),
            requireNotNull(inputs.artifact(inputs.gradleHome.resolve("bin/gradle")).digest), inputs.javaHome, home.root)
        val exportInput = AnalysisInput(snapshot, captured.root.toString(), context = AnalysisContext(configurationDigest = configuration.policyDigest),
            execution = profile, outputRoot = phaseRoot.resolve("export-output").toString())
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
            realWriteJson(evidence.resolve("phase-$number-export-process.json"), buildJsonObject {
                put("status", outcome.status); put("reasons", JsonArray(outcome.reasons.map(::JsonPrimitive)))
                put("exitCode", outcome.exitCode?.let(::JsonPrimitive) ?: JsonNull); put("executionConfirmed", outcome.executionConfirmed)
                put("cancelled", outcome.cancelled); put("timedOut", outcome.timedOut); put("truncated", outcome.truncated)
                put("command", JsonArray(outcome.command.map(::JsonPrimitive))); put("elapsedMillis", outcome.metrics.elapsedMillis)
            })
            realWrite(evidence.resolve("phase-$number-private-export-stdout.txt"), outcome.stdout.toByteArray(Charsets.UTF_8), 8 * 1024 * 1024)
            realWrite(evidence.resolve("phase-$number-private-export-stderr.txt"), outcome.stderr.toByteArray(Charsets.UTF_8), 8 * 1024 * 1024)
        }.run(exportInput, ExactExecutionAuthority(permit), CancellationToken { phase.deadline.isExpired() })
        realExpect(completed === actual, "real_produced_actual_export_completion_missing")
        val raw = actual.report(GradleModelExporter.REPORT, 16 * 1024 * 1024)
        realWrite(evidence.resolve("phase-$number-raw-model-export.json"), raw) // ENTIRE report, before any fixture/admission decision.
        val request = ModelImportRequest(ModelNamespace(namespace.ownerId, namespace.workspaceId, namespace.worktreeId, registration.binding.lineageId),
            snapshot, captured.root.toString(), configuration.policyDigest, Digests.sha256(raw), GradleModelExporter.scriptDigest(),
            "produced-${fixture.selection}-export-$number")
        val output = requireNotNull(actual.outputDirectory)
        fun verifyExecuted() {
            checkpoint(phase); startup.verifyCompletion(actual)
            realExpect(completed === actual && request.snapshot == exportInput.snapshot && request.materializedRoot == exportInput.materializedRoot &&
                request.configurationDigest == exportInput.context.configurationDigest && request.exporterDigest == GradleModelExporter.scriptDigest() &&
                actual.successfulExecution && actual.exitCode == 0 && !actual.cancelled && !actual.timedOut && !actual.truncated && actual.reasons.isEmpty(),
                "real_produced_exporter_invocation_unverified")
            realExpect(actual.command == listOf(profile.executable) + profile.arguments.map {
                it.replace("{snapshot}", captured.root.toString()).replace("{output}", output.toString())
            }, "real_produced_exporter_command_changed")
            realExpect(Digests.sha256(actual.report(GradleModelExporter.REPORT, 16 * 1024 * 1024)) == request.exportDigest,
                "real_produced_actual_report_changed")
            inputs.verifyRuntimeInputs(); verifyCapture(); checkpoint(phase)
        }
        verifyExecuted(); home.confirmCompletedExport(number)
        val origins = fixture.origins(request, output, inputs)
        var observed: GradleProjectExport? = null
        val ingress = ModelIngressPermit(request,
            Digests.sha256(Digests.canonical(JsonArray(listOf(inputs.runtimeDigest, home.digest, startup.image.digest).map(::JsonPrimitive)))),
            verifySnapshot = { verifyCapture(); inputs.verifyRuntimeInputs(); startup.verify(); checkpoint(phase) },
            verifyExecutedExport = ::verifyExecuted, verifyArtifact = inputs::verifyArtifact,
            verifyModelInputs = { graph ->
                verifyCapture(); fixture.verifyGraph(snapshot, number, graph, inputs, generation.installation, output, origins)
                inputs.verifyJvmProducerArtifacts(graph); observed = graph; checkpoint(phase)
            }, verifyJvmArgumentOrigin = { compilation ->
                verifyExecuted(); inputs.verifyJvmProducerArtifacts(requireNotNull(observed))
                when (compilation.id) { RealProducedJvmFixture.PRODUCER_ID -> origins.producer; fixture.consumerId -> origins.consumer; else -> null }
            }, verifyJvmProducedOutputOrigin = { compilation ->
                verifyExecuted(); inputs.verifyJvmProducerArtifacts(requireNotNull(observed))
                if (compilation.id == fixture.consumerId) origins.edge else null
            })
        val model = ProjectModelImporter(ExactModelIngressAuthority(ingress)).import(request, raw)
        phase.model = model
        realWriteJson(evidence.resolve("phase-$number-imported-model.json"), buildJsonObject {
            put("identity", ContractJson.encodeToJsonElement(model.identity)); put("request", ContractJson.encodeToJsonElement(request))
            put("certificate", model.ingressCertificate); put("issues", ContractJson.encodeToJsonElement(model.issues))
            putJsonArray("rawContexts") { model.compilationIds.forEach { id -> add(ContractJson.encodeToJsonElement(model.context(id, configuration.policyDigest).context)) } }
            put("allOriginalIssuesRetained", true); put("qualified", false)
        })
        assertRawUnavailable(model)
        realProducedRefusal("compiler_model_incomplete") { KotlinJvmModelBinding.verifyAndBind(model, RealProducedJvmFixture.PRODUCER_ID, generation.installation) }
        val preview = KotlinJvmProducedOutputPreview.create(model, generation.installation, phase.deadline); phase.preview = preview
        realExpect(preview.model === model && preview.graph.compilationIds.toSet() == setOf(RealProducedJvmFixture.PRODUCER_ID, fixture.consumerId),
            "real_produced_genuine_graph_not_connected")
        realProducedRefusal("compiler_model_produced_obligation_unfulfilled") { preview.bindCompilation(fixture.consumerId) }
        previous?.artifact?.let { stale ->
            realProducedRefusal("compiler_model_produced_foreign_identity") { preview.bindCompilation(fixture.consumerId, stale) }
            realWriteJson(evidence.resolve("phase-$number-stale-previous-import-rejected.json"), buildJsonObject {
                put("reason", "compiler_model_produced_foreign_identity"); put("beforeConsumerPreparation", true)
                put("sameActualGeneration", true); put("newGenuineImport", true); put("counterfactualModelCopyUsed", false)
            })
        }
        val impact = ProjectImpactPlanner().impact(previous?.model, model); phase.impact = impact
        realExpect(impact.obligations.map { it.compilationId }.toSet() == model.compilationIds.toSet() && impact.obligations.size == 2,
            "real_produced_impact_dropped_compilation")
        if (number == 2) {
            val changed = if (fixture.selection == RealProducedJvmFixture.FRIEND_API) RealProducedJvmFixture.PRODUCER_PATH else fixture.consumerPath
            realExpect(impact.changedFiles == listOf(changed), "real_produced_expected_source_delta_changed")
            if (fixture.selection == RealProducedJvmFixture.FRIEND_API) realExpect(
                requireNotNull(previous?.model).snapshot.files.single { it.path == fixture.consumerPath } == snapshot.files.single { it.path == fixture.consumerPath },
                "real_produced_api_control_consumer_was_edited")
        }
        realWriteJson(evidence.resolve("phase-$number-whole-obligations.json"), buildJsonObject {
            put("impactCertificate", impact.certificateId); put("changedFiles", JsonArray(impact.changedFiles.map(::JsonPrimitive)))
            put("impactReasons", JsonArray(impact.reasons.map(::JsonPrimitive))); put("obligations", ContractJson.encodeToJsonElement(impact.obligations))
            putJsonArray("requiredParents") { impact.obligations.forEach { obligation -> add(buildJsonObject {
                put("compilationId", obligation.compilationId); put("requiredRuleIds", JsonArray(catalog.ids.sorted().map(::JsonPrimitive)))
            }) } }
            put("conservativeWholeGraphImpactNotSemanticOptimization", true); put("kernelParentPlanUsed", false)
            put("publicKernelScalarParentsRetainFull1663", true); put("consumerCannotSubmitUntilProducerAcceptedOutput", true)
        })
        val provider = KotlinJvmModelLiveCompilerProvider(generation.installation, generation.authority, preview); phase.provider = provider
        val resolved = preview.bindCompilation(RealProducedJvmFixture.PRODUCER_ID)
        val entry = custody.retain(number, parent(phase, resolved), model, preview, resolved, provider, null); phase.producer = entry
        checkpoint(phase)
        phase.producerReview = kernel.submit(registration.client, SubmitRequest("produced-$number-producer", "produced-$number-producer", entry.original))
        realWriteJson(evidence.resolve("phase-$number-producer-accepted.json"), requireNotNull(phase.producerReview))
        return buildJsonObject { put("state", "producer_parent_accepted"); put("phase", number); put("timing", timing(phase)); put("qualified", false) }
    }

    private fun parent(phase: RealProducedJvmPhase, resolved: JvmProducedPreviewCompilation): AnalysisInput {
        val config = requireNotNull(phase.configuration)
        val activation = activationPlan(catalog, catalog.ids.sorted(), true, config.ruleSettings.mapValues { it.value.enabled }, config.policyDigest)
        val context = resolved.context.copy(policies = resolved.context.policies + mapOf(
            "ktchecker.catalog-inventory" to Digests.sha256(Digests.canonical(JsonArray(catalog.records))),
            "ktchecker.activation-plan" to Digests.canonical(ContractJson.encodeToJsonElement(activation)),
            "ktchecker.execution-plan" to Digests.sha256(Digests.canonical(ContractJson.encodeToJsonElement(emptyMap<String, TrustedExecution>())))))
        return AnalysisInput(requireNotNull(phase.model).snapshot, requireNotNull(phase.captured).root.toString(), context,
            catalog.ids.sorted(), requireNotNull(phase.impact).changedFiles, phase.commit?.manifest?.parentSnapshotId)
    }

    private fun consumer(number: Int): JsonObject {
        val phase = phase(number)
        realExpect(!retireAttempted && !phase.checked && !phase.consumerAttempted && phase.producer?.checked == true &&
            phase.producer?.success != null, "real_produced_consumer_requires_checked_successful_producer")
        phase.consumerAttempted = true
        checkpoint(phase)
        val producer = requireNotNull(phase.producer); val success = requireNotNull(producer.success)
        val artifact = success.materializeJar(root.resolve("phase-$number-produced-archive")); phase.artifact = artifact
        custody.verifyJar(producer, artifact)
        val preview = requireNotNull(phase.preview); val model = requireNotNull(phase.model)
        val resolved = preview.bindCompilation(fixture.consumerId, artifact)
        val entry = custody.retain(number, parent(phase, resolved), model, preview, resolved, requireNotNull(phase.provider), success)
        phase.consumer = entry
        checkpoint(phase)
        phase.consumerReview = kernel.submit(registration.client, SubmitRequest("produced-$number-consumer", "produced-$number-consumer", entry.original))
        realWriteJson(evidence.resolve("phase-$number-consumer-accepted.json"), requireNotNull(phase.consumerReview))
        return buildJsonObject { put("state", "consumer_parent_accepted"); put("phase", number); put("qualified", false) }
    }

    private fun status(number: Int): JsonObject {
        val phase = phase(number)
        val producer = requireNotNull(phase.producer)
        val p = kernel.status(registration.client, requireNotNull(phase.producerReview).reviewId)
        checkCompletion(phase, producer, p)
        val consumer = phase.consumer
        val c = phase.consumerReview?.let { kernel.status(registration.client, it.reviewId) }
        if (consumer != null && c != null) checkCompletion(phase, consumer, c)
        if (!phase.checked && producer.checked && ((fixture.selection == RealProducedJvmFixture.FAILED) || consumer?.checked == true)) {
            assertRawUnavailable(requireNotNull(phase.model))
            generation.verify(); realTreeBoundary(realPath(selection.runRoot))
            phase.checked = true; phase.completedNanos = System.nanoTime()
            realWriteJson(evidence.resolve("phase-$number-verified.json"), buildJsonObject {
                put("state", "phase_verified_unqualified"); put("phase", number); put("case", fixture.selection)
                put("producer", ContractJson.encodeToJsonElement(p)); put("consumer", c?.let { ContractJson.encodeToJsonElement(it) } ?: JsonNull)
                put("timing", timing(phase)); put("qualified", false); put("usefulClean", false)
                put("productionWorkbenchActivated", false); put("generalMainTestSupport", false)
            })
        }
        return buildJsonObject {
            put("state", if (phase.checked) "phase_verified_unqualified" else if (producer.checked && consumer == null) "producer_verified_awaiting_explicit_consumer_begin" else "phase_pending")
            put("phase", number); put("phaseVerified", phase.checked); put("producer", ContractJson.encodeToJsonElement(p))
            put("consumer", c?.let { ContractJson.encodeToJsonElement(it) } ?: JsonNull); put("timing", timing(phase)); put("qualified", false)
        }
    }

    private fun checkCompletion(phase: RealProducedJvmPhase, entry: RealProducedJvmEntry, status: ReviewStatus) {
        if (entry.checked || status.state in setOf("queued", "running")) return
        realWriteJson(evidence.resolve("${entry.label}-kernel-status.json"), status)
        realExpect(!status.cancelled && status.reasons.none { it in setOf("prepared_input_cleanup_failed", "analysis_deadline_exceeded", "analysis_interrupted") },
            "real_produced_kernel_attempt_not_settled")
        val child = custody.complete(entry, requireNotNull(status.result))
        val failed = if (entry.producer) fixture.selection == RealProducedJvmFixture.FAILED else phase.number == 2
        realExpect((child.status == "failed") == failed &&
            ("compiler_model_live_api_diagnostic_failure" in child.reasons) == failed, "real_produced_unexpected_actual_compiler_exit")
        if (!failed) realExpect(child.findings.none { it.severity == "error" } && child.rawDiagnostics.none { it.severity == "error" },
            "real_produced_success_has_error_diagnostic")
        else if (entry.producer) {
            requireError(child, RealProducedJvmFixture.PRODUCER_PATH, "missingProducerValue")
            realExpect(entry.success == null && requireNotNull(entry.permit).successfulProducedOutput() == null, "real_produced_failed_output_minted")
            realProducedRefusal("compiler_model_produced_obligation_unfulfilled") { requireNotNull(phase.preview).bindCompilation(fixture.consumerId) }
            realWriteJson(evidence.resolve("phase-${phase.number}-consumer-unfulfilled.json"), buildJsonObject {
                put("state", "unsupported_unsubmitted_consumer"); put("reason", "producer_compilation_failed_without_accepted_output")
                put("compilationId", fixture.consumerId); put("requiredRuleIds", JsonArray(catalog.ids.sorted().map(::JsonPrimitive)))
                put("consumerKernelSubmissionPerformed", false); put("successfulProducerTokenMinted", false); put("qualified", false)
            })
        } else if (fixture.selection == RealProducedJvmFixture.FRIEND_API) {
            requireError(child, fixture.consumerPath, "publicValue")
            realExpect(!hasError(child, fixture.consumerPath, "internalValue"), "real_produced_friend_internal_regressed")
        } else {
            requireError(child, fixture.consumerPath, "internalValue")
            requireError(child, fixture.consumerPath, "privateValue")
            realExpect(!hasError(child, fixture.consumerPath, "publicValue"), "real_produced_nonfriend_public_api_unresolved")
        }
        entry.checked = true
    }

    private fun phase(number: Int): RealProducedJvmPhase {
        realExpect(failure == null && number in 1..phases.size, "real_produced_phase_unavailable")
        return phases[number - 1]
    }
    private fun checkpoint(phase: RealProducedJvmPhase) {
        realExpect(!phase.deadline.isExpired() && !Thread.currentThread().isInterrupted, "real_produced_original_review_deadline")
    }
    private fun assertRawUnavailable(model: VerifiedProjectModel) {
        realExpect(model.issues.size == 1 && model.issues.single().kind == "jvm_produced_output_deferred" &&
            model.compilationIds.size == 2 && model.compilationIds.all { id -> model.context(id, model.identity.configurationDigest).let {
                it.completeness == ModelCompleteness.UNAVAILABLE && it.certificateId == null && it.context?.fullProject == false
            } }, "real_produced_raw_model_became_complete_or_lost_issue")
    }
    fun retainFailure(code: String) {
        if (failure != null) return
        failure = code
        realWriteJson(evidence.resolve("unavailable-retained.json"), buildJsonObject {
            put("state", "unavailable_retained"); put("reason", code); put("attemptedPhases", JsonArray(attempted.sorted().map(::JsonPrimitive)))
            put("generation", generation.evidence()); put("retirementVerified", retirementVerified)
            put("ownerTransportAutoClosed", false); put("retryPermitted", false); put("qualified", false)
        })
    }
}

private fun timing(phase: RealProducedJvmPhase): JsonObject = buildJsonObject {
    put("phase", phase.number); put("ownerBeginAt", phase.startedAt); put("ownerBeginMonotonicNanos", phase.entered)
    put("originalDeadlineIdentity", phase.deadline.identity); put("originalDeadlineMillis", phase.deadline.durationMillis)
    put("completionObservedMillis", phase.completedNanos?.let { JsonPrimitive((it - phase.entered) / 1_000_000) } ?: JsonNull)
    put("sameProducerConsumerClockObject", phase.preview?.originalDeadline === phase.deadline)
    put("cpuMillis", JsonNull); put("peakRssBytes", JsonNull); put("speedClaimed", false)
}
private fun hasError(result: ProviderResult, path: String, symbol: String): Boolean =
    result.findings.any { it.severity == "error" && it.location.path == path && it.diagnosis.contains(symbol) } ||
        result.rawDiagnostics.any { it.severity == "error" && it.location?.path == path && it.message.contains(symbol) }
private fun requireError(result: ProviderResult, path: String, symbol: String) =
    realExpect(hasError(result, path, symbol), "real_produced_expected_named_error_not_observed")
private fun realProducedRefusal(expected: String, action: () -> Any?) {
    val actual = try { action(); null } catch (error: Throwable) { realProducedFailureCode(error) }
    realExpect(actual == expected, "real_produced_expected_refusal_missing")
}
private fun realProducedFailureCode(error: Throwable): String = when (error) {
    is RealModelProblem -> error.code
    is ProjectModelException -> error.code
    is dev.ktchecker.compiler.KotlinJvmModelException -> error.code
    is dev.ktchecker.compiler.KotlinJvmRuntimeException -> error.code
    is ExecutionProblem -> error.code
    is KernelException -> error.code
    is CliProblem -> error.code
    // The internal live-model exception is intentionally not a CLI API. These two controls
    // admit only known fixed codes, never arbitrary exception text or reflected implementation.
    else -> error.message?.takeIf { it in setOf("compiler_model_produced_obligation_unfulfilled",
        "compiler_model_produced_foreign_identity") } ?: "real_produced_owner_operation_failed"
}

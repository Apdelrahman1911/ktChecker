package dev.ktchecker.providers.detekt

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import dev.ktchecker.snapshot.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.extension.TestWatcher
import kotlin.test.*

/** Separate source set/task: never renews the older pack's source witnesses or evidence. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DetektSyntaxCorpusIntegrationTest {
    private lateinit var temporary: Path
    private val repository: Path get() = Path.of(System.getProperty("ktchecker.repositoryRoot")).toAbsolutePath().normalize()
    private val fixtures: Path get() = repository.resolve("providers/detekt-fixtures/corpus/syntax-v1")
    private val expansionFixtures: Path get() = repository.resolve(DetektCorpusInventory.EXPANSION)
    private val protocolFixtures: Path get() = repository.resolve(DetektCorpusInventory.PROTOCOL)
    private val observabilityFixtures: Path get() = repository.resolve(DetektCorpusInventory.OBSERVABILITY)
    private val localShapeFixtures: Path get() = repository.resolve(DetektCorpusInventory.LOCAL_SHAPES)
    private lateinit var inventory: DetektCorpusInventory
    private val enabled: Boolean get() = System.getProperty("ktchecker.detekt.corpus.integration") == "true"
    private lateinit var cohort: DetektCorpusCohort
    private var evidence: DetektCorpusEvidence? = null
    private var budget: DetektCorpusBudget? = null
    private var pendingSidecar: JsonObject? = null
    private var startWitness: Witness? = null
    private val progressReferences = mutableListOf<JsonObject>()
    private val successful = AtomicBoolean()
    private val failureRetained = AtomicBoolean()
    @RegisterExtension @JvmField val evidenceGate: TestWatcher = object : TestWatcher {
        override fun testSuccessful(context: ExtensionContext) { successful.set(true) }
        override fun testFailed(context: ExtensionContext, cause: Throwable) {
            successful.set(false)
            retainFailure(cause)
        }
    }

    @AfterAll fun finalizeSelectedCohortOnly() {
        val owned = evidence ?: return
        try {
            if (!successful.get() || pendingSidecar == null) {
                owned.withdrawCohort()
            } else {
                val limits = assertNotNull(budget)
                limits.requireWithinDeadline()
                assertNotNull(startWitness).verify()
                owned.verifyDirectories()
                owned.artifact("completion-ready-to-publish.json", buildJsonObject {
                    put("state", "selected_cohort_candidate_ready_to_publish"); put("captured_at", Instant.now().toString())
                    put("cohort", cohort.name); put("qualified", false)
                    put("candidate_only", true); put("run_complete", false); put("publication_committed", false)
                    put("budget", limits.summary())
                    put("note", "Candidate checkpoint only; expensive serialization/live write still require final deadline checks and real JUnit/build completion.")
                }.toString().toByteArray())
                limits.requireWithinDeadline()
                owned.publish(assertNotNull(pendingSidecar), limits::requireWithinDeadline)
                // Final clock after serialization, hash computation, output bounds, write and handle closure.
                // It is inside this failure catch; no further evidence work can overrun after it.
                limits.requireWithinDeadline()
            }
        } catch (cause: Throwable) {
            try { retainFailure(cause) } catch (checkpoint: Throwable) { cause.addSuppressed(checkpoint) }
            throw cause
        } finally {
            owned.close()
        }
    }

    private fun retainFailure(cause: Throwable) {
        val owned = evidence ?: return
        try {
            if (failureRetained.compareAndSet(false, true)) {
                // Even exhausted budgets may retain bounded failure checkpoints; never retry a provider.
                runCatching { budget?.failure(cause) }
                owned.artifact("completion-failed.json", buildJsonObject {
                    put("state", "failed_or_incomplete"); put("failure_type", cause.javaClass.name)
                    put("cohort", cohort.name); put("finished_at", Instant.now().toString()); put("qualified", false)
                    put("run_complete", false); put("publication_committed", false); put("scratch_quarantined", true)
                    budget?.let { put("budget", it.summary()) }
                    put("note", "Retained/quarantined job scratch and every ancestor plus actual partial artifacts; no passing live cohort sidecar or retry.")
                }.toString().toByteArray())
            }
        } finally {
            // Evidence-bound/serialization/checkpoint errors must never skip withdrawal.
            owned.withdrawCohort()
        }
    }

    @Test fun `explicit bounded selected cohort retains actual native outcomes and heuristic gaps`() {
        assumeTrue(enabled, "Separate real corpus disabled; explicitly select one bounded detektCorpusCohort when scheduled")
        val settings = DetektCorpusSettings.selected(System.getProperty("ktchecker.detekt.corpus.cohort"),
            System.getProperty("ktchecker.detekt.corpus.maxInvocations"), System.getProperty("ktchecker.detekt.corpus.aggregateMillis"),
            System.getProperty("ktchecker.detekt.corpus.childMillis"))
        cohort = settings.cohort
        val owned = DetektCorpusEvidence(repository, System.getProperty("ktchecker.libraryFixtureEvidenceRoot"), cohort)
        evidence = owned
        val run = owned.runDirectory
        val limits = DetektCorpusBudget(settings, { event ->
            progressReferences += artifact(run, "progress/${event.getValue("sequence").jsonPrimitive.int.toString().padStart(4, '0')}.json",
                event.toString().toByteArray())
        })
        budget = limits
        temporary = owned.createRetainedScratch()
        artifact(run, "retained-scratch.json", buildJsonObject {
            put("state", "private_retained_job_scratch"); put("path", repository.relativize(temporary).toString().replace('\\', '/'))
            put("automatic_cleanup", false); put("qualified", false)
            put("note", "Not a framework TempDir. Retain scratch and every ancestor on success, failure, cancellation, uncertain native return or throw; owner audits before cleanup.")
        }.toString().toByteArray())
        owned.prepareCohort()
        assertTrue(System.getProperty("java.version").startsWith("21."), "Corpus requires the selected JDK21")
        val approved = loadCorpus()
        assertEquals(DetektCorpusCohort.FULL_AUTHORED_RULES, approved.size)
        assertEquals(DetektCorpusCohort.authoredRuleIds, approved.map { it.ruleId }.toSet())
        assertEquals(DetektCorpusCohort.FULL_AUTHORED_ANALYSES, approved.sumOf { 6 + it.controls.size })
        assertEquals(15, approved.count { it.activation == "advisory" })
        assertEquals(16, approved.count { it.activation == "opt_in" })
        val previousIds = DetektCorpusCohort.preProtocol.flatMap { it.analysesByRule.keys }.toSet()
        val previous = approved.filter { it.ruleId in previousIds }
        assertEquals(DetektCorpusCohort.PRE_PROTOCOL_AUTHORED_RULES, previous.size)
        assertEquals(DetektCorpusCohort.PRE_PROTOCOL_AUTHORED_ANALYSES, previous.sumOf { 6 + it.controls.size })
        assertEquals(11, previous.count { it.activation == "advisory" })
        assertEquals(5, previous.count { it.activation == "opt_in" })
        val priorIds = DetektCorpusCohort.preObservability.flatMap { it.analysesByRule.keys }.toSet()
        val prior = approved.filter { it.ruleId in priorIds }
        assertEquals(DetektCorpusCohort.PRE_OBSERVABILITY_AUTHORED_RULES, prior.size)
        assertEquals(DetektCorpusCohort.PRE_OBSERVABILITY_AUTHORED_ANALYSES, prior.sumOf { 6 + it.controls.size })
        assertEquals(13, prior.count { it.activation == "advisory" })
        assertEquals(6, prior.count { it.activation == "opt_in" })
        val preLocalIds = DetektCorpusCohort.preLocalShapes.flatMap { it.analysesByRule.keys }.toSet()
        val preLocal = approved.filter { it.ruleId in preLocalIds }
        assertEquals(DetektCorpusCohort.PRE_LOCAL_SHAPES_AUTHORED_RULES, preLocal.size)
        assertEquals(DetektCorpusCohort.PRE_LOCAL_SHAPES_AUTHORED_ANALYSES, preLocal.sumOf { 6 + it.controls.size })
        assertEquals(13, preLocal.count { it.activation == "advisory" })
        assertEquals(9, preLocal.count { it.activation == "opt_in" })
        val corpus = cohort.analysesByRule.keys.map { id -> approved.single { it.ruleId == id } }
        for (fixture in corpus) assertEquals(cohort.analysesByRule.getValue(fixture.ruleId), 6 + fixture.controls.size)
        assertEquals(corpus.any { it.activation == "opt_in" }, cohort.requiresExplicitOwnerOptIn)
        val notRun = approved.filter { it.ruleId !in cohort.analysesByRule }
        assertEquals(DetektCorpusCohort.FULL_AUTHORED_RULES - corpus.size, notRun.size)
        val controlReference = artifact(run, "selected-cohort-controls.json", buildJsonObject {
            put("cohort", cohort.name); put("cohort_plan_digest", Digests.sha256(cohort.planIdentity))
            put("requires_explicit_owner_opt_in", cohort.requiresExplicitOwnerOptIn)
            put("selected_opt_in_rule_ids", JsonArray(corpus.filter { it.activation == "opt_in" }.map { JsonPrimitive(it.ruleId) }))
            put("selected_rule_ids", JsonArray(corpus.map { JsonPrimitive(it.ruleId) }))
            put("not_run_rule_ids", JsonArray(notRun.map { JsonPrimitive(it.ruleId) }))
            put("full_corpus_state", "not_run"); put("scope_exclusions", JsonArray(emptyList()))
            put("approved_corpus_rule_count", DetektCorpusCohort.FULL_AUTHORED_RULES)
            put("approved_corpus_analysis_count", DetektCorpusCohort.FULL_AUTHORED_ANALYSES)
            put("planned_invocations", cohort.plannedInvocations); put("budget", limits.summary())
        }.toString().toByteArray())
        limits.requireWithinDeadline()
        val javaHome = Path.of(System.getProperty("java.home"))
        val installation = DetektInstallation(javaHome.resolve("bin/java"), repository.resolve(".plan-deps/detekt"), javaHome)
        DetektCatalog.verifyArtifacts(installation.artifactDirectory)
        artifact(run, "completion-started.json", "{\"state\":\"started_unfinished\",\"qualified\":false}".toByteArray())
        val witness = beginWitness(run, installation)
        startWitness = witness
        var versionEvidence: List<JsonObject>? = null
        val rows = mutableListOf<JsonObject>()
        for (fixture in corpus) {
            val identity = SnapshotIdentity("detekt-corpus-owner", cohort.contextId, fixture.slug)
            val store = SnapshotStore(temporary.resolve("store-${fixture.slug}"), identity.ownerId)
            store.registerWorktree(identity)
            var head: SnapshotCommit? = null
            var previousPaths = emptySet<String>()
            var parentMaterialization: MaterializedSnapshot? = null
            val cases = mutableListOf<JsonObject>()
            val standard = listOf(
                Case("positive", "positive", fixture.positive),
                Case("negative", "negative", fixture.negative),
                Case("configuration", "configuration", fixture.positive.copy(expected = emptyList()), disabled = true),
                Case("version", "version", fixture.positive),
                Case("suppression", "suppression", fixture.suppression),
                Case("regression", "regression", fixture.negative),
            )
            for (case in standard + fixture.controls) {
                limits.requireWithinDeadline()
                witness.verify()
                val changes = case.fixture.sources.map { (path, text) ->
                    SnapshotChange.PutFile(SnapshotPath(path = path), ImmutableBlob.utf8(text))
                } + (previousPaths - case.fixture.sources.keys).map { SnapshotChange.Delete(SnapshotPath(path = it)) }
                val previous = head
                val commit = store.sealOverlay(OverlayRequest(identity, "fixture", "syntax-corpus-v1", case.id,
                    previous?.snapshotId, previous?.headRevision ?: 0, changes))
                if (case.id == "regression") {
                    assertEquals(previous?.snapshotId, commit.manifest.parentSnapshotId)
                    assertNotEquals(previous?.snapshotId, commit.snapshotId)
                    assertTrue((previousPaths - case.fixture.sources.keys).isNotEmpty(), "Obsolete suppression file must be removed")
                }
                head = commit
                previousPaths = case.fixture.sources.keys
                val materialized = store.materialize(identity, commit.snapshotId, temporary.resolve("${fixture.slug}-${case.id}-sources"))
                val context = AnalysisContext(scope = RuleContext(module = ":${cohort.contextId}", sourceSet = "synthetic"),
                    compilerVersion = "2.4.21", compilationFiles = case.fixture.sources.keys.sorted())
                val settings = DetektSettings(disabledRuleIds = if (case.disabled) setOf(fixture.ruleId) else emptySet(),
                    options = if (case.fixture.options.isEmpty()) emptyMap() else mapOf(fixture.ruleId to case.fixture.options))
                var input = AnalysisInput(commit.toContractManifest(), materialized.root.toString(), context, listOf(fixture.ruleId),
                    outputRoot = temporary.resolve("${fixture.slug}-${case.id}-outputs").toString())
                val ticket = limits.reserve(CorpusInvocationKind.ANALYSIS, fixture.ruleId, case.id)
                val plan = DetektProvider(installation, settings = settings).executionPlan(input, limits.childTimeoutMillis(ticket))
                assertEquals("light", plan.analysisMode)
                assertEquals(if (case.disabled) emptySet() else setOf(fixture.ruleId), plan.activeRuleIds)
                input = input.copy(execution = plan.execution)
                val verify = { check(store.verifyMaterialization(materialized).valid); Unit }
                val authority = ExactExecutionAuthority(ExecutionPermit(plan.execution, commit.snapshotId, materialized.root,
                    input.analysisKey(), verify))
                val prefix = "cases/${fixture.slug}/${case.id}"
                val inputRef = artifact(run, "$prefix/input.json", normalized(ContractJson.encodeToString(input)))
                val sourceRefs = case.fixture.sources.keys.sorted().map { path ->
                    val bytes = Files.readAllBytes(materialized.root.resolve(safeRelativePath(path)))
                    assertEquals(input.snapshot.files.single { it.path == path }.digest, Digests.sha256(bytes))
                    artifact(run, "$prefix/sources/$path", bytes)
                }
                var observed: ProcessOutcome? = null
                var processRef: JsonObject? = null
                val result = trackedCall {
                    DetektProvider(installation, authority, settings, IsolatedProcessRunner { actual ->
                        observed = actual
                        processRef = artifact(run, "$prefix/process.json", processJson(actual).toString().toByteArray())
                        limits.observeProcess(ticket, actual, witness.supervisorIdentity)
                    }).analyze(input, limits.cancellation)
                }
                val resultRef = artifact(run, "$prefix/provider-result.json", normalized(ContractJson.encodeToString(result)))
                val configRef = artifact(run, "$prefix/generated-config.yml", plan.configuration.toByteArray())
                val reportRef = observed?.let { actual ->
                    runCatching { actual.report("detekt.sarif") }.getOrNull()
                        ?.let { artifact(run, "$prefix/report.sarif", it) }
                }
                // Actual failed output is already retained; no passed receipt or live sidecar precedes assertions.
                val actual = assertNotNull(observed, "${fixture.ruleId}/${case.id} did not reach isolated runtime: ${result.reasons}")
                assertEquals("executed", actual.status, "${fixture.ruleId}/${case.id}: ${actual.reasons}")
                assertTrue(actual.reasons.isEmpty(), "A new call requires the runtime's validated native cleanup terminal, not a reusable permit")
                assertTrue(actual.executionConfirmed, "Executed corpus cases require the actual native exec confirmation")
                assertEquals(0, actual.exitCode, "${fixture.ruleId}/${case.id}: ${actual.stderr}")
                assertFalse(actual.cancelled || actual.timedOut || actual.truncated)
                assertSupervisorIdentity(actual, witness.supervisorIdentity)
                assertEquals("detekt", result.providerId)
                assertEquals("partial", result.status, "${fixture.ruleId}/${case.id}: ${result.reasons}; ${result.rawDiagnostics}")
                assertTrue("detekt_compatibility_tuple_unqualified" in result.reasons)
                assertTrue("project_compiler_2.4.21_differs_from_detekt_embedded_compiler" in result.reasons)
                assertFalse("unmapped_or_unrequested_upstream_diagnostic" in result.reasons)
                assertFalse(result.complete())
                assertTrue(result.coverage.all { !it.tested && !it.qualified && it.certificateId == null && !it.successful() })
                val coverage = result.coverage.single()
                assertEquals(fixture.ruleId, coverage.ruleId)
                assertEquals(input.context.scope, coverage.context)
                assertEquals(if (case.disabled) "not_executed" else "executed", coverage.provenance)
                val selectedSarifId = DetektCatalog.byId.getValue(fixture.ruleId).sarifId
                val parsedReport = DetektReportParser().parse(actual.report("detekt.sarif"), input)
                assertEquals(!case.disabled, selectedSarifId in parsedReport.enabledSarifIds,
                    "An enabled negative needs its exact advertised descriptor; empty results are insufficient")
                assertEquals(if (case.fixture.expected.isEmpty()) "unknown" else "findings", coverage.state)
                if (!case.disabled) assertEquals("exact_upstream_executed_but_compatibility_and_release_qualification_missing", coverage.reason)
                if (case.disabled) {
                    assertEquals("rule_disabled_by_owner_selected_configuration", coverage.reason)
                    assertFalse(coverage.implemented)
                } else assertTrue(coverage.implemented, "The selected upstream rule must actually be active, even for negatives")
                assertFindings(fixture, case.fixture, result, input)
                assertTrue(store.verifyMaterialization(materialized).valid, "Lint-only process rewrote immutable source bytes")
                if (case.id == "regression") {
                    assertTrue(store.verifyMaterialization(assertNotNull(parentMaterialization)).valid, "Editing a child changed its registered parent")
                }
                witness.verify()
                val analysisRefs = listOf(inputRef, resultRef, configRef, assertNotNull(processRef), assertNotNull(reportRef)) + sourceRefs
                val analysisChecked = artifact(run, "$prefix/analysis-checked.json", buildJsonObject {
                    put("case_id", case.id); put("rule_id", fixture.ruleId); put("passed", true); put("qualified", false)
                    put("execution_confirmed", actual.executionConfirmed)
                    put("selected_sarif_rule_id", selectedSarifId)
                    put("selected_sarif_descriptor_enabled", selectedSarifId in parsedReport.enabledSarifIds)
                    put("supervisor_identity", actualSupervisorIdentityJson(actual.supervisorIdentity))
                    put("cleanup_signal", "runtime_validated_owned_child_terminal")
                    put("note", "Actual executed/empty-reasons runtime outcome validates cleanup; a permit or confirmation flag alone does not.")
                    put("evidence", JsonArray(analysisRefs))
                }.toString().toByteArray())
                limits.checked(ticket, analysisRefs + analysisChecked)
                val additional = mutableListOf(analysisChecked)
                if (case.id == "positive") {
                    val replayTicket = limits.reserve(CorpusInvocationKind.AUTHORIZATION_DENIAL, fixture.ruleId, "configuration-permit-replay")
                    val disabled = DetektSettings(disabledRuleIds = setOf(fixture.ruleId))
                    assertNotEquals(plan.execution, DetektProvider(installation, settings = disabled)
                        .executionPlan(input, plan.execution.timeoutMillis, plan.execution.maxOutputBytes).execution)
                    var replayOutcome: ProcessOutcome? = null
                    var denialRef: JsonObject? = null
                    val replay = trackedCall {
                        DetektProvider(installation, authority, disabled, IsolatedProcessRunner { denied ->
                            replayOutcome = denied
                            denialRef = artifact(run, "$prefix/configuration-permit-replay-denial-process.json",
                                processJson(denied).toString().toByteArray())
                            limits.observeProcess(replayTicket, denied, witness.supervisorIdentity)
                        }).analyze(input, limits.cancellation)
                    }
                    val replayRef = artifact(run, "$prefix/configuration-permit-replay.json", normalized(ContractJson.encodeToString(replay)))
                    additional += replayRef
                    val denied = assertNotNull(replayOutcome, "Runtime completion callback must retain the authorization denial")
                    assertAuthorizationDenied(denied)
                    assertEquals("detekt", replay.providerId)
                    assertEquals("partial", replay.status)
                    assertEquals(listOf("execution_not_authorized"), replay.reasons)
                    assertTrue(replay.findings.isEmpty())
                    val replayCoverage = replay.coverage.single()
                    assertEquals(fixture.ruleId, replayCoverage.ruleId)
                    assertEquals(input.context.scope, replayCoverage.context)
                    assertEquals("unavailable", replayCoverage.state)
                    assertEquals("execution_not_authorized", replayCoverage.reason)
                    assertNull(replayCoverage.certificateId)
                    assertTrue(replayCoverage.provenance == "not_executed" && !replayCoverage.implemented && !replayCoverage.tested &&
                        !replayCoverage.qualified && !replayCoverage.successful())
                    val denialProcess = assertNotNull(denialRef)
                    additional += denialProcess
                    val denialReceipt = artifact(run, "$prefix/configuration-permit-replay-denial-receipt.json", buildJsonObject {
                        put("case_kind", "changed_configuration_permit_replay"); put("passed", true); put("qualified", false)
                        put("execution_confirmed", denied.executionConfirmed); put("supervisor_identity", actualSupervisorIdentityJson(denied.supervisorIdentity))
                        put("actual_denial", processJson(denied)); put("process", denialProcess); put("provider_result", replayRef)
                        put("note", "Actual runtime callback is refusal before process/output setup, not a started provider or exec-unconfirmed fork.")
                    }.toString().toByteArray())
                    additional += denialReceipt
                    witness.verify()
                    limits.checked(replayTicket, listOf(replayRef, denialProcess, denialReceipt))
                }
                if (case.id == "version") {
                    if (versionEvidence == null) versionEvidence = versionProbe(run, input, plan, verify, witness)
                    additional += assertNotNull(versionEvidence)
                    for (target in listOf("common", "native")) {
                        val abstentionTicket = limits.reserve(CorpusInvocationKind.ABSTENTION, fixture.ruleId, "unsupported-$target")
                        val request = input.copy(context = context.copy(scope = context.scope.copy(target = target)))
                        val permit = ExactExecutionAuthority(ExecutionPermit(plan.execution, commit.snapshotId, materialized.root,
                            request.analysisKey(), verify))
                        var unsupportedOutcome: ProcessOutcome? = null
                        val abstained = trackedCall {
                            DetektProvider(installation, permit, settings, IsolatedProcessRunner { unexpected ->
                                unsupportedOutcome = unexpected
                                additional += artifact(run, "$prefix/$target-unexpected-process.json",
                                    processJson(unexpected).toString().toByteArray())
                            }).analyze(request, limits.cancellation)
                        }
                        val abstentionRef = artifact(run, "$prefix/$target-provider-result.json", normalized(ContractJson.encodeToString(abstained)))
                        additional += abstentionRef
                        limits.observeAbstention(abstentionTicket, abstained, unsupportedOutcome)
                        assertNull(unsupportedOutcome, "$target must not start a JVM syntax process")
                        assertEquals("detekt", abstained.providerId)
                        assertEquals("partial", abstained.status)
                        assertEquals(listOf("detekt_jvm_provider_does_not_qualify_common_or_native_targets"), abstained.reasons)
                        assertTrue(abstained.findings.isEmpty())
                        val unsupportedCoverage = abstained.coverage.single()
                        assertEquals(fixture.ruleId, unsupportedCoverage.ruleId)
                        assertEquals(request.context.scope, unsupportedCoverage.context)
                        assertEquals("unsupported", unsupportedCoverage.state)
                        assertNull(unsupportedCoverage.certificateId)
                        assertTrue(abstained.coverage.all { it.provenance == "not_executed" && !it.successful() })
                        witness.verify()
                        val abstentionReceipt = artifact(run, "$prefix/$target-abstention-receipt.json", buildJsonObject {
                            put("case_kind", "unsupported_target_abstention"); put("target", target)
                            put("rule_id", fixture.ruleId); put("passed", true); put("qualified", false)
                            put("runtime_callback_observed", false); put("provider_result", abstentionRef)
                            put("cleanup_signal", "provider_abstention_without_runtime_callback")
                        }.toString().toByteArray())
                        additional += abstentionReceipt
                        limits.checked(abstentionTicket, listOf(abstentionRef, abstentionReceipt))
                    }
                }
                witness.verify()
                val refs = listOf(inputRef, resultRef, configRef, assertNotNull(processRef), assertNotNull(reportRef)) + sourceRefs + additional
                val receipt = artifact(run, "$prefix/receipt.json", buildJsonObject {
                    put("schema_version", 1); put("case_id", case.id); put("case_kind", case.kind); put("passed", true)
                    put("provider_id", "detekt"); put("provider_version", DETEKT_VERSION); put("rule_id", fixture.ruleId)
                    put("upstream_id", fixture.upstreamId); put("actual_exit_code", actual.exitCode); put("actual_provider_status", result.status)
                    put("execution_confirmed", actual.executionConfirmed)
                    put("supervisor_identity", actualSupervisorIdentityJson(actual.supervisorIdentity))
                    put("snapshot_id", commit.snapshotId); put("parent_snapshot_id", commit.manifest.parentSnapshotId)
                    put("analysis_key", input.analysisKey()); put("configuration_sha256", plan.configurationDigest)
                    put("analysis_mode", plan.analysisMode); put("registered_materialization_verified", true)
                    put("source_rewrites", false); put("qualified", false); put("authored_intent", case.fixture.intent)
                    put("authored_assessment", case.assessment)
                    put("assessment_scope", "Actual syntax result plus authored legal-alternative intent; not compiler/API identity or precision qualification.")
                    if (case.id == "suppression") put("suppression_scope", "upstream_file_annotation_with_unsuppressed_neighbor")
                    put("findings", JsonArray(result.findings.map { ContractJson.encodeToJsonElement(it.location) }))
                    put("expected_findings", JsonArray(case.fixture.expected.map { it.toJson() }))
                    put("artifacts", JsonArray(refs)); put("start_witness", witness.manifestReference)
                    put("artifact_pins", JsonArray(plan.artifacts.map { ContractJson.encodeToJsonElement(it.copy(path = "{pinned-installation}/${Path.of(it.path).fileName}")) }))
                    put("command_template", JsonArray((listOf(plan.execution.executable) + plan.execution.arguments).map(::JsonPrimitive)))
                }.toString().toByteArray())
                cases += buildJsonObject {
                    put("kind", case.kind); put("case_id", case.id); put("passed", true)
                    put("evidence", JsonArray(refs + receipt)); put("assessment", case.assessment)
                }
                parentMaterialization = materialized
            }
            rows += buildJsonObject {
                put("rule_id", fixture.ruleId); put("implemented", true); put("tested", true); put("qualified", false)
                put("provider_status", "executed_unqualified"); put("implementation_evidence", JsonArray(witness.references + controlReference))
                put("cases", JsonArray(cases)); put("qualification_evidence", JsonArray(emptyList()))
                put("gaps", JsonArray((fixture.limitations + listOf(
                    "Independent synthetic light PSI corpus only; no whole-project symbol/CFG/API, compiler compatibility, precision, performance or release qualification.",
                    "Known heuristic-gap and opt-in policy controls retain all genuine findings; tested does not imply valid universal defect semantics.",
                    "File-scoped upstream suppression only; arbitrary ktChecker-ID aliases and declaration suppression are not attested by this corpus.",
                    "START source/resource archives and active class-resource/project code images do not independently prove a reproducible source-to-loaded-bytecode build.",
                )).map(::JsonPrimitive)))
            }
        }
        for (fixture in notRun) rows += buildJsonObject {
            put("rule_id", fixture.ruleId); put("implemented", false); put("tested", false); put("qualified", false)
            put("attempted", false); put("provider_status", "not_run")
            put("implementation_evidence", JsonArray(emptyList())); put("cases", JsonArray(emptyList()))
            put("qualification_evidence", JsonArray(emptyList()))
            put("gaps", JsonArray((fixture.limitations +
                "Full thirty-one-rule/287-analysis authoring scope retained; prior twenty-two/199, nineteen/172 and sixteen/145 remain intact. This rule is not_run, not a scope exclusion or borrowed execution.").map(::JsonPrimitive)))
        }
        witness.verify()
        owned.verifyDirectories()
        val checkedBudget = limits.finish()
        val budgetReference = artifact(run, "checked-cohort-budget.json", checkedBudget.toString().toByteArray())
        val sidecar = buildJsonObject {
            put("schema_version", 1); put("provider_id", "detekt"); put("provider_version", DETEKT_VERSION)
            put("candidate_only", true); put("run_complete", false); put("publication_committed", false)
            put("context", buildJsonObject {
                put("id", "${cohort.contextId}-native-preview1"); put("target", "jvm"); put("source_set", "synthetic")
                put("corpus_cohort", cohort.name)
                put("cohort_plan_digest", Digests.sha256(cohort.planIdentity))
                put("corpus_source_tuple_digest", witness.sourceTupleDigest)
                put("corpus_code_image_digest", witness.corpusImageDigest)
                put("corpus_fixture_set_digest", witness.fixtureSetDigest)
                put("invocation_ceiling", settings.maxInvocations); put("aggregate_limit_millis", settings.aggregateMillis)
                put("child_limit_millis", settings.childMillis)
                put("execution_backend", "owned_child_native_supervisor_preview1")
                put("supervisor_protocol_version", witness.supervisorIdentity.protocolVersion)
                put("supervisor_target", witness.supervisorIdentity.target)
                put("supervisor_source_digest", witness.supervisorIdentity.sourceDigest)
                put("supervisor_binary_digest", witness.supervisorIdentity.binaryDigest)
                put("runtime_execution_source_digest", witness.runtimeSourceDigest)
                put("runtime_code_image_digest", witness.runtimeImageDigest)
                put("native_qualification", false)
                put("module", ":${cohort.contextId}"); put("compilation", "main"); put("analysis_mode", "light")
                put("kotlin_version", "2.4.21"); put("compiler_version", "2.4.21"); put("embedded_kotlin_version", DETEKT_EMBEDDED_KOTLIN)
                put("jdk_version", System.getProperty("java.version")); put("library_versions", buildJsonObject {})
                put("scope_note", "${corpus.size} actually checked ${cohort.name} syntax routes only. Full thirty-one/287 authoring scope remains not_run; prior twenty-two/199, nineteen/172 and sixteen/145 are intact and ${notRun.size} other rows are not exclusions. Unqualified.")
            })
            put("start_witness", witness.manifestReference); put("rules", JsonArray(rows))
            put("approved_corpus_rule_count", DetektCorpusCohort.FULL_AUTHORED_RULES); put("approved_corpus_analysis_count", DetektCorpusCohort.FULL_AUTHORED_ANALYSES); put("full_corpus_state", "not_run")
            put("selected_cohort_rule_count", corpus.size); put("not_run_rule_ids", JsonArray(notRun.map { JsonPrimitive(it.ruleId) }))
            put("scope_exclusions", JsonArray(emptyList())); put("cohort_controls", controlReference)
            put("checked_budget", budgetReference); put("progress_evidence", JsonArray(progressReferences))
        }
        val finished = artifact(run, "candidate-sidecar.json", sidecar.toString().toByteArray())
        artifact(run, "completion-assertions-checked.json", buildJsonObject {
            put("state", "selected_cohort_invocation_assertions_checked_candidate"); put("captured_at", Instant.now().toString())
            put("candidate_only", true); put("run_complete", false); put("publication_committed", false)
            put("tested_rules", corpus.size); put("not_run_rules", notRun.size); put("sidecar", finished); put("qualified", false)
            put("checked_budget", budgetReference); put("progress_evidence", JsonArray(progressReferences))
            put("note", "JUnit/build logs require separately retained real task completion; this local witness is not a release certificate.")
        }.toString().toByteArray())
        limits.requireWithinDeadline()
        // No live sidecar until the successful-test gate plus final drift/deadline checks.
        pendingSidecar = sidecar
    }

    private fun assertFindings(fixture: CorpusFixture, expected: FixtureCase, result: ProviderResult, input: AnalysisInput) {
        assertTrue(result.findings.all { it.ruleId == fixture.ruleId && it.upstreamId == fixture.upstreamId })
        fun key(location: SourceLocation) = listOf(location.path, location.line.toString(), location.column.toString(),
            location.endLine.toString(), location.endColumn.toString()).joinToString(":")
        val actual = result.findings.map { key(it.location) }.sorted()
        val desired = expected.expected.map { "${it.path}:${it.line}:${it.column}:${it.endLine}:${it.endColumn}" }.sorted()
        assertEquals(desired, actual, "${fixture.ruleId}: retain exact independent start/end ranges, not a relaxed count")
        for (finding in result.findings) {
            assertEquals(input.snapshot.files.single { it.path == finding.location.path }.digest, finding.location.fileDigest)
        }
    }

    private fun versionProbe(run: Path, input: AnalysisInput, plan: DetektExecutionPlan, verify: () -> Unit,
        witness: Witness): List<JsonObject> {
        val limits = assertNotNull(budget)
        val ticket = limits.reserve(CorpusInvocationKind.CLI_PROBE, input.requiredRuleIds.single(), "cli-version-probe")
        val cli = plan.artifacts.single { it.coordinate == "dev.detekt:detekt-cli:$DETEKT_VERSION:all" }.path
        val execution = plan.execution.copy(arguments = listOf("-Xmx128m", "-jar", cli, "--version"),
            timeoutMillis = limits.childTimeoutMillis(ticket))
        val probeInput = input.copy(execution = execution, outputRoot = temporary.resolve("version-cli").toString())
        val authority = ExactExecutionAuthority(ExecutionPermit(execution, input.snapshot.snapshotId, Path.of(input.materializedRoot),
            probeInput.analysisKey(), verify))
        var processRef: JsonObject? = null
        val actual = trackedCall {
            IsolatedProcessRunner { observed ->
                processRef = artifact(run, "version/process.json", processJson(observed).toString().toByteArray())
                limits.observeProcess(ticket, observed, witness.supervisorIdentity)
            }.run(probeInput, authority, limits.cancellation)
        }
        val process = assertNotNull(processRef)
        assertEquals("executed", actual.status, actual.reasons.joinToString())
        assertTrue(actual.reasons.isEmpty())
        assertTrue(actual.executionConfirmed, "The CLI version probe requires actual native exec confirmation")
        assertFalse(actual.cancelled || actual.timedOut || actual.truncated)
        assertEquals(0, actual.exitCode)
        assertEquals(DETEKT_VERSION, actual.stdout.trim())
        assertSupervisorIdentity(actual, witness.supervisorIdentity)
        verify()
        witness.verify()
        val receipt = artifact(run, "version/receipt.json", buildJsonObject {
            put("actual_exit_code", actual.exitCode); put("stdout", actual.stdout); put("stderr", actual.stderr)
            put("execution_confirmed", actual.executionConfirmed)
            put("supervisor_identity", actualSupervisorIdentityJson(actual.supervisorIdentity))
            put("declared_project_compiler", "2.4.21"); put("embedded_compiler_pin", DETEKT_EMBEDDED_KOTLIN)
            put("compatibility_qualified", false); put("process", process)
        }.toString().toByteArray())
        limits.checked(ticket, listOf(process, receipt))
        return listOf(process, receipt)
    }

    private fun <T> trackedCall(operation: () -> T): T = try { operation() }
        catch (cause: Throwable) { budget?.failure(cause); throw cause }

    private fun processJson(actual: ProcessOutcome): JsonObject = buildJsonObject {
        put("status", actual.status); put("exit_code", actual.exitCode)
        put("execution_confirmed", actual.executionConfirmed)
        put("executed", actual.executed); put("successful_execution", actual.successfulExecution)
        put("output_directory", actual.outputDirectory?.toString()?.replace(temporary.toString(), "{fixture-temporary}"))
        put("stdout", actual.stdout.replace(temporary.toString(), "{fixture-temporary}"))
        put("stderr", actual.stderr.replace(temporary.toString(), "{fixture-temporary}"))
        put("reasons", JsonArray(actual.reasons.map(::JsonPrimitive))); put("cancelled", actual.cancelled)
        put("timed_out", actual.timedOut); put("truncated", actual.truncated)
        put("metrics", ContractJson.encodeToJsonElement(actual.metrics))
        put("command", JsonArray(actual.command.map { JsonPrimitive(it.replace(temporary.toString(), "{fixture-temporary}")) }))
        put("supervisor_identity", actualSupervisorIdentityJson(actual.supervisorIdentity))
    }

    private fun actualSupervisorIdentityJson(identity: OwnedSupervisorIdentity?): JsonElement =
        identity?.let { value -> buildJsonObject {
            put("protocol_version", value.protocolVersion); put("target", value.target)
            put("source_digest", value.sourceDigest); put("binary_digest", value.binaryDigest)
            put("identity_origin", "actual_launched_helper_result_not_inspector")
            put("native_qualification", false)
        } } ?: JsonNull

    private fun assertSupervisorIdentity(actual: ProcessOutcome, expected: OwnedSupervisorIdentity) {
        val identity = assertNotNull(actual.supervisorIdentity, "Executed corpus must retain the actual owned-child helper identity")
        assertEquals("ktchecker-owned-child-supervisor/preview-1", identity.protocolVersion)
        assertTrue(identity.target.isNotBlank())
        assertTrue(Digests.valid(identity.sourceDigest) && Digests.valid(identity.binaryDigest))
        assertEquals(expected, identity, "The actual launched helper must match the installed manifest/binary retained at START")
    }

    private fun assertAuthorizationDenied(actual: ProcessOutcome) {
        assertEquals("unavailable", actual.status)
        assertEquals(listOf("execution_not_authorized"), actual.reasons)
        assertFalse(actual.executionConfirmed || actual.executed || actual.successfulExecution)
        assertNull(actual.supervisorIdentity); assertNull(actual.exitCode); assertNull(actual.outputDirectory)
        assertTrue(actual.command.isEmpty()); assertEquals("", actual.stdout); assertEquals("", actual.stderr)
        assertFalse(actual.cancelled || actual.timedOut || actual.truncated)
        assertEquals(0L, actual.metrics.outputBytes)
        assertNull(actual.metrics.cpuMillis); assertNull(actual.metrics.peakRssBytes)
    }

    private fun normalized(value: String): ByteArray = value.replace(temporary.toString(), "{fixture-temporary}").toByteArray()

    private fun artifact(run: Path, relative: String, bytes: ByteArray): JsonObject {
        val owned = assertNotNull(evidence)
        require(run == owned.runDirectory)
        return owned.artifact(relative, bytes)
    }

    private fun reference(path: Path): JsonObject = buildJsonObject {
        put("path", repository.relativize(path).toString().replace('\\', '/')); put("sha256", fileDigest(path).removePrefix("sha256:"))
    }

    private data class Witness(val references: List<JsonObject>, val manifestReference: JsonObject,
        val supervisorIdentity: OwnedSupervisorIdentity, val runtimeSourceDigest: String, val runtimeImageDigest: String,
        val sourceTupleDigest: String, val corpusImageDigest: String, val fixtureSetDigest: String, val verify: () -> Unit)

    private fun beginWitness(run: Path, installation: DetektInstallation): Witness {
        inventory.verify()
        val sourceRoots = listOf("modules/provider-detekt/src/main", "modules/provider-detekt/src/corpusTest",
            "modules/provider-runtime/src/main", "modules/provider-runtime/native", "modules/contracts/src/main", "modules/snapshot/src/main",
            "providers/detekt-fixtures/corpus/syntax-v1", DetektCorpusInventory.EXPANSION, DetektCorpusInventory.PROTOCOL, DetektCorpusInventory.OBSERVABILITY, DetektCorpusInventory.LOCAL_SHAPES).map(repository::resolve)
        val buildFiles = listOf("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradle/wrapper/gradle-wrapper.properties",
            "modules/provider-detekt/build.gradle.kts", "modules/provider-runtime/build.gradle.kts",
            "modules/contracts/build.gradle.kts", "modules/snapshot/build.gradle.kts",
            "providers/detekt-fixtures/test_syntax_corpus.py", "providers/detekt-fixtures/test_syntax_expansion.py",
            "providers/detekt-fixtures/test_syntax_protocol.py", "providers/detekt-fixtures/test_syntax_observability.py",
            "providers/detekt-fixtures/test_syntax_local_shapes.py",
            "providers/detekt-fixtures/corpus/syntax-v1/README.md")
            .map(repository::resolve)
        fun sourceFiles(): List<Path> = (sourceRoots.flatMap { directory ->
            Files.walk(directory).use { paths -> paths.filter {
                Files.isRegularFile(it, NOFOLLOW_LINKS) && it.none { part -> part.toString() == "__pycache__" }
            }.sorted().toList() }
        } + buildFiles.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }).distinct()
        val sources = sourceFiles().associateWith { Files.readAllBytes(it) }
        val sourceRefs = sources.flatMap { (path, bytes) ->
            val archived = artifact(run, "at-start/${repository.relativize(path).toString().replace('\\', '/')}", bytes)
            listOf(reference(path), archived)
        }
        val nativeAnchor = Class.forName("dev.ktchecker.providers.runtime.OwnedProcessSupervisor", false, IsolatedProcessRunner::class.java.classLoader)
        val types = listOf(DetektSyntaxCorpusIntegrationTest::class.java, DetektProvider::class.java, DetektCatalog::class.java,
            DetektCorpusInventory::class.java, DetektCorpusExpansionPins::class.java, DetektCorpusProtocolPins::class.java, DetektCorpusObservabilityPins::class.java, DetektCorpusLocalShapesPins::class.java, CorpusInvocationSpec::class.java,
            DetektCorpusCohort::class.java, DetektCorpusBudget::class.java, DetektCorpusSettings::class.java, DetektCorpusEvidence::class.java,
            DetektReportParser::class.java, IsolatedProcessRunner::class.java, ExecutionPermit::class.java,
            ExactExecutionAuthority::class.java, OwnedProcessContainment::class.java, OwnedSupervisorIdentity::class.java, nativeAnchor,
            SnapshotStore::class.java, ArtifactIdentity::class.java)
        fun classBytes(type: Class<*>): ByteArray = type.getResourceAsStream("/${type.name.replace('.', '/')}.class")!!.use { it.readBytes() }
        val classes = types.associateWith(::classBytes)
        val classRefs = classes.map { (type, bytes) -> artifact(run, "code-images/${type.simpleName}.class", bytes) }
        val codeLocations = types.map { Path.of(it.protectionDomain.codeSource.location.toURI()).toAbsolutePath().normalize() }.distinct()
        val images = codeLocations.associateWith { projectCodeImage(it) }
        val imageRefs = images.entries.mapIndexed { index, (path, bytes) ->
            val suffix = if (Files.isDirectory(path, NOFOLLOW_LINKS)) "${path.fileName}.zip" else path.fileName.toString()
            artifact(run, "code-images/module-$index-$suffix", bytes)
        }
        // Read the exact installed code-source resources, not inspector output or first-match classpath resources.
        val runtimeOrigin = nativeAnchor.protectionDomain?.codeSource?.location ?: error("Actual helper loader has no installed CodeSource")
        require(runtimeOrigin.protocol == "file" && runtimeOrigin.query == null && runtimeOrigin.ref == null)
        val runtimeLocation = Path.of(runtimeOrigin.toURI())
            .toAbsolutePath().normalize()
        val manifestName = OwnedProcessContainment.MANIFEST_RESOURCE.removePrefix("/")
        val nativeManifest = installedRuntimeResource(runtimeLocation, manifestName, 512)
        val lines = nativeManifest.toString(Charsets.UTF_8).split('\n')
        require(lines.size == 5 && lines.last().isEmpty() && lines[0] == "ktchecker-owned-child-supervisor/preview-1")
        require(lines[1].matches(Regex("(linux|macos)-(x86_64|aarch64)")))
        require(Digests.valid(lines[2]) && Digests.valid(lines[3]))
        val supervisorIdentity = OwnedSupervisorIdentity(lines[0], lines[1], lines[2], lines[3])
        assertEquals(supervisorIdentity.sourceDigest, Digests.sha256(sources.getValue(repository.resolve("modules/provider-runtime/native/owned_process_supervisor.c"))),
            "The build manifest C source claim must match captured START source bytes; not reproducible-build proof")
        val binaryName = manifestName.substringBeforeLast('/') + "/${supervisorIdentity.target}/supervisor"
        val nativeBinary = installedRuntimeResource(runtimeLocation, binaryName, 1_048_576)
        assertEquals(supervisorIdentity.binaryDigest, Digests.sha256(nativeBinary))
        val provenanceName = manifestName.substringBeforeLast('/') + "/build-provenance.json"
        val nativeProvenance = installedRuntimeResource(runtimeLocation, provenanceName, 16_384)
        require(Json.parseToJsonElement(nativeProvenance.toString(Charsets.UTF_8)).jsonObject["schemaVersion"]?.jsonPrimitive?.content ==
            "ktchecker-owned-child-supervisor-build/1")
        val nativeRefs = listOf(
            artifact(run, "native-image/manifest", nativeManifest),
            artifact(run, "native-image/${supervisorIdentity.target}/supervisor", nativeBinary),
            artifact(run, "native-image/build-provenance.json", nativeProvenance),
        ) + listOf(manifestName, binaryName, provenanceName).map { name ->
            reference(if (Files.isDirectory(runtimeLocation, NOFOLLOW_LINKS))
                runtimeLocation.parent.parent.parent.resolve("resources/main/$name") else runtimeLocation)
        }.distinct()
        val toolPins = DetektCatalog.verifyArtifacts(installation.artifactDirectory)
        val javaDigest = fileDigest(installation.javaExecutable)
        val toolRef = artifact(run, "tool-start-witness.json", buildJsonObject {
            put("schema_version", 1); put("captured_at", Instant.now().toString()); put("qualified", false)
            put("artifacts", JsonArray(toolPins.map { ContractJson.encodeToJsonElement(it.copy(
                path = "{pinned-installation}/${Path.of(it.path).fileName}")) }))
            put("java_executable_digest", javaDigest); put("java_runtime_version", System.getProperty("java.runtime.version"))
            put("attribution", "Actual installed engine and launcher file digests checked at START/END; upstream engine JARs and the full JDK are not redistributed or reproducibly attested.")
        }.toString().toByteArray())
        val refs = sourceRefs + classRefs + imageRefs + nativeRefs + toolRef
        val manifest = artifact(run, "start-witness.json", buildJsonObject {
            put("schema_version", 1); put("captured_at", Instant.now().toString()); put("qualified", false)
            put("references", JsonArray(refs))
            put("source_attribution", "Captured current bytes plus retained originals at START; never filled in from later source.")
            put("bytecode_attribution", "Active class-resource images and complete project class-directory/JAR files; no independent reproducible build or classloader-transform proof.")
            put("installed_supervisor_identity", buildJsonObject {
                put("protocol_version", supervisorIdentity.protocolVersion); put("target", supervisorIdentity.target)
                put("source_digest", supervisorIdentity.sourceDigest); put("binary_digest", supervisorIdentity.binaryDigest)
                put("identity_origin", "exact_installed_code_source_manifest_and_binary_at_START_not_inspector")
                put("source_digest_scope", "Installed build manifest claim; retained source bytes alone do not prove a reproducible source-to-binary build.")
                put("build_provenance_scope", "Bounded informational compiler-driver receipt only; no transitive toolchain or native platform qualification.")
                put("native_qualification", false)
            })
        }.toString().toByteArray())
        val runtimeSource = repository.resolve("modules/provider-runtime/src/main/kotlin/dev/ktchecker/providers/runtime/Execution.kt")
        val corpusLocation = Path.of(DetektSyntaxCorpusIntegrationTest::class.java.protectionDomain.codeSource.location.toURI())
            .toAbsolutePath().normalize()
        val sourceTupleDigest = Digests.sha256(sources.entries.sortedBy { repository.relativize(it.key).toString() }.joinToString("\n") {
            "${repository.relativize(it.key).toString().replace('\\', '/')}:${Digests.sha256(it.value)}"
        })
        val fixtureSetDigest = Digests.sha256(sources.entries.filter { (path, _) ->
            (path.startsWith(fixtures) || path.startsWith(expansionFixtures) || path.startsWith(protocolFixtures) || path.startsWith(observabilityFixtures) || path.startsWith(localShapeFixtures)) && path.toString().endsWith(".json") }
            .sortedBy { repository.relativize(it.key).toString() }.joinToString("\n") {
                "${repository.relativize(it.key).toString().replace('\\', '/')}:${Digests.sha256(it.value)}" })
        return Witness(refs + manifest, manifest, supervisorIdentity, Digests.sha256(sources.getValue(runtimeSource)),
            Digests.sha256(images.getValue(runtimeLocation)), sourceTupleDigest, Digests.sha256(images.getValue(corpusLocation)), fixtureSetDigest) {
            inventory.verify()
            assertEquals(sources.keys.toList(), sourceFiles(), "Source/resource/fixture membership drift after START")
            for ((path, bytes) in sources) assertContentEquals(bytes, Files.readAllBytes(path), "Source/resource/fixture drift after START: $path")
            for ((type, bytes) in classes) assertContentEquals(bytes, classBytes(type), "Class-resource drift after START: ${type.name}")
            for ((path, bytes) in images) assertContentEquals(bytes, projectCodeImage(path), "Project code image drift after START: $path")
            assertContentEquals(nativeManifest, installedRuntimeResource(runtimeLocation, manifestName, 512), "Installed helper manifest drift after START")
            assertContentEquals(nativeBinary, installedRuntimeResource(runtimeLocation, binaryName, 1_048_576), "Installed helper binary drift after START")
            assertContentEquals(nativeProvenance, installedRuntimeResource(runtimeLocation, provenanceName, 16_384), "Installed informational build receipt drift after START")
            assertEquals(toolPins, DetektCatalog.verifyArtifacts(installation.artifactDirectory), "Pinned engine image drift after START")
            assertEquals(javaDigest, fileDigest(installation.javaExecutable), "Selected Java executable drift after START")
        }
    }

    /** Mirror only the runtime's exact development-resource/JAR origin, without executing a helper. */
    private fun installedRuntimeResource(source: Path, name: String, maximum: Int): ByteArray {
        require(source.startsWith(repository) && !Files.isSymbolicLink(source))
        require(name.startsWith("dev/ktchecker/providers/runtime/native/") && maximum in 1..1_048_576)
        val relative = safeRelativePath(name)
        val bytes = if (Files.isDirectory(source, NOFOLLOW_LINKS)) {
            require(source.endsWith(Path.of("build/classes/kotlin/main")))
            val build = source.parent.parent.parent
            val path = build.resolve("resources/main").resolve(relative)
            var current = build
            for (part in build.relativize(path)) {
                current = current.resolve(part)
                require(!Files.isSymbolicLink(current))
                require(if (current == path) Files.isRegularFile(current, NOFOLLOW_LINKS) else Files.isDirectory(current, NOFOLLOW_LINKS))
            }
            require(Files.size(path) in 1..maximum.toLong())
            Files.newInputStream(path).use { it.readNBytes(maximum + 1) }
        } else {
            require(Files.isRegularFile(source, NOFOLLOW_LINKS) && source.toString().endsWith(".jar") && Files.size(source) <= 32_000_000)
            ZipFile(source.toFile()).use { archive ->
                require(archive.size() <= 100_000)
                val entries = archive.entries().asSequence().filter { it.name == name }.toList()
                require(entries.size == 1)
                val entry = entries.single()
                require(!entry.isDirectory && entry.size in 1..maximum.toLong())
                archive.getInputStream(entry).use { it.readNBytes(maximum + 1) }.also { require(it.size.toLong() == entry.size) }
            }
        }
        require(bytes.isNotEmpty() && bytes.size <= maximum)
        return bytes
    }

    /** No upstream engine is loaded in this JVM; archive only the project code locations already in use. */
    private fun projectCodeImage(directory: Path): ByteArray {
        require(directory.startsWith(repository) && !Files.isSymbolicLink(directory))
        if (Files.isRegularFile(directory, NOFOLLOW_LINKS)) {
            require(directory.toString().endsWith(".jar") && Files.size(directory) in 1..32_000_000L)
            return Files.newInputStream(directory).use { it.readNBytes(32_000_001) }
                .also { require(it.size in 1..32_000_000) }
        }
        require(Files.isDirectory(directory, NOFOLLOW_LINKS))
        val files = Files.walk(directory).use { paths ->
            paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) && it.toString().endsWith(".class") }
                .limit(10_001).sorted().toList()
        }
        require(files.isNotEmpty() && files.size <= 10_000)
        var total = 0L
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for (file in files) {
                val remaining = (32_000_000 - total).toInt()
                require(Files.size(file) in 1..remaining.toLong())
                val data = Files.newInputStream(file).use { it.readNBytes(remaining + 1) }
                total += data.size
                require(total <= 32_000_000)
                zip.putNextEntry(ZipEntry(directory.relativize(file).toString().replace('\\', '/')).apply { time = 0 })
                zip.write(data)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private data class Expected(val path: String, val line: Int, val column: Int, val endLine: Int, val endColumn: Int,
        val span: String, val occurrence: Int) {
        fun toJson() = buildJsonObject {
            put("path", path); put("line", line); put("column", column); put("end_line", endLine); put("end_column", endColumn)
            put("span", span); put("occurrence", occurrence)
        }
    }
    private data class FixtureCase(val sources: Map<String, String>, val expected: List<Expected>, val intent: String,
        val options: Map<String, JsonElement> = emptyMap())
    private data class Case(val id: String, val kind: String, val fixture: FixtureCase, val disabled: Boolean = false,
        val assessment: String = "independent_synthetic_case")
    private data class CorpusFixture(val ruleId: String, val upstreamId: String, val activation: String, val positive: FixtureCase,
        val negative: FixtureCase, val suppression: FixtureCase, val controls: List<Case>, val limitations: List<String>) {
        val slug: String get() = ruleId.substringAfterLast('.')
    }

    private fun loadCorpus(): List<CorpusFixture> {
        inventory = DetektCorpusInventory.load(repository)
        return inventory.fixtures.map { item ->
            require(item.number("schema_version") == 1 && item.string("authoring_state") == "unexecuted")
            require(item.string("corpus_id") == when (item.string("rule_id")) {
                in DetektCorpusLocalShapesPins.controlIds -> "detekt-syntax-local-shapes-v1"
                in DetektCorpusObservabilityPins.controlIds -> "detekt-syntax-exception-observability-v1"
                in DetektCorpusProtocolPins.controlIds -> "detekt-syntax-protocol-v1"
                in DetektCorpusExpansionPins.controlIds -> "detekt-syntax-expansion-v1"
                else -> "detekt-syntax-corpus-v1"
            })
            require(item.string("provider_id") == "detekt" && item.string("provider_version") == DETEKT_VERSION)
            require(item.string("embedded_kotlin_version") == DETEKT_EMBEDDED_KOTLIN)
            val rule = DetektCatalog.byId.getValue(item.string("rule_id"))
            require(rule.exact && rule.depth == "syntax" && rule.upstream_id == item.string("upstream_id"))
            require(rule.default_activation == item.string("default_activation") && rule.catalog_record_sha256 == item.string("catalog_record_sha256"))
            val controls = item["controls"]!!.jsonArray.map { value ->
                val control = value.jsonObject
                val id = control.string("id")
                require(id.matches(Regex("[a-z0-9-]+")) && id !in setOf("positive", "negative", "configuration", "version", "suppression", "regression"))
                val kind = control.string("case_kind")
                require(kind in setOf("configuration", "regression"))
                Case(id, kind, parseCase(control), assessment = control.string("assessment"))
            }
            require(controls.map { it.id }.distinct().size == controls.size)
            CorpusFixture(rule.rule_id, rule.upstream_id, rule.default_activation,
                parseCase(item["positive"]!!.jsonObject), parseCase(item["negative"]!!.jsonObject),
                parseCase(item["suppression"]!!.jsonObject), controls, item["limitations"]!!.jsonArray.map { it.jsonPrimitive.content })
        }.also { require(it.map { fixture -> fixture.ruleId }.distinct().size == it.size) }
    }

    private fun parseCase(item: JsonObject): FixtureCase {
        val sources = item["sources"]!!.jsonObject.mapValues { (_, value) ->
            value.jsonPrimitive.also { require(it.isString) }.content
        }
        require(sources.isNotEmpty())
        for ((path, source) in sources) {
            safeRelativePath(path)
            require(path.endsWith(".kt") && source.isNotEmpty() && source.endsWith('\n'))
        }
        val expected = item["expected"]!!.jsonArray.map { value ->
            val row = value.jsonObject
            Expected(row.string("path"), row.number("line"), row.number("column"), row.number("end_line"), row.number("end_column"),
                row.string("span"), row.number("occurrence")).also { exp ->
                val source = sources.getValue(exp.path)
                require(exp.span.isNotEmpty() && exp.occurrence >= 0)
                var offset = -exp.span.length
                repeat(exp.occurrence + 1) {
                    offset = source.indexOf(exp.span, offset + exp.span.length)
                    require(offset >= 0)
                }
                fun point(position: Int): Pair<Int, Int> = source.substring(0, position).let { prefix ->
                    prefix.count { it == '\n' } + 1 to prefix.substringAfterLast('\n').length
                }
                require(point(offset) == (exp.line to exp.column))
                require(point(offset + exp.span.length) == (exp.endLine to exp.endColumn))
            }
        }
        require(expected.distinct().size == expected.size)
        return FixtureCase(sources, expected, item.string("intent"), item["options"]?.jsonObject?.toMap().orEmpty())
    }

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.also { require(it.isString) }.content
    private fun JsonObject.number(name: String): Int = getValue(name).jsonPrimitive.also { require(!it.isString) }.int
}

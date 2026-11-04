package dev.ktchecker.providers.detekt

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import dev.ktchecker.snapshot.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.extension.TestWatcher
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** Separate source set/task: never renews the older pack's source witnesses or evidence. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DetektSyntaxCorpusIntegrationTest {
    @TempDir lateinit var temporary: Path
    private val repository: Path get() = Path.of(System.getProperty("ktchecker.repositoryRoot")).toAbsolutePath().normalize()
    private val fixtures: Path get() = repository.resolve("providers/detekt-fixtures/corpus/syntax-v1")
    private val enabled: Boolean get() = System.getProperty("ktchecker.detekt.corpus.integration") == "true"
    private val evidenceRoot: Path get() {
        val selected = System.getProperty("ktchecker.libraryFixtureEvidenceRoot")
            ?: return repository.resolve("providers/detekt-fixtures")
        val root = Path.of(selected).toAbsolutePath().normalize().resolve("detekt")
        require(root.startsWith(repository.resolve("build"))) { "CI evidence root must be inside repository build/" }
        var ancestor = repository
        for (part in repository.relativize(root)) {
            ancestor = ancestor.resolve(part)
            require(!Files.isSymbolicLink(ancestor)) { "CI evidence root must not have symlink descendants" }
        }
        return root
    }
    private val liveSidecar: Path get() = evidenceRoot.resolve("evidence/verified-jvm-syntax-corpus-v1.json")
    private var runDirectory: Path? = null
    private val successful = AtomicBoolean()
    @RegisterExtension @JvmField val evidenceGate: TestWatcher = object : TestWatcher {
        override fun testSuccessful(context: ExtensionContext) { successful.set(true) }
        override fun testFailed(context: ExtensionContext, cause: Throwable) {
            if (enabled) {
                Files.deleteIfExists(liveSidecar)
                runDirectory?.let { directory ->
                    Files.writeString(directory.resolve("completion.json"), buildJsonObject {
                        put("state", "failed_or_incomplete"); put("failure_type", cause.javaClass.name)
                        put("finished_at", Instant.now().toString()); put("qualified", false)
                        put("note", "Retained START bytes and actual partial artifacts; no passing live corpus sidecar.")
                    }.toString())
                }
            }
        }
    }

    @AfterAll fun withdrawIncompleteCorpusOnly() {
        if (enabled && !successful.get()) Files.deleteIfExists(liveSidecar)
    }

    @AfterEach fun restoreOwnedTemporaryPermissions() {
        if (Files.exists(temporary)) Files.walkFileTree(temporary, object : java.nio.file.SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                dir.toFile().setWritable(true, true)
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                if (!attrs.isSymbolicLink) file.toFile().setWritable(true, true)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }

    @Test fun `ten exact syntax rules execute independent controls and retain genuine heuristic gaps`() {
        assumeTrue(enabled, "Separate real corpus disabled; use :provider-detekt:corpusTest -PdetektCorpusIntegration=true")
        assertTrue(System.getProperty("java.version").startsWith("21."), "Corpus requires the selected JDK21")
        val corpus = loadCorpus()
        assertEquals(10, corpus.size)
        assertEquals(8, corpus.count { it.activation == "advisory" })
        assertEquals(2, corpus.count { it.activation == "opt_in" })
        val javaHome = Path.of(System.getProperty("java.home"))
        val installation = DetektInstallation(javaHome.resolve("bin/java"), repository.resolve(".plan-deps/detekt"), javaHome)
        DetektCatalog.verifyArtifacts(installation.artifactDirectory)
        Files.deleteIfExists(liveSidecar)
        val run = evidenceRoot.resolve("runs/${Instant.now().toString().replace(':', '-')}-${UUID.randomUUID().toString().take(8)}-syntax-corpus-v1")
        Files.createDirectories(run)
        runDirectory = run
        Files.writeString(run.resolve("completion.json"), "{\"state\":\"started_unfinished\",\"qualified\":false}")
        val witness = beginWitness(run, installation)
        var versionEvidence: List<JsonObject>? = null
        val rows = mutableListOf<JsonObject>()
        for (fixture in corpus) {
            val identity = SnapshotIdentity("detekt-corpus-owner", "detekt-syntax-corpus", fixture.slug)
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
                val context = AnalysisContext(scope = RuleContext(module = ":detekt-syntax-corpus", sourceSet = "synthetic"),
                    compilerVersion = "2.4.21", compilationFiles = case.fixture.sources.keys.sorted())
                val settings = DetektSettings(disabledRuleIds = if (case.disabled) setOf(fixture.ruleId) else emptySet(),
                    options = if (case.fixture.options.isEmpty()) emptyMap() else mapOf(fixture.ruleId to case.fixture.options))
                var input = AnalysisInput(commit.toContractManifest(), materialized.root.toString(), context, listOf(fixture.ruleId),
                    outputRoot = temporary.resolve("${fixture.slug}-${case.id}-outputs").toString())
                val plan = DetektProvider(installation, settings = settings).executionPlan(input)
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
                val result = DetektProvider(installation, authority, settings, IsolatedProcessRunner { observed = it })
                    .analyze(input, CancellationToken.NONE)
                val resultRef = artifact(run, "$prefix/provider-result.json", normalized(ContractJson.encodeToString(result)))
                val configRef = artifact(run, "$prefix/generated-config.yml", plan.configuration.toByteArray())
                val processRef = observed?.let { artifact(run, "$prefix/process.json", processJson(it).toString().toByteArray()) }
                val reportRef = observed?.let { actual ->
                    runCatching { actual.report("detekt.sarif") }.getOrNull()
                        ?.let { artifact(run, "$prefix/report.sarif", it) }
                }
                // Actual failed output is already retained; no passed receipt or live sidecar precedes assertions.
                val actual = assertNotNull(observed, "${fixture.ruleId}/${case.id} did not reach isolated runtime: ${result.reasons}")
                assertEquals("executed", actual.status, "${fixture.ruleId}/${case.id}: ${actual.reasons}")
                assertTrue(actual.executionConfirmed, "Executed corpus cases require the actual native exec confirmation")
                assertEquals(0, actual.exitCode, "${fixture.ruleId}/${case.id}: ${actual.stderr}")
                assertFalse(actual.cancelled || actual.timedOut || actual.truncated)
                assertSupervisorIdentity(actual, witness.supervisorIdentity)
                assertEquals("partial", result.status, "${fixture.ruleId}/${case.id}: ${result.reasons}; ${result.rawDiagnostics}")
                assertTrue("detekt_compatibility_tuple_unqualified" in result.reasons)
                assertTrue("project_compiler_2.4.21_differs_from_detekt_embedded_compiler" in result.reasons)
                assertFalse("unmapped_or_unrequested_upstream_diagnostic" in result.reasons)
                assertFalse(result.complete())
                assertTrue(result.coverage.all { !it.tested && !it.qualified && it.certificateId == null && !it.successful() })
                val coverage = result.coverage.single()
                assertEquals("executed", coverage.provenance)
                if (case.disabled) {
                    assertEquals("rule_disabled_by_owner_selected_configuration", coverage.reason)
                    assertFalse(coverage.implemented)
                } else assertTrue(coverage.implemented, "The selected upstream rule must actually be active, even for negatives")
                assertFindings(fixture, case.fixture, result, input)
                assertTrue(store.verifyMaterialization(materialized).valid, "Lint-only process rewrote immutable source bytes")
                if (case.id == "regression") {
                    assertTrue(store.verifyMaterialization(assertNotNull(parentMaterialization)).valid, "Editing a child changed its registered parent")
                }
                val additional = mutableListOf<JsonObject>()
                if (case.id == "positive") {
                    val disabled = DetektSettings(disabledRuleIds = setOf(fixture.ruleId))
                    assertNotEquals(plan.execution, DetektProvider(installation, settings = disabled).executionProfile(input))
                    var replayOutcome: ProcessOutcome? = null
                    val replay = DetektProvider(installation, authority, disabled, IsolatedProcessRunner { replayOutcome = it })
                        .analyze(input, CancellationToken.NONE)
                    val replayRef = artifact(run, "$prefix/configuration-permit-replay.json", normalized(ContractJson.encodeToString(replay)))
                    additional += replayRef
                    val denialRef = replayOutcome?.let { artifact(run, "$prefix/configuration-permit-replay-denial-process.json",
                        processJson(it).toString().toByteArray()) }
                    val denied = assertNotNull(replayOutcome, "Runtime completion callback must retain the authorization denial")
                    assertAuthorizationDenied(denied)
                    assertEquals(listOf("execution_not_authorized"), replay.reasons)
                    assertTrue(replay.findings.isEmpty())
                    assertTrue(replay.coverage.all { it.provenance == "not_executed" && !it.implemented && !it.tested && !it.qualified && !it.successful() })
                    val denialProcess = assertNotNull(denialRef)
                    additional += denialProcess
                    additional += artifact(run, "$prefix/configuration-permit-replay-denial-receipt.json", buildJsonObject {
                        put("case_kind", "changed_configuration_permit_replay"); put("passed", true); put("qualified", false)
                        put("execution_confirmed", denied.executionConfirmed); put("supervisor_identity", actualSupervisorIdentityJson(denied.supervisorIdentity))
                        put("actual_denial", processJson(denied)); put("process", denialProcess); put("provider_result", replayRef)
                        put("note", "Actual runtime callback is refusal before process/output setup, not a started provider or exec-unconfirmed fork.")
                    }.toString().toByteArray())
                }
                if (case.id == "version") {
                    if (versionEvidence == null) versionEvidence = versionProbe(run, input, plan, verify, witness.supervisorIdentity)
                    additional += assertNotNull(versionEvidence)
                    for (target in listOf("common", "native")) {
                        val request = input.copy(context = context.copy(scope = context.scope.copy(target = target)))
                        val permit = ExactExecutionAuthority(ExecutionPermit(plan.execution, commit.snapshotId, materialized.root,
                            request.analysisKey(), verify))
                        var unsupportedOutcome: ProcessOutcome? = null
                        val abstained = DetektProvider(installation, permit, settings, IsolatedProcessRunner { unsupportedOutcome = it })
                            .analyze(request, CancellationToken.NONE)
                        additional += artifact(run, "$prefix/$target-provider-result.json", normalized(ContractJson.encodeToString(abstained)))
                        assertNull(unsupportedOutcome, "$target must not start a JVM syntax process")
                        assertTrue(abstained.findings.isEmpty())
                        assertEquals("unsupported", abstained.coverage.single().state)
                        assertTrue(abstained.coverage.all { it.provenance == "not_executed" && !it.successful() })
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
                put("provider_status", "executed_unqualified"); put("implementation_evidence", JsonArray(witness.references))
                put("cases", JsonArray(cases)); put("qualification_evidence", JsonArray(emptyList()))
                put("gaps", JsonArray((fixture.limitations + listOf(
                    "Independent synthetic light PSI corpus only; no whole-project symbol/CFG/API, compiler compatibility, precision, performance or release qualification.",
                    "Known heuristic-gap and opt-in policy controls retain all genuine findings; tested does not imply valid universal defect semantics.",
                    "File-scoped upstream suppression only; arbitrary ktChecker-ID aliases and declaration suppression are not attested by this corpus.",
                    "START source/resource archives and active class-resource/project code images do not independently prove a reproducible source-to-loaded-bytecode build.",
                )).map(::JsonPrimitive)))
            }
        }
        witness.verify()
        val sidecar = buildJsonObject {
            put("schema_version", 1); put("provider_id", "detekt"); put("provider_version", DETEKT_VERSION)
            put("context", buildJsonObject {
                put("id", "detekt-syntax-corpus-v1"); put("target", "jvm"); put("source_set", "synthetic")
                put("execution_backend", "owned_child_native_supervisor_preview1")
                put("supervisor_protocol_version", witness.supervisorIdentity.protocolVersion)
                put("supervisor_target", witness.supervisorIdentity.target)
                put("supervisor_source_digest", witness.supervisorIdentity.sourceDigest)
                put("supervisor_binary_digest", witness.supervisorIdentity.binaryDigest)
                put("runtime_execution_source_digest", witness.runtimeSourceDigest)
                put("runtime_code_image_digest", witness.runtimeImageDigest)
                put("native_qualification", false)
                put("module", ":detekt-syntax-corpus"); put("compilation", "main"); put("analysis_mode", "light")
                put("kotlin_version", "2.4.21"); put("compiler_version", "2.4.21"); put("embedded_kotlin_version", DETEKT_EMBEDDED_KOTLIN)
                put("jdk_version", System.getProperty("java.version")); put("library_versions", buildJsonObject {})
                put("scope_note", "Ten independent synthetic syntax routes, including known heuristic gaps and explicitly opted-in API conventions; unqualified.")
            })
            put("start_witness", witness.manifestReference); put("rules", JsonArray(rows))
        }
        val finished = artifact(run, "verified-sidecar.json", sidecar.toString().toByteArray())
        Files.writeString(run.resolve("completion.json"), buildJsonObject {
            put("state", "all_authored_assertions_passed_unqualified"); put("finished_at", Instant.now().toString())
            put("rules", rows.size); put("sidecar", finished); put("qualified", false)
            put("note", "JUnit/build logs require separately retained real task completion; this local witness is not a release certificate.")
        }.toString())
        Files.createDirectories(liveSidecar.parent)
        Files.writeString(liveSidecar, sidecar.toString())
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
        expectedSupervisor: OwnedSupervisorIdentity): List<JsonObject> {
        val cli = plan.artifacts.single { it.coordinate == "dev.detekt:detekt-cli:$DETEKT_VERSION:all" }.path
        val execution = plan.execution.copy(arguments = listOf("-Xmx128m", "-jar", cli, "--version"))
        val probeInput = input.copy(execution = execution, outputRoot = temporary.resolve("version-cli").toString())
        val authority = ExactExecutionAuthority(ExecutionPermit(execution, input.snapshot.snapshotId, Path.of(input.materializedRoot),
            probeInput.analysisKey(), verify))
        val actual = IsolatedProcessRunner().run(probeInput, authority)
        val process = artifact(run, "version/process.json", processJson(actual).toString().toByteArray())
        assertEquals("executed", actual.status, actual.reasons.joinToString())
        assertTrue(actual.executionConfirmed, "The CLI version probe requires actual native exec confirmation")
        assertFalse(actual.cancelled || actual.timedOut || actual.truncated)
        assertEquals(0, actual.exitCode)
        assertEquals(DETEKT_VERSION, actual.stdout.trim())
        assertSupervisorIdentity(actual, expectedSupervisor)
        val receipt = artifact(run, "version/receipt.json", buildJsonObject {
            put("actual_exit_code", actual.exitCode); put("stdout", actual.stdout); put("stderr", actual.stderr)
            put("execution_confirmed", actual.executionConfirmed)
            put("supervisor_identity", actualSupervisorIdentityJson(actual.supervisorIdentity))
            put("declared_project_compiler", "2.4.21"); put("embedded_compiler_pin", DETEKT_EMBEDDED_KOTLIN)
            put("compatibility_qualified", false); put("process", process)
        }.toString().toByteArray())
        return listOf(process, receipt)
    }

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
        val path = run.resolve(safeRelativePath(relative))
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
        return reference(path)
    }

    private fun reference(path: Path): JsonObject = buildJsonObject {
        put("path", repository.relativize(path).toString().replace('\\', '/')); put("sha256", fileDigest(path).removePrefix("sha256:"))
    }

    private data class Witness(val references: List<JsonObject>, val manifestReference: JsonObject,
        val supervisorIdentity: OwnedSupervisorIdentity, val runtimeSourceDigest: String, val runtimeImageDigest: String, val verify: () -> Unit)

    private fun beginWitness(run: Path, installation: DetektInstallation): Witness {
        val sourceRoots = listOf("modules/provider-detekt/src/main", "modules/provider-detekt/src/corpusTest",
            "modules/provider-runtime/src/main", "modules/provider-runtime/native", "modules/contracts/src/main", "modules/snapshot/src/main",
            "providers/detekt-fixtures/corpus/syntax-v1").map(repository::resolve)
        val buildFiles = listOf("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradle/wrapper/gradle-wrapper.properties",
            "modules/provider-detekt/build.gradle.kts", "modules/provider-runtime/build.gradle.kts",
            "modules/contracts/build.gradle.kts", "modules/snapshot/build.gradle.kts",
            "providers/detekt-fixtures/test_syntax_corpus.py", "providers/detekt-fixtures/corpus/syntax-v1/README.md")
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
        return Witness(refs + manifest, manifest, supervisorIdentity, Digests.sha256(sources.getValue(runtimeSource)), Digests.sha256(images.getValue(runtimeLocation))) {
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

    private fun loadCorpus(): List<CorpusFixture> = Files.list(fixtures).use { paths ->
        paths.filter { it.toString().endsWith(".json") }.sorted().toList().map { path ->
            val item = Json.parseToJsonElement(Files.readString(path)).jsonObject
            require(item.number("schema_version") == 1 && item.string("authoring_state") == "unexecuted")
            require(item.string("provider_id") == "detekt" && item.string("provider_version") == DETEKT_VERSION)
            require(item.string("embedded_kotlin_version") == DETEKT_EMBEDDED_KOTLIN)
            val rule = DetektCatalog.byId.getValue(item.string("rule_id"))
            require(rule.exact && rule.depth == "syntax" && rule.upstream_id == item.string("upstream_id"))
            require(rule.default_activation == item.string("default_activation") && rule.catalog_record_sha256 == item.string("catalog_record_sha256"))
            val controls = item["controls"]!!.jsonArray.map { value ->
                val control = value.jsonObject
                val id = control.string("id")
                require(id.matches(Regex("[a-z0-9-]+")))
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

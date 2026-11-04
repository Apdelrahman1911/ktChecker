package dev.ktchecker.providers.detekt

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import dev.ktchecker.snapshot.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.extension.TestWatcher
import org.junit.jupiter.api.io.TempDir
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

/** Real pinned child processes, intentionally opt-in; no network hidden in tests. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DetektIntegrationTest {
    @TempDir lateinit var temporary: Path
    private val root: Path get() = Path.of(System.getProperty("ktchecker.repositoryRoot")).toAbsolutePath().normalize()
    private val fixtureRoot: Path get() = root.resolve("providers/detekt-fixtures")
    private val evidenceRoot: Path get() {
        val selected = System.getProperty("ktchecker.libraryFixtureEvidenceRoot") ?: return fixtureRoot
        val destination = Path.of(selected).toAbsolutePath().normalize().resolve("detekt")
        val repository = root.toAbsolutePath().normalize()
        require(destination.startsWith(repository.resolve("build"))) { "CI evidence root must be inside repository build/" }
        var ancestor = repository
        for (part in repository.relativize(destination)) {
            ancestor = ancestor.resolve(part)
            require(!Files.isSymbolicLink(ancestor)) { "CI evidence root must not have symlink descendants" }
        }
        return destination
    }
    private val selected = listOf(
        "ktc.kotlin.detekt.empty-blocks.empty-catch-block",
        "ktc.maintainability.detekt.libraries.forbidden-public-data-class",
        "ktc.compose.pack.ComposableParamOrder",
    )
    private var activeRun: Path? = null
    private val successfulEvidenceMethods = AtomicInteger()
    @RegisterExtension @JvmField val evidenceGate: TestWatcher = object : TestWatcher {
        override fun testSuccessful(context: ExtensionContext) { successfulEvidenceMethods.incrementAndGet() }
        override fun testFailed(context: ExtensionContext, cause: Throwable) {
            activeRun?.let { run -> Files.writeString(run.resolve("completion.json"), buildJsonObject {
                put("state", "failed_or_incomplete"); put("failure_type", cause.javaClass.name); put("qualified", false)
                put("note", "Actual START and partial execution artifacts retained; no passing native-suite attribution.")
            }.toString()) }
        }
    }

    @AfterAll fun withdrawIncompleteSuiteEvidence() {
        if (System.getProperty("ktchecker.detekt.integration") == "true" && successfulEvidenceMethods.get() != 2) {
            Files.deleteIfExists(evidenceRoot.resolve("evidence/verified-jvm-syntax-native-preview1.json"))
            Files.deleteIfExists(evidenceRoot.resolve("evidence/verified-jvm-coroutine-upstream-native-preview1.json"))
        }
    }

    @AfterEach fun restoreOwnedTestPermissions() {
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

    @Test fun `positive negative settings version suppression and immutable edit regression execute upstream`() {
        assumeTrue(System.getProperty("ktchecker.detekt.integration") == "true", "Real Detekt integration disabled; use -PdetektIntegration=true")
        val java = Path.of(System.getProperty("java.home")).resolve("bin/java")
        val installation = DetektInstallation(java, root.resolve(".plan-deps/detekt"), Path.of(System.getProperty("java.home")))
        DetektCatalog.verifyArtifacts(installation.artifactDirectory)
        startRun("syntax")
        val witness = beginWitness("syntax", installation)
        Files.deleteIfExists(evidenceRoot.resolve("evidence/verified-jvm-syntax-native-preview1.json"))
        val identity = SnapshotIdentity("fixture-owner", "detekt-fixture", "syntax")
        val store = SnapshotStore(temporary.resolve("store"), identity.ownerId)
        store.registerWorktree(identity)
        var head: SnapshotCommit? = null
        var parentMaterialization: MaterializedSnapshot? = null
        val cases = mutableListOf<JsonObject>()
        var versionEvidence: JsonObject? = null
        val sourceDirectories = listOf("positive" to "positive", "negative" to "negative", "configuration" to "positive",
            "version" to "positive", "suppression" to "suppression", "regression" to "negative")
        for ((kind, directory) in sourceDirectories) {
            witness.verify()
            val sources = fixtureSources(directory)
            val changes = sources.map { (path, text) -> SnapshotChange.PutFile(SnapshotPath(path = path), ImmutableBlob.utf8(text)) }
            val commit = store.sealOverlay(OverlayRequest(identity, "fixture", "syntax-suite", "case-$kind", head?.snapshotId,
                head?.headRevision ?: 0, changes))
            head = commit
            val materialized = store.materialize(identity, commit.snapshotId, temporary.resolve("sources-$kind"))
            val context = AnalysisContext(scope = RuleContext(module = ":detekt-fixture"), compilerVersion = "2.4.21",
                libraries = mapOf("compose-rules" to COMPOSE_RULES_VERSION), compilationFiles = sources.keys.sorted())
            val extras = if (kind == "positive") listOf("ktc.coroutines.swallowed-cancellation", "ktc.resources.owned_autocloseable_exit",
                "ktc.kotlin.detekt.empty-blocks.empty-class-block", "ktc.maintainability.detekt.ruleauthors.use-entity-at-name") else emptyList()
            var input = AnalysisInput(commit.toContractManifest(), materialized.root.toString(), context, selected + extras,
                outputRoot = temporary.resolve("outputs").toString())
            val settings = if (kind == "configuration") DetektSettings(disabledRuleIds = selected.toSet()) else DetektSettings()
            val planner = DetektProvider(installation, settings = settings)
            val plan = planner.executionPlan(input)
            input = input.copy(execution = plan.execution)
            val verifySnapshot = {
                check(store.verifyMaterialization(materialized).valid) { "Registered immutable materialization changed" }
                Unit
            }
            val authority = ExactExecutionAuthority(ExecutionPermit(plan.execution, commit.snapshotId, materialized.root,
                input.analysisKey(), verifySnapshot))
            var observed: ProcessOutcome? = null
            val result = DetektProvider(installation, authority, settings, IsolatedProcessRunner { observed = it })
                .analyze(input, CancellationToken.NONE)
            // Preserve failures too, without generating a passed sidecar for them.
            writeArtifact("syntax-$kind-provider-result.json", ContractJson.encodeToString(result)
                .replace(temporary.toString(), "{fixture-temporary}").toByteArray())
            writeArtifact("syntax-$kind-config.yml", plan.configuration.toByteArray())
            val processRef = observed?.let { writeArtifact("syntax-$kind-process.json", fixtureProcessJson(it, temporary).toString().toByteArray()) }
            val reportRef = observed?.let { actual -> runCatching { actual.report("detekt.sarif") }.getOrNull()
                ?.let { writeArtifact("syntax-$kind.sarif", it) } }
            val actual = assertNotNull(observed, "$kind did not reach the isolated runner: ${result.reasons}")
            witness.native.assertExecuted(actual)
            assertEquals(0, actual.exitCode, "$kind: ${actual.stderr}")
            assertEquals("partial", result.status, "$kind: ${result.reasons}; ${result.rawDiagnostics}")
            assertTrue(result.coverage.all { !it.qualified && it.certificateId == null })
            assertFalse(result.complete())
            assertTrue(store.verifyMaterialization(materialized).valid, "Lint-only upstream changed registered source bytes")
            if (kind == "regression") {
                assertTrue(store.verifyMaterialization(assertNotNull(parentMaterialization)).valid, "Child correction changed the registered parent")
            }
            if (kind in setOf("positive", "version", "suppression")) {
                assertEquals(selected.toSet(), result.findings.map { it.ruleId }.toSet(), "$kind: ${result.reasons}; ${result.rawDiagnostics}")
                assertEquals(selected.size, result.findings.size, "$kind must retain exactly one finding per rule")
                assertTrue(result.findings.all { finding -> finding.location.fileDigest == input.snapshot.files.single { it.path == finding.location.path }.digest })
            } else {
                assertTrue(result.findings.isEmpty(), "$kind: ${result.findings}")
            }
            if (kind == "positive") {
                assertEquals("unsupported", result.coverage.single { it.ruleId == "ktc.coroutines.swallowed-cancellation" }.state)
                assertEquals("unsupported", result.coverage.single { it.ruleId == "ktc.resources.owned_autocloseable_exit" }.state)
                assertEquals("excluded", result.coverage.single { it.ruleId.endsWith("empty-class-block") }.state)
                assertEquals("excluded", result.coverage.single { it.ruleId.endsWith("use-entity-at-name") }.state)
            }
            if (kind == "configuration") assertTrue(result.coverage.all { it.reason == "rule_disabled_by_owner_selected_configuration" })
            val additional = mutableListOf<JsonObject>()
            if (kind == "positive") {
                val changedSettings = DetektSettings(disabledRuleIds = selected.toSet())
                assertNotEquals(plan.execution, DetektProvider(installation, settings = changedSettings).executionProfile(input))
                var replayOutcome: ProcessOutcome? = null
                val replay = DetektProvider(installation, authority, changedSettings, IsolatedProcessRunner { replayOutcome = it })
                    .analyze(input, CancellationToken.NONE)
                val replayRef = writeArtifact("syntax-configuration-permit-replay.json", ContractJson.encodeToString(replay)
                    .replace(temporary.toString(), "{fixture-temporary}").toByteArray())
                additional += replayRef
                val denialRef = replayOutcome?.let { writeArtifact("syntax-configuration-permit-replay-denial-process.json",
                    fixtureProcessJson(it, temporary).toString().toByteArray()) }
                val denied = assertNotNull(replayOutcome, "Runtime completion callback must retain the authorization denial")
                assertFixtureAuthorizationDenied(denied)
                assertEquals(listOf("execution_not_authorized"), replay.reasons)
                assertTrue(replay.findings.isEmpty())
                assertTrue(replay.coverage.all { it.provenance == "not_executed" && !it.implemented && !it.tested && !it.qualified && !it.successful() })
                val denialProcess = assertNotNull(denialRef)
                additional += denialProcess
                additional += writeArtifact("syntax-configuration-permit-replay-denial-receipt.json", buildJsonObject {
                    put("case_kind", "changed_configuration_permit_replay"); put("passed", true); put("qualified", false)
                    put("execution_confirmed", denied.executionConfirmed); put("supervisor_identity", supervisorIdentityJson(denied.supervisorIdentity))
                    put("actual_denial", fixtureProcessJson(denied, temporary)); put("process", denialProcess); put("provider_result", replayRef)
                    put("note", "Actual completion callback reports authorization refusal before process/output setup; it is not a launched child or an exec-unconfirmed fork.")
                }.toString().toByteArray())
            }
            if (kind == "suppression") {
                assertTrue(result.findings.all { finding -> !finding.location.excerpt.contains("Suppressed") && !finding.location.excerpt.contains("suppressed") })
            }
            if (kind == "version") {
                assertTrue("project_compiler_2.4.21_differs_from_detekt_embedded_compiler" in result.reasons)
                val cli = plan.artifacts.single { it.coordinate.endsWith(":all") }.path
                val execution = plan.execution.copy(arguments = listOf("-Xmx384m", "-jar", cli, "--version"))
                val versionInput = input.copy(execution = execution)
                val versionPermit = ExactExecutionAuthority(ExecutionPermit(execution, commit.snapshotId, materialized.root,
                    versionInput.analysisKey(), verifySnapshot))
                val outcome = IsolatedProcessRunner().run(versionInput, versionPermit)
                val versionProcess = writeArtifact("syntax-version-cli-process.json", fixtureProcessJson(outcome, temporary).toString().toByteArray())
                witness.native.assertExecuted(outcome)
                assertEquals(0, outcome.exitCode)
                assertEquals(DETEKT_VERSION, outcome.stdout.trim())
                versionEvidence = writeArtifact("syntax-version-cli.json", buildJsonObject {
                    put("schema_version", 1); put("case_kind", "version"); put("actual_exit_code", outcome.exitCode)
                    put("execution_confirmed", outcome.executionConfirmed); put("supervisor_identity", supervisorIdentityJson(outcome.supervisorIdentity))
                    put("process", versionProcess)
                    put("stdout", outcome.stdout); put("stderr", outcome.stderr)
                    put("reported_detekt_version", outcome.stdout.trim()); put("embedded_kotlin_pin", DETEKT_EMBEDDED_KOTLIN)
                    put("project_compiler", "2.4.21"); put("compatibility_qualified", false)
                }.toString().toByteArray())
                for (target in listOf("common", "native")) {
                    val request = input.copy(context = context.copy(scope = context.scope.copy(target = target)))
                    val permit = ExactExecutionAuthority(ExecutionPermit(plan.execution, commit.snapshotId, materialized.root,
                        request.analysisKey(), verifySnapshot))
                    var unsupportedOutcome: ProcessOutcome? = null
                    val abstained = DetektProvider(installation, permit, settings, IsolatedProcessRunner { unsupportedOutcome = it })
                        .analyze(request, CancellationToken.NONE)
                    additional += writeArtifact("syntax-$target-abstention.json", ContractJson.encodeToString(abstained)
                        .replace(temporary.toString(), "{fixture-temporary}").toByteArray())
                    assertNull(unsupportedOutcome, "$target must not start a JVM syntax process")
                    assertTrue(abstained.findings.isEmpty())
                    assertTrue(abstained.coverage.all { it.state == "unsupported" && it.provenance == "not_executed" && !it.successful() })
                }
            }
            val sarifReference = assertNotNull(reportRef, "Actual isolated SARIF must be retained")
            val resultJson = ContractJson.encodeToString(result).replace(temporary.toString(), "{fixture-temporary}")
            val resultReference = writeArtifact("syntax-$kind-provider-result.json", resultJson.toByteArray())
            val configurationReference = writeArtifact("syntax-$kind-config.yml", plan.configuration.toByteArray())
            val refs = listOf(sarifReference, resultReference, configurationReference, assertNotNull(processRef)) + additional +
                if (kind == "version") listOf(versionEvidence!!) else emptyList()
            val receipt = buildJsonObject {
                put("schema_version", 1); put("case_kind", kind); put("passed", true)
                put("provider_id", "detekt"); put("provider_version", DETEKT_VERSION)
                put("run_id", assertNotNull(activeRun).fileName.toString())
                put("actual_exit_code", actual.exitCode); put("execution_confirmed", actual.executionConfirmed)
                put("supervisor_identity", supervisorIdentityJson(actual.supervisorIdentity))
                put("analysis_key", input.analysisKey()); put("snapshot_id", commit.snapshotId)
                put("capture_kind", commit.manifest.capture.kind.name); put("registered_materialization_verified", true)
                put("analysis_mode", plan.analysisMode); put("configuration_sha256", plan.configurationDigest)
                put("actual_provider_status", result.status); put("qualified", false)
                put("command_template", JsonArray((listOf(plan.execution.executable) + plan.execution.arguments).map(::JsonPrimitive)))
                put("artifacts", JsonArray(refs)); put("implementation_witnesses", JsonArray(witness.references))
                put("artifact_pins", JsonArray(plan.artifacts.map { buildJsonObject { put("coordinate", it.coordinate); put("sha256", it.digest) } }))
                put("finding_counts", buildJsonObject { for (id in selected) put(id, result.findings.count { it.ruleId == id }) })
                put("note", "Real pinned process via isolated runtime; output path prefixes normalized only in archived provider-result JSON. Syntax stubs do not qualify libraries.")
            }
            val receiptReference = writeArtifact("syntax-$kind-receipt.json", receipt.toString().toByteArray())
            cases += buildJsonObject {
                put("kind", kind); put("passed", true)
                put("evidence", JsonArray(refs + receiptReference))
            }
            parentMaterialization = materialized
            witness.verify()
        }
        witness.verify()
        val sourceEvidence = witness.references
        val sidecar = buildJsonObject {
            put("schema_version", 1); put("provider_id", "detekt"); put("provider_version", DETEKT_VERSION)
            put("context", buildJsonObject {
                put("id", "detekt-syntax-fixtures-native-preview1"); put("target", "jvm"); put("source_set", "main")
                putNativeContext(witness)
                put("kotlin_version", "2.4.21"); put("jdk_version", System.getProperty("java.version"))
                put("library_versions", buildJsonObject { put("compose-rules", COMPOSE_RULES_VERSION) })
                put("embedded_kotlin_version", DETEKT_EMBEDDED_KOTLIN); put("analysis_mode", "light")
                put("scope_note", "Synthetic syntax fixtures only; Compose/Entity stubs, not actual library/compiler qualification.")
            })
            put("rules", JsonArray(selected.map { ruleId -> buildJsonObject {
                put("rule_id", ruleId); put("implemented", true); put("tested", true); put("qualified", false)
                put("provider_status", "executed_unqualified"); put("implementation_evidence", JsonArray(sourceEvidence))
                put("cases", JsonArray(cases)); put("qualification_evidence", JsonArray(emptyList()))
                put("gaps", JsonArray(listOf("No qualified compatibility/precision/performance tuple; syntax fixtures are not library-semantic evidence.").map(::JsonPrimitive)))
            } }))
        }
        writeArtifact("syntax-native-sidecar.json", sidecar.toString().toByteArray())
        val sidecarPath = evidenceRoot.resolve("evidence/verified-jvm-syntax-native-preview1.json")
        Files.createDirectories(sidecarPath.parent)
        Files.writeString(sidecarPath, sidecar.toString())
        finishRun()
    }

    @Test fun `full resolved coroutine upstream guidance never implements the narrower catalog contract`() {
        assumeTrue(System.getProperty("ktchecker.detekt.integration") == "true", "Real semantic Detekt integration disabled; use -PdetektIntegration=true")
        val javaHome = Path.of(System.getProperty("java.home"))
        val java = javaHome.resolve("bin/java")
        val installation = DetektInstallation(java, root.resolve(".plan-deps/detekt"), javaHome)
        val stdlib = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI())
        val kotlinVersion = KotlinVersion.CURRENT.toString()
        assertEquals("2.4.21", kotlinVersion)
        val classpath = listOf(ArtifactIdentity("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion", fileDigest(stdlib), stdlib.toString()))
        startRun("coroutine")
        val witness = beginWitness("coroutine", installation, classpath)
        Files.deleteIfExists(evidenceRoot.resolve("evidence/verified-jvm-coroutine-upstream-native-preview1.json"))
        val ruleId = "ktc.coroutines.sleep-in-coroutine"
        val identity = SnapshotIdentity("fixture-owner", "detekt-fixture", "coroutines")
        val store = SnapshotStore(temporary.resolve("store-full"), identity.ownerId)
        store.registerWorktree(identity)
        var head: SnapshotCommit? = null
        val cases = mutableListOf<JsonObject>()
        for (kind in listOf("positive", "negative")) {
            witness.verify()
            val text = Files.readString(fixtureRoot.resolve("cases/coroutines/$kind/Sleep.kt"))
            val commit = store.sealOverlay(OverlayRequest(identity, "fixture", "semantic-suite", "case-$kind", head?.snapshotId,
                head?.headRevision ?: 0, listOf(SnapshotChange.PutFile(SnapshotPath(path = "Sleep.kt"), ImmutableBlob.utf8(text)))))
            head = commit
            val materialized = store.materialize(identity, commit.snapshotId, temporary.resolve("full-sources-$kind"))
            val context = AnalysisContext(scope = RuleContext(module = ":coroutine-fixture"), compilerVersion = kotlinVersion,
                classpath = classpath, fullProject = true, compilationFiles = listOf("Sleep.kt"),
                modelDigest = Digests.sha256("controlled complete one-file JVM fixture and JDK21"),
                dependencyDigest = Digests.sha256(ContractJson.encodeToString(classpath)),
                toolchainDigest = Digests.sha256(fileDigest(java) + DetektCatalog.pins.joinToString { it.sha256 }),
                configurationDigest = Digests.sha256("explicit default raw SleepInsteadOfDelay selection"))
            var input = AnalysisInput(commit.toContractManifest(), materialized.root.toString(), context, listOf(ruleId),
                outputRoot = temporary.resolve("full-outputs-$kind").toString())
            val planner = DetektProvider(installation)
            var plan = planner.executionPlan(input)
            input = input.copy(context = context.copy(configurationDigest = plan.configurationDigest))
            plan = planner.executionPlan(input)
            assertEquals("full", plan.analysisMode)
            input = input.copy(execution = plan.execution)
            val authority = ExactExecutionAuthority(ExecutionPermit(plan.execution, commit.snapshotId, materialized.root,
                input.analysisKey(), verifySnapshot = { check(store.verifyMaterialization(materialized).valid) }))
            var observed: ProcessOutcome? = null
            val result = DetektProvider(installation, authority, runner = IsolatedProcessRunner { observed = it })
                .analyze(input, CancellationToken.NONE)
            val resultReference = writeArtifact("coroutine-$kind-provider-result.json",
                ContractJson.encodeToString(result).replace(temporary.toString(), "{fixture-temporary}").toByteArray())
            val processRef = observed?.let { writeArtifact("coroutine-$kind-process.json", fixtureProcessJson(it, temporary).toString().toByteArray()) }
            val reportRef = observed?.let { actual -> runCatching { actual.report("detekt.sarif") }.getOrNull()
                ?.let { writeArtifact("coroutine-$kind.sarif", it) } }
            val configurationRef = writeArtifact("coroutine-$kind-config.yml", plan.configuration.toByteArray())
            val actual = assertNotNull(observed, "$kind did not reach the isolated runner: ${result.reasons}")
            witness.native.assertExecuted(actual)
            assertEquals(0, actual.exitCode, actual.stderr)
            assertTrue(result.findings.isEmpty(), "Raw upstream must not create narrowed catalog findings")
            assertFalse(result.complete())
            assertEquals("unsupported", result.coverage.single().state, result.reasons.joinToString() + result.rawDiagnostics)
            assertFalse(result.coverage.single().implemented)
            assertTrue(store.verifyMaterialization(materialized).valid, "Lint-only raw detector changed registered source bytes")
            val raw = result.rawDiagnostics.filter { it.upstreamId == "detekt.coroutines.SleepInsteadOfDelay" }
            assertEquals(if (kind == "positive") 1 else 0, raw.size, result.reasons.joinToString() + result.rawDiagnostics)
            val sarifReference = assertNotNull(reportRef, "Actual isolated full-mode SARIF must be retained")
            val refs = listOf(resultReference, sarifReference, configurationRef, assertNotNull(processRef))
            val receiptReference = writeArtifact("coroutine-$kind-receipt.json", buildJsonObject {
                put("schema_version", 1); put("case_kind", kind); put("passed", true)
                put("analysis_mode", "full"); put("provider_status", result.status); put("catalog_refinement_implemented", false)
                put("run_id", assertNotNull(activeRun).fileName.toString())
                put("actual_exit_code", actual.exitCode); put("execution_confirmed", actual.executionConfirmed)
                put("supervisor_identity", supervisorIdentityJson(actual.supervisorIdentity))
                put("snapshot_id", commit.snapshotId); put("registered_materialization_verified", true)
                put("stdlib_coordinate", classpath.single().coordinate); put("stdlib_sha256", classpath.single().digest)
                put("project_compiler", kotlinVersion); put("embedded_kotlin", DETEKT_EMBEDDED_KOTLIN); put("qualified", false)
                put("raw_upstream_findings", raw.size); put("catalog_findings", result.findings.size)
                put("artifacts", JsonArray(refs)); put("implementation_witnesses", JsonArray(witness.references))
                put("note", "Actual JDK resolved call/alias and local-owner negative. Raw detector integration only; narrowed catalog adapter unimplemented.")
            }.toString().toByteArray())
            cases += buildJsonObject { put("kind", kind); put("passed", true); put("evidence", JsonArray(refs + receiptReference)) }
            witness.verify()
        }
        witness.verify()
        val sidecar = buildJsonObject {
            put("schema_version", 1); put("provider_id", "detekt"); put("provider_version", DETEKT_VERSION)
            put("execution_implementation_witnesses", JsonArray(witness.references))
            put("context", buildJsonObject {
                put("id", "detekt-coroutine-raw-fixtures-native-preview1"); put("target", "jvm"); put("source_set", "main")
                putNativeContext(witness)
                put("kotlin_version", kotlinVersion); put("jdk_version", System.getProperty("java.version"))
                put("library_versions", buildJsonObject { put("kotlin-stdlib", kotlinVersion) })
                put("analysis_mode", "full"); put("stdlib_sha256", classpath.single().digest)
            })
            put("rules", JsonArray(listOf(buildJsonObject {
                put("rule_id", ruleId); put("implemented", false); put("tested", false); put("qualified", false)
                put("provider_status", "raw_upstream_executed_catalog_unsupported"); put("cases", JsonArray(cases))
                put("implementation_evidence", JsonArray(emptyList())); put("qualification_evidence", JsonArray(emptyList()))
                put("gaps", JsonArray(listOf("Raw upstream positive/negative replay is not refined catalog implementation or qualification.",
                    "No configuration/version/suppression/regression refinement suite; embedded compiler mismatch remains unqualified.").map(::JsonPrimitive)))
            })))
        }
        writeArtifact("coroutine-native-sidecar.json", sidecar.toString().toByteArray())
        val path = evidenceRoot.resolve("evidence/verified-jvm-coroutine-upstream-native-preview1.json")
        Files.createDirectories(path.parent)
        Files.writeString(path, sidecar.toString())
        finishRun()
    }

    private fun fixtureSources(directory: String): Map<String, String> {
        val parent = fixtureRoot.resolve("cases/syntax")
        val shared = Files.readString(parent.resolve("positive/Composable.kt"))
        val files = Files.list(parent.resolve(directory)).use { stream ->
            stream.filter { it.toString().endsWith(".kt") }.sorted().toList().associate { it.fileName.toString() to Files.readString(it) }
        }.toMutableMap()
        files.putIfAbsent("Composable.kt", shared)
        return files
    }

    private fun writeArtifact(name: String, bytes: ByteArray): JsonObject {
        val path = assertNotNull(activeRun, "A new native run archive must exist before artifact capture")
            .resolve("evidence-artifacts").resolve(safeRelativePath(name))
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
        return reference(path)
    }

    private fun reference(path: Path): JsonObject = buildJsonObject {
        put("path", root.relativize(path).toString().replace('\\', '/'))
        put("sha256", fileDigest(path).removePrefix("sha256:"))
    }

    private fun startRun(prefix: String) {
        val run = evidenceRoot.resolve("runs/${Instant.now().toString().replace(':', '-')}-${UUID.randomUUID().toString().take(8)}-$prefix-native-preview1")
        Files.createDirectories(run.resolve("evidence-artifacts"))
        activeRun = run
        Files.writeString(run.resolve("completion.json"), "{\"state\":\"started_unfinished\",\"qualified\":false}")
    }

    private fun finishRun() {
        Files.writeString(assertNotNull(activeRun).resolve("completion.json"), buildJsonObject {
            put("state", "all_method_assertions_passed_unqualified"); put("qualified", false)
            put("note", "Fresh native-supervised method only; actual JUnit/suite completion must be retained separately. Historical pack is not renewed.")
        }.toString())
    }

    private data class Witness(val references: List<JsonObject>, val native: NativeFixtureWitness,
        val runtimeSourceDigest: String, val runtimeImageDigest: String, val verify: () -> Unit)

    private fun JsonObjectBuilder.putNativeContext(witness: Witness) {
        put("execution_backend", "owned_child_native_supervisor_preview1")
        put("supervisor_protocol_version", witness.native.identity.protocolVersion)
        put("supervisor_target", witness.native.identity.target)
        put("supervisor_source_digest", witness.native.identity.sourceDigest)
        put("supervisor_binary_digest", witness.native.identity.binaryDigest)
        put("runtime_execution_source_digest", witness.runtimeSourceDigest)
        put("runtime_code_image_digest", witness.runtimeImageDigest)
        put("native_qualification", false)
    }

    /** Attribute runs to START bytes, never silently refresh passing witnesses from END source. */
    private fun beginWitness(prefix: String, installation: DetektInstallation,
        classpath: List<ArtifactIdentity> = emptyList()): Witness {
        val native = captureNativeFixtureWitness(root, prefix, ::writeArtifact)
        val sourceRoots = listOf("modules/provider-detekt/src/main", "modules/provider-detekt/src/test",
            "modules/provider-runtime/src/main", "modules/contracts/src/main", "modules/snapshot/src/main", "providers/detekt-fixtures/cases")
            .map(root::resolve)
        val buildFiles = listOf("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradle/wrapper/gradle-wrapper.properties",
            "modules/provider-detekt/build.gradle.kts", "modules/provider-runtime/build.gradle.kts", "modules/contracts/build.gradle.kts",
            "modules/snapshot/build.gradle.kts", "providers/detekt-fixtures/README.md").map(root::resolve)
        fun sourceFiles(): List<Path> = (sourceRoots.flatMap { directory -> Files.walk(directory).use { paths ->
            paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.sorted().toList()
        } } + buildFiles.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }).distinct()
        val start = sourceFiles().associateWith { Files.readAllBytes(it) }
        val sourceReferences = start.flatMap { (path, bytes) -> listOf(reference(path),
            writeArtifact("$prefix-at-start/${root.relativize(path).toString().replace('\\', '/')}", bytes)) }
        val types = listOf(DetektProvider::class.java, DetektCatalog::class.java, DetektReportParser::class.java,
            DetektIntegrationTest::class.java, IsolatedProcessRunner::class.java, ExecutionPermit::class.java,
            ExactExecutionAuthority::class.java, NativeFixtureWitness::class.java, native.anchor, SnapshotStore::class.java, ArtifactIdentity::class.java)
        fun bytes(type: Class<*>): ByteArray = type.getResourceAsStream("/" + type.name.replace('.', '/') + ".class")!!.use { it.readBytes() }
        val binaries = types.associateWith { bytes(it) }
        val binaryReferences = binaries.map { (type, data) -> writeArtifact("$prefix-${type.simpleName}.class", data) }
        val images = types.map { Path.of(it.protectionDomain.codeSource.location.toURI()).toAbsolutePath().normalize() }.distinct()
            .associateWith { fixtureProjectCodeImage(root, it) }
        val imageRefs = images.entries.mapIndexed { index, (path, data) -> writeArtifact("$prefix-project-image-$index-${path.fileName}.zip", data) }
        val toolPins = DetektCatalog.verifyArtifacts(installation.artifactDirectory)
        val javaDigest = fileDigest(installation.javaExecutable)
        val toolRef = writeArtifact("$prefix-tool-start-witness.json", buildJsonObject {
            put("phase", "START_before_processes"); put("qualified", false)
            put("artifact_pins", JsonArray(toolPins.map { ContractJson.encodeToJsonElement(it.copy(path = "{pinned-installation}/${Path.of(it.path).fileName}")) }))
            put("java_executable_digest", javaDigest)
            put("resolved_classpath", JsonArray(classpath.map { ContractJson.encodeToJsonElement(it.copy(path = "{selected-classpath}/${Path.of(it.path).fileName}")) }))
            put("note", "Actual installed tool/launcher/classpath digests; external upstream engine and full JDK not redistributed or reproducibly attested.")
        }.toString().toByteArray())
        val references = sourceReferences + binaryReferences + imageRefs + native.references + toolRef
        val manifest = writeArtifact("$prefix-start-witness.json", buildJsonObject {
            put("phase", "START_before_processes"); put("qualified", false); put("implementation_witnesses", JsonArray(references))
            put("note", "Current bytes and original START archives only; no retrospective source renewal or source-to-loaded-bytecode proof.")
        }.toString().toByteArray())
        val runtimePath = root.resolve("modules/provider-runtime/src/main/kotlin/dev/ktchecker/providers/runtime/Execution.kt")
        val runtimeCodeSource = Path.of(native.anchor.protectionDomain.codeSource.location.toURI()).toAbsolutePath().normalize()
        return Witness(references + manifest, native, Digests.sha256(start.getValue(runtimePath)), Digests.sha256(images.getValue(runtimeCodeSource))) {
            assertEquals(start.keys.toList(), sourceFiles(), "Source/fixture membership changed after START")
            for ((path, data) in start) assertContentEquals(data, Files.readAllBytes(path), "Source/fixture drift after START witness; refusing passing sidecar: $path")
            for ((type, data) in binaries) assertContentEquals(data, bytes(type), "Executed provider/test/runtime class drift; refusing passing sidecar")
            for ((path, data) in images) assertContentEquals(data, fixtureProjectCodeImage(root, path), "Project code-image drift after START")
            native.verify()
            assertEquals(toolPins, DetektCatalog.verifyArtifacts(installation.artifactDirectory), "Engine artifact drift after START")
            assertEquals(javaDigest, fileDigest(installation.javaExecutable), "Java executable drift after START")
            for (entry in classpath) assertEquals(entry.digest, fileDigest(Path.of(entry.path)), "Resolved classpath drift after START")
        }
    }
}

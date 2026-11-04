package dev.ktchecker.providers.ktlint

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import dev.ktchecker.snapshot.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.extension.TestWatcher
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** Genuine pinned lint-only processes. Neither these tests nor upstream tests qualify a release. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KtlintIntegrationTest {
    @TempDir lateinit var temporary: Path
    private val root: Path get() = Path.of(System.getProperty("ktchecker.repositoryRoot")).toAbsolutePath().normalize()
    private val fixtures: Path get() = root.resolve("providers/ktlint-fixtures")
    private val evidenceRoot: Path get() {
        val selected = System.getProperty("ktchecker.libraryFixtureEvidenceRoot") ?: return fixtures
        val destination = Path.of(selected).toAbsolutePath().normalize().resolve("ktlint")
        val repository = root.toAbsolutePath().normalize()
        require(destination.startsWith(repository.resolve("build"))) { "CI evidence root must be inside repository build/" }
        var ancestor = repository
        for (part in repository.relativize(destination)) {
            ancestor = ancestor.resolve(part)
            require(!Files.isSymbolicLink(ancestor)) { "CI evidence root must not have symlink descendants" }
        }
        return destination
    }
    private val selected = listOf("ktc.maintainability.ktlint.no-wildcard-imports", "ktc.maintainability.ktlint.no-trailing-spaces",
        "ktc.maintainability.ktlint.final-newline", "ktc.compose.pack.ComposableParamOrder")
    private val installation get() = Path.of(System.getProperty("java.home")).let {
        KtlintInstallation(it.resolve("bin/java"), root.resolve(".plan-deps/ktlint"), it)
    }
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
        if (System.getProperty("ktchecker.ktlint.integration") == "true" && successfulEvidenceMethods.get() != 2) {
            Files.deleteIfExists(evidenceRoot.resolve("evidence/verified-jvm-syntax-native-preview1.json"))
        }
    }

    @AfterEach fun restoreOwnedFixturePermissions() {
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

    @Test fun `four selected syntax rules execute positive negative config version scoped suppression and immutable regression`() {
        assumeTrue(System.getProperty("ktchecker.ktlint.integration") == "true", "Real Ktlint integration disabled; use -PktlintIntegration=true")
        startRun("syntax")
        val witness = beginWitness("syntax")
        val sidecarPath = evidenceRoot.resolve("evidence/verified-jvm-syntax-native-preview1.json")
        Files.deleteIfExists(sidecarPath)
        val identity = SnapshotIdentity("fixture-owner", "ktlint-fixture", "six-cases")
        val store = SnapshotStore(temporary.resolve("store"), identity.ownerId)
        store.registerWorktree(identity)
        var head: SnapshotCommit? = null
        var previousPaths = emptySet<String>()
        var parentMaterialization: MaterializedSnapshot? = null
        val cases = mutableListOf<JsonObject>()
        for ((kind, directory) in listOf("positive" to "positive", "negative" to "negative", "configuration" to "positive",
            "version" to "positive", "suppression" to "suppression", "regression" to "negative")) {
            witness.verify()
            val sources = sourceFixture(directory)
            val changes = sources.map { (path, text) -> SnapshotChange.PutFile(SnapshotPath(path = path), ImmutableBlob.utf8(text)) } +
                (previousPaths - sources.keys).map { SnapshotChange.Delete(SnapshotPath(path = it)) }
            val commit = store.sealOverlay(OverlayRequest(identity, "fixture", "six-cases", kind, head?.snapshotId,
                head?.headRevision ?: 0, changes))
            if (kind == "regression") assertEquals(head?.snapshotId, commit.manifest.parentSnapshotId)
            head = commit
            previousPaths = sources.keys
            val materialized = store.materialize(identity, commit.snapshotId, temporary.resolve("sources-$kind"))
            val context = AnalysisContext(scope = RuleContext(module = ":ktlint-fixture"), compilerVersion = KTLINT_EMBEDDED_KOTLIN,
                libraries = mapOf("compose-rules" to KTLINT_COMPOSE_RULES_VERSION), compilationFiles = sources.keys.filter { it.endsWith(".kt") }.sorted())
            val extras = if (kind == "positive") listOf("ktc.compose.pack.ComposableNaming", "ktc.compose.pack.CompositionLocalNaming",
                "ktc.maintainability.ktlint.condition-wrapping", "ktc.maintainability.ktlint.ktlint-suppression-infrastructure",
                "ktc.compose.pack.ModifierShouldBeForwardedToEveryBranch") else emptyList()
            val settings = if (kind == "configuration") KtlintSettings(disabledRuleIds = selected.toSet()) else KtlintSettings()
            var input = AnalysisInput(commit.toContractManifest(), materialized.root.toString(), context, selected + extras,
                outputRoot = temporary.resolve("outputs-$kind").toString())
            val plan = KtlintProvider(installation, settings = settings).executionPlan(input)
            input = input.copy(execution = plan.execution)
            val verify = { check(store.verifyMaterialization(materialized).valid); Unit }
            val authority = ExactExecutionAuthority(ExecutionPermit(plan.execution, commit.snapshotId, materialized.root, input.analysisKey(), verify))
            var outcome: ProcessOutcome? = null
            val result = KtlintProvider(installation, authority, settings, IsolatedProcessRunner { outcome = it }).analyze(input, CancellationToken.NONE)
            val resultRef = artifact("syntax-$kind-provider-result.json", ContractJson.encodeToString(result).replace(temporary.toString(), "{fixture-temporary}").toByteArray())
            val configRef = artifact("syntax-$kind-editorconfig", plan.configuration.toByteArray())
            val processRef = outcome?.let { artifact("syntax-$kind-process.json", fixtureProcessJson(it, temporary).toString().toByteArray()) }
            val reportRef = outcome?.let { actual -> runCatching { actual.report("ktlint.json") }.getOrNull()
                ?.let { artifact("syntax-$kind-report.json", it) } }
            val actual = assertNotNull(outcome, "$kind did not reach the isolated runner: ${result.reasons}")
            val outputRef = assertNotNull(processRef)
            witness.native.assertExecuted(actual)
            assertEquals("partial", result.status, "$kind: ${result.reasons}; ${result.rawDiagnostics}")
            assertTrue("ktlint_syntax_tuple_unqualified" in result.reasons, "$kind: ${result.reasons}; ${result.rawDiagnostics}")
            assertTrue(result.coverage.all { !it.qualified && it.certificateId == null && !it.successful() })
            assertFalse(result.complete())
            assertTrue(store.verifyMaterialization(materialized).valid, "Lint-only process changed source snapshot")
            if (kind == "regression") assertTrue(store.verifyMaterialization(assertNotNull(parentMaterialization)).valid,
                "Child correction changed its registered parent")
            val expected = kind in setOf("positive", "version", "suppression")
            assertEquals(if (expected) 1 else 0, actual.exitCode, "$kind: ${actual.stderr}")
            if (expected) {
                assertEquals(selected.toSet(), result.findings.map { it.ruleId }.toSet(), "$kind: ${result.findings}")
                assertEquals(4, result.findings.size, "$kind must retain exactly one adjacent unsuppressed finding per selected rule")
                for (finding in result.findings) {
                    assertEquals(input.snapshot.files.single { it.path == finding.location.path }.digest, finding.location.fileDigest)
                    val expectedPath = when (finding.ruleId) {
                        selected[0] -> "Wildcard.kt"
                        selected[1] -> "Whitespace.kt"
                        selected[2] -> "Newline.kt"
                        else -> "Compose.kt"
                    }
                    assertEquals(expectedPath, finding.location.path)
                    assertEquals(if (expectedPath == "Compose.kt") 6 else 3, finding.location.line)
                    assertEquals(when (expectedPath) {
                        "Wildcard.kt" -> 0
                        "Whitespace.kt" -> "fun whitespace() = Unit".length
                        "Newline.kt" -> "fun newline() = Unit".length - 1
                        else -> 4
                    }, finding.location.column)
                }
            } else assertTrue(result.findings.isEmpty(), "$kind: ${result.findings}")
            val additional = mutableListOf<JsonObject>()
            if (kind == "positive") {
                assertTrue(extras.dropLast(1).all { id -> result.coverage.single { it.ruleId == id }.state == "excluded" })
                assertEquals("unsupported", result.coverage.single { it.ruleId == extras.last() }.state)
                val changed = KtlintSettings(disabledRuleIds = selected.toSet())
                assertNotEquals(plan.execution, KtlintProvider(installation, settings = changed).executionProfile(input))
                var replayOutcome: ProcessOutcome? = null
                val replay = KtlintProvider(installation, authority, changed, IsolatedProcessRunner { replayOutcome = it })
                    .analyze(input, CancellationToken.NONE)
                val replayRef = artifact("syntax-configuration-permit-replay.json", ContractJson.encodeToString(replay)
                    .replace(temporary.toString(), "{fixture-temporary}").toByteArray())
                additional += replayRef
                val denialRef = replayOutcome?.let { artifact("syntax-configuration-permit-replay-denial-process.json",
                    fixtureProcessJson(it, temporary).toString().toByteArray()) }
                val denied = assertNotNull(replayOutcome, "Runtime completion callback must retain the authorization denial")
                assertFixtureAuthorizationDenied(denied)
                assertEquals(listOf("execution_not_authorized"), replay.reasons)
                assertTrue(replay.findings.isEmpty())
                assertTrue(replay.coverage.all { it.provenance == "not_executed" && !it.implemented && !it.tested && !it.qualified && !it.successful() })
                val denialProcess = assertNotNull(denialRef)
                additional += denialProcess
                additional += artifact("syntax-configuration-permit-replay-denial-receipt.json", buildJsonObject {
                    put("case_kind", "changed_configuration_permit_replay"); put("passed", true); put("qualified", false)
                    put("execution_confirmed", denied.executionConfirmed); put("supervisor_identity", supervisorIdentityJson(denied.supervisorIdentity))
                    put("actual_denial", fixtureProcessJson(denied, temporary)); put("process", denialProcess); put("provider_result", replayRef)
                    put("note", "Actual completion callback reports authorization refusal before process/output setup; it is not a launched child or an exec-unconfirmed fork.")
                }.toString().toByteArray())
            }
            if (kind == "configuration") assertTrue(result.coverage.all { it.reason == "rule_disabled_by_owner_selected_configuration" })
            var versionRef: JsonObject? = null
            if (kind == "version") {
                val cli = plan.artifacts.single { it.coordinate == "com.pinterest.ktlint:ktlint-cli:$KTLINT_VERSION:all" }.path
                val execution = plan.execution.copy(arguments = listOf("-Xmx128m", "-jar", cli, "--version"))
                val versionInput = input.copy(execution = execution)
                val versionAuthority = ExactExecutionAuthority(ExecutionPermit(execution, commit.snapshotId, materialized.root, versionInput.analysisKey(), verify))
                val version = IsolatedProcessRunner().run(versionInput, versionAuthority)
                val versionProcess = artifact("syntax-version-cli-process.json", fixtureProcessJson(version, temporary).toString().toByteArray())
                witness.native.assertExecuted(version)
                assertEquals(0, version.exitCode)
                assertEquals("ktlint version $KTLINT_VERSION", version.stdout.trim())
                val newer = input.copy(context = context.copy(compilerVersion = "2.4.21"))
                val newerAuthority = ExactExecutionAuthority(ExecutionPermit(plan.execution, commit.snapshotId, materialized.root, newer.analysisKey(), verify))
                var mismatchOutcome: ProcessOutcome? = null
                val mismatch = KtlintProvider(installation, newerAuthority, runner = IsolatedProcessRunner { mismatchOutcome = it })
                    .analyze(newer, CancellationToken.NONE)
                assertNull(mismatchOutcome, "Newer parser tuple must abstain before the isolated runner")
                assertEquals("unsupported", mismatch.coverage.single { it.ruleId == selected[0] }.state)
                assertTrue(mismatch.coverage.all { it.provenance == "not_executed" && !it.successful() })
                versionRef = artifact("syntax-version-cli.json", buildJsonObject {
                    put("actual_exit_code", version.exitCode); put("stdout", version.stdout); put("stderr", version.stderr)
                    put("execution_confirmed", version.executionConfirmed); put("supervisor_identity", supervisorIdentityJson(version.supervisorIdentity))
                    put("process", versionProcess)
                    put("embedded_parser_pin", KTLINT_EMBEDDED_KOTLIN); put("host_compiler", KotlinVersion.CURRENT.toString())
                    put("newer_project_profile", "2.4.21"); put("newer_profile_executed", false); put("qualified", false)
                    put("newer_profile_result", ContractJson.encodeToJsonElement(mismatch))
                }.toString().toByteArray())
                for (target in listOf("common", "native")) {
                    val request = input.copy(context = context.copy(scope = context.scope.copy(target = target)))
                    val permit = ExactExecutionAuthority(ExecutionPermit(plan.execution, commit.snapshotId, materialized.root,
                        request.analysisKey(), verify))
                    var unsupportedOutcome: ProcessOutcome? = null
                    val abstained = KtlintProvider(installation, permit, settings, IsolatedProcessRunner { unsupportedOutcome = it })
                        .analyze(request, CancellationToken.NONE)
                    additional += artifact("syntax-$target-abstention.json", ContractJson.encodeToString(abstained)
                        .replace(temporary.toString(), "{fixture-temporary}").toByteArray())
                    assertNull(unsupportedOutcome, "$target must not start a JVM syntax process")
                    assertTrue(abstained.findings.isEmpty())
                    assertTrue(abstained.coverage.all { it.state == "unsupported" && it.provenance == "not_executed" && !it.successful() })
                }
            }
            val refs = listOf(resultRef, configRef, assertNotNull(reportRef), outputRef) + listOfNotNull(versionRef) + additional
            val receipt = artifact("syntax-$kind-receipt.json", buildJsonObject {
                put("schema_version", 1); put("case_kind", kind); put("passed", true)
                put("provider_id", "ktlint"); put("provider_version", KTLINT_VERSION); put("actual_exit_code", actual.exitCode)
                put("run_id", assertNotNull(activeRun).fileName.toString()); put("execution_confirmed", actual.executionConfirmed)
                put("supervisor_identity", supervisorIdentityJson(actual.supervisorIdentity))
                put("snapshot_id", commit.snapshotId); put("analysis_key", input.analysisKey()); put("registered_materialization_verified", true)
                put("configuration_sha256", plan.configurationDigest); put("qualified", false); put("source_rewrites", false)
                if (kind == "suppression") put("suppression_scope", "upstream_file_annotation_only")
                put("command_template", JsonArray((listOf(plan.execution.executable) + plan.execution.arguments).map(::JsonPrimitive)))
                put("artifact_pins", JsonArray(plan.artifacts.map { buildJsonObject { put("coordinate", it.coordinate); put("sha256", it.digest) } }))
                put("finding_counts", buildJsonObject { for (id in selected) put(id, result.findings.count { it.ruleId == id }) })
                put("artifacts", JsonArray(refs)); put("implementation_witnesses", JsonArray(witness.references))
                put("note", "Real Ktlint JSON/debug scan-count/exit verified; syntax stubs do not prove Compose compiler/API identities. Source and loaded-class witnesses frozen at START.")
            }.toString().toByteArray())
            cases += buildJsonObject { put("kind", kind); put("passed", true); put("evidence", JsonArray(refs + receipt)) }
            parentMaterialization = materialized
            witness.verify()
        }
        witness.verify()
        val sidecar = buildJsonObject {
            put("schema_version", 1); put("provider_id", "ktlint"); put("provider_version", KTLINT_VERSION)
            put("context", buildJsonObject {
                put("id", "ktlint-syntax-fixtures-native-preview1"); put("target", "jvm"); put("source_set", "main"); put("module", ":ktlint-fixture"); put("compilation", "main")
                putNativeContext(witness)
                put("kotlin_version", KTLINT_EMBEDDED_KOTLIN); put("embedded_parser_version", KTLINT_EMBEDDED_KOTLIN)
                put("jdk_version", System.getProperty("java.version")); put("host_compiler_version", KotlinVersion.CURRENT.toString())
                put("library_versions", buildJsonObject { put("compose-rules", KTLINT_COMPOSE_RULES_VERSION) })
                put("scope_note", "Synthetic Kotlin2.2.21 syntax profile only; not an actual project KGP/Compose semantic qualification.")
            })
            put("rules", JsonArray(selected.map { id -> buildJsonObject {
                put("rule_id", id); put("implemented", true); put("tested", true); put("qualified", false); put("provider_status", "executed_unqualified")
                put("implementation_evidence", JsonArray(witness.references)); put("cases", JsonArray(cases)); put("qualification_evidence", JsonArray(emptyList()))
                put("gaps", JsonArray(listOf("Only selected synthetic syntax cases executed. Suppression evidence is file-scoped only; Compose ParameterOrder declaration suppression remains unsupported by the upstream file visitor. No KGP2.4.21/common/Native/Android or actual Compose compiler/API, precision/performance/release qualification; ktChecker-ID suppression aliases remain unimplemented.").map(::JsonPrimitive)))
            } }))
        }
        Files.createDirectories(sidecarPath.parent)
        artifact("syntax-native-sidecar.json", sidecar.toString().toByteArray())
        Files.writeString(sidecarPath, sidecar.toString())
        finishRun()
    }

    @Test fun `real editorconfig inheritance exemptions and parse errors never produce clean semantic coverage`() {
        assumeTrue(System.getProperty("ktchecker.ktlint.integration") == "true", "Real configuration integration disabled; use -PktlintIntegration=true")
        startRun("configuration")
        val witness = beginWitness("configuration")
        val ruleId = selected.first()
        val rootConfig = "root = true\n\n[*.{kt,kts}]\nij_kotlin_packages_to_use_import_on_demand = java.util.*\n"
        val wildcard = "package fixture\n\nimport java.io.*\n\nfun example() = Unit\n"
        val cases = listOf(
            "inheritance" to mapOf(".editorconfig" to rootConfig, "Parent.kt" to wildcard, "nested/Child.kt" to wildcard,
                "nested/.editorconfig" to "[*.kt]\nktlint_standard_no-wildcard-imports = disabled\n"),
            "exemption" to mapOf(".editorconfig" to rootConfig.replace("java.util.*", "java.io.*"), "Parent.kt" to wildcard),
            "parse-failure" to mapOf(".editorconfig" to rootConfig, "Parent.kt" to "fun incomplete("),
            "compose-declaration-gap" to sourceFixture("declaration-suppression-gap"),
        )
        for ((kind, sources) in cases) {
            witness.verify()
            val identity = SnapshotIdentity("fixture-owner", "ktlint-fixture", kind)
            val store = SnapshotStore(temporary.resolve("store-$kind"), identity.ownerId)
            store.registerWorktree(identity)
            val commit = store.sealOverlay(OverlayRequest(identity, "fixture", "configuration", kind, null, 0,
                sources.map { (path, text) -> SnapshotChange.PutFile(SnapshotPath(path = path), ImmutableBlob.utf8(text)) }))
            val materialized = store.materialize(identity, commit.snapshotId, temporary.resolve("sources-$kind"))
            val caseRuleId = if (kind == "compose-declaration-gap") selected.last() else ruleId
            var input = AnalysisInput(commit.toContractManifest(), materialized.root.toString(), AnalysisContext(compilerVersion = KTLINT_EMBEDDED_KOTLIN,
                compilationFiles = sources.keys.filter { it.endsWith(".kt") }.sorted()), listOf(caseRuleId), outputRoot = temporary.resolve("outputs-$kind").toString())
            val plan = KtlintProvider(installation).executionPlan(input)
            input = input.copy(execution = plan.execution)
            val authority = ExactExecutionAuthority(ExecutionPermit(plan.execution, commit.snapshotId, materialized.root, input.analysisKey(),
                { check(store.verifyMaterialization(materialized).valid) }))
            var outcome: ProcessOutcome? = null
            val result = KtlintProvider(installation, authority, runner = IsolatedProcessRunner { outcome = it }).analyze(input, CancellationToken.NONE)
            val resultRef = artifact("configuration-$kind-provider-result.json", ContractJson.encodeToString(result).replace(temporary.toString(), "{fixture-temporary}").toByteArray())
            val processRef = outcome?.let { artifact("configuration-$kind-process.json", fixtureProcessJson(it, temporary).toString().toByteArray()) }
            val reportRef = outcome?.let { actual -> runCatching { actual.report("ktlint.json") }.getOrNull()
                ?.let { artifact("configuration-$kind-report.json", it) } }
            val configurationRef = artifact("configuration-$kind-editorconfig", plan.configuration.toByteArray())
            val actual = assertNotNull(outcome, "$kind did not reach the isolated runner: ${result.reasons}")
            witness.native.assertExecuted(actual)
            assertEquals("partial", result.status)
            assertFalse(result.complete())
            assertTrue(result.coverage.all { !it.qualified && !it.successful() && it.certificateId == null })
            assertTrue(store.verifyMaterialization(materialized).valid)
            when (kind) {
                "inheritance" -> { assertEquals(1, result.findings.size, result.reasons.joinToString()); assertEquals("Parent.kt", result.findings.single().location.path) }
                "exemption" -> { assertTrue(result.findings.isEmpty()); assertTrue("ktlint_syntax_tuple_unqualified" in result.reasons) }
                "compose-declaration-gap" -> {
                    // Compose 0.6.8 scans these functions during its file visit. Ktlint
                    // checks suppression at the visited file, not emitted offsets.
                    // Retain this observed limitation; never post-filter the report.
                    assertEquals(2, result.findings.size, "Declaration suppression is an explicit upstream gap, not a successful suppression test")
                    assertTrue(result.findings.all { it.ruleId == selected.last() && it.location.path == "Compose.kt" && it.location.column == 4 })
                    assertEquals(setOf(6, 10), result.findings.map { it.location.line }.toSet())
                    assertTrue(result.findings.any { it.location.excerpt.contains("SuppressedExample") })
                    assertTrue("ktlint_syntax_tuple_unqualified" in result.reasons)
                }
                else -> { assertTrue(result.findings.isEmpty()); assertEquals(listOf("ktlint_parse_or_rule_failure"), result.reasons); assertEquals("unknown", result.coverage.single().state) }
            }
            artifact("configuration-$kind-receipt.json", buildJsonObject {
                put("schema_version", 1); put("case_kind", kind); put("passed", true); put("qualified", false)
                put("provider_id", "ktlint"); put("provider_version", KTLINT_VERSION)
                put("run_id", assertNotNull(activeRun).fileName.toString()); put("actual_exit_code", actual.exitCode)
                put("execution_confirmed", actual.executionConfirmed); put("supervisor_identity", supervisorIdentityJson(actual.supervisorIdentity))
                put("snapshot_id", commit.snapshotId); put("analysis_key", input.analysisKey())
                put("registered_materialization_verified", true); put("source_rewrites", false)
                put("provider_status", result.status); put("provider_reasons", JsonArray(result.reasons.map(::JsonPrimitive)))
                put("artifacts", JsonArray(listOf(resultRef, configurationRef, assertNotNull(processRef)) + listOfNotNull(reportRef)))
                put("implementation_witnesses", JsonArray(witness.references))
                put("note", "Actual native exec-confirmed syntax controls, including genuine parse failure and retained declaration-suppression gap; not semantic/compatibility qualification.")
            }.toString().toByteArray())
            witness.verify()
        }
        witness.verify()
        finishRun()
    }

    private fun sourceFixture(directory: String): Map<String, String> = Json.parseToJsonElement(Files.readString(fixtures.resolve("cases/$directory/sources.json")))
        .jsonObject.mapValues { it.value.jsonPrimitive.content }

    private fun artifact(name: String, bytes: ByteArray): JsonObject {
        val path = assertNotNull(activeRun, "A new native run archive must exist before artifact capture")
            .resolve("evidence-artifacts").resolve(safeRelativePath(name))
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
        return buildJsonObject { put("path", root.relativize(path).toString().replace('\\', '/')); put("sha256", Digests.sha256(bytes).removePrefix("sha256:")) }
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

    private fun beginWitness(prefix: String): Witness {
        val native = captureNativeFixtureWitness(root, prefix, ::artifact)
        val sourceRoots = listOf("modules/provider-ktlint/src/main", "modules/provider-ktlint/src/test",
            "modules/provider-runtime/src/main", "modules/contracts/src/main", "modules/snapshot/src/main", "providers/ktlint-fixtures/cases")
            .map(root::resolve)
        val buildFiles = listOf("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradle/wrapper/gradle-wrapper.properties",
            "modules/provider-ktlint/build.gradle.kts", "modules/provider-runtime/build.gradle.kts", "modules/contracts/build.gradle.kts",
            "modules/snapshot/build.gradle.kts", "providers/ktlint-fixtures/README.md").map(root::resolve)
        fun sourceFiles(): List<Path> = (sourceRoots.flatMap { directory -> Files.walk(directory).use { paths ->
            paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.sorted().toList()
        } } + buildFiles.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }).distinct()
        val start = sourceFiles().associateWith { Files.readAllBytes(it) }
        val sourceRefs = start.flatMap { (path, bytes) -> listOf(buildJsonObject {
            put("path", root.relativize(path).toString().replace('\\', '/')); put("sha256", Digests.sha256(bytes).removePrefix("sha256:"))
        }, artifact("$prefix-at-start/${root.relativize(path).toString().replace('\\', '/')}", bytes)) }
        val types = listOf(KtlintProvider::class.java, KtlintCatalog::class.java, KtlintReportParser::class.java, KtlintIntegrationTest::class.java,
            IsolatedProcessRunner::class.java, ExecutionPermit::class.java, ExactExecutionAuthority::class.java,
            NativeFixtureWitness::class.java, native.anchor, SnapshotStore::class.java, ArtifactIdentity::class.java)
        fun bytes(type: Class<*>): ByteArray = type.getResourceAsStream("/" + type.name.replace('.', '/') + ".class")!!.use { it.readBytes() }
        val binaries = types.associateWith { bytes(it) }
        val binaryRefs = binaries.map { (type, content) -> artifact("$prefix-${type.simpleName}.class", content) }
        val images = types.map { Path.of(it.protectionDomain.codeSource.location.toURI()).toAbsolutePath().normalize() }.distinct()
            .associateWith { fixtureProjectCodeImage(root, it) }
        val imageRefs = images.entries.mapIndexed { index, (path, data) -> artifact("$prefix-project-image-$index-${path.fileName}.zip", data) }
        val selectedInstallation = installation
        val toolPins = KtlintCatalog.verifyArtifacts(selectedInstallation.artifactDirectory)
        val javaDigest = fileDigest(selectedInstallation.javaExecutable)
        val toolRef = artifact("$prefix-tool-start-witness.json", buildJsonObject {
            put("phase", "START_before_processes"); put("qualified", false)
            put("artifact_pins", JsonArray(toolPins.map { ContractJson.encodeToJsonElement(it.copy(path = "{pinned-installation}/${Path.of(it.path).fileName}")) }))
            put("java_executable_digest", javaDigest)
            put("note", "Actual installed tool/launcher digests; upstream engine and full JDK not redistributed or reproducibly attested.")
        }.toString().toByteArray())
        val references = sourceRefs + binaryRefs + imageRefs + native.references + toolRef
        val manifest = artifact("$prefix-start-witness.json", buildJsonObject {
            put("phase", "START_before_processes"); put("passed", false); put("qualified", false)
            put("implementation_witnesses", JsonArray(references))
            put("note", "Current bytes and original START archives only; no retrospective source renewal or source-to-loaded-bytecode proof.")
        }.toString().toByteArray())
        val runtimePath = root.resolve("modules/provider-runtime/src/main/kotlin/dev/ktchecker/providers/runtime/Execution.kt")
        val runtimeCodeSource = Path.of(native.anchor.protectionDomain.codeSource.location.toURI()).toAbsolutePath().normalize()
        return Witness(references + manifest, native, Digests.sha256(start.getValue(runtimePath)), Digests.sha256(images.getValue(runtimeCodeSource))) {
            assertEquals(start.keys.toList(), sourceFiles(), "Source/fixture membership changed after START")
            for ((path, data) in start) assertContentEquals(data, Files.readAllBytes(path), "Source/fixture changed after START; refusing passing evidence: $path")
            for ((type, content) in binaries) assertContentEquals(content, bytes(type), "Executed provider/test/runtime class changed; refusing passing evidence")
            for ((path, data) in images) assertContentEquals(data, fixtureProjectCodeImage(root, path), "Project code-image drift after START")
            native.verify()
            assertEquals(toolPins, KtlintCatalog.verifyArtifacts(selectedInstallation.artifactDirectory), "Engine artifact drift after START")
            assertEquals(javaDigest, fileDigest(selectedInstallation.javaExecutable), "Java executable drift after START")
        }
    }
}

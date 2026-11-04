package dev.ktchecker.providers.ktlint

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import dev.ktchecker.snapshot.*
import java.nio.file.Files
import java.nio.file.Path
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
    private val root: Path get() = Path.of(System.getProperty("ktchecker.repositoryRoot"))
    private val fixtures: Path get() = root.resolve("providers/ktlint-fixtures")
    private val selected = listOf("ktc.maintainability.ktlint.no-wildcard-imports", "ktc.maintainability.ktlint.no-trailing-spaces",
        "ktc.maintainability.ktlint.final-newline", "ktc.compose.pack.ComposableParamOrder")
    private val installation get() = Path.of(System.getProperty("java.home")).let {
        KtlintInstallation(it.resolve("bin/java"), root.resolve(".plan-deps/ktlint"), it)
    }
    private val successfulEvidenceMethods = AtomicInteger()
    @RegisterExtension @JvmField val evidenceGate: TestWatcher = object : TestWatcher {
        override fun testSuccessful(context: ExtensionContext) { successfulEvidenceMethods.incrementAndGet() }
    }

    @AfterAll fun withdrawIncompleteSuiteEvidence() {
        if (System.getProperty("ktchecker.ktlint.integration") == "true" && successfulEvidenceMethods.get() != 2) {
            Files.deleteIfExists(fixtures.resolve("evidence/verified-jvm-syntax.json"))
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
        Files.createDirectories(fixtures.resolve("evidence-artifacts"))
        val witness = beginWitness()
        val sidecarPath = fixtures.resolve("evidence/verified-jvm-syntax.json")
        Files.deleteIfExists(sidecarPath)
        val identity = SnapshotIdentity("fixture-owner", "ktlint-fixture", "six-cases")
        val store = SnapshotStore(temporary.resolve("store"), identity.ownerId)
        store.registerWorktree(identity)
        var head: SnapshotCommit? = null
        var previousPaths = emptySet<String>()
        val cases = mutableListOf<JsonObject>()
        for ((kind, directory) in listOf("positive" to "positive", "negative" to "negative", "configuration" to "positive",
            "version" to "positive", "suppression" to "suppression", "regression" to "negative")) {
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
            val actual = assertNotNull(outcome, "$kind did not reach the isolated runner: ${result.reasons}")
            val reportRef = actual.outputDirectory?.resolve("ktlint.json")?.takeIf { Files.isRegularFile(it) }
                ?.let { artifact("syntax-$kind-report.json", Files.readAllBytes(it)) }
            val outputRef = artifact("syntax-$kind-process.json", buildJsonObject {
                put("status", actual.status); put("exit_code", actual.exitCode); put("stdout", actual.stdout.replace(temporary.toString(), "{fixture-temporary}"))
                put("stderr", actual.stderr.replace(temporary.toString(), "{fixture-temporary}")); put("cancelled", actual.cancelled)
                put("timed_out", actual.timedOut); put("truncated", actual.truncated)
            }.toString().toByteArray())
            assertEquals("partial", result.status, "$kind: ${result.reasons}; ${result.rawDiagnostics}")
            assertTrue("ktlint_syntax_tuple_unqualified" in result.reasons, "$kind: ${result.reasons}; ${result.rawDiagnostics}")
            assertTrue(result.coverage.all { !it.qualified && it.certificateId == null && !it.successful() })
            assertFalse(result.complete())
            assertTrue(store.verifyMaterialization(materialized).valid, "Lint-only process changed source snapshot")
            val expected = kind in setOf("positive", "version", "suppression")
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
            if (kind == "positive") {
                assertTrue(extras.dropLast(1).all { id -> result.coverage.single { it.ruleId == id }.state == "excluded" })
                assertEquals("unsupported", result.coverage.single { it.ruleId == extras.last() }.state)
                val changed = KtlintSettings(disabledRuleIds = selected.toSet())
                assertNotEquals(plan.execution, KtlintProvider(installation, settings = changed).executionProfile(input))
                val replay = KtlintProvider(installation, authority, changed).analyze(input, CancellationToken.NONE)
                assertEquals(listOf("execution_not_authorized"), replay.reasons)
                assertTrue(replay.coverage.all { it.provenance == "not_executed" })
            }
            if (kind == "configuration") assertTrue(result.coverage.all { it.reason == "rule_disabled_by_owner_selected_configuration" })
            var versionRef: JsonObject? = null
            if (kind == "version") {
                val cli = plan.artifacts.single { it.coordinate == "com.pinterest.ktlint:ktlint-cli:$KTLINT_VERSION:all" }.path
                val execution = plan.execution.copy(arguments = listOf("-Xmx128m", "-jar", cli, "--version"))
                val versionInput = input.copy(execution = execution)
                val versionAuthority = ExactExecutionAuthority(ExecutionPermit(execution, commit.snapshotId, materialized.root, versionInput.analysisKey(), verify))
                val version = IsolatedProcessRunner().run(versionInput, versionAuthority)
                assertEquals("executed", version.status, version.reasons.joinToString())
                assertEquals(0, version.exitCode)
                assertEquals(KTLINT_VERSION, version.stdout.trim())
                val newer = input.copy(context = context.copy(compilerVersion = "2.4.21"))
                val newerAuthority = ExactExecutionAuthority(ExecutionPermit(plan.execution, commit.snapshotId, materialized.root, newer.analysisKey(), verify))
                val mismatch = KtlintProvider(installation, newerAuthority).analyze(newer, CancellationToken.NONE)
                assertEquals("unsupported", mismatch.coverage.single { it.ruleId == selected[0] }.state)
                assertTrue(mismatch.coverage.all { it.provenance == "not_executed" && !it.successful() })
                versionRef = artifact("syntax-version-cli.json", buildJsonObject {
                    put("actual_exit_code", version.exitCode); put("stdout", version.stdout); put("stderr", version.stderr)
                    put("embedded_parser_pin", KTLINT_EMBEDDED_KOTLIN); put("host_compiler", KotlinVersion.CURRENT.toString())
                    put("newer_project_profile", "2.4.21"); put("newer_profile_executed", false); put("qualified", false)
                    put("newer_profile_result", ContractJson.encodeToJsonElement(mismatch))
                }.toString().toByteArray())
            }
            val refs = listOf(resultRef, configRef, assertNotNull(reportRef), outputRef) + listOfNotNull(versionRef)
            val receipt = artifact("syntax-$kind-receipt.json", buildJsonObject {
                put("schema_version", 1); put("case_kind", kind); put("passed", true)
                put("provider_id", "ktlint"); put("provider_version", KTLINT_VERSION); put("actual_exit_code", actual.exitCode)
                put("snapshot_id", commit.snapshotId); put("analysis_key", input.analysisKey()); put("registered_materialization_verified", true)
                put("configuration_sha256", plan.configurationDigest); put("qualified", false); put("source_rewrites", false)
                put("command_template", JsonArray((listOf(plan.execution.executable) + plan.execution.arguments).map(::JsonPrimitive)))
                put("artifact_pins", JsonArray(plan.artifacts.map { buildJsonObject { put("coordinate", it.coordinate); put("sha256", it.digest) } }))
                put("finding_counts", buildJsonObject { for (id in selected) put(id, result.findings.count { it.ruleId == id }) })
                put("artifacts", JsonArray(refs)); put("implementation_witnesses", JsonArray(witness.references))
                put("note", "Real Ktlint JSON/debug scan-count/exit verified; syntax stubs do not prove Compose compiler/API identities. Source and loaded-class witnesses frozen at START.")
            }.toString().toByteArray())
            cases += buildJsonObject { put("kind", kind); put("passed", true); put("evidence", JsonArray(refs + receipt)) }
        }
        witness.verify()
        val sidecar = buildJsonObject {
            put("schema_version", 1); put("provider_id", "ktlint"); put("provider_version", KTLINT_VERSION)
            put("context", buildJsonObject {
                put("id", "ktlint-syntax-fixtures"); put("target", "jvm"); put("source_set", "main"); put("module", ":ktlint-fixture"); put("compilation", "main")
                put("kotlin_version", KTLINT_EMBEDDED_KOTLIN); put("embedded_parser_version", KTLINT_EMBEDDED_KOTLIN)
                put("jdk_version", System.getProperty("java.version")); put("host_compiler_version", KotlinVersion.CURRENT.toString())
                put("library_versions", buildJsonObject { put("compose-rules", KTLINT_COMPOSE_RULES_VERSION) })
                put("scope_note", "Synthetic Kotlin2.2.21 syntax profile only; not an actual project KGP/Compose semantic qualification.")
            })
            put("rules", JsonArray(selected.map { id -> buildJsonObject {
                put("rule_id", id); put("implemented", true); put("tested", true); put("qualified", false); put("provider_status", "executed_unqualified")
                put("implementation_evidence", JsonArray(witness.references)); put("cases", JsonArray(cases)); put("qualification_evidence", JsonArray(emptyList()))
                put("gaps", JsonArray(listOf("Only selected synthetic syntax cases executed. No KGP2.4.21/common/Native/Android or actual Compose compiler/API, precision/performance/release qualification; ktChecker-ID suppression aliases remain unimplemented.").map(::JsonPrimitive)))
            } }))
        }
        Files.createDirectories(sidecarPath.parent)
        Files.writeString(sidecarPath, sidecar.toString())
    }

    @Test fun `real editorconfig inheritance exemptions and parse errors never produce clean semantic coverage`() {
        assumeTrue(System.getProperty("ktchecker.ktlint.integration") == "true", "Real configuration integration disabled; use -PktlintIntegration=true")
        val ruleId = selected.first()
        val rootConfig = "root = true\n\n[*.{kt,kts}]\nij_kotlin_packages_to_use_import_on_demand = java.util.*\n"
        val wildcard = "package fixture\n\nimport java.io.*\n\nfun example() = Unit\n"
        val cases = listOf(
            "inheritance" to mapOf(".editorconfig" to rootConfig, "Parent.kt" to wildcard, "nested/Child.kt" to wildcard,
                "nested/.editorconfig" to "[*.kt]\nktlint_standard_no-wildcard-imports = disabled\n"),
            "exemption" to mapOf(".editorconfig" to rootConfig.replace("java.util.*", "java.io.*"), "Parent.kt" to wildcard),
            "parse-failure" to mapOf(".editorconfig" to rootConfig, "Parent.kt" to "fun incomplete("),
        )
        for ((kind, sources) in cases) {
            val identity = SnapshotIdentity("fixture-owner", "ktlint-fixture", kind)
            val store = SnapshotStore(temporary.resolve("store-$kind"), identity.ownerId)
            store.registerWorktree(identity)
            val commit = store.sealOverlay(OverlayRequest(identity, "fixture", "configuration", kind, null, 0,
                sources.map { (path, text) -> SnapshotChange.PutFile(SnapshotPath(path = path), ImmutableBlob.utf8(text)) }))
            val materialized = store.materialize(identity, commit.snapshotId, temporary.resolve("sources-$kind"))
            var input = AnalysisInput(commit.toContractManifest(), materialized.root.toString(), AnalysisContext(compilerVersion = KTLINT_EMBEDDED_KOTLIN,
                compilationFiles = sources.keys.filter { it.endsWith(".kt") }.sorted()), listOf(ruleId), outputRoot = temporary.resolve("outputs-$kind").toString())
            val plan = KtlintProvider(installation).executionPlan(input)
            input = input.copy(execution = plan.execution)
            val authority = ExactExecutionAuthority(ExecutionPermit(plan.execution, commit.snapshotId, materialized.root, input.analysisKey(),
                { check(store.verifyMaterialization(materialized).valid) }))
            var outcome: ProcessOutcome? = null
            val result = KtlintProvider(installation, authority, runner = IsolatedProcessRunner { outcome = it }).analyze(input, CancellationToken.NONE)
            Files.createDirectories(fixtures.resolve("evidence-artifacts"))
            artifact("configuration-$kind-provider-result.json", ContractJson.encodeToString(result).replace(temporary.toString(), "{fixture-temporary}").toByteArray())
            outcome?.outputDirectory?.resolve("ktlint.json")?.takeIf { Files.isRegularFile(it) }?.let {
                artifact("configuration-$kind-report.json", Files.readAllBytes(it))
            }
            assertEquals("partial", result.status)
            assertFalse(result.complete())
            assertTrue(result.coverage.all { !it.qualified && !it.successful() && it.certificateId == null })
            assertTrue(store.verifyMaterialization(materialized).valid)
            when (kind) {
                "inheritance" -> { assertEquals(1, result.findings.size, result.reasons.joinToString()); assertEquals("Parent.kt", result.findings.single().location.path) }
                "exemption" -> { assertTrue(result.findings.isEmpty()); assertTrue("ktlint_syntax_tuple_unqualified" in result.reasons) }
                else -> { assertTrue(result.findings.isEmpty()); assertEquals(listOf("ktlint_parse_or_rule_failure"), result.reasons); assertEquals("unknown", result.coverage.single().state) }
            }
        }
    }

    private fun sourceFixture(directory: String): Map<String, String> = Json.parseToJsonElement(Files.readString(fixtures.resolve("cases/$directory/sources.json")))
        .jsonObject.mapValues { it.value.jsonPrimitive.content }

    private fun artifact(name: String, bytes: ByteArray): JsonObject {
        val path = fixtures.resolve("evidence-artifacts/$name")
        Files.write(path, bytes)
        return buildJsonObject { put("path", root.relativize(path).toString().replace('\\', '/')); put("sha256", Digests.sha256(bytes).removePrefix("sha256:")) }
    }

    private data class Witness(val references: List<JsonObject>, val verify: () -> Unit)
    private fun beginWitness(): Witness {
        val sources = Files.walk(root.resolve("modules/provider-ktlint/src")).use { paths -> paths.filter { Files.isRegularFile(it) }.sorted().toList() } +
            Files.walk(fixtures.resolve("cases")).use { paths -> paths.filter { Files.isRegularFile(it) }.sorted().toList() }
        val start = sources.associateWith { fileDigest(it) }
        val sourceRefs = start.map { (path, hash) -> buildJsonObject { put("path", root.relativize(path).toString().replace('\\', '/')); put("sha256", hash.removePrefix("sha256:")) } }
        val types = listOf(KtlintProvider::class.java, KtlintCatalog::class.java, KtlintReportParser::class.java, KtlintIntegrationTest::class.java,
            IsolatedProcessRunner::class.java, ExecutionPermit::class.java, ExactExecutionAuthority::class.java)
        fun bytes(type: Class<*>): ByteArray = type.getResourceAsStream("/" + type.name.replace('.', '/') + ".class")!!.use { it.readBytes() }
        val binaries = types.associateWith { bytes(it) }
        val binaryRefs = binaries.map { (type, content) -> artifact("syntax-${type.simpleName}.class", content) }
        return Witness(sourceRefs + binaryRefs) {
            for ((path, hash) in start) assertEquals(hash, fileDigest(path), "Source/fixture changed after START; refusing passing evidence: $path")
            for ((type, content) in binaries) assertContentEquals(content, bytes(type), "Executed provider/test/runtime class changed; refusing passing evidence")
        }
    }
}

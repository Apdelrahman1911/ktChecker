package dev.ktchecker.semantic

import dev.ktchecker.contracts.AnalysisMetrics
import dev.ktchecker.contracts.ArtifactIdentity
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import dev.ktchecker.providers.runtime.OwnedSupervisorIdentity
import dev.ktchecker.providers.runtime.ProcessOutcome
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SemanticFixtureSummaryTest {
    private val digest = Digests.sha256("synthetic test evidence, not an execution")
    private val privateText = "/private/owner/SECRET_PROJECT"
    private fun report(): SemanticFixtureRunner.SuiteEvidence {
        val source = SemanticSource("$privateText/Source.kt", digest, 20, "private_source_set")
        val span = SemanticSpan(source.path, source.digest, source.sourceSet, 0, 0, 0, 3, 0, 3)
        val symbol = SymbolFact(digest, "function", "PRIVATE_SYMBOL", "PRIVATE_MODULE", "Source", false, span)
        val shard = SemanticFactShard(digest, digest, digest, digest, digest, SEMANTIC_COMPILER_VERSION, source,
            CompilerSourceContext("PRIVATE_MODULE", "JVM", false, emptyList()), listOf(digest), listOf(symbol), emptyList(),
            emptyList(), emptyList(), listOf(UnknownSemanticFact("call", "unresolved_or_error_reference", span)))
        val bundle = SemanticFactBundle(digest, digest, digest, "jvm", "PRIVATE_COMPILATION", "incomplete", 1,
            listOf("semantic_compilation_failed", "PRIVATE reason at $privateText"), listOf(shard), false,
            limitations = listOf("PRIVATE_CUSTOM_LIMITATION"))
        return SemanticFixtureRunner.SuiteEvidence(state = "failed", mode = "jvm", target = "jvm",
            observedAt = "2026-11-04T00:00:00Z", testedCommit = "a".repeat(40),
            runner = mapOf("os" to "Linux", "fixtureHostJava" to "21.0.9", "projectJavaVersion" to "25.0.1",
                "ImageVersion" to "20261025.1.0", "DEVELOPER_DIR" to privateText, "TOKEN_SECRET" to "PRIVATE_TOKEN",
                "GITHUB_RUN_ID" to "$privateText/run"),
            artifacts = listOf(ArtifactIdentity("ktchecker:$SEMANTIC_ADAPTER_ID", digest, "$privateText/plugin.jar"),
                ArtifactIdentity("selected-semantic-jdk:lib/modules", digest, "$privateText/jdk/lib/modules")),
            cases = listOf(SemanticFixtureRunner.CaseEvidence("unresolved", true,
                mapOf("compiler_failure_not_successful_facts" to true), bundle)),
            executions = listOf(SemanticFixtureRunner.ExecutionEvidence("unresolved", digest, digest,
                listOf("$privateText/java", "-cp", "$privateText/compiler.jar", "PRIVATE_COMMAND"), "executed", 1,
                emptyList(), AnalysisMetrics(8, 3, 1024, 19), digest, digest)), reasons = emptyList(),
            limitations = listOf("PRIVATE_SUITE_LIMITATION"))
    }

    @Test fun `upload projection preserves binding counts results metrics but not private data`() {
        val summary = SemanticFixtureSummary.from(report(), digest)
        val json = ContractJson.encodeToString(summary)
        assertEquals("executed_semantic_fact_fixtures", summary.evidenceKind)
        assertEquals("failed", summary.state)
        assertEquals(digest, summary.privateEvidenceDigest)
        assertEquals(digest, summary.cases.single().requestDigest)
        assertEquals(1, summary.cases.single().counts["visitedSourceInputs"])
        assertEquals(1, summary.cases.single().counts["symbolFacts"])
        assertEquals(1, summary.cases.single().unknownReasonCounts["unresolved_or_error_reference"])
        assertContains(summary.cases.single().reasons, "semantic_compilation_failed")
        assertContains(summary.cases.single().reasons, "unstructured_detail_omitted")
        assertEquals(8L, summary.executions.single().metrics.elapsedMillis)
        assertEquals(1, summary.executions.single().exitCode)
        assertEquals("25.0.1", summary.runner["projectJavaVersion"])
        assertEquals(2, summary.artifacts.size)
        assertFalse(summary.tupleQualified)
        assertFalse(summary.customRulesQualified)
        listOf(privateText, "PRIVATE_", "DEVELOPER_DIR", "TOKEN_SECRET", "GITHUB_RUN_ID", "\"command\"", "\"path\"",
            "\"bundle\"", "\"source\"", "\"qualifiedName\"").forEach { assertFalse(it in json, "Private field leaked: $it") }
    }

    @Test fun `malformed metadata cannot leak paths or arbitrary environment into summary`() {
        val raw = report().copy(testedCommit = privateText, observedAt = privateText,
            artifacts = listOf(ArtifactIdentity("$privateText/unknown", digest, privateText),
                ArtifactIdentity("selected-native-2.4.21:konan/../../$privateText", digest, privateText)),
            reasons = listOf("failure at $privateText"), tupleQualified = true, customRulesQualified = true)
        val summary = SemanticFixtureSummary.from(raw, digest)
        assertNull(summary.testedCommit)
        assertEquals("unavailable", summary.observedAt)
        assertTrue(summary.artifacts.all { it.coordinate == "unrecognized_coordinate_omitted" && Digests.valid(it.omittedCoordinateDigest!!) })
        assertEquals(listOf("unstructured_detail_omitted"), summary.reasons)
        assertFalse(summary.tupleQualified)
        assertFalse(summary.customRulesQualified)
        assertFalse(privateText in ContractJson.encodeToString(summary))
    }

    private fun originalFailure(): ProcessOutcome = ProcessOutcome(
        status = "failed", reasons = listOf("execution_timeout", "PRIVATE reason at $privateText"), exitCode = 137,
        stdout = "PRIVATE_STDOUT\n", stderr = "PRIVATE_STDERR\n", outputDirectory = Path.of("$privateText/output"),
        command = listOf("$privateText/java", "PRIVATE_ORIGINAL_ARG"), cancelled = true, timedOut = true, truncated = true,
        metrics = AnalysisMetrics(123, 45, 678, 90), outputIdentity = "PRIVATE_ORIGINAL_DIRECTORY_IDENTITY",
        supervisorIdentity = OwnedSupervisorIdentity("PRIVATE_PROTOCOL", "PRIVATE_TARGET", "PRIVATE_SOURCE_DIGEST", "PRIVATE_BINARY_DIGEST"),
        executionConfirmed = true,
    )

    @Test fun `private original outcome preserves every returned field and derived flag`() {
        val outcome = originalFailure()
        val evidence = SemanticFixtureRunner.executionEvidence("positive", digest, digest, outcome)
        val restored = ContractJson.decodeFromString<SemanticFixtureRunner.ExecutionEvidence>(ContractJson.encodeToString(evidence))
        assertEquals(evidence, restored)
        val original = assertNotNull(restored.originalOutcome)
        assertEquals(outcome.status, original.status); assertEquals(outcome.reasons, original.reasons)
        assertEquals(outcome.exitCode, original.exitCode)
        assertEquals(outcome.stdout, original.stdout); assertEquals(outcome.stderr, original.stderr)
        assertEquals(outcome.outputDirectory.toString(), original.outputDirectory); assertEquals(outcome.command, original.command)
        assertEquals(outcome.cancelled, original.cancelled); assertEquals(outcome.timedOut, original.timedOut)
        assertEquals(outcome.truncated, original.truncated); assertEquals(outcome.metrics, original.metrics)
        assertEquals("PRIVATE_ORIGINAL_DIRECTORY_IDENTITY", outcome.outputIdentityForPrivateEvidence())
        assertEquals("PRIVATE_ORIGINAL_DIRECTORY_IDENTITY", original.outputIdentity)
        val supervisor = assertNotNull(original.supervisorIdentity)
        assertEquals("PRIVATE_PROTOCOL", supervisor.protocolVersion); assertEquals("PRIVATE_TARGET", supervisor.target)
        assertEquals("PRIVATE_SOURCE_DIGEST", supervisor.sourceDigest); assertEquals("PRIVATE_BINARY_DIGEST", supervisor.binaryDigest)
        assertTrue(original.executionConfirmed); assertTrue(original.executed); assertFalse(original.successfulExecution)
        assertEquals(Digests.sha256(outcome.stdout), restored.stdoutDigest)
        assertEquals(Digests.sha256(outcome.stderr), restored.stderrDigest)
    }

    @Test fun `private outcomes preserve missing confirmation and null original identities without reconstruction`() {
        for (outcome in listOf(ProcessOutcome("unavailable", reasons = listOf("execution_not_authorized")),
                ProcessOutcome("executed", exitCode = 0), ProcessOutcome("executed", exitCode = 1, executionConfirmed = true))) {
            val original = assertNotNull(SemanticFixtureRunner.executionEvidence("unresolved", digest, digest, outcome).originalOutcome)
            assertNull(original.outputDirectory); assertNull(original.outputIdentity); assertNull(original.supervisorIdentity)
            assertNull(outcome.outputIdentityForPrivateEvidence())
            assertEquals(outcome.executionConfirmed, original.executionConfirmed)
            assertEquals(outcome.executed, original.executed)
            assertEquals(outcome.successfulExecution, original.successfulExecution)
            assertEquals(outcome.exitCode, original.exitCode)
        }
        // Even a forged status/exit cannot make the copied original exec-confirmation true.
        val unconfirmed = SemanticFixtureRunner.executionEvidence("dependency", digest, digest, ProcessOutcome("executed", exitCode = 0))
        assertFalse(assertNotNull(unconfirmed.originalOutcome).successfulExecution)
        // These constructed DTO controls are not evidence that any process ran.
    }

    @Test fun `complete private outcome never broadens the public summary allowlist`() {
        val evidence = SemanticFixtureRunner.executionEvidence("unresolved", digest, digest, originalFailure())
        val raw = report().copy(executions = listOf(evidence))
        val privateJson = ContractJson.encodeToString(raw)
        listOf("PRIVATE_STDOUT", "PRIVATE_STDERR", "PRIVATE_ORIGINAL_DIRECTORY_IDENTITY", "PRIVATE_PROTOCOL",
            "PRIVATE_SOURCE_DIGEST", "PRIVATE_BINARY_DIGEST").forEach { assertContains(privateJson, it) }
        val summary = SemanticFixtureSummary.from(raw, digest)
        val oldProjection = SemanticFixtureSummary.from(raw.copy(executions = listOf(evidence.copy(originalOutcome = null))), digest)
        assertEquals(oldProjection, summary)
        val publicJson = ContractJson.encodeToString(summary)
        listOf(privateText, "PRIVATE_", "\"originalOutcome\"", "\"stdout\"", "\"stderr\"", "\"outputDirectory\"",
            "\"outputIdentity\"", "\"supervisorIdentity\"", "\"executionConfirmed\"", "\"command\"",
            "\"cancelled\"", "\"timedOut\"", "\"truncated\"").forEach { assertFalse(it in publicJson, "Private field leaked: $it") }
        assertEquals(evidence.stdoutDigest, summary.executions.single().stdoutDigest)
        assertEquals(evidence.stderrDigest, summary.executions.single().stderrDigest)
        assertFalse(summary.tupleQualified); assertFalse(summary.customRulesQualified)
    }

    @Test fun `per-attempt private outcome publication is exclusive and preserves prior and sibling bytes`(@TempDir directory: Path) {
        val evidence = SemanticFixtureRunner.executionEvidence("positive", digest, digest, originalFailure())
        val sibling = directory.resolve("unrelated-sentinel")
        Files.writeString(sibling, "PRIVATE_UNRELATED_SENTINEL")
        val path = SemanticFixtureRunner.writePrivateExecutionEvidence(directory, evidence)
        assertEquals(directory.resolve("positive-execution.json"), path)
        val originalBytes = Files.readAllBytes(path)
        val restored = ContractJson.decodeFromString<SemanticFixtureRunner.ExecutionEvidence>(String(originalBytes, Charsets.UTF_8))
        assertEquals(evidence, restored)
        assertFailsWith<FileAlreadyExistsException> {
            SemanticFixtureRunner.writePrivateExecutionEvidence(directory, evidence.copy(status = "PRIVATE_REPLACEMENT"))
        }
        assertContentEquals(originalBytes, Files.readAllBytes(path))
        assertEquals("PRIVATE_UNRELATED_SENTINEL", Files.readString(sibling))
    }

    @Test fun `per-attempt private writer rejects unplanned labels and lossy records`(@TempDir directory: Path) {
        val evidence = SemanticFixtureRunner.executionEvidence("dependency", digest, digest, originalFailure())
        for (label in listOf("../escape", "sixth-probe", "positive/child")) {
            assertFailsWith<IllegalArgumentException> {
                SemanticFixtureRunner.writePrivateExecutionEvidence(directory, evidence.copy(label = label))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            SemanticFixtureRunner.writePrivateExecutionEvidence(directory, evidence.copy(originalOutcome = null))
        }
        Files.list(directory).use { assertEquals(0L, it.count()) }
    }
}

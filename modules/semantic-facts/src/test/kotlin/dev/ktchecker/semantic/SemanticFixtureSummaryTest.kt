package dev.ktchecker.semantic

import dev.ktchecker.contracts.AnalysisMetrics
import dev.ktchecker.contracts.ArtifactIdentity
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
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
}

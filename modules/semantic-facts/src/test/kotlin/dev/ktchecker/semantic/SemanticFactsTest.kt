package dev.ktchecker.semantic

import dev.ktchecker.contracts.ArtifactIdentity
import dev.ktchecker.contracts.Digests
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class SemanticFactsTest {
    private val bytes = "fun f() = 1\n".toByteArray()
    private val source = SemanticSource("src/A.kt", Digests.sha256(bytes), bytes.size.toLong(), "main")
    private val artifact = ArtifactIdentity("fixture-only", Digests.sha256("fixture"), Path.of("fixture.jar").toAbsolutePath().toString())
    private fun request() = SemanticFactsRequest(Digests.sha256("snapshot"), Digests.sha256("analysis"), Digests.sha256("tools"),
        listOf(artifact), artifact, Path.of(".").toAbsolutePath().normalize().toString(), listOf(source),
        listOf(SemanticSourceSet("main")), "main", "jvm", "fixture", listOf("-language-version", "2.4"))
    private fun shard(request: SemanticFactsRequest = request()) = SemanticFactShard(request.digest(), request.snapshotId,
        request.analysisId, request.toolchainId, request.pluginArtifact.digest, SEMANTIC_COMPILER_VERSION, source,
        CompilerSourceContext("exact-compiler-module", "JVM", false, emptyList()), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), emptyList())

    @Test fun `UTF16 columns map exactly to UTF8 bytes across CRLF and supplementary characters`() {
        val text = "// é🙂\r\nval π = \"é🙂\"\r\n"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val input = source.copy(digest = Digests.sha256(bytes), size = bytes.size.toLong())
        val coordinates = SourceCoordinates(input, bytes)
        val span = coordinates.span(1, 4, 1, 5)
        assertEquals("π", bytes.copyOfRange(span.startByte, span.endByte).toString(Charsets.UTF_8))
        val across = coordinates.span(0, 3, 1, 5)
        assertEquals("é🙂\r\nval π", bytes.copyOfRange(across.startByte, across.endByte).toString(Charsets.UTF_8))
        assertEquals(input.digest, span.digest)
        assertEquals(bytes.size, coordinates.span(2, 0, 2, 0).endByte)
    }

    @Test fun `coordinates refuse surrogate splits out of range reversed and changed bytes`() {
        val text = "🙂\n".toByteArray()
        val input = source.copy(digest = Digests.sha256(text), size = text.size.toLong())
        val coordinates = SourceCoordinates(input, text)
        listOf({ coordinates.span(0, 1, 0, 2) }, { coordinates.span(3, 0, 3, 0) },
            { coordinates.span(0, 2, 0, 0) }).forEach { action ->
            assertEquals("semantic_source_range_invalid", assertFailsWith<SemanticFactException> { action() }.code)
        }
        assertEquals("semantic_source_digest_mismatch", assertFailsWith<SemanticFactException> {
            SourceCoordinates(input, "changed".toByteArray())
        }.code)
    }

    @Test fun `invalid UTF8 and unqualified BOM mapping are not silently normalized`() {
        val invalid = byteArrayOf(0xc3.toByte(), 0x28)
        assertEquals("semantic_source_encoding_unavailable", assertFailsWith<SemanticFactException> {
            SourceCoordinates(source.copy(digest = Digests.sha256(invalid), size = invalid.size.toLong()), invalid)
        }.code)
        val bom = "\uFEFFfun f() = 1".toByteArray()
        assertEquals("semantic_source_bom_unqualified", assertFailsWith<SemanticFactException> {
            SourceCoordinates(source.copy(digest = Digests.sha256(bom), size = bom.size.toLong()), bom)
        }.code)
    }

    @Test fun `selected tuple is never qualified by a successful data envelope`() {
        val input = request()
        val bundle = SemanticFactsValidation.assemble(input, listOf(shard(input)), 0)
        assertTrue(bundle.completeForSupportedVocabulary)
        assertEquals("extracted", bundle.state)
        assertFalse(bundle.tupleQualified)
        assertFalse(bundle.customRulesQualified)
        assertTrue(bundle.limitations.isNotEmpty())
    }

    @Test fun `compiler failure cannot be hidden behind successfully emitted facts`() {
        val input = request()
        val bundle = SemanticFactsValidation.assemble(input, listOf(shard(input)), 1)
        assertFalse(bundle.completeForSupportedVocabulary)
        assertEquals("incomplete", bundle.state)
        assertContains(bundle.reasons, "semantic_compilation_failed")
    }

    @Test fun `missing source facts and missing compiler completion are explicitly incomplete`() {
        val bundle = SemanticFactsValidation.assemble(request(), emptyList(), null)
        assertFalse(bundle.completeForSupportedVocabulary)
        assertContains(bundle.reasons, "semantic_sources_not_visited")
        assertContains(bundle.reasons, "semantic_compiler_completion_missing")
    }

    @Test fun `foreign snapshot plugin or analysis evidence is rejected rather than merged`() {
        val input = request()
        val variants = listOf(shard(input).copy(snapshotId = Digests.sha256("other")),
            shard(input).copy(pluginDigest = Digests.sha256("other")), shard(input).copy(analysisId = Digests.sha256("other")))
        variants.forEach {
            val bundle = SemanticFactsValidation.assemble(input, listOf(it), 0)
            assertEquals("failed", bundle.state)
            assertTrue(bundle.shards.isEmpty())
            assertContains(bundle.reasons, "semantic_evidence_binding_invalid")
        }
    }

    @Test fun `unknown resolution abstains even when the compiler exits zero`() {
        val input = request()
        val bundle = SemanticFactsValidation.assemble(input, listOf(shard(input).copy(
            unknown = listOf(UnknownSemanticFact("call", "unresolved_or_error_reference")))), 0)
        assertFalse(bundle.completeForSupportedVocabulary)
        assertContains(bundle.reasons, "semantic_facts_unknown")
    }

    @Test fun `unknown nested type cannot be laundered by an empty unknown list`() {
        val input = request()
        val type = TypeFact("class", "kotlin.collections.List", false,
            arguments = listOf(TypeProjectionFact("invariant", TypeFact("unknown", reason = "missing_dependency"))))
        val span = SourceCoordinates(source, bytes).span(0, 0, 0, 1)
        val bundle = SemanticFactsValidation.assemble(input, listOf(shard(input).copy(types = listOf(TypeUseFact(span, type)))), 0)
        assertFalse(bundle.completeForSupportedVocabulary)
        assertContains(bundle.reasons, "semantic_facts_unknown")
    }

    @Test fun `cyclic missing and unrelated refinement inputs stay unknown`() {
        assertContains(SemanticFactsValidation.graphProblems(listOf(SemanticSourceSet("a", listOf("b")),
            SemanticSourceSet("b", listOf("a"))), "a", setOf("a")), "semantic_refinement_cycle")
        assertContains(SemanticFactsValidation.graphProblems(listOf(SemanticSourceSet("a", listOf("missing"))),
            "a", setOf("a")), "semantic_source_set_unknown")
        assertContains(SemanticFactsValidation.graphProblems(listOf(SemanticSourceSet("a"), SemanticSourceSet("b")),
            "a", setOf("b")), "semantic_refinement_outside_compilation")
    }

    @Test fun `actual compiler refinement contradicting declared graph is incomplete`() {
        val input = request().copy(sourceSets = listOf(SemanticSourceSet("common"), SemanticSourceSet("main", listOf("common"))))
        val bundle = SemanticFactsValidation.assemble(input, listOf(shard(input)), 0)
        assertFalse(bundle.completeForSupportedVocabulary)
        assertContains(bundle.reasons, "semantic_refinement_graph_mismatch")
    }

    @Test fun `duplicate shards and missing symbol targets are rejected`() {
        val input = request()
        assertEquals("failed", SemanticFactsValidation.assemble(input, listOf(shard(input), shard(input)), 0).state)
        val span = SourceCoordinates(source, bytes).span(0, 0, 0, 1)
        val call = CallFact(span, "function_call", Digests.sha256("missing-target"), null, TypeFact("class", "kotlin.Int", false))
        val bundle = SemanticFactsValidation.assemble(input, listOf(shard(input).copy(calls = listOf(call))), 0)
        assertEquals("failed", bundle.state)
        assertContains(bundle.reasons, "semantic_symbol_graph_invalid")
    }
}

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
        val bundle = SemanticFactsValidation.assemble(input, listOf(shard(input)), 0, sourceBytes = { bytes })
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

    @Test fun `unverified data envelopes cannot certify source coordinate coverage`() {
        val input = request()
        val bundle = SemanticFactsValidation.assemble(input, listOf(shard(input)), 0)
        assertFalse(bundle.completeForSupportedVocabulary)
        assertContains(bundle.reasons, "semantic_source_bytes_unverified")
    }

    @Test fun `invalid roots are structured failures and never reach the byte reader`() {
        val input = request().copy(sourceRoot = "invalid\u0000root")
        var reads = 0
        val bundle = SemanticFactsValidation.assemble(input, listOf(shard(input)), 0, sourceBytes = { reads++; bytes })
        assertFalse(bundle.completeForSupportedVocabulary)
        assertContains(bundle.reasons, "semantic_root_invalid")
        assertEquals(0, reads)
    }

    @Test fun `closed type vocabulary rejects invented kinds and malformed structures`() {
        val malformed = listOf(TypeFact("bogus"), TypeFact("class"), TypeFact("class", "kotlin.Int"),
            TypeFact("flexible"), TypeFact("flexible", components = listOf(TypeFact("class", "kotlin.Int", false))),
            TypeFact("type_parameter", nullable = false, parameterOwner = "guessed", parameterIndex = 0),
            TypeFact("type_parameter", nullable = false, parameterOwner = Digests.sha256("owner"), parameterIndex = -1),
            TypeFact("definitely_not_null", nullable = true, components = listOf(TypeFact("class", "kotlin.Int", false))),
            TypeFact("class", "kotlin.List", false, listOf(TypeProjectionFact("guessed", TypeFact("class", "kotlin.Int", false)))),
            TypeFact("class", "kotlin.List", false, listOf(TypeProjectionFact("star", TypeFact("class", "kotlin.Int", false)))))
        malformed.forEach { type ->
            assertFalse(type.known, "Malformed type became known: $type")
            val input = request()
            val span = SourceCoordinates(source, bytes).span(0, 0, 0, 1)
            val bundle = SemanticFactsValidation.assemble(input, listOf(shard(input).copy(types = listOf(TypeUseFact(span, type)))),
                0, sourceBytes = { bytes })
            assertFalse(bundle.completeForSupportedVocabulary)
            assertContains(bundle.reasons, "semantic_facts_unknown")
        }
    }

    @Test fun `exact source validation rejects inconsistent UTF16 and byte spans`() {
        val input = request()
        val span = SourceCoordinates(source, bytes).span(0, 0, 0, 1)
        val variants = listOf(span.copy(endByte = 2), span.copy(endLine = 3), span.copy(startColumn = 8, endColumn = 9))
        variants.forEach {
            val bundle = SemanticFactsValidation.assemble(input, listOf(shard(input).copy(
                types = listOf(TypeUseFact(it, TypeFact("class", "kotlin.Int", false))))), 0, sourceBytes = { bytes })
            assertEquals("failed", bundle.state)
            assertFalse(bundle.completeForSupportedVocabulary)
            assertTrue(bundle.reasons.any { it in setOf("semantic_source_coordinate_mismatch", "semantic_source_range_invalid") })
        }
        assertContains(SemanticFactsValidation.assemble(input, listOf(shard(input)), 0,
            sourceBytes = { "changed".toByteArray() }).reasons, "semantic_source_digest_mismatch")
    }

    @Test fun `symbol identity and argument parameter bindings cannot be forged`() {
        val input = request()
        val type = TypeFact("class", "kotlin.Int", false)
        val descriptor = SymbolFact("", "function", "fixture.f", "module", "Source", false,
            returnType = type, parameters = listOf(SemanticParameter(0, "value", type)))
        val symbol = descriptor.copy(id = resolvedSymbolIdentity(descriptor))
        val span = SourceCoordinates(source, bytes).span(0, 0, 0, 1)
        val call = CallFact(span, "function_call", symbol.id, null, type,
            arguments = listOf(ArgumentBindingFact(span, 0, "value")))
        val valid = shard(input).copy(symbols = listOf(symbol), calls = listOf(call))
        assertTrue(SemanticFactsValidation.assemble(input, listOf(valid), 0, sourceBytes = { bytes }).completeForSupportedVocabulary)
        val badArguments = listOf(call.copy(arguments = listOf(ArgumentBindingFact(span, 3, "value"))),
            call.copy(arguments = listOf(ArgumentBindingFact(span, 0, "other"))),
            call.copy(arguments = call.arguments + call.arguments))
        badArguments.forEach {
            val bundle = SemanticFactsValidation.assemble(input, listOf(valid.copy(calls = listOf(it))), 0, sourceBytes = { bytes })
            assertEquals("failed", bundle.state)
            assertContains(bundle.reasons, "semantic_argument_binding_invalid")
        }
        assertContains(SemanticFactsValidation.assemble(input, listOf(valid.copy(
            symbols = listOf(symbol.copy(qualifiedName = "fixture.other")))), 0, sourceBytes = { bytes }).reasons,
            "semantic_symbol_identity_invalid")
    }

    @Test fun `validated facts detach caller collections and are recursively unmodifiable`() {
        val input = request()
        val arguments = mutableListOf(TypeProjectionFact("star"))
        val types = mutableListOf(TypeUseFact(SourceCoordinates(source, bytes).span(0, 0, 0, 1),
            TypeFact("class", "kotlin.List", false, arguments)))
        val declarations = mutableListOf<String>()
        val shards = mutableListOf(shard(input).copy(types = types, declarations = declarations))
        val bundle = SemanticFactsValidation.assemble(input, shards, 0, sourceBytes = { bytes })
        assertTrue(bundle.completeForSupportedVocabulary)
        arguments.clear()
        types.clear()
        declarations += "foreign"
        shards.clear()
        assertEquals(1, bundle.shards.size)
        assertEquals(1, bundle.shards.single().types.size)
        assertEquals(1, bundle.shards.single().types.single().type.arguments.size)
        assertTrue(bundle.shards.single().declarations.isEmpty())
        assertFailsWith<UnsupportedOperationException> { (bundle.shards as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (bundle.shards.single().types as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (bundle.shards.single().types.single().type.arguments as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (bundle.limitations as MutableList<*>).clear() }
    }

    @Test fun `cyclic and overdeep DTO type trees fail closed instead of overflowing`() {
        val input = request()
        val children = mutableListOf<TypeFact>()
        val type = TypeFact("intersection", components = children)
        children += type
        assertFalse(type.known)
        val span = SourceCoordinates(source, bytes).span(0, 0, 0, 1)
        val bundle = SemanticFactsValidation.assemble(input, listOf(shard(input).copy(types = listOf(TypeUseFact(span, type)))),
            0, sourceBytes = { bytes })
        assertEquals("failed", bundle.state)
        assertContains(bundle.reasons, "semantic_fact_shape_limit")
        var repeated = TypeFact("class", "kotlin.Int", false)
        repeat(6) { repeated = TypeFact("intersection", components = List(64) { repeated }) }
        assertFalse(repeated.known) // Small shared DAG must not expand into unbounded validation work.
    }
}

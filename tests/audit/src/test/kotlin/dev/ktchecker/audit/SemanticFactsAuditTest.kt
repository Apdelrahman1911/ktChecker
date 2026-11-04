package dev.ktchecker.audit

import dev.ktchecker.contracts.ArtifactIdentity
import dev.ktchecker.contracts.Digests
import dev.ktchecker.semantic.*
import java.nio.file.Path
import kotlin.test.*

/**
 * Independent DTO/source-coordinate boundary tests, not compiler or FIR integration evidence.
 * Every compiler identity, observation and resolved symbol below is explicitly SYNTHETIC.
 * No artifact is opened or executed; successful assembly must still remain unqualified.
 */
class SemanticFactsAuditTest {
    private val integer = TypeFact("class", "kotlin.Int", false)

    private inner class Fixture(text: String = "// 🧩e\u0301\r\nfun f(value: Int) = value\r\n") {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val source = SemanticSource("owned/Example.kt", Digests.sha256(bytes), bytes.size.toLong(), "main")
        private val artifact = ArtifactIdentity("SYNTHETIC-NOT-EXECUTED", Digests.sha256("not a compiler"),
            Path.of("synthetic-not-executable.bin").toAbsolutePath().toString())
        val request = SemanticFactsRequest(
            Digests.sha256("synthetic snapshot"), Digests.sha256("synthetic analysis"),
            Digests.sha256("synthetic toolchain"), listOf(artifact), artifact,
            Path.of(".").toAbsolutePath().normalize().toString(), listOf(source),
            listOf(SemanticSourceSet("main")), "main", "jvm", "audit-main",
            listOf("-language-version", "2.4"))
        val span = SourceCoordinates(source, bytes).span(1, 4, 1, 5)

        fun shard(input: SemanticFactsRequest = request) = SemanticFactShard(
            input.digest(), input.snapshotId, input.analysisId, input.toolchainId,
            input.pluginArtifact.digest, SEMANTIC_COMPILER_VERSION, source,
            CompilerSourceContext("synthetic-main-module", "JVM", false, emptyList()),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

        fun symbol(vararg: Boolean = false): SymbolFact = canonical(SymbolFact(
            "", "function", "audit.f", "synthetic-main-module", "Source", false,
            source = span, returnType = integer,
            parameters = listOf(SemanticParameter(0, "value", integer, vararg))))

        fun call(symbol: SymbolFact) = CallFact(span, "function_call", symbol.id, null, integer,
            arguments = listOf(ArgumentBindingFact(span, 0, "value")))

        fun assemble(shard: SemanticFactShard = shard(), input: SemanticFactsRequest = request) =
            SemanticFactsValidation.assemble(input, listOf(shard), 0, sourceBytes = { bytes })
    }

    private fun canonical(symbol: SymbolFact) = symbol.copy(id = resolvedSymbolIdentity(symbol))

    @Test
    fun internallyConsistentSyntheticFactsAreNeverCompilerOrRuleQualification() {
        val fixture = Fixture()
        val symbol = fixture.symbol()
        val bundle = fixture.assemble(fixture.shard().copy(declarations = listOf(symbol.id),
            symbols = listOf(symbol), calls = listOf(fixture.call(symbol))))
        assertEquals("extracted", bundle.state)
        assertTrue(bundle.completeForSupportedVocabulary)
        assertFalse(bundle.tupleQualified)
        assertFalse(bundle.customRulesQualified)
        assertTrue(bundle.limitations.any { "dynamic/candidate/negative" in it })
    }

    @Test
    fun absenceOfVerifiedSourceBytesOrCompilerCompletionCannotBecomeComplete() {
        val fixture = Fixture()
        val request = fixture.request
        val shards = listOf(fixture.shard())
        val noBytes = SemanticFactsValidation.assemble(request, shards, 0)
        assertFalse(noBytes.completeForSupportedVocabulary)
        assertContains(noBytes.reasons, "semantic_source_bytes_unverified")
        val unavailable = SemanticFactsValidation.assemble(request, shards, 0,
            sourceBytes = { throw IllegalStateException("synthetic immutable capture unavailable") })
        assertFalse(unavailable.completeForSupportedVocabulary)
        assertContains(unavailable.reasons, "semantic_source_bytes_unavailable")
        val changed = SemanticFactsValidation.assemble(request, shards, 0, sourceBytes = {
            fixture.bytes.copyOf().also { it[0] = '!'.code.toByte() }
        })
        assertEquals("failed", changed.state)
        assertContains(changed.reasons, "semantic_source_digest_mismatch")
        val completionCases = listOf(
            SemanticFactsValidation.assemble(request, shards, null, sourceBytes = { fixture.bytes }) to "semantic_compiler_completion_missing",
            SemanticFactsValidation.assemble(request, shards, 1, sourceBytes = { fixture.bytes }) to "semantic_compilation_failed",
            SemanticFactsValidation.assemble(request, shards, 0, executedSuccessfully = false,
                sourceBytes = { fixture.bytes }) to "semantic_execution_unavailable")
        completionCases.forEach { (bundle, reason) ->
            assertFalse(bundle.completeForSupportedVocabulary)
            assertContains(bundle.reasons, reason)
        }
    }

    @Test
    fun closedTypeShapesAndNestedUnknownsPreventInventedKnownTypes() {
        val fixture = Fixture()
        val typeParameter = TypeFact("type_parameter", nullable = false,
            parameterOwner = Digests.sha256("synthetic generic owner"), parameterIndex = 0)
        val known = listOf(integer, typeParameter,
            TypeFact("flexible", components = listOf(integer, integer.copy(nullable = true))),
            TypeFact("intersection", components = listOf(integer, typeParameter)),
            TypeFact("definitely_not_null", nullable = false, components = listOf(typeParameter)),
            TypeFact("class", "kotlin.collections.List", false, listOf(TypeProjectionFact("star"))))
        known.forEach { assertTrue(it.known) }
        val unknown = listOf(
            TypeFact("invented_kind", "kotlin.Int", false), integer.copy(nullable = null),
            integer.copy(classifierId = " "), integer.copy(parameterIndex = 0),
            typeParameter.copy(parameterOwner = "name-only-owner"), typeParameter.copy(parameterIndex = 256),
            TypeFact("flexible", components = listOf(integer)),
            TypeFact("intersection", components = emptyList()),
            TypeFact("definitely_not_null", nullable = true, components = listOf(typeParameter)),
            TypeFact("class", "kotlin.collections.List", false, listOf(TypeProjectionFact("star", integer))),
            TypeFact("class", "kotlin.collections.List", false, listOf(TypeProjectionFact("out"))),
            TypeFact("class", "kotlin.collections.List", false,
                listOf(TypeProjectionFact("invariant", TypeFact("unknown", reason = "missing dependency")))),
            integer.copy(reason = "unresolved evidence"))
        unknown.forEach { type ->
            assertFalse(type.known)
            val bundle = fixture.assemble(fixture.shard().copy(types = listOf(TypeUseFact(fixture.span, type))))
            assertFalse(bundle.completeForSupportedVocabulary)
            assertContains(bundle.reasons, "semantic_facts_unknown")
        }
    }

    @Test
    fun everyShardEnvelopeIdentityIsCheckedBeforeFactsCanBeRetained() {
        val fixture = Fixture()
        val original = fixture.shard()
        val foreign = Digests.sha256("synthetic foreign evidence")
        val variants = listOf(
            original.copy(schemaVersion = 2), original.copy(adapterId = "other-adapter"),
            original.copy(requestDigest = foreign), original.copy(snapshotId = foreign),
            original.copy(analysisId = foreign), original.copy(toolchainId = foreign),
            original.copy(pluginDigest = foreign), original.copy(observedCompilerVersion = "2.4.20"),
            original.copy(input = original.input.copy(digest = foreign)),
            original.copy(input = original.input.copy(sourceSet = "unowned")),
            original.copy(input = original.input.copy(size = original.input.size + 1)))
        variants.forEach { shard ->
            val bundle = fixture.assemble(shard)
            assertEquals("failed", bundle.state)
            assertFalse(bundle.completeForSupportedVocabulary)
            assertTrue(bundle.shards.isEmpty())
            assertContains(bundle.reasons, "semantic_evidence_binding_invalid")
        }
    }

    @Test
    fun unicodeAndCrLfCoordinatesAreExactRatherThanByteRangePlausibility() {
        val fixture = Fixture()
        val coordinates = SourceCoordinates(fixture.source, fixture.bytes)
        val puzzle = coordinates.span(0, 3, 0, 5)
        assertEquals("🧩", fixture.bytes.copyOfRange(puzzle.startByte, puzzle.endByte).toString(Charsets.UTF_8))
        val combined = coordinates.span(0, 5, 0, 7)
        assertEquals("e\u0301", fixture.bytes.copyOfRange(combined.startByte, combined.endByte).toString(Charsets.UTF_8))
        assertEquals("semantic_source_range_invalid", assertFailsWith<SemanticFactException> {
            coordinates.span(0, 4, 0, 5)
        }.code)
        // CR/LF bytes are not extra compiler columns on the preceding line.
        assertEquals("semantic_source_range_invalid", assertFailsWith<SemanticFactException> {
            coordinates.span(0, 7, 0, 8)
        }.code)
        val variants = listOf(puzzle.copy(endByte = puzzle.endByte - 1),
            puzzle.copy(startColumn = 2), puzzle.copy(endColumn = 4),
            puzzle.copy(digest = Digests.sha256("other bytes")), puzzle.copy(sourceSet = "unowned"))
        variants.forEach { span ->
            val bundle = fixture.assemble(fixture.shard().copy(types = listOf(TypeUseFact(span, integer))))
            assertEquals("failed", bundle.state)
            assertFalse(bundle.completeForSupportedVocabulary)
            assertTrue(bundle.shards.isEmpty())
        }
    }

    @Test
    fun invalidUtf8AndBomDoNotAcquireCoordinatesThroughReplacementOrNormalization() {
        val fixture = Fixture()
        val cases = listOf(
            byteArrayOf(0xf0.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()) to "semantic_source_encoding_unavailable",
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()) to "semantic_source_encoding_unavailable",
            "\uFEFFfun f() = 1\r\n".toByteArray(Charsets.UTF_8) to "semantic_source_bom_unqualified")
        cases.forEach { (bytes, code) ->
            val source = fixture.source.copy(digest = Digests.sha256(bytes), size = bytes.size.toLong())
            assertEquals(code, assertFailsWith<SemanticFactException> { SourceCoordinates(source, bytes) }.code)
        }
        val mutable = fixture.bytes.copyOf()
        val coordinates = SourceCoordinates(fixture.source, mutable)
        mutable.fill(0)
        assertEquals(fixture.span, coordinates.span(1, 4, 1, 5))
    }

    @Test
    fun argumentNamesAndOrdinalsMustAgreeWithResolvedParametersAndVarargs() {
        val fixture = Fixture()
        val symbol = fixture.symbol()
        val call = fixture.call(symbol)
        val base = fixture.shard().copy(symbols = listOf(symbol))
        val malformed = listOf(call.copy(kind = "invented_call"),
            call.copy(arguments = listOf(ArgumentBindingFact(fixture.span, -1, "value"))),
            call.copy(arguments = listOf(ArgumentBindingFact(fixture.span, 1, "value"))),
            call.copy(arguments = listOf(ArgumentBindingFact(fixture.span, 0, "other"))),
            call.copy(arguments = call.arguments + call.arguments))
        malformed.forEach {
            val bundle = fixture.assemble(base.copy(calls = listOf(it)))
            assertEquals("failed", bundle.state)
            assertContains(bundle.reasons, "semantic_argument_binding_invalid")
        }
        val vararg = fixture.symbol(vararg = true)
        val repeated = fixture.call(vararg).let { it.copy(arguments = it.arguments + it.arguments) }
        assertTrue(fixture.assemble(base.copy(symbols = listOf(vararg), calls = listOf(repeated))).completeForSupportedVocabulary)
    }

    @Test
    fun canonicalDigestDoesNotExcuseMalformedSymbolVocabularyOrParameterShape() {
        val fixture = Fixture()
        val original = fixture.symbol()
        val malformed = listOf(original.copy(kind = "guessed-symbol"), original.copy(qualifiedName = ""),
            original.copy(compilerModule = " "), original.copy(origin = ""),
            original.copy(parameters = listOf(SemanticParameter(1, "value", integer))),
            original.copy(contextParameters = listOf(SemanticParameter(2, "context", integer))),
            original.copy(parameters = listOf(SemanticParameter(0, "", integer))))
        malformed.forEach {
            val bundle = fixture.assemble(fixture.shard().copy(symbols = listOf(canonical(it))))
            assertEquals("failed", bundle.state)
            assertContains(bundle.reasons, "semantic_symbol_identity_invalid")
        }
        val forged = fixture.assemble(fixture.shard().copy(symbols = listOf(original.copy(qualifiedName = "audit.other"))))
        assertContains(forged.reasons, "semantic_symbol_identity_invalid")
        val local = original.copy(local = true)
        assertNotEquals(resolvedSymbolIdentity(local), resolvedSymbolIdentity(local.copy(source = fixture.span.copy(startColumn = 3))))
        assertNotEquals(resolvedSymbolIdentity(original), resolvedSymbolIdentity(original.copy(returnType = integer.copy(nullable = true))))
    }

    @Test
    fun duplicateSymbolsAndEveryDanglingRelationFailRatherThanDropEdges() {
        val fixture = Fixture()
        val symbol = fixture.symbol()
        val missing = Digests.sha256("missing synthetic symbol")
        val base = fixture.shard().copy(symbols = listOf(symbol))
        val variants = listOf(base.copy(symbols = listOf(symbol, symbol)),
            base.copy(declarations = listOf(missing)),
            base.copy(calls = listOf(fixture.call(symbol).copy(targetSymbolId = missing))),
            base.copy(calls = listOf(fixture.call(symbol).copy(containingSymbolId = missing))),
            base.copy(expectActual = listOf(ExpectActualFact(symbol.id, missing, "MatchedSuccessfully",
                "not_checked", "main", "common"))))
        variants.forEach {
            val bundle = fixture.assemble(it)
            assertEquals("failed", bundle.state)
            assertContains(bundle.reasons, "semantic_symbol_graph_invalid")
            assertTrue(bundle.shards.isEmpty())
        }
    }

    @Test
    fun malformedRequestsNeverReachTheSourceReaderEvenWithPlausibleShards() {
        val fixture = Fixture()
        val unsafe = listOf("../foreign.kt", "/absolute.kt", "a/../b.kt", "a\\b.kt", "drive:x.kt", "a//b.kt", "a\u0000.kt")
        val requests = unsafe.map { path -> fixture.request.copy(sources = listOf(fixture.source.copy(path = path))) } +
            listOf(fixture.request.copy(compilerVersion = "2.4.20"), fixture.request.copy(adapterId = "unknown"),
                fixture.request.copy(compilerArguments = emptyList()),
                fixture.request.copy(sources = listOf(fixture.source, fixture.source)))
        requests.forEach { request ->
            var reads = 0
            val shard = fixture.shard(request).copy(input = request.sources.first())
            val bundle = SemanticFactsValidation.assemble(request, listOf(shard), 0, sourceBytes = {
                reads++
                fixture.bytes
            })
            assertEquals(0, reads)
            assertFalse(bundle.completeForSupportedVocabulary)
            assertContains(bundle.reasons, "semantic_source_bytes_unverified")
        }
    }

    @Test
    fun validatedRequestAndFactsAreDetachedBeforeInvokingSourceAuthority() {
        val fixture = Fixture()
        val projection = mutableListOf(TypeProjectionFact("star"))
        val types = mutableListOf(TypeUseFact(fixture.span,
            TypeFact("class", "kotlin.collections.List", false, projection)))
        val declarations = mutableListOf<String>()
        val observedParents = mutableListOf<String>()
        val requestParents = mutableListOf<String>()
        val sources = fixture.request.sources.toMutableList()
        val arguments = fixture.request.compilerArguments.toMutableList()
        val request = fixture.request.copy(sources = sources, compilerArguments = arguments,
            sourceSets = listOf(SemanticSourceSet("main", requestParents)))
        val digestBeforeCallback = request.digest()
        val shards = mutableListOf(fixture.shard(request).copy(types = types, declarations = declarations,
            compilerContext = fixture.shard().compilerContext.copy(dependsOnModules = observedParents)))
        val bundle = SemanticFactsValidation.assemble(request, shards, 0, sourceBytes = {
            sources.clear()
            arguments.clear()
            requestParents += "late request parent"
            observedParents += "late observed parent"
            declarations += "late declaration"
            projection.clear()
            types.clear()
            shards.clear()
            fixture.bytes
        })
        assertTrue(bundle.completeForSupportedVocabulary)
        assertEquals(digestBeforeCallback, bundle.requestDigest)
        val retained = bundle.shards.single()
        assertTrue(retained.declarations.isEmpty())
        assertTrue(retained.compilerContext.dependsOnModules.isEmpty())
        assertEquals(1, retained.types.single().type.arguments.size)
        listOf(bundle.shards, bundle.reasons, bundle.limitations, retained.declarations,
            retained.compilerContext.dependsOnModules, retained.types, retained.types.single().type.arguments).forEach {
            assertFailsWith<UnsupportedOperationException> { (it as MutableList<*>).clear() }
        }
    }

    @Test
    fun cyclesExcessiveDepthAndWideTypeTreesFailClosedBeforeSourceAccess() {
        val fixture = Fixture()
        val children = mutableListOf<TypeFact>()
        val cyclic = TypeFact("intersection", components = children)
        children += cyclic
        var deep = integer
        repeat(30) { deep = TypeFact("intersection", components = listOf(deep)) }
        val wide = TypeFact("class", "kotlin.List", false, List(65) { TypeProjectionFact("star") })
        listOf(cyclic, deep, wide).forEach { type ->
            assertFalse(type.known)
            var reads = 0
            val bundle = SemanticFactsValidation.assemble(fixture.request,
                listOf(fixture.shard().copy(types = listOf(TypeUseFact(fixture.span, type)))), 0, sourceBytes = {
                    reads++
                    fixture.bytes
                })
            assertEquals("failed", bundle.state)
            assertContains(bundle.reasons, "semantic_fact_shape_limit")
            assertTrue(bundle.shards.isEmpty())
            assertEquals(0, reads)
        }
    }

    @Test
    fun exactRefinementGraphUnknownsAndAbsentSourceVisitsRemainIncomplete() {
        val fixture = Fixture()
        val request = fixture.request.copy(sourceSets = listOf(SemanticSourceSet("common"),
            SemanticSourceSet("main", listOf("common"))))
        val mismatch = fixture.assemble(fixture.shard(request), request)
        assertFalse(mismatch.completeForSupportedVocabulary)
        assertContains(mismatch.reasons, "semantic_refinement_graph_mismatch")
        val unknown = fixture.assemble(fixture.shard().copy(
            compilerContext = fixture.shard().compilerContext.copy(dependsOnModules = listOf("unmapped-module"))))
        assertContains(unknown.reasons, "semantic_refinement_module_unmapped")
        assertFalse(unknown.completeForSupportedVocabulary)
        val absent = SemanticFactsValidation.assemble(fixture.request, emptyList(), 0, sourceBytes = { fixture.bytes })
        assertContains(absent.reasons, "semantic_sources_not_visited")
        assertFalse(absent.completeForSupportedVocabulary)
        val duplicate = SemanticFactsValidation.assemble(fixture.request,
            listOf(fixture.shard(), fixture.shard()), 0, sourceBytes = { fixture.bytes })
        assertEquals("failed", duplicate.state)
        assertContains(duplicate.reasons, "semantic_duplicate_source_evidence")
    }
}

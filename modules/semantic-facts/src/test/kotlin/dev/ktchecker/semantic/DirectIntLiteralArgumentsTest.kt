package dev.ktchecker.semantic

import dev.ktchecker.contracts.ArtifactIdentity
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

/**
 * Synthetic DTO/wire/boundary controls, NOT compiler execution or rule evidence.
 * Real compiler-input cases are separately wired through DirectIntLiteralFixtureRunner.
 * Every method in this SOURCE proposal is UNRUN.
 */
class DirectIntLiteralArgumentsTest {
    private val bytes = "fun f() { sink(1) }\n".toByteArray()
    private val digest = Digests.sha256("synthetic literal contract, not compiler evidence")
    private val source = SemanticSource("src/A.kt", Digests.sha256(bytes), bytes.size.toLong(), "main")
    private val artifact = ArtifactIdentity("synthetic-only", digest,
        Path.of("synthetic-literal-fixture.jar").toAbsolutePath().normalize().toString())
    private val coordinates = SourceCoordinates(source, bytes)
    private val callSpan = coordinates.span(0, 10, 0, 17)
    private val argumentSpan = coordinates.span(0, 15, 0, 16)
    private val intType = TypeFact("class", "kotlin.Int", false)
    private val unitType = TypeFact("class", "kotlin.Unit", false)
    private val symbol = SymbolFact("", "function", "synthetic.literal.sink", "synthetic-library",
        "Library", false, returnType = unitType, parameters = listOf(SemanticParameter(0, "index", intType)))
        .let { it.copy(id = resolvedSymbolIdentity(it)) }

    private fun request(version: Int? = null) = SemanticFactsRequest(digest, digest, digest,
        listOf(artifact), artifact, Path.of(".").toAbsolutePath().normalize().toString(), listOf(source),
        listOf(SemanticSourceSet("main")), "main", "jvm", "synthetic-literal-contract",
        listOf("-language-version", "2.4"), directIntLiteralArgumentVersion = version)

    private fun observed(value: Int = 1) =
        DirectIntLiteralArgumentFact(DirectIntLiteralArgumentState.OBSERVED, value = value)

    private fun unsupported() = DirectIntLiteralArgumentFact(DirectIntLiteralArgumentState.UNSUPPORTED,
        reason = DirectIntLiteralArgumentReason.NOT_DIRECT_FIR_LITERAL)

    private fun unavailable() = DirectIntLiteralArgumentFact(DirectIntLiteralArgumentState.UNKNOWN,
        reason = DirectIntLiteralArgumentReason.TYPE_UNAVAILABLE)

    private fun argument(fact: DirectIntLiteralArgumentFact? = null, span: SemanticSpan? = argumentSpan) =
        ArgumentBindingFact(span, 0, "index", directIntLiteral = fact)

    private fun call(arguments: List<ArgumentBindingFact> = listOf(argument())) =
        CallFact(callSpan, "function_call", symbol.id, null, unitType, arguments = arguments)

    private fun shard(input: SemanticFactsRequest, calls: List<CallFact> = listOf(call())) =
        SemanticFactShard(input.digest(), input.snapshotId, input.analysisId, input.toolchainId,
            input.pluginArtifact.digest, SEMANTIC_COMPILER_VERSION, source,
            CompilerSourceContext("synthetic-main", "JVM", false, emptyList()), emptyList(), listOf(symbol),
            calls, emptyList(), emptyList(), emptyList())

    private fun withFact(input: SemanticFactsRequest, fact: DirectIntLiteralArgumentFact) =
        shard(input, listOf(call(listOf(argument(fact)))))

    private fun withVarargArguments(input: SemanticFactsRequest, count: Int, fact: DirectIntLiteralArgumentFact? = null): SemanticFactShard {
        val varargSymbol = symbol.copy(parameters = listOf(symbol.parameters.single().copy(vararg = true)))
            .let { it.copy(id = resolvedSymbolIdentity(it)) }
        val varargCall = call(List(count) { argument(fact) }).copy(targetSymbolId = varargSymbol.id)
        return shard(input, listOf(varargCall)).copy(symbols = listOf(varargSymbol))
    }

    private fun assemble(input: SemanticFactsRequest, value: SemanticFactShard) =
        SemanticFactsValidation.assemble(input, listOf(value), 0, sourceBytes = { bytes.copyOf() })

    private fun assertRejected(input: SemanticFactsRequest, value: SemanticFactShard, reason: String) {
        val bundle = assemble(input, value)
        assertEquals("failed", bundle.state)
        assertContains(bundle.reasons, reason)
        assertTrue(bundle.shards.isEmpty())
        assertFalse(bundle.completeForSupportedVocabulary)
        assertFalse(bundle.tupleQualified)
        assertFalse(bundle.customRulesQualified)
    }

    @Test fun defaultRequestRetainsOldOrderedWireAndDigest() {
        val input = request()
        val oldWire = """{"snapshotId":"$digest","analysisId":"$digest","toolchainId":"$digest","compilerArtifacts":[${ContractJson.encodeToString(artifact)}],""" +
            """"pluginArtifact":${ContractJson.encodeToString(artifact)},"sourceRoot":${ContractJson.encodeToString(input.sourceRoot)},"sources":[${ContractJson.encodeToString(source)}],""" +
            """"sourceSets":[{"id":"main","dependsOn":[]}],"leafSourceSet":"main","target":"jvm","compilationId":"synthetic-literal-contract","compilerArguments":["-language-version","2.4"],""" +
            """"dependencyArtifacts":[],"compilerVersion":"2.4.21","adapterId":"kotlin-fir-2.4.21/v1","schemaVersion":1}"""
        assertEquals(oldWire, ContractJson.encodeToString(input))
        assertEquals(Digests.sha256("ktchecker:semantic-request:v1\u0000" +
            Digests.canonical(ContractJson.parseToJsonElement(oldWire))), input.digest())
        val decoded = ContractJson.decodeFromString<SemanticFactsRequest>(oldWire)
        assertNull(decoded.directIntLiteralArgumentVersion)
        assertEquals(oldWire, ContractJson.encodeToString(decoded))
    }

    @Test fun defaultArgumentRetainsOldOrderedWireAndAbsentMeaning() {
        val oldWire = """{"source":${ContractJson.encodeToString(argumentSpan)},"parameterIndex":0,"parameterName":"index"}"""
        assertEquals(oldWire, ContractJson.encodeToString(argument()))
        val decoded = ContractJson.decodeFromString<ArgumentBindingFact>(oldWire)
        assertNull(decoded.directIntLiteral)
        assertEquals(oldWire, ContractJson.encodeToString(decoded))
        assertEquals(ContractJson.encodeToString(LegacyArgumentBinding(argumentSpan, 0, "index")), oldWire)
    }

    @Test fun defaultShardRoundtripDoesNotInventLiteralOutcomes() {
        val input = request()
        val value = shard(input)
        val wire = ContractJson.encodeToString(value)
        assertFalse("directIntLiteral" in wire)
        val decoded = ContractJson.decodeFromString<SemanticFactShard>(wire)
        assertEquals(value, decoded)
        assertNull(decoded.calls.single().arguments.single().directIntLiteral)
        assertTrue(assemble(input, decoded).completeForSupportedVocabulary)
        // That flag is the OLD vocabulary. It says nothing about literal or descriptor coverage.
    }

    @Test fun optInRoundtripChangesRequestBindingButNotResolvedSymbolIdentity() {
        val input = request(1)
        assertNotEquals(request().digest(), input.digest())
        val requestWire = ContractJson.encodeToString(input)
        assertEquals(JsonPrimitive(1), ContractJson.parseToJsonElement(requestWire)
            .jsonObject["directIntLiteralArgumentVersion"])
        assertEquals(input, ContractJson.decodeFromString<SemanticFactsRequest>(requestWire))
        val value = withFact(input, observed(0))
        val wire = ContractJson.encodeToString(value)
        val decoded = ContractJson.decodeFromString<SemanticFactShard>(wire)
        assertEquals(value, decoded)
        assertEquals(1, decoded.calls.single().arguments.single().directIntLiteral!!.version)
        assertEquals(0, decoded.calls.single().arguments.single().directIntLiteral!!.value)
        assertEquals(ContractJson.encodeToString(symbol), ContractJson.encodeToString(decoded.symbols.single()))
        assertEquals(symbol.id, resolvedSymbolIdentity(decoded.symbols.single()))
    }

    @Test fun strictLegacyArgumentReaderRejectsNonemptyExtensionRatherThanDroppingIt() {
        val strictLegacy = Json { ignoreUnknownKeys = false }
        assertEquals(LegacyArgumentBinding(argumentSpan, 0, "index"),
            strictLegacy.decodeFromString<LegacyArgumentBinding>(ContractJson.encodeToString(argument())))
        assertFails {
            strictLegacy.decodeFromString<LegacyArgumentBinding>(ContractJson.encodeToString(argument(observed())))
        }
        // Default-byte compatibility is deliberately NOT nonempty reader or JVM ABI compatibility.
    }

    @Test fun unsupportedRequestedVersionsFailEvenWithNoOutcomes() {
        listOf(-1, 0, 2, Int.MAX_VALUE).forEach { version ->
            val input = request(version)
            assertContains(SemanticFactsValidation.requestProblems(input),
                "semantic_direct_int_literal_request_version_unsupported")
            assertRejected(input, shard(input), "semantic_direct_int_literal_request_version_unsupported")
        }
    }

    @Test fun unsolicitedObservedUnsupportedAndUnknownOutcomesAreRejected() {
        val input = request()
        listOf(observed(), unsupported(), unavailable()).forEach { fact ->
            assertRejected(input, withFact(input, fact), "semantic_direct_int_literal_not_requested")
        }
    }

    @Test fun observationVersionCannotBorrowV1Interpretation() {
        val input = request(1)
        listOf(-1, 0, 2).forEach { version ->
            assertRejected(input, withFact(input, observed().copy(version = version)),
                "semantic_direct_int_literal_observation_version_unsupported")
        }
    }

    @Test fun observedOutcomeRequiresValueAndForbidsReason() {
        val input = request(1)
        listOf(observed().copy(value = null), observed().copy(reason = DirectIntLiteralArgumentReason.NOT_DIRECT_FIR_LITERAL),
            observed().copy(value = null, reason = DirectIntLiteralArgumentReason.TYPE_UNAVAILABLE)).forEach { fact ->
            assertRejected(input, withFact(input, fact), "semantic_direct_int_literal_outcome_invalid")
        }
    }

    @Test fun nonobservedOutcomesRequireExclusiveStateSpecificReason() {
        val input = request(1)
        listOf(unsupported().copy(value = 1), unsupported().copy(reason = null),
            unsupported().copy(reason = DirectIntLiteralArgumentReason.TYPE_UNAVAILABLE),
            unavailable().copy(value = 1), unavailable().copy(reason = null),
            unavailable().copy(reason = DirectIntLiteralArgumentReason.LITERAL_KIND_NOT_INT)).forEach { fact ->
            assertRejected(input, withFact(input, fact), "semantic_direct_int_literal_outcome_invalid")
        }
    }

    @Test fun strictWireRejectsUnknownEnumsAndNonIntRepresentations() {
        val strict = Json { ignoreUnknownKeys = false; coerceInputValues = false }
        val wire = ContractJson.encodeToString(observed())
        listOf(wire.replace("\"OBSERVED\"", "\"FUTURE_STATE\""),
            wire.replace("\"value\":1", "\"value\":2147483648"),
            wire.replace("\"value\":1", "\"value\":1.5"),
            wire.replace("\"value\":1", "\"value\":true")).forEach { malformed ->
            assertNotEquals(wire, malformed)
            assertFails { strict.decodeFromString<DirectIntLiteralArgumentFact>(malformed) }
        }
        val unsupportedWire = ContractJson.encodeToString(unsupported())
        assertFails {
            strict.decodeFromString<DirectIntLiteralArgumentFact>(
                unsupportedWire.replace("\"NOT_DIRECT_FIR_LITERAL\"", "\"FUTURE_REASON\""))
        }
    }

    @Test fun observedLiteralRequiresPresentNonemptyRealArgumentAndCallSources() {
        val input = request(1)
        val variants = listOf(
            call(listOf(argument(observed(), null))),
            call(listOf(argument(observed(), argumentSpan.copy(synthetic = true)))),
            call(listOf(argument(observed(), coordinates.span(0, 15, 0, 15)))),
            call(listOf(argument(observed()))).copy(source = callSpan.copy(synthetic = true)))
        variants.forEach { candidate ->
            assertRejected(input, shard(input, listOf(candidate)), "semantic_direct_int_literal_source_invalid")
        }
    }

    @Test fun unavailableSourceReasonsMustMatchTheRetainedSourceShape() {
        val input = request(1)
        val missing = unavailable().copy(reason = DirectIntLiteralArgumentReason.SOURCE_UNAVAILABLE)
        val synthetic = unavailable().copy(reason = DirectIntLiteralArgumentReason.SOURCE_SYNTHETIC)
        val missingCall = call(listOf(argument(missing, null)))
        val syntheticCall = call(listOf(argument(synthetic, argumentSpan.copy(synthetic = true))))
        assertTrue(assemble(input, shard(input, listOf(missingCall))).completeForSupportedVocabulary)
        assertTrue(assemble(input, shard(input, listOf(syntheticCall))).completeForSupportedVocabulary)
        assertRejected(input, withFact(input, missing), "semantic_direct_int_literal_source_invalid")
        assertRejected(input, withFact(input, synthetic), "semantic_direct_int_literal_source_invalid")
        assertRejected(input, shard(input, listOf(call(listOf(argument(unavailable(), null))))),
            "semantic_direct_int_literal_source_invalid")
    }

    @Test fun existingResolvedParameterBindingCannotBeReplacedByLiteralSpelling() {
        val input = request(1)
        listOf(argument(observed()).copy(parameterIndex = -1),
            argument(observed()).copy(parameterIndex = 1),
            argument(observed()).copy(parameterName = "other"),
            argument(observed()).copy(parameterName = "")).forEach { malformed ->
            assertRejected(input, shard(input, listOf(call(listOf(malformed)))), "semantic_argument_binding_invalid")
        }
    }

    @Test fun literalSpanMustStayInsideItsExactCallAndSource() {
        val input = request(1)
        listOf(coordinates.span(0, 0, 0, 1), argumentSpan.copy(path = "src/Other.kt"),
            argumentSpan.copy(digest = Digests.sha256("other source")),
            argumentSpan.copy(sourceSet = "other")).forEach { misplaced ->
            assertRejected(input, shard(input, listOf(call(listOf(argument(observed(), misplaced))))),
                "semantic_direct_int_literal_source_invalid")
        }
    }

    @Test fun literalFactsAreFunctionCallAndPerShardSourceBound() {
        val input = request(1)
        val literalCall = call(listOf(argument(observed())))
        listOf("property_access", "callable_reference").forEach { kind ->
            assertRejected(input, shard(input, listOf(literalCall.copy(kind = kind))),
                "semantic_direct_int_literal_binding_invalid")
        }
        val other = source.copy(path = "src/B.kt")
        val pair = input.copy(sources = listOf(source, other))
        val misplaced = shard(pair, listOf(literalCall)).copy(input = other)
        val bundle = SemanticFactsValidation.assemble(pair, listOf(shard(pair), misplaced), 0,
            sourceBytes = { bytes.copyOf() })
        assertEquals("failed", bundle.state)
        assertContains(bundle.reasons, "semantic_direct_int_literal_binding_invalid")
    }

    @Test fun oneForeignRequestOrPluginBindingRejectsTheWholeShardSet() {
        val other = source.copy(path = "src/B.kt")
        val input = request(1).copy(sources = listOf(source, other))
        val first = withFact(input, observed())
        val second = shard(input, emptyList()).copy(input = other, symbols = emptyList())
        val oldRequest = input.copy(directIntLiteralArgumentVersion = null)
        listOf(second.copy(requestDigest = oldRequest.digest()),
            second.copy(pluginDigest = Digests.sha256("different plugin")),
            second.copy(toolchainId = Digests.sha256("different invocation"))).forEach { foreign ->
            val bundle = SemanticFactsValidation.assemble(input, listOf(first, foreign), 0, sourceBytes = { bytes.copyOf() })
            assertEquals("failed", bundle.state)
            assertTrue(bundle.shards.isEmpty())
            assertContains(bundle.reasons, "semantic_evidence_binding_invalid")
        }
    }

    @Test fun absentUnsupportedAndUnknownDoNotBecomeLiteralCoverage() {
        val input = request(1)
        listOf<DirectIntLiteralArgumentFact?>(null, unsupported(), unavailable()).forEach { fact ->
            val bundle = assemble(input, shard(input, listOf(call(listOf(argument(fact))))))
            assertTrue(bundle.completeForSupportedVocabulary) // Only the pre-existing core vocabulary.
            assertEquals(fact, bundle.shards.single().calls.single().arguments.single().directIntLiteral)
            assertFalse(bundle.tupleQualified)
            assertFalse(bundle.customRulesQualified)
            assertNull(fact?.value)
        }
    }

    @Test fun exactTypeGuardRejectsUnsignedWrongNullableGenericAndUnknownTypes() {
        assertTrue(isDirectIntLiteralType(intType))
        listOf(TypeFact("class", "kotlin.UInt", false), TypeFact("class", "kotlin.Long", false),
            intType.copy(nullable = true), intType.copy(nullable = null), intType.copy(reason = "unresolved"),
            intType.copy(arguments = listOf(TypeProjectionFact("invariant", intType))),
            intType.copy(components = listOf(intType)), intType.copy(parameterOwner = digest),
            intType.copy(parameterIndex = 0),
            TypeFact("flexible", components = listOf(intType, intType.copy(nullable = true))),
            TypeFact("unknown", reason = "compiler_type_unresolved"),
            TypeFact("class", "Int", false)).forEach { candidate ->
            assertFalse(isDirectIntLiteralType(candidate), "Not the exact supported Int type: $candidate")
        }
        // This is the exact guard called by FIR extraction, not a fake FIR object or signedness oracle.
    }

    @Test fun freezingDetachesArgumentInventoryAndScalarOutcome() {
        val input = request(1)
        val fact = observed()
        val arguments = mutableListOf(argument(fact))
        val value = shard(input, listOf(call(arguments)))
        val bundle = assemble(input, value)
        arguments.clear()
        val frozen = bundle.shards.single().calls.single().arguments
        assertEquals(1, frozen.size)
        assertEquals(fact, frozen.single().directIntLiteral)
        assertNotSame(fact, frozen.single().directIntLiteral)
        assertFailsWith<UnsupportedOperationException> {
            (frozen as MutableList<ArgumentBindingFact>).clear()
        }
        assertFalse(bundle.tupleQualified)
        assertFalse(bundle.customRulesQualified)
    }

    @Test fun requestFreezingRetainsCapabilityAndDigestWhileDetachingMutableInputs() {
        val arguments = mutableListOf("-language-version", "2.4")
        val input = request(1).copy(compilerArguments = arguments)
        val frozen = SemanticFactsFreeze.request(input)
        val before = frozen.digest()
        arguments += "-changed-after-freeze"
        assertEquals(1, frozen.directIntLiteralArgumentVersion)
        assertEquals(before, frozen.digest())
        assertNotEquals(input.digest(), frozen.digest())
        assertFailsWith<UnsupportedOperationException> {
            (frozen.compilerArguments as MutableList<String>).clear()
        }
    }

    @Test fun defaultVarargInventoryAboveLiteralLimitRemainsAcceptedAndWireStable() {
        val input = request()
        val count = DIRECT_INT_LITERAL_ARGUMENTS_PER_CALL_LIMIT + 1
        val value = withVarargArguments(input, count)
        val wire = ContractJson.encodeToString(value)
        assertFalse("directIntLiteral" in wire)
        val restored = ContractJson.decodeFromString<SemanticFactShard>(wire)
        val bundle = assemble(input, restored)
        assertEquals("extracted", bundle.state)
        assertTrue(bundle.completeForSupportedVocabulary) // Original vocabulary, not literal coverage.
        val frozen = bundle.shards.single()
        assertEquals(count, frozen.calls.single().arguments.size)
        assertTrue(frozen.calls.single().arguments.all { it.directIntLiteral == null })
        assertEquals(wire, ContractJson.encodeToString(frozen))
        assertFalse(bundle.tupleQualified)
        assertFalse(bundle.customRulesQualified)
    }

    @Test fun argumentInventoryIsBoundedBeforeCopyingOptionalOutcomes() {
        val input = request(1)
        val count = DIRECT_INT_LITERAL_ARGUMENTS_PER_CALL_LIMIT + 1
        assertRejected(input, withVarargArguments(input, count, observed()), "semantic_fact_shape_limit")
        // A requested extension cannot evade its shape bound by omitting every outcome.
        assertRejected(input, withVarargArguments(input, count), "semantic_fact_shape_limit")
        // An unsolicited nonempty extension cannot borrow legacy/default unlimited shape.
        val legacyInput = request()
        assertRejected(legacyInput, withVarargArguments(legacyInput, count, observed()), "semantic_fact_shape_limit")
    }

    @Test fun scalarOutcomeBudgetsAreBoundedPerShardAndAcrossTheBundle() {
        val input = request(1)
        val full = call(List(256) { argument(unsupported()) })
        val exact = shard(input, List(78) { full } + call(List(32) { argument(unsupported()) }))
        val frozen = SemanticFactsFreeze.shards(listOf(exact), 1)
        assertEquals(20_000, frozen.single().calls.sumOf { it.arguments.size })
        val overShard = shard(input, List(79) { full })
        assertEquals("semantic_direct_int_literal_shape_limit", assertFailsWith<SemanticFactException> {
            SemanticFactsFreeze.shards(listOf(overShard), 1)
        }.code)
        assertEquals(100_000, SemanticFactsFreeze.shards(List(5) { exact }, 5).sumOf { row ->
            row.calls.sumOf { it.arguments.size }
        })
        assertEquals("semantic_direct_int_literal_shape_limit", assertFailsWith<SemanticFactException> {
            SemanticFactsFreeze.shards(List(6) { exact }, 6)
        }.code)
        // Deliberately malformed shared DTO graphs exercise allocation bounds, not semantic validity.
    }

    @Test fun wellFormedCallerValuesNeverAuthenticateCompilerOrQualifyARule() {
        val input = request(1)
        listOf(0, 1, Int.MIN_VALUE, Int.MAX_VALUE).forEach { value ->
            val bundle = assemble(input, withFact(input, observed(value)))
            assertTrue(bundle.completeForSupportedVocabulary)
            assertEquals(value, bundle.shards.single().calls.single().arguments.single().directIntLiteral!!.value)
            assertFalse(bundle.tupleQualified)
            assertFalse(bundle.customRulesQualified)
        }
        // These are caller-authored scalar values. No source parsing or unary-minus support is proven.
    }

    @Test fun futureFixtureCapabilityIsExplicitVersionedAndJvmOnly() {
        assertNull(semanticFixtureDirectIntLiteralArgumentVersion("jvm", null))
        assertNull(semanticFixtureDirectIntLiteralArgumentVersion("native", null))
        assertEquals(1, semanticFixtureDirectIntLiteralArgumentVersion("jvm", 1))
        listOf(0, -1, 2).forEach { version ->
            assertFailsWith<IllegalArgumentException> { semanticFixtureDirectIntLiteralArgumentVersion("jvm", version) }
        }
        assertFailsWith<IllegalArgumentException> { semanticFixtureDirectIntLiteralArgumentVersion("native", 1) }
    }

    @Serializable
    private data class LegacyArgumentBinding(val source: SemanticSpan?, val parameterIndex: Int, val parameterName: String)
}

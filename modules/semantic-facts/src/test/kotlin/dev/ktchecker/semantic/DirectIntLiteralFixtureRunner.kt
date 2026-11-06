package dev.ktchecker.semantic

/**
 * Separate, explicit future fixture entrypoint. It requests v1 through the ORIGINAL serial,
 * isolated compiler/execution-owner path; it does not authorize a run or load FIR in this JVM.
 * These authored expectations do not themselves establish a successful compiler execution.
 */
object DirectIntLiteralFixtureRunner {
    @JvmStatic fun main(args: Array<String>) {
        SemanticFixtureRunner.run(args, directIntLiteralArgumentVersion = DIRECT_INT_LITERAL_ARGUMENT_VERSION)
    }
}

internal const val DIRECT_INT_LITERAL_FIXTURE_PATH = "sources/jvmMain/DirectIntLiteralArguments.kt"

/** No implicit capability, unknown-version fallback, or Native substitution. */
internal fun semanticFixtureDirectIntLiteralArgumentVersion(mode: String, version: Int?): Int? {
    require(version == null || version == DIRECT_INT_LITERAL_ARGUMENT_VERSION) { "Unsupported literal fixture vocabulary" }
    require(version == null || mode == "jvm") { "This literal fixture slice selects JVM only" }
    return version
}

/**
 * Assertions over the actual bundle supplied by the existing runner, not a producer or authority.
 * Optional source shapes can be compiler-normalized to a literal or explicitly unsupported. We
 * do not unwrap/evaluate them here or infer that any source syntax is covered from one observation.
 */
internal fun directIntLiteralFixtureAssertions(request: SemanticFactsRequest,
                                               bundle: SemanticFactBundle): Map<String, Boolean> {
    val names = setOf("takeZero", "takeOne", "takeMaximum", "takeMinimum", "takeAboveIntMaximum", "takeBelowIntMinimum",
        "takeUnsignedMaximum", "takeLong", "takeUnsigned", "takeString",
        "takeBoolean", "takeChar", "takeNull", "takeVariable", "takeConstant", "takeCall", "takeArithmetic",
        "takeUnary", "takeParenthesized", "takeNamed", "takeNamedOrder")
    val symbols = bundle.shards.flatMap { it.symbols }.associateBy { it.id }
    val calls = bundle.shards.flatMap { it.calls }.filter {
        it.source.path == DIRECT_INT_LITERAL_FIXTURE_PATH && it.kind == "function_call"
    }
    val targets = names.associateWith { name ->
        calls.filter { symbols[it.targetSymbolId]?.qualifiedName == "fixture.literal.arguments.$name" }
    }
    fun argument(name: String, index: Int = 0, parameterName: String = "index"): ArgumentBindingFact? {
        val call = targets[name]?.singleOrNull() ?: return null
        val selected = symbols[call.targetSymbolId] ?: return null
        if (selected.kind != "function" || selected.local || selected.parameters.getOrNull(index)?.name != parameterName) return null
        return call.arguments.singleOrNull { it.parameterIndex == index && it.parameterName == parameterName }
    }
    fun observed(name: String, expected: Int): Boolean {
        val fact = argument(name)?.directIntLiteral ?: return false
        return fact.version == DIRECT_INT_LITERAL_ARGUMENT_VERSION &&
            fact.state == DirectIntLiteralArgumentState.OBSERVED && fact.value == expected && fact.reason == null
    }
    fun notObserved(name: String): Boolean {
        val fact = argument(name)?.directIntLiteral ?: return false
        return fact.version == DIRECT_INT_LITERAL_ARGUMENT_VERSION &&
            fact.state != DirectIntLiteralArgumentState.OBSERVED && fact.value == null && fact.reason != null
    }
    fun optionalCompilerShape(name: String, expected: Int, index: Int = 0, parameterName: String = "index"): Boolean {
        val fact = argument(name, index, parameterName)?.directIntLiteral ?: return false
        if (fact.version != DIRECT_INT_LITERAL_ARGUMENT_VERSION) return false
        return when (fact.state) {
            DirectIntLiteralArgumentState.OBSERVED -> fact.value == expected && fact.reason == null
            DirectIntLiteralArgumentState.UNSUPPORTED, DirectIntLiteralArgumentState.UNKNOWN -> fact.value == null && fact.reason != null
        }
    }
    val mapped = names.flatMap { name ->
        if (name == "takeNamedOrder") listOf(argument(name, 0, "first"), argument(name, 1, "second"))
        else listOf(argument(name))
    }
    return linkedMapOf(
        "literal_v1_exact_opt_in_request" to (request.directIntLiteralArgumentVersion == DIRECT_INT_LITERAL_ARGUMENT_VERSION &&
            request.schemaVersion == 1 && request.adapterId == SEMANTIC_ADAPTER_ID && request.target == "jvm" &&
            request.compilerVersion == SEMANTIC_COMPILER_VERSION),
        "literal_facts_bind_actual_request_and_plugin" to (bundle.requestDigest == request.digest() &&
            bundle.shards.isNotEmpty() && bundle.shards.all {
                it.requestDigest == request.digest() && it.pluginDigest == request.pluginArtifact.digest &&
                    it.observedCompilerVersion == SEMANTIC_COMPILER_VERSION
            }),
        "literal_all_fixture_targets_resolved_once" to targets.values.all { it.size == 1 },
        "literal_all_mapped_arguments_have_explicit_v1_outcomes" to mapped.all {
            it?.directIntLiteral?.version == DIRECT_INT_LITERAL_ARGUMENT_VERSION
        },
        "literal_actual_zero_one_maximum" to (observed("takeZero", 0) && observed("takeOne", 1) && observed("takeMaximum", Int.MAX_VALUE)),
        "literal_signed_boundaries_never_narrow_or_wrap" to (optionalCompilerShape("takeMinimum", Int.MIN_VALUE) &&
            listOf("takeAboveIntMaximum", "takeBelowIntMinimum", "takeUnsignedMaximum").all(::notObserved)),
        "literal_wrong_and_unsigned_kinds_abstain" to listOf("takeLong", "takeUnsigned", "takeString",
            "takeBoolean", "takeChar", "takeNull").all(::notObserved),
        "literal_no_variable_constant_call_or_arithmetic_propagation" to listOf(
            "takeVariable", "takeConstant", "takeCall", "takeArithmetic").all(::notObserved),
        "literal_unary_and_wrapper_shapes_never_guess_values" to (optionalCompilerShape("takeUnary", -1) &&
            optionalCompilerShape("takeParenthesized", 4) && optionalCompilerShape("takeNamed", 2)),
        "literal_named_order_uses_resolved_parameter_identity" to (optionalCompilerShape("takeNamedOrder", 5, 0, "first") &&
            optionalCompilerShape("takeNamedOrder", 6, 1, "second")),
        "literal_observed_spans_are_real_and_values_exclusive" to mapped.filter {
            it?.directIntLiteral?.state == DirectIntLiteralArgumentState.OBSERVED
        }.all { binding ->
            val source = binding?.source
            val fact = binding?.directIntLiteral
            source != null && !source.synthetic && fact != null && fact.value != null && fact.reason == null
        },
        "literal_no_tuple_or_custom_rule_qualification" to (!bundle.tupleQualified && !bundle.customRulesQualified),
    )
}

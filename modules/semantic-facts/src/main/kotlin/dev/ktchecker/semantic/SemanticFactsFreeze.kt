package dev.ktchecker.semantic

import java.util.Collections

/** Detached, recursively unmodifiable facts at the validation boundary. Raw DTOs are not proofs. */
internal object SemanticFactsFreeze {
    fun <T> list(values: Collection<T>): List<T> = Collections.unmodifiableList(values.toList())

    fun request(value: SemanticFactsRequest): SemanticFactsRequest = value.copy(
        compilerArtifacts = list(value.compilerArtifacts), sources = list(value.sources),
        sourceSets = list(value.sourceSets.map { it.copy(dependsOn = list(it.dependsOn)) }),
        compilerArguments = list(value.compilerArguments), dependencyArtifacts = list(value.dependencyArtifacts))

    fun shards(values: List<SemanticFactShard>, maximum: Int,
               directIntLiteralArgumentsRequested: Boolean = false): List<SemanticFactShard> {
        if (values.size > maximum) fail()
        val binaryOriginBudget = JvmBinaryOriginFreezeBudget()
        var literalArguments = 0
        return list(values.map { shard ->
            if (shard.symbols.size.toLong() + shard.calls.size + shard.types.size + shard.unknown.size > 20_000) fail()
            var nodes = 0
            var shardLiteralArguments = 0
            fun deepType(value: TypeFact, depth: Int): TypeFact {
                if (++nodes > 100_000 || depth > 24 || value.arguments.size > 64 || value.components.size > 64) fail()
                return value.copy(arguments = list(value.arguments.map { it.copy(type = it.type?.let { child -> deepType(child, depth + 1) }) }),
                    components = list(value.components.map { deepType(it, depth + 1) }))
            }
            fun type(value: TypeFact): TypeFact = deepType(value, 0)
            fun parameters(values: List<SemanticParameter>): List<SemanticParameter> {
                if (values.size > 256) fail()
                return list(values.map { it.copy(type = type(it.type)) })
            }
            fun arguments(values: List<ArgumentBindingFact>): List<ArgumentBindingFact> {
                // Preserve legacy/default vararg inventories, which can exceed parameter count.
                // Bound requested or present extension shapes before copying optional outcomes.
                if (values.size > DIRECT_INT_LITERAL_ARGUMENTS_PER_CALL_LIMIT &&
                    (directIntLiteralArgumentsRequested || values.any { it.directIntLiteral != null })) fail()
                return list(values.map { argument ->
                    val literal = argument.directIntLiteral
                    if (literal != null && (++literalArguments > DIRECT_INT_LITERAL_ARGUMENTS_PER_BUNDLE_LIMIT ||
                            ++shardLiteralArguments > DIRECT_INT_LITERAL_ARGUMENTS_PER_SHARD_LIMIT)) {
                        throw SemanticFactException("semantic_direct_int_literal_shape_limit", "Literal outcomes exceed the bounded DTO vocabulary")
                    }
                    argument.copy(directIntLiteral = literal?.copy())
                })
            }
            shard.copy(compilerContext = shard.compilerContext.copy(dependsOnModules = list(shard.compilerContext.dependsOnModules)),
                declarations = list(shard.declarations), symbols = list(shard.symbols.map { symbol ->
                    symbol.copy(returnType = symbol.returnType?.let(::type), receiverType = symbol.receiverType?.let(::type),
                        dispatchReceiverType = symbol.dispatchReceiverType?.let(::type), parameters = parameters(symbol.parameters),
                        contextParameters = parameters(symbol.contextParameters), flags = list(symbol.flags),
                        annotationClassIds = list(symbol.annotationClassIds), supertypes = list(symbol.supertypes.map(::type)),
                        aliasExpansion = symbol.aliasExpansion?.let(::type))
                }), calls = list(shard.calls.map { call ->
                    call.copy(resultType = type(call.resultType), dispatchReceiverType = call.dispatchReceiverType?.let(::type),
                        extensionReceiverType = call.extensionReceiverType?.let(::type), arguments = arguments(call.arguments))
                }), types = list(shard.types.map { it.copy(type = type(it.type)) }),
                expectActual = list(shard.expectActual), unknown = list(shard.unknown),
                jvmBinaryOrigins = JvmBinaryOriginValidation.freeze(shard.jvmBinaryOrigins, binaryOriginBudget))
        })
    }

    private fun fail(): Nothing = throw SemanticFactException("semantic_fact_shape_limit", "Fact tree exceeds the bounded DTO vocabulary")
}

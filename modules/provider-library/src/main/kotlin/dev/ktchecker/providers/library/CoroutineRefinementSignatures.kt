package dev.ktchecker.providers.library

import dev.ktchecker.semantic.SymbolFact
import dev.ktchecker.semantic.TypeFact

/** Signature filtering is additional validation, NEVER the missing per-binary ownership receipt. */
internal object CoroutineRefinementSignatures {
    fun plain(type: TypeFact?, name: String): Boolean = type?.let {
        it.kind == "class" && it.classifierId == name && it.nullable == false && it.arguments.isEmpty() && it.known
    } == true

    private fun parameter(type: TypeFact?, index: Int): Boolean = type?.let {
        it.kind == "type_parameter" && it.parameterIndex == index && it.nullable == false && it.known
    } == true

    private fun applied(type: TypeFact?, name: String, vararg arguments: TypeFact): Boolean = type?.let {
        it.kind == "class" && it.classifierId == name && it.nullable == false && it.known &&
            it.arguments.size == arguments.size && it.arguments.withIndex().all { (i, value) ->
                value.variance == "invariant" && value.type == arguments[i]
            }
    } == true

    fun intResult(type: TypeFact): Boolean = type.kind == "class" && type.classifierId == "kotlin.Result" &&
        type.nullable == false && type.known && type.arguments.size == 1 &&
        type.arguments.single().variance == "invariant" && plain(type.arguments.single().type, "kotlin.Int")

    fun matches(role: CoroutineCallableRole, symbol: SymbolFact): Boolean {
        if (symbol.kind != "function" || symbol.local || symbol.source != null ||
            "visibility:public" !in symbol.flags || symbol.contextParameters.isNotEmpty() ||
            symbol.parameters.any { it.vararg }) return false
        if (role == CoroutineCallableRole.THREAD_SLEEP_LONG) {
            return symbol.qualifiedName == "java.lang.Thread.sleep" && symbol.origin in setOf("Java.Library", "Library") &&
                "suspend" !in symbol.flags && symbol.typeParameterCount == 0 && symbol.receiverType == null &&
                (symbol.dispatchReceiverType == null || plain(symbol.dispatchReceiverType, "java.lang.Thread")) &&
                plain(symbol.returnType, "kotlin.Unit") && symbol.parameters.size == 1 &&
                symbol.parameters.single().index == 0 && plain(symbol.parameters.single().type, "kotlin.Long")
        }
        if (symbol.origin != "Library" || symbol.dispatchReceiverType != null) return false
        if (role == CoroutineCallableRole.DELAY_LONG) {
            return symbol.qualifiedName == "kotlinx.coroutines.delay" && "suspend" in symbol.flags &&
                symbol.receiverType == null && symbol.typeParameterCount == 0 && plain(symbol.returnType, "kotlin.Unit") &&
                symbol.parameters.size == 1 && symbol.parameters.single().index == 0 &&
                plain(symbol.parameters.single().type, "kotlin.Long")
        }
        if ("suspend" in symbol.flags || "inline" !in symbol.flags) return false
        return when (role) {
            CoroutineCallableRole.RUN_CATCHING -> {
                val result = symbol.returnType ?: return false
                val element = result.arguments.singleOrNull()?.type ?: return false
                symbol.qualifiedName == "kotlin.runCatching" && symbol.receiverType == null &&
                    symbol.typeParameterCount == 1 && parameter(element, 0) &&
                    applied(result, "kotlin.Result", element) && symbol.parameters.size == 1 &&
                    applied(symbol.parameters.single().type, "kotlin.Function0", element)
            }
            CoroutineCallableRole.GET_OR_THROW -> {
                val result = symbol.returnType ?: return false
                symbol.qualifiedName == "kotlin.getOrThrow" && symbol.typeParameterCount == 1 &&
                    parameter(result, 0) && applied(symbol.receiverType, "kotlin.Result", result) &&
                    symbol.parameters.isEmpty()
            }
            CoroutineCallableRole.GET_OR_ELSE -> {
                val result = symbol.returnType ?: return false
                val element = symbol.receiverType?.arguments?.singleOrNull()?.type ?: return false
                symbol.qualifiedName == "kotlin.getOrElse" && symbol.typeParameterCount == 2 &&
                    parameter(result, 0) && parameter(element, 1) &&
                    result.parameterOwner == element.parameterOwner &&
                    applied(symbol.receiverType, "kotlin.Result", element) &&
                    symbol.parameters.size == 1 && applied(symbol.parameters.single().type,
                        "kotlin.Function1", TypeFact("class", "kotlin.Throwable", false), result)
            }
            else -> false
        }
    }
}

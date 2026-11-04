@file:OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class)

package dev.ktchecker.semantic.adapters.kotlin2421

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import dev.ktchecker.semantic.*
import kotlinx.serialization.encodeToString
import org.jetbrains.kotlin.KtRealSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.expressions.impl.FirResolvedArgumentList
import org.jetbrains.kotlin.fir.references.FirResolvedErrorReference
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.resolve.multiplatform.ExpectActualMatchingCompatibility
import java.util.Collections
import java.util.IdentityHashMap

/** The only FIR-facing extractor. No compiler objects escape into the public fact model. */
internal class FirFactExtractor(
    private val emitter: SemanticFactEmitter,
    private val file: FirFile,
    private val input: SemanticSource,
) {
    private val symbols = IdentityHashMap<FirBasedSymbol<*>, SymbolFact>()
    private val localDeclarations = Collections.newSetFromMap(IdentityHashMap<FirBasedSymbol<*>, Boolean>())
    private val declarations = mutableSetOf<String>()
    private val calls = mutableListOf<CallFact>()
    private val types = mutableListOf<TypeUseFact>()
    private val relations = mutableListOf<ExpectActualFact>()
    private val unknown = mutableListOf<UnknownSemanticFact>()
    private var visited = 0

    fun extract(): SemanticFactShard {
        // Source affiliation comes from FIR container files, never a symbol's spelling or imports.
        var indexed = 0
        file.accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (++indexed > 100_000) throw SemanticFactException("semantic_fact_limit", "Source symbol index exceeds the bounded traversal")
                if (element is FirDeclaration) localDeclarations += element.symbol
                element.acceptChildren(this)
            }
        })
        var containing: String? = null
        file.accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (++visited > 100_000 || symbols.size + calls.size + types.size + unknown.size > 20_000) {
                    throw SemanticFactException("semantic_fact_limit", "Source exceeds the bounded semantic-fact vocabulary")
                }
                val previous = containing
                if (element is FirMemberDeclaration) {
                    val declaration = symbol(element.symbol)
                    declarations += declaration.id
                    containing = declaration.id
                    if (element.status.isActual) expectActual(element, declaration)
                }
                when (element) {
                    is FirFunctionCall, is FirPropertyAccessExpression, is FirCallableReferenceAccess ->
                        access(element as FirQualifiedAccessExpression, containing)
                    is FirResolvedTypeRef -> span(element.source)?.let { source ->
                        val fact = type(element.coneType)
                        types += TypeUseFact(source, fact)
                        unknownType(fact, "type", source)
                    }
                }
                element.acceptChildren(this)
                containing = previous
            }
        })
        val module = file.moduleData
        return SemanticFactShard(emitter.request.digest(), emitter.request.snapshotId, emitter.request.analysisId,
            emitter.request.toolchainId, emitter.request.pluginArtifact.digest, KotlinCompilerVersion.VERSION, input,
            CompilerSourceContext(module.name.asString(), module.platform.toString(), module.isCommon,
                module.dependsOnDependencies.map { it.name.asString() }.sorted()),
            declarations.sorted(), symbols.values.distinctBy { it.id }.sortedBy { it.id },
            calls.distinct().sortedWith(compareBy({ it.source.startByte }, { it.targetSymbolId })),
            types.distinct().sortedBy { it.source.startByte }, relations.distinct().sortedBy { it.actualSymbolId },
            unknown.distinct().sortedWith(compareBy({ it.source?.startByte ?: -1 }, { it.kind }, { it.reason })))
    }

    private fun access(expression: FirQualifiedAccessExpression, containing: String?) {
        val source = span(expression.source) ?: return
        val reference = expression.calleeReference
        if (reference !is FirResolvedNamedReference || reference is FirResolvedErrorReference) {
            unknown += UnknownSemanticFact("call", "unresolved_or_error_reference", source)
            return
        }
        val target = symbol(reference.resolvedSymbol)
        val result = expressionType(expression)
        unknownType(result, "call_type", source)
        val function = expression as? FirFunctionCall
        val arguments = (function?.argumentList as? FirResolvedArgumentList)?.mapping?.map { (argument, parameter) ->
            val parameters = (reference.resolvedSymbol.fir as? FirFunction)?.valueParameters.orEmpty()
            val index = parameters.indexOfFirst { it.symbol === parameter.symbol }
            if (index < 0) unknown += UnknownSemanticFact("argument", "parameter_binding_not_in_selected_signature", source)
            ArgumentBindingFact(span(argument.source), index, parameter.name.asString())
        }.orEmpty()
        if (function != null && function.argumentList.arguments.isNotEmpty() && function.argumentList !is FirResolvedArgumentList) {
            unknown += UnknownSemanticFact("argument", "argument_mapping_unavailable", source)
        }
        calls += CallFact(source, when (expression) {
            is FirFunctionCall -> "function_call"
            is FirCallableReferenceAccess -> "callable_reference"
            else -> "property_access"
        }, target.id, containing, result,
            expression.dispatchReceiver?.let(::expressionType),
            expression.extensionReceiver?.let(::expressionType), arguments)
    }

    private fun symbol(symbol: FirBasedSymbol<*>): SymbolFact {
        symbols[symbol]?.let { return it }
        val declaration = symbol.fir
        val member = declaration as? FirMemberDeclaration
        val callable = declaration as? FirCallableDeclaration
        val function = declaration as? FirFunction
        val location = sourceFile(symbol)?.let { span(symbol.source, it) }
        val returnType = callable?.returnTypeRef?.let(::resolvedType)
        val receiver = callable?.receiverParameter?.typeRef?.let(::resolvedType)
        val parameters = function?.valueParameters?.mapIndexed { index, parameter ->
            SemanticParameter(index, parameter.name.asString(), resolvedType(parameter.returnTypeRef), parameter.isVararg)
        }.orEmpty()
        val contextParameters = callable?.contextParameters?.mapIndexed { index, parameter ->
            SemanticParameter(index, parameter.name.asString(), resolvedType(parameter.returnTypeRef), parameter.isVararg)
        }.orEmpty()
        val kind = when (declaration) {
            is FirConstructor -> "constructor"
            is FirPropertyAccessor -> if (declaration.isGetter) "getter" else "setter"
            is FirValueParameter -> "value_parameter"
            is FirAnonymousFunction -> "lambda"
            is FirFunction -> "function"
            is FirBackingField -> "backing_field"
            is FirProperty -> "property"
            is FirTypeAlias -> "type_alias"
            is FirRegularClass -> "class"
            else -> "unsupported_symbol"
        }
        val qualifiedName = name(symbol)
        val descriptor = SymbolFact("", kind, qualifiedName, symbol.moduleData.name.asString(), symbol.origin.toString(),
            member?.isLocal == true || symbol is FirValueParameterSymbol || symbol is FirAnonymousFunctionSymbol,
            location, returnType, receiver, callable?.dispatchReceiverType?.let(::type),
            parameters, contextParameters, member?.typeParameters?.size ?: 0,
            member?.status?.let(::flags).orEmpty(), symbol.resolvedAnnotationClassIds.map { it.asFqNameString() }.sorted(),
            (declaration as? FirRegularClass)?.superTypeRefs?.map(::resolvedType).orEmpty(),
            (declaration as? FirTypeAlias)?.expandedTypeRef?.let(::resolvedType))
        // A source location is needed only to disambiguate genuinely local declarations. Moving a
        // non-local overload does not merge it with another overload or change its signature key.
        val key = descriptor.copy(source = if (descriptor.local) location else null)
        val result = descriptor.copy(id = Digests.sha256("ktchecker:resolved-symbol:v1\u0000" + ContractJson.encodeToString(key)))
        symbols[symbol] = result
        if (kind == "unsupported_symbol" || qualifiedName == "<unknown>") {
            unknown += UnknownSemanticFact("symbol", "unsupported_compiler_symbol_kind", location)
        }
        if (descriptor.local && location == null) unknown += UnknownSemanticFact("symbol", "local_symbol_source_unavailable")
        if (symbol.origin.fromSource && symbol.source != null && location == null) {
            unknown += UnknownSemanticFact("symbol", "source_symbol_location_unavailable")
        }
        (listOfNotNull(returnType, receiver, descriptor.dispatchReceiverType, descriptor.aliasExpansion) +
            parameters.map { it.type } + contextParameters.map { it.type } + descriptor.supertypes).forEach {
            unknownType(it, "symbol_type", location)
        }
        return result
    }

    private fun expectActual(declaration: FirMemberDeclaration, actual: SymbolFact) {
        val candidates = declaration.expectForActual
        if (candidates.isNullOrEmpty()) {
            unknown += UnknownSemanticFact("expect_actual", "compiler_matching_information_unavailable", actual.source)
            return
        }
        candidates.forEach { (compatibility, expects) ->
            expects.forEach { expected ->
                val target = symbol(expected)
                relations += ExpectActualFact(actual.id, target.id,
                    if (compatibility == ExpectActualMatchingCompatibility.MatchedSuccessfully) "matched_candidate" else "mismatched_candidate",
                    compatibility::class.java.simpleName, actual.source?.sourceSet, target.source?.sourceSet)
            }
        }
    }

    private fun flags(status: FirDeclarationStatus): List<String> = buildList {
        add("visibility:${status.visibility.name}")
        status.modality?.let { add("modality:${it.name.lowercase()}") }
        if (status.isExpect) add("expect")
        if (status.isActual) add("actual")
        if (status.isSuspend) add("suspend")
        if (status.isInline) add("inline")
        if (status.isExternal) add("external")
        if (status.isOverride) add("override")
        if (status.isConst) add("const")
        if (status.isLateInit) add("lateinit")
        if (status.isOperator) add("operator")
    }.sorted()

    private fun name(symbol: FirBasedSymbol<*>): String = when (symbol) {
        // Accessors/backing fields have no independent callable ID in this compiler. Their
        // compiler-owned property edge supplies the name; declaration kind distinguishes them.
        is FirPropertyAccessorSymbol -> name(symbol.propertySymbol)
        is FirBackingFieldSymbol -> name(symbol.propertySymbol)
        is FirCallableSymbol<*> -> symbol.callableId?.asSingleFqName()?.asString()
            ?: if (symbol.isLocal || symbol is FirValueParameterSymbol || symbol is FirAnonymousFunctionSymbol)
                "<local:${symbol.name.asString()}>" else "<unknown>"
        is FirClassLikeSymbol<*> -> symbol.classId.asFqNameString()
        else -> "<unknown>"
    }

    private fun sourceFile(symbol: FirBasedSymbol<*>): FirFile? {
        if (symbol in localDeclarations) return file
        return try {
            val provider = symbol.moduleData.session.firProvider
            when (symbol) {
                is FirCallableSymbol<*> -> provider.getFirCallableContainerFile(symbol)
                is FirClassLikeSymbol<*> -> provider.getFirClassifierContainerFileIfAny(symbol)
                else -> null
            }
        } catch (_: IllegalStateException) { null }
    }

    private fun span(source: KtSourceElement?, file: FirFile = this.file): SemanticSpan? {
        if (source == null || source.startOffset < 0 || source.endOffset < source.startOffset) return null
        val selected = emitter.source(file) ?: return null
        val mapping = file.sourceFileLinesMapping
        if (mapping == null) {
            unknown += UnknownSemanticFact("source", "compiler_line_mapping_unavailable")
            return null
        }
        return try {
            val (line, column) = mapping.getLineAndColumnByOffset(source.startOffset)
            val (endLine, endColumn) = mapping.getLineAndColumnByOffset(source.endOffset)
            emitter.coordinates(selected).span(line, column, endLine, endColumn, source.kind != KtRealSourceElementKind)
        } catch (e: SemanticFactException) {
            unknown += UnknownSemanticFact("source", e.code)
            null
        } catch (_: IndexOutOfBoundsException) {
            unknown += UnknownSemanticFact("source", "compiler_range_outside_mapping")
            null
        }
    }

    private fun resolvedType(ref: FirTypeRef): TypeFact = type((ref as? FirResolvedTypeRef)?.coneType)

    // Error-tolerant file checking can encounter a resolved callee before its expression or
    // receiver type is available. Missing types become explicit unknown facts and invalidate
    // completeness; this opt-in must never silently treat an absent type as a resolved one.
    @OptIn(UnresolvedExpressionTypeAccess::class)
    private fun expressionType(expression: FirExpression): TypeFact = type(expression.coneTypeOrNull)

    private fun type(value: ConeKotlinType?): TypeFact = type(value, 0, qualifyParameterOwner = true)

    private fun type(value: ConeKotlinType?, depth: Int, qualifyParameterOwner: Boolean): TypeFact {
        fun unknown(reason: String) = TypeFact("unknown", reason = reason)
        if (value == null) return unknown("compiler_type_unresolved")
        if (depth > 24 || value.typeArguments.size > 64) return unknown("compiler_type_depth_limit")
        fun nested(child: ConeKotlinType) = type(child, depth + 1, qualifyParameterOwner)
        return when (value) {
            is ConeErrorType -> unknown("compiler_error_type")
            is ConeFlexibleType -> TypeFact("flexible", components = listOf(nested(value.lowerBound), nested(value.upperBound)))
            is ConeDefinitelyNotNullType -> TypeFact("definitely_not_null", nullable = false, components = listOf(nested(value.original)))
            is ConeIntersectionType -> TypeFact("intersection", components = value.intersectedTypes.map(::nested))
            is ConeClassLikeType -> TypeFact("class", value.lookupTag.classId.asFqNameString(), value.isMarkedNullable,
                arguments = value.typeArguments.map { projection ->
                    if (projection.kind == ProjectionKind.STAR) TypeProjectionFact("star")
                    else TypeProjectionFact(projection.kind.name.lowercase(), (projection as? ConeKotlinTypeProjection)?.type?.let(::nested)
                        ?: unknown("compiler_projection_unavailable"))
                })
            is ConeTypeParameterType -> {
                val parameter = value.lookupTag.typeParameterSymbol
                val owner = parameter.containingDeclarationSymbol
                val parameters = when (owner) {
                    is FirCallableSymbol<*> -> owner.typeParameterSymbols
                    is FirClassLikeSymbol<*> -> owner.typeParameterSymbols
                    else -> emptyList()
                }
                val index = parameters.indexOfFirst { it === parameter }
                if (index < 0) unknown("compiler_type_parameter_owner_unavailable")
                else TypeFact("type_parameter", nullable = value.isMarkedNullable,
                    parameterOwner = if (qualifyParameterOwner) ownerKey(owner) else "signature-local-parameter",
                    parameterIndex = index)
            }
            else -> unknown("compiler_type_kind_unmodeled:${value::class.java.simpleName}")
        }
    }

    /** Bound generic signature rendering uses positional parameters, avoiding recursive owner IDs. */
    private fun ownerKey(owner: FirBasedSymbol<*>): String {
        val declaration = owner.fir
        val types = if (declaration is FirCallableDeclaration) {
            val refs = listOfNotNull(declaration.receiverParameter?.typeRef, declaration.returnTypeRef) +
                (declaration as? FirFunction)?.valueParameters.orEmpty().map { it.returnTypeRef }
            refs.map { type((it as? FirResolvedTypeRef)?.coneType, 0, qualifyParameterOwner = false) }
        } else emptyList()
        return Digests.sha256("ktchecker:type-parameter-owner:v1\u0000${owner.moduleData.name}\u0000${name(owner)}\u0000" +
            ContractJson.encodeToString(types))
    }

    private fun unknownType(type: TypeFact, kind: String, source: SemanticSpan?) {
        if (!type.known) unknown += UnknownSemanticFact(kind, type.reason ?: "nested_type_information_unknown", source)
    }
}

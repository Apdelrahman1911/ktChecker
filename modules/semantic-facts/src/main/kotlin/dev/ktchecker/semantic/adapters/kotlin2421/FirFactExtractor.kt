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
import org.jetbrains.kotlin.types.ConstantValueKind
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
    private val binaryOrigins = emitter.binaryOriginObserver?.let { mutableListOf<JvmBinaryOriginObservation>() }
    private val observeDirectIntLiteralArguments = when (emitter.request.directIntLiteralArgumentVersion) {
        null -> false
        DIRECT_INT_LITERAL_ARGUMENT_VERSION -> true
        else -> throw SemanticFactException("semantic_direct_int_literal_request_version_unsupported",
            "This producer supports only the explicitly requested literal argument vocabulary v1")
    }
    private var literalArgumentObservations = 0
    private var visited = 0
    private var convertedTypeNodes = 0

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
            unknown.distinct().sortedWith(compareBy({ it.source?.startByte ?: -1 }, { it.kind }, { it.reason })),
            jvmBinaryOrigins = binaryOrigins?.let(::jvmBinaryOriginSurvey))
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
        val mapping = (function?.argumentList as? FirResolvedArgumentList)?.mapping
        if (observeDirectIntLiteralArguments && mapping != null && mapping.size > DIRECT_INT_LITERAL_ARGUMENTS_PER_CALL_LIMIT) {
            throw SemanticFactException("semantic_direct_int_literal_shape_limit", "Too many mapped literal argument outcomes")
        }
        val arguments = mapping?.map { (argument, parameter) ->
            val parameters = (reference.resolvedSymbol.fir as? FirFunction)?.valueParameters.orEmpty()
            val index = parameters.indexOfFirst { it.symbol === parameter.symbol }
            if (index < 0) unknown += UnknownSemanticFact("argument", "parameter_binding_not_in_selected_signature", source)
            val argumentSource = span(argument.source)
            ArgumentBindingFact(argumentSource, index, parameter.name.asString(),
                directIntLiteral = if (observeDirectIntLiteralArguments && index >= 0)
                    directIntLiteralArgument(argument, argumentSource, source) else null)
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

    /**
     * Inspect exactly the compiler object in the resolved mapping. In particular, never unwrap
     * named/spread/smart-cast nodes, parse source text, evaluate unary calls, or follow properties.
     * Kotlin 2.4.21's FirLiteralExpression documents kind, not value's boxed class, as authoritative
     * for signedness. Parsed integer literals retain a Long payload after kind approximation.
     * Both the exact Int kind and the existing resolved Int type are still required.
     */
    private fun directIntLiteralArgument(argument: FirExpression, argumentSource: SemanticSpan?,
                                         callSource: SemanticSpan): DirectIntLiteralArgumentFact {
        if (++literalArgumentObservations > DIRECT_INT_LITERAL_ARGUMENTS_PER_SHARD_LIMIT) {
            throw SemanticFactException("semantic_direct_int_literal_shape_limit", "Literal argument outcomes exceed the bounded vocabulary")
        }
        fun unsupported(reason: DirectIntLiteralArgumentReason) =
            DirectIntLiteralArgumentFact(DirectIntLiteralArgumentState.UNSUPPORTED, reason = reason)
        fun unavailable(reason: DirectIntLiteralArgumentReason) =
            DirectIntLiteralArgumentFact(DirectIntLiteralArgumentState.UNKNOWN, reason = reason)
        if (argumentSource == null) return unavailable(DirectIntLiteralArgumentReason.SOURCE_UNAVAILABLE)
        if (argumentSource.synthetic || callSource.synthetic) return unavailable(DirectIntLiteralArgumentReason.SOURCE_SYNTHETIC)
        if (argument !is FirLiteralExpression) return unsupported(DirectIntLiteralArgumentReason.NOT_DIRECT_FIR_LITERAL)
        if (argument.kind != ConstantValueKind.Int) return unsupported(DirectIntLiteralArgumentReason.LITERAL_KIND_NOT_INT)
        val value = directIntLiteralSignedValue(argument.value)
            ?: return unsupported(DirectIntLiteralArgumentReason.VALUE_NOT_INT)
        val literalType = expressionType(argument)
        if (!literalType.known) return unavailable(DirectIntLiteralArgumentReason.TYPE_UNAVAILABLE)
        if (!isDirectIntLiteralType(literalType)) return unsupported(DirectIntLiteralArgumentReason.TYPE_NOT_EXACT_INT)
        return DirectIntLiteralArgumentFact(DirectIntLiteralArgumentState.OBSERVED, value = value)
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
        val result = descriptor.copy(id = resolvedSymbolIdentity(descriptor))
        symbols[symbol] = result
        emitter.binaryOriginObserver?.let {
            requireNotNull(binaryOrigins).add(it.observe(symbol, result.id, file.moduleData.platform))
        }
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
        if (++convertedTypeNodes > 100_000) throw SemanticFactException("semantic_type_limit", "Resolved type expansion exceeds the bounded vocabulary")
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
                val ownerIdentity = if (qualifyParameterOwner) ownerKey(owner) else ownerBaseKey(owner)
                if (index < 0 || ownerIdentity == null) unknown("compiler_type_parameter_owner_unavailable")
                else TypeFact("type_parameter", nullable = value.isMarkedNullable,
                    parameterOwner = ownerIdentity,
                    parameterIndex = index)
            }
            else -> unknown("compiler_type_kind_unmodeled:${value::class.java.simpleName}")
        }
    }

    /**
     * Bound generic signatures use positional parameters plus nonrecursive owner bases. Local
     * owners must carry their actual source identity: same-spelled generics in different enclosing
     * bodies must not share a type-parameter owner. Missing affiliation explicitly abstains.
     */
    private fun ownerKey(owner: FirBasedSymbol<*>): String? {
        val base = ownerBaseKey(owner) ?: return null
        val declaration = owner.fir
        fun signatureType(ref: FirTypeRef): TypeFact = type((ref as? FirResolvedTypeRef)?.coneType, 0, qualifyParameterOwner = false)
        val callable = declaration as? FirCallableDeclaration
        val member = declaration as? FirMemberDeclaration
        val groups = linkedMapOf(
            "receiver" to listOfNotNull(callable?.receiverParameter?.typeRef?.let(::signatureType)),
            "dispatch" to listOfNotNull(callable?.dispatchReceiverType?.let { type(it, 0, qualifyParameterOwner = false) }),
            "return" to listOfNotNull(callable?.returnTypeRef?.let(::signatureType)),
            "values" to (declaration as? FirFunction)?.valueParameters.orEmpty().map { signatureType(it.returnTypeRef) },
            "context" to callable?.contextParameters.orEmpty().map { signatureType(it.returnTypeRef) },
        )
        val bounds = member?.typeParameters.orEmpty().map { parameter -> parameter.symbol.resolvedBounds.map(::signatureType) }
        if ((groups.values.flatten() + bounds.flatten()).any { !it.known }) return null
        val modes = member?.typeParameters.orEmpty().map { "${it.symbol.variance}:${it.symbol.isReified}" }
        return Digests.sha256("ktchecker:type-parameter-owner:v1\u0000$base\u0000" +
            ContractJson.encodeToString(groups) + ContractJson.encodeToString(bounds) + ContractJson.encodeToString(modes) +
            ContractJson.encodeToString(member?.status?.let(::flags).orEmpty()))
    }

    private fun ownerBaseKey(owner: FirBasedSymbol<*>): String? {
        val name = name(owner)
        if (name == "<unknown>") return null
        val declaration = owner.fir
        val local = (declaration as? FirMemberDeclaration)?.isLocal == true || owner is FirAnonymousFunctionSymbol
        val localSource = if (local) sourceFile(owner)?.let { span(owner.source, it) } ?: return null else null
        return Digests.sha256("ktchecker:type-parameter-owner-base:v1\u0000${owner.moduleData.name}\u0000$name\u0000" +
            "${declaration::class.java.simpleName}\u0000" + ContractJson.encodeToString(localSource))
    }

    private fun unknownType(type: TypeFact, kind: String, source: SemanticSpan?) {
        if (!type.known) unknown += UnknownSemanticFact(kind, type.reason ?: "nested_type_information_unknown", source)
    }
}

/**
 * Exact signed payload decoding only, AFTER the producer has required ConstantValueKind.Int.
 * Kotlin's parsed integer literal payload can stay Long when its kind becomes Int. Narrow only
 * that exact boxed class, after both signed bounds; never call Number.toInt or reinterpret UInt.
 * An already boxed Int needs no conversion. This helper alone proves no FIR kind, type or origin.
 */
internal fun directIntLiteralSignedValue(value: Any?): Int? = when (value) {
    is Int -> value
    is Long -> if (value >= Int.MIN_VALUE.toLong() && value <= Int.MAX_VALUE.toLong()) value.toInt() else null
    else -> null
}

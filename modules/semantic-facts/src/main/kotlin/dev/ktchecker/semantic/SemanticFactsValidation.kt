package dev.ktchecker.semantic

import dev.ktchecker.contracts.Digests
import java.nio.file.Path

/** No project code, compiler, or plugin is loaded here; only supplied immutable source bytes. */
object SemanticFactsValidation {
    fun requestProblems(request: SemanticFactsRequest): List<String> = extractionRequestProblems(SemanticRequestData.of(request))

    fun observedRequestProblems(request: ObservedSemanticFactsRequest): List<String> {
        try { observedSemanticRequestBounds(request) }
        catch (_: SemanticFactException) { return listOf("observed_semantic_request_bound") }
        return extractionRequestProblems(SemanticRequestData.of(request))
    }

    internal fun extractionRequestProblems(request: SemanticRequestData): List<String> {
        val problems = mutableListOf<String>()
        if (request.schemaVersion != 1 || request.adapterId != SEMANTIC_ADAPTER_ID ||
            request.compilerVersion != SEMANTIC_COMPILER_VERSION) problems += "semantic_tuple_unavailable"
        if (request.directIntLiteralArgumentVersion != null &&
            request.directIntLiteralArgumentVersion != DIRECT_INT_LITERAL_ARGUMENT_VERSION) {
            problems += "semantic_direct_int_literal_request_version_unsupported"
        }
        if (listOf(request.snapshotId, request.analysisId, request.toolchainId).any { !Digests.valid(it) }) {
            problems += "semantic_identity_invalid"
        }
        val root = runCatching { Path.of(request.sourceRoot) }.getOrNull()
        if (root == null) problems += "semantic_root_invalid"
        else if (!root.isAbsolute) problems += "semantic_root_not_absolute"
        if (request.sources.isEmpty() || request.sources.size > 50_000) problems += "semantic_source_inventory_invalid"
        if (request.sources.map { it.path }.distinct().size != request.sources.size) problems += "semantic_duplicate_source"
        request.sources.forEach {
            if (!safePath(it.path) || !Digests.valid(it.digest) || it.size !in 0..4_194_304) problems += "semantic_source_invalid"
        }
        if (request.sources.sumOf { it.size.coerceIn(0, 4_194_305) } > 268_435_456) problems += "semantic_input_limit"
        if (request.compilerArtifacts.isEmpty() || request.compilerArtifacts.any { !Digests.valid(it.digest) } ||
            !Digests.valid(request.pluginArtifact.digest)) problems += "semantic_artifact_identity_invalid"
        if ((request.observed == null && request.target.isBlank()) || request.compilationId.isBlank()) problems += "semantic_context_missing"
        request.observed?.let { observed ->
            if (observed.profile != OBSERVED_SEMANTIC_REQUEST_PROFILE || observed.platform != "jvm" ||
                observed.target.any { it.isISOControl() } || observed.target.isNotEmpty() && observed.target.isBlank()) {
                problems += "observed_semantic_context_unsupported"
            }
            if (!Digests.valid(observed.modelEvidenceIdentity) ||
                !Regex("[a-f0-9]{32}").matches(observed.observedInvocationIdentity) ||
                !Regex("[a-f0-9]{32}").matches(observed.originalDeadlineIdentity) ||
                observed.originalDeadlineMillis !in 1..3_600_000) problems += "observed_semantic_origin_labels_invalid"
        }
        if (request.compilerArguments.isEmpty() || request.compilerArguments.size > 4096 ||
            request.compilerArguments.any { it.length > 16_384 || '\u0000' in it }) problems += "semantic_compiler_options_unknown"
        if (request.dependencyArtifacts.any { !Digests.valid(it.digest) }) problems += "semantic_dependency_identity_invalid"
        problems += graphProblems(request.sourceSets, request.leafSourceSet, request.sources.map { it.sourceSet }.toSet())
        return problems.distinct().sorted()
    }

    fun graphProblems(sourceSets: List<SemanticSourceSet>, leaf: String, used: Set<String>): List<String> {
        val problems = mutableSetOf<String>()
        if (sourceSets.isEmpty() || sourceSets.size > 256 || sourceSets.any { it.id.isBlank() || it.id.length > 256 }) {
            return listOf("semantic_source_set_inventory_invalid")
        }
        val byId = sourceSets.associateBy { it.id }
        if (byId.size != sourceSets.size) problems += "semantic_source_set_duplicate"
        if (leaf !in byId || used.any { it !in byId } || sourceSets.any { set -> set.dependsOn.any { it !in byId } }) {
            problems += "semantic_source_set_unknown"
        }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(id: String) {
            if (id in visiting) { problems += "semantic_refinement_cycle"; return }
            if (!visited.add(id)) return
            visiting += id
            byId[id]?.dependsOn?.forEach(::visit)
            visiting -= id
        }
        sourceSets.forEach { visit(it.id) }
        val closure = mutableSetOf<String>()
        fun include(id: String) { if (closure.add(id)) byId[id]?.dependsOn?.forEach(::include) }
        include(leaf)
        if (used.any { it !in closure }) problems += "semantic_refinement_outside_compilation"
        return problems.sorted()
    }

    /**
     * Missing exact source bytes is deliberately noncertifying. The trusted caller supplies bytes
     * from its immutable snapshot, not a later live checkout. All nested collections are detached
     * and made unmodifiable before validation; a caller cannot mutate a validated fact in place.
     */
    fun assemble(request: SemanticFactsRequest, shards: List<SemanticFactShard>, compilerExitCode: Int?,
                 executedSuccessfully: Boolean = true, executionReasons: List<String> = emptyList(),
                 sourceBytes: ((SemanticSource) -> ByteArray)? = null): SemanticFactBundle {
        val frozenRequest = SemanticFactsFreeze.request(request)
        val frozenShards = try {
            SemanticFactsFreeze.shards(shards, (frozenRequest.sources.size.toLong() * 4).coerceAtMost(200_000).toInt(),
                directIntLiteralArgumentsRequested = frozenRequest.directIntLiteralArgumentVersion != null)
        } catch (e: SemanticFactException) {
            return SemanticFactBundle(frozenRequest.digest(), frozenRequest.snapshotId, frozenRequest.analysisId,
                frozenRequest.target, frozenRequest.compilationId, "failed", compilerExitCode,
                SemanticFactsFreeze.list(listOf(e.code)), emptyList(), false)
        }
        return assembleFrozen(SemanticRequestData.of(frozenRequest), frozenShards, compilerExitCode, executedSuccessfully,
            SemanticFactsFreeze.list(executionReasons), sourceBytes)
    }

    /** Separate nominal observed route. Execution booleans remain DATA, never an owner receipt. */
    fun assembleObserved(request: ObservedSemanticFactsRequest, shards: List<SemanticFactShard>, compilerExitCode: Int?,
                         executedSuccessfully: Boolean, executionReasons: List<String> = emptyList(),
                         sourceBytes: ((SemanticSource) -> ByteArray)? = null): ObservedSemanticFactBundle {
        val frozenRequest = SemanticFactsFreeze.observedRequest(request)
        val frozenShards = try {
            SemanticFactsFreeze.shards(shards, (frozenRequest.sources.size.toLong() * 4).coerceAtMost(4096).toInt(),
                directIntLiteralArgumentsRequested = frozenRequest.directIntLiteralArgumentVersion != null)
        } catch (e: SemanticFactException) {
            return ObservedSemanticFactBundle(frozenRequest, SemanticFactBundle(frozenRequest.digest(),
                frozenRequest.snapshotId, frozenRequest.analysisId, frozenRequest.target, frozenRequest.compilationId,
                "failed", compilerExitCode, SemanticFactsFreeze.list(listOf(e.code)), emptyList(), false))
        }
        val facts = assembleFrozen(SemanticRequestData.of(frozenRequest), frozenShards, compilerExitCode,
            executedSuccessfully, SemanticFactsFreeze.list(executionReasons), sourceBytes)
        return ObservedSemanticFactBundle(frozenRequest, facts)
    }

    private fun assembleFrozen(request: SemanticRequestData, shards: List<SemanticFactShard>, compilerExitCode: Int?,
                               executedSuccessfully: Boolean, executionReasons: List<String>,
                               sourceBytes: ((SemanticSource) -> ByteArray)?): SemanticFactBundle {
        val requestProblems = extractionRequestProblems(request)
        val reasons = requestProblems.toMutableList()
        reasons += executionReasons
        if (!executedSuccessfully) reasons += "semantic_execution_unavailable"
        if (compilerExitCode == null) reasons += "semantic_compiler_completion_missing"
        else if (compilerExitCode != 0) reasons += "semantic_compilation_failed"
        val digest = request.digest()
        val byPath = request.sources.associateBy { it.path }
        var invalid = "semantic_direct_int_literal_request_version_unsupported" in requestProblems
        val allSpans = mutableListOf<SemanticSpan>()
        shards.forEach { shard ->
            if (shard.schemaVersion != 1 || shard.adapterId != request.adapterId || shard.requestDigest != digest ||
                shard.snapshotId != request.snapshotId || shard.analysisId != request.analysisId ||
                shard.toolchainId != request.toolchainId || shard.pluginDigest != request.pluginArtifact.digest ||
                shard.observedCompilerVersion != request.compilerVersion || byPath[shard.input.path] != shard.input) {
                invalid = true
                reasons += "semantic_evidence_binding_invalid"
            }
            val binaryOriginProblems = JvmBinaryOriginValidation.problems(request.observeJvmBinaryOrigins,
                shard.jvmBinaryOrigins, shard.symbols)
            if (binaryOriginProblems.isNotEmpty()) {
                invalid = true
                reasons += binaryOriginProblems
            }
            val literalProblems = directIntLiteralProblems(request, shard)
            if (literalProblems.isNotEmpty()) {
                invalid = true
                reasons += literalProblems
            }
            // Origin survey unknowns do not redefine the original semantic vocabulary's
            // completeness. Every optional survey row retains its own explicit unknown reason.
            // Neither do literal UNKNOWN/UNSUPPORTED or absent outcomes: they are never evidence
            // of complete expression coverage, a clean bound, or a fixed descriptor proof.
            if (shard.unknown.isNotEmpty()) reasons += "semantic_facts_unknown"
            val spans = shard.symbols.mapNotNull { it.source } + shard.calls.map { it.source } +
                shard.calls.flatMap { call -> call.arguments.mapNotNull { it.source } } +
                shard.types.map { it.source } + shard.unknown.mapNotNull { it.source }
            allSpans += spans
            if (spans.any { span ->
                    val source = byPath[span.path]
                    source == null || source.digest != span.digest || source.sourceSet != span.sourceSet ||
                        span.startByte < 0 || span.endByte < span.startByte || span.endByte > source.size ||
                        span.startLine < 0 || span.startColumn < 0 || span.endLine < span.startLine || span.endColumn < 0 ||
                        (span.endLine == span.startLine && span.endColumn < span.startColumn)
                }) {
                invalid = true
                reasons += "semantic_source_evidence_invalid"
            }
            val allTypes = shard.types.map { it.type } + shard.calls.flatMap {
                listOfNotNull(it.resultType, it.dispatchReceiverType, it.extensionReceiverType)
            } + shard.symbols.flatMap { symbol ->
                listOfNotNull(symbol.returnType, symbol.receiverType, symbol.dispatchReceiverType, symbol.aliasExpansion) +
                    symbol.parameters.map { it.type } + symbol.contextParameters.map { it.type } + symbol.supertypes
            }
            if (allTypes.any { !it.known }) reasons += "semantic_facts_unknown"
            val symbols = shard.symbols.associateBy { it.id }
            if (shard.symbols.any { symbol ->
                    !Digests.valid(symbol.id) || symbol.id != resolvedSymbolIdentity(symbol) ||
                        symbol.kind !in SYMBOL_KINDS || symbol.qualifiedName.isBlank() || symbol.compilerModule.isBlank() ||
                        symbol.origin.isBlank() || symbol.typeParameterCount !in 0..256 ||
                        symbol.parameters.map { it.index } != symbol.parameters.indices.toList() ||
                        symbol.contextParameters.map { it.index } != symbol.contextParameters.indices.toList() ||
                        (symbol.parameters + symbol.contextParameters).any { it.name.isBlank() }
                }) {
                invalid = true
                reasons += "semantic_symbol_identity_invalid"
            }
            if (shard.symbols.any { it.kind == "unsupported_symbol" || it.qualifiedName == "<unknown>" || (it.local && it.source == null) }) {
                reasons += "semantic_facts_unknown"
            }
            if (symbols.size != shard.symbols.size || shard.declarations.any { it !in symbols } ||
                shard.calls.any { it.targetSymbolId !in symbols || (it.containingSymbolId != null && it.containingSymbolId !in symbols) } ||
                shard.expectActual.any { it.actualSymbolId !in symbols || it.expectSymbolId !in symbols }) {
                invalid = true
                reasons += "semantic_symbol_graph_invalid"
            }
            if (shard.calls.any { call ->
                    val selected = symbols[call.targetSymbolId]
                    call.kind !in CALL_KINDS || call.arguments.any { argument ->
                        selected?.parameters?.getOrNull(argument.parameterIndex)?.name != argument.parameterName
                    } || call.arguments.groupBy { it.parameterIndex }.any { (index, arguments) ->
                        arguments.size > 1 && selected?.parameters?.getOrNull(index)?.vararg != true
                    }
                }) {
                invalid = true
                reasons += "semantic_argument_binding_invalid"
            }
        }
        // Group spans by path, then keep only one bounded source coordinate map alive at a time.
        // No arbitrary callback is invoked for a malformed request (including unsafe input paths).
        if (sourceBytes == null || requestProblems.isNotEmpty()) reasons += "semantic_source_bytes_unverified"
        else {
            val spansByPath = allSpans.groupBy { it.path }
            request.sources.forEach { source ->
                try {
                    val coordinates = SourceCoordinates(source, sourceBytes(source))
                    if (spansByPath[source.path].orEmpty().any { span ->
                            coordinates.span(span.startLine, span.startColumn, span.endLine, span.endColumn, span.synthetic) != span
                        }) {
                        invalid = true
                        reasons += "semantic_source_coordinate_mismatch"
                    }
                } catch (e: SemanticFactException) {
                    invalid = true
                    reasons += e.code
                } catch (_: Exception) {
                    reasons += "semantic_source_bytes_unavailable"
                }
            }
        }
        val seen = shards.map { it.input.path }.toSet()
        if (request.sources.any { it.path !in seen }) reasons += "semantic_sources_not_visited"
        if (shards.groupBy { it.input.path to it.compilerContext.moduleName }.any { it.value.size != 1 }) {
            invalid = true
            reasons += "semantic_duplicate_source_evidence"
        }
        reasons += observedGraphProblems(request, shards)
        val complete = reasons.isEmpty()
        return SemanticFactBundle(digest, request.snapshotId, request.analysisId, request.target, request.compilationId,
            when { invalid -> "failed"; complete -> "extracted"; !executedSuccessfully -> "unavailable"; else -> "incomplete" },
            compilerExitCode, SemanticFactsFreeze.list(reasons.distinct().sorted()),
            if (invalid) emptyList() else SemanticFactsFreeze.list(shards.sortedBy { it.input.path }), complete)
    }

    /** Shape/binding validation only. A well-formed DTO never authenticates its FIR producer. */
    private fun directIntLiteralProblems(request: SemanticRequestData, shard: SemanticFactShard): List<String> {
        val problems = mutableSetOf<String>()
        shard.calls.forEach { call ->
            call.arguments.forEach argumentLoop@ { argument ->
                val literal = argument.directIntLiteral ?: return@argumentLoop
                if (request.directIntLiteralArgumentVersion != DIRECT_INT_LITERAL_ARGUMENT_VERSION) {
                    problems += if (request.directIntLiteralArgumentVersion == null)
                        "semantic_direct_int_literal_not_requested" else "semantic_direct_int_literal_request_version_unsupported"
                }
                if (literal.version != DIRECT_INT_LITERAL_ARGUMENT_VERSION) {
                    problems += "semantic_direct_int_literal_observation_version_unsupported"
                }
                val validOutcome = when (literal.state) {
                    DirectIntLiteralArgumentState.OBSERVED -> literal.value != null && literal.reason == null
                    DirectIntLiteralArgumentState.UNSUPPORTED -> literal.value == null && literal.reason in setOf(
                        DirectIntLiteralArgumentReason.NOT_DIRECT_FIR_LITERAL,
                        DirectIntLiteralArgumentReason.LITERAL_KIND_NOT_INT,
                        DirectIntLiteralArgumentReason.VALUE_NOT_INT,
                        DirectIntLiteralArgumentReason.TYPE_NOT_EXACT_INT)
                    DirectIntLiteralArgumentState.UNKNOWN -> literal.value == null && literal.reason in setOf(
                        DirectIntLiteralArgumentReason.SOURCE_UNAVAILABLE,
                        DirectIntLiteralArgumentReason.SOURCE_SYNTHETIC,
                        DirectIntLiteralArgumentReason.TYPE_UNAVAILABLE)
                }
                if (!validOutcome) problems += "semantic_direct_int_literal_outcome_invalid"
                if (call.kind != "function_call" || call.source.path != shard.input.path ||
                    call.source.digest != shard.input.digest || call.source.sourceSet != shard.input.sourceSet) {
                    problems += "semantic_direct_int_literal_binding_invalid"
                }
                val source = argument.source
                val sourceValid = when (literal.reason) {
                    DirectIntLiteralArgumentReason.SOURCE_UNAVAILABLE -> source == null
                    DirectIntLiteralArgumentReason.SOURCE_SYNTHETIC -> source != null && (source.synthetic || call.source.synthetic)
                    else -> source != null && !source.synthetic && !call.source.synthetic &&
                        source.path == call.source.path && source.digest == call.source.digest &&
                        source.sourceSet == call.source.sourceSet && source.startByte < source.endByte &&
                        source.startByte >= call.source.startByte && source.endByte <= call.source.endByte
                }
                if (!sourceValid) problems += "semantic_direct_int_literal_source_invalid"
            }
        }
        return problems.sorted()
    }

    /** Compare actual FIR module refinement to the supplied graph, without name heuristics. */
    private fun observedGraphProblems(request: SemanticRequestData, shards: List<SemanticFactShard>): List<String> {
        val modules = shards.groupBy { it.input.sourceSet }.mapValues { (_, rows) -> rows.map { it.compilerContext.moduleName }.toSet() }
        if (modules.values.any { it.size != 1 }) return listOf("semantic_source_set_context_ambiguous")
        val moduleToSet = modules.map { (set, names) -> names.single() to set }.toMap()
        if (moduleToSet.size != modules.size) return listOf("semantic_source_set_context_ambiguous")
        val expected = request.sourceSets.associate { it.id to it.dependsOn.toSet() }
        val observed = shards.groupBy { it.input.sourceSet }.mapValues { (_, rows) ->
            rows.flatMap { it.compilerContext.dependsOnModules }.map { moduleToSet[it] ?: "unknown:$it" }.toSet()
        }
        if (observed.values.any { parents -> parents.any { it.startsWith("unknown:") } }) return listOf("semantic_refinement_module_unmapped")
        fun closure(graph: Map<String, Set<String>>, node: String): Set<String> {
            val seen = mutableSetOf<String>()
            fun add(id: String) { graph[id].orEmpty().forEach { if (seen.add(it)) add(it) } }
            add(node)
            return seen
        }
        return if (modules.keys.any { closure(expected, it) != closure(observed, it) }) listOf("semantic_refinement_graph_mismatch") else emptyList()
    }

    private fun safePath(value: String): Boolean = value.isNotBlank() && value.length <= 4096 &&
        !value.startsWith('/') && '\\' !in value && ':' !in value && value.none { it.isISOControl() } &&
        value.split('/').none { it.isEmpty() || it == "." || it == ".." }

    private val SYMBOL_KINDS = setOf("constructor", "getter", "setter", "value_parameter", "lambda", "function",
        "backing_field", "property", "type_alias", "class", "unsupported_symbol")
    private val CALL_KINDS = setOf("function_call", "property_access", "callable_reference")
}

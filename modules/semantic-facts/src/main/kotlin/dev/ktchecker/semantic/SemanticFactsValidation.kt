package dev.ktchecker.semantic

import dev.ktchecker.contracts.Digests
import java.nio.file.Path

/** Data-only checks; no project code, compiler, or plugin is loaded here. */
object SemanticFactsValidation {
    fun requestProblems(request: SemanticFactsRequest): List<String> {
        val problems = mutableListOf<String>()
        if (request.schemaVersion != 1 || request.adapterId != SEMANTIC_ADAPTER_ID ||
            request.compilerVersion != SEMANTIC_COMPILER_VERSION) problems += "semantic_tuple_unavailable"
        if (listOf(request.snapshotId, request.analysisId, request.toolchainId).any { !Digests.valid(it) }) {
            problems += "semantic_identity_invalid"
        }
        if (!Path.of(request.sourceRoot).isAbsolute) problems += "semantic_root_not_absolute"
        if (request.sources.isEmpty() || request.sources.size > 50_000) problems += "semantic_source_inventory_invalid"
        if (request.sources.map { it.path }.distinct().size != request.sources.size) problems += "semantic_duplicate_source"
        request.sources.forEach {
            if (!safePath(it.path) || !Digests.valid(it.digest) || it.size !in 0..4_194_304) problems += "semantic_source_invalid"
        }
        if (request.sources.sumOf { it.size.coerceIn(0, 4_194_305) } > 268_435_456) problems += "semantic_input_limit"
        if (request.compilerArtifacts.isEmpty() || request.compilerArtifacts.any { !Digests.valid(it.digest) } ||
            !Digests.valid(request.pluginArtifact.digest)) problems += "semantic_artifact_identity_invalid"
        if (request.target.isBlank() || request.compilationId.isBlank()) problems += "semantic_context_missing"
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

    fun assemble(request: SemanticFactsRequest, shards: List<SemanticFactShard>, compilerExitCode: Int?,
                 executedSuccessfully: Boolean = true, executionReasons: List<String> = emptyList()): SemanticFactBundle {
        val reasons = requestProblems(request).toMutableList()
        reasons += executionReasons
        if (!executedSuccessfully) reasons += "semantic_execution_unavailable"
        if (compilerExitCode == null) reasons += "semantic_compiler_completion_missing"
        else if (compilerExitCode != 0) reasons += "semantic_compilation_failed"
        val digest = request.digest()
        val byPath = request.sources.associateBy { it.path }
        var invalid = false
        shards.forEach { shard ->
            if (shard.schemaVersion != 1 || shard.adapterId != request.adapterId || shard.requestDigest != digest ||
                shard.snapshotId != request.snapshotId || shard.analysisId != request.analysisId ||
                shard.toolchainId != request.toolchainId || shard.pluginDigest != request.pluginArtifact.digest ||
                shard.observedCompilerVersion != request.compilerVersion || byPath[shard.input.path] != shard.input) {
                invalid = true
                reasons += "semantic_evidence_binding_invalid"
            }
            if (shard.unknown.isNotEmpty()) reasons += "semantic_facts_unknown"
            val spans = shard.symbols.mapNotNull { it.source } + shard.calls.map { it.source } +
                shard.calls.flatMap { call -> call.arguments.mapNotNull { it.source } } +
                shard.types.map { it.source } + shard.unknown.mapNotNull { it.source }
            if (spans.any { span ->
                    val source = byPath[span.path]
                    source == null || source.digest != span.digest || source.sourceSet != span.sourceSet ||
                        span.startByte < 0 || span.endByte < span.startByte || span.endByte > source.size ||
                        span.startLine < 0 || span.startColumn < 0 || span.endLine < span.startLine || span.endColumn < 0
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
            if (symbols.size != shard.symbols.size || shard.declarations.any { it !in symbols } ||
                shard.calls.any { it.targetSymbolId !in symbols || (it.containingSymbolId != null && it.containingSymbolId !in symbols) } ||
                shard.expectActual.any { it.actualSymbolId !in symbols || it.expectSymbolId !in symbols }) {
                invalid = true
                reasons += "semantic_symbol_graph_invalid"
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
            compilerExitCode, reasons.distinct().sorted(), if (invalid) emptyList() else shards.sortedBy { it.input.path }, complete)
    }

    /** Compare actual FIR module refinement to the supplied graph, without name heuristics. */
    private fun observedGraphProblems(request: SemanticFactsRequest, shards: List<SemanticFactShard>): List<String> {
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
}

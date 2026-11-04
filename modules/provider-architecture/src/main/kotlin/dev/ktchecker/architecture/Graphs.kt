package dev.ktchecker.architecture

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.CancellationToken
import dev.ktchecker.contracts.Digests
import dev.ktchecker.projectmodel.VerifiedProjectModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
internal data class EdgeOrigin(val from: String, val to: String, val compilationId: String?, val variant: String?)

@Serializable
internal data class ArchitectureGraphEdge(val from: String, val to: String, val origins: List<EdgeOrigin>)

internal data class ResolvedArchitectureGraph(
    val nodes: List<String>,
    val edges: List<ArchitectureGraphEdge>,
    val certificateId: String,
)

internal class ArchitectureGraphException(val code: String) : IllegalArgumentException(code)

/**
 * Owner-node quotient graph derived only from actual Gradle/refinement edges, never imports/names.
 * Projection precedes closure: adjacent quotient hops need not share one actual identity.
 */
internal fun graph(model: VerifiedProjectModel, scope: ArchitectureScope,
                   cancellation: CancellationToken = CancellationToken.NONE): ResolvedArchitectureGraph {
    cancellation.throwIfCancelled()
    if (scopeTextUpperBound(scope) > MAX_ARCHITECTURE_TEXT_BYTES) throw ArchitectureGraphException("architecture_graph_text_bound_exceeded")
    val issues = model.graphIssues(scope.compilationIds)
    if (issues.isNotEmpty()) throw ArchitectureGraphException("architecture_model_graph_incomplete:" + issues.map { it.kind }.distinct().sorted().joinToString(","))
    if (scope.graphKind == ArchitectureGraphKind.RESOLVED_SYMBOL_DEPENDENCY) throw ArchitectureGraphException("architecture_complete_resolved_symbol_graph_unavailable")
    val selected = scope.compilationIds.map { requireNotNull(model.compilation(it)) }
    val knownActuals = when (scope.graphKind) {
        ArchitectureGraphKind.GRADLE_MODULE_DEPENDENCY -> model.export.modules.map { it.id }.toSet()
        ArchitectureGraphKind.SOURCE_SET_REFINEMENT -> model.export.sourceSets.map { it.id }.toSet()
        ArchitectureGraphKind.RESOLVED_SYMBOL_DEPENDENCY -> emptySet()
    }
    if (scope.nodeMapping.keys.any { it !in knownActuals }) throw ArchitectureGraphException("architecture_node_mapping_unknown_actual_identity")
    val actualNodes = mutableSetOf<String>()
    val origins = mutableListOf<EdgeOrigin>()
    val selectedIds = scope.compilationIds.toSet()
    var actualText = 2L
    fun addOrigin(origin: EdgeOrigin) {
        cancellation.throwIfCancelled()
        if (origins.size >= MAX_GRAPH_EDGES) throw ArchitectureGraphException("architecture_actual_graph_bound_exceeded")
        actualText += 128 + quotedJsonUpperBound(scope.nodeMapping[origin.from]) + quotedJsonUpperBound(scope.nodeMapping[origin.to]) + originTextUpperBound(origin)
        if (actualText > MAX_ARCHITECTURE_TEXT_BYTES) throw ArchitectureGraphException("architecture_graph_text_bound_exceeded")
        origins += origin
    }
    when (scope.graphKind) {
        ArchitectureGraphKind.GRADLE_MODULE_DEPENDENCY -> {
            selected.forEach { compilation ->
                cancellation.throwIfCancelled()
                actualNodes += compilation.moduleId
                compilation.projectDependencies.forEach { dependency ->
                    val producer = dependency.compilationId
                        ?: throw ArchitectureGraphException("architecture_project_variant_mapping_unavailable")
                    // Transitive paths/SCCs need the selected producer's outgoing graph too.
                    // An arbitrary truncated node list cannot certify that no cycle exists.
                    if (producer !in selectedIds) throw ArchitectureGraphException("architecture_compilation_graph_scope_not_closed")
                    actualNodes += dependency.moduleId
                    addOrigin(EdgeOrigin(compilation.moduleId, dependency.moduleId, compilation.id, dependency.selectedVariant))
                }
            }
        }
        ArchitectureGraphKind.SOURCE_SET_REFINEMENT -> {
            actualNodes += selected.flatMap { it.sourceSetIds }
            actualNodes.sorted().forEach { id -> model.sourceSet(id)!!.dependsOn.forEach { parent ->
                if (parent !in actualNodes) throw ArchitectureGraphException("architecture_refinement_graph_scope_not_closed")
                addOrigin(EdgeOrigin(id, parent, null, null))
            } }
        }
        ArchitectureGraphKind.RESOLVED_SYMBOL_DEPENDENCY -> error("unsupported semantic graph")
    }
    if (actualNodes.any { it !in scope.nodeMapping }) throw ArchitectureGraphException("architecture_node_membership_unavailable")
    val projected = origins.groupBy {
        cancellation.throwIfCancelled()
        PolicyEdge(scope.nodeMapping.getValue(it.from), scope.nodeMapping.getValue(it.to))
    }
        .filterKeys { scope.includeMappedSelfEdges || it.from != it.to }
        .map { (edge, witnesses) -> ArchitectureGraphEdge(edge.from, edge.to,
            immutable(witnesses.distinct().sortedWith(compareBy<EdgeOrigin> { it.from }.thenBy { it.to }.thenBy { it.compilationId.orEmpty() }.thenBy { it.variant.orEmpty() }))) }
        .sortedWith(compareBy<ArchitectureGraphEdge> { it.from }.thenBy { it.to })
    val nodes = actualNodes.map { scope.nodeMapping.getValue(it) }.distinct().sorted()
    if (nodes.size > 4096 || projected.size > MAX_GRAPH_EDGES) throw ArchitectureGraphException("architecture_graph_bound_exceeded")
    val edges = (if (scope.traversal == ArchitectureEdgeTraversal.DIRECT) projected else transitive(nodes, projected, cancellation))
        .filter { scope.includeMappedSelfEdges || it.from != it.to }
    graphTextUpperBound(edges, cancellation = cancellation)
    val digest = Digests.sha256("ktchecker/architecture-graph/1\u0000" + model.ingressCertificate + "\u0000" +
        Digests.canonical(ContractJson.encodeToJsonElement(scope)) + "\u0000" + Digests.canonical(ContractJson.encodeToJsonElement(edges)))
    return ResolvedArchitectureGraph(immutable(nodes), immutable(edges), digest)
}

private const val MAX_GRAPH_EDGES = 50_000
private const val MAX_TRANSITIVE_WITNESS_ORIGINS = 200_000
internal const val MAX_ARCHITECTURE_TEXT_BYTES = 8L * 1024 * 1024

/** Six bytes per UTF-16 code unit safely covers UTF-8 and JSON escaping, without allocating it. */
internal fun quotedJsonUpperBound(value: String?): Long = if (value == null) 4 else 2 + 6L * value.length

private fun scopeTextUpperBound(scope: ArchitectureScope): Long = 512L +
    scope.compilationIds.sumOf { quotedJsonUpperBound(it) + 1 } +
    scope.nodeMapping.entries.sumOf { quotedJsonUpperBound(it.key) + quotedJsonUpperBound(it.value) + 2 }

private fun originTextUpperBound(origin: EdgeOrigin): Long = 128L + quotedJsonUpperBound(origin.from) + quotedJsonUpperBound(origin.to) +
    quotedJsonUpperBound(origin.compilationId) + quotedJsonUpperBound(origin.variant)

internal fun graphTextUpperBound(edges: List<ArchitectureGraphEdge>, limit: Long = MAX_ARCHITECTURE_TEXT_BYTES,
                                code: String = "architecture_graph_text_bound_exceeded",
                                cancellation: CancellationToken = CancellationToken.NONE): Long {
    var bytes = 2L
    edges.forEach { edge ->
        cancellation.throwIfCancelled()
        bytes += 128 + quotedJsonUpperBound(edge.from) + quotedJsonUpperBound(edge.to)
        if (bytes > limit) throw ArchitectureGraphException(code)
        edge.origins.forEach { origin ->
            cancellation.throwIfCancelled()
            bytes += originTextUpperBound(origin)
            if (bytes > limit) throw ArchitectureGraphException(code)
        }
    }
    return bytes
}

private fun transitive(nodes: List<String>, direct: List<ArchitectureGraphEdge>, cancellation: CancellationToken): List<ArchitectureGraphEdge> {
    val adjacency = direct.groupBy { it.from }
    val result = mutableListOf<ArchitectureGraphEdge>()
    var witnessOrigins = 0
    var witnessText = 2L
    nodes.forEach { start ->
        cancellation.throwIfCancelled()
        val previous = mutableMapOf<String, ArchitectureGraphEdge>()
        val queue = ArrayDeque<String>()
        adjacency[start].orEmpty().forEach { edge ->
            cancellation.throwIfCancelled()
            if (previous.putIfAbsent(edge.to, edge) == null) queue.addLast(edge.to)
        }
        while (queue.isNotEmpty()) {
            cancellation.throwIfCancelled()
            val current = queue.removeFirst()
            adjacency[current].orEmpty().forEach { edge ->
                cancellation.throwIfCancelled()
                if (previous.putIfAbsent(edge.to, edge) == null) queue.addLast(edge.to)
            }
        }
        previous.keys.sorted().forEach { target ->
            cancellation.throwIfCancelled()
            var current = target
            val path = ArrayDeque<ArchitectureGraphEdge>()
            do {
                cancellation.throwIfCancelled()
                if (++witnessOrigins > MAX_TRANSITIVE_WITNESS_ORIGINS) throw ArchitectureGraphException("architecture_transitive_witness_bound_exceeded")
                val edge = previous.getValue(current)
                path.addFirst(edge)
                current = edge.from
            } while (current != start)
            val witnessed = ArchitectureGraphEdge(start, target, immutable(path.flatMap { it.origins.take(1) }))
            witnessText += graphTextUpperBound(listOf(witnessed), cancellation = cancellation)
            if (witnessText > MAX_ARCHITECTURE_TEXT_BYTES) throw ArchitectureGraphException("architecture_graph_text_bound_exceeded")
            result += witnessed
            if (result.size > MAX_GRAPH_EDGES) throw ArchitectureGraphException("architecture_transitive_graph_bound_exceeded")
        }
    }
    return result
}

/** Iterative Kosaraju: O(V+E), one SCC report, no recursive/exponential cycle enumeration. */
internal fun stronglyConnected(graph: ResolvedArchitectureGraph,
                               cancellation: CancellationToken = CancellationToken.NONE): List<List<String>> {
    cancellation.throwIfCancelled()
    val forward = graph.edges.groupBy { it.from }.mapValues { entry -> entry.value.map { it.to }.distinct().sorted() }
    val reverse = graph.edges.groupBy { it.to }.mapValues { entry -> entry.value.map { it.from }.distinct().sorted() }
    val visited = mutableSetOf<String>()
    val finish = mutableListOf<String>()
    graph.nodes.forEach { start ->
        cancellation.throwIfCancelled()
        if (!visited.add(start)) return@forEach
        val stack = ArrayDeque<Pair<String, Iterator<String>>>()
        stack.addLast(start to forward[start].orEmpty().iterator())
        while (stack.isNotEmpty()) {
            cancellation.throwIfCancelled()
            val (node, children) = stack.last()
            if (children.hasNext()) {
                val child = children.next()
                if (visited.add(child)) stack.addLast(child to forward[child].orEmpty().iterator())
            } else { stack.removeLast(); finish += node }
        }
    }
    visited.clear()
    val result = mutableListOf<List<String>>()
    finish.asReversed().forEach { start ->
        cancellation.throwIfCancelled()
        if (!visited.add(start)) return@forEach
        val component = mutableListOf<String>()
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            cancellation.throwIfCancelled()
            val node = queue.removeFirst(); component += node
            reverse[node].orEmpty().forEach { cancellation.throwIfCancelled(); if (visited.add(it)) queue.addLast(it) }
        }
        result += component.sorted()
    }
    return result.sortedBy { it.first() }
}

/** One deterministic owner-node cycle witness per SCC; not necessarily an actual-module cycle. */
internal fun cycleWitness(graph: ResolvedArchitectureGraph, component: List<String>,
                          cancellation: CancellationToken = CancellationToken.NONE): List<ArchitectureGraphEdge> {
    cancellation.throwIfCancelled()
    val nodes = component.toSet()
    val edges = graph.edges.filter { it.from in nodes && it.to in nodes }
    if (component.size == 1) return immutable(edges.filter { it.from == it.to }.take(1))
    val first = edges.first { it.from != it.to }
    val adjacency = edges.groupBy { it.from }
    val previous = mutableMapOf<String, ArchitectureGraphEdge>()
    val queue = ArrayDeque(listOf(first.to))
    val visited = mutableSetOf(first.to)
    while (queue.isNotEmpty() && first.from !in previous) {
        cancellation.throwIfCancelled()
        adjacency[queue.removeFirst()].orEmpty().forEach { edge ->
            cancellation.throwIfCancelled()
            if (visited.add(edge.to)) { previous[edge.to] = edge; queue.addLast(edge.to) }
        }
    }
    val path = ArrayDeque<ArchitectureGraphEdge>()
    var current = first.from
    while (current != first.to) {
        cancellation.throwIfCancelled()
        val edge = previous.getValue(current); path.addFirst(edge); current = edge.from
    }
    path.addFirst(first)
    return immutable(path)
}

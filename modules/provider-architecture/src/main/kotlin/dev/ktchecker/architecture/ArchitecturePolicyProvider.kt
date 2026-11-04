package dev.ktchecker.architecture

import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.VerifiedProjectModel
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement
import java.util.concurrent.CancellationException

/**
 * Explicit owner graph policies only. The current implementation supports a bounded actual
 * owner-node quotient graph of Gradle-module dependencies or source-set refinement. Projection
 * precedes traversal/SCCs; a quotient path/cycle need not be an actual-module path/cycle. It is not an import ban,
 * complete symbol graph, ABI exporter or declaration-ownership resolver. No tuple is qualified.
 */
class ArchitecturePolicyProvider(
    private val model: VerifiedProjectModel? = null,
    private val ownerPolicies: VerifiedArchitecturePolicies? = null,
) : AnalysisProvider {
    override val id: String = ARCHITECTURE_PROVIDER_ID
    override val version: String = ARCHITECTURE_PROVIDER_VERSION
    override val supportedRuleIds: Set<String> = setOf(ALLOWED_DEPENDENCY_EDGE, DEPENDENCY_CYCLE_POLICY)
    val mappedRuleIds: Set<String> = supportedRuleIds + setOf(EXPORTED_API_DEPENDENCY_POLICY, SOURCE_SET_OWNERSHIP_POLICY)

    override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
        val started = System.nanoTime()
        val request = ContractJson.decodeFromString<AnalysisInput>(ContractJson.encodeToString(input))
        val requested = request.requiredRuleIds.distinct()
        fun unavailable(reason: String, state: String = "unavailable") = ProviderResult(id,
            if (state == "cancelled") "cancelled" else "partial",
            coverage = immutable(requested.map { Coverage(it, request.context.scope, state, reason, depth = "graph") }),
            reasons = immutable(listOf(reason)), providerVersion = version)
        if (requested.isEmpty()) return unavailable("architecture_no_explicit_rules_requested")
        if (request.requiredRuleIds.size != requested.size) return unavailable("architecture_rule_selection_duplicate")
        if (cancellation.isCancelled()) return unavailable("architecture_cancelled", "cancelled")
        val actualModel = model ?: return unavailable("architecture_verified_project_model_required")
        val policies = ownerPolicies ?: return unavailable("architecture_owner_policy_not_authorized")
        if (policies.request.namespace != actualModel.namespace || policies.request.snapshotId != actualModel.snapshot.snapshotId ||
            policies.request.modelCertificateId != actualModel.ingressCertificate ||
            policies.request.configurationDigest != actualModel.identity.configurationDigest) return unavailable("architecture_policy_model_binding_mismatch")
        if (request.snapshot != actualModel.snapshot || request.context.configurationDigest != actualModel.identity.configurationDigest) {
            return unavailable("architecture_snapshot_or_configuration_binding_mismatch")
        }
        val expected = actualModel.context(request.context.scope, actualModel.identity.configurationDigest).context
            ?: return unavailable("architecture_actual_compilation_context_required", "unsupported")
        if (request.context != expected) return unavailable("architecture_context_model_binding_mismatch")
        val compilation = actualModel.export.compilations.single { value -> value.moduleId == expected.scope.module &&
            value.target == expected.scope.target && value.name == expected.scope.compilation &&
            actualModel.sourceSet(value.defaultSourceSetId)?.name == expected.scope.sourceSet }
        val policyDigest = policies.bundle.digest()
        val capturedLocations = actualModel.snapshot.files.associate { it.path to SourceLocation(it.path, it.digest) }
        val moduleAnchors = actualModel.export.modules.associate { module -> module.id to module.buildFile?.let { capturedLocations[it] } }
        val sourceSetOwners = actualModel.export.sourceSets.associate { it.id to it.moduleId }
        val findings = mutableListOf<Finding>()
        val coverage = mutableListOf<Coverage>()
        val reasons = mutableListOf<String>()
        requested.forEach { ruleId ->
            if (cancellation.isCancelled()) return unavailable("architecture_cancelled", "cancelled")
            val policy = policies.byRuleId[ruleId]
            val unsupported = when {
                ruleId !in mappedRuleIds -> "architecture_rule_mapping_unavailable"
                policy == null -> "architecture_rule_not_declared_by_owner"
                ruleId == EXPORTED_API_DEPENDENCY_POLICY -> "architecture_effective_api_export_visibility_and_complete_dependency_evidence_unavailable"
                ruleId == SOURCE_SET_OWNERSHIP_POLICY -> "architecture_resolved_declaration_and_binary_symbol_ownership_evidence_unavailable"
                policy.scope.graphKind == ArchitectureGraphKind.RESOLVED_SYMBOL_DEPENDENCY -> "architecture_complete_resolved_symbol_graph_unavailable"
                compilation.id !in policy.scope.compilationIds -> "architecture_context_outside_explicit_owner_policy_scope"
                else -> null
            }
            if (unsupported != null) {
                reasons += "$unsupported:$ruleId"
                coverage += Coverage(ruleId, expected.scope, if (policy == null) "unavailable" else "unsupported", unsupported, depth = "graph")
                return@forEach
            }
            try {
                val graph = graph(actualModel, requireNotNull(policy).scope, cancellation)
                fun anchor(actualId: String): SourceLocation {
                    val moduleId = if (policy.scope.graphKind == ArchitectureGraphKind.SOURCE_SET_REFINEMENT) sourceSetOwners[actualId] else actualId
                    return moduleAnchors[moduleId] ?: throw ArchitectureGraphException("architecture_captured_build_anchor_unavailable")
                }
                var reportBytes = 0L
                fun report(witness: List<ArchitectureGraphEdge>, diagnosis: String): Finding {
                    cancellation.throwIfCancelled()
                    val finding = violation(request, actualModel, policies, policyDigest, ruleId, graph, witness, diagnosis, ::anchor,
                        MAX_RULE_REPORT_BYTES - reportBytes, cancellation)
                    reportBytes += ContractJson.encodeToString(finding).toByteArray(Charsets.UTF_8).size
                    if (reportBytes > MAX_RULE_REPORT_BYTES) throw ArchitectureGraphException("architecture_finding_report_bound_exceeded")
                    return finding
                }
                val violations = when (policy) {
                    is DeclaredArchitecturePolicy.AllowedEdges -> {
                        val forbidden = policy.forbiddenEdges.toSet()
                        val allowed = policy.allowedEdges.toSet()
                        val forbiddenActual = graph.edges.filter { edge ->
                            cancellation.throwIfCancelled()
                            val decision = PolicyEdge(edge.from, edge.to)
                            decision in forbidden || decision !in allowed && policy.defaultPolicy == DefaultEdgePolicy.FORBID
                        }
                        if (forbiddenActual.size > MAX_RULE_FINDINGS) throw ArchitectureGraphException("architecture_finding_count_bound_exceeded")
                        forbiddenActual.map { edge ->
                            cancellation.throwIfCancelled()
                            report(listOf(edge),
                                "Owner policy forbids ${edge.from} -> ${edge.to} in the owner-node quotient graph ${policy.scope.graphKind}/${policy.scope.traversal}.")
                        }
                    }
                    is DeclaredArchitecturePolicy.AcyclicGraph -> {
                        val allowed = policy.allowedComponents.map { it.toSet() }.toSet()
                        val selfEdges = graph.edges.filter { it.from == it.to }.map { it.from }.toSet()
                        val forbiddenActual = stronglyConnected(graph, cancellation).filter { component ->
                            cancellation.throwIfCancelled()
                            (component.size > 1 || component.single() in selfEdges) && component.toSet() !in allowed
                        }
                        if (forbiddenActual.size > MAX_RULE_FINDINGS) throw ArchitectureGraphException("architecture_finding_count_bound_exceeded")
                        forbiddenActual.map { component ->
                            cancellation.throwIfCancelled()
                            if (component.sumOf { quotedJsonUpperBound(it) + 12 } > MAX_WITNESS_TEXT_BYTES) {
                                throw ArchitectureGraphException("architecture_witness_report_bound_exceeded")
                            }
                            report(cycleWitness(graph, component, cancellation),
                                "Owner-selected quotient graph contains SCC ${component.joinToString(", ")}; one owner-node cycle witness is reported, not necessarily an actual-module cycle.")
                        }
                    }
                    else -> error("Unsupported semantic policy cannot reach graph analysis")
                }
                findings += violations
                val reason = "architecture_exact_graph_executed_but_runtime_tuple_unqualified"
                reasons += "$reason:$ruleId"
                // Evidence hashes are not release/runtime qualification. Do not return a successful
                // negative coverage certificate just because this in-process algorithm ran.
                coverage += Coverage(ruleId, expected.scope, if (violations.isNotEmpty()) "findings" else "unqualified", reason,
                    depth = "graph", provenance = "executed", implemented = true, tested = false, qualified = false)
            } catch (_: CancellationException) {
                return unavailable("architecture_cancelled", "cancelled")
            } catch (error: ArchitectureGraphException) {
                reasons += "${error.code}:$ruleId"
                coverage += Coverage(ruleId, expected.scope, "unsupported", error.code, depth = "graph")
            }
        }
        if (cancellation.isCancelled()) return unavailable("architecture_cancelled", "cancelled")
        return ProviderResult(id, "partial", immutable(findings), immutable(coverage), immutable(reasons.distinct()),
            metrics = AnalysisMetrics(elapsedMillis = (System.nanoTime() - started) / 1_000_000), providerVersion = version)
    }

    private fun violation(input: AnalysisInput, model: VerifiedProjectModel, policies: VerifiedArchitecturePolicies, policyDigest: String,
                          ruleId: String, graph: ResolvedArchitectureGraph, witness: List<ArchitectureGraphEdge>, diagnosis: String,
                          anchor: (String) -> SourceLocation, remainingReportBytes: Long, cancellation: CancellationToken): Finding {
        val witnessBytes = graphTextUpperBound(witness, MAX_WITNESS_TEXT_BYTES, "architecture_witness_report_bound_exceeded", cancellation)
        val diagnosisBytes = quotedJsonUpperBound(diagnosis)
        if (diagnosisBytes > MAX_WITNESS_TEXT_BYTES) throw ArchitectureGraphException("architecture_witness_report_bound_exceeded")
        val origins = witness.flatMap { edge -> cancellation.throwIfCancelled(); edge.origins }.distinct()
        val first = origins.firstOrNull() ?: throw ArchitectureGraphException("architecture_edge_witness_unavailable")
        val location = anchor(first.from)
        val related = (origins.flatMap { cancellation.throwIfCancelled(); listOf(anchor(it.from), anchor(it.to)) }).distinct().filter { it != location }
        // Reserve an escape-safe upper bound BEFORE constructing/canonicalizing witness/evidence JSON.
        // Four witness budgets cover both rendered edge summaries and their fixed per-hop labels;
        // locations/context are accounted separately because their paths can dwarf short edge IDs.
        fun locationBytes(value: SourceLocation): Long = 256 + quotedJsonUpperBound(value.path) +
            quotedJsonUpperBound(value.fileDigest) + quotedJsonUpperBound(value.excerpt)
        val estimatedFindingBytes = 16_384L + 4 * witnessBytes + diagnosisBytes + locationBytes(location) +
            related.sumOf(::locationBytes) + listOf(input.context.scope.module, input.context.scope.sourceSet,
                input.context.scope.target, input.context.scope.compilation).sumOf { quotedJsonUpperBound(it) }
        if (estimatedFindingBytes > remainingReportBytes) throw ArchitectureGraphException("architecture_finding_report_bound_exceeded")
        val encodedWitness = Digests.canonical(ContractJson.encodeToJsonElement(witness))
        if (encodedWitness.toByteArray(Charsets.UTF_8).size > MAX_RULE_REPORT_BYTES) throw ArchitectureGraphException("architecture_witness_report_bound_exceeded")
        val witnessDigest = Digests.sha256(encodedWitness)
        val subject = witness.map { PolicyEdge(it.from, it.to) }
        val fingerprint = Digests.sha256(listOf(ruleId, input.context.scope.id, policyDigest,
            Digests.canonical(ContractJson.encodeToJsonElement(subject))).joinToString("\u0000"))
        return Finding(ruleId, if (ruleId == ALLOWED_DEPENDENCY_EDGE) "Declared dependency edge is forbidden" else "Declared acyclic graph contains a cycle",
            diagnosis, "This is an explicit owner-selected graph contract, not a universal layering convention.",
            location, fingerprint, id, input.context.scope, severity = "warning", kind = "policy", certainty = "high",
            evidence = immutable(listOf(
                Evidence("owner-policy", "Authenticated exact policy revision ${policies.bundle.revision}; explicit node mapping and graph scope.", digest = policyDigest),
                Evidence("verified-project-model", "Actual owner-authorized Gradle model for ${model.snapshot.snapshotId}.", digest = model.ingressCertificate),
                Evidence("resolved-graph", "Bounded owner-node quotient graph derived from actual edges; ${witness.joinToString { "${it.from} -> ${it.to}" }}.", digest = graph.certificateId),
                Evidence("edge-witness", origins.joinToString { "${it.from} -> ${it.to}, compilation=${it.compilationId ?: "refinement"}, variant=${it.variant ?: "dependsOn"}" }, digest = witnessDigest),
                Evidence("graph-precision", "Actual endpoints are mapped before traversal/SCCs. Consecutive owner-node witness hops may use different actual identities merged into the same node; no connected actual-module path/cycle is claimed."),
                Evidence("location-precision", "Build-configuration anchor at file start, not the inferred source position of this graph edge."),
            )), verification = immutable(listOf(Verification(expected = "Re-export this exact owner-selected model and policy graph; confirm this edge/cycle witness no longer violates the declared contract."))),
            semanticDigest = Digests.sha256(model.ingressCertificate + "\u0000" + policies.certificateId + "\u0000" + graph.certificateId),
            relatedLocations = immutable(related))
    }

    private companion object {
        const val MAX_RULE_FINDINGS = 1024
        const val MAX_RULE_REPORT_BYTES = 8L * 1024 * 1024
        const val MAX_WITNESS_TEXT_BYTES = 512L * 1024
    }
}

package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.ContractJson
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Observed-only graph sibling; never a VerifiedJvmProducedGraph. The exact original task/output
 * relation was observed, but configuration/input closure is permanently unproven. This grants
 * neither a compiler run nor fulfilled output/coverage/retirement/reuse evidence.
 */
class ObservedJvmProducedGraph internal constructor(
    val requestDigest: String,
    val modelDigest: String,
    val evidenceIdentity: String,
    val invocationIdentity: String,
    val producerCompilationId: String,
    val consumerCompilationId: String,
    val reference: ModelJvmProducedOutputReference,
    val digest: String,
) {
    val assuranceProfile: String get() = OBSERVED_EXPORT_PROFILE
    val compilationIds: List<String> = immutable(listOf(producerCompilationId, consumerCompilationId))
}

internal fun observedJvmProducedGraph(request: ModelImportRequest, graph: GradleProjectExport, modelDigest: String,
    evidenceIdentity: String, issues: List<ModelIssue>, layouts: Map<String, VerifiedJvmArgumentLayout>,
    origins: Map<String, ModelJvmProducedOutputOrigin>): ObservedJvmProducedGraph? {
    val shape = jvmProducedGraphShape(graph, layouts, origins) ?: return null
    // Typed observed branch only. Never modify ordinary verifiedJvmProducedGraph's allowlist or
    // strip these issues before passing an apparent COMPLETE model to an ordinary compiler.
    if (issues.distinct().toSet() != setOf(observedClosureIssue(), deferredJvmIssue(shape.consumer.id, shape.reference))) return null
    val identity = canonicalDigest(ContractJson.encodeToJsonElement(listOf(OBSERVED_EXPORT_PROFILE,
        JVM_PRODUCED_OUTPUT_PREVIEW, request.digest(), evidenceIdentity, request.executionId,
        CONFIGURATION_INPUT_CLOSURE_UNPROVEN, modelDigest, shape.producer.id, shape.consumer.id,
        shape.reference.digest(), layouts.getValue(shape.producer.id).digest, layouts.getValue(shape.consumer.id).digest)))
    return ObservedJvmProducedGraph(request.digest(), modelDigest, evidenceIdentity, request.executionId,
        shape.producer.id, shape.consumer.id, shape.reference, identity)
}

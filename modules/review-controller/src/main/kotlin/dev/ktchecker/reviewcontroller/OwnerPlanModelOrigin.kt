package dev.ktchecker.reviewcontroller

import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.*

/** Exact nominal model objects; a read-only facts projection never substitutes one assurance for another. */
internal sealed class OwnerPlanModelOrigin {
    class Verified(val value: VerifiedProjectModel) : OwnerPlanModelOrigin()
    class Observed(val value: ObservedProjectModel) : OwnerPlanModelOrigin()
    val verified: VerifiedProjectModel get() = when (this) {
        is Verified -> value
        is Observed -> problem("observed_model_not_verified", "An observed one-shot model is not a verified ingress capability.")
    }
    val observed: ObservedProjectModel? get() = when (this) { is Verified -> null; is Observed -> value }
    val namespace: ModelNamespace get() = when (this) { is Verified -> value.namespace; is Observed -> value.namespace }
    val snapshot: SnapshotManifest get() = when (this) { is Verified -> value.snapshot; is Observed -> value.snapshot }
    val export: GradleProjectExport get() = when (this) { is Verified -> value.export; is Observed -> value.export }
    val identity: ProjectModelIdentity get() = when (this) { is Verified -> value.identity; is Observed -> value.identity }
    val issues: List<ModelIssue> get() = when (this) { is Verified -> value.issues; is Observed -> value.issues }
    val compilationIds: List<String> get() = when (this) { is Verified -> value.compilationIds; is Observed -> value.compilationIds }
    val authorityEvidenceDigest: String get() = when (this) { is Verified -> value.ingressCertificate; is Observed -> value.evidenceIdentity }
    fun sameModel(other: OwnerPlanModelOrigin): Boolean = when (this) {
        is Verified -> other is Verified && value === other.value
        is Observed -> other is Observed && value === other.value
    }
    fun context(id: String): OwnerPlanContextOrigin = when (this) {
        is Verified -> OwnerPlanContextOrigin.Verified(value.context(id, value.identity.configurationDigest))
        is Observed -> OwnerPlanContextOrigin.Observed(value.context(id, value.identity.configurationDigest))
    }
    fun compilation(id: String): ModelCompilation? = when (this) { is Verified -> value.compilation(id); is Observed -> value.compilation(id) }
    fun sourceSet(id: String): ModelSourceSet? = when (this) { is Verified -> value.sourceSet(id); is Observed -> value.sourceSet(id) }
    fun jvmArgumentLayout(id: String): VerifiedJvmArgumentLayout? = when (this) {
        is Verified -> value.jvmArgumentLayout(id); is Observed -> value.jvmArgumentLayout(id)
    }
    fun graph(): OwnerProducedGraphFacts? = when (this) {
        is Verified -> value.jvmProducedGraph?.let { OwnerProducedGraphFacts(it.requestDigest, it.modelDigest,
            it.producerCompilationId, it.consumerCompilationId, it.reference, it.digest) }
        is Observed -> value.jvmProducedGraph?.let { OwnerProducedGraphFacts(it.requestDigest, it.modelDigest,
            it.producerCompilationId, it.consumerCompilationId, it.reference, it.digest) }
    }
}

internal class OwnerProducedGraphFacts(val requestDigest: String, val modelDigest: String,
    val producerCompilationId: String, val consumerCompilationId: String, val reference: ModelJvmProducedOutputReference,
    val digest: String) {
    val compilationIds: List<String> = immutable(listOf(producerCompilationId, consumerCompilationId))
}

internal sealed class OwnerPlanContextOrigin {
    class Verified(val value: VerifiedCompilationContext) : OwnerPlanContextOrigin()
    class Observed(val value: ObservedCompilationContext) : OwnerPlanContextOrigin()
    val context: AnalysisContext? get() = when (this) { is Verified -> value.context; is Observed -> value.context }
    val issues: List<ModelIssue> get() = when (this) { is Verified -> value.issues; is Observed -> value.issues }
    val completeness: ModelCompleteness get() = when (this) { is Verified -> value.completeness; is Observed -> value.completeness }
    val certificateId: String? get() = when (this) { is Verified -> value.certificateId; is Observed -> value.certificateId }
}

internal sealed class OwnerPlanImpactOrigin {
    class Verified(val value: ProjectImpactPlan) : OwnerPlanImpactOrigin()
    class Observed(val value: ObservedProjectImpactPlan) : OwnerPlanImpactOrigin()
    val changedFiles: List<String> get() = when (this) { is Verified -> value.changedFiles; is Observed -> value.changedFiles }
    val obligations: List<CompilationObligation> get() = when (this) { is Verified -> value.obligations; is Observed -> value.obligations }
    val evidenceDigest: String get() = when (this) { is Verified -> value.certificateId; is Observed -> value.evidenceIdentity }
}

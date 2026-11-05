package dev.ktchecker.cli

import dev.ktchecker.compiler.KotlinJvmModelBinding
import dev.ktchecker.compiler.live.model.KotlinJvmProducedOutputPreview
import dev.ktchecker.contracts.SnapshotManifest
import dev.ktchecker.projectmodel.*
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline

/** Live retained sibling origins. Neither a DTO nor evidence equality can construct the other model. */
internal sealed class OwnerModelOrigin {
    class Verified(val value: VerifiedProjectModel) : OwnerModelOrigin()
    class Observed(val value: ObservedProjectModel) : OwnerModelOrigin()
    val verified: VerifiedProjectModel get() = when (this) {
        is Verified -> value
        is Observed -> throw CliProblem("model_observed_not_verified", "An observed one-shot model cannot become verified ingress.")
    }
    val observed: ObservedProjectModel? get() = when (this) { is Verified -> null; is Observed -> value }
    val namespace: ModelNamespace get() = when (this) { is Verified -> value.namespace; is Observed -> value.namespace }
    val snapshot: SnapshotManifest get() = when (this) { is Verified -> value.snapshot; is Observed -> value.snapshot }
    fun matches(preview: KotlinJvmProducedOutputPreview): Boolean = when (this) {
        is Verified -> preview.observedModel == null && preview.model === value && preview.graph === value.jvmProducedGraph
        is Observed -> preview.observedModel === value && preview.observedGraph === value.jvmProducedGraph
    }
    fun matches(binding: KotlinJvmModelBinding): Boolean = when (this) {
        is Verified -> binding.observedModel == null && binding.model === value
        is Observed -> binding.observedModel === value
    }
    fun requireOriginalClock(deadline: OwnedApplicationDeadline) {
        when (this) {
            is Verified -> Unit
            is Observed -> {
                if (value.invocation.originalDeadline !== deadline) throw CliProblem("model_observed_original_deadline_mismatch",
                    "Observed import, intent, generation and both children require the same original pre-export clock object.")
                value.invocation.verifyLive()
            }
        }
    }
}

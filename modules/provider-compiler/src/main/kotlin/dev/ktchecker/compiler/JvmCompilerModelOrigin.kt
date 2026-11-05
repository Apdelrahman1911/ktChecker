package dev.ktchecker.compiler

import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.*
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import java.util.Collections

/** Read-only graph evidence. It is NOT an importer, output, binding or execution capability. */
class JvmProducedPreviewGraphIdentity internal constructor(
    val requestDigest: String,
    val modelDigest: String,
    val producerCompilationId: String,
    val consumerCompilationId: String,
    val reference: ModelJvmProducedOutputReference,
    val digest: String,
) {
    val compilationIds: List<String> = Collections.unmodifiableList(listOf(producerCompilationId, consumerCompilationId))
}

/** Shared validators read frozen facts, while the exact nominal origin is NEVER erased. */
internal class JvmCompilerContextFacts(val compilation: ModelCompilation?, val context: AnalysisContext?,
    val completeness: ModelCompleteness, val certificateId: String?)

internal sealed class JvmCompilerModelOrigin {
    class VerifiedOrigin(val value: VerifiedProjectModel) : JvmCompilerModelOrigin()
    class ObservedOneShotOrigin(val value: ObservedProjectModel) : JvmCompilerModelOrigin()

    val verified: VerifiedProjectModel get() = when (this) {
        is VerifiedOrigin -> value
        is ObservedOneShotOrigin -> throw KotlinJvmModelException("compiler_model_observed_origin_forbidden",
            "An observed one-shot model is not a verified model or an ordinary compiler grant.")
    }
    val observed: ObservedProjectModel? get() = when (this) {
        is VerifiedOrigin -> null
        is ObservedOneShotOrigin -> value
    }
    val namespace: ModelNamespace get() = when (this) {
        is VerifiedOrigin -> value.namespace
        is ObservedOneShotOrigin -> value.namespace
    }
    val snapshot: SnapshotManifest get() = when (this) {
        is VerifiedOrigin -> value.snapshot
        is ObservedOneShotOrigin -> value.snapshot
    }
    val export: GradleProjectExport get() = when (this) {
        is VerifiedOrigin -> value.export
        is ObservedOneShotOrigin -> value.export
    }
    val identity: ProjectModelIdentity get() = when (this) {
        is VerifiedOrigin -> value.identity
        is ObservedOneShotOrigin -> value.identity
    }
    val issues: List<ModelIssue> get() = when (this) {
        is VerifiedOrigin -> value.issues
        is ObservedOneShotOrigin -> value.issues
    }
    fun context(id: String): JvmCompilerContextFacts = when (this) {
        is VerifiedOrigin -> value.context(id, value.identity.configurationDigest).let {
            JvmCompilerContextFacts(it.compilation, it.context, it.completeness, it.certificateId)
        }
        is ObservedOneShotOrigin -> value.context(id, value.identity.configurationDigest).let {
            JvmCompilerContextFacts(it.compilation, it.context, it.completeness, it.certificateId)
        }
    }
    fun compilation(id: String): ModelCompilation? = when (this) {
        is VerifiedOrigin -> value.compilation(id)
        is ObservedOneShotOrigin -> value.compilation(id)
    }
    fun sourceSet(id: String): ModelSourceSet? = when (this) {
        is VerifiedOrigin -> value.sourceSet(id)
        is ObservedOneShotOrigin -> value.sourceSet(id)
    }
    fun jvmArgumentLayout(id: String): VerifiedJvmArgumentLayout? = when (this) {
        is VerifiedOrigin -> value.jvmArgumentLayout(id)
        is ObservedOneShotOrigin -> value.jvmArgumentLayout(id)
    }
    fun graphIdentity(): JvmProducedPreviewGraphIdentity? = when (this) {
        is VerifiedOrigin -> value.jvmProducedGraph?.let {
            JvmProducedPreviewGraphIdentity(it.requestDigest, it.modelDigest, it.producerCompilationId,
                it.consumerCompilationId, it.reference, it.digest)
        }
        is ObservedOneShotOrigin -> value.jvmProducedGraph?.let {
            JvmProducedPreviewGraphIdentity(it.requestDigest, it.modelDigest, it.producerCompilationId,
                it.consumerCompilationId, it.reference, it.digest)
        }
    }
    fun requireOriginalDeadline(deadline: OwnedApplicationDeadline) {
        when (this) {
            is VerifiedOrigin -> Unit
            is ObservedOneShotOrigin -> {
                if (value.invocation.originalDeadline !== deadline) throw KotlinJvmModelException(
                    "compiler_model_observed_original_deadline_mismatch", "The original pre-export clock object must be retained.")
                value.invocation.verifyLive()
            }
        }
    }
    fun requireObservedPolicies(context: AnalysisContext) {
        when (this) {
            is VerifiedOrigin -> Unit
            is ObservedOneShotOrigin -> {
                val expected = mapOf(OBSERVED_ASSURANCE_POLICY to OBSERVED_EXPORT_PROFILE,
                    OBSERVED_MODEL_EVIDENCE_POLICY to value.evidenceIdentity,
                    OBSERVED_INVOCATION_POLICY to value.invocation.identity,
                    OBSERVED_CLOSURE_POLICY to CONFIGURATION_INPUT_CLOSURE_UNPROVEN)
                if (context.fullProject || expected.any { context.policies[it.key] != it.value }) {
                    throw KotlinJvmModelException("compiler_model_observed_evidence_mismatch",
                        "The typed one-shot model permanently retains its exact observed evidence and closure gap.")
                }
            }
        }
    }
}

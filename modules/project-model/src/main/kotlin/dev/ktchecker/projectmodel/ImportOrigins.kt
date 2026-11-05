package dev.ktchecker.projectmodel

/**
 * Module-private exhaustive authority branch. An observed owner callback cannot be substituted
 * for ModelIngressPermit's complete-read attestation. Shared readers return frozen facts only;
 * public complete/observed importers still construct their own distinct nominal capability.
 */
internal sealed class ModelImportOrigin {
    class Complete(val permit: ModelIngressPermit) : ModelImportOrigin()
    class Observed(val permit: ObservedExportPermit) : ModelImportOrigin()

    val gradleRuntimeDigest: String get() = when (this) {
        is Complete -> permit.gradleRuntimeDigest
        is Observed -> permit.gradleRuntimeDigest
    }

    private fun checkpoint() {
        when (this) {
            is Complete -> Unit
            is Observed -> permit.invocation.verifyLive()
        }
    }

    fun verifySnapshot() {
        checkpoint()
        when (this) {
            is Complete -> permit.verifySnapshot()
            is Observed -> permit.verifySnapshot()
        }
        checkpoint()
    }

    fun verifyExecutedExport() {
        checkpoint()
        when (this) {
            is Complete -> permit.verifyExecutedExport()
            is Observed -> permit.verifyExecutedExport()
        }
        checkpoint()
    }

    fun verifyInputs(graph: GradleProjectExport) {
        checkpoint()
        when (this) {
            is Complete -> permit.verifyModelInputs(graph)
            is Observed -> permit.verifyObservedInputs(graph)
        }
        checkpoint()
    }

    fun verifyArtifact(artifact: ModelArtifact) {
        checkpoint()
        when (this) {
            is Complete -> permit.verifyArtifact(artifact)
            is Observed -> permit.verifyArtifact(artifact)
        }
        checkpoint()
    }

    fun verifyJvmArgumentOrigin(compilation: ModelCompilation): ModelJvmArgumentOrigin? {
        checkpoint()
        val value = when (this) {
            is Complete -> permit.verifyJvmArgumentOrigin(compilation)
            is Observed -> permit.verifyJvmArgumentOrigin(compilation)
        }
        checkpoint()
        return value
    }

    fun verifyJvmProducedOutputOrigin(compilation: ModelCompilation): ModelJvmProducedOutputOrigin? {
        checkpoint()
        val value = when (this) {
            is Complete -> permit.verifyJvmProducedOutputOrigin(compilation)
            is Observed -> permit.verifyJvmProducedOutputOrigin(compilation)
        }
        checkpoint()
        return value
    }
}

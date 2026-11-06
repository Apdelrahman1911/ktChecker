package dev.ktchecker.semantic

import dev.ktchecker.contracts.ArtifactIdentity
import dev.ktchecker.contracts.ContractJson
import kotlinx.serialization.decodeFromString

/**
 * Internal read-only extractor/validator view. It NEVER constructs a legacy request from observed
 * data. The original nominal request supplies its own digest; no target/platform normalization or
 * model/execution authority is introduced by sharing the pure FIR vocabulary implementation.
 */
internal class SemanticRequestData private constructor(
    val snapshotId: String,
    val analysisId: String,
    val toolchainId: String,
    val compilerArtifacts: List<ArtifactIdentity>,
    val pluginArtifact: ArtifactIdentity,
    val sourceRoot: String,
    val sources: List<SemanticSource>,
    val sourceSets: List<SemanticSourceSet>,
    val leafSourceSet: String,
    val target: String,
    val compilationId: String,
    val compilerArguments: List<String>,
    val dependencyArtifacts: List<ArtifactIdentity>,
    val compilerVersion: String,
    val adapterId: String,
    val schemaVersion: Int,
    val observeJvmBinaryOrigins: Boolean,
    val directIntLiteralArgumentVersion: Int?,
    val observed: ObservedSemanticFactsRequest?,
    private val originalDigest: () -> String,
) {
    fun digest(): String = originalDigest()

    companion object {
        fun of(value: SemanticFactsRequest): SemanticRequestData = with(value) {
            SemanticRequestData(snapshotId, analysisId, toolchainId, compilerArtifacts, pluginArtifact, sourceRoot,
                sources, sourceSets, leafSourceSet, target, compilationId, compilerArguments, dependencyArtifacts,
                compilerVersion, adapterId, schemaVersion, observeJvmBinaryOrigins, directIntLiteralArgumentVersion,
                null, value::digest)
        }
        fun of(value: ObservedSemanticFactsRequest): SemanticRequestData = with(value) {
            SemanticRequestData(snapshotId, analysisId, toolchainId, compilerArtifacts, pluginArtifact, sourceRoot,
                sources, sourceSets, leafSourceSet, target, compilationId, compilerArguments, dependencyArtifacts,
                compilerVersion, adapterId, schemaVersion, observeJvmBinaryOrigins, directIntLiteralArgumentVersion,
                value, value::digest)
        }
    }
}

/** Pure, strict schema selection shared by the actual plugin and data-only decoder controls. */
internal fun decodeSelectedSemanticRequest(kind: String?, text: String): SemanticRequestData {
    if (text.length > 1_048_576) throw SemanticFactException("semantic_request_unsafe", "Semantic request exceeds its text bound")
    return when (kind) {
        null -> SemanticRequestData.of(ContractJson.decodeFromString<SemanticFactsRequest>(text))
        OBSERVED_SEMANTIC_REQUEST_KIND -> {
            val request = ContractJson.decodeFromString<ObservedSemanticFactsRequest>(text)
            observedSemanticRequestBounds(request)
            SemanticRequestData.of(request)
        }
        else -> throw SemanticFactException("semantic_request_kind_unsupported", "Unknown semantic request kind")
    }
}

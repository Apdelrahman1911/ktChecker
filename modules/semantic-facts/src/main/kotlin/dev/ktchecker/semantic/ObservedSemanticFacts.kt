@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.ktchecker.semantic

import dev.ktchecker.contracts.ArtifactIdentity
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement

const val OBSERVED_SEMANTIC_REQUEST_KIND = "observed-jvm-v1"
const val OBSERVED_SEMANTIC_REQUEST_PROFILE = "ktchecker/observed-jvm-semantic-request/1"
const val OBSERVED_SEMANTIC_CONFIGURATION_GAP = "configuration_input_closure_unproven"

/**
 * Separate nominal DATA schema, never a conversion to SemanticFactsRequest/VerifiedProjectModel.
 * target is the RAW model target name (including the ordinary JVM empty name); platform is the
 * independently observed backend. Both remain in this distinct request digest and output binding.
 * Model/invocation/clock labels are documentary joins only: an actual live owner action must retain
 * the SAME original model, admission, archives, attempt, deadline and returned compiler outcome.
 * A new plugin action needs its own exact execution authority; this DTO cannot borrow one.
 */
@Serializable
data class ObservedSemanticFactsRequest(
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
    val platform: String,
    val compilationId: String,
    /** Exact ORIGINAL selected model argv. A separately authorized profile binds its projection. */
    val compilerArguments: List<String>,
    val modelEvidenceIdentity: String,
    val observedInvocationIdentity: String,
    val originalDeadlineIdentity: String,
    val originalDeadlineMillis: Long,
    val dependencyArtifacts: List<ArtifactIdentity> = emptyList(),
    val compilerVersion: String = SEMANTIC_COMPILER_VERSION,
    val adapterId: String = SEMANTIC_ADAPTER_ID,
    val profile: String = OBSERVED_SEMANTIC_REQUEST_PROFILE,
    val schemaVersion: Int = 1,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val observeJvmBinaryOrigins: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val directIntLiteralArgumentVersion: Int? = null,
) {
    fun digest(): String {
        observedSemanticRequestBounds(this)
        return Digests.sha256("ktchecker:observed-semantic-request:v1\u0000" +
            Digests.canonical(ContractJson.encodeToJsonElement(this)))
    }
}

/**
 * Frozen structural validation DATA, not an execution or supplying-binary capability. Complete
 * vocabulary does not close the observed model's configuration/read-set gap. No constructor,
 * decoder, copy method or flag can turn this nominal result into project/tuple/rule qualification.
 */
class ObservedSemanticFactBundle internal constructor(
    val request: ObservedSemanticFactsRequest,
    val facts: SemanticFactBundle,
) {
    val configurationInputClosure: String get() = OBSERVED_SEMANTIC_CONFIGURATION_GAP
    val completeProject: Boolean get() = false
    val tupleQualified: Boolean get() = false
    val customRulesQualified: Boolean get() = false
}

/** Bound the new schema before copying/digesting it. Legacy limits/encoding remain unchanged. */
internal fun observedSemanticRequestBounds(value: ObservedSemanticFactsRequest) {
    var textBytes = 0L
    fun need(valid: Boolean) {
        if (!valid) throw SemanticFactException("observed_semantic_request_bound", "Observed semantic request exceeds its data bounds")
    }
    fun text(value: String, maximum: Int = 4_096) {
        need(value.length <= maximum)
        // String-to-UTF8 hashing replaces malformed UTF16. Refuse, never repair, every field
        // BEFORE serialization/digesting so distinct nominal requests cannot share that repair.
        var index = 0
        while (index < value.length) {
            val character = value[index++]
            if (character.isHighSurrogate()) {
                need(index < value.length && value[index].isLowSurrogate())
                index++
            } else need(!character.isLowSurrogate())
        }
        textBytes += 2L + 6L * value.length
        need(textBytes <= 8L * 1024 * 1024)
    }
    fun artifact(value: ArtifactIdentity) { text(value.coordinate); text(value.digest); text(value.path) }
    need(value.sources.size <= 1024 && value.sourceSets.size <= 256 && value.compilerArtifacts.size <= 256 &&
        value.dependencyArtifacts.size <= 4096 && value.compilerArguments.size <= 4096)
    with(value) {
        listOf(snapshotId, analysisId, toolchainId, sourceRoot, leafSourceSet, target, platform, compilationId,
            modelEvidenceIdentity, observedInvocationIdentity, originalDeadlineIdentity, compilerVersion, adapterId, profile).forEach { text(it) }
        compilerArtifacts.forEach(::artifact); artifact(pluginArtifact); dependencyArtifacts.forEach(::artifact)
        compilerArguments.forEach { text(it, 16_384) }
        sources.forEach { text(it.path); text(it.digest); text(it.sourceSet) }
        sourceSets.forEach { sourceSet ->
            need(sourceSet.dependsOn.size <= 256); text(sourceSet.id); sourceSet.dependsOn.forEach { text(it) }
        }
    }
}

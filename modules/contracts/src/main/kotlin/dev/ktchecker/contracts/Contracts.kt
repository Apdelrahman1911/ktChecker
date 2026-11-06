package dev.ktchecker.contracts

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

const val PREVIEW_CONTRACT = "ktchecker/preview-1"

val ContractJson = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }

object Digests {
    fun sha256(bytes: ByteArray): String = "sha256:" + MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
    fun sha256(text: String): String = sha256(text.toByteArray(StandardCharsets.UTF_8))
    fun valid(value: String): Boolean = value.matches(Regex("sha256:[0-9a-f]{64}"))
    fun canonical(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries.sortedBy { it.key }.joinToString(",", "{", "}") {
            JsonPrimitive(it.key).toString() + ":" + canonical(it.value)
        }
        is JsonArray -> element.joinToString(",", "[", "]") { canonical(it) }
        else -> element.toString()
    }
}

/** Trusted transport supplies principal identity; request data cannot grant permissions. */
@Serializable data class ClientIdentity(val ownerId: String, val clientId: String, val sessionId: String)

@Serializable data class SnapshotFile(val path: String, val digest: String, val size: Long)

/** Snapshot digest is content identity, not proof of a current mutable working directory. */
@Serializable data class SnapshotManifest(
    val snapshotId: String,
    val workspaceId: String,
    val worktreeId: String,
    val files: List<SnapshotFile>,
    val ownerId: String = "",
    val parentSnapshotId: String? = null,
    val captureKind: String = "immutable_overlay",
    val declaredRoots: List<String> = emptyList(),
    val scopeExclusions: List<String> = emptyList(),
    val unknownInputs: List<String> = emptyList(),
)

@Serializable data class ArtifactIdentity(val coordinate: String, val digest: String, val path: String)

@Serializable data class RuleContext(
    val target: String = "jvm",
    val sourceSet: String = "main",
    val module: String = ":main",
    val compilation: String = "main",
) { val id: String get() = ContractJson.encodeToString(listOf(module, sourceSet, target, compilation)) }

/** Unknown model/toolchain/dependency digests prohibit semantic completeness or unsafe reuse. */
@Serializable data class AnalysisContext(
    val scope: RuleContext = RuleContext(),
    val compilerVersion: String? = null,
    val classpath: List<ArtifactIdentity> = emptyList(),
    val compilerArguments: List<String> = emptyList(),
    val libraries: Map<String, String> = emptyMap(),
    val policies: Map<String, String> = emptyMap(),
    val modelDigest: String? = null,
    val dependencyDigest: String? = null,
    val toolchainDigest: String? = null,
    val configurationDigest: String? = null,
    val fullProject: Boolean = false,
    /** Exact owner-imported compilation members. Null means unavailable, not every project source set. */
    val compilationFiles: List<String>? = null,
)

/** Execution settings are owner-authorized code, not taken from project comments/model args. */
@Serializable data class TrustedExecution(
    val executable: String,
    val arguments: List<String> = emptyList(),
    val approved: Boolean = false,
    val executableDigest: String? = null,
    val timeoutMillis: Long = 60_000,
    val maxOutputBytes: Int = 1_048_576,
    val environment: Map<String, String> = emptyMap(),
)

@Serializable data class AnalysisInput(
    val snapshot: SnapshotManifest,
    val materializedRoot: String,
    val context: AnalysisContext = AnalysisContext(),
    val requiredRuleIds: List<String> = emptyList(),
    val changedPaths: List<String> = emptyList(),
    val baselineSnapshotId: String? = null,
    val execution: TrustedExecution? = null,
    val outputRoot: String? = null,
) {
    /** Conservative initial key includes paths/arguments. Narrower reuse needs an equivalence proof. */
    fun analysisKey(): String = Digests.sha256(Digests.canonical(ContractJson.encodeToJsonElement(this)))
}

@Serializable data class SourceLocation(
    val path: String,
    val fileDigest: String,
    val line: Int = 1,
    val column: Int = 0,
    val endLine: Int = line,
    val endColumn: Int = column,
    val excerpt: String = "",
)

@Serializable data class Evidence(
    val kind: String,
    val summary: String,
    val artifactRef: String? = null,
    val digest: String? = null,
)

/** Digest-linked retrieval metadata, never suppression identity, source proof or access authority.
 * Consumers must retain the locator in full delivery/receipt bytes and verify content before use.
 */
@Serializable data class EvidenceRetrievalAttachment(
    val kind: String,
    val digest: String,
    val artifactRef: String,
)

@Serializable data class Verification(
    val kind: String = "recheck",
    val expected: String,
    val command: List<String> = emptyList(),
    val state: String = "not_run",
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable data class Finding(
    val ruleId: String,
    val title: String,
    val diagnosis: String,
    val whyItMatters: String,
    val location: SourceLocation,
    val fingerprint: String,
    val providerId: String,
    val context: RuleContext = RuleContext(),
    val severity: String = "warning",
    val kind: String = "probable_defect",
    val certainty: String = "medium",
    val evidence: List<Evidence> = emptyList(),
    val fixHint: String? = null,
    val verification: List<Verification> = emptyList(),
    val upstreamId: String? = null,
    val symbolId: String? = null,
    val semanticDigest: String? = null,
    val relatedLocations: List<SourceLocation> = emptyList(),
    val disposition: String = "open",
    // Do not add an empty field to legacy/default Finding bytes or their content checksums.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val retrievalAttachments: List<EvidenceRetrievalAttachment> = emptyList(),
) {
    init {
        // Direct construction, copy and decode validate the link. This is not collection
        // immutability: publication must still detach and reject later caller-owned mutation.
        if (retrievalAttachments.isNotEmpty()) require(retrievalAttachments.all { attachment ->
            attachment.kind.isNotBlank() && '\u0000' !in attachment.kind &&
                attachment.artifactRef.isNotBlank() && '\u0000' !in attachment.artifactRef &&
                Digests.valid(attachment.digest) && evidence.any {
                    it.kind == attachment.kind && it.digest == attachment.digest
                }
        }) { "finding_retrieval_attachment_invalid_or_unlinked" }
    }
}

@Serializable data class Coverage(
    val ruleId: String,
    val context: RuleContext = RuleContext(),
    val state: String,
    val reason: String? = null,
    val required: Boolean = true,
    val depth: String = "build",
    val provenance: String = "not_executed",
    val certificateId: String? = null,
    val implemented: Boolean = false,
    val tested: Boolean = false,
    val qualified: Boolean = false,
) {
    fun successful(): Boolean = state in setOf("passed", "findings", "not_applicable") &&
        !certificateId.isNullOrBlank() && provenance in setOf("executed", "reused_with_proof")
}

@Serializable data class RawDiagnostic(
    val message: String,
    val severity: String = "error",
    val upstreamId: String? = null,
    val location: SourceLocation? = null,
)

@Serializable data class AnalysisMetrics(
    val elapsedMillis: Long = 0,
    val cpuMillis: Long? = null,
    val peakRssBytes: Long? = null,
    val outputBytes: Long = 0,
)

@Serializable data class ProviderResult(
    val providerId: String,
    val status: String,
    val findings: List<Finding> = emptyList(),
    val coverage: List<Coverage> = emptyList(),
    val reasons: List<String> = emptyList(),
    val rawDiagnostics: List<RawDiagnostic> = emptyList(),
    val metrics: AnalysisMetrics = AnalysisMetrics(),
    val providerVersion: String = "preview",
) {
    /** Structural consistency only. The broker still verifies the exact plan, identity and certificates. */
    fun complete(): Boolean = status == "complete" && coverage.any { it.required } &&
        coverage.filter { it.required }.all { it.successful() }

    fun completeFor(input: AnalysisInput): Boolean {
        val expected = input.requiredRuleIds.toSet()
        val required = coverage.filter { it.required }
        return expected.isNotEmpty() && complete() && required.size == expected.size &&
            required.map { it.ruleId }.toSet() == expected &&
            required.all { it.context == input.context.scope }
    }
}

fun interface CancellationToken {
    fun isCancelled(): Boolean
    fun throwIfCancelled() { if (isCancelled()) throw CancellationException("analysis cancelled") }
    companion object { val NONE = CancellationToken { false } }
}

interface AnalysisProvider {
    val id: String
    val version: String get() = "preview"
    val supportedRuleIds: Set<String>
    fun analyze(input: AnalysisInput, cancellation: CancellationToken = CancellationToken.NONE): ProviderResult
}

/** Intermediate observations do not discharge the final required rule/context plan. */
interface ProgressiveAnalysisProvider : AnalysisProvider {
    fun analyzeProgressive(
        input: AnalysisInput,
        cancellation: CancellationToken,
        onProgress: (ProviderResult) -> Unit,
    ): ProviderResult

    override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult =
        analyzeProgressive(input, cancellation) { }
}

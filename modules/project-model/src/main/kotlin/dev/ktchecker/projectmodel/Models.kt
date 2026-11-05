package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.AnalysisContext
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.RuleContext
import dev.ktchecker.contracts.SnapshotManifest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import java.util.Collections

const val GRADLE_MODEL_EXPORTER_ID = "ktchecker-gradle-kmp-model/1"

@Serializable
data class ModelNamespace(val ownerId: String, val workspaceId: String, val worktreeId: String, val lineageId: String)

/** Exact immutable owner capture and execution, not a caller-selected complete/project flag. */
@Serializable
data class ModelImportRequest(
    val namespace: ModelNamespace,
    val snapshot: SnapshotManifest,
    val materializedRoot: String,
    val configurationDigest: String,
    val exportDigest: String,
    val exporterDigest: String,
    val executionId: String,
) {
    fun digest(): String = Digests.sha256("ktchecker/model-ingress/1\u0000" +
        Digests.canonical(ContractJson.encodeToJsonElement(this)))
}

@Serializable
data class ModelFile(val path: String, val digest: String, val size: Long)

/** External artifacts must be independently anchored to immutable owner/CAS bytes by ingress. */
@Serializable
data class ModelArtifact(val coordinate: String, val path: String, val digest: String?, val size: Long? = null)

@Serializable
data class ModelSourceSet(
    val id: String,
    val moduleId: String,
    val name: String,
    val dependsOn: List<String>,
    val sourceRoots: List<String>,
    val resourceRoots: List<String>,
    val sources: List<ModelFile>,
    val resources: List<ModelFile>,
)

@Serializable
data class GeneratedSourceInput(
    val taskPath: String,
    val sourceSetIds: List<String>,
    val inputFiles: List<ModelFile>,
    val outputFiles: List<ModelFile>,
    val outputRoots: List<String>,
    val state: String,
    /** Actual Gradle task input properties, canonically encoded; unknown objects are gaps. */
    val inputProperties: Map<String, String> = emptyMap(),
)

@Serializable
data class ModelToolchain(
    val kind: String,
    val compilerVersion: String?,
    val compilerArtifacts: List<ModelArtifact>,
    val runtimeArtifacts: List<ModelArtifact>,
    val compilerPlugins: List<ModelArtifact> = emptyList(),
)

@Serializable
data class ModelProjectDependency(
    val moduleId: String,
    val selectedVariant: String,
    val variantAttributes: Map<String, String>,
    /** Exact resolved producer identity. Missing mapping widens impact; it does not guess main. */
    val compilationId: String? = null,
)

@Serializable
data class ModelCompilation(
    val id: String,
    val moduleId: String,
    val target: String,
    val platform: String,
    val name: String,
    val defaultSourceSetId: String,
    val sourceSetIds: List<String>,
    val sourceFiles: List<ModelFile>,
    val compileTaskPath: String,
    val dependencyConfiguration: String,
    /** Order is significant. Do not sort a compiler classpath when hashing or executing it. */
    val classpath: List<ModelArtifact>,
    val projectDependencies: List<ModelProjectDependency>,
    val toolchain: ModelToolchain?,
    /** Intended task-configuration arguments, not proof of an observed compiler invocation.
     * The pinned exporter requests every KGP argument type with errors enabled. Any failure
     * is a completeness gap; partial serialized options are never an execution substitute. */
    val compilerArguments: List<String>,
    val languageVersion: String?,
    val apiVersion: String?,
    val friendArtifacts: List<ModelArtifact> = emptyList(),
    val producedVariants: List<String> = emptyList(),
    /** Actual associateWith/friend compilation graph, not guessed test-to-main relationships. */
    val associatedCompilationIds: List<String> = emptyList(),
    /** Java sources and compiler/plugin auxiliary inputs captured in the same source snapshot. */
    val auxiliaryInputFiles: List<ModelFile> = emptyList(),
    val auxiliaryArtifacts: List<ModelArtifact> = emptyList(),
    /** Optional full-original-vector facts. Absent legacy fields never infer path-role authority. */
    val jvmArgumentRoles: ModelJvmArgumentRoles? = null,
    /** Deferred producer obligations never become externally enrolled artifacts after import. */
    val deferredJvmOutputs: List<ModelJvmProducedOutputReference> = emptyList(),
)

@Serializable
data class ModelModule(val id: String, val directory: String, val buildFile: String?, val pluginIds: List<String>)

/** Exporter facts are data, not authority. Unknown kinds are conservative completeness gaps. */
@Serializable
data class ModelIssue(val kind: String, val scopeId: String? = null, val detail: String)

@Serializable
data class GradleProjectExport(
    val schemaVersion: Int = 1,
    val exporterId: String = GRADLE_MODEL_EXPORTER_ID,
    val gradleVersion: String,
    val modules: List<ModelModule>,
    val sourceSets: List<ModelSourceSet>,
    val compilations: List<ModelCompilation>,
    val configurationInputs: List<ModelFile>,
    val generatedInputs: List<GeneratedSourceInput>,
    val buildscriptArtifacts: List<ModelArtifact>,
    val includedBuilds: List<String>,
    val issues: List<ModelIssue>,
    /** Owner-pinned external settings/init scripts are explicit, never silently outside the graph. */
    val externalConfigurationArtifacts: List<ModelArtifact> = emptyList(),
)

@Serializable
data class ProjectModelIdentity(
    val snapshotId: String,
    val sourceDigest: String,
    val modelDigest: String,
    val dependencyDigest: String,
    val toolchainDigest: String,
    val exporterDigest: String,
    val gradleRuntimeDigest: String,
    val configurationDigest: String,
)

enum class ModelCompleteness { COMPLETE, UNAVAILABLE, UNSUPPORTED }

/** A bounded compilation model, NOT a semantic resolver or a successful compiler certificate. */
class VerifiedCompilationContext internal constructor(
    val compilation: ModelCompilation?,
    val context: AnalysisContext?,
    val completeness: ModelCompleteness,
    issues: List<ModelIssue>,
    val certificateId: String?,
) {
    val issues: List<ModelIssue> = immutable(issues)
}

/** Nonserialized, importer-created capability. JSON round trips never recreate its verification. */
class VerifiedProjectModel internal constructor(
    val namespace: ModelNamespace,
    val snapshot: SnapshotManifest,
    val export: GradleProjectExport,
    val identity: ProjectModelIdentity,
    issues: List<ModelIssue>,
    val ingressCertificate: String,
    jvmArgumentLayouts: Map<String, VerifiedJvmArgumentLayout> = emptyMap(),
    jvmArgumentRejections: Map<String, String> = emptyMap(),
    /** Explicit incomplete-graph preview only; normal context/completeness is unchanged. */
    val jvmProducedGraph: VerifiedJvmProducedGraph? = null,
) {
    private val jvmArgumentLayouts: Map<String, VerifiedJvmArgumentLayout> = immutableMap(jvmArgumentLayouts)
    /** Execution-layout eligibility is separate from unchanged whole-graph completeness issues. */
    val jvmArgumentRejections: Map<String, String> = immutableMap(jvmArgumentRejections)
    fun jvmArgumentLayout(compilationId: String): VerifiedJvmArgumentLayout? = jvmArgumentLayouts[compilationId]
    val issues: List<ModelIssue> = immutable(issues)
    val compilationIds: List<String> = immutable(export.compilations.map { it.id })
    fun context(scope: RuleContext, configurationDigest: String): VerifiedCompilationContext = compilationContext(this, scope, configurationDigest)
    fun context(compilationId: String, configurationDigest: String): VerifiedCompilationContext {
        val compilation = compilation(compilationId) ?: return VerifiedCompilationContext(null, null, ModelCompleteness.UNSUPPORTED,
            listOf(ModelIssue("compilation_context_unknown", compilationId, "Requested compilation is absent; it is not silently retired.")), null)
        val sourceSet = requireNotNull(sourceSet(compilation.defaultSourceSetId))
        return context(RuleContext(compilation.target, sourceSet.name, compilation.moduleId, compilation.name), configurationDigest)
    }
    fun compilation(id: String): ModelCompilation? = export.compilations.singleOrNull { it.id == id }
    fun sourceSet(id: String): ModelSourceSet? = export.sourceSets.singleOrNull { it.id == id }
    /** Graph-only support does not imply source/API/negative-lookup semantic completeness. */
    fun graphIssues(compilationIds: Collection<String>): List<ModelIssue> = selectedGraphIssues(this, compilationIds)
}

class ProjectModelException(val code: String, message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

internal fun <T> immutable(values: Collection<T>): List<T> = Collections.unmodifiableList(values.toList())
internal fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(values.toMap())
internal fun canonicalDigest(value: kotlinx.serialization.json.JsonElement): String = Digests.sha256(Digests.canonical(value))

package dev.ktchecker.semantic

import dev.ktchecker.contracts.ArtifactIdentity
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement

const val SEMANTIC_ADAPTER_ID = "kotlin-fir-2.4.21/v1"
const val SEMANTIC_COMPILER_VERSION = "2.4.21"
const val SEMANTIC_PLUGIN_ID = "dev.ktchecker.semantic-facts"

/** A selected tuple is not a qualified tuple. Only the trusted execution owner supplies this input. */
@Serializable
data class SemanticFactsRequest(
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
    val dependencyArtifacts: List<ArtifactIdentity> = emptyList(),
    val compilerVersion: String = SEMANTIC_COMPILER_VERSION,
    val adapterId: String = SEMANTIC_ADAPTER_ID,
    val schemaVersion: Int = 1,
) {
    fun digest(): String = Digests.sha256("ktchecker:semantic-request:v1\u0000" +
        Digests.canonical(ContractJson.encodeToJsonElement(this)))
}

@Serializable
data class SemanticSource(val path: String, val digest: String, val size: Long, val sourceSet: String)

/** Explicit fragments/refinement from an authorized model, never inferred from directory names. */
@Serializable
data class SemanticSourceSet(val id: String, val dependsOn: List<String> = emptyList())

/** UTF-16 compiler locations and exact UTF-8 byte offsets are both zero based, end exclusive. */
@Serializable
data class SemanticSpan(
    val path: String,
    val digest: String,
    val sourceSet: String,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val startByte: Int,
    val endByte: Int,
    val synthetic: Boolean = false,
)

@Serializable
data class TypeProjectionFact(val variance: String, val type: TypeFact? = null)

/** Unknown/error/captured types never acquire a fabricated classifier ID. */
@Serializable
data class TypeFact(
    val kind: String,
    val classifierId: String? = null,
    val nullable: Boolean? = null,
    val arguments: List<TypeProjectionFact> = emptyList(),
    val components: List<TypeFact> = emptyList(),
    val parameterOwner: String? = null,
    val parameterIndex: Int? = null,
    val reason: String? = null,
) {
    val known: Boolean get() = kind != "unknown" && reason == null &&
        arguments.all { it.variance == "star" || it.type?.known == true } && components.all { it.known }
}

@Serializable
data class SemanticParameter(
    val index: Int,
    val name: String,
    val type: TypeFact,
    val vararg: Boolean = false,
)

/** ID binds the resolved declaration signature/module, not merely its spelling or imported name. */
@Serializable
data class SymbolFact(
    val id: String,
    val kind: String,
    val qualifiedName: String,
    val compilerModule: String,
    val origin: String,
    val local: Boolean,
    val source: SemanticSpan? = null,
    val returnType: TypeFact? = null,
    val receiverType: TypeFact? = null,
    val dispatchReceiverType: TypeFact? = null,
    val parameters: List<SemanticParameter> = emptyList(),
    val contextParameters: List<SemanticParameter> = emptyList(),
    val typeParameterCount: Int = 0,
    val flags: List<String> = emptyList(),
    val annotationClassIds: List<String> = emptyList(),
    val supertypes: List<TypeFact> = emptyList(),
    val aliasExpansion: TypeFact? = null,
)

@Serializable
data class ArgumentBindingFact(val source: SemanticSpan?, val parameterIndex: Int, val parameterName: String)

/** This is a statically resolved call/access target, not a complete dynamic call graph. */
@Serializable
data class CallFact(
    val source: SemanticSpan,
    val kind: String,
    val targetSymbolId: String,
    val containingSymbolId: String?,
    val resultType: TypeFact,
    val dispatchReceiverType: TypeFact? = null,
    val extensionReceiverType: TypeFact? = null,
    val arguments: List<ArgumentBindingFact> = emptyList(),
)

@Serializable
data class TypeUseFact(val source: SemanticSpan, val type: TypeFact)

/** FIR matching is deliberately distinguished from final expect/actual compatibility checking. */
@Serializable
data class ExpectActualFact(
    val actualSymbolId: String,
    val expectSymbolId: String,
    val matching: String,
    val compilerCompatibilityKind: String,
    val actualSourceSet: String?,
    val expectSourceSet: String?,
)

@Serializable
data class UnknownSemanticFact(val kind: String, val reason: String, val source: SemanticSpan? = null)

@Serializable
data class CompilerSourceContext(
    val moduleName: String,
    val platform: String,
    val common: Boolean,
    val dependsOnModules: List<String>,
)

@Serializable
data class SemanticFactShard(
    val requestDigest: String,
    val snapshotId: String,
    val analysisId: String,
    val toolchainId: String,
    val pluginDigest: String,
    val observedCompilerVersion: String,
    val input: SemanticSource,
    val compilerContext: CompilerSourceContext,
    val declarations: List<String>,
    val symbols: List<SymbolFact>,
    val calls: List<CallFact>,
    val types: List<TypeUseFact>,
    val expectActual: List<ExpectActualFact>,
    val unknown: List<UnknownSemanticFact>,
    val adapterId: String = SEMANTIC_ADAPTER_ID,
    val schemaVersion: Int = 1,
)

@Serializable
data class SemanticFactBundle(
    val requestDigest: String,
    val snapshotId: String,
    val analysisId: String,
    val target: String,
    val compilationId: String,
    val state: String,
    val compilerExitCode: Int?,
    val reasons: List<String>,
    val shards: List<SemanticFactShard>,
    val completeForSupportedVocabulary: Boolean,
    val tupleQualified: Boolean = false,
    val customRulesQualified: Boolean = false,
    val limitations: List<String> = SEMANTIC_LIMITATIONS,
    val adapterId: String = SEMANTIC_ADAPTER_ID,
)

val SEMANTIC_LIMITATIONS = listOf(
    "Version-isolated FIR compiler-plugin spike; not a supported standalone Analysis API implementation.",
    "Statically resolved direct targets are not complete dynamic/candidate/negative dependency edges.",
    "No interprocedural control/data-flow, effect, lifetime, aliasing, or thread-affinity proof.",
    "No universal cinterop/header, compiler-plugin, generated-source, or project-import qualification.",
    "FIR expect/actual candidate matching is not by itself final compatibility or Apple build evidence.",
    "Fact extraction does not implement, test, or qualify any custom diagnostic rule.",
)

class SemanticFactException(val code: String, message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

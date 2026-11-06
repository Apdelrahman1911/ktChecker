@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.ktchecker.semantic

import dev.ktchecker.contracts.ArtifactIdentity
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement
import java.util.Collections

const val SEMANTIC_ADAPTER_ID = "kotlin-fir-2.4.21/v1"
const val SEMANTIC_COMPILER_VERSION = "2.4.21"
const val SEMANTIC_PLUGIN_ID = "dev.ktchecker.semantic-facts"
const val DIRECT_INT_LITERAL_ARGUMENT_VERSION = 1

internal const val DIRECT_INT_LITERAL_ARGUMENTS_PER_CALL_LIMIT = 256
internal const val DIRECT_INT_LITERAL_ARGUMENTS_PER_SHARD_LIMIT = 20_000
internal const val DIRECT_INT_LITERAL_ARGUMENTS_PER_BUNDLE_LIMIT = 100_000

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
    /** Separate opt-in observation, never a request for execution or binary-ownership authority. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val observeJvmBinaryOrigins: Boolean = false,
    /** Independent opt-in vocabulary. Null means NOT OBSERVED, never complete literal coverage. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val directIntLiteralArgumentVersion: Int? = null,
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
    val known: Boolean get() {
        var nodes = 0
        return known(0) { ++nodes <= 100_000 }
    }

    private fun known(depth: Int, withinBudget: () -> Boolean): Boolean {
        if (!withinBudget() || depth > 24 || reason != null || arguments.size > 64 || components.size > 64) return false
        val noParameter = parameterOwner == null && parameterIndex == null
        fun children() = components.all { it.known(depth + 1, withinBudget) }
        return when (kind) {
            "class" -> !classifierId.isNullOrBlank() && classifierId.length <= 4096 && nullable != null &&
                noParameter && components.isEmpty() && arguments.all {
                    when (it.variance) {
                        "star" -> it.type == null
                        "invariant", "in", "out" -> it.type?.known(depth + 1, withinBudget) == true
                        else -> false
                    }
                }
            "type_parameter" -> classifierId == null && nullable != null && arguments.isEmpty() && components.isEmpty() &&
                parameterOwner?.let(Digests::valid) == true && parameterIndex != null && parameterIndex in 0..255
            "flexible" -> classifierId == null && nullable == null && noParameter && arguments.isEmpty() &&
                components.size == 2 && children()
            "intersection" -> classifierId == null && nullable == null && noParameter && arguments.isEmpty() &&
                components.isNotEmpty() && children()
            "definitely_not_null" -> classifierId == null && nullable == false && noParameter && arguments.isEmpty() &&
                components.size == 1 && children()
            else -> false
        }
    }
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

/** Canonical structural binding only; caller-created DTOs do not establish compiler provenance. */
fun resolvedSymbolIdentity(symbol: SymbolFact): String = Digests.sha256("ktchecker:resolved-symbol:v1\u0000" +
    ContractJson.encodeToString(symbol.copy(id = "", source = if (symbol.local) symbol.source else null)))

@Serializable
enum class DirectIntLiteralArgumentState { OBSERVED, UNSUPPORTED, UNKNOWN }

/** Fixed reasons keep this optional scalar vocabulary bounded; no compiler object/text escapes. */
@Serializable
enum class DirectIntLiteralArgumentReason {
    NOT_DIRECT_FIR_LITERAL,
    LITERAL_KIND_NOT_INT,
    VALUE_NOT_INT,
    TYPE_NOT_EXACT_INT,
    SOURCE_UNAVAILABLE,
    SOURCE_SYNTHETIC,
    TYPE_UNAVAILABLE,
}

/**
 * A compiler observation of the mapped argument object, not evaluation of source expressions.
 * OBSERVED requires exactly an Int value and no reason. Other states require their fixed reason
 * and no value. Validation cannot authenticate caller-created DTOs or confer execution authority.
 * Unary/wrapper evaluation, const/variable propagation and descriptor/alias/count proof are absent.
 */
@Serializable
data class DirectIntLiteralArgumentFact(
    val state: DirectIntLiteralArgumentState,
    val value: Int? = null,
    val reason: DirectIntLiteralArgumentReason? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val version: Int = DIRECT_INT_LITERAL_ARGUMENT_VERSION,
)

/** Shared by the real FIR producer and data-only guard controls; it is not provenance evidence. */
internal fun isDirectIntLiteralType(type: TypeFact): Boolean = type.known &&
    type.kind == "class" && type.classifierId == "kotlin.Int" && type.nullable == false &&
    type.arguments.isEmpty() && type.components.isEmpty() &&
    type.parameterOwner == null && type.parameterIndex == null

@Serializable
data class ArgumentBindingFact(
    val source: SemanticSpan?,
    val parameterIndex: Int,
    val parameterName: String,
    /** Absent is NOT OBSERVED, including on an opted-in request; it is never a clean result. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val directIntLiteral: DirectIntLiteralArgumentFact? = null,
)

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
    /** Absent by default/old adapter: binary origins are NOT OBSERVED, never implicitly verified. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val jvmBinaryOrigins: JvmBinaryOriginSurvey? = null,
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
    /** Original core vocabulary only; never literal/descriptor coverage or rule qualification. */
    val completeForSupportedVocabulary: Boolean,
    val tupleQualified: Boolean = false,
    val customRulesQualified: Boolean = false,
    val limitations: List<String> = SEMANTIC_LIMITATIONS,
    val adapterId: String = SEMANTIC_ADAPTER_ID,
)

val SEMANTIC_LIMITATIONS: List<String> = Collections.unmodifiableList(listOf(
    "Version-isolated FIR compiler-plugin spike; not a supported standalone Analysis API implementation.",
    "Statically resolved direct targets are not complete dynamic/candidate/negative dependency edges.",
    "No interprocedural control/data-flow, effect, lifetime, aliasing, or thread-affinity proof.",
    "No universal cinterop/header, compiler-plugin, generated-source, or project-import qualification.",
    "Selected Native distribution digests do not attest the complete external LLVM/libffi/sysroot/cache dependency closure.",
    "FIR expect/actual candidate matching is not by itself final compatibility or Apple build evidence.",
    "Fact extraction does not implement, test, or qualify any custom diagnostic rule.",
))

class SemanticFactException(val code: String, message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

package dev.ktchecker.providers.library

import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.*
import dev.ktchecker.semantic.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

const val COROUTINE_REFINEMENT_PROVIDER_ID = "library-coroutine-refinement"
const val COROUTINE_REFINEMENT_VERSION = "direct-sleep-result/1"
const val SLEEP_IN_COROUTINE = "ktc.coroutines.sleep-in-coroutine"
const val RESULT_SWALLOWING_CANCELLATION = "ktc.coroutines.result-swallowing-cancellation"

@Serializable
enum class CoroutineCallableRole { THREAD_SLEEP_LONG, RUN_CATCHING, DELAY_LONG, GET_OR_ELSE, GET_OR_THROW }

/** Selection is DATA. The host must separately attest each resolved binary's actual ownership. */
@Serializable
data class CoroutineCallableSelection(
    val role: CoroutineCallableRole,
    val symbolId: String,
    /** Null only for Thread.sleep: its ownership is bound to the complete selected JDK image. */
    val dependencyArtifact: ArtifactIdentity? = null,
)

@Serializable
data class CoroutineRefinementApplicability(
    val callables: List<CoroutineCallableSelection>,
    /** A complete owner-selected vector, including empty. Source names/annotations grant nothing. */
    val blockingBridgeExemptions: List<BlockingInteropExemption> = emptyList(),
    val adapterId: String = SEMANTIC_ADAPTER_ID,
    val compilerVersion: String = SEMANTIC_COMPILER_VERSION,
    val target: String = "jvm",
    val schemaVersion: Int = 1,
)

/** Raw serialized facts, exit-code fields and selected names never establish an execution. */
data class CoroutineRefinementSelection(
    val request: SemanticFactsRequest,
    val shards: List<SemanticFactShard>,
    val compilerExitCode: Int?,
    val applicability: CoroutineRefinementApplicability,
)

@Serializable
data class CoroutineRefinementAuthorizationRequest(
    val modelNamespace: ModelNamespace,
    val modelCertificateId: String,
    val input: AnalysisInput,
    val semanticRequest: SemanticFactsRequest,
    val runtimeArtifacts: List<ArtifactIdentity>,
    val compilerExitCode: Int,
    val factContentDigest: String,
    val applicability: CoroutineRefinementApplicability,
    val providerId: String = COROUTINE_REFINEMENT_PROVIDER_ID,
    val providerVersion: String = COROUTINE_REFINEMENT_VERSION,
) {
    fun digest(): String {
        refinementAuthorizationBounds(this)
        return Digests.sha256("ktchecker/coroutine-refinement-authorization/1\u0000" +
            Digests.canonical(ContractJson.encodeToJsonElement(this)))
    }
}

fun interface CoroutineRefinementAuthority {
    fun authorize(request: CoroutineRefinementAuthorizationRequest): CoroutineRefinementPermit?
    companion object { val DENY = CoroutineRefinementAuthority { null } }
}

/**
 * Single-use NONSERIALIZED host capability. No callback is optional and production must not
 * echo DTOs, accept claimed exit codes, or replace immutable evidence with live-checkout scans.
 *
 * verifyExtraction checks the registered successful exact compiler/plugin invocation, request,
 * source inventory, ordered options/dependencies and frozen output digest. verifyRuntimeImage
 * checks its actual whole selected JDK image/closure (a filename or one lib/modules pin is NOT
 * an image attestation). verifyCallableOwnership checks EACH selected resolved symbol against
 * that JDK image or its exact genuine stdlib/coroutines binary under this ordered dependency
 * vector. The extractor's origin/module/name alone does not identify the supplying artifact.
 * verifyBlockingBridgePolicy checks the complete explicit owner configuration bound to THIS
 * input's configurationDigest, including empty exclusions. Even these receipts do not qualify
 * the tuple/rule or authorize a process launch.
 */
class CoroutineRefinementPermit(
    private val model: VerifiedProjectModel,
    request: CoroutineRefinementAuthorizationRequest,
    private val verifySnapshot: (CoroutineRefinementAuthorizationRequest) -> Unit,
    private val verifyExtraction: (CoroutineRefinementAuthorizationRequest) -> Unit,
    private val verifyArtifact: (ArtifactIdentity) -> Unit,
    private val verifyRuntimeImage: (CoroutineRefinementAuthorizationRequest) -> Unit,
    private val verifyCallableOwnership: (CoroutineCallableSelection, CoroutineRefinementAuthorizationRequest) -> Unit,
    private val verifyBlockingBridgePolicy: (CoroutineRefinementAuthorizationRequest) -> Unit,
    private val readSourceBytes: (SemanticSource) -> ByteArray,
) {
    val request: CoroutineRefinementAuthorizationRequest = freezeRefinementAuthorization(model, request)
    val requestDigest: String = this.request.digest()
    private val consumed = AtomicBoolean()

    internal fun verify(actualModel: VerifiedProjectModel, actual: CoroutineRefinementAuthorizationRequest,
                        cancellation: CancellationToken) {
        cancellation.throwIfCancelled()
        refinementRequire(actualModel === model && actual.digest() == requestDigest &&
            consumed.compareAndSet(false, true), "coroutine_refinement_authorization_mismatch_or_replay")
        verifySnapshot(request); cancellation.throwIfCancelled()
        verifyExtraction(request); cancellation.throwIfCancelled()
        val extraction = request.semanticRequest
        (extraction.compilerArtifacts + extraction.pluginArtifact + extraction.dependencyArtifacts +
            request.runtimeArtifacts).forEach {
            cancellation.throwIfCancelled(); verifyArtifact(it); cancellation.throwIfCancelled()
        }
        verifyRuntimeImage(request); cancellation.throwIfCancelled()
        request.applicability.callables.forEach {
            cancellation.throwIfCancelled(); verifyCallableOwnership(it, request); cancellation.throwIfCancelled()
        }
        verifyBlockingBridgePolicy(request); cancellation.throwIfCancelled()
    }

    internal fun bytes(source: SemanticSource, cancellation: CancellationToken): ByteArray {
        cancellation.throwIfCancelled()
        val bytes = readSourceBytes(source).copyOf()
        cancellation.throwIfCancelled()
        refinementRequire(bytes.size.toLong() == source.size && Digests.sha256(bytes) == source.digest,
            "coroutine_refinement_snapshot_bytes_mismatch")
        return bytes
    }
}

class ExactCoroutineRefinementAuthority(private val permit: CoroutineRefinementPermit) : CoroutineRefinementAuthority {
    override fun authorize(request: CoroutineRefinementAuthorizationRequest): CoroutineRefinementPermit? =
        permit.takeIf { request.digest() == it.requestDigest }
}

internal class CoroutineRefinementProblem(val code: String) : IllegalArgumentException(code)
internal fun refinementRequire(value: Boolean, code: String) { if (!value) throw CoroutineRefinementProblem(code) }

internal fun freezeRefinementApplicability(value: CoroutineRefinementApplicability): CoroutineRefinementApplicability {
    refinementApplicabilityBounds(LibraryTextBudget(), value)
    refinementRequire(value.schemaVersion == 1 && value.adapterId == SEMANTIC_ADAPTER_ID &&
        value.compilerVersion == SEMANTIC_COMPILER_VERSION && value.target == "jvm",
        "coroutine_refinement_applicability_unsupported")
    refinementRequire(value.callables.size <= 5 && value.callables.map { it.role }.distinct().size == value.callables.size &&
        value.callables.map { it.symbolId }.distinct().size == value.callables.size &&
        value.callables.all { Digests.valid(it.symbolId) }, "coroutine_refinement_callable_selection_invalid")
    value.callables.forEach { selected ->
        val coordinate = when (selected.role) {
            CoroutineCallableRole.THREAD_SLEEP_LONG -> null
            CoroutineCallableRole.DELAY_LONG -> RUNBLOCKING_ARTIFACT
            else -> "org.jetbrains.kotlin:kotlin-stdlib:$SEMANTIC_COMPILER_VERSION"
        }
        refinementRequire(if (coordinate == null) selected.dependencyArtifact == null else
            selected.dependencyArtifact?.let { it.coordinate == coordinate && Digests.valid(it.digest) &&
                it.path.length in 1..4096 } == true, "coroutine_refinement_callable_artifact_unsupported")
    }
    refinementRequire(value.blockingBridgeExemptions.size <= 256 &&
        value.blockingBridgeExemptions.distinct().size == value.blockingBridgeExemptions.size &&
        value.blockingBridgeExemptions.all { Digests.valid(it.symbolId) && !it.source.synthetic },
        "coroutine_refinement_policy_selection_invalid")
    return value.copy(callables = libraryList(value.callables),
        blockingBridgeExemptions = libraryList(value.blockingBridgeExemptions))
}

internal data class CoroutineRefinementBinding(val input: AnalysisInput, val runtimeArtifacts: List<ArtifactIdentity>)

/** Exact model/context/source/option equality, never a directory-to-source-set or target guess. */
internal fun refinementBinding(model: VerifiedProjectModel, input: AnalysisInput, request: SemanticFactsRequest,
                               api: CoroutineRefinementApplicability): CoroutineRefinementBinding {
    LibraryTextBudget().input(input)
    refinementRequire(input.snapshot == model.snapshot && input.context.configurationDigest == model.identity.configurationDigest &&
        input.snapshot.unknownInputs.isEmpty() && input.snapshot.scopeExclusions.isEmpty(),
        "coroutine_refinement_model_snapshot_configuration_mismatch")
    val verified = model.context(input.context.scope, model.identity.configurationDigest)
    refinementRequire(verified.completeness == ModelCompleteness.COMPLETE && verified.certificateId != null &&
        verified.context == input.context, "coroutine_refinement_verified_compilation_required")
    val compilation = requireNotNull(verified.compilation)
    val toolchain = compilation.toolchain
    refinementRequire(compilation.target == "jvm" && compilation.platform == "jvm" && toolchain?.kind == "jvm" &&
        toolchain?.compilerVersion == SEMANTIC_COMPILER_VERSION && request.target == compilation.target &&
        request.compilationId == compilation.id && request.snapshotId == model.snapshot.snapshotId &&
        request.sourceRoot == input.materializedRoot && request.leafSourceSet == compilation.defaultSourceSetId &&
        request.toolchainId == input.context.toolchainDigest && request.compilerArguments == input.context.compilerArguments,
        "coroutine_refinement_extraction_context_mismatch")
    refinementRequire(compilation.friendArtifacts.isEmpty() && compilation.auxiliaryInputFiles.isEmpty() &&
        compilation.auxiliaryArtifacts.isEmpty() && compilation.deferredJvmOutputs.isEmpty() &&
        compilation.projectDependencies.isEmpty(), "coroutine_refinement_extended_compilation_inputs_unsupported")
    refinementRequire(request.dependencyArtifacts == input.context.classpath &&
        request.dependencyArtifacts.map { it.path }.distinct().size == request.dependencyArtifacts.size,
        "coroutine_refinement_ordered_dependency_mismatch")
    api.callables.mapNotNull { it.dependencyArtifact }.distinct().forEach { artifact ->
        refinementRequire(request.dependencyArtifacts.singleOrNull { it.coordinate == artifact.coordinate } == artifact &&
            request.dependencyArtifacts.none { it.coordinate.substringBeforeLast(':') == artifact.coordinate.substringBeforeLast(':') &&
                it.coordinate != artifact.coordinate }, "coroutine_refinement_selected_dependency_mismatch")
    }
    fun identity(artifact: ModelArtifact): ArtifactIdentity {
        refinementRequire(artifact.digest?.let(Digests::valid) == true, "coroutine_refinement_toolchain_identity_missing")
        return ArtifactIdentity(artifact.coordinate, requireNotNull(artifact.digest), artifact.path)
    }
    val selectedToolchain = requireNotNull(toolchain)
    refinementRequire(request.compilerArtifacts == selectedToolchain.compilerArtifacts.map(::identity),
        "coroutine_refinement_compiler_artifact_mismatch")
    refinementRequire(selectedToolchain.compilerPlugins.all { identity(it) == request.pluginArtifact },
        "coroutine_refinement_additional_compiler_plugins_unsupported")
    val runtime = selectedToolchain.runtimeArtifacts.map(::identity)
    refinementRequire(runtime.isNotEmpty() && runtime.size <= 4096 && runtime.map { it.path }.distinct().size == runtime.size,
        "coroutine_refinement_jdk_image_selection_missing")
    val sourceSets = compilation.sourceSetIds.map { requireNotNull(model.sourceSet(it)) }
    refinementRequire(request.sourceSets == sourceSets.map { SemanticSourceSet(it.id, it.dependsOn) },
        "coroutine_refinement_source_graph_mismatch")
    val sources = compilation.sourceFiles.map { source ->
        refinementRequire(source.path.endsWith(".kt") && !source.path.endsWith(".kts"),
            "coroutine_refinement_non_kotlin_input_unsupported")
        val owners = sourceSets.filter { source in it.sources }
        refinementRequire(owners.size == 1, "coroutine_refinement_source_affiliation_ambiguous")
        SemanticSource(source.path, source.digest, source.size, owners.single().id)
    }
    refinementRequire(request.sources == sources && sources.sumOf { it.size } <= 8L * 1024 * 1024,
        "coroutine_refinement_source_inventory_mismatch_or_bound")
    val execution = input.execution?.let { it.copy(arguments = libraryList(it.arguments),
        environment = Collections.unmodifiableMap(it.environment.toMap())) }
    val frozen = input.copy(snapshot = model.snapshot, context = requireNotNull(verified.context),
        requiredRuleIds = libraryList(input.requiredRuleIds), changedPaths = libraryList(input.changedPaths), execution = execution)
    return CoroutineRefinementBinding(frozen, libraryList(runtime))
}

internal fun freezeRefinementAuthorization(model: VerifiedProjectModel,
                                           value: CoroutineRefinementAuthorizationRequest): CoroutineRefinementAuthorizationRequest {
    refinementAuthorizationBounds(value)
    refinementRequire(value.providerId == COROUTINE_REFINEMENT_PROVIDER_ID && value.providerVersion == COROUTINE_REFINEMENT_VERSION &&
        value.modelNamespace == model.namespace && value.modelCertificateId == model.ingressCertificate &&
        value.compilerExitCode == 0 && Digests.valid(value.factContentDigest),
        "coroutine_refinement_authorization_binding_invalid")
    val request = freezeSemanticRequest(value.semanticRequest)
    val api = freezeRefinementApplicability(value.applicability)
    val binding = refinementBinding(model, value.input, request, api)
    refinementRequire(binding.runtimeArtifacts == value.runtimeArtifacts, "coroutine_refinement_runtime_binding_mismatch")
    return value.copy(input = binding.input, semanticRequest = request, applicability = api,
        runtimeArtifacts = binding.runtimeArtifacts)
}

private fun refinementApplicabilityBounds(budget: LibraryTextBudget, value: CoroutineRefinementApplicability) {
    refinementRequire(value.callables.size <= 5 && value.blockingBridgeExemptions.size <= 256,
        "coroutine_refinement_input_bound_exceeded")
    budget.text(value.adapterId); budget.text(value.compilerVersion); budget.text(value.target)
    value.callables.forEach { budget.text(it.symbolId); it.dependencyArtifact?.let(budget::artifact) }
    value.blockingBridgeExemptions.forEach { budget.text(it.symbolId); budget.span(it.source) }
}

private fun refinementAuthorizationBounds(value: CoroutineRefinementAuthorizationRequest) {
    val budget = LibraryTextBudget()
    with(value.modelNamespace) { budget.text(ownerId); budget.text(workspaceId); budget.text(worktreeId); budget.text(lineageId) }
    budget.text(value.modelCertificateId); budget.text(value.factContentDigest)
    budget.text(value.providerId); budget.text(value.providerVersion)
    budget.input(value.input); budget.semanticRequest(value.semanticRequest)
    refinementApplicabilityBounds(budget, value.applicability)
    refinementRequire(value.runtimeArtifacts.size <= 4096, "coroutine_refinement_runtime_bound_exceeded")
    value.runtimeArtifacts.forEach(budget::artifact)
}

/** Same existing bounded vocabulary, with this slice's own selection metadata (not fake API DTOs). */
internal fun refinementFactBounds(request: SemanticFactsRequest, shards: List<SemanticFactShard>,
                                  api: CoroutineRefinementApplicability) {
    val budget = LibraryTextBudget()
    budget.semanticRequest(request); refinementApplicabilityBounds(budget, api)
    refinementRequire(shards.size <= 1024, "coroutine_refinement_fact_bound_exceeded")
    var records = 0L
    var typeNodes = 0
    fun type(value: TypeFact, depth: Int = 0) {
        refinementRequire(++typeNodes <= 100_000 && depth <= 24 && value.arguments.size <= 64 && value.components.size <= 64,
            "coroutine_refinement_type_bound_exceeded")
        budget.text(value.kind); budget.text(value.classifierId); budget.text(value.parameterOwner); budget.text(value.reason)
        value.arguments.forEach { budget.text(it.variance); it.type?.let { child -> type(child, depth + 1) } }
        value.components.forEach { type(it, depth + 1) }
    }
    fun parameters(values: List<SemanticParameter>) {
        refinementRequire(values.size <= 256, "coroutine_refinement_parameter_bound_exceeded")
        values.forEach { budget.text(it.name); type(it.type) }
    }
    fun strings(values: List<String>, maximum: Int) {
        refinementRequire(values.size <= maximum, "coroutine_refinement_fact_bound_exceeded"); values.forEach(budget::text)
    }
    shards.forEach { shard ->
        records += shard.declarations.size.toLong() + shard.symbols.size + shard.calls.size + shard.types.size +
            shard.expectActual.size + shard.unknown.size
        refinementRequire(records <= 100_000, "coroutine_refinement_fact_bound_exceeded")
        budget.text(shard.requestDigest); budget.text(shard.snapshotId); budget.text(shard.analysisId); budget.text(shard.toolchainId)
        budget.text(shard.pluginDigest); budget.text(shard.observedCompilerVersion); budget.text(shard.adapterId); budget.source(shard.input)
        budget.text(shard.compilerContext.moduleName); budget.text(shard.compilerContext.platform)
        strings(shard.compilerContext.dependsOnModules, 256); strings(shard.declarations, 100_000)
        shard.symbols.forEach {
            budget.text(it.id); budget.text(it.kind); budget.text(it.qualifiedName); budget.text(it.compilerModule); budget.text(it.origin)
            budget.span(it.source); it.returnType?.let { t -> type(t) }; it.receiverType?.let { t -> type(t) }
            it.dispatchReceiverType?.let { t -> type(t) }; it.aliasExpansion?.let { t -> type(t) }
            parameters(it.parameters); parameters(it.contextParameters); strings(it.flags, 64); strings(it.annotationClassIds, 256)
            refinementRequire(it.supertypes.size <= 64, "coroutine_refinement_fact_bound_exceeded")
            it.supertypes.forEach { t -> type(t) }
        }
        shard.calls.forEach {
            budget.span(it.source); budget.text(it.kind); budget.text(it.targetSymbolId); budget.text(it.containingSymbolId)
            type(it.resultType); it.dispatchReceiverType?.let { t -> type(t) }; it.extensionReceiverType?.let { t -> type(t) }
            refinementRequire(it.arguments.size <= 256, "coroutine_refinement_fact_bound_exceeded")
            it.arguments.forEach { a -> budget.span(a.source); budget.text(a.parameterName) }
        }
        shard.types.forEach { budget.span(it.source); type(it.type) }
        shard.expectActual.forEach {
            budget.text(it.actualSymbolId); budget.text(it.expectSymbolId); budget.text(it.matching)
            budget.text(it.compilerCompatibilityKind); budget.text(it.actualSourceSet); budget.text(it.expectSourceSet)
        }
        shard.unknown.forEach { budget.text(it.kind); budget.text(it.reason); budget.span(it.source) }
    }
}

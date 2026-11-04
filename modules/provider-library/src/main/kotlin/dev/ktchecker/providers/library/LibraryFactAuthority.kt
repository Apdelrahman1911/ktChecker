package dev.ktchecker.providers.library

import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.*
import dev.ktchecker.semantic.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

const val LIBRARY_PROVIDER_ID = "library-fir-direct"
const val LIBRARY_PROVIDER_VERSION = "runblocking-direct/1"
const val RUNBLOCKING_IN_SUSPEND = "ktc.coroutines.runblocking-in-suspend"
const val RUNBLOCKING_COROUTINES_VERSION = "1.11.0"
const val RUNBLOCKING_ARTIFACT = "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0"

/** A nonlocal symbol ID does not bind its body; the captured declaration span does. */
@Serializable
data class BlockingInteropExemption(val symbolId: String, val source: SemanticSpan)

/** Selection metadata only. Neither this DTO nor an artifact coordinate grants applicability. */
@Serializable
data class RunBlockingApplicability(
    val artifact: ArtifactIdentity,
    val selectedSymbolIds: List<String>,
    val blockingInteropExemptions: List<BlockingInteropExemption> = emptyList(),
    val libraryVersion: String = RUNBLOCKING_COROUTINES_VERSION,
    val adapterId: String = SEMANTIC_ADAPTER_ID,
    val compilerVersion: String = SEMANTIC_COMPILER_VERSION,
    val target: String = "jvm",
    val schemaVersion: Int = 1,
)

/** Raw compiler output is data, not extraction authorization or a qualified rule result. */
data class RunBlockingFactSelection(
    val request: SemanticFactsRequest,
    val shards: List<SemanticFactShard>,
    val compilerExitCode: Int?,
    val applicability: RunBlockingApplicability,
)

@Serializable
data class LibraryFactAuthorizationRequest(
    val modelNamespace: ModelNamespace,
    val modelCertificateId: String,
    val input: AnalysisInput,
    val semanticRequest: SemanticFactsRequest,
    val compilerExitCode: Int,
    val factContentDigest: String,
    val applicability: RunBlockingApplicability,
    val providerId: String = LIBRARY_PROVIDER_ID,
    val providerVersion: String = LIBRARY_PROVIDER_VERSION,
) {
    fun digest(): String {
        authorizationBounds(this)
        return Digests.sha256("ktchecker/library-fact-authorization/1\u0000" +
            Digests.canonical(ContractJson.encodeToJsonElement(this)))
    }
}

/** Only the authenticated host may supply this capability; serialized approval grants nothing. */
fun interface LibraryFactAuthority {
    fun authorize(request: LibraryFactAuthorizationRequest): LibraryFactPermit?
    companion object { val DENY = LibraryFactAuthority { null } }
}

/**
 * Single-use, nonserialized host capability. Every callback is mandatory; there are no fake
 * production receipts. They must check registered immutable owner evidence, not echo JSON,
 * infer execution from exit-code fields, or scan a live checkout before/after.
 *
 * verifyExtraction attests the actual successful, completed exact compiler/plugin invocation,
 * source inventory, options and frozen output identified by request. verifyCallableOwnership
 * must attest EACH selected binary symbol came from the selected coroutines artifact under the
 * exact ordered dependency vector. FIR module/origin/name/signature has no per-binary provenance
 * and is insufficient for this receipt. verifyInteropPolicy attests this exact owner-configured
 * exemption vector (including the empty vector), never project annotation/name heuristics.
 *
 * readSourceBytes supplies fresh bytes from the registered immutable snapshot. Nothing here
 * authorizes a launch, loads a compiler/plugin/project, or qualifies a release/provider tuple.
 * Like model ingress, this cooperative host API is not a same-UID sandbox security boundary.
 */
class LibraryFactPermit(
    private val model: VerifiedProjectModel,
    request: LibraryFactAuthorizationRequest,
    private val verifySnapshot: (LibraryFactAuthorizationRequest) -> Unit,
    private val verifyExtraction: (LibraryFactAuthorizationRequest) -> Unit,
    private val verifyArtifact: (ArtifactIdentity) -> Unit,
    private val verifyCallableOwnership: (String, ArtifactIdentity, LibraryFactAuthorizationRequest) -> Unit,
    private val verifyInteropPolicy: (LibraryFactAuthorizationRequest) -> Unit,
    private val readSourceBytes: (SemanticSource) -> ByteArray,
) {
    val request: LibraryFactAuthorizationRequest = freezeAuthorization(model, request)
    val requestDigest: String = this.request.digest()
    private val consumed = AtomicBoolean()

    internal fun verify(actualModel: VerifiedProjectModel, actual: LibraryFactAuthorizationRequest,
                        cancellation: CancellationToken) {
        cancellation.throwIfCancelled()
        if (actualModel !== model || actual.digest() != requestDigest || !consumed.compareAndSet(false, true)) {
            throw LibraryRuleProblem("library_fact_authorization_mismatch_or_replay")
        }
        verifySnapshot(request); cancellation.throwIfCancelled()
        verifyExtraction(request); cancellation.throwIfCancelled()
        val extraction = request.semanticRequest
        // Preserve order and reverify every selected identity; no pin/provenance cache.
        (extraction.compilerArtifacts + extraction.pluginArtifact + extraction.dependencyArtifacts).forEach {
            cancellation.throwIfCancelled(); verifyArtifact(it); cancellation.throwIfCancelled()
        }
        request.applicability.selectedSymbolIds.forEach {
            cancellation.throwIfCancelled()
            verifyCallableOwnership(it, request.applicability.artifact, request)
            cancellation.throwIfCancelled()
        }
        verifyInteropPolicy(request); cancellation.throwIfCancelled()
    }

    internal fun bytes(source: SemanticSource, cancellation: CancellationToken): ByteArray {
        cancellation.throwIfCancelled()
        val bytes = readSourceBytes(source)
        cancellation.throwIfCancelled()
        if (bytes.size.toLong() != source.size) throw LibraryRuleProblem("library_snapshot_source_size_mismatch")
        return bytes.copyOf()
    }
}

/** A previously issued typed permit is not reconstructed by deserializing its request. */
class ExactLibraryFactAuthority(private val permit: LibraryFactPermit) : LibraryFactAuthority {
    override fun authorize(request: LibraryFactAuthorizationRequest): LibraryFactPermit? =
        permit.takeIf { request.digest() == it.requestDigest }
}

internal class LibraryRuleProblem(val code: String) : IllegalArgumentException(code)
internal fun requireLibrary(valid: Boolean, code: String) { if (!valid) throw LibraryRuleProblem(code) }
internal fun <T> libraryList(values: Collection<T>): List<T> = Collections.unmodifiableList(values.toList())

internal fun freezeSemanticRequest(value: SemanticFactsRequest): SemanticFactsRequest {
    LibraryTextBudget().semanticRequest(value)
    requireLibrary(value.sources.size in 1..256 && value.sourceSets.size in 1..256 &&
        value.compilerArtifacts.size in 1..256 && value.dependencyArtifacts.size <= 4096 &&
        value.compilerArguments.size in 1..4096 && value.sourceSets.all { it.dependsOn.size <= 256 },
        "library_fact_input_bound_exceeded")
    val frozen = value.copy(compilerArtifacts = libraryList(value.compilerArtifacts),
        dependencyArtifacts = libraryList(value.dependencyArtifacts), sources = libraryList(value.sources),
        sourceSets = libraryList(value.sourceSets.map { it.copy(dependsOn = libraryList(it.dependsOn)) }),
        compilerArguments = libraryList(value.compilerArguments))
    requireLibrary(SemanticFactsValidation.requestProblems(frozen).isEmpty(), "library_semantic_request_invalid")
    return frozen
}

internal fun freezeApplicability(value: RunBlockingApplicability): RunBlockingApplicability {
    LibraryTextBudget().applicability(value)
    requireLibrary(value.schemaVersion == 1 && value.libraryVersion == RUNBLOCKING_COROUTINES_VERSION &&
        value.adapterId == SEMANTIC_ADAPTER_ID && value.compilerVersion == SEMANTIC_COMPILER_VERSION &&
        value.target == "jvm" && value.artifact.coordinate == RUNBLOCKING_ARTIFACT &&
        Digests.valid(value.artifact.digest) && value.artifact.path.length in 1..4096,
        "library_runblocking_applicability_unsupported")
    requireLibrary(value.selectedSymbolIds.size in 1..8 && value.selectedSymbolIds.all(Digests::valid) &&
        value.selectedSymbolIds.distinct().size == value.selectedSymbolIds.size &&
        value.blockingInteropExemptions.size <= 256 &&
        value.blockingInteropExemptions.all { Digests.valid(it.symbolId) && !it.source.synthetic } &&
        value.blockingInteropExemptions.distinct().size == value.blockingInteropExemptions.size,
        "library_runblocking_applicability_invalid")
    return value.copy(selectedSymbolIds = libraryList(value.selectedSymbolIds),
        blockingInteropExemptions = libraryList(value.blockingInteropExemptions))
}

/** Binding consumes only a verified model and exact immutable identities, never directory guesses. */
internal fun libraryBinding(model: VerifiedProjectModel, input: AnalysisInput, request: SemanticFactsRequest,
                            api: RunBlockingApplicability): Pair<AnalysisInput, ModelCompilation> {
    LibraryTextBudget().input(input)
    requireLibrary(input.snapshot == model.snapshot && input.context.configurationDigest == model.identity.configurationDigest,
        "library_model_snapshot_configuration_mismatch")
    val verified = model.context(input.context.scope, model.identity.configurationDigest)
    requireLibrary(verified.completeness == ModelCompleteness.COMPLETE && verified.certificateId != null &&
        verified.context == input.context, "library_verified_compilation_context_required")
    val compilation = requireNotNull(verified.compilation)
    // Named JVM only. An actual empty/desktop/other target identity is never normalized to jvm.
    requireLibrary(compilation.target == "jvm" && compilation.platform == "jvm" &&
        compilation.toolchain?.kind == "jvm" && compilation.toolchain?.compilerVersion == SEMANTIC_COMPILER_VERSION &&
        request.target == compilation.target && request.compilationId == compilation.id &&
        request.snapshotId == model.snapshot.snapshotId && request.sourceRoot == input.materializedRoot &&
        request.leafSourceSet == compilation.defaultSourceSetId &&
        request.toolchainId == input.context.toolchainDigest && request.compilerArguments == input.context.compilerArguments,
        "library_extraction_context_binding_mismatch")
    requireLibrary(request.dependencyArtifacts == input.context.classpath &&
        request.dependencyArtifacts.map { it.path }.distinct().size == request.dependencyArtifacts.size &&
        request.dependencyArtifacts.count { it.coordinate == RUNBLOCKING_ARTIFACT } == 1 &&
        api.artifact == request.dependencyArtifacts.single { it.coordinate == RUNBLOCKING_ARTIFACT } &&
        request.dependencyArtifacts.none { it.coordinate.startsWith("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:") &&
            it.coordinate != RUNBLOCKING_ARTIFACT },
        "library_ordered_dependency_binding_mismatch")
    fun artifact(value: ModelArtifact): ArtifactIdentity {
        requireLibrary(value.digest != null, "library_compiler_artifact_identity_missing")
        return ArtifactIdentity(value.coordinate, requireNotNull(value.digest), value.path)
    }
    requireLibrary(request.compilerArtifacts == requireNotNull(compilation.toolchain).compilerArtifacts.map(::artifact),
        "library_compiler_artifact_binding_mismatch")
    val sourceSets = compilation.sourceSetIds.map { requireNotNull(model.sourceSet(it)) }
    requireLibrary(request.sourceSets == sourceSets.map { SemanticSourceSet(it.id, it.dependsOn) },
        "library_source_set_graph_binding_mismatch")
    val actualSources = compilation.sourceFiles.map { source ->
        requireLibrary(source.path.endsWith(".kt") && !source.path.endsWith(".kts"),
            "library_non_kotlin_source_vocabulary_unsupported")
        val owners = sourceSets.filter { source in it.sources }
        requireLibrary(owners.size == 1, "library_source_affiliation_ambiguous")
        SemanticSource(source.path, source.digest, source.size, owners.single().id)
    }
    requireLibrary(request.sources == actualSources, "library_compilation_source_inventory_mismatch")
    val execution = input.execution?.let {
        requireLibrary(it.arguments.size <= 4096 && it.arguments.all { arg -> arg.length <= 16_384 } &&
            it.environment.size <= 256 && it.environment.all { entry ->
                entry.key.length <= 4096 && entry.value.length <= 16_384
            }, "library_analysis_input_bound_exceeded")
        it.copy(arguments = libraryList(it.arguments),
            environment = Collections.unmodifiableMap(it.environment.toMap()))
    }
    requireLibrary(input.materializedRoot.length in 1..4096 && (input.outputRoot?.length ?: 0) <= 4096 &&
        input.baselineSnapshotId?.let(Digests::valid) != false,
        "library_analysis_input_bound_exceeded")
    return input.copy(snapshot = model.snapshot, context = requireNotNull(verified.context),
        requiredRuleIds = libraryList(input.requiredRuleIds), changedPaths = libraryList(input.changedPaths),
        execution = execution) to compilation
}

internal fun freezeAuthorization(model: VerifiedProjectModel, value: LibraryFactAuthorizationRequest): LibraryFactAuthorizationRequest {
    authorizationBounds(value)
    requireLibrary(value.providerId == LIBRARY_PROVIDER_ID && value.providerVersion == LIBRARY_PROVIDER_VERSION &&
        value.modelNamespace == model.namespace && value.modelCertificateId == model.ingressCertificate &&
        value.compilerExitCode == 0 && Digests.valid(value.factContentDigest), "library_fact_authorization_binding_invalid")
    val request = freezeSemanticRequest(value.semanticRequest)
    val api = freezeApplicability(value.applicability)
    val input = libraryBinding(model, value.input, request, api).first
    return value.copy(input = input, semanticRequest = request, applicability = api)
}

/** Bound the entire authorization DTO, including unrelated captured files and execution metadata. */
internal fun authorizationBounds(value: LibraryFactAuthorizationRequest) {
    val budget = LibraryTextBudget()
    with(value.modelNamespace) { budget.text(ownerId); budget.text(workspaceId); budget.text(worktreeId); budget.text(lineageId) }
    budget.text(value.modelCertificateId); budget.text(value.factContentDigest)
    budget.text(value.providerId); budget.text(value.providerVersion)
    budget.input(value.input); budget.semanticRequest(value.semanticRequest); budget.applicability(value.applicability)
}

/** Six bytes per UTF-16 code unit bounds every JSON string escape before any serialization. */
internal class LibraryTextBudget {
    private var encodedTextBytes = 0L

    fun text(value: String?) {
        encodedTextBytes += if (value == null) 4L else 2L + 6L * value.length
        requireLibrary(encodedTextBytes <= 8L * 1024 * 1024, "library_fact_text_bound_exceeded")
    }

    private fun size(value: Int, maximum: Int) = requireLibrary(value <= maximum, "library_fact_input_bound_exceeded")
    private fun strings(values: Collection<String>, maximum: Int) { size(values.size, maximum); values.forEach(::text) }
    private fun entries(values: Map<String, String>) {
        size(values.size, 256)
        values.forEach { (key, value) -> text(key); text(value) }
    }

    fun artifact(value: ArtifactIdentity) { text(value.coordinate); text(value.digest); text(value.path) }
    fun source(value: SemanticSource) { text(value.path); text(value.digest); text(value.sourceSet) }
    fun span(value: SemanticSpan?) { value?.let { text(it.path); text(it.digest); text(it.sourceSet) } }

    fun input(value: AnalysisInput) {
        requireLibrary(value.requiredRuleIds.size <= 4096 && value.requiredRuleIds.all { it.length in 1..4096 } &&
            value.changedPaths.size <= 50_000 && value.changedPaths.all { it.length in 1..4096 } &&
            value.materializedRoot.length in 1..4096 && (value.outputRoot?.length ?: 0) <= 4096,
            "library_analysis_input_bound_exceeded")
        with(value.snapshot) {
            text(snapshotId); text(ownerId); text(workspaceId); text(worktreeId); text(parentSnapshotId); text(captureKind)
            size(files.size, 50_000); files.forEach { text(it.path); text(it.digest) }
            strings(declaredRoots, 256); strings(scopeExclusions, 4096); strings(unknownInputs, 4096)
        }
        text(value.materializedRoot); text(value.baselineSnapshotId); text(value.outputRoot)
        strings(value.requiredRuleIds, 4096); strings(value.changedPaths, 50_000)
        with(value.context) {
            with(scope) { text(target); text(sourceSet); text(module); text(compilation) }
            text(compilerVersion); text(modelDigest); text(dependencyDigest); text(toolchainDigest); text(configurationDigest)
            size(classpath.size, 4096); classpath.forEach(::artifact)
            strings(compilerArguments, 4096); entries(libraries); entries(policies)
            compilationFiles?.let { strings(it, 256) }
        }
        value.execution?.let {
            requireLibrary(it.executable.length in 1..4096 && it.arguments.size <= 4096 &&
                it.arguments.all { arg -> arg.length <= 16_384 } && it.environment.size <= 256 &&
                it.environment.all { entry -> entry.key.length <= 4096 && entry.value.length <= 16_384 },
                "library_analysis_input_bound_exceeded")
            text(it.executable); text(it.executableDigest); strings(it.arguments, 4096); entries(it.environment)
        }
    }

    fun semanticRequest(value: SemanticFactsRequest) {
        size(value.sources.size, 256); size(value.sourceSets.size, 256); size(value.compilerArtifacts.size, 256)
        size(value.dependencyArtifacts.size, 4096); size(value.compilerArguments.size, 4096)
        text(value.sourceRoot); text(value.snapshotId); text(value.analysisId); text(value.toolchainId)
        text(value.target); text(value.compilationId); text(value.leafSourceSet); text(value.adapterId); text(value.compilerVersion)
        value.sources.forEach(::source); value.compilerArtifacts.forEach(::artifact); artifact(value.pluginArtifact)
        value.dependencyArtifacts.forEach(::artifact)
        value.sourceSets.forEach { text(it.id); strings(it.dependsOn, 256) }
        value.compilerArguments.forEach(::text)
    }

    fun applicability(value: RunBlockingApplicability) {
        size(value.selectedSymbolIds.size, 8); size(value.blockingInteropExemptions.size, 256)
        text(value.libraryVersion); text(value.adapterId); text(value.compilerVersion); text(value.target)
        artifact(value.artifact); value.selectedSymbolIds.forEach(::text)
        value.blockingInteropExemptions.forEach { text(it.symbolId); span(it.source) }
    }
}

/**
 * Escape-safe aggregate bounds precede freezing/serialization/authorization. This internal slice
 * supports at most 256 Kotlin sources, 100k fact records, 100k type nodes and 8 MiB encoded identity
 * text per facts/authorization DTO. Fixed-size structural fields are separately count-bounded.
 * Larger/opaque inputs are unavailable, not negative evidence or a smaller compilation plan.
 */
internal fun factBounds(request: SemanticFactsRequest, shards: List<SemanticFactShard>, api: RunBlockingApplicability) {
    val budget = LibraryTextBudget()
    var records = 0L
    var typeNodes = 0
    fun text(value: String?) = budget.text(value)
    fun size(value: Int, maximum: Int) = requireLibrary(value <= maximum, "library_fact_input_bound_exceeded")
    fun span(value: SemanticSpan?) = budget.span(value)
    fun type(value: TypeFact, depth: Int = 0) {
        requireLibrary(++typeNodes <= 100_000 && depth <= 24, "library_fact_type_bound_exceeded")
        size(value.arguments.size, 64); size(value.components.size, 64)
        text(value.kind); text(value.classifierId); text(value.parameterOwner); text(value.reason)
        value.arguments.forEach { text(it.variance); it.type?.let { child -> type(child, depth + 1) } }
        value.components.forEach { type(it, depth + 1) }
    }
    fun parameters(values: List<SemanticParameter>) {
        size(values.size, 256); values.forEach { text(it.name); type(it.type) }
    }
    budget.semanticRequest(request); budget.applicability(api)
    size(shards.size, 1024)
    shards.forEach { shard ->
        records += shard.declarations.size.toLong() + shard.symbols.size + shard.calls.size +
            shard.types.size + shard.expectActual.size + shard.unknown.size
        requireLibrary(records <= 100_000, "library_fact_input_bound_exceeded")
        text(shard.requestDigest); text(shard.snapshotId); text(shard.analysisId); text(shard.toolchainId)
        text(shard.pluginDigest); text(shard.observedCompilerVersion); text(shard.adapterId); budget.source(shard.input)
        text(shard.compilerContext.moduleName); text(shard.compilerContext.platform)
        size(shard.compilerContext.dependsOnModules.size, 256); shard.compilerContext.dependsOnModules.forEach(::text)
        shard.declarations.forEach(::text)
        shard.symbols.forEach { symbol ->
            text(symbol.id); text(symbol.kind); text(symbol.qualifiedName); text(symbol.compilerModule); text(symbol.origin)
            span(symbol.source); symbol.returnType?.let { type(it) }; symbol.receiverType?.let { type(it) }
            symbol.dispatchReceiverType?.let { type(it) }; symbol.aliasExpansion?.let { type(it) }
            parameters(symbol.parameters); parameters(symbol.contextParameters)
            size(symbol.flags.size, 64); symbol.flags.forEach(::text)
            size(symbol.annotationClassIds.size, 256); symbol.annotationClassIds.forEach(::text)
            size(symbol.supertypes.size, 64); symbol.supertypes.forEach { type(it) }
        }
        shard.calls.forEach { call ->
            span(call.source); text(call.kind); text(call.targetSymbolId); text(call.containingSymbolId); type(call.resultType)
            call.dispatchReceiverType?.let { type(it) }; call.extensionReceiverType?.let { type(it) }
            size(call.arguments.size, 256)
            call.arguments.forEach { span(it.source); text(it.parameterName) }
        }
        shard.types.forEach { span(it.source); type(it.type) }
        shard.expectActual.forEach {
            text(it.actualSymbolId); text(it.expectSymbolId); text(it.matching); text(it.compilerCompatibilityKind)
            text(it.actualSourceSet); text(it.expectSourceSet)
        }
        shard.unknown.forEach { text(it.kind); text(it.reason); span(it.source) }
    }
}

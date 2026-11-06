package dev.ktchecker.providers.library

import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.*
import dev.ktchecker.semantic.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

const val EMPTY_ASSERT_FAILS_WITH = "ktc.testing.empty_assert_fails_with"
const val EMPTY_ASSERTION_PROVIDER_ID = "library-empty-assertion"
const val EMPTY_ASSERTION_PROVIDER_VERSION = "direct-empty-reified/1"
const val EMPTY_ASSERTION_KOTLIN_TEST = "org.jetbrains.kotlin:kotlin-test:2.4.21"

@Serializable
enum class EmptyAssertionTestDisposition { REQUIRED_TO_PASS, EXPECTED_FAILURE, CALIBRATION, SUPPRESSED }

/**
 * Explicit owner test policy, NOT discovery by directory, @Test spelling or a serialized claim.
 * The declaration span binds the body even when its nonlocal symbol ID is unchanged by an edit.
 * REQUIRED_TO_PASS means a genuine test entry must return normally under its actual configured
 * runner. Expected-failure handlers, calibration scopes, disabled/unreachable entries and exact
 * declaration/file suppressions must not receive that disposition. Missing entries are unknown.
 */
@Serializable
data class EmptyAssertionTestEntry(
    val symbolId: String,
    val source: SemanticSpan,
    val disposition: EmptyAssertionTestDisposition,
)

/** Selection DATA only; neither a coordinate nor the policy vector grants authority. */
@Serializable
data class EmptyAssertionApplicability(
    val artifact: ArtifactIdentity,
    val selectedSymbolIds: List<String>,
    val testEntries: List<EmptyAssertionTestEntry>,
    val adapterId: String = SEMANTIC_ADAPTER_ID,
    val compilerVersion: String = SEMANTIC_COMPILER_VERSION,
    val target: String = "jvm",
    val schemaVersion: Int = 1,
)

data class EmptyAssertionFactSelection(
    val request: SemanticFactsRequest,
    val shards: List<SemanticFactShard>,
    val compilerExitCode: Int?,
    val applicability: EmptyAssertionApplicability,
)

@Serializable
data class EmptyAssertionAuthorizationRequest(
    val modelNamespace: ModelNamespace,
    val modelCertificateId: String,
    val input: AnalysisInput,
    val semanticRequest: SemanticFactsRequest,
    val runtimeArtifacts: List<ArtifactIdentity>,
    val compilerExitCode: Int,
    val factContentDigest: String,
    val applicability: EmptyAssertionApplicability,
    val providerId: String = EMPTY_ASSERTION_PROVIDER_ID,
    val providerVersion: String = EMPTY_ASSERTION_PROVIDER_VERSION,
) {
    fun digest(): String {
        emptyAssertionAuthorizationBounds(this)
        return Digests.sha256("ktchecker/empty-assertion-authorization/1\u0000" +
            Digests.canonical(ContractJson.encodeToJsonElement(this)))
    }
}

fun interface EmptyAssertionAuthority {
    fun authorize(request: EmptyAssertionAuthorizationRequest): EmptyAssertionPermit?
    companion object { val DENY = EmptyAssertionAuthority { null } }
}

/**
 * One-use NONSERIALIZED host capability, like the existing library-rule authority. Production
 * callbacks must check the owner's registered immutable evidence, not echo a DTO, claim a launch
 * from an exit-code field or scan a live checkout before/after. verifyExtraction checks the exact
 * successfully completed compiler/plugin invocation and frozen request/output. Every selected
 * callable needs genuine supplying kotlin-test binary ownership under the exact ordered classpath;
 * FIR names/module/origin or a matching filename are insufficient.
 *
 * verifyTestPolicy checks THIS explicit owner-configured test-entry/disposition vector (including
 * empty) against the configuration identity admitted at model ingress, actual test-source/runner
 * membership, normal-return requirements and exclusions. It is the existing owner-policy pattern,
 * not a new runner-launch API or an assertion that this rule found a bug. It must never designate a
 * meta-test, caught expected failure, approved calibration, ignored entry or suppressed declaration
 * as REQUIRED_TO_PASS. The provider independently checks the complete direct-body shape.
 *
 * This cooperative host interface neither launches code nor qualifies a provider/rule tuple.
 */
class EmptyAssertionPermit(
    private val model: VerifiedProjectModel,
    request: EmptyAssertionAuthorizationRequest,
    private val verifySnapshot: (EmptyAssertionAuthorizationRequest) -> Unit,
    private val verifyExtraction: (EmptyAssertionAuthorizationRequest) -> Unit,
    private val verifyArtifact: (ArtifactIdentity) -> Unit,
    private val verifyCallableOwnership: (String, ArtifactIdentity, EmptyAssertionAuthorizationRequest) -> Unit,
    private val verifyTestPolicy: (EmptyAssertionAuthorizationRequest) -> Unit,
    private val readSourceBytes: (SemanticSource) -> ByteArray,
) {
    val request: EmptyAssertionAuthorizationRequest = freezeEmptyAssertionAuthorization(model, request)
    val requestDigest: String = this.request.digest()
    private val consumed = AtomicBoolean()

    internal fun verify(actualModel: VerifiedProjectModel, actual: EmptyAssertionAuthorizationRequest,
                        cancellation: CancellationToken) {
        cancellation.throwIfCancelled()
        emptyAssertionRequire(actualModel === model && actual.digest() == requestDigest &&
            consumed.compareAndSet(false, true), "empty_assertion_authorization_mismatch_or_replay")
        verifySnapshot(request); cancellation.throwIfCancelled()
        verifyExtraction(request); cancellation.throwIfCancelled()
        val extraction = request.semanticRequest
        (extraction.compilerArtifacts + extraction.pluginArtifact + extraction.dependencyArtifacts +
            request.runtimeArtifacts).forEach {
            cancellation.throwIfCancelled(); verifyArtifact(it); cancellation.throwIfCancelled()
        }
        request.applicability.selectedSymbolIds.forEach {
            verifyCallableOwnership(it, request.applicability.artifact, request); cancellation.throwIfCancelled()
        }
        verifyTestPolicy(request); cancellation.throwIfCancelled()
    }

    internal fun bytes(source: SemanticSource, cancellation: CancellationToken): ByteArray {
        cancellation.throwIfCancelled()
        val bytes = readSourceBytes(source).copyOf()
        cancellation.throwIfCancelled()
        emptyAssertionRequire(bytes.size.toLong() == source.size && Digests.sha256(bytes) == source.digest,
            "empty_assertion_snapshot_source_mismatch")
        return bytes
    }
}

class ExactEmptyAssertionAuthority(private val permit: EmptyAssertionPermit) : EmptyAssertionAuthority {
    override fun authorize(request: EmptyAssertionAuthorizationRequest): EmptyAssertionPermit? =
        permit.takeIf { it.requestDigest == request.digest() }
}

internal class EmptyAssertionProblem(val code: String) : IllegalArgumentException(code)
internal fun emptyAssertionRequire(valid: Boolean, code: String) { if (!valid) throw EmptyAssertionProblem(code) }

internal fun freezeEmptyAssertionApplicability(value: EmptyAssertionApplicability): EmptyAssertionApplicability {
    emptyAssertionApplicabilityBounds(LibraryTextBudget(), value)
    emptyAssertionRequire(value.schemaVersion == 1 && value.adapterId == SEMANTIC_ADAPTER_ID &&
        value.compilerVersion == SEMANTIC_COMPILER_VERSION && value.target == "jvm" &&
        value.artifact.coordinate == EMPTY_ASSERTION_KOTLIN_TEST && Digests.valid(value.artifact.digest) &&
        value.artifact.path.length in 1..4096, "empty_assertion_applicability_unsupported")
    emptyAssertionRequire(value.selectedSymbolIds.all(Digests::valid) &&
        value.selectedSymbolIds.distinct().size == value.selectedSymbolIds.size &&
        value.testEntries.all { Digests.valid(it.symbolId) && !it.source.synthetic } &&
        value.testEntries.map { it.symbolId to it.source }.distinct().size == value.testEntries.size,
        "empty_assertion_selection_invalid")
    return value.copy(selectedSymbolIds = libraryList(value.selectedSymbolIds), testEntries = libraryList(value.testEntries))
}

internal data class EmptyAssertionBinding(val input: AnalysisInput, val runtimeArtifacts: List<ArtifactIdentity>)

/** Reuse actual verified compilation membership/options; never infer test status from its name. */
internal fun emptyAssertionBinding(model: VerifiedProjectModel, input: AnalysisInput, request: SemanticFactsRequest,
                                   api: EmptyAssertionApplicability): EmptyAssertionBinding {
    LibraryTextBudget().input(input)
    emptyAssertionRequire(input.snapshot == model.snapshot && input.context.configurationDigest == model.identity.configurationDigest &&
        input.snapshot.unknownInputs.isEmpty() && input.snapshot.scopeExclusions.isEmpty(),
        "empty_assertion_model_snapshot_configuration_mismatch")
    val verified = model.context(input.context.scope, model.identity.configurationDigest)
    emptyAssertionRequire(verified.completeness == ModelCompleteness.COMPLETE && verified.certificateId != null &&
        verified.context == input.context, "empty_assertion_verified_compilation_required")
    val compilation = requireNotNull(verified.compilation)
    val toolchain = compilation.toolchain
    emptyAssertionRequire(compilation.target == "jvm" && compilation.platform == "jvm" && toolchain?.kind == "jvm" &&
        toolchain.compilerVersion == SEMANTIC_COMPILER_VERSION && request.target == compilation.target &&
        request.compilationId == compilation.id && request.snapshotId == model.snapshot.snapshotId &&
        request.sourceRoot == input.materializedRoot && request.leafSourceSet == compilation.defaultSourceSetId &&
        request.toolchainId == input.context.toolchainDigest && request.compilerArguments == input.context.compilerArguments,
        "empty_assertion_extraction_context_mismatch")
    emptyAssertionRequire(compilation.friendArtifacts.isEmpty() && compilation.auxiliaryInputFiles.isEmpty() &&
        compilation.auxiliaryArtifacts.isEmpty() && compilation.deferredJvmOutputs.isEmpty() &&
        compilation.projectDependencies.isEmpty(), "empty_assertion_extended_compilation_inputs_unsupported")
    emptyAssertionRequire(request.dependencyArtifacts == input.context.classpath &&
        request.dependencyArtifacts.map { it.path }.distinct().size == request.dependencyArtifacts.size &&
        request.dependencyArtifacts.singleOrNull { it.coordinate == EMPTY_ASSERTION_KOTLIN_TEST } == api.artifact &&
        request.dependencyArtifacts.none { it.coordinate.startsWith("org.jetbrains.kotlin:kotlin-test:") &&
            it.coordinate != EMPTY_ASSERTION_KOTLIN_TEST }, "empty_assertion_ordered_dependency_mismatch")
    fun identity(value: ModelArtifact): ArtifactIdentity {
        emptyAssertionRequire(value.digest?.let(Digests::valid) == true, "empty_assertion_toolchain_identity_missing")
        return ArtifactIdentity(value.coordinate, requireNotNull(value.digest), value.path)
    }
    val selectedToolchain = requireNotNull(toolchain)
    emptyAssertionRequire(request.compilerArtifacts == selectedToolchain.compilerArtifacts.map(::identity) &&
        selectedToolchain.compilerPlugins.all { identity(it) == request.pluginArtifact },
        "empty_assertion_compiler_plugin_binding_mismatch")
    val runtime = selectedToolchain.runtimeArtifacts.map(::identity)
    emptyAssertionRequire(runtime.isNotEmpty() && runtime.size <= 4096 && runtime.map { it.path }.distinct().size == runtime.size,
        "empty_assertion_runtime_selection_missing")
    val sourceSets = compilation.sourceSetIds.map { requireNotNull(model.sourceSet(it)) }
    emptyAssertionRequire(request.sourceSets == sourceSets.map { SemanticSourceSet(it.id, it.dependsOn) },
        "empty_assertion_source_graph_mismatch")
    val sources = compilation.sourceFiles.map { source ->
        emptyAssertionRequire(source.path.endsWith(".kt") && !source.path.endsWith(".kts"),
            "empty_assertion_non_kotlin_source_unsupported")
        val owners = sourceSets.filter { source in it.sources }
        emptyAssertionRequire(owners.size == 1, "empty_assertion_source_affiliation_ambiguous")
        SemanticSource(source.path, source.digest, source.size, owners.single().id)
    }
    emptyAssertionRequire(request.sources == sources && sources.sumOf { it.size } <= 8L * 1024 * 1024,
        "empty_assertion_source_inventory_mismatch_or_bound")
    val execution = input.execution?.let { it.copy(arguments = libraryList(it.arguments),
        environment = Collections.unmodifiableMap(it.environment.toMap())) }
    return EmptyAssertionBinding(input.copy(snapshot = model.snapshot, context = requireNotNull(verified.context),
        requiredRuleIds = libraryList(input.requiredRuleIds), changedPaths = libraryList(input.changedPaths), execution = execution),
        libraryList(runtime))
}

internal fun freezeEmptyAssertionAuthorization(model: VerifiedProjectModel,
                                               value: EmptyAssertionAuthorizationRequest): EmptyAssertionAuthorizationRequest {
    emptyAssertionAuthorizationBounds(value)
    emptyAssertionRequire(value.providerId == EMPTY_ASSERTION_PROVIDER_ID && value.providerVersion == EMPTY_ASSERTION_PROVIDER_VERSION &&
        value.modelNamespace == model.namespace && value.modelCertificateId == model.ingressCertificate &&
        value.compilerExitCode == 0 && Digests.valid(value.factContentDigest), "empty_assertion_authorization_binding_invalid")
    val request = freezeSemanticRequest(value.semanticRequest)
    val api = freezeEmptyAssertionApplicability(value.applicability)
    val binding = emptyAssertionBinding(model, value.input, request, api)
    emptyAssertionRequire(binding.runtimeArtifacts == value.runtimeArtifacts, "empty_assertion_runtime_binding_mismatch")
    return value.copy(input = binding.input, semanticRequest = request, applicability = api, runtimeArtifacts = binding.runtimeArtifacts)
}

private fun emptyAssertionApplicabilityBounds(budget: LibraryTextBudget, value: EmptyAssertionApplicability) {
    emptyAssertionRequire(value.selectedSymbolIds.size <= 8 && value.testEntries.size <= 256,
        "empty_assertion_selection_bound_exceeded")
    budget.artifact(value.artifact); budget.text(value.adapterId); budget.text(value.compilerVersion); budget.text(value.target)
    value.selectedSymbolIds.forEach(budget::text)
    value.testEntries.forEach { budget.text(it.symbolId); budget.span(it.source) }
}

private fun emptyAssertionAuthorizationBounds(value: EmptyAssertionAuthorizationRequest) {
    val budget = LibraryTextBudget()
    with(value.modelNamespace) { budget.text(ownerId); budget.text(workspaceId); budget.text(worktreeId); budget.text(lineageId) }
    budget.text(value.modelCertificateId); budget.text(value.factContentDigest); budget.text(value.providerId); budget.text(value.providerVersion)
    budget.input(value.input); budget.semanticRequest(value.semanticRequest); emptyAssertionApplicabilityBounds(budget, value.applicability)
    emptyAssertionRequire(value.runtimeArtifacts.size <= 4096, "empty_assertion_runtime_bound_exceeded")
    value.runtimeArtifacts.forEach(budget::artifact)
}

/** Bound the existing vocabulary before freezing/digesting it; no coroutine-shaped fake API DTO. */
internal fun emptyAssertionFactBounds(request: SemanticFactsRequest, shards: List<SemanticFactShard>, api: EmptyAssertionApplicability) {
    val budget = LibraryTextBudget()
    budget.semanticRequest(request); emptyAssertionApplicabilityBounds(budget, api)
    emptyAssertionRequire(shards.size <= 1024, "empty_assertion_fact_bound_exceeded")
    var records = 0L
    var typeNodes = 0
    fun strings(values: List<String>, maximum: Int) {
        emptyAssertionRequire(values.size <= maximum, "empty_assertion_fact_bound_exceeded"); values.forEach(budget::text)
    }
    fun type(value: TypeFact, depth: Int = 0) {
        emptyAssertionRequire(++typeNodes <= 100_000 && depth <= 24 && value.arguments.size <= 64 && value.components.size <= 64,
            "empty_assertion_type_bound_exceeded")
        budget.text(value.kind); budget.text(value.classifierId); budget.text(value.parameterOwner); budget.text(value.reason)
        value.arguments.forEach { budget.text(it.variance); it.type?.let { t -> type(t, depth + 1) } }
        value.components.forEach { type(it, depth + 1) }
    }
    fun parameters(values: List<SemanticParameter>) {
        emptyAssertionRequire(values.size <= 256, "empty_assertion_fact_bound_exceeded")
        values.forEach { budget.text(it.name); type(it.type) }
    }
    shards.forEach { shard ->
        records += shard.declarations.size.toLong() + shard.symbols.size + shard.calls.size + shard.types.size +
            shard.expectActual.size + shard.unknown.size
        emptyAssertionRequire(records <= 100_000, "empty_assertion_fact_bound_exceeded")
        budget.text(shard.requestDigest); budget.text(shard.snapshotId); budget.text(shard.analysisId); budget.text(shard.toolchainId)
        budget.text(shard.pluginDigest); budget.text(shard.observedCompilerVersion); budget.text(shard.adapterId); budget.source(shard.input)
        budget.text(shard.compilerContext.moduleName); budget.text(shard.compilerContext.platform)
        strings(shard.compilerContext.dependsOnModules, 256); strings(shard.declarations, 100_000)
        shard.symbols.forEach {
            budget.text(it.id); budget.text(it.kind); budget.text(it.qualifiedName); budget.text(it.compilerModule); budget.text(it.origin)
            budget.span(it.source); it.returnType?.let { t -> type(t) }; it.receiverType?.let { t -> type(t) }
            it.dispatchReceiverType?.let { t -> type(t) }; it.aliasExpansion?.let { t -> type(t) }
            parameters(it.parameters); parameters(it.contextParameters); strings(it.flags, 64); strings(it.annotationClassIds, 256)
            emptyAssertionRequire(it.supertypes.size <= 64, "empty_assertion_fact_bound_exceeded"); it.supertypes.forEach { t -> type(t) }
        }
        shard.calls.forEach {
            budget.span(it.source); budget.text(it.kind); budget.text(it.targetSymbolId); budget.text(it.containingSymbolId)
            type(it.resultType); it.dispatchReceiverType?.let { t -> type(t) }; it.extensionReceiverType?.let { t -> type(t) }
            emptyAssertionRequire(it.arguments.size <= 256, "empty_assertion_fact_bound_exceeded")
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

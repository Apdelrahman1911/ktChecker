package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.encodeToJsonElement

const val OBSERVED_EXPORT_PROFILE = "ktchecker/observed-export-one-shot/1"
const val CONFIGURATION_INPUT_CLOSURE_UNPROVEN = "configuration_input_closure_unproven"
const val OBSERVED_ASSURANCE_POLICY = "ktchecker.model-assurance"
const val OBSERVED_MODEL_EVIDENCE_POLICY = "ktchecker.observed-model-evidence"
const val OBSERVED_PLAN_EVIDENCE_POLICY = "ktchecker.observed-export-evidence"
const val OBSERVED_INVOCATION_POLICY = "ktchecker.observed-export-invocation"
const val OBSERVED_CLOSURE_POLICY = "ktchecker.observed-export-closure"

internal fun observedClosureIssue() = ModelIssue(CONFIGURATION_INPUT_CLOSURE_UNPROVEN, null,
    "The exact owner-observed export does not prove the complete Gradle configuration read/input closure; no completeness or reuse is authorized.")

/**
 * Allocate BEFORE the actual one-shot export. This is an owner object, not a decoded request
 * field or permission by itself. The original deadline is supplied, NEVER started/refreshed here.
 * The owner's execution callback must prove this exact identity was used by the actual launch.
 */
class ObservedExportInvocation(val originalDeadline: OwnedApplicationDeadline) {
    val identity: String = UUID.randomUUID().toString().replace("-", "")
    /** Documentary scalars only; callers granting execution must still retain the SAME clock object. */
    val originalDeadlineIdentity: String get() = originalDeadline.identity
    val originalDeadlineMillis: Long get() = originalDeadline.durationMillis
    private val permit = AtomicReference<ObservedExportPermit?>()
    private val consumed = AtomicBoolean()

    fun verifyLive() {
        if (originalDeadline.isExpired()) throw ProjectModelException("observed_export_original_deadline_expired",
            "The original owner export/review budget expired; no replacement clock is granted.")
        if (Thread.currentThread().isInterrupted) throw ProjectModelException("observed_export_interrupted",
            "The original observed export/review was interrupted.")
    }

    internal fun enroll(value: ObservedExportPermit) {
        verifyLive()
        if (value.request.executionId != identity || !permit.compareAndSet(null, value)) {
            throw ProjectModelException("observed_export_invocation_mismatch_or_replay",
                "One prelaunch observed invocation may enroll exactly one owner permit.")
        }
    }

    internal fun consume(value: ObservedExportPermit) {
        verifyLive()
        if (permit.get() !== value || !consumed.compareAndSet(false, true)) {
            throw ProjectModelException("observed_export_invocation_mismatch_or_replay",
                "The exact prelaunch observed invocation is single-use, including failed imports.")
        }
    }
}

/**
 * Trusted local owner capability for conditional diagnostics ONLY. In contrast to
 * ModelIngressPermit, verifyObservedInputs does NOT attest complete configuration/input closure.
 * It MUST attest the whole registered source snapshot and independent actual task/source-set,
 * ordered compiler/library/argv/JDK/association/output observations from the SAME successful,
 * quiescent, pinned Gradle/exporter invocation. Reports/path scans alone cannot enroll authority.
 * Every artifact must already be in owner custody; the report cannot choose an allowlist.
 * observedEvidenceDigest joins those actual receipts; a digest alone is never a grant.
 */
class ObservedExportPermit(
    request: ModelImportRequest,
    val invocation: ObservedExportInvocation,
    val gradleRuntimeDigest: String,
    val observedEvidenceDigest: String,
    val verifySnapshot: () -> Unit,
    val verifyExecutedExport: () -> Unit,
    val verifyArtifact: (ModelArtifact) -> Unit,
    val verifyObservedInputs: (GradleProjectExport) -> Unit,
    val verifyJvmArgumentOrigin: (ModelCompilation) -> ModelJvmArgumentOrigin? = { null },
    val verifyJvmProducedOutputOrigin: (ModelCompilation) -> ModelJvmProducedOutputOrigin? = { null },
) {
    val request: ModelImportRequest = freezeRequest(request)
    val requestDigest: String = this.request.digest()
    init {
        require(Digests.valid(gradleRuntimeDigest) && Digests.valid(observedEvidenceDigest))
        invocation.enroll(this)
    }
    internal fun consume(actual: ModelImportRequest) {
        if (actual.digest() != requestDigest || actual.executionId != invocation.identity) {
            throw ProjectModelException("observed_export_ingress_mismatch", "The observed permit belongs to another exact export request.")
        }
        invocation.consume(this)
    }
}

fun interface ObservedExportAuthority {
    fun authorize(request: ModelImportRequest): ObservedExportPermit?
    companion object { val DENY = ObservedExportAuthority { null } }
}

class ExactObservedExportAuthority(private val permit: ObservedExportPermit) : ObservedExportAuthority {
    override fun authorize(request: ModelImportRequest): ObservedExportPermit? =
        permit.takeIf { request.digest() == it.requestDigest && request.executionId == it.invocation.identity }
}

/** No COMPLETE state/certificate is representable for an observed-only compilation. */
class ObservedCompilationContext internal constructor(
    val compilation: ModelCompilation?, val context: AnalysisContext?, issues: List<ModelIssue>,
) {
    val issues: List<ModelIssue> = immutable(issues)
    val completeness: ModelCompleteness get() = if (context == null) ModelCompleteness.UNSUPPORTED else ModelCompleteness.UNAVAILABLE
    val certificateId: String? get() = null
    init {
        require(context?.fullProject != true && this.issues.contains(observedClosureIssue()))
    }
}

/**
 * Nominal sibling, NOT a subtype/wrapper/conversion to VerifiedProjectModel. Its immutable
 * evidence/contexts permanently retain the unproven closure even after a successful compiler.
 * The live invocation is single-use authority; exported metadata/history cannot recreate it.
 */
class ObservedProjectModel internal constructor(
    val namespace: ModelNamespace,
    val snapshot: SnapshotManifest,
    val export: GradleProjectExport,
    val identity: ProjectModelIdentity,
    issues: List<ModelIssue>,
    val invocation: ObservedExportInvocation,
    val observedEvidenceDigest: String,
    val evidenceIdentity: String,
    jvmArgumentLayouts: Map<String, VerifiedJvmArgumentLayout>,
    jvmArgumentRejections: Map<String, String>,
    val jvmProducedGraph: ObservedJvmProducedGraph?,
) {
    val assuranceProfile: String get() = OBSERVED_EXPORT_PROFILE
    val issues: List<ModelIssue> = immutable(issues)
    val compilationIds: List<String> = immutable(export.compilations.map { it.id })
    private val layouts = immutableMap(jvmArgumentLayouts)
    val jvmArgumentRejections = immutableMap(jvmArgumentRejections)
    init {
        require(this.issues.contains(observedClosureIssue()) && Digests.valid(observedEvidenceDigest) && Digests.valid(evidenceIdentity))
    }
    fun compilation(id: String): ModelCompilation? = export.compilations.singleOrNull { it.id == id }
    fun sourceSet(id: String): ModelSourceSet? = export.sourceSets.singleOrNull { it.id == id }
    fun jvmArgumentLayout(compilationId: String): VerifiedJvmArgumentLayout? = layouts[compilationId]
    fun context(compilationId: String, configurationDigest: String): ObservedCompilationContext {
        val compilation = compilation(compilationId) ?: return ObservedCompilationContext(null, null,
            issues + ModelIssue("compilation_context_unknown", compilationId, "The actual observed compilation is absent; it is not silently retired."))
        val set = requireNotNull(sourceSet(compilation.defaultSourceSetId))
        return context(RuleContext(compilation.target, set.name, compilation.moduleId, compilation.name), configurationDigest)
    }
    fun context(scope: RuleContext, configurationDigest: String): ObservedCompilationContext {
        digest(configurationDigest)
        if (configurationDigest != identity.configurationDigest) throw ProjectModelException("model_configuration_mismatch",
            "The observed compilation retains the exact owner policy captured before export.")
        val compilation = export.compilations.singleOrNull { it.moduleId == scope.module && it.target == scope.target &&
            it.name == scope.compilation && sourceSet(it.defaultSourceSetId)?.name == scope.sourceSet }
            ?: return ObservedCompilationContext(null, null, issues + ModelIssue("compilation_context_unknown", null,
                "No unique actual observed compilation matches the requested context."))
        val chain = compilation.toolchain
        val dependency = canonicalDigest(ContractJson.encodeToJsonElement(mapOf("classpath" to compilation.classpath,
            "friends" to compilation.friendArtifacts, "auxiliary" to compilation.auxiliaryArtifacts)))
        val toolchain = chain?.takeIf { (it.compilerArtifacts + it.runtimeArtifacts + it.compilerPlugins).all { a -> a.digest != null } }
            ?.let { canonicalDigest(ContractJson.encodeToJsonElement(it)) }
        val context = AnalysisContext(scope, chain?.compilerVersion,
            immutable(compilation.classpath.mapNotNull { a -> a.digest?.let { ArtifactIdentity(a.coordinate, it, a.path) } }),
            immutable(compilation.compilerArguments), policies = immutableMap(mapOf(
                "ktchecker.model" to GRADLE_MODEL_EXPORTER_ID,
                OBSERVED_ASSURANCE_POLICY to OBSERVED_EXPORT_PROFILE,
                OBSERVED_MODEL_EVIDENCE_POLICY to evidenceIdentity,
                OBSERVED_INVOCATION_POLICY to invocation.identity,
                OBSERVED_CLOSURE_POLICY to CONFIGURATION_INPUT_CLOSURE_UNPROVEN)),
            modelDigest = identity.modelDigest, dependencyDigest = dependency, toolchainDigest = toolchain,
            configurationDigest = configurationDigest, fullProject = false,
            compilationFiles = immutable(compilation.sourceFiles.map { it.path }))
        return ObservedCompilationContext(compilation, context, issues)
    }
}

/** Separate observed-only factory. No ModelIngressPermit or VerifiedProjectModel is created. */
class ObservedProjectModelImporter(private val authority: ObservedExportAuthority = ObservedExportAuthority.DENY) {
    fun import(request: ModelImportRequest, rawExport: ByteArray): ObservedProjectModel {
        val input = prepareModelImportBytes(request, rawExport)
        val permit = authority.authorize(input.request) ?: throw ProjectModelException("observed_export_not_authorized",
            "An exact independently observed one-shot owner export is required.")
        permit.consume(input.request)
        val facts = readModelImportFacts(input, ModelImportOrigin.Observed(permit))
        permit.invocation.verifyLive()
        val issues = immutable((facts.issues + observedClosureIssue()).distinct().sortedWith(
            compareBy<ModelIssue> { it.scopeId.orEmpty() }.thenBy { it.kind }.thenBy { it.detail }))
        val evidence = canonicalDigest(ContractJson.encodeToJsonElement(listOf(OBSERVED_EXPORT_PROFILE,
            input.request.digest(), permit.gradleRuntimeDigest, permit.observedEvidenceDigest,
            permit.invocation.identity, permit.invocation.originalDeadline.identity,
            CONFIGURATION_INPUT_CLOSURE_UNPROVEN, facts.contentEvidenceDigest)))
        val modelIdentity = facts.identity.copy(modelDigest = canonicalDigest(ContractJson.encodeToJsonElement(listOf(
            OBSERVED_EXPORT_PROFILE, facts.identity.modelDigest, evidence))))
        return ObservedProjectModel(input.request.namespace, input.request.snapshot, facts.graph, modelIdentity, issues,
            permit.invocation, permit.observedEvidenceDigest, evidence, facts.roleLayouts, facts.roleRejections,
            observedJvmProducedGraph(input.request, facts.graph, modelIdentity.modelDigest, evidence, issues,
                facts.roleLayouts, facts.producedOrigins))
    }
}

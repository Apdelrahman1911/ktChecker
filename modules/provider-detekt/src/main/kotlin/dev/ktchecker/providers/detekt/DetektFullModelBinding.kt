package dev.ktchecker.providers.detekt

import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.*
import dev.ktchecker.providers.runtime.*
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*

/**
 * Independent, live OWNER facts for the 2.4.10 experiment, not a decoded argument-role report.
 * The owner must retain the SAME strict importer object, registered source root, selected compile
 * task and original compiler destination from its capture/export lifecycle. Do not derive these
 * paths by parsing a report or copy a 2.4.21 original-roles/observed-model capability into this API.
 * verifyOrigin rechecks that retained origin, not mutable-worktree recapture or JSON self-attestation.
 */
class DetektFullModelOrigin(
    val model: VerifiedProjectModel,
    val compilationId: String,
    val compileTaskPath: String,
    sourceRoot: Path,
    compilerDestination: Path,
    private val verifyOrigin: () -> Unit,
) {
    val sourceRoot = fullPath(sourceRoot.toString())
    val compilerDestination = fullPath(compilerDestination.toString())
    internal fun verify() = verifyOrigin()
}

/** Documentary identity only; this record cannot restore an origin, installation or attempt. */
class DetektFullBindingIdentity internal constructor(
    val digest: String,
    val compilationId: String,
    val modelContextDigest: String,
    val argumentProjectionDigest: String,
    val installationDigest: String,
    val runtimeImageDigest: String,
)

internal class DetektFullBinding(
    val origin: DetektFullModelOrigin,
    val provider: DetektFullModelProvider,
    val installation: DetektFullInstallation,
    val compilation: ModelCompilation,
    val arguments: DetektFullArgumentProjection,
    val identity: DetektFullBindingIdentity,
) {
    fun verify(input: AnalysisInput, verifyFrozen: () -> Unit) {
        installation.checkOwner()
        origin.verify()
        installation.verify()
        verifyFrozen()
        fullCheck(provider.usesInstallation(installation), "detekt_full_provider_mismatch",
            "A live FULL binding cannot move to another installation/provider.")
        fullCheck(input.context.compilerVersion == DETEKT_EMBEDDED_KOTLIN &&
            input.context.compilerArguments == arguments.originalArguments &&
            arguments.renderOriginal() == compilation.compilerArguments &&
            input.context.compilationFiles == compilation.sourceFiles.map { it.path },
            "detekt_full_bound_arguments_changed", "Raw argv and the complete original modeled member order must remain unchanged.")
        verifyDependencies(input.context.classpath)
        verifyFrozen()
        origin.verify()
        installation.checkOwner()
    }

    fun verifyDependencies(actual: List<ArtifactIdentity>) {
        fullCheck(actual.size == compilation.classpath.size, "detekt_full_classpath_binding_mismatch",
            "The provider child cannot drop or reorder a dependency.")
        compilation.classpath.zip(actual).forEach { (declared, artifact) ->
            fullCheck(artifact.coordinate == declared.coordinate && artifact.digest == declared.digest,
                "detekt_full_classpath_binding_mismatch", "Every dependency retains its exact original ordinal/coordinate/digest.")
            val size = declared.size ?: fullFailure("detekt_full_classpath_binding_mismatch", "A dependency has no exact size.")
            verifyFullArtifact(DetektFullPinnedArtifact(artifact, size)) {
                installation.checkOwner()
                origin.verify()
            }
        }
    }
}

/**
 * A separate FULL-only route. It neither widens LIGHT nor certifies the project's Kotlin2.4.21.
 * COMPLETE here is only the strict importer prerequisite, never a successful analysis result.
 */
object DetektVerifiedFullModelBinding {
    const val SCHEMA = "ktchecker/detekt-verified-model-full-2.4.10-experiment/1"
    const val PROFILE_PROPERTY = "-Dktchecker.detekt.fullModelBinding="
    private val CONTROLLER_POLICIES = setOf("ktchecker.catalog-inventory", "ktchecker.activation-plan", "ktchecker.execution-plan")

    fun prepare(
        origin: DetektFullModelOrigin,
        original: AnalysisInput,
        provider: DetektFullModelProvider,
        detektRuleIds: List<String>,
        timeoutMillis: Long = 60_000,
        maxOutputBytes: Int = 1_048_576,
    ): PreparedDetektFullModelInput {
        val parent = freezeDetektInput(original)
        val selected = immutableDetektList(detektRuleIds)
        val model = origin.model
        val verified = model.context(origin.compilationId, model.identity.configurationDigest)
        val compilation = verified.compilation ?: fullFailure("detekt_full_compilation_unavailable",
            "The exact selected actual compilation is absent.")
        val context = verified.context ?: fullFailure("detekt_full_context_unavailable", "The strict imported context is unavailable.")
        fullCheck(compilation.platform == "jvm" && compilation.toolchain?.kind == "jvm",
            "detekt_full_platform_unsupported", "Common, Native, Android/mixed source roles and target-name aliases grant no FULL JVM route.")
        fullCheck(compilation.toolchain?.compilerVersion == DETEKT_EMBEDDED_KOTLIN &&
            context.compilerVersion == DETEKT_EMBEDDED_KOTLIN, "detekt_full_compiler_unsupported",
            "The embedded 2.4.10 engine is not semantic support for modeled Kotlin2.4.21 or another compiler.")
        fullCheck(model.issues.isEmpty() && verified.completeness == ModelCompleteness.COMPLETE &&
            verified.certificateId != null && context.fullProject && model.jvmProducedGraph == null &&
            model.snapshot.scopeExclusions.isEmpty() && model.snapshot.unknownInputs.isEmpty(),
            "detekt_full_model_incomplete", "No observed/deferred/unknown graph or input may be promoted into FULL semantics.")
        val chain = requireNotNull(compilation.toolchain)
        fullCheck(chain.compilerPlugins.isEmpty() && compilation.projectDependencies.isEmpty() &&
            compilation.friendArtifacts.isEmpty() && compilation.associatedCompilationIds.isEmpty() &&
            compilation.auxiliaryArtifacts.isEmpty() && compilation.auxiliaryInputFiles.isEmpty() &&
            compilation.deferredJvmOutputs.isEmpty() &&
            model.export.generatedInputs.none { generated -> generated.sourceSetIds.any { it in compilation.sourceSetIds } },
            "detekt_full_auxiliary_inputs_unsupported", "Compiler plugins, generated/Java/auxiliary/friend/produced inputs need separately proven semantics.")
        fullCheck(compilation.jvmArgumentRoles == null && model.jvmArgumentLayout(origin.compilationId) == null,
            "detekt_full_foreign_argument_adapter_unsupported", "The compiler2.4.21 original-role capability cannot be transplanted to this2.4.10 boundary.")
        val sourceSets = compilation.sourceSetIds.map { id -> model.sourceSet(id)
            ?: fullFailure("detekt_full_source_sets_unavailable", "An exact compilation source set is absent.") }
        fullCheck(sourceSets.isNotEmpty() && sourceSets.all { it.dependsOn.isEmpty() && it.resources.isEmpty() } &&
            compilation.sourceFiles.isNotEmpty() && compilation.sourceFiles.all { it.path.endsWith(".kt") } &&
            sourceSets.flatMap { it.sources }.sortedBy { it.path } == compilation.sourceFiles.sortedBy { it.path },
            "detekt_full_source_roles_unsupported", "Only the complete exact Kotlin-only source-set union is supported; no common/actual refinement is inferred.")
        fullCheck(parent.snapshot == model.snapshot && parent.execution == null && parent.outputRoot == null &&
            parent.materializedRoot == origin.sourceRoot.toString() && compilation.compileTaskPath == origin.compileTaskPath,
            "detekt_full_origin_mismatch", "Retain the owner's SAME registered whole snapshot/source root/compile task without injected profiles or outputs.")
        val additions = parent.context.policies.keys - context.policies.keys
        fullCheck(parent.context == context.copy(policies = parent.context.policies) &&
            context.policies.all { (key, value) -> parent.context.policies[key] == value } && additions == CONTROLLER_POLICIES,
            "detekt_full_context_mismatch", "Scope, original argv/membership, ordered classpath, model/ingress and policy values cannot be rewritten.")
        fullCheck(parent.context.policies["ktchecker.catalog-inventory"]?.let(Digests::valid) == true &&
            !parent.context.policies["ktchecker.activation-plan"].isNullOrBlank() &&
            parent.context.policies["ktchecker.execution-plan"] == Digests.sha256("{}"),
            "detekt_full_parent_profiles_mismatch", "The final owner catalog/activation and EMPTY whole-parent execution-plan map are required.")
        fullCheck(parent.requiredRuleIds.isNotEmpty() && parent.requiredRuleIds.distinct().size == parent.requiredRuleIds.size &&
            selected.isNotEmpty() && selected.distinct().size == selected.size &&
            selected.all { it in provider.mappedRuleIds } && parent.requiredRuleIds.filter { it in selected } == selected,
            "detekt_full_rule_projection_invalid", "The selected vector must be an exact ordered subset of ALL retained parent obligations.")
        fullCheck(timeoutMillis in 1..60_000 && maxOutputBytes in 1..1_048_576,
            "detekt_full_execution_limits_invalid", "The initial FULL child has fixed bounded execution/output limits.")
        fullCheck(provider.activeRuleIds(selected) == setOf(DETEKT_FULL_MISSING_USE_CALL),
            "detekt_full_no_enabled_semantic_rule", "No exact enabled representative FULL rule is executable; an empty/disabled selection must not launch.")
        val installation = provider.selectedInstallation()
            ?: fullFailure("detekt_full_installation_unavailable", "No independently owner-selected pinned FULL installation is available.")
        origin.verify()
        installation.requireModelToolchain(chain)
        val stdlib = compilation.classpath.singleOrNull { it.coordinate == DetektFullInstallation.STDLIB_COORDINATE }
        fullCheck(stdlib?.digest == DetektFullInstallation.STDLIB_SHA256 && stdlib?.size == DetektFullInstallation.STDLIB_BYTES,
            "detekt_full_stdlib_pin_mismatch", "This initial2.4.10 boundary requires its exact separately pinned modeled stdlib, not the host2.4.21 runtime.")
        val projection = projectDetektFullArguments(compilation, origin.sourceRoot, origin.compilerDestination, installation.jdkHome)
        fullCheck(parent.context.classpath == compilation.classpath.map {
            ArtifactIdentity(it.coordinate, requireNotNull(it.digest), it.path)
        }, "detekt_full_classpath_binding_mismatch", "The imported ordered original dependency paths cannot be substituted at preparation.")
        val contextDigest = fullJsonDigest(ContractJson.encodeToJsonElement(context))
        val relation = buildJsonObject {
            put("schema", SCHEMA); put("wholeParent", parent.analysisKey())
            put("namespace", ContractJson.encodeToJsonElement(model.namespace))
            put("modelIdentity", ContractJson.encodeToJsonElement(model.identity))
            put("ingressCertificate", model.ingressCertificate); put("compilationCertificate", verified.certificateId)
            put("compilationId", origin.compilationId); put("compileTaskPath", origin.compileTaskPath)
            put("ownerSourceRoot", origin.sourceRoot.toString()); put("ownerCompilerDestination", origin.compilerDestination.toString())
            put("modelContext", contextDigest); put("argumentProjection", projection.digest)
            put("selectedRuleIds", ContractJson.encodeToJsonElement(selected))
            put("installation", installation.digest); put("wholeJdkImage", installation.runtimeImageDigest)
            put("projectCompiler", DETEKT_EMBEDDED_KOTLIN); put("embeddedCompiler", DETEKT_EMBEDDED_KOTLIN)
            put("archiveBinding", "ktchecker/kernel-regular-jar-archive-1")
            put("compilerEmissionExecuted", false); put("completeTypeResolutionProven", false)
            put("compatibilityQualified", false); put("releaseQualified", false)
        }
        val identity = DetektFullBindingIdentity(fullJsonDigest(relation), origin.compilationId, contextDigest,
            projection.digest, installation.digest, installation.runtimeImageDigest)
        val binding = DetektFullBinding(origin, provider, installation, compilation, projection, identity)
        binding.verify(parent) { origin.verify() }
        val plan = provider.boundExecutionPlan(parent.copy(requiredRuleIds = selected), binding, timeoutMillis, maxOutputBytes)
        fullCheck(plan.analysisMode == "full" && plan.activeRuleIds == setOf(DETEKT_FULL_MISSING_USE_CALL),
            "detekt_full_plan_mismatch", "The fixed FULL plan cannot silently become LIGHT or enable another rule.")
        return PreparedDetektFullModelInput(binding, parent, selected, plan)
    }
}

/** Not admission approval. Retain only in the authenticated owner's live prepared-plan lifecycle. */
class PreparedDetektFullModelInput internal constructor(
    internal val binding: DetektFullBinding,
    original: AnalysisInput,
    selected: List<String>,
    internal val plan: DetektExecutionPlan,
) {
    val original = freezeDetektInput(original)
    val originalAnalysisKey = this.original.analysisKey()
    val detektRuleIds = immutableDetektList(selected)
    val detektProfile = freezeDetektProfile(plan.execution)
    val bindingIdentity get() = binding.identity

    /**
     * SAME exact kernel regular-JAR relation as LIGHT/compiler, not generic path normalization.
     * The CLI profile uses fixed ordinal archive templates, so an original compiler/dependency
     * path cannot leak into execution even though every original token remains in the context.
     */
    fun bindAttempt(kernelOriginal: AnalysisInput, kernelEffective: AnalysisInput,
                    detektChild: AnalysisInput, verifyFrozen: () -> Unit): DetektFullModelPermit {
        val received = freezeDetektInput(kernelOriginal)
        val effective = freezeDetektInput(kernelEffective)
        val child = freezeDetektInput(detektChild)
        fullCheck(received == original && received.analysisKey() == originalAnalysisKey,
            "detekt_full_original_mismatch", "The kernel original differs from the complete retained owner input.")
        requireDetektModelArchiveRelocation(original, effective)
        fullCheck(child == effective.copy(requiredRuleIds = detektRuleIds, execution = detektProfile),
            "detekt_full_child_mismatch", "Only the exact selected-rule/profile projection may become this provider child.")
        binding.verify(child, verifyFrozen)
        fullCheck(binding.provider.boundExecutionPlan(child, binding, detektProfile.timeoutMillis, detektProfile.maxOutputBytes) == plan,
            "detekt_full_profile_changed", "The installed FULL configuration, argument projection or identity changed before admission.")
        verifyFrozen()
        return DetektFullModelPermit(this, child, verifyFrozen)
    }
}

/** Live, revocable attempt grant: equal JSON or a separately equal ExecutionPermit is insufficient. */
class DetektFullModelPermit internal constructor(
    private val prepared: PreparedDetektFullModelInput,
    input: AnalysisInput,
    private val verifyFrozen: () -> Unit,
) : AutoCloseable {
    private val input = freezeDetektInput(input)
    private val active = AtomicBoolean(true)
    val expectedAnalysisKey = this.input.analysisKey()
    val bindingIdentity get() = prepared.bindingIdentity
    internal val plan get() = prepared.plan
    val executionPermit = ExecutionPermit(prepared.detektProfile, this.input.snapshot.snapshotId,
        Path.of(this.input.materializedRoot), expectedAnalysisKey, { verify(prepared.binding.provider, this.input) })

    fun accepts(input: AnalysisInput): Boolean = active.get() && input == this.input && input.analysisKey() == expectedAnalysisKey
    internal fun matches(provider: DetektFullModelProvider, input: AnalysisInput): Boolean =
        provider === prepared.binding.provider && accepts(input)

    internal fun checkOwner() = prepared.binding.installation.checkOwner()

    internal fun verify(provider: DetektFullModelProvider, input: AnalysisInput) {
        fullCheck(matches(provider, input), "detekt_full_attempt_mismatch", "The live grant is revoked or belongs to another provider/input.")
        prepared.binding.verify(this.input, verifyFrozen)
        fullCheck(provider.boundExecutionPlan(this.input, prepared.binding, plan.execution.timeoutMillis, plan.execution.maxOutputBytes) == plan,
            "detekt_full_profile_changed", "The exact installed FULL plan no longer matches.")
        verifyFrozen()
        fullCheck(matches(provider, input), "detekt_full_attempt_mismatch", "The live grant changed while its archive/installation were verified.")
    }

    override fun close() { active.set(false) }
}

fun interface DetektFullModelAuthority {
    fun authorize(input: AnalysisInput): DetektFullModelPermit?
    companion object { val DENY = DetektFullModelAuthority { null } }
}

class ExactDetektFullModelAuthority(private val permit: DetektFullModelPermit) : DetektFullModelAuthority {
    override fun authorize(input: AnalysisInput): DetektFullModelPermit? = permit.takeIf { it.accepts(input) }
}

class ExactDetektFullModelExecutionAuthority(private val permit: DetektFullModelPermit) : ExecutionAuthority {
    override fun authorize(input: AnalysisInput): ExecutionPermit? = permit.executionPermit.takeIf { permit.accepts(input) }
}

package dev.ktchecker.compiler.live.model

import dev.ktchecker.compiler.*
import dev.ktchecker.compiler.live.*
import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import dev.ktchecker.workerprotocol.CompilerModelWire as ModelWire
import java.nio.file.Path
import java.util.Collections
import kotlinx.serialization.json.encodeToJsonElement

/** Hard host bounds, in addition to the separately checked aggregate wire-frame bound. */
internal object ModelLiveLimits {
    const val SNAPSHOT_FILES = 256
    const val COMPILATION_FILES = 64
    const val SOURCE_BYTES = 1_048_576L
    const val SNAPSHOT_BYTES = 16_777_216L
    const val DEPENDENCIES = 16
    const val DEPENDENCY_BYTES = 67_108_864L
    const val DEPENDENCY_TOTAL_BYTES = 268_435_456L
    // P/K retain the COMPLETE owner catalog, not the compiler's much smaller projection.
    const val PARENT_RULES = 4_096
    const val COMPILER_RULES = 1_024
    const val PATH_BYTES = 512
    const val PATH_DEPTH = 16
    // Retention bounds for unchanged original KGP argv, NOT the executable wire vocabulary.
    // UTF-8 bytes are deliberately stricter than the importer's per-token character bound.
    const val ORIGINAL_ARGUMENTS = 128
    const val ORIGINAL_ARGUMENT_BYTES = 8_192
    const val INPUT_TEXT_BYTES = 2_097_152L
    // Directories as well as files count toward a bounded filesystem walk.
    const val SNAPSHOT_ENTRIES = SNAPSHOT_FILES * PATH_DEPTH
}

/**
 * Owner-only additive preparation. The caller must capture originalDeadline BEFORE configuration,
 * importer work, preparation and queueing. A decoded deadline, model digest, cold ExecutionPermit,
 * or otherwise-equal installation object cannot create this live model relation.
 *
 * The empty ordinary-profile map is truthful: this fixed live route has no cold command profile.
 * Its separate nonserialized owner application/attempt capabilities are still mandatory.
 */
fun KotlinJvmModelBinding.bindPreparedLiveInput(
    original: AnalysisInput,
    compilerRuleIds: List<String>,
    provider: KotlinJvmModelLiveCompilerProvider,
    originalDeadline: OwnedApplicationDeadline,
): PreparedJvmLiveModelInput {
    modelDeadlineCheckpoint(originalDeadline)
    val frozen = freezeModelLiveInput(original)
    requireOriginalInput(frozen, requireFinal = true)
    liveCheck(provider.usesInstallation(installation), "compiler_model_live_installation_mismatch")
    requireModelLiveShape(frozen, this)
    liveCheck(frozen.context.policies["ktchecker.execution-plan"] == emptyModelColdProfilesDigest(),
        "compiler_model_live_cold_profile_mismatch")
    var dependencyBytes = 0L
    compilation.classpath.forEach { artifact ->
        val size = artifact.size ?: liveFailure("compiler_model_live_dependency_limit")
        liveCheck(size in 1..ModelLiveLimits.DEPENDENCY_BYTES &&
            size <= ModelLiveLimits.DEPENDENCY_TOTAL_BYTES - dependencyBytes, "compiler_model_live_dependency_limit")
        dependencyBytes += size
    }
    liveCheck(compilerRuleIds.isNotEmpty() && compilerRuleIds.size <= ModelLiveLimits.COMPILER_RULES,
        "compiler_model_live_rule_projection_invalid")
    val rules = Collections.unmodifiableList(compilerRuleIds.toList())
    liveCheck(rules.distinct().size == rules.size &&
        frozen.requiredRuleIds.isNotEmpty() && frozen.requiredRuleIds.distinct().size == frozen.requiredRuleIds.size &&
        rules.all { it in frozen.requiredRuleIds && it in provider.supportedRuleIds }, "compiler_model_live_rule_projection_invalid")
    val checkpoint = { modelDeadlineCheckpoint(originalDeadline) }
    verifyInstallation(checkpoint)
    // Admission reads the original model artifacts; the attempt path below never does.
    verifyDependencies(compilation.classpath, frozen.context.classpath, checkpoint)
    checkpoint()
    return PreparedJvmLiveModelInput(this, frozen, rules, provider, originalDeadline)
}

/** Exact full owner input and original ingress clock, not an admission approval for another client. */
class PreparedJvmLiveModelInput internal constructor(
    internal val binding: KotlinJvmModelBinding,
    original: AnalysisInput,
    compilerRuleIds: List<String>,
    internal val provider: KotlinJvmModelLiveCompilerProvider,
    val originalDeadline: OwnedApplicationDeadline,
) {
    val original: AnalysisInput = freezeModelLiveInput(original)
    val originalAnalysisKey: String = this.original.analysisKey()
    val compilerRuleIds: List<String> = Collections.unmodifiableList(compilerRuleIds.toList())
    val bindingIdentity: JvmModelBindingIdentity get() = binding.identity
    /** Read-only importer-derived execution projection; never replaces original context fields. */
    val normalizedCompilerOptions: List<String> get() = binding.normalizedCompilerOptions
    val executableCompilationFiles: List<String> get() = binding.executableCompilationFiles
    val argumentLayoutDigest: String? get() = binding.argumentLayoutDigest

    /**
     * Only the trusted kernel observer supplies this tuple and whole-archive callback. P -> K
     * retains the entire model and permits only the exact kernel source/classpath/output paths;
     * K -> C changes only the retained compiler rule vector. No cold execution profile is inserted.
     * Actual job copies and C -> E binding happen only after this exact attempt is admitted.
     */
    fun bindAttempt(
        kernelOriginal: AnalysisInput,
        kernelEffective: AnalysisInput,
        compilerChild: AnalysisInput,
        application: KotlinJvmModelCompilerApplication,
        verifyFrozen: () -> Unit,
        registerMaterialization: (ModelCompilerJobMaterialization) -> Unit,
        verifyMaterialization: (ModelCompilerJobMaterialization) -> Unit,
    ): ModelCompilerAttemptPermit {
        modelDeadlineCheckpoint(originalDeadline)
        val suppliedOriginal = freezeModelLiveInput(kernelOriginal)
        val archived = freezeModelLiveInput(kernelEffective)
        val child = freezeModelLiveInput(compilerChild, ModelLiveLimits.COMPILER_RULES)
        liveCheck(suppliedOriginal == original && suppliedOriginal.analysisKey() == originalAnalysisKey,
            "compiler_model_live_original_mismatch")
        liveCheck(provider.usesInstallation(binding.installation) && application.request.installation === binding.installation &&
            application.request.namespace.matches(original), "compiler_model_live_application_binding_mismatch")
        requireJvmModelArchiveRelocation(original, archived)
        liveCheck(child == archived.copy(requiredRuleIds = compilerRuleIds, execution = null),
            "compiler_model_live_child_mismatch")
        val checkpoint = { modelDeadlineCheckpoint(originalDeadline) }
        checkpoint(); verifyFrozen(); checkpoint()
        binding.verifyInstallation(checkpoint)
        verifyDependencies(binding.compilation.classpath, archived.context.classpath, checkpoint)
        verifyFrozen(); checkpoint()
        return ModelCompilerAttemptPermit(this, archived, child, application, verifyFrozen,
            registerMaterialization, verifyMaterialization)
    }
}

internal fun emptyModelColdProfilesDigest(): String = Digests.sha256(
    Digests.canonical(ContractJson.encodeToJsonElement(emptyMap<String, TrustedExecution>())))

internal fun modelDeadlineCheckpoint(deadline: OwnedApplicationDeadline) {
    liveCheck(!deadline.isExpired(), "compiler_model_live_request_deadline")
    liveCheck(!Thread.currentThread().isInterrupted, "compiler_live_interrupted")
}

/** Pure bounded detach. No lookup, authority callback, clock creation, or filesystem access. */
internal fun freezeModelLiveInput(input: AnalysisInput, maxRules: Int = ModelLiveLimits.PARENT_RULES): AnalysisInput {
    liveCheck(input.snapshot.files.size <= ModelLiveLimits.SNAPSHOT_FILES &&
        input.snapshot.declaredRoots.size <= 64 && input.snapshot.scopeExclusions.size <= 64 && input.snapshot.unknownInputs.size <= 64 &&
        input.context.classpath.size <= ModelLiveLimits.DEPENDENCIES && input.context.compilerArguments.size <= ModelLiveLimits.ORIGINAL_ARGUMENTS &&
        (input.context.compilationFiles?.size ?: 0) <= ModelLiveLimits.COMPILATION_FILES &&
        input.context.libraries.size <= 256 && input.context.policies.size <= 64 &&
        input.requiredRuleIds.size <= maxRules && input.changedPaths.size <= ModelLiveLimits.SNAPSHOT_FILES,
        "compiler_model_live_input_limit")
    liveCheck(input.execution == null, "compiler_model_live_cold_execution_unsupported")
    var textBytes = 0L
    fun bounded(value: String, maximum: Int = 4_096) {
        liveCheck(value.length <= maximum && '\u0000' !in value, "compiler_model_live_input_limit")
        val size = value.toByteArray(Charsets.UTF_8).size.toLong()
        liveCheck(size <= maximum && size <= ModelLiveLimits.INPUT_TEXT_BYTES - textBytes, "compiler_model_live_input_limit")
        textBytes += size
    }
    bounded(input.materializedRoot); input.outputRoot?.let { bounded(it) }
    input.requiredRuleIds.forEach { bounded(it, 256) }
    input.changedPaths.forEach { bounded(it) }
    listOf(input.snapshot.snapshotId, input.snapshot.ownerId, input.snapshot.workspaceId, input.snapshot.worktreeId,
        input.snapshot.captureKind).forEach { bounded(it) }
    input.snapshot.parentSnapshotId?.let { bounded(it) }; input.baselineSnapshotId?.let { bounded(it) }
    input.snapshot.declaredRoots.forEach { bounded(it) }; input.snapshot.scopeExclusions.forEach { bounded(it) }
    input.snapshot.unknownInputs.forEach { bounded(it) }
    input.snapshot.files.forEach { bounded(it.path); bounded(it.digest, 71) }
    input.context.classpath.forEach { bounded(it.path); bounded(it.coordinate); bounded(it.digest, 71) }
    input.context.compilerArguments.forEach { bounded(it, ModelLiveLimits.ORIGINAL_ARGUMENT_BYTES) }
    input.context.compilationFiles?.forEach { bounded(it) }
    input.context.libraries.forEach { (key, value) -> bounded(key); bounded(value) }
    input.context.policies.forEach { (key, value) ->
        bounded(key, 256)
        // The complete captured activation vector is not a short scalar label.
        bounded(value, 1_048_576)
    }
    listOf(input.context.scope.module, input.context.scope.compilation, input.context.scope.sourceSet, input.context.scope.target)
        .forEach { bounded(it) }
    listOfNotNull(input.context.compilerVersion, input.context.modelDigest, input.context.configurationDigest,
        input.context.dependencyDigest, input.context.toolchainDigest).forEach { bounded(it) }
    return freezeJvmInput(input)
}

/** Pure structural checks may precede exact attempt lookup; they grant no argument semantics. */
internal fun requireModelLiveStructure(input: AnalysisInput) {
    liveCheck(input.snapshot.files.size <= ModelLiveLimits.SNAPSHOT_FILES && input.context.classpath.size <= ModelLiveLimits.DEPENDENCIES,
        "compiler_model_live_input_limit")
    liveCheck(input.context.fullProject && input.context.compilerVersion == CompilerRuleRegistry.VERSION &&
        input.snapshot.scopeExclusions.isEmpty() && input.snapshot.unknownInputs.isEmpty() &&
        listOf(input.snapshot.snapshotId, input.context.modelDigest, input.context.dependencyDigest,
            input.context.configurationDigest, input.context.toolchainDigest).all { it != null && Digests.valid(it) },
        "compiler_model_live_verified_context_required")
    val paths = input.snapshot.files.map { modelRelativePath(it.path) }
    liveCheck(paths.isNotEmpty() && paths.distinct().size == paths.size, "compiler_model_live_snapshot_members_invalid")
    val names = paths.toSet()
    paths.forEach { path ->
        var ancestor = Path.of(path).parent
        while (ancestor != null) {
            liveCheck(ancestor.toString().replace(java.io.File.separatorChar, '/') !in names, "compiler_model_live_snapshot_members_invalid")
            ancestor = ancestor.parent
        }
    }
    var total = 0L
    input.snapshot.files.forEach { file ->
        liveCheck(Digests.valid(file.digest) && file.size in 0..ModelLiveLimits.SOURCE_BYTES &&
            file.size <= ModelLiveLimits.SNAPSHOT_BYTES - total, "compiler_model_live_snapshot_limit")
        total += file.size
    }
    val selected = input.context.compilationFiles
    liveCheck(selected != null && selected.isNotEmpty() && selected.size <= ModelLiveLimits.COMPILATION_FILES &&
        selected.distinct().size == selected.size && selected.all { it in names && it.endsWith(".kt") },
        "compiler_model_live_compilation_members_invalid")
}

/**
 * No untyped full-argv path: absent binding retains the exact legacy scalar-only validation.
 * A live binding may supply a DISTINCT wire projection, but its raw argv AND raw model-member
 * order must still match at P/K/C/E. The binding's importer certificate covers the total original
 * token classification, complete declared dependency roles and executable source-order relation.
 * This function never parses, strips, sorts, rewrites or infers roles from an AnalysisInput.
 */
internal fun requireModelLiveShape(input: AnalysisInput, binding: KotlinJvmModelBinding? = null) {
    requireModelLiveStructure(input)
    if (binding == null) {
        requireModelLiveOptions(input.context.compilerArguments)
        return
    }
    liveCheck(input.context.compilerArguments == binding.originalContext.compilerArguments &&
        input.context.compilationFiles == binding.originalContext.compilationFiles,
        "compiler_model_live_argument_projection_mismatch")
    val members = requireNotNull(input.context.compilationFiles)
    val executable = binding.executableCompilationFiles
    liveCheck(executable.size == members.size && executable.distinct().size == executable.size &&
        executable.toSet() == members.toSet(), "compiler_model_live_argument_projection_mismatch")
    if (binding.argumentLayoutDigest == null) {
        // Historical scalar binding cannot acquire a reordered-source or path-normalization grant.
        liveCheck(binding.normalizedCompilerOptions == input.context.compilerArguments && executable == members,
            "compiler_model_live_argument_projection_mismatch")
    } else {
        liveCheck(Digests.valid(requireNotNull(binding.argumentLayoutDigest)),
            "compiler_model_live_argument_projection_mismatch")
    }
    requireModelLiveOptions(binding.normalizedCompilerOptions)
}

internal fun modelRelativePath(value: String): String {
    liveCheck(value.toByteArray(Charsets.UTF_8).size <= ModelLiveLimits.PATH_BYTES &&
        value.split('/').size <= ModelLiveLimits.PATH_DEPTH, "compiler_model_live_path_limit")
    return try { CompilerSnapshot.relative(value) }
    catch (_: Exception) { liveFailure("compiler_model_live_path_invalid") }
}

/** The wire owns the one exact token grammar; no inferred target, rewriting or deduplication. */
internal fun requireModelLiveOptions(options: List<String>) {
    try { ModelWire.validateOptions(options) }
    catch (_: IllegalArgumentException) { liveFailure("compiler_model_live_options_unsupported") }
}

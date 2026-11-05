package dev.ktchecker.compiler.live

import dev.ktchecker.compiler.KotlinJvmToolchain
import dev.ktchecker.compiler.freezeJvmInput
import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import dev.ktchecker.providers.runtime.OwnedApplicationOwner
import dev.ktchecker.workerprotocol.ApplicationWire as Wire
import dev.ktchecker.workerprotocol.CompilerJobWire as CompilerWire
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*

/** An exact detached namespace descriptor; neither its digest nor its strings grant authority. */
class CompilerApplicationNamespace(val ownerId: String, val workspaceId: String, val worktreeId: String) {
    init { listOf(ownerId, workspaceId, worktreeId).forEach { liveCheck(it.isNotBlank() && it.length <= 256 &&
        it.none { c -> c == '\u0000' || c == '\r' || c == '\n' }, "compiler_live_namespace_invalid") } }
    val digest: String = Wire.digest("ktchecker/compiler-live-namespace/1",
        Digests.canonical(JsonArray(listOf(ownerId, workspaceId, worktreeId).map(::JsonPrimitive))).toByteArray(Charsets.UTF_8))
    internal fun matches(input: AnalysisInput): Boolean = input.snapshot.let {
        it.ownerId == ownerId && it.workspaceId == workspaceId && it.worktreeId == worktreeId
    }
}

/**
 * Trusted-owner request data. Create originalDeadline at ingress BEFORE preparing this descriptor.
 * The original installation OBJECT remains authoritative after its artifact copies are materialized.
 * There is deliberately no ExecutionPermit, model-permit conversion, or executable selection by input.
 */
class CompilerGenerationRequest(
    val owner: OwnedApplicationOwner,
    val namespace: CompilerApplicationNamespace,
    val installation: KotlinJvmToolchain,
    launch: Wire.Launch,
    val originalDeadline: OwnedApplicationDeadline,
) {
    val launch: Wire.Launch = Wire.decodeLaunch(Wire.encodeLaunch(launch))
    val generation: CompilerWire.Generation = CompilerWire.decodeGeneration(this.launch.payload())
    val launchDigest: String = Wire.launchDigest(this.launch)
    val dependencyClasspath: List<ArtifactIdentity> = Collections.unmodifiableList(
        generation.dependencyIndexes().map { index -> installation.compilerClasspath.getOrNull(index)?.copy()
            ?: liveFailure("compiler_live_installation_mismatch") })
    val dependencyDigest: String = Wire.digest("ktchecker/compiler-live-original-dependencies/1",
        Digests.canonical(ContractJson.encodeToJsonElement(dependencyClasspath)).toByteArray(Charsets.UTF_8))
    val configurationDigest: String = CompilerWire.profileDigest(generation)

    init {
        liveCheck(!originalDeadline.isExpired(), "compiler_live_generation_deadline")
        liveCheck(this.launch.identity().ownerDomain() == owner.identity &&
            this.launch.identity().worktreeDigest() == namespace.digest, "compiler_live_owner_binding_mismatch")
        CompilerWire.matchGeneration(this.launch, generation)
    }
}

/** A nonserialized, single-use generation grant bound to the exact request/owner/installation objects. */
class CompilerGenerationPermit(
    val request: CompilerGenerationRequest,
    private val verifyBeforeStart: () -> Unit,
    private val verifyLive: () -> Unit,
) {
    private val live = AtomicBoolean(true)
    private val consumed = AtomicBoolean()
    private val cancellation = AtomicReference<(() -> Unit)?>(null)

    /**
     * Revoke first; an attached app then closes its actual publication gate before transport cancel.
     * Overlapping publication/revoke linearizes at that gate, not at a detached status observation.
     */
    fun revoke() {
        live.set(false)
        cancellation.get()?.invoke()
    }

    internal fun consume(expected: CompilerGenerationRequest) {
        liveCheck(request === expected, "compiler_live_generation_binding_mismatch")
        liveCheck(consumed.compareAndSet(false, true), "compiler_live_generation_permit_used")
        checkLive()
    }

    internal fun beforeStart() { checkLive(); verifyBeforeStart(); checkLive() }
    internal fun verify() { checkLive(); verifyLive(); checkLive() }
    internal fun checkLive() {
        liveCheck(live.get(), "compiler_live_generation_revoked")
        liveCheck(!request.originalDeadline.isExpired(), "compiler_live_generation_deadline")
    }

    internal fun invalidate() { live.set(false) }

    internal fun bindCancellation(action: () -> Unit) {
        liveCheck(cancellation.compareAndSet(null, action), "compiler_live_generation_binding_mismatch")
        if (!live.get()) action()
    }
}

/** Frozen lookup only. In particular, constructing this during analyze cannot start a fresh clock. */
class CompilerAttemptRequest internal constructor(val application: KotlinJvmCompilerApplication, input: AnalysisInput) {
    val input: AnalysisInput = freezeLiveInput(input)
    val analysisKey: String = this.input.analysisKey()
}

interface KotlinJvmApplicationAuthority {
    fun authorizeGeneration(request: CompilerGenerationRequest): CompilerGenerationPermit?
    fun authorizeAttempt(provider: KotlinJvmLiveCompilerProvider, request: CompilerAttemptRequest): CompilerAttemptPermit?

    companion object {
        val DENY: KotlinJvmApplicationAuthority = object : KotlinJvmApplicationAuthority {
            override fun authorizeGeneration(request: CompilerGenerationRequest): CompilerGenerationPermit? = null
            override fun authorizeAttempt(provider: KotlinJvmLiveCompilerProvider, request: CompilerAttemptRequest): CompilerAttemptPermit? = null
        }
    }
}

/**
 * Owner retains this BEFORE calling provider.analyze(originalInput). The constructor captures the
 * immutable original source while retaining its original ingress clock. No job directory is created
 * until consume succeeds in the real application. All three owner hooks are mandatory.
 */
class CompilerAttemptPermit(
    val owner: OwnedApplicationOwner,
    val provider: KotlinJvmLiveCompilerProvider,
    val application: KotlinJvmCompilerApplication,
    originalInput: AnalysisInput,
    val originalDeadline: OwnedApplicationDeadline,
    private val verifyCapturedInput: () -> Unit,
    private val registerMaterialization: (CompilerJobMaterialization) -> Unit,
    private val verifyMaterialization: (CompilerJobMaterialization) -> Unit,
) {
    val original: AnalysisInput = freezeLiveInput(originalInput)
    val originalKey: String = original.analysisKey()
    internal val capture: CompilerCapturedSource
    internal val ticket = application.newAttemptTicket(originalDeadline)
    private val registered = AtomicReference<CompilerJobMaterialization?>(null)

    init {
        liveCheck(owner === application.request.owner && provider.uses(application), "compiler_live_attempt_binding_mismatch")
        validateLiveOriginal(application.request, original)
        val sourceRoot = livePath(original.materializedRoot)
        val generationRoot = livePath(application.request.launch.generationRoot().path())
        liveCheck(!sourceRoot.startsWith(generationRoot) && !generationRoot.startsWith(sourceRoot), "compiler_live_original_root_overlap")
        checkpoint()
        verifyCapturedInput()
        checkpoint()
        capture = CompilerCapturedSource.capture(original, ::checkpoint)
        verifyCapturedInput()
        capture.verify(::checkpoint)
        checkpoint()
    }

    /** Only an exact currently-admitted attempt can revoke its application's active generation. */
    fun revoke() { application.revokeAttempt(this) }

    internal fun matches(selected: KotlinJvmLiveCompilerProvider, request: CompilerAttemptRequest): Boolean =
        application.isAttemptLive(this) && selected === provider && application === request.application &&
            owner === application.request.owner && provider.uses(application) &&
            original == request.input && originalKey == request.analysisKey

    internal fun consume() {
        application.admitAttempt(this)
    }

    internal fun checkpoint() {
        application.checkAttemptLive(this)
        application.checkLive()
        liveCheck(!originalDeadline.isExpired(), "compiler_live_request_deadline")
    }

    internal fun verifyOriginal(checkpoint: () -> Unit) {
        checkpoint(); this.checkpoint()
        verifyCapturedInput()
        checkpoint(); this.checkpoint()
        capture.verify(checkpoint)
        checkpoint(); this.checkpoint()
    }

    internal fun register(value: CompilerJobMaterialization, checkpoint: () -> Unit) {
        application.requireActiveAttempt(this)
        liveCheck(registered.compareAndSet(null, value), "compiler_live_materialization_replayed")
        liveCheck(value.original === original, "compiler_live_attempt_binding_mismatch")
        checkpoint(); registerMaterialization(value); checkpoint()
    }

    internal fun verifyRegistered(value: CompilerJobMaterialization, checkpoint: () -> Unit) {
        liveCheck(registered.get() === value, "compiler_live_materialization_binding_mismatch")
        checkpoint(); verifyMaterialization(value); checkpoint()
    }
}

internal class CompilerApplicationProblem(val code: String) : IllegalStateException(code)
internal fun liveFailure(code: String): Nothing = throw CompilerApplicationProblem(code)
internal fun liveCheck(value: Boolean, code: String) { if (!value) liveFailure(code) }

/** Bound before copying/serializing arbitrary contract lists. Invalid oversized obligations stay unresolved. */
internal fun freezeLiveInput(input: AnalysisInput): AnalysisInput {
    liveCheck(input.snapshot.files.size <= 1 && input.snapshot.declaredRoots.size <= 1 &&
        input.snapshot.scopeExclusions.size <= 16 && input.snapshot.unknownInputs.size <= 16 &&
        input.context.classpath.size <= 16 && input.context.compilerArguments.size <= 64 &&
        (input.context.compilationFiles?.size ?: 0) <= 1 && input.context.libraries.size <= 16 &&
        input.context.policies.size <= 16 && input.requiredRuleIds.size <= 1024 && input.changedPaths.size <= 1,
        "compiler_live_input_limit")
    val strings = buildList<String> {
        add(input.materializedRoot); input.outputRoot?.let(::add)
        addAll(input.requiredRuleIds); addAll(input.changedPaths); addAll(input.snapshot.declaredRoots)
        addAll(input.snapshot.scopeExclusions); addAll(input.snapshot.unknownInputs)
        addAll(listOf(input.snapshot.snapshotId, input.snapshot.ownerId, input.snapshot.workspaceId, input.snapshot.worktreeId))
        input.snapshot.parentSnapshotId?.let(::add); input.baselineSnapshotId?.let(::add); add(input.snapshot.captureKind)
        input.snapshot.files.forEach { add(it.path); add(it.digest) }
        input.context.classpath.forEach { add(it.coordinate); add(it.digest); add(it.path) }
        addAll(input.context.compilerArguments); input.context.compilationFiles?.let(::addAll)
        input.context.libraries.forEach { (k, v) -> add(k); add(v) }
        input.context.policies.forEach { (k, v) -> add(k); add(v) }
        addAll(listOf(input.context.scope.target, input.context.scope.sourceSet, input.context.scope.module, input.context.scope.compilation))
        listOfNotNull(input.context.compilerVersion, input.context.modelDigest, input.context.dependencyDigest,
            input.context.toolchainDigest, input.context.configurationDigest).let(::addAll)
    }
    liveCheck(strings.all { it.length <= 4096 && '\u0000' !in it } && strings.sumOf { it.length.toLong() } <= 65_536,
        "compiler_live_input_limit")
    liveCheck(input.context.modelDigest == null && !input.context.fullProject &&
        input.context.policies.keys.none { it.startsWith("ktchecker.model") }, "compiler_live_model_profile_unsupported")
    // An execution profile is explicitly unsupported, so never deep-copy an unbounded foreign argv.
    liveCheck(input.execution == null, "compiler_live_execution_profile_unsupported")
    return freezeJvmInput(input)
}

internal fun validateLiveOriginal(request: CompilerGenerationRequest, input: AnalysisInput) {
    liveCheck(input.context.modelDigest == null && !input.context.fullProject &&
        input.context.policies.keys.none { it.startsWith("ktchecker.model") }, "compiler_live_model_profile_unsupported")
    liveCheck(request.namespace.matches(input), "compiler_live_namespace_mismatch")
    liveCheck(input.execution == null && input.outputRoot == null, "compiler_live_original_profile_mismatch")
    liveCheck(input.context.scope.target == "jvm" && input.context.compilerVersion == CompilerWire.COMPILER_VERSION &&
        input.context.compilerArguments == CompilerWire.M0_OPTIONS && input.context.policies.isEmpty() &&
        input.context.libraries.isEmpty(), "compiler_live_options_unsupported")
    liveCheck(input.context.classpath == request.dependencyClasspath && input.context.dependencyDigest == request.dependencyDigest &&
        input.context.configurationDigest == request.configurationDigest && input.context.toolchainDigest == request.installation.digest,
        "compiler_live_installation_binding_mismatch")
    liveCheck(input.context.compilationFiles == listOf("Fixture.kt") && input.snapshot.files.size == 1 &&
        input.snapshot.files.single().let { it.path == "Fixture.kt" && Digests.valid(it.digest) &&
            it.size in 1..CompilerWire.MAX_SOURCE_BYTES.toLong() } && input.snapshot.declaredRoots == listOf(".") &&
        input.snapshot.scopeExclusions.isEmpty() && input.snapshot.unknownInputs.isEmpty() &&
        input.snapshot.captureKind == "immutable_overlay", "compiler_live_snapshot_profile_unsupported")
    liveCheck(Digests.valid(input.snapshot.snapshotId) && input.snapshot.parentSnapshotId?.let(Digests::valid) != false &&
        input.baselineSnapshotId?.let(Digests::valid) != false && input.changedPaths.all { it == "Fixture.kt" } &&
        input.requiredRuleIds.size == input.requiredRuleIds.distinct().size &&
        input.requiredRuleIds.all { it.isNotBlank() && it.length <= 256 && it.none { c -> c == '\r' || c == '\n' } },
        "compiler_live_original_identity_invalid")
    livePath(input.materializedRoot)
}

internal fun liveEffective(request: CompilerGenerationRequest, original: AnalysisInput, sourceRoot: Path, outputRoot: Path): AnalysisInput =
    freezeLiveInput(original.copy(materializedRoot = sourceRoot.toString(), outputRoot = outputRoot.toString(),
        context = original.context.copy(classpath = original.context.classpath.mapIndexed { index, artifact ->
            artifact.copy(path = Path.of(request.launch.generationRoot().path()).resolve("compiler")
                .resolve("${request.generation.dependencyIndexes()[index]}.jar").toString()) })))

internal fun validateLiveEffective(request: CompilerGenerationRequest, original: AnalysisInput, effective: AnalysisInput,
    sourceRoot: Path, outputRoot: Path) {
    validateLiveOriginal(request, original)
    liveCheck(effective == liveEffective(request, original, sourceRoot, outputRoot), "compiler_live_effective_input_mismatch")
}

internal fun livePath(value: String): Path {
    val path = try { Path.of(value) } catch (_: Exception) { liveFailure("compiler_live_path_invalid") }
    liveCheck(path.isAbsolute && path.normalize() == path && path.toString() == value &&
        value.length <= 4096 && value.none { it == '\u0000' || it == '\r' || it == '\n' }, "compiler_live_path_invalid")
    return path
}

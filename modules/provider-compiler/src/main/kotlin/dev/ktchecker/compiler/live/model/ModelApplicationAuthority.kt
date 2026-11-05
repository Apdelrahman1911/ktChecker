package dev.ktchecker.compiler.live.model

import dev.ktchecker.compiler.KotlinJvmToolchain
import dev.ktchecker.compiler.live.*
import dev.ktchecker.contracts.AnalysisInput
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import dev.ktchecker.providers.runtime.OwnedApplicationOwner
import dev.ktchecker.workerprotocol.ApplicationWire as Wire
import dev.ktchecker.workerprotocol.CompilerModelWire as ModelWire
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Typed owner generation request. Create its generation deadline BEFORE preparing/copying the
 * installation descriptor. It is independent of each original model-request ingress clock.
 * The SAME installed toolchain object stays authoritative, not its serializable digest alone.
 */
class ModelCompilerGenerationRequest(
    val owner: OwnedApplicationOwner,
    val namespace: CompilerApplicationNamespace,
    val installation: KotlinJvmToolchain,
    launch: Wire.Launch,
    val originalDeadline: OwnedApplicationDeadline,
) {
    val launch: Wire.Launch = Wire.decodeLaunch(Wire.encodeLaunch(launch))
    val generation: ModelWire.Generation = ModelWire.decodeGeneration(this.launch.payload())
    val launchDigest: String = Wire.launchDigest(this.launch)

    init {
        liveCheck(!originalDeadline.isExpired(), "compiler_model_live_generation_deadline")
        liveCheck(this.launch.identity().ownerDomain() == owner.identity &&
            this.launch.identity().worktreeDigest() == namespace.digest, "compiler_model_live_owner_binding_mismatch")
        ModelWire.matchGeneration(this.launch, generation)
    }
}

/** Nonserialized exact single-use generation grant; neither a launch DTO nor a cold grant is one. */
class ModelCompilerGenerationPermit(
    val request: ModelCompilerGenerationRequest,
    private val verifyBeforeStart: () -> Unit,
    private val verifyLive: () -> Unit,
) {
    private val live = AtomicBoolean(true)
    private val consumed = AtomicBoolean()
    private val cancellation = AtomicReference<(() -> Unit)?>(null)

    fun revoke() { live.set(false); cancellation.get()?.invoke() }

    internal fun consume(expected: ModelCompilerGenerationRequest) {
        // A wrong request cannot steal this grant or revoke its legitimate consumer.
        liveCheck(request === expected, "compiler_model_live_generation_binding_mismatch")
        liveCheck(consumed.compareAndSet(false, true), "compiler_model_live_generation_permit_used")
        checkLive()
    }

    internal fun beforeStart() { checkLive(); verifyBeforeStart(); checkLive() }
    internal fun verify() { checkLive(); verifyLive(); checkLive() }
    internal fun checkLive() {
        liveCheck(live.get(), "compiler_model_live_generation_revoked")
        liveCheck(!request.originalDeadline.isExpired(), "compiler_model_live_generation_deadline")
    }
    internal fun invalidate() { live.set(false) }
    internal fun bindCancellation(action: () -> Unit) {
        liveCheck(cancellation.compareAndSet(null, action), "compiler_model_live_generation_binding_mismatch")
        if (!live.get()) action()
    }
}

/** Pure bounded request lookup. No filesystem, authority callback, clock minting, or generation selection. */
class ModelCompilerAttemptRequest internal constructor(input: AnalysisInput) {
    val input: AnalysisInput = freezeModelLiveInput(input, ModelLiveLimits.COMPILER_RULES)
    val analysisKey: String = this.input.analysisKey()
}

interface KotlinJvmModelApplicationAuthority {
    fun authorizeGeneration(request: ModelCompilerGenerationRequest): ModelCompilerGenerationPermit?
    fun authorizeAttempt(provider: KotlinJvmModelLiveCompilerProvider, request: ModelCompilerAttemptRequest): ModelCompilerAttemptPermit?

    companion object {
        val DENY: KotlinJvmModelApplicationAuthority = object : KotlinJvmModelApplicationAuthority {
            override fun authorizeGeneration(request: ModelCompilerGenerationRequest): ModelCompilerGenerationPermit? = null
            override fun authorizeAttempt(provider: KotlinJvmModelLiveCompilerProvider,
                request: ModelCompilerAttemptRequest): ModelCompilerAttemptPermit? = null
        }
    }
}

/**
 * Only PreparedJvmLiveModelInput.bindAttempt creates this model application capability. Its full
 * P/K/C tuple, typed importer binding, original ingress clock and archive callbacks are retained.
 * A serialized model/cold profile cannot bypass that relation or select an application.
 */
class ModelCompilerAttemptPermit internal constructor(
    internal val prepared: PreparedJvmLiveModelInput,
    archived: AnalysisInput,
    input: AnalysisInput,
    val application: KotlinJvmModelCompilerApplication,
    private val verifyFrozen: () -> Unit,
    private val registerMaterialization: (ModelCompilerJobMaterialization) -> Unit,
    private val verifyMaterialization: (ModelCompilerJobMaterialization) -> Unit,
) : AutoCloseable {
    val provider: KotlinJvmModelLiveCompilerProvider get() = prepared.provider
    val original: AnalysisInput get() = prepared.original
    val archived: AnalysisInput = freezeModelLiveInput(archived)
    val input: AnalysisInput = freezeModelLiveInput(input, ModelLiveLimits.COMPILER_RULES)
    val originalDeadline: OwnedApplicationDeadline get() = prepared.originalDeadline
    val providerInputKey: String = this.input.analysisKey()
    val archivedInputKey: String = this.archived.analysisKey()
    internal val ticket = application.newAttemptTicket(originalDeadline)
    internal val capture: ModelArchiveCapture
    private val registered = AtomicReference<ModelCompilerJobMaterialization?>(null)
    private val successfulOutput = AtomicReference<ModelSuccessfulJvmOutput?>(null)

    /** Only compiler code0 AND accepted publication can make this non-null. Not provider coverage. */
    fun successfulProducedOutput(): ModelSuccessfulJvmOutput? = successfulOutput.get()
    internal fun retainsSuccessfulOutput(value: ModelSuccessfulJvmOutput): Boolean = successfulOutput.get() === value
    internal fun publishSuccessfulOutput(value: ModelSuccessfulJvmOutput) {
        liveCheck(value.permit === this && successfulOutput.compareAndSet(null, value), "compiler_model_produced_success_replayed")
    }

    /** Completed-output custody, NOT an attempt-ticket renewal. Only the retained accepted token
     * can invoke this path; the whole original K archive and owner materialization hooks remain
     * authoritative while later consumers borrow its output. No old model source/artifact reads.
     */
    internal fun verifySuccessfulCustody(value: ModelSuccessfulJvmOutput, materialized: ModelCompilerJobMaterialization,
        checkpoint: () -> Unit) {
        liveCheck(retainsSuccessfulOutput(value), "compiler_model_produced_not_accepted")
        application.checkLive(); checkpoint()
        verifyFrozen(); capture.verify(checkpoint)
        verifyRegistered(materialized, checkpoint)
        // Hooks do not get the last word over either complete archive or application liveness.
        verifyFrozen(); capture.verify(checkpoint)
        application.checkLive(); checkpoint()
        liveCheck(retainsSuccessfulOutput(value), "compiler_model_produced_not_accepted")
    }

    init {
        liveCheck(provider.usesInstallation(application.request.installation) &&
            prepared.binding.installation === application.request.installation &&
            application.request.namespace.matches(original), "compiler_model_live_application_binding_mismatch")
        val archiveRoot = livePath(this.archived.materializedRoot)
        val generationRoot = livePath(application.request.launch.generationRoot().path())
        val archiveParent = archiveRoot.parent
        val originalOutput = livePath(requireNotNull(this.input.outputRoot))
        liveCheck(!archiveParent.startsWith(generationRoot) && !generationRoot.startsWith(archiveParent) &&
            !originalOutput.startsWith(generationRoot) && !generationRoot.startsWith(originalOutput),
            "compiler_model_live_original_root_overlap")
        checkpoint(); verifyFrozen(); checkpoint()
        capture = ModelArchiveCapture.capture(this.input, prepared.binding, ::checkpoint)
        verifyFrozen(); capture.verify(::checkpoint); checkpoint()
    }

    /** A stale/rejected/completed ticket cannot revoke a subsequent attempt's application. */
    fun revoke() { application.revokeAttempt(this) }
    override fun close() { revoke() }

    /** Pure authority lookup; foreign input cannot run owner callbacks or consume the ticket. */
    fun accepts(input: AnalysisInput): Boolean = application.isAttemptLive(this) &&
        this.input == input && providerInputKey == input.analysisKey()

    internal fun matches(selected: KotlinJvmModelLiveCompilerProvider, request: ModelCompilerAttemptRequest): Boolean =
        selected === provider && selected.usesInstallation(prepared.binding.installation) &&
            application.request.installation === prepared.binding.installation && accepts(request.input) &&
            providerInputKey == request.analysisKey && selected.usesBinding(prepared.binding)

    internal fun consume() { application.admitAttempt(this) }
    internal fun checkpoint() {
        application.checkAttemptLive(this)
        application.checkLive()
        modelDeadlineCheckpoint(originalDeadline)
    }

    internal fun verifyOriginal(checkpoint: () -> Unit) {
        checkpoint(); this.checkpoint(); verifyFrozen(); checkpoint(); this.checkpoint()
        prepared.binding.verifyInstallation(checkpoint)
        prepared.binding.producedResolution?.let { it.verify(prepared.binding.model, prepared.binding.installation,
            prepared.binding.compilationId, checkpoint) }
        capture.verify(checkpoint)
        checkpoint(); this.checkpoint()
    }

    internal fun register(value: ModelCompilerJobMaterialization, checkpoint: () -> Unit) {
        application.requireActiveAttempt(this)
        liveCheck(registered.compareAndSet(null, value), "compiler_model_live_materialization_replayed")
        liveCheck(value.original === original && value.archived === archived && value.input === input,
            "compiler_model_live_materialization_binding_mismatch")
        checkpoint(); registerMaterialization(value); checkpoint()
    }

    internal fun verifyRegistered(value: ModelCompilerJobMaterialization, checkpoint: () -> Unit) {
        liveCheck(registered.get() === value, "compiler_model_live_materialization_binding_mismatch")
        checkpoint(); verifyMaterialization(value); checkpoint()
    }
}

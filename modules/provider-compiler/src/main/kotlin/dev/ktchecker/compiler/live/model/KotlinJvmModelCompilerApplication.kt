package dev.ktchecker.compiler.live.model

import dev.ktchecker.compiler.live.*
import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import dev.ktchecker.workerprotocol.ApplicationWire as Wire
import dev.ktchecker.workerprotocol.CompilerModelWire as ModelWire
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

sealed interface ModelCompilerApplicationOpenResult {
    class Ready internal constructor(val application: KotlinJvmModelCompilerApplication) : ModelCompilerApplicationOpenResult
    class Unavailable internal constructor(reasons: List<String>, val cleanup: OwnedApplicationRetirement? = null) : ModelCompilerApplicationOpenResult {
        val reasons: List<String> = Collections.unmodifiableList(reasons.distinct())
    }
}

/**
 * Additive bounded JVM-model application. Transport/custody remain runtime-owned; compiler code
 * stays in the separately packaged model worker. M0 APIs/profile and ordinary routes are unchanged.
 */
class KotlinJvmModelCompilerApplication private constructor(
    internal val request: ModelCompilerGenerationRequest,
    private val generationPermit: ModelCompilerGenerationPermit,
    private val session: OwnedApplicationSession,
    private val installationCapture: ModelGenerationCapture?,
) {
    val hello: Wire.Hello = session.hello
    private val lifecycle = CompilerApplicationLifecycle(request.originalDeadline, generationPermit::revoke)
    private val sequence = AtomicInteger(1)
    private val materializations = ConcurrentHashMap<Int, ModelCompilerJobMaterialization>()

    internal fun checkLive() { lifecycle.checkOpen(); generationPermit.checkLive() }
    internal fun newAttemptTicket(deadline: OwnedApplicationDeadline): CompilerApplicationLifecycle.Ticket = lifecycle.newTicket(deadline)
    internal fun isAttemptLive(permit: ModelCompilerAttemptPermit): Boolean = permit.application === this && lifecycle.isLive(permit.ticket)
    internal fun checkAttemptLive(permit: ModelCompilerAttemptPermit) { requireBoundAttempt(permit); lifecycle.checkTicketLive(permit.ticket) }
    internal fun requireActiveAttempt(permit: ModelCompilerAttemptPermit) { requireBoundAttempt(permit); lifecycle.requireActive(permit.ticket) }
    internal fun admitAttempt(permit: ModelCompilerAttemptPermit) { requireBoundAttempt(permit); lifecycle.admit(permit.ticket) }
    internal fun revokeAttempt(permit: ModelCompilerAttemptPermit) { requireBoundAttempt(permit); lifecycle.revoke(permit.ticket) }

    private fun requireBoundAttempt(permit: ModelCompilerAttemptPermit) {
        liveCheck(permit.application === this && permit.provider.usesInstallation(request.installation) &&
            permit.prepared.binding.installation === request.installation && request.namespace.matches(permit.original),
            "compiler_model_live_attempt_binding_mismatch")
    }

    fun cancel() { lifecycle.cancel() }
    private fun cancelRevokedGeneration() { lifecycle.closeForGenerationRevocation(); session.cancel() }

    /** Healthy EOF/retirement is generation cleanup only, never job success or a model certificate. */
    fun retire(): OwnedApplicationRetirement {
        lifecycle.closeForRetirement()
        return try { session.retire() } finally { generationPermit.invalidate() }
    }

    internal fun verifyProducedOutput(value: ModelCompilerJobMaterialization, checkpoint: () -> Unit) {
        checkLive(); checkpoint()
        liveCheck(installationCapture != null && materializations.values.any { it === value },
            "compiler_model_produced_custody_unavailable")
        generationPermit.verify(); value.verifyInputs(checkpoint); value.verifyOutput(true, checkpoint)
        requireNotNull(installationCapture).verify(checkpoint)
        checkLive(); checkpoint()
    }

    internal fun compile(permit: ModelCompilerAttemptPermit, cancellation: CancellationToken,
        providerStartedNanos: Long): OwnedApplicationJobResult<ProviderResult> {
        var admitted = false
        try {
            requireBoundAttempt(permit); permit.checkpoint()
            permit.consume()
            admitted = true
            val installation = installationCapture ?: liveFailure("compiler_model_live_binding_control_no_execution")
            val checkpoint = {
                checkLive(); permit.checkpoint()
                if (Thread.currentThread().isInterrupted) liveFailure("compiler_live_interrupted")
                if (cancellation.isCancelled()) throw CancellationException("compiler_model_live_cancelled")
                // A false-returning cancellation callback still spends the original clock and
                // may set the present interrupt. Observe both before any subsequent side effect.
                checkLive(); permit.checkpoint()
            }
            checkpoint() // After admission/lock wait and before any copy or application write.
            val next = sequence.getAndIncrement()
            liveCheck(next <= request.launch.limits().maxJobs(), "compiler_model_live_job_limit")
            verifyRetained(installation, checkpoint)
            reserveRetainedBudget(permit)
            permit.verifyOriginal(checkpoint)
            val materialized = ModelCompilerJobMaterialization.create(request, permit, next, checkpoint)
            liveCheck(materializations.putIfAbsent(next, materialized) == null, "compiler_model_live_materialization_replayed")
            permit.register(materialized, checkpoint)
            val body = materialized.wireRequest
            val job = Wire.Job(request.launch.identity().generation(), Wire.helloDigest(hello), next,
                Wire.jobId(request.launch.identity().generation(), next), permit.providerInputKey,
                materialized.effective.analysisKey(), permit.originalDeadline.identity, ModelWire.encodeRequest(body))
            Wire.matchJob(request.launch, hello, job, next)
            ModelWire.matchRequest(request.launch, request.generation, job, body)

            fun verifyBound(sealedOutput: Boolean) {
                checkpoint()
                requireModelEffectiveRelation(permit.input, materialized.effective, livePath(materialized.sourceRoot.path()),
                    livePath(materialized.classpathRoot.path()), livePath(materialized.outputRoot.path()))
                permit.verifyOriginal(checkpoint)
                materialized.verifyInputs(checkpoint); materialized.verifyOutput(sealedOutput, checkpoint)
                permit.verifyRegistered(materialized, checkpoint)
                generationPermit.verify()
                // Recheck real inputs/output/installation AFTER all materialization/generation hooks.
                permit.verifyOriginal(checkpoint)
                materialized.verifyInputs(checkpoint); materialized.verifyOutput(sealedOutput, checkpoint)
                verifyRetained(installation, checkpoint)
                generationPermit.checkLive(); checkpoint()
            }

            var producedCandidate: ModelSuccessfulJvmOutput? = null
            val runtimePermit = OwnedApplicationJobPermit<ProviderResult>(session, job, permit.originalDeadline,
                verifyBeforeWrite = { verifyBound(false) },
                validateResult = { result ->
                    checkpoint()
                    Wire.matchResult(request.launch, hello, job, result)
                    val observation = ModelWire.decodeObservation(result.payload())
                    ModelWire.matchObservation(request.launch, request.generation, job, body, result, observation)
                    permit.verifyOriginal(checkpoint); materialized.verifyInputs(checkpoint)
                    materialized.sealOutput(checkpoint); verifyBound(true)
                    val entireDetachedResult = renderModelObservation(permit.input, permit.capture.sourceTexts,
                        materialized.sourceRoot, request.installation.digest, permit.prepared.bindingIdentity.digest,
                        result, observation, providerStartedNanos, checkpoint, permit.prepared.binding)
                    val preview = permit.prepared.binding.producedResolution?.preview
                    if (preview != null && observation.compilerCode() == 0 &&
                        permit.prepared.binding.compilationId == preview.graph.producerCompilationId) {
                        producedCandidate = ModelSuccessfulJvmOutput(permit, materialized, result, observation,
                            entireDetachedResult, materialized.producedMembers())
                    }
                    checkpoint()
                    entireDetachedResult
                },
                verifyBeforePublication = { verifyBound(true) })
            val result = session.submit(runtimePermit, cancellation)
            when (result) {
                is OwnedApplicationJobResult.Accepted -> {
                    checkpoint()
                    // State-only gate, then one atomic publication of the prebuilt candidate.
                    // No IO, parsing, metrics or provider-row changes after the acceptance gate.
                    finishAcceptedModelOutput(producedCandidate,
                        finishAccepted = { lifecycle.finishAccepted(permit.ticket) },
                        publish = permit::publishSuccessfulOutput)
                }
                is OwnedApplicationJobResult.Unavailable -> lifecycle.failActive(permit.ticket)
            }
            return result
        } catch (failure: CancellationException) {
            if (admitted) lifecycle.failActive(permit.ticket)
            throw failure
        } catch (failure: CompilerApplicationProblem) {
            if (admitted) lifecycle.failActive(permit.ticket)
            throw failure
        } catch (_: Throwable) {
            if (admitted) lifecycle.failActive(permit.ticket)
            liveFailure("compiler_model_live_application_failed")
        }
    }

    private fun reserveRetainedBudget(permit: ModelCompilerAttemptPermit) {
        liveCheck(permit.capture.snapshotBytes <= ModelWire.MAX_GENERATION_SNAPSHOT_BYTES - materializations.values.sumOf { it.snapshotBytes },
            "compiler_model_live_generation_snapshot_limit")
        liveCheck(permit.capture.dependencyBytes <= ModelWire.MAX_GENERATION_DEPENDENCY_BYTES - materializations.values.sumOf { it.dependencyBytes },
            "compiler_model_live_generation_dependency_limit")
        liveCheck(materializations.values.sumOf { it.outputBytes() } <= ModelWire.MAX_GENERATION_OUTPUT_BYTES - ModelWire.MAX_JOB_OUTPUT_BYTES &&
            materializations.values.sumOf { it.outputEntries() } <= ModelWire.MAX_GENERATION_OUTPUT_FILES - ModelWire.MAX_JOB_OUTPUT_FILES,
            "compiler_model_live_generation_output_limit")
    }

    private fun verifyRetained(installation: ModelGenerationCapture, checkpoint: () -> Unit) {
        checkpoint(); installation.verify(checkpoint)
        val root = livePath(request.launch.generationRoot().path())
        liveCheck(liveNames(root.resolve("jobs"), request.launch.limits().maxJobs()) == materializations.keys.map { it.toString() }.toSet(),
            "compiler_model_live_generation_root_drift")
        var snapshots = 0L; var dependencies = 0L; var outputs = 0L; var outputEntries = 0L
        materializations.toSortedMap().values.forEach { value ->
            checkpoint(); value.verifyInputs(checkpoint)
            if (value.outputSealed()) value.verifyOutput(true, checkpoint)
            snapshots += value.snapshotBytes; dependencies += value.dependencyBytes
            outputs += value.outputBytes(); outputEntries += value.outputEntries()
            liveCheck(snapshots <= ModelWire.MAX_GENERATION_SNAPSHOT_BYTES && dependencies <= ModelWire.MAX_GENERATION_DEPENDENCY_BYTES &&
                outputs <= ModelWire.MAX_GENERATION_OUTPUT_BYTES && outputEntries <= ModelWire.MAX_GENERATION_OUTPUT_FILES,
                "compiler_model_live_generation_limit")
        }
        CompilerTree.capture(root.resolve("tmp"), ModelWire.MAX_TEMP_FILES, ModelWire.MAX_TEMP_BYTES, false, checkpoint)
        checkpoint()
    }

    companion object {
        fun open(request: ModelCompilerGenerationRequest,
            authority: KotlinJvmModelApplicationAuthority = KotlinJvmModelApplicationAuthority.DENY,
            cancellation: CancellationToken = CancellationToken.NONE): ModelCompilerApplicationOpenResult {
            fun unavailable(code: String) = ModelCompilerApplicationOpenResult.Unavailable(listOf(code))
            val permit = try { authority.authorizeGeneration(request) }
            catch (_: Exception) { return unavailable("compiler_model_live_generation_authority_failed") }
                ?: return unavailable("compiler_model_live_generation_not_authorized")
            var admitted = false
            try {
                permit.consume(request)
                admitted = true
                val checkpoint = {
                    permit.checkLive()
                    if (Thread.currentThread().isInterrupted) liveFailure("compiler_live_interrupted")
                    if (cancellation.isCancelled()) throw CancellationException("compiler_model_live_cancelled")
                    permit.checkLive()
                    if (Thread.currentThread().isInterrupted) liveFailure("compiler_live_interrupted")
                }
                checkpoint(); permit.beforeStart(); checkpoint()
                val capture = ModelGenerationCapture.capture(request, checkpoint)
                val runtimePermit = OwnedApplicationGenerationPermit(request.owner, request.launch, request.originalDeadline,
                    verifyBeforeStart = { checkpoint(); permit.beforeStart(); capture.verify(checkpoint); checkpoint() },
                    validateHello = { hello ->
                        checkpoint(); ModelWire.matchHello(request.launch, request.generation, hello)
                        permit.verify(); capture.verify(checkpoint); checkpoint()
                    },
                    verifyLive = { checkpoint(); permit.verify(); capture.verify(checkpoint); checkpoint() })
                return when (val opened = OwnedApplicationSessions.open(runtimePermit, cancellation)) {
                    is OwnedApplicationOpenResult.Unavailable -> {
                        permit.invalidate()
                        ModelCompilerApplicationOpenResult.Unavailable(opened.reasons.map { it.code }, opened.cleanup)
                    }
                    is OwnedApplicationOpenResult.Ready -> {
                        try {
                            val application = KotlinJvmModelCompilerApplication(request, permit, opened.session, capture)
                            val ready = ModelCompilerApplicationOpenResult.Ready(application)
                            permit.bindCancellation(application::cancelRevokedGeneration)
                            checkpoint(); application.lifecycle.openAdmission(); ready
                        } catch (_: Exception) {
                            permit.invalidate(); opened.session.cancel()
                            ModelCompilerApplicationOpenResult.Unavailable(listOf("compiler_model_live_open_acceptance_failed"), opened.session.retire())
                        }
                    }
                }
            } catch (_: CancellationException) {
                if (admitted) permit.revoke()
                return unavailable("compiler_model_live_cancelled")
            } catch (failure: CompilerApplicationProblem) {
                if (admitted) permit.revoke()
                return unavailable(failure.code)
            } catch (_: Exception) {
                if (admitted) permit.revoke()
                return unavailable("compiler_model_live_generation_verification_failed")
            }
        }

        /** Pure binding/lifecycle controls. Execution is HARD-DISABLED; synthetic sessions never submit. */
        internal fun bindingControls(request: ModelCompilerGenerationRequest, permit: ModelCompilerGenerationPermit,
            neverSubmitSession: OwnedApplicationSession): KotlinJvmModelCompilerApplication {
            liveCheck(permit.request === request, "compiler_model_live_generation_binding_mismatch")
            val application = KotlinJvmModelCompilerApplication(request, permit, neverSubmitSession, null)
            permit.bindCancellation(application::cancelRevokedGeneration)
            permit.checkLive(); application.lifecycle.openAdmission()
            return application
        }
    }
}

/** Ordering helper, not a producer/Accepted factory. Production calls it ONLY in the real runtime
 * Accepted branch, with the real lifecycle gate and the exact permit's atomic reference. Its
 * opaque-value controls can test gate failure/null-candidate behavior without fake compiler pins,
 * synthetic Accepted objects, actual output capabilities or a test-only acceptance bypass.
 */
internal inline fun <T : Any> finishAcceptedModelOutput(candidate: T?, finishAccepted: () -> Unit, publish: (T) -> Unit) {
    finishAccepted()
    candidate?.let(publish)
}

/** Original installation and admitted private copies are checked independently at each fence. */
internal class ModelGenerationCapture private constructor(
    private val request: ModelCompilerGenerationRequest,
    private val roots: List<CompilerRootPin>,
    private val files: List<CompilerFilePin>,
) {
    fun verify(checkpoint: () -> Unit) {
        checkpoint(); request.installation.verify(checkpoint)
        roots.forEach { checkpoint(); it.verify() }
        val root = livePath(request.launch.generationRoot().path())
        liveCheck(liveNames(root, 5) == setOf("bridge.jar", "launch.bin", "compiler", "jobs", "tmp"), "compiler_model_live_generation_root_drift")
        liveCheck(liveNames(root.resolve("compiler"), ModelWire.MAX_ARTIFACTS) ==
            request.generation.compilerArtifacts().indices.map { "$it.jar" }.toSet(), "compiler_model_live_compiler_member_drift")
        files.forEach { it.verify(checkpoint) }
        roots.forEach { checkpoint(); it.verify() }; checkpoint()
    }

    companion object {
        fun capture(request: ModelCompilerGenerationRequest, checkpoint: () -> Unit): ModelGenerationCapture {
            checkpoint(); ModelWire.matchGeneration(request.launch, request.generation)
            val installed = request.installation.installationView()
            val generation = request.generation
            liveCheck(installed.runtime.root.toString() == generation.jdk().home().path() &&
                installed.runtime.javaExecutable.toString() == generation.jdk().executable() &&
                installed.runtime.executableDigest == generation.jdk().executableDigest() &&
                installed.runtime.digest == generation.jdk().runtimeDigest() && installed.toolchainDigest == request.installation.digest &&
                installed.compilerClasspath.size == generation.compilerArtifacts().size, "compiler_model_live_installation_mismatch")
            val root = livePath(request.launch.generationRoot().path())
            val roots = listOf(CompilerRootPin.capture(root, request.launch.generationRoot().fileKey()),
                CompilerRootPin.capture(livePath(generation.jdk().home().path()), generation.jdk().home().fileKey()),
                CompilerRootPin.capture(root.resolve("compiler")), CompilerRootPin.capture(root.resolve("jobs")), CompilerRootPin.capture(root.resolve("tmp")))
            liveCheck(roots.map { it.root.fileKey() }.distinct().size == roots.size, "compiler_model_live_root_alias")
            liveCheck(liveNames(root.resolve("jobs"), 0).isEmpty(), "compiler_model_live_generation_not_fresh")
            val files = ArrayList<CompilerFilePin>()
            installed.compilerClasspath.forEachIndexed { index, artifact ->
                checkpoint()
                val admitted = generation.compilerArtifacts()[index]
                val original = artifact.identity
                liveCheck(artifact.ordinal == index && original.coordinate == admitted.coordinate() &&
                    livePath(original.path).fileName.toString() == admitted.fileName() && original.digest == admitted.digest() &&
                    artifact.size == admitted.size(), "compiler_model_live_installation_mismatch")
                files += CompilerFilePin.capture(livePath(original.path), original.digest, artifact.size, checkpoint = checkpoint)
                files += CompilerFilePin.capture(root.resolve("compiler/$index.jar"), admitted.digest(), admitted.size(), admitted.fileKey(), checkpoint)
            }
            files += CompilerFilePin.capture(root.resolve("bridge.jar"), generation.bridge().digest(), generation.bridge().size(),
                generation.bridge().fileKey(), checkpoint)
            val encoded = Wire.encodeLaunch(request.launch)
            files += CompilerFilePin.capture(root.resolve("launch.bin"), Wire.sha256(encoded), encoded.size.toLong(), checkpoint = checkpoint)
            liveCheck(files.map { it.fileKey }.distinct().size == files.size, "compiler_model_live_artifact_alias")
            return ModelGenerationCapture(request, Collections.unmodifiableList(roots), Collections.unmodifiableList(files)).also { it.verify(checkpoint) }
        }
    }
}

package dev.ktchecker.cli

import dev.ktchecker.compiler.live.CompilerApplicationNamespace
import dev.ktchecker.compiler.live.model.ModelCompilerApplicationOpenResult
import dev.ktchecker.compiler.live.model.ModelCompilerGenerationPermit
import dev.ktchecker.compiler.live.model.ModelCompilerGenerationRequest
import dev.ktchecker.contracts.CancellationToken
import dev.ktchecker.contracts.Digests
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import dev.ktchecker.workerprotocol.ApplicationWire as Wire
import dev.ktchecker.workerprotocol.CompilerModelWire as ModelWire
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*

/** No request field, PATH lookup or test fixture can select the production worker archive. */
internal const val OWNER_MODEL_WORKER = "providers/compiler-model/ktchecker-compiler-model-application-preview-1.jar"

/**
 * One command-owned production generation. Construction is source/copy preparation, not a launch.
 * open() alone calls the real owner runtime; retire() alone records its original retirement call.
 * Both are single-use locally and retain failure evidence. No process probing, direct signals,
 * fake sessions, implicit relaunch, foreign-generation retirement or refreshed clock is present.
 */
internal class OwnerModelGeneration(
    private val workbench: Workbench,
    private val runtime: OwnerLiveModelRuntime,
    namespace: CompilerApplicationNamespace,
    root: Path,
    private val clock: OwnerModelCommandClock,
    private val custody: OwnerModelCommandCustody,
) {
    private val originalDeadline: OwnedApplicationDeadline get() = clock.originalDeadline
    private val openAttempted = AtomicBoolean()
    private val retirement: OwnerModelGenerationRetirement
    private val quarantined = AtomicBoolean()
    private val roots: List<OwnerModelRootPin>
    private val originalBridge: OwnerModelFilePin
    private val compilerSources: List<OwnerModelFilePin>
    private val files: List<OwnerModelFilePin>
    val request: ModelCompilerGenerationRequest
    private val permit: ModelCompilerGenerationPermit
    private val cancellation = CancellationToken { quarantined.get() || clock.cancelled() }
    private var opened: OwnerLiveGenerationStatus? = null
    private var openFailure = false

    init {
        checkpoint()
        ownerModelRequire(runtime.ownerId == workbench.state.ownerId && namespace.ownerId == runtime.ownerId &&
            root.startsWith(workbench.state.root) && root != workbench.state.root &&
            runtime.reviewTimeoutMillis == originalDeadline.durationMillis)
        workbench.verifyOwnerCode(); checkpoint()
        val home = Workbench.distributionHome()
            ?: throw CliProblem("owner_model_distribution_unavailable", "Use the installed distribution's fixed model worker.")
        val bridgePath = ownerModelPath(home.resolve(OWNER_MODEL_WORKER))
        originalBridge = OwnerModelFilePin.capture(bridgePath, ModelWire.MAX_BRIDGE_BYTES, ::checkpoint)
        ownerModelRequire(originalBridge.digest == runtime.workerBridgeDigest, "owner_model_worker_changed")
        val installed = runtime.installation.installationView()
        runtime.installation.verify(::checkpoint)
        ownerModelRequire(installed.compilerClasspath.size in 1..ModelWire.MAX_ARTIFACTS &&
            installed.compilerClasspath.sumOf { it.size } <= ModelWire.MAX_ARTIFACT_TOTAL_BYTES)
        val generationRoot = ownerModelFreshDirectory(root)
        val compilerRoot = ownerModelFreshDirectory(root.resolve("compiler"))
        val jobsRoot = ownerModelFreshDirectory(root.resolve("jobs"))
        val tmpRoot = ownerModelFreshDirectory(root.resolve("tmp"))
        roots = listOf(generationRoot, compilerRoot, jobsRoot, tmpRoot,
            OwnerModelRootPin.capture(installed.runtime.root))
        val bridge = originalBridge.copyTo(root.resolve("bridge.jar"), ::checkpoint)
        compilerSources = installed.compilerClasspath.mapIndexed { index, artifact ->
            ownerModelRequire(artifact.ordinal == index)
            OwnerModelFilePin.capture(ownerModelPath(Path.of(artifact.identity.path)), ModelWire.MAX_ARTIFACT_BYTES,
                ::checkpoint).also { ownerModelRequire(it.digest == artifact.identity.digest && it.size == artifact.size) }
        }
        val copies = compilerSources.mapIndexed { index, source -> source.copyTo(root.resolve("compiler/$index.jar"), ::checkpoint) }
        val originalKeys = compilerSources.map { it.fileKey } + originalBridge.fileKey
        val copiedKeys = copies.map { it.fileKey } + bridge.fileKey + roots.take(4).map { it.fileKey }
        ownerModelRequire(copiedKeys.distinct().size == copiedKeys.size && copiedKeys.none { it in originalKeys },
            "owner_model_generation_alias")
        val descriptor = ModelWire.Generation(ModelWire.PRODUCED_PROFILE, ModelWire.PRODUCED_API, ModelWire.COMPILER_VERSION,
            ModelWire.Jdk(roots.last().wire, installed.runtime.javaExecutable.toString(), installed.runtime.executableDigest,
                installed.runtime.digest, 21),
            ModelWire.Artifact("ktchecker:model-worker-bridge", "bridge.jar", bridge.digest, bridge.size, bridge.fileKey),
            installed.compilerClasspath.mapIndexed { index, artifact ->
                ModelWire.Artifact(artifact.identity.coordinate, Path.of(artifact.identity.path).fileName.toString(),
                    artifact.identity.digest, artifact.size, copies[index].fileKey)
            })
        val implementation = ownerProducedDigest(buildJsonObject {
            put("schema", "ktchecker/owner-model-command-generation/1")
            put("runtime", runtime.implementationIdentity); put("ownerCode", workbench.ownerCodeDigest)
            put("profile", descriptor.profile()); put("installation", installed.toolchainDigest)
        })
        val identity = Wire.Identity(UUID.randomUUID().toString().replace("-", ""), runtime.owner.identity, namespace.digest,
            implementation, bridge.digest, ModelWire.compilerClosureDigest(descriptor), ModelWire.toolchainDigest(descriptor),
            descriptor.apiVersion(), descriptor.profile())
        val launch = Wire.Launch(identity, Wire.Limits(4, 1_048_576, 1_048_576), generationRoot.wire, root.toString(),
            ModelWire.command(generationRoot.wire, descriptor), ModelWire.environment(generationRoot.wire, descriptor),
            ModelWire.encodeGeneration(descriptor))
        val launchPin = ownerModelWrite(root.resolve("launch.bin"), Wire.encodeLaunch(launch), Wire.MAX_LAUNCH_BYTES, ::checkpoint)
        files = copies + bridge + launchPin
        request = ModelCompilerGenerationRequest(runtime.owner, namespace, runtime.installation, launch, originalDeadline)
        retirement = OwnerModelGenerationRetirement(launch.identity().generation(), namespace.digest)
        permit = ModelCompilerGenerationPermit(request, verifyBeforeStart = {
            ownerModelRequire(ownerModelNames(jobsRoot.path, 0).isEmpty() && ownerModelNames(tmpRoot.path, 0).isEmpty(),
                "owner_model_generation_not_fresh")
            verify()
        }, verifyLive = ::verify)
        custody.bindGeneration(request)
        verify()
    }

    fun open(): OwnerLiveGenerationStatus {
        checkpoint()
        ownerModelRequire(openAttempted.compareAndSet(false, true), "owner_model_generation_open_replayed")
        return try { runtime.open(request, permit, custody, cancellation).also { opened = it } }
        catch (failure: Throwable) { openFailure = true; throw failure }
    }

    fun verify() {
        checkpoint(); workbench.verifyOwnerCode(); checkpoint()
        runtime.installation.verify(::checkpoint)
        originalBridge.verify(::checkpoint)
        compilerSources.forEach { it.verify(::checkpoint) }
        roots.forEach { it.verify() }
        files.forEach { it.verify(::checkpoint) }
        ownerModelRequire(ownerModelNames(roots.first().path, 5) == setOf("compiler", "jobs", "tmp", "bridge.jar", "launch.bin") &&
            ownerModelNames(roots[1].path, compilerSources.size) == compilerSources.indices.map { "$it.jar" }.toSet(),
            "owner_model_generation_topology_changed")
        roots.forEach { it.verify() }; checkpoint()
    }

    /** Only explicit command-owner cleanup calls this once, even after original budget expiry. */
    fun retire(): OwnerLiveGenerationStatus = retirement.retire(openAttempted.get()) { runtime.retire(request) }

    val retirementVerified: Boolean get() = retirement.verified
    val wasOpened: Boolean get() = openAttempted.get()
    val retirementWasAttempted: Boolean get() = retirement.attempted

    /** State-only denial after the one explicit retirement failed; never another cleanup attempt. */
    fun quarantine() { quarantined.set(true) }

    /** Detached real call/receipt evidence only. Neither CLOSED nor HELLO is a compiler-job success. */
    fun evidence(): JsonObject = buildJsonObject {
        val retired = retirement.returned
        put("generationId", request.launch.identity().generation()); put("profile", request.generation.profile())
        put("launchDigest", request.launchDigest); put("installationDigest", runtime.installation.digest)
        put("originalDeadlineIdentity", originalDeadline.identity); put("originalDeadlineMillis", originalDeadline.durationMillis)
        put("openAttempted", openAttempted.get()); put("openThrew", openFailure)
        put("quarantined", quarantined.get())
        put("retirementAttempted", retirement.attempted); put("retirementThrew", retirement.threw)
        put("state", retired?.state ?: opened?.state ?: if (openFailure) "open_failed_retained" else "prepared_not_opened")
        put("openReason", opened?.reason?.let(::JsonPrimitive) ?: JsonNull)
        val result = opened?.opened
        (result as? ModelCompilerApplicationOpenResult.Ready)?.let { actual ->
            putJsonObject("actualHello") {
                put("digest", Wire.helloDigest(actual.application.hello))
                put("loaderToken", actual.application.hello.loaderToken())
                put("observedVersion", actual.application.hello.observedVersion())
                put("originsVerified", actual.application.hello.originsVerified())
                put("initialResetObserved", actual.application.hello.initialResetObserved())
            }
        }
        put("openReasons", JsonArray((result as? ModelCompilerApplicationOpenResult.Unavailable)?.reasons.orEmpty().map(::JsonPrimitive)))
        put("retirementDisposition", retired?.retirement?.disposition?.name?.let(::JsonPrimitive) ?: JsonNull)
        put("retirementReasons", JsonArray(retired?.retirement?.reasons.orEmpty().map { JsonPrimitive(it.code) }))
        put("retirementTransportReasons", JsonArray(retired?.retirement?.transportReasonCodes.orEmpty().map(::JsonPrimitive)))
        put("qualified", false); put("usefulComplete", false)
    }

    private fun checkpoint() {
        ownerModelRequire(!quarantined.get(), "owner_model_generation_quarantined")
        clock.checkpoint()
    }
}

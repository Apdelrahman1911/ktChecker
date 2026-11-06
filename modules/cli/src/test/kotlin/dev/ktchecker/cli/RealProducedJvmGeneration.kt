package dev.ktchecker.cli

import dev.ktchecker.compiler.KotlinJvmToolchain
import dev.ktchecker.compiler.live.CompilerApplicationNamespace
import dev.ktchecker.compiler.live.model.*
import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.OwnerFullCatalog1665
import dev.ktchecker.providers.runtime.*
import dev.ktchecker.workerprotocol.ApplicationWire as Wire
import dev.ktchecker.workerprotocol.CompilerModelWire as ModelWire
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.*

/** New fixed test owner, not OwnerLiveModelRuntime/Workbench activation. Pure exact lookups only. */
internal class RealProducedJvmAuthority : KotlinJvmModelApplicationAuthority {
    private val lock = Any()
    private var generation: ModelCompilerGenerationPermit? = null
    private val attempts = linkedMapOf<String, ModelCompilerAttemptPermit>()
    fun register(permit: ModelCompilerGenerationPermit) = synchronized(lock) {
        realExpect(generation == null, "real_produced_generation_reenrollment")
        generation = permit
    }
    fun register(permit: ModelCompilerAttemptPermit) = synchronized(lock) {
        realExpect(permit.providerInputKey !in attempts, "real_produced_attempt_reenrollment")
        attempts[permit.providerInputKey] = permit
    }
    override fun authorizeGeneration(request: ModelCompilerGenerationRequest): ModelCompilerGenerationPermit? =
        synchronized(lock) { generation?.takeIf { it.request === request } }
    override fun authorizeAttempt(provider: KotlinJvmModelLiveCompilerProvider,
        request: ModelCompilerAttemptRequest): ModelCompilerAttemptPermit? = synchronized(lock) {
        attempts[request.analysisKey]?.takeIf { it.provider === provider && it.accepts(request.input) }
    }
}

/** One NEW actual compiler application, one selected installation, at most four independent jobs.
 * No implicit cancellation/retirement, recovery, retries, process inspection or fake sessions.
 */
internal class RealProducedJvmGeneration(
    val installation: KotlinJvmToolchain,
    val namespace: CompilerApplicationNamespace,
    val root: Path,
    bridgeSource: RealModelFilePin,
    val startup: RealModelStartup,
    selectionDigest: String,
    originalDeadline: OwnedApplicationDeadline,
    private val runRoot: Path,
    val authority: RealProducedJvmAuthority,
) {
    private val owner = OwnedApplicationOwner()
    val request: ModelCompilerGenerationRequest
    val permit: ModelCompilerGenerationPermit
    private val roots: Map<Path, String>
    private val files: List<RealModelFilePin>
    private var opened: ModelCompilerApplicationOpenResult? = null
    private var retirement: OwnedApplicationRetirement? = null
    private var retireAttempted = false
    val application: KotlinJvmModelCompilerApplication
        get() = (opened as? ModelCompilerApplicationOpenResult.Ready)?.application
            ?: throw RealModelProblem("real_produced_generation_not_ready")
    val hello: Wire.Hello get() = application.hello
    val retirementVerified: Boolean get() = retirement?.disposition == OwnedApplicationCleanupDisposition.CLOSED

    init {
        realExpect(!originalDeadline.isExpired(), "real_produced_generation_original_deadline")
        realDirectory(root)
        listOf("compiler", "jobs", "tmp").forEach { realDirectory(root.resolve(it)) }
        val bridge = realCopy(bridgeSource, root.resolve("bridge.jar"))
        val installed = installation.installationView()
        val copies = installed.compilerClasspath.mapIndexed { index, artifact ->
            realExpect(artifact.ordinal == index, "real_produced_compiler_order")
            realCopy(RealModelFilePin.capture(realPath(artifact.identity.path), artifact.identity.digest, artifact.size),
                root.resolve("compiler/$index.jar"))
        }
        val descriptor = ModelWire.Generation(ModelWire.PRODUCED_PROFILE, ModelWire.PRODUCED_API, ModelWire.COMPILER_VERSION,
            ModelWire.Jdk(Wire.Root(installed.runtime.root.toString(), realKey(installed.runtime.root, true)),
                installed.runtime.javaExecutable.toString(), installed.runtime.executableDigest, installed.runtime.digest, 21),
            ModelWire.Artifact("ktchecker:model-worker-bridge", "bridge.jar", bridge.digest, bridge.bytes, bridge.fileKey),
            installed.compilerClasspath.mapIndexed { index, artifact -> ModelWire.Artifact(artifact.identity.coordinate,
                realPath(artifact.identity.path).fileName.toString(), artifact.identity.digest, artifact.size, copies[index].fileKey) })
        val implementation = Digests.sha256(Digests.canonical(buildJsonObject {
            put("schema", "ktchecker/real-produced-jvm-test-owner/1"); put("selection", selectionDigest)
            put("installedCodeImage", startup.image.digest); put("profile", ModelWire.PRODUCED_PROFILE)
            put("compilerChildCap", 1024); put("parentCap", 4096); put("requiredParentRules", OwnerFullCatalog1665.RULE_COUNT)
            put("catalogContract", OwnerFullCatalog1665.ID); put("catalogRuleIdsDigest", OwnerFullCatalog1665.RULE_IDS_DIGEST)
        }))
        val identity = Wire.Identity(UUID.randomUUID().toString().replace("-", ""), owner.identity, namespace.digest,
            implementation, bridge.digest, ModelWire.compilerClosureDigest(descriptor), ModelWire.toolchainDigest(descriptor),
            descriptor.apiVersion(), descriptor.profile())
        val registered = Wire.Root(root.toString(), realKey(root, true))
        val launch = Wire.Launch(identity, Wire.Limits(4, 1_048_576, 1_048_576), registered, root.toString(),
            ModelWire.command(registered, descriptor), ModelWire.environment(registered, descriptor), ModelWire.encodeGeneration(descriptor))
        val bytes = Wire.encodeLaunch(launch); realWrite(root.resolve("launch.bin"), bytes)
        files = copies + bridge + RealModelFilePin.capture(root.resolve("launch.bin"), Wire.sha256(bytes), bytes.size.toLong())
        roots = listOf(root, root.resolve("compiler"), root.resolve("jobs"), root.resolve("tmp"), installed.runtime.root)
            .associateWith { realKey(it, true) }
        request = ModelCompilerGenerationRequest(owner, namespace, installation, launch, originalDeadline)
        permit = ModelCompilerGenerationPermit(request, verifyBeforeStart = {
            realExpect(Files.list(root.resolve("jobs")).use { !it.findAny().isPresent }, "real_produced_generation_not_fresh")
            verify(); realTreeBoundary(runRoot)
        }, verifyLive = ::verify)
        authority.register(permit)
    }

    fun open(): ModelCompilerApplicationOpenResult {
        realExpect(opened == null, "real_produced_generation_open_replayed")
        return KotlinJvmModelCompilerApplication.open(request, authority, CancellationToken.NONE).also { opened = it }
    }
    fun verify() {
        startup.verify(); installation.verify()
        roots.forEach { (path, key) -> realExpect(realKey(path, true) == key, "real_produced_generation_root_changed") }
        files.forEach { it.verify() }
        realExpect(Files.list(root).use { paths -> paths.map { it.fileName.toString() }.toList().toSet() } ==
            setOf("compiler", "jobs", "tmp", "bridge.jar", "launch.bin"), "real_produced_generation_topology_changed")
    }
    /** Called ONLY by explicit owner.retire after every selected control is settled and checked.
     * CLOSED is the actual runtime's independent transport/supervisor receipt, not an owner guess
     * or compiler-job evidence. This method neither signals processes nor retries retirement.
     */
    fun retire(): OwnedApplicationRetirement {
        realExpect(!retireAttempted, "real_produced_retirement_replayed")
        retireAttempted = true
        return application.retire().also { retirement = it }
    }
    fun evidence(): JsonObject = buildJsonObject {
        put("generationId", request.launch.identity().generation()); put("profile", request.generation.profile())
        put("launchDigest", request.launchDigest); put("installedToolchainDigest", installation.digest)
        put("generationOriginalDeadlineIdentity", request.originalDeadline.identity)
        put("generationOriginalDeadlineMillis", request.originalDeadline.durationMillis)
        put("state", when {
            retirementVerified -> "explicit_retirement_verified"
            retireAttempted -> "retirement_unverified_retained"
            opened is ModelCompilerApplicationOpenResult.Ready -> "ready"
            opened != null -> "unavailable_retained"
            else -> "not_opened"
        })
        (opened as? ModelCompilerApplicationOpenResult.Ready)?.let { ready -> putJsonObject("actualHello") {
            put("digest", Wire.helloDigest(ready.application.hello)); put("loaderToken", ready.application.hello.loaderToken())
            put("version", ready.application.hello.observedVersion()); put("originsVerified", ready.application.hello.originsVerified())
            put("initialResetObserved", ready.application.hello.initialResetObserved())
        } }
        put("openReasons", JsonArray((opened as? ModelCompilerApplicationOpenResult.Unavailable)?.reasons.orEmpty().map(::JsonPrimitive)))
        put("retirementDisposition", retirement?.disposition?.name?.let(::JsonPrimitive) ?: JsonNull)
        put("retirementReasons", JsonArray(retirement?.reasons.orEmpty().map { JsonPrimitive(it.code) }))
        put("retirementTransportReasons", JsonArray(retirement?.transportReasonCodes.orEmpty().map(::JsonPrimitive)))
        put("qualified", false); put("productionWorkbenchActivated", false)
    }
}

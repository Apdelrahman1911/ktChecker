package dev.ktchecker.cli

import dev.ktchecker.compiler.*
import dev.ktchecker.compiler.live.CompilerApplicationNamespace
import dev.ktchecker.compiler.live.model.*
import dev.ktchecker.config.ConfigStore
import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.*
import dev.ktchecker.projectmodel.*
import dev.ktchecker.providers.runtime.*
import dev.ktchecker.reviewcontroller.OwnerPlanAssembler
import dev.ktchecker.snapshot.SnapshotStore
import dev.ktchecker.workerprotocol.ApplicationWire as Wire
import dev.ktchecker.workerprotocol.CompilerModelWire as ModelWire
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.*

/** Actual installed code/resource image and native bundle inspection, not source-to-binary proof. */
internal class RealModelStartup private constructor(val image: CodeImage, val supervisor: OwnedSupervisorIdentity) {
    fun verify() {
        image.verify()
        val actual = OwnedProcessContainment.inspect()
        realExpect(actual.available && actual.identity == supervisor, "real_model_supervisor_image_changed")
    }
    fun verifyCompletion(actual: ProcessOutcome) {
        verify()
        realExpect(actual.executionConfirmed && actual.supervisorIdentity == supervisor, "real_model_exporter_supervisor_unverified")
    }
    fun evidence(): JsonObject = buildJsonObject {
        put("codeImageDigest", image.digest)
        putJsonArray("codeRoots") { image.roots.forEach { root -> add(buildJsonObject {
            put("kind", root.kind); put("digest", root.digest); put("entries", root.entryCount); put("bytes", root.byteCount)
            put("selectors", JsonArray(root.selectors.map(::JsonPrimitive)))
        }) } }
        putJsonObject("supervisor") {
            put("protocolVersion", supervisor.protocolVersion); put("target", supervisor.target)
            put("sourceDigest", supervisor.sourceDigest); put("binaryDigest", supervisor.binaryDigest)
        }
        put("loadedMemoryOrSourceToBinaryAttested", false)
    }
    companion object {
        fun capture(): RealModelStartup {
            val classes = listOf(RealModelOwnerMain::class.java, Workbench::class.java, RpcServer::class.java,
                OwnerLiveModelRuntime::class.java, AnalysisInput::class.java, SnapshotStore::class.java,
                KernelService::class.java, ConfigStore::class.java, IsolatedProcessRunner::class.java,
                ProjectModelImporter::class.java, ModelJvmArgumentOrigin::class.java, OwnerPlanAssembler::class.java,
                KotlinJvmToolchain::class.java, KotlinJvmModelBinding::class.java, KotlinJvmModelCompilerApplication::class.java,
                dev.ktchecker.providers.detekt.DetektProvider::class.java, dev.ktchecker.providers.ktlint.KtlintProvider::class.java,
                dev.ktchecker.providers.lint.AndroidLintProvider::class.java, dev.ktchecker.providers.native.KotlinNativeProvider::class.java,
                Wire::class.java, ModelWire::class.java, Unit::class.java, kotlinx.serialization.KSerializer::class.java, Json::class.java,
                Class.forName("org.sqlite.JDBC", false, Workbench::class.java.classLoader))
            val resources = mapOf(
                "catalog" to CodeImage.resourceRoot(Catalog::class.java, "/catalog/rules-core.json"),
                "process-supervisor" to CodeImage.resourceRoot(IsolatedProcessRunner::class.java, OwnedProcessContainment.MANIFEST_RESOURCE),
                "compiler-mappings" to CodeImage.resourceRoot(CompilerRuleRegistry::class.java, "/compiler-rule-map.json"),
                "project-model-exporter" to CodeImage.resourceRoot(GradleModelExporter::class.java, "/${GradleModelExporter.SCRIPT}"),
                "detekt-mappings" to CodeImage.resourceRoot(dev.ktchecker.providers.detekt.DetektCatalog::class.java, "/detekt-rule-map.json"),
                "detekt-artifacts" to CodeImage.resourceRoot(dev.ktchecker.providers.detekt.DetektCatalog::class.java, "/detekt-artifacts.json"),
                "ktlint-mappings" to CodeImage.resourceRoot(dev.ktchecker.providers.ktlint.KtlintCatalog::class.java, "/ktlint-rule-map.json"),
                "ktlint-artifacts" to CodeImage.resourceRoot(dev.ktchecker.providers.ktlint.KtlintCatalog::class.java, "/ktlint-artifacts.json"),
                "lint-mappings" to CodeImage.resourceRoot(dev.ktchecker.providers.lint.LintCatalog::class.java, "/dev/ktchecker/providers/lint/android-lint-catalog.json"),
                "native-mappings" to CodeImage.resourceRoot(dev.ktchecker.providers.native.NativeCatalog::class.java, "/dev/ktchecker/providers/native/kotlin-native-catalog.json"))
            val image = CodeImage.capture(classes, resources)
            val actual = OwnedProcessContainment.inspect()
            realExpect(actual.available && actual.identity != null, "real_model_supervisor_unavailable")
            return RealModelStartup(image, requireNotNull(actual.identity)).also { it.verify() }
        }
    }
}

/** One physical model application. No fallback, retries, recovery or implicit retirement. */
internal class RealModelGeneration(
    val runtime: OwnerLiveModelRuntime,
    namespace: CompilerApplicationNamespace,
    val root: Path,
    bridgeSource: RealModelFilePin,
    val startup: RealModelStartup,
    selectionDigest: String,
    originalDeadline: OwnedApplicationDeadline,
    private val runRoot: Path,
) {
    val request: ModelCompilerGenerationRequest
    val permit: ModelCompilerGenerationPermit
    val installedFiles: List<RealModelFilePin>
    private val roots: Map<Path, String>
    private var actualOpen: OwnerLiveGenerationStatus? = null
    val hello: Wire.Hello get() = (requireNotNull(actualOpen).opened as ModelCompilerApplicationOpenResult.Ready).application.hello

    init {
        realExpect(!originalDeadline.isExpired(), "real_model_generation_original_deadline")
        realDirectory(root)
        listOf("compiler", "jobs", "tmp").forEach { realDirectory(root.resolve(it)) }
        val bridge = realCopy(bridgeSource, root.resolve("bridge.jar"))
        val installed = runtime.installation.installationView()
        val copies = installed.compilerClasspath.mapIndexed { index, item ->
            realExpect(item.ordinal == index, "real_model_generation_compiler_order")
            realCopy(RealModelFilePin.capture(realPath(item.identity.path), item.identity.digest, item.size), root.resolve("compiler/$index.jar"))
        }
        val descriptor = ModelWire.Generation(ModelWire.PROFILE, ModelWire.API, ModelWire.COMPILER_VERSION,
            ModelWire.Jdk(Wire.Root(installed.runtime.root.toString(), realKey(installed.runtime.root, true)),
                installed.runtime.javaExecutable.toString(), installed.runtime.executableDigest, installed.runtime.digest, 21),
            ModelWire.Artifact("ktchecker:model-worker-bridge", "bridge.jar", bridge.digest, bridge.bytes, bridge.fileKey),
            installed.compilerClasspath.mapIndexed { index, item -> ModelWire.Artifact(item.identity.coordinate,
                Path.of(item.identity.path).fileName.toString(), item.identity.digest, item.size, copies[index].fileKey) })
        val implementation = Digests.sha256(Digests.canonical(buildJsonObject {
            put("schema", "ktchecker/real-no-test-feasibility-code/1")
            put("installedCodeImage", startup.image.digest); put("runtimeImplementation", runtime.implementationIdentity)
            put("selection", selectionDigest); put("fixture", RealModelFixture.CASE)
        }))
        val identity = Wire.Identity(UUID.randomUUID().toString().replace("-", ""), runtime.owner.identity, namespace.digest,
            implementation, bridge.digest, ModelWire.compilerClosureDigest(descriptor), ModelWire.toolchainDigest(descriptor),
            ModelWire.API, ModelWire.PROFILE)
        val registered = Wire.Root(root.toString(), realKey(root, true))
        val launch = Wire.Launch(identity, Wire.Limits(2, 1_048_576, 1_048_576), registered, root.toString(),
            ModelWire.command(registered, descriptor), ModelWire.environment(registered, descriptor), ModelWire.encodeGeneration(descriptor))
        val bytes = Wire.encodeLaunch(launch)
        realWrite(root.resolve("launch.bin"), bytes)
        installedFiles = copies + bridge + RealModelFilePin.capture(root.resolve("launch.bin"), Wire.sha256(bytes), bytes.size.toLong())
        roots = listOf(root, root.resolve("compiler"), root.resolve("jobs"), root.resolve("tmp"), installed.runtime.root).associateWith { realKey(it, true) }
        request = ModelCompilerGenerationRequest(runtime.owner, namespace, runtime.installation, launch, originalDeadline)
        permit = ModelCompilerGenerationPermit(request,
            verifyBeforeStart = {
                realExpect(Files.list(root.resolve("jobs")).use { !it.findAny().isPresent }, "real_model_generation_not_fresh")
                verify(); realTreeBoundary(runRoot)
            }, verifyLive = { verify() })
    }
    fun verify() {
        startup.verify(); runtime.installation.verify()
        roots.forEach { (path, key) -> realExpect(realKey(path, true) == key, "real_model_generation_root_changed") }
        installedFiles.forEach { it.verify() }
        realExpect(Files.list(root).use { it.map { path -> path.fileName.toString() }.toList().toSet() } ==
            setOf("compiler", "jobs", "tmp", "bridge.jar", "launch.bin"), "real_model_generation_topology_changed")
    }
    fun open(custody: RealModelCustody): OwnerLiveGenerationStatus {
        realExpect(actualOpen == null, "real_model_generation_open_replayed")
        val opened = runtime.open(request, permit, custody, CancellationToken.NONE)
        actualOpen = opened
        return opened
    }
    fun evidence(status: OwnerLiveGenerationStatus = runtime.status(request)): JsonObject = buildJsonObject {
        put("generationId", status.generationId); put("namespaceDigest", status.namespaceDigest); put("state", status.state)
        put("generationOriginalDeadlineIdentity", request.originalDeadline.identity)
        put("generationOriginalDeadlineMillis", request.originalDeadline.durationMillis)
        put("launchDigest", request.launchDigest); put("installedToolchainDigest", runtime.installation.digest)
        put("runtimeImplementationIdentity", runtime.implementationIdentity)
        put("wireImplementationIdentity", request.launch.identity().implementationDigest())
        val ready = status.opened as? ModelCompilerApplicationOpenResult.Ready
        if (ready != null) putJsonObject("actualHello") {
            val hello = ready.application.hello
            put("digest", Wire.helloDigest(hello)); put("loaderToken", hello.loaderToken()); put("version", hello.observedVersion())
            put("originsVerified", hello.originsVerified()); put("initialResetObserved", hello.initialResetObserved())
        }
        val unavailable = status.opened as? ModelCompilerApplicationOpenResult.Unavailable
        put("openUnavailableReasons", JsonArray(unavailable?.reasons.orEmpty().map(::JsonPrimitive)))
        put("reason", status.reason?.let(::JsonPrimitive) ?: JsonNull)
        put("retirementDisposition", status.retirement?.disposition?.name?.let(::JsonPrimitive) ?: JsonNull)
        put("retirementReasons", JsonArray(status.retirement?.reasons.orEmpty().map { JsonPrimitive(it.code) }))
        put("retirementTransportReasons", JsonArray(status.retirement?.transportReasonCodes.orEmpty().map(::JsonPrimitive)))
        put("qualified", false)
    }
}

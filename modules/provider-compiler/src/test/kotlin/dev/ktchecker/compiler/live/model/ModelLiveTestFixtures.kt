package dev.ktchecker.compiler.live.model

import dev.ktchecker.compiler.*
import dev.ktchecker.compiler.live.CompilerApplicationNamespace
import dev.ktchecker.compiler.live.CompilerApplicationProblem
import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.GradleProjectExport
import dev.ktchecker.providers.runtime.*
import dev.ktchecker.workerprotocol.ApplicationWire as Wire
import dev.ktchecker.workerprotocol.CompilerModelWire as ModelWire
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Real typed importer/preparation objects, synthetic export/JDK/JAR bytes and archive observer.
 * These are source-level unit controls, NOT Gradle/compiler/native execution or cleanup receipts.
 * Only bindingControls receives the deliberately false compiler pin; its compile path is disabled.
 */
internal class ModelLiveHostFixture(
    val authority: RetainedModelTestAuthority = RetainedModelTestAuthority(),
    transformGraph: (GradleProjectExport) -> GradleProjectExport = { it },
) : AutoCloseable {
    val generationDeadline = OwnedApplicationDeadline.afterMillis(120_000)
    val originalDeadline = OwnedApplicationDeadline.afterMillis(120_000)
    val modelFixture = KotlinJvmModelBindingFixtures()
    val root: Path get() = modelFixture.work
    val installation: KotlinJvmToolchain get() = modelFixture.installation
    val owner = OwnedApplicationOwner()
    val namespace = CompilerApplicationNamespace(modelFixture.namespace.ownerId,
        modelFixture.namespace.workspaceId, modelFixture.namespace.worktreeId)
    val generationRoot: Path = Files.createDirectory(root.resolve("live-model-generation"))
    val coldProvider = KotlinJvmCompilerProvider(installation, inputMode = KotlinJvmInputMode.VERIFIED_MODEL)
    val cold = modelFixture.prepare(coldProvider, modelFixture.importModel(transformGraph(modelFixture.graph())))
    val provider = KotlinJvmModelLiveCompilerProvider(installation, authority)
    val original: AnalysisInput = freezeModelLiveInput(cold.input.copy(context = cold.input.context.copy(
        policies = cold.input.context.policies + ("ktchecker.execution-plan" to emptyModelColdProfilesDigest()))))
    val prepared = cold.binding.bindPreparedLiveInput(original, listOf(modelFixture.rule), provider, originalDeadline)
    val launch: Wire.Launch
    val request: ModelCompilerGenerationRequest
    val session: NeverSubmitModelSession
    val generationPermit: ModelCompilerGenerationPermit
    val application: KotlinJvmModelCompilerApplication
    private var archiveOrdinal = 0

    init {
        Files.createDirectory(generationRoot.resolve("compiler"))
        Files.createDirectory(generationRoot.resolve("jobs"))
        Files.createDirectory(generationRoot.resolve("tmp"))
        val bridgePath = generationRoot.resolve("bridge.jar")
        Files.writeString(bridgePath, "SYNTHETIC model bridge: NEVER EXECUTE")
        val bridge = ModelWire.Artifact("synthetic:model-bridge", "bridge.jar", Digests.sha256(Files.readAllBytes(bridgePath)),
            Files.size(bridgePath), modelTestFileKey(bridgePath))
        val installed = installation.installationView()
        val compiler = installed.compilerClasspath.mapIndexed { index, artifact ->
            val originalPath = Path.of(artifact.identity.path)
            val copy = generationRoot.resolve("compiler/$index.jar")
            Files.copy(originalPath, copy)
            // This advertised digest is intentionally false for the fake installed compiler.
            // A real ModelGenerationCapture MUST reject it; no launcher is run by these tests.
            val claimed = if (originalPath.fileName.toString() == "kotlin-compiler-embeddable-2.4.21.jar")
                ModelWire.COMPILER_SHA256 else artifact.identity.digest
            ModelWire.Artifact(artifact.identity.coordinate, originalPath.fileName.toString(), claimed,
                artifact.size, modelTestFileKey(copy))
        }
        val generation = ModelWire.Generation(ModelWire.PROFILE, ModelWire.API, ModelWire.COMPILER_VERSION,
            ModelWire.Jdk(Wire.Root(installed.runtime.root.toString(), modelTestFileKey(installed.runtime.root)),
                installed.runtime.javaExecutable.toString(), installed.runtime.executableDigest,
                installed.runtime.digest, 21), bridge, compiler)
        val registered = Wire.Root(generationRoot.toString(), modelTestFileKey(generationRoot))
        val identity = Wire.Identity("a".repeat(32), owner.identity, namespace.digest,
            Digests.sha256("SYNTHETIC host model authority controls, NOT executed"), bridge.digest(),
            ModelWire.compilerClosureDigest(generation), ModelWire.toolchainDigest(generation), ModelWire.API, ModelWire.PROFILE)
        launch = Wire.Launch(identity, Wire.Limits(4, 1_048_576, 1_048_576), registered, generationRoot.toString(),
            ModelWire.command(registered, generation), ModelWire.environment(registered, generation), ModelWire.encodeGeneration(generation))
        Files.write(generationRoot.resolve("launch.bin"), Wire.encodeLaunch(launch))
        request = ModelCompilerGenerationRequest(owner, namespace, installation, launch, generationDeadline)
        session = NeverSubmitModelSession(launch)
        generationPermit = ModelCompilerGenerationPermit(request, {}, {})
        application = KotlinJvmModelCompilerApplication.bindingControls(request, generationPermit, session)
    }

    fun archive(preparation: PreparedJvmLiveModelInput = prepared): ModelTestArchive {
        val raw = modelFixture.archive(cold.prepared, "live-model-${++archiveOrdinal}")
        val before = preparation.original
        val effective = freezeModelLiveInput(before.copy(materializedRoot = raw.effective.materializedRoot,
            context = before.context.copy(classpath = raw.effective.context.classpath), outputRoot = raw.effective.outputRoot))
        val child = freezeModelLiveInput(effective.copy(requiredRuleIds = preparation.compilerRuleIds, execution = null),
            ModelLiveLimits.COMPILER_RULES)
        return ModelTestArchive(effective, child, raw::verify)
    }

    fun grant(archive: ModelTestArchive, preparation: PreparedJvmLiveModelInput = prepared,
        application: KotlinJvmModelCompilerApplication = this.application,
        verify: () -> Unit = archive::verify,
        register: (ModelCompilerJobMaterialization) -> Unit = archive::register,
        verifyMaterialization: (ModelCompilerJobMaterialization) -> Unit = archive::verifyMaterialization): ModelCompilerAttemptPermit =
        preparation.bindAttempt(preparation.original, archive.effective, archive.child, application,
            verify, register, verifyMaterialization)

    fun reprepare(deadline: OwnedApplicationDeadline = originalDeadline,
        selected: KotlinJvmModelLiveCompilerProvider = provider): PreparedJvmLiveModelInput =
        cold.binding.bindPreparedLiveInput(original, prepared.compilerRuleIds, selected, deadline)

    /** Pure binding-only descriptor variant; no launch file mutation or generation execution. */
    fun requestFor(otherNamespace: CompilerApplicationNamespace = namespace,
        otherInstallation: KotlinJvmToolchain = installation,
        deadline: OwnedApplicationDeadline = generationDeadline): ModelCompilerGenerationRequest {
        val old = launch.identity()
        val identity = Wire.Identity(old.generation(), owner.identity, otherNamespace.digest, old.implementationDigest(),
            old.bridgeDigest(), old.compilerClosureDigest(), old.toolchainDigest(), old.adapterVersion(), old.profile())
        val changed = Wire.Launch(identity, launch.limits(), launch.generationRoot(), launch.directory(),
            launch.command(), launch.environment(), launch.payload())
        return ModelCompilerGenerationRequest(owner, otherNamespace, otherInstallation, changed, deadline)
    }

    override fun close() { modelFixture.close() }
}

internal class ModelTestArchive(val effective: AnalysisInput, val child: AnalysisInput, private val checker: () -> Unit) {
    var verifications = 0
        private set
    var registrations = 0
        private set
    var materializationVerifications = 0
        private set
    private var registered: ModelCompilerJobMaterialization? = null
    fun verify() { verifications++; checker() }
    fun register(value: ModelCompilerJobMaterialization) {
        check(registered == null)
        check(value.archived == effective && value.input == child)
        registered = value
        registrations++
    }
    fun verifyMaterialization(value: ModelCompilerJobMaterialization) {
        check(registered === value)
        materializationVerifications++
    }
}

internal class RetainedModelTestAuthority : KotlinJvmModelApplicationAuthority {
    var generation: ModelCompilerGenerationPermit? = null
    var attempt: ModelCompilerAttemptPermit? = null
    var failure: Exception? = null
    var lookups = 0
    override fun authorizeGeneration(request: ModelCompilerGenerationRequest): ModelCompilerGenerationPermit? = generation
    override fun authorizeAttempt(provider: KotlinJvmModelLiveCompilerProvider,
        request: ModelCompilerAttemptRequest): ModelCompilerAttemptPermit? {
        lookups++
        failure?.let { throw it }
        return attempt
    }
}

internal class NeverSubmitModelSession(launch: Wire.Launch) : OwnedApplicationSession {
    private val submissionCount = AtomicInteger()
    private val cancellationCount = AtomicInteger()
    val submissions: Int get() = submissionCount.get()
    val cancellations: Int get() = cancellationCount.get()
    @Volatile var onCancel: () -> Unit = {}
    override val hello = Wire.Hello(launch.identity(), Wire.launchDigest(launch), "c".repeat(32), ModelWire.COMPILER_VERSION, true, true)
    override fun <T : Any> submit(permit: OwnedApplicationJobPermit<T>, cancellation: CancellationToken): OwnedApplicationJobResult<T> {
        submissionCount.incrementAndGet()
        throw AssertionError("Synthetic model host controls MUST NOT submit a runtime job")
    }
    override fun retire(): OwnedApplicationRetirement = throw AssertionError("Synthetic model controls have no retirement receipt")
    override fun cancel() { cancellationCount.incrementAndGet(); onCancel() }
}

internal fun modelTestFileKey(path: Path): String =
    checkNotNull(Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()).toString()

internal fun expectModelLiveFailure(code: String, action: () -> Unit) =
    assertEquals(code, assertFailsWith<CompilerApplicationProblem>(block = action).code)

internal fun expectSharedModelFailure(code: String, action: () -> Unit) =
    assertEquals(code, assertFailsWith<KotlinJvmModelException>(block = action).code)

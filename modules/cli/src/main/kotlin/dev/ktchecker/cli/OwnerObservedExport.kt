package dev.ktchecker.cli

import dev.ktchecker.compiler.CompilerSnapshot
import dev.ktchecker.compiler.JvmRuntimeEntryKind
import dev.ktchecker.compiler.KotlinJvmToolchain
import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.*
import dev.ktchecker.providers.runtime.*
import dev.ktchecker.snapshot.MaterializedSnapshot
import dev.ktchecker.snapshot.SnapshotIdentity
import dev.ktchecker.workerprotocol.CompilerModelWire as ModelWire
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.jar.JarFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

private const val OWNER_ORIGIN_SCRIPT = "ktchecker-owner-model-origin.init.gradle"
private const val OWNER_ORIGIN_REPORT = "owner-origin.json"
private const val OWNER_ORIGIN_MAX_BYTES = 4_194_304
private const val OWNER_MODEL_EXPORT_MAX_BYTES = 16_777_216
private const val OWNER_GRADLE_VERSION = "9.6.1"
private const val OWNER_SEED_ENTRIES = 50_000

/** Independently emitted actual task facts; decoding this data never creates any capability. */
@Serializable private data class OwnerObservedSourceSet(
    val id: String, val name: String, val dependsOn: List<String>,
    val sourceRoots: List<String>, val resourceRoots: List<String>, val sources: List<String>, val resources: List<String>,
)
@Serializable private data class OwnerObservedCode(val className: String, val path: String, val digest: String, val size: Long)
@Serializable private data class OwnerObservedCompilation(
    val id: String, val name: String, val moduleId: String, val compileTaskPath: String,
    val sourceSetIds: List<String>, val defaultSourceSetId: String,
    val sourcePaths: List<String>, val compilerArguments: List<String>,
    val libraries: List<String>, val existingLibraries: List<Boolean>,
    val destinationPath: String, val selectedJdkHome: String, val jdkHomeOperand: String?, val classpathOperand: String?,
    val pluginDataState: String, val friendPaths: List<String>, val declaredFriendPaths: List<String>,
    val codeOrigins: List<OwnerObservedCode>, val compilerFiles: List<String>, val associatedCompilationIds: List<String>,
    val dependencyConfiguration: String, val languageVersion: String?, val apiVersion: String?,
)
@Serializable private data class OwnerObservedEdge(
    val producerCompilationId: String, val consumerCompilationId: String, val producerCompileTaskPath: String,
    val outputPath: String, val classpathIndex: Int, val originalClasspathPresent: Boolean,
    val friend: Boolean, val originalFriendPresent: Boolean, val declaredFriendPresent: Boolean,
)
@Serializable private data class OwnerOriginObservation(
    val schemaVersion: Int, val adapter: String, val invocationId: String,
    val sourceRoot: String, val ownerOutput: String, val gradleHome: String, val gradleUserHome: String,
    val gradleVersion: String, val javaHome: String, val initScripts: List<String>,
    val modules: List<ModelModule>, val snapshotPaths: List<String>, val sourceSets: List<OwnerObservedSourceSet>,
    val buildscriptFiles: List<String>, val compilations: List<OwnerObservedCompilation>, val producedEdges: List<OwnerObservedEdge>,
)

/**
 * One exact actual observed export, NOT generic ModelIngressPermit authority. The immutable
 * submitted snapshot and selected byte artifacts are registered before launch/report decode.
 * Arbitrary Gradle configuration-read closure is explicitly unproven and never asserted here.
 * The sibling importer alone installs its mandatory taint; these callbacks cannot remove it.
 */
internal class OwnerObservedExport(
    private val workbench: Workbench,
    private val installation: KotlinJvmToolchain,
    private val intent: OwnerModelIntent,
    private val materialized: MaterializedSnapshot,
    private val runRoot: Path,
    gradleHome: Path,
    private val cache: OwnerModelCacheSeed,
    private val originalDeadline: OwnedApplicationDeadline,
    val invocation: ObservedExportInvocation,
) {
    private val used = AtomicBoolean()
    private val runnerEntered = AtomicBoolean()
    private val returnedQuiescent = AtomicBoolean()
    private val quarantined = AtomicBoolean()
    private val completed = AtomicReference<ProcessOutcome?>()
    private val output = AtomicReference<OwnerModelRootPin?>()
    private val snapshot: OwnerModelTreePin
    private val gradle: OwnerModelTreePin
    private val artifactPins: Map<String, OwnerModelFilePin>
    private val scriptPins = linkedMapOf<String, OwnerModelFilePin>()
    private val userHome: Path
    private val input: AnalysisInput
    private val expectedSupervisor: OwnedSupervisorIdentity
    private var originPin: OwnerModelFilePin? = null
    private var reportPin: OwnerModelFilePin? = null
    private var rawReport: ByteArray? = null
    private var origin: OwnerOriginObservation? = null
    private var importRequest: ModelImportRequest? = null
    private var origins: Map<String, ModelJvmArgumentOrigin> = emptyMap()
    private var producedOrigin: ModelJvmProducedOutputOrigin? = null
    private var evidenceDigest: String? = null
    private val identity = SnapshotIdentity(intent.binding.ownerId, intent.binding.workspaceId, intent.binding.worktreeId)

    init {
        checkpoint()
        ownerModelRequire(intent.originalDeadline === originalDeadline && invocation.originalDeadline === originalDeadline &&
            cache.runRoot == runRoot && intent.evidence.executionAuthorized &&
            intent.binding.ownerId == workbench.state.ownerId && runRoot.startsWith(workbench.state.root) &&
            materialized.root.startsWith(runRoot), "owner_model_export_intent_mismatch")
        cache.verifyInstallation(installation, originalDeadline)
        workbench.verifyOwnerCode(); checkpoint()
        installation.verify(::checkpoint)
        val inspected = OwnedProcessContainment.inspect()
        ownerModelRequire(inspected.available, "owner_model_supervisor_unavailable")
        expectedSupervisor = requireNotNull(inspected.identity)
        snapshot = OwnerModelTreePin.capture(materialized.root, OWNER_SEED_ENTRIES, ModelWire.MAX_SNAPSHOT_BYTES,
            ModelWire.MAX_SOURCE_BYTES.toLong(), ::checkpoint)
        verifySnapshot()
        gradle = OwnerModelTreePin.capture(ownerModelPath(gradleHome), OWNER_SEED_ENTRIES,
            CompilerSnapshot.MAX_TOTAL_ARTIFACT_BYTES, checkpoint = ::checkpoint)
        val bootstrap = gradle.files["lib/gradle-gradle-cli-main-$OWNER_GRADLE_VERSION.jar"]
            ?: throw CliProblem("owner_model_gradle_bootstrap_unsupported", "Select the bounded supported Gradle 9.6.1 distribution, not a shell launcher.")
        // Static class membership plus whole selected distribution custody, never a PATH shell.
        JarFile(bootstrap.path.toFile(), true).use { jar ->
            val entry = jar.getJarEntry("org/gradle/launcher/GradleMain.class")
            ownerModelRequire(entry != null && !entry.isDirectory && entry.size in 1..1_048_576,
                "owner_model_gradle_bootstrap_unsupported")
        }
        bootstrap.verify(::checkpoint)
        val seedRoot = cache.seed.root.path
        ownerModelRequire(listOf(materialized.root, runRoot).all { own ->
            !gradle.root.path.startsWith(own) && !own.startsWith(gradle.root.path) &&
                !seedRoot.startsWith(own) && !own.startsWith(seedRoot)
        }, "owner_model_selected_roots_overlap")
        ownerModelRequire(!seedRoot.startsWith(gradle.root.path) && !gradle.root.path.startsWith(seedRoot) &&
            listOf(seedRoot, gradle.root.path, runRoot).all { path ->
                val jdk = installation.installationView().runtime.root
                !path.startsWith(jdk) && !jdk.startsWith(path)
            }, "owner_model_selected_roots_overlap")
        userHome = cache.userHome
        cache.verifyArtifacts()
        val installed = installation.installationView()
        val jdkFiles = installed.runtime.entries.filter { it.kind == JvmRuntimeEntryKind.FILE }.map { member ->
            OwnerModelFilePin.capture(installed.runtime.root.resolve(member.relativePath), CompilerSnapshot.MAX_ARTIFACT_BYTES,
                ::checkpoint).also { pin -> ownerModelRequire(pin.digest == member.digest && pin.size == member.size) }
        }
        val registeredArtifacts = cache.copies.filterKeys { it.endsWith(".jar") }.values +
            gradle.files.filterKeys { it.endsWith(".jar") }.values + jdkFiles
        artifactPins = Collections.unmodifiableMap(registeredArtifacts.associateBy { it.path.toString() })
        ownerModelRequire(artifactPins.size == registeredArtifacts.size, "owner_model_artifact_alias")
        val exports = ownerModelFreshDirectory(runRoot.resolve("exports"))
        val fixed = GradleModelExporter.profile(installed.runtime.javaExecutable, installed.runtime.executableDigest,
            installed.runtime.root, userHome)
        val prefix = listOf("-Xmx512m", "-XX:MaxMetaspaceSize=256m", "-Duser.home={output}/home",
            "-Djava.io.tmpdir={output}/tmp", "-cp", bootstrap.path.toString(), "org.gradle.launcher.GradleMain")
        val profile = fixed.copy(arguments = prefix + fixed.arguments.dropLast(1) + listOf(
            "--init-script", "{output}/$OWNER_ORIGIN_SCRIPT", "-PktcheckerOwnerOriginOutput={output}/$OWNER_ORIGIN_REPORT",
            "-PktcheckerOwnerOriginInvocation=${invocation.identity}", fixed.arguments.last()),
            // No new remaining-time clock: cancellation and every callback use SAME original.
            timeoutMillis = originalDeadline.durationMillis,
            environment = mapOf("JAVA_HOME" to installed.runtime.root.toString(), "GRADLE_USER_HOME" to userHome.toString(),
                "PATH" to installed.runtime.root.resolve("bin").toString(), "LANG" to "C.UTF-8", "LC_ALL" to "C.UTF-8"))
        input = AnalysisInput(materialized.manifest.toContractManifest(), materialized.root.toString(),
            AnalysisContext(configurationDigest = intent.configuration.policyDigest, toolchainDigest = installation.digest,
                policies = mapOf("ktchecker.owner-observed-export" to invocation.identity)),
            execution = profile, outputRoot = exports.path.toString())
        verifyPrelaunch()
    }

    fun run(): ObservedProjectModel {
        checkpoint()
        ownerModelRequire(used.compareAndSet(false, true), "owner_model_export_replayed")
        val profile = requireNotNull(input.execution)
        val execution = ExecutionPermit(profile, input.snapshot.snapshotId, materialized.root, input.analysisKey(),
            verifySnapshot = ::verifyPrelaunch, prepareOutput = { path ->
                checkpoint()
                val root = OwnerModelRootPin.capture(path)
                ownerModelRequire(path.parent == Path.of(requireNotNull(input.outputRoot)) && output.compareAndSet(null, root) &&
                    ownerModelNames(path, 1) == setOf("tmp"), "owner_model_export_output_not_fresh")
                privatePermissions(path, true)
                GradleModelExporter.prepareOutput(path)
                val exported = OwnerModelFilePin.capture(path.resolve(GradleModelExporter.SCRIPT), OWNER_ORIGIN_MAX_BYTES.toLong(), ::checkpoint)
                ownerModelRequire(exported.digest == GradleModelExporter.scriptDigest(), "owner_model_exporter_changed")
                scriptPins[exported.path.toString()] = exported
                val bytes = requireNotNull(Workbench::class.java.getResourceAsStream("/$OWNER_ORIGIN_SCRIPT")).use { stream ->
                    stream.readNBytes(OWNER_ORIGIN_MAX_BYTES + 1)
                }
                ownerModelRequire(bytes.size in 1..OWNER_ORIGIN_MAX_BYTES, "owner_model_origin_script_unavailable")
                val observer = ownerModelWrite(path.resolve(OWNER_ORIGIN_SCRIPT), bytes, OWNER_ORIGIN_MAX_BYTES, ::checkpoint)
                scriptPins[observer.path.toString()] = observer
                root.verify(); verifyPrelaunch(); checkpoint()
            })
        val runner = IsolatedProcessRunner { actual ->
            ownerModelRequire(completed.compareAndSet(null, actual), "owner_model_export_completion_replayed")
        }
        // A thrown, failed, unavailable or ambiguous return after this point retains the entire
        // command even if no child actually started. No failure-string inference or later retry
        // can promote it into cleanup evidence. The runtime keeps its own real channel/image.
        ownerModelRequire(runnerEntered.compareAndSet(false, true), "owner_model_export_runner_replayed")
        val actual = runner.run(input, ExactExecutionAuthority(execution),
            CancellationToken { quarantined.get() || originalDeadline.isExpired() || Thread.currentThread().isInterrupted })
        ownerModelRequire(completed.get() === actual, "owner_model_export_outcome_substituted")
        verifyRawSuccessfulInvocation(actual)
        returnedQuiescent.set(true)
        verifyExecutedExport()
        // Bind BOTH byte reports before either decode. Only the private, pinned observer's data
        // selects origin facts; model-export.json cannot enroll a task/artifact/origin allowlist.
        val observedBytes = actual.report(OWNER_ORIGIN_REPORT, OWNER_ORIGIN_MAX_BYTES)
        val report = actual.report(GradleModelExporter.REPORT, OWNER_MODEL_EXPORT_MAX_BYTES)
        val root = requireNotNull(output.get())
        originPin = OwnerModelFilePin.capture(root.path.resolve(OWNER_ORIGIN_REPORT), OWNER_ORIGIN_MAX_BYTES.toLong(), ::checkpoint)
        reportPin = OwnerModelFilePin.capture(root.path.resolve(GradleModelExporter.REPORT), OWNER_MODEL_EXPORT_MAX_BYTES.toLong(), ::checkpoint)
        ownerModelRequire(originPin!!.digest == Digests.sha256(observedBytes) && reportPin!!.digest == Digests.sha256(report))
        rawReport = report.copyOf()
        val observed = ContractJson.decodeFromString<OwnerOriginObservation>(strictJsonDocument(observedBytes, "owner_model_origin_invalid"))
        origin = observed
        verifyObservation(observed)
        val request = ModelImportRequest(ModelNamespace(intent.binding.ownerId, intent.binding.workspaceId,
            intent.binding.worktreeId, intent.binding.lineageId), input.snapshot, materialized.root.toString(),
            intent.configuration.policyDigest, Digests.sha256(report), GradleModelExporter.scriptDigest(), invocation.identity)
        importRequest = request
        origins = Collections.unmodifiableMap(observed.compilations.associate { row ->
            row.id to ModelJvmArgumentOrigin(request, row.id, row.compileTaskPath, row.moduleId, row.name,
                root.path, installation.installationView().runtime.root).also { selected ->
                    ownerModelRequire(row.destinationPath == selected.destinationPath.toString())
                }
        })
        val edge = observed.producedEdges.single()
        producedOrigin = ModelJvmProducedOutputOrigin(request, origins.getValue(edge.producerCompilationId),
            origins.getValue(edge.consumerCompilationId), edge.classpathIndex, edge.originalClasspathPresent,
            edge.friend, edge.originalFriendPresent, edge.declaredFriendPresent)
        evidenceDigest = ownerProducedDigest(buildJsonObject {
            put("schema", "ktchecker/owner-observed-export-custody/1"); put("invocation", invocation.identity)
            put("request", request.digest()); put("input", input.analysisKey()); put("ownerCode", workbench.ownerCodeDigest)
            put("originalDeadline", originalDeadline.identity); put("snapshotTree", snapshot.digest)
            put("gradleTree", gradle.digest); put("cacheSelection", cache.evidence())
            put("originReport", originPin!!.digest); put("modelReport", reportPin!!.digest)
            put("scripts", ContractJson.encodeToJsonElement(scriptPins.mapValues { it.value.digest }))
            put("artifactPins", ContractJson.encodeToJsonElement(artifactPins.mapValues { listOf(it.value.digest, it.value.fileKey) }))
            put("command", ContractJson.encodeToJsonElement(actual.command))
            put("supervisorBinary", requireNotNull(actual.supervisorIdentity).binaryDigest)
            put("supervisorSource", requireNotNull(actual.supervisorIdentity).sourceDigest)
            put("executed", actual.executed); put("status", actual.status); put("exitCode", requireNotNull(actual.exitCode))
        })
        val permit = ObservedExportPermit(request, invocation, gradle.digest, requireNotNull(evidenceDigest),
            verifySnapshot = ::verifySnapshot, verifyExecutedExport = ::verifyExecutedExport,
            verifyArtifact = ::verifyArtifact, verifyObservedInputs = ::verifyObservedInputs,
            verifyJvmArgumentOrigin = { compilation ->
                verifyRetainedObservation(); verifyCompilation(compilation)
                origins[compilation.id]
            }, verifyJvmProducedOutputOrigin = { compilation ->
                verifyRetainedObservation(); verifyCompilation(compilation)
                producedOrigin?.takeIf { it.consumer.compilationId == compilation.id }
            })
        return ObservedProjectModelImporter(ExactObservedExportAuthority(permit)).import(request, report).also { model ->
            ownerModelRequire(model.invocation === invocation && model.observedEvidenceDigest == evidenceDigest &&
                model.snapshot == input.snapshot && model.namespace == request.namespace &&
                model.issues.any { it.kind == "configuration_input_closure_unproven" && it.scopeId == null })
            verifyRetainedObservation()
        }
    }

    /** Reverify actual invocation identity and immutable selected inputs, not mutable private metadata. */
    private fun verifyExecutedExport() {
        verifyInvocationIdentity()
        verifyPrelaunch()
    }

    /**
     * Actual returned outcome only. Runtime status=executed requires native FINAL+EOF+helper0+
     * QUIESCENT and the pinned helper image; it is NOT full image/pipe/worker resource CLOSED.
     * This audit does not require a fresh clock, nor authorize any work after the original expires.
     */
    private fun verifyRawSuccessfulInvocation(actual: ProcessOutcome) {
        ownerModelRequire(runnerEntered.get() && completed.get() === actual, "owner_model_export_outcome_substituted")
        val root = output.get() ?: throw CliProblem("owner_model_export_output_unavailable", "No pre-registered export output exists.")
        root.verify()
        val expected = listOf(requireNotNull(input.execution).executable) + input.execution!!.arguments.map { value ->
            value.replace("{snapshot}", materialized.root.toString()).replace("{output}", root.path.toString())
        }
        ownerModelRequire(actual.successfulExecution && actual.executed && actual.exitCode == 0 && actual.reasons.isEmpty() &&
            !actual.cancelled && !actual.timedOut && !actual.truncated && actual.command == expected &&
            actual.outputDirectory == root.path && actual.supervisorIdentity == expectedSupervisor,
            "owner_model_export_failed_or_nonquiescent")
    }

    private fun verifyInvocationIdentity() {
        checkpoint()
        val actual = completed.get() ?: throw CliProblem("owner_model_export_not_executed", "No actual owned export outcome exists.")
        ownerModelRequire(returnedQuiescent.get(), "owner_model_export_return_unverified")
        verifyRawSuccessfulInvocation(actual)
        originPin?.verify(::checkpoint); reportPin?.verify(::checkpoint)
        requireNotNull(output.get()).verify(); checkpoint()
    }

    private fun verifySnapshot() {
        checkpoint()
        val stored = workbench.snapshots.snapshot(identity, materialized.manifest.snapshotId)
        ownerModelRequire(stored == materialized.manifest && stored.capture.coherent &&
            stored.toContractManifest() == materialized.manifest.toContractManifest() &&
            stored.toContractManifest().captureKind == "immutable_overlay" && stored.scope.exclusions.isEmpty() &&
            stored.scope.unknownInputs.isEmpty() && workbench.snapshots.verifyMaterialization(materialized).valid,
            "owner_model_registered_snapshot_changed")
        snapshot.verify(::checkpoint)
        val members = materialized.manifest.toContractManifest().files.associateBy { it.path }
        ownerModelRequire(snapshot.files.keys == members.keys && snapshot.directories.keys == ownerModelDirectories(members.keys) &&
            snapshot.files.all { (path, pin) -> members.getValue(path).let { it.digest == pin.digest && it.size == pin.size } })
        checkpoint()
    }

    private fun verifyPrelaunch() {
        checkpoint(); workbench.verifyOwnerCode(); checkpoint()
        cache.verifyInstallation(installation, originalDeadline)
        verifySnapshot(); installation.verify(::checkpoint); gradle.verify(::checkpoint)
        // Whole Gradle/JDK images were checked above. Do not recursively rehash those
        // images per artifact/chunk; importer artifact callbacks still pin each exact file.
        cache.verifyArtifacts()
        scriptPins.values.forEach { it.verify(::checkpoint) }
        checkpoint()
    }

    private fun verifyArtifact(artifact: ModelArtifact) {
        checkpoint()
        val pin = artifactPins[artifact.path] ?: scriptPins[artifact.path]
            ?: throw CliProblem("owner_model_artifact_unregistered", "A report cannot enroll unselected or newly generated artifact bytes.")
        ownerModelRequire(pin.digest == artifact.digest && pin.size == artifact.size, "owner_model_artifact_mismatch")
        pin.verify(::checkpoint); checkpoint()
    }

    private fun verifyRetainedObservation() {
        verifyInvocationIdentity()
        val bytes = requireNotNull(rawReport)
        ownerModelRequire(Digests.sha256(bytes) == requireNotNull(importRequest).exportDigest &&
            requireNotNull(reportPin).digest == requireNotNull(importRequest).exportDigest)
        verifyObservation(requireNotNull(origin)); checkpoint()
    }

    private fun verifyObservation(value: OwnerOriginObservation) {
        checkpoint()
        val selected = installation.installationView().runtime
        val root = requireNotNull(output.get())
        ownerModelRequire(value.schemaVersion == 1 && value.adapter == "ktchecker/owner-model-actual-origin/1" &&
            value.invocationId == invocation.identity && value.sourceRoot == materialized.root.toString() &&
            value.ownerOutput == root.path.toString() && value.gradleHome == gradle.root.path.toString() &&
            value.gradleUserHome == userHome.toString() && value.gradleVersion == OWNER_GRADLE_VERSION &&
            value.javaHome == selected.root.toString() && value.initScripts == listOf(root.path.resolve(GradleModelExporter.SCRIPT).toString(),
                root.path.resolve(OWNER_ORIGIN_SCRIPT).toString()) && value.snapshotPaths == snapshot.files.keys.toList() &&
            value.modules.size == 1 && value.modules.single().id == ":" && value.modules.single().directory == "" &&
            value.compilations.size == 2 && value.compilations.map { it.id }.distinct().size == 2 &&
            value.sourceSets.size in 1..16 && value.sourceSets.map { it.id }.distinct().size == value.sourceSets.size &&
            value.producedEdges.size == 1, "owner_model_origin_invocation_mismatch")
        value.buildscriptFiles.forEach { ownerModelRequire(it in artifactPins, "owner_model_buildscript_unregistered") }
        value.compilations.forEach { row ->
            ownerModelRequire(row.moduleId == value.modules.single().id && row.id == "${row.moduleId}||${row.name}" &&
                row.selectedJdkHome == selected.root.toString() && row.sourcePaths.size in 1..64 &&
                row.sourcePaths.distinct().size == row.sourcePaths.size && row.sourcePaths.all { it in snapshot.files } &&
                row.compilerArguments.size in 1..128 && row.libraries.size in 1..16 &&
                row.libraries.size == row.existingLibraries.size && row.compilerFiles.size in 1..16 &&
                row.compilerFiles == installation.installationView().compilerClasspath.map { it.identity.path } &&
                row.compilerFiles.all { it in artifactPins } && row.codeOrigins.map { it.className } == listOf(
                    "org.jetbrains.kotlin.gradle.tasks.KotlinCompile", "org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments",
                    "org.jetbrains.kotlin.gradle.plugin.KotlinCompilerArgumentsProducer"))
            row.codeOrigins.forEach { code ->
                val pin = artifactPins[code.path]
                    ?: throw CliProblem("owner_model_task_code_unregistered", "Actual loaded task code was not pre-pinned; newly generated transforms are unsupported.")
                ownerModelRequire(code.digest == pin.digest && code.size == pin.size, "owner_model_task_code_changed")
                pin.verify(::checkpoint)
            }
        }
        val edge = value.producedEdges.single()
        val producer = value.compilations.single { it.id == edge.producerCompilationId }
        val consumer = value.compilations.single { it.id == edge.consumerCompilationId }
        ownerModelRequire(producer !== consumer && edge.producerCompileTaskPath == producer.compileTaskPath &&
            edge.outputPath == producer.destinationPath && edge.classpathIndex in consumer.libraries.indices &&
            consumer.libraries[edge.classpathIndex] == edge.outputPath &&
            edge.originalClasspathPresent == consumer.existingLibraries[edge.classpathIndex] &&
            edge.friend == (consumer.associatedCompilationIds == listOf(producer.id)) &&
            edge.originalFriendPresent == consumer.friendPaths.isNotEmpty() &&
            edge.declaredFriendPresent == consumer.declaredFriendPaths.isNotEmpty())
        value.compilations.forEach { row -> row.libraries.forEachIndexed { index, path ->
            if (row === consumer && index == edge.classpathIndex) {
                ownerModelRequire(path == edge.outputPath)
            } else ownerModelRequire(path in artifactPins && row.existingLibraries[index], "owner_model_library_unregistered")
        } }
        checkpoint()
    }

    /** Full direct task domain only. This deliberately does NOT attest arbitrary configuration reads. */
    private fun verifyObservedInputs(graph: GradleProjectExport) {
        verifyRetainedObservation()
        val observed = requireNotNull(origin)
        ownerModelRequire(graph.gradleVersion == observed.gradleVersion && graph.modules == observed.modules &&
            graph.generatedInputs.isEmpty() && graph.includedBuilds.isEmpty() && graph.sourceSets.size == observed.sourceSets.size &&
            graph.compilations.map { it.id } == observed.compilations.map { it.id } &&
            graph.buildscriptArtifacts.map { it.path }.sorted() == observed.buildscriptFiles &&
            graph.externalConfigurationArtifacts.map { it.path }.toSet() == observed.initScripts.toSet(),
            "owner_model_observed_graph_mismatch")
        graph.sourceSets.forEach { set ->
            val row = observed.sourceSets.singleOrNull { it.id == set.id }
                ?: throw CliProblem("owner_model_source_set_unobserved", "Every modeled source set needs its actual observation.")
            ownerModelRequire(set.moduleId == observed.modules.single().id && set.name == row.name && set.dependsOn == row.dependsOn &&
                set.sourceRoots == row.sourceRoots && set.resourceRoots == row.resourceRoots &&
                set.sources.map { it.path } == row.sources && set.resources.map { it.path } == row.resources && row.resources.isEmpty(),
                "owner_model_source_set_mismatch")
            (set.sources + set.resources).forEach(::verifyModelFile)
        }
        graph.configurationInputs.forEach(::verifyModelFile) // Individual declared bytes, NOT complete read-set attestation.
        graph.compilations.forEach(::verifyCompilation)
        checkpoint()
    }

    private fun verifyCompilation(compilation: ModelCompilation) {
        checkpoint()
        val row = requireNotNull(origin).compilations.singleOrNull { it.id == compilation.id }
            ?: throw CliProblem("owner_model_compilation_unobserved", "Every modeled compilation needs its actual observation.")
        val roles = compilation.jvmArgumentRoles
            ?: throw CliProblem("owner_model_argument_origin_missing", "No complete original task argument roles were exported.")
        val chain = compilation.toolchain
            ?: throw CliProblem("owner_model_toolchain_unobserved", "The actual task's selected toolchain is unavailable.")
        val runtime = installation.installationView().runtime
        val runtimeFiles = runtime.entries.filter { it.kind == JvmRuntimeEntryKind.FILE }.map { runtime.root.resolve(it.relativePath).toString() }.toSet()
        ownerModelRequire(compilation.moduleId == row.moduleId && compilation.target == "" && compilation.platform == "jvm" &&
            compilation.name == row.name && compilation.compileTaskPath == row.compileTaskPath &&
            compilation.defaultSourceSetId == row.defaultSourceSetId && compilation.sourceSetIds == row.sourceSetIds &&
            compilation.sourceFiles.map { it.path }.sorted() == row.sourcePaths.sorted() && compilation.compilerArguments == row.compilerArguments &&
            compilation.classpath.map { it.path } == row.libraries && compilation.friendArtifacts.map { it.path } == row.declaredFriendPaths &&
            compilation.associatedCompilationIds == row.associatedCompilationIds && compilation.auxiliaryInputFiles.isEmpty() &&
            compilation.auxiliaryArtifacts.isEmpty() && compilation.projectDependencies.isEmpty() && compilation.producedVariants.isEmpty() &&
            compilation.dependencyConfiguration == row.dependencyConfiguration && compilation.languageVersion == row.languageVersion &&
            compilation.apiVersion == row.apiVersion && chain.kind == "jvm" && chain.compilerVersion == ModelWire.COMPILER_VERSION &&
            chain.compilerArtifacts.map { it.path } == row.compilerFiles && chain.compilerPlugins.isEmpty() &&
            chain.runtimeArtifacts.map { it.path }.toSet() == runtimeFiles && chain.runtimeArtifacts.size == runtimeFiles.size &&
            roles.schemaVersion == 1 && roles.adapterId == JVM_ARGUMENT_ROLES_ADAPTER &&
            roles.compileTaskPath == row.compileTaskPath && roles.sourcePaths == row.sourcePaths &&
            roles.classpathOperand == row.classpathOperand && roles.destinationPath == row.destinationPath &&
            roles.selectedJdkHome == row.selectedJdkHome && roles.jdkHomeOperand == row.jdkHomeOperand &&
            roles.friendPathOperands == row.friendPaths && roles.pluginDataState == row.pluginDataState,
            "owner_model_compilation_origin_mismatch")
        compilation.sourceFiles.forEach(::verifyModelFile)
        val observedEdge = requireNotNull(origin).producedEdges.single()
        val expectedEdges = if (compilation.id == observedEdge.consumerCompilationId) listOf(ModelJvmProducedOutputReference(
            observedEdge.producerCompilationId, observedEdge.producerCompileTaskPath, observedEdge.outputPath, observedEdge.classpathIndex,
            observedEdge.originalClasspathPresent, observedEdge.friend, observedEdge.originalFriendPresent, observedEdge.declaredFriendPresent)) else emptyList()
        ownerModelRequire(compilation.deferredJvmOutputs == expectedEdges, "owner_model_produced_origin_mismatch")
        checkpoint()
    }

    private fun verifyModelFile(file: ModelFile) {
        val member = snapshot.files[file.path]
            ?: throw CliProblem("owner_model_file_not_submitted", "The actual task input was not in the complete submitted snapshot.")
        ownerModelRequire(member.digest == file.digest && member.size == file.size, "owner_model_snapshot_member_mismatch")
    }

    /** Unknown runner entry is deliberately over-retained; never infer prelaunch cleanup. */
    val safeToClose: Boolean get() = !runnerEntered.get() || returnedQuiescent.get()

    /** State-only denial; no second runner call, cleanup request, process inspection or signal. */
    fun quarantine() { quarantined.set(true) }

    fun evidence(): JsonObject = buildJsonObject {
        put("profile", "ktchecker/owner-observed-export-custody/1"); put("invocationId", invocation.identity)
        put("originalDeadlineIdentity", originalDeadline.identity); put("originalDeadlineMillis", originalDeadline.durationMillis)
        put("exportAttempted", used.get()); put("runnerEntered", runnerEntered.get())
        put("actualOutcomeRetained", completed.get() != null)
        put("quarantined", quarantined.get()); put("returnedChildGroupQuiescent", returnedQuiescent.get())
        put("cleanupEvidenceBasis", when {
            returnedQuiescent.get() -> "actual_exact_successful_runner_return"
            runnerEntered.get() -> "unknown_runner_entry_retained"
            else -> "runner_not_entered"
        })
        put("fullResourceCleanupReceiptAvailable", false)
        put("actualStatus", completed.get()?.status?.let(::JsonPrimitive) ?: JsonNull)
        put("actualReasons", completed.get()?.reasons?.let { ContractJson.encodeToJsonElement(it) } ?: JsonNull)
        put("gradleRuntimeDigest", gradle.digest); put("cacheSelection", cache.evidence())
        put("observedEvidenceDigest", evidenceDigest?.let(::JsonPrimitive) ?: JsonNull)
        put("configurationInputClosureProven", false); put("qualified", false); put("usefulComplete", false)
    }

    private fun checkpoint() {
        ownerModelRequire(!quarantined.get(), "owner_model_export_quarantined")
        ownerModelCheckpoint(originalDeadline); invocation.verifyLive()
    }
}

package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import dev.ktchecker.snapshot.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/** Explicitly invoked owner test driver only; never expose this as project/RPC enrollment. */
object OwnerModelFixtureRunner {
    @JvmStatic fun main(args: Array<String>) {
        val options = parseFixtureArguments(args)
        require(options.keys.all { it in setOf("fixtures", "inputs", "result") }) { "Unknown owner fixture option" }
        val fixtures = Path.of(requireNotNull(options["fixtures"])).toAbsolutePath().normalize()
        val result = Path.of(requireNotNull(options["result"])).toAbsolutePath().normalize()
        val work = Files.createTempDirectory("ktchecker-owner-model-run-").toRealPath()
        var snapshotId = ""
        var executionId: String? = null
        var outcome: ProcessOutcome? = null
        var startup: OwnerFixtureStartupCapture? = null
        var runtimeInputDigest: String? = null
        var runtimeIdentity: String? = null
        var executionHome: OwnerFixtureExecutionHome? = null
        var planEvidence = OwnerJvmPlanEvidence("unavailable_without_trusted_import",
            rejection = OwnerJvmPlanRejection("owner_jvm_trusted_import_unavailable", "trusted-import"))
        val envelope = try {
            // Bind authored containment/adapter sources and the actual installed code/native
            // image at START, before loading owner contracts/inputs or launching any process.
            val startupCapture = OwnerFixtureStartupCapture.capture(fixtures, work).also { startup = it }
            val contract = OwnerFixtureContract.load(fixtures.resolve("jvm-owner-export"))
            val registry = OwnerFixtureRuntimeRegistry.load(Path.of(requireNotNull(options["inputs"]) {
                "Explicit owner-prepared offline runtime/seed manifest is required"
            }))
            runtimeInputDigest = registry.runtimeDigest
            val home = registry.deriveExecutionHome(work).also { executionHome = it }
            // Bind only contract-pinned, already-enrolled dependency copies BEFORE
            // exporter registration/launch. The eventual report cannot enroll files.
            registry.bindCompilerPluginClasspath(contract.value.compilerPluginClasspath)
            val runtimeDigest = ownerFixtureRuntimeIdentity(registry.runtimeDigest, startupCapture.digest, home.digest).also { runtimeIdentity = it }
            fun verifyRuntime() { registry.verifyRuntimeInputs(); startupCapture.verify() }
            val capture = contract.capture(fixtures.resolve("jvm-owner-export/project"), work)
            snapshotId = capture.snapshot.snapshotId
            var planPreparationFailure: OwnerJvmPlanRejection? = null
            val preparedPlan = try {
                val executableContract = OwnerFixtureExecutableContract.load(fixtures.resolve("jvm-owner-export"))
                OwnerFixturePreparedJvmInputs.bind(executableContract, contract, capture, registry, home, startupCapture)
            } catch (error: Exception) {
                // The new classifier is not another importer gate or input enrollment path.
                planPreparationFailure = ownerJvmPlanFailure(error)
                null
            }
            verifyRuntime()
            val profile = GradleModelExporter.profile(registry.gradleHome.resolve("bin/gradle"),
                registry.artifact(registry.gradleHome.resolve("bin/gradle")).digest!!, registry.javaHome, home.root)
            val input = AnalysisInput(capture.snapshot, capture.root.toString(),
                context = AnalysisContext(configurationDigest = contract.digest), execution = profile,
                outputRoot = work.resolve("outputs").toString())
            executionId = UUID.randomUUID().toString()
            val permit = ExecutionPermit(profile, snapshotId, capture.root, input.analysisKey(),
                verifySnapshot = { ownerFixtureExecutionVerification { capture.verify(); verifyRuntime() } },
                prepareOutput = { output -> ownerFixtureExecutionVerification {
                    GradleModelExporter.prepareOutput(output); registry.registerExporter(output); verifyRuntime()
                    home.prepareForLaunch()
                } })
            // Only the actual runner completion enters this private invocation registry.
            // Retain that actual outcome before validation, including failed/mismatched identity.
            val completed = OwnerFixtureInvocation.execute(executionId, input, permit, startupCapture) { outcome = it }
            val raw = completed.outcome.report(GradleModelExporter.REPORT, 16 * 1024 * 1024)
            val namespace = ModelNamespace(capture.snapshot.ownerId, capture.snapshot.workspaceId,
                capture.snapshot.worktreeId, "fixed-owner-fixture-lineage")
            val request = ModelImportRequest(namespace, capture.snapshot, capture.root.toString(), contract.digest,
                Digests.sha256(raw), GradleModelExporter.scriptDigest(), executionId)
            val ingress = ModelIngressPermit(request, runtimeDigest,
                verifySnapshot = { capture.verify(); verifyRuntime() },
                verifyExecutedExport = { completed.verify(request); verifyRuntime() },
                // Exact artifact checks are per-file; whole runtime/START freshness is checked
                // at importer entry and exit, not rehashed in full for every JDK/classpath file.
                verifyArtifact = registry::verifyArtifact,
                verifyModelInputs = { graph -> verifyRuntime(); contract.verifyDomain(capture.snapshot, graph, registry) })
            val model = ProjectModelImporter(ExactModelIngressAuthority(ingress)).import(request, raw)
            val impact = ProjectImpactPlanner().impact(null, model)
            check(impact.obligations.map { it.compilationId } == contract.value.compilations.map { it.id }.sorted())
            // JSON is evidence data, never another importer/owner capability.
            Files.createDirectories(result.parent)
            Files.write(result.parent.resolve("owner-model-export.json"), raw)
            val contexts = model.compilationIds.map { id -> model.context(id, contract.digest).let {
                OwnerFixtureContext(id, it.completeness.name.lowercase(), it.context?.fullProject ?: false, it.issues.map { issue -> issue.kind }.distinct())
            } }
            val issueKinds = model.issues.map { it.kind }.distinct()
            // Only after the existing trusted import, using the actual owner's output root.
            // Classification, input drift or serialization failure never rewrites that import.
            planEvidence = try {
                if (preparedPlan == null) OwnerJvmPlanEvidence("unavailable_intended_operands",
                    rejection = planPreparationFailure ?: OwnerJvmPlanRejection("owner_jvm_plan_unprepared", "preparation"),
                    importedContexts = contexts, importedIssueKinds = issueKinds)
                else OwnerFixtureJvmExecutableInputs.evidence(preparedPlan.classifyAfterImport(model, request,
                    requireNotNull(completed.outcome.outputDirectory)), contexts, issueKinds)
            } catch (error: Exception) {
                OwnerJvmPlanEvidence("unavailable_intended_operands", rejection = ownerJvmPlanFailure(error),
                    importedContexts = contexts, importedIssueKinds = issueKinds)
            }
            OwnerModelFixtureEnvelope("exported_and_trusted_imported", contract.value.caseId, snapshotId, executionId,
                contract.digest, runtimeDigest, model.identity, model.ingressCertificate,
                contexts, issueKinds, impact.obligations.map { it.compilationId },
                assertions = listOf("Exact fixed source/domain contract matched the registered complete producer overlay.",
                    "Successful zero-exit quiescent pinned Gradle exporter invocation and fresh anchored report were verified.",
                    "Actual terminal-confirmed native supervisor identity matched the independent START bundle capture.",
                    "Original owner inputs remained immutable; only pre-enrolled exact dependency copies from the writable home could be model artifacts.",
                    "Seven exact ordered scripting classpath artifact pins and the intended -Xplugin operand matched; plugin metadata/outputs remained unqualified.",
                    "All actual compilation contexts remained initial review obligations; no compiler or rule coverage was certified."))
        } catch (error: Exception) {
            val code = when (error) {
                is OwnerFixtureProblem -> error.code
                is ProjectModelException -> error.code
                is ExecutionProblem -> error.code
                else -> "owner_model_fixture_prerequisite_failed"
            }
            OwnerModelFixtureEnvelope("unavailable", "jvm-owner-export", snapshotId, executionId,
                reasons = listOf(code), assertions = listOf("No model completeness, compiler success or runtime qualification was inferred from this failure."))
        }
        val planPath = result.parent.resolve("owner-lib-main-input-plan-${executionId ?: UUID.randomUUID()}.json")
        val planPublication = try {
            Files.createDirectories(result.parent)
            publishOwnerJvmPlanEvidence(planPath, planEvidence)
        } catch (error: Exception) {
            OwnerJvmPlanPublication("publication_failed", planPath.toString(), failureCode = ownerJvmPlanFailure(error).code)
        }
        val retained = envelope.copy(ownerRuntimeDigest = runtimeIdentity,
            runtimeInputDigest = runtimeInputDigest, startupDigest = startup?.digest,
            startupEvidencePath = startup?.evidencePath?.toString(), privateWorkRoot = work.toString(),
            executionHomeDigest = executionHome?.digest, executionHomeEvidencePath = executionHome?.evidencePath?.toString(),
            executionHomeRoot = executionHome?.root?.toString(),
            intendedInputPlan = planPublication,
            processOutcome = outcome?.let(::ownerFixtureProcessEvidence))
        Files.createDirectories(result.parent)
        outcome?.let { Files.writeString(result.parent.resolve("owner-model-private-process.log"), it.stdout + "\n" + it.stderr) }
        Files.writeString(result, ContractJson.encodeToString(retained) + "\n")
        println(ContractJson.encodeToString(retained))
        check(retained.state == "exported_and_trusted_imported") { "Owner model fixture unavailable; structured local result retained" }
    }
}

@Serializable internal data class OwnerFixtureModule(val id: String, val directory: String, val buildFile: String?)
@Serializable internal data class OwnerFixtureSourceSet(val id: String, val moduleId: String, val name: String,
    val dependsOn: List<String>, val sourceRoots: List<String>, val resourceRoots: List<String>)
@Serializable internal data class OwnerFixtureCompilation(val id: String, val moduleId: String, val target: String,
    val platform: String, val name: String, val defaultSourceSetId: String, val sourceSetIds: List<String>,
    val compileTaskPath: String, val dependencyConfiguration: String, val associatedCompilationIds: List<String>,
    val projectProducerIds: List<String>)
/** Ordered classpath files for default kotlin.scripting, not seven independent plugins. */
@Serializable internal data class OwnerFixturePluginClasspathPin(val seedRelativePath: String, val coordinate: String,
    val digest: String, val size: Long)
@Serializable internal data class OwnerFixtureContractData(val schemaVersion: Int, val caseId: String,
    val gradleVersion: String, val kotlinVersion: String, val javaMajor: Int, val files: List<ModelFile>,
    val modules: List<OwnerFixtureModule>, val sourceSets: List<OwnerFixtureSourceSet>,
    val compilations: List<OwnerFixtureCompilation>, val configurationPaths: List<String>,
    val compilerPluginClasspath: List<OwnerFixturePluginClasspathPin>)

internal data class OwnerFixtureCapture(val root: Path, val snapshot: SnapshotManifest, val verify: () -> Unit)
internal class OwnerFixtureProblem(val code: String) : IllegalStateException(code)

/** Preserve an owner callback's exact rejection without changing the runtime's public API.
 * This adapter neither handles nor converts unrelated exceptions into owner evidence. */
internal fun ownerFixtureExecutionVerification(verify: () -> Unit) {
    try { verify() }
    catch (error: OwnerFixtureProblem) {
        throw ExecutionProblem(error.code, "Owner fixture input verification failed").apply { initCause(error) }
    }
}

/** Pure exact lookup/comparison only, not enrollment or file/execution authority.
 * The driver supplies the private registry, never a map taken from model JSON. */
internal fun ownerFixturePinnedPluginClasspath(pins: List<OwnerFixturePluginClasspathPin>, root: Path,
    registered: Map<String, ModelArtifact>): List<ModelArtifact> {
    expect(root.isAbsolute && root.normalize() == root && pins.size == 7 &&
        pins.map { it.seedRelativePath }.distinct().size == pins.size, "owner_fixture_plugin_classpath_pin_mismatch")
    return pins.map { pin ->
        logicalPath(pin.seedRelativePath)
        expect(pin.seedRelativePath.startsWith("caches/modules-2/files-2.1/") && Digests.valid(pin.digest) &&
            pin.size in 0..512L * 1024 * 1024 &&
            pin.coordinate == "compiler-plugin:${Path.of(pin.seedRelativePath).fileName}", "owner_fixture_plugin_classpath_pin_mismatch")
        val path = root.resolve(pin.seedRelativePath)
        expect(',' !in path.toString(), "owner_fixture_plugin_classpath_pin_mismatch")
        val artifact = registered[path.toString()] ?: throw OwnerFixtureProblem("owner_fixture_artifact_unregistered")
        expect(artifact.path == path.toString() && artifact.digest == pin.digest && artifact.size == pin.size,
            "owner_fixture_plugin_classpath_pin_mismatch")
        artifact.copy(coordinate = pin.coordinate)
    }
}

/** Independently reviewed fixed fixture input domain, not a model-report echo. */
internal class OwnerFixtureContract private constructor(val value: OwnerFixtureContractData, val digest: String) {
    fun capture(source: Path, work: Path): OwnerFixtureCapture {
        val root = source.toAbsolutePath().normalize()
        expect(regularFiles(root).map { root.relativize(it).toString().replace('\\', '/') }.sorted() == value.files.map { it.path },
            "owner_fixture_source_inventory_changed")
        val changes = value.files.map { file ->
            val path = root.resolve(file.path)
            expect(hashFile(path, 32L * 1024 * 1024, file.size) == file.digest, "owner_fixture_source_bytes_changed")
            val bytes = readOwnerFixtureInput(path, 32 * 1024 * 1024, file.size)
            expect(bytes.size.toLong() == file.size && Digests.sha256(bytes) == file.digest, "owner_fixture_source_bytes_changed")
            SnapshotChange.PutFile(SnapshotPath(path = file.path), ImmutableBlob.copyOf(bytes))
        }
        val identity = SnapshotIdentity("model-fixture-owner", "model-fixture-workspace", UUID.randomUUID().toString())
        val store = SnapshotStore(work.resolve("cas"), identity.ownerId)
        store.registerWorktree(identity)
        val commit = store.sealOverlay(OverlayRequest(identity, "explicit-owner-fixture-driver", "explicit-owner-fixture-session",
            "fixed-fixture-producer-patch", null, 0, changes, scope = SnapshotScope("Complete fixed hash-pinned fixture producer input domain")))
        val materialized = store.materialize(identity, commit.snapshotId, work.resolve("snapshot"))
        val snapshot = materialized.manifest.toContractManifest()
        expect(snapshot.files.map { ModelFile(it.path, it.digest, it.size) } == value.files, "owner_fixture_capture_domain_changed")
        return OwnerFixtureCapture(materialized.root, snapshot) {
            expect(store.verifyMaterialization(materialized).valid, "owner_fixture_registered_capture_changed")
        }
    }

    fun verifyDomain(snapshot: SnapshotManifest, graph: GradleProjectExport, registry: OwnerFixtureRuntimeRegistry) {
        verifySourceDomain(snapshot, graph)
        verifyCompilerPluginDomain(graph, registry.compilerPluginClasspath)
        graph.compilations.forEach { actual ->
            val chain = actual.toolchain ?: throw OwnerFixtureProblem("owner_fixture_compiler_domain_changed")
            expect(chain.runtimeArtifacts.map { it.path }.sorted() == registry.jdkPaths, "owner_fixture_selected_jdk_domain_incomplete")
            chain.compilerPlugins.forEach(registry::verifyArtifact)
        }
        expect(graph.externalConfigurationArtifacts.map { it.path } == listOf(registry.exporterPath), "owner_fixture_external_configuration_domain_changed")
        graph.externalConfigurationArtifacts.forEach(registry::verifyArtifact)
    }

    /** Pure fixed-classpath/argv comparison. Production expected paths come ONLY from
     * prelaunch registry binding. This verifies no plugin metadata/properties/outputs,
     * file identity, executed compiler invocation or full compiler-input authority. */
    fun verifyCompilerPluginDomain(graph: GradleProjectExport, expectedClasspath: List<ModelArtifact>) {
        expect(graph.compilations.map { it.id }.sorted() == value.compilations.map { it.id }, "owner_fixture_compilation_domain_incomplete")
        expect(expectedClasspath.size == value.compilerPluginClasspath.size && expectedClasspath.zip(value.compilerPluginClasspath).all { (artifact, pin) ->
            artifact.coordinate == pin.coordinate && artifact.digest == pin.digest && artifact.size == pin.size &&
                Path.of(artifact.path).let { it.isAbsolute && it.normalize().toString() == artifact.path && it.endsWith(Path.of(pin.seedRelativePath)) } &&
                ',' !in artifact.path
        }, "owner_fixture_plugin_classpath_pin_mismatch")
        val operand = "-Xplugin=" + expectedClasspath.joinToString(",") { it.path }
        graph.compilations.forEach { actual ->
            val chain = actual.toolchain ?: throw OwnerFixtureProblem("owner_fixture_compiler_domain_changed")
            expect(chain.kind == actual.platform && chain.compilerVersion == value.kotlinVersion, "owner_fixture_compiler_domain_changed")
            expect(chain.compilerPlugins == expectedClasspath, "owner_fixture_compiler_plugin_classpath_changed")
            expect(actual.compilerArguments.filter { it.startsWith("-Xplugin") } == listOf(operand), "owner_fixture_compiler_plugin_operand_changed")
        }
    }

    /** Pure domain validation only; never an execution/artifact/import capability. */
    fun verifySourceDomain(snapshot: SnapshotManifest, graph: GradleProjectExport) {
        expect(snapshot.files.map { ModelFile(it.path, it.digest, it.size) } == value.files &&
            snapshot.unknownInputs.isEmpty() && snapshot.scopeExclusions.isEmpty(), "owner_fixture_capture_domain_incomplete")
        expect(graph.gradleVersion == value.gradleVersion, "owner_fixture_gradle_version_mismatch")
        expect(graph.modules.map { OwnerFixtureModule(it.id, it.directory, it.buildFile) }.sortedBy { it.id } == value.modules,
            "owner_fixture_module_domain_incomplete")
        expect(graph.sourceSets.map { OwnerFixtureSourceSet(it.id, it.moduleId, it.name, it.dependsOn.sorted(),
            it.sourceRoots.sorted(), it.resourceRoots.sorted()) }.sortedBy { it.id } == value.sourceSets,
            "owner_fixture_source_root_domain_incomplete")
        fun members(roots: List<String>): List<ModelFile> = value.files.filter { file -> roots.any { file.path.startsWith("$it/") } }
        val sets = value.sourceSets.associateBy { it.id }
        graph.sourceSets.forEach { actual ->
            val expected = sets.getValue(actual.id)
            expect(actual.sources.sortedBy { it.path } == members(expected.sourceRoots) &&
                actual.resources.sortedBy { it.path } == members(expected.resourceRoots), "owner_fixture_source_resource_members_incomplete")
        }
        expect(graph.compilations.map { it.id }.sorted() == value.compilations.map { it.id }, "owner_fixture_compilation_domain_incomplete")
        graph.compilations.forEach { actual ->
            val expected = value.compilations.single { it.id == actual.id }
            val selected = OwnerFixtureCompilation(actual.id, actual.moduleId, actual.target, actual.platform, actual.name,
                actual.defaultSourceSetId, actual.sourceSetIds.sorted(), actual.compileTaskPath, actual.dependencyConfiguration,
                actual.associatedCompilationIds.sorted(), actual.projectDependencies.mapNotNull { it.compilationId }.sorted())
            expect(selected == expected && actual.projectDependencies.all { it.compilationId != null }, "owner_fixture_actual_compilation_graph_changed")
            expect(actual.sourceFiles.sortedBy { it.path } == expected.sourceSetIds.flatMap { members(sets.getValue(it).sourceRoots) }
                .distinctBy { it.path }.sortedBy { it.path }, "owner_fixture_actual_compile_members_incomplete")
            expect(actual.auxiliaryInputFiles.isEmpty() && actual.auxiliaryArtifacts.isEmpty(), "owner_fixture_auxiliary_domain_unknown")
        }
        expect(graph.configurationInputs.sortedBy { it.path } == value.files.filter { it.path in value.configurationPaths },
            "owner_fixture_executable_configuration_readset_incomplete")
        // Pinned scripts contain no dynamic reads or generators; seed has no user init/properties.
        // This conclusion comes from that closed fixture contract, never an empty export list.
        expect(graph.generatedInputs.isEmpty() && graph.includedBuilds.isEmpty(), "owner_fixture_generation_or_composite_domain_changed")
    }

    companion object {
        private const val PIN = "sha256:ce8db3ddfcba040f71b3fa6177af540da743c2b44fedaded327816a1f03adb53"
        fun load(directory: Path): OwnerFixtureContract {
            val bytes = readOwnerFixtureInput(directory.resolve("input-contract.json"), 1024 * 1024)
            expect(bytes.size in 1..1024 * 1024 && Digests.sha256(bytes) == PIN, "owner_fixture_contract_pin_changed")
            val data = ContractJson.decodeFromString<OwnerFixtureContractData>(ownerFixtureUtf8(bytes))
            expect(data.schemaVersion == 1 && data.caseId == "jvm-owner-export" && data.javaMajor == 21 &&
                data.files.map { it.path } == data.files.map { it.path }.distinct().sorted(), "owner_fixture_contract_invalid")
            data.files.forEach { logicalPath(it.path); digest(it.digest); expect(it.size in 0..32L * 1024 * 1024, "owner_fixture_contract_invalid") }
            expect(data.compilerPluginClasspath.size == 7 && data.compilerPluginClasspath.map { it.seedRelativePath }.distinct().size == 7,
                "owner_fixture_contract_invalid")
            data.compilerPluginClasspath.forEach { pin ->
                logicalPath(pin.seedRelativePath); digest(pin.digest)
                expect(pin.seedRelativePath.startsWith("caches/modules-2/files-2.1/") && pin.size in 0..512L * 1024 * 1024 &&
                    pin.coordinate == "compiler-plugin:${Path.of(pin.seedRelativePath).fileName}", "owner_fixture_contract_invalid")
            }
            val covered = data.configurationPaths.toSet() + data.sourceSets.flatMap { set ->
                data.files.filter { file -> (set.sourceRoots + set.resourceRoots).any { file.path.startsWith("$it/") } }.map { it.path }
            }
            expect(covered == data.files.map { it.path }.toSet(), "owner_fixture_contract_unassigned_input")
            return OwnerFixtureContract(data, PIN)
        }
    }
}

@Serializable internal data class OwnerRuntimeFile(val kind: String, val relativePath: String, val digest: String, val size: Long, val executable: Boolean)
@Serializable internal data class OwnerRuntimeDirectory(val kind: String, val relativePath: String)
@Serializable internal data class OwnerRuntimeOmission(val kind: String, val path: String, val reason: String)
@Serializable internal data class OwnerRuntimeManifest(val schemaVersion: Int, val gradleArchive: String, val gradleHome: String, val javaHome: String,
    val gradleSeedHome: String, val files: List<OwnerRuntimeFile>, val directories: List<OwnerRuntimeDirectory>,
    val helperArtifacts: List<ModelArtifact>, val omittedInputEntries: List<OwnerRuntimeOmission>)

/** Registry is frozen BEFORE execution; the report cannot enroll new artifact paths/bytes. */
internal class OwnerFixtureRuntimeRegistry private constructor(private val manifest: OwnerRuntimeManifest) {
    val gradleHome: Path = Path.of(manifest.gradleHome)
    val javaHome: Path = Path.of(manifest.javaHome)
    val gradleSeedHome: Path = Path.of(manifest.gradleSeedHome)
    private val roots = mapOf("gradle" to gradleHome, "jdk" to javaHome, "seed" to gradleSeedHome)
    private val artifacts = manifest.files.associate { file ->
        val path = roots.getValue(file.kind).resolve(file.relativePath)
        path.toString() to ModelArtifact("owner-fixture-input:${file.kind}:${file.relativePath}", path.toString(), file.digest, file.size)
    }.toMutableMap().apply { manifest.helperArtifacts.forEach { put(it.path, it) } }
    val jdkPaths: List<String> = manifest.files.filter { it.kind == "jdk" }.map { javaHome.resolve(it.relativePath).toString() }.sorted()
    val runtimeDigest: String = Digests.sha256("ktchecker/owner-fixture-inputs/1\u0000" + ContractJson.encodeToString(manifest))
    var exporterPath: String? = null
        private set
    private var executionHome: OwnerFixtureExecutionHome? = null
    private var boundCompilerPluginClasspath: List<ModelArtifact>? = null
    val compilerPluginClasspath: List<ModelArtifact>
        get() = boundCompilerPluginClasspath?.toList() ?: throw OwnerFixtureProblem("owner_fixture_plugin_classpath_unbound")

    /** Existing complete owner JDK inventory only; this exposes no enrollment or compiler permit. */
    fun selectedJvmJdkInputs(): OwnerJvmJdkInputs = OwnerJvmJdkInputs.fromManifest(javaHome, runtimeDigest,
        manifest.files.filter { it.kind == "jdk" }, manifest.directories.filter { it.kind == "jdk" })

    fun deriveExecutionHome(work: Path): OwnerFixtureExecutionHome {
        expect(executionHome == null, "owner_fixture_execution_home_already_registered")
        verifyRuntimeInputs()
        val home = OwnerFixtureExecutionHome.derive(gradleSeedHome, manifest.files, manifest.directories, runtimeDigest,
            work.resolve("execution-home"), work.resolve("execution-home-evidence.json"))
        home.dependencyArtifacts.forEach { artifact ->
            expect(artifact.path !in artifacts, "owner_fixture_execution_home_artifact_collision")
            artifacts[artifact.path] = artifact
        }
        executionHome = home
        verifyRuntimeInputs()
        return home
    }

    fun bindCompilerPluginClasspath(pins: List<OwnerFixturePluginClasspathPin>) {
        expect(boundCompilerPluginClasspath == null && exporterPath == null, "owner_fixture_plugin_classpath_already_bound")
        val home = executionHome ?: throw OwnerFixtureProblem("owner_fixture_plugin_classpath_unbound")
        // Both exact original prepared seed entries and pre-enrolled copy identities
        // must match. No discovered/generated cache file or report-selected path joins.
        ownerFixturePinnedPluginClasspath(pins, gradleSeedHome, artifacts).forEach(::verifyArtifact)
        val copies = ownerFixturePinnedPluginClasspath(pins, home.root, artifacts)
        expect(copies.all { home.containsDependency(it.path) }, "owner_fixture_artifact_unregistered")
        copies.forEach(::verifyArtifact)
        boundCompilerPluginClasspath = copies.toList()
    }

    fun artifact(path: Path): ModelArtifact = artifacts[path.toString()] ?: throw OwnerFixtureProblem("owner_fixture_artifact_unregistered")
    fun verifyArtifact(actual: ModelArtifact) {
        val registered = artifacts[actual.path] ?: throw OwnerFixtureProblem("owner_fixture_artifact_unregistered")
        expect(actual.digest == registered.digest && actual.size == registered.size &&
            hashFile(Path.of(actual.path), 512L * 1024 * 1024, registered.size) == registered.digest, "owner_fixture_artifact_registry_mismatch")
        executionHome?.let { home -> if (home.containsDependency(actual.path)) home.verifyDependencyArtifact(actual) }
    }
    fun verifyRuntimeInputs() {
        verifyPrivateRuntimeRoots(roots.values)
        verifyOwnerRuntimeInventories(roots, manifest.files, manifest.directories)
        verifyOwnerRuntimeFileBytes(roots, manifest.files)
        manifest.helperArtifacts.forEach(::verifyArtifact)
        exporterPath?.let { verifyArtifact(artifacts.getValue(it)) }
        executionHome?.verifyInputs()
    }
    fun registerExporter(output: Path) {
        expect(exporterPath == null, "owner_fixture_exporter_already_registered")
        expect(boundCompilerPluginClasspath != null, "owner_fixture_plugin_classpath_unbound")
        val path = output.resolve(GradleModelExporter.SCRIPT)
        expect(hashFile(path, 1024 * 1024L) == GradleModelExporter.scriptDigest(), "owner_fixture_exporter_pin_changed")
        exporterPath = path.toString()
        artifacts[path.toString()] = ModelArtifact("owner-fixture-pinned-exporter", path.toString(), GradleModelExporter.scriptDigest(), Files.size(path))
    }

    companion object {
        private const val GRADLE_ARCHIVE_DIGEST = "sha256:9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14"
        fun load(path: Path): OwnerFixtureRuntimeRegistry {
            val bytes = readOwnerFixtureInput(path, 8 * 1024 * 1024)
            expect(bytes.size in 1..8 * 1024 * 1024, "owner_fixture_runtime_manifest_bound")
            val manifest = ContractJson.decodeFromString<OwnerRuntimeManifest>(ownerFixtureUtf8(bytes))
            expect(manifest.schemaVersion == 1 && manifest.files.size in 1..20_000 &&
                manifest.files.size + manifest.directories.size <= 20_000 &&
                manifest.files.sumOf { it.size } in 1..2L * 1024 * 1024 * 1024, "owner_fixture_runtime_manifest_bound")
            val roots = mapOf("gradle" to Path.of(manifest.gradleHome), "jdk" to Path.of(manifest.javaHome), "seed" to Path.of(manifest.gradleSeedHome))
            expect(roots.values.all { it.isAbsolute && it.normalize() == it && !Files.isSymbolicLink(it) } &&
                roots.values.toList().let { values -> values.indices.all { a -> values.indices.all { b -> a == b || !values[a].startsWith(values[b]) } } },
                "owner_fixture_runtime_roots_invalid")
            verifyPrivateRuntimeRoots(roots.values)
            expect(manifest.files.map { it.kind to it.relativePath }.distinct().size == manifest.files.size, "owner_fixture_runtime_manifest_duplicate")
            val fileNames = manifest.files.map { it.kind to it.relativePath }.toSet()
            expect(manifest.directories.map { it.kind to it.relativePath }.distinct().size == manifest.directories.size &&
                manifest.directories.none { (it.kind to it.relativePath) in fileNames },
                "owner_fixture_runtime_manifest_duplicate")
            manifest.directories.forEach { directory ->
                expect(directory.kind in roots, "owner_fixture_runtime_manifest_invalid")
                logicalPath(directory.relativePath)
                if (directory.kind == "seed") expect(directory.relativePath.substringBefore('/') in setOf("caches", "wrapper"), "owner_fixture_external_seed_configuration_forbidden")
            }
            manifest.files.forEach { file ->
                expect(file.kind in roots && file.size in 0..512L * 1024 * 1024, "owner_fixture_runtime_manifest_invalid")
                logicalPath(file.relativePath); digest(file.digest)
                if (file.kind == "seed") expect(file.relativePath.substringBefore('/') in setOf("caches", "wrapper"), "owner_fixture_external_seed_configuration_forbidden")
            }
            roots.forEach { (kind, root) -> expect(regularFiles(root).map { root.relativize(it).toString().replace('\\', '/') }.sorted() ==
                manifest.files.filter { it.kind == kind }.map { it.relativePath }.sorted(), "owner_fixture_runtime_inventory_changed") }
            val helperPaths = listOf("/bin/sh", "/usr/bin/uname", "/usr/bin/xargs", "/usr/bin/sed", "/usr/bin/tr").map { Path.of(it).toRealPath().toString() }
            expect(manifest.helperArtifacts.map { it.path }.sorted() == helperPaths.sorted(), "owner_fixture_helper_domain_changed")
            manifest.helperArtifacts.forEach { expect(it.digest != null && it.size != null, "owner_fixture_helper_identity_missing") }
            manifest.omittedInputEntries.forEach { omission ->
                logicalPath(omission.path)
                expect(omission.kind == "seed" && omission.path.substringBefore('/') in setOf("caches", "wrapper") &&
                    (omission.path.endsWith(".lock") || omission.path.endsWith(".lck")), "owner_fixture_input_omission_unqualified")
            }
            val registry = OwnerFixtureRuntimeRegistry(manifest)
            verifyOwnerRuntimeInventories(roots, manifest.files, manifest.directories)
            verifyOfficialGradle(registry, manifest)
            manifest.files.forEach { registry.verifyArtifact(registry.artifact(roots.getValue(it.kind).resolve(it.relativePath))) }
            manifest.helperArtifacts.forEach(registry::verifyArtifact)
            expect(Files.isExecutable(registry.gradleHome.resolve("bin/gradle")) && Files.isExecutable(registry.javaHome.resolve("bin/java")) &&
                manifest.files.any { it.kind == "gradle" && it.relativePath == "lib/gradle-gradle-cli-main-9.6.1.jar" } &&
                ownerFixtureUtf8(readOwnerFixtureInput(registry.javaHome.resolve("release"), 1024 * 1024))
                    .contains("JAVA_VERSION=\"21"), "owner_fixture_selected_runtime_unavailable")
            return registry
        }

        /** An archive SHA-256 assertion in JSON is not proof: hash the independently pinned ZIP
         * and every actual extracted runtime byte before allowing the launcher to execute. */
        private fun verifyOfficialGradle(registry: OwnerFixtureRuntimeRegistry, manifest: OwnerRuntimeManifest) {
            val archive = Path.of(manifest.gradleArchive)
            expect(hashFile(archive, 512L * 1024 * 1024) == GRADLE_ARCHIVE_DIGEST, "owner_fixture_vendor_gradle_archive_mismatch")
            val entries = mutableMapOf<String, Pair<String, Long>>()
            val directories = mutableSetOf<String>()
            var total = 0L
            ZipFile(archive.toFile()).use { zip -> zip.entries().asSequence().forEach { entry ->
                expect(entry.name.startsWith("gradle-9.6.1/"), "owner_fixture_vendor_gradle_archive_invalid")
                if (entry.isDirectory) {
                    val directory = entry.name.removePrefix("gradle-9.6.1/").trimEnd('/')
                    if (directory.isNotEmpty()) { logicalPath(directory); directories += directory }
                    return@forEach
                }
                val relative = entry.name.removePrefix("gradle-9.6.1/")
                logicalPath(relative)
                relative.indices.filter { relative[it] == '/' }.forEach { directories += relative.substring(0, it) }
                val sha = MessageDigest.getInstance("SHA-256")
                var count = 0L
                zip.getInputStream(entry).use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val amount = stream.read(buffer)
                        if (amount < 0) break
                        count += amount; total += amount
                        expect(count <= 512L * 1024 * 1024 && total <= 1024L * 1024 * 1024, "owner_fixture_vendor_gradle_archive_bound")
                        sha.update(buffer, 0, amount)
                    }
                }
                expect(entries.put(relative, ("sha256:" + sha.digest().joinToString("") { "%02x".format(it) }) to count) == null,
                    "owner_fixture_vendor_gradle_archive_duplicate")
            } }
            expect(manifest.files.filter { it.kind == "gradle" }.associate { it.relativePath to (it.digest to it.size) } == entries,
                "owner_fixture_extracted_gradle_distribution_mismatch")
            expect(manifest.directories.filter { it.kind == "gradle" }.map { it.relativePath }.toSet() == directories,
                "owner_fixture_extracted_gradle_distribution_mismatch")
            expect(hashFile(archive, 512L * 1024 * 1024) == GRADLE_ARCHIVE_DIGEST, "owner_fixture_vendor_gradle_archive_changed")
            // Registry verifies the extracted runtime against these independently derived bytes.
            manifest.files.filter { it.kind == "gradle" }.forEach { registry.verifyArtifact(registry.artifact(registry.gradleHome.resolve(it.relativePath))) }
        }
    }
}

/** No public/serialized completion receipt constructor or report-controlled registration path. */
private class OwnerFixtureInvocation private constructor(val id: String, private val input: AnalysisInput,
    val outcome: ProcessOutcome, private val startup: OwnerFixtureStartupCapture) {
    fun verify(request: ModelImportRequest) {
        startup.verifyCompletion(outcome)
        expect(request.executionId == id && request.snapshot == input.snapshot && request.materializedRoot == input.materializedRoot &&
            request.configurationDigest == input.context.configurationDigest && request.exporterDigest == GradleModelExporter.scriptDigest() &&
            outcome.successfulExecution && outcome.exitCode == 0 && !outcome.cancelled && !outcome.timedOut && !outcome.truncated && outcome.reasons.isEmpty(),
            "owner_fixture_invocation_not_verified")
        val output = requireNotNull(outcome.outputDirectory)
        val profile = requireNotNull(input.execution)
        expect(outcome.command == listOf(profile.executable) + profile.arguments.map {
            it.replace("{snapshot}", input.materializedRoot).replace("{output}", output.toString())
        }, "owner_fixture_invocation_profile_changed")
        expect(Digests.sha256(outcome.report(GradleModelExporter.REPORT, 16 * 1024 * 1024)) == request.exportDigest,
            "owner_fixture_executed_report_changed")
    }
    companion object {
        fun execute(id: String, input: AnalysisInput, permit: ExecutionPermit, startup: OwnerFixtureStartupCapture,
                    retainActual: (ProcessOutcome) -> Unit): OwnerFixtureInvocation {
            var actual: ProcessOutcome? = null
            val outcome = IsolatedProcessRunner { actual = it; retainActual(it) }.run(input, ExactExecutionAuthority(permit))
            expect(actual === outcome, "owner_fixture_actual_completion_missing")
            startup.verifyCompletion(outcome)
            return OwnerFixtureInvocation(id, input, outcome, startup)
        }
    }
}

private fun expect(condition: Boolean, code: String) { if (!condition) throw OwnerFixtureProblem(code) }

private fun verifyPrivateRuntimeRoots(roots: Collection<Path>) {
    val publicPermissions = PosixFilePermission.values().filter { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") }.toSet()
    expect(roots.all { root -> Files.getPosixFilePermissions(root, NOFOLLOW_LINKS).none { it in publicPermissions } },
        "owner_fixture_runtime_root_not_private")
}

/** Pure freshness validation, not an execution/artifact/import grant. Every runtime/seed
 * directory and non-lock file is an immutable registered input, including executable DSL
 * cache classes. Only regular seed lock files may change; new directories still fail. */
internal fun verifyOwnerRuntimeInventories(roots: Map<String, Path>, files: List<OwnerRuntimeFile>, directories: List<OwnerRuntimeDirectory>) {
    fun tree(root: Path): List<Pair<String, Boolean>> {
        expect(Files.isDirectory(root, NOFOLLOW_LINKS) && !Files.isSymbolicLink(root), "owner_fixture_input_root_unsafe")
        var entries = 0
        return Files.walk(root).use { paths -> paths.filter { it != root }.map { path ->
            expect(++entries <= 50_000, "owner_fixture_runtime_tree_bound")
            expect(!Files.isSymbolicLink(path), "owner_fixture_input_alias_unqualified")
            val relative = root.relativize(path).toString().replace('\\', '/')
            logicalPath(relative)
            val directory = Files.isDirectory(path, NOFOLLOW_LINKS)
            expect(directory || Files.isRegularFile(path, NOFOLLOW_LINKS), "owner_fixture_input_nonregular")
            relative to directory
        }.sorted(compareBy<Pair<String, Boolean>> { it.first }.thenBy { it.second }).toList() }
    }
    fun expected(kind: String): List<Pair<String, Boolean>> {
        val members = files.filter { it.kind == kind }.map { it.relativePath }
        return (members.map { it to false } + directories.filter { it.kind == kind }.map { it.relativePath to true })
            .sortedWith(compareBy<Pair<String, Boolean>> { it.first }.thenBy { it.second })
    }
    listOf("gradle", "jdk").forEach { kind ->
        expect(tree(roots.getValue(kind)) == expected(kind), "owner_fixture_runtime_inventory_changed")
    }
    val seed = tree(roots.getValue("seed"))
    expect(seed.all { it.first.substringBefore('/') in setOf("caches", "wrapper") }, "owner_fixture_external_seed_configuration_forbidden")
    expect(seed.filterNot { !it.second && seedLockFile(it.first) } ==
        expected("seed").filterNot { !it.second && seedLockFile(it.first) }, "owner_fixture_seed_inventory_changed")
}

private fun seedLockFile(path: String): Boolean = path.endsWith(".lock") || path.endsWith(".lck")

/** Pure byte/permission validation only. There is no runtime or model authority constructor
 * here, and no post-execution enrollment of newly generated cache or artifact bytes. */
internal fun verifyOwnerRuntimeFileBytes(roots: Map<String, Path>, files: List<OwnerRuntimeFile>) {
    files.filterNot { it.kind == "seed" && seedLockFile(it.relativePath) }.forEach { file ->
        val path = roots.getValue(file.kind).resolve(file.relativePath)
        expect(hashFile(path, 512L * 1024 * 1024, file.size) == file.digest, "owner_fixture_artifact_registry_mismatch")
        expect(Files.isExecutable(path) == file.executable, "owner_fixture_runtime_permissions_changed")
    }
}

/** Bounded data read only, never an input registry/capture/execution grant. Size is checked
 * before allocating any content buffer; every parent and leaf is anchored and no-follow. */
internal fun readOwnerFixtureInput(path: Path, limit: Int, expectedSize: Long? = null): ByteArray {
    expect(limit in 1..32 * 1024 * 1024 && (expectedSize == null || expectedSize in 0..limit.toLong()),
        "owner_fixture_input_bound")
    expect(path.isAbsolute && path.normalize() == path && path.nameCount > 0, "owner_fixture_input_unsafe")
    fun sameIdentity(before: BasicFileAttributes, after: BasicFileAttributes): Boolean =
        before.fileKey() != null && before.fileKey() == after.fileKey() && before.size() == after.size() &&
            before.lastModifiedTime() == after.lastModifiedTime() && before.isRegularFile == after.isRegularFile &&
            before.isDirectory == after.isDirectory && !after.isSymbolicLink
    fun sameDirectory(before: BasicFileAttributes, after: BasicFileAttributes): Boolean =
        before.fileKey() != null && before.fileKey() == after.fileKey() && before.isDirectory &&
            after.isDirectory && !after.isSymbolicLink
    try {
        Files.newDirectoryStream(path.root).use { directory ->
            val secure = directory as? SecureDirectoryStream<Path>
                ?: throw OwnerFixtureProblem("owner_fixture_secure_io_unavailable")
            fun read(parent: SecureDirectoryStream<Path>, index: Int): ByteArray {
                val name = path.getName(index)
                val view = parent.getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS)
                val before = view.readAttributes()
                expect(before.fileKey() != null && !before.isSymbolicLink, "owner_fixture_input_unsafe")
                if (index < path.nameCount - 1) {
                    expect(before.isDirectory, "owner_fixture_input_unsafe")
                    return parent.newDirectoryStream(name, NOFOLLOW_LINKS).use { child ->
                        val opened = child.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes()
                        expect(sameDirectory(before, opened), "owner_fixture_input_changed")
                        val bytes = read(child, index + 1)
                        expect(sameDirectory(before, view.readAttributes()), "owner_fixture_input_changed")
                        bytes
                    }
                }
                expect(before.isRegularFile, "owner_fixture_input_unsafe")
                expect(before.size() in 0..limit.toLong() && (expectedSize == null || before.size() == expectedSize),
                    "owner_fixture_input_bound")
                return parent.newByteChannel(name, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
                    expect(channel.size() == before.size(), "owner_fixture_input_changed")
                    // Exact prechecked capacity prevents ByteArrayOutputStream growth past the bound.
                    val bytes = ByteArrayOutputStream(before.size().toInt())
                    val buffer = ByteBuffer.allocate(minOf(64 * 1024, maxOf(1, before.size().toInt())))
                    var count = 0L
                    var noProgress = 0
                    while (true) {
                        buffer.clear()
                        val amount = channel.read(buffer)
                        if (amount < 0) break
                        if (amount == 0) {
                            expect(++noProgress <= 16, "owner_fixture_input_changed")
                            continue
                        }
                        noProgress = 0
                        count += amount
                        // Reject growth before writing or extending the result allocation.
                        expect(count <= before.size() && count <= limit, "owner_fixture_input_changed")
                        bytes.write(buffer.array(), 0, amount)
                    }
                    expect(count == before.size() && channel.size() == before.size() &&
                        sameIdentity(before, view.readAttributes()), "owner_fixture_input_changed")
                    bytes.toByteArray()
                }
            }
            return read(secure, 0)
        }
    } catch (error: OwnerFixtureProblem) { throw error }
    catch (error: Exception) { throw OwnerFixtureProblem("owner_fixture_input_unsafe").apply { initCause(error) } }
}

internal fun ownerFixtureUtf8(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
} catch (error: CharacterCodingException) {
    throw OwnerFixtureProblem("owner_fixture_input_utf8").apply { initCause(error) }
}

/** Pure identity composition only. Neither digest is an execution or ingress capability. */
internal fun ownerFixtureRuntimeIdentity(inputs: String, startup: String, executionHome: String): String {
    expect(Digests.valid(inputs) && Digests.valid(startup) && Digests.valid(executionHome), "owner_fixture_runtime_identity_invalid")
    return Digests.sha256("ktchecker/owner-fixture-runtime/3\u0000" + inputs + "\u0000" + startup + "\u0000" + executionHome)
}

private fun regularFiles(root: Path): List<Path> {
    expect(Files.isDirectory(root, NOFOLLOW_LINKS) && !Files.isSymbolicLink(root), "owner_fixture_input_root_unsafe")
    var entries = 0
    return Files.walk(root).use { paths -> paths.filter { it != root }.filter { path ->
        expect(++entries <= 50_000, "owner_fixture_input_tree_bound")
        expect(!Files.isSymbolicLink(path), "owner_fixture_input_alias_unqualified")
        !Files.isDirectory(path, NOFOLLOW_LINKS)
    }.map { path -> expect(Files.isRegularFile(path, NOFOLLOW_LINKS), "owner_fixture_input_nonregular"); path }.sorted().toList() }
}

@Serializable internal data class OwnerFixtureContext(val compilationId: String, val modelCompleteness: String,
    val fullProjectModel: Boolean, val gaps: List<String>)
/** Serialized actual-process evidence only, never a completion/ingress receipt constructor. */
@Serializable internal data class OwnerFixtureProcessEvidence(val status: String, val reasons: List<String>, val exitCode: Int?,
    val cancelled: Boolean, val timedOut: Boolean, val truncated: Boolean, val executionConfirmed: Boolean,
    val supervisor: OwnerFixtureSupervisorIdentity?, val command: List<String>, val outputDirectory: String?,
    val stdoutDigest: String, val stderrDigest: String)
private fun ownerFixtureProcessEvidence(actual: ProcessOutcome) = OwnerFixtureProcessEvidence(actual.status, actual.reasons,
    actual.exitCode, actual.cancelled, actual.timedOut, actual.truncated, actual.executionConfirmed,
    actual.supervisorIdentity?.let { OwnerFixtureSupervisorIdentity(it.protocolVersion, it.target, it.sourceDigest, it.binaryDigest) },
    actual.command, actual.outputDirectory?.toString(), Digests.sha256(actual.stdout), Digests.sha256(actual.stderr))

@Serializable internal data class OwnerModelFixtureEnvelope(val state: String, val caseId: String, val snapshotId: String,
    val executionId: String? = null, val sourceContractDigest: String? = null, val ownerRuntimeDigest: String? = null,
    val modelIdentity: ProjectModelIdentity? = null, val modelCertificateId: String? = null,
    val contexts: List<OwnerFixtureContext> = emptyList(), val reasons: List<String> = emptyList(),
    val requiredCompilationIds: List<String> = emptyList(), val assertions: List<String> = emptyList(),
    val qualified: Boolean = false,
    val runtimeInputDigest: String? = null, val startupDigest: String? = null, val startupEvidencePath: String? = null,
    val privateWorkRoot: String? = null, val processOutcome: OwnerFixtureProcessEvidence? = null,
    val executionHomeDigest: String? = null, val executionHomeEvidencePath: String? = null, val executionHomeRoot: String? = null,
    val intendedInputPlan: OwnerJvmPlanPublication? = null,
    val limitations: List<String> = listOf("Fixed explicitly selected trusted fixture only; this is not a general arbitrary-project capture proof.",
        "Registered model inputs are not executable compiler-input relocation or successful compiler/rule coverage.",
        "Pinned scripting classpath files/argv do not establish plugin metadata, generated outputs or an executed compiler invocation.",
        "Missing project/friend output artifacts and exporter API gaps remain unavailable; no tuple is qualified.",
        "START source/runtime/native-image identities do not attest source-to-binary builds, loaded JVM memory or a runtime tuple.",
        "The initial exact seed-copy derivation is bound; generated execution-home caches are not model/compiler artifact authority.",
        "Same-user read-only copies and native owned-child process groups are not an OS security sandbox."))

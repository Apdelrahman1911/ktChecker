package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.SnapshotManifest
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermission

internal const val OWNER_JVM_PLAN_EVIDENCE_LIMIT = 1024 * 1024
private const val MAX_ARGUMENT_BYTES = 64 * 1024

internal class OwnerJvmPlanProblem(val code: String, val role: String? = null, val tokenIndex: Int? = null) : IllegalStateException(code)
internal fun ownerJvmRequire(value: Boolean, code: String, role: String? = null, tokenIndex: Int? = null) {
    if (!value) throw OwnerJvmPlanProblem(code, role, tokenIndex)
}

internal fun ownerJvmAbsolutePath(value: String): Path {
    ownerJvmRequire(value.length in 1..4096 && value.none { it.isISOControl() } && '\\' !in value,
        "owner_jvm_path_unsafe", "path")
    val path = Path.of(value)
    ownerJvmRequire(path.isAbsolute && path.normalize().toString() == value && path.parent != null,
        "owner_jvm_path_unsafe", "path")
    return path
}

@Serializable internal data class OwnerJvmFilePin(val seedRelativePath: String, val coordinate: String, val digest: String, val size: Long)
@Serializable internal data class OwnerJvmExecutableContractData(val schemaVersion: Int, val caseId: String,
    val sourceContractDigest: String, val compilationId: String, val compileTaskPath: String,
    val compilerVersion: String, val jvmTarget: String, val moduleName: String, val source: ModelFile,
    val outputRelativePath: String, val scriptingAdapterId: String,
    val compilerRuntimeClasspath: List<OwnerJvmFilePin>, val compileClasspath: List<OwnerJvmFilePin>)

/** New authored pin; the older source contract and exporter history are not reinterpreted. */
internal class OwnerFixtureExecutableContract private constructor(val value: OwnerJvmExecutableContractData, val digest: String) {
    companion object {
        const val FILE_NAME = "executable-input-contract.json"
        const val PIN = "sha256:5a085721f56fbee0a9b7a6826a421dc0fc4d234184c4679a3f73df53290e4a88"
        fun load(directory: Path): OwnerFixtureExecutableContract {
            val bytes = readOwnerFixtureInput(directory.resolve(FILE_NAME), 64 * 1024)
            ownerJvmRequire(Digests.sha256(bytes) == PIN, "owner_jvm_contract_pin_changed", "contract")
            val data = ContractJson.decodeFromString<OwnerJvmExecutableContractData>(ownerFixtureUtf8(bytes))
            ownerJvmRequire(data.schemaVersion == 1 && data.caseId == "jvm-owner-export" &&
                data.compilationId == ":lib||main" && data.compileTaskPath == ":lib:compileKotlin" &&
                data.compilerVersion == "2.4.21" && data.jvmTarget == "21" && data.moduleName == "owner-model-fixture:lib" &&
                data.outputRelativePath == OwnerFixtureDerivedOutputTree.RELATIVE_PATH &&
                data.scriptingAdapterId == OwnerFixtureDefaultScripting2421.ADAPTER_ID &&
                data.compilerRuntimeClasspath.size == 14 && data.compileClasspath.size == 2,
                "owner_jvm_contract_invalid", "contract")
            return OwnerFixtureExecutableContract(data.copy(compilerRuntimeClasspath = immutable(data.compilerRuntimeClasspath),
                compileClasspath = immutable(data.compileClasspath)), PIN)
        }
    }
}

/** Pure pin comparison. The runtime driver separately verifies these paths in its existing registry. */
internal fun ownerJvmPinnedArtifacts(pins: List<OwnerJvmFilePin>, home: Path, count: Int, role: String): List<ModelArtifact> {
    ownerJvmAbsolutePath(home.toString())
    ownerJvmRequire(pins.size == count && pins.map { it.seedRelativePath }.distinct().size == count,
        "owner_jvm_pin_domain_invalid", role)
    return immutable(pins.map { pin ->
        logicalPath(pin.seedRelativePath)
        ownerJvmRequire(pin.seedRelativePath.startsWith("caches/modules-2/files-2.1/") &&
            pin.coordinate.length in 1..512 && Digests.valid(pin.digest) && pin.size in 1..64L * 1024 * 1024,
            "owner_jvm_pin_invalid", role)
        val path = ownerJvmAbsolutePath(home.resolve(pin.seedRelativePath).toString())
        ownerJvmRequire(',' !in path.toString() && ':' !in path.toString(), "owner_jvm_path_list_separator", role)
        ModelArtifact(pin.coordinate, path.toString(), pin.digest, pin.size)
    })
}

@Serializable internal enum class OwnerJvmFileRole { SOURCE, COMPILE_CLASSPATH, COMPILER_RUNTIME_CLASSPATH, SCRIPTING_CLASSPATH }
@Serializable internal data class OwnerJvmFileOccurrence(val role: OwnerJvmFileRole, val ordinal: Int, val path: String,
    val coordinate: String?, val digest: String, val size: Long)

@Serializable internal sealed interface OwnerJvmArgSlot {
    val index: Int
    val original: String
    @Serializable data class Scalar(override val index: Int, override val original: String) : OwnerJvmArgSlot
    @Serializable data class PathList(override val index: Int, override val original: String, val prefix: String,
        val separator: String, val files: List<OwnerJvmFileOccurrence>) : OwnerJvmArgSlot
    @Serializable data class Source(override val index: Int, override val original: String, val file: OwnerJvmFileOccurrence) : OwnerJvmArgSlot
    @Serializable data class OutputTree(override val index: Int, override val original: String, val output: OwnerJvmIntendedOutput) : OwnerJvmArgSlot
}

@Serializable internal data class OwnerJvmJdkIdentity(val javaHome: String, val runtimeInputDigest: String,
    val treeDigest: String, val fileCount: Int, val directoryCount: Int, val javaExecutable: ModelArtifact)
internal class OwnerJvmJdkInputs private constructor(val identity: OwnerJvmJdkIdentity, files: List<ModelArtifact>) {
    val files = immutable(files)
    companion object {
        /** The complete selected owner manifest, including directories, is the tree authority in the real driver.
         * This pure metadata constructor alone grants no authority; tests use explicitly synthetic inventories. */
        fun fromManifest(javaHome: Path, runtimeInputDigest: String, files: List<OwnerRuntimeFile>, directories: List<OwnerRuntimeDirectory>): OwnerJvmJdkInputs {
            ownerJvmAbsolutePath(javaHome.toString())
            ownerJvmRequire(Digests.valid(runtimeInputDigest) && files.size in 1..1024 && directories.size <= 256,
                "owner_jvm_jdk_bound", "jdk")
            val sortedFiles = files.sortedBy { it.relativePath }
            val sortedDirectories = directories.sortedBy { it.relativePath }
            val fileNames = sortedFiles.map { it.relativePath }
            val directoryNames = sortedDirectories.map { it.relativePath }
            ownerJvmRequire(fileNames.distinct().size == fileNames.size && directoryNames.distinct().size == directoryNames.size &&
                fileNames.none { it in directoryNames } && (fileNames + directoryNames).sumOf { it.length.toLong() } <= 128 * 1024,
                "owner_jvm_jdk_inventory_invalid", "jdk")
            sortedFiles.forEach {
                logicalPath(it.relativePath)
                ownerJvmRequire(it.kind == "jdk" && Digests.valid(it.digest) && it.size in 0..512L * 1024 * 1024,
                    "owner_jvm_jdk_inventory_invalid", "jdk")
            }
            sortedDirectories.forEach { logicalPath(it.relativePath); ownerJvmRequire(it.kind == "jdk", "owner_jvm_jdk_inventory_invalid", "jdk") }
            (fileNames + directoryNames).forEach { name ->
                val parent = name.substringBeforeLast('/', "")
                ownerJvmRequire(parent.isEmpty() || parent in directoryNames, "owner_jvm_jdk_parent_missing", "jdk")
            }
            val java = sortedFiles.singleOrNull { it.relativePath == "bin/java" && it.executable }
                ?: throw OwnerJvmPlanProblem("owner_jvm_selected_java_missing", "jdk")
            val artifacts = sortedFiles.map { ModelArtifact("compilation-jdk:${it.relativePath}", javaHome.resolve(it.relativePath).toString(), it.digest, it.size) }
            val treeDigest = Digests.sha256("ktchecker/owner-jvm-selected-jdk/1\u0000" + ContractJson.encodeToString(listOf(
                javaHome.toString(), runtimeInputDigest, ContractJson.encodeToString(sortedFiles), ContractJson.encodeToString(sortedDirectories))))
            return OwnerJvmJdkInputs(OwnerJvmJdkIdentity(javaHome.toString(), runtimeInputDigest, treeDigest, files.size,
                directories.size, artifacts.single { it.path == javaHome.resolve(java.relativePath).toString() }), artifacts)
        }
    }
}

internal data class OwnerJvmClassifierBindings(val sourceRoot: Path, val dependencyHome: Path, val ownerExporterOutput: Path, val jdk: OwnerJvmJdkInputs)
@Serializable internal enum class OwnerJvmComparisonOrigin { SYNTHETIC_DATA_ONLY, OWNER_REGISTRY_AFTER_TRUSTED_IMPORT }
@Serializable internal data class OwnerJvmPlanProvenance(val comparisonOrigin: OwnerJvmComparisonOrigin,
    val snapshotId: String, val modelIdentity: ProjectModelIdentity, val rawExportDigest: String, val exporterExecutionId: String,
    val sourceContractDigest: String, val runtimeInputDigest: String, val startupDigest: String, val executionHomeDigest: String)
@Serializable internal data class OwnerJvmPlanData(val provenance: OwnerJvmPlanProvenance, val executableContractDigest: String,
    val rawArgumentsDigest: String, val inputCoreIdentity: String, val launchSemantics: OwnerJvmLaunchSemantics,
    val languageVersion: String?, val apiVersion: String?, val versionMeaning: String,
    val slots: List<OwnerJvmArgSlot>, val files: List<OwnerJvmFileOccurrence>, val selectedJdk: OwnerJvmJdkIdentity,
    val output: OwnerJvmIntendedOutput, val scripting: OwnerJvmScriptingAssessment)
@Serializable internal data class OwnerJvmPlanRejection(val code: String, val role: String? = null, val tokenIndex: Int? = null)
@Serializable internal data class OwnerJvmPlanEvidence(val state: String, val plan: OwnerJvmPlanData? = null,
    val rejection: OwnerJvmPlanRejection? = null, val importedContexts: List<OwnerFixtureContext> = emptyList(),
    val importedIssueKinds: List<String> = emptyList(), val schemaVersion: Int = 1,
    val evidenceKind: String = "intended_operand_classification", val executionState: String = "unavailable", val qualified: Boolean = false,
    val limitations: List<String> = listOf("Classification is not an execution permit, complete model, compiler result or qualified tuple.",
        "Exported task arguments are intended configuration, not an observed Gradle/BTA or direct-CLI compiler invocation.",
        "Pinned default requirements do not fill missing host, plugin, BTA operation or discovery facts.",
        "No compiler attempt/output tree/ABI proof or generated-artifact enrollment is performed.",
        "START and owner manifest identities do not attest source-to-binary builds, loaded memory or hostile same-user isolation."))

internal sealed interface OwnerJvmPlanResult {
    class Classified internal constructor(val data: OwnerJvmPlanData) : OwnerJvmPlanResult
    class Rejected internal constructor(val problem: OwnerJvmPlanRejection) : OwnerJvmPlanResult
}

/** Exact fixture grammar only. This object never constructs TrustedExecution, a permit or a compiler binding. */
internal object OwnerFixtureJvmExecutableInputs {
    fun classify(contract: OwnerFixtureExecutableContract, sourceContract: OwnerFixtureContract,
        graph: GradleProjectExport, snapshot: SnapshotManifest, bindings: OwnerJvmClassifierBindings,
        provenance: OwnerJvmPlanProvenance,
        semantics: OwnerJvmLaunchSemantics = OwnerJvmLaunchSemantics.INTENDED_KGP_TASK_CONFIGURATION): OwnerJvmPlanResult = try {
        classifyExact(contract, sourceContract, graph, snapshot, bindings, provenance, semantics)
    } catch (error: OwnerJvmPlanProblem) {
        OwnerJvmPlanResult.Rejected(OwnerJvmPlanRejection(error.code, error.role, error.tokenIndex))
    } catch (error: OwnerFixtureProblem) {
        OwnerJvmPlanResult.Rejected(OwnerJvmPlanRejection(error.code, "source-domain"))
    } catch (error: ProjectModelException) {
        OwnerJvmPlanResult.Rejected(OwnerJvmPlanRejection(error.code, "model-data"))
    }

    private fun classifyExact(contract: OwnerFixtureExecutableContract, sourceContract: OwnerFixtureContract,
        graph: GradleProjectExport, snapshot: SnapshotManifest, bindings: OwnerJvmClassifierBindings,
        provenance: OwnerJvmPlanProvenance, semantics: OwnerJvmLaunchSemantics): OwnerJvmPlanResult.Classified {
        val c = contract.value
        ownerJvmRequire(java.io.File.pathSeparatorChar == ':' && java.io.File.separatorChar == '/', "owner_jvm_path_dialect_unsupported", "paths")
        ownerJvmRequire(semantics == OwnerJvmLaunchSemantics.INTENDED_KGP_TASK_CONFIGURATION,
            "owner_jvm_launch_semantics_unsupported", "launch")
        ownerJvmRequire(c.sourceContractDigest == sourceContract.digest && provenance.sourceContractDigest == sourceContract.digest,
            "owner_jvm_source_contract_mismatch", "contract")
        ownerJvmRequire(graph.compilations.size == 4 && graph.sourceSets.size == 4 && snapshot.files.size == sourceContract.value.files.size,
            "owner_jvm_fixture_domain_changed", "source-domain")
        sourceContract.verifySourceDomain(snapshot, graph)
        val actual = graph.compilations.singleOrNull { it.id == c.compilationId }
            ?: throw OwnerJvmPlanProblem("owner_jvm_compilation_missing", "compilation")
        ownerJvmRequire(actual.compileTaskPath == c.compileTaskPath && actual.sourceFiles == listOf(c.source) &&
            actual.projectDependencies.isEmpty() && actual.friendArtifacts.isEmpty() && actual.associatedCompilationIds.isEmpty() &&
            actual.auxiliaryInputFiles.isEmpty() && actual.auxiliaryArtifacts.isEmpty(), "owner_jvm_compilation_domain_changed", "compilation")
        ownerJvmRequire(actual.producedVariants == listOf("apiElements"), "owner_jvm_produced_variant_domain_changed", "output")
        ownerJvmRequire(actual.languageVersion == null && actual.apiVersion == null, "owner_jvm_version_defaults_changed", "versions")
        val roots = listOf(bindings.sourceRoot, bindings.dependencyHome, bindings.ownerExporterOutput, Path.of(bindings.jdk.identity.javaHome))
        roots.forEach { ownerJvmAbsolutePath(it.toString()) }
        ownerJvmRequire(roots.indices.all { a -> roots.indices.all { b -> a == b || !roots[a].startsWith(roots[b]) } },
            "owner_jvm_role_roots_overlap", "paths")
        ownerJvmRequire(provenance.snapshotId == snapshot.snapshotId && provenance.modelIdentity.snapshotId == snapshot.snapshotId &&
            provenance.modelIdentity.configurationDigest == sourceContract.digest &&
            provenance.runtimeInputDigest == bindings.jdk.identity.runtimeInputDigest,
            "owner_jvm_provenance_mismatch", "provenance")
        listOf(provenance.snapshotId, provenance.rawExportDigest, provenance.runtimeInputDigest, provenance.startupDigest,
            provenance.executionHomeDigest, provenance.modelIdentity.sourceDigest, provenance.modelIdentity.modelDigest,
            provenance.modelIdentity.dependencyDigest, provenance.modelIdentity.toolchainDigest, provenance.modelIdentity.exporterDigest,
            provenance.modelIdentity.gradleRuntimeDigest).forEach { ownerJvmRequire(Digests.valid(it), "owner_jvm_provenance_invalid", "provenance") }
        ownerJvmRequire(provenance.modelIdentity.gradleRuntimeDigest == ownerFixtureRuntimeIdentity(provenance.runtimeInputDigest,
            provenance.startupDigest, provenance.executionHomeDigest), "owner_jvm_provenance_mismatch", "provenance")
        ownerJvmRequire(provenance.exporterExecutionId.length in 1..128 && provenance.exporterExecutionId.none { it.isISOControl() },
            "owner_jvm_provenance_invalid", "provenance")

        val runtime = ownerJvmPinnedArtifacts(c.compilerRuntimeClasspath, bindings.dependencyHome, 14, "compiler-runtime-classpath")
        val classpath = ownerJvmPinnedArtifacts(c.compileClasspath, bindings.dependencyHome, 2, "compile-classpath")
        val plugins = ownerJvmPinnedArtifacts(sourceContract.value.compilerPluginClasspath.map {
            OwnerJvmFilePin(it.seedRelativePath, it.coordinate, it.digest, it.size)
        }, bindings.dependencyHome, 7, "scripting-classpath")
        val chain = actual.toolchain ?: throw OwnerJvmPlanProblem("owner_jvm_toolchain_missing", "toolchain")
        ownerJvmRequire(chain.kind == "jvm" && chain.compilerVersion == c.compilerVersion, "owner_jvm_toolchain_changed", "toolchain")
        ownerJvmRequire(chain.compilerArtifacts == runtime, "owner_jvm_compiler_runtime_classpath_changed", "compiler-runtime-classpath")
        ownerJvmRequire(actual.classpath == classpath, "owner_jvm_compile_classpath_changed", "compile-classpath")
        ownerJvmRequire(chain.compilerPlugins == plugins, "owner_jvm_scripting_classpath_changed", "scripting-classpath")
        ownerJvmRequire(chain.runtimeArtifacts.sortedBy { it.path } == bindings.jdk.files, "owner_jvm_selected_jdk_changed", "jdk")

        val argv = actual.compilerArguments
        ownerJvmRequire(argv.size == 13, "owner_jvm_argument_count_changed", "arguments")
        ownerJvmRequire(argv.all { it.length <= MAX_ARGUMENT_BYTES / 2 && '\u0000' !in it } &&
            argv.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } <= MAX_ARGUMENT_BYTES,
            "owner_jvm_argument_bound", "arguments")
        fun occurrences(role: OwnerJvmFileRole, artifacts: List<ModelArtifact>) = immutable(artifacts.mapIndexed { ordinal, artifact ->
            OwnerJvmFileOccurrence(role, ordinal, artifact.path, artifact.coordinate, requireNotNull(artifact.digest), requireNotNull(artifact.size))
        })
        val runtimeFiles = occurrences(OwnerJvmFileRole.COMPILER_RUNTIME_CLASSPATH, runtime)
        val compileFiles = occurrences(OwnerJvmFileRole.COMPILE_CLASSPATH, classpath)
        val pluginFiles = occurrences(OwnerJvmFileRole.SCRIPTING_CLASSPATH, plugins)
        val source = OwnerJvmFileOccurrence(OwnerJvmFileRole.SOURCE, 0, bindings.sourceRoot.resolve(c.source.path).toString(), null, c.source.digest, c.source.size)
        val output = OwnerFixtureDerivedOutputTree.intended(bindings.ownerExporterOutput)
        fun scalar(index: Int, expected: String): OwnerJvmArgSlot {
            ownerJvmRequire(argv[index] == expected, "owner_jvm_scalar_changed", "scalar", index)
            return OwnerJvmArgSlot.Scalar(index, expected)
        }
        fun paths(index: Int, prefix: String, separator: String, files: List<OwnerJvmFileOccurrence>): OwnerJvmArgSlot {
            ownerJvmRequire(argv[index] == prefix + files.joinToString(separator) { it.path }, "owner_jvm_path_operand_changed", "path-list", index)
            return OwnerJvmArgSlot.PathList(index, argv[index], prefix, separator, files)
        }
        ownerJvmRequire(argv[4] == output.intendedPath, "owner_jvm_output_operand_changed", "output", 4)
        ownerJvmRequire(argv[12] == source.path, "owner_jvm_source_operand_changed", "source", 12)
        val slots = immutable(listOf(scalar(0, "-Xallow-no-source-files"), scalar(1, "-classpath"), paths(2, "", ":", compileFiles),
            scalar(3, "-d"), OwnerJvmArgSlot.OutputTree(4, argv[4], output), scalar(5, "-jvm-target"), scalar(6, c.jvmTarget),
            scalar(7, "-module-name"), scalar(8, c.moduleName), scalar(9, "-no-reflect"), scalar(10, "-no-stdlib"),
            paths(11, "-Xplugin=", ",", pluginFiles), OwnerJvmArgSlot.Source(12, argv[12], source)))
        val scripting = OwnerFixtureDefaultScripting2421.inspect(sourceContract, bindings.dependencyHome, chain.compilerPlugins, argv, semantics)
        val rawDigest = Digests.sha256("ktchecker/owner-jvm-intended-argv/1\u0000" + ContractJson.encodeToString(argv.toList()))
        val provisional = OwnerJvmPlanData(provenance, contract.digest, rawDigest, "", semantics, null, null,
            "Compiler defaults tied to the pinned compiler; no language/API arguments are synthesized.", slots,
            immutable(listOf(source) + compileFiles + runtimeFiles + pluginFiles), bindings.jdk.identity, output, scripting)
        verifyOwnerJvmPlanEvidenceBound(OwnerJvmPlanEvidence("classified_intended_operands", provisional))
        val identity = Digests.sha256("ktchecker/owner-jvm-intended-input-core/1\u0000" + ContractJson.encodeToString(provisional))
        val result = OwnerJvmPlanResult.Classified(provisional.copy(inputCoreIdentity = identity))
        ownerJvmRequire(renderIntended(result) == argv, "owner_jvm_argument_bijection_failed", "arguments")
        return result
    }

    fun renderIntended(plan: OwnerJvmPlanResult.Classified): List<String> = immutable(plan.data.slots.map { slot -> when (slot) {
        is OwnerJvmArgSlot.Scalar -> slot.original
        is OwnerJvmArgSlot.PathList -> slot.prefix + slot.files.joinToString(slot.separator) { it.path }
        is OwnerJvmArgSlot.Source -> slot.file.path
        is OwnerJvmArgSlot.OutputTree -> slot.output.intendedPath
    } })

    fun evidence(result: OwnerJvmPlanResult, contexts: List<OwnerFixtureContext>, issueKinds: List<String>): OwnerJvmPlanEvidence = when (result) {
        is OwnerJvmPlanResult.Classified -> OwnerJvmPlanEvidence("classified_intended_operands", result.data,
            importedContexts = immutable(contexts.map { it.copy(gaps = immutable(it.gaps)) }), importedIssueKinds = immutable(issueKinds))
        is OwnerJvmPlanResult.Rejected -> OwnerJvmPlanEvidence("rejected_intended_operands", rejection = result.problem,
            importedContexts = immutable(contexts.map { it.copy(gaps = immutable(it.gaps)) }), importedIssueKinds = immutable(issueKinds))
    }
}

/** Bound BEFORE export using only registry paths already enrolled by the existing owner fixture.
 * The later classified plan remains data, even though this binding object cannot be recreated from JSON. */
internal class OwnerFixturePreparedJvmInputs private constructor(private val contract: OwnerFixtureExecutableContract,
    private val sourceContract: OwnerFixtureContract, private val capture: OwnerFixtureCapture,
    private val registry: OwnerFixtureRuntimeRegistry, private val home: OwnerFixtureExecutionHome,
    private val startup: OwnerFixtureStartupCapture, private val jdk: OwnerJvmJdkInputs) {
    fun classifyAfterImport(model: VerifiedProjectModel, request: ModelImportRequest, ownerExporterOutput: Path): OwnerJvmPlanResult {
        capture.verify(); registry.verifyRuntimeInputs(); startup.verify()
        ownerJvmRequire(request.snapshot == capture.snapshot && request.materializedRoot == capture.root.toString() &&
            model.snapshot == capture.snapshot && request.configurationDigest == sourceContract.digest &&
            model.identity.gradleRuntimeDigest == ownerFixtureRuntimeIdentity(registry.runtimeDigest, startup.digest, home.digest),
            "owner_jvm_owner_binding_changed", "provenance")
        val provenance = OwnerJvmPlanProvenance(OwnerJvmComparisonOrigin.OWNER_REGISTRY_AFTER_TRUSTED_IMPORT,
            capture.snapshot.snapshotId, model.identity, request.exportDigest, request.executionId, sourceContract.digest,
            registry.runtimeDigest, startup.digest, home.digest)
        val result = OwnerFixtureJvmExecutableInputs.classify(contract, sourceContract, model.export, capture.snapshot,
            OwnerJvmClassifierBindings(capture.root, home.root, ownerExporterOutput, jdk), provenance)
        capture.verify(); registry.verifyRuntimeInputs(); startup.verify()
        return result
    }

    companion object {
        fun bind(contract: OwnerFixtureExecutableContract, sourceContract: OwnerFixtureContract, capture: OwnerFixtureCapture,
            registry: OwnerFixtureRuntimeRegistry, home: OwnerFixtureExecutionHome, startup: OwnerFixtureStartupCapture): OwnerFixturePreparedJvmInputs {
            ownerJvmRequire(registry.exporterPath == null, "owner_jvm_plan_binding_after_export", "preparation")
            ownerJvmRequire(contract.value.sourceContractDigest == sourceContract.digest, "owner_jvm_source_contract_mismatch", "contract")
            verifyOwnerJvmContractAtStart(contract, startup.evidence)
            val groups = listOf(contract.value.compilerRuntimeClasspath to 14, contract.value.compileClasspath to 2,
                sourceContract.value.compilerPluginClasspath.map { OwnerJvmFilePin(it.seedRelativePath, it.coordinate, it.digest, it.size) } to 7)
            groups.forEach { (pins, count) ->
                for (root in listOf(registry.gradleSeedHome, home.root)) {
                    ownerJvmPinnedArtifacts(pins, root, count, "registry-binding").forEach { expected ->
                        val registered = registry.artifact(Path.of(expected.path))
                        ownerJvmRequire(registered.path == expected.path && registered.digest == expected.digest && registered.size == expected.size,
                            "owner_jvm_artifact_registry_pin_mismatch", "registry-binding")
                        registry.verifyArtifact(expected)
                    }
                }
            }
            return OwnerFixturePreparedJvmInputs(contract, sourceContract, capture, registry, home, startup, registry.selectedJvmJdkInputs())
        }
    }
}

/** Data comparison only. The driver supplies its privately constructed START capture, not JSON. */
internal fun verifyOwnerJvmContractAtStart(contract: OwnerFixtureExecutableContract, evidence: OwnerFixtureStartupEvidence) {
    val start = evidence.inputs.singleOrNull { it.role == "authored-executable-input-contract" }
    ownerJvmRequire(evidence.executableContractCaptureFailure == null && start?.digest == contract.digest,
        "owner_jvm_contract_not_bound_at_start", "contract")
}

/** Bound worst-case escaping before allocating the serialized evidence. No terminal transcripts/errors are included. */
internal fun verifyOwnerJvmPlanEvidenceBound(evidence: OwnerJvmPlanEvidence) {
    ownerJvmRequire(!evidence.qualified && evidence.executionState == "unavailable" && evidence.schemaVersion == 1 &&
        evidence.evidenceKind == "intended_operand_classification", "owner_jvm_plan_claim_forbidden", "evidence")
    // The fixed margin also covers worst-case field names, enum names, numbers and
    // type discriminators for the bounded DTO record counts (not just today's plan).
    var budget = 64 * 1024L
    fun text(value: String?) { if (value != null) {
        budget += value.length * 6L + 4
        ownerJvmRequire(budget <= OWNER_JVM_PLAN_EVIDENCE_LIMIT, "owner_jvm_plan_evidence_bound", "evidence")
    } }
    ownerJvmRequire(evidence.importedContexts.size <= 4 && evidence.importedIssueKinds.size <= 256 && evidence.limitations.size <= 16,
        "owner_jvm_plan_evidence_bound", "evidence")
    text(evidence.state); text(evidence.evidenceKind); text(evidence.executionState)
    evidence.rejection?.let { text(it.code); text(it.role) }
    evidence.limitations.forEach(::text); evidence.importedIssueKinds.forEach(::text)
    evidence.importedContexts.forEach { context ->
        ownerJvmRequire(context.gaps.size <= 256, "owner_jvm_plan_evidence_bound", "evidence")
        text(context.compilationId); text(context.modelCompleteness); context.gaps.forEach(::text)
    }
    fun file(value: OwnerJvmFileOccurrence) { text(value.path); text(value.coordinate); text(value.digest) }
    fun output(value: OwnerJvmIntendedOutput) {
        text(value.intendedPath); text(value.specDigest); text(value.compilerAttemptId)
        with(value.spec) { text(compilationId); text(taskPath); text(purpose); text(ownerRootRole); text(relativePath) }
        ownerJvmRequire(value.compilerAttemptId == null && value.presence is OwnerJvmTreePresence.Unobserved,
            "owner_jvm_plan_output_observation_forbidden", "output")
        text((value.presence as OwnerJvmTreePresence.Unobserved).reason)
    }
    evidence.plan?.let { p ->
        ownerJvmRequire(p.slots.size == 13 && p.files.size == 24 && p.scripting.requiredDefaults.size <= 16 &&
            p.scripting.reviewedServiceExpectations.size <= 4 && p.scripting.missingFacts.size <= 32 && p.scripting.intendedPluginOptions.isEmpty(),
            "owner_jvm_plan_evidence_bound", "evidence")
        with(p.provenance) {
            text(snapshotId); text(rawExportDigest); text(exporterExecutionId); text(sourceContractDigest)
            text(runtimeInputDigest); text(startupDigest); text(executionHomeDigest)
            with(modelIdentity) { text(snapshotId); text(sourceDigest); text(modelDigest); text(dependencyDigest); text(toolchainDigest)
                text(exporterDigest); text(gradleRuntimeDigest); text(configurationDigest) }
        }
        text(p.executableContractDigest); text(p.rawArgumentsDigest); text(p.inputCoreIdentity)
        text(p.languageVersion); text(p.apiVersion); text(p.versionMeaning)
        p.files.forEach(::file); output(p.output)
        with(p.selectedJdk) { text(javaHome); text(runtimeInputDigest); text(treeDigest)
            with(javaExecutable) { text(coordinate); text(path); text(digest) } }
        p.slots.forEach { slot ->
            text(slot.original)
            when (slot) {
                is OwnerJvmArgSlot.PathList -> {
                    ownerJvmRequire(slot.files.size <= 7, "owner_jvm_plan_evidence_bound", "evidence")
                    text(slot.prefix); text(slot.separator); slot.files.forEach(::file)
                }
                is OwnerJvmArgSlot.Source -> file(slot.file)
                is OwnerJvmArgSlot.OutputTree -> output(slot.output)
                else -> Unit
            }
        }
        with(p.scripting) {
            text(adapterId); text(pluginId); text(state)
            requiredDefaults.forEach { text(it.key); text(it.requiredValue) }
            reviewedServiceExpectations.forEach { text(it.service); text(it.implementation); text(it.use) }
            missingFacts.forEach { text(it.explanation) }
        }
    }
}

internal fun ownerJvmPlanEvidenceBytes(evidence: OwnerJvmPlanEvidence): ByteArray {
    verifyOwnerJvmPlanEvidenceBound(evidence)
    val bytes = (ContractJson.encodeToString(evidence) + "\n").toByteArray(Charsets.UTF_8)
    ownerJvmRequire(bytes.size <= OWNER_JVM_PLAN_EVIDENCE_LIMIT, "owner_jvm_plan_evidence_bound", "evidence")
    return bytes
}

@Serializable internal data class OwnerJvmPlanPublication(val state: String, val path: String? = null, val digest: String? = null, val failureCode: String? = null)
internal fun publishOwnerJvmPlanEvidence(path: Path, evidence: OwnerJvmPlanEvidence): OwnerJvmPlanPublication {
    val bytes = ownerJvmPlanEvidenceBytes(evidence)
    Files.write(path, bytes, CREATE_NEW, WRITE, NOFOLLOW_LINKS)
    Files.setPosixFilePermissions(path, setOf(PosixFilePermission.OWNER_READ))
    val digest = Digests.sha256(bytes)
    ownerJvmRequire(hashFile(path, OWNER_JVM_PLAN_EVIDENCE_LIMIT.toLong(), bytes.size.toLong()) == digest,
        "owner_jvm_plan_evidence_changed", "evidence")
    return OwnerJvmPlanPublication(evidence.state, path.toString(), digest)
}

internal fun ownerJvmPlanFailure(error: Exception): OwnerJvmPlanRejection = when (error) {
    is OwnerJvmPlanProblem -> OwnerJvmPlanRejection(error.code, error.role, error.tokenIndex)
    is OwnerFixtureProblem -> OwnerJvmPlanRejection(error.code, "owner-inputs")
    is ProjectModelException -> OwnerJvmPlanRejection(error.code, "model-data")
    else -> OwnerJvmPlanRejection("owner_jvm_plan_unavailable", "classifier")
}

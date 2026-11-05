package dev.ktchecker.providers.detekt

import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.*
import dev.ktchecker.providers.runtime.*
import dev.ktchecker.snapshot.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Real importer and registered immutable overlay, SYNTHETIC model/export/artifact receipts.
 * This is not an actually executed Gradle model or compiler. The source/archive copies below
 * are unit fixtures, not a kernel receipt. Planning uses genuine pinned Detekt JARs if present,
 * but no unit test executes their bytes. Only the separately opted-in native test may do that.
 */
internal class DetektModelFixtures(positive: Boolean = true) : AutoCloseable {
    val work: Path = Files.createTempDirectory("ktc-detekt-model-fixture-").toRealPath()
    val namespace = ModelNamespace("detekt-unit-owner", "detekt-unit-workspace", "detekt-unit-worktree", "detekt-unit-lineage")
    val configuration = Digests.sha256("synthetic captured Detekt model policy")
    val exactRule = "ktc.kotlin.detekt.empty-blocks.empty-catch-block"
    val semanticRule = "ktc.kotlin.detekt.potential-bugs.missing-use-call"
    val aliasRule = "ktc.kotlin.detekt.empty-blocks.empty-class-block"
    val refinementRule = "ktc.resources.owned_autocloseable_exit"
    val mainId = ":fixture||main"
    val testId = ":fixture||test"
    val selected = listOf(exactRule, semanticRule, aliasRule, refinementRule)
    val identity = SnapshotIdentity(namespace.ownerId, namespace.workspaceId, namespace.worktreeId)
    private val store = SnapshotStore(work.resolve("store"), namespace.ownerId).also { it.registerWorktree(identity) }
    private val capture: MaterializedSnapshot
    val sourceRoot: Path
    val snapshot: SnapshotManifest
    val compiler: ModelArtifact
    val runtime: ModelArtifact
    val dependencies: List<ModelArtifact>
    private val registeredArtifacts = linkedMapOf<String, ModelArtifact>()
    var retained = false

    init {
        Files.newDirectoryStream(work.root).use { assumeTrue(it is SecureDirectoryStream<*>,
            "Real importer requires anchored no-follow reads; unsupported filesystems do not pass this control") }
        val catchBody = if (positive) "" else "throw caught"
        val files = sortedMapOf(
            "build.gradle.kts" to "// SYNTHETIC model-only fixture, never executed by Gradle\n",
            "settings.gradle.kts" to "rootProject.name = \"detekt-model-unit\"\n",
            "src/main/kotlin/A.kt" to "fun first() { try { error(\"x\") } catch (caught: IllegalStateException) { $catchBody } }\n",
            "src/main/kotlin/B.kt" to "fun second() = 2\n",
            "src/test/kotlin/Foreign.kt" to "fun foreign() { try { error(\"x\") } catch (caught: IllegalStateException) {} }\n",
        )
        val commit = store.sealOverlay(OverlayRequest(identity, "synthetic-client", "synthetic-session", "synthetic-overlay", null, 0,
            files.map { (path, value) -> SnapshotChange.PutFile(SnapshotPath(path = path), ImmutableBlob.utf8(value)) },
            scope = SnapshotScope("Whole synthetic source/configuration domain, not an actual Gradle observation")))
        capture = store.materialize(identity, commit.snapshotId, work.resolve("source"))
        sourceRoot = capture.root
        snapshot = capture.manifest.toContractManifest()
        compiler = artifact("model-artifacts/compiler.jar", "SYNTHETIC non-executable compiler bytes", "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21")
        runtime = artifact("model-artifacts/runtime.bin", "SYNTHETIC runtime bytes, never executed", "unit:runtime:1")
        dependencies = listOf(artifact("model-artifacts/one.jar", "SYNTHETIC dependency one", "unit:one:1"),
            artifact("model-artifacts/two.jar", "SYNTHETIC dependency two", "unit:two:1"))
    }

    private fun artifact(relative: String, content: String, coordinate: String): ModelArtifact {
        val path = work.resolve(relative)
        Files.createDirectories(path.parent); Files.writeString(path, content)
        return ModelArtifact(coordinate, path.toString(), Digests.sha256(content), Files.size(path)).also { registeredArtifacts[it.path] = it }
    }

    fun graph(): GradleProjectExport {
        fun sources(prefix: String) = snapshot.files.filter { it.path.startsWith(prefix) }.map { ModelFile(it.path, it.digest, it.size) }
        fun sourceSet(name: String) = ModelSourceSet(":fixture|$name", ":fixture", name, emptyList(), listOf("src/$name/kotlin"),
            emptyList(), sources("src/$name/kotlin/"), emptyList())
        val main = sourceSet("main")
        val test = sourceSet("test")
        fun compilation(name: String, set: ModelSourceSet) = ModelCompilation(":fixture||$name", ":fixture", "", "jvm", name,
            set.id, listOf(set.id), set.sources.reversed(), if (name == "main") ":fixture:compileKotlin" else ":fixture:compileTestKotlin",
            if (name == "main") "compileClasspath" else "testCompileClasspath", dependencies, emptyList(),
            ModelToolchain("jvm", "2.4.21", listOf(compiler), listOf(runtime)),
            // The raw vector is intentionally not Detekt argv. Synthetic authority says it was
            // modeled; these tests make NO claim of an observed KGP original-roles layout.
            listOf("-no-stdlib", "-no-reflect", "-language-version", "2.4", "-api-version", "2.4", "-jvm-target", "21",
                "-module-name", "synthetic_$name", "-classpath", dependencies.joinToString(File.pathSeparator) { it.path },
                "-d", work.resolve("unused-compiler-output/$name").toString()) + set.sources.map { sourceRoot.resolve(it.path).toString() },
            "2.4", "2.4")
        return GradleProjectExport(gradleVersion = "9.6.1",
            modules = listOf(ModelModule(":fixture", "", "build.gradle.kts", listOf("org.jetbrains.kotlin.jvm"))),
            sourceSets = listOf(main, test), compilations = listOf(compilation("main", main), compilation("test", test)),
            configurationInputs = snapshot.files.filter { it.path.endsWith(".gradle.kts") }.map { ModelFile(it.path, it.digest, it.size) },
            generatedInputs = emptyList(), buildscriptArtifacts = emptyList(), includedBuilds = emptyList(), issues = emptyList())
    }

    fun importModel(graph: GradleProjectExport = graph()): VerifiedProjectModel {
        val registered = registeredArtifacts.toMap()
        val bytes = ContractJson.encodeToString(graph).toByteArray()
        val request = ModelImportRequest(namespace, snapshot, sourceRoot.toString(), configuration, Digests.sha256(bytes),
            GradleModelExporter.scriptDigest(), UUID.randomUUID().toString())
        val permit = ModelIngressPermit(request, Digests.sha256("SYNTHETIC exporter runtime receipt"),
            verifySnapshot = { check(store.verifyMaterialization(capture).valid) },
            verifyExecutedExport = { /* Synthetic unit receipt, never reported as a Gradle invocation. */ },
            verifyArtifact = { actual -> check(registered[actual.path] == actual) },
            verifyModelInputs = { actual -> check(actual == graph) })
        return ProjectModelImporter(ExactModelIngressAuthority(permit)).import(request, bytes)
    }

    fun parent(model: VerifiedProjectModel = importModel(), compilationId: String = mainId): AnalysisInput {
        val base = requireNotNull(model.context(compilationId, configuration).context)
        val rules = listOf("ktc.compiler.common.unresolved_reference") + selected
        return AnalysisInput(snapshot, sourceRoot.toString(), base.copy(policies = base.policies + mapOf(
            "ktchecker.catalog-inventory" to Digests.sha256("synthetic whole catalog"),
            "ktchecker.activation-plan" to "synthetic captured activation identity; not a permit",
            "ktchecker.execution-plan" to Digests.sha256("{}"))), rules, changedPaths = listOf("src/main/kotlin/A.kt"))
    }

    fun installation(required: Boolean = false): DetektInstallation {
        val repository = Path.of(requireNotNull(System.getProperty("ktchecker.repositoryRoot"))).toAbsolutePath().normalize()
        val directory = repository.resolve(".plan-deps/detekt")
        val available = DetektCatalog.pins.all { Files.isRegularFile(directory.resolve(it.filename)) }
        if (required) check(available) { "Explicit native model test requires all five already installed pinned Detekt JARs" }
        else assumeTrue(available,
            "Planning-only positive model controls require the exact cached five pinned Detekt JARs; never download or fake them")
        val jdk = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize()
        return DetektInstallation(jdk.resolve("bin/java"), directory, jdk)
    }

    class Grants(installation: DetektInstallation?, settings: DetektSettings = DetektSettings()) {
        var permit: DetektModelPermit? = null
        var executionOverride: ExecutionPermit? = null
        val outcomes = mutableListOf<ProcessOutcome>()
        val provider = DetektProvider(installation,
            authority = ExecutionAuthority { input -> executionOverride ?: permit?.takeIf { it.accepts(input) }?.executionPermit },
            settings = settings, runner = IsolatedProcessRunner { outcomes += it },
            inputMode = DetektInputMode.VERIFIED_MODEL_LIGHT,
            modelAuthority = DetektModelAuthority { input -> permit?.takeIf { it.accepts(input) } })
    }

    data class Archive(val effective: AnalysisInput, val child: AnalysisInput, val verify: () -> Unit)

    fun archive(prepared: PreparedDetektModelInput, name: String = "attempt"): Archive {
        val original = prepared.original
        val root = work.resolve("archive-$name/sources")
        Files.createDirectories(root)
        original.snapshot.files.forEach { file ->
            val target = root.resolve(file.path)
            Files.createDirectories(target.parent); Files.copy(Path.of(original.materializedRoot).resolve(file.path), target)
        }
        val classpath = original.context.classpath.mapIndexed { index, artifact ->
            val target = root.parent.resolve("classpath/artifact-$index.jar")
            Files.createDirectories(target.parent); Files.copy(Path.of(artifact.path), target)
            artifact.copy(path = target.toString())
        }
        val effective = original.copy(materializedRoot = root.toString(), context = original.context.copy(classpath = classpath),
            outputRoot = work.resolve("outputs/$name").toString())
        val child = effective.copy(requiredRuleIds = prepared.detektRuleIds, execution = prepared.detektProfile)
        return Archive(effective, child) {
            val paths = Files.walk(root).use { stream -> stream.peek { check(!Files.isSymbolicLink(it)) }
                .filter { !Files.isDirectory(it) }.map { root.relativize(it).toString().replace('\\', '/') }.toList().toSet() }
            check(paths == snapshot.files.map { it.path }.toSet())
            snapshot.files.forEach { file ->
                val path = root.resolve(file.path)
                check(Files.size(path) == file.size && fileDigest(path) == file.digest)
            }
            classpath.forEach { artifact -> check(fileDigest(Path.of(artifact.path)) == artifact.digest) }
        }
    }

    fun removeOriginalSourcesAndDependencies() {
        writable(sourceRoot)
        check(sourceRoot.toFile().deleteRecursively())
        dependencies.forEach { Files.delete(Path.of(it.path)) }
    }

    private fun writable(root: Path) {
        if (Files.exists(root)) Files.walk(root).use { paths -> paths.forEach { it.toFile().setWritable(true, true) } }
    }

    override fun close() {
        if (!retained) { writable(work); work.toFile().deleteRecursively() }
    }
}

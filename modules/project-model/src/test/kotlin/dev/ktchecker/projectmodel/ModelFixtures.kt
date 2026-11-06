package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.*
import dev.ktchecker.snapshot.*
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.util.UUID
import kotlin.test.assertFailsWith

/** Unit-test owner capability: registered producer overlays, not untrusted report flags. */
internal class ModelFixtures {
    // Canonicalize only this freshly created trusted outer root (macOS may alias /var).
    // Never canonicalize untrusted artifact/source descendants to bypass no-follow reads.
    val work: Path = Files.createTempDirectory("ktchecker-model-fixture-").toRealPath()
    val namespace = ModelNamespace("model-owner", "model-workspace", "model-worktree", "model-lineage")
    val identity = SnapshotIdentity(namespace.ownerId, namespace.workspaceId, namespace.worktreeId)
    val store = SnapshotStore(work.resolve("cas"), identity.ownerId).also { it.registerWorktree(identity) }
    val configurationDigest = Digests.sha256("explicit-owner-configuration")
    val gradleRuntimeDigest = Digests.sha256("unit-owner-pinned-runtime")
    val artifact: ModelArtifact = work.resolve("artifacts/compiler.jar").let { path ->
        Files.createDirectories(path.parent); Files.writeString(path, "owner-selected immutable artifact")
        ModelArtifact("unit-owner:artifact:1", path.toString(), Digests.sha256(Files.readAllBytes(path)), Files.size(path))
    }
    private var previous: SnapshotCommit? = null
    private var previousFiles: Map<String, String> = emptyMap()

    data class Capture(val root: Path, val snapshot: SnapshotManifest, val verify: () -> Unit)

    fun capture(files: Map<String, String> = files(), scope: SnapshotScope = SnapshotScope()): Capture {
        val puts = files.entries.sortedBy { it.key }.map { (path, text) -> SnapshotChange.PutFile(SnapshotPath(path = path), ImmutableBlob.utf8(text)) }
        val deletes = (previousFiles.keys - files.keys).sorted().map { SnapshotChange.Delete(SnapshotPath(path = it)) }
        val head = previous
        val commit = store.sealOverlay(OverlayRequest(identity, "fixture-owner-client", "fixture-owner-session", UUID.randomUUID().toString(),
            head?.snapshotId, head?.headRevision ?: 0, deletes + puts, scope = scope))
        previous = commit; previousFiles = files.toMap()
        val materialized = store.materialize(identity, commit.snapshotId, work.resolve("snapshot-${commit.headRevision}"))
        return Capture(materialized.root, materialized.manifest.toContractManifest()) {
            check(store.verifyMaterialization(materialized).valid) { "Registered producer overlay changed" }
        }
    }

    fun graph(capture: Capture): GradleProjectExport {
        val byPath = capture.snapshot.files.associateBy { it.path }
        fun file(path: String) = byPath.getValue(path).let { ModelFile(it.path, it.digest, it.size) }
        fun sourceSet(module: String, name: String, parent: String? = null): ModelSourceSet {
            val prefix = module.removePrefix(":") + "/src/$name"
            return ModelSourceSet("$module|$name", module, name, parent?.let { listOf("$module|$it") }.orEmpty(),
                listOf("$prefix/kotlin"), listOf("$prefix/resources"),
                byPath.keys.filter { it.startsWith("$prefix/kotlin/") }.sorted().map(::file),
                byPath.keys.filter { it.startsWith("$prefix/resources/") }.sorted().map(::file))
        }
        val sets = listOf(sourceSet(":lib", "commonMain"), sourceSet(":lib", "sharedMain", "commonMain"),
            sourceSet(":lib", "jvmMain", "sharedMain"), sourceSet(":lib", "desktopMain", "sharedMain"),
            sourceSet(":lib", "jvmTest"), sourceSet(":feature", "jvmMain"), sourceSet(":app", "jvmMain"), sourceSet(":independent", "jvmMain"))
        fun compilation(module: String, target: String, name: String = "main", parents: List<String>, dependency: String? = null, associated: List<String> = emptyList()): ModelCompilation {
            val ids = parents.map { "$module|$it" }
            val producer = dependency?.let { "$it|jvm|main" }
            return ModelCompilation("$module|$target|$name", module, target, "jvm", name, ids.last(), ids,
                sets.filter { it.id in ids }.flatMap { it.sources }.distinctBy { it.path }.sortedBy { it.path },
                "$module:compile-$target-$name", "${target}${name}CompileClasspath", listOf(artifact),
                dependency?.let { listOf(ModelProjectDependency(it, "jvmApiElements", mapOf("platform" to "jvm"), producer)) }.orEmpty(),
                ModelToolchain("jvm", "2.4.21", listOf(artifact), listOf(artifact)), listOf("-language-version", "2.4", "-api-version", "2.4"),
                "2.4", "2.4", producedVariants = listOf(if (target == "desktop") "desktopApiElements" else "jvmApiElements"), associatedCompilationIds = associated)
        }
        return GradleProjectExport(gradleVersion = "9.6.1", modules = listOf(":lib", ":feature", ":app", ":independent").map {
            ModelModule(it, it.removePrefix(":"), it.removePrefix(":") + "/build.gradle.kts", listOf("org.jetbrains.kotlin.multiplatform"))
        }, sourceSets = sets, compilations = listOf(
            compilation(":lib", "jvm", parents = listOf("commonMain", "sharedMain", "jvmMain")),
            compilation(":lib", "desktop", parents = listOf("commonMain", "sharedMain", "desktopMain")),
            compilation(":lib", "jvm", "test", listOf("jvmTest"), associated = listOf(":lib|jvm|main")),
            compilation(":feature", "jvm", parents = listOf("jvmMain"), dependency = ":lib"),
            compilation(":app", "jvm", parents = listOf("jvmMain"), dependency = ":feature"),
            compilation(":independent", "jvm", parents = listOf("jvmMain"))),
            configurationInputs = byPath.keys.filter { it.endsWith(".gradle.kts") }.sorted().map(::file), generatedInputs = emptyList(),
            buildscriptArtifacts = listOf(artifact), includedBuilds = emptyList(), issues = emptyList())
    }

    fun request(capture: Capture, graph: GradleProjectExport = graph(capture), raw: ByteArray = ContractJson.encodeToString(graph).toByteArray(),
                configuration: String = configurationDigest): ModelImportRequest = ModelImportRequest(namespace, capture.snapshot, capture.root.toString(),
        configuration, Digests.sha256(raw), GradleModelExporter.scriptDigest(), UUID.randomUUID().toString())

    fun permit(capture: Capture, request: ModelImportRequest, runtime: String = gradleRuntimeDigest,
               verifyExecution: () -> Unit = {}, verifyInputs: (GradleProjectExport) -> Unit = {}): ModelIngressPermit = ModelIngressPermit(request, runtime,
        capture.verify, verifyExecution, { selected ->
            check(selected.path == artifact.path && selected.digest == artifact.digest && selected.size == artifact.size) { "Artifact is not in this fixture owner's immutable registry" }
        }, verifyInputs)

    fun import(capture: Capture, graph: GradleProjectExport = graph(capture), configuration: String = configurationDigest,
               runtime: String = gradleRuntimeDigest): VerifiedProjectModel {
        assumeOwnerFixtureAnchoredReads(work)
        val raw = ContractJson.encodeToString(graph).toByteArray()
        val request = request(capture, graph, raw, configuration)
        return ProjectModelImporter(ExactModelIngressAuthority(permit(capture, request, runtime))).import(request, raw)
    }

    fun files(): Map<String, String> = mapOf(
        "settings.gradle.kts" to "rootProject.name = \"fixture\"\n",
        "lib/build.gradle.kts" to "// owner-selected KMP fixture lib\n",
        "feature/build.gradle.kts" to "// owner-selected fixture feature\n",
        "app/build.gradle.kts" to "// owner-selected fixture app\n",
        "independent/build.gradle.kts" to "// owner-selected fixture independent\n",
        "lib/src/commonMain/kotlin/Common.kt" to "fun commonValue() = 1\n",
        "lib/src/commonMain/resources/message.txt" to "resource-one\n",
        "lib/src/sharedMain/kotlin/Shared.kt" to "fun sharedValue() = commonValue()\n",
        "lib/src/jvmMain/kotlin/Jvm.kt" to "fun jvmValue() = sharedValue()\n",
        "lib/src/desktopMain/kotlin/Desktop.kt" to "fun desktopValue() = sharedValue()\n",
        "lib/src/jvmTest/kotlin/Checks.kt" to "fun testValue() = jvmValue()\n",
        "feature/src/jvmMain/kotlin/Feature.kt" to "fun featureValue() = jvmValue()\n",
        "app/src/jvmMain/kotlin/App.kt" to "fun appValue() = featureValue()\n",
        "independent/src/jvmMain/kotlin/Independent.kt" to "fun independentValue() = 0\n",
    )
}

internal fun ownerFixtureAnchoredReadsSupported(path: Path): Boolean =
    Files.newDirectoryStream(path.root).use { it is SecureDirectoryStream<*> }

internal fun assumeOwnerFixtureAnchoredReads(path: Path) {
    assumeTrue(ownerFixtureAnchoredReadsSupported(path),
        "Anchored no-follow model reads unavailable on this filesystem; no successful model import coverage is claimed")
}

/**
 * Keep fixture applicability outside the exact domain-failure assertion. Otherwise a genuine
 * JUnit abort is caught as the wrong exception and becomes a false model-rejection failure.
 * The injected probe is only for inert assertion-boundary controls, not a capability substitute.
 */
internal fun assertOwnerFixtureModelFailure(
    path: Path,
    assumeAnchoredReads: (Path) -> Unit = ::assumeOwnerFixtureAnchoredReads,
    block: () -> Unit,
): ProjectModelException {
    assumeAnchoredReads(path)
    return assertFailsWith<ProjectModelException>(block = block)
}

internal fun createOwnerFixtureSymbolicLink(link: Path, target: Path) {
    try { Files.createSymbolicLink(link, target) }
    catch (_: UnsupportedOperationException) {
        assumeTrue(false, "Filesystem symbolic-link creation unavailable; no alias-control execution coverage is claimed")
    } catch (error: FileSystemException) {
        if (System.getProperty("os.name").startsWith("Windows") && error.reason?.contains("privilege", ignoreCase = true) == true) {
            assumeTrue(false, "Windows symbolic-link privilege unavailable; no alias-control execution coverage is claimed")
        } else throw error
    }
}

package dev.ktchecker.providers.detekt

import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.*
import dev.ktchecker.providers.runtime.*
import dev.ktchecker.snapshot.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Genuine importer and registered immutable source overlay; explicitly SYNTHETIC export,
 * artifact enrollment and archive receipts. Nothing here ran Gradle or a Kotlin compiler.
 * The current real exporter is still pinned to 2.4.21 and CANNOT supply this issue-free model.
 * Even the native test below exercises only the actual Detekt engine on this synthetic model.
 *
 * Ordinary unit controls never execute a process. Their tiny JDK is identity DATA ONLY.
 * Native callers must separately select a real whole JDK and preserve all scratch on failure.
 */
internal class DetektFullFixtures(
    sources: Map<String, String> = DetektFullSemanticCases.directSources,
    directory: Path? = null,
    var retained: Boolean = false,
    private val checkpoint: () -> Unit = {},
) : AutoCloseable {
    val work: Path = (directory ?: Files.createTempDirectory("ktc-detekt-full-unit-")).toRealPath()
    val namespace = ModelNamespace("detekt-full-fixture-owner", "detekt-full-fixture-workspace",
        "detekt-full-fixture-worktree", "detekt-full-fixture-lineage")
    val configuration = Digests.sha256("SYNTHETIC full model capture policy; not a Gradle observation")
    val mainId = ":fixture||main"
    val testId = ":fixture||test"
    val compileTask = ":fixture:compileKotlin"
    val destination: Path = work.resolve("unused-compiler-output/main")
    val wholeRuleIds: List<String> = catalogIds()
    val selected: List<String> = wholeRuleIds.filter { it in DetektCatalog.byId }
    val identity = SnapshotIdentity(namespace.ownerId, namespace.workspaceId, namespace.worktreeId)
    private val store = SnapshotStore(work.resolve("store"), namespace.ownerId).also { it.registerWorktree(identity) }
    private val capture: MaterializedSnapshot
    val sourceRoot: Path
    val snapshot: SnapshotManifest
    var originActive = true
    var originChecks = 0
    private val registeredArtifacts = linkedMapOf<String, ModelArtifact>()
    var compilerArtifacts: List<ModelArtifact>
        private set
    var runtimeArtifacts: List<ModelArtifact>
        private set
    var dependencies: List<ModelArtifact>
        private set

    init {
        checkpoint()
        Files.newDirectoryStream(work.root).use { assumeTrue(it is SecureDirectoryStream<*>,
            "Strict source importer requires anchored no-follow reads; this is not a passing fallback") }
        require(sources.isNotEmpty() && sources.keys.all { it.startsWith("src/main/kotlin/") && it.endsWith(".kt") })
        val files = (sources + mapOf(
            "build.gradle.kts" to "// SYNTHETIC 2.4.10 model fixture; never executed by Gradle\n",
            "settings.gradle.kts" to "rootProject.name = \"detekt-full-synthetic-fixture\"\n",
            "src/test/kotlin/Foreign.kt" to DetektFullSemanticCases.foreignSource,
        )).toSortedMap()
        val commit = store.sealOverlay(OverlayRequest(identity, "synthetic-client", "synthetic-session", "synthetic-overlay", null, 0,
            files.map { (path, value) -> SnapshotChange.PutFile(SnapshotPath(path = path), ImmutableBlob.utf8(value)) },
            scope = SnapshotScope("Whole synthetic source/configuration model, NOT actually exported by Gradle")))
        capture = store.materialize(identity, commit.snapshotId, work.resolve("source"))
        sourceRoot = capture.root
        snapshot = capture.manifest.toContractManifest()
        compilerArtifacts = listOf(artifact("model-artifacts/synthetic-compiler.jar", "SYNTHETIC non-executable compiler identity\n",
            DetektFullInstallation.COMPILER_COORDINATE))
        runtimeArtifacts = listOf(artifact("model-artifacts/synthetic-runtime.bin", "SYNTHETIC non-executable runtime identity\n", "fixture:runtime:1"))
        dependencies = listOf(artifact("model-artifacts/synthetic-stdlib.jar", "SYNTHETIC non-executable dependency identity\n",
            DetektFullInstallation.STDLIB_COORDINATE))
        checkpoint()
    }

    private fun artifact(relative: String, content: String, coordinate: String): ModelArtifact {
        val path = work.resolve(relative)
        Files.createDirectories(path.parent); Files.writeString(path, content)
        return enroll(ModelArtifact(coordinate, path.toString(), Digests.sha256(content), Files.size(path)))
    }

    private fun enroll(artifact: ModelArtifact): ModelArtifact = artifact.also { registeredArtifacts[it.path] = it }

    /** Upgrades identity DATA to actual pinned bytes; no compiler/JVM/provider is executed. */
    fun enrollInstallation(facts: DetektFullFixtureInstallation, extraDependency: Boolean = true) {
        checkpoint()
        compilerArtifacts = facts.compilerArtifacts.map(::enroll)
        runtimeArtifacts = facts.installation.runtimeArtifacts().map(::enroll)
        // A private source dependency copy allows drift controls without modifying cached bytes.
        val stdlib = work.resolve("model-artifacts/stdlib-2.4.10.jar")
        Files.copy(Path.of(facts.stdlib.path), stdlib)
        val actualStdlib = enroll(facts.stdlib.copy(path = stdlib.toString()))
        dependencies = if (extraDependency) {
            val empty = work.resolve("model-artifacts/empty-fixture.jar")
            ZipOutputStream(Files.newOutputStream(empty)).use { /* Empty ZIP data, never executed. */ }
            listOf(actualStdlib, enroll(ModelArtifact("fixture:empty-archive:1", empty.toString(), fileDigest(empty), Files.size(empty))))
        } else listOf(actualStdlib)
        checkpoint()
    }

    fun unitInstallation(): DetektFullInstallation {
        val facts = DetektFullFixtureInstallation.unit(work.resolve("identity-only-jdk"), checkpoint)
        enrollInstallation(facts)
        return facts.installation
    }

    fun graph(): GradleProjectExport {
        fun sources(prefix: String) = snapshot.files.filter { it.path.startsWith(prefix) }.map { ModelFile(it.path, it.digest, it.size) }
        fun sourceSet(name: String) = ModelSourceSet(":fixture|$name", ":fixture", name, emptyList(), listOf("src/$name/kotlin"),
            emptyList(), sources("src/$name/kotlin/"), emptyList())
        val main = sourceSet("main")
        val test = sourceSet("test")
        fun compilation(name: String, set: ModelSourceSet): ModelCompilation {
            val output = if (name == "main") destination else work.resolve("unused-compiler-output/test")
            return ModelCompilation(":fixture||$name", ":fixture", "", "jvm", name, set.id, listOf(set.id), set.sources.reversed(),
                if (name == "main") compileTask else ":fixture:compileTestKotlin",
                if (name == "main") "compileClasspath" else "testCompileClasspath", dependencies, emptyList(),
                ModelToolchain("jvm", "2.4.10", compilerArtifacts, runtimeArtifacts),
                listOf("-no-stdlib", "-no-reflect", "-language-version", "2.4", "-api-version", "2.4", "-jvm-target", "21",
                    "-module-name", "synthetic_$name", "-classpath", dependencies.joinToString(":") { it.path },
                    "-d", output.toString()) + set.sources.map { sourceRoot.resolve(it.path).toString() }, "2.4", "2.4")
        }
        return GradleProjectExport(gradleVersion = "9.6.1",
            modules = listOf(ModelModule(":fixture", "", "build.gradle.kts", listOf("org.jetbrains.kotlin.jvm"))),
            sourceSets = listOf(main, test), compilations = listOf(compilation("main", main), compilation("test", test)),
            configurationInputs = snapshot.files.filter { it.path.endsWith(".gradle.kts") }.map { ModelFile(it.path, it.digest, it.size) },
            generatedInputs = emptyList(), buildscriptArtifacts = emptyList(), includedBuilds = emptyList(), issues = emptyList())
    }

    fun importModel(graph: GradleProjectExport = graph()): VerifiedProjectModel {
        checkpoint()
        val registered = registeredArtifacts.toMap()
        val bytes = ContractJson.encodeToString(graph).toByteArray()
        val request = ModelImportRequest(namespace, snapshot, sourceRoot.toString(), configuration, Digests.sha256(bytes),
            GradleModelExporter.scriptDigest(), UUID.randomUUID().toString())
        val permit = ModelIngressPermit(request, Digests.sha256("SYNTHETIC exporter runtime receipt, NOT executed Gradle"),
            verifySnapshot = ::verifyOrigin,
            verifyExecutedExport = { checkpoint() /* Explicit synthetic receipt, never an actual export claim. */ },
            verifyArtifact = { actual -> checkpoint(); check(registered[actual.path] == actual) },
            verifyModelInputs = { actual -> checkpoint(); check(actual == graph) })
        return ProjectModelImporter(ExactModelIngressAuthority(permit)).import(request, bytes).also { checkpoint() }
    }

    fun parent(model: VerifiedProjectModel = importModel(), compilationId: String = mainId): AnalysisInput {
        val context = requireNotNull(model.context(compilationId, configuration).context)
        return AnalysisInput(snapshot, sourceRoot.toString(), context.copy(policies = context.policies + mapOf(
            "ktchecker.catalog-inventory" to Digests.sha256(wholeRuleIds.joinToString("\u0000")),
            "ktchecker.activation-plan" to "SYNTHETIC owner activation identity, never an execution capability",
            "ktchecker.execution-plan" to Digests.sha256("{}"))), wholeRuleIds,
            changedPaths = model.compilation(compilationId)!!.sourceFiles.take(1).map { it.path })
    }

    fun origin(model: VerifiedProjectModel, compilationId: String = mainId, task: String = compileTask,
               root: Path = sourceRoot, output: Path = destination): DetektFullModelOrigin =
        DetektFullModelOrigin(model, compilationId, task, root, output, ::verifyOrigin)

    fun verifyOrigin() {
        checkpoint(); originChecks++
        if (!originActive) throw ExecutionProblem("fixture_full_origin_revoked", "Synthetic owner origin was revoked")
        check(store.verifyMaterialization(capture).valid) { "Registered original synthetic source overlay changed" }
        checkpoint()
    }

    class Grants(installation: DetektFullInstallation?, settings: DetektFullSettings = DetektFullSettings()) {
        var permit: DetektFullModelPermit? = null
        var executionOverride: ExecutionPermit? = null
        val outcomes = mutableListOf<ProcessOutcome>()
        val provider = DetektFullModelProvider(installation,
            authority = ExecutionAuthority { input -> executionOverride ?: permit?.takeIf { it.accepts(input) }?.executionPermit },
            settings = settings, runner = IsolatedProcessRunner { outcomes += it },
            modelAuthority = DetektFullModelAuthority { input -> permit?.takeIf { it.accepts(input) } })
    }

    data class Archive(val effective: AnalysisInput, val child: AnalysisInput, val verify: () -> Unit)

    /** Exact structural copy for binding controls, explicitly NOT an actual kernel custody receipt. */
    fun archive(prepared: PreparedDetektFullModelInput, name: String = "attempt"): Archive {
        checkpoint()
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
            checkpoint()
            val paths = Files.walk(root).use { stream -> stream.peek { check(!Files.isSymbolicLink(it)) }
                .filter { !Files.isDirectory(it, NOFOLLOW_LINKS) }
                .map { root.relativize(it).toString().replace('\\', '/') }.toList().toSet() }
            check(paths == snapshot.files.map { it.path }.toSet())
            snapshot.files.forEach { file ->
                val path = root.resolve(file.path)
                check(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) &&
                    Files.size(path) == file.size && fileDigest(path) == file.digest)
            }
            classpath.zip(original.context.classpath).forEachIndexed { index, (actual, before) ->
                val path = Path.of(actual.path)
                check(actual.coordinate == before.coordinate && actual.digest == before.digest &&
                    Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) &&
                    Files.size(path) == dependencies[index].size && fileDigest(path) == actual.digest)
            }
            checkpoint()
        }
    }

    override fun close() {
        if (!retained) {
            Files.walk(work).use { paths -> paths.forEach { it.toFile().setWritable(true, true) } }
            check(work.toFile().deleteRecursively())
        }
    }

    companion object {
        const val OWNER_CATALOG_CONTRACT = "ktchecker/owner-full-catalog/1665-u2/1"
        const val OWNER_RULE_IDS_DIGEST = "sha256:121660fc2a5aed084688d0c549f9e1bece2ec514e9ace4748211b55fa87cc1c5"
        fun repository(): Path = Path.of(requireNotNull(System.getProperty("ktchecker.repositoryRoot"))).toAbsolutePath().normalize()
        private fun catalogIds(): List<String> {
            val ids = listOf("rules-core.json", "rules-ecosystem.json", "rules-libraries.json", "rules-platform.json").flatMap { name ->
                Json.parseToJsonElement(Files.readString(repository().resolve("data/$name"))).jsonArray
                    .map { it.jsonObject.getValue("id").jsonPrimitive.content }
            }.sorted()
            check(ids.size == 1665 && ids.distinct().size == 1665) { "This fixture preserves ALL 1,665 actual catalog obligations, not an invented one-rule release scope" }
            check(Digests.sha256(Digests.canonical(JsonArray(ids.map(::JsonPrimitive)))) == OWNER_RULE_IDS_DIGEST) {
                "The reviewed1665 vector cannot be replaced by equal-count or duplicate IDs"
            }
            return ids
        }
    }
}

internal class DetektFullFixtureInstallation private constructor(
    val installation: DetektFullInstallation,
    val compilerArtifacts: List<ModelArtifact>,
    val stdlib: ModelArtifact,
) {
    companion object {
        private const val COMPILER_FILE = "kotlin-compiler-embeddable-2.4.10.jar"
        private const val STDLIB_FILE = "kotlin-stdlib-2.4.10.jar"

        fun unit(identityOnlyHome: Path, checkpoint: () -> Unit): DetektFullFixtureInstallation {
            val paths = artifactPaths(required = false)
            val java = syntheticJdk(identityOnlyHome)
            return capture(java.parent.parent, paths.first, paths.second, checkpoint)
        }

        /** Only the dedicated explicitly consented native task calls this; no java.home fallback. */
        fun native(checkpoint: () -> Unit): DetektFullFixtureInstallation {
            val paths = artifactPaths(required = true)
            val selected = fullPath(requireNotNull(System.getProperty("ktchecker.detekt.full.jdkHome")) {
                "Explicit owner-selected real link-free JDK21 home is mandatory"
            })
            return capture(selected, paths.first, paths.second, checkpoint)
        }

        private fun capture(jdk: Path, compiler: Path, stdlib: Path, checkpoint: () -> Unit): DetektFullFixtureInstallation {
            checkpoint()
            val compilerIdentity = ModelArtifact(DetektFullInstallation.COMPILER_COORDINATE, compiler.toString(),
                DetektFullInstallation.COMPILER_SHA256, DetektFullInstallation.COMPILER_BYTES)
            val stdlibIdentity = ModelArtifact(DetektFullInstallation.STDLIB_COORDINATE, stdlib.toString(),
                DetektFullInstallation.STDLIB_SHA256, DetektFullInstallation.STDLIB_BYTES)
            // Synthetic MODELED ordered vector only: genuine bytes, NOT an executed or complete
            // official compiler distribution. The actual engine is the separate pinned CLI JAR.
            val compilerVector = listOf(compilerIdentity, stdlibIdentity)
            val detekt = DetektInstallation(jdk.resolve("bin/java"), DetektFullFixtures.repository().resolve(".plan-deps/detekt"), jdk)
            val installation = DetektFullInstallation.capture(detekt,
                compilerVector.map { ArtifactIdentity(it.coordinate, requireNotNull(it.digest), it.path) }, checkpoint)
            verifyFullArtifact(DetektFullPinnedArtifact(ArtifactIdentity(stdlibIdentity.coordinate,
                requireNotNull(stdlibIdentity.digest), stdlibIdentity.path), requireNotNull(stdlibIdentity.size)), checkpoint)
            return DetektFullFixtureInstallation(installation, compilerVector, stdlibIdentity)
        }

        private fun artifactPaths(required: Boolean): Pair<Path, Path> {
            fun selected(property: String, module: String, filename: String): Path? {
                val explicit = System.getProperty(property)
                if (explicit != null) return fullPath(explicit)
                if (required) error("Native FULL fixture requires explicit $property; no implicit cache selection")
                val base = Path.of(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin", module, "2.4.10")
                if (!Files.isDirectory(base)) return null
                return Files.walk(base, 2).use { stream -> stream.filter { it.fileName.toString() == filename && Files.isRegularFile(it, NOFOLLOW_LINKS) }
                    .toList().singleOrNull() }
            }
            val compiler = selected("ktchecker.detekt.full.compiler", "kotlin-compiler-embeddable", COMPILER_FILE)
            val stdlib = selected("ktchecker.detekt.full.stdlib", "kotlin-stdlib", STDLIB_FILE)
            val directory = DetektFullFixtures.repository().resolve(".plan-deps/detekt")
            val present = compiler != null && stdlib != null && Files.isRegularFile(compiler, NOFOLLOW_LINKS) &&
                Files.isRegularFile(stdlib, NOFOLLOW_LINKS) && DetektCatalog.pins.all { Files.isRegularFile(directory.resolve(it.filename), NOFOLLOW_LINKS) }
            if (required) check(present) { "Native FULL test requires the existing exact pinned artifacts; never download or fabricate them" }
            else assumeTrue(present, "Planning-only binding positives require cached genuine pinned bytes; missing bytes SKIP, never pass or download")
            return requireNotNull(compiler) to requireNotNull(stdlib)
        }

        /** Tiny layout is DATA for the reused whole-image helper, never a mock executable engine. */
        private fun syntheticJdk(home: Path): Path {
            val files = mapOf(
                "release" to "JAVA_VERSION=\"21.0.9\"\nOS_NAME=\"Linux\"\nOS_ARCH=\"x86_64\"\n",
                "bin/java" to "NON-EXECUTED synthetic launcher identity, not a script or JVM\n",
                "lib/modules" to "NON-EXECUTED synthetic modules identity\n",
                "lib/jvm.cfg" to "NON-EXECUTED synthetic VM configuration\n",
                "conf/security/java.security" to "NON-EXECUTED synthetic security configuration\n",
                "lib/server/libjvm.so" to "NON-EXECUTED synthetic VM library identity\n",
            )
            files.forEach { (relative, content) ->
                val path = home.resolve(relative)
                Files.createDirectories(path.parent); Files.writeString(path, content)
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(if (relative == "bin/java") "rwx------" else "rw-------"))
            }
            Files.createDirectory(home.resolve("identity-only-empty-directory"))
            return home.toRealPath().resolve("bin/java")
        }
    }
}

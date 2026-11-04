package dev.ktchecker.compiler

import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.*
import dev.ktchecker.snapshot.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Real registered overlay/importer objects, SYNTHETIC export/domain receipts and JDK/JAR bytes.
 * The archive observer below is a unit-test byte-copy simulation, not kernel execution evidence.
 * The synthetic launcher and compiler JARs must never execute.
 */
internal class KotlinJvmModelBindingFixtures : AutoCloseable {
    val work: Path = Files.createTempDirectory("ktc-model-binding-unit-").toRealPath()
    val namespace = ModelNamespace("bridge-owner", "bridge-workspace", "bridge-worktree", "bridge-lineage")
    val configuration = Digests.sha256("unit captured owner policy")
    val rule = "ktc.compiler.common.unresolved_reference"
    val compilationId = ":fixture|customJvm|main"
    val snapshotIdentity = SnapshotIdentity(namespace.ownerId, namespace.workspaceId, namespace.worktreeId)
    val store: SnapshotStore
    val sourceRoot: Path
    val snapshot: SnapshotManifest
    val java: Path
    val compilerArtifacts: List<ModelArtifact>
    val dependencies: List<ModelArtifact>
    val installation: KotlinJvmToolchain
    private val registeredArtifacts = linkedMapOf<String, ModelArtifact>()
    private val capture: MaterializedSnapshot

    init {
        Files.newDirectoryStream(work.root).use { assumeTrue(it is SecureDirectoryStream<*>,
            "Anchored model reads are unavailable; no successful model import is claimed on this filesystem") }
        store = SnapshotStore(work.resolve("cas"), namespace.ownerId).also { it.registerWorktree(snapshotIdentity) }
        val files = mapOf("build.gradle.kts" to "// SYNTHETIC unit model; no Gradle process\n",
            "settings.gradle.kts" to "rootProject.name = \"binding-unit\"\n",
            "src/main/kotlin/A.kt" to "fun a() = 1\n", "src/main/kotlin/B.kt" to "fun b() = a()\n",
            "src/main/resources/state.txt" to "whole registered resource\n")
        val commit = store.sealOverlay(OverlayRequest(snapshotIdentity, "fixture-client", "fixture-session", "fixture-patch",
            null, 0, files.entries.sortedBy { it.key }.map { (path, text) ->
                SnapshotChange.PutFile(SnapshotPath(path = path), ImmutableBlob.utf8(text))
            }, scope = SnapshotScope("Complete synthetic fixture input domain")))
        capture = store.materialize(snapshotIdentity, commit.snapshotId, work.resolve("snapshot"))
        sourceRoot = capture.root
        snapshot = capture.manifest.toContractManifest()
        java = SyntheticJdk.create(work.resolve("jdk"))
        Files.createDirectory(work.resolve("jdk/empty-runtime-directory"))
        compilerArtifacts = listOf(
            artifact("compiler/kotlin-compiler-embeddable-2.4.21.jar", "not an executable compiler", "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21"),
            artifact("compiler/support.jar", "not executable compiler support", "unit:compiler-support:1"))
        dependencies = listOf(artifact("dependencies/first.jar", "same dependency bytes", "unit:duplicate-dependency:1"),
            artifact("dependencies/second.jar", "same dependency bytes", "unit:duplicate-dependency:1"))
        installation = KotlinJvmToolchain(java, compilerArtifacts.map {
            ArtifactIdentity("installed:${Path.of(it.path).fileName}", requireNotNull(it.digest), it.path)
        })
        runtimeArtifacts().forEach { registeredArtifacts[it.path] = it }
    }

    fun artifact(relative: String, bytes: String, coordinate: String): ModelArtifact {
        val path = work.resolve(relative)
        Files.createDirectories(path.parent); Files.writeString(path, bytes)
        return ModelArtifact(coordinate, path.toString(), Digests.sha256(Files.readAllBytes(path)), Files.size(path))
            .also { registeredArtifacts[it.path] = it }
    }

    private fun runtimeArtifacts(): List<ModelArtifact> = installation.runtimeImage.view().entries
        .filter { it.kind == JvmRuntimeEntryKind.FILE }.map {
            ModelArtifact("compilation-jdk:${it.relativePath}", installation.runtimeImage.root.resolve(it.relativePath).toString(), it.digest, it.size)
        }

    fun graph(): GradleProjectExport {
        val sources = snapshot.files.filter { it.path.endsWith(".kt") }.map { ModelFile(it.path, it.digest, it.size) }
        val resources = snapshot.files.filter { it.path.endsWith("state.txt") }.map { ModelFile(it.path, it.digest, it.size) }
        val sourceSet = ModelSourceSet(":fixture|main", ":fixture", "main", emptyList(), listOf("src/main/kotlin"),
            listOf("src/main/resources"), sources, resources)
        return GradleProjectExport(gradleVersion = "9.6.1", modules = listOf(
            ModelModule(":fixture", "", "build.gradle.kts", listOf("org.jetbrains.kotlin.jvm"))), sourceSets = listOf(sourceSet),
            compilations = listOf(ModelCompilation(compilationId, ":fixture", "customJvm", "jvm", "main", sourceSet.id,
                listOf(sourceSet.id), sources.reversed(), ":fixture:compileKotlin", "compileClasspath", dependencies, emptyList(),
                ModelToolchain("jvm", "2.4.21", compilerArtifacts, runtimeArtifacts()),
                listOf("-language-version", "2.4", "-api-version", "2.4", "-jvm-target", "21", "-module-name", "fixture"), "2.4", "2.4")),
            configurationInputs = snapshot.files.filter { it.path.endsWith(".gradle.kts") }.map { ModelFile(it.path, it.digest, it.size) },
            generatedInputs = emptyList(), buildscriptArtifacts = emptyList(), includedBuilds = emptyList(), issues = emptyList())
    }

    fun importModel(graph: GradleProjectExport = graph()): VerifiedProjectModel {
        // The test's frozen artifact registry predates ingress; reports cannot enroll other bytes.
        val registered = registeredArtifacts.toMap()
        val raw = ContractJson.encodeToString(graph).toByteArray()
        val request = ModelImportRequest(namespace, snapshot, sourceRoot.toString(), configuration, Digests.sha256(raw),
            GradleModelExporter.scriptDigest(), UUID.randomUUID().toString())
        val permit = ModelIngressPermit(request, Digests.sha256("SYNTHETIC unit exporter runtime receipt"),
            verifySnapshot = { check(store.verifyMaterialization(capture).valid) },
            verifyExecutedExport = { /* Synthetic unit authority; no successful Gradle invocation is claimed. */ },
            verifyArtifact = { value ->
                val expected = registered.getValue(value.path)
                check(value.digest == expected.digest && value.size == expected.size)
            }, verifyModelInputs = { actual -> check(actual == graph) })
        return ProjectModelImporter(ExactModelIngressAuthority(permit)).import(request, raw)
    }

    data class Preparation(val binding: KotlinJvmModelBinding, val input: AnalysisInput,
        val profiles: Map<String, TrustedExecution>, val prepared: PreparedJvmModelInput)

    fun prepare(provider: KotlinJvmCompilerProvider, model: VerifiedProjectModel = importModel()): Preparation {
        val binding = KotlinJvmModelBinding.verifyAndBind(model, compilationId, installation)
        val context = requireNotNull(model.context(compilationId, configuration).context)
        val rules = listOf(rule, "catalog.required-but-not-implemented")
        val activation = buildJsonObject {
            put("policyDigest", configuration); put("schemaVersion", 1)
            putJsonArray("decisions") { rules.forEach { id -> add(buildJsonObject {
                put("ruleId", id); put("active", true); put("origin", "unit-owner-policy"); put("reason", "enabled")
            }) } }
        }
        val input = AnalysisInput(snapshot, sourceRoot.toString(), context.copy(policies = context.policies + mapOf(
            "ktchecker.catalog-inventory" to Digests.sha256("complete unit catalog"),
            "ktchecker.activation-plan" to Digests.canonical(activation))), rules, changedPaths = listOf("src/main/resources/state.txt"))
        val profile = provider.executionProfile(input.copy(requiredRuleIds = listOf(rule)), binding)
        val profiles = mapOf(provider.id to profile)
        val finalInput = input.copy(context = input.context.copy(policies = input.context.policies +
            ("ktchecker.execution-plan" to Digests.sha256(Digests.canonical(ContractJson.encodeToJsonElement(profiles))))))
        return Preparation(binding, finalInput, profiles, binding.bindPreparedInput(finalInput, listOf(rule), provider, profiles))
    }

    class Archive(val effective: AnalysisInput, val child: AnalysisInput, private val checker: () -> Unit) {
        var verifications = 0
            private set
        fun verify() { verifications++; checker() }
    }

    fun archive(prepared: PreparedJvmModelInput, name: String = "attempt"): Archive {
        val original = prepared.original
        val root = work.resolve("private-archive-$name/sources")
        Files.createDirectories(root)
        original.snapshot.files.forEach { file ->
            val destination = root.resolve(file.path)
            Files.createDirectories(destination.parent)
            Files.copy(Path.of(original.materializedRoot).resolve(file.path), destination)
        }
        val classpath = original.context.classpath.mapIndexed { index, artifact ->
            val destination = root.parent.resolve("classpath/artifact-$index.jar")
            Files.createDirectories(destination.parent); Files.copy(Path.of(artifact.path), destination)
            artifact.copy(path = destination.toString())
        }
        val output = Files.createDirectories(work.resolve("outputs/$name"))
        val effective = freezeJvmInput(original.copy(materializedRoot = root.toString(),
            context = original.context.copy(classpath = classpath), outputRoot = output.toString()))
        val child = effective.copy(requiredRuleIds = prepared.compilerRuleIds, execution = prepared.compilerProfile)
        return Archive(effective, child) {
            val actual = Files.walk(root).use { paths -> paths.peek { check(!Files.isSymbolicLink(it)) }
                .filter { !Files.isDirectory(it) }.map { root.relativize(it).toString().replace('\\', '/') }.toList().toSet() }
            check(actual == effective.snapshot.files.map { it.path }.toSet())
            effective.snapshot.files.forEach { file ->
                val path = root.resolve(file.path)
                check(!Files.isSymbolicLink(path) && Files.size(path) == file.size && Digests.sha256(Files.readAllBytes(path)) == file.digest)
            }
            effective.context.classpath.forEach { artifact ->
                check(!Files.isSymbolicLink(Path.of(artifact.path)) && Digests.sha256(Files.readAllBytes(Path.of(artifact.path))) == artifact.digest)
            }
        }
    }

    fun removeOriginalInputs() {
        makeWritable(sourceRoot)
        check(sourceRoot.toFile().deleteRecursively())
        dependencies.forEach { Files.delete(Path.of(it.path)) }
    }

    private fun makeWritable(root: Path) {
        if (Files.exists(root)) Files.walk(root).use { paths -> paths.forEach { it.toFile().setWritable(true, true) } }
    }

    override fun close() { makeWritable(work); work.toFile().deleteRecursively() }
}

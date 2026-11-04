package dev.ktchecker.cli

import dev.ktchecker.compiler.*
import dev.ktchecker.config.*
import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.*
import dev.ktchecker.projectmodel.*
import dev.ktchecker.snapshot.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Real registered overlays, materialized files, configuration store and importer capabilities.
 * Export/domain receipts and compiler/JDK bytes are explicitly SYNTHETIC UNIT inputs. No Gradle,
 * compiler or fake launcher may execute; these tests are not platform/rule qualification.
 */
internal class OwnerModelFixtures : AutoCloseable {
    val work: Path = Files.createTempDirectory("ktc-owner-model-unit-").toRealPath()
    val state: PrivateState
    val namespace: ModelNamespace
    val snapshotIdentity: SnapshotIdentity
    val snapshots: SnapshotStore
    val catalog = Catalog()
    val aliases: RuleAliases
    val configs: ConfigStore
    val installation: KotlinJvmToolchain
    val compilerArtifacts: List<ModelArtifact>
    val dependencies: List<ModelArtifact>
    val rule = "ktc.compiler.common.unresolved_reference"
    val warningRule = "ktc.kotlin.detekt.style.magic-number"
    private val artifactRegistry = linkedMapOf<String, ModelArtifact>()
    private var previous: SnapshotCommit? = null
    private var previousFiles: Map<String, String> = emptyMap()

    init {
        Files.newDirectoryStream(work.root).use { assumeTrue(it is SecureDirectoryStream<*>,
            "Owner-model anchored ingress is unavailable; no successful Windows/import qualification is claimed") }
        state = PrivateState(work.resolve("state"))
        namespace = ModelNamespace(state.ownerId, "workspace", "worktree", "lineage")
        snapshotIdentity = SnapshotIdentity(namespace.ownerId, namespace.workspaceId, namespace.worktreeId)
        snapshots = SnapshotStore(state.namespace("snapshots"), state.ownerId).also { it.registerWorktree(snapshotIdentity) }
        aliases = RuleAliases(catalog.ids.map { PolicyRule(it) })
        configs = ConfigStore(state.namespace("configuration"), aliases)
        val java = syntheticJava(work.resolve("jdk"))
        // Intentionally not alphabetical: neither a directory listing nor sorting is authority.
        compilerArtifacts = listOf(
            artifact("compiler/z/kotlin-compiler-embeddable-2.4.21.jar", "NON-EXECUTABLE compiler input", "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21"),
            artifact("compiler/a/support.jar", "NON-EXECUTABLE compiler support", "unit:compiler-support:1"))
        dependencies = listOf(
            artifact("dependencies/z.jar", "identical dependency bytes", "unit:dependency:1"),
            artifact("dependencies/a.jar", "identical dependency bytes", "unit:dependency:1"))
        installation = KotlinJvmToolchain(java, compilerArtifacts.map {
            ArtifactIdentity("owner-installed:${Path.of(it.path).fileName}", requireNotNull(it.digest), it.path)
        })
        runtimeArtifacts().forEach { artifactRegistry[it.path] = it }
    }

    fun binding(client: String = "client") = ClientBinding(namespace.ownerId, namespace.workspaceId,
        namespace.worktreeId, client, namespace.lineageId, sessionId = "session-$client")
    fun request(name: String = "one") = SubmitPlanRequest("patch-$name", "request-$name", "plan-$name")

    fun setEnabled(enabled: Boolean = true, ruleId: String = rule) = mutate(ConfigAction.SetRuleEnabled(ruleId, enabled))

    fun mutate(action: ConfigAction): ConfigSnapshot {
        val binding = modelConfigBinding(binding())
        val principal = ClientIdentity(state.ownerId, "explicit-unit-owner", "unit-owner-session")
        val authority = HmacOwnerAuthority(ByteArray(32) { it.toByte() }, listOf(OwnerSessionRegistration(principal, setOf(binding))))
        val store = ConfigStore(state.namespace("configuration"), aliases, authority)
        val request = ConfigMutationRequest(binding, ConfigLayer.SHARED, configs.capture(binding).revisionToken, action, UUID.randomUUID().toString())
        return store.mutate(request, authority.issueForExplicitOwnerAction(principal, request))
    }

    data class Composition(val plans: OwnerModelPlans, val attempts: ModelAttemptGrants, val compiler: KotlinJvmCompilerProvider?)

    fun composition(selected: KotlinJvmToolchain? = installation, providerInstallation: KotlinJvmToolchain? = selected,
                    maximumInputs: Int = 4096, maximumAttempts: Int = 16): Composition {
        val attempts = ModelAttemptGrants(maximumAttempts)
        val provider = providerInstallation?.let { KotlinJvmCompilerProvider(it, attempts.executionAuthority,
            inputMode = KotlinJvmInputMode.VERIFIED_MODEL, modelAuthority = attempts.modelAuthority) }
        return Composition(OwnerModelPlans(state, snapshots, configs, catalog, selected, provider, attempts, {},
            maxInputs = maximumInputs), attempts, provider)
    }

    fun capture(files: Map<String, String> = files()): MaterializedSnapshot {
        val changes = (previousFiles.keys - files.keys).sorted().map { SnapshotChange.Delete(SnapshotPath(path = it)) } +
            files.entries.sortedBy { it.key }.map { (path, content) -> SnapshotChange.PutFile(SnapshotPath(path = path), ImmutableBlob.utf8(content)) }
        val commit = snapshots.sealOverlay(OverlayRequest(snapshotIdentity, "unit-owner", "unit-owner-session", UUID.randomUUID().toString(),
            previous?.snapshotId, previous?.headRevision ?: 0, changes))
        previous = commit
        previousFiles = files.toMap()
        return snapshots.materialize(snapshotIdentity, commit.snapshotId, work.resolve("capture-${commit.headRevision}"))
    }

    fun graph(capture: MaterializedSnapshot, modules: List<String> = listOf(":lib", ":app")): GradleProjectExport {
        val files = capture.manifest.toContractManifest().files
        fun modeled(file: SnapshotFile) = ModelFile(file.path, file.digest, file.size)
        val sets = modules.map { module ->
            val source = "${module.removePrefix(":")}/src/main"
            ModelSourceSet("$module|main", module, "main", emptyList(), listOf("$source/kotlin"), listOf("$source/resources"),
                files.filter { it.path.startsWith("$source/kotlin/") }.map(::modeled),
                files.filter { it.path.startsWith("$source/resources/") }.map(::modeled))
        }
        return GradleProjectExport(gradleVersion = "9.6.1", modules = modules.map {
            ModelModule(it, it.removePrefix(":"), "${it.removePrefix(":")}/build.gradle.kts", listOf("org.jetbrains.kotlin.jvm"))
        }, sourceSets = sets, compilations = sets.map { set ->
            ModelCompilation("${set.moduleId}|ownerJvm|main", set.moduleId, "ownerJvm", "jvm", "main", set.id, listOf(set.id),
                set.sources.reversed(), "${set.moduleId}:compileKotlin", "compileClasspath", dependencies,
                if (set.moduleId == ":app" && ":lib" in modules) listOf(ModelProjectDependency(":lib", "jvmApiElements",
                    mapOf("platform" to "jvm"), ":lib|ownerJvm|main")) else emptyList(),
                ModelToolchain("jvm", "2.4.21", compilerArtifacts, runtimeArtifacts()),
                listOf("-language-version", "2.4", "-api-version", "2.4", "-jvm-target", "21", "-module-name", set.moduleId.removePrefix(":")),
                "2.4", "2.4", producedVariants = listOf("jvmApiElements"))
        }, configurationInputs = files.filter { it.path.endsWith(".gradle.kts") }.map(::modeled),
            generatedInputs = emptyList(), buildscriptArtifacts = emptyList(), includedBuilds = emptyList(), issues = emptyList())
    }

    fun import(capture: MaterializedSnapshot, intent: OwnerModelIntent, graph: GradleProjectExport = graph(capture)): VerifiedProjectModel {
        val registered = artifactRegistry.toMap()
        val bytes = ContractJson.encodeToString(graph).toByteArray()
        val expected = ContractJson.decodeFromString<GradleProjectExport>(bytes.toString(Charsets.UTF_8))
        val request = ModelImportRequest(namespace, capture.manifest.toContractManifest(), capture.root.toString(),
            intent.configuration.policyDigest, Digests.sha256(bytes), GradleModelExporter.scriptDigest(), UUID.randomUUID().toString())
        val permit = ModelIngressPermit(request, Digests.sha256("SYNTHETIC unit exporter runtime receipt; never executed"),
            verifySnapshot = { check(snapshots.verifyMaterialization(capture).valid) },
            verifyExecutedExport = { check(request.exportDigest == Digests.sha256(bytes)) },
            verifyArtifact = { declared -> check(registered[declared.path] == declared) },
            verifyModelInputs = { observed -> check(observed == expected) })
        return ProjectModelImporter(ExactModelIngressAuthority(permit)).import(request, bytes)
    }

    /** Equal-byte alternate installation with new paths/order is NOT the original model identity. */
    fun alternativeInstallation(name: String = "alternative", reverseOrder: Boolean = false): KotlinJvmToolchain {
        val java = syntheticJava(work.resolve("$name/jdk"))
        val artifacts = compilerArtifacts.map { original ->
            val path = work.resolve("$name/compiler/${Path.of(original.path).fileName}")
            Files.createDirectories(path.parent); Files.copy(Path.of(original.path), path)
            ArtifactIdentity(original.coordinate, requireNotNull(original.digest), path.toString())
        }.let { if (reverseOrder) it.reversed() else it }
        return KotlinJvmToolchain(java, artifacts)
    }

    private fun artifact(relative: String, contents: String, coordinate: String): ModelArtifact {
        val path = work.resolve(relative)
        Files.createDirectories(path.parent); Files.writeString(path, contents)
        return ModelArtifact(coordinate, path.toString(), Digests.sha256(Files.readAllBytes(path)), Files.size(path))
            .also { artifactRegistry[it.path] = it }
    }

    private fun runtimeArtifacts(): List<ModelArtifact> = installation.runtimeImage.view().entries
        .filter { it.kind == JvmRuntimeEntryKind.FILE }.map {
            ModelArtifact("compilation-jdk:${it.relativePath}", installation.runtimeImage.root.resolve(it.relativePath).toString(), it.digest, it.size)
        }

    class Archive(val effective: AnalysisInput, private val checker: () -> Unit) {
        var verifications = 0
            private set
        fun verify() { verifications++; checker() }
    }

    /** Test-only exact byte archive projection, not evidence of a kernel worker or compiler run. */
    fun archive(original: AnalysisInput, name: String = UUID.randomUUID().toString()): Archive {
        val root = work.resolve("archives/$name/sources")
        Files.createDirectories(root)
        original.snapshot.files.forEach { file ->
            val destination = root.resolve(file.path)
            Files.createDirectories(destination.parent); Files.copy(Path.of(original.materializedRoot).resolve(file.path), destination)
        }
        val classpath = original.context.classpath.mapIndexed { index, artifact ->
            val destination = root.parent.resolve("classpath/artifact-$index.jar")
            Files.createDirectories(destination.parent); Files.copy(Path.of(artifact.path), destination)
            artifact.copy(path = destination.toString())
        }
        val effective = original.copy(materializedRoot = root.toString(), context = original.context.copy(classpath = classpath),
            outputRoot = Files.createDirectories(work.resolve("outputs/$name")).toString())
        return Archive(effective) {
            val actual = Files.walk(root).use { paths -> paths.peek { check(!Files.isSymbolicLink(it)) }
                .filter { !Files.isDirectory(it) }.map { root.relativize(it).toString().replace('\\', '/') }.toList().toSet() }
            check(actual == effective.snapshot.files.map { it.path }.toSet())
            effective.snapshot.files.forEach { file ->
                val path = root.resolve(file.path)
                check(Files.size(path) == file.size && Digests.sha256(Files.readAllBytes(path)) == file.digest)
            }
            effective.context.classpath.forEach { artifact ->
                check(!Files.isSymbolicLink(Path.of(artifact.path)) && Digests.sha256(Files.readAllBytes(Path.of(artifact.path))) == artifact.digest)
            }
        }
    }

    fun removeOriginalInputs(capture: MaterializedSnapshot) {
        writable(capture.root)
        check(capture.root.toFile().deleteRecursively())
        dependencies.forEach { Files.delete(Path.of(it.path)) }
    }

    fun files(): Map<String, String> = mapOf(
        "settings.gradle.kts" to "rootProject.name = \"synthetic-owner-model\"\n",
        "lib/build.gradle.kts" to "// synthetic unit model; no Gradle execution\n",
        "app/build.gradle.kts" to "// synthetic unit model; no Gradle execution\n",
        "lib/src/main/kotlin/Api.kt" to "fun apiValue() = 1\n",
        "lib/src/main/kotlin/Helper.kt" to "fun helperValue() = 2\n",
        "lib/src/main/resources/value.txt" to "whole snapshot resource\n",
        "app/src/main/kotlin/App.kt" to "fun appValue() = apiValue()\n",
    )

    private fun writable(root: Path) {
        if (Files.exists(root)) Files.walk(root).use { paths -> paths.forEach { it.toFile().setWritable(true, true) } }
    }
    override fun close() { writable(work); work.toFile().deleteRecursively() }
}

/** Tiny identity fixture only. The non-executable text launcher must never be passed to analyze. */
private fun syntheticJava(home: Path): Path {
    val os = when {
        System.getProperty("os.name").startsWith("Windows") -> "Windows"
        System.getProperty("os.name").lowercase().let { "mac" in it || "darwin" in it } -> "Darwin"
        else -> "Linux"
    }
    val javaName = if (os == "Windows") "java.exe" else "java"
    val vm = when (os) { "Windows" -> "bin/server/jvm.dll"; "Darwin" -> "lib/server/libjvm.dylib"; else -> "lib/server/libjvm.so" }
    val files = mapOf("release" to "JAVA_VERSION=\"21.0.9\"\nOS_NAME=\"$os\"\nOS_ARCH=\"x86_64\"\n",
        "bin/$javaName" to "NON-EXECUTED SYNTHETIC UNIT LAUNCHER\n", "lib/modules" to "synthetic modules\n",
        "lib/jvm.cfg" to "synthetic VM selection\n", "conf/security/java.security" to "synthetic security\n", vm to "synthetic VM\n")
    files.forEach { (name, contents) ->
        val path = home.resolve(name)
        Files.createDirectories(path.parent); Files.writeString(path, contents)
        if (home.fileSystem.supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(if (name == "bin/$javaName") "rwx------" else "rw-------"))
        }
    }
    return home.toRealPath().resolve("bin/$javaName")
}

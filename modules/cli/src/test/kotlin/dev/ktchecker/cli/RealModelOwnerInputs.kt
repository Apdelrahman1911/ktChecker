package dev.ktchecker.cli

import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Derived from pinned owner fixture data helpers; independent opt-in no-test experiment only. */
@Serializable internal data class RealModelRuntimeFile(val kind: String, val relativePath: String, val digest: String, val size: Long, val executable: Boolean)
@Serializable internal data class RealModelRuntimeDirectory(val kind: String, val relativePath: String)
@Serializable internal data class RealModelRuntimeOmission(val kind: String, val path: String, val reason: String)
@Serializable internal data class RealModelRuntimeManifest(val schemaVersion: Int, val gradleArchive: String, val gradleHome: String, val javaHome: String,
    val gradleSeedHome: String, val files: List<RealModelRuntimeFile>, val directories: List<RealModelRuntimeDirectory>,
    val helperArtifacts: List<ModelArtifact>, val omittedInputEntries: List<RealModelRuntimeOmission>)

/** Registry is frozen BEFORE execution; the report cannot enroll new artifact paths/bytes. */
internal class RealModelOwnerInputs private constructor(private val manifest: RealModelRuntimeManifest) {
    val gradleHome: Path = Path.of(manifest.gradleHome)
    val javaHome: Path = Path.of(manifest.javaHome)
    val gradleSeedHome: Path = Path.of(manifest.gradleSeedHome)
    private val roots = mapOf("gradle" to gradleHome, "jdk" to javaHome, "seed" to gradleSeedHome)
    private val artifacts = manifest.files.associate { file ->
        val path = roots.getValue(file.kind).resolve(file.relativePath)
        path.toString() to ModelArtifact("real-model-input:${file.kind}:${file.relativePath}", path.toString(), file.digest, file.size)
    }.toMutableMap().apply { manifest.helperArtifacts.forEach { put(it.path, it) } }
    val jdkPaths: List<String> = manifest.files.filter { it.kind == "jdk" }.map { javaHome.resolve(it.relativePath).toString() }.sorted()
    val runtimeDigest: String = Digests.sha256("ktchecker/real-model-inputs/1\u0000" + ContractJson.encodeToString(manifest))
    private val exporters = linkedMapOf<String, ModelArtifact>()
    private var executionHome: RealModelExecutionHome? = null
    val ownerManifestDigest: String = "sha256:" + RealModelRuntimeRoster.OWNER_MANIFEST_SHA256

    fun deriveExecutionHome(work: Path): RealModelExecutionHome {
        realExpect(executionHome == null, "real_model_execution_home_already_registered")
        verifyRuntimeInputs()
        val home = RealModelExecutionHome.derive(gradleSeedHome, manifest.files, manifest.directories, runtimeDigest,
            work.resolve("execution-home"), work.resolve("execution-home-evidence.json"))
        home.dependencyArtifacts.forEach { artifact ->
            realExpect(artifact.path !in artifacts, "real_model_execution_home_artifact_collision")
            artifacts[artifact.path] = artifact
        }
        executionHome = home
        verifyRuntimeInputs()
        return home
    }

    /** SINGLE preselected installation, issued before export; never populated from a report. */
    fun compilerClasspath(): List<ArtifactIdentity> {
        val home = executionHome ?: throw RealModelProblem("real_model_execution_home_unbound")
        return RealModelRuntimeRoster.compilerArtifacts.mapIndexed { index, pin ->
            realExpect(pin.ordinal == index, "real_model_compiler_order_changed")
            listOf(gradleSeedHome.resolve(pin.relativePath), home.root.resolve(pin.relativePath)).forEach { path ->
                val artifact = artifact(path)
                realExpect(artifact.digest == pin.digest && artifact.size == pin.bytes,
                    "real_model_compiler_selection_changed")
                verifyArtifact(artifact)
            }
            realExpect(home.containsDependency(home.root.resolve(pin.relativePath).toString()), "real_model_compiler_not_pre_enrolled")
            ArtifactIdentity(pin.coordinate, pin.digest, home.root.resolve(pin.relativePath).toString())
        }
    }

    fun selectedDependency(ordinal: Int): ModelArtifact {
        val home = executionHome ?: throw RealModelProblem("real_model_execution_home_unbound")
        val pin = RealModelRuntimeRoster.compilerArtifacts[ordinal]
        val actual = artifact(home.root.resolve(pin.relativePath))
        realExpect(actual.digest == pin.digest && actual.size == pin.bytes, "real_model_dependency_selection_changed")
        return actual.copy(coordinate = pin.coordinate)
    }

    /** Exact pinned producer/lowering bytes must be present in the genuine executed buildscript closure. */
    fun verifyJvmProducerArtifacts(graph: GradleProjectExport) {
        val home = executionHome ?: throw RealModelProblem("real_model_execution_home_unbound")
        val pins = listOf(
            "caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-gradle-plugin/2.4.21/d2b2862980453fbeb168a03dddd488d6191924df/kotlin-gradle-plugin-2.4.21-gradle96.jar" to
                "sha256:d0a11912936c8801f1773a47e8fc51835a6ab568d73e4e1d188281955a7f575b",
            "caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-gradle-plugin-api/2.4.21/8789c5d15e46b4e76829f7e6eafaed0857874b3b/kotlin-gradle-plugin-api-2.4.21-gradle96.jar" to
                "sha256:43b8c73f3e4f123b1a5e4bc8e62bd9b258dfa84129e6564bebb03c5fada84d23")
        pins.forEach { (relative, digest) ->
            val selected = artifact(home.root.resolve(relative))
            realExpect(home.containsDependency(selected.path) && selected.digest == digest &&
                graph.buildscriptArtifacts.any { it.path == selected.path && it.digest == selected.digest && it.size == selected.size },
                "real_model_exact_jvm_producer_not_observed")
            verifyArtifact(selected)
            val original = artifact(gradleSeedHome.resolve(relative))
            realExpect(original.digest == digest && original.size == selected.size, "real_model_jvm_producer_pin_changed")
            verifyArtifact(original)
        }
    }

    fun artifact(path: Path): ModelArtifact = artifacts[path.toString()] ?: throw RealModelProblem("real_model_artifact_unregistered")
    fun verifyArtifact(actual: ModelArtifact) {
        val registered = artifacts[actual.path] ?: throw RealModelProblem("real_model_artifact_unregistered")
        realExpect(actual.digest == registered.digest && actual.size == registered.size &&
            realHashFile(Path.of(actual.path), 512L * 1024 * 1024, registered.size) == registered.digest, "real_model_artifact_registry_mismatch")
        executionHome?.let { home -> if (home.containsDependency(actual.path)) home.verifyDependencyArtifact(actual) }
    }
    fun verifyRuntimeInputs() {
        verifyRealModelPrivateRuntimeRoots(roots.values)
        verifyRealModelRuntimeInventories(roots, manifest.files, manifest.directories)
        verifyRealModelRuntimeFileBytes(roots, manifest.files)
        manifest.helperArtifacts.forEach(::verifyArtifact)
        exporters.values.forEach(::verifyArtifact)
        executionHome?.verifyInputs()
    }
    fun registerExporter(output: Path, phase: Int) {
        realExpect(phase in 1..2 && exporters.size == phase - 1, "real_model_exporter_phase_replayed")
        val path = output.resolve(GradleModelExporter.SCRIPT)
        realExpect(path.toString() !in artifacts && realHashFile(path, 1024 * 1024L) == GradleModelExporter.scriptDigest(),
            "real_model_exporter_pin_changed")
        val artifact = ModelArtifact("real-model-pinned-exporter", path.toString(), GradleModelExporter.scriptDigest(), Files.size(path))
        exporters[path.toString()] = artifact
        artifacts[path.toString()] = artifact
    }

    fun exporterArtifact(output: Path): ModelArtifact = exporters[output.resolve(GradleModelExporter.SCRIPT).toString()]
        ?: throw RealModelProblem("real_model_exporter_unregistered")

    companion object {
        private const val GRADLE_ARCHIVE_DIGEST = "sha256:9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14"
        fun load(path: Path): RealModelOwnerInputs {
            val bytes = realRead(path, RealModelRuntimeRoster.OWNER_MANIFEST_BYTES, RealModelRuntimeRoster.OWNER_MANIFEST_BYTES.toLong())
            realExpect(Digests.sha256(bytes) == "sha256:" + RealModelRuntimeRoster.OWNER_MANIFEST_SHA256, "real_model_owner_manifest_pin_changed")
            realExpect(bytes.size in 1..8 * 1024 * 1024, "real_model_runtime_manifest_bound")
            val manifest = ContractJson.decodeFromString<RealModelRuntimeManifest>(strictJsonDocument(bytes, "real_model_manifest_json"))
            realExpect(manifest.schemaVersion == 1 && manifest.files.size in 1..20_000 &&
                manifest.files.size + manifest.directories.size <= 20_000 &&
                manifest.files.sumOf { it.size } in 1..1024L * 1024 * 1024, "real_model_runtime_manifest_bound")
            val roots = mapOf("gradle" to Path.of(manifest.gradleHome), "jdk" to Path.of(manifest.javaHome), "seed" to Path.of(manifest.gradleSeedHome))
            realExpect(roots.values.all { it.isAbsolute && it.normalize() == it && !Files.isSymbolicLink(it) } &&
                roots.values.toList().let { values -> values.indices.all { a -> values.indices.all { b -> a == b || !values[a].startsWith(values[b]) } } },
                "real_model_runtime_roots_invalid")
            verifyRealModelPrivateRuntimeRoots(roots.values)
            realExpect(manifest.files.map { it.kind to it.relativePath }.distinct().size == manifest.files.size, "real_model_runtime_manifest_duplicate")
            val fileNames = manifest.files.map { it.kind to it.relativePath }.toSet()
            realExpect(manifest.directories.map { it.kind to it.relativePath }.distinct().size == manifest.directories.size &&
                manifest.directories.none { (it.kind to it.relativePath) in fileNames },
                "real_model_runtime_manifest_duplicate")
            manifest.directories.forEach { directory ->
                realExpect(directory.kind in roots, "real_model_runtime_manifest_invalid")
                realLogical(directory.relativePath)
                if (directory.kind == "seed") realExpect(directory.relativePath.substringBefore('/') in setOf("caches"), "real_model_external_seed_configuration_forbidden")
            }
            manifest.files.forEach { file ->
                realExpect(file.kind in roots && file.size in 0..512L * 1024 * 1024, "real_model_runtime_manifest_invalid")
                realLogical(file.relativePath); realDigest(file.digest)
                if (file.kind == "seed") realExpect(file.relativePath.substringBefore('/') in setOf("caches"), "real_model_external_seed_configuration_forbidden")
            }
            roots.forEach { (kind, root) -> realExpect(realRegularFiles(root).map { root.relativize(it).toString().replace('\\', '/') }.sorted() ==
                manifest.files.filter { it.kind == kind }.map { it.relativePath }.sorted(), "real_model_runtime_inventory_changed") }
            val helperPaths = listOf("/bin/sh", "/usr/bin/uname", "/usr/bin/xargs", "/usr/bin/sed", "/usr/bin/tr").map { Path.of(it).toRealPath().toString() }
            realExpect(manifest.helperArtifacts.map { it.path }.sorted() == helperPaths.sorted(), "real_model_helper_domain_changed")
            manifest.helperArtifacts.forEach { realExpect(it.digest != null && it.size != null, "real_model_helper_identity_missing") }
            manifest.omittedInputEntries.forEach { omission ->
                realLogical(omission.path)
                realExpect(omission.kind == "seed" && omission.path.substringBefore('/') in setOf("caches") &&
                    (omission.path.endsWith(".lock") || omission.path.endsWith(".lck")), "real_model_input_omission_unqualified")
            }
            val registry = RealModelOwnerInputs(manifest)
            verifyRealModelRuntimeInventories(roots, manifest.files, manifest.directories)
            verifyOfficialGradle(registry, manifest)
            manifest.files.forEach { registry.verifyArtifact(registry.artifact(roots.getValue(it.kind).resolve(it.relativePath))) }
            manifest.helperArtifacts.forEach(registry::verifyArtifact)
            realExpect(Files.isExecutable(registry.gradleHome.resolve("bin/gradle")) && Files.isExecutable(registry.javaHome.resolve("bin/java")) &&
                manifest.files.any { it.kind == "gradle" && it.relativePath == "lib/gradle-gradle-cli-main-9.6.1.jar" } &&
                realUtf8(realRead(registry.javaHome.resolve("release"), 1024 * 1024))
                    .contains("JAVA_VERSION=\"21"), "real_model_selected_runtime_unavailable")
            return registry
        }

        /** An archive SHA-256 assertion in JSON is not proof: hash the independently pinned ZIP
         * and every actual extracted runtime byte before allowing the launcher to execute. */
        private fun verifyOfficialGradle(registry: RealModelOwnerInputs, manifest: RealModelRuntimeManifest) {
            val archive = Path.of(manifest.gradleArchive)
            realExpect(realHashFile(archive, 512L * 1024 * 1024) == GRADLE_ARCHIVE_DIGEST, "real_model_vendor_gradle_archive_mismatch")
            val entries = mutableMapOf<String, Pair<String, Long>>()
            val directories = mutableSetOf<String>()
            var total = 0L
            ZipFile(archive.toFile()).use { zip -> zip.entries().asSequence().forEach { entry ->
                realExpect(entry.name.startsWith("gradle-9.6.1/"), "real_model_vendor_gradle_archive_invalid")
                if (entry.isDirectory) {
                    val directory = entry.name.removePrefix("gradle-9.6.1/").trimEnd('/')
                    if (directory.isNotEmpty()) { realLogical(directory); directories += directory }
                    return@forEach
                }
                val relative = entry.name.removePrefix("gradle-9.6.1/")
                realLogical(relative)
                relative.indices.filter { relative[it] == '/' }.forEach { directories += relative.substring(0, it) }
                val sha = MessageDigest.getInstance("SHA-256")
                var count = 0L
                zip.getInputStream(entry).use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val amount = stream.read(buffer)
                        if (amount < 0) break
                        count += amount; total += amount
                        realExpect(count <= 512L * 1024 * 1024 && total <= 1024L * 1024 * 1024, "real_model_vendor_gradle_archive_bound")
                        sha.update(buffer, 0, amount)
                    }
                }
                realExpect(entries.put(relative, ("sha256:" + sha.digest().joinToString("") { "%02x".format(it) }) to count) == null,
                    "real_model_vendor_gradle_archive_duplicate")
            } }
            realExpect(manifest.files.filter { it.kind == "gradle" }.associate { it.relativePath to (it.digest to it.size) } == entries,
                "real_model_extracted_gradle_distribution_mismatch")
            realExpect(manifest.directories.filter { it.kind == "gradle" }.map { it.relativePath }.toSet() == directories,
                "real_model_extracted_gradle_distribution_mismatch")
            realExpect(realHashFile(archive, 512L * 1024 * 1024) == GRADLE_ARCHIVE_DIGEST, "real_model_vendor_gradle_archive_changed")
            // Registry verifies the extracted runtime against these independently derived bytes.
            manifest.files.filter { it.kind == "gradle" }.forEach { registry.verifyArtifact(registry.artifact(registry.gradleHome.resolve(it.relativePath))) }
        }
    }
}


private fun verifyRealModelPrivateRuntimeRoots(roots: Collection<Path>) {
    val publicPermissions = PosixFilePermission.values().filter { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") }.toSet()
    realExpect(roots.all { root -> Files.getPosixFilePermissions(root, NOFOLLOW_LINKS).none { it in publicPermissions } },
        "real_model_runtime_root_not_private")
}

/** Pure freshness validation, not an execution/artifact/import grant. Every runtime/seed
 * directory and non-lock file is an immutable registered input, including executable DSL
 * cache classes. Only regular seed lock files may change; new directories still fail. */
internal fun verifyRealModelRuntimeInventories(roots: Map<String, Path>, files: List<RealModelRuntimeFile>, directories: List<RealModelRuntimeDirectory>) {
    fun tree(root: Path): List<Pair<String, Boolean>> {
        realExpect(Files.isDirectory(root, NOFOLLOW_LINKS) && !Files.isSymbolicLink(root), "real_model_input_root_unsafe")
        var entries = 0
        return Files.walk(root).use { paths -> paths.filter { it != root }.map { path ->
            realExpect(++entries <= 40_000, "real_model_runtime_tree_bound")
            realExpect(!Files.isSymbolicLink(path), "real_model_input_alias_unqualified")
            val relative = root.relativize(path).toString().replace('\\', '/')
            realLogical(relative)
            val directory = Files.isDirectory(path, NOFOLLOW_LINKS)
            realExpect(directory || Files.isRegularFile(path, NOFOLLOW_LINKS), "real_model_input_nonregular")
            relative to directory
        }.sorted(compareBy<Pair<String, Boolean>> { it.first }.thenBy { it.second }).toList() }
    }
    fun expected(kind: String): List<Pair<String, Boolean>> {
        val members = files.filter { it.kind == kind }.map { it.relativePath }
        return (members.map { it to false } + directories.filter { it.kind == kind }.map { it.relativePath to true })
            .sortedWith(compareBy<Pair<String, Boolean>> { it.first }.thenBy { it.second })
    }
    listOf("gradle", "jdk").forEach { kind ->
        realExpect(tree(roots.getValue(kind)) == expected(kind), "real_model_runtime_inventory_changed")
    }
    val seed = tree(roots.getValue("seed"))
    realExpect(seed.all { it.first.substringBefore('/') in setOf("caches") }, "real_model_external_seed_configuration_forbidden")
    realExpect(seed.filterNot { !it.second && seedLockFile(it.first) } ==
        expected("seed").filterNot { !it.second && seedLockFile(it.first) }, "real_model_seed_inventory_changed")
}

private fun seedLockFile(path: String): Boolean = path.endsWith(".lock") || path.endsWith(".lck")

/** Pure byte/permission validation only. There is no runtime or model authority constructor
 * here, and no post-execution enrollment of newly generated cache or artifact bytes. */
internal fun verifyRealModelRuntimeFileBytes(roots: Map<String, Path>, files: List<RealModelRuntimeFile>) {
    files.filterNot { it.kind == "seed" && seedLockFile(it.relativePath) }.forEach { file ->
        val path = roots.getValue(file.kind).resolve(file.relativePath)
        realExpect(realHashFile(path, 512L * 1024 * 1024, file.size) == file.digest, "real_model_artifact_registry_mismatch")
        realExpect(Files.isExecutable(path) == file.executable, "real_model_runtime_permissions_changed")
    }
}

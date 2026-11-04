package dev.ktchecker.compiler

import dev.ktchecker.contracts.AnalysisInput
import dev.ktchecker.contracts.Digests
import dev.ktchecker.providers.runtime.IsolatedProcessRunner
import dev.ktchecker.providers.runtime.fileDigest
import dev.ktchecker.providers.runtime.strictUtf8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.jar.JarFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Startup copies bind authored inputs and executed artifacts separately; live sources are not bytecode proof. */
internal class CompilerFixtureProvenance private constructor(
    private val repository: Path,
    private val runRoot: Path,
    private val capturedAt: String,
    private val sources: List<Capture>,
    private val artifacts: List<Artifact>,
    private val anchors: List<Map<String, Any?>>,
    val compilerRegistryText: String,
    private val registryResource: Capture,
    private val registryResourceUrl: String,
) {
    private data class Capture(val original: Path, val preserved: Path, val digest: String, val size: Long)
    private data class Artifact(val location: Path, val kind: String, val roles: List<String>, val files: List<Capture>, val digest: String)

    val manifestPath: Path get() = runRoot.resolve("startup-provenance.json")

    fun description(): Map<String, Any?> = mapOf(
        "schema_version" to 1,
        "captured_at" to capturedAt,
        "capture_timing" to "startup_before_loading_fixture_inputs_or_executing_compiler_jobs",
        "source_to_bytecode_claim" to "none; startup source identities and actual class-directory/jar identities are recorded independently",
        "sources" to sources.map { mapOf("original_path" to relative(it.original), "size" to it.size,
            "preserved" to reference(it.preserved)) },
        "executed_artifacts" to artifacts.map { artifact -> mapOf(
            "code_source" to artifact.location.toString(), "kind" to artifact.kind, "roles" to artifact.roles,
            "manifest_sha256" to artifact.digest,
            "files" to artifact.files.map { file -> mapOf("entry" to artifactEntry(artifact.location, file.original),
                "size" to file.size, "preserved" to reference(file.preserved)) },
        ) },
        "class_resource_anchors" to anchors,
        "executed_registry_resource" to mapOf("resource_url" to registryResourceUrl,
            "preserved" to reference(registryResource.preserved), "size" to registryResource.size),
        "bounds" to mapOf("maximum_files_per_inventory" to MAX_FILES, "maximum_source_file_bytes" to MAX_SOURCE_BYTES,
            "maximum_artifact_file_bytes" to MAX_ARTIFACT_BYTES, "maximum_total_inventory_bytes" to MAX_TOTAL_BYTES),
        "limitations" to listOf("This is cooperative trusted fixture execution, not adversarial classloader or same-UID sandbox attestation.",
            "Only configured outer directories are canonicalized once for operating-system ancestor aliases; symlinked outer leaves and evidence descendants remain rejected.",
            "Captured source identities alone do not prove which sources produced bytecode; actual class artifacts are separately preserved.",
            "Executed class locations/resources are checked before every compiler job and at run end; source drift is checked at run end.",
            "Synthetic compiler-provider results establish neither independent qualification nor current-code results after later edits."),
    )

    fun implementationReferences(): List<Map<String, String>> = sources.filter {
        it.original.startsWith(repository.resolve("modules/provider-compiler/src/main/kotlin")) &&
            it.original.toString().endsWith(".kt")
    }.map { reference(it.preserved) } + listOf(reference(manifestPath))

    fun readFixture(relative: String): String {
        val original = repository.resolve("providers/compiler-fixtures").resolve(CompilerSnapshot.relative(relative)).normalize()
        val captured = sources.singleOrNull { it.original == original } ?: error("Fixture input was not captured at startup: $relative")
        val bytes = readStable(captured.preserved, MAX_SOURCE_BYTES)
        check(Digests.sha256(bytes).removePrefix("sha256:") == captured.digest) { "Captured fixture input changed" }
        return strictUtf8(bytes)
    }

    /** Never attribute a mixed lazy-loaded/rebuilt classpath to the original captured artifacts. */
    fun requireExecutedArtifactsStable() {
        val drift = artifactDrift()
        check(drift.isEmpty()) { "Executed artifact drift after startup: ${drift.map { it["path"] }}" }
    }

    fun drift(): List<Map<String, String>> {
        val drift = artifactDrift().toMutableList()
        val currentPaths = runCatching { sourceFiles(repository) }.getOrElse {
            drift += mapOf("kind" to "source_inventory_unreadable_or_unsafe", "path" to "authored-source-inputs")
            emptyList()
        }
        val originals = sources.map { it.original }.toSet()
        currentPaths.filterNot { it in originals }.forEach {
            drift += mapOf("kind" to "new_source_input", "path" to relative(it))
        }
        for (source in sources) {
            val actual = runCatching { Digests.sha256(readStable(source.original, MAX_SOURCE_BYTES)).removePrefix("sha256:") }.getOrNull()
            if (actual != source.digest) drift += mapOf("kind" to "changed_missing_or_unsafe_source_input", "path" to relative(source.original))
            val preserved = runCatching { fileDigest(source.preserved).removePrefix("sha256:") }.getOrNull()
            if (preserved != source.digest) drift += mapOf("kind" to "preserved_source_corrupted", "path" to relative(source.preserved))
        }
        return drift.distinct()
    }

    private fun artifactDrift(): List<Map<String, String>> {
        val drift = mutableListOf<Map<String, String>>()
        for (artifact in artifacts) {
            val actual = runCatching { inventoryDigest(artifact.location, artifactFiles(artifact.location).map { path ->
                val bytes = readStable(path, MAX_ARTIFACT_BYTES)
                Capture(path, path, Digests.sha256(bytes).removePrefix("sha256:"), bytes.size.toLong())
            }) }.getOrNull()
            if (actual != artifact.digest) drift += mapOf("kind" to "executed_artifact_changed_missing_or_unsafe", "path" to artifact.location.toString())
            for (file in artifact.files) if (runCatching { fileDigest(file.preserved).removePrefix("sha256:") }.getOrNull() != file.digest) {
                drift += mapOf("kind" to "preserved_artifact_corrupted", "path" to relative(file.preserved))
            }
        }
        val resource = runCatching { registryBytes() }.getOrNull()
        if (resource == null || Digests.sha256(resource).removePrefix("sha256:") != registryResource.digest) {
            drift += mapOf("kind" to "executed_registry_resource_changed", "path" to registryResourceUrl)
        }
        if (runCatching { fileDigest(registryResource.preserved).removePrefix("sha256:") }.getOrNull() != registryResource.digest) {
            drift += mapOf("kind" to "preserved_registry_resource_corrupted", "path" to relative(registryResource.preserved))
        }
        return drift
    }

    private fun relative(path: Path) = repository.relativize(path.toAbsolutePath().normalize()).toString()
    private fun reference(path: Path) = mapOf("path" to relative(path), "sha256" to fileDigest(path).removePrefix("sha256:"))

    companion object {
        private const val MAX_FILES = 4_000
        private const val MAX_SOURCE_BYTES = 4_194_304
        private const val MAX_ARTIFACT_BYTES = 67_108_864
        private const val MAX_TOTAL_BYTES = 134_217_728L
        private val SOURCE_ROOTS = listOf("modules/provider-compiler/src/main", "modules/provider-compiler/src/test",
            "modules/provider-runtime/src/main", "modules/contracts/src/main", "providers/compiler-fixtures/cases")
        private val SOURCE_FILES = listOf("modules/provider-compiler/build.gradle.kts", "modules/provider-runtime/build.gradle.kts",
            "modules/contracts/build.gradle.kts", "providers/compiler-fixtures/manifest.json",
            "providers/compiler-fixtures/generate_fixtures.py", "providers/compiler-fixtures/generate_rule_map.py",
            "providers/compiler-fixtures/README.md", "providers/compiler-fixtures/.gitattributes",
            "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
            "gradle/wrapper/gradle-wrapper.properties")

        fun capture(repositoryPath: Path, runPath: Path): CompilerFixtureProvenance {
            val suppliedRepository = repositoryPath.toAbsolutePath().normalize()
            val repository = canonicalOuterDirectory(suppliedRepository)
            val suppliedRun = runPath.toAbsolutePath().normalize()
            require(suppliedRun.startsWith(suppliedRepository)) { "Evidence run must remain below the configured repository" }
            val runRoot = repository.resolve(suppliedRepository.relativize(suppliedRun))
            require(Files.isDirectory(runRoot, NOFOLLOW_LINKS) && runRoot.toRealPath() == runRoot) { "Unsafe evidence run directory" }
            val capturedAt = Instant.now().toString()
            var total = 0L
            fun preserve(original: Path, destination: Path, maximum: Int): Capture {
                val bytes = readStable(original, maximum)
                total += bytes.size
                require(total <= MAX_TOTAL_BYTES) { "Startup evidence exceeds total byte bound" }
                Files.createDirectories(destination.parent)
                Files.write(destination, bytes, CREATE_NEW, WRITE)
                val digest = Digests.sha256(bytes).removePrefix("sha256:")
                check(fileDigest(destination).removePrefix("sha256:") == digest && readStable(original, maximum).contentEquals(bytes)) {
                    "Source or artifact drift while capturing startup evidence"
                }
                return Capture(original, destination, digest, bytes.size.toLong())
            }
            val sources = sourceFiles(repository).map { original -> preserve(original,
                runRoot.resolve("captured-sources").resolve(repository.relativize(original)), MAX_SOURCE_BYTES) }
            val roleClasses = linkedMapOf("compiler_provider" to KotlinJvmCompilerProvider::class.java,
                "compiler_verification" to CompilerVerification::class.java,
                "fixture_runner" to CompilerFixtureRunner::class.java, "fixture_provenance" to CompilerFixtureProvenance::class.java,
                "process_runner" to IsolatedProcessRunner::class.java, "contracts" to AnalysisInput::class.java,
                "host_json_runtime" to kotlinx.serialization.json.Json::class.java,
                "host_serialization_runtime" to kotlinx.serialization.KSerializer::class.java,
                "host_kotlin_runtime" to Unit::class.java)
            val locations = roleClasses.entries.groupBy { entry ->
                val url = checkNotNull(entry.value.protectionDomain.codeSource?.location) { "Missing actual class code source" }
                require(url.protocol == "file") { "Unsupported class code-source protocol" }
                val supplied = Path.of(url.toURI()).toAbsolutePath().normalize()
                // This is an explicit class-source boundary, not an evidence descendant.
                // Reject a linked class-source leaf and all links within directory inventories.
                if (Files.isDirectory(supplied, NOFOLLOW_LINKS)) canonicalOuterDirectory(supplied)
                else canonicalOuterDirectory(supplied.parent).resolve(supplied.fileName).also {
                    require(Files.isRegularFile(it, NOFOLLOW_LINKS) && it.toRealPath() == it) { "Symlinked class code source" }
                }
            }
            val artifacts = locations.entries.mapIndexed { index, (location, roles) ->
                val files = artifactFiles(location).map { original -> preserve(original,
                    runRoot.resolve("executed-code/$index").resolve(artifactEntry(location, original)), MAX_ARTIFACT_BYTES) }
                Artifact(location, if (Files.isDirectory(location, NOFOLLOW_LINKS)) "class_directory" else "jar",
                    roles.map { it.key }, files, inventoryDigest(location, files))
            }
            val anchors = roleClasses.map { (role, clazz) ->
                val artifact = artifacts.single { role in it.roles }
                val entry = clazz.name.replace('.', '/') + ".class"
                val bytes = clazz.getResourceAsStream("/$entry")?.use { it.readNBytes(MAX_SOURCE_BYTES + 1) }
                    ?: error("Actual class resource unavailable")
                require(bytes.size <= MAX_SOURCE_BYTES)
                val preserved = if (artifact.kind == "class_directory") {
                    artifact.files.single { artifactEntry(artifact.location, it.original) == entry }.also {
                        check(it.digest == Digests.sha256(bytes).removePrefix("sha256:")) { "Class resource differs from captured code location" }
                    }
                } else {
                    JarFile(artifact.location.toFile()).use { jar ->
                        val jarEntry = checkNotNull(jar.getJarEntry(entry))
                        check(jar.getInputStream(jarEntry).use { it.readNBytes(MAX_SOURCE_BYTES + 1) }.contentEquals(bytes)) {
                            "Class resource differs from captured jar"
                        }
                    }
                    artifact.files.single()
                }
                mapOf("role" to role, "class_name" to clazz.name, "class_resource_sha256" to Digests.sha256(bytes).removePrefix("sha256:"),
                    "preserved_artifact" to mapOf("path" to repository.relativize(preserved.preserved).toString(), "sha256" to preserved.digest))
            }
            val registryBytes = registryBytes()
            val authoredRegistry = sources.single { it.original == repository.resolve("modules/provider-compiler/src/main/resources/compiler-rule-map.json") }
            check(registryBytes.contentEquals(readStable(authoredRegistry.preserved, MAX_SOURCE_BYTES))) {
                "Loaded compiler registry differs from captured authored mapping resource; rebuild before running"
            }
            val implementationSources = sources.filter { it.original.startsWith(repository.resolve("modules/provider-compiler/src/main/kotlin")) &&
                it.original.toString().endsWith(".kt") }.associateBy { repository.relativize(it.original).toString() }
            for (row in Json.parseToJsonElement(strictUtf8(registryBytes)).jsonObject.getValue("mappings").jsonArray) {
                val refs = row.jsonObject.getValue("implementation_evidence").jsonArray
                check(refs.map { it.jsonObject.getValue("path").jsonPrimitive.content }.toSet() == implementationSources.keys) {
                    "Mapping implementation inventory differs from captured provider sources; refresh mappings before running"
                }
                for (ref in refs) {
                    val fields = ref.jsonObject
                    check(implementationSources.getValue(fields.getValue("path").jsonPrimitive.content).digest ==
                        fields.getValue("sha256").jsonPrimitive.content.removePrefix("sha256:")) {
                        "Mapping source digest differs from startup source bytes; refresh mappings before running"
                    }
                }
            }
            val registryPath = runRoot.resolve("executed-resources/compiler-rule-map.json")
            Files.createDirectories(registryPath.parent)
            Files.write(registryPath, registryBytes, CREATE_NEW, WRITE)
            val registryResource = Capture(registryPath, registryPath, Digests.sha256(registryBytes).removePrefix("sha256:"), registryBytes.size.toLong())
            return CompilerFixtureProvenance(repository, runRoot, capturedAt, sources, artifacts, anchors, strictUtf8(registryBytes),
                registryResource, checkNotNull(CompilerRuleRegistry::class.java.getResource("/compiler-rule-map.json")).toString())
        }

        private fun registryBytes(): ByteArray = checkNotNull(CompilerRuleRegistry::class.java.getResourceAsStream("/compiler-rule-map.json"))
            .use { it.readNBytes(MAX_SOURCE_BYTES + 1) }.also { require(it.size <= MAX_SOURCE_BYTES) }

        private fun sourceFiles(repository: Path): List<Path> = (SOURCE_ROOTS.flatMap { root -> regularFiles(repository.resolve(root)) } +
            SOURCE_FILES.map { repository.resolve(it).also { path -> require(Files.isRegularFile(path, NOFOLLOW_LINKS)) } })
            .distinct().sorted().also { require(it.size <= MAX_FILES) }

        private fun artifactFiles(location: Path): List<Path> = (if (Files.isDirectory(location, NOFOLLOW_LINKS)) regularFiles(location) else
            listOf(location).also { require(Files.isRegularFile(location, NOFOLLOW_LINKS) && location.toString().endsWith(".jar")) })
            .also { paths -> require(paths.sumOf { Files.size(it) } <= MAX_TOTAL_BYTES) { "Executed artifact inventory exceeds byte bound" } }

        private fun regularFiles(root: Path): List<Path> {
            require(Files.isDirectory(root, NOFOLLOW_LINKS) && root.toRealPath() == root.toAbsolutePath().normalize()) { "Unsafe startup inventory root" }
            return Files.walk(root).use { stream -> stream.peek { require(!Files.isSymbolicLink(it)) { "Symlink in startup inventory" } }
                .filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.limit(MAX_FILES.toLong() + 1).sorted().toList() }
                .also { require(it.size <= MAX_FILES) { "Startup inventory exceeds file bound" } }
        }

        private fun artifactEntry(location: Path, original: Path): String = if (Files.isDirectory(location, NOFOLLOW_LINKS))
            location.relativize(original).toString() else original.fileName.toString()

        private fun inventoryDigest(location: Path, files: List<Capture>): String = Digests.sha256(files.sortedBy { artifactEntry(location, it.original) }
            .joinToString("\n") { "${artifactEntry(location, it.original)}\u0000${it.digest}\u0000${it.size}" }).removePrefix("sha256:")

        /** Resolve OS aliases above a caller-declared outer root once, never aliases in evidence descendants. */
        internal fun canonicalOuterDirectory(root: Path): Path {
            val supplied = root.toAbsolutePath().normalize()
            require(Files.isDirectory(supplied, NOFOLLOW_LINKS) && !Files.isSymbolicLink(supplied)) { "Unsafe outer evidence directory" }
            return supplied.toRealPath()
        }

        internal fun readStable(path: Path, maximum: Int): ByteArray {
            require(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) &&
                path.toRealPath() == path.toAbsolutePath().normalize()) { "Unsafe evidence input" }
            val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(before.size() <= maximum) { "Evidence input exceeds file byte bound" }
            val bytes = Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { it.readNBytes(maximum + 1) }
            val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            check(bytes.size <= maximum && bytes.size.toLong() == before.size() && before.size() == after.size() &&
                before.lastModifiedTime() == after.lastModifiedTime() && before.fileKey() == after.fileKey()) { "Evidence input changed while reading" }
            return bytes
        }
    }
}

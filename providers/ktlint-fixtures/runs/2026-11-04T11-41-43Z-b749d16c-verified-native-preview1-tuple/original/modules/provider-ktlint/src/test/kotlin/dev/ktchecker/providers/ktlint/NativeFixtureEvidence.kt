package dev.ktchecker.providers.ktlint

import dev.ktchecker.contracts.Digests
import dev.ktchecker.providers.runtime.IsolatedProcessRunner
import dev.ktchecker.providers.runtime.OwnedSupervisorIdentity
import dev.ktchecker.providers.runtime.ProcessOutcome
import dev.ktchecker.providers.runtime.fileDigest
import dev.ktchecker.providers.runtime.safeRelativePath
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.*
import kotlin.test.*

/** Test-only actual installed bundle witness; never an inspector-derived launch claim. */
internal data class NativeFixtureWitness(
    val identity: OwnedSupervisorIdentity,
    val anchor: Class<*>,
    val references: List<JsonObject>,
    val verify: () -> Unit,
) {
    fun assertExecuted(actual: ProcessOutcome) {
        assertEquals("executed", actual.status, actual.reasons.joinToString())
        assertTrue(actual.executionConfirmed, "A fork-child exit is not proof that the provider executable started")
        assertTrue(actual.executed && actual.successfulExecution)
        assertFalse(actual.cancelled || actual.timedOut || actual.truncated)
        assertEquals(identity, assertNotNull(actual.supervisorIdentity), "Actual launched helper differs from retained START manifest/binary")
    }
}

internal fun supervisorIdentityJson(identity: OwnedSupervisorIdentity?, origin: String = "actual_launched_helper_result_not_inspector"): JsonElement =
    identity?.let { value -> buildJsonObject {
        put("protocol_version", value.protocolVersion); put("target", value.target)
        put("source_digest", value.sourceDigest); put("binary_digest", value.binaryDigest)
        put("identity_origin", origin); put("native_qualification", false)
    } } ?: JsonNull

/** The runtime completion callback reports refusals too; it does not imply a fork. */
internal fun assertFixtureAuthorizationDenied(actual: ProcessOutcome) {
    assertEquals("unavailable", actual.status)
    assertEquals(listOf("execution_not_authorized"), actual.reasons)
    assertFalse(actual.executionConfirmed || actual.executed || actual.successfulExecution)
    assertNull(actual.supervisorIdentity)
    assertNull(actual.exitCode)
    assertNull(actual.outputDirectory)
    assertTrue(actual.command.isEmpty())
    assertEquals("", actual.stdout); assertEquals("", actual.stderr)
    assertFalse(actual.cancelled || actual.timedOut || actual.truncated)
    assertEquals(0L, actual.metrics.outputBytes)
    assertNull(actual.metrics.cpuMillis); assertNull(actual.metrics.peakRssBytes)
}

internal fun fixtureProcessJson(actual: ProcessOutcome, temporary: Path): JsonObject = buildJsonObject {
    put("status", actual.status); put("exit_code", actual.exitCode); put("execution_confirmed", actual.executionConfirmed)
    put("executed", actual.executed); put("successful_execution", actual.successfulExecution)
    put("output_directory", actual.outputDirectory?.toString()?.replace(temporary.toString(), "{fixture-temporary}"))
    put("stdout", actual.stdout.replace(temporary.toString(), "{fixture-temporary}"))
    put("stderr", actual.stderr.replace(temporary.toString(), "{fixture-temporary}"))
    put("reasons", JsonArray(actual.reasons.map(::JsonPrimitive)))
    put("cancelled", actual.cancelled); put("timed_out", actual.timedOut); put("truncated", actual.truncated)
    put("metrics", dev.ktchecker.contracts.ContractJson.encodeToJsonElement(actual.metrics))
    put("command", JsonArray(actual.command.map { JsonPrimitive(it.replace(temporary.toString(), "{fixture-temporary}")) }))
    put("supervisor_identity", supervisorIdentityJson(actual.supervisorIdentity))
}

internal fun captureNativeFixtureWitness(repository: Path, prefix: String,
    artifact: (String, ByteArray) -> JsonObject): NativeFixtureWitness {
    val root = repository.toAbsolutePath().normalize()
    fun reference(path: Path): JsonObject = buildJsonObject {
        put("path", root.relativize(path).toString().replace('\\', '/')); put("sha256", fileDigest(path).removePrefix("sha256:"))
    }
    val sources = listOf("owned_process_supervisor.c", "build_supervisor.py", "README.md")
        .map { root.resolve("modules/provider-runtime/native/$it") }.associateWith { path ->
            require(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) && Files.size(path) <= 2_000_000)
            Files.readAllBytes(path)
        }
    val sourceRefs = sources.flatMap { (path, bytes) -> listOf(reference(path),
        artifact("$prefix-native-at-start/${root.relativize(path).toString().replace('\\', '/')}", bytes)) }
    val anchor = Class.forName("dev.ktchecker.providers.runtime.OwnedProcessSupervisor", false, IsolatedProcessRunner::class.java.classLoader)
    val origin = anchor.protectionDomain?.codeSource?.location ?: error("Actual helper loader has no installed CodeSource")
    require(origin.protocol == "file" && origin.query == null && origin.ref == null)
    val codeSource = Path.of(origin.toURI()).toAbsolutePath().normalize()
    val resourcePrefix = "dev/ktchecker/providers/runtime/native/"
    val manifest = readNativeFixtureResource(root, codeSource, resourcePrefix + "manifest", 512)
    val lines = manifest.toString(Charsets.UTF_8).split('\n')
    require(lines.size == 5 && lines.last().isEmpty() && lines[0] == "ktchecker-owned-child-supervisor/preview-1")
    require(lines[1].matches(Regex("(linux|macos)-(x86_64|aarch64)")) && Digests.valid(lines[2]) && Digests.valid(lines[3]))
    val identity = OwnedSupervisorIdentity(lines[0], lines[1], lines[2], lines[3])
    assertEquals(identity.sourceDigest, Digests.sha256(sources.getValue(root.resolve("modules/provider-runtime/native/owned_process_supervisor.c"))),
        "Installed manifest must retain the same C source bytes captured at START; this is not reproducible-build proof")
    val binaryName = resourcePrefix + identity.target + "/supervisor"
    val binary = readNativeFixtureResource(root, codeSource, binaryName, 1_048_576)
    assertEquals(identity.binaryDigest, Digests.sha256(binary))
    val provenanceName = resourcePrefix + "build-provenance.json"
    val provenance = readNativeFixtureResource(root, codeSource, provenanceName, 16_384)
    val receipt = Json.parseToJsonElement(provenance.toString(Charsets.UTF_8)).jsonObject
    require(receipt["schemaVersion"]?.jsonPrimitive?.content == "ktchecker-owned-child-supervisor-build/1")
    // Informational only. Its compiler driver receipt does not establish transitive build closure.
    val resources = linkedMapOf(resourcePrefix + "manifest" to manifest, binaryName to binary, provenanceName to provenance)
    val resourceRefs = resources.flatMap { (name, bytes) -> listOf(
        artifact("$prefix-native-image/${name.removePrefix(resourcePrefix)}", bytes),
        reference(if (Files.isDirectory(codeSource, NOFOLLOW_LINKS)) codeSource.parent.parent.parent.resolve("resources/main/$name") else codeSource),
    ) }.distinct()
    val start = artifact("$prefix-native-start-witness.json", buildJsonObject {
        put("phase", "START_before_processes"); put("qualified", false)
        put("loader_anchor", anchor.name); put("resources", JsonArray(resourceRefs)); put("sources", JsonArray(sourceRefs))
        put("installed_identity", supervisorIdentityJson(identity, "exact_installed_CodeSource_manifest_binary_at_START_not_inspector"))
        put("build_provenance", "Informational compiler-driver receipt only; not native platform qualification or reproducible source-to-binary proof.")
        put("code_source_scope", "Exact helper loader CodeSource JAR or literal same-module build/classes/kotlin/main -> build/resources/main; no context-classloader enumeration or fallback.")
    }.toString().toByteArray())
    return NativeFixtureWitness(identity, anchor, sourceRefs + resourceRefs + start) {
        for ((path, bytes) in sources) assertContentEquals(bytes, Files.readAllBytes(path), "Native source drift after START: $path")
        for ((name, bytes) in resources) assertContentEquals(bytes,
            readNativeFixtureResource(root, codeSource, name, if (name.endsWith("/manifest")) 512 else if (name.endsWith(".json")) 16_384 else 1_048_576),
            "Installed native bundle drift after START: $name")
        assertEquals(codeSource, Path.of(anchor.protectionDomain.codeSource.location.toURI()).toAbsolutePath().normalize())
    }
}

private fun readNativeFixtureResource(root: Path, source: Path, name: String, maximum: Int): ByteArray {
    require(source.startsWith(root) && !Files.isSymbolicLink(source) && maximum in 1..1_048_576)
    val relative = safeRelativePath(name)
    val bytes = if (Files.isDirectory(source, NOFOLLOW_LINKS)) {
        require(source.endsWith(Path.of("build/classes/kotlin/main")))
        val build = source.parent.parent.parent
        val path = build.resolve("resources/main").resolve(relative)
        var current = build
        for (part in build.relativize(path)) {
            current = current.resolve(part)
            require(!Files.isSymbolicLink(current))
            require(if (current == path) Files.isRegularFile(current, NOFOLLOW_LINKS) else Files.isDirectory(current, NOFOLLOW_LINKS))
        }
        require(Files.size(path) in 1..maximum.toLong())
        Files.newInputStream(path).use { it.readNBytes(maximum + 1) }
    } else {
        require(Files.isRegularFile(source, NOFOLLOW_LINKS) && source.toString().endsWith(".jar") && Files.size(source) <= 32_000_000)
        ZipFile(source.toFile()).use { archive ->
            require(archive.size() <= 100_000)
            val entries = archive.entries().asSequence().filter { it.name == name }.toList()
            require(entries.size == 1)
            val entry = entries.single()
            require(!entry.isDirectory && entry.size in 1..maximum.toLong())
            archive.getInputStream(entry).use { it.readNBytes(maximum + 1) }.also { require(it.size.toLong() == entry.size) }
        }
    }
    require(bytes.isNotEmpty() && bytes.size <= maximum)
    return bytes
}

/** Already loaded project code images only; never load upstream engines into the fixture JVM. */
internal fun fixtureProjectCodeImage(repository: Path, source: Path): ByteArray {
    require(source.startsWith(repository.toAbsolutePath().normalize()) && !Files.isSymbolicLink(source))
    if (Files.isRegularFile(source, NOFOLLOW_LINKS)) {
        require(source.toString().endsWith(".jar") && Files.size(source) in 1..32_000_000L)
        return Files.newInputStream(source).use { it.readNBytes(32_000_001) }
            .also { require(it.size in 1..32_000_000) }
    }
    require(Files.isDirectory(source, NOFOLLOW_LINKS))
    val files = Files.walk(source).use { paths -> paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) && it.toString().endsWith(".class") }
        .limit(10_001).sorted().toList() }
    require(files.isNotEmpty() && files.size <= 10_000)
    var total = 0L
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        for (file in files) {
            val remaining = (32_000_000 - total).toInt()
            require(Files.size(file) in 1..remaining.toLong())
            val data = Files.newInputStream(file).use { it.readNBytes(remaining + 1) }
            total += data.size; require(total <= 32_000_000)
            zip.putNextEntry(ZipEntry(source.relativize(file).toString().replace('\\', '/')).apply { time = 0 })
            zip.write(data); zip.closeEntry()
        }
    }
    return bytes.toByteArray()
}

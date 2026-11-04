package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import dev.ktchecker.providers.runtime.IsolatedProcessRunner
import dev.ktchecker.providers.runtime.OwnedProcessContainment
import dev.ktchecker.providers.runtime.OwnedSupervisorIdentity
import dev.ktchecker.providers.runtime.ProcessOutcome
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import java.util.zip.ZipInputStream

private const val STARTUP_EVIDENCE_LIMIT = 4 * 1024 * 1024
private const val STARTUP_ENTRY_LIMIT = 8_000

@Serializable internal data class OwnerFixtureSupervisorIdentity(val protocolVersion: String, val target: String,
    val declaredSourceDigest: String, val binaryDigest: String)
@Serializable internal data class OwnerFixtureStartupInput(val role: String, val original: String, val preserved: String,
    val digest: String, val size: Long)
@Serializable internal data class OwnerFixtureStartupTree(val root: String, val preserved: String, val entries: List<String>)
@Serializable internal data class OwnerFixtureStartupEvidence(val schemaVersion: Int, val capturedAt: String,
    val supervisor: OwnerFixtureSupervisorIdentity, val nativeManifest: ModelFile, val nativeBinary: ModelFile,
    val nativeBuildReceipt: ModelFile, val runtimeCodeSource: String, val runtimeClassRoles: List<String>,
    val inputs: List<OwnerFixtureStartupInput>, val trees: List<OwnerFixtureStartupTree>,
    val sourceToBinaryAttested: Boolean = false, val loadedJvmMemoryAttested: Boolean = false,
    val limitations: List<String> = listOf(
        "Startup authored sources, installed JVM code images and declared native bundle bytes are independent identities, not source-to-binary proof.",
        "Only actual runner completion can provide terminal-derived execution confirmation and the native image identity used by that invocation.",
        "Host JVM, loaded memory, OS loader/libraries and hostile same-user modification are not attested; no runtime tuple is qualified."))

/** A START-time private capture, not serialized authority or caller-selected native installation.
 * The native helper still comes only from the actual provider-runtime code source. Inspection
 * supplies expected bundle bytes; it is never substituted for the invocation's actual outcome. */
internal class OwnerFixtureStartupCapture private constructor(
    val evidence: OwnerFixtureStartupEvidence,
    val evidencePath: Path,
    val supervisorIdentity: OwnedSupervisorIdentity,
    private val originals: List<CapturedInput>,
    private val trees: List<CapturedTree>,
    private val runtimeOrigins: List<Pair<Class<*>, Path>>,
) {
    private data class CapturedInput(val original: Path, val preserved: Path, val digest: String, val size: Long)
    private data class CapturedTree(val root: Path, val preserved: Path, val entries: List<String>)
    val digest: String = Digests.sha256("ktchecker/owner-fixture-startup/1\u0000" + ContractJson.encodeToString(evidence))

    /** Additional drift detection against START originals, never a recapture/enrollment. */
    fun verify() {
        runtimeOrigins.forEach { (type, root) ->
            startupExpect(codeSource(type) == root, "owner_fixture_startup_runtime_origin_mismatch")
        }
        trees.forEach { startupExpect(startupTree(it.root) == it.entries && startupTree(it.preserved) == it.entries,
            "owner_fixture_startup_image_changed") }
        originals.forEach { input ->
            startupExpect(hashFile(input.original, MAX_FILE_BYTES.toLong(), input.size) == input.digest &&
                hashFile(input.preserved, MAX_FILE_BYTES.toLong(), input.size) == input.digest,
                "owner_fixture_startup_input_changed")
            startupExpect(Files.getPosixFilePermissions(input.preserved, NOFOLLOW_LINKS) == setOf(PosixFilePermission.OWNER_READ),
                "owner_fixture_startup_copy_permissions_changed")
        }
        verifyOwnerFixtureStartupEvidenceBound(evidence)
        startupExpect(Files.getPosixFilePermissions(evidencePath, NOFOLLOW_LINKS) == setOf(PosixFilePermission.OWNER_READ) &&
            ownerFixtureUtf8(readOwnerFixtureInput(evidencePath, STARTUP_EVIDENCE_LIMIT)) ==
            ContractJson.encodeToString(evidence) + "\n", "owner_fixture_startup_evidence_changed")
    }

    /** Called only on the actual runner's returned outcome by the private invocation registry. */
    fun verifyCompletion(actual: ProcessOutcome) {
        verifyOwnerFixtureSupervisorCompletion(supervisorIdentity, actual)
        verify()
    }

    companion object {
        private const val MAX_FILE_BYTES = 32 * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
        private const val NATIVE_PREFIX = "dev/ktchecker/providers/runtime/native/"
        private const val PROTOCOL = "ktchecker-owned-child-supervisor/preview-1"
        private val SOURCE_PATHS = listOf(
            "modules/provider-runtime/native/owned_process_supervisor.c",
            "modules/provider-runtime/native/build_supervisor.py",
            "modules/provider-runtime/build.gradle.kts",
            "modules/provider-runtime/src/main/kotlin/dev/ktchecker/providers/runtime/Execution.kt",
            "modules/provider-runtime/src/main/kotlin/dev/ktchecker/providers/runtime/OwnedProcessSupervisor.kt",
            "modules/provider-runtime/src/main/kotlin/dev/ktchecker/providers/runtime/OwnedSupervisorImage.kt",
            "modules/project-model/src/test/kotlin/dev/ktchecker/projectmodel/OwnerModelFixtureRunner.kt",
            "modules/project-model/src/test/kotlin/dev/ktchecker/projectmodel/OwnerFixtureStartup.kt",
            "modules/project-model/src/test/kotlin/dev/ktchecker/projectmodel/OwnerFixtureExecutionHome.kt",
        )
        private val RUNTIME_ROLES = listOf("IsolatedProcessRunner", "ProcessOutcome", "ExecutionPermit", "ExactExecutionAuthority",
            "OwnedProcessContainment", "OwnedSupervisorIdentity", "OwnedProcessSupervisor", "SupervisorTransport",
            "SupervisorFrameReader", "SupervisorImageLoader", "PreparedSupervisorImage")

        fun capture(fixtures: Path, work: Path): OwnerFixtureStartupCapture {
            val capturedAt = Instant.now().toString()
            startupExpect(fixtures.isAbsolute && fixtures.normalize() == fixtures &&
                fixtures.fileName.toString() == "model-fixtures" && fixtures.parent.fileName.toString() == "providers",
                "owner_fixture_startup_repository_invalid")
            val repository = fixtures.parent.parent
            val preservedRoot = Files.createDirectory(work.resolve("startup-inputs"))
            var total = 0L
            val originals = mutableListOf<CapturedInput>()
            val records = mutableListOf<OwnerFixtureStartupInput>()
            val trees = mutableListOf<CapturedTree>()
            fun preserve(path: Path, role: String, relative: String, maximum: Int = MAX_FILE_BYTES): ByteArray {
                logicalPath(relative)
                startupExpect(originals.size < STARTUP_ENTRY_LIMIT, "owner_fixture_startup_bound")
                val bytes = readOwnerFixtureInput(path, maximum)
                total += bytes.size
                startupExpect(total <= MAX_TOTAL_BYTES, "owner_fixture_startup_bound")
                val copy = preservedRoot.resolve(relative)
                Files.createDirectories(copy.parent)
                Files.write(copy, bytes, CREATE_NEW, WRITE, NOFOLLOW_LINKS)
                Files.setPosixFilePermissions(copy, setOf(PosixFilePermission.OWNER_READ))
                val digest = Digests.sha256(bytes)
                startupExpect(hashFile(copy, maximum.toLong(), bytes.size.toLong()) == digest &&
                    hashFile(path, maximum.toLong(), bytes.size.toLong()) == digest, "owner_fixture_startup_input_changed")
                originals += CapturedInput(path, copy, digest, bytes.size.toLong())
                records += OwnerFixtureStartupInput(role, path.toString(), copy.toString(), digest, bytes.size.toLong())
                return bytes
            }
            SOURCE_PATHS.forEach { preserve(repository.resolve(it), "authored-source", "sources/$it", 2 * 1024 * 1024) }

            val runtimeClass = IsolatedProcessRunner::class.java
            val runtimeRoot = codeSource(runtimeClass)
            val roleNames = RUNTIME_ROLES.map { "dev.ktchecker.providers.runtime.$it" }
            val runtimeOrigins = roleNames.map { name ->
                val actual = Class.forName(name, false, runtimeClass.classLoader)
                startupExpect(codeSource(actual) == runtimeRoot, "owner_fixture_startup_runtime_origin_mismatch")
                actual to runtimeRoot
            }

            // This expected identity is fixed at START; never replace it with a later inspection.
            val availability = OwnedProcessContainment.inspect()
            startupExpect(availability.available && availability.identity != null, "owner_fixture_supervisor_bundle_unavailable")
            val supervisor = requireNotNull(availability.identity)
            startupExpect(supervisor.protocolVersion == PROTOCOL && supervisor.target in
                setOf("linux-x86_64", "linux-aarch64", "macos-x86_64", "macos-aarch64"), "owner_fixture_supervisor_bundle_invalid")
            val names = mapOf(NATIVE_PREFIX + "manifest" to 512,
                NATIVE_PREFIX + supervisor.target + "/supervisor" to 1024 * 1024,
                NATIVE_PREFIX + "build-provenance.json" to 16 * 1024)
            val native: Map<String, ByteArray>
            if (Files.isRegularFile(runtimeRoot, NOFOLLOW_LINKS)) {
                val bytes = preserve(runtimeRoot, "installed-runtime-jar-image", "runtime-image/provider-runtime.jar")
                native = nativeEntries(bytes, names)
            } else {
                startupExpect(Files.isDirectory(runtimeRoot, NOFOLLOW_LINKS) &&
                    runtimeRoot.map { it.toString() }.takeLast(4) == listOf("build", "classes", "kotlin", "main"),
                    "owner_fixture_startup_runtime_origin_mismatch")
                val build = runtimeRoot.parent.parent.parent
                val resources = build.resolve("resources/main")
                val selected = mutableMapOf<String, ByteArray>()
                listOf("classes" to runtimeRoot, "resources" to resources).forEach { (kind, root) ->
                    val entries = startupTree(root)
                    startupExpect(trees.sumOf { it.entries.size } + entries.size + SOURCE_PATHS.size <= STARTUP_ENTRY_LIMIT,
                        "owner_fixture_startup_bound")
                    val preservedTree = Files.createDirectory(preservedRoot.resolve("runtime-image/$kind").also {
                        Files.createDirectories(it.parent)
                    })
                    entries.filter { it.startsWith("directory:") }.forEach { entry ->
                        Files.createDirectories(preservedTree.resolve(entry.removePrefix("directory:")))
                    }
                    trees += CapturedTree(root, preservedTree, entries)
                    entries.filter { it.startsWith("file:") }.forEach { entry ->
                        val relative = entry.removePrefix("file:")
                        val bytes = preserve(root.resolve(relative), "installed-runtime-$kind-image", "runtime-image/$kind/$relative",
                            if (kind == "resources") names[relative] ?: MAX_FILE_BYTES else MAX_FILE_BYTES)
                        if (kind == "resources" && relative in names) selected[relative] = bytes
                    }
                    startupExpect(startupTree(root) == entries, "owner_fixture_startup_image_changed")
                }
                native = selected
            }
            startupExpect(native.keys == names.keys, "owner_fixture_supervisor_bundle_incomplete")
            val manifest = native.getValue(NATIVE_PREFIX + "manifest")
            val binary = native.getValue(NATIVE_PREFIX + supervisor.target + "/supervisor")
            val receipt = native.getValue(NATIVE_PREFIX + "build-provenance.json")
            val lines = ownerFixtureUtf8(manifest).split('\n')
            startupExpect(lines == listOf(supervisor.protocolVersion, supervisor.target, supervisor.sourceDigest, supervisor.binaryDigest, "") &&
                binary.isNotEmpty() && receipt.isNotEmpty() && Digests.sha256(binary) == supervisor.binaryDigest,
                "owner_fixture_supervisor_bundle_identity_mismatch")
            val capturedSource = records.single { it.original == repository.resolve(SOURCE_PATHS.first()).toString() }
            // Only consistency with the bundle's DECLARED source claim. This is not a build proof.
            startupExpect(capturedSource.digest == supervisor.sourceDigest, "owner_fixture_supervisor_declared_source_changed")
            val evidence = OwnerFixtureStartupEvidence(1, capturedAt,
                OwnerFixtureSupervisorIdentity(supervisor.protocolVersion, supervisor.target, supervisor.sourceDigest, supervisor.binaryDigest),
                ModelFile(NATIVE_PREFIX + "manifest", Digests.sha256(manifest), manifest.size.toLong()),
                ModelFile(NATIVE_PREFIX + supervisor.target + "/supervisor", Digests.sha256(binary), binary.size.toLong()),
                ModelFile(NATIVE_PREFIX + "build-provenance.json", Digests.sha256(receipt), receipt.size.toLong()),
                runtimeRoot.toString(), roleNames, records.toList(), trees.map {
                    OwnerFixtureStartupTree(it.root.toString(), it.preserved.toString(), it.entries)
                })
            val evidencePath = preservedRoot.resolve("startup-evidence.json")
            // Bound worst-case JSON escaping BEFORE constructing the serialized content.
            verifyOwnerFixtureStartupEvidenceBound(evidence)
            val serialized = ContractJson.encodeToString(evidence) + "\n"
            startupExpect(serialized.toByteArray().size <= STARTUP_EVIDENCE_LIMIT, "owner_fixture_startup_bound")
            Files.writeString(evidencePath, serialized, CREATE_NEW, WRITE, NOFOLLOW_LINKS)
            Files.setPosixFilePermissions(evidencePath, setOf(PosixFilePermission.OWNER_READ))
            Files.walk(preservedRoot).use { paths -> paths.filter { Files.isDirectory(it, NOFOLLOW_LINKS) }.forEach { directory ->
                Files.setPosixFilePermissions(directory, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
            } }
            return OwnerFixtureStartupCapture(evidence, evidencePath, supervisor, originals.toList(), trees.toList(), runtimeOrigins).also { it.verify() }
        }

        private fun codeSource(type: Class<*>): Path {
            val selected = type.protectionDomain?.codeSource?.location
                ?: throw OwnerFixtureProblem("owner_fixture_startup_code_source_unavailable")
            startupExpect(selected.protocol == "file" && selected.ref == null && selected.query == null,
                "owner_fixture_startup_code_source_unavailable")
            val original = Path.of(selected.toURI())
            startupExpect(original.isAbsolute && original.normalize() == original && original.parent != null && !Files.isSymbolicLink(original),
                "owner_fixture_startup_code_source_unsafe")
            // The actual loaded code source is a trusted outer selection. Its leaf and all
            // image descendants remain no-follow; this never normalizes project artifacts.
            return original.parent.toRealPath().resolve(original.fileName)
        }

        private fun startupTree(root: Path): List<String> {
            startupExpect(Files.isDirectory(root, NOFOLLOW_LINKS) && !Files.isSymbolicLink(root), "owner_fixture_startup_image_unsafe")
            var count = 0
            var textBudget = 0L
            return Files.walk(root).use { paths -> paths.filter { it != root }.map { path ->
                startupExpect(++count <= STARTUP_ENTRY_LIMIT && !Files.isSymbolicLink(path), "owner_fixture_startup_image_unsafe")
                val relative = root.relativize(path).toString().replace('\\', '/')
                logicalPath(relative)
                textBudget += relative.length.toLong() * 6 + 80
                startupExpect(textBudget <= STARTUP_EVIDENCE_LIMIT, "owner_fixture_startup_bound")
                val directory = Files.isDirectory(path, NOFOLLOW_LINKS)
                startupExpect(directory || Files.isRegularFile(path, NOFOLLOW_LINKS), "owner_fixture_startup_image_unsafe")
                (if (directory) "directory:" else "file:") + relative
            }.sorted().toList() }
        }

        private fun nativeEntries(bytes: ByteArray, required: Map<String, Int>): Map<String, ByteArray> {
            val result = mutableMapOf<String, ByteArray>()
            val names = mutableSetOf<String>()
            var count = 0
            var total = 0L
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    startupExpect(++count <= STARTUP_ENTRY_LIMIT && names.add(entry.name), "owner_fixture_startup_jar_invalid")
                    logicalPath(entry.name.trimEnd('/'))
                    val maximum = required[entry.name]
                    if (maximum != null) startupExpect(!entry.isDirectory && entry.size <= maximum, "owner_fixture_startup_bound")
                    val retained = maximum?.let { ByteArrayOutputStream(minOf(it, 8192)) }
                    var entrySize = 0L
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val amount = zip.read(buffer)
                        if (amount < 0) break
                        entrySize += amount; total += amount
                        startupExpect(total <= MAX_TOTAL_BYTES && (maximum == null || entrySize <= maximum), "owner_fixture_startup_bound")
                        retained?.write(buffer, 0, amount)
                    }
                    if (retained != null) result[entry.name] = retained.toByteArray()
                }
            }
            return result
        }
    }
}

/** Conservative pure allocation guard for evidence data, not capture or execution authority. */
internal fun verifyOwnerFixtureStartupEvidenceBound(evidence: OwnerFixtureStartupEvidence) {
    startupExpect(evidence.inputs.size <= STARTUP_ENTRY_LIMIT && evidence.trees.size <= 2 &&
        evidence.trees.sumOf { it.entries.size.toLong() } <= STARTUP_ENTRY_LIMIT,
        "owner_fixture_startup_bound")
    // Six bytes per UTF-16 code unit covers JSON escaping and UTF-8; the fixed/record
    // margins cover field names, punctuation, numeric values, booleans and the newline.
    var budget = 16_384L + evidence.inputs.size * 256L + evidence.trees.size * 128L
    fun text(value: String) {
        budget += value.length.toLong() * 6 + 16
        startupExpect(budget <= STARTUP_EVIDENCE_LIMIT, "owner_fixture_startup_bound")
    }
    text(evidence.capturedAt)
    with(evidence.supervisor) { text(protocolVersion); text(target); text(declaredSourceDigest); text(binaryDigest) }
    listOf(evidence.nativeManifest, evidence.nativeBinary, evidence.nativeBuildReceipt).forEach { text(it.path); text(it.digest) }
    text(evidence.runtimeCodeSource)
    evidence.runtimeClassRoles.forEach(::text)
    evidence.inputs.forEach { text(it.role); text(it.original); text(it.preserved); text(it.digest) }
    evidence.trees.forEach { text(it.root); text(it.preserved); it.entries.forEach(::text) }
    evidence.limitations.forEach(::text)
}

/** Pure comparison only, not a completion receipt constructor or execution/import authority. */
internal fun verifyOwnerFixtureSupervisorCompletion(expected: OwnedSupervisorIdentity, actual: ProcessOutcome) {
    startupExpect(actual.executionConfirmed, "owner_fixture_supervisor_execution_not_confirmed")
    startupExpect(actual.supervisorIdentity == expected, "owner_fixture_supervisor_actual_identity_mismatch")
    startupExpect(actual.executed && actual.successfulExecution && actual.status == "executed" &&
        actual.exitCode == 0 && !actual.cancelled && !actual.timedOut && !actual.truncated && actual.reasons.isEmpty(),
        "owner_fixture_process_verification_failed")
}

private fun startupExpect(condition: Boolean, code: String) { if (!condition) throw OwnerFixtureProblem(code) }

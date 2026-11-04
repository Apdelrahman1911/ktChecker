package dev.ktchecker.providers.runtime

import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * The installed bundle's content identity, not an execution grant or a platform qualification.
 * binaryDigest is independently rehashed; sourceDigest is the installed build manifest's claim.
 */
data class OwnedSupervisorIdentity(
    val protocolVersion: String,
    val target: String,
    val sourceDigest: String,
    val binaryDigest: String,
)

data class OwnedSupervisorAvailability(
    val status: String,
    val target: String,
    val identity: OwnedSupervisorIdentity? = null,
    val reasons: List<String> = emptyList(),
) {
    val available: Boolean get() = status == "available"
}

/**
 * Inspect the exact installed bundle without executing or extracting it. `available` means only
 * that the supported host's bundled bytes and manifest match; it is not a cleanup/ABI qualification.
 * A later execution returns the identity it actually loaded, not this earlier inspection result.
 */
object OwnedProcessContainment {
    const val MANIFEST_RESOURCE = "/dev/ktchecker/providers/runtime/native/manifest"

    fun inspect(): OwnedSupervisorAvailability = SupervisorImageLoader().inspect()
}

internal object SupervisorBundleFormat {
    const val VERSION = "ktchecker-owned-child-supervisor/preview-1"
    const val PREFIX = "dev/ktchecker/providers/runtime/native/"
    const val MANIFEST = PREFIX + "manifest"
    const val MAX_MANIFEST = 512
    const val MAX_BINARY = 1_048_576
    const val MAX_JAR = 128L * 1024 * 1024
    const val MAX_JAR_ENTRIES = 100_000
    val targets = setOf("linux-x86_64", "linux-aarch64", "macos-x86_64", "macos-aarch64")
    val digest = Regex("sha256:[0-9a-f]{64}")

    fun target(os: String = System.getProperty("os.name", ""), arch: String = System.getProperty("os.arch", "")): String {
        val cpu = when (arch.lowercase(java.util.Locale.ROOT)) {
            "amd64", "x86_64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> return "unsupported"
        }
        val system = os.lowercase(java.util.Locale.ROOT)
        val platform = when {
            system == "linux" -> "linux"
            system == "mac os x" || system == "darwin" || system == "macos" -> "macos"
            system.startsWith("windows") -> "windows"
            else -> return "unsupported"
        }
        return "$platform-$cpu"
    }
}

/** Internal injection seam for byte-only tests. Production always uses this class's code source. */
internal fun interface SupervisorResources {
    fun read(name: String, maximum: Int): ByteArray?
}

internal class VerifiedSupervisorBundle(val identity: OwnedSupervisorIdentity, bytes: ByteArray) {
    private val content = bytes.clone()

    fun extract(parent: Path? = null): PreparedSupervisorImage {
        var root: Path? = null
        var rootKey: String? = null
        var fileKey: String? = null
        try {
            val privateDirectory = PosixFilePermissions.asFileAttribute(supervisorPermissions("rwx------"))
            val created = if (parent == null) Files.createTempDirectory("ktchecker-owned-supervisor-", privateDirectory)
                else Files.createTempDirectory(parent, "ktchecker-owned-supervisor-", privateDirectory)
            val selectedRoot = created.toAbsolutePath()
            root = selectedRoot
            val rootAttributes = supervisorAttributes(selectedRoot)
            val selectedRootKey = rootAttributes.fileKey()?.toString()
                ?: supervisorImageProblem("process_supervisor_private_io_unavailable")
            rootKey = selectedRootKey
            if (!rootAttributes.isDirectory || rootAttributes.isSymbolicLink ||
                Files.getFileAttributeView(selectedRoot, PosixFileAttributeView::class.java, NOFOLLOW_LINKS) == null ||
                Files.getPosixFilePermissions(selectedRoot, NOFOLLOW_LINKS) != supervisorPermissions("rwx------")) {
                supervisorImageProblem("process_supervisor_private_io_unavailable")
            }
            val binary = selectedRoot.resolve("supervisor")
            FileChannel.open(binary, setOf<OpenOption>(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(supervisorPermissions("rw-------"))).use { channel ->
                fileKey = supervisorAttributes(binary).fileKey()?.toString()
                    ?: supervisorImageProblem("process_supervisor_private_io_unavailable")
                val data = ByteBuffer.wrap(content)
                while (data.hasRemaining()) channel.write(data)
                channel.force(true)
            }
            Files.setPosixFilePermissions(binary, supervisorPermissions("r-x------"))
            Files.setPosixFilePermissions(selectedRoot, supervisorPermissions("r-x------"))
            return PreparedSupervisorImage(selectedRoot, binary, selectedRootKey, fileKey!!, content.size, identity).also { it.verify() }
        } catch (error: Exception) {
            if (root != null && rootKey != null) cleanupSupervisorImage(root, rootKey, fileKey)
            if (error is ExecutionProblem) throw error
            supervisorImageProblem("process_supervisor_private_io_unavailable")
        }
    }
}

/** Private per-invocation image. Call close only after the helper has actually exited. */
internal interface SupervisorImageHandle {
    val executable: Path
    val identity: OwnedSupervisorIdentity
    fun verify()
    fun closeSafely(): Boolean
}

internal class PreparedSupervisorImage internal constructor(
    internal val root: Path,
    override val executable: Path,
    private val rootKey: String,
    private val fileKey: String,
    private val byteCount: Int,
    override val identity: OwnedSupervisorIdentity,
) : SupervisorImageHandle, AutoCloseable {
    private val closed = AtomicBoolean()

    override fun verify() = supervisorImageBoundary {
        try { verifyOwnedContents() }
        catch (_: NoSuchFileException) { supervisorImageProblem("process_supervisor_image_changed") }
        catch (_: NotDirectoryException) { supervisorImageProblem("process_supervisor_image_changed") }
    }

    private fun verifyOwnedContents() {
        if (closed.get()) supervisorImageProblem("process_supervisor_image_changed")
        val directory = supervisorAttributes(root)
        if (!directory.isDirectory || directory.isSymbolicLink || directory.fileKey()?.toString() != rootKey ||
            Files.getPosixFilePermissions(root, NOFOLLOW_LINKS) != supervisorPermissions("r-x------")) {
            supervisorImageProblem("process_supervisor_image_changed")
        }
        // Establish this invocation's root before touching any child of a possible replacement.
        val binary = supervisorAttributes(executable)
        if (!binary.isRegularFile || binary.isSymbolicLink || binary.fileKey()?.toString() != fileKey || binary.size() != byteCount.toLong() ||
            Files.getPosixFilePermissions(executable, NOFOLLOW_LINKS) != supervisorPermissions("r-x------")) {
            supervisorImageProblem("process_supervisor_image_changed")
        }
        Files.newDirectoryStream(root).use { entries ->
            val iterator = entries.iterator()
            if (!iterator.hasNext() || iterator.next().fileName.toString() != "supervisor" || iterator.hasNext()) {
                supervisorImageProblem("process_supervisor_image_changed")
            }
        }
        val bytes = supervisorReadFile(executable, SupervisorBundleFormat.MAX_BINARY)
        if (bytes.size != byteCount || supervisorDigest(bytes) != identity.binaryDigest ||
            supervisorAttributes(root).fileKey()?.toString() != rootKey ||
            supervisorAttributes(executable).fileKey()?.toString() != fileKey) {
            supervisorImageProblem("process_supervisor_image_changed")
        }
    }

    override fun closeSafely(): Boolean {
        if (closed.get()) return true
        if (!cleanupSupervisorImage(root, rootKey, fileKey)) return false
        closed.set(true)
        return true
    }

    override fun close() {
        if (!closeSafely()) supervisorImageProblem("process_supervisor_image_cleanup_failed")
    }
}

/** No path, environment variable, project policy, or context classloader can install a helper. */
internal class SupervisorImageLoader(
    private val target: String = SupervisorBundleFormat.target(),
    private val resources: () -> SupervisorResources = { supervisorInstalledResources() },
) {
    fun inspect(): OwnedSupervisorAvailability = try {
        val bundle = load()
        OwnedSupervisorAvailability("available", target, bundle.identity)
    } catch (error: Exception) {
        OwnedSupervisorAvailability("unavailable", target, reasons = listOf(
            if (error is ExecutionProblem) error.code else "process_supervisor_bundle_unavailable"))
    }

    fun load(): VerifiedSupervisorBundle = supervisorImageBoundary {
        if (target !in SupervisorBundleFormat.targets) supervisorImageProblem("process_group_containment_unavailable")
        val selected = resources()
        val manifestBytes = selected.read(SupervisorBundleFormat.MANIFEST, SupervisorBundleFormat.MAX_MANIFEST)
            ?: supervisorImageProblem("process_group_containment_unavailable")
        if (manifestBytes.size > SupervisorBundleFormat.MAX_MANIFEST) supervisorImageProblem("process_supervisor_manifest_invalid")
        val text = try { supervisorUtf8(manifestBytes) } catch (_: Exception) {
            supervisorImageProblem("process_supervisor_manifest_invalid")
        }
        val lines = text.split('\n')
        if (lines.size != 5 || lines.last() != "" || lines[0] != SupervisorBundleFormat.VERSION ||
            lines[1] != target || !SupervisorBundleFormat.digest.matches(lines[2]) ||
            (lines[3] != "unavailable" && !SupervisorBundleFormat.digest.matches(lines[3]))) {
            supervisorImageProblem("process_supervisor_manifest_invalid")
        }
        if (lines[3] == "unavailable") supervisorImageProblem("process_group_containment_unavailable")
        val binary = selected.read(SupervisorBundleFormat.PREFIX + target + "/supervisor", SupervisorBundleFormat.MAX_BINARY)
            ?: supervisorImageProblem("process_group_containment_unavailable")
        if (binary.isEmpty() || binary.size > SupervisorBundleFormat.MAX_BINARY) {
            supervisorImageProblem("process_supervisor_binary_invalid")
        }
        if (supervisorDigest(binary) != lines[3]) supervisorImageProblem("process_supervisor_binary_digest_mismatch")
        // Re-read the manifest after the binary. A changing installed bundle is not a consistent image.
        val after = selected.read(SupervisorBundleFormat.MANIFEST, SupervisorBundleFormat.MAX_MANIFEST)
        if (after == null || !manifestBytes.contentEquals(after)) supervisorImageProblem("process_supervisor_bundle_changed")
        VerifiedSupervisorBundle(OwnedSupervisorIdentity(lines[0], target, lines[2], lines[3]), binary)
    }
}

/**
 * JAR resources come from the exact protection-domain JAR, never first-match classpath lookup.
 * The sole development mapping is <module>/build/classes/kotlin/main to that module's
 * build/resources/main. The caller's project/cwd, arbitrary resource roots and PATH are irrelevant.
 * This trusts a quiescent installed code source; it is not hostile-same-UID/loaded-memory attestation.
 */
internal fun supervisorInstalledResources(): SupervisorResources = supervisorImageBoundary {
    val source = OwnedProcessSupervisor::class.java.protectionDomain?.codeSource?.location
        ?: supervisorImageProblem("process_supervisor_bundle_origin_unavailable")
    if (source.protocol != "file" || source.ref != null || source.query != null) {
        supervisorImageProblem("process_supervisor_bundle_origin_unavailable")
    }
    supervisorCodeSourceResources(Path.of(URI(source.toExternalForm())))
}

internal fun supervisorCodeSourceResources(original: Path): SupervisorResources = supervisorImageBoundary {
    val absolute = original.toAbsolutePath()
    if (absolute.any { it.toString() in setOf(".", "..") }) supervisorImageProblem("process_supervisor_bundle_origin_unavailable")
    val canonical = supervisorCodeRoot(absolute)
    val observed = supervisorAttributes(canonical)
    fun checkOrigin() {
        if (supervisorCodeRoot(absolute) != canonical || supervisorStamp(supervisorAttributes(canonical)) != supervisorStamp(observed)) {
            supervisorImageProblem("process_supervisor_bundle_changed")
        }
    }
    if (observed.isRegularFile) {
        if (observed.size() > SupervisorBundleFormat.MAX_JAR) supervisorImageProblem("process_supervisor_bundle_limit")
        SupervisorResources { name, maximum ->
            supervisorResourceName(name)
            checkOrigin()
            val result = ZipFile(canonical.toFile()).use { archive ->
                if (archive.size() > SupervisorBundleFormat.MAX_JAR_ENTRIES) supervisorImageProblem("process_supervisor_bundle_limit")
                var selected: java.util.zip.ZipEntry? = null
                val entries = archive.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name == name) {
                        if (selected != null) supervisorImageProblem("process_supervisor_bundle_origin_ambiguous")
                        selected = entry
                    }
                }
                selected?.let { entry ->
                    if (entry.isDirectory || entry.size < 0 || entry.size > maximum) {
                        supervisorImageProblem("process_supervisor_bundle_limit")
                    }
                    archive.getInputStream(entry).use { stream -> supervisorReadBounded(stream, maximum) }.also {
                        if (it.size.toLong() != entry.size) supervisorImageProblem("process_supervisor_bundle_changed")
                    }
                }
            }
            checkOrigin()
            result
        }
    } else if (observed.isDirectory) {
        val suffix = listOf("build", "classes", "kotlin", "main")
        if (canonical.nameCount < suffix.size || canonical.map { it.toString() }.takeLast(suffix.size) != suffix) {
            supervisorImageProblem("process_supervisor_bundle_origin_unavailable")
        }
        val build = canonical.parent.parent.parent
        val resourceRoot = build.resolve("resources/main")
        SupervisorResources { name, maximum ->
            supervisorResourceName(name)
            checkOrigin()
            var current = build
            var missing = false
            for (component in (listOf("resources", "main") + name.split('/')).withIndex()) {
                current = current.resolve(component.value)
                if (!Files.exists(current, NOFOLLOW_LINKS)) { missing = true; break }
                val attributes = supervisorAttributes(current)
                if (attributes.isSymbolicLink ||
                    (current != resourceRoot.resolve(name) && !attributes.isDirectory)) {
                    supervisorImageProblem("process_supervisor_bundle_origin_unavailable")
                }
            }
            val result = if (missing) null else supervisorReadFile(current, maximum)
            checkOrigin()
            result
        }
    } else supervisorImageProblem("process_supervisor_bundle_origin_unavailable")
}

private fun supervisorResourceName(name: String) {
    if (name != SupervisorBundleFormat.MANIFEST && SupervisorBundleFormat.targets.none {
            name == SupervisorBundleFormat.PREFIX + it + "/supervisor"
        }) supervisorImageProblem("process_supervisor_bundle_origin_unavailable")
}

private fun supervisorCodeRoot(original: Path): Path {
    if (Files.isSymbolicLink(original)) supervisorImageProblem("process_supervisor_bundle_origin_unavailable")
    val parent = original.parent ?: supervisorImageProblem("process_supervisor_bundle_origin_unavailable")
    val canonical = parent.toRealPath().resolve(original.fileName)
    val attributes = supervisorAttributes(canonical)
    if (attributes.isSymbolicLink || (!attributes.isRegularFile && !attributes.isDirectory)) {
        supervisorImageProblem("process_supervisor_bundle_origin_unavailable")
    }
    return canonical
}

internal fun supervisorReadBounded(stream: InputStream, maximum: Int): ByteArray {
    if (maximum !in 1..SupervisorBundleFormat.MAX_BINARY) supervisorImageProblem("process_supervisor_bundle_limit")
    val output = java.io.ByteArrayOutputStream(minOf(maximum, 8192))
    val buffer = ByteArray(minOf(maximum + 1, 8192))
    var emptyReads = 0
    while (true) {
        val count = stream.read(buffer, 0, minOf(buffer.size, maximum - output.size() + 1))
        if (count < 0) break
        if (count == 0) {
            if (++emptyReads > 16) supervisorImageProblem("process_supervisor_bundle_unavailable")
            continue
        }
        emptyReads = 0
        if (count > maximum - output.size()) supervisorImageProblem("process_supervisor_bundle_limit")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun supervisorReadFile(path: Path, maximum: Int): ByteArray {
    val before = supervisorAttributes(path)
    if (!before.isRegularFile || before.isSymbolicLink || before.size() < 0 || before.size() > maximum) {
        supervisorImageProblem("process_supervisor_bundle_limit")
    }
    val result = Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { supervisorReadBounded(it, maximum) }
    if (result.size.toLong() != before.size() || supervisorStamp(supervisorAttributes(path)) != supervisorStamp(before)) {
        supervisorImageProblem("process_supervisor_bundle_changed")
    }
    return result
}

private fun supervisorAttributes(path: Path): BasicFileAttributes =
    Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)

private fun supervisorStamp(attributes: BasicFileAttributes): List<Any?> = listOf(
    attributes.fileKey(), attributes.size(), attributes.lastModifiedTime(), attributes.creationTime(),
    attributes.isRegularFile, attributes.isDirectory, attributes.isSymbolicLink,
)

internal fun supervisorDigest(bytes: ByteArray): String =
    "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

internal fun supervisorUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes)).toString()

private fun supervisorPermissions(text: String): Set<PosixFilePermission> = PosixFilePermissions.fromString(text)

/** Delete just this invocation's two known entries, never walk an unexpected replacement tree. */
private fun cleanupSupervisorImage(root: Path, expectedRoot: String, expectedFile: String?): Boolean = try {
    val directory = supervisorAttributes(root)
    if (!directory.isDirectory || directory.isSymbolicLink || directory.fileKey()?.toString() != expectedRoot) false
    else {
        Files.setPosixFilePermissions(root, supervisorPermissions("rwx------"))
        val binary = root.resolve("supervisor")
        if (Files.exists(binary, NOFOLLOW_LINKS)) {
            val attributes = supervisorAttributes(binary)
            if (expectedFile == null || !attributes.isRegularFile || attributes.isSymbolicLink ||
                attributes.fileKey()?.toString() != expectedFile) false
            else { Files.delete(binary); Files.delete(root); true }
        } else { Files.delete(root); true }
    }
} catch (_: Exception) { false }

private inline fun <T> supervisorImageBoundary(action: () -> T): T = try { action() }
catch (error: ExecutionProblem) { throw error }
catch (_: Exception) { supervisorImageProblem("process_supervisor_bundle_unavailable") }

private fun supervisorImageProblem(code: String): Nothing =
    throw ExecutionProblem(code, "The installed owned-child supervisor image is unavailable or changed.")

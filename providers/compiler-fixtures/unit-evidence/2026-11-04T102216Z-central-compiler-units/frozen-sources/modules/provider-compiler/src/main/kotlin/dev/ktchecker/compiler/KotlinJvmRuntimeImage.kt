package dev.ktchecker.compiler

import dev.ktchecker.contracts.Digests
import dev.ktchecker.providers.runtime.ExecutionProblem
import dev.ktchecker.providers.runtime.strictUtf8
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.*

data class KotlinJvmRuntimeLimits(
    val maxEntries: Int = 20_000,
    val maxDepth: Int = 64,
    val maxNameBytes: Int = 1_024,
    val maxTotalNameBytes: Long = 4_194_304,
    val maxFileBytes: Long = 536_870_912,
    val maxTotalBytes: Long = 1_073_741_824,
    val maxScanMillis: Long = 60_000,
) {
    init {
        require(maxEntries in 1..100_000 && maxDepth in 1..128 && maxNameBytes in 1..4_096)
        require(maxTotalNameBytes in 1L..67_108_864L && maxFileBytes in 1L..1_073_741_824L &&
            maxTotalBytes in 1L..4_294_967_296L && maxScanMillis in 1L..60_000L)
    }
}

class KotlinJvmRuntimeException(val code: String, message: String) : IllegalArgumentException(message)

/**
 * Read-only content identity of one explicitly selected, link-free JDK21 home.
 * The whole directory is included, including empty directories and executable policy.
 * This detects ordinary disk drift, not already-loaded code, transient fully restored
 * writes between checks, OS/JNI dependencies outside the image, or an adversarial
 * same-UID runtime. No fallback
 * claims an ordinary linked system JDK is completely identified by its launcher.
 */
class KotlinJvmRuntimeImage private constructor(
    val root: Path,
    val javaExecutable: Path,
    private val selectedRoot: Path,
    private val image: Image,
    private val limits: KotlinJvmRuntimeLimits,
) {
    private data class Entry(val path: String, val kind: String, val size: Long,
        val digest: String?, val executable: Boolean, val executableBits: Int?)
    private data class Layout(val javaVersion: String, val osName: String, val architecture: String, val javaName: String)
    private data class Image(val layout: Layout, val entries: List<Entry>, val byteCount: Long, val digest: String)
    private data class Stamp(val regular: Boolean, val directory: Boolean, val size: Long,
        val modified: FileTime, val key: Any?, val executable: Boolean, val executableBits: Int?)
    private data class Read(val digest: String, val bytes: ByteArray?, val stamp: Stamp)

    val digest: String get() = image.digest
    val entryCount: Int get() = image.entries.size
    val fileCount: Int get() = image.entries.count { it.kind == "file" }
    val byteCount: Long get() = image.byteCount
    val executableDigest: String get() = image.entries.single { it.path == "bin/${image.layout.javaName}" }.digest!!

    fun description(): Map<String, Any?> = mapOf(
        "schema" to "ktchecker/jdk-runtime-image-1", "root" to root.toString(), "digest" to digest,
        "java_version" to image.layout.javaVersion, "os_name" to image.layout.osName,
        "architecture" to image.layout.architecture, "entry_count" to entryCount,
        "file_count" to fileCount, "byte_count" to byteCount,
        "entries" to image.entries.map { entry -> mapOf("path" to entry.path, "kind" to entry.kind,
            "size" to entry.size, "sha256" to entry.digest, "executable" to entry.executable,
            "executable_bits" to entry.executableBits) },
        "bounds" to mapOf("max_entries" to limits.maxEntries, "max_depth" to limits.maxDepth,
            "max_name_bytes" to limits.maxNameBytes, "max_total_name_bytes" to limits.maxTotalNameBytes,
            "max_file_bytes" to limits.maxFileBytes, "max_total_bytes" to limits.maxTotalBytes,
            "max_scan_millis" to limits.maxScanMillis),
        "scope" to "Whole selected link-free JDK home; file bytes, entry names, empty directories and executable policy.",
        "limitations" to listOf("Trusted quiescent installation required; this is not same-UID sandbox attestation.",
            "Disk identity does not attest loaded/transformed runtime code or dependencies outside the image.",
            "Cooperative pre/post checks cannot detect transient fully restored writes between checks.",
            "Normal linked system JDKs are unsupported; explicit owner preparation/selection is required, with no launcher-only fallback."),
    )

    fun verify(checkpoint: () -> Unit = {}) = runtimeBoundary {
        val current = scan(root, selectedRoot, image.layout.javaName, limits, checkpoint)
        if (current != image) runtimeFailure("changed", "Selected Java runtime image changed after capture.")
    }

    companion object {
        fun capture(javaExecutable: Path, limits: KotlinJvmRuntimeLimits = KotlinJvmRuntimeLimits(),
            checkpoint: () -> Unit = {}): KotlinJvmRuntimeImage = runtimeBoundary {
            checkpoint()
            if (!javaExecutable.isAbsolute || javaExecutable.normalize() != javaExecutable ||
                javaExecutable.any { it.toString() in setOf(".", "..") }) {
                runtimeFailure("unsupported", "Java runtime selection must be absolute without dot or parent traversal.")
            }
            val selected = javaExecutable
            if (selected.parent?.fileName?.toString() != "bin" || selected.fileName.toString() !in setOf("java", "java.exe")) {
                runtimeFailure("unsupported", "Select a supported JDK home bin/java executable explicitly.")
            }
            val selectedRoot = selected.parent.parent ?: runtimeFailure("unsupported", "Select an ordinary JDK home explicitly.")
            // Only aliases above the explicit outer home are canonicalized once.
            val root = canonicalSelectedHome(selectedRoot)
            val executable = root.resolve("bin").resolve(selected.fileName)
            val image = scan(root, selectedRoot, selected.fileName.toString(), limits, checkpoint)
            KotlinJvmRuntimeImage(root, executable, selectedRoot, image, limits)
        }

        private fun scan(root: Path, selectedRoot: Path, javaName: String, limits: KotlinJvmRuntimeLimits,
            checkpoint: () -> Unit): Image {
            val started = System.nanoTime()
            fun checkClock() {
                checkpoint()
                if (System.nanoTime() - started >= limits.maxScanMillis * 1_000_000) {
                    runtimeFailure("limit", "Java runtime inventory exceeded its scan deadline.")
                }
            }
            checkClock()
            if (canonicalSelectedHome(selectedRoot) != root) {
                runtimeFailure("changed", "Selected Java runtime outer alias changed after capture.")
            }
            val release = read(root.resolve("release"), minOf(limits.maxFileBytes, 65_536), true, ::checkClock)
            val releaseText = try { strictUtf8(release.bytes!!) } catch (_: CharacterCodingException) {
                runtimeFailure("unsupported", "Java runtime release descriptor is not UTF-8.")
            }
            val layout = layout(releaseText, javaName)
            val vm = when (layout.osName) {
                "Linux" -> "lib/server/libjvm.so"
                "Darwin" -> "lib/server/libjvm.dylib"
                else -> "bin/server/jvm.dll"
            }
            for (relative in listOf("bin/$javaName", "lib/modules", "lib/jvm.cfg", "conf/security/java.security", vm)) {
                checkClock()
                val attributes = stamp(root.resolve(relative))
                if (!attributes.regular || attributes.size == 0L) runtimeFailure("unsupported", "Selected JDK layout is incomplete or unsupported.")
            }
            if (!Files.isExecutable(root.resolve("bin/$javaName"))) runtimeFailure("unsupported", "Selected Java launcher is not executable.")
            val entries = mutableListOf<Entry>()
            val stamps = linkedMapOf<Path, Stamp>()
            var byteCount = 0L
            var nameBytes = 0L
            fun add(path: Path): Pair<String, Stamp> {
                checkClock()
                if (entries.size >= limits.maxEntries) runtimeFailure("limit", "Java runtime inventory exceeded its entry bound.")
                val relative = if (path == root) "" else root.relativize(path).toString().replace(java.io.File.separatorChar, '/')
                val depth = if (path == root) 0 else root.relativize(path).nameCount
                if (depth > limits.maxDepth || relative.length > limits.maxNameBytes) runtimeFailure("limit", "Java runtime entry exceeds path bounds.")
                if (relative.any { it.code < 32 || it == '\\' } || path.toAbsolutePath().normalize() != path ||
                    !path.startsWith(root) || root.resolve(relative) != path) {
                    runtimeFailure("unsupported", "Java runtime entry has an unsupported name.")
                }
                val encoded = relative.toByteArray(Charsets.UTF_8)
                nameBytes += encoded.size
                if (encoded.size > limits.maxNameBytes || nameBytes > limits.maxTotalNameBytes) runtimeFailure("limit", "Java runtime names exceed byte bounds.")
                val attributes = stamp(path)
                stamps[path] = attributes
                return relative to attributes
            }
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val (relative, attributes) = add(dir)
                    if (!attributes.directory) runtimeFailure("unsupported", "Unsupported Java runtime directory.")
                    entries += Entry(relative, "directory", 0, null, attributes.executable, attributes.executableBits)
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val (relative, attributes) = add(file)
                    if (!attributes.regular) runtimeFailure("unsupported", "Java runtime contains a link, junction, or special entry.")
                    if (attributes.size > limits.maxFileBytes) runtimeFailure("limit", "Java runtime file exceeds its byte bound.")
                    byteCount += attributes.size
                    if (byteCount > limits.maxTotalBytes) runtimeFailure("limit", "Java runtime inventory exceeds its total byte bound.")
                    val actual = read(file, limits.maxFileBytes, false, ::checkClock)
                    if (actual.stamp != attributes) runtimeFailure("changed", "Java runtime entry changed while inventorying.")
                    entries += Entry(relative, "file", attributes.size, actual.digest, attributes.executable, attributes.executableBits)
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = throw exc
            })
            for ((path, before) in stamps) {
                checkClock()
                if (stamp(path) != before) runtimeFailure("changed", "Java runtime inventory changed while reading.")
            }
            if (entries.singleOrNull { it.path == "release" }?.digest != release.digest ||
                entries.map { it.path }.toSet().size != entries.size) {
                runtimeFailure("changed", "Java runtime release descriptor or entry names changed while inventorying.")
            }
            checkClock()
            if (canonicalSelectedHome(selectedRoot) != root) {
                runtimeFailure("changed", "Selected Java runtime alias changed during inventory.")
            }
            val sorted = Collections.unmodifiableList(entries.sortedBy { it.path })
            val identity = buildJsonObject {
                put("schema", "ktchecker/jdk-runtime-image-1")
                put("java_version", layout.javaVersion); put("os_name", layout.osName); put("architecture", layout.architecture)
                put("entries", JsonArray(sorted.map { entry -> buildJsonObject {
                    put("path", entry.path); put("kind", entry.kind); put("size", entry.size)
                    put("sha256", entry.digest?.let(::JsonPrimitive) ?: JsonNull)
                    put("executable", entry.executable); put("executable_bits", entry.executableBits?.let(::JsonPrimitive) ?: JsonNull)
                } }))
            }
            return Image(layout, sorted, byteCount, Digests.sha256(Digests.canonical(identity)))
        }

        private fun layout(release: String, javaName: String): Layout {
            fun field(name: String): String {
                val lines = release.lineSequence().map { it.removeSuffix("\r") }.filter { it.startsWith("$name=") }.toList()
                return lines.singleOrNull()?.let { Regex("$name=\"([^\"\\r\\n]+)\"").matchEntire(it)?.groupValues?.get(1) }
                    ?: runtimeFailure("unsupported", "Unsupported Java runtime release descriptor.")
            }
            val version = field("JAVA_VERSION")
            val os = field("OS_NAME")
            val architecture = field("OS_ARCH")
            if (!version.matches(Regex("21(?:\\.[0-9]+)*(?:[+-][A-Za-z0-9._+-]+)?")) ||
                os !in setOf("Linux", "Darwin", "Windows") || architecture !in setOf("x86_64", "aarch64", "amd64") ||
                javaName != (if (os == "Windows") "java.exe" else "java")) {
                runtimeFailure("unsupported", "Only explicitly pinned supported modular JDK21 layouts are accepted.")
            }
            return Layout(version, os, architecture, javaName)
        }

        private fun canonicalSelectedHome(selectedRoot: Path): Path {
            val attributes = Files.readAttributes(selectedRoot, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!attributes.isDirectory || attributes.isSymbolicLink || attributes.isOther) {
                runtimeFailure("unsupported", "Selected Java runtime home is not an ordinary directory.")
            }
            val parent = selectedRoot.parent ?: runtimeFailure("unsupported", "The filesystem root is not a supported JDK home.")
            val expected = parent.toRealPath().resolve(selectedRoot.fileName)
            val actual = selectedRoot.toRealPath()
            // A junction/reparse-point leaf must not be accepted just because its
            // platform attributes happen to call it a directory rather than a link.
            if (actual != expected) runtimeFailure("unsupported", "Linked Java runtime home leaves are unsupported.")
            return actual
        }

        private fun stamp(path: Path): Stamp {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (attributes.isSymbolicLink || attributes.isOther || (!attributes.isRegularFile && !attributes.isDirectory) || path.toRealPath() != path) {
                runtimeFailure("unsupported", "Java runtime contains a symlink, junction, or special entry.")
            }
            if (!Files.isReadable(path) || attributes.size() < 0) runtimeFailure("unreadable", "Java runtime entry is unreadable.")
            val permissions = if (path.fileSystem.supportedFileAttributeViews().contains("posix")) Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) else null
            val bits = permissions?.let { set ->
                (if (PosixFilePermission.OWNER_EXECUTE in set) 4 else 0) +
                    (if (PosixFilePermission.GROUP_EXECUTE in set) 2 else 0) + (if (PosixFilePermission.OTHERS_EXECUTE in set) 1 else 0)
            }
            return Stamp(attributes.isRegularFile, attributes.isDirectory, attributes.size(), attributes.lastModifiedTime(),
                attributes.fileKey(), Files.isExecutable(path), bits)
        }

        private fun read(path: Path, maximum: Long, preserveBytes: Boolean, checkpoint: () -> Unit): Read {
            checkpoint()
            val before = stamp(path)
            if (!before.regular) runtimeFailure("unsupported", "Java runtime requires an ordinary regular file.")
            if (before.size > maximum) runtimeFailure("limit", "Java runtime file exceeds its byte bound.")
            val hash = MessageDigest.getInstance("SHA-256")
            val copy = if (preserveBytes) ByteArrayOutputStream(before.size.toInt()) else null
            var size = 0L
            Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(65_536)
                while (true) {
                    checkpoint()
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > before.size || size > maximum) runtimeFailure("changed", "Java runtime file grew while reading.")
                    hash.update(buffer, 0, count)
                    copy?.write(buffer, 0, count)
                }
            }
            val after = stamp(path)
            if (size != before.size || after != before) runtimeFailure("changed", "Java runtime file changed while reading.")
            val digest = "sha256:" + hash.digest().joinToString("") { "%02x".format(it) }
            return Read(digest, copy?.toByteArray(), before)
        }
    }
}

private fun runtimeFailure(suffix: String, message: String): Nothing = throw KotlinJvmRuntimeException("compiler_java_runtime_$suffix", message)

private inline fun <T> runtimeBoundary(action: () -> T): T = try {
    action()
} catch (failure: KotlinJvmRuntimeException) {
    throw failure
} catch (failure: CancellationException) {
    throw failure
} catch (failure: ExecutionProblem) {
    throw failure
} catch (_: IOException) {
    runtimeFailure("unreadable", "Selected Java runtime image is missing, unreadable, or unsafe.")
} catch (_: SecurityException) {
    runtimeFailure("unreadable", "Selected Java runtime image is not readable.")
} catch (_: UnsupportedOperationException) {
    runtimeFailure("unsupported", "This host cannot inventory the selected Java runtime without following links.")
}

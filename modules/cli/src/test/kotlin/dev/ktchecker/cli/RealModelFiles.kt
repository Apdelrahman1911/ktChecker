package dev.ktchecker.cli

import dev.ktchecker.contracts.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlinx.serialization.encodeToString

/** Test-owner source support only. No path/digest DTO confers execution or ingress authority. */
internal class RealModelProblem(val code: String) : IllegalStateException(code)
internal fun realExpect(value: Boolean, code: String) { if (!value) throw RealModelProblem(code) }
internal fun realDigest(value: String) = realExpect(Digests.valid(value), "real_model_digest_invalid")
internal fun realPath(value: String): Path = Path.of(value).also {
    realExpect(it.isAbsolute && it.normalize() == it && it.nameCount > 0 && value.length <= 4096 &&
        value.none { c -> c.code < 32 || c == '\\' }, "real_model_path_invalid")
}
internal fun realLogical(value: String) {
    realExpect(value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size <= 4096 &&
        value.none { it.code < 32 || it == '\\' } && !Path.of(value).isAbsolute &&
        value.split('/').none { it in setOf("", ".", "..") }, "real_model_logical_path_invalid")
}
private fun realSame(before: BasicFileAttributes, after: BasicFileAttributes): Boolean =
    before.fileKey() != null && before.fileKey() == after.fileKey() && before.size() == after.size() &&
        before.lastModifiedTime() == after.lastModifiedTime() && before.isRegularFile == after.isRegularFile &&
        before.isDirectory == after.isDirectory && !after.isSymbolicLink

/** Every parent and leaf is anchored no-follow; no source byte allocation before the size bound. */
internal fun <T> realAnchoredDirectory(path: Path, action: (SecureDirectoryStream<Path>) -> T): T {
    realPath(path.toString())
    Files.newDirectoryStream(path.root).use { stream ->
        val root = stream as? SecureDirectoryStream<Path> ?: throw RealModelProblem("real_model_secure_io_unavailable")
        fun descend(parent: SecureDirectoryStream<Path>, index: Int): T {
            if (index == path.nameCount) return action(parent)
            val name = path.getName(index)
            val view = parent.getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS)
            val before = view.readAttributes()
            realExpect(before.isDirectory && !before.isSymbolicLink && before.fileKey() != null, "real_model_parent_unsafe")
            return parent.newDirectoryStream(name, NOFOLLOW_LINKS).use { child ->
                realExpect(child.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes().fileKey() == before.fileKey(),
                    "real_model_parent_changed")
                descend(child, index + 1).also {
                    val after = view.readAttributes()
                    realExpect(after.isDirectory && !after.isSymbolicLink && after.fileKey() == before.fileKey(), "real_model_parent_changed")
                }
            }
        }
        return descend(root, 0)
    }
}
internal fun realKey(path: Path, directory: Boolean = false): String = realAnchoredDirectory(path.parent) { parent ->
    val before = parent.getFileAttributeView(path.fileName, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()
    realExpect(!before.isSymbolicLink && (if (directory) before.isDirectory else before.isRegularFile) && before.fileKey() != null,
        "real_model_file_identity_unavailable")
    if (!directory) realExpect((Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as? Number)?.toLong() == 1L,
        "real_model_file_alias_unqualified")
    realExpect(realSame(before, Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)), "real_model_file_changed")
    before.fileKey().toString()
}
private fun <T> realReadChannel(path: Path, limit: Long, expectedSize: Long?, action: (java.nio.channels.SeekableByteChannel, Long) -> T): T =
    realAnchoredDirectory(path.parent) { directory ->
        realExpect(limit in 1..512L * 1024 * 1024 && (expectedSize == null || expectedSize in 0..limit), "real_model_input_bound")
        val view = directory.getFileAttributeView(path.fileName, BasicFileAttributeView::class.java, NOFOLLOW_LINKS)
        val before = view.readAttributes()
        realExpect(before.isRegularFile && !before.isSymbolicLink && before.fileKey() != null, "real_model_input_unsafe")
        realExpect(before.size() in 0..limit && (expectedSize == null || before.size() == expectedSize), "real_model_input_bound")
        directory.newByteChannel(path.fileName, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
            realExpect(channel.size() == before.size(), "real_model_input_changed")
            action(channel, before.size()).also {
                realExpect(channel.size() == before.size() && realSame(before, view.readAttributes()), "real_model_input_changed")
            }
        }
    }
internal fun realRead(path: Path, limit: Int, expectedSize: Long? = null): ByteArray {
    realExpect(limit in 1..32 * 1024 * 1024, "real_model_input_bound")
    return realReadChannel(path, limit.toLong(), expectedSize) { channel, size ->
        val bytes = ByteArrayOutputStream(size.toInt())
        val buffer = ByteBuffer.allocate(minOf(65536, maxOf(1, size.toInt())))
        var count = 0L; var stalled = 0
        while (true) {
            buffer.clear(); val amount = channel.read(buffer)
            if (amount < 0) break
            if (amount == 0) { realExpect(++stalled <= 16, "real_model_input_stalled"); continue }
            stalled = 0; count += amount
            realExpect(count <= size, "real_model_input_changed")
            bytes.write(buffer.array(), 0, amount)
        }
        realExpect(count == size, "real_model_input_changed")
        bytes.toByteArray()
    }
}
internal fun realHashFile(path: Path, limit: Long, expectedSize: Long? = null): String =
    realReadChannel(path, limit, expectedSize) { channel, size ->
        val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteBuffer.allocate(65536)
        var count = 0L; var stalled = 0
        while (true) {
            buffer.clear(); val amount = channel.read(buffer)
            if (amount < 0) break
            if (amount == 0) { realExpect(++stalled <= 16, "real_model_input_stalled"); continue }
            stalled = 0; count += amount
            realExpect(count <= size, "real_model_input_changed")
            buffer.flip(); hash.update(buffer)
        }
        realExpect(count == size, "real_model_input_changed")
        "sha256:" + java.util.HexFormat.of().formatHex(hash.digest())
    }
internal fun realUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
internal fun realDirectory(path: Path) {
    realAnchoredDirectory(path.parent) {
        Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        realKey(path, true)
    }
}
internal fun realWrite(path: Path, bytes: ByteArray, limit: Int = 16 * 1024 * 1024) {
    realExpect(bytes.size <= limit, "real_model_evidence_bound")
    realAnchoredDirectory(path.parent) { directory ->
        directory.newByteChannel(path.fileName, setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
            PosixFilePermissions.asFileAttribute(setOf(PosixFilePermission.OWNER_READ))).use { channel ->
            val buffer = ByteBuffer.wrap(bytes); var stalled = 0
            while (buffer.hasRemaining()) {
                if (channel.write(buffer) == 0) realExpect(++stalled <= 16, "real_model_write_stalled") else stalled = 0
            }
        }
    }
    realExpect(realHashFile(path, maxOf(1, limit).toLong(), bytes.size.toLong()) == Digests.sha256(bytes), "real_model_written_bytes_changed")
}
internal inline fun <reified T> realWriteJson(path: Path, value: T) =
    realWrite(path, (ContractJson.encodeToString(value) + "\n").toByteArray(Charsets.UTF_8))

internal data class RealModelFilePin(val path: Path, val digest: String, val bytes: Long, val fileKey: String) {
    fun verify() {
        realExpect(realKey(path) == fileKey && realHashFile(path, 512L * 1024 * 1024, bytes) == digest, "real_model_pinned_file_changed")
    }
    companion object {
        fun capture(path: Path, digest: String, bytes: Long): RealModelFilePin {
            realDigest(digest)
            val key = realKey(path)
            return RealModelFilePin(path, digest, bytes, key).also { it.verify() }
        }
    }
}
internal fun realCopy(source: RealModelFilePin, target: Path): RealModelFilePin {
    source.verify()
    realAnchoredDirectory(target.parent) { destination ->
        realReadChannel(source.path, 512L * 1024 * 1024, source.bytes) { from, size ->
            destination.newByteChannel(target.fileName, setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(setOf(PosixFilePermission.OWNER_READ))).use { to ->
                val buffer = ByteBuffer.allocate(65536); var count = 0L; var stalled = 0
                while (true) {
                    buffer.clear(); val amount = from.read(buffer)
                    if (amount < 0) break
                    if (amount == 0) { realExpect(++stalled <= 16, "real_model_copy_stalled"); continue }
                    stalled = 0; count += amount; realExpect(count <= size, "real_model_copy_changed")
                    buffer.flip()
                    while (buffer.hasRemaining()) {
                        if (to.write(buffer) == 0) realExpect(++stalled <= 16, "real_model_copy_stalled") else stalled = 0
                    }
                }
                realExpect(count == size && to.size() == size, "real_model_copy_changed")
            }
        }
    }
    source.verify()
    return RealModelFilePin.capture(target, source.digest, source.bytes).also {
        realExpect(it.fileKey != source.fileKey, "real_model_copy_not_independent")
    }
}

internal data class RealModelTreeBoundary(val entries: Int, val bytes: Long)
/** Boundary checks only, NOT a continuous filesystem quota or subprocess measurement. */
internal fun realTreeBoundary(root: Path, entryLimit: Int = 40_000, byteLimit: Long = 1024L * 1024 * 1024): RealModelTreeBoundary {
    val rootKey = realKey(root, true); var entries = 0; var bytes = 0L
    Files.walk(root).use { paths -> paths.forEach { path ->
        realExpect(++entries <= entryLimit && !Files.isSymbolicLink(path), "real_model_run_entry_bound_or_alias")
        if (path != root) realLogical(root.relativize(path).toString())
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        realExpect(attributes.isDirectory || attributes.isRegularFile, "real_model_run_nonregular")
        if (attributes.isRegularFile) {
            realKey(path); bytes += attributes.size()
            realExpect(bytes <= byteLimit, "real_model_run_byte_boundary_exceeded")
        }
    } }
    realExpect(realKey(root, true) == rootKey, "real_model_run_root_changed")
    return RealModelTreeBoundary(entries, bytes)
}
internal fun realRegularFiles(root: Path): List<Path> {
    realTreeBoundary(root, 40_000, 1024L * 1024 * 1024)
    return Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.sorted().toList() }
}

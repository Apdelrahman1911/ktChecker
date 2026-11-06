package dev.ktchecker.providers.runtime

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.Collections

/** Anchored raw output reads only. No launch, model, extraction, or artifact authority is issued. */
internal fun readOutputReports(root: Path?, outputIdentity: String?, relativeDirectory: String,
                               maxFiles: Int, maxFileBytes: Int, maxTotalBytes: Int): Map<String, ByteArray> {
    reportCollectionLimits(maxFiles, maxFileBytes, maxTotalBytes)
    if (relativeDirectory.length !in 1..4096 || relativeDirectory.any(Char::isISOControl) ||
        !reportCollectionWellFormed(relativeDirectory)) {
        throw ExecutionProblem("relative_path_invalid", "Expected a bounded normalized report subdirectory")
    }
    val parts = try { safeRelativePath(relativeDirectory).iterator().asSequence().toList() }
        catch (problem: ExecutionProblem) { throw problem }
        catch (_: Exception) { throw ExecutionProblem("relative_path_invalid", "Unrepresentable report subdirectory") }
    if (parts.size !in 1..64 || parts.any { !reportCollectionName(it) }) {
        throw ExecutionProblem("relative_path_invalid", "Expected a bounded normalized report subdirectory")
    }
    val selectedRoot = root ?: throw ExecutionProblem("report_unavailable", "No executed output directory")
    try {
        reportCollectionCheckpoint()
        if (!Files.isDirectory(selectedRoot, NOFOLLOW_LINKS)) {
            throw ExecutionProblem("report_root_invalid", "Output root was replaced")
        }
        Files.newDirectoryStream(selectedRoot).use { stream ->
            val directory = stream as? SecureDirectoryStream<Path>
                ?: throw ExecutionProblem("secure_report_io_unavailable", "No anchored no-follow report reader on this host")
            fun verifyRoot() {
                val opened = directory.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes()
                val named = Files.readAttributes(selectedRoot, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (outputIdentity == null || !opened.isDirectory || !named.isDirectory ||
                    opened.fileKey()?.toString() != outputIdentity || named.fileKey()?.toString() != outputIdentity) {
                    throw ExecutionProblem("report_root_replaced", "Report root differs from the created isolated output directory")
                }
            }
            verifyRoot()
            val reports = descendReportCollection(directory, parts, 0, maxFiles, maxFileBytes, maxTotalBytes)
            verifyRoot()
            reportCollectionCheckpoint()
            return reports
        }
    } catch (problem: ExecutionProblem) { throw problem }
    catch (_: Exception) {
        throw ExecutionProblem("report_collection_missing_or_unsafe", "Report collection is absent, changed or unsafe")
    }
}

private fun descendReportCollection(parent: SecureDirectoryStream<Path>, parts: List<Path>, offset: Int,
                                    maxFiles: Int, maxFileBytes: Int, maxTotalBytes: Int): Map<String, ByteArray> {
    reportCollectionCheckpoint()
    val leaf = parts[offset]
    fun attributes() = parent.getFileAttributeView(leaf, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()
    val before = attributes()
    val key = before.fileKey()?.toString()
    if (!before.isDirectory || key == null) {
        throw ExecutionProblem("report_collection_directory_invalid", "Reports require an actual bounded no-follow directory")
    }
    parent.newDirectoryStream(leaf, NOFOLLOW_LINKS).use { child ->
        fun verifyDirectory() {
            val opened = child.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes()
            val named = attributes()
            if (!opened.isDirectory || !named.isDirectory || opened.fileKey()?.toString() != key || named.fileKey()?.toString() != key) {
                throw ExecutionProblem("report_collection_changed", "Report directory identity changed")
            }
        }
        verifyDirectory()
        val reports = if (offset + 1 == parts.size) collectAnchoredReports(child, maxFiles, maxFileBytes, maxTotalBytes)
            else descendReportCollection(child, parts, offset + 1, maxFiles, maxFileBytes, maxTotalBytes)
        verifyDirectory()
        return reports
    }
}

/**
 * Internal data-reader seam for deterministic filesystem controls. The caller owns the anchor.
 * Before/after inventories detect ordinary drift, not hostile same-UID ABA or a coherent freeze.
 * Every candidate is counted/read or rejected, including files a consumer does not recognize.
 */
internal fun collectAnchoredReports(directory: SecureDirectoryStream<Path>, maxFiles: Int,
                                    maxFileBytes: Int, maxTotalBytes: Int): Map<String, ByteArray> {
    reportCollectionLimits(maxFiles, maxFileBytes, maxTotalBytes)
    reportCollectionCheckpoint()
    val inventory = reportCollectionInventory(directory, maxFiles, maxFileBytes, maxTotalBytes)
    val result = linkedMapOf<String, ByteArray>()
    inventory.forEach { (name, member) ->
        reportCollectionCheckpoint()
        if (reportCollectionMember(directory, member.path, maxFileBytes) != member) reportCollectionChanged()
        // The entire declared size sum was bounded BEFORE allocating or reading any file.
        directory.newByteChannel(member.path, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
            if (channel.size() != member.size.toLong()) reportCollectionChanged()
            val bytes = ByteArray(member.size)
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                reportCollectionCheckpoint()
                if (channel.read(buffer) <= 0) reportCollectionChanged()
            }
            reportCollectionCheckpoint()
            if (channel.read(ByteBuffer.allocate(1)) != -1 || channel.size() != member.size.toLong() ||
                reportCollectionMember(directory, member.path, maxFileBytes) != member) reportCollectionChanged()
            result[name] = bytes
        }
    }
    // A DirectoryStream is single-iteration; each inventory uses a NEW anchored '.' stream.
    if (reportCollectionInventory(directory, maxFiles, maxFileBytes, maxTotalBytes) != inventory) reportCollectionChanged()
    reportCollectionCheckpoint()
    return Collections.unmodifiableMap(result)
}

private data class ReportCollectionMember(val path: Path, val key: String, val size: Int,
                                         val modified: FileTime, val created: FileTime)

private fun reportCollectionInventory(directory: SecureDirectoryStream<Path>, maxFiles: Int,
                                      maxFileBytes: Int, maxTotalBytes: Int): Map<String, ReportCollectionMember> {
    val entries = sortedMapOf<String, ReportCollectionMember>()
    var total = 0L
    directory.newDirectoryStream(Path.of("."), NOFOLLOW_LINKS).use { listing ->
        for (entry in listing) {
            reportCollectionCheckpoint()
            if (entries.size >= maxFiles) reportCollectionOverflow()
            val leaf = entry.fileName
            if (leaf == null || !reportCollectionName(leaf)) {
                throw ExecutionProblem("report_collection_member_invalid", "Report filename is unsafe or unrepresentable")
            }
            val member = reportCollectionMember(listing, leaf, maxFileBytes)
            total += member.size.toLong()
            if (total > maxTotalBytes || entries.put(leaf.toString(), member) != null) reportCollectionOverflow()
        }
    }
    return entries
}

private fun reportCollectionMember(directory: SecureDirectoryStream<Path>, leaf: Path, maxFileBytes: Int): ReportCollectionMember {
    val attrs = directory.getFileAttributeView(leaf, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()
    val key = attrs.fileKey()?.toString()
    if (!attrs.isRegularFile || key == null) {
        throw ExecutionProblem("report_collection_member_invalid", "Report member is not an identifiable no-follow regular file")
    }
    if (attrs.size() !in 0L..maxFileBytes.toLong()) {
        throw ExecutionProblem("report_not_regular_or_oversized", "Report is not a bounded regular file")
    }
    return ReportCollectionMember(leaf, key, attrs.size().toInt(), attrs.lastModifiedTime(), attrs.creationTime())
}

private fun reportCollectionName(path: Path): Boolean {
    val name = path.toString()
    if (path.isAbsolute || path.nameCount != 1 || name.length !in 1..255 || name.isBlank() || name in setOf(".", "..") ||
        name.any { it.isISOControl() || it in "\\/:" }) return false
    if (!reportCollectionWellFormed(name)) return false
    // Reject lossy filename decoding rather than reading a different replacement-character name.
    return path.fileSystem.getPath(name) == path
}

private fun reportCollectionWellFormed(value: String): Boolean {
    var i = 0
    while (i < value.length) {
        val c = value[i++]
        if (c.isHighSurrogate()) {
            if (i == value.length || !value[i++].isLowSurrogate()) return false
        } else if (c.isLowSurrogate()) return false
    }
    return true
}

private fun reportCollectionLimits(maxFiles: Int, maxFileBytes: Int, maxTotalBytes: Int) {
    if (maxFiles !in 1..1024 || maxFileBytes !in 1..16_777_216 || maxTotalBytes !in 1..16_777_216) reportCollectionOverflow()
}
private fun reportCollectionOverflow(): Nothing = throw ExecutionProblem("report_collection_limit", "Report collection exceeds its bound")
private fun reportCollectionChanged(): Nothing = throw ExecutionProblem("report_collection_changed", "Report collection changed while being read")
private fun reportCollectionCheckpoint() {
    if (Thread.currentThread().isInterrupted) throw ExecutionProblem("report_collection_interrupted", "Report collection was interrupted")
}

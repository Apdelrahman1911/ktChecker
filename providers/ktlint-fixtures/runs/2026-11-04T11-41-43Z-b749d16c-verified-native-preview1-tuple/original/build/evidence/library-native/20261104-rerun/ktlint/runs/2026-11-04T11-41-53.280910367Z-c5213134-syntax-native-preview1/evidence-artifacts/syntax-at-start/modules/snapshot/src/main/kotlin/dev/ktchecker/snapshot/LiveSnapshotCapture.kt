package dev.ktchecker.snapshot

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.time.Clock

internal data class CapturedInputs(val entries: List<SnapshotEntry>, val blobs: List<ImmutableBlob>)

/** Hash/attribute checks detect races; only the caller's all-writer lease can establish coherence. */
internal class LiveSnapshotCapture(private val limits: SnapshotLimits, private val clock: Clock) {
    fun capture(roots: List<SnapshotRoot>, scope: SnapshotScope, deadlineMillis: Long): CapturedInputs {
        val entries = mutableListOf<SnapshotEntry>()
        val blobs = mutableMapOf<String, ImmutableBlob>()
        var capturedBytes = 0L
        var directories = 0L
        fun checkDeadline() {
            if (clock.millis() >= deadlineMillis || Thread.currentThread().isInterrupted) {
                throw SnapshotException("capture_deadline_exceeded", "Capture exceeded its lease or bounded time budget")
            }
        }
        roots.forEach { root ->
            checkDeadline()
            val live = root.livePath?.let(Path::of)
                ?: throw SnapshotException("live_root_unavailable", "A declared input root has no live capture path")
            if (!Files.isDirectory(live, NOFOLLOW_LINKS) || live.toRealPath() != live) {
                throw SnapshotException("live_root_changed", "Registered live root no longer has its original real path")
            }
            val beforeRoot = Files.readAttributes(live, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            val exclusions = scope.exclusions.filter { it.rootId == root.id }
            fun relative(file: Path): String = live.relativize(file).toString().replace(File.separatorChar, '/')
            fun excluded(relative: String): Boolean = exclusions.any { relative == it.path || relative.startsWith(it.path + "/") }
            Files.walkFileTree(live, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    checkDeadline()
                    if (dir != live) {
                        val path = relative(dir)
                        if (excluded(path)) return FileVisitResult.SKIP_SUBTREE
                        SnapshotPaths.relative(path)
                    }
                    if (++directories > limits.maxFiles.toLong() * 4) {
                        throw SnapshotException("snapshot_too_large", "Capture contains too many directories")
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    checkDeadline()
                    val path = relative(file)
                    if (excluded(path)) return FileVisitResult.CONTINUE
                    SnapshotPaths.relative(path)
                    if (entries.size >= limits.maxFiles) throw SnapshotException("snapshot_too_large", "Capture file count exceeds configured bound")
                    val entry = when {
                        attrs.isSymbolicLink -> {
                            val target = Files.readSymbolicLink(file).toString().replace(File.separatorChar, '/')
                            SnapshotPaths.linkTarget(path, target)
                            if (Files.readSymbolicLink(file).toString().replace(File.separatorChar, '/') != target) {
                                throw SnapshotException("live_input_changed", "A symlink changed during capture")
                            }
                            SnapshotEntry(root.id, path, SnapshotEntryKind.SYMLINK, symlinkTarget = target)
                        }
                        attrs.isRegularFile -> {
                            if (attrs.size() > limits.maxBlobBytes || attrs.size() > limits.maxSnapshotBytes - capturedBytes) {
                                throw SnapshotException("snapshot_too_large", "Live input exceeds the bounded capture size")
                            }
                            val bytes = FileChannel.open(file, READ, NOFOLLOW_LINKS).use { channel ->
                                if (channel.size() != attrs.size()) throw SnapshotException("live_input_changed", "File length changed before capture")
                                val buffer = ByteBuffer.allocate(attrs.size().toInt())
                                while (buffer.hasRemaining()) {
                                    checkDeadline()
                                    if (channel.read(buffer) < 0) throw SnapshotException("live_input_changed", "File was truncated during capture")
                                }
                                if (channel.size() != attrs.size() || channel.read(ByteBuffer.allocate(1)) != -1) {
                                    throw SnapshotException("live_input_changed", "File grew during capture")
                                }
                                buffer.array()
                            }
                            val after = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                            if (!after.isRegularFile || after.size() != attrs.size() || after.fileKey() != attrs.fileKey() ||
                                after.lastModifiedTime() != attrs.lastModifiedTime()) {
                                throw SnapshotException("live_input_changed", "File identity or metadata changed during capture")
                            }
                            capturedBytes += bytes.size
                            val blob = ImmutableBlob.copyOf(bytes)
                            blobs.putIfAbsent(blob.digest, blob)
                            val posix = Files.getFileAttributeView(file, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
                            val executable = posix?.readAttributes()?.permissions()?.any {
                                it == PosixFilePermission.OWNER_EXECUTE || it == PosixFilePermission.GROUP_EXECUTE || it == PosixFilePermission.OTHERS_EXECUTE
                            } ?: false
                            SnapshotEntry(root.id, path, digest = blob.digest, size = blob.size, executable = executable)
                        }
                        else -> throw SnapshotException("unsupported_file_kind", "Capture refuses devices, sockets, FIFOs, and other nonregular inputs")
                    }
                    entries.add(entry)
                    return FileVisitResult.CONTINUE
                }
            })
            val afterRoot = Files.readAttributes(live, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!afterRoot.isDirectory || afterRoot.fileKey() != beforeRoot.fileKey() || live.toRealPath() != live) {
                throw SnapshotException("live_root_changed", "Registered input root changed during capture")
            }
        }
        checkDeadline()
        val sorted = entries.sortedWith(compareBy({ it.rootId }, { it.path }))
        SnapshotPaths.validateManifest(sorted, roots, limits)
        return CapturedInputs(sorted, blobs.values.toList())
    }
}

package dev.ktchecker.snapshot

import dev.ktchecker.contracts.Digests
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/** Copies, never hardlinks. Owner read-only permissions are advisory, not a same-user sandbox.
 * Verification detects persistent divergence, not malicious transient writes restored before checking.
 */
internal class SnapshotMaterializer(
    private val limits: SnapshotLimits,
    private val readBlob: (String) -> ByteArray,
) {
    fun materialize(manifest: StoredSnapshotManifest, destination: Path, readOnly: Boolean): MaterializedSnapshot {
        val requested = destination.toAbsolutePath().normalize()
        val parent = requested.parent ?: throw SnapshotException("unsafe_path", "Materialization needs a parent directory")
        if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) {
            throw SnapshotException("unsafe_path", "Materialization parent must be an existing real directory")
        }
        val target = parent.toRealPath().resolve(requested.fileName)
        if (Files.exists(target, NOFOLLOW_LINKS)) throw SnapshotException("materialization_exists", "Each analysis needs a new private materialization directory")
        SnapshotPaths.validateManifest(manifest.entries, manifest.roots, limits)
        var ownedCreation = false
        try {
            // Exclusive root creation is the ownership boundary. A losing concurrent caller must
            // never clean up the winner's directory after its own CREATE_NEW failure.
            try {
                Files.createDirectory(target, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            } catch (_: UnsupportedOperationException) {
                Files.createDirectory(target)
            }
            ownedCreation = true
            StoreFiles(target, limits).privatePermissions(target, directory = true)
            manifest.roots.filter { it.mountPath.isNotEmpty() }.forEach { privateDirectory(target.resolve(it.mountPath)) }
            manifest.entries.filter { it.kind == SnapshotEntryKind.FILE }.forEach { entry ->
                val path = target.resolve(manifest.logicalPath(entry))
                privateDirectory(path.parent)
                val bytes = readBlob(requireNotNull(entry.digest))
                if (bytes.size.toLong() != entry.size) throw SnapshotException("store_corrupt", "Captured blob has a different size from its manifest")
                Files.write(path, bytes, CREATE_NEW, WRITE, NOFOLLOW_LINKS)
                filePermissions(path, readOnly, entry.executable)
            }
            manifest.entries.filter { it.kind == SnapshotEntryKind.SYMLINK }.forEach { entry ->
                val path = target.resolve(manifest.logicalPath(entry))
                privateDirectory(path.parent)
                Files.createSymbolicLink(path, Path.of(requireNotNull(entry.symlinkTarget)))
            }
            val checked = verify(manifest, target)
            if (!checked.valid) throw SnapshotException("materialization_mismatch", "Materialized input failed validation: ${checked.violations.joinToString()}")
            if (readOnly) setDirectoriesReadOnly(target)
            return MaterializedSnapshot(target, manifest)
        } catch (exception: Exception) {
            if (ownedCreation) deleteFailedMaterialization(target)
            if (!ownedCreation && exception is FileAlreadyExistsException) {
                throw SnapshotException("materialization_exists", "Another analysis already owns this materialization directory", exception)
            }
            if (exception is SnapshotException) throw exception
            throw SnapshotException("materialization_unavailable", "Cannot create isolated snapshot inputs on this filesystem", exception)
        }
    }

    fun verify(manifest: StoredSnapshotManifest, root: Path): MaterializationVerification {
        val violations = mutableListOf<String>()
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return MaterializationVerification(false, listOf("materialization_root_missing"))
        val expected = manifest.entries.associateBy(manifest::logicalPath)
        val seen = mutableSetOf<String>()
        var visitedBytes = 0L
        var visitedDirectories = 0L
        try {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (++visitedDirectories > limits.maxFiles.toLong() * 4 || violations.size > limits.maxFiles) {
                        violations.add("materialization_directory_limit")
                        return FileVisitResult.TERMINATE
                    }
                    if (dir != root) {
                        val relative = root.relativize(dir).toString().replace(File.separatorChar, '/')
                        if (expected.keys.none { it.startsWith(relative + "/") } &&
                            manifest.roots.none { it.mountPath == relative || it.mountPath.startsWith(relative + "/") }) {
                            violations.add("unexpected_directory:$relative")
                            return FileVisitResult.SKIP_SUBTREE
                        }
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relative = root.relativize(file).toString().replace(File.separatorChar, '/')
                    seen.add(relative)
                    val entry = expected[relative]
                    if (entry == null) {
                        violations.add("unexpected_input:$relative")
                    } else when (entry.kind) {
                        SnapshotEntryKind.FILE -> {
                            if (!attrs.isRegularFile || attrs.isSymbolicLink || attrs.size() != entry.size) {
                                violations.add("kind_or_size_changed:$relative")
                            } else {
                                visitedBytes += attrs.size()
                                if (attrs.size() > limits.maxBlobBytes || visitedBytes > limits.maxSnapshotBytes) {
                                    violations.add("materialization_limit:$relative")
                                    return FileVisitResult.TERMINATE
                                }
                                val bytes = FileChannel.open(file, READ, NOFOLLOW_LINKS).use { channel ->
                                    if (channel.size() != entry.size) throw IOException("Materialized input length changed")
                                    val buffer = ByteBuffer.allocate(entry.size.toInt())
                                    while (buffer.hasRemaining()) {
                                        if (channel.read(buffer) < 0) throw IOException("Materialized input truncated")
                                    }
                                    if (channel.size() != entry.size || channel.read(ByteBuffer.allocate(1)) != -1) {
                                        throw IOException("Materialized input grew")
                                    }
                                    buffer.array()
                                }
                                if (Digests.sha256(bytes) != entry.digest) violations.add("bytes_changed:$relative")
                                val posix = Files.getFileAttributeView(file, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
                                if (posix != null) {
                                    val executable = posix.readAttributes().permissions().any {
                                        it == PosixFilePermission.OWNER_EXECUTE || it == PosixFilePermission.GROUP_EXECUTE || it == PosixFilePermission.OTHERS_EXECUTE
                                    }
                                    if (executable != entry.executable) violations.add("mode_changed:$relative")
                                }
                            }
                        }
                        SnapshotEntryKind.SYMLINK -> if (!attrs.isSymbolicLink ||
                            Files.readSymbolicLink(file).toString().replace(File.separatorChar, '/') != entry.symlinkTarget) {
                            violations.add("symlink_changed:$relative")
                        }
                    }
                    if (violations.size > limits.maxFiles) return FileVisitResult.TERMINATE
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (_: IOException) {
            violations.add("materialization_read_failed")
        }
        (expected.keys - seen).sorted().forEach { violations.add("missing_input:$it") }
        return MaterializationVerification(violations.isEmpty(), violations.distinct().sorted())
    }

    private fun privateDirectory(path: Path) {
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(path, NOFOLLOW_LINKS)) throw SnapshotException("unsafe_path", "Materialization traverses a non-directory")
            return
        }
        try {
            Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        } catch (_: UnsupportedOperationException) {
            Files.createDirectories(path)
        }
        StoreFiles(path, limits).privatePermissions(path, directory = true)
    }

    private fun filePermissions(path: Path, readOnly: Boolean, executable: Boolean) {
        StoreFiles(path.parent, limits).privatePermissions(path)
        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString(when {
                readOnly && executable -> "r-x------"
                readOnly -> "r--------"
                executable -> "rwx------"
                else -> "rw-------"
            }))
        } else if (readOnly && !path.toFile().setReadOnly()) {
            throw SnapshotException("readonly_unavailable", "Cannot mark isolated source files read-only")
        }
    }

    private fun setDirectoriesReadOnly(root: Path) {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.getFileAttributeView(dir, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
                    ?.setPermissions(PosixFilePermissions.fromString("r-x------"))
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun deleteFailedMaterialization(root: Path) {
        if (!Files.exists(root, NOFOLLOW_LINKS)) return
        try {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    dir.toFile().setWritable(true, true)
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isSymbolicLink) file.toFile().setWritable(true, true)
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (_: IOException) {
            // Failed materializations are never returned to a provider. Preserve evidence for cleanup.
        }
    }
}

package dev.ktchecker.snapshot

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Crash safety uses atomic replacement + forced files. Directory fsync availability is exposed. */
internal class StoreFiles(val root: Path, private val limits: SnapshotLimits) {
    @Volatile var directorySyncSupported: Boolean = true
        private set

    fun probeDirectorySync() { forceDirectory(root) }

    fun directory(path: Path): Path {
        var created = false
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(path, NOFOLLOW_LINKS)) fail("unsafe_store", "Snapshot store path is not a real directory")
        } else {
            val parent = path.parent
            if (parent != null && (!Files.exists(parent, NOFOLLOW_LINKS) || (path != root && parent.startsWith(root)))) {
                directory(parent)
            }
            try {
                Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            } catch (_: UnsupportedOperationException) {
                Files.createDirectories(path)
            }
            created = true
        }
        privatePermissions(path, directory = true)
        if (created) {
            forceDirectory(path)
            path.parent?.let(::forceDirectory)
        }
        return path
    }

    fun privatePermissions(path: Path, directory: Boolean = false) {
        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
            return
        }
        val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java, NOFOLLOW_LINKS)
            ?: fail("permissions_unavailable", "Snapshot store requires owner-only POSIX permissions or ACLs")
        val entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.owner)
            .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
        if (directory) entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT)
        acl.acl = listOf(entry.build())
    }

    fun <T> locked(lockFile: Path, block: () -> T): T {
        directory(lockFile.parent)
        val canonical = lockFile.toAbsolutePath().normalize()
        val jvmLock = jvmLocks.computeIfAbsent(canonical) { ReentrantLock(true) }
        if (jvmLock.isHeldByCurrentThread) {
            fail("nested_snapshot_transaction", "Snapshot fences may inspect state but cannot call another snapshot mutation")
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.lockTimeoutMillis)
        if (!jvmLock.tryLock(limits.lockTimeoutMillis, TimeUnit.MILLISECONDS)) {
            fail("snapshot_busy", "Timed out waiting for snapshot coordination")
        }
        try {
            return FileChannel.open(lockFile, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                privatePermissions(lockFile)
                var osLock: FileLock? = null
                while (osLock == null) {
                    osLock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    if (osLock == null) {
                        if (System.nanoTime() >= deadline) fail("snapshot_busy", "Timed out waiting for another snapshot process")
                        Thread.sleep(5)
                    }
                }
                osLock.use { block() }
            }
        } finally {
            jvmLock.unlock()
        }
    }

    fun read(path: Path, maxBytes: Long = limits.maxMetadataBytes): ByteArray {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) fail("store_object_missing", "Snapshot object is missing or not a regular file")
        try {
            FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
                val size = channel.size()
                if (size > maxBytes || size > Int.MAX_VALUE) fail("store_object_too_large", "Snapshot object exceeds its configured limit")
                val buffer = ByteBuffer.allocate(size.toInt())
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) fail("store_corrupt", "Snapshot object was truncated")
                }
                if (channel.size() != size || channel.read(ByteBuffer.allocate(1)) != -1) {
                    fail("store_corrupt", "Snapshot object changed during reading")
                }
                return buffer.array()
            }
        } catch (exception: SnapshotException) {
            throw exception
        } catch (exception: IOException) {
            throw SnapshotException("store_read_failed", "Cannot read a snapshot object", exception)
        }
    }

    fun writeBudgeted(path: Path, bytes: ByteArray, replace: Boolean = false): Boolean =
        locked(root.resolve("quota.lock")) {
            if (!replace && Files.exists(path, NOFOLLOW_LINKS)) return@locked false
            // Count also abandoned temporaries. Quota is fail-closed, not an optimistic estimate.
            if (bytes.size.toLong() > limits.maxStoreBytes - usedBytes()) {
                fail("snapshot_store_full", "Snapshot store capacity exhausted; no patch was admitted")
            }
            atomicWrite(path, bytes, replace)
            true
        }

    fun atomicWrite(path: Path, bytes: ByteArray, replace: Boolean = false) {
        directory(path.parent)
        if (Files.isSymbolicLink(path)) fail("unsafe_store", "Snapshot state must not be a symlink")
        val temp = Files.createTempFile(path.parent, ".ktchecker-", ".tmp")
        try {
            privatePermissions(temp)
            FileChannel.open(temp, WRITE, NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            if (replace) Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
            else Files.move(temp, path, ATOMIC_MOVE)
            forceDirectory(path.parent)
        } catch (exception: AtomicMoveNotSupportedException) {
            throw SnapshotException("atomic_commit_unavailable", "Snapshot store filesystem lacks atomic replacement", exception)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun usedBytes(): Long {
        var bytes = 0L
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isRegularFile) fail("unsafe_store", "Snapshot store contains a nonregular object")
                bytes = Math.addExact(bytes, attrs.size())
                return FileVisitResult.CONTINUE
            }
        })
        return bytes
    }

    private fun forceDirectory(directory: Path) {
        try {
            FileChannel.open(directory, READ).use { it.force(true) }
        } catch (exception: IOException) {
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                directorySyncSupported = false
            } else {
                throw SnapshotException("directory_sync_failed", "Cannot persist snapshot directory metadata", exception)
            }
        } catch (_: UnsupportedOperationException) {
            directorySyncSupported = false
        }
    }

    private fun fail(code: String, message: String): Nothing = throw SnapshotException(code, message)

    companion object {
        private val jvmLocks = ConcurrentHashMap<Path, ReentrantLock>()
    }
}

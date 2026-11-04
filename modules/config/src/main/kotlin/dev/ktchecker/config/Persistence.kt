package dev.ktchecker.config

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** Trusted fault/telemetry hook; never supplied by a model or mutation request. */
interface ConfigWriteObserver {
    fun beforeAtomicReplace(temporary: Path, destination: Path) {}
    fun afterAtomicReplace(destination: Path) {}

    companion object { val NONE = object : ConfigWriteObserver {} }
}

data class ConfigStoragePaths(val shared: Path, val local: Path, val lock: Path)

internal data class LoadedLayer(val document: PolicyDocument?, val revision: LayerRevision, val rawText: String?)

/**
 * All cooperating readers and writers use one project lock, including all local
 * overlays. Never replace/delete the lock inode. Same-user hostile writers that
 * ignore advisory locks remain outside this cooperative storage guarantee.
 */
internal class ConfigPersistence(stateRoot: Path, private val observer: ConfigWriteObserver) {
    private val root: Path

    init {
        val absolute = stateRoot.toAbsolutePath().normalize()
        createDirectoryTree(absolute)
        if (Files.isSymbolicLink(absolute) || !Files.isDirectory(absolute, NOFOLLOW_LINKS)) {
            throw ConfigReadException("Configuration state root must be a real directory.")
        }
        root = absolute.toRealPath()
    }

    fun paths(binding: ConfigBinding): ConfigStoragePaths {
        val projectKey = Digests.sha256(ContractJson.encodeToString(listOf(binding.ownerId, binding.workspaceId))).removePrefix("sha256:")
        val localKey = Digests.sha256(ContractJson.encodeToString(listOf(binding.ownerId, binding.worktreeId, binding.lineageId))).removePrefix("sha256:")
        val directory = root.resolve("projects").resolve(projectKey)
        return ConfigStoragePaths(directory.resolve("shared.json"), directory.resolve("local").resolve("$localKey.json"), directory.resolve("policy.lock"))
    }

    fun <T> withLock(binding: ConfigBinding, block: (ConfigStoragePaths) -> T): T {
        val paths = paths(binding)
        createDirectoryTree(paths.lock.parent)
        requireWithinRoot(paths.lock.parent)
        val localLock = JVM_LOCKS.computeIfAbsent(paths.lock.toString()) { ReentrantLock(true) }
        if (localLock.isHeldByCurrentThread) throw ConfigException("Do not recursively enter configuration storage from a config fence.")
        localLock.lockInterruptibly()
        try {
            FileChannel.open(paths.lock, CREATE, READ, WRITE, NOFOLLOW_LINKS).use { channel ->
                channel.lock().use {
                    if (!Files.isRegularFile(paths.lock, NOFOLLOW_LINKS)) throw ConfigReadException("Policy lock is not a regular file.")
                    return block(paths)
                }
            }
        } catch (e: ConfigException) {
            throw e
        } catch (e: IOException) {
            throw ConfigReadException("Cannot lock/read configuration state.", e)
        } finally {
            localLock.unlock()
        }
    }

    fun load(path: Path): LoadedLayer {
        requireWithinRoot(path.parent)
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            return LoadedLayer(null, LayerRevision(0, null, ABSENT_DIGEST), null)
        }
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw ConfigReadException("Configuration must be a regular, non-symlink file.")
        try {
            val bytes = FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
                val size = channel.size()
                if (size !in 1..MAX_DOCUMENT_BYTES) throw ConfigReadException("Configuration file exceeds its size bound or is empty.")
                val buffer = ByteBuffer.allocate(size.toInt())
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) throw ConfigReadException("Configuration file changed/truncated while reading.")
                }
                if (channel.read(ByteBuffer.allocate(1)) >= 0) throw ConfigReadException("Configuration file grew while reading.")
                buffer.array()
            }
            val text = bytes.toString(Charsets.UTF_8)
            if (!text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) throw ConfigReadException("Configuration is not valid UTF-8.")
            val document = ContractJson.decodeFromString<PolicyDocument>(text)
            return LoadedLayer(document, LayerRevision(document.revision, document.generationId, Digests.sha256(bytes)), text)
        } catch (e: ConfigException) {
            throw e
        } catch (e: Exception) {
            throw ConfigReadException("Invalid or unreadable configuration; not treated as an empty policy.", e)
        }
    }

    /** One mutation changes ONE file; there is no partially committed two-layer batch. */
    fun replace(path: Path, document: PolicyDocument) {
        val bytes = ContractJson.encodeToString(document).toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > MAX_DOCUMENT_BYTES) throw ConfigValidationException("Configuration exceeds its durable size bound.")
        createDirectoryTree(path.parent)
        requireWithinRoot(path.parent)
        var temporary: Path? = null
        var replaced = false
        try {
            temporary = try {
                Files.createTempFile(path.parent, ".policy-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            } catch (_: UnsupportedOperationException) {
                Files.createTempFile(path.parent, ".policy-", ".tmp")
            }
            FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            observer.beforeAtomicReplace(temporary, path)
            Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
            replaced = true
            observer.afterAtomicReplace(path)
            forceDirectory(path.parent)
        } catch (e: Exception) {
            val detail = if (e is AtomicMoveNotSupportedException) "Atomic configuration replacement is unsupported; no non-atomic fallback." else
                if (replaced) "Configuration may have been applied, but durability could not be confirmed; inspect before retrying." else
                    "Configuration was not replaced; the previous policy remains intact."
            throw ConfigWriteException(detail, replaced, e)
        } finally {
            temporary?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun requireWithinRoot(path: Path) {
        val absolute = path.toAbsolutePath().normalize()
        if (!absolute.startsWith(root)) throw ConfigReadException("Configuration storage path escapes its state root.")
        var current = root
        for (part in root.relativize(absolute)) {
            current = current.resolve(part)
            if (Files.exists(current, NOFOLLOW_LINKS) && (Files.isSymbolicLink(current) || !Files.isDirectory(current, NOFOLLOW_LINKS))) {
                throw ConfigReadException("Configuration storage directory cannot be a symlink or non-directory.")
            }
        }
    }

    private companion object {
        const val MAX_DOCUMENT_BYTES = 16L * 1024 * 1024
        val ABSENT_DIGEST = Digests.sha256("ktchecker/config/absent/1")
        val JVM_LOCKS = ConcurrentHashMap<String, ReentrantLock>()

        fun createDirectoryTree(path: Path) {
            val absolute = path.toAbsolutePath().normalize()
            if (Files.exists(absolute, NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(absolute) || !Files.isDirectory(absolute, NOFOLLOW_LINKS)) throw ConfigReadException("Unsafe configuration directory.")
                return
            }
            absolute.parent?.let { createDirectoryTree(it) }
            try {
                Files.createDirectory(absolute, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            } catch (_: UnsupportedOperationException) {
                Files.createDirectory(absolute)
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                if (Files.isSymbolicLink(absolute) || !Files.isDirectory(absolute, NOFOLLOW_LINKS)) throw ConfigReadException("Unsafe configuration directory.")
            }
            // Persist newly created directory links before claiming a durable first write.
            forceDirectory(absolute)
            absolute.parent?.let { forceDirectory(it) }
        }

        fun forceDirectory(path: Path) {
            FileChannel.open(path, READ).use { it.force(true) }
        }
    }
}

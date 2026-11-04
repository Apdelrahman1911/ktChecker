package dev.ktchecker.cli

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable internal data class OwnerRecord(val ownerId: String, val format: Int = 1)

/** Per-user CLI/controller state, never placed in the reviewed project by default. */
internal class PrivateState(path: Path) {
    val root: Path
    val ownerId: String

    init {
        if (Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.exists(path.resolve("owner.json"), NOFOLLOW_LINKS)) {
            val foreign = Files.list(path).use { entries -> entries.anyMatch { it.fileName.toString() != "locks" } }
            if (foreign) throw CliProblem("state_directory_in_use", "Choose a new empty private state directory, not an existing project or home directory.")
            // Initializing a caller-selected EMPTY directory may establish permissions. Reopening
            // an existing store must verify them instead; never repair shared state silently.
            if (Files.list(path).use { !it.findAny().isPresent }) privatePermissions(path, directory = true)
        }
        privateDirectory(path.toAbsolutePath().normalize())
        root = path.toRealPath()
        ownerId = locked("owner") {
            val file = root.resolve("owner.json")
            if (Files.exists(file, NOFOLLOW_LINKS)) read<OwnerRecord>(file).also {
                if (!it.ownerId.matches(Regex("owner-[0-9a-f-]{36}")) || it.format != 1) {
                    throw CliProblem("state_invalid", "Unrecognized owner state; refusing to replace it.")
                }
            }.ownerId else {
                val owner = OwnerRecord("owner-${UUID.randomUUID()}")
                write(file, owner)
                owner.ownerId
            }
        }
    }

    fun namespace(name: String): Path {
        require(name.matches(Regex("[a-z][a-z0-9-]*")))
        return root.resolve(name).also(::privateDirectory)
    }

    fun keyFile(namespace: String, key: String): Path = namespace(namespace).resolve(Digests.sha256(key).removePrefix("sha256:") + ".json")

    fun <T> locked(key: String, action: () -> T): T {
        val directory = root.resolve("locks").also(::privateDirectory)
        val file = directory.resolve(Digests.sha256(key).removePrefix("sha256:") + ".lock")
        val lock = locks.computeIfAbsent(file) { ReentrantLock() }
        if (!lock.tryLock(10, TimeUnit.SECONDS)) throw CliProblem("state_busy", "Controller state is busy; retry without changing the request identity.")
        return try {
            rejectSymlink(file)
            FileChannel.open(file, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                privatePermissions(file, directory = false)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var held: java.nio.channels.FileLock? = null
                while (held == null) {
                    held = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    if (held != null) break
                    if (System.nanoTime() >= deadline) throw CliProblem("state_busy", "Controller state is busy; retry without changing the request identity.")
                    Thread.sleep(10)
                }
                held.use { action() }
            }
        } finally { lock.unlock() }
    }

    inline fun <reified T> read(path: Path): T = ContractJson.decodeFromString(readText(path))
    inline fun <reified T> write(path: Path, value: T) = writeBytes(path, ContractJson.encodeToString(value).toByteArray(Charsets.UTF_8))

    fun readText(path: Path): String {
        rejectSymlink(path)
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.size(path) > 8_388_608) {
            throw CliProblem("state_invalid", "Expected a bounded regular controller-state file.")
        }
        return Files.newInputStream(path, NOFOLLOW_LINKS).use {
            val bytes = it.readNBytes(8_388_609)
            if (bytes.size > 8_388_608) throw CliProblem("state_invalid", "Controller-state file exceeds the limit.")
            strictJsonDocument(bytes, "state_invalid")
        }
    }

    fun writeBytes(path: Path, bytes: ByteArray) {
        if (bytes.size > 8_388_608) throw CliProblem("state_limit", "Controller-state file exceeds the limit.")
        privateDirectory(path.parent)
        rejectSymlink(path)
        val temporary = Files.createTempFile(path.parent, ".ktchecker-", ".tmp")
        try {
            privatePermissions(temporary, directory = false)
            FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try { Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                throw CliProblem("atomic_state_unavailable", "Filesystem does not support atomic controller-state replacement.")
            }
            // On Windows directory fsync may be unavailable. No power-loss certification is claimed.
            try { FileChannel.open(path.parent, READ).use { it.force(true) } }
            catch (e: Exception) {
                val knownWindowsLimit = System.getProperty("os.name").startsWith("Windows") &&
                    (e is java.nio.file.AccessDeniedException || e is UnsupportedOperationException)
                if (!knownWindowsLimit) throw CliProblem("state_durability_unknown", "Replacement may be applied but directory durability failed; inspect before retrying.")
            }
        } finally { Files.deleteIfExists(temporary) }
    }

    companion object {
        private val locks = ConcurrentHashMap<Path, ReentrantLock>()
        fun defaultPath(): Path {
            val os = System.getProperty("os.name").lowercase()
            return when {
                os.contains("windows") -> System.getenv("LOCALAPPDATA")?.let(Path::of)
                    ?.resolve("ktChecker") ?: Path.of(System.getProperty("user.home"), "AppData", "Local", "ktChecker")
                os.contains("mac") -> Path.of(System.getProperty("user.home"), "Library", "Application Support", "ktChecker")
                else -> (System.getenv("XDG_STATE_HOME")?.let(Path::of)?.takeIf { it.isAbsolute }
                    ?: Path.of(System.getProperty("user.home"), ".local", "state")).resolve("ktchecker")
            }
        }
    }
}

internal fun rejectSymlink(path: Path) {
    if (Files.isSymbolicLink(path)) throw CliProblem("unsafe_state_path", "Controller paths must not be symbolic links.")
}

internal fun privateDirectory(path: Path) {
    rejectSymlink(path)
    if (Files.exists(path, NOFOLLOW_LINKS)) {
        if (!Files.isDirectory(path, NOFOLLOW_LINKS)) throw CliProblem("unsafe_state_path", "Controller path must be a real directory.")
        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
        if (posix != null && posix.readAttributes().permissions().any { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") }) {
            throw CliProblem("state_permissions", "Existing state directory must be owner-private; do not use a shared project directory.")
        }
        if (posix == null) {
            val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java, NOFOLLOW_LINKS)
                ?: throw CliProblem("private_state_unavailable", "Filesystem cannot enforce private per-user state.")
            if (acl.acl.any { it.type() == AclEntryType.ALLOW && it.principal() != acl.owner && it.permissions().isNotEmpty() }) {
                throw CliProblem("state_permissions", "Existing state ACL grants another principal access; choose owner-private state.")
            }
        }
        return
    } else {
        try { Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))) }
        catch (_: UnsupportedOperationException) { Files.createDirectories(path) }
    }
    privatePermissions(path, directory = true)
}

internal fun privatePermissions(path: Path, directory: Boolean) {
    val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
    if (posix != null) {
        posix.setPermissions(PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
        return
    }
    val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java, NOFOLLOW_LINKS)
        ?: throw CliProblem("private_state_unavailable", "Filesystem cannot enforce private per-user state permissions.")
    val permissions = AclEntryPermission.values().toSet()
    val entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.owner)
        .setPermissions(permissions)
        .apply { if (directory) setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT) }
        .build()
    acl.acl = listOf(entry)
}

package dev.ktchecker.compiler

import dev.ktchecker.contracts.Digests
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

internal data class CompilerFixtureDirectoryIdentity(val path: Path, val key: String)

internal fun fixtureDirectoryIdentity(path: Path): CompilerFixtureDirectoryIdentity {
    require(path.isAbsolute && path.normalize() == path && path.toRealPath() == path) { "Unsafe owned directory path" }
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    require(attributes.isDirectory && !attributes.isSymbolicLink) { "Owned path is not an ordinary directory" }
    return CompilerFixtureDirectoryIdentity(path, checkNotNull(attributes.fileKey()?.toString()) { "Stable directory identity required" })
}

internal fun requireFixtureDirectory(identity: CompilerFixtureDirectoryIdentity) {
    check(fixtureDirectoryIdentity(identity.path) == identity) { "Owned directory was replaced" }
}

internal fun privateFixturePermissions(path: Path) {
    if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
    }
}

/** Accounting includes immutable run files, the explicit latest sidecar, and atomic-write transient headroom. */
internal class CompilerFixtureEvidenceQuota(val maximum: Long, val terminalReserve: Long) {
    init { require(maximum > terminalReserve && terminalReserve > 0) }
    var retainedBytes = 0L
        private set
    var reservedBytes = 0L
        private set
    val normalMaximum: Long get() = maximum - terminalReserve

    inner class Reservation internal constructor(var remaining: Long) : AutoCloseable {
        internal val issuer = this@CompilerFixtureEvidenceQuota
        internal var active = true
        override fun close() { if (active) { reservedBytes -= remaining; remaining = 0; active = false } }
    }
    inner class Write internal constructor(val size: Long, val reservation: Reservation?) {
        private var active = true
        fun commit(replacedBytes: Long = 0) {
            check(active && replacedBytes in 0..retainedBytes)
            reservation?.let { check(it.active && it.remaining >= size); it.remaining -= size }
            reservedBytes -= size
            retainedBytes += size - replacedBytes
            active = false
        }
        fun abort() { if (active) { if (reservation == null) reservedBytes -= size; active = false } }
    }

    fun includeExisting(size: Long) {
        require(size >= 0)
        ensureRoom(size, terminal = false)
        retainedBytes += size
    }
    fun ensureRoom(size: Long, terminal: Boolean = false) {
        require(size >= 0)
        val limit = if (terminal) maximum else normalMaximum
        if (size > limit || retainedBytes > limit - size || reservedBytes > limit - size - retainedBytes) {
            throw CompilerFixtureStopped(CompilerFixtureStopReason.EVIDENCE_BUDGET, "Bounded evidence capacity is unavailable")
        }
    }
    fun reserveJob(size: Long): Reservation {
        ensureRoom(size)
        reservedBytes += size
        return Reservation(size)
    }
    fun beginWrite(size: Long, terminal: Boolean = false, reservation: Reservation? = null): Write {
        require(size >= 0)
        if (reservation == null) { ensureRoom(size, terminal); reservedBytes += size }
        else if (reservation.issuer !== this || !reservation.active || reservation.remaining < size) {
            throw CompilerFixtureStopped(CompilerFixtureStopReason.EVIDENCE_BUDGET, "Job evidence exceeded its pre-reserved byte bound")
        }
        return Write(size, reservation)
    }
}

/** Cooperative owned no-link output, never an arbitrary project-selected evidence or cleanup destination. */
internal class CompilerFixtureEvidence(
    private val repository: Path,
    val root: Path,
    latestSidecar: Path?,
    maximumBytes: Long = CompilerFixtureSettings.MAX_EVIDENCE_BYTES,
    terminalReserveBytes: Long = CompilerFixtureSettings.TERMINAL_RESERVE_BYTES,
) {
    private data class FileIdentity(val key: String, val size: Long, val digest: String, val immutable: Boolean)
    private val directories = linkedMapOf<Path, CompilerFixtureDirectoryIdentity>()
    private val files = linkedMapOf<Path, FileIdentity>()
    private val rootIdentity = fixtureDirectoryIdentity(root)
    private val outerIdentity = fixtureDirectoryIdentity(root.parent)
    private val external = latestSidecar?.toAbsolutePath()?.normalize()
    private val externalParent = external?.let { fixtureDirectoryIdentity(it.parent) }
    private val mutablePaths = setOfNotNull(external, root.resolve("latest-checkpoint.json"), root.resolve("summary.json"),
        root.resolve("compiler-jvm-${CompilerRuleRegistry.VERSION}.json"))
    val quota = CompilerFixtureEvidenceQuota(maximumBytes, terminalReserveBytes)

    init {
        require(root.startsWith(repository) && root != repository)
        require(external == null || external.startsWith(repository) && !external.startsWith(root))
        directories[root] = rootIdentity
        privateFixturePermissions(root)
        quota.includeExisting(OWNER_CONTROL_BYTES) // Conservative headroom for the owner's externally created bounded stop marker.
        external?.takeIf { Files.exists(it, NOFOLLOW_LINKS) }?.let {
            val identity = readIdentity(it, immutable = false)
            quota.includeExisting(identity.size)
            files[it] = identity
        }
    }

    fun directory(path: Path): Path {
        require(path.isAbsolute && path.normalize() == path && path.startsWith(root)) { "Evidence directory escapes this run" }
        requireFixtureDirectory(outerIdentity)
        requireFixtureDirectory(rootIdentity)
        var parent = root
        for (part in root.relativize(path)) {
            parent = parent.resolve(part)
            val known = directories[parent]
            if (known != null) requireFixtureDirectory(known)
            else {
                require(!Files.exists(parent, NOFOLLOW_LINKS)) { "Unregistered evidence directory already exists" }
                Files.createDirectory(parent)
                val identity = fixtureDirectoryIdentity(parent)
                directories[parent] = identity
                privateFixturePermissions(parent)
            }
        }
        return path
    }

    fun ownerStopRequested(): Boolean {
        val control = root.resolve("control")
        val identity = checkNotNull(directories[control]) { "Owner-private control directory was not registered" }
        requireFixtureDirectory(outerIdentity)
        requireFixtureDirectory(rootIdentity)
        requireFixtureDirectory(identity)
        val marker = control.resolve("stop")
        if (!Files.exists(marker, NOFOLLOW_LINKS)) return false
        val attributes = Files.readAttributes(marker, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(attributes.isRegularFile && !attributes.isSymbolicLink && attributes.size() <= 4_096 && marker.toRealPath() == marker) {
            "Owner stop marker is not a bounded ordinary file"
        }
        return true // Contents confer no authority and are deliberately neither read nor included in receipts.
    }

    fun writeNew(path: Path, bytes: ByteArray, terminal: Boolean = false,
        reservation: CompilerFixtureEvidenceQuota.Reservation? = null) = write(path, bytes, false, terminal, reservation)

    fun replaceLatest(path: Path, bytes: ByteArray, terminal: Boolean = false) = write(path, bytes, true, terminal, null)

    private fun write(path: Path, bytes: ByteArray, replace: Boolean, terminal: Boolean,
        reservation: CompilerFixtureEvidenceQuota.Reservation?) {
        require(path.isAbsolute && path.normalize() == path)
        if (replace) require(path in mutablePaths) { "Only explicitly named latest convenience files may be replaced" }
        else require(path.startsWith(root) && path != root && path !in files) { "Immutable evidence cannot be overwritten" }
        if (bytes.size > MAX_FILE_BYTES) throw CompilerFixtureStopped(CompilerFixtureStopReason.EVIDENCE_BUDGET,
            "Evidence file exceeds the bounded writer's file limit")
        checkParent(path)
        val previous = files[path]
        if (previous == null) require(!Files.exists(path, NOFOLLOW_LINKS)) { "Unregistered evidence file already exists" }
        else { require(replace && !previous.immutable); verifyFile(path, previous) }
        val ticket = quota.beginWrite(bytes.size.toLong(), terminal, reservation)
        val temporary = path.resolveSibling(".write-${UUID.randomUUID()}")
        var temporaryKey: String? = null
        var moved = false
        try {
            Files.createFile(temporary)
            temporaryKey = checkNotNull(Files.readAttributes(temporary, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()?.toString())
            if (temporary.fileSystem.supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"))
            }
            Files.newOutputStream(temporary, WRITE, NOFOLLOW_LINKS).use { it.write(bytes) }
            val temporaryIdentity = readIdentity(temporary, immutable = true)
            check(temporaryIdentity.size == bytes.size.toLong() && temporaryIdentity.digest == Digests.sha256(bytes).removePrefix("sha256:")) {
                "Evidence persistence changed bytes"
            }
            checkParent(path)
            if (previous == null) require(!Files.exists(path, NOFOLLOW_LINKS)) { "Evidence destination appeared during write" }
            else verifyFile(path, previous)
            if (replace) Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
            else Files.move(temporary, path, ATOMIC_MOVE)
            moved = true
            ticket.commit(previous?.size ?: 0)
            val finalIdentity = temporaryIdentity.copy(immutable = !replace)
            files[path] = finalIdentity
            verifyFile(path, finalIdentity)
            check(!Files.exists(temporary, NOFOLLOW_LINKS)) { "Atomic evidence temporary file did not disappear" }
        } finally {
            var unremovedTemporary = !moved && Files.exists(temporary, NOFOLLOW_LINKS)
            try {
            if (!moved && Files.exists(temporary, NOFOLLOW_LINKS)) {
                // Partial writes may not match the expected digest/size. Their captured creation key must still match.
                checkParent(path)
                val attributes = Files.readAttributes(temporary, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                check(temporaryKey != null && attributes.isRegularFile && !attributes.isSymbolicLink &&
                    attributes.fileKey()?.toString() == temporaryKey && temporary.toRealPath() == temporary) { "Evidence temporary path was substituted" }
                Files.delete(temporary)
                check(!Files.exists(temporary, NOFOLLOW_LINKS)) { "Evidence write cleanup did not disappear" }
                unremovedTemporary = false
            }
            } finally {
                if (unremovedTemporary) ticket.commit() // Retain the reserved upper bound; never hide a leftover partial file.
                else ticket.abort()
            }
        }
    }

    private fun checkParent(path: Path) {
        if (path == external) requireFixtureDirectory(checkNotNull(externalParent))
        else directory(path.parent)
    }

    private fun readIdentity(path: Path, immutable: Boolean): FileIdentity {
        val bytes = CompilerFixtureProvenance.readStable(path, MAX_FILE_BYTES)
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(attributes.isRegularFile && !attributes.isSymbolicLink && attributes.size() == bytes.size.toLong())
        return FileIdentity(checkNotNull(attributes.fileKey()?.toString()) { "Stable evidence file identity required" },
            bytes.size.toLong(), Digests.sha256(bytes).removePrefix("sha256:"), immutable)
    }

    private fun verifyFile(path: Path, expected: FileIdentity) {
        check(readIdentity(path, expected.immutable) == expected) { "Owned evidence file changed or was replaced" }
    }

    /** References can only name immutable registered bytes, never a future-overwritten latest file. */
    fun reference(path: Path): Map<String, String> {
        val identity = checkNotNull(files[path]) { "Evidence file was not persisted" }
        require(identity.immutable) { "Immutable receipts cannot hash-reference mutable convenience evidence" }
        checkParent(path)
        verifyFile(path, identity)
        return mapOf("path" to repository.relativize(path).toString(), "sha256" to identity.digest)
    }

    fun immutableFilesUnder(path: Path): List<Path> = files.filter { (file, identity) -> file.startsWith(path) && identity.immutable }
        .keys.sorted()
    fun contains(path: Path): Boolean = files[path]?.immutable == true
    fun statistics(): Map<String, Any> = mapOf("maximum_bytes" to quota.maximum,
        "reserved_terminal_bytes" to quota.terminalReserve, "normal_write_maximum_bytes" to quota.normalMaximum,
        "retained_bytes_upper_bound" to quota.retainedBytes, "active_reservations_bytes" to quota.reservedBytes,
        "owner_stop_marker_headroom_bytes" to OWNER_CONTROL_BYTES,
        "maximum_file_bytes" to MAX_FILE_BYTES,
        "scope" to "all_registered_run_files_plus_explicit_latest_sidecar; atomic_write_peak_reserved; historical_other_runs_not_counted",
        "limitations" to "trusted_quiescent_owned_paths_not_adversarial_same_UID_or_storage_hard_quota")

    companion object { private const val MAX_FILE_BYTES = 67_108_864; private const val OWNER_CONTROL_BYTES = 4_096L }
}

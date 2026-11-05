package dev.ktchecker.providers.detekt

import dev.ktchecker.contracts.Digests
import dev.ktchecker.providers.runtime.safeRelativePath
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

/**
 * Fresh, bounded, append-only event/artifact files. Actual file I/O/removal is anchored
 * to no-follow directory handles, never a mutable append path or recursive deletion.
 * Directory creation is checked before/after against stable keys; the selected owner
 * must keep ancestor directories stable. This is not hostile same-user/ABA attestation.
 */
internal class DetektCorpusEvidence(repository: Path, selected: String?, val cohort: DetektCorpusCohort) : AutoCloseable {
    val repository: Path = repository.toAbsolutePath().normalize()
    val root: Path = selectedRoot(this.repository, selected)
    val runDirectory: Path
    val liveRelative: Path = Path.of("evidence/verified-jvm-${cohort.evidenceStem}-${cohort.name}-native-preview1.json")
    private val directoryKeys = linkedMapOf<Path, String>()
    private val fileKeys = linkedMapOf<Path, String>()
    private var bytesWritten = 0L
    private var filesWritten = 0
    private var closed = false

    init {
        require(Files.isDirectory(this.repository, NOFOLLOW_LINKS) && !Files.isSymbolicLink(this.repository))
        directoryKeys[this.repository] = key(Files.readAttributes(this.repository, BasicFileAttributes::class.java, NOFOLLOW_LINKS))
        ensureDirectory(root)
        val stream = Files.newDirectoryStream(root)
        val handle = stream as? SecureDirectoryStream<Path> ?: run {
            stream.close()
            error("Anchored no-follow evidence I/O is unavailable on this host")
        }
        handle.use {
            require(key(it.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes()) == directoryKeys.getValue(root))
        }
        val runName = "${Instant.now().toString().replace(':', '-')}-${UUID.randomUUID().toString().take(8)}-${cohort.evidenceStem}-${cohort.name}"
        ensureDirectory(root.resolve("runs"))
        runDirectory = root.resolve("runs/$runName")
        // A pre-existing run, even a regular directory, must never be reused.
        require(!Files.exists(runDirectory, NOFOLLOW_LINKS))
        ensureDirectory(runDirectory)
        ensureDirectory(root.resolve("evidence"))
    }

    fun artifact(relative: String, bytes: ByteArray): JsonObject = writeNew(runDirectory.resolve(safeRelativePath(relative)), bytes)

    /** Not a JUnit TempDir: even uncertain native outcomes retain the job and all ancestors. */
    fun createRetainedScratch(): Path {
        verifyDirectories()
        require(Files.getFileStore(runDirectory).supportsFileAttributeView("posix")) { "Private retained scratch is unavailable" }
        val scratch = runDirectory.resolve("scratch")
        require(!Files.exists(scratch, NOFOLLOW_LINKS)) { "Scratch is one-shot and must never be reused" }
        Files.createDirectory(scratch, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val attrs = Files.readAttributes(scratch, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(attrs.isDirectory && !attrs.isSymbolicLink)
        directoryKeys[scratch] = key(attrs)
        verifyDirectories()
        return scratch
    }

    /** Withdraw only the named cohort, preserving its exact prior bytes first. */
    fun prepareCohort() {
        val existing = existingLive() ?: return
        artifact("prior-live-sidecar.json", existing.second)
        deleteSameFile(root.resolve(liveRelative), existing.first)
    }

    fun withdrawCohort() {
        val existing = existingLive() ?: return
        deleteSameFile(root.resolve(liveRelative), existing.first)
    }

    fun publish(sidecar: JsonObject, finalGate: () -> Unit = {}): JsonObject {
        require(sidecar["provider_id"]?.jsonPrimitive?.content == "detekt")
        require(sidecar["context"]?.jsonObject?.get("corpus_cohort")?.jsonPrimitive?.content == cohort.name)
        val committed = JsonObject(sidecar + mapOf(
            "candidate_only" to JsonPrimitive(false), "run_complete" to JsonPrimitive(true), "publication_committed" to JsonPrimitive(true)))
        val bytes = committed.toString().toByteArray(Charsets.UTF_8)
        try {
            verifyDirectories()
            finalGate()
            val reference = writeNew(root.resolve(liveRelative), bytes)
            // Includes serialization, reference/hash work, bounds, writes and every handle close.
            finalGate()
            return reference
        } catch (cause: Throwable) {
            // No checkpoint writer can prevent rollback after a late deadline/evidence error.
            try { withdrawCohort() } catch (withdrawal: Throwable) { cause.addSuppressed(withdrawal) }
            throw cause
        }
    }

    fun verifyDirectories() {
        check(!closed)
        for ((directory, expected) in directoryKeys) {
            val attrs = Files.readAttributes(directory, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(attrs.isDirectory && !attrs.isSymbolicLink && key(attrs) == expected) {
                "Evidence ancestor/directory replaced: $directory"
            }
        }
    }

    private fun writeNew(path: Path, bytes: ByteArray): JsonObject {
        require(path.startsWith(runDirectory) || path == root.resolve(liveRelative)) { "Not an owned corpus artifact path" }
        require(bytes.isNotEmpty() && bytes.size <= 32_000_000) { "Artifact exceeds the fixed per-file bound" }
        require(filesWritten < 2_000 && bytesWritten + bytes.size <= 128_000_000) { "Aggregate evidence bound exhausted" }
        ensureDirectory(path.parent)
        verifyDirectories()
        val relative = root.relativize(path)
        withDirectory(relative.parent) { directory ->
            val options: Set<OpenOption> = setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS)
            var createdKey: String? = null
            try {
                directory.newByteChannel(relative.fileName, options).use { channel ->
                    createdKey = key(attributes(directory, relative.fileName))
                    fileKeys[path] = createdKey!!
                    val data = ByteBuffer.wrap(bytes)
                    while (data.hasRemaining()) require(channel.write(data) > 0) { "Evidence write made no progress" }
                    if (channel is FileChannel) channel.force(true)
                }
                val attrs = attributes(directory, relative.fileName)
                require(attrs.isRegularFile && !attrs.isSymbolicLink && key(attrs) == createdKey && attrs.size() == bytes.size.toLong())
                verifyDirectories()
            } catch (cause: Throwable) {
                // Never remove an old/replaced file after CREATE_NEW failure.
                if (createdKey != null) runCatching {
                    val attrs = attributes(directory, relative.fileName)
                    if (attrs.isRegularFile && !attrs.isSymbolicLink && key(attrs) == createdKey) directory.deleteFile(relative.fileName)
                }
                throw cause
            }
        }
        ++filesWritten
        bytesWritten += bytes.size
        return buildJsonObject {
            put("path", repository.relativize(path).toString().replace('\\', '/'))
            put("sha256", Digests.sha256(bytes).removePrefix("sha256:"))
        }
    }

    private fun existingLive(): Pair<String, ByteArray>? {
        verifyDirectories()
        val path = root.resolve(liveRelative)
        return withDirectory(liveRelative.parent) { directory ->
            val attrs = try { attributes(directory, liveRelative.fileName) }
                catch (_: java.nio.file.NoSuchFileException) { return@withDirectory null }
            require(attrs.isRegularFile && !attrs.isSymbolicLink && attrs.size() in 1..8_388_608L) { "Unsafe cohort live sidecar" }
            val expected = key(attrs)
            fileKeys[path]?.let { require(it == expected) { "Published live sidecar replaced; refusing cleanup" } }
            requireSingleLink(path)
            val bytes = directory.newByteChannel(liveRelative.fileName, setOf<OpenOption>(READ, NOFOLLOW_LINKS)).use { channel ->
                val buffer = ByteBuffer.allocate(attrs.size().toInt())
                while (buffer.hasRemaining()) require(channel.read(buffer) > 0) { "Live sidecar changed during read" }
                require(channel.read(ByteBuffer.allocate(1)) == -1) { "Live sidecar grew during read" }
                buffer.array()
            }
            require(key(attributes(directory, liveRelative.fileName)) == expected)
            val item = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            require(item["schema_version"]?.jsonPrimitive?.int == 1 && item["provider_id"]?.jsonPrimitive?.content == "detekt")
            require(item["context"]?.jsonObject?.get("corpus_cohort")?.jsonPrimitive?.content == cohort.name) {
                "Existing sidecar is not attributed to the explicitly selected cohort; refusing deletion"
            }
            verifyDirectories()
            expected to bytes
        }
    }

    private fun deleteSameFile(path: Path, expected: String) {
        require(path == root.resolve(liveRelative)) { "Cleanup may remove only this cohort's live sidecar" }
        verifyDirectories()
        withDirectory(liveRelative.parent) { directory ->
            val attrs = attributes(directory, liveRelative.fileName)
            require(attrs.isRegularFile && !attrs.isSymbolicLink && key(attrs) == expected)
            requireSingleLink(path)
            directory.deleteFile(liveRelative.fileName)
        }
    }

    private fun requireSingleLink(path: Path) {
        require(Files.getFileStore(path.parent).supportsFileAttributeView("unix")) { "Evidence link count unavailable" }
        require((Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() == 1L) { "Hard-linked evidence is not owned cleanup" }
    }

    private fun ensureDirectory(path: Path) {
        require(path.startsWith(root) || root.startsWith(path))
        require(path.startsWith(repository) && repository.relativize(path).nameCount <= 64)
        var current = repository
        for (part in repository.relativize(path)) {
            current = current.resolve(part)
            if (!Files.exists(current, NOFOLLOW_LINKS)) {
                verifyKnownAncestor(current.parent)
                Files.createDirectory(current)
            }
            val attrs = Files.readAttributes(current, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(attrs.isDirectory && !attrs.isSymbolicLink) { "Evidence path contains a symlink or non-directory" }
            val actual = key(attrs)
            directoryKeys[current]?.let { require(it == actual) { "Evidence directory identity changed" } }
            directoryKeys[current] = actual
        }
        verifyKnownAncestor(repository)
    }

    private fun verifyKnownAncestor(path: Path) {
        var current = repository
        fun verify(directory: Path) {
            val attrs = Files.readAttributes(directory, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(attrs.isDirectory && !attrs.isSymbolicLink && key(attrs) == directoryKeys.getValue(directory))
        }
        verify(current)
        for (part in repository.relativize(path)) { current = current.resolve(part); verify(current) }
    }

    private fun <T> withDirectory(relative: Path, operation: (SecureDirectoryStream<Path>) -> T): T {
        check(!closed)
        verifyDirectories()
        val parts = relative.iterator().asSequence().toList()
        fun descend(directory: SecureDirectoryStream<Path>, index: Int, path: Path): T {
            val expected = directoryKeys.getValue(path)
            require(key(directory.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes()) == expected)
            if (index == parts.size) return operation(directory)
            val next = path.resolve(parts[index])
            return directory.newDirectoryStream(parts[index], NOFOLLOW_LINKS).use { child -> descend(child, index + 1, next) }
        }
        val stream = Files.newDirectoryStream(root)
        val handle = stream as? SecureDirectoryStream<Path> ?: run {
            stream.close()
            error("Anchored no-follow evidence I/O became unavailable")
        }
        return handle.use { descend(it, 0, root) }
    }

    private fun attributes(directory: SecureDirectoryStream<Path>, name: Path): BasicFileAttributes =
        directory.getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()

    // Every I/O handle is closed inside the caught operation, not after live publication.
    override fun close() { closed = true }

    companion object {
        private fun key(attrs: BasicFileAttributes): String = attrs.fileKey()?.toString()
            ?: error("Stable filesystem identity unavailable for evidence")

        fun selectedRoot(repository: Path, selected: String?): Path {
            if (selected == null) return repository.resolve("providers/detekt-fixtures")
            val raw = Path.of(selected)
            require(raw.none { it.toString() == ".." }) { "Evidence root must not contain traversal" }
            val base = (if (raw.isAbsolute) raw else repository.resolve(raw)).toAbsolutePath().normalize()
            require(base.startsWith(repository.resolve("build"))) { "Redirected corpus evidence must be inside repository build/" }
            return base.resolve("detekt")
        }
    }
}

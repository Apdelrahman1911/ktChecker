package dev.ktchecker.kernel

import dev.ktchecker.contracts.AnalysisInput
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Comparator
import kotlinx.serialization.encodeToString

/**
 * Copies only manifest-authorized bytes, verifies their identity, fsyncs before durable acceptance.
 * This certifies immutable analysis inputs, NOT equality with a mutable live worktree at any instant.
 * The containing per-user directory is the trust boundary; hostile same-UID mutation is not a sandbox.
 */
internal class InputArchive(private val root: Path, private val config: KernelConfig) {
    init { privateDirectory(root) }

    class Staged internal constructor(
        private val temp: Path,
        val destination: Path,
        val input: AnalysisInput,
        val bytes: Long,
    ) : AutoCloseable {
        private var installed = false
        @Volatile private var published = false

        /** All source/artifact IO stays outside the ledger writer. Before install, verify held
         * staging bytes; after publication, verify the immutable final archive path. */
        fun readInput(): AnalysisInput = if (published) input else input.copy(
                materializedRoot = temp.resolve("sources").toString(),
                context = input.context.copy(classpath = input.context.classpath.map { artifact ->
                    artifact.copy(path = temp.resolve(destination.relativize(Path.of(artifact.path))).toString())
                }))

        fun verify(maxBytes: Long) = verifyFrozen(readInput(), maxBytes)

        fun install() {
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(destination)) {
                    fail("archive_corrupt", "Archive destination is not a private directory")
                }
                // An unreferenced crash orphan may already exist. Never overwrite historical bytes.
                val manifest = destination.resolve("input.json")
                if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS) ||
                    Files.readString(manifest) != ContractJson.encodeToString(input)) {
                    fail("archive_conflict", "Archived input identity differs")
                }
                published = true
                return
            }
            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE)
            forceDirectory(destination.parent)
            installed = true
            published = true
        }
        override fun close() {
            if (!installed) deleteTree(temp)
        }
    }

    fun stage(key: String, original: AnalysisInput): Staged {
        val temp = Files.createTempDirectory(root, ".ingest-")
        privateDirectory(temp)
        try {
            val destination = root.resolve(key.removePrefix("sha256:"))
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                // Validate any crash orphan outside the DB lock; install itself only publishes a rename.
                val existing = destination.resolve("input.json")
                if (!Files.isRegularFile(existing, LinkOption.NOFOLLOW_LINKS)) fail("archive_corrupt", "Archive metadata is unavailable")
                verifyFrozen(ContractJson.decodeFromString<AnalysisInput>(Files.readString(existing)), config.maxSnapshotBytes)
            }
            val tempSources = temp.resolve("sources")
            privateDirectory(tempSources)
            val sourceRoot = Path.of(original.materializedRoot).toAbsolutePath().normalize()
            if (!Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(sourceRoot)) {
                fail("invalid_snapshot", "Snapshot materialization must be a directory, not a symbolic link")
            }
            var total = 0L
            for (file in original.snapshot.files) {
                val relative = safeRelative(file.path)
                val source = sourceRoot.resolve(relative)
                ensureNoLinks(sourceRoot, relative)
                val target = tempSources.resolve(relative)
                privateDirectory(target.parent)
                total += copyVerified(source, target, file.digest, file.size, config.maxSnapshotBytes - total)
            }
            val frozenClasspath = original.context.classpath.mapIndexed { index, artifact ->
                if (!Digests.valid(artifact.digest)) fail("invalid_artifact", "Classpath digest is required")
                val source = Path.of(artifact.path).toAbsolutePath().normalize()
                // Preview admits regular artifact files only; directory/unknown classpaths fail closed.
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) {
                    fail("invalid_artifact", "Classpath entry is not a regular immutable artifact file")
                }
                val extension = source.fileName.toString().substringAfterLast('.', "bin")
                    .takeIf { it.matches(Regex("[A-Za-z0-9]{1,12}")) } ?: "bin"
                val name = "artifact-$index.$extension"
                val target = temp.resolve("classpath").resolve(name)
                privateDirectory(target.parent)
                total += copyVerified(source, target, artifact.digest, Files.size(source), config.maxSnapshotBytes - total)
                artifact.copy(path = destination.resolve("classpath").resolve(name).toString())
            }
            val effective = original.copy(
                materializedRoot = destination.resolve("sources").toString(),
                context = original.context.copy(classpath = frozenClasspath),
                // Providers never write into model-requested locations; each attempt gets a private output.
                outputRoot = null,
            )
            val metadata = temp.resolve("input.json")
            writeDurable(metadata, ContractJson.encodeToString(effective).toByteArray())
            // Fsync all directories bottom-up before publishing the archive atomically.
            Files.walk(temp).use { paths -> paths.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                .sorted(Comparator.reverseOrder()).forEach { forceDirectory(it) } }
            return Staged(temp, destination, effective, total)
        } catch (t: Throwable) {
            deleteTree(temp)
            throw t
        }
    }

    /** Copies D's already retained sources/known artifacts, adding only the certified output
     * ordinal. The temporary read-location projection is NOT P and is never admitted as work. */
    fun stageResolvedFromDeclaration(key: String, original: AnalysisInput, archive: DeclaredPlanArchive,
        insertedArtifact: dev.ktchecker.contracts.ArtifactIdentity? = null): Staged {
        archive.verify()
        val declaration = archive.original
        val index = archive.admission.declaration.classpathIndex
        val known = original.context.classpath.filterIndexed { ordinal, _ -> insertedArtifact == null || ordinal != index }
        if (original.snapshot != declaration.snapshot || original.materializedRoot != declaration.materializedRoot ||
            original.outputRoot != null || known != declaration.context.classpath ||
            insertedArtifact != null && original.context.classpath.getOrNull(index) != insertedArtifact) {
            fail("produced_archive_resolution_mismatch", "Resolved execution must retain all declared source/known artifact identities")
        }
        val currentRead = archive.readInput()
        val retained = currentRead.context.classpath.iterator()
        val reads = original.context.classpath.mapIndexed { ordinal, artifact ->
            if (insertedArtifact != null && ordinal == index) artifact else retained.next()
        }
        if (retained.hasNext()) fail("produced_archive_resolution_mismatch", "Known artifact inventory was not consumed exactly")
        val staged = stage(key, original.copy(materializedRoot = currentRead.materializedRoot,
            context = original.context.copy(classpath = reads)))
        try {
            archive.verify()
            staged.verify(config.maxSnapshotBytes)
            return staged
        } catch (failure: Throwable) { staged.close(); throw failure }
    }

    companion object {
        fun verifyFrozen(input: AnalysisInput, maxBytes: Long = 64L * 1024 * 1024) {
            val root = Path.of(input.materializedRoot)
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) fail("archive_corrupt", "Archived source directory is unavailable")
            val expected = input.snapshot.files.map { safeRelative(it.path).toString() }.toSet()
            val actual = mutableSetOf<String>()
            Files.walk(root).use { paths -> paths.forEach { path ->
                if (Files.isSymbolicLink(path)) fail("archive_corrupt", "Archived source contains a symbolic link")
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) fail("archive_corrupt", "Archived source is not a regular file")
                    actual += root.relativize(path).toString()
                }
            } }
            if (actual != expected) fail("archive_corrupt", "Archived source inventory differs from the manifest")
            var total = 0L
            input.snapshot.files.forEach { file ->
                if (file.size < 0 || file.size > maxBytes - total) fail("archive_corrupt", "Archived source exceeds its bound")
                total += file.size
                val relative = safeRelative(file.path)
                ensureNoLinks(root, relative)
                verifyFile(root.resolve(relative), file.digest, file.size)
            }
            input.context.classpath.forEach { artifact ->
                val p = Path.of(artifact.path)
                val size = Files.size(p)
                if (size > maxBytes - total) fail("archive_corrupt", "Archived classpath exceeds its bound")
                total += size
                verifyFile(p, artifact.digest, size)
            }
        }

        fun safeRelative(value: String): Path {
            if (value.isBlank() || value.contains('\\') || value.contains('\u0000') || value.startsWith('/') ||
                value.split('/').any { it.isEmpty() || it == "." || it == ".." } ||
                Regex("^[A-Za-z]:").containsMatchIn(value)) {
                fail("invalid_path", "Snapshot paths must be normalized relative paths")
            }
            val result = Path.of(value)
            if (result.isAbsolute) fail("invalid_path", "Absolute source paths are not accepted")
            return result
        }

        private fun ensureNoLinks(root: Path, relative: Path) {
            if (Files.isSymbolicLink(root)) fail("invalid_snapshot", "Symbolic links are not accepted")
            var current = root
            for (part in relative) {
                current = current.resolve(part)
                if (Files.isSymbolicLink(current)) fail("invalid_snapshot", "Symbolic links are not accepted")
            }
        }

        private fun copyVerified(source: Path, target: Path, digest: String, size: Long, budget: Long): Long {
            if (size < 0 || size > budget || !Digests.valid(digest)) fail("input_limit", "Input size or digest is invalid")
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) {
                fail("invalid_snapshot", "Snapshot source is not a regular file")
            }
            val hash = MessageDigest.getInstance("SHA-256")
            var count = 0L
            Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS).use { from ->
                FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { to ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = from.read(buffer)
                        if (n < 0) break
                        count += n
                        if (count > size || count > budget) fail("input_changed", "Input differs from its sealed size")
                        hash.update(buffer, 0, n)
                        val bytes = ByteBuffer.wrap(buffer, 0, n)
                        while (bytes.hasRemaining()) to.write(bytes)
                    }
                    to.force(true)
                }
            }
            privateFile(target)
            val actual = "sha256:" + hash.digest().joinToString("") { "%02x".format(it) }
            if (count != size || actual != digest) fail("input_changed", "Input differs from its sealed digest")
            return count
        }

        private fun verifyFile(path: Path, expected: String, expectedSize: Long?) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                fail("archive_corrupt", "Archived input is missing or not a regular file")
            }
            if (expectedSize != null && Files.size(path) != expectedSize) fail("archive_corrupt", "Archived input size differs")
            val hash = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    hash.update(buffer, 0, n)
                }
            }
            val actual = "sha256:" + hash.digest().joinToString("") { "%02x".format(it) }
            if (actual != expected) fail("archive_corrupt", "Archived input digest differs")
        }
    }
}

internal fun writeDurable(path: Path, bytes: ByteArray) {
    FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
        channel.force(true)
    }
    privateFile(path)
}
internal fun forceDirectory(path: Path): Boolean {
    // Windows Java does not expose directory fsync. This is explicitly process-crash durability,
    // not a claimed cross-platform power-loss certificate; all other I/O failures still propagate.
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return false
    FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
    return true
}
internal fun deleteTree(root: Path) {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
    Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
}

package dev.ktchecker.compiler

import dev.ktchecker.contracts.AnalysisInput
import dev.ktchecker.contracts.ArtifactIdentity
import dev.ktchecker.contracts.Digests
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Collections

data class SealedCompilerSource(val relativePath: String, val digest: String, val text: String)

object CompilerSnapshot {
    const val MAX_SOURCE_BYTES = 16_777_216
    const val MAX_ARTIFACT_BYTES = 268_435_456L
    const val MAX_TOTAL_ARTIFACT_BYTES = 536_870_912L

    /** Compilation membership comes only from the sealed owner-imported model. */
    fun compilationPaths(input: AnalysisInput): List<String> {
        require(input.snapshot.files.size <= 20_000) { "Too many snapshot files" }
        val manifestPaths = input.snapshot.files.map { relative(it.path) }
        require(manifestPaths.toSet().size == manifestPaths.size) { "Duplicate snapshot paths" }
        // A source-only snapshot is allowed for limited transport execution; it
        // does not imply complete Gradle/KMP compilation membership.
        val paths = input.context.compilationFiles?.toList() ?: manifestPaths
        require(paths.isNotEmpty() && paths.size <= 20_000 && paths.toSet().size == paths.size) {
            "Missing or duplicate declared compilation members"
        }
        val members = manifestPaths.toSet()
        for (value in paths) {
            val path = relative(value)
            require(path in members) { "Declared compilation member is absent from the snapshot: $path" }
            require(path.endsWith(".kt") || path.endsWith(".java")) {
                "Unsupported compilation member $path; scripts/build metadata are not compiler sources"
            }
        }
        require(paths.any { it.endsWith(".kt") }) { "No declared Kotlin sources" }
        return Collections.unmodifiableList(paths)
    }

    fun read(input: AnalysisInput, checkpoint: () -> Unit = {}): List<SealedCompilerSource> {
        checkpoint()
        val root = Path.of(input.materializedRoot).toAbsolutePath().normalize()
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)) {
            "Materialized snapshot root is not an ordinary directory"
        }
        val selected = compilationPaths(input)
        val files = input.snapshot.files.associateBy { it.path }
        require(selected.sumOf { files.getValue(it).size.also { size ->
            require(size in 0..MAX_SOURCE_BYTES.toLong()) { "Invalid source size" }
        } } <= MAX_SOURCE_BYTES) { "Source batch exceeds byte limit" }
        var total = 0L
        val sources = selected.map { relative ->
            checkpoint()
            val file = files.getValue(relative)
            require(Digests.valid(file.digest)) { "Invalid source identity" }
            val path = root.resolve(relative).normalize()
            require(path.startsWith(root)) { "Source escapes snapshot" }
            var parent: Path? = path
            while (parent != null && parent.startsWith(root)) {
                require(!Files.isSymbolicLink(parent)) { "Symlink in materialized source path" }
                if (parent == root) break
                parent = parent.parent
            }
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) == file.size) {
                "Missing or changed snapshot source $relative"
            }
            val bytes = java.io.ByteArrayOutputStream(file.size.toInt()).also { output ->
                Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { stream ->
                    val buffer = ByteArray(65_536)
                    while (true) {
                        checkpoint()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        require(output.size().toLong() + count <= file.size) { "Source grew during read" }
                        output.write(buffer, 0, count)
                    }
                }
            }.toByteArray()
            total += bytes.size
            require(total <= MAX_SOURCE_BYTES && bytes.size.toLong() == file.size && Digests.sha256(bytes) == file.digest) {
                "Source snapshot size/digest changed or exceeds limit"
            }
            val text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            SealedCompilerSource(relative, file.digest, text)
        }
        return Collections.unmodifiableList(sources)
    }

    fun classpath(artifacts: List<ArtifactIdentity>, checkpoint: () -> Unit = {}): List<Path> {
        require(artifacts.size <= 2_048) { "Too many classpath artifacts" }
        val paths = artifacts.map { artifact ->
            checkpoint()
            artifactPath(artifact)
        }
        require(paths.sumOf { Files.size(it) } <= MAX_TOTAL_ARTIFACT_BYTES) { "Resolved classpath exceeds total byte limit" }
        return Collections.unmodifiableList(paths.mapIndexed { index, path ->
            checkpoint()
            val size = Files.size(path)
            val artifact = artifacts[index]
            require(hashArtifact(path, size, checkpoint) == artifact.digest) { "Resolved classpath artifact identity changed" }
            path
        })
    }

    /** Bounded streaming copy; the compiler sees only this verified job-local artifact. */
    fun copyArtifact(artifact: ArtifactIdentity, destination: Path, checkpoint: () -> Unit = {}) {
        checkpoint()
        val source = artifactPath(artifact)
        val size = Files.size(source)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        Files.newInputStream(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS).use { output ->
                val buffer = ByteArray(65_536)
                while (true) {
                    checkpoint()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= size && total <= MAX_ARTIFACT_BYTES) { "Artifact grew during copy" }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
        }
        require(total == size && digestString(digest) == artifact.digest) { "Artifact copy is not the resolved identity" }
    }

    private fun artifactPath(artifact: ArtifactIdentity): Path {
        require(Digests.valid(artifact.digest)) { "Unknown classpath digest" }
        val supplied = Path.of(artifact.path)
        require(supplied.isAbsolute) { "Resolved classpath path must be explicitly absolute" }
        val path = supplied.normalize()
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            "Classpath requires a resolved regular artifact; directory manifests are unsupported"
        }
        require(Files.size(path) in 0..MAX_ARTIFACT_BYTES) { "Classpath artifact exceeds hash limit" }
        return path
    }

    private fun hashArtifact(path: Path, size: Long, checkpoint: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { stream ->
            val buffer = ByteArray(65_536)
            while (true) {
                checkpoint()
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                require(total <= size && total <= MAX_ARTIFACT_BYTES) { "Artifact grew during verification" }
                digest.update(buffer, 0, count)
            }
        }
        require(total == size) { "Artifact size changed during verification" }
        return digestString(digest)
    }

    private fun digestString(digest: MessageDigest) = "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }

    fun relative(value: String): String {
        require(value.isNotBlank() && '\\' !in value && '\u0000' !in value && ':' !in value && '\n' !in value && '\r' !in value &&
            "{snapshot}" !in value && "{output}" !in value) { "Invalid snapshot path" }
        val path = Path.of(value)
        require(!path.isAbsolute && path.none { it.toString() in setOf(".", "..") } && path.normalize().toString() == value) {
            "Snapshot path is not canonical relative source identity"
        }
        return value
    }

    fun privateDirectory(parent: Path, prefix: String): Path {
        Files.createDirectories(parent)
        val result = Files.createTempDirectory(parent, prefix)
        if (result.fileSystem.supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(result, PosixFilePermissions.fromString("rwx------"))
        }
        return result
    }
}

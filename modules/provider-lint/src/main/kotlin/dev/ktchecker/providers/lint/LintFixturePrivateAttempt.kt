package dev.ktchecker.providers.lint

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.ExecutionProblem
import dev.ktchecker.providers.runtime.ProcessOutcome
import java.nio.ByteBuffer
import java.nio.file.DirectoryStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import kotlinx.serialization.json.*

/**
 * Opt-in local diagnostics, not public evidence or execution/report/coverage authority.
 * Requires a canonical, current-owner 0700 parent. Held no-follow directory handles anchor
 * every file write; pathname replacement never redirects bytes to a replacement directory.
 * Java has no mkdir-at: initial EMPTY directory provisioning requires a cooperative namespace.
 * Its pathname/anchored binding is checked before any private bytes are written. These checks
 * detect observed replacement, not arbitrary same-UID/privileged mutation or ABA attacks.
 * Failed candidates are left in place, never recursively cleaned or followed through aliases.
 */
internal object LintFixturePrivateAttempt {
    const val MAX_OUTPUT_BYTES = 8_388_608
    const val MAX_METADATA_BYTES = 65_536
    private val directoryPermissions = PosixFilePermissions.fromString("rwx------")
    private val filePermissions = PosixFilePermissions.fromString("rw-------")

    fun retain(destination: Path, fixture: Path, snapshotId: String,
               input: AnalysisInput?, attempt: ProcessOutcome?,
               checkpoint: (String) -> Unit = {}) = privateIo {
        retainChecked(destination, fixture, snapshotId, input, attempt, checkpoint)
    }

    /** Read-only capability/trust probe. A refusal is explicit, never an instruction to weaken Lint. */
    fun requireSupportedParent(parent: Path) = privateIo {
        withPrivateParent(canonicalParent(parent)) { it.verify() }
    }

    private fun retainChecked(destination: Path, fixture: Path, snapshotId: String,
                              input: AnalysisInput?, attempt: ProcessOutcome?, checkpoint: (String) -> Unit) {
        if ((input == null) != (attempt == null)) {
            throw ExecutionProblem("lint_private_attempt_descriptor_missing", "An original attempt requires its original fixture descriptor")
        }
        val requested = destination.toAbsolutePath()
        if (requested != requested.normalize()) aliasSelection()
        val parent = requested.parent?.let(::canonicalParent)
            ?: throw ExecutionProblem("lint_private_attempt_destination_invalid", "Select a fresh child of an existing private output parent")
        val target = parent.resolve(requested.fileName)
        for (source in listOfNotNull(fixture, input?.let { Path.of(it.materializedRoot) })) {
            val normalized = source.toAbsolutePath().normalize()
            val real = if (Files.exists(normalized, NOFOLLOW_LINKS)) normalized.toRealPath() else normalized
            if (target.startsWith(real) || real.startsWith(target)) {
                throw ExecutionProblem("lint_private_attempt_source_overlap", "Private diagnostics must be outside producer and materialized sources")
            }
        }
        val out = attempt?.stdout.orEmpty()
        val err = attempt?.stderr.orEmpty()
        if (out.length.toLong() + err.length > MAX_OUTPUT_BYTES) outputLimit()
        val stdout = out.toByteArray(Charsets.UTF_8)
        val stderr = err.toByteArray(Charsets.UTF_8)
        if (stdout.size.toLong() + stderr.size > MAX_OUTPUT_BYTES) outputLimit()
        val metadata = buildJsonObject {
            put("schemaVersion", 1)
            put("diagnosticOnly", true)
            put("scope", "private_lint_fixture_attempt_not_execution_or_qualification_authority")
            put("outputEncoding", "utf8_of_original_ProcessOutcome_strings")
            put("originalFixture", fixture.toAbsolutePath().normalize().toString())
            put("driverSnapshotId", snapshotId)
            put("lintAttemptCaptured", attempt != null)
            put("materializedRoot", input?.let { JsonPrimitive(it.materializedRoot) } ?: JsonNull)
            put("sourceSnapshot", input?.let { ContractJson.encodeToJsonElement(it.snapshot) } ?: JsonNull)
            put("analysisKey", input?.let { JsonPrimitive(it.analysisKey()) } ?: JsonNull)
            put("context", input?.let { ContractJson.encodeToJsonElement(it.context.scope) } ?: JsonNull)
            put("modelDigest", input?.context?.modelDigest?.let { JsonPrimitive(it) } ?: JsonNull)
            // Declared owner profile, not a reconstruction of ambient environment.
            put("declaredExecution", input?.execution?.let { ContractJson.encodeToJsonElement(it) } ?: JsonNull)
            put("process", attempt?.let { original ->
                buildJsonObject {
                    put("status", original.status)
                    put("reasons", JsonArray(original.reasons.map { JsonPrimitive(it) }))
                    put("exitCode", original.exitCode?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("command", JsonArray(original.command.map { JsonPrimitive(it) }))
                    put("outputDirectory", original.outputDirectory?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                    // Original stored datum only; never restat/reconstruct output identity.
                    put("outputIdentityOriginal", original.outputIdentityForPrivateEvidence()?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("supervisorIdentity", original.supervisorIdentity?.let { identity ->
                        buildJsonObject {
                            put("protocolVersion", identity.protocolVersion)
                            put("target", identity.target)
                            put("sourceDigest", identity.sourceDigest)
                            put("binaryDigest", identity.binaryDigest)
                        }
                    } ?: JsonNull)
                    put("executionConfirmed", original.executionConfirmed)
                    put("executed", original.executed)
                    put("successfulExecution", original.successfulExecution)
                    put("cancelled", original.cancelled)
                    put("timedOut", original.timedOut)
                    put("truncated", original.truncated)
                    put("metrics", ContractJson.encodeToJsonElement(original.metrics))
                }
            } ?: JsonNull)
            put("stdoutBytes", stdout.size)
            put("stderrBytes", stderr.size)
            put("stdoutDigest", Digests.sha256(stdout))
            put("stderrDigest", Digests.sha256(stderr))
            put("combinedOutputDigest", Digests.sha256(stdout + byteArrayOf(10) + stderr))
        }.toString().plus("\n").toByteArray(Charsets.UTF_8)
        if (metadata.size > MAX_METADATA_BYTES) {
            throw ExecutionProblem("lint_private_attempt_metadata_limit", "Private diagnostic metadata exceeds its fixed bound")
        }
        withPrivateParent(parent) { anchored ->
            requireAbsent(anchored.stream, target.fileName)
            checkpoint("parent_opened")
            anchored.verify()
            requireAbsent(anchored.stream, target.fileName)
            checkpoint("before_directory_create")
            // No private bytes pass through this pathname-only mkdir. Bind the created empty
            // directory to the held parent before opening it, then write only via held handles.
            try { Files.createDirectory(target, PosixFilePermissions.asFileAttribute(directoryPermissions)) }
            catch (_: FileAlreadyExistsException) { anchored.verify(); destinationExists() }
            val created = Files.readAttributes(target, PosixFileAttributes::class.java, NOFOLLOW_LINKS)
            val key = directoryKey(created)
            if (!isOwnerPrivateDirectory(created, anchored.owner)) bindingChanged()
            checkpoint("directory_created")
            anchored.verify()
            sameDirectory(anchored.stream, target.fileName, key)
            anchored.stream.newDirectoryStream(target.fileName, NOFOLLOW_LINKS).use { directory ->
                val output = PrivateDirectory(anchored, directory, target.fileName, key)
                output.verify()
                checkpoint("directory_opened")
                output.verify()
                val files = linkedMapOf("stdout.log" to stdout, "stderr.log" to stderr, "attempt.json" to metadata)
                val keys = files.mapValues { (name, bytes) -> writeNew(output, name, bytes, checkpoint) }
                output.verify()
                for ((name, bytes) in files) verifyFile(output, name, keys.getValue(name), bytes.size.toLong())
                val entries = directory.iterator().asSequence().take(4).map { it.fileName.toString() }.toList()
                if (entries.size != 3 || entries.toSet() != files.keys) bindingChanged()
                output.verify()
            }
            anchored.verify()
            sameDirectory(anchored.stream, target.fileName, key)
        }
    }

    private fun outputLimit(): Nothing =
        throw ExecutionProblem("lint_private_attempt_output_limit", "Private process output exceeds its fixed combined UTF-8 byte bound")

    private fun canonicalParent(path: Path): Path {
        val absolute = path.toAbsolutePath()
        if (absolute.nameCount > 128 || absolute.toString().length > 4096) {
            throw ExecutionProblem("lint_private_attempt_destination_invalid", "Private output parent exceeds its path bound")
        }
        // Deliberate alias rejection, including symlinked ancestors (e.g. /var on macOS).
        // Owners may explicitly select the canonical path; never silently relocate a request.
        if (absolute != absolute.normalize() || absolute != absolute.toRealPath()) aliasSelection()
        return absolute
    }

    private data class HeldDirectory(val stream: SecureDirectoryStream<Path>, val key: Any, val name: Path?)

    private class PrivateParent(val stream: SecureDirectoryStream<Path>, val owner: UserPrincipal,
                                private val held: List<HeldDirectory>) {
        fun verify() = checkBinding {
            for ((index, directory) in held.withIndex()) {
                sameDirectory(directory.stream, directory.key)
                if (index > 0) sameDirectory(held[index - 1].stream, requireNotNull(directory.name), directory.key)
            }
            if (!isOwnerPrivateDirectory(posix(stream), owner)) bindingChanged()
        }
    }

    private class PrivateDirectory(val parent: PrivateParent, val stream: SecureDirectoryStream<Path>,
                                   private val name: Path, private val key: Any) {
        fun verify() = checkBinding {
            parent.verify()
            sameDirectory(parent.stream, name, key)
            sameDirectory(stream, key)
            if (!isOwnerPrivateDirectory(posix(stream), parent.owner)) bindingChanged()
        }
    }

    private fun <T> withPrivateParent(path: Path, action: (PrivateParent) -> T): T {
        if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS) == null) {
            throw ExecutionProblem("lint_private_attempt_permissions_unavailable", "Owner-only private diagnostics require POSIX file permissions")
        }
        // Query the running process, not the mutable user.name property or a requested path's owner.
        val owner = try {
            val user = ProcessHandle.current().info().user().orElse(null) ?: secureIoUnavailable()
            path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(user)
        } catch (_: Exception) { secureIoUnavailable() }
        Files.newDirectoryStream(path.root).use { rootStream ->
            val root = secureDirectory(rootStream)
            val held = arrayListOf(HeldDirectory(root, directoryKey(basic(root)), null))
            fun descend(index: Int): T {
                val current = held.last().stream
                if (index == path.nameCount) {
                    if (!isOwnerPrivateDirectory(posix(current), owner)) {
                        throw ExecutionProblem("lint_private_attempt_parent_not_private", "Select an existing current-owner directory with exactly 0700 permissions")
                    }
                    val parent = PrivateParent(current, owner, held.toList())
                    parent.verify()
                    return action(parent).also { parent.verify() }
                }
                val name = path.getName(index)
                val key = directoryKey(current.getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes())
                return current.newDirectoryStream(name, NOFOLLOW_LINKS).use { child ->
                    held.add(HeldDirectory(child, key, name))
                    try {
                        sameDirectory(child, key)
                        sameDirectory(current, name, key)
                        descend(index + 1)
                    } finally { held.removeAt(held.lastIndex) }
                }
            }
            return descend(0)
        }
    }

    internal fun secureDirectory(stream: DirectoryStream<Path>): SecureDirectoryStream<Path> =
        stream as? SecureDirectoryStream<Path> ?: secureIoUnavailable()

    internal fun isOwnerPrivateDirectory(attributes: PosixFileAttributes, owner: UserPrincipal): Boolean =
        attributes.isDirectory && !attributes.isSymbolicLink && attributes.owner() == owner && attributes.permissions() == directoryPermissions

    private fun basic(stream: SecureDirectoryStream<Path>): BasicFileAttributes =
        (stream.getFileAttributeView(BasicFileAttributeView::class.java) ?: secureIoUnavailable()).readAttributes()

    private fun posix(stream: SecureDirectoryStream<Path>): PosixFileAttributes =
        (stream.getFileAttributeView(PosixFileAttributeView::class.java) ?: secureIoUnavailable()).readAttributes()

    private fun directoryKey(attributes: BasicFileAttributes): Any {
        if (!attributes.isDirectory || attributes.isSymbolicLink) bindingChanged()
        return attributes.fileKey() ?: secureIoUnavailable()
    }

    private fun sameDirectory(stream: SecureDirectoryStream<Path>, expected: Any) {
        val actual = basic(stream)
        if (!actual.isDirectory || actual.isSymbolicLink || actual.fileKey() != expected) bindingChanged()
    }

    private fun sameDirectory(parent: SecureDirectoryStream<Path>, name: Path, expected: Any) {
        val actual = parent.getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()
        if (!actual.isDirectory || actual.isSymbolicLink || actual.fileKey() != expected) bindingChanged()
    }

    private fun requireAbsent(parent: SecureDirectoryStream<Path>, name: Path) {
        try { parent.getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes() }
        catch (_: NoSuchFileException) { return }
        destinationExists()
    }

    private fun verifyFile(output: PrivateDirectory, name: String, key: Any, size: Long) = checkBinding {
        val attributes = output.stream.getFileAttributeView(Path.of(name), PosixFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()
        if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.fileKey() != key ||
            attributes.owner() != output.parent.owner || attributes.permissions() != filePermissions || attributes.size() != size) bindingChanged()
    }

    private fun writeNew(output: PrivateDirectory, name: String, bytes: ByteArray, checkpoint: (String) -> Unit): Any {
        output.verify()
        checkpoint("before_open:$name") // Mutation seam after check: open must still use the held directory.
        val opened = try {
            output.stream.newByteChannel(Path.of(name), setOf<OpenOption>(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(filePermissions))
        } catch (_: FileAlreadyExistsException) { bindingChanged() }
        val key = opened.use { channel ->
            val key = output.stream.getFileAttributeView(Path.of(name), BasicFileAttributeView::class.java, NOFOLLOW_LINKS)
                .readAttributes().fileKey() ?: secureIoUnavailable()
            output.verify()
            verifyFile(output, name, key, 0)
            if (channel.size() != 0L) bindingChanged()
            checkpoint("before_write:$name") // Writes remain attached to this newly created channel.
            val buffer = ByteBuffer.wrap(bytes)
            var stalled = 0
            while (buffer.hasRemaining()) {
                if (channel.write(buffer) == 0) {
                    if (++stalled > 16) writeFailed()
                } else stalled = 0
            }
            if (channel.size() != bytes.size.toLong()) bindingChanged()
            output.verify()
            verifyFile(output, name, key, bytes.size.toLong())
            key
        }
        checkpoint("after_write:$name")
        output.verify()
        verifyFile(output, name, key, bytes.size.toLong())
        return key
    }

    private fun aliasSelection(): Nothing = throw ExecutionProblem("lint_private_attempt_destination_alias", "Select a normalized canonical private output parent without aliases")
    private fun destinationExists(): Nothing = throw ExecutionProblem("lint_private_attempt_destination_exists", "Private diagnostics never overwrite an existing entry")
    private fun secureIoUnavailable(): Nothing = throw ExecutionProblem("lint_private_attempt_secure_io_unavailable", "Anchored private diagnostics require secure directory I/O, stable identities and a known process owner")
    private fun bindingChanged(): Nothing = throw ExecutionProblem("lint_private_attempt_binding_changed", "Private diagnostic directory or file binding changed")
    private fun writeFailed(): Nothing = throw ExecutionProblem("lint_private_attempt_write_failed", "Requested private diagnostics could not be retained")

    private fun <T> checkBinding(action: () -> T): T = try { action() }
        catch (_: Exception) { bindingChanged() }

    private fun <T> privateIo(action: () -> T): T = try { action() }
        // Local fixed errors only. Do not retain suppressed raw close/I/O exceptions from use{}.
        catch (error: ExecutionProblem) { throw ExecutionProblem(error.code, error.message.orEmpty()) }
        catch (_: UnsupportedOperationException) { secureIoUnavailable() }
        // Never publish an I/O exception's private path, cause or process contents.
        catch (_: Exception) { writeFailed() }
}

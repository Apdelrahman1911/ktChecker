package dev.ktchecker.providers.runtime

import dev.ktchecker.contracts.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.charset.CodingErrorAction
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes

/** A transport boolean, project policy or compiler argument is never execution authority. */
fun interface ExecutionAuthority {
    fun authorize(input: AnalysisInput): ExecutionPermit?

    companion object {
        val DENY = ExecutionAuthority { null }
    }
}

/**
 * Non-serializable authority supplied by the owner of an integrated immutable materialization.
 * verifySnapshot must verify that registered materialization, not recapture a live worktree.
 * Byte checks below detect additional drift; they do not prove coherent capture or prevent ABA.
 */
class ExecutionPermit(
    execution: TrustedExecution,
    val snapshotId: String,
    val materializedRoot: Path,
    val expectedAnalysisKey: String,
    val verifySnapshot: () -> Unit,
    val prepareOutput: (Path) -> Unit = {},
) {
    // A caller retaining a MutableList/MutableMap cannot change an already granted profile.
    val execution: TrustedExecution = execution.copy(
        arguments = java.util.Collections.unmodifiableList(execution.arguments.toList()),
        environment = java.util.Collections.unmodifiableMap(execution.environment.toMap()),
    )
}

/** Exact profile helper for an authenticated host; never construct this from project JSON. */
class ExactExecutionAuthority(private val permit: ExecutionPermit) : ExecutionAuthority {
    override fun authorize(input: AnalysisInput): ExecutionPermit? = permit.takeIf {
        input.execution == permit.execution && input.snapshot.snapshotId == permit.snapshotId &&
            input.analysisKey() == permit.expectedAnalysisKey &&
            Path.of(input.materializedRoot).toAbsolutePath().normalize() ==
            permit.materializedRoot.toAbsolutePath().normalize()
    }
}

class ExecutionProblem(val code: String, message: String) : IllegalArgumentException(message)

data class ProcessOutcome(
    val status: String,
    val reasons: List<String> = emptyList(),
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val outputDirectory: Path? = null,
    val command: List<String> = emptyList(),
    val cancelled: Boolean = false,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
    val metrics: AnalysisMetrics = AnalysisMetrics(),
    internal val outputIdentity: String? = null,
    val supervisorIdentity: OwnedSupervisorIdentity? = null,
    val executionConfirmed: Boolean = false,
) {
    /** A fork-child exit alone is not proof that the selected executable started. */
    val executed: Boolean get() = executionConfirmed
    val successfulExecution: Boolean get() = executionConfirmed && status == "executed"

    /** Fresh isolated output only. Symlinks and path traversal are not evidence. */
    fun report(relativePath: String, maxBytes: Int = 8_388_608): ByteArray {
        val root = outputDirectory ?: throw ExecutionProblem("report_unavailable", "No executed output directory")
        val rel = safeRelativePath(relativePath)
        if (maxBytes !in 1..16_777_216) throw ExecutionProblem("report_limit", "Invalid report bound")
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) throw ExecutionProblem("report_root_invalid", "Output root was replaced")
        try {
            Files.newDirectoryStream(root).use { directory ->
                val secure = directory as? SecureDirectoryStream<Path>
                    ?: throw ExecutionProblem("secure_report_io_unavailable", "No anchored no-follow report reader on this host")
                val actual = secure.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes().fileKey()?.toString()
                if (outputIdentity == null || outputIdentity != actual) {
                    throw ExecutionProblem("report_root_replaced", "Report root differs from the created isolated output directory")
                }
                return readReport(secure, rel.iterator().asSequence().toList(), maxBytes)
            }
        } catch (e: ExecutionProblem) { throw e }
        catch (_: Exception) { throw ExecutionProblem("report_missing_or_unsafe", "Provider report is absent or unsafe") }
    }
}

/** No shell, unique outputs, bounded diagnostics and cooperative owned-process-tree shutdown. */
class IsolatedProcessRunner(private val onComplete: (ProcessOutcome) -> Unit = {}) {
    fun run(
        input: AnalysisInput,
        authority: ExecutionAuthority = ExecutionAuthority.DENY,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ProcessOutcome = execute(input, authority, cancellation).also(onComplete)

    private fun execute(
        input: AnalysisInput,
        authority: ExecutionAuthority,
        cancellation: CancellationToken,
    ): ProcessOutcome {
        val started = System.nanoTime()
        fun outcome(status: String, reason: String) = ProcessOutcome(
            status, listOf(reason), metrics = AnalysisMetrics(elapsedMillis = elapsed(started)),
        )
        if (cancellation.isCancelled()) return outcome("cancelled", "cancelled_before_execution")
        val permit = try { authority.authorize(input) } catch (_: Exception) { null }
            ?: return outcome("unavailable", "execution_not_authorized")
        val execution = permit.execution
        val sources: SnapshotSources
        val executable: Path
        val output: Path
        val outputIdentity: String
        val command: List<String>
        try {
            if (!execution.approved || input.execution != execution) {
                throw ExecutionProblem("execution_not_authorized", "An exact owner-selected profile is required")
            }
            if (input.snapshot.snapshotId != permit.snapshotId ||
                input.analysisKey() != permit.expectedAnalysisKey ||
                Path.of(input.materializedRoot).toAbsolutePath().normalize() !=
                permit.materializedRoot.toAbsolutePath().normalize()) {
                throw ExecutionProblem("snapshot_binding_mismatch", "Execution permit names a different materialization")
            }
            if (execution.timeoutMillis !in 1..3_600_000 || execution.maxOutputBytes !in 1..16_777_216) {
                throw ExecutionProblem("execution_limits_invalid", "Execution bounds are invalid")
            }
            permit.verifySnapshot()
            sources = SnapshotSources(input)
            sources.verify()
            executable = Path.of(execution.executable)
            if (!executable.isAbsolute || !Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
                throw ExecutionProblem("executable_unavailable", "Executable must be an existing absolute selected path")
            }
            val expectedExecutableDigest = execution.executableDigest
            if (expectedExecutableDigest == null || !Digests.valid(expectedExecutableDigest) ||
                fileDigest(executable) != expectedExecutableDigest) {
                throw ExecutionProblem("executable_digest_mismatch", "Executable is not the pinned owner-selected binary")
            }
            val outputParent = input.outputRoot?.let { Path.of(it).toAbsolutePath().normalize() }
                ?: throw ExecutionProblem("output_root_required", "An owner-selected output root is required")
            if (outputParent.startsWith(sources.root) || sources.root.startsWith(outputParent)) {
                throw ExecutionProblem("output_source_overlap", "Outputs must be separate from the source materialization")
            }
            Files.createDirectories(outputParent)
            if (outputParent.toRealPath().startsWith(sources.root.toRealPath())) {
                throw ExecutionProblem("output_source_overlap", "Output parent resolves into sources")
            }
            output = Files.createTempDirectory(outputParent, "provider-")
            outputIdentity = Files.readAttributes(output, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                .fileKey()?.toString() ?: throw ExecutionProblem("output_identity_unavailable", "Host lacks stable output directory identity")
            Files.createDirectories(output.resolve("tmp"))
            permit.prepareOutput(output)
            command = listOf(executable.toString()) + execution.arguments.map {
                it.replace("{snapshot}", sources.root.toString()).replace("{output}", output.toString())
            }
            if (command.any { '\u0000' in it || "{snapshot}" in it || "{output}" in it }) {
                throw ExecutionProblem("execution_arguments_invalid", "Invalid trusted execution arguments")
            }
        } catch (e: Exception) {
            return outcome("unavailable", problemCode(e, "snapshot_verification_failed"))
        }

        // Preparation and owner callbacks count against the budget too. A callback is
        // trusted/cooperative and cannot be forcibly time-bounded in this JVM; overrun
        // is still a failed attempt, never evidence of successful execution.
        if (cancellation.isCancelled()) return ProcessOutcome("cancelled", listOf("cancelled_before_execution"),
            outputDirectory = output, command = command, cancelled = true, metrics = AnalysisMetrics(elapsedMillis = elapsed(started)))
        val remainingMillis = execution.timeoutMillis - elapsed(started)
        if (remainingMillis <= 0) return ProcessOutcome("failed", listOf("execution_timeout"),
            outputDirectory = output, command = command, timedOut = true, metrics = AnalysisMetrics(elapsedMillis = elapsed(started)))

        // The installed, digest-verified native image owns and retains its actual
        // fork child through the final group signal. Java only exchanges framed
        // output and one-shot cancellation with it; it never signals a PID/PGID.
        val environment = mutableMapOf<String, String>()
        System.getenv("PATH")?.let { environment["PATH"] = it }
        System.getenv("SystemRoot")?.let { environment["SystemRoot"] = it }
        environment.putAll(execution.environment)
        environment["TMPDIR"] = output.resolve("tmp").toString()
        environment["TMP"] = output.resolve("tmp").toString()
        environment["TEMP"] = output.resolve("tmp").toString()
        val supervised = OwnedProcessSupervisor.run(command, sources.root, environment,
            remainingMillis, execution.maxOutputBytes, cancellation)
        val reasons = supervised.reasons.toMutableList()
        var status = supervised.status
        var cancelled = supervised.cancelled
        var timeout = supervised.timedOut
        try {
            sources.verify()
            permit.verifySnapshot()
            if (input.analysisKey() != permit.expectedAnalysisKey) {
                throw ExecutionProblem("analysis_descriptor_changed", "Authorized analysis descriptor changed during execution")
            }
            if (fileDigest(executable) != execution.executableDigest) {
                throw ExecutionProblem("executable_changed", "Selected executable changed during execution")
            }
        } catch (e: Exception) {
            status = "failed"
            reasons += problemCode(e, "snapshot_changed_during_execution")
        }
        var out = ""
        var err = ""
        try { out = strictUtf8(supervised.stdout) }
        catch (_: Exception) { status = "failed"; reasons += "process_output_invalid_utf8" }
        try { err = strictUtf8(supervised.stderr) }
        catch (_: Exception) { status = "failed"; reasons += "process_output_invalid_utf8" }
        if (cancellation.isCancelled()) {
            cancelled = true
            // A late cancellation cannot erase a protocol/cleanup/snapshot error.
            if (status == "executed" || status == "cancelled") status = "cancelled"
            reasons += "execution_cancelled"
        } else if (elapsed(started) >= execution.timeoutMillis) {
            timeout = true
            status = "failed"
            reasons += "execution_timeout"
        }
        return ProcessOutcome(
            status = status,
            reasons = reasons.distinct(),
            exitCode = supervised.exitCode,
            stdout = out,
            stderr = err,
            outputDirectory = output,
            command = command,
            cancelled = cancelled,
            timedOut = timeout,
            truncated = supervised.truncated,
            metrics = AnalysisMetrics(elapsedMillis = elapsed(started), outputBytes = supervised.outputBytes),
            outputIdentity = outputIdentity,
            supervisorIdentity = supervised.supervisorIdentity,
            executionConfirmed = supervised.executionConfirmed,
        )
    }
}

private fun readReport(directory: SecureDirectoryStream<Path>, parts: List<Path>, maxBytes: Int): ByteArray {
    if (parts.size > 1) {
        directory.newDirectoryStream(parts.first(), NOFOLLOW_LINKS).use {
            return readReport(it, parts.drop(1), maxBytes)
        }
    }
    val leaf = parts.single()
    val attributes = directory.getFileAttributeView(leaf, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()
    if (!attributes.isRegularFile || attributes.size() > maxBytes) {
        throw ExecutionProblem("report_not_regular_or_oversized", "Report is not a bounded regular file")
    }
    directory.newByteChannel(leaf, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
        val bytes = ByteArrayOutputStream()
        val buffer = java.nio.ByteBuffer.allocate(8192)
        while (true) {
            val count = channel.read(buffer)
            if (count < 0) break
            if (bytes.size().toLong() + count > maxBytes) throw ExecutionProblem("report_limit", "Provider report exceeds the bound")
            bytes.write(buffer.array(), 0, count)
            buffer.clear()
        }
        return bytes.toByteArray()
    }
}

fun strictUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(java.nio.ByteBuffer.wrap(bytes)).toString()

/** Source locations are resolved only against the frozen manifest, never live workspace files. */
class SnapshotSources(private val input: AnalysisInput) {
    val root: Path = Path.of(input.materializedRoot).toAbsolutePath().normalize()
    private val files: Map<String, SnapshotFile> = input.snapshot.files.associateBy { it.path }

    fun verify() {
        if (!root.isAbsolute || !Files.isDirectory(root, NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            throw ExecutionProblem("snapshot_root_invalid", "Materialized root is not an isolated directory")
        }
        if (!Digests.valid(input.snapshot.snapshotId) ||
            input.snapshot.captureKind !in setOf("immutable_overlay", "coordinated_capture")) {
            throw ExecutionProblem("snapshot_provenance_unavailable", "A registered coherent snapshot is required")
        }
        if (files.size != input.snapshot.files.size) {
            throw ExecutionProblem("snapshot_duplicate_path", "Duplicate source paths")
        }
        for ((path, file) in files) {
            val resolved = root.resolve(safeRelativePath(path)).normalize()
            if (!Files.isRegularFile(resolved) || !resolved.toRealPath().startsWith(root.toRealPath()) ||
                !Digests.valid(file.digest) || Files.size(resolved) != file.size || fileDigest(resolved) != file.digest) {
                throw ExecutionProblem("snapshot_content_mismatch", "Materialized source differs from its immutable manifest")
            }
        }
    }

    fun location(path: String, line: Int? = null, column: Int? = null,
                 endLine: Int? = null, endColumn: Int? = null): SourceLocation {
        val reported = if (path.startsWith("file:")) {
            try { Path.of(java.net.URI(path)) } catch (_: Exception) {
                throw ExecutionProblem("location_invalid", "Invalid provider source URI")
            }
        } else Path.of(path)
        val rel = if (reported.isAbsolute) {
            val normalized = reported.normalize()
            if (!normalized.startsWith(root)) throw ExecutionProblem("location_outside_snapshot", "Location is not in immutable sources")
            root.relativize(normalized).toString().replace('\\', '/')
        } else safeRelativePath(path).toString().replace('\\', '/')
        val file = files[rel] ?: throw ExecutionProblem("location_not_in_manifest", "Provider location is absent from captured scope")
        // The contract has no nullable range. A location with no provider range is a
        // documented file anchor, not a fabricated text excerpt (e.g. a PNG resource).
        if (listOf(line, column, endLine, endColumn).all { it == null }) {
            return SourceLocation(rel, file.digest, excerpt = "")
        }
        if (line == null || (endColumn != null && endLine == null)) {
            throw ExecutionProblem("location_range_incomplete", "Provider supplied an incomplete text range")
        }
        val source = try { strictUtf8(Files.readAllBytes(root.resolve(rel))) } catch (_: Exception) {
            throw ExecutionProblem("location_source_not_utf8", "Text range requires captured UTF-8 source")
        }
        val lines = source.split('\n')
        val startLine = line ?: 1
        val startColumn = column ?: 0
        val lastLine = endLine ?: startLine
        val lastColumn = endColumn ?: startColumn
        if (startLine < 1 || startLine > lines.size || lastLine !in startLine..lines.size ||
            startColumn < 0 || startColumn > lines[startLine - 1].removeSuffix("\r").length ||
            lastColumn < 0 || lastColumn > lines[lastLine - 1].removeSuffix("\r").length ||
            (lastLine == startLine && lastColumn < startColumn)) {
            throw ExecutionProblem("location_range_invalid", "Provider source range is invalid")
        }
        return SourceLocation(rel, file.digest, startLine, startColumn, lastLine, lastColumn,
            lines[startLine - 1].removeSuffix("\r").take(240))
    }
}

fun safeRelativePath(value: String): Path {
    if (value.isBlank() || '\u0000' in value || '\\' in value || value.startsWith("/") ||
        value.split('/').any { it == ".." || it == "." || it.isEmpty() } || Regex("^[A-Za-z]:").containsMatchIn(value)) {
        throw ExecutionProblem("relative_path_invalid", "Expected a normalized captured relative path")
    }
    val path = Path.of(value)
    if (path.isAbsolute) throw ExecutionProblem("relative_path_invalid", "Expected a relative path")
    return path
}

fun fileDigest(path: Path): String {
    val hash = java.security.MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { stream ->
        val bytes = ByteArray(8192)
        while (true) {
            val count = stream.read(bytes)
            if (count < 0) break
            hash.update(bytes, 0, count)
        }
    }
    return "sha256:" + hash.digest().joinToString("") { "%02x".format(it) }
}

fun problemCode(error: Exception, fallback: String): String =
    if (error is ExecutionProblem) error.code else fallback

private fun elapsed(start: Long): Long = (System.nanoTime() - start) / 1_000_000

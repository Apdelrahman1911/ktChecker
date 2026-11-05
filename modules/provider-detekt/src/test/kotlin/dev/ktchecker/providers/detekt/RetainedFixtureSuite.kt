package dev.ktchecker.providers.detekt

import dev.ktchecker.contracts.AnalysisInput
import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.ProviderResult
import dev.ktchecker.providers.runtime.IsolatedProcessRunner
import dev.ktchecker.providers.runtime.OwnedSupervisorIdentity
import dev.ktchecker.providers.runtime.ProcessOutcome
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestWatcher

/**
 * Test-only ownership, not native cleanup or build/publication authority. Initial directory
 * provisioning requires a cooperative namespace: Java has no SecureDirectoryStream mkdir-at.
 * Subsequent writes are flat, anchored and CREATE_NEW. Before/after identity checks detect
 * replacement, not arbitrary namespace/ABA attacks. No job or ancestor is cleaned or chmodded.
 */
internal class RetainedFixtureSuite(
    private val providerId: String,
    private val setupGate: (Job, String) -> Unit = { _, _ -> },
    private val ioGate: (Job, String, String) -> Unit = { _, _, _ -> },
    private val channelDecorator: (SeekableByteChannel) -> SeekableByteChannel = { it },
    private val checkpointGate: (JsonObject) -> Unit = {},
) : AutoCloseable {
    internal data class Counts(val checked: Int = 0, val native: Int = 0, val denial: Int = 0, val abstention: Int = 0)

    /** Paths and suite binding never change, even if startup fails before creating them. */
    internal class Job internal constructor(
        internal val suite: RetainedFixtureSuite,
        val methodId: String?,
        val repository: Path,
        val runDirectory: Path,
        val scratchDirectory: Path,
        val artifactDirectory: Path,
        internal val directoryKeys: MutableMap<Path, String>,
    ) {
        internal var runCreated = false
        internal var scratchCreated = false
        internal var artifactsCreated = false
        internal var initialized = false
        internal var sequence = 0
        internal var eventBytes = 0L
        internal var artifactBytes = 0L
        internal var artifactCount = 0
        internal var candidateCount = 0
        internal var candidateBytes = 0L
        internal var counts = Counts()
        internal var methodChecked = false
        @Volatile internal var quarantined = false
        @Volatile internal var custody = "not_yet_verified"
        internal var custodyObservedAt: String? = null
        internal val references = mutableListOf<JsonObject>()
    }

    internal class Ticket internal constructor(val job: Job, val ordinal: Int, val kind: String) {
        internal var observed: ProcessOutcome? = null
        internal var callbacks = 0
    }

    @Volatile private var ownerThread: Thread? = null
    private val ownerLock = Any()
    @Volatile private var current: Job? = null
    private var last: Job? = null
    private var pending: Ticket? = null
    private var invoking: Ticket? = null
    private var callbackActive = false
    private var writing = false
    @Volatile private var halted = false
    @Volatile private var closed = false

    /** A foreign/reentrant event only stops admission; it must not initiate path I/O. */
    internal fun rejectWithoutIo(job: Job?, message: String): Nothing {
        halted = true
        job?.let { it.quarantined = true; it.custody = "unknown" }
        throw IllegalStateException(message)
    }

    internal fun ownerAccess(job: Job? = current) {
        synchronized(ownerLock) {
            if (ownerThread == null) ownerThread = Thread.currentThread()
            if (Thread.currentThread() !== ownerThread) rejectWithoutIo(job, "Foreign fixture thread")
        }
        if (writing) rejectWithoutIo(job, "Reentrant fixture evidence operation")
        if (job != null && job.suite !== this) rejectWithoutIo(null, "Foreign fixture job")
    }

    internal fun methodBoundary() {
        ownerAccess()
        checkAdmission()
        if (current != null || pending != null || invoking != null || callbackActive) {
            rejectWithoutIo(current, "Previous fixture method is not checked")
        }
    }

    fun startJob(repository: Path, destination: Path, prefix: String, methodId: String? = null, allocated: (Job) -> Unit = {}): Job {
        methodBoundary()
        var job: Job? = null
        try {
            require(providerId in setOf("detekt", "ktlint") && prefix.matches(Regex("[a-z0-9-]+")))
            val root = repository.toAbsolutePath().normalize()
            val selected = destination.toAbsolutePath().normalize()
            require(selected.startsWith(root) && selected != root) { "Retained evidence must be inside the repository" }
            val keys = linkedMapOf<Path, String>()
            fun remember(directory: Path) {
                val attrs = Files.readAttributes(directory, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                require(attrs.isDirectory && !attrs.isSymbolicLink) { "Retained ancestor is not a literal directory" }
                keys[directory] = fileKey(attrs)
            }
            remember(root)
            fun ensure(directory: Path) {
                verifyDirectories(keys)
                if (!Files.exists(directory, NOFOLLOW_LINKS)) Files.createDirectory(directory, PRIVATE_DIRECTORY)
                remember(directory)
                verifyDirectories(keys)
            }
            var ancestor = root
            for (part in root.relativize(selected)) { ancestor = ancestor.resolve(part); ensure(ancestor) }
            val runs = selected.resolve("runs")
            ensure(runs)
            val run = runs.resolve("${Instant.now().toString().replace(':', '-')}-${UUID.randomUUID()}-$prefix-native-preview1")
            require(!Files.exists(run, NOFOLLOW_LINKS)) { "Retained jobs are one-shot and may not be reused" }
            val created = Job(this, methodId, root, run, run.resolve("scratch"), run.resolve("evidence-artifacts"), keys)
            job = created
            current = created
            last = created
            allocated(created) // Bind the method before any fallible new-job directory/checkpoint I/O.
            checkAdmission()
            Files.createDirectory(run, PRIVATE_DIRECTORY)
            remember(run)
            created.runCreated = true
            verifyJob(created)
            setupGate(created, "run_created_before_scratch")
            checkAdmission()
            verifyJob(created)
            Files.createDirectory(created.scratchDirectory, PRIVATE_DIRECTORY)
            remember(created.scratchDirectory)
            created.scratchCreated = true
            verifyJob(created)
            setupGate(created, "scratch_created_before_artifacts")
            checkAdmission()
            verifyJob(created)
            Files.createDirectory(created.artifactDirectory, PRIVATE_DIRECTORY)
            remember(created.artifactDirectory)
            created.artifactsCreated = true
            verifyJob(created)
            for (directory in listOf(run, created.scratchDirectory, created.artifactDirectory)) {
                require(Files.getPosixFilePermissions(directory, NOFOLLOW_LINKS) == PRIVATE_PERMISSIONS)
            }
            created.initialized = true
            emit(created, "retained_private_job_created")
            return created
        } catch (cause: Throwable) {
            failed(cause, job)
            throw cause
        }
    }

    /** Persist unsettled ownership before entering even an apparently refusing provider. */
    internal fun begin(kind: String): Ticket = begin(checkNotNull(current) { "No retained fixture job" }, kind)

    private fun begin(job: Job, kind: String): Ticket = guarded(job) {
        if (current !== job || !job.initialized || pending != null || invoking != null || callbackActive) {
            rejectWithoutIo(job, "A return, callback or permit is not native cleanup or fresh admission")
        }
        require(kind in setOf("native", "denial", "abstention"))
        verifyJob(job)
        Ticket(job, job.counts.checked + 1, kind).also { ticket ->
            pending = ticket
            emit(job, "call_entered_ownership_unsettled")
        }
    }

    /** A synchronous callback is observed, never an admission release. */
    internal fun observe(ticket: Ticket, actual: ProcessOutcome) = guarded(ticket.job) {
        if (pending !== ticket || current !== ticket.job || ticket.callbacks != 0 || callbackActive) {
            rejectWithoutIo(ticket.job, "Unexpected, reentrant, late or duplicate runtime callback")
        }
        ticket.callbacks = 1
        ticket.observed = actual
        emit(ticket.job, "runtime_callback_not_admission_release")
    }

    private fun checkTicket(ticket: Ticket) {
        if (pending !== ticket || current !== ticket.job || invoking != null || callbackActive) {
            rejectWithoutIo(ticket.job, "Foreign, reentrant or already checked fixture call")
        }
    }

    internal fun checkedNative(ticket: Ticket, expected: OwnedSupervisorIdentity) = guarded(ticket.job) {
        checkTicket(ticket)
        val actual = checkNotNull(ticket.observed) { "Missing actual native completion callback" }
        check(ticket.kind == "native" && ticket.callbacks == 1 && actual.status == "executed" &&
            actual.reasons.isEmpty() && actual.executionConfirmed && actual.exitCode != null &&
            actual.supervisorIdentity == expected && !actual.cancelled && !actual.timedOut && !actual.truncated) {
            "Native ownership is not settled; exec confirmation alone is not cleanup"
        }
        val previous = ticket.job.counts
        release(ticket, "runtime_validated_owned_child_terminal", previous.copy(checked = previous.checked + 1, native = previous.native + 1))
    }

    internal fun checkedDenial(ticket: Ticket) = guarded(ticket.job) {
        checkTicket(ticket)
        val actual = checkNotNull(ticket.observed) { "Missing refusal-before-setup callback" }
        check(ticket.kind == "denial" && ticket.callbacks == 1 && actual.status == "unavailable" &&
            actual.reasons == listOf("execution_not_authorized") && !actual.executionConfirmed &&
            actual.supervisorIdentity == null && actual.exitCode == null && actual.outputDirectory == null &&
            actual.command.isEmpty() && actual.stdout.isEmpty() && actual.stderr.isEmpty() &&
            !actual.cancelled && !actual.timedOut && !actual.truncated && actual.metrics.outputBytes == 0L &&
            actual.metrics.cpuMillis == null && actual.metrics.peakRssBytes == null) {
            "False exec confirmation is not a complete authorization refusal before setup"
        }
        val previous = ticket.job.counts
        release(ticket, "exact_authorization_refusal_before_setup", previous.copy(checked = previous.checked + 1, denial = previous.denial + 1))
    }

    internal fun checkedAbstention(ticket: Ticket, input: AnalysisInput, result: ProviderResult) = guarded(ticket.job) {
        checkTicket(ticket)
        val requested = input.requiredRuleIds.toSet()
        check(ticket.kind == "abstention" && ticket.callbacks == 0 && ticket.observed == null &&
            requested.isNotEmpty() && result.providerId == providerId && result.status == "partial" &&
            result.findings.isEmpty() && result.rawDiagnostics.isEmpty() && result.coverage.size == requested.size &&
            result.coverage.map { it.ruleId }.toSet() == requested && result.coverage.all {
                it.context == input.context.scope && it.state == "unsupported" && it.provenance == "not_executed" &&
                    !it.implemented && !it.tested && !it.qualified && it.certificateId == null
            }) { "Unsupported tuple must truthfully abstain without any runtime callback" }
        val previous = ticket.job.counts
        release(ticket, "provider_abstention_without_runtime_callback", previous.copy(checked = previous.checked + 1, abstention = previous.abstention + 1))
    }

    fun <T> native(expected: OwnedSupervisorIdentity, onOutcome: (ProcessOutcome) -> Unit = {},
                   operation: (IsolatedProcessRunner) -> T): T = native(checkNotNull(current), expected, onOutcome, operation)

    internal fun <T> native(job: Job, expected: OwnedSupervisorIdentity, onOutcome: (ProcessOutcome) -> Unit = {},
                           operation: (IsolatedProcessRunner) -> T): T = invoke(job, "native", onOutcome, operation) { ticket, _ ->
        checkedNative(ticket, expected)
    }

    fun <T> denied(onOutcome: (ProcessOutcome) -> Unit = {}, operation: (IsolatedProcessRunner) -> T): T =
        denied(checkNotNull(current), onOutcome, operation)

    internal fun <T> denied(job: Job, onOutcome: (ProcessOutcome) -> Unit = {}, operation: (IsolatedProcessRunner) -> T): T =
        invoke(job, "denial", onOutcome, operation) { ticket, _ -> checkedDenial(ticket) }

    fun abstained(input: AnalysisInput, onOutcome: (ProcessOutcome) -> Unit = {},
                  operation: (IsolatedProcessRunner) -> ProviderResult): ProviderResult =
        abstained(checkNotNull(current), input, onOutcome, operation)

    internal fun abstained(job: Job, input: AnalysisInput, onOutcome: (ProcessOutcome) -> Unit = {},
                           operation: (IsolatedProcessRunner) -> ProviderResult): ProviderResult =
        invoke(job, "abstention", onOutcome, operation) { ticket, result -> checkedAbstention(ticket, input, result) }

    private fun <T> invoke(job: Job, kind: String, onOutcome: (ProcessOutcome) -> Unit,
                           operation: (IsolatedProcessRunner) -> T, checked: (Ticket, T) -> Unit): T = guarded(job) {
        val ticket = begin(job, kind)
        invoking = ticket
        val result = try {
            operation(IsolatedProcessRunner { actual ->
                ownerAccess(job)
                checkAdmission()
                if (invoking !== ticket) rejectWithoutIo(job, "Late runtime callback after operation return")
                observe(ticket, actual)
                callbackActive = true
                try { onOutcome(actual) } finally { callbackActive = false }
            })
        } finally { invoking = null }
        checked(ticket, result)
        result
    }

    private fun release(ticket: Ticket, signal: String, next: Counts) {
        emit(ticket.job, signal, proposed = countsJson(next))
        checkAdmission()
        // No committed counters change until write, close and post-write custody checks all succeed.
        ticket.job.counts = next
        pending = null
    }

    fun finishJob(): JsonObject = finishJob(checkNotNull(current))

    internal fun finishJob(job: Job): JsonObject = guarded(job) {
        if (current !== job || pending != null || invoking != null || callbackActive) {
            rejectWithoutIo(job, "Cannot finish with missing or uncertain ownership")
        }
        check(job.counts.checked > 0) { "No checked fixture calls" }
        emit(job, "method_assertions_checked_scratch_still_retained", proposed = buildJsonObject {
            put("method_assertions_checked", true)
        })
        checkAdmission()
        job.methodChecked = true
        current = null
        summary(job)
    }

    /** Exact job only: never use the previous method as a failure-publication fallback. */
    fun failed(cause: Throwable, job: Job? = current) {
        if (job != null && job.suite !== this) { halted = true; return }
        if (Thread.currentThread() !== ownerThread || writing) {
            halted = true
            job?.let { it.quarantined = true; it.custody = "unknown" }
            return
        }
        job?.quarantined = true
        if (halted || closed) { halted = true; return }
        halted = true
        if (job == null || job.suite !== this || !job.runCreated || job.custody == "unknown") return
        try { emit(job, "quarantined_no_automatic_cleanup", cause.javaClass.name, failureRecord = true) }
        catch (checkpoint: Throwable) { if (checkpoint !== cause) cause.addSuppressed(checkpoint) }
    }

    fun summary(): JsonObject { ownerAccess(); return summary(current ?: checkNotNull(last)) }

    internal fun summary(job: Job): JsonObject {
        ownerAccess(job)
        return summaryValue(job)
    }

    private fun summaryValue(job: Job): JsonObject = buildJsonObject {
        put("provider_id", providerId)
        put("run_id", job.runDirectory.fileName.toString())
        job.methodId?.let { put("junit_method_id", it) }
        put("run_last_known_path", relative(job, job.runDirectory))
        put("scratch_last_known_path", relative(job, job.scratchDirectory))
        put("artifacts_last_known_path", relative(job, job.artifactDirectory))
        put("run_created", job.runCreated); put("scratch_created", job.scratchCreated)
        put("artifact_directory_created", job.artifactsCreated); put("job_initialized", job.initialized)
        put("retention_policy", "retain_created_job_scratch_and_ancestors")
        put("automatic_cleanup", false); put("automatic_permission_restoration", false)
        put("custody_observation", job.custody); put("paths_are_last_known_not_current_filesystem_proof", true)
        job.custodyObservedAt?.let { put("custody_last_verified_at", it) }
        put("quarantined", job.quarantined); put("suite_admission_stopped", halted)
        put("ownership_unsettled", pending?.job === job)
        put("checked_calls", job.counts.checked); put("native_terminals", job.counts.native)
        put("refusal_terminals", job.counts.denial); put("no_callback_abstentions", job.counts.abstention)
        put("committed_state_requires_completed_checkpoint_write_close_and_validation", true)
        put("method_assertions_checked", job.methodChecked); put("qualified", false)
        put("artifact_count", job.artifactCount); put("artifact_bytes", job.artifactBytes)
        put("sidecar_candidate_count", job.candidateCount); put("sidecar_candidate_bytes", job.candidateBytes)
        put("references", JsonArray(job.references.toList()))
        put("note", "Retention policy and last observations only; not fresh filesystem/PID proof, JUnit/build success, a publication commit or cleanup authorization.")
    }

    internal fun artifact(job: Job, logicalName: String, bytes: ByteArray): JsonObject = guarded(job) {
        requireActive(job)
        require(bytes.size <= 67_108_864 && job.artifactCount < 4096 && job.artifactBytes + bytes.size <= 268_435_456L) {
            "Retained artifact bound exceeded"
        }
        val name = artifactName(logicalName)
        writeAnchored(job, job.artifactDirectory, name, bytes)
        checkAdmission()
        ++job.artifactCount
        job.artifactBytes += bytes.size
        buildJsonObject {
            put("logical_name", logicalName)
            put("path", relative(job, job.artifactDirectory.resolve(name)))
            put("sha256", Digests.sha256(bytes).removePrefix("sha256:"))
        }
    }

    internal fun sidecarCandidate(job: Job, name: String, sidecar: JsonObject): JsonObject = guarded(job) {
        requireActive(job)
        require(name.matches(Regex("[a-z0-9-]{1,80}")))
        require(sidecar["rules"]?.jsonArray?.all { rule ->
            rule.jsonObject["tested"]?.jsonPrimitive?.booleanOrNull == false &&
                rule.jsonObject["qualified"]?.jsonPrimitive?.booleanOrNull == false
        } == true) { "A retained candidate cannot claim tested or qualified publication" }
        val candidate = buildJsonObject {
            put("format", "ktchecker/fixture-sidecar-candidate/1")
            putCandidateFlags()
            put("provider_id", providerId); put("run_id", job.runDirectory.fileName.toString())
            put("sidecar", sidecar)
            put("note", "Run-scoped candidate only. A separate root publisher must verify the exact lifecycle, JUnit, build and generation tuple; tests never publish or withdraw live claims.")
        }.toString().toByteArray(Charsets.UTF_8)
        require(candidate.size <= 8_388_608 && job.candidateCount < 4 && job.candidateBytes + candidate.size <= 16_777_216L) {
            "Retained sidecar candidate bound exceeded"
        }
        val fileName = "sidecar-candidate-$name.json"
        writeAnchored(job, job.runDirectory, fileName, candidate)
        checkAdmission()
        ++job.candidateCount
        job.candidateBytes += candidate.size
        buildJsonObject {
            put("path", relative(job, job.runDirectory.resolve(fileName)))
            put("sha256", Digests.sha256(candidate).removePrefix("sha256:"))
        }
    }

    internal fun completion(job: Job, state: String) = guarded(job) {
        require(state in setOf("started-unfinished", "method-assertions-checked", "junit-method-success-observed"))
        if (state == "started-unfinished") requireActive(job)
        else if (!job.methodChecked || current != null || pending != null || invoking != null || callbackActive) {
            rejectWithoutIo(job, "A method observation is not a completed fixture job")
        }
        val event = buildJsonObject {
            put("state", state); putCandidateFlags()
            put("scratch_lifecycle", summaryValue(job))
            put("note", "Immutable method observation, never suite/build completion or live publication.")
        }.toString().toByteArray(Charsets.UTF_8)
        require(event.size <= 65_536)
        writeAnchored(job, job.runDirectory, "completion-$state.json", event)
    }

    private fun requireActive(job: Job) {
        if (current !== job || !job.initialized || job.methodChecked) rejectWithoutIo(job, "Stale or partially initialized fixture handle")
    }

    private fun emit(job: Job, phase: String, failureType: String? = null, proposed: JsonObject? = null, failureRecord: Boolean = false) {
        val event = buildJsonObject {
            put("phase", phase); put("sequence", job.sequence + 1); put("captured_at", Instant.now().toString())
            putCandidateFlags()
            put("scratch_lifecycle", summaryValue(job))
            proposed?.let { put("proposed_state_after_successful_write_close_and_validation", it) }
            failureType?.let { put("failure_type", it.take(512)) }
        }
        val bytes = event.toString().toByteArray(Charsets.UTF_8)
        require(job.sequence < 128 && bytes.size <= 65_536 && job.eventBytes + bytes.size <= 1_048_576) {
            "Retained lifecycle checkpoint bound exceeded"
        }
        val name = "scratch-lifecycle-${(job.sequence + 1).toString().padStart(4, '0')}.json"
        writeAnchored(job, job.runDirectory, name, bytes, failureRecord) { checkpointGate(event) }
        if (!failureRecord) checkAdmission()
        ++job.sequence
        job.eventBytes += bytes.size
        job.references += buildJsonObject {
            put("path", relative(job, job.runDirectory.resolve(name)))
            put("sha256", Digests.sha256(bytes).removePrefix("sha256:"))
        }
    }

    /** Every integration write funnels here; the selected directory and filename are anchored. */
    private fun writeAnchored(job: Job, directory: Path, name: String, bytes: ByteArray,
                              failureRecord: Boolean = false, beforeWrite: () -> Unit = {}) {
        ownerAccess(job)
        if (!failureRecord) checkAdmission()
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name)
        writing = true
        try {
            verifyJob(job)
            beforeWrite()
            ioGate(job, "before_open", name)
            if (!failureRecord) checkAdmission()
            verifyJob(job)
            Files.newDirectoryStream(directory).use { opened ->
                val secure = opened as? SecureDirectoryStream<Path> ?: error("Anchored fixture evidence I/O is unavailable")
                val attrs = secure.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes()
                require(attrs.isDirectory && fileKey(attrs) == job.directoryKeys.getValue(directory)) { "Opened evidence directory identity changed" }
                secure.newByteChannel(Path.of(name), setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS), PRIVATE_FILE).use { raw ->
                    // The outer use also closes the real channel if a synthetic decorator fails.
                    channelDecorator(raw).use { channel ->
                        val buffer = ByteBuffer.wrap(bytes)
                        while (buffer.hasRemaining()) channel.write(buffer)
                    }
                }
            }
            ioGate(job, "after_close", name)
            verifyJob(job)
            if (!failureRecord) checkAdmission()
        } catch (cause: Throwable) {
            // Never reopen a failed/rejected path from a watcher or a secondary failure writer.
            halted = true
            job.quarantined = true
            job.custody = "unknown"
            throw cause
        } finally { writing = false }
    }

    private fun verifyJob(job: Job) {
        if (job.custody == "unknown") error("Retained job custody is unknown; rejected paths must not be reopened")
        try {
            verifyDirectories(job.directoryKeys)
            job.custody = "verified_at_last_check"
            job.custodyObservedAt = Instant.now().toString()
        } catch (cause: Throwable) {
            job.custody = "unknown"
            job.quarantined = true
            halted = true
            throw cause
        }
    }

    internal fun isStopped(): Boolean { ownerAccess(); return halted }

    private fun checkAdmission() { check(!closed && !halted) { "Fixture suite stopped; no retry or new job is permitted" } }

    private inline fun <T> guarded(job: Job, operation: () -> T): T {
        ownerAccess(job)
        checkAdmission()
        return try { operation() } catch (cause: Throwable) { failed(cause, job); throw cause }
    }

    /** State flags only; never write, restore permissions, delete parents or settle native ownership. */
    override fun close() {
        ownerAccess()
        if (current != null || pending != null || invoking != null) { halted = true; current?.quarantined = true }
        closed = true
    }

    companion object {
        private val PRIVATE_PERMISSIONS = PosixFilePermissions.fromString("rwx------")
        private val PRIVATE_DIRECTORY = PosixFilePermissions.asFileAttribute(PRIVATE_PERMISSIONS)
        private val PRIVATE_FILE = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        internal fun artifactName(logicalName: String): String {
            require(logicalName.length in 1..4096 && '\\' !in logicalName && logicalName.none { it < ' ' || it == '\u007f' } &&
                logicalName.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "Invalid logical artifact name" }
            val label = logicalName.substringAfterLast('/').map { if (it.isLetterOrDigit() && it.code < 128 || it in "._-") it else '-' }.joinToString("").takeLast(80)
            return "artifact-${Digests.sha256(logicalName).removePrefix("sha256:")}-$label"
        }
        private fun relative(job: Job, path: Path) = job.repository.relativize(path).toString().replace('\\', '/')
        private fun countsJson(counts: Counts) = buildJsonObject {
            put("checked_calls", counts.checked); put("native_terminals", counts.native)
            put("refusal_terminals", counts.denial); put("no_callback_abstentions", counts.abstention)
        }
        private fun JsonObjectBuilder.putCandidateFlags() {
            put("run_complete", false); put("candidate_only", true); put("publication_committed", false)
            put("tested", false); put("qualified", false)
        }
        private fun fileKey(attrs: BasicFileAttributes): String = attrs.fileKey()?.toString()
            ?: error("Stable retained job filesystem identity is unavailable")
        private fun verifyDirectories(keys: Map<Path, String>) {
            for ((path, expected) in keys) {
                val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                require(attrs.isDirectory && !attrs.isSymbolicLink && fileKey(attrs) == expected) { "Retained job ancestor identity changed" }
            }
        }
    }
}

/** The actual ordinary integration adapter, also exercised without providers by the controls. */
internal class RetainedFixtureEvidence(private val suite: RetainedFixtureSuite) : BeforeEachCallback, TestWatcher, AutoCloseable {
    internal class Run internal constructor(val methodId: String, val job: RetainedFixtureSuite.Job, private val suite: RetainedFixtureSuite) {
        val runDirectory: Path get() = job.runDirectory
        val scratchDirectory: Path get() = job.scratchDirectory
        fun artifact(name: String, bytes: ByteArray): JsonObject = suite.artifact(job, name, bytes)
        fun sidecarCandidate(name: String, sidecar: JsonObject): JsonObject = suite.sidecarCandidate(job, name, sidecar)
        fun finishMethod() { suite.finishJob(job); suite.completion(job, "method-assertions-checked") }
        fun <T> native(expected: OwnedSupervisorIdentity, onOutcome: (ProcessOutcome) -> Unit = {},
                       operation: (IsolatedProcessRunner) -> T): T = suite.native(job, expected, onOutcome, operation)
        fun <T> denied(onOutcome: (ProcessOutcome) -> Unit = {}, operation: (IsolatedProcessRunner) -> T): T =
            suite.denied(job, onOutcome, operation)
        fun abstained(input: AnalysisInput, onOutcome: (ProcessOutcome) -> Unit = {},
                      operation: (IsolatedProcessRunner) -> ProviderResult): ProviderResult = suite.abstained(job, input, onOutcome, operation)
    }

    private class Attempt(val id: String) {
        var run: Run? = null // Assigned once, before any new-job filesystem setup.
        var observed = false
    }
    private var active: Attempt? = null
    private val attempts = linkedMapOf<String, Attempt>()
    private var evidenceFailure: Throwable? = null

    override fun beforeEach(context: ExtensionContext) {
        suite.ownerAccess()
        if (active != null && !active!!.observed) suite.rejectWithoutIo(active?.run?.job, "Missing prior JUnit method observation")
        suite.methodBoundary()
        if (attempts.size >= 128 || context.uniqueId in attempts) suite.rejectWithoutIo(null, "Duplicate or excessive fixture method identity")
        val attempt = Attempt(context.uniqueId)
        attempts[attempt.id] = attempt
        active = attempt // A pre-start failure now has no previous run to fall back to.
    }

    fun startRun(repository: Path, destination: Path, prefix: String): Run {
        suite.ownerAccess()
        val attempt = active ?: suite.rejectWithoutIo(null, "JUnit must establish this method identity before setup")
        if (attempt.observed || attempt.run != null) suite.rejectWithoutIo(attempt.run?.job, "A method cannot retry or replace its job")
        val job = suite.startJob(repository, destination, prefix, attempt.id) { created ->
            attempt.run = Run(attempt.id, created, suite)
        }
        val run = checkNotNull(attempt.run)
        check(run.job === job)
        suite.completion(job, "started-unfinished")
        return run
    }

    internal fun runFor(methodId: String): Run? { suite.ownerAccess(); return attempts[methodId]?.run }

    private fun attempt(context: ExtensionContext): Attempt {
        suite.ownerAccess()
        val selected = active
        if (selected == null || selected.id != context.uniqueId || selected.observed) {
            suite.rejectWithoutIo(null, "Foreign, late or duplicate JUnit method observation")
        }
        return selected
    }

    override fun testSuccessful(context: ExtensionContext) {
        val selected = attempt(context)
        try {
            val run = checkNotNull(selected.run) { "Successful integration method has no retained job" }
            suite.completion(run.job, "junit-method-success-observed")
        } catch (cause: Throwable) {
            evidenceFailure = cause
            suite.failed(cause, selected.run?.job)
        } finally { selected.observed = true }
    }

    override fun testFailed(context: ExtensionContext, cause: Throwable) {
        val selected = attempt(context)
        suite.failed(cause, selected.run?.job)
        selected.observed = true
    }

    override fun testAborted(context: ExtensionContext, cause: Throwable) {
        val selected = attempt(context)
        // An opt-in assumption before start owns no job/process. An abort after allocation is sticky.
        selected.run?.let { suite.failed(cause, it.job) }
        selected.observed = true
    }

    override fun close() {
        suite.ownerAccess()
        val missingObservation = attempts.values.any { !it.observed }
        suite.close() // No filesystem writes, live sidecar withdrawal, cleanup or permission changes.
        evidenceFailure?.let { throw IllegalStateException("Fixture JUnit evidence checkpoint failed", it) }
        check(!missingObservation) { "Fixture suite closed without every JUnit method observation" }
        check(!suite.isStopped()) { "Fixture suite is quarantined or incomplete; retained candidates cannot be published" }
    }
}

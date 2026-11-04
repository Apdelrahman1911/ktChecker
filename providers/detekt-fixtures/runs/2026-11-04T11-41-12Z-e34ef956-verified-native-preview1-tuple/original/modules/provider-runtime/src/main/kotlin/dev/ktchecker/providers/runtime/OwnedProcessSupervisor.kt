package dev.ktchecker.providers.runtime

import dev.ktchecker.contracts.CancellationToken
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

internal data class SupervisedProcessResult(
    val status: String,
    val reasons: List<String> = emptyList(),
    val exitCode: Int? = null,
    val stdout: ByteArray = byteArrayOf(),
    val stderr: ByteArray = byteArrayOf(),
    val outputBytes: Long = 0,
    val cancelled: Boolean = false,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
    val supervisorIdentity: OwnedSupervisorIdentity? = null,
    val executionConfirmed: Boolean = false,
)

/**
 * A bundled native helper alone owns its unreaped child leader and group. Java never derives a
 * process group from a PID, signals a group, enumerates descendants, or destroys the helper.
 * Each invocation gets new private executable bytes and new one-shot pipes. There is no reconnect,
 * replay, shared cancellation channel or substitute launcher. CPU/RSS are deliberately not claimed:
 * preview-1's wire format does not measure them; the caller measures its full wall-clock operation.
 * The helper and target inherit the same exact approved environment. LD_ and DYLD_ prefixes and
 * GLIBC_TUNABLES/GCONV_PATH/LOCPATH are therefore unavailable, not silently stripped: their loader
 * or libc module overrides would undermine the helper's bundled identity before main(). A future
 * separate target-environment protocol needs an explicit revision. This does not attest the
 * broker JVM, OS libraries, loaded memory or hostile same-user execution.
 */
internal object OwnedProcessSupervisor {
    private val slots = SupervisorSlots(4)

    fun run(
        command: List<String>,
        directory: Path,
        environment: Map<String, String>,
        timeoutMillis: Long,
        maxOutputBytes: Int,
        cancellation: CancellationToken,
    ): SupervisedProcessResult = SupervisorTransport(slots = slots).run(
        command, directory, environment, timeoutMillis, maxOutputBytes, cancellation,
    )
}

/** A deliberately signal-free interface: tests cannot accidentally exercise numeric PID cleanup. */
internal interface SupervisorChannel {
    val frames: InputStream
    val diagnostics: InputStream
    val control: OutputStream
    fun alive(): Boolean
    fun exitCode(): Int?
    fun awaitExit(millis: Long): Boolean
}

internal fun interface SupervisorLauncher {
    fun start(image: SupervisorImageHandle, command: List<String>, directory: Path,
              environment: Map<String, String>, timeoutMillis: Long, maxOutputBytes: Int): SupervisorChannel
}

private object InstalledSupervisorLauncher : SupervisorLauncher {
    override fun start(image: SupervisorImageHandle, command: List<String>, directory: Path,
                       environment: Map<String, String>, timeoutMillis: Long, maxOutputBytes: Int): SupervisorChannel {
        image.verify()
        val arguments = listOf(image.executable.toString(), "--ktc-owned-child-v1", timeoutMillis.toString(),
            maxOutputBytes.toString(), "--") + command
        val builder = ProcessBuilder(arguments).directory(directory.toFile())
        builder.environment().clear()
        builder.environment().putAll(environment)
        val process = builder.start()
        return object : SupervisorChannel {
            override val frames = process.inputStream
            override val diagnostics = process.errorStream
            override val control = process.outputStream
            override fun alive() = process.isAlive
            override fun exitCode(): Int? = if (process.isAlive) null else process.exitValue()
            override fun awaitExit(millis: Long): Boolean = process.waitFor(millis, TimeUnit.MILLISECONDS)
        }
    }
}

/**
 * Bounds invocation groups within this JVM, not the number of OS processes. Missing cleanup ACK
 * permanently poisons a slot for this JVM lifetime, even after the helper/workers exit. Restarting
 * does not prove cleanup; durable owner reconciliation and forced helper death remain unqualified.
 */
internal class SupervisorSlots(capacity: Int) {
    private val available = Semaphore(capacity.also { require(it in 1..16) }, true)
    fun acquire(): SupervisorSlot? = if (available.tryAcquire()) SupervisorSlot { available.release() } else null
    internal fun remaining(): Int = available.availablePermits()
}

internal class SupervisorSlot(private val release: () -> Unit) {
    private val returned = AtomicBoolean()
    fun close() { if (returned.compareAndSet(false, true)) release() }
}

internal object SupervisorFlags {
    const val EXIT_OBSERVED = 1
    const val LEADER_REAPED = 2
    const val QUIESCENT = 4
    const val GROUP_READY = 8
    const val EXEC_CONFIRMED = 16
    const val CANCELLED = 32
    const val TIMED_OUT = 64
    const val OUTPUT_LIMIT = 128
    const val PARENT_LOST = 256
    const val IO_FAILED = 512
    const val EXEC_FAILED = 1024
    const val PROTOCOL_FAILED = 2048
    const val OWNERSHIP_LOST = 4096
    const val SHUTDOWN_FAILED = 8192
    const val KNOWN = 0x3fff
    const val COMPLETED = 0x1f
}

internal data class SupervisorTerminal(
    val cause: Int,
    val flags: Int,
    val childExit: Int?,
    val observedOutput: Long,
    val capturedOutput: Long,
) {
    fun has(flag: Int): Boolean = flags and flag != 0
}

internal data class SupervisorWireSnapshot(
    val stdout: ByteArray,
    val stderr: ByteArray,
    val captured: Long,
    val terminal: SupervisorTerminal?,
    val eof: Boolean,
    val problem: String?,
)

/** Exact binary framing. Output is opaque bytes; the provider adapter applies strict UTF-8. */
internal class SupervisorFrameReader(private val maximumOutput: Int) {
    private val lock = Any()
    private val stdout = ByteArrayOutputStream()
    private val stderr = ByteArrayOutputStream()
    private var captured = 0L
    private var terminal: SupervisorTerminal? = null
    private var eof = false
    private var problem: String? = null
    val done = AtomicBoolean()

    init { require(maximumOutput in 1..16_777_216) }

    fun snapshot(): SupervisorWireSnapshot = synchronized(lock) {
        SupervisorWireSnapshot(stdout.toByteArray(), stderr.toByteArray(), captured, terminal, eof, problem)
    }

    fun problem(): String? = synchronized(lock) { problem }

    fun read(input: InputStream) {
        try {
            if (!readExact(input, 8).contentEquals("KTCOWN1\n".toByteArray(Charsets.US_ASCII))) invalidWire()
            while (true) {
                val tag = input.read()
                if (tag < 0) {
                    synchronized(lock) {
                        eof = true
                        if (terminal == null) invalidWire()
                    }
                    break
                }
                if (synchronized(lock) { terminal != null }) invalidWire()
                val length = ByteBuffer.wrap(readExact(input, 4)).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
                when (tag) {
                    1, 2 -> {
                        if (length !in 1L..8192L || length > maximumOutput - synchronized(lock) { captured }) invalidWire()
                        val bytes = readExact(input, length.toInt())
                        synchronized(lock) {
                            if (tag == 1) stdout.write(bytes) else stderr.write(bytes)
                            captured += length
                        }
                    }
                    3 -> {
                        if (length != 32L) invalidWire()
                        val data = ByteBuffer.wrap(readExact(input, 32)).order(ByteOrder.BIG_ENDIAN)
                        val cause = data.int
                        val flags = data.int
                        val exit = data.int
                        val reserved = data.int
                        val observed = data.long
                        val reportedCaptured = data.long
                        val received = synchronized(lock) { captured }
                        if (cause !in 0..10 || flags and SupervisorFlags.KNOWN.inv() != 0 || exit !in -1..255 || reserved != 0 ||
                            observed < 0 || reportedCaptured != received || observed < reportedCaptured ||
                            (flags and SupervisorFlags.EXIT_OBSERVED != 0) != (exit >= 0) ||
                            (cause == 0 && (flags != SupervisorFlags.COMPLETED || exit < 0 || observed != reportedCaptured))) {
                            invalidWire()
                        }
                        val final = SupervisorTerminal(cause, flags, exit.takeIf { it >= 0 }, observed, reportedCaptured)
                        validateSupervisorFinal(final)
                        synchronized(lock) { terminal = final }
                    }
                    else -> invalidWire()
                }
            }
        } catch (_: InvalidSupervisorWire) {
            synchronized(lock) { problem = "process_supervisor_protocol_error" }
        } catch (_: Exception) {
            synchronized(lock) { problem = "process_output_unreliable" }
        } finally { done.set(true) }
    }

    private fun readExact(input: InputStream, size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        var emptyReads = 0
        while (offset < size) {
            val read = input.read(result, offset, size - offset)
            if (read < 0) invalidWire()
            if (read == 0) {
                if (++emptyReads > 16) invalidWire()
            } else { offset += read; emptyReads = 0 }
        }
        return result
    }
}

private class InvalidSupervisorWire : Exception()
private fun invalidWire(): Nothing = throw InvalidSupervisorWire()

/** Cross-field facts of preview-1; invalid FINAL never establishes trusted exit/exec evidence. */
private fun validateSupervisorFinal(final: SupervisorTerminal) {
    val required = when (final.cause) {
        1 -> SupervisorFlags.CANCELLED
        2 -> SupervisorFlags.TIMED_OUT
        3 -> SupervisorFlags.OUTPUT_LIMIT
        4 -> SupervisorFlags.PARENT_LOST
        5 -> SupervisorFlags.IO_FAILED
        6 -> SupervisorFlags.EXEC_FAILED
        8 -> SupervisorFlags.SHUTDOWN_FAILED
        9 -> SupervisorFlags.PROTOCOL_FAILED
        10 -> SupervisorFlags.OWNERSHIP_LOST or SupervisorFlags.SHUTDOWN_FAILED
        else -> 0
    }
    if ((final.flags and required) != required) invalidWire()
    if (final.has(SupervisorFlags.EXEC_CONFIRMED) && (!final.has(SupervisorFlags.GROUP_READY) ||
        !final.has(SupervisorFlags.EXIT_OBSERVED) || final.has(SupervisorFlags.EXEC_FAILED) || final.childExit == 126)) invalidWire()
    if (final.has(SupervisorFlags.EXEC_FAILED) && (!final.has(SupervisorFlags.GROUP_READY) ||
        final.has(SupervisorFlags.EXEC_CONFIRMED))) invalidWire()
    if (final.has(SupervisorFlags.OWNERSHIP_LOST) && (!final.has(SupervisorFlags.SHUTDOWN_FAILED) ||
        final.has(SupervisorFlags.QUIESCENT))) invalidWire()
    val lifecycle = SupervisorFlags.GROUP_READY or SupervisorFlags.EXIT_OBSERVED or
        SupervisorFlags.LEADER_REAPED or SupervisorFlags.EXEC_CONFIRMED
    if (final.has(SupervisorFlags.QUIESCENT) && (final.flags and lifecycle) != 0 &&
        (!final.has(SupervisorFlags.EXIT_OBSERVED) || !final.has(SupervisorFlags.LEADER_REAPED))) invalidWire()
    if (final.cause == 7 && final.has(SupervisorFlags.EXEC_CONFIRMED)) invalidWire()
}

/**
 * Bounds helper lifetimes with its independent native deadline plus a four-second transport grace.
 * Cancellation/error sends a single C and EOF, then still requires FINAL + EOF + helper exit zero.
 * If cleanup cannot be acknowledged, return failure and poison that slot for this JVM lifetime;
 * a later helper exit or ACK cannot reclaim it. Never kill it or use a PATH substitute launcher.
 * The cap bounds unresolved invocation groups, not OS processes; restart is not cleanup evidence.
 * Durable owner reconciliation is not implemented and forced supervisor death is not qualified.
 * Trusted Java callbacks and filesystem calls remain cooperative, not forcibly preemptible.
 */
internal class SupervisorTransport(
    private val prepare: () -> SupervisorImageHandle = { SupervisorImageLoader().load().extract() },
    private val launcher: SupervisorLauncher = InstalledSupervisorLauncher,
    private val slots: SupervisorSlots = SupervisorSlots(4),
    private val cleanupGraceMillis: Long = 4000,
) {
    init { require(cleanupGraceMillis in 1..4000) }

    fun run(command: List<String>, directory: Path, environment: Map<String, String>, timeoutMillis: Long,
            maxOutputBytes: Int, cancellation: CancellationToken): SupervisedProcessResult {
        val started = System.nanoTime()
        val detachedCommand: List<String>
        val detachedEnvironment: Map<String, String>
        try {
            if (cancellation.isCancelled()) return SupervisedProcessResult("cancelled", listOf("cancelled_before_execution"), cancelled = true)
            if (timeoutMillis !in 1..3_600_000 || maxOutputBytes !in 1..16_777_216) {
                return SupervisedProcessResult("unavailable", listOf("execution_limits_invalid"))
            }
            // Check collection bounds before allocating detached copies; recheck after copying too.
            require(command.size in 1..4096 && environment.size <= 4096)
            detachedCommand = command.toList()
            detachedEnvironment = environment.toMap()
            validateSupervisorArguments(detachedCommand, directory, detachedEnvironment)
            if (detachedEnvironment.keys.any(::interposesSupervisorRuntime)) {
                return SupervisedProcessResult("unavailable", listOf("process_supervisor_environment_unavailable"))
            }
        } catch (_: Exception) {
            return SupervisedProcessResult("unavailable", listOf("process_supervisor_arguments_invalid"))
        }
        val slot = slots.acquire() ?: return SupervisedProcessResult("unavailable", listOf("process_supervisor_capacity_unavailable"))
        val image: SupervisorImageHandle
        try { image = prepare() }
        catch (error: Exception) {
            slot.close()
            return SupervisedProcessResult("unavailable", listOf(if (error is ExecutionProblem) error.code else "process_supervisor_bundle_unavailable"))
        }
        val channel: SupervisorChannel
        try {
            if (cancellation.isCancelled()) {
                if (image.closeSafely()) slot.close()
                return SupervisedProcessResult("cancelled", listOf("cancelled_before_execution"), cancelled = true)
            }
            if (elapsedSupervisorMillis(started) >= timeoutMillis) {
                if (image.closeSafely()) slot.close()
                return SupervisedProcessResult("failed", listOf("execution_timeout"), timedOut = true)
            }
            image.verify()
            channel = launcher.start(image, detachedCommand, directory, detachedEnvironment, timeoutMillis, maxOutputBytes)
        } catch (error: Exception) {
            if (image.closeSafely()) slot.close()
            return SupervisedProcessResult("failed", listOf(if (error is ExecutionProblem) error.code else "process_supervisor_start_failed"))
        }

        val reader = SupervisorFrameReader(maxOutputBytes)
        val diagnosticProblem = AtomicReference<String?>()
        val diagnosticsDone = AtomicBoolean()
        val controlProblem = AtomicReference<String?>()
        val workers = mutableListOf<Thread>()
        val frameThread = thread(isDaemon = true, name = "ktchecker-supervisor-frames") { reader.read(channel.frames) }
        val diagnosticThread = thread(isDaemon = true, name = "ktchecker-supervisor-diagnostics") {
            try {
                val bytes = ByteArray(8192)
                var count = 0
                var emptyReads = 0
                while (true) {
                    val read = channel.diagnostics.read(bytes)
                    if (read < 0) break
                    if (read == 0) {
                        if (++emptyReads > 16) { diagnosticProblem.set("process_supervisor_diagnostics_unreliable"); break }
                        continue
                    }
                    emptyReads = 0
                    diagnosticProblem.compareAndSet(null, "process_supervisor_diagnostics_unexpected")
                    count += read
                    if (count > 65_536) { diagnosticProblem.set("process_supervisor_diagnostics_limit"); break }
                }
            } catch (_: Exception) { diagnosticProblem.set("process_supervisor_diagnostics_unreliable") }
            finally { diagnosticsDone.set(true) }
        }
        workers.add(frameThread)
        workers.add(diagnosticThread)
        var cancelRequested = false
        fun cancelControl() {
            if (cancelRequested) return
            cancelRequested = true
            // One tiny write; even a damaged pipe must not block the caller's cleanup deadline.
            workers.add(thread(isDaemon = true, name = "ktchecker-supervisor-cancel") {
                try { channel.control.write('C'.code); channel.control.flush() }
                catch (_: Exception) { controlProblem.set("process_supervisor_control_failed") }
                finally { try { channel.control.close() } catch (_: Exception) { controlProblem.set("process_supervisor_control_failed") } }
            })
        }

        var interrupted = false
        var localCancelled = false
        var localTimeout = false
        var cancellationProblem = false
        var transportExpired = false
        var deadline = started + (timeoutMillis + cleanupGraceMillis) * 1_000_000
        var cleanupRequested = false
        fun requestCleanup() {
            if (!cleanupRequested) {
                cleanupRequested = true
                deadline = minOf(deadline, System.nanoTime() + cleanupGraceMillis * 1_000_000)
            }
            cancelControl()
        }
        try {
            while (true) {
                try { if (cancellation.isCancelled()) { localCancelled = true; requestCleanup() } }
                catch (_: Exception) { cancellationProblem = true; requestCleanup() }
                if (reader.problem() != null || diagnosticProblem.get() != null) requestCleanup()
                if (reader.done.get() && diagnosticsDone.get() && !channel.alive()) break
                if (System.nanoTime() >= deadline) {
                    transportExpired = true
                    localTimeout = !cleanupRequested || !localCancelled
                    requestCleanup()
                    break
                }
                try { Thread.sleep(5) }
                catch (_: InterruptedException) { interrupted = true; localCancelled = true; requestCleanup() }
            }
        } catch (_: Exception) {
            transportExpired = true
            requestCleanup()
        }

        // Preserve the exact bytes/exit already received even if trailers, EOF, cleanup or exit fail.
        val wire = reader.snapshot()
        val helperExit = try { channel.exitCode() } catch (_: Exception) { null }
        val terminal = wire.terminal
        val reasons = linkedSetOf<String>()
        terminal?.let { final ->
            supervisorCauseReason(final)?.let(reasons::add)
            for ((flag, reason) in supervisorFlagReasons) if (final.has(flag)) reasons.add(reason)
        }
        wire.problem?.let(reasons::add)
        diagnosticProblem.get()?.let(reasons::add)
        controlProblem.get()?.let(reasons::add)
        if (cancellationProblem) reasons.add("process_supervisor_cancellation_failed")
        if (localCancelled) reasons.add("execution_cancelled")
        if (localTimeout) reasons.add("execution_timeout")
        if (transportExpired) reasons.add("process_supervisor_transport_timeout")
        if (helperExit != null && helperExit != 0) reasons.add("process_supervisor_exit_failed")
        var imageVerified = false
        if (helperExit != null) {
            try { image.verify(); imageVerified = true }
            catch (error: Exception) { reasons.add(if (error is ExecutionProblem) error.code else "process_supervisor_image_changed") }
        }
        val cleanupAcknowledged = imageVerified && terminal != null && wire.problem == null && wire.eof && helperExit == 0 &&
            terminal.has(SupervisorFlags.QUIESCENT) && !terminal.has(SupervisorFlags.SHUTDOWN_FAILED) &&
            !terminal.has(SupervisorFlags.OWNERSHIP_LOST) && !transportExpired
        if (!cleanupAcknowledged) reasons.add("owned_process_group_shutdown_unverified")
        val cancelled = localCancelled || terminal?.has(SupervisorFlags.CANCELLED) == true
        val timedOut = localTimeout || terminal?.has(SupervisorFlags.TIMED_OUT) == true
        val truncated = terminal?.has(SupervisorFlags.OUTPUT_LIMIT) == true
        val status = when {
            cleanupAcknowledged && reasons.isEmpty() && terminal?.cause == 0 && terminal.flags == SupervisorFlags.COMPLETED -> "executed"
            cleanupAcknowledged && cancelled && !timedOut && reasons.all { it == "execution_cancelled" } -> "cancelled"
            else -> "failed"
        }

        // Close pipes only after the bounded cleanup attempt; C ignores SIGPIPE and still owns cleanup.
        // A blocked Java close/read remains a capped daemon, never a reason to kill/reuse a helper PID.
        workers.add(thread(isDaemon = true, name = "ktchecker-supervisor-close") {
            try { channel.control.close() } catch (_: Exception) { }
            try { channel.frames.close() } catch (_: Exception) { }
            try { channel.diagnostics.close() } catch (_: Exception) { }
        })
        cleanupWhenExited(channel, image, slot, workers.toList(), releaseCapacity = cleanupAcknowledged)
        if (interrupted) Thread.currentThread().interrupt()
        return SupervisedProcessResult(status, reasons.toList(), terminal?.childExit, wire.stdout, wire.stderr,
            terminal?.observedOutput ?: wire.captured, cancelled, timedOut, truncated, image.identity,
            terminal?.has(SupervisorFlags.EXEC_CONFIRMED) == true)
    }
}

private fun cleanupWhenExited(channel: SupervisorChannel, image: SupervisorImageHandle, slot: SupervisorSlot,
                              workers: List<Thread>, releaseCapacity: Boolean) {
    fun finished(): Boolean = try { !channel.alive() && workers.none { it.isAlive } } catch (_: Exception) { false }
    // Image deletion is independent of capacity. Once returned without ACK, never promote a late
    // terminal or helper exit into proof that its children disappeared; the slot stays poisoned.
    fun cleanup() { if (image.closeSafely() && releaseCapacity) slot.close() }
    if (finished()) { cleanup(); return }
    thread(isDaemon = true, name = "ktchecker-supervisor-retained-cleanup") {
        while (!finished()) {
            try { channel.awaitExit(250) } catch (_: Exception) { }
            workers.forEach { worker -> try { worker.join(25) } catch (_: InterruptedException) { } }
            if (!finished()) try { Thread.sleep(100) } catch (_: InterruptedException) { }
        }
        cleanup()
    }
}

private val supervisorFlagReasons = listOf(
    SupervisorFlags.CANCELLED to "execution_cancelled",
    SupervisorFlags.TIMED_OUT to "execution_timeout",
    SupervisorFlags.OUTPUT_LIMIT to "process_output_limit",
    SupervisorFlags.PARENT_LOST to "process_supervisor_parent_disconnected",
    SupervisorFlags.IO_FAILED to "process_output_unreliable",
    SupervisorFlags.EXEC_FAILED to "process_start_failed",
    SupervisorFlags.PROTOCOL_FAILED to "process_supervisor_protocol_error",
    SupervisorFlags.OWNERSHIP_LOST to "process_supervisor_ownership_lost",
    SupervisorFlags.SHUTDOWN_FAILED to "owned_process_group_shutdown_unverified",
)

private fun supervisorCauseReason(terminal: SupervisorTerminal): String? = when (terminal.cause) {
    0 -> null
    1 -> "execution_cancelled"
    2 -> "execution_timeout"
    3 -> "process_output_limit"
    4 -> "process_supervisor_parent_disconnected"
    5 -> "process_output_unreliable"
    6 -> "process_start_failed"
    7 -> if (terminal.has(SupervisorFlags.GROUP_READY) && terminal.has(SupervisorFlags.EXIT_OBSERVED) &&
        !terminal.has(SupervisorFlags.EXEC_CONFIRMED) && !terminal.has(SupervisorFlags.EXEC_FAILED)) {
        "process_start_unconfirmed"
    } else "process_supervisor_start_failed"
    8 -> "owned_process_group_shutdown_unverified"
    9 -> "process_supervisor_protocol_error"
    10 -> "process_supervisor_ownership_lost"
    else -> "process_supervisor_protocol_error"
}

private fun validateSupervisorArguments(command: List<String>, directory: Path, environment: Map<String, String>) {
    require(command.isNotEmpty() && command.size <= 4096 && environment.size <= 4096)
    require(directory.isAbsolute && Path.of(command.first()).isAbsolute)
    var bytes = 0L
    fun check(text: String) {
        require(text.length <= 65_536 && '\u0000' !in text)
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
        require(encoded.remaining() <= 65_536)
        bytes += encoded.remaining().toLong() + 1
        require(bytes <= 1_048_576)
    }
    command.forEach(::check)
    check(directory.toString())
    for ((name, value) in environment) {
        require(name.isNotEmpty() && '=' !in name)
        check(name)
        check(value)
    }
}

/** POSIX environment names are case-sensitive; reject exact prefixes/keys, not substring matches. */
private fun interposesSupervisorRuntime(name: String): Boolean = name.startsWith("LD_") || name.startsWith("DYLD_") ||
    name in setOf("GLIBC_TUNABLES", "GCONV_PATH", "LOCPATH")

private fun elapsedSupervisorMillis(started: Long): Long = (System.nanoTime() - started) / 1_000_000

package dev.ktchecker.providers.runtime

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.util.Collections

/** Internal transport plumbing, not an execution grant, worker HELLO, or compiler result. */
internal object OwnedSessionLimits {
    const val MAX_MILLIS = 3_600_000L
    const val MAX_BYTES = 16_777_216
    const val CHUNK = 8192
    const val REQUESTS = 8192L
    const val EPOCHS = 4096L
    const val QUEUE_FRAMES = 64
    const val QUEUE_BYTES = 262_144L
    const val CLEANUP_MILLIS = 4000L
    const val DIAGNOSTIC_BYTES = 65_536
    const val REASONS = 64
}

/** Create this BEFORE owner preparation/queueing. Receipt/ACK never refreshes this budget. */
internal class OwnedSessionDeadline private constructor(
    internal val startedNanos: Long,
    val durationMillis: Long,
) {
    internal fun remainingMillis(nowNanos: Long = System.nanoTime()): Long {
        val elapsed = nowNanos - startedNanos // Correct across nanoTime wrap for these bounded intervals.
        if (elapsed < 0) return 0 // A broken/backwards clock never grants fresh time.
        val remaining = durationMillis * 1_000_000 - elapsed
        return if (remaining <= 0) 0 else remaining / 1_000_000
    }

    internal fun expired(nowNanos: Long = System.nanoTime()): Boolean = remainingMillis(nowNanos) == 0L

    companion object {
        fun afterMillis(durationMillis: Long): OwnedSessionDeadline {
            require(durationMillis in 1..OwnedSessionLimits.MAX_MILLIS)
            return OwnedSessionDeadline(System.nanoTime(), durationMillis)
        }
    }
}

/** Bounded, deeply detached immutable configuration. This validates BEFORE the helper's loader. */
internal class OwnedSessionConfiguration(
    command: List<String>,
    val directory: Path,
    environment: Map<String, String>,
    val generationDeadline: OwnedSessionDeadline,
    val inputLimit: Int = OwnedSessionLimits.MAX_BYTES,
    val outputLimit: Int = OwnedSessionLimits.MAX_BYTES,
) {
    val command: List<String>
    val environment: Map<String, String>

    init {
        require(inputLimit in 1..OwnedSessionLimits.MAX_BYTES && outputLimit in 1..OwnedSessionLimits.MAX_BYTES)
        require(command.isNotEmpty() && command.size <= 4096 && environment.size <= 4096)
        require(directory.isAbsolute && Path.of(command.first()).isAbsolute)
        var bytes = 0L
        fun check(text: String) {
            require(text.length <= 65_536 && '\u0000' !in text)
            val size = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text)).remaining()
            require(size <= 65_536)
            require(size.toLong() + 1 <= 1_048_576L - bytes)
            bytes += size + 1L
        }
        val argv = command.toList()
        val env = LinkedHashMap(environment)
        require(argv.isNotEmpty() && argv.size <= 4096 && env.size <= 4096 && Path.of(argv.first()).isAbsolute)
        argv.forEach(::check)
        check(directory.toString())
        for ((name, value) in env) {
            require(name.isNotEmpty() && '=' !in name && !interposesLiveSupervisor(name))
            check(name)
            check(value)
        }
        this.command = Collections.unmodifiableList(argv)
        this.environment = Collections.unmodifiableMap(env)
    }
}

private fun interposesLiveSupervisor(name: String): Boolean = name.startsWith("LD_") || name.startsWith("DYLD_") ||
    name in setOf("GLIBC_TUNABLES", "GCONV_PATH", "LOCPATH")

internal enum class OwnedSessionState { OPENING, LIVE, RETIRING, TERMINAL, QUARANTINED }
internal enum class OwnedSessionStream { STDOUT, STDERR }
internal enum class OwnedSessionDelivery { NOT_SENT, MAY_HAVE_BEEN_WRITTEN }
internal enum class OwnedSessionControl(val tag: Int) { ARM(17), DATA(18), DISARM(19), CLOSE_STDIN(20) }

/** Fixed allowlist: never retain exception messages, diagnostics, or per-poll failure histories. */
internal enum class OwnedSessionReason(val code: String) {
    CAPACITY("owned_session_capacity"),
    BUNDLE_UNAVAILABLE("owned_session_bundle_unavailable"),
    IMAGE_EXTRACTION("owned_session_image_extraction_unresolved"),
    IMAGE_VERIFY("owned_session_image_changed"),
    IMAGE_CLEANUP("owned_session_image_cleanup_failed"),
    START_FAILED("owned_session_start_failed"),
    START_UNKNOWN("owned_session_start_unknown"),
    PUMP_START("owned_session_pump_start_failed"),
    PUMP_UNRESOLVED("owned_session_pump_unresolved"),
    GENERATION_TIMEOUT("owned_session_generation_timeout"),
    EPOCH_TIMEOUT("owned_session_epoch_timeout"),
    CANCELLED("owned_session_cancelled"),
    INTERRUPTED("owned_session_owner_operation_interrupted"),
    PROTOCOL("owned_session_protocol_error"),
    FRAME_IO("owned_session_frame_io_failed"),
    CONTROL_IO("owned_session_control_io_failed"),
    DIAGNOSTICS("owned_session_diagnostics_unexpected"),
    DIAGNOSTIC_IO("owned_session_diagnostics_unreliable"),
    OUTPUT_QUEUE("owned_session_output_queue_limit"),
    INPUT_LIMIT("owned_session_input_limit"),
    OUTPUT_LIMIT("owned_session_output_limit"),
    CONTROL_LIMIT("owned_session_control_limit"),
    HELPER_EXIT("owned_session_helper_exit_failed"),
    HELPER_STATE("owned_session_helper_state_unreliable"),
    CLEANUP_UNVERIFIED("owned_session_shutdown_unverified"),
    BUSY("owned_session_busy"),
    NOT_LIVE("owned_session_not_live"),
    WRONG_EPOCH("owned_session_wrong_epoch"),
    ALREADY_ARMED("owned_session_already_armed"),
    NOT_ARMED("owned_session_not_armed"),
    STDIN_CLOSED("owned_session_stdin_closed"),
    INVALID_DATA("owned_session_invalid_data_size"),
    NATIVE_TIMEOUT("owned_session_native_timeout"),
    NATIVE_PARENT_LOST("owned_session_native_parent_lost"),
    NATIVE_IO("owned_session_native_io_failed"),
    NATIVE_EXEC("owned_session_native_exec_failed"),
    NATIVE_SETUP("owned_session_native_setup_failed"),
    NATIVE_OWNERSHIP("owned_session_native_ownership_lost"),
    INPUT_INCOMPLETE("owned_session_input_incomplete"),
    UNEXPECTED("owned_session_internal_failure"),
}

internal fun ownedSessionReasons(reasons: Collection<OwnedSessionReason>): List<OwnedSessionReason> =
    Collections.unmodifiableList(reasons.distinct().also { check(it.size <= OwnedSessionLimits.REASONS) })

/** Bytes remain provisional, even after native cleanup; a separate application adapter qualifies them. */
internal class OwnedSessionOutput(val stream: OwnedSessionStream, bytes: ByteArray) {
    private val content = bytes.clone()
    val size: Int get() = content.size
    val bytes: ByteArray get() = content.clone()
}

internal data class OwnedSessionAck(
    val control: OwnedSessionControl,
    val sequence: Long,
    val valueA: Long,
    val valueB: Long,
)

internal sealed interface OwnedSessionOperation {
    data class Acknowledged(val ack: OwnedSessionAck) : OwnedSessionOperation
    class Rejected(reasons: Collection<OwnedSessionReason>) : OwnedSessionOperation {
        val reasons = ownedSessionReasons(reasons)
        val notSent = true
    }
    class Failed(
        reasons: Collection<OwnedSessionReason>,
        val sequence: Long?,
        val delivery: OwnedSessionDelivery,
    ) : OwnedSessionOperation {
        val reasons = ownedSessionReasons(reasons)
    }
}

internal sealed interface OwnedSessionOpenResult {
    /** A known helper channel, initially OPENING. No claim of child exec or worker readiness. */
    data class StartedTransport(val handle: OwnedProcessSession) : OwnedSessionOpenResult
    class Rejected(reasons: Collection<OwnedSessionReason>) : OwnedSessionOpenResult {
        val reasons = ownedSessionReasons(reasons)
    }
}

internal interface OwnedProcessSession {
    fun snapshot(): OwnedSessionSnapshot
    fun arm(epoch: Long, deadline: OwnedSessionDeadline): OwnedSessionOperation
    fun write(bytes: ByteArray): OwnedSessionOperation
    fun disarm(epoch: Long): OwnedSessionOperation
    fun closeStdin(): OwnedSessionOperation
    fun cancel()
    fun pollOutput(waitMillis: Long = 0): OwnedSessionOutput?
    fun awaitTerminal(waitMillis: Long = OwnedSessionLimits.CLEANUP_MILLIS): OwnedSessionTermination?
}

internal class OwnedSessionTermination(
    val state: OwnedSessionState,
    reasons: Collection<OwnedSessionReason>,
    val nativeFinal: OwnedSessionNativeFinal?,
    val identity: OwnedSupervisorIdentity,
    val cleanupAcknowledged: Boolean,
    val captureComplete: Boolean,
) {
    val reasons = ownedSessionReasons(reasons)
    val terminalExecutionConfirmed: Boolean = cleanupAcknowledged &&
        nativeFinal?.has(OwnedSessionFlags.EXEC_CONFIRMED) == true
}

internal class OwnedSessionSnapshot(
    val state: OwnedSessionState,
    val bound: Boolean,
    val identity: OwnedSupervisorIdentity,
    val receivedOutput: Long,
    val deliveredOutput: Long,
    val retainedOutput: Long,
    val discardedOutput: Long,
    val queuedFrames: Int,
    val acknowledgedInput: Long,
    val outstandingSequence: Long?,
    val provisionalFinal: OwnedSessionNativeFinal?,
    val termination: OwnedSessionTermination?,
    reasons: Collection<OwnedSessionReason>,
) {
    val reasons = ownedSessionReasons(reasons)
}

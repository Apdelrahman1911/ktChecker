package dev.ktchecker.compiler

import java.util.Collections

/** Fixed private observations, not public metrics, execution evidence, or deadline inputs. */
internal enum class CompilerPhase(val label: String) {
    PROVIDER_CALL("provider_call"),
    INITIAL_VERIFICATION("initial_verification"),
    WRAPPED_PERMIT_VERIFICATION("wrapped_permit_verification"),
    SNAPSHOT_VERIFICATION("snapshot_verification"),
    JDK_RUNTIME_VERIFICATION("jdk_runtime_verification"),
    COMPILER_CLASSPATH_VERIFICATION("compiler_classpath_verification"),
    MODEL_BINDING_VERIFICATION("model_binding_verification"),
    DEPENDENCY_CLASSPATH_VERIFICATION("dependency_classpath_verification"),
    SEALED_SOURCE_READING("sealed_source_reading"),
    OUTPUT_PREPARATION("output_preparation"),
    OWNER_OUTPUT_PREPARATION("owner_output_preparation"),
    SOURCE_COPYING("source_copying"),
    COMPILER_ARTIFACT_COPYING("compiler_artifact_copying"),
    DEPENDENCY_ARTIFACT_COPYING("dependency_artifact_copying"),
    RUNNER_CALL("runner_call"),
    EXECUTION_OBSERVER("execution_observer"),
    RESULT_NORMALIZATION("result_normalization"),
}

internal data class CompilerPhaseInterval(
    val sequence: Int,
    val phase: CompilerPhase,
    val occurrence: Int,
    val parentSequence: Int?,
    /** Zero is provider preflight; positive values are actual wrapped-permit callback occurrences. */
    val verificationOccurrence: Int?,
    val startNanos: Long?,
    val endNanos: Long?,
    val elapsedNanos: Long?,
    /** A normal return may be a refusal/failure. This is never an execution-success bit. */
    val actionReturnedNormally: Boolean?,
)

internal data class CompilerPhaseReceipt(
    val capacity: Int,
    val droppedPhaseCount: Long,
    val clockValid: Boolean,
    val nestingValid: Boolean,
    val allRecordedActionsFinished: Boolean,
    val intervals: List<CompilerPhaseInterval>,
) {
    val observationsComplete: Boolean get() = droppedPhaseCount == 0L && clockValid && nestingValid && allRecordedActionsFinished
}

/**
 * One synchronous provider call owns one recorder. All storage used while an action is
 * running is preallocated and bounded; exhaustion never prevents the action. No global
 * state, source/path labels, callback observer, or serialization participates in the call.
 * The injectable clock is solely a recorder-test seam. Provider deadline/cancellation
 * decisions continue to use their original production clocks, never these observations.
 */
internal class CompilerPhaseTimings(
    val capacity: Int = DEFAULT_CAPACITY,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    init { require(capacity in 0..MAX_CAPACITY) }

    private val phases = arrayOfNulls<CompilerPhase>(capacity)
    private val parents = IntArray(capacity) { NO_PHASE }
    private val occurrences = IntArray(capacity)
    private val verifications = IntArray(capacity) { NO_PHASE }
    private val starts = LongArray(capacity) { NO_TIME }
    private val ends = LongArray(capacity) { NO_TIME }
    private val returns = ByteArray(capacity)
    private val phaseCounts = IntArray(CompilerPhase.entries.size)
    private var used = 0
    private var active = NO_PHASE
    private var dropped = 0L
    private var origin = 0L
    private var hasOrigin = false
    private var lastOffset = 0L
    private var clockValid = true
    private var nestingValid = true

    internal fun begin(phase: CompilerPhase): Int {
        if (used == capacity) {
            if (dropped != Long.MAX_VALUE) dropped++
            return NO_PHASE
        }
        val index = used++
        phases[index] = phase
        parents[index] = active
        occurrences[index] = ++phaseCounts[phase.ordinal]
        verifications[index] = when (phase) {
            CompilerPhase.INITIAL_VERIFICATION -> 0
            CompilerPhase.WRAPPED_PERMIT_VERIFICATION -> occurrences[index]
            else -> if (active == NO_PHASE) NO_PHASE else verifications[active]
        }
        starts[index] = sampleOffset()
        active = index
        return index
    }

    internal fun end(index: Int, returnedNormally: Boolean) {
        if (index == NO_PHASE) return
        // This is only an observation defect, never a reason to replace an action's result/failure.
        if (index !in 0 until used || active != index) { nestingValid = false; return }
        ends[index] = sampleOffset()
        returns[index] = if (returnedNormally) 1 else 2
        active = parents[index]
    }

    /** A detached immutable snapshot; open/invalid/dropped observations remain explicit. */
    fun snapshot(): CompilerPhaseReceipt = CompilerPhaseReceipt(capacity, dropped, clockValid, nestingValid,
        (0 until used).all { returns[it] != 0.toByte() }, Collections.unmodifiableList((0 until used).map { index ->
            val start = starts[index].takeIf { it != NO_TIME }
            val end = ends[index].takeIf { it != NO_TIME }
            CompilerPhaseInterval(index + 1, checkNotNull(phases[index]), occurrences[index],
                parents[index].takeIf { it != NO_PHASE }?.plus(1), verifications[index].takeIf { it != NO_PHASE },
                start, end, if (start != null && end != null) end - start else null,
                when (returns[index].toInt()) { 1 -> true; 2 -> false; else -> null })
        }))

    private fun sampleOffset(): Long {
        if (!clockValid) return NO_TIME
        val now = try { nanoTime() } catch (_: Throwable) {
            // Even a failing test clock cannot skip work or mask the original action exception.
            clockValid = false
            return NO_TIME
        }
        if (!hasOrigin) { origin = now; hasOrigin = true; return 0 }
        // System.nanoTime is signed and may wrap. Subtraction is valid for intervals below
        // 2^63 ns; negative/backwards deltas are invalid, not silently clamped to zero.
        val offset = now - origin
        if (offset < lastOffset) { clockValid = false; return NO_TIME }
        lastOffset = offset
        return offset
    }

    companion object {
        // Total rows, including still-open parents: both storage and nesting are <=64.
        const val DEFAULT_CAPACITY = 64
        const val MAX_CAPACITY = 64
        private const val NO_PHASE = -1
        private const val NO_TIME = -1L
    }
}

/** No clock reads/storage on the default path; the action result/original exception is preserved. */
internal inline fun <T> CompilerPhaseTimings?.observe(phase: CompilerPhase, crossinline action: () -> T): T {
    if (this == null) return action()
    val index = begin(phase)
    var returnedNormally = false
    try {
        val result = action()
        returnedNormally = true
        return result
    } finally {
        end(index, returnedNormally)
    }
}

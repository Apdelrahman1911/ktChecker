package dev.ktchecker.compiler

import dev.ktchecker.providers.runtime.OwnedSupervisorIdentity
import dev.ktchecker.providers.runtime.ProcessOutcome

/** Trusted fixture-host invocation only. None of these values grants provider execution authority. */
internal data class CompilerFixtureSettings(
    val fixtureDirectory: String,
    val cohort: String,
    val maxAttempts: Int = MAX_ATTEMPTS,
    val overallMillis: Long = MAX_OVERALL_MILLIS,
    val perJobMillis: Long = MAX_PER_JOB_MILLIS,
    val evidenceBytes: Long = MAX_EVIDENCE_BYTES,
) {
    init {
        require(fixtureDirectory.isNotBlank() && '\u0000' !in fixtureDirectory)
        require(cohort in COHORTS) { "An explicit named authored cohort is required" }
        require(maxAttempts in 1..MAX_ATTEMPTS)
        require(overallMillis in 1..MAX_OVERALL_MILLIS && perJobMillis in 1..MAX_PER_JOB_MILLIS)
        require(evidenceBytes in MIN_EVIDENCE_BYTES..MAX_EVIDENCE_BYTES)
    }

    fun selects(authoredCohort: String?): Boolean {
        val name = authoredName(authoredCohort)
        return cohort == "all" || cohort == name
    }

    fun description(): Map<String, Any> = mapOf(
        "named_cohort" to cohort, "maximum_attempts" to maxAttempts,
        "overall_timeout_millis" to overallMillis, "per_job_timeout_millis" to perJobMillis,
        "maximum_evidence_bytes" to evidenceBytes, "reserved_terminal_evidence_bytes" to TERMINAL_RESERVE_BYTES,
        "maximum_job_evidence_bytes" to JOB_RESERVE_BYTES,
        "authority_scope" to "trusted_harness_invocation_only_not_project_policy_JSON_or_execution_permission",
        "unselected_scope" to "visible_not_run_not_excluded", "resume_or_merge" to false,
    )

    companion object {
        const val MAX_ATTEMPTS = 48
        const val MAX_OVERALL_MILLIS = 1_200_000L
        const val MAX_PER_JOB_MILLIS = 90_000L
        const val MAX_EVIDENCE_BYTES = 268_435_456L
        const val TERMINAL_RESERVE_BYTES = 8_388_608L
        const val JOB_RESERVE_BYTES = 16_777_216L
        const val MIN_EVIDENCE_BYTES = TERMINAL_RESERVE_BYTES + JOB_RESERVE_BYTES
        val COHORTS = setOf("original", "overrides", "visibility", "types_conventions",
            "inline_constructors", "opt_in", "jvm", "all")

        fun authoredName(value: String?): String = when (value) {
            null -> "original"
            "2026-11-04-overrides" -> "overrides"
            "2026-11-04-visibility" -> "visibility"
            "2026-11-04-conventions_types_nullability" -> "types_conventions"
            "2026-11-04-inline_constructors" -> "inline_constructors"
            "2026-11-04-opt_in" -> "opt_in"
            "2026-11-04-jvm_record_external" -> "jvm"
            else -> error("Unknown authored cohort; refusing a silent scope omission: $value")
        }

        /** Gradle JavaExec appends the fixture directory; options and that one positional may be in either order. */
        fun parse(args: Array<String>): CompilerFixtureSettings {
            val options = linkedMapOf<String, String>()
            val directories = mutableListOf<String>()
            for (argument in args) {
                if (!argument.startsWith("--")) { directories += argument; continue }
                val separator = argument.indexOf('=')
                require(separator > 2 && separator < argument.lastIndex) { "Use --name=value fixture options" }
                val name = argument.substring(2, separator)
                require(name in setOf("cohort", "max-attempts", "overall-seconds", "per-job-seconds", "evidence-mib")) {
                    "Unknown fixture option: $name"
                }
                require(options.put(name, argument.substring(separator + 1)) == null) { "Duplicate fixture option: $name" }
            }
            require(directories.size == 1) { "Exactly one fixture root is required" }
            fun number(name: String, default: Long, maximum: Long): Long = options[name]?.let {
                require(it.matches(Regex("[1-9][0-9]*"))) { "Invalid bounded fixture option: $name" }
                it.toLongOrNull()?.also { value -> require(value in 1..maximum) { "Fixture option exceeds its hard bound: $name" } }
                    ?: error("Invalid bounded fixture option: $name")
            } ?: default
            return CompilerFixtureSettings(directories.single(),
                options["cohort"] ?: error("Explicit --cohort=<named cohort> is required; no unbounded all default"),
                number("max-attempts", MAX_ATTEMPTS.toLong(), MAX_ATTEMPTS.toLong()).toInt(),
                number("overall-seconds", MAX_OVERALL_MILLIS / 1_000, MAX_OVERALL_MILLIS / 1_000) * 1_000,
                number("per-job-seconds", MAX_PER_JOB_MILLIS / 1_000, MAX_PER_JOB_MILLIS / 1_000) * 1_000,
                number("evidence-mib", MAX_EVIDENCE_BYTES / 1_048_576, MAX_EVIDENCE_BYTES / 1_048_576) * 1_048_576)
        }
    }
}

internal enum class CompilerFixtureStopReason(val code: String) {
    OWNER_STOP("owner_stop_requested"), UNSAFE_CONTROL("owner_control_missing_replaced_or_unsafe"),
    ATTEMPT_BUDGET("attempt_budget_exhausted"), OVERALL_DEADLINE("overall_deadline_exhausted"),
    EVIDENCE_BUDGET("evidence_budget_exhausted"), SCRATCH_CLEANUP("owned_scratch_cleanup_failed"),
    CHILD_QUIESCENCE("owned_child_quiescence_unverified"),
}

internal class CompilerFixtureStopped(val reason: CompilerFixtureStopReason, detail: String) :
    IllegalStateException("Bounded compiler fixture run stopped (${reason.code}): $detail")

/** Keep the actual provider/persistence failure primary when cleanup or terminal reporting also fails. */
internal fun preserveCompilerFixtureFailure(first: Throwable?, next: Throwable): Throwable = first?.also {
    if (it !== next && it.suppressed.none { suppressed -> suppressed === next }) it.addSuppressed(next)
} ?: next

/**
 * This is a decision over the real provider callback, not an execution/containment simulator.
 * The current provider returns before that callback only on a pre-runtime refusal. Every returned
 * post-launch native transport outcome carries owned_process_group_shutdown_unverified unless
 * FINAL + EOF + verified helper exit established its owned-group cleanup acknowledgement.
 * Exec confirmation, an exit code, or a failed provider result alone proves no quiescence.
 */
internal fun compilerFixtureChildQuiescenceProblem(
    providerEntered: Boolean,
    providerReturned: Boolean,
    actual: ProcessOutcome?,
    expectedSupervisor: OwnedSupervisorIdentity,
    observedInputMatches: Boolean,
): String? = when {
    !providerEntered && (providerReturned || actual != null) -> "Process observation was not bound to an entered provider call"
    !providerEntered -> null
    actual == null && !providerReturned -> "Provider threw after entry without an actual observer outcome; child state is unknown"
    actual == null -> null // A returned pre-runtime refusal, not a child-exit inference.
    !observedInputMatches -> "Actual observer input did not match the admitted fixture call"
    actual.supervisorIdentity != expectedSupervisor -> "Actual outcome lacks the matching START native supervisor identity"
    "owned_process_group_shutdown_unverified" in actual.reasons -> "Native owned-process-group shutdown was not acknowledged"
    else -> null
}

internal data class CompilerFixtureAttempt(
    val number: Int, val label: String, val ruleIds: Set<String>, val reservedAtMillis: Long,
    var state: String = "reserved_before_provider_call",
) {
    fun description(): Map<String, Any> = mapOf("number" to number, "label" to label,
        "fixture_rule_ids" to ruleIds.sorted(), "reserved_at_elapsed_millis" to reservedAtMillis,
        "state" to state, "execution_claim" to "none_use_actual_process_execution_confirmed_only")
}

/** Monotonic admission budget. Startup, materialization and checkpoint time all consume the overall budget. */
internal class CompilerFixtureBudget(
    val settings: CompilerFixtureSettings,
    private val startedNanos: Long = System.nanoTime(),
    private val nanoTime: () -> Long = System::nanoTime,
    private val ownerStop: () -> Boolean = { false },
) {
    val attempts = mutableListOf<CompilerFixtureAttempt>()
    private val stops = mutableListOf<Map<String, Any>>()
    var stopReason: CompilerFixtureStopReason? = null
        private set

    fun elapsedMillis(): Long = (nanoTime() - startedNanos).coerceAtLeast(0) / 1_000_000
    fun remainingMillis(): Long = (settings.overallMillis - elapsedMillis()).coerceAtLeast(0)
    fun deadlineReached(): Boolean = remainingMillis() == 0L

    fun stop(reason: CompilerFixtureStopReason, detail: String): CompilerFixtureStopped {
        if (stops.none { it["reason"] == reason.code }) stops += mapOf("reason" to reason.code,
            "detail" to detail, "at_elapsed_millis" to elapsedMillis())
        if (stopReason == null) stopReason = reason
        return CompilerFixtureStopped(reason, detail)
    }

    fun requireActive(checkOwner: Boolean = true) {
        stopReason?.let { throw CompilerFixtureStopped(it, "No further attempts after a recorded stop") }
        if (checkOwner) {
            val requested = try { ownerStop() } catch (failure: Exception) {
                throw stop(CompilerFixtureStopReason.UNSAFE_CONTROL, failure.message ?: "Unsafe owner stop directory")
            }
            if (requested) throw stop(CompilerFixtureStopReason.OWNER_STOP, "Owner's private control/stop marker exists")
        }
        if (deadlineReached()) throw stop(CompilerFixtureStopReason.OVERALL_DEADLINE, "Startup/attempt/checkpoint budget elapsed")
    }

    fun reserveAttempt(label: String, ruleIds: Set<String>): CompilerFixtureAttempt {
        requireActive()
        if (attempts.size >= settings.maxAttempts) throw stop(CompilerFixtureStopReason.ATTEMPT_BUDGET,
            "${settings.maxAttempts} attempts already reserved, including denied provider calls")
        return CompilerFixtureAttempt(attempts.size + 1, label, ruleIds.toSet(), elapsedMillis()).also(attempts::add)
    }

    /** Recomputed immediately before profile construction; a fresh exact permit still has to name this profile. */
    fun nextTimeoutMillis(): Long {
        requireActive(checkOwner = false)
        return minOf(settings.perJobMillis, remainingMillis()).also { check(it > 0) }
    }

    fun description(): Map<String, Any?> = settings.description() + mapOf(
        "elapsed_millis" to elapsedMillis(), "remaining_millis" to remainingMillis(),
        "reserved_attempts" to attempts.size, "stop_reason" to stopReason?.code,
        "stop_events" to stops.toList(), "attempts" to attempts.map { it.description() },
        "deadline_scope" to "cooperative_monotonic_no_further_launch_after_budget; not_OS_hard_kill_or_performance_certificate",
    )
}

/**
 * Bulk immutable receipts remain candidates. Only a settled final selector AND a successful
 * harness return may establish completion. The clock linearizes immediately after that selector's
 * bounded publication; no later independent clock check may turn a committed return into failure.
 * A failed correction leaves publication unconfirmed, never standalone proof in a stale selector.
 */
internal class CompilerFixtureTerminalPublication(private val budget: CompilerFixtureBudget) {
    private var attempted = false
    var committed = false
        private set

    fun publish(
        eligible: Boolean,
        reservePublication: () -> Unit,
        publishComplete: () -> Unit,
        publishIncomplete: (Throwable?) -> Unit,
    ): Boolean {
        check(!attempted) { "Terminal publication is one-shot; no resume or success retry" }
        attempted = true
        if (!eligible) { publishIncomplete(null); return false }
        try {
            budget.requireActive(checkOwner = false)
            reservePublication()
            budget.requireActive(checkOwner = false)
            publishComplete()
            // Includes serialization, reference hashing, filesystem writes and handle closure.
            budget.requireActive(checkOwner = false)
            committed = true
            return true
        } catch (failure: Throwable) {
            if (failure is CompilerFixtureStopped) budget.stop(failure.reason, failure.message ?: failure.reason.code)
            try { publishIncomplete(failure) }
            catch (correction: Throwable) { preserveCompilerFixtureFailure(failure, correction) }
            throw failure
        }
    }
}

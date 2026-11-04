package dev.ktchecker.providers.detekt

import dev.ktchecker.contracts.CancellationToken
import dev.ktchecker.contracts.ProviderResult
import dev.ktchecker.providers.runtime.OwnedSupervisorIdentity
import dev.ktchecker.providers.runtime.ProcessOutcome
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*

/** The full ten-rule authoring scope is unchanged; there is deliberately no full-corpus selector. */
internal object DetektCorpusCohort {
    const val NAME = "exceptions-core-v1"
    const val MAX_INVOCATIONS = 40
    const val MAX_AGGREGATE_MILLIS = 1_200_000L
    const val MAX_CHILD_MILLIS = 60_000L
    const val FULL_AUTHORED_RULES = 10
    const val FULL_AUTHORED_ANALYSES = 91
    val analysesByRule = linkedMapOf(
        "ktc.kotlin.detekt.exceptions.throwing-exception-from-finally" to 8,
        "ktc.kotlin.detekt.exceptions.swallowed-exception" to 11,
        "ktc.kotlin.detekt.exceptions.not-implemented-declaration" to 9,
    )
    val authoredRuleIds = analysesByRule.keys + setOf(
        "ktc.kotlin.detekt.potential-bugs.equals-always-returns-true-or-false",
        "ktc.kotlin.detekt.potential-bugs.invalid-range",
        "ktc.kotlin.detekt.potential-bugs.iterator-has-next-calls-next-method",
        "ktc.kotlin.detekt.potential-bugs.unconditional-jump-statement-in-loop",
        "ktc.kotlin.detekt.potential-bugs.useless-postfix-expression",
        "ktc.kotlin.detekt.style.data-class-should-be-immutable",
        "ktc.maintainability.detekt.libraries.library-entities-should-not-be-public",
    )
    val invocationsByKind = linkedMapOf(
        CorpusInvocationKind.ANALYSIS to 28,
        CorpusInvocationKind.CLI_PROBE to 1,
        CorpusInvocationKind.ABSTENTION to 6,
        CorpusInvocationKind.AUTHORIZATION_DENIAL to 3,
    )
    val plannedInvocations: Int get() = invocationsByKind.values.sum()
    val planIdentity: String get() = buildString {
        append("detekt-corpus-cohort/1\n$NAME\n")
        analysesByRule.forEach { (rule, count) -> append("$rule=$count\n") }
        invocationsByKind.forEach { (kind, count) -> append("${kind.id}=$count\n") }
        append("full_authoring_scope=$FULL_AUTHORED_RULES/$FULL_AUTHORED_ANALYSES/not_run\n")
        append("limits=$MAX_INVOCATIONS/$MAX_AGGREGATE_MILLIS/$MAX_CHILD_MILLIS\n")
    }
}

internal enum class CorpusInvocationKind(val id: String) {
    ANALYSIS("detector_analysis"), CLI_PROBE("cli_probe"),
    ABSTENTION("unsupported_target_abstention"), AUTHORIZATION_DENIAL("changed_configuration_authorization_denial"),
}

internal data class DetektCorpusSettings(
    val maxInvocations: Int,
    val aggregateMillis: Long,
    val childMillis: Long,
) {
    companion object {
        fun selected(cohort: String?, invocations: String?, aggregate: String?, child: String?): DetektCorpusSettings {
            require(cohort == DetektCorpusCohort.NAME) {
                "Real corpus requires explicit -PdetektCorpusCohort=${DetektCorpusCohort.NAME}; no implicit/full-ten execution path"
            }
            fun bounded(value: String?, default: Long, maximum: Long, label: String): Long {
                val parsed = value?.toLongOrNull() ?: if (value == null) default else error("$label must be a decimal integer")
                require(parsed in 1..maximum) { "$label must be in 1..$maximum" }
                return parsed
            }
            val maximum = bounded(invocations, 40, DetektCorpusCohort.MAX_INVOCATIONS.toLong(), "invocation ceiling").toInt()
            require(maximum >= DetektCorpusCohort.plannedInvocations) {
                "Approved cohort requires ${DetektCorpusCohort.plannedInvocations} reservations; insufficient budget fails before execution"
            }
            return DetektCorpusSettings(maximum,
                bounded(aggregate, DetektCorpusCohort.MAX_AGGREGATE_MILLIS, DetektCorpusCohort.MAX_AGGREGATE_MILLIS, "aggregate milliseconds"),
                bounded(child, DetektCorpusCohort.MAX_CHILD_MILLIS, DetektCorpusCohort.MAX_CHILD_MILLIS, "child milliseconds"))
        }
    }
}

/**
 * Cooperative monotonic run budget, including START capture and between-call preparation.
 * Reservations count every analyze/probe call, including denial and abstention requests.
 * Only a checked actual terminal/no-execution signal releases the next reservation; an
 * ExecutionPermit, a callback alone, or executionConfirmed=false is never a cleanup signal.
 * Runtime owns native cleanup; this driver never signals/enumerates a PID or process group.
 */
internal class DetektCorpusBudget(
    private val settings: DetektCorpusSettings,
    private val emit: (JsonObject) -> Unit,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    internal class Ticket(val ordinal: Int, val kind: CorpusInvocationKind, val ruleId: String, val caseId: String)
    private enum class Signal(val id: String) {
        NATIVE_TERMINAL("runtime_validated_owned_child_terminal"),
        REFUSAL_BEFORE_SETUP("exact_authorization_refusal_before_setup"),
        PROVIDER_ABSTENTION("provider_abstention_without_runtime_callback"),
        UNVERIFIED("missing_or_unverified_terminal"),
    }
    private val started = nowNanos()
    private val stopped = AtomicBoolean()
    private var finished = false
    private var active: Ticket? = null
    private var signal: Signal? = null
    private var sequence = 0
    private var reserved = 0
    private var checked = 0
    private var confirmedExecutions = 0
    private var observedRuntimeCallbacks = 0
    private val invocationIdentities = mutableSetOf<Triple<CorpusInvocationKind, String, String>>()
    private val analysesReservedByRule = mutableMapOf<String, Int>()
    private val reservedByKind = CorpusInvocationKind.entries.associateWith { 0 }.toMutableMap()
    private val checkedByKind = CorpusInvocationKind.entries.associateWith { 0 }.toMutableMap()
    val cancellation = CancellationToken { stopped.get() || elapsedNanos() >= settings.aggregateMillis * 1_000_000 }

    init {
        require(settings.maxInvocations in DetektCorpusCohort.plannedInvocations..DetektCorpusCohort.MAX_INVOCATIONS)
        require(settings.aggregateMillis in 1..DetektCorpusCohort.MAX_AGGREGATE_MILLIS)
        require(settings.childMillis in 1..DetektCorpusCohort.MAX_CHILD_MILLIS)
        event("run_started", null)
    }

    private fun elapsedNanos(): Long = (nowNanos() - started).coerceAtLeast(0)
    fun remainingMillis(): Long = ((settings.aggregateMillis * 1_000_000 - elapsedNanos()).coerceAtLeast(0)) / 1_000_000
    fun requireWithinDeadline() {
        check(!stopped.get()) { "Corpus stopped; no retry or new reservation is permitted" }
        if (remainingMillis() < 1) {
            stopped.set(true)
            event("aggregate_deadline_exhausted", active)
            error("Corpus aggregate monotonic deadline exhausted")
        }
    }

    fun reserve(kind: CorpusInvocationKind, ruleId: String, caseId: String): Ticket {
        requireWithinDeadline()
        check(!finished && active == null) { "Previous invocation lacks a checked actual terminal/no-execution result" }
        if (reserved >= settings.maxInvocations) {
            stopped.set(true)
            event("invocation_budget_exhausted", null)
            error("Corpus invocation budget exhausted; retries are prohibited")
        }
        require(ruleId in DetektCorpusCohort.analysesByRule)
        require(caseId.matches(Regex("[a-z0-9-]+")))
        require(reservedByKind.getValue(kind) < DetektCorpusCohort.invocationsByKind.getValue(kind)) { "Cohort kind limit reached; no extra call path" }
        require(invocationIdentities.add(Triple(kind, ruleId, caseId))) { "Duplicate invocation is a forbidden retry" }
        if (kind == CorpusInvocationKind.ANALYSIS) {
            val count = analysesReservedByRule.getOrDefault(ruleId, 0) + 1
            require(count <= DetektCorpusCohort.analysesByRule.getValue(ruleId)) { "Selected rule analysis limit reached" }
            analysesReservedByRule[ruleId] = count
        }
        val ticket = Ticket(++reserved, kind, ruleId, caseId)
        active = ticket
        signal = null
        reservedByKind[kind] = reservedByKind.getValue(kind) + 1
        // Persisted before even preparing a provider call; an emitter failure stops execution.
        event("reserved_before_call", ticket)
        return ticket
    }

    fun childTimeoutMillis(ticket: Ticket): Long {
        check(active === ticket && signal == null)
        requireWithinDeadline()
        return minOf(settings.childMillis, remainingMillis())
    }

    fun observeProcess(ticket: Ticket, actual: ProcessOutcome, expected: OwnedSupervisorIdentity) {
        check(active === ticket && signal == null) { "Unexpected/duplicate runtime callback" }
        ++observedRuntimeCallbacks
        if (actual.executionConfirmed) ++confirmedExecutions
        signal = when (ticket.kind) {
            CorpusInvocationKind.ANALYSIS, CorpusInvocationKind.CLI_PROBE ->
                if (actual.status == "executed" && actual.reasons.isEmpty() && actual.exitCode != null && actual.executionConfirmed &&
                    actual.supervisorIdentity == expected && !actual.cancelled && !actual.timedOut && !actual.truncated)
                    Signal.NATIVE_TERMINAL else Signal.UNVERIFIED
            CorpusInvocationKind.AUTHORIZATION_DENIAL ->
                if (authorizationRefusedBeforeSetup(actual)) Signal.REFUSAL_BEFORE_SETUP else Signal.UNVERIFIED
            CorpusInvocationKind.ABSTENTION -> Signal.UNVERIFIED
        }
        event("actual_runtime_callback_retained", ticket, buildJsonObject {
            put("actual_status", actual.status); put("actual_exit_code", actual.exitCode)
            put("execution_confirmed", actual.executionConfirmed)
            put("actual_reasons", JsonArray(actual.reasons.map(::JsonPrimitive)))
            put("actual_supervisor_target", actual.supervisorIdentity?.target)
            put("cleanup_signal", signal!!.id)
            put("cleanup_signal_scope", "Runtime executed status validates native terminal/cleanup; no standalone raw ACK or transferable execution authority is claimed.")
        })
    }

    fun observeAbstention(ticket: Ticket, result: ProviderResult, unexpectedProcess: ProcessOutcome?) {
        check(active === ticket && signal == null && ticket.kind == CorpusInvocationKind.ABSTENTION)
        if (unexpectedProcess != null) {
            ++observedRuntimeCallbacks
            if (unexpectedProcess.executionConfirmed) ++confirmedExecutions
        }
        signal = if (unexpectedProcess == null && result.providerId == "detekt" && result.findings.isEmpty() && result.coverage.size == 1 &&
            result.coverage.single().ruleId == ticket.ruleId &&
            ticket.caseId == "unsupported-${result.coverage.single().context.target}" &&
            result.coverage.all { it.state == "unsupported" && it.provenance == "not_executed" &&
                !it.implemented && !it.tested && !it.qualified && !it.successful() })
            Signal.PROVIDER_ABSTENTION else Signal.UNVERIFIED
        event("actual_provider_abstention_retained", ticket, buildJsonObject {
            put("actual_status", result.status); put("runtime_callback_observed", unexpectedProcess != null)
            put("unexpected_execution_confirmed", unexpectedProcess?.executionConfirmed)
            put("actual_reasons", JsonArray(result.reasons.map(::JsonPrimitive))); put("cleanup_signal", signal!!.id)
        })
    }

    fun checked(ticket: Ticket, references: List<JsonObject>) {
        check(active === ticket)
        val expected = when (ticket.kind) {
            CorpusInvocationKind.ANALYSIS, CorpusInvocationKind.CLI_PROBE -> Signal.NATIVE_TERMINAL
            CorpusInvocationKind.AUTHORIZATION_DENIAL -> Signal.REFUSAL_BEFORE_SETUP
            CorpusInvocationKind.ABSTENTION -> Signal.PROVIDER_ABSTENTION
        }
        if (signal != expected) {
            stopped.set(true)
            event("terminal_or_no_execution_unverified", ticket)
            error("Cannot advance corpus: actual native cleanup/no-execution signal unverified")
        }
        require(references.isNotEmpty())
        requireWithinDeadline()
        event("assertions_checked", ticket, buildJsonObject {
            put("passed", true); put("qualified", false); put("cleanup_signal", signal!!.id)
            put("evidence", JsonArray(references))
        })
        ++checked
        checkedByKind[ticket.kind] = checkedByKind.getValue(ticket.kind) + 1
        active = null
        signal = null
    }

    fun failure(cause: Throwable) {
        stopped.set(true)
        event("failed_or_incomplete", active, buildJsonObject {
            put("failure_type", cause.javaClass.name); put("qualified", false)
            put("note", "No retry, new reservation, or passing live sidecar; existing partial artifacts retained.")
        })
    }

    fun finish(): JsonObject {
        requireWithinDeadline()
        check(active == null && !finished)
        check(reserved == DetektCorpusCohort.plannedInvocations && checked == reserved)
        check(reservedByKind == DetektCorpusCohort.invocationsByKind && checkedByKind == reservedByKind)
        check(confirmedExecutions == 29 && observedRuntimeCallbacks == 32)
        event("all_cohort_invocations_checked", null)
        finished = true
        return summary()
    }

    fun summary(): JsonObject = buildJsonObject {
        put("cohort", DetektCorpusCohort.NAME); put("reserved_invocations", reserved); put("checked_invocations", checked)
        put("invocation_ceiling", settings.maxInvocations); put("aggregate_limit_millis", settings.aggregateMillis)
        put("child_limit_millis", settings.childMillis); put("elapsed_monotonic_millis", elapsedNanos() / 1_000_000)
        put("remaining_millis", remainingMillis()); put("stopped", stopped.get()); put("all_invocations_checked", finished)
        put("run_complete", false); put("publication_committed", false)
        put("actual_execution_confirmations", confirmedExecutions); put("actual_runtime_callbacks", observedRuntimeCallbacks)
        put("reserved_by_kind", buildJsonObject { reservedByKind.forEach { (kind, count) -> put(kind.id, count) } })
        put("checked_by_kind", buildJsonObject { checkedByKind.forEach { (kind, count) -> put(kind.id, count) } })
        put("native_launch_ceiling", 29); put("qualified", false)
    }

    private fun event(phase: String, ticket: Ticket?, details: JsonObject = buildJsonObject {}) {
        check(sequence < DetektCorpusCohort.MAX_INVOCATIONS * 3 + 4) { "Bounded progress-event capacity exhausted" }
        val value = buildJsonObject {
            put("schema_version", 1); put("sequence", ++sequence); put("phase", phase)
            put("cohort", DetektCorpusCohort.NAME); put("captured_at", Instant.now().toString())
            put("elapsed_monotonic_millis", elapsedNanos() / 1_000_000); put("remaining_millis", remainingMillis())
            put("reserved_invocations", reserved); put("checked_invocations", checked); put("qualified", false)
            ticket?.let { put("invocation", it.ordinal); put("kind", it.kind.id); put("rule_id", it.ruleId); put("case_id", it.caseId) }
            details.forEach { (name, item) -> put(name, item) }
        }
        try { emit(value) } catch (cause: Throwable) { stopped.set(true); throw cause }
    }

    companion object {
        fun authorizationRefusedBeforeSetup(actual: ProcessOutcome): Boolean =
            actual.status == "unavailable" && actual.reasons == listOf("execution_not_authorized") &&
                !actual.executionConfirmed && !actual.executed && !actual.successfulExecution &&
                actual.supervisorIdentity == null && actual.exitCode == null && actual.outputDirectory == null &&
                actual.command.isEmpty() && actual.stdout.isEmpty() && actual.stderr.isEmpty() &&
                !actual.cancelled && !actual.timedOut && !actual.truncated && actual.metrics.outputBytes == 0L &&
                actual.metrics.cpuMillis == null && actual.metrics.peakRssBytes == null
    }
}

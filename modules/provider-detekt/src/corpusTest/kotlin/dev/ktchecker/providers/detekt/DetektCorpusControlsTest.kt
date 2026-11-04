package dev.ktchecker.providers.detekt

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.OwnedSupervisorIdentity
import dev.ktchecker.providers.runtime.ProcessOutcome
import java.nio.file.Path
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Synthetic control states only: these tests never invoke a provider, helper, process, or sleep. */
class DetektCorpusControlsTest {
    private val rule = DetektCorpusCohort.analysesByRule.keys.first()
    private val identity = OwnedSupervisorIdentity("ktchecker-owned-child-supervisor/preview-1", "linux-x86_64",
        "sha256:${"a".repeat(64)}", "sha256:${"b".repeat(64)}")
    private val references = listOf(buildJsonObject { put("path", "synthetic-control-only.json"); put("sha256", "0".repeat(64)) })
    private fun settings() = DetektCorpusSettings.selected(DetektCorpusCohort.NAME, null, null, null)
    private fun native() = ProcessOutcome("executed", exitCode = 0, supervisorIdentity = identity, executionConfirmed = true)
    private fun denial() = ProcessOutcome("unavailable", listOf("execution_not_authorized"))
    private fun abstention(selectedRule: String = rule, target: String = "common") = ProviderResult("detekt", "partial",
        coverage = listOf(Coverage(selectedRule, RuleContext(target = target), state = "unsupported")))

    private class Clock(var nanos: Long = 0) { fun now(): Long = nanos }
    private fun checkedNative(budget: DetektCorpusBudget, kind: CorpusInvocationKind, selectedRule: String, case: String) {
        val ticket = budget.reserve(kind, selectedRule, case)
        budget.observeProcess(ticket, native(), identity)
        budget.checked(ticket, references)
    }

    @Test fun `integration alone has no cohort and full-ten selection is rejected`() {
        for (cohort in listOf(null, "", "syntax-v1", "all", "full-ten", "exceptions-core-v2"))
            assertFailsWith<IllegalArgumentException> { DetektCorpusSettings.selected(cohort, null, null, null) }
        assertEquals(40, settings().maxInvocations)
        assertEquals(1_200_000L, settings().aggregateMillis)
        assertEquals(60_000L, settings().childMillis)
    }

    @Test fun `approved plan counts every call and retains full authoring scope`() {
        assertEquals(listOf(8, 11, 9), DetektCorpusCohort.analysesByRule.values.toList())
        assertEquals(38, DetektCorpusCohort.plannedInvocations)
        assertEquals(listOf(28, 1, 6, 3), DetektCorpusCohort.invocationsByKind.values.toList())
        assertEquals(10, DetektCorpusCohort.FULL_AUTHORED_RULES)
        assertEquals(91, DetektCorpusCohort.FULL_AUTHORED_ANALYSES)
        assertTrue(DetektCorpusCohort.planIdentity.contains("10/91/not_run"))
    }

    @Test fun `invalid insufficient and oversized controls fail before any reservation`() {
        for (maximum in listOf("0", "-1", "37", "41", "48", "100000", "1.5", "yes"))
            assertFails { DetektCorpusSettings.selected(DetektCorpusCohort.NAME, maximum, null, null) }
        for (aggregate in listOf("0", "-1", "1200001", "999999999999999999999999", "bad"))
            assertFails { DetektCorpusSettings.selected(DetektCorpusCohort.NAME, null, aggregate, null) }
        for (child in listOf("0", "-1", "60001", "bad"))
            assertFails { DetektCorpusSettings.selected(DetektCorpusCohort.NAME, null, null, child) }
        assertEquals(38, DetektCorpusSettings.selected(DetektCorpusCohort.NAME, "38", "500", "10").maxInvocations)
    }

    @Test fun `callback and authorization do not release the next reservation`() {
        val events = mutableListOf<JsonObject>()
        val budget = DetektCorpusBudget(settings(), { events += it })
        val ticket = budget.reserve(CorpusInvocationKind.ANALYSIS, rule, "positive")
        assertEquals("reserved_before_call", events.last()["phase"]!!.jsonPrimitive.content)
        assertFailsWith<IllegalStateException> { budget.reserve(CorpusInvocationKind.ANALYSIS, rule, "negative") }
        budget.observeProcess(ticket, native(), identity)
        assertFailsWith<IllegalStateException> { budget.reserve(CorpusInvocationKind.ANALYSIS, rule, "negative") }
        budget.checked(ticket, references)
        assertEquals("assertions_checked", events.last()["phase"]!!.jsonPrimitive.content)
        assertEquals("runtime_validated_owned_child_terminal", events.last()["cleanup_signal"]!!.jsonPrimitive.content)
        val next = budget.reserve(CorpusInvocationKind.ANALYSIS, rule, "negative")
        assertEquals(2, next.ordinal)
        assertFailsWith<IllegalStateException> { budget.observeProcess(ticket, native(), identity) }
    }

    @Test fun `unconfirmed fork and cleanup failure permanently prevent advance`() {
        val outcomes = listOf(
            native().copy(executionConfirmed = false),
            native().copy(status = "failed", reasons = listOf("owned_process_group_shutdown_unverified")),
            native().copy(reasons = listOf("process_supervisor_protocol_error")),
            native().copy(supervisorIdentity = identity.copy(binaryDigest = "sha256:${"c".repeat(64)}")),
            native().copy(cancelled = true), native().copy(timedOut = true), native().copy(truncated = true),
        )
        for (actual in outcomes) {
            val budget = DetektCorpusBudget(settings(), {})
            val ticket = budget.reserve(CorpusInvocationKind.ANALYSIS, rule, "positive")
            budget.observeProcess(ticket, actual, identity)
            assertFailsWith<IllegalStateException> { budget.checked(ticket, references) }
            assertTrue(budget.cancellation.isCancelled())
            assertFailsWith<IllegalStateException> { budget.reserve(CorpusInvocationKind.ANALYSIS, rule, "retry") }
        }
    }

    @Test fun `denial requires complete refusal-before-setup tuple not a false confirmation`() {
        assertTrue(DetektCorpusBudget.authorizationRefusedBeforeSetup(denial()))
        val bad = listOf(
            denial().copy(status = "failed"), denial().copy(reasons = listOf("process_start_unconfirmed")),
            denial().copy(exitCode = 1), denial().copy(outputDirectory = Path.of("/synthetic-control-only")),
            denial().copy(command = listOf("/not-executed")), denial().copy(stdout = "output"), denial().copy(stderr = "error"),
            denial().copy(supervisorIdentity = identity), denial().copy(executionConfirmed = true),
            denial().copy(cancelled = true), denial().copy(timedOut = true), denial().copy(truncated = true),
            denial().copy(metrics = AnalysisMetrics(outputBytes = 1)),
            denial().copy(metrics = AnalysisMetrics(cpuMillis = 0)), denial().copy(metrics = AnalysisMetrics(peakRssBytes = 0)),
        )
        for (actual in bad) assertFalse(DetektCorpusBudget.authorizationRefusedBeforeSetup(actual))
        val budget = DetektCorpusBudget(settings(), {})
        val ticket = budget.reserve(CorpusInvocationKind.AUTHORIZATION_DENIAL, rule, "configuration-permit-replay")
        budget.observeProcess(ticket, denial(), identity)
        budget.checked(ticket, references)
        assertEquals(1, budget.summary()["checked_invocations"]!!.jsonPrimitive.int)
    }

    @Test fun `unsupported outcome needs no callback and truthful unsupported coverage`() {
        val budget = DetektCorpusBudget(settings(), {})
        val ticket = budget.reserve(CorpusInvocationKind.ABSTENTION, rule, "unsupported-common")
        budget.observeAbstention(ticket, abstention(), null)
        budget.checked(ticket, references)
        for ((result, callback) in listOf(abstention(target = "native") to native(), abstention().copy(coverage = emptyList()) to null,
            abstention(target = "native").copy(coverage = listOf(Coverage(rule, RuleContext(target = "native"), state = "unsupported", provenance = "executed"))) to null)) {
            val failed = DetektCorpusBudget(settings(), {})
            val failedTicket = failed.reserve(CorpusInvocationKind.ABSTENTION, rule, "unsupported-native")
            failed.observeAbstention(failedTicket, result, callback)
            assertFailsWith<IllegalStateException> { failed.checked(failedTicket, references) }
            assertTrue(failed.cancellation.isCancelled())
        }
    }

    @Test fun `monotonic deadline cancels preparation and caps child to remaining budget`() {
        val clock = Clock()
        val events = mutableListOf<JsonObject>()
        val budget = DetektCorpusBudget(settings().copy(aggregateMillis = 50), { events += it }, clock::now)
        val ticket = budget.reserve(CorpusInvocationKind.ANALYSIS, rule, "positive")
        assertEquals(50L, budget.childTimeoutMillis(ticket))
        clock.nanos = 49_000_000
        assertEquals(1L, budget.childTimeoutMillis(ticket))
        assertFalse(budget.cancellation.isCancelled())
        clock.nanos = 50_000_000
        assertTrue(budget.cancellation.isCancelled())
        assertFailsWith<IllegalStateException> { budget.childTimeoutMillis(ticket) }
        assertEquals("aggregate_deadline_exhausted", events.last()["phase"]!!.jsonPrimitive.content)
        clock.nanos = 0 // Even a fake clock reset cannot resume a stopped budget.
        assertFailsWith<IllegalStateException> { budget.reserve(CorpusInvocationKind.ANALYSIS, rule, "retry") }
        val capped = DetektCorpusBudget(settings(), {}, Clock()::now)
        assertEquals(60_000L, capped.childTimeoutMillis(capped.reserve(CorpusInvocationKind.ANALYSIS, rule, "positive")))
    }

    @Test fun `complete exact schedule confirms 29 launches and counts all 38 requests`() {
        val events = mutableListOf<JsonObject>()
        val budget = DetektCorpusBudget(settings().copy(maxInvocations = 38), { events += it })
        for ((selectedRule, count) in DetektCorpusCohort.analysesByRule) {
            repeat(count) { checkedNative(budget, CorpusInvocationKind.ANALYSIS, selectedRule, "analysis-$it") }
            val denied = budget.reserve(CorpusInvocationKind.AUTHORIZATION_DENIAL, selectedRule, "configuration-permit-replay")
            budget.observeProcess(denied, denial(), identity); budget.checked(denied, references)
            for (target in listOf("common", "native")) {
                val unsupported = budget.reserve(CorpusInvocationKind.ABSTENTION, selectedRule, "unsupported-$target")
                budget.observeAbstention(unsupported, abstention(selectedRule, target), null); budget.checked(unsupported, references)
            }
        }
        checkedNative(budget, CorpusInvocationKind.CLI_PROBE, rule, "cli-version-probe")
        assertEquals(29, events.count { it["execution_confirmed"]?.jsonPrimitive?.booleanOrNull == true })
        val summary = budget.finish()
        assertEquals(38, summary["reserved_invocations"]!!.jsonPrimitive.int)
        assertEquals(38, summary["checked_invocations"]!!.jsonPrimitive.int)
        assertTrue(summary["all_invocations_checked"]!!.jsonPrimitive.boolean)
        assertEquals(DetektCorpusCohort.invocationsByKind.mapKeys { it.key.id },
            summary["checked_by_kind"]!!.jsonObject.mapValues { it.value.jsonPrimitive.int })
        assertFailsWith<IllegalStateException> { budget.reserve(CorpusInvocationKind.ANALYSIS, rule, "unapproved-extra") }
        assertEquals(116, events.size) // One START + 38 * (reserve, actual, checked) + finish.
    }

    @Test fun `incomplete run cannot finish and progress write failure stops before a call`() {
        val partial = DetektCorpusBudget(settings(), {})
        checkedNative(partial, CorpusInvocationKind.ANALYSIS, rule, "positive")
        assertFailsWith<IllegalStateException> { partial.finish() }
        val failed = DetektCorpusBudget(settings(), { event ->
            if (event["phase"]!!.jsonPrimitive.content == "reserved_before_call") error("synthetic progress write failure")
        })
        assertFailsWith<IllegalStateException> { failed.reserve(CorpusInvocationKind.ANALYSIS, rule, "positive") }
        assertTrue(failed.cancellation.isCancelled())
        assertEquals(1, failed.summary()["reserved_invocations"]!!.jsonPrimitive.int)
        assertEquals(0, failed.summary()["checked_invocations"]!!.jsonPrimitive.int)
    }
}

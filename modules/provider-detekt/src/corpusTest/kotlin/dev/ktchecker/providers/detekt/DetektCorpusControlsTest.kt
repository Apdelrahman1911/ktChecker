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
    private val cohort = DetektCorpusCohort.EXCEPTIONS_CORE
    private val rule = cohort.analysesByRule.keys.first()
    private val identity = OwnedSupervisorIdentity("ktchecker-owned-child-supervisor/preview-1", "linux-x86_64",
        "sha256:${"a".repeat(64)}", "sha256:${"b".repeat(64)}")
    private val references = listOf(buildJsonObject { put("path", "synthetic-control-only.json"); put("sha256", "0".repeat(64)) })
    private fun settings(selected: DetektCorpusCohort = cohort) = DetektCorpusSettings.selected(selected.name, null, null, null)
    private fun native() = ProcessOutcome("executed", exitCode = 0, supervisorIdentity = identity, executionConfirmed = true)
    private fun denial() = ProcessOutcome("unavailable", listOf("execution_not_authorized"))
    private fun abstention(selectedRule: String = rule, target: String = "common") = ProviderResult("detekt", "partial",
        coverage = listOf(Coverage(selectedRule, RuleContext(target = target), state = "unsupported",
            reason = "detekt_jvm_provider_does_not_qualify_common_or_native_targets")),
        reasons = listOf("detekt_jvm_provider_does_not_qualify_common_or_native_targets"))

    private class Clock(var nanos: Long = 0) { fun now(): Long = nanos }
    private fun checkedNative(budget: DetektCorpusBudget, kind: CorpusInvocationKind, selectedRule: String, case: String) {
        val ticket = budget.reserve(kind, selectedRule, case)
        budget.observeProcess(ticket, native(), identity)
        budget.checked(ticket, references)
    }

    @Test fun `integration alone has no cohort and full-ten selection is rejected`() {
        for (cohort in listOf(null, "", "syntax-v1", "all", "full-ten", "exceptions-core-v2",
            "iteration-core-v2", "iteration-core-v1 ", " iteration-core-v1", "value-core-v1,opt-in-policy-v1", "../value-core-v1", "full-sixteen", "performance-shapes-v1 ",
            "performance-shapes-v1,public-contract-policy-v1", "syntax-expansion-v1"))
            assertFailsWith<IllegalArgumentException> { DetektCorpusSettings.selected(cohort, null, null, null) }
        assertEquals(40, settings().maxInvocations)
        assertEquals(1_200_000L, settings().aggregateMillis)
        assertEquals(60_000L, settings().childMillis)
    }

    @Test fun `approved plan counts every call and retains full authoring scope`() {
        assertEquals(listOf(8, 11, 9), cohort.analysesByRule.values.toList())
        assertEquals(38, cohort.plannedInvocations)
        assertEquals(listOf(28, 1, 6, 3), cohort.invocationsByKind.values.toList())
        assertEquals(10, DetektCorpusCohort.BASELINE_AUTHORED_RULES)
        assertEquals(16, DetektCorpusCohort.FULL_AUTHORED_RULES)
        assertEquals(91, DetektCorpusCohort.BASELINE_AUTHORED_ANALYSES)
        assertEquals(145, DetektCorpusCohort.FULL_AUTHORED_ANALYSES)
        assertTrue(cohort.planIdentity.contains("10/91/not_run"))
        assertEquals("sha256:1a04a745f3a90b093f07725c33f7aa5e4f68dcb4e08300e7498eabb23046469f", Digests.sha256(cohort.planIdentity))
    }

    @Test fun `four closed plans partition all ten unchanged rule counts without an all selector`() {
        val plans = DetektCorpusCohort.baseline
        assertEquals(listOf("exceptions-core-v1", "iteration-core-v1", "value-core-v1", "opt-in-policy-v1"), plans.map { it.name })
        assertEquals(listOf(listOf(8, 11, 9), listOf(9, 9, 10), listOf(9, 8), listOf(9, 9)),
            plans.map { it.analysesByRule.values.toList() })
        assertEquals(listOf(28, 28, 17, 18), plans.map { it.analysesByRule.values.sum() })
        assertEquals(listOf(38, 38, 24, 25), plans.map { it.plannedInvocations })
        assertEquals(listOf(29, 29, 18, 19), plans.map { it.nativeLaunchCeiling })
        assertEquals(listOf(32, 32, 20, 21), plans.map { it.expectedRuntimeCallbacks })
        val rules = plans.flatMap { it.analysesByRule.keys }
        assertEquals(10, rules.size)
        assertEquals(10, rules.distinct().size)
        assertEquals(DetektCorpusCohort.authoredRuleIds - DetektCorpusExpansionPins.controlIds.keys, rules.toSet())
        assertEquals(91, plans.sumOf { it.analysesByRule.values.sum() })
        for (plan in plans) {
            assertSame(plan, DetektCorpusCohort.selected(plan.name))
            assertSame(plan, settings(plan).cohort)
            assertTrue(plan.plannedInvocations <= 40)
            assertTrue(plan.planIdentity.contains("10/91/not_run"))
        }
    }

    @Test fun `closed selected rule and invocation maps cannot be mutated into another plan`() {
        for (plan in DetektCorpusCohort.all) {
            assertFailsWith<UnsupportedOperationException> { (plan.analysesByRule as MutableMap<String, Int>).clear() }
            assertFailsWith<UnsupportedOperationException> { (plan.invocationsByKind as MutableMap<CorpusInvocationKind, Int>).clear() }
        }
        assertFailsWith<UnsupportedOperationException> { (DetektCorpusCohort.all as MutableList<DetektCorpusCohort>).clear() }
        assertEquals(6, DetektCorpusCohort.all.size)
    }

    @Test fun `a rule from another authored cohort fails before reservation`() {
        for (selected in DetektCorpusCohort.all) {
            val events = mutableListOf<JsonObject>()
            val budget = DetektCorpusBudget(settings(selected), { events += it })
            val other = DetektCorpusCohort.authoredRuleIds.first { it !in selected.analysesByRule }
            assertFailsWith<IllegalArgumentException> { budget.reserve(CorpusInvocationKind.ANALYSIS, other, "positive") }
            assertEquals(1, events.size)
            assertEquals(0, budget.summary()["reserved_invocations"]!!.jsonPrimitive.int)
            assertEquals(1, budget.reserve(CorpusInvocationKind.ANALYSIS, selected.analysesByRule.keys.first(), "positive").ordinal)
        }
    }

    @Test fun `invalid insufficient and oversized controls fail before any reservation`() {
        for (selected in DetektCorpusCohort.all) {
            for (maximum in listOf("0", "-1", (selected.plannedInvocations - 1).toString(), "41", "48", "100000", "1.5", "yes"))
                assertFails { DetektCorpusSettings.selected(selected.name, maximum, null, null) }
            for (aggregate in listOf("0", "-1", "1200001", "999999999999999999999999", "bad"))
                assertFails { DetektCorpusSettings.selected(selected.name, null, aggregate, null) }
            for (child in listOf("0", "-1", "60001", "bad"))
                assertFails { DetektCorpusSettings.selected(selected.name, null, null, child) }
            assertEquals(selected.plannedInvocations,
                DetektCorpusSettings.selected(selected.name, selected.plannedInvocations.toString(), "500", "10").maxInvocations)
        }
        assertEquals(38, DetektCorpusSettings.selected(cohort.name, "38", "500", "10").maxInvocations)
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

    @Test fun `unsupported tuple rejects wrong status reasons certificate rule and empty coverage`() {
        val valid = abstention(target = "native")
        val variants = listOf(valid.copy(status = "failed"), valid.copy(reasons = emptyList()),
            valid.copy(reasons = valid.reasons + "unrelated_failure"), valid.copy(coverage = emptyList()),
            valid.copy(coverage = listOf(valid.coverage.single().copy(ruleId = "ktc.unrequested"))),
            valid.copy(coverage = listOf(valid.coverage.single().copy(reason = "another_reason"))),
            valid.copy(coverage = listOf(valid.coverage.single().copy(certificateId = "synthetic-not-a-certificate"))))
        for (result in variants) {
            val budget = DetektCorpusBudget(settings(), {})
            val ticket = budget.reserve(CorpusInvocationKind.ABSTENTION, rule, "unsupported-native")
            budget.observeAbstention(ticket, result, null)
            assertFailsWith<IllegalStateException> { budget.checked(ticket, references) }
            assertTrue(budget.cancellation.isCancelled())
        }
    }

    @Test fun `each selected schedule requires its own exact native callback and request counts`() {
        val sizes = mutableListOf<Int>()
        for (selected in DetektCorpusCohort.all) {
            val events = mutableListOf<JsonObject>()
            val budget = DetektCorpusBudget(settings(selected).copy(maxInvocations = selected.plannedInvocations), { events += it })
            for ((ruleIndex, entry) in selected.analysesByRule.entries.withIndex()) {
                val (selectedRule, count) = entry
                repeat(count) { caseIndex ->
                    val case = listOf("positive", "negative", "configuration", "version", "suppression", "regression")
                        .getOrNull(caseIndex) ?: if (selected.expansion)
                            DetektCorpusExpansionPins.controlIds.getValue(selectedRule)[caseIndex - 6] else "control-$caseIndex"
                    checkedNative(budget, CorpusInvocationKind.ANALYSIS, selectedRule, case)
                    if (case == "positive") {
                        val denied = budget.reserve(CorpusInvocationKind.AUTHORIZATION_DENIAL, selectedRule, "configuration-permit-replay")
                        budget.observeProcess(denied, denial(), identity); budget.checked(denied, references)
                    }
                    if (case == "version") {
                        if (ruleIndex == 0) checkedNative(budget, CorpusInvocationKind.CLI_PROBE, selectedRule, "cli-version-probe")
                        for (target in listOf("common", "native")) {
                            val unsupported = budget.reserve(CorpusInvocationKind.ABSTENTION, selectedRule, "unsupported-$target")
                            budget.observeAbstention(unsupported, abstention(selectedRule, target), null); budget.checked(unsupported, references)
                        }
                    }
                }
            }
            assertEquals(selected.nativeLaunchCeiling, events.count { it["execution_confirmed"]?.jsonPrimitive?.booleanOrNull == true })
            val summary = budget.finish()
            assertEquals(selected.name, summary["cohort"]!!.jsonPrimitive.content)
            assertEquals(selected.plannedInvocations, summary["reserved_invocations"]!!.jsonPrimitive.int)
            assertEquals(selected.plannedInvocations, summary["checked_invocations"]!!.jsonPrimitive.int)
            assertEquals(selected.nativeLaunchCeiling, summary["native_launch_ceiling"]!!.jsonPrimitive.int)
            assertEquals(selected.expectedRuntimeCallbacks, summary["actual_runtime_callbacks"]!!.jsonPrimitive.int)
            assertTrue(summary["all_invocations_checked"]!!.jsonPrimitive.boolean)
            assertFalse(summary["publication_committed"]!!.jsonPrimitive.boolean)
            assertEquals(selected.invocationsByKind.mapKeys { it.key.id },
                summary["checked_by_kind"]!!.jsonObject.mapValues { it.value.jsonPrimitive.int })
            assertFailsWith<IllegalStateException> {
                budget.reserve(CorpusInvocationKind.ANALYSIS, selected.analysesByRule.keys.first(), "unapproved-extra")
            }
            assertEquals(2 + 3 * selected.plannedInvocations, events.size)
            sizes += events.size
        }
        assertEquals(listOf(116, 116, 74, 77, 113, 113), sizes)
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
    @Test fun `new closed plans add six routes without changing old four identities`() {
        assertEquals(listOf(
            "sha256:1a04a745f3a90b093f07725c33f7aa5e4f68dcb4e08300e7498eabb23046469f",
            "sha256:855ccc2174e1497b74560aa5041923b870dbb289c68728eeffaf58140eb58890",
            "sha256:c6e6311078fe7d849755351e936326f00db5c508482f9830ac2367268a41c0c6",
            "sha256:83266a809d5e1e5ec7591a36882f7435ff7f108ea28a3426b418ce7d0f9ee421",
        ), DetektCorpusCohort.baseline.map { Digests.sha256(it.planIdentity) })
        val added = DetektCorpusCohort.all.filter { it.expansion }
        assertEquals(listOf("performance-shapes-v1", "public-contract-policy-v1"), added.map { it.name })
        assertEquals(16, DetektCorpusCohort.all.flatMap { it.analysesByRule.keys }.distinct().size)
        assertEquals(145, DetektCorpusCohort.all.sumOf { it.analysesByRule.values.sum() })
        for (plan in added) {
            assertEquals(listOf(9, 9, 9), plan.analysesByRule.values.toList())
            assertEquals(37, plan.plannedInvocations)
            assertEquals(28, plan.nativeLaunchCeiling)
            assertEquals(31, plan.expectedRuntimeCallbacks)
            assertEquals("syntax-expansion", plan.evidenceStem)
            val schedule = assertNotNull(plan.expectedSchedule)
            assertEquals(37, schedule.size)
            assertFailsWith<UnsupportedOperationException> { (schedule as MutableList<CorpusInvocationSpec>).clear() }
        }
        assertTrue(DetektCorpusCohort.baseline.all { it.evidenceStem == "syntax-corpus" && it.expectedSchedule == null })
    }

    @Test fun `new schedules reject reordered analysis and fabricated control before reservation`() {
        for (plan in DetektCorpusCohort.all.filter { it.expansion }) {
            val events = mutableListOf<JsonObject>()
            val budget = DetektCorpusBudget(settings(plan), { events += it })
            val selectedRule = plan.analysesByRule.keys.first()
            assertFailsWith<IllegalArgumentException> { budget.reserve(CorpusInvocationKind.ANALYSIS, selectedRule, "negative") }
            assertEquals(0, budget.summary()["reserved_invocations"]!!.jsonPrimitive.int)
            checkedNative(budget, CorpusInvocationKind.ANALYSIS, selectedRule, "positive")
            assertFailsWith<IllegalArgumentException> { budget.reserve(CorpusInvocationKind.ANALYSIS, selectedRule, "negative") }
            assertFailsWith<IllegalArgumentException> { budget.reserve(CorpusInvocationKind.AUTHORIZATION_DENIAL, selectedRule, "fake-refusal") }
            assertEquals(1, budget.summary()["reserved_invocations"]!!.jsonPrimitive.int)
            assertEquals(4, events.size)
            val deniedTicket = budget.reserve(CorpusInvocationKind.AUTHORIZATION_DENIAL, selectedRule, "configuration-permit-replay")
            budget.observeProcess(deniedTicket, denial(), identity)
            budget.checked(deniedTicket, references)
            assertEquals(2, budget.summary()["checked_invocations"]!!.jsonPrimitive.int)
        }
    }

}

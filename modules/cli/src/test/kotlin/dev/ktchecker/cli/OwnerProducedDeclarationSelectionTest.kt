package dev.ktchecker.cli

import dev.ktchecker.contracts.Digests
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import dev.ktchecker.reviewcontroller.AssembledReviewPlan
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * SYNTHETIC ordinary-model negative controls. No fake artifact is advertised as the pinned
 * produced compiler, and no exporter/compiler/worker runs. Positive produced declaration and
 * output authority require the separate real owner import/execution route, not these fixtures.
 */
class OwnerProducedDeclarationSelectionTest {
    private fun rejectedWithoutMutation(assembly: AssembledReviewPlan, expectedRules: List<String>) {
        val original = assembly.plan
        val digest = assembly.planDigest
        val inputs = original.slots.map { it.input }
        val checks = AtomicInteger()
        val deadline = OwnedApplicationDeadline.afterMillis(120_000)
        assertNull(assembly.declareJvmProduced(Digests.sha256("SYNTHETIC explicit owner intent"),
            deadline.identity, deadline.durationMillis) { checks.incrementAndGet() })
        assertSame(original, assembly.plan)
        assertEquals(digest, assembly.planDigest)
        assertNull(assembly.plan.jvmProduced)
        assertEquals(0, checks.get(), "An ineligible model cannot enroll the produced owner callback")
        assertEquals(inputs, assembly.plan.slots.map { it.input })
        assembly.plan.slots.forEach { assertEquals(expectedRules, it.input.requiredRuleIds) }
    }

    @Test fun anOrdinaryExecutableWholePlanCannotBeUpgradedBySelectingTheProducedProfile() {
        OwnerModelFixtures().use { f ->
            f.setEnabled()
            val c = f.composition()
            val intent = c.plans.begin(f.binding(), f.request(), true)
            val capture = f.capture()
            val model = f.import(capture, intent)
            val prepared = c.plans.prepare(intent, null, model, capture)
            assertNull(model.jvmProducedGraph)
            assertEquals(model.compilationIds.size, prepared.assembly.plan.slots.size)
            assertTrue(prepared.assembly.plan.slots.all { it.state == "pending" })
            rejectedWithoutMutation(prepared.assembly, f.catalog.ids.sorted())
            assertEquals(prepared.assembly.plan, c.plans.resolve(f.binding(), f.request()))
            assertNull(prepared.produced)
            assertEquals(0, c.attempts.activeCount())
        }
    }

    @Test fun lackOfOwnerExecutionConsentPreservesEveryUnsupportedOrdinaryObligation() {
        OwnerModelFixtures().use { f ->
            val c = f.composition()
            val intent = c.plans.begin(f.binding(), f.request(), false)
            val capture = f.capture()
            val model = f.import(capture, intent)
            val prepared = c.plans.prepare(intent, null, model, capture)
            assertNull(model.jvmProducedGraph)
            assertEquals(model.compilationIds.size, prepared.assembly.plan.slots.size)
            assertTrue(prepared.assembly.plan.slots.all { it.state == "unsupported" })
            val reasons = prepared.assembly.plan.slots.associate { it.slotId to it.reasons }
            rejectedWithoutMutation(prepared.assembly, f.catalog.ids.sorted())
            assertEquals(reasons, prepared.assembly.plan.slots.associate { it.slotId to it.reasons })
            assertTrue(prepared.assembly.plan.slots.all { c.plans.evidence(it.input)!!.profiles.isEmpty() })
            assertEquals(0, c.attempts.activeCount())
        }
    }

    @Test fun selectionCannotDropRemovedOrRetargetedHistoricalContextsToFitTwoSlots() {
        for (kind in listOf("removed", "retargeted")) OwnerModelFixtures().use { f ->
            val c = f.composition(selected = null)
            val intent = c.plans.begin(f.binding(), f.request(), false)
            val previous = f.capture()
            val before = f.import(previous, intent)
            val capture = if (kind == "removed") f.capture(f.files().filterKeys { !it.startsWith("app/") }) else previous
            val graph = if (kind == "removed") f.graph(capture, listOf(":lib")) else f.graph(capture).let { ordinary ->
                ordinary.copy(compilations = ordinary.compilations.map {
                    if (it.moduleId == ":lib") it.copy(target = "desktop") else it
                })
            }
            val after = f.import(capture, intent, graph)
            val prepared = c.plans.prepare(intent, before, after, capture)
            val reason = if (kind == "removed") "required_compilation_removed_unresolved" else "required_context_retargeted_unresolved"
            val historical = prepared.assembly.plan.slots.single { reason in it.reasons }
            assertEquals(after.compilationIds.size + 1, prepared.assembly.plan.slots.size)
            assertEquals("unsupported", historical.state)
            assertTrue(historical.input.context.compilationFiles!!.isEmpty())
            rejectedWithoutMutation(prepared.assembly, f.catalog.ids.sorted())
            assertSame(historical, prepared.assembly.plan.slots.single { it.slotId == historical.slotId })
            assertEquals(0, c.attempts.activeCount())
        }
    }
}

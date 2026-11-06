package dev.ktchecker.cli

import dev.ktchecker.contracts.Digests
import dev.ktchecker.kernel.ProducedPlanClock
import dev.ktchecker.projectmodel.VerifiedProjectModel
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import dev.ktchecker.snapshot.MaterializedSnapshot
import kotlin.test.*

/**
 * SYNTHETIC import-epoch/state controls, never compiler/Gradle/Native execution. The fixture
 * imports real registered overlays using explicitly synthetic ordinary-model artifacts; it
 * does not manufacture a produced graph, a pinned compiler, a generation or an output token.
 * Direct reserve() calls simulate ONLY prepare's already-consumed-intent state transition.
 */
class OwnerProducedImportEpochTest {
    private data class Composition(val plans: OwnerModelPlans, val runtime: OwnerLiveModelRuntime,
        val cold: ModelAttemptGrants)
    private data class Candidate(val intent: OwnerModelIntent, val model: VerifiedProjectModel,
        val capture: MaterializedSnapshot)

    private fun live(f: OwnerModelFixtures, capacity: Int = 16, verifyCode: () -> Unit = {}): Composition {
        val runtime = OwnerLiveModelRuntime(f.state.ownerId, f.installation,
            Digests.sha256("SYNTHETIC absent worker; import-state controls never open a generation"),
            maximumActiveAttempts = 1, maximumRetainedAttempts = capacity)
        val cold = ModelAttemptGrants()
        return Composition(OwnerModelPlans(f.state, f.snapshots, f.configs, f.catalog, null, null,
            cold, verifyCode, liveRuntime = runtime), runtime, cold)
    }

    private fun candidate(f: OwnerModelFixtures, c: Composition, name: String): Candidate {
        // Consent is deliberately absent: completing prepare must keep every context unsupported.
        val intent = c.plans.begin(f.binding(), f.request(name), false)
        val capture = f.capture()
        val model = f.import(capture, intent)
        assertNull(model.jvmProducedGraph, "Synthetic ordinary bytes are never an exact-pin produced-model fixture")
        return Candidate(intent, model, capture)
    }

    private fun reserve(c: Composition, value: Candidate): OwnerImportedModel {
        assertTrue(value.intent.used.compareAndSet(false, true))
        return c.runtime.reserveImport(value.intent, value.model)
    }

    private fun problem(code: String, action: () -> Unit) =
        assertEquals(code, assertFailsWith<CliProblem>(block = action).code)

    private fun noExecution(c: Composition) {
        assertEquals(0, c.runtime.retainedGenerations())
        assertEquals(0, c.runtime.retainedAttempts())
        assertEquals(0, c.cold.activeCount())
    }

    @Test fun aNewUnsupportedPreparationStillInvalidatesThePreviousImportEpoch() {
        OwnerModelFixtures().use { f ->
            val c = live(f)
            val old = reserve(c, candidate(f, c, "old"))
            assertTrue(old.validity.isCurrent())
            val next = candidate(f, c, "new-unsupported")
            val prepared = c.plans.prepare(next.intent, null, next.model, next.capture)
            assertFalse(old.validity.isCurrent())
            assertNull(prepared.produced)
            assertNull(prepared.assembly.plan.jvmProduced)
            assertEquals(next.model.compilationIds.size, prepared.assembly.plan.slots.size)
            prepared.assembly.plan.slots.forEach { slot ->
                assertEquals("unsupported", slot.state)
                assertEquals(f.catalog.ids.sorted(), slot.input.requiredRuleIds)
                assertTrue(c.plans.evidence(slot.input)!!.profiles.isEmpty())
            }
            problem("model_live_import_superseded") { c.runtime.prepareProduced(old) }
            noExecution(c)
        }
    }

    @Test fun epochLinearizesInsidePrepareNotAtBeginOrExporterImportStart() {
        OwnerModelFixtures().use { f ->
            var duringPrepare: () -> Unit = {}
            val c = live(f, verifyCode = { duringPrepare() })
            val old = reserve(c, candidate(f, c, "old"))
            val next = candidate(f, c, "new")
            assertTrue(old.validity.isCurrent(), "begin and fixture import do not reserve a preparation epoch")
            assertFalse(next.intent.used.get())
            val failure = IllegalStateException("SYNTHETIC stop before source/config/compiler preparation IO")
            duringPrepare = {
                assertFalse(old.validity.isCurrent(), "Invalidation must precede prepare's first owner-code callback")
                throw failure
            }
            assertSame(failure, assertFailsWith<IllegalStateException> {
                c.plans.prepare(next.intent, null, next.model, next.capture)
            })
            assertTrue(next.intent.used.get())
            assertFalse(old.validity.isCurrent(), "Later preparation failure cannot restore an old epoch")
            problem("model_intent_invalid_or_replayed") {
                c.plans.prepare(next.intent, null, next.model, next.capture)
            }
            noExecution(c)
        }
    }

    @Test fun replayingAnOldConsumedIntentCannotInvalidateOrReplaceTheNewerImport() {
        OwnerModelFixtures().use { f ->
            val c = live(f)
            val first = candidate(f, c, "first")
            val old = reserve(c, first)
            val next = reserve(c, candidate(f, c, "next"))
            assertFalse(old.validity.isCurrent())
            repeat(2) {
                problem("model_live_import_replayed") { c.runtime.reserveImport(first.intent, first.model) }
                assertTrue(next.validity.isCurrent())
            }
            problem("model_live_import_superseded") { c.runtime.prepareProduced(old) }
            problem("model_live_generation_unavailable") { c.runtime.prepareProduced(next) }
            assertTrue(next.validity.isCurrent(), "A missing generation grants nothing but does not resurrect a replay")
            noExecution(c)
        }
    }

    @Test fun anUnusedIntentCannotCrossTheRealPrepareBoundaryOrRevokeCurrentState() {
        OwnerModelFixtures().use { f ->
            val c = live(f)
            val current = reserve(c, candidate(f, c, "current"))
            val unused = candidate(f, c, "unused")
            problem("model_live_import_replayed") { c.runtime.reserveImport(unused.intent, unused.model) }
            assertFalse(unused.intent.used.get())
            assertTrue(current.validity.isCurrent())
            noExecution(c)
        }
    }

    @Test fun anEqualCopiedImportHandleIsNotTheRetainedCapabilityEvenBeforeAnyGenerationExists() {
        OwnerModelFixtures().use { f ->
            val c = live(f)
            val value = candidate(f, c, "original")
            val actual = reserve(c, value)
            val copied = OwnerImportedModel(actual.intent, actual.model)
            assertEquals(actual.namespace.digest, copied.namespace.digest)
            assertSame(actual.intent, copied.intent)
            assertSame(actual.model, copied.model)
            assertNotSame(actual, copied)
            problem("model_live_import_superseded") { c.runtime.prepareProduced(copied) }
            assertTrue(actual.validity.isCurrent())
            problem("model_live_generation_unavailable") { c.runtime.prepareProduced(actual) }
            noExecution(c)
        }
    }

    @Test fun aValidNewImportAtCapacityRevokesOldOutputWithoutAllocatingANewCapability() {
        OwnerModelFixtures().use { f ->
            val c = live(f, capacity = 1)
            val first = candidate(f, c, "first")
            val old = reserve(c, first)
            val next = candidate(f, c, "capacity-denied")
            assertTrue(next.intent.used.compareAndSet(false, true))
            problem("model_live_import_capacity") { c.runtime.reserveImport(next.intent, next.model) }
            assertFalse(old.validity.isCurrent(), "Quota failure is not permission to keep superseded output current")
            problem("model_live_import_superseded") { c.runtime.prepareProduced(old) }
            problem("model_live_import_superseded") { c.runtime.prepareProduced(OwnerImportedModel(next.intent, next.model)) }
            problem("model_live_import_replayed") { c.runtime.reserveImport(first.intent, first.model) }
            problem("model_live_import_capacity") { c.runtime.reserveImport(next.intent, next.model) }
            assertFalse(old.validity.isCurrent())
            noExecution(c)
        }
    }

    @Test fun foreignOwnerOrModelNamespaceCannotUseImportInvalidationAsARevocationPrimitive() {
        OwnerModelFixtures().use { f -> OwnerModelFixtures().use { foreign ->
            val c = live(f)
            val current = reserve(c, candidate(f, c, "current"))
            val local = candidate(f, c, "local")
            val other = live(foreign)
            val foreignInput = candidate(foreign, other, "foreign")
            assertTrue(local.intent.used.compareAndSet(false, true))
            assertTrue(foreignInput.intent.used.compareAndSet(false, true))
            problem("model_live_import_namespace_mismatch") { c.runtime.reserveImport(local.intent, foreignInput.model) }
            assertTrue(current.validity.isCurrent())
            problem("model_live_import_namespace_mismatch") { c.runtime.reserveImport(foreignInput.intent, foreignInput.model) }
            assertTrue(current.validity.isCurrent())
            noExecution(c)
            noExecution(other)
        } }
    }

    @Test fun invalidationIsStickyAndAnEqualImportCannotBeLateEnrolledAfterANewerOne() {
        OwnerModelFixtures().use { f ->
            val c = live(f)
            val first = candidate(f, c, "first")
            val old = reserve(c, first)
            val newer = reserve(c, candidate(f, c, "newer"))
            repeat(2) {
                old.validity.invalidate()
                problem("model_live_import_superseded") { c.runtime.prepareProduced(old) }
                problem("model_live_import_replayed") { c.runtime.reserveImport(first.intent, first.model) }
            }
            assertFalse(old.validity.isCurrent())
            assertTrue(newer.validity.isCurrent())
            noExecution(c)
        }
    }

    @Test fun kernelClockProjectionRetainsTheSameOriginalOwnerObjectOriginDurationAndIdentity() {
        val before = System.nanoTime()
        val original = OwnedApplicationDeadline.afterMillis(120_000)
        val after = System.nanoTime()
        assertTrue(original.startedAtNanos - before >= 0)
        assertTrue(after - original.startedAtNanos >= 0)
        val fence = ProducedPlanClock(original, original.identity, original.startedAtNanos, original.durationMillis)
        assertSame(original, fence.ownerClock)
        assertEquals(original.identity, fence.identity)
        assertEquals(original.startedAtNanos, fence.startedAtNanos)
        assertEquals(original.durationMillis, fence.durationMillis)
        assertFalse(fence.isExpired())
        assertTrue(fence.remainingMillis() in 1..original.durationMillis)
        val replacement = OwnedApplicationDeadline.afterMillis(original.durationMillis)
        assertNotEquals(original.identity, replacement.identity)
        assertNotSame(fence.ownerClock, replacement)
    }
}

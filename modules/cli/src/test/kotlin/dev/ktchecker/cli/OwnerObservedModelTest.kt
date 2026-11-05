package dev.ktchecker.cli

import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.*
import dev.ktchecker.projectmodel.*
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import dev.ktchecker.reviewcontroller.*
import dev.ktchecker.snapshot.MaterializedSnapshot
import kotlinx.serialization.json.JsonArray
import kotlin.test.*

/**
 * SYNTHETIC no-execution unit controls. Imports retain real registered immutable snapshots but
 * ordinary synthetic compiler bytes cannot make a produced graph. No generation is opened;
 * no Accepted output, kernel parent, compiler invocation or platform qualification is invented.
 */
class OwnerObservedModelTest {
    private data class Composition(val plans: OwnerModelPlans, val runtime: OwnerLiveModelRuntime, val cold: ModelAttemptGrants)
    private data class Candidate(val intent: OwnerModelIntent, val model: ObservedProjectModel, val capture: MaterializedSnapshot)

    private fun live(f: OwnerModelFixtures, capacity: Int = 16, verifyCode: () -> Unit = {}): Composition {
        val runtime = OwnerLiveModelRuntime(f.state.ownerId, f.installation,
            Digests.sha256("SYNTHETIC absent worker; never open a generation"),
            maximumActiveAttempts = 1, maximumRetainedAttempts = capacity)
        val cold = ModelAttemptGrants()
        return Composition(OwnerModelPlans(f.state, f.snapshots, f.configs, f.catalog, null, null, cold,
            verifyCode, liveRuntime = runtime), runtime, cold)
    }

    private fun candidate(f: OwnerModelFixtures, c: Composition, name: String,
        authorized: Boolean = false, transform: (GradleProjectExport) -> GradleProjectExport = { it }): Candidate {
        val invocation = ObservedExportInvocation(OwnedApplicationDeadline.afterMillis(120_000))
        val intent = c.plans.begin(f.binding(), f.request(name), authorized, invocation.originalDeadline)
        val capture = f.capture()
        val model = f.importObservedUnit(capture, intent.configuration.policyDigest, invocation, transform(f.graph(capture)))
        assertNull(model.jvmProducedGraph, "Synthetic ordinary bytes are not a pinned produced-graph fixture")
        return Candidate(intent, model, capture)
    }

    /** State transition simulation only, matching prepare's already-consumed intent boundary. */
    private fun reserve(c: Composition, value: Candidate): OwnerImportedModel {
        assertTrue(value.intent.used.compareAndSet(false, true))
        return c.runtime.reserveObservedImport(value.intent, value.model)
    }

    private fun problem(code: String, action: () -> Unit) = assertEquals(code, assertFailsWith<CliProblem>(block = action).code)
    private fun noExecution(c: Composition) {
        assertEquals(0, c.runtime.retainedGenerations()); assertEquals(0, c.runtime.retainedAttempts())
        assertEquals(0, c.cold.activeCount())
    }

    @Test fun unsupportedObservedPreparationRetainsTheWholeAll1663VectorButPublishesNoParentOrScalarAuthority() {
        OwnerModelFixtures().use { f ->
            val c = live(f); val value = candidate(f, c, "unsupported", authorized = true)
            val failure = assertFailsWith<ObservedModelPlanUnavailable> {
                c.plans.prepareObserved(value.intent, null, value.model, value.capture)
            }
            assertEquals("observed_model_complete_declaration_unavailable", failure.code)
            assertContains(failure.reasons, CONFIGURATION_INPUT_CLOSURE_UNPROVEN)
            assertFailsWith<UnsupportedOperationException> { (failure.reasons as MutableList<*>).clear() }
            val attempted = failure.attemptedPlan
            assertEquals(value.intent.binding, attempted.binding); assertEquals(value.intent.request, attempted.request)
            assertEquals(value.model.compilationIds.size, attempted.slots.size)
            assertNull(attempted.jvmProduced)
            val evidence = ObservedExportPlanEvidence(value.intent.binding, value.model.invocation.identity, value.model.evidenceIdentity)
            attempted.slots.forEach { slot ->
                assertEquals("unsupported", slot.state); assertNull(slot.identity)
                assertEquals(f.catalog.ids.sorted(), slot.input.requiredRuleIds); assertEquals(1663, slot.input.requiredRuleIds.size)
                assertEquals(value.model.snapshot, slot.input.snapshot)
                assertFalse(slot.input.context.fullProject); assertNull(slot.input.execution); assertNull(slot.input.outputRoot)
                assertContains(slot.reasons, CONFIGURATION_INPUT_CLOSURE_UNPROVEN)
                assertTrue(evidence.policies().all { slot.input.context.policies[it.key] == it.value })
                assertNull(c.plans.evidence(slot.input)); assertNull(c.plans.policy(value.intent.binding, slot.input))
                assertNull(c.cold.modelAuthority.authorize(slot.input)); assertNull(c.cold.executionAuthority.authorize(slot.input))
                assertEquals("model_plan_not_committed", assertFailsWith<KernelException> {
                    c.plans.observe(slot.input, slot.input) { error("No uncommitted archive callback may run") }
                }.code)
            }
            assertNull(c.plans.resolve(value.intent.binding, value.intent.request))
            val restored = live(f)
            assertNull(restored.plans.resolve(value.intent.binding, value.intent.request))
            attempted.slots.forEach { assertNull(restored.plans.evidence(it.input)) }
            problem("model_live_intent_not_restorable") {
                restored.plans.begin(value.intent.binding, value.intent.request, true)
            }
            noExecution(c); noExecution(restored)
        }
    }

    @Test fun genuineNewObservedPreparationInvalidatesOldEpochBeforeTheFirstPreparationIoEvenWhenItFails() {
        OwnerModelFixtures().use { f ->
            var checkpoint: () -> Unit = {}
            val c = live(f, verifyCode = { checkpoint() })
            val old = reserve(c, candidate(f, c, "old"))
            val next = candidate(f, c, "next")
            assertTrue(old.validity.isCurrent(), "begin/import are not the prepare-time epoch")
            val failure = IllegalStateException("SYNTHETIC stop at first preparation callback")
            checkpoint = { assertFalse(old.validity.isCurrent()); throw failure }
            assertSame(failure, assertFailsWith<IllegalStateException> {
                c.plans.prepareObserved(next.intent, null, next.model, next.capture)
            })
            assertTrue(next.intent.used.get()); assertFalse(old.validity.isCurrent())
            problem("model_intent_invalid_or_replayed") { c.plans.prepareObserved(next.intent, null, next.model, next.capture) }
            problem("model_live_import_superseded") { c.runtime.prepareProduced(old) }
            noExecution(c)
        }
    }

    @Test fun replayedInvocationUnderEitherOldOrFreshIntentCannotInvalidateTheNewerImport() {
        OwnerModelFixtures().use { f ->
            val c = live(f); val first = candidate(f, c, "first"); val old = reserve(c, first)
            val current = reserve(c, candidate(f, c, "current"))
            assertFalse(old.validity.isCurrent())
            problem("model_live_import_replayed") { c.runtime.reserveObservedImport(first.intent, first.model) }
            assertTrue(current.validity.isCurrent())
            val freshIntent = c.plans.begin(f.binding(), f.request("old-invocation-new-intent"), false,
                first.model.invocation.originalDeadline)
            assertTrue(freshIntent.used.compareAndSet(false, true))
            problem("model_live_import_replayed") { c.runtime.reserveObservedImport(freshIntent, first.model) }
            assertTrue(current.validity.isCurrent())
            problem("model_observed_not_verified") { current.model }
            assertNotNull(current.observedModel)
            noExecution(c)
        }
    }

    @Test fun unusedIntentOrForeignClockRejectsBeforeIntentConsumptionAndCurrentEpochInvalidation() {
        OwnerModelFixtures().use { f ->
            val c = live(f); val current = reserve(c, candidate(f, c, "current"))
            val unused = candidate(f, c, "unused")
            problem("model_live_import_replayed") { c.runtime.reserveObservedImport(unused.intent, unused.model) }
            assertFalse(unused.intent.used.get()); assertTrue(current.validity.isCurrent())
            val otherIntent = c.plans.begin(f.binding(), f.request("different-clock"), false,
                OwnedApplicationDeadline.afterMillis(unused.model.invocation.originalDeadline.durationMillis))
            problem("model_intent_invalid_or_replayed") {
                c.plans.prepareObserved(otherIntent, null, unused.model, unused.capture)
            }
            assertFalse(otherIntent.used.get()); assertTrue(current.validity.isCurrent())
            assertTrue(otherIntent.used.compareAndSet(false, true))
            problem("model_observed_original_deadline_mismatch") {
                c.runtime.reserveObservedImport(otherIntent, unused.model)
            }
            assertTrue(current.validity.isCurrent()); noExecution(c)
        }
    }

    @Test fun capacityFailureStillRevokesAGenuinelySupersededObservedImport() {
        OwnerModelFixtures().use { f ->
            val c = live(f, capacity = 1); val first = candidate(f, c, "first"); val old = reserve(c, first)
            val next = candidate(f, c, "capacity")
            problem("model_live_import_capacity") { c.plans.prepareObserved(next.intent, null, next.model, next.capture) }
            assertTrue(next.intent.used.get()); assertFalse(old.validity.isCurrent())
            assertNull(c.plans.resolve(next.intent.binding, next.intent.request))
            problem("model_live_import_replayed") { c.runtime.reserveObservedImport(first.intent, first.model) }
            problem("model_live_import_superseded") { c.runtime.prepareProduced(old) }
            noExecution(c)
        }
    }

    @Test fun installedOrdinaryColdModelRouteCannotAcceptTheObservedEntryPoint() {
        OwnerModelFixtures().use { f ->
            val c = f.composition(); val intent = c.plans.begin(f.binding(), f.request("cold-denied"), true)
            val invocation = ObservedExportInvocation(OwnedApplicationDeadline.afterMillis(120_000))
            val capture = f.capture(); val model = f.importObservedUnit(capture, intent.configuration.policyDigest, invocation)
            problem("observed_model_live_route_required") { c.plans.prepareObserved(intent, null, model, capture) }
            assertFalse(intent.used.get()); assertEquals(0, c.attempts.activeCount())
            assertNull(c.plans.resolve(intent.binding, intent.request))
        }
    }

    private fun assemble(f: OwnerModelFixtures, before: ObservedProjectModel?, after: Candidate): AssembledReviewPlan {
        val model = after.model
        val inventory = OwnerCatalogInventory(Digests.sha256(Digests.canonical(JsonArray(f.catalog.records))), f.catalog.ids)
        val source = OwnerObservedPlanMaterialization(model, after.capture.root) {
            check(f.snapshots.snapshot(f.snapshotIdentity, model.snapshot.snapshotId) == after.capture.manifest)
            if (!f.snapshots.verifyMaterialization(after.capture).valid) null else
                MaterializationVerification(after.capture.manifest.toContractManifest(), after.capture.root.toString(),
                    Digests.sha256("SYNTHETIC registered materialization identity"))
        }
        val policy = OwnerPolicyPreparer { request ->
            assertSame(model, request.observedModel)
            assertEquals("observed_model_not_verified", assertFailsWith<ReviewControllerException> { request.model }.code)
            val identity = CapturedPolicyIdentity(request.binding, after.intent.configuration.policyDigest,
                Digests.sha256("SYNTHETIC captured policy revision; no kernel registration"))
            val activation = PlanActivation(identity.configurationDigest, inventory.ruleIds.map {
                PlanRuleActivation(it, false, "synthetic-unit-captured-policy", "explicitly-disabled")
            })
            CapturedPlanPolicy(request, identity, request.withActivation(activation)) { identity }
        }
        return OwnerPlanAssembler(inventory, policy, OwnerExecutionPreparer {
            error("Observed assembler must not call the ordinary execution preparer")
        }).assembleObserved(after.intent.binding, after.intent.request, before, model, source, listOf(after.intent.binding.clientId))
    }

    @Test fun assemblerRetainsRemovedAndRetargetedBeforeScopesWithFullRulesAndNoVerifiedViews() {
        OwnerModelFixtures().use { f ->
            val c = live(f); val before = candidate(f, c, "before")
            val after = candidate(f, c, "after") { graph -> graph.copy(
                modules = graph.modules.filter { it.id == ":lib" },
                sourceSets = graph.sourceSets.filter { it.moduleId == ":lib" },
                compilations = graph.compilations.filter { it.moduleId == ":lib" }.map { it.copy(target = "retargetedJvm") }) }
            assertEquals(before.model.snapshot.snapshotId, after.model.snapshot.parentSnapshotId)
            val assembly = assemble(f, before.model, after)
            assertSame(before.model, assembly.provenance.observedBefore); assertSame(after.model, assembly.provenance.observedAfter)
            assertNotNull(assembly.provenance.observedImpact)
            assertEquals("observed_model_not_verified", assertFailsWith<ReviewControllerException> { assembly.provenance.after }.code)
            assertEquals("observed_impact_not_verified", assertFailsWith<ReviewControllerException> { assembly.provenance.impact }.code)
            assertEquals(3, assembly.plan.slots.size)
            assertEquals(setOf(":lib|ownerJvm|main", ":app|ownerJvm|main"), assembly.provenance.slots.mapNotNull { it.compilationId }.toSet())
            assertTrue(assembly.plan.slots.any { "required_context_retargeted_unresolved" in it.reasons })
            assertTrue(assembly.plan.slots.any { "required_compilation_removed_unresolved" in it.reasons })
            assembly.plan.slots.forEach { slot ->
                assertEquals("unsupported", slot.state); assertNull(slot.identity)
                assertEquals(f.catalog.ids.sorted(), slot.input.requiredRuleIds)
                assertContains(slot.reasons, CONFIGURATION_INPUT_CLOSURE_UNPROVEN)
                assertFalse(slot.input.context.fullProject)
            }
            assembly.provenance.slots.forEach { evidence ->
                assertNull(evidence.verifiedContext); assertNotNull(evidence.observedContext)
                assertNotNull(evidence.impactObligation, "Every retained current/historical scope has its exact observed impact obligation")
                assertNull(evidence.executionIdentity)
            }
            assertNull(assembly.declareJvmProduced(Digests.sha256("synthetic intent"), after.model.invocation.originalDeadlineIdentity,
                120_000) { error("Ordinary verified declaration must reject observed origin before callbacks") })
            assertNull(assembly.declareObservedJvmProduced(Digests.sha256("synthetic intent"), after.model.invocation.originalDeadlineIdentity,
                120_000) { error("No synthetic graph can make a declared execution grant") })
            noExecution(c)
        }
    }

    @Test fun emptyObservedModelKeepsOneExplicitFullCatalogUnavailableObligation() {
        OwnerModelFixtures().use { f ->
            val c = live(f); val empty = candidate(f, c, "empty") { it.copy(compilations = emptyList()) }
            val assembly = assemble(f, null, empty)
            val slot = assembly.plan.slots.single()
            assertEquals("project-model-unavailable", slot.slotId)
            assertEquals("unsupported", slot.state); assertNull(slot.identity)
            assertEquals(f.catalog.ids.sorted(), slot.input.requiredRuleIds)
            assertContains(slot.reasons, "compilation_model_empty")
            assertContains(slot.reasons, CONFIGURATION_INPUT_CLOSURE_UNPROVEN)
            assertFalse(slot.input.context.fullProject)
            assertNull(assembly.provenance.slots.single().observedContext)
            noExecution(c)
        }
    }
}

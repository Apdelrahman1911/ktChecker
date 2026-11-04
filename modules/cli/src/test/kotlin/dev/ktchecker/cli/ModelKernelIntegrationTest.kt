package dev.ktchecker.cli

import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.*
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * REAL SQLite admission/recovery, immutable archives and worker lifecycle with the owner bridge.
 * The provider only inspects capabilities and returns unqualified coverage. Synthetic compiler/
 * JDK inputs NEVER execute; this is not evidence of a successful Gradle or compiler invocation.
 */
class ModelKernelIntegrationTest {
    private val config = KernelConfig(workerCount = 1, globalParallelism = 1, perWorktreeParallelism = 1,
        heartbeatMillis = 50, leaseMillis = 500, pollMillis = 5, analysisTimeoutMillis = 15_000)

    private class Observer(val composition: OwnerModelFixtures.Composition, val afterPrepare: () -> Unit = {}) : PreparedInputObserver {
        val prepared = AtomicInteger()
        val finished = AtomicInteger()
        override fun prepared(original: AnalysisInput, effective: AnalysisInput, verifyFrozen: () -> Unit) {
            composition.plans.observe(original, effective, verifyFrozen)
            prepared.incrementAndGet()
            afterPrepare()
        }
        override fun finished(effective: AnalysisInput) {
            composition.attempts.finish(effective)
            finished.incrementAndGet()
        }
    }

    private class InspectingProvider(val f: OwnerModelFixtures, val c: OwnerModelFixtures.Composition) : AnalysisProvider {
        override val id = "synthetic-model-capability-inspector"
        override val version = "unit/1"
        override val supportedRuleIds = f.catalog.ids
        val executed = ConcurrentLinkedQueue<AnalysisInput>()
        override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
            cancellation.throwIfCancelled()
            val child = c.attempts.child(input, listOf(f.rule))
            val model = assertNotNull(c.attempts.modelAuthority.authorize(child))
            val execution = assertNotNull(c.attempts.executionAuthority.authorize(child))
            assertTrue(model.accepts(child))
            assertEquals(child.analysisKey(), execution.expectedAnalysisKey)
            assertEquals(f.catalog.ids.sorted(), input.requiredRuleIds)
            assertEquals(listOf(f.rule), child.requiredRuleIds)
            assertEquals(input.snapshot, child.snapshot)
            execution.verifySnapshot()
            executed += input
            // Nothing invokes c.compiler.analyze: its JDK/JAR fixture bytes must never execute.
            return ProviderResult(id, "incomplete", coverage = input.requiredRuleIds.map {
                Coverage(it, input.context.scope, "unavailable", "synthetic_owner_model_inspection_only")
            }, reasons = listOf("synthetic_owner_model_inspection_only"), providerVersion = version)
        }
    }

    private fun policy(f: OwnerModelFixtures, c: OwnerModelFixtures.Composition) = ResultPolicy.versioned("synthetic-owner-model-policy/1") { binding, input, raw ->
        f.configs.apply(requireNotNull(c.plans.policy(binding, input)), input, raw)
    }

    private fun await(kernel: KernelService, client: ClientRegistration, parent: ParentPlanStatus): List<ReviewStatus> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        return parent.slots.map { slot ->
            var status = kernel.status(client.client, slot.reviewId)
            while (status.result == null && System.nanoTime() < deadline) {
                Thread.sleep(5)
                status = kernel.status(client.client, slot.reviewId)
            }
            assertNotNull(status.result, "Synthetic lifecycle inspection did not finish its accepted obligation")
            assertTrue(status.result!!.coverage.all { !it.implemented && !it.tested && !it.qualified && it.certificateId == null })
            status
        }
    }

    private fun deliver(kernel: KernelService, client: ClientRegistration) {
        val events = kernel.events(client.client, limit = 100)
        assertFalse(events.hasMore)
        events.events.forEach { event ->
            val inserted = Digests.sha256("unit-host-insertion:${event.clientId}:${event.payloadDigest}")
            kernel.acknowledge(client.adapter, listOf(event.eventId), ContextReceipt("ack-${event.eventId}", inserted,
                "unit-context-${event.eventId}", Instant.EPOCH.toString()))
            kernel.observe(client.adapter, listOf(event.eventId), ObservationReceipt("observe-${event.eventId}",
                "unit-model-request-${event.eventId}", listOf(inserted)))
        }
    }

    @Test fun acceptedWholeParentUsesLiveArchiveBridgeAfterOriginalInputsDisappearAndNeverFinalizes() {
        OwnerModelFixtures().use { f ->
            f.setEnabled()
            val c = f.composition()
            val intent = c.plans.begin(f.binding(), f.request(), true)
            val capture = f.capture()
            val plan = c.plans.prepare(intent, null, f.import(capture, intent), capture)
            val observer = Observer(c)
            val provider = InspectingProvider(f, c)
            val path = f.work.resolve("broker")
            val (client, accepted) = KernelService.open(path, provider, config.copy(workerCount = 0),
                planAuthority = c.plans, preparedInputObserver = observer, resultPolicy = policy(f, c)).use { kernel ->
                val client = kernel.registerClient(f.binding())
                client to kernel.submitPlan(client.client, plan.request).also {
                    assertEquals("pending", it.state)
                    assertEquals(plan.assembly.plan.slots.size, it.slots.size)
                    assertTrue(it.slots.all { slot -> slot.requiredRuleIds == f.catalog.ids.sorted() })
                }
            }
            f.removeOriginalInputs(capture)
            // LIVE owner capabilities intentionally remain in this JVM; only the broker restarts.
            KernelService.open(path, provider, config, planAuthority = c.plans,
                preparedInputObserver = observer, resultPolicy = policy(f, c)).use { kernel ->
                assertEquals(accepted.planId, kernel.submitPlan(client.client, plan.request).planId)
                await(kernel, client, accepted).forEach {
                    assertEquals(listOf("synthetic_owner_model_inspection_only"), it.result!!.reasons)
                    assertEquals(f.catalog.ids, it.result!!.coverage.map { row -> row.ruleId }.toSet())
                }
                assertEquals(plan.assembly.plan.slots.size, provider.executed.size)
                assertEquals(provider.executed.size, observer.prepared.get())
                assertEquals(provider.executed.size, observer.finished.get())
                assertEquals(0, c.attempts.activeCount())
                val acceptedEvent = kernel.events(client.client).events.single { it.kind == "plan_accepted" }
                assertEquals(accepted.planId, ContractJson.decodeFromString<ReviewStatus>(acceptedEvent.payload).parentPlan!!.planId)
                deliver(kernel, client)
                val gate = kernel.prepareGate(client.client)
                assertFalse(gate.ready)
                assertFalse(gate.reasons.any { it.startsWith("insertion_missing:") || it.startsWith("model_observation_missing:") })
                val completions = AtomicInteger()
                assertFalse(kernel.completeGate(client.client, gate.cutId) { completions.incrementAndGet() }.allowed)
                assertEquals(0, completions.get())
            }
        }
    }

    @Test fun workerCancellationBetweenOwnerPreparationAndProviderEntryRevokesEveryAttemptGrant() {
        OwnerModelFixtures().use { f ->
            f.setEnabled()
            val c = f.composition()
            val intent = c.plans.begin(f.binding(), f.request(), true)
            val capture = f.capture()
            val plan = c.plans.prepare(intent, null, f.import(capture, intent), capture)
            val observer = Observer(c) { Thread.sleep(60) }
            val provider = InspectingProvider(f, c)
            KernelService.open(f.work.resolve("broker-cancel"), provider, config.copy(analysisTimeoutMillis = 25),
                planAuthority = c.plans, preparedInputObserver = observer, resultPolicy = policy(f, c)).use { kernel ->
                val client = kernel.registerClient(f.binding())
                val accepted = kernel.submitPlan(client.client, plan.request)
                await(kernel, client, accepted).forEach {
                    assertEquals(listOf("analysis_cancelled_or_deadline_exceeded"), it.result!!.reasons)
                }
                assertEquals(accepted.slots.size, observer.prepared.get())
                assertEquals(accepted.slots.size, observer.finished.get())
                assertTrue(provider.executed.isEmpty(), "The owned timeout must happen before the provider can run")
                assertEquals(0, c.attempts.activeCount())
            }
        }
    }

    @Test fun ownerPreparationExceptionAfterIssuingGrantStillRunsKernelTeardownAndRetainsFailure() {
        OwnerModelFixtures().use { f ->
            f.setEnabled()
            val c = f.composition()
            val intent = c.plans.begin(f.binding(), f.request(), true)
            val capture = f.capture()
            val plan = c.plans.prepare(intent, null, f.import(capture, intent), capture)
            val observer = Observer(c) { throw KernelException("synthetic_partial_preparation_failure", "Unit fault injection after grant creation") }
            val provider = InspectingProvider(f, c)
            KernelService.open(f.work.resolve("broker-partial"), provider, config,
                planAuthority = c.plans, preparedInputObserver = observer, resultPolicy = policy(f, c)).use { kernel ->
                val client = kernel.registerClient(f.binding())
                val accepted = kernel.submitPlan(client.client, plan.request)
                await(kernel, client, accepted).forEach { assertEquals(listOf("synthetic_partial_preparation_failure"), it.result!!.reasons) }
                assertEquals(accepted.slots.size, observer.prepared.get())
                assertEquals(accepted.slots.size, observer.finished.get())
                assertTrue(provider.executed.isEmpty())
                assertEquals(0, c.attempts.activeCount())
            }
        }
    }

    @Test fun fullOwnerRestartPreservesAcceptedObligationsButCannotDeserializeLiveCompilerAuthority() {
        OwnerModelFixtures().use { f ->
            f.setEnabled()
            val original = f.composition()
            val intent = original.plans.begin(f.binding(), f.request(), true)
            val capture = f.capture()
            val plan = original.plans.prepare(intent, null, f.import(capture, intent), capture)
            val path = f.work.resolve("broker-restart")
            val initialProvider = InspectingProvider(f, original)
            val (client, accepted) = KernelService.open(path, initialProvider, config.copy(workerCount = 0),
                planAuthority = original.plans, preparedInputObserver = Observer(original), resultPolicy = policy(f, original)).use { kernel ->
                val client = kernel.registerClient(f.binding())
                client to kernel.submitPlan(client.client, plan.request)
            }
            val restored = f.composition()
            val observer = Observer(restored)
            val provider = InspectingProvider(f, restored)
            f.removeOriginalInputs(capture)
            KernelService.open(path, provider, config, planAuthority = restored.plans,
                preparedInputObserver = observer, resultPolicy = policy(f, restored)).use { kernel ->
                assertEquals(accepted.planId, kernel.submitPlan(client.client, plan.request).planId,
                    "Durable exact retry must not erase the previously accepted whole obligation")
                await(kernel, client, accepted).forEach {
                    assertEquals(listOf("model_live_authority_unavailable"), it.result!!.reasons)
                    assertNotNull(kernel.status(client.client, it.reviewId))
                }
                assertTrue(provider.executed.isEmpty())
                assertEquals(0, observer.prepared.get())
                assertEquals(accepted.slots.size, observer.finished.get())
                assertEquals(0, restored.attempts.activeCount())
                val status = kernel.planStatus(client.client, accepted.planId)
                assertEquals(accepted.slots.map { it.reviewId }, status.slots.map { it.reviewId })
                assertEquals("incomplete", status.state)
                val gate = kernel.prepareGate(client.client)
                assertFalse(kernel.completeGate(client.client, gate.cutId).allowed)
                assertTrue(gate.requiredEventIds.isNotEmpty(), "Lost live authority must not consume required delivery obligations")
            }
        }
    }
}

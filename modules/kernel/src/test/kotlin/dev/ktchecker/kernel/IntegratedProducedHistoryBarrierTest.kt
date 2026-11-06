package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/** Real joint barrier and schema3 history; all owner/provider/merge/delivery authorities are SYNTHETIC. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class IntegratedProducedHistoryBarrierTest {
    @TempDir lateinit var temp: Path

    private class Registry(private val produced: ProducedLane1665Fixture) : ReviewPlanAuthority, ReviewPlanRoutingAuthority {
        var declared: RequiredReviewPlan? = null
        var route: AuthorizedReviewRoute? = null
        override fun resolve(binding: ClientBinding, request: SubmitPlanRequest): RequiredReviewPlan? =
            declared?.takeIf { it.binding == binding && it.request == request } ?: produced.resolve(binding, request)
        override fun authorizeProduced(request: ProducedPlanAdmissionRequest) = produced.authorizeProduced(request)
        override fun authorize(binding: ClientBinding, request: SubmitPlanRequest, declared: RequiredReviewPlan) =
            route?.takeIf { it.origin == declared && declared.binding == binding && declared.request == request }
    }
    private class Fence(val check: IntegratedSnapshotCheck, private val finalPlans: List<RequiredReviewPlan>) : IntegratedSnapshotFence {
        private val monitor = Any()
        val requests = ConcurrentLinkedQueue<IntegratedFenceRequest>()
        override fun <T> withCurrentIntegratedSnapshot(request: IntegratedFenceRequest, action: (IntegratedSnapshotCheck) -> T): T =
            synchronized(monitor) {
                // The attestation was selected BEFORE receiving request, not copied from callback arguments.
                finalPlans.forEach { plan -> plan.slots.forEach { InputArchive.verifyFrozen(it.input) } }
                requests += request
                action(check)
            }
    }
    private data class Scenario(
        val service: KernelService, val f: ProducedLane1665Fixture, val clients: List<ClientRegistration>,
        val declared: RequiredReviewPlan, val history: ParentPlanStatus, val finals: List<ParentPlanStatus>,
        val finalPlans: List<RequiredReviewPlan>, val obligations: List<IntegratedPatchObligation>,
        val fence: Fence, val authority: IntegratedCompletionAuthority, val effects: AtomicInteger, val release: CountDownLatch,
    ) {
        val origin get() = clients.first().client
        fun barrier(limits: IntegratedCompletionLimits = IntegratedCompletionLimits()) = IntegratedCompletionBarrier(service, authority, limits)
    }

    private fun withDeferredHistory(observed: Boolean = false, body: (Scenario) -> Unit) {
        val f = ProducedLane1665Fixture(temp.resolve("fixture")); val registry = Registry(f); val typed = f.Provider()
        val finalInputs = AtomicReference<List<AnalysisInput>>(emptyList())
        val provider = object : AnalysisProvider {
            override val id = ProducedLane1665Fixture.PROVIDER
            override val supportedRuleIds = f.rules.toSet()
            override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
                if (ProducedLane1665Fixture.PREVIEW in input.context.policies) return typed.analyze(input, cancellation)
                cancellation.throwIfCancelled()
                assertTrue(finalInputs.get().any { it.snapshot == input.snapshot && it.context == input.context && it.requiredRuleIds == input.requiredRuleIds })
                val key = input.analysisKey()
                // Ordinary positive kernel fixture ONLY. These flags are NOT real compiler/rule qualification.
                return ProviderResult(id, "complete", coverage = f.rules.map { rule -> Coverage(rule, input.context.scope, "passed",
                    provenance = "executed", certificateId = Digests.sha256("synthetic-final-only:$key:$rule"),
                    implemented = true, tested = true, qualified = true) })
            }
        }
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        KernelService.open(temp.resolve("ledger"), provider, ProducedLane1665Fixture.CONFIG, planAuthority = registry,
            routingAuthority = registry, preparedInputObserver = f.observer).use { service ->
            try {
                val clients = listOf("a", "b").map { id -> service.registerClient(
                    ClientBinding("owner", "workspace", "tree", id, "lineage-$id", 1, "session-$id")) }
                val declared = f.definition(clients.first().binding, "before-integration",
                    observedInvocation = if (observed) "synthetic-observed-history-export" else null)
                f.resolver[declared.request.planRef] = { request ->
                    entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS))
                    ProducedSlotResolution.Ready(f.consumer(request))
                }
                val history = service.submitPlan(clients.first().client, declared.request)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                f.finished(service, clients.first(), history.slots.first().reviewId)

                val source = temp.resolve("final-source"); Files.createDirectories(source)
                val previous = declared.slots.first().input
                previous.snapshot.files.forEach { file -> Files.copy(Path.of(previous.materializedRoot).resolve(file.path), source.resolve(file.path)) }
                val snapshot = previous.snapshot.copy(snapshotId = Digests.sha256("synthetic-integrated-after-produced"),
                    parentSnapshotId = previous.snapshot.snapshotId)
                val inputs = declared.slots.map { slot -> slot.input.copy(snapshot = snapshot, materializedRoot = source.toString(),
                    // Independent SYNTHETIC final-model authority, not a conversion of any
                    // observed model to a verified capability. Its clean vector cannot erase D.
                    context = if (observed) slot.input.context.copy(fullProject = true,
                        modelDigest = Digests.sha256("synthetic-independent-final-model"),
                        policies = slot.input.context.policies - OBSERVED_EXPORT_POLICY_KEYS)
                    else slot.input.context.copy(fullProject = true)) }
                finalInputs.set(inputs)
                val plans = clients.map { client ->
                    val name = "final-${client.binding.clientId}"
                    RequiredReviewPlan(client.binding, SubmitPlanRequest("patch-$name", "key-$name", "ref-$name"),
                        Digests.sha256(canonical(snapshot)), declared.generatedInputsDigest, Digests.sha256("synthetic-final-model"),
                        Digests.sha256("synthetic-final-impact"), inputs.mapIndexed { index, input ->
                            RequiredPlanSlot(declared.slots[index].slotId, input, assertNotNull(contextIdentity(input)))
                        }, listOf(client.binding.clientId))
                }
                val recipients = clients.zip(plans).map { (client, plan) ->
                    val digest = Digests.sha256(canonical(plan))
                    val preparation = PlanRecipientPreparation(digest, Digests.sha256("synthetic-final-policy:$digest"),
                        Digests.sha256("synthetic-final-execution:$digest"))
                    AuthorizedPlanRecipient(plan, client.adapter, preparation) {
                        plan.slots.forEach { InputArchive.verifyFrozen(it.input) }; preparation
                    }
                }
                registry.declared = plans.first().copy(requiredRecipientClientIds = clients.map { it.binding.clientId })
                registry.route = AuthorizedReviewRoute(assertNotNull(registry.declared), recipients)
                service.submitPlan(clients.first().client, assertNotNull(registry.declared).request)
                val finals = clients.zip(plans).map { (client, plan) ->
                    val id = service.ledger.read { c -> assertNotNull(c.one("SELECT plan_id FROM parent_plans WHERE client_id=? AND idempotency_key=?",
                        client.binding.clientId, plan.request.idempotencyKey) { it.getString(1) }) }
                    service.planStatus(client.client, id).slots.forEach { f.finished(service, client, it.reviewId) }
                    service.planStatus(client.client, id).also { assertEquals("complete", it.state) }
                }
                val obligations = service.ledger.read { c -> clients.flatMap { client -> obligations(c, client.binding.clientId) } }
                val check = IntegratedSnapshotCheck(true, true, snapshot.snapshotId, "synthetic-final-integration-epoch",
                    Digests.sha256("synthetic-independent-retained-merge-proof"), plans.map { plan ->
                        val input = plan.slots.last().input
                        IntegratedMemberSnapshotCheck(plan.binding, Digests.sha256(canonical(plan)), SnapshotCheck(true,
                            input.snapshot.snapshotId, 1, input.context.configurationDigest, input.context.modelDigest,
                            input.context.dependencyDigest, input.context.toolchainDigest, "synthetic-final-analysis-epoch", true,
                            contextIdentities = plan.slots.map { assertNotNull(it.identity) }, sourceBindingDigest = plan.sourceBindingDigest,
                            generatedInputsDigest = plan.generatedInputsDigest))
                    }, obligations.map { obligation ->
                        val identity = integratedObligationDigest(obligation)
                        IntegratedPatchDisposition(identity, "incorporated", Digests.sha256("synthetic-independent-patch-proof:$identity"))
                    })
                val fence = Fence(check, plans); val effects = AtomicInteger()
                val action = object : IntegratedCompletionAction {
                    override val identity = Digests.sha256("synthetic-final-effect")
                    override fun perform(permit: IntegratedCompletionPermit): IntegratedActionReceipt {
                        effects.incrementAndGet()
                        return IntegratedActionReceipt(permit.cutId, permit.scopeDigest, Digests.sha256("synthetic-effect-receipt"))
                    }
                }
                val members = clients.zip(plans).map { (client, plan) ->
                    val digest = Digests.sha256(canonical(plan)); val readiness = Digests.sha256("synthetic-final-readiness:$digest")
                    AuthorizedIntegratedMember(client.binding, plan.request, digest, client.adapter, readiness) {
                        plan.slots.forEach { InputArchive.verifyFrozen(it.input) }; readiness
                    }
                }
                val grant = AuthorizedIntegratedCompletion("completion", clients.first().binding, clients.first().binding.clientId,
                    Digests.sha256("synthetic-final-registration"), Digests.sha256(canonical(f.rules)), f.rules, members, fence, action)
                val authority = IntegratedCompletionAuthority { binding, ref -> grant.takeIf { binding == it.origin && ref == it.completionRef } }
                body(Scenario(service, f, clients, declared, history, finals, plans, obligations, fence, authority, effects, release))
            } finally { release.countDown() }
        }
    }

    private fun obligations(c: Connection, client: String): List<IntegratedPatchObligation> =
        c.query("SELECT review_id,patch_id,request_hash,input_json,sequence FROM reviews WHERE client_id=? ORDER BY sequence", client) { row ->
            val input = ContractJson.decodeFromString<AnalysisInput>(row.getString(4))
            IntegratedPatchObligation(client, "review", row.getString(1), row.getString(2), row.getString(3), input.analysisKey(), input.snapshot.snapshotId, row.getLong(5))
        } + c.query("SELECT plan_id,patch_id,request_hash,plan_digest,definition_json,review_frontier FROM parent_plans WHERE client_id=? ORDER BY rowid", client) { row ->
            val plan = ContractJson.decodeFromString<RequiredReviewPlan>(row.getString(5))
            IntegratedPatchObligation(client, "parent_plan", row.getString(1), row.getString(2), row.getString(3), row.getString(4),
                plan.slots.first().input.snapshot.snapshotId, row.getLong(6))
        }

    private fun assertDeniedWholeHistory(s: Scenario, cut: IntegratedCompletionCut) {
        assertFalse(cut.ready); assertTrue("joint_produced_profile_unavailable:a" in cut.reasons)
        if (s.declared.jvmProduced?.observedExport != null) {
            s.history.slots.forEach { slot -> assertTrue("$OBSERVED_EXPORT_CONFIGURATION_GAP:a:${slot.reviewId}" in cut.reasons) }
            assertTrue(s.finalPlans.all { plan -> plan.slots.all { observedExportReasons(it.input).isEmpty() } })
            assertTrue(OBSERVED_EXPORT_CONFIGURATION_GAP in s.service.planStatus(s.origin, s.history.planId).reasons)
        }
        assertTrue("joint_parent_incomplete:a:${s.history.planId}" in cut.reasons)
        assertTrue(cut.reasons.none { it.startsWith("joint_insertion_missing:") || it.startsWith("joint_model_observation_missing:") ||
            it.startsWith("joint_member_source_configuration_mismatch:") || it.startsWith("joint_final_scope_incomplete:") ||
            it.startsWith("current_coverage_missing:") || it == "joint_historical_patch_dispositions_incomplete" }, cut.reasons.toString())
        assertEquals(s.finals.map { it.planId }, cut.scope.members.map { it.planId })
        assertEquals(3330, cut.scope.requiredCoverage.size)
        cut.scope.requiredCoverage.groupBy { it.context }.values.forEach { assertEquals(s.f.rules, it.map { pair -> pair.ruleId }) }
        val request = s.fence.requests.last()
        assertEquals(s.obligations.toSet(), request.historicalObligations.toSet()); assertEquals(s.obligations.size, request.historicalObligations.size)
        s.history.slots.forEachIndexed { index, slot ->
            val obligation = request.historicalObligations.single { it.obligationId == slot.reviewId }
            assertEquals(s.declared.slots[index].input.analysisKey(), obligation.inputOrPlanDigest)
        }
        val completed = s.barrier().complete(s.origin, cut.cutId)
        assertFalse(completed.allowed); assertFalse(completed.actionPerformed); assertEquals(0, s.effects.get())
        s.service.ledger.read { c ->
            assertEquals(listOf("open", "open"), c.query("SELECT gate_state FROM clients ORDER BY client_id") { it.getString(1) })
            assertEquals(6L, c.count("SELECT COUNT(*) FROM reviews")); assertEquals(3L, c.count("SELECT COUNT(*) FROM parent_plans"))
            assertEquals(2L, c.count("SELECT COUNT(*) FROM reviews WHERE kind='produced' AND work_key IS NULL"))
        }
    }

    @Test fun freshCompleteRoutedFinalSnapshotCannotOmitDeferredDOrPromoteItsLaterResolvedPreviewHistory() = withDeferredHistory { s ->
        s.clients.forEach { s.f.deliver(s.service, it) }
        val consumerReview = s.history.slots.last().reviewId
        assertNull(s.f.resolved(s.service, consumerReview))
        val before = s.service.ledger.read { c -> obligations(c, "a") }
        val deferred = s.barrier().prepare(s.origin, "completion")
        assertTrue("joint_slot_deferred:a:$consumerReview" in deferred.reasons)
        assertTrue("joint_execution_work_unavailable:a:$consumerReview" in deferred.reasons)
        assertTrue("joint_publication_not_quiescent:a" in deferred.reasons)
        assertDeniedWholeHistory(s, deferred)

        s.release.countDown(); s.f.finished(s.service, s.clients.first(), consumerReview)
        assertEquals("ready", s.f.resolved(s.service, consumerReview))
        val consumer = s.f.typedAttempts.single { it.execution.slotId == ProducedLane1665Fixture.CONSUMER }
        assertNotEquals(s.declared.slots.last().input.analysisKey(), consumer.original.analysisKey())
        s.clients.forEach { s.f.deliver(s.service, it) }
        val resolved = s.barrier().prepare(s.origin, "completion")
        assertFalse("joint_slot_deferred:a:$consumerReview" in resolved.reasons)
        assertFalse("joint_execution_work_unavailable:a:$consumerReview" in resolved.reasons)
        assertFalse("joint_publication_not_quiescent:a" in resolved.reasons)
        assertDeniedWholeHistory(s, resolved)
        assertEquals(before, s.service.ledger.read { c -> obligations(c, "a") })
        assertEquals(1, s.f.resolutionCalls.getValue(s.declared.request.planRef).get())
        assertEquals(2, s.f.typedAttempts.size)
    }

    @Test fun jointHistoryByteLimitCountsOriginalDeferredRowEvenWhenItsExecutionAndResultOperandsAreNull() = withDeferredHistory { s ->
        s.clients.forEach { s.f.deliver(s.service, it) }
        val consumer = s.history.slots.last().reviewId
        assertNull(s.f.resolved(s.service, consumer))
        assertDeniedWholeHistory(s, s.barrier().prepare(s.origin, "completion")) // Same entire dataset fits default caps.
        val (total, deferredBytes) = metadataBytes(s.service, consumer)
        assertTrue(deferredBytes > 1); assertTrue(total <= IntegratedCompletionLimits().maxHistoryBytes)
        val limit = total - deferredBytes / 2
        assertTrue(total - deferredBytes <= limit && limit < total)
        val calls = s.fence.requests.size
        val failure = assertFailsWith<KernelException> {
            s.barrier(IntegratedCompletionLimits(maxHistoryBytes = limit)).prepare(s.origin, "completion")
        }
        assertEquals("joint_history_limit", failure.code)
        assertEquals(calls, s.fence.requests.size, "Whole-history rejection precedes the composite fence, not a projected subset")
        assertEquals(0, s.effects.get()); assertNull(s.f.resolved(s.service, consumer))
        s.service.ledger.read { c ->
            assertEquals(6L, c.count("SELECT COUNT(*) FROM reviews")); assertEquals(3L, c.count("SELECT COUNT(*) FROM parent_plans"))
        }
    }

    @Test fun independentCleanIntegratedFinalModelCannotEraseObservedExportGapBeforeOrAfterConsumerResolution() =
        withDeferredHistory(observed = true) { s ->
            s.clients.forEach { s.f.deliver(s.service, it) }
            val consumerReview = s.history.slots.last().reviewId
            assertNull(s.f.resolved(s.service, consumerReview))
            val before = s.service.ledger.read { c -> obligations(c, "a") }
            val deferred = s.barrier().prepare(s.origin, "completion")
            assertTrue("joint_slot_deferred:a:$consumerReview" in deferred.reasons)
            assertDeniedWholeHistory(s, deferred)

            s.release.countDown(); s.f.finished(s.service, s.clients.first(), consumerReview)
            s.clients.forEach { s.f.deliver(s.service, it) }
            val resolved = s.barrier().prepare(s.origin, "completion")
            assertFalse("joint_slot_deferred:a:$consumerReview" in resolved.reasons)
            assertFalse("joint_publication_not_quiescent:a" in resolved.reasons)
            assertDeniedWholeHistory(s, resolved)
            assertEquals(before, s.service.ledger.read { c -> obligations(c, "a") })
            assertEquals(2, s.f.typedAttempts.size)
            assertTrue(s.f.typedAttempts.all { it.execution.certificate.observedExport == s.declared.jvmProduced?.observedExport })
            assertEquals(1, s.f.resolutionCalls.getValue(s.declared.request.planRef).get())
            assertEquals(0, s.effects.get())
        }

    /** Independent row/cell UTF-8 accounting, not a copy of the production SQL SUM/COALESCE expression. */
    private fun metadataBytes(service: KernelService, deferredReview: String): Pair<Long, Long> = service.ledger.read { c ->
        fun bytes(values: List<String?>): Long = values.sumOf { it?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L }
        var total = 0L; var deferred = 0L
        c.query("""SELECT r.review_id,r.input_json,r.request_json,r.execution_input_json,w.frozen_json,w.result_json,
            r.effective_result_json,w.validation_json,w.progress_faults_json,r.policy_validation_json,r.policy_certificate,
            r.execution_certificate,r.resolution_reasons_json FROM review_executions r LEFT JOIN work w ON w.work_key=r.work_key""") { row ->
            val cells = (2..13).map { row.getString(it) }; val size = bytes(cells); total += size
            if (row.getString(1) == deferredReview) {
                assertNull(row.getString(5)); assertNull(row.getString(6)); assertNull(row.getString(7))
                assertNotNull(row.getString(2)); assertNotNull(row.getString(3)); assertNotNull(row.getString(4)); deferred = size
            }
        }
        fun add(sql: String, columns: Int) { c.query(sql) { row -> total += bytes((1..columns).map { row.getString(it) }) } }
        add("SELECT definition_json FROM parent_plans", 1)
        add("""SELECT archives_json,admission_digest,origin_instance,authority_identity,provider_key,producer_slot_id,consumer_slot_id,fair_key
            FROM produced_plan_admissions""", 8)
        add("SELECT claim_nonce,origin_instance,slot_id,CAST(reserved_archive_bytes AS TEXT) FROM plan_slot_resolution_claims", 4)
        add("SELECT record_json,record_digest,resolved_input_json,certificate_id,work_key,reasons_json FROM plan_slot_resolutions", 6)
        add("SELECT p.record_json,p.certificate_id,p.origin_instance FROM work_publications p JOIN review_executions r ON r.work_key=p.work_key", 3)
        add("SELECT payload FROM events", 1)
        add("SELECT definition_json FROM routing_batches", 1)
        total to deferred
    }
}

package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * Real isolated SQLite/WAL, archives, two service instances and concurrent client/worker threads.
 * ALL provider, shared-API impact, model/config/merge and delivery authorities are SYNTHETIC.
 * In particular planPassed's certificate flags are fixture contracts, NOT compiler qualification.
 * Reconnect is a quiescent service reopen plus lost adapter response/checkpoint replay, NOT a
 * process-kill test. KernelStressTest owns the larger dispatch-fairness oracle; this suite adds
 * mixed-finding history/delivery composition, rather than another clean queue benchmark.
 */
class KernelSustainedHistoryTest {
    @TempDir lateinit var temp: Path
    private val shared = RuleContext(target = "jvm", module = ":shared-api")
    private val consumer = RuleContext(target = "jvm", module = ":affected-consumer")
    private val contexts = listOf(shared, consumer)
    private val config = KernelConfig(workerCount = 1, globalParallelism = 2, perWorktreeParallelism = 2,
        heartbeatMillis = 40, leaseMillis = 160, pollMillis = 5, analysisTimeoutMillis = 15_000)

    @Test @Timeout(value = 90, unit = TimeUnit.SECONDS)
    fun seededFindingHistoriesRequireCurrentConsumerCoverageAndIndependentObservationAfterReconnect() {
        listOf(17, 83).forEach { runHistory(it, cancelConsumers = false) }
    }

    @Test @Timeout(value = 90, unit = TimeUnit.SECONDS)
    fun seededConsumerCancellationsRemainJointObligationsAfterCleanAncestralRechecks() {
        listOf(29, 97).forEach { runHistory(it, cancelConsumers = true) }
    }

    private fun runHistory(seed: Int, cancelConsumers: Boolean) = History(seed).use { h ->
        val random = Random(seed)
        var parent: String? = null
        lateinit var heldConsumer: Ticket
        repeat(8) { wave ->
            // The final historical defect is unique: a late old publication must not disappear
            // behind the newer clean shared-API-only result, nor overwrite the later full recheck.
            val defects = when {
                wave == 7 -> listOf("late-consumer-$seed")
                wave == 0 || random.nextBoolean() -> listOf("api-contract-${random.nextInt(3)}")
                else -> emptyList()
            }
            val batch = h.admit(wave, parent, contexts, defects, origin = (seed + wave) % 2)
            parent = batch.tickets.first().input.snapshot.snapshotId
            batch.tickets.forEach { assertTrue(it.entered.await(5, TimeUnit.SECONDS), "seed=$seed wave=$wave provider entry") }
            val cancelClient = if (cancelConsumers && wave % 2 == 1) (seed + wave / 2) % 2 else null
            h.clientRound(batch, cancelClient, List(2) { random.nextInt(1, 4) }, List(2) { random.nextInt(3) })
            val order = if (wave == 7) batch.tickets else batch.tickets.shuffled(random)
            order.forEach { ticket ->
                if (wave == 7 && ticket.input.context.scope == consumer) heldConsumer = ticket
                else h.finish(ticket)
            }
            if (wave != 7) {
                h.assertCut(h.boundary().prepare(h.clients.first().client, h.completionRef))
                if (wave == 2 || wave == 5) h.reconnectBetweenPages()
            }
        }

        // A clean result for the changed API context is not a substitute for its affected
        // consumer's accepted context/rule. Both clients still owe the whole historical vector.
        val narrow = h.admit(8, parent, listOf(shared), emptyList(), origin = seed % 2)
        parent = narrow.tickets.single().input.snapshot.snapshotId
        assertTrue(narrow.tickets.single().entered.await(5, TimeUnit.SECONDS))
        h.clientRound(narrow, null, listOf(2, 3), listOf(2, 1))
        h.finish(narrow.tickets.single())
        val pending = h.boundary().prepare(h.clients.first().client, h.completionRef)
        h.assertCut(pending)
        assertTrue(pending.reasons.any { it.startsWith("review_pending:") }, "seed=$seed: held peer still owes work")
        assertFalse(pending.ready)
        h.finish(heldConsumer)
        val missingConsumer = h.boundary().prepare(h.clients.first().client, h.completionRef)
        h.assertCut(missingConsumer)
        h.clients.forEach { client ->
            assertContains(missingConsumer.reasons, "current_coverage_missing:${consumer.id}:$PLAN_TEST_RULE:${client.binding.clientId}")
            assertContains(missingConsumer.reasons, "open_finding:${fingerprint(heldConsumer.input, "late-consumer-$seed")}:${client.binding.clientId}")
        }
        assertFalse(missingConsumer.ready)

        val finalBatch = h.admit(9, parent, contexts, emptyList(), origin = 0)
        finalBatch.tickets.forEach { assertTrue(it.entered.await(5, TimeUnit.SECONDS)) }
        h.clientRound(finalBatch, null, listOf(3, 1), listOf(2, 0))
        finalBatch.tickets.shuffled(random).forEach(h::finish)
        h.assertAllEvidence()
        h.clients.indices.forEach { h.drain(it) }
        h.deliver(0, h.delivery[0].fetched, observe = true)
        h.deliver(1, h.delivery[1].fetched, observe = false)
        h.assertReceipts()

        val boundary = h.boundary()
        val insertedOnly = boundary.prepare(h.clients.first().client, h.completionRef)
        h.assertCut(insertedOnly)
        assertTrue(insertedOnly.reasons.none { it.startsWith("current_coverage_missing:") || it.startsWith("open_finding:") })
        val unobservedPeer = h.delivery[1].fetched.map { it.eventId }.toSet() - h.delivery[1].observed
        assertTrue(unobservedPeer.isNotEmpty(), "The final peer result has never been observed")
        assertEquals(unobservedPeer.map { "joint_model_observation_missing:${h.clients[1].binding.clientId}:$it" }.toSet(),
            insertedOnly.reasons.filter { it.startsWith("joint_model_observation_missing:") }.toSet())
        assertFalse(insertedOnly.ready)
        assertFalse(boundary.complete(h.clients.first().client, insertedOnly.cutId).allowed)
        assertEquals(0, h.actionCalls.get())

        h.deliver(1, h.delivery[1].fetched, observe = true)
        val cut = boundary.prepare(h.clients.first().client, h.completionRef)
        h.assertCut(cut)
        assertEquals(contexts.map { IntegratedCoverageRequirement(it, PLAN_TEST_RULE) }.toSet(), cut.scope.requiredCoverage.toSet())
        assertEquals(h.clients.map { it.binding }.toSet(), cut.scope.members.map { it.binding }.toSet())
        val before = h.retainedCounts()
        if (cancelConsumers) {
            // Clean complete certificates resolve old findings, NOT cancelled subscriptions or
            // their parent obligations. No member may be claimed as a convenient clean subset.
            assertFalse(cut.ready)
            assertEquals(h.cancellationReasons(), cut.reasons.toSet())
            assertFalse(boundary.complete(h.clients.first().client, cut.cutId).allowed)
            assertEquals(listOf("open", "open"), h.gateStates())
            assertEquals(0, h.actionCalls.get())
        } else {
            assertTrue(cut.ready, "seed=$seed: ${cut.reasons}")
            val result = boundary.complete(h.clients.first().client, cut.cutId)
            assertTrue(result.allowed, "seed=$seed: ${result.reasons}")
            assertTrue(result.actionPerformed)
            assertEquals(listOf("complete", "complete"), h.gateStates())
            assertEquals(1, h.actionCalls.get())
            assertFalse(boundary.complete(h.clients.first().client, cut.cutId).allowed)
        }
        assertEquals(before, h.retainedCounts(), "Completion cannot trim accepted history or delivery evidence")
        h.reopenServices()
        h.assertAllEvidence()
        h.assertReceipts()
        assertEquals(before, h.retainedCounts())
        assertFalse(h.boundary().complete(h.clients.first().client, cut.cutId).allowed)
        assertEquals(if (cancelConsumers) 0 else 1, h.actionCalls.get())
    }

    private class Ticket(val input: AnalysisInput, val defects: List<String>) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        var published = false // Coordinator-owned; set only after BOTH clients expose the result.
    }
    private data class Review(val client: ClientRegistration, val plan: RequiredReviewPlan, val parent: ParentPlanStatus,
        val slot: RequiredPlanSlot, val accepted: ReviewStatus, val ticket: Ticket, var cancelled: Boolean = false)
    private data class Batch(val plans: List<RequiredReviewPlan>, val parents: List<ParentPlanStatus>,
        val tickets: List<Ticket>, val reviews: List<Review>)
    private class Delivery {
        var cursor: String? = null
        val fetched = mutableListOf<ReviewEvent>()
        val acked = mutableSetOf<String>()
        val observed = mutableSetOf<String>()
    }
    private class Registry : ReviewPlanAuthority, ReviewPlanRoutingAuthority {
        val routes = ConcurrentHashMap<String, AuthorizedReviewRoute>()
        override fun resolve(binding: ClientBinding, request: SubmitPlanRequest) = routes[request.planRef]?.origin
        override fun authorize(binding: ClientBinding, request: SubmitPlanRequest, declared: RequiredReviewPlan) = routes[request.planRef]
    }

    private inner class History(val seed: Int) : AutoCloseable {
        val completionRef = "synthetic-history-completion-$seed"
        private val database = temp.resolve("ledger-$seed")
        private val registry = Registry()
        private val tickets = ConcurrentHashMap<Pair<String, RuleContext>, Ticket>()
        private val pool = Executors.newFixedThreadPool(2)
        private val active = AtomicInteger()
        private val maximumActive = AtomicInteger()
        private val provider = PlanTestProvider { input, cancellation ->
            // The kernel changes materializedRoot/outputRoot for execution, so an original
            // analysisKey is deliberately NOT used to find the scripted immutable snapshot.
            val ticket = tickets.getValue(input.snapshot.snapshotId to input.context.scope)
            ticket.calls.incrementAndGet()
            maximumActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            ticket.entered.countDown()
            try {
                assertTrue(ticket.release.await(10, TimeUnit.SECONDS), "seed=$seed: bounded provider release")
                assertFalse(cancellation.isCancelled(), "One recipient cannot cancel shared computation")
                InputArchive.verifyFrozen(input)
                assertEquals(ticket.input.snapshot, input.snapshot)
                assertEquals(ticket.input.context, input.context) // These fixtures have no external classpath.
                assertNotEquals(ticket.input.materializedRoot, input.materializedRoot)
                planPassed(ticket.input, ticket.defects.map { finding(ticket.input, it) })
            } finally { active.decrementAndGet() }
        }
        private var services = openServices()
        val clients = listOf("a", "b").mapIndexed { index, id -> services[index].registerClient(
            ClientBinding("owner", "workspace", "tree", id, "lineage-$id", 1, "session-$id")) }
        val delivery = List(2) { Delivery() }
        private val batches = mutableListOf<Batch>()
        private val reviews get() = batches.flatMap { it.reviews }
        val actionCalls = AtomicInteger()
        private lateinit var fence: HistoryFence
        private val action = object : IntegratedCompletionAction {
            override val identity = Digests.sha256("synthetic-history-action:$seed")
            override fun perform(permit: IntegratedCompletionPermit): IntegratedActionReceipt {
                assertTrue(fence.held)
                assertEquals(listOf("action_claimed", "action_claimed"), gateStates())
                assertEquals(clients.map { it.binding }.toSet(), permit.scope.members.map { it.binding }.toSet())
                actionCalls.incrementAndGet()
                return IntegratedActionReceipt(permit.cutId, permit.scopeDigest,
                    Digests.sha256("synthetic-history-effect:${permit.cutId}:${permit.scopeDigest}"))
            }
        }

        private fun openServices() = List(2) { KernelService.open(database, provider, config,
            planAuthority = registry, routingAuthority = registry) }

        fun admit(wave: Int, parent: String?, selected: List<RuleContext>, defects: List<String>, origin: Int): Batch {
            val inputs = inputs(seed, wave, parent, selected)
            val work = inputs.map { input -> Ticket(input, if (input.context.scope == consumer) defects else emptyList()).also {
                assertNull(tickets.putIfAbsent(input.snapshot.snapshotId to input.context.scope, it), "Every wave is a distinct archived input")
            } }
            val plans = clients.map { client ->
                val label = "$seed-$wave-${client.binding.clientId}"
                RequiredReviewPlan(client.binding, SubmitPlanRequest("patch-$label", "key-$label", "ref-$label"),
                    Digests.sha256(canonical(inputs.first().snapshot)), Digests.sha256(canonical(emptyList<SnapshotFile>())),
                    Digests.sha256("synthetic-model-certificate"), Digests.sha256("synthetic-shared-api-impact:$label"),
                    inputs.mapIndexed { index, input -> RequiredPlanSlot("slot-$index", input, assertNotNull(contextIdentity(input))) },
                    listOf(client.binding.clientId))
            }
            val recipients = clients.zip(plans).map { (client, plan) ->
                val digest = Digests.sha256(canonical(plan))
                val preparation = PlanRecipientPreparation(digest, Digests.sha256("synthetic-policy:$digest"), Digests.sha256("synthetic-execution:$digest"))
                AuthorizedPlanRecipient(plan, client.adapter, preparation) { plan.slots.forEach { InputArchive.verifyFrozen(it.input) }; preparation }
            }
            val declared = plans[origin].copy(requiredRecipientClientIds = clients.map { it.binding.clientId })
            registry.routes[declared.request.planRef] = AuthorizedReviewRoute(declared, recipients)
            val accepted = services[origin].submitPlan(clients[origin].client, declared.request)
            val parents = clients.mapIndexed { index, client ->
                val id = services[index].ledger.read { c -> assertNotNull(c.one(
                    "SELECT plan_id FROM parent_plans WHERE client_id=? AND patch_id=?", client.binding.clientId, plans[index].request.patchId) { it.getString(1) }) }
                services[index].planStatus(client.client, id)
            }
            assertEquals(accepted.planId, parents[origin].planId)
            val records = clients.flatMapIndexed { index, client -> plans[index].slots.mapIndexed { slotIndex, slot ->
                val child = services[index].status(client.client, parents[index].slots[slotIndex].reviewId)
                assertEquals(slot.input.snapshot.snapshotId, child.sourceSnapshotId)
                Review(client, plans[index], parents[index], slot, child, work[slotIndex])
            } }
            return Batch(plans, parents, work, records).also { batches += it }
        }

        fun clientRound(batch: Batch, cancelClient: Int?, limits: List<Int>, modes: List<Int>) {
            // Both providers are parked before these actors run. No sleep decides publication,
            // cancellation or receipt order; the two independent per-client streams commute.
            val start = CyclicBarrier(2)
            val futures = clients.indices.map { index -> pool.submit {
                start.await(5, TimeUnit.SECONDS)
                if (index == cancelClient) {
                    val record = batch.reviews.single { it.client == clients[index] && it.slot.input.context.scope == consumer }
                    assertEquals("cancelled", services[index].cancel(clients[index].client, record.accepted.reviewId).state)
                    record.cancelled = true
                    val peer = batch.reviews.single { it.client == clients[1 - index] && it.ticket === record.ticket }
                    assertFalse(services[1 - index].status(peer.client.client, peer.accepted.reviewId).cancelled)
                }
                pump(index, limits[index], modes[index])
            } }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            assertReceipts()
        }

        fun finish(ticket: Ticket) {
            ticket.release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            reviews.filter { it.ticket === ticket }.forEach { record ->
                var status = services[0].status(record.client.client, record.accepted.reviewId)
                while (status.result == null && System.nanoTime() < deadline) {
                    Thread.sleep(5) // Poll a predicate, never choose a schedule by sleeping.
                    status = services[0].status(record.client.client, record.accepted.reviewId)
                }
                assertNotNull(status.result, "seed=$seed: no result for ${record.accepted.reviewId}")
                assertEvidence(record, status)
            }
            ticket.published = true
        }

        private fun assertEvidence(record: Review, status: ReviewStatus) {
            assertEquals(if (record.cancelled) "cancelled" else "complete", status.state, "seed=$seed: ${status.reasons}")
            val result = assertNotNull(status.result)
            assertEquals(planPassed(record.ticket.input, record.ticket.defects.map { finding(record.ticket.input, it) }), result)
            assertEquals(listOf(PLAN_TEST_RULE to record.slot.input.context.scope), result.coverage.map { it.ruleId to it.context })
            assertTrue(result.coverage.all { it.required && it.successful() })
            assertEquals(record.ticket.defects.map { fingerprint(record.ticket.input, it) }.toSet(), result.findings.map { it.fingerprint }.toSet())
            assertTrue(result.findings.all { it.disposition == "open" })
        }

        private fun pump(index: Int, limit: Int, mode: Int): EventPage {
            val page = services[index].events(clients[index].client, delivery[index].cursor, limit)
            adopt(index, page)
            if (mode != 0) deliver(index, page.events, observe = mode == 2)
            return page
        }

        private fun adopt(index: Int, page: EventPage) {
            val state = delivery[index]
            assertEquals((state.fetched.size + 1L..state.fetched.size + page.events.size.toLong()).toList(), page.events.map { it.sequence })
            page.events.forEach { event ->
                assertEquals(clients[index].binding.clientId, event.clientId)
                assertEquals(clients[index].binding.generation, event.generation)
                assertTrue(event.deliveryRequired)
                assertEquals(Digests.sha256(event.payload), event.payloadDigest)
                assertFalse(state.fetched.any { it.eventId == event.eventId })
            }
            state.fetched += page.events
            state.cursor = page.cursor
        }

        fun drain(index: Int) {
            do { val more = pump(index, 3, 0).hasMore } while (more)
            assertEquals(allEvents(index), delivery[index].fetched, "Saved cursors must recover the entire exact stream")
        }

        fun deliver(index: Int, events: List<ReviewEvent>, observe: Boolean) {
            events.forEach { event ->
                val digest = Digests.sha256("synthetic-inserted-context:${event.clientId}:${event.payloadDigest}")
                services[index].acknowledge(clients[index].adapter, listOf(event.eventId),
                    ContextReceipt("ack-${event.eventId}", digest, "item-${event.eventId}", Instant.EPOCH.toString()))
                delivery[index].acked += event.eventId
                if (observe) {
                    services[index].observe(clients[index].adapter, listOf(event.eventId),
                        ObservationReceipt("observed-${event.eventId}", "synthetic-model-request-${event.eventId}", listOf(digest)))
                    delivery[index].observed += event.eventId
                }
            }
        }

        fun assertReceipts() = services[0].ledger.read { c -> clients.forEachIndexed { index, client ->
            val acked = c.query("SELECT event_id FROM acknowledgements WHERE client_id=?", client.binding.clientId) { it.getString(1) }
            val observed = c.query("SELECT event_id FROM observations WHERE client_id=?", client.binding.clientId) { it.getString(1) }
            assertEquals(delivery[index].acked.size, acked.size, "Exact receipt retries cannot add another insertion")
            assertEquals(delivery[index].observed.size, observed.size, "Exact receipt retries cannot add another observation")
            assertEquals(delivery[index].acked, acked.toSet())
            assertEquals(delivery[index].observed, observed.toSet())
        } }

        fun reconnectBetweenPages() {
            assertTrue(tickets.values.all { it.published })
            if (delivery[0].fetched.size == delivery[1].fetched.size) pump(0, 1, 0)
            assertNotEquals(delivery[0].fetched.size, delivery[1].fetched.size, "Restore genuinely independent high-water marks")
            val previous = clients.indices.map { allEvents(it) }
            val cursors = delivery.map { assertNotNull(it.cursor) }
            val pages = clients.indices.map { services[it].events(clients[it].client, cursors[it], it + 1) }
            pages.forEach { assertTrue(it.events.isNotEmpty(), "The reconnect must interrupt a real page backlog") }
            // Durable ACK/observation succeeded, but the adapter lost the response/cursor update.
            clients.indices.forEach { deliver(it, pages[it].events.take(1), observe = it == 1) }
            assertReceipts()
            val calls = provider.calls.get()
            reopenServices()
            clients.indices.forEach { index ->
                assertEquals(clients[index], services[1 - index].registerClient(clients[index]))
                assertEquals(previous[index], allEvents(index), "Restart must not replace an immutable old payload")
                assertEquals("invalid_cursor", assertFailsWith<KernelException> {
                    services[index].events(clients[index].client, cursors[1 - index])
                }.code)
                val replay = services[index].events(clients[index].client, cursors[index], index + 1)
                assertEquals(pages[index], replay)
                adopt(index, replay)
                deliver(index, replay.events.take(1), observe = index == 1) // Exact receipt retry, not a new attestation.
            }
            assertEquals(calls, provider.calls.get(), "Receipt/cursor recovery cannot rerun completed work")
            assertReceipts()
            assertCut(boundary().prepare(clients.first().client, completionRef))
        }

        fun reopenServices() {
            assertTrue(tickets.values.all { it.published }, "Only quiescent service reopen is modeled here")
            services.asReversed().forEach { it.close() }
            services = openServices()
        }

        private fun allEvents(index: Int): List<ReviewEvent> = buildList {
            var cursor: String? = null
            do {
                val page = services[index].events(clients[index].client, cursor, 3)
                addAll(page.events); cursor = page.cursor
            } while (page.hasMore)
        }

        fun assertAllEvidence() {
            assertEquals(10, batches.size)
            assertEquals(19, tickets.size)
            assertEquals(19, tickets.values.map { it.input.analysisKey() }.distinct().size)
            assertEquals(38, reviews.size)
            assertEquals(2, maximumActive.get())
            assertTrue(tickets.values.all { it.published && it.calls.get() == 1 }, "Two recipients share work, never delivery or obligations")
            clients.forEachIndexed { index, client ->
                val records = reviews.filter { it.client == client }
                assertEquals((1L..records.size.toLong()).toList(), records.map { it.accepted.sequence })
                val events = allEvents(index)
                assertEquals((1L..events.size.toLong()).toList(), events.map { it.sequence })
                assertEquals(2 * records.size + batches.size + records.count { it.cancelled }, events.size)
                records.forEach { record ->
                    val status = services[index].status(client.client, record.accepted.reviewId)
                    assertEvidence(record, status)
                    val own = events.filter { it.reviewId == status.reviewId }
                    assertEquals(1, own.count { it.kind == "accepted" })
                    assertEquals(if (record.cancelled) 1 else 0, own.count { it.kind == "cancelled" })
                    val event = own.single { it.kind == "result" }
                    assertEquals(Digests.sha256(event.payload), event.payloadDigest)
                    assertEquals(status, ContractJson.decodeFromString<ReviewStatus>(event.payload))
                }
                batches.forEach { batch ->
                    val parent = batch.parents[index]
                    val status = services[index].planStatus(client.client, parent.planId)
                    assertEquals(if (batch.reviews.any { it.client == client && it.cancelled }) "cancelled" else "complete", status.state)
                    val accepted = events.map { ContractJson.decodeFromString<ReviewStatus>(it.payload) }
                        .single { it.parentPlan?.planId == parent.planId }
                    assertEquals(parent.slots.map { it.reviewId }, accepted.parentPlan!!.slots.map { it.reviewId })
                }
            }
            assertEquals(19L, services[0].ledger.read { it.count("SELECT COUNT(*) FROM work_certificates") })
        }

        fun cancellationReasons(): Set<String> = buildSet {
            reviews.filter { it.cancelled }.forEach { record ->
                add("review_cancelled:${record.accepted.reviewId}:${record.client.binding.clientId}")
                add("joint_parent_cancelled:${record.client.binding.clientId}:${record.parent.planId}")
                add("joint_publication_not_quiescent:${record.client.binding.clientId}")
            }
        }

        fun assertCut(cut: IntegratedCompletionCut) {
            val expected = mutableSetOf<String>()
            clients.forEachIndexed { index, client ->
                val id = client.binding.clientId
                val records = reviews.filter { it.client == client }
                val current = batches.last().reviews.filter { it.client == client && it.ticket.published && !it.cancelled }
                records.map { it.slot.input.context.scope }.distinct().forEach { context ->
                    val recheck = current.singleOrNull { it.slot.input.context.scope == context }
                    if (recheck == null) expected += "current_coverage_missing:${context.id}:$PLAN_TEST_RULE:$id"
                    // This tiny oracle uses the prescribed linear, same-configuration history,
                    // NOT production lineageReasons/descends or whichever worker finished last.
                    val findings = if (recheck != null) listOf(recheck) else records.filter { it.ticket.published && it.slot.input.context.scope == context }
                    findings.forEach { r -> r.ticket.defects.forEach { expected += "open_finding:${fingerprint(r.ticket.input, it)}:$id" } }
                }
                records.filter { !it.ticket.published && !it.cancelled }.forEach { expected += "review_pending:${it.accepted.reviewId}:$id" }
                records.filter { it.cancelled }.forEach { expected += "review_cancelled:${it.accepted.reviewId}:$id" }
                val events = allEvents(index)
                events.filter { it.eventId !in delivery[index].acked }.forEach { expected += "joint_insertion_missing:$id:${it.eventId}" }
                events.filter { it.eventId !in delivery[index].observed }.forEach { expected += "joint_model_observation_missing:$id:${it.eventId}" }
            }
            val prefixes = listOf("current_coverage_missing:", "open_finding:", "review_pending:", "review_cancelled:",
                "joint_insertion_missing:", "joint_model_observation_missing:")
            assertEquals(expected, cut.reasons.filter { reason -> prefixes.any { reason.startsWith(it) } }.toSet(), "seed=$seed, waves=${batches.size}")
            if (expected.isNotEmpty()) assertFalse(cut.ready)
        }

        fun boundary(): IntegratedCompletionBarrier {
            val plans = batches.last().plans
            val obligations = reviews.map { r -> IntegratedPatchObligation(r.client.binding.clientId, "review", r.accepted.reviewId,
                r.accepted.patchId, Digests.sha256(canonical(SubmitRequest(r.accepted.patchId, r.accepted.patchId, r.slot.input))),
                r.slot.input.analysisKey(), r.accepted.sourceSnapshotId, r.accepted.sequence) } + batches.flatMap { batch ->
                batch.plans.mapIndexed { index, plan -> IntegratedPatchObligation(plan.binding.clientId, "parent_plan", batch.parents[index].planId,
                    plan.request.patchId, Digests.sha256(canonical(plan.request)), Digests.sha256(canonical(plan)),
                    plan.slots.first().input.snapshot.snapshotId, batch.reviews.filter { it.client == clients[index] }.maxOf { it.accepted.sequence }) }
            }
            fence = HistoryFence(plans, obligations, batches.size.toLong())
            val members = clients.zip(plans).map { (client, plan) ->
                val digest = Digests.sha256(canonical(plan))
                val readiness = Digests.sha256("synthetic-history-readiness:${canonical(client.binding)}:$digest")
                AuthorizedIntegratedMember(client.binding, plan.request, digest, client.adapter, readiness) {
                    plan.slots.forEach { InputArchive.verifyFrozen(it.input) }; readiness
                }
            }
            val grant = AuthorizedIntegratedCompletion(completionRef, clients.first().binding, clients.first().binding.clientId,
                Digests.sha256("synthetic-history-registration:$seed"), Digests.sha256(canonical(listOf(PLAN_TEST_RULE))),
                listOf(PLAN_TEST_RULE), members, fence, action)
            return IntegratedCompletionBarrier(services[0], IntegratedCompletionAuthority { binding, ref ->
                grant.takeIf { it.origin == binding && it.completionRef == ref }
            })
        }

        fun gateStates() = services[0].ledger.read { c -> clients.map { client ->
            assertNotNull(c.one("SELECT gate_state FROM clients WHERE client_id=?", client.binding.clientId) { it.getString(1) })
        } }
        fun retainedCounts() = services[0].ledger.read { c -> listOf("reviews", "parent_plans", "routing_batches", "events", "acknowledgements", "observations")
            .associateWith { c.count("SELECT COUNT(*) FROM $it") } }

        override fun close() {
            tickets.values.forEach { it.release.countDown() }
            pool.shutdownNow()
            services.asReversed().forEach { it.close() }
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "Fixture client actors must settle")
        }
    }

    /** Independent prescribed snapshot/provenance state, never an attestation copied from request. */
    private class HistoryFence(private val plans: List<RequiredReviewPlan>, obligations: List<IntegratedPatchObligation>, epoch: Long) : IntegratedSnapshotFence {
        private val lock = ReentrantLock()
        private val expected = obligations.toSet()
        val held get() = lock.isHeldByCurrentThread
        private val check = IntegratedSnapshotCheck(true, true, plans.first().slots.first().input.snapshot.snapshotId,
            "synthetic-linear-integration-$epoch", Digests.sha256("synthetic-linear-merge-proof:$epoch"), plans.map { plan ->
                val input = plan.slots.last().input
                IntegratedMemberSnapshotCheck(plan.binding, Digests.sha256(canonical(plan)), SnapshotCheck(true, input.snapshot.snapshotId,
                    epoch, input.context.configurationDigest, input.context.modelDigest, input.context.dependencyDigest, input.context.toolchainDigest,
                    "synthetic-member-epoch-$epoch", true, contextIdentities = plan.slots.mapNotNull { it.identity },
                    sourceBindingDigest = plan.sourceBindingDigest, generatedInputsDigest = plan.generatedInputsDigest))
            }, obligations.map { obligation ->
                val digest = integratedObligationDigest(obligation)
                IntegratedPatchDisposition(digest, "incorporated", Digests.sha256("synthetic-retained-patch:$digest"))
            })
        override fun <T> withCurrentIntegratedSnapshot(request: IntegratedFenceRequest, action: (IntegratedSnapshotCheck) -> T): T = lock.withLock {
            assertEquals(expected.size, request.historicalObligations.size)
            assertEquals(expected, request.historicalObligations.toSet(), "Every earlier child AND parent must remain in the joint cut")
            plans.forEach { plan -> plan.slots.forEach { InputArchive.verifyFrozen(it.input) } }
            action(check)
        }
    }

    private fun inputs(seed: Int, wave: Int, parent: String?, selected: List<RuleContext>): List<AnalysisInput> {
        val root = temp.resolve("source-$seed-$wave")
        Files.createDirectories(root)
        val contents = sortedMapOf("Consumer.kt" to "package synthetic\nval consumerWave = $wave\n",
            "Shared.kt" to "package synthetic\nval apiWave = $wave\n")
        val files = contents.map { (path, text) ->
            Files.writeString(root.resolve(path), text)
            SnapshotFile(path, Digests.sha256(text), text.toByteArray().size.toLong())
        }
        val snapshot = SnapshotManifest(Digests.sha256("synthetic-history:$seed:$wave:$parent"), "workspace", "tree", files,
            "owner", parent, declaredRoots = listOf("."))
        return selected.map { context -> AnalysisInput(snapshot, root.toString(), AnalysisContext(scope = context,
            compilerVersion = "synthetic-compiler", modelDigest = Digests.sha256("synthetic-model"),
            dependencyDigest = Digests.sha256("synthetic-dependencies:${context.id}"), toolchainDigest = Digests.sha256("synthetic-toolchain"),
            configurationDigest = Digests.sha256("synthetic-config:${context.id}"), fullProject = true, compilationFiles = files.map { it.path }),
            listOf(PLAN_TEST_RULE)) }
    }

    private fun fingerprint(input: AnalysisInput, defect: String) = Digests.sha256("synthetic-api-finding:${input.context.scope.id}:$defect")
    private fun finding(input: AnalysisInput, defect: String): Finding {
        val file = input.snapshot.files.single { it.path == "Consumer.kt" }
        return Finding(PLAN_TEST_RULE, "Synthetic affected consumer", "Synthetic API mismatch $defect", "Fixture only",
            SourceLocation(file.path, file.digest), fingerprint(input, defect), PLAN_TEST_PROVIDER, input.context.scope, severity = "error")
    }
}

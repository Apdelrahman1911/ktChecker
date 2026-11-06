package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * Real SQLite/WAL, archives, service instances and threads; ALL provider, delivery, source,
 * configuration, merge and final-effect authorities are SYNTHETIC. No compiler or host child.
 * planPassed's implemented/tested/qualified flags are fixture contracts, not rule qualification.
 *
 * Unlike the quiescent reopens in KernelStressTest/KernelSustainedHistoryTest, only a zero-worker
 * control service reconnects while a DIFFERENT service retains an active shared claim. Discarding
 * an ACK reply/cursor update models an adapter checkpoint loss, not a process kill or real network.
 * The finite dispatch oracle checks service opportunities, never CPU fairness or latency. The
 * final one-rule/one-context audit does not qualify full/incremental equivalence or all hosts.
 */
class KernelLiveReconnectFairnessTest {
    @TempDir lateinit var temp: Path
    private val context = RuleContext(target = "jvm", module = ":synthetic-live-reconnect")
    private val config = KernelConfig(workerCount = 0, globalParallelism = 1, perWorktreeParallelism = 1,
        maxQueuedWork = 64, maxPendingPerClient = 64, maxRetainedReviews = 64,
        maxArchiveBytes = 4L * 1024 * 1024, maxSnapshotBytes = 64L * 1024,
        maxSnapshotFiles = 4, maxInputBytes = 64 * 1024, maxResultBytes = 32 * 1024,
        maxEventPageSize = 8, maxEventPageBytes = 32 * 1024,
        heartbeatMillis = 100, leaseMillis = 1_000, pollMillis = 5, analysisTimeoutMillis = 60_000)
    private val limits = IntegratedCompletionLimits(maxMembers = 2, maxRoutes = 1, maxReviews = 64,
        maxParents = 2, maxEvents = 128, maxHistoryBytes = 2L * 1024 * 1024,
        maxCoveragePairs = 64, maxAuditBytes = 512 * 1024, maxCuts = 4)

    @Test @Timeout(value = 90, unit = TimeUnit.SECONDS)
    fun liveControlReconnectPreservesFairDispatchAndRequiresPeersOwnFinalObservation() =
        runHistory(cancelSubscriber = false)

    @Test @Timeout(value = 90, unit = TimeUnit.SECONDS)
    fun liveControlReconnectCannotEraseCancelledSharedSubscriptionsFromTheFinalAudit() =
        runHistory(cancelSubscriber = true)

    private fun runHistory(cancelSubscriber: Boolean) = History().use { h ->
        h.admit("shared-initial", listOf(0, 1))
        h.admit("cold-initial", listOf(1))
        repeat(4) { h.admit("hot-initial-$it", listOf(0)) }
        h.startWorker()
        repeat(30) { dispatch ->
            val current = h.nextClaim()
            // Six finite replenishments occur while a real claim holds the only durable slot.
            // A hot producer cannot refresh its age or consume an incidental subscriber's turn.
            if (dispatch < 6) {
                repeat(2) { h.admit("hot-$dispatch-$it", listOf(0)) }
                h.admit("cold-$dispatch", listOf(1))
                h.admit("shared-$dispatch", listOf(0, 1))
            }
            h.clientRound(current, cancelSubscriber)
            if (h.isShared(current) && h.liveReconnects < 3) h.reconnectDuringClaim(current)
            h.releaseAndAwaitPublication(current)
        }
        assertEquals(3, h.liveReconnects)
        h.assertHistory(cancelSubscriber)

        val finalTicket = h.admitFinalRoute()
        assertSame(finalTicket, h.nextClaim())
        h.releaseAndAwaitPublication(finalTicket)
        h.assertAllResults()
        h.clients.indices.forEach { h.drain(it) }
        h.assertStreamHistory()
        h.deliver(0, h.streams[0].events, observe = true)
        h.deliver(1, h.streams[1].events, observe = false)
        h.assertReceipts()

        val barrier = h.barrier()
        val inserted = barrier.prepare(h.clients[0].client, h.completionRef)
        val beforeObservation = h.assertAudit(inserted)
        assertEquals("prepared", beforeObservation.state)
        assertNull(beforeObservation.receipt)
        val missingPeer = h.streams[1].events.map { it.eventId }.toSet() - h.streams[1].observed
        assertTrue(missingPeer.isNotEmpty(), "A's observations and B's ACK retries cannot observe B's final result")
        assertEquals(missingPeer.map { "joint_model_observation_missing:b:$it" }.toSet(),
            inserted.reasons.filter { it.startsWith("joint_model_observation_missing:") }.toSet())
        assertTrue(inserted.reasons.none { it.startsWith("joint_insertion_missing:") })
        assertFalse(inserted.ready)
        assertFalse(barrier.complete(h.clients[0].client, inserted.cutId).allowed)
        assertEquals(0, h.actionCalls.get())
        assertEquals(listOf("open", "open"), h.gateStates())
        assertEquals(beforeObservation, h.assertAudit(inserted),
            "A forbidden completion must preserve its prepared audit without a receipt")

        h.deliver(1, h.streams[1].events, observe = true)
        val cut = barrier.prepare(h.clients[0].client, h.completionRef)
        val afterObservation = h.assertAudit(cut)
        assertEquals("prepared", afterObservation.state)
        assertNull(afterObservation.receipt)
        assertEquals(beforeObservation.request, afterObservation.request,
            "ACK/observation traffic cannot trim history or extend the finite work/result frontier")
        val retained = h.retainedCounts()
        if (cancelSubscriber) {
            val cancelled = h.reviews.filter { it.cancelled }
            assertEquals(7, cancelled.size)
            assertEquals(cancelled.map { "review_cancelled:${it.status.reviewId}:a" }.toSet() +
                "joint_publication_not_quiescent:a", cut.reasons.toSet())
            assertFalse(cut.ready)
            assertFalse(barrier.complete(h.clients[0].client, cut.cutId).allowed)
            assertEquals(listOf("open", "open"), h.gateStates())
            assertEquals(0, h.actionCalls.get())
            assertEquals(afterObservation, h.assertAudit(cut),
                "Cancelled history must not claim or rewrite the prepared audit")
        } else {
            assertTrue(cut.ready, cut.reasons.toString())
            val result = barrier.complete(h.clients[0].client, cut.cutId)
            assertTrue(result.allowed, result.reasons.toString())
            assertTrue(result.actionPerformed)
            assertEquals("complete", result.state)
            assertEquals(listOf("complete", "complete"), h.gateStates())
            assertEquals(1, h.actionCalls.get())
        }
        assertEquals(retained, h.retainedCounts())
        h.reopenControls()
        h.assertAllResults()
        h.assertReceipts()
        assertEquals(retained, h.retainedCounts())
        val reopenedAudit = h.assertAudit(cut)
        assertEquals(afterObservation.request, reopenedAudit.request)
        assertEquals(if (cancelSubscriber) "prepared" else "complete", reopenedAudit.state)
        if (cancelSubscriber) assertNull(reopenedAudit.receipt) else assertNotNull(reopenedAudit.receipt)
        val expectedGateStates = if (cancelSubscriber) listOf("open", "open") else listOf("complete", "complete")
        assertEquals(expectedGateStates, h.gateStates())
        assertFalse(h.barrier().complete(h.clients[0].client, cut.cutId).allowed)
        assertEquals(if (cancelSubscriber) 0 else 1, h.actionCalls.get())
        assertEquals(expectedGateStates, h.gateStates())
        assertEquals(reopenedAudit, h.assertAudit(cut),
            "Forbidden or replayed completion cannot change the durable audit or its receipt")
        assertEquals(retained, h.retainedCounts())
        h.assertDispatchAges()
    }

    private class Ticket(val label: String, val input: AnalysisInput) {
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
    }

    private data class Review(val clientIndex: Int, val plannedSequence: Long, val ordinal: Int,
        val ticket: Ticket, val request: SubmitRequest, val status: ReviewStatus, var cancelled: Boolean = false)

    private class Stream {
        var cursor: String? = null
        val events = mutableListOf<ReviewEvent>()
        val acked = mutableSetOf<String>()
        val observed = mutableSetOf<String>()
    }

    private data class ClaimIdentity(val workKey: String, val owner: String, val attempt: Long, val deadline: Long)

    private class Registry : ReviewPlanAuthority, ReviewPlanRoutingAuthority {
        val routes = ConcurrentHashMap<String, AuthorizedReviewRoute>()
        override fun resolve(binding: ClientBinding, request: SubmitPlanRequest) = routes[request.planRef]?.origin
        override fun authorize(binding: ClientBinding, request: SubmitPlanRequest, declared: RequiredReviewPlan) = routes[request.planRef]
    }

    private inner class History : AutoCloseable {
        private val database = temp.resolve("ledger")
        private val registry = Registry()
        private val tickets = ConcurrentHashMap<String, Ticket>()
        private val entered = LinkedBlockingQueue<Ticket>(32)
        private val actors = Executors.newFixedThreadPool(2)
        private val active = AtomicInteger()
        private val maximumActive = AtomicInteger()
        private val provider = PlanTestProvider { input, cancellation ->
            val ticket = tickets.getValue(input.snapshot.snapshotId)
            assertEquals(1, ticket.calls.incrementAndGet(), "No reconnect may replace or repeat a live claim")
            maximumActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            try {
                InputArchive.verifyFrozen(input)
                assertEquals(ticket.input.snapshot, input.snapshot)
                assertEquals(ticket.input.context, input.context)
                assertNotEquals(ticket.input.materializedRoot, input.materializedRoot)
                assertFalse(cancellation.isCancelled())
                assertTrue(entered.offer(ticket), "The finite provider-entry queue must not overflow")
                assertTrue(ticket.release.await(30, TimeUnit.SECONDS), "Bounded provider release: ${ticket.label}")
                assertFalse(cancellation.isCancelled(), "Closing a control service or cancelling A cannot revoke B's worker")
                planPassed(ticket.input)
            } finally { active.decrementAndGet() }
        }
        private val controls = mutableListOf<KernelService>()
        val clients = try {
            repeat(2) { controls += openControl() }
            listOf("a", "b").mapIndexed { index, id -> controls[index].registerClient(
                ClientBinding("owner", "workspace", "tree", id, "lineage-$id", 1, "session-$id")) }
        } catch (failure: Throwable) {
            fun attempt(action: () -> Unit) {
                try { action() } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
            }
            attempt { actors.shutdownNow() }
            controls.asReversed().forEach { control -> attempt { control.close() } }
            throw failure
        }
        val streams = List(2) { Stream() }
        val reviews = mutableListOf<Review>()
        private val served = mutableSetOf<String>()
        private val expectedAges = mutableMapOf(0 to 0L, 1 to 0L)
        private var dispatches = 0L
        private var parentSnapshot: String? = null
        private var worker: KernelService? = null
        var liveReconnects = 0
            private set
        val completionRef = "synthetic-live-reconnect-final"
        val actionCalls = AtomicInteger()
        private lateinit var plans: List<RequiredReviewPlan>
        private lateinit var parents: List<ParentPlanStatus>
        private lateinit var fence: FinalFence
        private lateinit var grant: AuthorizedIntegratedCompletion

        private fun openControl() = KernelService.open(database, provider, config,
            planAuthority = registry, routingAuthority = registry)

        fun startWorker() {
            assertNull(worker)
            worker = KernelService.open(database, provider, config.copy(workerCount = 1),
                planAuthority = registry, routingAuthority = registry)
        }

        private fun ticket(label: String): Ticket {
            val root = Files.createDirectory(temp.resolve("source-$label")).toRealPath()
            val text = "package synthetic\nval checkpoint = \"$label\"\n"
            Files.writeString(root.resolve("Main.kt"), text)
            val snapshot = SnapshotManifest(Digests.sha256("synthetic-live-reconnect:$label:$parentSnapshot"),
                "workspace", "tree", listOf(SnapshotFile("Main.kt", Digests.sha256(text), text.toByteArray().size.toLong())),
                "owner", parentSnapshot, declaredRoots = listOf("."))
            parentSnapshot = snapshot.snapshotId
            val input = AnalysisInput(snapshot, root.toString(), AnalysisContext(scope = context,
                compilerVersion = "synthetic-compiler", modelDigest = Digests.sha256("synthetic-model"),
                dependencyDigest = Digests.sha256("synthetic-dependencies"), toolchainDigest = Digests.sha256("synthetic-toolchain"),
                configurationDigest = Digests.sha256("synthetic-config"), fullProject = true,
                compilationFiles = listOf("Main.kt")), listOf(PLAN_TEST_RULE))
            return Ticket(label, input).also { assertNull(tickets.putIfAbsent(snapshot.snapshotId, it)) }
        }

        fun admit(label: String, recipients: List<Int>) {
            val ticket = ticket(label)
            recipients.forEach { index ->
                val request = SubmitRequest("patch-$label", "key-$label", ticket.input)
                record(index, ticket, request, controls[index].submit(clients[index].client, request))
            }
        }

        private fun record(index: Int, ticket: Ticket, request: SubmitRequest, status: ReviewStatus) {
            val sequence = reviews.count { it.clientIndex == index }.toLong() + 1
            assertEquals(sequence, status.sequence)
            assertEquals(clients[index].binding.clientId, status.clientId)
            assertEquals(ticket.input.snapshot.snapshotId, status.sourceSnapshotId)
            assertNull(status.result, "Every admitted ticket is new work, not a completed cache hit")
            reviews += Review(index, sequence, reviews.size, ticket, request, status)
        }

        fun nextClaim(): Ticket {
            // Independent finite reference model: planned admission order, per-client sequence and
            // previously EXPECTED service ages, never production SQL's chosen sponsor/candidate.
            // Cancellation and ACKs do not remove any accepted subscription from this model.
            val sponsor = assertNotNull(reviews.filter { it.ticket.input.snapshot.snapshotId !in served }.minWithOrNull(
                compareBy<Review>({ expectedAges.getValue(it.clientIndex) }, { it.plannedSequence }, { it.ordinal })),
                "Every provider entry must have an accepted queued obligation")
            val actual = assertNotNull(entered.poll(10, TimeUnit.SECONDS), "No bounded dispatch opportunity appeared")
            assertSame(sponsor.ticket, actual, "Least-served eligible client must select its own oldest queued obligation")
            assertTrue(served.add(actual.input.snapshot.snapshotId))
            expectedAges[sponsor.clientIndex] = ++dispatches
            assertDispatchAges()
            val claim = liveClaim(actual)
            assertEquals(assertNotNull(worker).instanceId, claim.owner)
            assertEquals(1L, claim.attempt)
            assertEquals(1, active.get())
            return actual
        }

        fun assertDispatchAges() = controls[0].ledger.read { c ->
            val actual = c.query("SELECT client_id,last_served FROM fair_clients ORDER BY client_id") {
                it.getString(1) to it.getLong(2)
            }.toMap()
            assertEquals(clients.indices.associate { clients[it].binding.clientId to expectedAges.getValue(it) }, actual,
                "Shared service spends exactly the sponsor's turn; reconnect cannot reset either age")
            assertEquals(dispatches, c.count("SELECT CAST(value AS INTEGER) FROM metadata WHERE key='dispatch_counter'"))
            assertEquals(listOf(dispatches), c.query("SELECT last_served FROM fair_queues") { it.getLong(1) })
        }

        private fun liveClaim(ticket: Ticket): ClaimIdentity = controls[0].ledger.read { c ->
            val review = reviews.first { it.ticket === ticket }
            assertNotNull(c.one("""SELECT w.state,w.work_key,w.owner_instance,w.attempt,w.deadline FROM work w
                JOIN reviews r ON r.work_key=w.work_key WHERE r.review_id=?""", review.status.reviewId) {
                assertEquals("running", it.getString(1))
                ClaimIdentity(it.getString(2), it.getString(3), it.getLong(4), it.getLong(5))
            })
        }

        fun isShared(ticket: Ticket) = reviews.filter { it.ticket === ticket }.map { it.clientIndex }.toSet() == setOf(0, 1)

        fun clientRound(ticket: Ticket, cancelSubscriber: Boolean) {
            val peers = reviews.filter { it.ticket === ticket }
            val start = CyclicBarrier(2)
            val futures = clients.indices.map { index -> actors.submit {
                start.await(5, TimeUnit.SECONDS)
                if (cancelSubscriber && index == 0 && peers.size == 2) {
                    val own = peers.single { it.clientIndex == 0 }
                    assertEquals("cancelled", controls[0].cancel(clients[0].client, own.status.reviewId).state)
                    own.cancelled = true
                    val other = peers.single { it.clientIndex == 1 }
                    assertFalse(controls[1].status(clients[1].client, other.status.reviewId).cancelled)
                }
                val page = controls[index].events(clients[index].client, streams[index].cursor, 1)
                adopt(index, page)
                deliver(index, page.events, observe = index == 0)
            } }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            assertDispatchAges()
            assertReceipts()
        }

        fun reconnectDuringClaim(ticket: Ticket) {
            assertTrue(isShared(ticket), "Every live reconnect must overlap both clients' exact shared work")
            val claim = liveClaim(ticket)
            val cursor = assertNotNull(streams[1].cursor)
            val page = controls[1].events(clients[1].client, cursor, 1)
            assertEquals(1, page.events.size, "Reconnect must interrupt an actual queued outbox page")
            // The ACK commits; its reply and the cursor update are deliberately discarded.
            deliver(1, page.events, observe = false)
            val receipts = retainedCounts()
            val old = controls[1]
            old.close() // This service has NO workers; the separately owned worker stays live.
            controls[1] = openControl()
            assertNotEquals(old.instanceId, controls[1].instanceId)
            assertEquals(clients[1], controls[1].registerClient(clients[1]))
            assertEquals("invalid_limit", assertFailsWith<KernelException> {
                controls[1].events(clients[1].client, cursor, limit = config.maxEventPageSize + 1)
            }.code)
            // Use a valid page size so cursor isolation, not the default limit, is tested.
            assertEquals("invalid_cursor", assertFailsWith<KernelException> {
                controls[1].events(clients[1].client, assertNotNull(streams[0].cursor), limit = 1)
            }.code)
            val foreign = streams[0].events.first()
            assertEquals("event_not_found", assertFailsWith<KernelException> {
                controls[1].acknowledge(clients[1].adapter, listOf(foreign.eventId), insertion(foreign))
            }.code)
            val replay = controls[1].events(clients[1].client, cursor, 1)
            assertEquals(page, replay, "Reconnect must replay identical owned bytes from the unadvanced checkpoint")
            adopt(1, replay)
            deliver(1, replay.events, observe = false)
            assertEquals(receipts, retainedCounts(), "Exact ACK replay cannot create another receipt or consume A's event")
            assertReceipts()
            assertEquals(claim, liveClaim(ticket), "Reconnect cannot renew a deadline or replace an active attempt")
            assertDispatchAges()
            assertEquals(1, active.get())
            liveReconnects++
        }

        fun releaseAndAwaitPublication(ticket: Ticket) {
            ticket.release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            // Predicate polling only, bounded by ONE original deadline. No sleep chooses a
            // schedule: all admission/cancel/reconnect ordering above uses the parked claim.
            while (true) {
                val statuses = reviews.filter { it.ticket === ticket }.map { review ->
                    controls[review.clientIndex].status(clients[review.clientIndex].client, review.status.reviewId)
                }
                if (statuses.all { it.result != null }) break
                assertTrue(System.nanoTime() < deadline, "No durable publication for ${ticket.label}")
                Thread.yield()
            }
        }

        fun assertHistory(cancelSubscriber: Boolean) {
            assertEquals(30, tickets.size)
            assertEquals(37, reviews.size)
            assertEquals(30L, dispatches)
            assertEquals(if (cancelSubscriber) 7 else 0, reviews.count { it.cancelled })
            assertEquals(30, provider.calls.get())
            assertEquals(1, maximumActive.get())
            assertTrue(tickets.values.all { it.calls.get() == 1 })
            assertEquals(0L, controls[0].ledger.read { it.count("SELECT COUNT(*) FROM work WHERE state<>'complete'") })
            assertAllResults()
        }

        fun assertAllResults() {
            reviews.forEach { review ->
                val status = controls[review.clientIndex].status(clients[review.clientIndex].client, review.status.reviewId)
                assertEquals(if (review.cancelled) "cancelled" else "complete", status.state, status.reasons.toString())
                assertEquals(review.cancelled, status.cancelled)
                assertEquals(planPassed(review.ticket.input), status.result)
            }
            assertTrue(tickets.values.all { it.calls.get() == 1 })
        }

        fun assertStreamHistory() {
            clients.indices.forEach { index ->
                val events = streams[index].events
                assertEquals((1L..events.size.toLong()).toList(), events.map { it.sequence })
                reviews.filter { it.clientIndex == index }.forEach { review ->
                    val owned = events.filter { it.reviewId == review.status.reviewId }
                    assertEquals(1, owned.count { it.kind == "accepted" })
                    assertEquals(if (review.cancelled) 1 else 0, owned.count { it.kind == "cancelled" })
                    val result = owned.single { it.kind == "result" }
                    assertEquals(controls[index].status(clients[index].client, review.status.reviewId),
                        ContractJson.decodeFromString<ReviewStatus>(result.payload))
                }
                val parent = assertNotNull(ContractJson.decodeFromString<ReviewStatus>(
                    events.single { it.kind == "plan_accepted" }.payload).parentPlan)
                assertEquals(parents[index].planId, parent.planId)
                assertEquals(Digests.sha256(canonical(plans[index])), parent.planDigest)
            }
            val ids = streams.flatMap { it.events }.map { it.eventId }
            assertEquals(ids.size, ids.distinct().size)
        }

        private fun adopt(index: Int, page: EventPage) {
            val stream = streams[index]
            val next = stream.events.size.toLong() + 1
            assertEquals((next until next + page.events.size).toList(), page.events.map { it.sequence })
            page.events.forEach { event ->
                assertEquals(clients[index].binding.clientId, event.clientId)
                assertEquals(clients[index].binding.generation, event.generation)
                assertEquals(Digests.sha256(event.payload), event.payloadDigest)
                assertTrue(event.deliveryRequired)
                assertFalse(stream.events.any { it.eventId == event.eventId })
            }
            stream.events += page.events
            stream.cursor = page.cursor
        }

        fun drain(index: Int) {
            repeat(128) {
                val page = controls[index].events(clients[index].client, streams[index].cursor, 2)
                adopt(index, page)
                if (!page.hasMore) {
                    assertEquals(allEvents(index), streams[index].events)
                    return
                }
            }
            fail("Finite outbox did not drain within its explicit page bound")
        }

        private fun allEvents(index: Int): List<ReviewEvent> {
            val events = mutableListOf<ReviewEvent>()
            var cursor: String? = null
            repeat(128) {
                val page = controls[index].events(clients[index].client, cursor, 8)
                events += page.events
                cursor = page.cursor
                if (!page.hasMore) return events
            }
            fail("Finite full outbox exceeded its explicit page bound")
        }

        fun deliver(index: Int, events: List<ReviewEvent>, observe: Boolean) {
            events.forEach { event ->
                val receipt = insertion(event)
                controls[index].acknowledge(clients[index].adapter, listOf(event.eventId), receipt)
                streams[index].acked += event.eventId
                if (observe) {
                    controls[index].observe(clients[index].adapter, listOf(event.eventId),
                        ObservationReceipt("observed-${event.eventId}", "synthetic-request-${event.eventId}", listOf(receipt.contextDigest)))
                    streams[index].observed += event.eventId
                }
            }
        }

        fun assertReceipts() = controls[0].ledger.read { c -> clients.indices.forEach { index ->
            val id = clients[index].binding.clientId
            val acked = c.query("SELECT event_id FROM acknowledgements WHERE client_id=?", id) { it.getString(1) }
            val observed = c.query("SELECT event_id FROM observations WHERE client_id=?", id) { it.getString(1) }
            assertEquals(streams[index].acked.size, acked.size)
            assertEquals(streams[index].observed.size, observed.size)
            assertEquals(streams[index].acked, acked.toSet())
            assertEquals(streams[index].observed, observed.toSet())
        } }

        fun admitFinalRoute(): Ticket {
            val finalTicket = ticket("final-integrated")
            plans = clients.map { client ->
                val id = client.binding.clientId
                RequiredReviewPlan(client.binding, SubmitPlanRequest("final-$id", "final-key-$id", "final-ref-$id"),
                    Digests.sha256(canonical(finalTicket.input.snapshot)), Digests.sha256(canonical(emptyList<SnapshotFile>())),
                    Digests.sha256("synthetic-model-certificate"), Digests.sha256("synthetic-final-impact"),
                    listOf(RequiredPlanSlot("slot-0", finalTicket.input, assertNotNull(contextIdentity(finalTicket.input)))), listOf(id))
            }
            val recipients = clients.zip(plans).map { (client, plan) ->
                val digest = Digests.sha256(canonical(plan))
                val preparation = PlanRecipientPreparation(digest, Digests.sha256("synthetic-policy:$digest"),
                    Digests.sha256("synthetic-execution:$digest"))
                AuthorizedPlanRecipient(plan, client.adapter, preparation) { InputArchive.verifyFrozen(finalTicket.input); preparation }
            }
            val declared = plans[0].copy(requiredRecipientClientIds = clients.map { it.binding.clientId })
            registry.routes[declared.request.planRef] = AuthorizedReviewRoute(declared, recipients)
            val accepted = controls[0].submitPlan(clients[0].client, declared.request)
            parents = clients.indices.map { index ->
                val event = allEvents(index).single { it.kind == "plan_accepted" }
                val parent = assertNotNull(ContractJson.decodeFromString<ReviewStatus>(event.payload).parentPlan)
                controls[index].planStatus(clients[index].client, parent.planId)
            }
            assertEquals(accepted.planId, parents[0].planId)
            clients.indices.forEach { index ->
                val status = controls[index].status(clients[index].client, parents[index].slots.single().reviewId)
                val patch = "plan-child:${Digests.sha256(canonical(listOf(parents[index].planId, "slot-0")))}"
                assertEquals(patch, status.patchId)
                record(index, finalTicket, SubmitRequest(patch, patch, finalTicket.input), status)
            }
            return finalTicket
        }

        fun barrier(): IntegratedCompletionBarrier {
            if (!::grant.isInitialized) {
                fence = FinalFence(plans, expectedObligations())
                val members = clients.zip(plans).map { (client, plan) ->
                    val digest = Digests.sha256(canonical(plan))
                    val readiness = Digests.sha256("synthetic-final-readiness:$digest")
                    AuthorizedIntegratedMember(client.binding, plan.request, digest, client.adapter, readiness) {
                        InputArchive.verifyFrozen(plan.slots.single().input); readiness
                    }
                }
                val action = object : IntegratedCompletionAction {
                    override val identity = Digests.sha256("synthetic-live-reconnect-action")
                    override fun perform(permit: IntegratedCompletionPermit): IntegratedActionReceipt {
                        actionCalls.incrementAndGet() // Count entry even if the barrier catches an assertion below.
                        assertTrue(fence.held)
                        assertEquals(listOf("action_claimed", "action_claimed"), gateStates())
                        assertEquals(clients.map { it.binding }.toSet(), permit.scope.members.map { it.binding }.toSet())
                        return IntegratedActionReceipt(permit.cutId, permit.scopeDigest,
                            Digests.sha256("synthetic-final-effect:${permit.cutId}:${permit.scopeDigest}"))
                    }
                }
                grant = AuthorizedIntegratedCompletion(completionRef, clients[0].binding, clients[0].binding.clientId,
                    Digests.sha256("synthetic-live-reconnect-registration"), Digests.sha256(canonical(listOf(PLAN_TEST_RULE))),
                    listOf(PLAN_TEST_RULE), members, fence, action)
            }
            return IntegratedCompletionBarrier(controls[0], IntegratedCompletionAuthority { binding, ref ->
                grant.takeIf { it.origin == binding && it.completionRef == ref }
            }, limits)
        }

        private fun expectedObligations(): List<IntegratedPatchObligation> = reviews.map { review ->
            IntegratedPatchObligation(clients[review.clientIndex].binding.clientId, "review", review.status.reviewId,
                review.request.patchId, Digests.sha256(canonical(review.request)), review.ticket.input.analysisKey(),
                review.ticket.input.snapshot.snapshotId, review.plannedSequence)
        } + plans.mapIndexed { index, plan ->
            IntegratedPatchObligation(plan.binding.clientId, "parent_plan", parents[index].planId, plan.request.patchId,
                Digests.sha256(canonical(plan.request)), Digests.sha256(canonical(plan)), plan.slots.single().input.snapshot.snapshotId,
                reviews.filter { it.clientIndex == index }.maxOf { it.plannedSequence })
        }

        fun assertAudit(cut: IntegratedCompletionCut): IntegratedCompletionAudit {
            val audit = controls[0].ledger.read { c ->
                val encoded = assertNotNull(c.one("SELECT value FROM metadata WHERE key=?", IntegratedCompletionAuditCodec.key(cut.cutId)) {
                    it.getString(1)
                })
                IntegratedCompletionAuditCodec(limits).decode(cut.cutId, encoded)
            }
            assertEquals(cut, audit.cut)
            assertEquals(cut.scope, audit.request.scope)
            assertEquals(cut.scopeDigest, audit.request.scopeDigest)
            assertEquals(39, reviews.size)
            assertEquals(31, provider.calls.get())
            assertEquals(41, audit.request.historicalObligations.size)
            assertEquals(expectedObligations().toSet(), audit.request.historicalObligations.toSet())
            assertEquals(1, audit.request.routes.size)
            assertEquals(listOf(IntegratedCoverageRequirement(context, PLAN_TEST_RULE)), cut.scope.requiredCoverage)
            clients.indices.forEach { index ->
                val events = streams[index].events
                val frontier = audit.request.frontiers.single { it.clientId == clients[index].binding.clientId }
                assertEquals(reviews.count { it.clientIndex == index }.toLong(), frontier.reviewFrontier)
                assertEquals(events.size.toLong(), frontier.eventFrontier)
                assertEquals(events.map { it.eventId }, frontier.requiredEventIds)
            }
            assertEquals(80 + reviews.count { it.cancelled }, streams.sumOf { it.events.size })
            return audit
        }

        fun gateStates() = controls[0].ledger.read { c -> clients.map { client ->
            assertNotNull(c.one("SELECT gate_state FROM clients WHERE client_id=?", client.binding.clientId) { it.getString(1) })
        } }

        fun retainedCounts() = controls[0].ledger.read { c ->
            listOf("reviews", "work", "work_certificates", "parent_plans", "routing_batches", "events", "receipts", "acknowledgements", "observations")
                .associateWith { c.count("SELECT COUNT(*) FROM $it") }
        }

        fun reopenControls() {
            controls.indices.forEach { index ->
                controls[index].close()
                controls[index] = openControl()
                assertEquals(clients[index], controls[index].registerClient(clients[index]))
                assertEquals(streams[index].events, allEvents(index))
            }
        }

        override fun close() {
            var firstFailure: Throwable? = null
            var interrupted = false
            fun attempt(action: () -> Unit) {
                try {
                    action()
                } catch (failure: Throwable) {
                    if (failure is InterruptedException) interrupted = true
                    val first = firstFailure
                    if (first == null) firstFailure = failure
                    else if (first !== failure) first.addSuppressed(failure)
                }
            }
            // Give every owned resource its finite cleanup attempt; retain all failures.
            tickets.values.forEach { ticket -> attempt { ticket.release.countDown() } }
            attempt { actors.shutdownNow() }
            attempt { assertTrue(actors.awaitTermination(10, TimeUnit.SECONDS), "Bounded client actors must settle") }
            attempt { worker?.close() }
            controls.asReversed().forEach { control -> attempt { control.close() } }
            if (interrupted) Thread.currentThread().interrupt()
            firstFailure?.let { throw it }
        }
    }

    private fun insertion(event: ReviewEvent) = ContextReceipt("ack-${event.eventId}",
        Digests.sha256("synthetic-context:${event.clientId}:${event.payloadDigest}"), "item-${event.eventId}", Instant.EPOCH.toString())

    /** Fixed independently prescribed final snapshot/provenance, NOT an echo of the supplied request. */
    private class FinalFence(private val plans: List<RequiredReviewPlan>, obligations: List<IntegratedPatchObligation>) : IntegratedSnapshotFence {
        private val lock = ReentrantLock()
        private val expected = obligations.toSet()
        val held get() = lock.isHeldByCurrentThread
        private val check = IntegratedSnapshotCheck(true, true, plans[0].slots.single().input.snapshot.snapshotId,
            "synthetic-final-integration-epoch", Digests.sha256("synthetic-final-composition-proof"), plans.map { plan ->
                val input = plan.slots.single().input
                IntegratedMemberSnapshotCheck(plan.binding, Digests.sha256(canonical(plan)), SnapshotCheck(true, input.snapshot.snapshotId,
                    1, input.context.configurationDigest, input.context.modelDigest, input.context.dependencyDigest, input.context.toolchainDigest,
                    "synthetic-final-${plan.binding.clientId}-epoch", true, contextIdentities = plan.slots.mapNotNull { it.identity },
                    sourceBindingDigest = plan.sourceBindingDigest, generatedInputsDigest = plan.generatedInputsDigest))
            }, obligations.map { obligation ->
                val digest = integratedObligationDigest(obligation)
                IntegratedPatchDisposition(digest, "incorporated", Digests.sha256("synthetic-retained-disposition:$digest"))
            })

        override fun <T> withCurrentIntegratedSnapshot(request: IntegratedFenceRequest, action: (IntegratedSnapshotCheck) -> T): T = lock.withLock {
            assertEquals(expected.size, request.historicalObligations.size)
            assertEquals(expected, request.historicalObligations.toSet(), "Reconnect and cancellation cannot omit any accepted patch")
            plans.forEach { InputArchive.verifyFrozen(it.slots.single().input) }
            action(check)
        }
    }
}

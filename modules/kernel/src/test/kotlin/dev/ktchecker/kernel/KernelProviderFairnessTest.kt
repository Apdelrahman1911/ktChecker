package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * Finite synthetic dispatch controls, not CPU fairness, infinite-starvation or qualification evidence.
 * Providers use distinct versions of one implementation and the same durable resource policy.
 * Latches select the adversarial schedule; polling below only observes already released results.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class KernelProviderFairnessTest {
    @TempDir lateinit var temp: Path
    private val config = KernelConfig(workerCount = 0, globalParallelism = 2, perWorktreeParallelism = 2,
        heartbeatMillis = 40, leaseMillis = 200, pollMillis = 5, analysisTimeoutMillis = 60_000)

    private fun binding(id: String, tree: String) = ClientBinding("owner", "workspace", tree, id, "lineage-$id", 1, "session-$id")
    private fun fair(binding: ClientBinding) = Digests.sha256(canonical(listOf(binding.ownerId, binding.workspaceId, binding.worktreeId)))
    private fun input(name: String, tree: String): AnalysisInput {
        val source = temp.resolve("source-$name")
        Files.createDirectories(source)
        val text = "fun main() = Unit\n"
        Files.writeString(source.resolve("Main.kt"), text)
        val snapshot = SnapshotManifest(Digests.sha256("provider-fairness:$name"), "workspace", tree,
            listOf(SnapshotFile("Main.kt", Digests.sha256(text), text.toByteArray().size.toLong())),
            ownerId = "owner", declaredRoots = listOf("."))
        return AnalysisInput(snapshot, source.toString(), AnalysisContext(modelDigest = Digests.sha256("model"),
            dependencyDigest = Digests.sha256("dependencies"), toolchainDigest = Digests.sha256("tools"),
            configurationDigest = Digests.sha256("configuration"), fullProject = true), listOf("test/provider-fairness"))
    }

    private class Ticket(val name: String, val input: AnalysisInput) {
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
    }
    private class Occupancy {
        private var active = 0
        private val activeByTree = mutableMapOf<String, Int>()
        var maximum = 0; private set
        val maximumByTree = mutableMapOf<String, Int>()
        @Synchronized fun enter(tree: String) {
            active++
            maximum = maxOf(maximum, active)
            val count = activeByTree.getOrDefault(tree, 0) + 1
            activeByTree[tree] = count
            maximumByTree[tree] = maxOf(maximumByTree.getOrDefault(tree, 0), count)
        }
        @Synchronized fun leave(tree: String) {
            active--
            activeByTree[tree] = activeByTree.getValue(tree) - 1
        }
        @Synchronized fun assertCaps(global: Int, perTree: Int) {
            assertTrue(maximum <= global, "Physical callback occupancy exceeded the shared global cap")
            assertTrue(maximumByTree.values.all { it <= perTree }, "Provider versions must not split physical-tree capacity")
        }
    }
    private class Probe(override val version: String, private val occupancy: Occupancy) : AnalysisProvider {
        override val id = "synthetic-provider-fairness"
        override val supportedRuleIds = setOf("test/provider-fairness")
        private val tickets = ConcurrentHashMap<String, Ticket>()
        private val entered = LinkedBlockingQueue<Ticket>()
        fun track(ticket: Ticket) {
            val prior = tickets.putIfAbsent(ticket.input.snapshot.snapshotId, ticket)
            assertTrue(prior == null || prior === ticket)
        }
        fun expect(ticket: Ticket) = assertSame(ticket, assertNotNull(entered.poll(10, TimeUnit.SECONDS),
            "No bounded provider-$version dispatch for ${ticket.name}"), "Unexpected next provider-$version dispatch")
        fun releaseAll() = tickets.values.forEach { it.release.countDown() }
        override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
            val ticket = checkNotNull(tickets[input.snapshot.snapshotId])
            check(ticket.calls.incrementAndGet() == 1) { "A shared computation was invoked twice" }
            occupancy.enter(input.snapshot.worktreeId)
            try {
                entered.put(ticket)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
                while (!ticket.release.await(25, TimeUnit.MILLISECONDS)) {
                    cancellation.throwIfCancelled()
                    check(System.nanoTime() < deadline) { "Finite synthetic provider release was not received" }
                }
                cancellation.throwIfCancelled()
                return ProviderResult(id, "incomplete", coverage = input.requiredRuleIds.map {
                    Coverage(it, input.context.scope, "unknown", reason = "synthetic_fairness_only_unqualified")
                }, reasons = listOf("synthetic_fairness_only_unqualified"), providerVersion = version)
            } finally { occupancy.leave(input.snapshot.worktreeId) }
        }
    }
    private data class Admission(val service: KernelService, val client: ClientRegistration, val review: ReviewStatus)
    private data class Ages(
        val counter: Long,
        val trees: Map<Pair<String, String>, Long>,
        val clients: Map<Pair<String, String>, Long>,
        val physicalTrees: Map<String, Pair<Long, Long>>,
        val physicalClients: Map<String, Pair<String, Long>>,
    )
    private fun ages(ledger: Ledger): Ages = ledger.read { c ->
        Ages(c.count("SELECT CAST(value AS INTEGER) FROM metadata WHERE key='dispatch_counter'"),
            c.query("SELECT provider_key,fair_key,last_served FROM provider_fair_queues") { (it.getString(1) to it.getString(2)) to it.getLong(3) }.toMap(),
            c.query("SELECT provider_key,client_id,last_served FROM provider_fair_clients") { (it.getString(1) to it.getString(2)) to it.getLong(3) }.toMap(),
            c.query("SELECT fair_key,last_served,produced_last_served FROM fair_queues") { it.getString(1) to (it.getLong(2) to it.getLong(3)) }.toMap(),
            c.query("SELECT client_id,fair_key,last_served FROM fair_clients") { it.getString(1) to (it.getString(2) to it.getLong(3)) }.toMap())
    }
    private fun result(admission: Admission): ReviewStatus {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var status = admission.service.status(admission.client.client, admission.review.reviewId)
        while (status.result == null && System.nanoTime() < deadline) {
            Thread.sleep(5) // Observation only: every schedule-controlling release precedes this call.
            status = admission.service.status(admission.client.client, admission.review.reviewId)
        }
        assertNotNull(status.result, "Released synthetic work did not finish")
        return status
    }
    private fun rows(ledger: Ledger, includeInstances: Boolean = false): Map<String, List<List<String?>>> = ledger.read { c ->
        (listOf("metadata", "clients", "fair_queues", "fair_clients", "provider_fair_queues", "provider_fair_clients", "work",
            "reviews", "parent_plans", "produced_plan_admissions", "plan_slots", "plan_slot_resolutions", "events") +
            if (includeInstances) listOf("instances") else emptyList()).associateWith { table ->
            c.query("SELECT * FROM $table ORDER BY rowid") { row -> (1..row.metaData.columnCount).map { row.getString(it) } }
        }
    }
    private fun corrupt(block: () -> Unit) = assertEquals("ledger_corrupt", assertFailsWith<KernelException>(block = block).code)

    private inner class Harness(label: String, val policy: KernelConfig = config) : AutoCloseable {
        val root = temp.resolve("ledger-$label")
        val occupancy = Occupancy()
        val p = Probe("P", occupancy)
        val q = Probe("Q", occupancy)
        val pService = KernelService.open(root, p, policy)
        val qService = try { KernelService.open(root, q, policy) } catch (failure: Throwable) { pService.close(); throw failure }
        private val workers = mutableListOf<KernelService>()
        val admissions = mutableListOf<Admission>()
        fun service(provider: Probe) = if (provider === p) pService else { check(provider === q); qService }
        fun key(provider: Probe) = service(provider).providerKey
        fun client(id: String, tree: String) = pService.registerClient(binding(id, tree))
        fun ticket(name: String, tree: String) = Ticket(name, input(name, tree))
        fun submit(provider: Probe, client: ClientRegistration, ticket: Ticket, tag: String = ticket.name): Admission {
            provider.track(ticket)
            val service = service(provider)
            return Admission(service, client, service.submit(client.client, SubmitRequest("patch-$tag", "idempotency-$tag", ticket.input)))
                .also { admissions += it }
        }
        fun start(provider: Probe, count: Int = 1) { workers += KernelService.open(root, provider, policy.copy(workerCount = count)) }
        fun snapshot() = ages(pService.ledger)
        fun releaseAll() { p.releaseAll(); q.releaseAll() }
        fun finishAll() { releaseAll(); admissions.forEach { result(it) } }
        fun running(): Map<String, Long> = pService.ledger.read { c ->
            c.query("SELECT fair_key,COUNT(*) FROM work WHERE state='running' GROUP BY fair_key") { it.getString(1) to it.getLong(2) }.toMap()
        }
        override fun close() {
            releaseAll() // Includes failing assertions and worker-startup failures, before any close/join.
            try { workers.asReversed().forEach { it.close() } }
            finally { try { qService.close() } finally { pService.close() } }
        }
    }

    @Test fun finiteTwoProviderTwoTreeThreeClientBypassStopsAtTheNextEligibleQTurn() {
        Harness("finite-bypass").use { h ->
            val a = h.client("A", "H"); val b = h.client("B", "H"); val c = h.client("C", "G")
            val ps = (1..3).map { h.ticket("P$it", "H").also { ticket -> h.submit(h.p, a, ticket) } }
            val gs = (1..3).map { h.ticket("QG$it", "G").also { ticket -> h.submit(h.q, c, ticket) } }
            val qb = h.ticket("QB", "H"); val br = h.submit(h.q, b, qb)
            assertTrue(h.snapshot().trees.values.all { it == 0L })
            h.start(h.p); h.p.expect(ps[0])
            h.start(h.q); h.q.expect(gs[0]) // Initial Q tie is fixed by admission order, not timing.
            ps[0].release.countDown(); h.p.expect(ps[1])
            val beforeQ = h.snapshot()
            assertEquals(3L, beforeQ.counter)
            assertEquals(3L, beforeQ.trees[h.key(h.p) to fair(a.binding)])
            assertEquals(0L, beforeQ.trees[h.key(h.q) to fair(b.binding)])
            assertEquals(2L, beforeQ.trees[h.key(h.q) to fair(c.binding)])
            assertEquals(mapOf(fair(a.binding) to 1L, fair(c.binding) to 1L), h.running())
            assertEquals("queued", h.qService.status(b.client, br.review.reviewId).state)
            // H has only one running job (< physical cap2); when QG1 returns, global occupancy
            // drops below2. QB is eligible. P2 must not age Q's H domain and let QG2 bypass it.
            gs[0].release.countDown(); h.q.expect(qb)
            assertEquals(4L, h.snapshot().clients[h.key(h.q) to "B"])
            ps[1].release.countDown(); h.p.expect(ps[2])
            qb.release.countDown(); h.q.expect(gs[1])
            gs[1].release.countDown(); h.q.expect(gs[2])
            h.finishAll()
            assertEquals(7L, h.snapshot().counter)
            assertTrue((ps + gs + qb).all { it.calls.get() == 1 })
            h.occupancy.assertCaps(2, 2)
            // This finite counterexample can scale with backlog; it does not allege literal
            // infinite starvation in a ledger whose retained-admission quota is finite.
        }
    }

    @Test fun servingTheSameClientThroughPDoesNotSpendItsOlderQTurn() {
        Harness("same-client").use { h ->
            val x = h.client("X", "H"); val y = h.client("Y", "H")
            val qx = h.ticket("QX-first", "H"); val qy = h.ticket("QY", "H"); val px = h.ticket("PX", "H")
            h.submit(h.q, x, qx); h.submit(h.q, y, qy); h.submit(h.p, x, px)
            h.start(h.p); h.p.expect(px)
            assertEquals(1L, h.snapshot().clients[h.key(h.p) to "X"])
            assertEquals(0L, h.snapshot().clients[h.key(h.q) to "X"])
            h.start(h.q); h.q.expect(qx) // X's first Q review precedes Y's equally aged Q review.
            assertEquals(1L, h.snapshot().clients[h.key(h.p) to "X"])
            assertEquals(2L, h.snapshot().clients[h.key(h.q) to "X"])
            assertEquals(0L, h.snapshot().clients[h.key(h.q) to "Y"])
            qx.release.countDown(); h.q.expect(qy)
            assertEquals(1L, h.snapshot().clients[h.key(h.p) to "X"])
            h.finishAll()
        }
    }

    @Test fun firstQMembershipOfAKnownPClientJoinsCurrentRoundButExistingMembershipNeverResets() {
        Harness("new-membership").use { h ->
            val x = h.client("X", "H"); val y = h.client("Y", "G")
            val qy = h.ticket("new-QY", "G"); val px = h.ticket("new-PX", "H")
            h.submit(h.q, y, qy); h.submit(h.p, x, px)
            h.start(h.p); h.p.expect(px)
            assertNull(h.snapshot().clients[h.key(h.q) to "X"])
            val qx1 = h.ticket("new-QX1", "H"); h.submit(h.q, x, qx1)
            assertEquals(1L, h.snapshot().clients[h.key(h.q) to "X"])
            assertEquals(1L, h.snapshot().trees[h.key(h.q) to fair(x.binding)])
            assertEquals(1L, h.snapshot().clients[h.key(h.p) to "X"])
            h.start(h.q); h.q.expect(qy)
            val qx2 = h.ticket("new-QX2", "H"); h.submit(h.q, x, qx2)
            assertEquals(2L, h.snapshot().counter)
            assertEquals(1L, h.snapshot().clients[h.key(h.q) to "X"])
            assertEquals(1L, h.snapshot().trees[h.key(h.q) to fair(x.binding)])
            qy.release.countDown(); h.q.expect(qx1)
            qx1.release.countDown(); h.q.expect(qx2)
            assertEquals(1L, h.snapshot().clients[h.key(h.p) to "X"])
            h.finishAll()
        }
    }

    @Test fun sharedQDispatchChargesOnlyItsQSponsorAndNotAnIncidentalPeerOrItsPDomain() {
        Harness("shared-sponsor").use { h ->
            val a = h.client("A", "H"); val b = h.client("B", "H")
            val shared = h.ticket("shared-Q", "H"); val oldB = h.ticket("shared-B-old", "H")
            val laterA = h.ticket("shared-A-later", "H"); val pb = h.ticket("shared-PB", "H")
            val sponsor = h.submit(h.q, a, shared)
            h.submit(h.q, b, oldB)
            val incidental = h.submit(h.q, b, shared)
            h.submit(h.q, a, laterA); h.submit(h.p, b, pb)
            assertNotEquals(sponsor.review.reviewId, incidental.review.reviewId)
            h.start(h.p); h.p.expect(pb)
            h.start(h.q); h.q.expect(shared)
            assertEquals(2L, h.snapshot().clients[h.key(h.q) to "A"])
            assertEquals(0L, h.snapshot().clients[h.key(h.q) to "B"])
            assertEquals(1L, h.snapshot().clients[h.key(h.p) to "B"])
            h.qService.cancel(b.client, incidental.review.reviewId)
            assertEquals(0L, h.snapshot().clients[h.key(h.q) to "B"])
            shared.release.countDown(); h.q.expect(oldB)
            assertEquals(3L, h.snapshot().clients[h.key(h.q) to "B"])
            assertEquals(1L, h.snapshot().clients[h.key(h.p) to "B"])
            oldB.release.countDown(); h.q.expect(laterA)
            h.finishAll()
            assertEquals(1, shared.calls.get())
            assertEquals("cancelled", result(incidental).state)
            assertFalse(result(sponsor).cancelled)
            assertEquals(1L, h.snapshot().clients[h.key(h.p) to "B"])
        }
    }

    @Test fun physicalWorktreeAndGlobalCapsStillSpanDifferentProviderKeys() {
        Harness("physical-caps", config.copy(perWorktreeParallelism = 1)).use { h ->
            val a = h.client("A", "H"); val b = h.client("B", "H")
            val c = h.client("C", "G"); val d = h.client("D", "J")
            val ph = h.ticket("cap-PH", "H"); val qh = h.ticket("cap-QH", "H")
            val qg = h.ticket("cap-QG", "G"); val qj = h.ticket("cap-QJ", "J")
            h.submit(h.p, a, ph); h.submit(h.q, b, qh); h.submit(h.q, c, qg); h.submit(h.q, d, qj)
            h.start(h.p); h.p.expect(ph)
            h.start(h.q, count = 2); h.q.expect(qg)
            // Positive exclusion oracle: older Q(H) loses to younger Q(G) only because P(H)
            // already occupies the SAME physical-tree slot. Q has two workers for cap pressure.
            assertEquals(mapOf(fair(a.binding) to 1L, fair(c.binding) to 1L), h.running())
            assertEquals(0, qh.calls.get())
            ph.release.countDown(); h.q.expect(qh)
            assertEquals(mapOf(fair(b.binding) to 1L, fair(c.binding) to 1L), h.running())
            qg.release.countDown(); h.q.expect(qj)
            assertEquals(mapOf(fair(b.binding) to 1L, fair(d.binding) to 1L), h.running())
            h.finishAll()
            h.occupancy.assertCaps(2, 1)
            assertEquals(2, h.occupancy.maximum)
            assertEquals(4L, h.snapshot().counter)
        }
    }

    @Test fun workerlessReopenViaQThenPKeepsExactServedAndUnservedAgesAndCounter() {
        val h = Harness("restart")
        h.use {
            val a = h.client("A", "H"); val b = h.client("B", "H"); val c = h.client("C", "G")
            val p = h.ticket("restart-P", "H"); val admitted = h.submit(h.p, a, p)
            h.submit(h.q, b, h.ticket("restart-QH", "H")); h.submit(h.q, c, h.ticket("restart-QG", "G"))
            h.start(h.p); h.p.expect(p); p.release.countDown(); result(admitted)
        }
        val before = h.snapshot()
        assertEquals(1L, before.counter)
        assertEquals(1L, before.clients[h.key(h.p) to "A"])
        assertEquals(0L, before.clients[h.key(h.q) to "B"])
        KernelService.open(h.root, h.q, h.policy).use { q ->
            assertEquals(before, ages(q.ledger))
            KernelService.open(h.root, h.p, h.policy).use { p -> assertEquals(before, ages(p.ledger)) }
            assertEquals(before, ages(q.ledger))
            assertEquals(2L, q.ledger.read { it.count("SELECT COUNT(*) FROM work WHERE state='queued'") })
        }
        assertEquals(before, h.snapshot())
    }

    @Test fun missingSchema4AgesAreNotRepairedByAdmissionOrAnotherProviderReopen() {
        listOf("provider_fair_queues", "provider_fair_clients").forEach { table ->
            val h = Harness("missing-$table")
            h.use {
                val a = h.client("A", "H")
                h.submit(h.p, a, h.ticket("retained-$table", "H"))
                h.pService.ledger.transaction { it.execute("DELETE FROM $table WHERE provider_key=?", h.key(h.p)) }
                val damaged = rows(h.pService.ledger)
                corrupt { h.submit(h.p, a, h.ticket("must-not-accept-$table", "H")) }
                assertEquals(damaged, rows(h.pService.ledger), "Admission must not reset missing retained-domain ages or add an obligation")
            }
            val before = rows(h.pService.ledger, includeInstances = true)
            repeat(2) { corrupt { KernelService.open(h.root, h.q, h.policy).use { } } }
            assertEquals(before, rows(h.pService.ledger, includeInstances = true), "Refused reopen must not write instances, ages or accepted history")
            h.pService.ledger.read { c ->
                assertEquals(0L, c.count("SELECT COUNT(*) FROM $table WHERE provider_key=?", h.key(h.p)))
                assertEquals(0L, c.count("SELECT COUNT(*) FROM provider_fair_queues WHERE provider_key=?", h.key(h.q)))
                assertEquals(0L, c.count("SELECT COUNT(*) FROM provider_fair_clients WHERE provider_key=?", h.key(h.q)))
                assertEquals(1L, c.count("SELECT COUNT(*) FROM reviews"))
            }
        }
    }

    @Test fun persistedProducedAdmissionDomainWithoutExecutionUsesStoredPIdentityWhenQReopens() {
        listOf("provider_fair_queues", "provider_fair_clients", "fair_clients").forEach { table ->
            val h = Harness("admission-domain-$table")
            h.use {
                val a = h.client("declared-P", "H")
                h.pService.ledger.transaction { c ->
                    // Deliberately minimal schema-level DATA, not a normally accepted no-work
                    // produced plan, live grant, migration or execution/qualification evidence.
                    // No slots/work are fabricated: isolate admission-domain coverage itself.
                    h.pService.ledger.admitProviderFairness(c, h.key(h.p), fair(a.binding), a.binding.clientId)
                    c.execute("""INSERT INTO parent_plans(plan_id,client_id,patch_id,idempotency_key,request_hash,definition_json,plan_digest,review_frontier)
                        VALUES('domain-only',?,'patch-domain','key-domain',?,'{}',?,0)""", a.binding.clientId,
                        Digests.sha256("schema-level request"), Digests.sha256("schema-level declaration"))
                    c.execute("""INSERT INTO produced_plan_admissions(plan_id,origin_instance,authority_identity,provider_key,producer_slot_id,
                        consumer_slot_id,fair_key,archives_json,archive_bytes,admission_digest) VALUES('domain-only',?,?,?,'producer','consumer',?,'[]',0,?)""",
                        h.pService.instanceId, Digests.sha256("schema-level authority, not a grant"), h.key(h.p), fair(a.binding), Digests.sha256("schema-level admission"))
                    c.execute("UPDATE metadata SET value='9' WHERE key='dispatch_counter'")
                    c.execute("UPDATE provider_fair_queues SET last_served=3 WHERE provider_key=?", h.key(h.p))
                    c.execute("UPDATE provider_fair_clients SET last_served=4 WHERE provider_key=?", h.key(h.p))
                }
            }
            val before = h.snapshot()
            KernelService.open(h.root, h.q, h.policy).use { q ->
                assertEquals(before, ages(q.ledger))
                assertTrue(before.trees.keys.all { it.first == h.key(h.p) })
                assertTrue(before.clients.keys.all { it.first == h.key(h.p) })
                q.ledger.read { c ->
                    assertEquals(0L, c.count("SELECT COUNT(*) FROM work"))
                    assertEquals(0L, c.count("SELECT COUNT(*) FROM reviews"))
                    assertEquals(h.key(h.p), c.one("SELECT provider_key FROM produced_plan_admissions") { it.getString(1) })
                }
            }
            h.pService.ledger.transaction { c ->
                if (table == "fair_clients") {
                    c.execute("DELETE FROM provider_fair_clients WHERE provider_key=?", h.key(h.p))
                    c.execute("DELETE FROM fair_clients WHERE client_id='declared-P'")
                } else c.execute("DELETE FROM $table WHERE provider_key=?", h.key(h.p))
            }
            val damaged = rows(h.pService.ledger, includeInstances = true)
            if (table == "fair_clients") {
                // Q has no prior membership, but P's retained admission proves the physical
                // client mapping already existed. First Q admission must not repair its loss.
                corrupt { h.pService.ledger.transaction { c ->
                    h.pService.ledger.admitProviderFairness(c, h.key(h.q), fair(binding("declared-P", "H")), "declared-P")
                } }
                assertEquals(damaged, rows(h.pService.ledger, includeInstances = true))
            }
            corrupt { KernelService.open(h.root, h.q, h.policy).use { } }
            assertEquals(damaged, rows(h.pService.ledger, includeInstances = true))
        }
    }

    @Test fun actualProducedAdmissionAndReadyProducerInsertionPreserveExistingQAgesAfterPDispatch() {
        // Reuse the actual current1665 synthetic SPI fixture, not the schema-level DATA above.
        // Neither ready insertion nor these assertions claim produced-resolution fairness.
        val f = ProducedLane1665Fixture(temp.resolve("produced-fixture")); val qProvider = f.Provider()
        val policy = ProducedLane1665Fixture.CONFIG.copy(workerCount = 0)
        val root = temp.resolve("produced-real-admission-ledger")
        val pProvider = Probe("P", Occupancy())
        KernelService.open(root, qProvider, policy, planAuthority = f, preparedInputObserver = f.observer, resultPolicy = f.policy).use { q ->
            val client = q.registerClient(ProducedLane1665Fixture.binding("provider-ages"))
            val first = f.definition(client.binding, "provider-ages-first")
            q.submitPlan(client.client, first.request)
            val initial = ages(q.ledger)
            assertEquals(0L, initial.trees[q.providerKey to fair(client.binding)])
            assertEquals(0L, initial.clients[q.providerKey to client.binding.clientId])
            KernelService.open(root, pProvider, policy.copy(workerCount = 1)).use { p ->
                try {
                    val pc = p.registerClient(binding("ordinary-P", "G"))
                    val ticket = Ticket("advance-P", input("advance-P", "G")); pProvider.track(ticket)
                    val pr = Admission(p, pc, p.submit(pc.client, SubmitRequest("advance-P", "advance-P", ticket.input)))
                    pProvider.expect(ticket)
                    assertEquals(1L, ages(q.ledger).counter)
                    val second = f.definition(client.binding, "provider-ages-second")
                    q.submitPlan(client.client, second.request)
                    val after = ages(q.ledger)
                    assertEquals(1L, after.counter)
                    assertEquals(0L, after.trees[q.providerKey to fair(client.binding)])
                    assertEquals(0L, after.clients[q.providerKey to client.binding.clientId])
                    q.ledger.read { c ->
                        assertEquals(2L, c.count("SELECT COUNT(*) FROM produced_plan_admissions WHERE provider_key=?", q.providerKey))
                        assertEquals(2L, c.count("""SELECT COUNT(*) FROM plan_slot_resolutions x JOIN work w ON w.work_key=x.work_key
                            WHERE x.state='ready' AND w.state='queued' AND w.provider_key=?""", q.providerKey))
                        assertEquals(0L, c.count("SELECT COUNT(*) FROM work WHERE provider_key=? AND attempt<>0", q.providerKey))
                    }
                    assertEquals(2, f.producerPreparations.get())
                    assertEquals(0, qProvider.calls.get())
                    assertTrue(f.resolutionCalls.values.all { it.get() == 0 })
                    ticket.release.countDown(); result(pr)
                } finally { pProvider.releaseAll() }
            }
        }
    }
}

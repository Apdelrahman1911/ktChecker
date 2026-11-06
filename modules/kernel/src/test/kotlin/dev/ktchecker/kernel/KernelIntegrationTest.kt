package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/** Real SQLite/files/threads; the provider and model/source authorities are explicitly synthetic. */
class KernelIntegrationTest {
    @TempDir lateinit var temp: Path
    private val rule = "test/no-defect"
    private val testConfig = KernelConfig(workerCount = 2, globalParallelism = 2, perWorktreeParallelism = 1,
        heartbeatMillis = 30, leaseMillis = 120, pollMillis = 5, analysisTimeoutMillis = 15_000)

    private fun fixtureInput(name: String, text: String = "fun main() = Unit\n", worktree: String = "tree", parent: String? = null,
                      context: RuleContext = RuleContext()): AnalysisInput {
        val root = temp.resolve("source-$name")
        Files.createDirectories(root)
        Files.writeString(root.resolve("Main.kt"), text)
        val snapshot = SnapshotManifest(Digests.sha256("snapshot:$name:$text:$parent"), "workspace", worktree,
            listOf(SnapshotFile("Main.kt", Digests.sha256(text), text.toByteArray().size.toLong())),
            ownerId = "owner", parentSnapshotId = parent, declaredRoots = listOf("."))
        return AnalysisInput(snapshot, root.toString(), AnalysisContext(scope = context,
            modelDigest = Digests.sha256("model"), dependencyDigest = Digests.sha256("deps"),
            toolchainDigest = Digests.sha256("tools"), configurationDigest = Digests.sha256("config"), fullProject = true), listOf(rule))
    }

    private fun binding(id: String, tree: String = "tree") = ClientBinding("owner", "workspace", tree, id, "lineage-$id", 1, "session-$id")
    private fun request(id: String, input: AnalysisInput) = SubmitRequest("patch-$id", "idempotency-$id", input)

    private class Provider(private val body: (AnalysisInput, CancellationToken) -> ProviderResult) : AnalysisProvider {
        override val id = "synthetic-test"
        override val supportedRuleIds = setOf("test/no-defect")
        val calls = AtomicInteger()
        override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
            calls.incrementAndGet()
            return body(input, cancellation)
        }
    }

    private fun passed(input: AnalysisInput, findings: List<Finding> = emptyList()) = ProviderResult("synthetic-test", "complete", findings,
        input.requiredRuleIds.map { Coverage(it, input.context.scope, if (findings.any { f -> f.ruleId == it }) "findings" else "passed",
            provenance = "executed", certificateId = Digests.sha256("synthetic-certificate:${input.analysisKey()}:$it"),
            implemented = true, tested = true, qualified = true) })

    private fun finding(input: AnalysisInput) = Finding(rule, "Synthetic defect", "Synthetic test diagnosis", "Fixture only",
        SourceLocation("Main.kt", input.snapshot.files.single().digest), Digests.sha256("finding:${input.snapshot.snapshotId}"), "synthetic-test",
        context = input.context.scope)

    /** This is a controlled test authority, not evidence of a production host or live scan. */
    private class SyntheticFence(initial: AnalysisInput) : SnapshotGate {
        private val lock = Any()
        private var current = initial
        private var epoch = 1L
        fun update(input: AnalysisInput) = synchronized(lock) { current = input; epoch++ }
        override fun <T> withCurrentSnapshot(input: AnalysisInput, action: (SnapshotCheck) -> T): T = synchronized(lock) {
            action(SnapshotCheck(current.snapshot.snapshotId == input.snapshot.snapshotId, current.snapshot.snapshotId, epoch,
                current.context.configurationDigest, current.context.modelDigest, current.context.dependencyDigest,
                current.context.toolchainDigest, "synthetic-config-source-epoch:$epoch", authoritative = true))
        }
    }

    private fun await(service: KernelService, client: ClientRegistration, reviewId: String): ReviewStatus {
        var status = service.status(client.client, reviewId)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (status.result == null && System.nanoTime() < deadline) {
            Thread.sleep(5)
            status = service.status(client.client, reviewId)
        }
        assertNotNull(status.result, "Review did not finish: $status")
        return status
    }
    private fun allEvents(service: KernelService, client: ClientRegistration): List<ReviewEvent> = buildList {
        var cursor: String? = null
        do {
            val page = service.events(client.client, cursor, 7)
            addAll(page.events)
            cursor = page.cursor
        } while (page.hasMore)
    }
    private fun insertAll(service: KernelService, client: ClientRegistration, observe: Boolean = true): List<ReviewEvent> {
        val events = allEvents(service, client)
        for (event in events) {
            assertEquals(Digests.sha256(event.payload), event.payloadDigest)
            val contextDigest = Digests.sha256("synthetic context item:${event.payload}")
            service.acknowledge(client.adapter, listOf(event.eventId), ContextReceipt("ack-${event.eventId}", contextDigest,
                "item-${event.eventId}", Instant.EPOCH.toString()))
            if (observe) service.observe(client.adapter, listOf(event.eventId), ObservationReceipt("observe-${event.eventId}",
                "synthetic-model-request-${event.eventId}", listOf(contextDigest)))
        }
        return events
    }
    private fun assertCode(code: String, block: () -> Unit) = assertEquals(code, assertFailsWith<KernelException>(block = block).code)
    private fun submitRetry(service: KernelService, client: ClientRegistration, request: SubmitRequest): ReviewStatus {
        repeat(1000) {
            try { return service.submit(client.client, request) }
            catch (e: KernelException) { if (e.code != "ingestion_busy") throw e; Thread.sleep(2) }
        }
        error("bounded retry exhausted")
    }

    @Test fun sharedExecutionKeepsCancellationPermissionsAndDeliverySeparate() {
        val input = fixtureInput("shared")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val provider = Provider { effective, cancellation ->
            entered.countDown()
            assertTrue(release.await(10, TimeUnit.SECONDS))
            assertFalse(cancellation.isCancelled(), "One client must not cancel shared computation")
            passed(effective)
        }
        KernelService.open(temp.resolve("ledger"), provider, testConfig, SyntheticFence(input)).use { service ->
            val a = service.registerClient(binding("a"))
            val b = service.registerClient(binding("b"))
            val ar = service.submit(a.client, request("same", input))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val br = service.submit(b.client, request("same", input))
            service.cancel(a.client, ar.reviewId)
            release.countDown()
            assertEquals("cancelled", await(service, a, ar.reviewId).state)
            assertEquals("complete", await(service, b, br.reviewId).state)
            assertEquals(1, provider.calls.get())
            assertCode("review_not_found") { service.status(a.client, br.reviewId) }
            assertCode("unauthorized") { service.acknowledge(AdapterCredential(a.client.clientId, a.client.token),
                listOf(allEvents(service, a).first().eventId), ContextReceipt("fake", Digests.sha256("x"), "item", Instant.EPOCH.toString())) }
            insertAll(service, b)
            val cutB = service.prepareGate(b.client)
            assertTrue(cutB.ready, cutB.reasons.toString())
            assertTrue(service.completeGate(b.client, cutB.cutId).allowed)
            insertAll(service, a)
            assertTrue(service.prepareGate(a.client).reasons.any { it.startsWith("review_cancelled:") })
            assertTrue(allEvents(service, a).map { it.eventId }.intersect(allEvents(service, b).map { it.eventId }.toSet()).isEmpty())
        }
    }

    @Test fun insertionIsNotObservationAndFiniteCutRequiresExactTrustedReceipt() {
        val input = fixtureInput("receipts")
        KernelService.open(temp.resolve("ledger"), Provider { i, _ -> passed(i) }, testConfig, SyntheticFence(input)).use { service ->
            val a = service.registerClient(binding("a"))
            val b = service.registerClient(binding("b"))
            val review = service.submit(a.client, request("1", input))
            await(service, a, review.reviewId)
            val event = allEvents(service, a).first()
            assertCode("insertion_required") { service.observe(a.adapter, listOf(event.eventId),
                ObservationReceipt("premature", "request", listOf(event.payloadDigest))) }
            assertCode("event_not_found") { service.acknowledge(b.adapter, listOf(event.eventId),
                ContextReceipt("foreign", event.payloadDigest, "item", Instant.EPOCH.toString())) }
            val aCursor = service.events(a.client, limit = 1).cursor
            assertCode("invalid_cursor") { service.events(b.client, aCursor) }
            assertCode("invalid_cursor") { service.events(a.client, aCursor + "x") }
            val originalCount = allEvents(service, a).size
            insertAll(service, a, observe = false)
            val cut = service.prepareGate(a.client)
            assertFalse(cut.ready)
            assertTrue(cut.reasons.any { it.startsWith("model_observation_missing:") })
            assertFalse(service.completeGate(a.client, cut.cutId).allowed)
            assertCode("observation_mismatch") { service.observe(a.adapter, listOf(event.eventId),
                ObservationReceipt("wrong", "request", listOf(Digests.sha256("not inserted")))) }
            insertAll(service, a)
            var performed = 0
            val result = service.completeGate(a.client, cut.cutId) { performed++ }
            assertTrue(result.allowed, result.reasons.toString())
            assertTrue(result.actionPerformed)
            assertEquals(1, performed)
            assertFalse(service.completeGate(a.client, cut.cutId) { performed++ }.allowed)
            assertEquals(1, performed)
            assertEquals(originalCount, allEvents(service, a).size, "Gate and receipt control must not recursively require delivery")
        }
    }

    @Test fun gateChecksSourceAndConfigEpochAtTheActualSynchronousAction() {
        val input = fixtureInput("fence")
        val changed = fixtureInput("changed", "fun changed() = 1\n", parent = input.snapshot.snapshotId)
        val fence = SyntheticFence(input)
        KernelService.open(temp.resolve("ledger"), Provider { i, _ -> passed(i) }, testConfig, fence).use { service ->
            val client = service.registerClient(binding("client"))
            val review = service.submit(client.client, request("1", input))
            await(service, client, review.reviewId)
            insertAll(service, client)
            val stale = service.prepareGate(client.client)
            fence.update(changed)
            assertFalse(service.completeGate(client.client, stale.cutId).allowed)
            fence.update(input)
            assertFalse(service.completeGate(client.client, stale.cutId).allowed, "Source ABA must invalidate the captured epoch")
            val cut = service.prepareGate(client.client)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val changedDone = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val final = pool.submit<GateResult> { service.completeGate(client.client, cut.cutId) {
                    entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS))
                } }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                pool.submit { fence.update(changed); changedDone.countDown() }
                assertFalse(changedDone.await(75, TimeUnit.MILLISECONDS), "Source fence must span the guarded action")
                release.countDown()
                assertTrue(final.get(5, TimeUnit.SECONDS).actionPerformed)
                assertTrue(changedDone.await(5, TimeUnit.SECONDS))
            } finally { release.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun durableAcceptanceSurvivesRestartAndOriginalMaterializationChanges() {
        val input = fixtureInput("durable", "val original = 7\n")
        val database = temp.resolve("ledger")
        val provider = Provider { i, _ ->
            assertNotEquals(input.materializedRoot, i.materializedRoot)
            assertEquals("val original = 7\n", Files.readString(Path.of(i.materializedRoot).resolve("Main.kt")))
            passed(i)
        }
        lateinit var client: ClientRegistration
        lateinit var accepted: ReviewStatus
        KernelService.open(database, provider, testConfig.copy(workerCount = 0)).use { service ->
            client = service.registerClient(binding("durable"))
            accepted = service.submit(client.client, request("1", input))
            assertEquals("queued", accepted.state)
        }
        Files.writeString(Path.of(input.materializedRoot).resolve("Main.kt"), "different mutable bytes")
        KernelService.open(database, provider, testConfig, SyntheticFence(input)).use { recovered ->
            assertEquals("complete", await(recovered, client, accepted.reviewId).state)
            assertEquals(accepted.reviewId, recovered.submit(client.client, request("1", input)).reviewId)
            assertCode("client_exists") { recovered.registerClient(binding("durable").copy(generation = 2)) }
            assertTrue(allEvents(recovered, client).any { it.kind == "accepted" })
            assertTrue(allEvents(recovered, client).any { it.kind == "result" })
        }
    }

    @Test fun concurrentIdempotencyAndMultipleServiceInstancesRetainEveryPatch() {
        val input = fixtureInput("concurrent")
        val provider = Provider { i, _ -> passed(i) }
        val config = testConfig.copy(workerCount = 0)
        val database = temp.resolve("ledger")
        KernelService.open(database, provider, config).use { first ->
            KernelService.open(database, provider, config).use { second ->
                val client = first.registerClient(binding("concurrent"))
                val pool = Executors.newFixedThreadPool(8)
                try {
                    val duplicates = pool.invokeAll((1..24).map { index -> Callable {
                        submitRetry(if (index % 2 == 0) first else second, client, request("duplicate", input))
                    } }).map { it.get().reviewId }
                    assertEquals(1, duplicates.distinct().size)
                    val distinct = pool.invokeAll((1..40).map { index -> Callable {
                        submitRetry(if (index % 2 == 0) first else second, client, request("$index", input))
                    } }).map { it.get() }
                    assertEquals(40, distinct.map { it.reviewId }.distinct().size)
                    assertEquals((1L..41L).toList(), (distinct.map { it.sequence } + 1L).sorted())
                    assertEquals(41, allEvents(first, client).count { it.kind == "accepted" })
                    assertCode("idempotency_conflict") { first.submit(client.client, request("duplicate", input).copy(patchId = "different")) }
                    assertCode("patch_id_conflict") { first.submit(client.client, request("duplicate", input).copy(idempotencyKey = "different")) }
                    KernelService.open(database, provider, testConfig).use { worker ->
                        distinct.forEach { assertEquals("complete", await(worker, client, it.reviewId).state) }
                    }
                    assertEquals(1, provider.calls.get(), "Only full-identical inputs share work, not obligations")
                    assertEquals(41, allEvents(second, client).count { it.kind == "result" })
                } finally { pool.shutdownNow() }
            }
        }
    }

    @Test fun fairQueueDoesNotLetAHotWorktreeStarveColdWorktrees() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val provider = Provider { i, _ -> order += i.snapshot.worktreeId; passed(i) }
        val config = testConfig.copy(workerCount = 0, globalParallelism = 1)
        val database = temp.resolve("ledger")
        KernelService.open(database, provider, config).use { service ->
            val a = service.registerClient(binding("a", "a"))
            val b = service.registerClient(binding("b", "b"))
            val c = service.registerClient(binding("c", "c"))
            val records = (1..5).map { a to service.submit(a.client, request("a$it", fixtureInput("a$it", worktree = "a"))) } +
                (b to service.submit(b.client, request("b", fixtureInput("b", worktree = "b")))) +
                (c to service.submit(c.client, request("c", fixtureInput("c", worktree = "c"))))
            KernelService.open(database, provider, config.copy(workerCount = 1)).use { worker ->
                records.forEach { (client, review) -> await(worker, client, review.reviewId) }
            }
            assertEquals(listOf("a", "b", "c"), order.take(3))
            assertEquals(7, order.size)
        }
    }

    @Test fun clientFairnessPreventsOneAgentBacklogFromStarvingOthersInTheSameWorktree() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val provider = Provider { i, _ -> order += i.snapshot.snapshotId; passed(i) }
        val config = testConfig.copy(workerCount = 0, globalParallelism = 1)
        val database = temp.resolve("client-fair-ledger")
        KernelService.open(database, provider, config).use { submitter ->
            val hot = submitter.registerClient(binding("hot"))
            val coldB = submitter.registerClient(binding("cold-b"))
            val coldC = submitter.registerClient(binding("cold-c"))
            val inputs = (1..24).map { fixtureInput("hot-$it") }
            val b = fixtureInput("cold-b")
            val c = fixtureInput("cold-c")
            val records = inputs.mapIndexed { index, input -> hot to submitter.submit(hot.client, request("h$index", input)) } +
                (coldB to submitter.submit(coldB.client, request("b", b))) +
                (coldC to submitter.submit(coldC.client, request("c", c)))
            KernelService.open(database, provider, config.copy(workerCount = 1)).use { worker ->
                records.forEach { (client, review) -> await(worker, client, review.reviewId) }
            }
            assertEquals(listOf(inputs.first().snapshot.snapshotId, b.snapshot.snapshotId, c.snapshot.snapshotId), order.take(3))
            assertEquals(inputs.map { it.snapshot.snapshotId }, order.filter { it != b.snapshot.snapshotId && it != c.snapshot.snapshotId },
                "The sponsoring client's own queued obligations remain FIFO")
            assertEquals(26, order.size)
        }
    }

    @Test fun coalescedDispatchDoesNotSpendOtherSubscribersTurnsAndServiceAgesSurviveRestart() {
        val shared = fixtureInput("fair-shared")
        val oldB = fixtureInput("fair-b-old")
        val oldC = fixtureInput("fair-c-old")
        val laterA = fixtureInput("fair-a-later")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val provider = Provider { i, token ->
            order += i.snapshot.snapshotId
            if (i.snapshot.snapshotId == shared.snapshot.snapshotId) {
                entered.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS))
                assertFalse(token.isCancelled(), "A co-subscriber's cancellation cannot stop the sponsor's computation")
            }
            passed(i)
        }
        val config = testConfig.copy(workerCount = 0, globalParallelism = 1)
        val database = temp.resolve("shared-client-fair-ledger")
        var ages = emptyMap<String, Long>()
        KernelService.open(database, provider, config).use { submitter ->
            val a = submitter.registerClient(binding("a"))
            val b = submitter.registerClient(binding("b"))
            val c = submitter.registerClient(binding("c"))
            val ar = submitter.submit(a.client, request("shared", shared))
            val br = submitter.submit(b.client, request("old", oldB))
            val bs = submitter.submit(b.client, request("shared", shared))
            val cr = submitter.submit(c.client, request("old", oldC))
            val al = submitter.submit(a.client, request("later", laterA))
            KernelService.open(database, provider, config.copy(workerCount = 1)).use { worker ->
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    val firstAges = submitter.ledger.read { connection -> connection.query(
                        "SELECT client_id,last_served FROM fair_clients") { it.getString(1) to it.getLong(2) }.toMap() }
                    assertEquals(mapOf("a" to 1L, "b" to 0L, "c" to 0L), firstAges,
                        "Incidental shared service must not consume B's turn for its older exclusive work")
                    submitter.cancel(b.client, bs.reviewId)
                    release.countDown()
                    listOf(a to ar, b to br, b to bs, c to cr, a to al).forEach { (client, review) -> await(worker, client, review.reviewId) }
                    assertEquals("cancelled", worker.status(b.client, bs.reviewId).state)
                    assertEquals("complete", worker.status(a.client, ar.reviewId).state)
                } finally { release.countDown() }
            }
            assertEquals(listOf(shared, oldB, oldC, laterA).map { it.snapshot.snapshotId }, order)
            ages = submitter.ledger.read { connection -> connection.query(
                "SELECT client_id,last_served FROM fair_clients") { it.getString(1) to it.getLong(2) }.toMap() }
            assertEquals(mapOf("a" to 4L, "b" to 2L, "c" to 3L), ages)
        }
        KernelService.open(database, provider, config).use { restarted ->
            assertEquals(ages, restarted.ledger.read { connection -> connection.query(
                "SELECT client_id,last_served FROM fair_clients") { it.getString(1) to it.getLong(2) }.toMap() })
            assertEquals(4, provider.calls.get())
        }
    }

    @Test fun newlyAdmittedClientJoinsTheCurrentFairRoundBehindAlreadyWaitingClients() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = fixtureInput("fair-first")
        val waiting = fixtureInput("fair-waiting")
        val newcomer = fixtureInput("fair-newcomer")
        val provider = Provider { i, _ ->
            order += i.snapshot.snapshotId
            if (i.snapshot.snapshotId == first.snapshot.snapshotId) {
                entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS))
            }
            passed(i)
        }
        val config = testConfig.copy(workerCount = 0, globalParallelism = 1)
        val database = temp.resolve("fair-new-client-ledger")
        KernelService.open(database, provider, config).use { submitter ->
            val a = submitter.registerClient(binding("a"))
            val b = submitter.registerClient(binding("b"))
            val ar = submitter.submit(a.client, request("a", first))
            val br = submitter.submit(b.client, request("b", waiting))
            KernelService.open(database, provider, config.copy(workerCount = 1)).use { worker ->
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    val c = submitter.registerClient(binding("new"))
                    val cr = submitter.submit(c.client, request("c", newcomer))
                    assertEquals(1L, submitter.ledger.read { connection -> connection.count(
                        "SELECT last_served FROM fair_clients WHERE client_id='new'") })
                    release.countDown()
                    listOf(a to ar, b to br, c to cr).forEach { (client, review) -> await(worker, client, review.reviewId) }
                } finally { release.countDown() }
            }
            assertEquals(listOf(first, waiting, newcomer).map { it.snapshot.snapshotId }, order)
        }
    }

    @Test fun reopeningDamagedSchema4RefusesMissingFairnessTableWithoutRepairingOrLosingAcceptedObligations() {
        val input = fixtureInput("damaged-fairness")
        val provider = Provider { i, _ -> passed(i) }
        val database = temp.resolve("damaged-fairness-ledger")
        lateinit var client: ClientRegistration
        lateinit var review: ReviewStatus
        lateinit var acceptedEvents: List<ReviewEvent>
        KernelService.open(database, provider, testConfig.copy(workerCount = 0)).use { service ->
            client = service.registerClient(binding("damaged-fairness"))
            review = service.submit(client.client, request("retained", input))
            acceptedEvents = allEvents(service, client)
        }
        // Test-only offline corruption of schema4, NOT a supported older-schema upgrade.
        DriverManager.getConnection("jdbc:sqlite:${database.resolve("ledger.sqlite").toUri()}?mode=rw").use { c ->
            c.execute("DROP TABLE fair_clients")
            fun retained(connection: Connection) = listOf("metadata", "clients", "instances", "fair_queues", "work", "reviews", "events")
                .associateWith { table -> connection.query("SELECT * FROM $table ORDER BY rowid") { row ->
                    List(row.metaData.columnCount) { column -> row.getString(column + 1) }
                } }
            val before = retained(c)
            val schema = c.query("SELECT sql FROM sqlite_schema ORDER BY name") { it.getString(1) }
            repeat(2) {
                assertCode("ledger_corrupt") { KernelService.open(database, provider, testConfig).use { } }
                assertEquals(before, retained(c), "Refusal must preserve every accepted review, work row and outbox")
                assertEquals(schema, c.query("SELECT sql FROM sqlite_schema ORDER BY name") { it.getString(1) })
                assertEquals(0L, c.count("SELECT COUNT(*) FROM sqlite_schema WHERE name='fair_clients'"))
            }
            assertEquals("queued", c.one("SELECT w.state FROM work w JOIN reviews r ON r.work_key=w.work_key WHERE r.review_id=?",
                review.reviewId) { it.getString(1) })
            assertEquals(acceptedEvents.map { it.payload }, c.query("SELECT payload FROM events ORDER BY sequence") { it.getString(1) })
            assertEquals(1, acceptedEvents.size)
            assertEquals("accepted", acceptedEvents.single().kind)
        }
        assertEquals(0, provider.calls.get())
    }

    @Test fun concurrentSchema4ReopensKeepSidecarsPrivateAndRetainAcceptedWork() {
        val provider = Provider { i, _ -> passed(i) }
        val database = temp.resolve("reopen-sidecars-ledger")
        val config = testConfig.copy(workerCount = 0)
        lateinit var client: ClientRegistration
        lateinit var review: ReviewStatus
        lateinit var acceptedEvents: List<ReviewEvent>
        KernelService.open(database, provider, config).use { service ->
            client = service.registerClient(binding("reopen-sidecars"))
            review = service.submit(client.client, request("retained", fixtureInput("reopen-sidecars")))
            acceptedEvents = allEvents(service, client)
        }
        // Two bounded producers, 24 real reopen/close cycles each, zero provider workers.
        // Heartbeat/read connections also open and close, exercising last-connection WAL cleanup.
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val attempts = (0 until 2).map { pool.submit(Callable {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                repeat(24) {
                    KernelService.open(database, provider, config).use { reopened ->
                        assertEquals(review, reopened.status(client.client, review.reviewId))
                        assertEquals(acceptedEvents, allEvents(reopened, client))
                        reopened.ledger.read { c ->
                            assertEquals("4", c.one("SELECT value FROM metadata WHERE key='schema_version'") { it.getString(1) })
                            // This read connection keeps SQLite's sidecars present during inspection.
                            listOf("ledger.sqlite", "ledger.sqlite-wal", "ledger.sqlite-shm").forEach { name ->
                                val path = database.resolve(name)
                                assertTrue(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                                if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                                    assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path))
                                }
                            }
                            assertEquals(1L, c.count("SELECT COUNT(*) FROM reviews"))
                            assertEquals(1L, c.count("SELECT COUNT(*) FROM events"))
                        }
                    }
                }
            }) }
            start.countDown()
            attempts.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
        KernelService.open(database, provider, config).use { reopened ->
            assertEquals(review, reopened.status(client.client, review.reviewId))
            assertEquals(acceptedEvents, allEvents(reopened, client))
        }
        assertEquals(0, provider.calls.get())
    }

    @Test fun globalAndPerWorktreeCapsSpanIndependentServiceInstances() {
        val concurrent = AtomicInteger()
        val maximum = AtomicInteger()
        val perTree = ConcurrentHashMap<String, AtomicInteger>()
        val maximumPerTree = ConcurrentHashMap<String, AtomicInteger>()
        val provider = Provider { i, _ ->
            val n = concurrent.incrementAndGet(); maximum.accumulateAndGet(n, ::maxOf)
            val tree = perTree.computeIfAbsent(i.snapshot.worktreeId) { AtomicInteger() }.incrementAndGet()
            maximumPerTree.computeIfAbsent(i.snapshot.worktreeId) { AtomicInteger() }.accumulateAndGet(tree, ::maxOf)
            Thread.sleep(25)
            perTree.getValue(i.snapshot.worktreeId).decrementAndGet(); concurrent.decrementAndGet()
            passed(i)
        }
        val database = temp.resolve("ledger")
        KernelService.open(database, provider, testConfig.copy(workerCount = 0)).use { submitter ->
            val jobs = (1..3).flatMap { tree ->
                val client = submitter.registerClient(binding("client$tree", "tree$tree"))
                (1..5).map { n -> client to submitter.submit(client.client,
                    request("$tree-$n", fixtureInput("$tree-$n", worktree = "tree$tree"))) }
            }
            KernelService.open(database, provider, testConfig).use { first ->
                KernelService.open(database, provider, testConfig).use {
                    jobs.forEach { (client, review) -> await(first, client, review.reviewId) }
                }
            }
            assertTrue(maximum.get() <= 2)
            assertEquals(2, maximum.get())
            assertTrue(maximumPerTree.values.all { it.get() == 1 })
        }
    }

    @Test fun queueAndMetadataBoundsRejectBeforeAcceptanceWithoutDiscardingOldPatches() {
        val provider = Provider { i, _ -> passed(i) }
        KernelService.open(temp.resolve("ledger"), provider, testConfig.copy(workerCount = 0, maxQueuedWork = 1)).use { service ->
            val client = service.registerClient(binding("bounded"))
            val first = service.submit(client.client, request("1", fixtureInput("one")))
            assertCode("queue_full") { service.submit(client.client, request("2", fixtureInput("two"))) }
            assertEquals("queued", service.status(client.client, first.reviewId).state)
            assertEquals(1, allEvents(service, client).size)
            assertCode("snapshot_scope_mismatch") { service.submit(client.client, request("foreign", fixtureInput("foreign", worktree = "foreign"))) }
        }
    }

    @Test fun exactCoverageImplementationIdentityAndSourceValidationFailClosed() {
        val variations: List<(AnalysisInput) -> ProviderResult> = listOf(
            { i -> passed(i).copy(coverage = emptyList()) },
            { i -> passed(i).copy(coverage = passed(i).coverage.map { it.copy(context = RuleContext(sourceSet = "test")) }) },
            { i -> passed(i).copy(coverage = passed(i).coverage.map { it.copy(implemented = false) }) },
            { i -> passed(i).copy(coverage = passed(i).coverage.map { it.copy(qualified = false) }) },
            { i -> passed(i).copy(providerId = "untrusted") },
            { i -> passed(i, listOf(finding(i).copy(location = SourceLocation("Main.kt", Digests.sha256("other"))))) },
            { i -> passed(i, listOf(finding(i).copy(disposition = "fixed"))) },
        )
        variations.forEachIndexed { index, result ->
            val input = fixtureInput("invalid-$index")
            KernelService.open(temp.resolve("ledger-$index"), Provider { i, _ -> result(i) }, testConfig, SyntheticFence(input)).use { service ->
                val client = service.registerClient(binding("bad$index"))
                val review = service.submit(client.client, request("1", input))
                assertEquals("incomplete", await(service, client, review.reviewId).state)
                insertAll(service, client)
                assertFalse(service.prepareGate(client.client).ready)
            }
        }
    }

    @Test fun emptyRequestedPlanExpandsToExplicitSupportedRulesInsteadOfVacuousSuccess() {
        val input = fixtureInput("empty-plan").copy(requiredRuleIds = emptyList())
        val provider = Provider { i, _ -> assertEquals(listOf(rule), i.requiredRuleIds); passed(i) }
        KernelService.open(temp.resolve("ledger"), provider, testConfig, SyntheticFence(input)).use { service ->
            val client = service.registerClient(binding("empty"))
            val review = service.submit(client.client, request("1", input))
            assertEquals("complete", await(service, client, review.reviewId).state)
            insertAll(service, client)
            assertTrue(service.prepareGate(client.client).ready)
        }
    }

    @Test fun laterCompleteAncestralCertificateResolvesButLateOldCompletionCannotOverwriteIt() {
        val old = fixtureInput("old", "val bug = 1\n")
        val current = fixtureInput("fixed", "val fixed = 2\n", parent = old.snapshot.snapshotId)
        val oldStarted = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val provider = Provider { i, _ ->
            if (i.snapshot.snapshotId == old.snapshot.snapshotId) {
                oldStarted.countDown(); assertTrue(releaseOld.await(10, TimeUnit.SECONDS)); passed(i, listOf(finding(i)))
            } else passed(i)
        }
        KernelService.open(temp.resolve("ledger"), provider, testConfig.copy(perWorktreeParallelism = 2), SyntheticFence(current)).use { service ->
            val client = service.registerClient(binding("lineage"))
            val first = service.submit(client.client, request("old", old))
            assertTrue(oldStarted.await(5, TimeUnit.SECONDS))
            val second = service.submit(client.client, request("fixed", current))
            assertEquals("complete", await(service, client, second.reviewId).state)
            assertFalse(service.prepareGate(client.client).ready, "All accepted patches, including intermediate ones, remain obligations")
            releaseOld.countDown()
            assertEquals(1, await(service, client, first.reviewId).result!!.findings.size)
            insertAll(service, client)
            val cut = service.prepareGate(client.client)
            assertTrue(cut.ready, cut.reasons.toString())
            assertTrue(service.completeGate(client.client, cut.cutId).allowed)
        }
    }

    @Test fun localPolicyNeverLeaksThroughSharedComputationAndCannotRewriteFailures() {
        val input = fixtureInput("policy")
        val provider = Provider { i, _ -> passed(i, listOf(finding(i))) }
        val policy = ResultPolicy { binding, _, raw ->
            if (binding.clientId == "accepted") raw.copy(findings = raw.findings.map { it.copy(disposition = "accepted") }) else raw
        }
        KernelService.open(temp.resolve("ledger"), provider, testConfig, SyntheticFence(input), resultPolicy = policy).use { service ->
            val a = service.registerClient(binding("accepted"))
            val b = service.registerClient(binding("open"))
            val ar = service.submit(a.client, request("1", input))
            val br = service.submit(b.client, request("1", input))
            assertEquals("accepted", await(service, a, ar.reviewId).result!!.findings.single().disposition)
            assertEquals("open", await(service, b, br.reviewId).result!!.findings.single().disposition)
            assertEquals(1, provider.calls.get())
            insertAll(service, a); insertAll(service, b)
            assertTrue(service.prepareGate(a.client).ready)
            assertTrue(service.prepareGate(b.client).reasons.any { it.startsWith("open_finding:") })
            val raw = service.ledger.read { c -> c.one("SELECT result_json FROM work") { it.getString(1) }!! }
            assertEquals("open", ContractJson.decodeFromString<ProviderResult>(raw).findings.single().disposition)
        }
        val incomplete = Provider { _ , _ -> ProviderResult("synthetic-test", "incomplete", reasons = listOf("missing-proof")) }
        val rewriting = ResultPolicy { _, i, _ -> passed(i) }
        KernelService.open(temp.resolve("bad-policy"), incomplete, testConfig, SyntheticFence(input), resultPolicy = rewriting).use { service ->
            val client = service.registerClient(binding("bad-policy"))
            val review = service.submit(client.client, request("1", input))
            val status = await(service, client, review.reviewId)
            assertEquals("incomplete", status.state)
            assertTrue("result_policy_changed_protected_evidence" in status.reasons)
        }
    }

    @Test fun unavailableAuthorityAndIncompleteScopeNeverReportAnAllowedGate() {
        val input = fixtureInput("unknown")
        KernelService.open(temp.resolve("unavailable"), Provider { i, _ -> passed(i) }, testConfig).use { service ->
            val client = service.registerClient(binding("unknown"))
            val review = service.submit(client.client, request("1", input))
            await(service, client, review.reviewId); insertAll(service, client)
            assertFalse(service.prepareGate(client.client).ready)
        }
        val scoped = input.copy(snapshot = input.snapshot.copy(unknownInputs = listOf("generated model unavailable")))
        KernelService.open(temp.resolve("scope"), Provider { i, _ -> passed(i) }, testConfig, SyntheticFence(scoped)).use { service ->
            val client = service.registerClient(binding("scope"))
            val review = service.submit(client.client, request("1", scoped))
            await(service, client, review.reviewId); insertAll(service, client)
            assertTrue("source_scope_incomplete" in service.prepareGate(client.client).reasons)
        }
    }

    @Test fun progressiveEventsAreDurableDeduplicatedAndReplayedToLateSubscribersWithoutCompletion() {
        val input = fixtureInput("progress")
        val progressed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val provider = object : ProgressiveAnalysisProvider {
            override val id = "synthetic-test"
            override val supportedRuleIds = setOf(rule)
            override fun analyzeProgressive(input: AnalysisInput, cancellation: CancellationToken, onProgress: (ProviderResult) -> Unit): ProviderResult {
                val partial = passed(input, listOf(finding(input))).copy(status = "partial")
                onProgress(partial); onProgress(partial)
                progressed.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS))
                return passed(input, listOf(finding(input)))
            }
        }
        KernelService.open(temp.resolve("ledger"), provider, testConfig, SyntheticFence(input)).use { service ->
            val a = service.registerClient(binding("a"))
            val b = service.registerClient(binding("b"))
            val ar = service.submit(a.client, request("1", input))
            assertTrue(progressed.await(5, TimeUnit.SECONDS))
            assertEquals("running", service.status(a.client, ar.reviewId).state)
            assertEquals(1, allEvents(service, a).count { it.kind == "progress" })
            val br = service.submit(b.client, request("1", input))
            assertEquals(1, allEvents(service, b).count { it.kind == "progress" })
            assertFalse(service.prepareGate(a.client).ready)
            release.countDown()
            await(service, a, ar.reviewId); await(service, b, br.reviewId)
            assertEquals(1, allEvents(service, b).count { it.kind == "result" })
        }
    }
    @Test fun killedWorkerProcessRecoversEveryAcceptedRequestFromItsDurableArchive() {
        val input = fixtureInput("crash", "val durable = 19\n")
        val database = temp.resolve("crash-ledger")
        val provider = Provider { i, _ -> passed(i) }
        lateinit var client: ClientRegistration
        lateinit var review: ReviewStatus
        KernelService.open(database, provider, testConfig.copy(workerCount = 0)).use { service ->
            client = service.registerClient(binding("crash"))
            review = service.submit(client.client, request("crash", input))
        }
        val configFile = temp.resolve("crash-config.json")
        Files.writeString(configFile, canonical(testConfig.copy(workerCount = 1)))
        val started = temp.resolve("child-started")
        val log = temp.resolve("child.log")
        val javaExecutable = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val classpath = buildSet<String> {
            System.getProperty("java.class.path").split(java.io.File.pathSeparator).filter { it.isNotBlank() }.forEach(::add)
            var loader: ClassLoader? = KernelCrashChild::class.java.classLoader
            while (loader != null) {
                if (loader is java.net.URLClassLoader) loader.urLs.filter { it.protocol == "file" }.forEach { add(Path.of(it.toURI()).toString()) }
                loader = loader.parent
            }
            listOf(KernelCrashChild::class.java, KernelService::class.java, AnalysisInput::class.java,
                kotlin.Unit::class.java, kotlinx.serialization.KSerializer::class.java, kotlinx.serialization.json.Json::class.java,
                Class.forName("org.sqlite.JDBC")).forEach { add(Path.of(it.protectionDomain.codeSource.location.toURI()).toString()) }
        }.joinToString(java.io.File.pathSeparator)
        val child = ProcessBuilder(javaExecutable.toString(), "-cp", classpath, KernelCrashChild::class.java.name,
            database.toString(), configFile.toString(), started.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!Files.exists(started) && child.isAlive && System.nanoTime() < until) Thread.sleep(10)
            assertTrue(Files.exists(started), "Child did not enter durable work: " + Files.readString(log).take(2000))
            child.destroyForcibly()
            assertTrue(child.waitFor(10, TimeUnit.SECONDS))
            Files.delete(Path.of(input.materializedRoot).resolve("Main.kt"))
            KernelService.open(database, provider, testConfig).use { service ->
                assertEquals("complete", await(service, client, review.reviewId).state)
                val events = allEvents(service, client)
                assertEquals(1, events.count { it.kind == "accepted" })
                assertEquals(1, events.count { it.kind == "result" })
                assertTrue(events.any { it.kind == "recovered" })
            }
        } finally { if (child.isAlive) { child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS) } }
    }

    @Test fun fencedOldAttemptCannotPublishOverAReplacementResult() {
        // This deliberately injects a newer token in SQLite. Production only reclaims confirmed
        // dead processes; this fixture specifically proves the stale publication guard itself.
        val input = fixtureInput("fenced")
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstReturned = CountDownLatch(1)
        val counter = AtomicInteger()
        val provider = Provider { i, _ ->
            if (counter.incrementAndGet() == 1) {
                firstStarted.countDown(); assertTrue(releaseFirst.await(10, TimeUnit.SECONDS))
                firstReturned.countDown(); passed(i, listOf(finding(i)))
            } else passed(i)
        }
        val config = testConfig.copy(workerCount = 1, globalParallelism = 1)
        val database = temp.resolve("fenced-ledger")
        KernelService.open(database, provider, config).use { first ->
            val client = first.registerClient(binding("fenced"))
            val review = first.submit(client.client, request("1", input))
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
            first.ledger.transaction { c ->
                c.execute("UPDATE work SET state='queued',owner_instance=NULL,attempt=attempt+1 WHERE state='running'")
            }
            KernelService.open(database, provider, config).use { replacement ->
                assertTrue(await(replacement, client, review.reviewId).result!!.findings.isEmpty())
                releaseFirst.countDown()
                assertTrue(firstReturned.await(5, TimeUnit.SECONDS))
                Thread.sleep(100)
                assertTrue(replacement.status(client.client, review.reviewId).result!!.findings.isEmpty())
                assertEquals(1, allEvents(replacement, client).count { it.kind == "result" })
            }
        }
    }

    @Test fun unknownGuardedActionOutcomeCannotBeReplayedEvenWithANewCutOrAfterRestart() {
        val input = fixtureInput("action-failure")
        val provider = Provider { i, _ -> passed(i) }
        val database = temp.resolve("action-failure-ledger")
        val fence = SyntheticFence(input)
        lateinit var client: ClientRegistration
        lateinit var cut: GateCut
        var sideEffects = 0
        KernelService.open(database, provider, testConfig, fence).use { service ->
            client = service.registerClient(binding("action"))
            val review = service.submit(client.client, request("1", input))
            await(service, client, review.reviewId); insertAll(service, client)
            cut = service.prepareGate(client.client)
            val result = service.completeGate(client.client, cut.cutId) { sideEffects++; throw IllegalStateException("synthetic post-effect failure") }
            assertFalse(result.allowed)
            assertEquals(listOf("guarded_action_outcome_unknown"), result.reasons)
            assertFalse(service.completeGate(client.client, cut.cutId) { sideEffects++ }.allowed)
            val another = service.prepareGate(client.client)
            assertFalse(another.ready)
            assertFalse(service.completeGate(client.client, another.cutId) { sideEffects++ }.allowed)
            assertCode("lineage_finalized") { service.submit(client.client, request("2", input)) }
        }
        KernelService.open(database, provider, testConfig, fence).use { restarted ->
            assertFalse(restarted.completeGate(client.client, cut.cutId) { sideEffects++ }.allowed)
            assertFalse(restarted.prepareGate(client.client).ready)
        }
        assertEquals(1, sideEffects)
    }

    @Test fun trustedEnrollmentIntentIsIdempotentButCannotReplaceCredentialsOrGeneration() {
        val provider = Provider { i, _ -> passed(i) }
        val database = temp.resolve("enrollment-ledger")
        lateinit var registration: ClientRegistration
        KernelService.open(database, provider, testConfig.copy(workerCount = 0)).use { service ->
            registration = service.registerClient(binding("enrollment"))
            assertEquals(registration, service.registerClient(registration))
            assertFalse(registration.toString().contains(registration.adapter.token))
            assertCode("client_exists") { service.registerClient(registration.copy(binding = registration.binding.copy(generation = 2))) }
            assertCode("client_exists") { service.registerClient(registration.copy(adapter = registration.adapter.copy(token = "z".repeat(43)))) }
        }
        KernelService.open(database, provider, testConfig.copy(workerCount = 0)).use { service ->
            assertEquals(registration, service.registerClient(registration))
        }
    }

    @Test fun aLiveProviderIgnoringCancellationRetainsItsGlobalSlotPastDeadlineAndLease() {
        val first = fixtureInput("hung-first")
        val second = fixtureInput("waiting-second")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val count = AtomicInteger()
        val provider = Provider { i, cancellation ->
            if (count.incrementAndGet() == 1) {
                entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS))
                assertTrue(cancellation.isCancelled(), "The soft deadline must reach the provider")
            }
            passed(i)
        }
        val config = testConfig.copy(workerCount = 1, globalParallelism = 1, analysisTimeoutMillis = 500)
        val database = temp.resolve("hung-ledger")
        KernelService.open(database, provider, config).use { a ->
            KernelService.open(database, provider, config).use { b ->
                val client = a.registerClient(binding("hung"))
                val firstReview = a.submit(client.client, request("first", first))
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val secondReview = b.submit(client.client, request("second", second))
                try {
                    Thread.sleep(700)
                    assertEquals(1, count.get(), "Do not oversubscribe by reclaiming a merely expired live worker")
                    assertEquals("queued", b.status(client.client, secondReview.reviewId).state)
                } finally { release.countDown() }
                assertEquals("incomplete", await(a, client, firstReview.reviewId).state)
                assertEquals("complete", await(b, client, secondReview.reviewId).state)
            }
        }
    }

    @Test @Timeout(value = 90, unit = TimeUnit.SECONDS)
    fun expiredBusyWorktreeCannotHeadOfLineBlockFairPeerClientsOrBorrowTheirFinalDelivery() {
        // Real threads/SQLite, but one-rule scalar provider/source/delivery authorities are synthetic.
        // An expired live claim keeps ITS slot; it must not block another eligible worktree's slot.
        // This finite opportunity oracle does not promise progress for the saturated worktree or CPU fairness.
        class Ticket(val label: String, val input: AnalysisInput) {
            val release = CountDownLatch(1)
            val calls = AtomicInteger()
        }
        data class Review(val clientIndex: Int, val plannedSequence: Long, val ordinal: Int,
            val ticket: Ticket, val status: ReviewStatus, var cancelled: Boolean = false)
        data class ClaimIdentity(val workKey: String, val owner: String, val attempt: Long, val deadline: Long)
        val tickets = ConcurrentHashMap<String, Ticket>()
        fun ticket(label: String, tree: String, parent: String? = null): Ticket {
            val value = Ticket(label, fixtureInput("expired-fair-$label", worktree = tree, parent = parent))
            assertNull(tickets.putIfAbsent(value.input.snapshot.snapshotId, value))
            return value
        }
        val stalledFirst = ticket("stalled-first", "stalled")
        val stalledNext = ticket("stalled-next", "stalled", stalledFirst.input.snapshot.snapshotId)
        val initialShared = ticket("shared-initial", "healthy")
        val stalledFence = SyntheticFence(stalledNext.input)
        val healthyFence = SyntheticFence(initialShared.input)
        val fences = object : SnapshotGate {
            override fun <T> withCurrentSnapshot(input: AnalysisInput, action: (SnapshotCheck) -> T): T =
                when (input.snapshot.worktreeId) {
                    "stalled" -> stalledFence.withCurrentSnapshot(input, action)
                    "healthy" -> healthyFence.withCurrentSnapshot(input, action)
                    else -> error("Unexpected synthetic worktree")
                }
        }
        val stalledEntered = LinkedBlockingQueue<CancellationToken>(1)
        val healthyEntered = LinkedBlockingQueue<Pair<Ticket, CancellationToken>>(16)
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val activeByTree = mapOf("stalled" to AtomicInteger(), "healthy" to AtomicInteger())
        val maximumByTree = mapOf("stalled" to AtomicInteger(), "healthy" to AtomicInteger())
        val provider = Provider { effective, cancellation ->
            val current = tickets.getValue(effective.snapshot.snapshotId)
            assertEquals(1, current.calls.incrementAndGet(), "A still-live claim must never be replaced")
            val total = active.incrementAndGet()
            val treeTotal = activeByTree.getValue(effective.snapshot.worktreeId).incrementAndGet()
            maximumActive.accumulateAndGet(total, ::maxOf)
            maximumByTree.getValue(effective.snapshot.worktreeId).accumulateAndGet(treeTotal, ::maxOf)
            try {
                assertTrue(total <= 2 && treeTotal <= 1, "Global/per-worktree reservations bound actual provider entries")
                assertEquals(current.input.snapshot, effective.snapshot)
                assertEquals(current.input.context, effective.context)
                assertFalse(cancellation.isCancelled())
                when (current) {
                    stalledFirst -> {
                        assertTrue(stalledEntered.offer(cancellation))
                        assertTrue(current.release.await(60, TimeUnit.SECONDS), "Bounded stalled-provider release")
                        assertTrue(cancellation.isCancelled(), "Returning clean bytes cannot erase the elapsed deadline")
                    }
                    stalledNext -> assertEquals(0L, stalledFirst.release.count, "Same-worktree work must wait for the live owner")
                    else -> {
                        assertTrue(healthyEntered.offer(current to cancellation))
                        assertTrue(current.release.await(15, TimeUnit.SECONDS), "Bounded peer-provider release: ${current.label}")
                        assertFalse(cancellation.isCancelled(), "A's cancellation or another worktree's expiry cannot revoke B's work")
                    }
                }
                passed(effective)
            } finally {
                activeByTree.getValue(effective.snapshot.worktreeId).decrementAndGet()
                active.decrementAndGet()
            }
        }
        fun releaseAll() { tickets.values.forEach { it.release.countDown() } }
        // Timeout/caps are durable policy and remain identical; only workerCount changes between instances.
        val config = testConfig.copy(workerCount = 0, globalParallelism = 2, perWorktreeParallelism = 1,
            analysisTimeoutMillis = 5_000)
        val database = temp.resolve("expired-live-fair-ledger")
        val actionCalls = List(3) { AtomicInteger() } // healthy A, healthy B, stalled client
        KernelService.open(database, provider, config, fences).use { control ->
            val stalled = control.registerClient(binding("stalled", "stalled"))
            val peers = listOf("a", "b").map { control.registerClient(binding(it, "healthy")) }
            val firstReview = control.submit(stalled.client, request(stalledFirst.label, stalledFirst.input))
            val nextReview = control.submit(stalled.client, request(stalledNext.label, stalledNext.input))
            assertEquals(listOf("queued", "queued"), listOf(firstReview.state, nextReview.state))
            val reviews = mutableListOf<Review>()
            val plannedSequences = LongArray(2)
            var healthyParent = initialShared.input.snapshot.snapshotId
            fun admit(current: Ticket, recipients: List<Int>): Ticket {
                recipients.forEach { index ->
                    val sequence = ++plannedSequences[index]
                    val ordinal = reviews.size
                    val status = control.submit(peers[index].client, request(current.label, current.input))
                    assertEquals(sequence, status.sequence)
                    assertEquals(peers[index].binding.clientId, status.clientId)
                    assertEquals(current.input.snapshot.snapshotId, status.sourceSnapshotId)
                    assertNull(status.result, "Each accepted ticket is new work, never a completed cache hit")
                    reviews += Review(index, sequence, ordinal, current, status)
                }
                return current
            }
            fun add(label: String, recipients: List<Int>): Ticket {
                val current = ticket(label, "healthy", healthyParent)
                healthyParent = current.input.snapshot.snapshotId
                return admit(current, recipients)
            }
            admit(initialShared, listOf(0, 1))
            add("cold-initial", listOf(1))
            repeat(2) { add("hot-initial-$it", listOf(0)) }
            fun liveClaim(reviewId: String): ClaimIdentity = control.ledger.read { c ->
                assertNotNull(c.one("""SELECT w.state,w.work_key,w.owner_instance,w.attempt,w.deadline FROM work w
                    JOIN reviews r ON r.work_key=w.work_key WHERE r.review_id=?""", reviewId) {
                    assertEquals("running", it.getString(1))
                    ClaimIdentity(it.getString(2), it.getString(3), it.getLong(4), it.getLong(5))
                })
            }
            KernelService.open(database, provider, config.copy(workerCount = 1), fences).use { stalledWorker ->
                try {
                    val stalledToken = assertNotNull(stalledEntered.poll(10, TimeUnit.SECONDS), "The first admitted worktree must own the first claim")
                    val originalClaim = liveClaim(firstReview.reviewId)
                    assertEquals(stalledWorker.instanceId, originalClaim.owner)
                    assertEquals(1L, originalClaim.attempt)
                    assertTrue(originalClaim.deadline > 0)
                    // Observe the real monotonic deadline token, not a fixed sleep or a persisted wall-clock/lease guess.
                    // The worker stays open throughout; its heartbeat may keep renewing lease_until.
                    val expiryWait = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                    while (!stalledToken.isCancelled()) {
                        assertTrue(System.nanoTime() < expiryWait, "The ordinary analysis deadline must reach this still-live provider")
                        Thread.sleep(5)
                    }
                    fun assertStalledReservation() {
                        assertTrue(stalledToken.isCancelled())
                        assertFalse(stalledWorker.isStopped())
                        assertEquals(originalClaim, liveClaim(firstReview.reviewId), "No deadline renewal, attempt replacement or slot reclamation")
                        assertEquals("running", control.status(stalled.client, firstReview.reviewId).state)
                        assertNull(control.status(stalled.client, firstReview.reviewId).result)
                        assertEquals("queued", control.status(stalled.client, nextReview.reviewId).state)
                        assertEquals(0, stalledNext.calls.get())
                        assertEquals(1L, control.ledger.read { c -> c.count("SELECT COUNT(*) FROM instances WHERE instance_id=? AND closed=0", stalledWorker.instanceId) })
                    }
                    assertStalledReservation()
                    // Only now admit a second worker: no healthy claim spends its timeout waiting for initial expiry.
                    KernelService.open(database, provider, config.copy(workerCount = 1), fences).use { peerWorker ->
                        try {
                            val served = mutableSetOf<Ticket>()
                            val expectedAges = mutableMapOf(0 to 0L, 1 to 0L)
                            var dispatches = 1L // The first, still-running stalled claim already spent one opportunity.
                            fun nextHealthyClaim(): Pair<Ticket, CancellationToken> {
                                // Model only eligible healthy subscriptions, using planned sequence/admission order and
                                // previously EXPECTED sponsor ages. Never infer the next winner from production SQL.
                                val sponsor = assertNotNull(reviews.filter { it.ticket !in served }.minWithOrNull(
                                    compareBy<Review>({ expectedAges.getValue(it.clientIndex) }, { it.plannedSequence }, { it.ordinal })))
                                val entered = assertNotNull(healthyEntered.poll(10, TimeUnit.SECONDS), "A saturated older worktree cannot head-of-line block an eligible peer")
                                assertSame(sponsor.ticket, entered.first, "The least-served eligible client selects its own oldest work")
                                assertTrue(served.add(entered.first))
                                expectedAges[sponsor.clientIndex] = ++dispatches
                                val claim = liveClaim(reviews.first { it.ticket === entered.first }.status.reviewId)
                                assertEquals(peerWorker.instanceId, claim.owner)
                                assertEquals(1L, claim.attempt)
                                assertFalse(entered.second.isCancelled())
                                assertStalledReservation()
                                assertEquals(2, active.get())
                                assertEquals(mapOf("stalled" to 1, "healthy" to 1), activeByTree.mapValues { it.value.get() })
                                control.ledger.read { c ->
                                    assertEquals(2L, c.count("SELECT COUNT(*) FROM work WHERE state='running'"))
                                    assertEquals(dispatches, c.count("SELECT CAST(value AS INTEGER) FROM metadata WHERE key='dispatch_counter'"))
                                    assertEquals(mapOf("stalled" to 1L, "a" to expectedAges.getValue(0), "b" to expectedAges.getValue(1)),
                                        c.query("SELECT client_id,last_served FROM fair_clients") { it.getString(1) to it.getLong(2) }.toMap(),
                                        "Coalescing spends only the sponsor's age, not the incidental subscriber's turn")
                                    assertEquals(mapOf("stalled" to 1L, "a" to dispatches, "b" to dispatches),
                                        c.query("SELECT f.client_id,q.last_served FROM fair_clients f JOIN fair_queues q ON q.fair_key=f.fair_key") {
                                            it.getString(1) to it.getLong(2)
                                        }.toMap())
                                }
                                return entered
                            }
                            fun releaseAndAwait(current: Ticket) {
                                current.release.countDown()
                                reviews.filter { it.ticket === current }.forEach { await(control, peers[it.clientIndex], it.status.reviewId) }
                            }
                            var finalTicket: Ticket? = null
                            repeat(13) { dispatch ->
                                val (current, token) = nextHealthyClaim()
                                if (dispatch == 0) {
                                    assertSame(initialShared, current)
                                    val own = reviews.single { it.ticket === current && it.clientIndex == 0 }
                                    val other = reviews.single { it.ticket === current && it.clientIndex == 1 }
                                    assertCode("review_not_found") { control.cancel(peers[0].client, other.status.reviewId) }
                                    val cancelled = control.cancel(peers[0].client, own.status.reviewId)
                                    assertEquals("cancelled", cancelled.state)
                                    assertEquals(cancelled, control.cancel(peers[0].client, own.status.reviewId))
                                    own.cancelled = true
                                    val peer = control.status(peers[1].client, other.status.reviewId)
                                    assertEquals("running", peer.state)
                                    assertFalse(peer.cancelled)
                                    assertFalse(token.isCancelled())
                                }
                                if (dispatch < 3) {
                                    add("hot-$dispatch", listOf(0))
                                    add("cold-$dispatch", listOf(1))
                                    add("shared-$dispatch", listOf(0, 1))
                                }
                                if (dispatch == 12) {
                                    // Parked predecessor prevents the final A/B pair racing its own dispatch sponsor.
                                    finalTicket = add("final-current", listOf(0, 1))
                                    healthyFence.update(assertNotNull(finalTicket).input)
                                }
                                releaseAndAwait(current)
                            }
                            val finalCurrent = assertNotNull(finalTicket)
                            assertSame(finalCurrent, nextHealthyClaim().first)
                            releaseAndAwait(finalCurrent)
                            assertEquals(14, served.size)
                            assertEquals(19, reviews.size)
                            assertEquals(listOf(10L, 9L), plannedSequences.toList())
                            assertEquals(15L, dispatches)
                            assertEquals(15, provider.calls.get())
                            assertStalledReservation()
                            val peerEvents = peers.map { allEvents(control, it) }
                            assertEquals(listOf(21, 18), peerEvents.map { it.size })
                            peers.indices.forEach { index ->
                                val events = peerEvents[index]
                                assertEquals((1L..events.size.toLong()).toList(), events.map { it.sequence })
                                assertTrue(events.all { it.clientId == peers[index].binding.clientId && it.generation == 1L && it.deliveryRequired })
                                reviews.filter { it.clientIndex == index }.forEach { review ->
                                    val status = control.status(peers[index].client, review.status.reviewId)
                                    assertEquals(if (review.cancelled) "cancelled" else "complete", status.state)
                                    assertEquals(review.cancelled, status.cancelled)
                                    assertEquals("complete", assertNotNull(status.result).status)
                                    assertEquals(if (review.cancelled) listOf("client_cancelled_obligation_retained") else emptyList(), status.reasons)
                                    val owned = events.filter { it.reviewId == status.reviewId }
                                    assertEquals(if (review.cancelled) listOf("accepted", "cancelled", "result") else listOf("accepted", "result"), owned.map { it.kind })
                                    assertEquals(status, ContractJson.decodeFromString<ReviewStatus>(owned.last().payload))
                                }
                            }
                            assertTrue(peerEvents[0].map { it.eventId }.toSet().intersect(peerEvents[1].map { it.eventId }.toSet()).isEmpty())
                            val foreign = peerEvents[1].last()
                            val foreignDigest = Digests.sha256("synthetic context item:${foreign.payload}")
                            assertCode("event_not_found") { control.acknowledge(peers[0].adapter, listOf(foreign.eventId),
                                ContextReceipt("wrong-client-ack", foreignDigest, "wrong-client-item", Instant.EPOCH.toString())) }
                            assertCode("event_not_found") { control.observe(peers[0].adapter, listOf(foreign.eventId),
                                ObservationReceipt("wrong-client-observe", "wrong-client-request", listOf(foreignDigest))) }
                            assertEquals(peerEvents[0], insertAll(control, peers[0]))
                            assertEquals(peerEvents[1], insertAll(control, peers[1], observe = false))
                            val stalledAccepted = insertAll(control, stalled)
                            assertEquals(listOf("accepted", "accepted"), stalledAccepted.map { it.kind })
                            fun assertReceipts(acked: List<ReviewEvent>, observed: List<ReviewEvent>) = control.ledger.read { c ->
                                assertEquals(acked.map { it.eventId to it.clientId }.sortedBy { it.first },
                                    c.query("SELECT event_id,client_id FROM acknowledgements ORDER BY event_id") { it.getString(1) to it.getString(2) })
                                assertEquals(observed.map { it.eventId to it.clientId }.sortedBy { it.first },
                                    c.query("SELECT event_id,client_id FROM observations ORDER BY event_id") { it.getString(1) to it.getString(2) })
                                assertEquals((acked.size + observed.size).toLong(), c.count("SELECT COUNT(*) FROM receipts"))
                            }
                            fun assertFrontier(cut: GateCut, client: ClientRegistration, events: List<ReviewEvent>, current: AnalysisInput) {
                                assertEquals(client.binding.clientId, cut.clientId)
                                assertEquals(client.binding.lineageId, cut.lineageId)
                                assertEquals(client.binding.generation, cut.generation)
                                assertEquals(current.snapshot.snapshotId, cut.sourceSnapshotId)
                                assertEquals(events.last().sequence, cut.eventFrontier)
                                assertEquals(events.map { it.eventId }, cut.requiredEventIds)
                            }
                            assertReceipts(stalledAccepted + peerEvents.flatten(), stalledAccepted + peerEvents[0])
                            val missingB = control.prepareGate(peers[1].client)
                            assertFrontier(missingB, peers[1], peerEvents[1], finalCurrent.input)
                            assertFalse(missingB.ready)
                            assertEquals(peerEvents[1].map { "model_observation_missing:${it.eventId}" }.toSet(), missingB.reasons.toSet(),
                                "A's observations and B's own ACKs cannot discharge B's independent observation obligations")
                            assertFalse(control.completeGate(peers[1].client, missingB.cutId) { actionCalls[1].incrementAndGet() }.allowed)
                            assertEquals(peerEvents[1], insertAll(control, peers[1])) // Exact ACK retries, then B's own observations.
                            assertReceipts(stalledAccepted + peerEvents.flatten(), stalledAccepted + peerEvents.flatten())
                            val readyB = control.prepareGate(peers[1].client)
                            assertFrontier(readyB, peers[1], peerEvents[1], finalCurrent.input)
                            assertTrue(readyB.ready, readyB.reasons.toString())
                            // Scalar callbacks run inside DB/source fences: no kernel re-entry, I/O or wait here.
                            val completedB = control.completeGate(peers[1].client, readyB.cutId) { actionCalls[1].incrementAndGet() }
                            assertTrue(completedB.allowed && completedB.actionPerformed)
                            val cancelledA = control.prepareGate(peers[0].client)
                            assertFrontier(cancelledA, peers[0], peerEvents[0], finalCurrent.input)
                            assertEquals(listOf("review_cancelled:${reviews.single { it.cancelled }.status.reviewId}"), cancelledA.reasons)
                            assertFalse(cancelledA.ready)
                            assertFalse(control.completeGate(peers[0].client, cancelledA.cutId) { actionCalls[0].incrementAndGet() }.allowed)
                            val pendingStalled = control.prepareGate(stalled.client)
                            assertFrontier(pendingStalled, stalled, stalledAccepted, stalledNext.input)
                            assertFalse(pendingStalled.ready)
                            assertEquals(setOf("review_pending:${firstReview.reviewId}", "review_pending:${nextReview.reviewId}"),
                                pendingStalled.reasons.filter { it.startsWith("review_pending:") }.toSet())
                            assertTrue(pendingStalled.reasons.none { it.startsWith("insertion_missing:") || it.startsWith("model_observation_missing:") })
                            assertFalse(control.completeGate(stalled.client, pendingStalled.cutId) { actionCalls[2].incrementAndGet() }.allowed)
                            assertEquals(listOf(0, 1, 0), actionCalls.map { it.get() })
                            assertEquals(mapOf("a" to "open", "b" to "complete", "stalled" to "open"), control.ledger.read { c ->
                                c.query("SELECT client_id,gate_state FROM clients") { it.getString(1) to it.getString(2) }.toMap()
                            })
                            assertStalledReservation()
                            assertEquals(1, active.get())
                            assertEquals(peerEvents, peers.map { allEvents(control, it) }, "Delivery/finalization does not extend the finite work frontier")
                            stalledFirst.release.countDown()
                            val late = await(control, stalled, firstReview.reviewId)
                            assertEquals("incomplete", late.state)
                            assertEquals("incomplete", assertNotNull(late.result).status)
                            assertTrue("analysis_deadline_exceeded" in late.reasons)
                            assertEquals("complete", await(control, stalled, nextReview.reviewId).state)
                            val stalledEvents = insertAll(control, stalled)
                            assertEquals(4, stalledEvents.size)
                            listOf(firstReview, nextReview).forEach { review ->
                                assertEquals(listOf("accepted", "result"), stalledEvents.filter { it.reviewId == review.reviewId }.map { it.kind })
                            }
                            assertReceipts(stalledEvents + peerEvents.flatten(), stalledEvents + peerEvents.flatten())
                            val incompleteStalled = control.prepareGate(stalled.client)
                            assertFrontier(incompleteStalled, stalled, stalledEvents, stalledNext.input)
                            assertFalse(incompleteStalled.ready)
                            assertEquals(listOf("review_incomplete:${firstReview.reviewId}"), incompleteStalled.reasons,
                                "Neither the eligible peer's final action nor a clean current descendant erases this historical timeout")
                            assertFalse(control.completeGate(stalled.client, incompleteStalled.cutId) { actionCalls[2].incrementAndGet() }.allowed)
                            assertEquals(listOf(0, 1, 0), actionCalls.map { it.get() })
                            assertEquals(16, tickets.size)
                            assertEquals(16, provider.calls.get())
                            assertTrue(tickets.values.all { it.calls.get() == 1 })
                            assertEquals(0, active.get())
                            assertEquals(2, maximumActive.get())
                            assertEquals(mapOf("stalled" to 1, "healthy" to 1), maximumByTree.mapValues { it.value.get() })
                            control.ledger.read { c ->
                                assertEquals(16L, c.count("SELECT CAST(value AS INTEGER) FROM metadata WHERE key='dispatch_counter'"))
                                assertEquals(16L, c.count("SELECT COUNT(*) FROM work WHERE state='complete' AND attempt=1"))
                                assertEquals(0L, c.count("SELECT COUNT(*) FROM work WHERE state<>'complete'"))
                                assertEquals(21L, c.count("SELECT COUNT(*) FROM reviews"))
                                assertEquals(43L, c.count("SELECT COUNT(*) FROM events"))
                            }
                        } finally { releaseAll() } // Before peerWorker.close(), including every failing assertion path.
                    }
                } finally { releaseAll() } // Also before stalledWorker.close() if peer startup/expiry observation fails.
            }
        }
    }

    @Test fun byteBoundedEventPagesNeverSkipAnEventAndNewAcceptedWorkInvalidatesCuts() {
        val input = fixtureInput("paged")
        KernelService.open(temp.resolve("paged-ledger"), Provider { i, _ -> passed(i) }, testConfig.copy(maxEventPageBytes = 64), SyntheticFence(input)).use { service ->
            val client = service.registerClient(binding("paged"))
            val first = service.submit(client.client, request("1", input))
            await(service, client, first.reviewId)
            val page = service.events(client.client, limit = 100)
            assertEquals(1, page.events.size)
            assertTrue(page.hasMore)
            val next = service.events(client.client, page.cursor, 100)
            assertEquals(1, next.events.size)
            assertEquals(2L, next.events.single().sequence)
            insertAll(service, client)
            val cut = service.prepareGate(client.client)
            service.submit(client.client, request("2", input))
            insertAll(service, client)
            assertTrue("lineage_epoch_changed" in service.completeGate(client.client, cut.cutId).reasons)
            assertTrue(service.prepareGate(client.client).ready)
        }
    }

}

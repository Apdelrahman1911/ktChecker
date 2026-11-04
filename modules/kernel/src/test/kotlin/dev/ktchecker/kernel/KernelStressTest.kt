package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.ceil
import kotlin.random.Random
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * Real SQLite/WAL, archives, queues, outboxes and concurrent callers; synthetic provider/fences.
 * This is a bounded scheduling stress fixture, NOT a FIR, production-host or CPU-time benchmark.
 * Timing observations are reported, never used as tight machine-dependent correctness thresholds.
 */
class KernelStressTest {
    @TempDir lateinit var temp: Path
    private val rule = "test/stress-no-defect"
    private val providerId = "synthetic-stress"
    private val config = KernelConfig(workerCount = 0, globalParallelism = 4, perWorktreeParallelism = 2,
        maxQueuedWork = 256, maxPendingPerClient = 128, heartbeatMillis = 100, leaseMillis = 1_000,
        pollMillis = 10, analysisTimeoutMillis = 120_000, maxEventPageSize = 64, maxEventPageBytes = 16_384)

    private data class Patch(val index: Int, val input: AnalysisInput, val primary: ClientRegistration, val mirror: ClientRegistration)
    private data class Accepted(
        val client: ClientRegistration, val request: SubmitRequest, val status: ReviewStatus,
        val submittedNanos: Long, val acceptedNanos: Long,
    )
    private data class Dispatch(
        val sequence: Long, val tree: String, val sponsor: String?, val minTreeAge: Long, val treeAge: Long,
        val minClientAge: Long, val clientAge: Long?, val oldestReview: Long?, val selectedReview: Long?,
        val running: Int, val treeRunning: Int,
    )

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    fun sustainedConcurrentHundredDistinctPatchesPreserveFairnessCancellationAndEveryObligation() {
        // A failed rerun must not leave a previous run's success artifact looking current.
        Files.deleteIfExists(reportPath())
        val database = temp.resolve("stress-ledger")
        val providerRelease = CountDownLatch(1)
        val firstFiftyAccepted = CountDownLatch(50)
        val producerStart = CountDownLatch(1)
        val producerReady = CountDownLatch(12)
        val accepted = ConcurrentHashMap<String, Accepted>()
        val terminalObserved = ConcurrentHashMap<String, Long>()
        val providerStarted = ConcurrentHashMap<String, Long>()
        val providerDuration = ConcurrentHashMap<String, Long>()
        val providerCalls = ConcurrentHashMap<String, AtomicInteger>()
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val treeActive = ConcurrentHashMap<String, AtomicInteger>()
        val treeMaximum = ConcurrentHashMap<String, AtomicInteger>()
        val producerActive = AtomicInteger()
        val producerMaximum = AtomicInteger()
        val ingestionRetries = AtomicInteger()
        val cancelled = ConcurrentHashMap.newKeySet<String>()
        val currentByTree = ConcurrentHashMap<String, AnalysisInput>()
        val eventStreams = mutableMapOf<String, MutableList<ReviewEvent>>()
        val cursors = mutableMapOf<String, String>()
        val streamRunning = AtomicBoolean(true)
        val streamFailure = AtomicReference<Throwable?>()
        val pool = Executors.newFixedThreadPool(12)
        var collector: Thread? = null
        val services = mutableListOf<KernelService>()
        val clients = mutableListOf<ClientRegistration>()
        var stressStarted = 0L
        var stressFinished = 0L
        var dispatches = emptyList<Dispatch>()
        var halfBacklog = 0L

        val fence = object : SnapshotGate {
            override fun <T> withCurrentSnapshot(input: AnalysisInput, action: (SnapshotCheck) -> T): T {
                // Immutable synthetic current heads are established before the workload starts.
                val current = currentByTree.getValue(input.snapshot.worktreeId)
                return action(SnapshotCheck(input.snapshot.snapshotId == current.snapshot.snapshotId,
                    current.snapshot.snapshotId, 1, current.context.configurationDigest, current.context.modelDigest,
                    current.context.dependencyDigest, current.context.toolchainDigest, "synthetic-fixed-current-head", authoritative = true))
            }
        }
        val provider = object : ProgressiveAnalysisProvider {
            override val id = providerId
            override val supportedRuleIds = setOf(rule)
            override fun analyzeProgressive(input: AnalysisInput, cancellation: CancellationToken, onProgress: (ProviderResult) -> Unit): ProviderResult {
                val key = input.snapshot.snapshotId
                providerCalls.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
                providerStarted[key] = System.nanoTime()
                val nowActive = active.incrementAndGet()
                maximumActive.accumulateAndGet(nowActive, ::maxOf)
                val perTree = treeActive.computeIfAbsent(input.snapshot.worktreeId) { AtomicInteger() }
                val nowTree = perTree.incrementAndGet()
                treeMaximum.computeIfAbsent(input.snapshot.worktreeId) { AtomicInteger() }.accumulateAndGet(nowTree, ::maxOf)
                try {
                    assertTrue(providerRelease.await(90, TimeUnit.SECONDS), "Bounded synthetic startup barrier timed out")
                    val began = System.nanoTime()
                    assertFalse(cancellation.isCancelled(), "Client cancellation cannot reach shared provider execution")
                    onProgress(ProviderResult(id, "partial"))
                    Thread.sleep(20) // Deliberately synthetic service time, not compiler execution.
                    assertFalse(cancellation.isCancelled(), "Client cancellation cannot reach shared provider execution")
                    val result = ProviderResult(id, "complete", coverage = input.requiredRuleIds.map { selected ->
                        Coverage(selected, input.context.scope, "passed", provenance = "executed",
                            certificateId = Digests.sha256("synthetic-stress:${input.analysisKey()}:$selected"),
                            implemented = true, tested = true, qualified = true)
                    })
                    providerDuration[key] = System.nanoTime() - began
                    return result
                } finally {
                    perTree.decrementAndGet()
                    active.decrementAndGet()
                }
            }
        }

        try {
            val submitter = KernelService.open(database, provider, config, fence).also(services::add)
            installDispatchAudit(submitter)
            val patches = buildList {
                var index = 0
                listOf("hot-a" to 64, "hot-b" to 20, "cold-a" to 8, "cold-b" to 8).forEach { (tree, count) ->
                    val group = (0..3).map { client -> submitter.registerClient(ClientBinding(
                        "stress-owner", "stress-workspace", tree, "$tree-client-$client", "$tree-lineage-$client")) }
                    clients += group
                    repeat(count) { local ->
                        val input = input(index, tree)
                        val primary = group[when (local % 8) { 6 -> 1; 7 -> 2; else -> 0 }]
                        add(Patch(index++, input, primary, group[3]))
                        currentByTree[tree] = input
                    }
                }
            }.shuffled(Random(17))
            assertEquals(100, patches.map { it.input.analysisKey() }.distinct().size,
                "This must not collapse into a benchmark of one cache hit")
            clients.forEach { eventStreams[it.binding.clientId] = mutableListOf() }
            // Three independent service instances, one ledger. More worker threads than durable slots.
            val firstWorker = KernelService.open(database, provider, config.copy(workerCount = 3), fence).also(services::add)
            val secondWorker = KernelService.open(database, provider, config.copy(workerCount = 3), fence).also(services::add)

            fun stream(client: ClientRegistration): Boolean {
                val id = client.binding.clientId
                val page = submitter.events(client.client, cursors[id], 17)
                val previous = eventStreams.getValue(id).lastOrNull()?.sequence ?: 0L
                assertEquals((previous + 1..previous + page.events.size).toList(), page.events.map { it.sequence })
                page.events.forEach { event ->
                    assertEquals(id, event.clientId)
                    assertEquals(Digests.sha256(event.payload), event.payloadDigest)
                }
                eventStreams.getValue(id).addAll(page.events)
                cursors[id] = page.cursor
                return page.hasMore
            }
            collector = thread(name = "kernel-stress-outbox-reader", isDaemon = true) {
                try {
                    var next = 0
                    while (streamRunning.get()) {
                        val visible = submitter.ledger.read { c -> c.query("""SELECT r.review_id FROM reviews r
                            JOIN work w ON w.work_key=r.work_key WHERE w.state='complete'""") { it.getString(1) } }
                        val observed = System.nanoTime()
                        visible.forEach { terminalObserved.putIfAbsent(it, observed) }
                        stream(clients[next++ % clients.size])
                        Thread.sleep(5)
                    }
                } catch (t: Throwable) { streamFailure.set(t) }
            }

            stressStarted = System.nanoTime()
            val futures = patches.map { patch -> pool.submit {
                producerReady.countDown()
                assertTrue(producerStart.await(30, TimeUnit.SECONDS))
                val n = producerActive.incrementAndGet()
                producerMaximum.accumulateAndGet(n, ::maxOf)
                try {
                    val service = if (patch.index % 2 == 0) firstWorker else secondWorker
                    val request = SubmitRequest("patch-${patch.index}", "idem-${patch.index}", patch.input)
                    val primary = submit(service, patch.primary, request, ingestionRetries)
                    accepted[primary.status.reviewId] = primary
                    val mirror = submit(submitter, patch.mirror, request, ingestionRetries)
                    accepted[mirror.status.reviewId] = mirror
                    if (patch.index % 5 == 0) {
                        service.cancel(patch.primary.client, primary.status.reviewId)
                        cancelled += primary.status.reviewId
                    }
                    firstFiftyAccepted.countDown()
                } finally { producerActive.decrementAndGet() }
            } }
            assertTrue(producerReady.await(15, TimeUnit.SECONDS))
            producerStart.countDown()
            assertTrue(firstFiftyAccepted.await(90, TimeUnit.SECONDS), "Concurrent admission did not reach its bounded fixture milestone")
            halfBacklog = submitter.ledger.read { c -> c.count("SELECT COUNT(*) FROM work WHERE state IN ('queued','running')") }
            assertTrue(halfBacklog >= 50, "Fifty distinct accepted patches must coexist, not be replaced latest-wins")
            // Providers now drain while the remaining concurrent producers, cancellations and
            // cursor reads continue. The first fifty are deliberately held for a known backlog.
            providerRelease.countDown()
            futures.forEach { it.get(90, TimeUnit.SECONDS) }
            assertEquals(200, accepted.size)
            assertEquals(20, cancelled.size)
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
            while (accepted.keys.any { !terminalObserved.containsKey(it) } && System.nanoTime() < until) {
                streamFailure.get()?.let { throw AssertionError("Concurrent outbox reader failed", it) }
                Thread.sleep(10)
            }
            assertTrue(accepted.keys.all { terminalObserved.containsKey(it) }, "Every primary and mirrored subscription must finish")
            stressFinished = System.nanoTime()
            assertTrue(producerMaximum.get() > 1)
            assertEquals(100, providerCalls.size)
            assertTrue(providerCalls.values.all { it.get() == 1 }, "Each full-identical pair shares one execution only")
            assertTrue(maximumActive.get() in 2..config.globalParallelism)
            assertTrue(treeMaximum.values.all { it.get() in 1..config.perWorktreeParallelism })

            val primary = accepted.values.first { it.status.reviewId in cancelled }
            val mirror = accepted.values.first { it.status.sourceSnapshotId == primary.status.sourceSnapshotId &&
                it.client.binding.clientId != primary.client.binding.clientId }
            assertEquals("review_not_found", assertFailsWith<KernelException> {
                submitter.cancel(primary.client.client, mirror.status.reviewId)
            }.code)
            assertEquals("complete", submitter.status(mirror.client.client, mirror.status.reviewId).state)
            assertEquals("unauthorized", assertFailsWith<KernelException> {
                submitter.acknowledge(AdapterCredential(primary.client.client.clientId, primary.client.client.token),
                    listOf("not-an-event"), ContextReceipt("fake", Digests.sha256("fake"), "fake", Instant.EPOCH.toString()))
            }.code)

            // Concurrent admission order differs by client. Sixteen explicit cache-hit refresh
            // obligations establish each client's same current head without dropping intermediates.
            clients.forEach { client ->
                val current = currentByTree.getValue(client.binding.worktreeId)
                val refresh = submit(submitter, client, SubmitRequest("current-refresh", "current-refresh", current), ingestionRetries)
                accepted[refresh.status.reviewId] = refresh
                assertNotNull(refresh.status.result)
            }
            assertEquals(216, accepted.size)
            assertEquals(100, providerCalls.values.sumOf { it.get() })
            streamRunning.set(false)
            collector.join(10_000)
            assertFalse(collector.isAlive)
            streamFailure.get()?.let { throw AssertionError("Concurrent outbox reader failed", it) }
            clients.forEach { client ->
                while (stream(client)) { /* saved cursor advances only over actual returned events */ }
            }

            accepted.values.forEach { record ->
                val status = submitter.status(record.client.client, record.status.reviewId)
                assertNotNull(status.result)
                assertEquals(if (status.reviewId in cancelled) "cancelled" else "complete", status.state)
                val events = eventStreams.getValue(record.client.binding.clientId).filter { it.reviewId == status.reviewId }
                assertEquals(1, events.count { it.kind == "accepted" })
                assertEquals(1, events.count { it.kind == "progress" })
                assertEquals(1, events.count { it.kind == "result" })
                assertEquals(if (status.cancelled) 1 else 0, events.count { it.kind == "cancelled" })
            }
            assertEquals(668, eventStreams.values.sumOf { it.size })
            val allIds = eventStreams.values.flatten().map { it.eventId }
            assertEquals(allIds.size, allIds.distinct().size)
            clients.forEach { client ->
                val id = client.binding.clientId
                val records = accepted.values.filter { it.client.binding.clientId == id }
                assertEquals((1L..records.size.toLong()).toList(), records.map { it.status.sequence }.sorted())
                val events = eventStreams.getValue(id)
                assertEquals((1L..events.size.toLong()).toList(), events.map { it.sequence })
                val cut = submitter.prepareGate(client.client)
                assertFalse(cut.ready, "Fetching outboxes cannot satisfy model observation")
                assertEquals(events.map { it.eventId }, cut.requiredEventIds)
                events.chunked(50).forEachIndexed { chunk, batch ->
                    val digest = Digests.sha256(canonical(batch.map { it.payloadDigest }))
                    val ids = batch.map { it.eventId }
                    submitter.acknowledge(client.adapter, ids, ContextReceipt("ack-$chunk", digest, "item-$chunk", Instant.EPOCH.toString()))
                    submitter.observe(client.adapter, ids, ObservationReceipt("observed-$chunk", "synthetic-model-request-$chunk", listOf(digest)))
                }
                val after = submitter.prepareGate(client.client)
                val cancelledHere = records.filter { it.status.reviewId in cancelled }.map { it.status.reviewId }.toSet()
                if (cancelledHere.isEmpty()) assertTrue(after.ready, after.reasons.toString())
                else {
                    assertFalse(after.ready)
                    assertEquals(cancelledHere, after.reasons.filter { it.startsWith("review_cancelled:") }.map { it.substringAfter(':') }.toSet())
                }
            }
            dispatches = submitter.ledger.read { c -> c.query("SELECT * FROM stress_dispatches ORDER BY sequence") { row ->
                Dispatch(row.getLong("sequence"), row.getString("fair_key"), row.getString("sponsor_client"),
                    row.getLong("min_tree_age"), row.getLong("tree_age"), row.getLong("min_client_age"),
                    row.getObject("client_age")?.let { row.getLong("client_age") },
                    row.getObject("oldest_review")?.let { row.getLong("oldest_review") },
                    row.getObject("selected_review")?.let { row.getLong("selected_review") },
                    row.getInt("running"), row.getInt("tree_running"))
            } }
            assertEquals((1L..100L).toList(), dispatches.map { it.sequence })
            dispatches.forEach { dispatch ->
                assertNotNull(dispatch.sponsor)
                assertEquals(dispatch.minTreeAge, dispatch.treeAge, "Oldest eligible worktree must get this dispatch opportunity")
                assertEquals(dispatch.minClientAge, dispatch.clientAge, "Oldest eligible client must sponsor this worktree turn")
                assertEquals(dispatch.oldestReview, dispatch.selectedReview, "Sponsor's own oldest queued obligation must be selected")
                assertTrue(dispatch.running <= config.globalParallelism)
                assertTrue(dispatch.treeRunning <= config.perWorktreeParallelism)
            }
        } finally {
            producerStart.countDown()
            providerRelease.countDown()
            streamRunning.set(false)
            collector?.join(10_000)
            pool.shutdownNow()
            pool.awaitTermination(10, TimeUnit.SECONDS)
            services.asReversed().forEach { it.close() }
        }

        // Real reopen verifies durable intermediate reviews, cancellation, events and observations.
        KernelService.open(database, provider, config, fence).use { recovered ->
            assertEquals(216L, recovered.ledger.read { c -> c.count("SELECT COUNT(*) FROM reviews") })
            assertEquals(100L, recovered.ledger.read { c -> c.count("SELECT COUNT(*) FROM work WHERE state='complete'") })
            clients.forEach { client ->
                assertEquals(client, recovered.registerClient(client))
                val persisted = mutableListOf<ReviewEvent>()
                var cursor: String? = null
                do {
                    val page = recovered.events(client.client, cursor, 23)
                    persisted += page.events
                    cursor = page.cursor
                } while (page.hasMore)
                assertEquals(eventStreams.getValue(client.binding.clientId), persisted)
                val wasCancelled = accepted.values.any { it.client.binding.clientId == client.binding.clientId && it.status.reviewId in cancelled }
                assertEquals(!wasCancelled, recovered.prepareGate(client.client).ready)
            }
            assertEquals(100, providerCalls.values.sumOf { it.get() })
        }

        writeReport(stressStarted, stressFinished, accepted.values.filter { it.request.patchId != "current-refresh" },
            terminalObserved, providerStarted, providerDuration, ingestionRetries.get(), producerMaximum.get(),
            maximumActive.get(), treeMaximum.values.maxOf { it.get() }, halfBacklog, dispatches,
            services.map { it.ledger.metrics.snapshot() })
    }

    private fun input(index: Int, tree: String): AnalysisInput {
        val root = temp.resolve("source-$index")
        Files.createDirectories(root)
        val text = "// Synthetic immutable stress fixture $index\nval patch$index = $index\n" + "// bounded payload\n".repeat(32)
        Files.writeString(root.resolve("Main.kt"), text)
        return AnalysisInput(SnapshotManifest(Digests.sha256("$tree:$text"), "stress-workspace", tree,
            listOf(SnapshotFile("Main.kt", Digests.sha256(text), text.toByteArray().size.toLong())),
            ownerId = "stress-owner", declaredRoots = listOf(".")), root.toString(),
            AnalysisContext(modelDigest = Digests.sha256("synthetic-model"), dependencyDigest = Digests.sha256("synthetic-dependencies"),
                toolchainDigest = Digests.sha256("synthetic-toolchain"), configurationDigest = Digests.sha256("synthetic-config"), fullProject = true),
            listOf(rule))
    }

    private fun submit(service: KernelService, client: ClientRegistration, request: SubmitRequest, retries: AtomicInteger): Accepted {
        val began = System.nanoTime()
        val deadline = began + TimeUnit.SECONDS.toNanos(90)
        var attempts = 0
        while (true) {
            try { return Accepted(client, request, service.submit(client.client, request), began, System.nanoTime()) }
            catch (e: KernelException) {
                if (e.code != "ingestion_busy" || System.nanoTime() >= deadline) throw e
                retries.incrementAndGet()
                Thread.sleep((2 + attempts++ % 7).toLong())
            }
        }
    }

    /** Test-only SQLite triggers observe the same serialized dispatch boundary, not provider timing. */
    private fun installDispatchAudit(service: KernelService) = service.ledger.transaction { c ->
        c.execute("""CREATE TABLE stress_dispatches(sequence INTEGER PRIMARY KEY,work_key TEXT NOT NULL,fair_key TEXT NOT NULL,
            sponsor_client TEXT,min_tree_age INTEGER,tree_age INTEGER,min_client_age INTEGER,client_age INTEGER,
            oldest_review INTEGER,selected_review INTEGER,running INTEGER,tree_running INTEGER)""")
        c.execute("""CREATE TABLE stress_candidates(sequence INTEGER,client_id TEXT,age INTEGER,oldest_review INTEGER,selected_review INTEGER,
            PRIMARY KEY(sequence,client_id))""")
        c.execute("""CREATE TRIGGER stress_before_dispatch BEFORE UPDATE OF state ON work
            WHEN OLD.state='queued' AND NEW.state='running' BEGIN
            INSERT INTO stress_dispatches(sequence,work_key,fair_key,min_tree_age,tree_age,min_client_age,running,tree_running)
            SELECT (SELECT CAST(value AS INTEGER)+1 FROM metadata WHERE key='dispatch_counter'),NEW.work_key,NEW.fair_key,
                (SELECT MIN(f.last_served) FROM fair_queues f WHERE EXISTS
                    (SELECT 1 FROM work q WHERE q.state='queued' AND q.provider_key=NEW.provider_key AND q.fair_key=f.fair_key)
                    AND (SELECT COUNT(*) FROM work a WHERE a.state='running' AND a.fair_key=f.fair_key)<${config.perWorktreeParallelism}),
                (SELECT last_served FROM fair_queues WHERE fair_key=NEW.fair_key),
                (SELECT MIN(fc.last_served) FROM fair_clients fc JOIN reviews r ON r.client_id=fc.client_id
                    JOIN work q ON q.work_key=r.work_key WHERE q.fair_key=NEW.fair_key AND q.state='queued' AND q.provider_key=NEW.provider_key),
                (SELECT COUNT(*)+1 FROM work WHERE state='running'),
                (SELECT COUNT(*)+1 FROM work WHERE state='running' AND fair_key=NEW.fair_key);
            INSERT INTO stress_candidates(sequence,client_id,age,oldest_review,selected_review)
            SELECT (SELECT CAST(value AS INTEGER)+1 FROM metadata WHERE key='dispatch_counter'),r.client_id,fc.last_served,
                MIN(r.sequence),MIN(CASE WHEN r.work_key=NEW.work_key THEN r.sequence ELSE NULL END)
                FROM reviews r JOIN fair_clients fc ON fc.client_id=r.client_id JOIN work q ON q.work_key=r.work_key
                WHERE q.fair_key=NEW.fair_key AND q.state='queued' AND q.provider_key=NEW.provider_key
                GROUP BY r.client_id,fc.last_served;
            END""")
        c.execute("""CREATE TRIGGER stress_after_sponsor AFTER UPDATE OF last_served ON fair_clients BEGIN
            UPDATE stress_dispatches SET sponsor_client=NEW.client_id,client_age=OLD.last_served,
                oldest_review=(SELECT oldest_review FROM stress_candidates WHERE sequence=NEW.last_served AND client_id=NEW.client_id),
                selected_review=(SELECT selected_review FROM stress_candidates WHERE sequence=NEW.last_served AND client_id=NEW.client_id)
                WHERE sequence=NEW.last_served;
            END""")
    }

    private fun writeReport(started: Long, finished: Long, records: Collection<Accepted>, terminal: Map<String, Long>,
                            providerStarted: Map<String, Long>, providerDuration: Map<String, Long>, retries: Int,
                            producerMaximum: Int, maximumRunning: Int, maximumPerTree: Int, halfBacklog: Long,
                            dispatches: List<Dispatch>, timings: List<LedgerTimingSnapshot>) {
        fun distribution(nanos: List<Long>): JsonObject {
            val ordered = nanos.sorted()
            fun percentile(p: Double) = ordered[(ceil(ordered.size * p).toInt() - 1).coerceIn(ordered.indices)] / 1_000_000.0
            return buildJsonObject {
                put("samples", ordered.size)
                put("mean_ms", ordered.average() / 1_000_000.0)
                put("p50_ms", percentile(0.5)); put("p95_ms", percentile(0.95)); put("max_ms", ordered.last() / 1_000_000.0)
            }
        }
        fun combined(values: List<TimingSnapshot>) = buildJsonObject {
            val count = values.sumOf { it.count }
            put("operations", count)
            put("total_ms", values.sumOf { it.totalNanos } / 1_000_000.0)
            put("mean_ms", if (count == 0L) 0.0 else values.sumOf { it.totalNanos } / count / 1_000_000.0)
            put("max_ms", values.maxOf { it.maximumNanos } / 1_000_000.0)
        }
        val elapsed = (finished - started) / 1_000_000_000.0
        val report = buildJsonObject {
            put("fixture", "kernel-concurrent-100-distinct-patches-v1")
            put("contract", PREVIEW_CONTRACT)
            put("verified", true)
            put("scope", "Synthetic 20ms provider, real SQLite FULL WAL/files/threads; not FIR, production-host qualification, CPU-time fairness or a performance SLA")
            put("timing_scope", "One bounded run, no warmup; first 50 patches held behind startup barrier; SQL fairness-audit triggers and outbox poller add overhead")
            put("captured_at", Instant.now().toString())
            put("jdk", System.getProperty("java.version")); put("os", System.getProperty("os.name"))
            put("available_processors", Runtime.getRuntime().availableProcessors())
            put("distinct_patch_work", 100); put("concurrent_work_subscriptions", 200)
            put("current_head_cache_refresh_subscriptions", 16); put("retained_reviews", 216); put("retained_required_events", 668)
            put("client_count", 16); put("worktree_count", 4); put("service_instances", 3)
            put("producer_threads", 12); put("max_active_producers", producerMaximum)
            put("global_cap", config.globalParallelism); put("per_worktree_cap", config.perWorktreeParallelism)
            put("observed_max_provider_running", maximumRunning); put("observed_max_provider_per_worktree", maximumPerTree)
            put("backlog_at_release", halfBacklog); put("ingestion_busy_retries_before_acceptance", retries)
            put("client_cancellations_with_preserved_obligations", 20)
            put("elapsed_workload_seconds", elapsed); put("distinct_work_throughput_per_second", 100 / elapsed)
            put("submission_with_retry", distribution(records.map { it.acceptedNanos - it.submittedNanos }))
            put("queue_wait_lower_bound_after_submit_return", distribution(records.map {
                (providerStarted.getValue(it.status.sourceSnapshotId) - it.acceptedNanos).coerceAtLeast(0)
            }))
            put("queue_note", "Lower bound: execution can begin before submit returns; includes deliberately held startup backlog")
            put("synthetic_provider_duration_excluding_startup_barrier", distribution(providerDuration.values.toList()))
            put("submit_to_terminal_storage_observation", distribution(records.map {
                (terminal.getValue(it.status.reviewId) - it.submittedNanos).coerceAtLeast(0)
            }))
            put("terminal_note", "First SQLite visibility noticed by fixture poller, not external delivery/model latency")
            put("ledger_connection", combined(timings.map { it.connection })); put("ledger_read_transaction", combined(timings.map { it.read }))
            put("ledger_writer_begin_wait", combined(timings.map { it.writerWait })); put("ledger_writer_hold", combined(timings.map { it.writerHold }))
            put("ledger_note", "All three instance lifetimes, including setup, validation, heartbeat and close; max is observed, never guaranteed")
            put("audited_dispatches", dispatches.size)
            put("fairness", "Every dispatch chose minimum eligible worktree and sponsor-client age plus sponsor FIFO; only sponsor's turn charged")
        }
        val path = reportPath()
        Files.createDirectories(path.toAbsolutePath().parent)
        Files.writeString(path, Digests.canonical(report) + "\n")
    }

    private fun reportPath(): Path = Path.of(System.getProperty("ktchecker.kernel.stressReport", "build/reports/stress/kernel-100-patches.json"))
}

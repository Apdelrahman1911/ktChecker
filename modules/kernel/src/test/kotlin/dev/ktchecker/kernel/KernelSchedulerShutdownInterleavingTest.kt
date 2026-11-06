package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/** Real SQLite/files/threads; provider results and delivery receipts are synthetic, not qualification. */
class KernelSchedulerShutdownInterleavingTest {
    @TempDir lateinit var temp: Path
    private val rule = "test/shutdown-drain"
    private val providerId = "synthetic-shutdown-drain"
    private val providerVersion = "shutdown-interleaving-1"
    private val staleSentinel = "stopped-attempt-result-must-not-publish"

    @Test @Timeout(value = 90, unit = TimeUnit.SECONDS)
    fun closeReturnsWhileItsProviderDrainsWithoutReclaimingOrPublishingTheStoppedAttempt() {
        val firstInput = input("first", "first-tree")
        val peerInput = input("peer", "peer-tree")
        val firstEntered = CountDownLatch(1)
        val firstInterrupted = CountDownLatch(1)
        val firstRelease = CountDownLatch(1)
        val firstExited = CountDownLatch(1)
        val retryEntered = CountDownLatch(1)
        val retryRelease = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val firstToken = AtomicReference<CancellationToken>()
        val firstEffective = AtomicReference<AnalysisInput>()
        val retryEffective = AtomicReference<AnalysisInput>()
        val peerEffective = AtomicReference<AnalysisInput>()
        val staleReturned = AtomicReference<ProviderResult>()
        val firstCalls = AtomicInteger()
        val peerCalls = AtomicInteger()
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val provider = object : AnalysisProvider {
            override val id = providerId
            override val version = providerVersion
            override val supportedRuleIds = setOf(rule)
            override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
                val concurrent = active.incrementAndGet()
                maximumActive.accumulateAndGet(concurrent, ::maxOf)
                var drainingOriginal = false
                try {
                    assertEquals(1, concurrent, "A live draining provider still consumes the global slot")
                    return when (input.snapshot.snapshotId) {
                        firstInput.snapshot.snapshotId -> when (firstCalls.incrementAndGet()) {
                            1 -> {
                                drainingOriginal = true
                                firstEffective.set(input)
                                firstToken.set(cancellation)
                                firstEntered.countDown()
                                awaitIgnoringInterrupt(firstRelease, firstInterrupted)
                                assertTrue(cancellation.isCancelled(), "Close must cancel the original attempt")
                                passed(input, listOf(Finding(rule, staleSentinel, "Synthetic stopped attempt", "Fixture only",
                                    SourceLocation("Main.kt", input.snapshot.files.single().digest),
                                    Digests.sha256(staleSentinel), providerId, context = input.context.scope)))
                                    .also { staleReturned.set(it) }
                            }
                            2 -> {
                                retryEffective.set(input)
                                retryEntered.countDown()
                                assertTrue(retryRelease.await(30, TimeUnit.SECONDS), "Bounded retry release")
                                assertFalse(cancellation.isCancelled(), "The replacement owns a fresh attempt")
                                passed(input)
                            }
                            else -> error("The retained ordinary work may be retried only once in this fixture")
                        }
                        peerInput.snapshot.snapshotId -> {
                            assertEquals(1, peerCalls.incrementAndGet())
                            peerEffective.set(input)
                            assertFalse(cancellation.isCancelled(), "Stopping the first service must not cancel the peer")
                            passed(input)
                        }
                        else -> error("Unexpected synthetic input")
                    }
                } finally {
                    active.decrementAndGet()
                    if (drainingOriginal) firstExited.countDown()
                }
            }
        }
        val config = KernelConfig(workerCount = 0, globalParallelism = 1, perWorktreeParallelism = 1,
            heartbeatMillis = 30, leaseMillis = 120, pollMillis = 5, analysisTimeoutMillis = 60_000)
        val database = temp.resolve("shutdown-ledger")
        KernelService.open(database, provider, config).use { control ->
            var original: KernelService? = null
            var replacement: KernelService? = null
            // A broken close must fail a bounded get, not leave a non-daemon executor holding Gradle open.
            val closer = Executors.newSingleThreadExecutor { task ->
                Thread(task, "synthetic-shutdown-close").apply { isDaemon = true }
            }
            try {
                val firstClient = control.registerClient(binding("first-client", "first-tree"))
                val peerClient = control.registerClient(binding("peer-client", "peer-tree"))
                val firstReview = control.submit(firstClient.client, SubmitRequest("first-patch", "first-key", firstInput))
                assertEquals("queued", firstReview.state)
                val originalService = KernelService.open(database, provider, config.copy(workerCount = 1)).also { original = it }
                assertTrue(firstEntered.await(10, TimeUnit.SECONDS), "The original provider must actually enter")
                assertFalse(assertNotNull(firstToken.get()).isCancelled())
                val peerReview = control.submit(peerClient.client, SubmitRequest("peer-patch", "peer-key", peerInput))
                val firstClaim = reservation(control, firstReview.reviewId)
                val queuedPeer = reservation(control, peerReview.reviewId)
                assertEquals("running", firstClaim.state)
                assertEquals(originalService.instanceId, firstClaim.owner)
                assertEquals(1L, firstClaim.attempt)
                assertNull(firstClaim.raw)
                assertTrue(firstClaim.deadline > System.currentTimeMillis())
                assertEquals(0, closed(control, originalService))
                assertNotEquals(firstClaim.key, queuedPeer.key, "The second client has distinct physical work")
                assertEquals("queued", queuedPeer.state)
                assertEquals(0L, queuedPeer.attempt)
                assertNull(queuedPeer.owner)
                assertNull(queuedPeer.raw)

                val closing = closer.submit {
                    closeStarted.countDown()
                    originalService.close()
                    closeReturned.countDown()
                }
                assertTrue(closeStarted.await(10, TimeUnit.SECONDS), "Close must start independently of provider exit")
                assertTrue(firstInterrupted.await(10, TimeUnit.SECONDS), "The provider must observe and ignore close's interrupt")
                closing.get(10, TimeUnit.SECONDS)
                assertEquals(0L, closeReturned.count, "This is a successful close-return boundary, not merely a request")
                assertEquals(1L, firstRelease.count)
                assertEquals(1L, firstExited.count, "Close returned while the original provider was still inside analyze")
                assertTrue(assertNotNull(firstToken.get()).isCancelled())
                assertEquals(firstClaim, reservation(control, firstReview.reviewId))
                assertEquals(0, closed(control, originalService), "Close-requested is not yet a retired instance")

                // Startup runs recovery synchronously. Observe another committed heartbeat/recovery
                // after the old lease expires as well; a fixed sleep alone is not our recovery oracle.
                val replacementService = KernelService.open(database, provider, config.copy(workerCount = 1)).also { replacement = it }
                val initialHeartbeat = heartbeat(control, replacementService)
                awaitCondition("Replacement recovery must run after the draining owner's lease expires") {
                    control.ledger.read { c ->
                        val lease = assertNotNull(c.one("SELECT lease_until FROM work WHERE work_key=?", firstClaim.key) { it.getLong(1) })
                        val beat = assertNotNull(c.one("SELECT heartbeat FROM instances WHERE instance_id=?", replacementService.instanceId) { it.getLong(1) })
                        beat > initialHeartbeat && beat > lease
                    }
                }
                assertEquals(firstClaim, reservation(control, firstReview.reviewId), "Recovery must not replace a live stopped owner")
                assertEquals(queuedPeer, reservation(control, peerReview.reviewId), "The global slot is still reserved")
                assertEquals(0, closed(control, originalService))
                assertEquals(1L, firstExited.count)
                assertEquals(1, firstCalls.get())
                assertEquals(0, peerCalls.get())
                assertEquals(1, active.get())
                assertEquals(1, maximumActive.get())
                assertTrue(firstClaim.deadline > System.currentTimeMillis(), "Exercise shutdown, not the soft deadline")
                assertNoPublication(control, firstClaim.key, firstReview.reviewId)
                assertNoPublication(control, queuedPeer.key, peerReview.reviewId)

                firstRelease.countDown()
                assertTrue(firstExited.await(10, TimeUnit.SECONDS), "The old provider must really exit before retry")
                assertTrue(retryEntered.await(10, TimeUnit.SECONDS), "Retained work must be claimed by the replacement")
                val retryClaim = reservation(control, firstReview.reviewId)
                assertEquals(firstClaim.key, retryClaim.key, "Retry preserves the original work identity")
                assertEquals("running", retryClaim.state)
                assertEquals(replacementService.instanceId, retryClaim.owner)
                assertEquals(firstClaim.attempt + 2, retryClaim.attempt, "Release and the new claim each advance the attempt fence")
                assertNull(retryClaim.raw)
                assertNotEquals(assertNotNull(firstEffective.get()).analysisKey(), assertNotNull(retryEffective.get()).analysisKey())
                assertTrue(assertNotNull(staleReturned.get()).completeFor(assertNotNull(firstEffective.get())),
                    "The stopped provider really returned an otherwise complete sentinel result")
                assertEquals(staleSentinel, assertNotNull(staleReturned.get()).findings.single().title)
                awaitCondition("Only actual provider exit may retire the original instance") { closed(control, originalService) == 1 }
                assertEquals(1L, retryRelease.count)
                assertNoPublication(control, firstClaim.key, firstReview.reviewId)
                val recovering = control.events(firstClient.client)
                assertFalse(recovering.hasMore)
                assertEquals(listOf("accepted", "recovered"), recovering.events.map { it.kind })
                assertEquals(firstReview.copy(reasons = listOf("service_stopped_work_retained")),
                    ContractJson.decodeFromString<ReviewStatus>(recovering.events.last().payload))

                retryRelease.countDown()
                val firstFinal = awaitResult(control, firstClient, firstReview.reviewId)
                val peerFinal = awaitResult(control, peerClient, peerReview.reviewId)
                val expectedFirst = passed(assertNotNull(retryEffective.get()))
                val expectedPeer = passed(assertNotNull(peerEffective.get()))
                assertEquals(firstReview.copy(state = "complete", result = expectedFirst), firstFinal)
                assertEquals(peerReview.copy(state = "complete", result = expectedPeer), peerFinal)
                assertEquals(2, firstCalls.get())
                assertEquals(1, peerCalls.get())
                assertEquals(0, active.get())
                assertEquals(1, maximumActive.get(), "Close/recovery never oversubscribes actual provider calls")
                val firstCertificate = assertPublication(control, firstReview, firstInput, assertNotNull(retryEffective.get()),
                    expectedFirst, firstClaim.key, retryClaim.attempt)
                assertPublication(control, peerReview, peerInput, assertNotNull(peerEffective.get()), expectedPeer, queuedPeer.key, 1)
                val staleDigest = Digests.sha256(canonical(assertNotNull(staleReturned.get())))
                val staleCertificate = Digests.sha256(canonical(listOf(firstInput.analysisKey(),
                    assertNotNull(firstEffective.get()).analysisKey(), staleDigest, control.providerKey)))
                assertNotEquals(staleCertificate, firstCertificate, "No certificate may bind the stopped attempt's sentinel")

                val firstEvents = assertEvents(control, firstClient, firstReview, firstFinal, listOf("accepted", "recovered", "result"))
                val peerEvents = assertEvents(control, peerClient, peerReview, peerFinal, listOf("accepted", "result"))
                assertTrue((firstEvents + peerEvents).none { staleSentinel in it.payload })
                assertEquals("review_not_found", assertFailsWith<KernelException> { control.status(firstClient.client, peerReview.reviewId) }.code)
                control.ledger.read { c ->
                    assertEquals(2L, c.count("SELECT COUNT(*) FROM work WHERE state='complete'"))
                    assertEquals(0L, c.count("SELECT COUNT(*) FROM work WHERE state<>'complete'"))
                    assertEquals(2L, c.count("SELECT COUNT(*) FROM reviews"))
                    assertEquals(2L, c.count("SELECT COUNT(*) FROM work_certificates"))
                    assertEquals(5L, c.count("SELECT COUNT(*) FROM events"))
                    assertEquals(3L, c.count("SELECT CAST(value AS INTEGER) FROM metadata WHERE key='dispatch_counter'"))
                }
                assertReceipts(control, emptyList())
                deliver(control, firstClient, firstEvents)
                assertReceipts(control, firstEvents) // A's receipts do not discharge B's accepted/result obligations.
                assertEquals(peerEvents, control.events(peerClient.client).events)
                deliver(control, peerClient, peerEvents)
                assertReceipts(control, firstEvents + peerEvents)
            } finally {
                // Release before ANY close, including failed entry, startup, boundary or result assertions.
                firstRelease.countDown()
                retryRelease.countDown()
                try { replacement?.close() } finally {
                    try { original?.close() } finally {
                        closer.shutdownNow()
                        assertTrue(closer.awaitTermination(10, TimeUnit.SECONDS), "The bounded close executor must terminate")
                        awaitCondition("Provider calls must drain before the temporary store closes") { active.get() == 0 }
                        for (service in listOfNotNull(original, replacement)) {
                            awaitCondition("Stopped worker instances must retire before store cleanup") { closed(control, service) == 1 }
                        }
                    }
                }
            }
        }
    }

    private data class Reservation(val key: String, val state: String, val owner: String?, val attempt: Long,
        val deadline: Long, val raw: String?)

    private fun reservation(service: KernelService, reviewId: String): Reservation = service.ledger.read { c ->
        assertNotNull(c.one("""SELECT w.work_key,w.state,w.owner_instance,w.attempt,w.deadline,w.result_json
            FROM work w JOIN reviews r ON r.work_key=w.work_key WHERE r.review_id=?""", reviewId) {
            Reservation(it.getString(1), it.getString(2), it.getString(3), it.getLong(4), it.getLong(5), it.getString(6))
        })
    }

    private fun closed(control: KernelService, worker: KernelService): Int = control.ledger.read { c ->
        assertNotNull(c.one("SELECT closed FROM instances WHERE instance_id=?", worker.instanceId) { it.getInt(1) })
    }

    private fun heartbeat(control: KernelService, worker: KernelService): Long = control.ledger.read { c ->
        assertNotNull(c.one("SELECT heartbeat FROM instances WHERE instance_id=?", worker.instanceId) { it.getLong(1) })
    }

    private fun assertNoPublication(service: KernelService, workKey: String, reviewId: String) = service.ledger.read { c ->
        assertNull(c.one("SELECT result_json FROM work WHERE work_key=?", workKey) { it.getString(1) })
        assertEquals(0L, c.count("SELECT COUNT(*) FROM work_certificates WHERE work_key=?", workKey))
        assertEquals(0L, c.count("SELECT COUNT(*) FROM events WHERE review_id=? AND kind='result'", reviewId))
    }

    private fun assertPublication(service: KernelService, review: ReviewStatus, original: AnalysisInput,
        effective: AnalysisInput, expected: ProviderResult, key: String, attempt: Long): String {
        val json = canonical(expected)
        val digest = Digests.sha256(json)
        val certificate = Digests.sha256(canonical(listOf(original.analysisKey(), effective.analysisKey(), digest, service.providerKey)))
        assertEquals(Reservation(key, "complete", null, attempt, 0, json), reservation(service, review.reviewId))
        service.ledger.read { c ->
            assertEquals(1L, c.count("SELECT COUNT(*) FROM work_certificates WHERE work_key=?", key))
            assertEquals(listOf(original.analysisKey(), effective.analysisKey(), digest, service.providerKey, certificate),
                c.one("""SELECT input_key,effective_key,result_digest,provider_key,certificate_id
                    FROM work_certificates WHERE work_key=?""", key) { row -> (1..5).map { row.getString(it) } })
            assertEquals(1L, c.count("SELECT COUNT(*) FROM events WHERE review_id=? AND kind='result'", review.reviewId))
        }
        return certificate
    }

    private fun assertEvents(service: KernelService, client: ClientRegistration, accepted: ReviewStatus,
        completed: ReviewStatus, kinds: List<String>): List<ReviewEvent> {
        val page = service.events(client.client)
        assertFalse(page.hasMore)
        assertEquals(kinds, page.events.map { it.kind })
        assertEquals((1..kinds.size).map { it.toLong() }, page.events.map { it.sequence })
        for (event in page.events) {
            assertEquals(client.binding.clientId, event.clientId)
            assertEquals(client.binding.generation, event.generation)
            assertEquals(accepted.reviewId, event.reviewId)
            assertEquals(accepted.patchId, event.patchId)
            assertEquals(accepted.sourceSnapshotId, event.sourceSnapshotId)
            assertTrue(event.deliveryRequired)
            assertEquals(Digests.sha256(event.payload), event.payloadDigest)
            val expected = when (event.kind) {
                // Admission delivery is a projection, not the queued submit-return status.
                "accepted" -> accepted.copy(state = "accepted", result = null, reasons = emptyList())
                "recovered" -> accepted.copy(reasons = listOf("service_stopped_work_retained"))
                "result" -> completed
                else -> error("Unexpected event kind")
            }
            assertEquals(canonical(expected), event.payload)
        }
        return page.events
    }

    private fun deliver(service: KernelService, client: ClientRegistration, events: List<ReviewEvent>) {
        for (event in events) {
            val digest = Digests.sha256("synthetic-inserted-context:${event.payload}")
            service.acknowledge(client.adapter, listOf(event.eventId), ContextReceipt("ack-${event.eventId}", digest,
                "item-${event.eventId}", Instant.EPOCH.toString()))
            service.observe(client.adapter, listOf(event.eventId), ObservationReceipt("observe-${event.eventId}",
                "request-${event.eventId}", listOf(digest)))
        }
    }

    private fun assertReceipts(service: KernelService, expected: List<ReviewEvent>) = service.ledger.read { c ->
        for (table in listOf("acknowledgements", "observations")) {
            assertEquals(expected.map { it.eventId to it.clientId }.sortedBy { it.first },
                c.query("SELECT event_id,client_id FROM $table ORDER BY event_id") { it.getString(1) to it.getString(2) })
        }
        assertEquals(expected.size.toLong() * 2, c.count("SELECT COUNT(*) FROM receipts"))
    }

    private fun awaitResult(service: KernelService, client: ClientRegistration, reviewId: String): ReviewStatus {
        var status = service.status(client.client, reviewId)
        awaitCondition("Review must publish a terminal result: $reviewId") {
            status = service.status(client.client, reviewId)
            status.result != null
        }
        return status
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        do {
            if (condition()) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        fail(message)
    }

    private fun awaitIgnoringInterrupt(release: CountDownLatch, interrupted: CountDownLatch) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (release.count != 0L) {
            val remaining = deadline - System.nanoTime()
            assertTrue(remaining > 0, "Bounded interrupt-ignoring provider release")
            try {
                assertTrue(release.await(remaining, TimeUnit.NANOSECONDS), "Bounded interrupt-ignoring provider release")
            } catch (_: InterruptedException) {
                interrupted.countDown() // Intentionally ignore close's interruption; do not extend the absolute bound.
            }
        }
    }

    private fun binding(id: String, tree: String) = ClientBinding("owner", "workspace", tree, id, "lineage-$id", 1, "session-$id")

    private fun input(name: String, tree: String): AnalysisInput {
        val source = "fun main() = Unit\n"
        val root = temp.resolve("source-$name")
        Files.createDirectories(root)
        Files.writeString(root.resolve("Main.kt"), source)
        val snapshot = SnapshotManifest(Digests.sha256("synthetic-snapshot:$name:$source"), "workspace", tree,
            listOf(SnapshotFile("Main.kt", Digests.sha256(source), source.toByteArray().size.toLong())),
            ownerId = "owner", declaredRoots = listOf("."))
        return AnalysisInput(snapshot, root.toString(), AnalysisContext(scope = RuleContext(),
            modelDigest = Digests.sha256("model"), dependencyDigest = Digests.sha256("deps"),
            toolchainDigest = Digests.sha256("tools"), configurationDigest = Digests.sha256("config"), fullProject = true), listOf(rule))
    }

    private fun passed(input: AnalysisInput, findings: List<Finding> = emptyList()) = ProviderResult(providerId, "complete", findings,
        input.requiredRuleIds.map { Coverage(it, input.context.scope, if (findings.isEmpty()) "passed" else "findings",
            provenance = "executed", certificateId = Digests.sha256("synthetic-coverage:${input.analysisKey()}:$it"),
            implemented = true, tested = true, qualified = true) }, providerVersion = providerVersion)
}

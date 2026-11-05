package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Real scheduler/SQLite/archive controls; provider, source authority and model receipts are synthetic. */
class KernelPublicationFenceTest {
    @TempDir lateinit var temp: Path
    private val config = KernelConfig(workerCount = 1, globalParallelism = 1, perWorktreeParallelism = 1,
        heartbeatMillis = 40, leaseMillis = 160, pollMillis = 5, analysisTimeoutMillis = 15_000)
    private val shortBudget = 1500L

    private fun input(name: String, texts: Map<String, String> = mapOf("Main.kt" to "val value = 1\n"),
                      parent: String? = null): AnalysisInput {
        val root = temp.resolve("source-$name")
        Files.createDirectories(root)
        val files = texts.toSortedMap().map { (path, text) ->
            val file = root.resolve(path)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
            SnapshotFile(path, Digests.sha256(text), text.toByteArray().size.toLong())
        }
        val snapshot = SnapshotManifest(Digests.sha256("synthetic-publication:$name:${canonical(files)}:$parent"),
            "workspace", "tree", files, ownerId = "owner", parentSnapshotId = parent, declaredRoots = listOf("."))
        return AnalysisInput(snapshot, root.toString(), AnalysisContext(scope = RuleContext(module = ":$name"),
            modelDigest = Digests.sha256("model"), dependencyDigest = Digests.sha256("dependencies"),
            toolchainDigest = Digests.sha256("toolchain"), configurationDigest = Digests.sha256("configuration"),
            fullProject = true), listOf(PLAN_TEST_RULE))
    }

    private fun register(service: KernelService, id: String) = service.registerClient(
        ClientBinding("owner", "workspace", "tree", id, "lineage-$id", sessionId = "session-$id"))
    private fun request(id: String, input: AnalysisInput) = SubmitRequest("patch-$id", "key-$id", input)
    private fun await(latch: CountDownLatch) = assertTrue(latch.await(10, TimeUnit.SECONDS), "Fixture synchronization timed out")

    private fun awaitResult(service: KernelService, client: ClientRegistration, reviewId: String): ReviewStatus {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var status = service.status(client.client, reviewId)
        while (status.result == null && System.nanoTime() - until < 0) {
            Thread.sleep(5)
            status = service.status(client.client, reviewId)
        }
        assertNotNull(status.result, "Synthetic review did not publish a terminal result")
        return status
    }

    /** Entry to finished is AFTER the earlier deadline sample; this wait cannot expire it retroactively. */
    private fun waitPastOriginalBudget(finishedAt: Long) {
        val elapsed = TimeUnit.MILLISECONDS.toNanos(shortBudget + 40)
        while (System.nanoTime() - finishedAt < elapsed) Thread.sleep(5)
    }

    private fun events(service: KernelService, client: ClientRegistration): List<ReviewEvent> = buildList {
        var cursor: String? = null
        do {
            val page = service.events(client.client, cursor, 7)
            addAll(page.events)
            cursor = page.cursor
        } while (page.hasMore)
    }

    private fun deliver(service: KernelService, client: ClientRegistration, observe: Boolean = true): List<ReviewEvent> {
        val delivered = events(service, client)
        for (event in delivered) {
            assertEquals(client.binding.clientId, event.clientId)
            assertEquals(Digests.sha256(event.payload), event.payloadDigest)
            val digest = Digests.sha256("synthetic-context:${event.eventId}:${event.payload}")
            service.acknowledge(client.adapter, listOf(event.eventId), ContextReceipt("ack-${event.eventId}", digest,
                "context-${event.eventId}", Instant.EPOCH.toString()))
            if (observe) service.observe(client.adapter, listOf(event.eventId), ObservationReceipt("observe-${event.eventId}",
                "synthetic-model-request-${event.eventId}", listOf(digest)))
        }
        return delivered
    }

    /** Synthetic exact-head authority held THROUGH the guarded action; never a live filesystem scan. */
    private class Fence(initial: AnalysisInput) : SnapshotGate {
        private val lock = Any()
        private var current = initial
        private var epoch = 1L
        fun update(input: AnalysisInput) = synchronized(lock) { current = input; epoch++ }
        override fun <T> withCurrentSnapshot(input: AnalysisInput, action: (SnapshotCheck) -> T): T = synchronized(lock) {
            action(SnapshotCheck(current.snapshot == input.snapshot, current.snapshot.snapshotId, epoch,
                current.context.configurationDigest, current.context.modelDigest, current.context.dependencyDigest,
                current.context.toolchainDigest, "synthetic-publication-fence:$epoch", authoritative = true))
        }
    }

    private fun assertRawCompleteButReviewIncomplete(status: ReviewStatus, reason: String) {
        assertEquals("incomplete", status.state)
        assertTrue(reason in status.reasons, status.reasons.toString())
        val raw = assertNotNull(status.result)
        assertEquals("complete", raw.status, "A late publication must retain earlier raw provider evidence")
        assertTrue(raw.reasons.isEmpty())
        assertEquals(1, raw.coverage.size)
        assertTrue(raw.coverage.single().successful())
        assertTrue(raw.coverage.single().qualified)
    }

    private fun assertGateBlockedByIncompleteReview(service: KernelService, client: ClientRegistration, review: String) {
        deliver(service, client)
        val cut = service.prepareGate(client.client)
        assertFalse(cut.ready)
        assertTrue("review_incomplete:$review" in cut.reasons, cut.reasons.toString())
        assertFalse(cut.reasons.any { it.startsWith("insertion_missing:") || it.startsWith("model_observation_missing:") })
        var actions = 0
        val result = service.completeGate(client.client, cut.cutId) { actions++ }
        assertFalse(result.allowed)
        assertFalse(result.actionPerformed)
        assertEquals(0, actions)
    }

    @Test fun lateSuccessfulTeardownCannotPublishSharedCoverageAsCompleteOrConsumeAnotherClientsDelivery() {
        val input = input("shared-late")
        val finishedEntered = CountDownLatch(1)
        val releaseFinished = CountDownLatch(1)
        val finishedAt = AtomicLong()
        val token = AtomicReference<CancellationToken>()
        val provider = PlanTestProvider { effective, cancellation -> token.set(cancellation); planPassed(effective) }
        val observer = object : PreparedInputObserver {
            override fun prepared(original: AnalysisInput, effective: AnalysisInput, verifyFrozen: () -> Unit) { verifyFrozen() }
            override fun finished(effective: AnalysisInput) {
                finishedAt.set(System.nanoTime())
                finishedEntered.countDown()
                await(releaseFinished)
            }
        }
        KernelService.open(temp.resolve("ledger"), provider, config.copy(analysisTimeoutMillis = shortBudget),
            Fence(input), observer).use { service ->
            val a = register(service, "cancelled-subscriber")
            val b = register(service, "retained-subscriber")
            try {
                val first = service.submit(a.client, request("same", input))
                await(finishedEntered)
                val second = service.submit(b.client, request("same", input))
                service.cancel(a.client, first.reviewId)
                assertFalse(token.get().isCancelled(), "Subscription cancellation is not shared execution cancellation")
                waitPastOriginalBudget(finishedAt.get())
                releaseFinished.countDown()
                val result = awaitResult(service, b, second.reviewId)
                assertRawCompleteButReviewIncomplete(result, "analysis_deadline_exceeded")
                val cancelled = awaitResult(service, a, first.reviewId)
                assertEquals("cancelled", cancelled.state)
                assertTrue("client_cancelled_obligation_retained" in cancelled.reasons)
                assertTrue("analysis_deadline_exceeded" in cancelled.reasons)
                assertEquals(1, provider.calls.get(), "Equivalent subscribers must still share exactly one execution")
                val aEvents = deliver(service, a)
                val bEvents = events(service, b)
                assertTrue(aEvents.map { it.eventId }.toSet().intersect(bEvents.map { it.eventId }.toSet()).isEmpty())
                val undelivered = service.prepareGate(b.client)
                assertTrue(undelivered.reasons.any { it.startsWith("insertion_missing:") })
                assertTrue(undelivered.reasons.any { it.startsWith("model_observation_missing:") })
                assertGateBlockedByIncompleteReview(service, b, second.reviewId)
                assertTrue(service.prepareGate(a.client).reasons.any { it.startsWith("review_cancelled:") })
            } finally { releaseFinished.countDown() }
        }
    }

    @Test fun presentWorkerInterruptAfterTeardownCannotCompleteAndDoesNotLeakToTheNextClaim() {
        val firstInput = input("interrupted")
        val nextInput = input("next")
        val firstFinished = AtomicBoolean()
        val workerThreads = ConcurrentLinkedQueue<Thread>()
        val provider = PlanTestProvider { effective, _ ->
            workerThreads += Thread.currentThread()
            assertFalse(Thread.currentThread().isInterrupted)
            planPassed(effective)
        }
        val observer = object : PreparedInputObserver {
            override fun prepared(original: AnalysisInput, effective: AnalysisInput, verifyFrozen: () -> Unit) { verifyFrozen() }
            override fun finished(effective: AnalysisInput) {
                if (firstFinished.compareAndSet(false, true)) Thread.currentThread().interrupt()
            }
        }
        val fence = Fence(firstInput)
        KernelService.open(temp.resolve("ledger"), provider, config, fence, observer).use { service ->
            val a = register(service, "interrupted-client")
            val first = service.submit(a.client, request("interrupted", firstInput))
            assertRawCompleteButReviewIncomplete(awaitResult(service, a, first.reviewId), "analysis_interrupted")
            assertGateBlockedByIncompleteReview(service, a, first.reviewId)
            assertFalse(Thread.currentThread().isInterrupted, "The caller is not the interrupted worker")
            val b = register(service, "next-client")
            fence.update(nextInput)
            val next = service.submit(b.client, request("next", nextInput))
            val accepted = awaitResult(service, b, next.reviewId)
            assertEquals("complete", accepted.state, accepted.reasons.toString())
            assertEquals(2, provider.calls.get())
            assertEquals(1, workerThreads.toSet().size, "The bounded worker is reused only after exact-attempt cleanup")
            deliver(service, b)
            val cut = service.prepareGate(b.client)
            assertTrue(cut.ready, cut.reasons.toString())
            var actions = 0
            assertTrue(service.completeGate(b.client, cut.cutId) { actions++ }.actionPerformed)
            assertEquals(1, actions, "Another client's incomplete obligation cannot consume this independent lineage's action")
        }
    }

    @Test fun waitingForThePublicationWriterDoesNotRenewTheOriginalClaimBudget() {
        val input = input("writer-wait")
        val finishedEntered = CountDownLatch(1)
        val releaseFinished = CountDownLatch(1)
        val finishedReturning = CountDownLatch(1)
        val finishedAt = AtomicLong()
        val provider = PlanTestProvider()
        val observer = object : PreparedInputObserver {
            override fun prepared(original: AnalysisInput, effective: AnalysisInput, verifyFrozen: () -> Unit) { verifyFrozen() }
            override fun finished(effective: AnalysisInput) {
                finishedAt.set(System.nanoTime())
                finishedEntered.countDown()
                await(releaseFinished)
                finishedReturning.countDown()
            }
        }
        KernelService.open(temp.resolve("ledger"), provider, config.copy(analysisTimeoutMillis = shortBudget),
            Fence(input), observer).use { service ->
            val client = register(service, "writer-wait-client")
            try {
                val review = service.submit(client.client, request("writer-wait", input))
                await(finishedEntered)
                service.ledger.transaction { c ->
                    // Acquire the actual SQLite writer BEFORE teardown returns. No raw publication
                    // can cross this barrier, even if the provider and teardown returned in time.
                    releaseFinished.countDown()
                    await(finishedReturning)
                    waitPastOriginalBudget(finishedAt.get())
                    assertEquals(1L, c.count("SELECT COUNT(*) FROM work WHERE state='running'"))
                    assertEquals(0L, c.count("SELECT COUNT(*) FROM work WHERE state='complete'"))
                }
                assertRawCompleteButReviewIncomplete(awaitResult(service, client, review.reviewId), "analysis_deadline_exceeded")
                assertEquals(1, provider.calls.get())
                assertGateBlockedByIncompleteReview(service, client, review.reviewId)
            } finally { releaseFinished.countDown() }
        }
    }

    @Test fun anExpiredInterruptedObsoleteClaimCannotInvalidateTheReplacementOrFollowingClaim() {
        val original = input("obsolete")
        val sentinel = input("sentinel")
        val oldFinished = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val oldFinishedAt = AtomicLong()
        val oldProvider = PlanTestProvider { effective, _ -> planPassed(effective) }
        val replacementProvider = PlanTestProvider()
        val observer = object : PreparedInputObserver {
            override fun prepared(original: AnalysisInput, effective: AnalysisInput, verifyFrozen: () -> Unit) { verifyFrozen() }
            override fun finished(effective: AnalysisInput) {
                if (effective.snapshot.snapshotId == original.snapshot.snapshotId) {
                    oldFinishedAt.set(System.nanoTime())
                    oldFinished.countDown()
                    await(releaseOld)
                    Thread.currentThread().interrupt()
                }
            }
        }
        val laneConfig = config.copy(analysisTimeoutMillis = shortBudget)
        KernelService.open(temp.resolve("ledger"), oldProvider, laneConfig, Fence(original), observer).use { first ->
            val client = register(first, "obsolete-client")
            try {
                val review = first.submit(client.client, request("original", original))
                await(oldFinished)
                // Explicit synthetic attempt-token injection, NOT permission to reclaim live
                // production work on a lease timeout. The old fake provider has already returned.
                first.ledger.transaction { c ->
                    assertEquals(1, c.execute("""UPDATE work SET state='queued',owner_instance=NULL,lease_until=NULL,
                        deadline=NULL,attempt=attempt+1 WHERE state='running'"""))
                }
                KernelService.open(temp.resolve("ledger"), replacementProvider, laneConfig, Fence(original)).use { replacement ->
                    assertEquals("complete", awaitResult(replacement, client, review.reviewId).state)
                    assertEquals(1, replacementProvider.calls.get())
                }
                val before = first.status(client.client, review.reviewId)
                val retainedEvents = events(first, client)
                val nextClient = register(first, "sentinel-client")
                val next = first.submit(nextClient.client, request("sentinel", sentinel))
                waitPastOriginalBudget(oldFinishedAt.get())
                releaseOld.countDown()
                // Only the old service remains. Its single worker must reject the obsolete
                // publication and pass through exact-attempt cleanup before running this sentinel.
                assertEquals("complete", awaitResult(first, nextClient, next.reviewId).state)
                assertEquals(2, oldProvider.calls.get())
                assertEquals(before, first.status(client.client, review.reviewId))
                assertEquals(retainedEvents, events(first, client), "Obsolete failure must not append another subscriber result")
                deliver(first, client)
                val cut = first.prepareGate(client.client)
                assertTrue(cut.ready, cut.reasons.toString())
                assertTrue(first.completeGate(client.client, cut.cutId).allowed)
            } finally { releaseOld.countDown() }
        }
    }

    @Test fun separateLineageSuccessAndReceiptsCannotReplaceFreshCombinedSnapshotReview() {
        val left = input("left", mapOf("Left.kt" to "val left = 1\n"))
        val right = input("right", mapOf("Right.kt" to "val right = 2\n"))
        val combined = input("combined", mapOf("Left.kt" to "val left = 1\n", "Right.kt" to "val right = 2\n"))
        val combinedEntered = CountDownLatch(1)
        val releaseCombined = CountDownLatch(1)
        val provider = PlanTestProvider { effective, _ ->
            if (effective.snapshot.snapshotId == combined.snapshot.snapshotId) {
                combinedEntered.countDown()
                await(releaseCombined)
            }
            planPassed(effective)
        }
        val fence = Fence(left)
        KernelService.open(temp.resolve("ledger"), provider, config, fence).use { service ->
            val a = register(service, "left-client")
            val b = register(service, "right-client")
            val integration = register(service, "integration-client")
            try {
                val ar = service.submit(a.client, request("left", left))
                assertEquals("complete", awaitResult(service, a, ar.reviewId).state)
                deliver(service, a)
                val aCut = service.prepareGate(a.client)
                assertTrue(aCut.ready, aCut.reasons.toString())
                fence.update(right)
                val br = service.submit(b.client, request("right", right))
                assertEquals("complete", awaitResult(service, b, br.reviewId).state)
                deliver(service, b)
                val bCut = service.prepareGate(b.client)
                assertTrue(bCut.ready, bCut.reasons.toString())
                fence.update(combined)
                var actions = 0
                assertFalse(service.completeGate(a.client, aCut.cutId) { actions++ }.allowed)
                assertFalse(service.completeGate(b.client, bCut.cutId) { actions++ }.allowed)
                assertFalse(service.prepareGate(integration.client).ready)
                val merged = service.submit(integration.client, request("combined", combined))
                await(combinedEntered)
                val pending = service.prepareGate(integration.client)
                assertTrue(pending.reasons.any { it.startsWith("review_pending:") })
                assertFalse(service.completeGate(integration.client, pending.cutId) { actions++ }.allowed)
                releaseCombined.countDown()
                val completed = awaitResult(service, integration, merged.reviewId)
                assertEquals("complete", completed.state)
                assertEquals(combined.snapshot.snapshotId, completed.sourceSnapshotId)
                assertEquals(3, provider.calls.get(), "A combined immutable input needs its own execution, not a union of earlier success labels")
                val missing = service.prepareGate(integration.client)
                assertTrue(missing.reasons.any { it.startsWith("insertion_missing:") })
                deliver(service, integration, observe = false)
                val notObserved = service.prepareGate(integration.client)
                assertTrue(notObserved.reasons.any { it.startsWith("model_observation_missing:") })
                assertFalse(service.completeGate(integration.client, notObserved.cutId) { actions++ }.allowed)
                assertEquals(0, actions)
                deliver(service, integration)
                val ready = service.prepareGate(integration.client)
                assertTrue(ready.ready, ready.reasons.toString())
                assertTrue(service.completeGate(integration.client, ready.cutId) { actions++ }.actionPerformed)
                assertEquals(1, actions)
            } finally { releaseCombined.countDown() }
        }
    }

    private fun rawPublicationAudit(ledger: Ledger, reviewId: String): List<List<String?>> = ledger.read { c ->
        // Stored SQL values (including timestamps, raw/effective results and both certificates)
        // must stay historical. A gate refusal is not permission to rewrite the retained review.
        listOf(
            assertNotNull(c.one("SELECT * FROM reviews WHERE review_id=?", reviewId) { row ->
                (1..row.metaData.columnCount).map { row.getString(it) }
            }),
            assertNotNull(c.one("SELECT w.* FROM work w JOIN reviews r ON r.work_key=w.work_key WHERE r.review_id=?", reviewId) { row ->
                (1..row.metaData.columnCount).map { row.getString(it) }
            }),
            assertNotNull(c.one("""SELECT cert.* FROM work_certificates cert JOIN reviews r ON r.work_key=cert.work_key
                WHERE r.review_id=?""", reviewId) { row ->
                (1..row.metaData.columnCount).map { row.getString(it) }
            }),
        )
    }

    @Test fun legacyRawPublicationCertificateCannotSatisfyCurrentCompletionOrBeReusedAfterUpgrade() {
        val input = input("legacy-publication")
        val database = temp.resolve("ledger")
        val fence = Fence(input)
        val seedProvider = PlanTestProvider()
        lateinit var client: ClientRegistration
        lateinit var completed: ReviewStatus
        lateinit var ready: GateCut
        lateinit var currentProviderKey: String
        lateinit var currentWorkKey: String
        lateinit var legacyProviderKey: String
        lateinit var retainedEvents: List<ReviewEvent>
        KernelService.open(database, seedProvider, config, fence).use { service ->
            client = register(service, "legacy-client")
            val review = service.submit(client.client, request("legacy", input))
            completed = awaitResult(service, client, review.reviewId)
            assertEquals("complete", completed.state)
            deliver(service, client)
            ready = service.prepareGate(client.client)
            assertTrue(ready.ready, ready.reasons.toString())
            retainedEvents = events(service, client)
            currentProviderKey = service.providerKey
            // Exact pre-profile derivation. This is not a caller-selected or production legacy mode.
            legacyProviderKey = Digests.sha256(canonical(listOf(service.providerId, service.providerVersion,
                canonical(service.supportedRules.toList()), canonical(service.authorizedFindingProviders.mapValues { it.value.sorted() }))))
            assertNotEquals(legacyProviderKey, currentProviderKey)
            currentWorkKey = assertNotNull(service.ledger.read { c ->
                c.one("SELECT work_key FROM reviews WHERE review_id=?", review.reviewId) { it.getString(1) }
            })
            assertEquals(Digests.sha256(canonical(listOf(currentProviderKey, input.analysisKey()))), currentWorkKey)
        }
        assertEquals(1, seedProvider.calls.get())
        val legacyWorkKey = Digests.sha256(canonical(listOf(legacyProviderKey, input.analysisKey())))
        val ledger = Ledger(database, config)
        // Synthetic OFFLINE old-identity fixture, after the only scheduler has closed. Transplant
        // only the work/provider key and its matching certificate, keeping real archived bytes,
        // raw/effective evidence, timestamps, receipts and review IDs unchanged. This is NOT an
        // implementation of historical migration or evidence that an old execution met a deadline.
        ledger.transaction { c ->
            assertEquals(0L, c.count("SELECT COUNT(*) FROM instances WHERE closed=0"), "The old fixture scheduler must be stopped")
            assertEquals(1L, c.count("SELECT COUNT(*) FROM work WHERE state='complete' AND owner_instance IS NULL"))
            val certificateParts = assertNotNull(c.one("""SELECT input_key,effective_key,result_digest FROM work_certificates
                WHERE work_key=?""", currentWorkKey) { listOf(it.getString(1), it.getString(2), it.getString(3)) })
            val legacyCertificate = Digests.sha256(canonical(certificateParts + legacyProviderKey))
            assertEquals(1, c.execute("""INSERT INTO work(work_key,input_json,frozen_json,provider_key,fair_key,state,
                owner_instance,attempt,lease_until,deadline,result_json,validation_json,progress_limited,archive_bytes,created_at)
                SELECT ?,input_json,frozen_json,?,fair_key,state,owner_instance,attempt,lease_until,deadline,result_json,
                    validation_json,progress_limited,archive_bytes,created_at FROM work WHERE work_key=?""",
                legacyWorkKey, legacyProviderKey, currentWorkKey))
            assertEquals(1, c.execute("UPDATE reviews SET work_key=? WHERE work_key=?", legacyWorkKey, currentWorkKey))
            c.execute("UPDATE work_progress SET work_key=? WHERE work_key=?", legacyWorkKey, currentWorkKey)
            assertEquals(1, c.execute("""UPDATE work_certificates SET work_key=?,provider_key=?,certificate_id=? WHERE work_key=?""",
                legacyWorkKey, legacyProviderKey, legacyCertificate, currentWorkKey))
            assertEquals(1, c.execute("DELETE FROM work WHERE work_key=?", currentWorkKey))
        }
        val historical = rawPublicationAudit(ledger, completed.reviewId)
        val upgradedProvider = PlanTestProvider()
        KernelService.open(database, upgradedProvider, config, fence).use { service ->
            assertEquals(currentProviderKey, service.providerKey)
            // Historical display is not silently reclassified; the final lineage certificate check
            // must reject the old identity even when all old insertion/observation receipts exist.
            assertEquals(completed, service.status(client.client, completed.reviewId))
            var actions = 0
            val oldCut = service.completeGate(client.client, ready.cutId) { actions++ }
            assertFalse(oldCut.allowed)
            assertFalse(oldCut.actionPerformed)
            assertTrue("review_certificate_invalid:${completed.reviewId}" in oldCut.reasons, oldCut.reasons.toString())
            val blocked = service.prepareGate(client.client)
            assertFalse(blocked.ready)
            assertTrue("review_certificate_invalid:${completed.reviewId}" in blocked.reasons, blocked.reasons.toString())
            assertFalse(blocked.reasons.any { it.startsWith("insertion_missing:") || it.startsWith("model_observation_missing:") })
            assertEquals(0, upgradedProvider.calls.get())
            val fresh = service.submit(client.client, request("explicit-new-review", input))
            assertEquals("complete", awaitResult(service, client, fresh.reviewId).state)
            assertEquals(1, upgradedProvider.calls.get(), "The old raw key cannot satisfy a current-profile request")
            service.ledger.read { c ->
                assertEquals(2L, c.count("SELECT COUNT(*) FROM work"))
                assertEquals(2L, c.count("SELECT COUNT(*) FROM work_certificates"))
                assertEquals(currentWorkKey, c.one("SELECT work_key FROM reviews WHERE review_id=?", fresh.reviewId) { it.getString(1) })
                assertEquals(legacyProviderKey, c.one("SELECT provider_key FROM work WHERE work_key=?", legacyWorkKey) { it.getString(1) })
            }
            deliver(service, client)
            val stillHistorical = service.prepareGate(client.client)
            assertFalse(stillHistorical.ready, "A fresh review does not erase the earlier retained lineage obligation")
            assertTrue("review_certificate_invalid:${completed.reviewId}" in stillHistorical.reasons, stillHistorical.reasons.toString())
            assertFalse(service.completeGate(client.client, stillHistorical.cutId) { actions++ }.allowed)
            assertEquals(0, actions)
            assertEquals(historical, rawPublicationAudit(service.ledger, completed.reviewId))
            assertEquals(retainedEvents, events(service, client).filter { it.reviewId == completed.reviewId })
        }
    }

    @Test fun currentRawPublicationProfileReusesTerminalWorkAcrossRestartWithoutSharingDelivery() {
        val input = input("current-profile-restart")
        val database = temp.resolve("ledger")
        val fence = Fence(input)
        val provider = PlanTestProvider()
        val originalRequest = request("current-profile", input)
        lateinit var a: ClientRegistration
        lateinit var completed: ReviewStatus
        lateinit var providerKey: String
        lateinit var historical: List<List<String?>>
        lateinit var aEvents: List<ReviewEvent>
        KernelService.open(database, provider, config, fence).use { service ->
            a = register(service, "restart-original-client")
            val first = service.submit(a.client, originalRequest)
            completed = awaitResult(service, a, first.reviewId)
            assertEquals("complete", completed.state)
            providerKey = service.providerKey
            aEvents = deliver(service, a)
            assertTrue(service.prepareGate(a.client).ready)
            historical = rawPublicationAudit(service.ledger, completed.reviewId)
        }
        assertEquals(1, provider.calls.get())
        val restartedProvider = PlanTestProvider()
        KernelService.open(database, restartedProvider, config, fence).use { service ->
            assertEquals(providerKey, service.providerKey)
            assertEquals(completed, service.status(a.client, completed.reviewId))
            assertEquals(completed, service.submit(a.client, originalRequest), "Durable idempotency must survive the unchanged profile")
            assertTrue(service.prepareGate(a.client).ready)
            val b = register(service, "restart-cache-client")
            val cached = service.submit(b.client, request("cached-current-profile", input))
            assertEquals("complete", cached.state, cached.reasons.toString())
            assertEquals(completed.result, cached.result)
            assertNotEquals(completed.reviewId, cached.reviewId)
            assertEquals(0, restartedProvider.calls.get(), "A revision boundary must not disable safe same-profile cache reuse")
            service.ledger.read { c ->
                assertEquals(1L, c.count("SELECT COUNT(*) FROM work"))
                assertEquals(1L, c.count("SELECT COUNT(*) FROM work_certificates"))
                assertEquals(2L, c.count("SELECT COUNT(*) FROM reviews"))
            }
            val bEvents = events(service, b)
            assertTrue(aEvents.map { it.eventId }.toSet().intersect(bEvents.map { it.eventId }.toSet()).isEmpty())
            val missing = service.prepareGate(b.client)
            assertFalse(missing.ready)
            assertTrue(missing.reasons.any { it.startsWith("insertion_missing:") })
            assertTrue(missing.reasons.any { it.startsWith("model_observation_missing:") })
            deliver(service, b, observe = false)
            val unobserved = service.prepareGate(b.client)
            assertTrue(unobserved.reasons.any { it.startsWith("model_observation_missing:") })
            var actions = 0
            assertFalse(service.completeGate(b.client, unobserved.cutId) { actions++ }.allowed)
            deliver(service, b)
            val ready = service.prepareGate(b.client)
            assertTrue(ready.ready, ready.reasons.toString())
            assertTrue(service.completeGate(b.client, ready.cutId) { actions++ }.actionPerformed)
            assertEquals(1, actions)
            assertEquals(historical, rawPublicationAudit(service.ledger, completed.reviewId))
            assertEquals(aEvents, events(service, a))
            assertEquals(0, restartedProvider.calls.get())
        }
    }
}

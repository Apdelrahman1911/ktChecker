package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/** Real scheduler/SQLite/archive and receipt paths; provider and fixed source authority are synthetic. */
class SameClientSharedWorkCancellationTest {
    @TempDir lateinit var temp: Path
    private val config = KernelConfig(workerCount = 1, globalParallelism = 1, perWorktreeParallelism = 1,
        heartbeatMillis = 40, leaseMillis = 160, pollMillis = 5, analysisTimeoutMillis = 20_000)

    @Test @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun cancellingOneSameClientReviewCannotCancelOrDischargeItsSharedAndCachedSiblingsAfterReopen() {
        val input = input()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val provider = PlanTestProvider { effective, token ->
            entered.countDown()
            assertTrue(release.await(10, TimeUnit.SECONDS), "The test must release the owned synthetic worker")
            assertFalse(token.isCancelled(), "Cancellation is review-local even when the sibling has the same client ID")
            planPassed(effective)
        }
        val requests = listOf("cancelled", "live-sibling", "cached-sibling").map {
            SubmitRequest("patch-$it", "idempotency-$it", input)
        }
        val root = temp.resolve("ledger")
        val retained = KernelService.open(root, provider, config, FixedFence(input)).use { service ->
            val client = service.registerClient(ClientBinding("owner", "workspace", "tree", "same-client", "lineage",
                sessionId = "same-client-session"))
            try {
                val first = service.submit(client.client, requests[0])
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val sibling = service.submit(client.client, requests[1])
                assertNotEquals(first.reviewId, sibling.reviewId)
                assertEquals("running", sibling.state)
                assertEquals(1, service.ledger.read { c ->
                    c.query("SELECT work_key FROM reviews WHERE client_id=?", client.binding.clientId) { it.getString(1) }.toSet().size
                })

                val withdrawn = service.cancel(client.client, first.reviewId)
                assertTrue(withdrawn.cancelled)
                assertEquals("cancelled", withdrawn.state)
                assertEquals(withdrawn, service.submit(client.client, requests[0]), "An exact request retry must not recreate a live subscription")
                assertFalse(service.status(client.client, sibling.reviewId).cancelled)
                assertEquals("running", service.status(client.client, sibling.reviewId).state)
                release.countDown()

                val cancelled = terminal(service, client, first.reviewId)
                val complete = terminal(service, client, sibling.reviewId)
                assertEquals("cancelled", cancelled.state)
                assertEquals(listOf("client_cancelled_obligation_retained"), cancelled.reasons)
                assertEquals("complete", complete.state)
                assertFalse(complete.cancelled)
                assertEquals("complete", assertNotNull(complete.result).status)
                assertEquals(complete.result, cancelled.result, "Withdrawal must retain the same successful raw evidence, not erase it")
                val cached = service.submit(client.client, requests[2])
                assertEquals("complete", cached.state, "The third review must reuse already terminal work, not wait for another execution")
                assertFalse(cached.cancelled)
                assertEquals(complete.result, cached.result)
                assertEquals(1, provider.calls.get())
                val statuses = listOf(cancelled, complete, cached)
                assertEquals(3, statuses.map { it.reviewId }.toSet().size)
                assertEquals(listOf(1L, 2L, 3L), statuses.map { it.sequence })
                assertEquals(1L, service.ledger.read { it.count("SELECT COUNT(*) FROM work WHERE state='complete'") })
                assertEquals(statuses.map { it.reviewId }, service.ledger.read { c ->
                    c.query("SELECT review_id FROM reviews WHERE client_id=? ORDER BY sequence", client.binding.clientId) { it.getString(1) }
                })

                val events = events(service, client)
                assertEquals((1L..events.size.toLong()).toList(), events.map { it.sequence })
                assertEquals(events.size, events.map { it.eventId }.toSet().size)
                assertEquals(listOf(cancelled.reviewId), events.filter { it.kind == "cancelled" }.map { it.reviewId })
                statuses.forEach { status ->
                    val accepted = events.single { it.reviewId == status.reviewId && it.kind == "accepted" }
                    assertEquals(status.patchId, accepted.patchId)
                    val result = events.single { it.reviewId == status.reviewId && it.kind == "result" }
                    assertEquals(status, ContractJson.decodeFromString<ReviewStatus>(result.payload))
                }
                assertEquals(cancelled, service.cancel(client.client, cancelled.reviewId))
                assertEquals(events, events(service, client), "Cancellation retry must not append another obligation or change sibling events")

                val cachedEvents = events.filter { it.reviewId == cached.reviewId }
                assertEquals(setOf("accepted", "result"), cachedEvents.map { it.kind }.toSet())
                val earlierEvents = events.filter { it.reviewId != cached.reviewId }
                deliver(service, client, earlierEvents, observe = true)
                val undelivered = service.prepareGate(client.client)
                assertEquals(cachedEvents.map { "insertion_missing:${it.eventId}" }.toSet(),
                    undelivered.reasons.filter { it.startsWith("insertion_missing:") }.toSet())
                assertEquals(cachedEvents.map { "model_observation_missing:${it.eventId}" }.toSet(),
                    undelivered.reasons.filter { it.startsWith("model_observation_missing:") }.toSet())
                deliver(service, client, cachedEvents, observe = false)
                val insertedOnly = service.prepareGate(client.client)
                assertTrue(insertedOnly.reasons.none { it.startsWith("insertion_missing:") })
                assertEquals(cachedEvents.map { "model_observation_missing:${it.eventId}" }.toSet(),
                    insertedOnly.reasons.filter { it.startsWith("model_observation_missing:") }.toSet())
                deliver(service, client, cachedEvents, observe = true)
                assertCancellationBlocksAction(service, client, cancelled.reviewId)
                Triple(client, statuses, events)
            } finally { release.countDown() }
        }

        KernelService.open(root, provider, config, FixedFence(input)).use { reopened ->
            val (client, statuses, originalEvents) = retained
            assertEquals(client, reopened.registerClient(client))
            statuses.zip(requests).forEach { (status, request) ->
                assertEquals(status, reopened.status(client.client, status.reviewId))
                assertEquals(status, reopened.submit(client.client, request))
            }
            assertEquals(statuses[0], reopened.cancel(client.client, statuses[0].reviewId))
            assertEquals(originalEvents, events(reopened, client))
            assertCancellationBlocksAction(reopened, client, statuses[0].reviewId)
            assertEquals(1, provider.calls.get(), "Completed shared work must not rerun on idempotent requests or restart")
        }
    }

    private fun input(): AnalysisInput {
        val root = temp.resolve("source")
        Files.createDirectories(root)
        val text = "fun value() = 1\n"
        Files.writeString(root.resolve("Main.kt"), text)
        return AnalysisInput(SnapshotManifest(Digests.sha256("same-client-cancellation:$text"), "workspace", "tree",
            listOf(SnapshotFile("Main.kt", Digests.sha256(text), text.toByteArray().size.toLong())),
            ownerId = "owner", declaredRoots = listOf(".")), root.toString(), AnalysisContext(
            modelDigest = Digests.sha256("synthetic-model"), configurationDigest = Digests.sha256("synthetic-policy"),
            dependencyDigest = Digests.sha256("synthetic-dependencies"), toolchainDigest = Digests.sha256("synthetic-toolchain"),
            fullProject = true), listOf(PLAN_TEST_RULE))
    }

    private class FixedFence(private val expected: AnalysisInput) : SnapshotGate {
        override fun <T> withCurrentSnapshot(input: AnalysisInput, action: (SnapshotCheck) -> T): T = action(
            SnapshotCheck(input == expected, expected.snapshot.snapshotId, 1L, expected.context.configurationDigest,
                expected.context.modelDigest, expected.context.dependencyDigest, expected.context.toolchainDigest,
                "synthetic-same-client-epoch", authoritative = true))
    }

    private fun terminal(service: KernelService, client: ClientRegistration, reviewId: String): ReviewStatus {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var status = service.status(client.client, reviewId)
        // A cancelled status is immediate; wait for its retained raw result, not just that terminal label.
        while (status.result == null && System.nanoTime() < deadline) {
            Thread.sleep(5)
            status = service.status(client.client, reviewId)
        }
        assertNotNull(status.result, "Shared raw evidence was not published for $reviewId")
        return status
    }

    private fun events(service: KernelService, client: ClientRegistration): List<ReviewEvent> = buildList {
        var cursor: String? = null
        do {
            val page = service.events(client.client, cursor, 2)
            addAll(page.events)
            cursor = page.cursor
        } while (page.hasMore)
    }

    private fun deliver(service: KernelService, client: ClientRegistration, events: List<ReviewEvent>, observe: Boolean) {
        for (event in events) {
            assertEquals(client.binding.clientId, event.clientId)
            assertEquals(Digests.sha256(event.payload), event.payloadDigest)
            val digest = Digests.sha256("same-client-context:${event.eventId}:${event.payloadDigest}")
            service.acknowledge(client.adapter, listOf(event.eventId), ContextReceipt("ack-${event.eventId}", digest,
                "context-${event.eventId}", Instant.EPOCH.toString()))
            if (observe) service.observe(client.adapter, listOf(event.eventId), ObservationReceipt("observe-${event.eventId}",
                "model-request-${event.eventId}", listOf(digest)))
        }
    }

    private fun assertCancellationBlocksAction(service: KernelService, client: ClientRegistration, cancelledReviewId: String) {
        val cut = service.prepareGate(client.client)
        assertFalse(cut.ready)
        assertEquals(listOf("review_cancelled:$cancelledReviewId"), cut.reasons)
        var actions = 0
        val decision = service.completeGate(client.client, cut.cutId) { actions++ }
        assertFalse(decision.allowed)
        assertFalse(decision.actionPerformed)
        assertEquals(0, actions)
    }
}

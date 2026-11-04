package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Synthetic attempt permits/provider, actual scheduler cancellation, threads, SQLite and archives. */
class KernelPreparedInputLifecycleTest {
    @TempDir lateinit var temp: Path
    private val config = KernelConfig(workerCount = 2, globalParallelism = 2, perWorktreeParallelism = 2,
        heartbeatMillis = 40, leaseMillis = 160, pollMillis = 5, analysisTimeoutMillis = 10_000)

    private fun input(name: String): AnalysisInput {
        val root = temp.resolve("source-$name")
        Files.createDirectories(root)
        val text = "val value = 1\n"
        Files.writeString(root.resolve("Main.kt"), text)
        return AnalysisInput(SnapshotManifest(Digests.sha256("synthetic-attempt-source:$name"), "workspace", "tree",
            listOf(SnapshotFile("Main.kt", Digests.sha256(text), text.toByteArray().size.toLong())), ownerId = "owner"),
            root.toString(), AnalysisContext(scope = RuleContext(module = ":$name")), listOf(PLAN_TEST_RULE))
    }

    private fun register(service: KernelService, id: String) = service.registerClient(ClientBinding("owner", "workspace", "tree", id, "lineage-$id"))

    private fun awaitResult(service: KernelService, client: ClientRegistration, review: String): ReviewStatus {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var status = service.status(client.client, review)
        while (status.result == null && System.nanoTime() < deadline) { Thread.sleep(5); status = service.status(client.client, review) }
        assertNotNull(status.result, "Synthetic attempt did not publish a terminal result")
        return status
    }

    @Test fun cancellationAfterPreparationButBeforeProviderEntryReleasesItsExactPermitOutsideWriterLocks() {
        val prepared = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val permits = ConcurrentHashMap<String, AnalysisInput>()
        val provider = PlanTestProvider()
        lateinit var service: KernelService
        val observer = object : PreparedInputObserver {
            override fun prepared(original: AnalysisInput, effective: AnalysisInput, verifyFrozen: () -> Unit) {
                verifyFrozen()
                permits[assertNotNull(effective.outputRoot)] = effective
                prepared.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
            override fun finished(effective: AnalysisInput) {
                // Independent transaction proves teardown does not hold the publication lock.
                service.ledger.transaction { it.count("SELECT COUNT(*) FROM reviews") }
                permits.remove(assertNotNull(effective.outputRoot), effective)
                finished.countDown()
            }
        }
        KernelService.open(temp.resolve("ledger"), provider, config.copy(analysisTimeoutMillis = 50), preparedInputObserver = observer).use { opened ->
            service = opened
            val client = register(service, "cancel-before-provider")
            try {
                val accepted = service.submit(client.client, SubmitRequest("cancel-before-provider", "cancel-before-provider", input("cancelled")))
                assertTrue(prepared.await(5, TimeUnit.SECONDS))
                assertEquals(1, permits.size)
                Thread.sleep(100) // The scheduler's real deadline, not a fabricated caller cancellation.
                release.countDown()
                assertTrue(finished.await(5, TimeUnit.SECONDS))
                val status = awaitResult(service, client, accepted.reviewId)
                assertEquals(0, provider.calls.get())
                assertTrue(permits.isEmpty())
                assertTrue("analysis_cancelled_or_deadline_exceeded" in status.result!!.reasons)
                assertEquals("incomplete", status.state)
            } finally { release.countDown() }
        }
    }

    @Test fun partialPreparationThrowStillFinishesAndDoesNotInvokeProvider() {
        val permits = ConcurrentHashMap<String, AnalysisInput>()
        val finished = ConcurrentLinkedQueue<AnalysisInput>()
        val provider = PlanTestProvider()
        val observer = object : PreparedInputObserver {
            override fun prepared(original: AnalysisInput, effective: AnalysisInput, verifyFrozen: () -> Unit) {
                permits[assertNotNull(effective.outputRoot)] = effective
                error("synthetic-partial-allocation-private-diagnostic")
            }
            override fun finished(effective: AnalysisInput) {
                permits.remove(assertNotNull(effective.outputRoot), effective)
                finished += effective
            }
        }
        KernelService.open(temp.resolve("ledger"), provider, config, preparedInputObserver = observer).use { service ->
            val client = register(service, "partial-prepare")
            val accepted = service.submit(client.client, SubmitRequest("partial-prepare", "partial-prepare", input("partial")))
            val status = awaitResult(service, client, accepted.reviewId)
            assertEquals(0, provider.calls.get())
            assertTrue(permits.isEmpty())
            assertEquals(1, finished.size)
            assertTrue("provider_exception" in status.result!!.reasons)
            assertFalse(canonical(status).contains("private-diagnostic"))
        }
    }

    @Test fun providerThrowAndRepeatedExactCleanupCannotRevokeAnotherConcurrentAttempt() {
        val permits = ConcurrentHashMap<String, AnalysisInput>()
        val finished = ConcurrentLinkedQueue<AnalysisInput>()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failedFinished = CountDownLatch(1)
        lateinit var service: KernelService
        val observer = object : PreparedInputObserver {
            override fun prepared(original: AnalysisInput, effective: AnalysisInput, verifyFrozen: () -> Unit) {
                verifyFrozen()
                permits[assertNotNull(effective.outputRoot)] = effective
            }
            override fun finished(effective: AnalysisInput) {
                service.ledger.transaction { it.count("SELECT COUNT(*) FROM reviews") }
                permits.remove(assertNotNull(effective.outputRoot), effective) // Exact and idempotent.
                finished += effective
                if (effective.context.scope.module == ":throws") failedFinished.countDown()
            }
        }
        val provider = PlanTestProvider { effective, _ ->
            if (effective.context.scope.module == ":held") {
                held.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS))
                planPassed(effective)
            } else error("synthetic-provider-failure")
        }
        KernelService.open(temp.resolve("ledger"), provider, config, preparedInputObserver = observer).use { opened ->
            service = opened
            val a = register(service, "held")
            val b = register(service, "throws")
            try {
                val first = service.submit(a.client, SubmitRequest("held", "held", input("held")))
                assertTrue(held.await(5, TimeUnit.SECONDS))
                val second = service.submit(b.client, SubmitRequest("throws", "throws", input("throws")))
                assertTrue(failedFinished.await(5, TimeUnit.SECONDS))
                val failure = awaitResult(service, b, second.reviewId)
                assertTrue("provider_exception" in failure.result!!.reasons)
                assertEquals(1, permits.size)
                assertEquals(":held", permits.values.single().context.scope.module)
                observer.finished(finished.single { it.context.scope.module == ":throws" })
                assertEquals(1, permits.size, "Duplicate teardown must not clear another live grant")
                release.countDown()
                assertNotNull(awaitResult(service, a, first.reviewId).result)
                assertTrue(permits.isEmpty())
            } finally { release.countDown() }
        }
    }

    @Test fun cleanupFailurePreservesRawTerminalEvidenceAndAddsAFailClosedBrokerReason() {
        val observer = object : PreparedInputObserver {
            override fun prepared(original: AnalysisInput, effective: AnalysisInput, verifyFrozen: () -> Unit) { verifyFrozen() }
            override fun finished(effective: AnalysisInput) { error("synthetic-private-cleanup-diagnostic") }
        }
        val provider = PlanTestProvider { input, _ ->
            val file = input.snapshot.files.first()
            val finding = Finding(PLAN_TEST_RULE, "Synthetic", "Preserve this finding", "Test only", SourceLocation(file.path, file.digest),
                Digests.sha256("synthetic-cleanup-evidence"), PLAN_TEST_PROVIDER, context = input.context.scope)
            planPassed(input, listOf(finding))
        }
        KernelService.open(temp.resolve("ledger"), provider, config, preparedInputObserver = observer).use { service ->
            val client = register(service, "cleanup-failure")
            val accepted = service.submit(client.client, SubmitRequest("cleanup-failure", "cleanup-failure", input("cleanup")))
            val status = awaitResult(service, client, accepted.reviewId)
            assertEquals("incomplete", status.state)
            assertTrue("prepared_input_cleanup_failed" in status.reasons)
            assertEquals("complete", status.result!!.status, "Raw provider terminal evidence must not be replaced by teardown failure")
            assertEquals("Preserve this finding", status.result!!.findings.single().diagnosis)
            assertEquals(1, status.result!!.coverage.size)
            assertFalse(canonical(status).contains("private-cleanup-diagnostic"))
        }
    }
}

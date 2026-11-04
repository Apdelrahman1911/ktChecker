package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Real SQLite, archives, threads and restart; synthetic providers, fences and model observations.
 * This is a generic-kernel evaluator-upgrade regression, NOT a demonstrated shipped-CLI bypass. */
class KernelPolicyEvaluatorTest {
    @TempDir lateinit var temp: Path
    private val config = KernelConfig(workerCount = 1, globalParallelism = 1, perWorktreeParallelism = 1,
        heartbeatMillis = 30, leaseMillis = 120, pollMillis = 5, analysisTimeoutMillis = 15_000)

    @Test fun completedAcceptanceCannotAuthorizeReopenedStricterEvaluatorOrPreviouslyReadyCut() {
        val original = completed("completed-upgrade")
        val calls = AtomicInteger()
        val stricter = ResultPolicy.versioned(EVALUATOR_B) { _, _, raw -> calls.incrementAndGet(); raw }
        KernelService.open(original.root, original.provider, config.copy(workerCount = 0), SyntheticFence(original.input),
            resultPolicy = stricter).use { service ->
            val status = service.status(original.client.client, original.status.reviewId)
            assertEquals("incomplete", status.state)
            assertTrue("policy_evaluator_mismatch" in status.reasons)
            assertEquals(original.status.result, status.result, "Stored historical acceptance is not rewritten as new consent")
            assertEquals(status, service.submit(original.client.client, original.request))
            assertEquals(original.retained, retained(service, status.reviewId))
            assertEquals(original.events, events(service, original.client))
            assertEquals(0, calls.get(), "An idempotent A review must not be silently evaluated as B")
            assertEquals(1, original.provider.calls.get())

            val cut = service.prepareGate(original.client.client)
            assertFalse(cut.ready)
            assertTrue("policy_evaluator_mismatch:${status.reviewId}" in cut.reasons)
            assertTrue(cut.reasons.none { it.startsWith("insertion_missing:") || it.startsWith("model_observation_missing:") })
            assertFalse("current_source_configuration_authority_unavailable" in cut.reasons)
            val actions = AtomicInteger()
            for (id in listOf(original.cut.cutId, cut.cutId)) {
                val completion = service.completeGate(original.client.client, id) { actions.incrementAndGet() }
                assertFalse(completion.allowed, completion.reasons.toString())
                assertFalse(completion.actionPerformed)
                assertTrue("policy_evaluator_mismatch:${status.reviewId}" in completion.reasons)
            }
            assertEquals(0, actions.get(), "The ready A cut is not authority for an action under B")
        }
    }

    @Test fun freshStricterRawCacheReviewRetainsOwnClassificationAndDoesNotPoisonUnrelatedLineages() {
        val original = completed("raw-cache-upgrade")
        val calls = ConcurrentLinkedQueue<String>()
        val policy = ResultPolicy.versioned(EVALUATOR_B) { binding, _, raw ->
            calls += binding.clientId
            if (binding.clientId == "b-accepted") accepted(raw) else raw
        }
        KernelService.open(original.root, original.provider, config.copy(workerCount = 0), SyntheticFence(original.input),
            resultPolicy = policy).use { service ->
            val strict = service.registerClient(binding("b-strict"))
            val strictStatus = service.submit(strict.client, request("b-strict", original.input))
            assertEquals("complete", strictStatus.state, strictStatus.reasons.toString())
            assertEquals("open", strictStatus.result!!.findings.single().disposition)
            assertEquals(original.retained.raw, retained(service, strictStatus.reviewId).raw)
            assertEquals(1, original.provider.calls.get(), "Policy identity must not destroy raw-cache reuse")
            val undelivered = service.prepareGate(strict.client)
            assertTrue(undelivered.reasons.any { it.startsWith("model_observation_missing:") }, "A's receipts cannot deliver B's results")
            observeAll(service, strict)
            val strictCut = service.prepareGate(strict.client)
            assertFalse(strictCut.ready)
            assertEquals(listOf("open_finding:${strictStatus.result!!.findings.single().fingerprint}"), strictCut.reasons)

            // A different B-only client with its own captured acceptance must not be blocked merely
            // because this ledger also contains an unrelated historical A obligation.
            val allowed = service.registerClient(binding("b-accepted"))
            val allowedStatus = service.submit(allowed.client, request("b-accepted", original.input))
            assertEquals("accepted", allowedStatus.result!!.findings.single().disposition)
            observeAll(service, allowed)
            val allowedCut = service.prepareGate(allowed.client)
            assertTrue(allowedCut.ready, allowedCut.reasons.toString())
            val actions = AtomicInteger()
            assertTrue(service.completeGate(allowed.client, allowedCut.cutId) { actions.incrementAndGet() }.actionPerformed)
            assertEquals(1, actions.get())

            // Even fresh B evidence in the SAME client does not silently waive required A history.
            val later = service.submit(original.client.client, request("later-b", original.input))
            assertEquals("complete", later.state)
            observeAll(service, original.client)
            assertTrue("policy_evaluator_mismatch:${original.status.reviewId}" in service.prepareGate(original.client.client).reasons)
            assertEquals(original.retained, retained(service, original.status.reviewId))
            assertEquals(listOf("b-strict", "b-accepted", original.client.binding.clientId), calls.toList())
            assertEquals(1, original.provider.calls.get())
        }
    }

    @Test fun declaredIdentitySurvivesRestartWithoutReevaluatingOrDuplicatingHistoricalEvents() {
        val original = completed("same-evaluator")
        val calls = AtomicInteger()
        val same = ResultPolicy.versioned(EVALUATOR_A) { _, _, raw -> calls.incrementAndGet(); accepted(raw) }
        KernelService.open(original.root, original.provider, config.copy(workerCount = 0), SyntheticFence(original.input),
            resultPolicy = same).use { service ->
            assertEquals(original.status, service.submit(original.client.client, original.request))
            assertEquals(original.retained, retained(service, original.status.reviewId))
            assertEquals(original.events, events(service, original.client))
            val actions = AtomicInteger()
            val result = service.completeGate(original.client.client, original.cut.cutId) { actions.incrementAndGet() }
            assertTrue(result.allowed, result.reasons.toString())
            assertTrue(result.actionPerformed)
            assertEquals(1, actions.get())
            assertEquals(0, calls.get())
        }
    }

    @Test fun unversionedSamRemainsInstanceScopedEvenWhenTheSameObjectIsReused() {
        val calls = AtomicInteger()
        val unknown = ResultPolicy { _, _, raw -> calls.incrementAndGet(); accepted(raw) }
        assertNull(unknown.evaluatorIdentity)
        val original = completed("unversioned", unknown)
        assertEquals(1, calls.get())
        KernelService.open(original.root, original.provider, config.copy(workerCount = 0), SyntheticFence(original.input),
            resultPolicy = unknown).use { service ->
            assertTrue("policy_evaluator_mismatch" in service.status(original.client.client, original.status.reviewId).reasons)
            assertFalse(service.completeGate(original.client.client, original.cut.cutId).allowed)
            assertEquals(original.retained, retained(service, original.status.reviewId))
            assertEquals(1, calls.get())
        }
    }

    @Test fun inFlightForeignWorkerNeverClassifiesSubscriberProgressOrCompletionWithItsOwnEvaluator() {
        val input = input("live-sharing")
        val root = temp.resolve("ledger-live-sharing")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val provider = Provider(entered, release, progressive = true)
        val aCalls = ConcurrentLinkedQueue<String>()
        val bCalls = ConcurrentLinkedQueue<String>()
        val aPolicy = ResultPolicy.versioned(EVALUATOR_A) { binding, _, raw -> aCalls += binding.clientId; accepted(raw) }
        val bPolicy = ResultPolicy.versioned(EVALUATOR_B) { binding, _, raw -> bCalls += binding.clientId; raw }
        KernelService.open(root, provider, config, SyntheticFence(input), resultPolicy = aPolicy).use { a ->
            val aClient = a.registerClient(binding("live-a"))
            val aReview = a.submit(aClient.client, request("live-a", input))
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            try {
                KernelService.open(root, provider, config.copy(workerCount = 0), SyntheticFence(input), resultPolicy = bPolicy).use { b ->
                    val bClient = b.registerClient(binding("live-b"))
                    val bReview = b.submit(bClient.client, request("live-b", input))
                    val admission = assertNotNull(PolicyCertificate.decode(retained(b, bReview.reviewId).certificate))
                    assertEquals(PolicyCertificate.ADMITTED, admission.state)
                    assertEquals(b.policyEvaluatorIdentity, admission.evaluatorIdentity)
                    assertNotEquals(a.policyEvaluatorIdentity, admission.evaluatorIdentity)
                    release.countDown()
                    val aResult = awaitEvaluated(a, aClient, aReview.reviewId)
                    val bResult = awaitEvaluated(b, bClient, bReview.reviewId)
                    assertEquals("accepted", aResult.result!!.findings.single().disposition)
                    assertEquals("open", bResult.result!!.findings.single().disposition)
                    assertEquals("complete", bResult.state, bResult.reasons.toString())
                    assertEquals(listOf("live-a", "live-a"), aCalls.toList(), "A may classify only its own progress and terminal result")
                    assertEquals(listOf("live-b"), bCalls.toList(), "B classifies the raw terminal result once")
                    assertEquals(1, provider.calls.get())
                    assertEquals(retained(a, aReview.reviewId).raw, retained(b, bReview.reviewId).raw)

                    val bEvents = events(b, bClient)
                    assertEquals(listOf("accepted", "progress", "result", "result"), bEvents.map { it.kind })
                    val published = bEvents.drop(1).map { ContractJson.decodeFromString<ReviewStatus>(it.payload) }
                    assertTrue(published.all { it.result!!.findings.single().disposition == "open" })
                    assertTrue(published.take(2).all { "policy_evaluator_mismatch" in it.reasons })
                    assertEquals("incomplete", published[1].state)
                    assertEquals(bResult, published.last())
                    assertTrue(bEvents.all { it.deliveryRequired })
                    observeAll(a, aClient)
                    assertTrue(a.prepareGate(aClient.client).ready)
                    assertTrue(b.prepareGate(bClient.client).reasons.any { it.startsWith("model_observation_missing:") })
                    observeAll(b, bClient)
                    assertEquals(listOf("open_finding:${bResult.result!!.findings.single().fingerprint}"), b.prepareGate(bClient.client).reasons)
                    assertTrue("policy_evaluator_mismatch" in a.status(bClient.client, bReview.reviewId).reasons)
                }
            } finally { release.countDown() }
        }
    }

    @Test fun matchingEvaluatorRecoversDeferredRawWorkOnceAcrossConcurrentReopensWithoutProviderReplay() {
        val calls = AtomicInteger()
        val restores = AtomicInteger()
        val readyPolicy = AtomicBoolean()
        val bPolicy = ResultPolicy.versioned(EVALUATOR_B, object : ResultPolicy {
            override fun isReady(binding: ClientBinding, input: AnalysisInput): Boolean = readyPolicy.get()
            override fun restoreCaptured(binding: ClientBinding, input: AnalysisInput) {
                restores.incrementAndGet()
                readyPolicy.set(true)
            }
            override fun apply(binding: ClientBinding, input: AnalysisInput, raw: ProviderResult): ProviderResult {
                calls.incrementAndGet()
                return accepted(raw)
            }
        })
        val deferred = deferred("racing-recovery", bPolicy)
        assertEquals(0, calls.get())
        val opened = ConcurrentLinkedQueue<KernelService>()
        val pool = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val release = CountDownLatch(1)
        try {
            val futures = (1..2).map {
                pool.submit(Callable {
                    ready.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    KernelService.open(deferred.root, deferred.provider, deferred.config.copy(workerCount = 0), SyntheticFence(deferred.input),
                        preparedInputObserver = PreparedInputObserver { _, _, _ -> error("Raw recovery must not prepare or run a provider") },
                        resultPolicy = bPolicy).also { opened += it }
                })
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            release.countDown()
            val services = futures.map { it.get(15, TimeUnit.SECONDS) }
            val result = awaitEvaluated(services.first(), deferred.client, deferred.reviewId)
            assertEquals("complete", result.state, result.reasons.toString())
            repeat(3) { services.forEach { service -> service.recoverPolicyEvaluations() } }
            assertEquals(1, calls.get(), "Two matching instances cannot publish/evaluate the same completed admission twice")
            assertTrue(restores.get() in 1..2, "Concurrent exact restoration is idempotent; evaluated rows are never restored again")
            assertEquals(1, deferred.provider.calls.get())
            assertEquals(deferred.retained.raw, retained(services.first(), deferred.reviewId).raw)
            val certificate = assertNotNull(PolicyCertificate.decode(retained(services.first(), deferred.reviewId).certificate))
            assertEquals(PolicyCertificate.EVALUATED, certificate.state)
            assertEquals(PolicyCertificate.decode(deferred.retained.certificate)!!.evaluatorIdentity, certificate.evaluatorIdentity)
            assertEquals(listOf("accepted", "result", "result"), events(services.first(), deferred.client).map { it.kind })
            val stale = services.first().completeGate(deferred.client.client, deferred.cut.cutId)
            assertFalse(stale.allowed)
            assertTrue("lineage_epoch_changed" in stale.reasons)
            observeAll(services.first(), deferred.client)
            assertTrue(services.first().prepareGate(deferred.client.client).ready)
        } finally {
            release.countDown()
            pool.shutdownNow()
            try { assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS)) }
            finally { opened.forEach { it.close() } }
        }
    }

    @Test fun arbitraryPurePolicyFailureRemainsTerminalIncompleteRatherThanRetryingOrLoadingAuthority() {
        for (readinessFailure in listOf(false, true)) for (assertion in listOf(false, true)) {
            val calls = AtomicInteger()
            fun failPolicy(): Nothing {
                calls.incrementAndGet()
                if (assertion) throw AssertionError("Synthetic pure evaluator bug")
                else error("Synthetic pure evaluator bug")
            }
            val bPolicy = ResultPolicy.versioned(EVALUATOR_B, object : ResultPolicy {
                override fun isReady(binding: ClientBinding, input: AnalysisInput): Boolean = if (readinessFailure) failPolicy() else true
                override fun apply(binding: ClientBinding, input: AnalysisInput, raw: ProviderResult): ProviderResult = failPolicy()
            })
            val deferred = deferred("unavailable-$readinessFailure-$assertion", bPolicy)
            KernelService.open(deferred.root, deferred.provider, config.copy(workerCount = 0), SyntheticFence(deferred.input),
                preparedInputObserver = PreparedInputObserver { _, _, _ -> error("No provider preparation in policy-only recovery") },
                resultPolicy = bPolicy).use { service ->
                val status = awaitEvaluated(service, deferred.client, deferred.reviewId)
                assertEquals("incomplete", status.state)
                assertEquals(listOf("result_policy_unavailable"), status.reasons)
                assertEquals("open", status.result!!.findings.single().disposition)
                repeat(3) { service.recoverPolicyEvaluations() }
                assertEquals(status, service.submit(deferred.client.client, deferred.request))
                assertEquals(1, calls.get())
                assertEquals(1, deferred.provider.calls.get())
                assertEquals(deferred.retained.raw, retained(service, deferred.reviewId).effective)
                assertEquals(listOf("accepted", "result", "result"), events(service, deferred.client).map { it.kind })
                observeAll(service, deferred.client)
                assertTrue("review_incomplete:${status.reviewId}" in service.prepareGate(deferred.client.client).reasons)
            }
        }
    }

    @Test fun cancelledDeferredPolicyStillConsumesPendingQuotaAndRecoveryPreservesCancellation() {
        val limited = config.copy(maxPendingPerClient = 1)
        val bPolicy = ResultPolicy.versioned(EVALUATOR_B) { _, _, raw -> accepted(raw) }
        val deferred = deferred("cancelled-pending", bPolicy, limited, cancelled = true)
        KernelService.open(deferred.root, deferred.provider, limited.copy(workerCount = 0), SyntheticFence(deferred.input),
            resultPolicy = accepting()).use { foreign ->
            val before = events(foreign, deferred.client)
            assertEquals("client_queue_full", assertFailsWith<KernelException> {
                foreign.submit(deferred.client.client, request("over-quota", deferred.input))
            }.code)
            assertEquals(before, events(foreign, deferred.client))
            assertEquals(deferred.retained, retained(foreign, deferred.reviewId))
        }
        KernelService.open(deferred.root, deferred.provider, limited.copy(workerCount = 0), SyntheticFence(deferred.input),
            resultPolicy = bPolicy).use { service ->
            val status = awaitEvaluated(service, deferred.client, deferred.reviewId)
            assertEquals("cancelled", status.state)
            assertTrue(status.cancelled)
            assertEquals("accepted", status.result!!.findings.single().disposition)
            observeAll(service, deferred.client)
            val cut = service.prepareGate(deferred.client.client)
            assertFalse(cut.ready)
            assertTrue("review_cancelled:${status.reviewId}" in cut.reasons)
            assertTrue(cut.reasons.none { it.startsWith("policy_evaluator_") || it.startsWith("model_observation_missing:") })
        }
    }

    @Test fun notReadyProgressAndTerminalRemainPendingUntilCapturedPreparationBecomesReady() {
        val input = input("not-ready")
        val provider = Provider(progressive = true)
        val binding = binding("not-ready-owner")
        val ready = AtomicBoolean()
        val available = AtomicBoolean()
        val restores = AtomicInteger()
        val evaluations = AtomicInteger()
        val policy = ResultPolicy.versioned(EVALUATOR_B, object : ResultPolicy {
            override fun isReady(binding: ClientBinding, input: AnalysisInput): Boolean = ready.get()
            override fun restoreCaptured(actualBinding: ClientBinding, actualInput: AnalysisInput) {
                assertEquals(binding, actualBinding)
                assertEquals(input, actualInput)
                restores.incrementAndGet()
                if (available.get()) ready.set(true)
            }
            override fun apply(binding: ClientBinding, input: AnalysisInput, raw: ProviderResult): ProviderResult {
                assertTrue(ready.get())
                evaluations.incrementAndGet()
                return accepted(raw)
            }
        })
        val limited = config.copy(maxPendingPerClient = 1)
        KernelService.open(temp.resolve("ledger-not-ready"), provider, limited, SyntheticFence(input), resultPolicy = policy).use { service ->
            val client = service.registerClient(binding)
            val review = service.submit(client.client, request("not-ready", input))
            awaitCondition("Raw provider completion was not retained") { retained(service, review.reviewId).raw != null }
            val pending = retained(service, review.reviewId)
            assertNull(pending.effective)
            assertEquals(PolicyCertificate.ADMITTED, PolicyCertificate.decode(pending.certificate)!!.state)
            val history = events(service, client)
            assertEquals(listOf("accepted", "progress", "result"), history.map { it.kind })
            val progress = ContractJson.decodeFromString<ReviewStatus>(history[1].payload)
            assertEquals("open", progress.result!!.findings.single().disposition)
            assertTrue("captured_policy_not_ready" in progress.reasons)
            assertTrue("captured_policy_not_ready" in service.status(client.client, review.reviewId).reasons)
            assertEquals(0, evaluations.get(), "Not-ready is not an arbitrary callback failure")
            val epoch = service.ledger.read { it.count("SELECT epoch FROM clients WHERE client_id=?", binding.clientId) }
            repeat(3) { service.recoverPolicyEvaluations() }
            assertTrue(restores.get() >= 3)
            assertEquals(history, events(service, client), "Unchanged pending state must not churn required result events")
            assertEquals(epoch, service.ledger.read { it.count("SELECT epoch FROM clients WHERE client_id=?", binding.clientId) })
            assertEquals("client_queue_full", assertFailsWith<KernelException> {
                service.submit(client.client, request("over-not-ready-quota", input))
            }.code)
            observeAll(service, client)
            val oldCut = service.prepareGate(client.client)
            assertFalse(oldCut.ready)

            available.set(true)
            service.recoverPolicyEvaluations()
            val status = awaitEvaluated(service, client, review.reviewId)
            assertEquals("complete", status.state, status.reasons.toString())
            assertEquals("accepted", status.result!!.findings.single().disposition)
            assertEquals(pending.raw, retained(service, review.reviewId).raw)
            assertEquals(1, evaluations.get())
            assertEquals(1, provider.calls.get())
            assertEquals(history + events(service, client).last(), events(service, client))
            val stale = service.completeGate(client.client, oldCut.cutId)
            assertFalse(stale.allowed)
            assertTrue("lineage_epoch_changed" in stale.reasons)
            assertTrue(service.prepareGate(client.client).reasons.any { it.startsWith("model_observation_missing:") })
            observeAll(service, client)
            assertTrue(service.prepareGate(client.client).ready)
        }
    }

    @Test fun restorationFailuresAndNotReadyTransitionsHaveBoundedAuditAndCanRecoverLater() {
        val phase = AtomicInteger()
        val ready = AtomicBoolean()
        val evaluations = AtomicInteger()
        val policy = ResultPolicy.versioned(EVALUATOR_B, object : ResultPolicy {
            override fun isReady(binding: ClientBinding, input: AnalysisInput): Boolean = ready.get()
            override fun restoreCaptured(binding: ClientBinding, input: AnalysisInput) {
                when (phase.get()) {
                    0 -> error("Synthetic archived preparation temporarily unavailable")
                    1 -> Unit
                    else -> ready.set(true)
                }
            }
            override fun apply(binding: ClientBinding, input: AnalysisInput, raw: ProviderResult): ProviderResult {
                evaluations.incrementAndGet()
                return accepted(raw)
            }
        })
        val deferred = deferred("restore-retry", policy)
        KernelService.open(deferred.root, deferred.provider, config.copy(workerCount = 0), SyntheticFence(deferred.input),
            preparedInputObserver = PreparedInputObserver { _, _, _ -> error("No provider or execution-authority restoration") },
            resultPolicy = policy).use { service ->
            awaitCondition("Missing preparation was not recorded") {
                "captured_policy_restore_unavailable" in service.status(deferred.client.client, deferred.reviewId).reasons
            }
            val failed = events(service, deferred.client)
            repeat(3) { service.recoverPolicyEvaluations() }
            assertEquals(failed, events(service, deferred.client))
            assertNull(retained(service, deferred.reviewId).effective)
            phase.set(1)
            service.recoverPolicyEvaluations()
            val pending = events(service, deferred.client)
            assertEquals(failed.size + 1, pending.size)
            assertEquals(setOf("captured_policy_restore_unavailable", "captured_policy_not_ready", "policy_evaluation_missing"),
                service.status(deferred.client.client, deferred.reviewId).reasons.toSet())
            repeat(4) { index -> phase.set(index % 2); service.recoverPolicyEvaluations() }
            assertEquals(pending, events(service, deferred.client), "Alternating retry failures cannot create unlimited required events")
            assertEquals(0, evaluations.get())
            assertEquals(deferred.retained.certificate, retained(service, deferred.reviewId).certificate)

            phase.set(2)
            service.recoverPolicyEvaluations()
            val status = awaitEvaluated(service, deferred.client, deferred.reviewId)
            assertEquals("complete", status.state, status.reasons.toString())
            assertTrue(status.reasons.isEmpty())
            assertEquals("accepted", status.result!!.findings.single().disposition)
            assertEquals(1, evaluations.get())
            assertEquals(1, deferred.provider.calls.get())
            assertEquals(pending.size + 1, events(service, deferred.client).size)
        }
    }

    @Test fun blockedCapturedRestoreLeavesHeartbeatAndLedgerResponsiveAndCannotPublishAfterClose() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val thread = AtomicReference<Thread>()
        val ready = AtomicBoolean()
        val evaluations = AtomicInteger()
        val policy = ResultPolicy.versioned(EVALUATOR_B, object : ResultPolicy {
            override fun isReady(binding: ClientBinding, input: AnalysisInput): Boolean = ready.get()
            override fun restoreCaptured(binding: ClientBinding, input: AnalysisInput) {
                thread.set(Thread.currentThread())
                entered.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                // Deliberately uncooperative unit adapter: close must fence its late return, not
                // publish a result after abandoning the bounded join. Always released in finally.
                while (release.count != 0L && System.nanoTime() < deadline) {
                    try { release.await(100, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { interrupted.countDown() }
                }
                check(release.count == 0L)
                ready.set(true)
            }
            override fun apply(binding: ClientBinding, input: AnalysisInput, raw: ProviderResult): ProviderResult {
                evaluations.incrementAndGet()
                return accepted(raw)
            }
        })
        val deferred = deferred("blocked-restore", policy)
        val service = KernelService.open(deferred.root, deferred.provider, config.copy(workerCount = 0), SyntheticFence(deferred.input),
            preparedInputObserver = PreparedInputObserver { _, _, _ -> error("Policy-only recovery cannot replay preparation") }, resultPolicy = policy)
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS), "Opening the service must not synchronously block on restoration")
            val heartbeat = service.ledger.read { it.count("SELECT MAX(heartbeat) FROM instances WHERE closed=0") }
            awaitCondition("Blocked restoration held up the independent heartbeat") {
                service.ledger.read { it.count("SELECT MAX(heartbeat) FROM instances WHERE closed=0") } > heartbeat + config.heartbeatMillis
            }
            assertEquals("during-restore", service.registerClient(binding("during-restore")).binding.clientId)
            service.cancel(deferred.client.client, deferred.reviewId)
            val pending = retained(service, deferred.reviewId)
            val eventCount = service.ledger.read { it.count("SELECT COUNT(*) FROM events") }
            val epoch = service.ledger.read { it.count("SELECT epoch FROM clients WHERE client_id=?", deferred.client.binding.clientId) }
            val start = System.nanoTime()
            service.close()
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5), "Close must retain a bounded shared join budget")
            assertTrue(interrupted.await(1, TimeUnit.SECONDS))
            assertTrue(thread.get().isAlive)
            release.countDown()
            thread.get().join(10_000)
            assertFalse(thread.get().isAlive)
            assertEquals(pending, retained(service, deferred.reviewId), "Late restoration must not publish after stop")
            assertEquals(eventCount, service.ledger.read { it.count("SELECT COUNT(*) FROM events") })
            assertEquals(epoch, service.ledger.read { it.count("SELECT epoch FROM clients WHERE client_id=?", deferred.client.binding.clientId) })
            assertEquals(0, evaluations.get())
            assertEquals(1, deferred.provider.calls.get())
        } finally {
            release.countDown()
            service.close()
            thread.get()?.join(10_000)
        }
        KernelService.open(deferred.root, deferred.provider, config.copy(workerCount = 0), SyntheticFence(deferred.input),
            resultPolicy = ResultPolicy.versioned(EVALUATOR_B) { _, _, raw -> accepted(raw) }).use { restored ->
            val status = awaitEvaluated(restored, deferred.client, deferred.reviewId)
            assertEquals("cancelled", status.state)
            assertTrue(status.cancelled)
            assertEquals("accepted", status.result!!.findings.single().disposition)
            observeAll(restored, deferred.client)
            assertTrue("review_cancelled:${status.reviewId}" in restored.prepareGate(deferred.client.client).reasons)
        }
    }

    @Test fun durableRecoveryCursorReachesReadyAdmissionAfterThirtyTwoUnreadyRowsAcrossRestart() {
        val input = input("fair-policy")
        val root = temp.resolve("ledger-fair-policy")
        val rawEntered = CountDownLatch(1)
        val rawRelease = CountDownLatch(1)
        val provider = Provider(rawEntered, rawRelease)
        val admissions = mutableListOf<Pair<ClientRegistration, String>>()
        val restored = ConcurrentLinkedQueue<String>()
        val evaluations = AtomicInteger()
        val slow = config.copy(heartbeatMillis = 60_000, leaseMillis = 180_000)
        val policy = ResultPolicy.versioned(EVALUATOR_B, object : ResultPolicy {
            override fun isReady(binding: ClientBinding, input: AnalysisInput): Boolean = binding.clientId == "fair-33"
            override fun restoreCaptured(binding: ClientBinding, input: AnalysisInput) { restored += binding.clientId }
            override fun apply(binding: ClientBinding, input: AnalysisInput, raw: ProviderResult): ProviderResult {
                evaluations.incrementAndGet()
                return accepted(raw)
            }
        })
        KernelService.open(root, provider, config, SyntheticFence(input), resultPolicy = accepting()).use { a ->
            val aClient = a.registerClient(binding("fair-raw-owner"))
            val rawReview = a.submit(aClient.client, request("fair-raw", input))
            assertTrue(rawEntered.await(10, TimeUnit.SECONDS))
            try {
                KernelService.open(root, provider, slow.copy(workerCount = 0), SyntheticFence(input), resultPolicy = policy).use { b ->
                    (1..33).forEach { index ->
                        val client = b.registerClient(binding("fair-$index"))
                        admissions += client to b.submit(client.client, request("fair-$index", input)).reviewId
                    }
                }
                assertTrue(restored.isEmpty(), "No raw result was available for policy restoration")
                rawRelease.countDown()
                awaitEvaluated(a, aClient, rawReview.reviewId)
                assertTrue(admissions.all { retained(a, it.second).effective == null })
            } finally { rawRelease.countDown() }
        }
        KernelService.open(root, provider, slow.copy(workerCount = 0), SyntheticFence(input), resultPolicy = policy).use { first ->
            awaitCondition("The first bounded batch did not finish") {
                first.ledger.read { it.count("SELECT COUNT(*) FROM reviews WHERE policy_validation_json=?",
                    canonical(listOf("captured_policy_not_ready"))) } == 32L
            }
            assertEquals((1..32).map { "fair-$it" }, restored.toList())
            assertNull(retained(first, admissions.last().second).effective)
            assertEquals(0, evaluations.get())
            assertEquals(1L, first.ledger.read { it.count("SELECT COUNT(*) FROM metadata WHERE key LIKE 'policy_recovery_cursor:%'") })
        }
        restored.clear()
        KernelService.open(root, provider, slow.copy(workerCount = 0), SyntheticFence(input), resultPolicy = policy).use { next ->
            val (client, reviewId) = admissions.last()
            val result = awaitEvaluated(next, client, reviewId)
            assertEquals("fair-33", restored.first(), "Persistent rotation must skip the already-attempted first batch")
            assertEquals("complete", result.state, result.reasons.toString())
            assertEquals("accepted", result.result!!.findings.single().disposition)
            assertTrue(admissions.dropLast(1).all { retained(next, it.second).effective == null })
            assertTrue(restored.size <= 32, "One restart must not restore an unbounded startup inventory")
            assertEquals(1, evaluations.get())
            assertEquals(1, provider.calls.get())
            assertEquals(listOf("accepted", "result", "result"), events(next, client).map { it.kind })
        }
    }

    @Test fun parentQuotaRejectsUnreadyCachedAndUnsupportedFanoutWithoutInstallingOrphanArchives() {
        val limited = config.copy(maxPendingPerClient = 1)
        val original = completed("parent-cache-quota", limits = limited)
        val secondInput = original.input.copy(context = original.input.context.copy(scope = RuleContext(module = ":cached-second")))
        KernelService.open(original.root, original.provider, limited, SyntheticFence(secondInput), resultPolicy = accepting()).use { a ->
            awaitEvaluated(a, original.client, a.submit(original.client.client, request("cached-second", secondInput)).reviewId)
        }
        val policy = ResultPolicy.versioned(EVALUATOR_B, object : ResultPolicy {
            override fun isReady(binding: ClientBinding, input: AnalysisInput): Boolean = false
            override fun apply(binding: ClientBinding, input: AnalysisInput, raw: ProviderResult): ProviderResult = error("Unready policy must not run")
        })
        val cases = listOf("cached", "mixed", "unsupported")
        for (kind in cases) {
            val binding = binding("parent-cache-owner-$kind")
            val request = SubmitPlanRequest("parent-$kind", "parent-$kind-key", "parent-$kind-ref")
            val inputs = when (kind) {
                "cached" -> listOf(original.input, secondInput)
                "mixed" -> listOf(original.input, original.input.copy(context = original.input.context.copy(scope = RuleContext(module = ":new-mixed"))))
                else -> (1..2).map { index -> original.input.copy(context = original.input.context.copy(scope = RuleContext(module = ":unsupported-$index"))) }
            }
            val executable = plan(binding, request, inputs)
            val definition = if (kind == "unsupported") executable.copy(slots = executable.slots.map {
                it.copy(identity = null, state = "unsupported", reasons = listOf("synthetic_unsupported_scope"))
            }) else executable
            KernelService.open(original.root, original.provider, limited.copy(workerCount = 0), SyntheticFence(original.input),
                resultPolicy = policy, planAuthority = ReviewPlanAuthority { _, _ -> definition }).use { service ->
                val owner = service.registerClient(binding)
                val before = service.ledger.read { listOf(it.count("SELECT COUNT(*) FROM reviews"), it.count("SELECT COUNT(*) FROM work"),
                    it.count("SELECT COUNT(*) FROM events"), it.count("SELECT COUNT(*) FROM parent_plans")) }
                val archives = archiveFootprint(original.root)
                var inserted = 0
                service.plans.afterSlotInsert = { inserted++ }
                repeat(2) {
                    assertEquals("client_queue_full", assertFailsWith<KernelException> { service.submitPlan(owner.client, request) }.code)
                    assertEquals(before, service.ledger.read { c -> listOf(c.count("SELECT COUNT(*) FROM reviews"), c.count("SELECT COUNT(*) FROM work"),
                        c.count("SELECT COUNT(*) FROM events"), c.count("SELECT COUNT(*) FROM parent_plans")) })
                    assertEquals(archives, archiveFootprint(original.root), "Rejected $kind fanout must not grow uncharged archive destinations")
                }
                assertEquals(4, inserted, "Both attempts reached the post-admission check, not merely raw-work preflight")
            }
        }
        assertEquals(2, original.provider.calls.get())
    }

    @Test fun parentPlanQuotaIncludesCompletedRawReviewsStillAwaitingTheirAdmittedEvaluator() {
        val limited = config.copy(maxPendingPerClient = 2)
        val deferred = deferred("parent-pending-quota", ResultPolicy.versioned(EVALUATOR_B) { _, _, raw -> raw }, limited)
        val planRequest = SubmitPlanRequest("parent-over-quota", "parent-over-quota-key", "parent-over-quota-ref")
        val newInputs = (1..2).map { index -> deferred.input.copy(context = deferred.input.context.copy(scope = RuleContext(module = ":new-$index"))) }
        val plan = plan(deferred.client.binding, planRequest, newInputs)
        KernelService.open(deferred.root, deferred.provider, limited.copy(workerCount = 0), SyntheticFence(deferred.input),
            resultPolicy = accepting(), planAuthority = ReviewPlanAuthority { _, _ -> plan }).use { service ->
            val before = service.ledger.read { it.count("SELECT COUNT(*) FROM reviews") to it.count("SELECT COUNT(*) FROM work") }
            assertEquals("client_queue_full", assertFailsWith<KernelException> { service.submitPlan(deferred.client.client, planRequest) }.code)
            assertEquals(before, service.ledger.read { it.count("SELECT COUNT(*) FROM reviews") to it.count("SELECT COUNT(*) FROM work") })
            assertEquals(0L, service.ledger.read { it.count("SELECT COUNT(*) FROM parent_plans") })
            assertEquals(deferred.retained, retained(service, deferred.reviewId))
        }
    }

    @Test fun parentChildAdmissionsAndIdempotentParentReadsRetainTheirEvaluatorAcrossUpgrade() {
        val input = input("parent-upgrade")
        val root = temp.resolve("ledger-parent-upgrade")
        val provider = Provider()
        val binding = binding("parent-owner")
        val request = SubmitPlanRequest("parent-patch", "parent-key", "parent-ref")
        val plan = plan(binding, request, listOf(input))
        val authority = ReviewPlanAuthority { client, submitted -> plan.takeIf { client == binding && submitted == request } }
        lateinit var client: ClientRegistration
        lateinit var parent: ParentPlanStatus
        lateinit var historical: Retained
        lateinit var history: List<ReviewEvent>
        lateinit var cut: GateCut
        KernelService.open(root, provider, config, SyntheticFence(input, plan), resultPolicy = accepting(), planAuthority = authority).use { a ->
            client = a.registerClient(binding)
            parent = a.submitPlan(client.client, request)
            val child = awaitEvaluated(a, client, parent.slots.single().reviewId)
            assertEquals("complete", child.state)
            historical = retained(a, child.reviewId)
            assertEquals(a.policyEvaluatorIdentity, PolicyCertificate.decode(historical.certificate)!!.evaluatorIdentity)
            history = observeAll(a, client)
            cut = a.prepareGate(client.client)
            assertTrue(cut.ready, cut.reasons.toString())
        }
        val calls = AtomicInteger()
        KernelService.open(root, provider, config.copy(workerCount = 0), SyntheticFence(input, plan),
            resultPolicy = ResultPolicy.versioned(EVALUATOR_B) { _, _, raw -> calls.incrementAndGet(); raw }, planAuthority = authority).use { b ->
            val current = b.planStatus(client.client, parent.planId)
            assertEquals("incomplete", current.state)
            assertTrue("policy_evaluator_mismatch" in current.slots.single().reasons)
            assertEquals(current, b.submitPlan(client.client, request))
            assertEquals(historical, retained(b, parent.slots.single().reviewId))
            assertEquals(history, events(b, client))
            val actions = AtomicInteger()
            assertFalse(b.completeGate(client.client, cut.cutId) { actions.incrementAndGet() }.allowed)
            assertEquals(0, actions.get())
            assertEquals(0, calls.get())
            assertEquals(1, provider.calls.get())
        }
    }

    @Test fun legacyMalformedAndUnknownCertificatesRemainAuditableWithoutAcquiringCurrentIdentity() {
        val original = completed("legacy-certificates")
        KernelService.open(original.root, original.provider, config.copy(workerCount = 0), SyntheticFence(original.input),
            resultPolicy = accepting()).use { service ->
            val valid = assertNotNull(PolicyCertificate.decode(original.retained.certificate))
            val raw = ContractJson.decodeFromString<ProviderResult>(original.retained.raw!!)
            val oldDigest = Digests.sha256(canonical(listOf(canonical(original.client.binding), original.input.analysisKey(),
                original.input.context.configurationDigest.orEmpty(), Digests.sha256(canonical(raw)), Digests.sha256(original.retained.effective!!))))
            val invalid = listOf(null, oldDigest, "{", "x".repeat(1025), " ${original.retained.certificate}",
                canonical(valid.copy(format = "unknown-policy-certificate/2")), canonical(valid.copy(state = "unknown")),
                canonical(valid.copy(digest = null)), canonical(valid.copy(state = PolicyCertificate.ADMITTED)),
                canonical(valid.copy(evaluatorIdentity = "not-a-digest")))
            for (encoded in invalid) {
                service.ledger.transaction { it.execute("UPDATE reviews SET policy_certificate=? WHERE review_id=?", encoded, original.status.reviewId) }
                val before = retained(service, original.status.reviewId)
                val status = service.status(original.client.client, original.status.reviewId)
                assertEquals("incomplete", status.state)
                assertTrue("policy_evaluator_identity_unknown" in status.reasons)
                assertEquals(original.status.result, status.result)
                assertEquals(status, service.submit(original.client.client, original.request))
                service.recoverPolicyEvaluations()
                val actions = AtomicInteger()
                assertFalse(service.completeGate(original.client.client, original.cut.cutId) { actions.incrementAndGet() }.allowed)
                assertEquals(0, actions.get())
                assertEquals(before, retained(service, status.reviewId), "Reads/recovery cannot relabel a legacy or malformed certificate")
                assertEquals(original.events, events(service, original.client))
            }
            // A syntactically valid terminal envelope still needs its exact evaluator-bound digest.
            service.ledger.transaction { it.execute("UPDATE reviews SET policy_certificate=? WHERE review_id=?",
                canonical(valid.copy(digest = Digests.sha256("wrong-effective-certificate"))), original.status.reviewId) }
            assertTrue("review_certificate_invalid:${original.status.reviewId}" in service.prepareGate(original.client.client).reasons)
            assertFalse(service.completeGate(original.client.client, original.cut.cutId).allowed)
            assertEquals(1, original.provider.calls.get())
        }
    }

    private data class Retained(val raw: String?, val effective: String?, val certificate: String?, val validation: String, val cancelled: Boolean)
    private data class Completed(val root: Path, val input: AnalysisInput, val provider: Provider, val client: ClientRegistration,
        val request: SubmitRequest, val status: ReviewStatus, val retained: Retained, val events: List<ReviewEvent>, val cut: GateCut)
    private data class Deferred(val root: Path, val input: AnalysisInput, val provider: Provider, val client: ClientRegistration,
        val request: SubmitRequest, val reviewId: String, val retained: Retained, val config: KernelConfig, val cut: GateCut)

    private fun completed(name: String, policy: ResultPolicy = accepting(), limits: KernelConfig = config): Completed {
        val input = input(name)
        val root = temp.resolve("ledger-$name")
        val provider = Provider()
        return KernelService.open(root, provider, limits, SyntheticFence(input), resultPolicy = policy).use { service ->
            val client = service.registerClient(binding("$name-owner"))
            val request = request(name, input)
            val status = awaitEvaluated(service, client, service.submit(client.client, request).reviewId)
            assertEquals("complete", status.state, status.reasons.toString())
            assertEquals("accepted", status.result!!.findings.single().disposition)
            val events = observeAll(service, client)
            val cut = service.prepareGate(client.client)
            assertTrue(cut.ready, cut.reasons.toString())
            Completed(root, input, provider, client, request, status, retained(service, status.reviewId), events, cut)
        }
    }

    private fun deferred(name: String, bPolicy: ResultPolicy, limits: KernelConfig = config, cancelled: Boolean = false): Deferred {
        val input = input(name)
        val root = temp.resolve("ledger-$name")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val provider = Provider(entered, release)
        val aCalls = ConcurrentLinkedQueue<String>()
        val aPolicy = ResultPolicy.versioned(EVALUATOR_A) { client, _, raw -> aCalls += client.clientId; accepted(raw) }
        return KernelService.open(root, provider, limits, SyntheticFence(input), resultPolicy = aPolicy).use { a ->
            val aClient = a.registerClient(binding("$name-a"))
            val aReview = a.submit(aClient.client, request("$name-a", input))
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            try {
                lateinit var bClient: ClientRegistration
                lateinit var bReview: ReviewStatus
                val bRequest = request("$name-b", input)
                lateinit var admission: Retained
                KernelService.open(root, provider, limits.copy(workerCount = 0), SyntheticFence(input), resultPolicy = bPolicy).use { b ->
                    bClient = b.registerClient(binding("$name-b"))
                    bReview = b.submit(bClient.client, bRequest)
                    if (cancelled) b.cancel(bClient.client, bReview.reviewId)
                    admission = retained(b, bReview.reviewId)
                    assertNull(admission.effective)
                    assertEquals(PolicyCertificate.ADMITTED, PolicyCertificate.decode(admission.certificate)!!.state)
                }
                release.countDown()
                awaitEvaluated(a, aClient, aReview.reviewId)
                val pending = retained(a, bReview.reviewId)
                assertNull(pending.effective, "A must retain, not evaluate, B's policy obligation while B is disconnected")
                assertNotNull(pending.raw)
                assertEquals(admission.certificate, pending.certificate)
                assertEquals(listOf(aClient.binding.clientId), aCalls.toList())
                assertEquals(1, provider.calls.get())
                Deferred(root, input, provider, bClient, bRequest, bReview.reviewId, pending, limits, a.prepareGate(bClient.client))
            } finally { release.countDown() }
        }
    }

    private fun input(name: String): AnalysisInput {
        val root = temp.resolve("source-$name")
        Files.createDirectories(root)
        val text = "val fixture = 1\n"
        Files.writeString(root.resolve("Main.kt"), text)
        val snapshot = SnapshotManifest(Digests.sha256("policy-evaluator:$name:$text"), "workspace", "tree",
            listOf(SnapshotFile("Main.kt", Digests.sha256(text), text.toByteArray().size.toLong())), ownerId = "owner", declaredRoots = listOf("."))
        return AnalysisInput(snapshot, root.toString(), AnalysisContext(compilerVersion = "synthetic-kotlin/1",
            modelDigest = Digests.sha256("model"), dependencyDigest = Digests.sha256("dependencies"),
            toolchainDigest = Digests.sha256("tools"), configurationDigest = Digests.sha256("captured-policy"),
            fullProject = true, compilationFiles = listOf("Main.kt")), listOf(RULE))
    }

    private fun plan(binding: ClientBinding, request: SubmitPlanRequest, inputs: List<AnalysisInput>) = RequiredReviewPlan(
        binding, request, Digests.sha256(canonical(inputs.first().snapshot)), Digests.sha256(canonical(emptyList<SnapshotFile>())),
        Digests.sha256("synthetic-model-certificate"), Digests.sha256("synthetic-impact-certificate"),
        inputs.mapIndexed { index, input -> RequiredPlanSlot("slot-$index", input, assertNotNull(contextIdentity(input))) }, listOf(binding.clientId))

    private fun retained(service: KernelService, reviewId: String): Retained = service.ledger.read { c ->
        assertNotNull(c.one("""SELECT w.result_json,r.effective_result_json,r.policy_certificate,r.policy_validation_json,r.cancelled
            FROM reviews r JOIN work w ON w.work_key=r.work_key WHERE r.review_id=?""", reviewId) {
            Retained(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getInt(5) != 0)
        })
    }

    private fun awaitEvaluated(service: KernelService, client: ClientRegistration, review: String): ReviewStatus {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (retained(service, review).effective == null && System.nanoTime() < deadline) Thread.sleep(5)
        assertNotNull(retained(service, review).effective, "Policy evaluation did not finish")
        return service.status(client.client, review)
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(condition(), message)
    }

    private fun archiveFootprint(root: Path): List<String> = Files.walk(root.resolve("inputs")).use { paths ->
        paths.map { path -> root.relativize(path).toString() + if (Files.isRegularFile(path)) ":${Digests.sha256(Files.readAllBytes(path))}" else "/" }
            .sorted().toList()
    }

    private fun events(service: KernelService, client: ClientRegistration): List<ReviewEvent> = buildList {
        var cursor: String? = null
        do {
            val page = service.events(client.client, cursor, 8)
            addAll(page.events)
            cursor = page.cursor
        } while (page.hasMore)
    }

    private fun observeAll(service: KernelService, client: ClientRegistration): List<ReviewEvent> {
        val events = events(service, client)
        for (event in events) {
            assertEquals(Digests.sha256(event.payload), event.payloadDigest)
            assertTrue(event.deliveryRequired)
            val digest = Digests.sha256("synthetic-context:${event.payloadDigest}")
            service.acknowledge(client.adapter, listOf(event.eventId), ContextReceipt("ack-${event.eventId}", digest,
                "item-${event.eventId}", Instant.EPOCH.toString()))
            service.observe(client.adapter, listOf(event.eventId), ObservationReceipt("observation-${event.eventId}",
                "synthetic-model-request-${event.eventId}", listOf(digest)))
        }
        return events
    }

    private class SyntheticFence(private val expected: AnalysisInput, private val plan: RequiredReviewPlan? = null) : SnapshotGate {
        private val lock = Any()
        override fun <T> withCurrentSnapshot(input: AnalysisInput, action: (SnapshotCheck) -> T): T = synchronized(lock) {
            action(SnapshotCheck(expected.snapshot == input.snapshot && expected.context == input.context,
                expected.snapshot.snapshotId, 1, expected.context.configurationDigest, expected.context.modelDigest,
                expected.context.dependencyDigest, expected.context.toolchainDigest, "synthetic-policy-evaluator-fence/1", authoritative = true,
                contextIdentities = plan?.slots?.mapNotNull { it.identity }.orEmpty(),
                sourceBindingDigest = plan?.sourceBindingDigest, generatedInputsDigest = plan?.generatedInputsDigest))
        }
    }

    private class Provider(private val entered: CountDownLatch? = null, private val release: CountDownLatch? = null,
                           private val progressive: Boolean = false) : ProgressiveAnalysisProvider {
        override val id = PROVIDER
        override val version = "synthetic/1"
        override val supportedRuleIds = setOf(RULE)
        val calls = AtomicInteger()
        override fun analyzeProgressive(input: AnalysisInput, cancellation: CancellationToken, onProgress: (ProviderResult) -> Unit): ProviderResult {
            calls.incrementAndGet()
            entered?.countDown()
            if (release != null) check(release.await(10, TimeUnit.SECONDS))
            cancellation.throwIfCancelled()
            val finding = Finding(RULE, "Synthetic policy finding", "Synthetic diagnosis", "Fixture only",
                SourceLocation("Main.kt", input.snapshot.files.single().digest), Digests.sha256("finding:${input.snapshot.snapshotId}"),
                PROVIDER, severity = "warning", context = input.context.scope)
            val raw = ProviderResult(id, "complete", listOf(finding), input.requiredRuleIds.map { rule ->
                Coverage(rule, input.context.scope, "findings", provenance = "executed",
                    certificateId = Digests.sha256("synthetic-evaluation:${input.analysisKey()}:$rule"), implemented = true, tested = true, qualified = true)
            }, providerVersion = version)
            if (progressive) onProgress(raw.copy(status = "running"))
            return raw
        }
    }

    companion object {
        private const val RULE = "test/policy-evaluator"
        private const val PROVIDER = "synthetic-policy-evaluator"
        private const val EVALUATOR_A = "synthetic-policy-evaluator/A/1"
        private const val EVALUATOR_B = "synthetic-policy-evaluator/B/2"
        private fun accepted(raw: ProviderResult) = raw.copy(findings = raw.findings.map { it.copy(disposition = "accepted") })
        private fun accepting() = ResultPolicy.versioned(EVALUATOR_A) { _, _, raw -> accepted(raw) }
        private fun binding(id: String) = ClientBinding("owner", "workspace", "tree", id, "lineage-$id", sessionId = "session-$id")
        private fun request(id: String, input: AnalysisInput) = SubmitRequest("patch-$id", "key-$id", input)
    }
}

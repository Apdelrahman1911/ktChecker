package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * Future kernel/ledger tests with a SYNTHETIC provider and owner archive, not host/durability proof.
 * The tested interleavings are synchronous public authority.prepare callbacks, outside both ledger
 * and owner fences. No production hook, parallel peer process, timed race or reentrant pure apply.
 */
@Timeout(30)
class KernelPolicyReassessmentInterleavingTest {
    @TempDir lateinit var temp: Path
    private val config = KernelConfig(workerCount = 1, globalParallelism = 1, perWorktreeParallelism = 1,
        heartbeatMillis = 50, leaseMillis = 200, pollMillis = 5, analysisTimeoutMillis = 15_000)

    @Test fun cancelOriginalDuringPreparePublishesOnlyCancellationAndSurvivesReopen() {
        fixture("cancel-original").use { f ->
            val oldEvents = observeAll(f.service, f.client)
            val oldEvidence = retainedEvidence(f.service)
            assertEquals(1, oldEvidence.size)
            assertEquals(24, oldEvidence.single().size)
            val oldReceipts = receipts(f.service)
            val before = frontier(f.service, f.client)
            val request = request(f, "cancel-during-prepare")
            val hooks = AtomicInteger()
            f.authority.policy = NEW_POLICY
            f.authority.duringPrepare = { binding, subject ->
                assertEquals(f.client.binding, binding)
                assertEquals(f.status.reviewId, subject.reviewId)
                assertEquals(f.input, subject.input)
                assertEquals(1, f.authority.prepares.get())
                assertEquals(0, f.authority.applies.get())
                assertEquals(0, f.authority.fences.get())
                assertEquals("complete", f.service.status(f.client.client, subject.reviewId).state)
                hooks.incrementAndGet()
                assertTrue(f.service.cancel(f.client.client, subject.reviewId).cancelled)
            }
            reject("policy_reassessment_original_incomplete") { f.service.reassessPolicy(f.client.client, request) }
            assertEquals(1, hooks.get())
            assertEquals(1, f.authority.prepares.get())
            assertEquals(1, f.authority.fences.get())
            assertEquals(0, f.authority.applies.get())
            val cancelled = f.service.status(f.client.client, f.status.reviewId)
            assertEquals("cancelled", cancelled.state)
            assertTrue(cancelled.cancelled)
            assertEquals(f.status.result, cancelled.result)
            val history = events(f.service, f.client)
            assertEquals(oldEvents, history.dropLast(1))
            val cancellation = history.last()
            assertEquals("cancelled", cancellation.kind)
            assertEquals(f.status.reviewId, cancellation.reviewId)
            assertTrue(cancellation.deliveryRequired)
            assertEquals(canonical(cancelled), cancellation.payload)
            assertTrue(history.none { it.kind == POLICY_EVENT })
            assertEquals(before.copy(epoch = before.epoch + 1, nextEvent = before.nextEvent + 1), frontier(f.service, f.client))
            assertEquals(oldEvidence, retainedEvidence(f.service))
            assertEquals(oldReceipts, receipts(f.service))
            reject("policy_reassessment_not_found") { f.service.policyReassessmentStatus(f.client.client, request.idempotencyKey) }
            // Retrying this never-accepted request cannot repair cancellation or allocate another capture.
            reject("policy_reassessment_original_incomplete") { f.service.reassessPolicy(f.client.client, request) }
            assertEquals(1, f.authority.prepares.get())
            assertEquals(history, events(f.service, f.client))
            assertNoNewExecution(f, f.service)

            deliver(f.service, f.client, cancellation)
            f.authority.policy = ORIGINAL_POLICY // Remove unrelated configuration staleness from the gate assertion.
            assertCancelledGate(f.service, f.client, f.status.reviewId)
            val deliveredReceipts = receipts(f.service)
            val cancelledFrontier = frontier(f.service, f.client)
            f.service.close()
            reopen(f).use { restarted ->
                assertEquals(cancelled, restarted.status(f.client.client, f.status.reviewId))
                assertEquals(oldEvidence, retainedEvidence(restarted))
                assertEquals(history, events(restarted, f.client))
                assertEquals(deliveredReceipts, receipts(restarted))
                assertEquals(cancelledFrontier, frontier(restarted, f.client))
                reject("policy_reassessment_not_found") { restarted.policyReassessmentStatus(f.client.client, request.idempotencyKey) }
                reject("policy_reassessment_original_incomplete") { restarted.reassessPolicy(f.client.client, request) }
                assertCancelledGate(restarted, f.client, f.status.reviewId)
                assertEquals(1, f.authority.prepares.get())
                assertEquals(0, f.authority.applies.get())
                assertNoNewExecution(f, restarted)
            }
        }
    }

    @Test fun closeDuringPreparePropagatesClosedAndOnlyFreshRetryAfterReopenCanPublish() {
        fixture("close-service").use { f ->
            val oldEvents = observeAll(f.service, f.client)
            val oldEvidence = retainedEvidence(f.service)
            assertEquals(1, oldEvidence.size)
            assertEquals(24, oldEvidence.single().size)
            val oldReceipts = receipts(f.service)
            val before = frontier(f.service, f.client)
            val request = request(f, "close-during-prepare")
            val hooks = AtomicInteger()
            f.authority.policy = NEW_POLICY
            f.authority.duringPrepare = { binding, subject ->
                assertEquals(f.client.binding, binding)
                assertEquals(f.status.reviewId, subject.reviewId)
                assertEquals(1, f.authority.prepares.get())
                assertEquals(0, f.authority.applies.get())
                assertEquals(0, f.authority.fences.get())
                hooks.incrementAndGet()
                f.service.close() // No ledger/config/source fence is held by prepare.
            }
            reject("service_closed") { f.service.reassessPolicy(f.client.client, request) }
            assertEquals(1, hooks.get())
            assertEquals(1, f.authority.prepares.get())
            assertEquals(1, f.authority.fences.get())
            assertEquals(0, f.authority.applies.get())
            reject("service_closed") { f.service.status(f.client.client, f.status.reviewId) }
            f.authority.duringPrepare = { _, _ -> }
            // Do not read the closed instance's ledger. Reopen the same temporary store with no workers.
            reopen(f).use { restarted ->
                assertEquals(f.status, restarted.status(f.client.client, f.status.reviewId))
                assertEquals(oldEvidence, retainedEvidence(restarted))
                assertEquals(oldEvents, events(restarted, f.client))
                assertEquals(oldReceipts, receipts(restarted))
                assertEquals(before, frontier(restarted, f.client))
                reject("policy_reassessment_not_found") { restarted.policyReassessmentStatus(f.client.client, request.idempotencyKey) }
                assertNoNewExecution(f, restarted)
                // Same identifiers are retryable because close left no accepted/pending policy event.
                val view = restarted.reassessPolicy(f.client.client, request)
                assertEquals(request, view.request)
                assertEquals(before.nextEvent, view.sequence)
                assertEquals(2, f.authority.prepares.get())
                assertEquals(1, f.authority.applies.get())
                val history = events(restarted, f.client)
                assertEquals(oldEvents, history.dropLast(1))
                assertEquals(view.viewId, history.last().eventId)
                assertEquals(POLICY_EVENT, history.last().kind)
                assertTrue(history.last().deliveryRequired)
                assertEquals(canonical(view), history.last().payload)
                assertEquals(before.copy(epoch = before.epoch + 1, nextEvent = before.nextEvent + 1), frontier(restarted, f.client))
                assertEquals(oldEvidence, retainedEvidence(restarted))
                assertEquals(oldReceipts, receipts(restarted))
                assertEquals(view, restarted.reassessPolicy(f.client.client, request))
                assertEquals(2, f.authority.prepares.get())
                assertEquals(1, f.authority.applies.get())
                assertEquals(history, events(restarted, f.client))
                val pending = restarted.prepareGate(f.client.client)
                assertFalse(pending.ready)
                assertTrue("insertion_missing:${view.viewId}" in pending.reasons)
                deliver(restarted, f.client, history.last())
                val ready = restarted.prepareGate(f.client.client)
                assertTrue(ready.ready, ready.reasons.toString())
                assertNoNewExecution(f, restarted)
            }
        }
    }

    @Test fun otherClientCancellingSameWorkDuringPrepareDoesNotCancelOrDeliverForRequester() {
        fixture("shared-work").use { f ->
            val other = f.service.registerClient(binding("other-consumer"))
            // Only the consumer binding/submit tuple differs: the ENTIRE accepted input is identical.
            val otherStatus = f.service.submit(other.client, SubmitRequest("other-patch", "other-submit", f.input))
            assertEquals("complete", otherStatus.state, otherStatus.reasons.toString())
            assertNotEquals(f.status.reviewId, otherStatus.reviewId)
            val sharedWork = workKey(f.service, f.status.reviewId)
            assertEquals(sharedWork, workKey(f.service, otherStatus.reviewId))
            assertEquals(listOf(canonical(f.input)), f.service.ledger.read { c ->
                c.query("SELECT DISTINCT input_json FROM reviews ORDER BY input_json") { it.getString(1) }
            })
            assertEquals(1L, f.service.ledger.read { it.count("SELECT COUNT(*) FROM work") })
            assertNoNewExecution(f, f.service)
            val requesterEvents = observeAll(f.service, f.client)
            val otherEvents = observeAll(f.service, other)
            val oldEvidence = retainedEvidence(f.service)
            assertEquals(2, oldEvidence.size)
            val oldReceipts = receipts(f.service)
            val requesterFrontier = frontier(f.service, f.client)
            val otherFrontier = frontier(f.service, other)
            val hooks = AtomicInteger()
            f.authority.policy = NEW_POLICY
            f.authority.duringPrepare = { binding, subject ->
                assertEquals(f.client.binding, binding)
                assertEquals(sharedWork, subject.workKey)
                assertEquals(f.input, subject.input)
                assertEquals(0, f.authority.fences.get())
                hooks.incrementAndGet()
                assertTrue(f.service.cancel(other.client, otherStatus.reviewId).cancelled)
                assertEquals(requesterFrontier, frontier(f.service, f.client))
                assertEquals("complete", f.service.status(f.client.client, f.status.reviewId).state)
            }
            val view = f.service.reassessPolicy(f.client.client, request(f, "other-cancels"))
            assertEquals(1, hooks.get())
            assertEquals(1, f.authority.prepares.get())
            assertEquals(1, f.authority.applies.get())
            assertEquals(sharedWork, view.certified.workKey)
            assertEquals(f.input.analysisKey(), view.certified.inputKey)
            assertEquals(f.status, f.service.status(f.client.client, f.status.reviewId))
            assertTrue(f.service.status(other.client, otherStatus.reviewId).cancelled)
            assertEquals(oldEvidence, retainedEvidence(f.service))
            assertEquals(oldReceipts, receipts(f.service))
            val requesterHistory = events(f.service, f.client)
            val otherHistory = events(f.service, other)
            assertEquals(requesterEvents, requesterHistory.dropLast(1))
            assertEquals(otherEvents, otherHistory.dropLast(1))
            val policyEvent = requesterHistory.last()
            val cancellation = otherHistory.last()
            assertEquals(view.viewId, policyEvent.eventId)
            assertEquals(POLICY_EVENT, policyEvent.kind)
            assertEquals(f.client.binding.clientId, policyEvent.clientId)
            assertEquals("cancelled", cancellation.kind)
            assertEquals(other.binding.clientId, cancellation.clientId)
            assertEquals(otherStatus.reviewId, cancellation.reviewId)
            assertTrue(policyEvent.deliveryRequired && cancellation.deliveryRequired)
            assertTrue(otherHistory.none { it.kind == POLICY_EVENT })
            assertEquals(requesterFrontier.copy(epoch = requesterFrontier.epoch + 1, nextEvent = requesterFrontier.nextEvent + 1), frontier(f.service, f.client))
            assertEquals(otherFrontier.copy(epoch = otherFrontier.epoch + 1, nextEvent = otherFrontier.nextEvent + 1), frontier(f.service, other))
            reject("event_not_found") { acknowledge(f.service, other, policyEvent) }
            reject("event_not_found") { acknowledge(f.service, f.client, cancellation) }
            assertEquals(oldReceipts, receipts(f.service))
            assertFalse(f.service.prepareGate(f.client.client).ready)
            deliver(f.service, f.client, policyEvent)
            val ready = f.service.prepareGate(f.client.client)
            assertTrue(ready.ready, ready.reasons.toString())
            val otherPending = f.service.prepareGate(other.client)
            assertFalse(otherPending.ready)
            assertTrue("insertion_missing:${cancellation.eventId}" in otherPending.reasons)
            assertTrue("review_cancelled:${otherStatus.reviewId}" in otherPending.reasons)
            deliver(f.service, other, cancellation)
            assertCancelledGate(f.service, other, otherStatus.reviewId)
            reject("policy_reassessment_original_incomplete") {
                f.service.reassessPolicy(other.client, PolicyReassessmentRequest(otherStatus.reviewId, "other-cancelled", other.binding.generation, NEW_POLICY))
            }
            assertEquals(1, f.authority.prepares.get())
            assertEquals(1, f.authority.applies.get())
            assertEquals(oldEvidence, retainedEvidence(f.service))
            assertEquals(requesterHistory, events(f.service, f.client))
            assertEquals(otherHistory, events(f.service, other))
            val stillReady = f.service.prepareGate(f.client.client)
            assertTrue(stillReady.ready, stillReady.reasons.toString())
            assertNoNewExecution(f, f.service)
        }
    }

    private data class Fixture(val root: Path, val input: AnalysisInput, val service: KernelService,
        val client: ClientRegistration, val status: ReviewStatus, val provider: Provider,
        val authority: Authority, val observerCalls: AtomicInteger) : AutoCloseable {
        override fun close() = service.close()
    }

    private fun fixture(name: String): Fixture {
        val source = temp.resolve("source-$name")
        Files.createDirectories(source)
        val text = "val fixture = 1\n"
        Files.writeString(source.resolve("Main.kt"), text)
        val snapshot = SnapshotManifest(Digests.sha256("$name:$text"), "workspace", "tree",
            listOf(SnapshotFile("Main.kt", Digests.sha256(text), text.toByteArray().size.toLong())), ownerId = "owner", declaredRoots = listOf("."))
        val input = AnalysisInput(snapshot, source.toString(), AnalysisContext(compilerVersion = "synthetic/1",
            modelDigest = Digests.sha256("model"), dependencyDigest = Digests.sha256("dependencies"), toolchainDigest = Digests.sha256("toolchain"),
            configurationDigest = ORIGINAL_POLICY, fullProject = true, compilationFiles = listOf("Main.kt")), listOf(RULE))
        val root = temp.resolve("ledger-$name")
        val provider = Provider()
        val authority = Authority()
        val observers = AtomicInteger()
        val service = open(root, provider, authority, input, observers, 1)
        try {
            val client = service.registerClient(binding(name))
            val submitted = service.submit(client.client, SubmitRequest("patch-$name", "submit-$name", input))
            val status = awaitInitialRaw(service, client, submitted.reviewId)
            assertEquals("complete", status.state, status.reasons.toString())
            val raw = assertNotNull(status.result)
            assertTrue(raw.completeFor(input))
            assertEquals(2, raw.findings.size)
            assertEquals(2, raw.findings.map { it.fingerprint }.distinct().size)
            assertTrue(raw.findings.all { it.disposition == "open" })
            assertEquals(1, provider.calls.get())
            assertEquals(1, observers.get())
            return Fixture(root, input, service, client, status, provider, authority, observers)
        } catch (failure: Throwable) { service.close(); throw failure }
    }

    private fun open(root: Path, provider: Provider, authority: Authority, input: AnalysisInput, observers: AtomicInteger, workers: Int) =
        KernelService.open(root, provider, config.copy(workerCount = workers), authority.legacyFence(input),
            preparedInputObserver = PreparedInputObserver { _, _, _ -> observers.incrementAndGet() }, policyReassessmentAuthority = authority)
    private fun reopen(f: Fixture) = open(f.root, f.provider, f.authority, f.input, f.observerCalls, 0)

    private class Authority : PolicyReassessmentAuthority {
        override val evaluatorIdentity = "synthetic/interleaving-policy/1"
        override val issuerIdentity = "synthetic/interleaving-owner/1"
        var policy = ORIGINAL_POLICY
        var duringPrepare: (ClientBinding, CertifiedPolicyReview) -> Unit = { _, _ -> }
        val prepares = AtomicInteger()
        val applies = AtomicInteger()
        val fences = AtomicInteger()
        private data class Saved(val binding: ClientBinding, val subject: CertifiedPolicyReview, val capture: PolicyReassessmentCapture)
        private val captures = ConcurrentHashMap<String, Saved>() // Memory only: deliberately not filesystem restart proof.
        private val lock = Any()

        override fun prepare(binding: ClientBinding, subject: CertifiedPolicyReview, expectedPolicyDigest: String): PreparedPolicyReassessment {
            prepares.incrementAndGet()
            val current = policy
            if (current != expectedPolicyDigest) throw KernelException("policy_reassessment_capture_mismatch", "Synthetic expected policy differs")
            val capture = PolicyReassessmentCapture(assertNotNull(subject.input.context.configurationDigest), current,
                Digests.sha256("original:${canonical(binding)}:${subject.input.analysisKey()}"),
                Digests.sha256("current:${canonical(binding)}:${subject.input.analysisKey()}:$current"),
                Digests.sha256("analysis:${canonical(binding)}:${subject.input.analysisKey()}"))
            captures[capture.currentCaptureId] = Saved(binding, subject, capture)
            val result = prepared(subject, capture)
            duringPrepare(binding, subject) // Intentionally BEFORE the owner fence and after kernel admission read.
            return result
        }

        override fun restore(binding: ClientBinding, subject: CertifiedPolicyReview, capture: PolicyReassessmentCapture): PreparedPolicyReassessment {
            check(captures[capture.currentCaptureId] == Saved(binding, subject, capture))
            return prepared(subject, capture)
        }

        private fun prepared(subject: CertifiedPolicyReview, saved: PolicyReassessmentCapture) = object : PreparedPolicyReassessment {
            override val capture = saved
            override fun apply(raw: ProviderResult): ProviderResult {
                applies.incrementAndGet()
                return raw.copy(findings = raw.findings.map { it.copy(disposition = "suppressed") })
            }
            override fun <T> withCurrent(action: (SnapshotCheck) -> T): T = synchronized(lock) {
                fences.incrementAndGet()
                action(checkFor(subject.input, policy == saved.currentPolicyDigest)) // Exactly once, no callback catch/retry.
            }
        }

        private fun checkFor(input: AnalysisInput, current: Boolean) = SnapshotCheck(current, input.snapshot.snapshotId, 1, policy,
            input.context.modelDigest, input.context.dependencyDigest, input.context.toolchainDigest,
            "synthetic-analysis-epoch:$policy", authoritative = true)
        fun legacyFence(expected: AnalysisInput): SnapshotGate = object : SnapshotGate {
            override fun <T> withCurrentSnapshot(input: AnalysisInput, action: (SnapshotCheck) -> T): T = synchronized(lock) {
                action(checkFor(expected, input == expected && policy == expected.context.configurationDigest))
            }
        }
    }

    private class Provider : AnalysisProvider {
        override val id = "synthetic-policy-interleavings"
        override val version = "1"
        override val supportedRuleIds = setOf(RULE)
        val calls = AtomicInteger()
        override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
            calls.incrementAndGet()
            cancellation.throwIfCancelled()
            val key = input.analysisKey()
            val first = Finding(RULE, "First occurrence", "Synthetic diagnostic", "Fixture only",
                SourceLocation("Main.kt", input.snapshot.files.single().digest), Digests.sha256("first"), id,
                context = input.context.scope, severity = "warning", semanticDigest = key,
                evidence = listOf(Evidence("compiler-options", key, digest = key)))
            val second = first.copy(title = "Second occurrence", fingerprint = Digests.sha256("second"),
                evidence = first.evidence + Evidence("occurrence", "second"))
            return ProviderResult(id, "complete", listOf(first, second), listOf(Coverage(RULE, input.context.scope, "findings",
                provenance = "executed", certificateId = Digests.sha256("coverage:$key"), implemented = true, tested = true, qualified = true)),
                providerVersion = version)
        }
    }

    /** Immutable original columns only; cancelled/frontiers/required events are asserted separately. */
    private fun retainedEvidence(service: KernelService): List<List<String?>> = service.ledger.read { c ->
        c.query("""SELECT r.review_id,r.client_id,r.patch_id,r.idempotency_key,r.request_hash,r.request_json,
            r.input_json,r.work_key,r.sequence,r.kind,r.effective_result_json,r.policy_certificate,r.policy_validation_json,
            w.input_json,w.frozen_json,w.result_json,w.validation_json,w.provider_key,w.state,
            wc.input_key,wc.effective_key,wc.result_digest,wc.provider_key,wc.certificate_id
            FROM reviews r JOIN work w ON w.work_key=r.work_key LEFT JOIN work_certificates wc ON wc.work_key=w.work_key
            ORDER BY r.review_id""") { row -> (1..row.metaData.columnCount).map { row.getString(it) } }
    }
    private fun workKey(service: KernelService, reviewId: String): String = service.ledger.read { c ->
        assertNotNull(c.one("SELECT work_key FROM reviews WHERE review_id=?", reviewId) { it.getString(1) })
    }
    private data class Frontier(val epoch: Long, val nextEvent: Long, val nextReview: Long, val gateState: String)
    private fun frontier(service: KernelService, client: ClientRegistration): Frontier = service.ledger.read { c ->
        assertNotNull(c.one("SELECT epoch,next_event,next_review,gate_state FROM clients WHERE client_id=?", client.binding.clientId) {
            Frontier(it.getLong(1), it.getLong(2), it.getLong(3), it.getString(4))
        })
    }
    private fun receipts(service: KernelService): List<List<String?>> = service.ledger.read { c ->
        listOf("receipts", "acknowledgements", "observations").flatMap { table ->
            c.query("SELECT * FROM $table ORDER BY rowid") { row -> listOf(table) + (1..row.metaData.columnCount).map { row.getString(it) } }
        }
    }
    private fun events(service: KernelService, client: ClientRegistration): List<ReviewEvent> = service.events(client.client).also {
        assertFalse(it.hasMore, "This bounded synthetic history must fit one page")
    }.events
    private fun observeAll(service: KernelService, client: ClientRegistration): List<ReviewEvent> = events(service, client).also {
        it.forEach { event -> deliver(service, client, event) }
    }
    private fun contextDigest(event: ReviewEvent) = Digests.sha256("synthetic-context:${event.eventId}:${event.payloadDigest}")
    private fun acknowledge(service: KernelService, client: ClientRegistration, event: ReviewEvent) {
        service.acknowledge(client.adapter, listOf(event.eventId),
            ContextReceipt("ack-${event.eventId}", contextDigest(event), "item-${event.eventId}", Instant.EPOCH.toString()))
    }
    private fun deliver(service: KernelService, client: ClientRegistration, event: ReviewEvent) {
        acknowledge(service, client, event)
        service.observe(client.adapter, listOf(event.eventId),
            ObservationReceipt("observed-${event.eventId}", "synthetic-model-${event.eventId}", listOf(contextDigest(event))))
    }
    private fun assertCancelledGate(service: KernelService, client: ClientRegistration, reviewId: String) {
        val gate = service.prepareGate(client.client)
        assertFalse(gate.ready)
        assertTrue("review_cancelled:$reviewId" in gate.reasons)
        assertTrue(gate.reasons.none { it.startsWith("insertion_missing:") || it.startsWith("model_observation_missing:") })
    }
    private fun assertNoNewExecution(f: Fixture, readable: KernelService) {
        assertEquals(1, f.provider.calls.get())
        assertEquals(1, f.observerCalls.get())
        assertEquals(1L, readable.ledger.read { it.count("SELECT COUNT(*) FROM work") })
    }
    /** Initial raw-completion condition wait ONLY, not race ordering; each interleaving above is synchronous. */
    private fun awaitInitialRaw(service: KernelService, client: ClientRegistration, reviewId: String): ReviewStatus {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        var status = service.status(client.client, reviewId)
        while (status.state in setOf("queued", "running") && System.nanoTime() < deadline) {
            Thread.sleep(5) // Bounded interruptible setup polling, not a timed publication/cancellation race.
            status = service.status(client.client, reviewId)
        }
        assertFalse(status.state in setOf("queued", "running"), "Initial synthetic raw execution did not terminalize")
        return status
    }
    private fun request(f: Fixture, key: String) = PolicyReassessmentRequest(f.status.reviewId, key, f.client.binding.generation, NEW_POLICY)
    private fun reject(code: String, action: () -> Unit) { assertEquals(code, assertFailsWith<KernelException> { action() }.code) }
    companion object {
        private const val RULE = "test/policy-interleavings"
        private const val POLICY_EVENT = "policy_reassessed"
        private val ORIGINAL_POLICY = Digests.sha256("original-policy")
        private val NEW_POLICY = Digests.sha256("new-policy")
        private fun binding(id: String) = ClientBinding("owner", "workspace", "tree", id, "lineage-$id", sessionId = "session-$id")
    }
}

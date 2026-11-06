package dev.ktchecker.kernel

import dev.ktchecker.contracts.AnalysisContext
import dev.ktchecker.contracts.AnalysisInput
import dev.ktchecker.contracts.AnalysisMetrics
import dev.ktchecker.contracts.AnalysisProvider
import dev.ktchecker.contracts.CancellationToken
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Coverage
import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.Evidence
import dev.ktchecker.contracts.Finding
import dev.ktchecker.contracts.ProviderResult
import dev.ktchecker.contracts.RuleContext
import dev.ktchecker.contracts.SnapshotFile
import dev.ktchecker.contracts.SnapshotManifest
import dev.ktchecker.contracts.SourceLocation
import java.nio.file.Files
import java.nio.file.Path
import java.sql.ResultSet
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * Real kernel, SQLite, immutable archives, policy/work certificates and event receipts; synthetic
 * provider, captured policies, current-configuration authority and model observation. This does
 * not mutate ConfigStore, execute a project or establish provider/host qualification.
 */
class KernelPolicyConcurrentReplacementTest {
    @TempDir lateinit var temp: Path

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun lateSharedOldPolicyCannotBorrowNewPartialAcceptanceOrAnotherClientsFullScopeAndReceipts() {
        val old = input()
        val partial = old.copy(context = old.context.copy(configurationDigest = CURRENT_CONFIGURATION, fullProject = false))
        // Exact original input equality except configurationDigest is the accounting contract.
        // In particular, do not silently change the source root, snapshot or any analysis option.
        val full = old.copy(context = old.context.copy(configurationDigest = CURRENT_CONFIGURATION))
        assertEquals(old, full.copy(context = full.context.copy(configurationDigest = OLD_CONFIGURATION)))
        val aBinding = binding("policy-client-a")
        val bBinding = binding("policy-client-b")
        val captures = ConcurrentHashMap<CaptureKey, String>()
        captures[CaptureKey(aBinding, OLD_CONFIGURATION)] = "open"
        captures[CaptureKey(bBinding, OLD_CONFIGURATION)] = "open"
        val classifications = ConcurrentLinkedQueue<Classification>()
        val policy = ResultPolicy.versioned("synthetic-concurrent-captured-policy/1") { client, original, raw ->
            val disposition = checkNotNull(captures[CaptureKey(client, checkNotNull(original.context.configurationDigest))])
            classifications += Classification(client, original, disposition)
            raw.copy(findings = raw.findings.map { it.copy(disposition = disposition) })
        }
        val provider = SyntheticProvider()
        val fence = SyntheticFence(old)
        val config = KernelConfig(workerCount = 2, globalParallelism = 2, perWorktreeParallelism = 2,
            heartbeatMillis = 50, leaseMillis = 300, pollMillis = 5, analysisTimeoutMillis = 30_000)
        val aActions = AtomicInteger()
        val bActions = AtomicInteger()
        KernelService.open(temp.resolve("ledger"), provider, config, fence, resultPolicy = policy).use { service ->
            try {
                val a = service.registerClient(aBinding)
                val b = service.registerClient(bBinding)
                val aOldRequest = request("a-old", old)
                val bOldRequest = request("b-old", old)
                val partialRequest = request("a-current-partial", partial)
                val fullRequest = request("a-current-full", full)
                val aOldSubmitted = service.submit(a.client, aOldRequest)
                assertTrue(provider.oldEntered.await(10, TimeUnit.SECONDS), "Old raw execution must enter its explicit barrier")
                val bOldSubmitted = service.submit(b.client, bOldRequest)
                assertNotEquals(aOldSubmitted.reviewId, bOldSubmitted.reviewId)
                assertNull(aOldSubmitted.result)
                assertNull(bOldSubmitted.result)

                // A captures a new policy WHILE both old reviews still share their original raw
                // execution. The captured A/old and B/old entries remain immutable and distinct.
                assertNull(captures.putIfAbsent(CaptureKey(aBinding, CURRENT_CONFIGURATION), "accepted"))
                fence.updateConfiguration(CURRENT_CONFIGURATION)
                val partialSubmitted = service.submit(a.client, partialRequest)
                val partialStatus = awaitResult(service, a, partialSubmitted.reviewId)
                assertEquals("complete", partialStatus.state, partialStatus.reasons.toString())
                assertEquals("accepted", partialStatus.result!!.findings.single().disposition)
                assertEquals(listOf(Classification(aBinding, partial, "accepted")), classifications.toList())
                for ((client, review) in listOf(a to aOldSubmitted, b to bOldSubmitted)) {
                    val stillRunning = service.status(client.client, review.reviewId)
                    assertEquals("running", stillRunning.state)
                    assertNull(stillRunning.result, "New-policy completion must precede both old-policy publications")
                }
                assertEquals(1L, provider.oldRelease.count)
                assertEquals(1L, provider.oldExited.count)
                assertEquals(2, provider.calls.get())
                assertEquals(2, provider.maxActive.get(), "A partial attempt must genuinely overlap the shared old attempt")
                assertEquals(1, provider.active.get())
                assertEquals(setOf(Kind.CURRENT_PARTIAL), provider.rawResults.keys)
                assertEquals(Counts(2, 3, 1, 4, 0, 0, 0), counts(service))

                provider.oldRelease.countDown()
                val aOld = record(service, a, aOldRequest, awaitResult(service, a, aOldSubmitted.reviewId), provider, "open")
                val bOld = record(service, b, bOldRequest, awaitResult(service, b, bOldSubmitted.reviewId), provider, "open")
                val aPartial = record(service, a, partialRequest, partialStatus, provider, "accepted")
                val earlier = listOf(aOld, bOld, aPartial)
                assertEquals(0L, provider.oldExited.count)
                assertEquals(aOld.audit.getValue("work_key"), bOld.audit.getValue("work_key"))
                assertEquals(aOld.audit.getValue("raw_result"), bOld.audit.getValue("raw_result"))
                assertNotEquals(aOld.audit.getValue("policy_certificate"), bOld.audit.getValue("policy_certificate"))
                assertEquals(3, classifications.size)
                assertEquals(setOf(Classification(aBinding, old, "open"), Classification(bBinding, old, "open")),
                    classifications.toList().drop(1).toSet(), "Late publication uses each review's original captured input")
                val aBeforeFull = events(service, a)
                val bEvents = events(service, b)
                assertEquals(4, aBeforeFull.size)
                assertEquals(2, bEvents.size)
                assertTrue(aBeforeFull.map { it.eventId }.toSet().intersect(bEvents.map { it.eventId }.toSet()).isEmpty())
                assertEquals(Counts(2, 3, 2, 6, 0, 0, 0), counts(service))

                acknowledge(service, a, aBeforeFull)
                observe(service, a, aBeforeFull)
                val partialCut = service.prepareGate(a.client)
                assertEquals(aBeforeFull.map { it.eventId }, partialCut.requiredEventIds)
                assertEquals(listOf("full_project_scope_unproven",
                    "current_coverage_missing:${old.context.scope.id}:$RULE", "open_finding:$FINGERPRINT").sorted(), partialCut.reasons,
                    "Delivered partial acceptance is not full-scope accounting for the retained old finding")
                assertDenied(service, a, partialCut, aActions)
                assertEquals(Triple(0L, 0L, 0L), deliveryCounts(service, b))

                val fullSubmitted = service.submit(a.client, fullRequest)
                val aFull = record(service, a, fullRequest, awaitResult(service, a, fullSubmitted.reviewId), provider, "accepted")
                val history = earlier + aFull
                earlier.forEach { assertHistoryUnchanged(service, it) }
                val aEvents = events(service, a)
                assertEquals(aBeforeFull, aEvents.take(4))
                assertEquals(aFull.events, aEvents.drop(4))
                assertEquals(Counts(3, 4, 3, 8, 4, 4, 8), counts(service))
                val insertionMissing = aFull.events.map { "insertion_missing:${it.eventId}" }
                val observationMissing = aFull.events.map { "model_observation_missing:${it.eventId}" }
                val undeliveredFull = service.prepareGate(a.client)
                assertEquals(aEvents.map { it.eventId }, undeliveredFull.requiredEventIds)
                assertEquals((insertionMissing + observationMissing).sorted(), undeliveredFull.reasons,
                    "Exact current full-policy accounting clears only coverage/finding reasons, not delivery")
                assertDenied(service, a, undeliveredFull, aActions)

                acknowledge(service, a, aFull.events)
                val insertedFull = service.prepareGate(a.client)
                assertEquals(observationMissing.sorted(), insertedFull.reasons, "ACK alone is never model observation")
                assertDenied(service, a, insertedFull, aActions)
                observe(service, a, aFull.events)
                val readyA = service.prepareGate(a.client)
                assertTrue(readyA.ready, readyA.reasons.toString())
                assertTrue(readyA.reasons.isEmpty())
                assertEquals(Triple(6L, 6L, 12L), deliveryCounts(service, a))
                assertEquals(Triple(0L, 0L, 0L), deliveryCounts(service, b))
                assertEquals(bEvents, events(service, b))
                history.forEach { assertHistoryUnchanged(service, it) }

                // The scalar synthetic fence is globally at B. The peer's configuration mismatch
                // must not mask a leaked finding waiver or leaked A delivery/observation receipts.
                val peerUndelivered = service.prepareGate(b.client)
                assertTrue("configuration_authority_mismatch_or_unknown" in peerUndelivered.reasons)
                assertTrue("open_finding:$FINGERPRINT" in peerUndelivered.reasons)
                for (event in bEvents) {
                    assertTrue("insertion_missing:${event.eventId}" in peerUndelivered.reasons)
                    assertTrue("model_observation_missing:${event.eventId}" in peerUndelivered.reasons)
                }
                assertDenied(service, b, peerUndelivered, bActions)
                val retainedReceipts = receiptAudit(service)
                val retainedCounts = counts(service)
                val foreign = assertFailsWith<KernelException> {
                    service.acknowledge(a.adapter, listOf(bEvents.first().eventId), ContextReceipt("foreign-a-ack",
                        contextDigest(bEvents.first()), "foreign-a-item", Instant.EPOCH.toString()))
                }
                assertEquals("event_not_found", foreign.code)
                assertEquals(retainedReceipts, receiptAudit(service), "A rejected foreign ACK must mutate no receipt")
                assertEquals(retainedCounts, counts(service))

                acknowledge(service, b, bEvents)
                observe(service, b, bEvents)
                val deliveredPeer = service.prepareGate(b.client)
                assertEquals(bEvents.map { it.eventId }, deliveredPeer.requiredEventIds)
                assertTrue("configuration_authority_mismatch_or_unknown" in deliveredPeer.reasons)
                assertTrue("open_finding:$FINGERPRINT" in deliveredPeer.reasons,
                    "A's current full-scope acceptance cannot discharge B's captured old-policy finding")
                assertTrue(deliveredPeer.reasons.none { it.startsWith("insertion_missing:") || it.startsWith("model_observation_missing:") })
                assertDenied(service, b, deliveredPeer, bActions)
                assertEquals(0, aActions.get())
                assertEquals(0, bActions.get())
                // B's independent receipt/gate traffic must not invalidate A's previously ready cut.
                val done = service.completeGate(a.client, readyA.cutId) { aActions.incrementAndGet() }
                assertTrue(done.allowed, done.reasons.toString())
                assertTrue(done.actionPerformed)
                val replay = service.completeGate(a.client, readyA.cutId) { aActions.incrementAndGet() }
                assertFalse(replay.allowed)
                assertFalse(replay.actionPerformed)
                assertEquals(1, aActions.get())
                assertEquals(0, bActions.get())

                assertEquals(Counts(3, 4, 3, 8, 8, 8, 16), counts(service))
                assertEquals(Triple(6L, 6L, 12L), deliveryCounts(service, a))
                assertEquals(Triple(2L, 2L, 4L), deliveryCounts(service, b))
                assertEquals(3, provider.calls.get())
                assertEquals(0, provider.active.get())
                assertEquals(Kind.entries.toSet(), provider.attempted.toSet())
                assertEquals(3, provider.attempted.size, "Exactly one raw attempt of each kind, despite four reviews")
                assertEquals(Kind.entries.toSet(), provider.rawResults.keys)
                assertEquals(1, provider.rawResults.values.map { it.findings.single() }.toSet().size)
                assertTrue(provider.rawResults.values.all { it.findings.single().disposition == "open" })
                assertEquals(4, classifications.size)
                assertEquals(Classification(aBinding, full, "accepted"), classifications.last())
                assertEquals(mapOf(CaptureKey(aBinding, OLD_CONFIGURATION) to "open",
                    CaptureKey(bBinding, OLD_CONFIGURATION) to "open", CaptureKey(aBinding, CURRENT_CONFIGURATION) to "accepted"), captures)
                history.forEach { assertHistoryUnchanged(service, it) }
            } finally {
                // Release even if enrollment, either await, or any oracle fails; do this BEFORE
                // KernelService.use closes/joins its workers. No test-owned background thread leaks.
                provider.oldRelease.countDown()
            }
        }
    }

    private fun input(): AnalysisInput {
        val root = Files.createDirectory(temp.resolve("source"))
        val text = "val syntheticPolicySubject = 1\n"
        Files.writeString(root.resolve("Main.kt"), text)
        val snapshot = SnapshotManifest(Digests.sha256("synthetic-policy-snapshot:$text"), "workspace", "tree",
            listOf(SnapshotFile("Main.kt", Digests.sha256(text), text.toByteArray(Charsets.UTF_8).size.toLong())),
            ownerId = "owner", declaredRoots = listOf("."))
        return AnalysisInput(snapshot, root.toString(), AnalysisContext(scope = RuleContext(module = ":app"),
            compilerVersion = "synthetic/1", modelDigest = Digests.sha256("synthetic-model"),
            dependencyDigest = Digests.sha256("synthetic-dependencies"), toolchainDigest = Digests.sha256("synthetic-toolchain"),
            configurationDigest = OLD_CONFIGURATION, fullProject = true, compilationFiles = listOf("Main.kt")), listOf(RULE))
    }

    private fun awaitResult(service: KernelService, client: ClientRegistration, reviewId: String): ReviewStatus {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var status = service.status(client.client, reviewId)
        // Poll only for a terminal result; interleaving is established by the provider latch above.
        while (status.result == null && System.nanoTime() < deadline) {
            Thread.sleep(5)
            status = service.status(client.client, reviewId)
        }
        assertNotNull(status.result, "Bounded synthetic result wait expired")
        return status
    }

    private data class Recorded(val client: ClientRegistration, val status: ReviewStatus,
                                val audit: Map<String, String>, val events: List<ReviewEvent>)

    private fun record(service: KernelService, client: ClientRegistration, request: SubmitRequest, status: ReviewStatus,
                       provider: SyntheticProvider, disposition: String): Recorded {
        val input = request.input
        val effective = provider.effectiveInputs.getValue(kind(input))
        val raw = provider.rawResults.getValue(kind(input))
        val expected = raw.copy(findings = raw.findings.map { it.copy(disposition = disposition) })
        assertEquals("complete", status.state, status.reasons.toString())
        assertFalse(status.cancelled)
        assertTrue(status.reasons.isEmpty())
        assertEquals(expected, status.result)
        assertTrue(raw.completeFor(input))
        assertEquals("open", raw.findings.single().disposition)
        assertEquals(input.copy(materializedRoot = effective.materializedRoot, outputRoot = effective.outputRoot), effective)
        assertNotEquals(input.materializedRoot, effective.materializedRoot, "The real kernel must use its immutable archive")
        val audit = reviewAudit(service, client, status.reviewId)
        assertEquals(canonical(request), audit.getValue("request_json"))
        assertEquals(Digests.sha256(canonical(request)), audit.getValue("request_hash"))
        assertEquals(canonical(input), audit.getValue("review_input"))
        assertEquals(canonical(input), audit.getValue("work_input"))
        assertEquals(canonical(effective.copy(outputRoot = null)), audit.getValue("frozen_input"))
        assertEquals(canonical(raw), audit.getValue("raw_result"))
        assertEquals(canonical(expected), audit.getValue("effective_result"))
        assertEquals("complete", audit.getValue("work_state"))
        assertEquals("1", audit.getValue("attempt"))
        for (field in listOf("work_validation", "policy_validation", "progress_faults")) assertEquals("[]", audit.getValue(field))
        val rawDigest = Digests.sha256(canonical(raw))
        assertEquals(Digests.sha256(canonical(listOf(service.providerKey, input.analysisKey()))), audit.getValue("work_key"))
        assertEquals(input.analysisKey(), audit.getValue("input_key"))
        assertEquals(effective.analysisKey(), audit.getValue("effective_key"))
        assertEquals(rawDigest, audit.getValue("result_digest"))
        assertEquals(service.providerKey, audit.getValue("provider_key"))
        assertEquals(Digests.sha256(canonical(listOf(input.analysisKey(), effective.analysisKey(), rawDigest, service.providerKey))),
            audit.getValue("certificate_id"))
        val policy = assertNotNull(PolicyCertificate.decode(audit.getValue("policy_certificate")))
        assertEquals(PolicyCertificate.EVALUATED, policy.state)
        assertEquals(service.policyEvaluatorIdentity, policy.evaluatorIdentity)
        assertEquals(PolicyCertificate.effectiveDigest(service.policyEvaluatorIdentity, client.binding, input,
            rawDigest, Digests.sha256(canonical(expected))), policy.digest)
        val published = events(service, client).filter { it.reviewId == status.reviewId }
        assertEquals(listOf("accepted", "result"), published.map { it.kind })
        // Admission deliberately emits an accepted projection, not submit's queued/running state.
        assertEquals(listOf(status.copy(state = "accepted", result = null, reasons = emptyList()), status),
            published.map { ContractJson.decodeFromString<ReviewStatus>(it.payload) })
        assertTrue(published.all { it.patchId == status.patchId && it.sourceSnapshotId == input.snapshot.snapshotId })
        return Recorded(client, status, audit, published)
    }

    private fun assertHistoryUnchanged(service: KernelService, recorded: Recorded) {
        assertEquals(recorded.status, service.status(recorded.client.client, recorded.status.reviewId))
        assertEquals(recorded.audit, reviewAudit(service, recorded.client, recorded.status.reviewId))
        assertEquals(recorded.events, events(service, recorded.client).filter { it.reviewId == recorded.status.reviewId })
    }

    private fun reviewAudit(service: KernelService, client: ClientRegistration, reviewId: String): Map<String, String> =
        assertNotNull(service.ledger.read { c -> c.one("""SELECT r.review_id,r.client_id,r.patch_id,r.idempotency_key,
            r.request_json,r.request_hash,r.input_json review_input,r.work_key,r.sequence,r.cancelled,
            r.effective_result_json effective_result,r.policy_validation_json policy_validation,r.policy_certificate,
            r.created_at review_created_at,w.input_json work_input,w.frozen_json frozen_input,w.state work_state,
            w.attempt,w.result_json raw_result,w.validation_json work_validation,w.progress_faults_json progress_faults,
            w.created_at work_created_at,w.archive_bytes,wc.input_key,wc.effective_key,wc.result_digest,wc.provider_key,wc.certificate_id
            FROM reviews r JOIN work w ON w.work_key=r.work_key JOIN work_certificates wc ON wc.work_key=w.work_key
            WHERE r.review_id=? AND r.client_id=?""", reviewId, client.binding.clientId) { row(it) } })

    private fun row(result: ResultSet): Map<String, String> = (1..result.metaData.columnCount).associate { index ->
        result.metaData.getColumnLabel(index) to assertNotNull(result.getString(index))
    }

    private data class Counts(val work: Long, val reviews: Long, val certificates: Long, val events: Long,
                              val acknowledgements: Long, val observations: Long, val receipts: Long)

    private fun counts(service: KernelService): Counts = service.ledger.read { c -> Counts(
        c.count("SELECT COUNT(*) FROM work"), c.count("SELECT COUNT(*) FROM reviews"),
        c.count("SELECT COUNT(*) FROM work_certificates"), c.count("SELECT COUNT(*) FROM events"),
        c.count("SELECT COUNT(*) FROM acknowledgements"), c.count("SELECT COUNT(*) FROM observations"),
        c.count("SELECT COUNT(*) FROM receipts")) }

    private fun deliveryCounts(service: KernelService, client: ClientRegistration): Triple<Long, Long, Long> =
        service.ledger.read { c -> Triple(c.count("SELECT COUNT(*) FROM acknowledgements WHERE client_id=?", client.binding.clientId),
            c.count("SELECT COUNT(*) FROM observations WHERE client_id=?", client.binding.clientId),
            c.count("SELECT COUNT(*) FROM receipts WHERE client_id=?", client.binding.clientId)) }

    private fun receiptAudit(service: KernelService): List<List<Map<String, String>>> = service.ledger.read { c -> listOf(
        c.query("SELECT * FROM acknowledgements ORDER BY event_id") { row(it) },
        c.query("SELECT * FROM observations ORDER BY event_id,observation_id") { row(it) },
        c.query("SELECT * FROM receipts ORDER BY client_id,kind,receipt_id") { row(it) }) }

    private fun events(service: KernelService, client: ClientRegistration): List<ReviewEvent> {
        val page = service.events(client.client, limit = 16)
        assertFalse(page.hasMore, "This finite lineage fits in one bounded page")
        assertEquals((1..page.events.size).map { it.toLong() }, page.events.map { it.sequence })
        for (event in page.events) {
            assertEquals(client.binding.clientId, event.clientId)
            assertEquals(client.binding.generation, event.generation)
            assertTrue(event.deliveryRequired)
            assertEquals(Digests.sha256(event.payload), event.payloadDigest)
        }
        return page.events
    }

    private fun acknowledge(service: KernelService, client: ClientRegistration, events: List<ReviewEvent>) {
        for (event in events) assertEquals(ReceiptResult(client.binding.clientId, listOf(event.eventId), "acknowledged"),
            service.acknowledge(client.adapter, listOf(event.eventId), ContextReceipt("ack-${event.eventId}",
                contextDigest(event), "item-${event.eventId}", Instant.EPOCH.toString())))
    }

    private fun observe(service: KernelService, client: ClientRegistration, events: List<ReviewEvent>) {
        for (event in events) assertEquals(ReceiptResult(client.binding.clientId, listOf(event.eventId), "model_observed"),
            service.observe(client.adapter, listOf(event.eventId), ObservationReceipt("observation-${event.eventId}",
                "synthetic-model-request-${event.eventId}", listOf(contextDigest(event)))))
    }

    private fun assertDenied(service: KernelService, client: ClientRegistration, cut: GateCut, actions: AtomicInteger) {
        assertFalse(cut.ready)
        val before = actions.get()
        val result = service.completeGate(client.client, cut.cutId) { actions.incrementAndGet() }
        assertFalse(result.allowed, result.reasons.toString())
        assertFalse(result.actionPerformed)
        assertEquals(cut.reasons, result.reasons)
        assertEquals(before, actions.get(), "A denied gate must not enter its guarded action")
    }

    private data class CaptureKey(val binding: ClientBinding, val configuration: String)
    private data class Classification(val binding: ClientBinding, val originalInput: AnalysisInput, val disposition: String)
    private enum class Kind { OLD_FULL, CURRENT_PARTIAL, CURRENT_FULL }

    private class SyntheticProvider : AnalysisProvider {
        override val id = PROVIDER
        override val version = VERSION
        override val supportedRuleIds = setOf(RULE)
        val oldEntered = CountDownLatch(1)
        val oldRelease = CountDownLatch(1)
        val oldExited = CountDownLatch(1)
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val attempted = ConcurrentLinkedQueue<Kind>()
        val effectiveInputs = ConcurrentHashMap<Kind, AnalysisInput>()
        val rawResults = ConcurrentHashMap<Kind, ProviderResult>()

        override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
            val kind = kind(input)
            calls.incrementAndGet()
            attempted += kind
            val running = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, running) }
            try {
                check(effectiveInputs.putIfAbsent(kind, input) == null) { "Unexpected duplicate raw attempt" }
                if (kind == Kind.OLD_FULL) {
                    oldEntered.countDown()
                    check(oldRelease.await(20, TimeUnit.SECONDS)) { "Bounded old-provider barrier expired" }
                }
                cancellation.throwIfCancelled()
                return result(input).also { rawResults[kind] = it }
            } finally {
                active.decrementAndGet()
                if (kind == Kind.OLD_FULL) oldExited.countDown()
            }
        }
    }

    /** Synthetic global current-config authority. Scalar SnapshotGate has no client binding.
     * Full-project scope remains a separate gate predicate, not an assumption of this fence. */
    private class SyntheticFence(private val fixed: AnalysisInput) : SnapshotGate {
        private val lock = Any()
        private var configuration = checkNotNull(fixed.context.configurationDigest)
        private var policyEpoch = 1L
        fun updateConfiguration(next: String) = synchronized(lock) {
            require(Digests.valid(next))
            configuration = next
            policyEpoch++
        }
        override fun <T> withCurrentSnapshot(input: AnalysisInput, action: (SnapshotCheck) -> T): T = synchronized(lock) {
            action(SnapshotCheck(current = input.snapshot == fixed.snapshot && input.context.configurationDigest == configuration &&
                input.context.modelDigest == fixed.context.modelDigest && input.context.dependencyDigest == fixed.context.dependencyDigest &&
                input.context.toolchainDigest == fixed.context.toolchainDigest,
                snapshotId = fixed.snapshot.snapshotId, sourceEpoch = 1, configurationDigest = configuration,
                modelDigest = fixed.context.modelDigest, dependencyDigest = fixed.context.dependencyDigest,
                toolchainDigest = fixed.context.toolchainDigest, analysisEpoch = "synthetic-policy-epoch:$policyEpoch", authoritative = true))
        }
    }

    private companion object {
        const val RULE = "test/concurrent-policy-replacement"
        const val PROVIDER = "synthetic-concurrent-policy"
        const val VERSION = "synthetic-concurrent-policy/1"
        val OLD_CONFIGURATION = Digests.sha256("synthetic-captured-policy-old")
        val CURRENT_CONFIGURATION = Digests.sha256("synthetic-captured-policy-current")
        val FINGERPRINT = Digests.sha256("synthetic-identical-policy-finding")
        fun binding(id: String) = ClientBinding("owner", "workspace", "tree", id, "lineage-$id", sessionId = "session-$id")
        fun request(id: String, input: AnalysisInput) = SubmitRequest("patch-$id", "key-$id", input)
        fun contextDigest(event: ReviewEvent) = Digests.sha256("synthetic-insertion:${event.eventId}:${event.payloadDigest}")
        fun kind(input: AnalysisInput): Kind = when {
            input.context.configurationDigest == OLD_CONFIGURATION && input.context.fullProject -> Kind.OLD_FULL
            input.context.configurationDigest == CURRENT_CONFIGURATION && !input.context.fullProject -> Kind.CURRENT_PARTIAL
            input.context.configurationDigest == CURRENT_CONFIGURATION && input.context.fullProject -> Kind.CURRENT_FULL
            else -> error("Unexpected synthetic analysis identity")
        }
        fun result(input: AnalysisInput): ProviderResult = ProviderResult(PROVIDER, "complete", listOf(Finding(
            RULE, "Synthetic policy diagnostic", "Synthetic stable predicate", "Regression fixture only",
            SourceLocation("Main.kt", input.snapshot.files.single().digest, 1, 0, 1, 3, "val"), FINGERPRINT, PROVIDER,
            context = input.context.scope, severity = "warning",
            evidence = listOf(Evidence("static_rule", "Synthetic stable evidence", digest = Digests.sha256("synthetic-evidence"))))),
            coverage = listOf(Coverage(RULE, input.context.scope, "findings", provenance = "executed",
                certificateId = Digests.sha256("synthetic-coverage:${input.analysisKey()}"),
                // Structural synthetic coverage only, never a real qualification assertion.
                implemented = true, tested = true, qualified = true)),
            metrics = AnalysisMetrics(elapsedMillis = 11, cpuMillis = 7, peakRssBytes = 8_192, outputBytes = 123),
            providerVersion = VERSION)
    }
}

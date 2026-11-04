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
import dev.ktchecker.contracts.RawDiagnostic
import dev.ktchecker.contracts.RuleContext
import dev.ktchecker.contracts.SnapshotFile
import dev.ktchecker.contracts.SnapshotManifest
import dev.ktchecker.contracts.SourceLocation
import dev.ktchecker.contracts.Verification
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Broker/SQLite regression with synthetic providers, current-source authority and model receipts.
 * No compiler, build, target execution or genuine model delivery is exercised. This guards current
 * result publication, not ConfigStore's separate trusted historical-absence verifier semantics.
 */
class KernelPolicyFixedGuardTest {
    @TempDir lateinit var temp: Path

    private val rule = "test/current-failure-policy-guard"
    private val providerId = "synthetic-policy-guard"
    private val providerVersion = "synthetic-guard-1"
    private val policyError = "result_policy_changed_protected_evidence"
    private val testConfig = KernelConfig(
        workerCount = 1, globalParallelism = 1, perWorktreeParallelism = 1,
        heartbeatMillis = 30, leaseMillis = 120, pollMillis = 5, analysisTimeoutMillis = 15_000,
    )

    @Test fun fixedPolicyCannotResolveCurrentFailureOrRewriteFailedAndUnavailableExecution() {
        // "complete" means the synthetic diagnostic analysis completed while finding a failure;
        // it does not mean that failed execution passed. With successful required coverage and no
        // other validation gaps, this variant isolates rejection of policy-produced "fixed".
        for (rawState in listOf("complete", "failed", "unavailable")) {
            withReview("fixed-$rawState", rawState, currentFailure = true, proposedDisposition = "fixed") { fixture ->
                val raw = fixture.raw
                val finding = raw.findings.single()
                assertEquals(fixture.input.snapshot.snapshotId, fixture.status.sourceSnapshotId)
                assertEquals(fixture.input.context.scope, finding.context)
                assertEquals(fixture.input.snapshot.files.single().digest, finding.location.fileDigest)
                assertTrue(finding.evidence.any { it.kind == "execution_failure" })
                assertTrue(finding.verification.any { it.state == "failed" })
                assertEquals("open", finding.disposition, "The provider did not submit a forged lifecycle label")
                assertEquals(listOf(policyError), fixture.retained.policyIssues)
                assertEquals("incomplete", fixture.status.state)
                assertFalse(fixture.status.cancelled, "Cancellation must not explain this refusal")
                assertTrue(policyError in fixture.status.reasons)

                // Full DTO equality retains failure evidence, verification, coverage, reasons,
                // raw diagnostics, provider identity and metrics, not merely the finding count.
                assertEquals(raw, fixture.status.result)
                assertEquals(raw, fixture.deliveredResult.result)
                assertEquals(fixture.status.reasons, fixture.deliveredResult.reasons)
                assertEquals(canonical(raw), fixture.retained.rawJson)
                assertEquals(canonical(raw), fixture.retained.effectiveJson)
                if (rawState == "complete") {
                    assertTrue(raw.completeFor(fixture.input))
                    assertTrue(fixture.retained.workIssues.isEmpty(), fixture.retained.workIssues.toString())
                    assertEquals(listOf(policyError), fixture.status.reasons,
                        "The policy guard, not an already-failed provider or missing coverage, must reject this result")
                } else {
                    assertFalse(raw.completeFor(fixture.input))
                    assertEquals(rawState, raw.status)
                    assertEquals(rawState, raw.coverage.single().state)
                    assertTrue(raw.reasons.isNotEmpty())
                    assertTrue(raw.rawDiagnostics.isNotEmpty())
                    assertTrue("required_coverage_incomplete_or_wrong_context" in fixture.retained.workIssues)
                    assertTrue("coverage_unsuccessful:$rule" in fixture.retained.workIssues)
                    assertTrue("provider_reported_incompleteness" in fixture.retained.workIssues)
                    assertTrue("unaccounted_raw_diagnostics" in fixture.retained.workIssues)
                }

                // All required events have synthetic ACK/observation receipts, and the current
                // source authority is present. Missing delivery/source authority cannot mask a
                // policy-guard regression by independently keeping this barrier closed.
                val cut = fixture.service.prepareGate(fixture.client.client)
                assertEquals(fixture.events.map { it.eventId }, cut.requiredEventIds)
                assertFalse(cut.ready, rawState)
                assertTrue("review_incomplete:${fixture.status.reviewId}" in cut.reasons)
                assertTrue("open_finding:${finding.fingerprint}" in cut.reasons)
                assertTrue(cut.reasons.none { it.startsWith("insertion_missing:") || it.startsWith("model_observation_missing:") })
                assertFalse("current_source_configuration_authority_unavailable" in cut.reasons)
                val actions = AtomicInteger()
                val completion = fixture.service.completeGate(fixture.client.client, cut.cutId) { actions.incrementAndGet() }
                assertFalse(completion.allowed, completion.reasons.toString())
                assertFalse(completion.actionPerformed)
                assertEquals(0, actions.get(), "A rejected current result must never invoke the guarded final action")
                assertTrue("review_incomplete:${fixture.status.reviewId}" in completion.reasons)
                assertEquals(raw, fixture.service.status(fixture.client.client, fixture.status.reviewId).result)
            }
        }
    }

    @Test fun ordinaryAcceptanceControlPassesTheSameDeliveryAndCompletionHarnessWithoutChangingRawEvidence() {
        // This is a contrast for this exact harness, not a new suppression matching/scope test.
        withReview("accepted-control", "complete", currentFailure = false, proposedDisposition = "accepted") { fixture ->
            val raw = fixture.raw
            val expected = raw.copy(findings = raw.findings.map { it.copy(disposition = "accepted") })
            assertTrue(raw.completeFor(fixture.input))
            assertEquals("open", raw.findings.single().disposition)
            assertTrue(raw.findings.single().verification.isEmpty())
            assertEquals(listOf("static_rule"), raw.findings.single().evidence.map { it.kind })
            assertEquals("complete", fixture.status.state)
            assertTrue(fixture.status.reasons.isEmpty(), fixture.status.reasons.toString())
            assertTrue(fixture.retained.workIssues.isEmpty())
            assertTrue(fixture.retained.policyIssues.isEmpty())
            assertEquals(expected, fixture.status.result)
            assertEquals(expected, fixture.deliveredResult.result)
            assertEquals(canonical(raw), fixture.retained.rawJson)
            assertEquals(canonical(expected), fixture.retained.effectiveJson)

            val cut = fixture.service.prepareGate(fixture.client.client)
            assertEquals(fixture.events.map { it.eventId }, cut.requiredEventIds)
            assertTrue(cut.ready, cut.reasons.toString())
            val actions = AtomicInteger()
            val completion = fixture.service.completeGate(fixture.client.client, cut.cutId) { actions.incrementAndGet() }
            assertTrue(completion.allowed, completion.reasons.toString())
            assertTrue(completion.actionPerformed)
            assertEquals(1, actions.get())
        }
    }

    private data class RetainedResult(
        val rawJson: String,
        val effectiveJson: String,
        val workIssues: List<String>,
        val policyIssues: List<String>,
    )

    private data class ReviewFixture(
        val input: AnalysisInput,
        val service: KernelService,
        val client: ClientRegistration,
        val status: ReviewStatus,
        val raw: ProviderResult,
        val retained: RetainedResult,
        val events: List<ReviewEvent>,
        val deliveredResult: ReviewStatus,
    )

    private fun withReview(
        name: String,
        rawState: String,
        currentFailure: Boolean,
        proposedDisposition: String,
        check: (ReviewFixture) -> Unit,
    ) {
        val input = fixtureInput(name)
        val produced = AtomicReference<ProviderResult?>(null)
        val policyInput = AtomicReference<ProviderResult?>(null)
        val providerCalls = AtomicInteger()
        val policyCalls = AtomicInteger()
        val provider = object : AnalysisProvider {
            override val id = providerId
            override val version = providerVersion
            override val supportedRuleIds = setOf(rule)
            override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
                cancellation.throwIfCancelled()
                providerCalls.incrementAndGet()
                return rawResult(input, rawState, currentFailure).also { produced.set(it) }
            }
        }
        val policy = ResultPolicy { _, _, raw ->
            policyCalls.incrementAndGet()
            policyInput.set(raw)
            // No other field is changed: rejecting this candidate specifically exercises the
            // kernel's disposition allowlist rather than its general immutable-evidence check.
            raw.copy(findings = raw.findings.map { it.copy(disposition = proposedDisposition) })
        }
        KernelService.open(temp.resolve("ledger-$name"), provider, testConfig, SyntheticFence(input), resultPolicy = policy).use { service ->
            val client = service.registerClient(ClientBinding("owner", "workspace", "tree", name, "lineage-$name", sessionId = "session-$name"))
            val submitted = service.submit(client.client, SubmitRequest("patch-$name", "idempotency-$name", input))
            val status = awaitResult(service, client, submitted.reviewId)
            val raw = assertNotNull(produced.get())
            assertEquals(1, providerCalls.get())
            assertEquals(1, policyCalls.get(), "The candidate disposition must actually reach the policy guard")
            assertEquals(raw, policyInput.get(), "The callback receives the detached current raw result")
            val retained = service.ledger.read { connection ->
                connection.one("""SELECT w.result_json,r.effective_result_json,w.validation_json,r.policy_validation_json
                    FROM reviews r JOIN work w ON w.work_key=r.work_key WHERE r.review_id=? AND r.client_id=?""",
                    status.reviewId, client.binding.clientId) { row ->
                    RetainedResult(row.getString("result_json"), row.getString("effective_result_json"),
                        ContractJson.decodeFromString<List<String>>(row.getString("validation_json")),
                        ContractJson.decodeFromString<List<String>>(row.getString("policy_validation_json")))
                }
            }
            val events = observeAll(service, client)
            val delivered = ContractJson.decodeFromString<ReviewStatus>(events.single { it.kind == "result" }.payload)
            assertEquals(status.reviewId, delivered.reviewId)
            assertEquals(status.state, delivered.state)
            check(ReviewFixture(input, service, client, status, raw, assertNotNull(retained), events, delivered))
        }
    }

    private fun fixtureInput(name: String): AnalysisInput {
        val root = temp.resolve("source-$name")
        val bytes = "fun currentPolicyGuardFixture() = Unit\n".toByteArray(Charsets.UTF_8)
        Files.createDirectory(root)
        Files.write(root.resolve("Main.kt"), bytes)
        val snapshot = SnapshotManifest(
            Digests.sha256("synthetic-policy-guard:$name:${Digests.sha256(bytes)}"), "workspace", "tree",
            listOf(SnapshotFile("Main.kt", Digests.sha256(bytes), bytes.size.toLong())),
            ownerId = "owner", declaredRoots = listOf("."),
        )
        return AnalysisInput(snapshot, root.toString(), AnalysisContext(
            scope = RuleContext(module = ":app", sourceSet = "main", target = "jvm", compilation = "main"),
            modelDigest = Digests.sha256("synthetic-model"), dependencyDigest = Digests.sha256("synthetic-dependencies"),
            toolchainDigest = Digests.sha256("synthetic-tools"), configurationDigest = Digests.sha256("synthetic-policy"), fullProject = true,
        ), requiredRuleIds = listOf(rule))
    }

    private fun rawResult(input: AnalysisInput, state: String, currentFailure: Boolean): ProviderResult {
        val location = SourceLocation("Main.kt", input.snapshot.files.single().digest, 1, 0, 1, 3, "fun")
        val finding = Finding(
            rule, "Synthetic current diagnostic", "Synthetic predicate remains present", "Regression fixture only",
            location, Digests.sha256("synthetic-current-finding:${input.snapshot.snapshotId}"), providerId,
            context = input.context.scope, severity = if (currentFailure) "error" else "warning",
            evidence = listOf(Evidence(if (currentFailure) "execution_failure" else "static_rule",
                "Synthetic current evidence", artifactRef = "synthetic:retained-report", digest = Digests.sha256("synthetic-evidence"))),
            verification = if (currentFailure) listOf(Verification("build", "Synthetic execution must succeed", state = "failed")) else emptyList(),
        )
        return ProviderResult(
            providerId, state, listOf(finding),
            coverage = listOf(Coverage(rule, input.context.scope, if (state == "complete") "findings" else state,
                reason = if (state == "complete") null else "Synthetic execution $state",
                provenance = if (state == "unavailable") "not_executed" else "executed",
                certificateId = if (state == "complete") Digests.sha256("synthetic-coverage:${input.analysisKey()}") else null,
                // Synthetic structural fixtures only; these flags do not qualify a real provider.
                implemented = true, tested = true, qualified = true)),
            reasons = if (state == "complete") emptyList() else listOf("synthetic_execution_$state"),
            rawDiagnostics = if (state == "complete") emptyList() else listOf(
                RawDiagnostic("Retain synthetic $state diagnostic", "error", "SYNTHETIC_FAILURE", location)),
            metrics = AnalysisMetrics(elapsedMillis = 11, cpuMillis = 7, peakRssBytes = 8_192, outputBytes = 123),
            providerVersion = providerVersion,
        )
    }

    /** Fixed immutable test authority, not a claim about a live workspace or real model. */
    private class SyntheticFence(private val expected: AnalysisInput) : SnapshotGate {
        private val lock = Any()
        override fun <T> withCurrentSnapshot(input: AnalysisInput, action: (SnapshotCheck) -> T): T = synchronized(lock) {
            action(SnapshotCheck(
                current = input.snapshot == expected.snapshot && input.context == expected.context,
                snapshotId = expected.snapshot.snapshotId, sourceEpoch = 1L,
                configurationDigest = expected.context.configurationDigest, modelDigest = expected.context.modelDigest,
                dependencyDigest = expected.context.dependencyDigest, toolchainDigest = expected.context.toolchainDigest,
                analysisEpoch = "synthetic-policy-guard-epoch:1", authoritative = true,
            ))
        }
    }

    private fun awaitResult(service: KernelService, client: ClientRegistration, reviewId: String): ReviewStatus {
        val started = System.nanoTime()
        val limit = TimeUnit.SECONDS.toNanos(15)
        var status = service.status(client.client, reviewId)
        while (status.result == null && System.nanoTime() - started < limit) {
            Thread.sleep(5)
            status = service.status(client.client, reviewId)
        }
        assertNotNull(status.result, "Bounded wait expired before terminal result: $status")
        return status
    }

    private fun observeAll(service: KernelService, client: ClientRegistration): List<ReviewEvent> {
        val page = service.events(client.client, limit = 8)
        assertFalse(page.hasMore, "A single non-progressive review has a bounded event frontier")
        assertEquals(listOf("accepted", "result"), page.events.map { it.kind })
        for (event in page.events) {
            assertEquals(client.binding.clientId, event.clientId)
            assertTrue(event.deliveryRequired)
            assertEquals(Digests.sha256(event.payload), event.payloadDigest)
            val context = Digests.sha256("synthetic-context-item:${event.payload}")
            service.acknowledge(client.adapter, listOf(event.eventId), ContextReceipt(
                "ack-${event.eventId}", context, "item-${event.eventId}", Instant.EPOCH.toString()))
            service.observe(client.adapter, listOf(event.eventId), ObservationReceipt(
                "observation-${event.eventId}", "synthetic-model-request-${event.eventId}", listOf(context)))
        }
        return page.events
    }
}

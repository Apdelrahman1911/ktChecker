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
import kotlin.test.assertNotEquals
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

    @Test fun accountedDispositionsCannotWaiveAnIsolatedExecutionFailureOrFailedVerification() {
        val labels = listOf("accepted", "suppressed", "disabled", "baselined", "fixed", "unavailable")
        for (signal in listOf("execution", "verification")) for (label in labels) {
            withReview("$signal-$label", "complete", currentFailure = false, proposedDisposition = label,
                rawTransform = { raw -> raw.copy(findings = raw.findings.map { finding ->
                    if (signal == "execution") finding.copy(evidence = listOf(Evidence("execution_failure", "Synthetic failed execution")))
                    else finding.copy(verification = listOf(Verification("build", "Synthetic build must succeed", state = "failed")))
                }) }) { fixture ->
                val finding = fixture.raw.findings.single()
                assertEquals("warning", finding.severity, "A severe diagnostic must not mask either independent signal")
                if (signal == "execution") {
                    assertEquals(listOf("execution_failure"), finding.evidence.map { it.kind })
                    assertTrue(finding.verification.isEmpty())
                } else {
                    assertEquals(listOf("static_rule"), finding.evidence.map { it.kind })
                    assertEquals(listOf("failed"), finding.verification.map { it.state })
                }
                assertRejectedPolicy(fixture, finding)
            }
        }
    }

    @Test fun intrinsicFailureKindsVerificationStatesAndSevereExecutionDiagnosticsAreIndependentlyProtected() {
        val signals = buildList<Pair<String, (Finding) -> Finding>> {
            for (kind in listOf("build_failure", "EXECUTION_FAILURE", "compiler_failure", "test_failure", "worker_failure")) {
                add("evidence-$kind" to { finding: Finding -> finding.copy(evidence = listOf(Evidence(kind, "Synthetic current failure"))) })
            }
            for (state in listOf("failed", "ERROR", "crashed")) {
                add("verification-$state" to { finding: Finding -> finding.copy(verification = listOf(Verification("check", "Must succeed", state = state))) })
            }
            for (kind in listOf("compiler_diagnostic", "build_result", "test_result", "execution_result")) {
                add("diagnostic-$kind" to { finding: Finding -> finding.copy(severity = "error", evidence = listOf(Evidence(kind, "Synthetic current error"))) })
            }
            for (provider in listOf("fixture-COMPILER", "fixture-native", "fixture-build", "fixture-runtime")) {
                add("provider-$provider" to { finding: Finding -> finding.copy(severity = "error", providerId = provider) })
            }
        }
        for ((name, transform) in signals) {
            val trustedProviders = if (name.startsWith("provider-")) setOf(name.removePrefix("provider-")) else setOf(providerId)
            withReview("signal-$name", "complete", currentFailure = false, proposedDisposition = "accepted",
                findingProviders = trustedProviders, rawTransform = { raw -> raw.copy(findings = raw.findings.map(transform)) }) { fixture ->
                assertRejectedPolicy(fixture, fixture.raw.findings.single())
            }
        }
    }

    @Test fun ordinaryCompilerWarningsAndUnprotectedStaticErrorsRetainTheirAccounting() {
        for (kind in listOf("compiler-warning", "static-error")) {
            for (label in listOf("accepted", "suppressed", "disabled", "baselined", "unavailable")) {
                withReview("ordinary-$kind-$label", "complete", currentFailure = false, proposedDisposition = label,
                    rawTransform = { raw -> raw.copy(findings = raw.findings.map {
                        if (kind == "static-error") it.copy(severity = "error")
                        else it.copy(evidence = listOf(Evidence("compiler_diagnostic", "Synthetic non-failing compiler warning")),
                            verification = listOf(Verification("check", "No failure", state = "passed")))
                    }) }) { fixture ->
                    val expected = fixture.raw.copy(findings = fixture.raw.findings.map { it.copy(disposition = label) })
                    assertPolicyAccounting(fixture, expected)
                    assertEquals("complete", fixture.status.state)
                    assertTrue(fixture.status.reasons.isEmpty())
                    assertTrue(fixture.retained.workIssues.isEmpty())
                    val cut = fixture.service.prepareGate(fixture.client.client)
                    if (label == "unavailable") {
                        assertTrue("finding_unavailable:${fixture.raw.findings.single().fingerprint}" in cut.reasons)
                        assertFalse(cut.ready)
                        val actions = AtomicInteger()
                        val completion = fixture.service.completeGate(fixture.client.client, cut.cutId) { actions.incrementAndGet() }
                        assertFalse(completion.allowed)
                        assertFalse(completion.actionPerformed)
                        assertEquals(0, actions.get())
                    } else {
                        assertTrue(cut.ready, cut.reasons.toString())
                        val actions = AtomicInteger()
                        val completion = fixture.service.completeGate(fixture.client.client, cut.cutId) { actions.incrementAndGet() }
                        assertTrue(completion.allowed, completion.reasons.toString())
                        assertTrue(completion.actionPerformed)
                        assertEquals(1, actions.get())
                    }
                }
            }
        }
    }

    @Test fun oneWaivedFailureRejectsTheEntireMixedPolicyProjectionRatherThanPublishingAPrefix() {
        for (label in listOf("accepted", "suppressed", "disabled", "baselined")) {
            withReview("mixed-illegal-$label", "complete", currentFailure = false, proposedDisposition = label,
                rawTransform = ::withSeparateExecutionFailure) { fixture ->
                assertEquals(2, fixture.raw.findings.size)
                assertEquals(listOf("static_rule", "execution_failure"), fixture.raw.findings.map { it.evidence.single().kind })
                assertRejectedPolicy(fixture, fixture.raw.findings.last())
                assertEquals(listOf("open", "open"), fixture.status.result!!.findings.map { it.disposition })
            }
        }
    }

    @Test fun validWarningAccountingAlongsideAnOpenFailureRemainsVisibleButCannotAuthorizeCompletion() {
        withReview("mixed-open-failure", "complete", currentFailure = false, proposedDisposition = "accepted",
            rawTransform = ::withSeparateExecutionFailure,
            policyTransform = { result -> result.copy(findings = result.findings.map { finding ->
                if (finding.evidence.any { it.kind == "execution_failure" }) finding.copy(disposition = "open") else finding
            }) }) { fixture ->
            val expected = fixture.raw.copy(findings = fixture.raw.findings.mapIndexed { index, finding ->
                if (index == 0) finding.copy(disposition = "accepted") else finding
            })
            assertPolicyAccounting(fixture, expected)
            assertEquals("complete", fixture.status.state, "Diagnostic analysis completed; current failed execution still blocks the gate")
            assertTrue(fixture.retained.workIssues.isEmpty())
            assertTrue(fixture.status.reasons.isEmpty())
            assertGateRefused(fixture, fixture.raw.findings.last(), requireIncomplete = false)
        }
    }

    @Test fun ordinaryAccountingNeverRewritesFailedUnavailableCancelledOrIncompleteProviderEvidence() {
        for (state in listOf("failed", "unavailable", "cancelled", "incomplete", "complete")) {
            withReview("provider-state-$state", state, currentFailure = false, proposedDisposition = "accepted",
                rawTransform = { raw ->
                    if (state == "complete") raw.copy(coverage = raw.coverage.map { it.copy(state = "failed", reason = "Synthetic coverage failure") })
                    else raw
                }) { fixture ->
                val expected = fixture.raw.copy(findings = fixture.raw.findings.map { it.copy(disposition = "accepted") })
                assertPolicyAccounting(fixture, expected)
                assertEquals("incomplete", fixture.status.state)
                assertEquals(state, fixture.status.result!!.status)
                assertFalse(fixture.raw.completeFor(fixture.input))
                assertTrue("required_coverage_incomplete_or_wrong_context" in fixture.retained.workIssues)
                assertTrue("coverage_unsuccessful:$rule" in fixture.retained.workIssues)
                if (state != "complete") {
                    assertTrue("provider_reported_incompleteness" in fixture.retained.workIssues)
                    assertTrue("unaccounted_raw_diagnostics" in fixture.retained.workIssues)
                } else {
                    assertTrue(fixture.raw.reasons.isEmpty())
                    assertTrue(fixture.raw.rawDiagnostics.isEmpty(), "Failed coverage alone must keep completion closed")
                }
                assertGateRefused(fixture, requireIncomplete = true)
            }
        }
    }

    @Test fun policyCannotChangeFailureStatusOrCoverageEvenWhileApplyingOtherwiseValidWarningAccounting() {
        for (field in listOf("status", "coverage")) {
            withReview("rewritten-$field", "failed", currentFailure = false, proposedDisposition = "accepted",
                policyTransform = { result ->
                    if (field == "status") result.copy(status = "complete")
                    else result.copy(coverage = result.coverage.map { it.copy(state = "findings", reason = null,
                        certificateId = Digests.sha256("forged-policy-coverage"), provenance = "executed") })
                }) { fixture ->
                assertRejectedPolicy(fixture, fixture.raw.findings.single(), workMustBeComplete = false)
                assertEquals("failed", fixture.status.result!!.status)
                assertEquals("failed", fixture.status.result!!.coverage.single().state)
                assertTrue(fixture.raw.reasons.isNotEmpty())
                assertTrue(fixture.raw.rawDiagnostics.isNotEmpty())
            }
        }
    }

    @Test fun unchangedCallerIdentityCannotAdoptPreGuardAcceptanceOnRestartAndRawReuseUsesTheNewGuard() {
        // A data-only schema2 fixture models a previously evaluated unsafe acceptance. No old
        // kernel binary is executed, and production code never rewrites these historical rows.
        val original = archivedGuardReview("pre-guard", currentFailure = true, legacy = true)
        val providerCalls = AtomicInteger()
        val policyCalls = AtomicInteger()
        val provider = syntheticProvider {
            providerCalls.incrementAndGet()
            error("A retained raw result must not replay its provider")
        }
        val policy = acceptingPolicy(original.declaredIdentity, policyCalls)
        assertEquals(original.declaredIdentity, policy.evaluatorIdentity, "The caller's declared identity did not change")
        KernelService.open(original.root, provider, testConfig.copy(workerCount = 0), SyntheticFence(original.input),
            resultPolicy = policy).use { service ->
            val oldCertificate = assertNotNull(PolicyCertificate.decode(original.retained[4]))
            assertNotEquals(oldCertificate.evaluatorIdentity, service.policyEvaluatorIdentity)
            val status = service.status(original.client.client, original.reviewId)
            assertEquals("incomplete", status.state)
            assertEquals(listOf("policy_evaluator_mismatch"), status.reasons)
            assertEquals(original.effective, status.result, "Historical acceptance is inspectable, not upgraded or erased")
            assertEquals(status, service.submit(original.client.client, original.request))
            assertEquals(0, service.recoverPolicyEvaluations(), "Old terminal history is not silently relabelled")
            assertEquals(0, policyCalls.get())
            assertEquals(original.retained, retainedPolicyAudit(service, original.reviewId))
            assertEquals(original.events, service.events(original.client.client, limit = 8).events)
            val newCut = service.prepareGate(original.client.client)
            assertFalse(newCut.ready)
            assertTrue("policy_evaluator_mismatch:${original.reviewId}" in newCut.reasons)
            assertTrue(newCut.reasons.none { it.startsWith("insertion_missing:") || it.startsWith("model_observation_missing:") })
            assertFalse("current_source_configuration_authority_unavailable" in newCut.reasons)
            val actions = AtomicInteger()
            for (cut in listOf(original.cut, newCut)) {
                val completion = service.completeGate(original.client.client, cut.cutId) { actions.incrementAndGet() }
                assertFalse(completion.allowed)
                assertFalse(completion.actionPerformed)
                assertTrue("policy_evaluator_mismatch:${original.reviewId}" in completion.reasons)
            }
            assertEquals(0, actions.get(), "Even a retained formerly-ready cut cannot authorize the action")

            // Re-evaluation is a fresh obligation, not an in-place rewrite of old owner history.
            val fresh = service.registerClient(ClientBinding("owner", "workspace", "tree", "guard-recheck", "guard-recheck-lineage",
                sessionId = "guard-recheck-session"))
            val rechecked = service.submit(fresh.client, SubmitRequest("guard-recheck-patch", "guard-recheck-key", original.input))
            assertEquals("incomplete", rechecked.state)
            assertEquals(listOf(policyError), rechecked.reasons)
            assertEquals(original.raw, rechecked.result)
            assertEquals(1, policyCalls.get(), "The retained raw cache is evaluated exactly once under the new guard")
            assertEquals(0, providerCalls.get())
            val freshAudit = retainedPolicyAudit(service, rechecked.reviewId)
            assertEquals(original.retained[0], freshAudit[0], "The same immutable raw execution evidence is reused")
            assertEquals(canonical(original.raw), freshAudit[2])
            assertEquals(service.policyEvaluatorIdentity, assertNotNull(PolicyCertificate.decode(freshAudit[4])).evaluatorIdentity)
            observeAll(service, fresh)
            val freshCut = service.prepareGate(fresh.client)
            assertFalse(freshCut.ready)
            assertTrue("open_finding:${original.raw.findings.single().fingerprint}" in freshCut.reasons)
            assertFalse(service.completeGate(fresh.client, freshCut.cutId) { actions.incrementAndGet() }.allowed)
            assertEquals(0, actions.get())
            assertEquals(original.retained, retainedPolicyAudit(service, original.reviewId))
            assertEquals(original.events, service.events(original.client.client, limit = 8).events)
        }
    }

    @Test fun sameGuardRevisionAndCallerIdentityRetainOrdinaryAcceptanceAcrossRestartWithoutReplay() {
        val original = archivedGuardReview("same-guard", currentFailure = false, legacy = false)
        val providerCalls = AtomicInteger()
        val policyCalls = AtomicInteger()
        val provider = syntheticProvider { providerCalls.incrementAndGet(); error("Completed work must not replay") }
        val policy = acceptingPolicy(original.declaredIdentity, policyCalls)
        assertEquals(original.declaredIdentity, policy.evaluatorIdentity)
        KernelService.open(original.root, provider, testConfig.copy(workerCount = 0), SyntheticFence(original.input),
            resultPolicy = policy).use { service ->
            assertEquals(service.policyEvaluatorIdentity, assertNotNull(PolicyCertificate.decode(original.retained[4])).evaluatorIdentity)
            val status = service.submit(original.client.client, original.request)
            assertEquals("complete", status.state)
            assertTrue(status.reasons.isEmpty())
            assertEquals(original.effective, status.result)
            assertEquals(original.retained, retainedPolicyAudit(service, original.reviewId))
            assertEquals(original.events, service.events(original.client.client, limit = 8).events)
            val actions = AtomicInteger()
            val completion = service.completeGate(original.client.client, original.cut.cutId) { actions.incrementAndGet() }
            assertTrue(completion.allowed, completion.reasons.toString())
            assertTrue(completion.actionPerformed)
            assertEquals(1, actions.get())
            assertEquals(0, policyCalls.get())
            assertEquals(0, providerCalls.get())
        }
    }

    private data class ArchivedGuardReview(
        val root: Path,
        val input: AnalysisInput,
        val declaredIdentity: String,
        val client: ClientRegistration,
        val request: SubmitRequest,
        val reviewId: String,
        val raw: ProviderResult,
        val effective: ProviderResult,
        val retained: List<String?>,
        val events: List<ReviewEvent>,
        val cut: GateCut,
    )

    private fun archivedGuardReview(name: String, currentFailure: Boolean, legacy: Boolean): ArchivedGuardReview {
        val input = fixtureInput(name)
        val root = temp.resolve("ledger-$name")
        val declared = "synthetic-caller-policy/$name/1"
        val providerCalls = AtomicInteger()
        val policyCalls = AtomicInteger()
        val provider = syntheticProvider { observed -> providerCalls.incrementAndGet(); rawResult(observed, "complete", currentFailure) }
        return KernelService.open(root, provider, testConfig, SyntheticFence(input), resultPolicy = acceptingPolicy(declared, policyCalls)).use { service ->
            val client = service.registerClient(ClientBinding("owner", "workspace", "tree", name, "lineage-$name", sessionId = "session-$name"))
            val request = SubmitRequest("patch-$name", "key-$name", input)
            val status = awaitResult(service, client, service.submit(client.client, request).reviewId)
            assertEquals(1, providerCalls.get())
            assertEquals(1, policyCalls.get())
            val raw = ContractJson.decodeFromString<ProviderResult>(retainedPolicyAudit(service, status.reviewId)[0]!!)
            assertTrue(raw.completeFor(input))
            val accepted = raw.copy(findings = raw.findings.map { it.copy(disposition = "accepted") })
            if (legacy) {
                assertEquals("incomplete", status.state)
                assertEquals(listOf(policyError), status.reasons)
                // Preserve the real raw result/work certificate. Install only the exact terminal
                // policy projection and undelivered outbox shape that the old guard allowed.
                val oldIdentity = Digests.sha256(canonical(listOf("declared-result-policy/1", declared)))
                val certificate = PolicyCertificate(oldIdentity, PolicyCertificate.EVALUATED,
                    PolicyCertificate.effectiveDigest(oldIdentity, client.binding, input, Digests.sha256(canonical(raw)), Digests.sha256(canonical(accepted))))
                val payload = canonical(status.copy(state = "complete", result = accepted, reasons = emptyList()))
                service.ledger.transaction { connection ->
                    assertEquals(1, connection.execute("UPDATE reviews SET effective_result_json=?,policy_validation_json=?,policy_certificate=? WHERE review_id=?",
                        canonical(accepted), canonical(emptyList<String>()), canonical(certificate), status.reviewId))
                    assertEquals(1, connection.execute("UPDATE events SET payload=?,payload_digest=? WHERE review_id=? AND kind='result'",
                        payload, Digests.sha256(payload), status.reviewId))
                }
            } else {
                assertEquals("complete", status.state)
                assertTrue(status.reasons.isEmpty())
                assertEquals(accepted, status.result)
            }
            val events = observeAll(service, client)
            val cut = service.prepareGate(client.client)
            val archivedCut = if (legacy) cut.copy(ready = true, reasons = emptyList()) else cut
            if (legacy) {
                assertFalse(cut.ready, "The current guard must refuse this old identity even before restart")
                assertTrue("policy_evaluator_mismatch:${status.reviewId}" in cut.reasons)
                // A former ready cut is fixture data too; completeGate must recompute authority.
                service.ledger.transaction { connection ->
                    assertEquals(1, connection.execute("UPDATE gate_cuts SET cut_json=? WHERE cut_id=?", canonical(archivedCut), cut.cutId))
                }
            } else assertTrue(cut.ready, cut.reasons.toString())
            ArchivedGuardReview(root, input, declared, client, request, status.reviewId, raw, accepted,
                retainedPolicyAudit(service, status.reviewId), events, archivedCut)
        }
    }

    /** Both the raw work certificate and old policy projection are retained byte-for-byte. */
    private fun retainedPolicyAudit(service: KernelService, reviewId: String): List<String?> = service.ledger.read { connection ->
        assertNotNull(connection.one("""SELECT w.result_json,w.validation_json,r.effective_result_json,r.policy_validation_json,r.policy_certificate,
            c.input_key,c.effective_key,c.result_digest,c.provider_key,c.certificate_id FROM reviews r
            JOIN work w ON w.work_key=r.work_key JOIN work_certificates c ON c.work_key=w.work_key WHERE r.review_id=?""", reviewId) { row ->
            (1..10).map { row.getString(it) }
        })
    }

    private fun acceptingPolicy(identity: String, calls: AtomicInteger): ResultPolicy = ResultPolicy.versioned(identity) { _, _, raw ->
        calls.incrementAndGet()
        raw.copy(findings = raw.findings.map { it.copy(disposition = "accepted") })
    }

    private fun syntheticProvider(produce: (AnalysisInput) -> ProviderResult): AnalysisProvider = object : AnalysisProvider {
        override val id = providerId
        override val version = providerVersion
        override val supportedRuleIds = setOf(rule)
        override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
            cancellation.throwIfCancelled()
            return produce(input)
        }
    }

    private fun withSeparateExecutionFailure(raw: ProviderResult): ProviderResult {
        val warning = raw.findings.single()
        val failure = warning.copy(title = "Separate current execution failure",
            fingerprint = Digests.sha256("separate-failure:${warning.fingerprint}"),
            evidence = listOf(Evidence("execution_failure", "Synthetic distinct failed execution")))
        return raw.copy(findings = listOf(warning, failure))
    }

    private fun assertRejectedPolicy(fixture: ReviewFixture, openFinding: Finding, workMustBeComplete: Boolean = true) {
        assertEquals(listOf(policyError), fixture.retained.policyIssues)
        assertEquals("incomplete", fixture.status.state)
        assertFalse(fixture.status.cancelled)
        assertTrue(policyError in fixture.status.reasons)
        assertEquals(fixture.raw, fixture.status.result)
        assertEquals(fixture.raw, fixture.deliveredResult.result)
        assertEquals(fixture.status.reasons, fixture.deliveredResult.reasons)
        assertEquals(canonical(fixture.raw), fixture.retained.rawJson)
        assertEquals(canonical(fixture.raw), fixture.retained.effectiveJson)
        if (workMustBeComplete) {
            assertTrue(fixture.raw.completeFor(fixture.input))
            assertTrue(fixture.retained.workIssues.isEmpty(), fixture.retained.workIssues.toString())
            assertEquals(listOf(policyError), fixture.status.reasons, "Only the policy guard may explain rejection")
        }
        assertGateRefused(fixture, openFinding, requireIncomplete = true)
    }

    private fun assertPolicyAccounting(fixture: ReviewFixture, expected: ProviderResult) {
        assertTrue(fixture.retained.policyIssues.isEmpty(), fixture.retained.policyIssues.toString())
        assertEquals(expected, fixture.status.result)
        assertEquals(expected, fixture.deliveredResult.result)
        assertEquals(fixture.status.reasons, fixture.deliveredResult.reasons)
        assertEquals(fixture.raw, expected.copy(findings = fixture.raw.findings), "No non-disposition evidence may change")
        assertEquals(canonical(fixture.raw), fixture.retained.rawJson)
        assertEquals(canonical(expected), fixture.retained.effectiveJson)
    }

    private fun assertGateRefused(fixture: ReviewFixture, openFinding: Finding? = null, requireIncomplete: Boolean) {
        val cut = fixture.service.prepareGate(fixture.client.client)
        assertEquals(fixture.events.map { it.eventId }, cut.requiredEventIds)
        assertFalse(cut.ready, cut.reasons.toString())
        if (requireIncomplete) assertTrue("review_incomplete:${fixture.status.reviewId}" in cut.reasons)
        if (openFinding != null) assertTrue("open_finding:${openFinding.fingerprint}" in cut.reasons)
        assertTrue(cut.reasons.none { it.startsWith("insertion_missing:") || it.startsWith("model_observation_missing:") })
        assertFalse("current_source_configuration_authority_unavailable" in cut.reasons)
        val actions = AtomicInteger()
        val completion = fixture.service.completeGate(fixture.client.client, cut.cutId) { actions.incrementAndGet() }
        assertFalse(completion.allowed, completion.reasons.toString())
        assertFalse(completion.actionPerformed)
        assertEquals(0, actions.get(), "Retained failure evidence must prevent the actual guarded action")
        assertEquals(fixture.status.result, fixture.service.status(fixture.client.client, fixture.status.reviewId).result)
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
        rawTransform: (ProviderResult) -> ProviderResult = { it },
        policyTransform: (ProviderResult) -> ProviderResult = { it },
        findingProviders: Set<String> = setOf(providerId),
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
                return rawTransform(rawResult(input, rawState, currentFailure)).also { produced.set(it) }
            }
        }
        val policy = ResultPolicy { _, _, raw ->
            policyCalls.incrementAndGet()
            policyInput.set(raw)
            // By default only accounting changes; explicit negative controls also attempt to
            // rewrite failed status/coverage and must be rejected without changing retained raw data.
            policyTransform(raw.copy(findings = raw.findings.map { it.copy(disposition = proposedDisposition) }))
        }
        KernelService.open(temp.resolve("ledger-$name"), provider, testConfig, SyntheticFence(input),
            findingProviders = mapOf(rule to findingProviders), resultPolicy = policy).use { service ->
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

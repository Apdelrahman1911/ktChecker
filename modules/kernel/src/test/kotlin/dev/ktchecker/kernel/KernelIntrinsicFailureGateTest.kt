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
import dev.ktchecker.contracts.Verification
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Real broker/SQLite/source-archive controls with synthetic provider results, current-source
 * authority and model receipts. No compiler, build, target execution or real model is exercised.
 * A complete diagnostic analysis can report failed execution; its severity is not a passing check.
 */
class KernelIntrinsicFailureGateTest {
    @TempDir lateinit var temp: Path

    private val config = KernelConfig(workerCount = 1, globalParallelism = 1, perWorktreeParallelism = 1,
        heartbeatMillis = 30, leaseMillis = 120, pollMillis = 5, analysisTimeoutMillis = 15_000)

    @Test fun defaultSeverityThresholdStillBlocksEveryIntrinsicFailureKindAtInfoAndHint() {
        val kinds = listOf("build_failure", "EXECUTION_FAILURE", "compiler_failure", "test_failure", "worker_failure")
        for (severity in listOf("info", "hint")) for ((index, kind) in kinds.withIndex()) {
            withFixture("kind-$severity-$index") { fixture ->
                assertEquals(setOf("error", "warning"), config.blockingSeverities)
                val review = fixture.review("current") { input ->
                    result(input, listOf(finding(input, severity).copy(evidence = listOf(Evidence(kind, "Synthetic current failure")))))
                }
                val failure = review.raw.findings.single()
                assertEquals(severity, failure.severity)
                assertEquals(listOf(kind), failure.evidence.map { it.kind })
                assertTrue(failure.verification.isEmpty(), "No second failure signal may mask this control")
                assertEquals(PROVIDER, failure.providerId, "The provider name is not an execution failure heuristic")
                fixture.assertComplete(review)
                fixture.assertBlocked(listOf(failure))
                fixture.assertHistoryUnchanged(review)
            }
        }
    }

    @Test fun defaultSeverityThresholdStillBlocksFailedErrorAndCrashedVerificationAtInfoAndHint() {
        for (severity in listOf("info", "hint")) for (state in listOf("failed", "ERROR", "crashed")) {
            withFixture("verification-$severity-$state") { fixture ->
                val review = fixture.review("current") { input ->
                    result(input, listOf(finding(input, severity).copy(
                        verification = listOf(Verification("check", "Synthetic execution must succeed", state = state)))))
                }
                val failure = review.raw.findings.single()
                assertEquals(listOf("static_rule"), failure.evidence.map { it.kind })
                assertEquals(listOf(state), failure.verification.map { it.state })
                fixture.assertComplete(review)
                fixture.assertBlocked(listOf(failure))
                fixture.assertHistoryUnchanged(review)
            }
        }
    }

    @Test fun emptyBlockingSeveritiesCannotWaiveSevereExecutionDiagnosticsOrExecutionProviderErrors() {
        val diagnostics = listOf("compiler_diagnostic", "build_result", "test_result", "execution_result")
        val executionProviders = listOf("fixture-COMPILER", "fixture-native", "fixture-build", "fixture-runtime")
        for ((index, kind) in diagnostics.withIndex()) {
            withFixture("severe-diagnostic-$index", config.copy(blockingSeverities = emptySet())) { fixture ->
                val review = fixture.review("current") { input ->
                    result(input, listOf(finding(input, "error").copy(evidence = listOf(Evidence(kind, "Synthetic current error")))))
                }
                assertTrue(review.raw.findings.single().verification.isEmpty())
                assertEquals(PROVIDER, review.raw.findings.single().providerId)
                fixture.assertComplete(review)
                fixture.assertBlocked(review.raw.findings)
            }
        }
        for ((index, findingProvider) in executionProviders.withIndex()) {
            withFixture("severe-provider-$index", config.copy(blockingSeverities = emptySet()),
                findingProviders = setOf(findingProvider)) { fixture ->
                val review = fixture.review("current") { input ->
                    result(input, listOf(finding(input, "error").copy(providerId = findingProvider)))
                }
                assertTrue(review.raw.findings.single().verification.isEmpty())
                assertEquals(listOf("static_rule"), review.raw.findings.single().evidence.map { it.kind })
                fixture.assertComplete(review)
                fixture.assertBlocked(review.raw.findings)
            }
        }
    }

    @Test fun anErrorOnlyOwnerThresholdCannotTurnAWarningExecutionFailureIntoAPass() {
        for (signal in listOf("execution", "verification")) {
            withFixture("owner-threshold-$signal", config.copy(blockingSeverities = setOf("error"))) { fixture ->
                val review = fixture.review("current") { input ->
                    val warning = finding(input, "warning")
                    val failure = if (signal == "execution") warning.copy(evidence = listOf(Evidence("execution_failure", "Still failed")))
                        else warning.copy(verification = listOf(Verification("check", "Must succeed", state = "failed")))
                    result(input, listOf(failure))
                }
                fixture.assertComplete(review)
                fixture.assertBlocked(review.raw.findings)
            }
        }
    }

    @Test fun ordinaryAdvisoriesAndExplicitSeverityOptOutRemainNonblocking() {
        val controls = listOf(
            Triple("info", config, "static_rule"),
            Triple("hint", config, "static_rule"),
            Triple("warning", config.copy(blockingSeverities = setOf("error")), "compiler_diagnostic"),
            Triple("info", config, "execution_result"),
            Triple("warning", config.copy(blockingSeverities = emptySet()), "static_rule"),
            Triple("error", config.copy(blockingSeverities = emptySet()), "static_rule"),
        )
        for ((index, control) in controls.withIndex()) {
            val (severity, settings, kind) = control
            withFixture("ordinary-$index", settings) { fixture ->
                val review = fixture.review("current") { input ->
                    result(input, listOf(finding(input, severity).copy(evidence = listOf(Evidence(kind, "Synthetic non-failure")),
                        verification = listOf(Verification("check", "No failed verification", state = "passed")))))
                }
                fixture.assertComplete(review)
                fixture.assertAllowed()
                fixture.assertHistoryUnchanged(review)
            }
        }
    }

    @Test fun accountingAnOrdinaryWarningCannotHideAnOpenAdvisoryFailureInTheSameResult() {
        val policy = ResultPolicy { _, _, raw -> raw.copy(findings = raw.findings.map {
            if (it.evidence.single().kind == "static_rule") it.copy(disposition = "accepted") else it
        }) }
        withFixture("mixed-accounting", policy = policy) { fixture ->
            val review = fixture.review("current") { input ->
                result(input, listOf(finding(input, "warning"), finding(input, "info").copy(
                    fingerprint = Digests.sha256("separate current failure:${input.snapshot.snapshotId}"),
                    evidence = listOf(Evidence("execution_failure", "Separate current failed execution")))))
            }
            val effective = review.raw.copy(findings = review.raw.findings.mapIndexed { index, finding ->
                if (index == 0) finding.copy(disposition = "accepted") else finding
            })
            fixture.assertComplete(review, effective)
            assertEquals(listOf("accepted", "open"), review.status.result!!.findings.map { it.disposition })
            fixture.assertBlocked(listOf(review.raw.findings.last()))
            fixture.assertHistoryUnchanged(review)
        }
    }

    @Test fun policyStillCannotRelabelAnAdvisoryFailureAsAccepted() {
        val policy = ResultPolicy { _, _, raw -> raw.copy(findings = raw.findings.map { it.copy(disposition = "accepted") }) }
        withFixture("relabelled-advisory", policy = policy) { fixture ->
            val review = fixture.review("current") { input -> result(input, listOf(executionFailure(input, "hint"))) }
            assertTrue(review.raw.completeFor(review.input))
            assertTrue(review.retained.workIssues.isEmpty())
            assertEquals(listOf("result_policy_changed_protected_evidence"), review.retained.policyIssues)
            assertEquals("incomplete", review.status.state)
            assertEquals(review.retained.policyIssues, review.status.reasons)
            fixture.assertRawRetained(review)
            fixture.assertBlocked(review.raw.findings, exact = false)
        }
    }

    @Test fun aComparableCleanDescendantResolvesAbsenceWithoutRewritingHistoricalFailure() {
        for (severity in listOf("info", "hint")) withFixture("clean-descendant-$severity") { fixture ->
            val original = fixture.review("original") { input -> result(input, listOf(executionFailure(input, severity))) }
            fixture.assertComplete(original)
            fixture.assertBlocked(original.raw.findings)
            val repairedInput = fixture.newInput("repaired", original.input.snapshot.snapshotId, "val syntheticState = 2\n")
            val repaired = fixture.review("repaired", repairedInput) { result(it, emptyList()) }
            assertNotEquals(original.input.snapshot.files, repaired.input.snapshot.files)
            assertEquals(original.input.snapshot.snapshotId, repaired.input.snapshot.parentSnapshotId)
            assertEquals(original.input.context, repaired.input.context)
            assertEquals(original.input.requiredRuleIds, repaired.input.requiredRuleIds)
            assertTrue(repaired.input.context.fullProject)
            assertTrue(repaired.raw.findings.isEmpty())
            fixture.assertComplete(repaired)
            fixture.assertAllowed()
            fixture.assertHistoryUnchanged(original)
            fixture.assertHistoryUnchanged(repaired)
            assertEquals("open", fixture.service.status(fixture.client.client, original.status.reviewId).result!!.findings.single().disposition,
                "Comparable absence closes the lineage obligation; it does not rewrite the old diagnostic as fixed")
        }
    }

    @Test fun incompleteCancelledUncoveredOrIncomparableCleanReportsCannotResolveAdvisoryFailure() {
        val variants = listOf("foreign-ancestry", "policy", "context", "incomplete", "coverage", "partial-scope", "cancelled")
        for (variant in variants) withFixture("invalid-descendant-$variant") { fixture ->
            val original = fixture.review("original") { input -> result(input, listOf(executionFailure(input, "info"))) }
            fixture.assertComplete(original)
            fixture.assertBlocked(original.raw.findings)
            var currentInput = fixture.newInput("current", original.input.snapshot.snapshotId, "val syntheticState = 2\n")
            currentInput = when (variant) {
                "foreign-ancestry" -> currentInput.copy(snapshot = currentInput.snapshot.copy(parentSnapshotId = Digests.sha256("unrelated ancestor")))
                "policy" -> currentInput.copy(context = currentInput.context.copy(configurationDigest = Digests.sha256("another policy")))
                "context" -> currentInput.copy(context = currentInput.context.copy(scope = currentInput.context.scope.copy(compilation = "test")))
                "partial-scope" -> currentInput.copy(context = currentInput.context.copy(fullProject = false))
                else -> currentInput
            }
            val current = fixture.review("current", currentInput) { input ->
                val clean = result(input, emptyList())
                when (variant) {
                    "incomplete" -> clean.copy(status = "incomplete", reasons = listOf("Synthetic incomplete analysis"))
                    "coverage" -> clean.copy(coverage = emptyList())
                    else -> clean
                }
            }
            assertTrue(current.raw.findings.isEmpty(), "Absence alone must not discharge the retained failure")
            fixture.assertRawRetained(current)
            if (variant in setOf("incomplete", "coverage")) {
                assertEquals("incomplete", current.status.state)
                assertFalse(current.raw.completeFor(current.input))
                assertTrue("required_coverage_incomplete_or_wrong_context" in current.retained.workIssues)
            } else fixture.assertComplete(current)
            if (variant == "cancelled") {
                val cancelled = fixture.service.cancel(fixture.client.client, current.status.reviewId)
                assertTrue(cancelled.cancelled)
                assertEquals("cancelled", cancelled.state)
                assertEquals(current.raw, cancelled.result)
                assertEquals(listOf("cancelled"), fixture.observeNewEvents().map { it.kind })
            }
            val cut = fixture.assertBlocked(original.raw.findings, exact = variant in setOf("foreign-ancestry", "policy"))
            when (variant) {
                "cancelled" -> assertTrue("review_cancelled:${current.status.reviewId}" in cut.reasons)
                "incomplete", "coverage" -> assertTrue("review_incomplete:${current.status.reviewId}" in cut.reasons)
                "partial-scope" -> assertTrue("full_project_scope_unproven" in cut.reasons)
            }
            fixture.assertHistoryUnchanged(original)
        }
    }

    @Test fun aStillFailingDescendantCannotPassByChangingSeverityAndItsDisplayFingerprint() {
        for (severity in listOf("info", "hint")) for (keepFingerprint in listOf(false, true)) {
            withFixture("still-failing-$severity-$keepFingerprint") { fixture ->
                val original = fixture.review("original") { input -> result(input, listOf(executionFailure(input, "error"))) }
                fixture.assertComplete(original)
                fixture.assertBlocked(original.raw.findings)
                // Source bytes, failure evidence and verification do not improve. A new capture
                // and severity-dependent provider fingerprint cannot stand in for absence proof.
                val currentInput = fixture.newInput("current", original.input.snapshot.snapshotId)
                val current = fixture.review("current", currentInput) { input ->
                    val failure = executionFailure(input, severity)
                    result(input, listOf(if (keepFingerprint) failure.copy(fingerprint = original.raw.findings.single().fingerprint) else failure))
                }
                assertEquals(original.input.snapshot.files, current.input.snapshot.files)
                assertEquals(original.raw.findings.single().evidence, current.raw.findings.single().evidence)
                assertEquals(original.raw.findings.single().verification, current.raw.findings.single().verification)
                assertEquals(keepFingerprint, original.raw.findings.single().fingerprint == current.raw.findings.single().fingerprint)
                fixture.assertComplete(current)
                fixture.assertBlocked(current.raw.findings)
                fixture.assertHistoryUnchanged(original)
                fixture.assertHistoryUnchanged(current)
            }
        }
    }

    private fun withFixture(name: String, settings: KernelConfig = config, findingProviders: Set<String> = setOf(PROVIDER),
                            policy: ResultPolicy = ResultPolicy.NONE, check: (Fixture) -> Unit) {
        Fixture(name, settings, findingProviders, policy).use(check)
    }

    private inner class Fixture(private val name: String, settings: KernelConfig, findingProviders: Set<String>, policy: ResultPolicy) : AutoCloseable {
        private val planned = ConcurrentHashMap<String, (AnalysisInput) -> ProviderResult>()
        private val produced = ConcurrentHashMap<String, ProviderResult>()
        private val calls = AtomicInteger()
        private val fence = SyntheticFence()
        private val provider = object : AnalysisProvider {
            override val id = PROVIDER
            override val version = VERSION
            override val supportedRuleIds = setOf(RULE)
            override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
                cancellation.throwIfCancelled()
                calls.incrementAndGet()
                return planned.getValue(input.snapshot.snapshotId)(input).also { produced[input.snapshot.snapshotId] = it }
            }
        }
        val service = KernelService.open(temp.resolve("ledger-$name"), provider, settings, fence,
            findingProviders = mapOf(RULE to findingProviders), resultPolicy = policy)
        val client = service.registerClient(ClientBinding("owner", "workspace", "tree", name, "lineage-$name", sessionId = "session-$name"))
        private val events = mutableListOf<ReviewEvent>()
        private var cursor: String? = null

        fun newInput(stage: String, parent: String? = null, text: String = "val syntheticState = 1\n"): AnalysisInput {
            val root = temp.resolve("source-$name-$stage")
            Files.createDirectory(root)
            val bytes = text.toByteArray(Charsets.UTF_8)
            Files.write(root.resolve("Main.kt"), bytes)
            val files = listOf(SnapshotFile("Main.kt", Digests.sha256(bytes), bytes.size.toLong()))
            val snapshot = SnapshotManifest(Digests.sha256("synthetic-intrinsic-failure:$name:$stage:${canonical(files)}:$parent"),
                "workspace", "tree", files, ownerId = "owner", parentSnapshotId = parent, declaredRoots = listOf("."))
            return AnalysisInput(snapshot, root.toString(), AnalysisContext(scope = RuleContext(module = ":app"),
                modelDigest = Digests.sha256("synthetic-model"), dependencyDigest = Digests.sha256("synthetic-dependencies"),
                toolchainDigest = Digests.sha256("synthetic-toolchain"), configurationDigest = Digests.sha256("synthetic-policy"),
                fullProject = true), requiredRuleIds = listOf(RULE))
        }

        fun review(stage: String, input: AnalysisInput = newInput(stage), produce: (AnalysisInput) -> ProviderResult): RetainedReview {
            assertEquals(null, planned.putIfAbsent(input.snapshot.snapshotId, produce))
            fence.update(input)
            val before = calls.get()
            val submitted = service.submit(client.client, SubmitRequest("patch-$stage", "key-$stage", input))
            val started = System.nanoTime()
            var status = service.status(client.client, submitted.reviewId)
            while (status.result == null && System.nanoTime() - started < TimeUnit.SECONDS.toNanos(15)) {
                Thread.sleep(5)
                status = service.status(client.client, submitted.reviewId)
            }
            assertNotNull(status.result, "Bounded synthetic provider wait expired")
            assertEquals(before + 1, calls.get(), "Each synthetic current result must actually be analyzed")
            val raw = assertNotNull(produced[input.snapshot.snapshotId])
            val delivered = observeNewEvents()
            assertEquals(listOf("accepted", "result"), delivered.map { it.kind })
            assertTrue(delivered.all { it.reviewId == status.reviewId && it.sourceSnapshotId == input.snapshot.snapshotId })
            val eventResult = ContractJson.decodeFromString<ReviewStatus>(delivered.last().payload)
            assertEquals(status, eventResult, "The consumer must receive exactly the retained current policy projection")
            return RetainedReview(input, status, raw, retained(status.reviewId), delivered)
        }

        fun observeNewEvents(): List<ReviewEvent> {
            val page = service.events(client.client, cursor, 16)
            assertFalse(page.hasMore, "This finite synthetic lineage fits in one page")
            for (event in page.events) {
                assertEquals(client.binding.clientId, event.clientId)
                assertTrue(event.deliveryRequired)
                assertEquals(Digests.sha256(event.payload), event.payloadDigest)
                val context = Digests.sha256("synthetic-context:${event.eventId}:${event.payload}")
                service.acknowledge(client.adapter, listOf(event.eventId), ContextReceipt(
                    "ack-${event.eventId}", context, "item-${event.eventId}", Instant.EPOCH.toString()))
                service.observe(client.adapter, listOf(event.eventId), ObservationReceipt(
                    "observation-${event.eventId}", "synthetic-model-request-${event.eventId}", listOf(context)))
            }
            cursor = page.cursor
            events += page.events
            return page.events
        }

        fun assertRawRetained(review: RetainedReview, expected: ProviderResult = review.raw) {
            assertEquals(review.raw, expected.copy(findings = review.raw.findings), "Policy may change no non-disposition result field")
            assertEquals(expected, review.status.result)
            assertEquals(canonical(review.input), review.retained.inputJson)
            assertEquals(canonical(review.raw), review.retained.rawJson)
            assertEquals(canonical(expected), review.retained.effectiveJson)
            val certificate = assertNotNull(PolicyCertificate.decode(review.retained.policyCertificate))
            assertEquals(PolicyCertificate.EVALUATED, certificate.state)
            assertEquals(service.policyEvaluatorIdentity, certificate.evaluatorIdentity)
        }

        fun assertComplete(review: RetainedReview, expected: ProviderResult = review.raw) {
            assertTrue(review.raw.completeFor(review.input))
            assertTrue(review.raw.reasons.isEmpty())
            assertTrue(review.raw.rawDiagnostics.isEmpty())
            assertTrue(review.raw.findings.all { it.disposition == "open" })
            assertTrue(review.retained.workIssues.isEmpty(), review.retained.workIssues.toString())
            assertTrue(review.retained.policyIssues.isEmpty(), review.retained.policyIssues.toString())
            assertEquals("complete", review.status.state)
            assertFalse(review.status.cancelled)
            assertTrue(review.status.reasons.isEmpty(), review.status.reasons.toString())
            assertRawRetained(review, expected)
        }

        fun assertBlocked(failures: List<Finding>, exact: Boolean = true): GateCut {
            val cut = service.prepareGate(client.client)
            assertEquals(events.map { it.eventId }, cut.requiredEventIds)
            assertFalse(cut.ready, cut.reasons.toString())
            val expected = failures.map { "open_finding:${it.fingerprint}" }.distinct().sorted()
            if (exact) assertEquals(expected, cut.reasons, "Only the retained failure may block this otherwise complete lineage")
            else assertTrue(cut.reasons.containsAll(expected), cut.reasons.toString())
            assertTrue(cut.reasons.none { it.startsWith("insertion_missing:") || it.startsWith("model_observation_missing:") })
            assertFalse("current_source_configuration_authority_unavailable" in cut.reasons)
            val entries = AtomicInteger()
            val completion = service.completeGate(client.client, cut.cutId) {
                entries.incrementAndGet() // Count BEFORE throwing: catch-to-refusal must not hide accidental execution.
                throw IllegalStateException("A retained synthetic failure must prevent callback entry")
            }
            assertEquals(0, entries.get(), "The final action must not start, even if its exception would be caught")
            assertFalse(completion.allowed)
            assertFalse(completion.actionPerformed)
            assertEquals(cut.reasons, completion.reasons)
            return cut
        }

        fun assertAllowed() {
            val cut = service.prepareGate(client.client)
            assertEquals(events.map { it.eventId }, cut.requiredEventIds)
            assertTrue(cut.ready, cut.reasons.toString())
            assertTrue(cut.reasons.isEmpty())
            val entries = AtomicInteger()
            val completion = service.completeGate(client.client, cut.cutId) { entries.incrementAndGet() }
            assertTrue(completion.allowed, completion.reasons.toString())
            assertTrue(completion.actionPerformed)
            assertEquals(1, entries.get(), "The same delivery/source harness must permit an ordinary valid final action")
        }

        fun assertHistoryUnchanged(review: RetainedReview) {
            assertEquals(review.retained, retained(review.status.reviewId))
            assertEquals(review.status, service.status(client.client, review.status.reviewId))
            val page = service.events(client.client, limit = 16)
            assertFalse(page.hasMore)
            assertEquals(review.events, page.events.filter { it.reviewId == review.status.reviewId })
        }

        private fun retained(reviewId: String): Retained = assertNotNull(service.ledger.read { connection ->
            connection.one("""SELECT r.input_json,w.result_json,r.effective_result_json,w.validation_json,r.policy_validation_json,r.policy_certificate
                FROM reviews r JOIN work w ON w.work_key=r.work_key WHERE r.review_id=? AND r.client_id=?""", reviewId, client.binding.clientId) { row ->
                Retained(row.getString("input_json"), row.getString("result_json"), row.getString("effective_result_json"),
                    ContractJson.decodeFromString<List<String>>(row.getString("validation_json")),
                    ContractJson.decodeFromString<List<String>>(row.getString("policy_validation_json")), row.getString("policy_certificate"))
            }
        })

        override fun close() = service.close()
    }

    private data class Retained(val inputJson: String, val rawJson: String, val effectiveJson: String,
                                val workIssues: List<String>, val policyIssues: List<String>, val policyCertificate: String)
    private data class RetainedReview(val input: AnalysisInput, val status: ReviewStatus, val raw: ProviderResult,
                                      val retained: Retained, val events: List<ReviewEvent>)

    /** Synthetic exact-current authority, held through each gate callback; not a live-source claim. */
    private class SyntheticFence : SnapshotGate {
        private val lock = Any()
        private var current: AnalysisInput? = null
        private var epoch = 0L
        fun update(input: AnalysisInput) = synchronized(lock) { current = input; epoch++ }
        override fun <T> withCurrentSnapshot(input: AnalysisInput, action: (SnapshotCheck) -> T): T = synchronized(lock) {
            val expected = checkNotNull(current)
            action(SnapshotCheck(current = input.snapshot == expected.snapshot && input.context == expected.context,
                snapshotId = expected.snapshot.snapshotId, sourceEpoch = epoch,
                configurationDigest = expected.context.configurationDigest, modelDigest = expected.context.modelDigest,
                dependencyDigest = expected.context.dependencyDigest, toolchainDigest = expected.context.toolchainDigest,
                analysisEpoch = "synthetic-intrinsic-failure-epoch:$epoch", authoritative = true))
        }
    }

    private fun finding(input: AnalysisInput, severity: String): Finding = Finding(
        RULE, "Synthetic diagnostic", "Synthetic predicate", "Regression fixture only",
        SourceLocation("Main.kt", input.snapshot.files.single().digest, 1, 0, 1, 3, "val"),
        Digests.sha256("synthetic-current-finding:${input.snapshot.snapshotId}"), PROVIDER,
        context = input.context.scope, severity = severity,
        evidence = listOf(Evidence("static_rule", "Synthetic ordinary evidence", digest = Digests.sha256("synthetic-evidence"))),
    )

    private fun executionFailure(input: AnalysisInput, severity: String): Finding = finding(input, severity)
        .copy(evidence = listOf(Evidence("execution_failure", "Synthetic current failed execution")))

    private fun result(input: AnalysisInput, findings: List<Finding>): ProviderResult = ProviderResult(
        PROVIDER, "complete", findings,
        coverage = listOf(Coverage(RULE, input.context.scope, if (findings.isEmpty()) "passed" else "findings",
            provenance = "executed", certificateId = Digests.sha256("synthetic-coverage:${input.analysisKey()}"),
            // Structural synthetic fixtures only. These flags are not real provider qualification.
            implemented = true, tested = true, qualified = true)),
        metrics = AnalysisMetrics(elapsedMillis = 11, cpuMillis = 7, peakRssBytes = 8_192, outputBytes = 123),
        providerVersion = VERSION,
    )

    private companion object {
        const val RULE = "test/intrinsic-failure-severity-gate"
        const val PROVIDER = "synthetic-severity-oracle"
        const val VERSION = "synthetic-severity-oracle-1"
    }
}

package dev.ktchecker.audit

import dev.ktchecker.config.*
import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.*
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

private const val COMPOSITION_WARNING = "audit/synthetic-composition-warning"
private const val COMPOSITION_COMPILER_FAILURE = "audit/synthetic-composition-compiler-failure"
private const val COMPOSITION_BUILD_FAILURE = "audit/synthetic-composition-build-failure"
private const val COMPOSITION_PROVIDER = "audit/synthetic-composition-provider"
private const val COMPOSITION_VERSION = "synthetic-plumbing-only-1"
private const val COMPOSITION_EVALUATOR = "audit/synthetic-owner-config-adapter/1"

/**
 * Actual ConfigStore HMAC/CAS/files/fences and actual SQLite kernel workers/outbox/recovery.
 * Providers, their qualification cells, source/model authority, owner archive adapter and model
 * observation receipts are explicitly SYNTHETIC integration fixtures, not shipped CLI coverage.
 * No compiler, build, project code, native tool, real model request or host attestation runs here.
 * These controls compose existing subsystems; they do not re-test the matching/authority matrix.
 */
class ConfigKernelActiveReviewAuditTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun realLocalMaskDuringSharedWorkCannotRewriteCapturedResultsOrDischargeAnotherClient() {
        requireCompositionDirectorySync(temporary)
        val fixture = CompositionFixture(temporary)
        val oldLeft = fixture.mutate(fixture.configs.capture(fixture.left), ConfigLayer.SHARED,
            ConfigAction.AddSuppression(compositionSuppression("shared-warning", COMPOSITION_WARNING)))
        val oldRight = fixture.configs.capture(fixture.right)
        val leftInput = fixture.input(oldLeft)
        val rightInput = fixture.input(oldRight)
        val cancellingBinding = fixture.client("left-cancelling", fixture.left)
        val survivingBinding = fixture.client("left-surviving", fixture.left)
        val independentBinding = fixture.client("right-independent", fixture.right)
        val policy = SyntheticCapturedConfigAdapter(fixture.configs, fixture.captures)
        fixture.enroll(policy, cancellingBinding, leftInput, oldLeft)
        fixture.enroll(policy, survivingBinding, leftInput, oldLeft)
        fixture.enroll(policy, independentBinding, rightInput, oldRight)
        // Same lineage/input may share raw work. The distinct real policy lineage does NOT:
        // ConfigStore includes the full binding in its policy digest and hence in analysisKey.
        assertNotEquals(leftInput.analysisKey(), rightInput.analysisKey())
        val provider = SyntheticCompositionProvider(expectedStarts = 2, progressive = true)
        KernelService.open(fixture.ledger, provider, compositionKernelConfig(), fixture.fence(),
            resultPolicy = policy.policy).use { service ->
            try {
                val cancelling = service.registerClient(cancellingBinding)
                val surviving = service.registerClient(survivingBinding)
                val independent = service.registerClient(independentBinding)
                val cancelledReview = service.submit(cancelling.client, compositionRequest("old-cancel", leftInput))
                assertTrue(provider.firstPublished.await(10, TimeUnit.SECONDS), "First synthetic raw worker did not enter")
                val survivingReview = service.submit(surviving.client, compositionRequest("old-survive", leftInput))
                val independentReview = service.submit(independent.client, compositionRequest("old-independent", rightInput))
                assertTrue(provider.allPublished.await(10, TimeUnit.SECONDS), "Both synthetic raw workers must be active")
                assertEquals(2, provider.calls.get())
                assertEquals(2, provider.active.get())
                assertEquals("running", service.status(surviving.client, survivingReview.reviewId).state)
                assertEquals("running", service.status(independent.client, independentReview.reviewId).state)
                assertEquals(compositionStored(fixture.ledger, cancelledReview.reviewId).workKey,
                    compositionStored(fixture.ledger, survivingReview.reviewId).workKey)
                assertNotEquals(compositionStored(fixture.ledger, survivingReview.reviewId).workKey,
                    compositionStored(fixture.ledger, independentReview.reviewId).workKey)
                assertEquals("cancelled", service.cancel(cancelling.client, cancelledReview.reviewId).state)
                val oldSurvivingEvents = compositionEvents(service, surviving)
                val oldIndependentEvents = compositionEvents(service, independent)
                for (history in listOf(oldSurvivingEvents, oldIndependentEvents)) {
                    assertEquals(listOf("accepted", "progress"), history.map { it.kind })
                    assertEquals("accepted", compositionPayload(history.last()).result!!.findings.single().disposition)
                }

                // A second actual store instance changes only this lineage's local layer while
                // the two raw providers are blocked. No publication callback re-captures policy.
                val nowLeft = fixture.mutate(oldLeft, ConfigLayer.LOCAL,
                    ConfigAction.MaskSharedSuppression("shared-warning"), fixture.anotherStore())
                assertNotEquals(oldLeft.revisionToken, nowLeft.revisionToken)
                assertEquals(oldRight.revisionToken, fixture.configs.capture(fixture.right).revisionToken)
                assertEquals(2, provider.active.get(), "The edit must occur DURING both raw reviews")
                assertNull(compositionStored(fixture.ledger, survivingReview.reviewId).raw)
                assertNull(compositionStored(fixture.ledger, independentReview.reviewId).raw)
                assertEquals(oldSurvivingEvents, compositionEvents(service, surviving))
                assertEquals(oldIndependentEvents, compositionEvents(service, independent))

                provider.release.countDown()
                val survivor = compositionAwaitEvaluated(service, fixture.ledger, surviving, survivingReview.reviewId)
                val peer = compositionAwaitEvaluated(service, fixture.ledger, independent, independentReview.reviewId)
                val cancelled = compositionAwaitEvaluated(service, fixture.ledger, cancelling, cancelledReview.reviewId)
                assertEquals("complete", survivor.state, survivor.reasons.toString())
                assertEquals("complete", peer.state, peer.reasons.toString())
                assertEquals("cancelled", cancelled.state)
                assertTrue(cancelled.cancelled)
                assertFalse(provider.sawCancellation.get(), "One consumer's cancellation cannot stop its surviving subscriber")
                assertEquals(2, provider.calls.get())
                assertEquals("accepted", survivor.result!!.findings.single().disposition)
                assertEquals("accepted", peer.result!!.findings.single().disposition)
                val survivorStored = compositionStored(fixture.ledger, survivor.reviewId)
                val peerStored = compositionStored(fixture.ledger, peer.reviewId)
                assertEquals(survivorStored.raw, compositionStored(fixture.ledger, cancelled.reviewId).raw)
                for (stored in listOf(survivorStored, peerStored)) {
                    assertEquals("open", compositionRaw(stored).findings.single().disposition)
                }
                assertEquals(oldSurvivingEvents, compositionEvents(service, surviving).take(oldSurvivingEvents.size))
                assertEquals(oldIndependentEvents, compositionEvents(service, independent).take(oldIndependentEvents.size))

                val retainedSurvivorEvents = compositionObserveAll(service, surviving)
                assertEquals(retainedSurvivorEvents, compositionEvents(service, surviving), "Receipts do not create more delivery obligations")
                val undeliveredPeer = service.prepareGate(independent.client)
                assertTrue(undeliveredPeer.reasons.any { it.startsWith("model_observation_missing:") },
                    "The surviving client's receipts cannot deliver its independent peer's old-policy result")
                compositionObserveAll(service, independent)
                val peerCut = service.prepareGate(independent.client)
                assertTrue(peerCut.ready, peerCut.reasons.toString())
                val peerEffects = AtomicInteger()
                val peerGate = service.completeGate(independent.client, peerCut.cutId) { peerEffects.incrementAndGet() }
                assertTrue(peerGate.allowed, peerGate.reasons.toString())
                assertTrue(peerGate.actionPerformed)
                assertEquals(1, peerEffects.get(), "An unrelated cancelled/stale lineage cannot block this fully delivered peer")

                val staleCut = service.prepareGate(surviving.client)
                assertFalse(staleCut.ready)
                assertTrue("local_configuration_changed" in staleCut.reasons, staleCut.reasons.toString())
                assertTrue("configuration_authority_mismatch_or_unknown" in staleCut.reasons)
                assertTrue(staleCut.reasons.none(::compositionDeliveryReason))
                val staleEffects = AtomicInteger()
                assertFalse(service.completeGate(surviving.client, staleCut.cutId) { staleEffects.incrementAndGet() }.allowed)
                assertEquals(0, staleEffects.get())

                val freshInput = fixture.input(nowLeft)
                fixture.enroll(policy, survivingBinding, freshInput, nowLeft)
                val fresh = compositionAwaitEvaluated(service, fixture.ledger, surviving,
                    service.submit(surviving.client, compositionRequest("after-local-mask", freshInput)).reviewId)
                assertEquals("complete", fresh.state, fresh.reasons.toString())
                assertEquals("open", fresh.result!!.findings.single().disposition)
                assertEquals(3, provider.calls.get(), "Only the same-lineage initial pair shared one of the three raw computations")
                assertEquals(survivor, service.status(surviving.client, survivor.reviewId))
                assertEquals(survivorStored, compositionStored(fixture.ledger, survivor.reviewId))
                assertEquals(retainedSurvivorEvents, compositionEvents(service, surviving).filter { it.reviewId == survivor.reviewId })
                compositionObserveAll(service, surviving)
                val currentButOpen = service.prepareGate(surviving.client)
                assertFalse(currentButOpen.ready)
                assertTrue(currentButOpen.reasons.any { it.startsWith("open_finding:") })
                assertFalse("current_source_configuration_authority_unavailable" in currentButOpen.reasons)
                assertFalse("configuration_authority_mismatch_or_unknown" in currentButOpen.reasons)
                compositionObserveAll(service, cancelling)
                assertTrue("review_cancelled:${cancelled.reviewId}" in service.prepareGate(cancelling.client).reasons)
            } finally { provider.release.countDown() }
        }
    }

    @Test
    fun workerlessRecoveryUsesEachExactRealPolicyArchiveAndMissingPeerNeverAdoptsNewerConfiguration() {
        requireCompositionDirectorySync(temporary)
        val fixture = CompositionFixture(temporary)
        val oldLeft = fixture.mutate(fixture.configs.capture(fixture.left), ConfigLayer.SHARED,
            ConfigAction.AddSuppression(compositionSuppression("shared-warning", COMPOSITION_WARNING)))
        val oldRight = fixture.mutate(fixture.configs.capture(fixture.right), ConfigLayer.LOCAL,
            ConfigAction.AddSuppression(compositionSuppression("shared-warning", COMPOSITION_WARNING,
                SuppressionReason.REPORTED_FALSE_POSITIVE)))
        val leftInput = fixture.input(oldLeft)
        val rightInput = fixture.input(oldRight)
        val leftBinding = fixture.client("recover-left", fixture.left)
        val rightBinding = fixture.client("recover-right", fixture.right)
        val waitingPolicy = SyntheticCapturedConfigAdapter(fixture.configs, fixture.captures, restorationAllowed = false)
        val leftCapture = fixture.enroll(waitingPolicy, leftBinding, leftInput, oldLeft, ready = false)
        fixture.enroll(waitingPolicy, rightBinding, rightInput, oldRight, ready = false)
        val provider = SyntheticCompositionProvider(expectedStarts = 2)
        val limits = compositionKernelConfig().copy(maxPendingPerClient = 1)
        lateinit var left: ClientRegistration
        lateinit var right: ClientRegistration
        lateinit var leftReview: ReviewStatus
        lateinit var rightReview: ReviewStatus
        lateinit var leftRaw: CompositionStored
        lateinit var rightRaw: CompositionStored
        lateinit var leftHistory: List<ReviewEvent>
        lateinit var rightHistory: List<ReviewEvent>
        KernelService.open(fixture.ledger, provider, limits, fixture.fence(), resultPolicy = waitingPolicy.policy).use { service ->
            try {
                left = service.registerClient(leftBinding)
                right = service.registerClient(rightBinding)
                leftReview = service.submit(left.client, compositionRequest("recover-old-left", leftInput))
                rightReview = service.submit(right.client, compositionRequest("recover-old-right", rightInput))
                assertTrue(provider.allPublished.await(10, TimeUnit.SECONDS))
                val writer = fixture.anotherStore()
                fixture.mutate(oldLeft, ConfigLayer.SHARED, ConfigAction.RemoveSuppression("shared-warning"), writer)
                fixture.mutate(writer.capture(fixture.right), ConfigLayer.LOCAL,
                    ConfigAction.RemoveSuppression("shared-warning"), writer)
                assertEquals(2, provider.active.get(), "Both owner edits must occur DURING active raw reviews")
                assertNull(compositionStored(fixture.ledger, leftReview.reviewId).raw)
                assertNull(compositionStored(fixture.ledger, rightReview.reviewId).raw)
                provider.release.countDown()
                compositionAwait("Both completed raw results must remain policy-pending") {
                    listOf(leftReview, rightReview).all { compositionStored(fixture.ledger, it.reviewId).raw != null }
                }
                leftRaw = compositionStored(fixture.ledger, leftReview.reviewId)
                rightRaw = compositionStored(fixture.ledger, rightReview.reviewId)
                for ((client, review) in listOf(left to leftReview, right to rightReview)) {
                    assertNull(compositionStored(fixture.ledger, review.reviewId).effective)
                    val pending = service.status(client.client, review.reviewId)
                    assertTrue("policy_evaluation_missing" in pending.reasons)
                    assertTrue("captured_policy_not_ready" in pending.reasons)
                    assertEquals("open", pending.result!!.findings.single().disposition)
                }
                assertEquals(0, waitingPolicy.evaluations.get())
                leftHistory = compositionEvents(service, left)
                rightHistory = compositionEvents(service, right)
            } finally { provider.release.countDown() }
        }

        // Withhold only this test owner's FrozenConfig archive, never a kernel/source archive.
        // The retained expected token/hash/input record does not contain a ConfigSnapshot fallback.
        val withheld = temporary.resolve("withheld-test-owner-left-policy.json")
        Files.move(leftCapture.path, withheld)
        val restoredStore = fixture.anotherStore()
        val latestLeft = restoredStore.capture(fixture.left)
        val latestRight = restoredStore.capture(fixture.right)
        assertNotEquals(oldLeft.revisionToken, latestLeft.revisionToken)
        assertNotEquals(oldRight.revisionToken, latestRight.revisionToken)
        assertEquals("open", restoredStore.apply(latestLeft, fixture.input(latestLeft), compositionRaw(leftRaw)).findings.single().disposition)
        assertEquals("open", restoredStore.apply(latestRight, fixture.input(latestRight), compositionRaw(rightRaw)).findings.single().disposition)
        val restoredPolicy = SyntheticCapturedConfigAdapter(restoredStore, fixture.captures)
        assertNull(restoredPolicy.loadedToken(leftBinding, leftInput))
        assertNull(restoredPolicy.loadedToken(rightBinding, rightInput))
        KernelService.open(fixture.ledger, provider, limits.copy(workerCount = 0), fixture.fence(restoredStore),
            preparedInputObserver = PreparedInputObserver { _, _, _ -> error("Synthetic policy-only recovery must never prepare raw execution") },
            resultPolicy = restoredPolicy.policy).use { service ->
            val restoredRight = compositionAwaitEvaluated(service, fixture.ledger, right, rightReview.reviewId)
            compositionAwait("Missing exact owner archive must be auditable and retryable") {
                "captured_policy_restore_unavailable" in service.status(left.client, leftReview.reviewId).reasons
            }
            assertEquals("complete", restoredRight.state, restoredRight.reasons.toString())
            assertEquals("suppressed", restoredRight.result!!.findings.single().disposition)
            assertEquals(oldRight.revisionToken, restoredPolicy.loadedToken(rightBinding, rightInput))
            assertNull(restoredPolicy.loadedToken(leftBinding, leftInput))
            assertNull(compositionStored(fixture.ledger, leftReview.reviewId).effective)
            assertTrue("policy_evaluation_missing" in service.status(left.client, leftReview.reviewId).reasons)
            assertEquals(leftRaw.raw, compositionStored(fixture.ledger, leftReview.reviewId).raw)
            assertEquals(rightRaw.raw, compositionStored(fixture.ledger, rightReview.reviewId).raw)
            assertEquals(2, provider.calls.get(), "Workerless recovery may restore policy, never rerun the raw providers")
            assertEquals(1, restoredPolicy.evaluations.get(), "The missing left capture cannot prevent its independent ready peer's evaluation")
            assertEquals("client_queue_full", assertFailsWith<KernelException> {
                service.submit(left.client, compositionRequest("cannot-replace-missing-policy", fixture.input(latestLeft)))
            }.code)
            assertEquals(leftHistory, compositionEvents(service, left).take(leftHistory.size))
            assertEquals(rightHistory, compositionEvents(service, right).take(rightHistory.size))
            val rightRetained = compositionStored(fixture.ledger, rightReview.reviewId)
            val rightDelivered = compositionObserveAll(service, right)
            val rightCut = service.prepareGate(right.client)
            assertFalse(rightCut.ready)
            assertTrue("shared_configuration_changed" in rightCut.reasons)
            assertTrue(rightCut.reasons.none(::compositionDeliveryReason))
            assertTrue(rightCut.reasons.none { it.endsWith(":${leftReview.reviewId}") }, "No cross-client missing-policy obligation")

            assertEquals(leftCapture.archiveDigest, Digests.sha256(Files.readAllBytes(withheld)))
            Files.move(withheld, leftCapture.path)
            val restoredLeft = compositionAwaitEvaluated(service, fixture.ledger, left, leftReview.reviewId)
            assertEquals("complete", restoredLeft.state, restoredLeft.reasons.toString())
            assertEquals("accepted", restoredLeft.result!!.findings.single().disposition)
            assertEquals(oldLeft.revisionToken, restoredPolicy.loadedToken(leftBinding, leftInput))
            assertEquals(2, restoredPolicy.evaluations.get())
            assertEquals(2, provider.calls.get())
            assertEquals(leftRaw.raw, compositionStored(fixture.ledger, leftReview.reviewId).raw)
            assertEquals(rightRetained, compositionStored(fixture.ledger, rightReview.reviewId))
            assertEquals(rightDelivered, compositionEvents(service, right))
            assertEquals(leftHistory, compositionEvents(service, left).take(leftHistory.size))
            assertEquals("open", compositionPayload(leftHistory.last()).result!!.findings.single().disposition,
                "The original unclassified event remains immutable, not retroactively accepted")
            compositionObserveAll(service, left)
            val leftCut = service.prepareGate(left.client)
            assertFalse(leftCut.ready)
            assertTrue("shared_configuration_changed" in leftCut.reasons)
            assertTrue(leftCut.reasons.none(::compositionDeliveryReason))
            val effects = AtomicInteger()
            for ((client, cut) in listOf(left to leftCut, right to rightCut)) {
                val gate = service.completeGate(client.client, cut.cutId) { effects.incrementAndGet() }
                assertFalse(gate.allowed)
                assertFalse(gate.actionPerformed)
            }
            assertEquals(0, effects.get(), "Exact old policy restoration is not current configuration authority")
        }
    }

    @Test
    fun ownerSuppressionsAddedDuringConcurrentCompilerAndBuildFailuresPreserveUnavailableEvidenceAndHistory() {
        requireCompositionDirectorySync(temporary)
        val fixture = CompositionFixture(temporary)
        val oldCompilerPolicy = fixture.configs.capture(fixture.left)
        val oldBuildPolicy = fixture.configs.capture(fixture.right)
        val compilerInput = fixture.input(oldCompilerPolicy, listOf(COMPOSITION_WARNING, COMPOSITION_COMPILER_FAILURE))
        val buildInput = fixture.input(oldBuildPolicy, listOf(COMPOSITION_WARNING, COMPOSITION_BUILD_FAILURE))
        val compilerBinding = fixture.client("compiler-consumer", fixture.left)
        val buildBinding = fixture.client("build-consumer", fixture.right)
        val policy = SyntheticCapturedConfigAdapter(fixture.configs, fixture.captures)
        fixture.enroll(policy, compilerBinding, compilerInput, oldCompilerPolicy)
        fixture.enroll(policy, buildBinding, buildInput, oldBuildPolicy)
        val provider = SyntheticCompositionProvider(expectedStarts = 2, failureResults = true)
        KernelService.open(fixture.ledger, provider, compositionKernelConfig(), fixture.fence(), resultPolicy = policy.policy).use { service ->
            try {
                val compiler = service.registerClient(compilerBinding)
                val build = service.registerClient(buildBinding)
                val oldCompilerReview = service.submit(compiler.client, compositionRequest("compiler-old", compilerInput))
                val oldBuildReview = service.submit(build.client, compositionRequest("build-old", buildInput))
                assertTrue(provider.allPublished.await(10, TimeUnit.SECONDS))
                // All three rules deliberately allow broad owner suppression in this synthetic
                // registry. Intrinsic failure evidence, not a request denial, must protect failures.
                val newCompilerPolicy = fixture.mutate(oldCompilerPolicy, ConfigLayer.SHARED,
                    ConfigAction.ImportSharedConfig(SharedProjectConfig(suppressions = listOf(
                        compositionSuppression("waive-warning", COMPOSITION_WARNING),
                        compositionSuppression("try-waive-compiler", COMPOSITION_COMPILER_FAILURE),
                        compositionSuppression("try-waive-build", COMPOSITION_BUILD_FAILURE),
                    ))), fixture.anotherStore())
                val newBuildPolicy = fixture.configs.capture(fixture.right)
                assertEquals(3, newCompilerPolicy.shared.suppressions.size)
                assertNotEquals(oldCompilerPolicy.revisionToken, newCompilerPolicy.revisionToken)
                assertNotEquals(oldBuildPolicy.revisionToken, newBuildPolicy.revisionToken)
                assertEquals(2, provider.active.get(), "Suppressions must be committed DURING both synthetic failures")
                assertNull(compositionStored(fixture.ledger, oldCompilerReview.reviewId).raw)
                assertNull(compositionStored(fixture.ledger, oldBuildReview.reviewId).raw)
                provider.release.countDown()
                val oldCompiler = compositionAwaitEvaluated(service, fixture.ledger, compiler, oldCompilerReview.reviewId)
                val oldBuild = compositionAwaitEvaluated(service, fixture.ledger, build, oldBuildReview.reviewId)
                for ((status, expectedStatus) in listOf(oldCompiler to "failed", oldBuild to "unavailable")) {
                    assertEquals("incomplete", status.state)
                    assertEquals(expectedStatus, status.result!!.status)
                    assertTrue(status.result!!.findings.all { it.disposition == "open" })
                    assertEquals(compositionRaw(compositionStored(fixture.ledger, status.reviewId)), status.result)
                }
                val oldCompilerStored = compositionStored(fixture.ledger, oldCompiler.reviewId)
                val oldBuildStored = compositionStored(fixture.ledger, oldBuild.reviewId)
                val oldCompilerEvents = compositionEvents(service, compiler)
                val oldBuildEvents = compositionEvents(service, build)

                val freshCompilerInput = fixture.input(newCompilerPolicy, compilerInput.requiredRuleIds)
                val freshBuildInput = fixture.input(newBuildPolicy, buildInput.requiredRuleIds)
                fixture.enroll(policy, compilerBinding, freshCompilerInput, newCompilerPolicy)
                fixture.enroll(policy, buildBinding, freshBuildInput, newBuildPolicy)
                val freshCompiler = compositionAwaitEvaluated(service, fixture.ledger, compiler,
                    service.submit(compiler.client, compositionRequest("compiler-new-policy", freshCompilerInput)).reviewId)
                val freshBuild = compositionAwaitEvaluated(service, fixture.ledger, build,
                    service.submit(build.client, compositionRequest("build-new-policy", freshBuildInput)).reviewId)
                for ((status, expectedStatus, failureRule) in listOf(
                    Triple(freshCompiler, "failed", COMPOSITION_COMPILER_FAILURE),
                    Triple(freshBuild, "unavailable", COMPOSITION_BUILD_FAILURE),
                )) {
                    val raw = compositionRaw(compositionStored(fixture.ledger, status.reviewId))
                    val effective = assertNotNull(status.result)
                    assertEquals("incomplete", status.state)
                    assertEquals(expectedStatus, raw.status)
                    assertEquals(expectedStatus, effective.status)
                    assertEquals("accepted", effective.findings.single { it.ruleId == COMPOSITION_WARNING }.disposition,
                        "The actual captured owner suppression must apply to an ordinary warning, not be wholly rejected")
                    assertEquals("open", effective.findings.single { it.ruleId == failureRule }.disposition)
                    assertEquals(raw.copy(findings = raw.findings.map {
                        if (it.ruleId == COMPOSITION_WARNING) it.copy(disposition = "accepted") else it
                    }), effective, "Only the ordinary warning's disposition may differ: coverage/failures/diagnostics/metrics stay exact")
                    assertEquals(expectedStatus, effective.coverage.single { it.ruleId == failureRule }.state)
                    assertTrue(status.reasons.none { it in setOf("result_policy_unavailable", "result_policy_changed_protected_evidence") },
                        "The config matcher must preserve failures itself; this is not a kernel fallback masking a bad adapter")
                }
                assertEquals(4, provider.calls.get())
                assertEquals(oldCompilerStored, compositionStored(fixture.ledger, oldCompiler.reviewId))
                assertEquals(oldBuildStored, compositionStored(fixture.ledger, oldBuild.reviewId))
                assertEquals(oldCompilerEvents, compositionEvents(service, compiler).filter { it.reviewId == oldCompiler.reviewId })
                assertEquals(oldBuildEvents, compositionEvents(service, build).filter { it.reviewId == oldBuild.reviewId })
                val effects = AtomicInteger()
                val pairs = listOf(Triple(compiler, oldCompiler, freshCompiler), Triple(build, oldBuild, freshBuild))
                for ((client, old, fresh) in pairs) {
                    compositionObserveAll(service, client)
                    val cut = service.prepareGate(client.client)
                    assertFalse(cut.ready)
                    assertTrue(cut.reasons.none(::compositionDeliveryReason))
                    assertFalse("current_source_configuration_authority_unavailable" in cut.reasons)
                    assertFalse("configuration_authority_mismatch_or_unknown" in cut.reasons)
                    assertTrue("review_incomplete:${old.reviewId}" in cut.reasons)
                    assertTrue("review_incomplete:${fresh.reviewId}" in cut.reasons)
                    val failure = fresh.result!!.findings.single { it.ruleId != COMPOSITION_WARNING }
                    assertTrue("open_finding:${failure.fingerprint}" in cut.reasons)
                    val other = pairs.single { it.first.binding != client.binding }
                    assertFalse("review_incomplete:${other.second.reviewId}" in cut.reasons)
                    assertFalse("review_incomplete:${other.third.reviewId}" in cut.reasons)
                    val gate = service.completeGate(client.client, cut.cutId) { effects.incrementAndGet() }
                    assertFalse(gate.allowed)
                    assertFalse(gate.actionPerformed)
                }
                assertEquals(0, effects.get(), "Suppression and full delivery cannot convert compiler/build failure into completion")
            } finally { provider.release.countDown() }
        }
    }
}

private fun compositionKernelConfig() = KernelConfig(
    workerCount = 2, globalParallelism = 2, perWorktreeParallelism = 2,
    heartbeatMillis = 40, leaseMillis = 400, pollMillis = 5, analysisTimeoutMillis = 30_000,
)

private fun compositionSuppression(id: String, rule: String, reason: SuppressionReason = SuppressionReason.ACCEPTED_RISK) =
    SuppressionSpec(id, PolicySelector.Rule(rule), SuppressionScope.Project, reason,
        "SYNTHETIC explicit test-owner decision, not a waiver for any actual project finding")

private class CompositionFixture(private val root: Path) {
    val left = ConfigBinding("composition-owner", "composition-workspace", "composition-tree", "left-lineage")
    val right = left.copy(lineageId = "right-lineage")
    private val owner = ClientIdentity(left.ownerId, "explicit-synthetic-owner", "synthetic-owner-session")
    private val clock = Clock.systemUTC()
    private val authority = HmacOwnerAuthority(ByteArray(32) { (it + 17).toByte() },
        listOf(OwnerSessionRegistration(owner, setOf(left, right))))
    private val aliases = RuleAliases(listOf(COMPOSITION_WARNING, COMPOSITION_COMPILER_FAILURE, COMPOSITION_BUILD_FAILURE).map {
        PolicyRule(it, implementationDigest = Digests.sha256("SYNTHETIC implementation identity for plumbing only:$it"))
    })
    private val requests = AtomicInteger()
    val configs = anotherStore()
    val captures = SyntheticOwnerPolicyArchive(root.resolve("test-owner-policy-archives"))
    val ledger: Path = root.resolve("kernel-ledger")
    private val base: AnalysisInput

    init {
        val source = Files.createDirectory(root.resolve("synthetic-source"))
        val text = "val syntheticCompositionFixture = 1\n"
        Files.writeString(source.resolve("Composition.kt"), text)
        base = AnalysisInput(
            SnapshotManifest(Digests.sha256("SYNTHETIC composition snapshot:$text"), left.workspaceId, left.worktreeId,
                listOf(SnapshotFile("Composition.kt", Digests.sha256(text), text.toByteArray(Charsets.UTF_8).size.toLong())),
                ownerId = left.ownerId, declaredRoots = listOf(".")),
            source.toString(), AnalysisContext(compilerVersion = "SYNTHETIC-no-compiler-invocation",
                modelDigest = Digests.sha256("SYNTHETIC composition model"),
                dependencyDigest = Digests.sha256("SYNTHETIC composition dependencies"),
                toolchainDigest = Digests.sha256("SYNTHETIC composition toolchain"),
                fullProject = true, compilationFiles = listOf("Composition.kt")),
        )
    }

    fun anotherStore() = ConfigStore(root.resolve("actual-config-state"), aliases, authority, clock = clock)
    fun input(snapshot: ConfigSnapshot, rules: List<String> = listOf(COMPOSITION_WARNING)): AnalysisInput =
        base.copy(context = base.context.copy(configurationDigest = snapshot.policyDigest), requiredRuleIds = rules.distinct().sorted())
    fun client(id: String, binding: ConfigBinding) = ClientBinding(binding.ownerId, binding.workspaceId, binding.worktreeId,
        id, binding.lineageId, sessionId = "synthetic-session-$id")
    fun mutate(snapshot: ConfigSnapshot, layer: ConfigLayer, action: ConfigAction, store: ConfigStore = configs): ConfigSnapshot {
        val request = ConfigMutationRequest(snapshot.binding, layer, snapshot.revisionToken, action, "synthetic-owner-request-${requests.incrementAndGet()}")
        return store.mutate(request, authority.issueForExplicitOwnerAction(owner, request, clock.instant()))
    }
    fun enroll(adapter: SyntheticCapturedConfigAdapter, binding: ClientBinding, input: AnalysisInput,
               snapshot: ConfigSnapshot, ready: Boolean = true): SyntheticOwnerCapture {
        val capture = captures.enroll(configs, binding, input, snapshot)
        if (ready) adapter.preload(capture, snapshot)
        return capture
    }
    fun fence(store: ConfigStore = configs): SnapshotGate = ActualConfigSyntheticSourceFence(store, captures, base)
}

private data class CompositionPolicyKey(val binding: ClientBinding, val inputKey: String)
private data class SyntheticOwnerCapture(
    val key: CompositionPolicyKey,
    val input: AnalysisInput,
    val expected: ConfigRevisionToken,
    val path: Path,
    val archiveDigest: String,
    val archiveBytes: Long,
)

/** Test-owner records survive service reopen, not a simulated owner-process crash/re-enrollment.
 * Expected full binding/input/token/hash are retained independently of archived FrozenConfig bytes.
 * No record retains a ConfigSnapshot that could hide a missing archive during recovery. */
private class SyntheticOwnerPolicyArchive(private val root: Path) {
    private val records = ConcurrentHashMap<CompositionPolicyKey, SyntheticOwnerCapture>()
    init { Files.createDirectory(root) }

    fun enroll(store: ConfigStore, binding: ClientBinding, input: AnalysisInput, snapshot: ConfigSnapshot): SyntheticOwnerCapture {
        assertEquals(ConfigBinding(binding.ownerId, binding.workspaceId, binding.worktreeId, binding.lineageId), snapshot.binding)
        assertEquals(snapshot.policyDigest, input.context.configurationDigest)
        assertEquals(input.requiredRuleIds.distinct().sorted(), input.requiredRuleIds)
        assertEquals(input.snapshot.files.sortedBy { it.path }, input.snapshot.files)
        assertEquals(input.changedPaths.distinct().sorted(), input.changedPaths)
        val frozenInput = ContractJson.decodeFromString<AnalysisInput>(ContractJson.encodeToString(input))
        val key = CompositionPolicyKey(binding, frozenInput.analysisKey())
        val bytes = ContractJson.encodeToString(store.exportSnapshot(snapshot)).toByteArray(Charsets.UTF_8)
        assertTrue(bytes.size in 1..1_048_576)
        val name = Digests.sha256(ContractJson.encodeToString(binding) + ":" + key.inputKey).removePrefix("sha256:")
        val path = root.resolve("$name.json")
        Files.write(path, bytes, CREATE_NEW, WRITE)
        val capture = SyntheticOwnerCapture(key, frozenInput, snapshot.revisionToken, path, Digests.sha256(bytes), bytes.size.toLong())
        assertNull(records.putIfAbsent(key, capture), "A test-owner capture cannot be replaced in place")
        return capture
    }

    // Pure lookups: these methods never inspect policy files, acquire config locks, or call kernel.
    fun find(binding: ClientBinding, input: AnalysisInput): SyntheticOwnerCapture? =
        records[CompositionPolicyKey(binding, input.analysisKey())]?.takeIf { it.input == input }
    fun expectedFor(input: AnalysisInput): ConfigRevisionToken? = records.values
        .filter { it.input == input && it.key.inputKey == input.analysisKey() }.map { it.expected }.distinct().singleOrNull()
}

/** Pure publication callbacks, separate bounded disk restoration lane. This is deliberately
 * synthetic glue around the ACTUAL ConfigStore, not the production controller/CLI adapter. */
private class SyntheticCapturedConfigAdapter(
    private val configs: ConfigStore,
    private val captures: SyntheticOwnerPolicyArchive,
    private val restorationAllowed: Boolean = true,
) {
    private val loaded = ConcurrentHashMap<CompositionPolicyKey, ConfigSnapshot>()
    val evaluations = AtomicInteger()
    fun preload(capture: SyntheticOwnerCapture, snapshot: ConfigSnapshot) {
        assertEquals(capture.expected, snapshot.revisionToken)
        assertNull(loaded.putIfAbsent(capture.key, snapshot))
    }
    fun loadedToken(binding: ClientBinding, input: AnalysisInput): ConfigRevisionToken? =
        captures.find(binding, input)?.let { loaded[it.key]?.revisionToken }

    val policy: ResultPolicy = ResultPolicy.versioned(COMPOSITION_EVALUATOR, object : ResultPolicy {
        override fun isReady(binding: ClientBinding, input: AnalysisInput): Boolean {
            val capture = captures.find(binding, input) ?: return false
            return loaded[capture.key]?.revisionToken == capture.expected
        }

        override fun apply(binding: ClientBinding, input: AnalysisInput, raw: ProviderResult): ProviderResult {
            val capture = checkNotNull(captures.find(binding, input))
            val snapshot = checkNotNull(loaded[capture.key])
            check(snapshot.revisionToken == capture.expected)
            evaluations.incrementAndGet()
            // ConfigStore.apply is pure matching over this captured object; NO capture/restore/I/O.
            return configs.apply(snapshot, input, raw)
        }

        override fun restoreCaptured(binding: ClientBinding, input: AnalysisInput) {
            if (!restorationAllowed) return
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Synthetic policy restoration interrupted")
            val capture = checkNotNull(captures.find(binding, input))
            if (loaded[capture.key]?.revisionToken == capture.expected) return
            check(capture.archiveBytes in 1L..1_048_576L)
            check(Files.isRegularFile(capture.path, NOFOLLOW_LINKS)) { "Exact synthetic owner policy archive unavailable" }
            check(Files.size(capture.path) == capture.archiveBytes)
            val bytes = Files.readAllBytes(capture.path)
            check(bytes.size.toLong() == capture.archiveBytes && Digests.sha256(bytes) == capture.archiveDigest)
            val frozen = ContractJson.decodeFromString<FrozenConfig>(String(bytes, Charsets.UTF_8))
            check(frozen.binding == capture.expected.binding && frozen.revisionToken == capture.expected)
            val restored = configs.restoreSnapshot(frozen, capture.expected)
            check(restored.policyDigest == input.context.configurationDigest)
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Synthetic policy restoration interrupted")
            loaded.putIfAbsent(capture.key, restored)
        }
    })
}

/** Real configuration lock/token through the kernel's synchronous action; the fixed source/model/
 * dependency/toolchain state and epoch below are SYNTHETIC, not live source/host authority. */
private class ActualConfigSyntheticSourceFence(
    private val configs: ConfigStore,
    private val captures: SyntheticOwnerPolicyArchive,
    private val source: AnalysisInput,
) : SnapshotGate {
    private val sourceLock = Any()
    override fun <T> withCurrentSnapshot(input: AnalysisInput, action: (SnapshotCheck) -> T): T {
        val expected = captures.expectedFor(input)
            ?: return action(SnapshotCheck(false, reasons = listOf("synthetic_owner_capture_unavailable")))
        return configs.withCurrentConfig(expected.binding, expected) { configuration ->
            synchronized(sourceLock) {
                val sourceMatches = input.snapshot == source.snapshot && input.materializedRoot == source.materializedRoot &&
                    input.context.copy(configurationDigest = null) == source.context
                // Read matches in the INNER callback, after the synthetic source lock was acquired.
                val current = sourceMatches && configuration.matches
                action(SnapshotCheck(current = current, authoritative = current,
                    snapshotId = source.snapshot.snapshotId, sourceEpoch = 1,
                    configurationDigest = configuration.current.policyDigest,
                    modelDigest = source.context.modelDigest, dependencyDigest = source.context.dependencyDigest,
                    toolchainDigest = source.context.toolchainDigest,
                    analysisEpoch = Digests.sha256("SYNTHETIC source epoch 1:" + ContractJson.encodeToString(configuration.current.revisionToken)),
                    reasons = configuration.reasons + if (sourceMatches) emptyList() else listOf("synthetic_source_authority_mismatch"),
                    validUntilEpochMillis = configuration.current.validUntilEpochMillis))
            }
        }
    }
}

/** Latches delimit the concurrent edit window. Results/metrics/certificates are SYNTHETIC.
 * The qualified ordinary row exists only to reach the broker gate, never as actual rule evidence. */
private class SyntheticCompositionProvider(
    expectedStarts: Int,
    private val progressive: Boolean = false,
    private val failureResults: Boolean = false,
) : ProgressiveAnalysisProvider {
    override val id = COMPOSITION_PROVIDER
    override val version = COMPOSITION_VERSION
    override val supportedRuleIds = setOf(COMPOSITION_WARNING, COMPOSITION_COMPILER_FAILURE, COMPOSITION_BUILD_FAILURE)
    val firstPublished = CountDownLatch(1)
    val allPublished = CountDownLatch(expectedStarts)
    val release = CountDownLatch(1)
    val calls = AtomicInteger()
    val active = AtomicInteger()
    val sawCancellation = AtomicBoolean()

    override fun analyzeProgressive(input: AnalysisInput, cancellation: CancellationToken,
                                    onProgress: (ProviderResult) -> Unit): ProviderResult {
        calls.incrementAndGet()
        active.incrementAndGet()
        try {
            val raw = if (failureResults) compositionFailureResult(input) else compositionWarningResult(input)
            if (progressive) onProgress(raw.copy(status = "partial", reasons = listOf("synthetic_progress_only")))
            firstPublished.countDown()
            allPublished.countDown()
            check(release.await(20, TimeUnit.SECONDS)) { "Synthetic concurrent-review release timed out" }
            if (cancellation.isCancelled()) sawCancellation.set(true)
            cancellation.throwIfCancelled()
            return raw
        } finally { active.decrementAndGet() }
    }
}

private fun compositionFinding(input: AnalysisInput, rule: String) = Finding(
    rule, "SYNTHETIC integration finding", "Fixture evidence only", "Exercises actual configuration/kernel composition",
    SourceLocation("Composition.kt", input.snapshot.files.single().digest, 1, 0, 1, 3, "val"),
    Digests.sha256("SYNTHETIC occurrence:$rule:${input.snapshot.snapshotId}:${input.context.scope.id}"), COMPOSITION_PROVIDER,
    context = input.context.scope, evidence = listOf(Evidence("synthetic_fixture", "No actual diagnostic rule ran")),
)

private fun compositionWarningCoverage(input: AnalysisInput) = Coverage(COMPOSITION_WARNING, input.context.scope, "findings",
    provenance = "executed", certificateId = Digests.sha256("SYNTHETIC qualification:${input.analysisKey()}:$COMPOSITION_WARNING"),
    implemented = true, tested = true, qualified = true)

private fun compositionWarningResult(input: AnalysisInput) = ProviderResult(COMPOSITION_PROVIDER, "complete",
    findings = listOf(compositionFinding(input, COMPOSITION_WARNING)), coverage = listOf(compositionWarningCoverage(input)),
    providerVersion = COMPOSITION_VERSION)

private fun compositionFailureResult(input: AnalysisInput): ProviderResult {
    val compiler = COMPOSITION_COMPILER_FAILURE in input.requiredRuleIds
    val rule = if (compiler) COMPOSITION_COMPILER_FAILURE else COMPOSITION_BUILD_FAILURE
    val state = if (compiler) "failed" else "unavailable"
    check(input.requiredRuleIds.toSet() == setOf(COMPOSITION_WARNING, rule))
    val failure = compositionFinding(input, rule).copy(
        severity = if (compiler) "error" else "warning",
        evidence = listOf(Evidence(if (compiler) "compiler_diagnostic" else "build_failure",
            "SYNTHETIC failure shape; no compiler or build was invoked")),
        verification = if (compiler) emptyList() else listOf(Verification(kind = "build", expected = "Synthetic success", state = "failed")),
    )
    return ProviderResult(COMPOSITION_PROVIDER, state,
        findings = listOf(compositionFinding(input, COMPOSITION_WARNING), failure),
        coverage = listOf(compositionWarningCoverage(input), Coverage(rule, input.context.scope, state,
            reason = "synthetic_failure_not_qualified", provenance = "not_executed", implemented = true)),
        reasons = listOf(if (compiler) "synthetic_compiler_failure" else "synthetic_build_unavailable"),
        rawDiagnostics = listOf(RawDiagnostic("SYNTHETIC retained failure diagnostic", "error",
            upstreamId = "SYNTHETIC_FAILURE", location = failure.location)),
        metrics = AnalysisMetrics(elapsedMillis = 7, cpuMillis = 3, outputBytes = 91),
        providerVersion = COMPOSITION_VERSION)
}

private fun compositionRequest(id: String, input: AnalysisInput) = SubmitRequest(id, "synthetic-idempotency-$id", input)
private fun compositionPayload(event: ReviewEvent) = ContractJson.decodeFromString<ReviewStatus>(event.payload)
private fun compositionRaw(stored: CompositionStored) = ContractJson.decodeFromString<ProviderResult>(assertNotNull(stored.raw))
private fun compositionDeliveryReason(reason: String) = reason.startsWith("insertion_missing:") || reason.startsWith("model_observation_missing:")

private data class CompositionStored(val workKey: String, val input: String, val raw: String?, val effective: String?,
                                     val policyCertificate: String?, val policyValidation: String, val cancelled: Boolean)

/** Read-only SQLite/WAL access, not raw database-file I/O and not an internal kernel API. */
private fun compositionStored(root: Path, review: String): CompositionStored =
    DriverManager.getConnection("jdbc:sqlite:${root.resolve("ledger.sqlite").toUri()}?mode=ro").use { connection ->
        connection.createStatement().use { it.execute("PRAGMA busy_timeout=10000") }
        connection.prepareStatement("""SELECT r.work_key,r.input_json,w.result_json,r.effective_result_json,
            r.policy_certificate,r.policy_validation_json,r.cancelled FROM reviews r JOIN work w ON w.work_key=r.work_key
            WHERE r.review_id=?""").use { query ->
            query.setString(1, review)
            query.executeQuery().use { row ->
                assertTrue(row.next(), "Expected exact admitted review in actual SQLite storage")
                val stored = CompositionStored(row.getString(1), row.getString(2), row.getString(3), row.getString(4),
                    row.getString(5), row.getString(6), row.getInt(7) != 0)
                assertFalse(row.next())
                stored
            }
        }
    }

private fun compositionAwait(message: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
    while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
    assertTrue(condition(), message)
}

private fun compositionAwaitEvaluated(service: KernelService, root: Path, client: ClientRegistration, review: String): ReviewStatus {
    // A result in status can be RAW and still policy-pending; await the durable effective column.
    compositionAwait("Exact captured policy was not evaluated") { compositionStored(root, review).effective != null }
    return service.status(client.client, review)
}

private fun compositionEvents(service: KernelService, client: ClientRegistration): List<ReviewEvent> = buildList {
    var cursor: String? = null
    var pages = 0
    do {
        assertTrue(++pages <= 100, "Synthetic audit event pagination must terminate")
        val page = service.events(client.client, cursor, limit = 3)
        page.events.forEach {
            assertEquals(Digests.sha256(it.payload), it.payloadDigest)
            assertTrue(it.deliveryRequired)
        }
        addAll(page.events)
        cursor = page.cursor
    } while (page.hasMore)
}

/** Synthetic owner/host receipts ONLY. No actual model context or model request is attested. */
private fun compositionObserveAll(service: KernelService, client: ClientRegistration): List<ReviewEvent> =
    compositionEvents(service, client).also { events ->
        for (event in events) {
            val context = Digests.sha256("SYNTHETIC context:${event.payloadDigest}")
            service.acknowledge(client.adapter, listOf(event.eventId), ContextReceipt("ack-${event.eventId}", context,
                "synthetic-item-${event.eventId}", Instant.EPOCH.toString()))
            service.observe(client.adapter, listOf(event.eventId), ObservationReceipt("observe-${event.eventId}",
                "synthetic-no-model-request-${event.eventId}", listOf(context)))
        }
    }

/** Same exact operation-local capability boundary as ConfigAuditTest, whose helper is file-private.
 * This is setup, not another capability-classifier test. Never skip ordinary I/O, permission or
 * unexpected errors, and never precreate ConfigStore directories to bypass its durability path. */
private fun requireCompositionDirectorySync(root: Path) {
    val directory = Files.createTempDirectory(root, "composition-directory-sync-control-").toAbsolutePath().normalize()
    val file = directory.resolve("fixture.txt")
    val bytes = "SYNTHETIC directory-force capability control\n".toByteArray(Charsets.UTF_8)
    Files.write(file, bytes)
    assertContentEquals(bytes, Files.readAllBytes(file))
    FileChannel.open(file, WRITE).use { it.force(true) }
    Files.newDirectoryStream(directory).use { entries -> assertEquals(listOf(file.fileName), entries.map { it.fileName }) }
    val supported = try {
        FileChannel.open(directory, READ).use { it.force(true) }
        true
    } catch (failure: UnsupportedOperationException) {
        if (failure.cause != null || failure.suppressed.isNotEmpty()) throw failure
        false
    } catch (failure: AccessDeniedException) {
        if (failure.cause != null || failure.suppressed.isNotEmpty() || directory.fileSystem !== FileSystems.getDefault() ||
            directory.fileSystem.provider().javaClass.name != "sun.nio.fs.WindowsFileSystemProvider" ||
            failure.file != directory.toString() || failure.otherFile != null) throw failure
        false
    }
    // Every call is at method entry, outside negative/concurrency wrappers. Aborted coverage is
    // unavailable, never a passing claim about actual durable configuration or kernel integration.
    assumeTrue(supported, "Directory force unavailable; no actual config/kernel composition execution coverage is claimed")
}

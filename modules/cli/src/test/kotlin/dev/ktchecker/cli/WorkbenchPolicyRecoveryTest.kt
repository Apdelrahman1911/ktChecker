package dev.ktchecker.cli

import dev.ktchecker.config.*
import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Real Workbench policy object, ConfigStore archive, SQLite and restart. Raw findings are synthetic;
 * this is not compiler execution, shipped-CLI bypass evidence, or platform/model qualification. */
class WorkbenchPolicyRecoveryTest {
    private val limits = KernelConfig(workerCount = 1, globalParallelism = 1, perWorktreeParallelism = 1,
        maxPendingPerClient = 1, heartbeatMillis = 30, leaseMillis = 120, pollMillis = 5)

    @Test fun reopenedLazyPolicyRecoversExactArchiveWithoutAdoptingLaterOwnerConsentOrReplayingProvider() {
        val options = WorkbenchOptions(Files.createTempDirectory("ktchecker-workbench-policy-"), workerCount = 0)
        val root = options.state.resolve("policy-cache-test")
        val provider = SyntheticWarning()
        lateinit var saved: PreparedReview
        lateinit var client: ClientRegistration
        lateinit var original: ReviewStatus
        lateinit var originalEvents: List<ReviewEvent>
        lateinit var archive: Path
        lateinit var archiveDigest: String
        lateinit var evaluator: String
        lateinit var currentToken: ConfigRevisionToken
        Workbench(options).use { work ->
            saved = prepare(work)
            client = work.registration(saved.binding.clientId)
            evaluator = assertNotNull(work.capturedResultPolicy.evaluatorIdentity)
            assertTrue(work.capturedResultPolicy.isReady(saved.binding, saved.input))
            assertFalse(saved.executionAuthorized)
            KernelService.open(root, provider, limits, resultPolicy = work.capturedResultPolicy).use { kernel ->
                kernel.registerClient(client)
                original = awaitPolicy(kernel, client, kernel.submit(client.client,
                    SubmitRequest(saved.patchId, saved.idempotencyKey, saved.input)).reviewId)
                assertEquals("complete", original.state, original.reasons.toString())
                assertEquals("open", original.result!!.findings.single().disposition)
                originalEvents = kernel.events(client.client).events
            }
            // This later, explicit owner action must never become consent for the older capture.
            work.mutateConfig(client.binding.clientId, ConfigLayer.SHARED, saved.configuration.revisionToken,
                ConfigAction.AddSuppression(SuppressionSpec("accepted-later", PolicySelector.Rule(RULE),
                    SuppressionScope.Project, SuppressionReason.ACCEPTED_RISK, "Explicit unit owner action after capture")))
            val current = work.configs.capture(saved.configuration.binding)
            currentToken = current.revisionToken
            assertNotEquals(saved.configuration.revisionToken, currentToken)
            val freshInput = saved.input.copy(context = saved.input.context.copy(configurationDigest = current.policyDigest))
            assertEquals("accepted", work.configs.apply(current, freshInput, original.result!!).findings.single().disposition,
                "The later policy must actually differ, rather than being an inert test mutation")
            archive = work.state.keyFile("prepared", saved.input.analysisKey())
            archiveDigest = Digests.sha256(Files.readAllBytes(archive))
        }
        val held = archive.resolveSibling(archive.fileName.toString() + ".held")
        Files.move(archive, held)
        try {
            Workbench(options).use { work ->
                val policy = work.capturedResultPolicy // The exact object installed in work.kernel.
                assertEquals(evaluator, policy.evaluatorIdentity)
                assertFalse(policy.isReady(saved.binding, saved.input), "A reopened scalar capture begins with an empty lazy policy cache")
                val paths = work.configs.storagePaths(saved.configuration.binding)
                val sharedBefore = Files.readAllBytes(paths.shared)
                val localBefore = if (Files.exists(paths.local)) Files.readAllBytes(paths.local) else null
                KernelService.open(root, provider, limits.copy(workerCount = 0), resultPolicy = policy,
                    preparedInputObserver = PreparedInputObserver { _, _, _ -> error("Policy restoration cannot prepare or execute raw work") }).use { kernel ->
                    kernel.registerClient(client)
                    assertEquals(original, kernel.status(client.client, original.reviewId))
                    assertEquals(originalEvents, kernel.events(client.client).events)
                    assertFalse(policy.isReady(saved.binding, saved.input), "Historical effective reads do not restore or re-evaluate policy")
                    // Direct generic-kernel cache admission exercises the composition's lazy path;
                    // the ordinary Workbench submit path already prepares its exact capture eagerly.
                    val cached = kernel.submit(client.client, SubmitRequest("cached-review", "cached-review-key", saved.input))
                    assertEquals("incomplete", cached.state)
                    assertTrue("captured_policy_not_ready" in cached.reasons)
                    assertTrue("policy_evaluation_missing" in cached.reasons)
                    assertEquals("open", cached.result!!.findings.single().disposition)
                    awaitCondition("Unavailable exact archive was not kept auditable and pending") {
                        "captured_policy_restore_unavailable" in kernel.status(client.client, cached.reviewId).reasons
                    }
                    val pendingEvents = kernel.events(client.client).events
                    repeat(3) {
                        Thread.sleep(60)
                        assertEquals(pendingEvents, kernel.events(client.client).events, "Repeated missing archive must not churn events")
                    }
                    assertFalse(policy.isReady(saved.binding, saved.input))
                    assertEquals("client_queue_full", assertFailsWith<KernelException> {
                        kernel.submit(client.client, SubmitRequest("still-pending", "still-pending-key", saved.input))
                    }.code)
                    assertEquals(1, provider.calls.get())
                    assertEquals(currentToken, work.configs.capture(saved.configuration.binding).revisionToken)

                    Files.move(held, archive) // Restore only the same privately captured immutable record.
                    val recovered = awaitPolicy(kernel, client, cached.reviewId)
                    assertEquals("complete", recovered.state, recovered.reasons.toString())
                    assertEquals("open", recovered.result!!.findings.single().disposition,
                        "Newly accepted owner policy must not replace the archived policy at the old input digest")
                    assertEquals(original.result, recovered.result)
                    assertTrue(policy.isReady(saved.binding, saved.input))
                    assertEquals(1, provider.calls.get(), "workerCount=0 still restores policy, never provider work or execution authority")
                    assertEquals(pendingEvents.size + 1, kernel.events(client.client).events.size)
                    assertEquals(original, kernel.status(client.client, original.reviewId))
                    assertEquals(originalEvents, kernel.events(client.client).events.take(originalEvents.size))
                    assertEquals(archiveDigest, Digests.sha256(Files.readAllBytes(archive)))
                    assertEquals(currentToken, work.configs.capture(saved.configuration.binding).revisionToken)
                    assertContentEquals(sharedBefore, Files.readAllBytes(paths.shared))
                    if (localBefore == null) assertFalse(Files.exists(paths.local))
                    else assertContentEquals(localBefore, Files.readAllBytes(paths.local))
                    assertFalse(work.state.read<PreparedReview>(archive).executionAuthorized)
                }
            }
        } finally {
            if (Files.exists(held) && !Files.exists(archive)) Files.move(held, archive)
        }
    }

    @Test fun restorationRejectsAnyDifferentFullConsumerBindingBeforeCachingPolicy() {
        val options = WorkbenchOptions(Files.createTempDirectory("ktchecker-workbench-binding-"), workerCount = 0)
        val saved = Workbench(options).use(::prepare)
        Workbench(options).use { work ->
            val policy = work.capturedResultPolicy
            assertFalse(policy.isReady(saved.binding, saved.input))
            val different = listOf(saved.binding.copy(clientId = "other-client"), saved.binding.copy(sessionId = "other-session"),
                saved.binding.copy(lineageId = "other-lineage"), saved.binding.copy(generation = saved.binding.generation + 1),
                saved.binding.copy(worktreeId = "other-tree"), saved.binding.copy(workspaceId = "other-project"),
                saved.binding.copy(ownerId = "other-owner"))
            for (binding in different) {
                assertEquals("prepared_binding_mismatch", assertFailsWith<CliProblem> { policy.restoreCaptured(binding, saved.input) }.code)
                assertFalse(policy.isReady(binding, saved.input))
                assertFalse(policy.isReady(saved.binding, saved.input), "A rejected binding must not warm even the legitimate consumer's policy")
            }
            policy.restoreCaptured(saved.binding, saved.input)
            assertTrue(policy.isReady(saved.binding, saved.input))
            assertTrue(different.none { policy.isReady(it, saved.input) })
            assertEquals(saved, work.state.read<PreparedReview>(work.state.keyFile("prepared", saved.input.analysisKey())))
        }
    }

    private fun prepare(work: Workbench): PreparedReview {
        work.register("project", "tree", "policy-owner", "policy-session", "policy-lineage")
        val commit = work.overlay(OverlayDocument("project", "tree", "policy-owner", "policy-session", "captured-patch",
            changes = listOf(OverlayChange("put", "Main.kt", content = "val captured = 42\n"))))
        return work.prepare(PreparationSpec("policy-owner", commit.snapshotId, "captured-patch", "captured-request", listOf("Main.kt"),
            requiredRuleIds = listOf(RULE), addKotlinStdlib = false), false)
    }

    private fun awaitPolicy(kernel: KernelService, client: ClientRegistration, reviewId: String): ReviewStatus {
        awaitCondition("Policy did not reach a terminal evaluated result") {
            val status = kernel.status(client.client, reviewId)
            status.result != null && "policy_evaluation_missing" !in status.reasons
        }
        return kernel.status(client.client, reviewId)
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(condition(), message)
    }

    private class SyntheticWarning : AnalysisProvider {
        override val id = "synthetic-workbench-policy"
        override val version = "synthetic/1"
        override val supportedRuleIds = setOf(RULE)
        val calls = AtomicInteger()
        override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
            calls.incrementAndGet()
            cancellation.throwIfCancelled()
            val finding = Finding(RULE, "Synthetic warning", "Synthetic diagnosis", "Policy restoration fixture only",
                SourceLocation("Main.kt", input.snapshot.files.single().digest), Digests.sha256("synthetic-workbench-finding"), id,
                context = input.context.scope, severity = "warning")
            return ProviderResult(id, "complete", listOf(finding), listOf(Coverage(RULE, input.context.scope, "findings",
                provenance = "executed", certificateId = Digests.sha256("synthetic-workbench-coverage"),
                implemented = true, tested = true, qualified = true)), providerVersion = version)
        }
    }

    companion object { private const val RULE = "ktc.kotlin.detekt.style.magic-number" }
}

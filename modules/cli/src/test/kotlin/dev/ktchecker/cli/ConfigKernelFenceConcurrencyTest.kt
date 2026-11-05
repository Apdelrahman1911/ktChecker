package dev.ktchecker.cli

import dev.ktchecker.config.*
import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

/**
 * Actual ConfigStore locks/owner permits and KernelService SQLite/publication/scalar gates.
 * The source authority, provider and delivery receipts are synthetic and private to these tests.
 * This does NOT activate Workbench's deliberately nonauthoritative gate, the routed joint barrier,
 * compiler execution, real model observation, or any production full-project completion authority.
 */
class ConfigKernelFenceConcurrencyTest {
    @TempDir lateinit var temp: Path

    @Test fun siblingSharedEditWaitsThroughKernelActionWithoutRewritingCapturedPolicyOrResults() = fixture("action-first").use { f ->
        val order = ConcurrentLinkedQueue<String>()
        val actionEntered = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        val firstVerified = CountDownLatch(1)
        val pending = f.pendingSharedRemoval(object : ConfigWriteObserver {
            override fun beforeAtomicReplace(temporary: Path, destination: Path) { order += "owner-replace" }
        }, firstVerified)
        val actions = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val completion = pool.submit<GateResult> {
                f.kernel.completeGate(f.client.client, f.cut.cutId) {
                    // A deterministic structural check supplements the concurrent ordering:
                    // even another store/worktree must see the SAME held project fence here.
                    assertFailsWith<ConfigException> { pending.store.capture(f.sibling) }
                    order += "action-entered"
                    actions.incrementAndGet()
                    actionEntered.countDown()
                    await(releaseAction)
                    order += "action-returning"
                }
            }
            await(actionEntered)
            val edit = pool.submit<ConfigSnapshot> { pending.store.mutate(pending.request, pending.permit) }
            await(firstVerified)
            assertEquals(listOf("action-entered"), order.toList(), "Owner replacement cannot cross the held final-action fence")
            releaseAction.countDown()
            val done = completion.get(WAIT_SECONDS, TimeUnit.SECONDS)
            assertTrue(done.allowed, done.reasons.toString())
            assertTrue(done.actionPerformed)
            val committed = edit.get(WAIT_SECONDS, TimeUnit.SECONDS)
            assertEquals(listOf("action-entered", "action-returning", "owner-replace"), order.toList())
            assertEquals(2L, committed.shared.revision.revision)
            assertTrue(committed.shared.suppressions.isEmpty())
            assertEquals(0L, committed.local.revision.revision)
            assertEquals(pending.permit.requestDigest, committed.shared.auditEvents.last().requestDigest)
            f.assertCurrentRemovalAndHistoricalAcceptance()
            assertFalse(f.kernel.completeGate(f.client.client, f.cut.cutId) { actions.incrementAndGet() }.allowed)
            assertEquals(1, actions.get(), "The original final action is not replayed after the owner edit")
        } finally {
            releaseAction.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS), "Synthetic workers must terminate")
        }
    }

    @Test fun siblingSharedCommitWhileCompletionWaitsInvalidatesWholeReadyCutAndLeavesLedgerResponsive() = fixture("edit-first").use { f ->
        val replacing = CountDownLatch(1)
        val releaseReplace = CountDownLatch(1)
        val gateEntered = CountDownLatch(1)
        val pending = f.pendingSharedRemoval(object : ConfigWriteObserver {
            override fun beforeAtomicReplace(temporary: Path, destination: Path) {
                replacing.countDown()
                await(releaseReplace)
            }
        })
        val actions = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val edit = pool.submit<ConfigSnapshot> { pending.store.mutate(pending.request, pending.permit) }
            await(replacing) // Mutation holds the actual common project lock, before atomic replace.
            f.fence.beforeConfig = { gateEntered.countDown() }
            val completion = pool.submit<GateResult> {
                f.kernel.completeGate(f.client.client, f.cut.cutId) { actions.incrementAndGet() }
            }
            await(gateEntered)
            // An actual unrelated SQLite writer must still work while completion waits for CONFIG.
            // No DB internals, raw fd inspection, sleeping quiet windows or timeout-based success.
            val unrelated = f.kernel.registerClient(f.client.binding.copy(clientId = "unrelated-client", lineageId = "unrelated-lineage"))
            assertEquals("unrelated-client", unrelated.binding.clientId)
            assertEquals(f.status, f.kernel.status(f.client.client, f.status.reviewId))
            assertEquals(0, actions.get())
            releaseReplace.countDown()
            val committed = edit.get(WAIT_SECONDS, TimeUnit.SECONDS)
            val denied = completion.get(WAIT_SECONDS, TimeUnit.SECONDS)
            assertFalse(denied.allowed)
            assertFalse(denied.actionPerformed)
            assertEquals(0, actions.get())
            assertTrue("shared_configuration_changed" in denied.reasons, denied.reasons.toString())
            assertTrue("effective_policy_changed" in denied.reasons, denied.reasons.toString())
            assertTrue("current_source_configuration_authority_unavailable" in denied.reasons, denied.reasons.toString())
            assertEquals(2L, committed.shared.revision.revision)
            assertEquals(pending.permit.requestDigest, committed.shared.auditEvents.last().requestDigest)
            f.assertCurrentRemovalAndHistoricalAcceptance()
            assertFalse(f.kernel.prepareGate(f.client.client).ready, "Old captured consent cannot become current again after the edit")
        } finally {
            releaseReplace.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS), "Synthetic workers must terminate")
        }
    }

    private fun fixture(name: String): Fixture = Fixture(temp.resolve(name))

    private class Fixture(root: Path) : AutoCloseable {
        private val state = root.resolve("configuration")
        private val binding = ConfigBinding("owner", "project", "tree", "lineage")
        val sibling = binding.copy(worktreeId = "sibling-tree", lineageId = "sibling-lineage")
        private val owner = ClientIdentity("owner", "synthetic-owner-control", "synthetic-owner-session")
        private val aliases = RuleAliases(listOf(PolicyRule(RULE, implementationDigest = Digests.sha256("synthetic-rule-v1"))))
        private val authority = HmacOwnerAuthority(ByteArray(32) { it.toByte() },
            listOf(OwnerSessionRegistration(owner, setOf(binding, sibling))))
        val configs = ConfigStore(state, aliases, authority)
        val captured = run {
            val request = ConfigMutationRequest(binding, ConfigLayer.SHARED, configs.capture(binding).revisionToken,
                ConfigAction.AddSuppression(SuppressionSpec("accepted", PolicySelector.Rule(RULE), SuppressionScope.Project,
                    SuppressionReason.ACCEPTED_RISK, "Explicit synthetic owner decision before review")), "initial-owner-decision")
            configs.mutate(request, authority.issueForExplicitOwnerAction(owner, request))
        }
        private val sourceBytes = "fun synthetic() = 1\n".toByteArray()
        private val sourceRoot = root.resolve("source").also { Files.createDirectories(it); Files.write(it.resolve("Main.kt"), sourceBytes) }
        val input = AnalysisInput(
            SnapshotManifest(Digests.sha256("synthetic-config-fence:${root.fileName}"), binding.workspaceId, binding.worktreeId,
                listOf(SnapshotFile("Main.kt", Digests.sha256(sourceBytes), sourceBytes.size.toLong())),
                ownerId = binding.ownerId, declaredRoots = listOf(".")),
            sourceRoot.toString(), AnalysisContext(
                RuleContext(module = ":app", target = "jvm", sourceSet = "main", compilation = "main"),
                modelDigest = Digests.sha256("synthetic-model"), dependencyDigest = Digests.sha256("synthetic-dependencies"),
                toolchainDigest = Digests.sha256("synthetic-toolchain"), configurationDigest = captured.policyDigest, fullProject = true,
            ), requiredRuleIds = listOf(RULE),
        )
        private val consumer = ClientBinding(binding.ownerId, binding.workspaceId, binding.worktreeId, "review-client", binding.lineageId)
        private val provider = SyntheticWarning()
        val fence = ConfigSourceFence(configs, captured, input)
        val kernel = KernelService.open(root.resolve("broker"), provider,
            KernelConfig(workerCount = 1, globalParallelism = 1, perWorktreeParallelism = 1,
                heartbeatMillis = 30, leaseMillis = 120, pollMillis = 5), snapshotGate = fence,
            resultPolicy = ResultPolicy.versioned("ktchecker/test/captured-config-fence/1") { actualBinding, actualInput, raw ->
                assertEquals(consumer, actualBinding)
                assertEquals(captured.policyDigest, actualInput.context.configurationDigest)
                configs.apply(captured, actualInput, raw) // Pure captured projection: never acquires current config under DB.
            })
        val client: ClientRegistration
        val status: ReviewStatus
        private val retainedEvents: List<ReviewEvent>
        val cut: GateCut

        init {
            try {
                client = kernel.registerClient(consumer)
                status = awaitResult(kernel, client, kernel.submit(client.client, SubmitRequest("patch", "review-request", input)).reviewId)
                retainedEvents = observeAll(kernel, client)
                cut = kernel.prepareGate(client.client)
                assertEquals("complete", status.state, status.reasons.toString())
                assertEquals("accepted", status.result!!.findings.single().disposition)
                assertTrue(cut.ready, cut.reasons.toString())
                val primary = configs.storagePaths(binding)
                val other = configs.storagePaths(sibling)
                assertEquals(primary.shared, other.shared)
                assertEquals(primary.lock, other.lock)
                assertNotEquals(primary.local, other.local)
            } catch (failure: Throwable) {
                kernel.close()
                throw failure
            }
        }

        fun pendingSharedRemoval(observer: ConfigWriteObserver, firstVerified: CountDownLatch? = null): PendingEdit {
            val observed = MutationAuthority { request, permit, now ->
                authority.verify(request, permit, now).also { firstVerified?.countDown() }
            }
            val writer = ConfigStore(state, aliases, observed, writeObserver = observer)
            val current = writer.capture(sibling)
            val request = ConfigMutationRequest(sibling, ConfigLayer.SHARED, current.revisionToken,
                ConfigAction.RemoveSuppression("accepted"), "sibling-owner-removal")
            return PendingEdit(writer, request, authority.issueForExplicitOwnerAction(owner, request))
        }

        fun assertCurrentRemovalAndHistoricalAcceptance() {
            val current = configs.capture(binding)
            val raw = assertNotNull(provider.emitted)
            assertTrue(current.shared.suppressions.isEmpty())
            assertNotEquals(captured.policyDigest, current.policyDigest)
            assertEquals(captured.local.revision, current.local.revision)
            assertEquals("open", raw.findings.single().disposition)
            assertEquals(raw, configs.apply(current, input.copy(context = input.context.copy(configurationDigest = current.policyDigest)), raw))
            assertEquals(raw.copy(findings = raw.findings.map { it.copy(disposition = "accepted") }), status.result)
            assertEquals(status, kernel.status(client.client, status.reviewId), "Historical effective result is not reclassified by a current owner edit")
            assertEquals(retainedEvents, kernel.events(client.client).events, "Owner config changes do not rewrite prior delivery history")
            assertContentEquals(sourceBytes, Files.readAllBytes(sourceRoot.resolve("Main.kt")))
        }

        override fun close() = kernel.close()
    }

    private data class PendingEdit(val store: ConfigStore, val request: ConfigMutationRequest, val permit: MutationPermit)

    /** Test-only source lock. Current configuration and its epoch come from the actual store. */
    private class ConfigSourceFence(private val configs: ConfigStore, private val captured: ConfigSnapshot,
                                    private val expected: AnalysisInput) : SnapshotGate {
        private val source = ReentrantLock(true)
        @Volatile var beforeConfig: (() -> Unit)? = null
        override fun <T> withCurrentSnapshot(input: AnalysisInput, action: (SnapshotCheck) -> T): T {
            beforeConfig?.invoke()
            return configs.withCurrentConfig(captured.binding, captured.revisionToken) { config ->
                source.withLock {
                    action(SnapshotCheck(current = config.matches && input.snapshot == expected.snapshot && input.context == expected.context,
                        snapshotId = expected.snapshot.snapshotId, sourceEpoch = 1L,
                        configurationDigest = config.current.policyDigest, modelDigest = expected.context.modelDigest,
                        dependencyDigest = expected.context.dependencyDigest, toolchainDigest = expected.context.toolchainDigest,
                        analysisEpoch = Digests.sha256("synthetic-config-source:${config.current.policyDigest}:source-1"),
                        authoritative = true, reasons = config.reasons, validUntilEpochMillis = config.current.validUntilEpochMillis))
                }
            }
        }
    }

    private class SyntheticWarning : AnalysisProvider {
        override val id = "synthetic-config-fence"
        override val version = "synthetic/1"
        override val supportedRuleIds = setOf(RULE)
        @Volatile var emitted: ProviderResult? = null
        override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
            cancellation.throwIfCancelled()
            val finding = Finding(RULE, "Synthetic warning", "Synthetic diagnostic", "Config fence fixture only",
                SourceLocation("Main.kt", input.snapshot.files.single().digest), Digests.sha256("synthetic-config-finding"), id,
                context = input.context.scope, severity = "warning",
                evidence = listOf(Evidence("static_rule", "Synthetic evidence", digest = Digests.sha256("synthetic-evidence"))))
            return ProviderResult(id, "complete", listOf(finding), listOf(Coverage(RULE, input.context.scope, "findings",
                provenance = "executed", certificateId = Digests.sha256("synthetic-coverage"),
                implemented = true, tested = true, qualified = true)), providerVersion = version).also { emitted = it }
        }
    }

    companion object {
        private const val RULE = "ktc.test.config-fence"
        private const val WAIT_SECONDS = 15L

        private fun await(latch: CountDownLatch) = assertTrue(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), "Synthetic barrier timed out")

        private fun awaitResult(kernel: KernelService, client: ClientRegistration, reviewId: String): ReviewStatus {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
            var status = kernel.status(client.client, reviewId)
            while (status.result == null && System.nanoTime() < deadline) {
                Thread.sleep(5)
                status = kernel.status(client.client, reviewId)
            }
            assertNotNull(status.result, "Synthetic provider did not publish within the bounded wait")
            return status
        }

        private fun observeAll(kernel: KernelService, client: ClientRegistration): List<ReviewEvent> {
            val page = kernel.events(client.client)
            assertFalse(page.hasMore)
            assertEquals(listOf("accepted", "result"), page.events.map { it.kind })
            for (event in page.events) {
                val context = Digests.sha256("synthetic-context:${event.payload}")
                kernel.acknowledge(client.adapter, listOf(event.eventId), ContextReceipt(
                    "ack-${event.eventId}", context, "item-${event.eventId}", Instant.EPOCH.toString()))
                kernel.observe(client.adapter, listOf(event.eventId), ObservationReceipt(
                    "observation-${event.eventId}", "synthetic-request-${event.eventId}", listOf(context)))
            }
            return page.events
        }
    }
}

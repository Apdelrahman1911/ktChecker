package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * Real isolated SQLite/files/threads; ALL providers, model/merge/config/fence/delivery authorities
 * are deliberately SYNTHETIC. These tests do not qualify a compiler or implement real host delivery.
 * The dedicated crash history owns one fresh child JVM; other claimed/unknown states are exercised directly.
 */
class IntegratedCompletionBarrierTest {
    @TempDir lateinit var temp: Path
    private val config = KernelConfig(workerCount = 2, globalParallelism = 2, perWorktreeParallelism = 2,
        heartbeatMillis = 40, leaseMillis = 160, pollMillis = 5, analysisTimeoutMillis = 15_000)

    private class PlanRegistry : ReviewPlanAuthority, ReviewPlanRoutingAuthority {
        val plans = ConcurrentHashMap<String, RequiredReviewPlan>()
        val routes = ConcurrentHashMap<String, AuthorizedReviewRoute>()
        override fun resolve(binding: ClientBinding, request: SubmitPlanRequest) = plans[request.planRef]
        override fun authorize(binding: ClientBinding, request: SubmitPlanRequest, declared: RequiredReviewPlan) = routes[request.planRef]
    }

    private class Authority(var grant: AuthorizedIntegratedCompletion) : IntegratedCompletionAuthority {
        override fun authorize(binding: ClientBinding, completionRef: String): AuthorizedIntegratedCompletion? =
            grant.takeIf { it.origin == binding && it.completionRef == completionRef }
    }

    /**
     * Actual inventory identities, but deliberately SYNTHETIC execution/certificates. The true
     * flags satisfy the kernel fixture contract; they are NOT evidence that these rules exist,
     * execute, or are qualified in any real provider, target, compiler, or owner host.
     */
    private class FullCatalogSyntheticProvider(rules: List<String>) : AnalysisProvider {
        override val id = "synthetic-joint-full-catalog"
        override val supportedRuleIds = rules.toSet()
        val calls = AtomicInteger()
        override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
            cancellation.throwIfCancelled()
            calls.incrementAndGet()
            assertEquals(supportedRuleIds, input.requiredRuleIds.toSet())
            // Hash the large input once, not once per rule as the small toy helper does.
            val key = input.analysisKey()
            return ProviderResult(id, "complete", coverage = input.requiredRuleIds.map { rule ->
                Coverage(rule, input.context.scope, "passed", provenance = "executed",
                    certificateId = Digests.sha256("synthetic-only-full-catalog:$key:$rule"),
                    implemented = true, tested = true, qualified = true)
            })
        }
    }

    private fun actualFullCatalog(): List<String> {
        val bytes = assertNotNull(javaClass.getResourceAsStream("/joint-completion-full-catalog-1663.txt")).use { it.readBytes() }
        assertEquals("sha256:4c00ec83059325b8ee2fac672e6c53948746bda6920d900a9d1942ce1b03520d", Digests.sha256(bytes))
        val rules = bytes.toString(Charsets.UTF_8).lineSequence().filter { it.isNotEmpty() }.toList()
        assertEquals(1663, rules.size)
        assertEquals(rules.distinct().sorted(), rules)
        return rules
    }

    private fun fourDeclaredContexts() = listOf(
        RuleContext(target = "jvm", sourceSet = "main", module = ":application", compilation = "main"),
        RuleContext(target = "jvm", sourceSet = "test", module = ":application", compilation = "test"),
        RuleContext(target = "android", sourceSet = "androidMain", module = ":shared", compilation = "debug"),
        RuleContext(target = "iosArm64", sourceSet = "iosMain", module = ":shared", compilation = "main"),
    )

    /** Stored independent synthetic model/provenance state, NOT a check copied from request DTOs. */
    private class Fence(service: KernelService, private val plans: List<RequiredReviewPlan>) : IntegratedSnapshotFence {
        private val lock = ReentrantLock()
        val calls = AtomicInteger()
        var before: () -> Unit = {}
        var twice = false
        var after: () -> Unit = {}
        var retainOnly = false
        var foreignOnly = false
        var swallowInvalidCheck = false
        var throwBefore = false
        var retained: ((IntegratedSnapshotCheck) -> Unit)? = null
        val rejectedForeign = AtomicInteger()
        val held: Boolean get() = lock.isHeldByCurrentThread
        var check: IntegratedSnapshotCheck

        init {
            val patches = service.ledger.read { c -> plans.flatMap { plan ->
                val client = plan.binding.clientId
                c.query("SELECT review_id,patch_id,request_hash,input_json,sequence FROM reviews WHERE client_id=? ORDER BY sequence", client) { row ->
                    val input = ContractJson.decodeFromString<AnalysisInput>(row.getString("input_json"))
                    IntegratedPatchObligation(client, "review", row.getString("review_id"), row.getString("patch_id"), row.getString("request_hash"),
                        input.analysisKey(), input.snapshot.snapshotId, row.getLong("sequence"))
                } + c.query("SELECT plan_id,patch_id,request_hash,definition_json,plan_digest,review_frontier FROM parent_plans WHERE client_id=? ORDER BY rowid", client) { row ->
                    val definition = ContractJson.decodeFromString<RequiredReviewPlan>(row.getString("definition_json"))
                    IntegratedPatchObligation(client, "parent_plan", row.getString("plan_id"), row.getString("patch_id"), row.getString("request_hash"),
                        row.getString("plan_digest"), definition.slots.first().input.snapshot.snapshotId, row.getLong("review_frontier"))
                }
            } }
            check = IntegratedSnapshotCheck(true, true, plans.first().slots.first().input.snapshot.snapshotId,
                "synthetic-integration-epoch-1", Digests.sha256("synthetic-retained-merge-provenance"), plans.map { plan ->
                    val input = plan.slots.last().input
                    IntegratedMemberSnapshotCheck(plan.binding, Digests.sha256(canonical(plan)), SnapshotCheck(true, input.snapshot.snapshotId,
                        1, input.context.configurationDigest, input.context.modelDigest, input.context.dependencyDigest, input.context.toolchainDigest,
                        "synthetic-${plan.binding.clientId}-analysis-epoch-1", true, contextIdentities = plan.slots.mapNotNull { it.identity },
                        sourceBindingDigest = plan.sourceBindingDigest, generatedInputsDigest = plan.generatedInputsDigest))
                }, patches.map { obligation ->
                    val identity = integratedObligationDigest(obligation)
                    IntegratedPatchDisposition(identity, "incorporated", Digests.sha256("synthetic-patch-provenance:$identity"))
                })
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T> withCurrentIntegratedSnapshot(request: IntegratedFenceRequest, action: (IntegratedSnapshotCheck) -> T): T = lock.withLock {
            calls.incrementAndGet()
            plans.forEach { plan -> plan.slots.forEach { InputArchive.verifyFrozen(it.input) } }
            retained = { supplied -> action(supplied); Unit }
            if (throwBefore) error("synthetic-owner-fence-before-callback")
            // Deliberately broken owner SPI implementations. The boundary must ignore fabricated
            // generic returns and must reject every late/foreign callback BEFORE any claim work.
            if (retainOnly) return@withLock request as T
            if (foreignOnly) {
                val executor = Executors.newFixedThreadPool(2)
                try {
                    val attempted = (0..1).map { executor.submit {
                        val failure = runCatching { action(check) }.exceptionOrNull()
                        if (failure is KernelException && failure.code == "joint_fence_callback_invalid") rejectedForeign.incrementAndGet()
                    } }
                    attempted.forEach { it.get(2, TimeUnit.SECONDS) }
                } finally { executor.shutdownNow() }
                return@withLock request as T
            }
            if (swallowInvalidCheck) {
                runCatching { action(check.copy(members = List(65) { check.members.first() })) }
                return@withLock request as T
            }
            before()
            val result = action(check)
            if (twice) runCatching { action(check) }
            after()
            result
        }
    }

    private class Action : IntegratedCompletionAction {
        override val identity = Digests.sha256("synthetic-owner-final-delivery-action-v1")
        val calls = AtomicInteger()
        var body: (IntegratedCompletionPermit) -> Unit = {}
        var receipt = true
        override fun perform(permit: IntegratedCompletionPermit): IntegratedActionReceipt? {
            calls.incrementAndGet()
            body(permit)
            return if (receipt) IntegratedActionReceipt(permit.cutId, permit.scopeDigest,
                Digests.sha256("synthetic-verified-delivery:${permit.cutId}:${permit.scopeDigest}")) else null
        }
    }

    private class Fixture(
        val service: KernelService,
        val registry: PlanRegistry,
        val clients: List<ClientRegistration>,
        val plans: List<RequiredReviewPlan>,
        val oldPlans: List<RequiredReviewPlan>,
        val fence: Fence,
        val action: Action,
        val authority: Authority,
        val path: Path,
    ) : AutoCloseable {
        val barrier = IntegratedCompletionBarrier(service, authority)
        val origin get() = clients.first().client
        override fun close() = service.close()
    }

    private fun input(label: String, parent: String? = null): AnalysisInput {
        val root = temp.resolve("source-$label-${UUID.randomUUID()}")
        Files.createDirectories(root)
        val bytes = "package synthetic\nval value = 1\n"
        Files.writeString(root.resolve("Main.kt"), bytes)
        val snapshot = SnapshotManifest(Digests.sha256("synthetic-joint-snapshot:$label"), "workspace", "tree",
            listOf(SnapshotFile("Main.kt", Digests.sha256(bytes), bytes.toByteArray().size.toLong())), "owner", parent, declaredRoots = listOf("."))
        return AnalysisInput(snapshot, root.toString(), AnalysisContext(compilerVersion = "synthetic-compiler",
            modelDigest = Digests.sha256("synthetic-model"), dependencyDigest = Digests.sha256("synthetic-dependencies"),
            toolchainDigest = Digests.sha256("synthetic-toolchain"), configurationDigest = Digests.sha256("synthetic-config"),
            fullProject = true, compilationFiles = listOf("Main.kt")), listOf(PLAN_TEST_RULE))
    }

    private fun route(service: KernelService, registry: PlanRegistry, clients: List<ClientRegistration>, input: AnalysisInput,
        label: String, contexts: List<RuleContext> = listOf(input.context.scope)): List<RequiredReviewPlan> {
        val inputs = contexts.map { context -> input.copy(context = input.context.copy(scope = context)) }
        val plans = clients.map { client ->
            val id = "$label-${client.binding.clientId}"
            RequiredReviewPlan(client.binding, SubmitPlanRequest("patch-$id", "key-$id", "ref-$id"), Digests.sha256(canonical(input.snapshot)),
                Digests.sha256("synthetic-generated-empty"), Digests.sha256("synthetic-model-certificate"), Digests.sha256("synthetic-impact-$label"),
                inputs.mapIndexed { index, current -> RequiredPlanSlot("slot-${index.toString().padStart(3, '0')}", current,
                    assertNotNull(contextIdentity(current))) }, listOf(client.binding.clientId))
        }
        val recipients = clients.zip(plans).map { (client, plan) ->
            val digest = Digests.sha256(canonical(plan))
            val preparation = PlanRecipientPreparation(digest, Digests.sha256("synthetic-policy:${client.binding.clientId}:$digest"),
                Digests.sha256("synthetic-execution:${client.binding.clientId}:$digest"))
            AuthorizedPlanRecipient(plan, client.adapter, preparation) { inputs.forEach { InputArchive.verifyFrozen(it) }; preparation }
        }
        val declared = plans.first().copy(requiredRecipientClientIds = clients.map { it.binding.clientId })
        registry.plans[declared.request.planRef] = declared
        registry.routes[declared.request.planRef] = AuthorizedReviewRoute(declared, recipients)
        service.submitPlan(clients.first().client, declared.request)
        return plans
    }

    private fun awaitReviews(service: KernelService, clients: List<ClientRegistration>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        clients.forEach { client ->
            val ids = service.ledger.read { c -> c.query("SELECT review_id FROM reviews WHERE client_id=? ORDER BY sequence", client.binding.clientId) { it.getString(1) } }
            ids.forEach { id ->
                var status = service.status(client.client, id)
                while (status.result == null && System.nanoTime() < deadline) { Thread.sleep(5); status = service.status(client.client, id) }
                assertNotNull(status.result, "Synthetic review did not finish")
            }
        }
    }

    private fun fixture(
        history: Boolean = false,
        workers: Int = 2,
        provider: AnalysisProvider = PlanTestProvider(),
        requiredRuleIds: List<String> = listOf(PLAN_TEST_RULE),
        contexts: List<RuleContext> = listOf(RuleContext()),
        beforeFinal: (KernelService, List<ClientRegistration>, List<RequiredReviewPlan>) -> Unit = { _, _, _ -> },
    ): Fixture {
        val registry = PlanRegistry()
        val path = temp.resolve("ledger-${UUID.randomUUID()}")
        val service = KernelService.open(path, provider, config.copy(workerCount = workers), planAuthority = registry, routingAuthority = registry)
        try {
            val clients = listOf("a", "b").map { id -> service.registerClient(ClientBinding("owner", "workspace", "tree", id, "lineage-$id", 1, "session-$id")) }
            val oldInput = if (history) input("before").copy(requiredRuleIds = requiredRuleIds) else null
            val oldPlans = if (oldInput == null) emptyList() else route(service, registry, clients, oldInput, "before", contexts)
            if (history && workers != 0) awaitReviews(service, clients)
            beforeFinal(service, clients, oldPlans)
            val plans = route(service, registry, clients,
                input("integrated", oldInput?.snapshot?.snapshotId).copy(requiredRuleIds = requiredRuleIds), "integrated", contexts)
            if (workers != 0) awaitReviews(service, clients)
            val fence = Fence(service, plans)
            val action = Action()
            val members = clients.zip(plans).map { (client, plan) ->
                val digest = Digests.sha256(canonical(plan))
                val identity = Digests.sha256("synthetic-readiness:${canonical(client.binding)}:$digest")
                AuthorizedIntegratedMember(client.binding, plan.request, digest, client.adapter, identity) {
                    plan.slots.forEach { InputArchive.verifyFrozen(it.input) }; identity
                }
            }
            val authority = Authority(AuthorizedIntegratedCompletion("completion", clients.first().binding, clients.first().binding.clientId,
                Digests.sha256("synthetic-live-joint-owner-registration"), Digests.sha256(canonical(requiredRuleIds)), requiredRuleIds, members, fence, action))
            return Fixture(service, registry, clients, plans, oldPlans, fence, action, authority, path)
        } catch (failure: Throwable) { service.close(); throw failure }
    }

    private fun events(service: KernelService, client: ClientRegistration): List<ReviewEvent> = buildList {
        var cursor: String? = null
        do {
            val page = service.events(client.client, cursor, 2)
            addAll(page.events); cursor = page.cursor
        } while (page.hasMore)
    }

    private fun deliver(service: KernelService, client: ClientRegistration, observe: Boolean = true) {
        events(service, client).forEach { event ->
            val context = Digests.sha256("synthetic-context:${event.payload}")
            service.acknowledge(client.adapter, listOf(event.eventId), ContextReceipt("ack-${event.eventId}", context, "item-${event.eventId}", Instant.EPOCH.toString()))
            if (observe) service.observe(client.adapter, listOf(event.eventId), ObservationReceipt("observe-${event.eventId}",
                "synthetic-model-request-${event.eventId}", listOf(context)))
        }
    }

    private fun deliverAll(f: Fixture) = f.clients.forEach { deliver(f.service, it) }
    private fun ready(f: Fixture): IntegratedCompletionCut {
        deliverAll(f)
        return f.barrier.prepare(f.origin, "completion").also { assertTrue(it.ready, it.reasons.toString()) }
    }
    private fun states(f: Fixture): List<String> = f.service.ledger.read { c ->
        f.clients.map { client -> c.one("SELECT gate_state FROM clients WHERE client_id=?", client.binding.clientId) { it.getString(1) }!! }
    }
    private fun auditText(f: Fixture, cutId: String): String = f.service.ledger.read { c ->
        c.one("SELECT value FROM metadata WHERE key=?", IntegratedCompletionAuditCodec.key(cutId)) { it.getString(1) }!!
    }
    private fun assertCode(code: String, block: () -> Unit) = assertEquals(code, assertFailsWith<KernelException>(block = block).code)

    @Test fun positiveFinalRoutedIntegrationRetainsAllHistoriesAndClaimsEveryMemberOnce() = fixture(history = true).use { f ->
        val cut = ready(f)
        val before = f.service.ledger.read { c -> listOf("reviews", "parent_plans", "events", "routing_batches").map { c.count("SELECT COUNT(*) FROM $it") } }
        f.action.body = { permit -> assertTrue(f.fence.held); assertEquals(cut.scope, permit.scope); assertEquals(listOf("action_claimed", "action_claimed"), states(f)) }
        val result = f.barrier.complete(f.origin, cut.cutId)
        assertTrue(result.allowed, result.reasons.toString()); assertTrue(result.actionPerformed)
        assertEquals("complete", result.state); assertEquals(listOf("complete", "complete"), states(f))
        assertEquals(1, f.action.calls.get())
        assertFalse(f.barrier.complete(f.origin, cut.cutId).allowed)
        assertFalse(f.barrier.status(f.origin, cut.cutId).allowed)
        f.service.ledger.read { c ->
            assertEquals(before, listOf("reviews", "parent_plans", "events", "routing_batches").map { c.count("SELECT COUNT(*) FROM $it") })
            assertEquals(2L, c.count("SELECT COUNT(*) FROM clients WHERE routed_obligations=1"))
            assertEquals(2L, c.count("SELECT COUNT(*) FROM routing_batches"))
        }
        assertEquals(1, f.action.calls.get())
    }

    @Test fun defaultDenyCannotAcquireAuthorityFromAReferenceOrDecodedCut() = fixture().use { f ->
        deliverAll(f)
        assertCode("joint_completion_authority_unavailable") { IntegratedCompletionBarrier(f.service).prepare(f.origin, "completion") }
        assertEquals(listOf("open", "open"), states(f)); assertEquals(0, f.action.calls.get())
    }

    @Test fun oldBranchReceiptsAndOriginOnlyReceiptsDoNotProveCombinedPeerObservation() = fixture(history = true,
        beforeFinal = { service, clients, _ -> clients.forEach { deliver(service, it) } }).use { f ->
        deliver(f.service, f.clients.first())
        val missing = f.barrier.prepare(f.origin, "completion")
        assertFalse(missing.ready); assertTrue(missing.reasons.any { it.startsWith("joint_insertion_missing:b:") })
        deliver(f.service, f.clients.last(), observe = false)
        val inserted = f.barrier.prepare(f.origin, "completion")
        assertFalse(inserted.ready); assertTrue(inserted.reasons.any { it.startsWith("joint_model_observation_missing:b:") })
        assertFalse(f.barrier.complete(f.origin, inserted.cutId).allowed)
        assertEquals(0, f.action.calls.get()); assertTrue(ready(f).ready)
    }

    @Test fun actualPeerAdapterCapabilityAndEveryFullBindingAreRequired() = fixture().use { f ->
        val g = f.authority.grant
        val peer = g.members.last()
        val impostor = AuthorizedIntegratedMember(peer.binding, peer.request, peer.planDigest,
            AdapterCredential(peer.binding.clientId, f.clients.first().adapter.token), peer.readinessIdentity, peer.verifyForCompletion)
        f.authority.grant = AuthorizedIntegratedCompletion(g.completionRef, g.origin, g.integrationClientId, g.registrationDigest, g.catalogDigest,
            g.requiredRuleIds, listOf(g.members.first(), impostor), g.fence, g.action)
        assertCode("unauthorized") { f.barrier.prepare(f.origin, "completion") }
        assertEquals(0, f.fence.calls.get()); assertEquals(0, f.action.calls.get()); assertEquals(listOf("open", "open"), states(f))
    }

    @Test fun pendingAndHistoricalFailedReviewsCannotBeReplacedByTheFinalCleanBatch() {
        fixture(workers = 0).use { f ->
            deliverAll(f)
            val cut = f.barrier.prepare(f.origin, "completion")
            assertFalse(cut.ready); assertTrue(cut.reasons.any { "review_pending" in it })
            assertFalse(f.barrier.complete(f.origin, cut.cutId).allowed); assertEquals(0, f.action.calls.get())
        }
        fixture(history = true, provider = PlanTestProvider { input, _ ->
            if (input.snapshot.snapshotId == Digests.sha256("synthetic-joint-snapshot:before")) planPassed(input).copy(status = "incomplete", reasons = listOf("synthetic-prior-failure"))
            else planPassed(input)
        }).use { f ->
            deliverAll(f)
            val cut = f.barrier.prepare(f.origin, "completion")
            assertFalse(cut.ready); assertTrue(cut.reasons.any { "review_incomplete" in it }); assertEquals(0, f.action.calls.get())
        }
    }

    @Test fun cancellingOneHistoricalConsumerDoesNotCancelPeerOrDischargeJointObligation() = fixture(history = true,
        beforeFinal = { service, clients, _ ->
            val id = service.ledger.read { c -> service.plans.latest(c, clients.first().binding.clientId)!!.id }
            service.cancelPlan(clients.first().client, id)
            val peer = service.ledger.read { c -> service.plans.latest(c, clients.last().binding.clientId)!!.id }
            assertEquals("complete", service.planStatus(clients.last().client, peer).state)
        }).use { f ->
        deliverAll(f)
        val cut = f.barrier.prepare(f.origin, "completion")
        assertFalse(cut.ready); assertTrue(cut.reasons.any { "review_cancelled" in it })
        assertFalse(f.barrier.complete(f.origin, cut.cutId).allowed); assertEquals(listOf("open", "open"), states(f))
    }

    @Test @Timeout(value = 90, unit = TimeUnit.SECONDS)
    fun committedCancellationAndLostAckReplySurviveSharedWorkerDeathAndRemainInTheJointAudit() {
        // Only this newly started child is killed. The existing reconnect suite never kills a
        // claim owner; this history exercises recoverDead and its new per-subscriber outboxes.
        val provider = PlanTestProvider { input, cancellation ->
            assertFalse(cancellation.isCancelled())
            planPassed(input)
        }
        fixture(history = true, workers = 0, provider = provider).use { f ->
            val a = f.clients[0]
            val b = f.clients[1]
            val old = f.clients.zip(f.oldPlans).map { (client, plan) -> f.service.submitPlan(client.client, plan.request) }
            val current = f.clients.zip(f.plans).map { (client, plan) -> f.service.submitPlan(client.client, plan.request) }
            val oldReview = old.map { it.slots.single().reviewId }
            fun encodedAudit(service: KernelService, cut: IntegratedCompletionCut) = service.ledger.read { c ->
                assertNotNull(c.one("SELECT value FROM metadata WHERE key=?", IntegratedCompletionAuditCodec.key(cut.cutId)) { it.getString(1) })
            }
            fun audit(service: KernelService, cut: IntegratedCompletionCut) =
                IntegratedCompletionAuditCodec(IntegratedCompletionLimits()).decode(cut.cutId, encodedAudit(service, cut))
            fun counts(service: KernelService) = service.ledger.read { c ->
                listOf("reviews", "parent_plans", "plan_slots", "routing_batches", "routing_members", "work", "work_certificates",
                    "events", "acknowledgements", "observations", "receipts").associateWith { c.count("SELECT COUNT(*) FROM $it") }
            }
            fun gateStates(service: KernelService) = service.ledger.read { c -> f.clients.map { client ->
                assertNotNull(c.one("SELECT gate_state FROM clients WHERE client_id=?", client.binding.clientId) { it.getString(1) })
            } }
            fun ack(service: KernelService, client: ClientRegistration, event: ReviewEvent) =
                service.acknowledge(client.adapter, listOf(event.eventId), cancellationAckCrashReceipt(event))
            fun observe(service: KernelService, client: ClientRegistration, event: ReviewEvent) =
                service.observe(client.adapter, listOf(event.eventId), ObservationReceipt("observe-${event.eventId}",
                    "synthetic-model-request-${event.eventId}", listOf(cancellationAckCrashReceipt(event).contextDigest)))
            val initialCounts = counts(f.service)
            assertEquals(mapOf("reviews" to 4L, "parent_plans" to 4L, "plan_slots" to 4L, "routing_batches" to 2L,
                "routing_members" to 4L, "work" to 2L, "work_certificates" to 0L, "events" to 8L,
                "acknowledgements" to 0L, "observations" to 0L, "receipts" to 0L), initialCounts)
            val beforeCut = f.barrier.prepare(f.origin, "completion")
            assertFalse(beforeCut.ready)
            val beforeAudit = audit(f.service, beforeCut)
            val expectedIdentities = f.clients.indices.flatMap { index -> listOf(old[index], current[index]).flatMap { plan ->
                listOf(Triple(f.clients[index].binding.clientId, "parent_plan", plan.planId),
                    Triple(f.clients[index].binding.clientId, "review", plan.slots.single().reviewId))
            } }.toSet()
            assertEquals(8, beforeAudit.request.historicalObligations.size)
            assertEquals(expectedIdentities, beforeAudit.request.historicalObligations.map { Triple(it.clientId, it.kind, it.obligationId) }.toSet())
            assertEquals(2, beforeAudit.request.routes.size)

            // The application's durable checkpoint remains at B's first event. Knowing the next
            // page in this test oracle is not an application ACK reply or a persisted new cursor.
            val checkpoint = f.service.events(b.client, limit = 1).cursor
            val lostPage = f.service.events(b.client, checkpoint, 1)
            val lostEvent = lostPage.events.single()
            assertEquals("plan_accepted", lostEvent.kind)
            assertEquals(oldReview[1], lostEvent.reviewId)
            assertEquals(old[1].planId, ContractJson.decodeFromString<ReviewStatus>(lostEvent.payload).parentPlan?.planId)
            assertEquals(2L, lostEvent.sequence)
            val receipt = cancellationAckCrashReceipt(lostEvent)
            // Caps and deadlines are durable ledger policy; only the worker count may differ.
            val workerConfig = config.copy(workerCount = 1)
            val specFile = temp.resolve("owned-cancel-ack-child.json")
            val marker = temp.resolve("owned-cancel-ack-committed")
            val log = temp.resolve("owned-cancel-ack-child.log")
            assertFalse(Files.exists(marker))
            writeDurable(specFile, canonical(CancellationAckCrashSpec(workerConfig, f.clients, old[0].planId,
                f.oldPlans[0].slots.single().input.snapshot.snapshotId, checkpoint)).toByteArray())
            val javaExecutable = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
            val classpath = buildSet<String> {
                System.getProperty("java.class.path").split(java.io.File.pathSeparator).filter { it.isNotBlank() }.forEach(::add)
                var loader: ClassLoader? = KernelCancellationAckCrashChild::class.java.classLoader
                while (loader != null) {
                    if (loader is java.net.URLClassLoader) loader.urLs.filter { it.protocol == "file" }.forEach { add(Path.of(it.toURI()).toString()) }
                    loader = loader.parent
                }
                listOf(KernelCancellationAckCrashChild::class.java, PlanTestProvider::class.java, KernelService::class.java,
                    AnalysisInput::class.java, kotlin.Unit::class.java, kotlinx.serialization.KSerializer::class.java,
                    kotlinx.serialization.json.Json::class.java, Class.forName("org.sqlite.JDBC")).forEach {
                    add(Path.of(it.protectionDomain.codeSource.location.toURI()).toString())
                }
            }.joinToString(java.io.File.pathSeparator)
            val child = ProcessBuilder(javaExecutable.toString(), "-Xmx128m", "-XX:MaxMetaspaceSize=96m", "-XX:ReservedCodeCacheSize=32m",
                "-XX:+UseSerialGC", "-cp", classpath, KernelCancellationAckCrashChild::class.java.name,
                f.path.toString(), specFile.toString(), marker.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                fun committedPhase() = Files.isRegularFile(marker) && Files.readString(marker) == "committed-local-cancel-and-peer-ack\n"
                while (!committedPhase() && child.isAlive && System.nanoTime() < deadline) Thread.sleep(5)
                assertTrue(committedPhase(), "Owned child did not commit its phase: " + Files.readString(log).take(2000))
                assertTrue(child.isAlive)
                val claim = f.service.ledger.read { c ->
                    val keys = oldReview.map { id -> assertNotNull(c.one("SELECT work_key FROM reviews WHERE review_id=?", id) { it.getString(1) }) }
                    assertEquals(1, keys.distinct().size)
                    assertEquals(1L, c.count("SELECT COUNT(*) FROM work WHERE state='running'"))
                    assertNotNull(c.one("""SELECT w.work_key,w.owner_instance,w.attempt,i.pid,i.closed FROM work w
                        JOIN instances i ON i.instance_id=w.owner_instance WHERE w.work_key=? AND w.state='running'""", keys.first()) {
                        assertEquals(child.pid(), it.getLong("pid"))
                        assertEquals(0, it.getInt("closed"))
                        Triple(it.getString("work_key"), it.getString("owner_instance"), it.getLong("attempt"))
                    })
                }
                assertEquals(1L, claim.third)
                assertEquals("cancelled", f.service.planStatus(a.client, old[0].planId).state)
                assertTrue(f.service.status(a.client, oldReview[0]).cancelled)
                assertEquals("running", f.service.status(b.client, oldReview[1]).state)
                assertFalse(f.service.status(b.client, oldReview[1]).cancelled)
                assertEquals(0, provider.calls.get(), "The parent has no worker before the crash")
                val beforeCrashEvents = f.clients.associate { it.binding.clientId to events(f.service, it) }
                assertEquals(listOf(5, 4), f.clients.map { beforeCrashEvents.getValue(it.binding.clientId).size })
                val committedReceiptLog = f.service.ledger.read { c ->
                    assertEquals(1L, c.count("SELECT COUNT(*) FROM acknowledgements"))
                    assertEquals(0L, c.count("SELECT COUNT(*) FROM observations"))
                    assertEquals(1L, c.count("SELECT COUNT(*) FROM receipts"))
                    assertEquals(canonical(receipt), c.one("SELECT receipt_json FROM acknowledgements WHERE event_id=? AND client_id=?",
                        lostEvent.eventId, b.binding.clientId) { it.getString(1) })
                    assertNotNull(c.one("SELECT request_json FROM receipts WHERE client_id=? AND kind='ack' AND receipt_id=?",
                        b.binding.clientId, receipt.receiptId) { it.getString(1) })
                }
                // No control service remains alive to perform graceful recovery or consume delivery.
                // The child owns this real claim, and its cancellation/ACK transactions have returned.
                f.service.close()
                assertTrue(child.isAlive)
                child.destroyForcibly()
                assertTrue(child.waitFor(10, TimeUnit.SECONDS), "The newly owned child did not terminate")
                assertFalse(child.isAlive)
                assertNotEquals(0, child.exitValue())

                data class Retained(val cut: IntegratedCompletionCut, val audit: IntegratedCompletionAudit, val encoded: String,
                    val events: Map<String, List<ReviewEvent>>, val counts: Map<String, Long>)
                val retained = KernelService.open(f.path, provider, workerConfig.copy(workerCount = 0)).use { recovered ->
                    // Opening without workers gives a stable view of the dead-process transition,
                    // before any replacement attempt can publish. No SQL fault injection is used.
                    recovered.ledger.read { c ->
                        assertEquals(1L, c.count("SELECT closed FROM instances WHERE instance_id=?", claim.second))
                        val work = assertNotNull(c.one("SELECT state,owner_instance,attempt FROM work WHERE work_key=?", claim.first) {
                            Triple(it.getString(1), it.getString(2), it.getLong(3))
                        })
                        assertEquals("queued", work.first); assertNull(work.second); assertEquals(claim.third + 1L, work.third)
                        assertEquals(0L, c.count("SELECT COUNT(*) FROM work WHERE state='running'"))
                        assertEquals(1L, c.count("SELECT COUNT(*) FROM acknowledgements"))
                        assertEquals(0L, c.count("SELECT COUNT(*) FROM observations"))
                        assertEquals(canonical(receipt), c.one("SELECT receipt_json FROM acknowledgements WHERE event_id=? AND client_id=?",
                            lostEvent.eventId, b.binding.clientId) { it.getString(1) })
                        assertEquals(committedReceiptLog, c.one("SELECT request_json FROM receipts WHERE client_id=? AND kind='ack' AND receipt_id=?",
                            b.binding.clientId, receipt.receiptId) { it.getString(1) })
                    }
                    f.clients.forEachIndexed { index, client ->
                        val stream = events(recovered, client)
                        val prefix = beforeCrashEvents.getValue(client.binding.clientId)
                        assertEquals(prefix, stream.take(prefix.size))
                        val recovery = stream.drop(prefix.size).single()
                        assertEquals("recovered", recovery.kind); assertEquals(oldReview[index], recovery.reviewId)
                        val status = ContractJson.decodeFromString<ReviewStatus>(recovery.payload)
                        assertEquals(index == 0, status.cancelled)
                        assertEquals(if (index == 0) "cancelled" else "queued", status.state)
                        assertContains(status.reasons, "dead_process_work_recovered")
                        assertNull(status.result)
                        assertEquals(stream, events(recovered, client), "Fetching cannot acknowledge or discard the recovery event")
                    }
                    val beforeRetries = counts(recovered)
                    assertEquals("cancelled", recovered.cancelPlan(a.client, old[0].planId).state)
                    f.clients.forEachIndexed { index, client ->
                        listOf(f.oldPlans[index] to old[index], f.plans[index] to current[index]).forEach { (plan, accepted) ->
                            assertEquals(accepted.planId, recovered.submitPlan(client.client, plan.request).planId)
                        }
                    }
                    assertEquals(lostPage, recovered.events(b.client, checkpoint, 1))
                    assertCode("invalid_cursor") { recovered.events(a.client, checkpoint, 1) }
                    repeat(2) { assertEquals(ReceiptResult(b.binding.clientId, listOf(lostEvent.eventId), "acknowledged"), ack(recovered, b, lostEvent)) }
                    assertCode("receipt_conflict") { recovered.acknowledge(b.adapter, listOf(lostEvent.eventId),
                        receipt.copy(contextDigest = Digests.sha256("synthetic-conflicting-retry"))) }
                    assertCode("plan_not_found") { recovered.cancelPlan(a.client, old[1].planId) }
                    assertEquals(beforeRetries, counts(recovered), "Lost-reply retries cannot duplicate receipts, cancellations, or acceptance")
                    assertEquals(beforeAudit, audit(recovered, beforeCut))

                    KernelService.open(f.path, provider, workerConfig).use { awaitReviews(recovered, f.clients) }
                    assertEquals(2, provider.calls.get(), "One replacement shared execution plus one clean final shared execution")
                    f.clients.forEachIndexed { index, client ->
                        val historical = recovered.status(client.client, oldReview[index])
                        assertEquals(if (index == 0) "cancelled" else "complete", historical.state)
                        assertEquals(index == 0, historical.cancelled); assertNotNull(historical.result)
                        assertEquals("complete", recovered.planStatus(client.client, current[index].planId).state)
                        assertEquals(if (index == 0) "cancelled" else "complete", recovered.planStatus(client.client, old[index].planId).state)
                    }
                    recovered.ledger.read { c ->
                        assertEquals(claim.third + 2L, c.count("SELECT attempt FROM work WHERE work_key=?", claim.first))
                        assertEquals(2L, c.count("SELECT COUNT(*) FROM work WHERE state='complete'"))
                        assertEquals(2L, c.count("SELECT COUNT(*) FROM work_certificates"))
                    }
                    val streams = f.clients.associate { it.binding.clientId to events(recovered, it) }
                    assertEquals(listOf(8, 7), f.clients.map { streams.getValue(it.binding.clientId).size })
                    f.clients.forEachIndexed { index, client ->
                        val stream = streams.getValue(client.binding.clientId)
                        assertEquals((1L..stream.size.toLong()).toList(), stream.map { it.sequence })
                        assertEquals(stream.size, stream.map { it.eventId }.distinct().size)
                        stream.forEach { assertTrue(it.deliveryRequired); assertEquals(Digests.sha256(it.payload), it.payloadDigest) }
                        assertEquals(mapOf("accepted" to 2, "plan_accepted" to 2, "recovered" to 1, "result" to 2) +
                            if (index == 0) mapOf("cancelled" to 1) else emptyMap(), stream.groupingBy { it.kind }.eachCount())
                    }
                    assertTrue(streams.getValue("a").map { it.eventId }.intersect(streams.getValue("b").map { it.eventId }.toSet()).isEmpty())
                    val freshPeerEvents = streams.getValue("b").drop(beforeCrashEvents.getValue("b").size)
                    assertEquals(mapOf("recovered" to 1, "result" to 2), freshPeerEvents.groupingBy { it.kind }.eachCount())
                    deliver(recovered, a)
                    beforeCrashEvents.getValue("b").forEach { ack(recovered, b, it); observe(recovered, b, it) }
                    val barrier = IntegratedCompletionBarrier(recovered, f.authority)
                    val cancellationReasons = setOf("joint_parent_cancelled:a:${old[0].planId}",
                        "review_cancelled:${oldReview[0]}:a", "joint_publication_not_quiescent:a")
                    val missing = barrier.prepare(f.origin, "completion")
                    assertFalse(missing.ready)
                    assertEquals(cancellationReasons + freshPeerEvents.flatMap { listOf("joint_insertion_missing:b:${it.eventId}",
                        "joint_model_observation_missing:b:${it.eventId}") }, missing.reasons.toSet())
                    val missingAudit = audit(recovered, missing)
                    assertFalse(barrier.complete(f.origin, missing.cutId).allowed)
                    assertEquals(missingAudit, audit(recovered, missing))
                    freshPeerEvents.forEach { ack(recovered, b, it) }
                    val inserted = barrier.prepare(f.origin, "completion")
                    assertFalse(inserted.ready)
                    assertEquals(cancellationReasons + freshPeerEvents.map { "joint_model_observation_missing:b:${it.eventId}" }, inserted.reasons.toSet())
                    assertEquals(missingAudit.request, audit(recovered, inserted).request)
                    assertCode("event_not_found") { ack(recovered, a, freshPeerEvents.first()) }
                    assertCode("event_not_found") { observe(recovered, a, freshPeerEvents.first()) }
                    assertFalse(barrier.complete(f.origin, inserted.cutId).allowed)
                    assertEquals(0, f.action.calls.get()); assertEquals(listOf("open", "open"), gateStates(recovered))
                    freshPeerEvents.forEach { observe(recovered, b, it) }
                    val cut = barrier.prepare(f.origin, "completion")
                    assertEquals(cancellationReasons, cut.reasons.toSet()); assertFalse(cut.ready)
                    val finalAudit = audit(recovered, cut)
                    assertEquals(cut, finalAudit.cut); assertEquals("prepared", finalAudit.state); assertNull(finalAudit.receipt)
                    assertEquals(beforeAudit.request.scope, finalAudit.request.scope)
                    assertEquals(beforeAudit.request.scopeDigest, finalAudit.request.scopeDigest)
                    assertEquals(beforeAudit.request.historicalObligations, finalAudit.request.historicalObligations)
                    assertEquals(beforeAudit.request.routes, finalAudit.request.routes)
                    assertEquals(missingAudit.request, finalAudit.request)
                    f.clients.forEachIndexed { index, client ->
                        val prior = beforeAudit.request.frontiers.single { it.clientId == client.binding.clientId }
                        val frontier = finalAudit.request.frontiers.single { it.clientId == client.binding.clientId }
                        val stream = streams.getValue(client.binding.clientId)
                        assertEquals(2L, frontier.reviewFrontier)
                        assertEquals(stream.size.toLong(), frontier.eventFrontier)
                        assertEquals(stream.map { it.eventId }, frontier.requiredEventIds)
                        assertEquals(prior.lineageEpoch + if (index == 0) 4L else 3L, frontier.lineageEpoch)
                        assertNotEquals(prior.historyDigest, frontier.historyDigest)
                    }
                    assertFalse(barrier.complete(f.origin, cut.cutId).allowed)
                    assertEquals(finalAudit, audit(recovered, cut))
                    assertEquals(0, f.action.calls.get()); assertEquals(listOf("open", "open"), gateStates(recovered))
                    assertEquals(initialCounts + mapOf("work_certificates" to 2L, "events" to 15L, "acknowledgements" to 15L,
                        "observations" to 15L, "receipts" to 30L), counts(recovered))
                    Retained(cut, finalAudit, encodedAudit(recovered, cut), streams, counts(recovered))
                }
                KernelService.open(f.path, provider, workerConfig.copy(workerCount = 0)).use { reopened ->
                    val barrier = IntegratedCompletionBarrier(reopened, f.authority)
                    assertEquals(retained.audit, audit(reopened, retained.cut))
                    assertEquals(retained.encoded, encodedAudit(reopened, retained.cut))
                    assertEquals("cancelled", reopened.planStatus(a.client, old[0].planId).state)
                    assertEquals("complete", reopened.planStatus(b.client, old[1].planId).state)
                    assertFalse(barrier.status(f.origin, retained.cut.cutId).allowed)
                    assertFalse(barrier.complete(f.origin, retained.cut.cutId).allowed)
                    assertEquals(retained.encoded, encodedAudit(reopened, retained.cut))
                    f.clients.forEach { assertEquals(retained.events.getValue(it.binding.clientId), events(reopened, it)) }
                    assertEquals(retained.counts, counts(reopened))
                    assertEquals(listOf("open", "open"), gateStates(reopened))
                    assertEquals(2, provider.calls.get()); assertEquals(0, f.action.calls.get())
                }
            } finally {
                // Only the Process returned above is a signal target. Clear an existing test
                // interrupt just for the finite reap, then restore it; never scan/signal by PID.
                val interrupted = Thread.interrupted()
                try {
                    if (child.isAlive) child.destroyForcibly()
                    assertTrue(child.waitFor(10, TimeUnit.SECONDS), "The newly owned child was not reaped")
                } finally { if (interrupted) Thread.currentThread().interrupt() }
            }
        }
    }

    @Test fun missingHistoricalPatchDispositionEvenForAlreadyCompleteOldParentBlocks() = fixture(history = true).use { f ->
        f.fence.check = f.fence.check.copy(patchDispositions = f.fence.check.patchDispositions.dropLast(1))
        deliverAll(f)
        val cut = f.barrier.prepare(f.origin, "completion")
        assertFalse(cut.ready); assertTrue("joint_historical_patch_dispositions_incomplete" in cut.reasons)
        assertEquals(0, f.action.calls.get())
    }

    @Test fun sameBytesDoNotSurviveIntegrationOrPerMemberSourceConfigurationAba() = fixture().use { f ->
        val cut = ready(f)
        f.fence.check = f.fence.check.copy(integrationEpoch = "synthetic-integration-epoch-2-same-bytes")
        assertFalse(f.barrier.complete(f.origin, cut.cutId).allowed)
        val fresh = ready(f)
        f.fence.check = f.fence.check.copy(members = f.fence.check.members.mapIndexed { index, member ->
            if (index == 1) member.copy(check = member.check.copy(sourceEpoch = 2, analysisEpoch = "synthetic-config-aba-2")) else member
        })
        assertFalse(f.barrier.complete(f.origin, fresh.cutId).allowed)
        assertEquals(0, f.action.calls.get()); assertEquals(listOf("open", "open"), states(f))
    }

    @Test fun newerManualInputCannotBorrowOlderWholeIntegratedPlanAuthority() = fixture().use { f ->
        val cut = ready(f)
        val peer = f.clients.last()
        f.service.submit(peer.client, SubmitRequest("new-manual", "new-manual", f.plans.last().slots.single().input))
        awaitReviews(f.service, f.clients); deliverAll(f)
        assertFalse(f.barrier.complete(f.origin, cut.cutId).allowed)
        val changed = f.barrier.prepare(f.origin, "completion")
        assertFalse(changed.ready); assertTrue(changed.reasons.any { "joint_current_parent_plan_missing:b" == it })
        assertEquals(0, f.action.calls.get())
    }

    @Test fun admissionBetweenFactsAndJointClaimInvalidatesWholeCutWithoutAnyMemberPrefix() = fixture().use { f ->
        val cut = ready(f)
        f.fence.before = {
            f.service.submit(f.clients.last().client, SubmitRequest("racing-manual", "racing-manual", f.plans.last().slots.single().input))
        }
        val result = f.barrier.complete(f.origin, cut.cutId)
        assertFalse(result.allowed)
        assertTrue(result.reasons.any { it == "joint_member_frontier_changed_or_claimed:b" })
        assertEquals(listOf("open", "open"), states(f)); assertEquals(0, f.action.calls.get())
    }

    @Test fun anotherHistoricalRouteExtendsClosureAndRejectsAnOwnerSelectedSubset() = fixture().use { f ->
        val cut = ready(f)
        val c = f.service.registerClient(ClientBinding("owner", "workspace", "tree", "c", "lineage-c", 1, "session-c"))
        route(f.service, f.registry, listOf(f.clients.last(), c), f.plans.first().slots.single().input, "peer-new-route")
        awaitReviews(f.service, f.clients + c)
        assertCode("joint_route_closure_mismatch") { f.barrier.complete(f.origin, cut.cutId) }
        assertEquals(0, f.action.calls.get()); assertEquals(listOf("open", "open"), states(f))
    }

    @Test fun finalEffectRunsOutsideDatabaseLockButUnderAllMemberClaimsAndCompositeFence() = fixture().use { f ->
        val cut = ready(f)
        val executor = Executors.newSingleThreadExecutor()
        try {
            f.action.body = {
                assertTrue(f.fence.held)
                assertEquals(listOf("action_claimed", "action_claimed"), states(f))
                // A real concurrent writer must enter while the final callback is running.
                executor.submit { f.service.registerClient(ClientBinding("owner", "workspace", "unrelated", "unrelated", "unrelated")) }.get(2, TimeUnit.SECONDS)
                val peer = f.clients.last()
                val id = f.service.ledger.read { c -> f.service.plans.latest(c, peer.binding.clientId)!!.id }
                assertCode("lineage_finalized") { f.service.cancelPlan(peer.client, id) }
                assertCode("lineage_finalized") { f.service.submit(peer.client, SubmitRequest("too-late", "too-late", f.plans.last().slots.single().input)) }
                // Monotone receipt traffic is permitted and does not extend the frozen frontier.
                deliver(f.service, peer)
            }
            val result = f.barrier.complete(f.origin, cut.cutId)
            assertTrue(result.allowed, result.reasons.toString()); assertEquals(1, f.action.calls.get())
        } finally { executor.shutdownNow() }
    }

    @Test fun throwAfterClaimRetainsUnknownAcrossRestartAndNeverReplays() {
        val f = fixture()
        val cut = ready(f)
        f.action.body = { error("synthetic-after-effect-unknown") }
        try {
            val result = f.barrier.complete(f.origin, cut.cutId)
            assertFalse(result.allowed); assertEquals("outcome_unknown", result.state)
            assertEquals(listOf("outcome_unknown", "outcome_unknown"), states(f)); assertEquals(1, f.action.calls.get())
            assertFalse(f.barrier.complete(f.origin, cut.cutId).allowed)
        } finally { f.close() }
        KernelService.open(f.path, PlanTestProvider(), config.copy(workerCount = 0)).use { service ->
            val recovered = IntegratedCompletionBarrier(service, f.authority)
            assertEquals("outcome_unknown", recovered.status(f.origin, cut.cutId).state)
            assertFalse(recovered.complete(f.origin, cut.cutId).allowed)
            assertCode("lineage_finalized") { service.submit(f.origin, SubmitRequest("restart-too-late", "restart-too-late", f.plans.first().slots.single().input)) }
            assertEquals(1, f.action.calls.get())
        }
    }

    @Test fun concurrentCompletionCannotReplayFinalEffect() = fixture().use { f ->
        val cut = ready(f)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            f.action.body = { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)) }
            val first = executor.submit<IntegratedCompletionResult> { f.barrier.complete(f.origin, cut.cutId) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertFalse(f.barrier.complete(f.origin, cut.cutId).allowed)
            release.countDown()
            assertTrue(first.get(4, TimeUnit.SECONDS).allowed)
            assertEquals(1, f.action.calls.get())
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun repeatedOrThrowingFenceAndMissingActualEffectReceiptNeverReportCompletion() {
        fixture().use { f ->
            val cut = ready(f); f.fence.twice = true
            assertFalse(f.barrier.complete(f.origin, cut.cutId).allowed)
            assertEquals(1, f.action.calls.get()); assertEquals(listOf("outcome_unknown", "outcome_unknown"), states(f))
        }
        fixture().use { f ->
            val cut = ready(f); f.action.receipt = false
            assertFalse(f.barrier.complete(f.origin, cut.cutId).allowed)
            assertEquals(listOf("outcome_unknown", "outcome_unknown"), states(f)); assertEquals(1, f.action.calls.get())
        }
        fixture().use { f ->
            val cut = ready(f); f.fence.after = { error("synthetic-fence-release-failure-after-effect") }
            assertFalse(f.barrier.complete(f.origin, cut.cutId).allowed)
            assertEquals(listOf("outcome_unknown", "outcome_unknown"), states(f)); assertEquals(1, f.action.calls.get())
            assertCode("joint_fence_callback_invalid") { f.fence.retained!!.invoke(f.fence.check) }
            assertFalse(f.barrier.complete(f.origin, cut.cutId).allowed); assertEquals(1, f.action.calls.get())
        }
    }

    @Test fun retainedCallbackAfterReturnOrFenceThrowCannotClaimOrPerformAnythingLate() {
        fixture().use { f ->
            val cut = ready(f); f.fence.retainOnly = true
            assertFalse(f.barrier.complete(f.origin, cut.cutId).allowed)
            assertCode("joint_fence_callback_invalid") { f.fence.retained!!.invoke(f.fence.check) }
            assertEquals(0, f.action.calls.get()); assertEquals(listOf("open", "open"), states(f))
        }
        fixture().use { f ->
            val cut = ready(f); f.fence.throwBefore = true
            assertFalse(f.barrier.complete(f.origin, cut.cutId).allowed)
            assertCode("joint_fence_callback_invalid") { f.fence.retained!!.invoke(f.fence.check) }
            assertEquals(0, f.action.calls.get()); assertEquals(listOf("open", "open"), states(f))
        }
    }

    @Test fun simultaneousForeignThreadCallbacksAndSwallowedCallbackErrorsCannotFabricateOutcome() {
        fixture().use { f ->
            val cut = ready(f); f.fence.foreignOnly = true
            assertFalse(f.barrier.complete(f.origin, cut.cutId).allowed)
            assertEquals(2, f.fence.rejectedForeign.get()); assertEquals(0, f.action.calls.get())
            assertEquals(listOf("open", "open"), states(f))
        }
        fixture().use { f ->
            val cut = ready(f); f.fence.swallowInvalidCheck = true
            assertFalse(f.barrier.complete(f.origin, cut.cutId).allowed)
            assertEquals(0, f.action.calls.get()); assertEquals(listOf("open", "open"), states(f))
        }
    }

    @Test fun boundedHistoryRejectsWholeClosureAndMetadataCarriesNoPrivateCapabilities() = fixture(history = true).use { f ->
        deliverAll(f)
        assertCode("joint_history_limit") { IntegratedCompletionBarrier(f.service, f.authority, IntegratedCompletionLimits(maxReviews = 1)).prepare(f.origin, "completion") }
        assertEquals(listOf("open", "open"), states(f))
        val cut = ready(f)
        val audit = auditText(f, cut.cutId)
        f.clients.forEach { client -> assertFalse(client.adapter.token in audit); assertFalse(client.client.token in audit) }
        assertFalse("materializedRoot" in audit); assertFalse("effective_result_json" in audit)
        assertTrue("historicalObligations" in audit)
        assertEquals(0, f.action.calls.get())
    }

    @Test fun actual1663CatalogFreshTwoClientFourContextBatchRequiresIndependentCombinedObservation() {
        val rules = actualFullCatalog()
        val contexts = fourDeclaredContexts()
        val provider = FullCatalogSyntheticProvider(rules)
        fixture(provider = provider, requiredRuleIds = rules, contexts = contexts).use { f ->
            // Every catalog ID is required in EVERY declared context, without applicability,
            // activation, provider-support, or target filtering to manufacture a small pass.
            f.plans.forEach { plan ->
                assertEquals(contexts.toSet(), plan.slots.map { it.input.context.scope }.toSet())
                plan.slots.forEach { assertEquals(rules, it.input.requiredRuleIds) }
            }
            deliver(f.service, f.clients.first())
            deliver(f.service, f.clients.last(), observe = false)
            val missingPeer = f.barrier.prepare(f.origin, "completion")
            assertFalse(missingPeer.ready)
            assertTrue(missingPeer.reasons.any { it.startsWith("joint_model_observation_missing:b:") })
            assertFalse(f.barrier.complete(f.origin, missingPeer.cutId).allowed)
            assertEquals(0, f.action.calls.get())

            val cut = ready(f) // Default limits; no history deletion or increased cap.
            assertEquals(6652, cut.scope.requiredCoverage.size)
            assertEquals(contexts.toSet(), cut.scope.requiredCoverage.map { it.context }.toSet())
            cut.scope.requiredCoverage.groupBy { it.context }.values.forEach { coverage ->
                assertEquals(rules, coverage.map { it.ruleId })
            }
            assertEquals(Digests.sha256(canonical(rules)), cut.scope.catalogDigest)
            val before = f.service.ledger.read { c ->
                listOf("reviews", "parent_plans", "events", "routing_batches").map { c.count("SELECT COUNT(*) FROM $it") }
            }
            assertEquals(8L, before[0]); assertEquals(2L, before[1]); assertEquals(1L, before[3])
            f.action.body = { permit ->
                assertTrue(f.fence.held)
                assertEquals(cut.scope, permit.scope)
                assertEquals(listOf("action_claimed", "action_claimed"), states(f))
            }
            val result = f.barrier.complete(f.origin, cut.cutId)
            assertTrue(result.allowed, result.reasons.toString())
            assertEquals(listOf("complete", "complete"), states(f))
            assertEquals(1, f.action.calls.get())
            assertFalse(f.barrier.complete(f.origin, cut.cutId).allowed)
            f.service.ledger.read { c ->
                assertEquals(before, listOf("reviews", "parent_plans", "events", "routing_batches").map { c.count("SELECT COUNT(*) FROM $it") })
                assertEquals(2L, c.count("SELECT COUNT(*) FROM clients WHERE routed_obligations=1"))
            }
        }
    }

    @Test fun actual1663CatalogHistoricalReviewsCountAgainstPairCeilingEvenWhenFinalVectorFits() {
        val rules = actualFullCatalog()
        val defaults = IntegratedCompletionLimits()
        assertEquals(65_536, defaults.maxCoveragePairs)
        assertEquals(39, defaults.maxCoveragePairs / rules.size)
        assertEquals(64_857, rules.size * 39)
        assertTrue(rules.size * 40 > defaults.maxCoveragePairs)
        assertEquals(9, defaults.maxCoveragePairs / (rules.size * 4))
        // These are arithmetic ceilings, NOT a claim that 39 complete batches fit the separate
        // 32 MiB history and 4 MiB audit bounds. The history test below uses two actual batches.
        fixture(history = true, provider = FullCatalogSyntheticProvider(rules), requiredRuleIds = rules).use { f ->
            val cut = ready(f)
            val audit = auditText(f, cut.cutId)
            val obligations = ContractJson.parseToJsonElement(audit).jsonObject.getValue("request").jsonObject
                .getValue("historicalObligations").jsonArray
            assertEquals(8, obligations.size) // 4 historical/current child reviews AND 4 parent patches.
            assertEquals(4, obligations.count { it.jsonObject.getValue("kind").jsonPrimitive.content == "review" })
            assertEquals(4, obligations.count { it.jsonObject.getValue("kind").jsonPrimitive.content == "parent_plan" })
            val before = f.service.ledger.read { c ->
                listOf("reviews", "parent_plans", "events", "routing_batches", "metadata").map { c.count("SELECT COUNT(*) FROM $it") }
            }
            // Exercise the same retained-history bound cheaply with a SMALLER owner ceiling:
            // the final 1663-pair vector fits, but the old+new 3326 pairs/client must not.
            val limited = IntegratedCompletionBarrier(f.service, f.authority, defaults.copy(maxCoveragePairs = rules.size))
            assertCode("joint_history_limit") { limited.prepare(f.origin, "completion") }
            assertEquals(listOf("open", "open"), states(f)); assertEquals(0, f.action.calls.get())
            f.service.ledger.read { c ->
                assertEquals(before, listOf("reviews", "parent_plans", "events", "routing_batches", "metadata").map { c.count("SELECT COUNT(*) FROM $it") })
            }
            assertTrue(f.barrier.complete(f.origin, cut.cutId).allowed)
            assertEquals(1, f.action.calls.get())
        }
    }

    @Test fun actual1663CatalogCompactAuditRemovesDuplicateVectorOverflowButNeverDischargesPendingHistory() {
        val rules = actualFullCatalog()
        val contexts = (0 until 9).map { RuleContext(module = ":features:capacity-module-$it") }
        val vector = contexts.flatMap { context -> rules.map { IntegratedCoverageRequirement(context, it) } }
        val vectorBytes = canonical(vector).toByteArray(Charsets.UTF_8).size
        val defaults = IntegratedCompletionLimits()
        assertEquals(4 * 1024 * 1024, defaults.maxAuditBytes)
        assertTrue(vectorBytes < defaults.maxAuditBytes)
        assertTrue(2L * vectorBytes > defaults.maxAuditBytes) // The unchanged legacy doubled vector cannot fit.
        val provider = FullCatalogSyntheticProvider(rules)
        // Workers are deliberately not started to isolate representation capacity from large
        // result-history bytes. Pending work is RETAINED, never counted as successful or waived.
        fixture(workers = 0, provider = provider, requiredRuleIds = rules, contexts = contexts).use { f ->
            val before = f.service.ledger.read { c ->
                listOf("reviews", "parent_plans", "events", "routing_batches", "metadata").map { c.count("SELECT COUNT(*) FROM $it") }
            }
            assertEquals(18L, before[0]); assertEquals(2L, before[1]); assertEquals(1L, before[3])
            val cut = f.barrier.prepare(f.origin, "completion")
            assertFalse(cut.ready)
            assertEquals(vector, cut.scope.requiredCoverage)
            assertEquals(18, cut.reasons.count { it.startsWith("review_pending:") })
            val missingCoverage = cut.reasons.filter { it.startsWith("current_coverage_missing:") }.toSet()
            assertEquals(29_934, missingCoverage.size)
            f.clients.forEach { client -> contexts.forEach { context -> rules.forEach { rule ->
                assertTrue("current_coverage_missing:${context.id}:$rule:${client.binding.clientId}" in missingCoverage)
            } } }
            assertTrue("joint_publication_not_quiescent:a" in cut.reasons)
            assertTrue("joint_publication_not_quiescent:b" in cut.reasons)
            assertFalse(f.barrier.complete(f.origin, cut.cutId).allowed)
            val audit = auditText(f, cut.cutId)
            assertTrue(audit.toByteArray(Charsets.UTF_8).size < defaults.maxAuditBytes)
            val decoded = IntegratedCompletionAuditCodec(defaults).decode(cut.cutId, audit)
            assertEquals(IntegratedCompletionAuditCodec.CURRENT_FORMAT, decoded.format)
            assertEquals(cut, decoded.cut)
            assertEquals(20, decoded.request.historicalObligations.size) // All18 reviews AND2 parents.
            assertTrue(canonical(decoded.request).toByteArray(Charsets.UTF_8).size <= defaults.maxAuditBytes)
            assertEquals(0, provider.calls.get()); assertEquals(0, f.action.calls.get())
            assertEquals(listOf("open", "open"), states(f))
            f.service.ledger.read { c ->
                assertEquals(before.take(4), listOf("reviews", "parent_plans", "events", "routing_batches").map { c.count("SELECT COUNT(*) FROM $it") })
                assertEquals(before.last() + 1, c.count("SELECT COUNT(*) FROM metadata")) // ONE retained not-ready v2 audit.
                assertEquals(18L, c.count("SELECT COUNT(*) FROM reviews WHERE cancelled=0 AND effective_result_json IS NULL"))
                assertEquals(9L, c.count("SELECT COUNT(*) FROM work WHERE state='queued'"))
                assertEquals(2L, c.count("SELECT COUNT(*) FROM clients WHERE routed_obligations=1"))
            }
        }
    }

    @Test fun everyLegacyAuditStateIsReadOnlyWithoutOwnerResolutionMigrationOrGateReset() {
        val codec = IntegratedCompletionAuditCodec(IntegratedCompletionLimits())
        listOf("prepared", "action_claimed", "outcome_unknown", "complete").forEach { state -> fixture().use { f ->
            val current = ready(f)
            val saved = codec.decode(current.cutId, auditText(f, current.cutId))
            val legacyCut = current.copy(cutId = current.cutId.replace("joint-v2:", "joint-v1:"))
            val legacy = saved.copy(format = IntegratedCompletionAuditCodec.LEGACY_FORMAT, cut = legacyCut, state = state,
                receipt = if (state in setOf("complete", "outcome_unknown")) IntegratedActionReceipt(legacyCut.cutId,
                    legacyCut.scopeDigest, Digests.sha256("synthetic-retained-legacy-receipt")) else null)
            val encoded = codec.encode(legacy)
            val expectedState = if (state == "prepared") "open" else state
            f.service.ledger.transaction { c ->
                c.execute("INSERT INTO metadata(key,value) VALUES(?,?)", IntegratedCompletionAuditCodec.key(legacyCut.cutId), encoded)
                f.clients.forEach { client -> c.execute("UPDATE clients SET gate_state=?,active_cut_id=? WHERE client_id=?",
                    expectedState, if (state == "prepared") null else legacyCut.cutId, client.binding.clientId) }
            }
            val barrier = IntegratedCompletionBarrier(f.service, IntegratedCompletionAuthority { _, _ ->
                error("A legacy audit must never resolve or invoke a live owner capability")
            })
            val before = f.service.ledger.read { c ->
                listOf("reviews", "parent_plans", "events", "routing_batches", "metadata").map { c.count("SELECT COUNT(*) FROM $it") }
            }
            listOf(barrier.complete(f.origin, legacyCut.cutId), barrier.status(f.origin, legacyCut.cutId)).forEach { result ->
                assertFalse(result.allowed); assertFalse(result.actionPerformed)
                assertEquals(state, result.state)
                assertEquals(listOf("joint_legacy_audit_only_no_permission"), result.reasons)
            }
            assertEquals(listOf(expectedState, expectedState), states(f))
            assertEquals(encoded, auditText(f, legacyCut.cutId))
            assertEquals(0, f.action.calls.get())
            f.service.ledger.read { c ->
                assertEquals(before, listOf("reviews", "parent_plans", "events", "routing_batches", "metadata").map { c.count("SELECT COUNT(*) FROM $it") })
                assertEquals(2L, c.count("SELECT COUNT(*) FROM clients WHERE routed_obligations=1"))
            }
            if (state != "prepared") {
                val newer = f.barrier.prepare(f.origin, "completion")
                assertFalse(newer.ready)
                assertFalse(f.barrier.complete(f.origin, newer.cutId).allowed)
                assertEquals(listOf(expectedState, expectedState), states(f))
            }
        } }
    }

    @Test fun legacyAndCompactAuditsShareOneCutQuotaWithoutRetiringHistoryOrBorrowingLegacyAuthority() = fixture().use { f ->
        val cut = ready(f)
        val codec = IntegratedCompletionAuditCodec(IntegratedCompletionLimits())
        val saved = codec.decode(cut.cutId, auditText(f, cut.cutId))
        val legacy = saved.copy(format = IntegratedCompletionAuditCodec.LEGACY_FORMAT,
            cut = cut.copy(cutId = cut.cutId.replace("joint-v2:", "joint-v1:")))
        f.service.ledger.transaction { c ->
            c.execute("INSERT INTO metadata(key,value) VALUES(?,?)", IntegratedCompletionAuditCodec.key(legacy.cut.cutId), codec.encode(legacy))
        }
        val limited = IntegratedCompletionBarrier(f.service, f.authority, IntegratedCompletionLimits(maxCuts = 2))
        val before = f.service.ledger.read { c ->
            listOf("reviews", "parent_plans", "events", "routing_batches", "metadata").map { c.count("SELECT COUNT(*) FROM $it") }
        }
        assertCode("joint_cut_limit") { limited.prepare(f.origin, "completion") }
        assertEquals(listOf("open", "open"), states(f)); assertEquals(0, f.action.calls.get())
        assertFalse(limited.complete(f.origin, legacy.cut.cutId).allowed)
        assertTrue(limited.complete(f.origin, cut.cutId).allowed) // Explicit independently prepared v2 authority only.
        assertEquals(1, f.action.calls.get())
        f.service.ledger.read { c ->
            assertEquals(before, listOf("reviews", "parent_plans", "events", "routing_batches", "metadata").map { c.count("SELECT COUNT(*) FROM $it") })
        }
        assertEquals("prepared", codec.decode(legacy.cut.cutId, auditText(f, legacy.cut.cutId)).state)
    }

    @Test fun compactPreparationReservesActualReceiptAndUnknownReadbackBeforeAnyMemberClaim() {
        listOf(false, true).forEach { failAfterEffect -> fixture().use { f ->
            val first = ready(f)
            val defaults = IntegratedCompletionLimits()
            val codec = IntegratedCompletionAuditCodec(defaults)
            val saved = codec.decode(first.cutId, auditText(f, first.cutId))
            val worst = saved.copy(state = "outcome_unknown", receipt = IntegratedActionReceipt(first.cutId,
                first.scopeDigest, Digests.sha256("synthetic-maximal-receipt-size")))
            val bound = codec.encode(worst).toByteArray(Charsets.UTF_8).size
            assertTrue(canonical(saved.request).toByteArray(Charsets.UTF_8).size < bound)
            assertTrue(auditText(f, first.cutId).toByteArray(Charsets.UTF_8).size < bound)
            val tooSmall = IntegratedCompletionBarrier(f.service, f.authority, defaults.copy(maxAuditBytes = bound - 1))
            val before = f.service.ledger.read { c -> c.count("SELECT COUNT(*) FROM metadata") }
            assertCode("joint_audit_limit") { tooSmall.prepare(f.origin, "completion") }
            assertEquals(before, f.service.ledger.read { c -> c.count("SELECT COUNT(*) FROM metadata") })
            assertEquals(listOf("open", "open"), states(f)); assertEquals(0, f.action.calls.get())
            val exact = IntegratedCompletionBarrier(f.service, f.authority, defaults.copy(maxAuditBytes = bound))
            val cut = exact.prepare(f.origin, "completion")
            assertTrue(cut.ready, cut.reasons.toString())
            assertNull(IntegratedCompletionAuditCodec(defaults.copy(maxAuditBytes = bound)).decode(cut.cutId, auditText(f, cut.cutId)).receipt)
            if (failAfterEffect) f.fence.after = { error("synthetic-fence-failure-after-verified-effect") }
            val result = exact.complete(f.origin, cut.cutId)
            assertEquals(!failAfterEffect, result.allowed)
            val state = if (failAfterEffect) "outcome_unknown" else "complete"
            assertEquals(state, result.state); assertEquals(listOf(state, state), states(f))
            assertEquals(1, f.action.calls.get())
            val retained = auditText(f, cut.cutId)
            assertTrue(retained.toByteArray(Charsets.UTF_8).size <= bound)
            val record = IntegratedCompletionAuditCodec(defaults.copy(maxAuditBytes = bound)).decode(cut.cutId, retained)
            assertEquals(state, record.state); assertNotNull(record.receipt)
            assertEquals(state, exact.status(f.origin, cut.cutId).state)
            assertFalse(exact.complete(f.origin, cut.cutId).allowed); assertEquals(1, f.action.calls.get())
        } }
    }

    @Test fun legacyScalarGateRetainsPermanentRouteDenialAndPeerCannotUseOriginCut() = fixture().use { f ->
        val cut = ready(f)
        f.clients.forEach { client ->
            val scalar = f.service.prepareGate(client.client)
            assertFalse(scalar.ready)
            assertTrue("cross_client_finalization_unavailable" in scalar.reasons)
            assertFalse(f.service.completeGate(client.client, scalar.cutId).allowed)
        }
        assertCode("joint_cut_not_found") { f.barrier.complete(f.clients.last().client, cut.cutId) }
        assertEquals(0, f.action.calls.get()); assertEquals(listOf("open", "open"), states(f))
    }
}

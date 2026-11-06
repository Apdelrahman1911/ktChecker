package dev.ktchecker.reviewcontroller

import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.*
import dev.ktchecker.projectmodel.VerifiedProjectModel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Real imported model/immutable-capture capabilities, enrolled adapters, SQLite, archives and
 * worker threads. Export/approval callbacks and the provider are SYNTHETIC, not executed Gradle
 * or a compiler. All provider coverage remains unqualified. No current joint source authority,
 * successful local-cut composition or production provider qualification is claimed by these tests.
 */
class ControllerRoutingIntegrationTest {
    @TempDir lateinit var temp: Path
    private val config = KernelConfig(workerCount = 1, globalParallelism = 1, perWorktreeParallelism = 1,
        heartbeatMillis = 50, leaseMillis = 400, pollMillis = 5, analysisTimeoutMillis = 15_000)
    private fun fixture() = ControllerFixtures(temp.resolve(UUID.randomUUID().toString()))
    private fun assemble(f: ControllerFixtures, capture: ControllerFixtures.Capture, model: VerifiedProjectModel,
                         client: String, request: SubmitPlanRequest = f.request(client), before: VerifiedProjectModel? = null,
                         prep: TestPreparations = TestPreparations(), binding: ClientBinding = f.binding(client)) =
        prep.assembler(f).assemble(binding, request, before, model, capture.materialization(model), listOf(client))
    private fun assertKernelCode(code: String, action: () -> Unit) =
        assertEquals(code, assertFailsWith<KernelException>(block = action).code)

    private class Attempt(val original: AnalysisInput, val effective: AnalysisInput, val verify: () -> Unit)

    /** Requires the actual exact transformed archive callback on every attempt, never a DTO flag. */
    private class ArchiveProvider(assemblies: List<AssembledReviewPlan>) : AnalysisProvider {
        override val id = "unit-provider"
        override val supportedRuleIds = assemblies.flatMap { it.provenance.inventory.ruleIds }.toSet()
        private val originals = assemblies.flatMap { it.plan.slots }.filter { it.state == "pending" }
            .map { it.input }.associateBy { it.analysisKey() }
        private val permits = ConcurrentHashMap<String, Attempt>()
        val executed = ConcurrentLinkedQueue<AnalysisInput>()
        val attempts = ConcurrentLinkedQueue<Attempt>()
        val policyCalls = ConcurrentLinkedQueue<Pair<ClientBinding, String>>()
        var beforeAnalyze: (CancellationToken) -> Unit = {}
        private val policies = assemblies.flatMap { assembly -> assembly.plan.slots.map { slot ->
            (assembly.plan.binding to slot.input.analysisKey()) to assembly.provenance.slots.single { it.slotId == slot.slotId }.policyIdentity
        } }.toMap()
        val policy = ResultPolicy.versioned("synthetic-owner-route-captured-policy/1") { binding, input, raw ->
            val key = binding to input.analysisKey()
            val identity = requireNotNull(policies[key]) { "Missing this exact consumer's retained policy" }
            check(identity.binding == binding && identity.configurationDigest == input.context.configurationDigest)
            policyCalls += key
            raw // Never turn unqualified execution into a successful coverage certificate.
        }
        val observer = PreparedInputObserver { original, effective, verifyFrozen ->
            assertEquals(originals[original.analysisKey()], original)
            assertNotEquals(original.materializedRoot, effective.materializedRoot)
            assertNotNull(effective.outputRoot); assertNull(effective.execution)
            assertEquals(original.context.classpath.size, effective.context.classpath.size)
            original.context.classpath.zip(effective.context.classpath).forEach { (old, frozen) ->
                assertEquals(old.coordinate, frozen.coordinate); assertEquals(old.digest, frozen.digest)
                assertNotEquals(old.path, frozen.path)
            }
            assertEquals(original, effective.copy(materializedRoot = original.materializedRoot, outputRoot = original.outputRoot,
                context = effective.context.copy(classpath = original.context.classpath)))
            verifyFrozen()
            val attempt = Attempt(original, freezeInput(effective), verifyFrozen)
            assertNull(permits.putIfAbsent(effective.analysisKey(), attempt))
            attempts += attempt
        }
        override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
            val attempt = requireNotNull(permits.remove(input.analysisKey())) { "No live exact-archive test permit" }
            assertEquals(attempt.effective, input); attempt.verify()
            beforeAnalyze(cancellation)
            cancellation.throwIfCancelled()
            input.snapshot.files.forEach { file ->
                val bytes = Files.readAllBytes(Path.of(input.materializedRoot).resolve(file.path))
                assertEquals(file.size, bytes.size.toLong()); assertEquals(file.digest, Digests.sha256(bytes))
            }
            input.context.classpath.forEach { assertEquals(it.digest, Digests.sha256(Files.readAllBytes(Path.of(it.path)))) }
            assertEquals(attempt.original.context.compilationFiles, input.context.compilationFiles)
            assertTrue(input.snapshot.files.size > input.context.compilationFiles!!.size)
            attempt.verify(); executed += freezeInput(input)
            return ProviderResult(id, "incomplete", coverage = input.requiredRuleIds.map {
                Coverage(it, input.context.scope, "unsupported", "synthetic_provider_unqualified", provenance = "executed")
            }, reasons = listOf("synthetic_provider_unqualified"))
        }
    }

    private fun awaitResults(kernel: KernelService, client: ClientRegistration, parent: ParentPlanStatus) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        parent.slots.forEach { slot ->
            var status = kernel.status(client.client, slot.reviewId)
            while (status.result == null && System.nanoTime() < deadline) {
                Thread.sleep(5); status = kernel.status(client.client, slot.reviewId)
            }
            val result = assertNotNull(status.result, "Child did not publish: $status")
            assertFalse(status.cancelled)
            if (slot.state == "unsupported") {
                assertContains(result.reasons, "required_plan_scope_unsupported")
                assertTrue(result.coverage.isEmpty())
            } else {
                assertEquals("incomplete", status.state)
                assertEquals(listOf("synthetic_provider_unqualified"), result.reasons,
                    "An archive/authorization/assertion failure is not the expected unqualified execution")
                assertEquals(slot.requiredRuleIds.toSet(), result.coverage.map { it.ruleId }.toSet())
                assertTrue(result.coverage.all { !it.implemented && !it.tested && !it.qualified && it.certificateId == null })
            }
        }
        assertEquals("incomplete", kernel.planStatus(client.client, parent.planId).state)
    }
    private fun events(kernel: KernelService, client: ClientRegistration): List<ReviewEvent> = buildList {
        var cursor: String? = null
        do { val page = kernel.events(client.client, cursor, 3); addAll(page.events); cursor = page.cursor } while (page.hasMore)
    }
    private fun insertionDigest(event: ReviewEvent) = Digests.sha256("synthetic-route-context:${event.clientId}:${event.payloadDigest}")
    private fun acknowledge(kernel: KernelService, client: ClientRegistration, event: ReviewEvent) {
        assertEquals(Digests.sha256(event.payload), event.payloadDigest)
        kernel.acknowledge(client.adapter, listOf(event.eventId), ContextReceipt("ack-${event.eventId}", insertionDigest(event),
            "item-${event.eventId}", Instant.EPOCH.toString()))
    }
    private fun observe(kernel: KernelService, client: ClientRegistration, event: ReviewEvent) {
        kernel.observe(client.adapter, listOf(event.eventId), ObservationReceipt("observation-${event.eventId}",
            "synthetic-model-request-${event.eventId}", listOf(insertionDigest(event))))
    }
    private fun deliver(kernel: KernelService, client: ClientRegistration, selected: List<ReviewEvent> = events(kernel, client)) {
        selected.forEach { acknowledge(kernel, client, it); observe(kernel, client, it) }
    }
    private fun missing(cut: GateCut) = cut.reasons.filter { it.startsWith("insertion_missing:") || it.startsWith("model_observation_missing:") }
    private fun deniedJointCompletion(kernel: KernelService, client: ClientRegistration): GateCut {
        val cut = kernel.prepareGate(client.client)
        assertContains(cut.reasons, "cross_client_finalization_unavailable"); assertFalse(cut.ready)
        var actions = 0
        val result = kernel.completeGate(client.client, cut.cutId) { actions++ }
        assertContains(result.reasons, "cross_client_finalization_unavailable")
        assertFalse(result.allowed); assertFalse(result.actionPerformed); assertEquals(0, actions)
        return cut
    }
    private fun enrollAndRoute(kernel: KernelService, registry: OwnerReviewRouteRegistry,
                               assemblies: List<AssembledReviewPlan>): List<Pair<ClientRegistration, ParentPlanStatus>> {
        val clients = assemblies.map { kernel.registerClient(it.plan.binding) }
        val handles = assemblies.zip(clients).map { (assembly, client) -> OwnerRouteRecipient(assembly, client.adapter) }
        val request = registry.register(handles.first(), handles)
        assertKernelCode("plan_authority_unavailable") { kernel.submitPlan(clients.last().client, assemblies.last().plan.request) }
        val origin = kernel.submitPlan(clients.first().client, request)
        return clients.zip(assemblies).mapIndexed { index, (client, assembly) ->
            client to (if (index == 0) origin else kernel.submitPlan(client.client, assembly.plan.request))
        }
    }

    @Test fun actualWholeRouteSharesOnlyComputationAndRecoversDistinctAcceptedOutboxesAfterRestart() {
        val f = fixture(); val capture = f.capture(); val model = f.import(capture); val prep = TestPreparations()
        val assemblies = listOf("a", "b").map { assemble(f, capture, model, it, prep = prep) }
        val registry = OwnerReviewRouteRegistry(f.namespace.ownerId); val provider = ArchiveProvider(assemblies)
        val database = f.work.resolve("kernel-route")
        val accepted = KernelService.open(database, provider, config.copy(workerCount = 0), planAuthority = registry,
            routingAuthority = registry, preparedInputObserver = provider.observer, resultPolicy = provider.policy).use { kernel ->
            enrollAndRoute(kernel, registry, assemblies).also { pairs ->
                pairs.zip(assemblies).forEach { (pair, assembly) ->
                    val (client, parent) = pair
                    assertEquals("pending", parent.state); assertEquals(assembly.planDigest, parent.planDigest)
                    assertEquals(assembly.plan.slots.map { it.slotId }, parent.slots.map { it.slotId })
                    assertEquals((1L..(parent.slots.size + 1L)).toList(), events(kernel, client).map { it.sequence })
                    val payload = ContractJson.decodeFromString<ReviewStatus>(events(kernel, client).single { it.kind == "plan_accepted" }.payload)
                    assertEquals(parent, payload.parentPlan)
                    assertTrue(events(kernel, client).all { it.clientId == client.binding.clientId && !it.payload.contains(client.adapter.token) })
                }
                assertEquals(0, provider.executed.size)
            }
        }
        val fresh = OwnerReviewRouteRegistry(f.namespace.ownerId)
        assertNull(fresh.resolve(assemblies.first().plan.binding, assemblies.first().plan.request))
        KernelService.open(database, provider, config, planAuthority = fresh, routingAuthority = fresh,
            preparedInputObserver = provider.observer, resultPolicy = provider.policy).use { kernel ->
            accepted.zip(assemblies).forEach { (pair, assembly) ->
                val (client, parent) = pair
                assertEquals(parent.planId, kernel.submitPlan(client.client, assembly.plan.request).planId)
                awaitResults(kernel, client, parent)
            }
            assertEquals(5, model.compilationIds.size)
            assertEquals(5, provider.executed.size); assertEquals(5, provider.attempts.size)
            assertEquals(10, provider.policyCalls.size)
            assemblies.forEach { assembly -> assembly.plan.slots.forEach { slot ->
                val key = assembly.plan.binding to slot.input.analysisKey()
                assertContains(provider.policyCalls, key)
                assertEquals(assembly.plan.binding, prep.policyRecords.getValue(key).binding)
                assertEquals(assembly.plan.binding, prep.executionRecords.getValue(key).binding)
            } }
            val (a, pa) = accepted[0]; val (b, pb) = accepted[1]
            val ea = events(kernel, a); val eb = events(kernel, b)
            assertEquals(11, ea.size); assertEquals(11, eb.size)
            assertTrue(ea.map { it.eventId }.toSet().intersect(eb.map { it.eventId }.toSet()).isEmpty())
            assertTrue(pa.slots.map { it.reviewId }.toSet().intersect(pb.slots.map { it.reviewId }.toSet()).isEmpty())
            assertKernelCode("plan_not_found") { kernel.planStatus(b.client, pa.planId) }
            assertKernelCode("event_not_found") { acknowledge(kernel, a, eb.single { it.kind == "plan_accepted" }) }
            val parentA = ea.single { it.kind == "plan_accepted" }
            deliver(kernel, a, ea.filterNot { it.eventId == parentA.eventId })
            assertEquals(setOf("insertion_missing:${parentA.eventId}", "model_observation_missing:${parentA.eventId}"),
                missing(deniedJointCompletion(kernel, a)).toSet())
            acknowledge(kernel, a, parentA)
            assertEquals(listOf("model_observation_missing:${parentA.eventId}"), missing(kernel.prepareGate(a.client)))
            observe(kernel, a, parentA)
            assertTrue(missing(deniedJointCompletion(kernel, a)).isEmpty())
            assertEquals(2 * eb.size, missing(deniedJointCompletion(kernel, b)).size)
            deliver(kernel, b)
            assertTrue(missing(deniedJointCompletion(kernel, b)).isEmpty())
        }
    }

    @Test fun unenrolledAdapterOrStaleExactBindingCannotAcceptAnyPrefixAndCorrectWholeRetryStartsAtOne() {
        for (kind in listOf("token", "session", "generation")) {
            val f = fixture(); val capture = f.capture(); val model = f.import(capture)
            val stale = when (kind) {
                "session" -> f.binding("b").copy(sessionId = "not-enrolled")
                "generation" -> f.binding("b").copy(generation = 2)
                else -> f.binding("b")
            }
            val a = assemble(f, capture, model, "a"); val wrongB = assemble(f, capture, model, "b", binding = stale)
            val b = assemble(f, capture, model, "b")
            val provider = ArchiveProvider(listOf(a, wrongB, b)); val registry = OwnerReviewRouteRegistry(f.namespace.ownerId)
            val database = f.work.resolve("kernel-reject-$kind")
            val clients = KernelService.open(database, provider, config.copy(workerCount = 0), planAuthority = registry,
                routingAuthority = registry, preparedInputObserver = provider.observer, resultPolicy = provider.policy).use { kernel ->
                val ca = kernel.registerClient(f.binding("a")); val cb = kernel.registerClient(f.binding("b"))
                val ha = OwnerRouteRecipient(a, ca.adapter)
                val hb = OwnerRouteRecipient(wrongB, if (kind == "token") cb.adapter.copy(token = Digests.sha256("not-b-enrollment")) else cb.adapter)
                registry.register(ha, listOf(ha, hb)) // Retention does not pretend to authenticate an enrollment.
                assertKernelCode("routing_recipient_unauthorized") { kernel.submitPlan(ca.client, a.plan.request) }
                assertTrue(events(kernel, ca).isEmpty()); assertTrue(events(kernel, cb).isEmpty())
                assertKernelCode("plan_authority_unavailable") { kernel.submitPlan(cb.client, b.plan.request) }
                assertEquals(0, provider.executed.size)
                listOf(ca, cb)
            }
            val corrected = OwnerReviewRouteRegistry(f.namespace.ownerId)
            KernelService.open(database, provider, config.copy(workerCount = 0), planAuthority = corrected,
                routingAuthority = corrected, preparedInputObserver = provider.observer, resultPolicy = provider.policy).use { kernel ->
                val ha = OwnerRouteRecipient(a, clients[0].adapter); val hb = OwnerRouteRecipient(b, clients[1].adapter)
                corrected.register(ha, listOf(ha, hb))
                val pa = kernel.submitPlan(clients[0].client, a.plan.request)
                val pb = kernel.submitPlan(clients[1].client, b.plan.request)
                assertEquals(5, pa.slots.size); assertEquals(5, pb.slots.size)
                clients.forEach { assertEquals((1L..6L).toList(), events(kernel, it).map { event -> event.sequence }) }
            }
        }
    }

    @Test fun finalRecipientPolicyRevokedAfterWholeArchiveStagingRollsBackEveryLocalAcceptance() {
        val f = fixture(); val capture = f.capture(); val model = f.import(capture)
        val prepA = TestPreparations(); val prepB = TestPreparations()
        val a = assemble(f, capture, model, "a", prep = prepA); val b = assemble(f, capture, model, "b", prep = prepB)
        val provider = ArchiveProvider(listOf(a, b)); val registry = OwnerReviewRouteRegistry(f.namespace.ownerId)
        KernelService.open(f.work.resolve("kernel-late-revocation"), provider, config.copy(workerCount = 0), planAuthority = registry,
            routingAuthority = registry, preparedInputObserver = provider.observer, resultPolicy = provider.policy).use { kernel ->
            val ca = kernel.registerClient(a.plan.binding); val cb = kernel.registerClient(b.plan.binding)
            val ha = OwnerRouteRecipient(a, ca.adapter); val hb = OwnerRouteRecipient(b, cb.adapter)
            registry.register(ha, listOf(ha, hb))
            val retains = AtomicInteger(); val saved = prepB.policyRevision
            // Rounds: resolve, authorize, kernel pre-stage verification, kernel POST-stage
            // verification. Revoke B only in that last A callback, not during initial resolution.
            val postStage = 3 * a.plan.slots.size + 1
            prepA.afterPolicyRetain = { if (retains.incrementAndGet() == postStage) prepB.policyRevision = Digests.sha256("b-revoked-after-staging") }
            assertKernelCode("routing_preparation_unavailable") { kernel.submitPlan(ca.client, a.plan.request) }
            assertTrue(retains.get() >= postStage)
            assertTrue(events(kernel, ca).isEmpty()); assertTrue(events(kernel, cb).isEmpty())
            assertEquals(0, provider.executed.size)
            assertKernelCode("plan_authority_unavailable") { kernel.submitPlan(cb.client, b.plan.request) }
            prepA.afterPolicyRetain = {}; prepB.policyRevision = saved
            val pa = kernel.submitPlan(ca.client, a.plan.request); val pb = kernel.submitPlan(cb.client, b.plan.request)
            assertEquals(a.planDigest, pa.planDigest); assertEquals(b.planDigest, pb.planDigest)
            listOf(ca, cb).forEach { assertEquals((1L..6L).toList(), events(kernel, it).map { event -> event.sequence }) }
            b.plan.slots.forEach { assertEquals(saved, prepB.policyRecords.getValue(b.plan.binding to it.input.analysisKey()).revisionDigest) }
        }
    }

    @Test fun cancellingOriginDuringSharedExecutionNeverCancelsPeersWorkOrConsumesItsDelivery() {
        val f = fixture(); val capture = f.capture(); val model = f.import(capture)
        val assemblies = listOf("a", "b").map { assemble(f, capture, model, it) }
        val registry = OwnerReviewRouteRegistry(f.namespace.ownerId); val provider = ArchiveProvider(assemblies)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val first = AtomicInteger()
        provider.beforeAnalyze = { token ->
            if (first.getAndIncrement() == 0) { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
            assertFalse(token.isCancelled(), "The independently subscribed peer still requires this shared work")
        }
        KernelService.open(f.work.resolve("kernel-cancellation"), provider, config, planAuthority = registry,
            routingAuthority = registry, preparedInputObserver = provider.observer, resultPolicy = provider.policy).use { kernel ->
            try {
                val accepted = enrollAndRoute(kernel, registry, assemblies)
                val (a, pa) = accepted[0]; val (b, pb) = accepted[1]
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                // All complete local acceptance payloads are already visible before the blocked
                // first execution can finish; neither member is a deferred local prefix.
                accepted.forEach { (client, parent) ->
                    val payload = ContractJson.decodeFromString<ReviewStatus>(events(kernel, client).single { it.kind == "plan_accepted" }.payload)
                    assertEquals(parent.slots.map { it.reviewId }, payload.parentPlan!!.slots.map { it.reviewId })
                }
                assertEquals("cancelled", kernel.cancelPlan(a.client, pa.planId).state)
                assertEquals("pending", kernel.planStatus(b.client, pb.planId).state)
                assertTrue(pa.slots.all { kernel.status(a.client, it.reviewId).cancelled })
                release.countDown(); awaitResults(kernel, b, pb)
                assertEquals(5, provider.executed.size); assertEquals(5, provider.attempts.size)
                assertEquals("cancelled", kernel.planStatus(a.client, pa.planId).state)
                deliver(kernel, a)
                assertTrue(missing(deniedJointCompletion(kernel, a)).isEmpty())
                assertEquals(2 * events(kernel, b).size, missing(deniedJointCompletion(kernel, b)).size)
                deliver(kernel, b)
                assertTrue(missing(deniedJointCompletion(kernel, b)).isEmpty())
            } finally { release.countDown() }
        }
    }

    @Test fun routingPreservesFullAndIncrementalVectorsIncludingUnaffectedRemovedAndRetargetedContexts() {
        for (kind in listOf("resource", "removed", "retargeted")) {
            val f = fixture(); val beforeCapture = f.capture(); val before = f.import(beforeCapture)
            val resource = "lib/src/commonMain/resources/message.txt"
            val capture = when (kind) {
                "resource" -> f.capture(f.files() + (resource to "captured incremental change\n"))
                "removed" -> f.capture(f.files() - "lib/src/desktopMain/kotlin/Desktop.kt")
                else -> beforeCapture
            }
            val graph = f.graph(capture).let { graph -> when (kind) {
                "removed" -> graph.copy(compilations = graph.compilations.filterNot { it.id == ":lib|desktop|main" },
                    sourceSets = graph.sourceSets.filterNot { it.id == ":lib|desktopMain" })
                "retargeted" -> graph.copy(compilations = graph.compilations.map {
                    if (it.id == ":lib|jvm|main") it.copy(defaultSourceSetId = ":lib|commonMain") else it
                })
                else -> graph
            } }
            val after = f.import(capture, graph)
            val full = assemble(f, capture, after, "full")
            val incremental = assemble(f, capture, after, "incremental", before = before)
            assertEquals(after.compilationIds.size, full.plan.slots.size)
            if (kind == "resource") {
                assertEquals(listOf(resource), incremental.provenance.impact.changedFiles)
                assertFalse(incremental.provenance.impact.obligations.any { it.compilationId == ":independent|jvm|main" })
                assertTrue(incremental.provenance.slots.any { it.compilationId == ":independent|jvm|main" &&
                    "unaffected_context_rechecked_without_reuse_proof" in it.explanations })
                assertEquals(full.plan.slots.map { it.identity }, incremental.plan.slots.map { it.identity })
            } else {
                assertEquals(after.compilationIds.size + 1, incremental.plan.slots.size)
                val retired = incremental.plan.slots.single { it.state == "unsupported" }
                assertContains(retired.reasons, if (kind == "removed") "required_compilation_removed_unresolved" else "required_context_retargeted_unresolved")
                assertNull(incremental.provenance.slots.single { it.slotId == retired.slotId }.executionIdentity)
            }
            val assemblies = listOf(full, incremental)
            val provider = ArchiveProvider(assemblies); val registry = OwnerReviewRouteRegistry(f.namespace.ownerId)
            val expectedExecutions = assemblies.flatMap { it.plan.slots }.filter { it.state == "pending" }.map { it.input.analysisKey() }.distinct().size
            KernelService.open(f.work.resolve("kernel-vector-$kind"), provider, config, planAuthority = registry,
                routingAuthority = registry, preparedInputObserver = provider.observer, resultPolicy = provider.policy).use { kernel ->
                val accepted = enrollAndRoute(kernel, registry, assemblies)
                accepted.zip(assemblies).forEach { (pair, assembly) ->
                    val (client, parent) = pair
                    assertEquals(assembly.planDigest, parent.planDigest)
                    assertEquals(assembly.plan.slots.map { it.slotId }, parent.slots.map { it.slotId })
                    assertTrue(parent.slots.all { it.requiredRuleIds == f.inventory.ruleIds })
                    awaitResults(kernel, client, parent); deliver(kernel, client)
                    assertTrue(missing(deniedJointCompletion(kernel, client)).isEmpty())
                }
                assertEquals(expectedExecutions, provider.executed.size)
                provider.executed.forEach { assertEquals(after.snapshot, it.snapshot); assertEquals(f.inventory.ruleIds, it.requiredRuleIds) }
                val unsupported = incremental.plan.slots.filter { it.state == "unsupported" }.map { it.input.context.scope }.toSet()
                assertFalse(provider.executed.any { it.context.scope in unsupported })
            }
        }
    }

    @Test fun newlyCapturedCombinedLocalReviewCannotEraseRoutedHistoryOrInventJointCompletion() {
        val f = fixture(); val oldCapture = f.capture(); val before = f.import(oldCapture)
        val old = listOf("a", "b").map { assemble(f, oldCapture, before, it) }
        val combinedCapture = f.capture(f.files() + mapOf("lib/src/commonMain/kotlin/Common.kt" to "fun commonValue() = 2\n",
            "independent/src/jvmMain/kotlin/Independent.kt" to "fun independentValue() = 3\n"))
        val combined = f.import(combinedCapture)
        val fresh = assemble(f, combinedCapture, combined, "a", request = f.request("combined"), before = before)
        assertEquals(combined.compilationIds.size, fresh.plan.slots.size)
        assertTrue(fresh.plan.slots.all { it.input.snapshot == combined.snapshot })
        assertTrue(old.flatMap { it.plan.slots }.map { it.input.analysisKey() }.toSet()
            .intersect(fresh.plan.slots.map { it.input.analysisKey() }.toSet()).isEmpty())
        val provider = ArchiveProvider(old + fresh); val route = OwnerReviewRouteRegistry(f.namespace.ownerId)
        val database = f.work.resolve("kernel-combined-history")
        val accepted = KernelService.open(database, provider, config, planAuthority = route, routingAuthority = route,
            preparedInputObserver = provider.observer, resultPolicy = provider.policy).use { kernel ->
            enrollAndRoute(kernel, route, old).also { pairs -> pairs.forEach { (client, parent) ->
                awaitResults(kernel, client, parent); deliver(kernel, client)
                assertTrue(missing(deniedJointCompletion(kernel, client)).isEmpty())
                kernel.cancelPlan(client.client, parent.planId); deliver(kernel, client)
            } }
        }
        val local = OwnerReviewPlanRegistry(f.namespace.ownerId).also { it.register(fresh) }
        KernelService.open(database, provider, config, planAuthority = local,
            preparedInputObserver = provider.observer, resultPolicy = provider.policy).use { kernel ->
            val client = accepted.first().first
            val parent = kernel.submitPlan(client.client, fresh.plan.request)
            assertEquals(fresh.planDigest, parent.planDigest); awaitResults(kernel, client, parent); deliver(kernel, client)
            assertEquals(10, provider.executed.size, "The fresh combined snapshot still needs its entire own context vector")
            val cut = deniedJointCompletion(kernel, client)
            assertTrue(missing(cut).isEmpty()); assertEquals(combined.snapshot.snapshotId, cut.sourceSnapshotId)
            // This history deliberately never had qualified provider success. The explicit
            // joint-authority denial is asserted separately, not inferred from that other failure.
            deniedJointCompletion(kernel, accepted.last().first)
        }
    }
}

package dev.ktchecker.reviewcontroller

import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.*
import dev.ktchecker.projectmodel.VerifiedProjectModel
import dev.ktchecker.snapshot.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * Real SnapshotStore disjoint rebase, importer/assembler capabilities, kernel raw-work cache,
 * SQLite/WAL, archives, routing, worker and joint barrier. Model/approval/configuration/merge,
 * provider semantics and final delivery are SYNTHETIC. Only two fake unit catalog rows are used;
 * true coverage flags below are positive protocol controls, NOT actual provider qualification.
 *
 * Full versus incremental means distinct whole-input requests with different verified hints.
 * No compiler, Gradle, compiler incremental cache, physical live-worktree or host process runs.
 * The source half uses the real overlay fence; the remaining composite authority is a test SPI,
 * not a production implementation or a proof of full/incremental compiler equivalence.
 */
class SnapshotCacheIntegratedHistoryTest {
    @TempDir lateinit var temp: Path
    private val config = KernelConfig(workerCount = 1, globalParallelism = 1, perWorktreeParallelism = 1,
        maxQueuedWork = 24, maxPendingPerClient = 32, maxRetainedReviews = 64,
        maxArchiveBytes = 8L * 1024 * 1024, maxSnapshotBytes = 128L * 1024, maxSnapshotFiles = 32,
        maxInputBytes = 512 * 1024, maxResultBytes = 32 * 1024,
        maxEventPageSize = 8, maxEventPageBytes = 32 * 1024,
        heartbeatMillis = 100, leaseMillis = 1_000, pollMillis = 5, analysisTimeoutMillis = 60_000)
    private val limits = IntegratedCompletionLimits(maxMembers = 2, maxRoutes = 4, maxReviews = 40,
        maxParents = 8, maxEvents = 88, maxHistoryBytes = 4L * 1024 * 1024,
        maxCoveragePairs = 80, maxAuditBytes = 512 * 1024, maxCuts = 3)

    private companion object {
        const val COMMON = "lib/src/commonMain/kotlin/Common.kt"
        const val RESOURCE = "lib/src/commonMain/resources/message.txt"
        const val APP = "app/src/jvmMain/kotlin/App.kt"
        const val NEW_COMMON = "fun commonValue() = 2\n"
        const val REQUIRE_ONE = "synthetic consumer requires commonValue=1\n"
        const val ACCEPT_TWO = "synthetic consumer accepts commonValue=2\n"
        const val COMPLETION = "synthetic-snapshot-cache-completion"
    }

    @Test @Timeout(value = 90, unit = TimeUnit.SECONDS)
    fun warmedCleanCacheCannotEraseCombinedOnlyFindingInUnchangedIncrementalConsumer() = runHistory(defect = true)

    @Test @Timeout(value = 90, unit = TimeUnit.SECONDS)
    fun cleanCombinedControlRequiresFreshWholeVectorsAndPeersOwnObservationBeforeOneEffect() = runHistory(defect = false)

    private fun runHistory(defect: Boolean) {
        val f = ControllerFixtures(temp.resolve("owner"))
        val baseFiles = f.files()
        val base = f.capture(baseFiles)
        val baseModel = f.import(base)
        val baseHead = f.store.currentHead(f.identity)
        val prep = SyntheticPreparations()
        val assembler = prep.assembler(f)
        val provider = WholeArchiveProvider(f.inventory.ruleIds)
        val routes = OwnerReviewRouteRegistry(f.namespace.ownerId)
        val database = f.work.resolve("kernel")
        val readback = Readback(database)
        val batches = mutableListOf<Batch>()
        KernelService.open(database, provider, config, planAuthority = routes, routingAuthority = routes,
            preparedInputObserver = provider.observer, resultPolicy = prep.policy).use { service ->
            try {
                val clients = listOf("a", "b").map { service.registerClient(f.binding(it)) }
                fun assemble(label: String, capture: ControllerFixtures.Capture, model: VerifiedProjectModel,
                             before: (Int) -> VerifiedProjectModel?): List<AssembledReviewPlan> = clients.mapIndexed { index, client ->
                    assembler.assemble(client.binding, f.request("$label-${client.binding.clientId}"), before(index), model,
                        capture.materialization(model), listOf(client.binding.clientId))
                }
                fun admit(assemblies: List<AssembledReviewPlan>, files: Map<String, String>): Batch {
                    provider.expect(assemblies, files)
                    val batch = route(service, routes, clients, assemblies, batches)
                    batches += batch
                    return batch
                }

                val initial = admit(assemble("initial", base, baseModel) { null }, baseFiles)
                awaitBatch(service, clients, initial, expectFinding = false)
                clients.forEach { deliver(service, it, events(service, it), observe = true) }
                assertEquals(5, provider.calls.get())
                assertEquals(5L, readback.counts().getValue("work"))
                val initialEvents = clients.map { events(service, it) }

                // New routed obligations, not an idempotent submission retry. Exact completed
                // input reuse must skip provider work but never borrow either recipient's events.
                val cached = admit(assemble("cache-hit", base, baseModel) { null }, baseFiles)
                assertEquals(initial.members.map { it.assembly.plan.slots.map { s -> s.input } },
                    cached.members.map { it.assembly.plan.slots.map { s -> s.input } })
                assertTrue(cached.parents.all { it.state == "complete" })
                awaitBatch(service, clients, cached, expectFinding = false)
                assertEquals(5, provider.calls.get())
                assertEquals(5, provider.attempts.size)
                assertEquals(5L, readback.counts().getValue("work"))
                assertEquals(20L, readback.counts().getValue("reviews"))
                assertTrue(initial.children.map { it.reviewId }.toSet().intersect(cached.children.map { it.reviewId }.toSet()).isEmpty())
                clients.forEachIndexed { index, client ->
                    val all = events(service, client)
                    assertEquals(22, all.size)
                    assertEquals(initialEvents[index], all.take(11))
                    assertTrue(all.take(11).map { it.eventId }.toSet().intersect(all.drop(11).map { it.eventId }.toSet()).isEmpty())
                    deliver(service, client, all, observe = true)
                }

                // Two producers start at the SAME real base revision. The right producer is
                // submitted only after left has committed; its mode must actually perform rebase.
                val leftCommit = f.store.sealOverlay(OverlayRequest(f.identity, "producer-left", "session-left", "source-left",
                    base.snapshot.snapshotId, baseHead.headRevision,
                    listOf(SnapshotChange.PutFile(SnapshotPath(path = COMMON), ImmutableBlob.utf8(NEW_COMMON))),
                    mode = CommitMode.STRICT_HEAD))
                val leftFiles = baseFiles + (COMMON to NEW_COMMON)
                val left = materialize(f, leftCommit, "left", leftFiles)
                val leftModel = f.import(left)
                val leftBatch = admit(assemble("left", left, leftModel) { baseModel }, leftFiles)
                awaitBatch(service, clients, leftBatch, expectFinding = false)
                assertEquals(10, provider.calls.get())
                clients.forEach { deliver(service, it, events(service, it), observe = true) }
                val historicalResults = batches.flatMap { it.children }.associate { child ->
                    child.reviewId to service.status(clients[child.client].client, child.reviewId)
                }

                val rightText = if (defect) REQUIRE_ONE else ACCEPT_TWO
                val peerStore = SnapshotStore(f.store.root, f.identity.ownerId)
                assertEquals(leftCommit.snapshotId, peerStore.currentHead(f.identity).headSnapshotId)
                val combinedCommit = peerStore.sealOverlay(OverlayRequest(f.identity, "producer-right", "session-right", "source-right",
                    base.snapshot.snapshotId, baseHead.headRevision,
                    listOf(SnapshotChange.PutFile(SnapshotPath(path = RESOURCE), ImmutableBlob.utf8(rightText))),
                    mode = CommitMode.REBASE_DISJOINT))
                assertEquals(base.snapshot.snapshotId, combinedCommit.manifest.producerBaseSnapshotId)
                assertEquals(leftCommit.snapshotId, combinedCommit.manifest.parentSnapshotId)
                assertEquals(baseHead.headRevision + 2, combinedCommit.headRevision)
                assertEquals(listOf(RESOURCE), combinedCommit.manifest.delta.map { it.path })
                assertEquals(combinedCommit.snapshotId, f.store.currentHead(f.identity).headSnapshotId)
                val combinedFiles = baseFiles + mapOf(COMMON to NEW_COMMON, RESOURCE to rightText)
                val combined = materialize(f, combinedCommit, "combined", combinedFiles)
                val model = f.import(combined)
                assertEquals(base.snapshot.files.single { it.path == APP }, combined.snapshot.files.single { it.path == APP })
                f.store.withVerifiedSnapshot(f.identity, base.snapshot.snapshotId, requireLiveMatch = false, requireNoOmissions = true) {
                    assertFalse(it.valid); assertEquals("stale_snapshot", it.reason)
                }

                val finalAssemblies = assemble("combined", combined, model) { index -> if (index == 0) null else leftModel }
                val full = finalAssemblies[0]
                val incremental = finalAssemblies[1]
                assertEquals(5, model.compilationIds.size)
                assertEquals(full.plan.slots.map { it.identity }, incremental.plan.slots.map { it.identity })
                assertTrue(finalAssemblies.all { it.plan.slots.size == 5 && it.plan.slots.all { slot -> slot.state == "pending" } })
                assertEquals(listOf(RESOURCE), incremental.provenance.impact.changedFiles)
                assertTrue(incremental.provenance.impact.obligations.any { it.compilationId == ":app|jvm|main" })
                assertFalse(incremental.provenance.impact.obligations.any { it.compilationId == ":independent|jvm|main" })
                assertTrue(incremental.provenance.slots.any { it.compilationId == ":independent|jvm|main" &&
                    "unaffected_context_rechecked_without_reuse_proof" in it.explanations })
                full.plan.slots.forEach { assertNull(it.input.baselineSnapshotId); assertEquals(combinedFiles.keys.sorted(), it.input.changedPaths) }
                incremental.plan.slots.forEach {
                    assertEquals(left.snapshot.snapshotId, it.input.baselineSnapshotId)
                    assertEquals(listOf(RESOURCE), it.input.changedPaths)
                    assertEquals(combined.snapshot, it.input.snapshot)
                }
                val appSlot = incremental.plan.slots.single { it.input.context.scope.module == ":app" }
                assertEquals(listOf(APP), appSlot.input.context.compilationFiles)
                assertFalse(APP in appSlot.input.changedPaths || COMMON in appSlot.input.changedPaths)
                assertTrue(full.plan.slots.map { it.input.analysisKey() }.toSet()
                    .intersect(incremental.plan.slots.map { it.input.analysisKey() }.toSet()).isEmpty())
                val oldKeys = batches.flatMap { it.children }.map { it.input.analysisKey() }.toSet()
                assertTrue(finalAssemblies.flatMap { it.plan.slots }.none { it.input.analysisKey() in oldKeys })

                provider.parkSnapshot = combined.snapshot.snapshotId
                val finalBatch = admit(finalAssemblies, combinedFiles)
                assertTrue(provider.entered.await(10, TimeUnit.SECONDS), "A fresh combined whole-input execution must actually enter")
                assertEquals(11, provider.calls.get())
                assertTrue(finalBatch.children.all { service.status(clients[it.client].client, it.reviewId).result == null })
                val fence = OverlayFence(f, combinedCommit, finalBatch.members, batches.toList())
                val actionCalls = AtomicInteger()
                val action = object : IntegratedCompletionAction {
                    override val identity = Digests.sha256("synthetic-snapshot-cache-effect-v1")
                    override fun perform(permit: IntegratedCompletionPermit): IntegratedActionReceipt {
                        actionCalls.incrementAndGet() // First: even an assertion caught by the barrier counts as entry.
                        assertTrue(fence.held)
                        assertEquals(listOf("action_claimed", "action_claimed"), readback.gates())
                        assertEquals(combined.snapshot.snapshotId, permit.scope.integrationSnapshotId)
                        assertEquals(clients.map { it.binding }, permit.scope.members.map { it.binding })
                        return IntegratedActionReceipt(permit.cutId, permit.scopeDigest,
                            Digests.sha256("synthetic-snapshot-cache-effect:${permit.cutId}:${permit.scopeDigest}"))
                    }
                }
                val joint = OwnerIntegratedCompletionRegistry(f.namespace.ownerId)
                joint.register(COMPLETION, finalBatch.members.first(), finalBatch.members.first(), finalBatch.members, fence, action)
                val barrier = IntegratedCompletionBarrier(service, joint, limits)
                val pending = barrier.prepare(clients[0].client, COMPLETION)
                assertFalse(pending.ready)
                finalBatch.children.forEach { assertContains(pending.reasons, "review_pending:${it.reviewId}:${clients[it.client].binding.clientId}") }
                assertAudit(readback, pending, fence.requests.last())
                denied(service, clients[0], barrier, pending, readback, actionCalls)

                provider.release.countDown()
                awaitBatch(service, clients, finalBatch, expectFinding = defect)
                assertEquals(20, provider.calls.get(), "Both full and incremental combined vectors need five fresh exact-key executions")
                assertEquals(20, provider.attempts.size)
                val expectedKeys = batches.flatMap { it.children }.map { it.input.analysisKey() }.toSet()
                assertEquals(20, expectedKeys.size)
                assertEquals(expectedKeys, provider.attempts.map { it.original.analysisKey() }.toSet())
                assertEquals(40, prep.policyCalls.size, "Every cached/shared review still evaluates its own retained consumer policy")
                historicalResults.forEach { (id, expected) -> assertEquals(expected, service.status(clients.single { it.binding.clientId == expected.clientId }.client, id)) }
                finalBatch.children.filter { it.client == 0 }.forEach { a ->
                    val b = finalBatch.children.single { it.client == 1 && it.slotId == a.slotId }
                    val ar = assertNotNull(service.status(clients[0].client, a.reviewId).result)
                    val br = assertNotNull(service.status(clients[1].client, b.reviewId).result)
                    assertEquals(ar.copy(coverage = ar.coverage.map { it.copy(certificateId = null) }),
                        br.copy(coverage = br.coverage.map { it.copy(certificateId = null) }))
                    assertTrue(ar.coverage.map { it.certificateId }.toSet().intersect(br.coverage.map { it.certificateId }.toSet()).isEmpty())
                }

                val allEvents = clients.map { events(service, it) }
                assertEvents(service, clients, batches, allEvents)
                assertEquals(88, allEvents.sumOf { it.size })
                assertEquals(40L, readback.counts().getValue("reviews"))
                assertEquals(20L, readback.counts().getValue("work"))
                assertEquals(8L, readback.counts().getValue("parent_plans"))
                assertEquals(4L, readback.counts().getValue("routing_batches"))
                deliver(service, clients[0], allEvents[0], observe = true)
                deliver(service, clients[1], allEvents[1], observe = false)
                val peerFinal = allEvents[1].filter { it.sourceSnapshotId == combined.snapshot.snapshotId }
                assertEquals(11, peerFinal.size)
                val fingerprint = Digests.sha256("synthetic-combined-unchanged-consumer:${Digests.sha256(baseFiles.getValue(APP))}")
                val open = if (defect) clients.map { "open_finding:$fingerprint:${it.binding.clientId}" }.toSet() else emptySet()
                val inserted = barrier.prepare(clients[0].client, COMPLETION)
                assertEquals(open + peerFinal.map { "joint_model_observation_missing:b:${it.eventId}" }, inserted.reasons.toSet())
                val insertedAudit = assertAudit(readback, inserted, fence.requests.last())
                val insertedRequest = fence.requests.last()
                denied(service, clients[0], barrier, inserted, readback, actionCalls)

                deliver(service, clients[1], peerFinal, observe = true)
                val cut = barrier.prepare(clients[0].client, COMPLETION)
                assertEquals(insertedRequest, fence.requests.last(), "Observation does not replace or trim the combined history")
                val preparedAudit = assertAudit(readback, cut, fence.requests.last())
                assertEquals(insertedAudit.getValue("request"), preparedAudit.getValue("request"))
                assertEquals(open, cut.reasons.toSet())
                val counts = readback.counts()
                if (defect) {
                    assertFalse(cut.ready)
                    denied(service, clients[0], barrier, cut, readback, actionCalls)
                } else {
                    assertTrue(cut.ready, cut.reasons.toString())
                    val completed = barrier.complete(clients[0].client, cut.cutId)
                    assertTrue(completed.allowed, completed.reasons.toString()); assertTrue(completed.actionPerformed)
                    assertEquals("complete", completed.state); assertEquals(1, actionCalls.get())
                    assertEquals(listOf("complete", "complete"), readback.gates())
                    val receipt = IntegratedActionReceipt(cut.cutId, cut.scopeDigest,
                        Digests.sha256("synthetic-snapshot-cache-effect:${cut.cutId}:${cut.scopeDigest}"))
                    assertEquals(JsonObject(preparedAudit + mapOf("state" to JsonPrimitive("complete"),
                        "receipt" to ContractJson.parseToJsonElement(canonical(receipt)))), readback.audit(cut.cutId))
                }
                val retainedAudit = readback.audit(cut.cutId)
                val retainedGates = readback.gates()
                assertFalse(barrier.status(clients[0].client, cut.cutId).allowed)
                val replay = barrier.complete(clients[0].client, cut.cutId)
                assertFalse(replay.allowed); assertFalse(replay.actionPerformed)
                assertEquals(if (defect) 0 else 1, actionCalls.get())
                assertEquals(retainedAudit, readback.audit(cut.cutId)); assertEquals(retainedGates, readback.gates())
                assertEquals(counts, readback.counts())
                assertEquals(allEvents, clients.map { events(service, it) })
            } finally {
                // Release BEFORE KernelService.use closes the only owned worker, even on an
                // assertion/timeout. There are no extra actors/processes/foreign permits to stop.
                provider.release.countDown()
            }
        }
    }

    private fun materialize(f: ControllerFixtures, commit: SnapshotCommit, label: String, oracle: Map<String, String>): ControllerFixtures.Capture {
        val materialized = f.store.materialize(f.identity, commit.snapshotId, f.work.resolve("seam-$label"))
        val snapshot = materialized.manifest.toContractManifest()
        assertEquals(oracle.entries.sortedBy { it.key }.map { (path, bytes) ->
            SnapshotFile(path, Digests.sha256(bytes), bytes.toByteArray(Charsets.UTF_8).size.toLong())
        }, snapshot.files)
        oracle.forEach { (path, bytes) -> assertEquals(bytes, Files.readString(materialized.root.resolve(path))) }
        assertTrue(f.store.verifyMaterialization(materialized).valid)
        return ControllerFixtures.Capture(materialized.root, snapshot) { f.store.verifyMaterialization(materialized).valid }
    }

    private data class Child(val client: Int, val slotId: String, val input: AnalysisInput, val reviewId: String,
                             val request: SubmitRequest, val sequence: Long)
    private class Batch(val members: List<OwnerRouteRecipient>, val parents: List<ParentPlanStatus>, val children: List<Child>) {
        fun obligations(): List<IntegratedPatchObligation> = children.map { child ->
            IntegratedPatchObligation(members[child.client].assembly.plan.binding.clientId, "review", child.reviewId,
                child.request.patchId, Digests.sha256(canonical(child.request)), child.input.analysisKey(), child.input.snapshot.snapshotId, child.sequence)
        } + parents.mapIndexed { index, parent ->
            val plan = members[index].assembly.plan
            IntegratedPatchObligation(plan.binding.clientId, "parent_plan", parent.planId, plan.request.patchId,
                Digests.sha256(canonical(plan.request)), parent.planDigest, parent.sourceSnapshotId,
                children.filter { it.client == index }.maxOf { it.sequence })
        }
    }

    private fun route(service: KernelService, registry: OwnerReviewRouteRegistry, clients: List<ClientRegistration>,
                      assemblies: List<AssembledReviewPlan>, history: List<Batch>): Batch {
        val members = assemblies.zip(clients).map { (assembly, client) -> OwnerRouteRecipient(assembly, client.adapter) }
        val request = registry.register(members.first(), members)
        val origin = service.submitPlan(clients[0].client, request)
        val parents = listOf(origin, service.submitPlan(clients[1].client, assemblies[1].plan.request))
        val children = parents.flatMapIndexed { index, parent ->
            val plan = assemblies[index].plan
            assertEquals(assemblies[index].planDigest, parent.planDigest)
            assertEquals(plan.slots.map { it.slotId }, parent.slots.map { it.slotId })
            parent.slots.mapIndexed { slotIndex, slot ->
                val input = plan.slots[slotIndex].input
                val sequence = history.sumOf { batch -> batch.children.count { it.client == index }.toLong() } + slotIndex + 1
                val identity = Digests.sha256(canonical(listOf(parent.planId, slot.slotId)))
                val childRequest = SubmitRequest("plan-child:$identity", "plan-child:$identity", input)
                val status = service.status(clients[index].client, slot.reviewId)
                assertEquals(sequence, status.sequence); assertEquals(childRequest.patchId, status.patchId)
                assertEquals(input.snapshot.snapshotId, status.sourceSnapshotId)
                Child(index, slot.slotId, input, slot.reviewId, childRequest, sequence)
            }
        }
        return Batch(members, parents, children)
    }

    private fun awaitBatch(service: KernelService, clients: List<ClientRegistration>, batch: Batch, expectFinding: Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        batch.children.forEach { child ->
            var status = service.status(clients[child.client].client, child.reviewId)
            while (status.result == null && System.nanoTime() < deadline) {
                Thread.sleep(5); status = service.status(clients[child.client].client, child.reviewId)
            }
            assertEquals("complete", status.state, "Provider assertions/authorization failures are not the expected synthetic outcome: $status")
            assertFalse(status.cancelled); assertTrue(status.reasons.isEmpty())
            val result = assertNotNull(status.result)
            assertEquals("complete", result.status); assertTrue(result.reasons.isEmpty())
            assertEquals(child.input.requiredRuleIds, result.coverage.map { it.ruleId })
            val findingExpected = expectFinding && child.input.context.scope.module == ":app"
            assertEquals(if (findingExpected) 1 else 0, result.findings.size)
            if (findingExpected) {
                val finding = result.findings.single()
                val digest = child.input.snapshot.files.single { it.path == APP }.digest
                assertEquals(child.input.requiredRuleIds.first(), finding.ruleId)
                assertEquals(SourceLocation(APP, digest), finding.location)
                assertEquals(Digests.sha256("synthetic-combined-unchanged-consumer:$digest"), finding.fingerprint)
                assertEquals(child.input.context.scope, finding.context); assertEquals("error", finding.severity)
                assertEquals("open", finding.disposition)
            }
            result.coverage.forEach { coverage ->
                assertEquals(child.input.context.scope, coverage.context)
                assertEquals(if (findingExpected && coverage.ruleId == child.input.requiredRuleIds.first()) "findings" else "passed", coverage.state)
                assertEquals("executed", coverage.provenance); assertTrue(Digests.valid(assertNotNull(coverage.certificateId)))
                assertTrue(coverage.implemented && coverage.tested && coverage.qualified, "Synthetic positive protocol flags only")
            }
        }
        batch.parents.forEachIndexed { index, parent -> assertEquals("complete", service.planStatus(clients[index].client, parent.planId).state) }
    }

    private class SyntheticPreparations {
        private val executions = TestPreparations()
        private val retained = ConcurrentHashMap<Pair<ClientBinding, String>, CapturedPolicyIdentity>()
        val policyCalls = ConcurrentLinkedQueue<Pair<ClientBinding, String>>()
        fun assembler(f: ControllerFixtures) = OwnerPlanAssembler(f.inventory, OwnerPolicyPreparer { request ->
            val identity = CapturedPolicyIdentity(request.binding, assertNotNull(request.input.context.configurationDigest),
                Digests.sha256("synthetic-seam-policy-revision"))
            val activation = PlanActivation(identity.configurationDigest, request.input.requiredRuleIds.map {
                PlanRuleActivation(it, true, "synthetic_test_catalog_only", "positive_protocol_control_not_real_qualification")
            })
            CapturedPlanPolicy(request, identity, request.withActivation(activation)) { final ->
                retained[request.binding to final.analysisKey()] = identity; identity
            }
        }, OwnerExecutionPreparer(executions::execution))
        val policy = ResultPolicy.versioned("synthetic-snapshot-cache-policy/1") { binding, input, raw ->
            val key = binding to input.analysisKey()
            val identity = assertNotNull(retained[key], "Every consumer needs its own live retained exact-input policy")
            assertEquals(binding, identity.binding); assertEquals(input.context.configurationDigest, identity.configurationDigest)
            policyCalls += key
            raw
        }
    }

    private class ExpectedInput(val input: AnalysisInput, val files: Map<String, String>)
    private class Attempt(val original: AnalysisInput, val effective: AnalysisInput, val files: Map<String, String>, val verify: () -> Unit)
    private class WholeArchiveProvider(private val rules: List<String>) : AnalysisProvider {
        override val id = "unit-provider"
        override val supportedRuleIds = rules.toSet()
        private val originals = ConcurrentHashMap<String, ExpectedInput>()
        private val permits = ConcurrentHashMap<String, Attempt>()
        val attempts = ConcurrentLinkedQueue<Attempt>()
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val parked = AtomicBoolean()
        @Volatile var parkSnapshot: String? = null
        fun expect(assemblies: List<AssembledReviewPlan>, files: Map<String, String>) {
            assemblies.flatMap { it.plan.slots }.forEach { slot ->
                val expected = ExpectedInput(freezeInput(slot.input), files.toMap())
                originals.putIfAbsent(slot.input.analysisKey(), expected)?.let {
                    assertEquals(expected.input, it.input); assertEquals(expected.files, it.files)
                }
            }
        }
        val observer = PreparedInputObserver { original, effective, verifyFrozen ->
            val expected = assertNotNull(originals[original.analysisKey()])
            assertEquals(expected.input, original)
            assertNotEquals(original.materializedRoot, effective.materializedRoot)
            assertNotNull(effective.outputRoot); assertNull(effective.execution)
            assertEquals(original, effective.copy(materializedRoot = original.materializedRoot, outputRoot = original.outputRoot,
                context = effective.context.copy(classpath = original.context.classpath)))
            verifyFrozen()
            val attempt = Attempt(original, freezeInput(effective), expected.files, verifyFrozen)
            assertNull(permits.putIfAbsent(effective.analysisKey(), attempt))
            attempts += attempt
        }
        override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
            calls.incrementAndGet()
            val attempt = assertNotNull(permits.remove(input.analysisKey()), "Every execution needs its exact live archive permit")
            assertEquals(attempt.effective, input); attempt.verify()
            cancellation.throwIfCancelled()
            if (input.snapshot.snapshotId == parkSnapshot && parked.compareAndSet(false, true)) {
                entered.countDown(); assertTrue(release.await(30, TimeUnit.SECONDS), "Bounded combined execution was not released")
            }
            val actual = input.snapshot.files.associate { file ->
                val bytes = Files.readAllBytes(Path.of(input.materializedRoot).resolve(file.path))
                assertEquals(file.digest, Digests.sha256(bytes)); assertEquals(file.size, bytes.size.toLong())
                file.path to bytes.toString(Charsets.UTF_8)
            }
            assertEquals(attempt.files, actual, "The full independently prescribed tree, not changedPaths or selected sources, must reach the provider")
            assertEquals(rules, input.requiredRuleIds)
            assertTrue(input.snapshot.files.size > assertNotNull(input.context.compilationFiles).size)
            input.context.classpath.forEach { assertEquals(it.digest, Digests.sha256(Files.readAllBytes(Path.of(it.path)))) }
            cancellation.throwIfCancelled(); attempt.verify()
            // Intentionally tiny synthetic interaction, not Kotlin semantics or a compiler cache:
            // neither old clean snapshot has both bytes, and the reported consumer is UNCHANGED.
            val findings = if (actual.getValue(COMMON) == NEW_COMMON && actual.getValue(RESOURCE) == REQUIRE_ONE &&
                input.context.scope.module == ":app") {
                val digest = input.snapshot.files.single { it.path == APP }.digest
                listOf(Finding(rules.first(), "Synthetic combined-only consumer defect", "Two producer inputs disagree in the combined fixture",
                    "Synthetic seam oracle only", SourceLocation(APP, digest), Digests.sha256("synthetic-combined-unchanged-consumer:$digest"),
                    id, context = input.context.scope, severity = "error"))
            } else emptyList()
            return ProviderResult(id, "complete", findings, rules.map { rule ->
                Coverage(rule, input.context.scope, if (findings.any { it.ruleId == rule }) "findings" else "passed",
                    provenance = "executed", certificateId = Digests.sha256("synthetic-seam-certificate:${input.analysisKey()}:$rule"),
                    implemented = true, tested = true, qualified = true)
            })
        }
    }

    /** Same actual worktree for both clients; synthetic configuration/merge declarations are fixed
     * independently of requests. Actual Store lock protects the current integrated overlay through action. */
    private class OverlayFence(private val f: ControllerFixtures, private val combined: SnapshotCommit,
                               private val members: List<OwnerRouteRecipient>, history: List<Batch>) : OwnerIntegratedSnapshotFence {
        private val lock = ReentrantLock()
        private var sourceHeld = false
        val held get() = lock.isHeldByCurrentThread && sourceHeld
        override val identity = Digests.sha256("synthetic-seam-composite-fence-v1")
        val requests = mutableListOf<IntegratedFenceRequest>()
        private val obligations = history.flatMap { it.obligations() }
        private val proof = Digests.sha256(canonical(listOf("synthetic-model-and-review-dispositions-with-actual-overlay-chain",
            combined.snapshotId, assertNotNull(combined.manifest.parentSnapshotId), assertNotNull(combined.manifest.producerBaseSnapshotId))))
        private val dispositions = obligations.map {
            val digest = integratedObligationDigest(it)
            IntegratedPatchDisposition(digest, "incorporated", Digests.sha256("synthetic-review-disposition:$proof:$digest"))
        }
        override fun <T> withCurrentIntegratedSnapshot(assemblies: List<AssembledReviewPlan>, integration: AssembledReviewPlan,
            request: IntegratedFenceRequest, action: (IntegratedSnapshotCheck) -> T): T = lock.withLock {
            assertEquals(members.map { it.assembly }, assemblies); assertSame(members.first().assembly, integration)
            assertEquals(48, obligations.size)
            assertEquals(obligations.toSet(), request.historicalObligations.toSet())
            assertEquals(obligations.size, request.historicalObligations.size)
            assertEquals(4, request.routes.size)
            requests += ContractJson.decodeFromString<IntegratedFenceRequest>(canonical(request))
            f.store.withVerifiedSnapshot(f.identity, combined.snapshotId, requireLiveMatch = false, requireNoOmissions = true) { source ->
                assertTrue(source.valid); assertTrue(source.coherent); assertFalse(source.liveMatchCertified)
                assertEquals(combined.sourceEpoch, source.expectedSourceEpoch)
                assertEquals(combined.headRevision, source.head.headRevision)
                assertEquals(3, source.head.acceptedPatchCount)
                val check = IntegratedSnapshotCheck(true, true, combined.snapshotId, "synthetic-integration:${source.head.sourceEpoch}", proof,
                    members.map { member ->
                        val plan = member.assembly.plan
                        val input = plan.slots.last().input
                        IntegratedMemberSnapshotCheck(plan.binding, member.assembly.planDigest,
                            SnapshotCheck(true, combined.snapshotId, source.head.sourceEpoch, input.context.configurationDigest,
                                input.context.modelDigest, input.context.dependencyDigest, input.context.toolchainDigest,
                                "synthetic-model-policy:${f.configuration}", authoritative = true,
                                contextIdentities = plan.slots.map { assertNotNull(it.identity) }, sourceBindingDigest = plan.sourceBindingDigest,
                                generatedInputsDigest = plan.generatedInputsDigest))
                    }, dispositions)
                sourceHeld = true
                try { action(check) } finally { sourceHeld = false }
            }
        }
    }

    private fun events(service: KernelService, client: ClientRegistration): List<ReviewEvent> {
        val result = mutableListOf<ReviewEvent>()
        var cursor: String? = null
        repeat(16) { // At most44 retained events/client; seven/page, explicit limit below bound8.
            val page = service.events(client.client, cursor, limit = 7)
            result += page.events
            if (!page.hasMore) {
                assertEquals((1L..result.size.toLong()).toList(), result.map { it.sequence })
                return result
            }
            assertTrue(page.events.isNotEmpty()); assertNotEquals(cursor, page.cursor)
            cursor = page.cursor
        }
        fail("Bounded event pagination did not settle")
    }

    private fun deliver(service: KernelService, client: ClientRegistration, events: List<ReviewEvent>, observe: Boolean) {
        events.forEach { event ->
            assertEquals(client.binding.clientId, event.clientId); assertEquals(Digests.sha256(event.payload), event.payloadDigest)
            val digest = Digests.sha256("synthetic-seam-insertion:${event.clientId}:${event.payloadDigest}")
            service.acknowledge(client.adapter, listOf(event.eventId), ContextReceipt("ack-${event.eventId}", digest,
                "synthetic-item-${event.eventId}", Instant.EPOCH.toString()))
            if (observe) service.observe(client.adapter, listOf(event.eventId), ObservationReceipt("observe-${event.eventId}",
                "synthetic-model-request-${event.eventId}", listOf(digest)))
        }
    }

    private fun assertEvents(service: KernelService, clients: List<ClientRegistration>, history: List<Batch>, streams: List<List<ReviewEvent>>) {
        assertEquals(88, streams.flatten().map { it.eventId }.distinct().size)
        clients.forEachIndexed { index, client ->
            val events = streams[index]
            assertEquals(44, events.size); assertTrue(events.all { it.deliveryRequired && it.generation == client.binding.generation })
            history.flatMap { it.children }.filter { it.client == index }.forEach { child ->
                val own = events.filter { it.reviewId == child.reviewId && it.kind in setOf("accepted", "result") }
                assertEquals(setOf("accepted", "result"), own.map { it.kind }.toSet()); assertEquals(2, own.size)
                assertTrue(own.all { it.sourceSnapshotId == child.input.snapshot.snapshotId && it.patchId == child.request.patchId })
                val accepted = ContractJson.decodeFromString<ReviewStatus>(own.single { it.kind == "accepted" }.payload)
                assertEquals("accepted", accepted.state); assertNull(accepted.result)
                assertEquals(service.status(client.client, child.reviewId), ContractJson.decodeFromString<ReviewStatus>(own.single { it.kind == "result" }.payload))
            }
            history.forEach { batch ->
                val parent = batch.parents[index]
                val event = events.filter { it.kind == "plan_accepted" }.single {
                    ContractJson.decodeFromString<ReviewStatus>(it.payload).parentPlan?.planId == parent.planId
                }
                val payload = ContractJson.decodeFromString<ReviewStatus>(event.payload)
                assertEquals(parent.slots.last().reviewId, event.reviewId, "The parent event is anchored to its actual final child")
                assertEquals(parent.planId, assertNotNull(payload.parentPlan).planId)
                assertEquals(parent.slots.map { it.reviewId }, payload.parentPlan!!.slots.map { it.reviewId })
                assertEquals(parent.planDigest, payload.parentPlan!!.planDigest)
            }
        }
    }

    /** Read-only JDBC WAL snapshots, never raw descriptors/immutable=1, migrations or row writes.
     * Inspect actual persisted audit JSON without reaching across Kotlin internal visibility. */
    private class Readback(private val root: Path) {
        private fun <T> read(body: (Connection) -> T): T =
            DriverManager.getConnection("jdbc:sqlite:${root.resolve("ledger.sqlite").toUri()}?mode=ro").use { c ->
                c.createStatement().use { it.execute("PRAGMA busy_timeout=5000"); it.execute("PRAGMA query_only=ON") }
                c.autoCommit = false
                body(c)
            }
        fun counts(): Map<String, Long> = read { c ->
            listOf("reviews", "work", "work_certificates", "parent_plans", "routing_batches", "events", "receipts", "acknowledgements", "observations")
                .associateWith { table -> c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM $table").use { r -> check(r.next()); r.getLong(1) } } }
        }
        fun gates(): List<String> = read { c -> c.createStatement().use { s ->
            s.executeQuery("SELECT gate_state FROM clients ORDER BY client_id").use { r -> buildList { while (r.next()) add(r.getString(1)) } }
        } }
        fun audit(cutId: String): JsonObject = read { c ->
            assertTrue(cutId.startsWith("joint-v2:"))
            c.prepareStatement("SELECT value FROM metadata WHERE key=?").use { s ->
                s.setString(1, "ktchecker/joint-completion/2/cut/$cutId")
                s.executeQuery().use { r -> assertTrue(r.next()); ContractJson.parseToJsonElement(r.getString(1)).jsonObject }
            }
        }
    }

    private fun assertAudit(readback: Readback, cut: IntegratedCompletionCut, request: IntegratedFenceRequest): JsonObject {
        val audit = readback.audit(cut.cutId)
        assertEquals("ktchecker/joint-completion-cut/2", audit.getValue("format").jsonPrimitive.content)
        assertEquals("prepared", audit.getValue("state").jsonPrimitive.content); assertEquals(JsonNull, audit.getValue("receipt"))
        assertEquals(cut.scope, request.scope); assertEquals(cut.scopeDigest, request.scopeDigest)
        val requestJson = ContractJson.parseToJsonElement(canonical(request)).jsonObject
        assertEquals(JsonObject(requestJson.filterKeys { it != "scope" }), audit.getValue("request"))
        val cutJson = ContractJson.parseToJsonElement(canonical(cut)).jsonObject
        assertEquals(JsonObject(cutJson.filterKeys { it != "scope" }), audit.getValue("cut"))
        val scope = audit.getValue("scope").jsonObject
        assertEquals(cut.scope.integrationSnapshotId, scope.getValue("integrationSnapshotId").jsonPrimitive.content)
        assertEquals(ContractJson.parseToJsonElement(canonical(cut.scope.members)), scope.getValue("members"))
        assertEquals(ContractJson.parseToJsonElement(canonical(cut.scope.requiredCoverage.map { it.context }.distinct())), scope.getValue("contexts"))
        assertEquals(ContractJson.parseToJsonElement(canonical(cut.scope.requiredCoverage.map { it.ruleId }.distinct())), scope.getValue("ruleIds"))
        assertEquals(48, request.historicalObligations.size)
        assertEquals(10, cut.scope.requiredCoverage.size)
        request.frontiers.forEach { frontier ->
            assertEquals(20L, frontier.reviewFrontier)
            assertEquals(frontier.eventFrontier, frontier.requiredEventIds.size.toLong())
        }
        return audit
    }

    private fun denied(service: KernelService, client: ClientRegistration, barrier: IntegratedCompletionBarrier,
                       cut: IntegratedCompletionCut, readback: Readback, calls: AtomicInteger) {
        assertFalse(cut.ready)
        val before = readback.audit(cut.cutId)
        val counts = readback.counts()
        val result = barrier.complete(client.client, cut.cutId)
        assertFalse(result.allowed); assertFalse(result.actionPerformed); assertEquals("prepared", result.state)
        assertEquals(0, calls.get(), "Caught callback assertions must never mask forbidden action entry")
        assertEquals(listOf("open", "open"), readback.gates())
        assertEquals(before, readback.audit(cut.cutId), "A refused completion must retain the exact persisted prepared audit/no receipt")
        assertEquals(counts, readback.counts())
        assertFalse(barrier.status(client.client, cut.cutId).allowed)
        // Verify this is a live enrolled client rather than a swallowed service/authentication error.
        assertEquals(client, service.registerClient(client))
    }
}

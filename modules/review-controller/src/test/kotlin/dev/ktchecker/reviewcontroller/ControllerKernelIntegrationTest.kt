package dev.ktchecker.reviewcontroller

import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * REAL registered overlays, importer capabilities, SQLite, archived files and worker threads.
 * Export/approval callbacks and provider are SYNTHETIC. No compiler/Gradle process executes;
 * no rule is marked implemented/tested/qualified and no successful coverage certificate is made.
 * Reopening a kernel below retains live owner capabilities in this JVM, not deserialized authority.
 */
class ControllerKernelIntegrationTest {
    @TempDir lateinit var temp: Path
    private val config = KernelConfig(workerCount = 1, globalParallelism = 1, perWorktreeParallelism = 1,
        heartbeatMillis = 50, leaseMillis = 200, pollMillis = 5, analysisTimeoutMillis = 15_000)
    private fun fixture() = ControllerFixtures(temp.resolve(UUID.randomUUID().toString()))

    private class CheckedAttempt(val original: AnalysisInput, val effective: AnalysisInput, val verify: () -> Unit)

    /** Provider requires a live exact original -> transformed archive permit, not profile booleans. */
    private class ArchiveCheckingProvider(assemblies: List<AssembledReviewPlan>) : AnalysisProvider {
        override val id = "unit-provider"
        override val supportedRuleIds = assemblies.flatMap { it.provenance.inventory.ruleIds }.toSet()
        private val originals = assemblies.flatMap { it.plan.slots }.filter { it.state == "pending" }
            .map { it.input }.associateBy { it.analysisKey() }
        private val permits = ConcurrentHashMap<String, CheckedAttempt>()
        val attempts = ConcurrentLinkedQueue<CheckedAttempt>()
        val executed = ConcurrentLinkedQueue<AnalysisInput>()
        val policyCalls = ConcurrentLinkedQueue<Pair<ClientBinding, String>>()
        private val policies = assemblies.flatMap { assembly -> assembly.plan.slots.map { slot ->
            val identity = assembly.provenance.slots.single { it.slotId == slot.slotId }.policyIdentity
            (assembly.plan.binding to slot.input.analysisKey()) to identity
        } }.toMap()

        // Pure preloaded per-consumer lookup. The queue is test instrumentation, not policy I/O.
        val policy = ResultPolicy.versioned("synthetic-controller-captured-policy/1") { binding, input, raw ->
            val key = binding to input.analysisKey()
            val identity = requireNotNull(policies[key]) { "No captured policy for this exact consumer/input" }
            check(identity.binding == binding && identity.configurationDigest == input.context.configurationDigest)
            policyCalls += key
            raw
        }

        val observer = PreparedInputObserver { original, effective, verifyFrozen ->
            assertEquals(originals[original.analysisKey()], original, "Attempt must name a retained assembler input")
            assertNotEquals(original.materializedRoot, effective.materializedRoot)
            assertNotNull(effective.outputRoot)
            assertNull(effective.execution) // No approved Boolean creates a process capability.
            assertEquals(original.context.classpath.size, effective.context.classpath.size)
            original.context.classpath.zip(effective.context.classpath).forEach { (old, frozen) ->
                assertEquals(old.coordinate, frozen.coordinate); assertEquals(old.digest, frozen.digest)
                assertNotEquals(old.path, frozen.path)
            }
            // Only the kernel's private source/classpath/output paths may differ. Everything else,
            // including whole snapshot, explicit membership/order and final policies, is exact.
            assertEquals(original, effective.copy(materializedRoot = original.materializedRoot, outputRoot = original.outputRoot,
                context = effective.context.copy(classpath = original.context.classpath)))
            assertNotEquals(exactPlanContextIdentity(original)!!.classpathDigest, exactPlanContextIdentity(effective)!!.classpathDigest)
            verifyFrozen()
            val permit = CheckedAttempt(original, freezeInput(effective), verifyFrozen)
            assertNull(permits.putIfAbsent(effective.analysisKey(), permit))
            attempts += permit
        }

        override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
            val permit = requireNotNull(permits.remove(input.analysisKey())) { "No live archive-bound test permit" }
            assertEquals(permit.effective, input)
            permit.verify()
            cancellation.throwIfCancelled()
            input.snapshot.files.forEach { file ->
                val bytes = Files.readAllBytes(Path.of(input.materializedRoot).resolve(file.path))
                assertEquals(file.size, bytes.size.toLong()); assertEquals(file.digest, Digests.sha256(bytes))
            }
            input.context.classpath.forEach { assertEquals(it.digest, Digests.sha256(Files.readAllBytes(Path.of(it.path)))) }
            assertEquals(permit.original.context.compilationFiles, input.context.compilationFiles)
            assertTrue(input.snapshot.files.size > input.context.compilationFiles!!.size, "The archive is the whole project, not selected sources only")
            permit.verify()
            executed += freezeInput(input)
            return ProviderResult(id, "incomplete", coverage = input.requiredRuleIds.map {
                Coverage(it, input.context.scope, "unsupported", "synthetic_provider_unqualified", provenance = "executed")
            }, reasons = listOf("synthetic_provider_unqualified"))
        }
    }

    private fun awaitResults(kernel: KernelService, client: ClientRegistration, parent: ParentPlanStatus): ParentPlanStatus {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        parent.slots.forEach { slot ->
            var status = kernel.status(client.client, slot.reviewId)
            while (status.result == null && System.nanoTime() < deadline) {
                Thread.sleep(5)
                status = kernel.status(client.client, slot.reviewId)
            }
            assertNotNull(status.result, "Child did not finish: $status")
            if (slot.state != "unsupported") {
                assertEquals("incomplete", status.state)
                assertEquals(listOf("synthetic_provider_unqualified"), status.result!!.reasons,
                    "An assertion/authorization failure must not pass as expected unqualified coverage")
                assertEquals(slot.requiredRuleIds.toSet(), status.result!!.coverage.map { it.ruleId }.toSet())
                assertTrue(status.result!!.coverage.all { !it.implemented && !it.tested && !it.qualified && it.certificateId == null })
            }
        }
        return kernel.planStatus(client.client, parent.planId).also { assertEquals("incomplete", it.state) }
    }

    private fun events(kernel: KernelService, client: ClientRegistration): List<ReviewEvent> = buildList {
        var cursor: String? = null
        do {
            val page = kernel.events(client.client, cursor, 3)
            addAll(page.events); cursor = page.cursor
        } while (page.hasMore)
    }

    private fun insertionDigest(event: ReviewEvent) = Digests.sha256("unit-adapter-context:${event.clientId}:${event.payloadDigest}")
    private fun acknowledge(kernel: KernelService, client: ClientRegistration, event: ReviewEvent) {
        assertEquals(Digests.sha256(event.payload), event.payloadDigest)
        kernel.acknowledge(client.adapter, listOf(event.eventId), ContextReceipt("ack-${event.eventId}", insertionDigest(event),
            "unit-item-${event.eventId}", Instant.EPOCH.toString()))
    }
    private fun observe(kernel: KernelService, client: ClientRegistration, event: ReviewEvent) {
        kernel.observe(client.adapter, listOf(event.eventId), ObservationReceipt("observe-${event.eventId}",
            "unit-model-request-${event.eventId}", listOf(insertionDigest(event))))
    }
    private fun deliver(kernel: KernelService, client: ClientRegistration, selected: List<ReviewEvent> = events(kernel, client)) {
        selected.forEach { acknowledge(kernel, client, it); observe(kernel, client, it) }
    }
    private fun deliveryMissing(cut: GateCut) = cut.reasons.filter { it.startsWith("insertion_missing:") || it.startsWith("model_observation_missing:") }

    @Test fun fullImportedPlanExecutesOnlyItsExactTransformedWholeArchiveAndNeverBecomesQualified() {
        val f = fixture(); val capture = f.capture(); val model = f.import(capture); val prep = TestPreparations()
        val assembly = prep.assembler(f).assemble(f.binding(), f.request(), null, model, capture.materialization(model), listOf(f.binding().clientId))
        val registry = OwnerReviewPlanRegistry(f.namespace.ownerId).also { it.register(assembly) }
        val provider = ArchiveCheckingProvider(listOf(assembly)); val database = f.work.resolve("kernel-full")
        val (client, accepted) = KernelService.open(database, provider, config.copy(workerCount = 0),
            planAuthority = registry, preparedInputObserver = provider.observer, resultPolicy = provider.policy).use { kernel ->
            val client = kernel.registerClient(f.binding())
            client to kernel.submitPlan(client.client, assembly.plan.request).also { assertEquals("pending", it.state) }
        }
        // Accepted source/classpath archives survive loss of original inputs. This synthetic
        // provider consumes no external compiler/runtime; their freezing is not being certified.
        capture.replaceCommonSourceForTest("changed after acceptance\n")
        f.artifacts.forEach { Files.writeString(Path.of(it.path), "changed after classpath archival\n") }
        assertFalse(capture.verifyRegistered())
        assertEquals("materialization_unavailable", assertFailsWith<ReviewControllerException> { registry.resolve(f.binding(), f.request()) }.code)
        val freshRegistry = OwnerReviewPlanRegistry(f.namespace.ownerId)
        assertNull(freshRegistry.resolve(f.binding(), f.request()))
        KernelService.open(database, provider, config, planAuthority = freshRegistry,
            preparedInputObserver = provider.observer, resultPolicy = provider.policy).use { kernel ->
            assertEquals(accepted.planId, kernel.submitPlan(client.client, assembly.plan.request).planId) // Durable exact retry, no decoded capability restore.
            val completed = awaitResults(kernel, client, accepted)
            assertEquals(model.compilationIds.size, provider.executed.size)
            assertEquals(model.compilationIds.size, provider.attempts.size)
            assertEquals(assembly.plan.slots.map { it.input.context.scope }.toSet(), provider.executed.map { it.context.scope }.toSet())
            provider.executed.forEach {
                assertEquals(model.snapshot, it.snapshot); assertTrue(it.context.fullProject)
                assertEquals(f.inventory.ruleIds, it.requiredRuleIds)
                assertEquals(listOf("unit:z-first:1", "unit:a-second:1"), it.context.classpath.map { artifact -> artifact.coordinate })
            }
            assertEquals(completed.slots.size, provider.policyCalls.size)
            val parentEvent = events(kernel, client).single { it.kind == "plan_accepted" }
            val payload = ContractJson.decodeFromString<ReviewStatus>(parentEvent.payload)
            assertTrue(parentEvent.deliveryRequired); assertEquals(accepted.planId, payload.parentPlan!!.planId)
            assertEquals(accepted.slots.map { it.slotId }, payload.parentPlan!!.slots.map { it.slotId })
            deliver(kernel, client)
            val cut = kernel.prepareGate(client.client)
            assertTrue(deliveryMissing(cut).isEmpty()); assertFalse(cut.ready)
            val action = AtomicInteger()
            assertFalse(kernel.completeGate(client.client, cut.cutId) { action.incrementAndGet() }.allowed)
            assertEquals(0, action.get())
        }
    }

    @Test fun sameInputConsumersShareOnlyExecutionNotParentDeliveryOrCapturedPolicyAuthority() {
        val f = fixture(); val capture = f.capture(); val model = f.import(capture); val prep = TestPreparations()
        val bindings = listOf(f.binding("a"), f.binding("b"))
        val assemblies = bindings.map { binding -> prep.assembler(f).assemble(binding, f.request(binding.clientId), null,
            model, capture.materialization(model), listOf(binding.clientId)) }
        val registry = OwnerReviewPlanRegistry(f.namespace.ownerId).also { r -> assemblies.forEach { r.register(it) } }
        val provider = ArchiveCheckingProvider(assemblies); val database = f.work.resolve("kernel-clients")
        val accepted = KernelService.open(database, provider, config.copy(workerCount = 0), planAuthority = registry,
            preparedInputObserver = provider.observer, resultPolicy = provider.policy).use { kernel ->
            assemblies.map { assembly ->
                val client = kernel.registerClient(assembly.plan.binding)
                client to kernel.submitPlan(client.client, assembly.plan.request)
            }
        }
        KernelService.open(database, provider, config, planAuthority = registry,
            preparedInputObserver = provider.observer, resultPolicy = provider.policy).use { kernel ->
            val (a, parentA) = accepted[0]; val (b, parentB) = accepted[1]
            accepted.forEach { (client, parent) -> awaitResults(kernel, client, parent) }
            assertEquals(model.compilationIds.size, provider.executed.size)
            assertEquals(2 * model.compilationIds.size, provider.policyCalls.size)
            assemblies.forEach { assembly -> assembly.plan.slots.forEach { slot ->
                assertContains(provider.policyCalls, assembly.plan.binding to slot.input.analysisKey())
                assertTrue(assembly.plan.binding to slot.input.analysisKey() in prep.policyRecords)
                assertTrue(assembly.plan.binding to slot.input.analysisKey() in prep.executionRecords)
            } }
            val aEvents = events(kernel, a); val bEvents = events(kernel, b)
            assertEquals(model.compilationIds.size * 2 + 1, aEvents.size)
            assertEquals(aEvents.size, bEvents.size)
            assertTrue(aEvents.map { it.eventId }.toSet().intersect(bEvents.map { it.eventId }.toSet()).isEmpty())
            assertTrue(parentA.slots.map { it.reviewId }.toSet().intersect(parentB.slots.map { it.reviewId }.toSet()).isEmpty())
            val parentEventA = aEvents.single { it.kind == "plan_accepted" }
            val parentEventB = bEvents.single { it.kind == "plan_accepted" }
            assertEquals("event_not_found", assertFailsWith<KernelException> { acknowledge(kernel, a, parentEventB) }.code)
            assertEquals("plan_not_found", assertFailsWith<KernelException> { kernel.planStatus(b.client, parentA.planId) }.code)
            deliver(kernel, a, aEvents.filterNot { it.eventId == parentEventA.eventId })
            val childrenOnly = kernel.prepareGate(a.client)
            assertEquals(setOf("insertion_missing:${parentEventA.eventId}", "model_observation_missing:${parentEventA.eventId}"), deliveryMissing(childrenOnly).toSet())
            assertContains(childrenOnly.requiredEventIds, parentEventA.eventId)
            acknowledge(kernel, a, parentEventA)
            assertEquals(listOf("model_observation_missing:${parentEventA.eventId}"), deliveryMissing(kernel.prepareGate(a.client)))
            assertEquals(2 * bEvents.size, deliveryMissing(kernel.prepareGate(b.client)).size)
            observe(kernel, a, parentEventA)
            assertTrue(deliveryMissing(kernel.prepareGate(a.client)).isEmpty())
            assertEquals(2 * bEvents.size, deliveryMissing(kernel.prepareGate(b.client)).size)
            deliver(kernel, b)
            assertTrue(deliveryMissing(kernel.prepareGate(b.client)).isEmpty())
            assertEquals("cancelled", kernel.cancelPlan(a.client, parentA.planId).state)
            assertEquals("incomplete", kernel.planStatus(b.client, parentB.planId).state)
            accepted.forEach { (client, _) ->
                val cut = kernel.prepareGate(client.client)
                assertFalse(kernel.completeGate(client.client, cut.cutId).allowed)
            }
        }
    }

    @Test fun removedAndRetargetedOldContextsRemainAdministrativeObligationsBesideCurrentWorkerResults() {
        for (kind in listOf("removed", "retargeted")) {
            val f = fixture(); val oldCapture = f.capture(); val before = f.import(oldCapture)
            val capture = if (kind == "removed") f.capture(f.files() - "lib/src/desktopMain/kotlin/Desktop.kt") else oldCapture
            val graph = f.graph(capture).let { graph -> if (kind == "removed") graph.copy(
                compilations = graph.compilations.filterNot { it.id == ":lib|desktop|main" },
                sourceSets = graph.sourceSets.filterNot { it.id == ":lib|desktopMain" }) else graph.copy(compilations = graph.compilations.map {
                    if (it.id == ":lib|jvm|main") it.copy(defaultSourceSetId = ":lib|commonMain") else it
                }) }
            val after = f.import(capture, graph); val prep = TestPreparations()
            val assembly = prep.assembler(f).assemble(f.binding(), f.request(), before, after, capture.materialization(after), listOf(f.binding().clientId))
            val unsupported = assembly.plan.slots.single { it.state == "unsupported" }
            val registry = OwnerReviewPlanRegistry(f.namespace.ownerId).also { it.register(assembly) }
            val provider = ArchiveCheckingProvider(listOf(assembly))
            KernelService.open(f.work.resolve("kernel-old-$kind"), provider, config, planAuthority = registry,
                preparedInputObserver = provider.observer, resultPolicy = provider.policy).use { kernel ->
                val client = kernel.registerClient(f.binding())
                val accepted = kernel.submitPlan(client.client, assembly.plan.request)
                val completed = awaitResults(kernel, client, accepted)
                assertEquals(after.compilationIds.size + 1, completed.slots.size)
                assertEquals(after.compilationIds.size, provider.executed.size)
                val oldSlot = completed.slots.single { it.slotId == unsupported.slotId }
                assertEquals("unsupported", oldSlot.state); assertEquals(unsupported.input.context.scope, oldSlot.context)
                assertEquals(f.inventory.ruleIds, oldSlot.requiredRuleIds)
                val oldStatus = kernel.status(client.client, oldSlot.reviewId)
                assertContains(oldStatus.result!!.reasons, "required_plan_scope_unsupported")
                assertTrue(oldStatus.result!!.coverage.isEmpty()) // No provider or successful certificate for missing authority.
                assertFalse(provider.executed.any { it.context.scope == oldSlot.context })
                provider.executed.forEach { assertEquals(after.snapshot, it.snapshot) }
                deliver(kernel, client)
                val cut = kernel.prepareGate(client.client)
                assertTrue(cut.reasons.any { it.startsWith("parent_plan_incomplete:") })
                assertFalse(kernel.completeGate(client.client, cut.cutId).allowed)
            }
        }
    }

    /** Captured observations only: immutable model/capture evidence is NEVER current-live authority. */
    private class UnavailableVectorProbe(private val assembly: AssembledReviewPlan) : SnapshotGate {
        val scalarCalls = AtomicInteger()
        val vectorCalls = AtomicInteger()
        private val expected = assembly.plan.slots.asReversed().map { it.input }
        private val anchor = expected.first()
        var observation = SnapshotCheck(false, anchor.snapshot.snapshotId, 1, anchor.context.configurationDigest,
            anchor.context.modelDigest, anchor.context.dependencyDigest, anchor.context.toolchainDigest,
            "unit-captured-not-live", authoritative = false, reasons = listOf("unit_no_live_current_authority"),
            contextIdentities = assembly.plan.slots.filter { it.state == "pending" }.map { it.identity!! },
            sourceBindingDigest = assembly.plan.sourceBindingDigest, generatedInputsDigest = assembly.plan.generatedInputsDigest)

        override fun <T> withCurrentSnapshot(input: AnalysisInput, action: (SnapshotCheck) -> T): T {
            scalarCalls.incrementAndGet()
            return action(SnapshotCheck(false, reasons = listOf("unit_scalar_probe_cannot_attest_a_parent")))
        }
        override fun <T> withCurrentSnapshots(inputs: List<AnalysisInput>, action: (SnapshotCheck) -> T): T {
            vectorCalls.incrementAndGet()
            assertEquals(expected, inputs, "Kernel must supply the entire retained owner vector in one callback")
            assertEquals(assembly.provenance.after.snapshot, inputs.first().snapshot)
            assertTrue(inputs.all { it.snapshot == inputs.first().snapshot })
            return action(observation) // Stored before this call, not authority echoed from caller fields.
        }
    }

    @Test fun incrementalImpactStillRequiresWholeVectorAndEvenMatchingCapturedIdentitiesCannotAuthorizeFinalAction() {
        val f = fixture(); val beforeCapture = f.capture(); val before = f.import(beforeCapture)
        val changed = "lib/src/commonMain/resources/message.txt"
        val capture = f.capture(f.files().toMutableMap().apply { this[changed] = "incremental resource update\n" })
        val after = f.import(capture); val prep = TestPreparations()
        val assembly = prep.assembler(f).assemble(f.binding(), f.request(), before, after, capture.materialization(after), listOf(f.binding().clientId))
        assertEquals(listOf(changed), assembly.provenance.impact.changedFiles)
        assertFalse(assembly.provenance.impact.obligations.any { it.compilationId == ":independent|jvm|main" })
        assertEquals(after.compilationIds.size, assembly.plan.slots.size)
        val registry = OwnerReviewPlanRegistry(f.namespace.ownerId).also { it.register(assembly) }
        val provider = ArchiveCheckingProvider(listOf(assembly)); val probe = UnavailableVectorProbe(assembly)
        KernelService.open(f.work.resolve("kernel-vector"), provider, config, snapshotGate = probe,
            planAuthority = registry, preparedInputObserver = provider.observer, resultPolicy = provider.policy).use { kernel ->
            val client = kernel.registerClient(f.binding())
            val accepted = kernel.submitPlan(client.client, assembly.plan.request)
            awaitResults(kernel, client, accepted)
            assertEquals(after.compilationIds.size, provider.executed.size)
            provider.executed.forEach { assertEquals(listOf(changed), it.changedPaths); assertEquals(before.snapshot.snapshotId, it.baselineSnapshotId) }
            deliver(kernel, client)
            val cut = kernel.prepareGate(client.client)
            assertFalse(cut.ready); assertContains(cut.reasons, "current_source_configuration_authority_unavailable")
            assertContains(cut.reasons, "unit_no_live_current_authority")
            assertFalse(cut.reasons.any { it.startsWith("plan_context_vector_authority_mismatch") || it.startsWith("plan_source_binding_authority_mismatch") })
            val action = AtomicInteger()
            assertFalse(kernel.completeGate(client.client, cut.cutId) { action.incrementAndGet() }.allowed)
            val anchor = assembly.plan.slots.last().input.context.scope
            val nonAnchor = probe.observation.contextIdentities.first { it.context != anchor }
            probe.observation = probe.observation.copy(contextIdentities = probe.observation.contextIdentities.map {
                if (it == nonAnchor) it.copy(compilerArgumentsDigest = Digests.sha256("unit-corrupted-non-anchor-arguments")) else it
            })
            val changedVector = kernel.completeGate(client.client, cut.cutId) { action.incrementAndGet() }
            assertContains(changedVector.reasons, "plan_context_vector_authority_mismatch")
            assertContains(changedVector.reasons, "authority_fence_changed")
            assertFalse(changedVector.allowed); assertEquals(0, action.get())
            assertEquals(0, probe.scalarCalls.get()); assertTrue(probe.vectorCalls.get() >= 3)
        }
    }
}

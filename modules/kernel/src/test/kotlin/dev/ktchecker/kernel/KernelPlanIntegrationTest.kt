package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

internal const val PLAN_TEST_PROVIDER = "synthetic-plan"
internal const val PLAN_TEST_RULE = "test/plan"

internal fun planPassed(input: AnalysisInput, findings: List<Finding> = emptyList()) = ProviderResult(
    PLAN_TEST_PROVIDER, "complete", findings,
    input.requiredRuleIds.map { rule -> Coverage(rule, input.context.scope,
        if (findings.any { it.ruleId == rule }) "findings" else "passed", provenance = "executed",
        certificateId = Digests.sha256("synthetic-plan-certificate:${input.analysisKey()}:$rule"),
        implemented = true, tested = true, qualified = true) },
)

internal class PlanTestProvider(
    private val body: (AnalysisInput, CancellationToken) -> ProviderResult = { input, _ -> planPassed(input) },
) : AnalysisProvider {
    override val id = PLAN_TEST_PROVIDER
    override val supportedRuleIds = setOf(PLAN_TEST_RULE)
    val calls = AtomicInteger()
    val scopes = ConcurrentLinkedQueue<RuleContext>()
    override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
        calls.incrementAndGet()
        scopes += input.context.scope
        return body(input, cancellation)
    }
}

/** Real SQLite/files/threads/process death; model, impact, compiler and host authorities are synthetic. */
class KernelPlanIntegrationTest {
    @TempDir lateinit var temp: Path
    private val config = KernelConfig(workerCount = 2, globalParallelism = 2, perWorktreeParallelism = 2,
        heartbeatMillis = 40, leaseMillis = 160, pollMillis = 5, analysisTimeoutMillis = 15_000)

    private fun binding(id: String) = ClientBinding("owner", "workspace", "tree", id, "lineage-$id", 1, "session-$id")
    private fun request(id: String) = SubmitPlanRequest("patch-$id", "key-$id", "ref-$id")

    private fun inputs(name: String, count: Int = 2, parent: String? = null): List<AnalysisInput> {
        val root = temp.resolve("source-$name")
        Files.createDirectories(root.resolve("support"))
        val contents = linkedMapOf("Main.kt" to "package fixture\nval main = 1\n", "support/Util.kt" to "package fixture\nval util = 2\n")
        val files = contents.map { (path, text) ->
            Files.writeString(root.resolve(path), text)
            SnapshotFile(path, Digests.sha256(text), text.toByteArray().size.toLong())
        }
        val snapshot = SnapshotManifest(Digests.sha256("plan-source:$name:$parent"), "workspace", "tree", files,
            ownerId = "owner", parentSnapshotId = parent, declaredRoots = listOf("."))
        return (0 until count).map { index ->
            AnalysisInput(snapshot, root.toString(), AnalysisContext(
                scope = RuleContext(module = ":module-$index"), compilerVersion = "2.4.21",
                compilerArguments = listOf("-module-name", "module-$index"),
                libraries = mapOf("kotlin" to "2.4.21"), policies = mapOf(PLAN_TEST_RULE to "enabled-$index"),
                modelDigest = Digests.sha256("model"), dependencyDigest = Digests.sha256("dependencies-$index"),
                toolchainDigest = Digests.sha256("toolchain-$index"), configurationDigest = Digests.sha256("configuration-$index"),
                fullProject = true, compilationFiles = files.map { it.path }), listOf(PLAN_TEST_RULE))
        }
    }

    private fun definition(binding: ClientBinding, request: SubmitPlanRequest, inputs: List<AnalysisInput>) = RequiredReviewPlan(
        binding = binding, request = request, sourceBindingDigest = Digests.sha256(canonical(inputs.first().snapshot)),
        generatedInputsDigest = Digests.sha256(canonical(emptyList<SnapshotFile>())),
        modelCertificateId = Digests.sha256("synthetic-model-certificate"),
        impactCertificateId = Digests.sha256("synthetic-impact-certificate:${inputs.first().snapshot.snapshotId}"),
        slots = inputs.mapIndexed { index, input -> RequiredPlanSlot("slot-${index.toString().padStart(3, '0')}", input, assertNotNull(contextIdentity(input))) },
        requiredRecipientClientIds = listOf(binding.clientId),
    )

    private class Registry : ReviewPlanAuthority {
        val definitions = ConcurrentHashMap<String, RequiredReviewPlan>()
        val calls = AtomicInteger()
        fun put(definition: RequiredReviewPlan) { definitions[definition.request.planRef] = definition }
        override fun resolve(binding: ClientBinding, request: SubmitPlanRequest): RequiredReviewPlan? {
            calls.incrementAndGet()
            // Deliberately does not check caller binding/tuple: kernel must reject an incorrectly
            // addressed entry too, rather than merely assuming every trusted controller is bug-free.
            return definitions[request.planRef]
        }
    }

    /** The attestation comes from a stored trusted definition, NEVER by echoing supplied inputs. */
    private class VectorFence : SnapshotGate {
        private val lock = Any()
        private var check = SnapshotCheck(false)
        private var epoch = 0L
        val scalarCalls = AtomicInteger()
        val vectorCalls = AtomicInteger()
        fun install(definition: RequiredReviewPlan) = synchronized(lock) {
            val trusted = ContractJson.decodeFromString<RequiredReviewPlan>(canonical(definition))
            val anchor = trusted.slots.sortedBy { it.slotId }.last().input
            epoch++
            check = SnapshotCheck(true, anchor.snapshot.snapshotId, epoch, anchor.context.configurationDigest,
                anchor.context.modelDigest, anchor.context.dependencyDigest, anchor.context.toolchainDigest,
                "synthetic-joint-config-source:$epoch", authoritative = true,
                contextIdentities = trusted.slots.filter { it.state == "pending" }.sortedBy { it.slotId }.mapNotNull { it.identity },
                sourceBindingDigest = trusted.sourceBindingDigest, generatedInputsDigest = trusted.generatedInputsDigest)
        }
        fun current(): SnapshotCheck = synchronized(lock) { ContractJson.decodeFromString(canonical(check)) }
        /** Negative fixtures may deliberately change a non-anchor field without incrementing scalar epochs. */
        fun set(value: SnapshotCheck) = synchronized(lock) { check = ContractJson.decodeFromString(canonical(value)) }
        override fun <T> withCurrentSnapshot(input: AnalysisInput, action: (SnapshotCheck) -> T): T = synchronized(lock) {
            scalarCalls.incrementAndGet()
            action(check)
        }
        override fun <T> withCurrentSnapshots(inputs: List<AnalysisInput>, action: (SnapshotCheck) -> T): T = synchronized(lock) {
            vectorCalls.incrementAndGet()
            action(check)
        }
    }

    private fun awaitPlan(service: KernelService, client: ClientRegistration, id: String): ParentPlanStatus {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        val accepted = service.planStatus(client.client, id)
        for (slot in accepted.slots) {
            var child = service.status(client.client, slot.reviewId)
            while (child.result == null && System.nanoTime() < deadline) {
                Thread.sleep(5)
                child = service.status(client.client, slot.reviewId)
            }
            assertNotNull(child.result, "Child did not finish: $child")
        }
        return service.planStatus(client.client, id)
    }

    private fun awaitReview(service: KernelService, client: ClientRegistration, id: String): ReviewStatus {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        var status = service.status(client.client, id)
        while (status.result == null && System.nanoTime() < deadline) {
            Thread.sleep(5)
            status = service.status(client.client, id)
        }
        assertNotNull(status.result, "Manual review did not finish: $status")
        return status
    }

    private fun events(service: KernelService, client: ClientRegistration): List<ReviewEvent> = buildList {
        var cursor: String? = null
        do {
            val page = service.events(client.client, cursor, 3)
            addAll(page.events)
            cursor = page.cursor
        } while (page.hasMore)
    }

    private fun deliver(service: KernelService, client: ClientRegistration, select: (ReviewEvent) -> Boolean = { true }) {
        events(service, client).filter(select).forEach { event ->
            assertEquals(Digests.sha256(event.payload), event.payloadDigest)
            val digest = Digests.sha256("synthetic-plan-context:${event.payload}")
            service.acknowledge(client.adapter, listOf(event.eventId), ContextReceipt("ack-${event.eventId}", digest,
                "item-${event.eventId}", Instant.EPOCH.toString()))
            service.observe(client.adapter, listOf(event.eventId), ObservationReceipt("observe-${event.eventId}",
                "model-request-${event.eventId}", listOf(digest)))
        }
    }

    private fun counts(service: KernelService): Map<String, Long> = service.ledger.read { c ->
        listOf("parent_plans", "plan_slots", "reviews", "work", "events", "work_certificates", "fair_clients")
            .associateWith { c.count("SELECT COUNT(*) FROM $it") }
    }
    private fun assertNoAcceptance(service: KernelService, client: ClientRegistration) {
        assertTrue(counts(service).values.all { it == 0L }, counts(service).toString())
        service.ledger.read { c ->
            assertEquals(1L, c.count("SELECT next_review FROM clients WHERE client_id=?", client.binding.clientId))
            assertEquals(1L, c.count("SELECT next_event FROM clients WHERE client_id=?", client.binding.clientId))
            assertEquals(0L, c.count("SELECT epoch FROM clients WHERE client_id=?", client.binding.clientId))
        }
    }
    private fun assertCode(code: String, block: () -> Unit) = assertEquals(code, assertFailsWith<KernelException>(block = block).code)

    @Test fun everyRequiredSlotAndAcceptanceEventBecomeVisibleInOneCommitBeforeAnyProviderRuns() {
        val inputs = inputs("atomic", 3)
        val registry = Registry()
        val paused = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var service: KernelService
        val provider = PlanTestProvider { input, _ ->
            service.ledger.read { c ->
                assertEquals(1L, c.count("SELECT COUNT(*) FROM parent_plans"))
                assertEquals(3L, c.count("SELECT COUNT(*) FROM plan_slots"))
                assertEquals(3L, c.count("SELECT COUNT(*) FROM reviews"))
                assertEquals(3L, c.count("SELECT COUNT(*) FROM events WHERE kind='accepted'"))
                assertEquals(1L, c.count("SELECT COUNT(*) FROM events WHERE kind='plan_accepted'"))
            }
            planPassed(input)
        }
        KernelService.open(temp.resolve("ledger"), provider, config, planAuthority = registry).use { opened ->
            service = opened
            val client = service.registerClient(binding("atomic"))
            val request = request("atomic")
            val prepared = definition(client.binding, request, inputs)
            registry.put(prepared.copy(slots = prepared.slots.reversed()))
            service.plans.afterSlotInsert = { count -> if (count == 1) {
                paused.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS))
            } }
            val pool = Executors.newSingleThreadExecutor()
            try {
                val pending = pool.submit<ParentPlanStatus> { service.submitPlan(client.client, request) }
                assertTrue(paused.await(5, TimeUnit.SECONDS))
                assertNoAcceptance(service, client)
                assertTrue(events(service, client).isEmpty())
                assertEquals(0, provider.calls.get())
                release.countDown()
                val accepted = pending.get(10, TimeUnit.SECONDS)
                assertEquals(3, accepted.slots.size)
                assertEquals(prepared.slots.map { it.slotId }, accepted.slots.map { it.slotId }, "Caller slot ordering must not choose a different scalar anchor")
                assertEquals("complete", awaitPlan(service, client, accepted.planId).state)
                val parentEvent = events(service, client).single { it.kind == "plan_accepted" }
                assertTrue(parentEvent.deliveryRequired)
                val payload = ContractJson.decodeFromString<ReviewStatus>(parentEvent.payload)
                assertTrue((ContractJson.parseToJsonElement(parentEvent.payload) as JsonObject)["parentPlan"] is JsonObject,
                    "The nonnull parent acceptance vector must remain explicit on the wire")
                assertEquals(accepted, payload.parentPlan)
                assertEquals(accepted.slots.map { it.reviewId }, payload.parentPlan!!.slots.map { it.reviewId })
                assertEquals(3, provider.calls.get())
            } finally { release.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun faultAfterAnInsertedChildRollsBackTheWholePlanWithoutInstallingArchivesBeforeRetry() {
        val inputs = inputs("rollback")
        val registry = Registry()
        KernelService.open(temp.resolve("ledger"), PlanTestProvider(), config.copy(workerCount = 0), planAuthority = registry).use { service ->
            val client = service.registerClient(binding("rollback"))
            val request = request("rollback")
            registry.put(definition(client.binding, request, inputs))
            service.plans.afterSlotInsert = { if (it == 1) throw IllegalStateException("injected precommit fault") }
            assertFailsWith<IllegalStateException> { service.submitPlan(client.client, request) }
            assertNoAcceptance(service, client)
            assertEquals(0L, Files.list(service.root.resolve("inputs")).use { it.count() }, "Pre-publication rejection must close every uninstalled staging directory")
            service.plans.afterSlotInsert = { }
            val accepted = service.submitPlan(client.client, request)
            assertEquals("pending", accepted.state)
            assertEquals(2, accepted.slots.size)
            assertEquals(2L, counts(service).getValue("work"))
            assertEquals(3, events(service, client).size)
            assertEquals(accepted.planId, service.submitPlan(client.client, request).planId)
            assertEquals(2, registry.calls.get(), "An accepted retry must not need to resolve mutable controller state")
        }
    }

    @Test fun forciblyKilledUncommittedFanoutConfersNoAcceptanceAndAcceptedRetryRecoversWithoutOriginalsOrResolver() {
        val inputs = inputs("killed-parent")
        val database = temp.resolve("ledger")
        val registry = Registry()
        val fence = VectorFence()
        val provider = PlanTestProvider { effective, _ ->
            assertNotEquals(inputs.first().materializedRoot, effective.materializedRoot)
            assertEquals("package fixture\nval main = 1\n", Files.readString(Path.of(effective.materializedRoot).resolve("Main.kt")))
            planPassed(effective)
        }
        lateinit var client: ClientRegistration
        lateinit var accepted: ParentPlanStatus
        val request = request("killed-parent")
        KernelService.open(database, provider, config.copy(workerCount = 0), planAuthority = registry).use { reader ->
            client = reader.registerClient(binding("killed-parent"))
            val definition = definition(client.binding, request, inputs)
            registry.put(definition)
            fence.install(definition)
            val configFile = temp.resolve("child-config.json")
            val definitionFile = temp.resolve("child-definition.json")
            val registrationFile = temp.resolve("child-registration.json")
            writeDurable(configFile, canonical(config.copy(workerCount = 0)).toByteArray())
            writeDurable(definitionFile, canonical(definition).toByteArray())
            writeDurable(registrationFile, canonical(client).toByteArray())
            val marker = temp.resolve("child-prefix-inserted")
            val log = temp.resolve("child.log")
            val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
            val child = ProcessBuilder(java.toString(), "-Xmx128m", "-cp", crashClasspath(), KernelPlanCrashChild::class.java.name,
                database.toString(), configFile.toString(), definitionFile.toString(), registrationFile.toString(), marker.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start()
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (!Files.exists(marker) && child.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
                assertTrue(Files.exists(marker), "Child did not enter fanout transaction: " + Files.readString(log).take(2000))
                // Reuse an already-open service: opening a service would need the paused writer lock.
                assertNoAcceptance(reader, client)
                assertTrue(events(reader, client).isEmpty())
                assertEquals(0, provider.calls.get())
                child.destroyForcibly()
                assertTrue(child.waitFor(10, TimeUnit.SECONDS))
                assertNoAcceptance(reader, client)
                accepted = reader.submitPlan(client.client, request)
                assertEquals("pending", accepted.state)
                assertEquals(2, accepted.slots.size)
            } finally { if (child.isAlive) { child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS) } }
        }
        deleteTree(Path.of(inputs.first().materializedRoot))
        KernelService.open(database, provider, config, fence).use { recovered ->
            assertEquals("complete", awaitPlan(recovered, client, accepted.planId).state)
            assertEquals(accepted.planId, recovered.submitPlan(client.client, request).planId)
            val events = events(recovered, client)
            assertEquals(2, events.count { it.kind == "accepted" })
            assertEquals(1, events.count { it.kind == "plan_accepted" })
            assertEquals(2, events.count { it.kind == "result" })
            deliver(recovered, client)
            val cut = recovered.prepareGate(client.client)
            assertTrue(cut.ready, cut.reasons.toString())
            assertTrue(recovered.completeGate(client.client, cut.cutId).allowed)
            assertEquals(2, provider.calls.get())
        }
    }

    private fun crashClasspath(): String = buildSet<String> {
        System.getProperty("java.class.path").split(java.io.File.pathSeparator).filter { it.isNotBlank() }.forEach(::add)
        var loader: ClassLoader? = KernelPlanCrashChild::class.java.classLoader
        while (loader != null) {
            if (loader is java.net.URLClassLoader) loader.urLs.filter { it.protocol == "file" }.forEach { add(Path.of(it.toURI()).toString()) }
            loader = loader.parent
        }
        listOf(KernelPlanCrashChild::class.java, KernelService::class.java, AnalysisInput::class.java,
            kotlin.Unit::class.java, kotlinx.serialization.KSerializer::class.java, kotlinx.serialization.json.Json::class.java,
            Class.forName("org.sqlite.JDBC")).forEach { add(Path.of(it.protectionDomain.codeSource.location.toURI()).toString()) }
    }.joinToString(java.io.File.pathSeparator)

    @Test fun completeVectorNeedsParentEventObservationAndOneJointFenceSpansTheGuardedAction() {
        val inputs = inputs("vector")
        assertEquals(2, inputs.map { it.context.dependencyDigest }.distinct().size)
        assertEquals(2, inputs.map { it.context.toolchainDigest }.distinct().size)
        assertEquals(2, inputs.map { it.context.configurationDigest }.distinct().size)
        val registry = Registry()
        val fence = VectorFence()
        KernelService.open(temp.resolve("ledger"), PlanTestProvider(), config, fence, planAuthority = registry).use { service ->
            val client = service.registerClient(binding("vector"))
            val request = request("vector")
            val definition = definition(client.binding, request, inputs)
            registry.put(definition); fence.install(definition)
            val plan = service.submitPlan(client.client, request)
            assertEquals("complete", awaitPlan(service, client, plan.planId).state)
            val parentEvent = events(service, client).single { it.kind == "plan_accepted" }
            deliver(service, client) { it.kind != "plan_accepted" }
            val missing = service.prepareGate(client.client)
            assertFalse(missing.ready)
            assertTrue("insertion_missing:${parentEvent.eventId}" in missing.reasons)
            assertTrue("model_observation_missing:${parentEvent.eventId}" in missing.reasons)
            deliver(service, client)
            val cut = service.prepareGate(client.client)
            assertTrue(cut.ready, cut.reasons.toString())
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val changed = CountDownLatch(1)
            val check = fence.current()
            val nonAnchor = check.copy(contextIdentities = check.contextIdentities.mapIndexed { index, identity ->
                if (index == 0) identity.copy(dependencyDigest = Digests.sha256("changed")) else identity
            })
            val calls = AtomicInteger()
            val pool = Executors.newFixedThreadPool(2)
            try {
                val action = pool.submit<GateResult> { service.completeGate(client.client, cut.cutId) {
                    calls.incrementAndGet(); entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS))
                } }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                pool.submit { fence.set(nonAnchor); changed.countDown() }
                assertFalse(changed.await(75, TimeUnit.MILLISECONDS), "The non-anchor context's authority must stay fenced too")
                release.countDown()
                assertTrue(action.get(5, TimeUnit.SECONDS).actionPerformed)
                assertTrue(changed.await(5, TimeUnit.SECONDS))
                assertFalse(service.completeGate(client.client, cut.cutId) { calls.incrementAndGet() }.allowed)
                assertEquals(1, calls.get())
                assertEquals(0, fence.scalarCalls.get())
                assertTrue(fence.vectorCalls.get() >= 3)
            } finally { release.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun scalarOnlyAdaptersRejectMultipleContextsAndDoNotInventSingleContextMembershipAttestation() {
        for (count in listOf(1, 2)) {
            val inputs = inputs("scalar-$count", count)
            val registry = Registry()
            val calls = AtomicInteger()
            val scalar = object : SnapshotGate {
                override fun <T> withCurrentSnapshot(input: AnalysisInput, action: (SnapshotCheck) -> T): T {
                    calls.incrementAndGet()
                    val trusted = inputs.last()
                    return action(SnapshotCheck(true, trusted.snapshot.snapshotId, 1, trusted.context.configurationDigest,
                        trusted.context.modelDigest, trusted.context.dependencyDigest, trusted.context.toolchainDigest,
                        "synthetic-scalar", authoritative = true))
                }
            }
            KernelService.open(temp.resolve("ledger-$count"), PlanTestProvider(), config, scalar, planAuthority = registry).use { service ->
                val client = service.registerClient(binding("scalar-$count"))
                val request = request("scalar-$count")
                registry.put(definition(client.binding, request, inputs))
                val plan = service.submitPlan(client.client, request)
                assertEquals("complete", awaitPlan(service, client, plan.planId).state)
                deliver(service, client)
                val cut = service.prepareGate(client.client)
                assertFalse(cut.ready)
                assertTrue("plan_context_vector_authority_mismatch" in cut.reasons)
                if (count == 2) {
                    assertEquals(0, calls.get(), "The vector default must not loop independent scalar fences")
                    assertTrue("snapshot_vector_authority_unavailable" in cut.reasons)
                } else assertEquals(1, calls.get())
                assertFalse(service.completeGate(client.client, cut.cutId).allowed)
            }
        }
    }

    @Test fun missingDuplicatedAndChangedNonAnchorIdentitiesInvalidateTheCutEvenWhenScalarEpochsDoNotChange() {
        val inputs = inputs("identity-vector")
        val registry = Registry()
        val fence = VectorFence()
        KernelService.open(temp.resolve("ledger"), PlanTestProvider(), config, fence, planAuthority = registry).use { service ->
            val client = service.registerClient(binding("identity-vector"))
            val request = request("identity-vector")
            val definition = definition(client.binding, request, inputs)
            registry.put(definition); fence.install(definition)
            val plan = service.submitPlan(client.client, request)
            assertEquals("complete", awaitPlan(service, client, plan.planId).state)
            deliver(service, client)
            val cut = service.prepareGate(client.client)
            assertTrue(cut.ready, cut.reasons.toString())
            val baseline = fence.current()
            val first = baseline.contextIdentities.first()
            val different = Digests.sha256("different-identity")
            val changedIdentities = listOf(
                first.copy(context = first.context.copy(module = ":unexpected")),
                first.copy(modelDigest = different), first.copy(dependencyDigest = different), first.copy(toolchainDigest = different),
                first.copy(compilerVersion = "not-the-registered-compiler"), first.copy(compilerArgumentsDigest = different),
                first.copy(classpathDigest = different), first.copy(librariesDigest = different), first.copy(policiesDigest = different),
                first.copy(compilationFilesDigest = different), first.copy(configurationDigest = different),
            )
            val mutations = changedIdentities.map { identity ->
                baseline.copy(contextIdentities = listOf(identity, baseline.contextIdentities.last()))
            } + listOf(
                baseline.copy(contextIdentities = baseline.contextIdentities.drop(1)),
                baseline.copy(contextIdentities = baseline.contextIdentities + first),
                baseline.copy(sourceBindingDigest = different), baseline.copy(generatedInputsDigest = different),
            )
            var actionCalls = 0
            mutations.forEach { changed ->
                assertEquals(baseline.sourceEpoch, changed.sourceEpoch)
                assertEquals(baseline.analysisEpoch, changed.analysisEpoch)
                assertEquals(baseline.configurationDigest, changed.configurationDigest)
                assertEquals(baseline.dependencyDigest, changed.dependencyDigest)
                fence.set(changed)
                val result = service.completeGate(client.client, cut.cutId) { actionCalls++ }
                assertFalse(result.allowed, changed.toString())
                assertTrue("authority_fence_changed" in result.reasons, result.reasons.toString())
                assertFalse(service.prepareGate(client.client).ready)
            }
            assertEquals(0, actionCalls)
            fence.set(baseline)
            assertTrue(service.completeGate(client.client, cut.cutId) { actionCalls++ }.actionPerformed)
            assertEquals(1, actionCalls)
        }
    }

    @Test fun malformedControllerEntriesCannotRetargetOwnerPatchSnapshotRootOrCompilationIdentity() {
        val inputs = inputs("malformed")
        val registry = Registry()
        KernelService.open(temp.resolve("ledger"), PlanTestProvider(), config.copy(workerCount = 0), planAuthority = registry).use { service ->
            val client = service.registerClient(binding("malformed"))
            val request = request("malformed")
            val good = definition(client.binding, request, inputs)
            fun slot(change: (RequiredPlanSlot) -> RequiredPlanSlot): RequiredReviewPlan =
                good.copy(slots = listOf(change(good.slots.first()), good.slots.last()))
            fun lastSlot(change: (RequiredPlanSlot) -> RequiredPlanSlot): RequiredReviewPlan =
                good.copy(slots = listOf(good.slots.first(), change(good.slots.last())))
            fun context(change: (AnalysisContext) -> AnalysisContext): RequiredReviewPlan =
                slot { it.copy(input = it.input.copy(context = change(it.input.context))) }
            fun snapshot(change: (SnapshotManifest) -> SnapshotManifest): RequiredReviewPlan {
                val manifest = change(good.slots.first().input.snapshot)
                return good.copy(sourceBindingDigest = Digests.sha256(canonical(manifest)),
                    slots = good.slots.map { it.copy(input = it.input.copy(snapshot = manifest)) })
            }
            val artifact = ArtifactIdentity("synthetic:artifact:1", Digests.sha256("artifact"), temp.resolve("unused.jar").toString())
            val malformed = listOf(
                "plan_binding_mismatch" to good.copy(binding = good.binding.copy(ownerId = "another-owner")),
                "plan_binding_mismatch" to good.copy(binding = good.binding.copy(generation = 2)),
                "plan_binding_mismatch" to good.copy(binding = good.binding.copy(lineageId = "another-lineage")),
                "plan_request_mismatch" to good.copy(request = request.copy(patchId = "another-patch")),
                "plan_request_mismatch" to good.copy(request = request.copy(idempotencyKey = "another-key")),
                "plan_request_mismatch" to good.copy(request = request.copy(planRef = "another-ref")),
                "plan_source_binding_mismatch" to good.copy(sourceBindingDigest = Digests.sha256("other-manifest")),
                "plan_identity_invalid" to good.copy(generatedInputsDigest = "unverified"),
                "plan_identity_invalid" to good.copy(modelCertificateId = "unverified"),
                "plan_identity_invalid" to good.copy(impactCertificateId = "unverified"),
                "plan_snapshot_mismatch" to slot { it.copy(input = it.input.copy(materializedRoot = temp.resolve("another-root").toString())) },
                // The first manifest is checked against the source binding before comparing slots.
                "plan_source_binding_mismatch" to slot { it.copy(input = it.input.copy(snapshot = it.input.snapshot.copy(declaredRoots = listOf("different")))) },
                // A later divergent manifest must also fail when the first manifest/binding is valid.
                "plan_snapshot_mismatch" to lastSlot { it.copy(input = it.input.copy(snapshot = it.input.snapshot.copy(declaredRoots = listOf("different")))) },
                "snapshot_scope_mismatch" to snapshot { it.copy(worktreeId = "other-tree") },
                "invalid_snapshot" to snapshot { it.copy(snapshotId = "not-sealed") },
                "plan_snapshot_not_canonical" to snapshot { it.copy(files = it.files.reversed()) },
                "plan_context_identity_mismatch" to snapshot { it.copy(unknownInputs = listOf("uncaptured-generated")) },
                "plan_context_identity_mismatch" to snapshot { it.copy(scopeExclusions = listOf("excluded-root")) },
                "plan_context_identity_mismatch" to slot { it.copy(identity = null) },
                "plan_context_identity_mismatch" to context { it.copy(fullProject = false) },
                "plan_context_identity_mismatch" to context { it.copy(compilerVersion = null) },
                "plan_context_identity_mismatch" to context { it.copy(compilerVersion = "another-compiler") },
                "plan_context_identity_mismatch" to context { it.copy(compilerArguments = it.compilerArguments.reversed()) },
                "plan_context_identity_mismatch" to context { it.copy(classpath = listOf(artifact)) },
                "plan_context_identity_mismatch" to context { it.copy(libraries = mapOf("kotlin" to "0")) },
                "plan_context_identity_mismatch" to context { it.copy(policies = mapOf(PLAN_TEST_RULE to "disabled")) },
                "plan_context_identity_mismatch" to context { it.copy(compilationFiles = null) },
                "plan_context_identity_mismatch" to context { it.copy(compilationFiles = listOf("missing.kt")) },
                "plan_context_identity_mismatch" to context { it.copy(compilationFiles = it.compilationFiles!!.reversed()) },
                "plan_context_identity_mismatch" to context { it.copy(compilationFiles = it.compilationFiles!! + it.compilationFiles!!.first()) },
                "plan_rule_inventory_empty" to slot { it.copy(input = it.input.copy(requiredRuleIds = emptyList())) },
                "plan_slot_state_invalid" to slot { it.copy(state = "complete") },
                "plan_slot_state_invalid" to slot { it.copy(state = "unsupported", reasons = emptyList()) },
                "plan_slot_state_invalid" to slot { it.copy(reasons = listOf("unavailable")) },
                "plan_slots_invalid" to good.copy(slots = emptyList()),
                "plan_slots_invalid" to good.copy(slots = listOf(good.slots.first(), good.slots.first())),
                "plan_context_duplicate" to good.copy(slots = listOf(good.slots.first(), good.slots.first().copy(slotId = "other-slot"))),
            )
            malformed.forEach { (code, bad) ->
                registry.definitions[request.planRef] = bad
                assertCode(code) { service.submitPlan(client.client, request) }
                assertNoAcceptance(service, client)
            }
        }
    }

    @Test fun crossClientRequiredRecipientsAndAbsentTrustedAuthorityFailBeforeAnyAcceptance() {
        val inputs = inputs("recipients")
        val registry = Registry()
        val database = temp.resolve("ledger")
        KernelService.open(database, PlanTestProvider(), config.copy(workerCount = 0), planAuthority = registry).use { service ->
            val client = service.registerClient(binding("recipient"))
            val request = request("recipients")
            val good = definition(client.binding, request, inputs)
            registry.put(good.copy(requiredRecipientClientIds = listOf(client.binding.clientId, "other-client").sorted()))
            assertCode("cross_client_routing_unavailable") { service.submitPlan(client.client, request) }
            assertNoAcceptance(service, client)
            KernelService.open(database, PlanTestProvider(), config.copy(workerCount = 0)).use { noAuthority ->
                assertCode("plan_authority_unavailable") { noAuthority.submitPlan(client.client, request) }
                assertNoAcceptance(noAuthority, client)
            }
        }
    }

    @Test fun malformedRecipientVectorsMissingInitiatorEmptyOrDuplicatedFailBeforeRoutingAuthorityOrAcceptance() {
        val inputs = inputs("malformed-recipients")
        val registry = Registry()
        val routingCalls = AtomicInteger()
        val routing = ReviewPlanRoutingAuthority { _, _, _ -> routingCalls.incrementAndGet(); null }
        KernelService.open(temp.resolve("ledger"), PlanTestProvider(), config.copy(workerCount = 0),
            planAuthority = registry, routingAuthority = routing).use { service ->
            val client = service.registerClient(binding("recipient"))
            val request = request("malformed-recipients")
            val good = definition(client.binding, request, inputs)
            listOf(listOf("other-client"), emptyList(), listOf(client.binding.clientId, client.binding.clientId)).forEach { recipients ->
                registry.put(good.copy(requiredRecipientClientIds = recipients))
                assertCode("plan_recipients_invalid") { service.submitPlan(client.client, request) }
                assertNoAcceptance(service, client)
            }
            assertEquals(0, routingCalls.get())
        }
    }

    @Test fun unsupportedAndRemovedRequiredSlotsStayVisibleNeverExecuteOrGetCoverageCertificatesAndCannotBeCancelledAway() {
        val inputs = inputs("unsupported")
        val registry = Registry()
        val provider = PlanTestProvider()
        val fence = VectorFence()
        KernelService.open(temp.resolve("ledger"), provider, config, fence, planAuthority = registry).use { service ->
            val client = service.registerClient(binding("unsupported"))
            val request = request("unsupported")
            val good = definition(client.binding, request, inputs)
            fun unsupported(slot: RequiredPlanSlot) = slot.copy(state = "unsupported", identity = null,
                input = slot.input.copy(context = slot.input.context.copy(compilerVersion = null, modelDigest = null),
                    requiredRuleIds = listOf("missing-provider/required-rule")), reasons = listOf("context_removed_unresolved"))
            val mixed = good.copy(slots = listOf(good.slots.first(), unsupported(good.slots.last())))
            registry.put(mixed); fence.install(mixed)
            val accepted = service.submitPlan(client.client, request)
            val done = awaitPlan(service, client, accepted.planId)
            assertEquals("incomplete", done.state)
            val absent = done.slots.last()
            assertEquals("unsupported", absent.state)
            assertTrue("context_removed_unresolved" in absent.reasons)
            val failure = service.status(client.client, absent.reviewId).result!!
            assertEquals("incomplete", failure.status)
            assertTrue(failure.coverage.isEmpty())
            assertEquals(1, provider.calls.get())
            assertEquals(listOf(inputs.first().context.scope), provider.scopes.toList())
            assertEquals(1L, counts(service).getValue("work_certificates"))
            deliver(service, client)
            assertFalse(service.prepareGate(client.client).ready)
            service.cancel(client.client, absent.reviewId)
            val childCancelled = service.planStatus(client.client, accepted.planId)
            assertEquals("cancelled", childCancelled.state)
            assertEquals("cancelled", childCancelled.slots.last().state)
            assertTrue("context_removed_unresolved" in childCancelled.slots.last().reasons)
            assertEquals("cancelled", service.cancelPlan(client.client, accepted.planId).state)
            assertEquals("cancelled", service.cancelPlan(client.client, accepted.planId).state)
            assertEquals(2, events(service, client).count { it.kind == "cancelled" })
            deliver(service, client)
            assertTrue(service.prepareGate(client.client).reasons.any { it.startsWith("parent_plan_cancelled:") })

            val allUnsupported = good.copy(request = this.request("all-unsupported"), slots = good.slots.map(::unsupported))
            registry.put(allUnsupported); fence.install(allUnsupported)
            val all = service.submitPlan(client.client, allUnsupported.request)
            assertEquals("incomplete", all.state)
            assertTrue(all.slots.all { it.state == "unsupported" })
            assertEquals(1, provider.calls.get())
            assertEquals(1L, counts(service).getValue("work_certificates"))
            assertEquals(4L, counts(service).getValue("reviews"), "Every required unsupported obligation remains retained")
        }
    }

    @Test fun sharedWorkNeverSharesCancellationPlanPermissionsOrObservationDuties() {
        val inputs = inputs("shared")
        val registry = Registry()
        val fence = VectorFence()
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val provider = PlanTestProvider { input, cancellation ->
            entered.countDown()
            assertTrue(release.await(10, TimeUnit.SECONDS))
            assertFalse(cancellation.isCancelled())
            planPassed(input)
        }
        KernelService.open(temp.resolve("ledger"), provider, config, fence, planAuthority = registry).use { service ->
            val a = service.registerClient(binding("a"))
            val b = service.registerClient(binding("b"))
            val ad = definition(a.binding, request("shared-a"), inputs)
            val bd = definition(b.binding, request("shared-b"), inputs)
            registry.put(ad); registry.put(bd); fence.install(bd)
            try {
                val ap = service.submitPlan(a.client, ad.request)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val bp = service.submitPlan(b.client, bd.request)
                assertCode("plan_not_found") { service.planStatus(a.client, bp.planId) }
                assertCode("plan_not_found") { service.cancelPlan(a.client, bp.planId) }
                assertEquals("cancelled", service.cancelPlan(a.client, ap.planId).state)
                release.countDown()
                assertEquals("cancelled", awaitPlan(service, a, ap.planId).state)
                assertEquals("complete", awaitPlan(service, b, bp.planId).state)
                assertEquals(2, provider.calls.get())
                assertEquals(2L, counts(service).getValue("work"))
                assertEquals(4L, counts(service).getValue("reviews"))
                val aEvents = events(service, a)
                val bEvents = events(service, b)
                assertTrue(aEvents.map { it.eventId }.intersect(bEvents.map { it.eventId }.toSet()).isEmpty())
                assertCode("event_not_found") { service.acknowledge(a.adapter, listOf(bEvents.first().eventId),
                    ContextReceipt("foreign", Digests.sha256("foreign"), "item", Instant.EPOCH.toString())) }
                deliver(service, b)
                val bCut = service.prepareGate(b.client)
                assertTrue(bCut.ready, bCut.reasons.toString())
                assertTrue(service.completeGate(b.client, bCut.cutId).allowed)
                val aCut = service.prepareGate(a.client)
                assertTrue(aCut.reasons.any { it.startsWith("insertion_missing:") })
                deliver(service, a)
                assertTrue(service.prepareGate(a.client).reasons.any { it.startsWith("parent_plan_cancelled:") })
            } finally { release.countDown() }
        }
    }

    @Test fun perConsumerPoliciesUseEachContextsConfigurationNotAnotherClientsOrTheScalarAnchor() {
        val inputs = inputs("policies")
        val registry = Registry()
        val fence = VectorFence()
        val provider = PlanTestProvider { input, _ ->
            val file = input.snapshot.files.first()
            val finding = Finding(PLAN_TEST_RULE, "Synthetic", "Synthetic defect", "Test only",
                SourceLocation(file.path, file.digest), Digests.sha256("finding:${input.context.scope.id}"), PLAN_TEST_PROVIDER,
                context = input.context.scope)
            planPassed(input, listOf(finding))
        }
        val policy = ResultPolicy { binding, _, raw ->
            if (binding.clientId == "approved") raw.copy(findings = raw.findings.map { it.copy(disposition = "accepted") }) else raw
        }
        KernelService.open(temp.resolve("ledger"), provider, config, fence, resultPolicy = policy, planAuthority = registry).use { service ->
            val a = service.registerClient(binding("approved"))
            val b = service.registerClient(binding("unapproved"))
            val ad = definition(a.binding, request("policy-a"), inputs)
            val bd = definition(b.binding, request("policy-b"), inputs)
            registry.put(ad); registry.put(bd); fence.install(bd)
            val ap = service.submitPlan(a.client, ad.request)
            val bp = service.submitPlan(b.client, bd.request)
            assertEquals("complete", awaitPlan(service, a, ap.planId).state)
            assertEquals("complete", awaitPlan(service, b, bp.planId).state)
            assertEquals(2, provider.calls.get())
            ap.slots.forEach { assertEquals("accepted", service.status(a.client, it.reviewId).result!!.findings.single().disposition) }
            bp.slots.forEach { assertEquals("open", service.status(b.client, it.reviewId).result!!.findings.single().disposition) }
            deliver(service, a); deliver(service, b)
            val aCut = service.prepareGate(a.client)
            assertTrue(aCut.ready, aCut.reasons.toString())
            assertTrue(service.completeGate(a.client, aCut.cutId).allowed)
            assertTrue(service.prepareGate(b.client).reasons.any { it.startsWith("open_finding:") })
        }
    }

    @Test fun wholePlanQueuePendingReviewArchiveAndMetadataLimitsLeaveNoAcceptedPrefix() {
        val inputs = inputs("limits")
        val byteCount = inputs.first().snapshot.files.sumOf { it.size }
        val limits = listOf(
            "queue_full" to config.copy(workerCount = 0, maxQueuedWork = 1),
            "client_queue_full" to config.copy(workerCount = 0, maxPendingPerClient = 1),
            "ledger_full" to config.copy(workerCount = 0, maxRetainedReviews = 1),
            "archive_full" to config.copy(workerCount = 0, maxArchiveBytes = byteCount * 2 - 1),
            "input_limit" to config.copy(workerCount = 0, maxInputBytes = 512),
            "input_limit" to config.copy(workerCount = 0, maxResultBytes = 512),
        )
        limits.forEachIndexed { index, (code, limited) ->
            val registry = Registry()
            val provider = PlanTestProvider()
            KernelService.open(temp.resolve("ledger-$index"), provider, limited, planAuthority = registry).use { service ->
                val client = service.registerClient(binding("limit-$index"))
                val request = request("limit-$index")
                registry.put(definition(client.binding, request, inputs))
                assertCode(code) { service.submitPlan(client.client, request) }
                assertNoAcceptance(service, client)
                assertEquals(0, provider.calls.get())
            }
        }
    }

    @Test fun exactPlanIdempotencySurvivesResolverLossAndManualEndpointsCannotReuseItsKeysOrPatch() {
        val inputs = inputs("idempotency")
        val registry = Registry()
        val database = temp.resolve("ledger")
        lateinit var client: ClientRegistration
        lateinit var accepted: ParentPlanStatus
        val request = request("idempotency")
        KernelService.open(database, PlanTestProvider(), config.copy(workerCount = 0), planAuthority = registry).use { service ->
            client = service.registerClient(binding("idempotency"))
            val definition = definition(client.binding, request, inputs)
            registry.put(definition)
            accepted = service.submitPlan(client.client, request)
            registry.definitions.clear()
            assertEquals(accepted.planId, service.submitPlan(client.client, request).planId)
            assertEquals(1, registry.calls.get())
            assertCode("idempotency_conflict") { service.submitPlan(client.client, request.copy(patchId = "other")) }
            assertCode("idempotency_conflict") { service.submitPlan(client.client, request.copy(planRef = "other")) }
            val samePatch = request.copy(idempotencyKey = "new-key")
            registry.put(definition.copy(request = samePatch))
            assertCode("patch_id_conflict") { service.submitPlan(client.client, samePatch) }
            assertCode("idempotency_conflict") { service.submit(client.client, SubmitRequest("manual", request.idempotencyKey, inputs.first())) }
            assertCode("patch_id_conflict") { service.submit(client.client, SubmitRequest(request.patchId, "manual", inputs.first())) }

            val manual = service.registerClient(binding("manual"))
            service.submit(manual.client, SubmitRequest("manual-patch", "manual-key", inputs.first()))
            val duplicateKey = SubmitPlanRequest("another-patch", "manual-key", "manual-ref")
            registry.put(this.definition(manual.binding, duplicateKey, inputs))
            assertCode("idempotency_conflict") { service.submitPlan(manual.client, duplicateKey) }
            val duplicatePatch = duplicateKey.copy(patchId = "manual-patch", idempotencyKey = "another-key")
            registry.put(this.definition(manual.binding, duplicatePatch, inputs))
            assertCode("patch_id_conflict") { service.submitPlan(manual.client, duplicatePatch) }
            assertEquals(1L, counts(service).getValue("parent_plans"))
            assertEquals(3L, counts(service).getValue("reviews"))
        }
        KernelService.open(database, PlanTestProvider(), config.copy(workerCount = 0)).use { recovered ->
            assertEquals(accepted, recovered.submitPlan(client.client, request))
        }
    }

    @Test fun concurrentExactParentRetriesAcrossServiceInstancesHaveOnePlanAndNoSequenceGaps() {
        val inputs = inputs("concurrent")
        val registry = Registry()
        val database = temp.resolve("ledger")
        val config = config.copy(workerCount = 0)
        KernelService.open(database, PlanTestProvider(), config, planAuthority = registry).use { a ->
            KernelService.open(database, PlanTestProvider(), config, planAuthority = registry).use { b ->
                val client = a.registerClient(binding("concurrent"))
                val request = request("concurrent")
                registry.put(definition(client.binding, request, inputs))
                val pool = Executors.newFixedThreadPool(8)
                try {
                    val plans = pool.invokeAll((0 until 24).map { index -> Callable {
                        val service = if (index % 2 == 0) a else b
                        var accepted: ParentPlanStatus? = null
                        for (attempt in 0 until 1000) {
                            try { accepted = service.submitPlan(client.client, request); break }
                            catch (e: KernelException) { if (e.code != "ingestion_busy") throw e; Thread.sleep(2) }
                        }
                        assertNotNull(accepted)
                    } }).map { it.get() }
                    assertEquals(1, plans.map { it.planId }.distinct().size)
                    assertEquals(1L, counts(a).getValue("parent_plans"))
                    assertEquals(2L, counts(a).getValue("reviews"))
                    assertEquals(2L, counts(a).getValue("work"))
                    assertEquals(listOf(1L, 2L, 3L), events(a, client).map { it.sequence })
                    assertEquals(listOf(1L, 2L), plans.first().slots.map { a.status(client.client, it.reviewId).sequence })
                } finally { pool.shutdownNow() }
            }
        }
    }

    @Test fun missingDurableSlotLinkInvalidatesAnExistingReadyCutInsteadOfInferringCompletionFromTheChildren() {
        val inputs = inputs("missing-link")
        val registry = Registry()
        val fence = VectorFence()
        KernelService.open(temp.resolve("ledger"), PlanTestProvider(), config, fence, planAuthority = registry).use { service ->
            val client = service.registerClient(binding("missing-link"))
            val request = request("missing-link")
            val definition = definition(client.binding, request, inputs)
            registry.put(definition); fence.install(definition)
            val plan = service.submitPlan(client.client, request)
            assertEquals("complete", awaitPlan(service, client, plan.planId).state)
            deliver(service, client)
            val cut = service.prepareGate(client.client)
            assertTrue(cut.ready, cut.reasons.toString())
            service.ledger.transaction { c ->
                c.execute("DELETE FROM plan_slots WHERE plan_id=? AND slot_id=?", plan.planId, plan.slots.first().slotId)
            }
            val corrupt = service.planStatus(client.client, plan.planId)
            assertEquals("incomplete", corrupt.state)
            assertTrue("required_plan_slot_missing" in corrupt.slots.first().reasons)
            assertEquals(2L, counts(service).getValue("reviews"))
            var calls = 0
            val decision = service.completeGate(client.client, cut.cutId) { calls++ }
            assertFalse(decision.allowed)
            assertTrue("parent_plan_incomplete:${plan.planId}" in decision.reasons)
            assertEquals(0, calls)
        }
    }

    @Test fun laterNarrowerPlansAndNewManualSnapshotsCannotWaiveAnOlderAcceptedContext() {
        val inputs = inputs("retained-contexts")
        val registry = Registry()
        val fence = VectorFence()
        KernelService.open(temp.resolve("ledger"), PlanTestProvider(), config, fence, planAuthority = registry).use { service ->
            val client = service.registerClient(binding("retained-contexts"))
            val first = definition(client.binding, request("wide"), inputs)
            registry.put(first); fence.install(first)
            val wide = service.submitPlan(client.client, first.request)
            assertEquals("complete", awaitPlan(service, client, wide.planId).state)
            deliver(service, client)
            assertTrue(service.prepareGate(client.client).ready)
            val narrow = definition(client.binding, request("narrow"), inputs.take(1))
            registry.put(narrow); fence.install(narrow)
            val narrowed = service.submitPlan(client.client, narrow.request)
            assertEquals("complete", awaitPlan(service, client, narrowed.planId).state)
            deliver(service, client)
            val cut = service.prepareGate(client.client)
            assertFalse(cut.ready)
            assertTrue(cut.reasons.any { it.startsWith("current_coverage_missing:${inputs.last().context.scope.id}") })

            val newer = this.inputs("newer-manual", parent = inputs.first().snapshot.snapshotId)
            fence.install(definition(client.binding, request("not-submitted"), newer))
            val manual = service.submit(client.client, SubmitRequest("manual-newer", "manual-newer", newer.last()))
            assertEquals("complete", awaitReview(service, client, manual.reviewId).state)
            deliver(service, client)
            assertTrue("current_parent_plan_missing" in service.prepareGate(client.client).reasons)
        }
    }

    @Test fun laterManualInputsCannotBorrowTheOlderVectorEvenOnTheSameSnapshotWithUnchangedScalarDigests() {
        val inputs = inputs("same-snapshot-manual")
        val original = inputs.last()
        val artifactPath = temp.resolve("manual-artifact.jar")
        Files.writeString(artifactPath, "synthetic artifact bytes, not a compiler fixture")
        val artifact = ArtifactIdentity("synthetic:manual:1", Digests.sha256(Files.readAllBytes(artifactPath)), artifactPath.toString())
        val contexts = listOf(
            original.context.copy(compilerArguments = original.context.compilerArguments + "-Xsynthetic-option"),
            original.context.copy(classpath = listOf(artifact)),
            original.context.copy(policies = mapOf(PLAN_TEST_RULE to "different-policy")),
            original.context.copy(libraries = mapOf("kotlin" to "different-library")),
            original.context, // Even identical manual refreshes need an explicit new parent frontier.
        )
        val registry = Registry()
        val fence = VectorFence()
        KernelService.open(temp.resolve("ledger"), PlanTestProvider(), config, fence, planAuthority = registry).use { service ->
            contexts.forEachIndexed { index, context ->
                val client = service.registerClient(binding("same-snapshot-$index"))
                val definition = definition(client.binding, request("same-snapshot-$index"), inputs)
                registry.put(definition); fence.install(definition)
                val plan = service.submitPlan(client.client, definition.request)
                assertEquals("complete", awaitPlan(service, client, plan.planId).state)
                deliver(service, client)
                assertTrue(service.prepareGate(client.client).ready)
                val modified = original.copy(context = context)
                assertEquals(original.snapshot, modified.snapshot)
                assertEquals(original.context.configurationDigest, modified.context.configurationDigest)
                assertEquals(original.context.dependencyDigest, modified.context.dependencyDigest)
                assertEquals(original.context.modelDigest, modified.context.modelDigest)
                assertEquals(original.context.toolchainDigest, modified.context.toolchainDigest)
                val review = service.submit(client.client, SubmitRequest("manual-$index", "manual-$index", modified))
                assertEquals("complete", awaitReview(service, client, review.reviewId).state)
                deliver(service, client)
                val cut = service.prepareGate(client.client)
                assertFalse(cut.ready)
                assertTrue("current_parent_plan_missing" in cut.reasons, cut.reasons.toString())
                var calls = 0
                assertFalse(service.completeGate(client.client, cut.cutId) { calls++ }.allowed)
                assertEquals(0, calls)

                val refreshed = definition.copy(request = request("refreshed-$index"))
                registry.put(refreshed)
                val fresh = service.submitPlan(client.client, refreshed.request)
                assertEquals("complete", awaitPlan(service, client, fresh.planId).state)
                deliver(service, client)
                val current = service.prepareGate(client.client)
                assertTrue(current.ready, current.reasons.toString())
            }
        }
    }

    @Test fun corruptStoredDefinitionOrDigestCannotSupplyStatusIdempotencyOrGateAuthorityAfterRestart() {
        val inputs = inputs("corrupt-definition")
        val registry = Registry()
        val fence = VectorFence()
        val database = temp.resolve("ledger")
        lateinit var client: ClientRegistration
        lateinit var accepted: ParentPlanStatus
        val request = request("corrupt-definition")
        KernelService.open(database, PlanTestProvider(), config, fence, planAuthority = registry).use { service ->
            client = service.registerClient(binding("corrupt-definition"))
            val definition = definition(client.binding, request, inputs)
            registry.put(definition); fence.install(definition)
            accepted = service.submitPlan(client.client, request)
            assertEquals("complete", awaitPlan(service, client, accepted.planId).state)
            deliver(service, client)
            val cut = service.prepareGate(client.client)
            assertTrue(cut.ready, cut.reasons.toString())
            val stored = service.ledger.read { c -> c.one("SELECT definition_json,plan_digest FROM parent_plans WHERE plan_id=?", accepted.planId) {
                it.getString(1) to it.getString(2)
            }!! }
            val differentDefinition = definition.copy(generatedInputsDigest = Digests.sha256("different-generated"))
            val truncated = stored.first.dropLast(3)
            val corruptions = listOf(
                canonical(differentDefinition) to stored.second,
                stored.first to Digests.sha256("different-definition-digest"),
                truncated to stored.second,
                truncated to Digests.sha256(truncated),
            )
            corruptions.forEach { (json, digest) ->
                service.ledger.transaction { c -> c.execute("UPDATE parent_plans SET definition_json=?,plan_digest=? WHERE plan_id=?", json, digest, accepted.planId) }
                assertCode("plan_corrupt") { service.planStatus(client.client, accepted.planId) }
                assertCode("plan_corrupt") { service.submitPlan(client.client, request) }
                assertCode("plan_corrupt") { service.prepareGate(client.client) }
                var calls = 0
                assertCode("plan_corrupt") { service.completeGate(client.client, cut.cutId) { calls++ } }
                assertEquals(0, calls)
            }
            assertEquals(2L, counts(service).getValue("reviews"))
            assertEquals(2L, counts(service).getValue("work_certificates"))
        }
        KernelService.open(database, PlanTestProvider(), config, fence).use { recovered ->
            assertCode("plan_corrupt") { recovered.planStatus(client.client, accepted.planId) }
            assertCode("plan_corrupt") { recovered.submitPlan(client.client, request) }
            assertCode("plan_corrupt") { recovered.prepareGate(client.client) }
        }
    }

    @Test fun olderScalarPayloadsWithoutOptionalParentOrVectorFieldsStillDecodeWithoutInventingAuthority() {
        val status = ReviewStatus("review", "client", "patch", 1, Digests.sha256("source"), "queued")
        val encoded = ContractJson.parseToJsonElement(canonical(status)) as JsonObject
        assertFalse("parentPlan" in encoded, "Default null must not add an unknown field to the legacy scalar wire shape")
        val oldStatus = JsonObject(encoded.filterKeys { it != "parentPlan" })
        assertEquals(status, ContractJson.decodeFromString<ReviewStatus>(oldStatus.toString()))
        assertNull(ContractJson.decodeFromString<ReviewStatus>(oldStatus.toString()).parentPlan)
        val check = SnapshotCheck(true, snapshotId = Digests.sha256("source"), sourceEpoch = 1, analysisEpoch = "legacy-scalar", authoritative = true)
        val oldCheck = JsonObject((ContractJson.parseToJsonElement(canonical(check)) as JsonObject)
            .filterKeys { it !in setOf("contextIdentities", "sourceBindingDigest", "generatedInputsDigest") })
        val decoded = ContractJson.decodeFromString<SnapshotCheck>(oldCheck.toString())
        assertEquals(check, decoded)
        assertTrue(decoded.contextIdentities.isEmpty())
        assertNull(decoded.sourceBindingDigest)
        assertNull(decoded.generatedInputsDigest)
    }
}

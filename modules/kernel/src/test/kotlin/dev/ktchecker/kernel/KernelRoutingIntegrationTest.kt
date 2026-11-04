package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Real SQLite/files/threads. Every model, approval, policy and provider authority here is SYNTHETIC. */
class KernelRoutingIntegrationTest {
    @TempDir lateinit var temp: Path
    private val config = KernelConfig(workerCount = 0, globalParallelism = 2, perWorktreeParallelism = 2,
        heartbeatMillis = 40, leaseMillis = 160, pollMillis = 5, analysisTimeoutMillis = 15_000)

    private fun binding(id: String) = ClientBinding("owner", "workspace", "tree", id, "lineage-$id", 1, "session-$id")
    private fun request(id: String) = SubmitPlanRequest("patch-$id", "key-$id", "ref-$id")

    private fun inputs(name: String, count: Int = 2, tree: String = "tree"): List<AnalysisInput> {
        val root = temp.resolve("source-$name")
        Files.createDirectories(root)
        val text = "package fixture\nval value = 1\n"
        Files.writeString(root.resolve("Main.kt"), text)
        val files = listOf(SnapshotFile("Main.kt", Digests.sha256(text), text.toByteArray().size.toLong()))
        val snapshot = SnapshotManifest(Digests.sha256("synthetic-routed-source:$name"), "workspace", tree, files,
            ownerId = "owner", declaredRoots = listOf("."))
        return (0 until count).map { index -> AnalysisInput(snapshot, root.toString(), AnalysisContext(
            scope = RuleContext(module = ":module-$index"), compilerVersion = "synthetic-compiler",
            compilerArguments = listOf("-module-name", "module-$index"), policies = mapOf(PLAN_TEST_RULE to "synthetic-profile-$index"),
            modelDigest = Digests.sha256("synthetic-model"), dependencyDigest = Digests.sha256("synthetic-dependencies-$index"),
            toolchainDigest = Digests.sha256("synthetic-toolchain-$index"), configurationDigest = Digests.sha256("synthetic-config-$index"),
            fullProject = true, compilationFiles = listOf("Main.kt")), listOf(PLAN_TEST_RULE)) }
    }

    private fun local(binding: ClientBinding, request: SubmitPlanRequest, inputs: List<AnalysisInput>) = RequiredReviewPlan(
        binding, request, Digests.sha256(canonical(inputs.first().snapshot)), Digests.sha256("synthetic-no-generated-inputs"),
        Digests.sha256("synthetic-model-certificate"), Digests.sha256("synthetic-impact:${inputs.first().snapshot.snapshotId}"),
        inputs.mapIndexed { index, input -> RequiredPlanSlot("slot-${index.toString().padStart(3, '0')}", input, assertNotNull(contextIdentity(input))) },
        listOf(binding.clientId))

    private class Registry : ReviewPlanAuthority, ReviewPlanRoutingAuthority {
        val definitions = ConcurrentHashMap<String, RequiredReviewPlan>()
        val routes = ConcurrentHashMap<String, AuthorizedReviewRoute>()
        val resolutions = AtomicInteger()
        val grants = AtomicInteger()
        override fun resolve(binding: ClientBinding, request: SubmitPlanRequest): RequiredReviewPlan? {
            resolutions.incrementAndGet()
            return definitions[request.planRef]
        }
        override fun authorize(binding: ClientBinding, request: SubmitPlanRequest, declared: RequiredReviewPlan): AuthorizedReviewRoute? {
            grants.incrementAndGet()
            return routes[request.planRef]
        }
        fun install(route: AuthorizedReviewRoute) {
            definitions[route.origin.request.planRef] = route.origin
            routes[route.origin.request.planRef] = route
        }
    }

    /** Private synthetic owner record plus actual byte checks, not an unchecked DTO echo. */
    private fun recipient(client: ClientRegistration, plan: RequiredReviewPlan): AuthorizedPlanRecipient {
        val retained = ContractJson.decodeFromString<RequiredReviewPlan>(canonical(plan))
        val digest = Digests.sha256(canonical(retained))
        val preparation = PlanRecipientPreparation(digest,
            Digests.sha256("synthetic-policy-revision-1:${canonical(retained.binding)}:$digest"),
            Digests.sha256("synthetic-owner-execution-approval-1:${canonical(retained.binding)}:$digest"))
        return AuthorizedPlanRecipient(plan, client.adapter, preparation) {
            assertEquals(Digests.sha256("synthetic-model-certificate"), retained.modelCertificateId)
            retained.slots.forEach { InputArchive.verifyFrozen(it.input) }
            preparation
        }
    }

    private fun replace(
        original: AuthorizedPlanRecipient,
        plan: RequiredReviewPlan = original.plan,
        adapter: AdapterCredential = original.adapter,
        preparation: PlanRecipientPreparation = original.preparation,
        verify: () -> PlanRecipientPreparation? = original.verifyForAdmission,
    ) = AuthorizedPlanRecipient(plan, adapter, preparation, verify)

    private fun route(recipients: List<AuthorizedPlanRecipient>): AuthorizedReviewRoute = AuthorizedReviewRoute(
        recipients.first().plan.copy(requiredRecipientClientIds = recipients.map { it.plan.binding.clientId }), recipients)

    private data class Fixture(val clients: List<ClientRegistration>, val locals: List<RequiredReviewPlan>, val route: AuthorizedReviewRoute)
    private fun fixture(service: KernelService, registry: Registry, inputs: List<List<AnalysisInput>>): Fixture {
        val clients = inputs.indices.map { service.registerClient(binding("recipient-$it")) }
        val locals = clients.zip(inputs).map { (client, selected) -> local(client.binding, request(client.binding.clientId), selected) }
        val route = route(clients.zip(locals).map { (client, plan) -> recipient(client, plan) })
        registry.install(route)
        return Fixture(clients, locals, route)
    }

    private fun events(service: KernelService, client: ClientRegistration): List<ReviewEvent> = buildList {
        var cursor: String? = null
        do {
            val page = service.events(client.client, cursor, 2)
            addAll(page.events)
            cursor = page.cursor
        } while (page.hasMore)
    }

    private fun accepted(service: KernelService, client: ClientRegistration): ParentPlanStatus = assertNotNull(
        ContractJson.decodeFromString<ReviewStatus>(events(service, client).last { it.kind == "plan_accepted" }.payload).parentPlan)

    private fun awaitPlan(service: KernelService, client: ClientRegistration, id: String): ParentPlanStatus {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        val accepted = service.planStatus(client.client, id)
        accepted.slots.forEach { slot ->
            var status = service.status(client.client, slot.reviewId)
            while (status.result == null && System.nanoTime() < deadline) {
                Thread.sleep(5)
                status = service.status(client.client, slot.reviewId)
            }
            assertNotNull(status.result, "Synthetic child did not finish")
        }
        return service.planStatus(client.client, id)
    }

    private fun deliver(service: KernelService, client: ClientRegistration) {
        events(service, client).forEach { event ->
            val digest = Digests.sha256("synthetic-context:${event.payload}")
            service.acknowledge(client.adapter, listOf(event.eventId), ContextReceipt("ack-${event.eventId}", digest,
                "item-${event.eventId}", Instant.EPOCH.toString()))
            service.observe(client.adapter, listOf(event.eventId), ObservationReceipt("observe-${event.eventId}",
                "synthetic-model-${event.eventId}", listOf(digest)))
        }
    }

    private fun counts(service: KernelService): Map<String, Long> = service.ledger.read { c ->
        listOf("parent_plans", "routing_batches", "routing_members", "plan_slots", "reviews", "work", "events", "work_certificates")
            .associateWith { c.count("SELECT COUNT(*) FROM $it") }
    }

    private fun noAcceptance(service: KernelService, clients: List<ClientRegistration>) {
        assertTrue(counts(service).values.all { it == 0L }, counts(service).toString())
        service.ledger.read { c -> clients.forEach { client ->
            assertEquals(1L, c.count("SELECT next_review FROM clients WHERE client_id=?", client.binding.clientId))
            assertEquals(1L, c.count("SELECT next_event FROM clients WHERE client_id=?", client.binding.clientId))
            assertEquals(0L, c.count("SELECT epoch FROM clients WHERE client_id=?", client.binding.clientId))
            assertEquals(0L, c.count("SELECT routed_obligations FROM clients WHERE client_id=?", client.binding.clientId))
        } }
    }

    private fun assertCode(code: String, block: () -> Unit): KernelException = assertFailsWith<KernelException>(block = block).also {
        assertEquals(code, it.code)
    }

    /** Stored synthetic current authority; never derives an attestation by echoing method arguments. */
    private class Fence : SnapshotGate {
        private var check = SnapshotCheck(false)
        fun install(plan: RequiredReviewPlan) {
            val retained = ContractJson.decodeFromString<RequiredReviewPlan>(canonical(plan))
            val anchor = retained.slots.last().input
            check = SnapshotCheck(true, anchor.snapshot.snapshotId, 1, anchor.context.configurationDigest,
                anchor.context.modelDigest, anchor.context.dependencyDigest, anchor.context.toolchainDigest,
                "synthetic-routed-test-fence", authoritative = true, contextIdentities = retained.slots.mapNotNull { it.identity },
                sourceBindingDigest = retained.sourceBindingDigest, generatedInputsDigest = retained.generatedInputsDigest)
        }
        override fun <T> withCurrentSnapshot(input: AnalysisInput, action: (SnapshotCheck) -> T): T = action(check)
        override fun <T> withCurrentSnapshots(inputs: List<AnalysisInput>, action: (SnapshotCheck) -> T): T = action(check)
    }

    @Test fun completeCrossClientVectorAndBothOutboxesPublishAtomicallyBeforeAnyProviderRuns() {
        val inputs = inputs("atomic")
        val registry = Registry()
        val paused = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var service: KernelService
        val provider = PlanTestProvider { input, _ ->
            val observed = counts(service)
            assertEquals(2L, observed["parent_plans"])
            assertEquals(4L, observed["plan_slots"])
            assertEquals(4L, observed["reviews"])
            assertEquals(1L, observed["routing_batches"])
            assertEquals(2L, observed["routing_members"])
            service.ledger.read { c -> assertEquals(2L, c.count("SELECT COUNT(*) FROM events WHERE kind='plan_accepted'")) }
            planPassed(input)
        }
        KernelService.open(temp.resolve("ledger"), provider, config.copy(workerCount = 2), planAuthority = registry, routingAuthority = registry).use { opened ->
            service = opened
            val f = fixture(service, registry, listOf(inputs, inputs))
            service.plans.afterSlotInsert = { if (it == 3) { paused.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) } }
            val pool = Executors.newSingleThreadExecutor()
            try {
                val pending = pool.submit<ParentPlanStatus> { service.submitPlan(f.clients[0].client, f.route.origin.request) }
                assertTrue(paused.await(5, TimeUnit.SECONDS))
                noAcceptance(service, f.clients)
                f.clients.forEach { assertTrue(events(service, it).isEmpty()) }
                assertEquals(0, provider.calls.get())
                release.countDown()
                val origin = pending.get(10, TimeUnit.SECONDS)
                assertEquals(f.clients[0].binding.clientId, origin.clientId)
                f.clients.zip(f.locals).forEach { (client, local) ->
                    val plan = accepted(service, client)
                    assertEquals(Digests.sha256(canonical(local)), plan.planDigest)
                    assertEquals(2, plan.slots.size)
                    assertEquals("complete", awaitPlan(service, client, plan.planId).state)
                }
                assertNotEquals(origin.planId, accepted(service, f.clients[1]).planId)
                assertEquals(2, provider.calls.get(), "Exact equivalent work may be shared, not subscriber obligations")
                assertEquals(4L, counts(service)["reviews"])
            } finally { release.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun faultAfterTheOtherRecipientsFirstChildRollsBackEveryParentEventAndSequenceAndRetryIsWhole() {
        val inputs = inputs("rollback")
        val registry = Registry()
        KernelService.open(temp.resolve("ledger"), PlanTestProvider(), config, planAuthority = registry, routingAuthority = registry).use { service ->
            val f = fixture(service, registry, listOf(inputs, inputs))
            service.plans.afterSlotInsert = { if (it == 3) error("synthetic-before-route-commit") }
            assertFailsWith<IllegalStateException> { service.submitPlan(f.clients[0].client, f.route.origin.request) }
            noAcceptance(service, f.clients)
            service.plans.afterSlotInsert = { }
            val plan = service.submitPlan(f.clients[0].client, f.route.origin.request)
            assertEquals(plan, service.submitPlan(f.clients[0].client, f.route.origin.request))
            f.clients.forEach { assertEquals(listOf(1L, 2L, 3L), events(service, it).map { event -> event.sequence }) }
            assertEquals(1L, counts(service)["routing_batches"])
            assertEquals(2L, counts(service)["work"])
        }
    }

    @Test fun defaultDenyMissingRecipientsDuplicateRecipientsAndChangedOriginNeverAcceptAPrefix() {
        val inputs = inputs("grants")
        val registry = Registry()
        KernelService.open(temp.resolve("ledger"), PlanTestProvider(), config, planAuthority = registry, routingAuthority = registry).use { service ->
            val f = fixture(service, registry, listOf(inputs, inputs))
            val a = f.route.recipients[0]
            val b = f.route.recipients[1]
            val third = service.registerClient(binding("extra-recipient"))
            val c = recipient(third, local(third.binding, request("extra"), inputs))
            registry.routes.clear()
            assertCode("cross_client_routing_unavailable") { service.submitPlan(f.clients[0].client, f.route.origin.request) }
            val cases = listOf(
                "routing_recipients_invalid" to AuthorizedReviewRoute(f.route.origin, listOf(a)),
                "routing_recipients_invalid" to AuthorizedReviewRoute(f.route.origin, listOf(b)),
                "routing_recipients_invalid" to AuthorizedReviewRoute(f.route.origin, listOf(a, a)),
                "routing_recipients_invalid" to AuthorizedReviewRoute(f.route.origin, listOf(a, b, c)),
                "routing_origin_mismatch" to AuthorizedReviewRoute(f.locals[0], listOf(a, b)),
                "routing_origin_mismatch" to AuthorizedReviewRoute(f.route.origin.copy(request = request("wrong")), listOf(a, b)),
                "routing_origin_mismatch" to AuthorizedReviewRoute(f.route.origin.copy(binding = f.route.origin.binding.copy(sessionId = "wrong")), listOf(a, b)),
                "routing_origin_mismatch" to AuthorizedReviewRoute(f.route.origin.copy(slots = f.route.origin.slots.drop(1)), listOf(a, b)),
                "routing_origin_mismatch" to AuthorizedReviewRoute(f.route.origin, listOf(replace(a, plan = a.plan.copy(slots = a.plan.slots.drop(1))), b)),
            )
            cases.forEach { (code, invalid) ->
                registry.routes[f.route.origin.request.planRef] = invalid
                assertCode(code) { service.submitPlan(f.clients[0].client, f.route.origin.request) }
                noAcceptance(service, f.clients + third)
            }
            registry.definitions[f.route.origin.request.planRef] = f.route.origin.copy(slots = f.route.origin.slots.reversed())
            assertCode("routing_plan_not_canonical") { service.submitPlan(f.clients[0].client, f.route.origin.request) }
            noAcceptance(service, f.clients)
        }
        // An installed plan authority does not implicitly install routing authority.
        KernelService.open(temp.resolve("default-deny"), PlanTestProvider(), config, planAuthority = registry).use { service ->
            val f = fixture(service, registry, listOf(inputs, inputs))
            assertCode("cross_client_routing_unavailable") { service.submitPlan(f.clients[0].client, f.route.origin.request) }
            noAcceptance(service, f.clients)
        }
    }

    @Test fun everyRecipientRequiresItsActualAdapterAndExactRegisteredGenerationSessionAndNamespace() {
        val inputs = inputs("enrollment")
        val registry = Registry()
        KernelService.open(temp.resolve("ledger"), PlanTestProvider(), config, planAuthority = registry, routingAuthority = registry).use { service ->
            val f = fixture(service, registry, listOf(inputs, inputs))
            val b = f.route.recipients[1]
            val changed = listOf(
                replace(b, adapter = f.clients[0].adapter),
                replace(b, adapter = AdapterCredential(b.adapter.clientId, f.clients[1].client.token)),
                replace(b, adapter = b.adapter.copy(token = "x".repeat(43))),
                replace(b, plan = b.plan.copy(binding = b.plan.binding.copy(generation = 2))),
                replace(b, plan = b.plan.copy(binding = b.plan.binding.copy(sessionId = "another-session"))),
                replace(b, plan = b.plan.copy(binding = b.plan.binding.copy(lineageId = "another-lineage"))),
            )
            changed.forEach { invalid ->
                val prepared = invalid.preparation.copy(planDigest = Digests.sha256(canonical(invalid.plan)))
                registry.routes[f.route.origin.request.planRef] = AuthorizedReviewRoute(f.route.origin, listOf(f.route.recipients[0], replace(invalid, preparation = prepared)))
                assertCode("routing_recipient_unauthorized") { service.submitPlan(f.clients[0].client, f.route.origin.request) }
                noAcceptance(service, f.clients)
            }
            listOf(b.plan.binding.copy(ownerId = "another-owner"), b.plan.binding.copy(workspaceId = "another-workspace")).forEach { foreign ->
                registry.routes[f.route.origin.request.planRef] = AuthorizedReviewRoute(f.route.origin,
                    listOf(f.route.recipients[0], replace(b, plan = b.plan.copy(binding = foreign))))
                assertCode("routing_namespace_mismatch") { service.submitPlan(f.clients[0].client, f.route.origin.request) }
            }
            val absent = ClientRegistration(binding("not-enrolled"), ClientCredential("not-enrolled", "c".repeat(43)), AdapterCredential("not-enrolled", "a".repeat(43)))
            val missing = recipient(absent, local(absent.binding, request("not-enrolled"), inputs))
            val route = route(listOf(f.route.recipients[0], missing))
            registry.install(route)
            assertCode("routing_recipient_unauthorized") { service.submitPlan(f.clients[0].client, route.origin.request) }
            noAcceptance(service, f.clients)
        }
    }

    @Test fun missingCopiedChangedOrThrowingPreparationIsDeniedWithoutExposingPrivateEvidence() {
        val inputs = inputs("preparation")
        val registry = Registry()
        KernelService.open(temp.resolve("ledger"), PlanTestProvider(), config, planAuthority = registry, routingAuthority = registry).use { service ->
            val f = fixture(service, registry, listOf(inputs, inputs))
            val a = f.route.recipients[0]
            val b = f.route.recipients[1]
            val invalid = listOf(
                replace(b, preparation = b.preparation.copy(planDigest = Digests.sha256("wrong-plan"))),
                replace(b, preparation = b.preparation.copy(capturedPolicyDigest = "missing")),
                replace(b, preparation = b.preparation.copy(executionApprovalDigest = "missing")),
                replace(b, preparation = a.preparation),
                replace(b, preparation = b.preparation.copy(capturedPolicyDigest = a.preparation.capturedPolicyDigest)),
                replace(b, preparation = b.preparation.copy(executionApprovalDigest = a.preparation.executionApprovalDigest)),
            )
            invalid.forEach { entry ->
                registry.routes[f.route.origin.request.planRef] = AuthorizedReviewRoute(f.route.origin, listOf(a, entry))
                assertCode("routing_preparation_invalid") { service.submitPlan(f.clients[0].client, f.route.origin.request) }
                noAcceptance(service, f.clients)
            }
            val callbacks: List<() -> PlanRecipientPreparation?> = listOf(
                { null }, { error("private-recipient-policy:${f.clients[1].adapter.token}") },
                { throw AssertionError("private-recipient-policy:${f.clients[1].adapter.token}") },
                { b.preparation.copy(executionApprovalDigest = Digests.sha256("changed-approval")) },
            )
            callbacks.forEach { callback ->
                registry.routes[f.route.origin.request.planRef] = AuthorizedReviewRoute(f.route.origin, listOf(a, replace(b, verify = callback)))
                val error = assertCode("routing_preparation_unavailable") { service.submitPlan(f.clients[0].client, f.route.origin.request) }
                assertFalse(error.message.orEmpty().contains(f.clients[1].adapter.token))
                assertFalse(error.message.orEmpty().contains("private-recipient-policy"))
                noAcceptance(service, f.clients)
            }
            val calls = AtomicInteger()
            registry.routes[f.route.origin.request.planRef] = AuthorizedReviewRoute(f.route.origin, listOf(a, replace(b, verify = {
                if (calls.incrementAndGet() == 1) b.verifyForAdmission() else null
            })))
            assertCode("routing_preparation_unavailable") { service.submitPlan(f.clients[0].client, f.route.origin.request) }
            assertEquals(2, calls.get(), "Preparation must be checked again after staging")
            noAcceptance(service, f.clients)
        }
    }

    @Test fun preparationRunsOutsideWriterLocksAndEveryAdapterIsReauthenticatedInTheCommit() {
        val inputs = inputs("reauthenticate")
        val registry = Registry()
        val paused = CountDownLatch(1)
        val release = CountDownLatch(1)
        KernelService.open(temp.resolve("ledger"), PlanTestProvider(), config, planAuthority = registry, routingAuthority = registry).use { service ->
            val f = fixture(service, registry, listOf(inputs, inputs))
            val b = f.route.recipients[1]
            val calls = AtomicInteger()
            val prepared = replace(b, verify = {
                // This independent write would deadlock/time out if verification held a writer.
                service.ledger.transaction { c -> assertEquals(0L, c.count("SELECT COUNT(*) FROM routing_batches")) }
                val verified = b.verifyForAdmission()
                if (calls.incrementAndGet() == 2) { paused.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
                verified
            })
            registry.install(AuthorizedReviewRoute(f.route.origin, listOf(f.route.recipients[0], prepared)))
            val pool = Executors.newSingleThreadExecutor()
            try {
                val submitted = pool.submit<KernelException> {
                    assertFailsWith<KernelException> { service.submitPlan(f.clients[0].client, f.route.origin.request) }
                }
                assertTrue(paused.await(5, TimeUnit.SECONDS))
                // Test-only enrollment corruption simulates losing the exact bound capability;
                // the production API intentionally has no token-replacement/auto-claim operation.
                service.ledger.transaction { c -> c.execute("UPDATE clients SET adapter_hash=? WHERE client_id=?",
                    Digests.sha256("different-private-capability"), f.clients[1].binding.clientId) }
                release.countDown()
                assertEquals("routing_recipient_unauthorized", submitted.get(10, TimeUnit.SECONDS).code)
                noAcceptance(service, f.clients)
                service.ledger.transaction { c -> c.execute("UPDATE clients SET adapter_hash=? WHERE client_id=?",
                    Digests.sha256(f.clients[1].adapter.token), f.clients[1].binding.clientId) }
                assertEquals("pending", service.submitPlan(f.clients[0].client, f.route.origin.request).state)
            } finally { release.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun anotherRecipientsRealFinalActionClaimRejectsTheEntireRacingRoute() {
        val inputs = inputs("gate-race")
        val registry = Registry()
        val fence = Fence()
        val paused = CountDownLatch(1)
        val release = CountDownLatch(1)
        KernelService.open(temp.resolve("ledger"), PlanTestProvider(), config.copy(workerCount = 2), fence,
            planAuthority = registry, routingAuthority = registry).use { service ->
            val f = fixture(service, registry, listOf(inputs, inputs))
            val b = f.clients[1]
            val old = local(b.binding, request("pre-route-local"), inputs)
            registry.definitions[old.request.planRef] = old
            fence.install(old)
            val accepted = service.submitPlan(b.client, old.request)
            assertEquals("complete", awaitPlan(service, b, accepted.planId).state)
            deliver(service, b)
            val cut = service.prepareGate(b.client)
            assertTrue(cut.ready, cut.reasons.toString())
            val baseline = counts(service)
            val recipient = f.route.recipients[1]
            val calls = AtomicInteger()
            registry.install(AuthorizedReviewRoute(f.route.origin, listOf(f.route.recipients[0], replace(recipient, verify = {
                val verified = recipient.verifyForAdmission()
                if (calls.incrementAndGet() == 2) { paused.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
                verified
            }))))
            val pool = Executors.newSingleThreadExecutor()
            try {
                val submitted = pool.submit<KernelException> {
                    assertFailsWith<KernelException> { service.submitPlan(f.clients[0].client, f.route.origin.request) }
                }
                assertTrue(paused.await(5, TimeUnit.SECONDS))
                var actions = 0
                assertTrue(service.completeGate(b.client, cut.cutId) { actions++ }.actionPerformed)
                assertEquals(1, actions)
                release.countDown()
                assertEquals("lineage_finalized", submitted.get(10, TimeUnit.SECONDS).code)
                assertEquals(baseline, counts(service))
                assertTrue(events(service, f.clients[0]).isEmpty())
                assertEquals(0L, service.ledger.read { it.count("SELECT SUM(routed_obligations) FROM clients") })
            } finally { release.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun quotasCountTheCombinedVectorAndMetadataButDeduplicateOnlyActuallyEquivalentWork() {
        val a = inputs("quota-a")
        val b = inputs("quota-b")
        val byteCount = a.first().snapshot.files.sumOf { it.size }
        val provisional = listOf(local(binding("recipient-0"), request("recipient-0"), a), local(binding("recipient-1"), request("recipient-1"), b))
        val largest = (provisional + provisional.first().copy(requiredRecipientClientIds = provisional.map { it.binding.clientId }))
            .maxOf { canonical(it).toByteArray().size }
        val limits = listOf(
            "ledger_full" to config.copy(maxRetainedReviews = 3),
            "queue_full" to config.copy(maxQueuedWork = 3),
            "client_queue_full" to config.copy(maxPendingPerClient = 1),
            "archive_full" to config.copy(maxArchiveBytes = byteCount * 3),
            "input_limit" to config.copy(maxInputBytes = largest + 128),
            "input_limit" to config.copy(maxResultBytes = 1),
        )
        limits.forEachIndexed { index, (code, bounded) ->
            val registry = Registry()
            KernelService.open(temp.resolve("quota-$index"), PlanTestProvider(), bounded, planAuthority = registry, routingAuthority = registry).use { service ->
                val f = fixture(service, registry, listOf(a, b))
                assertCode(code) { service.submitPlan(f.clients[0].client, f.route.origin.request) }
                noAcceptance(service, f.clients)
            }
        }
        val registry = Registry()
        KernelService.open(temp.resolve("shared-quota"), PlanTestProvider(), config.copy(maxRetainedReviews = 4,
            maxQueuedWork = 2, maxPendingPerClient = 2, maxArchiveBytes = byteCount * 2), planAuthority = registry, routingAuthority = registry).use { service ->
            val f = fixture(service, registry, listOf(a, a))
            service.submitPlan(f.clients[0].client, f.route.origin.request)
            assertEquals(4L, counts(service)["reviews"])
            assertEquals(2L, counts(service)["work"])
        }
        val pendingRegistry = Registry()
        KernelService.open(temp.resolve("pending-quota"), PlanTestProvider(), config.copy(maxPendingPerClient = 2),
            planAuthority = pendingRegistry, routingAuthority = pendingRegistry).use { service ->
            val f = fixture(service, pendingRegistry, listOf(a, a))
            service.submit(f.clients[1].client, SubmitRequest("prior-pending", "prior-pending", b.first()))
            val before = counts(service)
            assertCode("client_queue_full") { service.submitPlan(f.clients[0].client, f.route.origin.request) }
            assertEquals(before, counts(service))
            assertTrue(events(service, f.clients[0]).isEmpty())
        }
    }

    @Test fun concurrentExactRetriesAcrossIndependentServicesCommitOneWholeBatchWithNoDuplicateEventsOrSequenceGaps() {
        val inputs = inputs("concurrent-retry")
        val registry = Registry()
        val bothResolved = CyclicBarrier(2)
        val routing = ReviewPlanRoutingAuthority { binding, request, declared ->
            val grant = registry.authorize(binding, request, declared)
            bothResolved.await(10, TimeUnit.SECONDS)
            grant
        }
        val database = temp.resolve("ledger")
        KernelService.open(database, PlanTestProvider(), config, planAuthority = registry, routingAuthority = routing).use { first ->
            KernelService.open(database, PlanTestProvider(), config, planAuthority = registry, routingAuthority = routing).use { second ->
                val f = fixture(first, registry, listOf(inputs, inputs))
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val futures = listOf(first, second).map { service -> pool.submit<ParentPlanStatus> {
                        service.submitPlan(f.clients[0].client, f.route.origin.request)
                    } }
                    val results = futures.map { it.get(15, TimeUnit.SECONDS) }
                    assertEquals(results.first(), results.last())
                    assertEquals(1L, counts(first)["routing_batches"])
                    assertEquals(2L, counts(first)["parent_plans"])
                    assertEquals(4L, counts(first)["reviews"])
                    assertEquals(6L, counts(first)["events"])
                    f.clients.forEach { client ->
                        assertEquals(listOf(1L, 2L, 3L), events(first, client).map { it.sequence })
                        val plan = accepted(first, client)
                        assertEquals(plan, second.submitPlan(client.client, f.locals.single { it.binding == client.binding }.request))
                    }
                    assertEquals(2, registry.resolutions.get(), "Durable exact retries cannot recreate or expand grants")
                    assertEquals(2, registry.grants.get())
                } finally { pool.shutdownNow() }
            }
        }
    }

    @Test fun overlappingRoutesCannotAttachTheSameRecipientsAlreadyAcceptedRequestAsAPartialSecondBatch() {
        val inputs = inputs("overlap")
        val registry = Registry()
        val bothResolved = CyclicBarrier(2)
        val routing = ReviewPlanRoutingAuthority { binding, request, declared ->
            registry.authorize(binding, request, declared).also { bothResolved.await(10, TimeUnit.SECONDS) }
        }
        val database = temp.resolve("ledger")
        KernelService.open(database, PlanTestProvider(), config, planAuthority = registry, routingAuthority = routing).use { first ->
            KernelService.open(database, PlanTestProvider(), config, planAuthority = registry, routingAuthority = routing).use { second ->
                val a = first.registerClient(binding("overlap-a"))
                val b = first.registerClient(binding("overlap-b"))
                val c = first.registerClient(binding("overlap-c"))
                val ar = recipient(a, local(a.binding, request("overlap-a"), inputs))
                val br = recipient(b, local(b.binding, request("overlap-b"), inputs))
                val cr = recipient(c, local(c.binding, request("overlap-c"), inputs))
                val ab = route(listOf(ar, br)); registry.install(ab)
                val cb = route(listOf(cr, br)); registry.install(cb)
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val left = pool.submit<Result<ParentPlanStatus>> { runCatching { first.submitPlan(a.client, ab.origin.request) } }
                    val right = pool.submit<Result<ParentPlanStatus>> { runCatching { second.submitPlan(c.client, cb.origin.request) } }
                    val outcomes = listOf(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS))
                    assertEquals(1, outcomes.count { it.isSuccess })
                    assertEquals("routing_request_conflict", (outcomes.single { it.isFailure }.exceptionOrNull() as KernelException).code)
                    assertEquals(1L, counts(first)["routing_batches"])
                    assertEquals(2L, counts(first)["parent_plans"])
                    assertEquals(4L, counts(first)["reviews"])
                    assertEquals(3, events(first, b).size)
                    val loser = if (outcomes.first().isFailure) a else c
                    assertTrue(events(first, loser).isEmpty())
                    first.ledger.read { db -> assertEquals(1L, db.count("SELECT next_review FROM clients WHERE client_id=?", loser.binding.clientId)) }
                } finally { pool.shutdownNow() }
            }
        }
    }

    @Test fun localStatusCursorsCancellationAckAndObservationNeverConsumeOrExposeAnotherRecipientsObligation() {
        val inputs = inputs("isolation")
        val registry = Registry()
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val provider = PlanTestProvider { input, cancellation ->
            entered.countDown()
            assertTrue(release.await(10, TimeUnit.SECONDS))
            assertFalse(cancellation.isCancelled(), "One subscriber cancellation must not cancel shared work")
            planPassed(input)
        }
        KernelService.open(temp.resolve("ledger"), provider, config.copy(workerCount = 2), planAuthority = registry, routingAuthority = registry).use { service ->
            val f = fixture(service, registry, listOf(inputs, inputs))
            try {
                val ap = service.submitPlan(f.clients[0].client, f.route.origin.request)
                val bp = accepted(service, f.clients[1])
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                f.clients.forEachIndexed { index, client ->
                    val other = f.clients[1 - index]
                    val otherPlan = if (index == 0) bp else ap
                    val exposed = canonical(service.planStatus(client.client, if (index == 0) ap.planId else bp.planId)) +
                        events(service, client).joinToString { it.payload }
                    listOf(other.binding.clientId, other.binding.lineageId, other.binding.sessionId, other.client.token,
                        other.adapter.token, otherPlan.planId, f.locals[1 - index].request.planRef,
                        f.route.recipients[1 - index].preparation.capturedPolicyDigest).forEach { assertFalse(exposed.contains(it)) }
                    assertCode("plan_not_found") { service.planStatus(client.client, otherPlan.planId) }
                    assertCode("plan_not_found") { service.cancelPlan(client.client, otherPlan.planId) }
                    assertCode("review_not_found") { service.status(client.client, otherPlan.slots.first().reviewId) }
                }
                val cursor = service.events(f.clients[0].client, limit = 1).cursor
                assertCode("invalid_cursor") { service.events(f.clients[1].client, cursor) }
                val bEvent = events(service, f.clients[1]).first()
                val fakeReceipt = ContextReceipt("foreign-ack", Digests.sha256("synthetic-context"), "foreign-item", Instant.EPOCH.toString())
                assertCode("event_not_found") { service.acknowledge(f.clients[0].adapter, listOf(bEvent.eventId), fakeReceipt) }
                assertCode("event_not_found") { service.observe(f.clients[0].adapter, listOf(bEvent.eventId),
                    ObservationReceipt("foreign-observe", "synthetic-model", listOf(fakeReceipt.contextDigest))) }
                assertCode("unauthorized") { service.acknowledge(AdapterCredential(f.clients[1].binding.clientId, f.clients[1].client.token), listOf(bEvent.eventId), fakeReceipt) }
                assertEquals("cancelled", service.cancelPlan(f.clients[0].client, ap.planId).state)
                assertEquals("pending", service.planStatus(f.clients[1].client, bp.planId).state)
                release.countDown()
                assertEquals("complete", awaitPlan(service, f.clients[1], bp.planId).state)
                assertEquals("cancelled", awaitPlan(service, f.clients[0], ap.planId).state)
                assertEquals(2, provider.calls.get())
                deliver(service, f.clients[0])
                service.ledger.read { c ->
                    assertEquals(0L, c.count("SELECT COUNT(*) FROM acknowledgements WHERE client_id=?", f.clients[1].binding.clientId))
                    assertEquals(0L, c.count("SELECT COUNT(*) FROM observations WHERE client_id=?", f.clients[1].binding.clientId))
                }
                val blocked = service.prepareGate(f.clients[1].client)
                assertTrue(blocked.reasons.any { it.startsWith("insertion_missing:") })
                assertTrue("cross_client_finalization_unavailable" in blocked.reasons)
                deliver(service, f.clients[1])
                assertFalse(service.prepareGate(f.clients[1].client).ready)
            } finally { release.countDown() }
        }
    }

    @Test fun sameRawWorkRetainsIndependentCapturedPoliciesAndCachedWorkCannotSubstituteForMissingApproval() {
        val inputs = inputs("policies")
        val registry = Registry()
        val captured = ConcurrentHashMap<Pair<ClientBinding, String>, String>()
        val policy = ResultPolicy { binding, input, raw ->
            val disposition = captured[binding to input.context.configurationDigest.orEmpty()] ?: error("synthetic-policy-not-retained")
            raw.copy(findings = raw.findings.map { it.copy(disposition = disposition) })
        }
        val provider = PlanTestProvider { input, _ ->
            val file = input.snapshot.files.first()
            val finding = Finding(PLAN_TEST_RULE, "Synthetic", "Synthetic defect", "Test only", SourceLocation(file.path, file.digest),
                Digests.sha256("synthetic-finding:${input.context.scope.id}"), PLAN_TEST_PROVIDER, context = input.context.scope)
            planPassed(input, listOf(finding))
        }
        KernelService.open(temp.resolve("ledger"), provider, config.copy(workerCount = 2), resultPolicy = policy,
            planAuthority = registry, routingAuthority = registry).use { service ->
            val f = fixture(service, registry, listOf(inputs, inputs))
            f.locals.forEachIndexed { index, plan -> plan.slots.forEach { slot ->
                captured[plan.binding to slot.input.context.configurationDigest.orEmpty()] = if (index == 0) "accepted" else "open"
            } }
            service.submitPlan(f.clients[0].client, f.route.origin.request)
            f.clients.forEachIndexed { index, client ->
                val plan = awaitPlan(service, client, accepted(service, client).planId)
                assertEquals("complete", plan.state)
                plan.slots.forEach { slot -> assertEquals(if (index == 0) "accepted" else "open",
                    service.status(client.client, slot.reviewId).result!!.findings.single().disposition) }
            }
            assertEquals(2, provider.calls.get())
            service.ledger.read { c -> c.query("SELECT result_json FROM work") {
                ContractJson.decodeFromString<ProviderResult>(it.getString(1))
            }.forEach { assertEquals("open", it.findings.single().disposition) } }
            val before = counts(service)
            val c = service.registerClient(binding("cached-c"))
            val d = service.registerClient(binding("cached-d"))
            val cr = recipient(c, local(c.binding, request("cached-c"), inputs))
            val dr = recipient(d, local(d.binding, request("cached-d"), inputs))
            val missingApproval = route(listOf(cr, replace(dr, verify = { null })))
            registry.install(missingApproval)
            assertCode("routing_preparation_unavailable") { service.submitPlan(c.client, missingApproval.origin.request) }
            assertEquals(before, counts(service))
            assertTrue(events(service, c).isEmpty()); assertTrue(events(service, d).isEmpty())
        }
    }

    @Test fun exactRetriesSurviveRestartAndAuthorityLossAndLaterLocalOrScalarPlansNeverRemoveTheRoutedGateBarrier() {
        val inputs = inputs("restart")
        val registry = Registry()
        val fence = Fence()
        val database = temp.resolve("ledger")
        lateinit var f: Fixture
        lateinit var original: List<ParentPlanStatus>
        lateinit var before: Map<String, Long>
        KernelService.open(database, PlanTestProvider(), config.copy(workerCount = 2), fence,
            planAuthority = registry, routingAuthority = registry).use { service ->
            f = fixture(service, registry, listOf(inputs, inputs))
            fence.install(f.locals[0])
            service.submitPlan(f.clients[0].client, f.route.origin.request)
            original = f.clients.map { awaitPlan(service, it, accepted(service, it).planId) }
            f.clients.forEach { deliver(service, it) }
            f.clients.forEach { client ->
                val cut = service.prepareGate(client.client)
                assertFalse(cut.ready)
                assertTrue("cross_client_finalization_unavailable" in cut.reasons)
                var actions = 0
                assertFalse(service.completeGate(client.client, cut.cutId) { actions++ }.allowed)
                assertEquals(0, actions)
            }
            before = counts(service)
        }
        deleteTree(Path.of(inputs.first().materializedRoot))
        // No resolver, routing callback or live original remains; exact accepted tuples are not
        // new grants and cannot grow, shrink or replace the durable recipient vector.
        KernelService.open(database, PlanTestProvider(), config, fence).use { recovered ->
            f.clients.forEachIndexed { index, client ->
                assertEquals(original[index], recovered.submitPlan(client.client, f.locals[index].request))
                assertEquals(original[index], recovered.planStatus(client.client, original[index].planId))
                assertTrue("cross_client_finalization_unavailable" in recovered.prepareGate(client.client).reasons)
            }
            assertCode("plan_authority_unavailable") { recovered.submitPlan(f.clients[0].client, request("new-without-authority")) }
            assertEquals(before, counts(recovered))
        }
        // Restore only the synthetic owner's original test materialization, then admit newer local
        // and scalar work. Neither kind can reinterpret the old cross-client route as local-only.
        this.inputs("restart")
        KernelService.open(database, PlanTestProvider(), config.copy(workerCount = 2), fence, planAuthority = registry).use { service ->
            f.clients.forEach { client ->
                val next = local(client.binding, request("later-${client.binding.clientId}"), inputs)
                registry.definitions[next.request.planRef] = next
                fence.install(next)
                val plan = service.submitPlan(client.client, next.request)
                assertEquals("complete", awaitPlan(service, client, plan.planId).state)
                deliver(service, client)
                var cut = service.prepareGate(client.client)
                assertFalse(cut.ready)
                assertTrue("cross_client_finalization_unavailable" in cut.reasons)
                val scalar = service.submit(client.client, SubmitRequest("scalar-${client.binding.clientId}", "scalar-${client.binding.clientId}", inputs.last()))
                assertNotNull(scalar.result, "The exact input is already complete in the immutable work cache")
                deliver(service, client)
                cut = service.prepareGate(client.client)
                assertFalse(cut.ready)
                assertTrue("cross_client_finalization_unavailable" in cut.reasons)
                val refreshed = next.copy(request = request("refreshed-${client.binding.clientId}"))
                registry.definitions[refreshed.request.planRef] = refreshed
                service.submitPlan(client.client, refreshed.request)
                deliver(service, client)
                cut = service.prepareGate(client.client)
                assertFalse(cut.ready)
                assertTrue("cross_client_finalization_unavailable" in cut.reasons)
                var actions = 0
                assertFalse(service.completeGate(client.client, cut.cutId) { actions++ }.allowed)
                assertEquals(0, actions)
            }
            service.cancelPlan(f.clients[0].client, original[0].planId)
            deliver(service, f.clients[0])
            assertTrue("cross_client_finalization_unavailable" in service.prepareGate(f.clients[0].client).reasons)
            service.ledger.read { c -> assertEquals(2L, c.count("SELECT SUM(routed_obligations) FROM clients")) }
        }
        KernelService.open(database, PlanTestProvider(), config, fence).use { service ->
            f.clients.forEach { assertTrue("cross_client_finalization_unavailable" in service.prepareGate(it.client).reasons) }
            assertEquals(1L, counts(service)["routing_batches"])
        }
    }

    @Test fun corruptionOfAnyRecipientsPrivateBatchParentSlotOrAcceptanceOutboxFailsTheWholeRouteOnStatusRetryAndRestart() {
        val inputs = inputs("corruption")
        val mutations: List<(java.sql.Connection, ParentPlanStatus) -> Unit> = listOf(
            { c, _ -> c.execute("UPDATE routing_batches SET routing_digest=?", Digests.sha256("wrong-route")) },
            { c, _ -> c.execute("UPDATE routing_batches SET definition_json=?,routing_digest=?", "{}", Digests.sha256("{}")) },
            { c, _ -> c.execute("UPDATE routing_batches SET definition_json=?,routing_digest=?", "{", Digests.sha256("{")) },
            { c, _ ->
                val existing = c.one("SELECT definition_json FROM routing_batches") { it.getString(1) }!!
                val decoded = ContractJson.parseToJsonElement(existing) as JsonObject
                val members = decoded.getValue("members") as JsonArray
                val narrowed = Digests.canonical(JsonObject(decoded + ("members" to JsonArray(members.dropLast(1)))))
                c.execute("UPDATE routing_batches SET definition_json=?,routing_digest=?", narrowed, Digests.sha256(narrowed))
            },
            { c, b -> c.execute("DELETE FROM routing_members WHERE plan_id=?", b.planId) },
            { c, b -> c.execute("DELETE FROM plan_slots WHERE plan_id=? AND slot_id=?", b.planId, b.slots.first().slotId) },
            { c, b -> c.execute("DELETE FROM parent_plans WHERE plan_id=?", b.planId) },
            { c, b -> c.execute("UPDATE parent_plans SET routing_id=NULL WHERE plan_id=?", b.planId) },
            { c, b -> c.execute("UPDATE parent_plans SET request_hash=? WHERE plan_id=?", Digests.sha256("wrong-request"), b.planId) },
            { c, b -> c.execute("UPDATE parent_plans SET definition_json=?,plan_digest=? WHERE plan_id=?", "{}", Digests.sha256("{}"), b.planId) },
            { c, b -> c.execute("UPDATE reviews SET request_hash=? WHERE review_id=?", Digests.sha256("wrong-child"), b.slots.first().reviewId) },
            { c, b -> c.execute("UPDATE reviews SET input_json='{}' WHERE review_id=?", b.slots.first().reviewId) },
            { c, b -> c.execute("DELETE FROM events WHERE client_id=? AND kind='plan_accepted'", b.clientId) },
            { c, b -> c.execute("UPDATE events SET payload=?,payload_digest=? WHERE client_id=? AND kind='plan_accepted'", "{}", Digests.sha256("{}"), b.clientId) },
            { c, b ->
                val existing = c.one("SELECT payload FROM events WHERE client_id=? AND kind='plan_accepted'", b.clientId) { it.getString(1) }!!
                val payload = ContractJson.decodeFromString<ReviewStatus>(existing)
                val parent = payload.parentPlan!!
                val narrowed = canonical(payload.copy(parentPlan = parent.copy(slots = parent.slots.dropLast(1))))
                c.execute("UPDATE events SET payload=?,payload_digest=? WHERE client_id=? AND kind='plan_accepted'", narrowed, Digests.sha256(narrowed), b.clientId)
            },
            { c, b -> c.execute("DELETE FROM events WHERE review_id=? AND kind='accepted'", b.slots.first().reviewId) },
            { c, b -> c.execute("UPDATE clients SET routed_obligations=0 WHERE client_id=?", b.clientId) },
            { c, _ -> c.execute("DELETE FROM routing_batches") },
        )
        mutations.forEachIndexed { index, mutate ->
            val registry = Registry()
            val database = temp.resolve("corrupt-$index")
            lateinit var f: Fixture
            lateinit var origin: ParentPlanStatus
            KernelService.open(database, PlanTestProvider(), config, planAuthority = registry, routingAuthority = registry).use { service ->
                f = fixture(service, registry, listOf(inputs, inputs))
                origin = service.submitPlan(f.clients[0].client, f.route.origin.request)
                val other = accepted(service, f.clients[1])
                val cut = service.prepareGate(f.clients[0].client)
                // Deliberate private-store corruption, not an API that deletes or waives history.
                DriverManager.getConnection("jdbc:sqlite:${database.resolve("ledger.sqlite")}").use { c ->
                    c.createStatement().use { it.execute("PRAGMA busy_timeout=15000"); it.execute("PRAGMA foreign_keys=OFF") }
                    mutate(c, other)
                }
                assertCode("routing_plan_corrupt") { service.planStatus(f.clients[0].client, origin.planId) }
                assertCode("routing_plan_corrupt") { service.submitPlan(f.clients[0].client, f.route.origin.request) }
                assertCode("routing_plan_corrupt") { service.prepareGate(f.clients[0].client) }
                var actions = 0
                assertCode("routing_plan_corrupt") { service.completeGate(f.clients[0].client, cut.cutId) { actions++ } }
                assertEquals(0, actions)
            }
            KernelService.open(database, PlanTestProvider(), config).use { recovered ->
                assertCode("routing_plan_corrupt") { recovered.planStatus(f.clients[0].client, origin.planId) }
                assertCode("routing_plan_corrupt") { recovered.submitPlan(f.clients[0].client, f.route.origin.request) }
                assertCode("routing_plan_corrupt") { recovered.prepareGate(f.clients[0].client) }
            }
        }
    }

    @Test fun independentWorktreesNeedIndependentExactEnrollmentsAndAllRecipientAndSlotBoundsAreWholeRouteBounds() {
        val inputsA = inputs("tree-a")
        val inputsB = inputs("tree-b", tree = "another-tree")
        val registry = Registry()
        KernelService.open(temp.resolve("ledger"), PlanTestProvider(), config, planAuthority = registry, routingAuthority = registry).use { service ->
            val a = service.registerClient(binding("tree-a"))
            val b = service.registerClient(binding("tree-b").copy(worktreeId = "another-tree"))
            val routed = route(listOf(recipient(a, local(a.binding, request("tree-a"), inputsA)), recipient(b, local(b.binding, request("tree-b"), inputsB))))
            registry.install(routed)
            service.submitPlan(a.client, routed.origin.request)
            assertEquals(4L, counts(service)["work"], "Different exact worktree/source inputs must not share work")
            assertTrue("cross_client_finalization_unavailable" in service.prepareGate(a.client).reasons)
            assertTrue("cross_client_finalization_unavailable" in service.prepareGate(b.client).reasons)
        }
        val largeRegistry = Registry()
        KernelService.open(temp.resolve("large"), PlanTestProvider(), config, planAuthority = largeRegistry, routingAuthority = largeRegistry).use { service ->
            val many = inputs("many-contexts", 257)
            val f = fixture(service, largeRegistry, listOf(many, many))
            assertCode("plan_slots_invalid") { service.submitPlan(f.clients[0].client, f.route.origin.request) }
            noAcceptance(service, f.clients)
            val tooMany = f.route.origin.copy(requiredRecipientClientIds = (0..64).map { "recipient-$it" })
            largeRegistry.definitions[tooMany.request.planRef] = tooMany
            assertCode("plan_recipients_invalid") { service.submitPlan(f.clients[0].client, tooMany.request) }
            noAcceptance(service, f.clients)
        }
    }

    @Test fun forciblyKilledUncommittedRoutedFanoutLeavesNoAcceptedPrefixAndWholeRetrySurvivesWithoutOldAuthority() {
        val inputs = inputs("killed-route")
        val registry = Registry()
        val database = temp.resolve("ledger")
        lateinit var f: Fixture
        lateinit var plansAccepted: List<ParentPlanStatus>
        KernelService.open(database, PlanTestProvider(), config, planAuthority = registry, routingAuthority = registry).use { service ->
            f = fixture(service, registry, listOf(inputs, inputs))
            val configFile = temp.resolve("child-config.json")
            val plansFile = temp.resolve("child-plans.json")
            val enrollmentsFile = temp.resolve("child-enrollments.json")
            writeDurable(configFile, canonical(config).toByteArray())
            writeDurable(plansFile, canonical(f.locals).toByteArray())
            writeDurable(enrollmentsFile, canonical(f.clients).toByteArray())
            val marker = temp.resolve("child-routed-prefix-inserted")
            val log = temp.resolve("child.log")
            val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
            val child = ProcessBuilder(java.toString(), "-Xmx128m", "-cp", crashClasspath(), KernelRoutingCrashChild::class.java.name,
                database.toString(), configFile.toString(), plansFile.toString(), enrollmentsFile.toString(), marker.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start()
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (!Files.exists(marker) && child.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
                assertTrue(Files.exists(marker), "Synthetic child did not enter the routing transaction: " + Files.readString(log).take(2000))
                noAcceptance(service, f.clients)
                f.clients.forEach { assertTrue(events(service, it).isEmpty()) }
                child.destroyForcibly()
                assertTrue(child.waitFor(10, TimeUnit.SECONDS))
                noAcceptance(service, f.clients)
                service.submitPlan(f.clients[0].client, f.route.origin.request)
                plansAccepted = f.clients.map { accepted(service, it) }
                assertEquals(1L, counts(service)["routing_batches"])
            } finally { if (child.isAlive) { child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS) } }
        }
        deleteTree(Path.of(inputs.first().materializedRoot))
        KernelService.open(database, PlanTestProvider(), config.copy(workerCount = 2)).use { recovered ->
            f.clients.forEachIndexed { index, client ->
                assertEquals("complete", awaitPlan(recovered, client, plansAccepted[index].planId).state)
                assertEquals(plansAccepted[index].planId, recovered.submitPlan(client.client, f.locals[index].request).planId)
                assertEquals(2, events(recovered, client).count { it.kind == "accepted" })
                assertEquals(1, events(recovered, client).count { it.kind == "plan_accepted" })
                assertEquals(2, events(recovered, client).count { it.kind == "result" })
                deliver(recovered, client)
                assertTrue("cross_client_finalization_unavailable" in recovered.prepareGate(client.client).reasons)
            }
        }
    }

    private fun crashClasspath(): String = buildSet<String> {
        System.getProperty("java.class.path").split(java.io.File.pathSeparator).filter { it.isNotBlank() }.forEach(::add)
        var loader: ClassLoader? = KernelRoutingCrashChild::class.java.classLoader
        while (loader != null) {
            if (loader is java.net.URLClassLoader) loader.urLs.filter { it.protocol == "file" }.forEach { add(Path.of(it.toURI()).toString()) }
            loader = loader.parent
        }
        listOf(KernelRoutingCrashChild::class.java, KernelService::class.java, AnalysisInput::class.java, kotlin.Unit::class.java,
            kotlinx.serialization.KSerializer::class.java, kotlinx.serialization.json.Json::class.java,
            Class.forName("org.sqlite.JDBC")).forEach { add(Path.of(it.protectionDomain.codeSource.location.toURI()).toString()) }
    }.joinToString(java.io.File.pathSeparator)
}

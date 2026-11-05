package dev.ktchecker.reviewcontroller

import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.kernel.*
import dev.ktchecker.projectmodel.ModelNamespace
import dev.ktchecker.projectmodel.VerifiedProjectModel
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.serialization.json.JsonArray
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Real imported model/capture capabilities. Registry-only adapter strings are NOT authentication. */
class OwnerReviewRouteRegistryTest {
    @TempDir lateinit var temp: Path
    private fun fixture(namespace: ModelNamespace = ModelNamespace("controller-owner", "workspace", "worktree", "lineage")) =
        ControllerFixtures(temp.resolve(UUID.randomUUID().toString()), namespace)
    private fun assertCode(code: String, action: () -> Unit) =
        assertEquals(code, assertFailsWith<ReviewControllerException>(block = action).code)
    private fun handle(assembly: AssembledReviewPlan, token: String = "unit-only-not-authenticated") =
        OwnerRouteRecipient(assembly, AdapterCredential(assembly.plan.binding.clientId, token))
    private fun assemble(f: ControllerFixtures, capture: ControllerFixtures.Capture, model: VerifiedProjectModel,
                         client: String, request: SubmitPlanRequest = f.request(client), prep: TestPreparations = TestPreparations()) =
        prep.assembler(f).assemble(f.binding(client), request, null, model, capture.materialization(model), listOf(client))

    @Test fun completeLiveVectorsAreRetainedWithDistinctPreparationAndOnlyExactOriginCanResolve() {
        val f = fixture(); val capture = f.capture(); val model = f.import(capture); val prep = TestPreparations()
        val a = handle(assemble(f, capture, model, "a", prep = prep))
        val b = handle(assemble(f, capture, model, "b", prep = prep))
        val registry = OwnerReviewRouteRegistry(f.namespace.ownerId)
        val supplied = mutableListOf(b, a)
        assertEquals(a.assembly.plan.request, registry.register(a, supplied))
        supplied.clear() // Caller collections never become retained authority.
        val declared = assertNotNull(registry.resolve(a.assembly.plan.binding, a.assembly.plan.request))
        assertEquals(a.assembly.plan.copy(requiredRecipientClientIds = listOf("a", "b")), declared)
        assertNull(registry.resolve(b.assembly.plan.binding, b.assembly.plan.request)) // No local-only prefix.
        val route = assertNotNull(registry.authorize(declared.binding, declared.request, declared))
        assertEquals(listOf(a.assembly.plan, b.assembly.plan), route.recipients.map { it.plan })
        assertEquals(a.assembly.plan.slots.map { it.input }, b.assembly.plan.slots.map { it.input })
        assertEquals(model.compilationIds.size * 2, route.recipients.sumOf { it.plan.slots.size })
        assertEquals(2, route.recipients.map { it.preparation.capturedPolicyDigest }.distinct().size)
        assertEquals(2, route.recipients.map { it.preparation.executionApprovalDigest }.distinct().size)
        route.recipients.forEach { recipient ->
            assertEquals(listOf(recipient.plan.binding.clientId), recipient.plan.requiredRecipientClientIds)
            assertEquals(digest(recipient.plan), recipient.preparation.planDigest)
            assertEquals(recipient.preparation, recipient.verifyForAdmission())
            recipient.plan.slots.forEach { slot ->
                val key = recipient.plan.binding to slot.input.analysisKey()
                assertEquals(recipient.plan.binding, prep.policyRecords.getValue(key).binding)
                assertEquals(recipient.plan.binding, prep.executionRecords.getValue(key).binding)
                assertEquals(f.inventory.ruleIds, slot.input.requiredRuleIds)
            }
        }
        assertFalse(canonical(declared).contains("unit-only-not-authenticated"))
        assertFailsWith<UnsupportedOperationException> { (declared.requiredRecipientClientIds as MutableList<*>).clear() }
        assertEquals(declared, registry.resolve(declared.binding, declared.request))
    }

    @Test fun wrongTuplesIncompleteOriginOrAlteredDeclaredVectorNeverRunAnyRetainedCallback() {
        val f = fixture(); val capture = f.capture(); val model = f.import(capture)
        val a = handle(assemble(f, capture, model, "a")); val b = handle(assemble(f, capture, model, "b"))
        val registry = OwnerReviewRouteRegistry(f.namespace.ownerId)
        registry.register(a, listOf(a, b))
        val declared = assertNotNull(registry.resolve(a.assembly.plan.binding, a.assembly.plan.request))
        val before = capture.verifies
        val wrongBindings = listOf(declared.binding.copy(ownerId = "other"), declared.binding.copy(workspaceId = "other"),
            declared.binding.copy(worktreeId = "other"), declared.binding.copy(lineageId = "other"),
            declared.binding.copy(sessionId = "other"), declared.binding.copy(generation = 2), b.assembly.plan.binding)
        wrongBindings.forEach { binding ->
            assertNull(registry.resolve(binding, declared.request))
            assertNull(registry.authorize(binding, declared.request, declared))
        }
        val wrongRequests = listOf(declared.request.copy(planRef = "other"), declared.request.copy(patchId = "other"),
            declared.request.copy(idempotencyKey = "other"), b.assembly.plan.request)
        wrongRequests.forEach { request ->
            assertNull(registry.resolve(declared.binding, request))
            assertNull(registry.authorize(declared.binding, request, declared))
        }
        listOf(declared.copy(requiredRecipientClientIds = listOf("a")), declared.copy(slots = declared.slots.dropLast(1)),
            declared.copy(modelCertificateId = Digests.sha256("untrusted-model"))).forEach {
            assertNull(registry.authorize(declared.binding, declared.request, it))
        }
        assertNull(registry.authorize(b.assembly.plan.binding, b.assembly.plan.request, b.assembly.plan))
        assertEquals(before, capture.verifies)
    }

    @Test fun malformedWholeSetsAndMetadataLimitsRejectBeforeReadinessWithoutReservingAnyPrefix() {
        val f = fixture(); val capture = f.capture(); val model = f.import(capture)
        val a = handle(assemble(f, capture, model, "a")); val b = handle(assemble(f, capture, model, "b"))
        val registry = OwnerReviewRouteRegistry(f.namespace.ownerId)
        val before = capture.verifies
        listOf(listOf(a), listOf(a, a), List(65) { a }).forEach {
            assertCode("route_recipients_invalid") { registry.register(a, it) }
        }
        assertCode("route_recipients_invalid") { registry.register(handle(a.assembly), listOf(a, b)) }
        assertCode("route_adapter_mismatch") { registry.register(a, listOf(a, OwnerRouteRecipient(b.assembly, a.adapter))) }
        for (token in listOf("", " ", "x".repeat(513))) {
            assertCode("route_adapter_invalid") { registry.register(a, listOf(a, handle(b.assembly, token))) }
        }
        assertCode("registry_owner_mismatch") { OwnerReviewRouteRegistry("other-owner").register(a, listOf(a, b)) }
        val tiny = OwnerReviewRouteRegistry(f.namespace.ownerId, maxRouteBytes = 1)
        assertCode("route_metadata_limit") { tiny.register(a, listOf(a, b)) }
        assertNull(tiny.resolve(a.assembly.plan.binding, a.assembly.plan.request))
        assertEquals(before, capture.verifies)
        val other = fixture(f.namespace.copy(workspaceId = "other-workspace"))
        val otherCapture = other.capture(); val otherModel = other.import(otherCapture)
        val foreign = handle(assemble(other, otherCapture, otherModel, "b"))
        assertCode("route_namespace_mismatch") { registry.register(a, listOf(a, foreign)) }
        assertNull(registry.resolve(a.assembly.plan.binding, a.assembly.plan.request))
        assertEquals(a.assembly.plan.request, registry.register(a, listOf(a, b)))
    }

    @Test fun outOfRangeCollectionSizesRejectBeforeIteratorOrAnyAdmissionCallback() {
        val f = fixture(); val capture = f.capture(); val model = f.import(capture); val prep = TestPreparations()
        val a = handle(assemble(f, capture, model, "a", prep = prep))
        val b = handle(assemble(f, capture, model, "b", prep = prep))
        val registry = OwnerReviewRouteRegistry(f.namespace.ownerId, maxPlans = 2)
        var iteratorCalls = 0; var retainCalls = 0
        prep.afterPolicyRetain = { retainCalls++ }; prep.afterExecutionRetain = { retainCalls++ }
        val before = capture.verifies
        for (advertisedSize in listOf(0, 1, 65, Int.MAX_VALUE)) {
            val oversized = object : AbstractCollection<OwnerRouteRecipient>() {
                override val size: Int = advertisedSize
                override fun iterator(): Iterator<OwnerRouteRecipient> {
                    iteratorCalls++
                    error("An out-of-range collection must never be materialized")
                }
            }
            assertCode("route_recipients_invalid") { registry.register(a, oversized) }
            assertEquals(0, iteratorCalls, "size=$advertisedSize")
        }
        assertEquals(before, capture.verifies); assertEquals(0, retainCalls)
        assertNull(registry.resolve(a.assembly.plan.binding, a.assembly.plan.request))
        assertNull(registry.resolve(b.assembly.plan.binding, b.assembly.plan.request))
        assertEquals(a.assembly.plan.request, registry.register(a, listOf(a, b))) // No reserved prefix/capacity.
    }

    @Test fun inconsistentCollectionSizesRejectBeforeAnExtraElementOrAnyAdmissionCallback() {
        val f = fixture(); val capture = f.capture(); val model = f.import(capture); val prep = TestPreparations()
        val a = handle(assemble(f, capture, model, "a", prep = prep))
        val b = handle(assemble(f, capture, model, "b", prep = prep))
        val registry = OwnerReviewRouteRegistry(f.namespace.ownerId, maxPlans = 2)
        var nextCalls = 0; var hasNextCalls = 0; var retainCalls = 0
        prep.afterPolicyRetain = { retainCalls++ }; prep.afterExecutionRetain = { retainCalls++ }
        val before = capture.verifies
        val underreported = object : AbstractCollection<OwnerRouteRecipient>() {
            override val size: Int = 2
            override fun iterator(): Iterator<OwnerRouteRecipient> = object : Iterator<OwnerRouteRecipient> {
                override fun hasNext(): Boolean { hasNextCalls++; return true }
                override fun next(): OwnerRouteRecipient {
                    nextCalls++
                    check(nextCalls <= 2) { "A bounded detach must not request the unending collection's extra element" }
                    return if (nextCalls == 1) a else b
                }
            }
        }
        assertCode("route_recipients_invalid") { registry.register(a, underreported) }
        assertEquals(2, nextCalls); assertEquals(3, hasNextCalls)
        val overreported = object : AbstractCollection<OwnerRouteRecipient>() {
            override val size: Int = 3
            override fun iterator(): Iterator<OwnerRouteRecipient> = listOf(a, b).iterator()
        }
        assertCode("route_recipients_invalid") { registry.register(a, overreported) }
        assertEquals(before, capture.verifies); assertEquals(0, retainCalls)
        assertNull(registry.resolve(a.assembly.plan.binding, a.assembly.plan.request))
        assertNull(registry.resolve(b.assembly.plan.binding, b.assembly.plan.request))
        assertEquals(a.assembly.plan.request, registry.register(a, listOf(a, b)))
    }

    @Test fun wholeRouteMetadataUsesExactUtf8EnvelopeAndRejectsOneByteShortWithoutCallbacks() {
        val f = fixture(); val capture = f.capture(); val model = f.import(capture); val prep = TestPreparations()
        val a = handle(assemble(f, capture, model, "a", f.request("a-é-日-🚀-\"-\\"), prep))
        val b = handle(assemble(f, capture, model, "b", f.request("b-é-日-🚀-\"-\\"), prep))
        val reference = OwnerReviewRouteRegistry(f.namespace.ownerId)
        reference.register(a, listOf(b, a))
        val declared = assertNotNull(reference.resolve(a.assembly.plan.binding, a.assembly.plan.request))
        val route = assertNotNull(reference.authorize(declared.binding, declared.request, declared))
        // Measure only returned evidence from real live handles; parsing it does not create a
        // route capability. The envelope uses raw JSON objects, not strings of escaped vectors.
        val envelope = JsonArray(listOf(ContractJson.parseToJsonElement(canonical(declared)),
            JsonArray(route.recipients.map { recipient -> JsonArray(listOf(
                ContractJson.parseToJsonElement(canonical(recipient.plan)),
                ContractJson.parseToJsonElement(canonical(recipient.preparation)),
            )) })))
        val encoded = Digests.canonical(envelope)
        val exactBytes = encoded.toByteArray(Charsets.UTF_8).size
        assertTrue(exactBytes > encoded.length, "Fixture must distinguish UTF-8 bytes from character count")
        var retainCalls = 0
        prep.afterPolicyRetain = { retainCalls++ }; prep.afterExecutionRetain = { retainCalls++ }
        val before = capture.verifies
        val oneByteShort = OwnerReviewRouteRegistry(f.namespace.ownerId, maxRouteBytes = exactBytes - 1)
        assertCode("route_metadata_limit") { oneByteShort.register(a, listOf(a, b)) }
        assertNull(oneByteShort.resolve(a.assembly.plan.binding, a.assembly.plan.request))
        assertNull(oneByteShort.resolve(b.assembly.plan.binding, b.assembly.plan.request))
        assertEquals(before, capture.verifies); assertEquals(0, retainCalls)
        val exact = OwnerReviewRouteRegistry(f.namespace.ownerId, maxRouteBytes = exactBytes)
        assertEquals(a.assembly.plan.request, exact.register(a, listOf(b, a)))
        assertEquals(declared, exact.resolve(declared.binding, declared.request))
        assertEquals(route.recipients.map { it.plan },
            assertNotNull(exact.authorize(declared.binding, declared.request, declared)).recipients.map { it.plan })
        assertTrue(capture.verifies > before); assertTrue(retainCalls > 0)
    }

    @Test fun equalWholeRetriesRetainOriginalCallbacksButDifferentProvenanceOrAdapterCannotReplaceThem() {
        val f = fixture(); val capture = f.capture(); val model = f.import(capture)
        val originalPrep = TestPreparations(); val originalCalls = AtomicInteger()
        val a = handle(assemble(f, capture, model, "a", prep = originalPrep))
        val b = handle(assemble(f, capture, model, "b", prep = originalPrep))
        val registry = OwnerReviewRouteRegistry(f.namespace.ownerId)
        registry.register(a, listOf(a, b))
        val replacementPrep = TestPreparations(); val replacementCalls = AtomicInteger()
        val aa = handle(assemble(f, capture, model, "a", prep = replacementPrep))
        val bb = handle(assemble(f, capture, model, "b", prep = replacementPrep))
        assertEquals(a.assembly.plan, aa.assembly.plan)
        originalPrep.afterPolicyRetain = { originalCalls.incrementAndGet() }
        replacementPrep.afterPolicyRetain = { replacementCalls.incrementAndGet() }
        assertEquals(a.assembly.plan.request, registry.register(aa, listOf(bb, aa)))
        assertTrue(replacementCalls.get() > 0); assertTrue(originalCalls.get() > 0)
        originalCalls.set(0); replacementCalls.set(0)
        val declared = assertNotNull(registry.resolve(a.assembly.plan.binding, a.assembly.plan.request))
        val route = assertNotNull(registry.authorize(declared.binding, declared.request, declared))
        route.recipients.forEach { it.verifyForAdmission() }
        assertTrue(originalCalls.get() > 0); assertEquals(0, replacementCalls.get())
        val changedPrep = TestPreparations().apply { policyRevision = Digests.sha256("new-revision-same-policy-bytes") }
        val changed = handle(assemble(f, capture, model, "b", prep = changedPrep))
        assertEquals(b.assembly.plan, changed.assembly.plan)
        assertCode("route_provenance_conflict") { registry.register(a, listOf(a, changed)) }
        assertCode("route_provenance_conflict") { registry.register(a, listOf(a, handle(b.assembly, "different-enrollment"))) }
        assertCode("route_reference_conflict") { registry.register(b, listOf(a, b)) } // Cannot rotate the origin.
        assertEquals(declared, registry.resolve(a.assembly.plan.binding, a.assembly.plan.request))
    }

    @Test fun aLateMembersReferencePatchOrIdempotencyCollisionLeavesEveryNewMemberUnreserved() {
        for (kind in listOf("reference", "patch", "idempotency")) {
            val f = fixture(); val capture = f.capture(); val model = f.import(capture)
            val registry = OwnerReviewRouteRegistry(f.namespace.ownerId, maxPlans = 4)
            val seed = handle(assemble(f, capture, model, "m"))
            val z = handle(assemble(f, capture, model, "z", f.request("seed-z")))
            registry.register(seed, listOf(seed, z))
            val a = handle(assemble(f, capture, model, "a"))
            val unique = f.request("new-z")
            val collision = when (kind) {
                "reference" -> unique.copy(planRef = z.assembly.plan.request.planRef)
                "patch" -> unique.copy(patchId = z.assembly.plan.request.patchId)
                else -> unique.copy(idempotencyKey = z.assembly.plan.request.idempotencyKey)
            }
            val late = handle(assemble(f, capture, model, "z", collision))
            assertCode("route_${kind}_conflict") { registry.register(a, listOf(a, late)) }
            assertNull(registry.resolve(a.assembly.plan.binding, a.assembly.plan.request))
            val b = handle(assemble(f, capture, model, "b"))
            assertEquals(a.assembly.plan.request, registry.register(a, listOf(a, b))) // Also proves no leaked capacity.
            assertNotNull(registry.resolve(seed.assembly.plan.binding, seed.assembly.plan.request))
            assertNotNull(registry.resolve(a.assembly.plan.binding, a.assembly.plan.request))
        }
    }

    @Test fun concurrentExactRegistrationAndCapacityChecksNeverReplaceOrInstallALocalPrefix() {
        val f = fixture(); val capture = f.capture(); val model = f.import(capture)
        val prep = TestPreparations()
        // TestPreparations' maps are otherwise deliberately simple. Serialize just their own
        // retention instrumentation, never the registry lock or imported source verification.
        val assembler = OwnerPlanAssembler(f.inventory, OwnerPolicyPreparer { request ->
            val captured = prep.policy(request)
            CapturedPlanPolicy(request, captured.identity, captured.input) { final -> synchronized(prep) { captured.retainForAdmission(final) } }
        }, OwnerExecutionPreparer { request ->
            val captured = prep.execution(request)
            CapturedPlanExecution(request, captured.identity, captured.profiles, captured.input) { final ->
                synchronized(prep) { captured.retainForAdmission(final) }
            }
        })
        fun member(client: String) = handle(assembler.assemble(f.binding(client), f.request(client), null, model,
            capture.materialization(model), listOf(client)))
        val a = member("a"); val b = member("b"); val c = member("c"); val d = member("d")
        val registry = OwnerReviewRouteRegistry(f.namespace.ownerId, maxPlans = 3)
        val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
        try {
            val results = (1..2).map { pool.submit<SubmitPlanRequest> { assertTrue(start.await(5, TimeUnit.SECONDS)); registry.register(a, listOf(b, a)) } }
            start.countDown()
            results.forEach { assertEquals(a.assembly.plan.request, it.get(10, TimeUnit.SECONDS)) }
            assertCode("route_registry_full") { registry.register(c, listOf(c, d)) }
            assertNull(registry.resolve(c.assembly.plan.binding, c.assembly.plan.request))
            assertNull(registry.resolve(d.assembly.plan.binding, d.assembly.plan.request))
            assertNotNull(registry.resolve(a.assembly.plan.binding, a.assembly.plan.request))
            assertEquals(a.assembly.plan.request, registry.register(a, listOf(a, b)))
        } finally { start.countDown(); pool.shutdownNow() }
    }

    @Test fun everyRecipientsSourcePolicyAndExecutionRemainLiveAndCallbacksRunOutsideRegistryLocks() {
        val f = fixture(); val capture = f.capture(); val model = f.import(capture)
        val pa = TestPreparations(); val pb = TestPreparations()
        val a = handle(assemble(f, capture, model, "a", prep = pa)); val b = handle(assemble(f, capture, model, "b", prep = pb))
        val registry = OwnerReviewRouteRegistry(f.namespace.ownerId)
        val pool = Executors.newSingleThreadExecutor()
        try {
            pb.afterPolicyRetain = { assertNull(pool.submit<RequiredReviewPlan?> {
                registry.resolve(a.assembly.plan.binding, f.request("unknown"))
            }.get(5, TimeUnit.SECONDS)) }
            registry.register(a, listOf(a, b))
            val declared = assertNotNull(registry.resolve(a.assembly.plan.binding, a.assembly.plan.request))
            val grant = assertNotNull(registry.authorize(declared.binding, declared.request, declared))
            grant.recipients.forEach { assertEquals(it.preparation, it.verifyForAdmission()) }
            pb.afterPolicyRetain = {}
            val savedPolicy = pb.policyRevision; val savedApproval = pb.executionApproval
            pb.policyRevision = Digests.sha256("b-policy-revoked")
            assertCode("policy_authority_changed") { registry.resolve(declared.binding, declared.request) }
            assertCode("policy_authority_changed") { grant.recipients.single { it.plan.binding.clientId == "b" }.verifyForAdmission() }
            pb.policyRevision = savedPolicy; pb.executionApproval = Digests.sha256("b-execution-revoked")
            assertCode("execution_authority_changed") { registry.authorize(declared.binding, declared.request, declared) }
            pb.executionApproval = savedApproval
            val epoch = capture.epoch
            capture.epoch = Digests.sha256("same-capture-ABA")
            assertCode("materialization_authority_changed") { registry.resolve(declared.binding, declared.request) }
            capture.epoch = epoch; capture.available = false
            assertCode("materialization_unavailable") { registry.resolve(declared.binding, declared.request) }
            capture.available = true
            assertEquals(declared, registry.resolve(declared.binding, declared.request))
        } finally { pb.afterPolicyRetain = {}; pool.shutdownNow() }
    }

    @Test fun unsupportedScopeStaysWholeAndUnexecutedButMissingCapturedPolicyCannotCreateARoute() {
        val f = fixture(); val capture = f.capture(); val model = f.import(capture)
        val a = handle(assemble(f, capture, model, "a"))
        val empty = f.import(capture, f.graph(capture).copy(sourceSets = emptyList(), compilations = emptyList()))
        val denied = OwnerPlanAssembler(f.inventory).assemble(f.binding("b"), f.request("b"), null, empty,
            capture.materialization(empty), listOf("b"))
        assertEquals("unsupported", denied.plan.slots.single().state)
        val registry = OwnerReviewRouteRegistry(f.namespace.ownerId)
        assertCode("route_policy_unavailable") { registry.register(a, listOf(a, handle(denied))) }
        assertNull(registry.resolve(a.assembly.plan.binding, a.assembly.plan.request))
        val prep = TestPreparations()
        val b = handle(assemble(f, capture, empty, "b", prep = prep))
        assertTrue(prep.executionRequests.isEmpty()); assertTrue(prep.executionRecords.isEmpty())
        registry.register(a, listOf(a, b))
        val declared = assertNotNull(registry.resolve(a.assembly.plan.binding, a.assembly.plan.request))
        val recipient = assertNotNull(registry.authorize(declared.binding, declared.request, declared)).recipients.single { it.plan.binding.clientId == "b" }
        assertEquals(b.assembly.plan, recipient.plan)
        val slot = recipient.plan.slots.single()
        assertEquals("unsupported", slot.state); assertContains(slot.reasons, "required_project_scope_unavailable")
        assertEquals(f.inventory.ruleIds, slot.input.requiredRuleIds)
        assertNull(b.assembly.provenance.slots.single().executionIdentity)
        assertEquals(recipient.preparation, recipient.verifyForAdmission())
        assertTrue(prep.executionRecords.isEmpty()) // A vector digest is not approval to execute unavailable scope.
    }

    @Test fun serializedEvidenceCannotReconstituteTheNonserializedClosedCollectionOrLiveCallbacks() {
        val f = fixture(); val capture = f.capture(); val model = f.import(capture)
        val a = handle(assemble(f, capture, model, "a")); val b = handle(assemble(f, capture, model, "b"))
        val registry = OwnerReviewRouteRegistry(f.namespace.ownerId).also { it.register(a, listOf(a, b)) }
        val declared = assertNotNull(registry.resolve(a.assembly.plan.binding, a.assembly.plan.request))
        val decoded = ContractJson.decodeFromString<RequiredReviewPlan>(canonical(declared))
        assertEquals(declared, decoded)
        val fresh = OwnerReviewRouteRegistry(f.namespace.ownerId)
        assertNull(fresh.resolve(decoded.binding, decoded.request)); assertNull(fresh.authorize(decoded.binding, decoded.request, decoded))
        assertEquals(listOf(AssembledReviewPlan::class.java, AdapterCredential::class.java),
            OwnerRouteRecipient::class.java.constructors.single().parameterTypes.toList())
        assertEquals(listOf(OwnerRouteRecipient::class.java, Collection::class.java),
            OwnerReviewRouteRegistry::class.java.methods.single { it.name == "register" }.parameterTypes.toList())
        assertFalse(a.toString().contains(a.adapter.token))
        fresh.register(a, listOf(a, b)) // Only the still-live original capabilities can register again.
        assertEquals(decoded, fresh.resolve(decoded.binding, decoded.request))
    }
}

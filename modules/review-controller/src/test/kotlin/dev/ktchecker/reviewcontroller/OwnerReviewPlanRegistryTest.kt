package dev.ktchecker.reviewcontroller

import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.*
import dev.ktchecker.projectmodel.GradleProjectExport
import dev.ktchecker.projectmodel.VerifiedProjectModel
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OwnerReviewPlanRegistryTest {
    @TempDir lateinit var temp: Path
    private fun fixture() = ControllerFixtures(temp.resolve(UUID.randomUUID().toString()))
    private fun assertCode(code: String, action: () -> Unit) = assertEquals(code, assertFailsWith<ReviewControllerException>(block = action).code)

    @Test fun exactRegistrationIsIdempotentButReferencesPatchKeysAndAuthorityCannotBeRetargeted() {
        val f = fixture(); val capture = f.capture(); val model = f.import(capture); val prep = TestPreparations()
        fun assemble(request: SubmitPlanRequest = f.request(), preparations: TestPreparations = prep) = preparations.assembler(f)
            .assemble(f.binding(), request, null, model, capture.materialization(model), listOf(f.binding().clientId))
        val original = assemble(); val registry = OwnerReviewPlanRegistry(f.namespace.ownerId)
        assertEquals(f.request(), registry.register(original)); assertEquals(f.request(), registry.register(original))
        val equivalent = assemble(); assertEquals(original.planDigest, equivalent.planDigest)
        assertEquals(f.request(), registry.register(equivalent)); assertSame(original.plan, registry.resolve(f.binding(), f.request()))
        assertCode("plan_reference_conflict") { registry.register(assemble(f.request().copy(patchId = "other-patch", idempotencyKey = "other-key"))) }
        assertCode("plan_patch_conflict") { registry.register(assemble(f.request().copy(planRef = "other-ref", idempotencyKey = "other-key"))) }
        assertCode("plan_idempotency_conflict") { registry.register(assemble(f.request().copy(planRef = "other-ref", patchId = "other-patch"))) }
        val changedAuthority = TestPreparations().apply { policyRevision = Digests.sha256("different-revision-with-same-config-digest") }
        val altered = assemble(preparations = changedAuthority)
        assertEquals(original.plan, altered.plan)
        assertCode("plan_provenance_conflict") { registry.register(altered) }
        assertSame(original.plan, registry.resolve(f.binding(), f.request()))
        val before = capture.verifies
        assertCode("registry_owner_mismatch") { OwnerReviewPlanRegistry("other-owner").register(original) }
        assertEquals(before, capture.verifies)
        val bounded = OwnerReviewPlanRegistry(f.namespace.ownerId, maxPlans = 1)
        bounded.register(original)
        assertCode("plan_registry_full") { bounded.register(assemble(f.request("second"))) }
        assertSame(original.plan, bounded.resolve(f.binding(), f.request()))
    }

    @Test fun wrongBindingOrAnyRequestTupleMemberCannotResolveARegisteredPlan() {
        val f = fixture(); val capture = f.capture(); val model = f.import(capture); val prep = TestPreparations()
        val registry = OwnerReviewPlanRegistry(f.namespace.ownerId)
        val assembly = prep.assembler(f).assemble(f.binding(), f.request(), null, model, capture.materialization(model), listOf(f.binding().clientId))
        registry.register(assembly); val verifies = capture.verifies
        val bindings = listOf(f.binding("other-client"), f.binding().copy(ownerId = "other"), f.binding().copy(workspaceId = "other"),
            f.binding().copy(worktreeId = "other"), f.binding().copy(lineageId = "other"), f.binding().copy(sessionId = "other"), f.binding().copy(generation = 2))
        bindings.forEach { assertNull(registry.resolve(it, f.request())) }
        listOf(f.request().copy(patchId = "other"), f.request().copy(idempotencyKey = "other"), f.request().copy(planRef = "other")).forEach {
            assertNull(registry.resolve(f.binding(), it))
        }
        assertEquals(verifies, capture.verifies) // Wrong tuples do not restore/grant another client's state.
    }

    @Test fun sourcePolicyAndExecutionAreReverifiedOutsideMapLocksOnEveryResolution() {
        val f = fixture(); val capture = f.capture(); val model = f.import(capture); val prep = TestPreparations()
        val registry = OwnerReviewPlanRegistry(f.namespace.ownerId)
        val assembly = prep.assembler(f).assemble(f.binding(), f.request(), null, model, capture.materialization(model), listOf(f.binding().clientId))
        val executor = Executors.newSingleThreadExecutor()
        try {
            // Another thread must be able to take the registry map lock while readiness is running.
            prep.afterPolicyRetain = { assertNull(executor.submit<RequiredReviewPlan?> { registry.resolve(f.binding(), f.request("unknown")) }.get(5, TimeUnit.SECONDS)) }
            registry.register(assembly)
            val verifies = capture.verifies
            assertSame(assembly.plan, registry.resolve(f.binding(), f.request()))
            assertTrue(capture.verifies >= verifies + 2)
        } finally { prep.afterPolicyRetain = {}; executor.shutdownNow() }
        prep.policyRevision = Digests.sha256("revoked-policy")
        assertCode("policy_authority_changed") { registry.resolve(f.binding(), f.request()) }
        prep.policyRevision = assembly.provenance.slots.first().policyIdentity!!.revisionDigest
        prep.executionApproval = Digests.sha256("revoked-execution")
        assertCode("execution_authority_changed") { registry.resolve(f.binding(), f.request()) }
        prep.executionApproval = assembly.provenance.slots.first().executionIdentity!!.approvalDigest
        capture.epoch = Digests.sha256("source-aba-even-with-identical-bytes")
        assertCode("materialization_authority_changed") { registry.resolve(f.binding(), f.request()) }
    }

    @Test fun changedSourceBytesOrUnavailableCaptureCannotUseAnAlreadyRegisteredReference() {
        val f = fixture(); val capture = f.capture(); val model = f.import(capture); val prep = TestPreparations()
        val registry = OwnerReviewPlanRegistry(f.namespace.ownerId)
        val assembly = prep.assembler(f).assemble(f.binding(), f.request(), null, model, capture.materialization(model), listOf(f.binding().clientId))
        registry.register(assembly)
        capture.available = false
        assertCode("materialization_unavailable") { registry.resolve(f.binding(), f.request()) }
        capture.available = true
        Files.writeString(capture.root.resolve("lib/src/commonMain/kotlin/Common.kt"), "fun commonValue() = 999\n")
        assertFalse(capture.verifyRegistered())
        assertCode("materialization_unavailable") { registry.resolve(f.binding(), f.request()) }
        val provider = object : AnalysisProvider {
            override val id = "unit-no-worker"
            override val supportedRuleIds = emptySet<String>()
            override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult = error("No provider may run")
        }
        KernelService.open(f.work.resolve("broker-rejected"), provider, KernelConfig(workerCount = 0), planAuthority = registry).use { kernel ->
            val client = kernel.registerClient(f.binding())
            assertCode("materialization_unavailable") { kernel.submitPlan(client.client, f.request()) }
            assertTrue(kernel.events(client.client).events.isEmpty())
        }
    }

    @Test fun allCollectionsAndProfilesAreDetachedAndRawDtoRoundTripsCannotRecreateRegistryAuthority() {
        val f = fixture(); val capture = f.capture(); val model = f.import(capture); val prep = TestPreparations()
        val rules = f.inventory.ruleIds.toMutableList()
        val inventory = OwnerCatalogInventory(f.inventory.catalogDigest, rules)
        rules.clear(); assertEquals(f.inventory.ruleIds, inventory.ruleIds)
        val recipients = mutableListOf(f.binding().clientId)
        val savedProfiles = mutableListOf<CapturedPlanExecution>()
        val assembler = OwnerPlanAssembler(inventory, OwnerPolicyPreparer(prep::policy), OwnerExecutionPreparer { request ->
            val identity = CapturedExecutionIdentity(request.binding, request.input.analysisKey(), request.input.context.toolchainDigest!!, prep.executionApproval)
            val args = mutableListOf("--unit-original"); val environment = mutableMapOf("UNIT" to "original")
            val profiles = mutableMapOf("unit-provider" to TrustedExecution("/unit/no-execution", args, environment = environment))
            CapturedPlanExecution(request, identity, profiles, request.withProfiles(profiles)) { identity }.also {
                savedProfiles += it; args.clear(); environment["UNIT"] = "changed"; profiles.clear()
            }
        })
        val assembly = assembler.assemble(f.binding(), f.request(), null, model, capture.materialization(model), recipients)
        recipients += "another-client"
        assertEquals(listOf(f.binding().clientId), assembly.plan.requiredRecipientClientIds)
        assertFailsWith<UnsupportedOperationException> { (assembly.plan.slots as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (assembly.plan.requiredRecipientClientIds as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (inventory.ruleIds as MutableList<*>).clear() }
        val input = assembly.plan.slots.first().input
        assertFailsWith<UnsupportedOperationException> { (input.snapshot.files as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (input.snapshot.declaredRoots as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (input.requiredRuleIds as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (input.context.compilationFiles as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (input.context.compilerArguments as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (input.context.classpath as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (input.context.libraries as MutableMap<*, *>).clear() }
        assertFailsWith<UnsupportedOperationException> { (input.context.policies as MutableMap<*, *>).clear() }
        assertFailsWith<UnsupportedOperationException> { (assembly.provenance.slots as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (assembly.provenance.slots.first().explanations as MutableList<*>).clear() }
        savedProfiles.forEach {
            assertEquals(listOf("--unit-original"), it.profiles.getValue("unit-provider").arguments)
            assertEquals(mapOf("UNIT" to "original"), it.profiles.getValue("unit-provider").environment)
            assertFailsWith<UnsupportedOperationException> { (it.profiles as MutableMap<*, *>).clear() }
            assertFailsWith<UnsupportedOperationException> { (it.profiles.getValue("unit-provider").arguments as MutableList<*>).clear() }
            assertFailsWith<UnsupportedOperationException> { (it.profiles.getValue("unit-provider").environment as MutableMap<*, *>).clear() }
        }
        val decoded = ContractJson.decodeFromString<RequiredReviewPlan>(canonical(assembly.plan))
        assertEquals(assembly.plan, decoded)
        val freshRegistry = OwnerReviewPlanRegistry(f.namespace.ownerId)
        assertNull(freshRegistry.resolve(decoded.binding, decoded.request))
        val registrationMethods = OwnerReviewPlanRegistry::class.java.methods.filter { it.name == "register" }
        assertEquals(listOf(AssembledReviewPlan::class.java), registrationMethods.single().parameterTypes.toList())
        val assemblyParameters = OwnerPlanAssembler::class.java.methods.single { it.name == "assemble" }.parameterTypes.toList()
        assertEquals(2, assemblyParameters.count { it == VerifiedProjectModel::class.java })
        assertFalse(GradleProjectExport::class.java in assemblyParameters)
        assertFalse(RequiredReviewPlan::class.java in assemblyParameters)
        freshRegistry.register(assembly)
        assertSame(assembly.plan, freshRegistry.resolve(f.binding(), f.request()))
        assertNull(OwnerReviewPlanRegistry(f.namespace.ownerId).resolve(f.binding(), f.request())) // No unsafe restart restore path.
    }

    @Test fun independentSameInputClientsKeepDistinctPreparationsSubscriptionsAndRecipientAuthority() {
        val f = fixture(); val capture = f.capture(); val model = f.import(capture); val prep = TestPreparations()
        val a = f.binding("a"); val b = f.binding("b"); val registry = OwnerReviewPlanRegistry(f.namespace.ownerId)
        val first = prep.assembler(f).assemble(a, f.request("a"), null, model, capture.materialization(model), listOf(a.clientId))
        val second = prep.assembler(f).assemble(b, f.request("b"), null, model, capture.materialization(model), listOf(b.clientId))
        registry.register(first); registry.register(second)
        assertEquals(first.plan.slots.map { it.input }, second.plan.slots.map { it.input })
        first.plan.slots.forEach { assertTrue(a to it.input.analysisKey() in prep.policyRecords); assertTrue(b to it.input.analysisKey() in prep.policyRecords) }
        assertNull(registry.resolve(b, first.plan.request)); assertNull(registry.resolve(a, second.plan.request))
        val provider = object : AnalysisProvider {
            override val id = "unit-no-worker"
            // Empty installed support cannot shrink the owner inventory used for either client.
            override val supportedRuleIds = emptySet<String>()
            override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult = error("No worker configured")
        }
        KernelService.open(f.work.resolve("broker"), provider, KernelConfig(workerCount = 0), planAuthority = registry).use { kernel ->
            val ca = kernel.registerClient(a); val cb = kernel.registerClient(b)
            val pa = kernel.submitPlan(ca.client, first.plan.request); val pb = kernel.submitPlan(cb.client, second.plan.request)
            assertEquals("pending", pa.state); assertEquals("pending", pb.state)
            assertTrue(pa.slots.map { it.reviewId }.toSet().intersect(pb.slots.map { it.reviewId }.toSet()).isEmpty())
            assertTrue(pa.slots.all { it.requiredRuleIds == f.inventory.ruleIds }); assertTrue(pb.slots.all { it.requiredRuleIds == f.inventory.ruleIds })
            val ea = kernel.events(ca.client).events; val eb = kernel.events(cb.client).events
            assertEquals(first.plan.slots.size + 1, ea.size); assertEquals(second.plan.slots.size + 1, eb.size)
            assertTrue(ea.all { it.clientId == a.clientId }); assertTrue(eb.all { it.clientId == b.clientId })
            assertEquals(1, ea.count { it.kind == "plan_accepted" }); assertEquals(1, eb.count { it.kind == "plan_accepted" })
            assertFailsWith<KernelException> { kernel.planStatus(cb.client, pa.planId) }
            assertEquals("cancelled", kernel.cancelPlan(ca.client, pa.planId).state)
            assertEquals("pending", kernel.planStatus(cb.client, pb.planId).state)
        }
    }

    @Test fun emptyModelAdministrativePlanIsKernelAdmissibleButNeverExecutesOrGetsFinalAuthority() {
        val f = fixture(); val capture = f.capture(); val graph = f.graph(capture)
        val empty = f.import(capture, graph.copy(sourceSets = emptyList(), compilations = emptyList()))
        val assembly = OwnerPlanAssembler(f.inventory).assemble(f.binding(), f.request(), null, empty, capture.materialization(empty), listOf(f.binding().clientId))
        val registry = OwnerReviewPlanRegistry(f.namespace.ownerId).also { it.register(assembly) }
        var calls = 0
        val provider = object : AnalysisProvider {
            override val id = "unit-no-execution"
            override val supportedRuleIds = f.inventory.ruleIds.toSet()
            override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult { calls++; error("Unsupported is not executable") }
        }
        KernelService.open(f.work.resolve("broker-empty"), provider, KernelConfig(workerCount = 0), planAuthority = registry).use { kernel ->
            val client = kernel.registerClient(f.binding())
            val parent = kernel.submitPlan(client.client, f.request())
            assertEquals("incomplete", parent.state); assertEquals("unsupported", parent.slots.single().state)
            assertEquals(f.inventory.ruleIds, parent.slots.single().requiredRuleIds)
            assertEquals(0, calls)
            assertFalse(kernel.completeGate(client.client, kernel.prepareGate(client.client).cutId).allowed)
            assertEquals("cancelled", kernel.cancelPlan(client.client, parent.planId).state)
        }
    }
}

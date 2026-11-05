package dev.ktchecker.reviewcontroller

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import dev.ktchecker.kernel.*
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Source/controller controls with SYNTHETIC owner preparations; no provider or real host runs. */
class OwnerIntegratedCompletionRegistryTest {
    @TempDir lateinit var temp: Path

    private class UnavailableFence : OwnerIntegratedSnapshotFence {
        override val identity = Digests.sha256("synthetic-unavailable-owner-joint-fence")
        override fun <T> withCurrentIntegratedSnapshot(assemblies: List<AssembledReviewPlan>, integration: AssembledReviewPlan,
            request: IntegratedFenceRequest, action: (IntegratedSnapshotCheck) -> T): T =
            action(IntegratedSnapshotCheck(reasons = listOf("real_host_integration_not_installed")))
    }

    private class UnavailableAction : IntegratedCompletionAction {
        override val identity = Digests.sha256("synthetic-unavailable-final-action")
        override fun perform(permit: IntegratedCompletionPermit): IntegratedActionReceipt? = null
    }

    private class Fixture(
        val owner: ControllerFixtures,
        val capture: ControllerFixtures.Capture,
        val preparations: TestPreparations,
        val members: List<OwnerRouteRecipient>,
        val registry: OwnerIntegratedCompletionRegistry,
        val fence: UnavailableFence = UnavailableFence(),
        val action: UnavailableAction = UnavailableAction(),
    ) {
        fun register() = registry.register("complete", members.first(), members.first(), members, fence, action)
        fun resolve() = registry.authorize(members.first().assembly.plan.binding, "complete")
    }

    private fun fixture(): Fixture {
        val owner = ControllerFixtures(temp.resolve(UUID.randomUUID().toString()))
        val capture = owner.capture(); val model = owner.import(capture); val preparations = TestPreparations()
        val members = listOf("a", "b").mapIndexed { index, id ->
            val binding = owner.binding(id)
            val assembly = preparations.assembler(owner).assemble(binding, owner.request(id), null, model, capture.materialization(model), listOf(id))
            // Synthetic test tokens only: actual independent enrollment is tested in kernel tests.
            OwnerRouteRecipient(assembly, AdapterCredential(id, ('A' + index).toString().repeat(43)))
        }
        return Fixture(owner, capture, preparations, members, OwnerIntegratedCompletionRegistry(owner.namespace.ownerId))
    }

    private fun assertCode(code: String, block: () -> Unit) = assertEquals(code, assertFailsWith<ReviewControllerException>(block = block).code)

    @Test fun completeLiveHandlesKeepExactIndependentBindingsAndCannotBeReplacedByEqualDtoAuthority() {
        val f = fixture()
        assertNull(f.resolve())
        assertEquals("complete", f.register()); assertEquals("complete", f.register())
        val grant = assertNotNull(f.resolve())
        assertEquals(listOf("a", "b"), grant.members.map { it.binding.clientId })
        grant.members.zip(f.members).forEach { (member, handle) ->
            assertEquals(handle.assembly.plan.binding, member.binding)
            assertEquals(handle.assembly.plan.request, member.request)
            assertEquals(handle.assembly.planDigest, member.planDigest)
            assertEquals(handle.adapter, member.adapter)
            assertEquals(handle.assembly.registrationDigest, member.verifyForCompletion())
        }
        assertEquals(f.owner.inventory.ruleIds, grant.requiredRuleIds)
        assertEquals(f.owner.inventory.catalogDigest, grant.catalogDigest)
        assertCode("joint_reference_conflict") {
            f.registry.register("complete", f.members.first(), f.members.first(), f.members, UnavailableFence(), f.action)
        }
        assertCode("joint_reference_conflict") {
            f.registry.register("complete", f.members.first(), f.members.first(), f.members, f.fence, UnavailableAction())
        }
        val decoded = ContractJson.decodeFromString<RequiredReviewPlan>(canonical(f.members.first().assembly.plan))
        assertNull(OwnerIntegratedCompletionRegistry(f.owner.namespace.ownerId).authorize(decoded.binding, "complete"))
        val types = OwnerIntegratedCompletionRegistry::class.java.methods.single { it.name == "register" }.parameterTypes.toList()
        assertEquals(2, types.count { it == OwnerRouteRecipient::class.java })
        assertFalse(RequiredReviewPlan::class.java in types)
    }

    @Test fun everyWrongOriginBindingAndPeerCannotResolveOrRunReadinessCallbacks() {
        val f = fixture(); f.register()
        val origin = f.members.first().assembly.plan.binding
        val before = f.capture.verifies
        listOf(origin.copy(ownerId = "other"), origin.copy(workspaceId = "other"), origin.copy(worktreeId = "other"),
            origin.copy(clientId = "other"), origin.copy(lineageId = "other"), origin.copy(sessionId = "other"),
            origin.copy(generation = 2), f.members.last().assembly.plan.binding).forEach {
            assertNull(f.registry.authorize(it, "complete"))
        }
        assertNull(f.registry.authorize(origin, "unknown"))
        assertEquals(before, f.capture.verifies)
    }

    @Test fun registrationAndResolutionReadinessStayOutsideRegistryLockAndDetectSourcePolicyAba() {
        val f = fixture(); val executor = Executors.newSingleThreadExecutor()
        try {
            f.preparations.afterPolicyRetain = {
                assertNull(executor.submit<AuthorizedIntegratedCompletion?> {
                    f.registry.authorize(f.members.first().assembly.plan.binding, "unknown")
                }.get(2, TimeUnit.SECONDS))
            }
            f.register(); assertNotNull(f.resolve())
        } finally { f.preparations.afterPolicyRetain = {}; executor.shutdownNow() }
        f.preparations.policyRevision = Digests.sha256("synthetic-revoked-policy")
        assertCode("policy_authority_changed") { f.resolve() }
        f.preparations.policyRevision = f.members.first().assembly.provenance.slots.first().policyIdentity!!.revisionDigest
        f.capture.epoch = Digests.sha256("same-bytes-different-source-authority")
        assertCode("materialization_authority_changed") { f.resolve() }
    }

    @Test fun oversizedOrIncompleteMemberCollectionsAreRejectedBeforeCopyOrCallback() {
        val f = fixture(); val before = f.capture.verifies
        val oversized = object : AbstractCollection<OwnerRouteRecipient>() {
            override val size = 65
            override fun iterator(): Iterator<OwnerRouteRecipient> = error("Oversized collection must never be iterated")
        }
        assertCode("joint_members_invalid") { f.registry.register("complete", f.members.first(), f.members.first(), oversized, f.fence, f.action) }
        assertCode("joint_members_invalid") { f.registry.register("complete", f.members.first(), f.members.first(), listOf(f.members.first()), f.fence, f.action) }
        assertCode("joint_members_invalid") { f.registry.register("complete", f.members.first(), f.members.first(), listOf(f.members.last(), f.members.last()), f.fence, f.action) }
        assertEquals(before, f.capture.verifies); assertNull(f.resolve())
        assertEquals("complete", f.register()) // No failed prefix consumed registration capacity.
    }
}

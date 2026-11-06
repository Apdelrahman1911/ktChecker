package dev.ktchecker.reviewcontroller

import dev.ktchecker.contracts.Digests
import dev.ktchecker.kernel.AdapterCredential
import dev.ktchecker.kernel.AuthorizedPlanRecipient
import dev.ktchecker.kernel.AuthorizedReviewRoute
import dev.ktchecker.kernel.ClientBinding
import dev.ktchecker.kernel.PlanRecipientPreparation
import dev.ktchecker.kernel.RequiredReviewPlan
import dev.ktchecker.kernel.ReviewPlanAuthority
import dev.ktchecker.kernel.ReviewPlanRoutingAuthority
import dev.ktchecker.kernel.SubmitPlanRequest

/**
 * Nonserialized owner handle: a live assembler capability plus THIS recipient's actual adapter
 * credential. Neither a decoded plan nor a client ID can reconstruct it. The kernel, not this
 * registry, authenticates the credential against the independently enrolled exact ClientBinding.
 * This deliberately has no data-class toString/copy exposing a private capability token.
 */
class OwnerRouteRecipient(assembly: AssembledReviewPlan, adapter: AdapterCredential) {
    internal val assembly: AssembledReviewPlan = assembly
    internal val adapter: AdapterCredential = adapter.copy()
}

/**
 * Bounded owner-private IN-MEMORY whole-route authority over live local assembler capabilities.
 * Registration reserves ALL member tuples atomically, not separate local prefixes. Only the
 * selected origin can initiate the route; peers recover their local accepted tuples from the
 * kernel's durable ledger AFTER atomic admission. No DTO/file/restart restores this authority.
 *
 * Captured preparation is admission evidence only. This does not combine recipient receipts,
 * provide a current multi-client source/config fence, or authorize routed final completion.
 * cross_client_finalization_unavailable remains an explicit kernel limitation.
 */
class OwnerReviewRouteRegistry(
    val ownerId: String,
    private val maxPlans: Int = 1024,
    private val maxRouteBytes: Int = 4 * 1024 * 1024,
) : ReviewPlanAuthority, ReviewPlanRoutingAuthority {
    private val lock = Any()
    private val byReference = mutableMapOf<String, Route>()
    private val byPatch = mutableMapOf<Pair<String, String>, String>()
    private val byIdempotency = mutableMapOf<Pair<String, String>, String>()

    init {
        requireIdentifier(ownerId, "route registry owner")
        require(maxPlans > 0 && maxRouteBytes > 0)
    }

    private class Member(val assembly: AssembledReviewPlan, val adapter: AdapterCredential,
                         val preparation: PlanRecipientPreparation) {
        val plan: RequiredReviewPlan get() = assembly.plan
        fun verify(): PlanRecipientPreparation {
            // Actual source verification and per-client captured-policy/execution retention, not
            // an echo of an expected digest. All callbacks run outside registry/kernel locks.
            assembly.verifyForAdmission()
            return preparation
        }
    }

    private class Route(val origin: Member, val members: List<Member>, val declared: RequiredReviewPlan,
                        val registrationDigest: String) {
        fun verify() { members.forEach { it.verify() } }
    }

    /** Exact UTF-8 size of `[declared, [[localPlan, preparation], ...]]`, without joining vectors. */
    private class RouteMetadataBudget(maxBytes: Int, memberCount: Int) {
        // Outer/list/member delimiters and commas consume 4 + 4*n bytes for a nonempty route.
        private var remaining = maxBytes.toLong() - 4L - 4L * memberCount
        init { if (remaining < 0) exceeded() }

        fun include(encoded: String) {
            // UTF-16 length is a lower bound on UTF-8 bytes, including replacement of unpaired
            // surrogates. Reject first when possible, before allocating an encoded byte array.
            if (encoded.length.toLong() > remaining) exceeded()
            val bytes = encoded.toByteArray(Charsets.UTF_8).size.toLong()
            if (bytes > remaining) exceeded()
            remaining -= bytes
        }

        private fun exceeded(): Nothing = problem("route_metadata_limit",
            "The complete route exceeds the owner metadata bound; no prefix was registered.")
    }

    /** The exact origin handle must be a member of the complete owner-supplied closed collection. */
    fun register(origin: OwnerRouteRecipient, recipients: Collection<OwnerRouteRecipient>): SubmitPlanRequest {
        val handles = boundedRecipients(recipients)
        if (handles.count { it === origin } != 1 ||
            handles.map { it.assembly.plan.binding.clientId }.distinct().size != handles.size) {
            problem("route_recipients_invalid", "A whole route requires 2..64 distinct recipients including its exact origin handle.")
        }
        if (handles.sumOf { it.assembly.plan.slots.size.toLong() } > 512) {
            problem("route_slot_limit", "The complete route exceeds 512 required slots; no prefix can be registered.")
        }
        val originPlan = origin.assembly.plan
        handles.forEach { handle ->
            val plan = handle.assembly.plan
            if (plan.binding.ownerId != ownerId) problem("registry_owner_mismatch", "Every route recipient must belong to this owner.")
            if (plan.binding.workspaceId != originPlan.binding.workspaceId) {
                problem("route_namespace_mismatch", "Cross-workspace routing is unavailable; no recipient prefix was registered.")
            }
            if (plan.requiredRecipientClientIds != listOf(plan.binding.clientId)) {
                problem("route_local_plan_invalid", "Every recipient must retain its own whole assembler-created local plan.")
            }
            if (handle.adapter.clientId != plan.binding.clientId) {
                problem("route_adapter_mismatch", "A recipient requires its own actual adapter credential, not another client's capability.")
            }
            if (handle.adapter.token.isBlank() || handle.adapter.token.length > 512) {
                problem("route_adapter_invalid", "Adapter capabilities must be nonempty and bounded; the kernel still authenticates enrollment.")
            }
        }
        if (handles.map { it.assembly.plan.request.planRef }.distinct().size != handles.size) {
            problem("route_reference_conflict", "Each member needs its own exact immutable reference.")
        }
        val ordered = handles.sortedBy { it.assembly.plan.binding.clientId }
        val declared = originPlan.copy(requiredRecipientClientIds = immutable(ordered.map { it.assembly.plan.binding.clientId }))
        // Bound one complete encoded member at a time BEFORE callbacks or combined identity.
        // Count raw canonical JSON, not JSON strings containing repeatedly escaped plan vectors.
        // Private adapter tokens are separately bounded and never serialized into this evidence.
        val metadata = RouteMetadataBudget(maxRouteBytes, ordered.size)
        metadata.include(canonical(declared))
        val members = ordered.map { handle ->
            val encodedPlan = canonical(handle.assembly.plan)
            metadata.include(encodedPlan)
            val preparation = preparation(handle.assembly, Digests.sha256(encodedPlan))
            metadata.include(canonical(preparation))
            Member(handle.assembly, handle.adapter.copy(), preparation)
        }
        val selected = members.single { it.assembly === origin.assembly && it.adapter == origin.adapter }
        // The route identity recipe/domain is unchanged; the byte budget is not an identity.
        val identity = digest(listOf("ktchecker/owner-review-route/1", digest(declared), canonical(members.map {
            listOf(it.assembly.planDigest, it.assembly.registrationDigest, canonical(it.preparation), Digests.sha256(it.adapter.token))
        })))
        val candidate = Route(selected, immutable(members), declared, identity)
        candidate.verify()
        val registered = synchronized(lock) {
            val reference = declared.request.planRef
            val old = byReference[reference]
            if (old != null) {
                if (old.declared != declared || old.members.map { it.plan } != members.map { it.plan }) {
                    problem("route_reference_conflict", "A registered reference cannot be rebound to another complete route.")
                }
                if (old.registrationDigest != candidate.registrationDigest) {
                    problem("route_provenance_conflict", "Equal plan DTOs cannot replace retained source, policy, execution or enrollment authority.")
                }
                old // Never replace the original live callbacks, even for an equivalent retry.
            } else {
                // Complete preflight before the first map write: conflicts/capacity never install
                // just an origin or some of its required independent recipients.
                members.forEach { member ->
                    val plan = member.plan
                    if (plan.request.planRef in byReference) problem("route_reference_conflict", "A required reference already belongs to another registered route.")
                    if ((plan.binding.clientId to plan.request.patchId) in byPatch) problem("route_patch_conflict", "A required client's patch is already registered.")
                    if ((plan.binding.clientId to plan.request.idempotencyKey) in byIdempotency) problem("route_idempotency_conflict", "A required client's idempotency key is already registered.")
                }
                if (members.size > maxPlans - byReference.size) {
                    problem("route_registry_full", "The bounded owner registry cannot retain every required member; no obligation was removed.")
                }
                members.forEach { member ->
                    val plan = member.plan
                    byReference[plan.request.planRef] = candidate
                    byPatch[plan.binding.clientId to plan.request.patchId] = plan.request.planRef
                    byIdempotency[plan.binding.clientId to plan.request.idempotencyKey] = plan.request.planRef
                }
                candidate
            }
        }
        if (registered !== candidate) registered.verify()
        return registered.declared.request
    }

    private fun boundedRecipients(recipients: Collection<OwnerRouteRecipient>): List<OwnerRouteRecipient> {
        val count = recipients.size
        if (count !in 2..64) {
            problem("route_recipients_invalid", "A whole route requires 2..64 distinct recipients including its exact origin handle.")
        }
        // Do not invoke an oversized collection's iterator or materialize an unchecked toList.
        // Also reject underreported/changing sizes before reading an extra element, so a caller
        // cannot bypass this bound with an unending or inconsistent collection iterator.
        val handles = ArrayList<OwnerRouteRecipient>(count)
        val iterator = recipients.iterator()
        while (iterator.hasNext()) {
            if (handles.size == count) {
                problem("route_recipients_invalid", "The supplied closed collection changed size; no prefix was registered.")
            }
            handles.add(iterator.next())
        }
        if (handles.size != count) {
            problem("route_recipients_invalid", "The supplied closed collection changed size; no prefix was registered.")
        }
        return handles
    }

    override fun resolve(binding: ClientBinding, request: SubmitPlanRequest): RequiredReviewPlan? {
        val route = exactOrigin(binding, request) ?: return null
        route.verify()
        return route.declared
    }

    override fun authorize(binding: ClientBinding, request: SubmitPlanRequest, declared: RequiredReviewPlan): AuthorizedReviewRoute? {
        val route = exactOrigin(binding, request) ?: return null
        if (route.declared != declared) return null
        route.verify()
        return AuthorizedReviewRoute(route.declared, route.members.map { member ->
            AuthorizedPlanRecipient(member.plan, member.adapter.copy(), member.preparation.copy(), member::verify)
        })
    }

    private fun exactOrigin(binding: ClientBinding, request: SubmitPlanRequest): Route? {
        if (binding.ownerId != ownerId) return null
        val route = synchronized(lock) { byReference[request.planRef] } ?: return null
        // A peer's exact local tuple is reserved but cannot initiate a local-only prefix. Wrong
        // tuples never run preparation callbacks or touch another client's captured records.
        return route.takeIf { it.declared.binding == binding && it.declared.request == request }
    }

    private fun preparation(assembly: AssembledReviewPlan, encodedPlanDigest: String): PlanRecipientPreparation {
        val plan = assembly.plan
        val evidence = assembly.provenance.slots.associateBy { it.slotId }
        if (evidence.size != plan.slots.size || evidence.keys != plan.slots.map { it.slotId }.toSet() || encodedPlanDigest != assembly.planDigest) {
            problem("route_provenance_invalid", "The complete local vector must match its retained live assembler provenance.")
        }
        val policies = mutableListOf<List<String>>()
        val executions = mutableListOf<List<String>>()
        plan.slots.forEach { slot ->
            val retained = evidence.getValue(slot.slotId)
            val policy = retained.policyIdentity
                ?: problem("route_policy_unavailable", "Every recipient slot requires an actual captured per-client policy; no whole route was registered.")
            if (policy.binding != plan.binding || policy.configurationDigest != slot.input.context.configurationDigest) {
                problem("route_provenance_invalid", "Captured policy belongs to another exact recipient or configuration.")
            }
            requireDigest(policy.revisionDigest, "route captured policy revision")
            policies += listOf(slot.slotId, slot.input.analysisKey(), canonical(policy.binding), policy.configurationDigest, policy.revisionDigest)
            val execution = retained.executionIdentity
            if (slot.state == "pending") {
                if (execution == null || execution.binding != plan.binding || execution.toolchainDigest != slot.input.context.toolchainDigest ||
                    PLAN_EXECUTION_POLICY_KEY !in slot.input.context.policies || execution.inputKey != slot.input.copy(context =
                        slot.input.context.copy(policies = slot.input.context.policies - PLAN_EXECUTION_POLICY_KEY)).analysisKey()) {
                    problem("route_execution_unavailable", "Every executable slot needs its own captured exact-input execution approval.")
                }
                requireDigest(execution.approvalDigest, "route execution approval")
                executions += listOf(slot.slotId, slot.input.analysisKey(), canonical(execution.binding), execution.inputKey,
                    execution.toolchainDigest, execution.approvalDigest)
            } else {
                if (slot.state != "unsupported" || slot.reasons.isEmpty() || execution != null) {
                    problem("route_provenance_invalid", "Unavailable scope must remain an explicit unexecuted administrative obligation.")
                }
                // This marks NO execution authority for an unsupported slot; it does not invent
                // an approval, drop the slot, or turn incomplete scope into executable coverage.
                executions += listOf(slot.slotId, slot.input.analysisKey(), "unsupported_no_execution", canonical(slot.reasons))
            }
        }
        return PlanRecipientPreparation(assembly.planDigest,
            digest(listOf("ktchecker/owner-route-captured-policy/1", canonical(plan.binding), assembly.planDigest, canonical(policies))),
            digest(listOf("ktchecker/owner-route-execution-approval/1", canonical(plan.binding), assembly.planDigest, canonical(executions))))
    }
}

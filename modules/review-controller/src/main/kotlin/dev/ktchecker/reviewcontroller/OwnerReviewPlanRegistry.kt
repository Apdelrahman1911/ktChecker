package dev.ktchecker.reviewcontroller

import dev.ktchecker.kernel.ClientBinding
import dev.ktchecker.kernel.RequiredReviewPlan
import dev.ktchecker.kernel.ReviewPlanAuthority
import dev.ktchecker.kernel.SubmitPlanRequest

/**
 * Owner-private, bounded IN-MEMORY registry. This is NOT durable capability deserialization.
 * Register only an assembler-created capability; DTOs/export JSON/certificate strings cannot
 * enroll themselves. A restart needs fresh live authority for new admission, not decoded files.
 */
class OwnerReviewPlanRegistry(val ownerId: String, private val maxPlans: Int = 1024) : ReviewPlanAuthority {
    private val lock = Any()
    private val byReference = mutableMapOf<String, AssembledReviewPlan>()
    private val byPatch = mutableMapOf<Pair<String, String>, String>()
    private val byIdempotency = mutableMapOf<Pair<String, String>, String>()

    init {
        requireIdentifier(ownerId, "registry owner")
        require(maxPlans > 0)
    }

    fun register(assembly: AssembledReviewPlan): SubmitPlanRequest {
        val plan = assembly.plan
        if (plan.binding.ownerId != ownerId) problem("registry_owner_mismatch", "A plan cannot enroll in another owner's private registry.")
        assembly.verifyForAdmission() // No source/config/preparation callback under the map lock.
        val registered = synchronized(lock) {
            val reference = plan.request.planRef
            val old = byReference[reference]
            if (old != null) {
                if (old.plan.binding != plan.binding || old.plan.request != plan.request || old.planDigest != assembly.planDigest) {
                    problem("plan_reference_conflict", "A registered reference cannot be rebound to another exact client/request/plan.")
                }
                if (old.registrationDigest != assembly.registrationDigest) {
                    problem("plan_provenance_conflict", "An identical DTO cannot replace the original source/policy/execution authority.")
                }
                old // Never overwrite even equivalent callbacks with another client's cache entry.
            } else {
                val patchKey = plan.binding.clientId to plan.request.patchId
                val idempotencyKey = plan.binding.clientId to plan.request.idempotencyKey
                if (patchKey in byPatch) problem("plan_patch_conflict", "This client's patch is already bound to another registered reference.")
                if (idempotencyKey in byIdempotency) problem("plan_idempotency_conflict", "This client's idempotency key already has another registered request.")
                if (byReference.size >= maxPlans) problem("plan_registry_full", "The bounded owner registry is full; no existing obligation was removed.")
                byReference[reference] = assembly
                byPatch[patchKey] = reference
                byIdempotency[idempotencyKey] = reference
                assembly
            }
        }
        if (registered !== assembly) registered.verifyForAdmission()
        return registered.plan.request
    }

    override fun resolve(binding: ClientBinding, request: SubmitPlanRequest): RequiredReviewPlan? {
        if (binding.ownerId != ownerId) return null
        val entry = synchronized(lock) { byReference[request.planRef] } ?: return null
        if (entry.plan.binding != binding || entry.plan.request != request) return null
        entry.verifyForAdmission() // Called by KernelService before staging, outside its DB locks.
        return entry.plan
    }
}

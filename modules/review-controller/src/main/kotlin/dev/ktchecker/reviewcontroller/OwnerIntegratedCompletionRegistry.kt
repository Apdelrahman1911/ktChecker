package dev.ktchecker.reviewcontroller

import dev.ktchecker.contracts.Digests
import dev.ktchecker.kernel.*

/**
 * Host integration SPI, NOT an implementation of a live integration fence. Retain actual source,
 * configuration and merge/provenance authorities for every supplied live assembler capability.
 * One composite fence must span all of them and the actual latest integrated snapshot through
 * action. It must derive every named worktree/lineage projection and historical patch disposition
 * from real integration provenance, not from equal DTOs or digest echoes. No loop of scalar gates.
 *
 * identity binds the installed live authority/revision; it is evidence, not a serializable grant.
 * Callback cancellation/timeout must not interrupt peers, cancel accepted work or revoke shared
 * provider capabilities. Unavailable host integration must return an unavailable check.
 */
interface OwnerIntegratedSnapshotFence {
    val identity: String
    fun <T> withCurrentIntegratedSnapshot(
        assemblies: List<AssembledReviewPlan>,
        integration: AssembledReviewPlan,
        request: IntegratedFenceRequest,
        action: (IntegratedSnapshotCheck) -> T,
    ): T
}

/**
 * Additive owner-private in-memory registry, separate from immutable route R2 admission. No
 * deserialization, client-ID lookup of adapter secrets, automatic restart restoration or fallback
 * final action. Registration retains every live assembly and every independent actual adapter.
 * The kernel derives the durable route closure and newest integration batch; this registry cannot
 * narrow that closure or convert local completed computations into a whole-project completion.
 */
class OwnerIntegratedCompletionRegistry(
    val ownerId: String,
    private val maxReferences: Int = 256,
    private val maxMetadataBytes: Int = 4 * 1024 * 1024,
) : IntegratedCompletionAuthority {
    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()

    init {
        requireIdentifier(ownerId, "joint registry owner")
        require(maxReferences in 1..1024 && maxMetadataBytes in 1..4 * 1024 * 1024)
    }

    private class Entry(
        val reference: String,
        val origin: OwnerRouteRecipient,
        val integration: OwnerRouteRecipient,
        val members: List<OwnerRouteRecipient>,
        val registrationDigest: String,
        val fenceIdentity: String,
        val actionIdentity: String,
        val fence: OwnerIntegratedSnapshotFence,
        val action: IntegratedCompletionAction,
    ) {
        fun verify() {
            if (fence.identity != fenceIdentity || action.identity != actionIdentity) {
                problem("joint_live_authority_changed", "The installed integration/delivery authority changed.")
            }
            members.forEach { it.assembly.verifyForAdmission() }
        }
    }

    /** Same exact owner handles may be used to admit the final route; registration admits no work. */
    fun register(
        completionRef: String,
        origin: OwnerRouteRecipient,
        integration: OwnerRouteRecipient,
        recipients: Collection<OwnerRouteRecipient>,
        fence: OwnerIntegratedSnapshotFence,
        action: IntegratedCompletionAction,
    ): String {
        requireIdentifier(completionRef, "joint completion reference")
        val members = bounded(recipients).sortedBy { it.assembly.plan.binding.clientId }
        if (members.count { it === origin } != 1 || members.count { it === integration } != 1 ||
            members.map { it.assembly.plan.binding.clientId }.distinct().size != members.size) {
            problem("joint_members_invalid", "Exact origin and integration handles must belong to the complete distinct member vector.")
        }
        if (members.sumOf { it.assembly.plan.slots.size.toLong() } > 512) {
            problem("joint_slots_limit", "The complete integrated batch exceeds 512 slots; no member prefix was registered.")
        }
        val namespace = origin.assembly.plan.binding
        val inventory = integration.assembly.provenance.inventory
        members.forEach { member ->
            val plan = member.assembly.plan
            if (plan.binding.ownerId != ownerId || plan.binding.workspaceId != namespace.workspaceId ||
                plan.requiredRecipientClientIds != listOf(plan.binding.clientId)) {
                problem("joint_namespace_mismatch", "Every whole local plan must retain this owner's exact declared workspace scope.")
            }
            if (member.adapter.clientId != plan.binding.clientId || member.adapter.token.length !in 43..128) {
                problem("joint_adapter_invalid", "Every recipient must retain its own bounded actual adapter credential.")
            }
            val catalog = member.assembly.provenance.inventory
            if (catalog.catalogDigest != inventory.catalogDigest || catalog.ruleIds != inventory.ruleIds ||
                plan.slots.any { it.input.requiredRuleIds != inventory.ruleIds }) {
                problem("joint_catalog_mismatch", "Every recipient must review the entire same owner-installed catalog.")
            }
        }
        val fenceIdentity = fence.identity
        val actionIdentity = action.identity
        requireDigest(fenceIdentity, "joint fence authority identity")
        requireDigest(actionIdentity, "joint final action identity")
        val memberIdentities = members.map { member ->
            listOf(canonical(member.assembly.plan.binding), canonical(member.assembly.plan.request), member.assembly.planDigest,
                member.assembly.registrationDigest, Digests.sha256(member.adapter.token))
        }
        val encoded = canonical(listOf("ktchecker/owner-integrated-completion/1", completionRef,
            canonical(namespace), integration.assembly.plan.binding.clientId, inventory.catalogDigest,
            canonical(inventory.ruleIds), canonical(memberIdentities), fenceIdentity, actionIdentity))
        if (encoded.length > maxMetadataBytes || encoded.toByteArray(Charsets.UTF_8).size > maxMetadataBytes) {
            problem("joint_registry_metadata_limit", "The whole owner registration exceeds its bound; no prefix was retained.")
        }
        val candidate = Entry(completionRef, origin, integration, immutable(members), Digests.sha256(encoded),
            fenceIdentity, actionIdentity, fence, action)
        candidate.verify() // All real source/policy/execution callbacks are outside the registry lock.
        val retained = synchronized(lock) {
            val old = entries[completionRef]
            if (old != null) {
                if (old.registrationDigest != candidate.registrationDigest || old.fence !== fence || old.action !== action) {
                    problem("joint_reference_conflict", "A joint reference cannot replace its full live scope, credentials, fence or final action.")
                }
                old // Even equivalent replacement assemblies never replace retained callbacks.
            } else {
                if (entries.size >= maxReferences) problem("joint_registry_full", "The bounded owner registry is full; no obligation was removed.")
                entries[completionRef] = candidate
                candidate
            }
        }
        if (retained !== candidate) retained.verify()
        return completionRef
    }

    override fun authorize(binding: ClientBinding, completionRef: String): AuthorizedIntegratedCompletion? {
        if (binding.ownerId != ownerId) return null
        val entry = synchronized(lock) { entries[completionRef] } ?: return null
        if (entry.origin.assembly.plan.binding != binding) return null
        entry.verify()
        val inventory = entry.integration.assembly.provenance.inventory
        return AuthorizedIntegratedCompletion(completionRef, binding, entry.integration.assembly.plan.binding.clientId,
            entry.registrationDigest, inventory.catalogDigest, inventory.ruleIds, entry.members.map { member ->
                val assembly = member.assembly
                AuthorizedIntegratedMember(assembly.plan.binding, assembly.plan.request, assembly.planDigest, member.adapter,
                    assembly.registrationDigest) {
                    assembly.verifyForAdmission()
                    assembly.registrationDigest
                }
            }, object : IntegratedSnapshotFence {
                override fun <T> withCurrentIntegratedSnapshot(request: IntegratedFenceRequest, action: (IntegratedSnapshotCheck) -> T): T {
                    if (entry.fence.identity != entry.fenceIdentity) problem("joint_live_authority_changed", "The joint fence authority changed.")
                    return entry.fence.withCurrentIntegratedSnapshot(immutable(entry.members.map { it.assembly }),
                        entry.integration.assembly, request, action)
                }
            }, object : IntegratedCompletionAction {
                override val identity = entry.actionIdentity
                override fun perform(permit: IntegratedCompletionPermit): IntegratedActionReceipt? {
                    if (entry.action.identity != entry.actionIdentity) problem("joint_live_authority_changed", "The final effect authority changed.")
                    return entry.action.perform(permit)
                }
            })
    }

    private fun bounded(values: Collection<OwnerRouteRecipient>): List<OwnerRouteRecipient> {
        val count = values.size
        if (count !in 2..64) problem("joint_members_invalid", "The whole joint member collection must contain 2..64 recipients.")
        val result = ArrayList<OwnerRouteRecipient>(count)
        val iterator = values.iterator()
        while (iterator.hasNext()) {
            if (result.size == count) problem("joint_members_invalid", "The closed joint member collection changed size.")
            result += iterator.next()
        }
        if (result.size != count) problem("joint_members_invalid", "The closed joint member collection changed size.")
        return result
    }
}

package dev.ktchecker.architecture

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import dev.ktchecker.projectmodel.ModelNamespace
import dev.ktchecker.projectmodel.VerifiedProjectModel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

const val ARCHITECTURE_PROVIDER_ID = "declared-architecture"
const val ARCHITECTURE_PROVIDER_VERSION = "1"
const val ALLOWED_DEPENDENCY_EDGE = "ktc.architecture.allowed_dependency_edge"
const val DEPENDENCY_CYCLE_POLICY = "ktc.architecture.dependency_cycle_policy"
const val EXPORTED_API_DEPENDENCY_POLICY = "ktc.architecture.exported_api_dependency_policy"
const val SOURCE_SET_OWNERSHIP_POLICY = "ktc.architecture.source_set_ownership_policy"

/** Symbol/package graphs require a separately trusted semantic adapter; spelling is not an edge. */
@Serializable
enum class ArchitectureGraphKind { GRADLE_MODULE_DEPENDENCY, SOURCE_SET_REFINEMENT, RESOLVED_SYMBOL_DEPENDENCY }

@Serializable
/** Traversal of the owner-node quotient graph, after actual identities have been mapped. */
enum class ArchitectureEdgeTraversal { DIRECT, TRANSITIVE }

@Serializable
enum class DefaultEdgePolicy { ALLOW, FORBID }

@Serializable
data class ArchitectureScope(
    val graphKind: ArchitectureGraphKind,
    /** Explicit actual compilation identities, including desired production/test/generated scopes. */
    val compilationIds: List<String>,
    /**
     * Exact actual module/source-set identity -> owner-selected architectural node. No patterns.
     * Edges are projected BEFORE traversal and SCC detection: consolidating actual identities can
     * create owner-node paths/cycles without a connected actual-module/source-set path/cycle.
     */
    val nodeMapping: Map<String, String>,
    val traversal: ArchitectureEdgeTraversal,
    val includeMappedSelfEdges: Boolean,
)

@Serializable
data class PolicyEdge(val from: String, val to: String)

@Serializable
sealed class DeclaredArchitecturePolicy {
    abstract val scope: ArchitectureScope
    abstract val ruleId: String

    @Serializable @SerialName("allowed_edges")
    data class AllowedEdges(
        override val scope: ArchitectureScope,
        val defaultPolicy: DefaultEdgePolicy,
        val allowedEdges: List<PolicyEdge>,
        val forbiddenEdges: List<PolicyEdge>,
    ) : DeclaredArchitecturePolicy() { override val ruleId: String get() = ALLOWED_DEPENDENCY_EDGE }

    @Serializable @SerialName("acyclic_graph")
    data class AcyclicGraph(
        override val scope: ArchitectureScope,
        /** Exact approved SCC node sets. A larger/new SCC does not inherit an old exception. */
        val allowedComponents: List<List<String>> = emptyList(),
    ) : DeclaredArchitecturePolicy() { override val ruleId: String get() = DEPENDENCY_CYCLE_POLICY }

    @Serializable @SerialName("exported_api")
    data class ExportedApi(
        override val scope: ArchitectureScope,
        /** Owner-defined ABI/export boundary, not inferred Kotlin public or api()/implementation(). */
        val boundaryId: String,
        val forbiddenExports: List<PolicyEdge>,
    ) : DeclaredArchitecturePolicy() { override val ruleId: String get() = EXPORTED_API_DEPENDENCY_POLICY }

    @Serializable @SerialName("source_set_ownership")
    data class SourceSetOwnership(
        override val scope: ArchitectureScope,
        val ownershipContractId: String,
        val permittedPlacements: Map<String, List<String>>,
    ) : DeclaredArchitecturePolicy() { override val ruleId: String get() = SOURCE_SET_OWNERSHIP_POLICY }
}

/** Detached owner policy data. Loading this JSON alone does not activate a rule. */
@Serializable
data class ArchitecturePolicyBundle(
    val namespace: ModelNamespace,
    val configurationDigest: String,
    val revision: Long,
    val policies: List<DeclaredArchitecturePolicy>,
    val schemaVersion: Int = 1,
) {
    fun digest(): String = Digests.sha256("ktchecker/declared-architecture-policy/1\u0000" +
        Digests.canonical(ContractJson.encodeToJsonElement(this)))
}

@Serializable
data class ArchitecturePolicyRequest(
    val namespace: ModelNamespace,
    val snapshotId: String,
    val modelCertificateId: String,
    val configurationDigest: String,
    val policyDigest: String,
) {
    fun digest(): String = Digests.sha256("ktchecker/architecture-policy-authority/1\u0000" +
        Digests.canonical(ContractJson.encodeToJsonElement(this)))
}

/** Host capability only: only an authenticated explicit owner policy action may issue a receipt. */
fun interface ArchitecturePolicyAuthority {
    fun authorize(request: ArchitecturePolicyRequest): ArchitecturePolicyPermit?
    companion object { val DENY = ArchitecturePolicyAuthority { null } }
}

/** Nonserialized, single-use receipt. Project/model JSON cannot grant itself policy authority. */
class ArchitecturePolicyPermit(val request: ArchitecturePolicyRequest, val verifyOwnerPolicy: () -> Unit) {
    private val consumed = AtomicBoolean()
    internal fun consume(actual: ArchitecturePolicyRequest) {
        if (request != actual || !consumed.compareAndSet(false, true)) {
            throw ArchitecturePolicyException("architecture_policy_mismatch_or_replay", "Policy receipt is exact and single-use.")
        }
        verifyOwnerPolicy()
    }
}

class ExactArchitecturePolicyAuthority(private val permit: ArchitecturePolicyPermit) : ArchitecturePolicyAuthority {
    override fun authorize(request: ArchitecturePolicyRequest): ArchitecturePolicyPermit? = permit.takeIf { it.request == request }
}

/** Importer-created capability bound to one verified model/namespace/configuration and revision. */
class VerifiedArchitecturePolicies internal constructor(
    val bundle: ArchitecturePolicyBundle,
    val request: ArchitecturePolicyRequest,
    val certificateId: String,
) {
    val byRuleId: Map<String, DeclaredArchitecturePolicy> = immutableMap(bundle.policies.associateBy { it.ruleId })
}

class ArchitecturePolicyImporter(private val authority: ArchitecturePolicyAuthority = ArchitecturePolicyAuthority.DENY) {
    fun import(model: VerifiedProjectModel, policy: ArchitecturePolicyBundle): VerifiedArchitecturePolicies {
        // Bound live input before copying it; freeze below contains no serialization allocation.
        validate(policy)
        val frozen = freeze(policy)
        validate(frozen)
        val request = request(model, frozen)
        if (model.namespace != frozen.namespace || model.identity.configurationDigest != frozen.configurationDigest) {
            throw ArchitecturePolicyException("architecture_policy_binding_mismatch", "Policy belongs to another owner/worktree/lineage or configuration revision.")
        }
        val permit = authority.authorize(request)
            ?: throw ArchitecturePolicyException("architecture_policy_not_authorized", "Only an explicit owner-selected policy may activate architecture analysis.")
        permit.consume(request)
        return VerifiedArchitecturePolicies(frozen, request, Digests.sha256(request.digest() + "\u0000" + frozen.digest()))
    }

    companion object {
        /** Host review helper; the result is a request for authorization, never an authorization. */
        fun request(model: VerifiedProjectModel, policy: ArchitecturePolicyBundle): ArchitecturePolicyRequest = ArchitecturePolicyRequest(
            model.namespace, model.snapshot.snapshotId, model.ingressCertificate, model.identity.configurationDigest, policy.digest())
    }
}

class ArchitecturePolicyException(val code: String, message: String) : IllegalArgumentException(message)

internal fun <T> immutable(values: Collection<T>): List<T> = Collections.unmodifiableList(values.toList())
internal fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(values.toMap())

private fun freeze(policy: ArchitecturePolicyBundle): ArchitecturePolicyBundle {
    fun scope(value: ArchitectureScope) = value.copy(compilationIds = immutable(value.compilationIds), nodeMapping = immutableMap(value.nodeMapping))
    return policy.copy(policies = immutable(policy.policies.map {
        when (it) {
            is DeclaredArchitecturePolicy.AllowedEdges -> it.copy(scope = scope(it.scope), allowedEdges = immutable(it.allowedEdges), forbiddenEdges = immutable(it.forbiddenEdges))
            is DeclaredArchitecturePolicy.AcyclicGraph -> it.copy(scope = scope(it.scope), allowedComponents = immutable(it.allowedComponents.map { component -> immutable(component) }))
            is DeclaredArchitecturePolicy.ExportedApi -> it.copy(scope = scope(it.scope), forbiddenExports = immutable(it.forbiddenExports))
            is DeclaredArchitecturePolicy.SourceSetOwnership -> it.copy(scope = scope(it.scope), permittedPlacements = immutableMap(it.permittedPlacements.mapValues { entry -> immutable(entry.value) }))
        }
    }))
}

private fun validate(policy: ArchitecturePolicyBundle) {
    fun check(valid: Boolean, description: String) { if (!valid) throw ArchitecturePolicyException("architecture_policy_invalid", "Invalid $description.") }
    var encodedBytes = 512L
    fun text(value: String) {
        encodedBytes += quotedJsonUpperBound(value)
        check(encodedBytes <= MAX_ARCHITECTURE_TEXT_BYTES, "bounded encoded owner policy text")
    }
    fun id(value: String) {
        check(value.length in 1..4096 && !value.isBlank() && value.none { it.isISOControl() }, "exact owner policy identity")
        text(value)
    }
    check(policy.schemaVersion == 1 && policy.revision >= 0 && Digests.valid(policy.configurationDigest), "policy schema/revision/configuration")
    listOf(policy.namespace.ownerId, policy.namespace.workspaceId, policy.namespace.worktreeId, policy.namespace.lineageId).forEach(::id)
    check(policy.policies.size in 1..4 && policy.policies.map { it.ruleId }.distinct().size == policy.policies.size, "nonempty unique declared policies")
    policy.policies.forEach { rule ->
        encodedBytes += 1024
        check(encodedBytes <= MAX_ARCHITECTURE_TEXT_BYTES, "bounded encoded owner policy text")
        val scope = rule.scope
        check(scope.compilationIds.size in 1..4096 && scope.compilationIds.distinct().size == scope.compilationIds.size, "explicit unique compilation scope")
        check(scope.nodeMapping.size in 1..4096, "explicit node membership mapping")
        scope.compilationIds.forEach(::id); scope.nodeMapping.forEach { (actual, node) -> encodedBytes += 8; id(actual); id(node) }
        val nodes = scope.nodeMapping.values.toSet()
        fun edges(values: List<PolicyEdge>) {
            check(values.size <= 50_000, "bounded policy edges")
            values.forEach { edge ->
                encodedBytes += 64; text(edge.from); text(edge.to)
                check(edge.from in nodes && edge.to in nodes, "declared edge endpoints")
            }
            check(values.distinct().size == values.size, "unique policy edges")
        }
        when (rule) {
            is DeclaredArchitecturePolicy.AllowedEdges -> {
                edges(rule.allowedEdges); edges(rule.forbiddenEdges)
                val forbidden = rule.forbiddenEdges.toSet()
                check(rule.allowedEdges.none { it in forbidden }, "nonconflicting edge decisions")
            }
            is DeclaredArchitecturePolicy.AcyclicGraph -> {
                check(rule.allowedComponents.size <= 4096, "bounded exact cycle exceptions")
                rule.allowedComponents.forEach { component ->
                    check(component.size in 1..4096, "bounded exact cycle exception membership")
                    component.forEach { text(it) }; encodedBytes += 2
                    check(component.size == component.distinct().size && component.all { it in nodes }, "exact cycle exception membership")
                }
                check(rule.allowedComponents.map { it.toSet() }.distinct().size == rule.allowedComponents.size, "unique cycle exceptions")
            }
            is DeclaredArchitecturePolicy.ExportedApi -> { id(rule.boundaryId); edges(rule.forbiddenExports) }
            is DeclaredArchitecturePolicy.SourceSetOwnership -> {
                id(rule.ownershipContractId)
                check(rule.permittedPlacements.size in 1..4096, "explicit ownership contract")
                rule.permittedPlacements.forEach { (owner, actuals) ->
                    id(owner); encodedBytes += 8
                    check(actuals.size in 1..4096, "bounded ownership placement membership")
                    actuals.forEach(::id)
                    check(actuals.distinct().size == actuals.size, "ownership placement membership")
                }
            }
        }
    }
    check(encodedBytes <= MAX_ARCHITECTURE_TEXT_BYTES, "bounded encoded owner policy text")
}

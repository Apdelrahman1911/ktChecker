package dev.ktchecker.kernel

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.RuleContext
import kotlinx.serialization.Serializable

/** In-memory audit only. Neither this record nor either serialized version grants authority. */
@Serializable internal data class IntegratedCompletionAudit(
    val format: String,
    val cut: IntegratedCompletionCut,
    val registrationDigest: String,
    val actionIdentity: String,
    val request: IntegratedFenceRequest,
    val check: IntegratedSnapshotCheck,
    val state: String = "prepared",
    val receipt: IntegratedActionReceipt? = null,
)

/**
 * Lossless metadata representation only: no new history validator, authority, or wire contract.
 * All old obligations are retained. v2 removes repeated context/rule pairs and stores scope once;
 * the exact expanded request still has the ORIGINAL byte/pair bounds and semantic digest recipe.
 */
internal class IntegratedCompletionAuditCodec(private val limits: IntegratedCompletionLimits) {
    @Serializable private data class MatrixScope(
        val origin: ClientBinding,
        val integrationClientId: String,
        val integrationPlanId: String,
        val integrationSnapshotId: String,
        val finalRoutingId: String,
        val finalRoutingDigest: String,
        val catalogDigest: String,
        val members: List<IntegratedClientScope>,
        val contexts: List<RuleContext>,
        val ruleIds: List<String>,
        val coverageMode: String = CARTESIAN,
        val scopeKind: String = DECLARED_SCOPE,
    ) {
        fun emptyScope() = IntegratedCompletionScope(origin, integrationClientId, integrationPlanId,
            integrationSnapshotId, finalRoutingId, finalRoutingDigest, catalogDigest, members,
            emptyList(), scopeKind)
    }

    @Serializable private data class CutIdentity(
        val cutId: String,
        val completionRef: String,
        val scopeDigest: String,
        val ready: Boolean,
        val reasons: List<String>,
        val contractVersion: String,
    )

    @Serializable private data class RequestFacts(
        val scopeDigest: String,
        val frontiers: List<IntegratedClientFrontier>,
        val historicalObligations: List<IntegratedPatchObligation>,
        val routes: List<IntegratedRouteIdentity>,
    ) {
        fun request(scope: IntegratedCompletionScope) = IntegratedFenceRequest(scope, scopeDigest,
            frontiers, historicalObligations, routes)
    }

    @Serializable private data class CompactAudit(
        val format: String,
        val scope: MatrixScope,
        val cut: CutIdentity,
        val registrationDigest: String,
        val actionIdentity: String,
        val request: RequestFacts,
        val check: IntegratedSnapshotCheck,
        val state: String = "prepared",
        val receipt: IntegratedActionReceipt? = null,
    )

    fun encode(value: IntegratedCompletionAudit): String {
        if (value.format == LEGACY_FORMAT) {
            // Legacy encoding is retained only for exact readback/tests. The barrier NEVER
            // creates, transitions, restores authority from, or migrates a v1 audit.
            validateIdentity(value, LEGACY_ID_PREFIX)
            return canonical(value).also(::bounded)
        }
        if (value.format != CURRENT_FORMAT) invalid()
        attestationCounts(value.check)
        val compact = compact(value)
        validateIdentity(value, CURRENT_ID_PREFIX)
        val encoded = canonical(compact).also(::bounded)
        if (value.state == "prepared") {
            // A valid receipt has fixed-length digest fields. A post-effect fence failure may
            // retain that receipt while changing complete to the longest state, outcome_unknown.
            // Reserve that EXACT legal encoding now, BEFORE any member can be claimed.
            val terminal = compact.copy(state = "outcome_unknown", receipt = IntegratedActionReceipt(
                value.cut.cutId, value.cut.scopeDigest, MAX_RECEIPT_DIGEST))
            bounded(canonical(terminal))
        }
        return encoded
    }

    fun decode(expectedCutId: String, encoded: String): IntegratedCompletionAudit {
        // The caller selects the already-validated metadata key, never an untrusted format
        // field. Dispatch directly to its one exact serializer; no generic tree or fallback.
        val expectedFormat = when {
            validId(expectedCutId, LEGACY_ID_PREFIX) -> LEGACY_FORMAT
            validId(expectedCutId, CURRENT_ID_PREFIX) -> CURRENT_FORMAT
            else -> fail("joint_cut_not_found", "Joint cut is not available to this client")
        }
        bounded(encoded)
        return try {
            when (expectedFormat) {
                LEGACY_FORMAT -> ContractJson.decodeFromString<IntegratedCompletionAudit>(encoded).also {
                    if (it.format != expectedFormat || it.cut.cutId != expectedCutId) invalid()
                    if (canonical(it) != encoded) invalid()
                    validateIdentity(it, LEGACY_ID_PREFIX)
                }
                CURRENT_FORMAT -> {
                    val compact = ContractJson.decodeFromString<CompactAudit>(encoded)
                    if (compact.format != expectedFormat || compact.cut.cutId != expectedCutId) invalid()
                    // Small omitted-default objects can become large during canonical encoding.
                    // Apply the original callback/cardinality caps and exact expanded-request
                    // byte preflight BEFORE dictionary sorting or whole-record reencoding.
                    attestationCounts(compact.check)
                    val pairs = preflight(compact.scope, compact.request)
                    // Duplicate keys, omitted defaults, unknown fields, noncanonical ordering,
                    // and whitespace variants cannot alias a retained compact record. Do this
                    // final exact-byte check BEFORE allocating any Cartesian coverage vector.
                    if (canonical(compact) != encoded) invalid()
                    val request = expand(compact.scope, compact.request, pairs)
                    val cut = compact.cut.let { IntegratedCompletionCut(it.cutId, it.completionRef,
                        request.scope, it.scopeDigest, it.ready, it.reasons, it.contractVersion) }
                    IntegratedCompletionAudit(compact.format, cut, compact.registrationDigest,
                        compact.actionIdentity, request, compact.check, compact.state, compact.receipt).also {
                        // Also proves every compact field survived reconstruction. No ignored
                        // field, subset normalization, alternate mode, or partial decode fallback.
                        if (encode(it) != encoded) invalid()
                    }
                }
                else -> invalid()
            }
        } catch (failure: KernelException) {
            throw failure
        } catch (_: Exception) {
            invalid()
        }
    }

    private fun compact(value: IntegratedCompletionAudit): CompactAudit {
        if (value.cut.scope != value.request.scope) invalid()
        val scope = value.cut.scope
        val coverage = scope.requiredCoverage
        if (coverage.size !in 1..limits.maxCoveragePairs) historyLimit()
        val contexts = coverage.map { it.context }.distinct().sortedBy { canonical(it) }
        val rules = coverage.map { it.ruleId }.distinct().sorted()
        if (contexts.size.toLong() * rules.size != coverage.size.toLong()) invalid()
        var index = 0
        for (context in contexts) for (rule in rules) {
            val actual = coverage[index++]
            if (actual.context != context || actual.ruleId != rule) invalid()
        }
        val matrix = MatrixScope(scope.origin, scope.integrationClientId, scope.integrationPlanId,
            scope.integrationSnapshotId, scope.finalRoutingId, scope.finalRoutingDigest,
            scope.catalogDigest, scope.members, contexts, rules, scopeKind = scope.scopeKind)
        val request = value.request.let { RequestFacts(it.scopeDigest, it.frontiers, it.historicalObligations, it.routes) }
        preflight(matrix, request)
        return CompactAudit(value.format, matrix, value.cut.let { CutIdentity(it.cutId,
            it.completionRef, it.scopeDigest, it.ready, it.reasons, it.contractVersion) },
            value.registrationDigest, value.actionIdentity, request, value.check, value.state, value.receipt)
    }

    private fun expand(matrix: MatrixScope, facts: RequestFacts, pairs: Int): IntegratedFenceRequest {
        val coverage = ArrayList<IntegratedCoverageRequirement>(pairs)
        for (context in matrix.contexts) for (rule in matrix.ruleIds) {
            coverage += IntegratedCoverageRequirement(context, rule)
        }
        return facts.request(matrix.emptyScope().copy(requiredCoverage = coverage))
    }

    /** Exact restored-request byte count before decoder Cartesian allocation or scope hashing. */
    private fun preflight(matrix: MatrixScope, facts: RequestFacts): Int {
        if (matrix.coverageMode != CARTESIAN || matrix.scopeKind != DECLARED_SCOPE ||
            matrix.contexts.isEmpty() || matrix.ruleIds.isEmpty()) invalid()
        if (matrix.contexts.size > limits.maxCoveragePairs || matrix.ruleIds.size > 50_000) historyLimit()
        val pairs = matrix.contexts.size.toLong() * matrix.ruleIds.size
        if (pairs > limits.maxCoveragePairs) historyLimit()
        // All cheap collection caps precede ANY default-expanding canonical serialization.
        if (matrix.members.size !in 2..limits.maxMembers || facts.frontiers.size > limits.maxMembers ||
            facts.historicalObligations.size > limits.maxReviews + limits.maxParents || facts.routes.size > limits.maxRoutes ||
            facts.frontiers.sumOf { it.requiredEventIds.size.toLong() } > limits.maxEvents) historyLimit()

        // Each pair is {"context":<canonical-context>,"ruleId":<canonical-rule-string>}.
        // Context/rule JSON includes its exact escaping and UTF-8 bytes, including non-ASCII.
        val contextBytes = matrix.contexts.sumOf { bytes(canonical(it)) }
        val ruleBytes = matrix.ruleIds.sumOf { bytes(canonical(it)) }
        val vectorBytes = contextBytes * matrix.ruleIds.size + ruleBytes * matrix.contexts.size +
            PAIR_OVERHEAD.toLong() * pairs + (pairs - 1) + 2
        val baseBytes = bytes(canonical(facts.request(matrix.emptyScope())))
        // The base request contains this scope once and exactly [] for requiredCoverage.
        if (baseBytes - 2 + vectorBytes > limits.maxAuditBytes) auditLimit()
        // Compare, never normalize. Do not repeatedly sort/encode an over-byte dictionary.
        if (matrix.contexts != matrix.contexts.distinct().sortedBy { canonical(it) } ||
            matrix.ruleIds != matrix.ruleIds.distinct().sorted()) invalid()
        return pairs.toInt()
    }

    private fun attestationCounts(check: IntegratedSnapshotCheck) {
        // Identical ceilings to the live composite-fence callback. Persisted v2 decoding
        // cannot bypass them with short omitted-default member/check representations.
        if (check.members.size > limits.maxMembers || check.patchDispositions.size > limits.maxReviews + limits.maxParents ||
            check.reasons.size > 64 || check.members.any { it.check.contextIdentities.size > 512 || it.check.reasons.size > 64 }) {
            fail("joint_attestation_limit", "The complete joint attestation exceeds its bound")
        }
    }

    private fun validateIdentity(value: IntegratedCompletionAudit, idPrefix: String) {
        if (!validId(value.cut.cutId, idPrefix) || value.cut.scope != value.request.scope ||
            value.cut.scopeDigest != value.request.scopeDigest ||
            value.cut.scopeDigest != integratedScopeDigest(value.cut.scope) || value.state !in STATES) invalid()
        if (value.format == CURRENT_FORMAT &&
            ((value.state in setOf("prepared", "action_claimed") && value.receipt != null) ||
                (value.state == "complete" && value.receipt == null))) invalid()
        // A malformed persisted receipt must not count as verified delivery on readback.
        value.receipt?.let {
            if (it.cutId != value.cut.cutId || it.scopeDigest != value.cut.scopeDigest || !Digests.valid(it.receiptDigest)) invalid()
        }
    }

    private fun bytes(value: String): Long = value.toByteArray(Charsets.UTF_8).size.toLong()
    private fun bounded(value: String) {
        if (value.length > limits.maxAuditBytes || bytes(value) > limits.maxAuditBytes) auditLimit()
    }
    private fun invalid(): Nothing = fail("joint_audit_invalid", "The exact joint audit identity or lossless coverage matrix is unavailable")
    private fun auditLimit(): Nothing = fail("joint_audit_limit", "The complete joint audit, expanded request or terminal reserve exceeds its unchanged bound")
    private fun historyLimit(): Nothing = fail("joint_history_limit", "The complete joint audit scope exceeds its unchanged bounds; no obligation was removed")

    companion object {
        const val LEGACY_FORMAT = "ktchecker/joint-completion-cut/1"
        const val CURRENT_FORMAT = "ktchecker/joint-completion-cut/2"
        const val LEGACY_ID_PREFIX = "joint-v1:"
        const val CURRENT_ID_PREFIX = "joint-v2:"
        const val LEGACY_KEY_PREFIX = "ktchecker/joint-completion/1/cut/"
        const val CURRENT_KEY_PREFIX = "ktchecker/joint-completion/2/cut/"
        private const val CARTESIAN = "all_declared_contexts_x_full_catalog"
        private const val DECLARED_SCOPE = "declared_routed_lineages_and_catalog"
        private const val PAIR_OVERHEAD = 22 // {"context": + ,"ruleId": + }
        private val MAX_RECEIPT_DIGEST = "sha256:" + "0".repeat(64)
        private val STATES = setOf("prepared", "action_claimed", "outcome_unknown", "complete")
        private val ID_SUFFIX = Regex("[0-9a-f-]{36}")

        private fun validId(cutId: String, prefix: String): Boolean =
            cutId.startsWith(prefix) && cutId.removePrefix(prefix).matches(ID_SUFFIX)

        fun key(cutId: String): String = when {
            validId(cutId, LEGACY_ID_PREFIX) -> LEGACY_KEY_PREFIX + cutId
            validId(cutId, CURRENT_ID_PREFIX) -> CURRENT_KEY_PREFIX + cutId
            else -> fail("joint_cut_not_found", "Joint cut is not available to this client")
        }
    }
}

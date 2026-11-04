package dev.ktchecker.config

import dev.ktchecker.contracts.AnalysisInput
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.Finding
import dev.ktchecker.contracts.ProviderResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.util.Collections

@Serializable
data class PolicyRule(
    val canonicalId: String,
    val aliases: Set<String> = emptySet(),
    val suppressionAllowed: Boolean = true,
    val disablingAllowed: Boolean = true,
    val failureProtected: Boolean = false,
    /** Null means unavailable, not a hash of the rule name masquerading as implementation proof. */
    val implementationDigest: String? = null,
)

/** Trusted catalog only; aliases are exact, never guessed by names or wildcard. */
class RuleAliases(rules: Collection<PolicyRule>) {
    private val byId: Map<String, PolicyRule>
    private val canonical: Map<String, PolicyRule>
    val digest: String

    init {
        val ids = mutableMapOf<String, PolicyRule>()
        val records = mutableMapOf<String, PolicyRule>()
        rules.forEach { supplied ->
            val rule = supplied.copy(aliases = Collections.unmodifiableSet(supplied.aliases.toSet()))
            validateIdentifier(rule.canonicalId, "canonical rule")
            if (rule.implementationDigest != null && !Digests.valid(rule.implementationDigest)) throw ConfigValidationException("Rule implementation identity must be SHA-256.")
            if (records.put(rule.canonicalId, rule) != null) throw ConfigValidationException("Duplicate canonical rule: ${rule.canonicalId}")
            (rule.aliases + rule.canonicalId).forEach { alias ->
                validateIdentifier(alias, "rule alias")
                val previous = ids.put(alias, rule)
                if (previous != null && previous.canonicalId != rule.canonicalId) {
                    throw ConfigValidationException("Rule alias has more than one canonical identity: $alias")
                }
            }
        }
        byId = ids.toMap()
        canonical = records.toMap()
        val descriptor = records.values.sortedBy { it.canonicalId }.map { it.copy(aliases = it.aliases.sorted().toSet()) }
        digest = Digests.sha256(Digests.canonical(ContractJson.encodeToJsonElement(descriptor)))
    }

    fun canonicalId(id: String): String? = byId[id]?.canonicalId
    fun rule(id: String): PolicyRule? = byId[id]
    fun requireRule(id: String): PolicyRule = rule(id) ?: throw ConfigValidationException("Unregistered rule or alias: $id")
    fun allRules(): List<PolicyRule> = immutableList(canonical.values.sortedBy { it.canonicalId })

    companion object {
        val NONE = RuleAliases(emptyList())
        fun identities(ids: Collection<String>, protectedIds: Set<String> = emptySet()): RuleAliases = RuleAliases(
            ids.map { PolicyRule(it, failureProtected = it in protectedIds) },
        )
    }
}

/** No proof is the safe default. Compiler/provider-owned strings alone are insufficient. */
fun interface FindingIdentityVerifier {
    fun verify(binding: ConfigBinding, input: AnalysisInput, finding: Finding, canonicalRuleId: String): VerifiedFindingIdentity?

    companion object { val NONE = FindingIdentityVerifier { _, _, _, _ -> null } }
}

/** Resolution needs fresh comparable coverage/absence proof; disposition strings do not grant it. */
fun interface FindingLifecycleVerifier {
    fun verify(binding: ConfigBinding, input: AnalysisInput, finding: Finding, canonicalRuleId: String): VerifiedLifecycleDisposition?

    companion object { val NONE = FindingLifecycleVerifier { _, _, _, _ -> null } }
}

internal class PolicyMatcher(
    private val aliases: RuleAliases,
    private val verifier: FindingIdentityVerifier,
    private val lifecycleVerifier: FindingLifecycleVerifier,
) {
    fun findingSelector(snapshot: ConfigSnapshot, input: AnalysisInput, finding: Finding): PolicySelector.ExactFinding {
        requireInput(snapshot, input, finding)
        val rule = aliases.requireRule(finding.ruleId)
        if (rule.implementationDigest == null) throw ConfigValidationException("Narrow policy requires an actual rule/provider implementation identity.")
        val anchor = anchor(snapshot.binding, input, finding, rule)
        return PolicySelector.ExactFinding(rule.canonicalId, fingerprint(anchor), anchor)
    }

    fun explain(snapshot: ConfigSnapshot, input: AnalysisInput, finding: Finding): SuppressionExplanation {
        val rule = aliases.rule(finding.ruleId)
        fun decision(disposition: PolicyDisposition, message: String, fingerprint: String? = null,
                     entry: String? = null, layer: ConfigLayer? = null, attempts: List<MatchAttempt> = emptyList()) =
            SuppressionExplanation(finding, rule?.canonicalId, fingerprint, disposition, message, entry, layer, immutableList(attempts))

        if (snapshot.registryDigest != aliases.digest || !inputBindingMatches(snapshot, input, finding)) {
            return decision(PolicyDisposition.OPEN, "Identity/registry mismatch: no policy matching was authorized for this finding.")
        }
        if (rule == null) return decision(PolicyDisposition.OPEN, "Unknown rule identity; aliases are not guessed.")
        val sourceCurrent = sourceMatches(input, finding)
        val anchor = anchor(snapshot.binding, input, finding, rule, sourceCurrent)
        val fingerprint = fingerprint(anchor)
        val lifecycle = lifecycleVerifier.verify(snapshot.binding, input, finding, rule.canonicalId)?.takeIf {
            it.certificateId.isNotBlank() && it.snapshotId == input.snapshot.snapshotId && it.policyDigest == snapshot.policyDigest &&
                it.canonicalRuleId == rule.canonicalId && it.providerId == finding.providerId && it.fingerprint == fingerprint &&
                it.disposition in setOf(PolicyDisposition.FIXED, PolicyDisposition.UNAVAILABLE, PolicyDisposition.BASELINED)
        }
        // Fixed is accepted only from a fresh trusted absence certificate, never a provider label.
        if (lifecycle?.disposition == PolicyDisposition.FIXED) {
            return decision(PolicyDisposition.FIXED, "Trusted lifecycle certificate proves resolution; configuration did not infer a fix.", fingerprint)
        }
        if (isProtectedFailure(finding, rule)) {
            return decision(PolicyDisposition.OPEN, "Compiler/build/execution failures cannot be waived by finding policy.", fingerprint)
        }
        if (lifecycle != null) {
            return decision(lifecycle.disposition, "Trusted lifecycle evidence/accounting is retained, not inferred from a provider label.", fingerprint)
        }
        if (!sourceCurrent) return decision(PolicyDisposition.OPEN, "Finding source bytes/range are not bound to this immutable snapshot.", fingerprint)
        if (snapshot.ruleSettings[rule.canonicalId]?.enabled == false && rule.disablingAllowed && !rule.failureProtected) {
            return decision(PolicyDisposition.DISABLED, "Rule explicitly disabled by owner configuration; this is not fixed.", fingerprint)
        }

        val attempts = mutableListOf<MatchAttempt>()
        val matches = mutableListOf<ResolvedSuppression>()
        snapshot.suppressions.forEach { candidate ->
            val state = when {
                candidate.activity != "active" -> candidate.activity
                !rule.suppressionAllowed || rule.failureProtected -> "suppression_not_allowed"
                candidate.record.spec.selector.ruleId != rule.canonicalId -> "rule_mismatch"
                isNarrow(candidate.record.spec.selector, candidate.record.spec.scope) &&
                    (rule.implementationDigest == null || (candidate.record.spec.selector is PolicySelector.Rule &&
                        candidate.record.spec.selector.ruleIdentityDigest == null)) -> "rule_implementation_identity_unavailable"
                else -> match(candidate.record.spec.selector, candidate.record.spec.scope, anchor)
            }
            attempts += MatchAttempt(candidate.record.spec.id, candidate.layer, state)
            if (state == "matched") matches += candidate
        }
        val selected = matches.sortedWith(compareByDescending<ResolvedSuppression> { it.layer == ConfigLayer.LOCAL }
            .thenByDescending { specificity(it.record.spec.selector, it.record.spec.scope) }
            .thenBy { it.record.spec.id }).firstOrNull()
        if (selected != null) {
            val disposition = if (selected.record.spec.reason == SuppressionReason.ACCEPTED_RISK) PolicyDisposition.ACCEPTED else PolicyDisposition.SUPPRESSED
            return decision(disposition, selected.record.spec.justification, fingerprint, selected.record.spec.id, selected.layer, attempts)
        }
        val baseline = snapshot.baselines.filter { it.active && it.record.spec.selector.ruleId == rule.canonicalId &&
            rule.implementationDigest != null && rule.suppressionAllowed && !rule.failureProtected }
            .sortedWith(compareByDescending<ResolvedBaseline> { it.layer == ConfigLayer.LOCAL }.thenBy { it.record.spec.id })
            .firstOrNull { matchSelector(it.record.spec.selector, anchor) == "matched" }
        if (baseline != null) {
            return decision(PolicyDisposition.BASELINED, baseline.record.spec.justification, fingerprint, baseline.record.spec.id, baseline.layer, attempts)
        }
        return decision(PolicyDisposition.OPEN, "No active matching owner policy; old policy labels are not evidence of resolution.", fingerprint, attempts = attempts)
    }

    fun apply(snapshot: ConfigSnapshot, input: AnalysisInput, result: ProviderResult): ProviderResult = result.copy(
        findings = result.findings.map { explain(snapshot, input, it).classifiedFinding() },
        // Deliberately do not change status, reasons, raw diagnostics or coverage.
    )

    private fun anchor(binding: ConfigBinding, input: AnalysisInput, finding: Finding, rule: PolicyRule, verifyIdentity: Boolean = true): FindingAnchor {
        val location = finding.location
        val verified = (if (verifyIdentity) verifier.verify(binding, input, finding, rule.canonicalId) else null)?.takeIf { identity ->
            runCatching { validateVerifiedIdentity(identity) }.isSuccess &&
                identity.snapshotId == input.snapshot.snapshotId && identity.ownerId == binding.ownerId &&
                identity.workspaceId == binding.workspaceId && identity.worktreeId == binding.worktreeId &&
                identity.lineageId == binding.lineageId && identity.canonicalRuleId == rule.canonicalId &&
                identity.providerId == finding.providerId &&
                (finding.semanticDigest == null || finding.semanticDigest == identity.materialDigest)
        }
        val evidence = finding.evidence.map { ContractJson.encodeToJsonElement(it) }.sortedBy { Digests.canonical(it) }
        return FindingAnchor(
            binding, rule.canonicalId, rule.implementationDigest ?: Digests.sha256("unavailable-implementation:${rule.canonicalId}"), finding.providerId, finding.context,
            finding.severity, finding.kind, finding.certainty,
            location.path, location.fileDigest, location.line, location.column, location.endLine, location.endColumn,
            Digests.sha256(Digests.canonical(JsonArray(evidence))), verified,
        )
    }

    private fun requireInput(snapshot: ConfigSnapshot, input: AnalysisInput, finding: Finding) {
        if (snapshot.registryDigest != aliases.digest || !inputBindingMatches(snapshot, input, finding) || !sourceMatches(input, finding)) {
            throw ConfigValidationException("Finding is not bound to this immutable snapshot/configuration namespace.")
        }
    }

    private fun inputBindingMatches(snapshot: ConfigSnapshot, input: AnalysisInput, finding: Finding): Boolean {
        val manifest = input.snapshot
        val binding = snapshot.binding
        return manifest.ownerId == binding.ownerId && manifest.workspaceId == binding.workspaceId &&
            manifest.worktreeId == binding.worktreeId && Digests.valid(manifest.snapshotId) &&
            input.context.configurationDigest == snapshot.policyDigest && finding.context == input.context.scope
    }

    private fun sourceMatches(input: AnalysisInput, finding: Finding): Boolean {
        val location = finding.location
        return runCatching { validatePath(location.path); validateRange(location.line, location.column, location.endLine, location.endColumn) }.isSuccess &&
            Digests.valid(location.fileDigest) &&
            input.snapshot.files.count { it.path == location.path } == 1 &&
            input.snapshot.files.any { it.path == location.path && it.digest == location.fileDigest }
    }

    private fun match(selector: PolicySelector, scope: SuppressionScope, current: FindingAnchor): String {
        val selected = matchSelector(selector, current)
        if (selected != "matched") return selected
        return when (scope) {
            SuppressionScope.Project -> "matched"
            is SuppressionScope.Target -> if (scope.target == current.context.target) "matched" else "scope_mismatch"
            is SuppressionScope.Module -> if (scope.module == current.context.module) "matched" else "scope_mismatch"
            is SuppressionScope.SourceSet -> if (scope.module == current.context.module && scope.sourceSet == current.context.sourceSet &&
                (scope.target == null || scope.target == current.context.target)) "matched" else "scope_mismatch"
            is SuppressionScope.File -> narrow(scope.path == current.path && scope.fileDigest == current.fileDigest, scope.verifiedIdentity, current)
            is SuppressionScope.Symbol -> verifiedMatch(scope.identity, current)
            is SuppressionScope.Range -> narrow(
                scope.path == current.path && scope.fileDigest == current.fileDigest &&
                    comparePosition(scope.line, scope.column, current.line, current.column) <= 0 &&
                    (comparePosition(current.line, current.column, scope.endLine, scope.endColumn) < 0 ||
                        (comparePosition(scope.line, scope.column, scope.endLine, scope.endColumn) == 0 &&
                            comparePosition(current.line, current.column, current.endLine, current.endColumn) == 0)) &&
                    comparePosition(scope.endLine, scope.endColumn, current.endLine, current.endColumn) >= 0,
                scope.verifiedIdentity, current,
            )
        }
    }

    private fun narrow(exact: Boolean, expected: VerifiedFindingIdentity?, current: FindingAnchor): String =
        if (expected != null) verifiedMatch(expected, current) else if (exact) "matched" else "source_changed"

    private fun matchSelector(selector: PolicySelector, current: FindingAnchor): String = when (selector) {
        is PolicySelector.Rule -> when {
            selector.ruleId != current.canonicalRuleId -> "rule_mismatch"
            selector.ruleIdentityDigest != null && selector.ruleIdentityDigest != current.ruleIdentityDigest -> "rule_implementation_changed"
            else -> "matched"
        }
        is PolicySelector.ExactFinding -> when {
            selector.ruleId != current.canonicalRuleId -> "rule_mismatch"
            selector.anchor.binding != current.binding || selector.anchor.context != current.context ||
                selector.anchor.ruleIdentityDigest != current.ruleIdentityDigest || selector.anchor.providerId != current.providerId ||
                selector.anchor.severity != current.severity || selector.anchor.findingKind != current.findingKind ||
                selector.anchor.certainty != current.certainty -> "identity_changed"
            selector.anchor.verifiedIdentity != null -> verifiedMatch(selector.anchor.verifiedIdentity, current)
            // Do not accidentally promote an old exact anchor when a verifier is later enabled.
            selector.fingerprint == fingerprint(current.copy(verifiedIdentity = null)) -> "matched"
            else -> "fingerprint_changed"
        }
    }

    private fun verifiedMatch(expected: VerifiedFindingIdentity, current: FindingAnchor): String {
        val actual = current.verifiedIdentity ?: return "identity_unverified"
        if (stableIdentity(expected) != stableIdentity(actual)) return "symbol_or_material_evidence_changed"
        return "matched"
    }

    private fun specificity(selector: PolicySelector, scope: SuppressionScope): Int =
        (if (selector is PolicySelector.ExactFinding) 100 else 0) + when (scope) {
            is SuppressionScope.Range -> 7
            is SuppressionScope.Symbol -> 6
            is SuppressionScope.File -> 5
            is SuppressionScope.SourceSet -> 4
            is SuppressionScope.Module -> 3
            is SuppressionScope.Target -> 2
            SuppressionScope.Project -> 1
        }
}

internal fun isNarrow(selector: PolicySelector, scope: SuppressionScope): Boolean = selector is PolicySelector.ExactFinding ||
    scope is SuppressionScope.File || scope is SuppressionScope.Symbol || scope is SuppressionScope.Range

/** Versioned, canonical identity; never use a provider-supplied display fingerprint. */
fun fingerprint(anchor: FindingAnchor): String = Digests.sha256(Digests.canonical(buildJsonObject {
    put("schema", "ktchecker/finding-policy-1")
    put("binding", ContractJson.encodeToJsonElement(anchor.binding))
    put("rule", anchor.canonicalRuleId)
    put("ruleIdentity", anchor.ruleIdentityDigest)
    put("provider", anchor.providerId)
    put("context", ContractJson.encodeToJsonElement(anchor.context))
    put("severity", anchor.severity)
    put("kind", anchor.findingKind)
    put("certainty", anchor.certainty)
    val verified = anchor.verifiedIdentity
    if (verified != null) {
        put("verified", stableIdentity(verified))
    } else {
        put("path", anchor.path)
        put("fileDigest", anchor.fileDigest)
        put("line", anchor.line); put("column", anchor.column)
        put("endLine", anchor.endLine); put("endColumn", anchor.endColumn)
        put("evidenceDigest", anchor.evidenceDigest)
    }
}))

private fun stableIdentity(identity: VerifiedFindingIdentity) = buildJsonObject {
    put("owner", identity.ownerId); put("workspace", identity.workspaceId)
    put("worktree", identity.worktreeId); put("lineage", identity.lineageId)
    put("rule", identity.canonicalRuleId); put("provider", identity.providerId)
    put("providerArtifact", identity.providerArtifactDigest)
    put("symbol", identity.symbolKey); put("material", identity.materialDigest)
    put("evidenceKey", identity.evidenceKey); put("evidenceDigest", identity.evidenceDigest)
}

internal fun isProtectedFailure(finding: Finding, rule: PolicyRule?): Boolean {
    if (rule?.failureProtected == true) return true
    val kinds = finding.evidence.map { it.kind.lowercase() }
    if (kinds.any { it in setOf("build_failure", "execution_failure", "compiler_failure", "test_failure", "worker_failure") }) return true
    if (finding.verification.any { it.state.lowercase() in setOf("failed", "error", "crashed") }) return true
    return finding.severity.lowercase() in setOf("error", "fatal") && (
        kinds.any { it in setOf("compiler_diagnostic", "build_result", "test_result", "execution_result") } ||
            finding.providerId.lowercase().let { "compiler" in it || "native" in it || "build" in it || "runtime" in it }
        )
}

internal fun validateIdentifier(value: String, description: String) {
    if (value.length !in 1..256 || value.any { it.isISOControl() } || value.isBlank()) {
        throw ConfigValidationException("Invalid $description identifier.")
    }
}

internal fun validatePath(path: String) {
    if (path.length !in 1..4096 || path.startsWith('/') || path.contains('\\') || path.any { it.isISOControl() } ||
        path.split('/').any { it.isEmpty() || it == "." || it == ".." } || Regex("^[A-Za-z]:").containsMatchIn(path)) {
        throw ConfigValidationException("A normalized, relative logical source path is required.")
    }
}

internal fun comparePosition(line: Int, column: Int, otherLine: Int, otherColumn: Int): Int =
    if (line != otherLine) line.compareTo(otherLine) else column.compareTo(otherColumn)

internal fun validateRange(line: Int, column: Int, endLine: Int, endColumn: Int) {
    if (line < 1 || endLine < 1 || column < 0 || endColumn < 0 || comparePosition(line, column, endLine, endColumn) > 0) {
        throw ConfigValidationException("Invalid end-exclusive source range.")
    }
}

internal fun validateVerifiedIdentity(identity: VerifiedFindingIdentity) {
    listOf(identity.certificateId, identity.ownerId, identity.workspaceId, identity.worktreeId, identity.lineageId,
        identity.canonicalRuleId, identity.providerId, identity.symbolKey, identity.evidenceKey).forEach { validateIdentifier(it, "verified identity") }
    listOf(identity.snapshotId, identity.providerArtifactDigest, identity.materialDigest, identity.evidenceDigest).forEach {
        if (!Digests.valid(it)) throw ConfigValidationException("Verified identity is missing a content digest.")
    }
}

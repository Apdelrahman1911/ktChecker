package dev.ktchecker.providers.lint

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.providers.runtime.ExecutionProblem
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

@Serializable data class LintRuleVersion(
    val lintVersion: String,
    val coordinate: String,
    val binaryDigest: String,
    val scope: String,
    val severity: String,
    val enabledByDefault: Boolean,
)

@Serializable data class LintRule(
    val ruleId: String,
    val upstreamId: String,
    val registry: String,
    val title: String,
    val severity: String,
    val kind: String,
    val certainty: String,
    val depth: String,
    val activation: String,
    val versions: List<LintRuleVersion>,
    val positiveSeed: String? = null,
    val negativeSeed: String? = null,
    val positiveHasAssertion: Boolean = false,
    val gap: String? = null,
)

object LintCatalog {
    val rules: List<LintRule> by lazy {
        val text = requireNotNull(javaClass.getResourceAsStream("/dev/ktchecker/providers/lint/android-lint-catalog.json"))
            .use { it.readBytes().toString(Charsets.UTF_8) }
        ContractJson.decodeFromString<List<LintRule>>(text).also { rows ->
            require(rows.size == 609 && rows.map { it.ruleId }.toSet().size == 609)
            require(rows.map { it.upstreamId }.toSet().size == 603)
            require(rows.map { it.registry to it.upstreamId }.toSet().size == 609)
        }
    }
    val byRuleId: Map<String, LintRule> by lazy { rules.associateBy { it.ruleId } }
    val ruleIds: Set<String> get() = byRuleId.keys

    fun resolve(upstreamId: String, lintVersion: String, registries: Set<String>): LintRule {
        val all = rules.filter { it.upstreamId == upstreamId }
        if (all.isEmpty()) throw ExecutionProblem("lint_unknown_issue_id:$upstreamId", "Unmapped Lint diagnostic")
        val versioned = all.filter { rule -> rule.versions.any { it.lintVersion == lintVersion } }
        if (versioned.isEmpty()) throw ExecutionProblem("lint_issue_version_unavailable:$upstreamId", "Issue is absent in selected Lint version")
        val scoped = versioned.filter { it.registry in registries }
        if (scoped.isEmpty()) throw ExecutionProblem("lint_issue_origin_unavailable:$upstreamId", "Issue is absent in selected artifact registry")
        if (scoped.size > 1) throw ExecutionProblem("lint_issue_origin_ambiguous:$upstreamId", "Report did not disambiguate artifact-scoped identity")
        return scoped.single()
    }
}

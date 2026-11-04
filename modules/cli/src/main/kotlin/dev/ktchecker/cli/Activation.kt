package dev.ktchecker.cli

import dev.ktchecker.contracts.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

internal const val ACTIVATION_POLICY_KEY = "ktchecker.activation-plan"

@Serializable internal data class RuleActivation(
    val ruleId: String,
    val active: Boolean,
    val origin: String,
    val reason: String,
)

@Serializable internal data class ActivationPlan(
    val policyDigest: String,
    val decisions: List<RuleActivation>,
    val schemaVersion: Int = 1,
)

/** Candidate coverage is deliberately independent of experimental execution activation.
 * No release-qualified registry has been installed, so there are NO default-enabled rules
 * in this preview. An explicit owner subset or captured enabled policy can request an
 * experimental execution, never qualification. Disabled and excluded rows remain visible.
 */
internal fun activationPlan(
    catalog: Catalog,
    required: List<String>,
    explicitOwnerSubset: Boolean,
    ruleSettings: Map<String, Boolean>,
    policyDigest: String,
): ActivationPlan {
    val byId = catalog.records.associateBy { it.getValue("id").jsonPrimitive.content }
    if (required.distinct().size != required.size || required.any { it !in byId }) {
        throw CliProblem("invalid_rule_plan", "Candidate rule identities must be unique and known.")
    }
    return ActivationPlan(policyDigest, required.sorted().map { id ->
        val mode = byId.getValue(id).getValue("default_activation").jsonPrimitive.content
        when {
            mode == "excluded" -> RuleActivation(id, false, "catalog_exclusion", "justified_catalog_exclusion")
            ruleSettings[id] == false -> RuleActivation(id, false, "captured_owner_policy", "disabled_by_explicit_owner_policy")
            explicitOwnerSubset -> RuleActivation(id, true, "explicit_owner_subset", "experimental_unqualified_rule_requested")
            ruleSettings[id] == true -> RuleActivation(id, true, "captured_owner_policy", "experimental_unqualified_rule_requested")
            mode == "opt_in" -> RuleActivation(id, false, "catalog_default", "opt_in_rule_not_selected")
            else -> RuleActivation(id, false, "qualification_gate", "rule_context_not_qualified_for_default_activation")
        }
    })
}

/** Absence or malformed/duplicate decisions MUST NOT default to running every candidate. */
internal fun validatedActivation(input: AnalysisInput, catalog: Catalog): Map<String, RuleActivation> {
    val serialized = input.context.policies[ACTIVATION_POLICY_KEY] ?: return emptyMap()
    val plan = try { ContractJson.decodeFromString<ActivationPlan>(serialized) }
        catch (_: Exception) { throw CliProblem("invalid_activation_plan", "Owner activation plan is malformed.") }
    val ids = plan.decisions.map { it.ruleId }
    if (plan.schemaVersion != 1 || plan.policyDigest != input.context.configurationDigest ||
        ids.size != ids.toSet().size || ids.toSet() != input.requiredRuleIds.toSet() ||
        ids.any { it !in catalog.ids } || plan.decisions.any { decision -> decision.active &&
            catalog.records.single { it.getValue("id").jsonPrimitive.content == decision.ruleId }
                .getValue("default_activation").jsonPrimitive.content == "excluded" }) {
        throw CliProblem("invalid_activation_plan", "Owner activation plan does not match captured coverage and policy.")
    }
    return plan.decisions.associateBy { it.ruleId }
}

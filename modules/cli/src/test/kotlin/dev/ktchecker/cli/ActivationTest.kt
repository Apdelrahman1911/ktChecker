package dev.ktchecker.cli

import dev.ktchecker.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ActivationTest {
    private val catalog = Catalog()
    private val compiler = "ktc.compiler.common.abstract_member_not_implemented"
    private val optional = "ktc.compose.pack.ComposableParamOrder"
    private val policy = Digests.sha256("captured-policy")

    @Test fun defaultsKeepAllCandidatesVisibleWithoutEnablingUnqualifiedOrOptInChecks() {
        val required = catalog.applicableInventory("jvm")
        val plan = activationPlan(catalog, required, false, emptyMap(), policy)
        assertEquals(required.toSet(), plan.decisions.map { it.ruleId }.toSet())
        assertFalse(plan.decisions.any { it.active })
        assertEquals("opt_in_rule_not_selected", plan.decisions.single { it.ruleId == optional }.reason)
        assertEquals("rule_context_not_qualified_for_default_activation", plan.decisions.single { it.ruleId == compiler }.reason)
    }

    @Test fun explicitOwnerSelectionCannotOverrideCapturedDisableOrCatalogExclusion() {
        val excluded = catalog.records.first { it["default_activation"]!!.jsonPrimitive.content == "excluded" }["id"]!!.jsonPrimitive.content
        val plan = activationPlan(catalog, listOf(compiler, optional, excluded), true, mapOf(optional to false, excluded to true), policy)
        assertEquals(setOf(compiler), plan.decisions.filter { it.active }.map { it.ruleId }.toSet())
        assertEquals("disabled_by_explicit_owner_policy", plan.decisions.single { it.ruleId == optional }.reason)
        assertEquals("justified_catalog_exclusion", plan.decisions.single { it.ruleId == excluded }.reason)
    }

    @Test fun ownerPolicyEnablesOnlyItsSelectedCandidateAndNeverChangesQualification() {
        val plan = activationPlan(catalog, listOf(compiler, optional), false, mapOf(optional to true), policy)
        assertEquals(listOf(optional), plan.decisions.filter { it.active }.map { it.ruleId })
        assertEquals("experimental_unqualified_rule_requested", plan.decisions.single { it.active }.reason)
    }

    @Test fun missingMalformedDuplicateOrWrongPolicyPlansFailClosed() {
        val input = AnalysisInput(SnapshotManifest("snapshot", "workspace", "tree", emptyList()), "/sealed",
            AnalysisContext(configurationDigest = policy), listOf(compiler))
        assertTrue(validatedActivation(input, catalog).isEmpty())
        val plan = activationPlan(catalog, listOf(compiler), true, emptyMap(), policy)
        fun bound(value: ActivationPlan) = input.copy(context = input.context.copy(policies = mapOf(
            ACTIVATION_POLICY_KEY to ContractJson.encodeToString(ActivationPlan.serializer(), value))))
        assertTrue(validatedActivation(bound(plan), catalog).getValue(compiler).active)
        assertFailsWith<CliProblem> { validatedActivation(bound(plan.copy(policyDigest = "wrong")), catalog) }
        assertFailsWith<CliProblem> { validatedActivation(bound(plan.copy(decisions = plan.decisions + plan.decisions)), catalog) }
        assertFailsWith<CliProblem> { validatedActivation(bound(plan.copy(decisions = emptyList())), catalog) }
    }
}

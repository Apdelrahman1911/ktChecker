package dev.ktchecker.providers.buildcontracts

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.ProviderResult
import dev.ktchecker.contracts.RuleContext
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Complements the single-rule B1 policy tests with mixed-rule requests and the remaining scope
 * fields. Owner callbacks are synthetic; suppressed/disabled/unavailable rows never become
 * successful coverage, and an unsupported same-family sibling must remain visible.
 */
class WrapperRulePolicyIntegrationTest {
    @Test fun owner_disable_applies_only_to_the_exact_rule_and_keeps_the_requested_sibling_visible() {
        val fixture = fixture()
        val result = fixture.analyze(WrapperRulePolicy(enabled = false))
        assertRequiredRows(fixture, result, "disabled", "wrapper_rule_disabled_by_owner")
        assertTrue(result.findings.isEmpty())
    }

    @Test fun exact_suppression_keeps_required_rows_and_does_not_create_success_for_either_rule() {
        val fixture = fixture()
        val result = fixture.analyze(WrapperRulePolicy(suppressions = listOf(exactSuppression(fixture))))
        assertRequiredRows(fixture, result, "suppressed", "wrapper_exact_id_suppressed_by_owner")
        assertTrue(result.findings.isEmpty())
        assertFalse(ContractJson.encodeToString(result).contains(PRIVATE_JUSTIFICATION))
    }

    @Test fun a_valid_suppression_cannot_salvage_an_invalid_entry_into_suppressed_or_clean_coverage() {
        val fixture = fixture()
        val valid = exactSuppression(fixture)
        listOf(valid.copy(ruleId = SIBLING_RULE), valid.copy(ruleId = "ktc.gradle.*"),
            valid.copy(justification = " ")).forEach { invalid ->
            val result = fixture.analyze(WrapperRulePolicy(suppressions = listOf(valid, invalid)))
            assertRequiredRows(fixture, result, "unavailable", WrapperUnavailableReason.POLICY_UNSUPPORTED.code)
            // Empty findings here are a policy failure, not a successfully applied suppression.
            assertTrue(result.findings.isEmpty())
        }
        assertEquals(0, fixture.snapshots)
        assertEquals(0, fixture.reads)
        assertEquals(0, fixture.policyReceipts)
    }

    @Test fun revoking_an_exact_suppression_receipt_returns_unavailable_not_suppression_success() {
        val fixture = fixture()
        val admission = fixture.install(WrapperRulePolicy(suppressions = listOf(exactSuppression(fixture))))
        assertIs<WrapperContextAdmission.Installed>(admission)
        fixture.policyAllowed = false
        val result = GradleWrapperChecksumProvider(admission).analyze(fixture.input)
        assertRequiredRows(fixture, result, "unavailable", WrapperUnavailableReason.POLICY_ATTESTATION_UNAVAILABLE.code)
        assertTrue(result.findings.isEmpty())
        assertEquals(2, fixture.policyReceipts)
        assertEquals(2, fixture.reads)
    }

    @Test fun same_rule_path_target_and_source_set_cannot_suppress_another_module() {
        val fixture = fixture()
        assertStillFinds(fixture, exactSuppression(fixture).copy(context = fixture.scope.copy(module = ":other-build")))
    }

    @Test fun same_rule_path_target_source_set_and_module_cannot_suppress_another_compilation() {
        val fixture = fixture()
        assertStillFinds(fixture, exactSuppression(fixture).copy(context = fixture.scope.copy(compilation = "other")))
    }

    private fun fixture(): WrapperContractFixture = WrapperContractFixture(
        scope = RuleContext(target = "jvm", sourceSet = "main", module = ":owned-build", compilation = "main"),
        requested = listOf(WRAPPER_CHECKSUM_FORMAT, SIBLING_RULE),
    )

    private fun exactSuppression(fixture: WrapperContractFixture): WrapperRuleSuppression =
        WrapperRuleSuppression(WRAPPER_CHECKSUM_FORMAT, WrapperContractFixture.PROPERTIES_PATH,
            fixture.scope, PRIVATE_JUSTIFICATION)

    private fun assertStillFinds(fixture: WrapperContractFixture, suppression: WrapperRuleSuppression) {
        val result = fixture.analyze(WrapperRulePolicy(suppressions = listOf(suppression)))
        assertRequiredRows(fixture, result, "findings", "wrapper_checksum_format_observed_unqualified")
        val finding = result.findings.single()
        assertEquals(WRAPPER_CHECKSUM_FORMAT, finding.ruleId)
        assertEquals(WrapperContractFixture.PROPERTIES_PATH, finding.location.path)
        assertEquals(fixture.scope, finding.context)
    }

    private fun assertRequiredRows(
        fixture: WrapperContractFixture,
        result: ProviderResult,
        expectedState: String,
        expectedReason: String,
    ) {
        assertEquals("partial", result.status)
        assertNoncertifying(result)
        assertFalse(result.completeFor(fixture.input))
        assertEquals(listOf(WRAPPER_CHECKSUM_FORMAT, SIBLING_RULE), result.coverage.map { it.ruleId })
        assertTrue(result.coverage.all { it.required && it.context == fixture.scope })
        val selected = result.coverage.single { it.ruleId == WRAPPER_CHECKSUM_FORMAT }
        assertEquals(expectedState, selected.state)
        assertEquals(expectedReason, selected.reason)
        assertFalse(selected.successful())
        val sibling = result.coverage.single { it.ruleId == SIBLING_RULE }
        assertEquals("unsupported", sibling.state)
        assertEquals("build_contract_rule_not_supported", sibling.reason)
        assertFalse(sibling.successful())
        assertEquals(listOf(expectedReason), result.reasons)
    }

    private companion object {
        // Existing catalog ID in the same Gradle family; this provider does not implement it.
        const val SIBLING_RULE = "ktc.gradle.release_dependency_verification_policy"
        const val PRIVATE_JUSTIFICATION = "private-owner-policy-reason"
    }
}

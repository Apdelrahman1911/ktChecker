package dev.ktchecker.providers.buildcontracts

import dev.ktchecker.contracts.*
import kotlin.test.*

class WrapperActivationPolicyBoundsTest {
    private fun completePlan(): String = requireNotNull(javaClass.getResourceAsStream(
        "/dev/ktchecker/providers/buildcontracts/complete-catalog-activation-1663.json",
    )).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun assertRejectedBeforeOwner(fixture: WrapperContractFixture, input: AnalysisInput) {
        assertUnavailable(fixture.install(actualInput = input), WrapperUnavailableReason.ANALYSIS_ENVELOPE_BOUND_EXCEEDED)
        assertEquals(0, fixture.snapshots)
        assertEquals(0, fixture.selectedReceipts)
        assertEquals(0, fixture.pathReceipts)
        assertEquals(0, fixture.policyReceipts)
        assertEquals(0, fixture.reads)
    }

    @Test fun complete_actual_1663_id_activation_plan_is_preserved_and_independently_reverified() {
        val plan = completePlan()
        assertTrue(plan.length > 4096)
        assertTrue(plan.toByteArray(Charsets.UTF_8).size < MAX_WRAPPER_ACTIVATION_POLICY_UTF8_BYTES)
        assertEquals(1663, Regex("\"ruleId\":").findAll(plan).count())
        assertEquals(1, Regex("\"active\":true").findAll(plan).count())
        val policies = mapOf(WRAPPER_ACTIVATION_POLICY_KEY to plan, "sibling-owner-policy" to "retain-exactly")
        val fixture = WrapperContractFixture(inputPolicies = policies)
        val frozen = freezeWrapperInput(fixture.input)
        assertTrue(frozen.context.policies == policies)
        val installed = assertIs<WrapperContextAdmission.Installed>(fixture.install())
        installed.context.verify(fixture.input, CancellationToken.NONE)
        assertEquals(2, fixture.snapshots)
        assertEquals(2, fixture.selectedReceipts)
        assertEquals(2, fixture.pathReceipts)
        assertEquals(2, fixture.policyReceipts)
        val result = fixture.analyze()
        assertEquals(1, result.findings.size)
        assertEquals(WRAPPER_CHECKSUM_FORMAT, result.findings.single().ruleId)
        assertNoncertifying(result)
    }

    @Test fun changing_one_full_activation_decision_breaks_exact_child_binding() {
        val plan = completePlan()
        val fixture = WrapperContractFixture(inputPolicies = mapOf(
            WRAPPER_ACTIVATION_POLICY_KEY to plan, "sibling-owner-policy" to "retain-exactly",
        ))
        val installed = assertIs<WrapperContextAdmission.Installed>(fixture.install())
        val changedPlan = plan.replaceFirst("\"active\":true", "\"active\":false")
        assertTrue(changedPlan != plan)
        val changed = fixture.input.copy(context = fixture.input.context.copy(policies =
            fixture.input.context.policies + (WRAPPER_ACTIVATION_POLICY_KEY to changedPlan)))
        val problem = assertFailsWith<WrapperContractProblem> {
            installed.context.verify(changed, CancellationToken.NONE)
        }
        assertEquals(WrapperUnavailableReason.INPUT_BINDING_MISMATCH, problem.reason)
        assertEquals(1, fixture.snapshots)
        assertEquals(1, fixture.selectedReceipts)
        assertEquals(1, fixture.pathReceipts)
        assertEquals(1, fixture.policyReceipts)
    }

    @Test fun exact_one_mib_ascii_activation_value_is_accepted_as_opaque_policy_only() {
        val value = "x".repeat(MAX_WRAPPER_ACTIVATION_POLICY_UTF8_BYTES)
        val fixture = WrapperContractFixture(inputPolicies = mapOf(WRAPPER_ACTIVATION_POLICY_KEY to value))
        assertTrue(freezeWrapperInput(fixture.input).context.policies[WRAPPER_ACTIVATION_POLICY_KEY] == value)
        val installed = assertIs<WrapperContextAdmission.Installed>(fixture.install())
        installed.context.verify(fixture.input, CancellationToken.NONE)
        assertEquals(2, fixture.policyReceipts)
    }

    @Test fun exact_one_mib_multibyte_utf8_activation_values_are_accepted() {
        val maximum = MAX_WRAPPER_ACTIVATION_POLICY_UTF8_BYTES
        val values = listOf(
            "\u00e9".repeat(maximum / 2),
            "\u20ac".repeat(maximum / 3) + "x".repeat(maximum % 3),
            "\uD83D\uDE00".repeat(maximum / 4),
        )
        values.forEach { value ->
            assertEquals(maximum, value.toByteArray(Charsets.UTF_8).size)
            val fixture = WrapperContractFixture(inputPolicies = mapOf(WRAPPER_ACTIVATION_POLICY_KEY to value))
            assertTrue(freezeWrapperInput(fixture.input).context.policies[WRAPPER_ACTIVATION_POLICY_KEY] == value)
            assertIs<WrapperContextAdmission.Installed>(fixture.install())
            assertEquals(1, fixture.policyReceipts)
        }
    }

    @Test fun multibyte_utf8_maximum_overflow_rejects_before_owner_callbacks_and_bytes() {
        val maximum = MAX_WRAPPER_ACTIVATION_POLICY_UTF8_BYTES
        val values = listOf(
            "\u00e9".repeat(maximum / 2 + 1),
            "\u20ac".repeat(maximum / 3 + 1),
            "\uD83D\uDE00".repeat(maximum / 4 + 1),
        )
        values.forEach { value ->
            assertTrue(value.length <= maximum)
            assertTrue(value.toByteArray(Charsets.UTF_8).size > maximum)
            val fixture = WrapperContractFixture()
            assertRejectedBeforeOwner(fixture, fixture.input.copy(context = fixture.input.context.copy(
                policies = mapOf(WRAPPER_ACTIVATION_POLICY_KEY to value))))
        }
    }

    @Test fun ascii_activation_maximum_overflow_rejects_before_owner_callbacks_and_bytes() {
        val fixture = WrapperContractFixture()
        assertRejectedBeforeOwner(fixture, fixture.input.copy(context = fixture.input.context.copy(policies =
            mapOf(WRAPPER_ACTIVATION_POLICY_KEY to "x".repeat(MAX_WRAPPER_ACTIVATION_POLICY_UTF8_BYTES + 1)))))
    }

    @Test fun every_other_policy_value_keeps_4096_character_bound_without_stripping() {
        val plan = completePlan()
        val sibling = "\u20ac".repeat(4096)
        val fixture = WrapperContractFixture(inputPolicies = mapOf(
            WRAPPER_ACTIVATION_POLICY_KEY to plan, "sibling-owner-policy" to sibling,
        ))
        assertIs<WrapperContextAdmission.Installed>(fixture.install())
        assertTrue(freezeWrapperInput(fixture.input).context.policies["sibling-owner-policy"] == sibling)
        val rejected = WrapperContractFixture()
        assertRejectedBeforeOwner(rejected, rejected.input.copy(context = rejected.input.context.copy(policies =
            mapOf(WRAPPER_ACTIVATION_POLICY_KEY to plan, "sibling-owner-policy" to sibling + "x"))))
    }

    @Test fun activation_named_library_value_does_not_receive_policy_only_larger_bound() {
        val fixture = WrapperContractFixture()
        assertRejectedBeforeOwner(fixture, fixture.input.copy(context = fixture.input.context.copy(
            libraries = mapOf(WRAPPER_ACTIVATION_POLICY_KEY to completePlan()))))
    }

    @Test fun activation_value_still_contributes_to_aggregate_eight_mib_envelope_bound() {
        val policies = (0 until 100).associate { "sibling-$it" to "x".repeat(4096) } + mapOf(
            WRAPPER_ACTIVATION_POLICY_KEY to "x".repeat(MAX_WRAPPER_ACTIVATION_POLICY_UTF8_BYTES),
        )
        val fixture = WrapperContractFixture()
        assertRejectedBeforeOwner(fixture, fixture.input.copy(context = fixture.input.context.copy(policies = policies)))
    }

    @Test fun unpaired_activation_surrogates_reject_before_owner_but_serialized_escape_literals_remain_opaque() {
        val maximum = MAX_WRAPPER_ACTIVATION_POLICY_UTF8_BYTES
        val values = listOf("\uD800", "\uDC00", "\uD800A", "A\uDC00", "\uD800".repeat(maximum))
        values.forEach { value ->
            val fixture = WrapperContractFixture()
            val input = fixture.input.copy(context = fixture.input.context.copy(policies =
                mapOf(WRAPPER_ACTIVATION_POLICY_KEY to value)))
            assertRejectedBeforeOwner(fixture, input)
        }
        val serializedLiteral = """{"reason":"\uD800"}"""
        val fixture = WrapperContractFixture(inputPolicies = mapOf(WRAPPER_ACTIVATION_POLICY_KEY to serializedLiteral))
        assertTrue(freezeWrapperInput(fixture.input).context.policies[WRAPPER_ACTIVATION_POLICY_KEY] == serializedLiteral)
        assertIs<WrapperContextAdmission.Installed>(fixture.install())
    }
}

package dev.ktchecker.providers.buildcontracts

import dev.ktchecker.contracts.*
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Useful provider integration against synthetic owner receipts, not wrapper/runtime qualification. */
class GradleWrapperChecksumProviderTest {
    @Test fun malformed_declaration_has_a_useful_exact_ID_format_finding() {
        val fixture = WrapperContractFixture()
        val result = fixture.analyze()
        assertNoncertifying(result)
        val finding = result.findings.single()
        assertEquals(WRAPPER_CHECKSUM_FORMAT, finding.ruleId)
        assertEquals(BUILD_CONTRACTS_PROVIDER_ID, finding.providerId)
        assertEquals("high", finding.certainty)
        assertEquals("error", finding.severity)
        assertEquals(fixture.scope, finding.context)
        assertEquals(WrapperContractFixture.PROPERTIES_PATH, finding.location.path)
        assertEquals(1, finding.location.line)
        assertEquals("", finding.location.excerpt)
        assertEquals("findings", result.coverage.single().state)
        assertEquals(2, fixture.snapshots)
        assertEquals(2, fixture.selectedReceipts)
        assertEquals(2, fixture.pathReceipts)
        assertEquals(2, fixture.policyReceipts)
        assertEquals(2, fixture.reads)
        assertTrue(finding.verification.all { it.command.isEmpty() && it.state == "not_run" })
    }

    @Test fun pinned_accepted_shape_is_not_a_claim_of_digest_integrity() {
        val result = WrapperContractFixture(WrapperContractFixture.properties(WrapperContractFixture.ACCEPTED_DIGEST)).analyze()
        assertNoncertifying(result)
        assertTrue(result.findings.isEmpty())
        assertEquals("unqualified", result.coverage.single().state)
        assertEquals("wrapper_checksum_shape_accepted_integrity_unverified", result.coverage.single().reason)
    }

    @Test fun syntactically_valid_but_unverified_wrong_digest_is_outside_format_predicate() {
        val result = WrapperContractFixture(WrapperContractFixture.properties("0".repeat(64))).analyze()
        assertTrue(result.findings.isEmpty())
        assertNoncertifying(result)
        assertEquals("wrapper_checksum_shape_accepted_integrity_unverified", result.coverage.single().reason)
    }

    @Test fun absent_checksum_is_separate_optional_policy() {
        val result = WrapperContractFixture(WrapperContractFixture.properties(null)).analyze()
        assertTrue(result.findings.isEmpty())
        assertEquals("wrapper_checksum_absent_optional_policy_separate", result.coverage.single().reason)
        assertNoncertifying(result)
    }

    @Test fun declared_empty_value_is_not_absence() {
        val result = WrapperContractFixture(WrapperContractFixture.properties("")).analyze()
        assertEquals(1, result.findings.size)
        assertNoncertifying(result)
    }

    @Test fun uppercase_letters_are_not_accepted_for_this_case_sensitive_consumer() {
        val result = WrapperContractFixture(WrapperContractFixture.properties("A".repeat(64))).analyze()
        assertEquals(1, result.findings.size)
        assertNoncertifying(result)
    }

    @Test fun cached_distribution_without_comparison_is_not_a_runtime_failure() {
        val fixture = WrapperContractFixture().apply { verificationPath = WrapperVerificationPath.CACHED_WITHOUT_COMPARISON }
        val result = fixture.analyze()
        assertTrue(result.findings.isEmpty())
        assertEquals("wrapper_cached_path_without_verification", result.coverage.single().reason)
        assertEquals("not_applicable", result.coverage.single().state)
        assertNoncertifying(result)
    }

    @Test fun unknown_cache_path_has_explicit_unavailable_coverage_not_empty_pass() {
        val fixture = WrapperContractFixture().apply { verificationPath = WrapperVerificationPath.UNKNOWN }
        val result = fixture.analyze()
        assertTrue(result.findings.isEmpty())
        assertEquals(WrapperUnavailableReason.VERIFICATION_PATH_ATTESTATION_UNAVAILABLE.code, result.coverage.single().reason)
        assertEquals("unavailable", result.coverage.single().state)
        assertNoncertifying(result)
    }

    @Test fun default_missing_installed_context_is_explicit_unavailable() {
        val fixture = WrapperContractFixture()
        val result = GradleWrapperChecksumProvider().analyze(fixture.input)
        assertEquals(WrapperUnavailableReason.SELECTED_CONSUMER_ATTESTATION_UNAVAILABLE.code, result.coverage.single().reason)
        assertTrue(result.findings.isEmpty())
        assertEquals(0, fixture.reads)
        assertNoncertifying(result)
    }

    @Test fun project_JSON_policy_strings_cannot_claim_wrapper_comparison_or_cache_authority() {
        val fixture = WrapperContractFixture()
        val input = fixture.input.copy(context = fixture.input.context.copy(policies = mapOf(
            "knownWrapper" to "true", "wrapperVersion" to SELECTED_WRAPPER_VERSION,
            "comparison" to "lowercase-exact", "cacheState" to "fresh", WRAPPER_CHECKSUM_FORMAT to "enabled")))
        val result = GradleWrapperChecksumProvider().analyze(input)
        assertEquals(WrapperUnavailableReason.SELECTED_CONSUMER_ATTESTATION_UNAVAILABLE.code, result.coverage.single().reason)
        assertTrue(result.findings.isEmpty())
        assertNoncertifying(result)
    }

    @Test fun escaped_key_and_continuation_use_actual_Properties_semantics_in_provider() {
        val fixture = WrapperContractFixture(WrapperContractFixture.BASE_URL + "distributionSha\\u003256Sum=a\\\n  bc\n")
        assertEquals(1, fixture.analyze().findings.size)
    }

    @Test fun a_valid_continued_digest_is_not_a_regex_false_positive() {
        val digest = WrapperContractFixture.ACCEPTED_DIGEST
        val result = WrapperContractFixture(WrapperContractFixture.BASE_URL +
            "distributionSha256Sum=${digest.take(32)}\\\n  ${digest.drop(32)}\n").analyze()
        assertTrue(result.findings.isEmpty())
        assertNoncertifying(result)
    }

    @Test fun duplicate_declarations_observe_only_the_consumed_last_value() {
        val digest = WrapperContractFixture.ACCEPTED_DIGEST
        val valid = WrapperContractFixture(WrapperContractFixture.BASE_URL +
            "distributionSha256Sum=abc\ndistributionSha256Sum=$digest\n").analyze()
        val invalid = WrapperContractFixture(WrapperContractFixture.BASE_URL +
            "distributionSha256Sum=$digest\ndistributionSha256Sum=abc\n").analyze()
        assertTrue(valid.findings.isEmpty())
        assertEquals(1, invalid.findings.size)
        assertNoncertifying(valid); assertNoncertifying(invalid)
    }

    @Test fun malformed_unrelated_properties_escape_is_a_parser_gap_not_salvaged_findings() {
        val fixture = WrapperContractFixture(WrapperContractFixture.properties("abc") + "other=\\uZZZZ\n")
        val result = fixture.analyze()
        assertEquals(WrapperUnavailableReason.PROPERTIES_PARSER_UNAVAILABLE.code, result.coverage.single().reason)
        assertTrue(result.findings.isEmpty())
        assertNoncertifying(result)
    }

    @Test fun unselected_generated_and_test_properties_are_not_scanned_as_build_contracts() {
        val ignored = WrapperContractFixture.properties("abc").toByteArray()
        val fixture = WrapperContractFixture(WrapperContractFixture.properties(WrapperContractFixture.ACCEPTED_DIGEST),
            extraFiles = mapOf("build/generated/gradle-wrapper.properties" to ignored, "src/test/gradle-wrapper.properties" to ignored))
        val result = fixture.analyze()
        assertTrue(result.findings.isEmpty())
        assertEquals(2, fixture.reads)
        assertNoncertifying(result)
    }

    @Test fun caller_selection_of_generated_properties_is_not_actual_wrapper_ownership_proof() {
        val path = "build/generated/gradle-wrapper.properties"
        val fixture = WrapperContractFixture(WrapperContractFixture.properties(WrapperContractFixture.ACCEPTED_DIGEST),
            extraFiles = mapOf(path to WrapperContractFixture.properties("abc").toByteArray()))
        val result = fixture.analyze(actualSelection = fixture.selection.copy(propertiesPath = path))
        assertTrue(result.findings.isEmpty())
        assertEquals(WrapperUnavailableReason.SELECTED_CONSUMER_ATTESTATION_UNAVAILABLE.code, result.coverage.single().reason)
        assertNoncertifying(result)
    }

    @Test fun owner_disable_is_exact_noncertifying_policy() {
        val result = WrapperContractFixture().analyze(WrapperRulePolicy(enabled = false))
        assertTrue(result.findings.isEmpty())
        assertEquals("disabled", result.coverage.single().state)
        assertEquals("wrapper_rule_disabled_by_owner", result.coverage.single().reason)
        assertNoncertifying(result)
    }

    @Test fun justified_exact_ID_path_context_suppression_does_not_create_clean_credit() {
        val fixture = WrapperContractFixture()
        val suppression = WrapperRuleSuppression(WRAPPER_CHECKSUM_FORMAT, WrapperContractFixture.PROPERTIES_PATH,
            fixture.scope, "private-owner-justification")
        val result = fixture.analyze(WrapperRulePolicy(suppressions = listOf(suppression)))
        assertTrue(result.findings.isEmpty())
        assertEquals("suppressed", result.coverage.single().state)
        assertNoncertifying(result)
        assertFalse(ContractJson.encodeToString(result).contains("private-owner-justification"))
    }

    @Test fun another_target_or_source_set_suppression_does_not_hide_this_context() {
        val fixture = WrapperContractFixture()
        listOf(fixture.scope.copy(target = "iosArm64"), fixture.scope.copy(sourceSet = "test")).forEach { other ->
            val suppression = WrapperRuleSuppression(WRAPPER_CHECKSUM_FORMAT, WrapperContractFixture.PROPERTIES_PATH, other, "reviewed")
            assertEquals(1, fixture.analyze(WrapperRulePolicy(suppressions = listOf(suppression))).findings.size)
        }
    }

    @Test fun another_selected_properties_path_suppression_does_not_hide_this_file() {
        val fixture = WrapperContractFixture()
        val suppression = WrapperRuleSuppression(WRAPPER_CHECKSUM_FORMAT, "other/gradle-wrapper.properties", fixture.scope, "reviewed")
        assertEquals(1, fixture.analyze(WrapperRulePolicy(suppressions = listOf(suppression))).findings.size)
    }

    @Test fun sibling_ID_and_family_wildcard_suppressions_are_rejected_not_suppress_all() {
        val fixture = WrapperContractFixture()
        listOf("ktc.compiler.common.abstract_class_member_not_implemented", "ktc.gradle.*").forEach { id ->
            val suppression = WrapperRuleSuppression(id, WrapperContractFixture.PROPERTIES_PATH, fixture.scope, "reviewed")
            val result = fixture.analyze(WrapperRulePolicy(suppressions = listOf(suppression)))
            assertTrue(result.findings.isEmpty())
            assertEquals(WrapperUnavailableReason.POLICY_UNSUPPORTED.code, result.coverage.single().reason)
            assertNoncertifying(result)
        }
    }

    @Test fun requested_sibling_stays_visible_and_unsupported_with_no_leaked_finding_or_credit() {
        val sibling = "ktc.compiler.common.abstract_class_member_not_implemented"
        val fixture = WrapperContractFixture(requested = listOf(WRAPPER_CHECKSUM_FORMAT, sibling))
        val result = fixture.analyze()
        assertEquals(listOf(WRAPPER_CHECKSUM_FORMAT, sibling), result.coverage.map { it.ruleId })
        assertEquals(WRAPPER_CHECKSUM_FORMAT, result.findings.single().ruleId)
        assertEquals("unsupported", result.coverage.single { it.ruleId == sibling }.state)
        assertNoncertifying(result)
    }

    @Test fun all_owner_attested_catalog_platform_labels_are_synthetic_context_controls_only() {
        listOf(WrapperBuildPlatform.COMMON, WrapperBuildPlatform.ANDROID, WrapperBuildPlatform.IOS,
            WrapperBuildPlatform.DESKTOP, WrapperBuildPlatform.JVM).forEach { platform ->
            val scope = RuleContext(target = platform.name.lowercase(), module = ":owned-build")
            val fixture = WrapperContractFixture(scope = scope, platform = platform)
            val result = fixture.analyze()
            assertEquals(scope, result.findings.single().context)
            assertNoncertifying(result)
        }
    }

    @Test fun unknown_platform_is_unavailable_without_inferred_support() {
        val result = WrapperContractFixture(platform = WrapperBuildPlatform.UNKNOWN).analyze()
        assertEquals(WrapperUnavailableReason.PLATFORM_ATTESTATION_UNAVAILABLE.code, result.coverage.single().reason)
        assertTrue(result.findings.isEmpty())
        assertNoncertifying(result)
    }

    @Test fun URL_version_suffix_does_not_override_an_unsupported_owner_version() {
        val fixture = WrapperContractFixture().apply { wrapperVersion = "9.6.2" }
        val result = fixture.analyze()
        assertEquals(WrapperUnavailableReason.SELECTED_VERSION_UNSUPPORTED.code, result.coverage.single().reason)
        assertTrue(result.findings.isEmpty())
        assertNoncertifying(result)
    }

    @Test fun distribution_version_also_requires_actual_owner_provenance() {
        val fixture = WrapperContractFixture().apply { distributionVersion = "unknown" }
        val result = fixture.analyze()
        assertEquals(WrapperUnavailableReason.SELECTED_VERSION_UNSUPPORTED.code, result.coverage.single().reason)
        assertTrue(result.findings.isEmpty())
    }

    @Test fun URL_credentials_checksum_and_exceptions_never_enter_serialized_result() {
        val text = "distributionUrl=https\\://private-user:private-password@example.invalid/gradle-9.6.1-bin.zip\n" +
            "distributionSha256Sum=private-checksum-secret\n"
        val result = WrapperContractFixture(text).analyze()
        assertEquals(1, result.findings.size)
        val encoded = ContractJson.encodeToString(result)
        listOf("private-user", "private-password", "private-checksum-secret", "example.invalid", "https://").forEach {
            assertFalse(encoded.contains(it))
        }
        val fixture = WrapperContractFixture().apply { snapshotAllowed = false }
        val failed = fixture.analyze()
        assertFalse(ContractJson.encodeToString(failed).contains("private-fixture-snapshot-error"))
        assertEquals(WrapperUnavailableReason.SNAPSHOT_ATTESTATION_UNAVAILABLE.code, failed.coverage.single().reason)
    }

    @Test fun input_target_change_cannot_reuse_installed_context_or_suppression_authority() {
        val fixture = WrapperContractFixture()
        val admission = fixture.install()
        val changed = fixture.input.copy(context = fixture.input.context.copy(scope = fixture.scope.copy(target = "iosArm64")))
        val result = GradleWrapperChecksumProvider(admission).analyze(changed)
        assertEquals(WrapperUnavailableReason.INPUT_BINDING_MISMATCH.code, result.coverage.single().reason)
        assertTrue(result.findings.isEmpty())
        assertNoncertifying(result)
    }

    @Test fun cancelled_analysis_never_runs_owner_callbacks_or_source_readers() {
        val fixture = WrapperContractFixture()
        val result = GradleWrapperChecksumProvider().analyze(fixture.input, CancellationToken { true })
        assertEquals("cancelled", result.status)
        assertEquals(WrapperUnavailableReason.CANCELLED.code, result.coverage.single().reason)
        assertEquals(0, fixture.snapshots)
        assertEquals(0, fixture.reads)
        assertNoncertifying(result)
    }

    @Test fun duplicate_or_empty_rule_requests_are_not_implicit_activation() {
        val fixture = WrapperContractFixture()
        val duplicate = fixture.input.copy(requiredRuleIds = listOf(WRAPPER_CHECKSUM_FORMAT, WRAPPER_CHECKSUM_FORMAT))
        val duplicates = GradleWrapperChecksumProvider().analyze(duplicate)
        assertEquals("wrapper_rule_selection_duplicate", duplicates.coverage.single().reason)
        assertNoncertifying(duplicates)
        val empty = GradleWrapperChecksumProvider().analyze(fixture.input.copy(requiredRuleIds = emptyList()))
        assertEquals(listOf("wrapper_no_explicit_rules_requested"), empty.reasons)
        assertTrue(empty.coverage.isEmpty())
        assertNoncertifying(empty)
    }

    @Test fun deterministic_same_snapshot_receipts_have_stable_fingerprints() {
        val fixture = WrapperContractFixture()
        assertEquals(fixture.analyze().findings.single().fingerprint, fixture.analyze().findings.single().fingerprint)
    }
}

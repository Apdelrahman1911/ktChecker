package dev.ktchecker.compiler

import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.Finding
import dev.ktchecker.contracts.RawDiagnostic
import dev.ktchecker.contracts.SourceLocation
import java.nio.file.Path
import kotlinx.serialization.json.*

/** Fixed proposed reservation identities, never an admission permit or execution witness. */
internal data class CompilerFixtureNullabilityDeclaration(
    val number: Int, val ruleId: String, val upstreamId: String, val kind: String,
    val path: String, val sourceSha256: String,
) {
    init {
        require(number in 1..CompilerFixtureNullabilityPlan.INPUT_COUNT)
        require(kind in CompilerFixtureNullabilityPlan.KINDS)
        require(ruleId == "ktc.compiler.common." + upstreamId.lowercase())
        require(sourceSha256.matches(Regex("[0-9a-f]{64}")))
        require(path == "cohort-plans/nullability-protocols-cases/" + upstreamId.lowercase() + "/" + kind + ".kt")
    }
    val label: String get() = "nullability-$kind-$upstreamId"

    /** This exact per-source oracle is declared before any actual job and never filters raw output. */
    fun oracle(): Map<String, Any?> = mapOf(
        "authored_path" to path, "source_sha256" to sourceSha256,
        "kind" to kind, "selected_upstream_id" to upstreamId, "selected_stable_id" to ruleId,
        "proposed_compilation" to when (kind) {
            "positive" -> "supervised_nonzero_with_exact_selected_raw_and_mapped_ERROR"
            "suppression" -> "unknown_preserve_every_actual_compiler_outcome"
            else -> "supervised_exit0_selected_ID_absent_in_both_channels"
        },
        "allowed_error_upstream_ids" to when (kind) {
            "positive" -> listOf(upstreamId)
            "suppression" -> null // Unknown is not an empty-error expectation or permission to drop errors.
            else -> emptyList<String>()
        },
        "positive_selected_error_requires_exact_authored_file_and_valid_actual_location" to (kind == "positive"),
        "unexpected_or_unnamed_raw_and_mapped_errors" to if (kind == "suppression")
            "report_all_actual_errors_without_validating_unknown_suppression" else "reject_as_unmatched_and_retain_all",
        "non_error_diagnostics" to "allow_and_retain_all_without_clean_compilation_or_coverage_claim",
        "suppression_expectation_validation" to if (kind == "suppression") "unknown_not_validated" else "not_applicable",
        "initial_source_anchors_are_not_observed_diagnostic_locations" to true,
        "ordinary_six_kind_assessment" to false, "qualification" to "none",
    )

    fun description(): Map<String, Any?> = mapOf("planned_attempt_number" to number,
        "rule_id" to ruleId, "upstream_id" to upstreamId, "kind" to kind,
        "authored_path" to path, "source_sha256" to sourceSha256, "job_label" to label,
        "predeclared_per_source_oracle" to oracle(), "planned_number_is_not_execution_authority" to true)
}

internal data class CompilerFixtureNullabilityInput(
    val declaration: CompilerFixtureNullabilityDeclaration, val source: String, val authoringRecord: JsonObject,
) {
    fun description(): Map<String, Any?> = declaration.description() + mapOf(
        "compiler_flags" to CompilerFixtureNullabilityPlan.FLAGS,
        "initial_manifest_authoring_record" to authoringRecord,
        "initial_authoring_status_is_not_current_execution_evidence" to true)
}

/**
 * Pure decisions over independently supplied actual runner observations. Unit values grant no
 * execution, cleanup, provenance or qualification. An expected-error positive can match its
 * proposed oracle while its actual compiler task remains failed. All raw errors stay retained.
 */
internal data class CompilerFixtureNullabilityObservation(
    val declaration: CompilerFixtureNullabilityDeclaration,
    val attemptNumber: Int,
    val executionConfirmed: Boolean,
    val healthySupervisedExecution: Boolean,
    val pinnedVersionObserved: Boolean,
    val receiptsAndAuthoredInputIntact: Boolean,
    val actualExitCode: Int?,
    val providerStatus: String,
    val selectedRawDiagnosticCount: Int,
    val selectedRawErrorAtAuthoredPointCount: Int,
    val rawDiagnosticCount: Int,
    val rawErrorDiagnosticCount: Int,
    val selectedFindingCount: Int,
    val selectedErrorFindingAtAuthoredPointCount: Int,
    val selectedErrorRawAndMappedPointsAgree: Boolean,
    val findingCount: Int,
    val errorFindingCount: Int,
    val errorSuppressionDiagnosticCount: Int,
) {
    init {
        require(attemptNumber > 0)
        require(selectedRawDiagnosticCount in 0..rawDiagnosticCount)
        require(selectedRawErrorAtAuthoredPointCount in 0..selectedRawDiagnosticCount)
        require(rawErrorDiagnosticCount in selectedRawErrorAtAuthoredPointCount..rawDiagnosticCount)
        require(selectedFindingCount in 0..findingCount)
        require(selectedErrorFindingAtAuthoredPointCount in 0..selectedFindingCount)
        require(errorFindingCount in selectedErrorFindingAtAuthoredPointCount..findingCount)
        require(errorSuppressionDiagnosticCount in 0..rawDiagnosticCount)
    }

    val observedReceiptComplete: Boolean get() = executionConfirmed && healthySupervisedExecution &&
        pinnedVersionObserved && receiptsAndAuthoredInputIntact && actualExitCode != null &&
        providerStatus in setOf("partial", "failed") && (actualExitCode == 0 || providerStatus == "failed")
    val selectedIdObserved: Boolean get() = selectedRawDiagnosticCount > 0 || selectedFindingCount > 0
    // Retain actual failure facts even if later receipt/START checks withhold attribution.
    val actualCompilerTaskFailed: Boolean get() = executionConfirmed && actualExitCode != null && actualExitCode != 0
    val actualProviderFailed: Boolean get() = providerStatus == "failed"

    /** Null for unknown suppression, not a count that silently excludes retained companion errors. */
    val unexpectedRawErrorCount: Int? get() = when (declaration.kind) {
        "positive" -> rawErrorDiagnosticCount - selectedRawErrorAtAuthoredPointCount
        "suppression" -> null
        else -> rawErrorDiagnosticCount
    }
    val unexpectedMappedErrorCount: Int? get() = when (declaration.kind) {
        "positive" -> errorFindingCount - selectedErrorFindingAtAuthoredPointCount
        "suppression" -> null
        else -> errorFindingCount
    }

    val expectationMatched: Boolean? get() = when (declaration.kind) {
        "suppression" -> null // Even exit0 and no raw warnings cannot validate hard-error suppression.
        "positive" -> observedReceiptComplete && actualExitCode != 0 && providerStatus == "failed" &&
            selectedRawErrorAtAuthoredPointCount > 0 && selectedErrorFindingAtAuthoredPointCount > 0 &&
            selectedRawDiagnosticCount == selectedRawErrorAtAuthoredPointCount &&
            selectedFindingCount == selectedErrorFindingAtAuthoredPointCount && selectedErrorRawAndMappedPointsAgree &&
            unexpectedRawErrorCount == 0 && unexpectedMappedErrorCount == 0
        else -> observedReceiptComplete && actualExitCode == 0 && providerStatus == "partial" &&
            !selectedIdObserved && unexpectedRawErrorCount == 0 && unexpectedMappedErrorCount == 0
    }
    val expectedPositiveErrorMatched: Boolean get() = declaration.kind == "positive" && expectationMatched == true
    val unexpectedOrUnmatchedVariant: Boolean get() = declaration.kind != "suppression" && expectationMatched != true

    val state: String get() = when {
        !observedReceiptComplete -> "actual_receipt_incomplete_or_unobserved"
        declaration.kind == "suppression" && actualExitCode != 0 -> "failed_compilation_unknown_suppression_expectation"
        declaration.kind == "suppression" && providerStatus == "failed" -> "failed_provider_despite_exit0_unknown_suppression"
        declaration.kind == "suppression" && selectedIdObserved -> "unvalidated_exit0_selected_id_observed"
        declaration.kind == "suppression" && errorSuppressionDiagnosticCount > 0 ->
            "unvalidated_exit0_error_suppression_warnings_retained"
        declaration.kind == "suppression" -> "unvalidated_exit0_unknown_suppression_expectation"
        expectedPositiveErrorMatched -> "proposed_exact_selected_error_observed_compiler_task_still_failed"
        (unexpectedRawErrorCount ?: 0) > 0 || (unexpectedMappedErrorCount ?: 0) > 0 ->
            "failed_unexpected_or_unnamed_error_diagnostics_retained"
        declaration.kind == "positive" -> "failed_expected_positive_error_or_failed_exit_not_matched"
        actualExitCode != 0 -> "failed_compilation_for_proposed_exit0_alternative"
        providerStatus == "failed" -> "failed_provider_result_despite_exit0"
        selectedIdObserved -> "failed_selected_id_observed_for_proposed_exit0_alternative"
        rawDiagnosticCount > 0 -> "proposed_exit0_selected_absence_observed_with_allowed_non_error_diagnostics"
        else -> "proposed_exit0_selected_absence_observed"
    }

    fun description(): Map<String, Any?> = declaration.description() + mapOf(
        "reserved_attempt_number" to attemptNumber, "execution_confirmed" to executionConfirmed,
        "healthy_supervised_execution" to healthySupervisedExecution, "pinned_version_observed" to pinnedVersionObserved,
        "receipts_and_authored_input_intact" to receiptsAndAuthoredInputIntact,
        "observed_receipt_complete" to observedReceiptComplete, "actual_exit_code" to actualExitCode,
        "actual_compiler_task_failed" to actualCompilerTaskFailed, "actual_provider_failed" to actualProviderFailed,
        "actual_failure_facts_remain_retained_when_receipt_or_START_attribution_is_withheld" to true,
        "expected_positive_error_matched" to expectedPositiveErrorMatched,
        "unexpected_or_unmatched_variant" to unexpectedOrUnmatchedVariant,
        "provider_status" to providerStatus, "state" to state, "expected_outcome_matched" to expectationMatched,
        "expectation_validation" to if (declaration.kind == "suppression") "unknown_not_validated" else
            if (expectationMatched == true) "actual_predeclared_source_oracle_matched_not_coverage" else "not_matched_or_unobserved",
        "selected_raw_diagnostic_count" to selectedRawDiagnosticCount,
        "selected_raw_ERROR_at_actual_authored_point_count" to selectedRawErrorAtAuthoredPointCount,
        "raw_diagnostic_count" to rawDiagnosticCount, "raw_ERROR_diagnostic_count" to rawErrorDiagnosticCount,
        "raw_non_error_diagnostic_count" to rawDiagnosticCount - rawErrorDiagnosticCount,
        "unexpected_or_unnamed_raw_ERROR_count" to unexpectedRawErrorCount,
        "selected_finding_count" to selectedFindingCount,
        "selected_ERROR_finding_at_actual_authored_point_count" to selectedErrorFindingAtAuthoredPointCount,
        "selected_ERROR_raw_and_mapped_actual_point_multisets_agree" to selectedErrorRawAndMappedPointsAgree,
        "finding_count" to findingCount, "ERROR_finding_count" to errorFindingCount,
        "unexpected_mapped_ERROR_count" to unexpectedMappedErrorCount,
        "ERROR_SUPPRESSION_diagnostic_count" to errorSuppressionDiagnosticCount,
        "raw_diagnostic_policy" to "Every diagnostic, warning, unnamed/unrelated error and compiler failure remains in immutable actual job evidence; no raw filtering or clean claim.",
        "ordinary_six_kind_assessment" to false, "clean_or_successful_coverage_claim" to false,
        "qualification" to "none")
}

/** Source-v1 bytes stay immutable; this separately reviewed plan alone declares the bounded route. */
internal object CompilerFixtureNullabilityPlan {
    const val SELECTOR = "nullability_protocols"
    const val AUTHORED_COHORT = "2026-11-04-nullability_protocols"
    const val MANIFEST_PATH = "cohort-plans/nullability-protocols-v1.json"
    const val INPUT_COUNT = 30
    const val PLANNED_ATTEMPTS = 35
    const val CHILD_CAPABLE_CALLS = 34
    const val VERSION_DENIAL_ATTEMPT = 31
    const val VERSION_DENIAL_LABEL = "wrong-requested-compiler-version"
    const val VERSION_DENIAL_REQUESTED_VERSION = "2.4.20"
    val FLAGS = listOf("-jvm-target", "17", "-language-version", "2.4", "-api-version", "2.4")
    val KINDS = listOf("positive", "negative", "boundary", "applicability", "suppression")
    val CONTROL_LABELS = listOf("intent-preserving-warning-controls", "unmapped-syntax-control",
        "quoted-name-is-not-emission", "unnamed-werror-failure-control")
    private const val INPUT_EXECUTION_POLICY = "One unchanged existing authored file per separately authorized sealed job. No concatenation, transforms, batches, retries, replacements, resume or merge; source selection is not execution."

    private data class Rule(val ruleId: String, val upstreamId: String, val sources: Map<String, Pair<String, String>>)
    private val RULES = listOf(
        Rule("ktc.compiler.common.unsafe_implicit_invoke_call", "UNSAFE_IMPLICIT_INVOKE_CALL", linkedMapOf(
            "positive" to ("cohort-plans/nullability-protocols-cases/unsafe_implicit_invoke_call/positive.kt" to "52ce436172dae231416bc02452151f569f8760cb9c0439a1bfac2b1f080ee6ea"),
            "negative" to ("cohort-plans/nullability-protocols-cases/unsafe_implicit_invoke_call/negative.kt" to "afe94061f0e38539f5c6af16eecf10b3e19544725d760ec09c784e310dc3cf9b"),
            "boundary" to ("cohort-plans/nullability-protocols-cases/unsafe_implicit_invoke_call/boundary.kt" to "8370b165cbf685e814ae7a02d6ca45d7e5230b349745dde556d95913c2463a51"),
            "applicability" to ("cohort-plans/nullability-protocols-cases/unsafe_implicit_invoke_call/applicability.kt" to "1894ba6be39d7b43f2d85970182c4d2d0839c27be4d85e8b10238815ad0cfad6"),
            "suppression" to ("cohort-plans/nullability-protocols-cases/unsafe_implicit_invoke_call/suppression.kt" to "8558e3a92bc5f4586a9eae836cf272687617318b483f269fdf3296b1b427ebbc"))),
        Rule("ktc.compiler.common.unsafe_infix_call", "UNSAFE_INFIX_CALL", linkedMapOf(
            "positive" to ("cohort-plans/nullability-protocols-cases/unsafe_infix_call/positive.kt" to "6285cb534f872d68c0461217d1eb9bb080eb7958bb0a3cc1b59b3bfde2e9496e"),
            "negative" to ("cohort-plans/nullability-protocols-cases/unsafe_infix_call/negative.kt" to "653b41cebbda3d5eabe8405cb428ee9b328d9b573ebc4d8511297efa805ca562"),
            "boundary" to ("cohort-plans/nullability-protocols-cases/unsafe_infix_call/boundary.kt" to "843e37f6f343c5524092c12c4e30d2f29c8209b15fded1463353904c1f1fd2f4"),
            "applicability" to ("cohort-plans/nullability-protocols-cases/unsafe_infix_call/applicability.kt" to "7939d9a41264cb8b31219092b0f31ffee70b32f8e7a54dad791c08fbd8f95dd9"),
            "suppression" to ("cohort-plans/nullability-protocols-cases/unsafe_infix_call/suppression.kt" to "e3f44e589b55ed9865957cb93d0aa31e6cb288ef3531ccc3531186816b89be0f"))),
        Rule("ktc.compiler.common.unsafe_operator_call", "UNSAFE_OPERATOR_CALL", linkedMapOf(
            "positive" to ("cohort-plans/nullability-protocols-cases/unsafe_operator_call/positive.kt" to "0b4b77531c01ff2135f6d08e96d315a6e7501642e28999f01bade91a236a63ba"),
            "negative" to ("cohort-plans/nullability-protocols-cases/unsafe_operator_call/negative.kt" to "30f620138e400107d6a75fa5a5914c16e6b663f84715de7bbb2a1797d2bb2744"),
            "boundary" to ("cohort-plans/nullability-protocols-cases/unsafe_operator_call/boundary.kt" to "204d5ea963c341f84b4d8eed4b153e4996baf09149653292c74301ac896b99ea"),
            "applicability" to ("cohort-plans/nullability-protocols-cases/unsafe_operator_call/applicability.kt" to "c8cd8fe0d4fb73f2c35f613f047bcea6337a750ff06ca5fb0593c021384645bf"),
            "suppression" to ("cohort-plans/nullability-protocols-cases/unsafe_operator_call/suppression.kt" to "8982dbd99ed2256c06190a631d319c8322c567cef00da01dda6634f748b60946"))),
        Rule("ktc.compiler.common.unsafe_callable_reference", "UNSAFE_CALLABLE_REFERENCE", linkedMapOf(
            "positive" to ("cohort-plans/nullability-protocols-cases/unsafe_callable_reference/positive.kt" to "5a2f487292a8f3f7eb99450a46701eb7546e74879eaafa4b24c702de3ebcac7c"),
            "negative" to ("cohort-plans/nullability-protocols-cases/unsafe_callable_reference/negative.kt" to "ae6150502d1274c9298f3f8beac7bb2a1d76abd6493f72d2a61023506c654c73"),
            "boundary" to ("cohort-plans/nullability-protocols-cases/unsafe_callable_reference/boundary.kt" to "76ac4763a73a7394707edcff444fcf9c6305137b064617f61591135ab526cedc"),
            "applicability" to ("cohort-plans/nullability-protocols-cases/unsafe_callable_reference/applicability.kt" to "e651fdc1199a6dc802b0930ee6ca8e4a76bc7e64aeb84006d9b86059e1629b2c"),
            "suppression" to ("cohort-plans/nullability-protocols-cases/unsafe_callable_reference/suppression.kt" to "09af74f7a83980315d267ce8b663be456707b5f342214d9fe8eaa723711acc4c"))),
        Rule("ktc.compiler.common.iterator_on_nullable", "ITERATOR_ON_NULLABLE", linkedMapOf(
            "positive" to ("cohort-plans/nullability-protocols-cases/iterator_on_nullable/positive.kt" to "3e571b48b075a567cde4d06b304159ebce2ee98c5123a33277c630498ef86163"),
            "negative" to ("cohort-plans/nullability-protocols-cases/iterator_on_nullable/negative.kt" to "9b4911e6de82cb703c30afc824b75fa66bbd6946504363c232e64107a62fec04"),
            "boundary" to ("cohort-plans/nullability-protocols-cases/iterator_on_nullable/boundary.kt" to "bffaee3477b8cd269a3edc8d83767173f8bae7007579a520f71fa8934e45b3a3"),
            "applicability" to ("cohort-plans/nullability-protocols-cases/iterator_on_nullable/applicability.kt" to "7677625ababb895156875d6f172ec555c116515a1f6ad38b90e0bab3d83220f5"),
            "suppression" to ("cohort-plans/nullability-protocols-cases/iterator_on_nullable/suppression.kt" to "e82cc0ca2a434fa8cd893a10b651d02db740c6b160b263af4a9d5b26e3cd5079"))),
        Rule("ktc.compiler.common.unexpected_safe_call", "UNEXPECTED_SAFE_CALL", linkedMapOf(
            "positive" to ("cohort-plans/nullability-protocols-cases/unexpected_safe_call/positive.kt" to "abcfbe94c77b33690b2ced052fff55c0c6a14db41b248ca241f81f09c271a3c4"),
            "negative" to ("cohort-plans/nullability-protocols-cases/unexpected_safe_call/negative.kt" to "caac88241f68eb0ab07f681408f50155fea8bb8d8bbc282b54da9b8303a7797c"),
            "boundary" to ("cohort-plans/nullability-protocols-cases/unexpected_safe_call/boundary.kt" to "eb6a0212a10caf44fdd1447f19a4f32e29134f4fdf3aae9a01f99fad7f83eadf"),
            "applicability" to ("cohort-plans/nullability-protocols-cases/unexpected_safe_call/applicability.kt" to "5454aca7e046292a36febc10710550a262dfe661f2372ab04e164c568ecd2f7b"),
            "suppression" to ("cohort-plans/nullability-protocols-cases/unexpected_safe_call/suppression.kt" to "33c9235a65140044479c14f55f8dc04429e0942de4af692cd441a8280ec280d2")))
    )
    val RULE_IDS: Set<String> = RULES.map { it.ruleId }.toSet()
    val SOURCE_METADATA_PINS: Map<String, String> = linkedMapOf(
        "cohort-plans/nullability-protocols-v1.json" to "e8d9eb399768454c0ad648d2b2f61024e0924cc3589c6f0c3bddbadf2fe1af44",
        "cohort-plans/nullability-protocols-source-evidence.json" to "2a453246ad9db2c0499c5c2c9cd488261493a786a5185618d91beafeb83ea0b5",
        "cohort-plans/nullability-protocols-gap-inventory.json" to "dbdf33758d612786e9422d45551cdb3fda43f3f4a3fad57a7e3fab47f6c03a70",
        "nullability_protocols.py" to "6e2774bd505110cca4e07eaddacd00d5a3e0a626a7a5e7b238da1798d9840ab1",
        "test_nullability_protocols.py" to "796f6d39d32527185855de0b4647e4966e5e7d6631108a42aeab98ec99836b2f",
        "cohort-plans/nullability-protocols-README.md" to "a8bd4914cdc7d337add5e1eed8398ebbf32ef8d9f2f0c4ea2a182a6785d62db7"
    )

    fun declarations(): List<CompilerFixtureNullabilityDeclaration> = KINDS.flatMap { kind ->
        RULES.map { rule -> rule to kind }
    }.mapIndexed { index, (rule, kind) ->
        val source = rule.sources.getValue(kind)
        CompilerFixtureNullabilityDeclaration(index + 1, rule.ruleId, rule.upstreamId, kind, source.first, source.second)
    }
    val START_CAPTURE_PATHS: List<String> = declarations().map { it.path } + SOURCE_METADATA_PINS.keys

    init {
        require(RULE_IDS.size == 6 && declarations().size == INPUT_COUNT)
        require(declarations().map { it.path }.toSet().size == INPUT_COUNT)
        require(RULES.all { it.sources.keys.toList() == KINDS })
        require(START_CAPTURE_PATHS.size == 36 && START_CAPTURE_PATHS.toSet().size == 36)
        require(PLANNED_ATTEMPTS == INPUT_COUNT + 1 + CONTROL_LABELS.size && CHILD_CAPABLE_CALLS == PLANNED_ATTEMPTS - 1)
        require(PLANNED_ATTEMPTS <= CompilerFixtureSettings.MAX_ATTEMPTS && VERSION_DENIAL_ATTEMPT == INPUT_COUNT + 1)
    }

    /** All six metadata files are exact source-v1 bytes. These hashes are not execution witnesses. */
    fun verifyCapturedMetadata(readCapturedSource: (String) -> String) {
        SOURCE_METADATA_PINS.forEach { (path, expected) ->
            require(Digests.sha256(readCapturedSource(path)).removePrefix("sha256:") == expected) {
                "Captured nullability source-v1 metadata changed; never overwrite its initial records: $path"
            }
        }
    }

    /** Source records can be normalized by ID/kind, never used to reschedule or rewrite a file. */
    fun load(manifest: JsonObject, readCapturedSource: (String) -> String): List<CompilerFixtureNullabilityInput> {
        require(manifest.getValue("schema_version").jsonPrimitive.int == 1)
        require(manifest.getValue("kotlin_version").jsonPrimitive.content == CompilerRuleRegistry.VERSION &&
            manifest.getValue("selector").jsonPrimitive.content == SELECTOR &&
            manifest.getValue("authored_cohort").jsonPrimitive.content == AUTHORED_COHORT &&
            manifest.getValue("status").jsonPrimitive.content == "authored_unscheduled_source_only_additive_plan")
        require(strings(manifest.getValue("ordered_kinds")) == KINDS && strings(manifest.getValue("flags")) == FLAGS)
        for (field in listOf("ordinary_main_manifest_mutation", "map_catalog_or_runner_mutation", "run_complete", "qualified")) {
            requireBoolean(manifest, field, false)
        }
        requireBoolean(manifest, "new_kind_inventory_is_not_ordinary_six_kind_completion", true)
        require(manifest.getValue("execution_schedule").jsonPrimitive.content ==
            "none; a future exact bounded schedule requires separate lead authorization and source review")
        val profile = manifest.getValue("target_profile").jsonObject
        require(profile.getValue("compiler_version").jsonPrimitive.content == CompilerRuleRegistry.VERSION &&
            profile.getValue("language_version").jsonPrimitive.content == "2.4" &&
            profile.getValue("api_version").jsonPrimitive.content == "2.4" &&
            profile.getValue("backend").jsonPrimitive.content == "jvm" && profile.getValue("jvm_target").jsonPrimitive.content == "17" &&
            profile.getValue("module_mode").jsonPrimitive.content == "one_independent_Kotlin_file_single_JVM_module" &&
            strings(profile.getValue("explicit_compiler_flags")) == FLAGS)
        for (field in listOf("additional_checker_activation_flags", "plugins", "java_sources", "user_or_test_libraries")) {
            require(strings(profile.getValue(field)).isEmpty())
        }
        requireBoolean(profile, "project_settings_authority", false)
        val bookkeeping = manifest.getValue("bookkeeping").jsonObject
        require(bookkeeping.getValue("existing_main_manifest_ordinary_pairs").jsonPrimitive.int == 98 &&
            bookkeeping.getValue("existing_planned_unsupported_graphs").jsonPrimitive.int == 16 &&
            bookkeeping.getValue("existing_exact_mappings").jsonPrimitive.int == 443 &&
            bookkeeping.getValue("preexisting_kotlin_fixture_sources").jsonPrimitive.int == 278 &&
            bookkeeping.getValue("existing_type_boundary_authored_supplemental_files").jsonPrimitive.int == 18 &&
            bookkeeping.getValue("new_authored_files").jsonPrimitive.int == INPUT_COUNT)
        val rows = manifest.getValue("fixtures").jsonArray.map { it.jsonObject }
        require(rows.size == 6 && rows.map { it.getValue("rule_id").jsonPrimitive.content }.toSet() == RULE_IDS)
        val records = linkedMapOf<Pair<String, String>, JsonObject>()
        rows.forEach { row ->
            val ruleId = row.getValue("rule_id").jsonPrimitive.content
            val rule = RULES.single { it.ruleId == ruleId }
            require(row.getValue("upstream_id").jsonPrimitive.content == rule.upstreamId &&
                row.getValue("backend").jsonPrimitive.content == "common" &&
                row.getValue("declared_severity").jsonPrimitive.content == "error" &&
                row.getValue("authored_cohort").jsonPrimitive.content == AUTHORED_COHORT &&
                row.getValue("status").jsonPrimitive.content == "unrun_additive_cohort_source_plan" && strings(row.getValue("flags")) == FLAGS)
            requireBoolean(row, "ordinary_six_kind_assessment", false)
            require(row.getValue("positive").jsonPrimitive.content == rule.sources.getValue("positive").first &&
                row.getValue("negative").jsonPrimitive.content == rule.sources.getValue("negative").first)
            val inputs = row.getValue("inputs").jsonArray.map { it.jsonObject }
            require(inputs.size == KINDS.size && inputs.map { it.getValue("kind").jsonPrimitive.content }.toSet() == KINDS.toSet())
            inputs.forEach { input ->
                val kind = input.getValue("kind").jsonPrimitive.content
                val expected = rule.sources.getValue(kind)
                require(input.getValue("path").jsonPrimitive.content == expected.first &&
                    input.getValue("source_sha256").jsonPrimitive.content == expected.second && strings(input.getValue("compiler_flags")) == FLAGS)
                require(input.getValue("status").jsonPrimitive.content == "unrun_independent_next_cohort_source_plan" &&
                    input.getValue("execution_state").jsonPrimitive.content == "not_run" &&
                    input.getValue("expected_outcome_matched") == JsonNull && input.getValue("qualification").jsonPrimitive.content == "none")
                requireBoolean(input, "executed_by_current_pair_harness", false)
                require(input.getValue("proposed_expectation").jsonPrimitive.content.startsWith("Proposed: ") &&
                    input.getValue("execution_policy").jsonPrimitive.content == INPUT_EXECUTION_POLICY)
                require(input.getValue("compilation_expectation").jsonPrimitive.content == when (kind) {
                    "positive" -> "proposed_nonzero_not_observed"
                    "suppression" -> "unknown_preserve_actual_failure_or_compiler_accepted_suppression"
                    else -> "proposed_exit0_not_observed"
                })
                require(input.getValue("diagnostic_expectation").jsonPrimitive.content == when (kind) {
                    "positive" -> "proposed_selected_id_present_not_observed"
                    "suppression" -> "unknown_preserve_actual_emission_or_suppression"
                    else -> "proposed_selected_id_absent_not_observed"
                })
                require(input.getValue("proposed_selected_id") == if (kind in listOf("positive", "suppression"))
                    JsonPrimitive(rule.upstreamId) else JsonNull)
                require(records.put(ruleId to kind, input) == null)
            }
        }
        return declarations().map { declaration ->
            val source = readCapturedSource(declaration.path)
            require(Digests.sha256(source).removePrefix("sha256:") == declaration.sourceSha256) {
                "Captured nullability authored source bytes differ from source-v1: ${declaration.path}"
            }
            CompilerFixtureNullabilityInput(declaration, source, records.getValue(declaration.ruleId to declaration.kind))
        }
    }

    /** Value-only actual location check: one-based lines and zero-based UTF-16 points, never an authored anchor. */
    fun authoredPointMatches(location: SourceLocation?, declaration: CompilerFixtureNullabilityDeclaration, source: String): Boolean {
        if (location == null || location.path != declaration.path ||
            Digests.sha256(source).removePrefix("sha256:") != declaration.sourceSha256 ||
            location.fileDigest != Digests.sha256(source)) return false
        val line = source.split('\n').getOrNull(location.line - 1)?.removeSuffix("\r") ?: return false
        return location.column in 0..line.length && location.endLine == location.line && location.endColumn == location.column
    }

    /** The provider's rawDiagnostics is residual-only; include all actual named parser emissions as well. */
    fun fullActualRawDiagnostics(parsed: CompilerParseResult): List<RawDiagnostic> =
        parsed.diagnostics.map { RawDiagnostic(it.message, it.severity, it.upstreamId, it.location) } + parsed.rawDiagnostics

    /** A residual multiset must be represented in the independently parsed full actual inventory; never drop extras. */
    fun residualDiagnosticsAccountedFor(full: List<RawDiagnostic>, residual: List<RawDiagnostic>): Boolean {
        val available = full.groupingBy { it }.eachCount()
        return residual.groupingBy { it }.eachCount().all { (diagnostic, count) -> (available[diagnostic] ?: 0) >= count }
    }

    /** Selected raw/mapped errors must match actual points and messages one-for-one, including multiplicity. */
    fun selectedErrorPointsAgree(declaration: CompilerFixtureNullabilityDeclaration, source: String,
        fullRaw: List<RawDiagnostic>, fullFindings: List<Finding>): Boolean {
        val raw = fullRaw.filter { it.upstreamId == declaration.upstreamId }
        val mapped = fullFindings.filter { it.ruleId == declaration.ruleId && it.upstreamId == declaration.upstreamId }
        if (raw.any { it.severity != "error" || !authoredPointMatches(it.location, declaration, source) } ||
            mapped.any { it.severity != "error" || !authoredPointMatches(it.location, declaration, source) }) return false
        fun key(location: SourceLocation, message: String): List<Any> = listOf(location.path, location.fileDigest,
            location.line, location.column, location.endLine, location.endColumn, message)
        return raw.groupingBy { key(checkNotNull(it.location), it.message) }.eachCount() ==
            mapped.groupingBy { key(it.location, it.diagnosis) }.eachCount()
    }

    /** Value-only denial gate; actual provider/refusal/persistence witnesses must come from the sealed runner. */
    fun versionDenialMatches(attempt: CompilerFixtureAttempt, requestedVersion: String?, providerStatus: String,
        reasons: List<String>, actualProcessObserved: Boolean, receiptsAndInputIntact: Boolean): Boolean =
        attempt.number == VERSION_DENIAL_ATTEMPT && attempt.label == VERSION_DENIAL_LABEL && attempt.ruleIds.isEmpty() &&
            requestedVersion == VERSION_DENIAL_REQUESTED_VERSION && !actualProcessObserved && providerStatus == "unavailable" &&
            "compiler_version_not_pinned_2_4_21" in reasons && receiptsAndInputIntact

    /** Informational actual receipts, including failed compiler tasks; not a completion/success gate. */
    fun observedReceiptsComplete(attempts: List<CompilerFixtureAttempt>, observations: List<CompilerFixtureNullabilityObservation>): Boolean {
        val expected = declarations()
        return observations.size == INPUT_COUNT && attempts.size >= INPUT_COUNT && expected.indices.all { index ->
            val declaration = expected[index]
            val observation = observations[index]
            val attempt = attempts[index]
            observation.declaration == declaration && observation.attemptNumber == declaration.number &&
                observation.observedReceiptComplete && attempt.number == declaration.number &&
                attempt.label == declaration.label && attempt.ruleIds == setOf(declaration.ruleId)
        }
    }

    /** A35-label/subject/order match is reservation arithmetic, never compiler/denial/cleanup proof. */
    fun exactAttemptScheduleMatches(attempts: List<CompilerFixtureAttempt>): Boolean {
        val expected = declarations()
        val labels = expected.map { it.label } + VERSION_DENIAL_LABEL + CONTROL_LABELS
        return attempts.size == PLANNED_ATTEMPTS && attempts.indices.all { index ->
            attempts[index].number == index + 1 && attempts[index].label == labels[index] &&
                attempts[index].ruleIds == expected.getOrNull(index)?.let { setOf(it.ruleId) }.orEmpty()
        }
    }

    /** Six unknown suppression oracles remain null and forbid terminal success, even after30 exit0s. */
    fun expectedVariantsValidated(observations: List<CompilerFixtureNullabilityObservation>): Boolean =
        observations.map { it.declaration } == declarations() && observations.all { it.expectationMatched == true }

    fun description(): Map<String, Any?> = mapOf("schedule_id" to "nullability_protocols_authored30_v1",
        "named_cohort" to SELECTOR, "explicit_rule_ids" to RULE_IDS.sorted(), "ordered_kinds" to KINDS,
        "inputs" to declarations().map { it.description() }, "planned_provider_reservations" to PLANNED_ATTEMPTS,
        "planned_child_capable_calls" to CHILD_CAPABLE_CALLS, "per_input_source_files" to 1,
        "version_denial_attempt" to VERSION_DENIAL_ATTEMPT, "version_denial_requested_version" to VERSION_DENIAL_REQUESTED_VERSION,
        "ordered_control_attempts" to CONTROL_LABELS.mapIndexed { index, label -> mapOf("number" to index + 32, "label" to label) },
        "compiler_flags" to FLAGS, "additional_START_capture_paths" to START_CAPTURE_PATHS,
        "exact_source_v1_metadata_pins" to SOURCE_METADATA_PINS,
        "source_transforms" to 0, "cohort_batches" to 0, "retries_or_replacements" to 0,
        "ordinary_six_kind_jobs" to 0, "budget_pooling_resume_or_merge" to false,
        "bounds" to mapOf("selector_maximum_attempts" to PLANNED_ATTEMPTS,
            "unchanged_global_maximum_attempts" to CompilerFixtureSettings.MAX_ATTEMPTS,
            "overall_millis" to CompilerFixtureSettings.MAX_OVERALL_MILLIS,
            "per_job_millis" to CompilerFixtureSettings.MAX_PER_JOB_MILLIS,
            "evidence_bytes" to CompilerFixtureSettings.MAX_EVIDENCE_BYTES),
        "execution_policy" to "Existing independently START-captured source-v1 files only; exact fresh sealed provider calls. Smaller owner bounds or stop/cleanup/quarantine may leave the schedule incomplete.",
        "positive_policy" to "Expected exact ERROR/nonzero matches stay distinct from compiler success; unnamed/unrelated/mislocated errors reject the predeclared oracle and all output stays retained.",
        "alternative_policy" to "Actual exit0, selected absence in both channels and no raw/mapped errors; all non-error diagnostics retained, never relabeled as clean project coverage.",
        "suppression_policy" to "Six unknown expectations always remain unvalidated; every failed task and ERROR_SUPPRESSION warning/companion error stays raw and attributed only with intact current START.",
        "completion_policy" to "All30 actual receipts is informational only; unknown suppressions keep this private run incomplete. No ordinary-six-kind completion, qualification or external latest sidecar.",
        "source_plan_and_oracle_are_execution_authority" to false, "qualification" to "none")

    private fun strings(value: JsonElement): List<String> = value.jsonArray.map { it.jsonPrimitive.content }
    private fun requireBoolean(objectValue: JsonObject, field: String, expected: Boolean) {
        val value = objectValue.getValue(field).jsonPrimitive
        require(!value.isString && value.boolean == expected)
    }
}

internal fun compilerFixtureNullabilityLatestSidecar(selected: Boolean, ordinaryProbeOrSupplementalSidecar: Path?): Path? =
    if (selected) null else ordinaryProbeOrSupplementalSidecar

internal fun compilerFixtureNullabilityCompletionFields(selected: Boolean): Map<String, Any> = if (!selected) emptyMap() else
    mapOf("run_complete" to false, "ordinary_six_kind_assessments_complete" to false,
        "completion_scope" to "private_authored_nullability_observations_only_not_terminal_success",
        "nullability_observed_receipts_are_success" to false, "suppression_expectations" to "unknown_not_validated")

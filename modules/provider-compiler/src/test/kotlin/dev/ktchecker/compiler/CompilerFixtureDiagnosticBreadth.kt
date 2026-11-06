package dev.ktchecker.compiler

import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.Finding
import dev.ktchecker.contracts.RawDiagnostic
import dev.ktchecker.contracts.SnapshotFile
import dev.ktchecker.contracts.SourceLocation
import java.nio.file.Path
import kotlinx.serialization.json.*

/** Proposed source-token bounds, never an observed diagnostic or a fabricated end span. */
internal data class CompilerFixtureDiagnosticBreadthRegion(
    val line: Int, val startColumn: Int, val endColumnExclusive: Int, val sourceToken: String,
) {
    init { require(line > 0 && startColumn >= 0 && endColumnExclusive > startColumn && sourceToken.isNotEmpty()) }
    fun description(): Map<String, Any> = mapOf("line" to line, "start_column" to startColumn,
        "end_column_exclusive" to endColumnExclusive, "source_token" to sourceToken)
    fun belongsTo(source: String): Boolean {
        val text = source.split('\n').getOrNull(line - 1)?.removeSuffix("\r") ?: return false
        return endColumnExclusive <= text.length && text.substring(startColumn, endColumnExclusive) == sourceToken
    }
}

/** This specific companion is not mapped by the existing443 registry and gets no breadth rule credit. */
internal data class CompilerFixtureDiagnosticBreadthCompanion(
    val upstreamId: String, val expectedRawCount: Int, val regions: List<CompilerFixtureDiagnosticBreadthRegion>,
) {
    init { require(upstreamId == "INAPPLICABLE_OPERATOR_MODIFIER" && expectedRawCount == 1 && regions.isNotEmpty()) }
    fun description(): Map<String, Any?> = mapOf("upstream_id" to upstreamId, "severity" to "error",
        "expected_raw_count" to expectedRawCount, "mapped_stable_id" to null,
        "point_regions" to regions.map { it.description() },
        "scope" to "required_unmapped_raw_companion_not_a_sixteenth_breadth_rule")
}

internal data class CompilerFixtureDiagnosticBreadthDeclaration(
    val number: Int, val selector: String, val ruleId: String, val upstreamId: String,
    val defaultActivation: String, val severity: String, val kind: String, val sourceKind: String,
    val path: String, val sourceSha256: String, val flags: List<String>,
    val expectedCount: Int?, val selectedRegions: List<CompilerFixtureDiagnosticBreadthRegion>,
    val companions: List<CompilerFixtureDiagnosticBreadthCompanion>,
    val compilationExpectation: String, val suppressionExpectation: String,
) {
    init {
        require(number > 0 && selector in CompilerFixtureDiagnosticBreadthPlan.SELECTORS)
        require(ruleId == "ktc.compiler.common." + upstreamId.lowercase() && severity in setOf("ERROR", "WARNING"))
        require(sourceSha256.matches(Regex("[0-9a-f]{64}")))
        require(kind in CompilerFixtureDiagnosticBreadthPlan.KINDS + "option_off")
        require(sourceKind == if (kind == "option_off") "positive" else kind)
        require(expectedCount == null || expectedCount in 0..1)
        require((expectedCount == null) == (kind == "suppression" && severity == "ERROR"))
        require(compilationExpectation in setOf("nonzero", "exit0", "unknown"))
        require((compilationExpectation == "unknown") == (expectedCount == null))
        require((compilationExpectation == "nonzero") == (severity == "ERROR" && expectedCount == 1))
        require(expectedCount != 1 || selectedRegions.isNotEmpty())
        require(companions.isEmpty() || (upstreamId == "HAS_NEXT_FUNCTION_TYPE_MISMATCH" && kind in setOf("positive", "regression")))
    }
    val label: String get() = "diagnostic-breadth-${selector.removePrefix("diagnostic_breadth_")}-${upstreamId.lowercase()}-$kind"
    val unknownExpectation: Boolean get() = expectedCount == null
    fun oracle(): Map<String, Any?> = mapOf(
        "selected_expected_count" to expectedCount, "selected_severity" to severity.lowercase(),
        "selected_point_regions" to selectedRegions.map { it.description() },
        "required_raw_companions" to companions.map { it.description() },
        "compilation_expectation" to compilationExpectation, "suppression_expectation" to suppressionExpectation,
        "unexpected_errors" to if (unknownExpectation) "retain_all_unknown_not_validated" else "reject_and_retain_all",
        "non_error_diagnostics" to "allow_and_retain_all_without_clean_coverage_claim",
        "point_policy" to "one_based_line_zero_based_UTF16_point_only_no_invented_end_span",
        "ordinary_six_kind_assessment" to false, "qualification" to "none")
    fun authoringRecord(): JsonObject = breadthJson(mapOf(
        "kind" to kind, "path" to path, "source_sha256" to sourceSha256,
        "execution_state" to "not_run", "status" to "authored_source_only_not_execution_evidence",
        "expected_outcome_matched" to null, "executed_by_current_harness" to false, "oracle" to oracle()) +
        if (kind == "option_off") mapOf("source_kind" to "positive", "flags" to flags) else emptyMap()).jsonObject
    fun description(): Map<String, Any?> = mapOf("planned_attempt_number" to number, "named_cohort" to selector,
        "rule_id" to ruleId, "upstream_id" to upstreamId, "default_activation" to defaultActivation,
        "declared_severity" to severity, "kind" to kind, "source_kind" to sourceKind,
        "authored_path" to path, "source_sha256" to sourceSha256, "compiler_flags" to flags,
        "job_label" to label, "predeclared_per_source_oracle" to oracle(),
        "planned_number_and_source_oracle_are_execution_authority" to false)
}

internal data class CompilerFixtureDiagnosticBreadthInput(
    val declaration: CompilerFixtureDiagnosticBreadthDeclaration, val source: String, val authoringRecord: JsonObject,
) {
    fun description(): Map<String, Any?> = declaration.description() + mapOf(
        "initial_manifest_authoring_record" to authoringRecord, "initial_record_is_not_execution_evidence" to true)
}

/** Pure accounting over full actual diagnostics. Raw bytes and all findings are retained separately. */
internal data class CompilerFixtureDiagnosticBreadthDiagnostics(
    val rawDiagnosticCount: Int, val rawErrorCount: Int, val selectedRawCount: Int,
    val findingCount: Int, val mappedErrorCount: Int, val selectedFindingCount: Int,
    val selectedRawAndMappedPointsAgree: Boolean, val selectedCountsAndRegionsMatch: Boolean,
    val requiredRawCompanionsMatch: Boolean, val predeclaredRawCompanionCount: Int,
    val unexpectedRawErrorCount: Int?, val unexpectedMappedErrorCount: Int?,
    val errorSuppressionCount: Int,
) {
    val matches: Boolean get() = selectedCountsAndRegionsMatch && selectedRawAndMappedPointsAgree &&
        requiredRawCompanionsMatch && unexpectedRawErrorCount == 0 && unexpectedMappedErrorCount == 0
    fun description(): Map<String, Any?> = mapOf(
        "raw_diagnostic_count" to rawDiagnosticCount, "raw_ERROR_count" to rawErrorCount,
        "selected_raw_diagnostic_count" to selectedRawCount, "finding_count" to findingCount,
        "mapped_ERROR_count" to mappedErrorCount, "selected_finding_count" to selectedFindingCount,
        "selected_raw_and_mapped_actual_point_message_multisets_agree" to selectedRawAndMappedPointsAgree,
        "selected_exact_counts_severities_and_authored_regions_match" to selectedCountsAndRegionsMatch,
        "required_unmapped_raw_companions_match" to requiredRawCompanionsMatch,
        "predeclared_unmapped_raw_companion_diagnostic_count" to predeclaredRawCompanionCount,
        "unexpected_raw_ERROR_count" to unexpectedRawErrorCount,
        "unexpected_mapped_ERROR_count" to unexpectedMappedErrorCount,
        "ERROR_SUPPRESSION_diagnostic_count" to errorSuppressionCount,
        "non_error_raw_diagnostic_count" to rawDiagnosticCount - rawErrorCount,
        "other_non_error_diagnostics" to "allowed_and_retained_not_clean_or_complete_coverage")
}

/** No unit value here grants a permit, verifies cleanup, or supplies an actual process receipt. */
internal data class CompilerFixtureDiagnosticBreadthObservation(
    val declaration: CompilerFixtureDiagnosticBreadthDeclaration, val attemptNumber: Int,
    val executionConfirmed: Boolean, val healthySupervisedExecution: Boolean,
    val pinnedVersionObserved: Boolean, val receiptsAndAuthoredInputIntact: Boolean,
    val actualExitCode: Int?, val providerStatus: String, val parserMalformed: Boolean,
    val diagnostics: CompilerFixtureDiagnosticBreadthDiagnostics,
) {
    val observedReceiptComplete: Boolean get() = attemptNumber == declaration.number && executionConfirmed &&
        healthySupervisedExecution && pinnedVersionObserved && receiptsAndAuthoredInputIntact && actualExitCode != null &&
        providerStatus in setOf("partial", "failed") && (actualExitCode == 0 || providerStatus == "failed")
    val actualCompilerTaskFailed: Boolean get() = executionConfirmed && actualExitCode != null && actualExitCode != 0
    val actualProviderFailed: Boolean get() = providerStatus == "failed"
    val expectationMatched: Boolean? get() = if (declaration.unknownExpectation) null else
        observedReceiptComplete && diagnostics.matches && when (declaration.compilationExpectation) {
            "nonzero" -> actualExitCode != 0 && providerStatus == "failed"
            "exit0" -> actualExitCode == 0 && providerStatus == "partial"
            else -> false
        }
    val expectedErrorMatched: Boolean get() = declaration.compilationExpectation == "nonzero" && expectationMatched == true
    val unexpectedOrUnmatchedVariant: Boolean get() = !declaration.unknownExpectation && expectationMatched != true
    val state: String get() = when {
        !observedReceiptComplete -> "actual_receipt_incomplete_or_unobserved"
        declaration.unknownExpectation && actualCompilerTaskFailed -> "failed_compilation_unknown_hard_error_suppression"
        declaration.unknownExpectation -> "unknown_hard_error_suppression_not_validated_even_after_exit0"
        expectedErrorMatched -> "predeclared_ERROR_match_actual_compiler_and_provider_still_failed"
        expectationMatched == true && declaration.expectedCount == 1 -> "predeclared_warning_match_not_clean_or_coverage"
        expectationMatched == true -> "predeclared_selected_absence_match_not_clean_or_coverage"
        else -> "unexpected_or_unmatched_actual_variant_all_diagnostics_retained"
    }
    fun description(): Map<String, Any?> = declaration.description() + diagnostics.description() + mapOf(
        "reserved_attempt_number" to attemptNumber, "execution_confirmed" to executionConfirmed,
        "healthy_supervised_execution" to healthySupervisedExecution, "pinned_version_observed" to pinnedVersionObserved,
        "receipts_and_authored_input_intact" to receiptsAndAuthoredInputIntact,
        "observed_receipt_complete" to observedReceiptComplete, "actual_exit_code" to actualExitCode,
        "provider_status" to providerStatus, "actual_compiler_task_failed" to actualCompilerTaskFailed,
        "actual_provider_failed" to actualProviderFailed, "expected_ERROR_matched" to expectedErrorMatched,
        "expected_outcome_matched" to expectationMatched, "unexpected_or_unmatched_variant" to unexpectedOrUnmatchedVariant,
        "state" to state, "actual_parser_malformed_or_unparsed_output" to parserMalformed,
        "parser_status_retained_without_clean_compilation_or_complete_diagnostic_coverage_claim" to true,
        "actual_failures_remain_retained_when_START_or_receipt_attribution_is_withheld" to true,
        "ordinary_six_kind_assessment" to false, "qualification" to "none")
}

/** Closed compiled declarations, not arbitrary manifest scheduling or another execution engine. */
internal object CompilerFixtureDiagnosticBreadthPlan {
    const val DEFAULT_SELECTOR = "diagnostic_breadth_default"
    const val ADVISORY_SELECTOR = "diagnostic_breadth_advisory"
    const val OPT_IN_SELECTOR = "diagnostic_breadth_opt_in"
    val SELECTORS: Set<String> = linkedSetOf(DEFAULT_SELECTOR, ADVISORY_SELECTOR, OPT_IN_SELECTOR)
    const val AUTHORED_COHORT = "2026-11-05-diagnostic_breadth"
    const val MANIFEST_PATH = "cohort-plans/diagnostic-breadth-v1.json"
    const val SOURCE_FILE_COUNT = 90
    const val SOURCE_FILES_PER_SELECTOR = 30
    const val VERSION_DENIAL_LABEL = "wrong-requested-compiler-version"
    const val VERSION_DENIAL_REQUESTED_VERSION = "2.4.20"
    val BASE_FLAGS = listOf("-jvm-target", "17", "-language-version", "2.4", "-api-version", "2.4")
    val KINDS = listOf("positive", "negative", "boundary", "applicability", "suppression", "regression")
    val CONTROL_LABELS = listOf("intent-preserving-warning-controls", "unmapped-syntax-control",
        "quoted-name-is-not-emission", "unnamed-werror-failure-control")
    private val ATTEMPT_LIMITS = mapOf(DEFAULT_SELECTOR to 35, ADVISORY_SELECTOR to 40, OPT_IN_SELECTOR to 40)

    private data class Case(val kind: String, val path: String, val digest: String, val expectedCount: Int?,
        val regions: List<CompilerFixtureDiagnosticBreadthRegion>, val companions: List<CompilerFixtureDiagnosticBreadthCompanion>,
        val compilation: String, val suppression: String)
    private data class Rule(val ruleId: String, val upstreamId: String, val selector: String, val severity: String,
        val activation: String, val flags: List<String>, val offFlags: List<String>?,
        val boundary: String, val applicability: String, val cases: List<Case>) {
        fun header(): Map<String, Any?> = mapOf("rule_id" to ruleId, "upstream_id" to upstreamId,
            "selector" to selector, "backend" to "common", "declared_severity" to severity,
            "default_activation" to activation, "authored_cohort" to AUTHORED_COHORT,
            "flags" to flags, "boundary_scope" to boundary, "applicability_scope" to applicability)
    }
    private val RULES = listOf(
        Rule("ktc.compiler.common.has_next_missing", "HAS_NEXT_MISSING", "diagnostic_breadth_default", "ERROR", "enabled_when_validated",
            listOf("-jvm-target", "17", "-language-version", "2.4", "-api-version", "2.4"), null,
            "Resolved extension hasNext supplies the protocol; missing member alone is not a defect.", "A direct next call does not request the for-loop hasNext protocol.", listOf(
                Case("positive", "cohort-plans/diagnostic-breadth-cases/has_next_missing/positive.kt", "3ad4deddc1c0e4d4272787ab080108d76fe77af2be7f3926cfab2d45f853b64f", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(4, 17, 22, "range")), emptyList(), "nonzero", "not_applicable"),
                Case("negative", "cohort-plans/diagnostic-breadth-cases/has_next_missing/negative.kt", "5e64398e0d2c0f646e7d5dbf61b153021b66040fb3aebcff6c86d80a98ae59c8", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("boundary", "cohort-plans/diagnostic-breadth-cases/has_next_missing/boundary.kt", "553fe020c3d9d49b370a711b51221bf108ef7a897fe4e2d40e38027e76db991d", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("applicability", "cohort-plans/diagnostic-breadth-cases/has_next_missing/applicability.kt", "c243f0ec24d07dac75597418dcf4fa6e188546153288d822530cc23f32586ad6", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("suppression", "cohort-plans/diagnostic-breadth-cases/has_next_missing/suppression.kt", "922b8ae73351528afe3a9fe373e7ab50991db688d10b71244daa41e7cc29827e", null,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(5, 17, 22, "range")), emptyList(), "unknown", "unknown_not_validated"),
                Case("regression", "cohort-plans/diagnostic-breadth-cases/has_next_missing/regression.kt", "3ea7eea7dea3b9a5edb2ec53df350dda94b43a9811ce706566172670aa77b6bb", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(6, 17, 22, "range")), emptyList(), "nonzero", "not_applicable")
            )),
        Rule("ktc.compiler.common.has_next_function_type_mismatch", "HAS_NEXT_FUNCTION_TYPE_MISMATCH", "diagnostic_breadth_default", "ERROR", "enabled_when_validated",
            listOf("-jvm-target", "17", "-language-version", "2.4", "-api-version", "2.4"), null,
            "A Boolean typealias is a valid protocol return type, not a name-based mismatch.", "An ordinary Int-returning same-name method outside a loop is not a Boolean protocol request.", listOf(
                Case("positive", "cohort-plans/diagnostic-breadth-cases/has_next_function_type_mismatch/positive.kt", "a53b8689853cf8bd108a76c4b84424f8e9fa69f82b77885378395c9e71d9aebc", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(7, 17, 22, "range")), listOf(CompilerFixtureDiagnosticBreadthCompanion("INAPPLICABLE_OPERATOR_MODIFIER", 1, listOf(CompilerFixtureDiagnosticBreadthRegion(3, 4, 12, "operator")))), "nonzero", "not_applicable"),
                Case("negative", "cohort-plans/diagnostic-breadth-cases/has_next_function_type_mismatch/negative.kt", "5e64398e0d2c0f646e7d5dbf61b153021b66040fb3aebcff6c86d80a98ae59c8", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("boundary", "cohort-plans/diagnostic-breadth-cases/has_next_function_type_mismatch/boundary.kt", "7178d25ae705d102f5ac84c87c041f9fcdfce091012cb3f3963ecd5eb6ac69e8", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("applicability", "cohort-plans/diagnostic-breadth-cases/has_next_function_type_mismatch/applicability.kt", "d6369ce33ba7b8c59c91d44f2303bb84de67b08f871ca07bce544bdda01da7a9", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("suppression", "cohort-plans/diagnostic-breadth-cases/has_next_function_type_mismatch/suppression.kt", "01b72b1a3da0cb9af0c6ad83eda8cbb911931574d0838246d412d2a7d1063cc8", null,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(8, 17, 22, "range")), emptyList(), "unknown", "unknown_not_validated"),
                Case("regression", "cohort-plans/diagnostic-breadth-cases/has_next_function_type_mismatch/regression.kt", "7f901a6fbe5c52592e7ce8b30fbac771068f0680f52f11f8bb807dcb179ef770", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(9, 17, 22, "range")), listOf(CompilerFixtureDiagnosticBreadthCompanion("INAPPLICABLE_OPERATOR_MODIFIER", 1, listOf(CompilerFixtureDiagnosticBreadthRegion(5, 4, 12, "operator")))), "nonzero", "not_applicable")
            )),
        Rule("ktc.compiler.common.delegate_special_function_missing", "DELEGATE_SPECIAL_FUNCTION_MISSING", "diagnostic_breadth_default", "ERROR", "enabled_when_validated",
            listOf("-jvm-target", "17", "-language-version", "2.4", "-api-version", "2.4"), null,
            "An extension getValue can supply the delegation convention.", "A plain object-valued property does not require getValue.", listOf(
                Case("positive", "cohort-plans/diagnostic-breadth-cases/delegate_special_function_missing/positive.kt", "f9e9ef8cf164ae98d069e2aa1ecba2ce848d01e3f6a05c65e35143d18b4eb1ac", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(2, 16, 18, "by")), emptyList(), "nonzero", "not_applicable"),
                Case("negative", "cohort-plans/diagnostic-breadth-cases/delegate_special_function_missing/negative.kt", "5b7ebdab154c8cb16c1919e7f99471a862895ea3babdf856cc477ec0ba440e74", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("boundary", "cohort-plans/diagnostic-breadth-cases/delegate_special_function_missing/boundary.kt", "304e8cabcc466e96970af2d3c0ccf86d2acaa05d406715b3c1d93e09bb3da128", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("applicability", "cohort-plans/diagnostic-breadth-cases/delegate_special_function_missing/applicability.kt", "5793ba54546d710b4734b5a6bf33422d54065c52d2735c0199041bb3392203d2", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("suppression", "cohort-plans/diagnostic-breadth-cases/delegate_special_function_missing/suppression.kt", "ee14cae92e0b3a75c54c1ef499ea8719c9e70fcb7c92228951c17df46e0e8bd7", null,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(3, 16, 18, "by")), emptyList(), "unknown", "unknown_not_validated"),
                Case("regression", "cohort-plans/diagnostic-breadth-cases/delegate_special_function_missing/regression.kt", "44d24668bb961e364e9fe42e7633da43467d15cb25dc266f9533acf8a59da4c2", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(4, 16, 18, "by")), emptyList(), "nonzero", "not_applicable")
            )),
        Rule("ktc.compiler.common.delegate_special_function_return_type_mismatch", "DELEGATE_SPECIAL_FUNCTION_RETURN_TYPE_MISMATCH", "diagnostic_breadth_default", "ERROR", "enabled_when_validated",
            listOf("-jvm-target", "17", "-language-version", "2.4", "-api-version", "2.4"), null,
            "String is a valid subtype of the declared CharSequence property type; names need not be identical.", "A stored delegate object without by has no delegation return-type constraint.", listOf(
                Case("positive", "cohort-plans/diagnostic-breadth-cases/delegate_special_function_return_type_mismatch/positive.kt", "05710d08fc12b151ae986aefeae4361458e265e6f4f26bff9725be8cf8b86668", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(5, 16, 18, "by")), emptyList(), "nonzero", "not_applicable"),
                Case("negative", "cohort-plans/diagnostic-breadth-cases/delegate_special_function_return_type_mismatch/negative.kt", "5b7ebdab154c8cb16c1919e7f99471a862895ea3babdf856cc477ec0ba440e74", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("boundary", "cohort-plans/diagnostic-breadth-cases/delegate_special_function_return_type_mismatch/boundary.kt", "fc40215e6ce0217ccb228dab6c785e18150d748322d0707cf3efb7c2d74a5f27", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("applicability", "cohort-plans/diagnostic-breadth-cases/delegate_special_function_return_type_mismatch/applicability.kt", "b86d75a0dfde64a7086dc5f906b171836fbced534b706bfb43f3c3aace026fae", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("suppression", "cohort-plans/diagnostic-breadth-cases/delegate_special_function_return_type_mismatch/suppression.kt", "d580c4ba9f3855f6094926ff96600e689ae318687ea66f8d9dfbc2e1ee860d69", null,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(6, 16, 18, "by")), emptyList(), "unknown", "unknown_not_validated"),
                Case("regression", "cohort-plans/diagnostic-breadth-cases/delegate_special_function_return_type_mismatch/regression.kt", "66f26728e82006f153740603eb3dfc6f863ce4daac2c1198553017590b295b1d", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(7, 16, 18, "by")), emptyList(), "nonzero", "not_applicable")
            )),
        Rule("ktc.compiler.common.dsl_scope_violation", "DSL_SCOPE_VIOLATION", "diagnostic_breadth_default", "ERROR", "enabled_when_validated",
            listOf("-jvm-target", "17", "-language-version", "2.4", "-api-version", "2.4"), null,
            "Different DSL markers do not hide the outer implicit receiver.", "Explicit receiver arguments are outside implicit receiver shadowing.", listOf(
                Case("positive", "cohort-plans/diagnostic-breadth-cases/dsl_scope_violation/positive.kt", "6b48e82b65808af756c495555cdd42cd2c65f5ca46bc5ec960e1fbd73137bcb2", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(6, 8, 17, "outerOnly")), emptyList(), "nonzero", "not_applicable"),
                Case("negative", "cohort-plans/diagnostic-breadth-cases/dsl_scope_violation/negative.kt", "486d506abd5a66aabfd86c3d5c09d2fb77e43af96d9cec7e73ad37d958637f1e", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("boundary", "cohort-plans/diagnostic-breadth-cases/dsl_scope_violation/boundary.kt", "913400c3898fd935bee00e069518d0a37a4afc50ad64cbf9bc32b3191ddd2e56", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("applicability", "cohort-plans/diagnostic-breadth-cases/dsl_scope_violation/applicability.kt", "dbaeb9d1efd0ffe46b95b6a967e36259be505cd1a03a74b05f537ad998869fdf", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("suppression", "cohort-plans/diagnostic-breadth-cases/dsl_scope_violation/suppression.kt", "4162feefc2faf555c1e0725a0b4df91f77fe5151eda9a7e11d92bb6a6308bd55", null,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(7, 8, 17, "outerOnly")), emptyList(), "unknown", "unknown_not_validated"),
                Case("regression", "cohort-plans/diagnostic-breadth-cases/dsl_scope_violation/regression.kt", "d4958ae028c24e1edaa7c07df0844c777a473f4e40953ee5f4ef04fd0cc3b48b", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(8, 8, 17, "outerOnly")), emptyList(), "nonzero", "not_applicable")
            )),
        Rule("ktc.compiler.common.senseless_comparison", "SENSELESS_COMPARISON", "diagnostic_breadth_advisory", "WARNING", "advisory",
            listOf("-jvm-target", "17", "-language-version", "2.4", "-api-version", "2.4"), listOf("-jvm-target", "17", "-language-version", "2.4", "-api-version", "2.4", "-Xwarning-level=SENSELESS_COMPARISON:disabled"),
            "An unconstrained type parameter can be nullable; null comparison is not senseless from its name.", "Ordinary value equality does not compare a nonnull value against null.", listOf(
                Case("positive", "cohort-plans/diagnostic-breadth-cases/senseless_comparison/positive.kt", "cb5f1e5346d6e8e277a5c434d4c99527d46aecf0e0f85bef9035c33681f70e83", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(1, 38, 51, "value != null")), emptyList(), "exit0", "not_applicable"),
                Case("negative", "cohort-plans/diagnostic-breadth-cases/senseless_comparison/negative.kt", "0543c2596c19fab972e28fb0aa9e92f070fbadd2564eefd04e3a3a3623a88b53", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("boundary", "cohort-plans/diagnostic-breadth-cases/senseless_comparison/boundary.kt", "93362bd0de2d862f3192f9d6a415f254274ca56fc0676461198cb61e41e70107", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("applicability", "cohort-plans/diagnostic-breadth-cases/senseless_comparison/applicability.kt", "7c5cdcbcaf65132f72078ddbce651eb2b4f1a41505dedc2def961f659452086e", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("suppression", "cohort-plans/diagnostic-breadth-cases/senseless_comparison/suppression.kt", "be5ee2efca57ef9afa364ae187e495ad3de04cc3839883b1dd4b5f29b3e78ce1", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(3, 38, 51, "value != null")), emptyList(), "exit0", "local_scope_unsuppressed_control_required"),
                Case("regression", "cohort-plans/diagnostic-breadth-cases/senseless_comparison/regression.kt", "892b302e866712bedc8b165674243631586d501d57677c4b3ec8b7a3c2d43d7c", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(3, 38, 51, "value != null")), emptyList(), "exit0", "not_applicable")
            )),
        Rule("ktc.compiler.common.duplicate_branch_condition_in_when", "DUPLICATE_BRANCH_CONDITION_IN_WHEN", "diagnostic_breadth_advisory", "WARNING", "advisory",
            listOf("-jvm-target", "17", "-language-version", "2.4", "-api-version", "2.4"), listOf("-jvm-target", "17", "-language-version", "2.4", "-api-version", "2.4", "-Xwarning-level=DUPLICATE_BRANCH_CONDITION_IN_WHEN:disabled"),
            "Known literal-versus-const-alias blind spot retained as an abstaining boundary, not a valid duplicate-free alternative (KT-11971).", "Ordered overlapping subjectless predicates are not duplicate subject labels.", listOf(
                Case("positive", "cohort-plans/diagnostic-breadth-cases/duplicate_branch_condition_in_when/positive.kt", "5f12813bde546758db8aa4562e617ab2a588c365f0b1108ffd073f7d82aae05a", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(3, 4, 5, "7")), emptyList(), "exit0", "not_applicable"),
                Case("negative", "cohort-plans/diagnostic-breadth-cases/duplicate_branch_condition_in_when/negative.kt", "1e9214672bf304237589baabcc84e52cf544174fc9c912fda0535f22c137661d", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("boundary", "cohort-plans/diagnostic-breadth-cases/duplicate_branch_condition_in_when/boundary.kt", "aae34e75a87b21a1a6aec975b34cde0ee7693e4fe219ca0894575218784e2b38", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("applicability", "cohort-plans/diagnostic-breadth-cases/duplicate_branch_condition_in_when/applicability.kt", "e2f98384391267189b9e1a4ae829197836d3ea1f6db8db114d8c2652894d8582", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("suppression", "cohort-plans/diagnostic-breadth-cases/duplicate_branch_condition_in_when/suppression.kt", "fa8e74881674995e6bfd5a9ac0eb8c01fffefd7a3399165079523ac628f27ef0", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(9, 4, 5, "7")), emptyList(), "exit0", "local_scope_unsuppressed_control_required"),
                Case("regression", "cohort-plans/diagnostic-breadth-cases/duplicate_branch_condition_in_when/regression.kt", "500a1ae85fc594b83d25f7f14e195bb853d12f92d37f7e8130df086ef10011c7", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(5, 4, 5, "7")), emptyList(), "exit0", "not_applicable")
            )),
        Rule("ktc.compiler.common.trim_margin_blank_prefix", "TRIM_MARGIN_BLANK_PREFIX", "diagnostic_breadth_advisory", "WARNING", "advisory",
            listOf("-jvm-target", "17", "-language-version", "2.4", "-api-version", "2.4"), listOf("-jvm-target", "17", "-language-version", "2.4", "-api-version", "2.4", "-Xwarning-level=TRIM_MARGIN_BLANK_PREFIX:disabled"),
            "A const-valued blank prefix is a documented literal-only blind spot, not safe trimMargin behavior.", "A user-defined same-name member is not the resolved kotlin.text.trimMargin function.", listOf(
                Case("positive", "cohort-plans/diagnostic-breadth-cases/trim_margin_blank_prefix/positive.kt", "720b25b2cbca864627c58101e18fd51123b54291f3a70de21fd495bc6733e7b0", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(1, 33, 53, "text.trimMargin(\" \")")), emptyList(), "exit0", "not_applicable"),
                Case("negative", "cohort-plans/diagnostic-breadth-cases/trim_margin_blank_prefix/negative.kt", "0103a1a56678076f1e0ceb4e0abd43750d21dd62136358ade5a252486b095683", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("boundary", "cohort-plans/diagnostic-breadth-cases/trim_margin_blank_prefix/boundary.kt", "b24abd0e1df1029e349edaa6685d3f14dd9942e71467c055afa6f8ccb95d8042", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("applicability", "cohort-plans/diagnostic-breadth-cases/trim_margin_blank_prefix/applicability.kt", "19277a25ec8ce16482610bb04e471f866fb4a6ac9ba5cf5492655d13467891da", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("suppression", "cohort-plans/diagnostic-breadth-cases/trim_margin_blank_prefix/suppression.kt", "ce6941818fcb08660dc9703a3e7115d3e9a4f2bbb06f6e5ec547b8c8c53f60ba", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(3, 36, 56, "text.trimMargin(\" \")")), emptyList(), "exit0", "local_scope_unsuppressed_control_required"),
                Case("regression", "cohort-plans/diagnostic-breadth-cases/trim_margin_blank_prefix/regression.kt", "87472ccf0407783ec22b601eeae3cd8d0d1fb90fc69f4d71bb6f0176fb081e98", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(3, 33, 53, "text.trimMargin(\" \")")), emptyList(), "exit0", "not_applicable")
            )),
        Rule("ktc.compiler.common.array_equality_operator_can_be_replaced_with_content_equals", "ARRAY_EQUALITY_OPERATOR_CAN_BE_REPLACED_WITH_CONTENT_EQUALS", "diagnostic_breadth_advisory", "WARNING", "advisory",
            listOf("-jvm-target", "17", "-language-version", "2.4", "-api-version", "2.4", "-Wextra"), listOf("-jvm-target", "17", "-language-version", "2.4", "-api-version", "2.4", "-Wextra", "-Xwarning-level=ARRAY_EQUALITY_OPERATOR_CAN_BE_REPLACED_WITH_CONTENT_EQUALS:disabled"),
            "Explicit identity comparison is intentional and excluded; this advisory does not forbid array identity semantics.", "List structural equality is not same-family array equality.", listOf(
                Case("positive", "cohort-plans/diagnostic-breadth-cases/array_equality_operator_can_be_replaced_with_content_equals/positive.kt", "7aaca17dec132fe7adb922da093d4d09d620306dacf2557debbf228afb7a40de", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(1, 59, 61, "==")), emptyList(), "exit0", "not_applicable"),
                Case("negative", "cohort-plans/diagnostic-breadth-cases/array_equality_operator_can_be_replaced_with_content_equals/negative.kt", "ad5dad7083081dd4402a446f484d5fa55517b0af86a6102e794049a54248200e", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("boundary", "cohort-plans/diagnostic-breadth-cases/array_equality_operator_can_be_replaced_with_content_equals/boundary.kt", "8f3c7b206cb7a7815e9e36d8e82e981d7d14ccf21c19f988da3bd328744299f9", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("applicability", "cohort-plans/diagnostic-breadth-cases/array_equality_operator_can_be_replaced_with_content_equals/applicability.kt", "3e9496c45c144611477268fddbcfb028a2903819ce281e8639e1a3255564a2d5", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("suppression", "cohort-plans/diagnostic-breadth-cases/array_equality_operator_can_be_replaced_with_content_equals/suppression.kt", "bc22907cffdb676774011fe9964f5621ee415827e922f10af3b339a18c3a26a4", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(3, 61, 63, "==")), emptyList(), "exit0", "local_scope_unsuppressed_control_required"),
                Case("regression", "cohort-plans/diagnostic-breadth-cases/array_equality_operator_can_be_replaced_with_content_equals/regression.kt", "d3336d14df9f515acf53b33196ea6198b7888e1d6aab746f30559b466d0edb90", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(3, 59, 61, "==")), emptyList(), "exit0", "not_applicable")
            )),
        Rule("ktc.compiler.common.assigned_value_is_never_read", "ASSIGNED_VALUE_IS_NEVER_READ", "diagnostic_breadth_advisory", "WARNING", "advisory",
            listOf("-jvm-target", "17", "-language-version", "2.4", "-api-version", "2.4", "-Wextra"), listOf("-jvm-target", "17", "-language-version", "2.4", "-api-version", "2.4", "-Wextra", "-Xwarning-level=ASSIGNED_VALUE_IS_NEVER_READ:disabled"),
            "Delegated writes may execute observers and are excluded rather than treated as dead ordinary-local writes.", "Member setter calls may have externally observable effects; this local assignment checker does not prove them redundant.", listOf(
                Case("positive", "cohort-plans/diagnostic-breadth-cases/assigned_value_is_never_read/positive.kt", "ae96c4d30f8e4132c17d66d3e0780272dd30f9d253f59bdfe828fe0ba5805bf6", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(4, 4, 9, "value")), emptyList(), "exit0", "not_applicable"),
                Case("negative", "cohort-plans/diagnostic-breadth-cases/assigned_value_is_never_read/negative.kt", "a0fec78383ab959d76f6c8189d7d4cf38e97c7e6fb3af74ee2963e6181186602", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("boundary", "cohort-plans/diagnostic-breadth-cases/assigned_value_is_never_read/boundary.kt", "60b94e296ce5b0077fb0b195fbdea2e549774ea6f4e3784f10b80a1daf02d8a4", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("applicability", "cohort-plans/diagnostic-breadth-cases/assigned_value_is_never_read/applicability.kt", "6e40efc18a2b3fe3c47296c7be475676ef86bb159441bc42af3176d8b9753707", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("suppression", "cohort-plans/diagnostic-breadth-cases/assigned_value_is_never_read/suppression.kt", "83924ed73cbdd450cd862ee4956fadeca582c79a955ebf24f6b4277e7afa366d", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(12, 4, 9, "value")), emptyList(), "exit0", "local_scope_unsuppressed_control_required"),
                Case("regression", "cohort-plans/diagnostic-breadth-cases/assigned_value_is_never_read/regression.kt", "56a05911d91ce9b577b8ecc8e6eaa2b0f3528bd8c6c8523775b3d53e1967db72", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(6, 4, 9, "value")), emptyList(), "exit0", "not_applicable")
            )),
        Rule("ktc.compiler.common.can_be_val", "CAN_BE_VAL", "diagnostic_breadth_opt_in", "WARNING", "opt_in",
            listOf("-jvm-target", "17", "-language-version", "2.4", "-api-version", "2.4", "-Wextra"), listOf("-jvm-target", "17", "-language-version", "2.4", "-api-version", "2.4", "-Wextra", "-Xwarning-level=CAN_BE_VAL:disabled"),
            "Delegated var can have observable setValue semantics and is excluded by the checker.", "An immutable parameter without a local var gives no local mutability advice.", listOf(
                Case("positive", "cohort-plans/diagnostic-breadth-cases/can_be_val/positive.kt", "541895229c6d2be9c148c1eb01a8edc4528eea8127ca936253f864bac71fd01a", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(2, 4, 7, "var")), emptyList(), "exit0", "not_applicable"),
                Case("negative", "cohort-plans/diagnostic-breadth-cases/can_be_val/negative.kt", "d8d71acf16eca35a405e2212f1e1c8d25cce4605c991de58e780e1cb9afc2167", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("boundary", "cohort-plans/diagnostic-breadth-cases/can_be_val/boundary.kt", "52f9ac698b992d1a358e724e3f7c539739b173eec6404fdfcaf971d3463789d2", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("applicability", "cohort-plans/diagnostic-breadth-cases/can_be_val/applicability.kt", "f6aa9f418a553607859722c310cc710dd1429a17b949d160e70dae6398fe60c0", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("suppression", "cohort-plans/diagnostic-breadth-cases/can_be_val/suppression.kt", "d8728607b21fcd0e048c2c555cde3d4c42c5b5d2efa1c1ba2632f4321fd57786", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(7, 4, 7, "var")), emptyList(), "exit0", "local_scope_unsuppressed_control_required"),
                Case("regression", "cohort-plans/diagnostic-breadth-cases/can_be_val/regression.kt", "a227c4d77963dec7305dc8d315cfbe09fa0da0a0dbc4012cf18f0bb2b0bb81d5", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(4, 4, 7, "var")), emptyList(), "exit0", "not_applicable")
            )),
        Rule("ktc.compiler.common.redundant_call_of_conversion_method", "REDUNDANT_CALL_OF_CONVERSION_METHOD", "diagnostic_breadth_opt_in", "WARNING", "opt_in",
            listOf("-jvm-target", "17", "-language-version", "2.4", "-api-version", "2.4", "-Wextra"), listOf("-jvm-target", "17", "-language-version", "2.4", "-api-version", "2.4", "-Wextra", "-Xwarning-level=REDUNDANT_CALL_OF_CONVERSION_METHOD:disabled"),
            "Ambiguous integer literals are an explicit exclusion, not an inferred redundant-conversion proof.", "A custom extension converter is excluded; same method name is not a builtin dispatch-type match.", listOf(
                Case("positive", "cohort-plans/diagnostic-breadth-cases/redundant_call_of_conversion_method/positive.kt", "6b961756f92789895b785d0eb15602d170b15856f15171197ea0490ae708aae1", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(1, 34, 41, "toInt()")), emptyList(), "exit0", "not_applicable"),
                Case("negative", "cohort-plans/diagnostic-breadth-cases/redundant_call_of_conversion_method/negative.kt", "91db1918d708e4cc9e02a1413825f5825920f00cb776b000c699ca06a790ee94", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("boundary", "cohort-plans/diagnostic-breadth-cases/redundant_call_of_conversion_method/boundary.kt", "b37ba9297fc9784ba0f2b2a3538baf1cbf9e8d99df18fff8210af117541865a6", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("applicability", "cohort-plans/diagnostic-breadth-cases/redundant_call_of_conversion_method/applicability.kt", "0c7128656a0ea44f1529d2e625839f32834331c0898f6f01599ba8cf2832970d", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("suppression", "cohort-plans/diagnostic-breadth-cases/redundant_call_of_conversion_method/suppression.kt", "d89add178f7e758d2da281c468490ca43440539b95bfb5f62f421a95473d9c44", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(3, 37, 44, "toInt()")), emptyList(), "exit0", "local_scope_unsuppressed_control_required"),
                Case("regression", "cohort-plans/diagnostic-breadth-cases/redundant_call_of_conversion_method/regression.kt", "e733eda7af2fd41bbe5fc8832b8786bd0b4f725ee3b20ee283d7eaaaeae8f555", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(3, 34, 41, "toInt()")), emptyList(), "exit0", "not_applicable")
            )),
        Rule("ktc.compiler.common.useless_call_on_not_null", "USELESS_CALL_ON_NOT_NULL", "diagnostic_breadth_opt_in", "WARNING", "opt_in",
            listOf("-jvm-target", "17", "-language-version", "2.4", "-api-version", "2.4", "-Wextra"), listOf("-jvm-target", "17", "-language-version", "2.4", "-api-version", "2.4", "-Wextra", "-Xwarning-level=USELESS_CALL_ON_NOT_NULL:disabled"),
            "After a null guard the actual smartcast receiver is nonnull, so this boundary still expects the warning.", "A custom same-name extension is not one of the exact kotlin stdlib callable IDs.", listOf(
                Case("positive", "cohort-plans/diagnostic-breadth-cases/useless_call_on_not_null/positive.kt", "781b1f9e7d6e94ae340bd8d3774c4af93bab684ac7323a55801dc98101a978d4", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(1, 48, 57, "orEmpty()")), emptyList(), "exit0", "not_applicable"),
                Case("negative", "cohort-plans/diagnostic-breadth-cases/useless_call_on_not_null/negative.kt", "92049d278bd529e368cab4a060ee033d08060704abad11f8dbf48283fa467627", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("boundary", "cohort-plans/diagnostic-breadth-cases/useless_call_on_not_null/boundary.kt", "7902b125375d1503f96c3b4c87a8bdabec14364bf5c6e465a07b5fb6c2528ae5", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(3, 18, 27, "orEmpty()")), emptyList(), "exit0", "not_applicable"),
                Case("applicability", "cohort-plans/diagnostic-breadth-cases/useless_call_on_not_null/applicability.kt", "4bccfb06565f59f0f45f06487240c77d5a74c7786c6babd5dd733a19ecd2fcd9", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("suppression", "cohort-plans/diagnostic-breadth-cases/useless_call_on_not_null/suppression.kt", "46ed3c7080e0ca132872042e8a73f0be7c67602f572edf008bf100a0d1ecc0df", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(3, 51, 60, "orEmpty()")), emptyList(), "exit0", "local_scope_unsuppressed_control_required"),
                Case("regression", "cohort-plans/diagnostic-breadth-cases/useless_call_on_not_null/regression.kt", "cc056c865316ab4d02c6f5153d948e13782ae0b04602297c4fe1281b1d80891b", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(3, 48, 57, "orEmpty()")), emptyList(), "exit0", "not_applicable")
            )),
        Rule("ktc.compiler.common.no_explicit_return_type_in_api_mode", "NO_EXPLICIT_RETURN_TYPE_IN_API_MODE", "diagnostic_breadth_opt_in", "ERROR", "opt_in",
            listOf("-jvm-target", "17", "-language-version", "2.4", "-api-version", "2.4", "-Xexplicit-api=strict"), listOf("-jvm-target", "17", "-language-version", "2.4", "-api-version", "2.4", "-Xexplicit-api=disable"),
            "Internal declarations without PublishedApi are not effective public API.", "A public block-bodied Unit function is an intentional explicit-return-type exemption.", listOf(
                Case("positive", "cohort-plans/diagnostic-breadth-cases/no_explicit_return_type_in_api_mode/positive.kt", "f653b299042a0d52ac722955023a4cbefe781007594db777bce49873903ec947", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(1, 11, 17, "answer")), emptyList(), "nonzero", "not_applicable"),
                Case("negative", "cohort-plans/diagnostic-breadth-cases/no_explicit_return_type_in_api_mode/negative.kt", "dd2d1659a93dce4d6b55dd3e4c488277b19bd4a4e72d3f2b0301f6c54ae19b5e", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("boundary", "cohort-plans/diagnostic-breadth-cases/no_explicit_return_type_in_api_mode/boundary.kt", "08e4cc9e03cfca0097b6e6687aebbcace1ea96fadc08eb80559b32118fc7c364", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("applicability", "cohort-plans/diagnostic-breadth-cases/no_explicit_return_type_in_api_mode/applicability.kt", "df30111874e0eab909490d197f5212164dd9188a9d1845a14fe496b4cc6548d6", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("suppression", "cohort-plans/diagnostic-breadth-cases/no_explicit_return_type_in_api_mode/suppression.kt", "3e67fce0fee3408c07974b379c55c193628a6935156d74ea4ce2f8c11418d1b2", null,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(2, 11, 17, "answer")), emptyList(), "unknown", "unknown_not_validated"),
                Case("regression", "cohort-plans/diagnostic-breadth-cases/no_explicit_return_type_in_api_mode/regression.kt", "fa85f12274bb1d500e4387638ee27b623dca0ff6b8b2778e9c4ae06fdcd447ae", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(3, 11, 17, "answer")), emptyList(), "nonzero", "not_applicable")
            )),
        Rule("ktc.compiler.common.return_value_not_used", "RETURN_VALUE_NOT_USED", "diagnostic_breadth_opt_in", "WARNING", "opt_in",
            listOf("-jvm-target", "17", "-language-version", "2.4", "-api-version", "2.4", "-Xreturn-value-checker=full"), listOf("-jvm-target", "17", "-language-version", "2.4", "-api-version", "2.4", "-Xreturn-value-checker=disable"),
            "IgnorableReturnValue intentionally exempts a non-Unit result, only in full enabled mode.", "Unit-returning effects have no required value to consume.", listOf(
                Case("positive", "cohort-plans/diagnostic-breadth-cases/return_value_not_used/positive.kt", "eaddc65762ee868b0b4916737b4eec16806a2542c27104e9d3de52b4fd389cbf", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(3, 4, 11, "compute")), emptyList(), "exit0", "not_applicable"),
                Case("negative", "cohort-plans/diagnostic-breadth-cases/return_value_not_used/negative.kt", "72be0ccbdae4ebc1f22f2ab2681b379604f75aae61d7e6c7ff957e1dbdf4dd8f", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("boundary", "cohort-plans/diagnostic-breadth-cases/return_value_not_used/boundary.kt", "e10077e9145f522b7b7cb1fa87ae295d41352d50397edbe167070aad567ddbe5", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("applicability", "cohort-plans/diagnostic-breadth-cases/return_value_not_used/applicability.kt", "f74b2d4f5d45022e8e8396a038e2a461d32e9b68d5fa79805230ca053d1f8d23", 0,
                    emptyList(), emptyList(), "exit0", "not_applicable"),
                Case("suppression", "cohort-plans/diagnostic-breadth-cases/return_value_not_used/suppression.kt", "f1bca10edd0bea47c347852bf81d2ad6a7089def64962536fdbae658f65926cf", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(7, 4, 11, "compute")), emptyList(), "exit0", "local_scope_unsuppressed_control_required"),
                Case("regression", "cohort-plans/diagnostic-breadth-cases/return_value_not_used/regression.kt", "89cc75d8eff0e5e1d9e64825f02f6c35df2edca735d8c57db270cdc1acdd96f3", 1,
                    listOf(CompilerFixtureDiagnosticBreadthRegion(5, 4, 11, "compute")), emptyList(), "exit0", "not_applicable")
            ))
    )
    val SOURCE_METADATA_PINS: Map<String, String> = linkedMapOf(
        "cohort-plans/diagnostic-breadth-v1.json" to "4bbb0528fcd0ab9b7c5c8fb5252bbe03a481666abed1ec5bb29b6632bed8b8bd",
        "cohort-plans/diagnostic-breadth-source-evidence.json" to "ed4e9f5c81d0ca7383fe38b41da4d4a23e419b7afd5ecee1e178fa9ffc9b546b",
        "cohort-plans/diagnostic-breadth-gap-inventory.json" to "9cbf6743f83d25dcef58325997552539e7c0116efff3a8523ab58c87df456c54",
        "cohort-plans/diagnostic-breadth-README.md" to "f28007321273fafbff8b4562977d72b15f8607cacc5359db5162ecae0818148b",
        "test_diagnostic_breadth.py" to "93de1bdd142c939864a1a72b39ccbbfc4161663c18e8e85db71633c5e5dedbe2",
        "test_diagnostic_breadth_hookup.py" to "4b18f75ae9067b75e4177345a9aa3f33d2e62e34ba629be271511d5ce7c86c09",
    )
    val RULE_IDS: Set<String> = RULES.map { it.ruleId }.toSet()
    val START_CAPTURE_PATHS: List<String> = RULES.flatMap { rule -> rule.cases.map { it.path } } + SOURCE_METADATA_PINS.keys

    init {
        require(RULES.size == 15 && RULE_IDS.size == 15 && START_CAPTURE_PATHS.size == 96 && START_CAPTURE_PATHS.toSet().size == 96)
        require(RULES.all { it.cases.map { source -> source.kind } == KINDS })
        require(RULE_IDS.intersect(CompilerFixtureNullabilityPlan.RULE_IDS).isEmpty())
        SELECTORS.forEach { selector ->
            require(rules(selector).size == 5 && declarations(selector).size + 5 == maxAttempts(selector))
            require((selector == DEFAULT_SELECTOR) == rules(selector).all { it.offFlags == null })
        }
    }

    private fun rules(selector: String): List<Rule> {
        require(selector in SELECTORS) { "An exact closed diagnostic breadth selector is required" }
        return RULES.filter { it.selector == selector }
    }
    fun ruleIds(selector: String): Set<String> = rules(selector).map { it.ruleId }.toSet()
    fun maxAttempts(selector: String): Int { require(selector in SELECTORS); return ATTEMPT_LIMITS.getValue(selector) }
    fun versionDenialAttempt(selector: String): Int = maxAttempts(selector) - 4
    fun childCapableCalls(selector: String): Int = maxAttempts(selector) - 1

    fun declarations(selector: String): List<CompilerFixtureDiagnosticBreadthDeclaration> {
        val selected = rules(selector)
        val sources = KINDS.flatMap { kind -> selected.map { rule -> rule to rule.cases.single { it.kind == kind } } }
        val ordinary = sources.mapIndexed { index, (rule, source) -> declaration(index + 1, rule, source) }
        val off = selected.filter { it.offFlags != null }.mapIndexed { index, rule ->
            val positive = rule.cases.single { it.kind == "positive" }
            declaration(31 + index, rule, positive, optionOff = true)
        }
        return ordinary + off
    }
    fun allDeclarations(): List<CompilerFixtureDiagnosticBreadthDeclaration> = SELECTORS.flatMap(::declarations)
    private fun declaration(number: Int, rule: Rule, source: Case, optionOff: Boolean = false) =
        CompilerFixtureDiagnosticBreadthDeclaration(number, rule.selector, rule.ruleId, rule.upstreamId, rule.activation,
            rule.severity, if (optionOff) "option_off" else source.kind, source.kind, source.path, source.digest,
            if (optionOff) checkNotNull(rule.offFlags) else rule.flags, if (optionOff) 0 else source.expectedCount,
            if (optionOff) emptyList() else source.regions, if (optionOff) emptyList() else source.companions,
            if (optionOff) "exit0" else source.compilation, if (optionOff) "not_applicable" else source.suppression)

    fun verifyCapturedMetadata(readCaptured: (String) -> String) {
        SOURCE_METADATA_PINS.forEach { (path, digest) -> require(Digests.sha256(readCaptured(path)).removePrefix("sha256:") == digest) {
            "Diagnostic breadth metadata differs from the reviewed source-only plan: $path"
        } }
    }

    /** Validate every record before reading source; normalize row order but never let JSON choose calls. */
    fun load(manifest: JsonObject, readCapturedSource: (String) -> String): List<CompilerFixtureDiagnosticBreadthInput> {
        val expectedHeader = breadthJson(mapOf("schema_version" to 1, "kotlin_version" to "2.4.21",
            "status" to "authored_source_only_additive_plan", "run_complete" to false,
            "ordinary_six_kind_assessments_complete" to false, "qualification" to "none", "ordered_kinds" to KINDS,
            "source_file_count" to SOURCE_FILE_COUNT, "rule_count" to 15, "selectors" to SELECTORS.toList(),
            "source_plan_and_oracle_are_execution_authority" to false))
        require(JsonObject(manifest.filterKeys { it != "fixtures" }) == expectedHeader)
        val rows = manifest.getValue("fixtures").jsonArray.map { it.jsonObject }
        require(rows.size == RULES.size && rows.map { it.getValue("rule_id").jsonPrimitive.content }.toSet() == RULE_IDS)
        val records = linkedMapOf<Pair<String, String>, JsonObject>()
        for (rule in RULES) {
            val row = rows.single { it.getValue("rule_id").jsonPrimitive.content == rule.ruleId }
            require(JsonObject(row.filterKeys { it !in setOf("inputs", "option_off_probe") }) == breadthJson(rule.header()))
            val inputs = row.getValue("inputs").jsonArray.map { it.jsonObject }
            require(inputs.size == KINDS.size && inputs.map { it.getValue("kind").jsonPrimitive.content }.toSet() == KINDS.toSet())
            for (expected in declarations(rule.selector).filter { it.ruleId == rule.ruleId }) {
                val record = if (expected.kind == "option_off") row.getValue("option_off_probe").jsonObject else
                    inputs.single { it.getValue("kind").jsonPrimitive.content == expected.kind }
                require(record == expected.authoringRecord()) { "Authored breadth expectation or source identity changed" }
                require(records.put(rule.ruleId to expected.kind, record) == null)
            }
            if (rule.offFlags == null) require(row.getValue("option_off_probe") == JsonNull)
        }
        val sourceDeclarations = allDeclarations().filter { it.kind != "option_off" }
        val sources = sourceDeclarations.associate { declaration ->
            val source = readCapturedSource(declaration.path)
            require(Digests.sha256(source).removePrefix("sha256:") == declaration.sourceSha256) { "Authored breadth source changed: ${declaration.path}" }
            require((declaration.selectedRegions + declaration.companions.flatMap { it.regions }).all { it.belongsTo(source) })
            declaration.path to source
        }
        require(sources.size == SOURCE_FILE_COUNT)
        return allDeclarations().map { CompilerFixtureDiagnosticBreadthInput(it, sources.getValue(it.path), records.getValue(it.ruleId to it.kind)) }
    }

    /** Exact unchanged execute() shape: one authored input plus inert captured build metadata, never extra members. */
    fun snapshotMembersMatch(input: CompilerFixtureDiagnosticBreadthInput, files: List<SnapshotFile>): Boolean {
        val d = input.declaration
        val sourceBytes = input.source.toByteArray(Charsets.UTF_8)
        if (Digests.sha256(sourceBytes) != "sha256:" + d.sourceSha256) return false
        val metadata = "// Synthetic captured fixture metadata; never executed or compiled.\n".toByteArray(Charsets.UTF_8)
        val expected = listOf(
            SnapshotFile(d.path, "sha256:" + d.sourceSha256, sourceBytes.size.toLong()),
            SnapshotFile("build.gradle.kts", Digests.sha256(metadata), metadata.size.toLong()),
        )
        return files.size == 2 && files.sortedBy { it.path } == expected.sortedBy { it.path }
    }

    fun authoredPointMatches(location: SourceLocation?, declaration: CompilerFixtureDiagnosticBreadthDeclaration,
        source: String, regions: List<CompilerFixtureDiagnosticBreadthRegion>): Boolean {
        if (location == null || location.path != declaration.path ||
            location.fileDigest != "sha256:" + declaration.sourceSha256 ||
            Digests.sha256(source).removePrefix("sha256:") != declaration.sourceSha256 ||
            location.endLine != location.line || location.endColumn != location.column) return false
        return regions.any { region -> region.belongsTo(source) && location.line == region.line &&
            location.column in region.startColumn until region.endColumnExclusive }
    }

    fun diagnostics(input: CompilerFixtureDiagnosticBreadthInput, fullRaw: List<RawDiagnostic>,
        fullFindings: List<Finding>): CompilerFixtureDiagnosticBreadthDiagnostics {
        val d = input.declaration
        fun point(location: SourceLocation?, regions: List<CompilerFixtureDiagnosticBreadthRegion>) = authoredPointMatches(location, d, input.source, regions)
        val raw = fullRaw.filter { it.upstreamId == d.upstreamId }
        // OR is intentional: a wrong upstream/stable-ID pairing must not disappear from the selected channel.
        val mapped = fullFindings.filter { it.ruleId == d.ruleId || it.upstreamId == d.upstreamId }
        val rawValid = raw.all { it.severity == d.severity.lowercase() && point(it.location, d.selectedRegions) }
        val mappedValid = mapped.all { it.ruleId == d.ruleId && it.upstreamId == d.upstreamId &&
            it.severity == d.severity.lowercase() && point(it.location, d.selectedRegions) }
        fun key(location: SourceLocation, message: String): List<Any> = listOf(location.path, location.fileDigest,
            location.line, location.column, location.endLine, location.endColumn, message)
        val pointsAgree = rawValid && mappedValid && raw.groupingBy { key(checkNotNull(it.location), it.message) }.eachCount() ==
            mapped.groupingBy { key(it.location, it.diagnosis) }.eachCount()
        val companionMatch = d.companions.all { expected ->
            val actual = fullRaw.filter { it.upstreamId == expected.upstreamId }
            actual.size == expected.expectedRawCount && actual.all { it.severity == "error" && point(it.location, expected.regions) } &&
                fullFindings.none { it.upstreamId == expected.upstreamId }
        }
        fun allowedRawError(rawError: RawDiagnostic): Boolean =
            (d.expectedCount == 1 && d.severity == "ERROR" && rawError.upstreamId == d.upstreamId && point(rawError.location, d.selectedRegions)) ||
                d.companions.any { it.upstreamId == rawError.upstreamId && point(rawError.location, it.regions) }
        val unexpectedRaw = if (d.unknownExpectation) null else fullRaw.count { it.severity == "error" && !allowedRawError(it) }
        val unexpectedMapped = if (d.unknownExpectation) null else fullFindings.count { it.severity == "error" &&
            !(d.expectedCount == 1 && d.severity == "ERROR" && it.ruleId == d.ruleId && it.upstreamId == d.upstreamId && point(it.location, d.selectedRegions)) }
        return CompilerFixtureDiagnosticBreadthDiagnostics(fullRaw.size, fullRaw.count { it.severity == "error" }, raw.size,
            fullFindings.size, fullFindings.count { it.severity == "error" }, mapped.size, pointsAgree,
            !d.unknownExpectation && raw.size == d.expectedCount && mapped.size == d.expectedCount && rawValid && mappedValid,
            companionMatch, fullRaw.count { actual -> d.companions.any { it.upstreamId == actual.upstreamId } },
            unexpectedRaw, unexpectedMapped, fullRaw.count { it.upstreamId == "ERROR_SUPPRESSION" })
    }

    fun versionDenialMatches(selector: String, attempt: CompilerFixtureAttempt, requestedVersion: String?, providerStatus: String,
        reasons: List<String>, actualProcessObserved: Boolean, receiptsAndInputIntact: Boolean): Boolean =
        attempt.number == versionDenialAttempt(selector) && attempt.label == VERSION_DENIAL_LABEL && attempt.ruleIds.isEmpty() &&
            requestedVersion == VERSION_DENIAL_REQUESTED_VERSION && !actualProcessObserved && providerStatus == "unavailable" &&
            "compiler_version_not_pinned_2_4_21" in reasons && receiptsAndInputIntact

    /** Full reservation equality is arithmetic, not a compiler, denial, native or cleanup witness. */
    fun exactAttemptScheduleMatches(selector: String, attempts: List<CompilerFixtureAttempt>): Boolean {
        val expected = declarations(selector)
        val labels = expected.map { it.label } + VERSION_DENIAL_LABEL + CONTROL_LABELS
        return attempts.size == maxAttempts(selector) && attempts.indices.all { index ->
            attempts[index].number == index + 1 && attempts[index].label == labels[index] &&
                attempts[index].ruleIds == expected.getOrNull(index)?.let { setOf(it.ruleId) }.orEmpty()
        }
    }
    fun observedReceiptsComplete(selector: String, attempts: List<CompilerFixtureAttempt>,
        observations: List<CompilerFixtureDiagnosticBreadthObservation>): Boolean {
        val expected = declarations(selector)
        return observations.size == expected.size && attempts.size >= expected.size && expected.indices.all { index ->
            val d = expected[index]
            val o = observations[index]
            val a = attempts[index]
            o.declaration == d && o.attemptNumber == d.number && o.observedReceiptComplete &&
                a.number == d.number && a.label == d.label && a.ruleIds == setOf(d.ruleId)
        }
    }
    fun expectedVariantsValidated(selector: String, observations: List<CompilerFixtureDiagnosticBreadthObservation>): Boolean =
        observations.map { it.declaration } == declarations(selector) && observations.all { it.expectationMatched == true }
    fun unknownSuppressionCount(selector: String): Int = declarations(selector).count { it.unknownExpectation }

    fun description(selector: String): Map<String, Any?> = mapOf("schedule_id" to "${selector}_source90_v1",
        "named_cohort" to selector, "explicit_rule_ids" to ruleIds(selector).sorted(), "ordered_source_kinds" to KINDS,
        "all_breadth_rule_ids" to RULE_IDS.sorted(), "all_authored_source_files" to SOURCE_FILE_COUNT,
        "selected_authored_source_files" to SOURCE_FILES_PER_SELECTOR,
        "selected_option_off_reused_positive_calls" to declarations(selector).count { it.kind == "option_off" },
        "ordered_inputs" to declarations(selector).map { it.description() }, "planned_provider_reservations" to maxAttempts(selector),
        "planned_child_capable_calls" to childCapableCalls(selector), "version_denial_attempt" to versionDenialAttempt(selector),
        "version_denial_requested_version" to VERSION_DENIAL_REQUESTED_VERSION,
        "ordered_control_attempts" to CONTROL_LABELS.mapIndexed { index, label -> mapOf("number" to versionDenialAttempt(selector) + 1 + index, "label" to label) },
        "additional_START_capture_paths" to START_CAPTURE_PATHS, "exact_metadata_pins" to SOURCE_METADATA_PINS,
        "source_transforms" to 0, "batches" to 0, "retries_or_replacements" to 0, "ordinary_six_kind_jobs" to 0,
        "budget_pooling_resume_or_merge" to false, "unselected_scope" to "visible_not_run_not_excluded",
        "unknown_hard_error_suppression_expectations" to unknownSuppressionCount(selector),
        "compiler_errors_remain_failed_tasks_even_when_oracle_matches" to true,
        "bounds" to mapOf("selector_maximum_attempts" to maxAttempts(selector),
            "unchanged_global_maximum_attempts" to CompilerFixtureSettings.MAX_ATTEMPTS,
            "overall_millis" to CompilerFixtureSettings.MAX_OVERALL_MILLIS, "per_job_millis" to CompilerFixtureSettings.MAX_PER_JOB_MILLIS,
            "evidence_bytes" to CompilerFixtureSettings.MAX_EVIDENCE_BYTES),
        "source_plan_and_oracle_are_execution_authority" to false, "qualification" to "none")
}

private fun breadthJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Int -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (key, element) -> checkNotNull(key as? String) to breadthJson(element) })
    is List<*> -> JsonArray(value.map(::breadthJson))
    else -> error("Unexpected source-oracle JSON value type")
}

internal fun compilerFixtureDiagnosticBreadthLatestSidecar(selected: Boolean, ordinarySidecar: Path?): Path? =
    if (selected) null else ordinarySidecar

internal fun compilerFixtureDiagnosticBreadthCompletionFields(selector: String?, complete: Boolean): Map<String, Any> {
    if (selector == null) return emptyMap()
    val localComplete = complete && CompilerFixtureDiagnosticBreadthPlan.unknownSuppressionCount(selector) == 0
    return mapOf("run_complete" to localComplete, "ordinary_six_kind_assessments_complete" to false,
        "completion_scope" to "private_diagnostic_breadth_local_schedule_only_no_global_stage_credit",
        "diagnostic_breadth_local_schedule_complete" to localComplete,
        "diagnostic_breadth_observed_receipts_alone_are_success" to false,
        "unknown_hard_error_suppression_expectations" to CompilerFixtureDiagnosticBreadthPlan.unknownSuppressionCount(selector))
}

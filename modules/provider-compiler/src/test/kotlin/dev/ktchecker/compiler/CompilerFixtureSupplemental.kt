package dev.ktchecker.compiler

import dev.ktchecker.contracts.Digests
import java.nio.file.Path
import kotlinx.serialization.json.*

/** Reviewed source declarations only. Neither an ordinal nor a hash is an execution witness. */
internal data class CompilerFixtureSupplementalDeclaration(
    val number: Int, val ruleId: String, val upstreamId: String, val kind: String,
    val path: String, val sourceSha256: String,
) {
    val label: String get() = "supplemental-$kind-$upstreamId"
    fun description(): Map<String, Any> = mapOf("planned_attempt_number" to number,
        "rule_id" to ruleId, "upstream_id" to upstreamId, "kind" to kind,
        "authored_path" to path, "source_sha256" to sourceSha256, "job_label" to label)
}

internal data class CompilerFixtureSupplementalInput(
    val declaration: CompilerFixtureSupplementalDeclaration, val source: String, val authoringRecord: JsonObject,
) {
    fun description(): Map<String, Any> = declaration.description() + mapOf(
        "compiler_flags" to CompilerFixtureSupplementalPlan.FLAGS,
        "initial_manifest_authoring_record" to authoringRecord,
        "initial_authoring_status_is_not_current_execution_evidence" to true)
}

/**
 * Value-only diagnostic decision fed by the actual sealed runner. Unit values alone prove no
 * launch, compiler execution, source attribution or cleanup. A healthy supervised nonzero exit
 * can establish an observed receipt, never a successful compilation or passing suppression.
 */
internal data class CompilerFixtureSupplementalObservation(
    val declaration: CompilerFixtureSupplementalDeclaration,
    val attemptNumber: Int,
    val executionConfirmed: Boolean,
    val healthySupervisedExecution: Boolean,
    val pinnedVersionObserved: Boolean,
    val receiptsAndAuthoredInputIntact: Boolean,
    val actualExitCode: Int?,
    val providerStatus: String,
    val selectedRawDiagnosticCount: Int,
    val selectedFindingCount: Int,
    val rawDiagnosticCount: Int,
    val errorSuppressionDiagnosticCount: Int,
) {
    init {
        require(attemptNumber > 0)
        require(selectedRawDiagnosticCount in 0..rawDiagnosticCount && selectedFindingCount >= 0)
        require(errorSuppressionDiagnosticCount in 0..rawDiagnosticCount)
    }

    val observedReceiptComplete: Boolean get() = executionConfirmed && healthySupervisedExecution &&
        pinnedVersionObserved && receiptsAndAuthoredInputIntact && actualExitCode != null &&
        providerStatus in setOf("partial", "failed") && (actualExitCode == 0 || providerStatus == "failed")
    val selectedIdObserved: Boolean get() = selectedRawDiagnosticCount > 0 || selectedFindingCount > 0

    /** The authored suppression expectations are unknown, including when the compiler exits zero. */
    val expectationMatched: Boolean? get() = if (declaration.kind == "suppression") null else
        observedReceiptComplete && actualExitCode == 0 && providerStatus == "partial" && !selectedIdObserved

    val failedVariant: Boolean get() = observedReceiptComplete &&
        (actualExitCode != 0 || providerStatus == "failed" || expectationMatched == false)

    val state: String get() = when {
        !observedReceiptComplete -> "actual_receipt_incomplete_or_unobserved"
        actualExitCode != 0 -> "failed_compilation"
        providerStatus == "failed" -> "failed_provider_result_despite_exit0"
        declaration.kind == "suppression" && selectedIdObserved -> "unvalidated_exit0_selected_id_observed"
        declaration.kind == "suppression" && errorSuppressionDiagnosticCount > 0 ->
            "unvalidated_compiler_accepted_suppression_with_ERROR_SUPPRESSION_warnings"
        declaration.kind == "suppression" && rawDiagnosticCount > 0 ->
            "unvalidated_compiler_accepted_suppression_with_raw_diagnostics"
        declaration.kind == "suppression" -> "unvalidated_compiler_accepted_suppression_unknown_expectation"
        selectedIdObserved -> "failed_selected_id_observed"
        rawDiagnosticCount > 0 -> "proposed_exit0_selected_absence_observed_with_raw_diagnostics"
        else -> "proposed_exit0_selected_absence_observed"
    }

    fun description(): Map<String, Any?> = declaration.description() + mapOf(
        "reserved_attempt_number" to attemptNumber, "execution_confirmed" to executionConfirmed,
        "healthy_supervised_execution" to healthySupervisedExecution, "pinned_version_observed" to pinnedVersionObserved,
        "receipts_and_authored_input_intact" to receiptsAndAuthoredInputIntact,
        "observed_receipt_complete" to observedReceiptComplete, "actual_exit_code" to actualExitCode,
        "provider_status" to providerStatus, "state" to state, "failed_variant" to failedVariant,
        "expected_outcome_matched" to expectationMatched,
        "expectation_validation" to if (declaration.kind == "suppression") "unknown_not_validated" else
            if (expectationMatched == true) "actual_proposed_exit0_selected_absence_matched" else "not_matched_or_unobserved",
        "selected_raw_diagnostic_count" to selectedRawDiagnosticCount, "selected_finding_count" to selectedFindingCount,
        "raw_diagnostic_count" to rawDiagnosticCount, "ERROR_SUPPRESSION_diagnostic_count" to errorSuppressionDiagnosticCount,
        "suppression_warning_policy" to "Retain raw warnings: compiler error suppression is UNSPECIFIED and WILL NOT BE PRESERVED; exit0 is not reliable suppression.",
        "ordinary_six_kind_assessment" to false, "clean_or_successful_coverage_claim" to false, "qualification" to "none")
}

/** Fixed, independently executed authored files; never regenerate or concatenate their bodies. */
internal object CompilerFixtureSupplementalPlan {
    const val SELECTOR = "type_boundaries_supplemental"
    const val AUTHORED_COHORT = "2026-11-04-type_boundaries"
    const val INPUT_COUNT = 18
    const val PLANNED_ATTEMPTS = 23
    const val CHILD_CAPABLE_CALLS = 22
    const val VERSION_DENIAL_LABEL = "wrong-requested-compiler-version"
    val FLAGS = listOf("-jvm-target", "17")
    val KINDS = listOf("boundary", "applicability", "suppression")
    val CONTROL_LABELS = listOf("intent-preserving-warning-controls", "unmapped-syntax-control",
        "quoted-name-is-not-emission", "unnamed-werror-failure-control")

    private data class Rule(val ruleId: String, val upstreamId: String, val sources: Map<String, Pair<String, String>>)
    private val RULES = listOf(
        Rule("ktc.compiler.common.int_literal_out_of_range", "INT_LITERAL_OUT_OF_RANGE", linkedMapOf(
            "boundary" to ("cases/int_literal_out_of_range/boundary.kt" to "5317b90e45898a253afe0d5a9f58d6862e8264da8ce4dd033732a1a92f4a50ed"),
            "applicability" to ("cases/int_literal_out_of_range/applicability.kt" to "6e338567d56a6a600e208ebca03903c1854ca6dbba5d1c2e3ebe4f2f176e346c"),
            "suppression" to ("cases/int_literal_out_of_range/suppression.kt" to "c8a323d1910f22dbd1d00b3598a6f8da2f47fe9adca9cb71de82e7429eb7dce7"))),
        Rule("ktc.compiler.common.cannot_check_for_erased", "CANNOT_CHECK_FOR_ERASED", linkedMapOf(
            "boundary" to ("cases/cannot_check_for_erased/boundary.kt" to "cd63f250360d9bec939380d308169887d7017f562699a95e49f95c455215a6b8"),
            "applicability" to ("cases/cannot_check_for_erased/applicability.kt" to "eae215a94605de750be76cd9dc911368ab5ab798123768651272948de460f044"),
            "suppression" to ("cases/cannot_check_for_erased/suppression.kt" to "afa0a7509ae539b0b6c4dccae4977f52ff2d893b8a8a848548c7d9bec9aac087"))),
        Rule("ktc.compiler.common.recursive_typealias_expansion", "RECURSIVE_TYPEALIAS_EXPANSION", linkedMapOf(
            "boundary" to ("cases/recursive_typealias_expansion/boundary.kt" to "27546e583f4ff54393af9d0ceaaf8e7d1806c884bd03af1489180bf8bad1d19b"),
            "applicability" to ("cases/recursive_typealias_expansion/applicability.kt" to "78ef670e4512bbcad9866de3d57e3a416888fe9b7cd738511e228c380b7d6140"),
            "suppression" to ("cases/recursive_typealias_expansion/suppression.kt" to "8e089e9e6464df1fe29d6124f56289d057f0060e9bd35536c42d162b1bc40e1f"))),
        Rule("ktc.compiler.common.conflicting_upper_bounds", "CONFLICTING_UPPER_BOUNDS", linkedMapOf(
            "boundary" to ("cases/conflicting_upper_bounds/boundary.kt" to "58a7486a1f7dbdd8a72e2ea8f75e41504458925e7622bf61bc7ef2cd878d823c"),
            "applicability" to ("cases/conflicting_upper_bounds/applicability.kt" to "f832b9c433d988981fb9167b8e9dab57531064dd0cbd60959fbe88262f51f1cb"),
            "suppression" to ("cases/conflicting_upper_bounds/suppression.kt" to "a34f0128ad63764a731737ca044ed6d828fac2412359b7616510276a5b321fd0"))),
        Rule("ktc.compiler.common.type_variance_conflict_error", "TYPE_VARIANCE_CONFLICT_ERROR", linkedMapOf(
            "boundary" to ("cases/type_variance_conflict_error/boundary.kt" to "e84683626556459de8661d278d632556b49a798f54863b088470aaa037b998b6"),
            "applicability" to ("cases/type_variance_conflict_error/applicability.kt" to "b964d3824925310f5c878cef028d4f6fcece9e838f42962d25b91e59db9c9f1f"),
            "suppression" to ("cases/type_variance_conflict_error/suppression.kt" to "a72f4f74a5492cd76ed5d8095169c417371bde406902a373929b9b035f4d73e9"))),
        Rule("ktc.compiler.common.unresolved_reference_wrong_receiver", "UNRESOLVED_REFERENCE_WRONG_RECEIVER", linkedMapOf(
            "boundary" to ("cases/unresolved_reference_wrong_receiver/boundary.kt" to "05f4412ed537aefc23824dc5223a8f09ed7c1474944411bfc884c895af284c0f"),
            "applicability" to ("cases/unresolved_reference_wrong_receiver/applicability.kt" to "fad5f520c0fa0cc9e72b3cf3a88c5ee51eef3f44a6a263a4aeb1de1dc123c2b5"),
            "suppression" to ("cases/unresolved_reference_wrong_receiver/suppression.kt" to "c5de166c4603fef3470e978bf72a436885a11b05fbf4227291388b5026722dcc"))),
    )
    val RULE_IDS: Set<String> = RULES.map { it.ruleId }.toSet()

    fun declarations(): List<CompilerFixtureSupplementalDeclaration> = KINDS.flatMap { kind -> RULES.map { rule ->
        val source = rule.sources.getValue(kind)
        CompilerFixtureSupplementalDeclaration(0, rule.ruleId, rule.upstreamId, kind, source.first, source.second)
    } }.mapIndexed { index, declaration -> declaration.copy(number = index + 1) }

    init {
        require(RULE_IDS.size == 6 && declarations().size == INPUT_COUNT)
        require(declarations().map { it.path }.toSet().size == INPUT_COUNT)
        require(RULES.all { it.sources.keys.toList() == KINDS })
        require(PLANNED_ATTEMPTS == INPUT_COUNT + 1 + CONTROL_LABELS.size && CHILD_CAPABLE_CALLS == PLANNED_ATTEMPTS - 1)
        require(PLANNED_ATTEMPTS <= CompilerFixtureSettings.MAX_ATTEMPTS)
    }

    /** Parsing fails closed on added, removed, duplicate, rewritten or retrospectively relabeled variants. */
    fun load(manifest: JsonObject, readCapturedSource: (String) -> String): List<CompilerFixtureSupplementalInput> {
        require(manifest.getValue("kotlin_version").jsonPrimitive.content == CompilerRuleRegistry.VERSION)
        val rows = manifest.getValue("fixtures").jsonArray.map { it.jsonObject }.filter { "supplemental_inputs" in it }
        require(rows.size == 6 && rows.map { it.getValue("rule_id").jsonPrimitive.content }.toSet() == RULE_IDS) {
            "The reviewed six-ID supplemental inventory changed"
        }
        val records = linkedMapOf<Pair<String, String>, JsonObject>()
        rows.forEach { row ->
            val ruleId = row.getValue("rule_id").jsonPrimitive.content
            val rule = RULES.single { it.ruleId == ruleId }
            require(row.getValue("upstream_id").jsonPrimitive.content == rule.upstreamId &&
                row.getValue("authored_cohort").jsonPrimitive.content == AUTHORED_COHORT &&
                row.getValue("declared_severity").jsonPrimitive.content == "error" && strings(row.getValue("flags")) == FLAGS)
            val variants = row.getValue("supplemental_inputs").jsonArray.map { it.jsonObject }
            require(variants.size == KINDS.size && variants.map { it.getValue("kind").jsonPrimitive.content }.toSet() == KINDS.toSet())
            variants.forEach { variant ->
                val kind = variant.getValue("kind").jsonPrimitive.content
                val expected = rule.sources.getValue(kind)
                require(variant.getValue("path").jsonPrimitive.content == expected.first &&
                    variant.getValue("source_sha256").jsonPrimitive.content == expected.second &&
                    strings(variant.getValue("compiler_flags")) == FLAGS)
                require(variant.getValue("status").jsonPrimitive.content == "unrun_supplemental_source_plan" &&
                    variant.getValue("execution_state").jsonPrimitive.content == "not_run" &&
                    !variant.getValue("executed_by_current_pair_harness").jsonPrimitive.isString &&
                    variant.getValue("executed_by_current_pair_harness").jsonPrimitive.boolean == false) {
                    "Keep initial authoring records unchanged; new execution belongs to its own run receipts"
                }
                require(variant.getValue("proposed_expectation").jsonPrimitive.content.startsWith("Proposed:"))
                require(variant.getValue("execution_policy").jsonPrimitive.content ==
                    "Independent sealed variant only; never concatenate positive/negative/boundary/applicability/suppression variants or count source presence as execution.")
                require(variant.getValue("compilation_expectation").jsonPrimitive.content == if (kind == "suppression")
                    "unknown_preserve_actual_failure_or_compiler_accepted_suppression" else "proposed_exit0_not_observed")
                require(variant.getValue("diagnostic_expectation").jsonPrimitive.content == if (kind == "suppression")
                    "unknown_preserve_actual_emission_or_suppression" else "proposed_selected_id_absent_not_observed")
                require(records.put(ruleId to kind, variant) == null)
            }
        }
        return declarations().map { declaration ->
            val source = readCapturedSource(declaration.path)
            require(Digests.sha256(source).removePrefix("sha256:") == declaration.sourceSha256) {
                "Captured authored supplemental bytes differ from the reviewed source: ${declaration.path}"
            }
            CompilerFixtureSupplementalInput(declaration, source, records.getValue(declaration.ruleId to declaration.kind))
        }
    }

    /** Informational actual receipt completeness, including failed compiler tasks; never a success gate. */
    fun observedReceiptsComplete(attempts: List<CompilerFixtureAttempt>, observations: List<CompilerFixtureSupplementalObservation>): Boolean {
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

    /** Reserved labels/subjects only; matching arithmetic is not execution or denial proof. */
    fun exactAttemptScheduleMatches(attempts: List<CompilerFixtureAttempt>): Boolean {
        val expected = declarations()
        val labels = expected.map { it.label } + VERSION_DENIAL_LABEL + CONTROL_LABELS
        return attempts.size == PLANNED_ATTEMPTS && attempts.indices.all { index ->
            attempts[index].number == index + 1 && attempts[index].label == labels[index] &&
                attempts[index].ruleIds == expected.getOrNull(index)?.let { setOf(it.ruleId) }.orEmpty()
        }
    }

    /** Null/unknown suppression expectations cannot validate terminal success, even with exit0. */
    fun expectedVariantsValidated(observations: List<CompilerFixtureSupplementalObservation>): Boolean =
        observations.map { it.declaration } == declarations() && observations.all { it.expectationMatched == true }

    fun description(): Map<String, Any> = mapOf("schedule_id" to "type_boundaries_supplemental_authored18_v1",
        "named_cohort" to SELECTOR, "explicit_rule_ids" to RULE_IDS.sorted(), "ordered_kinds" to KINDS,
        "inputs" to declarations().map { it.description() }, "planned_provider_reservations" to PLANNED_ATTEMPTS,
        "planned_child_capable_calls" to CHILD_CAPABLE_CALLS, "per_input_source_files" to 1,
        "version_denial_attempt" to 19, "version_denial_requested_version" to "2.4.20",
        "ordered_control_attempts" to CONTROL_LABELS.mapIndexed { index, label -> mapOf("number" to index + 20, "label" to label) },
        "compiler_flags" to FLAGS, "source_transforms" to 0, "cohort_batches" to 0, "retries_or_replacements" to 0,
        "ordinary_six_kind_jobs" to 0, "budget_pooling_resume_or_merge" to false,
        "bounds" to mapOf("maximum_attempts" to CompilerFixtureSettings.MAX_ATTEMPTS,
            "overall_millis" to CompilerFixtureSettings.MAX_OVERALL_MILLIS,
            "per_job_millis" to CompilerFixtureSettings.MAX_PER_JOB_MILLIS,
            "evidence_bytes" to CompilerFixtureSettings.MAX_EVIDENCE_BYTES),
        "execution_policy" to "Existing START-captured authored bytes and paths; independent exact calls only. Stop/cleanup/quarantine and smaller owner bounds can leave the planned schedule incomplete.",
        "suppression_policy" to "Every nonzero exit stays failed; exit0 unknown suppression expectations stay unvalidated, with all raw warnings retained.",
        "completion_policy" to "All18 observed receipts is informational only; failed or unknown variants keep the private run partial. No ordinary completion or mutable latest sidecar.",
        "execution_or_project_policy_authority" to false, "qualification" to "none")

    private fun strings(value: JsonElement): List<String> = value.jsonArray.map { it.jsonPrimitive.content }
}

/** A supplemental invocation has no external latest destination, independently of probe behavior. */
internal fun compilerFixtureSupplementalLatestSidecar(selected: Boolean, ordinaryOrProbeSidecar: Path?): Path? =
    if (selected) null else ordinaryOrProbeSidecar

/** Unknown authored suppression expectations cannot turn observed receipts into a passing run. */
internal fun compilerFixtureSupplementalCompletionFields(selected: Boolean): Map<String, Any> = if (!selected) emptyMap() else
    mapOf("run_complete" to false, "ordinary_six_kind_assessments_complete" to false,
        "completion_scope" to "private_authored_supplemental_observations_only_not_terminal_success",
        "supplemental_observed_receipts_are_success" to false, "suppression_expectations" to "unknown_not_validated")

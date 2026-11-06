package dev.ktchecker.compiler

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.ProcessOutcome

internal data class CompilerCurrent445Assessment(
    val expectedOutcomeMatched: Boolean?,
    val knownAssertionsMatched: Boolean,
    val problems: List<String>,
    val selectedCounts: List<Map<String, Any?>>,
) {
    fun description(): Map<String, Any?> = mapOf(
        "expected_outcome_matched" to expectedOutcomeMatched,
        "known_assertions_matched" to knownAssertionsMatched,
        "problems" to problems, "selected_observations" to selectedCounts,
        "ordinary_six_kind_assessment" to false, "counts_as_current_test_or_implementation_evidence" to false,
        "qualification" to "none", "source_plan_rewritten" to false,
    )
}

/** Pure accounting over separately retained actual parser and provider returns; not an emission simulator. */
internal object CompilerCurrent445FixtureOracle {
    private data class PointMessage(
        val ruleId: String, val upstreamId: String?, val severity: String, val message: String, val point: SourceLocation?,
    )
    private fun <T> multiset(values: List<T>): Map<T, Int> = values.groupingBy { it }.eachCount()

    fun assess(c: CompilerCurrent445Case, parsed: CompilerParseResult, result: ProviderResult,
        process: ProcessOutcome, registry: CompilerRuleRegistry): CompilerCurrent445Assessment {
        val problems = mutableListOf<String>()
        fun demand(ok: Boolean, code: String) { if (!ok) problems += code }
        val trusted = process.successfulExecution && parsed.reportedVersion == CompilerRuleRegistry.VERSION
        demand(trusted, "execution_or_version_not_trusted")
        demand(process.exitCode != null, "compiler_exit_unavailable")
        demand(!process.cancelled && !process.timedOut && !process.truncated, "cancelled_timed_out_or_truncated")
        demand("process_output_invalid_utf8" !in process.reasons, "raw_bytes_unavailable_invalid_utf8")
        demand(process.reasons.isEmpty(), "process_transport_or_verification_problem")
        demand(!parsed.malformed && parsed.rawDiagnostics.isEmpty(), "unparsed_or_malformed_output")
        demand(result.providerId == CompilerRuleRegistry.PROVIDER_ID &&
            result.providerVersion == CompilerRuleRegistry.VERSION, "provider_identity_mismatch")
        demand(!result.complete() && result.status != "complete" &&
            result.coverage.none { it.tested || it.qualified || it.successful() || it.certificateId != null },
            "synthetic_observation_cannot_promote_coverage")
        demand(result.coverage.size == CompilerCurrent445FixtureContract.RULE_IDS.size &&
            result.coverage.map { it.ruleId }.toSet() == CompilerCurrent445FixtureContract.RULE_IDS.toSet(),
            "selected_coverage_membership_mismatch")
        val expectedCoverageState = if (!trusted || process.exitCode != 0 || result.rawDiagnostics.isNotEmpty()) "failed" else "unknown"
        demand(result.coverage.all { it.state == expectedCoverageState }, "synthetic_coverage_must_remain_unknown_or_failed")
        demand(result.status == if (process.exitCode == 0) "partial" else "failed", "normalized_provider_status_inconsistent")

        // Exact residual join: provider.rawDiagnostics is not the full emission inventory.
        val residual = parsed.rawDiagnostics.toMutableList()
        for (diagnostic in parsed.diagnostics) {
            val mapping = registry.byUpstreamId[diagnostic.upstreamId]
            val selected = mapping?.ruleId in CompilerCurrent445FixtureContract.RULE_IDS
            if (!trusted || mapping == null || !mapping.jvmApplicable || diagnostic.location == null || !selected) {
                residual += RawDiagnostic(diagnostic.message, diagnostic.severity, diagnostic.upstreamId, diagnostic.location)
                val reason = when {
                    mapping == null -> "unmapped_compiler_diagnostic:" + diagnostic.upstreamId
                    !trusted || !mapping.jvmApplicable || diagnostic.location == null ->
                        "untrusted_or_wrong_target_compiler_diagnostic:" + diagnostic.upstreamId
                    else -> "mapped_diagnostic_outside_selected_rules:" + mapping.ruleId
                }
                demand(reason in result.reasons, "missing_residual_reason:" + diagnostic.upstreamId)
            }
            // Unexpected warnings/IDs are retained too; never an alias or a way to rescue a known-case oracle.
            demand(selected && diagnostic.upstreamId in CompilerCurrent445FixtureContract.UPSTREAM_IDS,
                "unexpected_diagnostic:" + diagnostic.upstreamId)
        }
        demand(multiset(residual) == multiset(result.rawDiagnostics), "full_parser_provider_residual_mismatch")
        demand(result.findings.all { finding -> c.expectations.any {
            it.ruleId == finding.ruleId && it.upstreamId == finding.upstreamId
        } }, "foreign_or_mispaired_selected_finding")

        demand(result.findings.all { finding -> registry.byRuleId[finding.ruleId]?.let { mapping ->
            finding.kind == mapping.kind && finding.certainty == mapping.certainty
        } == true }, "normalized_classification_mismatch")
        val counts = c.expectations.map { expectation ->
            val full = parsed.diagnostics.filter { it.upstreamId == expectation.upstreamId }
            val selected = result.findings.filter { it.ruleId == expectation.ruleId }
            val parserPoints = full.map {
                PointMessage(expectation.ruleId, it.upstreamId, it.severity, it.message, it.location)
            }
            val selectedPoints = selected.map { PointMessage(it.ruleId, it.upstreamId, it.severity, it.diagnosis, it.location) }
            val equal = multiset(parserPoints) == multiset(selectedPoints)
            demand(equal, "selected_point_message_multiset_mismatch:" + expectation.upstreamId)
            val validPoints = (parserPoints + selectedPoints).all { point ->
                point.upstreamId == expectation.upstreamId && point.severity == expectation.severity &&
                    point.point?.let { location ->
                        location.path == c.path && location.fileDigest == "sha256:" + c.sourceSha256 &&
                            location.line == location.endLine && location.column == location.endColumn &&
                            expectation.regions.any { region ->
                                location.line == region.line && location.column in region.start until region.endExclusive
                            }
                    } == true
            }
            demand(validPoints, "wrong_severity_source_or_actual_point:" + expectation.upstreamId)
            val countMatched = expectation.count?.let { count ->
                full.size == count && selected.size == count && equal && validPoints
            }
            if (countMatched == false) problems += "known_count_mismatch:" + expectation.upstreamId
            mapOf("rule_id" to expectation.ruleId, "upstream_id" to expectation.upstreamId,
                "expected_full_parser_count" to expectation.count, "expected_selected_finding_count" to expectation.count,
                "actual_full_parser_count" to full.size, "actual_selected_finding_count" to selected.size,
                "point_message_multiset_equal" to equal, "actual_points_within_source_regions" to validPoints,
                "known_count_matched" to countMatched,
                "null_is_unknown_not_a_successful_match" to (expectation.count == null))
        }
        when (c.exitExpectation) {
            "exit0" -> demand(process.exitCode == 0, "expected_zero_exit")
            "nonzero" -> demand(process.exitCode != null && process.exitCode != 0, "expected_nonzero_exit")
            "unknown" -> demand(c.observationOnly, "unknown_exit_outside_observation_case")
            else -> problems += "unsupported_exit_oracle"
        }
        if (!c.observationOnly) demand(result.status == c.statusExpectation, "provider_status_mismatch")
        demand(process.exitCode == null || process.exitCode == 0 || result.status == "failed", "compiler_failure_hidden")
        val knownMatched = problems.isEmpty()
        return CompilerCurrent445Assessment(
            // Even an exit0 and a matched warning cannot settle unsupported hard-error suppression.
            if (c.observationOnly) null else knownMatched, knownMatched, problems.distinct(), counts)
    }

    fun streamRetention(process: ProcessOutcome): Map<String, Any?> = mapOf(
        "api_boundary" to "ProcessOutcome.stdout_and_stderr_Strings_not_native_raw_byte_arrays",
        "captured_representation" to "UTF8_reencoding_of_exact_returned_runtime_Strings",
        "source_plan_raw_byte_retention_capability_resolved" to false,
        "invalid_utf8_raw_bytes_available" to false,
        "actual_invalid_utf8_reported" to ("process_output_invalid_utf8" in process.reasons),
        "valid_utf8_round_trip_only" to ("process_output_invalid_utf8" !in process.reasons),
        "truncated" to process.truncated,
        "unresolved_requirement" to "source-v1_every_actual_stdout_stderr_byte_including_malformed_output",
        "scope_reduction_or_qualification_authority" to false,
    )

    fun parserDescription(parsed: CompilerParseResult): Map<String, Any?> = mapOf(
        "inventory_kind" to "full_parser_output_separate_from_provider_residual_rawDiagnostics",
        "reported_version" to parsed.reportedVersion, "malformed" to parsed.malformed,
        "diagnostics" to parsed.diagnostics.map { mapOf("upstream_id" to it.upstreamId, "severity" to it.severity,
            "message" to it.message, "rendered_phase" to it.renderedPhase,
            "location" to it.location?.let { point -> mapOf("path" to point.path, "file_digest" to point.fileDigest,
                "line" to point.line, "column" to point.column, "end_line" to point.endLine, "end_column" to point.endColumn) }) },
        "raw_diagnostics" to parsed.rawDiagnostics.map { mapOf("message" to it.message, "severity" to it.severity,
            "upstream_id" to it.upstreamId, "location" to it.location?.let { point ->
                mapOf("path" to point.path, "file_digest" to point.fileDigest, "line" to point.line,
                    "column" to point.column, "end_line" to point.endLine, "end_column" to point.endColumn) }) },
        "points_are_reported_points_not_expected_token_spans" to true,
    )
}

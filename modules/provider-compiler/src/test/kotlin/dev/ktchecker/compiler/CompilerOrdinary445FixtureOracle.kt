package dev.ktchecker.compiler

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.ProcessOutcome

internal data class CompilerOrdinary445Assessment(
    val receiptTrusted: Boolean, val diagnosticExpectedOutcomeMatched: Boolean?,
    val safetyControlMatched: Boolean?, val problems: List<String>, val selectedCount: Int,
    val residualCount: Int,
) {
    val knownAssertionsMatched: Boolean get() = receiptTrusted && diagnosticExpectedOutcomeMatched != false && safetyControlMatched != false
    fun invalidate(reason: String): CompilerOrdinary445Assessment = copy(receiptTrusted = false,
        diagnosticExpectedOutcomeMatched = diagnosticExpectedOutcomeMatched?.let { false },
        safetyControlMatched = safetyControlMatched?.let { false }, problems = (problems + reason).distinct())
    fun failBehavior(reason: String): CompilerOrdinary445Assessment = copy(
        diagnosticExpectedOutcomeMatched = diagnosticExpectedOutcomeMatched?.let { false }, problems = (problems + reason).distinct())
    fun description(): Map<String, Any?> = mapOf("receipt_trusted" to receiptTrusted,
        "diagnostic_expected_outcome_matched" to diagnosticExpectedOutcomeMatched,
        "non_complete_safety_control_matched" to safetyControlMatched, "known_assertions_matched" to knownAssertionsMatched,
        "problems" to problems, "selected_finding_count" to selectedCount, "residual_raw_diagnostic_count" to residualCount,
        "null_diagnostic_expectation_is_unknown_not_pass" to true, "qualified" to false)
}
internal data class CompilerOrdinary445Point(val ruleId: String, val upstreamId: String?, val severity: String,
    val message: String, val location: SourceLocation?)
internal data class CompilerOrdinary445Stamp(
    val jobId: String, val snapshotId: String, val analysisKey: String, val outputDirectory: String,
    val sourcePath: String, val sourceText: String, val points: List<CompilerOrdinary445Point>,
)

/** Pure independent parser/provider accounting, never a synthetic emission producer. */
internal object CompilerOrdinary445FixtureOracle {
    private fun <T> multiset(values: List<T>): Map<T, Int> = values.groupingBy { it }.eachCount()
    private fun pointValid(point: SourceLocation?, sources: Map<String, String>): Boolean {
        if (point == null) return false
        val source = sources[point.path] ?: return false
        val line = source.split('\n').getOrNull(point.line - 1)?.removeSuffix("\r") ?: return false
        return point.fileDigest == Digests.sha256(source) && point.column in 0..line.length &&
            point.endLine == point.line && point.endColumn == point.column
    }
    fun assess(job: CompilerOrdinary445Job, parsed: CompilerParseResult, result: ProviderResult,
        process: ProcessOutcome, registry: CompilerRuleRegistry): CompilerOrdinary445Assessment {
        require(!job.refusal)
        val integrity = mutableListOf<String>(); val behavior = mutableListOf<String>()
        fun demand(ok: Boolean, code: String) { if (!ok) integrity += code }
        fun expect(ok: Boolean, code: String) { if (!ok) behavior += code }
        val trusted = process.successfulExecution && parsed.reportedVersion == CompilerRuleRegistry.VERSION
        demand(trusted, "execution_or_version_untrusted")
        demand(process.exitCode != null, "compiler_exit_unavailable")
        demand(!process.cancelled && !process.timedOut && !process.truncated, "cancelled_timed_out_or_truncated")
        demand("process_output_invalid_utf8" !in process.reasons, "native_invalid_utf8_bytes_unavailable")
        demand(process.reasons.isEmpty(), "process_transport_or_verification_problem")
        demand(!parsed.malformed && parsed.rawDiagnostics.isEmpty(), "unparsed_or_malformed_output")
        demand(result.providerId == CompilerRuleRegistry.PROVIDER_ID && result.providerVersion == CompilerRuleRegistry.VERSION,
            "provider_identity_mismatch")
        demand(result.status == if (process.exitCode == 0) "partial" else "failed", "compiler_failure_hidden_or_status_inconsistent")
        demand(!result.complete() && result.status != "complete" &&
            result.coverage.none { it.tested || it.qualified || it.successful() || it.certificateId != null }, "synthetic_fixture_cannot_qualify")
        demand(result.coverage.size == job.ruleIds.size && result.coverage.map { it.ruleId }.toSet() == job.ruleIds.toSet(),
            "selected_coverage_membership_mismatch")
        val coverageState = if (!trusted || process.exitCode != 0 || result.rawDiagnostics.isNotEmpty()) "failed" else "unknown"
        demand(result.coverage.all { it.state == coverageState && it.provenance == "executed" }, "coverage_failure_or_unknown_hidden")
        demand(result.coverage.all { it.implemented == (trusted && result.findings.any { f -> f.ruleId == it.ruleId }) },
            "mapping_row_cannot_invent_emission_implementation")
        val residual = parsed.rawDiagnostics.toMutableList()
        val selectedParser = mutableListOf<CompilerOrdinary445Point>()
        for (diagnostic in parsed.diagnostics) {
            val mapping = registry.byUpstreamId[diagnostic.upstreamId]
            if (!trusted || mapping == null || !mapping.jvmApplicable || diagnostic.location == null || mapping.ruleId !in job.ruleIds) {
                residual += RawDiagnostic(diagnostic.message, diagnostic.severity, diagnostic.upstreamId, diagnostic.location)
                val reason = when {
                    mapping == null -> "unmapped_compiler_diagnostic:" + diagnostic.upstreamId
                    !trusted || !mapping.jvmApplicable || diagnostic.location == null -> "untrusted_or_wrong_target_compiler_diagnostic:" + diagnostic.upstreamId
                    else -> "mapped_diagnostic_outside_selected_rules:" + mapping.ruleId
                }
                demand(reason in result.reasons, "missing_residual_reason:" + diagnostic.upstreamId)
            } else selectedParser += CompilerOrdinary445Point(mapping.ruleId, diagnostic.upstreamId,
                diagnostic.severity, diagnostic.message, diagnostic.location)
        }
        // Companion diagnostics are retained, not aliases or silently selected findings. In a
        // positive case they need not erase a real selected emission; a negative must have NO residuals.
        demand(multiset(residual) == multiset(result.rawDiagnostics), "full_parser_provider_residual_multiset_mismatch")
        val selected = result.findings.map { CompilerOrdinary445Point(it.ruleId, it.upstreamId, it.severity, it.diagnosis, it.location) }
        demand(multiset(selectedParser) == multiset(selected), "selected_point_message_multiset_mismatch")
        demand(selected.all { point -> point.ruleId in job.ruleIds &&
            registry.byRuleId[point.ruleId]?.upstreamId == point.upstreamId && pointValid(point.location, job.sources) },
            "foreign_rule_source_digest_or_reported_point")
        demand(result.findings.all { f -> registry.byRuleId[f.ruleId]?.let { f.kind == it.kind && f.certainty == it.certainty } == true },
            "normalized_classification_mismatch")
        val fixture = checkNotNull(job.fixture)
        val expectedSeverity = if (job.control == "configuration" && fixture.severity == "warning") "error" else fixture.severity
        fun emission() {
            expect(selected.isNotEmpty() && selected.all { it.upstreamId == fixture.upstreamId && it.severity == expectedSeverity },
                "expected_selected_emission_or_severity_missing")
            expect(if (expectedSeverity == "error") process.exitCode != null && process.exitCode != 0 else process.exitCode == 0,
                "expected_error_failure_or_warning_success_missing")
        }
        var safety: Boolean? = null
        when (job.control) {
            "positive", "configuration", "version", "regression" -> emission()
            "negative", "warning_disabled" -> {
                expect(process.exitCode == 0 && selected.isEmpty() && result.rawDiagnostics.isEmpty() && parsed.diagnostics.isEmpty(),
                    "negative_requires_actual_zero_exit_and_no_selected_or_raw_diagnostics")
                if (job.control == "warning_disabled") expect(result.coverage.singleOrNull()?.reason ==
                    "compiler_warning_disabled_under_selected_configuration", "warning_disabled_reason_missing")
            }
            "suppression" -> {
                val safe = "compiler_source_suppression_requires_scoped_coverage_evidence" in result.reasons &&
                    !result.complete() && result.coverage.none { it.successful() || it.tested || it.qualified } &&
                    (process.exitCode == 0 || result.status == "failed")
                safety = integrity.isEmpty() && safe
                expect(safe, "source_suppression_non_complete_safety_control_failed")
                if (!job.hardErrorSuppression) {
                    expect(process.exitCode == 0 && selected.isNotEmpty() &&
                        selected.none { it.location?.path == job.primaryPath } &&
                        selected.all { it.location?.path == job.unsuppressedPath && it.severity == "warning" },
                        "scoped_warning_suppression_or_adjacent_control_failed")
                }
                // No guessed diagnostic count, absence or compiler exit expectation for hard errors.
            }
            else -> behavior += "unknown_ordinary_control"
        }
        if (job.control == "version") expect(job.flags.takeLast(4) == listOf("-language-version", "2.4", "-api-version", "2.4"),
            "explicit_language_api_control_missing")
        if (job.control == "configuration") expect(if (fixture.severity == "warning")
            "-Xwarning-level=${fixture.upstreamId}:error" in job.flags else
            job.flags.getOrNull(job.flags.indexOf("-jvm-target") + 1) == "21", "configuration_control_missing")
        val matched = integrity.isEmpty() && behavior.isEmpty()
        return CompilerOrdinary445Assessment(integrity.isEmpty(), if (job.hardErrorSuppression) null else matched,
            safety, (integrity + behavior).distinct(), selected.size, residual.size)
    }
    fun assessRefusal(job: CompilerOrdinary445Job, input: AnalysisInput, result: ProviderResult,
        observedProcesses: Int, outputRegistered: Boolean): CompilerOrdinary445Assessment {
        val reason = "compiler_version_not_pinned_2_4_21"
        val matched = job.refusal && job.requestedVersion == "2.4.20" && input.context.compilerVersion == "2.4.20" &&
            input.execution == null && input.requiredRuleIds == job.ruleIds && observedProcesses == 0 && !outputRegistered &&
            result.providerId == CompilerRuleRegistry.PROVIDER_ID && result.providerVersion == CompilerRuleRegistry.VERSION &&
            result.status == "unavailable" && result.reasons == listOf(reason) && result.findings.isEmpty() && result.rawDiagnostics.isEmpty() &&
            result.coverage.size == job.ruleIds.size && result.coverage.map { it.ruleId }.toSet() == job.ruleIds.toSet() &&
            result.coverage.all { it.state == "unsupported" && it.reason == reason && !it.implemented && !it.tested && !it.qualified &&
                !it.successful() && it.certificateId == null && it.context == input.context.scope }
        return CompilerOrdinary445Assessment(matched, matched, null,
            if (matched) emptyList() else listOf("exact_pre_runtime_version_refusal_not_observed"), 0, result.rawDiagnostics.size)
    }
    /** Requires every selected occurrence, not just the earliest point or a set that erases duplicates. */
    fun regressionMatches(before: CompilerOrdinary445Stamp?, after: CompilerOrdinary445Stamp): Boolean {
        if (before == null || before.points.isEmpty() || after.points.isEmpty()) return false
        if (after.sourceText != "\n\n" + before.sourceText || after.sourcePath == before.sourcePath ||
            after.snapshotId == before.snapshotId || after.analysisKey == before.analysisKey || after.outputDirectory == before.outputDirectory ||
            Digests.sha256(after.sourceText) == Digests.sha256(before.sourceText)) return false
        if (!before.points.all { pointValid(it.location, mapOf(before.sourcePath to before.sourceText)) } ||
            !after.points.all { pointValid(it.location, mapOf(after.sourcePath to after.sourceText)) }) return false
        val shifted = before.points.map { p -> p.copy(location = checkNotNull(p.location).let {
            it.copy(path = after.sourcePath, fileDigest = Digests.sha256(after.sourceText), line = it.line + 2, endLine = it.endLine + 2)
        }) }
        return multiset(shifted) == multiset(after.points)
    }
    fun streamRetention(process: ProcessOutcome): Map<String, Any?> = mapOf(
        "api_boundary" to "ProcessOutcome.stdout_and_stderr_Strings_not_native_raw_byte_arrays",
        "captured_representation" to "UTF8_reencoding_of_exact_returned_runtime_Strings",
        "native_invalid_utf8_raw_bytes_available" to false,
        "actual_invalid_utf8_reported" to ("process_output_invalid_utf8" in process.reasons),
        "valid_utf8_round_trip_only" to ("process_output_invalid_utf8" !in process.reasons), "truncated" to process.truncated,
        "invalid_or_truncated_streams_can_receive_credit" to false, "historical_nine_observation_raw_capability_hold_changed" to false)
    fun parserDescription(parsed: CompilerParseResult): Map<String, Any?> = mapOf(
        "inventory_kind" to "full_parser_output_separate_from_provider_residual_rawDiagnostics",
        "reported_version" to parsed.reportedVersion, "malformed" to parsed.malformed,
        "diagnostics" to parsed.diagnostics.map { mapOf("upstream_id" to it.upstreamId, "severity" to it.severity,
            "message" to it.message, "rendered_phase" to it.renderedPhase, "location" to pointDescription(it.location)) },
        "raw_diagnostics" to parsed.rawDiagnostics.map { mapOf("upstream_id" to it.upstreamId, "severity" to it.severity,
            "message" to it.message, "location" to pointDescription(it.location)) },
        "points_are_actual_reported_points_not_expected_spans" to true)
    private fun pointDescription(point: SourceLocation?): Map<String, Any?>? = point?.let { mapOf("path" to it.path,
        "file_digest" to it.fileDigest, "line" to it.line, "column" to it.column, "end_line" to it.endLine, "end_column" to it.endColumn) }
}

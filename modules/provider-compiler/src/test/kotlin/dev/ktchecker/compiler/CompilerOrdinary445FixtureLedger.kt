package dev.ktchecker.compiler

// CompilerFixtureEvidence.reference emits raw lowercase SHA-256, NOT the
// sha256:-prefixed contracts.Digests identifier. Require its exact closed schema;
// never normalize a mismatched reference into attributable evidence.
private val immutableEvidenceSha256 = Regex("[0-9a-f]{64}")

/** Candidate facts are inert until the entire custody/publication chain and outer command settle. */
internal data class CompilerOrdinary445Fact(
    val jobId: String, val ruleIds: List<String>, val kind: String, val control: String,
    val persisted: Boolean, val receiptInvariants: Boolean, val cleaned: Boolean,
    val assessment: CompilerOrdinary445Assessment?, val evidence: Map<String, String>?,
) {
    val attributable: Boolean get() = persisted && receiptInvariants && cleaned && assessment?.receiptTrusted == true &&
        evidence?.let { it.keys == setOf("path", "sha256") && immutableEvidenceSha256.matches(it.getValue("sha256")) &&
            runCatching { CompilerOrdinary445FixtureContract.relativePath(it.getValue("path")) }.isSuccess } == true
}
internal object CompilerOrdinary445FixtureLedger {
    fun rows(fixtures: List<CompilerOrdinary445Fixture>, facts: List<CompilerOrdinary445Fact>, settled: Boolean): List<Map<String, Any?>> {
        require(fixtures.map { it.ruleId }.toSet().size == fixtures.size && facts.map { it.jobId }.toSet().size == facts.size)
        return fixtures.map { fixture ->
            val selected = facts.filter { fixture.ruleId in it.ruleIds }
            val byControl = selected.associateBy { it.control }
            val ordinaryControls = listOf("positive", "negative", "configuration", "version", "suppression", "regression", "requested_version_refusal") +
                if (fixture.severity == "warning") listOf("warning_disabled") else emptyList()
            val completeRoster = selected.size == ordinaryControls.size && byControl.keys == ordinaryControls.toSet() &&
                selected.all { it.kind == when (it.control) {
                    "warning_disabled" -> "configuration"
                    "requested_version_refusal" -> "version"
                    else -> it.control
                } }
            val custody = settled && completeRoster && selected.all { it.attributable }
            fun group(kind: String): List<CompilerOrdinary445Fact> = (when (kind) {
                "configuration" -> listOf("positive", "configuration") + if (fixture.severity == "warning") listOf("warning_disabled") else emptyList()
                "version" -> listOf("version", "requested_version_refusal")
                "suppression" -> listOf("positive", "suppression")
                "regression" -> listOf("positive", "regression")
                else -> listOf(kind)
            }).mapNotNull(byControl::get)
            val kinds = CompilerOrdinary445FixtureContract.CASE_KINDS.map { kind ->
                val jobs = group(kind)
                val known = if (!custody) null else when {
                    jobs.any { it.assessment?.diagnosticExpectedOutcomeMatched == false || it.assessment?.safetyControlMatched == false } -> false
                    jobs.any { it.assessment?.diagnosticExpectedOutcomeMatched == null } -> null
                    else -> true
                }
                mapOf("kind" to kind, "diagnostic_expected_outcome_matched" to known,
                    "non_complete_safety_control_matched" to if (custody && kind == "suppression") byControl[kind]?.assessment?.safetyControlMatched else null,
                    "job_ids" to jobs.map { it.jobId }, "job_evidence" to jobs.mapNotNull { it.evidence },
                    "qualified" to false)
            }
            val implemented: Boolean? = if (custody) byControl["positive"]?.assessment?.diagnosticExpectedOutcomeMatched else null
            val tested: Boolean? = if (!custody || implemented != true) null else when {
                kinds.any { it["diagnostic_expected_outcome_matched"] == false } -> false
                kinds.any { it["diagnostic_expected_outcome_matched"] == null } -> null
                else -> true
            }
            mapOf("rule_id" to fixture.ruleId, "upstream_id" to fixture.upstreamId, "declared_severity" to fixture.severity,
                "local_synthetic_implemented" to implemented, "local_six_kind_tested" to tested, "qualified" to false,
                "six_kind_receipts" to kinds, "custody_and_terminal_publication_settled" to custody,
                "hard_error_suppression_diagnostic_expectation_unknown" to (fixture.severity == "error"),
                "ktchecker_accepted_risk_owner_workflow_exercised" to false,
                "whole_context_or_global_coverage_credit" to false, "activation_changed" to false,
                "stage_scope" to "This synthetic fixture route only; successful exact outer command and independent build review also required.")
        }
    }
}

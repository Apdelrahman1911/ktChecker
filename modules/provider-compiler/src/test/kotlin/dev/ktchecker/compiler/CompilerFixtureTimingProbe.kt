package dev.ktchecker.compiler

import java.nio.file.Path
import kotlinx.serialization.json.*

/** Test-host-only bounded observations, not an ordinary fixture cohort or production provider mode. */
object CompilerFixtureTimingProbe {
    @JvmStatic fun main(args: Array<String>) = CompilerFixtureRunner.runTimingProbe(args)
}

internal enum class CompilerFixtureProbeSource(val label: String, val capturedPath: String) {
    POSITIVE("positive", "cases/unresolved_reference/positive.kt"),
    CLEAN("clean", "cases/unresolved_reference/negative.kt"),
}

internal data class CompilerFixtureProbeSample(val number: Int, val source: CompilerFixtureProbeSource) {
    val label: String get() = "timing-probe-$number-${source.label}"
    fun description(): Map<String, Any> = mapOf("sample_number" to number, "source_kind" to source.label,
        "job_label" to label, "captured_source_path" to source.capturedPath, "discarded_as_warmup" to false)
}

/** Value-only gate fed by the real runner; these values alone confer no execution or cleanup proof. */
internal data class CompilerFixtureProbeObservation(
    val sample: CompilerFixtureProbeSample,
    val attemptNumber: Int,
    val executionConfirmed: Boolean,
    val successfulSupervisedExecution: Boolean,
    val expectedOutcomeMatched: Boolean,
    val receiptsIntact: Boolean,
    val timingObservationsComplete: Boolean,
) {
    val eligible: Boolean get() = executionConfirmed && successfulSupervisedExecution && expectedOutcomeMatched &&
        receiptsIntact && timingObservationsComplete

    fun description(): Map<String, Any> = sample.description() + mapOf(
        "reserved_attempt_number" to attemptNumber, "execution_confirmed" to executionConfirmed,
        "successful_supervised_execution" to successfulSupervisedExecution,
        "expected_sample_outcome_matched" to expectedOutcomeMatched, "receipt_invariants_intact" to receiptsIntact,
        "timing_observations_complete" to timingObservationsComplete, "sample_candidate_eligible" to eligible,
        "ordinary_six_kind_assessment" to false, "qualification" to "none")
}

/** Fixed trusted scheduling only. A variant label is owner metadata, never source/bytecode identity. */
internal class CompilerFixtureTimingProbeSpec private constructor(val fixtureDirectory: String, val variant: String) {
    fun settings(): CompilerFixtureSettings = CompilerFixtureSettings(fixtureDirectory, "original",
        maxAttempts = SAMPLE_COUNT, overallMillis = 300_000, perJobMillis = 60_000, evidenceBytes = 134_217_728)

    fun samples(): List<CompilerFixtureProbeSample> = (1..SAMPLE_COUNT).map { number ->
        CompilerFixtureProbeSample(number, if (number % 2 == 1) CompilerFixtureProbeSource.POSITIVE else CompilerFixtureProbeSource.CLEAN)
    }

    /** One callback per ordinal, including sample1. Exceptions stop the schedule; there is no retry/fallback. */
    fun forEachSample(action: (CompilerFixtureProbeSample) -> Unit) = samples().forEach(action)

    fun description(): Map<String, Any> = mapOf(
        "schema" to "ktchecker/compiler-scanner-timing-probe-1", "schedule_id" to "scanner_eight_v1",
        "variant_label" to variant,
        "variant_label_scope" to "owner_metadata_only; independently inspect START sources and actual executed artifact identities",
        "rule_id" to RULE_ID, "upstream_id" to UPSTREAM_ID, "flags" to FLAGS,
        "samples" to samples().map { it.description() }, "selected_kotlin_files_per_job" to 1,
        "retains_first_sample" to true, "discarded_warmup_samples" to 0, "replacement_samples" to 0,
        "ordinary_six_kind_assessments" to "not_run_outside_probe_schedule",
        "intent_and_transport_controls" to "not_run_outside_probe_schedule",
        "ordinary_inventory_selector" to "original is retained for missing-kind bookkeeping only; never ordinary scheduling",
        "bounded_invocation" to settings().description(), "compiler_heap_mib" to COMPILER_HEAP_MIB,
        "fixture_host_heap_mib" to 384, "maximum_job_output_bytes" to 2_097_152,
        "maximum_timing_rows_per_job" to CompilerPhaseTimings.MAX_CAPACITY,
        "maximum_timing_sidecar_bytes_per_job" to CompilerFixtureTimingEvidence.MAX_BYTES,
        "publication_scope" to "private run only; no ordinary external latest sidecar",
        "freshness_scope" to "fresh snapshot directory, output and exact permit per call; equal source bytes may retain equal content-addressed IDs",
        "completion_scope" to "eight expected actual outcomes plus intact provenance/lifecycle/timing and terminal publication/harness return; never ordinary fixture completion",
        "qualification" to "none; observations only, not latency/SLO/release or model-composition evidence")

    fun expectedOutcomeMatches(sample: CompilerFixtureProbeSample, successfulSupervisedExecution: Boolean,
        exitCode: Int?, providerStatus: String, exactSealedEmission: Boolean, cleanAbsence: Boolean): Boolean {
        if (!successfulSupervisedExecution) return false
        return when (sample.source) {
            CompilerFixtureProbeSource.POSITIVE -> exitCode != null && exitCode != 0 && providerStatus == "failed" && exactSealedEmission
            CompilerFixtureProbeSource.CLEAN -> exitCode == 0 && providerStatus == "partial" && cleanAbsence
        }
    }

    /** Only sample eligibility. Shared terminal provenance, cleanup, clock and publication gates still apply. */
    fun samplesEligible(attempts: List<CompilerFixtureAttempt>, observations: List<CompilerFixtureProbeObservation>,
        actualJobCount: Int, uniqueOutputCount: Int, orphanedOutcomeCount: Int): Boolean {
        val schedule = samples()
        return actualJobCount == SAMPLE_COUNT && uniqueOutputCount == SAMPLE_COUNT && orphanedOutcomeCount == 0 &&
            attempts.size == SAMPLE_COUNT && attempts.map { it.number } == schedule.map { it.number } &&
            attempts.map { it.label } == schedule.map { it.label } && attempts.all { it.ruleIds == setOf(RULE_ID) } &&
            observations.map { it.sample } == schedule && observations.all { it.attemptNumber == it.sample.number && it.eligible }
    }

    companion object {
        const val SAMPLE_COUNT = 8
        const val COMPILER_HEAP_MIB = 512
        const val RULE_ID = "ktc.compiler.common.unresolved_reference"
        const val UPSTREAM_ID = "UNRESOLVED_REFERENCE"
        val FLAGS: List<String> get() = listOf("-jvm-target", "17")
        private val VARIANTS = setOf("instrumented-baseline", "scan-buffer-hex")

        /** Deliberately not the ordinary parser: cohort/ID/budget overrides are rejected, never ignored. */
        fun parse(args: Array<String>): CompilerFixtureTimingProbeSpec {
            var variant: String? = null
            val directories = mutableListOf<String>()
            for (argument in args) {
                if (!argument.startsWith("--")) { directories += argument; continue }
                require(argument.startsWith("--variant=")) { "Only --variant is accepted by the fixed timing probe" }
                require(variant == null) { "Duplicate timing-probe variant" }
                variant = argument.removePrefix("--variant=")
                require(variant in VARIANTS) { "Use --variant=instrumented-baseline or --variant=scan-buffer-hex" }
            }
            require(directories.size == 1) { "Exactly one fixture root is required" }
            return CompilerFixtureTimingProbeSpec(directories.single(), checkNotNull(variant) { "Explicit --variant is required" }).also {
                it.settings() // Preserve the ordinary host's bounded directory validation without parsing selectors.
            }
        }

        // This legacy-path shape is checked against actual retained rows, never populated as invented observations.
        private val LEGACY_PHASE_COUNTS = CompilerPhase.entries.filterNot { it == CompilerPhase.MODEL_BINDING_VERIFICATION }
            .associate { phase -> phase.label to when (phase) {
                CompilerPhase.SNAPSHOT_VERIFICATION, CompilerPhase.JDK_RUNTIME_VERIFICATION,
                CompilerPhase.COMPILER_CLASSPATH_VERIFICATION, CompilerPhase.DEPENDENCY_CLASSPATH_VERIFICATION -> 3
                CompilerPhase.WRAPPED_PERMIT_VERIFICATION -> 2
                else -> 1
            } }

        /** Validate the persisted A1 sidecar, independently of actual execution/outcome eligibility. */
        fun timingObservationsComplete(bytes: ByteArray?, providerElapsedMillis: Long, rawRunnerElapsedMillis: Long?): Boolean {
            if (bytes == null || bytes.size > CompilerFixtureTimingEvidence.MAX_BYTES || providerElapsedMillis < 0 ||
                rawRunnerElapsedMillis == null || rawRunnerElapsedMillis < 0) return false
            return try {
                val document = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                val rows = document.getValue("phase_intervals").jsonArray.map { it.jsonObject }
                if (document.getValue("schema").jsonPrimitive.content != "ktchecker/compiler-phase-timings-1" ||
                    document.getValue("capacity").jsonPrimitive.int != CompilerPhaseTimings.MAX_CAPACITY ||
                    rows.size !in 1..CompilerPhaseTimings.MAX_CAPACITY ||
                    document.getValue("recorded_phase_count").jsonPrimitive.int != rows.size ||
                    document.getValue("dropped_phase_count").jsonPrimitive.long != 0L ||
                    listOf("clock_valid", "nesting_valid", "all_recorded_actions_finished", "observations_complete").any {
                        !document.getValue(it).jsonPrimitive.boolean
                    } || document.getValue("provider_reported_elapsed_millis").jsonPrimitive.long != providerElapsedMillis ||
                    document.getValue("raw_runner_elapsed_millis").jsonPrimitive.long != rawRunnerElapsedMillis ||
                    rows.groupingBy { it.getValue("phase").jsonPrimitive.content }.eachCount() != LEGACY_PHASE_COUNTS) return false
                val occurrences = mutableMapOf<String, Int>()
                rows.withIndex().all { (index, row) ->
                    val phase = row.getValue("phase").jsonPrimitive.content
                    val occurrence = (occurrences[phase] ?: 0) + 1
                    occurrences[phase] = occurrence
                    val parentValue = row.getValue("parent_sequence")
                    val parent = if (parentValue == JsonNull) null else parentValue.jsonPrimitive.int
                    val verificationValue = row.getValue("verification_occurrence")
                    val verification = if (verificationValue == JsonNull) null else verificationValue.jsonPrimitive.int
                    val start = row.getValue("start_nanos").jsonPrimitive.long
                    val end = row.getValue("end_nanos").jsonPrimitive.long
                    val parentRow = parent?.takeIf { it in 1..index }?.let { rows[it - 1] }
                    val expectedVerification = when (phase) {
                        CompilerPhase.INITIAL_VERIFICATION.label -> JsonPrimitive(0)
                        CompilerPhase.WRAPPED_PERMIT_VERIFICATION.label -> JsonPrimitive(occurrence)
                        else -> parentRow?.getValue("verification_occurrence") ?: JsonNull
                    }
                    row.getValue("sequence").jsonPrimitive.int == index + 1 &&
                        row.getValue("occurrence").jsonPrimitive.int == occurrence &&
                        start >= 0 && end >= start && row.getValue("elapsed_nanos").jsonPrimitive.long == end - start &&
                        row.getValue("action_returned_normally").jsonPrimitive.booleanOrNull != null &&
                        verificationValue == expectedVerification && (verification == null || verification in 0..2) &&
                        if (index == 0) phase == CompilerPhase.PROVIDER_CALL.label && parent == null && start == 0L
                        else parentRow != null && start >= parentRow.getValue("start_nanos").jsonPrimitive.long &&
                            end <= parentRow.getValue("end_nanos").jsonPrimitive.long
                }
            } catch (_: Exception) { false }
        }
    }
}

/** Both evidence-store admission and later writes use the same destination decision. */
internal fun compilerFixtureLatestSidecar(fixtureRoot: Path, probe: CompilerFixtureTimingProbeSpec?): Path? =
    if (probe == null) fixtureRoot.resolve("evidence/compiler-jvm-${CompilerRuleRegistry.VERSION}.json") else null

/** Even a settled probe selector must not label an ordinary six-kind fixture run complete. */
internal fun compilerFixtureCompletionFields(probe: CompilerFixtureTimingProbeSpec?, complete: Boolean): Map<String, Any> =
    if (probe == null) mapOf("run_complete" to complete) else mapOf("run_complete" to false, "probe_complete" to complete,
        "completion_scope" to "private_scanner_eight_probe_only_not_ordinary_fixture_completion",
        "probe_variant_label" to probe.variant, "variant_label_is_artifact_identity" to false,
        "ordinary_six_kind_assessments_complete" to false)

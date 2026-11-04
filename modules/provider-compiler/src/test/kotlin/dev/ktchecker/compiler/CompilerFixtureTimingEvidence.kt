package dev.ktchecker.compiler

import dev.ktchecker.contracts.ProviderResult
import dev.ktchecker.providers.runtime.ProcessOutcome
import kotlinx.serialization.json.*

/** Private, bounded fixture sidecar only. It is not AnalysisMetrics or a qualification receipt. */
internal object CompilerFixtureTimingEvidence {
    const val MAX_BYTES = 131_072
    private val json = Json { prettyPrint = true }

    fun encode(receipt: CompilerPhaseReceipt, result: ProviderResult?, actual: ProcessOutcome?): ByteArray {
        require(receipt.capacity in 0..CompilerPhaseTimings.MAX_CAPACITY && receipt.intervals.size <= receipt.capacity)
        val document = buildJsonObject {
            put("schema", "ktchecker/compiler-phase-timings-1")
            put("provider_reported_elapsed_millis", result?.metrics?.elapsedMillis?.let(::JsonPrimitive) ?: JsonNull)
            put("raw_runner_elapsed_millis", actual?.metrics?.elapsedMillis?.let(::JsonPrimitive) ?: JsonNull)
            put("metric_origins", buildJsonObject {
                put("provider_reported_elapsed_millis", "Unchanged ProviderResult.metrics.elapsedMillis; pre-execution refusals may report zero.")
                put("raw_runner_elapsed_millis", "Actual execution-observer ProcessOutcome.metrics.elapsedMillis before provider aggregate replacement; null is unobserved, not zero.")
                put("existing_process_json_elapsed_millis", "Compatibility field remains provider-reported elapsed, not raw runner elapsed.")
            })
            put("clock", "System.nanoTime relative to the first recorded phase; independent of all authority/cancellation/deadline clocks.")
            put("capacity", receipt.capacity)
            put("recorded_phase_count", receipt.intervals.size)
            put("dropped_phase_count", receipt.droppedPhaseCount)
            put("clock_valid", receipt.clockValid)
            put("nesting_valid", receipt.nestingValid)
            put("all_recorded_actions_finished", receipt.allRecordedActionsFinished)
            put("observations_complete", receipt.observationsComplete)
            put("phase_intervals", JsonArray(receipt.intervals.map { interval -> buildJsonObject {
                put("sequence", interval.sequence)
                put("phase", interval.phase.label)
                put("occurrence", interval.occurrence)
                put("parent_sequence", interval.parentSequence?.let(::JsonPrimitive) ?: JsonNull)
                put("verification_occurrence", interval.verificationOccurrence?.let(::JsonPrimitive) ?: JsonNull)
                put("start_nanos", interval.startNanos?.let(::JsonPrimitive) ?: JsonNull)
                put("end_nanos", interval.endNanos?.let(::JsonPrimitive) ?: JsonNull)
                put("elapsed_nanos", interval.elapsedNanos?.let(::JsonPrimitive) ?: JsonNull)
                put("action_returned_normally", interval.actionReturnedNormally?.let(::JsonPrimitive) ?: JsonNull)
            } }))
            put("interval_scope", "Intervals nest/overlap and are not additive. Parent sequence binds nesting; verification occurrence zero is preflight, positive values count actual wrapped callbacks, never inferred launches.")
            put("runner_interval_scope", "Whole runner call, including pre/post verification, output preparation and owned-process lifecycle; not compiler-only time, and unattributed time is not teardown.")
            put("model_binding_interval_scope", "Verified-model binding checks remain one compound interval; no fabricated JDK/classpath split inside that route.")
            put("provider_interval_scope", "Provider implementation only, excluding fixture/toolchain construction and evidence persistence. Its nanosecond boundary is not identical to the existing coarse provider metric.")
            put("completion_scope", "Returned normally and complete observations are not successful execution, authority, cleanup, clean coverage or qualification. Read independently retained actual process/result/lifecycle receipts.")
            put("not_measured", JsonArray(listOf("cpu", "rss", "physical_disk_io", "compiler_only", "teardown_only",
                "fixture_startup", "semantic_worker_warmth").map(::JsonPrimitive)))
            put("qualification", "none; private observations only, no SLO or performance/release certificate")
        }
        return (json.encodeToString(JsonElement.serializer(), document) + "\n").toByteArray(Charsets.UTF_8).also {
            check(it.size <= MAX_BYTES) { "Fixed-label timing receipt exceeded its private byte bound" }
        }
    }
}

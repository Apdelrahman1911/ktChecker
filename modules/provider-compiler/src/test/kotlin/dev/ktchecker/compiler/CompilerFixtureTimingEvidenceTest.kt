package dev.ktchecker.compiler

import dev.ktchecker.contracts.AnalysisMetrics
import dev.ktchecker.contracts.ProviderResult
import dev.ktchecker.providers.runtime.ProcessOutcome
import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

/** Serialization/quota controls, not measured process latency or fabricated compiler evidence. */
class CompilerFixtureTimingEvidenceTest {
    @Test fun providerAndRunnerMetricsRemainSeparatelyNamedWithoutChangingEitherObject() {
        val timings = CompilerPhaseTimings(nanoTime = { 123L })
        timings.observe(CompilerPhase.PROVIDER_CALL) { Unit }
        val provider = ProviderResult("unit-serialization-only", "failed", metrics = AnalysisMetrics(elapsedMillis = 9_000))
        val runner = ProcessOutcome("unavailable", metrics = AnalysisMetrics(elapsedMillis = 4_000))
        val encoded = CompilerFixtureTimingEvidence.encode(timings.snapshot(), provider, runner)
        val document = Json.parseToJsonElement(encoded.toString(Charsets.UTF_8)).jsonObject
        assertEquals(9_000L, document.getValue("provider_reported_elapsed_millis").jsonPrimitive.long)
        assertEquals(4_000L, document.getValue("raw_runner_elapsed_millis").jsonPrimitive.long)
        assertEquals(9_000L, provider.metrics.elapsedMillis)
        assertEquals(4_000L, runner.metrics.elapsedMillis)
        assertFalse(provider.complete())
        assertFalse(runner.executed)
        assertTrue(document.getValue("observations_complete").jsonPrimitive.boolean)
        assertFalse(document.keys.any { it in setOf("elapsed_millis", "executed", "successful_execution", "cpu_millis", "peak_rss_bytes") })
        assertEquals(0L, document.getValue("phase_intervals").jsonArray.single().jsonObject.getValue("elapsed_nanos").jsonPrimitive.long)
        assertTrue(encoded.last() == '\n'.code.toByte())
    }

    @Test fun absentResultsAndUnobservedRunnerTimeAreNullNotInventedZero() {
        val timings = CompilerPhaseTimings()
        timings.observe(CompilerPhase.PROVIDER_CALL) { Unit }
        val document = decode(timings.snapshot())
        assertEquals(JsonNull, document.getValue("provider_reported_elapsed_millis"))
        assertEquals(JsonNull, document.getValue("raw_runner_elapsed_millis"))
        assertTrue(document.getValue("observations_complete").jsonPrimitive.boolean)
        assertTrue(document.getValue("completion_scope").jsonPrimitive.content.contains("not successful execution"))
    }

    @Test fun exhaustionAndInvalidClockAreExplicitAndUnmeasuredFieldsStayAbsent() {
        val timings = CompilerPhaseTimings(1) { throw IllegalStateException("test clock") }
        timings.observe(CompilerPhase.PROVIDER_CALL) {
            timings.observe(CompilerPhase.RUNNER_CALL) { Unit }
        }
        val document = decode(timings.snapshot())
        assertEquals(1L, document.getValue("dropped_phase_count").jsonPrimitive.long)
        assertFalse(document.getValue("clock_valid").jsonPrimitive.boolean)
        assertFalse(document.getValue("observations_complete").jsonPrimitive.boolean)
        val row = document.getValue("phase_intervals").jsonArray.single().jsonObject
        assertEquals(JsonNull, row.getValue("start_nanos"))
        assertEquals(JsonNull, row.getValue("end_nanos"))
        assertEquals(JsonNull, row.getValue("elapsed_nanos"))
        assertEquals(JsonPrimitive(true), row.getValue("action_returned_normally"))
        assertEquals(CompilerPhase.PROVIDER_CALL.label, row.getValue("phase").jsonPrimitive.content)
    }

    @Test fun aCompletelyObservedActionFailureNeverBecomesSuccessfulAnalysisEvidence() {
        val original = IllegalStateException("unit action failed")
        val timings = CompilerPhaseTimings()
        assertSame(original, assertFailsWith<IllegalStateException> {
            timings.observe(CompilerPhase.PROVIDER_CALL) { throw original }
        })
        val document = decode(timings.snapshot())
        assertTrue(document.getValue("observations_complete").jsonPrimitive.boolean)
        assertEquals(JsonPrimitive(false), document.getValue("phase_intervals").jsonArray.single()
            .jsonObject.getValue("action_returned_normally"))
        assertEquals(JsonNull, document.getValue("provider_reported_elapsed_millis"))
        assertEquals(JsonNull, document.getValue("raw_runner_elapsed_millis"))
        assertFalse(document.keys.any { it in setOf("executed", "successful_execution", "analysis_complete") })
    }

    @Test fun maximumRecorderCapacityStillFitsThePrivateReceiptByteBound() {
        var ticks = 0L
        val timings = CompilerPhaseTimings(CompilerPhaseTimings.MAX_CAPACITY) { ticks++ }
        repeat(CompilerPhaseTimings.MAX_CAPACITY) {
            timings.observe(CompilerPhase.DEPENDENCY_CLASSPATH_VERIFICATION) { Unit }
        }
        val bytes = CompilerFixtureTimingEvidence.encode(timings.snapshot(), null, null)
        assertTrue(bytes.size <= CompilerFixtureTimingEvidence.MAX_BYTES)
        assertEquals(CompilerPhaseTimings.MAX_CAPACITY,
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject.getValue("phase_intervals").jsonArray.size)
    }

    @Test fun unboundedHandMadeReceiptIsRejectedBeforeSerialization() {
        val timings = CompilerPhaseTimings(1)
        timings.observe(CompilerPhase.PROVIDER_CALL) { Unit }
        val receipt = timings.snapshot()
        assertFailsWith<IllegalArgumentException> {
            CompilerFixtureTimingEvidence.encode(receipt.copy(intervals = receipt.intervals + receipt.intervals), null, null)
        }
    }

    @Test fun timingUsesExistingImmutableWriterAndJobReservationWithoutReplacingRawEvidence() {
        val root = Files.createTempDirectory("ktc-timing-evidence-unit-").toRealPath()
        try {
            val run = Files.createDirectory(root.resolve("run"))
            val store = CompilerFixtureEvidence(root, run, null, maximumBytes = 262_144, terminalReserveBytes = 16_384)
            val reservation = store.quota.reserveJob(196_608)
            try {
                // Marker bytes are serialization controls only, not a fake actual process receipt.
                val raw = "raw persistence marker, not process evidence\n".toByteArray()
                store.writeNew(run.resolve("raw-marker.txt"), raw, reservation = reservation)
                val rawReference = store.reference(run.resolve("raw-marker.txt"))
                val timings = CompilerPhaseTimings()
                timings.observe(CompilerPhase.PROVIDER_CALL) { Unit }
                val encoded = CompilerFixtureTimingEvidence.encode(timings.snapshot(), null, null)
                val path = run.resolve("timing.json")
                store.writeNew(path, encoded, reservation = reservation)
                assertContentEquals(encoded, Files.readAllBytes(path))
                assertEquals(rawReference, store.reference(run.resolve("raw-marker.txt")))
                assertContentEquals(raw, Files.readAllBytes(run.resolve("raw-marker.txt")))
                assertFails { store.writeNew(path, encoded, reservation = reservation) }
            } finally { reservation.close() }
            assertEquals(0L, store.quota.reservedBytes)
        } finally { root.toFile().deleteRecursively() }
    }

    private fun decode(receipt: CompilerPhaseReceipt): JsonObject = Json.parseToJsonElement(
        CompilerFixtureTimingEvidence.encode(receipt, null, null).toString(Charsets.UTF_8)).jsonObject
}

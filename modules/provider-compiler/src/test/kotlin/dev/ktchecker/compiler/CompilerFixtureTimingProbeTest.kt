package dev.ktchecker.compiler

import dev.ktchecker.contracts.AnalysisMetrics
import dev.ktchecker.contracts.ProviderResult
import dev.ktchecker.providers.runtime.ProcessOutcome
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

/** Schedule/parser/publication value controls only. No compiler/provider/helper execution or measured timings. */
class CompilerFixtureTimingProbeTest {
    @Test fun explicitVariantAndSingleGradleAppendedRootAreRequired() {
        val baseline = probe()
        assertEquals("instrumented-baseline", baseline.variant)
        assertEquals("fixtures", baseline.fixtureDirectory)
        assertEquals(baseline.settings(), CompilerFixtureTimingProbeSpec.parse(arrayOf("fixtures", "--variant=instrumented-baseline")).settings())
        assertEquals("scan-buffer-hex", probe("scan-buffer-hex").variant)
        listOf(emptyArray(), arrayOf("fixtures"), arrayOf("--variant=instrumented-baseline"),
            arrayOf("fixtures", "another", "--variant=instrumented-baseline"),
            arrayOf("", "--variant=instrumented-baseline"), arrayOf("bad\u0000root", "--variant=instrumented-baseline")).forEach {
            assertFails { CompilerFixtureTimingProbeSpec.parse(it) }
        }
    }

    @Test fun everySelectorBudgetRetryAndUnknownVariantOverrideIsRejectedEvenAtTheFixedValue() {
        val overrides = listOf("--cohort=original", "--cohort=all", "--rule-id=${CompilerFixtureTimingProbeSpec.RULE_ID}",
            "--ids=UNRESOLVED_REFERENCE", "--max-attempts=8", "--max-attempts=9", "--overall-seconds=300",
            "--per-job-seconds=60", "--evidence-mib=128", "--warmup=1", "--retry=1", "--resume=true", "--unknown=x")
        overrides.forEach { option -> assertFails { CompilerFixtureTimingProbeSpec.parse(arrayOf(
            "fixtures", "--variant=instrumented-baseline", option)) } }
        listOf("--variant", "--variant=", "--variant=baseline", "--variant=scan-buffer-hex=extra",
            "--variant=../elsewhere", "--variant-extra=scan-buffer-hex").forEach {
            assertFails { CompilerFixtureTimingProbeSpec.parse(arrayOf("fixtures", it)) }
        }
        assertFails { CompilerFixtureTimingProbeSpec.parse(arrayOf("fixtures", "--variant=instrumented-baseline", "--variant=scan-buffer-hex")) }
    }

    @Test fun fixedProbeBoundsDoNotAlterTheOrdinaryParserDefaults() {
        val fixed = probe().settings()
        assertEquals("original", fixed.cohort)
        assertEquals(8, fixed.maxAttempts)
        assertEquals(300_000L, fixed.overallMillis)
        assertEquals(60_000L, fixed.perJobMillis)
        assertEquals(134_217_728L, fixed.evidenceBytes)
        assertEquals(512, CompilerFixtureTimingProbeSpec.COMPILER_HEAP_MIB)
        assertEquals(64, CompilerPhaseTimings.MAX_CAPACITY)
        assertEquals(131_072, CompilerFixtureTimingEvidence.MAX_BYTES)
        val ordinary = CompilerFixtureSettings.parse(arrayOf("fixtures", "--cohort=original"))
        assertEquals(48, ordinary.maxAttempts)
        assertEquals(1_200_000L, ordinary.overallMillis)
        assertEquals(90_000L, ordinary.perJobMillis)
        assertEquals(268_435_456L, ordinary.evidenceBytes)
        assertFails { CompilerFixtureSettings.parse(arrayOf("fixtures", "--variant=instrumented-baseline")) }
    }

    @Test fun exactEightAlternatingOrdinalsRetainTheFirstSampleAndNeverAddFallbacks() {
        val spec = probe()
        val observed = mutableListOf<CompilerFixtureProbeSample>()
        spec.forEachSample { observed += it } // No success/failure callback result can ask for a replacement.
        assertEquals((1..8).toList(), observed.map { it.number })
        assertEquals(List(4) { listOf(CompilerFixtureProbeSource.POSITIVE, CompilerFixtureProbeSource.CLEAN) }.flatten(), observed.map { it.source })
        assertEquals(8, observed.map { it.label }.distinct().size)
        assertEquals(spec.samples(), probe("scan-buffer-hex").samples())
        assertEquals(listOf("-jvm-target", "17"), CompilerFixtureTimingProbeSpec.FLAGS)
        assertTrue(observed.all { it.description()["discarded_as_warmup"] == false })
        assertEquals(true, spec.description()["retains_first_sample"])
        assertEquals(0, spec.description()["replacement_samples"])
    }

    @Test fun callbackFailureStopsAtThatOrdinalWithoutRetryingOrReplacingAnySample() {
        val observed = mutableListOf<Int>()
        val failure = IllegalStateException("unit scheduling failure, not a compiler outcome")
        assertSame(failure, assertFailsWith<IllegalStateException> {
            probe().forEachSample { sample ->
                observed += sample.number
                if (sample.number == 3) throw failure
            }
        })
        assertEquals(listOf(1, 2, 3), observed)
    }

    @Test fun fixedAttemptBudgetIncludesRefusalsAndAllowsNoNinthReservation() {
        val spec = probe()
        val budget = CompilerFixtureBudget(spec.settings(), startedNanos = 0, nanoTime = { 0 })
        spec.forEachSample { sample -> budget.reserveAttempt(sample.label, setOf(CompilerFixtureTimingProbeSpec.RULE_ID)).state = "unit_refusal_only" }
        assertEquals(8, budget.attempts.size)
        assertFalse(spec.samplesEligible(budget.attempts, emptyList(), 0, 0, 0))
        assertEquals(CompilerFixtureStopReason.ATTEMPT_BUDGET,
            assertFailsWith<CompilerFixtureStopped> { budget.reserveAttempt("forbidden-replacement", emptySet()) }.reason)
    }

    @Test fun startupAndPublicationTimeConsumeTheSameOverallBudgetWithoutExtendingAJob() {
        var now = 0L
        val budget = CompilerFixtureBudget(probe().settings(), startedNanos = 0, nanoTime = { now })
        assertEquals(60_000L, budget.nextTimeoutMillis())
        now = 280_000_000_000L
        assertEquals(20_000L, budget.nextTimeoutMillis())
        now = 300_000_000_000L
        assertEquals(CompilerFixtureStopReason.OVERALL_DEADLINE,
            assertFailsWith<CompilerFixtureStopped> { budget.requireActive() }.reason)
    }

    @Test fun positiveMatchingRequiresActualNonzeroExitAndFailedProviderStatusWithoutRewritingEither() {
        val spec = probe()
        val sample = spec.samples().first()
        assertTrue(spec.expectedOutcomeMatches(sample, true, 1, "failed", true, false))
        listOf<Int?>(null, 0).forEach { assertFalse(spec.expectedOutcomeMatches(sample, true, it, "failed", true, false)) }
        listOf("partial", "complete", "unavailable", "cancelled").forEach {
            assertFalse(spec.expectedOutcomeMatches(sample, true, 1, it, true, false))
        }
        assertFalse(spec.expectedOutcomeMatches(sample, false, 1, "failed", true, false))
        assertFalse(spec.expectedOutcomeMatches(sample, true, 1, "failed", false, true))
    }

    @Test fun cleanMatchingRequiresRealZeroExitAndCleanAbsenceWithoutClaimingQualifiedCompletion() {
        val spec = probe()
        val sample = spec.samples()[1]
        assertTrue(spec.expectedOutcomeMatches(sample, true, 0, "partial", false, true))
        listOf<Int?>(null, 1).forEach { assertFalse(spec.expectedOutcomeMatches(sample, true, it, "partial", false, true)) }
        listOf("failed", "complete", "unavailable", "cancelled").forEach {
            assertFalse(spec.expectedOutcomeMatches(sample, true, 0, it, false, true))
        }
        assertFalse(spec.expectedOutcomeMatches(sample, false, 0, "partial", false, true))
        assertFalse(spec.expectedOutcomeMatches(sample, true, 0, "partial", true, false))
    }

    @Test fun sampleGateRequiresExactScheduleAttributionAndEightDistinctActualOutputs() {
        val spec = probe()
        val attempts = attempts(spec)
        val observations = observations(spec)
        assertTrue(spec.samplesEligible(attempts, observations, 8, 8, 0)) // Boolean control only, not actual run evidence.
        assertFalse(spec.samplesEligible(attempts.drop(1), observations, 8, 8, 0))
        assertFalse(spec.samplesEligible(attempts + attempts.last(), observations, 8, 8, 0))
        assertFalse(spec.samplesEligible(attempts, observations.drop(1), 8, 8, 0)) // Sample1 cannot be discarded as warmup.
        assertFalse(spec.samplesEligible(attempts, observations.reversed(), 8, 8, 0))
        assertFalse(spec.samplesEligible(attempts, observations + observations.last(), 8, 8, 0))
        assertFalse(spec.samplesEligible(attempts, observations.replaceAt(0, observations[0].copy(attemptNumber = 2)), 8, 8, 0))
        assertFalse(spec.samplesEligible(attempts, observations.replaceAt(0, observations[0].copy(sample = observations[1].sample)), 8, 8, 0))
        assertFalse(spec.samplesEligible(attempts.replaceAt(0, attempts[0].copy(label = "different-control")), observations, 8, 8, 0))
        assertFalse(spec.samplesEligible(attempts.replaceAt(0, attempts[0].copy(number = 9)), observations, 8, 8, 0))
        assertFalse(spec.samplesEligible(attempts.replaceAt(0, attempts[0].copy(ruleIds = emptySet())), observations, 8, 8, 0))
        assertFalse(spec.samplesEligible(attempts.replaceAt(0, attempts[0].copy(ruleIds = setOf(CompilerFixtureTimingProbeSpec.RULE_ID, "extra"))), observations, 8, 8, 0))
        listOf(0, 7, 9).forEach { count ->
            assertFalse(spec.samplesEligible(attempts, observations, count, 8, 0))
            assertFalse(spec.samplesEligible(attempts, observations, 8, count, 0))
        }
        assertFalse(spec.samplesEligible(attempts, observations, 8, 8, 1))
    }

    @Test fun everyMissingActualOutcomeReceiptOrTimingObservationWithholdsSampleEligibility() {
        val spec = probe()
        val observations = observations(spec)
        observations.forEachIndexed { index, value ->
            listOf(value.copy(executionConfirmed = false), value.copy(successfulSupervisedExecution = false),
                value.copy(expectedOutcomeMatched = false), value.copy(receiptsIntact = false),
                value.copy(timingObservationsComplete = false)).forEach { broken ->
                assertFalse(spec.samplesEligible(attempts(spec), observations.replaceAt(index, broken), 8, 8, 0))
            }
        }
    }

    @Test fun probeCompletionFieldsNeverMarkOrdinaryAssessmentsOrControlsComplete() {
        assertEquals(mapOf("run_complete" to false), compilerFixtureCompletionFields(null, false))
        assertEquals(mapOf("run_complete" to true), compilerFixtureCompletionFields(null, true))
        for (variant in listOf("instrumented-baseline", "scan-buffer-hex")) for (complete in listOf(false, true)) {
            val fields = compilerFixtureCompletionFields(probe(variant), complete)
            assertEquals(false, fields["run_complete"])
            assertEquals(complete, fields["probe_complete"])
            assertEquals(false, fields["ordinary_six_kind_assessments_complete"])
            assertEquals(false, fields["variant_label_is_artifact_identity"])
            assertEquals("not_run_outside_probe_schedule", probe(variant).description()["intent_and_transport_controls"])
            assertFalse(fields.containsKey("qualified"))
        }
    }

    @Test fun deadlineCrossingInTheFinalProbeSelectorRequiresIncompleteCorrectionAndFailsReturn() {
        val spec = probe()
        var now = 0L
        val budget = CompilerFixtureBudget(spec.settings(), startedNanos = 0, nanoTime = { now })
        val publication = CompilerFixtureTerminalPublication(budget)
        var fields = compilerFixtureCompletionFields(spec, false)
        assertFailsWith<CompilerFixtureStopped> {
            publication.publish(true, {}, {
                fields = compilerFixtureCompletionFields(spec, true)
                now = 300_000_000_000L
            }, { fields = compilerFixtureCompletionFields(spec, false) })
        }
        assertFalse(publication.committed)
        assertEquals(false, fields["probe_complete"])
        assertEquals(false, fields["run_complete"])
        assertEquals(CompilerFixtureStopReason.OVERALL_DEADLINE, budget.stopReason)
    }

    @Test fun missingSamplesCannotCommitEvenWhenOrdinaryPublicationClockIsActive() {
        val spec = probe()
        val publication = CompilerFixtureTerminalPublication(CompilerFixtureBudget(spec.settings(), 0, { 0 }))
        var incomplete = false
        assertFalse(publication.publish(spec.samplesEligible(attempts(spec), observations(spec).dropLast(1), 8, 8, 0),
            { fail("No complete publication reservation for an incomplete probe") },
            { fail("No positive selector for an incomplete probe") }, { incomplete = true }))
        assertTrue(incomplete)
        assertFalse(publication.committed)
    }

    @Test fun bothProbeVariantsHaveNoOrdinaryExternalSidecarDestination() {
        val fixtureRoot = Path.of("/unit-only/providers/compiler-fixtures")
        assertEquals(fixtureRoot.resolve("evidence/compiler-jvm-${CompilerRuleRegistry.VERSION}.json"),
            compilerFixtureLatestSidecar(fixtureRoot, null))
        assertNull(compilerFixtureLatestSidecar(fixtureRoot, probe()))
        assertNull(compilerFixtureLatestSidecar(fixtureRoot, probe("scan-buffer-hex")))
    }

    @Test fun privateEvidenceStoreCannotReplaceTheOrdinaryPublicSidecar() {
        val repository = Files.createTempDirectory("ktc-probe-publication-unit-").toRealPath()
        try {
            val fixtureRoot = Files.createDirectories(repository.resolve("providers/compiler-fixtures"))
            val latest = checkNotNull(compilerFixtureLatestSidecar(fixtureRoot, null))
            Files.createDirectory(latest.parent)
            val marker = "ordinary unit marker, not evidence\n".toByteArray()
            Files.write(latest, marker)
            val run = Files.createDirectories(fixtureRoot.resolve("runs/unit-only"))
            val store = CompilerFixtureEvidence(repository, run, compilerFixtureLatestSidecar(fixtureRoot, probe()),
                maximumBytes = 262_144, terminalReserveBytes = 16_384)
            assertFails { store.replaceLatest(latest, "forbidden".toByteArray()) }
            assertFails { store.writeNew(latest, "forbidden".toByteArray()) }
            store.replaceLatest(run.resolve("latest-checkpoint.json"), "private unit marker\n".toByteArray())
            assertContentEquals(marker, Files.readAllBytes(latest))
        } finally { repository.toFile().deleteRecursively() }
    }

    @Test fun timingShapeAndCompleteObservationsAloneNeverConfirmExecution() {
        val bytes = timingShapeControl()
        assertTrue(CompilerFixtureTimingProbeSpec.timingObservationsComplete(bytes, 700, 300))
        val document = decode(bytes)
        assertEquals(25, document.getValue("phase_intervals").jsonArray.size)
        assertFalse(document.containsKey("execution_confirmed"))
        val spec = probe()
        assertFalse(spec.samplesEligible(attempts(spec), observations(spec).map {
            it.copy(executionConfirmed = false, successfulSupervisedExecution = false)
        }, 8, 8, 0))
    }

    @Test fun absentMalformedOverBoundAndIncompleteTimingSidecarsAreRejected() {
        val bytes = timingShapeControl()
        assertFalse(CompilerFixtureTimingProbeSpec.timingObservationsComplete(null, 700, 300))
        assertFalse(CompilerFixtureTimingProbeSpec.timingObservationsComplete(ByteArray(0), 700, 300))
        assertFalse(CompilerFixtureTimingProbeSpec.timingObservationsComplete("not-json".toByteArray(), 700, 300))
        assertFalse(CompilerFixtureTimingProbeSpec.timingObservationsComplete(ByteArray(131_073), 700, 300))
        assertFalse(CompilerFixtureTimingProbeSpec.timingObservationsComplete(bytes, 700, null))
        assertFalse(CompilerFixtureTimingProbeSpec.timingObservationsComplete(bytes, -1, 300))
        assertFalse(CompilerFixtureTimingProbeSpec.timingObservationsComplete(bytes, 700, -1))
        val document = decode(bytes)
        val changes = mapOf("schema" to JsonPrimitive("unknown"), "capacity" to JsonPrimitive(128),
            "recorded_phase_count" to JsonPrimitive(24), "dropped_phase_count" to JsonPrimitive(1),
            "clock_valid" to JsonPrimitive(false), "nesting_valid" to JsonPrimitive(false),
            "all_recorded_actions_finished" to JsonPrimitive(false), "observations_complete" to JsonPrimitive(false),
            "provider_reported_elapsed_millis" to JsonPrimitive(701), "raw_runner_elapsed_millis" to JsonNull)
        changes.forEach { (key, value) -> assertFalse(valid(document.with(key, value)), key) }
        document.keys.forEach { key ->
            if (key in changes || key == "phase_intervals") assertFalse(valid(JsonObject(document - key)), key)
        }
    }

    @Test fun missingPhaseCallbacksAndInvalidIntervalAttributionAreNotFilledInAsComplete() {
        val document = decode(timingShapeControl())
        val rows = document.getValue("phase_intervals").jsonArray
        val missing = JsonArray(rows.dropLast(1))
        assertFalse(valid(document.with("phase_intervals", missing).with("recorded_phase_count", JsonPrimitive(missing.size))))
        val row = rows[1].jsonObject
        val changes = mapOf("sequence" to JsonPrimitive(50), "occurrence" to JsonPrimitive(2),
            "phase" to JsonPrimitive(CompilerPhase.MODEL_BINDING_VERIFICATION.label),
            "parent_sequence" to JsonPrimitive(2), "verification_occurrence" to JsonPrimitive(1),
            "start_nanos" to JsonNull, "end_nanos" to JsonPrimitive(-1), "elapsed_nanos" to JsonPrimitive(-1),
            "action_returned_normally" to JsonNull)
        changes.forEach { (key, value) ->
            assertFalse(valid(document.with("phase_intervals", JsonArray(rows.replaceAt(1, row.with(key, value))))), key)
        }
    }

    @Test fun aFullyObservedActionFailureRemainsOnlyAnObservationNotSuccessfulAnalysis() {
        val document = decode(timingShapeControl())
        val rows = document.getValue("phase_intervals").jsonArray
        val changed = document.with("phase_intervals", JsonArray(rows.replaceAt(1,
            rows[1].jsonObject.with("action_returned_normally", JsonPrimitive(false)))))
        assertTrue(valid(changed)) // Finished failure is a complete observation; actual-outcome gate remains separate.
        val spec = probe()
        assertFalse(spec.samplesEligible(attempts(spec), observations(spec).map { it.copy(expectedOutcomeMatched = false) }, 8, 8, 0))
    }

    private fun probe(variant: String = "instrumented-baseline") =
        CompilerFixtureTimingProbeSpec.parse(arrayOf("--variant=$variant", "fixtures"))

    private fun attempts(spec: CompilerFixtureTimingProbeSpec) = spec.samples().map {
        CompilerFixtureAttempt(it.number, it.label, setOf(CompilerFixtureTimingProbeSpec.RULE_ID), 0)
    }
    private fun observations(spec: CompilerFixtureTimingProbeSpec) = spec.samples().map {
        CompilerFixtureProbeObservation(it, it.number, true, true, true, true, true)
    }
    private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> = toMutableList().also { it[index] = value }
    private fun JsonObject.with(key: String, value: JsonElement): JsonObject = JsonObject(this + (key to value))
    private fun decode(bytes: ByteArray): JsonObject = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    private fun valid(document: JsonObject) = CompilerFixtureTimingProbeSpec.timingObservationsComplete(
        document.toString().toByteArray(Charsets.UTF_8), 700, 300)

    /** Synthetic recorder shape only; both supplied metric objects explicitly remain unexecuted/unavailable. */
    private fun timingShapeControl(): ByteArray {
        var tick = 0L
        val timings = CompilerPhaseTimings(nanoTime = { tick++ })
        fun verification(phase: CompilerPhase, readSources: Boolean = false): Unit = timings.observe(phase) {
            listOf(CompilerPhase.SNAPSHOT_VERIFICATION, CompilerPhase.JDK_RUNTIME_VERIFICATION,
                CompilerPhase.COMPILER_CLASSPATH_VERIFICATION, CompilerPhase.DEPENDENCY_CLASSPATH_VERIFICATION).forEach {
                timings.observe(it) { Unit }
            }
            if (readSources) timings.observe(CompilerPhase.SEALED_SOURCE_READING) { Unit }
            Unit
        }
        timings.observe(CompilerPhase.PROVIDER_CALL) {
            verification(CompilerPhase.INITIAL_VERIFICATION, readSources = true)
            timings.observe(CompilerPhase.RUNNER_CALL) {
                verification(CompilerPhase.WRAPPED_PERMIT_VERIFICATION)
                timings.observe(CompilerPhase.OUTPUT_PREPARATION) {
                    listOf(CompilerPhase.OWNER_OUTPUT_PREPARATION, CompilerPhase.SOURCE_COPYING,
                        CompilerPhase.COMPILER_ARTIFACT_COPYING, CompilerPhase.DEPENDENCY_ARTIFACT_COPYING).forEach {
                        timings.observe(it) { Unit }
                    }
                }
                verification(CompilerPhase.WRAPPED_PERMIT_VERIFICATION)
            }
            timings.observe(CompilerPhase.EXECUTION_OBSERVER) { Unit }
            timings.observe(CompilerPhase.RESULT_NORMALIZATION) { Unit }
        }
        return CompilerFixtureTimingEvidence.encode(timings.snapshot(),
            ProviderResult("unit-value-only", "unavailable", metrics = AnalysisMetrics(elapsedMillis = 700)),
            ProcessOutcome("unavailable", metrics = AnalysisMetrics(elapsedMillis = 300)))
    }
}

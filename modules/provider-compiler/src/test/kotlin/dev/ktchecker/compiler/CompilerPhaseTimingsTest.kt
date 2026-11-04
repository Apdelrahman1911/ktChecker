package dev.ktchecker.compiler

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.*

/** Recorder/denial controls only. Synthetic installations are byte inputs, never launched. */
class CompilerPhaseTimingsTest {
    @Test fun nestedIntervalsRetainOrderParentsAndActualVerificationOccurrences() {
        var tick = 100L
        val timings = CompilerPhaseTimings(nanoTime = { tick.also { tick += 10 } })
        var calls = 0
        val returned = timings.observe(CompilerPhase.PROVIDER_CALL) {
            timings.observe(CompilerPhase.INITIAL_VERIFICATION) {
                timings.observe(CompilerPhase.SNAPSHOT_VERIFICATION) { calls++ }
                timings.observe(CompilerPhase.JDK_RUNTIME_VERIFICATION) { calls++ }
            }
            timings.observe(CompilerPhase.RUNNER_CALL) {
                repeat(2) {
                    timings.observe(CompilerPhase.WRAPPED_PERMIT_VERIFICATION) {
                        timings.observe(CompilerPhase.SNAPSHOT_VERIFICATION) { calls++ }
                        timings.observe(CompilerPhase.JDK_RUNTIME_VERIFICATION) { calls++ }
                    }
                }
            }
            "action value, not an execution witness"
        }
        assertEquals("action value, not an execution witness", returned)
        assertEquals(6, calls)
        val receipt = timings.snapshot()
        assertTrue(receipt.observationsComplete)
        assertEquals((1..receipt.intervals.size).toList(), receipt.intervals.map { it.sequence })
        assertEquals(0L, receipt.intervals.first().startNanos)
        assertNull(receipt.intervals.first().parentSequence)
        assertEquals(listOf(0, 1, 2), receipt.intervals.filter {
            it.phase == CompilerPhase.SNAPSHOT_VERIFICATION }.map { it.verificationOccurrence })
        assertEquals(listOf(1, 2, 3), receipt.intervals.filter {
            it.phase == CompilerPhase.JDK_RUNTIME_VERIFICATION }.map { it.occurrence })
        val bySequence = receipt.intervals.associateBy { it.sequence }
        receipt.intervals.forEach { interval ->
            assertEquals(true, interval.actionReturnedNormally)
            val start = assertNotNull(interval.startNanos)
            val end = assertNotNull(interval.endNanos)
            assertEquals(end - start, interval.elapsedNanos)
            interval.parentSequence?.let { parentId ->
                val parent = bySequence.getValue(parentId)
                assertTrue(start >= parent.startNanos!!)
                assertTrue(end <= parent.endNanos!!)
            }
        }
        // Parent/child overlap is deliberate: summing every row double-counts work.
        assertTrue(receipt.intervals.sumOf { it.elapsedNanos!! } > receipt.intervals.first().elapsedNanos!!)
    }

    @Test fun exhaustionBoundsStorageButNeverSkipsNestedOrLaterActions() {
        for (capacity in listOf(0, 1, 2)) {
            var calls = 0
            var clockReads = 0
            val timings = CompilerPhaseTimings(capacity) { (++clockReads).toLong() }
            val value = timings.observe(CompilerPhase.PROVIDER_CALL) {
                repeat(8) {
                    timings.observe(CompilerPhase.INITIAL_VERIFICATION) {
                        timings.observe(CompilerPhase.SNAPSHOT_VERIFICATION) { calls++ }
                    }
                }
                42
            }
            assertEquals(42, value)
            assertEquals(8, calls)
            val receipt = timings.snapshot()
            assertEquals(capacity, receipt.intervals.size)
            assertEquals((17 - capacity).toLong(), receipt.droppedPhaseCount)
            assertEquals(2 * capacity, clockReads)
            assertTrue(receipt.allRecordedActionsFinished)
            assertFalse(receipt.observationsComplete)
        }
    }

    @Test fun aFailureInAnUnrecordedActionRemainsTheOriginalFailure() {
        val original = CancellationException("original cancelled action")
        val timings = CompilerPhaseTimings(1)
        val caught = assertFailsWith<CancellationException> {
            timings.observe(CompilerPhase.PROVIDER_CALL) {
                timings.observe(CompilerPhase.SNAPSHOT_VERIFICATION) { throw original }
            }
        }
        assertSame(original, caught)
        val receipt = timings.snapshot()
        assertEquals(1L, receipt.droppedPhaseCount)
        assertEquals(false, receipt.intervals.single().actionReturnedNormally)
        assertFalse(receipt.observationsComplete)
    }

    @Test fun exactMaximumNestingFitsAndTheNextDepthStillRunsWithoutGrowingStorage() {
        assertEquals(64, CompilerPhaseTimings.DEFAULT_CAPACITY)
        assertEquals(64, CompilerPhaseTimings.MAX_CAPACITY)
        for (depth in listOf(64, 65)) {
            var actions = 0
            val timings = CompilerPhaseTimings()
            fun nested(remaining: Int): Int = timings.observe(CompilerPhase.PROVIDER_CALL) {
                actions++
                if (remaining == 1) 1 else 1 + nested(remaining - 1)
            }
            assertEquals(depth, nested(depth))
            assertEquals(depth, actions)
            val receipt = timings.snapshot()
            assertEquals(64, receipt.intervals.size)
            assertEquals((depth - 64).toLong(), receipt.droppedPhaseCount)
            assertEquals(depth == 64, receipt.observationsComplete)
            assertTrue(receipt.allRecordedActionsFinished)
            assertTrue(receipt.intervals.all { it.actionReturnedNormally == true })
            assertEquals(listOf(null) + (1..63).toList(), receipt.intervals.map { it.parentSequence })
        }
    }

    @Test fun failingStartOrEndClocksNeverSkipOrReplaceNormalActionResults() {
        for (failureAt in listOf(1, 2)) {
            var reads = 0
            var calls = 0
            val value = Any()
            val timings = CompilerPhaseTimings(nanoTime = {
                if (++reads == failureAt) throw AssertionError("recorder-only clock failure")
                reads.toLong()
            })
            assertSame(value, timings.observe(CompilerPhase.PROVIDER_CALL) { calls++; value })
            assertEquals(1, calls)
            val receipt = timings.snapshot()
            assertFalse(receipt.clockValid)
            assertFalse(receipt.observationsComplete)
            assertEquals(true, receipt.intervals.single().actionReturnedNormally)
            assertNull(receipt.intervals.single().endNanos)
            assertNull(receipt.intervals.single().elapsedNanos)
            timings.observe(CompilerPhase.SOURCE_COPYING) { calls++ }
            assertEquals(failureAt, reads, "An invalid clock is latched, not repeatedly retried")
            assertEquals(2, calls)
        }
    }

    @Test fun clockFailureInFinallyDoesNotMaskAnActionExceptionCancellationOrError() {
        for (original in listOf(IllegalStateException("original failure"),
                CancellationException("original cancellation"), AssertionError("original error"))) {
            var reads = 0
            val timings = CompilerPhaseTimings(nanoTime = {
                if (++reads == 2) throw IllegalStateException("different recorder failure")
                100L
            })
            assertSame(original, assertFailsWith<Throwable> {
                timings.observe(CompilerPhase.PROVIDER_CALL) { throw original }
            })
            assertEquals(false, timings.snapshot().intervals.single().actionReturnedNormally)
            assertFalse(timings.snapshot().clockValid)
            assertTrue(original.suppressed.isEmpty(), "Observational defects do not rewrite action failures")
        }
    }

    @Test fun backwardsClockReadInvalidatesRatherThanClampsOrFabricatesDurations() {
        val ticks = listOf(100L, 110L, 109L).iterator()
        val timings = CompilerPhaseTimings(nanoTime = { ticks.next() })
        val value = timings.observe(CompilerPhase.PROVIDER_CALL) {
            timings.observe(CompilerPhase.INITIAL_VERIFICATION) { "kept" }
        }
        assertEquals("kept", value)
        val receipt = timings.snapshot()
        assertFalse(receipt.clockValid)
        assertFalse(receipt.observationsComplete)
        assertEquals(listOf(0L, 10L), receipt.intervals.map { it.startNanos })
        assertTrue(receipt.intervals.all { it.elapsedNanos == null && it.endNanos == null })
        assertTrue(receipt.intervals.all { it.actionReturnedNormally == true })
    }

    @Test fun signedNanoTimeOriginsAndShortCounterWrapsHaveValidRelativeDurations() {
        for (ticks in listOf(listOf(-9L, -3L), listOf(Long.MAX_VALUE - 2, Long.MIN_VALUE + 3))) {
            val iterator = ticks.iterator()
            val timings = CompilerPhaseTimings(nanoTime = { iterator.next() })
            timings.observe(CompilerPhase.PROVIDER_CALL) { Unit }
            val receipt = timings.snapshot()
            assertTrue(receipt.observationsComplete)
            assertEquals(0L, receipt.intervals.single().startNanos)
            assertEquals(6L, receipt.intervals.single().elapsedNanos)
        }
        val overflow = listOf(0L, Long.MIN_VALUE).iterator()
        val invalid = CompilerPhaseTimings(nanoTime = { overflow.next() })
        invalid.observe(CompilerPhase.PROVIDER_CALL) { Unit }
        assertFalse(invalid.snapshot().clockValid)
        assertNull(invalid.snapshot().intervals.single().elapsedNanos)
    }

    @Test fun snapshotsAreDetachedImmutableAndOpenActionsAreExplicitlyIncomplete() {
        val timings = CompilerPhaseTimings()
        lateinit var open: CompilerPhaseReceipt
        timings.observe(CompilerPhase.PROVIDER_CALL) {
            open = timings.snapshot()
            timings.observe(CompilerPhase.SEALED_SOURCE_READING) { Unit }
        }
        assertEquals(1, open.intervals.size)
        assertFalse(open.allRecordedActionsFinished)
        assertFalse(open.observationsComplete)
        assertNull(open.intervals.single().endNanos)
        assertNull(open.intervals.single().actionReturnedNormally)
        val closed = timings.snapshot()
        assertEquals(2, closed.intervals.size)
        assertTrue(closed.observationsComplete)
        assertFailsWith<UnsupportedOperationException> { (closed.intervals as MutableList<CompilerPhaseInterval>).clear() }
        assertNull(open.intervals.single().endNanos, "Later completion cannot alter a prior snapshot")
    }

    @Test fun invalidNestingIsOnlyAnObservationDefectNeverAnActionFailure() {
        val timings = CompilerPhaseTimings()
        val outer = timings.begin(CompilerPhase.PROVIDER_CALL)
        val inner = timings.begin(CompilerPhase.INITIAL_VERIFICATION)
        timings.end(outer, true)
        timings.end(inner, true)
        timings.end(outer, true)
        assertFalse(timings.snapshot().nestingValid)
        assertFalse(timings.snapshot().observationsComplete)
        assertTrue(timings.snapshot().allRecordedActionsFinished)
    }

    @Test fun nullRecorderPreservesValuesAndOriginalExceptions() {
        val timings: CompilerPhaseTimings? = null
        val value = Any()
        var calls = 0
        assertSame(value, timings.observe(CompilerPhase.PROVIDER_CALL) { calls++; value })
        val original = IllegalStateException("action, not recorder")
        assertSame(original, assertFailsWith<IllegalStateException> {
            timings.observe(CompilerPhase.PROVIDER_CALL) { calls++; throw original }
        })
        assertEquals(2, calls)
    }

    @Test fun capacityAndLabelsCannotBecomeAnUnboundedOrArbitraryMetricChannel() {
        assertFailsWith<IllegalArgumentException> { CompilerPhaseTimings(-1) }
        assertFailsWith<IllegalArgumentException> { CompilerPhaseTimings(CompilerPhaseTimings.MAX_CAPACITY + 1) }
        assertEquals(0, CompilerPhaseTimings(0).snapshot().capacity)
        assertEquals(CompilerPhaseTimings.MAX_CAPACITY,
            CompilerPhaseTimings(CompilerPhaseTimings.MAX_CAPACITY).snapshot().capacity)
        assertEquals(17, CompilerPhase.entries.size)
        assertEquals(CompilerPhase.entries.size, CompilerPhase.entries.map { it.label }.distinct().size)
        assertTrue(CompilerPhase.entries.all { it.label.matches(Regex("[a-z_]+")) })
    }

    @Test fun observedAndDefaultDenialsKeepIdenticalResultsInputAndAuthorityBehavior() = withInput { installation, base ->
        var authorityCalls = 0
        var observerCalls = 0
        val provider = KotlinJvmCompilerProvider(installation, ExecutionAuthority { authorityCalls++; null },
            executionObserver = { _, _ -> observerCalls++ })
        val input = base.copy(execution = provider.executionProfile(base))
        val serialized = ContractJson.encodeToString(AnalysisInput.serializer(), input)
        val key = input.analysisKey()
        val normal = provider.analyze(input, CancellationToken.NONE)
        val timings = CompilerPhaseTimings()
        val observed = provider.analyzeObserved(input, CancellationToken.NONE, timings)
        assertEquals(normal, observed)
        assertEquals(listOf("execution_not_authorized"), observed.reasons)
        assertEquals(2, authorityCalls)
        assertEquals(0, observerCalls)
        assertEquals(key, input.analysisKey())
        assertEquals(serialized, ContractJson.encodeToString(AnalysisInput.serializer(), input))
        assertEquals(listOf(CompilerPhase.PROVIDER_CALL), timings.snapshot().intervals.map { it.phase })
        assertEquals(true, timings.snapshot().intervals.single().actionReturnedNormally)
        assertTrue(timings.snapshot().observationsComplete, "Complete observations do not mean authorized execution")
        assertFalse(observed.complete())
    }

    @Test fun cancellationAndVersionRefusalsStillPrecedeAuthorityAndAllPreparation() = withInput { installation, base ->
        val provider = KotlinJvmCompilerProvider(installation, ExecutionAuthority { error("Must not ask for authority") },
            executionObserver = { _, _ -> error("Must not execute") })
        for ((input, cancellation, reason) in listOf(
            Triple(base, CancellationToken { true }, "cancelled_before_execution"),
            Triple(base.copy(context = base.context.copy(compilerVersion = "2.4.20")), CancellationToken.NONE,
                "compiler_version_not_pinned_2_4_21"))) {
            val timings = CompilerPhaseTimings()
            val result = provider.analyzeObserved(input, cancellation, timings)
            assertEquals(listOf(reason), result.reasons)
            assertEquals(listOf(CompilerPhase.PROVIDER_CALL), timings.snapshot().intervals.map { it.phase })
        }
    }

    @Test fun preflightFailureRetainsOriginalRefusalAndDoesNotEnterRunnerOrLaterChecks() = withInput { installation, base ->
        var authority: ExecutionAuthority = ExecutionAuthority.DENY
        var checks = 0
        val provider = KotlinJvmCompilerProvider(installation, ExecutionAuthority { authority.authorize(it) },
            executionObserver = { _, _ -> error("Must not execute") })
        val input = base.copy(execution = provider.executionProfile(base))
        authority = ExactExecutionAuthority(ExecutionPermit(input.execution!!, input.snapshot.snapshotId,
            Path.of(input.materializedRoot), input.analysisKey(), {
                checks++
                throw ExecutionProblem("authored_snapshot_refusal", "unit-only refusal before any process")
            }, { error("Must not prepare output") }))
        val normal = provider.analyze(input, CancellationToken.NONE)
        val timings = CompilerPhaseTimings()
        val observed = provider.analyzeObserved(input, CancellationToken.NONE, timings)
        assertEquals(normal, observed)
        assertEquals(listOf("authored_snapshot_refusal"), observed.reasons)
        assertEquals(2, checks)
        assertEquals(listOf(CompilerPhase.PROVIDER_CALL, CompilerPhase.INITIAL_VERIFICATION,
            CompilerPhase.SNAPSHOT_VERIFICATION), timings.snapshot().intervals.map { it.phase })
        assertEquals(listOf(true, false, false), timings.snapshot().intervals.map { it.actionReturnedNormally })
    }

    @Test fun observedToolchainChecksRetainCheckpointOrderCountsAndOriginalCancellation() = withInput { installation, _ ->
        var normalChecks = 0
        installation.verify { normalChecks++ }
        var observedChecks = 0
        val timings = CompilerPhaseTimings()
        installation.verifyObserved({ observedChecks++ }, timings)
        assertEquals(normalChecks, observedChecks)
        assertEquals(listOf(CompilerPhase.JDK_RUNTIME_VERIFICATION, CompilerPhase.COMPILER_CLASSPATH_VERIFICATION),
            timings.snapshot().intervals.map { it.phase })
        for (observed in listOf(false, true)) {
            var calls = 0
            val original = CancellationException("original checkpoint cancellation")
            val receipt = if (observed) CompilerPhaseTimings() else null
            assertSame(original, assertFailsWith<CancellationException> {
                installation.verifyObserved({ if (++calls == 3) throw original }, receipt)
            })
            assertEquals(3, calls)
            receipt?.let { assertEquals(false, it.snapshot().intervals.single().actionReturnedNormally) }
        }
    }

    @Test fun modelOnlyDenialDoesNotAcquireLegacyAuthorityOrRecordFallbackWork() = withInput { installation, base ->
        val provider = KotlinJvmCompilerProvider(installation, ExecutionAuthority { error("No legacy authority") },
            inputMode = KotlinJvmInputMode.VERIFIED_MODEL, modelAuthority = KotlinJvmModelAuthority.DENY,
            executionObserver = { _, _ -> error("Must not execute") })
        val timings = CompilerPhaseTimings()
        val result = provider.analyzeObserved(base, CancellationToken.NONE, timings)
        assertEquals(listOf("compiler_model_not_authorized"), result.reasons)
        assertEquals(listOf(CompilerPhase.PROVIDER_CALL), timings.snapshot().intervals.map { it.phase })
    }

    private fun withInput(action: (KotlinJvmToolchain, AnalysisInput) -> Unit) {
        val root = Files.createTempDirectory("ktc-compiler-timings-unit-").toRealPath()
        try {
            val artifact = Files.writeString(root.resolve("kotlin-compiler-embeddable-2.4.21.jar"),
                "not a compiler; never executed")
            val installation = KotlinJvmToolchain(SyntheticJdk.create(root.resolve("jdk")), listOf(
                ArtifactIdentity("unit:never-run", fileDigest(artifact), artifact.toString())))
            val materialized = Files.createDirectory(root.resolve("snapshot"))
            val text = "fun example() = 1\n"
            Files.writeString(materialized.resolve("source.kt"), text)
            val snapshot = SnapshotManifest(Digests.sha256("timing-unit-snapshot"), "unit", "unit", listOf(
                SnapshotFile("source.kt", Digests.sha256(text), text.toByteArray(Charsets.UTF_8).size.toLong())))
            action(installation, AnalysisInput(snapshot, materialized.toString(), AnalysisContext(
                RuleContext("jvm", "synthetic", ":unit", "main"), "2.4.21", compilationFiles = listOf("source.kt"))))
        } finally { root.toFile().deleteRecursively() }
    }
}

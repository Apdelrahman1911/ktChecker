package dev.ktchecker.compiler

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/**
 * Authored scalar/ownership/denial controls, not a real compiler-timeout reproduction.
 * Synthetic actions test the internal adapter, not runner wiring. The real-provider controls use
 * text-only installations and either refuse before run or refuse its second snapshot check.
 * Their outputRoot is also null: no control may reach native launch or execute the synthetic JDK.
 */
class CompilerCallObservationTest {
    @Test fun normalRefusalReturnsTheSameInstanceExactlyOnceWithoutClaimingSuccess() {
        val observation = CompilerCallObservation()
        val original = result()
        var calls = 0
        val actual = observation.observeCall(SUPPORTED) { capture ->
            calls++
            checkNotNull(capture).timings.observe(CompilerPhase.PROVIDER_CALL) { original }
        }
        assertSame(original, actual)
        assertEquals(1, calls)
        val snapshot = observation.snapshot()
        assertEquals(CompilerObservationCallState.SETTLED, snapshot.state)
        assertEquals(0L, snapshot.extraCallsAtRead)
        val call = assertNotNull(snapshot.call)
        assertTrue(call.providerReturnedNormally)
        assertEquals("unavailable", assertNotNull(call.providerReturn.value).status)
        assertEquals(true, call.phases.intervals.single().actionReturnedNormally)
        assertFalse(actual.complete())
    }

    @Test fun originalPrimaryExceptionsRemainIdenticalWithoutRetryOrSuppression() {
        for (primary in listOf(IllegalStateException("synthetic primary"),
                CancellationException("synthetic cancellation"), AssertionError("synthetic error"))) {
            val observation = CompilerCallObservation()
            var calls = 0
            val caught = assertFailsWith<Throwable> {
                observation.observeCall(SUPPORTED) { capture ->
                    calls++
                    checkNotNull(capture).timings.observe(CompilerPhase.PROVIDER_CALL) { throw primary }
                }
            }
            assertSame(primary, caught)
            assertEquals(1, calls)
            assertTrue(primary.suppressed.isEmpty())
            val call = assertNotNull(observation.snapshot().call)
            assertFalse(call.providerReturnedNormally)
            assertEquals(CompilerObservationCaptureState.NOT_REACHED, call.providerReturn.state)
            assertNull(call.providerReturn.value)
            assertEquals(false, call.phases.intervals.single().actionReturnedNormally)
        }
    }

    @Test fun syntheticRawAdjustedAndFinalScalarsRemainDistinct() {
        val observation = CompilerCallObservation()
        val raw = ProcessOutcome("cancelled", listOf("execution_cancelled"), exitCode = 137,
            cancelled = true, executionConfirmed = true, metrics = AnalysisMetrics(31, 4, 1_234, 777))
        val adjusted = raw.copy(status = "failed", reasons = listOf("execution_timeout", "compiler_total_timeout"),
            cancelled = false, timedOut = true)
        val final = result("failed", adjusted.reasons + "compiler_build_failed:137",
            raw.metrics.copy(elapsedMillis = 47))
        assertSame(final, observation.observeCall(SUPPORTED) { capture ->
            val local = checkNotNull(capture)
            local.timings.observe(CompilerPhase.PROVIDER_CALL) {
                val returned = local.timings.observe(CompilerPhase.RUNNER_CALL) { raw }
                // Authored adjusted value, not a substituted production clock or timeout experiment.
                local.captureOutcomes(returned, adjusted, SUPPORTED)
                local.timings.observe(CompilerPhase.EXECUTION_OBSERVER) { Unit }
                local.timings.observe(CompilerPhase.RESULT_NORMALIZATION) { final }
            }
        })
        val call = assertNotNull(observation.snapshot().call)
        assertProcessSummary(raw, assertNotNull(call.runnerReturnedOutcome.value))
        assertProcessSummary(adjusted, assertNotNull(call.providerAdjustedOutcome.value))
        assertProviderSummary(final, assertNotNull(call.providerReturn.value))
        assertTrue(call.runnerReturnedOutcome.value!!.cancelled)
        assertFalse(call.providerAdjustedOutcome.value!!.cancelled)
        assertTrue(call.providerAdjustedOutcome.value!!.timedOut)
        assertEquals(31L, call.runnerReturnedOutcome.value!!.metrics.elapsedMillis)
        assertEquals(47L, call.providerReturn.value!!.metrics.elapsedMillis)
        assertEquals(4L, call.providerReturn.value!!.metrics.cpuMillis)
        assertTrue(call.providerReturnedNormally, "Normal return does not mean the compilation succeeded")
        assertFalse(final.complete())
    }

    @Test fun mutableListsCannotChangeSnapshotsAndPrivateGraphsAreNotProjected() {
        val rawReasons = mutableListOf("execution_timeout")
        val finalReasons = mutableListOf("compiler_total_timeout")
        val diagnostics = mutableListOf(RawDiagnostic("PRIVATE-DIAGNOSTIC-SENTINEL"))
        val findings = mutableListOf(Finding(RULE, "PRIVATE-TITLE-SENTINEL", "PRIVATE-DIAGNOSIS-SENTINEL",
            "PRIVATE-WHY-SENTINEL", SourceLocation("PRIVATE-PATH-SENTINEL", digest('a')),
            "PRIVATE-FINGERPRINT-SENTINEL", CompilerRuleRegistry.PROVIDER_ID))
        val raw = ProcessOutcome("failed", rawReasons, stdout = "PRIVATE-STDOUT-SENTINEL",
            stderr = "PRIVATE-STDERR-SENTINEL", outputDirectory = Path.of("/PRIVATE-OUTPUT-SENTINEL"),
            command = listOf("PRIVATE-COMMAND-SENTINEL"))
        val original = result("failed", finalReasons).copy(findings = findings, rawDiagnostics = diagnostics)
        val input = documentaryInput()
        val observation = CompilerCallObservation()
        observation.observeCall(SUPPORTED) { capture ->
            val local = checkNotNull(capture)
            local.timings.observe(CompilerPhase.PROVIDER_CALL) {
                local.captureIdentity(input.analysisKey(), input, digest('d'), digest('e'), 768)
                local.captureOutcomes(raw, raw, SUPPORTED)
                original
            }
        }
        val first = observation.snapshot()
        rawReasons.clear(); rawReasons += "PRIVATE-REASON-SENTINEL"
        finalReasons.clear(); findings.clear(); diagnostics.clear()
        val later = observation.snapshot()
        assertEquals(first, later)
        val call = assertNotNull(first.call)
        assertEquals(listOf("execution_timeout"), call.runnerReturnedOutcome.value!!.reasons.values)
        assertEquals(listOf("compiler_total_timeout"), call.providerReturn.value!!.reasons.values)
        assertEquals(1, call.providerReturn.value!!.findingCount)
        assertEquals(1, call.providerReturn.value!!.rawDiagnosticCount)
        assertFalse(first.toString().contains("PRIVATE-"))
        assertFailsWith<UnsupportedOperationException> {
            (call.runnerReturnedOutcome.value!!.reasons.values as MutableList<String>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (call.phases.intervals as MutableList<CompilerObservedPhaseInterval>).clear()
        }
    }

    @Test fun reasonInspectionIsBoundedEvenWhenTheInputListAdvertisesMaximumSize() {
        var inspected = 0
        val reasons = object : AbstractList<String>() {
            override val size: Int get() = Int.MAX_VALUE
            override fun get(index: Int): String {
                assertTrue(index in 0 until 64)
                inspected++
                return "execution_timeout"
            }
        }
        val observation = CompilerCallObservation()
        val original = result("failed", reasons)
        assertSame(original, observation.observeCall(SUPPORTED) { original })
        val copied = assertNotNull(observation.snapshot().call).providerReturn.value!!.reasons
        assertEquals(64, inspected)
        assertEquals(Int.MAX_VALUE, copied.originalCount)
        assertEquals(64, copied.inspectedCount)
        assertEquals(Int.MAX_VALUE - 64, copied.uninspectedCount)
        assertEquals(0, copied.omittedInspectedCount)
        assertEquals(64, copied.values.size)
        assertFalse(copied.exact)
    }

    @Test fun unsafeReasonPayloadsAreOmittedRatherThanTruncatedOrClaimedExact() {
        val safe = listOf("execution_timeout", "compiler_build_failed:137", "compiler_build_failed:not_exited",
            "compiler_build_failed:-2147483648", "compiler_execution_or_version_untrusted:$RULE")
        val reasons = safe + listOf("PRIVATE-SHORT-CODE", "compiler_build_failed:+137", "compiler_build_failed:0137",
            "compiler_build_failed:2147483648", "compiler_execution_or_version_untrusted:ktc.compiler.common.unknown",
            "compiler_total_timeout:" + "x".repeat(256))
        val observation = CompilerCallObservation()
        observation.observeCall(SUPPORTED) { result("failed", reasons) }
        val copied = observation.snapshot().call!!.providerReturn.value!!
        assertEquals(safe, copied.reasons.values)
        assertEquals(reasons.size, copied.reasons.originalCount)
        assertEquals(reasons.size - safe.size, copied.reasons.omittedInspectedCount)
        assertEquals(0, copied.reasons.uninspectedCount)
        assertFalse(copied.reasons.exact)
        assertFalse(copied.fieldsComplete)
        assertTrue(copied.reasons.values.all { it.length <= 256 })
        assertFalse(copied.toString().contains("PRIVATE-"))
    }

    @Test fun unknownStatusesAndForeignProviderIdentityAreExplicitlyIncomplete() {
        val observation = CompilerCallObservation()
        val original = ProviderResult("PRIVATE-PROVIDER", "PRIVATE-STATUS", providerVersion = "PRIVATE-VERSION")
        observation.observeCall(SUPPORTED) { capture ->
            checkNotNull(capture).captureOutcomes(ProcessOutcome("PRIVATE-RAW-STATUS"),
                ProcessOutcome("PRIVATE-ADJUSTED-STATUS"), SUPPORTED)
            original
        }
        val call = observation.snapshot().call!!
        assertEquals(CompilerObservationCaptureState.CAPTURED, call.providerReturn.state)
        assertNull(call.runnerReturnedOutcome.value!!.status)
        assertNull(call.providerAdjustedOutcome.value!!.status)
        assertNull(call.providerReturn.value!!.status)
        assertFalse(call.providerReturn.value!!.providerIdentityMatches)
        assertFalse(call.runnerReturnedOutcome.value!!.fieldsComplete)
        assertFalse(call.providerReturn.value!!.fieldsComplete)
        assertFalse(call.toString().contains("PRIVATE-"))
    }

    @Test fun rawAndAdjustedCopyFailuresAreIndependentlyQuarantined() {
        val broken = ProcessOutcome("failed", failingReasons())
        val good = ProcessOutcome("failed", listOf("execution_timeout"))
        for ((raw, adjusted) in listOf(broken to good, good to broken)) {
            val observation = CompilerCallObservation()
            val original = result()
            assertSame(original, observation.observeCall(SUPPORTED) { capture ->
                checkNotNull(capture).captureOutcomes(raw, adjusted, SUPPORTED)
                original
            })
            val call = observation.snapshot().call!!
            assertEquals(if (raw === broken) CompilerObservationCaptureState.CAPTURE_FAILED
                else CompilerObservationCaptureState.CAPTURED, call.runnerReturnedOutcome.state)
            assertEquals(if (adjusted === broken) CompilerObservationCaptureState.CAPTURE_FAILED
                else CompilerObservationCaptureState.CAPTURED, call.providerAdjustedOutcome.state)
            assertEquals(raw === broken, call.runnerReturnedOutcome.value == null)
            assertEquals(adjusted === broken, call.providerAdjustedOutcome.value == null)
            assertEquals(CompilerObservationCaptureState.CAPTURED, call.providerReturn.state)
            assertTrue(call.providerReturnedNormally)
        }
    }

    @Test fun finalResultCopyFailureCannotReplaceTheOriginalNormallyReturnedObject() {
        val original = result("failed", failingReasons())
        val observation = CompilerCallObservation()
        var calls = 0
        assertSame(original, observation.observeCall(SUPPORTED) { capture ->
            calls++
            checkNotNull(capture).timings.observe(CompilerPhase.PROVIDER_CALL) { original }
        })
        assertEquals(1, calls)
        val call = observation.snapshot().call!!
        assertTrue(call.providerReturnedNormally)
        assertEquals(CompilerObservationCaptureState.CAPTURE_FAILED, call.providerReturn.state)
        assertNull(call.providerReturn.value)
        assertEquals(true, call.phases.intervals.single().actionReturnedNormally)
    }

    @Test fun identityCopyOmitsInvalidOrOversizeStringsWithoutRetainingPaths() {
        val base = documentaryInput()
        val input = base.copy(snapshot = base.snapshot.copy(snapshotId = "PRIVATE-SNAPSHOT-SENTINEL"),
            context = base.context.copy(configurationDigest = "x".repeat(8_192),
                modelDigest = "sha256:" + "A".repeat(64), toolchainDigest = "PRIVATE-TOOLCHAIN-SENTINEL"),
            execution = base.execution!!.copy(executableDigest = "sha256:short"))
        val actualKey = input.analysisKey()
        val capture = CompilerCallCapture()
        capture.captureIdentity(actualKey, input, digest('d'), "x".repeat(8_192), 768)
        capture.finish(true)
        val slot = capture.snapshot().identity
        assertEquals(CompilerObservationCaptureState.CAPTURED, slot.state)
        val identity = assertNotNull(slot.value)
        assertEquals(actualKey, identity.analysisKey)
        assertNull(identity.snapshotId)
        assertNull(identity.configurationDigest)
        assertNull(identity.modelDigest)
        assertEquals(digest('b'), identity.dependencyDigest)
        assertNull(identity.declaredToolchainDigest)
        assertEquals(digest('d'), identity.installedToolchainDigest)
        assertNull(identity.executableDigest)
        assertNull(identity.mappingResourceDigest)
        assertEquals(768, identity.compilerHeapMiB)
        assertEquals(60_000L, identity.timeoutMillis)
        assertEquals(1_048_576, identity.maxOutputBytes)
        assertEquals(1, identity.profileEnvironmentEntries)
        assertTrue(identity.identityFieldsOmitted)
        assertFalse(identity.toString().contains("PRIVATE-"))
        assertFalse(identity.toString().contains("x".repeat(72)))
    }

    @Test fun absentIdentityAndFailedIdentityCopyAreDifferentStates() {
        val untouched = CompilerCallCapture().also { it.finish(false) }.snapshot()
        assertEquals(CompilerObservationCaptureState.NOT_REACHED, untouched.identity.state)
        assertNull(untouched.identity.value)
        val broken = CompilerCallCapture()
        broken.captureIdentity(digest('a'), documentaryInput().copy(execution = null), digest('d'), digest('e'), 768)
        broken.finish(true)
        val copied = broken.snapshot()
        assertEquals(CompilerObservationCaptureState.CAPTURE_FAILED, copied.identity.state)
        assertNull(copied.identity.value)
        assertEquals(CompilerObservationCaptureState.NOT_REACHED, copied.runnerReturnedOutcome.state)
    }

    @Test fun ownerAttachmentIsOneShotAndRejectsModelProvidersBeforeAttachment() = withInput { installation, _ ->
        val observation = CompilerCallObservation()
        val model = KotlinJvmCompilerProvider(installation, inputMode = KotlinJvmInputMode.VERIFIED_MODEL)
        assertFailsWith<IllegalArgumentException> { observation.observedProvider(model, digest('a')) }
        assertFalse(observation.snapshot().ownerAttached)
        val provider = KotlinJvmCompilerProvider(installation)
        val wrapper = observation.observedProvider(provider, digest('a'))
        assertNotSame(provider, wrapper)
        assertFalse(wrapper is KotlinJvmCompilerProvider)
        assertEquals(provider.id, wrapper.id)
        assertEquals(provider.version, wrapper.version)
        assertSame(provider.supportedRuleIds, wrapper.supportedRuleIds)
        assertEquals(digest('a'), observation.snapshot().ownerCodeDigest)
        assertFailsWith<IllegalStateException> { observation.observedProvider(provider, digest('b')) }
        assertEquals(digest('a'), observation.snapshot().ownerCodeDigest)
        assertEquals(CompilerObservationCallState.WAITING, observation.snapshot().state)
    }

    @Test fun invalidOwnerDigestIsOmittedWithoutChangingProviderRefusal() = withInput { installation, input ->
        val observation = CompilerCallObservation()
        val provider = KotlinJvmCompilerProvider(installation)
        val wrapper = observation.observedProvider(provider, "PRIVATE-OWNER-SENTINEL".repeat(100))
        val actual = wrapper.analyze(input, CancellationToken.NONE)
        assertEquals(listOf("execution_not_authorized"), actual.reasons)
        val snapshot = observation.snapshot()
        assertTrue(snapshot.ownerAttached)
        assertNull(snapshot.ownerCodeDigest)
        assertTrue(snapshot.ownerCodeDigestOmitted)
        assertEquals(CompilerObservationCallState.SETTLED, snapshot.state)
        assertFalse(snapshot.toString().contains("PRIVATE-"))
    }

    @Test fun activeReadersNeverProjectRecorderAndConcurrentOrLateCallsAreCountedNotRecorded() {
        val observation = CompilerCallObservation()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val firstResult = result("failed", listOf("execution_timeout"), AnalysisMetrics(11))
        val extraResult = result("unavailable", listOf("execution_not_authorized"), AnalysisMetrics(22))
        val future = executor.submit(Callable {
            observation.observeCall(SUPPORTED) { capture ->
                val local = checkNotNull(capture)
                local.timings.observe(CompilerPhase.PROVIDER_CALL) {
                    local.timings.observe(CompilerPhase.RUNNER_CALL) {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS)) { "bounded synthetic release was not received" }
                    }
                    firstResult
                }
            }
        })
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val active = observation.snapshot()
            assertEquals(CompilerObservationCallState.ACTIVE, active.state)
            assertNull(active.call)
            assertFalse(active.snapshotFailed)
            assertSame(extraResult, observation.observeCall(SUPPORTED) { capture -> assertNull(capture); extraResult })
            val during = observation.snapshot()
            assertEquals(CompilerObservationCallState.ACTIVE, during.state)
            assertNull(during.call)
            assertEquals(1L, during.extraCallsAtRead)
            release.countDown()
            assertSame(firstResult, future.get(10, TimeUnit.SECONDS))
            val settled = observation.snapshot()
            assertEquals(CompilerObservationCallState.SETTLED, settled.state)
            assertEquals(1L, settled.extraCallsAtRead)
            assertEquals(listOf("provider_call", "runner_call"), settled.call!!.phases.intervals.map { it.phase })
            assertTrue(settled.call!!.phases.allRecordedActionsFinished)
            assertEquals(11L, settled.call!!.providerReturn.value!!.metrics.elapsedMillis)
            assertSame(extraResult, observation.observeCall(SUPPORTED) { capture -> assertNull(capture); extraResult })
            assertEquals(2L, observation.snapshot().extraCallsAtRead)
            assertEquals(1L, settled.extraCallsAtRead, "A detached snapshot cannot attest to later submissions")
            assertEquals(0L, active.extraCallsAtRead)
            assertNull(active.call)
        } finally {
            release.countDown()
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    @Test fun distinctHandlesCannotMixCallsOrExtraCounts() {
        val first = CompilerCallObservation()
        val second = CompilerCallObservation()
        first.observeCall(SUPPORTED) { result(metrics = AnalysisMetrics(1)) }
        assertEquals(CompilerObservationCallState.WAITING, second.snapshot().state)
        second.observeCall(SUPPORTED) { result(metrics = AnalysisMetrics(2)) }
        first.observeCall(SUPPORTED) { capture -> assertNull(capture); result() }
        assertEquals(1L, first.snapshot().extraCallsAtRead)
        assertEquals(0L, second.snapshot().extraCallsAtRead)
        assertEquals(1L, first.snapshot().call!!.providerReturn.value!!.metrics.elapsedMillis)
        assertEquals(2L, second.snapshot().call!!.providerReturn.value!!.metrics.elapsedMillis)
    }

    @Test fun syntheticAdjustmentFailureLeavesRawSlotAbsentWithoutFalsifyingRunnerReturn() {
        val observation = CompilerCallObservation()
        val primary = AssertionError("synthetic adjustment failure after runner returned")
        assertSame(primary, assertFailsWith<AssertionError> {
            observation.observeCall(SUPPORTED) { capture ->
                val local = checkNotNull(capture)
                local.timings.observe(CompilerPhase.PROVIDER_CALL) {
                    local.timings.observe(CompilerPhase.RUNNER_CALL) { ProcessOutcome("failed") }
                    // No capture is allowed before the original adjustment decision completes.
                    throw primary
                }
            }
        })
        val call = observation.snapshot().call!!
        assertEquals(CompilerObservationCaptureState.NOT_REACHED, call.runnerReturnedOutcome.state)
        assertEquals(CompilerObservationCaptureState.NOT_REACHED, call.providerAdjustedOutcome.state)
        assertEquals(true, call.phases.intervals.single { it.phase == "runner_call" }.actionReturnedNormally)
        assertEquals(false, call.phases.intervals.single { it.phase == "provider_call" }.actionReturnedNormally)
        assertFalse(call.providerReturnedNormally)
        assertTrue(primary.suppressed.isEmpty())
    }

    @Test fun internalProjectionPreservesDroppedAndInvalidClockFlags() {
        val timings = CompilerPhaseTimings(1) { throw AssertionError("existing internal recorder seam") }
        val capture = CompilerCallCapture(timings)
        timings.observe(CompilerPhase.PROVIDER_CALL) {
            timings.observe(CompilerPhase.SNAPSHOT_VERIFICATION) { Unit }
        }
        capture.finish(true)
        val phases = capture.snapshot().phases
        assertEquals(1, phases.capacity)
        assertEquals(1L, phases.droppedPhaseCount)
        assertFalse(phases.clockValid)
        assertTrue(phases.nestingValid)
        assertTrue(phases.allRecordedActionsFinished)
        assertFalse(phases.observationsComplete)
        assertNull(phases.intervals.single().elapsedNanos)
    }

    @Test fun internalProjectionPreservesOpenAndInvalidNestingFlagsWithoutOwnerClockHooks() {
        val timings = CompilerPhaseTimings()
        val capture = CompilerCallCapture(timings)
        val outer = timings.begin(CompilerPhase.PROVIDER_CALL)
        val inner = timings.begin(CompilerPhase.INITIAL_VERIFICATION)
        timings.end(outer, true) // Intentionally invalid recorder-test ordering, not provider behavior.
        timings.end(inner, true)
        capture.finish(true)
        val phases = capture.snapshot().phases
        assertTrue(phases.clockValid)
        assertFalse(phases.nestingValid)
        assertFalse(phases.allRecordedActionsFinished)
        assertFalse(phases.observationsComplete)
        assertNull(phases.intervals.first().actionReturnedNormally)
        timings.end(outer, true)
        assertNull(phases.intervals.first().endNanos, "The detached projection cannot change later")
    }

    @Test fun ordinaryDefaultAndObservedDenialsPreserveInputAndAuthorityBehavior() = withInput { installation, base ->
        var authorities = 0
        var observers = 0
        val provider = KotlinJvmCompilerProvider(installation, ExecutionAuthority { authorities++; null },
            executionObserver = { _, _ -> observers++ })
        val input = base.copy(execution = provider.executionProfile(base))
        val encoded = ContractJson.encodeToString(AnalysisInput.serializer(), input)
        val key = input.analysisKey()
        val observation = CompilerCallObservation()
        val normal = provider.analyze(input, CancellationToken.NONE)
        val observed = observation.observedProvider(provider, digest('a')).analyze(input, CancellationToken.NONE)
        assertEquals(normal, observed)
        assertEquals(2, authorities)
        assertEquals(0, observers)
        assertEquals(key, input.analysisKey())
        assertEquals(encoded, ContractJson.encodeToString(AnalysisInput.serializer(), input))
        val call = observation.snapshot().call!!
        assertEquals(CompilerObservationCaptureState.NOT_REACHED, call.identity.state)
        assertEquals(CompilerObservationCaptureState.NOT_REACHED, call.runnerReturnedOutcome.state)
        assertEquals(listOf("provider_call"), call.phases.intervals.map { it.phase })
        assertProviderSummary(observed, assertNotNull(call.providerReturn.value))
        assertFalse(observed.complete())
    }

    @Test fun preflightIdentityUsesActualFrozenKeyNotTheLaterMutatedCallerGraph() = withInput { installation, base ->
        val callerRules = mutableListOf(RULE)
        var caller: AnalysisInput? = null
        var frozenKey: String? = null
        var authorizations = 0
        var verifications = 0
        val registry = CompilerRuleRegistry.load()
        val provider = KotlinJvmCompilerProvider(installation, ExecutionAuthority { request ->
            authorizations++
            assertNotSame(caller, request)
            assertNotSame(callerRules, request.requiredRuleIds)
            frozenKey = request.analysisKey()
            callerRules += RULE // A test-owner mutation after freeze, not a provider mutation.
            ExecutionPermit(request.execution!!, request.snapshot.snapshotId, Path.of(request.materializedRoot),
                frozenKey!!, {
                    verifications++
                    throw ExecutionProblem("snapshot_verification_failed", "synthetic preflight refusal")
                }, { error("No output may be prepared") })
        }, registry = registry, executionObserver = { _, _ -> error("No runner is reached") })
        val input = base.copy(requiredRuleIds = callerRules, execution = provider.executionProfile(base))
        caller = input
        val before = input.analysisKey()
        val observation = CompilerCallObservation()
        val actual = observation.observedProvider(provider, digest('a')).analyze(input, CancellationToken.NONE)
        assertEquals(1, authorizations)
        assertEquals(1, verifications)
        assertEquals(listOf("snapshot_verification_failed"), actual.reasons)
        val call = observation.snapshot().call!!
        val identity = assertNotNull(call.identity.value)
        assertEquals(before, identity.analysisKey)
        assertEquals(frozenKey, identity.analysisKey)
        assertNotEquals(input.analysisKey(), identity.analysisKey)
        assertEquals(base.snapshot.snapshotId, identity.snapshotId)
        assertEquals(installation.digest, identity.installedToolchainDigest)
        assertEquals(installation.executableDigest, identity.executableDigest)
        assertEquals(registry.mappingIdentity.resourceDigest, identity.mappingResourceDigest)
        assertFalse(identity.identityFieldsOmitted)
        assertEquals(768, identity.compilerHeapMiB)
        assertEquals(60_000L, identity.timeoutMillis)
        assertEquals(1_048_576, identity.maxOutputBytes)
        assertEquals(0, identity.profileEnvironmentEntries)
        assertEquals(CompilerObservationCaptureState.NOT_REACHED, call.runnerReturnedOutcome.state)
        assertEquals(listOf(true, false, false), call.phases.intervals.map { it.actionReturnedNormally })
        assertTrue(call.providerReturnedNormally)
        assertFalse(actual.complete())
    }

    @Test fun actualFreezeFailureLeavesIdentityAbsentAndRethrowsTheSamePrimaryOnce() = withInput { installation, base ->
        val primary = AssertionError("synthetic freeze error")
        var accesses = 0
        var authorizations = 0
        val files = object : AbstractList<SnapshotFile>() {
            override val size: Int get() = 1
            override fun get(index: Int): SnapshotFile { accesses++; throw primary }
        }
        val provider = KotlinJvmCompilerProvider(installation, ExecutionAuthority { authorizations++; null })
        val observation = CompilerCallObservation()
        val input = base.copy(snapshot = base.snapshot.copy(files = files))
        assertSame(primary, assertFailsWith<AssertionError> {
            observation.observedProvider(provider, digest('a')).analyze(input, CancellationToken.NONE)
        })
        assertEquals(1, accesses)
        assertEquals(0, authorizations)
        assertTrue(primary.suppressed.isEmpty())
        val call = observation.snapshot().call!!
        assertFalse(call.providerReturnedNormally)
        assertEquals(CompilerObservationCaptureState.NOT_REACHED, call.identity.state)
        assertEquals(CompilerObservationCaptureState.NOT_REACHED, call.providerReturn.state)
        assertEquals(false, call.phases.intervals.single().actionReturnedNormally)
    }

    @Test fun actualRunnerOnCompleteThrowIsNotMisreportedAsReturnedRunnerOutcome() {
        val primary = IllegalStateException("synthetic onComplete failure")
        var completions = 0
        var observers = 0
        withRunnerRefusal(onComplete = { completions++; throw primary }, executionObserver = { _, _ -> observers++ }) { provider, input ->
            val observation = CompilerCallObservation()
            assertSame(primary, assertFailsWith<IllegalStateException> {
                observation.observedProvider(provider, digest('a')).analyze(input, CancellationToken.NONE)
            })
            assertEquals(1, completions)
            assertEquals(0, observers)
            assertTrue(primary.suppressed.isEmpty())
            val call = observation.snapshot().call!!
            assertFalse(call.providerReturnedNormally)
            assertEquals(CompilerObservationCaptureState.NOT_REACHED, call.runnerReturnedOutcome.state)
            assertEquals(CompilerObservationCaptureState.NOT_REACHED, call.providerAdjustedOutcome.state)
            assertEquals(CompilerObservationCaptureState.NOT_REACHED, call.providerReturn.state)
            assertEquals(false, call.phases.intervals.single { it.phase == "runner_call" }.actionReturnedNormally)
            assertTrue(call.phases.intervals.none { it.phase == "execution_observer" || it.phase == "result_normalization" })
        }
    }

    @Test fun actualObserverThrowKeepsEarlierCopiesAndNeverPublishesWhileCallbackIsActive() {
        val primary = IllegalStateException("synthetic executionObserver failure")
        val observation = CompilerCallObservation()
        var raw: ProcessOutcome? = null
        var adjusted: ProcessOutcome? = null
        var observers = 0
        withRunnerRefusal(onComplete = { raw = it }, executionObserver = { _, outcome ->
            adjusted = outcome
            observers++
            assertEquals(CompilerObservationCallState.ACTIVE, observation.snapshot().state)
            assertNull(observation.snapshot().call)
            throw primary
        }) { provider, input ->
            assertSame(primary, assertFailsWith<IllegalStateException> {
                observation.observedProvider(provider, digest('a')).analyze(input, CancellationToken.NONE)
            })
            assertEquals(1, observers)
            assertTrue(primary.suppressed.isEmpty())
            val call = observation.snapshot().call!!
            assertProcessSummary(assertNotNull(raw), assertNotNull(call.runnerReturnedOutcome.value))
            assertProcessSummary(assertNotNull(adjusted), assertNotNull(call.providerAdjustedOutcome.value))
            assertFalse(call.providerReturnedNormally)
            assertEquals(CompilerObservationCaptureState.NOT_REACHED, call.providerReturn.state)
            assertEquals(true, call.phases.intervals.single { it.phase == "runner_call" }.actionReturnedNormally)
            assertEquals(false, call.phases.intervals.single { it.phase == "execution_observer" }.actionReturnedNormally)
            assertTrue(call.phases.intervals.none { it.phase == "result_normalization" })
            assertTrue(call.phases.allRecordedActionsFinished)
        }
    }

    @Test fun actualRunnerRefusalCapturesThreeBoundariesWithoutLaunchingOrClaimingSuccess() {
        var raw: ProcessOutcome? = null
        var adjusted: ProcessOutcome? = null
        withRunnerRefusal(onComplete = { raw = it }, executionObserver = { _, outcome -> adjusted = outcome }) { provider, input ->
            val observation = CompilerCallObservation()
            val actual = observation.observedProvider(provider, digest('a')).analyze(input, CancellationToken.NONE)
            val snapshot = observation.snapshot()
            assertEquals(CompilerObservationCallState.SETTLED, snapshot.state)
            val call = snapshot.call!!
            assertProcessSummary(assertNotNull(raw), assertNotNull(call.runnerReturnedOutcome.value))
            assertProcessSummary(assertNotNull(adjusted), assertNotNull(call.providerAdjustedOutcome.value))
            assertProviderSummary(actual, assertNotNull(call.providerReturn.value))
            assertEquals(listOf("snapshot_verification_failed"), raw!!.reasons)
            assertNull(raw!!.outputDirectory)
            assertTrue(raw!!.command.isEmpty())
            assertFalse(raw!!.executionConfirmed)
            assertFalse(actual.complete())
            assertEquals("failed", actual.status)
            assertTrue(call.providerReturnedNormally)
            assertTrue(call.phases.allRecordedActionsFinished)
            assertEquals(true, call.phases.intervals.single { it.phase == "runner_call" }.actionReturnedNormally)
            assertTrue(call.phases.intervals.none { it.phase == "output_preparation" })
        }
    }

    private fun result(status: String = "unavailable", reasons: List<String> = listOf("execution_not_authorized"),
        metrics: AnalysisMetrics = AnalysisMetrics()): ProviderResult = ProviderResult(CompilerRuleRegistry.PROVIDER_ID,
        status, reasons = reasons, metrics = metrics, providerVersion = CompilerRuleRegistry.VERSION)

    private fun failingReasons(): List<String> = object : AbstractList<String>() {
        override val size: Int get() = 1
        override fun get(index: Int): String = throw AssertionError("synthetic observation-copy defect")
    }

    private fun documentaryInput(): AnalysisInput = AnalysisInput(
        SnapshotManifest(digest('a'), "PRIVATE-WORKSPACE-SENTINEL", "PRIVATE-WORKTREE-SENTINEL", emptyList()),
        "/PRIVATE-MATERIALIZATION-SENTINEL", AnalysisContext(configurationDigest = digest('c'), modelDigest = digest('a'),
            dependencyDigest = digest('b'), toolchainDigest = digest('d')),
        execution = TrustedExecution("/PRIVATE-EXECUTABLE-SENTINEL", listOf("PRIVATE-ARGUMENT-SENTINEL"), true,
            digest('e'), environment = mapOf("PRIVATE-ENV-KEY" to "PRIVATE-ENV-VALUE")))

    private fun assertProcessSummary(expected: ProcessOutcome, actual: CompilerObservedProcessOutcome) {
        assertEquals(expected.status, actual.status)
        assertEquals(expected.reasons, actual.reasons.values)
        assertTrue(actual.reasons.exact)
        assertEquals(expected.exitCode, actual.exitCode)
        assertEquals(expected.cancelled, actual.cancelled)
        assertEquals(expected.timedOut, actual.timedOut)
        assertEquals(expected.truncated, actual.truncated)
        assertEquals(expected.executionConfirmed, actual.executionConfirmed)
        assertMetrics(expected.metrics, actual.metrics)
    }

    private fun assertProviderSummary(expected: ProviderResult, actual: CompilerObservedProviderResult) {
        assertTrue(actual.providerIdentityMatches)
        assertEquals(expected.status, actual.status)
        assertEquals(expected.reasons, actual.reasons.values)
        assertTrue(actual.reasons.exact)
        assertEquals(expected.findings.size, actual.findingCount)
        assertEquals(expected.coverage.size, actual.coverageCount)
        assertEquals(expected.rawDiagnostics.size, actual.rawDiagnosticCount)
        assertMetrics(expected.metrics, actual.metrics)
    }

    private fun assertMetrics(expected: AnalysisMetrics, actual: CompilerObservedMetrics) {
        assertEquals(expected.elapsedMillis, actual.elapsedMillis)
        assertEquals(expected.cpuMillis, actual.cpuMillis)
        assertEquals(expected.peakRssBytes, actual.peakRssBytes)
        assertEquals(expected.outputBytes, actual.outputBytes)
    }

    private fun withRunnerRefusal(onComplete: (ProcessOutcome) -> Unit = {},
        executionObserver: (AnalysisInput, ProcessOutcome) -> Unit = { _, _ -> },
        action: (KotlinJvmCompilerProvider, AnalysisInput) -> Unit) = withInput { installation, base ->
        var authority: ExecutionAuthority = ExecutionAuthority.DENY
        var checks = 0
        val provider = KotlinJvmCompilerProvider(installation, ExecutionAuthority { authority.authorize(it) },
            runner = IsolatedProcessRunner(onComplete), executionObserver = executionObserver)
        val input = base.copy(execution = provider.executionProfile(base))
        assertNull(input.outputRoot, "A second independent no-launch guard for synthetic installations")
        authority = ExactExecutionAuthority(ExecutionPermit(input.execution!!, input.snapshot.snapshotId,
            Path.of(input.materializedRoot), input.analysisKey(), {
                if (++checks >= 2) throw ExecutionProblem("snapshot_verification_failed", "synthetic runner preflight refusal")
            }, { error("No output may be prepared or synthetic executable launched") }))
        action(provider, input)
        assertEquals(2, checks, "One provider preflight and one refusing wrapped runner check")
    }

    private fun withInput(action: (KotlinJvmToolchain, AnalysisInput) -> Unit) {
        val root = Files.createTempDirectory("ktc-compiler-observation-unit-").toRealPath()
        try {
            val artifact = Files.writeString(root.resolve("kotlin-compiler-embeddable-2.4.21.jar"),
                "not a compiler; never executed")
            val installation = KotlinJvmToolchain(SyntheticJdk.create(root.resolve("jdk")), listOf(
                ArtifactIdentity("unit:never-run", fileDigest(artifact), artifact.toString())))
            val materialized = Files.createDirectory(root.resolve("snapshot"))
            val text = "fun example() = 1\n"
            Files.writeString(materialized.resolve("source.kt"), text)
            val snapshot = SnapshotManifest(Digests.sha256("observation-unit-snapshot"), "unit", "unit", listOf(
                SnapshotFile("source.kt", Digests.sha256(text), text.toByteArray(Charsets.UTF_8).size.toLong())))
            action(installation, AnalysisInput(snapshot, materialized.toString(), AnalysisContext(
                RuleContext("jvm", "synthetic", ":unit", "main"), "2.4.21", compilationFiles = listOf("source.kt")),
                requiredRuleIds = listOf(RULE)))
        } finally { root.toFile().deleteRecursively() }
    }

    private fun digest(hex: Char): String = "sha256:" + hex.toString().repeat(64)

    companion object {
        private const val RULE = "ktc.compiler.common.abstract_member_not_implemented"
        private val SUPPORTED = setOf(RULE)
    }
}

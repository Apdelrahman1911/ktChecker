package dev.ktchecker.compiler

import dev.ktchecker.providers.runtime.OwnedSupervisorIdentity
import dev.ktchecker.providers.runtime.ProcessOutcome
import kotlin.test.*

/** Invocation/clock/ledger values only. No provider, JVM, compiler or helper execution is simulated or launched. */
class CompilerFixtureControlsTest {
    @Test fun explicitNamedCohortIsRequiredAndGradleAppendedRootMayComeLast() {
        assertFails { CompilerFixtureSettings.parse(arrayOf("fixtures")) }
        val first = CompilerFixtureSettings.parse(arrayOf("--cohort=overrides", "fixtures"))
        assertEquals(first, CompilerFixtureSettings.parse(arrayOf("fixtures", "--cohort=overrides")))
        assertEquals(48, first.maxAttempts)
        assertEquals(1_200_000L, first.overallMillis)
        assertEquals(90_000L, first.perJobMillis)
        assertEquals(268_435_456L, first.evidenceBytes)
        assertTrue(first.selects("2026-11-04-overrides"))
        assertFalse(first.selects(null))
    }

    @Test fun allIsExplicitAndStillBoundedAndNeverAcceptsUnknownAuthoredMetadata() {
        val settings = CompilerFixtureSettings.parse(arrayOf("fixtures", "--cohort=all", "--max-attempts=3"))
        assertEquals(3, settings.maxAttempts)
        assertEquals(1_200_000L, settings.overallMillis)
        assertTrue(settings.selects(null))
        assertTrue(settings.selects("2026-11-04-opt_in"))
        assertFails { settings.selects("unexpected-authoring-group") }
        assertEquals(false, settings.description()["resume_or_merge"])
        assertEquals("visible_not_run_not_excluded", settings.description()["unselected_scope"])
    }

    @Test fun parserRejectsUnknownDuplicateUnboundedOverflowingAndMissingOptions() {
        val bad = listOf(arrayOf("fixtures", "--cohort=unknown"), arrayOf("fixtures", "--cohort=overrides", "--cohort=jvm"),
            arrayOf("fixtures", "--cohort=overrides", "--max-attempts=49"), arrayOf("fixtures", "--cohort=overrides", "--overall-seconds=1201"),
            arrayOf("fixtures", "--cohort=overrides", "--per-job-seconds=91"), arrayOf("fixtures", "--cohort=overrides", "--evidence-mib=257"),
            arrayOf("fixtures", "--cohort=overrides", "--evidence-mib=23"), arrayOf("fixtures", "--cohort=overrides", "--max-attempts=0"),
            arrayOf("fixtures", "--cohort=overrides", "--max-attempts=-1"), arrayOf("fixtures", "--cohort=overrides", "--max-attempts=+1"),
            arrayOf("fixtures", "--cohort=overrides", "--max-attempts=99999999999999999999999"),
            arrayOf("fixtures", "--cohort=overrides", "--resume=true"), arrayOf("fixtures", "--cohort"),
            arrayOf("fixtures", "--cohort="), arrayOf("--cohort=overrides"), arrayOf("fixtures", "another", "--cohort=overrides"))
        bad.forEach { assertFails { CompilerFixtureSettings.parse(it) } }
    }

    @Test fun smallerOwnerBoundsRemainInsideHardMaxima() {
        val value = CompilerFixtureSettings.parse(arrayOf("--cohort=visibility", "--max-attempts=2", "--overall-seconds=4",
            "--per-job-seconds=3", "--evidence-mib=24", "fixtures"))
        assertEquals(2, value.maxAttempts)
        assertEquals(4_000L, value.overallMillis)
        assertEquals(3_000L, value.perJobMillis)
        assertEquals(25_165_824L, value.evidenceBytes)
    }

    @Test fun attemptReservationsConservativelyCountDenialsButNeverClaimExecution() {
        val budget = CompilerFixtureBudget(CompilerFixtureSettings("fixtures", "overrides", maxAttempts = 2), 0, { 0 })
        val denied = budget.reserveAttempt("version-denial", emptySet())
        denied.state = "provider_denied_before_runtime"
        val subjects = mutableSetOf("authored-id")
        val next = budget.reserveAttempt("next", subjects)
        subjects.clear()
        assertEquals(setOf("authored-id"), next.ruleIds)
        budget.requireActive() // Finishing exactly the last admitted attempt is not itself an admission failure.
        val stopped = assertFailsWith<CompilerFixtureStopped> { budget.reserveAttempt("must-not-enter", emptySet()) }
        assertEquals(CompilerFixtureStopReason.ATTEMPT_BUDGET, stopped.reason)
        assertEquals(2, budget.attempts.size)
        assertEquals("none_use_actual_process_execution_confirmed_only", denied.description()["execution_claim"])
    }

    @Test fun preparationAndStartupTimeReduceEveryFreshProfileToRemainingMonotonicBudget() {
        var now = 400_000_000L
        val settings = CompilerFixtureSettings("fixtures", "overrides", overallMillis = 1_000, perJobMillis = 900)
        val budget = CompilerFixtureBudget(settings, 0, { now })
        assertEquals(600L, budget.nextTimeoutMillis())
        val attempt = budget.reserveAttempt("first", emptySet())
        assertEquals(400L, attempt.reservedAtMillis)
        now = 800_000_000
        assertEquals(200L, budget.nextTimeoutMillis())
        now = 1_000_000_000
        assertTrue(budget.deadlineReached())
        assertEquals(CompilerFixtureStopReason.OVERALL_DEADLINE,
            assertFailsWith<CompilerFixtureStopped> { budget.nextTimeoutMillis() }.reason)
        assertEquals(1, budget.attempts.size)
    }

    @Test fun perJobHardBoundAppliesEvenWhenLargeOverallBudgetRemains() {
        val budget = CompilerFixtureBudget(CompilerFixtureSettings("fixtures", "overrides"), 0, { 1_000_000 })
        assertEquals(90_000L, budget.nextTimeoutMillis())
        assertEquals(1_199_999L, budget.remainingMillis())
    }

    @Test fun cooperativeOwnerStopPreventsNextAdmissionWithoutCancellingCurrentDeadlineOrResuming() {
        var stopped = false
        val budget = CompilerFixtureBudget(CompilerFixtureSettings("fixtures", "overrides"), 0, { 0 }, { stopped })
        budget.reserveAttempt("current", emptySet())
        stopped = true
        assertFalse(budget.deadlineReached()) // Running job's cancellation token uses deadline, not the marker.
        assertEquals(CompilerFixtureStopReason.OWNER_STOP,
            assertFailsWith<CompilerFixtureStopped> { budget.reserveAttempt("next", emptySet()) }.reason)
        stopped = false
        assertFailsWith<CompilerFixtureStopped> { budget.requireActive() }
        assertEquals(1, budget.attempts.size)
    }

    @Test fun unsafeOwnerControlAndSecondaryCleanupStopRemainExplicitLedgerReasons() {
        val budget = CompilerFixtureBudget(CompilerFixtureSettings("fixtures", "overrides"), 0, { 0 }, { error("replaced control") })
        assertEquals(CompilerFixtureStopReason.UNSAFE_CONTROL,
            assertFailsWith<CompilerFixtureStopped> { budget.requireActive() }.reason)
        budget.stop(CompilerFixtureStopReason.SCRATCH_CLEANUP, "cleanup also failed")
        assertEquals(CompilerFixtureStopReason.UNSAFE_CONTROL, budget.stopReason)
        val events = budget.description()["stop_events"] as List<*>
        assertEquals(2, events.size)
        assertTrue(budget.attempts.isEmpty())
    }

    // Receipt-value predicates only: none of these values is an execution/cleanup witness.
    private val receiptIdentity = OwnedSupervisorIdentity("synthetic-value-only", "not-a-host",
        "not-native-source-evidence", "not-native-binary-evidence")

    @Test fun providerEntryWithoutReturnOrObserverRequiresQuarantineButPreparationFailureDoesNot() {
        assertNull(compilerFixtureChildQuiescenceProblem(false, false, null, receiptIdentity, true))
        assertNotNull(compilerFixtureChildQuiescenceProblem(true, false, null, receiptIdentity, true))
        // The current provider's actual returned no-observer path is a pre-runtime refusal.
        assertNull(compilerFixtureChildQuiescenceProblem(true, true, null, receiptIdentity, true))
    }

    @Test fun confirmedExecAndExitNeverOverrideUnverifiedNativeShutdown() {
        val outcome = ProcessOutcome("failed", listOf("owned_process_group_shutdown_unverified"), exitCode = 0,
            stdout = "captured output is not a cleanup acknowledgement", supervisorIdentity = receiptIdentity,
            executionConfirmed = true)
        assertNotNull(compilerFixtureChildQuiescenceProblem(true, true, outcome, receiptIdentity, true))
    }

    @Test fun actualObserverCleanupMayRemainKnownWhenProviderThrowsAfterTheCallback() {
        val outcome = ProcessOutcome("failed", listOf("execution_timeout"), supervisorIdentity = receiptIdentity,
            executionConfirmed = true)
        assertNull(compilerFixtureChildQuiescenceProblem(true, false, outcome, receiptIdentity, true))
        assertNotNull(compilerFixtureChildQuiescenceProblem(true, false, outcome, receiptIdentity, false))
        assertNotNull(compilerFixtureChildQuiescenceProblem(false, false, outcome, receiptIdentity, true))
    }

    @Test fun missingOrDifferentActualSupervisorIdentityIsConservativelyUnknown() {
        val outcome = ProcessOutcome("unavailable", listOf("process_supervisor_bundle_unavailable"))
        assertNotNull(compilerFixtureChildQuiescenceProblem(true, true, outcome, receiptIdentity, true))
        assertNotNull(compilerFixtureChildQuiescenceProblem(true, true,
            outcome.copy(supervisorIdentity = receiptIdentity.copy(binaryDigest = "different")), receiptIdentity, true))
    }

    @Test fun cleanupAcknowledgementDoesNotRequireSuccessfulCompilerExec() {
        val outcome = ProcessOutcome("failed", listOf("process_start_failed"), supervisorIdentity = receiptIdentity)
        assertFalse(outcome.executionConfirmed)
        assertNull(compilerFixtureChildQuiescenceProblem(true, true, outcome, receiptIdentity, true))
    }

    @Test fun cleanupAndReportingKeepTheFirstFailureAndDoNotSuppressItselfOrDuplicateCauses() {
        val first = IllegalStateException("provider failed first")
        val cleanup = IllegalStateException("cleanup unavailable")
        val reporting = IllegalStateException("terminal write failed")
        assertSame(first, preserveCompilerFixtureFailure(null, first))
        assertSame(first, preserveCompilerFixtureFailure(first, cleanup))
        assertSame(first, preserveCompilerFixtureFailure(first, reporting))
        preserveCompilerFixtureFailure(first, first)
        preserveCompilerFixtureFailure(first, cleanup)
        assertEquals(listOf(cleanup, reporting), first.suppressed.toList())
    }

    @Test fun deadlineReachedDuringBulkCandidateCaptureNeverPublishesACompleteSelector() {
        var now = 0L
        val budget = CompilerFixtureBudget(CompilerFixtureSettings("fixtures", "overrides", overallMillis = 10), 0, { now })
        val publication = CompilerFixtureTerminalPublication(budget)
        now = 10_000_000 // Expensive candidate serialization/hashing/writes already consumed the budget.
        var completes = 0
        var incompletes = 0
        val stopped = assertFailsWith<CompilerFixtureStopped> {
            publication.publish(true, {}, { completes++ }, { incompletes++ })
        }
        assertEquals(CompilerFixtureStopReason.OVERALL_DEADLINE, stopped.reason)
        assertEquals(0, completes)
        assertEquals(1, incompletes)
        assertFalse(publication.committed)
    }

    @Test fun deadlineReachedDuringFinalSelectorPublicationCorrectsItToIncomplete() {
        var now = 9_000_000L
        val budget = CompilerFixtureBudget(CompilerFixtureSettings("fixtures", "overrides", overallMillis = 10), 0, { now })
        val publication = CompilerFixtureTerminalPublication(budget)
        var selectedComplete = false
        var corrections = 0
        val stopped = assertFailsWith<CompilerFixtureStopped> {
            publication.publish(true, {}, {
                selectedComplete = true
                now = 10_000_000
            }, { failure ->
                assertNotNull(failure)
                corrections++
                selectedComplete = false
            })
        }
        assertEquals(CompilerFixtureStopReason.OVERALL_DEADLINE, stopped.reason)
        assertEquals(1, corrections)
        assertFalse(selectedComplete)
        assertFalse(publication.committed)
        assertFailsWith<CompilerFixtureStopped> { budget.reserveAttempt("no-retry", emptySet()) }
    }

    @Test fun terminalCapacityPreflightPreservesCorrectionHeadroomBeforeAnyPositiveWrite() {
        val budget = CompilerFixtureBudget(CompilerFixtureSettings("fixtures", "overrides"), 0, { 0 })
        val publication = CompilerFixtureTerminalPublication(budget)
        var positives = 0
        var negatives = 0
        assertFailsWith<CompilerFixtureStopped> {
            publication.publish(true, {
                throw CompilerFixtureStopped(CompilerFixtureStopReason.EVIDENCE_BUDGET, "No bounded correction headroom")
            }, { positives++ }, { negatives++ })
        }
        assertEquals(0, positives)
        assertEquals(1, negatives)
        assertEquals(CompilerFixtureStopReason.EVIDENCE_BUDGET, budget.stopReason)
        assertFalse(publication.committed)
    }

    @Test fun failedSelectorCorrectionCannotMakeAStalePositiveStandaloneCompletionProof() {
        val budget = CompilerFixtureBudget(CompilerFixtureSettings("fixtures", "overrides"), 0, { 0 })
        val publication = CompilerFixtureTerminalPublication(budget)
        val first = IllegalStateException("selector verification failed after write")
        val correction = IllegalStateException("correction storage failed")
        var stalePositive = false
        val thrown = assertFailsWith<IllegalStateException> {
            publication.publish(true, {}, { stalePositive = true; throw first }, { throw correction })
        }
        assertSame(first, thrown)
        assertEquals(listOf(correction), first.suppressed.toList())
        assertTrue(stalePositive)
        assertFalse(publication.committed) // Successful harness completion is independently required.
        assertFalse(stalePositive && publication.committed)
    }

    @Test fun terminalSuccessIsOneShotAndLinearizesAfterTheLastBoundedWrite() {
        var now = 0L
        val budget = CompilerFixtureBudget(CompilerFixtureSettings("fixtures", "overrides", overallMillis = 10), 0, { now })
        val publication = CompilerFixtureTerminalPublication(budget)
        assertTrue(publication.publish(true, {}, { now = 9_000_000 }, { fail("No incomplete publication expected") }))
        assertTrue(publication.committed)
        now = 10_000_000 // Later reporting is not an independent second completion decision.
        assertFails { publication.publish(true, {}, {}, {}) }
    }

    @Test fun anIneligibleTerminalPublishesOnlyIncompleteAndNeverRetriesThatFailure() {
        val budget = CompilerFixtureBudget(CompilerFixtureSettings("fixtures", "overrides", overallMillis = 1), 0, { 2_000_000 })
        val publication = CompilerFixtureTerminalPublication(budget)
        var negatives = 0
        assertFalse(publication.publish(false, { fail("No positive reservation") }, { fail("No completion") }, { negatives++ }))
        assertEquals(1, negatives)
        assertFalse(publication.committed)
        val failing = CompilerFixtureTerminalPublication(budget)
        assertFails { failing.publish(false, {}, {}, { negatives++; error("incomplete write failed") }) }
        assertEquals(2, negatives)
    }
}

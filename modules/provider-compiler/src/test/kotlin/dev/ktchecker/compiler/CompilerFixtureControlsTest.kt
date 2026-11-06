package dev.ktchecker.compiler

import dev.ktchecker.providers.runtime.OwnedSupervisorIdentity
import dev.ktchecker.providers.runtime.ProcessOutcome
import kotlin.test.*

/** Invocation/clock/ledger values only. No provider, JVM, compiler or helper execution is simulated or launched. */
class CompilerFixtureControlsTest {
    private val originalRuleIds = setOf(
        "ktc.compiler.common.unresolved_reference", "ktc.compiler.common.unresolved_import",
        "ktc.compiler.common.return_type_mismatch", "ktc.compiler.common.initializer_type_mismatch",
        "ktc.compiler.common.assignment_type_mismatch", "ktc.compiler.common.argument_type_mismatch",
        "ktc.compiler.common.null_for_nonnull_type", "ktc.compiler.common.unsafe_call",
        "ktc.compiler.common.no_value_for_parameter", "ktc.compiler.common.too_many_arguments",
        "ktc.compiler.common.named_parameter_not_found", "ktc.compiler.common.argument_passed_twice",
        "ktc.compiler.common.non_vararg_spread", "ktc.compiler.common.spread_of_nullable",
        "ktc.compiler.common.val_reassignment", "ktc.compiler.common.uninitialized_variable",
        "ktc.compiler.common.no_else_in_when", "ktc.compiler.common.wrong_annotation_target",
        "ktc.compiler.common.exposed_function_return_type", "ktc.compiler.common.upper_bound_violated",
        "ktc.compiler.common.division_by_zero", "ktc.compiler.common.unreachable_code",
        "ktc.compiler.common.cast_never_succeeds", "ktc.compiler.common.useless_cast",
        "ktc.compiler.common.unnecessary_safe_call", "ktc.compiler.common.unnecessary_not_null_assertion",
        "ktc.compiler.common.deprecation", "ktc.compiler.common.empty_range",
        "ktc.compiler.jvm.jvm_static_not_in_object_or_companion", "ktc.compiler.jvm.jvm_inline_without_value_class",
        "ktc.compiler.jvm.value_class_without_jvm_inline_annotation", "ktc.compiler.jvm.inapplicable_jvm_field",
        "ktc.compiler.jvm.overloads_without_default_arguments", "ktc.compiler.jvm.overloads_interface",
        "ktc.compiler.jvm.overloads_abstract", "ktc.compiler.jvm.synchronized_on_suspend_error",
        "ktc.compiler.jvm.synchronized_on_inline", "ktc.compiler.jvm.synchronized_on_abstract",
        "ktc.compiler.jvm.external_declaration_cannot_have_body", "ktc.compiler.jvm.non_data_class_jvm_record",
        "ktc.compiler.jvm.jvm_record_not_val_parameter", "ktc.compiler.jvm.field_in_jvm_record",
    )
    private val originalWarningIds = setOf(
        "ktc.compiler.common.division_by_zero", "ktc.compiler.common.unreachable_code",
        "ktc.compiler.common.cast_never_succeeds", "ktc.compiler.common.useless_cast",
        "ktc.compiler.common.unnecessary_safe_call", "ktc.compiler.common.unnecessary_not_null_assertion",
        "ktc.compiler.common.deprecation", "ktc.compiler.common.empty_range",
        "ktc.compiler.jvm.overloads_without_default_arguments", "ktc.compiler.jvm.synchronized_on_inline",
    )

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

    @Test fun typeBoundaryCohortIsExplicitBoundedAndDoesNotSelectExistingOrUnsupportedInputs() {
        val selected = CompilerFixtureSettings.parse(arrayOf("--cohort=type_boundaries", "fixtures"))
        assertEquals(48, selected.maxAttempts)
        assertTrue(selected.selects("2026-11-04-type_boundaries"))
        assertFalse(selected.selects(null))
        assertFalse(selected.selects("2026-11-04-conventions_types_nullability"))
        assertFails { selected.selects("2026-11-04-kmp_matching") }
        assertTrue(CompilerFixtureSettings("fixtures", "all").selects("2026-11-04-type_boundaries"))
    }

    @Test fun originalSubcohortsRequireExplicitNamesAndStableIdsInsteadOfSelectingAllOriginals() {
        assertFails { CompilerFixtureSettings.parse(arrayOf("fixtures")) }
        for (name in CompilerFixtureSettings.ORIGINAL_ID_COHORTS.keys) {
            val settings = CompilerFixtureSettings.parse(arrayOf("--cohort=$name", "fixtures"))
            assertEquals(48, settings.maxAttempts)
            assertFailsWith<IllegalArgumentException> { settings.selects(null) }
            assertFailsWith<IllegalArgumentException> { settings.selects(null, "") }
            assertFailsWith<IllegalArgumentException> { settings.selects(null, " ") }
            assertFailsWith<IllegalArgumentException> { settings.selects(null, "bad\u0000id") }
            assertFailsWith<IllegalArgumentException> { settings.selects(null, "ktc.compiler.common.not_a_frozen_original") }
            assertFailsWith<IllegalArgumentException> { settings.selects("2026-11-04-overrides") }
        }
        for (unknown in listOf("original_calls", "original_flow_types", "original_jvm", "original_calls_d",
            "original_flow", "original_unknown", "Original_calls_a")) {
            assertFails { CompilerFixtureSettings.parse(arrayOf("fixtures", "--cohort=$unknown")) }
        }
    }

    @Test fun originalSubcohortsUseDisjointExactStableIdsAndKeepOtherInputsVisibleButUnselected() {
        val expectedNames = setOf("original_calls_a", "original_calls_b", "original_calls_c", "original_flow_errors",
            "original_flow_warnings", "original_flow_extra", "original_jvm_errors_a", "original_jvm_errors_b",
            "original_jvm_warnings", "original_experimental")
        val groups = CompilerFixtureSettings.ORIGINAL_ID_COHORTS
        assertEquals(expectedNames, groups.keys)
        assertEquals(42, groups.values.sumOf { it.size })
        assertEquals(originalRuleIds, groups.values.flatten().toSet())
        assertEquals(originalRuleIds, CompilerFixtureSettings.ORIGINAL_RULE_IDS)
        assertEquals(expectedNames + setOf("original", "overrides", "visibility", "types_conventions",
            "inline_constructors", "opt_in", "jvm", "type_boundaries", "all", "type_boundaries_supplemental", "nullability_protocols",
            "diagnostic_breadth_default", "diagnostic_breadth_advisory", "diagnostic_breadth_opt_in"), CompilerFixtureSettings.COHORTS)
        for ((name, ids) in groups) {
            val settings = CompilerFixtureSettings("fixtures", name)
            for (id in originalRuleIds) assertEquals(id in ids, settings.selects(null, id), "$name: $id")
            assertFalse(settings.selects("2026-11-04-overrides", "ktc.compiler.common.nothing_to_override"))
            assertFalse(settings.selects("2026-11-04-type_boundaries", "ktc.compiler.common.int_literal_out_of_range"))
            assertFalse(settings.selects("2026-11-04-overrides", originalRuleIds.first()))
            assertFails { settings.selects("2026-11-04-kmp_matching", "ktc.compiler.common.actual_without_expect") }
            assertEquals(ids.sorted(), settings.description()["explicit_original_rule_ids"])
            assertEquals("visible_not_run_not_excluded", settings.description()["unselected_scope"])
        }
    }

    @Test fun legacyOriginalAllAndAuthoredCohortsKeepTheirExistingPredicates() {
        val original = CompilerFixtureSettings("fixtures", "original")
        val all = CompilerFixtureSettings("fixtures", "all")
        assertTrue(original.selects(null))
        assertTrue(all.selects(null))
        for (id in originalRuleIds) {
            assertTrue(original.selects(null, id))
            assertTrue(all.selects(null, id))
        }
        val authored = mapOf("overrides" to "2026-11-04-overrides", "visibility" to "2026-11-04-visibility",
            "types_conventions" to "2026-11-04-conventions_types_nullability",
            "inline_constructors" to "2026-11-04-inline_constructors", "opt_in" to "2026-11-04-opt_in",
            "jvm" to "2026-11-04-jvm_record_external", "type_boundaries" to "2026-11-04-type_boundaries")
        for ((name, metadata) in authored) {
            val settings = CompilerFixtureSettings("fixtures", name)
            assertTrue(settings.selects(metadata))
            assertFalse(settings.selects(null))
            assertTrue(all.selects(metadata))
            assertFalse(original.selects(metadata))
        }
        assertFails { all.selects("2026-11-04-original_calls_a", originalRuleIds.first()) }
        assertFails { all.selects("unknown", originalRuleIds.first()) }
        assertEquals(emptyList<String>(), original.description()["explicit_original_rule_ids"])
        assertEquals(emptyList<String>(), all.description()["explicit_original_rule_ids"])
    }

    @Test fun eachOriginalSubcohortFitsItsFullConditionalAdmissionPlanWithoutCompilerClaims() {
        // Source-plan arithmetic only: not a launch, process, assessment or cleanup witness.
        val expected = mapOf("original_calls_a" to (17 to 47), "original_calls_b" to (17 to 47),
            "original_calls_c" to (13 to 23), "original_flow_errors" to (17 to 47),
            "original_flow_warnings" to (12 to 48), "original_flow_extra" to (12 to 18),
            "original_jvm_errors_a" to (17 to 47), "original_jvm_errors_b" to (17 to 47),
            "original_jvm_warnings" to (12 to 24), "original_experimental" to (12 to 18))
        for ((name, ids) in CompilerFixtureSettings.ORIGINAL_ID_COHORTS) {
            val warnings = ids.count { it in originalWarningIds }
            val errors = ids.size - warnings
            assertTrue(warnings == 0 || errors == 0) // Each reviewed subgroup has one declared severity.
            assertEquals(1, ids.map { it.substringBeforeLast('.') }.toSet().size) // One mapped backend.
            val flags = ids.map { id -> listOf("-jvm-target", "17") + when (id) {
                "ktc.compiler.common.unreachable_code" -> listOf("-Wextra")
                "ktc.compiler.common.empty_range" -> listOf("-Xuse-fir-experimental-checkers")
                else -> emptyList()
            } }.toSet()
            assertEquals(1, flags.size) // No hidden stable-flag split in these exact subgroups.
            val cohortCalls = 6 + if (warnings > 0) 1 else 0
            val noFallback = cohortCalls + errors + 1 + 4 // Mandatory error suppression, denial and actual controls.
            val conditionalSingles = 5 * ids.size + warnings
            assertEquals(expected.getValue(name), noFallback to (noFallback + conditionalSingles))
            assertTrue(noFallback + conditionalSingles <= CompilerFixtureSettings.MAX_ATTEMPTS)
        }
        assertEquals(setOf("ktc.compiler.common.unreachable_code"), CompilerFixtureSettings.ORIGINAL_ID_COHORTS.getValue("original_flow_extra"))
        assertEquals(setOf("ktc.compiler.common.empty_range"), CompilerFixtureSettings.ORIGINAL_ID_COHORTS.getValue("original_experimental"))
    }

    @Test fun originalSubsetDescriptionAndSmallerOwnerBoundsNeverGrantExecutionAuthority() {
        val settings = CompilerFixtureSettings.parse(arrayOf("fixtures", "--cohort=original_flow_warnings",
            "--max-attempts=2", "--overall-seconds=7", "--per-job-seconds=3", "--evidence-mib=24"))
        assertEquals(2, settings.maxAttempts)
        assertEquals(7_000L, settings.overallMillis)
        assertEquals(3_000L, settings.perJobMillis)
        assertEquals(25_165_824L, settings.evidenceBytes)
        val description = settings.description()
        assertEquals("stable_original_rule_id_subset", description["selection_kind"])
        assertEquals(CompilerFixtureSettings.ORIGINAL_ID_COHORTS.getValue("original_flow_warnings").sorted(), description["explicit_original_rule_ids"])
        assertEquals("trusted_harness_invocation_only_not_project_policy_JSON_or_execution_permission", description["authority_scope"])
        assertEquals(false, description["resume_or_merge"])
        assertEquals("visible_not_run_not_excluded", description["unselected_scope"])
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

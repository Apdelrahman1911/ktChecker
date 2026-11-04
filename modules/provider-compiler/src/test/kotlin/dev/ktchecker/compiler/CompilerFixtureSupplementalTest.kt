package dev.ktchecker.compiler

import dev.ktchecker.contracts.Digests
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

/** Source/parser/ledger/diagnostic value controls only. No compiler, provider or native helper is launched or simulated. */
class CompilerFixtureSupplementalTest {
    private val plan = CompilerFixtureSupplementalPlan

    @Test fun separateExplicitSelectorSelectsOnlyTheEighteenSupplementalDeclarations() {
        val settings = CompilerFixtureSettings.parse(arrayOf("fixtures", "--cohort=type_boundaries_supplemental"))
        assertEquals(48, settings.maxAttempts)
        assertEquals(1_200_000L, settings.overallMillis)
        assertEquals(90_000L, settings.perJobMillis)
        assertEquals(268_435_456L, settings.evidenceBytes)
        assertEquals("explicit_authored_supplemental_variants_only", settings.description()["selection_kind"])
        assertEquals(false, settings.description()["ordinary_six_kind_scheduling"])
        assertEquals(plan.RULE_IDS.sorted(), settings.description()["explicit_supplemental_rule_ids"])
        assertEquals(plan.KINDS, settings.description()["supplemental_source_kinds"])
        val rows = manifest().getValue("fixtures").jsonArray.map { it.jsonObject }
        assertEquals(98, rows.size)
        rows.forEach { row -> assertFalse(settings.selects(row["authored_cohort"]?.jsonPrimitive?.content,
            row.getValue("rule_id").jsonPrimitive.content)) }
        plan.declarations().forEach { declaration ->
            assertTrue(settings.selectsSupplemental(plan.AUTHORED_COHORT, declaration.ruleId, declaration.kind))
            assertFalse(settings.selectsSupplemental(null, declaration.ruleId, declaration.kind))
            assertFalse(settings.selectsSupplemental("2026-11-04-overrides", declaration.ruleId, declaration.kind))
            for (legacy in listOf("type_boundaries", "all", "original", "original_calls_a")) {
                assertFalse(CompilerFixtureSettings("fixtures", legacy).selectsSupplemental(
                    plan.AUTHORED_COHORT, declaration.ruleId, declaration.kind))
            }
        }
        assertFails { settings.selects("2026-11-04-kmp_matching", "ktc.compiler.common.actual_without_expect") }
        assertFails { settings.selectsSupplemental("unknown", plan.RULE_IDS.first(), "boundary") }
        assertFails { settings.selectsSupplemental(plan.AUTHORED_COHORT, "ktc.compiler.common.unresolved_reference", "boundary") }
        assertFails { settings.selectsSupplemental(plan.AUTHORED_COHORT, "", "boundary") }
        assertFails { settings.selectsSupplemental(plan.AUTHORED_COHORT, plan.RULE_IDS.first(), "configuration") }
        for (name in listOf("type_boundaries_supplement", "Type_boundaries_supplemental", "supplemental", "type_boundaries_all")) {
            assertFails { CompilerFixtureSettings.parse(arrayOf("fixtures", "--cohort=$name")) }
        }
    }

    @Test fun fixedKindMajorScheduleIsEighteenSingleFilesOneDenialAndFourActualControlCalls() {
        val declarations = plan.declarations()
        val expectedIds = listOf("INT_LITERAL_OUT_OF_RANGE", "CANNOT_CHECK_FOR_ERASED", "RECURSIVE_TYPEALIAS_EXPANSION",
            "CONFLICTING_UPPER_BOUNDS", "TYPE_VARIANCE_CONFLICT_ERROR", "UNRESOLVED_REFERENCE_WRONG_RECEIVER")
        assertEquals((1..18).toList(), declarations.map { it.number })
        assertEquals(List(6) { "boundary" } + List(6) { "applicability" } + List(6) { "suppression" }, declarations.map { it.kind })
        assertEquals(List(3) { expectedIds }.flatten(), declarations.map { it.upstreamId })
        assertEquals(18, declarations.map { it.path }.distinct().size)
        assertEquals(18, declarations.map { it.label }.distinct().size)
        assertEquals(23, plan.PLANNED_ATTEMPTS)
        assertEquals(22, plan.CHILD_CAPABLE_CALLS)
        assertEquals(19, plan.description()["version_denial_attempt"])
        assertEquals("2.4.20", plan.description()["version_denial_requested_version"])
        assertEquals(listOf("intent-preserving-warning-controls", "unmapped-syntax-control",
            "quoted-name-is-not-emission", "unnamed-werror-failure-control"), plan.CONTROL_LABELS)
        assertEquals(listOf("-jvm-target", "17"), plan.FLAGS)
        for (field in listOf("source_transforms", "cohort_batches", "retries_or_replacements", "ordinary_six_kind_jobs")) {
            assertEquals(0, plan.description()[field])
        }
        assertEquals(false, plan.description()["budget_pooling_resume_or_merge"])
        assertEquals(false, plan.description()["execution_or_project_policy_authority"])
        assertEquals("none", plan.description()["qualification"])
    }

    @Test fun loadingReadsExistingExactAuthoredPathsAndPreservesAllInitialManifestRecords() {
        val original = manifest()
        val readPaths = mutableListOf<String>()
        val inputs = plan.load(original) { relative -> readPaths += relative; fixtureText(relative) }
        assertEquals(plan.declarations().map { it.path }, readPaths)
        assertEquals(plan.declarations(), inputs.map { it.declaration })
        assertEquals(18, inputs.size)
        inputs.forEach { input ->
            assertEquals(input.declaration.sourceSha256, Digests.sha256(input.source).removePrefix("sha256:"))
            assertEquals("not_run", input.authoringRecord.getValue("execution_state").jsonPrimitive.content)
            assertEquals("unrun_supplemental_source_plan", input.authoringRecord.getValue("status").jsonPrimitive.content)
            assertFalse(input.authoringRecord.getValue("executed_by_current_pair_harness").jsonPrimitive.boolean)
            assertEquals(input.authoringRecord, input.description()["initial_manifest_authoring_record"])
        }
        assertEquals(original, manifest()) // No authoring record is updated by reading or constructing a plan.
        assertFails { plan.load(original) { fixtureText(it) + "\n" } }
        assertFails { plan.load(original) { "" } }
    }

    @Test fun changedIdsKindsPathsHashesFlagsStatusesOrExpectationsFailClosedBeforeSourceRead() {
        val changes = listOf<Pair<String, JsonElement>>(
            "kind" to JsonPrimitive("configuration"), "path" to JsonPrimitive("cases/int_literal_out_of_range/positive.kt"),
            "source_sha256" to JsonPrimitive("0".repeat(64)), "compiler_flags" to JsonArray(listOf(JsonPrimitive("-jvm-target"), JsonPrimitive("21"))),
            "status" to JsonPrimitive("executed"), "execution_state" to JsonPrimitive("passed"),
            "executed_by_current_pair_harness" to JsonPrimitive(true), "compilation_expectation" to JsonPrimitive("observed_exit0"),
            "executed_by_current_pair_harness" to JsonPrimitive("false"),
            "diagnostic_expectation" to JsonPrimitive("observed_absence"), "execution_policy" to JsonPrimitive("concatenate variants"),
        )
        changes.forEach { (key, value) ->
            val changed = changeVariant(manifest()) { JsonObject(it + (key to value)) }
            var reads = 0
            assertFails { plan.load(changed) { reads++; fixtureText(it) } }
            assertEquals(0, reads, "Changed declaration should fail before any authored source is read: $key")
        }
        assertFails { plan.load(changeRow(manifest()) { JsonObject(it + ("rule_id" to JsonPrimitive("ktc.compiler.common.unresolved_reference"))) }, ::fixtureText) }
        assertFails { plan.load(changeRow(manifest()) { JsonObject(it + ("authored_cohort" to JsonPrimitive("2026-11-04-overrides"))) }, ::fixtureText) }
        assertFails { plan.load(changeRow(manifest()) { row -> JsonObject(row + ("supplemental_inputs" to
            JsonArray(row.getValue("supplemental_inputs").jsonArray.dropLast(1)))) }, ::fixtureText) }
        assertFails { plan.load(changeRow(manifest()) { row -> JsonObject(row + ("supplemental_inputs" to
            JsonArray(row.getValue("supplemental_inputs").jsonArray + row.getValue("supplemental_inputs").jsonArray.first()))) }, ::fixtureText) }
    }

    @Test fun manifestRowOrVariantOrderCannotChangeTheReviewedSixIdKindMajorSchedule() {
        val original = manifest()
        val rows = original.getValue("fixtures").jsonArray.reversed().map { element ->
            val row = element.jsonObject
            if ("supplemental_inputs" !in row) row else JsonObject(row +
                ("supplemental_inputs" to JsonArray(row.getValue("supplemental_inputs").jsonArray.reversed())))
        }
        val reordered = JsonObject(original + ("fixtures" to JsonArray(rows)))
        val inputs = plan.load(reordered, ::fixtureText)
        assertEquals(plan.declarations(), inputs.map { it.declaration })
        assertEquals((1..18).toList(), inputs.map { it.declaration.number })
        assertEquals(original, manifest())
    }

    @Test fun proposedBoundaryAndApplicabilityAbsenceRequiresActualExit0AndBothDiagnosticLedgers() {
        for (declaration in plan.declarations().filter { it.kind != "suppression" }) {
            val value = observation(declaration)
            assertEquals(true, value.expectationMatched) // Predicate values only, never observed compiler evidence.
            assertFalse(value.failedVariant)
            assertEquals("proposed_exit0_selected_absence_observed", value.state)
            assertEquals(false, value.description()["clean_or_successful_coverage_claim"])
            assertEquals(false, value.description()["ordinary_six_kind_assessment"])
            assertEquals("none", value.description()["qualification"])
            assertEquals(false, value.copy(selectedRawDiagnosticCount = 1, rawDiagnosticCount = 1).expectationMatched)
            assertEquals(false, value.copy(selectedFindingCount = 1).expectationMatched)
            assertEquals(false, value.copy(actualExitCode = 1, providerStatus = "failed").expectationMatched)
            assertEquals(false, value.copy(actualExitCode = 2, providerStatus = "failed").expectationMatched)
            assertEquals(false, value.copy(providerStatus = "failed").expectationMatched)
            val unrelatedWarning = value.copy(rawDiagnosticCount = 1)
            assertEquals(true, unrelatedWarning.expectationMatched)
            assertEquals("proposed_exit0_selected_absence_observed_with_raw_diagnostics", unrelatedWarning.state)
            assertEquals(false, unrelatedWarning.description()["clean_or_successful_coverage_claim"])
        }
    }

    @Test fun everyNonzeroSuppressionIncludingInternalCompilerErrorValuesStaysFailedNeverPassing() {
        for (declaration in plan.declarations().filter { it.kind == "suppression" }) {
            for (exit in listOf(1, 2, 137)) {
                val value = observation(declaration).copy(actualExitCode = exit, providerStatus = "failed",
                    selectedRawDiagnosticCount = 1, rawDiagnosticCount = 5)
                assertTrue(value.observedReceiptComplete) // Retention is distinct from compiler/task success.
                assertTrue(value.failedVariant)
                assertEquals("failed_compilation", value.state)
                assertNull(value.expectationMatched)
                assertEquals("unknown_not_validated", value.description()["expectation_validation"])
                assertEquals(exit, value.description()["actual_exit_code"])
                assertEquals("failed", value.description()["provider_status"])
                assertEquals(false, value.description()["clean_or_successful_coverage_claim"])
            }
        }
    }

    @Test fun exit0SuppressionWithUnspecifiedWarningsOrNoWarningsRemainsUnknownAndUnvalidated() {
        for (declaration in plan.declarations().filter { it.kind == "suppression" }) {
            val warned = observation(declaration).copy(rawDiagnosticCount = 1, errorSuppressionDiagnosticCount = 1)
            assertTrue(warned.observedReceiptComplete)
            assertFalse(warned.failedVariant)
            assertNull(warned.expectationMatched)
            assertEquals("unvalidated_compiler_accepted_suppression_with_ERROR_SUPPRESSION_warnings", warned.state)
            assertTrue(warned.description().getValue("suppression_warning_policy").toString().contains("UNSPECIFIED"))
            assertTrue(warned.description().getValue("suppression_warning_policy").toString().contains("WILL NOT BE PRESERVED"))
            val noWarnings = observation(declaration)
            assertNull(noWarnings.expectationMatched)
            assertEquals("unvalidated_compiler_accepted_suppression_unknown_expectation", noWarnings.state)
            assertEquals("unvalidated_exit0_selected_id_observed", noWarnings.copy(selectedFindingCount = 1).state)
            assertEquals("failed_provider_result_despite_exit0", noWarnings.copy(providerStatus = "failed").state)
            assertFalse(plan.expectedVariantsValidated(listOf(warned)))
        }
        val allExitZeroValues = plan.declarations().map(::observation)
        assertEquals(12, allExitZeroValues.count { it.expectationMatched == true })
        assertEquals(6, allExitZeroValues.count { it.expectationMatched == null })
        assertFalse(plan.expectedVariantsValidated(allExitZeroValues))
    }

    @Test fun missingExecutionVersionAttributionOrExitCannotBeCountedAsAnObservedReceiptOrAbsence() {
        val value = observation(plan.declarations().first())
        val incomplete = listOf(value.copy(executionConfirmed = false), value.copy(healthySupervisedExecution = false),
            value.copy(pinnedVersionObserved = false), value.copy(receiptsAndAuthoredInputIntact = false),
            value.copy(actualExitCode = null), value.copy(providerStatus = "unavailable"),
            value.copy(providerStatus = "cancelled"), value.copy(actualExitCode = 1, providerStatus = "partial"))
        incomplete.forEach {
            assertFalse(it.observedReceiptComplete)
            assertEquals(false, it.expectationMatched)
            assertEquals("actual_receipt_incomplete_or_unobserved", it.state)
        }
        assertFails { value.copy(selectedRawDiagnosticCount = 1) }
        assertFails { value.copy(errorSuppressionDiagnosticCount = 1) }
        assertFails { value.copy(selectedFindingCount = -1) }
    }

    @Test fun receiptCompletenessRequiresAllExactOrdinalsSubjectsAndDeclarationsNotJustEighteenCounts() {
        val attempts = reservedAttemptValues()
        val values = plan.declarations().map(::observation)
        assertTrue(plan.observedReceiptsComplete(attempts, values)) // Value gate only; no actual receipts or process claimed.
        assertFalse(plan.expectedVariantsValidated(values)) // Unknown suppression still prevents a successful terminal.
        assertFalse(plan.observedReceiptsComplete(emptyList(), values))
        assertFalse(plan.observedReceiptsComplete(attempts, emptyList()))
        assertFalse(plan.observedReceiptsComplete(attempts, values.drop(1)))
        assertFalse(plan.observedReceiptsComplete(attempts, values + values.last()))
        assertFalse(plan.observedReceiptsComplete(attempts, values.reversed()))
        assertFalse(plan.observedReceiptsComplete(attempts, listOf(values.first().copy(attemptNumber = 2)) + values.drop(1)))
        assertFalse(plan.observedReceiptsComplete(attempts, listOf(values.first().copy(executionConfirmed = false)) + values.drop(1)))
        assertFalse(plan.observedReceiptsComplete(listOf(attempts.first().copy(ruleIds = emptySet())) + attempts.drop(1), values))
        assertFalse(plan.observedReceiptsComplete(listOf(attempts.first().copy(label = "ordinary-positive-cohort")) + attempts.drop(1), values))
        val failedSuppressionValues = values.map { if (it.declaration.kind == "suppression")
            it.copy(actualExitCode = 2, providerStatus = "failed", rawDiagnosticCount = 45) else it }
        assertTrue(plan.observedReceiptsComplete(attempts, failedSuppressionValues))
        assertEquals(6, failedSuppressionValues.count { it.failedVariant })
        assertFalse(plan.expectedVariantsValidated(failedSuppressionValues))
    }

    @Test fun exactAttemptArithmeticIncludesOneDenialWithoutImplyingExecutionAndSmallerBoundsRemainStops() {
        val attempts = reservedAttemptValues()
        assertEquals(23, attempts.size)
        assertTrue(plan.exactAttemptScheduleMatches(attempts)) // Pure reservations are not actual execution proof.
        assertTrue(attempts.drop(18).all { it.ruleIds.isEmpty() })
        assertFalse(plan.observedReceiptsComplete(attempts, emptyList()))
        assertFalse(plan.exactAttemptScheduleMatches(attempts.dropLast(1)))
        assertFalse(plan.exactAttemptScheduleMatches(attempts + attempts.last()))
        assertFalse(plan.exactAttemptScheduleMatches(attempts.reversed()))
        val smaller = CompilerFixtureSettings.parse(arrayOf("fixtures", "--cohort=type_boundaries_supplemental",
            "--max-attempts=1", "--overall-seconds=2", "--per-job-seconds=1", "--evidence-mib=24"))
        assertEquals(1, smaller.maxAttempts)
        assertEquals(2_000L, smaller.overallMillis)
        assertEquals(1_000L, smaller.perJobMillis)
        assertEquals(25_165_824L, smaller.evidenceBytes)
        val budget = CompilerFixtureBudget(smaller, startedNanos = 0, nanoTime = { 0 })
        budget.reserveAttempt(plan.declarations().first().label, setOf(plan.declarations().first().ruleId))
        assertEquals(CompilerFixtureStopReason.ATTEMPT_BUDGET,
            assertFailsWith<CompilerFixtureStopped> { budget.reserveAttempt("no-pooling-or-retry", emptySet()) }.reason)
        assertFalse(plan.exactAttemptScheduleMatches(budget.attempts))
    }

    @Test fun privateDestinationAndCompletionOverlayCannotWriteOrCompleteTheOrdinaryLatestTuple() {
        val ordinary = Path.of("fixtures/evidence/compiler-jvm-2.4.21.json")
        assertNull(compilerFixtureSupplementalLatestSidecar(true, ordinary))
        assertNull(compilerFixtureSupplementalLatestSidecar(true, null))
        assertEquals(ordinary, compilerFixtureSupplementalLatestSidecar(false, ordinary))
        assertNull(compilerFixtureSupplementalLatestSidecar(false, null))
        val fields = mapOf<String, Any>("run_complete" to true) + compilerFixtureSupplementalCompletionFields(true)
        assertEquals(false, fields["run_complete"])
        assertEquals(false, fields["ordinary_six_kind_assessments_complete"])
        assertEquals(false, fields["supplemental_observed_receipts_are_success"])
        assertEquals("unknown_not_validated", fields["suppression_expectations"])
        assertFalse("supplemental_complete" in fields)
        assertFalse("probe_complete" in fields)
        assertEquals(emptyMap(), compilerFixtureSupplementalCompletionFields(false))
    }

    private fun observation(declaration: CompilerFixtureSupplementalDeclaration): CompilerFixtureSupplementalObservation =
        CompilerFixtureSupplementalObservation(declaration, declaration.number, true, true, true, true, 0, "partial", 0, 0, 0, 0)

    private fun reservedAttemptValues(): List<CompilerFixtureAttempt> {
        val budget = CompilerFixtureBudget(CompilerFixtureSettings("fixtures", plan.SELECTOR), startedNanos = 0, nanoTime = { 0 })
        plan.declarations().forEach { budget.reserveAttempt(it.label, setOf(it.ruleId)).state = "unit_value_only_not_run" }
        budget.reserveAttempt(plan.VERSION_DENIAL_LABEL, emptySet()).state = "unit_value_only_not_run"
        plan.CONTROL_LABELS.forEach { budget.reserveAttempt(it, emptySet()).state = "unit_value_only_not_run" }
        return budget.attempts
    }

    private fun manifest(): JsonObject = Json.parseToJsonElement(fixtureText("manifest.json")).jsonObject
    private fun fixtureText(relative: String): String = Files.readString(fixtureRoot().resolve(relative))
    private fun fixtureRoot(): Path = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
        .map { it.resolve("providers/compiler-fixtures") }.first { Files.isRegularFile(it.resolve("manifest.json")) }

    private fun changeRow(manifest: JsonObject, change: (JsonObject) -> JsonObject): JsonObject {
        val rows = manifest.getValue("fixtures").jsonArray
        val index = rows.indexOfFirst { "supplemental_inputs" in it.jsonObject }
        return JsonObject(manifest + ("fixtures" to JsonArray(rows.mapIndexed { number, row -> if (number == index) change(row.jsonObject) else row })))
    }

    private fun changeVariant(manifest: JsonObject, change: (JsonObject) -> JsonObject): JsonObject = changeRow(manifest) { row ->
        val variants = row.getValue("supplemental_inputs").jsonArray
        JsonObject(row + ("supplemental_inputs" to JsonArray(variants.mapIndexed { index, variant -> if (index == 0) change(variant.jsonObject) else variant })))
    }
}

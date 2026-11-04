package dev.ktchecker.compiler

import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.Finding
import dev.ktchecker.contracts.RawDiagnostic
import dev.ktchecker.contracts.SourceLocation
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

/** Source/parser/ledger/oracle value controls only. No compiler, provider or native helper is launched or simulated. */
class CompilerFixtureNullabilityTest {
    private val plan = CompilerFixtureNullabilityPlan

    @Test fun literalThirtyFileKindMajorPlanHasThirtyFiveReservationsAndThirtyFourPotentialChildCalls() {
        val declarations = plan.declarations()
        val ids = listOf("UNSAFE_IMPLICIT_INVOKE_CALL", "UNSAFE_INFIX_CALL", "UNSAFE_OPERATOR_CALL",
            "UNSAFE_CALLABLE_REFERENCE", "ITERATOR_ON_NULLABLE", "UNEXPECTED_SAFE_CALL")
        assertEquals((1..30).toList(), declarations.map { it.number })
        assertEquals(List(5) { ids }.flatten(), declarations.map { it.upstreamId })
        assertEquals(plan.KINDS.flatMap { kind -> List(6) { kind } }, declarations.map { it.kind })
        assertEquals(listOf("positive", "negative", "boundary", "applicability", "suppression"), plan.KINDS)
        assertEquals(30, declarations.map { it.path }.toSet().size)
        assertEquals(30, declarations.map { it.label }.toSet().size)
        assertEquals(35, plan.PLANNED_ATTEMPTS)
        assertEquals(34, plan.CHILD_CAPABLE_CALLS)
        assertEquals(31, plan.VERSION_DENIAL_ATTEMPT)
        assertEquals("2.4.20", plan.VERSION_DENIAL_REQUESTED_VERSION)
        assertEquals(listOf("intent-preserving-warning-controls", "unmapped-syntax-control",
            "quoted-name-is-not-emission", "unnamed-werror-failure-control"), plan.CONTROL_LABELS)
        assertEquals(listOf("-jvm-target", "17", "-language-version", "2.4", "-api-version", "2.4"), plan.FLAGS)
        for (field in listOf("source_transforms", "cohort_batches", "retries_or_replacements", "ordinary_six_kind_jobs")) {
            assertEquals(0, plan.description()[field])
        }
        assertEquals(false, plan.description()["budget_pooling_resume_or_merge"])
        assertEquals(false, plan.description()["source_plan_and_oracle_are_execution_authority"])
        assertEquals("none", plan.description()["qualification"])
        declarations.forEach { declaration ->
            assertEquals("ktc.compiler.common." + declaration.upstreamId.lowercase(), declaration.ruleId)
            assertEquals("cohort-plans/nullability-protocols-cases/${declaration.upstreamId.lowercase()}/${declaration.kind}.kt", declaration.path)
            assertEquals(declaration.sourceSha256, Digests.sha256(fixtureText(declaration.path)).removePrefix("sha256:"))
            assertEquals(declaration.oracle(), declaration.description()["predeclared_per_source_oracle"])
            assertEquals(false, declaration.oracle()["ordinary_six_kind_assessment"])
        }
    }

    @Test fun exactSelectorCapsOnlyThisRouteAtThirtyFiveAndRejectsOrdinaryOrUnknownInputSelection() {
        val settings = CompilerFixtureSettings.parse(arrayOf("fixtures", "--cohort=nullability_protocols"))
        assertEquals(35, settings.maxAttempts)
        assertEquals(35, CompilerFixtureSettings("fixtures", plan.SELECTOR).maxAttempts)
        assertEquals("explicit_authored_nullability_variants_only", settings.description()["selection_kind"])
        assertEquals(false, settings.description()["ordinary_six_kind_scheduling"])
        assertEquals(plan.RULE_IDS.sorted(), settings.description()["explicit_nullability_rule_ids"])
        assertEquals(plan.KINDS, settings.description()["nullability_source_kinds"])
        ordinaryManifest().getValue("fixtures").jsonArray.forEach { element ->
            val row = element.jsonObject
            assertFalse(settings.selects(row["authored_cohort"]?.jsonPrimitive?.content, row.getValue("rule_id").jsonPrimitive.content))
        }
        plan.declarations().forEach { assertTrue(settings.selectsNullability(plan.AUTHORED_COHORT, it.ruleId, it.kind)) }
        assertFails { settings.selectsNullability("unknown", plan.RULE_IDS.first(), "positive") }
        assertFails { settings.selectsNullability(plan.AUTHORED_COHORT, "ktc.compiler.common.unsafe_call", "positive") }
        assertFails { settings.selectsNullability(plan.AUTHORED_COHORT, plan.RULE_IDS.first(), "configuration") }
        assertFails { settings.selectsNullability(plan.AUTHORED_COHORT, "", "suppression") }
        assertFails { settings.selects("2026-11-04-nullability_protocols", plan.RULE_IDS.first()) }
        assertFails { CompilerFixtureSettings("fixtures", plan.SELECTOR, maxAttempts = 36) }
        for (bound in listOf("36", "48", "0", "-1", "035", "35x")) {
            assertFails { CompilerFixtureSettings.parse(arrayOf("fixtures", "--cohort=${plan.SELECTOR}", "--max-attempts=$bound")) }
        }
        for (selector in listOf("nullability", "Nullability_protocols", "nullability_protocols_all", "nullability_protocol")) {
            assertFails { CompilerFixtureSettings.parse(arrayOf("fixtures", "--cohort=$selector")) }
        }
    }

    @Test fun allOldRoutesRetainFortyEightAndUnchangedGlobalDeadlinePerJobAndEvidenceBounds() {
        assertEquals(48, CompilerFixtureSettings.MAX_ATTEMPTS)
        assertEquals(1_200_000L, CompilerFixtureSettings.MAX_OVERALL_MILLIS)
        assertEquals(90_000L, CompilerFixtureSettings.MAX_PER_JOB_MILLIS)
        assertEquals(268_435_456L, CompilerFixtureSettings.MAX_EVIDENCE_BYTES)
        assertEquals(16_777_216L, CompilerFixtureSettings.JOB_RESERVE_BYTES)
        assertEquals(8_388_608L, CompilerFixtureSettings.TERMINAL_RESERVE_BYTES)
        for (selector in CompilerFixtureSettings.COHORTS - plan.SELECTOR) {
            val old = CompilerFixtureSettings.parse(arrayOf("fixtures", "--cohort=$selector"))
            assertEquals(48, old.maxAttempts)
            assertEquals(48, CompilerFixtureSettings("fixtures", selector, maxAttempts = 48).maxAttempts)
            assertEquals(1_200_000L, old.overallMillis)
            assertEquals(90_000L, old.perJobMillis)
            assertEquals(268_435_456L, old.evidenceBytes)
            assertFalse(old.selectsNullability(plan.AUTHORED_COHORT, plan.RULE_IDS.first(), "positive"))
            assertFalse("explicit_nullability_rule_ids" in old.description())
        }
        assertEquals(10, CompilerFixtureSettings.ORIGINAL_ID_COHORTS.size)
        assertEquals(42, CompilerFixtureSettings.ORIGINAL_RULE_IDS.size)
        assertEquals(98, ordinaryManifest().getValue("fixtures").jsonArray.size)
        assertEquals(16, Json.parseToJsonElement(fixtureText("planned-kmp-manifest.json")).jsonObject.getValue("fixtures").jsonArray.size)
        val selected = CompilerFixtureSettings("fixtures", plan.SELECTOR)
        plan.declarations().forEach { assertFalse(selected.selectsSupplemental("2026-11-04-type_boundaries", it.ruleId, "boundary")) }
        assertFails { CompilerFixtureSettings.parse(arrayOf("fixtures")) }
    }

    @Test fun exactThirtySixAdditionalCapturePathsAndSixMetadataPinsRemainReadOnlySourceV1() {
        assertEquals(plan.declarations().map { it.path } + plan.SOURCE_METADATA_PINS.keys, plan.START_CAPTURE_PATHS)
        assertEquals(36, plan.START_CAPTURE_PATHS.size)
        assertEquals(36, plan.START_CAPTURE_PATHS.toSet().size)
        assertEquals(6, plan.SOURCE_METADATA_PINS.size)
        val reads = mutableListOf<String>()
        plan.verifyCapturedMetadata { path -> reads += path; fixtureText(path) }
        assertEquals(plan.SOURCE_METADATA_PINS.keys.toList(), reads)
        plan.SOURCE_METADATA_PINS.forEach { (path, digest) ->
            assertEquals(digest, Digests.sha256(fixtureText(path)).removePrefix("sha256:"))
            assertFails { plan.verifyCapturedMetadata { current -> fixtureText(current) + if (current == path) "\n" else "" } }
            assertFails { plan.verifyCapturedMetadata { current -> if (current == path) "" else fixtureText(current) } }
        }
        assertEquals(false, manifest().getValue("run_complete").jsonPrimitive.boolean)
        assertEquals("authored_unscheduled_source_only_additive_plan", manifest().getValue("status").jsonPrimitive.content)
    }

    @Test fun loaderReadsExactlyThirtyLiteralSourceFilesOnceWithoutChangingInitialAuthoringRecords() {
        val initial = manifest()
        val reads = mutableListOf<String>()
        val loaded = plan.load(initial) { path -> reads += path; fixtureText(path) }
        assertEquals(plan.declarations().map { it.path }, reads)
        assertEquals(30, reads.distinct().size)
        assertEquals(plan.declarations(), loaded.map { it.declaration })
        loaded.forEach { input ->
            assertEquals(input.declaration.sourceSha256, Digests.sha256(input.source).removePrefix("sha256:"))
            assertEquals("not_run", input.authoringRecord.getValue("execution_state").jsonPrimitive.content)
            assertEquals("unrun_independent_next_cohort_source_plan", input.authoringRecord.getValue("status").jsonPrimitive.content)
            assertEquals(JsonNull, input.authoringRecord.getValue("expected_outcome_matched"))
            assertFalse(input.authoringRecord.getValue("executed_by_current_pair_harness").jsonPrimitive.boolean)
            assertEquals(input.authoringRecord, input.description()["initial_manifest_authoring_record"])
            assertEquals(plan.FLAGS, input.description()["compiler_flags"])
        }
        assertEquals(initial, manifest())
        assertFails { plan.load(initial) { fixtureText(it) + "\n" } }
        assertFails { plan.load(initial) { "" } }
    }

    @Test fun manifestRowAndInputOrderCanNormalizeButCannotRescheduleTheReviewedKindMajorDeclarations() {
        val initial = manifest()
        val reordered = JsonObject(initial + ("fixtures" to JsonArray(initial.getValue("fixtures").jsonArray.reversed().map { element ->
            val row = element.jsonObject
            JsonObject(row + ("inputs" to JsonArray(row.getValue("inputs").jsonArray.reversed())))
        })))
        assertEquals(plan.declarations(), plan.load(reordered, ::fixtureText).map { it.declaration })
        assertEquals(initial, manifest())
        // Raw metadata pins are separately checked by the real runner; normalized values are not new on-disk bytes.
        assertFalse(Digests.sha256(reordered.toString()).removePrefix("sha256:") == plan.SOURCE_METADATA_PINS.getValue(plan.MANIFEST_PATH))
        assertFails { plan.load(JsonObject(initial + ("ordered_kinds" to JsonArray(plan.KINDS.reversed().map { JsonPrimitive(it) }))), ::fixtureText) }
    }

    @Test fun changedRowIdsBackendsSeveritiesPathsOrMissingDuplicateKindsFailBeforeSourceReading() {
        val rowChanges = listOf<Pair<String, JsonElement>>(
            "rule_id" to JsonPrimitive("ktc.compiler.common.unsafe_call"), "upstream_id" to JsonPrimitive("UNSAFE_CALL"),
            "backend" to JsonPrimitive("jvm"), "declared_severity" to JsonPrimitive("warning"),
            "authored_cohort" to JsonPrimitive("2026-11-04-type_boundaries"), "status" to JsonPrimitive("executed"),
            "ordinary_six_kind_assessment" to JsonPrimitive(true), "flags" to JsonArray(listOf(JsonPrimitive("-Werror"))),
            "positive" to JsonPrimitive("cases/unsafe_call/positive.kt"), "negative" to JsonPrimitive("../negative.kt"))
        rowChanges.forEach { (key, value) -> failsBeforeSourceRead(changeRow(manifest()) { JsonObject(it + (key to value)) }) }
        val first = manifest().getValue("fixtures").jsonArray.first()
        failsBeforeSourceRead(JsonObject(manifest() + ("fixtures" to JsonArray(manifest().getValue("fixtures").jsonArray.dropLast(1)))))
        failsBeforeSourceRead(JsonObject(manifest() + ("fixtures" to JsonArray(manifest().getValue("fixtures").jsonArray.dropLast(1) + first))))
        failsBeforeSourceRead(changeRow(manifest()) { row -> JsonObject(row + ("inputs" to JsonArray(row.getValue("inputs").jsonArray.dropLast(1)))) })
        failsBeforeSourceRead(changeRow(manifest()) { row ->
            JsonObject(row + ("inputs" to JsonArray(row.getValue("inputs").jsonArray.dropLast(1) + row.getValue("inputs").jsonArray.first())))
        })
    }

    @Test fun changedInitialExpectationsFlagsStatusExecutionFieldsAndSourceHashesFailClosed() {
        val changes = listOf<Pair<String, JsonElement>>(
            "kind" to JsonPrimitive("configuration"), "path" to JsonPrimitive("cases/unsafe_call/positive.kt"),
            "source_sha256" to JsonPrimitive("0".repeat(64)), "compiler_flags" to JsonArray(listOf(JsonPrimitive("-Werror"))),
            "status" to JsonPrimitive("observed"), "execution_state" to JsonPrimitive("passed"),
            "expected_outcome_matched" to JsonPrimitive(true), "expected_outcome_matched" to JsonPrimitive(false),
            "qualification" to JsonPrimitive("qualified"), "executed_by_current_pair_harness" to JsonPrimitive(true),
            "executed_by_current_pair_harness" to JsonPrimitive("false"), "proposed_expectation" to JsonPrimitive("Observed: pass"),
            "compilation_expectation" to JsonPrimitive("observed_exit0"), "diagnostic_expectation" to JsonPrimitive("observed_absence"),
            "proposed_selected_id" to JsonNull, "execution_policy" to JsonPrimitive("concatenate variants and retry"))
        changes.forEach { (key, value) -> failsBeforeSourceRead(changeInput(manifest()) { JsonObject(it + (key to value)) }) }
        assertEquals("not_run", manifest().getValue("fixtures").jsonArray.first().jsonObject.getValue("inputs").jsonArray.first()
            .jsonObject.getValue("execution_state").jsonPrimitive.content)
    }

    @Test fun changedPinnedProfileScopeBookkeepingAndSourceOnlyCompletionFieldsFailClosed() {
        val changes = listOf<Pair<String, JsonElement>>(
            "schema_version" to JsonPrimitive(2), "kotlin_version" to JsonPrimitive("2.4.20"), "selector" to JsonPrimitive("all"),
            "authored_cohort" to JsonPrimitive("original"), "status" to JsonPrimitive("executed"),
            "run_complete" to JsonPrimitive(true), "qualified" to JsonPrimitive(true), "run_complete" to JsonPrimitive("false"),
            "ordinary_main_manifest_mutation" to JsonPrimitive(true), "map_catalog_or_runner_mutation" to JsonPrimitive(true),
            "new_kind_inventory_is_not_ordinary_six_kind_completion" to JsonPrimitive(false),
            "flags" to JsonArray(listOf(JsonPrimitive("-Werror"))), "execution_schedule" to JsonPrimitive("35 current observations"))
        changes.forEach { (key, value) -> failsBeforeSourceRead(JsonObject(manifest() + (key to value))) }
        for ((key, value) in listOf<Pair<String, JsonElement>>("compiler_version" to JsonPrimitive("2.4.20"),
            "language_version" to JsonPrimitive("2.5"), "api_version" to JsonPrimitive("2.3"), "backend" to JsonPrimitive("native"),
            "jvm_target" to JsonPrimitive("21"), "module_mode" to JsonPrimitive("concatenated_KMP"),
            "plugins" to JsonArray(listOf(JsonPrimitive("plugin.jar"))), "java_sources" to JsonArray(listOf(JsonPrimitive("Main.java"))),
            "additional_checker_activation_flags" to JsonArray(listOf(JsonPrimitive("-Xuse-fir-experimental-checkers"))),
            "project_settings_authority" to JsonPrimitive(true))) {
            val initial = manifest()
            failsBeforeSourceRead(JsonObject(initial + ("target_profile" to JsonObject(initial.getValue("target_profile").jsonObject + (key to value)))))
        }
        for (key in listOf("existing_main_manifest_ordinary_pairs", "existing_planned_unsupported_graphs", "existing_exact_mappings",
            "preexisting_kotlin_fixture_sources", "existing_type_boundary_authored_supplemental_files", "new_authored_files")) {
            val initial = manifest()
            failsBeforeSourceRead(JsonObject(initial + ("bookkeeping" to JsonObject(initial.getValue("bookkeeping").jsonObject + (key to JsonPrimitive(0))))))
        }
    }

    @Test fun positiveOracleRequiresHealthyNonzeroFailedTaskExactErrorInBothChannelsAndActualPointAgreement() {
        plan.declarations().filter { it.kind == "positive" }.forEach { declaration ->
            val value = observation(declaration)
            assertTrue(value.observedReceiptComplete) // Value gate only; no actual receipts or process claimed.
            assertEquals(true, value.expectationMatched)
            assertTrue(value.expectedPositiveErrorMatched)
            assertTrue(value.actualCompilerTaskFailed)
            assertTrue(value.actualProviderFailed)
            assertFalse(value.unexpectedOrUnmatchedVariant)
            assertEquals("proposed_exact_selected_error_observed_compiler_task_still_failed", value.state)
            assertEquals(false, value.description()["clean_or_successful_coverage_claim"])
            assertEquals(false, value.description()["ordinary_six_kind_assessment"])
            assertEquals(false, value.copy(actualExitCode = 0, providerStatus = "partial").expectationMatched)
            assertEquals(false, value.copy(providerStatus = "partial").expectationMatched)
            assertEquals(false, value.copy(selectedRawDiagnosticCount = 0, selectedRawErrorAtAuthoredPointCount = 0).expectationMatched)
            assertEquals(false, value.copy(selectedFindingCount = 0, selectedErrorFindingAtAuthoredPointCount = 0).expectationMatched)
            assertEquals(false, value.copy(selectedErrorRawAndMappedPointsAgree = false).expectationMatched)
            assertEquals(false, value.copy(actualExitCode = null).expectationMatched)
        }
    }

    @Test fun positiveUnnamedUnrelatedOrMislocatedErrorsCannotBeFilteredIntoAMatchButAllWarningsStayAllowed() {
        val base = observation(plan.declarations().first())
        val unrelatedRawError = base.copy(rawDiagnosticCount = 2, rawErrorDiagnosticCount = 2)
        assertEquals(1, unrelatedRawError.unexpectedRawErrorCount)
        assertEquals(false, unrelatedRawError.expectationMatched)
        assertTrue(unrelatedRawError.actualCompilerTaskFailed)
        assertTrue(unrelatedRawError.unexpectedOrUnmatchedVariant)
        val unrelatedMappedError = base.copy(findingCount = 2, errorFindingCount = 2)
        assertEquals(1, unrelatedMappedError.unexpectedMappedErrorCount)
        assertEquals(false, unrelatedMappedError.expectationMatched)
        val mislocatedRaw = base.copy(selectedRawErrorAtAuthoredPointCount = 0)
        assertEquals(1, mislocatedRaw.unexpectedRawErrorCount)
        assertEquals(false, mislocatedRaw.expectationMatched)
        val selectedWarning = base.copy(selectedRawErrorAtAuthoredPointCount = 0, rawErrorDiagnosticCount = 0)
        assertEquals(false, selectedWarning.expectationMatched)
        assertEquals(false, base.copy(selectedErrorFindingAtAuthoredPointCount = 0).expectationMatched)
        val warnings = base.copy(rawDiagnosticCount = 3, findingCount = 3)
        assertEquals(true, warnings.expectationMatched)
        assertEquals(2, warnings.description()["raw_non_error_diagnostic_count"])
        assertEquals(false, warnings.description()["clean_or_successful_coverage_claim"])
        assertFails { base.copy(rawErrorDiagnosticCount = 0) }
        assertFails { base.copy(errorFindingCount = 0) }
        assertFails { base.copy(selectedRawDiagnosticCount = 2) }
    }

    @Test fun actualLocationUsesSealedDigestOneBasedLinesZeroBasedUtf16PointsNeverPinnedAnchors() {
        val declaration = plan.declarations().first()
        val source = fixtureText(declaration.path)
        val firstLine = source.split('\n').first()
        val actualPointValue = SourceLocation(declaration.path, Digests.sha256(source), 1, 0)
        assertTrue(plan.authoredPointMatches(actualPointValue, declaration, source))
        assertTrue(plan.authoredPointMatches(actualPointValue.copy(column = firstLine.length, endColumn = firstLine.length), declaration, source))
        val wrong = listOf(actualPointValue.copy(path = "other.kt"), actualPointValue.copy(fileDigest = Digests.sha256("other")),
            actualPointValue.copy(line = 0, endLine = 0), actualPointValue.copy(column = -1, endColumn = -1),
            actualPointValue.copy(column = firstLine.length + 1, endColumn = firstLine.length + 1),
            actualPointValue.copy(endLine = 2), actualPointValue.copy(endColumn = 1), actualPointValue.copy(line = Int.MAX_VALUE))
        wrong.forEach { assertFalse(plan.authoredPointMatches(it, declaration, source)) }
        assertFalse(plan.authoredPointMatches(null, declaration, source))
        assertFalse(plan.authoredPointMatches(actualPointValue, declaration, source + "\n"))
        assertEquals(true, declaration.oracle()["initial_source_anchors_are_not_observed_diagnostic_locations"])
    }

    @Test fun fullRawInventoryIncludesNamedMappedAndResidualDiagnosticsAndMatchesPointsMessagesAndMultiplicity() {
        val declaration = plan.declarations().first()
        val source = fixtureText(declaration.path)
        val point = SourceLocation(declaration.path, Digests.sha256(source), 1, 0)
        val selected = RawDiagnostic("value-only exact error", "error", declaration.upstreamId, point)
        val unnamed = RawDiagnostic("value-only unnamed error", "error")
        val unrelated = RawDiagnostic("value-only unrelated error", "error", "UNMAPPED_VALUE_ONLY", point)
        val warning = RawDiagnostic("value-only warning", "warning", "ERROR_SUPPRESSION", point)
        val info = RawDiagnostic("value-only retained info", "info")
        val parsedValues = CompilerParseResult(listOf(ParsedCompilerDiagnostic(declaration.upstreamId, "error", selected.message, point)),
            listOf(unnamed, unrelated, warning, info), "2.4.21", true)
        val full = plan.fullActualRawDiagnostics(parsedValues)
        assertEquals(listOf(selected, unnamed, unrelated, warning, info), full)
        assertEquals(3, full.count { it.severity == "error" })
        assertTrue(plan.residualDiagnosticsAccountedFor(full, listOf(unnamed, unrelated, warning, info)))
        assertFalse(plan.residualDiagnosticsAccountedFor(full, listOf(RawDiagnostic("extra retained error", "error"))))
        assertFalse(plan.residualDiagnosticsAccountedFor(full, listOf(unnamed, unnamed)))
        val finding = findingValue(declaration, point, selected.message)
        assertTrue(plan.selectedErrorPointsAgree(declaration, source, full, listOf(finding)))
        assertFalse(plan.selectedErrorPointsAgree(declaration, source, full, listOf(finding.copy(location = point.copy(column = 1, endColumn = 1)))))
        assertFalse(plan.selectedErrorPointsAgree(declaration, source, full, listOf(finding.copy(diagnosis = "different value-only error"))))
        assertFalse(plan.selectedErrorPointsAgree(declaration, source, full, listOf(finding.copy(severity = "warning"))))
        assertFalse(plan.selectedErrorPointsAgree(declaration, source, full + selected, listOf(finding)))
        assertTrue(plan.selectedErrorPointsAgree(declaration, source, full + selected, listOf(finding, finding)))
        assertFalse(plan.selectedErrorPointsAgree(declaration, source, listOf(selected.copy(location = null)), listOf(finding)))
        // A selected-point match does not remove unnamed/unrelated errors: the full counter oracle rejects those.
        assertEquals(false, observation(declaration).copy(rawDiagnosticCount = full.size,
            rawErrorDiagnosticCount = full.count { it.severity == "error" }).expectationMatched)
    }

    @Test fun negativeBoundaryAndApplicabilityRequireExit0BothChannelAbsenceAndNoErrorWithoutCleanClaim() {
        plan.declarations().filter { it.kind in listOf("negative", "boundary", "applicability") }.forEach { declaration ->
            val value = observation(declaration)
            assertEquals(true, value.expectationMatched) // Independent values only; not actual compiler acceptance.
            assertTrue(value.observedReceiptComplete)
            assertFalse(value.actualCompilerTaskFailed)
            assertFalse(value.unexpectedOrUnmatchedVariant)
            assertEquals(false, value.copy(selectedRawDiagnosticCount = 1, rawDiagnosticCount = 1).expectationMatched)
            assertEquals(false, value.copy(selectedFindingCount = 1, findingCount = 1).expectationMatched)
            assertEquals(false, value.copy(rawDiagnosticCount = 1, rawErrorDiagnosticCount = 1).expectationMatched)
            assertEquals(false, value.copy(findingCount = 1, errorFindingCount = 1).expectationMatched)
            assertEquals(false, value.copy(actualExitCode = 1, providerStatus = "failed").expectationMatched)
            assertEquals(false, value.copy(providerStatus = "failed").expectationMatched)
            val nonErrors = value.copy(rawDiagnosticCount = 3, findingCount = 1)
            assertEquals(true, nonErrors.expectationMatched)
            assertEquals("proposed_exit0_selected_absence_observed_with_allowed_non_error_diagnostics", nonErrors.state)
            assertEquals(3, nonErrors.description()["raw_non_error_diagnostic_count"])
            assertEquals(false, nonErrors.description()["clean_or_successful_coverage_claim"])
            assertEquals("none", nonErrors.description()["qualification"])
        }
    }

    @Test fun allSixSuppressionsStayUnknownForActualFailureExit0SelectedErrorsWarningsOrNoDiagnostics() {
        val suppressions = plan.declarations().filter { it.kind == "suppression" }
        assertEquals(6, suppressions.size)
        suppressions.forEach { declaration ->
            val zero = observation(declaration)
            assertTrue(zero.observedReceiptComplete)
            assertNull(zero.expectationMatched)
            assertFalse(zero.actualCompilerTaskFailed)
            assertFalse(zero.unexpectedOrUnmatchedVariant)
            assertEquals("unknown_not_validated", zero.description()["expectation_validation"])
            val warning = zero.copy(rawDiagnosticCount = 2, errorSuppressionDiagnosticCount = 2)
            assertNull(warning.expectationMatched)
            assertEquals("unvalidated_exit0_error_suppression_warnings_retained", warning.state)
            val selectedErrors = zero.copy(selectedRawDiagnosticCount = 1, selectedRawErrorAtAuthoredPointCount = 1,
                rawDiagnosticCount = 2, rawErrorDiagnosticCount = 1, selectedFindingCount = 1,
                selectedErrorFindingAtAuthoredPointCount = 1, findingCount = 1, errorFindingCount = 1,
                errorSuppressionDiagnosticCount = 1)
            assertNull(selectedErrors.expectationMatched)
            assertEquals(1, selectedErrors.description()["raw_ERROR_diagnostic_count"])
            for (exit in listOf(1, 2, 137)) {
                val failed = selectedErrors.copy(actualExitCode = exit, providerStatus = "failed")
                assertTrue(failed.observedReceiptComplete) // Actual receipt retention is not a successful compilation predicate.
                assertTrue(failed.actualCompilerTaskFailed)
                assertTrue(failed.actualProviderFailed)
                assertNull(failed.expectationMatched)
                assertNull(failed.unexpectedRawErrorCount)
                assertNull(failed.unexpectedMappedErrorCount)
                assertEquals("failed_compilation_unknown_suppression_expectation", failed.state)
                assertEquals(false, failed.description()["clean_or_successful_coverage_claim"])
            }
            assertNull(zero.copy(providerStatus = "failed").expectationMatched)
        }
    }

    @Test fun missingExecutionSupervisionVersionInputReceiptsOrExitWithholdAttributionButRetainActualFailureFacts() {
        plan.declarations().forEach { declaration ->
            val value = observation(declaration)
            val incomplete = listOf(value.copy(executionConfirmed = false), value.copy(healthySupervisedExecution = false),
                value.copy(pinnedVersionObserved = false), value.copy(receiptsAndAuthoredInputIntact = false),
                value.copy(actualExitCode = null), value.copy(providerStatus = "unavailable"), value.copy(providerStatus = "cancelled"))
            incomplete.forEach {
                assertFalse(it.observedReceiptComplete)
                assertFalse(plan.observedReceiptsComplete(reservedAttemptValues(), listOf(it)))
                if (declaration.kind == "suppression") assertNull(it.expectationMatched) else assertEquals(false, it.expectationMatched)
                assertEquals("actual_receipt_incomplete_or_unobserved", it.state)
            }
        }
        val retainedFailure = observation(plan.declarations().first()).copy(receiptsAndAuthoredInputIntact = false)
        assertTrue(retainedFailure.actualCompilerTaskFailed)
        assertTrue(retainedFailure.actualProviderFailed)
        assertEquals(1, retainedFailure.description()["actual_exit_code"])
        assertEquals(false, retainedFailure.expectationMatched)
        assertEquals(true, retainedFailure.description()["actual_failure_facts_remain_retained_when_receipt_or_START_attribution_is_withheld"])
        assertFalse(retainedFailure.copy(executionConfirmed = false).actualCompilerTaskFailed)
    }

    @Test fun thirtyObservedReceiptValuesRequireExactDeclarationsOrdinalsSubjectsAndIntactGatesNotCountLaundering() {
        val attempts = reservedAttemptValues()
        val values = plan.declarations().map(::observation)
        assertTrue(plan.observedReceiptsComplete(attempts, values)) // Value gate only; no actual receipts or process claimed.
        assertFalse(plan.expectedVariantsValidated(values))
        assertEquals(6, values.count { it.expectationMatched == null })
        assertEquals(24, values.count { it.expectationMatched == true })
        assertFalse(plan.observedReceiptsComplete(attempts, values.dropLast(1)))
        assertFalse(plan.observedReceiptsComplete(attempts, values.reversed()))
        assertFalse(plan.observedReceiptsComplete(attempts, values.dropLast(1) + values.first()))
        assertFalse(plan.observedReceiptsComplete(attempts, values + values.last()))
        assertFalse(plan.observedReceiptsComplete(attempts, values.mapIndexed { index, value ->
            if (index == 0) value.copy(attemptNumber = 2) else value }))
        assertFalse(plan.observedReceiptsComplete(attempts, values.mapIndexed { index, value ->
            if (index == 0) value.copy(declaration = value.declaration.copy(sourceSha256 = "0".repeat(64))) else value }))
        assertFalse(plan.observedReceiptsComplete(attempts, values.mapIndexed { index, value ->
            if (index == 0) value.copy(receiptsAndAuthoredInputIntact = false) else value }))
        assertFalse(plan.observedReceiptsComplete(attempts.drop(1), values))
        assertFalse(plan.observedReceiptsComplete(attempts.mapIndexed { index, attempt ->
            if (index == 0) attempt.copy(ruleIds = emptySet()) else attempt }, values))
        assertFalse(plan.observedReceiptsComplete(attempts.mapIndexed { index, attempt ->
            if (index == 0) attempt.copy(label = "padded-value-only-label") else attempt }, values))
        assertFalse(plan.expectedVariantsValidated(emptyList()))
        assertFalse(plan.expectedVariantsValidated(values.filter { it.declaration.kind != "suppression" }))
    }

    @Test fun thirtyFiveCorrectReservationLabelsAreArithmeticOnlyAndSmallerOwnerBoundsStayIncomplete() {
        val attempts = reservedAttemptValues()
        assertEquals((1..35).toList(), attempts.map { it.number })
        assertTrue(plan.exactAttemptScheduleMatches(attempts))
        assertTrue(attempts.drop(30).all { it.ruleIds.isEmpty() })
        assertEquals("wrong-requested-compiler-version", attempts[30].label)
        assertEquals(plan.CONTROL_LABELS, attempts.drop(31).map { it.label })
        assertFalse(plan.observedReceiptsComplete(attempts, emptyList()))
        assertFalse(plan.exactAttemptScheduleMatches(attempts.dropLast(1)))
        assertFalse(plan.exactAttemptScheduleMatches(attempts + attempts.last()))
        assertFalse(plan.exactAttemptScheduleMatches(attempts.reversed()))
        assertFalse(plan.exactAttemptScheduleMatches(attempts.mapIndexed { index, value ->
            if (index == 34) value.copy(ruleIds = setOf(plan.RULE_IDS.first())) else value }))
        val smaller = CompilerFixtureSettings.parse(arrayOf("fixtures", "--cohort=${plan.SELECTOR}", "--max-attempts=1",
            "--overall-seconds=2", "--per-job-seconds=1", "--evidence-mib=24"))
        assertEquals(1, smaller.maxAttempts)
        assertEquals(2_000L, smaller.overallMillis)
        assertEquals(1_000L, smaller.perJobMillis)
        assertEquals(25_165_824L, smaller.evidenceBytes)
        val budget = CompilerFixtureBudget(smaller, startedNanos = 0, nanoTime = { 0 })
        budget.reserveAttempt(plan.declarations().first().label, setOf(plan.declarations().first().ruleId))
        assertEquals(CompilerFixtureStopReason.ATTEMPT_BUDGET,
            assertFailsWith<CompilerFixtureStopped> { budget.reserveAttempt("no-retry-or-pooled-attempt", emptySet()) }.reason)
        assertFalse(plan.exactAttemptScheduleMatches(budget.attempts))
    }

    @Test fun versionDenialRequiresActualNoProcessRefusalIntactReceiptAndExactThirtyFirstSubject() {
        val attempt = reservedAttemptValues()[30]
        val reason = listOf("compiler_version_not_pinned_2_4_21")
        assertTrue(plan.versionDenialMatches(attempt, "2.4.20", "unavailable", reason, false, true)) // Value gate, not an actual refusal.
        assertFalse(plan.versionDenialMatches(attempt, "2.4.21", "unavailable", reason, false, true))
        assertFalse(plan.versionDenialMatches(attempt, null, "unavailable", reason, false, true))
        assertFalse(plan.versionDenialMatches(attempt, "2.4.20", "failed", reason, false, true))
        assertFalse(plan.versionDenialMatches(attempt, "2.4.20", "unavailable", emptyList(), false, true))
        assertFalse(plan.versionDenialMatches(attempt, "2.4.20", "unavailable", reason, true, true))
        assertFalse(plan.versionDenialMatches(attempt, "2.4.20", "unavailable", reason, false, false))
        for (changed in listOf(attempt.copy(number = 30), attempt.copy(label = "other-version-denial"),
            attempt.copy(ruleIds = setOf(plan.RULE_IDS.first())))) {
            assertFalse(plan.versionDenialMatches(changed, "2.4.20", "unavailable", reason, false, true))
        }
    }

    @Test fun privateLatestAndCompletionOverlayCannotPromoteOrdinarySixKindSuccessQualificationOrAnotherTuple() {
        val ordinary = Path.of("fixtures/evidence/compiler-jvm-2.4.21.json")
        assertNull(compilerFixtureNullabilityLatestSidecar(true, ordinary))
        assertNull(compilerFixtureNullabilityLatestSidecar(true, null))
        assertEquals(ordinary, compilerFixtureNullabilityLatestSidecar(false, ordinary))
        assertNull(compilerFixtureNullabilityLatestSidecar(false, null))
        val fields = mapOf<String, Any>("run_complete" to true) + compilerFixtureNullabilityCompletionFields(true)
        assertEquals(false, fields["run_complete"])
        assertEquals(false, fields["ordinary_six_kind_assessments_complete"])
        assertEquals(false, fields["nullability_observed_receipts_are_success"])
        assertEquals("unknown_not_validated", fields["suppression_expectations"])
        assertFalse("nullability_complete" in fields)
        assertFalse("supplemental_complete" in fields)
        assertFalse("probe_complete" in fields)
        assertFalse("qualified" in fields)
        assertEquals(emptyMap(), compilerFixtureNullabilityCompletionFields(false))
        assertFalse(plan.expectedVariantsValidated(plan.declarations().map { observation(it).copy(actualExitCode = 0, providerStatus = "partial") }))
    }

    private fun observation(declaration: CompilerFixtureNullabilityDeclaration): CompilerFixtureNullabilityObservation {
        val positive = declaration.kind == "positive"
        return CompilerFixtureNullabilityObservation(declaration, declaration.number, true, true, true, true,
            if (positive) 1 else 0, if (positive) "failed" else "partial",
            if (positive) 1 else 0, if (positive) 1 else 0, if (positive) 1 else 0, if (positive) 1 else 0,
            if (positive) 1 else 0, if (positive) 1 else 0, true, if (positive) 1 else 0, if (positive) 1 else 0, 0)
    }

    private fun findingValue(declaration: CompilerFixtureNullabilityDeclaration, point: SourceLocation, message: String): Finding =
        Finding(declaration.ruleId, "value-only exact error", message, "value-only gate, never an emission witness", point,
            "unit_value_only_not_evidence", "value-controls", severity = "error", upstreamId = declaration.upstreamId)

    private fun reservedAttemptValues(): List<CompilerFixtureAttempt> {
        val budget = CompilerFixtureBudget(CompilerFixtureSettings("fixtures", plan.SELECTOR), startedNanos = 0, nanoTime = { 0 })
        plan.declarations().forEach { budget.reserveAttempt(it.label, setOf(it.ruleId)).state = "unit_value_only_not_run" }
        budget.reserveAttempt(plan.VERSION_DENIAL_LABEL, emptySet()).state = "unit_value_only_not_run"
        plan.CONTROL_LABELS.forEach { budget.reserveAttempt(it, emptySet()).state = "unit_value_only_not_run" }
        return budget.attempts
    }

    private fun manifest(): JsonObject = Json.parseToJsonElement(fixtureText(plan.MANIFEST_PATH)).jsonObject
    private fun ordinaryManifest(): JsonObject = Json.parseToJsonElement(fixtureText("manifest.json")).jsonObject
    private fun fixtureText(relative: String): String = Files.readString(fixtureRoot().resolve(relative))
    private fun fixtureRoot(): Path = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
        .map { it.resolve("providers/compiler-fixtures") }.first { Files.isRegularFile(it.resolve(plan.MANIFEST_PATH)) }

    private fun failsBeforeSourceRead(changed: JsonObject) {
        var reads = 0
        assertFails { plan.load(changed) { reads++; fixtureText(it) } }
        assertEquals(0, reads)
    }

    private fun changeRow(initial: JsonObject, change: (JsonObject) -> JsonObject): JsonObject =
        JsonObject(initial + ("fixtures" to JsonArray(initial.getValue("fixtures").jsonArray.mapIndexed { index, row ->
            if (index == 0) change(row.jsonObject) else row
        })))

    private fun changeInput(initial: JsonObject, change: (JsonObject) -> JsonObject): JsonObject = changeRow(initial) { row ->
        JsonObject(row + ("inputs" to JsonArray(row.getValue("inputs").jsonArray.mapIndexed { index, input ->
            if (index == 0) change(input.jsonObject) else input
        })))
    }
}

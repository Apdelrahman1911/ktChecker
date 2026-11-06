package dev.ktchecker.compiler

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.ProcessOutcome
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

/** Synthetic/source contracts only. None of these tests starts a provider/compiler/native process. */
class CompilerCurrent445FixtureContractTest {
    private val contract = CompilerCurrent445FixtureContract
    private fun repository(): Path = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
        .take(8).first { Files.isRegularFile(it.resolve(contract.PLAN_PATH)) }
    private fun cases() = contract.cases(Files.readAllBytes(repository().resolve(contract.PLAN_PATH)))
    private fun args(root: Path) = arrayOf("--cohort=" + contract.SELECTOR, "--consent=" + contract.CONSENT,
        "--binding-sha256=" + "0".repeat(64), root.toString())
    private fun binding(): ByteArray = Files.readAllBytes(repository().resolve(contract.BINDING_PATH))

    @Test fun currentMappingIsExactAndLegacyIsNotASelectableFallback() {
        val current = CompilerRuleRegistry.load()
        contract.requireMapping(current.mappingIdentity)
        assertFailsWith<IllegalStateException> { contract.requireMapping(CompilerFixtureMappingContract.LEGACY443) }
        assertFailsWith<IllegalStateException> { contract.requireMapping(null) }
        assertFailsWith<IllegalStateException> {
            contract.requireMapping(current.mappingIdentity.copy(resourceDigest = Digests.sha256("changed exact bytes")))
        }
        assertFailsWith<IllegalStateException> { CompilerFixtureMappingContract.requireLegacy(current.mappingIdentity) }
        assertEquals(445, current.mappings.size)
        assertEquals(402, current.jvmRuleIds.size)
        assertEquals(43, current.mappings.count { !it.jvmApplicable })
        assertTrue(contract.RULE_IDS.all { current.byRuleId.getValue(it).backend == "common" })
        assertFalse("INAPPLICABLE_OPERATOR_MODIFIER_WARNING" in current.byUpstreamId)
    }

    @Test fun everyOldSelectorAndUnboundedOrDuplicateOptionIsDenied() {
        val root = Path.of("/never-created-current445-unit")
        val valid = args(root)
        for (old in CompilerFixtureSettings.COHORTS) {
            assertFails { CompilerCurrent445Settings.parse(valid.map {
                if (it.startsWith("--cohort=")) "--cohort=" + old else it
            }.toTypedArray()) }
        }
        for (extra in listOf("--max-attempts=10", "--per-job-seconds=91", "--overall-seconds=1201",
            "--evidence-mib=257", "--flags=-Werror", "--consent=" + contract.CONSENT)) {
            assertFails { CompilerCurrent445Settings.parse(valid + extra) }
        }
        assertFails { CompilerCurrent445Settings.parse(valid.filterNot { it.startsWith("--consent=") }.toTypedArray()) }
        assertFails { CompilerCurrent445Settings.parse(valid.filterNot { it.startsWith("--binding-sha256=") }.toTypedArray()) }
        assertEquals(90_000L, CompilerCurrent445Settings.parse(valid).perJobMillis)
    }

    @Test fun absentOwnerConsentOrBindingCannotAllocateRunOrScratch() {
        val root = Files.createTempDirectory("current445-refusal-unit-")
        try {
            assertFails { CompilerCurrent445FixtureRunner.run(args(root).filterNot { it.startsWith("--consent=") }.toTypedArray()) }
            assertFails { CompilerCurrent445FixtureRunner.run(args(root)) } // Binding absent, before Current445Run allocation.
            assertFalse(Files.exists(root.resolve("build")))
            assertEquals(0L, Files.list(root).use { it.count() })
        } finally { Files.delete(root) }
    }

    @Test fun boundSourceCatalogAndCaseRosterAreClosedAndWhitespaceIsIdentity() {
        val bytes = binding()
        val pins = contract.binding(bytes, Digests.sha256(bytes).removePrefix("sha256:"))
        assertEquals(contract.CAPTURE_PATHS, pins.map { it.path }.toSet())
        assertFails { contract.binding(bytes + byteArrayOf(32), Digests.sha256(bytes).removePrefix("sha256:")) }
        val original = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val changes = listOf(
            JsonObject(original + ("ordered_case_ids" to JsonArray(emptyList()))),
            JsonObject(original + ("selected_rule_ids" to JsonArray(listOf(JsonPrimitive("ktc.compiler.native.any"))))),
            JsonObject(original + ("mapping_contract" to JsonPrimitive(CompilerFixtureMappingContract.LEGACY443.contractId))),
            JsonObject(original + ("pins" to JsonArray(original.getValue("pins").jsonArray.dropLast(1)))),
        )
        for (changed in changes) {
            val encoded = changed.toString().toByteArray()
            assertFails { contract.binding(encoded, Digests.sha256(encoded).removePrefix("sha256:")) }
        }
    }

    @Test fun fixedPinsCannotBeReplacedByOwnerHashOrPathTraversal() {
        val root = Json.parseToJsonElement(binding().toString(Charsets.UTF_8)).jsonObject
        val pins = root.getValue("pins").jsonArray
        for (field in listOf("sha256", "path")) {
            val first = pins.first().jsonObject
            val changedPin = JsonObject(first + (field to JsonPrimitive(if (field == "path") "../unreviewed" else "0".repeat(64))))
            val changed = JsonObject(root + ("pins" to JsonArray(listOf(changedPin) + pins.drop(1)))).toString().toByteArray()
            assertFails { contract.binding(changed, Digests.sha256(changed).removePrefix("sha256:")) }
        }
    }

    @Test fun unchangedSourceV1RetainsNineCasesEightSourcesAndOneUnknownOracle() {
        val all = cases()
        assertEquals(contract.CASE_IDS, all.map { it.id })
        assertEquals(8, all.map { it.path }.toSet().size)
        assertEquals(1, all.count { it.observationOnly })
        assertEquals("unknown", all.last().exitExpectation)
        assertNull(all.last().expectations.last().count)
        assertEquals(all.first().sourceSha256, all[4].sourceSha256)
        assertEquals(all.first().path, all[4].path)
        assertEquals("-Xwarning-level=ERROR_SUPPRESSION:disabled", all[4].flags.last())
        all.forEach { c -> contract.requireSource(c, Files.readAllBytes(repository().resolve(c.path))) }
        assertFails { contract.cases(Files.readAllBytes(repository().resolve(contract.PLAN_PATH)) + byteArrayOf(32)) }
    }

    private data class Synthetic(
        val c: CompilerCurrent445Case, val parsed: CompilerParseResult, val result: ProviderResult, val process: ProcessOutcome,
    )
    private fun synthetic(index: Int, unknownOperatorCount: Int = 0, unknownExit: Int = 0): Synthetic {
        val c = cases()[index]
        val registry = CompilerRuleRegistry.load()
        val diagnostics = c.expectations.flatMap { expected ->
            List(expected.count ?: unknownOperatorCount) {
                val region = expected.regions.first()
                ParsedCompilerDiagnostic(expected.upstreamId, expected.severity, "synthetic unit only, not actual emission",
                    SourceLocation(c.path, "sha256:" + c.sourceSha256, region.line, region.start))
            }
        }
        val exit = if (c.observationOnly) unknownExit else if (c.exitExpectation == "nonzero") 1 else 0
        val findings = diagnostics.map { d ->
            Finding(registry.byUpstreamId.getValue(d.upstreamId).ruleId, "unit", d.message, "synthetic",
                checkNotNull(d.location), Digests.sha256(d.message), CompilerRuleRegistry.PROVIDER_ID,
                severity = d.severity, upstreamId = d.upstreamId,
                kind = registry.byUpstreamId.getValue(d.upstreamId).kind,
                certainty = registry.byUpstreamId.getValue(d.upstreamId).certainty)
        }
        return Synthetic(c, CompilerParseResult(diagnostics, emptyList(), "2.4.21", false),
            ProviderResult(CompilerRuleRegistry.PROVIDER_ID, if (exit == 0) "partial" else "failed", findings,
                contract.RULE_IDS.map { Coverage(it, state = if (exit == 0) "unknown" else "failed", provenance = "executed") },
                providerVersion = "2.4.21"),
            ProcessOutcome("executed", exitCode = exit, executionConfirmed = true))
    }
    private fun assess(s: Synthetic) = CompilerCurrent445FixtureOracle.assess(s.c, s.parsed, s.result, s.process, CompilerRuleRegistry.load())

    @Test fun eightKnownSourceOraclesCanMatchWithoutAnyCoveragePromotion() {
        for (index in 0..7) {
            val s = synthetic(index)
            assertEquals(true, assess(s).expectedOutcomeMatched, s.c.id)
            assertFalse(s.result.complete())
            assertTrue(s.result.coverage.none { it.tested || it.qualified || it.successful() })
        }
    }

    @Test fun expectedOperatorErrorStaysNonzeroFailedProviderNotSuccessfulBuild() {
        val s = synthetic(5)
        assertEquals(1, s.process.exitCode)
        assertEquals("failed", s.result.status)
        assertTrue(s.process.successfulExecution) // Transport trust, expressly not a successful compiler build.
        assertEquals(true, assess(s).expectedOutcomeMatched)
        assertEquals(false, assess(s.copy(result = s.result.copy(status = "partial"))).expectedOutcomeMatched)
        assertEquals(false, assess(s.copy(process = s.process.copy(exitCode = 0))).expectedOutcomeMatched)
    }

    @Test fun unsupportedHardErrorSuppressionNeverBecomesAnExpectedOutcomePass() {
        for ((count, exit) in listOf(0 to 0, 1 to 1, 1 to 0)) {
            val assessment = assess(synthetic(8, count, exit))
            assertTrue(assessment.knownAssertionsMatched)
            assertNull(assessment.expectedOutcomeMatched)
            assertNull(assessment.selectedCounts.last()["known_count_matched"])
        }
        val s = synthetic(8)
        val absentWarning = s.copy(parsed = s.parsed.copy(diagnostics = emptyList()), result = s.result.copy(findings = emptyList()))
        assertFalse(assess(absentWarning).knownAssertionsMatched)
        assertNull(assess(absentWarning).expectedOutcomeMatched)
    }

    @Test fun countEqualityRequiresExactFullParserAndNormalizedPointMessageMultisets() {
        val s = synthetic(0)
        val duplicate = s.copy(parsed = s.parsed.copy(diagnostics = s.parsed.diagnostics + s.parsed.diagnostics))
        assertEquals(false, assess(duplicate).expectedOutcomeMatched)
        val wrongMessage = s.copy(result = s.result.copy(findings = s.result.findings.map { it.copy(diagnosis = "different") }))
        assertEquals(false, assess(wrongMessage).expectedOutcomeMatched)
        val wrongId = s.copy(result = s.result.copy(findings = s.result.findings.map { it.copy(upstreamId = "OTHER_DIAGNOSTIC") }))
        assertEquals(false, assess(wrongId).expectedOutcomeMatched)
    }

    @Test fun tokenRegionsCannotReplaceActualPointsAndForeignSourceCannotPass() {
        val s = synthetic(0)
        for (location in listOf(
            s.result.findings.single().location.copy(endColumn = 42),
            s.result.findings.single().location.copy(path = "foreign.kt"),
            s.result.findings.single().location.copy(fileDigest = Digests.sha256("foreign")),
            s.result.findings.single().location.copy(column = 42, endColumn = 42),
        )) {
            val changed = s.copy(parsed = s.parsed.copy(diagnostics = s.parsed.diagnostics.map { it.copy(location = location) }),
                result = s.result.copy(findings = s.result.findings.map { it.copy(location = location) }))
            assertEquals(false, assess(changed).expectedOutcomeMatched)
        }
    }

    @Test fun wrongSeverityAndQualifiedOrCompleteResultsCannotBeCredited() {
        val s = synthetic(0)
        val severity = s.copy(parsed = s.parsed.copy(diagnostics = s.parsed.diagnostics.map { it.copy(severity = "error") }),
            result = s.result.copy(findings = s.result.findings.map { it.copy(severity = "error") }))
        assertEquals(false, assess(severity).expectedOutcomeMatched)
        assertEquals(false, assess(s.copy(result = s.result.copy(findings = s.result.findings.map {
            it.copy(kind = "correctness", certainty = "high")
        }))).expectedOutcomeMatched) // ERROR_SUPPRESSION remains intent-dependent/advisory.
        assertEquals(false, assess(s.copy(result = s.result.copy(status = "complete"))).expectedOutcomeMatched)
        assertEquals(false, assess(s.copy(result = s.result.copy(coverage = s.result.coverage.map { it.copy(qualified = true) }))).expectedOutcomeMatched)
        assertEquals(false, assess(s.copy(result = s.result.copy(coverage = s.result.coverage.map { it.copy(state = "passed") }))).expectedOutcomeMatched)
    }

    @Test fun mappedUnselectedErrorsStayInResidualWithExactReasonAndInvalidateKnownCase() {
        val s = synthetic(0)
        val extra = ParsedCompilerDiagnostic("UNRESOLVED_REFERENCE", "error", "unit unexpected error", s.parsed.diagnostics.single().location)
        val raw = RawDiagnostic(extra.message, extra.severity, extra.upstreamId, extra.location)
        val outside = s.copy(parsed = s.parsed.copy(diagnostics = s.parsed.diagnostics + extra),
            result = s.result.copy(rawDiagnostics = listOf(raw),
                reasons = listOf("mapped_diagnostic_outside_selected_rules:ktc.compiler.common.unresolved_reference")))
        val assessment = assess(outside)
        assertEquals(false, assessment.expectedOutcomeMatched)
        assertTrue("unexpected_diagnostic:UNRESOLVED_REFERENCE" in assessment.problems)
        assertFalse("full_parser_provider_residual_mismatch" in assessment.problems)
        assertTrue("full_parser_provider_residual_mismatch" in assess(outside.copy(result = outside.result.copy(rawDiagnostics = emptyList()))).problems)
    }

    @Test fun warningSiblingCannotBeAnAliasOrDisappearFromRawInventory() {
        val s = synthetic(0)
        val extra = s.parsed.diagnostics.single().copy(upstreamId = "INAPPLICABLE_OPERATOR_MODIFIER_WARNING")
        val changed = s.copy(parsed = s.parsed.copy(diagnostics = s.parsed.diagnostics + extra),
            result = s.result.copy(rawDiagnostics = listOf(RawDiagnostic(extra.message, extra.severity, extra.upstreamId, extra.location)),
                reasons = listOf("unmapped_compiler_diagnostic:INAPPLICABLE_OPERATOR_MODIFIER_WARNING")))
        assertEquals(false, assess(changed).expectedOutcomeMatched)
        assertTrue(assess(changed).problems.any { it.startsWith("unexpected_diagnostic:") })
        assertFalse(assess(changed).problems.any { it == "full_parser_provider_residual_mismatch" })
    }

    @Test fun invalidUtf8TruncationTimeoutCancellationAndWrongVersionAreNeverMatches() {
        val s = synthetic(0)
        for (process in listOf(
            s.process.copy(status = "failed", reasons = listOf("process_output_invalid_utf8")),
            s.process.copy(truncated = true), s.process.copy(timedOut = true), s.process.copy(cancelled = true),
            s.process.copy(executionConfirmed = false), s.process.copy(exitCode = null),
        )) assertEquals(false, assess(s.copy(process = process)).expectedOutcomeMatched)
        assertEquals(false, assess(s.copy(parsed = s.parsed.copy(reportedVersion = "2.4.20"))).expectedOutcomeMatched)
        val retention = CompilerCurrent445FixtureOracle.streamRetention(s.process.copy(reasons = listOf("process_output_invalid_utf8")))
        assertEquals(false, retention["source_plan_raw_byte_retention_capability_resolved"])
        assertEquals(false, retention["valid_utf8_round_trip_only"])
        assertEquals(false, retention["invalid_utf8_raw_bytes_available"])
    }

    @Test fun malformedOrQuotedDiagnosticTextIsNotAnEmission() {
        val s = synthetic(0)
        val parsed = CompilerDiagnosticParser().parse("quoted [ERROR_SUPPRESSION] text, not a header", emptyMap())
        assertTrue(parsed.diagnostics.isEmpty())
        assertTrue(parsed.malformed)
        assertEquals(false, assess(s.copy(parsed = parsed)).expectedOutcomeMatched)
    }

    @Test fun scheduleBudgetRefusesSkipReorderRetryTenthCallAndStickyStop() {
        var now = 0L
        val budget = CompilerCurrent445Budget(CompilerCurrent445Settings("/unit", "0".repeat(64), 1_000, 900),
            0, { now })
        assertFails { budget.reserve(contract.CASE_IDS[1]) }
        contract.CASE_IDS.forEach(budget::reserve)
        assertEquals(9, budget.reserved)
        assertFails { budget.reserve(contract.CASE_IDS.first()) }
        now = 1_000_000_000
        assertFails { budget.requireActive() }
        now = 1 // A recorded stop is not revoked by a fake test clock moving backwards.
        assertFails { budget.requireActive() }
    }

    @Test fun ownerStopAndUnsafeControlAreStickyAndNeverGrantAnotherAttempt() {
        for (unsafe in listOf(false, true)) {
            val budget = CompilerCurrent445Budget(CompilerCurrent445Settings("/unit", "0".repeat(64)),
                ownerStop = { if (unsafe) error("unsafe") else true })
            assertFails { budget.reserve(contract.CASE_IDS.first()) }
            assertEquals(0, budget.reserved)
            assertFails { budget.requireActive(false) }
        }
    }

    @Test fun lateTerminalDeadlineCorrectsCandidateAndCannotBeRetried() {
        var checks = 0
        val events = mutableListOf<String>()
        val publication = CompilerCurrent445Publication { if (++checks == 2) error("late deadline") }
        assertFails { publication.publish(true, { events += "candidate" }, { events += "incomplete" }) }
        assertEquals(listOf("candidate", "incomplete"), events)
        assertFails { publication.publish(true, {}, {}) }
    }

    @Test fun terminalWriteFailureRemainsPrimaryEvenWhenCorrectionAlsoFails() {
        val first = IllegalStateException("candidate write")
        val correction = IllegalStateException("correction write")
        val thrown = assertFailsWith<IllegalStateException> {
            CompilerCurrent445Publication {}.publish(true, { throw first }, { throw correction })
        }
        assertSame(first, thrown)
        assertTrue(thrown.suppressed.any { it === correction })
        var incomplete = false
        assertFalse(CompilerCurrent445Publication { error("ineligible must not checkpoint for success") }
            .publish(false, { error("ineligible must not publish success") }, { incomplete = true }))
        assertTrue(incomplete)
    }
}

package dev.ktchecker.compiler

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.ProcessOutcome
import java.nio.file.Files
import kotlin.test.*

/** All emissions below are synthetic UNIT inputs to the oracle, never fixture execution evidence. */
class CompilerOrdinary445FixtureOracleTest {
    private val registry by lazy { CompilerRuleRegistry.load() }
    private val contract = CompilerOrdinary445FixtureContract
    private val scope = RuleContext("jvm", "synthetic", ":ordinary445-unit", "main")
    private fun fixture(warning: Boolean = false): CompilerOrdinary445Fixture {
        val mapping = registry.byUpstreamId.getValue(if (warning) "UNNECESSARY_SAFE_CALL" else "UNRESOLVED_REFERENCE")
        fun pin(kind: String, text: String) = CompilerOrdinary445Pin("providers/compiler-fixtures/cases/unit/$kind.kt",
            Digests.sha256(text).removePrefix("sha256:"), text.toByteArray().size)
        val positive = "package ordinary445unit\nfun positive() = unresolvedUnitName\n"
        val negative = "package ordinary445unit\nfun positive() = 1\n"
        return CompilerOrdinary445Fixture(mapping.ruleId, mapping.upstreamId, if (warning) "warning" else "error",
            listOf("-jvm-target", "17"), pin("positive", positive), pin("negative", negative), "synthetic_unit_not_execution")
    }
    private fun schedule(warning: Boolean = false): List<CompilerOrdinary445Job> {
        val f = fixture(warning)
        return contract.schedule(CompilerOrdinary445Plan(listOf(f)), contract.SELECTORS.first()) { path ->
            (if (path == f.positive.path) "package ordinary445unit\nfun positive() = unresolvedUnitName\n"
            else "package ordinary445unit\nfun positive() = 1\n").toByteArray()
        }
    }
    private fun jobFor(control: String = "positive", warning: Boolean = false) = schedule(warning).single { it.control == control }
    private data class Synthetic(val job: CompilerOrdinary445Job, val parsed: CompilerParseResult,
        val result: ProviderResult, val process: ProcessOutcome)
    private fun synthetic(job: CompilerOrdinary445Job = jobFor(), count: Int = if (job.control in setOf("negative", "warning_disabled")) 0 else 1,
        exit: Int = if (job.control in setOf("negative", "warning_disabled") ||
            job.fixture?.severity == "warning" && job.control != "configuration") 0 else 1): Synthetic {
        val f = checkNotNull(job.fixture)
        val path = job.unsuppressedPath ?: job.primaryPath
        val text = job.sources.getValue(path)
        val line = text.lines().indexOfFirst { it.startsWith("fun ") } + 1
        val severity = if (job.control == "configuration" && f.severity == "warning") "error" else f.severity
        val location = SourceLocation(path, Digests.sha256(text), line, 17)
        val diagnostics = List(count) { ParsedCompilerDiagnostic(f.upstreamId, severity, "synthetic unit only", location) }
        val findings = diagnostics.map { Finding(f.ruleId, "unit", it.message, "synthetic", checkNotNull(it.location),
            Digests.sha256("synthetic fingerprint"), CompilerRuleRegistry.PROVIDER_ID, scope, it.severity,
            registry.byRuleId.getValue(f.ruleId).kind, registry.byRuleId.getValue(f.ruleId).certainty, upstreamId = f.upstreamId) }
        val reason = when (job.control) {
            "suppression" -> "compiler_source_suppression_requires_scoped_coverage_evidence"
            "warning_disabled" -> "compiler_warning_disabled_under_selected_configuration"
            else -> "resolved_compilation_context_incomplete"
        }
        return Synthetic(job, CompilerParseResult(diagnostics, emptyList(), "2.4.21", false),
            ProviderResult(CompilerRuleRegistry.PROVIDER_ID, if (exit == 0) "partial" else "failed", findings,
                listOf(Coverage(f.ruleId, scope, if (exit == 0) "unknown" else "failed", reason,
                    provenance = "executed", implemented = findings.isNotEmpty())), listOf(reason), providerVersion = "2.4.21"),
            ProcessOutcome("executed", exitCode = exit, executionConfirmed = true))
    }
    private fun assess(s: Synthetic) = CompilerOrdinary445FixtureOracle.assess(s.job, s.parsed, s.result, s.process, registry)
    private fun withDiagnostic(s: Synthetic, upstream: String, severity: String, message: String): Synthetic {
        val point = SourceLocation(s.job.primaryPath, Digests.sha256(s.job.sources.getValue(s.job.primaryPath)), 2, 0)
        val extra = ParsedCompilerDiagnostic(upstream, severity, message, point)
        val mapping = registry.byUpstreamId[upstream]
        val reason = if (mapping == null) "unmapped_compiler_diagnostic:$upstream" else "mapped_diagnostic_outside_selected_rules:${mapping.ruleId}"
        return s.copy(parsed = s.parsed.copy(diagnostics = s.parsed.diagnostics + extra),
            result = s.result.copy(rawDiagnostics = s.result.rawDiagnostics + RawDiagnostic(message, severity, upstream, point),
                reasons = s.result.reasons + reason, coverage = s.result.coverage.map { it.copy(state = "failed") }))
    }
    @Test fun actualErrorExpectationNeverBecomesSuccessfulCompilationOrQualifiedCoverage() {
        val s = synthetic()
        val assessment = assess(s)
        assertEquals(true, assessment.diagnosticExpectedOutcomeMatched)
        assertTrue(assessment.receiptTrusted)
        assertEquals(1, s.process.exitCode)
        assertEquals("failed", s.result.status)
        assertFalse(s.result.complete())
        assertTrue(s.result.coverage.none { it.tested || it.qualified || it.successful() })
        assertFalse(assess(s.copy(result = s.result.copy(status = "partial"))).knownAssertionsMatched)
        assertFalse(assess(s.copy(process = s.process.copy(exitCode = 0))).knownAssertionsMatched)
    }
    @Test fun allKnownControlTypesCanBeCheckedWithoutConflatingSafetyWithUnknownDiagnostics() {
        for (warning in listOf(false, true)) for (j in schedule(warning).filterNot { it.refusal }) {
            val a = assess(synthetic(j))
            assertTrue(a.knownAssertionsMatched, j.id + ": " + a.problems)
            if (j.hardErrorSuppression) {
                assertNull(a.diagnosticExpectedOutcomeMatched)
                assertEquals(true, a.safetyControlMatched)
            } else assertEquals(true, a.diagnosticExpectedOutcomeMatched)
        }
    }
    @Test fun unknownHardErrorSuppressionCannotBeCountedAsDiagnosticPassEvenWhenCompilerAcceptsIt() {
        val j = jobFor("suppression")
        for ((count, exit) in listOf(0 to 0, 1 to 1, 0 to 1)) {
            val s = synthetic(j, count, exit)
            val a = assess(s)
            assertNull(a.diagnosticExpectedOutcomeMatched)
            assertEquals(true, a.safetyControlMatched)
            assertTrue(a.knownAssertionsMatched)
            if (exit != 0) assertEquals("failed", s.result.status)
        }
        val failed = synthetic(j)
        // Treating an accepted finding as nonblocking cannot alter the actual compiler failure.
        val falselyAcceptedBuild = failed.copy(result = failed.result.copy(status = "partial", findings = emptyList()))
        assertFalse(assess(falselyAcceptedBuild).knownAssertionsMatched)
        assertNull(assess(falselyAcceptedBuild).diagnosticExpectedOutcomeMatched)
        val absentSafety = failed.copy(result = failed.result.copy(reasons = emptyList()))
        assertEquals(false, assess(absentSafety).safetyControlMatched)
    }
    @Test fun negativeRequiresRealZeroExitNoSelectedEmissionAndNoResidualAtAnySeverity() {
        val s = synthetic(jobFor("negative"))
        assertEquals(true, assess(s).diagnosticExpectedOutcomeMatched)
        assertFalse(assess(s.copy(process = s.process.copy(executionConfirmed = false))).knownAssertionsMatched)
        assertFalse(assess(s.copy(process = s.process.copy(exitCode = 1), result = s.result.copy(status = "failed",
            coverage = s.result.coverage.map { it.copy(state = "failed") }))).knownAssertionsMatched)
        for (severity in listOf("info", "warning", "error")) {
            val extra = withDiagnostic(s, "UNRESOLVED_IMPORT", severity, "retained companion")
            assertFalse(assess(extra).knownAssertionsMatched)
            assertFalse(assess(extra.copy(result = extra.result.copy(rawDiagnostics = emptyList()))).knownAssertionsMatched)
        }
        assertFalse(assess(synthetic(jobFor("negative"), count = 1, exit = 0)).knownAssertionsMatched)
    }
    @Test fun positiveCompanionsAreRetainedWithExactResidualReasonsNotAliasesOrDroppedCounts() {
        val s = withDiagnostic(synthetic(), "UNRESOLVED_IMPORT", "error", "retained distinct companion")
        assertTrue(assess(s).knownAssertionsMatched)
        assertEquals(1, assess(s).residualCount)
        assertFalse(assess(s.copy(result = s.result.copy(rawDiagnostics = emptyList()))).knownAssertionsMatched)
        assertFalse(assess(s.copy(result = s.result.copy(reasons = emptyList()))).knownAssertionsMatched)
        val duplicate = s.copy(parsed = s.parsed.copy(diagnostics = s.parsed.diagnostics + s.parsed.diagnostics.last()))
        assertFalse(assess(duplicate).knownAssertionsMatched)
        val alias = withDiagnostic(synthetic(), "INAPPLICABLE_OPERATOR_MODIFIER_WARNING", "warning", "not an alias")
        assertEquals(1, assess(alias).selectedCount)
        assertEquals(1, assess(alias).residualCount)
        assertTrue(assess(alias).knownAssertionsMatched) // Real UNRESOLVED_REFERENCE is still selected, alias remains raw only.
    }
    @Test fun exactOccurrencePointMessageAccountingDoesNotEraseDuplicatesOrInventLocations() {
        val s = synthetic(count = 2)
        assertTrue(assess(s).knownAssertionsMatched)
        assertFalse(assess(s.copy(parsed = s.parsed.copy(diagnostics = s.parsed.diagnostics.dropLast(1)))).knownAssertionsMatched)
        assertFalse(assess(s.copy(result = s.result.copy(findings = s.result.findings.dropLast(1)))).knownAssertionsMatched)
        assertFalse(assess(s.copy(result = s.result.copy(findings = s.result.findings.map { it.copy(diagnosis = "different message") }))).knownAssertionsMatched)
        assertFalse(assess(s.copy(result = s.result.copy(findings = s.result.findings.map { it.copy(upstreamId = "UNRESOLVED_IMPORT") }))).knownAssertionsMatched)
        val point = s.result.findings.first().location
        for (bad in listOf(point.copy(path = "foreign.kt"), point.copy(fileDigest = Digests.sha256("foreign")),
            point.copy(line = 0, endLine = 0), point.copy(column = 500, endColumn = 500), point.copy(endColumn = point.column + 1))) {
            val changed = s.copy(parsed = s.parsed.copy(diagnostics = s.parsed.diagnostics.map { it.copy(location = bad) }),
                result = s.result.copy(findings = s.result.findings.map { it.copy(location = bad) }))
            assertFalse(assess(changed).knownAssertionsMatched)
        }
    }
    @Test fun registryClassificationAndProviderStageBooleansAreNotInventedByTheHarness() {
        val s = synthetic()
        for (changed in listOf(
            s.result.copy(providerVersion = "2.4.20"), s.result.copy(status = "complete"),
            s.result.copy(findings = s.result.findings.map { it.copy(kind = "invented", certainty = "invented") }),
            s.result.copy(coverage = emptyList()),
            s.result.copy(coverage = s.result.coverage.map { it.copy(implemented = false) }),
            s.result.copy(coverage = s.result.coverage.map { it.copy(tested = true) }),
            s.result.copy(coverage = s.result.coverage.map { it.copy(qualified = true) }),
            s.result.copy(coverage = s.result.coverage.map { it.copy(certificateId = Digests.sha256("fabricated certificate")) }),
        )) assertFalse(assess(s.copy(result = changed)).knownAssertionsMatched)
    }
    @Test fun versionTransportMalformedOutputTimeoutAndInvalidRuntimeStringsNeverReceiveCredit() {
        val s = synthetic()
        for (process in listOf(s.process.copy(status = "failed"), s.process.copy(exitCode = null),
            s.process.copy(cancelled = true), s.process.copy(timedOut = true), s.process.copy(truncated = true),
            s.process.copy(executionConfirmed = false), s.process.copy(reasons = listOf("process_output_invalid_utf8"))))
            assertFalse(assess(s.copy(process = process)).knownAssertionsMatched)
        assertFalse(assess(s.copy(parsed = s.parsed.copy(reportedVersion = "2.4.20"))).knownAssertionsMatched)
        assertFalse(assess(s.copy(parsed = s.parsed.copy(malformed = true))).knownAssertionsMatched)
        val parsed = CompilerDiagnosticParser().parse("quoted [UNRESOLVED_REFERENCE] text, not a compiler header", emptyMap())
        assertTrue(parsed.diagnostics.isEmpty())
        assertFalse(assess(s.copy(parsed = parsed)).knownAssertionsMatched)
        val retention = CompilerOrdinary445FixtureOracle.streamRetention(s.process.copy(reasons = listOf("process_output_invalid_utf8")))
        assertEquals(false, retention["native_invalid_utf8_raw_bytes_available"])
        assertEquals(false, retention["valid_utf8_round_trip_only"])
        assertEquals(false, retention["invalid_or_truncated_streams_can_receive_credit"])
    }
    @Test fun configurationNeedsBothActualPromotionAndIndependentCleanDisabledWarning() {
        val promoted = synthetic(jobFor("configuration", warning = true))
        assertTrue(assess(promoted).knownAssertionsMatched)
        assertEquals("error", promoted.result.findings.single().severity)
        val wrong = promoted.copy(parsed = promoted.parsed.copy(diagnostics = promoted.parsed.diagnostics.map { it.copy(severity = "warning") }),
            result = promoted.result.copy(findings = promoted.result.findings.map { it.copy(severity = "warning") }))
        assertFalse(assess(wrong).knownAssertionsMatched)
        val disabled = synthetic(jobFor("warning_disabled", warning = true))
        assertTrue(assess(disabled).knownAssertionsMatched)
        assertFalse(assess(disabled.copy(result = disabled.result.copy(coverage = disabled.result.coverage.map { it.copy(reason = "unqualified") }))).knownAssertionsMatched)
        assertFalse(assess(withDiagnostic(disabled, "UNRESOLVED_IMPORT", "info", "not clean")).knownAssertionsMatched)
    }
    @Test fun warningSuppressionMustRemoveOnlySuppressedFileWhileAdjacentControlStillEmits() {
        val s = synthetic(jobFor("suppression", warning = true))
        assertTrue(assess(s).knownAssertionsMatched)
        assertEquals(s.job.unsuppressedPath, s.result.findings.single().location.path)
        val allAbsent = synthetic(s.job, count = 0)
        assertFalse(assess(allAbsent).knownAssertionsMatched)
        val badPoint = SourceLocation(s.job.primaryPath, Digests.sha256(s.job.sources.getValue(s.job.primaryPath)), 3, 17)
        val bad = s.copy(parsed = s.parsed.copy(diagnostics = s.parsed.diagnostics.map { it.copy(location = badPoint) }),
            result = s.result.copy(findings = s.result.findings.map { it.copy(location = badPoint) }))
        assertFalse(assess(bad).knownAssertionsMatched)
        assertFalse(assess(s.copy(result = s.result.copy(reasons = emptyList()))).knownAssertionsMatched)
    }
    private fun refusalInput(job: CompilerOrdinary445Job) = AnalysisInput(
        SnapshotManifest(Digests.sha256("synthetic-refusal-snapshot"), "unit", "unit", job.sources.map { (path, text) ->
            SnapshotFile(path, Digests.sha256(text), text.toByteArray().size.toLong()) }), "/unit/not-materialized",
        AnalysisContext(scope, job.requestedVersion, compilerArguments = job.flags, fullProject = false, compilationFiles = job.sources.keys.toList()), job.ruleIds)
    @Test fun versionRefusalIsAnActualExactProviderReturnNotAFabricatedCompilerPass() {
        val j = jobFor("requested_version_refusal")
        val input = refusalInput(j)
        val reason = "compiler_version_not_pinned_2_4_21"
        val refusalResult = ProviderResult(CompilerRuleRegistry.PROVIDER_ID, "unavailable", coverage = j.ruleIds.map {
            Coverage(it, scope, "unsupported", reason) }, reasons = listOf(reason), providerVersion = "2.4.21")
        fun assess(returned: ProviderResult = refusalResult, prepared: AnalysisInput = input, observed: Int = 0, registered: Boolean = false) =
            CompilerOrdinary445FixtureOracle.assessRefusal(j, prepared, returned, observed, registered)
        assertTrue(assess().knownAssertionsMatched)
        assertFalse(assess(observed = 1).knownAssertionsMatched)
        assertFalse(assess(registered = true).knownAssertionsMatched)
        assertFalse(assess(refusalResult.copy(reasons = listOf("execution_not_authorized"))).knownAssertionsMatched)
        assertFalse(assess(refusalResult.copy(status = "partial")).knownAssertionsMatched)
        assertFalse(assess(prepared = input.copy(context = input.context.copy(compilerVersion = "2.4.21"))).knownAssertionsMatched)
        assertFalse(assess(prepared = input.copy(execution = TrustedExecution("/not-used", approved = false))).knownAssertionsMatched)
    }
    private fun stamp(s: Synthetic, tag: String) = CompilerOrdinary445Stamp(s.job.id, Digests.sha256("snapshot-$tag"),
        Digests.sha256("key-$tag"), "/unit/output-$tag", s.job.primaryPath, s.job.sources.getValue(s.job.primaryPath),
        s.result.findings.map { CompilerOrdinary445Point(it.ruleId, it.upstreamId, it.severity, it.diagnosis, it.location) })
    @Test fun regressionRequiresFreshDigestSnapshotKeyOutputAndEveryExactlyShiftedOccurrence() {
        val before = stamp(synthetic(count = 2), "before")
        val after = stamp(synthetic(jobFor("regression"), count = 2), "after")
        assertTrue(CompilerOrdinary445FixtureOracle.regressionMatches(before, after))
        assertFalse(CompilerOrdinary445FixtureOracle.regressionMatches(null, after))
        for (bad in listOf(after.copy(snapshotId = before.snapshotId), after.copy(analysisKey = before.analysisKey),
            after.copy(outputDirectory = before.outputDirectory), after.copy(sourceText = before.sourceText),
            after.copy(sourcePath = before.sourcePath), after.copy(points = after.points.dropLast(1)),
            after.copy(points = after.points.map { it.copy(message = "changed") }),
            after.copy(points = after.points.map { p -> p.copy(location = checkNotNull(p.location).let { it.copy(line = it.line - 1, endLine = it.endLine - 1) }) })))
            assertFalse(CompilerOrdinary445FixtureOracle.regressionMatches(before, bad))
    }
    private fun facts(warning: Boolean): List<CompilerOrdinary445Fact> = schedule(warning).map { j ->
        val a = if (j.refusal) CompilerOrdinary445Assessment(true, true, null, emptyList(), 0, 0) else assess(synthetic(j))
        CompilerOrdinary445Fact(j.id, j.ruleIds, j.kind, j.control, true, true, true, a,
            mapOf("path" to "synthetic-unit/${j.id}.json", "sha256" to Digests.sha256("synthetic unit placeholder NOT runtime evidence").removePrefix("sha256:")))
    }
    @Test fun actualEvidenceHelperReferenceMatchesLedgerWithoutDigestOrPathRepair() {
        // Tiny owned-file control only. These synthetic facts are not compiler execution receipts.
        val repository = Files.createTempDirectory("ordinary445-reference-unit-").toRealPath()
        try {
            val run = Files.createDirectory(repository.resolve("run"))
            val store = CompilerFixtureEvidence(repository, run, null,
                maximumBytes = 65_536, terminalReserveBytes = 8_192)
            val receipt = run.resolve("positive-unit.json")
            val bytes = "{\"synthetic_unit_only\":true}\n".toByteArray()
            store.writeNew(receipt, bytes)
            val reference = store.reference(receipt)
            assertEquals(setOf("path", "sha256"), reference.keys)
            assertEquals(repository.relativize(receipt).toString(), reference.getValue("path"))
            assertEquals(Digests.sha256(bytes).removePrefix("sha256:"), reference.getValue("sha256"))
            assertTrue(reference.getValue("sha256").matches(Regex("[0-9a-f]{64}")))
            assertFalse(reference.getValue("sha256").startsWith("sha256:"))
            val original = facts(true)
            val fact = original.first().copy(evidence = reference)
            val row = CompilerOrdinary445FixtureLedger.rows(listOf(fixture(true)), listOf(fact) + original.drop(1), true).single()
            if (repository.fileSystem.separator == "/") {
                assertTrue(fact.attributable)
                assertEquals(true, row["local_synthetic_implemented"])
                assertEquals(true, row["local_six_kind_tested"])
            } else {
                // This runner admits Linux only; native Windows separators must not be rewritten.
                assertFalse(fact.attributable)
                assertNull(row["local_synthetic_implemented"])
                assertNull(row["local_six_kind_tested"])
            }
            assertEquals(false, row["qualified"])
        } finally {
            Files.walk(repository).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
    @Test fun wrongImmutableReferenceSchemaCannotBeNormalizedIntoAttributionOrLocalStages() {
        val original = facts(true)
        val first = original.first()
        val reference = checkNotNull(first.evidence)
        val raw = reference.getValue("sha256")
        assertTrue(first.attributable) // Exact helper-shaped raw64 schema, not a prefixed synthetic substitute.
        val wrong = listOf(
            reference + ("sha256" to "sha256:$raw"), reference + ("sha256" to "A".repeat(64)),
            reference + ("sha256" to raw.dropLast(1)), reference + ("sha256" to raw + "0"),
            reference + ("sha256" to " $raw"), reference + ("sha256" to "$raw\n"),
            reference + ("digest" to raw), reference - "path", reference - "sha256",
            mapOf("path" to reference.getValue("path"), "digest" to raw),
            reference + ("path" to "/absolute.json"), reference + ("path" to "../outside.json"),
            reference + ("path" to "run/../receipt.json"), reference + ("path" to "run//receipt.json"),
            reference + ("path" to "run\\receipt.json"), reference + ("path" to ""),
        )
        for (evidence in wrong) {
            val fact = first.copy(evidence = evidence)
            assertFalse(fact.attributable)
            val row = CompilerOrdinary445FixtureLedger.rows(listOf(fixture(true)), listOf(fact) + original.drop(1), true).single()
            assertNull(row["local_synthetic_implemented"])
            assertNull(row["local_six_kind_tested"])
            assertEquals(false, row["custody_and_terminal_publication_settled"])
            assertEquals(false, row["qualified"])
        }
    }
    @Test fun onlySettledActualKindReceiptsCouldSupportNarrowLocalStagesNeverQualification() {
        val warning = CompilerOrdinary445FixtureLedger.rows(listOf(fixture(true)), facts(true), true).single()
        assertEquals(true, warning["local_synthetic_implemented"])
        assertEquals(true, warning["local_six_kind_tested"])
        assertEquals(false, warning["qualified"])
        assertEquals(false, warning["whole_context_or_global_coverage_credit"])
        val error = CompilerOrdinary445FixtureLedger.rows(listOf(fixture()), facts(false), true).single()
        assertEquals(true, error["local_synthetic_implemented"])
        assertNull(error["local_six_kind_tested"])
        assertEquals(true, error["hard_error_suppression_diagnostic_expectation_unknown"])
        assertEquals(false, error["ktchecker_accepted_risk_owner_workflow_exercised"])
    }
    @Test fun missingWarningDisabledRefusalOrOtherKindCannotProduceTestedLedger() {
        val facts = facts(true)
        for (control in listOf("warning_disabled", "requested_version_refusal", "negative", "regression")) {
            val row = CompilerOrdinary445FixtureLedger.rows(listOf(fixture(true)), facts.filterNot { it.control == control }, true).single()
            assertNull(row["local_synthetic_implemented"])
            assertNull(row["local_six_kind_tested"])
        }
        assertFails { CompilerOrdinary445FixtureLedger.rows(listOf(fixture(true)), facts + facts.first(), true) }
    }
    @Test fun persistenceProvenanceCleanupOrLatePublicationFailureRevokesAllLocalStages() {
        val original = facts(true)
        val first = original.first()
        for (bad in listOf(first.copy(persisted = false), first.copy(receiptInvariants = false), first.copy(cleaned = false),
            first.copy(evidence = null), first.copy(evidence = mapOf("path" to "fake", "sha256" to "not-a-digest")),
            first.copy(kind = "negative"), first.copy(assessment = first.assessment!!.invalidate("late_index_or_checkpoint_failure")))) {
            val row = CompilerOrdinary445FixtureLedger.rows(listOf(fixture(true)), listOf(bad) + original.drop(1), true).single()
            assertNull(row["local_synthetic_implemented"])
            assertNull(row["local_six_kind_tested"])
        }
        val unpublished = CompilerOrdinary445FixtureLedger.rows(listOf(fixture(true)), original, false).single()
        assertNull(unpublished["local_synthetic_implemented"])
        assertNull(unpublished["local_six_kind_tested"])
    }
    @Test fun invalidatingUnknownSuppressionNeverConvertsNullToPassOrHidesFailure() {
        val a = assess(synthetic(jobFor("suppression"))).invalidate("receipt_late_failure")
        assertNull(a.diagnosticExpectedOutcomeMatched)
        assertEquals(false, a.safetyControlMatched)
        assertFalse(a.knownAssertionsMatched)
    }
}

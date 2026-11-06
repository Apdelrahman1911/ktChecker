package dev.ktchecker.cli

import dev.ktchecker.compiler.CompilerRuleRegistry
import dev.ktchecker.compiler.live.model.KotlinJvmModelLiveCompilerProvider
import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.OwnerFullCatalog1665
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.*

/**
 * SYNTHETIC fixed-shape controls only. A fake child uses the real coordinator/normalizer and
 * catalog, but never a compiler, generation, importer, output token or kernel publication.
 * A clean classifier result here is NOT accepted-output authority or execution/qualification.
 */
class OwnerProducedOperationalTest {
    private val catalog = Catalog()
    private val registry = CompilerRuleRegistry.load()
    private val selected = listOf("ktc.compiler.common.deprecation", "ktc.compiler.common.unresolved_reference")
    private val disabled = "ktc.compiler.common.unused_variable"
    private val missingActive = "ktc.kotlin.detekt.style.magic-number"
    private val policy = Digests.sha256("SYNTHETIC classifier captured policy")
    private val image by lazy { CodeImage.capture(listOf(ReviewCoordinator::class.java)) }
    private val mandatory = listOf("compiler_model_live_preview_not_qualified",
        "compiler_model_live_semantic_extraction_not_authorized",
        "compiler_model_live_elapsed_measured_to_armed_render_not_call_completion",
        "compiler_model_live_cpu_rss_unavailable", "compiler_model_live_produced_output_preview_not_qualified")
    private val logging = listOf("compiler_model_live_informational_logging_redacted",
        "compiler_model_live_unmapped_diagnostic", "compiler_model_live_raw_diagnostics_unresolved")
    private val suppression = "compiler_model_live_suppression_scope_not_certified"
    private val warnings = "compiler_model_live_warnings_disabled_under_selected_configuration"
    private val redacted = RawDiagnostic("Compiler informational logging was observed; private log text is not published.",
        "info", null, null)

    private fun newInput(arguments: List<String> = emptyList()): AnalysisInput {
        val rules = catalog.ids.sorted()
        val activation = activationPlan(catalog, rules, false,
            selected.associateWith { true } + mapOf(disabled to false, missingActive to true), policy)
        return AnalysisInput(SnapshotManifest("synthetic-classifier-snapshot", "workspace", "worktree",
            listOf(SnapshotFile("src/main/kotlin/Api.kt", Digests.sha256("fun api() = 1\n"), 14)), "owner"),
            "/synthetic-absent-frozen-sources", AnalysisContext(
                scope = RuleContext("", "main", ":example", "main"), compilerVersion = "2.4.21",
                compilerArguments = arguments, configurationDigest = policy, fullProject = false,
                compilationFiles = listOf("src/main/kotlin/Api.kt"), policies = mapOf(
                    ACTIVATION_POLICY_KEY to ContractJson.encodeToString(ActivationPlan.serializer(), activation))),
            rules, outputRoot = "/synthetic-absent-attempt-output")
    }

    private fun child(input: AnalysisInput): ProviderResult = ProviderResult(
        KotlinJvmModelLiveCompilerProvider.PROVIDER_ID, "partial", coverage = selected.map {
            Coverage(it, input.context.scope, "unqualified", "compiler_model_live_preview_not_qualified",
                depth = "build", provenance = "executed")
        }, reasons = mandatory + if ("-nowarn" in input.context.compilerArguments) listOf(warnings) else emptyList(),
        metrics = AnalysisMetrics(outputBytes = 512), providerVersion = KotlinJvmModelLiveCompilerProvider.VERSION)

    private fun warning(input: AnalysisInput): Finding = Finding(selected.first(), "SYNTHETIC warning shape",
        "SYNTHETIC diagnostic; no compiler ran", "Classifier control, not rule evidence",
        SourceLocation(input.snapshot.files.single().path, input.snapshot.files.single().digest),
        Digests.sha256("SYNTHETIC finding fingerprint"), KotlinJvmModelLiveCompilerProvider.PROVIDER_ID,
        input.context.scope, upstreamId = "DEPRECATION", semanticDigest = input.analysisKey())

    private inner class Shape(val input: AnalysisInput = newInput()) {
        val deadline = OwnedApplicationDeadline.afterMillis(120_000)
        val childInput = input.copy(requiredRuleIds = selected, execution = null)
        val actual = child(childInput)
        private var emitted = actual
        private var observed: AnalysisInput? = null
        val provider = object : AnalysisProvider {
            override val id = KotlinJvmModelLiveCompilerProvider.PROVIDER_ID
            override val version = KotlinJvmModelLiveCompilerProvider.VERSION
            override val supportedRuleIds = registry.jvmRuleIds
            override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
                observed = input
                return emitted
            }
        }
        val coordinator = ReviewCoordinator(catalog, listOf(ProviderRoute(provider, { true })), ExecutionGrants(), image)

        fun project(result: ProviderResult = actual, onProgress: (ProviderResult) -> Unit = {}): ProviderResult {
            emitted = result
            observed = null
            val parent = coordinator.analyzeProgressive(input, CancellationToken.NONE, onProgress)
            assertEquals(childInput, observed, "The real coordinator must project exactly the selected child rules")
            return parent
        }

        fun faults(result: ProviderResult, parent: ProviderResult, callElapsed: Long = 0,
            candidateInput: AnalysisInput = input, candidateChild: AnalysisInput = childInput): List<String> =
            ownerProducedParentFaults(catalog, coordinator, provider, candidateInput, candidateChild, result,
                callElapsed, deadline, parent)

        fun assertDenied(result: ProviderResult, message: String) {
            assertTrue(faults(result, project(result)).isNotEmpty(), message)
        }
    }

    @Test fun exactKnownGapsKeepAll1665RowsPartialAndUnqualified() {
        val s = Shape()
        val parent = s.project()
        assertTrue(s.faults(s.actual, parent).isEmpty())
        assertEquals("partial", parent.status)
        assertEquals(catalog.ids.sorted(), parent.coverage.map { it.ruleId })
        assertEquals(1665, parent.coverage.size)
        assertTrue(OwnerFullCatalog1665.accepts(parent.coverage.map { it.ruleId }))
        for (added in listOf(OwnerFullCatalog1665.ERROR_SUPPRESSION, OwnerFullCatalog1665.INAPPLICABLE_OPERATOR_MODIFIER)) {
            val row = parent.coverage.single { it.ruleId == added }
            assertTrue(row.required); assertFalse(row.tested); assertFalse(row.qualified); assertNull(row.certificateId)
            assertFalse(row.successful())
        }
        assertTrue(parent.coverage.all { it.required && !it.qualified && !it.tested && it.certificateId == null })
        assertEquals("disabled", parent.coverage.single { it.ruleId == disabled }.state)
        assertEquals("no_compatible_installed_rule_implementation", parent.coverage.single { it.ruleId == missingActive }.reason)
        selected.forEach { rule ->
            val row = parent.coverage.single { it.ruleId == rule }
            assertEquals("unqualified", row.state)
            assertEquals("executed", row.provenance)
            assertFalse(row.successful())
        }
        assertContains(parent.reasons, "preview_not_release_qualified")
        assertContains(parent.reasons, "required_rule_coverage_unavailable")
        assertFalse(parent.completeFor(s.input))
    }

    @Test fun legacyMissingAddedDuplicateAndSameCountReplacementVectorsCannotBorrowCurrentProjection() {
        val s = Shape(); val parent = s.project(); val current = s.input.requiredRuleIds
        val added = listOf(OwnerFullCatalog1665.ERROR_SUPPRESSION, OwnerFullCatalog1665.INAPPLICABLE_OPERATOR_MODIFIER)
        val legacy = current - added.toSet()
        assertEquals(1663, legacy.size)
        val substituted = (current - added.first() + "ktc.compiler.common.unreviewed_same_count_substitute").sorted()
        val duplicated = (current - added.first() + current.first()).sorted()
        assertEquals(1665, substituted.size); assertEquals(1665, duplicated.size)
        (listOf(legacy, substituted, duplicated, current.reversed()) + added.map { current - it }).forEach { ids ->
            assertFalse(OwnerFullCatalog1665.accepts(ids))
            assertEquals(listOf("owner_produced_operational_identity_mismatch"),
                s.faults(s.actual, parent, candidateInput = s.input.copy(requiredRuleIds = ids)))
        }
    }

    @Test fun exactRedactedGlobalLoggingAndExplicitSuppressionGapStayVisible() {
        val s = Shape()
        val actual = s.actual.copy(reasons = mandatory + suppression + logging,
            rawDiagnostics = listOf(redacted, redacted))
        val parent = s.project(actual)
        assertTrue(s.faults(actual, parent).isEmpty())
        assertEquals(actual.rawDiagnostics, parent.rawDiagnostics)
        (logging + suppression).forEach { assertContains(parent.reasons, "${s.provider.id}:$it") }
    }

    @Test fun arbitraryInfoWarningsErrorsAndLocatedOrNamedLoggingCannotMasqueradeAsTheRedactedGap() {
        val s = Shape()
        val location = SourceLocation(s.input.snapshot.files.single().path, s.input.snapshot.files.single().digest)
        listOf(RawDiagnostic("arbitrary information", "info"), redacted.copy(severity = "warning"),
            redacted.copy(severity = "error"), redacted.copy(upstreamId = "DEPRECATION"),
            redacted.copy(location = location), redacted.copy(message = redacted.message + " ")).forEach { raw ->
            s.assertDenied(s.actual.copy(reasons = mandatory + logging, rawDiagnostics = listOf(raw)),
                "Only the exact global redacted record is permitted, not $raw")
        }
    }

    @Test fun missingDuplicateUnknownAndUnbackedLoggingReasonsDeny() {
        val s = Shape()
        val variants = mandatory.map { omitted -> mandatory - omitted } + listOf(
            mandatory + mandatory.first(), mandatory + "compiler_model_live_api_diagnostic_failure",
            mandatory + "compiler_model_live_whatever_new_gap", mandatory + logging)
        variants.forEach { reasons -> s.assertDenied(s.actual.copy(reasons = reasons), "Unexpected reason shape: $reasons") }
        logging.forEach { omitted ->
            s.assertDenied(s.actual.copy(reasons = mandatory + (logging - omitted), rawDiagnostics = listOf(redacted)),
                "A logging record cannot omit its $omitted gap")
        }
    }

    @Test fun nowarnReasonMatchesTheExactOriginalArgumentRatherThanAnAssumedCleanCompiler() {
        val s = Shape(newInput(listOf("-nowarn")))
        val parent = s.project()
        assertTrue(s.faults(s.actual, parent).isEmpty())
        assertContains(parent.reasons, "${s.provider.id}:$warnings")
        s.assertDenied(s.actual.copy(reasons = s.actual.reasons - warnings), "-nowarn must remain disclosed")
        val normal = Shape()
        normal.assertDenied(normal.actual.copy(reasons = mandatory + warnings), "A reason cannot replace an original argument")
    }

    @Test fun failedCancelledCompleteOrUnavailableChildNeverBecomesOperationallyAccepted() {
        val s = Shape()
        listOf("failed", "cancelled", "complete", "unavailable").forEach {
            s.assertDenied(s.actual.copy(status = it), "Unrecognized child status: $it")
        }
        s.assertDenied(s.actual.copy(providerId = "ordinary-compiler"), "Wrong child provider")
        s.assertDenied(s.actual.copy(providerVersion = "different"), "Wrong child implementation")
    }

    @Test fun everyChildCoverageFieldAndTheCompleteOrderedProjectionAreRequired() {
        val s = Shape()
        val row = s.actual.coverage.first()
        val altered = listOf(row.copy(state = "passed"), row.copy(reason = null), row.copy(required = false),
            row.copy(depth = "semantic"), row.copy(provenance = "not_executed"), row.copy(certificateId = "invented"),
            row.copy(implemented = true), row.copy(tested = true), row.copy(qualified = true),
            row.copy(context = row.context.copy(sourceSet = "test")), row.copy(ruleId = disabled))
        val vectors = altered.map { listOf(it) + s.actual.coverage.drop(1) } + listOf(
            s.actual.coverage.drop(1), s.actual.coverage + row, s.actual.coverage.reversed())
        vectors.forEach { rows -> s.assertDenied(s.actual.copy(coverage = rows), "Changed or incomplete child coverage: $rows") }
    }

    @Test fun syntheticWarningIdentityAndExactCoordinatorDeduplicationArePreservedWithoutQualification() {
        val s = Shape()
        val finding = warning(s.childInput)
        val actual = s.actual.copy(findings = listOf(finding, finding), coverage = s.actual.coverage.map {
            it.copy(implemented = it.ruleId == finding.ruleId)
        })
        val parent = s.project(actual)
        assertTrue(s.faults(actual, parent).isEmpty())
        val normalized = parent.findings.single()
        assertEquals(finding.copy(fingerprint = normalized.fingerprint), normalized)
        assertTrue(Digests.valid(normalized.fingerprint))
        assertNotEquals(finding.fingerprint, normalized.fingerprint, "Raw coordinator identity has its own versioned schema")
        assertEquals(listOf("owner_produced_coordinator_return_substituted"),
            s.faults(actual, parent.copy(findings = listOf(finding))))
        assertTrue(parent.coverage.single { it.ruleId == finding.ruleId }.implemented)
        assertFalse(parent.completeFor(s.input))
    }

    @Test fun coLocatedMaterialWarningsRemainDistinctAndCannotBeDroppedFromTheStrictParentProjection() {
        val s = Shape()
        val finding = warning(s.childInput)
        val sibling = finding.copy(evidence = listOf(Evidence("synthetic_material", "Independent cause",
            digest = Digests.sha256("SYNTHETIC second cause"))), certainty = "high")
        assertEquals(finding.fingerprint, sibling.fingerprint)
        assertEquals(finding.location, sibling.location)
        assertEquals(finding.diagnosis, sibling.diagnosis)
        val actual = s.actual.copy(findings = listOf(finding, sibling), coverage = s.actual.coverage.map {
            it.copy(implemented = it.ruleId == finding.ruleId)
        })
        val parent = s.project(actual)
        assertTrue(s.faults(actual, parent).isEmpty())
        assertEquals(listOf(finding, sibling), parent.findings.map { it.copy(fingerprint = finding.fingerprint) })
        assertEquals(2, parent.findings.map { it.fingerprint }.distinct().size)
        assertTrue(parent.findings.all { Digests.valid(it.fingerprint) })
        assertEquals(listOf("owner_produced_coordinator_return_substituted"),
            s.faults(actual, parent.copy(findings = parent.findings.take(1))))
        assertEquals(listOf("owner_produced_coordinator_return_substituted"), s.faults(actual,
            parent.copy(findings = parent.findings.map { it.copy(fingerprint = finding.fingerprint) })))
        assertEquals("partial", parent.status)
        assertTrue(parent.coverage.all { !it.qualified && !it.tested && it.certificateId == null })
        assertFalse(parent.completeFor(s.input))
    }

    @Test fun decodedMalformedCompilerTextFailsTheWholeProjectionEvenWithAPlausibleCleanParent() {
        val s = Shape()
        val finding = warning(s.childInput)
        val valid = s.actual.copy(findings = listOf(finding), coverage = s.actual.coverage.map {
            it.copy(implemented = it.ruleId == finding.ruleId)
        })
        val plausible = s.project(valid)
        assertTrue(s.faults(valid, plausible).isEmpty(), "The clean reference is only a synthetic classifier shape")
        val marker = "synthetic-owner-json-diagnosis-marker"
        val marked = valid.copy(findings = listOf(finding.copy(diagnosis = marker)))
        for ((escape, unit) in listOf("\\ud800" to 0xd800, "\\udc00" to 0xdc00)) {
            val wire = ContractJson.encodeToString(marked).replace("\"$marker\"", "\"$escape\"")
            assertTrue(escape in wire)
            val decoded = ContractJson.decodeFromString<ProviderResult>(wire)
            val malformed = decoded.findings.single()
            assertEquals(listOf(unit), malformed.diagnosis.map { it.code }, "The escaped lone unit must reach the classifier")
            for (siblings in listOf(listOf(malformed), listOf(finding, malformed), listOf(malformed, finding))) {
                val actual = decoded.copy(findings = siblings)
                val progress = mutableListOf<ProviderResult>()
                val parent = s.project(actual) { progress += it }
                assertEquals(1, progress.size)
                (progress + parent).forEach { published ->
                    assertEquals("partial", published.status)
                    assertTrue(published.findings.isEmpty(), "Do not retain a benign prefix or suffix of a failed child")
                    val rows = published.coverage.filter { it.ruleId in selected }
                    assertEquals(selected, rows.map { it.ruleId })
                    assertTrue(rows.all { it.state == "failed" && it.reason == "coordinator_finding_text_invalid" })
                    assertFalse(published.coverage.any { it.successful() || it.qualified || it.tested || it.certificateId != null })
                    assertFalse(published.completeFor(s.input))
                }
                assertEquals(1665, parent.coverage.size)
                assertEquals(catalog.ids.sorted(), parent.coverage.map { it.ruleId })
                assertEquals(plausible.coverage.filterNot { it.ruleId in selected }, parent.coverage.filterNot { it.ruleId in selected })
                assertContains(parent.reasons, "preview_not_release_qualified")
                assertEquals(listOf("owner_produced_compiler_finding_unrecognized"), s.faults(actual, parent))
                assertEquals(listOf("owner_produced_compiler_finding_unrecognized"), s.faults(actual, plausible),
                    "A supplied clean projection cannot launder the exact actual malformed child")
            }
        }
    }

    @Test fun warningProviderRuleSourceContextSeverityAndSemanticIdentityCannotBeSubstituted() {
        val s = Shape()
        val finding = warning(s.childInput)
        listOf(finding.copy(providerId = "another-provider"), finding.copy(ruleId = disabled),
            finding.copy(context = finding.context.copy(compilation = "test")), finding.copy(disposition = "suppressed"),
            finding.copy(severity = "error"), finding.copy(severity = "info"), finding.copy(upstreamId = null),
            finding.copy(semanticDigest = s.input.analysisKey()), finding.copy(fingerprint = "not-a-digest"),
            finding.copy(location = finding.location.copy(fileDigest = Digests.sha256("other bytes"))),
            finding.copy(location = finding.location.copy(path = "not-in-the-snapshot.kt"))).forEach { altered ->
            s.assertDenied(s.actual.copy(findings = listOf(altered), coverage = s.actual.coverage.map {
                it.copy(implemented = it.ruleId == finding.ruleId)
            }), "Changed warning identity: $altered")
        }
    }

    @Test fun exactActualChildIsCheckedEvenWhenAPlausibleCleanParentIsSupplied() {
        val s = Shape()
        val cleanParent = s.project()
        val failed = s.actual.copy(status = "failed", reasons = mandatory + "compiler_model_live_api_diagnostic_failure")
        assertTrue(s.faults(failed, cleanParent).isNotEmpty(), "Parent normalization cannot wash an actual child failure")
        assertTrue(s.faults(s.actual.copy(coverage = s.actual.coverage.drop(1)), cleanParent).isNotEmpty())
    }

    @Test fun anyParentIdentityRowsReasonsRawFindingsOrMetricsSubstitutionDenies() {
        val s = Shape()
        val parent = s.project()
        val first = parent.coverage.first()
        val changed = listOf(parent.copy(providerId = "other-coordinator"), parent.copy(providerVersion = "other-version"),
            parent.copy(status = "complete"), parent.copy(coverage = parent.coverage.drop(1)),
            parent.copy(coverage = parent.coverage + first), parent.copy(coverage = parent.coverage.reversed()),
            parent.copy(coverage = listOf(first.copy(required = false)) + parent.coverage.drop(1)),
            parent.copy(reasons = parent.reasons.drop(1)), parent.copy(reasons = parent.reasons + "hidden-gap"),
            parent.copy(rawDiagnostics = listOf(redacted)), parent.copy(findings = listOf(warning(s.childInput))),
            parent.copy(metrics = parent.metrics.copy(cpuMillis = 0)), parent.copy(metrics = parent.metrics.copy(peakRssBytes = 1)),
            parent.copy(metrics = parent.metrics.copy(outputBytes = parent.metrics.outputBytes + 1)))
        changed.forEach { assertTrue(s.faults(s.actual, it).isNotEmpty(), "Parent must equal the actual coordinator projection") }
    }

    @Test fun childCpuRssByteBoundsAndMeasuredCallTimingAreNotGuessed() {
        val s = Shape()
        listOf(AnalysisMetrics(-1, outputBytes = 512), AnalysisMetrics(1, outputBytes = 512),
            AnalysisMetrics(cpuMillis = 0, outputBytes = 512), AnalysisMetrics(peakRssBytes = 1, outputBytes = 512),
            AnalysisMetrics(outputBytes = 0), AnalysisMetrics(outputBytes = -1), AnalysisMetrics(outputBytes = 1_048_577)).forEach {
            s.assertDenied(s.actual.copy(metrics = it), "Child metrics are not within the actual measured call: $it")
        }
        val parent = s.project()
        assertTrue(s.faults(s.actual, parent, callElapsed = -1).isNotEmpty())
    }

    @Test fun parentTimingCannotBeNegativePrecedeTheChildOrOutrunTheOriginalClock() {
        val s = Shape()
        val parent = s.project()
        listOf(-1L, Long.MAX_VALUE, s.deadline.durationMillis + 1).forEach { elapsed ->
            assertTrue(s.faults(s.actual, parent.copy(metrics = parent.metrics.copy(elapsedMillis = elapsed))).isNotEmpty())
        }
        val child = s.actual.copy(metrics = s.actual.metrics.copy(elapsedMillis = 1))
        assertTrue(s.faults(child, parent.copy(metrics = parent.metrics.copy(elapsedMillis = 0)), callElapsed = 1).isNotEmpty())
    }

    @Test fun fullProjectOrCatalogNarrowingAndChangedChildProjectionAreNotTheDeclaredPreview() {
        val s = Shape()
        val parent = s.project()
        listOf(s.input.copy(requiredRuleIds = selected), s.input.copy(requiredRuleIds = s.input.requiredRuleIds.reversed()),
            s.input.copy(context = s.input.context.copy(fullProject = true))).forEach {
            assertTrue(s.faults(s.actual, parent, candidateInput = it).isNotEmpty())
        }
        listOf(s.childInput.copy(requiredRuleIds = selected.drop(1)), s.childInput.copy(requiredRuleIds = selected.reversed()),
            s.childInput.copy(materializedRoot = "/other-absent-root"), s.childInput.copy(outputRoot = null),
            s.childInput.copy(execution = TrustedExecution("/never-executed", approved = true))).forEach {
            assertTrue(s.faults(s.actual, parent, candidateChild = it).isNotEmpty())
        }
    }

    @Test fun missingOrMalformedActivationCannotGrantASelectedCompilerRoute() {
        val s = Shape()
        val parent = s.project()
        val absent = s.input.copy(context = s.input.context.copy(policies = emptyMap()))
        assertTrue(s.faults(s.actual, parent, candidateInput = absent).isNotEmpty())
        val malformed = s.input.copy(context = s.input.context.copy(policies = mapOf(ACTIVATION_POLICY_KEY to "not-json")))
        assertEquals("invalid_activation_plan", assertFailsWith<CliProblem> {
            s.faults(s.actual, parent, candidateInput = malformed)
        }.code)
    }
}

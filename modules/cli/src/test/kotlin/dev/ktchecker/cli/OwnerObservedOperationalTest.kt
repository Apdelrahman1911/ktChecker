package dev.ktchecker.cli

import dev.ktchecker.compiler.CompilerRuleRegistry
import dev.ktchecker.compiler.live.model.KotlinJvmModelLiveCompilerProvider
import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.ObservedExportPlanEvidence
import dev.ktchecker.projectmodel.*
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import kotlin.test.*

/**
 * PURE classifier controls, never output/execute authority. The observed model comes from a real
 * registered snapshot and SYNTHETIC ordinary receipts; the child below is explicitly fake. Only
 * the real coordinator's deterministic projection is exercised. No Gradle/compiler/native call,
 * pinned produced graph, generation, attempt, Accepted token or publication exists in this test.
 */
class OwnerObservedOperationalTest {
    private val registry = CompilerRuleRegistry.load()
    private val selected = listOf("ktc.compiler.common.deprecation", "ktc.compiler.common.unresolved_reference")
    private val mandatory = listOf("compiler_model_live_preview_not_qualified",
        "compiler_model_live_semantic_extraction_not_authorized",
        "compiler_model_live_elapsed_measured_to_armed_render_not_call_completion",
        "compiler_model_live_cpu_rss_unavailable", "compiler_model_live_produced_output_preview_not_qualified",
        CONFIGURATION_INPUT_CLOSURE_UNPROVEN)
    private val image by lazy { CodeImage.capture(listOf(ReviewCoordinator::class.java)) }

    private inner class Shape(val fixture: OwnerModelFixtures) {
        val invocation = ObservedExportInvocation(OwnedApplicationDeadline.afterMillis(120_000))
        val policy = fixture.configs.capture(modelConfigBinding(fixture.binding())).policyDigest
        val capture = fixture.capture()
        val model = fixture.importObservedUnit(capture, policy, invocation)
        val evidence = ObservedExportPlanEvidence(fixture.binding(), invocation.identity, model.evidenceIdentity)
        val input: AnalysisInput
        val childInput: AnalysisInput
        val actual: ProviderResult
        private var emitted: ProviderResult
        private var observedInput: AnalysisInput? = null
        val provider = object : AnalysisProvider {
            override val id = KotlinJvmModelLiveCompilerProvider.PROVIDER_ID
            override val version = KotlinJvmModelLiveCompilerProvider.VERSION
            override val supportedRuleIds = registry.jvmRuleIds
            override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
                observedInput = input
                return emitted
            }
        }
        val coordinator = ReviewCoordinator(fixture.catalog, listOf(ProviderRoute(provider, { true })), ExecutionGrants(), image)

        init {
            assertNull(model.jvmProducedGraph, "The pure classifier is not a produced-graph grant")
            val raw = assertNotNull(model.context(model.compilationIds.first(), policy).context)
            val activation = activationPlan(fixture.catalog, fixture.catalog.ids.sorted(), false,
                selected.associateWith { true }, policy)
            input = AnalysisInput(model.snapshot, capture.root.toString(), raw.copy(policies = raw.policies + evidence.policies() +
                (ACTIVATION_POLICY_KEY to ContractJson.encodeToString(ActivationPlan.serializer(), activation))),
                fixture.catalog.ids.sorted(), outputRoot = fixture.work.resolve("synthetic-absent-output").toString())
            childInput = input.copy(requiredRuleIds = selected, execution = null)
            actual = ProviderResult(provider.id, "partial", coverage = selected.map { rule ->
                Coverage(rule, input.context.scope, "unqualified", "compiler_model_live_preview_not_qualified",
                    depth = "build", provenance = "executed")
            }, reasons = mandatory, metrics = AnalysisMetrics(outputBytes = 512), providerVersion = provider.version)
            emitted = actual
        }

        fun project(result: ProviderResult = actual): ProviderResult {
            emitted = result; observedInput = null
            val parent = coordinator.analyzeProgressive(input, CancellationToken.NONE) { }
            assertEquals(childInput, observedInput)
            return parent
        }

        fun faults(result: ProviderResult, parent: ProviderResult,
            candidateInput: AnalysisInput = input, candidateModel: ObservedProjectModel? = model,
            candidateEvidence: ObservedExportPlanEvidence? = evidence,
            deadline: OwnedApplicationDeadline = invocation.originalDeadline): List<String> =
            ownerProducedParentFaults(fixture.catalog, coordinator, provider, candidateInput, childInput, result,
                0, deadline, parent, candidateModel, candidateEvidence)
    }

    @Test fun typedObservedShapeRetainsActualRawGapAndExactCoordinatorPrefixWithoutQualification() {
        OwnerModelFixtures().use { f ->
            val s = Shape(f); val parent = s.project()
            assertTrue(s.faults(s.actual, parent).isEmpty(), "Pure shape match, NOT output/execute authority")
            assertContains(s.actual.reasons, CONFIGURATION_INPUT_CLOSURE_UNPROVEN)
            assertContains(parent.reasons, "${s.provider.id}:$CONFIGURATION_INPUT_CLOSURE_UNPROVEN")
            assertEquals(1663, parent.coverage.size); assertEquals(f.catalog.ids.sorted(), parent.coverage.map { it.ruleId })
            assertEquals("partial", parent.status); assertFalse(parent.completeFor(s.input))
            assertTrue(parent.coverage.all { it.required && !it.tested && !it.qualified && it.certificateId == null })
        }
    }

    @Test fun ordinaryClassifierCannotTreatObservedMarkersOrHalfATypedOriginAsStrictInput() {
        OwnerModelFixtures().use { f ->
            val s = Shape(f); val parent = s.project()
            assertEquals(listOf("owner_observed_origin_required"), s.faults(s.actual, parent,
                candidateModel = null, candidateEvidence = null))
            assertEquals(listOf("owner_observed_origin_mismatch"), s.faults(s.actual, parent, candidateEvidence = null))
            assertEquals(listOf("owner_observed_origin_mismatch"), s.faults(s.actual, parent, candidateModel = null))
        }
    }

    @Test fun parentOrInputMetadataCannotRepairAMissingOrPrefixedOnlyActualChildGap() {
        OwnerModelFixtures().use { f ->
            val s = Shape(f); val truthfulParent = s.project()
            for (replacement in listOf<String?>(null, "${s.provider.id}:$CONFIGURATION_INPUT_CLOSURE_UNPROVEN",
                    "$CONFIGURATION_INPUT_CLOSURE_UNPROVEN:synthetic", "unrelated:$CONFIGURATION_INPUT_CLOSURE_UNPROVEN")) {
                val child = s.actual.copy(reasons = (mandatory - CONFIGURATION_INPUT_CLOSURE_UNPROVEN) + listOfNotNull(replacement))
                assertEquals(listOf("owner_observed_raw_taint_missing"), s.faults(child, truthfulParent))
                assertEquals(listOf("owner_observed_raw_taint_missing"), s.faults(child, s.project(child)))
            }
        }
    }

    @Test fun coordinatorProjectionCannotStripDuplicateRenameOrInventTheClosureReason() {
        OwnerModelFixtures().use { f ->
            val s = Shape(f); val parent = s.project()
            val exact = "${s.provider.id}:$CONFIGURATION_INPUT_CLOSURE_UNPROVEN"
            val variants = listOf(parent.reasons - exact, parent.reasons + exact,
                parent.reasons.map { if (it == exact) CONFIGURATION_INPUT_CLOSURE_UNPROVEN else it },
                parent.reasons.map { if (it == exact) "another-provider:$CONFIGURATION_INPUT_CLOSURE_UNPROVEN" else it })
            variants.forEach { reasons ->
                assertEquals(listOf("owner_produced_coordinator_return_substituted"),
                    s.faults(s.actual, parent.copy(reasons = reasons)))
            }
        }
    }

    @Test fun typedEvidenceOriginalClockAndEveryObservedPolicyRemainExactlyBound() {
        OwnerModelFixtures().use { f ->
            val s = Shape(f); val parent = s.project()
            val changes = listOf(s.evidence.copy(invocationId = "other-invocation"),
                s.evidence.copy(evidenceDigest = Digests.sha256("other evidence")),
                s.evidence.copy(assurance = "complete"),
                s.evidence.copy(binding = s.evidence.binding.copy(lineageId = "another-lineage")))
            changes.forEach { evidence -> assertEquals(listOf("owner_observed_origin_mismatch"),
                s.faults(s.actual, parent, candidateEvidence = evidence)) }
            assertEquals(listOf("owner_observed_origin_mismatch"), s.faults(s.actual, parent,
                deadline = OwnedApplicationDeadline.afterMillis(s.invocation.originalDeadline.durationMillis)))
            s.evidence.policies().keys.forEach { key ->
                val input = s.input.copy(context = s.input.context.copy(policies = s.input.context.policies - key))
                assertEquals(listOf("owner_observed_origin_mismatch"), s.faults(s.actual, parent, candidateInput = input))
            }
            assertEquals(listOf("owner_observed_origin_mismatch"), s.faults(s.actual, parent,
                candidateInput = s.input.copy(context = s.input.context.copy(fullProject = true))))
            val otherModel = f.importObservedUnit(s.capture, s.policy, ObservedExportInvocation(OwnedApplicationDeadline.afterMillis(120_000)))
            assertEquals(s.model.snapshot, otherModel.snapshot)
            assertEquals(listOf("owner_observed_origin_mismatch"), s.faults(s.actual, parent, candidateModel = otherModel))
        }
    }

    @Test fun observedBranchAddsOnlyTheFixedGapAndDoesNotRelaxExistingRawDiagnosticReasons() {
        OwnerModelFixtures().use { f ->
            val s = Shape(f)
            for (reason in listOf("compiler_model_live_api_diagnostic_failure", "unknown-future-gap",
                    "unrelated:$CONFIGURATION_INPUT_CLOSURE_UNPROVEN", CONFIGURATION_INPUT_CLOSURE_UNPROVEN)) {
                val child = s.actual.copy(reasons = mandatory + reason)
                assertEquals(listOf("owner_produced_compiler_diagnostic_unrecognized"), s.faults(child, s.project(child)))
            }
            val raw = s.actual.copy(rawDiagnostics = listOf(RawDiagnostic("SYNTHETIC unmapped compiler error", "error")))
            assertEquals(listOf("owner_produced_compiler_diagnostic_unrecognized"), s.faults(raw, s.project(raw)))
        }
    }

    @Test fun perfectTaintStillCannotWashFailedStatusOrMissingQualifiedAndCertificateCoverage() {
        OwnerModelFixtures().use { f ->
            val s = Shape(f); val parent = s.project()
            assertEquals(listOf("owner_produced_compiler_return_not_accepted"), s.faults(s.actual.copy(status = "failed"), parent))
            val row = s.actual.coverage.first()
            val variants = listOf(s.actual.coverage.drop(1), listOf(row.copy(qualified = true)) + s.actual.coverage.drop(1),
                listOf(row.copy(certificateId = "invented-complete")) + s.actual.coverage.drop(1),
                listOf(row.copy(state = "passed")) + s.actual.coverage.drop(1))
            variants.forEach { rows ->
                assertEquals(listOf("owner_produced_compiler_coverage_unrecognized"), s.faults(s.actual.copy(coverage = rows), parent))
            }
        }
    }
}

package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import kotlinx.serialization.encodeToString
import kotlin.test.*

/**
 * Real SnapshotStore captures/importer validation with explicitly SYNTHETIC ordinary artifacts
 * and observed callbacks. No Gradle/compiler/native process or produced-output grant is created.
 * Successful imports below mean only nominal conditional model evidence, never COMPLETE ingress.
 */
class ObservedExportTest {
    private class Import(
        val fixture: ModelFixtures,
        val capture: ModelFixtures.Capture,
        val graph: GradleProjectExport = fixture.graph(capture),
        val invocation: ObservedExportInvocation = ObservedExportInvocation(OwnedApplicationDeadline.afterMillis(120_000)),
    ) {
        val raw = ContractJson.encodeToString(graph).toByteArray()
        val request = fixture.request(capture, graph, raw).copy(executionId = invocation.identity)
        fun permit(
            snapshot: () -> Unit = capture.verify,
            executed: () -> Unit = { check(request.exportDigest == Digests.sha256(raw)) },
            artifact: (ModelArtifact) -> Unit = { check(it == fixture.artifact) },
            observed: (GradleProjectExport) -> Unit = { check(it == graph) },
        ) = ObservedExportPermit(request, invocation, fixture.gradleRuntimeDigest,
            Digests.sha256("SYNTHETIC observed export receipts; no launch or complete closure claim"),
            snapshot, executed, artifact, observed)
        fun import(): ObservedProjectModel {
            assumeOwnerFixtureAnchoredReads(fixture.work)
            return ObservedProjectModelImporter(ExactObservedExportAuthority(permit())).import(request, raw)
        }
    }

    @Test fun observedAuthorityDefaultsToDenyAndItsExactPermitIsSingleUse() {
        val f = ModelFixtures(); val value = Import(f, f.capture())
        assertEquals("observed_export_not_authorized", assertFailsWith<ProjectModelException> {
            ObservedProjectModelImporter().import(value.request, value.raw)
        }.code)
        assumeOwnerFixtureAnchoredReads(f.work)
        val permit = value.permit()
        val importer = ObservedProjectModelImporter(ExactObservedExportAuthority(permit))
        val model = importer.import(value.request, value.raw)
        assertSame(value.invocation, model.invocation)
        assertSame(value.invocation.originalDeadline, model.invocation.originalDeadline)
        assertEquals("observed_export_invocation_mismatch_or_replay", assertFailsWith<ProjectModelException> {
            importer.import(value.request, value.raw)
        }.code)
        assertEquals("observed_export_invocation_mismatch_or_replay", assertFailsWith<ProjectModelException> {
            value.permit()
        }.code)
    }

    @Test fun invocationRequiresTheExactPreallocatedExecutionIdWithoutConsumingAForeignPermit() {
        val f = ModelFixtures(); val value = Import(f, f.capture())
        assertEquals("observed_export_invocation_mismatch_or_replay", assertFailsWith<ProjectModelException> {
            ObservedExportPermit(value.request.copy(executionId = "foreign-invocation"), value.invocation,
                f.gradleRuntimeDigest, Digests.sha256("synthetic observation"), value.capture.verify, {}, {}, {})
        }.code)
        val permit = value.permit()
        val exact = ObservedProjectModelImporter(ExactObservedExportAuthority(permit))
        assertEquals("observed_export_not_authorized", assertFailsWith<ProjectModelException> {
            exact.import(value.request.copy(executionId = "foreign-request"), value.raw)
        }.code)
        // Even a permissive authority cannot rebind the consumed request digest.
        assertEquals("observed_export_ingress_mismatch", assertFailsWith<ProjectModelException> {
            ObservedProjectModelImporter(ObservedExportAuthority { permit })
                .import(value.request.copy(configurationDigest = Digests.sha256("another policy")), value.raw)
        }.code)
        assumeOwnerFixtureAnchoredReads(f.work)
        assertSame(value.invocation, exact.import(value.request, value.raw).invocation)
    }

    @Test fun everyRequiredOwnerCallbackRunsBeforeAndAfterStructuralAndByteValidation() {
        val f = ModelFixtures(); val value = Import(f, f.capture())
        assumeOwnerFixtureAnchoredReads(f.work)
        var snapshots = 0; var executions = 0; var artifacts = 0; var observations = 0
        val permit = value.permit(snapshot = { value.capture.verify(); snapshots++ },
            executed = { executions++ }, artifact = { check(it == f.artifact); artifacts++ },
            observed = { check(it == value.graph); observations++ })
        ObservedProjectModelImporter(ExactObservedExportAuthority(permit)).import(value.request, value.raw)
        assertEquals(2, snapshots); assertEquals(2, executions)
        assertEquals(2, artifacts); assertEquals(2, observations)
    }

    @Test fun aFailureAtAnyRequiredCallbackConsumesTheOneShotInvocationPermanently() {
        val f = ModelFixtures(); val capture = f.capture()
        assumeOwnerFixtureAnchoredReads(f.work)
        for (stage in listOf("snapshot", "execution", "artifact", "observed-inputs")) {
            val value = Import(f, capture)
            val failure = IllegalStateException("SYNTHETIC denied $stage receipt")
            fun checkpoint(actual: String) { if (stage == actual) throw failure }
            val permit = value.permit(snapshot = { checkpoint("snapshot"); capture.verify() },
                executed = { checkpoint("execution") }, artifact = { checkpoint("artifact"); check(it == f.artifact) },
                observed = { checkpoint("observed-inputs"); check(it == value.graph) })
            val importer = ObservedProjectModelImporter(ExactObservedExportAuthority(permit))
            assertSame(failure, assertFailsWith<IllegalStateException> { importer.import(value.request, value.raw) })
            assertEquals("observed_export_invocation_mismatch_or_replay", assertFailsWith<ProjectModelException> {
                importer.import(value.request, value.raw)
            }.code)
        }
    }

    @Test fun observedContextsPermanentlyRetainTheFourRawEvidencePoliciesAndNoCompleteCertificate() {
        val f = ModelFixtures(); val value = Import(f, f.capture()); val model = value.import()
        assertEquals(listOf(observedClosureIssue()), model.issues)
        assertNull(model.jvmProducedGraph, "Ordinary synthetic artifacts do not become a pinned produced graph")
        model.compilationIds.forEach { id ->
            val facts = model.context(id, f.configurationDigest)
            val context = assertNotNull(facts.context)
            assertEquals(ModelCompleteness.UNAVAILABLE, facts.completeness)
            assertNull(facts.certificateId); assertFalse(context.fullProject)
            assertEquals(mapOf("ktchecker.model" to GRADLE_MODEL_EXPORTER_ID,
                OBSERVED_ASSURANCE_POLICY to OBSERVED_EXPORT_PROFILE,
                OBSERVED_MODEL_EVIDENCE_POLICY to model.evidenceIdentity,
                OBSERVED_INVOCATION_POLICY to value.invocation.identity,
                OBSERVED_CLOSURE_POLICY to CONFIGURATION_INPUT_CLOSURE_UNPROVEN), context.policies)
            assertFalse(OBSERVED_PLAN_EVIDENCE_POLICY in context.policies)
            assertFalse("ktchecker.model-certificate" in context.policies)
        }
        val unknown = model.context("not-exported", f.configurationDigest)
        assertEquals(ModelCompleteness.UNSUPPORTED, unknown.completeness)
        assertNull(unknown.context); assertNull(unknown.certificateId)
        assertTrue(observedClosureIssue() in unknown.issues)
        assertEquals(value.invocation.originalDeadline.identity, value.invocation.originalDeadlineIdentity)
        assertEquals(120_000L, value.invocation.originalDeadlineMillis)
        assertEquals("model_configuration_mismatch", assertFailsWith<ProjectModelException> {
            model.context(model.compilationIds.first(), Digests.sha256("replacement policy"))
        }.code)
    }

    @Test fun reportIssuesCannotOmitOrReplaceTheFixedGlobalClosureGap() {
        val f = ModelFixtures(); val capture = f.capture(); val graph = f.graph(capture)
        val spoof = ModelIssue(CONFIGURATION_INPUT_CLOSURE_UNPROVEN, ":lib|jvm|main", "Caller-chosen narrower gap")
        val model = Import(f, capture, graph.copy(issues = listOf(spoof))).import()
        assertTrue(spoof in model.issues)
        assertEquals(1, model.issues.count { it == observedClosureIssue() })
        assertTrue(model.compilationIds.all { observedClosureIssue() in model.context(it, f.configurationDigest).issues })
    }

    @Test fun completeIngressRemainsNominallySeparateAndObservedIdentityCannotAliasItsCertificate() {
        val f = ModelFixtures(); val capture = f.capture(); val observed = Import(f, capture).import()
        val complete = f.import(capture)
        assertFalse(VerifiedProjectModel::class.java.isInstance(observed))
        assertFalse(ObservedProjectModel::class.java.isInstance(complete))
        assertEquals(ModelCompleteness.COMPLETE, complete.context(complete.compilationIds.first(), f.configurationDigest).completeness)
        assertTrue(complete.issues.isEmpty())
        assertNotEquals(complete.identity.modelDigest, observed.identity.modelDigest)
        assertNotEquals(complete.ingressCertificate, observed.evidenceIdentity)
        val second = Import(f, capture).import()
        assertEquals(observed.export, second.export)
        assertNotSame(observed.invocation.originalDeadline, second.invocation.originalDeadline)
        assertNotEquals(observed.evidenceIdentity, second.evidenceIdentity)
        assertNotEquals(observed.identity.modelDigest, second.identity.modelDigest)
    }

    @Test fun permitModelContextAndImpactCollectionsAreDetachedAndImmutable() {
        val f = ModelFixtures(); val capture = f.capture(); val value = Import(f, capture)
        val mutableFiles = capture.snapshot.files.toMutableList()
        val request = value.request.copy(snapshot = value.request.snapshot.copy(files = mutableFiles))
        val permit = ObservedExportPermit(request, value.invocation, f.gradleRuntimeDigest,
            Digests.sha256("SYNTHETIC immutable receipt"), capture.verify, {}, { check(it == f.artifact) }, { check(it == value.graph) })
        mutableFiles.clear()
        assertEquals(capture.snapshot.files, permit.request.snapshot.files)
        assertFailsWith<UnsupportedOperationException> { (permit.request.snapshot.files as MutableList<*>).clear() }
        assumeOwnerFixtureAnchoredReads(f.work)
        val model = ObservedProjectModelImporter(ExactObservedExportAuthority(permit)).import(permit.request, value.raw)
        assertFailsWith<UnsupportedOperationException> { (model.issues as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (model.export.compilations.first().compilerArguments as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (model.compilationIds as MutableList<*>).clear() }
        val context = assertNotNull(model.context(model.compilationIds.first(), f.configurationDigest).context)
        assertFailsWith<UnsupportedOperationException> { (context.policies as MutableMap<*, *>).clear() }
        val impact = ObservedProjectImpactPlanner().impact(null, model)
        assertFailsWith<UnsupportedOperationException> { (impact.obligations as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (impact.obligations.first().reasons as MutableList<*>).clear() }
    }

    @Test fun unchangedObservedExportsStillRequireEveryBeforeAfterCompilationAndSourceSet() {
        val f = ModelFixtures(); val capture = f.capture()
        val before = Import(f, capture).import(); val after = Import(f, capture).import()
        val impact = ObservedProjectImpactPlanner().impact(before, after)
        assertTrue(impact.changedFiles.isEmpty())
        assertEquals(after.compilationIds.sorted(), impact.obligations.map { it.compilationId })
        assertEquals(after.export.sourceSets.map { it.id }.sorted(), impact.affectedSourceSetIds)
        assertTrue(impact.obligations.all { it.state == "unsupported" && CONFIGURATION_INPUT_CLOSURE_UNPROVEN in it.reasons })
        assertTrue(CONFIGURATION_INPUT_CLOSURE_UNPROVEN in impact.reasons)
    }

    @Test fun removedCompilationAndRetargetedStableIdBothRetainTheirPreviousContextObligations() {
        val f = ModelFixtures(); val capture = f.capture(); val graph = f.graph(capture)
        val before = Import(f, capture, graph).import()
        val removedId = ":independent|jvm|main"
        val retargetedId = ":lib|desktop|main"
        val changed = graph.copy(compilations = graph.compilations.filterNot { it.id == removedId }.map {
            if (it.id == retargetedId) it.copy(target = "otherDesktop") else it
        })
        val after = Import(f, capture, changed).import()
        val impact = ObservedProjectImpactPlanner().impact(before, after)
        val removed = impact.obligations.single { it.compilationId == removedId }
        assertEquals("removed_unresolved", removed.state)
        assertTrue("consumer_removed_without_retirement_evidence" in removed.reasons)
        val retargeted = impact.obligations.filter { it.compilationId == retargetedId }
        assertEquals(2, retargeted.size)
        assertEquals(setOf("desktop", "otherDesktop"), retargeted.map { it.context!!.target }.toSet())
        val old = retargeted.single { it.context!!.target == "desktop" }
        assertEquals("removed_unresolved", old.state)
        assertTrue("consumer_context_changed_without_retirement_evidence" in old.reasons)
        assertEquals(before.compilationIds.size + 1, impact.obligations.size)
        assertTrue(impact.obligations.all { CONFIGURATION_INPUT_CLOSURE_UNPROVEN in it.reasons })
    }
}

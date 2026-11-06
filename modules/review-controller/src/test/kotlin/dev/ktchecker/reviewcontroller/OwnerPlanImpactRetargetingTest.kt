package dev.ktchecker.reviewcontroller

import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.*
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Future importer/assembler tests with synthetic owner callbacks/artifacts; no compiler/process launch. */
class OwnerPlanImpactRetargetingTest {
    @TempDir lateinit var temp: Path

    @Test fun ambiguousActualScopesRetainBothHistoricalLabelsWithoutExecutionPromotion() {
        val f = ControllerFixtures(temp.resolve("ambiguous-retarget"))
        val capture = f.capture()
        val graph = ambiguousGraph(f, capture)
        val before = f.import(capture, graph)
        val after = f.import(capture, graph.copy(compilations = graph.compilations.map {
            if (it.id in AMBIGUOUS_IDS) it.copy(target = NEW_SCOPE.target) else it
        }), configuration = NEW_CONFIGURATION)
        assertTrue(before.issues.isEmpty())
        assertTrue(after.issues.isEmpty())
        assertTrue(before.compilation(PRIMARY_ID)!!.sourceFiles.isNotEmpty())
        assertTrue(before.compilation(ALIAS_ID)!!.sourceFiles.isEmpty())
        assertNotEquals(before.compilation(PRIMARY_ID)!!.defaultSourceSetId, before.compilation(ALIAS_ID)!!.defaultSourceSetId)
        assertNotEquals(before.identity.configurationDigest, after.identity.configurationDigest)
        assertNotEquals(before.identity.modelDigest, after.identity.modelDigest)
        // The importer admits distinct default source-set IDs, but RuleContext resolves names.
        // Both actual contexts are unavailable; exported labels must not repair that ambiguity.
        for (model in listOf(before, after)) for (id in AMBIGUOUS_IDS) {
            val context = model.context(id, model.identity.configurationDigest)
            assertEquals(ModelCompleteness.UNSUPPORTED, context.completeness)
            assertNull(context.context)
            assertNull(context.certificateId)
            assertContains(context.issues.map { it.kind }, "compilation_context_unknown")
        }
        val preparations = TestPreparations()
        val assembly = preparations.assembler(f).assemble(f.binding(), f.request(), before, after,
            capture.materialization(after), listOf(f.binding().clientId))
        val impact = assembly.provenance.impact
        assertTrue(impact.changedFiles.isEmpty())
        assertEquals(after.compilationIds.size + AMBIGUOUS_IDS.size, impact.obligations.size)
        assertEquals(impact.obligations.size, assembly.plan.slots.size)
        assertEquals(impact.obligations.size, impact.obligations.map { it.compilationId to it.context }.distinct().size)
        assertEquals(impact.certificateId, assembly.plan.impactCertificateId)
        assertEquals(digest(listOf(before.ingressCertificate, after.ingressCertificate,
            canonical(impact.changedFiles), canonical(impact.obligations))), impact.certificateId)
        assertNotEquals(digest(listOf(before.ingressCertificate, after.ingressCertificate,
            canonical(impact.changedFiles), canonical(impact.obligations.filterNot { it.state == "removed_unresolved" }))), impact.certificateId)
        for (id in AMBIGUOUS_IDS) {
            val obligations = impact.obligations.filter { it.compilationId == id }
            assertEquals(listOf(NEW_SCOPE, OLD_SCOPE), obligations.map { it.context })
            val current = obligations.single { it.context == NEW_SCOPE }
            val historical = obligations.single { it.context == OLD_SCOPE }
            assertEquals("unsupported", current.state)
            assertEquals("removed_unresolved", historical.state)
            assertContains(historical.reasons, "required_context_retargeted_unresolved")
            assertContains(historical.reasons, "consumer_context_changed_without_retirement_evidence")
            assertFalse("required_context_retargeted_unresolved" in current.reasons)
            for ((model, scope, obligation) in listOf(Triple(after, NEW_SCOPE, current), Triple(before, OLD_SCOPE, historical))) {
                assertEquals(model.identity.modelDigest, obligation.modelDigest)
                assertEquals(digest(model.compilation(id)!!.sourceFiles), obligation.compilationFilesDigest)
                assertEquals(after.identity.configurationDigest, obligation.configurationDigest)
                assertNull(obligation.dependencyDigest)
                assertNull(obligation.toolchainDigest)
                assertContains(obligation.reasons, "exported_context_label_only")
                assertContains(obligation.reasons, "compilation_context_unknown")
                val evidence = assembly.provenance.slots.single { it.compilationId == id && it.impactObligation?.context == scope }
                assertSame(model, evidence.sourceModel)
                assertEquals(obligation, evidence.impactObligation)
                assertLabelOnlySlot(assembly, evidence, after, scope)
            }
        }
        assertEquals(assembly.plan.slots.size, preparations.policyRequests.size)
        assertEquals(after.compilationIds.filterNot { it in AMBIGUOUS_IDS }.toSet(), preparations.executionRequests.map { it.compilationId }.toSet())
        assertEquals(after.compilationIds.size - AMBIGUOUS_IDS.size, preparations.executionRequests.size)
        assertTrue(preparations.executionRequests.none { it.compilationId in AMBIGUOUS_IDS })
    }

    @Test fun unchangedAmbiguousScopesWithNewPolicyGetOnlyCurrentUnsupportedEvidence() {
        val f = ControllerFixtures(temp.resolve("ambiguous-current-only"))
        val capture = f.capture()
        val graph = ambiguousGraph(f, capture)
        val before = f.import(capture, graph)
        val after = f.import(capture, graph, configuration = NEW_CONFIGURATION)
        assertTrue(before.issues.isEmpty())
        assertTrue(after.issues.isEmpty())
        assertNotEquals(before.identity.configurationDigest, after.identity.configurationDigest)
        val preparations = TestPreparations()
        val assembly = preparations.assembler(f).assemble(f.binding(), f.request(), before, after,
            capture.materialization(after), listOf(f.binding().clientId))
        val impact = assembly.provenance.impact
        assertTrue(impact.changedFiles.isEmpty())
        assertContains(impact.reasons, "owner_configuration_changed")
        assertEquals(after.compilationIds.sorted(), impact.obligations.map { it.compilationId })
        assertEquals(after.compilationIds.size, assembly.plan.slots.size)
        assertTrue(impact.obligations.none { it.state == "removed_unresolved" || "required_context_retargeted_unresolved" in it.reasons })
        assertTrue(assembly.plan.slots.none { it.slotId.startsWith("retired-context:") })
        for (id in AMBIGUOUS_IDS) {
            val obligation = impact.obligations.single { it.compilationId == id }
            assertEquals(OLD_SCOPE, obligation.context)
            assertEquals("unsupported", obligation.state)
            assertEquals(after.identity.configurationDigest, obligation.configurationDigest)
            assertEquals(after.identity.modelDigest, obligation.modelDigest)
            assertNull(obligation.dependencyDigest)
            assertNull(obligation.toolchainDigest)
            assertContains(obligation.reasons, "exported_context_label_only")
            assertContains(obligation.reasons, "compilation_context_unknown")
            val evidence = assembly.provenance.slots.single { it.compilationId == id }
            assertSame(after, evidence.sourceModel)
            assertEquals(obligation, evidence.impactObligation)
            assertLabelOnlySlot(assembly, evidence, after, OLD_SCOPE)
        }
        assertEquals(assembly.plan.slots.size, preparations.policyRequests.size)
        assertEquals(after.compilationIds.size - AMBIGUOUS_IDS.size, preparations.executionRequests.size)
        assertTrue(preparations.executionRequests.none { it.compilationId in AMBIGUOUS_IDS })
    }

    private fun assertLabelOnlySlot(assembly: AssembledReviewPlan, evidence: SlotAssemblyEvidence,
        after: VerifiedProjectModel, scope: RuleContext) {
        val verified = assertNotNull(evidence.verifiedContext)
        assertNull(verified.context)
        assertNull(verified.certificateId)
        assertEquals(ModelCompleteness.UNSUPPORTED, verified.completeness)
        val slot = assembly.plan.slots.single { it.slotId == evidence.slotId }
        assertEquals("unsupported", slot.state)
        assertNull(slot.identity)
        assertEquals(scope, slot.input.context.scope)
        assertEquals(after.snapshot, slot.input.snapshot)
        assertEquals(after.identity.modelDigest, slot.input.context.modelDigest)
        assertEquals(after.identity.configurationDigest, slot.input.context.configurationDigest)
        assertFalse(slot.input.context.fullProject)
        assertEquals(emptyList(), slot.input.context.compilationFiles)
        assertTrue(slot.input.context.classpath.isEmpty())
        assertTrue(slot.input.context.compilerArguments.isEmpty())
        assertNull(slot.input.context.dependencyDigest)
        assertNull(slot.input.context.toolchainDigest)
        assertNull(slot.input.execution)
        assertNull(evidence.executionIdentity)
        assertFalse(PLAN_EXECUTION_POLICY_KEY in slot.input.context.policies)
        assertEquals(assembly.provenance.inventory.ruleIds, slot.input.requiredRuleIds)
    }

    private fun ambiguousGraph(f: ControllerFixtures, capture: ControllerFixtures.Capture): GradleProjectExport {
        val graph = f.graph(capture)
        val primary = graph.compilations.single { it.id == PRIMARY_ID }
        val sourceSet = graph.sourceSets.single { it.id == primary.defaultSourceSetId }
        val aliasSet = sourceSet.copy(id = ":independent|same-name-alias", dependsOn = emptyList(),
            sourceRoots = emptyList(), resourceRoots = emptyList(), sources = emptyList(), resources = emptyList())
        val aliasCompilation = primary.copy(id = ALIAS_ID, defaultSourceSetId = aliasSet.id,
            sourceSetIds = listOf(aliasSet.id), sourceFiles = emptyList(), compileTaskPath = ":independent:compile-alias-main",
            dependencyConfiguration = "aliasMainCompileClasspath")
        return graph.copy(sourceSets = graph.sourceSets + aliasSet, compilations = graph.compilations + aliasCompilation)
    }

    companion object {
        private const val PRIMARY_ID = ":independent|jvm|main"
        private const val ALIAS_ID = ":independent|same-name-alias|main"
        private val AMBIGUOUS_IDS = listOf(PRIMARY_ID, ALIAS_ID)
        private val OLD_SCOPE = RuleContext("jvm", "jvmMain", ":independent", "main")
        private val NEW_SCOPE = OLD_SCOPE.copy(target = "otherJvm")
        private val NEW_CONFIGURATION = Digests.sha256("synthetic-retarget-new-owner-policy")
    }
}

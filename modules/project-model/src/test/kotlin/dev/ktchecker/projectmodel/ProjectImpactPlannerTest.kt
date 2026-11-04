package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.*
import dev.ktchecker.snapshot.*
import kotlinx.serialization.encodeToString
import kotlin.test.*

class ProjectImpactPlannerTest {
    @Test fun initialCaptureRequiresEveryActualTargetAndCompilation(): Unit {
        val fixture = ModelFixtures(); val capture = fixture.capture(); val model = fixture.import(capture)
        val impact = ProjectImpactPlanner().impact(null, model)
        assertEquals(model.compilationIds.sorted(), impact.obligations.map { it.compilationId })
        assertTrue(impact.obligations.all { it.state == "pending" && it.context != null })
        assertContains(impact.reasons, "initial_model_requires_all_consumers")
        assertTrue(Digests.valid(impact.certificateId))
    }

    @Test fun commonAndIntermediateBodyChangesReachBothJvmTargetsFriendsAndReverseConsumers(): Unit {
        for (changedPath in listOf("lib/src/commonMain/kotlin/Common.kt", "lib/src/sharedMain/kotlin/Shared.kt", "lib/src/commonMain/resources/message.txt")) {
            val fixture = ModelFixtures(); val oldCapture = fixture.capture(); val before = fixture.import(oldCapture)
            val nextFiles = fixture.files().toMutableMap().apply { this[changedPath] = getValue(changedPath) + "\n// producer edit\n" }
            val newCapture = fixture.capture(nextFiles); val after = fixture.import(newCapture)
            val impact = ProjectImpactPlanner().impact(before, after)
            assertEquals(listOf(changedPath), impact.changedFiles)
            assertEquals(setOf(":lib|jvm|main", ":lib|desktop|main", ":lib|jvm|test", ":feature|jvm|main", ":app|jvm|main"),
                impact.obligations.map { it.compilationId }.toSet())
            assertFalse(impact.obligations.any { it.compilationId == ":independent|jvm|main" })
            assertTrue(impact.obligations.all { it.state == "pending" })
            assertTrue(impact.reasons.isEmpty(), "No unsupported body-only optimization is used; all reverse graph consumers are still reached")
        }
    }

    @Test fun identicalBytesButMovedSourceSetMembershipStillInvalidatesWholeGraph(): Unit {
        val fixture = ModelFixtures(); val capture = fixture.capture()
        val original = fixture.graph(capture).let { graph -> graph.copy(sourceSets = graph.sourceSets.map {
            if (it.moduleId == ":lib") it.copy(sourceRoots = listOf("lib/src")) else it
        }) }
        val before = fixture.import(capture, original)
        val source = original.sourceSets.first { it.name == "commonMain" }.sources.single()
        val changed = original.copy(sourceSets = original.sourceSets.map {
            when (it.id) {
                ":lib|commonMain" -> it.copy(sources = emptyList())
                ":lib|sharedMain" -> it.copy(sources = (it.sources + source).sortedBy { file -> file.path })
                else -> it
            }
        })
        val after = fixture.import(capture, changed)
        assertTrue(after.issues.isEmpty())
        val impact = ProjectImpactPlanner().impact(before, after)
        assertTrue(impact.changedFiles.isEmpty())
        assertContains(impact.reasons, "model_topology_membership_or_arguments_changed")
        assertEquals(before.compilationIds.sorted(), impact.obligations.map { it.compilationId })
    }

    @Test fun identicalBytesButAuxiliaryOrGeneratedMembershipAndPropertiesStillWiden(): Unit {
        val fixture = ModelFixtures(); val capture = fixture.capture(); val graph = fixture.graph(capture)
        val output = graph.sourceSets.first { it.id == ":lib|commonMain" }.sources.single()
        val generated = GeneratedSourceInput(":fixtureGenerate", listOf(":lib|commonMain"), emptyList(), listOf(output),
            listOf("lib/src/commonMain/kotlin"), "captured", mapOf("flag" to "\"one\""))
        val original = graph.copy(generatedInputs = listOf(generated))
        val before = fixture.import(capture, original)
        val variants = listOf(
            original.copy(compilations = original.compilations.map {
                if (it.id == ":lib|jvm|main") it.copy(auxiliaryInputFiles = listOf(output)) else it
            }),
            original.copy(generatedInputs = listOf(generated.copy(inputFiles = listOf(output)))),
            original.copy(generatedInputs = listOf(generated.copy(inputProperties = mapOf("flag" to "\"two\"")))),
        )
        variants.forEach { next ->
            val impact = ProjectImpactPlanner().impact(before, fixture.import(capture, next))
            assertTrue(impact.changedFiles.isEmpty())
            assertContains(impact.reasons, "model_topology_membership_or_arguments_changed")
            assertEquals(before.compilationIds.sorted(), impact.obligations.map { it.compilationId })
        }
    }

    @Test fun identityOnlyOwnerPolicyToolchainRuntimeAndConfigurationChangesCannotYieldEmptyObligations(): Unit {
        val fixture = ModelFixtures(); val capture = fixture.capture(); val graph = fixture.graph(capture); val before = fixture.import(capture, graph)
        val policyChanged = fixture.import(capture, graph, configuration = Digests.sha256("owner-policy-revision-two"))
        val runtimeChanged = fixture.import(capture, graph, runtime = Digests.sha256("other-selected-gradle-runtime"))
        val toolchainChanged = fixture.import(capture, graph.copy(compilations = graph.compilations.map {
            it.copy(toolchain = it.toolchain!!.copy(compilerVersion = "next-owner-selected-compiler"))
        }))
        val argumentsChanged = fixture.import(capture, graph.copy(compilations = graph.compilations.map {
            it.copy(compilerArguments = it.compilerArguments + "-progressive")
        }))
        for ((after, reason) in listOf(policyChanged to "owner_configuration_changed", runtimeChanged to "gradle_runtime_identity_changed",
            toolchainChanged to "compiler_runtime_or_plugin_identity_changed", argumentsChanged to "model_topology_membership_or_arguments_changed")) {
            val impact = ProjectImpactPlanner().impact(before, after)
            assertTrue(impact.changedFiles.isEmpty()); assertContains(impact.reasons, reason)
            assertEquals(before.compilationIds.sorted(), impact.obligations.map { it.compilationId })
        }
    }

    @Test fun sourceScopeRootsAndCaptureProvenanceChangesWidenEvenWhenFileMapIsIdentical(): Unit {
        val fixture = ModelFixtures(); val capture = fixture.capture(); val graph = fixture.graph(capture); val before = fixture.import(capture)
        val variants = listOf(
            capture.snapshot.copy(declaredRoots = listOf("different-registered-root")),
            capture.snapshot.copy(captureKind = "coordinated_capture"),
            capture.snapshot.copy(unknownInputs = listOf("unmodeled external source")),
            capture.snapshot.copy(scopeExclusions = listOf("owner bounded source omission")),
        )
        variants.forEach { snapshot ->
            val after = fixture.import(capture.copy(snapshot = snapshot), graph)
            val impact = ProjectImpactPlanner().impact(before, after)
            assertTrue(impact.changedFiles.isEmpty()); assertContains(impact.reasons, "capture_scope_roots_or_provenance_changed")
            assertEquals(before.compilationIds.sorted(), impact.obligations.map { it.compilationId })
        }
    }

    @Test fun oldAndNewRefinementAndConsumerEdgesAreUnionedAndRemovedCompilationIsStillAnObligation(): Unit {
        val fixture = ModelFixtures(); val capture = fixture.capture(); val graph = fixture.graph(capture); val before = fixture.import(capture)
        val removed = ":lib|desktop|main"
        val afterGraph = graph.copy(compilations = graph.compilations.filterNot { it.id == removed }.map {
            if (it.moduleId == ":feature") it.copy(projectDependencies = emptyList()) else it
        })
        val after = fixture.import(capture, afterGraph)
        val impact = ProjectImpactPlanner().impact(before, after)
        val removedSlot = impact.obligations.single { it.compilationId == removed }
        assertEquals("removed_unresolved", removedSlot.state); assertEquals("desktop", removedSlot.context!!.target)
        assertEquals("desktopMain", removedSlot.context!!.sourceSet)
        assertContains(removedSlot.reasons, "consumer_removed_without_retirement_evidence")
        assertTrue(impact.obligations.any { it.compilationId == ":feature|jvm|main" })
        assertTrue(impact.obligations.any { it.compilationId == ":app|jvm|main" })
    }

    @Test fun unknownVariantGenerationAndUnownedInputsForceAllObligationsToRemainVisible(): Unit {
        val fixture = ModelFixtures(); val capture = fixture.capture(); val graph = fixture.graph(capture); val before = fixture.import(capture)
        val unknownVariant = fixture.import(capture, graph.copy(compilations = graph.compilations.map {
            it.copy(projectDependencies = it.projectDependencies.map { dependency -> dependency.copy(compilationId = null) })
        }))
        val variantImpact = ProjectImpactPlanner().impact(before, unknownVariant)
        assertEquals(before.compilationIds.sorted(), variantImpact.obligations.map { it.compilationId })
        assertTrue(variantImpact.obligations.all { it.state == "unsupported" })
        assertContains(variantImpact.reasons, "unknown_producer_variant_requires_full_relevant_graph")
        val newFiles = fixture.files() + ("opaque-generator-input.bin" to "actual captured unknown input")
        val next = fixture.capture(newFiles); val unknownInput = fixture.import(next)
        val inputImpact = ProjectImpactPlanner().impact(before, unknownInput)
        assertContains(inputImpact.reasons, "changed_input_membership_unknown")
        assertEquals(before.compilationIds.sorted(), inputImpact.obligations.map { it.compilationId })
        val generated = fixture.import(next, fixture.graph(next).copy(generatedInputs = listOf(
            GeneratedSourceInput(":generate", listOf(":lib|commonMain"), emptyList(), emptyList(), listOf("generated"), "pending"))))
        assertTrue(generated.issues.any { it.kind == "generated_input_unavailable" })
        assertTrue(ProjectImpactPlanner().impact(before, generated).obligations.all { it.state == "unsupported" })
    }

    @Test fun identicalSnapshotAndActualModelHaveNoInventedChangesOrObligations(): Unit {
        val fixture = ModelFixtures(); val capture = fixture.capture(); val before = fixture.import(capture); val after = fixture.import(capture)
        val impact = ProjectImpactPlanner().impact(before, after)
        assertTrue(impact.changedFiles.isEmpty()); assertTrue(impact.affectedSourceSetIds.isEmpty())
        assertTrue(impact.obligations.isEmpty()); assertTrue(impact.reasons.isEmpty())
        assertFailsWith<UnsupportedOperationException> { (impact.obligations as MutableList<*>).clear() }
    }

    @Test fun impactCannotSpliceAnotherWorktreeOrLineage(): Unit {
        val fixture = ModelFixtures(); val capture = fixture.capture(); val graph = fixture.graph(capture); val before = fixture.import(capture)
        val raw = ContractJson.encodeToString(graph).toByteArray()
        val request = fixture.request(capture, graph, raw).copy(namespace = fixture.namespace.copy(lineageId = "unrelated-history"))
        val after = ProjectModelImporter(ExactModelIngressAuthority(fixture.permit(capture, request))).import(request, raw)
        assertEquals("impact_namespace_mismatch", assertFailsWith<ProjectModelException> { ProjectImpactPlanner().impact(before, after) }.code)
    }
}

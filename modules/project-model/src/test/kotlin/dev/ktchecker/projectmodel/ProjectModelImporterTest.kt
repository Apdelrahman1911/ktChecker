package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.*
import dev.ktchecker.snapshot.*
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ProjectModelImporterTest {
    @Test fun authorityDefaultsToDenyAndReceiptIsExactAndSingleUse(): Unit {
        val fixture = ModelFixtures(); val capture = fixture.capture(); val graph = fixture.graph(capture)
        val raw = ContractJson.encodeToString(graph).toByteArray(); val request = fixture.request(capture, graph, raw)
        assertEquals("model_ingress_not_authorized", assertFailsWith<ProjectModelException> { ProjectModelImporter().import(request, raw) }.code)
        assumeOwnerFixtureAnchoredReads(fixture.work)
        val importer = ProjectModelImporter(ExactModelIngressAuthority(fixture.permit(capture, request)))
        assertEquals(ModelCompleteness.COMPLETE, importer.import(request, raw).context(":lib|jvm|main", fixture.configurationDigest).completeness)
        assertEquals("model_ingress_mismatch_or_replay", assertFailsWith<ProjectModelException> { importer.import(request, raw) }.code)
        assertEquals("model_ingress_not_authorized", assertFailsWith<ProjectModelException> {
            ProjectModelImporter(ExactModelIngressAuthority(fixture.permit(capture, request))).import(request.copy(executionId = "other-execution"), raw)
        }.code)
    }

    @Test fun successfulIngressPreservesActualSharedSourceSetsTargetsAndOrderedClasspath(): Unit {
        val fixture = ModelFixtures(); val capture = fixture.capture(); val model = fixture.import(capture)
        val jvm = model.context(":lib|jvm|main", fixture.configurationDigest)
        val desktop = model.context(":lib|desktop|main", fixture.configurationDigest)
        assertEquals(ModelCompleteness.COMPLETE, jvm.completeness); assertEquals(ModelCompleteness.COMPLETE, desktop.completeness)
        assertEquals("jvmMain", jvm.context!!.scope.sourceSet); assertEquals("desktopMain", desktop.context!!.scope.sourceSet)
        assertNotEquals(jvm.certificateId, desktop.certificateId)
        assertTrue(jvm.context!!.compilationFiles!!.contains("lib/src/sharedMain/kotlin/Shared.kt"))
        assertTrue(desktop.context!!.compilationFiles!!.contains("lib/src/sharedMain/kotlin/Shared.kt"))
        assertEquals(listOf(fixture.artifact.path), jvm.context!!.classpath.map { it.path })
        assertTrue(model.graphIssues(model.compilationIds).isEmpty())
        assertNull(model.context(RuleContext("jvm", "sharedMain", ":lib", "main"), fixture.configurationDigest).context)
        assertEquals(ModelCompleteness.UNSUPPORTED, model.context("removed", fixture.configurationDigest).completeness)
        assertEquals("model_configuration_mismatch", assertFailsWith<ProjectModelException> { model.context(":lib|jvm|main", Digests.sha256("retargeted")) }.code)
    }

    @Test fun originCallbacksMustAllSucceedAndPartialRootProofCannotCreateCapability(): Unit {
        val fixture = ModelFixtures(); val capture = fixture.capture(); val graph = fixture.graph(capture)
        val raw = ContractJson.encodeToString(graph).toByteArray(); val request = fixture.request(capture, graph, raw)
        var originChecks = 0; var inputChecks = 0
        val permit = fixture.permit(capture, request, verifyExecution = { originChecks++ }, verifyInputs = { inputChecks++ })
        assumeOwnerFixtureAnchoredReads(fixture.work)
        ProjectModelImporter(ExactModelIngressAuthority(permit)).import(request, raw)
        assertEquals(2, originChecks); assertEquals(2, inputChecks)
        val partialRequest = request.copy(executionId = "partial-root-attempt")
        val partial = fixture.permit(capture, partialRequest, verifyInputs = { throw IllegalStateException("Owner complete-model capture proof missing") })
        assertFailsWith<IllegalStateException> { ProjectModelImporter(ExactModelIngressAuthority(partial)).import(partialRequest, raw) }
    }

    @Test fun rawDigestUtf8AndUnknownSchemaAreNotAnEmptyGraph(): Unit {
        val fixture = ModelFixtures(); val capture = fixture.capture(); val graph = fixture.graph(capture)
        val raw = ContractJson.encodeToString(graph).toByteArray(); val request = fixture.request(capture, graph, raw)
        assertEquals("model_export_digest_or_size", assertFailsWith<ProjectModelException> { ProjectModelImporter().import(request, raw + 1) }.code)
        for ((invalid, code) in listOf(byteArrayOf(0xff.toByte()) to "model_export_utf8", "{\"unknown\":true}".toByteArray() to "model_export_schema")) {
            val selected = fixture.request(capture, graph, invalid)
            assertEquals(code, assertFailsWith<ProjectModelException> {
                ProjectModelImporter(ExactModelIngressAuthority(fixture.permit(capture, selected))).import(selected, invalid)
            }.code)
        }
    }

    @Test fun wrongProducerOwnerAndVariantAndAssociatedReferencesAreRejected(): Unit {
        val fixture = ModelFixtures(); val capture = fixture.capture(); val graph = fixture.graph(capture)
        val consumer = graph.compilations.first { it.moduleId == ":feature" }
        for ((change, code) in listOf(
            consumer.copy(projectDependencies = listOf(consumer.projectDependencies.single().copy(compilationId = ":independent|jvm|main"))) to "model_producer_variant_invalid",
            consumer.copy(projectDependencies = listOf(consumer.projectDependencies.single().copy(selectedVariant = "otherVariant"))) to "model_producer_variant_invalid",
            consumer.copy(associatedCompilationIds = listOf("absent")) to "model_reference_invalid",
            consumer.copy(associatedCompilationIds = listOf(":lib|jvm|main")) to "model_reference_invalid",
            consumer.copy(associatedCompilationIds = listOf(consumer.id)) to "model_reference_invalid",
        )) {
            assertEquals(code, assertOwnerFixtureModelFailure(fixture.work) { fixture.import(capture, graph.copy(compilations = graph.compilations.map { if (it.id == change.id) change else it })) }.code)
        }
    }

    @Test fun maliciousFileMembershipTraversalRefinementAndIssueScopesAreRejected(): Unit {
        val fixture = ModelFixtures(); val capture = fixture.capture(); val graph = fixture.graph(capture)
        val common = graph.sourceSets.first { it.name == "commonMain" }
        val source = common.sources.single()
        val cases = listOf(
            graph.copy(sourceSets = graph.sourceSets.map { if (it.id == common.id) it.copy(sourceRoots = listOf("elsewhere")) else it }) to "model_membership_invalid",
            // Even a claimed whole-project root cannot make a traversal path a logical captured input.
            graph.copy(sourceSets = graph.sourceSets.map { if (it.id == common.id) it.copy(sourceRoots = listOf(""), sources = listOf(source.copy(path = "../escape.kt"))) else it }) to "model_invalid",
            graph.copy(sourceSets = graph.sourceSets.map { if (it.id == common.id) it.copy(dependsOn = listOf(":feature|jvmMain")) else it }) to "model_reference_invalid",
            graph.copy(sourceSets = graph.sourceSets.map { if (it.id == common.id) it.copy(dependsOn = listOf(":lib|sharedMain")) else it }) to "model_refinement_cycle",
            graph.copy(issues = listOf(ModelIssue("model_gap", "concealed-unknown-scope", "Cannot hide this gap"))) to "model_issue_scope_invalid",
        )
        cases.forEach { (invalid, code) -> assertEquals(code, assertOwnerFixtureModelFailure(fixture.work) { fixture.import(capture, invalid) }.code) }
    }

    @Test fun unavailableCompilerArtifactAndMissingConfigurationNeverGiveFullProject(): Unit {
        val fixture = ModelFixtures(); val capture = fixture.capture(); val graph = fixture.graph(capture)
        val unknown = graph.copy(compilations = graph.compilations.map { it.copy(toolchain = null) })
        val model = fixture.import(capture, unknown)
        assertEquals(ModelCompleteness.UNAVAILABLE, model.context(":lib|jvm|main", fixture.configurationDigest).completeness)
        assertFalse(model.context(":lib|jvm|main", fixture.configurationDigest).context!!.fullProject)
        assertNull(model.context(":lib|jvm|main", fixture.configurationDigest).certificateId)
        assertTrue(model.graphIssues(model.compilationIds).isEmpty(), "Graph-only evidence is distinct from compiler completeness")
        val missingConfig = fixture.import(capture, graph.copy(configurationInputs = graph.configurationInputs.filterNot { it.path == "feature/build.gradle.kts" }))
        assertTrue(missingConfig.issues.any { it.kind == "build_configuration_missing" })
        assertTrue(missingConfig.graphIssues(listOf(":lib|jvm|main")).isNotEmpty(), "Another module's unknown graph must not be scoped away")
        val artifactUnknown = fixture.import(capture, graph.copy(compilations = graph.compilations.map { it.copy(classpath = listOf(fixture.artifact.copy(digest = null, size = null))) }))
        assertTrue(artifactUnknown.issues.any { it.kind == "artifact_unavailable" })
        assertFalse(artifactUnknown.context(":lib|jvm|main", fixture.configurationDigest).context!!.fullProject)
    }

    @Test fun namespaceSnapshotExclusionsUnknownCaptureAndEmptyModelFailClosed(): Unit {
        val fixture = ModelFixtures(); val capture = fixture.capture(scope = SnapshotScope(unknownInputs = listOf("external generation"),
            exclusions = listOf(CaptureExclusion("project", "excluded-source", "Not captured"))))
        val model = fixture.import(capture)
        assertTrue(model.issues.any { it.kind == "snapshot_scope_excluded" }); assertTrue(model.issues.any { it.kind == "snapshot_input_unknown" })
        val bestEffort = capture.copy(snapshot = capture.snapshot.copy(captureKind = "best_effort"))
        assertTrue(fixture.import(bestEffort).issues.any { it.kind == "snapshot_capture_unverified" })
        val graph = fixture.graph(capture); val raw = ContractJson.encodeToString(graph).toByteArray()
        val request = fixture.request(capture, graph, raw).copy(namespace = fixture.namespace.copy(ownerId = "another-owner"))
        assertEquals("model_namespace_mismatch", assertFailsWith<ProjectModelException> { ProjectModelImporter().import(request, raw) }.code)
        val empty = fixture.import(capture, graph.copy(compilations = emptyList()))
        assertTrue(empty.issues.any { it.kind == "compilation_model_empty" })
        assertTrue(empty.graphIssues(emptyList()).isNotEmpty())
    }

    @Test fun importedGraphAndPermitDetachAndFreezeEveryNestedCollection(): Unit {
        val fixture = ModelFixtures(); val capture = fixture.capture(); val graph = fixture.graph(capture)
        val callerFiles = capture.snapshot.files.toMutableList()
        val request = fixture.request(capture, graph).copy(snapshot = capture.snapshot.copy(files = callerFiles))
        val permit = fixture.permit(capture, request)
        callerFiles.clear()
        assertEquals(capture.snapshot.files, permit.request.snapshot.files)
        assertFailsWith<UnsupportedOperationException> { (permit.request.snapshot.files as MutableList<*>).clear() }
        val model = fixture.import(capture)
        assertFailsWith<UnsupportedOperationException> { (model.export.sourceSets.first().sourceRoots as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (model.export.compilations.first().toolchain!!.compilerArtifacts as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (model.export.compilations.first { it.moduleId == ":feature" }.projectDependencies.single().variantAttributes as MutableMap<*, *>).clear() }
        assertFailsWith<UnsupportedOperationException> { (model.export.compilations.first { it.name == "test" }.associatedCompilationIds as MutableList<*>).clear() }
        val output = graph.sourceSets.first { it.id == ":lib|commonMain" }.sources.single()
        val properties = mutableMapOf("fixture-property" to "\"pinned-value\"")
        val generated = fixture.import(capture, graph.copy(generatedInputs = listOf(GeneratedSourceInput(
            ":fixtureGenerate", listOf(":lib|commonMain"), emptyList(), listOf(output), listOf("lib/src/commonMain/kotlin"), "captured", properties))))
        properties.clear()
        assertEquals(mapOf("fixture-property" to "\"pinned-value\""), generated.export.generatedInputs.single().inputProperties)
        assertFailsWith<UnsupportedOperationException> { (generated.export.generatedInputs.single().inputProperties as MutableMap<*, *>).clear() }
        assertFailsWith<UnsupportedOperationException> { (generated.export.generatedInputs.single().outputFiles as MutableList<*>).clear() }
    }

    @Test fun artifactIdentityIncludesExactSizeAndNoFollowAncestors(): Unit {
        val fixture = ModelFixtures(); val capture = fixture.capture(); val graph = fixture.graph(capture)
        assumeOwnerFixtureAnchoredReads(fixture.work)
        fun attempt(selected: ModelArtifact): ProjectModelException {
            val changed = graph.copy(buildscriptArtifacts = listOf(selected), compilations = graph.compilations.map {
                it.copy(classpath = listOf(selected), toolchain = ModelToolchain("jvm", "2.4.21", listOf(selected), listOf(selected)))
            })
            val raw = ContractJson.encodeToString(changed).toByteArray(); val request = fixture.request(capture, changed, raw)
            val permit = ModelIngressPermit(request, fixture.gradleRuntimeDigest, capture.verify, {}, {}, {})
            return assertFailsWith { ProjectModelImporter(ExactModelIngressAuthority(permit)).import(request, raw) }
        }
        assertEquals("model_invalid", attempt(fixture.artifact.copy(size = fixture.artifact.size!! + 1)).code)
        assertEquals("model_artifact_changed", attempt(fixture.artifact.copy(digest = Digests.sha256("changed-artifact"))).code)
        val link = fixture.work.resolve("artifact-alias")
        createOwnerFixtureSymbolicLink(link, Path.of(fixture.artifact.path).parent)
        assertEquals("model_file_unsafe", attempt(fixture.artifact.copy(path = link.resolve("compiler.jar").toString())).code)
    }

    @Test fun classpathAndFriendAndAuxiliaryCategoriesHaveSeparateIdentities(): Unit {
        val fixture = ModelFixtures(); val capture = fixture.capture(); val graph = fixture.graph(capture)
        val original = fixture.import(capture, graph)
        val changed = fixture.import(capture, graph.copy(compilations = graph.compilations.map {
            if (it.id == ":lib|jvm|main") it.copy(classpath = emptyList(), friendArtifacts = listOf(fixture.artifact)) else it
        }))
        assertNotEquals(original.identity.dependencyDigest, changed.identity.dependencyDigest)
        assertNotEquals(original.context(":lib|jvm|main", fixture.configurationDigest).context!!.dependencyDigest,
            changed.context(":lib|jvm|main", fixture.configurationDigest).context!!.dependencyDigest)
    }
}

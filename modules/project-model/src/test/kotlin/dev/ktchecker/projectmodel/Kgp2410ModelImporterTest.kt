package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import kotlinx.serialization.encodeToString
import kotlin.test.*

/** Synthetic owner receipts and dummy artifact bytes ONLY. No actual Gradle export,
 * compiler, model task, provider, configuration-read closure or FIR extraction runs here. */
class Kgp2410ModelImporterTest {
    @Test fun explicitRouteStillDefaultsToDenyAfterCheckingItsOwnResourcePin() {
        val f = Fixture(); val (request, raw) = f.request()
        failure("model_ingress_not_authorized") { ProjectModelImporter().importKgp2410(request, raw) }
    }

    @Test fun defaultAndObservedEntriesNeverAutoSelectTheExperimentalResource() {
        val f = Fixture(); val (request, raw) = f.request()
        var strictCalls = 0; var observedCalls = 0
        failure("model_exporter_unavailable") {
            ProjectModelImporter(ModelIngressAuthority { strictCalls++; null }).import(request, raw)
        }
        failure("model_exporter_unavailable") {
            ObservedProjectModelImporter(ObservedExportAuthority { observedCalls++; null }).import(request, raw)
        }
        assertEquals(0, strictCalls); assertEquals(0, observedCalls)
    }

    @Test fun explicitEntryRejectsOldUnknownAndMutatedResourcePinsBeforeAuthority() {
        val f = Fixture(); val (request, raw) = f.request(); var calls = 0
        val importer = ProjectModelImporter(ModelIngressAuthority { calls++; null })
        for (digest in listOf(GradleModelExporter.scriptDigest(), Digests.sha256("unregistered script"))) {
            failure("model_exporter_unavailable") { importer.importKgp2410(request.copy(exporterDigest = digest), raw) }
        }
        failure("model_export_digest_or_size") { importer.importKgp2410(request, raw + 1) }
        assertEquals(0, calls)
    }

    @Test fun exactNewPinCannotRelabelAnOldUnknownOrMixedSchemaReport() {
        val f = Fixture()
        for (graph in listOf(f.graph.copy(exporterId = GRADLE_MODEL_EXPORTER_ID),
            f.graph.copy(exporterId = "unregistered"), f.graph.copy(schemaVersion = 2))) {
            failure("model_export_schema") { f.import(graph) }
        }
    }

    @Test fun oldResourceAndOldExporterIdKeepTheOriginalStrictPathAndPolicy() {
        val f = Fixture()
        val graph = f.graph.copy(exporterId = GRADLE_MODEL_EXPORTER_ID,
            compilations = f.graph.compilations.map { it.copy(toolchain = it.toolchain!!.copy(compilerVersion = "2.4.21")) })
        val (request, raw) = f.request(graph, legacy = true)
        val model = ProjectModelImporter(ExactModelIngressAuthority(f.permit(request))).import(request, raw)
        assertEquals(ModelCompleteness.COMPLETE, model.context(f.mainId, f.owner.configurationDigest).completeness)
        assertEquals(GRADLE_MODEL_EXPORTER_ID, model.context(f.mainId, f.owner.configurationDigest).context!!.policies["ktchecker.model"])
        failure("model_exporter_unavailable") { ProjectModelImporter().importKgp2410(request, raw) }
    }

    @Test fun newGraphCannotCrossTheOldSchemaEvenWithAnOldRequestPin() {
        val f = Fixture(); val (request, raw) = f.request(legacy = true)
        failure("model_export_schema") {
            ProjectModelImporter(ExactModelIngressAuthority(f.permit(request))).import(request, raw)
        }
    }

    @Test fun completeSyntheticOwnerPrerequisiteRetainsNewIdentityButCreatesNoJvmRoles() {
        val f = Fixture(); val model = f.import()
        val context = model.context(f.mainId, f.owner.configurationDigest)
        assertEquals(ModelCompleteness.COMPLETE, context.completeness)
        assertEquals(KGP_2410_MODEL_EXPORTER_ID, context.context!!.policies["ktchecker.model"])
        assertEquals(Kgp2410ModelExporter.scriptDigest(), model.identity.exporterDigest)
        assertEquals(f.graph, model.export)
        assertEquals(f.graph.compilations.map { it.id }, model.compilationIds)
        assertTrue(model.compilationIds.all { model.jvmArgumentLayout(it) == null })
        assertTrue(model.jvmArgumentRejections.isEmpty()); assertNull(model.jvmProducedGraph)
        assertEquals("", context.context!!.scope.target)
        assertEquals("2.4.10", context.context!!.compilerVersion)
    }

    @Test fun originalConfiguredVectorAndModelMemberOrderAreNeverSortedFilteredOrRoleRewritten() {
        val f = Fixture(); val main = f.graph.compilations.first()
        val original = main.compilerArguments + listOf("-Xunreviewed-by-FULL", "@retained-report-fact")
        val graph = f.graph.copy(compilations = listOf(main.copy(compilerArguments = original)) + f.graph.compilations.drop(1))
        val model = f.import(graph)
        val context = model.context(f.mainId, f.owner.configurationDigest).context!!
        assertEquals(original, context.compilerArguments)
        assertEquals(main.sourceFiles.map { it.path }, context.compilationFiles)
        assertEquals(main.classpath.map { it.path }, context.classpath.map { it.path })
        assertNull(model.jvmArgumentLayout(f.mainId), "Raw facts are not an executable FULL/compiler projection")
    }

    @Test fun oldJvmRolePayloadInEitherCompilationIsDeniedBeforeAnyRoleAuthorityCallback() {
        val f = Fixture(); var roleCalls = 0
        for (selected in f.graph.compilations) {
            val roles = ModelJvmArgumentRoles(compileTaskPath = selected.compileTaskPath,
                sourcePaths = selected.sourceFiles.map { it.path }, classpathOperand = f.owner.artifact.path,
                destinationPath = f.owner.work.resolve("declared-only-output").toString(),
                selectedJdkHome = f.owner.work.resolve("declared-only-jdk").toString(), jdkHomeOperand = null,
                pluginDataState = "provider_absent")
            val graph = f.graph.copy(compilations = f.graph.compilations.map { if (it.id == selected.id) it.copy(jvmArgumentRoles = roles) else it })
            val (request, raw) = f.request(graph)
            val permit = f.permit(request, role = { roleCalls++; null })
            failure("model_exporter_foreign_jvm_payload") { ProjectModelImporter(ExactModelIngressAuthority(permit)).importKgp2410(request, raw) }
        }
        assertEquals(0, roleCalls)
    }

    @Test fun deferredRolePayloadCannotBeReusedEvenWithoutOldArgumentRoles() {
        val f = Fixture(); val main = f.graph.compilations.first(); var originCalls = 0
        val ref = ModelJvmProducedOutputReference(main.id, main.compileTaskPath, f.owner.work.resolve("unexecuted-output").toString(),
            0, false, true, false, false)
        val graph = f.graph.copy(compilations = listOf(main, f.graph.compilations.last().copy(deferredJvmOutputs = listOf(ref))))
        val (request, raw) = f.request(graph)
        val permit = f.permit(request, produced = { originCalls++; null })
        failure("model_exporter_foreign_jvm_payload") { ProjectModelImporter(ExactModelIngressAuthority(permit)).importKgp2410(request, raw) }
        assertEquals(0, originCalls)
    }

    @Test fun foreignCompilerVersionsBecomeExplicitWholeModelGapsNotAnAllowlistRepair() {
        val f = Fixture()
        for (version in listOf(null, "2.4.21", "2.4.10-custom")) {
            val graph = f.graph.copy(compilations = f.graph.compilations.map {
                if (it.name == "test") it.copy(toolchain = it.toolchain!!.copy(compilerVersion = version)) else it
            })
            val model = f.import(graph)
            assertEquals(graph, model.export)
            assertTrue(model.issues.any { it.kind == "kotlin_compiler_version_unsupported" && it.scopeId == f.testId })
            val main = model.context(f.mainId, f.owner.configurationDigest)
            assertEquals(ModelCompleteness.UNAVAILABLE, main.completeness)
            assertFalse(main.context!!.fullProject); assertNull(main.certificateId)
        }
    }

    @Test fun originalExporterIssuesIncludingUnrelatedAndClosureGapsAreNeverErased() {
        val f = Fixture()
        val gaps = listOf(ModelIssue("compiler_arguments_unmodeled", f.testId, "Independent test producer unavailable"),
            ModelIssue("configuration_input_closure_unproven", null, "The real complete read set is not proven"))
        val model = f.import(f.graph.copy(issues = gaps))
        assertEquals(gaps, model.export.issues)
        assertTrue(model.issues.containsAll(gaps))
        assertEquals(ModelCompleteness.UNAVAILABLE, model.context(f.mainId, f.owner.configurationDigest).completeness)
        assertFalse(model.context(f.mainId, f.owner.configurationDigest).context!!.fullProject)
    }

    @Test fun declaredAssociatedPluginAndAuxiliaryInputsRemainFactsAndUnsupportedGaps() {
        val f = Fixture(); val main = f.graph.compilations.first(); val test = f.graph.compilations.last()
        for (changed in listOf(test.copy(associatedCompilationIds = listOf(main.id), friendArtifacts = listOf(f.owner.artifact)),
            test.copy(toolchain = test.toolchain!!.copy(compilerPlugins = listOf(f.owner.artifact))),
            test.copy(auxiliaryInputFiles = test.sourceFiles),
            test.copy(auxiliaryArtifacts = listOf(f.owner.artifact)),
            test.copy(projectDependencies = listOf(ModelProjectDependency(":app", "synthetic-unmapped-variant", emptyMap()))))) {
            val graph = f.graph.copy(compilations = listOf(main, changed)); val model = f.import(graph)
            assertEquals(graph, model.export)
            assertTrue(model.issues.any { it.kind in setOf("kgp2410_produced_inputs_unsupported", "kgp2410_additional_inputs_unsupported") })
            assertFalse(model.context(f.mainId, f.owner.configurationDigest).context!!.fullProject)
            assertNull(model.jvmProducedGraph)
        }
    }

    @Test fun nonJvmCompilationsRemainPresentAndMakeTheWholeModelUnavailable() {
        val f = Fixture()
        val graph = f.graph.copy(modules = f.graph.modules.map { it.copy(pluginIds = listOf("org.jetbrains.kotlin.multiplatform")) },
            compilations = f.graph.compilations.map { compilation ->
                if (compilation.id == f.testId) compilation.copy(target = "js", platform = "js", toolchain = compilation.toolchain!!.copy(kind = "js"))
                else compilation.copy(target = "jvm")
            })
        val model = f.import(graph)
        assertEquals(graph, model.export)
        assertTrue(model.issues.any { it.kind == "kgp2410_compilation_domain_unsupported" && it.scopeId == f.testId })
        assertEquals(ModelCompleteness.UNAVAILABLE, model.context(f.mainId, f.owner.configurationDigest).completeness)
    }

    @Test fun refinementResourceAndGeneratedFactsRetainTheirOwnExplicitGaps() {
        val f = Fixture(); val main = f.graph.sourceSets.first(); val test = f.graph.sourceSets.last()
        val refinement = f.graph.copy(sourceSets = listOf(main, test.copy(dependsOn = listOf(main.id))),
            compilations = f.graph.compilations.map { compilation ->
                if (compilation.id == f.testId) compilation.copy(sourceSetIds = listOf(main.id, test.id), sourceFiles = main.sources + test.sources)
                else compilation
            })
        val resources = f.graph.copy(sourceSets = listOf(main, test.copy(resourceRoots = test.sourceRoots, resources = test.sources)))
        val generated = f.graph.copy(generatedInputs = listOf(GeneratedSourceInput(":app:synthetic-generator",
            listOf(test.id), main.sources, test.sources, test.sourceRoots, "captured")))
        for ((graph, kind) in listOf(refinement to "kgp2410_source_domain_unsupported", resources to "kgp2410_source_domain_unsupported",
            generated to "kgp2410_generated_inputs_unsupported")) {
            val model = f.import(graph)
            assertEquals(graph, model.export)
            assertTrue(model.issues.any { it.kind == kind })
            assertFalse(model.context(f.mainId, f.owner.configurationDigest).context!!.fullProject)
            assertNull(model.jvmProducedGraph)
        }
    }

    @Test fun exactPermitRemainsSingleUseAndBoundToTheWholeRequest() {
        val f = Fixture(); val (request, raw) = f.request(); val permit = f.permit(request)
        val importer = ProjectModelImporter(ExactModelIngressAuthority(permit))
        failure("model_ingress_not_authorized") { importer.importKgp2410(request.copy(executionId = "different-owner-attempt"), raw) }
        importer.importKgp2410(request, raw)
        failure("model_ingress_mismatch_or_replay") { importer.importKgp2410(request, raw) }
    }

    @Test fun bothOriginalOwnerCallbacksRemainRequiredAndNoClosureIsManufactured() {
        val f = Fixture(); val (request, raw) = f.request(); var exports = 0; var inputs = 0
        val permit = f.permit(request, export = { exports++ }, inputs = { inputs++ })
        ProjectModelImporter(ExactModelIngressAuthority(permit)).importKgp2410(request, raw)
        assertEquals(2, exports); assertEquals(2, inputs)
        val (unproven, bytes) = f.request()
        val denied = f.permit(unproven, inputs = { throw IllegalStateException("Real complete configuration read set unavailable") })
        assertFailsWith<IllegalStateException> { ProjectModelImporter(ExactModelIngressAuthority(denied)).importKgp2410(unproven, bytes) }
    }

    @Test fun preparationFreezesOriginalBytesBeforeAnAuthorityCanMutateTheCallerBuffer() {
        val f = Fixture(); val (request, raw) = f.request(); val permit = f.permit(request)
        val importer = ProjectModelImporter(ModelIngressAuthority { raw.fill(0); permit })
        val model = importer.importKgp2410(request, raw)
        assertEquals(f.graph, model.export)
        assertTrue(raw.all { it == 0.toByte() })
        assertFails { (model.export.compilations as MutableList<*>).clear() }
        assertFails { (model.export.compilations.first().compilerArguments as MutableList<*>).clear() }
    }

    @Test fun completeOriginRevocationDuringSecondReadCannotReturnANominalModel() {
        val f = Fixture(); val (request, raw) = f.request(); var checks = 0
        val permit = f.permit(request, inputs = { if (++checks == 2) throw IllegalStateException("Original owner origin revoked") })
        assertFailsWith<IllegalStateException> { ProjectModelImporter(ExactModelIngressAuthority(permit)).importKgp2410(request, raw) }
        assertEquals(2, checks)
    }

    @Test fun unknownSerializedFieldsAreNotIgnoredToRecoverAnAcceptedGraph() {
        val f = Fixture(); val (request, bytes) = f.request()
        val raw = ("{\"unregisteredRoleCapability\":true," + bytes.toString(Charsets.UTF_8).drop(1)).toByteArray()
        val selected = request.copy(exportDigest = Digests.sha256(raw))
        failure("model_export_schema") {
            ProjectModelImporter(ExactModelIngressAuthority(f.permit(selected))).importKgp2410(selected, raw)
        }
    }

    private fun failure(code: String, block: () -> Unit) = assertEquals(code, assertFailsWith<ProjectModelException>(block = block).code)

    private class Fixture {
        val owner = ModelFixtures()
        val capture = owner.capture(mapOf("settings.gradle.kts" to "// SYNTHETIC owner model only\n",
            "app/build.gradle.kts" to "// No Gradle task is executed by this unit fixture\n",
            "app/src/main/kotlin/A.kt" to "fun a() = 1\n", "app/src/main/kotlin/Z.kt" to "fun z() = 2\n",
            "app/src/test/kotlin/Test.kt" to "fun test() = 3\n"))
        val mainId = ":app||main"
        val testId = ":app||test"
        val graph: GradleProjectExport
        init {
            assumeOwnerFixtureAnchoredReads(owner.work)
            fun modelFile(path: String): ModelFile = capture.snapshot.files.single { it.path == path }.let { ModelFile(it.path, it.digest, it.size) }
            val sets = listOf("main", "test").map { name ->
                val root = "app/src/$name/kotlin"
                ModelSourceSet(":app|$name", ":app", name, emptyList(), listOf(root), emptyList(),
                    capture.snapshot.files.filter { it.path.startsWith("$root/") }.map { modelFile(it.path) }, emptyList())
            }
            val compilations = sets.map { set ->
                val raw = listOf("-no-stdlib", "-no-reflect", "-language-version", "2.4", "-api-version", "2.4",
                    "-jvm-target", "21", "-module-name", "synthetic_" + set.name, "-classpath", owner.artifact.path,
                    "-d", owner.work.resolve("unexecuted-compiler-output/" + set.name).toString()) +
                    set.sources.reversed().map { capture.root.resolve(it.path).toString() }
                ModelCompilation(":app||${set.name}", ":app", "", "jvm", set.name, set.id, listOf(set.id), set.sources,
                    ":app:compile-${set.name}", "${set.name}CompileClasspath", listOf(owner.artifact), emptyList(),
                    ModelToolchain("jvm", "2.4.10", listOf(owner.artifact), listOf(owner.artifact)), raw, "2.4", "2.4")
            }
            graph = GradleProjectExport(exporterId = KGP_2410_MODEL_EXPORTER_ID, gradleVersion = "9.6.1",
                modules = listOf(ModelModule(":app", "app", "app/build.gradle.kts", listOf("org.jetbrains.kotlin.jvm"))),
                sourceSets = sets, compilations = compilations,
                configurationInputs = capture.snapshot.files.filter { it.path.endsWith(".gradle.kts") }.map { modelFile(it.path) },
                generatedInputs = emptyList(), buildscriptArtifacts = listOf(owner.artifact), includedBuilds = emptyList(), issues = emptyList())
        }
        fun request(graph: GradleProjectExport = this.graph, legacy: Boolean = false): Pair<ModelImportRequest, ByteArray> {
            val raw = ContractJson.encodeToString(graph).toByteArray()
            return owner.request(capture, graph, raw).copy(exporterDigest =
                if (legacy) GradleModelExporter.scriptDigest() else Kgp2410ModelExporter.scriptDigest()) to raw
        }
        fun permit(request: ModelImportRequest, export: () -> Unit = {}, inputs: (GradleProjectExport) -> Unit = {},
                   role: (ModelCompilation) -> ModelJvmArgumentOrigin? = { null },
                   produced: (ModelCompilation) -> ModelJvmProducedOutputOrigin? = { null }): ModelIngressPermit {
            val base = owner.permit(capture, request, verifyExecution = export, verifyInputs = inputs)
            return ModelIngressPermit(base.request, base.gradleRuntimeDigest, base.verifySnapshot, base.verifyExecutedExport,
                base.verifyArtifact, base.verifyModelInputs, role, produced)
        }
        fun import(graph: GradleProjectExport = this.graph): VerifiedProjectModel {
            val (request, raw) = request(graph)
            return ProjectModelImporter(ExactModelIngressAuthority(permit(request))).importKgp2410(request, raw)
        }
    }
}

package dev.ktchecker.providers.detekt

import dev.ktchecker.compiler.KotlinJvmRuntimeException
import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.*
import dev.ktchecker.providers.runtime.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Synthetic model/import/origin controls; no method in this class executes a process.
 * Positive bindings hash real cached pinned compiler/Detekt bytes plus a tiny non-executed JDK.
 * Those are identity checks, never actual Gradle, compiler, native Detekt or qualification proof.
 */
class DetektFullModelBindingTest {
    private fun failure(code: String, action: () -> Unit) =
        assertEquals(code, assertFailsWith<ExecutionProblem>(block = action).code)

    @Test fun `a compiler coordinate filename or declared2_4_10 cannot substitute for actual pinned bytes`(): Unit = DetektFullFixtures().use { f ->
        val artifact = f.compilerArtifacts.single()
        val identity = ArtifactIdentity(artifact.coordinate, requireNotNull(artifact.digest), artifact.path)
        val unselected = DetektInstallation(f.work.resolve("not-installed-jdk/bin/java"),
            f.work.resolve("not-installed-detekt"), f.work.resolve("not-installed-jdk"))
        // Fails at the compiler pin, before any JDK or engine selection could be accepted.
        failure("detekt_full_compiler_pin_mismatch") { DetektFullInstallation.capture(unselected, listOf(identity)) {} }
        for (artifacts in listOf(emptyList(), listOf(identity, identity))) {
            failure("detekt_full_compiler_installation_mismatch") { DetektFullInstallation.capture(unselected, artifacts) {} }
        }
    }

    @Test fun `FULL marker is a one way legacy refusal after every policy and model field is erased`(): Unit = DetektFullFixtures().use { f ->
        val fullMarker = DetektVerifiedFullModelBinding.PROFILE_PROPERTY + Digests.sha256("copied nominal marker")
        val profile = TrustedExecution("/never/run/java", arguments = listOf(fullMarker), approved = true)
        val parent = f.parent()
        val stripped = parent.copy(context = AnalysisContext(scope = RuleContext(target = "jvm"),
            compilerVersion = "2.4.10", compilationFiles = parent.context.compilationFiles), execution = profile)
        val legacy = DetektProvider()
        val declared = stripped.copy(context = stripped.context.copy(policies = mapOf(
            "ktchecker.model" to "owner-declared-single-compilation", "ktchecker.coverage" to "partial-project")))
        for (candidate in listOf(stripped, declared, ContractJson.decodeFromString<AnalysisInput>(ContractJson.encodeToString(stripped)))) {
            assertTrue(detektModelBearingInput(candidate))
            assertEquals(listOf("detekt_model_route_required"), legacy.analyze(candidate).reasons)
            failure("detekt_model_binding_required") { legacy.executionPlan(candidate) }
            assertEquals(listOf("detekt_model_not_authorized"),
                DetektProvider(inputMode = DetektInputMode.VERIFIED_MODEL_LIGHT).analyze(candidate).reasons)
            assertEquals(listOf("detekt_full_model_not_authorized"), DetektFullModelProvider().analyze(candidate).reasons)
        }
        assertFalse(detektModelBearingInput(declared.copy(execution = null)),
            "The change only recognizes/refuses the FULL marker, not unrelated legacy declared inputs")
    }

    @Test fun `serialized approval cannot replace live FULL origin installation and same grant object`(): Unit = DetektFullFixtures().use { f ->
        val parent = f.parent()
        val child = parent.copy(requiredRuleIds = f.selected,
            execution = TrustedExecution("/never/run/java", approved = true,
                arguments = listOf(DetektVerifiedFullModelBinding.PROFILE_PROPERTY + Digests.sha256("copied"))))
        var callbacks = 0
        val provider = DetektFullModelProvider(
            authority = ExactExecutionAuthority(ExecutionPermit(child.execution!!, child.snapshot.snapshotId,
                Path.of(child.materializedRoot), child.analysisKey(), { error("No callback without live FULL authority") })),
            runner = IsolatedProcessRunner { callbacks++ })
        assertEquals(setOf(DETEKT_FULL_MISSING_USE_CALL), provider.supportedRuleIds)
        assertEquals(276, provider.mappedRuleIds.size)
        for (candidate in listOf(child, ContractJson.decodeFromString<AnalysisInput>(ContractJson.encodeToString(child)),
            child.copy(context = child.context.copy(policies = emptyMap(), fullProject = false)))) {
            val result = provider.analyze(candidate)
            assertEquals(listOf("detekt_full_model_not_authorized"), result.reasons)
            assertEquals(f.selected, result.coverage.map { it.ruleId })
            assertTrue(result.coverage.all { !it.implemented && !it.tested && !it.qualified && it.certificateId == null && it.provenance == "not_executed" })
            assertFalse(result.complete())
        }
        assertEquals(0, callbacks)
    }

    @Test fun `whole context unnamed target raw argv policy receipt and rule vector cannot be normalized`(): Unit = DetektFullFixtures().use { f ->
        val model = f.importModel()
        val parent = f.parent(model)
        val context = parent.context
        val provider = DetektFullModelProvider()
        val origin = f.origin(model)
        val variants = listOf(context.copy(scope = context.scope.copy(target = "jvm")),
            context.copy(scope = context.scope.copy(module = ":other")), context.copy(scope = context.scope.copy(sourceSet = "test")),
            context.copy(compilerArguments = emptyList()), context.copy(compilerArguments = context.compilerArguments.reversed()),
            context.copy(compilationFiles = context.compilationFiles!!.reversed()),
            context.copy(compilationFiles = context.compilationFiles!! + "src/test/kotlin/Foreign.kt"),
            context.copy(compilerVersion = "2.4.21"), context.copy(fullProject = false),
            context.copy(modelDigest = Digests.sha256("foreign model")), context.copy(toolchainDigest = Digests.sha256("foreign toolchain")),
            context.copy(dependencyDigest = Digests.sha256("foreign classpath")), context.copy(configurationDigest = Digests.sha256("foreign policy")),
            context.copy(policies = context.policies - "ktchecker.model-certificate"),
            context.copy(policies = context.policies + ("ktchecker.model-certificate" to Digests.sha256("foreign ingress"))),
            context.copy(policies = context.policies + ("unreviewed.policy" to "ignored")))
        for (changed in variants) failure("detekt_full_context_mismatch") {
            DetektVerifiedFullModelBinding.prepare(origin, parent.copy(context = changed), provider, f.selected)
        }
        failure("detekt_full_parent_profiles_mismatch") {
            DetektVerifiedFullModelBinding.prepare(origin, parent.copy(context = context.copy(policies = context.policies +
                ("ktchecker.execution-plan" to Digests.sha256("{\"detekt\":\"grafted\"}")))), provider, f.selected)
        }
        for (candidate in listOf(parent.copy(snapshot = parent.snapshot.copy(snapshotId = Digests.sha256("foreign snapshot"))),
            parent.copy(materializedRoot = f.work.resolve("foreign-source").toString()),
            parent.copy(execution = TrustedExecution("/never/run/java", approved = true)),
            parent.copy(outputRoot = f.work.resolve("output").toString()))) {
            failure("detekt_full_origin_mismatch") { DetektVerifiedFullModelBinding.prepare(origin, candidate, provider, f.selected) }
        }
        failure("detekt_full_origin_mismatch") {
            DetektVerifiedFullModelBinding.prepare(f.origin(model, task = ":fixture:compileTestKotlin"), parent, provider, f.selected)
        }
        val other = f.importModel()
        assertEquals(model.identity.modelDigest, other.identity.modelDigest)
        assertNotEquals(model.ingressCertificate, other.ingressCertificate)
        failure("detekt_full_context_mismatch") {
            DetektVerifiedFullModelBinding.prepare(f.origin(other), parent, provider, f.selected)
        }
    }

    @Test fun `selection is ordered subset of all1665 and disabled or nonsemantic only never starts empty engine`(): Unit = DetektFullFixtures().use { f ->
        val model = f.importModel()
        val parent = f.parent(model)
        assertEquals(1665, parent.requiredRuleIds.size)
        assertEquals(276, f.selected.size)
        for (selection in listOf(emptyList(), f.selected.reversed(), listOf(DETEKT_FULL_MISSING_USE_CALL, DETEKT_FULL_MISSING_USE_CALL),
            listOf("ktc.compiler.common.unresolved_reference"), listOf("ktc.unmapped.invented"))) {
            failure("detekt_full_rule_projection_invalid") {
                DetektVerifiedFullModelBinding.prepare(f.origin(model), parent, DetektFullModelProvider(), selection)
            }
        }
        for (selection in listOf(f.selected - DETEKT_FULL_MISSING_USE_CALL, listOf("ktc.resources.owned_autocloseable_exit"))) {
            failure("detekt_full_no_enabled_semantic_rule") {
                DetektVerifiedFullModelBinding.prepare(f.origin(model), parent, DetektFullModelProvider(), selection)
            }
        }
        failure("detekt_full_no_enabled_semantic_rule") {
            DetektVerifiedFullModelBinding.prepare(f.origin(model), parent,
                DetektFullModelProvider(settings = DetektFullSettings(enabled = false)), f.selected)
        }
        for (timeout in listOf(0L, 60_001L)) failure("detekt_full_execution_limits_invalid") {
            DetektVerifiedFullModelBinding.prepare(f.origin(model), parent, DetektFullModelProvider(), f.selected, timeoutMillis = timeout)
        }
        for (limit in listOf(0, 1_048_577)) failure("detekt_full_execution_limits_invalid") {
            DetektVerifiedFullModelBinding.prepare(f.origin(model), parent, DetektFullModelProvider(), f.selected, maxOutputBytes = limit)
        }
    }

    @Test fun `embedded2_4_10 never stands for modeled2_4_21 unknown Common or Native`(): Unit = DetektFullFixtures().use { f ->
        val graph = f.graph()
        for (version in listOf("2.4.21", "2.3.0", null)) {
            val model = f.importModel(graph.copy(compilations = graph.compilations.map { it.copy(toolchain = it.toolchain!!.copy(compilerVersion = version)) }))
            failure("detekt_full_compiler_unsupported") {
                DetektVerifiedFullModelBinding.prepare(f.origin(model), f.parent(model), DetektFullModelProvider(), f.selected)
            }
        }
        for (platform in listOf("common", "native")) {
            val otherGraph = graph.copy(modules = graph.modules.map { it.copy(pluginIds = listOf("org.jetbrains.kotlin.multiplatform")) },
                compilations = graph.compilations.map { it.copy(target = platform + "Target", platform = platform,
                    toolchain = it.toolchain!!.copy(kind = platform)) })
            val model = f.importModel(otherGraph)
            failure("detekt_full_platform_unsupported") {
                DetektVerifiedFullModelBinding.prepare(f.origin(model), f.parent(model), DetektFullModelProvider(), f.selected)
            }
        }
        failure("detekt_full_compilation_unavailable") {
            val model = f.importModel()
            DetektVerifiedFullModelBinding.prepare(f.origin(model, compilationId = ":absent"), f.parent(model), DetektFullModelProvider(), f.selected)
        }
    }

    @Test fun `actual current exporter version and closure issues remain unavailable instead of being stripped`(): Unit = DetektFullFixtures().use { f ->
        val text = Files.readString(DetektFullFixtures.repository().resolve("modules/project-model/src/main/resources/ktchecker-model.init.gradle"))
        assertTrue(text.contains("known(plugin, 'getPluginVersion') != '2.4.21'"))
        assertTrue(text.contains("compilerVersion != '2.4.21'"))
        assertTrue(text.contains("ktchecker/kgp-2.4.21-original-jvm-roles/1"))
        val exporterGaps = listOf(
            ModelIssue("kotlin_plugin_version_unsupported", ":fixture", "The actual exporter remains pinned to KGP2.4.21, not this synthetic2.4.10 model."),
            ModelIssue("kotlin_compiler_version_unsupported", f.mainId, "Modeled compiler2.4.10 is outside the actual exporter pin."),
            ModelIssue("compiler_arguments_unmodeled", f.testId, "An independent original-argument observation failed."),
        )
        for (issues in listOf(exporterGaps.take(1), exporterGaps.take(2), exporterGaps,
            listOf(ModelIssue("jvm_produced_output_deferred", f.testId, "Deferred outputs are not completed by FULL analysis.")),
            listOf(ModelIssue("configuration_input_unmodeled", null, "An unknown configuration input remains an independent closure gap.")))) {
            val model = f.importModel(f.graph().copy(issues = issues))
            assertTrue(model.issues.containsAll(issues))
            val verified = model.context(f.mainId, f.configuration)
            assertEquals(ModelCompleteness.UNAVAILABLE, verified.completeness)
            assertNull(verified.certificateId)
            assertFalse(f.parent(model).context.fullProject)
            failure("detekt_full_model_incomplete") {
                DetektVerifiedFullModelBinding.prepare(f.origin(model), f.parent(model), DetektFullModelProvider(), f.selected)
            }
            assertEquals(1665, f.parent(model).requiredRuleIds.size)
        }
    }

    @Test fun `plugins auxiliary Java roles friends generated inputs and refinement cannot be implicitly analyzed`(): Unit = DetektFullFixtures().use { f ->
        val graph = f.graph()
        val main = graph.compilations.first()
        val extras = listOf(main.copy(toolchain = main.toolchain!!.copy(compilerPlugins = f.dependencies)),
            main.copy(auxiliaryInputFiles = graph.configurationInputs.take(1)), main.copy(auxiliaryArtifacts = f.dependencies),
            main.copy(friendArtifacts = f.dependencies), main.copy(associatedCompilationIds = listOf(f.testId)))
        for (variant in extras) {
            val model = f.importModel(graph.copy(compilations = listOf(variant) + graph.compilations.drop(1)))
            failure("detekt_full_auxiliary_inputs_unsupported") {
                DetektVerifiedFullModelBinding.prepare(f.origin(model), f.parent(model), DetektFullModelProvider(), f.selected)
            }
        }
        val generated = GeneratedSourceInput(":fixture:generateKotlin", main.sourceSetIds, graph.configurationInputs,
            main.sourceFiles.take(1), listOf("src/main/kotlin"), "captured")
        val generatedModel = f.importModel(graph.copy(generatedInputs = listOf(generated)))
        failure("detekt_full_auxiliary_inputs_unsupported") {
            DetektVerifiedFullModelBinding.prepare(f.origin(generatedModel), f.parent(generatedModel), DetektFullModelProvider(), f.selected)
        }
        val mainSet = graph.sourceSets.first()
        val common = mainSet.copy(id = ":fixture|commonMain", name = "commonMain", sources = mainSet.sources.take(1))
        val leaf = mainSet.copy(dependsOn = listOf(common.id), sources = mainSet.sources.drop(1))
        val refined = f.importModel(graph.copy(sourceSets = listOf(common, leaf) + graph.sourceSets.drop(1),
            compilations = listOf(main.copy(sourceSetIds = listOf(common.id, leaf.id))) + graph.compilations.drop(1)))
        failure("detekt_full_source_roles_unsupported") {
            DetektVerifiedFullModelBinding.prepare(f.origin(refined), f.parent(refined), DetektFullModelProvider(), f.selected)
        }
    }

    @Test fun `2_4_21 original role data cannot be transplanted into the new nominal route`(): Unit = DetektFullFixtures().use { f ->
        val graph = f.graph()
        val main = graph.compilations.first()
        val roles = ModelJvmArgumentRoles(compileTaskPath = main.compileTaskPath, sourcePaths = main.sourceFiles.map { it.path },
            classpathOperand = main.classpath.joinToString(":") { it.path }, destinationPath = f.destination.toString(),
            selectedJdkHome = f.work.resolve("unselected-jdk").toString(), jdkHomeOperand = null, pluginDataState = "absent")
        val model = f.importModel(graph.copy(compilations = listOf(main.copy(jvmArgumentRoles = roles)) + graph.compilations.drop(1)))
        assertNull(model.jvmArgumentLayout(f.mainId))
        assertEquals("model_jvm_argument_origin_unavailable", model.jvmArgumentRejections[f.mainId])
        failure("detekt_full_foreign_argument_adapter_unsupported") {
            DetektVerifiedFullModelBinding.prepare(f.origin(model), f.parent(model), DetektFullModelProvider(), f.selected)
        }
    }

    @Test fun `exact pinned preparation preserves all1665 raw arguments and ordinal classpath templates`(): Unit = DetektFullFixtures().use { f ->
        val installed = f.unitInstallation()
        val model = f.importModel()
        val parent = f.parent(model)
        val grants = DetektFullFixtures.Grants(installed)
        val prepared = DetektVerifiedFullModelBinding.prepare(f.origin(model), parent, grants.provider, f.selected)
        assertEquals(parent, prepared.original)
        assertEquals(1665, prepared.original.requiredRuleIds.size)
        assertEquals(f.wholeRuleIds, prepared.original.requiredRuleIds)
        assertEquals(parent.analysisKey(), prepared.originalAnalysisKey)
        assertEquals("", prepared.original.context.scope.target)
        assertEquals("2.4.10", prepared.original.context.compilerVersion)
        assertEquals(Digests.sha256("{}"), prepared.original.context.policies["ktchecker.execution-plan"])
        assertEquals(model.compilation(f.mainId)!!.compilerArguments, prepared.original.context.compilerArguments)
        assertEquals(f.selected, prepared.detektRuleIds)
        assertEquals("full", prepared.plan.analysisMode)
        assertEquals(setOf(DETEKT_FULL_MISSING_USE_CALL), prepared.plan.activeRuleIds)
        assertEquals(installed.runtimeImageDigest, prepared.bindingIdentity.runtimeImageDigest)
        assertEquals(5, prepared.plan.artifacts.size)
        val args = prepared.detektProfile.arguments
        fun operand(flag: String) = args[args.indexOf(flag) + 1]
        assertEquals("full", operand("--analysis-mode"))
        assertEquals("2.4", operand("--language-version")); assertEquals("2.4", operand("--api-version")); assertEquals("21", operand("--jvm-target"))
        assertEquals(installed.jdkHome.toString(), operand("--jdk-home"))
        assertEquals("{snapshot}/../classpath/artifact-0.jar:{snapshot}/../classpath/artifact-1.jar", operand("--classpath"))
        assertEquals("{snapshot}/" + DetektFullSemanticCases.DIRECT_PATH + ":{snapshot}/" + DetektFullSemanticCases.ALIAS_PATH, operand("--input"))
        assertEquals(listOf("-no-stdlib", "-no-reflect", "-module-name", "synthetic_main"), args.drop(args.indexOf("--") + 1))
        assertTrue(args.none { f.destination.toString() in it || f.sourceRoot.toString() in it || "model-artifacts" in it })
        assertTrue(args.none { it in setOf("-d", "-classpath", "-Werror", "-nowarn", "--all-rules") })
        assertTrue(args.any { it == DetektVerifiedFullModelBinding.PROFILE_PROPERTY + prepared.bindingIdentity.digest })
        assertTrue(grants.outcomes.isEmpty())
    }

    @Test fun `full modeled compiler vector and every runtime FILE identity remain exact without sorting`(): Unit = DetektFullFixtures().use { f ->
        val installed = f.unitInstallation()
        val chain = f.graph().compilations.first().toolchain!!
        installed.requireModelToolchain(chain)
        assertEquals(2, chain.compilerArtifacts.size)
        for (artifacts in listOf(chain.compilerArtifacts.reversed(), chain.compilerArtifacts.dropLast(1),
            chain.compilerArtifacts + chain.compilerArtifacts.first(),
            chain.compilerArtifacts.mapIndexed { index, artifact -> if (index == 0) artifact.copy(path = f.work.resolve("foreign-compiler.jar").toString()) else artifact },
            chain.compilerArtifacts.mapIndexed { index, artifact -> if (index == 0) artifact.copy(digest = Digests.sha256("foreign")) else artifact },
            chain.compilerArtifacts.mapIndexed { index, artifact -> if (index == 0) artifact.copy(size = 1) else artifact })) {
            failure("detekt_full_compiler_installation_mismatch") { installed.requireModelToolchain(chain.copy(compilerArtifacts = artifacts)) }
        }
        failure("detekt_full_compiler_unsupported") { installed.requireModelToolchain(chain.copy(compilerVersion = "2.4.21")) }
        assertEquals(6, chain.runtimeArtifacts.size)
        for (artifacts in listOf(chain.runtimeArtifacts.dropLast(1), chain.runtimeArtifacts + chain.runtimeArtifacts.first(),
            chain.runtimeArtifacts + ModelArtifact("compilation-jdk:identity-only-empty-directory", installed.jdkHome.resolve("identity-only-empty-directory").toString(), Digests.sha256(""), 0),
            chain.runtimeArtifacts.map { it.copy(coordinate = "foreign:" + it.coordinate) })) {
            failure("detekt_full_runtime_domain_mismatch") { installed.requireModelToolchain(chain.copy(runtimeArtifacts = artifacts)) }
        }
        val first = chain.runtimeArtifacts.first()
        for (changed in listOf(first.copy(path = f.work.resolve("foreign-runtime").toString()),
            first.copy(digest = Digests.sha256("changed runtime")), first.copy(size = 1))) {
            failure("detekt_full_runtime_artifact_mismatch") {
                installed.requireModelToolchain(chain.copy(runtimeArtifacts = listOf(changed) + chain.runtimeArtifacts.drop(1)))
            }
        }
        // FILE order is not compiler classpath order: exact unique domain/path/bytes are required;
        // the live retained image additionally binds empty directories and executable policy.
        installed.requireModelToolchain(chain.copy(runtimeArtifacts = chain.runtimeArtifacts.reversed()))
    }

    @Test fun `a genuine compiler and JDK still cannot make a synthetic stdlib or missing stdlib semantic`(): Unit = DetektFullFixtures().use { f ->
        val synthetic = f.graph()
        val installed = f.unitInstallation()
        val real = f.graph()
        val wrongBytes = synthetic.copy(compilations = synthetic.compilations.map { original ->
            original.copy(toolchain = real.compilations.single { it.id == original.id }.toolchain)
        })
        val missing = real.copy(compilations = real.compilations.map { it.copy(classpath = it.classpath.drop(1)) })
        for (graph in listOf(wrongBytes, missing)) {
            val model = f.importModel(graph)
            assertTrue(f.parent(model).context.fullProject, "Synthetic ingress completeness is only a prerequisite, never version compatibility")
            failure("detekt_full_stdlib_pin_mismatch") {
                DetektVerifiedFullModelBinding.prepare(f.origin(model), f.parent(model), DetektFullModelProvider(installed), f.selected)
            }
        }
    }

    @Test fun `whole JDK nonlauncher bytes and empty directories cannot hide behind an unchanged launcher`(): Unit = DetektFullFixtures().use { f ->
        val installed = f.unitInstallation()
        val modules = installed.jdkHome.resolve("lib/modules")
        val old = Files.readAllBytes(modules)
        val time = Files.getLastModifiedTime(modules)
        val launcherDigest = fileDigest(installed.javaExecutable)
        Files.write(modules, old.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
        Files.setLastModifiedTime(modules, time)
        assertEquals(launcherDigest, fileDigest(installed.javaExecutable))
        assertEquals("compiler_java_runtime_changed", assertFailsWith<KotlinJvmRuntimeException> { installed.verify() }.code)
        Files.write(modules, old); Files.setLastModifiedTime(modules, time); installed.verify()
        Files.createDirectory(installed.jdkHome.resolve("new-empty-directory"))
        assertEquals("compiler_java_runtime_changed", assertFailsWith<KotlinJvmRuntimeException> { installed.verify() }.code)
    }

    @Test fun `owner output fact cannot be chosen by parsing the original d operand`(): Unit = DetektFullFixtures().use { f ->
        val installed = f.unitInstallation()
        val model = f.importModel()
        val provider = DetektFullModelProvider(installed)
        failure("detekt_full_argument_operand_mismatch") {
            DetektVerifiedFullModelBinding.prepare(f.origin(model, output = f.work.resolve("wrong-owner-output")), f.parent(model), provider, f.selected)
        }
        failure("detekt_full_origin_mismatch") {
            DetektVerifiedFullModelBinding.prepare(f.origin(model, root = f.work.resolve("wrong-owner-source")), f.parent(model), provider, f.selected)
        }
    }

    @Test fun `archive permits only exact source and classpath ordinal relocation not rewritten context`(): Unit = DetektFullFixtures().use { f ->
        val installed = f.unitInstallation()
        val model = f.importModel()
        val prepared = DetektVerifiedFullModelBinding.prepare(f.origin(model), f.parent(model), DetektFullModelProvider(installed), f.selected)
        val archive = f.archive(prepared)
        var checks = 0
        val verify = { checks++; archive.verify() }
        val context = archive.effective.context
        for (changed in listOf(context.copy(scope = context.scope.copy(target = "jvm")), context.copy(compilerArguments = emptyList()),
            context.copy(compilationFiles = listOf("src/test/kotlin/Foreign.kt")), context.copy(fullProject = false),
            context.copy(policies = context.policies + ("ktchecker.model-certificate" to Digests.sha256("foreign"))))) {
            failure("detekt_model_effective_mismatch") {
                prepared.bindAttempt(prepared.original, archive.effective.copy(context = changed), archive.child, verify)
            }
        }
        failure("detekt_model_classpath_mapping_mismatch") {
            prepared.bindAttempt(prepared.original, archive.effective.copy(context = context.copy(classpath = context.classpath.reversed())), archive.child, verify)
        }
        failure("detekt_full_original_mismatch") {
            prepared.bindAttempt(prepared.original.copy(requiredRuleIds = f.selected), archive.effective, archive.child, verify)
        }
        for (child in listOf(archive.child.copy(requiredRuleIds = listOf(DETEKT_FULL_MISSING_USE_CALL)),
            archive.child.copy(execution = prepared.detektProfile.copy(arguments = prepared.detektProfile.arguments + "-nowarn")),
            archive.child.copy(context = archive.child.context.copy(compilerArguments = emptyList())))) {
            failure("detekt_full_child_mismatch") { prepared.bindAttempt(prepared.original, archive.effective, child, verify) }
        }
        failure("detekt_model_path_invalid") {
            prepared.bindAttempt(prepared.original, archive.effective.copy(materializedRoot = archive.effective.materializedRoot + "/../sources"), archive.child, verify)
        }
        failure("detekt_model_output_overlap") {
            prepared.bindAttempt(prepared.original, archive.effective.copy(outputRoot = Path.of(archive.effective.materializedRoot).parent.resolve("output").toString()), archive.child, verify)
        }
        assertEquals(0, checks)
        prepared.bindAttempt(prepared.original, archive.effective, archive.child, verify).use { permit ->
            assertTrue(permit.accepts(archive.child))
            assertEquals(prepared.original.context.compilerArguments, archive.child.context.compilerArguments)
            assertEquals(prepared.plan, permit.plan)
            assertTrue(checks > 0)
        }
    }

    @Test fun `live permit is provider instance bound revocable and refuses a separately equal execution grant`(): Unit = DetektFullFixtures().use { f ->
        val installed = f.unitInstallation()
        val model = f.importModel()
        val grants = DetektFullFixtures.Grants(installed)
        val prepared = DetektVerifiedFullModelBinding.prepare(f.origin(model), f.parent(model), grants.provider, f.selected)
        val archive = f.archive(prepared)
        val permit = prepared.bindAttempt(prepared.original, archive.effective, archive.child, archive.verify)
        grants.permit = permit
        try {
            assertSame(permit, ExactDetektFullModelAuthority(permit).authorize(archive.child))
            assertSame(permit.executionPermit, ExactDetektFullModelExecutionAuthority(permit).authorize(archive.child))
            assertFalse(permit.accepts(archive.child.copy(requiredRuleIds = listOf(DETEKT_FULL_MISSING_USE_CALL))))
            assertFalse(permit.accepts(archive.child.copy(context = archive.child.context.copy(compilerArguments = emptyList()))))
            val other = DetektFullModelProvider(installed, ExactDetektFullModelExecutionAuthority(permit),
                modelAuthority = ExactDetektFullModelAuthority(permit))
            assertEquals(listOf("detekt_full_attempt_mismatch"), other.analyze(archive.child).reasons)
            val realGrant = permit.executionPermit
            grants.executionOverride = ExecutionPermit(realGrant.execution, realGrant.snapshotId, realGrant.materializedRoot,
                realGrant.expectedAnalysisKey, realGrant.verifySnapshot, realGrant.prepareOutput)
            assertEquals(realGrant.execution, grants.executionOverride!!.execution)
            assertEquals(listOf("detekt_full_execution_grant_mismatch"), grants.provider.analyze(archive.child).reasons)
            assertTrue(grants.outcomes.isEmpty())
        } finally { permit.close() }
        assertFalse(permit.accepts(archive.child))
        assertNull(ExactDetektFullModelAuthority(permit).authorize(archive.child))
        assertEquals(listOf("detekt_full_model_not_authorized"), grants.provider.analyze(archive.child).reasons)
        assertTrue(grants.outcomes.isEmpty())
    }

    @Test fun `source archive dependency and owner revocation drift deny before any fake launcher could run`(): Unit = DetektFullFixtures().use { f ->
        val installed = f.unitInstallation()
        val model = f.importModel()
        val grants = DetektFullFixtures.Grants(installed)
        val prepared = DetektVerifiedFullModelBinding.prepare(f.origin(model), f.parent(model), grants.provider, f.selected)
        val archive = f.archive(prepared)
        prepared.bindAttempt(prepared.original, archive.effective, archive.child, archive.verify).use { permit ->
            grants.permit = permit
            val source = Path.of(archive.child.materializedRoot).resolve(DetektFullSemanticCases.DIRECT_PATH)
            val bytes = Files.readAllBytes(source)
            Files.write(source, bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
            assertEquals(listOf("detekt_full_binding_unavailable"), grants.provider.analyze(archive.child).reasons)
            Files.write(source, bytes)
            val dependency = Path.of(archive.child.context.classpath.last().path)
            val dependencyBytes = Files.readAllBytes(dependency)
            Files.write(dependency, dependencyBytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
            assertEquals(listOf("detekt_full_binding_unavailable"), grants.provider.analyze(archive.child).reasons)
            Files.write(dependency, dependencyBytes)
            f.originActive = false
            assertEquals(listOf("fixture_full_origin_revoked"), grants.provider.analyze(archive.child).reasons)
            assertTrue(grants.outcomes.isEmpty())
        }
    }

    @Test fun `original capture checkpoint survives preparation binding and attempted budget refresh`() {
        var expired = false
        var checks = 0
        val checkpoint = {
            checks++
            if (expired) throw ExecutionProblem("fixture_original_deadline_expired", "Original owner budget, never a refreshed scan timeout")
        }
        DetektFullFixtures(checkpoint = checkpoint).use { f ->
            val installed = f.unitInstallation()
            val model = f.importModel()
            val grants = DetektFullFixtures.Grants(installed)
            val origin = f.origin(model)
            val parent = f.parent(model)
            val prepared = DetektVerifiedFullModelBinding.prepare(origin, parent, grants.provider, f.selected)
            val archive = f.archive(prepared)
            prepared.bindAttempt(prepared.original, archive.effective, archive.child, archive.verify).use { permit ->
                grants.permit = permit
                assertTrue(checks > 20)
                expired = true
                failure("fixture_original_deadline_expired") { installed.verify() }
                failure("fixture_original_deadline_expired") {
                    DetektVerifiedFullModelBinding.prepare(origin, parent, grants.provider, f.selected, timeoutMillis = 60_000)
                }
                assertEquals(listOf("fixture_original_deadline_expired"), grants.provider.analyze(archive.child).reasons)
                assertTrue(grants.outcomes.isEmpty())
            }
        }
    }

    @Test fun `retained mutable caller lists maps and settings cannot rewrite the prepared profile`(): Unit = DetektFullFixtures().use { f ->
        val installed = f.unitInstallation()
        val model = f.importModel()
        val base = f.parent(model)
        val rules = base.requiredRuleIds.toMutableList()
        val arguments = base.context.compilerArguments.toMutableList()
        val members = base.context.compilationFiles!!.toMutableList()
        val policies = base.context.policies.toMutableMap()
        val ignoreClasses = mutableListOf("java.io.ByteArrayInputStream")
        val provider = DetektFullModelProvider(installed, settings = DetektFullSettings(ignoreClasses = ignoreClasses))
        val selection = f.selected.toMutableList()
        val parent = base.copy(requiredRuleIds = rules, context = base.context.copy(compilerArguments = arguments,
            compilationFiles = members, policies = policies))
        val prepared = DetektVerifiedFullModelBinding.prepare(f.origin(model), parent, provider, selection)
        rules.clear(); arguments.clear(); members.clear(); policies.clear(); selection.clear(); ignoreClasses += "java.io.FileInputStream"
        assertEquals(base, prepared.original)
        assertEquals(f.selected, prepared.detektRuleIds)
        assertFalse("java.io.FileInputStream" in prepared.plan.configuration)
        val archive = f.archive(prepared)
        prepared.bindAttempt(prepared.original, archive.effective, archive.child, archive.verify).use { assertTrue(it.accepts(archive.child)) }
        assertFails { (prepared.detektRuleIds as MutableList<String>).clear() }
        assertFails { (prepared.detektProfile.arguments as MutableList<String>).clear() }
        assertFails { (prepared.original.context.policies as MutableMap<String, String>).clear() }
    }
}

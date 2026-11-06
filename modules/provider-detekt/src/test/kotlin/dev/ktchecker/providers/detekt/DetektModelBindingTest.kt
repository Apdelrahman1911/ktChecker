package dev.ktchecker.providers.detekt

import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.*
import dev.ktchecker.providers.runtime.*
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Import/binding/projection unit controls only. NO test in this class launches a process. */
class DetektModelBindingTest {
    private fun failure(code: String, action: () -> Unit) =
        assertEquals(code, assertFailsWith<ExecutionProblem>(block = action).code)

    @Test fun `fixed model route denies copied JSON and cannot downgrade after model fields disappear`(): Unit = DetektModelFixtures().use { f ->
        val parent = f.parent()
        val input = parent.copy(requiredRuleIds = f.selected, execution = TrustedExecution("/bin/true", approved = true))
        val authority = ExactExecutionAuthority(ExecutionPermit(input.execution!!, input.snapshot.snapshotId,
            Path.of(input.materializedRoot), input.analysisKey(), { error("No snapshot callback may be used without model authority") }))
        var callbacks = 0
        val provider = DetektProvider(authority = authority, runner = IsolatedProcessRunner { callbacks++ },
            inputMode = DetektInputMode.VERIFIED_MODEL_LIGHT)
        assertEquals(156, provider.supportedRuleIds.size)
        assertFalse(f.semanticRule in provider.supportedRuleIds)
        assertFalse(f.aliasRule in provider.supportedRuleIds)
        assertFalse(f.refinementRule in provider.supportedRuleIds)
        val copied = ContractJson.decodeFromString<AnalysisInput>(ContractJson.encodeToString(input))
        for (candidate in listOf(input, copied, input.copy(context = AnalysisContext(compilationFiles = input.context.compilationFiles)))) {
            val result = provider.analyze(candidate)
            assertEquals(listOf("detekt_model_not_authorized"), result.reasons)
            assertTrue(result.coverage.all { it.provenance == "not_executed" && !it.implemented && !it.tested && !it.qualified })
            assertFalse(result.complete())
            failure("detekt_model_binding_required") { provider.executionPlan(candidate) }
        }
        assertEquals(0, callbacks)
    }

    @Test fun `legacy route refuses genuine model tags but keeps old explicit semantic fixture identity`(): Unit = DetektModelFixtures().use { f ->
        val genuine = f.parent()
        val legacy = DetektProvider()
        assertEquals(listOf("detekt_model_route_required"), legacy.analyze(genuine).reasons)
        failure("detekt_model_binding_required") { legacy.executionPlan(genuine) }
        val oldFixture = genuine.copy(context = genuine.context.copy(scope = genuine.context.scope.copy(target = "jvm"), policies = emptyMap()))
        assertEquals(listOf("execution_not_authorized"), legacy.analyze(oldFixture).reasons,
            "Non-null model digests/fullProject alone must not break the preexisting synthetic semantic integration shape")
        val declared = oldFixture.copy(context = oldFixture.context.copy(fullProject = false, policies = mapOf(
            "ktchecker.model" to "owner-declared-single-compilation", "ktchecker.coverage" to "partial-project")))
        assertEquals(listOf("execution_not_authorized"), legacy.analyze(declared).reasons)
        val forged = declared.copy(context = declared.context.copy(policies = declared.context.policies + ("ktchecker.model-extra" to "claim")))
        assertEquals(listOf("detekt_model_route_required"), legacy.analyze(forged).reasons)
        assertFailsWith<IllegalArgumentException> { DetektProvider(modelAuthority = DetektModelAuthority { null }) }
    }

    @Test fun `whole parent context raw argv source order and empty profiles cannot be normalized`(): Unit = DetektModelFixtures().use { f ->
        val model = f.importModel()
        val parent = f.parent(model)
        val provider = DetektProvider(inputMode = DetektInputMode.VERIFIED_MODEL_LIGHT)
        val context = parent.context
        val variants = listOf(context.copy(scope = context.scope.copy(target = "jvm")),
            context.copy(scope = context.scope.copy(sourceSet = "test")), context.copy(scope = context.scope.copy(module = ":other")),
            context.copy(scope = context.scope.copy(compilation = "test")), context.copy(compilerArguments = emptyList()),
            context.copy(compilerArguments = context.compilerArguments.reversed()), context.copy(classpath = context.classpath.reversed()),
            context.copy(compilationFiles = context.compilationFiles!!.reversed()),
            context.copy(compilationFiles = context.compilationFiles!! + "src/test/kotlin/Foreign.kt"),
            context.copy(compilerVersion = DETEKT_EMBEDDED_KOTLIN), context.copy(fullProject = false),
            context.copy(modelDigest = Digests.sha256("foreign model")), context.copy(toolchainDigest = Digests.sha256("foreign toolchain")),
            context.copy(dependencyDigest = Digests.sha256("foreign deps")), context.copy(configurationDigest = Digests.sha256("foreign configuration")),
            context.copy(policies = context.policies - "ktchecker.model-certificate"))
        variants.forEach { variant -> failure("detekt_model_context_mismatch") {
            DetektVerifiedModelBinding.prepare(model, f.mainId, parent.copy(context = variant), provider, f.selected)
        } }
        val otherIngress = f.importModel()
        assertEquals(model.identity.modelDigest, otherIngress.identity.modelDigest)
        assertNotEquals(model.ingressCertificate, otherIngress.ingressCertificate)
        failure("detekt_model_context_mismatch") {
            DetektVerifiedModelBinding.prepare(otherIngress, f.mainId, parent, provider, f.selected)
        }
        failure("detekt_model_parent_profiles_mismatch") {
            DetektVerifiedModelBinding.prepare(model, f.mainId, parent.copy(context = context.copy(policies = context.policies +
                ("ktchecker.execution-plan" to Digests.sha256("{\"detekt\":\"grafted\"}")))), provider, f.selected)
        }
        for (candidate in listOf(parent.copy(snapshot = parent.snapshot.copy(snapshotId = Digests.sha256("other snapshot"))),
            parent.copy(execution = TrustedExecution("/bin/true", approved = true)), parent.copy(outputRoot = f.work.resolve("output").toString()))) {
            failure("detekt_model_input_mismatch") { DetektVerifiedModelBinding.prepare(model, f.mainId, candidate, provider, f.selected) }
        }
    }

    @Test fun `rule vector is an exact ordered projection not all rules guessed from metadata`(): Unit = DetektModelFixtures().use { f ->
        val model = f.importModel()
        val parent = f.parent(model)
        val provider = DetektProvider(inputMode = DetektInputMode.VERIFIED_MODEL_LIGHT)
        for (selected in listOf(emptyList(), f.selected.reversed(), listOf(f.exactRule, f.exactRule),
            listOf("ktc.compiler.common.unresolved_reference"), listOf("ktc.compose.pack.ComposableParamOrder"))) {
            failure("detekt_model_rule_projection_invalid") {
                DetektVerifiedModelBinding.prepare(model, f.mainId, parent, provider, selected)
            }
        }
        failure("detekt_model_provider_mismatch") { DetektVerifiedModelBinding.prepare(model, f.mainId, parent, DetektProvider(), f.selected) }
        failure("detekt_model_compilation_unavailable") { DetektVerifiedModelBinding.prepare(model, ":absent", parent, provider, f.selected) }
    }

    @Test fun `issue label alone cannot enroll a deferred output graph or waive another gap`(): Unit = DetektModelFixtures().use { f ->
        val provider = DetektProvider(inputMode = DetektInputMode.VERIFIED_MODEL_LIGHT)
        val named = ModelIssue("jvm_produced_output_deferred", f.testId,
            "Deferred JVM output from ${f.mainId} at classpath ordinal 1.")
        for (issues in listOf(listOf(named), listOf(named, ModelIssue("artifact_unavailable", f.testId, "Independent unresolved artifact")),
            listOf(ModelIssue("compiler_arguments_unmodeled", f.testId, "Actual all-types exporter arguments missing")))) {
            val model = f.importModel(f.graph().copy(issues = issues))
            assertNull(model.jvmProducedGraph)
            assertEquals(ModelCompleteness.UNAVAILABLE, model.context(f.mainId, f.configuration).completeness)
            assertFalse(f.parent(model).context.fullProject)
            failure("detekt_model_incomplete") { DetektVerifiedModelBinding.prepare(model, f.mainId, f.parent(model), provider, f.selected) }
        }
    }

    @Test fun `actual platform plugins auxiliary sources and refinement are not target aliases`(): Unit = DetektModelFixtures().use { f ->
        val graph = f.graph()
        val main = graph.compilations.first()
        val provider = DetektProvider(inputMode = DetektInputMode.VERIFIED_MODEL_LIGHT)
        val nativeGraph = graph.copy(modules = graph.modules.map { it.copy(pluginIds = listOf("org.jetbrains.kotlin.multiplatform")) },
            compilations = graph.compilations.map { it.copy(target = "nativeTarget", platform = "native", toolchain = it.toolchain!!.copy(kind = "native")) })
        val native = f.importModel(nativeGraph)
        failure("detekt_model_platform_unsupported") { DetektVerifiedModelBinding.prepare(native, f.mainId, f.parent(native), provider, f.selected) }
        val extras = listOf(main.copy(toolchain = main.toolchain!!.copy(compilerPlugins = listOf(f.dependencies.first()))),
            main.copy(auxiliaryInputFiles = listOf(graph.configurationInputs.first())),
            main.copy(associatedCompilationIds = listOf(f.testId)), main.copy(friendArtifacts = listOf(f.dependencies.first())))
        extras.forEach { variant ->
            val model = f.importModel(graph.copy(compilations = listOf(variant) + graph.compilations.drop(1)))
            failure("detekt_model_auxiliary_inputs_unsupported") { DetektVerifiedModelBinding.prepare(model, f.mainId, f.parent(model), provider, f.selected) }
        }
        val mainSet = graph.sourceSets.first()
        val common = mainSet.copy(id = ":fixture|commonMain", name = "commonMain", sources = mainSet.sources.take(1))
        val leaf = mainSet.copy(dependsOn = listOf(common.id), sources = mainSet.sources.drop(1))
        val refined = f.importModel(graph.copy(sourceSets = listOf(common, leaf) + graph.sourceSets.drop(1),
            compilations = listOf(main.copy(sourceSetIds = listOf(common.id, leaf.id))) + graph.compilations.drop(1)))
        failure("detekt_model_source_roles_unsupported") { DetektVerifiedModelBinding.prepare(refined, f.mainId, f.parent(refined), provider, f.selected) }
    }

    @Test fun `unbacked original argument roles remain unavailable even for LIGHT`(): Unit = DetektModelFixtures().use { f ->
        val graph = f.graph()
        val main = graph.compilations.first()
        val roles = ModelJvmArgumentRoles(compileTaskPath = main.compileTaskPath, sourcePaths = main.sourceFiles.map { it.path },
            classpathOperand = main.classpath.joinToString(":") { it.path }, destinationPath = f.work.resolve("output").toString(),
            selectedJdkHome = f.work.resolve("jdk").toString(), jdkHomeOperand = null, pluginDataState = "absent")
        val model = f.importModel(graph.copy(compilations = listOf(main.copy(jvmArgumentRoles = roles)) + graph.compilations.drop(1)))
        assertNull(model.jvmArgumentLayout(f.mainId))
        failure("detekt_model_argument_origin_unavailable") {
            DetektVerifiedModelBinding.prepare(model, f.mainId, f.parent(model), DetektProvider(inputMode = DetektInputMode.VERIFIED_MODEL_LIGHT), f.selected)
        }
    }

    @Test fun `genuine importer object prepares LIGHT without rewriting unnamed target or raw compiler paths`(): Unit = DetektModelFixtures().use { f ->
        val model = f.importModel()
        val parent = f.parent(model)
        val grants = DetektModelFixtures.Grants(f.installation())
        val prepared = DetektVerifiedModelBinding.prepare(model, f.mainId, parent, grants.provider, f.selected)
        assertEquals(parent, prepared.original)
        assertEquals(parent.analysisKey(), prepared.originalAnalysisKey)
        assertEquals("", prepared.original.context.scope.target)
        assertTrue(prepared.original.context.fullProject)
        assertEquals(model.compilation(f.mainId)!!.compilerArguments, prepared.original.context.compilerArguments)
        assertEquals(Digests.sha256("{}"), prepared.original.context.policies["ktchecker.execution-plan"])
        assertEquals(f.selected, prepared.detektRuleIds)
        assertEquals(setOf(f.exactRule), prepared.plan.activeRuleIds)
        assertEquals("light", prepared.plan.analysisMode)
        val args = prepared.detektProfile.arguments
        assertEquals("light", args[args.indexOf("--analysis-mode") + 1])
        assertTrue(args.any { it == DetektVerifiedModelBinding.PROFILE_PROPERTY + prepared.bindingIdentity.digest })
        assertTrue(args.none { it in setOf("--classpath", "--jdk-home", "--jvm-target", "--language-version", "--api-version", "-d", "-classpath", "-no-stdlib") })
        assertTrue(args.none { f.sourceRoot.toString() in it || "unused-compiler-output" in it || "model-artifacts" in it })
        assertTrue(grants.outcomes.isEmpty())
    }

    @Test fun `semantic alias refinement and disabled only requests cannot launch empty model profile`(): Unit = DetektModelFixtures().use { f ->
        val model = f.importModel()
        val parent = f.parent(model)
        val installed = f.installation()
        for (selected in listOf(listOf(f.semanticRule), listOf(f.aliasRule), listOf(f.refinementRule))) {
            failure("detekt_model_no_light_rules_active") { DetektVerifiedModelBinding.prepare(model, f.mainId, parent,
                DetektProvider(installed, inputMode = DetektInputMode.VERIFIED_MODEL_LIGHT), selected) }
        }
        failure("detekt_model_no_light_rules_active") { DetektVerifiedModelBinding.prepare(model, f.mainId, parent,
            DetektProvider(installed, settings = DetektSettings(disabledRuleIds = setOf(f.exactRule)),
                inputMode = DetektInputMode.VERIFIED_MODEL_LIGHT), f.selected) }
    }

    @Test fun `prepared snapshots lists and settings detach retained mutable aliases`(): Unit = DetektModelFixtures().use { f ->
        val model = f.importModel()
        val base = f.parent(model)
        val rules = base.requiredRuleIds.toMutableList()
        val arguments = base.context.compilerArguments.toMutableList()
        val files = base.context.compilationFiles!!.toMutableList()
        val policies = base.context.policies.toMutableMap()
        val options = mutableMapOf<String, JsonElement>("allowedExceptionNameRegex" to JsonPrimitive("^(ignored)$"))
        val disabled = mutableSetOf<String>()
        val provider = DetektProvider(f.installation(), settings = DetektSettings(disabledRuleIds = disabled,
            options = mapOf(f.exactRule to options)), inputMode = DetektInputMode.VERIFIED_MODEL_LIGHT)
        val parent = base.copy(requiredRuleIds = rules, context = base.context.copy(compilerArguments = arguments,
            compilationFiles = files, policies = policies))
        val selected = f.selected.toMutableList()
        val prepared = DetektVerifiedModelBinding.prepare(model, f.mainId, parent, provider, selected)
        rules.clear(); arguments.clear(); files.clear(); policies.clear(); selected.clear(); disabled += f.exactRule
        options["allowedExceptionNameRegex"] = JsonPrimitive(".*")
        assertEquals(base, prepared.original)
        assertEquals(f.selected, prepared.detektRuleIds)
        val archive = f.archive(prepared)
        prepared.bindAttempt(prepared.original, archive.effective, archive.child, archive.verify).use { permit ->
            assertTrue(permit.accepts(archive.child))
            permit.executionPermit.verifySnapshot()
        }
        assertFails { (prepared.detektProfile.arguments as MutableList<String>).clear() }
        assertFails { (prepared.detektRuleIds as MutableList<String>).clear() }
    }

    @Test fun `archive relation rejects foreign files argv classpath ordinals and path aliases before callback`(): Unit = DetektModelFixtures().use { f ->
        val model = f.importModel()
        val provider = DetektProvider(f.installation(), inputMode = DetektInputMode.VERIFIED_MODEL_LIGHT)
        val prepared = DetektVerifiedModelBinding.prepare(model, f.mainId, f.parent(model), provider, f.selected)
        val archive = f.archive(prepared)
        var callbacks = 0
        val verify = { callbacks++; archive.verify() }
        val context = archive.effective.context
        val changed = listOf(
            archive.effective.copy(context = context.copy(scope = context.scope.copy(sourceSet = "test"))),
            archive.effective.copy(context = context.copy(compilerArguments = emptyList())),
            archive.effective.copy(context = context.copy(compilationFiles = listOf("src/test/kotlin/Foreign.kt"))),
        )
        for (effective in changed) {
            failure("detekt_model_effective_mismatch") { prepared.bindAttempt(prepared.original, effective, archive.child, verify) }
        }
        failure("detekt_model_classpath_mapping_mismatch") { prepared.bindAttempt(prepared.original,
            archive.effective.copy(context = context.copy(classpath = context.classpath.reversed())), archive.child, verify) }
        failure("detekt_model_child_mismatch") { prepared.bindAttempt(prepared.original, archive.effective,
            archive.child.copy(requiredRuleIds = listOf(f.exactRule)), verify) }
        failure("detekt_model_child_mismatch") { prepared.bindAttempt(prepared.original, archive.effective,
            archive.child.copy(execution = prepared.detektProfile.copy(arguments = prepared.detektProfile.arguments + "-nowarn")), verify) }
        failure("detekt_model_path_invalid") { prepared.bindAttempt(prepared.original,
            archive.effective.copy(materializedRoot = archive.effective.materializedRoot + "/../sources"), archive.child, verify) }
        failure("detekt_model_output_overlap") { prepared.bindAttempt(prepared.original,
            archive.effective.copy(outputRoot = Path.of(archive.effective.materializedRoot).parent.resolve("output").toString()), archive.child, verify) }
        assertEquals(0, callbacks)
    }

    @Test fun `live grant accepts only exact provider child and cannot revive after close`(): Unit = DetektModelFixtures().use { f ->
        val model = f.importModel()
        val grants = DetektModelFixtures.Grants(f.installation())
        val prepared = DetektVerifiedModelBinding.prepare(model, f.mainId, f.parent(model), grants.provider, f.selected)
        val archive = f.archive(prepared)
        val permit = prepared.bindAttempt(prepared.original, archive.effective, archive.child, archive.verify)
        grants.permit = permit
        try {
            assertTrue(permit.accepts(archive.child))
            assertSame(permit.executionPermit, ExactDetektModelExecutionAuthority(permit).authorize(archive.child))
            assertSame(permit, ExactDetektModelAuthority(permit).authorize(archive.child))
            for (candidate in listOf(archive.child.copy(context = archive.child.context.copy(scope = archive.child.context.scope.copy(target = "jvm"))),
                archive.child.copy(context = archive.child.context.copy(compilerArguments = emptyList())),
                archive.child.copy(requiredRuleIds = listOf(f.exactRule)), archive.child.copy(outputRoot = f.work.resolve("other-output").toString()))) {
                assertFalse(permit.accepts(candidate))
                assertEquals(listOf("detekt_model_not_authorized"), grants.provider.analyze(candidate).reasons)
            }
            val other = DetektProvider(f.installation(), authority = ExactDetektModelExecutionAuthority(permit),
                inputMode = DetektInputMode.VERIFIED_MODEL_LIGHT, modelAuthority = ExactDetektModelAuthority(permit))
            assertEquals(listOf("detekt_model_attempt_mismatch"), other.analyze(archive.child).reasons)
            grants.executionOverride = ExecutionPermit(permit.executionPermit.execution, permit.executionPermit.snapshotId,
                permit.executionPermit.materializedRoot, permit.expectedAnalysisKey, { error("Equal data is not this grant") })
            assertEquals(listOf("detekt_model_execution_grant_mismatch"), grants.provider.analyze(archive.child).reasons)
            grants.executionOverride = null
        } finally { permit.close() }
        assertFalse(permit.accepts(archive.child))
        assertEquals(listOf("detekt_model_not_authorized"), grants.provider.analyze(archive.child).reasons)
        failure("detekt_model_attempt_mismatch") { permit.executionPermit.verifySnapshot() }
        assertTrue(grants.outcomes.isEmpty(), "Every denial must occur before the real isolated runner")
    }

    @Test fun `attempt verification reads only frozen sources and not expired original compiler paths`(): Unit = DetektModelFixtures().use { f ->
        val model = f.importModel()
        val provider = DetektProvider(f.installation(), inputMode = DetektInputMode.VERIFIED_MODEL_LIGHT)
        val prepared = DetektVerifiedModelBinding.prepare(model, f.mainId, f.parent(model), provider, f.selected)
        val archive = f.archive(prepared)
        f.removeOriginalSourcesAndDependencies()
        prepared.bindAttempt(prepared.original, archive.effective, archive.child, archive.verify).use { permit ->
            assertEquals(prepared.original.context.compilerArguments, archive.child.context.compilerArguments)
            permit.executionPermit.verifySnapshot()
            assertTrue(permit.accepts(archive.child))
        }
    }

    @Test fun `SARIF positives negatives and excluded rows remain advisory without fabricated native execution`(): Unit = DetektModelFixtures().use { f ->
        val input = f.parent().copy(requiredRuleIds = f.selected)
        val provider = DetektProvider(inputMode = DetektInputMode.VERIFIED_MODEL_LIGHT)
        val sarifId = DetektCatalog.byId.getValue(f.exactRule).sarifId
        // Pure projection DTO only. Never fed to analyze/runner, and never called a process result.
        val plan = DetektExecutionPlan(TrustedExecution("/never-executed", approved = false), "", Digests.sha256(""),
            emptyList(), setOf(f.exactRule), "light")
        val positiveBytes = sarif(sarifId, "src/main/kotlin/A.kt").toByteArray()
        val parsed = DetektReportParser().parse(positiveBytes, input)
        val positive = provider.projectReport(input, plan, parsed, Digests.sha256(positiveBytes), "unit-only.sarif")
        assertEquals("partial", positive.status)
        assertEquals(f.exactRule, positive.findings.single().ruleId)
        assertEquals("advisory", positive.findings.single().kind)
        assertEquals("low", positive.findings.single().certainty)
        assertEquals(input.context.scope, positive.findings.single().context)
        assertEquals("", positive.findings.single().context.target)
        assertEquals(1, positive.rawDiagnostics.size)
        assertTrue("project_compiler_2.4.21_differs_from_detekt_embedded_compiler" in positive.reasons)
        assertFalse(positive.complete())
        val inactive = positive.coverage.filter { it.ruleId != f.exactRule }
        assertEquals(setOf("unavailable", "excluded", "unsupported"), inactive.map { it.state }.toSet())
        assertTrue(inactive.all { it.provenance == "not_executed" && !it.implemented && !it.tested && !it.qualified && it.certificateId == null })
        assertTrue(positive.coverage.all { !it.tested && !it.qualified && !it.successful() })
        val negative = provider.projectReport(input, plan, parsed.copy(diagnostics = emptyList()), Digests.sha256("unit negative"), "unit-only.sarif")
        assertTrue(negative.findings.isEmpty())
        assertEquals("unknown", negative.coverage.single { it.ruleId == f.exactRule }.state)
        assertFalse(negative.complete())
        for (result in listOf(positive, negative)) {
            val exact = result.coverage.single { it.ruleId == f.exactRule }
            assertTrue(exact.implemented)
            assertEquals("executed", exact.provenance)
            assertEquals("exact_syntax_upstream_executed_advisory_only_parser_compatibility_unqualified", exact.reason)
        }
        // Synthetic projection negative control, not a process observation: without an enabled
        // upstream descriptor, an empty result must never attest this rule's execution.
        val noDescriptor = provider.projectReport(input, plan,
            parsed.copy(enabledSarifIds = emptySet(), diagnostics = emptyList()),
            Digests.sha256("unit missing descriptor"), "unit-only.sarif")
        val missing = noDescriptor.coverage.single { it.ruleId == f.exactRule }
        assertEquals("partial", noDescriptor.status)
        assertTrue(noDescriptor.findings.isEmpty())
        assertFalse(noDescriptor.complete())
        assertEquals("unknown", missing.state)
        assertEquals("upstream_rule_not_reported_active", missing.reason)
        assertEquals("not_executed", missing.provenance)
        assertFalse(missing.implemented)
        assertFalse(missing.tested)
        assertFalse(missing.qualified)
        assertNull(missing.certificateId)
        assertFalse(missing.successful())
        failure("detekt_report_malformed") { DetektReportParser().parse(sarif(sarifId, "src/test/kotlin/Foreign.kt").toByteArray(), input) }
        val semantic = DetektCatalog.byId.getValue(f.semanticRule).sarifId
        failure("detekt_model_report_projection_mismatch") {
            provider.projectReport(input, plan, parsed.copy(enabledSarifIds = parsed.enabledSarifIds + semantic), Digests.sha256("unit"), "unit-only.sarif")
        }
        failure("detekt_model_report_projection_mismatch") {
            provider.projectReport(input, plan.copy(activeRuleIds = setOf(f.aliasRule)), parsed, Digests.sha256("unit"), "unit-only.sarif")
        }
        val disabled = DetektProvider(settings = DetektSettings(disabledRuleIds = setOf(f.exactRule)), inputMode = DetektInputMode.VERIFIED_MODEL_LIGHT)
        failure("detekt_model_report_projection_mismatch") {
            disabled.projectReport(input, plan, parsed, Digests.sha256("unit"), "unit-only.sarif")
        }
    }

    private fun sarif(rule: String, path: String): String =
        """{"version":"2.1.0","runs":[{"tool":{"driver":{"name":"detekt","rules":[{"id":${JsonPrimitive(rule)},"defaultConfiguration":{"enabled":true,"level":"warning"}}]}},"results":[{"ruleId":${JsonPrimitive(rule)},"message":{"text":"Synthetic SARIF projection only"},"locations":[{"physicalLocation":{"artifactLocation":{"uri":${JsonPrimitive(path)},"uriBaseId":"%SRCROOT%"},"region":{"startLine":1,"startColumn":1,"endLine":1,"endColumn":4}}}]}]}]}"""
}

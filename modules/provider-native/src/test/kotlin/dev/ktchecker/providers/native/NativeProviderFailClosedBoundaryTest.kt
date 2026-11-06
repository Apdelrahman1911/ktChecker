package dev.ktchecker.providers.native

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.nio.file.Path
import kotlin.test.*

/**
 * Synthetic post-execution POLICY projections, not successful Native executions or qualification.
 * No runner, source/artifact IO, host probe/spoof, permit, or compiler is used by these controls.
 * Only the separate early-admission controls call analyze, with mismatching targets or no plan and
 * a deny-only recording authority. Generic semantic target/platform binding is not tested here.
 */
class NativeProviderFailClosedBoundaryTest {
    @Test fun syntheticExactOutputCertificateStillProjectsIncompleteUntestedUnqualifiedCoverage() {
        val provider = KotlinNativeProvider()
        for (target in targets) {
            val fixture = fixture(target, provider.supportedRuleIds.toList())
            val stdout = syntheticTaskOutput(fixture.plan.requiredTasks)
            val result = projectSyntheticExitZeroPolicy(provider, fixture, stdout)
            val certificate = outputCertificate(fixture.input, stdout, "")
            assertTrue(Digests.valid(certificate))
            assertNonQualifyingProjection(fixture, result, certificate,
                "native_per_id_apple_precision_qualification_pending")
            assertTrue(result.findings.isEmpty())
            assertTrue(result.rawDiagnostics.isEmpty())
        }
    }

    @Test fun syntheticUnselectedWarningWithholdsCertificateWithoutProducingSuccessfulCoverage() {
        val warning = NativeCatalog.byName.getValue("DEPRECATION")
        for (target in targets) {
            val fixture = fixture(target)
            assertFalse(warning.ruleId in fixture.input.requiredRuleIds)
            val stdout = syntheticTaskOutput(fixture.plan.requiredTasks)
            val stderr = "w: " + commonPath + ":1:1 [" + warning.upstreamId + "] synthetic unselected warning"
            val result = projectSyntheticExitZeroPolicy(KotlinNativeProvider(), fixture, stdout, stderr)
            assertNonQualifyingProjection(fixture, result, null, "native_unselected_diagnostic:" + warning.ruleId)
            assertTrue(result.findings.isEmpty())
            assertEquals(listOf(warning.upstreamId), result.rawDiagnostics.map { it.upstreamId })
            assertEquals("warning", result.rawDiagnostics.single().severity)
        }
    }

    @Test fun syntheticCommonOnlyOrSiblingTaskOutputNeverCertifiesSelectedIosBackend() {
        for (target in targets) {
            val fixture = fixture(target)
            val sibling = targets.single { it != target }
            for (observed in listOf(setOf(commonTask), setOf(commonTask, sibling.task))) {
                val stdout = syntheticTaskOutput(observed)
                val parsed = NativeDiagnosticParser.parse(stdout, "")
                assertTrue(parsed.buildSuccessful, "A synthetic common build-success line is not selected iOS proof")
                assertEquals(listOf(target.task),
                    ExactGradleTaskProof.missingExecution(fixture.plan.requiredTasks, parsed.tasks))
                val result = projectSyntheticExitZeroPolicy(KotlinNativeProvider(), fixture, stdout)
                assertNonQualifyingProjection(fixture, result, null, "native_task_not_executed:" + target.task)
                assertTrue(result.findings.isEmpty())
                assertTrue(result.rawDiagnostics.isEmpty())
            }
        }
    }

    @Test fun commonOnlyAndSiblingTaskModelsFailSelectedIosAdmission() {
        for (target in targets) {
            val fixture = fixture(target)
            val module = fixture.input.context.scope.module
            requireNativeTaskSelection(fixture.plan, fixture.plan.requiredTasks.toList(), module)
            val sibling = targets.single { it != target }
            for (tasks in listOf(setOf(commonTask), setOf(commonTask, sibling.task))) {
                val wrongPlan = fixture.plan.copy(requiredTasks = tasks)
                // The exact module/argv selection is valid; it is the selected iOS backend that is missing.
                ExactGradleTaskProof.requireSelection(tasks.toList(), tasks, module)
                assertEquals("native_required_task_model_incomplete", assertFailsWith<ExecutionProblem> {
                    requireNativeTaskSelection(wrongPlan, tasks.toList(), module)
                }.code)
            }
        }
    }

    @Test fun providerRejectsMismatchedTargetsAndMissingPlanBeforeAuthority() {
        for (target in targets) {
            val fixture = fixture(target)
            var authorizationCalls = 0
            val deny = ExecutionAuthority { authorizationCalls++; null }
            val provider = KotlinNativeProvider(authority = deny, plan = fixture.plan)
            val sibling = targets.single { it != target }
            for (wrongTarget in listOf("jvm", "common", sibling.name)) {
                val input = fixture.input.copy(context = fixture.input.context.copy(
                    scope = fixture.input.context.scope.copy(target = wrongTarget)))
                val result = provider.analyze(input)
                assertUnavailable(input, result, "native_ios_target_model_required")
            }
            val noPlan = KotlinNativeProvider(authority = deny).analyze(fixture.input)
            assertUnavailable(fixture.input, noPlan, "native_trusted_plan_unavailable")
            assertEquals(0, authorizationCalls, "These guards precede host and authority admission")
        }
    }

    @Test fun cliTargetOperandBindingIsExactButOnlyStructural() {
        for (target in targets) {
            val fixture = fixture(target)
            val members = fixture.input.context.compilationFiles!!
            val arguments = listOf("-target", target.cli, "-Xmulti-platform", "-Xrender-internal-diagnostic-names",
                "-Xcommon-sources={snapshot}/" + commonPath, "-produce", "library", "-o", "{output}/native-policy") +
                members.map { "{snapshot}/$it" }
            val input = fixture.input.copy(execution = fixture.input.execution!!.copy(arguments = arguments))
            val plan = fixture.plan.copy(mode = "native_cli",
                cliSelection = NativeCliSelection(input.analysisKey(), members, listOf(commonPath)))
            assertFalse(input.execution!!.approved)
            requireNativeCliSelection(plan, input) // Pure argv/model admission; not an observed backend check.
            val sibling = targets.single { it != target }
            val mismatch = input.copy(execution = input.execution!!.copy(
                arguments = arguments.mapIndexed { index, value -> if (index == 1) sibling.cli else value }))
            val reboundPlan = plan.copy(cliSelection = plan.cliSelection!!.copy(expectedAnalysisKey = mismatch.analysisKey()))
            assertEquals("native_cli_target_binding_invalid", assertFailsWith<ExecutionProblem> {
                requireNativeCliSelection(reboundPlan, mismatch)
            }.code)
        }
    }

    /**
     * Models only the exit-zero post-success reduction with synthetic text. There is no ProcessOutcome,
     * execution confirmation or permission. Derive normalizer/task inputs as analyze does; do not pass
     * contradictory selection flags or fabricate a source-location resolver.
     */
    private fun projectSyntheticExitZeroPolicy(provider: KotlinNativeProvider, fixture: Fixture, stdout: String,
                                               stderr: String = ""): ProviderResult {
        assertFalse(fixture.input.execution!!.approved)
        val parsed = NativeDiagnosticParser.parse(stdout, stderr)
        val normalized = NativeFindingFactory(fixture.input, fixture.plan).normalize(
            parsed.diagnostics, Digests.sha256(stdout + "\n" + stderr), emptyList()) {
            fail("These synthetic cases must not resolve any selected source diagnostic")
        }
        assertTrue(normalized.errors.isEmpty())
        assertFalse(normalized.hasReportedError)
        assertTrue(parsed.buildSuccessful)
        assertFalse(ExactGradleTaskProof.hasUnrelatedFailure(fixture.plan.requiredTasks, parsed.tasks))
        val taskGaps = ExactGradleTaskProof.missingExecution(fixture.plan.requiredTasks, parsed.tasks)
            .map { "native_task_not_executed:$it" }
        val metrics = AnalysisMetrics(elapsedMillis = 7, outputBytes = (stdout + stderr).toByteArray(Charsets.UTF_8).size.toLong())
        val result = provider.projectCompletedRun(fixture.input, fixture.input.requiredRuleIds.toSet(),
            outputCertificate(fixture.input, stdout, stderr), normalized.findings, normalized.errors,
            normalized.selectionGaps, taskGaps, normalized.rawDiagnostics, normalized.exactSelectedOutput, metrics)
        assertSame(metrics, result.metrics, "Synthetic documentary metrics are forwarded, not execution evidence")
        return result
    }

    private fun assertNonQualifyingProjection(fixture: Fixture, result: ProviderResult, certificate: String?, reason: String) {
        assertEquals("kotlin-native", result.providerId)
        assertEquals("2.4.21", result.providerVersion)
        assertEquals("incomplete", result.status)
        assertEquals(listOf(reason), result.reasons)
        assertEquals(fixture.input.requiredRuleIds, result.coverage.map { it.ruleId })
        assertTrue(result.coverage.isNotEmpty())
        for (row in result.coverage) {
            assertEquals(fixture.input.context.scope, row.context)
            assertEquals("incomplete", row.state)
            assertEquals(reason, row.reason)
            assertEquals("build", row.depth)
            assertEquals("executed", row.provenance, "This is the policy's datum, not observed execution")
            assertEquals(certificate, row.certificateId)
            assertTrue(row.required)
            assertTrue(row.implemented)
            // successful() checks state/certificate/provenance, not the two qualification flags.
            assertFalse(row.successful())
            assertFalse(row.tested)
            assertFalse(row.qualified)
        }
        assertFalse(result.complete())
        assertFalse(result.completeFor(fixture.input))
    }

    private fun assertUnavailable(input: AnalysisInput, result: ProviderResult, reason: String) {
        assertEquals("unavailable", result.status)
        assertEquals(listOf(reason), result.reasons)
        assertEquals(input.requiredRuleIds, result.coverage.map { it.ruleId })
        assertTrue(result.findings.isEmpty())
        for (row in result.coverage) {
            assertEquals(input.context.scope, row.context)
            assertEquals("unavailable", row.state)
            assertEquals(reason, row.reason)
            assertEquals("not_executed", row.provenance)
            assertNull(row.certificateId)
            assertFalse(row.successful())
            assertFalse(row.tested)
            assertFalse(row.qualified)
        }
        assertFalse(result.complete())
        assertFalse(result.completeFor(input))
    }

    private data class Target(val name: String, val task: String, val cli: String)
    private data class Fixture(val input: AnalysisInput, val plan: NativeRunPlan)

    private fun fixture(target: Target, requested: List<String> =
        listOf(NativeCatalog.byName.getValue("ACTUAL_WITHOUT_EXPECT").ruleId)): Fixture {
        val members = listOf(commonPath, nativePath)
        val scope = RuleContext(target.name, "iosMain", ":selected", "main")
        val modelDigest = Digests.sha256("synthetic policy model " + target.name)
        val sourceText = "package synthetic\n"
        val input = AnalysisInput(
            SnapshotManifest(Digests.sha256("synthetic policy snapshot"), "unit", "native-policy",
                members.map { SnapshotFile(it, Digests.sha256(sourceText), sourceText.toByteArray(Charsets.UTF_8).size.toLong()) }),
            absolute("unused-source-root"),
            AnalysisContext(scope, compilerVersion = "2.4.21", modelDigest = modelDigest,
                dependencyDigest = Digests.sha256("synthetic dependencies"), toolchainDigest = Digests.sha256("synthetic toolchain"),
                configurationDigest = Digests.sha256("synthetic configuration"), fullProject = true, compilationFiles = members),
            requiredRuleIds = requested,
            execution = TrustedExecution(absolute("never-executed-konanc"), listOf(commonTask, target.task), approved = false),
            outputRoot = absolute("unused-output-root"))
        return Fixture(input, NativeRunPlan(target.name, emptyList(), requiredTasks = setOf(commonTask, target.task),
            compilationModel = ExactCompilationModel(scope, modelDigest, mapOf(commonPath to "commonMain", nativePath to "iosMain"))))
    }

    private fun syntheticTaskOutput(tasks: Set<String>): String =
        tasks.joinToString("\n") { "> Task $it" } + "\nBUILD SUCCESSFUL in synthetic policy fixture"

    private fun outputCertificate(input: AnalysisInput, stdout: String, stderr: String): String =
        Digests.sha256(input.analysisKey() + "\n" + stdout + "\n" + stderr)

    private fun absolute(name: String): String =
        Path.of("").toAbsolutePath().root.resolve("ktchecker-native-fail-closed").resolve(name).normalize().toString()

    private val commonTask = ":selected:compileCommonMainKotlinMetadata"
    private val commonPath = "src/commonMain/Api.kt"
    private val nativePath = "src/iosMain/Platform.kt"
    private val targets = listOf(
        Target("iosArm64", ":selected:compileKotlinIosArm64", "ios_arm64"),
        Target("iosSimulatorArm64", ":selected:compileKotlinIosSimulatorArm64", "ios_simulator_arm64"),
    )
}

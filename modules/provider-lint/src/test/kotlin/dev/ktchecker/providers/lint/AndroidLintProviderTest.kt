package dev.ktchecker.providers.lint

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlinx.serialization.decodeFromString
import kotlin.test.*

/** Executes a report transport probe, not Lint detectors; detector controls are separate. */
class AndroidLintProviderTest {
    private val content = "ktc.android.lint.ContentDescription"
    private val hardcoded = "ktc.android.lint.HardcodedText"
    private val builtin = "com.android.tools.lint.checks.BuiltinIssueRegistry"
    private val transportScope = RuleContext("android", "main", ":fixture", "debug")
    private val transportModelDigest = Digests.sha256("transport model")

    @Test fun `normalized finding binds provider artifact immutable source target variant and related locations`() {
        val result = analyze(xml("""
            <issue id="ContentDescription" severity="Warning" message="specific source issue" explanation="provider explanation" includedVariants="debug">
                <location file="src/androidMain/Main.kt" line="2" column="3"/>
                <location file="src/commonMain/Api.kt" line="1" column="1"/>
            </issue>
        """))
        if (unsupported(result)) return
        assertEquals("incomplete", result.status, result.reasons.toString())
        val finding = result.findings.single()
        assertEquals(content, finding.ruleId)
        assertEquals("ContentDescription", finding.upstreamId)
        assertEquals("android-lint", finding.providerId)
        assertEquals(transportScope, finding.context)
        assertEquals("android", finding.context.target)
        assertEquals("debug", finding.context.compilation)
        assertEquals("src/androidMain/Main.kt", finding.location.path)
        assertEquals(2, finding.location.line)
        assertEquals(2, finding.location.column)
        assertTrue(Digests.valid(finding.location.fileDigest))
        assertEquals("src/commonMain/Api.kt", finding.relatedLocations.single().path)
        assertOwnership(finding, "src/androidMain/Main.kt", "androidMain")
        assertOwnership(finding, "src/commonMain/Api.kt", "commonMain")
        assertTrue(finding.evidence.any { it.summary.contains(builtin) && it.summary.contains("com.android.tools.lint:lint-checks:32.3.1") })
        assertTrue(result.coverage.all { it.state == "incomplete" && it.provenance == "executed" &&
            it.certificateId != null && !it.successful() && !it.tested && !it.qualified })
        assertFalse(result.complete())
    }

    @Test fun `common source finding retains exact selected Android leaf context without expanding suppression authority`() {
        val selected = transportScope.copy(sourceSet = "androidMain")
        val result = analyze(xml(issue("ContentDescription", "src/commonMain/Api.kt")),
            inputChange = { it.copy(context = it.context.copy(scope = selected)) },
            planChange = { it.copy(compilationModel = it.compilationModel!!.copy(scope = selected)) })
        if (unsupported(result)) return
        assertEquals("incomplete", result.status, result.reasons.toString())
        val finding = result.findings.single()
        assertEquals(selected, finding.context)
        assertTrue(result.coverage.all { it.context == selected })
        assertOwnership(finding, "src/commonMain/Api.kt", "commonMain")
        assertEquals(Digests.sha256(listOf(content, finding.location.path, finding.location.fileDigest,
            finding.location.line.toString(), finding.location.column.toString(), selected.id,
            finding.evidence.single { it.kind == "provider_artifact" }.digest).joinToString("\u0000")), finding.fingerprint)
        assertTrue(result.coverage.none { it.successful() || it.tested || it.qualified })
    }

    @Test fun `zero source diagnostics are incomplete not clean inventory coverage`() {
        val result = analyze(xml(""))
        if (unsupported(result)) return
        assertEquals("incomplete", result.status)
        assertTrue(result.findings.isEmpty())
        assertFalse(result.complete())
        assertTrue(result.coverage.none { it.successful() || it.tested || it.qualified })
    }

    @Test fun `unknown issue wrong variant unselected source ranges and missing locations fail closed`() {
        for ((issues, reason) in listOf(
            issue("UnknownFutureIssue") to "lint_unknown_issue_id:UnknownFutureIssue",
            issue("HardcodedText") to "lint_unselected_issue:$hardcoded",
            issue("ContentDescription", attrs = "includedVariants=\"release\"") to "lint_variant_binding_mismatch:$content",
            issue("ContentDescription", attrs = "excludedVariants=\"debug\"") to "lint_variant_binding_mismatch:$content",
            issue("ContentDescription", "missing.kt") to "location_not_in_manifest",
            issue("ContentDescription", "/outside.kt") to "location_outside_snapshot",
            issue("ContentDescription", "../Main.kt") to "relative_path_invalid",
            issue("ContentDescription", "src/test/Test.kt") to "lint_excluded_source_reported:src/test/Test.kt",
            issue("ContentDescription", line = 900) to "location_range_invalid",
            "<issue id='ContentDescription' severity='Warning' message='no source position'/>" to "lint_location_unavailable",
        )) {
            val result = analyze(xml(issues))
            if (unsupported(result)) return
            assertEquals("failed", result.status, issues)
            assertContains(result.reasons, reason)
            assertFalse(result.complete())
            assertTrue(result.coverage.none { it.certificateId != null || it.successful() || it.tested || it.qualified })
        }
    }

    @Test fun `captured sibling compilation member cannot be relabeled as this Android variant`() {
        val result = analyze(xml(issue("ContentDescription")), inputChange = {
            it.copy(context = it.context.copy(compilationFiles = listOf("src/commonMain/Api.kt")))
        }, planChange = {
            it.copy(compilationModel = it.compilationModel!!.copy(sourceSetByFile = mapOf("src/commonMain/Api.kt" to "commonMain")))
        })
        if (unsupported(result)) return
        assertEquals("failed", result.status)
        assertContains(result.reasons, "location_outside_compilation")
    }

    @Test fun `explicit source inclusion is distinct from silently ignoring a reported excluded source`() {
        val result = analyze(xml(issue("ContentDescription", "src/test/Test.kt")), inputChange = {
            it.copy(context = it.context.copy(compilationFiles = it.context.compilationFiles!! + "src/test/Test.kt"))
        }, planChange = {
            it.copy(explicitlyIncludedPaths = setOf("src/test/Test.kt"), compilationModel = it.compilationModel!!.copy(
                sourceSetByFile = it.compilationModel!!.sourceSetByFile + ("src/test/Test.kt" to "test")))
        })
        if (unsupported(result)) return
        assertEquals("incomplete", result.status)
        assertEquals(transportScope, result.findings.single().context)
        assertOwnership(result.findings.single(), "src/test/Test.kt", "test")
        assertEquals("src/test/Test.kt", result.findings.single().location.path)
    }

    @Test fun `disabled requested ID and wrong artifact version cannot discharge coverage`() {
        val disabled = analyze(xml(""), requested = listOf(content, hardcoded), planChange = {
            it.copy(checkedRuleIds = setOf(content, hardcoded), disabledRuleIds = setOf(hardcoded))
        })
        if (unsupported(disabled)) return
        assertEquals("incomplete", disabled.status)
        val row = disabled.coverage.single { it.ruleId == hardcoded }
        assertEquals("unavailable", row.state)
        assertEquals("lint_rule_disabled_by_provider_configuration", row.reason)
        assertEquals("not_executed", row.provenance)
        assertNull(row.certificateId)
        val mismatch = analyze(xml(""), planChange = { it.copy(lintVersion = "32.5.0") })
        assertEquals("unavailable", mismatch.status)
        assertContains(mismatch.reasons, "lint_provider_artifact_mismatch")
        val missingVersion = analyze(xml(""), planChange = { it.copy(lintVersion = "32.6.0") })
        assertEquals(listOf("lint_version_unavailable"), missingVersion.reasons)
    }

    @Test fun `provider nonzero crash absent empty malformed and wrong client reports are explicit failures`() {
        for ((report, code, stderr, reason) in listOf(
            Case(xml(""), 2, "", "lint_provider_exit:2"),
            Case(xml(""), 1, "", "lint_nonzero_without_source_error"),
            Case(xml(""), 0, "Exception in thread main: crash", "lint_provider_crashed"),
            Case(null, 0, "", "report_missing_or_unsafe"),
            Case("", 0, "", "lint_report_empty"),
            Case("malformed XML", 0, "", "lint_report_invalid_xml"),
            Case(xml("", "9.5.0"), 0, "", "lint_report_version_mismatch"),
        )) {
            val result = analyze(report, code, stderr = stderr)
            if (unsupported(result)) return
            assertEquals("failed", result.status, reason)
            assertContains(result.reasons, reason)
            assertTrue(result.coverage.none { it.successful() || it.certificateId != null })
        }
        val sourceError = analyze(xml(issue("ContentDescription", severity = "Error")), 1)
        assertEquals("incomplete", sourceError.status)
        assertEquals("error", sourceError.findings.single().severity)
    }

    @Test fun `aggregate cached skipped sibling variant and unrelated module tasks cannot prove exact lint execution`() {
        val analysis = ":fixture:lintAnalyzeDebug"
        val report = ":fixture:lintReportDebug"
        val required = setOf(analysis, report)
        val plan: (LintRunPlan) -> LintRunPlan = { it.copy(mode = "gradle", requiredTaskPaths = required) }
        val selection: (AnalysisInput) -> AnalysisInput = {
            it.copy(execution = it.execution!!.copy(arguments = it.execution!!.arguments + required))
        }
        for (tasks in listOf(
            "> Task :fixture:lint\nBUILD SUCCESSFUL", "> Task $analysis UP-TO-DATE\n> Task $report\nBUILD SUCCESSFUL",
            "> Task $analysis FROM-CACHE\n> Task $report\nBUILD SUCCESSFUL", "> Task $analysis SKIPPED\n> Task $report\nBUILD SUCCESSFUL",
            "> Task $analysis NO-SOURCE\n> Task $report\nBUILD SUCCESSFUL",
            "> Task :fixture:lintAnalyzeRelease\n> Task $report\nBUILD SUCCESSFUL",
            "> Task :other:lintAnalyzeDebug\n> Task $report\nBUILD SUCCESSFUL",
            "> Task $analysis\n> Task $analysis\n> Task $report\nBUILD SUCCESSFUL",
        )) {
            val result = analyze(xml(""), stdout = tasks, inputChange = selection, planChange = plan)
            if (unsupported(result)) return
            assertEquals("failed", result.status)
            assertContains(result.reasons, "lint_task_execution_unverified")
        }
        val actual = analyze(xml(""), stdout = "> Task $analysis\n> Task $report\nBUILD SUCCESSFUL",
            inputChange = selection, planChange = plan)
        assertEquals("incomplete", actual.status)
        val unrelated = analyze(xml(issue("ContentDescription", severity = "Error")), 1,
            stdout = "> Task $analysis\n> Task $report FAILED\n> Task :other:lintReportDebug FAILED",
            inputChange = selection, planChange = plan)
        assertEquals("failed", unrelated.status)
        assertContains(unrelated.reasons, "lint_unrelated_task_failure")
        val unfinished = analyze(xml(""), stdout = "> Task $analysis\n> Task $report", inputChange = selection, planChange = plan)
        assertContains(unfinished.reasons, "lint_gradle_completion_unverified")
    }

    @Test fun `missing exact compilation members or guessed directory ownership never authorizes lint sources`() {
        val missingMembers = analyze(xml(""), inputChange = { it.copy(context = it.context.copy(compilationFiles = null)) })
        assertEquals(listOf("compilation_members_unavailable"), missingMembers.reasons)
        val missingModel = analyze(xml(""), planChange = { it.copy(compilationModel = null) })
        assertEquals(listOf("compilation_model_unavailable"), missingModel.reasons)
        val prefixGuess = analyze(xml(""), planChange = { it.copy(compilationModel = it.compilationModel!!.copy(
            sourceSetByFile = mapOf("src/androidMain/" to "androidMain", "src/commonMain/" to "commonMain"))) })
        assertEquals(listOf("compilation_source_ownership_unavailable"), prefixGuess.reasons)
        assertTrue(listOf(missingMembers, missingModel, prefixGuess).all { it.coverage.none { row -> row.certificateId != null } })
    }

    @Test fun `captured related locations need exact ownership in the selected compilation too`() {
        val result = analyze(xml("""
            <issue id="ContentDescription" severity="Warning" message="specific source issue">
                <location file="src/androidMain/Main.kt" line="2" column="3"/>
                <location file="src/commonMain/Api.kt" line="1" column="1"/>
            </issue>
        """), inputChange = { it.copy(context = it.context.copy(compilationFiles = listOf("src/androidMain/Main.kt"))) },
            planChange = { it.copy(compilationModel = it.compilationModel!!.copy(sourceSetByFile = mapOf("src/androidMain/Main.kt" to "androidMain"))) })
        if (unsupported(result)) return
        assertEquals("failed", result.status)
        assertContains(result.reasons, "location_outside_compilation")
        assertTrue(result.findings.isEmpty())
        assertTrue(result.coverage.none { it.certificateId != null })
    }

    @Test fun `Gradle lint admission rejects unselected paths basenames other modules and aggregates`() {
        val required = setOf(":fixture:lintAnalyzeDebug", ":fixture:lintReportDebug")
        val unselected = analyze(xml(""), planChange = { it.copy(mode = "gradle", requiredTaskPaths = required) })
        assertEquals(listOf("gradle_exact_task_not_selected"), unselected.reasons)
        for ((paths, reason) in listOf(
            setOf("lintAnalyzeDebug") to "gradle_exact_task_model_required",
            setOf(":other:lintAnalyzeDebug") to "gradle_task_module_mismatch",
            setOf(":fixture:lint") to "lint_aggregate_task_not_detector_proof",
        )) {
            val result = analyze(xml(""), inputChange = { it.copy(execution = it.execution!!.copy(arguments = it.execution!!.arguments + paths)) },
                planChange = { it.copy(mode = "gradle", requiredTaskPaths = paths) })
            assertEquals(listOf(reason), result.reasons)
            assertTrue(result.coverage.none { it.certificateId != null })
        }
    }

    @Test fun `KMP driver has no guessed lint task fallback when exact model paths are missing`() {
        val repository = Path.of(requireNotNull(System.getProperty("ktchecker.repository")))
        val report = Files.createTempDirectory("ktchecker-lint-missing-model-").resolve("result.json")
        LintFixtureMain.main(arrayOf("--fixture", repository.resolve("fixtures/kmp").toString(), "--case", "positive",
            "--result", report.toString(), "--lint-classpath", requireNotNull(System.getProperty("ktchecker.lint.classpath")),
            "--gradle", repository.resolve("does-not-exist").toString()))
        val result = ContractJson.decodeFromString<FixtureCheckEnvelope>(Files.readString(report))
        assertEquals("unavailable", result.state)
        assertTrue(Digests.valid(result.snapshotId))
        assertEquals(listOf("lint_fixture_exact_tasks_required"), result.providerResult.reasons)
        assertTrue(result.providerResult.coverage.isEmpty())
        assertNull(result.modelBinding)
    }

    @Test fun `transport approval missing frozen model and relabeled context never grant authority`() {
        val notAuthorized = analyze(xml(""), authorized = false)
        assertEquals("unavailable", notAuthorized.status)
        assertContains(notAuthorized.reasons, "execution_not_authorized")
        val noModel = analyze(xml(""), inputChange = { it.copy(context = it.context.copy(modelDigest = null)) })
        assertEquals(listOf("lint_resolved_frozen_model_required"), noModel.reasons)
        val ios = analyze(xml(""), inputChange = { it.copy(context = it.context.copy(scope = it.context.scope.copy(target = "iosArm64"))) })
        assertEquals(listOf("android_compilation_required"), ios.reasons)
        val stalePermit = analyze(xml(""), relabelAfterPermit = true)
        assertContains(stalePermit.reasons, "compilation_model_binding_mismatch")
        assertTrue(listOf(notAuthorized, noModel, ios, stalePermit).all { !it.complete() && it.coverage.none { row -> row.successful() } })
    }

    private data class Case(val report: String?, val code: Int, val stderr: String, val reason: String)

    private fun analyze(report: String?, exitCode: Int = 0, stdout: String = "", stderr: String = "",
                        requested: List<String> = listOf(content), authorized: Boolean = true,
                        relabelAfterPermit: Boolean = false, inputChange: (AnalysisInput) -> AnalysisInput = { it },
                        planChange: (LintRunPlan) -> LintRunPlan = { it }): ProviderResult {
        val work = Files.createTempDirectory("ktchecker-lint-transport-")
        val producer = work.resolve("fixture")
        for (path in listOf("src/androidMain/Main.kt", "src/commonMain/Api.kt", "src/test/Test.kt")) {
            val file = producer.resolve(path)
            Files.createDirectories(file.parent)
            Files.writeString(file, "package fixture\nfun declaration() = Unit\n")
        }
        val frozen = FixtureSnapshots.materialize(producer, work.resolve("frozen"))
        val checks = requireNotNull(System.getProperty("ktchecker.lint.classpath")) { "Test task must resolve the isolated pinned Lint runtime" }
            .split(File.pathSeparator).map { Path.of(it) }.single { it.fileName.toString() == "lint-checks-32.3.1.jar" }
        val registry = LintRegistryArtifact(builtin, ArtifactIdentity("com.android.tools.lint:lint-checks:32.3.1", fileDigest(checks), checks.toString()))
        val executable = Path.of(System.getProperty("java.home"), "bin/java").toRealPath()
        val classpath = listOf(LintReportProbe::class.java, kotlin.Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(File.pathSeparator)
        fun encoded(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())
        val execution = TrustedExecution(executable.toString(), listOf("-cp", classpath, LintReportProbe::class.java.name,
            "{output}/lint-results.xml", report?.let(::encoded) ?: "MISSING", exitCode.toString(), encoded(stdout), encoded(stderr)),
            true, fileDigest(executable), timeoutMillis = 10000)
        val scope = transportScope
        val modelDigest = transportModelDigest
        val sourceSetByFile = mapOf("src/androidMain/Main.kt" to "androidMain", "src/commonMain/Api.kt" to "commonMain")
        val context = AnalysisContext(scope,
            modelDigest = modelDigest, dependencyDigest = Digests.sha256("transport dependency"),
            toolchainDigest = registry.artifact.digest, configurationDigest = Digests.sha256("transport configuration"), fullProject = true,
            compilationFiles = sourceSetByFile.keys.sorted())
        val input = inputChange(AnalysisInput(frozen.manifest, frozen.root.toString(), context, requested,
            execution = execution, outputRoot = work.resolve("outputs").toString()))
        val authority = if (authorized) ExactExecutionAuthority(ExecutionPermit(input.execution!!, frozen.manifest.snapshotId, frozen.root,
            input.analysisKey(), frozen.verify)) else ExecutionAuthority.DENY
        val plan = planChange(LintRunPlan("32.3.1", listOf(registry), setOf(content),
            compilationModel = ExactCompilationModel(scope, modelDigest, sourceSetByFile)))
        return AndroidLintProvider(authority, plan).analyze(if (relabelAfterPermit) input.copy(
            context = input.context.copy(scope = input.context.scope.copy(compilation = "release"))) else input)
    }

    private fun unsupported(result: ProviderResult): Boolean {
        if (result.reasons != listOf("process_group_containment_unavailable")) return false
        assertEquals("unavailable", result.status)
        assertTrue(result.coverage.none { it.provenance == "executed" || it.successful() })
        return true
    }

    private fun assertOwnership(finding: Finding, path: String, sourceSet: String) {
        val evidence = finding.evidence.single { it.kind == "compilation_source_membership" && it.artifactRef == path }
        assertEquals(transportModelDigest, evidence.digest)
        assertContains(evidence.summary, "Imported source set $sourceSet;")
        assertContains(evidence.summary, "selected analysis context ${finding.context.id};")
        assertContains(evidence.summary, "immutable snapshot sha256:")
        assertContains(evidence.summary, "informational ownership only, not cross-context suppression authority")
    }

    private fun xml(issues: String, client: String = "9.3.1"): String = "<issues format=\"6\" by=\"lint $client\">$issues</issues>"
    private fun issue(upstream: String, path: String = "src/androidMain/Main.kt", severity: String = "Warning",
                      line: Int = 1, attrs: String = ""): String =
        """<issue id="$upstream" severity="$severity" message="synthetic transport" $attrs><location file="$path" line="$line" column="1"/></issue>"""
}

object LintReportProbe {
    @JvmStatic fun main(args: Array<String>) {
        if (args[1] != "MISSING") Files.write(Path.of(args[0]), Base64.getDecoder().decode(args[1]))
        System.out.print(String(Base64.getDecoder().decode(args[3]), Charsets.UTF_8))
        System.err.print(String(Base64.getDecoder().decode(args[4]), Charsets.UTF_8))
        kotlin.system.exitProcess(args[2].toInt())
    }
}

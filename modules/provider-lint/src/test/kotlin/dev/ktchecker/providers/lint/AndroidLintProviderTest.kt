package dev.ktchecker.providers.lint

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*

/** Executes a report transport probe, not Lint detectors; detector controls are separate. */
class AndroidLintProviderTest {
    private val content = "ktc.android.lint.ContentDescription"
    private val hardcoded = "ktc.android.lint.HardcodedText"
    private val builtin = "com.android.tools.lint.checks.BuiltinIssueRegistry"

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
        assertEquals("androidMain", finding.context.sourceSet)
        assertEquals("android", finding.context.target)
        assertEquals("debug", finding.context.compilation)
        assertEquals("src/androidMain/Main.kt", finding.location.path)
        assertEquals(2, finding.location.line)
        assertEquals(2, finding.location.column)
        assertTrue(Digests.valid(finding.location.fileDigest))
        assertEquals("src/commonMain/Api.kt", finding.relatedLocations.single().path)
        assertTrue(finding.evidence.any { it.summary.contains(builtin) && it.summary.contains("com.android.tools.lint:lint-checks:32.3.1") })
        assertTrue(result.coverage.all { it.state == "incomplete" && it.provenance == "executed" &&
            it.certificateId != null && !it.successful() && !it.tested && !it.qualified })
        assertFalse(result.complete())
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
        })
        if (unsupported(result)) return
        assertEquals("failed", result.status)
        assertContains(result.reasons, "lint_location_outside_compilation")
    }

    @Test fun `explicit source inclusion is distinct from silently ignoring a reported excluded source`() {
        val result = analyze(xml(issue("ContentDescription", "src/test/Test.kt")), planChange = {
            it.copy(explicitlyIncludedPaths = setOf("src/test/Test.kt"), sourceSets = mapOf("src/test/" to "test"))
        })
        if (unsupported(result)) return
        assertEquals("incomplete", result.status)
        assertEquals("test", result.findings.single().context.sourceSet)
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

    @Test fun `aggregate cached skipped and unrelated failing Gradle tasks cannot prove lint execution`() {
        for (tasks in listOf(
            "> Task :lint\nBUILD SUCCESSFUL", "> Task :lintAnalyzeAndroidMain UP-TO-DATE\nBUILD SUCCESSFUL",
            "> Task :lintAnalyzeAndroidMain FROM-CACHE\nBUILD SUCCESSFUL", "> Task :lintAnalyzeAndroidMain SKIPPED\nBUILD SUCCESSFUL",
        )) {
            val result = analyze(xml(""), stdout = tasks, planChange = { it.copy(requiredTaskPrefixes = setOf("lint")) })
            if (unsupported(result)) return
            assertEquals("failed", result.status)
            assertContains(result.reasons, "lint_task_execution_unverified")
        }
        val actual = analyze(xml(""), stdout = "> Task :lintAnalyzeAndroidMain\n> Task :lint\nBUILD SUCCESSFUL",
            planChange = { it.copy(requiredTaskPrefixes = setOf("lint")) })
        assertEquals("incomplete", actual.status)
        val unrelated = analyze(xml(issue("ContentDescription", severity = "Error")), 1,
            stdout = "> Task :lintAnalyzeAndroidMain\n> Task :unrelated FAILED",
            planChange = { it.copy(requiredTaskPrefixes = setOf("lint")) })
        assertEquals("failed", unrelated.status)
        assertContains(unrelated.reasons, "lint_unrelated_task_failure")
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
        assertContains(stalePermit.reasons, "execution_not_authorized")
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
        val context = AnalysisContext(RuleContext("android", "main", ":fixture", "debug"),
            modelDigest = Digests.sha256("transport model"), dependencyDigest = Digests.sha256("transport dependency"),
            toolchainDigest = registry.artifact.digest, configurationDigest = Digests.sha256("transport configuration"), fullProject = true)
        val input = inputChange(AnalysisInput(frozen.manifest, frozen.root.toString(), context, requested,
            execution = execution, outputRoot = work.resolve("outputs").toString()))
        val authority = if (authorized) ExactExecutionAuthority(ExecutionPermit(execution, frozen.manifest.snapshotId, frozen.root,
            input.analysisKey(), frozen.verify)) else ExecutionAuthority.DENY
        val plan = planChange(LintRunPlan("32.3.1", listOf(registry), setOf(content),
            sourceSets = mapOf("src/androidMain/" to "androidMain", "src/commonMain/" to "commonMain")))
        return AndroidLintProvider(authority, plan).analyze(if (relabelAfterPermit) input.copy(
            context = input.context.copy(scope = input.context.scope.copy(compilation = "release"))) else input)
    }

    private fun unsupported(result: ProviderResult): Boolean {
        if (result.reasons != listOf("process_group_containment_unavailable")) return false
        assertEquals("unavailable", result.status)
        assertTrue(result.coverage.none { it.provenance == "executed" || it.successful() })
        return true
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

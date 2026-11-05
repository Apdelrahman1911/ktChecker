package dev.ktchecker.compiler.live

import dev.ktchecker.contracts.*
import dev.ktchecker.workerprotocol.ApplicationWire as Wire
import dev.ktchecker.workerprotocol.CompilerJobWire as CompilerWire
import java.nio.file.Path
import kotlin.test.*

/** Detached DTO rendering controls, NOT observations of compiler execution or native cleanup. */
class CompilerLiveRenderingTest {
    @Test fun namedDiagnosticMapsToOriginalSourceAndRequestEvidenceWithoutCertificate() {
        val f = RenderingControl()
        val diagnostic = f.diagnostic("WARNING", "SENSELESS_COMPARISON", "Condition is always true", column = 5)
        val result = f.render(listOf(diagnostic))
        val finding = result.findings.single()
        assertEquals(f.rule, finding.ruleId)
        assertEquals("SENSELESS_COMPARISON", finding.upstreamId)
        assertEquals("Condition is always true", finding.diagnosis)
        assertEquals("Fixture.kt", finding.location.path)
        assertEquals(f.original.snapshot.files.single().digest, finding.location.fileDigest)
        assertEquals(4, finding.location.column)
        assertEquals(finding.location.column, finding.location.endColumn)
        assertEquals(f.requestDigest, finding.evidence.single { it.kind == "application-request" }.digest)
        assertEquals("partial", result.status)
        assertEquals("unqualified", result.coverage.single().state)
        assertNull(result.coverage.single().certificateId)
        assertFalse(result.coverage.single().qualified)
        assertFalse(result.coverage.single().tested)
        assertFalse(result.completeFor(f.original))
        assertTrue("compiler_live_semantic_extraction_not_authorized" in result.reasons)
    }

    @Test fun cleanObservationStillHasEveryRequiredUnqualifiedRow() {
        val f = RenderingControl()
        val result = f.render(emptyList())
        assertEquals("partial", result.status)
        assertTrue(result.findings.isEmpty())
        assertTrue(result.rawDiagnostics.isEmpty())
        assertEquals(f.original.requiredRuleIds, result.coverage.map { it.ruleId })
        assertTrue(result.coverage.all { it.state == "unqualified" && it.certificateId == null && !it.implemented && !it.qualified })
        assertFalse(result.complete())
        assertNull(result.metrics.cpuMillis)
        assertNull(result.metrics.peakRssBytes)
    }

    @Test fun unknownNamedCompilerErrorIsExplicitRawFailureNotDroppedOrMadeClean() {
        val f = RenderingControl()
        val result = f.render(listOf(f.diagnostic("ERROR", "FUTURE_COMPILER_ERROR", "Unknown future diagnostic")), code = 1)
        assertEquals("failed", result.status)
        assertTrue(result.findings.isEmpty())
        assertEquals("FUTURE_COMPILER_ERROR", result.rawDiagnostics.single().upstreamId)
        assertEquals("Unknown future diagnostic", result.rawDiagnostics.single().message)
        assertEquals("error", result.rawDiagnostics.single().severity)
        assertEquals("Fixture.kt", result.rawDiagnostics.single().location?.path)
        assertEquals("unqualified", result.coverage.single().state)
        assertTrue("compiler_live_raw_diagnostics_unresolved" in result.reasons)
        assertFalse(result.completeFor(f.original))
    }

    @Test fun mappedDiagnosticOutsideRequestedRulesRemainsVisibleRaw() {
        val f = RenderingControl()
        val result = f.render(listOf(f.diagnostic("WARNING", "UNNECESSARY_NOT_NULL_ASSERTION", "Unnecessary assertion")))
        assertTrue(result.findings.isEmpty())
        assertEquals("UNNECESSARY_NOT_NULL_ASSERTION", result.rawDiagnostics.single().upstreamId)
        assertTrue("compiler_live_diagnostic_outside_required_rules" in result.reasons)
        assertEquals(f.original.requiredRuleIds, result.coverage.map { it.ruleId })
    }

    @Test fun unknownRequiredRuleIsUnsupportedNotRemovedFromObligations() {
        val f = RenderingControl(extraRules = listOf("ktc.nonexistent.required_rule"))
        val result = f.render(emptyList())
        assertEquals(f.original.requiredRuleIds, result.coverage.map { it.ruleId })
        assertEquals(listOf("unqualified", "unsupported"), result.coverage.map { it.state })
        assertTrue(result.coverage.all { it.certificateId == null && !it.qualified })
    }

    @Test fun severityVariantsRetainDiagnosticSeverityAndNeverClaimQualification() {
        val f = RenderingControl()
        for (severity in listOf("WARNING", "STRONG_WARNING", "MANDATORY_WARNING")) {
            val result = f.render(listOf(f.diagnostic(severity, "SENSELESS_COMPARISON", "Actual warning")))
            assertEquals("warning", result.findings.single().severity)
            assertFalse(result.complete())
        }
        val error = f.render(listOf(f.diagnostic("ERROR", "SENSELESS_COMPARISON", "Diagnostic configured as error")), code = 1)
        assertEquals("error", error.findings.single().severity)
        assertEquals("failed", error.status)
        assertNull(error.coverage.single().certificateId)
    }

    @Test fun locationlessWarningsRemainRawAndPrivateLoggingTextIsNotPublished() {
        val f = RenderingControl()
        val warning = CompilerWire.Diagnostic("WARNING", "Global warning", "", -1, -1, "SENSELESS_COMPARISON")
        val logging = CompilerWire.Diagnostic("LOGGING", "private /owner/location/jdk detail", "", -1, -1, "")
        val result = f.render(listOf(warning, logging))
        assertTrue(result.findings.isEmpty())
        assertEquals(2, result.rawDiagnostics.size)
        assertEquals("Global warning", result.rawDiagnostics.first().message)
        assertTrue(result.rawDiagnostics.none { "/owner/location" in it.message })
        assertTrue("compiler_live_informational_logging_redacted" in result.reasons)
        assertFalse(result.complete())
    }

    @Test fun nonLoggingDiagnosticTextIsPreservedAndIsNotABlanketPrivatePathSanitizer() {
        val f = RenderingControl()
        val privateJdk = Path.of(f.original.materializedRoot).resolve("owner-private-jdk").toString()
        val privateSource = Path.of(f.sourceRoot.path()).resolve("Fixture.kt").toString()
        val message = "Synthetic diagnostic mentions $privateJdk and $privateSource"
        val named = f.render(listOf(f.diagnostic("WARNING", "SENSELESS_COMPARISON", message)))
        assertEquals(message, named.findings.single().diagnosis)
        val raw = f.render(listOf(CompilerWire.Diagnostic("WARNING", message, "", -1, -1, "")))
        assertEquals(message, raw.rawDiagnostics.single().message)
        assertTrue(named.findings.single().evidence.none { privateJdk in it.summary || privateSource in it.summary })
        assertFalse(named.complete())
        assertFalse(raw.complete())
    }

    @Test fun foreignPathsOrOutOfSourceCoordinatesRejectWholeRendering() {
        val f = RenderingControl()
        val values = listOf(f.diagnostic("WARNING", "SENSELESS_COMPARISON", "message",
            path = Path.of(f.original.materializedRoot).resolve("foreign").resolve("Fixture.kt").toString()),
            f.diagnostic("WARNING", "SENSELESS_COMPARISON", "message", line = 100),
            f.diagnostic("WARNING", "SENSELESS_COMPARISON", "message", column = 200))
        values.forEach { assertFailsWith<CompilerApplicationProblem> { f.render(listOf(it)) } }
    }

    @Test fun utf16PointAtLineEndIsAcceptedButNotInventedIntoAnEndSpan() {
        val f = RenderingControl(text = "val emoji = \"😀\"")
        val result = f.render(listOf(f.diagnostic("WARNING", "SENSELESS_COMPARISON", "message", column = f.text.length + 1)))
        val point = result.findings.single().location
        assertEquals(f.text.length, point.column)
        assertEquals(point.column, point.endColumn)
        assertFailsWith<CompilerApplicationProblem> {
            f.render(listOf(f.diagnostic("WARNING", "SENSELESS_COMPARISON", "message", column = f.text.length + 2)))
        }
    }

    @Test fun rootDisposalResetFailureOrUnsealedResultCannotRenderSuccess() {
        val f = RenderingControl()
        val good = f.observation(emptyList())
        val bad = CompilerWire.Observation(good.compilerVersion(), good.argvDigest(), good.memberDigest(), good.jobRoot(),
            good.sourceRoot(), good.outputRoot(), true, 0, 0, false, false, good.beforeReset(),
            CompilerWire.Reset(false, true, true, true), true, true, true, CompilerWire.FailureCode.RESET_FAILED, emptyList())
        assertFails { f.renderObservation(bad) }
        assertFailsWith<CompilerApplicationProblem> {
            renderLiveObservation(f.original, f.text, f.sourceRoot, f.installation,
                f.result(good, Wire.ResultState.OUTCOME_UNKNOWN), good)
        }
    }

    @Test fun changedOriginalKeyOrSourceBytesCannotReceiveBoundFinding() {
        val f = RenderingControl()
        val observation = f.observation(listOf(f.diagnostic("WARNING", "SENSELESS_COMPARISON", "message")))
        val result = f.result(observation)
        assertFailsWith<CompilerApplicationProblem> {
            renderLiveObservation(f.original.copy(changedPaths = listOf("Fixture.kt")), f.text, f.sourceRoot,
                f.installation, result, observation)
        }
        assertFailsWith<CompilerApplicationProblem> {
            renderLiveObservation(f.original, f.text.replace('1', '2'), f.sourceRoot, f.installation, result, observation)
        }
    }

    @Test fun resultCollectionsAndNestedFindingEvidenceAreDetachedAndImmutable() {
        val f = RenderingControl()
        val source = mutableListOf(f.diagnostic("WARNING", "SENSELESS_COMPARISON", "message"))
        val result = f.render(source)
        source.clear()
        assertEquals(1, result.findings.size)
        assertFailsWith<UnsupportedOperationException> { (result.findings as MutableList<Finding>).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.coverage as MutableList<Coverage>).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.reasons as MutableList<String>).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.findings.single().evidence as MutableList<Evidence>).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.findings.single().verification as MutableList<Verification>).clear() }
    }
}

private class RenderingControl(val text: String = "fun value() = 1\n", extraRules: List<String> = emptyList()) {
    val rule = "ktc.compiler.common.senseless_comparison"
    val installation = Digests.sha256("synthetic-installation-not-executed")
    val requestDigest = Digests.sha256("synthetic-request-not-executed")
    private val base = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().resolve("ktc-live-rendering-control")
    private val jobPath = base.resolve("generation").resolve("jobs").resolve("1")
    val sourceRoot = Wire.Root(jobPath.resolve("sources").toString(), "synthetic-source-root-key")
    val original = AnalysisInput(SnapshotManifest(Digests.sha256("synthetic-snapshot"), "workspace", "worktree",
        listOf(SnapshotFile("Fixture.kt", Digests.sha256(text), text.toByteArray(Charsets.UTF_8).size.toLong())), "owner",
        declaredRoots = listOf(".")), base.resolve("original").toString(), AnalysisContext(compilerVersion = "2.4.21"),
        requiredRuleIds = listOf(rule) + extraRules)

    fun diagnostic(severity: String, id: String, message: String, line: Int = 1, column: Int = 1,
        path: String = Path.of(sourceRoot.path()).resolve("Fixture.kt").toString()) = CompilerWire.Diagnostic(severity, message, path, line, column, id)

    fun observation(diagnostics: List<CompilerWire.Diagnostic>, code: Int = 0): CompilerWire.Observation = CompilerWire.Observation(
        "2.4.21", Digests.sha256("synthetic-argv"), CompilerWire.membersDigest(listOf(CompilerWire.Source("Fixture.kt",
            Digests.sha256(text), text.toByteArray(Charsets.UTF_8).size.toLong(), "synthetic-source-file-key"))),
        Wire.Root(jobPath.toString(), "synthetic-job-key"), sourceRoot,
        Wire.Root(jobPath.resolve("classes").toString(), "synthetic-output-key"), true, code, 1, true, false,
        CompilerWire.Reset(true, true, true, true), CompilerWire.Reset(true, true, true, true), true, true, true,
        CompilerWire.FailureCode.NONE, diagnostics)

    fun result(observation: CompilerWire.Observation, state: Wire.ResultState = Wire.ResultState.APPLICATION_SEALED) = Wire.Result(
        "a".repeat(32), Digests.sha256("synthetic-hello"), 1, Wire.jobId("a".repeat(32), 1), requestDigest,
        original.analysisKey(), Digests.sha256("synthetic-effective"), "b".repeat(32), state, CompilerWire.encodeObservation(observation))

    fun render(diagnostics: List<CompilerWire.Diagnostic>, code: Int = 0): ProviderResult = renderObservation(observation(diagnostics, code))
    fun renderObservation(observation: CompilerWire.Observation): ProviderResult = renderLiveObservation(original, text,
        sourceRoot, installation, result(observation), observation)
}

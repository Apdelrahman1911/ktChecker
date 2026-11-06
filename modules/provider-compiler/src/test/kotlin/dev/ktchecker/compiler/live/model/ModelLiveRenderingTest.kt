package dev.ktchecker.compiler.live.model

import dev.ktchecker.compiler.CompilerRuleRegistry
import dev.ktchecker.compiler.live.CompilerApplicationProblem
import dev.ktchecker.contracts.*
import dev.ktchecker.workerprotocol.ApplicationWire as Wire
import dev.ktchecker.workerprotocol.CompilerModelWire as ModelWire
import java.nio.file.Path
import kotlin.test.*

/** Detached synthetic DTO controls, NOT observations of compiler execution, ARM or retirement. */
class ModelLiveRenderingTest {
    @Test fun multiSourceDiagnosticsMapOnlyExactMembersAndRetainModelAndRequestEvidence() {
        val f = ModelRenderingControl()
        val result = f.render(listOf(f.diagnostic(path = "src/B.kt", column = 3),
            f.diagnostic(path = Path.of(f.sourceRoot.path()).resolve("src/A.kt").toString(), column = 5)))
        assertEquals(listOf("src/B.kt", "src/A.kt"), result.findings.map { it.location.path })
        assertEquals(listOf(2, 4), result.findings.map { it.location.column })
        result.findings.forEach { finding ->
            assertEquals(f.original.snapshot.files.single { it.path == finding.location.path }.digest, finding.location.fileDigest)
            assertEquals(finding.location.line, finding.location.endLine)
            assertEquals(finding.location.column, finding.location.endColumn)
            assertEquals(f.original.context.scope, finding.context)
            assertEquals("customJvm", finding.context.target)
            assertEquals(f.bindingDigest, finding.evidence.single { it.kind == "model-binding" }.digest)
            assertEquals(f.requestDigest, finding.evidence.single { it.kind == "application-request" }.digest)
            assertEquals(f.original.analysisKey(), finding.semanticDigest)
            assertTrue(finding.verification.all { it.state == "not_run" && it.command.isEmpty() })
        }
        assertEquals("partial", result.status)
        assertTrue(result.coverage.all { it.state == "unqualified" && !it.qualified && !it.tested && it.certificateId == null })
        assertFalse(result.completeFor(f.original))
    }

    @Test fun cleanObservationNeverCertifiesTheWholeModelOrDropsRequiredRows() {
        val native = CompilerRuleRegistry.load().mappings.first { it.backend == "native" }
        val f = ModelRenderingControl(extraRules = listOf(native.ruleId, "unknown.required.row"))
        val result = f.render(emptyList())
        assertEquals(f.original.requiredRuleIds, result.coverage.map { it.ruleId })
        assertEquals(listOf("unqualified", "unsupported", "unsupported"), result.coverage.map { it.state })
        assertTrue(result.coverage.all { !it.qualified && !it.implemented && it.certificateId == null })
        assertTrue(result.findings.isEmpty())
        assertTrue(result.rawDiagnostics.isEmpty())
        assertEquals("partial", result.status)
        assertFalse(result.complete())
        assertTrue("compiler_model_live_semantic_extraction_not_authorized" in result.reasons)
    }

    @Test fun unknownIdAndGlobalWerrorErrorsStayRawAndFailedRatherThanClean() {
        val f = ModelRenderingControl(options = listOf("-jvm-target", "21", "-Werror"))
        val diagnostics = listOf(f.diagnostic("ERROR", "FUTURE_COMPILER_ERROR", "Unknown future compiler error"),
            ModelWire.Diagnostic("ERROR", "warnings found and -Werror specified", "", -1, -1, ""))
        val result = f.render(diagnostics, 1)
        assertEquals("failed", result.status)
        assertTrue(result.findings.isEmpty())
        assertEquals(2, result.rawDiagnostics.size)
        assertEquals("FUTURE_COMPILER_ERROR", result.rawDiagnostics.first().upstreamId)
        assertEquals("src/A.kt", result.rawDiagnostics.first().location?.path)
        assertEquals("warnings found and -Werror specified", result.rawDiagnostics.last().message)
        assertEquals("error", result.rawDiagnostics.last().severity)
        assertNull(result.rawDiagnostics.last().upstreamId)
        assertNull(result.rawDiagnostics.last().location)
        assertTrue("compiler_model_live_global_error_unresolved" in result.reasons)
        assertTrue("compiler_model_live_raw_diagnostics_unresolved" in result.reasons)
        assertTrue(result.coverage.all { it.state == "unqualified" && !it.qualified })
        assertFalse(result.completeFor(f.original))
    }

    @Test fun codeZeroWithErrorOrCodeOneWithoutErrorCannotRenderAnAcceptedOutcome() {
        val f = ModelRenderingControl()
        assertFails { f.render(listOf(f.diagnostic("ERROR")), 0) }
        assertFails { f.render(emptyList(), 1) }
        assertFails { f.render(listOf(f.diagnostic("WARNING")), 1) }
    }

    @Test fun mappedErrorRetainsActualFailureAndWarningSeverityVariantsStayWarnings() {
        val f = ModelRenderingControl()
        for (severity in listOf("WARNING", "STRONG_WARNING", "MANDATORY_WARNING")) {
            val result = f.render(listOf(f.diagnostic(severity)))
            assertEquals("warning", result.findings.single().severity)
            assertEquals("partial", result.status)
        }
        val failed = f.render(listOf(f.diagnostic("ERROR")), 1)
        assertEquals("error", failed.findings.single().severity)
        assertEquals("failed", failed.status)
        assertFalse(failed.complete())
    }

    @Test fun outOfPlanNativeAndUnlocatedMappedDiagnosticsStayVisibleRaw() {
        val f = ModelRenderingControl()
        val native = CompilerRuleRegistry.load().mappings.first { it.backend == "native" }
        val result = f.render(listOf(f.diagnostic(id = "UNNECESSARY_NOT_NULL_ASSERTION"),
            f.diagnostic(id = native.upstreamId), ModelWire.Diagnostic("WARNING", "Global mapped warning", "", -1, -1, "SENSELESS_COMPARISON")))
        assertTrue(result.findings.isEmpty())
        assertEquals(3, result.rawDiagnostics.size)
        assertTrue("compiler_model_live_diagnostic_outside_required_rules" in result.reasons)
        assertTrue("compiler_model_live_non_jvm_diagnostic" in result.reasons)
        assertNull(result.rawDiagnostics.last().location)
        assertFalse(result.completeFor(f.original))
    }

    @Test fun privateLoggingIsRedactedButNonloggingCompilerMessageTextIsPreserved() {
        val f = ModelRenderingControl()
        val privateMessage = "Synthetic compiler message mentions /private/owner/jdk and /private/job/sources"
        val result = f.render(listOf(ModelWire.Diagnostic("LOGGING", privateMessage, "", -1, -1, ""),
            ModelWire.Diagnostic("INFO", "Global compiler info", "", -1, -1, ""), f.diagnostic(message = privateMessage)))
        assertEquals(privateMessage, result.findings.single().diagnosis)
        assertTrue(result.rawDiagnostics.first().message.contains("private log text is not published"))
        assertFalse(result.rawDiagnostics.first().message.contains("/private/owner"))
        assertEquals("Global compiler info", result.rawDiagnostics.last().message)
        assertTrue("compiler_model_live_informational_logging_redacted" in result.reasons)
        assertFalse(result.complete())
    }

    @Test fun basenameAliasesNoncompiledFilesAndOriginalArchivePathsCannotBecomeLocations() {
        val f = ModelRenderingControl()
        val paths = listOf("A.kt", "src/../src/A.kt", "build.gradle.kts", "resources/opaque.bin",
            Path.of(f.original.materializedRoot).resolve("src/A.kt").toString(),
            Path.of(f.sourceRoot.path()).resolve("other/A.kt").toString())
        paths.forEach { path -> expectModelLiveFailure("compiler_model_live_diagnostic_source_mismatch") {
            f.render(listOf(f.diagnostic(path = path)))
        } }
    }

    @Test fun crlfAndSupplementaryCharactersUseExactUtf16PointsWithoutClipping() {
        val f = ModelRenderingControl(firstText = "a😀b\r\nnext\r\n")
        val atEnd = f.render(listOf(f.diagnostic(line = 1, column = 5)))
        assertEquals(4, atEnd.findings.single().location.column) // a + two UTF16 emoji units + b.
        assertEquals(atEnd.findings.single().location.column, atEnd.findings.single().location.endColumn)
        assertEquals(4, f.render(listOf(f.diagnostic(line = 2, column = 5))).findings.single().location.column)
        assertEquals(0, f.render(listOf(f.diagnostic(line = 3, column = 1))).findings.single().location.column)
        for ((line, column) in listOf(1 to 6, 2 to 6, 3 to 2, 4 to 1)) {
            expectModelLiveFailure("compiler_model_live_diagnostic_location_invalid") { f.render(listOf(f.diagnostic(line = line, column = column))) }
        }
    }

    @Test fun unsupportedSelectedBareCrNeverGetsNormalizedOrRenderedAsClean() {
        for (text in listOf("one\rtwo", "one\r", "one\r\r\n")) {
            val f = ModelRenderingControl(firstText = text)
            expectModelLiveFailure("compiler_model_live_bare_cr_unsupported") { f.render(emptyList()) }
            assertEquals(text, f.texts.getValue("src/A.kt"))
        }
    }

    @Test fun sourceMapBytesOriginalKeyAndSealedStateAreMandatory() {
        val f = ModelRenderingControl()
        val observation = f.observation(listOf(f.diagnostic()))
        val result = f.result(observation)
        expectModelLiveFailure("compiler_model_live_result_identity_mismatch") {
            f.renderObservation(observation, result = f.result(observation, Wire.ResultState.OUTCOME_UNKNOWN))
        }
        expectModelLiveFailure("compiler_model_live_result_identity_mismatch") {
            renderModelObservation(f.original.copy(changedPaths = listOf("src/A.kt")), f.texts, f.sourceRoot,
                f.installation, f.bindingDigest, result, observation, System.nanoTime())
        }
        expectModelLiveFailure("compiler_model_live_render_source_mismatch") {
            renderModelObservation(f.original, f.texts - "src/B.kt", f.sourceRoot, f.installation,
                f.bindingDigest, result, observation, System.nanoTime())
        }
        expectModelLiveFailure("compiler_model_live_render_source_mismatch") {
            renderModelObservation(f.original, f.texts + ("src/A.kt" to f.texts.getValue("src/A.kt").replace('1', '2')),
                f.sourceRoot, f.installation, f.bindingDigest, result, observation, System.nanoTime())
        }
    }

    @Test fun internalFailureDisposalOrResetGapCannotBeTurnedIntoProviderSuccess() {
        val f = ModelRenderingControl()
        val good = f.observation(emptyList())
        val bad = ModelWire.Observation(good.compilerVersion(), good.argvDigest(), good.memberDigest(), good.jobRoot(),
            good.sourceRoot(), good.outputRoot(), true, 2, 0, false, false, good.beforeReset(),
            ModelWire.Reset(false, true, true, true), true, true, true, ModelWire.FailureCode.COMPILER_INTERNAL, emptyList())
        assertFails { f.renderObservation(bad) }
        assertFails { f.render(listOf(ModelWire.Diagnostic("EXCEPTION", "synthetic internal compiler exception", "", -1, -1, "")), 2) }
        assertFails { f.render(listOf(ModelWire.Diagnostic("OUTPUT", "synthetic compiler output callback", "", -1, -1, ""))) }
    }

    @Test fun rendererCheckpointsPropagateFailureAndMetricsAreMeasuredOnlyToArmedRender() {
        val f = ModelRenderingControl()
        var calls = 0
        expectModelLiveFailure("synthetic_original_clock_expired") {
            f.renderObservation(f.observation(listOf(f.diagnostic())), checkpoint = {
                calls++
                if (calls == 3) throw CompilerApplicationProblem("synthetic_original_clock_expired")
            })
        }
        assertEquals(3, calls)
        val result = f.render(emptyList())
        assertTrue(result.metrics.elapsedMillis >= 10L)
        assertNull(result.metrics.cpuMillis)
        assertNull(result.metrics.peakRssBytes)
        assertTrue(result.metrics.outputBytes > 0)
        assertTrue("compiler_model_live_elapsed_measured_to_armed_render_not_call_completion" in result.reasons)
        assertTrue("compiler_model_live_cpu_rss_unavailable" in result.reasons)
    }

    @Test fun suppressionOrNowarnDoesNotBecomeACompleteScopeClaim() {
        val f = ModelRenderingControl(firstText = "@file:Suppress(\"SENSELESS_COMPARISON\")\nfun a() = 1\n",
            options = listOf("-jvm-target", "21", "-nowarn"))
        val result = f.render(emptyList())
        assertTrue("compiler_model_live_suppression_scope_not_certified" in result.reasons)
        assertTrue("compiler_model_live_warnings_disabled_under_selected_configuration" in result.reasons)
        assertTrue(result.coverage.all { !it.qualified && it.state == "unqualified" })
        assertFalse(result.completeFor(f.original))
    }

    @Test fun fullResultAndNestedCollectionsAreDetachedAndImmutable() {
        val f = ModelRenderingControl()
        val diagnostics = mutableListOf(f.diagnostic(), ModelWire.Diagnostic("INFO", "Global info", "", -1, -1, ""))
        val result = f.render(diagnostics)
        diagnostics.clear()
        assertEquals(1, result.findings.size)
        assertEquals(1, result.rawDiagnostics.size)
        assertFailsWith<UnsupportedOperationException> { (result.findings as MutableList<Finding>).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.coverage as MutableList<Coverage>).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.reasons as MutableList<String>).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.rawDiagnostics as MutableList<RawDiagnostic>).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.findings.single().evidence as MutableList<Evidence>).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.findings.single().verification as MutableList<Verification>).clear() }
    }
}

internal class ModelRenderingControl(
    firstText: String = "fun a() = 1\n",
    secondText: String = "fun b() = a()\n",
    extraRules: List<String> = emptyList(),
    options: List<String> = listOf("-jvm-target", "21"),
) {
    val rule = "ktc.compiler.common.senseless_comparison"
    val installation = Digests.sha256("synthetic-installation-not-executed")
    val bindingDigest = Digests.sha256("synthetic-model-binding-not-execution-authority")
    val requestDigest = Digests.sha256("synthetic-model-request-not-executed")
    private val base = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().resolve("ktc-model-live-render-control")
    private val job = base.resolve("generation/jobs/1")
    val sourceRoot = Wire.Root(job.resolve("sources").toString(), "synthetic-model-source-root")
    val texts = linkedMapOf("src/A.kt" to firstText, "src/B.kt" to secondText)
    private val members = listOf(SnapshotFile("build.gradle.kts", Digests.sha256("opaque build metadata"), 21),
        SnapshotFile("src/A.kt", Digests.sha256(firstText), firstText.toByteArray(Charsets.UTF_8).size.toLong()),
        SnapshotFile("src/B.kt", Digests.sha256(secondText), secondText.toByteArray(Charsets.UTF_8).size.toLong()),
        SnapshotFile("resources/opaque.bin", Digests.sha256(byteArrayOf(0xff.toByte(), 0, 13)), 3))
    val original = AnalysisInput(SnapshotManifest(Digests.sha256("synthetic-full-model-snapshot"), "workspace", "worktree",
        members, "owner", declaredRoots = listOf(".")), base.resolve("archive/sources").toString(),
        AnalysisContext(scope = RuleContext("customJvm", "main", ":fixture", "main"), compilerVersion = "2.4.21",
            compilerArguments = options, modelDigest = Digests.sha256("synthetic-model"), dependencyDigest = Digests.sha256("synthetic-empty-dependencies"),
            toolchainDigest = Digests.sha256("synthetic-framework-model-toolchain"), configurationDigest = Digests.sha256("synthetic-config"),
            fullProject = true, compilationFiles = listOf("src/B.kt", "src/A.kt")), requiredRuleIds = listOf(rule) + extraRules,
        outputRoot = base.resolve("archive-output").toString())

    fun diagnostic(severity: String = "WARNING", id: String = "SENSELESS_COMPARISON", message: String = "Synthetic compiler diagnostic",
        path: String = "src/A.kt", line: Int = 1, column: Int = 1) = ModelWire.Diagnostic(severity, message, path, line, column, id)

    fun observation(diagnostics: List<ModelWire.Diagnostic>, code: Int = 0): ModelWire.Observation = ModelWire.Observation(
        "2.4.21", Digests.sha256("synthetic-model-argv"), ModelWire.workDigest(members.mapIndexed { index, file ->
            ModelWire.Member(file.path, file.digest, file.size, "synthetic-member-$index")
        }, requireNotNull(original.context.compilationFiles), emptyList(), original.context.compilerArguments),
        Wire.Root(job.toString(), "synthetic-model-job-root"), sourceRoot, Wire.Root(job.resolve("classes").toString(), "synthetic-model-output-root"),
        true, code, 1, true, false, ModelWire.Reset(true, true, true, true), ModelWire.Reset(true, true, true, true), true, true, true,
        ModelWire.FailureCode.NONE, diagnostics)

    fun result(observation: ModelWire.Observation, state: Wire.ResultState = Wire.ResultState.APPLICATION_SEALED): Wire.Result = Wire.Result(
        "a".repeat(32), Digests.sha256("synthetic-model-hello"), 1, Wire.jobId("a".repeat(32), 1), requestDigest,
        original.analysisKey(), Digests.sha256("synthetic-model-effective"), "b".repeat(32), state, ModelWire.encodeObservation(observation))

    fun render(diagnostics: List<ModelWire.Diagnostic>, code: Int = 0): ProviderResult = renderObservation(observation(diagnostics, code))
    fun renderObservation(observation: ModelWire.Observation, result: Wire.Result = result(observation), checkpoint: () -> Unit = {}): ProviderResult =
        renderModelObservation(original, texts, sourceRoot, installation, bindingDigest, result, observation,
            System.nanoTime() - 10_000_000L, checkpoint)
}

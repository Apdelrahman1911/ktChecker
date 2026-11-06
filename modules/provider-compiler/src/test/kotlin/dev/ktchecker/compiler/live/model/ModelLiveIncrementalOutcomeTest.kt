package dev.ktchecker.compiler.live.model

import dev.ktchecker.compiler.CompilerRuleRegistry
import dev.ktchecker.contracts.*
import dev.ktchecker.workerprotocol.ApplicationWire as Wire
import dev.ktchecker.workerprotocol.CompilerModelWire as ModelWire
import kotlin.test.*

/**
 * Detached synthetic renderer controls, NOT actual full/incremental compiler observations.
 * Each input has its OWN synthetic response. Diagnostic meaning is compared explicitly;
 * distinct request/semantic evidence is retained, never stripped to assert DTO/reuse equality.
 * The full1663 parent versus bounded compiler child is tested in ModelLiveIncrementalWorkTest.
 */
class ModelLiveIncrementalOutcomeTest {
    @Test fun freshResponsesKeepAnUnchangedConsumerFindingButNotAnotherRequestsEvidence() {
        val nativeRule = CompilerRuleRegistry.load().mappings.first { it.backend == "native" }.ruleId
        val f = ModelRenderingControl(secondText = "fun b() = a() == null\n",
            extraRules = listOf(nativeRule, "unknown.required.incremental.row"))
        val diagnostics = listOf(f.diagnostic(path = "src/B.kt", column = 11,
            message = "Synthetic comparison warning in unchanged consumer"))
        val full = fresh(f, f.original, diagnostics)
        val incremental = fresh(f, incrementalInput(f), diagnostics)
        assertSameMeaningWithSeparateAuthority(full, incremental)
        val finding = incremental.rendered.findings.single()
        assertEquals("src/B.kt", finding.location.path)
        assertFalse(finding.location.path in incremental.input.changedPaths)
        assertEquals("customJvm", finding.context.target)
        assertEquals(incremental.input.snapshot.files.single { it.path == "src/B.kt" }.digest, finding.location.fileDigest)
        assertEquals(listOf("unqualified", "unsupported", "unsupported"), incremental.rendered.coverage.map { it.state })
        assertEquals("partial", incremental.rendered.status)
    }

    @Test fun freshCleanAndSuppressedResponsesLeaveTheSameRequiredRowsExplicitlyUnqualified() {
        for (suppressed in listOf(false, true)) {
            val f = ModelRenderingControl(
                firstText = if (suppressed) "@file:Suppress(\"SENSELESS_COMPARISON\")\nfun a() = 1\n" else "fun a() = 1\n",
                options = listOf("-jvm-target", "21") + if (suppressed) listOf("-nowarn") else emptyList(),
                extraRules = listOf("unknown.required.incremental.row"))
            val full = fresh(f, f.original, emptyList())
            val incremental = fresh(f, incrementalInput(f), emptyList())
            assertSameMeaningWithSeparateAuthority(full, incremental)
            for (value in listOf(full, incremental)) {
                assertTrue(value.rendered.findings.isEmpty())
                assertTrue(value.rendered.rawDiagnostics.isEmpty())
                assertTrue(value.rendered.coverage.none { it.implemented })
                assertEquals(listOf("unqualified", "unsupported"), value.rendered.coverage.map { it.state })
                assertEquals("partial", value.rendered.status)
                assertEquals(suppressed, "compiler_model_live_suppression_scope_not_certified" in value.rendered.reasons)
                assertEquals(suppressed, "compiler_model_live_warnings_disabled_under_selected_configuration" in value.rendered.reasons)
            }
        }
    }

    @Test fun freshMappedAndUnknownFailuresInUnchangedSourcesCannotBecomeIncrementalCleanliness() {
        val f = ModelRenderingControl(options = listOf("-jvm-target", "21", "-Werror"))
        val variants = listOf(
            listOf(f.diagnostic(severity = "ERROR", path = "src/B.kt", message = "Synthetic mapped compiler error")),
            listOf(f.diagnostic(severity = "ERROR", id = "FUTURE_INCREMENTAL_COMPILER_ERROR", path = "src/B.kt",
                message = "Synthetic unknown error in unchanged consumer"),
                ModelWire.Diagnostic("ERROR", "warnings found and -Werror specified", "", -1, -1, "")))
        for ((index, diagnostics) in variants.withIndex()) {
            val full = fresh(f, f.original, diagnostics, code = 1)
            val incremental = fresh(f, incrementalInput(f), diagnostics, code = 1)
            assertSameMeaningWithSeparateAuthority(full, incremental)
            for (value in listOf(full, incremental)) {
                assertEquals("failed", value.rendered.status)
                assertTrue("compiler_model_live_api_diagnostic_failure" in value.rendered.reasons)
                if (index == 0) {
                    assertEquals("error", value.rendered.findings.single().severity)
                    assertEquals("src/B.kt", value.rendered.findings.single().location.path)
                    assertTrue(value.rendered.rawDiagnostics.isEmpty())
                } else {
                    assertTrue(value.rendered.findings.isEmpty())
                    assertEquals(2, value.rendered.rawDiagnostics.size)
                    assertEquals("FUTURE_INCREMENTAL_COMPILER_ERROR", value.rendered.rawDiagnostics.first().upstreamId)
                    assertEquals("src/B.kt", value.rendered.rawDiagnostics.first().location?.path)
                    assertEquals("warnings found and -Werror specified", value.rendered.rawDiagnostics.last().message)
                    assertNull(value.rendered.rawDiagnostics.last().location)
                    assertNull(value.rendered.rawDiagnostics.last().upstreamId)
                    assertTrue("compiler_model_live_global_error_unresolved" in value.rendered.reasons)
                    assertTrue("compiler_model_live_raw_diagnostics_unresolved" in value.rendered.reasons)
                }
            }
            assertFalse("src/B.kt" in incremental.input.changedPaths)
        }
    }

    @Test fun equalCompilerWorkCannotAuthorizeResponseSwapsUnknownPromotionOrChangedOnlyRendering() {
        val f = ModelRenderingControl()
        val diagnostics = listOf(f.diagnostic(path = "src/B.kt"))
        val full = fresh(f, f.original, diagnostics)
        val incremental = fresh(f, incrementalInput(f), diagnostics)
        assertSameMeaningWithSeparateAuthority(full, incremental) // Positive controls are independently matched first.
        for ((target, foreign) in listOf(full to incremental, incremental to full)) {
            expectModelLiveFailure("compiler_model_live_result_identity_mismatch") {
                renderModelObservation(target.input, f.texts, f.sourceRoot, f.installation, f.bindingDigest,
                    foreign.response, foreign.observation, System.nanoTime())
            }
            val response = target.response
            val unknown = Wire.Result(response.generation(), response.helloDigest(), response.sequence(), response.jobId(),
                response.requestDigest(), response.originalKey(), response.effectiveKey(), response.loaderToken(),
                Wire.ResultState.OUTCOME_UNKNOWN, response.payload())
            expectModelLiveFailure("compiler_model_live_result_identity_mismatch") {
                renderModelObservation(target.input, f.texts, f.sourceRoot, f.installation, f.bindingDigest,
                    unknown, target.observation, System.nanoTime())
            }
        }
        val changedOnly = f.texts.filterKeys { it in incremental.input.changedPaths }
        assertEquals(setOf("src/A.kt"), changedOnly.keys)
        expectModelLiveFailure("compiler_model_live_render_source_mismatch") {
            renderModelObservation(incremental.input, changedOnly, f.sourceRoot, f.installation, f.bindingDigest,
                incremental.response, incremental.observation, System.nanoTime())
        }
    }

    private fun incrementalInput(f: ModelRenderingControl): AnalysisInput = f.original.copy(
        changedPaths = listOf("src/A.kt"), baselineSnapshotId = Digests.sha256("synthetic-render-baseline-not-reuse-authority"))

    private data class DetachedOutcome(
        val input: AnalysisInput,
        val observation: ModelWire.Observation,
        val response: Wire.Result,
        val rendered: ProviderResult,
    )

    private fun fresh(f: ModelRenderingControl, input: AnalysisInput, diagnostics: List<ModelWire.Diagnostic>,
        code: Int = 0): DetachedOutcome {
        // f.observation describes the same exact compilation only; forbid other input rewrites.
        assertEquals(f.original, input.copy(changedPaths = f.original.changedPaths, baselineSnapshotId = f.original.baselineSnapshotId))
        val observation = f.observation(diagnostics, code)
        val template = f.result(observation)
        val response = Wire.Result(template.generation(), template.helloDigest(), template.sequence(), template.jobId(),
            Digests.sha256("SYNTHETIC detached renderer request:${input.analysisKey()}"), input.analysisKey(),
            Digests.sha256("SYNTHETIC detached renderer effective:${input.analysisKey()}"), template.loaderToken(),
            Wire.ResultState.APPLICATION_SEALED, ModelWire.encodeObservation(observation))
        // Renderer-only synthetic keys are NOT a matched job envelope or a worker-execution receipt.
        val rendered = renderModelObservation(input, f.texts, f.sourceRoot, f.installation, f.bindingDigest,
            response, observation, System.nanoTime())
        return DetachedOutcome(input, observation, response, rendered)
    }

    private fun assertSameMeaningWithSeparateAuthority(full: DetachedOutcome, incremental: DetachedOutcome) {
        assertNotEquals(full.input.analysisKey(), incremental.input.analysisKey())
        assertEquals(full.input.analysisKey(), full.response.originalKey())
        assertEquals(incremental.input.analysisKey(), incremental.response.originalKey())
        assertNotEquals(full.response.requestDigest(), incremental.response.requestDigest())
        assertNotEquals(full.response.effectiveKey(), incremental.response.effectiveKey())
        assertNotEquals(full.response, incremental.response)
        assertEquals(full.observation.memberDigest(), incremental.observation.memberDigest())
        assertEquals(full.observation.argvDigest(), incremental.observation.argvDigest())
        assertEquals(full.observation.diagnosticsDigest(), incremental.observation.diagnosticsDigest())

        val left = full.rendered
        val right = incremental.rendered
        assertEquals(left.providerId, right.providerId)
        assertEquals(left.providerVersion, right.providerVersion)
        assertEquals(left.status, right.status)
        assertEquals(left.coverage, right.coverage)
        assertEquals(left.reasons, right.reasons)
        assertEquals(left.rawDiagnostics, right.rawDiagnostics)
        assertEquals(left.findings.size, right.findings.size)
        left.findings.zip(right.findings).forEach { (before, after) ->
            assertEquals(before.ruleId, after.ruleId)
            assertEquals(before.title, after.title)
            assertEquals(before.diagnosis, after.diagnosis)
            assertEquals(before.whyItMatters, after.whyItMatters)
            assertEquals(before.location, after.location)
            assertEquals(before.fingerprint, after.fingerprint)
            assertEquals(before.providerId, after.providerId)
            assertEquals(before.context, after.context)
            assertEquals(before.severity, after.severity)
            assertEquals(before.kind, after.kind)
            assertEquals(before.certainty, after.certainty)
            assertEquals(before.fixHint, after.fixHint)
            assertEquals(before.verification, after.verification)
            assertEquals(before.upstreamId, after.upstreamId)
            assertEquals(before.symbolId, after.symbolId)
            assertEquals(before.relatedLocations, after.relatedLocations)
            assertEquals(before.disposition, after.disposition)
            assertEquals(before.evidence.filterNot { it.kind == "application-request" }, after.evidence.filterNot { it.kind == "application-request" })
            assertEquals(full.response.requestDigest(), before.evidence.single { it.kind == "application-request" }.digest)
            assertEquals(incremental.response.requestDigest(), after.evidence.single { it.kind == "application-request" }.digest)
            assertEquals(full.input.analysisKey(), before.semanticDigest)
            assertEquals(incremental.input.analysisKey(), after.semanticDigest)
            assertNotEquals(before, after, "Equivalent diagnostic meaning must not erase the request-specific evidence")
        }
        for (value in listOf(full, incremental)) {
            assertEquals(value.input.requiredRuleIds, value.rendered.coverage.map { it.ruleId })
            assertTrue(value.rendered.coverage.all { it.context == value.input.context.scope &&
                !it.qualified && !it.tested && it.certificateId == null && !it.successful() })
            assertFalse(value.rendered.completeFor(value.input))
            assertTrue(value.rendered.findings.all { finding -> finding.verification.all { it.state == "not_run" && it.command.isEmpty() } })
            // Time/output accounting is an observation, not part of the diagnostic-equivalence assertion.
            assertTrue(value.rendered.metrics.elapsedMillis >= 0)
            assertNull(value.rendered.metrics.cpuMillis)
            assertNull(value.rendered.metrics.peakRssBytes)
            assertTrue(value.rendered.metrics.outputBytes > 0)
        }
    }
}

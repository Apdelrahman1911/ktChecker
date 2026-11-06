package dev.ktchecker.providers.detekt

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.ExecutionProblem
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Synthetic SARIF parser/projection checks only. NOT actual Detekt or type-resolution evidence. */
class DetektFullReportTest {
    private val sarifId = "detekt.potential-bugs.MissingUseCall"
    private fun failure(code: String, action: () -> Unit) =
        assertEquals(code, assertFailsWith<ExecutionProblem>(block = action).code)

    private fun plan(mode: String = "full", active: Set<String> = setOf(DETEKT_FULL_MISSING_USE_CALL)): DetektExecutionPlan {
        val config = "SYNTHETIC projection-only plan, never executable YAML"
        return DetektExecutionPlan(TrustedExecution("/never/run/java", approved = false), config, Digests.sha256(config), emptyList(), active, mode)
    }

    private fun document(positive: Boolean, enabled: Boolean = true, descriptor: Boolean = true,
                         path: String = DetektFullSemanticCases.DIRECT_PATH, id: String = sarifId): ByteArray = buildJsonObject {
        put("version", "2.1.0")
        putJsonArray("runs") { add(buildJsonObject {
            putJsonObject("tool") { putJsonObject("driver") {
                put("name", "detekt")
                putJsonArray("rules") { if (descriptor) add(buildJsonObject {
                    put("id", id); putJsonObject("defaultConfiguration") { put("enabled", enabled); put("level", "error") }
                }) }
            } }
            putJsonArray("invocations") { add(buildJsonObject { put("executionSuccessful", true) }) }
            putJsonArray("results") { if (positive) add(buildJsonObject {
                put("ruleId", id); put("level", "error")
                putJsonObject("message") { put("text", "SYNTHETIC upstream MissingUseCall observation for parser control") }
                putJsonObject("partialFingerprints") { put("signature/v1", "synthetic-stable-signature") }
                putJsonArray("locations") { add(buildJsonObject { putJsonObject("physicalLocation") {
                    putJsonObject("artifactLocation") { put("uri", path); put("uriBaseId", "%SRCROOT%") }
                    putJsonObject("region") { put("startLine", 6); put("startColumn", 5); put("endLine", 6); put("endColumn", 26) }
                } }) }
            }) }
        }) }
    }.toString().toByteArray()

    @Test fun `positive is advisory low confidence raw preserving and never complete or release qualified`(): Unit = DetektFullFixtures().use { f ->
        val parent = f.parent()
        val input = parent.copy(requiredRuleIds = f.selected)
        val bytes = document(true)
        val report = DetektReportParser().parse(bytes, input)
        val processText = RawDiagnostic("SYNTHETIC process-diagnostic projection control", "info")
        val result = DetektFullModelProvider().projectReport(input, plan(), report, Digests.sha256(bytes), "synthetic/not-executed.sarif", listOf(processText))
        val finding = result.findings.single()
        assertEquals(DETEKT_FULL_MISSING_USE_CALL, finding.ruleId)
        assertEquals("potential-bugs:MissingUseCall", finding.upstreamId)
        assertEquals("warning", finding.severity); assertEquals("advisory", finding.kind); assertEquals("low", finding.certainty)
        assertEquals(DetektFullSemanticCases.DIRECT_PATH, finding.location.path)
        assertEquals(6, finding.location.line); assertEquals(4, finding.location.column); assertEquals(25, finding.location.endColumn)
        assertEquals(f.snapshot.files.single { it.path == finding.location.path }.digest, finding.location.fileDigest)
        assertEquals(input.context.scope, finding.context)
        assertEquals("", finding.context.target)
        assertEquals(Digests.sha256(bytes), finding.evidence.single().digest)
        assertEquals("not_run", finding.verification.single().state)
        assertEquals(listOf(processText, RawDiagnostic(report.diagnostics.single().message, "error", sarifId, finding.location)), result.rawDiagnostics)
        assertEquals("partial", result.status)
        assertFalse(result.complete()); assertFalse(result.completeFor(input))
        assertEquals(f.selected, result.coverage.map { it.ruleId })
        val semantic = result.coverage.single { it.ruleId == DETEKT_FULL_MISSING_USE_CALL }
        assertEquals("findings", semantic.state); assertEquals("semantic", semantic.depth); assertEquals("executed", semantic.provenance)
        assertTrue(semantic.implemented)
        assertTrue(result.coverage.all { !it.tested && !it.qualified && it.certificateId == null && !it.successful() })
        assertTrue(result.reasons.containsAll(listOf("detekt_full_2.4.10_experimental_semantic_observation_only",
            "detekt_complete_type_resolution_not_proven", "detekt_full_compiler_emission_not_executed", "detekt_full_initial_rule_slice_not_release_scope")))
        assertEquals(1665, parent.requiredRuleIds.size)
        assertEquals(parent.context.compilerArguments, input.context.compilerArguments)
    }

    @Test fun `negative requires the enabled descriptor and is unknown not passed`(): Unit = DetektFullFixtures().use { f ->
        val input = f.parent().copy(requiredRuleIds = f.selected)
        val bytes = document(false)
        val report = DetektReportParser().parse(bytes, input)
        assertEquals(setOf(sarifId), report.enabledSarifIds)
        val result = DetektFullModelProvider().projectReport(input, plan(), report, Digests.sha256(bytes), "synthetic/negative.sarif")
        assertTrue(result.findings.isEmpty())
        val row = result.coverage.single { it.ruleId == DETEKT_FULL_MISSING_USE_CALL }
        assertEquals("unknown", row.state); assertEquals("executed", row.provenance); assertTrue(row.implemented)
        assertFalse(row.successful()); assertFalse(row.tested); assertFalse(row.qualified); assertNull(row.certificateId)
        assertEquals("partial", result.status); assertFalse(result.complete())
        for (bytesWithoutEnabledDescriptor in listOf(document(false, enabled = false), document(false, descriptor = false))) {
            val absent = DetektReportParser().parse(bytesWithoutEnabledDescriptor, input)
            failure("detekt_full_report_projection_mismatch") {
                DetektFullModelProvider().projectReport(input, plan(), absent, Digests.sha256(bytesWithoutEnabledDescriptor), "synthetic/absent.sarif")
            }
        }
    }

    @Test fun `LIGHT disabled foreign and mismatched enabled result projections cannot become FULL observations`(): Unit = DetektFullFixtures().use { f ->
        val input = f.parent().copy(requiredRuleIds = f.selected)
        val bytes = document(false)
        val report = DetektReportParser().parse(bytes, input)
        val foreign = DetektReportParser().parse(document(true, id = "detekt.empty-blocks.EmptyCatchBlock"), input)
        for (candidate in listOf(foreign, report.copy(enabledSarifIds = report.enabledSarifIds + "detekt.style.MagicNumber"),
            report.copy(diagnostics = foreign.diagnostics))) {
            failure("detekt_full_report_projection_mismatch") {
                DetektFullModelProvider().projectReport(input, plan(), candidate, Digests.sha256(bytes), "synthetic/foreign.sarif")
            }
        }
        for (candidatePlan in listOf(plan("light"), plan(active = emptySet()), plan(active = setOf("ktc.kotlin.detekt.empty-blocks.empty-catch-block")))) {
            failure("detekt_full_report_projection_mismatch") {
                DetektFullModelProvider().projectReport(input, candidatePlan, report, Digests.sha256(bytes), "synthetic/wrong-mode.sarif")
            }
        }
        failure("detekt_full_report_projection_mismatch") {
            DetektFullModelProvider(settings = DetektFullSettings(enabled = false)).projectReport(input, plan(), report, Digests.sha256(bytes), "synthetic/disabled.sarif")
        }
    }

    @Test fun `report cannot erase compiler version prerequisite or invent selection membership`(): Unit = DetektFullFixtures().use { f ->
        val input = f.parent().copy(requiredRuleIds = f.selected)
        val bytes = document(false)
        val report = DetektReportParser().parse(bytes, input)
        for (changed in listOf(input.copy(context = input.context.copy(compilerVersion = "2.4.21")),
            input.copy(context = input.context.copy(fullProject = false)), input.copy(requiredRuleIds = f.selected - DETEKT_FULL_MISSING_USE_CALL),
            input.copy(requiredRuleIds = f.selected + DETEKT_FULL_MISSING_USE_CALL))) {
            failure("detekt_full_projection_context_mismatch") {
                DetektFullModelProvider().projectReport(changed, plan(), report, Digests.sha256(bytes), "synthetic/changed.sarif")
            }
        }
    }

    @Test fun `all selected syntax semantic alias and refined obligations retain explicit nonexecuted gaps`(): Unit = DetektFullFixtures().use { f ->
        val input = f.parent().copy(requiredRuleIds = f.selected)
        val bytes = document(false)
        val result = DetektFullModelProvider().projectReport(input, plan(), DetektReportParser().parse(bytes, input),
            Digests.sha256(bytes), "synthetic/gaps.sarif")
        val remaining = result.coverage.filter { it.ruleId != DETEKT_FULL_MISSING_USE_CALL }
        assertEquals(275, remaining.size)
        assertTrue(remaining.all { it.provenance == "not_executed" && !it.implemented && !it.tested && !it.qualified })
        assertEquals(setOf("unavailable", "excluded", "unsupported"), remaining.map { it.state }.toSet())
        assertEquals("unsupported", remaining.single { it.ruleId == "ktc.resources.owned_autocloseable_exit" }.state)
        assertEquals("excluded", remaining.single { it.ruleId == "ktc.kotlin.detekt.empty-blocks.empty-class-block" }.state)
        assertEquals("unavailable", remaining.single { it.ruleId == "ktc.kotlin.detekt.empty-blocks.empty-catch-block" }.state)
    }

    @Test fun `foreign test compilation locations and malformed descriptor evidence are rejected`(): Unit = DetektFullFixtures().use { f ->
        val input = f.parent().copy(requiredRuleIds = f.selected)
        for (bytes in listOf(document(true, path = "src/test/kotlin/Foreign.kt"), document(true, descriptor = false),
            document(true, enabled = false), document(true).toString(Charsets.UTF_8).replace("\"name\":\"detekt\"", "\"name\":\"invented-engine\"").toByteArray())) {
            assertFailsWith<ExecutionProblem> { DetektReportParser().parse(bytes, input) }
        }
        assertEquals(listOf(DetektFullSemanticCases.ALIAS_PATH, DetektFullSemanticCases.DIRECT_PATH), input.context.compilationFiles)
    }
}

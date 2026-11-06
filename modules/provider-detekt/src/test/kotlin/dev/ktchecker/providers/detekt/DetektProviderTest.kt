package dev.ktchecker.providers.detekt

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlinx.serialization.json.*
import kotlin.test.*

/** Parser and authority tests, not evidence that every mapped detector is tested. */
class DetektProviderTest {
    @TempDir lateinit var temporary: Path
    private val exactRule = "ktc.kotlin.detekt.empty-blocks.empty-catch-block"

    @Test fun `frozen mapping includes every alias and refinement without promotion`() {
        assertEquals(276, DetektCatalog.mappings.size)
        assertEquals(251, DetektCatalog.mappings.count { it.exact })
        assertEquals(15, DetektCatalog.mappings.count { it.relation == "excluded_alias" })
        assertEquals(10, DetektCatalog.mappings.count { it.relation == "requires_refinement" })
        val provider = DetektProvider()
        assertTrue(exactRule in provider.supportedRuleIds)
        for (mapping in DetektCatalog.mappings.filterNot { it.exact }) {
            assertFalse(mapping.rule_id in provider.supportedRuleIds, mapping.rule_id)
        }
    }

    @Test fun `five artifact pins have verified official origins but no qualification`() {
        assertEquals(5, DetektCatalog.pins.size)
        assertTrue(DetektCatalog.pins.all { it.url.startsWith("https://repo.maven.apache.org/maven2/") })
        assertTrue(DetektCatalog.pins.all { Digests.valid("sha256:" + it.sha256) && it.upstream_checksum_algorithm == "sha1" })
        assertEquals("2.4.10", DETEKT_EMBEDDED_KOTLIN)
        assertFailsWith<ExecutionProblem> { DetektCatalog.verifyArtifacts(temporary) }
    }

    @Test fun `serialized approval cannot grant execution`() {
        val input = input().copy(execution = TrustedExecution("/bin/true", approved = true))
        val result = DetektProvider().analyze(input, CancellationToken.NONE)
        assertEquals("partial", result.status)
        assertEquals(listOf("execution_not_authorized"), result.reasons)
        assertFalse(result.complete())
        assertTrue(result.coverage.none { it.successful() || it.implemented || it.qualified })
    }

    @Test fun `whole prepared context cannot be relabeled using an old permit`() {
        val initial = input().copy(execution = TrustedExecution("/bin/true", approved = true))
        val authority = ExactExecutionAuthority(ExecutionPermit(initial.execution!!, initial.snapshot.snapshotId,
            Path.of(initial.materializedRoot), initial.analysisKey(), verifySnapshot = { error("Must not be called") }))
        val changed = initial.copy(context = initial.context.copy(scope = initial.context.scope.copy(sourceSet = "test")))
        val result = DetektProvider(authority = authority).analyze(changed, CancellationToken.NONE)
        assertTrue("execution_not_authorized" in result.reasons)
        assertFalse(result.complete())
    }

    @Test fun `native context never receives JVM clean coverage`() {
        val initial = input()
        val native = initial.copy(context = initial.context.copy(scope = initial.context.scope.copy(target = "ios")))
        val result = DetektProvider().analyze(native, CancellationToken.NONE)
        assertEquals("unsupported", result.coverage.single().state)
        assertFalse(result.complete())
    }

    @Test fun `cancellation before authorization stays explicit`() {
        val result = DetektProvider().analyze(input(), CancellationToken { true })
        assertEquals("cancelled", result.status)
        assertEquals("not_executed", result.coverage.single().provenance)
    }

    @Test fun `real SARIF identity and one based ranges normalize against frozen bytes`() {
        val input = input()
        val report = DetektReportParser().parse(sarif().toByteArray(), input)
        val diagnostic = report.diagnostics.single()
        assertEquals("detekt.empty-blocks.EmptyCatchBlock", diagnostic.sarifId)
        assertEquals("source.kt", diagnostic.location.path)
        assertEquals(0, diagnostic.location.column)
        assertEquals(3, diagnostic.location.endColumn)
        assertEquals(input.snapshot.files.single().digest, diagnostic.location.fileDigest)
        assertEquals(setOf(diagnostic.sarifId), report.enabledSarifIds)
    }

    @Test fun `invalid tool version missing descriptor and quoted ranges are not clean reports`() {
        for (document in listOf(
            sarif().replace("2.1.0", "9.0"), sarif().replace("\"name\":\"detekt\"", "\"name\":\"other\""),
            sarif().replace("\"startColumn\":1", "\"startColumn\":\"1\""),
            sarif().replace("\"enabled\":true", "\"enabled\":false"),
            sarif().replace("\"ruleId\":\"detekt.empty-blocks.EmptyCatchBlock\"", "\"ruleId\":\"detekt.style.UnknownRule\""),
            "not a SARIF report",
        )) {
            assertFailsWith<ExecutionProblem> { DetektReportParser().parse(document.toByteArray(), input()) }
        }
    }

    @Test fun `unmanifested outside and out of range locations are rejected`() {
        for (document in listOf(sarif("../outside.kt"), sarif("missing.kt"), sarif("/outside.kt"),
            sarif().replace("\"startLine\":2", "\"startLine\":900"),
            sarif().replace("\"startColumn\":1", "\"startColumn\":0"))) {
            assertFailsWith<ExecutionProblem> { DetektReportParser().parse(document.toByteArray(), input()) }
        }
    }

    @Test fun `a captured but different compilation member cannot be relabeled`() {
        val input = input(extraFile = true)
        assertFailsWith<ExecutionProblem> { DetektReportParser().parse(sarif("other.kt").toByteArray(), input) }
    }

    @Test fun `malformed UTF8 is rejected rather than replaced`() {
        assertFailsWith<ExecutionProblem> { DetektReportParser().parse(sarif().toByteArray() + byteArrayOf(0x80.toByte()), input()) }
    }

    @Test fun `actual empty results are parseable but not a qualification certificate`() {
        val document = Json.parseToJsonElement(sarif()).jsonObject
        val run = document["runs"]!!.jsonArray.single().jsonObject.toMutableMap()
        run["results"] = JsonArray(emptyList())
        val empty = JsonObject(document.toMutableMap().apply { put("runs", JsonArray(listOf(JsonObject(run)))) })
        val report = DetektReportParser().parse(empty.toString().toByteArray(), input())
        assertTrue(report.diagnostics.isEmpty())
        assertFalse(Coverage(exactRule, state = "passed", provenance = "executed").successful())
    }

    @Test fun `legacy projection distinguishes process execution from each enabled rule`() {
        val input = input()
        val semanticRule = "ktc.kotlin.detekt.potential-bugs.missing-use-call"
        val positive = DetektReportParser().parse(sarif().toByteArray(), input)
        val negative = positive.copy(diagnostics = emptyList())
        val noDescriptor = negative.copy(enabledSarifIds = emptySet())
        // Synthetic projection inputs only: never passed to analyze, an authority, or a runner.
        val plan = DetektExecutionPlan(TrustedExecution("/never-executed", approved = false), "", Digests.sha256(""),
            emptyList(), setOf(exactRule), "light")
        val processDetail = RawDiagnostic("Synthetic process detail is not an individual rule execution witness", "info")
        data class Case(
            val label: String,
            val ruleId: String,
            val active: Set<String>,
            val report: DetektReport,
            val state: String,
            val reason: String,
            val provenance: String,
            val implemented: Boolean,
            val findingCount: Int = 0,
            val settings: DetektSettings = DetektSettings(),
        )
        val cases = listOf(
            Case("enabled finding", exactRule, setOf(exactRule), positive, "findings",
                "exact_upstream_executed_but_compatibility_and_release_qualification_missing", "executed", true, 1),
            Case("enabled zero findings is not a clean certificate", exactRule, setOf(exactRule), negative, "unknown",
                "exact_upstream_executed_but_compatibility_and_release_qualification_missing", "executed", true),
            Case("active plan without an enabled descriptor", exactRule, setOf(exactRule), noDescriptor, "unknown",
                "upstream_rule_not_reported_active", "not_executed", false),
            Case("descriptor cannot override owner disabled rule", exactRule, emptySet(), negative, "unknown",
                "rule_disabled_by_owner_selected_configuration", "not_executed", false,
                settings = DetektSettings(disabledRuleIds = setOf(exactRule))),
            Case("unavailable semantic prerequisites", semanticRule, emptySet(), noDescriptor, "unknown",
                "full_jvm_semantic_prerequisites_unavailable", "not_executed", false),
        )
        assertFalse(input.context.fullProject)
        assertTrue(input.context.classpath.isEmpty())
        for (case in cases) {
            val provider = DetektProvider(settings = case.settings)
            val mapping = DetektCatalog.byId.getValue(case.ruleId)
            assertTrue(case.ruleId in provider.mappedRuleIds, case.label)
            assertTrue(case.ruleId in provider.supportedRuleIds, case.label)
            assertEquals("exact", mapping.relation, case.label)
            assertEquals("advisory", mapping.default_activation, case.label)
            val selectedInput = input.copy(requiredRuleIds = listOf(case.ruleId))
            val result = provider.projectReport(selectedInput, plan.copy(activeRuleIds = case.active), case.report,
                Digests.sha256("synthetic projection: ${case.label}"), "synthetic/not-executed.sarif", listOf(processDetail))
            assertEquals("detekt", result.providerId, case.label)
            assertEquals(DETEKT_VERSION, result.providerVersion, case.label)
            assertEquals("partial", result.status, case.label)
            assertEquals(listOf(processDetail), result.rawDiagnostics, case.label)
            assertEquals(Coverage(case.ruleId, input.context.scope, case.state, case.reason, depth = mapping.depth,
                provenance = case.provenance, implemented = case.implemented), result.coverage.single(), case.label)
            assertEquals(case.findingCount, result.findings.size, case.label)
            assertTrue(result.findings.all { it.ruleId == case.ruleId && it.context == input.context.scope }, case.label)
            assertTrue("detekt_compatibility_tuple_unqualified" in result.reasons, case.label)
            assertFalse(result.complete(), case.label)
            assertFalse(result.completeFor(selectedInput), case.label)
            assertTrue(result.coverage.none { it.successful() || it.tested || it.qualified || it.certificateId != null }, case.label)
        }
    }

    @Test fun `copied Compose support metadata cannot mint projection authority while generic findings remain`() {
        val composeRule = "ktc.compose.pack.StateParam"
        val original = input().copy(requiredRuleIds = listOf(exactRule, composeRule))
        val parsed = DetektReportParser().parse(sarif().toByteArray(), original)
        val composeSarif = DetektCatalog.byId.getValue(composeRule).sarifId
        val report = parsed.copy(enabledSarifIds = parsed.enabledSarifIds + composeSarif,
            diagnostics = parsed.diagnostics + parsed.diagnostics.single().copy(sarifId = composeSarif,
                message = "unproved Compose diagnostic must be withheld"))
        val forged = DetektComposeApplicability(Digests.sha256("copied authority metadata"), mapOf(composeRule to
            DetektComposeRuleApplicability(mapOf(DetektComposeApiFamily.GENERIC_STATE to DetektComposeApiState.REVIEWED_PRESENT),
                executableFacets = true, completeApiSurface = true, reason = null)))
        val plan = DetektExecutionPlan(TrustedExecution("/never-executed", approved = false), "", Digests.sha256(""),
            emptyList(), setOf(exactRule, composeRule), "light", forged)
        val result = DetektProvider().projectReport(original, plan, report, Digests.sha256("synthetic"), "not-executed.sarif",
            diagnostics = listOf(RawDiagnostic("unproved Compose diagnostic must be withheld")))
        assertEquals(listOf(exactRule), result.findings.map { it.ruleId })
        assertEquals("findings", result.coverage.single { it.ruleId == exactRule }.state)
        assertTrue(result.coverage.single { it.ruleId == exactRule }.implemented)
        val denied = result.coverage.single { it.ruleId == composeRule }
        assertEquals("unavailable", denied.state)
        assertEquals("detekt_compose_execution_observation_required", denied.reason)
        assertFalse(denied.implemented || denied.tested || denied.qualified || denied.successful())
        assertTrue(result.rawDiagnostics.none { it.message == "unproved Compose diagnostic must be withheld" })
        assertTrue(result.rawDiagnostics.any { it.message.startsWith("Compose diagnostic withheld:") })
        assertFalse(result.complete())
    }

    @Test fun `fresh report locators are retained but do not change projected material evidence`() {
        // Pure parser/projection fixture only: no native runner, report read or authority permit.
        val input = input()
        val bytes = sarif().toByteArray()
        val parsed = DetektReportParser().parse(bytes, input)
        val reportDigest = Digests.sha256(bytes)
        val plan = DetektExecutionPlan(TrustedExecution("/never-executed", approved = false), "", Digests.sha256(""),
            emptyList(), setOf(exactRule), "light")
        val provider = DetektProvider()
        val firstPath = "/outputs/work-a/attempt-1/provider-a/detekt.sarif"
        val secondPath = "/outputs/work-b/attempt-2/provider-b/detekt.sarif"
        val first = provider.projectReport(input, plan, parsed, reportDigest, firstPath)
        val second = provider.projectReport(input, plan, parsed, reportDigest, secondPath)
        val a = first.findings.single()
        val b = second.findings.single()
        assertEquals(a.evidence, b.evidence)
        assertEquals(reportDigest, a.evidence.single { it.kind == "upstream_sarif" }.digest)
        assertNull(a.evidence.single { it.kind == "upstream_sarif" }.artifactRef)
        assertEquals(listOf(EvidenceRetrievalAttachment("upstream_sarif", reportDigest, firstPath)), a.retrievalAttachments)
        assertEquals(listOf(EvidenceRetrievalAttachment("upstream_sarif", reportDigest, secondPath)), b.retrievalAttachments)
        assertEquals(a.copy(retrievalAttachments = emptyList()), b.copy(retrievalAttachments = emptyList()))
        assertEquals(first.copy(findings = listOf(a.copy(retrievalAttachments = b.retrievalAttachments))), second)
        assertNotEquals(Digests.canonical(ContractJson.encodeToJsonElement(first)),
            Digests.canonical(ContractJson.encodeToJsonElement(second)))
        assertEquals("partial", first.status)
        assertEquals(DETEKT_VERSION, first.providerVersion)
        assertTrue(first.coverage.none { it.successful() || it.tested || it.qualified || it.certificateId != null })
        assertTrue("detekt_compatibility_tuple_unqualified" in first.reasons)
        assertFalse(first.complete())
    }

    @Test fun `complete report byte churn remains material even when parsed diagnostic is identical`() {
        val input = input()
        val bytes = sarif().toByteArray()
        val changedBytes = (sarif() + "\n").toByteArray()
        val firstReport = DetektReportParser().parse(bytes, input)
        val changedReport = DetektReportParser().parse(changedBytes, input)
        assertEquals(firstReport.diagnostics, changedReport.diagnostics)
        assertEquals(firstReport.enabledSarifIds, changedReport.enabledSarifIds)
        val plan = DetektExecutionPlan(TrustedExecution("/never-executed", approved = false), "", Digests.sha256(""),
            emptyList(), setOf(exactRule), "light")
        val provider = DetektProvider()
        val first = provider.projectReport(input, plan, firstReport, Digests.sha256(bytes), "synthetic/report.sarif")
        val changed = provider.projectReport(input, plan, changedReport, Digests.sha256(changedBytes), "synthetic/report.sarif")
        assertNotEquals(Digests.sha256(bytes), Digests.sha256(changedBytes))
        assertNotEquals(first.findings.single().evidence, changed.findings.single().evidence)
        for ((result, digest) in listOf(first to Digests.sha256(bytes), changed to Digests.sha256(changedBytes))) {
            val finding = result.findings.single()
            assertEquals(digest, finding.evidence.single().digest)
            assertEquals(digest, finding.retrievalAttachments.single().digest)
            assertEquals("synthetic/report.sarif", finding.retrievalAttachments.single().artifactRef)
            assertFalse(result.complete())
        }
        assertEquals(first.coverage, changed.coverage)
        assertEquals(first.reasons, changed.reasons)
        assertEquals(first.findings.single().location, changed.findings.single().location)
    }

    private fun input(extraFile: Boolean = false): AnalysisInput {
        val source = "package fixture\nfun source() {}\n"
        Files.writeString(temporary.resolve("source.kt"), source)
        val files = mutableListOf(SnapshotFile("source.kt", Digests.sha256(source), source.toByteArray().size.toLong()))
        if (extraFile) {
            Files.writeString(temporary.resolve("other.kt"), source)
            files += files.single().copy(path = "other.kt")
        }
        return AnalysisInput(SnapshotManifest(Digests.sha256("unit parser descriptor"), "fixture", "parser", files),
            temporary.toString(), AnalysisContext(compilerVersion = "2.4.21", compilationFiles = listOf("source.kt")), listOf(exactRule))
    }

    private fun sarif(path: String = "source.kt"): String =
        """{"version":"2.1.0","runs":[{"tool":{"driver":{"name":"detekt","rules":[{"id":"detekt.empty-blocks.EmptyCatchBlock","defaultConfiguration":{"enabled":true,"level":"error"}}]}},"results":[{"ruleId":"detekt.empty-blocks.EmptyCatchBlock","message":{"text":"unit parser diagnostic"},"locations":[{"physicalLocation":{"artifactLocation":{"uri":${JsonPrimitive(path)},"uriBaseId":"%SRCROOT%"},"region":{"startLine":2,"startColumn":1,"endLine":2,"endColumn":4}}}]}]}]}"""
}

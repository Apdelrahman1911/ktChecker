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

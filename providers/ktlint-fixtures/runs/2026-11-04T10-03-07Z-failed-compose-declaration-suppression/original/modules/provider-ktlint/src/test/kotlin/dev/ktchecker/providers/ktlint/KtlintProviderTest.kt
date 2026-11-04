package dev.ktchecker.providers.ktlint

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** Bookkeeping/parser/security tests do not claim 131 detector integrations. */
class KtlintProviderTest {
    @TempDir lateinit var temporary: Path
    private val rule = "ktc.maintainability.ktlint.no-wildcard-imports"
    private val installed get() = KtlintInstallation(temporary.resolve("jdk/bin/java"), temporary.resolve("artifacts"), temporary.resolve("jdk"))

    @Test fun `all 137 routes are retained but five exclusions cannot activate`() {
        assertEquals(137, KtlintCatalog.mappings.size)
        assertEquals(131, KtlintCatalog.mappings.count { it.exact })
        assertEquals(5, KtlintCatalog.mappings.count { it.relation == "excluded_alias" })
        assertEquals(1, KtlintCatalog.mappings.count { it.relation == "requires_refinement" })
        val provider = KtlintProvider()
        assertEquals(137, provider.mappedRuleIds.size)
        assertEquals(131, provider.supportedRuleIds.size)
        assertTrue(KtlintCatalog.mappings.filterNot { it.exact }.none { it.rule_id in provider.supportedRuleIds })
    }

    @Test fun `three official pins do not imply compatible Kotlin 2 point 4`() {
        assertEquals(3, KtlintCatalog.pins.size)
        assertEquals("2.2.21", KTLINT_EMBEDDED_KOTLIN)
        assertTrue(KtlintCatalog.pins.all { it.url.startsWith("https://repo.maven.apache.org/maven2/") && it.upstream_checksum_algorithm == "sha1" && Digests.valid("sha256:" + it.sha256) })
        assertFailsWith<ExecutionProblem> { KtlintCatalog.verifyArtifacts(temporary) }
    }

    @Test fun `default provider and transport approval cannot execute`() {
        val result = KtlintProvider().analyze(input().copy(execution = TrustedExecution("/bin/true", approved = true)), CancellationToken.NONE)
        assertEquals(listOf("execution_not_authorized"), result.reasons)
        assertFalse(result.complete())
        assertTrue(result.coverage.all { !it.successful() && !it.implemented && !it.tested && !it.qualified })
    }

    @Test fun `empty owner selection never activates style by default`() {
        val result = KtlintProvider().analyze(input().copy(requiredRuleIds = emptyList()), CancellationToken.NONE)
        assertEquals(listOf("no_explicit_opt_in_ktlint_rules_requested"), result.reasons)
        assertTrue(result.findings.isEmpty())
    }

    @Test fun `permit cannot be relabeled to another exact context`() {
        val before = input().copy(execution = TrustedExecution("/bin/true", approved = true))
        val authority = ExactExecutionAuthority(ExecutionPermit(before.execution!!, before.snapshot.snapshotId,
            Path.of(before.materializedRoot), before.analysisKey(), { error("must not verify relabeled input") }))
        val after = before.copy(context = before.context.copy(scope = before.context.scope.copy(sourceSet = "test")))
        assertEquals(listOf("execution_not_authorized"), KtlintProvider(authority = authority).analyze(after, CancellationToken.NONE).reasons)
    }

    @Test fun `newer unknown and non JVM tuples abstain without clean coverage`() {
        for (context in listOf(input().context.copy(compilerVersion = "2.4.21"), input().context.copy(compilerVersion = null),
            input().context.copy(scope = RuleContext(target = "ios")), input().context.copy(scope = RuleContext(target = "common")))) {
            val result = KtlintProvider().analyze(input().copy(context = context), CancellationToken.NONE)
            assertEquals("unsupported", result.coverage.single().state)
            assertFalse(result.complete())
            assertTrue(result.findings.isEmpty())
        }
    }

    @Test fun `preauthorization cancellation is visible`() {
        val result = KtlintProvider().analyze(input(), CancellationToken { true })
        assertEquals("cancelled", result.status)
        assertEquals("not_executed", result.coverage.single().provenance)
    }

    @Test fun `genuine JSON one based point maps only to frozen bytes`() {
        val input = input()
        val diagnostic = KtlintReportParser().parse(report().toByteArray(), input).diagnostics.single()
        assertEquals("standard:no-wildcard-imports", diagnostic.upstreamId)
        assertEquals("source.kt", diagnostic.location.path)
        assertEquals(2, diagnostic.location.line)
        assertEquals(0, diagnostic.location.column)
        assertEquals(input.snapshot.files.first { it.path == "source.kt" }.digest, diagnostic.location.fileDigest)
    }

    @Test fun `malformed schema ranges rule IDs and parse failures are not clean`() {
        for (document in listOf("{}", "", report().replace("\"column\":1", "\"column\":\"1\""),
            report().replace("\"column\":1", "\"column\":0"), report().replace("\"line\":2", "\"line\":200"),
            report().replace("standard:no-wildcard-imports", "not a rule"), report().replace("standard:no-wildcard-imports", ""),
            report().replace("\"errors\":[", "\"unknown\":["))) {
            assertFailsWith<ExecutionProblem> { KtlintReportParser().parse(document.toByteArray(), input()) }
        }
    }

    @Test fun `outside unmanifested and different compilation locations are refused`() {
        for (path in listOf("../outside.kt", "/outside.kt", "missing.kt", "other.kt")) {
            assertFailsWith<ExecutionProblem> { KtlintReportParser().parse(report(path).toByteArray(), input(extra = true)) }
        }
    }

    @Test fun `duplicate report files keys escaped aliases and invalid UTF8 are refused`() {
        val duplicate = "[" + report().removePrefix("[").removeSuffix("]") + "," + report().removePrefix("[").removeSuffix("]") + "]"
        for (document in listOf(duplicate, report().replace("\"line\":2", "\"line\":2,\"line\":2"),
            report().replace("\"line\":2", "\"line\":2,\"li\\u006Ee\":2"),
            report().replace("\"file\":", "\"file\":\"source.kt\",\"file\":"))) {
            assertFailsWith<ExecutionProblem> { KtlintReportParser().parse(document.toByteArray(), input()) }
        }
        assertFailsWith<ExecutionProblem> { KtlintReportParser().parse(report().toByteArray() + byteArrayOf(0x80.toByte()), input()) }
        assertEquals(1, KtlintReportParser().parse(report(message = "literal {\"line\":3,\"line\":4}, ], comma").toByteArray(), input()).diagnostics.size)
    }

    @Test fun `empty report requires matching genuine scan count and exit`() {
        val parser = KtlintReportParser()
        assertTrue(parser.parse("[]".toByteArray(), input()).diagnostics.isEmpty())
        val summary = "Finished processing in 5ms / 1 file(s) scanned / 0 error(s) found"
        parser.verifyCompletion(summary, "", 1, 0, 0)
        for ((log, files, errors, exit) in listOf(
            listOf(summary, 2, 0, 0), listOf(summary, 1, 1, 1), listOf(summary, 1, 0, 1),
            listOf("", 1, 0, 0), listOf(summary + " No files matched", 1, 0, 0),
            listOf(summary + "\n" + summary, 1, 0, 0), listOf(summary + " Skipping rule(s)", 1, 0, 0),
        )) assertFailsWith<ExecutionProblem> { parser.verifyCompletion(log as String, "", files as Int, errors as Int, exit as Int) }
    }

    @Test fun `captured root true is mandatory before artifact lookup`() {
        for (configuration in listOf(null, "[*.kt]\n", "root = false\n", "root = true\nroot = true\n")) {
            val problem = assertFailsWith<ExecutionProblem> { KtlintProvider(installed).executionPlan(input(configuration = configuration)) }
            assertEquals("ktlint_editorconfig_root_unavailable", problem.code)
        }
    }

    @Test fun `project cannot activate groups unselected IDs exclusions or obsolete aliases`() {
        for (setting in listOf("ktlint_standard = enabled", "ktlint_standard_final-newline = enabled",
            "ktlint_compose_naming-check = enabled", "ktlint_standard_condition-wrapping = enabled", "disabled_rules = no-wildcard-imports",
            "ktlint_internal = disabled", "ktlint_internal_ktlint-suppression = disabled")) {
            val problem = assertFailsWith<ExecutionProblem> { KtlintProvider(installed).executionPlan(input(configuration = "root = true\n\n[*]\n$setting\n")) }
            assertTrue(problem.code in setOf("ktlint_project_activation_outside_owner_selection", "ktlint_obsolete_rule_configuration", "ktlint_internal_suppression_cannot_be_disabled"), problem.code)
        }
    }

    @Test fun `safe selected rule config passes policy but missing installation still abstains`() {
        val problem = assertFailsWith<ExecutionProblem> { KtlintProvider(installed).executionPlan(input(configuration = "root = true\n\n[*]\nktlint_standard_no-wildcard-imports = enabled\n")) }
        assertEquals("ktlint_artifact_digest_mismatch", problem.code)
    }

    @Test fun `missing duplicate and glob compilation memberships do not expand scope`() {
        for (files in listOf(null, emptyList(), listOf("source.kt", "source.kt"), listOf("missing.kt"), listOf("*.kt"))) {
            val original = input()
            val problem = assertFailsWith<ExecutionProblem> { KtlintProvider(installed).executionPlan(original.copy(context = original.context.copy(compilationFiles = files))) }
            assertTrue(problem.code in setOf("ktlint_compilation_members_unavailable", "ktlint_compilation_members_invalid"))
        }
    }

    private fun input(configuration: String? = "root = true\n", extra: Boolean = false): AnalysisInput {
        val source = "package fixture\nimport java.io.*\nfun source() = Unit\n"
        val files = mutableListOf<SnapshotFile>()
        fun add(path: String, text: String) {
            Files.writeString(temporary.resolve(path), text)
            files += SnapshotFile(path, Digests.sha256(text), text.toByteArray().size.toLong())
        }
        add("source.kt", source)
        if (configuration != null) add(".editorconfig", configuration)
        if (extra) add("other.kt", source)
        return AnalysisInput(SnapshotManifest(Digests.sha256("unit descriptor, not integration capture"), "unit", "parser", files,
            captureKind = "immutable_overlay"), temporary.toString(), AnalysisContext(compilerVersion = KTLINT_EMBEDDED_KOTLIN,
            compilationFiles = listOf("source.kt")), listOf(rule))
    }

    private fun report(path: String = "source.kt", message: String = "unit parser Wildcard import") =
        """[{"file":${JsonPrimitive(path)},"errors":[{"line":2,"column":1,"message":${JsonPrimitive(message)},"rule":"standard:no-wildcard-imports"}]}]"""
}

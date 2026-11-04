package dev.ktchecker.providers.lint

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.ExecutionProblem
import kotlin.test.*

/** Synthetic identity/report transport tests, not detector fixtures or precision qualification. */
class LintCatalogTest {
    private val builtin = "com.android.tools.lint.checks.BuiltinIssueRegistry"

    @Test fun `all 609 artifact scoped records map exactly without promoting detector evidence`() {
        assertEquals(609, LintCatalog.rules.size)
        assertEquals(603, LintCatalog.rules.map { it.upstreamId }.toSet().size)
        assertEquals(609, LintCatalog.rules.map { it.registry to it.upstreamId }.toSet().size)
        for (rule in LintCatalog.rules) {
            assertEquals(rule, LintCatalog.byRuleId[rule.ruleId])
            assertTrue(rule.versions.isNotEmpty(), rule.ruleId)
            for (version in rule.versions) {
                assertTrue(Digests.valid(version.binaryDigest), rule.ruleId)
                assertEquals(rule, LintCatalog.resolve(rule.upstreamId, version.lintVersion, setOf(rule.registry)))
                val report = LintXmlParser.parse(xml(issue(rule.upstreamId)), "9.3.1")
                assertEquals(rule.upstreamId, report.issues.single().upstreamId)
                assertEquals(0, report.issues.single().locations.single().column)
            }
        }
        assertEquals(LintCatalog.ruleIds, AndroidLintProvider().supportedRuleIds)
    }

    @Test fun `AGP baseline 32_3_1 and newer lint 32_5_0 retain availability drift`() {
        val baseline = LintCatalog.rules.filter { it.registry == builtin && it.versions.any { v -> v.lintVersion == "32.3.1" } }
        val current = LintCatalog.rules.filter { it.registry == builtin && it.versions.any { v -> v.lintVersion == "32.5.0" } }
        assertEquals(512, baseline.size)
        assertEquals(517, current.size)
        assertEquals(setOf("DalvikOverride", "SquareAndRoundTilePreviews"), (baseline - current.toSet()).map { it.upstreamId }.toSet())
        assertEquals(7, (current - baseline.toSet()).size)
        assertEquals("lint_issue_version_unavailable:DalvikOverride", assertFailsWith<ExecutionProblem> {
            LintCatalog.resolve("DalvikOverride", "32.5.0", setOf(builtin))
        }.code)
        assertEquals("lint_issue_version_unavailable:CompileTimeConstant", assertFailsWith<ExecutionProblem> {
            LintCatalog.resolve("CompileTimeConstant", "32.3.1", setOf(builtin))
        }.code)
    }

    @Test fun `navigation duplicate upstream IDs are never collapsed across registries`() {
        for (upstream in listOf("MissingKeepAnnotation", "MissingSerializableAnnotation", "WrongStartDestinationType")) {
            val rows = LintCatalog.rules.filter { it.upstreamId == upstream }
            assertEquals(3, rows.size)
            assertEquals(3, rows.map { it.ruleId }.toSet().size)
            assertEquals("lint_issue_origin_ambiguous:$upstream", assertFailsWith<ExecutionProblem> {
                LintCatalog.resolve(upstream, "32.3.1", rows.map { it.registry }.toSet())
            }.code)
            for (row in rows) assertEquals(row.ruleId, LintCatalog.resolve(upstream, "32.3.1", setOf(row.registry)).ruleId)
        }
        assertFailsWith<ExecutionProblem> { LintCatalog.resolve("MissingKeepAnnotation", "32.3.1", setOf(builtin)) }
        assertFailsWith<ExecutionProblem> { LintCatalog.resolve("AnUnmappedIssue", "32.3.1", setOf(builtin)) }
    }

    @Test fun `maintained XML severity positions related locations and variants normalize exactly`() {
        for ((provider, expected) in listOf("Fatal" to "error", "Error" to "error", "Warning" to "warning", "Information" to "info")) {
            val issue = LintXmlParser.parse(xml("""
                <issue id="NewApi" severity="$provider" message="source diagnosis" explanation="specific evidence"
                  includedVariants="debug, release" excludedVariants="benchmark">
                  <location file="src/androidMain/Main.kt" line="2" column="3" endLine="3" endColumn="5"/>
                  <location file="src/commonMain/Api.kt" line="1" column="1"/>
                </issue>
            """), "9.3.1").issues.single()
            assertEquals(expected, issue.severity)
            assertEquals(2, issue.locations.size)
            assertEquals(2, issue.locations.first().line)
            assertEquals(2, issue.locations.first().column)
            assertEquals(4, issue.locations.first().endColumn)
            assertEquals(setOf("debug", "release"), issue.includedVariants)
            assertEquals(setOf("benchmark"), issue.excludedVariants)
        }
        assertEquals("9.3.1", LintRunPlan("32.3.1", emptyList(), emptySet()).expectedClientRevision)
        assertEquals("9.5.0", LintRunPlan("32.5.0", emptyList(), emptySet()).expectedClientRevision)
    }

    @Test fun `baselines unsafe XML version drift and malformed positions are explicit failures`() {
        for (bytes in listOf(
            byteArrayOf(), "not XML".toByteArray(),
            "<!DOCTYPE issues [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><issues format='6' by='lint 9.3.1'>&x;</issues>".toByteArray(),
            "<issues format='6' by='lint 9.3.1' type='baseline'/>".toByteArray(),
            "<issues format='6' by='lint 9.3.1' type='partial_results'/>".toByteArray(),
            "<issues format='5' by='lint 9.3.1'/>".toByteArray(),
            "<issues format='6'/>".toByteArray(), xml(issue("NewApi")).toString(Charsets.UTF_8).replace("9.3.1", "9.5.0").toByteArray(),
            xml(issue("NewApi")).toString(Charsets.UTF_8).replace("Warning", "Ignore").toByteArray(),
            xml(issue("NewApi")).toString(Charsets.UTF_8).replace("line=\"1\"", "line=\"0\"").toByteArray(),
            xml(issue("NewApi")).toString(Charsets.UTF_8).replace("column=\"1\"", "column=\"-1\"").toByteArray(),
            xml("<issue severity='Warning' message='missing identity'/>"),
            xml("<unexpected/>"), xml("unexpected non-whitespace"),
            xml(issue("NewApi")).toString(Charsets.UTF_8).replace("message=\"synthetic transport\"", "message=\"\"").toByteArray(),
        )) assertFailsWith<ExecutionProblem> { LintXmlParser.parse(bytes, "9.3.1") }
    }

    @Test fun `valid no-issue report is an attempt not release clean coverage`() {
        assertTrue(LintXmlParser.parse(xml(""), "9.3.1").issues.isEmpty())
        assertFalse(Coverage("ktc.android.lint.NewApi", state = "incomplete", provenance = "executed",
            certificateId = Digests.sha256("transport only"), implemented = true).successful())
    }

    private fun xml(issues: String): ByteArray = "<issues format=\"6\" by=\"lint 9.3.1\">$issues</issues>".toByteArray()
    private fun issue(upstream: String): String = """<issue id="$upstream" severity="Warning" message="synthetic transport"><location file="source.kt" line="1" column="1"/></issue>"""
}

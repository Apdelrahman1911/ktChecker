package dev.ktchecker.compiler

import dev.ktchecker.contracts.Digests
import java.nio.file.Path
import kotlin.test.*

/**
 * Unrun candidate tests for a root-adopted canonical445 successor.
 * They deliberately fail current443. No alternate runtime registry, compiler
 * process, actual toolchain or qualification is created. Header strings are
 * synthetic parser inputs, not compiler-run evidence. Custody is not tested here.
 */
class CompilerTwoDiagnosticMappingCandidateTest {
    @Test fun canonical445HasTheReviewedTwoCommonContracts() {
        val registry = CompilerRuleRegistry.load()
        assertEquals(445, registry.mappings.size)
        assertEquals(mapOf("common" to 294, "jvm" to 99, "native" to 43, "ir" to 9),
            registry.mappings.groupingBy { it.backend }.eachCount())
        assertEquals(402, registry.jvmRuleIds.size)
        val warning = registry.byUpstreamId.getValue("ERROR_SUPPRESSION")
        assertEquals("ktc.compiler.common.error_suppression", warning.ruleId)
        assertEquals("common", warning.backend)
        assertEquals("WARNING", warning.declaredSeverity)
        assertEquals("General syntax", warning.sourceSection)
        assertEquals("probable_defect", warning.kind)
        assertEquals("intent_dependent", warning.certainty)
        assertEquals("advisory", warning.defaultActivation)
        val error = registry.byUpstreamId.getValue("INAPPLICABLE_OPERATOR_MODIFIER")
        assertEquals("ktc.compiler.common.inapplicable_operator_modifier", error.ruleId)
        assertEquals("common", error.backend)
        assertEquals("ERROR", error.declaredSeverity)
        assertEquals("Modifiers", error.sourceSection)
        assertEquals("correctness", error.kind)
        assertEquals("high", error.certainty)
        assertEquals("enabled_when_validated", error.defaultActivation)
        assertNull(registry.byUpstreamId["INAPPLICABLE_OPERATOR_MODIFIER_WARNING"])
    }

    @Test fun commonRegistrationNeedsNoExtraCheckerSwitch() {
        val registry = CompilerRuleRegistry.load()
        for (id in listOf("ERROR_SUPPRESSION", "INAPPLICABLE_OPERATOR_MODIFIER")) {
            assertNull(CompilerFlags.activationGap(registry.byUpstreamId.getValue(id), emptyList()))
        }
    }

    @Test fun disabledWarningIsAnActivationGapNotACleanCheck() {
        val mapping = CompilerRuleRegistry.load().byUpstreamId.getValue("ERROR_SUPPRESSION")
        for (flags in listOf(listOf("-nowarn"), listOf("-Xwarning-level=ERROR_SUPPRESSION:disabled"))) {
            assertEquals(flags, CompilerFlags.validate(flags))
            assertEquals("compiler_warning_disabled_under_selected_configuration",
                CompilerFlags.activationGap(mapping, flags))
        }
    }

    @Test fun dedicatedUpstreamSuppressionSwitchIsNotSilentlyAuthorized() {
        assertFails { CompilerFlags.validate(listOf("-Xdont-warn-on-error-suppression")) }
    }

    @Test fun parserKeepsExactWarningIdEffectiveSeverityAndReportedPoint() {
        val path = Path.of("/isolated/two-diagnostic-source.kt")
        val source = "@Suppress(\"INAPPLICABLE_OPERATOR_MODIFIER\")\nfun safeValue(): Int = 1\n"
        val identity = CompilerSourceIdentity("two-diagnostic-source.kt", Digests.sha256(source), source)
        for (severity in listOf("warning", "error")) {
            val parsed = CompilerDiagnosticParser().parse(
                "info: kotlinc-jvm 2.4.21 (JRE 21)\n$path:1:11: $severity: [ERROR_SUPPRESSION] synthetic policy payload\n",
                mapOf(path to identity))
            assertFalse(parsed.malformed)
            assertEquals("2.4.21", parsed.reportedVersion)
            val diagnostic = parsed.diagnostics.single()
            assertEquals("ERROR_SUPPRESSION", diagnostic.upstreamId)
            assertEquals(severity, diagnostic.severity)
            val point = assertNotNull(diagnostic.location)
            assertEquals("two-diagnostic-source.kt", point.path)
            assertEquals(identity.digest, point.fileDigest)
            assertEquals(1, point.line)
            assertEquals(10, point.column)
            assertEquals(point.line, point.endLine)
            assertEquals(point.column, point.endColumn)
        }
    }

    @Test fun parserDoesNotAliasErrorFactoryAndWarningSibling() {
        val path = Path.of("/isolated/operator-source.kt")
        val source = "class Cursor {\n    operator fun hasNext(): Int = 1\n}\n"
        val identity = CompilerSourceIdentity("operator-source.kt", Digests.sha256(source), source)
        val registry = CompilerRuleRegistry.load()
        for ((id, severity) in listOf("INAPPLICABLE_OPERATOR_MODIFIER" to "error",
            "INAPPLICABLE_OPERATOR_MODIFIER_WARNING" to "warning")) {
            val parsed = CompilerDiagnosticParser().parse(
                "info: kotlinc-jvm 2.4.21 (JRE 21)\n$path:2:5: $severity: [$id] synthetic operator payload\n",
                mapOf(path to identity))
            assertFalse(parsed.malformed)
            val diagnostic = parsed.diagnostics.single()
            assertEquals(id, diagnostic.upstreamId)
            assertEquals(severity, diagnostic.severity)
            val point = assertNotNull(diagnostic.location)
            assertEquals(2, point.line)
            assertEquals(4, point.column)
            assertEquals(point.column, point.endColumn)
            if (id.endsWith("_WARNING")) assertNull(registry.byUpstreamId[id])
            else assertEquals("ktc.compiler.common.inapplicable_operator_modifier",
                registry.byUpstreamId.getValue(id).ruleId)
        }
    }
}

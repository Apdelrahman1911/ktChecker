package dev.ktchecker.providers.detekt

import org.junit.jupiter.api.Test
import kotlin.test.*

/** Authored-data controls only: passing these is NOT a detector or compiler execution. */
class DetektFullSemanticOracleTest {
    @Test fun `closed seven-control schedule and exact pre-authored expression ranges cannot expand from a generic flag`() {
        val cases = DetektFullSemanticCases.cases
        assertEquals(listOf("jdk_direct_and_alias", "standard_use_closure", "local_constructor_homonym", "resolved_ignore_class",
            "file_suppression_with_unchanged_neighbor", "source_defined_autocloseable", "local_use_homonym"), cases.map { it.id })
        assertEquals(listOf(2, 0, 0, 0, 1, 1, 1), cases.map { it.expected.size })
        assertTrue(cases.all { it.settings.enabled && it.semanticControl.isNotBlank() })
        for (case in cases) for (range in case.expected) {
            assertEquals(range.line, range.endLine)
            val line = case.sources.getValue(range.path).lines()[range.line - 1]
            assertEquals(range.expression, line.substring(range.column, range.endColumn), case.id)
        }
    }

    @Test fun `configuration and suppression controls preserve their exact unchanged positive neighbors`() {
        val cases = DetektFullSemanticCases.cases.associateBy { it.id }
        val direct = cases.getValue("jdk_direct_and_alias")
        val ignored = cases.getValue("resolved_ignore_class")
        assertEquals(direct.sources, ignored.sources)
        assertNotEquals(direct.settings, ignored.settings)
        assertEquals(listOf("java.io.FileInputStream"), ignored.settings.ignoreClasses)
        val suppressed = cases.getValue("file_suppression_with_unchanged_neighbor")
        assertEquals(direct.sources.getValue(DetektFullSemanticCases.DIRECT_PATH), suppressed.sources.getValue(DetektFullSemanticCases.DIRECT_PATH))
        assertEquals("@file:Suppress(\"MissingUseCall\")\n" + direct.sources.getValue(DetektFullSemanticCases.ALIAS_PATH),
            suppressed.sources.getValue(DetektFullSemanticCases.ALIAS_PATH))
        assertEquals(direct.expected.take(1), suppressed.expected)
        assertTrue(DetektFullSemanticCases.foreignSource.contains("FileInputStream(path)"))
        assertTrue(cases.values.all { it.sources.keys.none { path -> path.startsWith("src/test/") } })
    }
}

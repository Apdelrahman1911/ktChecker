package dev.ktchecker.providers.detekt

/** Independently authored pre-execution source/range oracles. Never generated from a report. */
internal data class DetektFullExpectedRange(
    val path: String,
    val line: Int,
    val column: Int,
    val endLine: Int,
    val endColumn: Int,
    val expression: String,
)

internal data class DetektFullSemanticCase(
    val id: String,
    val sources: Map<String, String>,
    val expected: List<DetektFullExpectedRange>,
    val settings: DetektFullSettings = DetektFullSettings(),
    val semanticControl: String,
)

/**
 * Pinned upstream MissingUseCall.kt resolves call return-type class symbols and their complete
 * supertype chains, then resolves only kotlin.io.use/kotlin.use as the recognized closures.
 * These seven actual-engine controls distinguish that semantic contract from name matching.
 * They do NOT prove all type resolution, compilation, catalog coverage or release qualification.
 */
internal object DetektFullSemanticCases {
    const val DIRECT_PATH = "src/main/kotlin/ADirect.kt"
    const val ALIAS_PATH = "src/main/kotlin/BAlias.kt"
    val directSource = """
        package full.direct

        import java.io.FileInputStream

        fun direct(path: String) {
            FileInputStream(path)
        }
    """.trimIndent() + "\n"
    val aliasSource = """
        package full.alias

        import java.io.FileInputStream as StreamAlias

        fun aliased(path: String) {
            StreamAlias(path)
        }
    """.trimIndent() + "\n"
    val directSources = mapOf(DIRECT_PATH to directSource, ALIAS_PATH to aliasSource)
    val foreignSource = """
        package full.foreign

        import java.io.FileInputStream

        fun foreignTestCompilation(path: String) {
            FileInputStream(path)
        }
    """.trimIndent() + "\n"
    private val directRange = DetektFullExpectedRange(DIRECT_PATH, 6, 4, 6, 25, "FileInputStream(path)")
    private val aliasRange = DetektFullExpectedRange(ALIAS_PATH, 6, 4, 6, 21, "StreamAlias(path)")

    val cases: List<DetektFullSemanticCase> = listOf(
        DetektFullSemanticCase("jdk_direct_and_alias", directSources, listOf(directRange, aliasRange),
            semanticControl = "Both imported JDK constructor spellings resolve to the same actual Closeable subtype."),
        DetektFullSemanticCase("standard_use_closure", mapOf(
            DIRECT_PATH to directSource.replace("FileInputStream(path)", "FileInputStream(path).use { it.read() }"),
            ALIAS_PATH to aliasSource.replace("StreamAlias(path)", "StreamAlias(path).use { it.read() }")), emptyList(),
            semanticControl = "The genuine standard use extension resolves and closes both streams; a negative alone is not passed coverage."),
        DetektFullSemanticCase("local_constructor_homonym", mapOf("src/main/kotlin/Homonym.kt" to """
            package full.homonym

            class FileInputStream(path: String)

            fun direct(path: String) {
                FileInputStream(path)
            }
        """.trimIndent() + "\n"), emptyList(),
            semanticControl = "Identical FileInputStream(path) call spelling but a local non-Closeable class must not be a finding."),
        DetektFullSemanticCase("resolved_ignore_class", directSources, emptyList(),
            settings = DetektFullSettings(ignoreClasses = listOf("java.io.FileInputStream")),
            semanticControl = "Identical positive bytes; exact resolved class identity is ignored under the separately bound owner setting."),
        DetektFullSemanticCase("file_suppression_with_unchanged_neighbor", mapOf(
            DIRECT_PATH to directSource,
            ALIAS_PATH to "@file:Suppress(\"MissingUseCall\")\n" + aliasSource), listOf(directRange),
            semanticControl = "Real file suppression affects only the aliased file; the separate-package unchanged direct neighbor still reports."),
        DetektFullSemanticCase("source_defined_autocloseable", mapOf("src/main/kotlin/Owned.kt" to """
            package full.owned

            class Owned : java.lang.AutoCloseable {
                override fun close() = Unit
            }

            fun leak() {
                Owned()
            }
        """.trimIndent() + "\n"),
            listOf(DetektFullExpectedRange("src/main/kotlin/Owned.kt", 8, 4, 8, 11, "Owned()")),
            semanticControl = "The source class resolves through its actual java.lang.AutoCloseable supertype, not an imported-name allowlist."),
        DetektFullSemanticCase("local_use_homonym", mapOf("src/main/kotlin/LocalUse.kt" to """
            package full.localuse

            import java.io.FileInputStream

            private fun FileInputStream.use(block: (FileInputStream) -> Unit) {
                block(this)
            }

            fun leak(path: String) {
                FileInputStream(path).use { it.read() }
            }
        """.trimIndent() + "\n"),
            listOf(DetektFullExpectedRange("src/main/kotlin/LocalUse.kt", 10, 4, 10, 25, "FileInputStream(path)")),
            semanticControl = "The local full.localuse.use extension does not close; same-spelled use is not kotlin.io.use/kotlin.use."),
    )
}

package dev.ktchecker.semantic

import kotlinx.serialization.Serializable
import java.net.URI
import java.nio.file.Path

internal const val NATIVE_EXPECT_ACTUAL_CASE = "incompatible-actual"
internal const val NATIVE_EXPECT_SOURCE = "negative/expectActual/commonMain/Expected.kt"
internal const val NATIVE_ACTUAL_SOURCE = "negative/expectActual/nativeMain/Actual.kt"
internal const val NATIVE_EXPECT_ACTUAL_DIAGNOSTIC = "EXPECT_ACTUAL_INCOMPATIBLE_RETURN_TYPE"

/** Private fixture evidence only. CLI numeric coordinates report a point, not a token endpoint. */
@Serializable
internal data class NativeExpectActualDiagnosticEvidence(
    val stream: String,
    val upstreamId: String,
    val severity: String,
    val reportedPoint: SemanticSpan,
    val renderedPhase: String? = null,
    val rangeKind: String = "compiler_reported_point",
)

/**
 * Narrow data-only decoder for the selected fixture's actual CLI streams. It grants no process
 * authority. The caller must separately require the original successful process outcome, exit 1,
 * and bound Native FIR facts. A diagnostic name in message/source text is not an emission.
 *
 * Kotlin v2.4.21 PlainTextMessageRenderer prints one-based UTF-16 line/column in its header.
 * Preserve that reported point: do not invent a declaration-name end from text or caret width.
 * Relative paths are resolved against the actual compiler working directory (the frozen snapshot),
 * never this test JVM's ambient working directory. Unknown/ambiguous evidence remains absent.
 */
internal fun parseNativeExpectActualDiagnostic(
    stdout: String, stderr: String, sourceRoot: Path, source: SemanticSource, sourceBytes: ByteArray,
): NativeExpectActualDiagnosticEvidence? {
    if (!sourceRoot.isAbsolute || source.path != NATIVE_ACTUAL_SOURCE || source.sourceSet != "nativeMain" ||
        stdout.length.toLong() + stderr.length > 2_097_152) return null
    val coordinates = SourceCoordinates(source, sourceBytes)
    val sourceRows = coordinates.text.lineSequence().map { it.removeSuffix("\r") }.toSet()
    val expectedPath = sourceRoot.resolve(source.path).normalize()
    var observed: NativeExpectActualDiagnosticEvidence? = null
    for ((stream, output) in listOf("stdout" to stdout, "stderr" to stderr)) {
        for (original in output.lineSequence()) {
            val line = original.removeSuffix("\r")
            if (line.length > 16_384) return null
            val header = NATIVE_LOCATED_HEADER.matchEntire(line)
            if (header == null) {
                val global = NATIVE_GLOBAL_HEADER.matchEntire(line)
                if (global != null && NATIVE_NAMED_BODY.matchEntire(global.groupValues[2])
                        ?.groupValues?.get(1) == NATIVE_EXPECT_ACTUAL_DIAGNOSTIC) return null
                continue
            }
            val named = NATIVE_NAMED_BODY.matchEntire(header.groupValues[5]) ?: continue
            if (named.groupValues[1] != NATIVE_EXPECT_ACTUAL_DIAGNOSTIC) continue
            if (observed != null || line in sourceRows || header.groupValues[4] != "error") return null
            val emittedPath = runCatching {
                val path = header.groupValues[1]
                val decoded = if (path.startsWith("file:")) Path.of(URI(path)) else Path.of(path)
                (if (decoded.isAbsolute) decoded else sourceRoot.resolve(decoded)).normalize()
            }.getOrNull() ?: return null
            if (emittedPath != expectedPath) return null
            val reportedLine = header.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
            val reportedColumn = header.groupValues[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
            val point = try {
                coordinates.span(reportedLine - 1, reportedColumn - 1, reportedLine - 1, reportedColumn - 1)
            } catch (_: SemanticFactException) {
                return null
            }
            observed = NativeExpectActualDiagnosticEvidence(stream, named.groupValues[1], header.groupValues[4],
                point, named.groupValues[2].takeIf { it.isNotEmpty() })
        }
    }
    return observed
}

private val NATIVE_LOCATED_HEADER = Regex("""^(.+):([0-9]+):([0-9]+): (error|warning|info): (.*)$""")
private val NATIVE_GLOBAL_HEADER = Regex("""^(error|warning|info): (.*)$""")
private val NATIVE_NAMED_BODY = Regex("""^\[([A-Z][A-Z0-9_]+)(?:\{([A-Za-z0-9_.-]+)})?] ?(.*)$""")

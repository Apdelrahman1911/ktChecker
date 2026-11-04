package dev.ktchecker.compiler

import dev.ktchecker.contracts.RawDiagnostic
import dev.ktchecker.contracts.SourceLocation
import java.net.URI
import java.nio.file.Path
import java.util.Collections

data class CompilerSourceIdentity(val path: String, val digest: String, val text: String)

data class ParsedCompilerDiagnostic(
    val upstreamId: String,
    val severity: String,
    val message: String,
    val location: SourceLocation?,
    val renderedPhase: String? = null,
)

data class CompilerParseResult(
    val diagnostics: List<ParsedCompilerDiagnostic>,
    val rawDiagnostics: List<RawDiagnostic>,
    val reportedVersion: String?,
    val malformed: Boolean,
)

/**
 * Parses compiler CLI headers, never a substring search in source snippets.
 * CLI columns are one-based UTF-16 coordinates. End position is the reported
 * point, not an invented token span. A quoted diagnostic name is not emission.
 */
class CompilerDiagnosticParser {
    fun parse(output: String, sourcePaths: Map<Path, CompilerSourceIdentity>): CompilerParseResult {
        val diagnostics = mutableListOf<ParsedCompilerDiagnostic>()
        val raw = mutableListOf<RawDiagnostic>()
        var version: String? = null
        var malformed = false
        var expectedSourceRow: String? = null
        var expectedCaret = false
        val identities = sourcePaths.mapKeys { it.key.toAbsolutePath().normalize().toString() }
        output.lineSequence().forEach { original ->
            val line = original.trimEnd('\r')
            if (expectedSourceRow != null && line == expectedSourceRow) {
                expectedSourceRow = null
                expectedCaret = true
                return@forEach
            }
            if (expectedCaret && CARET.matches(line)) {
                expectedCaret = false
                return@forEach
            }
            expectedSourceRow = null
            expectedCaret = false
            val versionMatch = VERSION.matchEntire(line)
            if (versionMatch != null) {
                val next = versionMatch.groupValues[1]
                if (version != null && version != next) malformed = true
                version = next
                return@forEach
            }
            val header = COLON_HEADER.matchEntire(line) ?: PAREN_HEADER.matchEntire(line)
            if (header != null) {
                val emittedPath = header.groupValues[1]
                val reportedLine = header.groupValues[2].toIntOrNull()
                val reportedColumn = header.groupValues[3].toIntOrNull()
                val severity = header.groupValues[4]
                val body = header.groupValues[5]
                if (body.length > MAX_MESSAGE) {
                    raw += RawDiagnostic("Compiler diagnostic exceeded the bounded message size: " + body.take(MAX_MESSAGE), severity)
                    malformed = true
                }
                val named = NAMED.matchEntire(body)
                val identity = resolveIdentity(emittedPath, identities)
                val location = if (identity != null && reportedLine != null && reportedColumn != null &&
                    validPoint(identity.text, reportedLine, reportedColumn)) {
                    SourceLocation(identity.path, identity.digest, reportedLine, reportedColumn - 1,
                        reportedLine, reportedColumn - 1)
                } else null
                if (named == null || location == null) {
                    raw += RawDiagnostic(body.take(MAX_MESSAGE), severity, named?.groupValues?.get(1), location)
                    malformed = true
                } else {
                    diagnostics += ParsedCompilerDiagnostic(named.groupValues[1], severity,
                        named.groupValues[3].take(MAX_MESSAGE), location,
                        named.groupValues[2].takeIf { it.isNotBlank() })
                    expectedSourceRow = identity!!.text.split('\n')[reportedLine!! - 1].removeSuffix("\r")
                }
                return@forEach
            }
            val global = GLOBAL_HEADER.matchEntire(line)
            if (global != null) {
                val severity = global.groupValues[1]
                val body = global.groupValues[2]
                val named = NAMED.matchEntire(body)
                // Locationless output is still genuine task evidence, not a
                // fabricated source finding. Info never hides warning/error.
                raw += RawDiagnostic(body.take(MAX_MESSAGE), severity, named?.groupValues?.get(1))
                malformed = true
                return@forEach
            }
            if (line.isBlank()) return@forEach
            // Plain source/caret rows are discarded only after a parsed header.
            // All unexplained task output remains visible and incomplete.
            raw += RawDiagnostic(line.take(MAX_MESSAGE), "info")
            malformed = true
        }
        return CompilerParseResult(Collections.unmodifiableList(diagnostics), Collections.unmodifiableList(raw),
            version, malformed)
    }

    private fun resolveIdentity(path: String, identities: Map<String, CompilerSourceIdentity>): CompilerSourceIdentity? {
        val decoded = runCatching { if (path.startsWith("file:")) Path.of(URI(path)).toString() else path }.getOrNull()
            ?: return null
        val normalized = runCatching { Path.of(decoded).toAbsolutePath().normalize().toString() }.getOrNull()
            ?: return null
        return identities[normalized]
    }

    private fun validPoint(text: String, line: Int, column: Int): Boolean {
        if (line < 1 || column < 1) return false
        val sourceLine = text.split('\n').getOrNull(line - 1)?.removeSuffix("\r") ?: return false
        return column <= sourceLine.length + 1
    }

    companion object {
        const val MAX_MESSAGE = 16_384
        private val VERSION = Regex("^(?:info: )?kotlinc-jvm ([0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?) \\(JRE [^\\r\\n)]+\\)$")
        private val COLON_HEADER = Regex("^(.+):([0-9]+):([0-9]+): (error|warning|info): (.*)$")
        private val PAREN_HEADER = Regex("^(.+): \\(([0-9]+), ([0-9]+)\\): (error|warning|info): (.*)$")
        private val GLOBAL_HEADER = Regex("^(error|warning|info): (.*)$")
        private val NAMED = Regex("^\\[([A-Z][A-Z0-9_]+)(?:\\{([A-Za-z0-9_.-]+)})?] ?(.*)$")
        private val CARET = Regex("^[\\s^~]+$")
    }
}

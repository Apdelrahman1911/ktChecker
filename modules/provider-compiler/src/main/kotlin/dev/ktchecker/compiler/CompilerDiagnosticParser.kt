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
        val identities = sourcePaths.mapKeys { it.key.toAbsolutePath().normalize().toString() }
        val messageShapedSourceRows = mutableSetOf<String>()
        var retainedSourceChars = 0
        var ambiguityLimitExceeded = false
        // Census the complete output before promoting any row. A source echo
        // can precede the located header that supplies its immutable witness.
        // Learn only rows referenced by valid located headers, including unnamed
        // headers; do not search unreferenced source text for diagnostic names.
        for (original in output.lineSequence()) {
            val line = original.trimEnd('\r')
            if (VERSION.matches(line)) continue // Same precedence as the parse.
            val header = COLON_HEADER.matchEntire(line) ?: PAREN_HEADER.matchEntire(line) ?: continue
            val identity = resolveIdentity(header.groupValues[1], identities) ?: continue
            val reportedLine = header.groupValues[2].toIntOrNull() ?: continue
            val reportedColumn = header.groupValues[3].toIntOrNull() ?: continue
            val sourceRow = validSourceRow(identity.text, reportedLine, reportedColumn) ?: continue
            if (!isMessageShapedRow(sourceRow) || sourceRow in messageShapedSourceRows) continue
            // String.length counts UTF-16 units, like the renderer coordinates.
            // Check duplicates first and subtract from the fixed aggregate cap.
            if (sourceRow.length > MAX_MESSAGE || messageShapedSourceRows.size >= MAX_AMBIGUOUS_SOURCE_ROWS ||
                sourceRow.length > MAX_AMBIGUOUS_SOURCE_CHARS - retainedSourceChars) {
                ambiguityLimitExceeded = true
                break // No prefix can be proved independent of later witnesses.
            }
            messageShapedSourceRows += sourceRow
            retainedSourceChars += sourceRow.length
        }
        val diagnostics = mutableListOf<ParsedCompilerDiagnostic>()
        val raw = mutableListOf<RawDiagnostic>()
        var version: String? = null
        var malformed = ambiguityLimitExceeded
        if (ambiguityLimitExceeded) raw += RawDiagnostic(AMBIGUITY_LIMIT_MESSAGE, "info")
        var expectedSourceRow: String? = null
        var expectedCaret = false
        var pendingMembers: AbstractMemberBlock? = null
        fun abandonMembers() {
            val block = pendingMembers ?: return
            if (block.members.isEmpty()) {
                // A header cut off before its first member must still leave raw
                // evidence: consumers must not mistake an empty raw list for a
                // complete task. Retain the genuine header, not an invented row.
                val diagnostic = diagnostics[block.diagnosticIndex]
                raw += RawDiagnostic(diagnostic.message, diagnostic.severity,
                    diagnostic.upstreamId, diagnostic.location)
            }
            block.members.forEach { raw += RawDiagnostic(it, "info") }
            if (block.sourceSeen) raw += RawDiagnostic(block.sourceRow.take(MAX_MESSAGE), "info")
            malformed = true
            pendingMembers = null
        }
        output.lineSequence().forEach { original ->
            val line = original.trimEnd('\r')
            if (line in messageShapedSourceRows || (ambiguityLimitExceeded && isMessageShapedRow(line))) {
                // Equality is ambiguous evidence, not a source/caret fence or
                // another emission. The whole-parse census also covers a row
                // equal to its own resolved source and echoes after abandonment.
                // On overflow this applies to ALL message rows, even the prefix.
                abandonMembers()
                expectedSourceRow = null
                expectedCaret = false
                raw += quarantinedMessage(line)
                malformed = true
                return@forEach
            }
            val block = pendingMembers
            if (block != null) {
                // A noncolliding new message wins. Header-shaped source rows
                // have already been quarantined using the whole-output census.
                val boundary = isMessageShapedRow(line)
                if (!boundary) {
                    if (block.sourceSeen) {
                        if (block.matchesCaret(line)) {
                            val diagnostic = diagnostics[block.diagnosticIndex]
                            diagnostics[block.diagnosticIndex] = diagnostic.copy(
                                message = diagnostic.message + "\n" + block.members.joinToString("\n"))
                            pendingMembers = null
                            return@forEach
                        }
                    } else if (line == block.sourceRow) {
                        if (memberCountMatches(block.plural, block.members.size)) {
                            block.sourceSeen = true
                            return@forEach
                        }
                    } else if (block.members.size < MAX_ABSTRACT_MEMBERS &&
                        (block.plural || block.members.isEmpty()) &&
                        line.length <= MAX_MESSAGE - block.messageSize - 1 && isSimpleMember(line)) {
                        block.members += line
                        block.messageSize += line.length + 1
                        return@forEach
                    }
                }
                // No partial attachment: retain the buffered rows as raw, then
                // process this row normally, including headers and global errors.
                abandonMembers()
            }
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
                val sourceRow = if (identity != null && reportedLine != null && reportedColumn != null) {
                    validSourceRow(identity.text, reportedLine, reportedColumn)
                } else null
                val location = if (identity != null && reportedLine != null && reportedColumn != null && sourceRow != null) {
                    SourceLocation(identity.path, identity.digest, reportedLine, reportedColumn - 1,
                        reportedLine, reportedColumn - 1)
                } else null
                if (named == null || location == null || sourceRow == null) {
                    raw += RawDiagnostic(body.take(MAX_MESSAGE), severity, named?.groupValues?.get(1), location)
                    malformed = true
                } else {
                    val diagnostic = ParsedCompilerDiagnostic(named.groupValues[1], severity,
                        named.groupValues[3].take(MAX_MESSAGE), location,
                        named.groupValues[2].takeIf { it.isNotBlank() })
                    diagnostics += diagnostic
                    val plural = abstractMemberPlural(diagnostic.upstreamId, severity, diagnostic.message)
                    if (body.length <= MAX_MESSAGE && plural != null) {
                        pendingMembers = AbstractMemberBlock(diagnostics.lastIndex, sourceRow,
                            location.column, plural, diagnostic.message.length)
                    } else {
                        expectedSourceRow = sourceRow
                    }
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
        abandonMembers() // EOF is not a successful source/caret fence.
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

    private fun validSourceRow(text: String, line: Int, column: Int): String? {
        if (line < 1 || column < 1) return null
        val row = text.splitToSequence('\n').drop(line - 1).firstOrNull()?.removeSuffix("\r") ?: return null
        return row.takeIf { column - 1 <= it.length }
    }

    private fun isMessageShapedRow(line: String): Boolean =
        VERSION.matches(line) || COLON_HEADER.matches(line) || PAREN_HEADER.matches(line) || GLOBAL_HEADER.matches(line)

    private fun quarantinedMessage(line: String): RawDiagnostic {
        // Preserve the old global body/severity/name shape, but never assign a
        // location to an ambiguous row. A claimed name is not a parsed finding.
        // Bound the body BEFORE extracting its name; an oversized identifier
        // must not survive via RawDiagnostic.upstreamId after message truncation.
        if (VERSION.matches(line)) return RawDiagnostic(line.take(MAX_MESSAGE), "info")
        val header = COLON_HEADER.matchEntire(line) ?: PAREN_HEADER.matchEntire(line)
        if (header != null) {
            val body = header.groupValues[5].take(MAX_MESSAGE)
            return RawDiagnostic(body, header.groupValues[4], NAMED.matchEntire(body)?.groupValues?.get(1))
        }
        val global = GLOBAL_HEADER.matchEntire(line)
        if (global != null) {
            val body = global.groupValues[2].take(MAX_MESSAGE)
            return RawDiagnostic(body, global.groupValues[1], NAMED.matchEntire(body)?.groupValues?.get(1))
        }
        return RawDiagnostic(line.take(MAX_MESSAGE), "info")
    }

    private class AbstractMemberBlock(
        val diagnosticIndex: Int,
        val sourceRow: String,
        val column: Int,
        val plural: Boolean,
        var messageSize: Int,
    ) {
        val members = mutableListOf<String>()
        var sourceSeen = false

        fun matchesCaret(line: String): Boolean {
            val width = line.length - column
            if (width < 1 || width > maxOf(1, sourceRow.length - column)) return false
            return line.indices.all { line[it] == if (it < column) ' ' else '^' }
        }
    }

    companion object {
        const val MAX_MESSAGE = 16_384
        private const val MAX_ABSTRACT_MEMBERS = 32
        private const val MAX_AMBIGUOUS_SOURCE_ROWS = 64
        private const val MAX_AMBIGUOUS_SOURCE_CHARS = 4 * MAX_MESSAGE
        private const val AMBIGUITY_LIMIT_MESSAGE = "Compiler header/source ambiguity tracking limit exceeded"
        private const val ABSTRACT_MEMBER_ID = "ABSTRACT_MEMBER_NOT_IMPLEMENTED"
        private const val SIMPLE_NAME = "[A-Za-z_][A-Za-z0-9_]*"
        private val VERSION = Regex("^(?:info: )?kotlinc-jvm ([0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?) \\(JRE [^\\r\\n)]+\\)$")
        private val COLON_HEADER = Regex("^(.+):([0-9]+):([0-9]+): (error|warning|info): (.*)$")
        private val PAREN_HEADER = Regex("^(.+): \\(([0-9]+), ([0-9]+)\\): (error|warning|info): (.*)$")
        private val GLOBAL_HEADER = Regex("^(error|warning|info): (.*)$")
        private val NAMED = Regex("^\\[([A-Z][A-Z0-9_]+)(?:\\{([A-Za-z0-9_.-]+)})?] ?(.*)$")
        private val CARET = Regex("^[\\s^~]+$")

        // Kotlin v2.4.21 FirErrorsDefaultMessages + SYMBOLS_ON_NEXT_LINES:
        // no indentation, one body/accessor-free symbol per line. This is an
        // intentionally small subset, not a general Kotlin signature parser.
        private val ABSTRACT_MEMBER_HEADER = Regex(
            "^(?:Class|Object|Interface) '[^'\\r\\n]+' is not abstract and does not implement abstract (member|members):$")
        private val SIMPLE_TYPE = "$SIMPLE_NAME\\??"
        private val SIMPLE_PROPERTY = Regex("^(?:val|var) $SIMPLE_NAME: $SIMPLE_TYPE$")
        private val SIMPLE_FUNCTION = Regex("^(?:suspend )?fun $SIMPLE_NAME\\(([^()]*)\\): $SIMPLE_TYPE$")
        private val SIMPLE_PARAMETER = Regex("^$SIMPLE_NAME: $SIMPLE_TYPE$")

        private fun abstractMemberPlural(upstreamId: String, severity: String, header: String): Boolean? {
            if (upstreamId != ABSTRACT_MEMBER_ID || severity != "error") return null
            val match = ABSTRACT_MEMBER_HEADER.matchEntire(header) ?: return null
            return match.groupValues[1] == "members"
        }

        private fun memberCountMatches(plural: Boolean, count: Int): Boolean =
            if (plural) count in 2..MAX_ABSTRACT_MEMBERS else count == 1

        private fun isSimpleMember(line: String): Boolean {
            if (SIMPLE_PROPERTY.matches(line)) return true
            val function = SIMPLE_FUNCTION.matchEntire(line) ?: return false
            val parameters = function.groupValues[1]
            return parameters.isEmpty() || parameters.split(", ").all { SIMPLE_PARAMETER.matches(it) }
        }

        // parse attaches these rows only after the complete source/caret fence.
        // The provider calls this only on its existing trusted, selected finding
        // path. Advice is not an implementation, verified fix or qualification.
        internal fun fixHint(diagnostic: ParsedCompilerDiagnostic): String? {
            if (diagnostic.location == null || diagnostic.message.length > MAX_MESSAGE) return null
            val rows = diagnostic.message.split('\n')
            val plural = abstractMemberPlural(diagnostic.upstreamId, diagnostic.severity, rows.first()) ?: return null
            if (!memberCountMatches(plural, rows.size - 1) || rows.drop(1).any { !isSimpleMember(it) }) return null
            return "Implement the listed abstract members with compatible overrides, or inherit compatible concrete " +
                "implementations. Recompile in the same context to verify."
        }
    }
}

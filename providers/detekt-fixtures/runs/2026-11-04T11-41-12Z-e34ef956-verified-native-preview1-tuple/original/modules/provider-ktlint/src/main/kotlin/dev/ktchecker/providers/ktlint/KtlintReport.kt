package dev.ktchecker.providers.ktlint

import dev.ktchecker.contracts.AnalysisInput
import dev.ktchecker.contracts.SourceLocation
import dev.ktchecker.providers.runtime.ExecutionProblem
import dev.ktchecker.providers.runtime.SnapshotSources
import dev.ktchecker.providers.runtime.strictUtf8
import kotlinx.serialization.json.*

data class KtlintDiagnostic(val upstreamId: String, val message: String, val location: SourceLocation)
data class KtlintReport(val diagnostics: List<KtlintDiagnostic>)

/** Genuine upstream JSON only. PSI detections are performed by the pinned child process. */
class KtlintReportParser {
    fun parse(bytes: ByteArray, input: AnalysisInput): KtlintReport {
        try {
            val document = strictUtf8(bytes)
            val entries = Json.parseToJsonElement(document).jsonArray
            rejectDuplicateKeys(document)
            val sources = SnapshotSources(input)
            val files = mutableSetOf<String>()
            val errors = mutableSetOf<String>()
            val diagnostics = entries.flatMap { entry ->
                val file = entry.jsonObject
                require(file.keys == setOf("file", "errors"))
                val path = file.text("file") ?: error("missing file")
                val source = sources.location(path)
                require(source.path in input.context.compilationFiles.orEmpty() && files.add(source.path))
                val reported = file["errors"]!!.jsonArray
                require(reported.isNotEmpty()) // Upstream JSON omits clean files entirely.
                reported.map { reportedError ->
                    val item = reportedError.jsonObject
                    require(item.keys == setOf("line", "column", "message", "rule"))
                    val line = item.integer("line") ?: error("missing line")
                    val column = item.integer("column") ?: error("missing column")
                    require(column > 0)
                    val message = item.text("message") ?: error("missing message")
                    val upstream = item.text("rule") ?: error("missing rule")
                    require(message.isNotBlank())
                    // Upstream uses an empty rule ID for parse/internal failures.
                    if (upstream.isEmpty()) throw ExecutionProblem("ktlint_parse_or_rule_failure", "Upstream Kotlin parse/rule failure; no clean coverage")
                    require(upstream.matches(Regex("[a-z][a-z0-9-]*:[a-z][a-z0-9-]*")))
                    val location = sources.location(path, line, column - 1, line, column - 1)
                    require(errors.add(listOf(location.path, line, column, upstream, message).joinToString("\u0000")))
                    KtlintDiagnostic(upstream, message, location)
                }
            }
            return KtlintReport(diagnostics)
        } catch (problem: ExecutionProblem) {
            throw problem
        } catch (_: Exception) {
            throw ExecutionProblem("ktlint_report_malformed", "Invalid or incomplete isolated Ktlint JSON report")
        }
    }

    /** Empty [] alone is not execution evidence: the actual CLI must scan every exact member. */
    fun verifyCompletion(stdout: String, stderr: String, files: Int, errors: Int, exitCode: Int?) {
        val combined = stdout + "\n" + stderr
        val summaries = Regex("Finished processing in [0-9]+ms / ([0-9]+) file\\(s\\) scanned / ([0-9]+) error\\(s\\) found").findAll(combined).toList()
        if (summaries.size != 1 || summaries.single().groupValues[1].toIntOrNull() != files ||
            summaries.single().groupValues[2].toIntOrNull() != errors || exitCode != (if (errors == 0) 0 else 1) ||
            "No files matched" in combined || "Skipping rule(s)" in combined || "Internal Error" in combined) {
            throw ExecutionProblem("ktlint_execution_incomplete", "Upstream completion/exit/report does not establish every exact compilation member was scanned")
        }
    }

    private fun JsonObject.text(key: String): String? = this[key]?.let { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
    private fun JsonObject.integer(key: String): Int? = this[key]?.let { (it as? JsonPrimitive)?.takeUnless { primitive -> primitive.isString }?.intOrNull }

    private data class KeyScope(val keys: MutableSet<String>?, var expectingKey: Boolean = true)

    /** JsonObject otherwise silently keeps the last duplicate field, including escaped aliases. */
    private fun rejectDuplicateKeys(document: String) {
        // Syntax is already validated above. This lexical pass only preserves object-key identity.
        val scopes = mutableListOf<KeyScope>()
        var offset = 0
        while (offset < document.length) {
            when (document[offset]) {
                '{' -> scopes += KeyScope(mutableSetOf())
                '[' -> scopes += KeyScope(null)
                '}', ']' -> scopes.removeAt(scopes.lastIndex)
                ',' -> scopes.lastOrNull()?.let { if (it.keys != null) it.expectingKey = true }
                '"' -> {
                    val start = offset++
                    while (document[offset] != '"') offset += if (document[offset] == '\\') 2 else 1
                    offset++
                    scopes.lastOrNull()?.let { scope ->
                        if (scope.keys != null && scope.expectingKey) {
                            val key = Json.decodeFromString<String>(document.substring(start, offset))
                            require(scope.keys.add(key)) { "Duplicate report member" }
                            scope.expectingKey = false
                        }
                    }
                    continue
                }
            }
            offset++
        }
    }
}

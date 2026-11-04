package dev.ktchecker.providers.native

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.providers.runtime.ExecutionProblem
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

@Serializable data class NativeRule(
    val ruleId: String,
    val upstreamId: String,
    val title: String,
    val severity: String,
    val family: String,
    val provider: String,
)

object NativeCatalog {
    val rules: List<NativeRule> by lazy {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/dev/ktchecker/providers/native/kotlin-native-catalog.json")).use { it.readBytes() }
        ContractJson.decodeFromString<List<NativeRule>>(bytes.toString(Charsets.UTF_8)).also {
            require(it.size == 344 && it.map { rule -> rule.ruleId }.toSet().size == 344 &&
                it.map { rule -> rule.upstreamId }.toSet().size == 344)
            require(it.none { rule -> rule.ruleId.startsWith("ktc.compiler.jvm.") })
        }
    }
    val byName: Map<String, NativeRule> by lazy { rules.associateBy { it.upstreamId } }
    val byId: Map<String, NativeRule> by lazy { rules.associateBy { it.ruleId } }
}

data class NativeDiagnostic(
    val name: String?, val severity: String, val message: String,
    val path: String? = null, val line: Int? = null, val column: Int? = null,
)
data class NativeTask(val path: String, val outcome: String) {
    val name: String get() = path.substringAfterLast(':')
    val actuallyExecuted: Boolean get() = outcome in setOf("EXECUTED", "FAILED")
}
data class NativeOutput(val diagnostics: List<NativeDiagnostic>, val tasks: List<NativeTask>, val buildSuccessful: Boolean)

/** Named Native compiler output. Unknown/unnamed diagnostics are preserved, never guessed by text. */
object NativeDiagnosticParser {
    private val prefix = Regex("^([ewi]):\\s+(.+?):(\\d+):(\\d+)\\s*:?\\s*(.*)$")
    private val old = Regex("^(error|warning|info):\\s+(.+?):\\s*\\((\\d+),\\s*(\\d+)\\):\\s*(.*)$")
    private val direct = Regex("^(.+?):(\\d+):(\\d+):\\s*(error|warning|info):\\s*(.*)$")
    private val global = Regex("^([ewi]|error|warning|info):\\s*(.*)$")
    private val task = Regex("^> Task (:[^ ]+)(?: (UP-TO-DATE|FROM-CACHE|SKIPPED|NO-SOURCE|FAILED))?\\s*$")
    private val name = Regex("^\\[([A-Z][A-Z0-9_]*)]\\s*(.*)$")
    private val ansi = Regex("\\u001B\\[[0-?]*[ -/]*[@-~]")

    fun parse(stdout: String, stderr: String): NativeOutput {
        val diagnostics = mutableListOf<NativeDiagnostic>()
        val tasks = mutableListOf<NativeTask>()
        for (original in (stdout + "\n" + stderr).lineSequence()) {
            val line = ansi.replace(original, "").trimEnd()
            val t = task.matchEntire(line)
            if (t != null) {
                tasks += NativeTask(t.groupValues[1], t.groupValues[2].ifBlank { "EXECUTED" })
                continue
            }
            val p = prefix.matchEntire(line)
            val o = old.matchEntire(line)
            val d = direct.matchEntire(line)
            val g = global.matchEntire(line)
            val diagnostic = when {
                p != null -> diagnostic(p.groupValues[1], p.groupValues[5], p.groupValues[2], p.groupValues[3], p.groupValues[4])
                o != null -> diagnostic(o.groupValues[1], o.groupValues[5], o.groupValues[2], o.groupValues[3], o.groupValues[4])
                d != null -> diagnostic(d.groupValues[4], d.groupValues[5], d.groupValues[1], d.groupValues[2], d.groupValues[3])
                g != null -> diagnostic(g.groupValues[1], g.groupValues[2], null, null, null)
                else -> null
            }
            if (diagnostic != null) diagnostics += diagnostic
        }
        return NativeOutput(diagnostics, tasks, stdout.lineSequence().any { it.startsWith("BUILD SUCCESSFUL") })
    }

    private fun diagnostic(severity: String, text: String, path: String?, line: String?, column: String?): NativeDiagnostic {
        val named = name.matchEntire(text)
        val row = line?.toIntOrNull()
        val col = column?.toIntOrNull()
        if ((line != null && (row == null || row < 1)) || (column != null && (col == null || col < 1))) {
            throw ExecutionProblem("native_location_invalid", "Invalid Native diagnostic range")
        }
        return NativeDiagnostic(named?.groupValues?.get(1), normalizeSeverity(severity),
            named?.groupValues?.get(2) ?: text, path, row, col?.minus(1))
    }
    private fun normalizeSeverity(value: String): String = when (value) {
        "e", "error" -> "error"
        "w", "warning" -> "warning"
        "i", "info" -> "info"
        else -> throw ExecutionProblem("native_severity_invalid", "Unknown compiler severity")
    }
}

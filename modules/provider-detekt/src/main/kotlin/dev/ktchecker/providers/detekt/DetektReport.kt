package dev.ktchecker.providers.detekt

import dev.ktchecker.contracts.AnalysisInput
import dev.ktchecker.contracts.SourceLocation
import dev.ktchecker.providers.runtime.ExecutionProblem
import dev.ktchecker.providers.runtime.SnapshotSources
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.*

data class DetektDiagnostic(
    val sarifId: String,
    val message: String,
    val severity: String,
    val location: SourceLocation,
    val relatedLocations: List<SourceLocation> = emptyList(),
    val signature: String? = null,
)

data class DetektReport(
    val enabledSarifIds: Set<String>,
    val diagnostics: List<DetektDiagnostic>,
)

/** Parses genuine SARIF. No source spelling/regular-expression substitute detectors. */
class DetektReportParser {
    fun parse(bytes: ByteArray, input: AnalysisInput): DetektReport {
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val root = Json.parseToJsonElement(text).jsonObject
            require(root.text("version") == "2.1.0")
            val runs = root["runs"]!!.jsonArray
            require(runs.size == 1)
            val run = runs.single().jsonObject
            val driver = run["tool"]!!.jsonObject["driver"]!!.jsonObject
            require(driver.text("name") == "detekt")
            val invocations = run["invocations"]?.jsonArray.orEmpty()
            require(invocations.none { it.jsonObject["executionSuccessful"]?.jsonPrimitive?.booleanOrNull == false })
            val descriptors = driver["rules"]?.jsonArray.orEmpty().map { it.jsonObject }
            val descriptorIds = descriptors.map { it.text("id") ?: error("missing descriptor id") }
            require(descriptorIds.distinct().size == descriptorIds.size)
            val enabled = descriptors.filter {
                it["defaultConfiguration"]?.jsonObject?.get("enabled")?.jsonPrimitive?.booleanOrNull == true
            }.map { it.text("id")!! }.toSet()
            val sources = SnapshotSources(input)
            val diagnostics = run["results"]?.jsonArray.orEmpty().map { result ->
                val item = result.jsonObject
                val sarifId = item.text("ruleId") ?: error("missing diagnostic ruleId")
                require(sarifId in descriptorIds && sarifId in enabled)
                val message = item["message"]!!.jsonObject.text("text") ?: error("missing diagnostic message")
                require(message.isNotBlank())
                val locations = item["locations"]!!.jsonArray.map { location ->
                    val physical = location.jsonObject["physicalLocation"]!!.jsonObject
                    val artifact = physical["artifactLocation"]!!.jsonObject
                    require(artifact.text("uriBaseId") in setOf(null, "%SRCROOT%"))
                    val uri = artifact.text("uri") ?: error("missing source uri")
                    val region = physical["region"]!!.jsonObject
                    val line = region.integer("startLine") ?: error("missing startLine")
                    val column = region.integer("startColumn") ?: error("missing startColumn")
                    require(column > 0)
                    val endLine = region.integer("endLine") ?: line
                    val endColumn = region.integer("endColumn") ?: column
                    require(endColumn > 0)
                    sources.location(uri, line, column - 1, endLine, endColumn - 1).also {
                        require(input.context.compilationFiles?.contains(it.path) == true)
                    }
                }
                require(locations.isNotEmpty())
                val descriptor = descriptors.single { it.text("id") == sarifId }
                val severity = item.text("level") ?: descriptor["defaultConfiguration"]?.jsonObject?.text("level") ?: "warning"
                require(severity in setOf("error", "warning", "note", "none"))
                val signature = item["partialFingerprints"]?.jsonObject?.text("signature/v1")
                DetektDiagnostic(sarifId, message, severity, locations.first(), locations.drop(1), signature)
            }
            return DetektReport(enabled, diagnostics)
        } catch (problem: ExecutionProblem) {
            throw problem
        } catch (_: Exception) {
            throw ExecutionProblem("detekt_report_malformed", "Invalid or incomplete isolated Detekt SARIF report")
        }
    }

    private fun JsonObject.text(key: String): String? = this[key]?.let {
        (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content
    }
    private fun JsonObject.integer(key: String): Int? = this[key]?.let {
        (it as? JsonPrimitive)?.takeUnless { primitive -> primitive.isString }?.intOrNull
    }
}

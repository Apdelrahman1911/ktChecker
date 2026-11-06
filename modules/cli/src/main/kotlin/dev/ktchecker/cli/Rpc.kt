package dev.ktchecker.cli

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.PREVIEW_CONTRACT
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.*

/** Exact private-RPC wire profile, not an authentication or source-read capability. */
internal const val FINDING_WIRE_PROFILE = "ktchecker/finding-retrieval-attachments-1"

/** Private NDJSON transport. MCP framing/version negotiation belongs to the SDK bridge. */
fun interface RpcHandler { fun call(method: String, params: JsonObject): JsonObject }

class CliProblem(val code: String, override val message: String) : RuntimeException(message)

internal fun strictJsonDocument(bytes: ByteArray, errorCode: String): String = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        .also { StrictJsonInput(it).check() }
} catch (_: Exception) {
    throw CliProblem(errorCode, "Expected bounded unambiguous UTF-8 JSON with unique object keys.")
}

class RpcServer(
    private val handler: RpcHandler,
    private val maxRequestBytes: Int = 16_777_216,
    private val maxResponseBytes: Int = 16_777_216,
) {
    init { require(maxRequestBytes in 128..16_777_216 && maxResponseBytes in 128..16_777_216) }

    fun serve(input: InputStream, output: OutputStream) {
        while (true) {
            val bytes = readLine(input) ?: return
            val response = try {
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                handle(decoder.decode(ByteBuffer.wrap(bytes)).toString())
            } catch (_: Exception) { error("", "invalid_request", "Expected one bounded UTF-8 JSON request.") }
            val encoded = ContractJson.encodeToString(JsonObject.serializer(), response).toByteArray(Charsets.UTF_8)
            val bounded = if (encoded.size <= maxResponseBytes) encoded else {
                val id = response["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                ContractJson.encodeToString(JsonObject.serializer(), error(id, "response_too_large",
                    "Result exceeds this transport page bound; use a smaller event page or artifact retrieval.")).toByteArray(Charsets.UTF_8)
            }
            output.write(bounded)
            output.write('\n'.code)
            output.flush()
        }
    }

    fun handle(line: String): JsonObject {
        var id = ""
        return try {
            if (line.length > maxRequestBytes || line.toByteArray(Charsets.UTF_8).size > maxRequestBytes) {
                throw CliProblem("request_too_large", "Request exceeds the transport bound.")
            }
            StrictJsonInput(line).check()
            val request = ContractJson.parseToJsonElement(line) as? JsonObject
                ?: throw CliProblem("invalid_request", "A request object is required.")
            requireKeys(request, setOf("id", "method", "params", "findingWireProfile"))
            val idValue = request["id"] as? JsonPrimitive
            if (idValue == null || !idValue.isString || idValue.content.length !in 1..256) {
                throw CliProblem("invalid_request", "A bounded string request ID is required.")
            }
            id = idValue.content
            val methodValue = request["method"] as? JsonPrimitive
            if (methodValue == null || !methodValue.isString ||
                !methodValue.content.matches(Regex("[a-z][a-zA-Z0-9_.]{0,127}"))) {
                throw CliProblem("invalid_request", "A method name is required.")
            }
            val params = request["params"] as? JsonObject
                ?: throw CliProblem("invalid_request", "A params object is required.")
            // Check every request before credential decoding or any handler side effect. An
            // earlier admitted request never grants a later request or reconnected peer admission.
            val profile = request["findingWireProfile"] as? JsonPrimitive
            if (profile == null || !profile.isString || profile.content != FINDING_WIRE_PROFILE) {
                throw CliProblem("incompatible_finding_wire_profile",
                    "This broker requires a matching ktChecker finding wire profile; rebuild or install matching peers.")
            }
            buildJsonObject {
                put("contractVersion", PREVIEW_CONTRACT)
                put("id", id)
                put("findingWireProfile", FINDING_WIRE_PROFILE)
                put("result", handler.call(methodValue.content, params))
            }
        } catch (e: CliProblem) {
            error(id, e.code, e.message)
        } catch (_: Exception) {
            // Tokens, source contents, commands, private filenames and stack traces never go to the model.
            error(id, "operation_failed", "Operation failed; inspect trusted local evidence before retrying.")
        }
    }

    private fun readLine(input: InputStream): ByteArray? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value == -1) return if (buffer.size() == 0) null else buffer.toByteArray()
            if (value == '\n'.code) return buffer.toByteArray()
            if (buffer.size() >= maxRequestBytes) {
                // Close instead of draining attacker-controlled unbounded input or guessing framing.
                throw CliProblem("request_too_large", "Request exceeds the transport bound.")
            }
            buffer.write(value)
        }
    }

    private fun error(id: String, code: String, message: String) = buildJsonObject {
        put("contractVersion", PREVIEW_CONTRACT); put("id", id)
        putJsonObject("error") { put("code", code); put("message", message) }
    }
}

/** Bounded preflight rejects ambiguous duplicate keys BEFORE any envelope or credential is read.
 * The serialization parser remains authoritative for decoded DTO types. Limits also apply to
 * direct handle() callers, rather than only the byte transport. Strings are decoded to detect
 * escaped key aliases ("token" and "\\u0074oken") in every nested object.
 */
internal class StrictJsonInput(private val source: String, private val maxDepth: Int = 64) {
    private var position = 0
    private var values = 0

    fun check() {
        value(0)
        whitespace()
        if (position != source.length) invalid()
    }

    private fun value(depth: Int) {
        if (depth > maxDepth || ++values > 200_000) invalid()
        whitespace()
        when (source.getOrNull(position)) {
            '{' -> {
                position++
                val keys = HashSet<String>()
                whitespace()
                if (take('}')) return
                while (true) {
                    whitespace()
                    if (source.getOrNull(position) != '"' || !keys.add(string())) invalid()
                    whitespace()
                    if (!take(':')) invalid()
                    value(depth + 1)
                    whitespace()
                    if (take('}')) return
                    if (!take(',')) invalid()
                }
            }
            '[' -> {
                position++
                whitespace()
                if (take(']')) return
                while (true) {
                    value(depth + 1)
                    whitespace()
                    if (take(']')) return
                    if (!take(',')) invalid()
                }
            }
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            '-', in '0'..'9' -> {
                val start = position
                while (position < source.length && source[position] !in " \r\n\t,]}") position++
                if (!NUMBER.matches(source.substring(start, position))) invalid()
            }
            else -> invalid()
        }
    }

    private fun string(): String {
        val start = position++
        while (position < source.length) {
            val character = source[position++]
            if (character == '"') {
                val decoded = try { ContractJson.decodeFromString<String>(source.substring(start, position)) }
                    catch (_: Exception) { invalid() }
                var i = 0
                while (i < decoded.length) {
                    val next = decoded[i++]
                    if (Character.isHighSurrogate(next)) {
                        if (i >= decoded.length || !Character.isLowSurrogate(decoded[i++])) invalid()
                    } else if (Character.isLowSurrogate(next)) invalid()
                }
                return decoded
            }
            if (character.code < 0x20) invalid()
            if (character == '\\') {
                if (position == source.length) invalid()
                when (source[position++]) {
                    '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                    'u' -> repeat(4) {
                        if (position == source.length || source[position++].digitToIntOrNull(16) == null) invalid()
                    }
                    else -> invalid()
                }
            }
        }
        invalid()
    }

    private fun literal(expected: String) {
        if (!source.startsWith(expected, position)) invalid()
        position += expected.length
    }
    private fun take(character: Char): Boolean = if (source.getOrNull(position) == character) { position++; true } else false
    private fun whitespace() { while (position < source.length && source[position] in " \n\r\t") position++ }
    private fun invalid(): Nothing = throw CliProblem("invalid_request", "Expected bounded unambiguous JSON with unique object keys.")
    companion object { private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?") }
}

internal fun requireKeys(value: JsonObject, allowed: Set<String>) {
    if ((value.keys - allowed).isNotEmpty()) throw CliProblem("invalid_request", "Unknown request fields are not allowed.")
}

internal inline fun <reified T> JsonObject.decode(name: String): T = ContractJson.decodeFromJsonElement(
    this[name] ?: throw CliProblem("invalid_request", "A required parameter is missing."),
)

internal inline fun <reified T> result(value: T): JsonObject = ContractJson.encodeToJsonElement(value).jsonObject

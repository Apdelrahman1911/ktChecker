package dev.ktchecker.cli

import dev.ktchecker.contracts.ContractJson
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

class OwnerInputTest {
    private fun file(bytes: ByteArray): String = Files.createTempFile("ktchecker-owner-json-", ".json")
        .also { Files.write(it, bytes) }.toString()

    @Test fun ownerDocumentsRejectDuplicateAndEscapedAliasKeys() {
        listOf("""{"approved":false,"approved":true}""", """{"nested":{"rule":"a","\u0072ule":"b"}}""",
            """{"items":[{"owner":"one","owner":"two"}]}""").forEach {
            val failure = assertFailsWith<CliProblem> { readOwnerFile<JsonObject>(file(it.toByteArray())) }
            assertEquals("invalid_owner_file", failure.code)
        }
    }

    @Test fun ownerDocumentsRejectMalformedUnicodeAndBoundNestedContainers() {
        listOf(byteArrayOf(0xC3.toByte(), 0x28), """{"name":"\ud800"}""".toByteArray(),
            ("[".repeat(65) + "0" + "]".repeat(65)).toByteArray()).forEach { bytes ->
            assertEquals("invalid_owner_file", assertFailsWith<CliProblem> { readOwnerFile<JsonElement>(file(bytes)) }.code)
        }
        assertEquals("雪🌎", readOwnerFile<JsonObject>(file("""{"name":"雪🌎"}""".toByteArray(Charsets.UTF_8)))["name"]!!.jsonPrimitive.content)
    }

    @Test fun ownerDocumentsRejectUnknownDtoFieldsInsteadOfSilentlyIgnoringThem() {
        val path = file("""{"ownerId":"owner-test","format":1,"approved":true}""".toByteArray())
        assertEquals("invalid_owner_file", assertFailsWith<CliProblem> { readOwnerFile<OwnerRecord>(path) }.code)
    }

    @Test fun machineJsonRemainsUtf8EvenWithAnAsciiConsole() {
        val value = buildJsonObject { put("name", "雪🌎"); put("lines", "one\ntwo") }
        val bytes = ByteArrayOutputStream()
        PrintStream(bytes, true, Charsets.US_ASCII).use { writeJsonLine(value, it) }
        val output = bytes.toString(Charsets.UTF_8)
        assertEquals(value.toString() + "\n", output)
        assertEquals(value, ContractJson.parseToJsonElement(output))
        assertEquals(1, output.count { it == '\n' })
    }

    @Test fun malformedNumericOptionsNeverFallBackToDefaults() {
        assertFailsWith<CliProblem> { Arguments(arrayOf("--workers", "oops")).integer("workers", 2) }
        assertFailsWith<CliProblem> { Arguments(arrayOf("--wait-ms", "1.5")).long("wait-ms", 0) }
        assertFailsWith<CliProblem> { Arguments(arrayOf("--limit", "999999999999999")).integer("limit", 20) }
        assertEquals(2, Arguments(emptyArray()).integer("workers", 2))
        assertEquals(250L, Arguments(arrayOf("--wait-ms", "250")).long("wait-ms", 0))
    }

    @Test fun corruptedPrivateJsonIsNotReinterpretedOnRestart() {
        val state = PrivateState(Files.createTempDirectory("ktchecker-strict-private-"))
        val file = state.root.resolve("synthetic.json")
        Files.writeString(file, """{"ownerId":"one","ownerId":"two"}""")
        assertEquals("state_invalid", assertFailsWith<CliProblem> { state.readText(file) }.code)
        Files.write(file, byteArrayOf(0xC0.toByte(), 0x80.toByte()))
        assertEquals("state_invalid", assertFailsWith<CliProblem> { state.readText(file) }.code)
    }
}

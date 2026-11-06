package dev.ktchecker.cli

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.PREVIEW_CONTRACT
import dev.ktchecker.contracts.Coverage
import dev.ktchecker.contracts.ProviderResult
import dev.ktchecker.kernel.ReviewStatus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.*
import kotlin.test.*

class RpcTest {
    private val server = RpcServer(RpcHandler { method, params -> buildJsonObject { put("method", method); put("params", params) } })

    @Test fun versionedEnvelopeAndIndependentIds() {
        val out = ByteArrayOutputStream()
        server.serve(ByteArrayInputStream(("""{"id":"a","method":"review.status","params":{},"findingWireProfile":"$FINDING_WIRE_PROFILE"}""" + "\n" +
            """{"id":"b","method":"review.events","params":{"limit":1},"findingWireProfile":"$FINDING_WIRE_PROFILE"}""").toByteArray()), out)
        val responses = out.toString(Charsets.UTF_8).trim().lines().map { ContractJson.parseToJsonElement(it).jsonObject }
        assertEquals(listOf("a", "b"), responses.map { it.getValue("id").jsonPrimitive.content })
        responses.forEach { assertEquals(PREVIEW_CONTRACT, it.getValue("contractVersion").jsonPrimitive.content); assertFalse("error" in it) }
    }

    @Test fun malformedAndUnknownFieldsNeverReachHandler() {
        val denying = RpcServer(RpcHandler { _, _ -> error("must not call") })
        listOf("null", "[]", "{", """{"id":1,"method":"review.status","params":{}}""",
            """{"id":"x","method":"../exec","params":{}}""",
            """{"id":"x","method":"review.status","params":{},"approved":true}""").forEach {
            assertEquals("invalid_request", denying.handle(it).getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        }
    }

    @Test fun secretExceptionsNeverReachWire() {
        val failing = RpcServer(RpcHandler { _, _ -> error("secret-token /private/file.kt") })
        val value = failing.handle("""{"id":"q","method":"review.status","params":{},"findingWireProfile":"$FINDING_WIRE_PROFILE"}""").toString()
        assertTrue(value.contains("operation_failed")); assertFalse(value.contains("secret-token")); assertFalse(value.contains("file.kt"))
    }

    @Test fun duplicateKeysIncludingEscapedAliasesAreRejectedBeforeDispatch() {
        var calls = 0
        val guarded = RpcServer(RpcHandler { _, _ -> calls++; JsonObject(emptyMap()) })
        listOf(
            """{"id":"a","id":"b","method":"review.status","params":{}}""",
            """{"id":"a","method":"review.status","params":{"credential":{"token":"first","\u0074oken":"second"}}}""",
            """{"id":"a","method":"review.status","params":{"x":[{"a":1,"a":2}]}}""",
        ).forEach { assertEquals("invalid_request", guarded.handle(it)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content) }
        assertEquals(0, calls)
        assertFalse("error" in guarded.handle("""{"id":"a","method":"review.status","params":{"x":{"a":1},"y":{"a":2}},"findingWireProfile":"$FINDING_WIRE_PROFILE"}"""))
    }

    @Test fun deepMalformedAndOversizedDirectRequestsAreBounded() {
        val nested = "[".repeat(65) + "0" + "]".repeat(65)
        listOf(nested, "1e", "01", "[truefalse]", "[0,]", """"\ud800"""", """{"a":0,}""").forEach {
            assertFailsWith<CliProblem> { StrictJsonInput(it).check() }
        }
        StrictJsonInput("""{"unicode":"\ud83c\udf0e","number":-1.2E+3,"value":[null,true,false]}""").check()
        val small = RpcServer(RpcHandler { _, _ -> error("not called") }, maxRequestBytes = 128)
        assertEquals("request_too_large", small.handle(" ".repeat(129))["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test fun boundedInputRejectsWithoutUnboundedDrain() {
        val input = ByteArrayInputStream(ByteArray(2_000) { 'x'.code.toByte() })
        assertFailsWith<CliProblem> { RpcServer(RpcHandler { _, _ -> JsonObject(emptyMap()) }, maxRequestBytes = 128).serve(input, ByteArrayOutputStream()) }
        assertTrue(input.available() >= 1_870)
    }

    @Test fun invalidUtf8IsNotSilentlyReplaced() {
        val out = ByteArrayOutputStream()
        server.serve(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28, '\n'.code.toByte())), out)
        assertTrue(out.toString(Charsets.UTF_8).contains("invalid_request"))
    }

    @Test fun boundedResponsesAreNotFalsePasses() {
        val tooLarge = RpcServer(RpcHandler { _, _ -> buildJsonObject { put("payload", "x".repeat(5_000)) } }, maxResponseBytes = 512)
        val out = ByteArrayOutputStream()
        tooLarge.serve(ByteArrayInputStream("""{"id":"q","method":"review.status","params":{},"findingWireProfile":"$FINDING_WIRE_PROFILE"}""".toByteArray()), out)
        assertTrue(out.toString(Charsets.UTF_8).contains("response_too_large")); assertFalse(out.toString(Charsets.UTF_8).contains("result"))
    }

    @Test fun doctorDoesNotClaimReleaseOrTargetExecution() {
        assertEquals(false, doctor().getValue("releaseQualified").jsonPrimitive.boolean)
        assertEquals("preview", doctor().getValue("status").jsonPrimitive.content)
        assertTrue(doctor().getValue("gaps").jsonArray.isNotEmpty())
    }

    @Test fun cliNeverUpgradesIncompleteKernelVerdictToExitZero() {
        val raw = ProviderResult("test", "complete", coverage = listOf(Coverage("ktc.test", state = "passed",
            provenance = "executed", certificateId = "structural-only")))
        val status = ReviewStatus("review", "client", "patch", 1, "snapshot", "incomplete", raw, listOf("unqualified_rule_context"))
        assertEquals(2, reviewExit(status))
        assertEquals(2, reviewExit(status.copy(state = "complete", reasons = emptyList(), cancelled = true)))
        assertEquals(2, reviewExit(status.copy(state = "complete", reasons = emptyList(), result = raw.copy(reasons = listOf("preview_not_release_qualified")))))
    }
}

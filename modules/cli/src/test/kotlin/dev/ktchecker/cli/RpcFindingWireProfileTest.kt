package dev.ktchecker.cli

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.PREVIEW_CONTRACT
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.*
import kotlin.test.*

/** Transport controls only: these do not attest real broker recovery, delivery, or model observation. */
class RpcFindingWireProfileTest {
    @Test fun missingMalformedAndUnsupportedProfilesCannotDispatchStatefulOperations() {
        var calls = 0
        val server = RpcServer(RpcHandler { _, _ -> calls++; JsonObject(emptyMap()) })
        val unsupported = listOf<JsonElement?>(null, JsonNull, JsonPrimitive(true), JsonPrimitive(1),
            JsonArray(emptyList()), JsonObject(emptyMap()), JsonPrimitive(""),
            JsonPrimitive("ktchecker/finding-retrieval-attachments-0"),
            JsonPrimitive("ktchecker/finding-retrieval-attachments-2"),
            JsonPrimitive("$FINDING_WIRE_PROFILE "))
        val methods = listOf("client.register", "review.submit", "review.status", "review.cancel",
            "review.events", "review.ack", "review.observe", "gate.prepare", "gate.complete",
            "review.policy.reassess", "review.policy.status")
        for (method in methods) for ((index, profile) in unsupported.withIndex()) {
            val id = "request-$index"
            val response = server.handle(request(id, method, profile))
            assertEquals(id, response.getValue("id").jsonPrimitive.content)
            assertEquals("incompatible_finding_wire_profile", errorCode(response))
            assertEquals(setOf("id", "contractVersion", "error"), response.keys)
            assertEquals(PREVIEW_CONTRACT, response.getValue("contractVersion").jsonPrimitive.content)
        }
        assertEquals(0, calls)
    }

    @Test fun compatibleRequestsEchoProfileAndPreserveFullPayloadAndDigest() {
        // A frozen synthetic event string. The transport must not strip the new field, normalize
        // locators, parse/rewrite payload JSON, or substitute its own wire profile inside payloads.
        val payload = """{"findings":[{"retrievalAttachments":[{"kind":"detekt-report","digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","artifactRef":"reports/full report.json"}]}],"findingWireProfile":"payload-not-authority"}"""
        // Independent wire literal: a coordinated change to the implementation constant and
        // default request helper must not silently change this peer compatibility contract.
        assertEquals("ktchecker/finding-retrieval-attachments-1", FINDING_WIRE_PROFILE)
        val digest = Digests.sha256(payload)
        var calls = 0
        val server = RpcServer(RpcHandler { _, params ->
            calls++
            buildJsonObject {
                put("clientId", params.getValue("clientId"))
                put("payload", payload)
                put("payloadDigest", digest)
            }
        })
        val responses = exchange(server,
            request("one", profile = JsonPrimitive("ktchecker/finding-retrieval-attachments-1"),
                params = buildJsonObject { put("clientId", "agent-a") }),
            request("two", params = buildJsonObject { put("clientId", "agent-b") }))
        assertEquals(listOf("one", "two"), responses.map { it.getValue("id").jsonPrimitive.content })
        assertEquals(listOf("agent-a", "agent-b"), responses.map { it.getValue("result").jsonObject.getValue("clientId").jsonPrimitive.content })
        responses.forEach { response ->
            assertEquals(setOf("contractVersion", "id", "findingWireProfile", "result"), response.keys)
            assertEquals(FINDING_WIRE_PROFILE, response.getValue("findingWireProfile").jsonPrimitive.content)
            val result = response.getValue("result").jsonObject
            assertEquals(payload, result.getValue("payload").jsonPrimitive.content)
            assertEquals(digest, result.getValue("payloadDigest").jsonPrimitive.content)
            assertEquals(digest, Digests.sha256(result.getValue("payload").jsonPrimitive.content))
        }
        assertEquals(2, calls)
    }

    @Test fun anEarlierAcceptedRequestDoesNotGrantLaterRequestsOnTheSameStream() {
        val dispatched = mutableListOf<String>()
        val server = RpcServer(RpcHandler { method, _ ->
            dispatched += method
            buildJsonObject { put("method", method) }
        })
        val responses = exchange(server,
            request("same-request-id", "review.events"),
            request("same-request-id", "review.cancel", null),
            request("same-request-id", "review.ack", JsonPrimitive("ktchecker/finding-retrieval-attachments-2")),
            request("same-request-id", "review.status"))
        // Correlation IDs are not admission grants, even when deliberately reused.
        assertEquals(List(4) { "same-request-id" },
            responses.map { it.getValue("id").jsonPrimitive.content })
        assertEquals(listOf("review.events", "review.status"), dispatched)
        for (index in listOf(0, 3)) assertEquals(FINDING_WIRE_PROFILE,
            responses[index].getValue("findingWireProfile").jsonPrimitive.content)
        for (index in listOf(1, 2)) {
            assertEquals("incompatible_finding_wire_profile", errorCode(responses[index]))
            assertFalse("result" in responses[index])
            assertFalse("findingWireProfile" in responses[index])
        }
    }

    @Test fun aFreshServerRequiresAdmissionAgainWithoutReplayingRequests() {
        var calls = 0
        val handler = RpcHandler { _, _ -> calls++; buildJsonObject { put("calls", calls) } }
        assertEquals(1, RpcServer(handler).handle(request("before")).getValue("result").jsonObject.getValue("calls").jsonPrimitive.int)
        val after = RpcServer(handler)
        assertEquals("incompatible_finding_wire_profile", errorCode(after.handle(request("missing", profile = null))))
        assertEquals(1, calls)
        assertEquals(2, after.handle(request("after")).getValue("result").jsonObject.getValue("calls").jsonPrimitive.int)
        assertEquals(2, calls)
    }

    @Test fun profileAdmissionDoesNotReplaceHandlerAuthorizationAndErrorsStayOrdinary() {
        var calls = 0
        val server = RpcServer(RpcHandler { method, _ ->
            calls++
            if (method == "review.ack") throw CliProblem("permission_denied", "Adapter credential is still required.")
            error("private-token /private/project/Source.kt")
        })
        val denied = server.handle(request("denied", "review.ack"))
        assertEquals("permission_denied", errorCode(denied))
        val failed = server.handle(request("failed"))
        assertEquals("operation_failed", errorCode(failed))
        for (response in listOf(denied, failed)) {
            assertEquals(setOf("contractVersion", "id", "error"), response.keys)
            assertFalse(response.toString().contains("private-token"))
            assertFalse(response.toString().contains("Source.kt"))
        }
        assertEquals(2, calls)
    }

    @Test fun oversizedSuccessIsNotRetriedOrRelabeledAsCompatibleSuccess() {
        var calls = 0
        val server = RpcServer(RpcHandler { _, _ ->
            calls++
            buildJsonObject { put("payload", "x".repeat(5000)) }
        }, maxResponseBytes = 512)
        val response = exchange(server, request("large")).single()
        assertEquals("large", response.getValue("id").jsonPrimitive.content)
        assertEquals("response_too_large", errorCode(response))
        assertEquals(setOf("contractVersion", "id", "error"), response.keys)
        assertEquals(1, calls)
    }

    private fun request(
        id: String,
        method: String = "review.status",
        profile: JsonElement? = JsonPrimitive(FINDING_WIRE_PROFILE),
        params: JsonObject = JsonObject(emptyMap()),
    ): String = buildJsonObject {
        put("id", id); put("method", method); put("params", params)
        profile?.let { put("findingWireProfile", it) }
    }.toString()

    private fun exchange(server: RpcServer, vararg requests: String): List<JsonObject> {
        val output = ByteArrayOutputStream()
        server.serve(ByteArrayInputStream(requests.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)), output)
        return output.toString(Charsets.UTF_8).trimEnd().lines().map { ContractJson.parseToJsonElement(it).jsonObject }
    }

    private fun errorCode(response: JsonObject): String = response.getValue("error").jsonObject.getValue("code").jsonPrimitive.content
}

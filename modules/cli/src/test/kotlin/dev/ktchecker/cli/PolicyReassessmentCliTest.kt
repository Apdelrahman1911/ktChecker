package dev.ktchecker.cli

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import dev.ktchecker.kernel.ClientCredential
import dev.ktchecker.kernel.PolicyReassessmentRequest
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

/** Parsing/transport and denied real-owner controls, not a fabricated certified execution or completion. */
class PolicyReassessmentCliTest {
    private val digest = Digests.sha256("synthetic-current-policy-identifier")
    private val credential = ClientCredential("client", "synthetic-client-transport-token-not-owner-authority")

    private fun document(): JsonObject = buildJsonObject {
        put("reviewId", "review-original"); put("idempotencyKey", "policy-request-雪")
        put("generation", 2); put("expectedPolicyDigest", digest)
    }
    private fun file(bytes: ByteArray): Path = Files.createTempFile("ktchecker-policy-cli-request-", ".json")
        .also { Files.write(it, bytes) }
    private fun file(value: JsonElement = document()): Path = file(value.toString().toByteArray(Charsets.UTF_8))
    private fun reassess(path: Path): Array<String> = arrayOf("reassess", "--client", "client", "--request", path.toString())
    private fun status(): Array<String> = arrayOf("policy-status", "--client", "client", "--id", "policy-request-雪")
    private fun rejected(document: JsonObject) {
        assertEquals("invalid_policy_reassessment_request", assertFailsWith<CliProblem> {
            decodePolicyReassessmentCliRequest(document)
        }.code)
    }
    private fun noOwner(args: Array<String>) {
        val parent = Files.createTempDirectory("ktchecker-policy-cli-preowner-")
        val state = parent.resolve("unopened-owner")
        assertEquals(3, runCli(args + arrayOf("--state", state.toString())))
        assertFalse(Files.exists(state, NOFOLLOW_LINKS), "Rejected invocation must precede owner creation or acceptance")
    }

    @Test fun exactTypedRequestIsReadWithoutModificationOrAuthorityExpansion() {
        val path = file()
        val before = Files.readAllBytes(path)
        val selected = assertIs<PolicyReassessmentCliCommand.Reassess>(parsePolicyReassessmentCli(Arguments(reassess(path))))
        assertEquals("client", selected.clientId)
        assertEquals(PolicyReassessmentRequest("review-original", "policy-request-雪", 2, digest), selected.request)
        assertEquals(document(), result(selected.request))
        assertContentEquals(before, Files.readAllBytes(path))
    }

    @Test fun requestSchemaRejectsEveryMissingFieldAndExtraAuthorityOrRawBody() {
        for (name in document().keys) rejected(JsonObject(document() - name))
        for (name in listOf("input", "raw", "result", "configuration", "capture", "credential", "authorization",
            "executionAuthorized", "ownerAction", "certificate", "request", "schemaVersion")) {
            rejected(JsonObject(document() + (name to JsonPrimitive("not-authority"))))
        }
    }

    @Test fun generationRequiresAnUnquotedPositiveJsonLongWithoutCoercion() {
        for (literal in listOf("null", "true", "false", "\"2\"", "2.0", "2e0", "0", "-1", "9223372036854775808", "[]", "{}")) {
            rejected(JsonObject(document() + ("generation" to ContractJson.parseToJsonElement(literal))))
        }
        for (generation in listOf(1L, Long.MAX_VALUE)) {
            val input = JsonObject(document() + ("generation" to JsonPrimitive(generation)))
            assertEquals(generation, decodePolicyReassessmentCliRequest(input).generation)
            val selected = assertIs<PolicyReassessmentCliCommand.Reassess>(
                parsePolicyReassessmentCli(Arguments(reassess(file(input)))))
            assertEquals(generation, selected.request.generation)
        }
    }

    @Test fun nonCanonicalAndOverflowGenerationDocumentsRejectBeforeOwnerCreation() {
        val tokens = listOf(
            // Valid JSON numbers/strings must fail our token/type check before Workbench.
            "2e0", "2E0", "2e+0", "2E+0", "2e-0", "2E-0", "1e1", "1E+1",
            "2.0", "2.000", "0.5", "-0.5", "\"2\"", "\"9223372036854775807\"",
            "9223372036854775808", "92233720368547758070", "18446744073709551615",
            "0", "-0", "-2", "null", "true",
            // These may fail at strict JSON preflight instead; none may open an owner.
            "+2", "02", "00", "2.", ".2", "\"+2\"", "\"02\"",
            "\u0662", "\uFF12", "2\u200B", "2\u0000", "\u00A02",
        )
        for (token in tokens) {
            val raw = """{"reviewId":"review-original","idempotencyKey":"policy-request-雪","generation":$token,"expectedPolicyDigest":"$digest"}"""
            val path = file(raw.toByteArray(Charsets.UTF_8))
            val before = Files.readAllBytes(path)
            noOwner(reassess(path))
            assertContentEquals(before, Files.readAllBytes(path))
        }
    }

    @Test fun identityFieldsRemainStringsAndRejectBlankControlOrUnboundedIds() {
        for (name in listOf("reviewId", "idempotencyKey", "expectedPolicyDigest")) {
            for (literal in listOf("null", "true", "2", "[]", "{}")) {
                rejected(JsonObject(document() + (name to ContractJson.parseToJsonElement(literal))))
            }
        }
        for (name in listOf("reviewId", "idempotencyKey")) {
            for (value in listOf("", " \t", "line\nbreak", "control\u0000id", "x".repeat(513))) {
                rejected(JsonObject(document() + (name to JsonPrimitive(value))))
            }
        }
        for (value in listOf("", "sha256:1234", digest.uppercase(), digest.removePrefix("sha256:"), "sha512:" + "0".repeat(64))) {
            rejected(JsonObject(document() + ("expectedPolicyDigest" to JsonPrimitive(value))))
        }
    }

    @Test fun commandShapeAndMissingIdentifiersRejectBeforeOwnerCreation() {
        val path = file()
        listOf(
            arrayOf("reassess", "--request", path.toString()),
            arrayOf("reassess", "--client", "client"),
            arrayOf("policy-status", "--id", "request"),
            arrayOf("policy-status", "--client", "client"),
            reassess(path) + "unexpected-word", status() + "unexpected-word",
            arrayOf("policy-status", "--client", " ", "--id", "request"),
            arrayOf("policy-status", "--client", "client", "--id", "bad\nrequest"),
        ).forEach(::noOwner)
    }

    @Test fun ownerExecutionMutationAndReviewOptionsCannotEnterEitherCommand() {
        val path = file()
        val forbidden = listOf(arrayOf("--owner-action"), arrayOf("--authorize-execution"),
            arrayOf("--prepared", "never-read.json"), arrayOf("--expected", "never-read.json"),
            arrayOf("--finding", "never-read.json"), arrayOf("--layer", "shared"), arrayOf("--rule", "rule"),
            arrayOf("--wait-ms", "0"), arrayOf("--review", "review"), arrayOf("--cursor", "cursor"),
            arrayOf("--limit", "1"), arrayOf("--selection-digest", digest))
        for (base in listOf(reassess(path), status())) for (extra in forbidden) {
            assertEquals("invalid_arguments", assertFailsWith<CliProblem> {
                parsePolicyReassessmentCli(Arguments(base + extra))
            }.code)
            noOwner(base + extra)
        }
        noOwner(reassess(path) + arrayOf("--id", "not-a-second-request"))
        noOwner(status() + arrayOf("--request", path.toString()))
    }

    @Test fun zeroWorkersAreDefaultAndNonzeroOrMalformedWorkersRejectBeforeOwner() {
        val path = file()
        for (base in listOf(reassess(path), status())) {
            assertEquals(parsePolicyReassessmentCli(Arguments(base)),
                parsePolicyReassessmentCli(Arguments(base + arrayOf("--workers", "0", "--format", "json"))))
            for (value in listOf("1", "2", "-1", "oops", "0.0", "999999999999")) {
                noOwner(base + arrayOf("--workers", value))
            }
        }
    }

    @Test fun duplicateUnknownOptionsAndNonJsonFormatRejectBeforeOwner() {
        val path = file()
        noOwner(reassess(path) + arrayOf("--client", "another-client"))
        noOwner(status() + arrayOf("--unknown-policy-permission", "true"))
        noOwner(status() + arrayOf("--format", "text"))
        noOwner(arrayOf("policy-status", "--client", "client", "--id"))
    }

    @Test fun ambiguousUnicodeDuplicateAndWrongRootDocumentsFailBeforeOwner() {
        val duplicate = document().toString().dropLast(1) + ",\"reviewId\":\"another\"}"
        val escapedDuplicate = document().toString().dropLast(1) + ",\"\\u0072eviewId\":\"another\"}"
        val inputs = listOf(duplicate.toByteArray(), escapedDuplicate.toByteArray(), byteArrayOf(0xC3.toByte(), 0x28),
            "null".toByteArray(), "[]".toByteArray(), "{}".toByteArray(), "{".toByteArray())
        for (bytes in inputs) {
            val path = file(bytes)
            val before = Files.readAllBytes(path)
            noOwner(reassess(path))
            assertContentEquals(before, Files.readAllBytes(path))
        }
    }

    @Test fun outputPreflightNeverReservesOverwritesOrCreatesDirectories() {
        val request = file()
        val parent = Files.createTempDirectory("ktchecker-policy-cli-output-")
        val existing = parent.resolve("keep.json")
        Files.writeString(existing, "retain original output\n")
        val missingParent = parent.resolve("missing")
        for (out in listOf(existing.toString(), request.toString(), parent.toString(), missingParent.resolve("view.json").toString(), "")) {
            noOwner(reassess(request) + arrayOf("--out", out))
            assertEquals("retain original output\n", Files.readString(existing))
            assertFalse(Files.exists(missingParent, NOFOLLOW_LINKS))
        }
        val out = parent.resolve("new-view.json")
        parsePolicyReassessmentCli(Arguments(reassess(request) + arrayOf("--out", out.toString())))
        assertFalse(Files.exists(out, NOFOLLOW_LINKS), "Preflight must not reserve or publish output")
    }

    @Test fun parsedRequestCannotBeReplacedByLaterOwnerFileEdits() {
        val path = file()
        val selected = parsePolicyReassessmentCli(Arguments(reassess(path)))
        Files.writeString(path, "not a request anymore")
        var calls = 0
        callPolicyReassessmentCli(selected, credential, RpcHandler { method, params ->
            calls++
            assertEquals("review.policy.reassess", method)
            assertEquals(document(), params.getValue("request"))
            buildJsonObject { put("transportTestOnly", true) }
        })
        assertEquals(1, calls)
    }

    @Test fun reassessmentDispatchPreservesAuditStateWithoutManufacturingAReviewVerdict() {
        val selected = parsePolicyReassessmentCli(Arguments(reassess(file())))
        for (auditOnly in listOf(true, false)) {
            // A transport sentinel only: no certificate/raw result and never submitted to a kernel.
            val sentinel = buildJsonObject {
                put("state", "policy_reassessed"); put("auditOnly", auditOnly)
                putJsonArray("reasons") { add("transport_test_only_not_completion") }
            }
            var calls = 0
            val response = callPolicyReassessmentCli(selected, credential, RpcHandler { method, params ->
                calls++
                assertEquals("review.policy.reassess", method)
                assertEquals(setOf("credential", "request"), params.keys)
                assertEquals(result(credential), params["credential"])
                assertEquals(document(), params["request"])
                sentinel
            })
            assertSame(sentinel, response)
            assertEquals("policy_reassessed", response.getValue("state").jsonPrimitive.content)
            assertEquals(auditOnly, response.getValue("auditOnly").jsonPrimitive.boolean)
            assertFalse("complete" in response || "passing" in response || "allow" in response)
            assertEquals(1, calls)
        }
    }

    @Test fun statusDispatchIsOnlyHistoricalLookupAndHasNoReassessmentRequest() {
        val selected = parsePolicyReassessmentCli(Arguments(status()))
        var calls = 0
        val sentinel = buildJsonObject { put("state", "policy_reassessed"); put("auditOnly", true) }
        assertSame(sentinel, callPolicyReassessmentCli(selected, credential, RpcHandler { method, params ->
            calls++
            assertEquals("review.policy.status", method)
            assertEquals(setOf("credential", "idempotencyKey"), params.keys)
            assertEquals("policy-request-雪", params.getValue("idempotencyKey").jsonPrimitive.content)
            assertEquals(result(credential), params["credential"])
            sentinel
        }))
        assertEquals(1, calls)
    }

    @Test fun mismatchedSelectedCredentialCannotReachEitherRpc() {
        for (selected in listOf(parsePolicyReassessmentCli(Arguments(reassess(file()))), parsePolicyReassessmentCli(Arguments(status())))) {
            var calls = 0
            assertEquals("permission_denied", assertFailsWith<CliProblem> {
                callPolicyReassessmentCli(selected, credential.copy(clientId = "other-client"), RpcHandler { _, _ ->
                    calls++
                    error("Mismatched selected credential must not dispatch")
                })
            }.code)
            assertEquals(0, calls)
        }
    }

    @Test fun duplicatePolicyRpcKeysNeverReachEvenATransportHandler() {
        var calls = 0
        val rpc = RpcServer(RpcHandler { _, _ -> calls++; error("Ambiguous policy request must not dispatch") })
        val response = rpc.handle("""{"id":"wire","method":"review.policy.reassess","params":{"request":{"reviewId":"one","\u0072eviewId":"two"}}}""")
        assertEquals("invalid_request", response.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        assertEquals(0, calls)
    }

    @Test fun realPolicyRpcRejectsAuthorityExtrasAndMissingOriginalWithoutCreatingViewsOrWork() {
        Workbench(WorkbenchOptions(Files.createTempDirectory("ktchecker-policy-cli-rpc-"), workerCount = 0)).use { work ->
            val registered = work.register("project", "tree", "client", "session", "lineage")
            val before = work.inspectConfig("client")
            val rpc = RpcServer(work)
            fun call(method: String, params: JsonObject): JsonObject = rpc.handle(buildJsonObject {
                put("id", "policy-wire"); put("method", method); put("params", params)
                put("findingWireProfile", FINDING_WIRE_PROFILE)
            }.toString())
            val request = JsonObject(document() + ("generation" to JsonPrimitive(registered.binding.generation)))
            val reassessParams = buildJsonObject { put("credential", result(registered.client)); put("request", request) }
            val statusParams = buildJsonObject { put("credential", result(registered.client)); put("idempotencyKey", "policy-request-雪") }
            for ((method, params) in listOf("review.policy.reassess" to reassessParams, "review.policy.status" to statusParams)) {
                for (name in listOf("input", "raw", "capture", "ownerAction", "authorizeExecution")) {
                    val response = call(method, JsonObject(params + (name to JsonPrimitive("not-authority"))))
                    assertEquals("invalid_request", response.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                }
            }
            for (name in listOf("input", "result", "configuration", "authorization", "certificate")) {
                val response = call("review.policy.reassess", JsonObject(reassessParams +
                    ("request" to JsonObject(request + (name to JsonPrimitive("not-authority"))))))
                assertTrue("error" in response)
                assertFalse("result" in response)
            }
            val absentOriginal = call("review.policy.reassess", reassessParams)
            assertTrue("error" in absentOriginal)
            assertEquals("policy_reassessment_review_not_found", absentOriginal.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            val absentView = call("review.policy.status", statusParams)
            assertEquals("policy_reassessment_not_found", absentView.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            val deniedMutation = call("config.mutate", buildJsonObject { put("ownerAction", true) })
            assertEquals("method_not_found", deniedMutation.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            assertTrue(work.kernel.events(registered.client).events.isEmpty())
            assertEquals(before, work.inspectConfig("client"))
        }
    }
}

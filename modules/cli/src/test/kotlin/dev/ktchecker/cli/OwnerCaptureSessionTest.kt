package dev.ktchecker.cli

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.RuleContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/** Synthetic typed owner operations, plus one no-provider ordinary-RPC denial check. */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class OwnerCaptureSessionTest {
    @TempDir lateinit var temp: Path

    private val digest = "sha256:" + "a".repeat(64)
    private val rule = "ktc.compiler.common.abstract_member_not_implemented"

    private inner class Operations : OwnerCaptureOperations {
        val verifications = mutableListOf<String>()
        val overlays = mutableListOf<OverlayDocument>()
        val preparations = mutableListOf<Pair<PreparationSpec, Boolean>>()
        val trace = mutableListOf<String>()
        var failVerification: Int? = null
        var failOverlay = false
        var largeResponse = false
        var policyVersion = 1

        override fun verifyEnvironment(expectedToolchainDigest: String) {
            verifications += expectedToolchainDigest
            trace += "verify"
            if (expectedToolchainDigest != digest || verifications.size == failVerification) {
                throw CliProblem("owner_capture_toolchain_changed", "Synthetic identity drift.")
            }
        }
        override fun overlay(document: OverlayDocument): JsonObject {
            trace += "overlay"
            overlays += document
            if (failOverlay) error("private-owner-marker-must-not-escape")
            return if (largeResponse) buildJsonObject { put("padding", "x".repeat(5000)) } else result(document)
        }
        override fun prepare(document: PreparationSpec, authorizeExecution: Boolean): JsonObject {
            trace += "prepare"
            preparations += document to authorizeExecution
            return buildJsonObject {
                put("document", result(document)); put("executionAuthorized", authorizeExecution)
                put("policyVersion", policyVersion)
            }
        }
    }

    private fun overlay(patch: String = "patch-one", source: String = "fun value() = 1") = OverlayDocument(
        "workspace", "worktree", "client", "session", patch,
        changes = listOf(OverlayChange("put", "Main.kt", content = source)))

    private fun preparation(suffix: String = "a") = PreparationSpec("client", "sha256:" + suffix.repeat(64),
        "patch-$suffix", "request-$suffix", listOf("Main.kt"), scope = RuleContext(target = "jvm"),
        requiredRuleIds = listOf(rule), libraries = mapOf("selection" to suffix))

    private fun params(document: JsonObject, consent: JsonElement? = null, expected: String = digest) = buildJsonObject {
        put("document", document); put("expectedToolchainDigest", expected)
        consent?.let { put("authorizeExecution", it) }
    }

    private fun request(method: String, parameters: JsonObject, profile: JsonElement? = JsonPrimitive(FINDING_WIRE_PROFILE)): String = buildJsonObject {
        put("id", "correlation-only"); put("method", method); put("params", parameters)
        profile?.let { put("findingWireProfile", it) }
    }.toString()

    private fun overlayRequest() = request("owner.overlay", params(result(overlay())))
    private fun prepareRequest(consent: Boolean = false, spec: PreparationSpec = preparation()) =
        request("owner.prepare", params(result(spec), JsonPrimitive(consent)))
    private fun session(operations: Operations, bound: Int = 16_777_216) = OwnerCaptureSession(operations, setOf(rule), bound)
    private fun code(value: JsonObject) = value.getValue("error").jsonObject.getValue("code").jsonPrimitive.content

    @Test fun launchPreflightIsClosedExplicitZeroWorkerAndNonMutating() {
        val state = temp.resolve("not-created")
        val base = listOf("owner-capture-session", "--state", state.toString(), "--compiler-dir", temp.resolve("compiler").toString(),
            "--compiler-java-home", temp.resolve("jdk").toString(), "--owner-action")
        val valid = parseOwnerCaptureSessionOptions(Arguments(base.toTypedArray()))
        assertEquals(0, valid.workerCount)
        assertEquals(state, valid.state)
        assertNotNull(valid.compilerDirectory)
        assertNotNull(valid.compilerJavaHome)
        val invalid = listOf(base.dropLast(1), base + "extra-word", base + listOf("--workers", "1"),
            base + listOf("--workers", "2"), base + listOf("--workers", "oops"), base + "--authorize-execution",
            base + listOf("--out", temp.resolve("result.json").toString()), base + listOf("--request", "ignored.json"),
            base + listOf("--format", "json"), base + listOf("--detekt-artifacts", "unselected"),
            base.filterIndexed { index, _ -> index !in 5..6 },
            base.mapIndexed { index, value -> if (index == 2) "" else value })
        invalid.forEach { args -> assertFailsWith<CliProblem> { parseOwnerCaptureSessionOptions(Arguments(args.toTypedArray())) } }
        assertFalse(Files.exists(state))
    }

    @Test fun explicitConsentIsPerPreparationAndNeverSticksFromTrueToFalse() {
        val operations = Operations()
        val session = session(operations)
        assertFalse("error" in session.handle(prepareRequest(true, preparation("a"))))
        assertFalse("error" in session.handle(prepareRequest(false, preparation("b"))))
        assertEquals(listOf(true, false), operations.preparations.map { it.second })
        assertEquals(listOf("verify", "prepare", "verify", "verify", "prepare", "verify"), operations.trace)
        assertEquals(List(4) { digest }, operations.verifications)
    }

    @Test fun missingStringNumericNullAndContainerConsentRejectBeforeOperationsAndPoison() {
        listOf<JsonElement?>(null, JsonNull, JsonPrimitive("true"), JsonPrimitive("false"), JsonPrimitive(1), JsonPrimitive(0),
            JsonArray(emptyList()), JsonObject(emptyMap())).forEach { consent ->
            val operations = Operations()
            val session = session(operations)
            val rejected = session.handle(request("owner.prepare", params(result(preparation()), consent)))
            assertEquals("invalid_request", code(rejected))
            assertEquals("owner_capture_session_failed", code(session.handle(prepareRequest(true))))
            assertTrue(operations.trace.isEmpty())
        }
    }

    @Test fun closedMethodsAndExactSchemasNeverReachOwnerOperations() {
        val valid = params(result(overlay()))
        val requests = listOf(
            request("owner.overlay", JsonObject(valid + ("credential" to JsonObject(emptyMap())))),
            request("owner.overlay", JsonObject(valid + ("authorizeExecution" to JsonPrimitive(true)))),
            request("owner.overlay", params(JsonObject(result(overlay()) + ("unknown" to JsonPrimitive(true))))),
            request("owner.overlay", JsonObject(valid - "expectedToolchainDigest")),
            request("owner.overlay", params(result(overlay()), expected = "not-a-digest")),
            request("owner.prepare", params(result(preparation().copy(requiredRuleIds = null)), JsonPrimitive(true))),
            request("owner.prepare", params(result(preparation().copy(requiredRuleIds = emptyList())), JsonPrimitive(true))),
            request("owner.prepare", params(result(preparation().copy(requiredRuleIds = listOf("ktc.detekt.any"))), JsonPrimitive(true))),
            request("owner.prepare", params(result(preparation().copy(requiredRuleIds = listOf(rule, rule))), JsonPrimitive(true))),
            request("owner.prepare", params(result(preparation().copy(compilerVersion = "2.4.20")), JsonPrimitive(true))),
            request("owner.prepare", params(result(preparation().copy(scope = RuleContext(target = "native"))), JsonPrimitive(true))),
        ) + listOf("client.register", "review.submit", "owner.register", "owner.configureCompilerPolicy", "owner.model-plan", "doctor").map {
            request(it, JsonObject(emptyMap()))
        }
        requests.forEach { frame ->
            val operations = Operations()
            val session = session(operations)
            assertTrue("error" in session.handle(frame))
            assertEquals("owner_capture_session_failed", code(session.handle(overlayRequest())))
            assertTrue(operations.trace.isEmpty())
        }
    }

    @Test fun exactDigestIsRequiredAgainAndBeforeAndAfterEveryMutation() {
        val operations = Operations()
        val session = session(operations)
        assertFalse("error" in session.handle(overlayRequest()))
        val changed = params(result(overlay("patch-two")), expected = "sha256:" + "b".repeat(64))
        assertEquals("owner_capture_toolchain_changed", code(session.handle(request("owner.overlay", changed))))
        assertEquals(1, operations.overlays.size)
        assertEquals(3, operations.verifications.size)
        assertEquals("owner_capture_session_failed", code(session.handle(overlayRequest())))
        assertEquals(3, operations.verifications.size)
    }

    @Test fun preAndPostMutationVerificationFailurePoisonsWithoutClaimingRollback() {
        for (failedAt in listOf(1, 2)) {
            val operations = Operations().also { it.failVerification = failedAt }
            val session = session(operations)
            assertEquals("owner_capture_toolchain_changed", code(session.handle(overlayRequest())))
            assertEquals(if (failedAt == 1) 0 else 1, operations.overlays.size)
            operations.failVerification = null
            assertEquals("owner_capture_session_failed", code(session.handle(overlayRequest())))
            assertEquals(failedAt, operations.verifications.size)
            assertEquals(if (failedAt == 1) 0 else 1, operations.overlays.size)
        }
    }

    @Test fun successiveSnapshotsDocumentsAndPolicyResultsAreNeverMemoizedBySession() {
        val operations = Operations()
        val session = session(operations)
        for ((index, suffix) in listOf("a", "b").withIndex()) {
            val selected = overlay("patch-$suffix", "fun value() = ${index + 1}")
            assertFalse("error" in session.handle(request("owner.overlay", params(result(selected)))))
            operations.policyVersion = index + 1
            val response = session.handle(prepareRequest(index == 0, preparation(suffix))).getValue("result").jsonObject
            assertEquals(index + 1, response.getValue("policyVersion").jsonPrimitive.int)
        }
        assertEquals(listOf("patch-a", "patch-b"), operations.overlays.map { it.patchId })
        assertNotEquals(operations.overlays[0].changes, operations.overlays[1].changes)
        assertEquals(listOf(preparation("a"), preparation("b")), operations.preparations.map { it.first })
        assertEquals(8, operations.verifications.size)
    }

    @Test fun envelopeOrProfileFailureEndsTheStreamBeforeAnyLaterQueuedFrame() {
        val operations = Operations()
        val session = session(operations)
        val invalid = request("owner.overlay", params(result(overlay())), null)
        val input = (overlayRequest() + "\n" + invalid + "\n" + overlayRequest() + "\n").toByteArray()
        val output = ByteArrayOutputStream()
        assertFalse(session.serve(ByteArrayInputStream(input), output))
        val replies = output.toString(Charsets.UTF_8).trim().lines().map { ContractJson.parseToJsonElement(it).jsonObject }
        assertEquals(2, replies.size)
        assertEquals("incompatible_finding_wire_profile", code(replies[1]))
        assertEquals(1, operations.overlays.size)
        assertEquals(2, operations.verifications.size)
    }

    @Test fun ambiguousMalformedTruncatedAndOversizedFramesNeverMutate() {
        val duplicated = overlayRequest().replace("\"id\":\"correlation-only\"", "\"id\":\"one\",\"id\":\"two\"")
        val frames = listOf((duplicated + "\n").toByteArray(), byteArrayOf(0xC3.toByte(), 0x28, '\n'.code.toByte()),
            overlayRequest().toByteArray(), ("x".repeat(2049) + "\n").toByteArray())
        frames.forEach { frame ->
            val operations = Operations()
            val output = ByteArrayOutputStream()
            assertFalse(session(operations, 2048).serve(ByteArrayInputStream(frame), output))
            assertTrue(operations.trace.isEmpty())
            assertTrue("error" in ContractJson.parseToJsonElement(output.toString(Charsets.UTF_8)).jsonObject)
        }
    }

    @Test fun failedOrOversizedSuccessIsNeverReplayedAndPrivateFailureTextIsHidden() {
        for (oversized in listOf(false, true)) {
            val operations = Operations().also { it.largeResponse = oversized; it.failOverlay = !oversized }
            val session = session(operations, 2048)
            val response = session.handle(overlayRequest())
            assertEquals(if (oversized) "response_too_large" else "operation_failed", code(response))
            assertFalse(response.toString().contains("private-owner-marker"))
            assertEquals(1, operations.overlays.size)
            assertEquals("owner_capture_session_failed", code(session.handle(overlayRequest())))
            assertEquals(1, operations.overlays.size)
        }
    }

    @Test fun ordinaryWorkbenchRpcNeverDispatchesOwnerCaptureMethods() {
        Workbench(WorkbenchOptions(temp.resolve("ordinary-state"), compilerDirectory = temp.resolve("absent-compiler"), workerCount = 0)).use { workbench ->
            val ordinary = RpcServer(workbench)
            for (method in listOf("owner.overlay", "owner.prepare")) {
                assertEquals("method_not_found", code(ordinary.handle(request(method, JsonObject(emptyMap())))))
            }
        }
    }
}

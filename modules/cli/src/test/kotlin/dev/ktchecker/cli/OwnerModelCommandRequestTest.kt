package dev.ktchecker.cli

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import dev.ktchecker.snapshot.SnapshotChange
import dev.ktchecker.workerprotocol.CompilerModelWire as ModelWire
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** Direct command negative controls. These NEVER construct a successful owner installation/worker. */
class OwnerModelCommandRequestTest {
    @TempDir lateinit var directory: Path

    @Test fun missingExecutionConsentAndUnsupportedOptionsFailBeforePathsOrStateAreTouched() {
        val state = directory.resolve("must-not-exist")
        val request = directory.resolve("source-request.json")
        Files.writeString(request, "do not read or rewrite this sentinel")
        val before = Files.readAllBytes(request)
        val base = arrayOf("model-plan", "--state", state.toString(), "--request", request.toString(),
            "--compiler-java-home", directory.resolve("missing-jdk").toString())
        assertEquals("owner_model_command_options_invalid", assertFailsWith<CliProblem> {
            runOwnerModelPlan(Arguments(base))
        }.code)
        for (extra in listOf(arrayOf("--workers", "0"), arrayOf("--prepared", "untrusted"),
            arrayOf("--wait-ms", "900000"), arrayOf("--owner-action"), arrayOf("--rule", "UNRESOLVED_REFERENCE"))) {
            assertEquals("owner_model_command_options_invalid", assertFailsWith<CliProblem> {
                runOwnerModelPlan(Arguments(base + arrayOf("--authorize-execution") + extra))
            }.code)
        }
        assertFalse(Files.exists(state))
        assertContentEquals(before, Files.readAllBytes(request))
    }

    @Test fun legacyCliExplicitlyRejectsMissingSeparateOwnerPolicyBeforeAnyPathReadOrStateSetup() {
        val state = directory.resolve("must-not-be-created")
        val request = directory.resolve("must-not-be-read.json")
        Files.writeString(request, "not JSON; policy setup must fail before this is read")
        val before = Files.readAllBytes(request)
        val failure = assertFailsWith<CliProblem> {
            runOwnerModelPlan(Arguments(arrayOf("model-plan", "--authorize-execution", "--state", state.toString(),
                "--request", request.toString(), "--compiler-java-home", directory.resolve("absent-jdk").toString())))
        }
        assertEquals("owner_model_explicit_policy_setup_required", failure.code)
        assertTrue(failure.message.orEmpty().contains("configureCompilerPolicy"))
        assertFalse(Files.exists(state)); assertContentEquals(before, Files.readAllBytes(request))
    }

    @Test fun unknownAndDuplicateArgumentsCannotReachOwnerFactory() {
        assertEquals("invalid_arguments", assertFailsWith<CliProblem> {
            Arguments(arrayOf("model-plan", "--authorize-execution", "--authorize-execution"))
        }.code)
        assertEquals("invalid_arguments", assertFailsWith<CliProblem> {
            Arguments(arrayOf("model-plan", "--compiler-rules", "invented"))
        }.code)
        assertTrue(Files.list(directory).use { it.count() } == 0L)
    }

    @Test fun typedFactoryRejectsFalseConsentBeforeResolvingMissingInputsOrCreatingState() {
        val state = directory.resolve("uncreated-owner")
        val selected = OwnerModelInvocationSelection("relative-or-missing-gradle", "missing-cache", "missing-java",
            listOf("unregistered.jar"), false)
        assertEquals("owner_model_explicit_execution_consent_required", assertFailsWith<CliProblem> {
            OwnerModelCommandSession.openLineage(OwnerModelLineageRegistration("workspace", "tree", "client", "session", "lineage"),
                selected, state)
        }.code)
        assertFalse(Files.exists(state))
    }

    @Test fun callerCannotMoveInvocationRootsOutsideTheOriginalOwnerState() {
        val state = directory.resolve("owner")
        assertEquals("owner_model_invocation_root_mismatch", assertFailsWith<CliProblem> {
            OwnerModelCommandSession.openLineage(OwnerModelLineageRegistration("workspace", "tree", "client", "session", "lineage"),
                OwnerModelInvocationSelection("missing", "missing", "missing", listOf("missing"), true), state,
                invocationRoot = directory.resolve("foreign-runs"))
        }.code)
        assertFalse(Files.exists(state)); assertFalse(Files.exists(directory.resolve("foreign-runs")))
    }

    @Test fun fullRootAndExactBindingVectorsAreRejectedRatherThanFiltered() {
        val patch = commandTestPatch()
        val variants = listOf(
            patch.copy(roots = emptyList()),
            patch.copy(roots = patch.roots + OwnerModelPatchRoot("included", "included")),
            patch.copy(roots = listOf(OwnerModelPatchRoot("project", "nested"))),
            patch.copy(files = listOf(patch.files.single().copy(rootId = "included"))),
            patch.copy(binding = patch.binding.copy(sessionId = "foreign-session")),
            patch.copy(binding = patch.binding.copy(lineageId = "foreign-lineage")),
            patch.copy(binding = patch.binding.copy(generation = 2L)),
        )
        variants.forEach { value -> assertFailsWith<CliProblem> { OwnerModelFrozenPatch.capture(value, commandTestBinding) { } } }
        assertTrue(Files.list(directory).use { it.count() } == 0L)
    }

    @Test fun sortedUniqueCanonicalBase64InventoryIsMandatoryIncludingExactPadding() {
        val a = commandTestFile("A.kt", byteArrayOf(1))
        val b = commandTestFile("B.kt", byteArrayOf(2))
        listOf(listOf(b, a), listOf(a, a), emptyList(),
            listOf(a.copy(contentBase64 = "AQ")), listOf(a.copy(contentBase64 = "AR==")),
            listOf(a.copy(contentBase64 = "AQ==\n")), listOf(a.copy(contentBase64 = "%%%="))).forEach { files ->
            assertFailsWith<CliProblem> { OwnerModelFrozenPatch.capture(commandTestPatch(files = files), commandTestBinding) { } }
        }
        val frozen = OwnerModelFrozenPatch.capture(commandTestPatch(files = listOf(a, b)), commandTestBinding) { }
        assertEquals(listOf("A.kt", "B.kt"), frozen.entries.map { it.path })
        assertEquals(Digests.sha256(byteArrayOf(1)), frozen.entries.first().digest)
    }

    @Test fun portablePathCollisionsUnicodeAndTraversalAreRejectedBeforeMutation() {
        val invalid = listOf("", "/Main.kt", "../Main.kt", "a/../Main.kt", "a//Main.kt", "a\\Main.kt", "a/./Main.kt",
            "C:/Main.kt", "NUL.kt", "trail./Main.kt", "trail /Main.kt", "a\u0085.kt", "e\u0301.kt",
            "a".repeat(256), "a/".repeat(ModelWire.MAX_PATH_DEPTH) + "Main.kt", "a\uD800.kt")
        invalid.forEach { path ->
            assertFailsWith<CliProblem>(path) {
                OwnerModelFrozenPatch.capture(commandTestPatch(files = listOf(commandTestFile(path))), commandTestBinding) { }
            }
        }
        for (names in listOf(listOf("a", "a/Main.kt"), listOf("Dir/A.kt", "dir/B.kt"), listOf("A.kt", "a.kt"))) {
            assertFailsWith<CliProblem> {
                OwnerModelFrozenPatch.capture(commandTestPatch(files = names.map { commandTestFile(it) }), commandTestBinding) { }
            }
        }
        val good = OwnerModelFrozenPatch.capture(commandTestPatch(files = listOf(commandTestFile("雪/é.kt"))), commandTestBinding) { }
        assertEquals("雪/é.kt", good.entries.single().path)
    }

    @Test fun sourceFileCountByteAndWholeDocumentLimitsAreNotRaised() {
        assertFailsWith<CliProblem> {
            OwnerModelFrozenPatch.capture(commandTestPatch(files = (0..ModelWire.MAX_SNAPSHOT_FILES).map {
                commandTestFile("%04d.kt".format(it), byteArrayOf())
            }), commandTestBinding) { }
        }
        assertEquals("owner_model_snapshot_encoding_or_limit", assertFailsWith<CliProblem> {
            OwnerModelFrozenPatch.capture(commandTestPatch(files = listOf(commandTestFile("Large.kt", ByteArray(ModelWire.MAX_SOURCE_BYTES + 1)))),
                commandTestBinding) { }
        }.code)
        assertEquals("owner_model_request_limit", assertFailsWith<CliProblem> {
            OwnerModelFrozenPatch.capture(commandTestPatch(files = listOf(commandTestFile("Large.kt").copy(
                contentBase64 = "A".repeat(OWNER_MODEL_REQUEST_BYTES)))), commandTestBinding) { }
        }.code)
    }

    @Test fun patchIdentityParentAndOrdinalDataAreBoundedWithoutInventingAParent() {
        val patch = commandTestPatch()
        listOf(patch.copy(patchId = ""), patch.copy(taskId = "x\n"), patch.copy(taskId = "雪".repeat(100)),
            patch.copy(ordinal = 0), patch.copy(ordinal = 33), patch.copy(expectedHeadRevision = -1),
            patch.copy(baseSnapshotId = "not-a-digest")).forEach { value ->
            assertFailsWith<CliProblem> { OwnerModelFrozenPatch.capture(value, commandTestBinding) { } }
        }
        assertFailsWith<CliProblem> { OwnerModelSnapshotLineage.verifyRequest(patch.copy(baseSnapshotId = Digests.sha256("foreign")), null) }
    }

    @Test fun frozenCompletePatchDoesNotBorrowMutableCallerListsOrFileBytes() {
        val bytes = byteArrayOf(0, 1, 2, -1)
        val files = mutableListOf(commandTestFile("Main.kt", bytes, executable = true))
        val roots = mutableListOf(OwnerModelPatchRoot("project", ""))
        val request = commandTestPatch(files = files).copy(roots = roots)
        val frozen = OwnerModelFrozenPatch.capture(request, commandTestBinding) { }
        val digest = frozen.digest
        bytes.fill(7); files.clear(); roots.add(OwnerModelPatchRoot("foreign", "elsewhere"))
        assertEquals(listOf("Main.kt"), frozen.request.files.map { it.path })
        assertEquals(listOf(OwnerModelPatchRoot("project", "")), frozen.request.roots)
        val put = frozen.overlay(null).changes.single() as SnapshotChange.PutFile
        assertEquals(Digests.sha256(byteArrayOf(0, 1, 2, -1)), put.content.digest)
        assertTrue(put.executable); assertEquals(digest, frozen.digest)
        assertFailsWith<UnsupportedOperationException> { (frozen.request.files as MutableList<*>).clear() }
        val pin = frozen.write(directory.toRealPath().resolve("immutable.json")) { }
        assertEquals(digest, pin.digest)
        assertEquals(frozen.request, ContractJson.decodeFromString<OwnerModelCompletedPatch>(Files.readString(pin.path)))
    }

    @Test fun wholeCallerDocumentIsDetachedBeforeTheFirstExternalCheckpoint() {
        val original = commandTestFile("Main.kt", byteArrayOf(5, 6))
        val caller = mutableListOf(original)
        var checkpoints = 0
        val frozen = OwnerModelFrozenPatch.capture(commandTestPatch(files = caller), commandTestBinding) {
            if (checkpoints++ == 0) caller.clear()
        }
        assertTrue(caller.isEmpty()); assertEquals(listOf(original), frozen.request.files)
        assertEquals(Digests.sha256(byteArrayOf(5, 6)), frozen.entries.single().digest)
    }

    @Test fun strictDocumentDecoderRejectsUnknownKeysDuplicateKeysAndInvalidUnicode() {
        val encoded = ContractJson.encodeToJsonElement(commandTestPatch()).jsonObject
        assertFailsWith<Exception> { ContractJson.decodeFromJsonElement<OwnerModelCompletedPatch>(
            JsonObject(encoded + ("model" to JsonPrimitive("not-authority")))) }
        assertFailsWith<CliProblem> { strictJsonDocument("{\"patchId\":\"a\",\"patchId\":\"b\"}".toByteArray(), "bad") }
        assertFailsWith<CliProblem> { strictJsonDocument("{\"x\":\"\\uD800\"}".toByteArray(), "bad") }
        assertFailsWith<CliProblem> { strictJsonDocument(byteArrayOf(0xC3.toByte(), 0x28), "bad") }
    }
}

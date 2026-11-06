package dev.ktchecker.cli

import dev.ktchecker.config.ConfigAction
import dev.ktchecker.config.ConfigLayer
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import dev.ktchecker.kernel.ClientRegistration
import dev.ktchecker.kernel.ReviewStatus
import dev.ktchecker.snapshot.SnapshotCommit
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/** Real CLI/private state, zero provider workers; output validation is not a transaction or grant. */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class CliOutputPreflightTest {
    @TempDir lateinit var temp: Path
    private val rule = "ktc.compiler.common.abstract_class_member_not_implemented"

    private fun registrationArgs() = arrayOf("register", "--workspace", "workspace", "--worktree", "tree",
        "--client", "client", "--session", "session", "--lineage", "lineage")

    private fun selectedCommands(): List<Array<String>> = listOf(
        arrayOf("doctor"), arrayOf("rules"), arrayOf("providers"), registrationArgs(), arrayOf("overlay"),
        arrayOf("prepare"), arrayOf("submit"), arrayOf("status"), arrayOf("events"), arrayOf("cancel"),
    ) + listOf("inspect", "export", "add", "remove", "import", "enable", "disable", "apply", "selector", "explain")
        .map { arrayOf("suppressions", it) }

    private fun invoke(state: Path, command: Array<String>, out: Path): Int = runCli(
        arrayOf("--state", state.toString(), "--workers", "0") + command + arrayOf("--out", out.toString()))

    /** Called only after the fixture owner closes, never while a broker/heartbeat is live. */
    private fun stateImage(state: Path): Map<String, String> {
        if (!Files.exists(state, NOFOLLOW_LINKS)) return emptyMap()
        return Files.walk(state).use { paths -> paths.toList().associate { path ->
            state.relativize(path).toString() to if (Files.isDirectory(path, NOFOLLOW_LINKS)) "directory" else {
                assertTrue(Files.isRegularFile(path, NOFOLLOW_LINKS), "Fixture state must contain only real files/directories")
                Digests.sha256(Files.readAllBytes(path))
            }
        } }
    }

    private fun rejectOutputsWithoutStateChanges(state: Path, command: Array<String>) {
        val parent = Files.createTempDirectory(temp, "output-errors-")
        val existing = parent.resolve("existing.json")
        Files.writeString(existing, "keep this output exactly\n")
        val missingParent = parent.resolve("missing")
        val before = stateImage(state)
        for (out in listOf(existing, missingParent.resolve("result.json"))) {
            assertEquals(3, invoke(state, command, out))
            assertEquals(before, stateImage(state), "Output rejection must precede all owner/broker/capture/authority writes")
            assertEquals("keep this output exactly\n", Files.readString(existing))
            assertFalse(Files.exists(missingParent, NOFOLLOW_LINKS), "No implicit output directory creation")
        }
    }

    @Test fun unusableOutputCannotInitializeAnySelectedCommandEvenWithDefaultWorkers() {
        val existing = temp.resolve("existing.json")
        Files.writeString(existing, "retain original\n")
        val missingParent = temp.resolve("missing-parent")
        var ordinal = 0
        for (command in selectedCommands()) {
            for (out in listOf(existing, missingParent.resolve("result.json"))) {
                val state = temp.resolve("unopened-${ordinal++}")
                // Deliberately omit --workers: output rejection must precede even the default owner.
                assertEquals(3, runCli(command + arrayOf("--state", state.toString(), "--out", out.toString())))
                assertFalse(Files.exists(state, NOFOLLOW_LINKS), command.joinToString(" "))
                assertFalse(Files.exists(missingParent, NOFOLLOW_LINKS))
                assertEquals("retain original\n", Files.readString(existing))
            }
        }
    }

    @Test fun missingRequiredCredentialAndPreparedOutputsRejectBeforeOwnerCreation() {
        for (command in listOf(registrationArgs(), arrayOf("prepare", "--authorize-execution"))) {
            val state = temp.resolve("no-output-${command.first()}")
            assertEquals(3, runCli(command + arrayOf("--state", state.toString())))
            assertFalse(Files.exists(state, NOFOLLOW_LINKS))
            assertEquals("Option --out is required.", assertFailsWith<CliProblem> {
                preflightCliOutput(Arguments(command))
            }.message)
        }
    }

    @Test fun preflightIsReadOnlyAndLeavesUnselectedRoutesToTheirOwnParsers() {
        val parent = Files.createDirectory(temp.resolve("output-parent"))
        val posix = Files.getFileAttributeView(parent, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
        if (posix != null) Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwxr-x---"))
        val permissions = posix?.readAttributes()?.permissions()
        val out = parent.resolve("fresh.json")
        val state = temp.resolve("unopened")
        for (command in selectedCommands()) {
            preflightCliOutput(Arguments(command + arrayOf("--state", state.toString(), "--out", out.toString())))
            assertFalse(Files.exists(out, NOFOLLOW_LINKS), "Preflight is not an output reservation")
            assertFalse(Files.exists(state, NOFOLLOW_LINKS))
            assertEquals(permissions, posix?.readAttributes()?.permissions(), "Do not chmod the output parent")
            if (command.first() !in setOf("register", "prepare")) preflightCliOutput(Arguments(command))
        }
        val existing = parent.resolve("existing.json")
        Files.writeString(existing, "unchanged\n")
        for (raw in listOf("", " \t", "bad\u0000path", existing.toString(), parent.toString(), existing.resolve("child.json").toString())) {
            assertEquals("invalid_arguments", assertFailsWith<CliProblem> {
                preflightCliOutput(Arguments(arrayOf("overlay", "--out", raw)))
            }.code)
        }
        for (command in listOf(arrayOf("rpc"), arrayOf("model-plan"), arrayOf("owner-model-session"),
            arrayOf("reassess"), arrayOf("policy-status"), arrayOf("unknown"), arrayOf("suppressions", "unknown"))) {
            preflightCliOutput(Arguments(command + arrayOf("--out", existing.toString())))
        }
        assertEquals("unchanged\n", Files.readString(existing))
        assertEquals(permissions, posix?.readAttributes()?.permissions())
    }

    @Test fun failedOutputsCannotReserveBindingsCaptureOverlaysOrRetainExecutionConsent() {
        val state = temp.resolve("capture-state")
        rejectOutputsWithoutStateChanges(state, registrationArgs())
        assertFalse(Files.exists(state, NOFOLLOW_LINKS))
        val registrationFile = temp.resolve("registration.json")
        assertEquals(0, invoke(state, registrationArgs(), registrationFile))
        val registration = readOwnerFile<ClientRegistration>(registrationFile.toString())
        assertEquals("workspace", registration.binding.workspaceId)
        assertEquals("client", registration.binding.clientId)

        val overlayRequest = temp.resolve("overlay.json")
        saveOwnerFile(overlayRequest, result(OverlayDocument("workspace", "tree", "client", "session", "patch",
            changes = listOf(OverlayChange("put", "Main.kt", content = "fun answer(): Int = 42\n")))))
        val overlayArgs = arrayOf("overlay", "--request", overlayRequest.toString())
        rejectOutputsWithoutStateChanges(state, overlayArgs)
        val overlayFile = temp.resolve("capture.json")
        assertEquals(0, invoke(state, overlayArgs, overlayFile))
        val committed = readOwnerFile<SnapshotCommit>(overlayFile.toString())
        assertEquals(1L, committed.headRevision)
        assertFalse(committed.replayed, "Rejected outputs must not leave an already accepted patch")

        val preparationRequest = temp.resolve("preparation.json")
        saveOwnerFile(preparationRequest, result(PreparationSpec("client", committed.snapshotId, "patch", "same-request",
            listOf("Main.kt"), requiredRuleIds = listOf(rule), addKotlinStdlib = false)))
        val prepareArgs = arrayOf("prepare", "--request", preparationRequest.toString())
        rejectOutputsWithoutStateChanges(state, prepareArgs + arrayOf("--authorize-execution"))
        val preparedFile = temp.resolve("prepared.json")
        assertEquals(0, invoke(state, prepareArgs, preparedFile))
        val prepared = readOwnerFile<PreparedReview>(preparedFile.toString())
        assertEquals(registration.binding, prepared.binding)
        assertEquals("patch", prepared.patchId)
        assertEquals("same-request", prepared.idempotencyKey)
        assertEquals(committed.snapshotId, prepared.input.snapshot.snapshotId)
        assertFalse(prepared.executionAuthorized, "Rejected authorized output must not silently grant the later preparation")
        assertNull(prepared.input.execution)
        assertFalse(prepared.input.context.fullProject)
    }

    @Test fun failedOutputsCannotSubmitCancelOrExerciseExplicitConfigurationAuthority() {
        val state = temp.resolve("review-state")
        val preparedFile = temp.resolve("prepared.json")
        val expectedFile = temp.resolve("expected.json")
        lateinit var registration: ClientRegistration
        lateinit var initialPolicy: JsonObject
        Workbench(WorkbenchOptions(state, workerCount = 0)).use { work ->
            registration = work.register("workspace", "tree", "client", "session", "lineage")
            val committed = work.overlay(OverlayDocument("workspace", "tree", "client", "session", "patch",
                changes = listOf(OverlayChange("put", "Main.kt", content = "fun answer(): Int = 42\n"))))
            val prepared = work.prepare(PreparationSpec("client", committed.snapshotId, "patch", "request",
                listOf("Main.kt"), requiredRuleIds = listOf(rule), addKotlinStdlib = false), authorizeExecution = false)
            saveOwnerFile(preparedFile, result(prepared))
            initialPolicy = work.inspectConfig("client")
            saveOwnerFile(expectedFile, initialPolicy)
        }
        val submitArgs = arrayOf("submit", "--prepared", preparedFile.toString())
        rejectOutputsWithoutStateChanges(state, submitArgs)
        val acceptedFile = temp.resolve("accepted.json")
        assertEquals(0, invoke(state, submitArgs, acceptedFile))
        val accepted = readOwnerFile<ReviewStatus>(acceptedFile.toString())
        assertEquals("queued", accepted.state)
        assertNull(accepted.result)
        assertFalse(accepted.cancelled)

        val cancelArgs = arrayOf("cancel", "--client", "client", "--review", accepted.reviewId)
        rejectOutputsWithoutStateChanges(state, cancelArgs)
        val cancelledFile = temp.resolve("cancelled.json")
        assertEquals(0, invoke(state, cancelArgs, cancelledFile))
        val cancelled = readOwnerFile<ReviewStatus>(cancelledFile.toString())
        assertEquals(accepted.reviewId, cancelled.reviewId)
        assertTrue(cancelled.cancelled)

        val mutationFile = temp.resolve("mutation.json")
        saveOwnerFile(mutationFile, buildJsonObject {
            put("layer", ContractJson.encodeToJsonElement(ConfigLayer.SHARED))
            put("expected", initialPolicy.getValue("revision"))
            put("action", ContractJson.encodeToJsonElement<ConfigAction>(ConfigAction.SetRuleEnabled(rule, true)))
        })
        rejectOutputsWithoutStateChanges(state, arrayOf("suppressions", "apply", "--client", "client",
            "--request", mutationFile.toString(), "--owner-action"))
        val enableArgs = arrayOf("suppressions", "enable", "--client", "client", "--rule", rule,
            "--expected", expectedFile.toString())
        rejectOutputsWithoutStateChanges(state, enableArgs + arrayOf("--owner-action"))
        val noAuthorityFile = temp.resolve("no-authority.json")
        assertEquals(3, invoke(state, enableArgs, noAuthorityFile))
        assertFalse(Files.exists(noAuthorityFile, NOFOLLOW_LINKS))
        Workbench(WorkbenchOptions(state, workerCount = 0)).use { work ->
            assertEquals(initialPolicy, work.inspectConfig("client"), "An output path cannot replace explicit --owner-action")
            assertEquals(registration, work.registration("client"))
            assertEquals(listOf("accepted", "cancelled"), work.kernel.events(registration.client).events.map { it.kind })
        }
        val policyFile = temp.resolve("policy.json")
        assertEquals(0, invoke(state, enableArgs + arrayOf("--owner-action"), policyFile))
        val policy = readOwnerFile<JsonObject>(policyFile.toString())
        assertNotEquals(initialPolicy.getValue("revision"), policy.getValue("revision"))
        val setting = policy.getValue("effectiveRules").jsonObject.getValue(rule).jsonObject
        assertTrue(setting.getValue("enabled").jsonPrimitive.boolean)
    }

    @Test fun lateOutputCollisionDoesNotOverwriteOrPretendToRollBackExactEnrollment() {
        val state = temp.resolve("late-collision-state")
        val out = temp.resolve("raced-output.json")
        preflightCliOutput(Arguments(registrationArgs() + arrayOf("--out", out.toString())))
        assertFalse(Files.exists(out, NOFOLLOW_LINKS))
        lateinit var registration: ClientRegistration
        Workbench(WorkbenchOptions(state, workerCount = 0)).use { work ->
            registration = work.register("workspace", "tree", "client", "session", "lineage")
        }
        // Deterministic post-preflight collision, after the action commits; no production race hook.
        Files.writeString(out, "another writer won\n")
        val committed = stateImage(state)
        assertFailsWith<FileAlreadyExistsException> { saveOwnerFile(out, result(registration)) }
        assertEquals("another writer won\n", Files.readString(out))
        assertEquals(committed, stateImage(state), "A late output failure cannot roll back retained owner state")
        val recoveredFile = temp.resolve("recovered.json")
        assertEquals(0, invoke(state, registrationArgs(), recoveredFile))
        assertEquals(registration, readOwnerFile<ClientRegistration>(recoveredFile.toString()),
            "Recovery replays the exact binding and both capabilities, never a replacement enrollment")
    }
}

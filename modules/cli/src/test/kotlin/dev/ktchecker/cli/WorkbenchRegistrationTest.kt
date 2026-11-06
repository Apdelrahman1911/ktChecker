package dev.ktchecker.cli

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import dev.ktchecker.kernel.ClientBinding
import dev.ktchecker.kernel.ClientRegistration
import dev.ktchecker.kernel.ReviewStatus
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/** Real private state/SQLite/CLI; no provider execution, project build or qualification claim. */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class WorkbenchRegistrationTest {
    @TempDir lateinit var temp: Path

    private data class Enrollment(val workspace: String, val tree: String, val client: String,
        val session: String, val lineage: String) {
        fun register(work: Workbench): ClientRegistration = work.register(workspace, tree, client, session, lineage)
        fun changed(field: String, value: String): Enrollment = when (field) {
            "workspace" -> copy(workspace = value)
            "worktree" -> copy(tree = value)
            "client" -> copy(client = value)
            "session" -> copy(session = value)
            "lineage" -> copy(lineage = value)
            else -> error("Unknown fixture field")
        }
    }

    private fun options() = WorkbenchOptions(temp.resolve("state"), workerCount = 0)
    private fun intentPath(state: Path, clientId: String): Path =
        state.resolve("clients").resolve(Digests.sha256(clientId).removePrefix("sha256:") + ".json")
    private fun clientLockPath(state: Path, clientId: String): Path =
        state.resolve("locks").resolve(Digests.sha256("client:$clientId").removePrefix("sha256:") + ".lock")

    @Test fun rejectedBindingCannotReserveAClientIdentityBeforeACorrectedRetry() {
        val options = options()
        val accepted = mutableListOf<Pair<Enrollment, ClientRegistration>>()
        Workbench(options).use { work ->
            var ordinal = 0
            for (field in listOf("workspace", "worktree", "session", "lineage", "client")) {
                for (invalid in listOf("", " \t", "bad\nid", "bad\u0000id", "bad\u007fid", "x".repeat(513))) {
                    val valid = Enrollment("project-${ordinal++}", "main", "client-$ordinal", "session-$ordinal", "lineage-$ordinal")
                    val rejected = valid.changed(field, invalid)
                    assertEquals("invalid_identifier", assertFailsWith<CliProblem> { rejected.register(work) }.code)
                    assertFalse(Files.exists(intentPath(work.state.root, rejected.client), NOFOLLOW_LINKS),
                        "Invalid $field must not leave a durable client intent")
                    assertFalse(Files.exists(clientLockPath(work.state.root, rejected.client), NOFOLLOW_LINKS),
                        "Validation must precede even the client lock/namespace creation")
                    val registration = valid.register(work)
                    assertEquals(ClientBinding(work.state.ownerId, valid.workspace, valid.tree, valid.client,
                        valid.lineage, sessionId = valid.session), registration.binding)
                    assertEquals(registration, valid.register(work), "Corrected enrollment is exactly replayable")
                    val retained = Files.readAllBytes(intentPath(work.state.root, valid.client))
                    assertEquals("invalid_identifier", assertFailsWith<CliProblem> { rejected.register(work) }.code)
                    assertContentEquals(retained, Files.readAllBytes(intentPath(work.state.root, valid.client)),
                        "A later invalid request cannot rewrite a valid enrollment")
                    accepted += valid to registration
                }
            }
        }
        Workbench(options).use { reopened ->
            for ((enrollment, registration) in accepted) {
                assertEquals(registration, enrollment.register(reopened), "Restart retains the exact binding and both capabilities")
                assertEquals(registration, reopened.registration(enrollment.client))
                assertTrue(reopened.kernel.events(registration.client).events.isEmpty())
            }
        }
    }

    @Test fun kernelCompatibleBoundaryAndUnicodeIdentitiesAreNotNormalizedOrOverRejected() {
        Workbench(options()).use { work ->
            // The broker limit is 512 Kotlin UTF-16 code units, not UTF-8 bytes or a path syntax.
            for (value in listOf(" leading and trailing ", "caf\u00e9-雪-🌍", "not/a/filesystem/path", "x".repeat(512), "🌍".repeat(256))) {
                val enrollment = Enrollment(value, value, value, value, value)
                val registered = enrollment.register(work)
                assertEquals(ClientBinding(work.state.ownerId, value, value, value, value, sessionId = value), registered.binding)
                assertEquals(registered, enrollment.register(work))
                assertEquals(registered, work.registration(value))
            }
        }
    }

    @Test fun ownerRpcEnrollmentRejectsForeignOwnerAndInvalidGenerationsBeforeRetainingIntent() {
        val options = options()
        val maximumGeneration = 9_007_199_254_740_991L
        fun enroll(work: Workbench, binding: ClientBinding): ClientRegistration = ContractJson.decodeFromJsonElement(
            work.call("client.register", buildJsonObject { put("binding", result(binding)) }))
        lateinit var expected: ClientRegistration
        Workbench(options).use { work ->
            val binding = ClientBinding(work.state.ownerId, "rpc-project", "main", "rpc-client", "lineage",
                generation = maximumGeneration, sessionId = "session")
            for (owner in listOf("foreign-owner", "", "bad\u0000owner")) {
                assertEquals("permission_denied", assertFailsWith<CliProblem> { enroll(work, binding.copy(ownerId = owner)) }.code)
                assertFalse(Files.exists(intentPath(work.state.root, binding.clientId), NOFOLLOW_LINKS))
                assertFalse(Files.exists(clientLockPath(work.state.root, binding.clientId), NOFOLLOW_LINKS))
            }
            for (generation in listOf(0L, -1L, maximumGeneration + 1, Long.MAX_VALUE)) {
                assertEquals("invalid_binding", assertFailsWith<CliProblem> { enroll(work, binding.copy(generation = generation)) }.code)
                assertFalse(Files.exists(intentPath(work.state.root, binding.clientId), NOFOLLOW_LINKS))
                assertFalse(Files.exists(clientLockPath(work.state.root, binding.clientId), NOFOLLOW_LINKS))
            }
            expected = enroll(work, binding)
            assertEquals(binding, expected.binding, "The interoperable generation maximum must remain accepted exactly")
            val retained = Files.readAllBytes(intentPath(work.state.root, binding.clientId))
            assertEquals(expected, enroll(work, binding))
            assertEquals("invalid_binding", assertFailsWith<CliProblem> { enroll(work, binding.copy(generation = 0)) }.code)
            assertContentEquals(retained, Files.readAllBytes(intentPath(work.state.root, binding.clientId)))
        }
        Workbench(options).use { reopened ->
            assertEquals(expected, enroll(reopened, expected.binding))
            assertEquals(expected, reopened.registration(expected.binding.clientId))
            assertTrue(reopened.kernel.events(expected.client).events.isEmpty())
        }
    }

    @Test fun cliRegistrationCanCorrectAnInvalidWorkspaceWithoutChangingClientOrState() {
        val state = temp.resolve("cli-state")
        val out = temp.resolve("client-雪.json")
        val common = arrayOf("--state", state.toString(), "--workers", "0", "register",
            "--worktree", "main", "--client", "same-client", "--session", "session", "--lineage", "feature", "--out", out.toString())
        assertEquals(3, runCli(common + arrayOf("--workspace", "")))
        assertFalse(Files.exists(out, NOFOLLOW_LINKS), "Rejected enrollment must not export credentials")
        assertFalse(Files.exists(intentPath(state, "same-client"), NOFOLLOW_LINKS))
        assertFalse(Files.exists(clientLockPath(state, "same-client"), NOFOLLOW_LINKS))
        assertEquals(0, runCli(common + arrayOf("--workspace", "correct-project")))
        val registration = readOwnerFile<ClientRegistration>(out.toString())
        assertEquals("correct-project", registration.binding.workspaceId)
        assertEquals("same-client", registration.binding.clientId)
        Workbench(WorkbenchOptions(state, workerCount = 0)).use { reopened ->
            assertEquals(registration, reopened.register("correct-project", "main", "same-client", "session", "feature"))
            assertTrue(reopened.kernel.events(registration.client).events.isEmpty())
        }
    }

    @Test fun twoProjectsKeepDistinctBindingsAndReadOnlyPreparedPatchQueuesAcrossRestart() {
        val options = options()
        val first = Enrollment("app-one", "same-tree-name", "app-one-agent", "session-one", "feature-one")
        val second = Enrollment("app-two", "same-tree-name", "app-two-agent", "session-two", "feature-two")
        val registrations = mutableListOf<ClientRegistration>()
        val prepared = mutableListOf<PreparedReview>()
        val reviews = mutableListOf<ReviewStatus>()
        Workbench(options).use { work ->
            val registeredFirst = first.register(work)
            val retainedFirst = Files.readAllBytes(intentPath(work.state.root, first.client))
            val conflict = assertFailsWith<CliProblem> { first.copy(workspace = second.workspace).register(work) }
            assertEquals("client_binding_conflict", conflict.code)
            assertTrue("workspace" in conflict.message.orEmpty())
            assertTrue("worktree" in conflict.message.orEmpty())
            assertContentEquals(retainedFirst, Files.readAllBytes(intentPath(work.state.root, first.client)))
            assertEquals(registeredFirst, work.registration(first.client))
            for ((index, enrollment) in listOf(first, second).withIndex()) {
                val registration = enrollment.register(work)
                registrations += registration
                val snapshot = work.overlay(OverlayDocument(enrollment.workspace, enrollment.tree, enrollment.client,
                    enrollment.session, "patch-1", changes = listOf(OverlayChange("put", "Main.kt",
                        content = "fun answer(): Int = ${index + 1}\n"))))
                val plan = work.prepare(PreparationSpec(enrollment.client, snapshot.snapshotId, "patch-1", "review-patch-1",
                    listOf("Main.kt"), requiredRuleIds = listOf("ktc.compiler.common.abstract_class_member_not_implemented"),
                    addKotlinStdlib = false), authorizeExecution = false)
                assertEquals(enrollment.workspace, plan.input.snapshot.workspaceId)
                assertEquals(enrollment.tree, plan.input.snapshot.worktreeId)
                assertEquals(registration.binding, plan.binding)
                assertFalse(plan.executionAuthorized)
                assertFalse(plan.input.context.fullProject)
                assertNull(plan.input.execution)
                prepared += plan
                reviews += work.submit(registration.client, plan.patchId, plan.idempotencyKey, plan.input)
            }
            assertNotEquals(prepared[0].input.snapshot.snapshotId, prepared[1].input.snapshot.snapshotId)
            assertNotEquals(prepared[0].input.materializedRoot, prepared[1].input.materializedRoot)
            assertNotEquals(reviews[0].reviewId, reviews[1].reviewId)
        }
        Workbench(options).use { reopened ->
            for (index in registrations.indices) {
                val registration = registrations[index]
                val plan = prepared[index]
                assertEquals(registration, reopened.registration(registration.binding.clientId))
                val replay = reopened.submit(registration.client, plan.patchId, plan.idempotencyKey, plan.input)
                assertEquals(reviews[index], replay, "Identical patch IDs in separate projects remain separate idempotent obligations")
                assertEquals("queued", replay.state)
                assertNull(replay.result, "Zero workers and non-authorized preparation do not run provider code")
                val events = reopened.kernel.events(registration.client).events
                assertEquals(listOf("accepted"), events.map { it.kind })
                assertEquals(replay.reviewId, events.single().reviewId)
                assertEquals(registration.binding.clientId, events.single().clientId)
                assertTrue(events.single().deliveryRequired)
            }
        }
    }
}

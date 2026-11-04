package dev.ktchecker.cli

import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlinx.serialization.json.*
import kotlin.test.*

/** Real controller/SQLite/CAS integration. No provider qualification or model-delivery claim. */
class WorkbenchTest {
    private fun options(): WorkbenchOptions = WorkbenchOptions(Files.createTempDirectory("ktchecker-controller-雪-"), workerCount = 0)
    private fun fixture(work: Workbench, client: String = "agent-a"): PreparedReview {
        work.register("project", "tree", client, "session-$client", "lineage-$client")
        val commit = work.overlay(OverlayDocument("project", "tree", client, "session-$client", "patch-1",
            changes = listOf(OverlayChange("put", "Main.kt", content = "fun answer(): Int = 42\n"))))
        return work.prepare(PreparationSpec(client, commit.snapshotId, "patch-1", "request-1", listOf("Main.kt"),
            requiredRuleIds = listOf("ktc.compiler.common.abstract_class_member_not_implemented"), addKotlinStdlib = false), false)
    }

    @Test fun realImmutableSubmissionSurvivesControllerRestartAndDuplicate() {
        val options = options()
        lateinit var saved: PreparedReview
        lateinit var credential: ClientCredential
        lateinit var receipt: ReviewStatus
        Workbench(options).use { work ->
            saved = fixture(work)
            credential = work.registration("agent-a").client
            receipt = work.submit(credential, saved.patchId, saved.idempotencyKey, saved.input)
            assertEquals("queued", receipt.state)
            assertEquals(receipt.reviewId, work.submit(credential, saved.patchId, saved.idempotencyKey, saved.input).reviewId)
            assertFalse(saved.input.context.fullProject)
            assertFalse(saved.executionAuthorized)
        }
        Workbench(options).use { work ->
            assertEquals(receipt.reviewId, work.submit(credential, saved.patchId, saved.idempotencyKey, saved.input).reviewId)
            assertEquals(credential, work.registration("agent-a").client)
            val accepted = work.kernel.events(credential).events
            assertEquals(1, accepted.size)
            assertEquals(receipt.reviewId, accepted.single().reviewId)
        }
    }

    @Test fun callerCannotForgeProviderAuthorizationOrOtherClientPreparedInputs() {
        Workbench(options()).use { work ->
            val saved = fixture(work)
            val other = work.register("project", "tree", "agent-b", "session-b", "lineage-b")
            assertFailsWith<CliProblem> { work.submit(other.client, saved.patchId, saved.idempotencyKey, saved.input) }
            val forged = saved.input.copy(execution = TrustedExecution("/bin/sh", listOf("-c", "echo unsafe"), true))
            assertFailsWith<CliProblem> { work.submit(work.registration("agent-a").client, saved.patchId, saved.idempotencyKey, forged) }
            assertFailsWith<CliProblem> { work.submit(work.registration("agent-a").client, saved.patchId, "other-key", saved.input) }
            assertTrue(work.kernel.events(work.registration("agent-a").client).events.isEmpty())
        }
    }

    @Test fun sharedClientEnrollmentIsIdempotentAndBindingsCannotMove() {
        val options = options()
        Workbench(options).use { work ->
            val expected = work.register("project", "tree", "client", "session", "lineage")
            val pool = Executors.newFixedThreadPool(2)
            try {
                val calls = pool.invokeAll(List(4) { Callable { work.register("project", "tree", "client", "session", "lineage") } })
                calls.forEach { assertEquals(expected, it.get()) }
            } finally { pool.shutdownNow() }
            assertFailsWith<CliProblem> { work.register("project", "different-tree", "client", "session", "lineage") }
        }
        Workbench(options).use { work -> assertEquals("session", work.registration("client").binding.sessionId) }
    }

    @Test fun unknownAndPrivilegedMutationMethodsAreNotReviewOperations() {
        Workbench(options()).use { work ->
            val rpc = RpcServer(work)
            listOf("config.mutate", "snapshot.edit", "exec", "review.prepare").forEach { method ->
                val value = rpc.handle("""{"id":"x","method":"$method","params":{}}""")
                assertEquals("method_not_found", value.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            }
        }
    }

    @Test fun privateStateRejectsExistingProjectOrWorldReadableStoreWithoutChangingPermissions() {
        val parent = Files.createTempDirectory("ktchecker-state-boundary-")
        val foreign = Files.createDirectory(parent.resolve("project"))
        Files.writeString(foreign.resolve("source.kt"), "val protected = 1")
        assertFailsWith<CliProblem> { PrivateState(foreign) }
        assertEquals("val protected = 1", Files.readString(foreign.resolve("source.kt")))
        val path = parent.resolve("state")
        PrivateState(path)
        if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"))
            assertFailsWith<CliProblem> { PrivateState(path) }
            assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), Files.getPosixFilePermissions(path))
        }
    }

    @Test fun ownerIntentCanBeReplayedAfterBrokerEnrollmentButBeforeResponse() {
        val options = options()
        lateinit var identity: ClientRegistration
        Workbench(options).use { work -> identity = work.register("project", "tree", "intent-client", "session", "lineage") }
        Workbench(options).use { work ->
            assertEquals(identity, work.register("project", "tree", "intent-client", "session", "lineage"))
            assertEquals(identity, work.kernel.registerClient(identity))
        }
    }
}

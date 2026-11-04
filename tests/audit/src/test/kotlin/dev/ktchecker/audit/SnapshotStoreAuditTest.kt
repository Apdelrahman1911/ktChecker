package dev.ktchecker.audit

import dev.ktchecker.snapshot.CommitMode
import dev.ktchecker.snapshot.CaptureExclusion
import dev.ktchecker.snapshot.ImmutableBlob
import dev.ktchecker.snapshot.LiveCaptureRequest
import dev.ktchecker.snapshot.LiveState
import dev.ktchecker.snapshot.MaterializedSnapshot
import dev.ktchecker.snapshot.OverlayRequest
import dev.ktchecker.snapshot.SnapshotChange
import dev.ktchecker.snapshot.SnapshotCommit
import dev.ktchecker.snapshot.SnapshotConflict
import dev.ktchecker.snapshot.SnapshotException
import dev.ktchecker.snapshot.SnapshotFaultInjector
import dev.ktchecker.snapshot.SnapshotIdentity
import dev.ktchecker.snapshot.SnapshotPath
import dev.ktchecker.snapshot.SnapshotRoot
import dev.ktchecker.snapshot.SnapshotScope
import dev.ktchecker.snapshot.SnapshotStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Real snapshot store/filesystem tests. Child JVMs do not compile code or emulate a host. */
class SnapshotStoreAuditTest {
    @TempDir
    lateinit var temporary: Path

    private val identity = SnapshotIdentity("audit-owner", "shared-project", "left-worktree")
    private fun directory(name: String): Path = temporary.toRealPath().resolve(name)
    private fun store(name: String = "store"): SnapshotStore = SnapshotStore(directory(name), identity.ownerId)
    private fun put(path: String, text: String) = SnapshotChange.PutFile(SnapshotPath(path = path), ImmutableBlob.utf8(text))
    private fun request(
        base: SnapshotCommit?,
        patch: String,
        changes: List<SnapshotChange>,
        client: String = "client-a",
        session: String = "session-a",
        mode: CommitMode = CommitMode.STRICT_HEAD,
        target: SnapshotIdentity = identity,
    ) = OverlayRequest(target, client, session, patch, base?.snapshotId, base?.headRevision ?: 0, changes, mode)

    private fun seed(store: SnapshotStore, target: SnapshotIdentity = identity): SnapshotCommit {
        store.registerWorktree(target)
        return store.sealOverlay(request(null, "seed", listOf(put("src/Base.kt", "val base = 1\n")), target = target))
    }

    @Test
    fun twoStoreInstancesRacingStrictHeadAdmitExactlyOneCommit() {
        val first = store()
        val base = seed(first)
        val second = store()
        val barrier = CyclicBarrier(3)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val attempts = listOf(first to "left", second to "right").map { (instance, name) ->
                pool.submit<Result<SnapshotCommit>> {
                    barrier.await(10, TimeUnit.SECONDS)
                    runCatching {
                        instance.sealOverlay(request(base, name, listOf(put("src/$name.kt", name)), client = name))
                    }
                }
            }
            barrier.await(10, TimeUnit.SECONDS)
            val results = attempts.map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.isSuccess })
            assertTrue(results.single { it.isFailure }.exceptionOrNull() is SnapshotConflict)
            val winner = results.single { it.isSuccess }.getOrThrow()
            val reopened = store()
            val head = reopened.currentHead(identity)
            assertEquals(base.headRevision + 1, head.headRevision)
            assertEquals(2, head.acceptedPatchCount)
            assertEquals(winner.snapshotId, head.headSnapshotId)
            assertEquals(2, reopened.snapshot(identity, winner.snapshotId).entries.size)
            assertEquals(base.snapshotId, reopened.snapshot(identity, base.snapshotId).snapshotId)
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun separateJvmStrictHeadRaceUsesRealCrossProcessCoordination() {
        val actual = store()
        val base = seed(actual)
        val start = directory("start")
        val children = listOf("left", "right").map { name ->
            val ready = directory("$name.ready")
            val outcome = directory("$name.outcome")
            val process = child("race", actual.root, identity, base, name, ready, start, outcome)
            ChildProcess(process, ready, outcome)
        }
        try {
            awaitReady(children)
            Files.writeString(start, "go")
            val outcomes = children.map { child ->
                assertTrue(child.process.waitFor(20, TimeUnit.SECONDS), "Child JVM did not finish")
                assertEquals(0, child.process.exitValue(), child.process.inputStream.bufferedReader().readText())
                Files.readString(child.outcome).trim()
            }
            assertEquals(1, outcomes.count { it.startsWith("OK ") }, outcomes.toString())
            assertEquals(1, outcomes.count { it == "REJECT snapshot_conflict" }, outcomes.toString())
            val reopened = store()
            val head = reopened.currentHead(identity)
            assertEquals(2, head.acceptedPatchCount)
            assertEquals(base.headRevision + 1, head.headRevision)
            assertEquals(2, reopened.snapshot(identity, requireNotNull(head.headSnapshotId)).entries.size)
        } finally {
            children.forEach { if (it.process.isAlive) it.process.destroyForcibly().waitFor(10, TimeUnit.SECONDS) }
        }
    }

    @Test
    fun byteAbaAndDeleteRecreateCannotHideARebaseConflict() {
        val actual = store()
        val base = seed(actual)
        val changed = actual.sealOverlay(request(base, "to-b", listOf(put("src/Base.kt", "val base = 2\n"))))
        val back = actual.sealOverlay(request(changed, "back-to-a", listOf(put("src/Base.kt", "val base = 1\n"))))
        val stale = request(base, "stale-edit", listOf(put("src/Base.kt", "val base = 3\n")), mode = CommitMode.REBASE_DISJOINT)
        assertFailsWith<SnapshotConflict> { actual.sealOverlay(stale) }
        assertEquals(back.snapshotId, actual.currentHead(identity).headSnapshotId)
        val deleted = actual.sealOverlay(request(back, "delete", listOf(SnapshotChange.Delete(SnapshotPath(path = "src/Base.kt")))))
        val restored = actual.sealOverlay(request(deleted, "recreate", listOf(put("src/Base.kt", "val base = 1\n"))))
        assertFailsWith<SnapshotConflict> {
            actual.sealOverlay(request(back, "stale-after-delete", listOf(put("src/Base.kt", "val base = 4\n")), mode = CommitMode.REBASE_DISJOINT))
        }
        val reopened = store()
        assertEquals(restored.snapshotId, reopened.currentHead(identity).headSnapshotId)
        assertEquals(5, reopened.currentHead(identity).acceptedPatchCount)
        assertEquals(base.manifest.entries, reopened.snapshot(identity, base.snapshotId).entries)
    }

    @Test
    fun disjointRebaseKeepsBothClientsAndClientLocalPatchIdsDoNotAlias() {
        val actual = store()
        val base = seed(actual)
        val first = actual.sealOverlay(request(base, "patch-1", listOf(put("src/Left.kt", "val left = 1\n")), client = "left"))
        val secondRequest = request(base, "patch-1", listOf(put("src/Right.kt", "val right = 1\n")), client = "right", mode = CommitMode.REBASE_DISJOINT)
        val second = actual.sealOverlay(secondRequest)
        assertEquals(first.snapshotId, second.manifest.parentSnapshotId)
        assertEquals(base.snapshotId, second.manifest.producerBaseSnapshotId)
        assertEquals(setOf("src/Base.kt", "src/Left.kt", "src/Right.kt"), second.manifest.entries.map { it.path }.toSet())
        val replay = store().sealOverlay(secondRequest)
        assertTrue(replay.replayed)
        assertEquals(second.snapshotId, replay.snapshotId)
        val conflict = assertFailsWith<SnapshotException> {
            actual.sealOverlay(secondRequest.copy(changes = listOf(put("src/Right.kt", "different"))))
        }
        assertEquals("idempotency_conflict", conflict.code)
        assertEquals(3, actual.currentHead(identity).acceptedPatchCount)
    }

    @Test
    fun changedFileOverlaysCannotEraseOmissionsInheritedFromProducerBaseOrIntegratedHead() {
        val actual = store()
        actual.registerWorktree(identity)
        val producerScope = SnapshotScope(
            exclusions = listOf(CaptureExclusion("project", "generated", "generator unavailable")),
            unknownInputs = listOf("unknown dependency model"),
        )
        val base = actual.sealOverlay(request(null, "partial-base", listOf(put("src/Base.kt", "val base = 1\n")))
            .copy(scope = producerScope))
        val head = actual.sealOverlay(request(base, "head", listOf(put("src/Head.kt", "val head = 1\n")), client = "head")
            .copy(scope = SnapshotScope(unknownInputs = listOf("unimported native target"))))
        val rebased = actual.sealOverlay(request(base, "rebase", listOf(put("src/Rebased.kt", "val rebased = 1\n")),
            client = "other", mode = CommitMode.REBASE_DISJOINT))
        assertEquals(head.snapshotId, rebased.manifest.parentSnapshotId)
        assertTrue(rebased.manifest.scope.exclusions.any { it.rootId == "project" && it.path == "generated" })
        assertEquals(setOf("unknown dependency model", "unimported native target"), rebased.manifest.scope.unknownInputs.toSet())
        val descendant = actual.sealOverlay(request(rebased, "ordinary-default-scope", listOf(put("src/Later.kt", "val later = 1\n"))))
        val reopened = store().snapshot(identity, descendant.snapshotId)
        assertEquals(rebased.manifest.scope.exclusions, reopened.scope.exclusions)
        assertEquals(rebased.manifest.scope.unknownInputs, reopened.scope.unknownInputs)
    }

    @Test
    fun abruptChildExitBeforeAndAfterStateInstallRecoversWithoutTornAdmission() {
        for ((point, admitted) in listOf("before_state_install" to false, "after_state_install" to true)) {
            val actual = store(point)
            val base = seed(actual)
            val ready = directory("$point.ready")
            val start = directory("$point.start")
            val outcome = directory("$point.outcome")
            Files.writeString(start, "go")
            val process = child(point, actual.root, identity, base, "crash-patch", ready, start, outcome)
            try {
                assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Crash child did not reach the named transaction boundary")
                assertEquals(71, process.exitValue(), process.inputStream.bufferedReader().readText())
                assertFalse(Files.exists(outcome), "A halted process must not invent a successful response")
                val reopened = store(point)
                val interrupted = reopened.currentHead(identity)
                assertEquals(if (admitted) 2 else 1, interrupted.acceptedPatchCount)
                assertEquals(if (admitted) base.headRevision + 1 else base.headRevision, interrupted.headRevision)
                val retry = reopened.sealOverlay(childRequest(identity, base, "crash-patch"))
                assertEquals(admitted, retry.replayed)
                assertEquals(2, reopened.currentHead(identity).acceptedPatchCount)
                assertEquals(retry.snapshotId, reopened.currentHead(identity).headSnapshotId)
                val entry = retry.manifest.entries.single { it.path == "src/crash-patch.kt" }
                assertContentEquals("crash-patch".toByteArray(), reopened.readBlob(identity, retry.snapshotId, requireNotNull(entry.digest)))
            } finally {
                if (process.isAlive) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun worktreeAndOwnerIdentityPreventCrossSnapshotOrBlobAccess() {
        val actual = store()
        val left = seed(actual)
        val rightIdentity = identity.copy(worktreeId = "right-worktree")
        actual.registerWorktree(rightIdentity)
        val right = actual.sealOverlay(request(null, "seed", listOf(put("src/Base.kt", "different right tree")), target = rightIdentity))
        assertNotEquals(left.snapshotId, right.snapshotId)
        assertFailsWith<SnapshotException> { actual.snapshot(rightIdentity, left.snapshotId) }
        assertFailsWith<SnapshotException> {
            actual.readBlob(rightIdentity, right.snapshotId, requireNotNull(left.manifest.entries.single().digest))
        }
        assertFailsWith<SnapshotException> { actual.currentHead(identity.copy(ownerId = "another-owner")) }
        assertFailsWith<SnapshotException> { SnapshotStore(actual.root, "another-owner") }
        assertEquals(left.snapshotId, actual.currentHead(identity).headSnapshotId)
        assertEquals(right.snapshotId, actual.currentHead(rightIdentity).headSnapshotId)
    }

    @Test
    fun unsafeBatchesAreRejectedWithoutChangingTheHead() {
        val actual = store()
        val base = seed(actual)
        val unsafe = listOf(
            listOf(put("../escape", "outside")),
            listOf(SnapshotChange.PutSymlink(SnapshotPath(path = "src/link.kt"), "../../outside")),
            listOf(put("src/Case.kt", "upper"), put("src/case.kt", "lower")),
            listOf(put("src/file", "file"), put("src/file/child.kt", "child")),
        )
        unsafe.forEachIndexed { index, changes ->
            assertFailsWith<SnapshotException> { actual.sealOverlay(request(base, "unsafe-$index", changes)) }
            assertEquals(base.snapshotId, actual.currentHead(identity).headSnapshotId)
            assertEquals(1, actual.currentHead(identity).acceptedPatchCount)
        }
        assertFalse(Files.exists(directory("escape")))
        assertEquals(base.manifest.entries, store().snapshot(identity, base.snapshotId).entries)
    }

    @Test
    fun mutableCallerBufferCannotRewriteTheCapturedContent() {
        val actual = store()
        actual.registerWorktree(identity)
        val original = "immutable content".toByteArray()
        val callerBuffer = original.copyOf()
        val blob = ImmutableBlob.copyOf(callerBuffer)
        callerBuffer.fill(0)
        val commit = actual.sealOverlay(request(null, "buffer", listOf(SnapshotChange.PutFile(SnapshotPath(path = "source.kt"), blob))))
        assertContentEquals(original, actual.readBlob(identity, commit.snapshotId, blob.digest))
    }

    @Test
    fun concurrentMaterializationLoserNeverDeletesTheWinnersDirectory() {
        val actual = store()
        actual.registerWorktree(identity)
        val captured = actual.sealOverlay(request(null, "materialize", (0 until 12).map {
            put("src/File$it.kt", "val value$it = $it\n".repeat(128))
        }))
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(12) { round ->
                val barrier = CyclicBarrier(3)
                val destination = directory("shared-materialization-$round")
                val attempts = (0 until 2).map {
                    pool.submit<Result<MaterializedSnapshot>> {
                        barrier.await(10, TimeUnit.SECONDS)
                        runCatching { actual.materialize(identity, captured.snapshotId, destination, readOnly = false) }
                    }
                }
                barrier.await(10, TimeUnit.SECONDS)
                val outcomes = attempts.map { it.get(20, TimeUnit.SECONDS) }
                assertEquals(1, outcomes.count { it.isSuccess }, "A single destination must have one creator; round $round")
                assertTrue(outcomes.single { it.isFailure }.exceptionOrNull() is SnapshotException)
                val winner = outcomes.single { it.isSuccess }.getOrThrow()
                val verification = actual.verifyMaterialization(winner)
                assertTrue(verification.valid, "Losing materialization destroyed winner: ${verification.violations}")
            }
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun expiredLeaseAndNewEditCannotBeMistakenForCurrentLiveCoverage() {
        val clock = MutableAuditClock(1_000)
        val live = Files.createDirectory(directory("live"))
        Files.writeString(live.resolve("Source.kt"), "val source = 1\n")
        val actual = SnapshotStore(directory("store"), identity.ownerId, clock = clock)
        actual.registerWorktree(identity, listOf(SnapshotRoot(livePath = live.toString())))
        val expired = actual.beginCooperativeEdit(identity, "client", "session", 0, true, leaseMillis = 10)
        assertEquals(LiveState.EDITING, actual.currentHead(identity).liveState)
        clock.advance(11)
        assertFailsWith<SnapshotException> {
            actual.captureCooperative(expired, LiveCaptureRequest(identity, "client", "session", "expired", 0))
        }
        assertNotEquals(LiveState.MATCHED_AT_CAPTURE, actual.currentHead(identity).liveState)
        val valid = actual.beginCooperativeEdit(identity, "client", "session", 0, true, leaseMillis = 1_000)
        val captured = actual.captureCooperative(valid, LiveCaptureRequest(identity, "client", "session", "captured", 0))
        val prior = actual.currentHead(identity)
        assertEquals(LiveState.MATCHED_AT_CAPTURE, prior.liveState)
        actual.beginCooperativeEdit(identity, "other-client", "other-session", captured.headRevision, true, leaseMillis = 1_000)
        val editing = actual.currentHead(identity)
        assertEquals(LiveState.EDITING, editing.liveState)
        assertTrue(editing.sourceEpoch > prior.sourceEpoch)
        assertEquals(prior.headSnapshotId, editing.headSnapshotId)
    }

    private fun child(
        mode: String,
        root: Path,
        identity: SnapshotIdentity,
        base: SnapshotCommit,
        name: String,
        ready: Path,
        start: Path,
        outcome: Path,
    ): Process {
        val classpath = requireNotNull(System.getProperty("ktchecker.audit.classpath")) {
            "The audit Gradle task must supply the actual test runtime classpath for bounded child JVMs"
        }
        val executable = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        return ProcessBuilder(
            executable.toString(), "-Xmx64m", "-XX:MaxMetaspaceSize=96m", "-cp", classpath,
            SnapshotAuditChild::class.java.name, mode, root.toString(), identity.ownerId, identity.workspaceId,
            identity.worktreeId, base.snapshotId, base.headRevision.toString(), name,
            ready.toString(), start.toString(), outcome.toString(),
        ).redirectErrorStream(true).start()
    }

    private fun awaitReady(children: List<ChildProcess>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (children.any { !Files.exists(it.ready) }) {
            for (child in children) {
                if (!child.process.isAlive) error("Child failed before barrier: ${child.process.inputStream.bufferedReader().readText()}")
            }
            check(System.nanoTime() < deadline) { "Child JVM barrier timed out" }
            Thread.sleep(10)
        }
    }

    private data class ChildProcess(val process: Process, val ready: Path, val outcome: Path)
}

private class MutableAuditClock(private var millis: Long) : Clock() {
    fun advance(amount: Long) { millis += amount }
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(millis)
    override fun millis(): Long = millis
}

private fun childRequest(identity: SnapshotIdentity, base: SnapshotCommit, name: String): OverlayRequest =
    OverlayRequest(identity, "child-$name", "child-session", name, base.snapshotId, base.headRevision,
        listOf(SnapshotChange.PutFile(SnapshotPath(path = "src/$name.kt"), ImmutableBlob.utf8(name))))

/** A real second process, including hard process death at a real store transaction boundary. */
object SnapshotAuditChild {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 11)
        val mode = arguments[0]
        val root = Path.of(arguments[1])
        val identity = SnapshotIdentity(arguments[2], arguments[3], arguments[4])
        val snapshotId = arguments[5]
        val revision = arguments[6].toLong()
        val name = arguments[7]
        val ready = Path.of(arguments[8])
        val start = Path.of(arguments[9])
        val outcome = Path.of(arguments[10])
        val actual = SnapshotStore(root, identity.ownerId, faults = SnapshotFaultInjector { point ->
            if (point == mode) Runtime.getRuntime().halt(71)
        })
        val manifest = actual.snapshot(identity, snapshotId)
        val base = SnapshotCommit(manifest, revision, manifest.capture.sourceEpoch)
        Files.writeString(ready, "ready")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!Files.exists(start)) {
            check(System.nanoTime() < deadline) { "Parent never released barrier" }
            Thread.sleep(10)
        }
        val result = try {
            val accepted = actual.sealOverlay(childRequest(identity, base, name))
            "OK ${accepted.snapshotId}"
        } catch (failure: SnapshotException) {
            "REJECT ${failure.code}"
        }
        Files.writeString(outcome, result)
    }
}

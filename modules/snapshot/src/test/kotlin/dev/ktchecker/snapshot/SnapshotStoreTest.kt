package dev.ktchecker.snapshot

import dev.ktchecker.contracts.Digests
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SnapshotStoreTest {
    @TempDir lateinit var temporary: Path
    private val identity = SnapshotIdentity("owner", "workspace", "tree")

    @AfterEach fun restoreTempPermissions() {
        // Materializations intentionally have read-only directories; restore only this test's area.
        Files.walkFileTree(temporary, object : java.nio.file.SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                dir.toFile().setWritable(true, true)
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                if (!attrs.isSymbolicLink) file.toFile().setWritable(true, true)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }

    private fun store(limits: SnapshotLimits = SnapshotLimits(), clock: Clock = Clock.systemUTC(), faults: SnapshotFaultInjector = SnapshotFaultInjector.NONE): SnapshotStore =
        SnapshotStore(temporary.resolve("state"), "owner", limits, clock, faults)

    private fun put(path: String, text: String, root: String = "project") =
        SnapshotChange.PutFile(SnapshotPath(root, path), ImmutableBlob.utf8(text))

    private fun request(
        patch: String,
        base: SnapshotCommit? = null,
        vararg changes: SnapshotChange,
        client: String = "agent-a",
        session: String = "session",
        mode: CommitMode = CommitMode.STRICT_HEAD,
        selected: SnapshotIdentity = identity,
    ): OverlayRequest = OverlayRequest(selected, client, session, patch, base?.snapshotId, base?.headRevision ?: 0, changes.toList(), mode)

    @Test fun `producer overlay is immutable and survives reopen`() {
        val store = store()
        store.registerWorktree(identity)
        val source = "val version = 1\r\n".toByteArray()
        val blob = ImmutableBlob.copyOf(source)
        source.fill(0)
        val commit = store.sealOverlay(request("patch-1", changes = arrayOf(SnapshotChange.PutFile(SnapshotPath(path = "src/A.kt"), blob))))
        val reopened = store()
        assertEquals(commit.manifest, reopened.snapshot(identity, commit.snapshotId))
        assertContentEquals("val version = 1\r\n".toByteArray(), reopened.readBlob(identity, commit.snapshotId, blob.digest))
        assertEquals(CaptureKind.IMMUTABLE_OVERLAY, commit.manifest.capture.kind)
        assertFalse(commit.manifest.capture.matchedLiveAtCapture)
        assertEquals(LiveState.UNVERIFIED, reopened.currentHead(identity).liveState)
        assertEquals(1, reopened.currentHead(identity).acceptedPatchCount)
    }

    @Test fun `duplicate request is exact replay and changed bytes conflict`() {
        val store = store()
        store.registerWorktree(identity)
        val original = request("patch-1", changes = arrayOf(put("src/A.kt", "class A")))
        val once = store.sealOverlay(original)
        val twice = store().sealOverlay(original)
        assertTrue(twice.replayed)
        assertEquals(once.snapshotId, twice.snapshotId)
        val error = assertFailsWith<SnapshotException> {
            store.sealOverlay(original.copy(changes = listOf(put("src/A.kt", "class Different"))))
        }
        assertEquals("idempotency_conflict", error.code)
        assertEquals(1, store.currentHead(identity).acceptedPatchCount)
    }

    @Test fun `client and session local patch ids are independent`() {
        val store = store()
        store.registerWorktree(identity)
        val first = store.sealOverlay(request("patch-1", changes = arrayOf(put("a/A.kt", "class A"))))
        val second = store.sealOverlay(request("patch-1", first, put("b/B.kt", "class B"), client = "agent-b"))
        val third = store.sealOverlay(request("patch-1", second, put("c/C.kt", "class C"), session = "new-session"))
        assertEquals(3, store.currentHead(identity).acceptedPatchCount)
        assertEquals(3, third.manifest.entries.size)
        assertNotEquals(first.snapshotId, second.snapshotId)
    }

    @Test fun `strict stale base is rejected without partially changing head`() {
        val store = store()
        store.registerWorktree(identity)
        val base = store.sealOverlay(request("base", changes = arrayOf(put("A.kt", "A"))))
        val latest = store.sealOverlay(request("new", base, put("B.kt", "B")))
        assertFailsWith<SnapshotConflict> { store.sealOverlay(request("stale", base, put("C.kt", "C"))) }
        assertEquals(latest.snapshotId, store.currentHead(identity).headSnapshotId)
        assertEquals(setOf("A.kt", "B.kt"), store.snapshot(identity, latest.snapshotId).entries.map { it.path }.toSet())
    }

    @Test fun `base digest revision and worktree must match`() {
        val store = store()
        store.registerWorktree(identity)
        val base = store.sealOverlay(request("base", changes = arrayOf(put("A.kt", "A"))))
        assertEquals("base_revision_mismatch", assertFailsWith<SnapshotException> {
            store.sealOverlay(request("wrong-revision", base, put("B.kt", "B")).copy(expectedHeadRevision = 0))
        }.code)
        val other = identity.copy(worktreeId = "independent-tree")
        store.registerWorktree(other)
        assertEquals("base_not_found", assertFailsWith<SnapshotException> {
            store.sealOverlay(request("wrong-tree", base, put("B.kt", "B"), selected = other))
        }.code)
    }

    @Test fun `two store instances concurrently rebase disjoint modules without lost files`() {
        val first = store()
        val second = store()
        first.registerWorktree(identity)
        val base = first.sealOverlay(request("base", changes = arrayOf(put("common/Api.kt", "interface Api"))))
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val work = listOf(first to "android", second to "desktop").map { (instance, module) ->
                pool.submit(Callable {
                    ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS))
                    instance.sealOverlay(request("patch-$module", base, put("$module/Use.kt", "class Use"),
                        client = module, mode = CommitMode.REBASE_DISJOINT))
                })
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown()
            work.forEach { it.get(30, TimeUnit.SECONDS) }
            val current = first.snapshot(identity, requireNotNull(first.currentHead(identity).headSnapshotId))
            assertEquals(setOf("common/Api.kt", "android/Use.kt", "desktop/Use.kt"), current.entries.map { it.path }.toSet())
            assertEquals(3, first.currentHead(identity).acceptedPatchCount)
        } finally { pool.shutdownNow() }
    }

    @Test fun `overlapping concurrent multifile batches cannot form a torn mixture`() {
        val store = store()
        store.registerWorktree(identity)
        val base = store.sealOverlay(request("base", changes = arrayOf(put("Api.kt", "old"), put("Use.kt", "old"))))
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { generation ->
                pool.submit(Callable {
                    start.await()
                    runCatching { store().sealOverlay(request("batch-$generation", base,
                        put("Api.kt", "$generation"), put("Use.kt", "$generation"), mode = CommitMode.REBASE_DISJOINT)) }
                })
            }
            start.countDown()
            val completed = results.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, completed.count { it.isSuccess })
            assertTrue(completed.single { it.isFailure }.exceptionOrNull() is SnapshotConflict)
            val head = store.snapshot(identity, requireNotNull(store.currentHead(identity).headSnapshotId))
            val contents = head.entries.map { String(store.readBlob(identity, head.snapshotId, requireNotNull(it.digest))) }
            assertEquals(1, contents.distinct().size)
        } finally { pool.shutdownNow() }
    }

    @Test fun `ABA edits are conflicts even after original bytes return`() {
        val store = store()
        store.registerWorktree(identity)
        val base = store.sealOverlay(request("a", changes = arrayOf(put("A.kt", "A"))))
        val changed = store.sealOverlay(request("b", base, put("A.kt", "B")))
        val restored = store.sealOverlay(request("a-again", changed, put("A.kt", "A")))
        assertEquals(base.manifest.treeDigest, restored.manifest.treeDigest)
        assertFailsWith<SnapshotConflict> {
            store().sealOverlay(request("stale-aba", base, put("A.kt", "C"), mode = CommitMode.REBASE_DISJOINT))
        }
    }

    @Test fun `add delete tombstones prevent stale create ABA`() {
        val store = store()
        store.registerWorktree(identity)
        val base = store.sealOverlay(request("empty"))
        val added = store.sealOverlay(request("add", base, put("A.kt", "A")))
        val deleted = store.sealOverlay(request("delete", added, SnapshotChange.Delete(SnapshotPath(path = "A.kt"))))
        assertTrue(deleted.manifest.entries.isEmpty())
        assertEquals("deleted", deleted.manifest.delta.single().operation)
        assertFailsWith<SnapshotConflict> { store.sealOverlay(request("stale-create", base, put("A.kt", "B"), mode = CommitMode.REBASE_DISJOINT)) }
    }

    @Test fun `rename and case-only rename record delete add and preserve bytes`() {
        val store = store()
        store.registerWorktree(identity)
        val base = store.sealOverlay(request("base", changes = arrayOf(put("src/a.kt", "class A"))))
        val moved = store.sealOverlay(request("move", base, SnapshotChange.Rename(SnapshotPath(path = "src/a.kt"), SnapshotPath(path = "src/A.kt"))))
        assertEquals(setOf("added", "deleted"), moved.manifest.delta.map { it.operation }.toSet())
        assertEquals("src/A.kt", moved.manifest.entries.single().path)
        assertEquals(base.manifest.entries.single().digest, moved.manifest.entries.single().digest)
        assertNotEquals(base.manifest.treeDigest, moved.manifest.treeDigest)
    }

    @Test fun `unsafe paths case directory collisions unicode and windows names fail atomically`() {
        val store = store()
        store.registerWorktree(identity)
        listOf("../secret", "/absolute", "a/../../secret", "a\\b.kt", "C:/x.kt", "./A.kt", "a//B.kt", "NUL.kt", "a. /B", "Cafe\u0301.kt").forEachIndexed { index, path ->
            assertFailsWith<SnapshotException>(path) { store.sealOverlay(request("bad-$index", changes = arrayOf(put(path, "x")))) }
        }
        assertEquals("case_collision", assertFailsWith<SnapshotException> {
            store.sealOverlay(request("case", changes = arrayOf(put("src/A.kt", "A"), put("Src/B.kt", "B"))))
        }.code)
        assertEquals("file_directory_collision", assertFailsWith<SnapshotException> {
            store.sealOverlay(request("directory", changes = arrayOf(put("src", "file"), put("src/A.kt", "A"))))
        }.code)
        assertEquals(0, store.currentHead(identity).acceptedPatchCount)
    }

    @Test fun `symlink escape missing target and cycles are rejected`() {
        val store = store()
        store.registerWorktree(identity)
        listOf("../../secret", "/secret", "C:/secret").forEachIndexed { index, target ->
            assertEquals("symlink_escape", assertFailsWith<SnapshotException> {
                store.sealOverlay(request("escape-$index", changes = arrayOf(SnapshotChange.PutSymlink(SnapshotPath(path = "src/link"), target))))
            }.code)
        }
        assertEquals("symlink_target_missing", assertFailsWith<SnapshotException> {
            store.sealOverlay(request("missing", changes = arrayOf(SnapshotChange.PutSymlink(SnapshotPath(path = "link"), "not-captured"))))
        }.code)
        assertEquals("symlink_cycle", assertFailsWith<SnapshotException> {
            store.sealOverlay(request("cycle", changes = arrayOf(
                SnapshotChange.PutSymlink(SnapshotPath(path = "a"), "b"), SnapshotChange.PutSymlink(SnapshotPath(path = "b"), "a"))))
        }.code)
        assertEquals(0, store.currentHead(identity).headRevision)
    }

    @Test fun `symlink descriptors remain part of content identity and provider projection`() {
        val store = store()
        store.registerWorktree(identity)
        val first = store.sealOverlay(request("link", changes = arrayOf(put("src/A.kt", "A"),
            SnapshotChange.PutSymlink(SnapshotPath(path = "Link.kt"), "src/A.kt"))))
        val second = store.sealOverlay(request("link-text-change", first,
            SnapshotChange.PutSymlink(SnapshotPath(path = "Link.kt"), "./src/A.kt")))
        assertNotEquals(first.manifest.treeDigest, second.manifest.treeDigest)
        val shared = second.toContractManifest()
        assertEquals(2, shared.files.size)
        assertEquals(1, shared.files.map { it.digest }.distinct().size)
    }

    @Test fun `configuration artifact and approved external roots participate in snapshot identity`() {
        val store = store()
        store.registerWorktree(identity, listOf(SnapshotRoot(), SnapshotRoot("external", "included")))
        val base = store.sealOverlay(request("base", changes = arrayOf(put("A.kt", "A"), put("build.gradle.kts", "v1"), put("library.jar", "one", "external"))))
        val config = store.sealOverlay(request("config", base, put("build.gradle.kts", "v2")))
        val dependency = store.sealOverlay(request("artifact", config, put("library.jar", "two", "external")))
        assertNotEquals(base.manifest.treeDigest, config.manifest.treeDigest)
        assertNotEquals(config.manifest.treeDigest, dependency.manifest.treeDigest)
        assertTrue(dependency.toContractManifest().files.any { it.path == "included/library.jar" })
    }

    @Test fun `scope omissions survive shared contract projection`() {
        val store = store()
        store.registerWorktree(identity)
        val commit = store.sealOverlay(request("partial", changes = arrayOf(put("A.kt", "A"))).copy(
            scope = SnapshotScope("one module", listOf(CaptureExclusion("project", "generated", "generator not approved")), listOf("Native SDK"))))
        val contract = commit.toContractManifest()
        assertEquals(listOf("project"), contract.declaredRoots)
        assertTrue(contract.scopeExclusions.single().contains("generator not approved"))
        assertEquals(listOf("Native SDK"), contract.unknownInputs)
    }

    @Test fun `different worktrees and owners cannot read each other's snapshots`() {
        val store = store()
        store.registerWorktree(identity)
        val first = store.sealOverlay(request("first", changes = arrayOf(put("secret.kt", "private"))))
        val secondIdentity = identity.copy(worktreeId = "other")
        store.registerWorktree(secondIdentity)
        assertEquals("snapshot_not_admitted", assertFailsWith<SnapshotException> { store.snapshot(secondIdentity, first.snapshotId) }.code)
        assertEquals("permission_denied", assertFailsWith<SnapshotException> { store.snapshot(identity.copy(ownerId = "other-owner"), first.snapshotId) }.code)
        assertEquals("permission_denied", assertFailsWith<SnapshotException> { SnapshotStore(temporary.resolve("state"), "other-owner") }.code)
        val differentOwner = SnapshotStore(temporary.resolve("different-owner-state"), "other-owner")
        differentOwner.registerWorktree(identity.copy(ownerId = "other-owner"))
        assertEquals(0, differentOwner.currentHead(identity.copy(ownerId = "other-owner")).acceptedPatchCount)
    }

    @Test fun `immutable source materialization cannot mutate CAS or another execution`() {
        val store = store()
        store.registerWorktree(identity)
        val commit = store.sealOverlay(request("base", changes = arrayOf(put("src/A.kt", "class A"))))
        val first = store.materialize(identity, commit.snapshotId, temporary.resolve("execution-a"))
        val second = store.materialize(identity, commit.snapshotId, temporary.resolve("execution-b"))
        val file = first.root.resolve("src/A.kt")
        file.toFile().setWritable(true, true)
        Files.writeString(file, "class B")
        assertFalse(store.verifyMaterialization(first).valid)
        assertTrue(store.verifyMaterialization(second).valid)
        assertEquals("class A", Files.readString(second.root.resolve("src/A.kt")))
        assertEquals("class A", String(store.readBlob(identity, commit.snapshotId, requireNotNull(commit.manifest.entries.single().digest))))
        assertEquals("materialization_exists", assertFailsWith<SnapshotException> { store.materialize(identity, commit.snapshotId, second.root) }.code)
    }

    @Test fun `unexpected provider outputs inside source invalidate materialization`() {
        val store = store()
        store.registerWorktree(identity)
        val commit = store.sealOverlay(request("base", changes = arrayOf(put("A.kt", "A"))))
        val execution = store.materialize(identity, commit.snapshotId, temporary.resolve("execution"), readOnly = false)
        Files.writeString(execution.root.resolve("generated.kt"), "unexpected")
        assertTrue(store.verifyMaterialization(execution).violations.contains("unexpected_input:generated.kt"))
    }

    @Test fun `CAS corruption is unavailable evidence not a successful snapshot read`() {
        val store = store()
        store.registerWorktree(identity)
        val commit = store.sealOverlay(request("base", changes = arrayOf(put("A.kt", "A"))))
        val digest = requireNotNull(commit.manifest.entries.single().digest)
        val hex = digest.removePrefix("sha256:")
        Files.writeString(store.root.resolve("objects/blobs/${hex.take(2)}/${hex.drop(2)}"), "B")
        assertEquals("store_corrupt", assertFailsWith<SnapshotException> { store.readBlob(identity, commit.snapshotId, digest) }.code)
        assertEquals("store_corrupt", assertFailsWith<SnapshotException> { store.materialize(identity, commit.snapshotId, temporary.resolve("failed-execution")) }.code)
        assertFalse(Files.exists(temporary.resolve("failed-execution"), NOFOLLOW_LINKS))
    }

    @Test fun `precommit crash leaves no admission and retry can install original patch`() {
        var injected = false
        val store = store(faults = SnapshotFaultInjector { point ->
            if (point == "before_state_install" && !injected) { injected = true; error("simulated broker crash") }
        })
        store.registerWorktree(identity)
        val patch = request("base", changes = arrayOf(put("A.kt", "A")))
        assertFailsWith<IllegalStateException> { store.sealOverlay(patch) }
        assertEquals(0, store().currentHead(identity).acceptedPatchCount)
        val retried = store().sealOverlay(patch)
        assertFalse(retried.replayed)
        assertEquals(1, store().currentHead(identity).acceptedPatchCount)
    }

    @Test fun `crash after durability boundary preserves accepted patch and exact replay`() {
        var injected = false
        val store = store(faults = SnapshotFaultInjector { point ->
            if (point == "after_state_install" && !injected) { injected = true; error("response lost after commit") }
        })
        store.registerWorktree(identity)
        val patch = request("base", changes = arrayOf(put("A.kt", "A")))
        assertFailsWith<IllegalStateException> { store.sealOverlay(patch) }
        val retried = store().sealOverlay(patch)
        assertTrue(retried.replayed)
        assertEquals(1, store().currentHead(identity).acceptedPatchCount)
    }

    @Test fun `active edit invalidates current immediately and next writer waits`() {
        val live = Files.createDirectory(temporary.resolve("live"))
        Files.writeString(live.resolve("A.kt"), "A")
        val store = store()
        store.registerWorktree(identity, listOf(SnapshotRoot(livePath = live.toString())))
        val lease = store.beginCooperativeEdit(identity, "agent-a", "session", 0, allWritersCooperate = true)
        assertEquals(LiveState.EDITING, store().currentHead(identity).liveState)
        assertEquals(1, store.currentHead(identity).sourceEpoch)
        assertEquals("worktree_editing", assertFailsWith<SnapshotException> {
            store.beginCooperativeEdit(identity, "agent-b", "other", 0, allWritersCooperate = true)
        }.code)
        val capture = store.captureCooperative(lease, LiveCaptureRequest(identity, "agent-a", "session", "capture", 0))
        assertTrue(capture.manifest.capture.coherent)
        assertTrue(capture.manifest.capture.matchedLiveAtCapture)
        assertEquals(LiveState.MATCHED_AT_CAPTURE, store.currentHead(identity).liveState)
        assertEquals(2, store.currentHead(identity).sourceEpoch)
        assertTrue(store.captureCooperative(lease, LiveCaptureRequest(identity, "agent-a", "session", "capture", 0)).replayed)
    }

    @Test fun `cooperative multifile capture includes untracked ignored build config and explicit exclusions`() {
        val live = Files.createDirectory(temporary.resolve("live"))
        Files.createDirectories(live.resolve("generated"))
        Files.createDirectories(live.resolve(".git"))
        Files.writeString(live.resolve("A.kt"), "A")
        Files.writeString(live.resolve(".gitignore"), "generated/")
        Files.writeString(live.resolve("generated/B.kt"), "B")
        Files.writeString(live.resolve("build.gradle.kts"), "plugins {}")
        Files.writeString(live.resolve(".git/config"), "excluded")
        val store = store()
        store.registerWorktree(identity, listOf(SnapshotRoot(livePath = live.toString())))
        val lease = store.beginCooperativeEdit(identity, "a", "s", 0, true)
        val capture = store.captureCooperative(lease, LiveCaptureRequest(identity, "a", "s", "capture", 0,
            SnapshotScope("all registered inputs except Git metadata", listOf(CaptureExclusion("project", ".git", "not project build input")))))
        assertEquals(setOf("A.kt", ".gitignore", "generated/B.kt", "build.gradle.kts"), capture.manifest.entries.map { it.path }.toSet())
    }

    @Test fun `best effort stable filesystem still cannot claim coherent capture or live current`() {
        val live = Files.createDirectory(temporary.resolve("live"))
        Files.writeString(live.resolve("A.kt"), "A")
        val store = store()
        store.registerWorktree(identity, listOf(SnapshotRoot(livePath = live.toString())))
        val capture = store.captureBestEffort(LiveCaptureRequest(identity, "a", "s", "best-effort", 0))
        assertFalse(capture.manifest.capture.coherent)
        assertFalse(capture.manifest.capture.matchedLiveAtCapture)
        assertTrue(capture.manifest.capture.uncertainty.single().contains("ABA"))
        assertEquals(LiveState.UNVERIFIED, store.currentHead(identity).liveState)
    }

    @Test fun `expired lease after crash remains unreviewed and stale writer cannot commit`() {
        val live = Files.createDirectory(temporary.resolve("live"))
        Files.writeString(live.resolve("A.kt"), "A")
        val clock = MutableClock(1000)
        val store = store(clock = clock)
        store.registerWorktree(identity, listOf(SnapshotRoot(livePath = live.toString())))
        val stale = store.beginCooperativeEdit(identity, "a", "s", 0, true, leaseMillis = 100)
        Files.writeString(live.resolve("A.kt"), "unfinished")
        clock.now = 1101
        val reopened = store(clock = clock)
        assertEquals(LiveState.EDITING, reopened.currentHead(identity).liveState)
        assertEquals(0, reopened.currentHead(identity).acceptedPatchCount)
        val next = reopened.beginCooperativeEdit(identity, "b", "s", 0, true, leaseMillis = 100)
        assertTrue(next.fence > stale.fence)
        assertEquals("stale_edit_lease", assertFailsWith<SnapshotException> {
            reopened.captureCooperative(stale, LiveCaptureRequest(identity, "a", "s", "stale", 0))
        }.code)
        reopened.abandonEdit(next)
        assertEquals(LiveState.UNVERIFIED, reopened.currentHead(identity).liveState)
    }

    @Test fun `all-writer participation cannot be inferred or skipped`() {
        val live = Files.createDirectory(temporary.resolve("live"))
        val store = store()
        store.registerWorktree(identity, listOf(SnapshotRoot(livePath = live.toString())))
        assertEquals("writer_cooperation_required", assertFailsWith<SnapshotException> {
            store.beginCooperativeEdit(identity, "a", "s", 0, allWritersCooperate = false)
        }.code)
        assertEquals(0, store.currentHead(identity).sourceEpoch)
    }

    @Test fun `same physical worktree cannot acquire independent aliases and fences`() {
        val live = Files.createDirectory(temporary.resolve("live"))
        val store = store()
        store.registerWorktree(identity, listOf(SnapshotRoot(livePath = live.toString())))
        assertEquals("worktree_alias", assertFailsWith<SnapshotException> {
            store.registerWorktree(identity.copy(worktreeId = "alias"), listOf(SnapshotRoot(livePath = live.toString())))
        }.code)
        val other = Files.createDirectory(temporary.resolve("other-live"))
        store.registerWorktree(identity.copy(worktreeId = "independent"), listOf(SnapshotRoot(livePath = other.toString())))
    }

    @Test fun `short current-head fence excludes commits from another store instance`() {
        val store = store()
        store.registerWorktree(identity)
        val other = store()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val fence = pool.submit(Callable {
                store.withCurrentHead(identity) { head ->
                    entered.countDown()
                    assertTrue(release.await(10, TimeUnit.SECONDS))
                    assertEquals(0, head.headRevision)
                }
            })
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val commit = pool.submit(Callable { other.sealOverlay(request("new", changes = arrayOf(put("A.kt", "A")))) })
            Thread.sleep(100)
            assertFalse(commit.isDone)
            release.countDown()
            fence.get(10, TimeUnit.SECONDS)
            assertEquals(1, commit.get(10, TimeUnit.SECONDS).headRevision)
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun `bounded patch and blob quotas reject without losing earlier accepted work`() {
        val store = store(SnapshotLimits(maxBlobBytes = 4, maxPatchesPerWorktree = 1))
        store.registerWorktree(identity)
        assertEquals("blob_too_large", assertFailsWith<SnapshotException> {
            store.sealOverlay(request("large", changes = arrayOf(put("A.kt", "12345"))))
        }.code)
        val first = store.sealOverlay(request("first", changes = arrayOf(put("A.kt", "A"))))
        assertEquals("patch_history_full", assertFailsWith<SnapshotException> { store.sealOverlay(request("second", first, put("B.kt", "B"))) }.code)
        assertTrue(store.sealOverlay(request("first", changes = arrayOf(put("A.kt", "A")))).replayed)
        assertEquals(first.snapshotId, store.currentHead(identity).headSnapshotId)
    }

    @Test fun `provided producer digest must match exact raw bytes`() {
        assertEquals("blob_digest_mismatch", assertFailsWith<SnapshotException> {
            ImmutableBlob.copyOf("a\r\n".toByteArray(), Digests.sha256("a\n"))
        }.code)
    }

    @Test fun `incremental overlay manifests equal a fresh complete byte oracle for every integrated patch`() {
        val store = store()
        store.registerWorktree(identity)
        var head = store.sealOverlay(request("initial"))
        val expected = linkedMapOf<String, String>()
        repeat(16) { index ->
            val path = "module${index % 4}/File${index % 7}.kt"
            val change = if (index % 3 == 2 && expected.isNotEmpty()) {
                val removed = expected.keys.first()
                expected.remove(removed)
                SnapshotChange.Delete(SnapshotPath(path = removed))
            } else {
                expected[path] = "fun value() = $index\n"
                put(path, requireNotNull(expected[path]))
            }
            head = store.sealOverlay(request("incremental-$index", head, change))
            val oracleIdentity = identity.copy(worktreeId = "oracle-$index")
            store.registerWorktree(oracleIdentity)
            val oracle = store.sealOverlay(request("full-$index", changes = expected.map { put(it.key, it.value) }.toTypedArray(), selected = oracleIdentity))
            assertEquals(oracle.manifest.entries, head.manifest.entries, "entries differ at accepted patch $index")
            assertEquals(oracle.manifest.treeDigest, head.manifest.treeDigest, "tree identity differs at accepted patch $index")
        }
    }

    @Test fun `bounded sustained concurrent disjoint submissions preserve all integrated inputs`() {
        val store = store()
        store.registerWorktree(identity)
        val base = store.sealOverlay(request("initial"))
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 32).map { index ->
                pool.submit(Callable {
                    store.sealOverlay(request("patch-1", base, put("module-$index/A.kt", "class A$index"),
                        client = "client-$index", mode = CommitMode.REBASE_DISJOINT))
                })
            }
            futures.forEach { it.get(60, TimeUnit.SECONDS) }
            val current = store.snapshot(identity, requireNotNull(store.currentHead(identity).headSnapshotId))
            assertEquals(32, current.entries.size)
            assertEquals(33, store.currentHead(identity).acceptedPatchCount)
            assertEquals((0 until 32).map { "module-$it/A.kt" }.toSet(), current.entries.map { it.path }.toSet())
        } finally { pool.shutdownNow() }
    }

    @Test fun `unregistered status and submission cannot grow the state namespace`() {
        val store = store()
        store.registerWorktree(identity)
        repeat(64) { index ->
            val unknown = identity.copy(worktreeId = "unknown-$index")
            assertEquals("worktree_not_registered", assertFailsWith<SnapshotException> { store.currentHead(unknown) }.code)
            assertEquals("worktree_not_registered", assertFailsWith<SnapshotException> { store.sealOverlay(request("patch", selected = unknown)) }.code)
        }
        Files.list(store.root.resolve("worktrees")).use { assertEquals(1L, it.count()) }
    }

    @Test fun `real escaping symlink capture never ingests target bytes`() {
        val live = Files.createDirectory(temporary.resolve("live"))
        val privateFile = Files.writeString(temporary.resolve("outside-secret"), "do-not-read")
        val supported = runCatching { Files.createSymbolicLink(live.resolve("escape"), Path.of("../outside-secret")) }.isSuccess
        assumeTrue(supported, "This runner cannot create symlinks; no symlink execution coverage is claimed")
        val store = store()
        store.registerWorktree(identity, listOf(SnapshotRoot(livePath = live.toString())))
        assertEquals("symlink_escape", assertFailsWith<SnapshotException> {
            store.captureBestEffort(LiveCaptureRequest(identity, "a", "s", "capture", 0))
        }.code)
        assertEquals(0, store.currentHead(identity).acceptedPatchCount)
        val digest = Digests.sha256(Files.readAllBytes(privateFile)).removePrefix("sha256:")
        assertFalse(Files.exists(store.root.resolve("objects/blobs/${digest.take(2)}/${digest.drop(2)}")))
    }

    @Test fun `real safe symlinks materialize without hardlinks or namespace escapes`() {
        val probe = Files.writeString(temporary.resolve("target"), "target")
        val supported = runCatching { Files.createSymbolicLink(temporary.resolve("link-probe"), probe.fileName) }.isSuccess
        assumeTrue(supported, "This runner cannot create symlinks; no symlink execution coverage is claimed")
        val store = store()
        store.registerWorktree(identity)
        val commit = store.sealOverlay(request("safe", changes = arrayOf(put("source/A.kt", "class A"),
            SnapshotChange.PutSymlink(SnapshotPath(path = "alias/Linked.kt"), "../source/A.kt"))))
        val execution = store.materialize(identity, commit.snapshotId, temporary.resolve("safe-execution"))
        assertTrue(Files.isSymbolicLink(execution.root.resolve("alias/Linked.kt")))
        assertEquals("class A", Files.readString(execution.root.resolve("alias/Linked.kt")))
        assertTrue(store.verifyMaterialization(execution).valid)
    }

    @Test fun `symlink expansion participates in input size bounds`() {
        val store = store(SnapshotLimits(maxSnapshotBytes = 8))
        store.registerWorktree(identity)
        assertEquals("snapshot_too_large", assertFailsWith<SnapshotException> {
            store.sealOverlay(request("aliases", changes = arrayOf(put("data", "1234"),
                SnapshotChange.PutSymlink(SnapshotPath(path = "alias-a"), "data"),
                SnapshotChange.PutSymlink(SnapshotPath(path = "alias-b"), "data"))))
        }.code)
        assertEquals(0, store.currentHead(identity).acceptedPatchCount)
    }

    @Test fun `removed live worktree does not block unrelated project registration`() {
        val first = Files.createDirectory(temporary.resolve("first-live"))
        val second = Files.createDirectory(temporary.resolve("second-live"))
        val store = store()
        store.registerWorktree(identity, listOf(SnapshotRoot(livePath = first.toString())))
        Files.delete(first)
        val other = identity.copy(worktreeId = "unrelated")
        assertEquals(other, store.registerWorktree(other, listOf(SnapshotRoot(livePath = second.toString()))).identity)
    }

    @Test fun `verified overlay fence is exact declared content but not physical live proof`() {
        val store = store()
        store.registerWorktree(identity)
        val commit = store.sealOverlay(request("overlay", changes = arrayOf(put("A.kt", "A"))))
        store.withVerifiedSnapshot(identity, commit.snapshotId) { check ->
            assertTrue(check.valid)
            assertTrue(check.coherent)
            assertFalse(check.liveMatchCertified)
            assertEquals(commit.sourceEpoch, check.expectedSourceEpoch)
        }
        store.withVerifiedSnapshot(identity, commit.snapshotId, requireLiveMatch = true) { check ->
            assertFalse(check.valid)
            assertEquals("live_snapshot_unverified", check.reason)
        }
    }

    @Test fun `verified fence rejects same snapshot id after new edit and abandonment epoch`() {
        val live = Files.createDirectory(temporary.resolve("live"))
        Files.writeString(live.resolve("A.kt"), "A")
        val store = store()
        store.registerWorktree(identity, listOf(SnapshotRoot(livePath = live.toString())))
        val firstLease = store.beginCooperativeEdit(identity, "a", "s", 0, true)
        val capture = store.captureCooperative(firstLease, LiveCaptureRequest(identity, "a", "s", "capture", 0))
        store.withVerifiedSnapshot(identity, capture.snapshotId, requireLiveMatch = true) { assertTrue(it.valid) }
        val nextLease = store.beginCooperativeEdit(identity, "b", "s", capture.headRevision, true)
        store.abandonEdit(nextLease)
        assertEquals(capture.snapshotId, store.currentHead(identity).headSnapshotId)
        store.withVerifiedSnapshot(identity, capture.snapshotId) { check ->
            assertFalse(check.valid)
            assertEquals("source_epoch_changed", check.reason)
            assertTrue(check.head.sourceEpoch > check.expectedSourceEpoch)
            assertEquals(null, check.head.activeLease)
        }
    }

    @Test fun `verified fence surfaces omissions rather than treating partial input as full project`() {
        val store = store()
        store.registerWorktree(identity)
        val partial = store.sealOverlay(request("partial", changes = arrayOf(put("A.kt", "A"))).copy(
            scope = SnapshotScope("declared module only", unknownInputs = listOf("generated-source input closure"))))
        store.withVerifiedSnapshot(identity, partial.snapshotId) {
            assertFalse(it.valid)
            assertEquals("snapshot_inputs_unknown", it.reason)
        }
        store.withVerifiedSnapshot(identity, partial.snapshotId, requireNoOmissions = false) {
            assertTrue(it.valid)
            assertEquals(listOf("generated-source input closure"), it.scope.unknownInputs)
        }
    }

    @Test fun `best effort capture can never pass exact snapshot fence`() {
        val live = Files.createDirectory(temporary.resolve("live"))
        Files.writeString(live.resolve("A.kt"), "A")
        val store = store()
        store.registerWorktree(identity, listOf(SnapshotRoot(livePath = live.toString())))
        val capture = store.captureBestEffort(LiveCaptureRequest(identity, "a", "s", "capture", 0))
        store.withVerifiedSnapshot(identity, capture.snapshotId, requireNoOmissions = false) {
            assertFalse(it.valid)
            assertEquals("snapshot_not_coherent", it.reason)
        }
    }

    @Test fun `changed file overlays cannot erase inherited omissions even after disjoint rebase`() {
        val store = store()
        store.registerWorktree(identity)
        val base = store.sealOverlay(request("partial-base", changes = arrayOf(put("A.kt", "A"))).copy(
            scope = SnapshotScope(unknownInputs = listOf("unresolved generator outputs"),
                exclusions = listOf(CaptureExclusion("project", "generated", "generator not authorized")))))
        val first = store.sealOverlay(request("first", base, put("B.kt", "B")).copy(
            scope = SnapshotScope(unknownInputs = listOf("external header closure"))))
        val rebased = store.sealOverlay(request("second", base, put("C.kt", "C"), mode = CommitMode.REBASE_DISJOINT))
        assertEquals(setOf("external header closure", "unresolved generator outputs"), rebased.manifest.scope.unknownInputs.toSet())
        assertEquals(base.manifest.scope.exclusions, rebased.manifest.scope.exclusions)
        assertEquals(first.snapshotId, rebased.manifest.parentSnapshotId)
        store.withVerifiedSnapshot(identity, rebased.snapshotId) {
            assertFalse(it.valid)
            assertEquals("snapshot_inputs_unknown", it.reason)
        }
        assertEquals("exclusion_conflict", assertFailsWith<SnapshotException> {
            store.sealOverlay(request("partial-restore", rebased, put("generated/One.kt", "incomplete restoration")))
        }.code)
    }

    private class MutableClock(var now: Long) : Clock() {
        override fun instant(): Instant = Instant.ofEpochMilli(now)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun millis(): Long = now
    }
}

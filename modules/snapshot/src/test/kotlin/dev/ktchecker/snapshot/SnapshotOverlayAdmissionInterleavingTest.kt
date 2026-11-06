package dev.ktchecker.snapshot

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Deterministic single-thread schedules at the public transaction-boundary test SPI.
 * These are real SnapshotStore operations, not a cross-process race qualification.
 * The final check is the snapshot half only: no provider, kernel gate, live-source,
 * authentication, model delivery, or native-action authority is exercised.
 */
class SnapshotOverlayAdmissionInterleavingTest {
    @TempDir lateinit var temporary: Path

    private val identity = SnapshotIdentity("owner", "workspace", "tree")
    private val limits = SnapshotLimits(maxBlobBytes = 4_096, maxSnapshotBytes = 65_536,
        maxFiles = 32, maxPatchesPerWorktree = 16, maxTrackedPaths = 64, maxWorktrees = 8,
        lockTimeoutMillis = 1_000)
    private val baseFiles = linkedMapOf(
        API to "package shared\nfun apiValue() = 1\n",
        LEFT to "package left\nfun leftValue() = shared.apiValue()\n",
        RIGHT to "package right\nfun rightValue() = shared.apiValue()\n",
        CONSUMER to "package consumer\nfun unchangedConsumer() = shared.apiValue()\n",
    )
    private val peerFiles = linkedMapOf(
        API to "package shared\nfun apiValue() = 2\n",
        LEFT to "package left\nfun leftValue() = shared.apiValue() + 1\n",
    )
    private val writerFiles = linkedMapOf(
        RIGHT to "package right\nfun rightValue() = shared.apiValue() + 2\n",
        RIGHT_CONFIG to "synthetic module-right option=2\n",
    )

    private data class Fixture(
        val store: SnapshotStore, val identity: SnapshotIdentity,
        val base: SnapshotCommit, val baseHead: WorktreeState,
    )

    private fun seed(directory: Path, selected: SnapshotIdentity = identity): Fixture {
        val store = SnapshotStore(directory, selected.ownerId, limits)
        store.registerWorktree(selected)
        val base = store.sealOverlay(OverlayRequest(selected, "seed", "seed-session", "base", null, 0,
            changes(baseFiles)))
        return Fixture(store, selected, base, store.currentHead(selected))
    }

    private fun changes(files: Map<String, String>): List<SnapshotChange> = files.map { (path, text) ->
        SnapshotChange.PutFile(SnapshotPath(path = path), ImmutableBlob.utf8(text))
    }

    private fun overlay(fixture: Fixture, client: String, patch: String, files: Map<String, String>,
                        mode: CommitMode = CommitMode.STRICT_HEAD): OverlayRequest =
        OverlayRequest(fixture.identity, client, "session-$client", patch, fixture.base.snapshotId,
            fixture.base.headRevision, changes(files), mode)

    private class FirstBlobSchedule(private val interleave: () -> Unit) : SnapshotFaultInjector {
        val points = mutableListOf<String>()
        var firings = 0
            private set
        override fun at(point: String) {
            points += point
            if (point == "blob_installed" && firings == 0) {
                firings++ // Mark before callback; the peer itself has no injected hook.
                interleave()
            }
        }
    }

    private fun instrumented(fixture: Fixture, schedule: FirstBlobSchedule): SnapshotStore =
        SnapshotStore(fixture.store.root, fixture.identity.ownerId, limits, faults = schedule)

    private fun assertContents(store: SnapshotStore, selected: SnapshotIdentity, commit: SnapshotCommit,
                               expected: Map<String, String>) {
        val manifest = store.snapshot(selected, commit.snapshotId)
        assertEquals(commit.manifest, manifest)
        val actual = manifest.entries.associate { entry ->
            assertEquals(SnapshotEntryKind.FILE, entry.kind)
            val bytes = store.readBlob(selected, commit.snapshotId, assertNotNull(entry.digest))
            assertEquals(entry.size, bytes.size.toLong())
            manifest.logicalPath(entry) to bytes.toString(Charsets.UTF_8)
        }
        assertEquals(expected, actual, "The complete combined content, not just edited filenames, must match")
        assertEquals(expected.keys.sorted(), manifest.toContractManifest().files.map { it.path })
        assertEquals(CaptureKind.IMMUTABLE_OVERLAY, manifest.capture.kind)
        assertTrue(manifest.capture.coherent)
        assertFalse(manifest.capture.matchedLiveAtCapture)
    }

    private fun assertCurrentSnapshotOnly(store: SnapshotStore, selected: SnapshotIdentity, commit: SnapshotCommit) {
        val current = store.currentHead(selected) // Never reenter snapshot APIs inside the held callback.
        assertEquals(commit.snapshotId, current.headSnapshotId)
        assertEquals(commit.headRevision, current.headRevision)
        store.withVerifiedSnapshot(selected, commit.snapshotId, requireLiveMatch = false, requireNoOmissions = true) { check ->
            assertTrue(check.valid, check.reason)
            assertEquals(null, check.reason)
            assertEquals(current, check.head)
            assertEquals(commit.snapshotId, check.expectedSnapshotId)
            assertEquals(commit.sourceEpoch, check.expectedSourceEpoch)
            assertTrue(check.coherent)
            assertFalse(check.liveMatchCertified, "An exact overlay is not a physical live-capture certificate")
        }
    }

    private fun assertStale(store: SnapshotStore, selected: SnapshotIdentity, old: SnapshotCommit, current: SnapshotCommit) {
        store.withVerifiedSnapshot(selected, old.snapshotId, requireLiveMatch = false, requireNoOmissions = true) { check ->
            assertFalse(check.valid)
            assertEquals("stale_snapshot", check.reason)
            assertEquals(old.snapshotId, check.expectedSnapshotId)
            assertEquals(current.snapshotId, check.head.headSnapshotId)
        }
    }

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun disjointRebaseUsesPeerHeadAfterBlobIngestionAndOnlyCombinedSnapshotIsCurrent() {
        val fixture = seed(temporary.resolve("state"))
        val peer = SnapshotStore(fixture.store.root, identity.ownerId, limits)
        val peerRequest = overlay(fixture, "left", "left-patch", peerFiles)
        val request = overlay(fixture, "right", "right-patch", writerFiles, CommitMode.REBASE_DISJOINT)
        var peerCommit: SnapshotCommit? = null
        val schedule = FirstBlobSchedule {
            assertEquals(fixture.baseHead, peer.currentHead(identity))
            peerCommit = peer.sealOverlay(peerRequest)
            assertEquals(fixture.base.headRevision + 1, peer.currentHead(identity).headRevision)
        }
        val combined = instrumented(fixture, schedule).sealOverlay(request)
        val interposed = assertNotNull(peerCommit)
        assertEquals(1, schedule.firings)
        assertEquals(listOf("blob_installed", "blob_installed", "manifest_installed", "before_state_install", "after_state_install"), schedule.points)
        assertFalse(combined.replayed)
        assertEquals(fixture.base.snapshotId, combined.manifest.producerBaseSnapshotId)
        assertEquals(interposed.snapshotId, combined.manifest.parentSnapshotId)
        assertEquals(fixture.base.headRevision + 2, combined.headRevision)
        assertEquals(fixture.base.sourceEpoch + 2, combined.sourceEpoch)
        assertEquals(writerFiles.keys, combined.manifest.delta.map { it.path }.toSet())
        assertEquals(request.clientId, combined.manifest.clientId)
        assertEquals(request.sessionId, combined.manifest.sessionId)
        assertEquals(request.patchId, combined.manifest.patchId)
        assertEquals(fixture.baseHead.acceptedPatchCount + 2, peer.currentHead(identity).acceptedPatchCount)
        assertContents(peer, identity, fixture.base, baseFiles)
        assertContents(peer, identity, interposed, baseFiles + peerFiles)
        assertContents(peer, identity, combined, baseFiles + peerFiles + writerFiles)
        assertEquals(fixture.base.manifest.entries.single { it.path == CONSUMER },
            combined.manifest.entries.single { it.path == CONSUMER }, "An unchanged shared-API consumer remains in the combined input")
        assertStale(peer, identity, fixture.base, combined)
        assertStale(peer, identity, interposed, combined)
        assertCurrentSnapshotOnly(peer, identity, combined)

        // A producer's old exact receipt is history, not a way to rewind the integrated head.
        val beforeReplay = peer.currentHead(identity)
        val oldReplay = peer.sealOverlay(peerRequest)
        assertTrue(oldReplay.replayed)
        assertEquals(interposed.manifest, oldReplay.manifest)
        assertEquals(beforeReplay, peer.currentHead(identity))
        assertStale(peer, identity, oldReplay, combined)
        val finalReplay = peer.sealOverlay(request)
        assertTrue(finalReplay.replayed)
        assertEquals(combined.manifest, finalReplay.manifest)
        assertEquals(beforeReplay, peer.currentHead(identity))
        assertCurrentSnapshotOnly(peer, identity, finalReplay)
    }

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun strictHeadRejectsDisjointPeerAdmissionAfterSuccessfulPreflightWithoutReceipt() =
        rejectedInterleaving(CommitMode.STRICT_HEAD, writerFiles, writerFiles.keys.map { SnapshotPath(path = it) })

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun overlappingRebaseRejectsEntireBatchAfterSuccessfulPreflightWithoutReceipt() =
        rejectedInterleaving(CommitMode.REBASE_DISJOINT, linkedMapOf(
            API to "package shared\nfun apiValue() = 99\n",
            RIGHT to "package right\nfun rightValue() = 99\n",
        ), listOf(SnapshotPath(path = API)))

    private fun rejectedInterleaving(mode: CommitMode, requestedFiles: Map<String, String>, conflicts: List<SnapshotPath>) {
        val fixture = seed(temporary.resolve("state"))
        val peer = SnapshotStore(fixture.store.root, identity.ownerId, limits)
        val peerRequest = overlay(fixture, "left", "left-patch", peerFiles)
        val request = overlay(fixture, "right", "right-patch", requestedFiles, mode)
        var peerCommit: SnapshotCommit? = null
        var peerHead: WorktreeState? = null
        val schedule = FirstBlobSchedule {
            assertEquals(fixture.baseHead, peer.currentHead(identity))
            peerCommit = peer.sealOverlay(peerRequest)
            peerHead = peer.currentHead(identity)
        }
        val writer = instrumented(fixture, schedule)
        val failure = assertFailsWith<SnapshotConflict> { writer.sealOverlay(request) }
        val interposed = assertNotNull(peerCommit)
        val installed = assertNotNull(peerHead)
        assertEquals(1, schedule.firings, "The first validation must really reach unlocked blob ingestion")
        assertEquals(conflicts, failure.paths)
        assertEquals(listOf("blob_installed", "blob_installed"), schedule.points,
            "The losing writer must never reach its manifest or state-install boundaries")
        assertEquals(installed, peer.currentHead(identity), "No head, epoch, lease or receipt-count change may leak from rejection")
        assertEquals(fixture.baseHead.acceptedPatchCount + 1, installed.acceptedPatchCount)
        assertContents(peer, identity, fixture.base, baseFiles)
        assertContents(peer, identity, interposed, baseFiles + peerFiles)
        assertCurrentSnapshotOnly(peer, identity, interposed)
        assertStale(peer, identity, fixture.base, interposed)

        // Ingested CAS blobs are not admitted inputs. Probe only digests absent from peer/base.
        val admittedDigests = (fixture.base.manifest.entries + interposed.manifest.entries).mapNotNull { it.digest }.toSet()
        for (put in request.changes.filterIsInstance<SnapshotChange.PutFile>()) {
            assertFalse(put.content.digest in admittedDigests)
            assertEquals("permission_denied", assertFailsWith<SnapshotException> {
                peer.readBlob(identity, interposed.snapshotId, put.content.digest)
            }.code)
        }
        val repeatFailure = assertFailsWith<SnapshotConflict> { writer.sealOverlay(request) }
        assertEquals(conflicts, repeatFailure.paths)
        assertEquals(listOf("blob_installed", "blob_installed"), schedule.points, "Stale retry fails before another ingestion")
        assertEquals(installed, peer.currentHead(identity))

        // Same client/session/patch and bytes, corrected base only: a phantom receipt would
        // cause idempotency_conflict instead of a fresh admission. No private ledger is read.
        val corrected = request.copy(baseSnapshotId = interposed.snapshotId, expectedHeadRevision = interposed.headRevision)
        assertEquals(Triple(request.clientId, request.sessionId, request.patchId),
            Triple(corrected.clientId, corrected.sessionId, corrected.patchId))
        val accepted = peer.sealOverlay(corrected)
        assertFalse(accepted.replayed)
        assertEquals(interposed.snapshotId, accepted.manifest.parentSnapshotId)
        assertEquals(interposed.snapshotId, accepted.manifest.producerBaseSnapshotId)
        assertEquals(interposed.headRevision + 1, accepted.headRevision)
        assertEquals(installed.acceptedPatchCount + 1, peer.currentHead(identity).acceptedPatchCount)
        assertContents(peer, identity, accepted, baseFiles + peerFiles + requestedFiles)
        assertContents(peer, identity, interposed, baseFiles + peerFiles)
        assertStale(peer, identity, interposed, accepted)
        assertCurrentSnapshotOnly(peer, identity, accepted)
    }

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun otherWorktreeSamePathAdmissionDoesNotInvalidateLocalStrictHeadOrSupplyItsFinalSnapshot() =
        isolatedInterleaving(otherOwner = false)

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun otherOwnerSamePathAdmissionDoesNotInvalidateLocalStrictHeadOrSupplyItsFinalSnapshot() =
        isolatedInterleaving(otherOwner = true)

    private fun isolatedInterleaving(otherOwner: Boolean) {
        val fixture = seed(temporary.resolve("state"))
        val foreignIdentity = if (otherOwner) identity.copy(ownerId = "other-owner") else identity.copy(worktreeId = "other-tree")
        val foreign = seed(if (otherOwner) temporary.resolve("other-owner-state") else fixture.store.root, foreignIdentity)
        val request = overlay(fixture, "same-client", "same-patch", writerFiles)
        val foreignRequest = overlay(foreign, "same-client", "same-patch", writerFiles)
        var foreignCommit: SnapshotCommit? = null
        val schedule = FirstBlobSchedule {
            assertEquals(fixture.baseHead, fixture.store.currentHead(identity))
            foreignCommit = foreign.store.sealOverlay(foreignRequest)
            assertEquals(fixture.baseHead, fixture.store.currentHead(identity), "A foreign namespace cannot move this worktree head")
        }
        val local = instrumented(fixture, schedule).sealOverlay(request)
        val interposed = assertNotNull(foreignCommit)
        assertEquals(1, schedule.firings)
        assertEquals(listOf("blob_installed", "blob_installed", "manifest_installed", "before_state_install", "after_state_install"), schedule.points)
        assertFalse(local.replayed)
        assertEquals(fixture.base.snapshotId, local.manifest.parentSnapshotId)
        assertEquals(fixture.base.snapshotId, local.manifest.producerBaseSnapshotId)
        assertEquals(foreign.base.snapshotId, interposed.manifest.parentSnapshotId)
        assertEquals(fixture.base.headRevision + 1, local.headRevision)
        assertEquals(fixture.baseHead.acceptedPatchCount + 1, fixture.store.currentHead(identity).acceptedPatchCount)
        assertContents(fixture.store, identity, local, baseFiles + writerFiles)
        assertContents(foreign.store, foreignIdentity, interposed, baseFiles + writerFiles)
        assertEquals(local.manifest.treeDigest, interposed.manifest.treeDigest, "Identical complete bytes are a non-vacuous identity control")
        assertNotEquals(local.snapshotId, interposed.snapshotId)
        var foreignCallbacks = 0
        assertEquals("snapshot_not_admitted", assertFailsWith<SnapshotException> {
            fixture.store.withVerifiedSnapshot(identity, interposed.snapshotId) { foreignCallbacks++ }
        }.code)
        assertEquals(0, foreignCallbacks)
        if (otherOwner) {
            assertEquals("permission_denied", assertFailsWith<SnapshotException> {
                fixture.store.withVerifiedSnapshot(foreignIdentity, interposed.snapshotId) { foreignCallbacks++ }
            }.code)
            assertEquals(0, foreignCallbacks)
        }
        assertCurrentSnapshotOnly(fixture.store, identity, local)
        assertCurrentSnapshotOnly(foreign.store, foreignIdentity, interposed)
    }

    private companion object {
        const val API = "shared/Api.kt"
        const val LEFT = "module-left/Left.kt"
        const val RIGHT = "module-right/Right.kt"
        const val RIGHT_CONFIG = "module-right/options.txt"
        const val CONSUMER = "consumer/Use.kt"
    }
}

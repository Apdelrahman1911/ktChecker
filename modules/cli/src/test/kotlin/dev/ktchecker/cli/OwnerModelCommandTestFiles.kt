package dev.ktchecker.cli

import dev.ktchecker.kernel.ClientBinding
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import dev.ktchecker.snapshot.SnapshotCommit
import dev.ktchecker.snapshot.SnapshotFaultInjector
import dev.ktchecker.snapshot.SnapshotIdentity
import dev.ktchecker.snapshot.SnapshotStore
import java.nio.file.Path
import java.util.Base64

/** Source/data-only command fixtures: real immutable SnapshotStore, NO compiler/export/host process. */
internal val commandTestBinding = ClientBinding("owner-unit", "workspace-unit", "worktree-unit", "client-unit",
    "lineage-unit", sessionId = "session-unit")
internal fun commandTestFile(path: String, bytes: ByteArray = "NON-EXECUTABLE source DATA\n".toByteArray(), executable: Boolean = false) =
    OwnerModelPatchFile("project", path, Base64.getEncoder().encodeToString(bytes), executable)
internal fun commandTestPatch(previous: SnapshotCommit? = null, files: List<OwnerModelPatchFile> = listOf(commandTestFile("Main.kt")),
    name: String = "patch-${(previous?.headRevision ?: 0L) + 1L}") = OwnerModelCompletedPatch(
    name, "task-unit", (previous?.headRevision ?: 0L) + 1L, commandTestBinding, previous?.snapshotId,
    previous?.headRevision ?: 0L, listOf(OwnerModelPatchRoot("project", "")), files)
internal fun commandTestClock(scope: OwnerModelClockScope? = null) = OwnerModelCommandClock(
    OwnedApplicationDeadline.afterMillis(OWNER_MODEL_ORIGINAL_MILLIS), scope)

/** A DENIAL fence only. Never a mocked monotonic clock, permit, model or worker receipt. */
internal class CommandTestPhase(override val originalMillis: Long) : OwnerModelPhaseFence {
    var checkpoints = 0
    var fail = false
    override fun checkpoint() { checkpoints++; check(!fail) { "SYNTHETIC phase denial" } }
}

internal class CommandSnapshotFixture(root: Path, faults: SnapshotFaultInjector = SnapshotFaultInjector.NONE) {
    val identity = SnapshotIdentity(commandTestBinding.ownerId, commandTestBinding.workspaceId, commandTestBinding.worktreeId)
    val store = SnapshotStore(root.resolve("snapshots"), commandTestBinding.ownerId, faults = faults)
    init { store.registerWorktree(identity) }
    fun freeze(request: OwnerModelCompletedPatch) = OwnerModelFrozenPatch.capture(request, commandTestBinding) { }
    fun capture(request: OwnerModelCompletedPatch, previous: SnapshotCommit? = null): Pair<OwnerModelSnapshotLineage, SnapshotCommit> {
        val holder = OwnerModelSnapshotLineage(freeze(request), previous)
        return holder to holder.capture(store) { }
    }
}

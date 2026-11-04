package dev.ktchecker.snapshot

import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.SnapshotFile
import kotlinx.serialization.Serializable
import java.nio.file.Path

/** Identity is supplied by an authenticated caller, never inferred from a directory or Git HEAD. */
@Serializable
data class SnapshotIdentity(val ownerId: String, val workspaceId: String, val worktreeId: String)

/** Roots are explicit inputs. mountPath is relative to the isolated materialization directory. */
@Serializable
data class SnapshotRoot(val id: String = "project", val mountPath: String = "", val livePath: String? = null)

@Serializable
data class SnapshotPath(val rootId: String = "project", val path: String)

/** Caller bytes are copied at construction and never exposed. Do not mutate bytes during construction. */
class ImmutableBlob private constructor(private val content: ByteArray) {
    val digest: String = Digests.sha256(content)
    val size: Long get() = content.size.toLong()
    internal fun copyBytes(): ByteArray = content.copyOf()

    companion object {
        fun copyOf(bytes: ByteArray, expectedDigest: String? = null): ImmutableBlob {
            val blob = ImmutableBlob(bytes.copyOf())
            if (expectedDigest != null && blob.digest != expectedDigest) {
                throw SnapshotException("blob_digest_mismatch", "Producer content differs from its declared digest")
            }
            return blob
        }

        fun utf8(text: String): ImmutableBlob = copyOf(text.toByteArray(Charsets.UTF_8))
    }
}

sealed interface SnapshotChange {
    data class PutFile(val path: SnapshotPath, val content: ImmutableBlob, val executable: Boolean = false) : SnapshotChange
    data class Delete(val path: SnapshotPath) : SnapshotChange
    /** Rename is an atomic delete/add, not a claim that the diagnostic subject is unchanged. */
    data class Rename(val from: SnapshotPath, val to: SnapshotPath) : SnapshotChange
    /** Only relative links to captured regular files within the same root are supported. */
    data class PutSymlink(val path: SnapshotPath, val target: String) : SnapshotChange
}

@Serializable
enum class CommitMode { STRICT_HEAD, REBASE_DISJOINT }

@Serializable
data class CaptureExclusion(val rootId: String, val path: String, val reason: String)

/** Completeness refers only to these declared inputs, never automatically to a complete project model. */
@Serializable
data class SnapshotScope(
    val description: String = "declared producer inputs",
    val exclusions: List<CaptureExclusion> = emptyList(),
    val unknownInputs: List<String> = emptyList(),
)

data class OverlayRequest(
    val identity: SnapshotIdentity,
    val clientId: String,
    val sessionId: String,
    val patchId: String,
    val baseSnapshotId: String?,
    val expectedHeadRevision: Long,
    val changes: List<SnapshotChange>,
    val mode: CommitMode = CommitMode.STRICT_HEAD,
    val scope: SnapshotScope = SnapshotScope(),
)

@Serializable
enum class SnapshotEntryKind { FILE, SYMLINK }

@Serializable
data class SnapshotEntry(
    val rootId: String,
    val path: String,
    val kind: SnapshotEntryKind = SnapshotEntryKind.FILE,
    val digest: String? = null,
    val size: Long = 0,
    val executable: Boolean = false,
    val symlinkTarget: String? = null,
)

@Serializable
data class SnapshotDelta(
    val rootId: String,
    val path: String,
    val operation: String,
    val beforeDigest: String? = null,
    val afterDigest: String? = null,
)

@Serializable
enum class CaptureKind { IMMUTABLE_OVERLAY, COORDINATED_CAPTURE, BEST_EFFORT }

@Serializable
data class CaptureProof(
    val kind: CaptureKind,
    val sourceEpoch: Long,
    val allWritersCooperate: Boolean = false,
    val leaseFence: Long? = null,
    val uncertainty: List<String> = emptyList(),
) {
    val coherent: Boolean get() = kind != CaptureKind.BEST_EFFORT
    val matchedLiveAtCapture: Boolean get() = kind == CaptureKind.COORDINATED_CAPTURE && allWritersCooperate
}

/** The digest covers every field other than itself. treeDigest identifies bytes/paths/modes/roots only. */
@Serializable
data class StoredSnapshotManifest(
    val snapshotId: String,
    val identity: SnapshotIdentity,
    val roots: List<SnapshotRoot>,
    val entries: List<SnapshotEntry>,
    val treeDigest: String,
    val parentSnapshotId: String?,
    val producerBaseSnapshotId: String?,
    val clientId: String,
    val sessionId: String,
    val patchId: String,
    val headRevision: Long,
    val capture: CaptureProof,
    val scope: SnapshotScope,
    val delta: List<SnapshotDelta>,
    val schemaVersion: Int = 1,
) {
    fun logicalPath(entry: SnapshotEntry): String {
        val mount = roots.single { it.id == entry.rootId }.mountPath
        return if (mount.isEmpty()) entry.path else "$mount/${entry.path}"
    }

    /** Symlink locations use the captured target's byte digest while the snapshot binds link semantics. */
    fun toContractManifest(): dev.ktchecker.contracts.SnapshotManifest {
        val byPath = entries.associateBy { SnapshotPath(it.rootId, it.path) }
        fun file(entry: SnapshotEntry): SnapshotEntry {
            var current = entry
            val seen = mutableSetOf<SnapshotPath>()
            while (current.kind == SnapshotEntryKind.SYMLINK) {
                if (!seen.add(SnapshotPath(current.rootId, current.path))) {
                    throw SnapshotException("symlink_cycle", "Snapshot contains a symlink cycle")
                }
                val target = SnapshotPaths.linkTarget(current.path, requireNotNull(current.symlinkTarget))
                current = byPath[SnapshotPath(current.rootId, target)]
                    ?: throw SnapshotException("symlink_target_missing", "Snapshot symlink target is absent")
            }
            return current
        }
        return dev.ktchecker.contracts.SnapshotManifest(
            snapshotId = snapshotId,
            workspaceId = identity.workspaceId,
            worktreeId = identity.worktreeId,
            files = entries.map { entry ->
                val bytes = file(entry)
                SnapshotFile(logicalPath(entry), requireNotNull(bytes.digest), bytes.size)
            }.sortedBy { it.path },
            ownerId = identity.ownerId,
            parentSnapshotId = parentSnapshotId,
            captureKind = capture.kind.name.lowercase(),
            declaredRoots = roots.map { it.id },
            scopeExclusions = scope.exclusions.map { "${it.rootId}:${it.path}: ${it.reason}" },
            unknownInputs = scope.unknownInputs,
        )
    }
}

@Serializable
enum class LiveState { UNVERIFIED, EDITING, MATCHED_AT_CAPTURE }

@Serializable
data class EditLease(
    val identity: SnapshotIdentity,
    val clientId: String,
    val sessionId: String,
    val token: String,
    val fence: Long,
    val headRevision: Long,
    val expiresAtMillis: Long,
    val phase: String = "editing",
)

@Serializable
data class WorktreeState(
    val identity: SnapshotIdentity,
    val roots: List<SnapshotRoot>,
    val headRevision: Long,
    val headSnapshotId: String?,
    val sourceEpoch: Long,
    val liveState: LiveState,
    val activeLease: EditLease?,
    val acceptedPatchCount: Int,
)

/** Snapshot-only evidence under a held fence; never a certificate of imported project coverage. */
@Serializable
data class SnapshotFenceCheck(
    val valid: Boolean,
    val reason: String?,
    val head: WorktreeState,
    val expectedSnapshotId: String,
    val expectedSourceEpoch: Long,
    val coherent: Boolean,
    val liveMatchCertified: Boolean,
    val scope: SnapshotScope,
)

@Serializable
data class SnapshotCommit(
    val manifest: StoredSnapshotManifest,
    val headRevision: Long,
    val sourceEpoch: Long,
    val replayed: Boolean = false,
) {
    val snapshotId: String get() = manifest.snapshotId
    fun toContractManifest(): dev.ktchecker.contracts.SnapshotManifest = manifest.toContractManifest()
}

data class LiveCaptureRequest(
    val identity: SnapshotIdentity,
    val clientId: String,
    val sessionId: String,
    val patchId: String,
    val expectedHeadRevision: Long,
    val scope: SnapshotScope = SnapshotScope(description = "declared registered live roots"),
)

data class MaterializedSnapshot(val root: Path, val manifest: StoredSnapshotManifest)

data class MaterializationVerification(val valid: Boolean, val violations: List<String>)

data class SnapshotLimits(
    val maxBlobBytes: Long = 16L * 1024 * 1024,
    val maxSnapshotBytes: Long = 256L * 1024 * 1024,
    val maxStoreBytes: Long = 1024L * 1024 * 1024,
    val maxFiles: Int = 50_000,
    val maxPatchesPerWorktree: Int = 4_096,
    val maxTrackedPaths: Int = 100_000,
    val maxWorktrees: Int = 128,
    val maxMetadataBytes: Long = 16L * 1024 * 1024,
    val maxLeaseMillis: Long = 120_000,
    val maxCaptureMillis: Long = 30_000,
    val lockTimeoutMillis: Long = 10_000,
) {
    init {
        require(maxBlobBytes > 0 && maxBlobBytes <= Int.MAX_VALUE)
        require(maxSnapshotBytes > 0 && maxStoreBytes > 0 && maxMetadataBytes > 0)
        require(maxFiles > 0 && maxPatchesPerWorktree > 0 && maxTrackedPaths > 0 && maxWorktrees > 0)
        require(maxLeaseMillis > 0 && maxCaptureMillis > 0 && lockTimeoutMillis > 0)
    }
}

open class SnapshotException(val code: String, message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class SnapshotConflict(val paths: List<SnapshotPath>, message: String) :
    SnapshotException("snapshot_conflict", message)

/** Fault injection happens only at named transaction boundaries, never fakes successful durability. */
fun interface SnapshotFaultInjector {
    fun at(point: String)
    companion object { val NONE = SnapshotFaultInjector { } }
}

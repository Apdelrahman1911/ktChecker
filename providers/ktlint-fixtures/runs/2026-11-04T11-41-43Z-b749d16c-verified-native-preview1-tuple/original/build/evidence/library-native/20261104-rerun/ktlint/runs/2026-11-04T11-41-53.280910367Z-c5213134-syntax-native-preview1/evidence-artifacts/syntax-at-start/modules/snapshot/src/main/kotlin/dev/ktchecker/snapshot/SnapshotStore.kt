package dev.ktchecker.snapshot

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Clock
import java.util.Locale
import java.util.UUID

/**
 * Per-user immutable source CAS and short, per-worktree metadata transactions.
 *
 * This is not an authentication endpoint. Its fixed owner must come from the trusted broker; calls
 * with another owner are rejected and the on-disk store is owner-private. Review never mutates the
 * live roots. A producer overlay defines exact submitted bytes, not a claim about a mutable checkout.
 */
class SnapshotStore(
    directory: Path,
    val ownerId: String,
    val limits: SnapshotLimits = SnapshotLimits(),
    private val clock: Clock = Clock.systemUTC(),
    private val faults: SnapshotFaultInjector = SnapshotFaultInjector.NONE,
) {
    private val requestedRoot = directory.toAbsolutePath().normalize()
    private val files: StoreFiles
    val root: Path

    /** False on platforms that cannot fsync directories: process-crash, not power-loss qualification. */
    val directorySyncSupported: Boolean get() = files.directorySyncSupported

    init {
        SnapshotPaths.identifier(ownerId, "ownerId")
        StoreFiles(requestedRoot, limits).directory(requestedRoot)
        root = requestedRoot.toRealPath()
        // Resolve legitimate ancestor aliases (notably /var on macOS) before choosing lock keys.
        // The store directory itself must be a real directory, as checked above with NOFOLLOW.
        files = StoreFiles(root, limits)
        files.probeDirectorySync()
        files.locked(root.resolve("owner.lock")) {
            val ownerFile = root.resolve("owner.json")
            if (Files.exists(ownerFile, NOFOLLOW_LINKS)) {
                val owner: StoreOwner = readDocument(ownerFile)
                if (owner.ownerId != ownerId) fail("permission_denied", "Snapshot store belongs to a different owner")
            } else {
                writeDocument(ownerFile, StoreOwner(ownerId))
            }
        }
    }

    fun registerWorktree(identity: SnapshotIdentity, roots: List<SnapshotRoot> = listOf(SnapshotRoot())): WorktreeState {
        authorize(identity)
        val normalized = normalizeRoots(roots)
        return files.locked(root.resolve("registration.lock")) {
            val registryPath = root.resolve("registrations.json")
            val registry = if (Files.exists(registryPath, NOFOLLOW_LINKS)) readDocument<RegistrationIndex>(registryPath)
            else RegistrationIndex()
            val existing = registry.registrations.find { it.identity == identity }
            if (existing != null && existing.roots != normalized) {
                fail("registration_conflict", "A worktree registration cannot silently change its captured roots")
            }
            if (existing == null) {
                if (registry.registrations.size >= limits.maxWorktrees) fail("worktree_limit", "Registered worktree limit reached")
                val physical = normalized.mapNotNull { it.livePath?.let(Path::of) }
                val aliases = registry.registrations.flatMap { it.roots }.mapNotNull { it.livePath?.let(Path::of) }
                if (physical.any { path -> aliases.any { physicalRootsOverlap(path, it) } }) {
                    fail("worktree_alias", "Overlapping physical roots must share one worktree edit fence")
                }
                // Reserve physical roots before installing state. A crash leaves a retryable reservation,
                // never two independent fences for the same live worktree.
                writeDocument(registryPath, registry.copy(registrations = registry.registrations + Registration(identity, normalized)), true)
            }
            withStateLock(identity, allowCreate = true) {
                val stateFile = statePath(identity)
                if (Files.exists(stateFile, NOFOLLOW_LINKS)) {
                    val state = readState(identity)
                    if (state.roots != normalized) fail("store_corrupt", "Registration and worktree roots disagree")
                    state.publicState()
                } else {
                    val state = PersistedWorktree(identity = identity, roots = normalized)
                    writeState(state)
                    state.publicState()
                }
            }
        }
    }

    fun currentHead(identity: SnapshotIdentity): WorktreeState = withCurrentHead(identity) { it }

    /**
     * Cross-process fence for a short kernel/config/delivery cut or guarded final action.
     * The callback must not run a provider/build, mutate source, or call another snapshot mutation.
     * A live MATCHED_AT_CAPTURE assertion is conditional on continuing all-writer participation.
     */
    fun <T> withCurrentHead(identity: SnapshotIdentity, block: (WorktreeState) -> T): T {
        authorize(identity)
        return withStateLock(identity) { block(readState(identity).publicState()) }
    }

    /**
     * Snapshot half of a final-action fence. The callback runs under the same cross-process lock and
     * must additionally enforce kernel lineage/coverage, configuration and delivery cuts. Even a
     * valid no-omissions check is not proof that a real project model's input inventory is complete.
     * Omission-free declared scope is conservative by default; a trusted narrower scope can opt out.
     */
    fun <T> withVerifiedSnapshot(
        identity: SnapshotIdentity,
        expectedSnapshotId: String,
        requireLiveMatch: Boolean = false,
        requireNoOmissions: Boolean = true,
        block: (SnapshotFenceCheck) -> T,
    ): T {
        authorize(identity)
        return withStateLock(identity) {
            val state = readState(identity)
            if (state.receipts.values.none { it.snapshotId == expectedSnapshotId }) {
                fail("snapshot_not_admitted", "Snapshot reference is not admitted for this worktree")
            }
            val expected = loadManifest(identity, expectedSnapshotId)
            val liveMatch = expected.capture.matchedLiveAtCapture && state.liveState == LiveState.MATCHED_AT_CAPTURE &&
                state.activeLease == null && state.sourceEpoch == expected.capture.sourceEpoch && state.headSnapshotId == expectedSnapshotId
            val reason = when {
                state.headSnapshotId != expectedSnapshotId -> "stale_snapshot"
                state.sourceEpoch != expected.capture.sourceEpoch -> "source_epoch_changed"
                state.activeLease != null -> "worktree_editing"
                !expected.capture.coherent -> "snapshot_not_coherent"
                state.roots != expected.roots -> "snapshot_roots_changed"
                requireNoOmissions && expected.scope.unknownInputs.isNotEmpty() -> "snapshot_inputs_unknown"
                requireNoOmissions && expected.scope.exclusions.isNotEmpty() -> "snapshot_inputs_excluded"
                requireLiveMatch && !liveMatch -> "live_snapshot_unverified"
                else -> null
            }
            block(SnapshotFenceCheck(reason == null, reason, state.publicState(), expectedSnapshotId,
                expected.capture.sourceEpoch, expected.capture.coherent, liveMatch, expected.scope))
        }
    }

    fun sealOverlay(request: OverlayRequest): SnapshotCommit {
        authorize(request.identity)
        validateProducer(request.clientId, request.sessionId, request.patchId)
        val immutable = request.copy(changes = request.changes.toList(), scope = normalizeScope(request.scope))
        val digest = overlayRequestDigest(immutable)
        val early = withStateLock(request.identity) {
            val state = readState(request.identity)
            replay(state, request.clientId, request.sessionId, request.patchId, digest) ?: run { prepareOverlay(state, immutable); null }
        }
        if (early != null) return early
        // Blob ingestion does not hold the worktree commit/capture fence.
        immutable.changes.filterIsInstance<SnapshotChange.PutFile>().forEach { putBlob(it.content) }
        return withStateLock(request.identity) {
            val state = readState(request.identity)
            replay(state, request.clientId, request.sessionId, request.patchId, digest) ?: run {
                val prepared = prepareOverlay(state, immutable)
                commit(
                    state = state,
                    clientId = request.clientId,
                    sessionId = request.sessionId,
                    patchId = request.patchId,
                    requestDigest = digest,
                    producerBase = request.baseSnapshotId,
                    entries = prepared.entries,
                    scope = prepared.scope,
                    kind = CaptureKind.IMMUTABLE_OVERLAY,
                    touched = touchedPaths(immutable.changes).map(SnapshotPaths::key).toSet(),
                )
            }
        }
    }

    /** Beginning an edit invalidates live-current assertions immediately, even before a byte changes. */
    fun beginCooperativeEdit(
        identity: SnapshotIdentity,
        clientId: String,
        sessionId: String,
        expectedHeadRevision: Long,
        allWritersCooperate: Boolean,
        leaseMillis: Long = limits.maxLeaseMillis,
    ): EditLease {
        authorize(identity)
        validateProducer(clientId, sessionId, "edit")
        if (!allWritersCooperate) fail("writer_cooperation_required", "Exact live capture requires every relevant writer to participate")
        requireLeaseDuration(leaseMillis)
        return withStateLock(identity) {
            val state = readState(identity)
            requireHead(state, expectedHeadRevision)
            requireNoLiveEditor(state)
            if (state.roots.any { it.livePath == null }) fail("live_root_unavailable", "Every root needs a registered live path for coordinated capture")
            val lease = EditLease(identity, clientId, sessionId, UUID.randomUUID().toString(),
                state.sourceEpoch + 1, state.headRevision, clock.millis() + leaseMillis)
            writeState(state.copy(sourceEpoch = lease.fence, liveState = LiveState.EDITING, activeLease = lease))
            lease
        }
    }

    fun renewEdit(lease: EditLease, leaseMillis: Long = limits.maxLeaseMillis): EditLease {
        authorize(lease.identity)
        requireLeaseDuration(leaseMillis)
        return withStateLock(lease.identity) {
            val state = readState(lease.identity)
            val active = requireLease(state, lease)
            if (active.phase != "editing") fail("capture_in_progress", "Cannot renew a lease after capture has begun")
            val renewed = active.copy(expiresAtMillis = clock.millis() + leaseMillis)
            writeState(state.copy(activeLease = renewed))
            renewed
        }
    }

    /** Abandonment never marks changed bytes reviewed. A subsequent exact capture is still required. */
    fun abandonEdit(lease: EditLease) {
        authorize(lease.identity)
        withStateLock(lease.identity) {
            val state = readState(lease.identity)
            if (state.activeLease?.token != lease.token || state.activeLease.fence != lease.fence ||
                state.activeLease.clientId != lease.clientId || state.activeLease.sessionId != lease.sessionId) {
                fail("stale_edit_lease", "Edit lease was replaced or already completed")
            }
            writeState(state.copy(sourceEpoch = state.sourceEpoch + 1, liveState = LiveState.UNVERIFIED, activeLease = null))
        }
    }

    /** Caller has finished writing and must remain paused through this call. Scanning is outside the metadata lock. */
    fun captureCooperative(lease: EditLease, request: LiveCaptureRequest): SnapshotCommit {
        authorize(request.identity)
        validateProducer(request.clientId, request.sessionId, request.patchId)
        if (lease.identity != request.identity || lease.clientId != request.clientId || lease.sessionId != request.sessionId) {
            fail("permission_denied", "Capture lease is bound to a different producer")
        }
        val normalized = request.copy(scope = normalizeScope(request.scope))
        val digest = liveRequestDigest(normalized, CaptureKind.COORDINATED_CAPTURE, lease.token)
        var early: SnapshotCommit? = null
        val stateAtStart = withStateLock(request.identity) {
            val state = readState(request.identity)
            early = replay(state, request.clientId, request.sessionId, request.patchId, digest)
            if (early == null) {
                requireHead(state, request.expectedHeadRevision)
                val active = requireLease(state, lease)
                if (active.phase != "editing") fail("capture_in_progress", "This lease has already begun capture")
                val capturing = state.copy(activeLease = active.copy(phase = "capturing"))
                writeState(capturing)
                capturing
            } else state
        }
        early?.let { return it }
        val capture = LiveSnapshotCapture(limits, clock).capture(stateAtStart.roots, normalized.scope,
            minOf(requireNotNull(stateAtStart.activeLease).expiresAtMillis, clock.millis() + limits.maxCaptureMillis))
        capture.blobs.forEach(::putBlob)
        return withStateLock(request.identity) {
            val state = readState(request.identity)
            replay(state, request.clientId, request.sessionId, request.patchId, digest) ?: run {
                requireHead(state, request.expectedHeadRevision)
                requireLease(state, lease)
                commit(state, request.clientId, request.sessionId, request.patchId, digest,
                    stateAtStart.headSnapshotId, capture.entries, normalized.scope, CaptureKind.COORDINATED_CAPTURE,
                    leaseFence = lease.fence)
            }
        }
    }

    /** A stable double scan would still not prove coherence; this mode is always visibly inexact. */
    fun captureBestEffort(request: LiveCaptureRequest): SnapshotCommit {
        authorize(request.identity)
        validateProducer(request.clientId, request.sessionId, request.patchId)
        val normalized = request.copy(scope = normalizeScope(request.scope))
        val digest = liveRequestDigest(normalized, CaptureKind.BEST_EFFORT, null)
        var early: SnapshotCommit? = null
        val stateAtStart = withStateLock(request.identity) {
            val state = readState(request.identity)
            early = replay(state, request.clientId, request.sessionId, request.patchId, digest)
            if (early == null) {
                requireHead(state, request.expectedHeadRevision)
                requireNoLiveEditor(state)
            }
            state
        }
        early?.let { return it }
        val capture = LiveSnapshotCapture(limits, clock).capture(stateAtStart.roots, normalized.scope,
            clock.millis() + limits.maxCaptureMillis)
        capture.blobs.forEach(::putBlob)
        return withStateLock(request.identity) {
            val state = readState(request.identity)
            replay(state, request.clientId, request.sessionId, request.patchId, digest) ?: run {
                requireHead(state, request.expectedHeadRevision)
                requireNoLiveEditor(state)
                if (state.sourceEpoch != stateAtStart.sourceEpoch) fail("capture_epoch_changed", "A coordinated editor started while the capture was running")
                commit(state, request.clientId, request.sessionId, request.patchId, digest,
                    stateAtStart.headSnapshotId, capture.entries, normalized.scope, CaptureKind.BEST_EFFORT)
            }
        }
    }

    fun snapshot(identity: SnapshotIdentity, snapshotId: String): StoredSnapshotManifest {
        authorize(identity)
        return withStateLock(identity) {
            val state = readState(identity)
            if (state.receipts.values.none { it.snapshotId == snapshotId }) {
                fail("snapshot_not_admitted", "Snapshot reference is not admitted for this worktree")
            }
            loadManifest(identity, snapshotId)
        }
    }

    /** Blob access requires an authorized snapshot reference, not just knowledge of a source hash. */
    fun readBlob(identity: SnapshotIdentity, snapshotId: String, digest: String): ByteArray {
        val manifest = snapshot(identity, snapshotId)
        if (manifest.entries.none { it.digest == digest }) fail("permission_denied", "Blob is not an input of this snapshot")
        return readBlobVerified(digest)
    }

    fun materialize(identity: SnapshotIdentity, snapshotId: String, destination: Path, readOnly: Boolean = true): MaterializedSnapshot {
        val manifest = snapshot(identity, snapshotId)
        return SnapshotMaterializer(limits, ::readBlobVerified).materialize(manifest, destination, readOnly)
    }

    fun verifyMaterialization(materialized: MaterializedSnapshot): MaterializationVerification {
        authorize(materialized.manifest.identity)
        val expected = snapshot(materialized.manifest.identity, materialized.manifest.snapshotId)
        return SnapshotMaterializer(limits, ::readBlobVerified).verify(expected, materialized.root)
    }

    private fun prepareOverlay(state: PersistedWorktree, request: OverlayRequest): PreparedOverlay {
        ensureCapacity(state)
        requireNoLiveEditor(state)
        val base = if (request.baseSnapshotId == null) {
            if (request.expectedHeadRevision != 0L) fail("base_revision_mismatch", "A missing base requires initial revision zero")
            null
        } else {
            val receipt = state.receipts.values.find { it.snapshotId == request.baseSnapshotId }
                ?: fail("base_not_found", "The declared base is not an accepted snapshot of this worktree")
            if (receipt.headRevision != request.expectedHeadRevision) fail("base_revision_mismatch", "Base snapshot and declared revision disagree")
            loadManifest(state.identity, request.baseSnapshotId)
        }
        val touched = touchedPaths(request.changes)
        touched.forEach { SnapshotPaths.validate(it, state.roots) }
        if (touched.size != touched.distinct().size) fail("duplicate_change", "An edit batch may change each logical path at most once")
        if (request.mode == CommitMode.STRICT_HEAD) {
            if (request.expectedHeadRevision != state.headRevision || request.baseSnapshotId != state.headSnapshotId) {
                throw SnapshotConflict(touched, "Worktree head changed; rebase or supply the current base explicitly")
            }
        } else {
            // Keep tombstones and modification revisions, not just byte equality: A -> B -> A is a conflict.
            val later = state.pathRevisions.filterValues { it > request.expectedHeadRevision }.keys.map(SnapshotPaths::decodeKey)
            val conflicts = touched.filter { changed -> later.any { SnapshotPaths.overlapping(changed, it) } }
            if (conflicts.isNotEmpty()) throw SnapshotConflict(conflicts, "Another completed patch edited an overlapping path after this base")
            if (request.expectedHeadRevision > state.headRevision) fail("base_revision_mismatch", "Base revision is newer than the worktree head")
        }
        // Changed files cannot prove that an inherited omission disappeared. In a disjoint rebase,
        // preserve both the producer base and newly integrated head's omission evidence. A full
        // cooperative recapture is a separate operation with an independently declared input scope.
        val headManifest = state.headSnapshotId?.let { loadManifest(state.identity, it) }
        val inherited = listOfNotNull(base?.scope, headManifest?.scope) + request.scope
        val effectiveScope = normalizeScope(request.scope.copy(
            exclusions = inherited.flatMap { it.exclusions }.groupBy { it.rootId to it.path }.map { (path, omissions) ->
                CaptureExclusion(path.first, path.second, omissions.map { it.reason }.distinct().sorted().joinToString("\n"))
            },
            unknownInputs = inherited.flatMap { it.unknownInputs }.distinct(),
        ))
        validateScope(state.roots, effectiveScope)
        val baseEntries = base?.entries.orEmpty().associateBy(SnapshotPaths::key)
        val headEntries = headManifest?.entries.orEmpty()
        val entries = headEntries.associateBy(SnapshotPaths::key).toMutableMap()
        request.changes.forEach { change ->
            when (change) {
                is SnapshotChange.PutFile -> {
                    if (change.content.size > limits.maxBlobBytes) fail("blob_too_large", "Producer blob exceeds configured limit")
                    entries[SnapshotPaths.key(change.path)] = SnapshotEntry(change.path.rootId, change.path.path,
                        digest = change.content.digest, size = change.content.size, executable = change.executable)
                }
                is SnapshotChange.PutSymlink -> {
                    SnapshotPaths.linkTarget(change.path.path, change.target)
                    entries[SnapshotPaths.key(change.path)] = SnapshotEntry(change.path.rootId, change.path.path,
                        kind = SnapshotEntryKind.SYMLINK, symlinkTarget = change.target)
                }
                is SnapshotChange.Delete -> {
                    if (SnapshotPaths.key(change.path) !in baseEntries) fail("delete_missing", "A deletion must name an entry in its declared base")
                    entries.remove(SnapshotPaths.key(change.path))
                }
                is SnapshotChange.Rename -> {
                    val source = baseEntries[SnapshotPaths.key(change.from)]
                        ?: fail("rename_source_missing", "A rename must name an entry in its declared base")
                    if (SnapshotPaths.key(change.to) in baseEntries) fail("rename_target_exists", "Rename cannot silently overwrite another base entry")
                    entries.remove(SnapshotPaths.key(change.from))
                    entries[SnapshotPaths.key(change.to)] = source.copy(rootId = change.to.rootId, path = change.to.path)
                }
            }
        }
        val result = entries.values.sortedWith(entryComparator)
        SnapshotPaths.validateManifest(result, state.roots, limits)
        validateExclusions(result, effectiveScope)
        if ((state.pathRevisions.keys + touched.map(SnapshotPaths::key)).toSet().size > limits.maxTrackedPaths) {
            fail("path_history_limit", "Path conflict-history limit reached; explicit archival is required")
        }
        return PreparedOverlay(result, effectiveScope)
    }

    private fun commit(
        state: PersistedWorktree,
        clientId: String,
        sessionId: String,
        patchId: String,
        requestDigest: String,
        producerBase: String?,
        entries: List<SnapshotEntry>,
        scope: SnapshotScope,
        kind: CaptureKind,
        touched: Set<String>? = null,
        leaseFence: Long? = null,
    ): SnapshotCommit {
        ensureCapacity(state)
        validateScope(state.roots, scope)
        SnapshotPaths.validateManifest(entries, state.roots, limits)
        validateExclusions(entries, scope)
        val prior = state.headSnapshotId?.let { loadManifest(state.identity, it).entries }.orEmpty()
        val delta = diff(prior, entries)
        val touchedKeys = touched ?: delta.map { SnapshotPaths.key(SnapshotPath(it.rootId, it.path)) }.toSet()
        if ((state.pathRevisions.keys + touchedKeys).toSet().size > limits.maxTrackedPaths) {
            fail("path_history_limit", "Path conflict-history limit reached; explicit archival is required")
        }
        // Each CAS object was checksum-verified at immutable ingestion (or by an earlier commit).
        // Do not rehash a whole project under the commit fence. Materialization rechecks every hash
        // before a provider receives bytes; missing/corrupt objects can never produce successful work.
        entries.filter { it.kind == SnapshotEntryKind.FILE }.forEach {
            val path = objectPath("blobs", requireNotNull(it.digest))
            if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.size(path) != it.size) {
                fail("store_corrupt", "An immutable blob is absent or has an unexpected size")
            }
        }
        val revision = state.headRevision + 1
        val epoch = state.sourceEpoch + 1
        val proof = CaptureProof(kind, epoch, kind == CaptureKind.COORDINATED_CAPTURE, leaseFence,
            if (kind == CaptureKind.BEST_EFFORT) listOf("Nonparticipating writers may produce torn or ABA capture; not exact-current evidence") else emptyList())
        val descriptor = StoredSnapshotManifest("", state.identity, state.roots, entries,
            digest("tree-v1", TreeDescriptor(state.roots, entries)), state.headSnapshotId, producerBase,
            clientId, sessionId, patchId, revision, proof, scope, delta)
        val manifest = descriptor.copy(snapshotId = digest("manifest-v1", descriptor))
        val manifestPath = objectPath("manifests", manifest.snapshotId)
        writeDocument(manifestPath, manifest)
        faults.at("manifest_installed")
        val receipt = PatchReceipt(requestDigest, manifest.snapshotId, revision, epoch)
        val next = state.copy(headRevision = revision, headSnapshotId = manifest.snapshotId, sourceEpoch = epoch,
            liveState = if (kind == CaptureKind.COORDINATED_CAPTURE) LiveState.MATCHED_AT_CAPTURE else LiveState.UNVERIFIED,
            activeLease = null,
            receipts = state.receipts + (receiptKey(clientId, sessionId, patchId) to receipt),
            pathRevisions = state.pathRevisions + touchedKeys.associateWith { revision })
        faults.at("before_state_install")
        writeState(next)
        faults.at("after_state_install")
        return SnapshotCommit(manifest, revision, epoch)
    }

    private fun replay(state: PersistedWorktree, clientId: String, sessionId: String, patchId: String, requestDigest: String): SnapshotCommit? {
        val receipt = state.receipts[receiptKey(clientId, sessionId, patchId)] ?: return null
        if (receipt.requestDigest != requestDigest) fail("idempotency_conflict", "Patch ID was already admitted with different bytes or producer parameters")
        val manifest = loadManifest(state.identity, receipt.snapshotId)
        return SnapshotCommit(manifest, receipt.headRevision, receipt.sourceEpoch, replayed = true)
    }

    private fun receiptKey(clientId: String, sessionId: String, patchId: String): String =
        Digests.sha256("$clientId\u0000$sessionId\u0000$patchId")

    private fun putBlob(blob: ImmutableBlob) {
        if (blob.size > limits.maxBlobBytes) fail("blob_too_large", "Source blob exceeds configured limit")
        val path = objectPath("blobs", blob.digest)
        val created = files.writeBudgeted(path, blob.copyBytes())
        if (!created) readBlobVerified(blob.digest)
        faults.at("blob_installed")
    }

    private fun readBlobVerified(digest: String): ByteArray {
        val bytes = files.read(objectPath("blobs", digest), limits.maxBlobBytes)
        if (Digests.sha256(bytes) != digest) fail("store_corrupt", "Captured source blob failed its content checksum")
        return bytes
    }

    private fun loadManifest(identity: SnapshotIdentity, snapshotId: String): StoredSnapshotManifest {
        val manifest: StoredSnapshotManifest = readDocument(objectPath("manifests", snapshotId))
        if (manifest.identity != identity) fail("permission_denied", "Snapshot belongs to a different worktree")
        if (manifest.snapshotId != snapshotId || digest("manifest-v1", manifest.copy(snapshotId = "")) != snapshotId ||
            digest("tree-v1", TreeDescriptor(manifest.roots, manifest.entries)) != manifest.treeDigest) {
            fail("store_corrupt", "Snapshot manifest failed its content checksum")
        }
        SnapshotPaths.validateManifest(manifest.entries, manifest.roots, limits)
        return manifest
    }

    private fun overlayRequestDigest(request: OverlayRequest): String = digest("overlay-request-v1", OverlayDescriptor(
        request.identity, request.clientId, request.sessionId, request.patchId, request.baseSnapshotId,
        request.expectedHeadRevision, request.mode, request.scope, request.changes.map { change ->
            when (change) {
                is SnapshotChange.PutFile -> ChangeDescriptor("put", change.path, change.content.digest, change.content.size, change.executable)
                is SnapshotChange.Delete -> ChangeDescriptor("delete", change.path)
                is SnapshotChange.Rename -> ChangeDescriptor("rename", change.from, to = change.to)
                is SnapshotChange.PutSymlink -> ChangeDescriptor("symlink", change.path, target = change.target)
            }
        }.sortedWith(compareBy({ it.path.rootId }, { it.path.path }, { it.operation }))))

    private fun liveRequestDigest(request: LiveCaptureRequest, kind: CaptureKind, token: String?): String =
        digest("live-request-v1", LiveDescriptor(request.identity, request.clientId, request.sessionId, request.patchId,
            request.expectedHeadRevision, request.scope, kind, token))

    private fun normalizeRoots(roots: List<SnapshotRoot>): List<SnapshotRoot> {
        if (roots.isEmpty() || roots.size > 64) fail("invalid_roots", "A worktree requires between one and 64 approved roots")
        val normalized = roots.map { root ->
            SnapshotPaths.rootId(root.id)
            SnapshotPaths.relative(root.mountPath, allowEmpty = true)
            val live = root.livePath?.let {
                val path = Path.of(it).toAbsolutePath().normalize()
                if (!Files.isDirectory(path, NOFOLLOW_LINKS)) fail("live_root_unavailable", "Registered live root must be an existing real directory")
                path.toRealPath().toString()
            }
            root.copy(livePath = live)
        }.sortedBy { it.id }
        if (normalized.map { it.id.lowercase(Locale.ROOT) }.distinct().size != normalized.size ||
            normalized.map { it.mountPath.lowercase(Locale.ROOT) }.distinct().size != normalized.size) {
            fail("root_collision", "Root IDs and mount paths must be unique under portable case folding")
        }
        val live = normalized.mapNotNull { it.livePath?.let(Path::of) }
        live.forEachIndexed { index, path ->
            if (live.drop(index + 1).any { physicalRootsOverlap(path, it) }) {
                fail("root_overlap", "A physical input cannot be captured under overlapping registered roots")
            }
            if (physicalRootsOverlap(root, path)) fail("store_inside_capture", "Snapshot state and live inputs must not contain each other")
        }
        return normalized
    }

    private fun physicalRootsOverlap(first: Path, second: Path): Boolean {
        if (first.startsWith(second) || second.startsWith(first)) return true
        // A removed old worktree keeps its lexical reservation but cannot alias an unrelated live
        // inode. Its disappearance must not prevent registering other, independent projects.
        if (!Files.exists(first, NOFOLLOW_LINKS) || !Files.exists(second, NOFOLLOW_LINKS)) return false
        // toRealPath handles symlinks. isSameFile additionally covers case aliases and bind mounts
        // whose lexical paths differ; ancestor comparisons prevent independently fenced subroots.
        fun contains(root: Path, child: Path): Boolean {
            var path: Path? = child
            while (path != null) {
                if (Files.isSameFile(root, path)) return true
                path = path.parent
            }
            return false
        }
        return contains(first, second) || contains(second, first)
    }

    private fun normalizeScope(scope: SnapshotScope): SnapshotScope {
        if (scope.description.isBlank() || scope.description.length > 4096 || scope.unknownInputs.size > 4096 || scope.exclusions.size > 4096) {
            fail("invalid_scope", "Snapshot scope description or inventory exceeds configured bounds")
        }
        if (scope.unknownInputs.any { it.isBlank() || it.length > 4096 }) fail("invalid_scope", "Unknown input descriptions must be nonempty and bounded")
        return scope.copy(exclusions = scope.exclusions.sortedWith(compareBy({ it.rootId }, { it.path })),
            unknownInputs = scope.unknownInputs.distinct().sorted())
    }

    private fun validateScope(roots: List<SnapshotRoot>, scope: SnapshotScope) {
        scope.exclusions.forEach {
            SnapshotPaths.validate(SnapshotPath(it.rootId, it.path), roots)
            if (it.reason.isBlank() || it.reason.length > 4096) fail("invalid_exclusion", "Every exclusion requires a bounded explicit rationale")
        }
        if (scope.exclusions.map { it.rootId to it.path }.distinct().size != scope.exclusions.size) fail("duplicate_exclusion", "Scope contains duplicate exclusions")
    }

    private fun validateExclusions(entries: List<SnapshotEntry>, scope: SnapshotScope) {
        if (entries.any { entry -> scope.exclusions.any { entry.rootId == it.rootId && (entry.path == it.path || entry.path.startsWith(it.path + "/")) } }) {
            fail("exclusion_conflict", "A manifest cannot both include and exclude the same input")
        }
    }

    private fun requireLease(state: PersistedWorktree, lease: EditLease): EditLease {
        val active = state.activeLease
        if (active == null || active.token != lease.token || active.fence != lease.fence ||
            active.clientId != lease.clientId || active.sessionId != lease.sessionId ||
            active.expiresAtMillis <= clock.millis()) fail("stale_edit_lease", "Edit lease expired or was replaced")
        return active
    }

    private fun requireNoLiveEditor(state: PersistedWorktree) {
        if (state.activeLease != null && state.activeLease.expiresAtMillis > clock.millis()) {
            fail("worktree_editing", "A cooperative editor holds this live worktree; use an independent worktree or wait")
        }
    }

    private fun requireLeaseDuration(duration: Long) {
        if (duration <= 0 || duration > limits.maxLeaseMillis) fail("invalid_lease_duration", "Edit lease exceeds configured duration")
    }

    private fun requireHead(state: PersistedWorktree, expected: Long) {
        if (state.headRevision != expected) throw SnapshotConflict(emptyList(), "Worktree head revision no longer matches")
    }

    private fun ensureCapacity(state: PersistedWorktree) {
        if (state.receipts.size >= limits.maxPatchesPerWorktree) fail("patch_history_full", "Accepted patch history limit reached; obligations must be archived explicitly")
    }

    private fun touchedPaths(changes: List<SnapshotChange>): List<SnapshotPath> = changes.flatMap {
        when (it) {
            is SnapshotChange.PutFile -> listOf(it.path)
            is SnapshotChange.PutSymlink -> listOf(it.path)
            is SnapshotChange.Delete -> listOf(it.path)
            is SnapshotChange.Rename -> listOf(it.from, it.to)
        }
    }

    private fun diff(before: List<SnapshotEntry>, after: List<SnapshotEntry>): List<SnapshotDelta> {
        val previous = before.associateBy(SnapshotPaths::key)
        val current = after.associateBy(SnapshotPaths::key)
        return (previous.keys + current.keys).sorted().mapNotNull { key ->
            val old = previous[key]
            val new = current[key]
            if (old == new) null else {
                val path = SnapshotPaths.decodeKey(key)
                SnapshotDelta(path.rootId, path.path, when { old == null -> "added"; new == null -> "deleted"; else -> "modified" },
                    old?.let { digest("entry-v1", it) }, new?.let { digest("entry-v1", it) })
            }
        }
    }

    private fun authorize(identity: SnapshotIdentity) {
        if (identity.ownerId != ownerId) fail("permission_denied", "Snapshot owner is not authorized in this local store")
        SnapshotPaths.identifier(identity.workspaceId, "workspaceId")
        SnapshotPaths.identifier(identity.worktreeId, "worktreeId")
    }

    private fun validateProducer(clientId: String, sessionId: String, patchId: String) {
        SnapshotPaths.identifier(clientId, "clientId")
        SnapshotPaths.identifier(sessionId, "sessionId")
        SnapshotPaths.identifier(patchId, "patchId")
    }

    private fun worktreeDirectory(identity: SnapshotIdentity): Path = root.resolve("worktrees").resolve(
        Digests.sha256("${identity.workspaceId}\u0000${identity.worktreeId}").removePrefix("sha256:"))

    private fun statePath(identity: SnapshotIdentity): Path = worktreeDirectory(identity).resolve("state.json")
    private fun <T> withStateLock(identity: SnapshotIdentity, allowCreate: Boolean = false, block: () -> T): T {
        val directory = worktreeDirectory(identity)
        if (!allowCreate && !Files.isDirectory(directory, NOFOLLOW_LINKS)) {
            fail("worktree_not_registered", "Register this worktree before accessing its snapshot state")
        }
        return files.locked(directory.resolve("commit.lock"), block)
    }

    private fun readState(identity: SnapshotIdentity): PersistedWorktree {
        val path = statePath(identity)
        if (!Files.exists(path, NOFOLLOW_LINKS)) fail("worktree_not_registered", "Register this worktree before submitting patches")
        val state: PersistedWorktree = readDocument(path)
        if (state.identity != identity) fail("store_corrupt", "Worktree state identity differs from its storage namespace")
        return state
    }

    private fun writeState(state: PersistedWorktree) = writeDocument(statePath(state.identity), state, true)

    private fun objectPath(kind: String, digest: String): Path {
        if (!Digests.valid(digest)) fail("invalid_digest", "Snapshot reference must be a SHA-256 content identity")
        val hash = digest.removePrefix("sha256:")
        return root.resolve("objects").resolve(kind).resolve(hash.take(2)).resolve(hash.drop(2))
    }

    private inline fun <reified T> readDocument(path: Path): T {
        try {
            val envelope = ContractJson.decodeFromString<DocumentEnvelope>(files.read(path).toString(Charsets.UTF_8))
            if (envelope.schemaVersion != 1 || Digests.sha256(envelope.payload) != envelope.digest) {
                fail("store_corrupt", "Snapshot metadata checksum or schema is invalid")
            }
            return ContractJson.decodeFromString(envelope.payload)
        } catch (exception: SnapshotException) {
            throw exception
        } catch (exception: Exception) {
            throw SnapshotException("store_corrupt", "Cannot decode snapshot metadata", exception)
        }
    }

    private inline fun <reified T> writeDocument(path: Path, value: T, replace: Boolean = false) {
        val payload = Digests.canonical(ContractJson.encodeToJsonElement(value))
        val bytes = ContractJson.encodeToString(DocumentEnvelope(payload, Digests.sha256(payload))).toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > limits.maxMetadataBytes) fail("metadata_limit", "Snapshot metadata exceeds configured bound")
        if (!files.writeBudgeted(path, bytes, replace)) {
            if (!files.read(path).contentEquals(bytes)) fail("store_corrupt", "Immutable metadata address contains different content")
        }
    }

    private inline fun <reified T> digest(tag: String, value: T): String = Digests.sha256(
        "ktchecker:snapshot:$tag\u0000" + Digests.canonical(ContractJson.encodeToJsonElement(value)))

    private fun fail(code: String, message: String): Nothing = throw SnapshotException(code, message)

    companion object {
        private val entryComparator = compareBy<SnapshotEntry>({ it.rootId }, { it.path })
    }
}

@Serializable private data class DocumentEnvelope(val payload: String, val digest: String, val schemaVersion: Int = 1)
private data class PreparedOverlay(val entries: List<SnapshotEntry>, val scope: SnapshotScope)
@Serializable private data class StoreOwner(val ownerId: String)
@Serializable private data class Registration(val identity: SnapshotIdentity, val roots: List<SnapshotRoot>)
@Serializable private data class RegistrationIndex(val registrations: List<Registration> = emptyList())
@Serializable private data class TreeDescriptor(val roots: List<SnapshotRoot>, val entries: List<SnapshotEntry>)
@Serializable private data class PatchReceipt(val requestDigest: String, val snapshotId: String, val headRevision: Long, val sourceEpoch: Long)
@Serializable private data class PersistedWorktree(
    val identity: SnapshotIdentity,
    val roots: List<SnapshotRoot>,
    val headRevision: Long = 0,
    val headSnapshotId: String? = null,
    val sourceEpoch: Long = 0,
    val liveState: LiveState = LiveState.UNVERIFIED,
    val activeLease: EditLease? = null,
    val receipts: Map<String, PatchReceipt> = emptyMap(),
    val pathRevisions: Map<String, Long> = emptyMap(),
) {
    fun publicState(): WorktreeState = WorktreeState(identity, roots, headRevision, headSnapshotId, sourceEpoch, liveState, activeLease, receipts.size)
}
@Serializable private data class ChangeDescriptor(
    val operation: String,
    val path: SnapshotPath,
    val digest: String? = null,
    val size: Long = 0,
    val executable: Boolean = false,
    val to: SnapshotPath? = null,
    val target: String? = null,
)
@Serializable private data class OverlayDescriptor(
    val identity: SnapshotIdentity,
    val clientId: String,
    val sessionId: String,
    val patchId: String,
    val base: String?,
    val revision: Long,
    val mode: CommitMode,
    val scope: SnapshotScope,
    val changes: List<ChangeDescriptor>,
)
@Serializable private data class LiveDescriptor(
    val identity: SnapshotIdentity,
    val clientId: String,
    val sessionId: String,
    val patchId: String,
    val revision: Long,
    val scope: SnapshotScope,
    val kind: CaptureKind,
    val leaseToken: String?,
)

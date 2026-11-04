# Immutable snapshots and cooperative edits

Implementation module: Kotlin/JVM, `dev.ktchecker.snapshot`. The snapshot store is
not a compiler, a project-model exporter, an authentication transport, or a sandbox.
Its tests do not establish complete ktChecker release coverage.

## Preferred path: immutable producer bytes

```kotlin
val store = SnapshotStore(stateDirectory, ownerId = authenticatedOwner)
val identity = SnapshotIdentity(authenticatedOwner, "application", "agent-a-worktree")
store.registerWorktree(identity) // no live filesystem reads

val first = store.sealOverlay(OverlayRequest(
    identity = identity,
    clientId = "agent-a",
    sessionId = "task-42",
    patchId = "patch-1",
    baseSnapshotId = null,
    expectedHeadRevision = 0,
    changes = listOf(SnapshotChange.PutFile(
        SnapshotPath(path = "src/Api.kt"),
        ImmutableBlob.utf8("interface Api\n"),
    )),
))
val execution = store.materialize(identity, first.snapshotId, uniqueExecutionDirectory)
val providerManifest = first.toContractManifest()
// Run providers with execution.root as source and a separate, unique output root.
// Afterwards reject persistent source-write divergence:
check(store.verifyMaterialization(execution).valid)
```

`ImmutableBlob.copyOf` copies caller bytes at construction, optionally verifying a
producer-supplied SHA-256 digest. The producer must not mutate the array during that
construction. Subsequent caller mutations cannot change the sealed blob.

The first patch defines a complete **declared-input** manifest. Later changes apply
to a named immutable base. A rename is a delete/add; deletions have explicit delta
tombstones. Empty/no-op patches still receive independent admitted records.
`SnapshotScope` records its description, exclusions with reasons, and unknown inputs.
These omissions survive `toContractManifest()` and must not become full-project
success because a client sets a `fullProject` flag.
Changed-file overlays conservatively inherit exclusions and unknown inputs from
both their declared base and the integrated head. Default request scope cannot
erase them. Replacing an omission inventory requires a complete live recapture or
a separately registered complete producer input tree, not a partial overlay flag.

### Concurrent clients

- Prefer separate `worktreeId` registrations or immutable producer overlays. Do not
  discover identity from Git's shared object store or a “latest” editor session.
- In one logical worktree, `STRICT_HEAD` checks both base snapshot and head revision.
- `REBASE_DISJOINT` permits disjoint batches from a stale base. Every touched path,
  including additions, deletions, mode changes and both sides of a rename, retains
  a modification revision. Overlap is rejected even after **A → B → A** byte changes.
- Multi-file batches install one state transition; rejected batches cannot partly
  alter the current head. Rebased manifests contain the combined integrated tree,
  not merely the submitting client's changed files.
- Idempotency is `(owner, workspace, worktree, client, session, patch)`. Different
  clients can each call a patch `patch-1`. A replay in the same producer lineage
  returns its original snapshot; different parameters/bytes are a conflict.
- The kernel must preserve each accepted patch's coverage and delivery obligations
  separately. A snapshot commit is not review completion or delivery acknowledgement.

## Live worktrees: explicit cooperative protocol

Register each real input root with a stable root ID, a materialization mount, and
its live path. Build/config files, ignored generated inputs and approved external
dependency roots are not implicitly omitted. Overlapping physical roots cannot be
registered as different worktrees with independent fences.

1. Every relevant editor, generator and writer participates in the same protocol.
2. A writer calls `beginCooperativeEdit(..., allWritersCooperate = true)`. This advances
   the source epoch and invalidates live-current status **before** it edits.
3. Perform the bounded logical edit batch. No provider analysis/build runs inside
   this edit lease. Writers must stop writing at lease expiry or loss of authority.
4. Stop writing and call `captureCooperative(lease, request)`. The durable lease
   enters `capturing`; other participating writers cannot begin. Copying is outside
   the metadata mutex. The final commit checks the still-valid fencing token and
   revision, then installs the complete captured manifest and releases the lease.
5. Analyze immutable copies without any edit/capture lock. Begin another edit batch
   only with a new lease.

`renewEdit` extends an editing lease within configured bounds. `abandonEdit` clears
the lease but leaves live-current status unverified; it never declares changed bytes
reviewed. A crash/expired lease preserves its unreviewed state. A new lease advances
the fence, and the old writer cannot submit a valid capture with its stale token.
Failed capture leaves the source dirty/unreviewed until explicit abandonment and a
fresh capture. A disconnected writer is not assumed to have completed its patch.

This is an **all-writer cooperation contract**, not enforcement against arbitrary
same-user programs. Attribute checks and hash scans only detect some races. A stable
double scan cannot exclude torn capture or ABA edits. `captureBestEffort` therefore
always emits `BEST_EFFORT`, explicit uncertainty and no coherent/live-current proof.
A plain CLI scan cannot legitimately relabel itself an immutable producer capture.

`withCurrentHead(identity) { state -> ... }` holds a short cross-process worktree
fence for the kernel's final action cut. The callback must not run analysis/builds
or call another snapshot mutation. It must check the selected lineage, current
snapshot/source epoch, coverage/model/toolchain/policy epochs and required delivery
frontier together. Checking the snapshot and then releasing the fence before the
guarded action is not equivalent. An overlay is exact submitted content, not proof
that a physical checkout still equals it.

`withVerifiedSnapshot(identity, snapshotId) { check -> ... }` additionally validates
the admitted reference, head ID **and source epoch**, capture coherence, registered
roots and (by default) absence of declared omissions under that same fence. It
rejects an old head even after a new edit is abandoned without changing the head
ID. `requireLiveMatch = true` additionally needs the cooperative live proof.
`requireNoOmissions = false` is only for a trusted explicitly narrower scope; the
returned scope still carries every omission, and a passing snapshot-only check
never certifies imported project coverage or delivery.

## Storage, isolation and bounds

- One fixed owner per private store, authenticated by the caller's transport. The
  store rejects other owner IDs. Paths use hashed workspace/worktree keys; snapshot
  and blob reads require an admitted reference in that worktree.
- Owner-only POSIX permissions or Windows ACLs protect state and materializations.
  Different OS users should use different stores and broker credentials. An ID
  string is not itself authentication or a project permission grant.
- SHA-256 objects are immutable, atomically installed and checksummed when read.
  Manifest identity also binds roots, logical paths, modes, symlink descriptors,
  producer/base lineage, capture proof and declared input scope. Tree identity is
  available separately for validated input-equivalence work.
- Files and manifests are forced before an atomically replaced state document
  admits the patch. New directory entries are synced where supported. A crash after
  admission but before response is recovered by exact replay. Orphan pre-admission
  CAS objects never become admitted references.
- `directorySyncSupported == false` means directory fsync is unavailable (notably
  Windows); do not advertise power-loss qualification from process-crash tests.
  Atomic replacement itself is mandatory; unsupported filesystems fail closed.
- Per-worktree JVM and OS locks cover only capture/commit metadata coordination,
  not provider execution. A separate store-quota lock bounds CAS/metadata writes.
- Limits cover blobs, snapshot bytes/files, metadata, total store bytes, worktrees,
  tracked path history, accepted patches, lease/capture duration and lock waiting.
  Saturation returns an explicit error without deleting earlier admitted patches.
  There is no automatic GC/archival yet: safe collection must coordinate pending
  ingestions and every review/delivery obligation, not guess from the current head.

Portable path validation rejects traversal, absolute paths, non-NFC names,
case/normalization collisions, device names, reserved Windows components and
file/directory collisions. Only relative symlinks to captured regular-file targets
inside their own root are currently supported; escaping, dangling and cyclic links
are explicit errors. Unsupported symlink creation is an unavailable materialization,
not silently different bytes. This is a visible implementation limitation, not a
scope reduction decision for the complete release.

Materializations are exclusive new directories and contain copies, not hardlinks.
A failed concurrent attempt cannot delete another attempt's completed directory.
Read-only owner permissions and post-execution verification are **not a same-user
security sandbox**: trusted code can change permissions, and restored transient
writes cannot be disproved by an after-the-fact comparison. Strong untrusted build
execution needs a separately validated OS containment route.

## Tests

Run through the root build coordinator:

```sh
./gradlew --no-daemon :snapshot:test :audit:test
```

The module contains tests for immutable bytes, idempotency, namespaced clients,
strict/rebased conflicts, concurrent multi-file coherence, ABA/tombstones, renames,
roots/build/config inputs, path/symlink safety, partial-scope propagation, users and
worktrees, corrupt CAS, materialization writes, commit crash boundaries, capture
leases and expiry, head/action fencing, bounds, sustained concurrent submissions,
and incremental/full **manifest** equivalence at every replayed patch. Independent
audit tests add real process races and abrupt process termination. Findings/coverage
equivalence, real host delivery, platform qualification, and performance are separate
integration/release gates; source tests alone do not establish those claims.

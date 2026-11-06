# Experimental Darwin private-state backend

**Status: source-authored, not yet compiled, imported, tested or natively
qualified. Darwin stays unavailable by default.** The opt-in is an experiment,
not a support certificate. No native archive, signature, installer, or model
permission is implied by this source packet. Windows remains unavailable.

## Explicit build and loading contract

The separate build script accepts only ordinary-user macOS 26, native arm64/x64,
Node **24.20.0**, an explicit macOS 26 SDK version, and the exact matching
official Node `.tar.gz` archive. It never downloads or runs an install hook.
The current executable and compiler headers must match the selected archive.
The compiler is Apple clang from the selected installed Xcode. Existing output
is never overwritten/reused as a new build; unsuccessful attempts are not a pass.
Failed/uncertain build scratch is retained, not automatically removed or replayed.
Before publishing its manifest, the explicit builder loads the just-built addon
in the same Node process to check ABI/source/runtime admission without state IO.
That future build-time load is not native functionality qualification.

For an independently authorized, fresh native build (not executed here):

```sh
KTC_DARWIN_PRIVATE_STATE_BUILD=1 \
DEVELOPER_DIR=/Applications/Xcode_26.4.1.app/Contents/Developer \
node bridges/mcp/native/build-darwin.mjs \
  --node-archive /owner/download/node-v24.20.0-darwin-arm64.tar.gz \
  --arch arm64 --sdk-version 26.4 --with-test-faults
```

Runtime additionally requires `KTC_DARWIN_PRIVATE_STATE_EXPERIMENTAL=1`. It
accepts Darwin kernel 25/macOS 26 only, checks a bounded fixed artifact/manifest,
the executable/source/native hashes, ABI8-compatible N-API ABI1 exports, and the
source receipt exported by the addon. Fault-injected addons are rejected. There
is no runtime compiler, subprocess ACL parser, package/PATH search, download,
cross-target emulation or fallback. `DYLD_*`/`LD_PRELOAD` injection is refused.
Root/set-ID runtime admission is rejected before artifact reads and independently
by addon initialization. The addon queries `sysctl.proc_translated` in-process:
only exact native0 or Apple's documented -1/ENOENT (kernel without Rosetta) is
accepted. Translated1, short/unknown values and all other errors reject. A sysctl
subprocess or `uname` would not establish the calling Node process's status.

These hashes bind trusted installation/build inputs. They do **not** attest the
loaded image or replace installation trust, signing, complete build-origin
evidence, code-review approval or hosted qualification. The release checksums
were obtained over official HTTPS; no independent OpenPGP verification occurred.
Native binaries are private experimental build outputs. Existing bridge
owner/license/NOTICE/package-publication gates and npm archive whitelist are
unchanged. This interim experiment is **source-checkout-only**: the native
source, build/test tools, manifests and `.node` artifacts are not shipped in the
MCP npm archive. Packed startup stays fail-closed even with opt-in, because those
required inputs are absent. Use only the explicit checkout commands, not an npm
install hook. This does **not** remove the release distributable requirement;
reviewed packaging and native qualification remain separate open gaps. The CI
lane uploads only sanitized JSON, never native binaries, npm archives or terminal
transcripts.

## Narrow security policy and lifetime

- Descriptor-relative, no-follow access on local ownership-enforcing APFS;
  ancestors owned by root/current UID and not group/other writable. A readonly
  foreign owner is rejected because it can change permissions later. Sticky
  world-writable ancestors, including `/private/tmp`, are not safe roots.
- The private store directory is current-UID mode0700; regular state/config
  files are current-UID, single-link and inaccessible through group/other modes.
  Every ACL ALLOW is rejected, even owner/inherited/inherit-only grants. Empty
  ACLs and DENY-only ACLs with recognized flags/permissions may be accepted.
  DENY may still make ordinary filesystem/SQLite operations unavailable.
- Fixed protected `/var` and `/tmp` aliases are the only symlink aliases, and
  not as the final private directory. Modern `/Users`/`/private` firmlinks retain
  and verify both visible and `/System/Volumes/Data` backing chains and inode
  identity. Unknown device transitions fail. This needs actual native hostile
  backing-path/mount tests; no Linux simulation substitutes for them.
- Only `inbox.sqlite`, `codex.sqlite` and their `-wal`, `-shm`, `-journal`
  siblings are admitted. Existing objects are never chmodded, repaired, truncated
  or deleted to make admission succeed. Nonempty preexisting rollback journals
  are rejected **before SQLite open**, because their super-journal references
  can escape the directory. New empty scaffolding is not a historic privacy
  certificate for an existing store.
- One active native capability per directory: **use separate Inbox and Codex
  directories**. Its exclusive directory flock plus in-process reservation
  precede every regular-file open. Admission DB/sidecar descriptors stay held
  until SQLite has closed. Later checks never open/close extra regular-file FDs,
  since POSIX close can release the process's SQLite locks. Independent config
  readers take a conflicting directory lock before opening a file.
- The strong TypeScript ledger retains SQLite plus its capability. A failed
  open or uncertain close quarantines them for process lifetime; it does not
  retry or unlock. Even native GC/worker teardown cannot close an abandoned open
  capability. Successful SQLite close finalizes all tracked statements first;
  the wrapper exposes no backup/session/blob API. Native close is one-shot and
  fails on uncertain descriptor cleanup. A live stale controller cannot be
  displaced through a second native capability; crash/reopen still obeys the
  existing durable controller lease. This stricter concurrency policy is not a
  claim that the Linux live-lease-displacement test passed on Darwin.
- Config is bounded to 1MiB and read from the same verified descriptor with
  pre/post identity, size/time and authority checks. No pathname reopen is used.
  No same-UID/root adversary isolation or historical confidentiality is claimed.

## Exact SQLite temporary-storage closure

Source: Node24.20.0 tag object `8392e555cbdef2145d2cd2a2a7d29204d88d4e15`, commit
`71b8b174857e25106d39b61a9e6f30d927da8b01`; SQLite **3.53.4**, source ID
`2026-07-24 19:02:57 bf7c7f30031888f4e796e429ab3978879485813aaca6f641c7b33e4e09459bcc`.
Embedded `deps/sqlite/sqlite3.c` SHA256:
`b1dd5d74ec7f29055a6684fa06fb3c2f6821c87dd38f9a458dfd2e8a1db28189`.

After native admission, the connection opens with extensions disabled. Its
**first SQL** is unqualified `PRAGMA temp_store=MEMORY`, with readback2. Version,
source ID and `TEMP_STORE=1`/unsafe-option checks supplement—not replace—the
release origin. Trusted schema is OFF, and WAL/FULL/busy timeout are read back.
Only a finite set of existing host SQL is exposed. ATTACH, VACUUM, backup,
extensions, custom VFS/proxy/temporary-directory changes and caller SQL are not.
Linux retains its preexisting behavior; this is not a new Linux ACL certificate.

The exact source trace covers TEMP/ephemeral btrees, statement/savepoint
subjournals and sorter memory; allocation failures return NOMEM rather than
spilling to disk. `SQLITE_TMPDIR` is deliberately **not** used as a confinement
mechanism: SQLite caches environment pointers and silently tries other roots on
stat/access failure. Mandatory WAL readback also rejects Darwin autolock fallback
that does not support WAL. New SQLite methods or runtime builds require review.

Source-review packet manifest SHA256:
`1a2e65815e33235adf479c0fe27fea5e931cf66f16d4ea4d18dce1508712abd5`;
review note SHA256:
`f02576621e83316cba11e8009f4b2260dade11d9325b46ac4432268a0c6d0403`.
These are source-review identities, not native test results.

## Apple API evidence

Official sources: `https://github.com/apple-oss-distributions/Libc` and
`https://github.com/apple-oss-distributions/xnu`. No Apple code is vendored into
the implementation; the source captures remain in the private review packet.

- Libc `sys/statx_np.c` blob `3b2d9fb4b26f653ed969747f6d4e4e5af2076319`, SHA256
  `5a05eabc7d870f2c1a98c4e3b828d020e4a51e4b60c6887d955422add747730d`.
  `statx1` can return success on ACL-buffer realloc ENOMEM. A fresh filesec must
  have present, retrieved, matching OWNER/GROUP/MODE as a completion sentinel
  **before** absent ACL is accepted; these properties follow the retrieval loop.
- `filesec.c` SHA256
  `ae040dd45027269485360f736bf983d1371f25bac5c56184a7b2d7021490a454`;
  `sys/acl.h` SHA256
  `9511f84f0abe1e108e10979900d4fea8567534aef78f0984f7050c49f6c29ff7`.
  ACL extraction is descriptor-bound, bounded by the fresh decoder's count
  check, and rejects `_FILESEC_REMOVE_ACL`/errors. Apple `acl_get_entry` returns
  **0** on success and **-1/EINVAL** on exhaustion, not the Linux convention.
- `acl_valid` checks magic, not all ACL semantics; flag setters do not validate
  unknown bits. The implementation clones the snapshot, rebuilds only known
  flags on the clone, and compares the complete bounded public serialized
  representations, including empty ACLs. Perms use an explicit known mask.
  Tentative DEFER_INHERIT and unknown flags/perms reject. No private layout cast,
  ACL string parser or mutation of filesystem ACLs supplies this validation.
- XNU commit `f6217f891ac0bb64f3d375211650a4c1ff8ca1ea`, `kern_descrip.c`/
  `vfs_vnops.c`, supports the independent fileglob flock design in source;
  APFS operation and close semantics still require native evidence.
- Apple's [Rosetta translation guidance](https://developer.apple.com/documentation/apple-silicon/about-the-rosetta-translation-environment)
  documents native0/translated1 and -1/ENOENT as native where Rosetta is absent.
  The same pinned XNU commit's `bsd/kern/kern_sysctl.c` registers the
  `sysctl.proc_translated` process handler only under `CONFIG_ROSETTA`.
  Sealed admission-source review manifest SHA256:
  `ba72d9518f5fec1652028f0db6266a66b5554848e579eed9ec7131e38cd60fd3`;
  note SHA256: `b99352e7c51dd5ff8c4b0b1bd821974343f9d70758e65dbd4b33a2ce2e66ba12`.

Sealed Apple review manifest SHA256:
`0418c705b18c1e1a7f48d1dce6eae2999621a84151941747310dc340be3cb442`;
review note SHA256:
`f7f865f32484582ee770abd2c09cc39ea45d780beeb192c213a66e0e6505d58d`.

## Tests and remaining qualification

The manual `darwin-private-state-validation.yml` lane requires explicit consent,
the exact full source/workflow commit and run attempt1. It builds the real addon
and a separately marked filesec-fault addon, then runs **16** component tests:
14 native component controls, one default guard and one native fault-injection
control. Wrong platform/artifact/ordinary UID/fixture consent fails, never skips.
There are no real owner, Codex, account, compiler-provider or model probes.
The controlled chown changes only a newly created disposable fixture, never a
system directory; backend calls remain ordinary-user operations.
Evidence is created exclusively and tagged with the fresh workflow/run identity;
only a successfully written new summary enables upload, never an existing file.
The driver records an attempted test command separately; executed remains unknown
after an unconfirmed spawn/module/partial failure and becomes true only after the
exact successful TAP denominator and unchanged source identity are confirmed.

**Even a fully passing component lane remains experimental-unqualified.** Still
required: transient-open observation with a positive disk-temp control; large
statement/savepoint/sorter and actual OOM controls; hostile mount/ownership-
disabled/firmlink-backing configurations; config-read race and complete malformed
ACL cases; worker teardown; and the explicitly supported native target matrix.
Final directory listings or injected failures do not replace those controls.
No native case has been executed in this source-only packet.

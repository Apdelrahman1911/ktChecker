# Owned-child supervisor — preview-1

This small native C11 program replaces Java's unsafe cleanup of a numeric PGID
after Java may already have reaped its leader. It is **cooperative cleanup for
owner-trusted providers**, not a sandbox against malicious same-UID processes,
deliberate `setsid`/group escape, injected code, a compromised OS/toolchain, or
externally forced helper death. A matching manifest is availability, not platform
qualification. Windows remains explicitly unavailable pending Job Objects.

## Lifetime invariant

1. The single-threaded helper sets `SIGCHLD` to its default non-auto-reaping
   disposition, clearing inherited ignore/`SA_NOCLDWAIT`, before `fork`.
2. Its **actual fork child** becomes the session/process-group leader. Private
   CLOEXEC pipes implement a two-way gate: child sends `R` after `setsid`, then
   must receive `G` before `execv`. No group is chosen by PID rediscovery. An
   ungranted child cannot start a provider or create provider descendants.
3. `waitid(P_PID, child, ..., WEXITED|WNOHANG|WNOWAIT)` observes without reaping.
   The kernel therefore retains the actual leader's PID even if it is a zombie.
   An unexpected wait error irrevocably revokes signal authority.
4. **Every real signal goes through `signal_owned`.** It can signal the known
   group only while the fork child is still owned/unreaped. Before readiness it
   can signal only that positive owned-child PID. The final SIGKILL precedes the
   irreversible `signals_finished` state; only then can *any* `waitpid` run.
   No error, cancellation, timeout, output reader, or retry can re-signal after
   that transition. There is no Java negative-PGID command or fallback.
5. Linux establishes child-subreaping before fork. After the last signal it
   reaps the leader and adopted descendants; `ECHILD` with the actual leader
   reaped is the child-set quiescence acknowledgement. A surviving child means
   failure at the bounded deadline, not more signals using a recycled number.
6. Darwin reaps the leader, then only **observes** `kill(-pgid, 0)` until ESRCH.
   A now-reused ID can prevent success but cannot trigger another real signal.
   EPERM/unknown observation is not an emptiness proof: it stays unverified and
   may only be followed by another read-only observation within the same cleanup
   budget. An inconclusive signal delivery or group observation is retained in
   separate internal history, not confused with a loss of wait custody. Only a
   valid leader reap plus the platform's independent empty-set/group proof can
   resolve that uncertainty. Substantive ownership/wait failures remain sticky.
   The first quiescence proof uses a fresh monotonic timestamp strictly before
   the original cleanup deadline; expiry/clock failure cannot later recover.
   A timely proof is not withdrawn merely because output is still draining.
   Actual Darwin execution requires its own native CI; Linux models are not
   Darwin evidence.

The child reports setup/exec failure over the private readiness pipe. EOF alone
does **not** prove exec: a child may have died between `G` and `execv`.
`EXEC_CONFIRMED` therefore requires the completed gate, readiness EOF without
an error marker, and a **normal** leader exit. Exit 126 is conservatively reserved
for internal pre-exec failure and never establishes confirmation, even if a real
target deliberately returns 126. Signaled exits are also start-unconfirmed, even
if the target really started. Raw child exit/output remain available; they are
not fabricated proof of provider execution. Post-`R` gate failures explicitly
report setup failure rather than silently exiting with a success-shaped pipe.

## Build and installed identity

```
python3 modules/provider-runtime/native/build_supervisor.py \
  --output <generated-resources-root> --cc /usr/bin/cc
```

Compilation is an explicit build step, never runtime compilation, download, or
PATH helper discovery. The compiler must be an absolute owner-selected path.
Flags are exactly `-std=c11 -O2 -Wall -Wextra -Werror -pedantic`. The build driver
runs with only `PATH=/usr/bin:/bin`, `LANG=C`, `LC_ALL=C`; the compiler and its
linker/SDK/system libraries remain trusted external build dependencies. Output
is bounded; compiler diagnostics are not printed or retained. A failed supported
POSIX build does not reuse old generated resources. No cross-target CLI option
pretends a host build is a different target.

Gradle task `:provider-runtime:buildOwnedProcessSupervisor` writes under
`build/generated/nativeResources`; `processResources` includes those bytes under
`build/resources/main`. It deliberately rebuilds rather than caching across an
unverified transitive toolchain change. Supported build targets are
`linux-x86_64`, `linux-aarch64`, `macos-x86_64`, `macos-aarch64`.

Resource root: `dev/ktchecker/providers/runtime/native/`. Its `manifest` has
exactly four UTF-8 LF-terminated lines:

```
ktchecker-owned-child-supervisor/preview-1
<target>
sha256:<C-source-hex>
sha256:<binary-hex>
```

The binary is `<target>/supervisor`, at most 1 MiB. Windows emits a manifest for
`windows-x86_64`/`windows-aarch64` with final line `unavailable` and no binary;
unknown targets are literal `unsupported` and unavailable. Missing, malformed,
wrong-target, or mismatched bundled bytes are explicitly unavailable. The JVM
selects resources only from the loader class's installed code-source JAR, or the
explicit same-module development mapping `build/classes/kotlin/main` to
`build/resources/main`; not a context classloader, environment, PATH, or project
configuration. Each launch uses bounded owner-private verified extracted bytes.
The result returns the identity actually loaded, not a later inspector lookup.

`build-provenance.json` is a separate bounded (16 KiB) informational receipt:

- `schemaVersion`: `ktchecker-owned-child-supervisor-build/1`; `protocolVersion`
  is the full manifest protocol string. `status` is `built` or `unavailable`;
  `reason` is null or `unsupported_host`.
- `host`: normalized `os` (`linux`, `macos`, `windows`, `unsupported`),
  `architecture` (`x86_64`, `aarch64`, `unsupported`), and `target`.
- `compiler`: `selectedPath`, `resolvedPath`, `driverSha256`,
  `versionFirstLine`, `versionOutputSha256`, `versionOutputBytes`; null when
  unavailable. Only safe public system-tool paths are retained; other compiler
  paths become `<private-compiler>`. Version output is at most 64 KiB, hashed in
  full without transcript retention. `versionFirstLine` is always `<redacted>`
  (including recognized banners, whose remainder could contain a private token),
  with `versionFirstLineRedacted: true` for every built receipt.
- `inputs.source` and `inputs.builder`: fixed repo-relative `path` and `sha256`.
- `invocation`: exact fixed `flags`, role-redacted
  `argv` (`<compiler>`, flags, `<source>`, `-o`, `<output>`), `exactArgvSha256`,
  `exactArgvEncoding: utf8-json-compact-ascii`, `privateArgvRetained: false`;
  null when unavailable. The exact private argv is hashed as UTF-8 compact JSON
  with `ensure_ascii=True`, **not retained**. This digest alone cannot recreate
  omitted private path bytes.
- `output`: resource-relative `path`, `sha256`, `bytes`, or null.
- `compilerDriverIdentityOnly: true`,
  `transitiveCompilerToolchainVerified: false`,
  `sourceToBinaryReproducibilityEstablished: false`.
- `privacy`: `privatePathsRetained: false`,
  `rawCompilerTranscriptRetained: false`, `versionFirstLineRedacted`.

All receipt SHA values are `sha256:` plus 64 lower-case hex characters. Driver
identity is **not** transitive toolchain closure or independently established
source-to-binary reproducibility. Runtime identity is the actual bundle's
manifest/binary; the source digest is a build-manifest claim. Receipts cannot
retroactively qualify earlier execution or replace captured executable bytes.

## Parent/native protocol

Exact argv, with no shell expansion:

```
supervisor --ktc-owned-child-v1 TIMEOUT_MS MAX_OUTPUT_BYTES -- /absolute/target arg...
```

Timeout is 1..3,600,000 ms; combined output cap is 1..16,777,216 bytes.
At most 4096 target argv words are accepted, each at most 65,536 bytes and at
most 1 MiB total including terminators. The JVM additionally bounds the selected
directory/environment. Child stdin is `/dev/null`; stdout/stderr are separate
captured pipes. Working directory and exact allowed environment are selected by
the authorized adapter. The JVM rejects `LD_*`, `DYLD_*`, `GLIBC_TUNABLES`,
`GCONV_PATH`, and `LOCPATH` as explicit unavailable before helper launch: they
could interpose on the trusted helper's own loader/libc. It does not silently
strip keys and claim to preserve a target environment. OS/libc dependencies are
still external, not binary-digest-pinned. A future target-only environment
handoff would need a separate reviewed protocol.

The parent keeps control stdin **open** during normal execution. `C` requests
cancellation, duplicate `C` is harmless, and `C` then EOF is ordinary one-shot
cancellation—not parent loss. Unsolicited EOF means owner loss and cleanup.
Other bytes cause protocol failure and cleanup. There is no Java START packet.
Native TERM/INT/HUP also enter cleanup; the exec child resets those dispositions.

Helper stdout begins with exact eight-byte magic `KTCOWN1\n`. Every frame is
`tag:u8 length:u32be payload:length`. Tags 1 and 2 are stdout/stderr, each with
1..8192 bytes. Combined data payloads never exceed the requested cap. Tag 3 is
FINAL, exactly once, with this fixed 32-byte payload:

| Offset | Type | Meaning |
|---:|---|---|
| 0 | u32be | first non-completed cause |
| 4 | u32be | flags |
| 8 | i32be | actual child exit, -1 unknown; signal normalized to 128+signal |
| 12 | u32be | reserved zero |
| 16 | u64be | bytes actually observed from child pipes, at most Long.MAX_VALUE |
| 24 | u64be | captured data bytes, equals data-frame payload total |

FINAL is followed by EOF and native process exit zero. That exit code means a
complete transport, **not** successful provider execution. Duplicate/trailing
frames, unknown tags/flags, partial records, inconsistent counters, no FINAL,
no EOF, unexpected helper stderr, or nonzero helper exit fail. Previously valid
raw output/actual exit are preserved through subsequent transport failure.

Flags: `1 EXIT_OBSERVED`, `2 LEADER_REAPED`, `4 QUIESCENT`, `8 GROUP_READY`,
`16 EXEC_CONFIRMED`, `32 CANCELLED`, `64 TIMED_OUT`, `128 OUTPUT_LIMIT`,
`256 PARENT_LOST`, `512 IO_FAILED`, `1024 EXEC_FAILED`, `2048 PROTOCOL_FAILED`,
`4096 OWNERSHIP_LOST`, `8192 SHUTDOWN_FAILED`. Known mask is `0x3fff`.

Causes: `0 completed`, `1 cancelled`, `2 timeout`, `3 output_limit`,
`4 parent_lost`, `5 io_failure`, `6 exec_failure`, `7 setup_or_start_unconfirmed`,
`8 shutdown_unverified`, `9 protocol_failure`, `10 ownership_lost`. Cause 7 with
group readiness and observed exit but no exec/failure confirmation is reported
as `process_start_unconfirmed`, not a factual assertion the target never ran.
Completed cause 0 requires flags **exactly 31**, known actual exit, and observed
bytes equal captured bytes. A nonzero target exit can still be an executed
attempt, never automatically a passing provider result.

## Budgets, owner loss, and failure boundaries

The native deadline is autonomous. Normal leader exit sends TERM and the final
KILL immediately; a failure/cancellation allows 250 ms TERM grace before KILL.
All cleanup, including grace, has a 2000 ms deadline. Output/control processing
is nonblocking with one bounded pending frame; final wire flushing has at most
500 ms. A broken/blocked owner output cannot block cleanup indefinitely. Failure
to flush can produce incomplete wire/nonzero helper exit: that is unknown, not
clean execution. Observed bytes count bytes actually read, not an invented total
of bytes a killed/blocked child might have attempted to produce.

The JVM passes remaining preparation budget and keeps a full end-to-end deadline
including image loading and postchecks; the outer transport cap adds at most
4000 ms. Cancellation closes control and awaits native acknowledgement, never
uses Java's reaper-sensitive child PID to signal groups, and never kills the
helper prematurely. Still-live unacknowledged helpers retain bounded global
slots/private images until actual exit rather than starting unlimited retries.
Trusted callbacks/filesystem operations are cooperative, not forcibly preemptible.

An outer broker should close control and await native acknowledgement before
termination. If it must TERM its own group, use at least 3 s, preferably **4 s**,
before an outer forced KILL; the native cleanup-plus-flush budget is 2.5 s.
Forcibly killing or externally losing the helper before its final signal cannot
guarantee cleanup of its child's separate session. No SIGKILL/crash recovery,
PID reconstruction, success receipt, or release qualification is claimed for
that boundary. Unknown cleanup must remain unknown.

`ProcessOutcome.executionConfirmed` derives only from the actual terminal's
`EXEC_CONFIRMED`; `executed` uses that explicit fact, not `exitCode != null`.
`successfulExecution` additionally requires status `executed`. CPU/RSS are not
measured by preview-1 and are not fabricated as zeros; the adapter reports full
wall-clock time and actual observed output byte count.

## Synthetic controls

```
python3 modules/provider-runtime/native/test_supervisor.py --cc /usr/bin/cc -v
```

The small C probe covers literal argv/nonzero exits, separated output, fast
leader exit with a synchronized TERM-resistant orphan, unrelated retained
process groups, cancellation C+EOF, autonomous timeout/output limits,
unsolicited EOF, actual owner death, helper TERM/INT/HUP, invalid control,
broken/blocked output, exec/start ambiguity, and inherited ignored SIGCHLD.
Every waiting probe self-expires; tests need no JVM, real provider, shell,
network, or large process. Windows/unsupported native controls are explicitly
skipped, never counted as execution qualification.

Linux additionally requires existing `/usr/bin/strace`. The actual syscall
control checks successful real TERM/KILL precede the leader's actual reap,
WNOWAIT appears, and ECHILD follows. This verifies the observed run and guards
the lifetime ordering; it **does not manufacture actual PID reuse**, prove every
scheduler interleaving, or substitute for the ownership invariant/source audit.
Temporary binaries and raw traces are deleted. This suite is distinct from JVM
in-memory parser tests and actual host/architecture/provider qualification.

A separate `MockedShutdownStateTest` class compiles a deterministic no-child C
state model with all wait, kill, and clock calls replaced. It selects the
read-only group-observation algorithm after loading the real host headers; this
is not a Darwin ABI build or evidence of any real syscall's errno. The seven
`mocked_shutdown_state` methods retain provisional error history, require the
independent modeled reap/empty-group proof, reject unresolved/reused groups and
late proofs, preserve substantive failures, and guard post-reap signal authority.
They are reported separately from the unchanged eighteen actual native methods;
the two existing Linux-only controls remain explicit skips on macOS. Passing
these models cannot substitute for actual macOS or Linux process controls.

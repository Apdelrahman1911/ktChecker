# Opt-in actual-Main pre-owner framing smoke

**Not an execution grant.** This entry is for one separately admitted, fresh,
pre-owner-only observation. It is not an owner/model/host acceptance runner, a
release test, a shutdown interface, or permission to revisit an uncertain attempt.
Root owns integration, the build/execution lane, input approval and final readback.

## Admission before any run

Root must independently review and adopt the paired JVM/TS transport, the JVM
pre-owner terminal successor, and any separately pinned compile-fix successors.
Then obtain successful, explicitly admitted compilation/control and isolated-stage
build receipts. A matching `approvalId`, `status: "verified_success"`, source hash,
or JSON document **does not manufacture approval or successful build provenance**.

Select one trusted complete link-free JDK 21 image and Node 24.20+ within major24,
with a trusted launch environment and no injected Node preloads/JVM agents. The
entry checks the running Node path/version and selected executable/JAR/module
bytes; it does not run a Java version probe or attest a complete JDK/native/loaded
code image. Those trust/admission obligations remain external to this smoke.

Use a separately approved isolated stage shaped as:

```text
<sourceRoot>/build/preview-handoffs/private-preview-<8..96 alnum_- chars>/install/ktchecker
```

This path shape is not proof of stage freshness or provenance. Root must establish
those independently. Ordinary `modules/cli/build/install/ktchecker`, the protected
original installation, and the earlier fixed preview installation are not valid
substitutes. The entry does not build, install, discover JARs, or choose a stage.

Select an exclusively new evidence directory, with an existing real parent:

```text
<sourceRoot>/build/ci-evidence/owner-preowner-smoke-<8..96 alnum_- chars>
```

The directory must be absent. Its two runtime subdirectories and all five owned
files are freshly created and retained. No previous output or child is adopted.

## Explicit selection and build record

The private UTF-8 selection document has exactly these **13 keys**:

```text
schema, approvalId, sourceRoot, distributionHome, javaHome, outputDirectory,
node, nodeVersion, jvmExecutable, jvmClasspath, sourceInputs, controllerOutputs,
buildRecord
```

`schema` is `ktchecker-owner-preowner-smoke-selection/1`. Every pin has exactly
`{label, path, digest, bytes}`; digest is lowercase `sha256:` plus64hex. Paths are
absolute normalized Linux paths. Selected files are read through the existing
no-follow, identity/size/hash-checking helpers; selection and build record are
private owned files. No path or physical-file alias is permitted among the
selected input files, or with the selection document. Inputs and output are
symmetrically disjoint. The selection document is at most2MiB; the build record
at most65536bytes; selected pin bytes total at most1GiB, each at most512MiB.

- `jvmExecutable.path` must be exactly `<javaHome>/bin/java`.
- `jvmClasspath` is an **explicit ordered** vector of1..128 pins, each a direct
  `<distributionHome>/lib/*.jar` file. Here `*.jar` describes the file suffix,
  not a wildcard argument. Order is preserved; colon aliases, a wildcard JVM
  classpath, discovery, sorting and fallback installations are not accepted.
- `sourceInputs` and `controllerOutputs` are label-sorted inventories, bounded
  by8192 and256 entries respectively. The declared existing controller closure,
  the three pre-owner runtime TS modules, and eight named JVM boundary sources
  are mandatory. The full required module/source list is in
  `requiredPreownerSmokeModules()` and `validatePreownerSmokeSelection()`.
  Extra selected source coverage is root's choice and must match its build
  record; the mandatory list is not a complete source/JDK/resource closure proof.
- The inherited conservative controller list includes dormant host modules. Their
  pinned presence does not invoke host/model operations or validate their behavior.
- No model selection, registration, compiler policy, Gradle cache or owner
  invocation selection is accepted by this pre-owner document.

The independently approved build record has exactly these **14 keys**:

```text
schema, status, approvalId, buildId, sourceDigest, sourceInputsDigest,
jvmInputsDigest, controllerOutputsDigest, nodeDigest, nodeVersion,
buildEvidenceDigest, jvmBridgeBaseReviewDigest, preownerSuccessorReviewDigest,
hostTransportReviewDigest
```

Its schema is `ktchecker-owner-approved-preowner-smoke-build/1`, and its status
must be `verified_success`. The approval and Node fields must match selection.
Using the existing `canonical`/`digest` helpers, the three inventory digests cover
ordered arrays of `{label,digest,bytes}` for source inputs, controller outputs,
and `[jvmExecutable, ...jvmClasspath]` respectively. The classpath is not reordered.
`sourceDigest`, actual build evidence and the exact reviewed composition are
independently pinned by root; syntactically valid review-digest fields alone are
not checked certificates. When multiple TS/JVM review packets compose, the
selected review record must explicitly bind the whole effective composition,
not silently reuse a predecessor's clearance. No example here issues approval.

The entry hashes all pins before launch, rechecks selection/file identities and
bytes immediately before spawn, and rechecks them after actual child close.
This is selected-disk/build-record linkage, **not** reproducibility or loaded
memory attestation. JAR entry/class/resource coexistence is not inspected here;
any separately approved installed-JAR structural verifier is distinct evidence.

## What the single admitted run does

The entry is `test/current-image-owner-preowner-smoke.ts`, compiled by the existing
TS configuration but deliberately **not** named `*.test.ts`. It is not invoked by
`npm test`, is absent from the published `dist/src/**` package/bin, and has no new
package script. The controls module never imports this executable entry.

Only **after separate root admission**, the intended invocation shape is:

```text
SELECTED_NODE dist/test/current-image-owner-preowner-smoke.js --selection ABS_FILE --selection-digest sha256:EXACT_DIGEST
```

These are placeholders, not runnable approved inputs. Do not add a shell timeout
or other wrapper that would signal or close an uncertain child. Root must arrange
bounded retained stdout/stderr and independently observe final parent exit and
report readback, without automatic retry, cleanup or renewal.

1. The first statement captures the actual original overall900 clock; the second
   captures the actual first120 entry, before selected IO or spawn. A stricter
   smoke-only observation deadline is the original overall start plus120seconds.
   It can only shorten waiting and cannot reset any phase.
2. A single direct `spawn` runs selected Java with a fixed memory-bounded argument
   vector, the explicit ordered classpath and **actual**
   `dev.ktchecker.cli.MainKt owner-model-session`. There is no shell, inherited
   environment, replacement owner implementation or caller-supplied spawn hook.
3. The child handle is retained first after `spawn` returns. The same actual
   stdout/stderr pipes reach the existing `RetainedOwnerPipeChannel`; an
   independently decoding proof observer is attached before that channel.
4. The channel sends one `owner.open` request, id1, empty params, and computes the
   actual same-original-clock overall0 reply. The proof brackets that reply with
   the same `RealModelClock` readings; a new/caller-supplied900 is not accepted.
5. When the actual JVM emits export1's challenge, the closed input shim consumes
   the channel's export reply **locally** and substitutes the existing `deny`
   frame. Exactly three raw writes are allowed: canonical open1, actual overall0
   reply, deny. **Zero export replies reach the JVM.** A later best-effort deny
   from the poisoned channel may be suppressed locally once, never written again.
6. No successful open result is accepted. The harness observes actual direct-child
   exit78 and close78, both with null signals, all raw stdio close events, complete
   raw write callbacks/drain conditions, exactly hello/overall0/export1 output
   frames, and zero child/raw/shim errors or stderr bytes.

The raw byte bounds are stdout8192, stderr65536, each forwarded write2048, three
raw writes. Stderr is discarded after a bounded digest/count; no raw private
transcript or challenge nonce is published. Successful observation requires no
stderr at all. The actual channel is expected to remain poisoned. Because the
shim uses `attachPipes`, the channel's own child exit/close flags remain false;
the harness's independently retained actual-child events are the relevant ones.

## Why terminal78 is pre-owner only

The JVM has one preallocated monotone atomic state:

```text
FRAMING_ONLY --CAS--> OWNER_SETUP_ATTEMPTED
FRAMING_ONLY --CAS--> TERMINAL_BEFORE_OWNER
```

There is no reset. The setup CAS precedes the first selection read and every
owner/file/setup effect. The terminal CAS is inside the existing failure path
after transport poison. A terminal winner excludes every queued/racing later
setup attempt; a setup winner forever excludes terminal78, even if no owner
reference was returned, selection parsing throws, or setup is partial/unknown.
Only framed DATA/challenges precede this boundary. The bridge-created reader and
deadline observer are daemon DATA threads and never call owner/provider functions.

The unchanged actual `MainKt` uses `exitProcess(runCli(args))`; this successor lets
only the proven pre-owner branch return78 through that existing route. Thus
"natural exit" means observed child exit without a **host** signal or pipe-close
request, not that Main no longer calls JVM process exit. There is no new RPC,
CLI flag, shutdown method, retirement receipt or generalized owner exit path.
Every post-setup/partial/unknown failure retains the existing quarantine/idle
lifetime stack and reachability fence forever. A timeout never changes that rule.

The fresh `unread-owner-selection.json` contains only this exact invalid sentinel
(plus newline), whose actual digest is passed in the fixed JVM argument vector:

```json
{"schema":"ktchecker-NOT-an-owner-selection-preowner-smoke/1"}
```

On the expected path it is never read by the JVM. It cannot decode as an owner
selection if a future regression crosses the boundary. This is an additional
defense, not substitute evidence for the source gate or a successful owner setup.

## Retained output and final success boundary

Owned files, each at most1MiB and created exclusively/read-only, are:

- `unread-owner-selection.json`
- `admission.json`
- `invocation.json`
- `wire-proof.json`
- `result.json`

Names are consumed before uncertain writes; partial/unknown files are never
rewritten. `owner-environment-home` and `owner-environment-tmp` are retained.
`result.json` inventories earlier owned files but cannot self-inventory its hash.
Its `runtimeBoundaryBeforeReport` includes the wire proof and excludes result
itself. A second boundary **after** result publication includes all current owned
files and runtime directories, bounded by64 entries/2MiB, and is delivered on
stdout as `finalRuntimeBoundary`. These are observed tree boundaries, not
continuous quotas, external-effect inventories, process-tree audits or quiescence.

The immutable successful-observation result is deliberately labeled
`observed_preowner_framing_pending_final_delivery`, **not final verification**.
The same original120/900 guards bracket boundary checks and success-sensitive
file writes, and remain active through final stdout's callback and a post-await
check. Parent exit defaults to1; only that final within-deadline path can select0.
The original timer is cleared only afterward. Failure diagnostics may be written
after expiry but cannot promote success, refresh a clock, retry output or cleanup.

Root may accept only the **composite** of one delivered bounded stdout record
(`verified_preowner_framing_only`, at most32768bytes), actual parent exit0, the
record's exact result digest/path and retained report/file readback, with a valid
post-result boundary. A success-shaped stdout line without callback/exit delivery,
or a pending observation file left by a later timeout/write failure, is incomplete.
There is no second stdout success/failure retry to repair uncertain delivery.

Even that composite validates only **actual-Main framing, original-clock
narrowing and early denial**. Owner initialization, registration/factory/context
identity, policy, compiler/provider, model/host acceptance, installed resource
coexistence, loaded image, owner quiescence, complete cleanup, useful-complete and
release qualification remain **unvalidated**. All original900/300/120 bounds,
full1663 obligations, historical attempts and independent platform holds remain.

## Controls are separate

The source successor adds11 Kotlin test-method sources (seven inert CAS controls,
four actual-transport memory-pipe controls) and13 top-level TS inert byte/schema/
channel controls. They do not spawn Java or create an owner/model. Kotlin controls
retain fresh inert temporary directories and close only their own inert local
pipes; that is not production child-cleanup authority. Their authored presence,
static byte assertions, and private patch applicability are not compilation,
executed test passes or an actual smoke receipt. Execution always needs its own
root admission and retained result.

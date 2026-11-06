# Thin current-image host driver: explicit new attempt only

This is an opt-in composition of `PreparedCurrentImageLaunch.prepare/open`, the
concrete `RetainedCurrentImageOwnerFactory`, and
`runActualCurrentImageAcceptance`. Importing the driver does not prepare or open
anything. There is no injected factory, owner, host, adapter, clock, old receipt,
historical attempt, retry, replacement or cleanup option.

Source and synthetic controls are **not actual owner/model delivery evidence**.
Only the lead may adopt the reviewed source, build/test it, select the resulting
image, and authorize a genuinely new actual attempt. An example command below
is not that authorization.

## Admission prerequisites

1. Independently review this driver, its fixture/observation helpers, the additive
   public-binding callback seam and their controls; then verify the selected
   source's build and controls. The four new control modules declare 115 Linux
   synthetic cases: capture 11, fixture 39, observation/publication 42, driver
   selectors 23. They never run this actual entry, a JVM, compiler, provider,
   Codex child or model. Retain the existing nonoverlapping controls too.
2. Select a **new**, successfully built isolated owner installation and the exact
   controller outputs. Preserve the original installation, old handoff images,
   uncertain attempts and the absent default checkpoint install. Packaging
   chooses its own new nonce stage; do not precreate or guess it. Root must
   independently check the selected ordered JAR vector, required owner classes,
   build provenance and root-owned smoke prerequisites. A minimum structural JAR
   roster is not transitive/runtime closure, native ABI or loaded-memory proof.
3. Pin source/output bytes and a successful owner-approved build record using the
   existing image selection. The existing mandatory **28** controller modules
   remain mandatory. Also include the three source/output pairs
   `current-image-host-acceptance-driver`,
   `current-image-host-acceptance-fixture`, and
   `current-image-host-acceptance-observer`: **31 minimum**, not a claim about all
   dynamic/native/JDK dependencies. The approved runner postimage must be pinned.
4. Independently pin the launch, authorization, invocation, compiler policy,
   transport and preexecution documents outside the new run root and external
   cache seed. Use an absent run root and new attempt/patch IDs. Refusal or an
   unknown earlier attempt does not authorize reusing its root, ID or processes.
5. Select JDK 21, Gradle 9.6.1, Kotlin 2.4.21 and the exact offline artifact/seed
   correspondence required by the existing transport. Keep explicit ordered
   compiler artifact paths and ordered pinned direct owner `I/lib/*.jar` inputs;
   no wildcard, directory discovery, PATH fallback, reordered vector or
   `-Dktchecker.home` override. The application model JAR belongs under
   `I/providers/compiler-model/`, not the ordinary owner lib vector. Compiler
   policy consent and `authorizeExecution:true` are separate decisions.
6. Select the existing fixed Codex ELF (289101384 bytes,
   `sha256:12eb3e81114588aca3b7998f4f19e8997b056aca08e57a7ca7c8a3ec8c652aad`),
   `gpt-6-astra`, `max`, and the existing no-reroute contract. Root must establish
   account/authentication availability and exact model/effort capability without
   exposing credentials. Documentation or matching schemas alone do not prove
   runtime capability. Do not substitute a model or adapter.
7. Explicitly accept the trusted-host limitation and retained lifetime below.
   A run must have a nonsignalling observer and retained launcher/session custody.
   Do not use a process timeout/kill wrapper to obtain a convenient exit status.

The owner transport retains its existing fixed Java argument construction:
selected `JDK/bin/java`, `-Xmx512m`, `-XX:MaxMetaspaceSize=256m`, fresh selected
owner environment home/tmp, the explicit pinned lib vector,
`dev.ktchecker.cli.MainKt owner-model-session --owner-action
--authorize-execution --request ABS --selection-digest sha256:...`.
The Workbench derives its distribution from its selected code-source JAR.

## Explicit public fixture and consent

The only project this driver captures is
`ktchecker-current-image-public-friend-patch-fix/1`. Both patches contain the
entire five-file project: `settings.gradle`, `build.gradle`, `gradle.properties`,
`src/main/kotlin/Api.kt`, and `src/test/kotlin/Use.kt`. Both main and test are
genuinely nonempty. Public Kotlin task configuration selects the actual producer
output, external classpath and main-to-test friend relationship **before**
capture. It does not remove test, invent a no-test fallback, merge sources,
rewrite a returned model or hide export gaps.

The first test source calls `missingDeclaration()`; the second calls the
main source's internal `declaredAcrossFiles()`. The authorization must expect
exactly `UNRESOLVED_REFERENCE` / `ktc.compiler.common.unresolved_reference` on
`src/test/kotlin/Use.kt`, present on patch 1 and absent on patch 2. Its sorted
14 compiler IDs must equal `CURRENT_IMAGE_PUBLIC_COMPILER_RULES`; the separate
owner compiler-policy selection must bind the same vector. Neither this fixture
nor these expected results prove that any compiler ran. Full1663 required
coverage, exact14 computation evidence and the denied final remain unchanged.

The selected independent preexecution document retains its existing
`ktchecker-current-image-host-independent-preexecution/1` fields, source-review
and completed-synthetic-control evidence. Additionally its `driver` object must
be exactly:

```json
{
  "schema": "ktchecker-current-image-thin-driver-consent/1",
  "fixture": "ktchecker-current-image-public-friend-patch-fix/1",
  "fixtureDigest": "sha256:253ad7c3b078bb5ba5bc312e208ded85c6a790807e8f81c521ec624462ae3e49",
  "trustedHostPolicy": "explicit_trusted_host_public_fixture_no_read_isolation_attestation",
  "retainedLifetimeAccepted": true
}
```

This pins both whole file vectors, roots, finding and 14 IDs. It is consent DATA,
not a certificate, sandbox, owner ticket or runtime grant. The callback's new
third argument contains only a frozen copy of the retained run's seven public
`ClientBinding` fields. The driver never guesses the first owner ID, consumes a
launch ticket for inspection, reads an owner store or receives credentials.
Existing two-argument callbacks remain compatible; post-callback guards and
exact returned-patch binding checks remain mandatory.

### Trusted host is not fixture-only read containment

The existing host's read-only sandbox and no-tools prompt do **not** establish
fixture-only read access or genuine tool disablement. Its Codex child inherits
the selected process environment/cwd; its newly created thread uses the fresh
public workspace. The pinned 0.160 request schemas do not establish newer
`readOnly.access.readableRoots` semantics: documented newer capabilities must
not be invented on this pinned runtime. Named permission-profile IDs do not
attest their filesystem meaning. No credential/home inspection or runtime probe
is implicit in this driver.

All receipts keep `publicReadIsolationAttested:false`. Retained client/adapter
tokens and selected private locations still feed the existing output exclusion
checks; `forbidden:[]` means no *additional* caller strings, not no exclusion
checks. This does not constrain arbitrary privileged out-of-band host reads.
Stronger containment would require a separate reviewed supported isolation or
adapter change and fresh explicit consent, never a silent switch here.

## Command after root's explicit new-attempt grant

Use the selected Node/output image and the exact selected launch-file digest:

```sh
"$SELECTED_NODE" --max-old-space-size=512 \
  "$SELECTED_CONTROLLER_ROOT/dist/test/current-image-host-acceptance-driver.js" \
  --selection "$ABS_LAUNCH_SELECTION" \
  --selection-digest "$SHA256_LAUNCH_SELECTION" \
  --approve-new-public-fixture-trusted-host
```

The digest argument includes `sha256:`. Arguments have exactly this order;
there are no environment-selector, replay, close, alternate-host or fixture-path
options. The existing image validator independently checks the running Node's
selected path/version/bytes. The command does not print private event payloads,
source, credential values or raw errors.

## Original clocks, observation and retained uncertainty

The original launch `RealModelClock` is created by `prepare` before selected-file
IO. Existing original **900/300/120** clocks and nominal imported-object
authority remain unchanged. The driver has an earlier-starting, denial-only
900-second observer; it never enters the owner transport as an execution clock.
The owner transport's first selection read remains inside its original120.
The driver retains the prepared launch, exact factory, opening promise, ticket,
runner promise and any late actual return. It calls `open` and the actual runner
once, and captures only the two fixed public patches.

At a driver observation deadline, the diagnostic result is incomplete. Late
actual returns/rejections stay retained and **never upgrade that result**.
`runnerReturnObserved` means a report observed within the original driver
observation deadline, not merely a later settled promise. No cancellation,
close, end, signal, unref, retry, replacement, cleanup or quiescence proof follows.

One separate diagnostic stdout write has a fixed absolute **15-second** bound
and at most 4096 bytes. It provides no execution time and does not reset any
runner/owner clock. Partial write, error, nonreturn or missing callback is
unconfirmed: no resend. A callback means a write callback, **not reader ACK**.
The helper rejects duplicate callbacks before first settlement; a later duplicate
cannot revoke an immutable first observation. The actual selected `node:fs.write`
has one callback; synthetic duplicate controls do not change that API.

A verified driver return describes a **prior within-original900 actual runner
observation**, followed by confirmed diagnostic publication. It does not attest
that the driver function itself returned within900, much less a new delivery
after the deadline (`driverReturnWithinOriginal900Attested:false`,
`publicationGrantsExecutionTime:false`). Timer observation cannot preempt a
blocked synchronous OS operation or certify process lifetime/quiescence. It is
not a hard-kill deadline or permission to continue provider/model work.

The driver never adds outer cleanup. The preselected inherited Codex child
failure policy remains disclosed: its own failure/open-failure paths may signal
their own new child. That is not an owner/compiler retirement claim and is not
misrepresented as a globally zero-signal host.

## Readback: distinguish candidates from actual function return

Under the exclusively new `RUN_ROOT/driver-observer`:

- `started.json` says the new driver preparation was recorded, not that a model
  or owner completed.
- `observation.json` and the sole stdout line are **candidate diagnostics**.
  They always say `status:incomplete_or_failed` and
  `actualHostDeliveryVerified:false`, even if the actual runner already returned.
  Their hashes/`runnerReturnObserved` are documentary data, not final success.
- The CLI `.then` callback records `returned.json` only **after observing the
  actual exported driver function return**. Its `driverReceipt` may report the
  prior verified delivery only if the direct actual runner return met all joins,
  both full-parent/exact14/finding-change summaries, selected launch/approval,
  denied final, no unknown operation, and the candidate write/publication checks.
  Otherwise it records incomplete/failed. Its `thisRecordWriteConfirmed:false`,
  `thisRecordAcknowledgedByReader:false` and `processExitObserved:false` expressly
  avoid claiming that this file confirms itself or the process exited.

Root must correlate a fresh readback with the exact launched invocation/image,
candidate-file and stdout digests, and the retained private joined evidence.
A copied JSON file is never imported as authority by this driver. Missing,
partial, mismatched or unconfirmed readback is a blocker, not permission to
replay. A returned-file write error sets a nonzero eventual exit code but does
not resend stdout or terminate anything. If preparation failed before a new
evidence directory existed, only an incomplete diagnostic may be available.

Setting `process.exitCode` does not exit. Once actual owner setup was attempted,
the existing owner/server stack, quarantine, TS handles and pending work remain
retained on success **and** failure. Natural pre-owner smoke termination is a
different, separately admitted path; this driver does not use it as owner/host
cleanup. Do not kill, close, reuse or inspect a historical uncertain attempt to
make this command look finished.

No result is release qualification, useful-clean/full-project proof, an internal
inference request trace, a license decision or binary-publication permission.
The native Windows hold remains exactly:

> native Windows treats Unicode quote dollar and bracket paths as data and allows readonly ordinary-user ancestors

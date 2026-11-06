# Pinned Kotlin/JVM compiler integration

The adapter starts Kotlin **2.4.21** in a separate bounded Java process. It never
loads compiler internals into the host. The 443 exact catalog mappings are a
transport ledger: 400 are JVM candidates; 43 Native mappings are **not executed
or supported by this JVM provider**. A mapping is not a fixture result.

## Authored inputs and current evidence

`manifest.json` now lists **92 ordinary JVM positive/corrected-negative pairs**:
the original 42 are unchanged, and 50 newly authored pairs cover overrides (13),
visibility (8), types/conventions (11), inline/constructors (5), opt-in (4), and
JVM interop (9). Each new pair has explicit plans for all six assessment kinds
and a valid or intentional/advisory alternative. These are **unexecuted source
plans**, not observed diagnostics, clean compilations, or qualification.

`planned-kmp-manifest.json` separately records **16 planned, unsupported**
expect/actual candidates: eight declaration checks and eight matching checks.
Their common/JVM source files and explicit JVM-refines-common graph remain
outside the ordinary runner. Execution awaits a capability binding sealed
copied files to their common/actual fragment roles and refinement edges.
Concatenating files or using same-module pseudo-KMP is prohibited. Even a future
JVM graph success would not prove metadata, Android, Native/iOS, linking, or
simulator support. `NO_ACTUAL_FOR_EXPECT` also requires the IR/FIR2IR path.

The current sidecar retains all **443 rules with implementation, tested,
qualified, and attempted flags false**. No real compiler corpus has run against
the new full-runtime/native-supervisor/model-bridge/fixture sources. Current
mapping source references were refreshed only after the engine source freeze;
that source-only refresh is not execution evidence. Historical tuples below
remain immutable and are not attributed to these edits.

## Run

```sh
./gradlew --no-daemon --max-workers=1 :provider-compiler:compilerFixtures \
  --args="--cohort=overrides" \
  -PcompilerFixtureJavaHome=/absolute/owner-prepared/link-free-jdk21
```

An explicit named `--cohort=` is required: `original`, `overrides`, `visibility`,
`types_conventions`, `inline_constructors`, `opt_in`, `jvm`, or explicit `all`.
There is no default-all run. These are trusted harness scheduling choices, not
project-policy JSON or execution authority. Every invocation creates a fresh
UUID/cohort run directory under repository-root `build/compiler-fixtures/runs/`;
resuming or merging prior runs is prohibited. New generated runs use the existing
ignored `build/` output area. Tracked history under `providers/compiler-fixtures/runs/`
is not moved, deleted, or ignored and remains in the source-identity inventory.

Hard maximums (also the defaults) are **48 reserved provider attempts, 20 minutes
overall, 90 seconds per job, and 256 MiB retained evidence**. Smaller bounds use
`--max-attempts=`, `--overall-seconds=`, `--per-job-seconds=`, and `--evidence-mib=`
(minimum 24 MiB). Startup, materialization and checkpoints consume the monotonic
overall budget. Each job gets a fresh exact permit and timeout no larger than
the per-job limit or remaining overall time. Denied provider calls consume
attempts without claiming compiler execution. Even explicit `all` stays capped;
an exhausted bound or missing/failed selected assessment makes the task fail
partial, not silently successful. The elapsed-time bound is cooperative, not an
OS hard-kill or performance certificate. The final clock decision follows the
last bounded completion-selector write; all bulk serialization, reference
hashing and checkpoint writes count before it. Later terminal notices do not
make a second, contradictory completion decision.

The runner prints its owner-private `build/compiler-fixtures/runs/<run>/control/stop`
path. Creating a
regular, non-linked file of at most 4 KiB there requests stop before the next
admission; it neither reads the contents nor signals a PID, and the current job
keeps its deadline. Unsafe/replaced control paths fail closed. Owner stop,
deadline, attempts, evidence exhaustion and cleanup failure remain distinct
receipt reasons.

The fixture host uses JDK21. The task requires an explicit owner-selected,
link-free JDK21 home via `compilerFixtureJavaHome`; it never silently selects
the Gradle launcher's ordinary system JDK or copies/prepares a JDK itself.
The child uses that selected image, a resolved pinned compiler classpath, explicit
Kotlin standard-library identities and serial child JVMs. The compiler child
heap defaults to 512 MiB (`-PcompilerFixtureHeapMiB=...`). Production hosts select
`compilerHeapMiB` and exact execution bounds; a JSON `approved` boolean is not
authority. Only an authenticated, non-serializable permit naming the entire
`AnalysisInput.analysisKey()` can execute.

Compiler installation identities preserve the ordered coordinate/content/path
list, framed as canonical JSON. The exact installation digest is intrinsic to
the approved JVM profile even when a limited request has no imported toolchain
digest; a declared mismatching toolchain is denied before execution.

The Java identity inventories the **whole selected JDK home**, not just
`bin/java`: modular runtime bytes, native VM libraries, configuration, release
metadata, all other files, entry names, empty directories and executable policy.
Only supported JDK21 layouts are accepted. Dot/parent-traversal selections,
linked home leaves, symlinks, junctions and special descendants are denied.
Aliases above the explicit ordinary outer home are canonicalized once and their
target stays pinned. Each full streaming scan bounds entries, depth, UTF-8 names,
individual/total file bytes and cooperative elapsed time; no mtime-only cache is
used. The image is reverified during preparation and after the child. Runtime
drift fails closed while actual command, exit code and output remain retained.
`toolchain.json` includes the captured full inventory and its digest. This is
trusted quiescent disk identity, **not** loaded-code or external OS/JNI attestation.
Normal symlinked system JDKs are intentionally unsupported; an explicit prepared
image is required for compiler execution, with no launcher-only fallback.

`manifest.json` and `cases/` contain original synthetic inputs, not copied or
disabled upstream execution evidence. The manifest's initial input status is
not updated to claim execution. Each actual run writes unique repository-root
`build/compiler-fixtures/runs/<run>/`
input manifests, captured source bytes, exact commands, version banners,
stdout/stderr, provider results and explicit assessments. The sidecar
`evidence/compiler-jvm-2.4.21.json` under `providers/compiler-fixtures/` keeps its
ordinary convenience-publication path and references those real files with SHA-256,
reporting **every** mapped rule, including unrun and Native gaps. Private timing,
supplemental, nullability, and diagnostic-breadth runs have no external latest
sidecar destination; only those routes avoid changing this source-visible sidecar.

All **92 ordinary pairs, 16 unsupported graph plans, and 443 mappings** remain
visible before cohort selection. Unselected, not-run, incomplete and failed
states are explicit gaps, never exclusions. Raw job receipts, compact job
indices, assessment receipts and ledger checkpoints are immutable; full
sidecar/provenance/summary checkpoints are retained at startup, each assessment
phase, controls and terminal publication. Only explicitly named latest
convenience files are replaced, and immutable receipts never hash-reference
them. Evidence writes use owned no-link directories, SHA-checked atomic moves
with no non-atomic fallback, and quota accounting for temporary-write peaks,
16 MiB prelaunch raw-evidence reservations and 8 MiB terminal headroom.
Bulk immutable checkpoints and latest sidecars/summaries remain explicitly
`candidate_only`, with `run_complete:false`. Only the settled small final
`latest-checkpoint.json` selector **together with successful harness return**
can establish bounded completion. It is clock-checked after publication;
overrun/error writes an immutable incomplete decision and corrects the selector.
Each terminal record is capped at 64 KiB, with 256 KiB headroom checked before
a positive selector for that write, corrections and one distinct fallback.
A failed correction or unknown harness return leaves even a positive selector
unconfirmed, never standalone success evidence. Atomic moves are not a
power-loss durability certificate.

Scratch roots are registered at creation; provider output is registered by the
original permit's pre-write callback, not by a subsequently observed pathname.
Cleanup inventories at most 20,000 entries/depth 32, then uses secure no-follow
reverse-depth deletion with file-key and disappearance checks. Replaced,
linked, special, unknown or reappeared retired roots are not deleted. Unsupported
secure directory streams are a failure/explicit host gap, never an unsafe
recursive-delete fallback. Cleanup receipts are retained, and failed cleanup
prevents further admission before final terminal evidence.
Unverified native group shutdown, a missing/mismatching actual supervisor
identity, or a provider throw after entry without an observer outcome instead
irreversibly quarantines the **whole private scratch tree**. No job subtree or
final ancestor is inventoried/deleted, and no later job is admitted; output
created before its registration callback is retained too. Exec confirmation or
an exit code is not a cleanup acknowledgement. This retains the original
failure when cleanup/reporting also fails and remains limited to the native
supervisor's cooperative owned-group boundary, not escaped-child attestation.

Before reading the manifest/cases, the runner preserves startup copies of the
provider, fixture, runtime and contracts sources plus relevant build metadata.
It separately preserves actual provider/runner/contracts class directories or
jars, host runtime jars and loaded registry bytes, with manifest digests and
class-resource anchors. Those artifact identities are **not** inferred from
live source hashes. Artifacts/resources are checked before each compiler job;
sources and artifacts are checked at run end. Drift fails the task and withholds
implementation/test attribution while keeping original copies and actual
attempt results. Each run also keeps its own sidecar so later runs cannot erase
historical failures. Existing tracked raw stdout/stderr and bytecode remain
byte-preserving Git evidence, not whitespace-cleaned text; new untracked run output
stays in the ignored build area until separately preserved by its owner.

The runner also preserves the native C source, builder and README, plus the
exact installed supervisor manifest, binary and informational build receipt at
START. Resource reads are bound to the internal `OwnedProcessSupervisor`
class's actual CodeSource: the exact ordinary JAR (bounded archive/entry reads)
or the literal same-module `build/classes/kotlin/main` to `build/resources/main`
mapping, with no linked descendants or context-classloader first match.
The whole actual runtime class directory/JAR is independently preserved.
Manifest (512 bytes), binary (1 MiB), and build receipt (16 KiB) bytes are
reverified before every job and at END. The manifest's source digest is a build
claim, **not** independently established source-to-binary reproducibility.

Every new `process.json` retains the actual observer outcome's
`execution_confirmed`, `executed`, and supervisor identity and compares that
identity to START-captured bytes. Inspection/availability is not a launch
witness. A nonnull/nonzero fork-child exit or nonempty output alone cannot
prove that the compiler executable started. Confirmed compiler diagnostic
failures retain their real nonzero exits. Jobs and raw outcomes are recorded
before receipt invariants can throw; failed/unconfirmed attempts remain in the
job ledger, while implementation/test attribution is withheld. None of these
new receipt fields is retrofitted onto historical process files.

Configured repository and actual class-source outer directories are canonicalized
once to accept operating-system ancestor aliases such as macOS `/var` versus
`/private/var`. Linked outer-directory leaves and all evidence-file or inventory
descendant symlinks remain rejected; arbitrary evidence paths are never silently
canonicalized by the bounded reader.

Six independent assessment kinds cover positive, corrected negative,
configuration (warning promotion and disable, or JVM target change), executed
version/renderer plus mismatched-version denial, real source suppression, and
changed/moved-source regression. Error suppression is executed individually:
failed compilation remains failed, and compiler-accepted suppression cannot
create successful coverage. Additional actual controls exercise unmapped syntax,
unnamed `-Werror` failure, quoted IDs, and intentional NaN/infinity/empty ranges.
No absence-of-warning claim is made for intentional floating-point/sentinel code.

The preserved `2026-11-04T09-40-05.124767771Z-b5c3e404` startup tuple executed
74 actual compiler children: all 42 authored IDs were observed, 41 passed all
six kinds, and two `EMPTY_RANGE` negative/configuration assessments failed.
Its deprecated experimental-checker flag emitted a global `DEPRECATED_CLI_ARG`
warning, correctly retained raw rather than treated as clean absence. All 187
normalized Findings had truthful not-run Verification; source/artifact drift
checks passed. Subsequent toolchain-identity changes require new validation;
these historical results are not attributed to later implementation edits.
The latest sidecar therefore withholds all current-source implementation/test
flags and references the immutable historical run separately. Existing 14-test
JUnit XML is preserved under `unit-evidence/` with an explicit post-run
preservation label; it is not a startup receipt or evidence for the new sources.
The new runtime-identity tests use tiny non-executed synthetic layouts, never a
fake JVM/compiler execution. The central `2026-11-04T10:22:16Z` compiler unit
reports independently verified **28 passed, one explicit native-Windows junction
skip, zero failures/errors** for the frozen pre-native-supervisor/model-bridge
source tuple only; exact XML and matching frozen sources are preserved
under `unit-evidence/2026-11-04T102216Z-central-compiler-units/`, labeled post-run
rather than startup bytecode attestation. The overall central batch failed in
other modules; it is not an all-modules passing receipt. These reports do not validate later runtime/native/model-bridge/fixture edits.
The nine byte-only native resource/receipt tests passed in the central
pre-bounded fixture freeze only. The first bounded source freeze actually
compiled and ran **106 cases: 103 passed, two final-root scratch cleanup tests
failed, and one explicit Windows skip**; those reports are retained under
`build/evidence/compiler-bounded-units/20261104-first/`. The subsequent
quarantine/terminal-publication/final-root fixes bring the bounded controls,
evidence and scratch tests to **40 (21/9/10)**, including 15 new non-executing
regressions. These fixes/tests are authored but not yet compiled or run; the
earlier receipt does not validate them. No current-source real
compiler child/corpus has run yet, so frozen unit success
does not reattribute historical per-ID results or create qualification.

## Boundaries

* Sources come only from an immutable manifest and explicit imported
  `context.compilationFiles`. Captured build metadata is retained for identity,
  but is never executed or inferred to be a compiler source. A source-only
  manifest without imported membership permits limited transport, not complete
  Gradle/KMP scope. `.kts`, source-root inference and directory classpaths are
  unsupported.
* Compiler, dependency and source bytes are verified and copied into fresh job
  outputs. The separately selected JDK runtime image is not copied per job; its
  full inventory is reverified before/after execution. Flags are allowlisted;
  response files, plugins, `-J`, output and
  classpath escapes are rejected. This is trusted-process isolation, **not an
  adversarial same-UID OS sandbox**.
* Named headers are resolved against the job's sealed sources. Reported locations
  are one-based lines and zero-based UTF-16 point columns, not guessed token
  spans. Unmapped, unnamed, unknown-path and malformed output stays raw and
  prevents completeness. Actual emitted severity is never replaced by factory
  declaration severity or inferred behavioral intent.
* Disabled/unenabled checker settings, unknown compilation/dependency/model
  identities, capture exclusions and source suppression block clean coverage.
  No rule/context is qualified by default. Exact owner-supplied qualification
  must additionally match dependency, model, compiler options and toolchain.
* Every normalized finding supplies a future `exact_context_recompile`
  Verification with `state="not_run"`. After edits it requires a new sealed
  snapshot and new owner permit, the same pinned compiler/explicit context,
  actual successful compilation and comparable diagnostics with the checker
  still enabled. Suppression, disabled checks, omitted inputs and failed builds
  are not proof of resolution. No Gradle task, shell command or reusable old
  execution permit is invented.
* Synthetic JVM/JDK21 results do not qualify real-world precision, warm latency,
  Windows/macOS portability, Android, Native/iOS, linking or simulator tests.
  CPU/RSS and other unmeasured quantities remain unknown; missing fixtures and
  hosted contexts are gaps, never exclusions or automatic default activation.

Refresh mapping references after implementation changes, without changing any
catalog status/default:

```sh
python3 providers/compiler-fixtures/generate_rule_map.py
```

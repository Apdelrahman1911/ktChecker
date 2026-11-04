# Scanner timing experiment: C source authored, not compiled or run

This describes an authored bounded observational probe, **not** a benchmark
result, compiler qualification, or latency/SLO certificate. C's entrypoint,
schedule, publication branch and 20 value-only control tests are authored but
uncompiled/unrun at this source freeze. A1 instrumentation and B scanner sources
were independently reviewed; that is not execution or performance evidence.
The lead owns central integration, compilation/tests and all JVM scheduling.
Do not run the real probe before that separate review and authorization.

## Two comparable variants

1. **Baseline:** the reviewed common root source freeze plus A1 and C, with the
   original per-file scanner buffer and per-byte digest formatter.
2. **Scanner:** that same common freeze plus A1, B and C. B only moves the existing
   65,536-byte read buffer to scan-local storage and uses equivalent lowercase
   `HexFormat`. Every byte, entry, bound, checkpoint, identity, release reread,
   authority decision, and owned-process lifecycle check remains required.

The common freeze must include the lead's current fixture inventory/selectors,
model/target changes and source-provenance policy, not broad copies from the
isolated base75fc659 worktree. Freeze each actual build's sources and executed
artifacts separately. Matching source files/HEAD do not prove matching bytecode.
Do not edit captured inputs while either invocation is running.

## Authored lifecycle reuse (C; pending central validation)

C uses a **test-host-only entrypoint**, not a new subprocess script:

- A small `CompilerFixtureTimingProbe.kt` entrypoint selects a fixed internal
  `scanner_eight_v1` schedule and delegates to the existing fixture runner. The
  ordinary `CompilerFixtureRunner.main` continues to select the ordinary
  authored-cohort schedule. This is trusted harness code, never input/project
  JSON, execution authority or a new production provider mode.
- The bounded `compilerTimingProbe` JavaExec task sits alongside, not in place
  of, the unchanged `compilerFixtures` task in
  `modules/provider-compiler/build.gradle.kts`. It uses the same explicit
  owner-prepared JDK/classpath setup and bounded host/child heaps. The entrypoint
  accepts only `--variant=instrumented-baseline` or `--variant=scan-buffer-hex`
  and one fixture directory (appended by Gradle). It rejects all cohort/ID,
  budget, retry/resume and unknown options, including otherwise valid ordinary
  selectors. A non-512MiB requested child heap is rejected, not silently ignored.
  The variant is owner metadata, **not** proof of actual source/bytecode identity.
- In `CompilerFixtureRunner.kt`, the **same** private `execute` path handles
  every job: admission/reservation, authored immutable source materialization,
  exact fresh permit, original pre-write output registration, isolated runner,
  actual observer, raw evidence, receipt invariants, quarantine/secure cleanup,
  and immutable job checkpoint. Its A1 body is unchanged by C, not copied into
  a second engine.
  No `ensureEmit` fallback, batching, automatic retry or hidden control job.
- Startup provenance/artifact capture, owner-stop handling, evidence quota,
  final source/artifact checks and `CompilerFixtureTerminalPublication` are
  reused. A separate **probe-schedule** eligibility predicate/receipt is added. Ordinary
  six-kind assessments and transport controls remain not run; never synthesize
  passed assessments, clear their missing-kind ledger, or qualify rules.
- Probe outputs stay in a new private run directory and do **not** replace
  `evidence/compiler-jvm-2.4.21.json` or any ordinary cohort's latest selection.
  The same destination decision gives the evidence store a null external
  sidecar and skips the later external write. Private terminal selectors use
  `probe_complete`; `run_complete` and ordinary-six-kind completion stay false
  even for a completed probe. All rule/scope gaps remain visible.
- C's source boundaries are the runner's schedule/private
  publication branches, the test-host entrypoint/schedule, dedicated unit tests,
  this document and the one Gradle task. No selector controller or production
  provider source is changed by C. The existing `original` selection is retained
  solely for missing-kind inventory bookkeeping, not ordinary scheduling. All
  11 original runner selector sites remain for the lead's finalized ID adapters;
  preserve those adapters and the lead's 98-pair inventory during hunk integration.

The 20 authored tests cover exact schedule/order, first-sample retention, parser
rejection, fixed budgets, no fallback/retry, positive-failure versus clean
semantics, exact attempt/output/sample eligibility, private-only publication,
terminal deadline correction, missing/invalid observations and probe-versus-
six-kind completion. Their synthetic recorder shapes and value predicates are
not actual execution receipts or measured timings. They must still be compiled
and run centrally before any real probe.

## Fixed workload and caps

Use the captured manifest pair for
`ktc.compiler.common.unresolved_reference` / `UNRESOLVED_REFERENCE`, with its
existing `-jvm-target 17` flags. Its positive file calls a missing function; its
corrected negative declares that function. Each compilation has **one** selected
`.kt` source. Captured synthetic build metadata is never executed or compiled.

For **each** variant, run exactly this serial schedule:

| Sample | Source kind |
| --- | --- |
| 1 | positive |
| 2 | corrected clean |
| 3 | positive |
| 4 | corrected clean |
| 5 | positive |
| 6 | corrected clean |
| 7 | positive |
| 8 | corrected clean |

- At most **8 reserved provider attempts** per variant, including refusals. A
  completed probe requires eight actual confirmed compiler executions, not just
  eight calls. No replacement samples after failures or early termination.
  A returned, intact job with a mismatched expected outcome is retained and the
  fixed remaining ordinals may proceed; an execution/persistence/lifecycle
  exception or budget stop ends the schedule without retry. Neither case can
  produce probe completion.
- **60 seconds per job, 300 seconds overall** per variant; startup, admission,
  evidence and terminal publication consume the overall monotonic budget.
  These are cooperative existing bounds, not a new OS hard-kill guarantee.
- **512 MiB compiler heap**, existing **384 MiB fixture-host heap**, **2 MiB
  diagnostic-output cap per job**, and **128 MiB retained evidence per variant**
  including existing 16 MiB job reservation and 8 MiB terminal headroom.
- Timing uses at most **64 total rows**, including open parents; its serialized
  sidecar is capped at **128 KiB** and uses the existing job reservation. Budget
  or persistence failures retain actual evidence and make the probe incomplete.
- Same explicit owner-selected link-free JDK21 home, ordered Kotlin2.4.21
  compiler/dependency artifacts, flags, source bytes, host configuration and
  lifecycle implementation in both variants. Reuse one toolchain object per
  invocation, but every job still launches a fresh compiler JVM as today.
  Snapshot directories, outputs and exact permits are fresh per call; repeated
  identical source bytes can correctly retain equal content-addressed IDs.
- No overlapping heavy work, JDK copies, active-process/profiler probes or
  cache-clearing/teardown tuning. Retain sample1; there is no discarded warmup.
  Run baseline then scanner serially and record that fixed order as a limitation.

The baseline and scanner runs are separate immutable invocations. Their source
and artifact receipts must establish the intended difference; neither run
resumes, merges, or overwrites another. If the overall cap stops a run before
eight confirmed executions, report the retained partial run without filling in
missing samples or launching an unapproved substitute run.

## Completion and evidence, independent of timing

Every job must retain START/input/source identities, fresh permit binding, exact
command, actual stdout/stderr, process/result receipts and cleanup evidence.
Require actual exec confirmation, matching captured supervisor identity, trusted
Kotlin2.4.21 transport, acknowledged owned-group shutdown and verified owned
scratch cleanup. A timing row's normal return is none of those proofs.

- Positive samples must actually emit the exact normalized ID at the sealed
  source point; their intentional nonzero compiler exit/provider failure stays
  a failure, not a clean build or passing coverage certificate.
- Corrected samples must actually exit zero with no selected diagnostic or
  unexplained raw diagnostic. They still are not qualified full-project results.
- Private probe completion requires all eight expected sample outcomes, intact
  attribution/lifecycle receipts, complete timing observations and successful
  terminal publication/harness return. Keep missing ordinary six-kind controls
  visible and no rule qualification. A candidate sidecar alone proves nothing.

`probe-schedule.json` binds the definition to captured manifest sources;
`probe-samples/` receipts reference the existing immutable job index and actual
`timing.json`, keeping actual exit/provider status and provider/raw-runner metrics
separately named. Checkpoints retain sample gaps as well as the unchanged ordinary
missing-kind ledger and null/not-run controls. Only a settled private selector's
`probe_complete=true` **and successful harness return** establish this narrow
completion. Ordinary sidecars are never selected or overwritten by a probe.

## Read the timing data without inventing phases

Keep `provider_reported_elapsed_millis`, `raw_runner_elapsed_millis` and relative
phase intervals separately named. The old `process.json.elapsed_millis` remains
the provider metric. A refusal's zero provider metric is not a zero-cost call;
null raw runner time means no observed runner metric, not zero elapsed.

Fixed labels cover provider preflight, snapshot/JDK/compiler/dependency checks,
source reading, actual wrapped-permit verification occurrences, original output
callback, source/artifact copying, the runner, observer and result normalization.
An ordinary completed legacy call currently has 25 rows and three JDK verification
intervals by source inspection. C validates that legacy shape against the actual
retained A1 rows, including sequence/occurrence/parent and verification binding,
complete clocks/intervals and separately matching provider/raw-runner metrics.
Missing or invalid rows make the probe ineligible; C never inserts expected
rows as measurements. Check actual occurrences rather than manufacture absent rows. Parent
intervals overlap children; summing all rows double-counts work. The runner is
compound supervisor/child/check/copy/lifecycle time, not compiler-only time.
No residual subtraction may be relabeled teardown. The verified-model route,
which this probe does not exercise, retains a compound model-binding interval.

Keep capacity exhaustion, invalid/backwards/failing observation clocks and open
intervals explicit. Incomplete timing cannot erase actual compiler outcomes, and
complete observations (including completely observed failures) cannot establish
successful analysis. Observation clocks never extend existing deadlines.

Report all eight per-variant sample values and simple descriptive min/median/
mean/max, with positive and clean subsets separate. Compare matching ordinals
and kinds while explicitly retaining run-order, OS-cache, host-load and first
sample effects. Eight samples are not an SLO percentile or significance study.

Separately label **source-derived** work counts: the full-image logical bytes
and verification count remain unchanged, while each scan has one 64 KiB scratch
buffer rather than one per file read (including the separate initial release
read). Eliminated allocations/format calls are not measured CPU, physical disk
I/O or latency savings. Do not infer CPU/RSS, child-only compilation time,
semantic-worker warmth, persistent-worker readiness, release qualification or
full-project/model composition from this probe.

## Observed bounded runs — 2026-11-04

Both schedules actually completed eight calls (four positive, four clean), with
25 observed phase rows per call. The [independent raw-evidence audit](audits/scanner-timing-20261104.json)
rehashes the retained inputs/results and recomputes these descriptive medians:

| Provider-reported elapsed time | Instrumented baseline | Scan-local buffer + hex formatter |
| --- | ---: | ---: |
| Positive compiler-error fixture | 4,312.5 ms | 4,276 ms |
| Corrected clean fixture | 5,705 ms | 5,503.5 ms |

This is **not a demonstrated causal speedup or the warm 1–3-second objective**.
There was one unrandomized schedule per variant, a warm filesystem/fixture-host
JVM, and a fresh compiler JVM for every call. The source differences were confined
to the scanner, its tests and mapping source references; the executed runtime JAR
also differed in one native build-provenance argv digest. The native binary and
manifest were identical. Nested verification intervals must not be added to
their parent timings.

The audit retains two unsuccessful preliminary attempts (missing system timing
utility, then stale mapping preflight), neither of which executed a compiler
sample. No sample was discarded. Acceptance latency, first useful finding,
required-review completion, agent-context delivery, contention and complete
process-tree CPU/memory were **not established by this experiment**. Raw closures
remain retained locally, not published or licensed for redistribution by this
summary. Private probe completion never promoted ordinary rule coverage.

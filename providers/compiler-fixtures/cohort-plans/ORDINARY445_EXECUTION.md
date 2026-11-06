# Ordinary current445 fixtures — separate, explicit route

This ADD-only successor executes the **98 existing ordinary positive/negative pairs** under the exact
`kotlin-compiler-2.4.21-common445-u2-v1` mapping. It does not override `CompilerFixtureRunner`'s legacy443
guard, edit the compiler project build, or convert the separately pinned nine-observation route into
ordinary coverage. An authored source plan, a pure unit-test pass and a registry row are not compiler
execution evidence.

## Exact finite schedule

`ordinary445-source-v1.json` pins all196 source files and the exact98 rule/upstream/severity/flag rows.
Lexicographically ordered rule IDs partition into **25 distinct selectors**: `ordinary445-01` through
`ordinary445-25` (24 groups of4, then2). No duplicate rule, omission, dynamic grouping or retry exists.
For each selected rule: positive; negative; configuration; an extra disabled-warning invocation if it
is a warning; explicit language/API version; suppression; moved/two-line-shift regression. One final
requested2.4.20 provider refusal is shared only by that cohort's version assessments. This refusal
receives no execution profile or permit and **does not run an alternate compiler**.

The complete obligations are **607 real compiler invocations +25 pre-runtime refusals =632 provider
calls**. Exact cohort sizes range13–28 calls; the budget is derived from each full schedule, not an
arbitrary call ceiling that truncates it. Each run is isolated and publishes separate receipts. The
route neither resumes nor merges prior runs, and a single cohort never claims full98 completion.

## Prerequisites and pure tests

The owner must approve the native/compiler lane, review this binding and a fresh build, and prepare an
explicit **link-free Linux JDK21**. The compiler remains Kotlin2.4.21, heap512MiB; the harness parent is
384MiB. No host-JDK or alternate-compiler fallback is permitted. Dependency installation/resolution,
compiler execution, and cleanup of a quarantined tree remain owner-controlled operations.

Normal test/check does not invoke this JavaExec runner. Its two unit classes use source/JSON/synthetic
oracle inputs and refusal-before-allocation controls, **not actual compiler emission**:

```bash
./gradlew --offline --no-daemon --max-workers=1 :provider-compiler:test \
  --tests dev.ktchecker.compiler.CompilerOrdinary445FixtureContractTest \
  --tests dev.ktchecker.compiler.CompilerOrdinary445FixtureOracleTest
```

Their synthetic owner-snapshot data is self-contained; a clean checkout does not need an ignored
historical evidence file to run these unit controls. Actual execution separately requires an explicit
fresh owner source snapshot **after all new files and this execution binding are present**. Its exact
schema is `capturedAt/head/dirty/files/aggregate`; every bound source and the binding itself must match
an entry. Only this selected subset is checked. HEAD, dirty and the owner aggregate are retained;
no whole-tree aggregate or source-to-bytecode equivalence is fabricated. Actual loaded class artifacts,
compiler/runtime identity, native resources and source captures are retained independently at START.

## Run exactly one cohort

Supply reviewed values from the owner, not a project self-attestation. `SNAPSHOT` is a repository-relative
`build/evidence/...json` path; both SHA values are full64-hex values externally reviewed by the owner.
The init script is explicit because changing `modules/provider-compiler/build.gradle.kts` would invalidate
the older route's independent pins.

```bash
# Owner supplies these four values, and separately records the exact command/build receipt.
JDK_HOME=/absolute/owner-prepared-link-free-jdk21
BINDING_SHA=<owner-reviewed-ordinary445-execution-v1-sha256>
SNAPSHOT=build/evidence/<fresh-owner-source-snapshot>.json
SNAPSHOT_SHA=<owner-reviewed-source-snapshot-sha256>

./gradlew --offline --no-daemon --max-workers=1 --console=plain \
  --init-script providers/compiler-fixtures/ordinary445.init.gradle.kts \
  :provider-compiler:compilerOrdinary445Fixtures \
  -PcompilerFixtureJavaHome="$JDK_HOME" \
  -PcompilerOrdinary445Consent=ordinary445-serial-fixtures-not-qualification-v1 \
  -PcompilerOrdinary445BindingSha256="$BINDING_SHA" \
  -PcompilerOrdinary445SourceSnapshot="$SNAPSHOT" \
  -PcompilerOrdinary445SourceSnapshotSha256="$SNAPSHOT_SHA" \
  -PcompilerOrdinary445Cohort=ordinary445-01
```

The runner prints its unique private run directory and its `control/stop` path. A bounded ordinary,
non-symlink stop marker there is checked between calls; no process-ID guessing/signalling occurs.
Optional properties can **only lower** `compilerOrdinary445OverallSeconds` (default/max1200),
`compilerOrdinary445PerJobSeconds` (90), or `compilerOrdinary445EvidenceMiB` (64–256, default256).
These are cooperative limits, not an OS hard-kill guarantee, and time spent in Gradle setup is separate.
A timeout is a failed run, never a reason to relabel a missing case or retry inside this schedule.

## Automate all25 without hiding separate receipts

With those reviewed variables and an owner-prepared **new private empty directory** `OUTER_RECEIPTS`,
this Bash recipe invokes every selector serially. Each invocation has its own outer log/exit receipt,
and the runner itself allocates a new immutable job/run receipt tree. Stop immediately on a nonzero
command; inspect its retained failures/quarantine before authorizing further work. Do not infer a full98
pass from a successful prefix or combine a failed prefix with a later retry. The source plan continues
to list all98 obligations even if execution stops.

```bash
set -u
common=(--offline --no-daemon --max-workers=1 --console=plain
  --init-script providers/compiler-fixtures/ordinary445.init.gradle.kts
  :provider-compiler:compilerOrdinary445Fixtures
  "-PcompilerFixtureJavaHome=$JDK_HOME"
  -PcompilerOrdinary445Consent=ordinary445-serial-fixtures-not-qualification-v1
  "-PcompilerOrdinary445BindingSha256=$BINDING_SHA"
  "-PcompilerOrdinary445SourceSnapshot=$SNAPSHOT"
  "-PcompilerOrdinary445SourceSnapshotSha256=$SNAPSHOT_SHA")
for n in {1..25}; do
  printf -v cohort 'ordinary445-%02d' "$n"
  # noclobber: never overwrite a receipt from a prior/overlapping invocation.
  ( set -C; printf '%q ' ./gradlew "${common[@]}" "-PcompilerOrdinary445Cohort=$cohort" \
      > "$OUTER_RECEIPTS/$cohort.command" ) || exit 2
  if ( set -C; ./gradlew "${common[@]}" "-PcompilerOrdinary445Cohort=$cohort" \
      > "$OUTER_RECEIPTS/$cohort.log" 2>&1 ); then status=0; else status=$?; fi
  ( set -C; printf '%s\n' "$status" > "$OUTER_RECEIPTS/$cohort.exit-code" ) || exit 2
  [ "$status" -eq 0 ] || exit "$status"
done
```

An outer log is not a rule receipt. Keep the reviewed command and source/build/binding identities;
verify each run's terminal summary, job indexes and cleanup/provenance receipts separately. This route
has no aggregate importer or global matrix-promotion command. A single current445 cohort result must
never erase other cohorts, all445 mapping IDs, the16 unsupported KMP graphs, or the1665×8 declared scope.

## Assessment and trust boundaries

* Positive evidence requires a real selected stable/upstream diagnostic with exact full-parser/provider
  occurrence multiplicity and sealed point/message provenance. An expected compiler error remains an
  actual nonzero exit and failed provider task; it is not labelled a successful build.
* A negative/disabled control requires actual trusted exit0, no selected emission and **no residual raw
  diagnostic at any severity**. Failed, skipped, unavailable and unexecuted cases cannot prove absence.
* Configuration checks target21 re-emission for errors; warnings require both promoted-error and
  independent clean disabled-warning controls. Version evidence is actual2.4.21 plus explicit
  language/API2.4 and a separately observed pre-runtime2.4.20 refusal.
* Warning suppression must remove the warning only from its suppressed file while a repackaged,
  adjacent unsuppressed control still emits. Hard-error `@Suppress` **diagnostic behavior is unknown**:
  actual invocation measures only the provider's non-complete safety behavior. Its safety result is
  separate from `diagnostic_expected_outcome_matched:null`; null never becomes a diagnostic pass.
  This compiler-only route does not exercise the owner's ktChecker accepted-risk workflow. Accepting
  a finding cannot turn an actual compiler failure into successful coverage.
* Regression requires all selected occurrences to move exactly+2 lines, unchanged messages/severity,
  new source path/digest, new snapshot/analysis key and a unique output directory.
* Runtime stdout/stderr are retained exactly as the Strings exposed by `ProcessOutcome`, UTF8-reencoded
  together with a separately retained full parser inventory and provider residual `rawDiagnostics`.
  The API does not expose invalid native raw-byte arrays; those bytes are **not** claimed retained.
  Invalid UTF8, truncation, timeout, cancellation, mismatched identities or malformed output prevent
  trust/credit. Companion diagnostics remain exact residuals, not selected aliases or hidden output.
* Every actual call receives a fresh sealed source snapshot and exact nonserializable permit with
  pre-write output registration. All observer outcomes/orphans are persisted. Unverified child state
  quarantines scratch; no cleanup is claimed when quiescence is unknown.
* Any persistence/provenance/cleanup/late-publication failure revokes local attribution. Publication is
  one-shot; a stale summary is insufficient without the reconciled ledger, no publication-unconfirmed
  marker and a successful exact outer command. There is no success retry.

Only genuinely settled selected receipts can support a **narrow local synthetic implemented** claim.
Local six-kind `tested` requires every diagnostic expectation; hard-error suppression leaves it unknown.
All `qualified` fields remain false, global implemented/tested/qualified stages stay uncredited, and no
provider activation/certificate/default policy changes. Historical1663×8, separate35/40/40, P6 b7, failed
B-timeout and historical Host493 HOLD are neither imported nor relabelled as these current445 receipts.

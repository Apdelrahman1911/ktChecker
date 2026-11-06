# Preview implementation evidence

This ledger separates **authored CI**, observed tool availability, infrastructure
unit tests, executed provider adapters, real target compile/link/runtime work,
field qualification, and authenticated host-model observation. A workflow file,
catalog mapping, mocked diagnostic, or successful prerequisite probe does not
establish the next category. Release gates in [validation-ci.md](validation-ci.md)
and [PLAN.md](PLAN.md) remain unchanged.

## Integration validation — 2026-11-06

### Later local rounds15–17 — scoped results and retained failures

These separately recorded local observations are **not** a combined current
all-project or hosted-CI pass. Retries do not erase earlier failures or skips.
Receipt paths below are relative to `build/evidence/integration-20261106/`.

| Lane | Actual result and limit | Source binding reported by the receipt |
| --- | --- | --- |
| Round15 CLI / semantic attempt | `round15-cli-semantic-result.json`: CLI **389/389**, zero failures/errors/skips, but combined command exit **1**. Semantic main sources compiled; test compilation **failed**, so **no semantic tests executed**. A missing `flatMap` closing brace in `ObservedSemanticFactsTest.kt` required the owner's one-brace retry repair; prior XML is not current semantic test credit. | `source-snapshot-round15-cli-semantic.json`, snapshot-file SHA-256 `4358f468af6b1a53d8095bdc655af414005c594adabc12466049f1f3611757fa`, `sourceStillMatches:true`. This SHA is of snapshot JSON bytes, **not** a source aggregate. |
| Round15 JVM runtime | `round15-runtime-result.json`: **252 total / 251 passed / one skipped**, zero failures/errors, exit **0**. Included `ProcessOutcomeReportsTest` **29/29** is not additional; `OwnedProcessSessionIntegrationTest` remains skipped. No model producer, library activation or hostile same-user freeze is established. | `source-snapshot-round15-runtime.json`; recorded aggregate `e2bde441d1bfc5f5c9a9de09e318fd309e0bfa31587d8fd80641290ed6638dd6`; source reported unchanged through the test. |
| Round15 iOS fixture / Python controls | `round15-ios-python-tests.json` plus both selected 150-byte summaries: the **same 51 controls** separately under normal Python and **`-O`**, each **OK / exit 0**. Not 102 distinct controls, real iOS compilation or a hosted pass. | The result has no full Python execution-source snapshot. Exactly four adopted pins are corroborated by the **later runtime snapshot**, as listed below; not a full execution closure. |
| Round16 semantic retry / Lint attempt | `round16-lint-semantic-result.json`: semantic **123/123**, including **17** synthetic `ObservedSemanticFactsTest` controls, zero failures/errors/skips. Lint **36 total / 35 passed / one failure / zero errors/skips**: invalid POSIX permission-fixture text, not a passing suite. Combined exit **1**. Semantic controls are not a real observed producer or Native qualification. | `source-snapshot-round16-lint-semantic.json`: file SHA-256 `743927f9883ff5495f25042cf1c607aa4fc6bf5734c5ceab94546c2e3d150568`, aggregate `c46f80dd7714f6e5ecc616099a1b183b34e3536e0a39f2826e5996597f847148`; source reported unchanged. |
| Round17 compiler controls / Lint retry | `round17-ordinary-lint-result.json`: compiler contract **13** + oracle **18** = **31/31** pure controls, **zero ordinary compiler cohorts executed**. Lint **36/36**, including private-attempt writer **12** and `RealLintFixtureTest` **four**; zero failures/errors/skips, command exit **0**. Standalone Lint fixtures/driver controls do **not** establish KMP provider execution. | `source-snapshot-round17-ordinary-lint.json`: file SHA-256 `477ee71d03087ceb44f448977631e012e8c3b7e4b059d66ecac89b901989bdfe`, aggregate `1b5bfb4163e22b5f880d9c28dc7603ff7822299f1beff1d1b1fb52cbb5f69004`; source reported unchanged. |
| Round17 portable source-linkage controls | `round17-linkage-tests.json` and both pinned logs: **35/35** separately normal and **`-O`**, all lines OK, both exit **0**. The source-only filesystem mirror is not an independently verified tracked clean-worktree run; no such verification is claimed here. All coverage stages stay **null**. | Named R3 adoption receipt `integration-round17-linkage-r3-adoption.json`, **1,029 B**, SHA-256 `e4bde221ae84e743f42b5e9113ed1055af55b98cfe06f0dda1888dc89c612d1d`, pins reporter/test/selection/public subset. Its earlier “tests pending” status is historical; the later result supplies execution. The **254-file** public subset is not a current whole-tree snapshot. |
| Bounded round16 Linux bridge validation | `bridge-round16-validation-erdjky2k/RESULT.json`: corrected Python **34** separately normal/`-O`; standalone Node-reporter **35 synthetic controls**; targeted Linux **32/32**, zero failure/cancellation/skip/todo; both selected TypeScript builds exit **0**. The deliberate Node fixture's outer exit **1** with checker **0** is an expected negative control, **not application success**. | Result reports **seven** bridge postimages and **four** compiled targets matching before/after. The same pinned round16 source snapshot is a **context identifier only**: unrelated source later changed and **no whole-tree-unchanged claim** is made. Final seal pins the result. No full reference-host rerun, native Windows/macOS or authenticated-model qualification. |

The bridge's original **4 MiB** harness bound blocked a deliberate **8 MiB + one
byte** negative XML write (34 tests, one harness error, exit **1**); that attempt
is retained, not replaced by the corrected passes. The recorded Node
`MaxListenersExceededWarning` remains in the evidence; it was not suppressed.

For the limited iOS source join, `integration-round15-ios-adoption.json`
(**1,779 B**, SHA-256 `fdc48c2274ca537b06ac83979702c3ef2bf23d2e2e97f84622d3c31ad401bbf8`)
contains these four postimage pins, matched to the later runtime snapshot
(**1,256,490 B**, file SHA-256
`423bf17c8c927c9c50f914260cdfb9ae7d381b939599cc57186f06ebf46acfa9`).
Only these four entries were used; no source payload or full Python execution
closure was verified in this join.

| Adopted iOS source path | Bytes | SHA-256 corroborated by later snapshot |
| --- | ---: | --- |
| `fixtures/kmp/build.gradle.kts` | 7278 | `0b2360d4a30e7193264d848d4ba0313d386fe6b8788a493f8d7afaccb43ed889` |
| `scripts/ci/run_semantic_checks.py` | 43419 | `fc3b81a4e0a7af9970fbaab5270d43a680edde4b231496931184fcfec1d9d453` |
| `scripts/ci/test_ci.py` | 81673 | `29c68f32588a25f008ae8d00ea17c65c90bc100375794ecb203a0f741f331582` |
| `scripts/ci/test_semantic_ci.py` | 131016 | `a47f8def9e03d589a3bc2253aa37c1474719749464cc7da469a5c37e6de03648` |

Exact result JSON byte pins (not newly recomputed source aggregates):

| Result | Bytes | SHA-256 |
| --- | ---: | --- |
| `round15-cli-semantic-result.json` | 15620 | `306e4bbd8fe319e90c08f83bae40d5fce876c02913c3f68af8c2e129784c5f6a` |
| `round15-runtime-result.json` | 3716 | `32fa2ebf3cd2017748a7c5df5f3846d9ddb26f4657bb3afcd1a45aa2f87d09d2` |
| `round15-ios-python-tests.json` | 1006 | `1417c206625f174d4f74c1629e9a855067fa1e06cd7b87e7894c2bae6fb1e5c8` |
| `round16-lint-semantic-result.json` | 4815 | `e3f115c96210902f62c78065d7ce0994412d25d81d85b6b790c220a6848c6385` |
| `round17-ordinary-lint-result.json` | 2783 | `9dfb6ee8b00e365e97e5fcb8bc5bb9b71d4e36ac4eb2ca7db7d70578c6a23663` |
| `round17-linkage-tests.json` | 1013 | `05043baa6d8470a2ff25120a488659c697653ede9a281023ab4ec82ab11b517a` |
| `bridge-round16-validation-erdjky2k/RESULT.json` | 31357 | `1b19ec716262fc95e4518d6c62a6a8023ed5ff4f54fc52f037eb5d867ab10f00` |
| `bridge-round16-validation-erdjky2k/FINAL-SEAL.json` | 1714 | `b6863b5133e4d58b3e3d36382f34e2d9721e2069d8947ac8989bbe6e6cc7ce8f` |

No rule or platform scope changes follow. All **1,665 × eight** obligations,
including **46 excluded rules**, and unknown implemented/tested/qualified stages
remain unchanged. Source linkage, fixture authorship and infrastructure totals
are not per-rule evidence; release, NOTICE/license/provenance and authenticated
host-model holds remain in force.

### Later local round14 receipts and initial PR #2 final workflow states

**Scoped local successors, not a combined current all-project or hosted-CI pass.**
Round14 JVM receipts bind `source-snapshot-round14.json`: dirty HEAD
`a14cab05a330dc785c15239d77d9364b509c72eb`, **4,788 source files**, aggregate
`f361130736f4dbfb1c5b5f8e0a7f7c7e4e4d5149c95cf49ec618c68f0c3b06a7`.
The Gradle receipt reports unchanged source across its invocation. These local
adoptions are not the clean pushed head. The earlier checklist and **12:46:21 UTC**
cutoff below remain historical; newer evidence does not rewrite those outcomes.

| Separate later lane | Actual receipt and limit |
| --- | --- |
| Full local CLI and library-provider unit rosters | `round14-jvm-tests-result.json`: CLI **377/377**, provider-library **154/154**, zero failures/errors/skips; Gradle exit **0**. Included rosters are `OwnerCaptureSessionTest` **11** and `LibrarySemanticProviderFactoryTest` **25**, not additional totals. The round12 controller/semantic results below retain their own source binding. No all-module or rule-delivery claim follows. |
| Python infrastructure controls | `round14-python-r1-results.json` plus each log's final summary: **214/214** separately under normal Python and **`-O`**, both exit **0**; process wall **17.316 / 17.526 s** (unittest **17.083 / 17.332 s**). This is the same four-module control roster in two modes, not 428 distinct controls, genuine Windows execution or a hosted-CI rerun. |
| Fresh local archive inventory | `round14-distribution-archives.json` verifies **44 regular files**, **90,982,595 payload bytes**, in each new ZIP/TAR against the selected installation. ZIP **84,210,381 B**, SHA-256 `76db0dcb3d73b009cbfa71f0e7916d10bbc82eefe3cc53d87a375752c3841fae`; TAR **91,022,336 B**, SHA-256 `90d70cc279ec38c8b903fbe749fd45feff2a43d7e27a33851c8107790036a084`. This POSIX input-verification receipt does not establish source/build provenance, executable correctness, Windows qualification, signing or license clearance. |
| Installed compiler-model worker packaging | `round14-model-worker-distribution-r1.json`: installed/task-output equality and ordinary-JAR isolation verified; **154,413 B**, SHA-256 `d9e58229842a8d10b484d3d32dae8c83a30807fb824d104f3693ea070adaf281`, Java **21** class headers, **34** ordinary JARs checked. Header inspection is not JVM verification or worker/provider execution. |
| Separate actual empty-assertion fact prerequisites | `empty-assertion-fixture-r0/run-summary.json`: Kotlin **2.4.21/JVM**, **5/5 cases / six compiler attempts**, exit **0**; the empty-assertion case checks **37 assertions**. This receipt is explicitly **round12-bound**, not a round14 rerun. It does not invoke the production library provider, execute the represented tests, or qualify a rule/tuple. The earlier **14** unit controls are a different denominator. |
| Android R1 inner-command observation | The separately verified R1 command exited **0** in **68.027 s**, with source membership unchanged. Its Lint XML is **86 B**, contains **no issues**, SHA-256 `671ddae6efb72a95ab3443c40b2e13f8e5b48761d7acf11b831d2896465887e7`; the `androidMain` report has the same recorded bytes/hash. An empty inner report is not a passing normalized positive/NewApi provider case, an output certificate or Android qualification. The original hosted harness failures remain. |

The prior **46-file / roughly 266 MB** distribution evidence remains preserved
separately; it is not overwritten or relabelled as the new 44-file inventory.
Archive and worker receipts were inspected as JSON metadata here, not by opening
or executing their binary contents. Local receipt paths above are relative to
`build/evidence/integration-20261106/`; the Android R1 is a separately retained
inner-command result, not an initial hosted workflow success.

**Initial PR #2 workflows are now final failures.** At
**2026-11-06 13:54:16.596850 UTC**, `pr2-initial-workflow-finals-round14.json`
records all four original workflows **completed / failure**: Implementation
**42219264829**, Planning **42219264832**, Bridges **42219264836**, and Platform
**42219264898**. Their final update times were **12:49:08**, **12:53:35**,
**12:44:21**, and **12:45:39 UTC**, respectively. API head metadata is
`a14cab05a330dc785c15239d77d9364b509c72eb`; it is not a checked-out-merge assertion.
The inspected hosted artifacts' separate actual merge binding
`7b8032973d7d74ca151f8bcab8a99e8747f21e20` below remains unchanged. Local corrections
and passing local unit rosters do not turn these initial workflows into passes;
fresh exact-source hosted successors are still required.

**Bounded benchmark R1 completed; required reviews did not.** The corrected
original link-free-JDK successor retained **two baseline** and **four measured**
trials. All **4/4 measured submissions** had durable admissions, but **all four
reviews remained incomplete; zero required-complete**. The two finding-bearing
trials observed useful findings at **5.741 / 6.424 s**, versus the earlier
**19.05 / 19.18 s** smoke below. Their coverage state was **failed**. Both
zero-finding controls had **unknown** coverage and no useful-clean observation.
Measured patch-to-durable-ACK was **1.907–2.429 s**; submit-only ACK round trips
were **53.209–58.721 ms**. This descriptive improvement spans different source
snapshots and is not a controlled comparison, percentile, throughput/fairness
claim or a successful **1–3 second warm target**. Compiler warmth remains
**unknown**, and no required-complete full-project baseline was established.

`benchmark-owner-session-r1-filtered-summary.json` preserves the selected fields
from `benchmark-owner-session-warm-history-sequential-r1/benchmark-observations.json`,
SHA-256 `38aa8da86548e3b854f410de4e342760541d8d8420abadf9194481d91bc15fe1`.
The separate `.process-metrics.json` receipt records exit **0**, elapsed
**75.360661 s**, CPU **106.322910 s user / 11.227854 s system**, peak
**622,380 KiB**. This is Linux wait accounting for the controller and its waited
children, **not summed concurrent process-tree RSS**. Model-context delivery was
**not attempted**; `T_context` and token counts are unavailable. The original R0
**zero-admission selected-system-JDK refusal** remains preserved and receives no
performance credit; it is not overwritten by the completed R1 smoke.

**Gates remain open.** The complete **1,665 × eight-context** declared obligation,
including **46 excluded IDs**, is unchanged. Current implementation/test/
qualification stages remain unknown; default/advisory/opt-in or disabled rules
are not delivered rules. No model-context delivery, broad rule/tuple or platform
qualification, 1–3 second warm target, owner-license/required-NOTICE/shaded review,
release clearance or current full-CI pass is established by these scoped receipts.
Source-linkage reporting remains selected-source evidence, not standalone release
coverage; local receipts do not substitute for clean-checkout verification. No
held historical execution is authorized or qualified by this update.

### Earlier PR #2 follow-up checklist (12:46:21 UTC observation)

These later receipts do not rewrite the earlier local snapshot below. Public
[draft PR #2](https://github.com/Apdelrahman1911/ktChecker/pull/2) was pushed at head
**`a14cab05a330dc785c15239d77d9364b509c72eb`**. Inspected hosted bridge,
Android, Desktop and iOS artifacts instead bind actual tested merge
**`7b8032973d7d74ca151f8bcab8a99e8747f21e20`**; CI environment records identify
`refs/pull/2/merge`. An API head SHA is not the tested merge. Hosted job statuses
below are the **2026-11-06 12:46:21 UTC** observation, not later final outcomes.

- [x] **Publish the source checkpoint:** PR #2 is public and draft. Source
  publication is not release publication, license clearance or qualification.
- [x] **Rerun three complete local test modules:** fresh round12 passed CLI
  **366/366**, review-controller **55/55**, semantic-facts **106/106**, all with zero
  failures/errors/skips; exit 0, **4 min 55 s**. This includes output-preflight
  **6**, corrected reassessment **12**, integrated cache-history **3**, and
  empty-assertion runner **14** tests. `round12-tests-result.json` pins
  `source-snapshot-round12.json`: base `a14cab0`, source aggregate
  `9a1344f9319677df24fc983133726e9e003c57ab565bda3f3886a32218ac4989`.
  This later source snapshot is not the clean pushed head or a whole-project pass;
  earlier full round9 **359/360** remains a failed historical run, not the current rerun.
- [x] **Retain separate actual Linux Native feasibility:** Kotlin/Native **2.4.21**,
  **`linuxX64`**, core-vocabulary library route, **5/5 cases / six compiler attempts**.
  Four negative cases exited 1, including incompatible expect/actual return types;
  `matched_candidate` is not compatibility. Receipt:
  `native-expect-actual-r0/native-run-summary.json`. Neither unit counts nor this
  library-only run establish framework/link/runtime, custom-rule or tuple qualification.
- [x] **Retain the hosted Desktop result:** [platform run 42219264898](https://github.com/Apdelrahman1911/ktChecker/actions/runs/42219264898)
  records Desktop **8/8**, zero skips, with a successful Desktop job. This small
  target-control result does not make the whole platform workflow pass.
- [x] **Record the actual iOS positive stage, without passing the iOS job:**
  `iosArm64` and `iosSimulatorArm64` compilation/framework linking executed;
  simulator tests passed **8/8**, zero skips, on Kotlin/Native **2.4.21**,
  Xcode **26.4.1** / SDK **26.4**. A separate inventory passed after the generic
  environment probe timed out. Failed negative/semantic gates remain separate below.
- [ ] **Repair and rerun all three hosted bridge jobs without relaxing fail-closed
  gates.** [Bridges run 42219264836](https://github.com/Apdelrahman1911/ktChecker/actions/runs/42219264836)
  failed. Node-reported **pass/fail/skip**: macOS MCP **37/9/3 of 49**, reference
  host **456/88/223 of 767**; Windows MCP **38/19/3 of 60**, reference host
  **403/91/192 of 686**. Linux MCP was **46/0/3 of 49**; its reference-host command
  timed out at **300.078 s**, with no completed test denominator. Keep Node counts
  separate from JUnit; some failure names remain unavailable. Genuine OS execution
  still used synthetic broker/model servers, not actual model-context delivery.
- [ ] **Repair and rerun both Android Lint harness cases.** Direct Android
  compile/host controls passed **8/8**, but `positive` and `new-api` provider cases
  failed with `lint_output_unreliable`, incomplete coverage and no output
  certificate; the expected NewApi finding was not normalized. Direct compilation
  is not passing provider evidence or a successful Android job.
- [ ] **Repair the iOS negative and independent semantic harnesses.** Four
  missing/wrong-actual stages exited 1 with no compiler tasks recorded: this is
  neither expected diagnostic evidence nor proof that invalid code compiled.
  The semantic dependency compiled, but all **five** semantic-plugin cases exited
  **2**, with **zero source visits**; no FIR extraction or iOS job pass is established.
- [ ] **Close remaining hosted evidence gaps:** at the recorded cutoff,
  Implementation/Planning Linux/macOS jobs had failed and Windows jobs were still
  running. The Darwin-consent lane was skipped, not passed. Collect exact-source
  successor results; local corrections do not inherit earlier hosted results.
- [ ] **Keep wider gates open:** unit success is not actual empty-assertion compiler
  execution or rule/tuple qualification; collect that separate execution receipt.
  Later unrun source additions receive no pass credit. Owner-license, required-NOTICE
  and shaded-dependency review remain unresolved; no release clearance is claimed.

Local receipt paths above are relative to `build/evidence/integration-20261106/`.
Hosted sanitized JSON binds bridge artifacts **13502582932 / 13502443043 /
13503225985**, Android/Desktop **13502359211 / 13502767156**, and iOS
**13503126632**; archive/member pins are retained separately. No raw hosted logs
or archives were read by this documentation reviewer.

### Earlier local snapshot (before the PR #2 follow-up)

**Local integration evidence, not a qualified release or a combined final-source
pass.** Named integration receipts are retained locally under
`build/evidence/integration-20261106/` in the isolated integration checkout. Runs span successive reviewed source
adoptions (`integration-round*.json`); the initial `source-snapshot.json` predates
later corrections. `source-snapshot-round7.json` records a later dirty tree, not
retroactive source identity for earlier commands or authenticated hosted CI.

| Separate local lane | Observed result and limit |
| --- | --- |
| JVM unit/infrastructure controls | Initial contracts **9/9**, config **102/102**, snapshot **47/47**; later semantic facts **80/80** and provider-library **129/129**, zero failures/errors/skips in those rosters. The initial command still exited 1 after host OOM; kernel had no completed XML and CLI was not reached (`core-first-result.json`). The semantic successor has passing XML and `BUILD SUCCESSFUL` at 63 s, but its final parent-shell exit was unavailable after continuation (`semantic-boxing-r2-result.json`). These are separate lanes, not a full current all-module result. |
| Provider/model/audit controls | `providers-current-result.json`: **1,611 default tests: 1,588 passed, 23 explicitly skipped, zero failures/errors**, across 12 modules; Gradle exit 0. Separately executed compiler worker supplement **58/58**, zero skips (`providers-worker-current-result.json`). Skipped and synthetic controls are not target execution or qualification. |
| Kernel/CLI follow-up | Latest full kernel **256/256**, zero skips, including the concurrent-policy regression; full CLI still **359/360**, one policy/input-pair expectation failure and no skips (`kernel-cli-round9-result.json`). The combined command exited 1. A test-only successor uses the checked ConfigStore reassessment pair on the same raw result; no passing successor/full-CLI result is credited here. MagicNumber remains unqualified and kernel completion/refusal gates are unchanged. Earlier kernel **253/254**, **254/255** and the original CLI failure remain retained; synthetic qualified-fixture kernel reassessment success is separately covered. |
| Actual FIR Int-literal fixture | Kotlin **2.4.21/JVM** successor **4/4 cases**, **five actual compiler attempts**, including positive 0/1/Int.MAX_VALUE and signed-boundary guards (`direct-int-literal-r2/summary.json`). Three expected compiler-rejection cases remain rejected/incomplete, not successful compilations. The original **3/4** failed fixture is retained separately in `direct-int-literal/`. **Zero rule/tuple qualification** from either fixture. |
| Python controls | Wire profile **7/7**, Compose reporter **52/52**, junction reporter **79/79**, prepare-only consent **57/57**, benchmark controls **42/42**, each under normal Python and `-O`. These are separate, potentially overlapping control rosters, not additive rule/corpus/OS qualification. See `python-*.log`, `test_run_preview_example-consent-r0*.log`, and `test_preview_benchmark-consent-r0*.log`. |
| TypeScript synthetic controls | MCP corrected **46/46** after retained **35/46**; reference-host focus **55/55**. Corrected full reference-host suite **897/897**, zero skips, exit 0 (**350 s**, `reference-host-ack-r1-full.log`), after the retained **886/893** failure and separate **38/38** ACK-diagnostic focus. These synthetic controls are not authenticated model-host delivery. |
| Workflow source controls | Root reports the separately author-executed corrected controls **92/92**, normal Python and `-O`; reviewed source adoption is recorded in `integration-round10.json`. The original `workflow-ci-round6.log` remains **89/92**, three exact source/action-roster failures. Author control execution and workflow source adoption are not a hosted-CI pass. |
| Fresh local distribution and real broker | `:cli:installDist :cli:distZip :cli:distTar` succeeded (**34 s**); explicit freshly installed-path JVM↔TypeScript broker checks passed **3/3**, zero skips (**11.22 s**, `mcp-real-broker-test.log`). `distribution-archives.json` verifies **44 files in each ZIP/TAR** against that installation. This is local interoperability/archive equality, not model delivery, final-source reproducibility or platform/release qualification. |

**Bounded benchmark, not the warm 1–3 second target.**
`benchmark-warm-history-sequential/benchmark-observations.json` retains two baseline
and **four measured one-file patches**, two clients, one worker, sequential
persistent-broker history, one selected build-depth rule and partial compilations.
All four measured reviews remained **incomplete; zero required-complete**. Two
finding cases produced useful findings at **19.05/19.18 s**; both zero-finding
cases had unknown coverage, not successful clean assessments. Submit ACK round
trips were **55.49–64.51 ms**, whereas patch-to-ACK was **14.32–14.81 s**.
The whole command exited 0 in **144.62 s**, with **160.84 s user / 19.34 s system**
and reported peak **602,272 KiB** (`benchmark-warm-history-sequential.process-metrics.json`);
this waited-process accounting is not summed concurrent process-tree RSS.
Compiler warmth is unknown, full-project warmth is not established, and
**model-context delivery was not attempted**. Consent controls above postdate this
measurement and do not retroactively bind it to their changed source.

**Declared inventory is not known current coverage.** The root-executed declared
ledger at `build/declared-coverage-1665-v1/inventory-20261106-round7.json` records
**1,665 records × eight contexts = 13,320 rows**, with **46 explicit exclusions**.
Root reports a **valid declared ledger but invalid current-coverage inventory**:
current coverage and implementation/test/qualification stages are **unknown,
not zero**. Command exit 0 is not coverage qualification; the earlier
1,663-record strict inventories below retain only their historical source scope.

**Packaging is not license clearance.** The actual dependency manifest,
`package-report.json`, CycloneDX `distribution.cdx.json` and dependency NOTICE
report were produced; inventory passed, but declared-license/required-NOTICE and
shaded-dependency review remain unresolved, as does the owner's original-code
license choice. Report-generation exit 0 does not clear those blockers.
`doctor.json` still reports **preview / `releaseQualified=false`**. No signing,
release publication, current-host model acceptance, current hosted-CI success,
broad rule/context qualification, or Android/iOS/Desktop/Native target
qualification is claimed by this update. These checks do not promote coverage
stages or relax any runtime, policy, delivery or release gate.

**Earlier dated sections below remain historical checkpoints**, with their
original source bindings, failures and qualification limits; their results are
not transferred to this integration tree.

## Original default35 compiler breadth execution — 2026-11-05 (incomplete)

**Independent readback is complete; the actual cohort is not: outer/host exit 1,
Gradle `:provider-compiler:compilerFixtures` FAILED.** Exact original command/result:
`build/ci-evidence/compiler-diagnostic-breadth-default-first-20261105-r1/`.
The failure remains `Diagnostic breadth remains incomplete`, not an accepted pass,
publication error or budget stop. No retry, resume, merge or later cohort is included.

- **35 reservations/returned jobs; 34 confirmed child executions; 30 input receipts.**
  The explicit Kotlin 2.4.20 denial had no child. Four unchanged controls matched.
  **25 known oracle matches** (ten expected ERRORs, 15 alternatives), **five null
  suppression expectations**, zero unexpected known mismatches. **13 input compiler/
  provider tasks failed** (15 including controls); matching an expected diagnostic
  does not turn failed compilation into successful compilation.
- Suppression attempts **21/23 exited 2 with code-generation crashes**, retaining
  71/58 residual info rows; **22 exited 1** with an unmapped operator error;
  **24/25 exited 0 but remained partial with `ERROR_SUPPRESSION` warnings**.
  All five remain unknown/unvalidated. The unmapped `INAPPLICABLE_OPERATOR_MODIFIER`
  companions in attempts 2/27 also remain explicit, with no invented rule or span.
- Independent DATA review checked **75 checkpoints (69 light/six full)** and
  **30,509 reference occurrences**. Cleanup receipts are bounded original
  observations, not fresh process-absence probes. All **98 ordinary pairs,
  16 unsupported graphs, 443 mappings and 100 declarations** remain visible
  (90 paths plus ten option-off reuses; 30 selected, 70 unselected—not excluded).
- Root before/after records **4,629 files** at
  `56f4f44f9fcfaf9d295ab57b646e1520cc395d0808077fcfb1b79224f3301f46`.
  The reviewer matched **488 START sources** and actual role/JDK/compiler/native
  artifact custody, not the whole 4,629-file aggregate or source-to-binary reproducibility.
  Separate **77/77 focused controls plus 39/39 coverage controls** are not cohort credit.

The original `root-result.json` retains its empty run-marker array/pending-readback
field. Independent `OUTER-READBACK.json` joins both markers from that same stdout;
no “latest” discovery or rewrite was used. Readback packet:
`/tmp/ktc-default35-independent-readback-r1-20261105-1sjd197b`, `READBACK.json` SHA-256
`41923eb1e406b382727e7be7289872417bf0f05142b1896bb36465d11c4e2eef`.

Run-local stages remain **0/0/0**; the separate last strict global inventory remains
**two implemented / zero tested / zero qualified, all defaults false**. Full scope
is still **1,663 × eight = 13,304 cells**. No clean/full-project, installed-image,
Native, performance or release qualification; the current image remains
**NOT-YET-VERIFIED**. Separate 35/40/40 schedules are not pooled. Upcoming eight-path
adoption/tests and any later execution remain outside this receipt.

## Later source-bound controls and current inventory — 2026-11-05

**Source adoption, executed controls and rule qualification remain distinct.**
These separate receipts are not a combined current-source graph pass, authentic
host-model acceptance, fresh package or release qualification. Earlier failures
and source bindings remain intact. Paths below are under `build/ci-evidence/`.

| Retained receipt | Actual result and limit |
| --- | --- |
| `registry-refresh-jvm35-20261105-r1/root-result.json`; `registry-coverage-python-controls-20261105-r1/root-summary.json` | Corrected **35/35 JVM + 34/34 Python breadth controls = 69/69**, plus separate **39/39 coverage-bookkeeping controls**. Each fresh lane exited 0 with zero failures/errors/skips and exact method rosters. Synthetic/source controls, **not a compiler provider cohort**; no old pass was transferred. |
| `current-coverage-successor-20261105-r1/root-result.json` and `inventory.json` | A **fresh strict-wrapper execution**, exit 0, **6.739 s**, valid inventory: **1,663 × eight = 13,304 rows; 11,448 mapped, two implemented, zero tested, zero qualified**. Independent readback verified the exact **73-input / 47-reference** closure and all **450 withdrawn provider cells** with zero current stages. Runtime defaults and qualification aggregates remain false; this is accounting, not provider execution. |
| `light3-local-declaration-use-20261105-r1/outer-exit.json` and `command.json` | First three-rule LIGHT cohort **failed**, exit 1, with the recorded **60-second child timeout**: **11 invocations reserved, ten checked, seven native execution confirmations**. The ceiling was 39, **not 39 completed**. Run completion/publication remained false; no retry or cleanup proof is claimed. |
| `breadth-r2-python-explicit34-20261105-r1/EVIDENCE-READBACK.json` | Original Python controls **32/34**, exit 1: a literal checkpoint-count assertion and an authority-name lexical false positive. With the separately executed original JVM **35/35**, the original breadth result remains **67/69**, not a pass; neither failure was overwritten by the corrected lanes. |
| `astra-combined-jvm-controls-20261105-r1/root-verification.json` | **146/146 selected JVM methods**, zero failures/errors/skips; wrapper and Gradle exit 0. Includes the original 35 breadth controls. Real infrastructure with synthetic providers/fixtures, not complete module suites or genuine compiler/model execution. |
| `detekt-full-seven-20261105-r1/root-verification.json` | **Seven original Detekt FULL outcomes**, all exit 0, findings **[2, 0, 0, 0, 1, 1, 1]**; **1/1 JUnit** passed. Actual Detekt 2.0.0-alpha.6 over a **synthetic Kotlin 2.4.10 model**, not an authentic exported model or execution of its modeled compiler. Independent readback did not rerun it; child rule rows remain untested/unqualified. |

The corrected controls and strict inventory bind 4,628 unchanged source files at
`fbc4d75ee7b6f5028a233d26305e86954a808b5df5536b28fb3372dc049a5a6a`,
following the reviewed six-path `registry-coverage-adoption-20261105-r1/adoption.json`.
Earlier JVM146 and LIGHT bind
`d727c1f1b4c65bce927b7097561b8ee74086830412eb4d9095da91bcfb3f4389`;
the original Python lane pins its own 429-file closure and records that
root-reported full-source identity. FULL7 instead binds 4,519 files at
`00a8fa517bd0fb74674f7bae3cb064d0e2aa3ca29109b80c8b144e49cb71fa5c`.
The fresh inventory is **50,535,057 bytes**, SHA-256
`5a962e37b7a1467e6c25e6c8fed8f85f3decf5fae12094af8fe994b7dbbe8e95`;
its observed counts are not inherited from the predecessor selector. Later edits
and the subsequent planned genuine compiler cohort are **outside this checkpoint**.

Hosted macOS failures stay separate: [implementation
42052349694](https://github.com/Apdelrahman1911/ktChecker/actions/runs/42052349694)
records **seven failures across three importer/model suites**, not a whole-job
denominator; [planning 42052349836](https://github.com/Apdelrahman1911/ktChecker/actions/runs/42052349836)
records **1,318 cases: 1,271 passed, nine failed, two errors, 36 skipped**;
[bridges 42052349834](https://github.com/Apdelrahman1911/ktChecker/actions/runs/42052349834)
records **MCP 16/20 passed, four failed**, and **reference host 127/181 passed,
45 failed, nine skipped**. Two reference-host failure names remain unavailable.
These PR reports tested merge
`f0b85da7890d3c98c5fd8552a360a0627767a188`, not merely API head
`0751941aee98ed1761eb247ad3dd528bbad915c4` or current local corrections.
The [manual Darwin run 42052589522](https://github.com/Apdelrahman1911/ktChecker/actions/runs/42052589522)
is separately bound to that head: `owned_command_failed`, unverified, native
controls attempted but executed count **unknown**, not zero or the planned count.
Only permitted sanitized observations were read; no denied raw logs or held
materialization/native investigation was opened. No fresh macOS success is claimed.

Sanitized hosted observation pins: `FAILURES.json`
`754bbca698a3342e9f327a93b89e3852bedc7a7ce32bedc5a2afa66bd6f56aad`;
`SOURCE-TUPLE.json`
`75690a0bdf6181be0bea7f760d180932bf137573d1dc7dfc1e4dc814bfd384b0`.

## Focused controls and exact hosted observations — 2026-11-05

The [focused update](evidence/focused-controls-20261105.json) keeps receipt hashes,
source identities and separate denominators. **This is not a combined current-source
graph pass, new distribution, live-model acceptance or release qualification.**
The full scope remains **1,663 × eight = 13,304 cells: two implemented, zero tested,
zero qualified**; this update does not regenerate or promote that ledger.

| Separate local lane | Actual result and limit |
| --- | --- |
| Owner lineage/custody/policy plus Detekt FULL controls | **101/101 methods** (62 owner + 39 FULL), zero failures/errors/skips; Gradle and outer exit 0, **70.030 s**. Source digest `1b86872eb55e0c7c5a6ff469ac709f860bda7c17af661df5d03b4f1039c76308` stayed unchanged. **Zero real FULL-engine or model/host invocations**; not semantic-provider or full-graph acceptance. |
| Earlier config/kernel lane | **3/3 methods passed**, **2.487 s JUnit**, with actual ConfigStore/SQLite/kernel and synthetic providers. The larger lane **failed** during FULL test-source compilation; Gradle exit 1 and recorded wrapper exit 120 remain distinct. Its 39 FULL controls did not run. |
| Inert packaging helpers | **30/30 methods, 146/146 subtests**, zero guard violations; outer exit 0, **0.401 s**. Separate reviewed helper-source packet only: **no actual packaging, installed-image, provider or model execution**. Ordinary reads were allowed; no hostile-code sandbox is claimed. |
| Expanded current-image host negative controls | Selection remains **56 top-level controls**, separate from earlier host receipts. R1 compilation failed before tests. R2 compiled but reported **262 passing Node outcomes and two OOM file-process failures** at 256 MiB. R3 compiled at 512 MiB but **still failed**, with **297 Node-reported outcomes: 296 pass, one OOM file failure**, **165.216 s**. Its JUnit contains **289 testcase elements: 288 pass, one failure**, plus **eight parent containers**—not 297 testcase elements. Neither failed lane proves 56/56 execution or a suite pass. |

The owner lane's earlier zero-test harness failure is also retained. These runs
use distinct source identities and do not add together; whole-command durations
are not analysis latency or the warm useful-result target.

[Hosted Linux run 42052349694](https://github.com/Apdelrahman1911/ktChecker/actions/runs/42052349694)
actually tested merge **`f0b85da7890d3c98c5fd8552a360a0627767a188`**, not API head
`0751941aee98ed1761eb247ad3dd528bbad915c4`. Its retained sanitized summaries contain
**1,466 testcase occurrences: 1,461 passed, three failed, two skipped**. The job
failed and evidence is incomplete; all three failures belong to the held
`ModelLiveMaterializationTest` suite. No failure cause or new investigation is
inferred from case names. No hosted raw logs or original hosted report bodies were opened for
this update.

[Exact-commit Darwin run 42052589522](https://github.com/Apdelrahman1911/ktChecker/actions/runs/42052589522)
retains a failed, unverified component summary for **`0751941aee98ed1761eb247ad3dd528bbad915c4`**.
Native controls were attempted; the executed count is **unknown**, not zero or the
16 expected cases. Default Darwin enablement remains false; this is not native,
Apple-target or platform qualification.

A separate Temurin 21.0.13+9 Linux x64 JDK archive was downloaded and checksum
verified (**207,858,352 bytes, 10.203 s**); it was **not extracted or executed** at
this cutoff. That prerequisite is not engine/model evidence or signature verification.

No result is included for the subsequently planned canonical/exporter controls.
FIR container observation's **22** controls remain source-only with review pending;
the benchmark collector's **34 new** controls are unexecuted with review corrections
pending. The real paired bridge remains work in progress. No real host-context/token
or benchmark measurement, runtime-default change, license choice, binary publication
or fresh package is established. Earlier checkpoints retain their own bindings.

## Parallel implementation checkpoint — 2026-11-05

The [machine-readable checkpoint](evidence/parallel-checkpoint-20261105.json)
binds these **separate scoped executions**, their source identities, receipt
hashes and prior-commit GitHub Actions URLs. They are not a combined release
test pass or a new runnable distribution.

| Executed lane | Verified result | Boundary |
| --- | --- | --- |
| Affected Kotlin compilation | Exit 0, **77.104 s** | Corrected two retained compilation failures; no tests or real owner/model run in this lane. |
| Kernel and selected owner/model/corpus controls | **380/380**, zero failures/errors/skips, **377.945 s** | Kernel 189, project-model 10, compiler 6, CLI 121, corpus 54. Real SQLite/files/threads; synthetic providers and authorities, not the full test graph. |
| Host bridge build and selected controls | Both TypeScript builds passed; **56/56** controls | Fourteen parent/projection and 42 delivery regressions. **Zero actual model requests**. |
| Expanded Detekt corpus controls | **67/67**, zero failures/errors/skips, **61.086 s** | Includes the previous 54 plus 13 observability controls; no provider execution in this control lane. |
| Real protocol/catch cohort | **27 analyses, 37 calls, 28 native confirmations, 31 callbacks, 113 events**, **103.947 s** | Three rules, nine authored cases each, 1/1 JUnit; 585 evidence references rehashed. |
| Real exception-observability cohort | **27 analyses, 37 calls, 28 native confirmations, 31 callbacks, 113 events**, **104.144 s** | Three rules, nine authored cases each, 1/1 JUnit; 585 evidence references rehashed. |
| Windows distribution-observation correction, private Linux controls | **80 methods / 199 synthetic subtests**, zero failures/errors/skips | Independently reviewed source; Windows API/buffer/handle seams mocked. Actual Windows execution remains pending. |

The two real cohorts use unchanged source
`6869fa11e5e5fb43c25daa058c28b69ff3730a3031a52933ec6ac2c507ad09a9`.
They exercise actual Detekt 2.0.0-alpha.6 on **Linux/JVM LIGHT synthetic fixtures**,
including configured negatives, suppressions and explicitly documented heuristic
false positives/misses. Matching those expected predicates is **not** measured
real-world precision. The declared/compiler 2.4.21 versus embedded 2.4.10 parser
boundary remains unqualified. Each lane preserves all 22 authored rule routes and
199 analyses while marking its 19 unexecuted rule rows `not_run`; it does not claim
to execute that entire inventory. The initial protocol evidence-reader failure
is retained; only its path interpretation was corrected, without rerunning the
provider.

The Windows correction retains full native file identity and no-follow/failure
guards instead of assuming identical POSIX-style path/descriptor metadata
encodings. Its first private runner stopped at a Linux standard-library import
guard before any test method; that zero-method failure is retained. A separately
reviewed runner correction passed the 80 controls without real Windows/native-ABI
execution. This supports adoption, not a proven hosted Windows failure cause.

The current source adds a nominal observed-export path, produced-output/owner
composition and parent-event/projection controls. Observed exports retain the
permanent `configuration_input_closure_unproven` gap and cannot grant complete
project coverage. The adopted owner command is still an interim one-parent path;
fresh explicit compiler-policy configuration and same-lineage patch→fix host
acceptance remain unfinished. No review silently enables or disables rules.

All four inspected Actions runs for prior head `663113117bb3784c1a7638ac8282e64beabe59d0`
(actual tested merge `295b716498200f27415bc02cac7218bd8c7e2143`) ended in failure.
Linux/macOS planning and Linux bridges passed their individual jobs; those do
not qualify Windows, macOS implementation or iOS target checks. Step metadata
alone does not establish their failure causes. An explicit consent/exact-commit
Darwin component profile is now authored in the indexed platform workflow;
**dispatch and native success are not claimed by this checkpoint**.

The full **1,663-rule release scope remains unchanged**. The last strict global
ledger still records 13,304 rule/context cells, two implemented, zero tested and
zero qualified; this checkpoint does not regenerate or promote it. Fresh host
delivery, platform qualification, general full/incremental equivalence, broad
per-rule reliability, fresh packaging/license approval and the warm 1–3-second
useful-result target remain open.

## Later focused receipts and retained failures — 2026-11-05

**These are separate source-bound observations, not an aggregate pass, a full
current-source graph, or a new runnable distribution.** Paths below are under
`build/ci-evidence/` in the isolated integration checkout. SHA-256 source digests
identify dirty-tree bytes, not just the common base commit `97810cc732edc6b2d24d5dd8038f9085aca237d1`.

| Receipt | Actual scoped result |
| --- | --- |
| `focused68-20261105-r3/root-verification.json` | **68/68 methods passed**, zero failures/skips, exit 0; includes three config baseline regressions. CLI helpers compiled; synthetic authorities, not genuine provider/host-model execution or the full graph. |
| `bridges-integration-20261105-r2/summary.json` | MCP **17/17** and reference host **165/165**, zero failures/skips, both exit 0. No actual model request or host-model observation. |
| `connected51-focused-20261105-r2/root-verification.json` — retained predecessor | **28 passed, two failed, 21 unexecuted**, zero skipped; 30/51 executed, exit 1. Production compilation failed for a missing `liveCheck` import; CLI helpers were not reached. The two wire-test failures are not erased by R3. |
| `detekt-iteration-cohort-20261105-r1/root-verification.json` | **28 detector analyses, 29 native confirmations, 38 calls, 116 events; 1/1 JUnit**, zero failures/skips, exit 0. Root rehashed 515 references. Actual synthetic Linux/JVM LIGHT cohort only; seven approved-not-run rules, heuristic gaps and the declared/embedded parser boundary remain unqualified. |
| `native5-adoption-20261105-asw8nsvo/linux22-root-readback.json` | CI author's original **22 Linux controls passed** (four builder metadata, 18 owned-child runtime), zero failures/skips. Root verified five-file adopted source matching, not a new whole-tree run or macOS qualification; root did not rerun them. |

R3 and both TS runs share unchanged source
`128590862b3cbcaf9ccac2a7f6f17a2a31717a635eb9c9ab9645ff3d5f121b4f`;
retained R2 used `5c9f7fc07d4bf4070f3978a9ccac4214e3ab28fbd17ee7417fa0b6a6bcaa1a15`.
Detekt used unchanged source `379600c0becf37dfd1dfad26befd9f0797520711f817a53acc9ad797bee10a57`;
that is also the native adoption postimage, **not** a blanket Linux22 execution binding.
Receipt SHA-256 pins in table order:
`4bb90b489da491e617b82029dca29e14e80ad9bad1f78bd6d2c7f6c3a99dbaf8`,
`2a3678b92ec75af5364514c141a40acc3d7d680e78f89dee633d3a2792936e97`,
`e7501229b9df83ebde1caa95677795f83dd55827eb57bdbdbc9f1d4c3d36b8ae`,
`99beed21e2bdb1f56549dabac1053f86fbf4ddaa550fd903c7b7b5b5993c91bf`,
`c886c64f62125a3861876a793d710cdf392a21516578bd31abc0ecbd933b326f`.

The retained hosted PR1 observation remains **12 jobs: two success, ten failure**
at actual reported merge checkout `87d9f0ac2e3a0b4a65fa634899de289dc181491c`,
not its API head or later local fixes. Windows timeout/final-source gaps remain.
Sanitized `hosted-ci-evidence-summary-r1.json` SHA-256:
`bcf2b09b344789e381d0070dff2ef43cf01f3862c534a1cdcb4cc05f743a7cbf`.

The later metadata-only `current-coverage-97810cc-20261105-independent-r2-kWi8t6Oq/summary.json`
(SHA-256 `61a37df9838e51c12dfaa3e78dd809902315f49a52726e1e302ef683190ce24e`)
retains **1,663 × eight = 13,304 rows; two implemented, zero tested, zero qualified**
at source `6bf2db663bdf26c5b59221320a92597c6d1d354c6f1ddc189af1a771c02aa206`.
All runtime defaults and qualification aggregates remain false. No coverage credit
transfers across these tuples; no production runtime/model, platform/ABI, full
native-supervision or release qualification is claimed. The older installation
and all earlier failed, incomplete, unavailable and unexecuted evidence remain.

## Current-source addendum — 2026-11-05

**These later receipts do not establish a complete current-source graph or a new
packaged preview.** The separate runs below used **4,286 files**, source SHA-256
`b1eccb76d7e7f60f1c451feb2ae68d50ba1ecd80adeea0948b618d66721cf589`,
unchanged before/after. Paths beginning `build/` are relative to the isolated
integration checkout; earlier entries retain their original source identities.

| Later local receipt under `build/ci-evidence/` | Actual result and boundary |
| --- | --- |
| `preview-current-graph-low-resource-20261105-r1/summary.json` | **Failed, exit 1, 15.423 s**: init-script compatibility failure before tests; zero cases and **0/24** successful required-task terminals. |
| `preview-current-graph-low-resource-20261105-r2/summary.json` | **Failed, exit 1, 267.520 s**: compilation progressed, but the kernel confirmed Gradle daemon **3668** was OOM-killed at **09:31:04 UTC**. Only **58 fresh audit cases** passed (six reports; zero failures/errors/skips) and **2/24** required-task terminals succeeded. This is not a 22-test-task-plus-two-auxiliary-task graph pass. |
| `ci-python-current-20261105-r1/verification-summary.json` | One complete local Python discovery: **933 methods discovered, started and completed; 932 passed, one skipped, zero failures/errors**. Reporter child exit 0 in **189.406 s**; launcher observation **191.991 s**, with actual settled outer exit 0 separately observed. No successful-subtest denominator is available. |

Both failed JVM directories retain `outer-exit-observation.json`: root observed
and drained their actual exit 1, not merely a failed summary. The two R2 successful
terminals are `:audit:test` and `:cli:compileTestKotlin`; a started CLI-test marker
does not supply missing terminal/JUnit evidence. Original-install inventories
match and integration-default absence is retained; descendant settlement is not
established. Python's one Linux-host skip remains visible; its settled launcher
observer is `ktchecker-current-ci-discovery-run-20261105-wd1kUsgC/settled-launcher-observer.json`,
SHA-256 `5f9fab531bd30eaecc79f3b02ee2553d172e300db98dc5162dc3dcb17d37c147`.
These are local controls, not hosted/model/provider or rule qualification.

The subsequent sole-file `RealModelFixture.kt` expected-label correction was
adopted at **4,286 files**, source SHA-256
`6710b926cdafe3b9b52525beeceb7899e1fed74e838e4bce9fc64c6c62442ee4`:
**uncompiled and unexecuted at adoption**. That snapshot, later CI changes and this
addendum inherit no older graph, TS163, package or doctor pass.

Metadata-only `current-portable-coverage-b1ecc-20261105-r1/summary.json` still
records **1,663 IDs × eight contexts = 13,304 cells: two implemented, zero tested,
zero qualified**. It grants zero new stage credit; the large report was not reread
for this addendum. Default activation is unchanged; broad Unicode remains
unverified. The older doctor-runnable installation and all earlier failures below
remain retained, not silently rebuilt or promoted.

Summary SHA-256 pins, in table order:
`079411fa6c42b7b761ab28b50848080e6e20167335794b5dfa25e3b5cba66e5c`,
`ae64eaf8b4cd8aa2e3935b6ca1879500d07760df630f6d71d955d06dbb6d6faf`,
`bb3129e8c64ccc228127e5446c13953efd91c50a5eed8f54562471d8e0e0c27c`.

<a id="latest-preview-handoff-2026-11-05"></a>

## Latest local handoff — 2026-11-05, unqualified preview

**Current rule/context accounting: 13,304 cells; two implemented, zero tested and
zero qualified.** The [later valid gap inventory](#current-rule-coverage-2026-11-05)
is not rule qualification. Local JVM, packaging and API controls below do not
replace per-rule evidence.

The ordinary JVM graph and fresh packaging command below used base
`5e00407ca5a2a31c371d471de3d4216a18fdd760` plus **4,237 captured files in a dirty checkout**,
SHA-256 `9dc3d42cdd10579727d7e72f79a6637272afc94bf7036a162d05ec6aaf99f722`.
The graph's before/after sources match. These receipts do not cover later source
packets or this documentation successor; the existing distribution embeds the
earlier contract, not this new status text. Counts describe separate runs and
must not be added together.

Paths beginning `build/` in this checkpoint are relative to the isolated
`build/checkpoints/integration-5e00407-20261104` checkout. Commands/logs and owner
state stay private; the [usage guide](implementation-usage.md) selects the new
installation explicitly rather than overwriting or using the preserved old one.

| Retained receipt | Observed result | Boundary |
| --- | --- | --- |
| `build/ci-evidence/preview-current-graph-20261105-r2/summary.json` | **22/22 required test tasks EXECUTED; 127 JUnit reports; 1,774 occurrences: 1,768 passed, six skipped, zero failures/errors**. Command exit 0, **276.751 s**. | Ordinary local Linux/JVM graph, including the model wire/worker and 83 B1 controls; not a new full-rule, live-model, platform or release qualification. The separately recorded 83-method run below is not additional coverage. |
| `build/preview-handoffs/ktchecker-20261105-f909k8w0/evidence/preview-package-command.json` | **11/11 required packaging tasks EXECUTED**, exit 0, **83.576 s**; new staged install and ZIP/TAR produced. | Actual compilation/packaging, not tests or analysis. The original packaging harness has no completion summary; later postvalidation is a separate observation, not a retroactive pass for that harness. |
| B1 CI correction R2 `receipts/verification-summary.json` | **19/19 synthetic methods and 166/166 subtests passed**, zero failures/errors/skips, seven external-operation guards zero; one **0.717275 s** run. | Privately tested CI-wiring successor, subsequently adopted; it requires B1's executed task and four exact reports. No hosted workflow, real reviewed-project wrapper, model or compiler execution by these controls. |

Receipt SHA-256 pins, in table order:
`6c1fbc408ccaef6892f22839bb2311a6849009108351c287e37429e4ec25b55a`,
`b05ca7734ad07fa55d5878947071ddfa3825a1e88528ea30665251e7ead55a3b`,
`5f6675a02aebfbf91f8563124d1e962384cd36fa188eded88d03463a73c26289`.
The B1 packet is `ktchecker-b1-ci-correction-r2-20261105-__iehsru`.

The six ordinary-graph skips are **one Windows junction control, two real Detekt
opt-ins, two real Ktlint opt-ins and one live-duplex integration opt-in**. Existing
runtime tests include actual v1 child execution; the graph is not all mocked, but
it does not renew the separate genuine v2/model/host observations or Apple target
qualification. Whole-command times are not warm compiler latency or the 1–3-second
feedback target.

### Fresh distribution postvalidation

The fresh `postvalidation-20261105-r2/summary.json` under that handoff passed
**all 11 steps**, exit 0. Its actual spaces/Unicode-path launcher `doctor` smoke
passed on **Linux amd64 / Java 21.0.12**, **0.402 s** for `doctor` only—not analysis
or latency/SLO evidence. The source, new installation and original shared install
remained unchanged; the integration default install remained absent.

The narrowly authorized difference is only the generated public `third_party`
root's **0700 → 0755** mode. Descendant names, types, file bytes and modes must
match exactly; no private-state permission exception or blanket mode masking was
introduced. Archive comparison verified **37 regular files** in each ZIP/TAR
against the staged installation without extraction. The inventory has **26 unique
coordinate/digest components** (28 resolved/configuration records), zero inventory
errors and **13 legal blockers**: owner license, 11 required NOTICE reviews and
compiler-embeddable's shaded third-party inventory. `release_qualified=false`;
no signing or publication occurred.

| Local archive under `archives/` | Bytes | SHA-256 |
| --- | --- | --- |
| `ktchecker-updated-preview.zip` | 78,430,285 | `bba71d298ca5dfda81adaea7d36727db82bdcb9d42e4d57ef33a7d08eccedb81` |
| `ktchecker-updated-preview.tar` | 84,700,672 | `11a26dbffc4400fa817d456ab86a5535bb854505a3469528c0ee0b4aea34cf6d` |

Postvalidation receipt pins under `postvalidation-20261105-r2/`:

- `summary.json`: `d037b45f44cbdbcb750b25a8bb402a208a5185deb71ca6192ab05de8ba33a682`.
- `distribution-smoke.json`: `7903c904837e9fcbb508b4391118236bb550d036f0f1529fca08dbdc15a99866`.
- `package-report.json`: `e13253280fde6b5018e3da1086fa347d7c679aaf044562145abccee1ed808dfc`.
- `archive-integrity.json`: `d5291bbb297d165ced7cd5faa36bd0599352af39f5fa8e52264805f4e14c2d45`.

Separate read-only audit `ktc-astra-distribution-audit-20261105/postvalidation-EtEdwX9i/receipt.json`
passed **343/343 checks**, SHA-256
`6a0e363b85a19ea00e424a9662283e1e683ebb2d42db922c7f8aa7de58bdb977`.
It did not rerun the build, launcher or provider. The resulting claim is a
**local doctor-runnable preview**, not genuine new model/compiler/host acceptance,
default provider activation or release qualification. The installed distribution
contains the two wire identities, not an installed isolated model-worker owner
runtime. Later source fixes are not silently included in these archive identities.

### Later focused source controls — not in the packaged handoff

The adopted joint-completion R2 source separately passed **26/26 methods**
(kernel 22, controller four), zero failures/errors/skips; both required tasks
**EXECUTED**, command exit 0, **59.873 s**. Receipt
`build/ci-evidence/joint-barrier-r2-focused-20261105/summary.json` SHA-256
`e929cc6a00dda3ea99cdb76e4aa9d2ef5ad328478fca5427ee7276344b3e7998` binds
**4,244 files** to unchanged before/after source SHA-256
`906f6560c1dd9bc6fd76daafc51fbb8b994eed77c3a347f34928c8d15d6e9227`.
These are local JVM completion/registry controls, not a new ordinary full graph,
real multi-model observation or release-scale qualification. The 65,536
history-pair / 4 MiB audit limits remain an explicit scale gap; no required member,
finding or coverage row may be trimmed to pass.

The later original-argv R2 **attempt 2** separately passed **28/28 methods**
(exporter API nine, importer six, typed roles 13), zero failures/errors/skips;
`:project-model:test` **EXECUTED**, command exit 0, **53.869 s**. Receipt
`build/ci-evidence/original-argv-r2-focused-20261105-attempt2/summary.json`
SHA-256 `fb07d2d2eb68a4cacb2bf0e0da456570ac8c72600f0c71354efc9cc7c3398ccf`
binds **4,248 files** to unchanged before/after source SHA-256
`5fb863e6e0bab58078d98585cef901d0bcb5dc534f83f318c14eafca42864c9e`.
These exporter/import/path-role controls are not a genuine project-model export,
compiler/model-host acceptance or a full current-source suite.

The predecessor `original-argv-r2-focused-20261105/summary.json` remains **failed,
exit 1, zero tests**, SHA-256
`231ed1bb3962f4d05484cb38ce91190c78cc29e2c6be6944df4f3d999fe8f627`.
An incorrect B1-seal versus source-list pin prevented adoption; the non-fail-fast
shell nevertheless ran the task with absent test filters. No argv candidate had
been adopted and no 28-case suite executed. The corrected fail-fast launcher and
adoption belong to the separate passing attempt, not a rewrite of that failure.

The subsequent joint/argv/map-dependent regression passed **1,036 occurrences:
1,035 passed, one Windows junction control skipped, zero failures/errors** across
**81 reports**. All **seven required tasks EXECUTED**: kernel, review-controller,
project-model, provider-compiler's ordinary/worker/model-worker tests, and CLI.
Command exit 0, **208.257 s**. Receipt
`build/ci-evidence/joint-argv-dependent-regression-20261105/summary.json` SHA-256
`2c5ed7b330b0ce31bc89ae78c72d6a0df0c566b6416aeceee97a2007dbc519ec`;
`command.json` SHA-256
`7d98c1d874dd760348bae7f81b090b7e362177adb9f4c623bc06fc4b6b960de4`.
The **4,248-file** before/after source matches SHA-256
`1c774d547033bcd5d8c42f8ef382553b02b6776827e0cfb84cfe8c6b6ba3dba4`;
the original installation stayed unchanged and the integration default stayed
absent. This is a seven-task dependent regression, **not all 22 tasks on current
source**, a new package, genuine model/host acceptance or per-rule qualification.

These runs have different source identities and are not a combined 54-case or
updated 1,774-case suite; the 1,036 occurrences are not added to earlier runs.
None of these later source identities is **in the 9dc3… installation or archives**
or inherits its smoke receipt. B1 CI wiring was separately adopted at its reviewed
workflow/test postimages; its synthetic pass still grants no new hosted execution
or updated graph/package receipt.

The separate prospective barrier CI gate passed **23/23 synthetic methods and
202/202 subtests**, zero failures/errors/skips, seven external guards zero, in one
**0.816766 s** run. It adds the two exact barrier class reports without replacing
the already-required kernel/controller tasks; root adoption was pending at this
receipt. `ktchecker-completion-barrier-ci-r1-20261105-4b7242er/receipts/verification-summary.json`
SHA-256 `48f54f7f2ad605c33aea4a85451f6c5c288af2fe5d94b4b055ea48ad8f6800d8`.
These are CI-wiring controls, not another 26-case JVM run or a hosted result.

### Pinned wrapper checksum consumer — API-only observation

Separate authorization first ran **21/21 synthetic guards**, then **one actual
runner with eight `Install.verifyDownloadChecksum` API invocations: eight passed,
zero failed**. These are separate from the ordinary B1 suite's 83 controls, not
29 combined rule tests or additional rule/context coverage. One `javac` command
(**0.941824 s**) and one `java` command (**0.126484 s**) both settled with exit 0;
the actual runner took **1.976515 s**, with no bound failure. These are fixture
command intervals, not compiler-analysis latency or the feedback SLO.

Retained outer receipt `ktc-wrapper-consumer-guarded-execution-20261105-ajImV5Rm/summary.json`
SHA-256 `0dc144bf5513050e75e1f321469298a53bb24da1f6445b7e525edc15af3cca16`
binds the actual `ktchecker-wrapper-consumer-run-xox7idjl/summary.json`, SHA-256
`49cc3417132b07bbfa2a7366207549e472cd3a6322c513f1b1f878386df2b1ea`.
Reviewed manifest SHA-256
`5c61a7179ef0434911fc0a5234469d99ef975943afcc6c26689e25146a283d2d`;
consumer JAR SHA-256
`7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d`.

The result corroborates **only that pinned static checksum API**. It does not
establish wrapper-version provenance, reviewed-project/Gradle/network execution,
owner-route/default activation, compiler/model/host acceptance or release
qualification. **Rule/context stage credit is zero**.

<a id="current-rule-coverage-2026-11-05"></a>

### Actual current rule/context accounting — not qualification

One actual reporter invocation settled with exit 0 and produced a valid gap
inventory on **4,248 files**, source SHA-256
`1c774d547033bcd5d8c42f8ef382553b02b6776827e0cfb84cfe8c6b6ba3dba4`,
unchanged before/after. It read existing evidence: **no Gradle, network or provider
execution and zero new stage credit**. This is later than the packaged 9dc3…
source; neither that archive nor the older full graph is updated by this report.

**All 1,663 IDs × eight contexts = 13,304 cells remain visible.** Report stage
counts are **11,448 mapped, two implemented, zero tested, zero qualified**; these
are cells, not qualified-rule counts. The mutually exclusive states are:

| Current state | Cells |
| --- | ---: |
| Excluded, still visible | 368 |
| Implemented but untested | 2 |
| Unimplemented | 12,934 |

Each context retains 46 excluded and 1,617 nonexcluded rows. Missing implementation
is 1,617 cells per context except the isolated Lint context (1,615); **all 1,617
nonexcluded cells in every context lack six-kind test completion and qualification**.
The catalog still contains **232 unmapped IDs and 28 nonexact-only IDs**. Declared
Android **1,584**, iOS **877** and Desktop **969** ID inventories overlap; they are
not summed, reduced or an approved release matrix. All platform scope remains
unqualified.

The report preserves **450 archival withdrawal cells: 443 compiler, three Detekt
syntax and four Ktlint syntax**. Historical artifacts remain; no stage credit was
transferred or rule/context row removed. The earlier routing analysis separately
reported **478 reference errors (443 compiler pins plus 35 syntax references)**;
those are source/data-analysis errors, not 478 withdrawn cells or an actual
reporter execution receipt. Earlier invalid refreshes remain failures rather than
being retroactively passed by this valid accounting result.
That source-analysis record is
`ktchecker-platform-rules-current-model-routing-e2ax722p/current-diagnostics.json`,
SHA-256 `31a0bc90330c6a3b50826f1e2f4e23c8bfc5a1aee0ff6081414051aa3931bba4`.

Retained packet `ktchecker-current-coverage-d69-run-20261105-HTuYtFzt`:

- `summary.json`: SHA-256
  `0bfdda6de6b22671ea6b7d6a0361c4c508cdd177e9777a5fcdaf5912fac66423`.
- `command.actual.json` (the settled reporter command): SHA-256
  `bbf6c88475224f39f3fd209ab457a6d80160e13a98dc5d634339db4561f07c4c`.
- `current-coverage.json`: SHA-256
  `5a962e37b7a1467e6c25e6c8fed8f85f3decf5fae12094af8fe994b7dbbe8e95`.
- `reconciliation/missing-coverage-summary.json` (exact gaps): SHA-256
  `ed050ae7972ec0550b5b20b30d305b0b666c8d6aefd0157a0059acbbe09e28ff`.
- `reconciliation/coverage-reconciliation.json`: SHA-256
  `991c19f4e6884dd4d1507aaabdcd2d79fbbd34d5bc39d8bede3484688bab3919`.

Reconciliation inspects that actual output; it is not another reporter run.
**A valid inventory with zero tested/qualified cells is a gap result, not
"all checks pass" or full-rule precision/recall evidence.**

### Latest-source harness checks — no live acceptance

The corrected runs below share **4,266 files**, unchanged before/after source
SHA-256 `97e45b69ee6728862db0d3a6055f91b58e734228f2bf79503b73c735d68ad83a`:

- Kotlin `build/ci-evidence/host-r3-config-focused-20261105/summary.json` passed
  **34/34 methods: 29 model controls and five config controls**, zero failures,
  errors or skips. All **five required tasks EXECUTED**, including CLI test
  compilation and the model-worker JAR. The real custody helper **compiled; its
  owner/main/live-model route did not execute**. Summary SHA-256
  `6ff74d48ffb86af7388e2a67c9b4f013339b852e7580612ec7899230d8dc0a2a`.
- TypeScript `build/ci-evidence/live-model-harness-ts-synthetic-20261105-r3/summary.json`
  passed **163/163 synthetic methods**, zero failures, skips or cancellations,
  joined to the original JUnit report. The **34 new controls are included**, not
  additional tests. Locked offline dependency installs succeeded using two
  distinct empty owned npm configuration files. Summary SHA-256
  `ac455d53e2ab63db634fcf81bb014cc418de6fccb43755505ca3fef46d2382ef`.

Retained predecessors are not rewritten by these passes:

- `build/ci-evidence/live-model-harness-kotlin-focused-20261105/summary.json`
  failed, exit 1: `:cli:compileTestKotlin` could not resolve `Member.path` at
  `RealModelCustody.kt:137,142`. The required provider test task did not execute;
  **436 retained stale XML occurrences provide no new test credit**. Summary
  SHA-256 `a5346a089bc1c31a5951bcf113c7743472b5a3f88405195e4d0c33dc8e9b7ccc`;
  command SHA-256 `9b8064294470cec883c98c6085b37d2660da71a8e5662faaea6928302f32e511`.
- Earlier TypeScript setup failed before tests: the initial selected-Node-path
  assertion failed before any dependency command; R2's dependency setup exited 1
  because npm loaded the same null-device configuration as both user and global
  configuration. **Neither failed setup attempt completed dependency installation
  or reached tests.**
  Retained `build/ci-evidence/live-model-harness-ts-synthetic-20261105/preparation-failure.json`
  SHA-256 `bcf0404e3ae19991a45720bfaef7543143aaf3e5a0a6f2488ce78ba6a7121235`;
  `live-model-harness-ts-synthetic-20261105-r2/summary.json` SHA-256
  `3f60fae88500d698197f218d79012c4578b2b4883ce4a423ecf0087466694805`.

These are separate local control runs, **not genuine model/compiler/host
acceptance, a full current-source graph or a latest-source package**. This
intermediate source is not final runtime admission; later source changes require
fresh evidence rather than inherited XML, graph or doctor credit.

### Failures retained and work still open

- Graph R1 `preview-current-graph-20261105-r1/preparation-result.json` failed
  **before Gradle**: its old-install guard selected the integration checkout,
  where no old installation existed. R2 instead guarded the original shared
  install and the absence of an integration default install. R1 is not a test run.
- Separate packaging `postvalidation-20261105-r1/summary.json` failed its strict
  public-notice-root mode check **before smoke**: source `third_party` was `0700`,
  the generated distribution root `0755`; all descendants matched. It remains a
  failed check, distinct from the incomplete original harness and any successor.
  The independent strict audit also remains **817/818 checks passed, one failed**
  on that same root mode; its later 343-check successor is a separate audit.
  R1 summary SHA-256 `a986245d3ecb2e8b927abaccb212135d2ce73a3d5953772e4f71d152f1e589a3`;
  strict `run-hvbTfXyL/receipt.json` SHA-256
  `36b0a7d1e3238b0d6e0a2afe1be045aef30888cedcf5663c1da6e97f5bd59f73`.
- Original B1 CI mock packet `ktchecker-b1-ci-guarded-validation-20261105-_ftwxpas`
  remains **18/19 methods and 165/166 subtests passed, one failed**, seven guards
  zero. The correction changes the ineffective lowercase-`:test` mutation to
  `task + 'NotRequired'` with an inequality assertion; it does not erase R1.
- Retained hosted metadata for exact `5e00407`, observed through
  **2026-11-04T21:15:33.977688Z**, records **four completed failed workflows,
  12 jobs: five successful/seven failed**. These are status metadata, not new
  test counts, causal diagnostics or a pass for the current dirty source:
  [Implementation 41912057147](https://github.com/Apdelrahman1911/ktChecker/actions/runs/41912057147)
  (1/3 jobs successful),
  [Planning 41912057201](https://github.com/Apdelrahman1911/ktChecker/actions/runs/41912057201)
  (1/3), [Bridges 41912057102](https://github.com/Apdelrahman1911/ktChecker/actions/runs/41912057102)
  (2/3), [Platform 41912056958](https://github.com/Apdelrahman1911/ktChecker/actions/runs/41912056958)
  (1/3). Retained `ktchecker-checkpoint-new-ci-metadata-20261104-nvljywco/sanitized-summary.json`
  SHA-256 `c3696324de157d6c7ee3eba3c9a7509798d40523aa9f80f80bf3db3b60e8d56b`.
  A separate readiness audit's three authenticated metadata GETs succeeded with
  Actions enabled; that audit is not run evidence, write authorization or runner
  capacity. No new dispatch, hosted success or platform-failure repair is claimed.
- The later current-coverage inventory is valid **gap accounting**, with zero
  tested/qualified cells; it neither transfers archival credit nor erases earlier
  invalid refreshes. **All 1,663 IDs and Android, iOS and Desktop/JVM obligations remain**.
  Unmapped/nonexact coverage, real project-model/compiler/host acceptance,
  full-rule precision/recall, exact release toolchains, platform/runtime evidence,
  Windows native containment/report access, notices/shading and owner licensing
  remain release gaps. No new default activation or binary publication is granted.
- The separate Unicode-validation task remains **unverified**. No new validation
  result is claimed by this documentation update.

Earlier command, corpus, harness, privacy, hosted and target failures below remain
retained; no successful local subset changes their recorded outcomes.

<a id="current-checkpoint-2026-11-05"></a>

## Earlier focused checkpoint — 2026-11-05 compiler-model continuation

These are separate local Linux observations of base
`5e00407ca5a2a31c371d471de3d4216a18fdd760` plus **run-specific dirty tested
bytes**, not one complete final-source suite. Retained receipt identifiers below
refer to metadata; original commands/logs stay private. Counts are per run, never
summed; subtests are not additional methods or rule/context qualification.

| Retained metadata receipt | Observed result | Boundary / retained failure |
| --- | --- | --- |
| `model-backend-r3-20261105/partial-summary.json` | **138 passed: 44 model-wire + 94 model-worker**; no test failures/errors/skips | The **whole command failed, exit 1**: the Gradle daemon disappeared and the host test task did not execute; cause unestablished. These are synthetic wire/worker controls, not a complete passing backend invocation. |
| `model-host-bounded-tests-20261105/summary.json` | **436 methods: 435 passed, one Windows junction control skipped on Linux**, zero failures/errors | Later bounded provider-compiler test task actually executed; includes 60 synthetic live-model controls. No new genuine model export/application observation. |
| `cli-route-model-types-r2-20261105/summary.json` | **167 passed: CLI 121 + controller 46**, zero failures/errors/skips | Both test tasks executed. The 23 live-model CLI, 12 owner-route and six routing-integration methods are included subsets, not extra counts. Synthetic callbacks/imports and throwing start permits do not demonstrate a real model/compiler session or host-model delivery. |
| Independent packaging R4 `result.json` | **16 exact methods and 68 subtests passed once**, exit 0 | Fixed-source/mutation and fabricated JAR-envelope controls only; eight-byte pseudo-class headers are not compiled bridge artifacts. The separate actual gate below has its own completed graph/artifact/raw-input/cache receipt; its credit is not inferred from these Python controls. |
| Actual model build-isolation `evidence/receipt.json` | **19/19 audited build stages passed**: 13 zero-exit commands and six exact-diagnostic negative commands, each exit 1; four actual model-JAR `FROM-CACHE` outcomes | **231.119 s** outer gate interval. Isolated model/M0 JDK build, archive and raw-input controls only; private test-only cache override on the model application JAR, never M0. Original archive bytes restored and M0 inventory unchanged. No tests, installed CLI, model/provider/Native execution, production-cache enablement or rule qualification. |
| `build-contracts-b1-first-20261105/summary.json` | **83 methods passed**, zero failures/errors/skips | `:provider-build-contracts:test` actually **EXECUTED**, command exit 0, **31.631 s**. Synthetic selected-wrapper/parser, owner/configuration/suppression/cancellation and complete activation-plan controls only; no reviewed-project wrapper download/execution, default CLI owner installation or source-chain/rule qualification. |

Receipt SHA-256 pins, in table order:
`976d720a8969a5d25e18f9464cba711f41bd5d97f188ee4fa7de6903b74d4463`,
`4af71ed43bf4259a0ed8fabe585ac8f113f41fa5488031ec094db613290d5369`,
`282af13858a9371964c689e419f9a04aff6ae89ed8428c2f7219a588c16de897`,
`e0607a83d4df05b0ecadb4e86ffc926908e85181ae6860366871010d5d632c1f`,
`9e17671b94f3e3082397840d1d09a537b3a08b1c582a47538816e0de4743eee5`,
`4ace1b7275a5d4ebf83c66c4131a9592552131bbbf8d352d7680dbf671125e3a`.

The model gate metadata is retained in local run
`ktc-compiler-model-build-isolation-run-20261105-obqsbw64`:
`preparation/gate-command.json` SHA-256
`c99afbecfc1adc1658fbe76e20ca542568e5cd7cfa67b1c7e92f52ee0b0e0b9b`,
`preparation/execution-audit.json` SHA-256
`2583695069b47d3571a92be96e3564f428b23e762554c10ac588b9bc5328f20a`, and
sealed `checksums.sha256` SHA-256
`5e8d2c26b1981ab0c3c890dbc5526c90625ebb3e32656951e96250dfc25f3c7c`.
The gate's captured 4,222-file source stayed unchanged at SHA-256
`7b777c83c81e15bd6e655ba5f93149f3ddbeb9b5a6ef3cc752124e97f709a6b5`;
the wrapper suite's separate 4,237-file source stayed unchanged at SHA-256
`9dc3d42cdd10579727d7e72f79a6637272afc94bf7036a162d05ec6aaf99f722`.
These are distinct snapshots, not a later integrated-suite or fresh installed
distribution receipt. Expected-negative commands remain nonzero failures with
their exact failed task/diagnostic; neither gate success nor a cache hit grants
worker readiness, genuine wrapper/provider/model execution or default activation.

The original zero-task Kotlin DSL failure, the daemon-failed backend command and
the earlier CLI type-inference compilation failure remain retained as failures.
The packaging R3 attempt also remains **14 passed / two failed methods**, with
two of 68 subtests failed: prefix/no-parentheses matching missed two mutations.
R4 corrected only those source checkers; its passing run does not rewrite R3.
Provider-host and CLI/controller receipts each retain exact before/after source
matches, but their tested fingerprints differ. Whole command intervals are not
compiler latency, warm-result distributions or 1–3-second target evidence.

Strict coverage is **not renewed by this checkpoint**: the retained refresh is
unavailable/invalid, and historical/stale evidence cannot supply current counts.
Reporting-only diagnostics do not restore coverage credit. All 1,663 catalog
obligations and release gates remain intact. These runs establish no new real
Kotlin/Native/Apple target compilation, linking or simulator success. No new hosted
CI execution is claimed by these local receipts. Genuine imported-model execution,
authenticated model-context delivery and full-rule/context qualification for
**this new model-backed route** remain unfinished; release completion is not granted.

<a id="current-checkpoint-2026-11-04"></a>

## Earlier local checkpoint (2026-11-04)

These are separate local Linux runs of source base
`21fc8c7725b176d093ddf40e5d5c335b2e7c16aa` **plus dirty exact tested bytes**,
with individual source/postimage postchecks, not a final-commit or complete
final-source suite result. Repeated runs and normal/optimized variants stay
separate, never added as independent diagnostic cases. All successful table
entries exited zero with no failures, errors or skips.

| Local metadata receipt | Observed result | Deliberate scope and exclusions |
| --- | --- | --- |
| JVM runtime v2 — `build/evidence/live-v2-jvm-tests/20261104-duplex-cachefix/summary.json` and `build/evidence/live-v2-jvm-tests/20261104-duplex-cachefix-repeat/summary.json` | **155/155 first; separately 155/155 repeated** | Same 155 methods: 62 fake/byte-only v2 controls, 92 prior runtime controls including actual v1 execution, and one genuine two-request v2 byte-duplex method. Local Linux x86_64 / Java 21 only. |
| Native v2 — `build/ci-evidence/live-v2-native-20261104/summary.json` and `normal.json` | **94/94: 45 pure/mocked Python controls + 49 compiled native methods** | Local Linux x86_64 fixture controls with verified inventory/method bindings; **31.300 s** suite wall interval, not latency/SLO. Not JVM application-worker, compiler, ABI, or release qualification. |
| Synthetic v2 reporter — `build/ci-evidence/live-v2-ci-synthetic-20261104/summary.json` | **71/71 normal; separately 71/71 optimized** | Synthetic reporter controls only; no Native execution or qualification. |
| Runtime cache wiring — `build/evidence/live-v2-test-wiring/20261104-first/summary.json` | **4/4 normal; separately 4/4 optimized** | Source-text/mutation controls only; not themselves Gradle-cache or native-execution proof. |
| Probe provisioner — `build/evidence/live-v2-probe/20261104-first/synthetic-summary.json` | **15/15 normal; separately 15/15 optimized** | Synthetic provisioner controls; no real compiler or probe execution by these controls. |
| Workflow wiring — `build/evidence/live-v2-workflow/20261104-first/summary.json` | **25/25 normal; separately 25/25 optimized** | Authored workflow wiring/mutation controls, not hosted execution. |
| Related-location suppressions — `build/evidence/suppressions-related/20261104-tests/summary.json` | **62/62: 10 new + 52 existing** | Synthetic finding-policy controls with real configuration persistence; not library, provider, or hosted qualification. |
| Kernel policy R2 — `build/evidence/kernel-policy/20261104-r2/summary.json` | **kernel 97/97; CLI 98/98; controller 28/28; 18 new methods** | Captured-evaluator fencing/lazy archived-policy recovery, rotating ≤32-row cursor and quota/archive ordering; synthetic provider/policy controls with actual SQLite, immutable archive/config persistence and worker-thread restarts. **104.334 s** suite wall interval; not a shipped CLI bypass, historical evaluator replacement, real Native/library diagnostics, or host qualification. |
| Earlier controller — `build/evidence/controller-ci-failures/20261104-fixed-controller-tests/root-exits-postcheck.json` | **28/28** | Full controller suite: synthetic analysis callbacks with real filesystem/CAS, SQLite native binding, ledger and worker threads; Gradle compilation, **not external analysis-compiler or owned-supervisor execution by tests**. Local UID0, not hosted nonroot/DOS evidence. |
| Windows fixture portability — `build/ci-evidence/windows-native-fixture-portability-20261104/root-exits-postcheck.json` | **103/103 normal; separately 103/103 optimized** | Local Linux synthetic Native-collector controls, including fake Darwin classpath projection; not hosted Windows/macOS or Native target qualification. Hosted failure remains unresolved. |
| Earlier macOS archive portability — `build/ci-evidence/macos-archive-portability-20261104/root-exits-postcheck.json` | **162/162 normal; separately 162/162 optimized** | Local Linux synthetic archive controls, including temp-alias/case-enumeration regression; not hosted macOS observation or release qualification. Hosted failure remains unresolved. |
| Earlier v1 builder custody — `build/ci-evidence/v1-builder-custody-20261104/root-exits-postcheck.json` | **52/52 normal; separately 52/52 optimized** | Synthetic compiler-driver lifecycle/filesystem custody controls; **no real child, compiler, or native execution**, and no qualification. |
| Native CLI admission — `build/evidence/native-cli-selection/20261104-first/summary.json` | **50/50: 21 new + 29 existing** | Entire portable provider-native JVM suite, with exact ordered source/common/library selection and immutable owner binding. **29.436 s** Gradle interval. Structural controls only: no actual Native compiler parser, Apple compile/link/simulator execution, or Native rule-context qualification. |

Both JVM Test tasks were **EXECUTED** with `--build-cache` and without
`--rerun-tasks`; the repeated gate used the same source/probe inputs and retained
new timestamped, digest-distinct original JUnit XML. Request two was constructed
only after reply one; real graceful stdin EOF, native flags/counters,
`cleanupAcknowledged` and `terminalExecutionConfirmed` checks passed. The
**36.857 s** and **26.438 s** suite wall times are not latency/SLO evidence. This
is opaque-byte transport, **not compiler-application HELLO, semantic-worker
readiness/integration, host-model delivery, or platform qualification**.

The earlier `build/evidence/live-v2-jvm-tests/20261104-duplex/summary.json`
records a rejected **Test UP-TO-DATE** attempt: no execution credit, no new test
counts, and no reuse of old XML as new evidence. The first v2-gate-skipped runtime
record (`build/evidence/live-v2-jvm-tests/20261104-first/summary.json`: 154 passed,
one real-duplex gate skipped) also remains separate and retained.

The separate Native-v2 collector records `closed_by_original_harness`, zero
unresolved obtained handles and observed absence of its original attempt
directory; **its wrapper did not independently verify native process closure**.
Neither reporter completeness nor the JVM checks upgrade that collector claim or
establish compiler readiness/default routing.

Related-location identity changes reopen legacy raw exact-finding/baseline
suppressions rather than silently accepting them; retained entries remain
auditable. Library direct tests passed **47/47** synthetic authority/algorithm controls
(`build/evidence/library-direct/20261104-first/summary.json`; **43.743 s** Gradle
wall interval; source postcheck passed). The owner-bound direct route remains
**unqualified and default-disabled**: no genuine plugin/library extraction or
coroutines artifact execution, no catalog/rule-context credit, and **all 125
obligations retained**. The R2
controller 28/28 is a separate rerun, not additional controller case coverage.

The independently reviewed [public hosted baseline](evidence/hosted-ci-21fc8c7-20261104.json)
at the older commit above reports **12 jobs: four successes, eight failures;
all four workflows failed**. It is not a new hosted run of these dirty changes;
local controls do not establish that those hosted failures are resolved.

This checkpoint adds no new installed distribution or publication, compiler
semantics, warm-target proof, Native/iOS/Windows target qualification, ABI, or
release qualification. Historical failures, distributions and qualification
boundaries below remain unchanged.

## Latest corrections — 2026-11-04

The corrected compiler suite actually executed **122 methods: 121 passed, one
Windows-only junction case skipped, zero failures/errors**. The 277 captured
source files were unchanged through the run. Forty bounded harness controls now
cover scratch ownership, uncertain-child quarantine, deadlines and evidence
publication; the additional public-evidence regression rejects disclosure of the
private compiler runtime location. These are integration/unit controls, **not**
execution or qualification of the expanded compiler-rule cohort. The earlier two
failed assertions and failed run remain retained separately.

A fresh genuine two-client Codex host attempt found the runtime-location privacy
defect in a compiler finding. It failed verification before completing any phase;
inserted/acknowledged events were not established as observed in a subsequent
model request. Its evidence and private run directory are retained, with one of
two test-thread deletions initially unconfirmed. The narrowly reviewed production
fix passes its regression; a new actual-host run is still required. RPC shutdown
or thread deletion is not proof of native-worker quiescence.

The privacy-corrected unsigned Linux x86_64 distribution was rebuilt successfully
in **18 seconds**, with all 133 pre-captured source/build inputs unchanged. It is
still a development preview, not a qualified release. Its spaces/Unicode-path
launcher smoke and 24-component dependency inventory passed; owner licensing and
third-party notice/shading review remain blocked. Local artifacts and full
logs are retained under `build/evidence/distribution/20261104-privacy-corrected/`;
their identities must not be substituted for the earlier benchmark's artifacts.

The Native-throws collector separately passed **86 synthetic Python tests** in
each of two executions, with zero failures/errors/skips. This validates its fake
CI-process and receipt controls only: no Apple tooling, real Native compilation,
framework linking or simulator qualification was performed by those tests.
The collector remains outside the active platform execution lane.

## Earlier local checkpoint — 2026-11-04, 12:23 UTC

These are separate actual Linux runs, not a complete final-source release suite:
kernel **75/75**, review-controller **27/27**, audit **58/58**, project-model
**47/47**, and CLI **95/95** passed without skips. CLI includes 26 new synthetic
typed-model composition controls; an accepted real Gradle-model-to-compiler path
is still unavailable. The second genuine model exporter ran successfully but its
compilation-domain validation rejected the result; no model certificate followed.

The first expanded compiler-harness run compiled and executed **106 methods:
103 passed, two scratch-cleanup assertions failed, one Windows-only case skipped**.
Independent review also found uncertain-worker cleanup and premature terminal
publication defects. This failure is retained; the expanded real compiler cohort
has not been counted as executed or qualified.

A fresh unsigned Linux x86_64 ZIP/TAR and installed launcher were built in
18 seconds; the spaces/Unicode-path `doctor` smoke and resolved dependency/SBOM
inventory passed. Owner licensing and dependency/shading review remain blocked.
The installed runtime uses SQLite JDBC **3.54.1.0**; the kernel test observed
embedded SQLite **3.54.1**. This is not new Windows/macOS qualification.

The [two-client executable observation](../benchmarks/README.md) used that exact
distribution and genuinely ran the JVM compiler for both independent clients.
Its first useful finding took **4.229 seconds**, measured at the application
poller; compiler warmth, model delivery and whole-tree CPU/RSS were not measured.
Both reviews correctly remained incomplete. It does not meet or refute the
representative warm 1–3-second target. Original private run archives are retained
under `build/evidence/`; the linked benchmark contains only public observations
and artifact identities. Full rule/platform/release gates remain unmet.

## Authored execution lanes

| Definition | Deliberate scope | Evidence required before reporting execution |
| --- | --- | --- |
| `.github/workflows/implementation.yml` | JVM-21 engine/broker on hosted Linux, Windows and macOS; actual implementation tests, unsigned preview archives and installed native launcher `doctor` smoke; genuine Detekt/Ktlint opt-ins on Linux/macOS only | Successful run/attempt, exact executed tasks, real JUnit case occurrences, matching source identity, launcher result and archive hashes; Windows must expose optional-provider unavailability and four actual opt-out skips |
| `.github/workflows/bridges-validation.yml` | Exact Node 24.20.0; genuine `npm ci`, pinned official MCP SDK and synthetic app-server tests, full/production registry audits, dry-run and actual unsigned npm packing on Linux/Windows/macOS | Actual command outcomes, Node case occurrences, lock identities, audit counts and safe tarball contents/hashes; no live model request or JVM integration is performed by this lane |
| `.github/workflows/platform-validation.yml`: desktop | KMP common/desktop actual-code boundary tests | Actual `desktopTest` execution and nonempty successful target JUnit reports |
| Same workflow: Android | Actual Android KMP compile/host-side JVM tests; clean/invalid `NewApi` controls; immutable-fixture Lint adapter integration | Concrete Android test task and reports, fresh Lint XML, named invalid-source diagnostic, actual provider envelope and matching artifacts |
| Same workflow: iOS | Both `iosArm64` and `iosSimulatorArm64` compilation and framework linking; actual simulator tests; missing/wrong-actual controls and immutable-fixture Native adapter integration | Explicit arm64 Darwin host, selected Xcode/SDK/runtime, executed compile/link/test tasks, framework binaries, simulator JUnit cases and actual provider envelopes |
| Same Apple runner: Native semantic/FIR | Separate compiler-plugin/library feasibility with one existing Native 2.4.21 arm64 home and project JDK25; no toolchain/dependency download or JVM substitute | Actual five serial compiler attempts and four exact fixture assertions; validated metadata-only summary and artifact/private-result digests; not framework/link/runtime qualification |

These are preview lanes, triggered by relevant PR/main changes, a weekly platform
schedule, and manual dispatch. Their authored state is **not a hosted run**.
The first actual hosted baseline is recorded below, including failures and skipped
provider controls. Later authored repairs/opt-ins still require another genuine
hosted attempt. Apple/Windows success cannot be inferred from Linux tests or
runner labels. Android host tests are **not device/emulator execution**.

The project fixture baseline is **KGP 2.4.21 / Gradle 9.6.1 / AGP 9.3.1 / JDK 25**,
with JVM bytecode 17, Android API major **37 / minor 0**, Build Tools 36.0.0 and
minSdk 24. The exact stable package is **`platforms;android-37.0`**, directory
`platforms/android-37.0` (revision 2, extension 22, base SDK), not the nonexistent
old `platforms;android-37` identifier. Neither 37.1 nor 37.2 is substituted. SDK
provisioning verifies actual public properties/`android.jar` hashes, does not
automatically accept licenses, and leaves the whole-distribution lock false.
The authored AGP `compileSdk = 37` DSL still awaits real resolution against this
package. The checker's
**engine/broker JVM 21 is separate**. Android/iOS jobs install JVM 21 and then
select JVM 25 as active `JAVA_HOME`; nested provider project Gradle receives that
explicit project home through `-PplatformJavaHome`. Availability evidence records
the observed patch, selected launcher/release-file hashes and vendor metadata.
`setup-java` currently selects Temurin by major version: this is **not yet the M0
exact vendor/patch/whole-distribution hash lock**, nor qualification of that tuple.

The Apple selection is Xcode **26.4.1 / build 17E202**, both SDKs **26.4**, and an
available **iPhone 17 UUID from the exact iOS 26.4 runtime**. Missing prerequisites
are unavailable, never a passing native substitute. Provider versions are Native
2.4.21 and Lint 32.3.1 (AGP 9.3.1); all fixture coverage remains `qualified=false`
and `state=incomplete`, even where actual execution provenance/certificates exist.

Linux/macOS implementation CI now authorizes pinned fixture resolvers and passes
`-PdetektIntegration=true -PktlintIntegration=true`. Bounded resolver output is
reduced to verified public coordinates/checksums; installation is not provider
execution. CI-only fixture outputs move under ignored
`build/ci-evidence/raw/library-fixtures`, without changing source/witness inputs
or weakening initial/final source-byte binding. Windows still builds/tests/packages
with both opt-ins false. Its separate library metadata must observe exactly
**two Detekt and two Ktlint skipped methods** for explicit opt-out messages,
report `process_group_containment_unavailable`, and never call those providers
executed. Other skips, missing/cached tasks or failed controls fail this gate.
These are authored changes, not new hosted provider-pass evidence.

## First genuine hosted baseline — commit `bc3208f`, attempt 1

Push time **2026-11-04T09:07:46Z**; exact commit
`bc3208ffb14bb30bdd1e81b6959db1ddcfef5541`. Original JUnit, sanitized evidence and
actual retained archives/tarballs were independently read from real hosted
artifacts. Observed clean Linux/macOS source contained **1525 files**, digest
`sha256:c5c081345b4eb37166db97c6bd82107206181ef905670cedbf5f85dab0195b08`.
This is historical baseline evidence, not proof that subsequent repairs ran.

| Actual workflow/run URL | Observed outcome |
| --- | --- |
| [Planning / 41804894050](https://github.com/Apdelrahman1911/ktChecker/actions/runs/41804894050) | Aggregate failure; Linux passed, macOS/Windows infrastructure alias controls failed |
| [Implementation / 41804894108](https://github.com/Apdelrahman1911/ktChecker/actions/runs/41804894108) | Aggregate failure; Linux actual build/package/smoke passed; macOS compiler test failed; Windows infrastructure failed before Gradle |
| [Platform / 41804894142](https://github.com/Apdelrahman1911/ktChecker/actions/runs/41804894142) | All three jobs failed SDK preflight before Gradle/target/provider execution |
| [Bridges / 41804894237](https://github.com/Apdelrahman1911/ktChecker/actions/runs/41804894237) | Linux/macOS actual install/test/audit/pack passed; Windows actual suites failed |

### Actual implementation jobs and archives

- Linux job **126489364869** completed **09:11:05Z**, Temurin **21.0.13+9** and
  executed pinned Gradle **9.6.1**. Actual module tests/build, installDist,
  ZIP/TAR, resolved dependency report and installed Unicode/spaces-path `doctor`
  smoke passed. Original XML: **248 occurrences, 246 passed, zero failures/errors,
  two skipped**. Both are genuine Detekt integration methods explicitly disabled
  without `-PdetektIntegration=true`; this hosted success **does not establish
  real hosted Detekt controls**.
- macOS arm64 job **126489364643** completed **09:11:00Z**, Temurin
  **21.0.13+9** / Gradle 9.6.1. **132 occurrences: 131 passed, one failure,
  zero errors/skips**. The genuine failure was
  `CompilerProviderTest.provenanceReadsAreBoundedAndRejectSymlinkInputs()`:
  `IllegalArgumentException: Unsafe evidence input`. Packaging/smoke did not run.
  The later narrow outer-ancestor-alias fix has Linux regression evidence only,
  not a hosted macOS pass.
- Windows job **126489365339** failed the collector portable-path infrastructure
  assertion before Gradle. The authored cross-flavor fix has no new hosted result
  yet; no security exception or test skip was introduced.

Actual artifact IDs: Linux sanitized evidence **13309228578**, original reports
**13309677210**, unsigned archives **13309861667**; macOS sanitized evidence
**13310085109**, original reports **13308919045**; Windows evidence **13309561251**.

Downloaded Linux archives were independently rehashed/member-inspected with
Python ZIP/TAR readers, **without filesystem extraction**:

| Actual unsigned archive | Bytes | Verified SHA-256 |
| --- | --- | --- |
| `ktchecker-0.1.0-dev.1.tar` | 85,331,456 | `cd201d0f0505131b2846eb3e9a0ccfb6da1020e73b7b9fd7c93382dc4a24511e` |
| `ktchecker-0.1.0-dev.1.zip` | 79,137,977 | `89520ecc79a941420d39feb4347c0fffa0b3bc3f0ef5885632c6be5f8cf79665` |

Each has **40 members (32 regular files/eight directories)**. All **21
components / 23 JAR paths**, four report-required attribution paths, three extra
baseline source assets and two embedded SQLite notices match actual digests/sizes.
ZIP/TAR file maps match; the Unix launcher retains executable mode.
Path/type/duplicate/bounds checks passed. Hosted inventory remains **11 Maven /
ten owner components**, license review blocked and `release_qualified=false`.
Archive byte/notice checks are not legal/shading review or another launcher run.

### Actual bridge jobs and npm packs

Linux job **126489364871** and macOS arm64 job **126489365276** genuinely ran
Node **24.20.0**, npm **11.19.0**, `npm ci`, **15 MCP / 26 host cases** per host,
zero failures/skips, zero full/production vulnerabilities at every severity, and
actual unsigned packs. Windows job **126489365232** installed/audited successfully,
then had **11 passed / four failed MCP** and **five passed / 21 failed host**
cases, zero skips and no packs. The baseline did not retain failure identities;
the new bounded dual Node/JUnit reporter records only unique literal public test
names and approved reason codes, not messages/stacks, on the next attempt.
Windows private-state qualification remains denied.

Artifact IDs: Linux evidence **13308358616**, packs **13309286694**; macOS evidence
**13309607039**, packs **13309736525**; Windows evidence **13308349695**.
All four downloaded Linux/macOS pack/member comparisons passed without extraction;
both hosts produced identical bytes:

| Pack | File occurrences; bytes | Verified SHA-256 |
| --- | --- | --- |
| `ktchecker-mcp-preview-0.1.0-preview.1.tgz` | 14; 11,017 | `025c774de083b5b008fa36e92418b4dced06fab344fa03b3d866413d7122f194` |
| `ktchecker-reference-host-preview-0.1.0-preview.1.tgz` | 38; 40,636 | `43f796fee4c638063f8855650e007baa2ea6b806d4eb4853f5114b17660981e1` |

These npm suites use a synthetic app-server, not a JVM broker/model request.
Owner licensing and the host's `file:../mcp` packaging gap remain unresolved;
passing audits/member checks do not qualify release or Windows ACLs.

### Actual platform preflight failures

Desktop job **126489365021** (artifact **13309341720**), Android
**126489365022** (**13309093616**) and iOS **126489364607** (**13309800684**)
all failed requesting the nonexistent old `platforms;android-37` identifier.
Build Tools 36.0.0 were present; no target/provider/Gradle execution followed.
Observed JDK patches were **25.0.5** on Desktop/iOS and **25.0.4.1** on Android,
not an exact whole-distribution M0 lock. Apple genuinely observed Xcode
**26.4.1 / 17E202**, both SDKs **26.4**, but no available simulator runtime.
There is **no hosted Native compile/link/simulator evidence** here.

Downloaded observations/independent byte reports remain locally under ignored
`build/ci-evidence/raw/hosted-bc3208f/`; they are not new hosted artifacts or
additional compiler executions.

## Observed lightweight checks — 2026-11-04 UTC

The following were executed locally in the Linux working tree, without acquiring
the global Gradle/compiler build slot:

| Actual command/probe | Observed result | What it does not prove |
| --- | --- | --- |
| `python3 -m py_compile scripts/ci/*.py` | Passed | Execution of Gradle, any provider or platform code |
| `python3 -m unittest discover -s scripts/ci -p 'test_*.py' -q` | **122 infrastructure tests passed** in 2.080 seconds, including SDK/package metadata, portable paths, bounded bridge reporters, 18 library-policy and 16 semantic-wrapper tests | No SDK/JAR download, npm installation, JVM/Native compilation, detector execution or model inference occurred in these synthetic harness tests |
| `python3 providers/platform-fixtures/test_retain_lint_evidence.py -q`, repeated with `python3 -O` | **Nine synthetic receipt tests passed** in each mode | The source association/privacy validator, not an executed Lint detector or two qualified IDs |
| actionlint 1.7.12, `-shellcheck= -pyflakes=`, against all three authored workflows | YAML/workflow static validation passed; reused existing binary with matching recorded SHA-256 `c872d6db8c6bf83a8eaa704fc93999f027d55dffbc63b8a6abdccb47df5f4cd4` | GitHub scheduling, runner availability, action execution, shell/Python linting, or build success |
| `python3 scripts/check_whitespace.py` | Passed | Compilation or execution of any implementation |
| `record_environment.py --require-java-major 21` | Linux x86_64, Python 3.12.3, actual Ubuntu OpenJDK **21.0.12** (`21.0.12+8-1-24.04-Ubuntu`); selected wrapper files pinned | Project JVM-25 availability/execution, or executed wrapper version; recorder never downloads/runs Gradle |
| Inventory-only probe of existing local `/opt/android-sdk` | Actual `platforms;android-37.0`, major 37/minor 0/revision 2/base SDK and Build Tools 36.0.0 properties/artifact hashes verified; whole-distribution lock false | No SDK manager/download/JVM was invoked; no AGP resolution, Android compilation, Lint execution or Android runtime |
| `run_platform_checks.py --profile ios` and `run_provider_checks.py --profile ios` | **Unavailable**: local JVM is 21 rather than project 25; non-Darwin/x86_64 host; no selected Xcode/SDK/runtime | iOS compilation/linking/tests or a Native provider attempt; no Gradle command was invoked by these prerequisite-failure runs |

Infrastructure tests exercise actual subprocess/output limits and timeout
termination, task-state distinction, negative controls requiring the expected
failed task and named diagnosis, actual source-byte changes, unsafe/malformed XML,
forged/empty counts, missing/stale evidence, preview-only doctor JSON, Windows
batch quoting, exact simulator selection, separate project/broker JVMs, provider
version/target/source identity requirements, executed certificates without
qualification, artifact-byte mismatches, and sanitizer failure/privacy paths.
Bridge controls also reject mutable/ranged SDK pins, inconsistent real Node/npm
summaries, unsupported packaged paths, tar links/traversal and stale source/job
evidence. Dependency controls reject malformed/duplicate-key manifests and
policies, forged file identities, ambiguous coordinates/POMs, wrong attribution
bytes, symlinked legal assets and unsafe XML/embedded legal files. A generic
"LGPL 2.1" declaration is deliberately not converted to `only` or `or-later`.
These are **not 122 qualified rules**, a natural precision corpus, or platform tests.

Local sanitized observations are ignored files under `build/ci-evidence/`:
`local-environment.json`, `local-ios-unavailable.json`, and
`local-ios-provider-unavailable.json`. They were recorded at approximately
**08:32 UTC** against dirty parent commit
`cb34adcf314eaaba63291e6f6970ad89ed2e9a19`, with 356 nonignored files and aggregate
source identity
`sha256:349ffaa4ece45f20895aa4a911cb2eac776fe4f0909ae2df520419cba8268e73`.
That identity is a **historical in-progress tree**, not the later committed
implementation: other agents and this ledger continued changing source files.
Never bind later artifacts to it merely because the commit label is unchanged.

The selected wrapper is Gradle **9.6.1**, with declared distribution SHA-256
`9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14` and actual
wrapper-JAR SHA-256
`7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d`.
The availability recorder leaves `executed_wrapper_version` unset until it can
consume an actual successful wrapper-version result. A system Gradle installation
does not stand in for the selected wrapper.

## Actual local implementation and packaging observations

The implementation exists and has executed local tests. The following historical
original JUnit XML was independently read from the coordinated lead's Linux
builds around 08:57 UTC. These are **246 case occurrences across separate in-progress builds**,
not one final-source run, 246 qualified rules, or hosted platform execution:

| Module/report context | Passed; failures/errors/skips | Latest report timestamp range (UTC) | Boundary |
| --- | --- | --- | --- |
| Contracts | 5; 0/0/0 | 08:32:17.901 | Actual DTO/contract methods |
| Snapshot | 42; 0/0/0 | 08:43:41.735 | Actual filesystem/store and controlled concurrency/crashes |
| Kernel | 25; 0/0/0 | 08:56:13.806–08:56:17.697 | Actual SQLite/scheduling/restart/stress; synthetic diagnostic/model receipts |
| Config | 34; 0/0/0 | 08:43:44.616–08:43:46.983 | Actual policy/authority storage; synthetic lifecycle observations |
| Process runtime | 21; 0/0/0 | 08:43:48.590–08:43:53.346 | Real bounded child processes; no hostile containment/Windows qualification |
| Compiler provider unit | 11; 0/0/0 | 08:35:01.944 | Parser/identity/denial tests, separate from actual compiler corpus |
| Detekt | 14; 0/0/0 | 08:28:49.768–08:29:12.554 | Twelve unit plus two actual pinned provider fixtures; incomplete/unqualified results |
| Android Lint | 17; 0/0/0 | 08:52:03.011–08:52:08.017 | Includes three actual isolated Lint 32.3.1 XML controls; not KMP fixture/device execution |
| Native provider | 8; 0/0/0 | 08:51:55.009 | Parser/catalog and explicit Linux-iOS unavailability; no Apple compiler execution |
| Semantic facts | 14; 0/0/0 | 08:52:25.544–08:52:25.734 | Historical DTO/coordinate/summary tests; the later actual fact-plugin run is recorded separately below |
| CLI | 20; 0/0/0 | 08:51:47.711–08:51:51.303 | Actual composition/registration controls; installed distribution also built and `doctor` starts |
| Independent audit | 35; 0/0/0 | 08:43:36.083–08:43:38.092 | Actual implementation/persistence with explicitly synthetic providers/host receipts |

The actual compiler corpus summary at
`providers/compiler-fixtures/runs/2026-11-04T08-35-03.142916645Z-b73907cd/summary.json`
records **80 actual executions in 80 distinct output directories**, 42 original
fixture IDs, 39 implemented/tested IDs, **14 failed case assessments** and
**zero qualified IDs**. The aggregate corpus task failed; passing subsets do not
hide failed controls or establish all 443 mappings. Its preserved historical
source evidence is not proof that subsequently changed sources were executed.

The later genuine corpus at
`providers/compiler-fixtures/runs/2026-11-04T09-40-05.124767771Z-b5c3e404/summary.json`
was independently read: **74 actual executions / 74 distinct output directories,
42 observed original IDs, 41 six-kind-tested IDs, two failed case assessments and
zero qualified IDs**. Its final provenance check passed with no recorded drift;
compiler 2.4.21 ran on project JDK **21.0.12**, not release JDK25. Summary digest:
`sha256:14ef06c8c2d65b85230fe6292824c0536c4803c259566e791954a00c52e424ad`.
It is already **historical** after the pending full-JDK process-runtime/source
change; newer source must run again before its mappings inherit evidence.

The delivery agent's contemporaneous early genuine Linux Node tests had **15 MCP
and 26 reference-host cases**, with no failures/skips. They use the pinned official
SDK and a synthetic app-server; neither these npm tests nor a local account probe
observe an authenticated model. The lead separately observed a live two-phase
host exercise with a synthetic kernel. The later actual compiler-backed host
exercise is recorded separately below, not conflated with npm unit tests or a
login probe's `requiresAuth=false` capability flag.

Later source adds a separately tested candidate Windows ACL helper: ten literal
native cases register only on Windows, while two invocation/decoder tests execute
on POSIX too. The lead still requires actual hosted native execution before
production wiring; the existing Windows privacy guard remains fail-closed.
Synthetic CI tests now retain its fixed public failure codes, never private ACL
messages, paths or stacks. Authored native cases are not observed Windows passes.

The lead's genuine Gradle init task
`:cli:writeDistributionDependencyManifest` succeeded. At **08:57:30 UTC**, the
Python inventory consumed its real resolved artifacts and the actual `installDist`
directory: **21 coordinate/hash components (11 Maven, ten owner), 23 installed
JAR paths**, zero absent/ambiguous identities and zero required-asset errors.
All 11 exact resolved POMs declare Apache 2.0. The retained Apache text and Kotlin
NOTICE/COPYRIGHT pinned hashes were present unchanged in the installed directory.
The stricter schema/path/POM validation subsequently reran successfully against
those same installed artifacts. The report records the inventory-time tree; it
does not reconstruct historical build inputs or verify archive member bytes.

Actual generated local reports are ignored files under `build/ci-evidence/`:
`package-report.json`, `distribution.cdx.json` (CycloneDX 1.6), and
`NOTICE.dependency-report.txt`. **License review is blocked**: the owner license
is unresolved, each Maven dependency's required NOTICE review remains unresolved,
and compiler-embeddable's shaded third-party inventory is incomplete. The report
does not invent an owner license, complete transitive redistribution clearance,
sign/publish anything, or make `release_qualified` true. Preview CI may retain an
inventory with those explicit blockers; `--require-license-review` fails on them.

## Genuine JVM semantic extraction and real application-host observation

`modules/semantic-facts/evidence/2026-11-04T091649Z-jvm/summary.json` was
independently read and validated against its metadata contract; SHA-256
`2cb4b8d9f005afc18dcd9e86be7731acc5499436184697a97a5d237032c5208e`.
Original XML has **21 passed unit occurrences** (19 semantic plus two summary
tests), zero failures/errors/skips. The genuine fixture driver records **four
assertion cases passed and five serial real compiler children**: dependency and
positive exit 0, unresolved/malformed/missing-dependency exit 1. Positive facts
are extracted with complete supported vocabulary and actual compiler **2.4.21**;
negative facts remain incomplete with `semantic_compilation_failed`, not guessed
resolved targets. Actual project JVM was **21.0.12**, default JVM bytecode **1.8**,
**not release JDK25 / JVM17**. Positive child elapsed 4.528 seconds; CPU/RSS were
unavailable. Both tuple/custom-rule qualifications remain false.

The Native wrapper is now authored and covered by synthetic infrastructure tests,
not genuinely executed on Apple. It requires observed Darwin arm64/JDK25/selected
Xcode and one already provisioned exact Native home, but not Android or a simulator
runtime. It verifies Native JAR version/component hashes and the private-result
digest, gates exact assertions/children, and uploads only an allowlisted metadata
projection of sibling `summary.json`. Raw facts/results/commands/logs remain
private. Native auto-download is disabled (`airplaneMode=true`); configured LLVM,
libffi and `xcode_26.4_17E192` sysroot cache presence is recorded independently of
host Xcode **17E202**, with external closure unqualified. A missing home or failed
dependency attempt remains unavailable/failed, never empty success. Static
class-byte ABI inspection is not actual Native plugin execution.

The authoritative public controlled-source host artifact is
`bridges/reference-host/evidence/cli-kernel-codex-public-fixture.json`, locally
committed in checkpoint **544f609**; observed **09:48–09:50 UTC**, `verified=true`.
It records actual CLI/kernel/Kotlin compilation and **three real Codex application
requests/content-bound public responses** with **gpt-6-astra**, Codex CLI
**0.160.0**, Node **24.20.0**, and no provider/model fallback. A/B used separate
owned threads/cursors/inboxes/acknowledgments/observations; B did not inherit A's
observation. The P2 owner repair ran through the durable patch path while a
consumer was disconnected, then broker/thread restart/resume preserved its cursor
and prior observations. The genuine recheck removed the old diagnosis.

The context had **1097 required candidate coverage rows**, but compilation was
not a complete imported project model: selected compiler coverage remained
unknown/incomplete and unqualified. The final action gate remained **denied**;
no guarded action or release qualification was granted. This is real
application-request/public-response evidence, **not an internal backend inference
trace**, all-rule correctness proof or review of every intermediate edit. Full
immutable coverage payloads were checked privately during the live run and
deleted with confirmed disposable-state cleanup. The public artifact retains
projected requests/responses, reason arrays, findings/raw diagnostics, selected
compiler coverage and full-coverage digest, **not all original coverage rows**.
Offline original full-payload/full-coverage reconstruction is therefore explicitly
unavailable; exact projected application requests and public text remain
independently checkable. Later code still needs its own execution evidence.

## Evidence collection and interpretation

CI runs use bounded subprocesses, no build/configuration cache and explicit
required task states. `SKIPPED`, `NO-SOURCE`, cached tasks, configuration/network
failures, and absent named diagnostics cannot pass target controls. Lint reports
are removed before each direct case. Native compilation is not JVM compilation;
linking is not simulator execution. The standalone fixture and AGP task/DSL names
still need genuine pinned-toolchain execution before their feasibility is claimed.

Provider controls separately invoke real JavaExec adapters with immutable fixture
captures. The outer harness checks actual task completion, driver assertions,
snapshot hash syntax, exact provider/version/target context, normalized canonical
diagnoses, captured-source locations, execution-certificate digests, and current
tool/artifact bytes. A structured v2 binding also checks the exact root-fixture
scope, file-to-source-set membership and fully qualified task paths. This is an
explicit fixture producer model, not arbitrary Gradle model import; source-origin
ownership is informational and does not expand suppression/configuration authority.
Findings retain the exact selected analysis scope even when their source file
originates in another member source set. The driver, not a mocked envelope, must actually verify its
snapshot materialization. These certificates bind small executed attempts; they
are not release qualification certificates and cannot make incomplete coverage
successful in the broker.

The collector records actual JUnit **test-case occurrences**, not declared suite
counts or unique rules, and hashes actual archives/framework files. Missing
reports/artifacts, failed/unavailable commands, malformed XML, differing actual
source identities, or non-success job status produce incomplete evidence.
`doctor` exit 0 only establishes execution of its explicitly preview contract;
it does not mean all checks/providers are complete.

Summary/evidence artifacts contain sanitized JSON and dependency inventory
NOTICE text, not raw terminal transcripts or authenticated model requests. By
explicit owner request, separate 14-day artifacts also retain original public
controlled-fixture JUnit XML and kernel stress JSON, including real failures.
They are original test reports, not sanitized summaries or terminal transcripts;
no private host state/worktree is uploaded. Raw command/compiler logs remain under
the unuploaded `raw/` folder. Bridge artifacts retain sanitized JSON only;
built-in Node JUnit side reports remain under unuploaded `raw/`, participate in
the process output-byte bound, and match the ordinary actual Node summary.
Unsigned preview distributions are separate artifacts, retained only after the
actual job succeeds. Bridge packs exclude tests/evidence/private configuration
and remain private, unsigned and unpublished. The reference-host local MCP
`file:../mcp` link is still a non-self-contained packaging gap; SDK audits do not
resolve owner licensing or Windows private-state qualification. Hosted jobs use
ephemeral runners, read-only repository
permissions and checkout without persisted credentials; they do not expose
secrets or privileged self-hosted resources to fork PR code.

## Outstanding evidence and unchanged release gates

- Rerun corrected final-source hosted implementation suites and installed
  launchers. The first genuine Linux baseline does not establish later macOS/
  Windows fixes or Linux/macOS real Detekt/Ktlint opt-ins; preserve visible
  Windows optional-provider unavailability and actual skipped counts.
- Execute the pinned JDK-25 Desktop/Android fixture and both real Apple targets,
  link outputs and simulator cases; execute provider integration controls and
  repair any genuine DSL/task/diagnostic failures without weakening controls.
- Execute the authored Native semantic metadata gate on actual Apple compiler/
  plugin/JDK25 bytes. The genuine JVM21/default1.8 extraction above does not
  establish the release tuple, Native semantics or link/runtime correctness.
- Rerun Windows bridge native ACL controls and full fail-closed suites;
  existing Linux/macOS actual unsigned packs do not establish Windows privacy or
  resolve local-link/license gaps.
- Resolve the owner distribution license and actual per-dependency NOTICE/shaded
  inventories. A successful dependency inventory is not a legal release gate.
- Establish exact M0 distributions, provider/libraries/classpath identities and
  required D8/R8, Compose and device/emulator boundaries before widening scope.
- Complete each required per-rule/context qualification, calibrated natural
  precision/recall corpus, field validation and release distribution/recovery
  gates. A maintained provider registry or one fixture never qualifies all IDs.
- Extend the genuine application-host observation beyond the public synthetic
  owner-overlay fixture only with explicit authority and retained evidence. CI
  does not perform model requests; public projections do not replace deleted
  original full payloads or provide an internal backend inference trace.
- Do not claim review of every intermediate patch from a final-commit CI run.

Future entries must retain the actual run/report/artifact identifiers, exact
source-byte digest, toolchain versions, test-case counts and failures/skips,
provider status/gaps, and separately named compile/link/runtime contexts. Update
these rows from observed outputs, never from the mere existence of tasks or files.

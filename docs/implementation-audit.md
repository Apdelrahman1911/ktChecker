# Independent implementation audit

**Started:** 2026-11-04. **Status:** implementation review in progress;
`ktchecker/preview-1`, not release certification.

This review is separate from [the design audit](review-audit.md). The auditor
authors this file and independent tests under `tests/audit/`, not the production
implementation. A source review, test name, mock host, or inventory entry is not
an executable conformance result. Pending checks below are not passing tests.

The requested scope remains all **1,663 catalog records**, including the opt-in
and advisory modes, with broad Android, iOS and Desktop/JVM release requirements.
Unimplemented, unsupported, unqualified and unexecuted portions must remain
visible. A partial engine or a provider import is not completion of that scope.

## Highest-risk acceptance boundaries

| Boundary | Adversarial witness required | Acceptance condition |
| --- | --- | --- |
| Shared computation, independent clients | Two clients submit equivalent snapshots; one cancels or acknowledges while the other still needs review. Reopen the durable store. | Work may be shared; obligations, cancellation, findings delivery and acknowledgments remain independently attributable and durable. |
| Project/worktree/user isolation | Same project in different worktrees, repeated local patch IDs, another principal, and two store instances using the same backing directory. | No accidental identity collision, cross-principal authority or cross-worktree head/config mutation. Unsupported multiuser configurations fail explicitly. |
| Every admitted patch | Continuous submissions, a slower earlier patch, cancellation, process restart and a failure during admission. | Each accepted patch has a durable obligation; coalescing does not discard required coverage. Admission never acknowledges an unrecorded obligation. |
| Snapshot CAS and lineage | Concurrent strict commits, disjoint rebase, overlapping writes, deletion/recreation and `A -> B -> A`. | Stale/conflicting commits cannot overwrite another lineage. Rebase uses change history where byte equality would hide a conflict. Failed admission leaves no partially advanced head. |
| Integrated immutable input | Source changes concurrently with build/config/model/artifact inputs; expired writer lease and an outside writer. | The reviewed identity binds the complete supported input tree and assumptions. Producer-owned submitted bytes are not mislabeled as a certified live filesystem snapshot. |
| Shared-API affected consumers | Change/remove an API, add an overload, alter source-set membership, and compare each incremental result with a clean result. | Old and new dependencies and relevant negative lookups participate. Unresolved impact widens coverage or reports incompleteness, not a smaller green set. |
| Partial and failed coverage | One rule/target/scope succeeds while another fails, is unsupported, times out or never runs. | Completion and absence claims cover only successfully checked comparable cells. No full-project pass or finding resolution follows from a partial run. |
| Suppression/config authority | Forged or stale revisions, competing shared/local updates, cross-worktree policy changes and restart between writes. | Trusted authorization is outside caller-controlled JSON; updates are atomic and scoped. Changed policy invalidates the integrated review identity and final gate. |
| Real delivery | Send succeeds without model inclusion; wrong-client, stale, replayed or forged receipts; reconnect after persistence. | Transport ACK cannot substitute for privileged, revision-bound model observation. Test/reference-host receipts never certify an untested real host. |
| Final action fence | Snapshot, config, coverage or delivery changes between checking and consuming a gate. | One coherent tuple and finite event frontier are revalidated/fenced at the final action; independently green checks on incompatible epochs do not pass. |
| Actual diagnostic semantics | Resolved positive and legitimate negative programs, settings/version/target controls, provider failures and malformed output. | Actual compiler/provider/custom behavior establishes each claim; parser success, metadata coverage and test doubles are reported separately. |
| Full/incremental equivalence | Run the same admitted input and effective configuration through clean and incremental paths, including failure/unknown states. | Findings, target/scope provenance and successful coverage are equal, not merely the finding count. |

The one-global-Gradle/compiler-job constraint applies during this audit. Independent
tests are supplied to the coordinated bounded build rather than starting competing
compiler daemons.

## Executed implementation evidence

### Latest bounded integration receipts

- The newer [sanitized actual-host summary](evidence/actual-host-supervised-20261104.json)
  records three application requests, three matching public responses and nine
  projected model contexts. Six full original event payloads are reconstructable;
  three full progress originals and the initial barrier's complete serialized
  request bytes are missing from retained evidence. The selected model was
  `gpt-6-astra`, without fallback. The final guarded action remained denied; this
  independently audited installed-image synthetic-project delivery experiment
  does not establish general-host, full-project or release qualification.

- The lead's coordinated Gradle 9.6.1 / Java 21 run executed **all 58 independent
  Kotlin tests** successfully. The auditor independently read all six XML reports
  in `tests/audit/build/test-results/test/`: config nine at
  `2026-11-04T09:54:32.418Z`, contracts seven at `09:54:32.857Z`, kernel eight at
  `09:54:32.885Z`, project-model ten at `09:54:34.356Z`, semantic facts thirteen at
  `09:54:34.511Z`, and snapshots eleven at `09:54:34.559Z`. All report zero
  failures/errors/skips. Earlier 17- and 35-test executions are historical subsets.
  The later `10:22` coordinated task was **UP-TO-DATE**, not another execution of
  these 58 tests. A fresh **58/58** execution at `12:06:34.919Z`–`12:06:39.222Z`
  is independently verified in the integrated batch retained below; its overall
  build still failed at CLI test compilation.
- The contract tests exercise the actual contract methods. The snapshot tests use
  the actual filesystem/store, including separate JVM contenders, hard
  child-process exits at real transaction boundaries, reopen and replay,
  ABA/tombstone conflicts, identity isolation, unsafe-batch rollback, buffer
  ownership, edit leases and same-destination materialization contention. The
  child JVMs do not run compilers or emulate model observation. Kernel fixtures
  use real SQLite, archives and child JVMs, but deliberately synthetic providers,
  source authority and observation receipts. Config tests use real HMAC authority,
  policy files and locks, with explicitly synthetic findings/lifecycle proof.
  Subsequent production changes require new execution; these tests do not cover
  parent-plan or actual semantic extraction by implication.
- The **23 newly executed independent Kotlin tests** comprise ten in
  `ProjectModelAuditTest` and thirteen in `SemanticFactsAuditTest`, with new audit
  dependencies on project-model and semantic-facts. Thus **58 are authored and
  executed** at this checkpoint. The model fixtures use actual temporary files
  and importer byte checks but fake, nonexecutable artifacts and explicitly
  synthetic origin callbacks. The semantic fixtures test DTO/coordinate
  boundaries with synthetic symbols and observations; they do not load FIR or
  execute a compiler. These tests were not included in the earlier 35-test pass.
- At `2026-11-04T09:17:34Z`, the auditor reran **five independent Python tests** in
  `tests/audit/test_coverage_audit.py` and all **31 owner-authored coverage tests**
  successfully. This includes the outer-root-alias correction and descendant/
  reentrant-symlink negatives. The miniature evidence is explicitly synthetic:
  inventory bookkeeping, not actual rule qualification. Commands:
  `python3 -m unittest discover -s tests/audit -p 'test_*_audit.py' -v` and
  `python3 -m unittest discover -s scripts -p 'test_rule_coverage.py' -v`.
- At `09:36:15Z`, the auditor independently reran all **nine platform-retention
  Python tests** in both normal and `python3 -O` modes, with zero failures. These
  are synthetic receipt/privacy validators, not Lint detector executions. The
  range-field and JUnit-name corrections are recorded in IA-032.
- Other owner-authored suites whose XML was independently inspected:

  | Suite | Executed tests | Report timestamp (UTC) | Evidence limit |
  | --- | ---: | --- | --- |
  | Kernel integration + stress (historical schema1) | 24 + 1 | `10:04:54.384`–`10:04:58.030` | Real persistence/scheduling and worker death; synthetic diagnostics and host receipts. Current schema2 first-run failures are retained below. |
  | Kernel parent plans (historical schema1) | 19 | `10:04:50.927` | Real SQLite/child-process atomicity, synthetic provider/plan/fence/host authorities; earlier assertion failure retained below. |
  | Kernel schema2/routing/current read-only preflight (seven suites) | 75 | `12:05:56.200`–`12:06:08.341` | Actual SQLite3.54.1, WAL/concurrency/lifecycle/storage controls; synthetic authorities/providers. Both preceding failed kernel runs remain retained. |
  | Config (five suites) | 35 | `11:15:25.965`–`11:15:28.424` | Actual policy/authority storage and corrected test-only host handling, not live model-host integration. |
  | Snapshot | 42 | `08:43:41.735` | Actual capture/store and filesystem controls, not complete project-model authority. |
  | Compiler provider/model bridge/native provenance (seven suites) | 80 passed, one skipped | `11:26:46.496`–`11:26:48.548` | Provider15, image13, bridge43 and native-provenance9 passes; tiny synthetic JDK/JAR bytes, no compiler/launcher execution. Native Windows junction test explicitly skipped. |
  | Corrected compiler bounded/privacy/provider controls (eleven suites) | 121 passed, one skipped | `12:40:11.684`–`12:40:14.323` | Forty bounded clock/filesystem controls and one public/private-evidence regression pass, alongside the prior81-case subset. Same explicit Windows junction skip; no real compiler cohort. |
  | Runtime (three suites, historical) | 28 | `09:20:12.362`–`09:20:17.218` | Includes seven pure model/task-binding tests. Passing orphan/cancellation controls do **not** close the critical PGID lifetime flaw in IA-038. |
  | Owned-child runtime (five suites) | 92 | `11:13:26.806`–`11:13:32.406` | Actual tiny JVM probes plus image/framing/slot controls; first83/84 failure retained. Current Linux runtime evidence, not real provider or other-platform qualification. |
  | Detekt unit + integration | 12 + 2 | `11:41:12.613`–`11:41:38.168` | Actual current native-supervised selected syntax/raw-coroutine processes and exact no-execution denial; first failed native batch retained below. No catalog-wide or library qualification. |
  | Separate bounded Detekt corpus controls | 20 passed, one skipped | `12:26:57.356`–`12:26:57.552` | Actual compilation and ten clock/admission plus ten filesystem/publication controls; real corpus explicitly disabled. No new detector/helper execution. |
  | Ktlint unit + integration | 16 + 2 | `11:41:43.073`–`11:42:10.556` | Actual current native-supervised selected processes and adapter controls; declaration-level suppression gap and first native-run failure remain explicit. |
  | Android Lint (three suites) | 22 | `09:54:38.740`–`09:54:44.097` | Includes two actual pinned Lint fixture tests and exact-context transport controls; partial/unqualified, not Android matrix qualification. |
  | Kotlin/Native | 14 | `09:54:53.336` | Pure subset/membership/task/parser controls and explicit both-target unavailability on Linux, not iOS compiler execution. |
  | Project-model (six suites) | 47 | `11:49:08.992`–`11:49:10.259` | Ten domain, seven execution-home, seven startup/completion, eleven importer, two profile and ten impact controls; synthetic export/runtime authority. Actual exporter execution and domain rejection are separately retained below. |
  | Architecture provider | 17 | `11:15:22.080` | Actual graph algorithms and pre-allocation bounds controls on synthetic verified-model/owner-policy fixtures. |
  | CLI (thirteen suites) | 95 | `12:12:08.409`–`12:12:49.354` | Includes26 typed-model controls,23 CodeImage, six compiler-installation and three containment-availability controls; actual storage/RPC mechanics, synthetic model/runtime authorities and no current real compiler composition. |
  | Review-controller (three suites) | 27 | `12:06:13.068`–`12:06:17.090` | Sixteen assembler, seven registry and four kernel-worker integration tests; live synthetic capabilities, unqualified nonexecuting providers, no restored authority or real model-host proof. |
  | Semantic facts | 21 | `09:16:30.187`–`09:16:30.465` | Nineteen validation/coordinate tests plus two summary tests; actual compiler-plugin evidence is reviewed separately below. |

  These XML reports have zero failures/errors; the **explicit compiler Windows
  skips and disabled real Detekt corpus test** are not passes. All other listed reports have zero skips. Source changes after
  their execution are not retrospectively covered. The Detekt integration source
  explicitly asserts unqualified/partial results; syntax stubs are not semantic
  library evidence, and upstream guidance does not implement a narrower catalog
  contract merely because it reports a related warning.
- The auditor reran **19 existing compiled Node tests** without any build or new
  inference: private RPC nine, synthetic Codex host six, and projection four.
  All passed with zero skips. They exercise malformed UTF-8, exact public-content
  matching, wrong/unowned threads, crash/reconnect, bounded projection and blank
  certificate handling. Synthetic model responses do not establish real inclusion.
- At `09:46:43Z`, the auditor independently reran the current **40 compiled
  reference-host Node tests**, all passing with zero failures/skips/cancellations,
  without rebuilding or invoking inference. This includes nine new exact
  coverage-validation-reason proof tests, three privacy/cleanup controls, parent
  acceptance projection and mandatory-content overflow. Earlier Node counts are
  historical subsets. Pure/synthetic delivery tests are not live-host qualification.
  At `09:57:13Z`, the auditor reran the three compiled telemetry/cleanup tests
  after the final fixed-allowlist privacy correction; all three passed.
- At `10:20:53Z`, the auditor independently reran **17 compiled MCP Node tests**,
  all passing with zero failures/skips, without building or executing PowerShell.
  Only two Windows source/invocation controls are registered on Linux; the eleven
  authored native Windows cases are **not registered**, not eleven executed passes.
  Native Windows execution remains **zero** and the production unqualified guard
  remains enabled.
- At `10:51:45Z`, the auditor independently reran the corrected **two owner-model
  preparer Python tests**, both passing in normal and optimized modes with zero
  skips. Synthetic archive/helper bytes were copied and never executed. The
  IA-036 default-deny seed inventory and anchored, prebounded manifest/contract/
  source reader were separately source-reviewed against the owner's `10:48:33Z`
  freeze (`sha256:bf7874362311f999f41ac0f0862472d7bdfbd8c83c9d86d4090124416103e0e2`);
  all 69 frozen source references independently rehashed unchanged. The new
  **32 model / 35 config** test counts were authored, not yet fresh executions
  at that checkpoint; historical 27/34 results did not cover those edits. Test-only host adjustments
  canonicalize only fresh trusted outer roots and explicitly skip unavailable
  anchored/symlink capabilities, without weakening production durability checks.
- The coordinated `11:15` run actually executed **37 model / 35 config / 17
  architecture** tests, all passing with zero failures/errors/skips. The auditor
  parsed all eleven archived XML reports in
  `build/evidence/model-native-start/20261104-first/`, checked executed test
  compilation/test tasks and `BUILD SUCCESSFUL in 41s`, and rehashed all **65**
  archived source references against live files and the reviewed owner freeze.
  All **77** freeze references also remain unchanged. The archived source map
  has SHA-256 `17f3f2d0e8407575b5d7262d38ac55e6871663346289b6dcb7e8243b555e45c9`;
  the log has SHA-256
  `98e06b2f2cd3f932c2cb27ec7b8d08f13b988fe347d88579c9d0e7e3ff267a08`.
  Pure startup/completion comparisons and synthetic importer authorities are
  not real preparation/export, START capture, loaded-code attestation or rule
  qualification.
- The next actual project-model execution at `11:49:08.992Z`–`11:49:10.259Z`
  passed **47/47**, zero failures/errors/skips and 47 unique testcase names.
  All six XML reports, actual compilation/test tasks and all **120 contemporaneous
  source copies** (2,891,304 bytes) in
  `build/evidence/model-cli-composition/20261104-first/` were independently checked.
  Source-map SHA-256:
  `eb8262ec4bd11ada4ffdd19dfb83f3463af994554126c3dfa53e83bf1eb5c68e`;
  log SHA-256:
  `1390684781d37767360238c9e21ea7bcf07ea95f2468d2ab4a70a474e08bbf13`.
  This is not an all-module pass: `BUILD FAILED in 34s` reflects the two CLI
  ConcurrentHashMap compilation errors, and CLI tests did not run. Later CLI,
  compiler-harness and kernel edits do not alter this archived model evidence;
  unchanged model source was separately verified.
- The first coordinated kernel run at `09:54` had **43/44 passes**: the parent
  malformed-manifest test expected a later snapshot error instead of the earlier
  source-binding rejection. Its XML is preserved under
  `/tmp/ktchecker-kernel-firstbatch-failure-bPbtjI/`. A test-only correction asserts
  the exact early rejection and adds last-slot divergence for the later boundary;
  the **44/44** rerun above passed, including **19/19 parent tests**. The first
  model run at `09:54:23.914`–`09:54:25.080` had **24/26 passes**, not a passing
  suite: fixture-size and membership negatives expected different earlier error
  boundaries. The corrected **27/27** historical model rerun passed; subsequent
  executable-seed-cache/START controls are covered by the actual **37/37** rerun,
  not retrospectively by those earlier results.
- The first schema2/routing/lifecycle batch at `11:38:10.149Z`–`11:38:31.119Z`
  actually executed **65/68 kernel passes, three failures**, plus **27/27
  controller passes**, with zero errors/skips. All nine archived XML reports in
  `build/evidence/kernel-routing/20261104-first/` were independently parsed;
  all **31** batch source references and the owner's **12** freeze references
  matched live files at inspection. Source-map SHA-256:
  `e1d185f0c27113dc1360ac63d29babd00b92a6fbd802eaf40a033aef3b255e50`;
  log SHA-256:
  `7929011ad827eee1045d5ad53eef68d0873fd24729ebcb16308025e9e2c5cc7c`.
  The log ends `BUILD FAILED in 57s`, not an all-module pass. Lifecycle **4/4**,
  schema **4/4**, routing **15/16**, old integration **23/24**, old parent-plan
  **18/19** and stress **1/1** were executed. Failures and pending corrections
  are detailed in the parent-plan section; no failed method's later assertions
  are inferred to have run.
- The next schema2 batch at `11:47:15.892Z`–`11:47:34.534Z` executed
  **69/70 kernel passes, one new failure**, plus **27/27 controller passes**,
  with zero errors/skips. All nine XML reports in
  `build/evidence/kernel-routing/20261104-rerun/` and all **31** unchanged
  run-source references were independently checked. Source-map SHA-256:
  `a3a516a5047e096e65e8d44e83597e9455df5c47a4bf1a5699fcaccbc60f5af3`;
  log SHA-256:
  `51441a296cc799cb05262b51f6268f52dfa3f52743018b5503f16fbd93c08bd1`.
  The log ends `BUILD FAILED in 49s`. All three earlier failing methods now
  pass; routing **16/16**, parent **20/20**, lifecycle **4/4**, schema **4/4**
  and stress **1/1** pass. Integration is **24/25**: the fairness test fails
  in the constructor's immutable SQLite probe with `SQLITE_CORRUPT`. This is
  a live-file API misuse, not proof of persistent physical database corruption;
  passing the other controls does not close IA-043.
- The portable read-only preflight/fixed-SQLite integrated run at
  `12:05:56.200Z`–`12:06:39.222Z` actually passed **75/75 kernel, 27/27
  review-controller and 58/58 independent audit** tests, with zero failures,
  errors or skips. All sixteen fresh XML reports and **95 contemporaneous
  source copies** (1,317,407 bytes) in
  `build/evidence/integrated-kernel-cli/20261104-first/` were independently checked.
  Source-map SHA-256:
  `599466e8579cd1a175c54e2e2382ae3827f20e3a9c83c0269b46e758e37f3216`;
  log SHA-256:
  `5553c6ef99b1536174b44d3277ed7ec8a94fcb8e3f93c1ea46bf5506064d6ebe`.
  Kernel comprises integration25, parent20, routing16, schema8, lifecycle4,
  stress1 and dependency1. The loaded driver reports **3.54.1.0**, embedded
  SQLite **3.54.1**, source ID
  `2026-10-16 14:45:20 555e07ef33f69a0957d24d8e555b702464a131d6f1607f4c3c1e4551e8e6ca76`.
  This identifies the loaded dependency, not source/binary authenticity. The
  config35 reports are old `11:15` XML and the task is **UP-TO-DATE**, not fresh.
  Overall `BUILD FAILED in 1m 14s` is retained: CLI production compiled, but
  `ModelPlansTest.kt:359` failed `SMARTCAST_IMPOSSIBLE`, so CLI tests did not run.
  The only later drift among those95 references is the exact local-binding test
  correction described below. Kernel source, dependency pin and controls match.
- The corrected CLI rerun actually passed **95/95** across thirteen suites at
  `12:12:08.409Z`–`12:12:55.429Z`, zero failures/errors/skips. All95 unique
  testcase names, actual test-compilation/test tasks, `BUILD SUCCESSFUL in 1m 23s`
  and all **95 contemporaneous source copies** (1,317,467 bytes, no live drift)
  in `build/evidence/cli-composition/20261104-rerun/` were independently checked.
  Source-map SHA-256:
  `1f215f7496311822f39ab1a122da90b933815a8e7f6ea74e004835e70da66564`;
  log SHA-256:
  `0e83af4db670410789b7246939eb9584bdd1267ca4c5eee6e6df66dab8cacdba`.
  The **26** new typed-model controls use synthetic inputs/capabilities, not an
  actual accepted model export, compiler child or production final fence. Both
  preceding compilation failures remain retained separately.
- The coordinated `10:22` batch was **not an all-module pass**: CLI compilation
  failed on `CodeImage.kt`'s mutable-set `+=` resolution, and controller test
  compilation failed on four implicitly typed `ModelCompilation` lambdas. The
  first log remains `/tmp/ktchecker-runtime-controller-integration-20261104.log`;
  controller failure evidence is also retained in
  `/tmp/ktchecker-controller-first-compile-failure-20261104-daWeZS/`. The corrections
  use explicit set `add` and explicit lambda parameter types, not weaker assertions.
  The separate `10:26` rerun actually executed CLI **66/66** and controller **27/27**
  and ended `BUILD SUCCESSFUL`; its log is
  `/tmp/ktchecker-cli-controller-rerun-20261104.log`.
- A full repository evidence-inventory generation at `2026-11-04T08:38:29Z`
  **failed closed**, reporting an Android Lint implementation-evidence digest
  mismatch for `AndroidLintProvider.kt` in the `ContentDescription` **mapping**.
  This was initially described as a sidecar; independent reinspection confirmed
  there was no executed Lint sidecar at that time. Refreshing the mapping source hash appropriately
  fixes that inventory-input error without granting execution/testing credit.
  A subsequent full generation succeeded: **1,663 records × three JVM contexts =
  4,989 cells**, with 42 implemented/tested cells and **zero qualified cells**;
  `all_requested_cells_qualified=false`, `release_qualified=false`. The newly
  written compiler sidecar accounted for 39 cells; its actual run is reviewed
  below. At `08:59:37Z`, regeneration **failed closed again**, this time on the
  old **executed Detekt sidecar**'s `DetektProvider.kt` digest after the provider
  configuration-binding correction. The owner preserved 33 prior raw/config/
  receipt/sidecar files byte-identically under
  `providers/detekt-fixtures/runs/2026-11-04T09-04-49Z-historical-before-profile-binding/`
  and withdrew two stale live sidecars; preservation records the mismatches, not
  fabricated old implementation bytes. Independent regeneration succeeded at
  `09:05:33Z` and again at `09:17:34Z`: **1,663 records × two contexts = 3,326 rows**,
  mapped 2,860, implemented 41, tested 39, qualified **zero**. States are 92
  excluded, two implemented/untested, 39 tested/unqualified and 3,193 unimplemented.
  The contexts are historical compiler JVM and standalone Android Lint XML;
  `all_requested_cells_qualified=false`, `release_qualified=false`. The earlier
  4,989-row report is historical. At `09:28:33Z`, after new Detekt evidence,
  independent regeneration succeeded with **1,663 × four contexts = 6,652 rows**:
  mapped 5,720, implemented 44, tested 42, qualified **zero**; states 184 excluded,
  two implemented/untested, 42 tested/unqualified and 6,424 unimplemented. The
  contexts are Detekt syntax JVM, Detekt raw-coroutine JVM, historical compiler
  JVM and historical standalone Android Lint XML. Both qualification booleans
  remain false. Source-hash maintenance alone cannot establish that changed
  implementation bytes passed previous fixtures.
- The last independently inspected successful inventory regeneration, at
  **`10:23:52.089620Z`**, succeeded
  with **1,663 records × five contexts = 8,315 rows**: mapped 7,150, implemented
  **nine**, tested **seven**, qualified **zero**. Its states were 230 excluded, two
  implemented/untested, seven tested/unqualified and 8,076 unimplemented. Its
  contexts were Detekt syntax, raw coroutine, Ktlint syntax, historical standalone
  Lint XML and `current-edited-synthetic-jvm-jdk21-unexecuted`. The selected current
  compiler implementation has **zero per-ID implemented/tested/qualified claims**;
  its 42 observed/41 six-case historical IDs are not current-source credit. The
  nine/seven cells were Detekt three/three, Ktlint four/four and historical Lint
  two/zero. Both qualification booleans remain false; the earlier 44/42 totals are
  historical. Subsequent library attribution withdrawals below also make these
  **nine/seven totals historical**, not the current state.
- The auditor then inspected the owner's retained
  `build/reports/library-native-source-review-rule-coverage.json`, SHA-256
  `a2a0d41f1e005f52644daac5c2b11f0ef01f2e91e80080d820d20f8079531cbd`,
  without regenerating it. Its **8,315 rows** independently total 7,150 mapped,
  **two implemented, zero tested and zero qualified**: only historical Lint
  `ContentDescription` and `HardcodedText` retain implementation credit. States
  are 230 excluded, two implemented/untested and 8,083 unimplemented; both release
  qualification booleans are false. At `11:33:23Z`, the auditor independently ran
  **41 coverage-inventory tests** in normal and optimized Python modes and the
  **five independent inventory audit tests**, all passing with zero skips.
  Their synthetic references/contexts test attribution, not actual rules.
- Following the independently verified fresh library rerun and hash-preserving
  publication below, the auditor parsed
  `build/reports/library-native-rerun-derived-rule-coverage.json`, SHA-256
  `b395471d9694a3ef7807fdac340eba55a2e38f8bf65c70662a6884b9fc83e3a5`.
  Its **1,663 records × eight contexts = 13,304 rows** independently total
  **11,440 mapped, nine implemented, seven tested, zero qualified**. States
  are 368 excluded, two implemented/untested, seven tested/unqualified and
  12,927 unimplemented; both qualification booleans remain false. Three new
  native-preview contexts coexist with the original withdrawn contexts rather
  than renewing their stale receipts. The seven tested cells represent six
  distinct IDs; `ComposableParamOrder` has separate Detekt and Ktlint cells.

### Actual JVM semantic extraction, not rule qualification

The auditor independently inspected
`modules/semantic-facts/evidence/2026-11-04T091649Z-jvm/` against the private
fixture report, exact captured sources and child compiler copies. The public
summary digest is
`sha256:2cb4b8d9f005afc18dcd9e86be7731acc5499436184697a97a5d237032c5208e`;
the private results digest is
`sha256:62804b3d1a330175317774d4eaffa0c5d88d3347d6c8ae6cd499faa6a4267270`.
Both match the recorded provenance.

- **Five actual serial isolated compiler executions**, five distinct output
  directories, **four of four cases**. Dependency build and positive compilation
  exit zero; unresolved, malformed and missing-dependency cases exit one and
  remain incomplete/unknown, not clean semantic results.
- Independently rehashed **13 selected artifact files and 40 child compiler
  copies**, and recomputed all four request digests and tuple/plugin/source
  bindings. Recomputed **367 span occurrences** against exact UTF-8 source,
  UTF-16 columns and CRLF, and **160 symbol-ID occurrences** against canonical
  descriptors, with zero discrepancies. The two same-spelled local generic
  owners are distinct and both are actually called.
- The positive case has five shards, 80 symbol occurrences/72 unique identities,
  41 calls, 52 type uses and four expect/actual candidates, without unknowns.
  This is actual **Linux/JDK 21.0.12/Kotlin 2.4.21/default JVM 1.8**, not release
  JDK 25/JVM 17 or Native. The tuple and custom-rule qualification flags are false.

The provenance's source and fixture-driver class inventories are explicitly
**post-run observations**, not startup source-to-bytecode or full-environment
attestation. Compiler/plugin artifacts were actually selected and verified by the
runner. The observed vocabulary is narrow: class/type-parameter types and
function/property calls do not establish flexible/intersection/definitely-nonnull
types, callable references, dynamic/candidate/negative dependency graphs or
effects/flow semantics. No diagnostic rules are implemented by these facts.
The thirteen independent semantic boundary tests subsequently passed in the
coordinated audit suite; their synthetic inputs do not broaden this FIR vocabulary.

### Detekt and Ktlint reruns, including retained failures

The `09:20` Detekt sidecars were
`providers/detekt-fixtures/evidence/verified-jvm-syntax.json` and
`verified-jvm-coroutine-upstream.json`. Startup source/fixture references and
seven selected loaded class-resource anchors are retained and drift-checked;
the inventory independently rehashed their references. These anchors are not a
full classloader image or proof that source produced the executed classes. The
same-original-input/permit, changed-settings rejection in the positive integration
case now executed. Three syntax IDs are tested/unqualified. Actual raw upstream
coroutine guidance still leaves the narrower catalog rule unimplemented/untested.
Before CI evidence-destination edits, the owner preserved that run under
`providers/detekt-fixtures/runs/2026-11-04T09-49-14Z-historical-before-ci-evidence-root/`
and withdrew the old live sidecars. The new **14/14** XML execution at `09:54`–
`09:55` is independently inspected above; it must not be confused with merely
relocating/rehashing the earlier evidence.

Ktlint's two-test integration XML at `09:23:27.781Z` records **one failure**, zero
skips: its version assertion expected `1.8.0` but received `ktlint version 1.8.0`.
The real editorconfig inheritance/exemption/parse-failure integration test passed;
the suite did not. The earlier dependency-as-ruleset failure and this partial run
are retained under `providers/ktlint-fixtures/runs/` with `failure.json`, logs and
artifacts. Sixteen unit passes do not replace the failed integration run. The live
tested sidecar was withdrawn pending a successful coordinated rerun. A later
actual run at `09:55:21.532Z` again had **one failure in two integration methods**:
the Compose declaration-level suppression still emitted a fifth finding where
four were required. The owner retained that run under
`providers/ktlint-fixtures/runs/2026-11-04T10-03-07Z-failed-compose-declaration-suppression/`.
The auditor independently rehashed all **54 files and 17 original startup
references** in that preserved failure. The original expected-four/actual-five
assertion remains failed; it was not rewritten as a pass.

The separate corrected run actually passed **two integration methods at
`10:07:20.437Z` and sixteen unit tests at `10:07:41.201Z`**, zero failures/errors/skips.
That run's original `verified-jvm-syntax.json` sidecar SHA-256 is
`3147a73ac73d519fbce3de2280d9c3840ce2f21b81e6ba8368cc3454698fd9a7`.
Independent byte-level verification checked 49 unique evidence references, 18
startup source/resource/fixture/class references, three actual pinned JARs, and
four selected rules across all six case kinds. Positive/version/file-suppression
cases each retain exactly four findings; negative/configuration/regression retain
zero. All twelve finding occurrences match captured bytes, excerpts and UTF-16
positions. A separate actual declaration-suppression-gap fixture retains **two
Compose findings at lines six and ten, column four**. File-scoped suppression does
not claim that unsupported declaration suppression works; no production filtering
or weakened four-finding assertion hides the gap.

The actual pinned `--version` observation and rejection of the newer 2.4.21
profile are distinct controls. Startup capture precedes the six-case method, not
necessarily the earlier configuration-method processes. The retained anchors do
not establish a full classloader image, source-to-bytecode proof or complete
offline reconstruction of the original `AnalysisInput`. Every provider result
remains partial/unqualified without a success certificate.

The later native-runtime/test-provenance edits do not inherit those executions.
The auditor independently verified the withdrawal archives:

- `providers/detekt-fixtures/runs/2026-11-04T11-09-38Z-4c26474e-historical-pre-native-attribution-withdrawal/`:
  **68** preserved originals, comprising both original sidecars and all **66**
  recursively referenced files. Its `historical-tuple.json` SHA-256 is
  `77121224b38812b268cb228e5db315a07961f7dc827635ed54cf9f8c93ecd025`.
- `providers/ktlint-fixtures/runs/2026-11-04T11-09-38Z-e2998f0c-historical-pre-native-attribution-withdrawal/`:
  **50** preserved originals, comprising the original sidecar and all **49**
  referenced files. Its index SHA-256 is
  `86f7d43cc4f35a3c0b97466f0690dbdf7939e232fd9f733b0edbfc5e45c1f988`.

Every archived hash matches its original sidecar/index reference; original
sidecar paths and receipts were not rewritten into new execution evidence.
At that withdrawal checkpoint, the original three library sidecars retained
historical attribution separately:
all **eight** rule rows have implemented/tested/qualified/attempted **false**, no
current cases and no current implementation or qualification references. This
does not mean historical processes never ran or adapter code is absent; it
withdraws stale current-source credit pending a fresh verified run. Native22 and
runtime92 controls alone do not renew any of these per-rule receipts.

The auditor also checked all **68** original/live references (890,516 bytes) in
the new library source freeze,
`/tmp/ktc-library-native-source-freeze-20261104-2c4nlfv5/`; `freeze.json` has
SHA-256 `77ee1f22b708835f938be6a97bac15a1fca84008c37fd2423ca327af47b58488`.
The narrow native-reader/integration diff review finds retained actual outcomes
before native-execution assertions and separate runtime source/code-image/native
tuple identities. This is not a complete review of the newly authored corpus or
full JDK/dependency closure. The withdrawn
sidecar hashes remain `63fdb6ab0071d4168b9d79a8827e3fdcfd8019d21de1903f594731dad74041a4`
(Detekt syntax), `400b1fae0f7a946314b5e3929dffeb2b06f49403a39df7d841d40f774669cd0d`
(Detekt coroutine), and `c55ec3e79c0cbff37df6ef379156c5fec8d87224ec27f31453bd22f3b7806e15`
(Ktlint syntax).

The first fresh native integration batch is **failed**, not a renewed pass:
Detekt **13/14** and Ktlint **17/18**, with one failed integration method each,
zero errors/skips. The auditor independently parsed all four reports in
`build/evidence/library-native-tests/20261104-first/` at `11:31:21.086Z`–
`11:31:52.981Z` and matched all **19** source references to live/frozen originals.
The source map SHA-256 is
`45eb076f4ee39eb546ab87d2b97d22253657c72d72d3c9140b1f89a57b0389e2`;
the log SHA-256 is
`9ba503fb474c810aa42b4b32b55532e1a004525c34d2b733de203540cb605d45`
and ends `BUILD FAILED in 1m 2s`.

Both six-case methods stop at their old assertion that changed-settings replay
must produce **no observer callback**. The retained callback instead reports
exactly `unavailable`/`execution_not_authorized`, null exit/output directory/native
identity, empty command/stdout/stderr and `executionConfirmed=false`: truthful
pre-execution denial telemetry, not an unauthorized child. Later case assertions
in those failed methods were not reached. A fresh successful run was still required;
the first failure and its actual results remain preserved.

The narrow denial-callback correction is now independently source-reviewed in
`/tmp/ktc-library-native-denial-callback-source-freeze-20261104-6h3ukbqk/`,
whose `freeze.json` SHA-256 is
`2f505f98c2c8d07dab5a8074604d31f466e268bacc1d6e3e101872606ce2abac`.
All **68** live/frozen references (899,580 bytes) match: exactly five test/corpus
Kotlin files and four documents changed; **59** other references are unchanged.
The auditor reviewed all nine diffs. Denials require the exact unavailable reason,
false confirmed/executed/successful flags, absent identity/exit/output directory,
empty command/stdout/stderr, zero output bytes, no cancellation/timeout/truncation
and absent CPU/RSS. Actual denial JSON is written before assertions; passing
receipts cannot be minted until all checks pass. A false exec-confirmation flag
alone would not distinguish refusal from an unconfirmed fork.

The first-failure preservation index has SHA-256
`88e85f51c9665afaae476f38177e11ce914e9962852e8c5511441662628fda03`
in `/tmp/ktc-library-native-first-failure-preserved-20261104-rmncukn_/`.
All **25** copied XML/log/source originals (641,384 bytes) and **299** still-retained
provider/source/class/native/report references (6,994,415 bytes) independently
rehash unchanged. At that checkpoint all three original sidecars remained
withdrawn. Preservation itself is not corrected-source execution, corpus
compilation or new per-rule credit.

The separately retained corrected-source native run **actually passed 32/32
tests**, with zero failures/errors/skips, at `11:41:12.613Z`–`11:42:10.556Z`.
The auditor independently parsed all four XML files in
`build/evidence/library-native-tests/20261104-rerun/`, matched all **19**
run-source references and checked `BUILD SUCCESSFUL in 1m 23s`. Source-map
SHA-256: `3a9694f7f46814d323456dca1431178f079037005678d08b060bc8da62856795`;
log SHA-256: `0420d6b6e69333a6aedc4f2ccc70245c48049b01892433a39b4613c945ec7bef`.

All four fresh completion records and **22 actual process-outcome records** in
`build/evidence/library-native/20261104-rerun/` were checked: **20 confirmed
native-supervised processes** (Detekt nine, Ktlint eleven) and **two exact
no-execution denial callbacks**. Confirmed processes carry the reviewed Linux
source/binary/protocol identity and expected flags/reasons. Ktlint's accepted
diagnostic exit **one** is successful process execution, not clean analysis.
The seven six-case provider/context rows pass but remain **unqualified**; the
raw coroutine row remains implemented/tested/qualified **false**. Fresh Ktlint
results still report the declaration-suppression gap at lines **six and ten**;
parse failure remains unknown coverage, not a negative finding certificate.

Immutable copies and derived publication were then independently verified:

- Detekt archive
  `providers/detekt-fixtures/runs/2026-11-04T11-41-12Z-e34ef956-verified-native-preview1-tuple/`:
  **254 exact original copies**, 5,085,100 bytes; `native-tuple.json` SHA-256
  `a77ae4f1038e7349b9d10e4a0c46cb0789c669263afc97d3512824bbbc1b7435`.
- Ktlint archive
  `providers/ktlint-fixtures/runs/2026-11-04T11-41-43Z-b749d16c-verified-native-preview1-tuple/`:
  **229 exact original copies**, 5,334,649 bytes; index SHA-256
  `b880c0c6770c6087248c534aec0cabc026f495f6536a6a4d848c1e3495ae5271`.

All **45 + 34** separate current-source guards match. The exact original complete
runtime JAR, SHA-256
`f0de15bd471311772028bbe08425588a2fb76702daa764b4a0f440ac8f868b31`,
was recovered from its already retained matching START image, not rebuilt or
retrospectively rehashed into new authority. The auditor compared ZIP entries:
the later live JAR differs only in informational `build-provenance.json`.
Original receipt/context/hash strings remain intact. All **1,525** direct
references in the three derived sidecars match; **4,706** nested archive
references have no hash disagreement. Twelve native-receipt fields are repeated
module/resource-relative metadata (three distinct names), not missing
repository evidence. Inverse relocation restores every raw context/stage/case
and existing evidence-list entry; implemented rows only append the declared
current-source guards and original-sidecar/archive pointers.

New derived sidecar SHA-256 values are
`6bb30481bdbd2dcaf9b672bd0f1f78c51c1754ebb87d58325cf01a81c42378db`
(Detekt syntax), `844ed35a2b86f20b0a9ff746e8f9b2e36fbcfaf66354a95cc9a78971c56936da`
(Detekt raw coroutine), and
`9e1716de54f0cba11d85247b08530b5b207f8bbe1bfbb1076605a90988a47ad2`
(Ktlint syntax), under the respective `evidence/*-native-preview1.json` paths.
The publication report is `build/reports/library-native-rerun-publication.json`,
SHA-256 `380eec9779335f36d7a36063368324885ad96052b4d19dec775728d4f55d99fa`.
This publication adds no process execution, full-JDK/build attestation, corpus
execution or rule/platform qualification and does not rewrite the first failure.

### Separate bounded Detekt corpus: controls verified, real execution not yet run

The auditor independently verified all **52** original, retained and live source
references (858,372 bytes) in
`/tmp/ktc-detekt-corpus-exceptions-core-v1-source-freeze-20261104-av5jmo0q/`
and `build/evidence/detekt-bounded-controls/20261104-first/source-freeze/original/`.
The freeze SHA-256 is
`3c1c41a0b3bad7084bbf9cb09cba5af875a1ce037391c31927d64d042499334b`.
All three retained XML reports independently count **20 passes, one explicit
disabled-integration skip**, zero failures/errors and21 unique test names.
Actual corpus-test compilation and control execution completed at
`12:26:57.356Z`–`12:26:57.552Z`, `BUILD SUCCESSFUL in 27s`.
Source-map SHA-256:
`8a78761e129dab1927d5e3fc62ea0fc62bdbf220755013dcb3f3acd1c6007d6e`;
log SHA-256:
`1362851e6e15173e3fb9404ffe0bb966d26a289e79da3d0a33d04955736670b9`.
The twenty tests use synthetic outcome values and small temporary filesystem
trees; they do not execute Detekt, a native helper or a real compiler.

All five corpus driver/control/evidence sources and the selected authored
heuristic controls were independently read. Only explicit
`exceptions-core-v1` is admitted: **28 detector analyses, one CLI probe, six
Common/Native abstentions and three configuration-authorization denials**.
All38 reservations count; hard caps are40 invocations,20 aggregate minutes and
60 seconds per child. Every new admission requires checked assertions plus the
actual matching native terminal or exact no-execution result. The current
runtime's `executed`/empty-reasons contract requires verified image, FINAL, EOF,
helper exit0 and QUIESCENT without ownership/shutdown failure; callback presence
or exec confirmation alone cannot discharge an uncertain child. No numeric
PID/PGID signaling is added by this driver.

The real integration has **no framework TempDir or recursive scratch cleanup**.
One-shot0700 scratch and every ancestor remain on success, failure, abort and
unknown native return. Bounded CREATE_NEW events precede calls and retain actual
outcomes before assertions. Bulk completion artifacts remain candidates with
`run_complete:false`; final publication clocks are inside the failure/withdrawal
boundary. Filesystem/identity refusal can itself prevent withdrawal, so an orphan
live sidecar without independently verified successful JUnit/build completion
is **unconfirmed**, not a green result. The monotonic budget begins just after
evidence-root construction; the lead's separate outer watchdog must cover that
setup, compilation and native cleanup grace too.

Exact findings/ranges on deferred or locally handled finally throws, custom
`TODO`/`NotImplementedError` symbols and shadowed caught names are retained,
not filtered or relaxed into universal defect semantics. Anticipated false
positives/negatives remain authored assessments until actual execution.
All **ten rules/91 analyses** remain the full `not_run` authoring scope, with
seven unselected rows explicitly not_run, not excluded. This review clears a
separately approved bounded trial, not a performed trial, corpus promotion,
library/compiler compatibility, other-host support or qualification.

### Actual compiler execution, including retained failures

The auditor independently inspected
`providers/compiler-fixtures/runs/2026-11-04T08-35-03.142916645Z-b73907cd/`:

- **80 isolated Kotlin 2.4.21 compiler processes**, 80 unique output directories,
  and one requested-version mismatch denied before execution.
- **42 per-ID fixture sets**, of which **39 pass all six case kinds**. Across
  252 assessments, 238 pass and **14 fail**. The overall fixture runner failed;
  `failure.json` retains the error rather than turning partial success into green.
- The missing expected IDs were `EMPTY_RANGE` and `UNREACHABLE_CODE` (five failed
  case kinds each), and `INAPPLICABLE_JVM_FIELD` (four). Unobserved diagnostics
  after attempted execution must not be labeled as unexecuted processes.
- Independent byte-level checks across all 81 jobs found zero mismatches in exact
  analysis keys, sealed source sizes/hashes, stdout/stderr digests, execution
  status/exit records, and compiler identity/command. All **183 findings** bind
  the input semantic key and captured file digest with valid UTF-16 point locations.
- Every input remains `fullProject=false`; every result remains incomplete with
  untested/unqualified coverage and no success certificate. Version rejection of
  requested 2.4.20 is not actual alternate-compiler execution. NaN/infinity/sentinel
  controls neither infer programmer intent nor require the compiler to be silent.

The first runner read live implementation sources at run end. Its now-preserved
original sidecar and unchanged source copies are explicitly **post-run archives**,
not startup capture or executed-bytecode proof. The revised runner separately
captures startup sources, real class-directory/JAR inventories and resource
anchors, checks artifacts before each job and checks source/artifact drift at run
end. The following actual rerun is distinct, not retrospective evidence for the
earlier sources. Neither the 39-ID subset nor all 443 mapped IDs is release-qualified.

The auditor independently completed byte-level verification of
`providers/compiler-fixtures/runs/2026-11-04T09-40-05.124767771Z-b5c3e404/`
at `10:00:29Z`:

- **75 jobs, 74 actual compiler executions**: 53 exit zero, 16 exit one, five
  exit two; the requested 2.4.20 job is denied without execution. Provider states
  are 53 partial, 21 failed and one unavailable. All 74 output directories are
  unique; ephemeral child compiler copies/output directories were deleted after
  capture, so their continued availability is not claimed.
- **42 observed implemented IDs, 41 passing all six case kinds, zero qualified**.
  Across 252 assessments, 250 pass and **two fail**: `EMPTY_RANGE` negative and
  configuration clean-output controls retain locationless `DEPRECATED_CLI_ARG`
  output. The fixture runner still exits one and preserves `failure.json`.
- The five exit-two jobs are individually suppressed errors for
  `UNRESOLVED_REFERENCE`, `NO_VALUE_FOR_PARAMETER`, `TOO_MANY_ARGUMENTS`,
  `NAMED_PARAMETER_NOT_FOUND` and `ARGUMENT_PASSED_TWICE`. Their real compiler
  backend exceptions remain raw diagnostics and failed provider results. Their
  passing suppression assessments explicitly test **failure preservation**, not
  successful suppression or clean compilation.
- Rehashed **941 unique sidecar references and 146 startup references**; checked
  110 preserved source files, seven executed-artifact inventory digests, nine
  class-resource anchors and the selected eight compiler JARs plus Java launcher.
  Recomputed every job's analysis key, 386 sealed-source occurrences, all **187
  findings**' exact input/context/file/UTF-16/raw-output bindings, and all 252
  assessment-to-job bindings, without discrepancies.
- Startup and end-drift receipt digests are respectively
  `sha256:c7f9bd9034aa7ade3abd7f36ca2655819368e67f1a2b03a6a33fee19431b89ae`
  and `sha256:4be5bf97e343f84fb94a87755428beb63fdcb09d9386c35383c7618e8f6ccde4`;
  the end check at `09:45:52.843339200Z` reports no drift. Source and executed
  artifact identities are independent, not source-to-bytecode proof. This actual
  Linux/JDK 21.0.12/Kotlin 2.4.21/JVM 17 tuple identifies the selected launcher and
  compiler artifacts, **not a full JDK image**. Later ordered-classpath/profile
  and whole-runtime-image changes do not inherit this execution.

### Current compiler identity and explicitly prepared JDK image

The current source binds the **ordered** compiler classpath's coordinates,
digests and selected paths, rather than sorting away execution order. Its
`ktchecker/kotlin-jvm-toolchain-3` identity includes the complete selected,
link-free JDK 21 tree: empty directories, names, file bytes and executable policy,
with entry/name/depth/byte/time limits. Absolute selections must already be
normalized and contain no dot segments; `/alias/../jdk` cannot be silently changed
to a different OS selection. An intrinsic toolchain-digest profile argument binds
the runtime even when the input omits a declared toolchain digest. A mismatched
declared identity denies execution. Runtime and JARs are rehashed before and
after a child attempt, without a hash cache; precise runtime errors retain actual
stdout/stderr/exit evidence. None of this attests loaded JVM memory, OS libraries,
JNI dependencies or original vendor-environment equivalence.

The pre-bridge compiler's **28 executed unit passes plus one explicit native Windows
skip** are preserved under
`providers/compiler-fixtures/unit-evidence/2026-11-04T102216Z-central-compiler-units/`.
The auditor rehashed its 21 references, including eighteen source copies and two
XML reports, and reconciled report observations. `verification.json` SHA-256 is
`07a83be242e33104be9bb67e8457a04978a6fef977db41e8a0cba45c1b2e7946`.
These are post-run copies matching the pre-batch source freeze, not startup
bytecode attestation. Tiny synthetic images are never executed as Java. The
actual older corpus does not establish compatibility of those image-schema
changes; neither run covers the subsequent model-bridge source edits.

The later fixture/native-provenance source freeze is
`/tmp/ktc-compiler-fixture-native-source-freeze-20261104-raj7bd8r/`.
The auditor checked all **261** live/frozen source pairs (2,293,065 bytes), with
zero differences. `SHA256SUMS` has SHA-256
`e88a5ce614eed1fc56f615d264c1a7275198fb7023c92ce43d2246149be92f83`;
`manifest.json` has SHA-256
`e125b15fad2a825757c793f49350e645a30c4ab4f3fa187d997b355299d35a52`.
The five reviewed fixture files capture exact native manifest/binary/build-receipt
resources from the reflected supervisor's installed code source, retain its
whole selected class artifact and check those resources before each job and at
END. Actual observer outcomes and raw jobs precede receipt-invariant rejection;
missing exec confirmation or a mismatched START identity withholds attribution
without erasing attempts. Build-source claims are not reproducibility or loaded
memory attestations. **Nine new byte-only tests actually passed** in the central
`11:26` run; they launch no helper, JVM or compiler. Refreshing
the 443-row compiler map to its nine current source references grants no current
per-rule implemented/tested/qualified credit.

`scripts/prepare_jdk_image.py` is a separate, explicitly **POSIX-only** preparation
utility. Source review verifies bounded approved link expansion, only reachable
external targets, exact reasoned dangling-link omissions, exclusive private
creation, repeated source/destination/topology checks and an outside private
receipt. Existing files/directories cannot be omitted by a dangling-link waiver.
Its optional probe owns the direct child with `waitid(...WNOWAIT)` until all group
signals finish, refuses a custom SIGCHLD reaper, checks exact captured Java
version and rejects a late exit after the deadline. This Python correction is
**not** evidence for the separate native/Kotlin runner replacement in IA-038.

The auditor independently ran all **42 utility tests twice**, normal and
`python3 -O`, at `10:19:18Z`, with zero failures/skips. They use tiny synthetic
filesystems and five tiny actual Python fake-launcher processes, not a JVM or a
full JDK copy. Verified utility/test SHA-256 values are respectively
`45dcf99a2a2db0fc8be676be2c61387839d2e9d60e4c7b278acda9fa9768d9db` and
`7533ec3ccf610611ca8e5bbedc6183f3f62b300acbf04a391f22314b9ac6e13f`.

At `10:36:43Z`, all **seven new independent Python audit tests** in
`tests/audit/test_jdk_probe_audit.py` passed in both normal and optimized modes,
zero failures/skips. They exercise the actual probe with **entirely mocked**
process/wait/signal operations: exact WNOWAIT observation, final signal before
first reap, no signal/reap after ownership loss, timeout, cleanup failure/overrun,
custom reaper rejection and unsupported-host abstention. No child process or
real signal is used. These add independent control-flow evidence for the Python
probe, not native containment evidence or a fix for the Kotlin runner.

Separately, the owner actually prepared a private installed JDK image and ran its
Java version probe with explicit approval. At `10:22:35.661218Z`, the auditor's
authorized read-only check independently rehashed the **entire destination**:
400 entries (311 files, 89 directories), **299,821,244 bytes**, exact names/kinds/
modes, before/after stamps and directory membership. Receipt and prepared-content
SHA-256 values are respectively
`e4860b63d5e1b6b078c2c87568b63cd9de697196dcb789446528e75b4df11e6c` and
`457f5f07d51a7cdfac7cf3a522d9962bd034848d68997eb9edd0be2ba8a79006`.
The receipt and private system paths/content are not published here.

The audit recomputed the recorded source-manifest digest and destination/source
mapping, checking 99 symlinks and 99 directories among 440 recorded source
objects, selected-root aliases and two still-absent targets. It did **not** perform
a second full original-source content rehash. Five external lookup domains and
two actual installed dangling-link omissions were explicitly selected for
controlled fixture/testing use; they are not synthetic observed source links.
The receipt records actual Java **21.0.12**, exit zero, 53 ms and 177 stderr bytes.
Original lexical probe text was not retained, only its digest/count; the auditor
did not reconstruct that text or execute Java. Owner preparation took 3.569 s.
This preparation-content schema digest is **not** automatically the Kotlin
runtime-image/toolchain schema digest, compiler compatibility, full-vendor
equivalence or rule qualification.

### Bounded compiler fixture runner: retained failures and reviewed corrections

The `12:06` bounded freeze contains **267 files**, all independently checked
against frozen/live bytes (2,378,477 bytes) and its exact `SHA256SUMS` map.
Manifest SHA-256:
`41548a9ffe8ad40541b21909da57393cc9e218eb764fedbe6e2f50d62d9e0762`;
path: `/tmp/ktc-compiler-fixture-bounded-source-freeze-20261104-7dvo4r4t/`.
Only Runner/Provenance/README changed from261; six Controls/Evidence/Scratch
support/test files were added. The auditor read all changed code and **25** new
test methods. Explicit named cohorts retain all92 ordinary pairs,16 unsupported
graph plans and443 mappings. Hard admission caps remain48 attempts,20 minutes
overall,90 seconds per job and256MiB evidence, with16MiB job reservations and8MiB
terminal headroom. Unselected/partial/failed cells remain visible; no historical
results merge into a new run or acquire qualification.

The first central execution is **103 passes, two failures and one explicit
Windows skip out of106 cases**, not a passing suite. All ten XML reports and
267 source copies in `build/evidence/compiler-bounded-units/20261104-first/`
were independently verified, including actual test compilation/execution at
`12:15:17.276Z`–`12:15:19.354Z` and `BUILD FAILED in 57s`.
Source-map SHA-256:
`071f0229294b65620aa80f747248c06e5d2db38bb70a575aaa366d780855e6d6`;
log SHA-256:
`2b0837fa46ad218f2312f4f20ce22d2c1c3b1c7f832d4a6e9032556b7d0c2038`.
New Controls9/Evidence9 pass; Scratch is5/7. The two failed assertions require
successful final-root cleanup at test lines27/41; source review identifies the
empty relative-path ancestor iteration as the likely defect. These are tiny
filesystem/clock/byte controls, **not real compiler/provider/helper execution**.

The source has useful immutable raw job indices, per-assessment/phase checkpoints,
original-permit pre-write output registration and bounded anchored deletion.
The first freeze had two independent findings blocking an actual bounded cohort:

- **IA-045:** per-job and final cleanup still run when native group shutdown is
  unverified or provider execution throws without a conclusive outcome. File
  identity and a stable tree are not proof that no child retains them; such
  registered scratch and its ancestors need quarantine, not deletion/re-enrollment.
- **IA-046:** terminal `runComplete` is sampled before bulk sidecar/summary/latest
  publication. If those budgeted writes cross the deadline, the final check throws
  outside the retained failure path while published artifacts still say complete.
  Candidate checkpoints cannot themselves certify final bounded completion;
  the final clock/decision/publication boundary needs an explicit truthful protocol.

The narrow correction freeze
`/tmp/ktc-compiler-fixture-lifecycle-source-freeze-20261104-25xa0i8f/`
was independently matched across all267 copied/live files (2,402,910 bytes),
including exact SHA256SUMS membership. Its manifest SHA-256 is
`45fb89d7421ed6e1baa65068fc0deb798f47c464551245a9d340f950bcc73366`;
exactly six authorized files differ from the failed unit tuple.

IA-045 now irreversibly quarantines the **entire private scratch manager** before
any per-job or final-root inventory/deletion on unknown entered-provider throws,
mismatched native identity/input or unacknowledged native shutdown. This includes
output created before its registration callback. No later path/outcome can
reenroll the quarantined tree. Source review confirms that the current provider's
returned no-observer path is pre-runtime refusal; an entered throw without that
return remains unknown. The final-root empty-relative-Path iteration is corrected
without weakening either originally failed assertion.

IA-046 now keeps all bulk checkpoints/sidecars/summaries explicitly incomplete
candidates. A one-shot,64KiB-bounded final selector is checked before and after
publication, with256KiB headroom for the positive write, incomplete decision,
correction and fallback. Failure preserves the first cause; a failed correction
or unknown harness return explicitly invalidates a standalone positive selector.
Successful completion requires the settled selector **and successful harness
return**, not a second contradictory late clock. The auditor read all15 new
non-executing regressions; bounded controls now total40 (21 controls/9 evidence/
10 scratch).

The subsequently reviewed IA-048 public-evidence correction and exact443-reference
mapping repin are retained in the combined freeze
`/tmp/ktc-compiler-fixture-final-source-freeze-20261104-dsn4ut3l/`.
All **277** copied/live files (2,505,698 bytes) and its exact SHA256SUMS map match;
manifest SHA-256:
`416cfeef42e61e34359490ef75fe166e1a8fe2461cb1563a9aaf96e9ec52479e`.
It preserves all267 prior paths, changes only their compiler map and adds the
nine referenced production-source copies plus one public-evidence test. Parsed
map comparison finds **only443 provider digest substitutions**; all3,987 source
references (nine unique) match, without changed diagnostic/default/non-reference
fields.

The central corrected-source retry now actually reports **121 passes and one
explicit Windows junction skip out of122 unique cases**, zero failures/errors.
The auditor independently parsed all eleven current XML reports at
`12:40:11.684Z`–`12:40:14.323Z`: Controls21/Evidence9/Scratch10 all pass, including
both initially failed final-root assertions, and the new public-evidence test
passes. The other81 cases retain provider, model/installation, runtime-image and
native-resource controls. All277 live/frozen/source-copy hashes were rechecked
unchanged; the already retained rerun source map has SHA-256
`468567f4442b14e822eeb486ee5164b0713d44282d3f8efcce989c76e41a3496`.
The final archive in `build/evidence/compiler-bounded-units/20261104-rerun/`
was then independently checked: all eleven retained XML files are byte-identical
to the observed current reports, and the log records actual production/test
compilation, test execution and **BUILD SUCCESSFUL in 39s**. Log SHA-256:
`f24635e1877f98fd03455bce30a1065f0b9b025b390d676a261fcd53b7e49720`;
verification-summary SHA-256:
`00d71858873ca6849ae589a6138be8abdcb6da171b5eb21767c5f271d1d23def`.
No real compiler-cohort or corrected-host execution follows from these tiny
synthetic/byte controls.
These harness corrections do not retract the separate native/runtime controls,
erase previous failures, waive rules or promote synthetic hosts.

### Owned POSIX supervision: current Linux native/runtime controls passed

The auditor reread the final native C, Kotlin transport/image loader, execution
adapter, build script and tiny-test sources. The owner's seven final freeze
hashes match; selected identities are C
`453468d9b07489c79ac4a061e9ae9fc6d00d58d98c473670bb6885e3f23ea0fe`,
execution adapter
`38b51316e03eb622099fede43ff678e6417aed3c347e0a9acfa1e63f6e7eebfd`,
and transport
`12e01bad7926ef388cacbe59680b594d4bc0e41a37b91d0a4f2197e2f464ad17`.
These are the first runtime-test source identities, not a freeze of the pending
Kotlin corrections below. The auditor has not run native compilation or any JVM;
the lead ran the bounded tests and the auditor inspected their retained results.
The corrected current Linux runtime has now cleared the scoped IA-038 execution
hold, subject to each provider's separate source/test/START and owner approvals.

- The single-threaded native helper is the sole reaper of its actual fork child.
  It clears inherited SIGCHLD auto-reaping, uses private `setsid` readiness plus
  an exec gate, and retains the child with `WNOWAIT` through **all** TERM/KILL
  operations. Ownership loss revokes signaling. An irreversible final-signal
  transition precedes every reap; neither Java nor later error handling signals
  a raw PID/PGID. Linux subreaping checks the actual waitable child set to ECHILD;
  Darwin's later numeric-group check is read-only, never signal authority.
- A received `C` followed by EOF is cancellation, not parent loss; naked EOF is
  owner loss. Every outcome requires bounded FINAL/EOF/helper-exit evidence for
  cleanup acknowledgement. Missing, malformed, trailing, blocked or failed
  transport stays failed/unverified. **Identified resource defect:** the first
  transport returns a slot after helper/readers exit even without cleanup ACK.
  Ordinary signal/wait failure or uninterruptible target I/O can leave unresolved
  children, so repeated attempts need not respect the advertised four-slot cap.
  The source-reviewed correction permanently consumes that invocation's slot
  after bounded unverified cleanup, while allowing private-image cleanup only
  after helper/workers exit. In-window valid ACK may release; a later helper exit
  or broker restart is not proof of target cleanup. This bounds unresolved
  invocations within one JVM, not descendants per invocation or across restarts.
- Failure FINALs also need cross-field validation, not merely success's exact
  flags. In particular, confirmed execution requires group readiness and an
  observed non-126 exit, without exec failure; quiescence with child lifecycle
  evidence requires observed/reaped child state. Pre-fork cancellation/setup can
  legitimately report quiescence without child state. A normal exit above 127
  is not automatically a signal, and quiescence plus a previously recorded
  shutdown error still cannot acknowledge cleanup. The new Kotlin decoder checks
  these facts before assigning terminal evidence; the new ACK predicate also
  requires post-run private-image verification. The rerun below exercises these
  corrections; malformed FINALs never establish new trusted exit/exec evidence.
- CLOEXEC EOF alone cannot prove provider start. `EXEC_CONFIRMED` requires the
  completed private gate, no setup/exec error record, and a normal child exit
  other than reserved 126. Actual signal/126 exits retain raw exit/output but
  remain start-unconfirmed, even when a real target may have run. `executed`
  no longer follows merely from `exitCode != null`; successful execution also
  requires the executed status. A completed nonzero exit is not a passing rule.
- The installed code-source bundle, exact host, bounded manifest/binary and
  fresh private image bytes are checked without PATH or context-classloader
  discovery. Loader/libc interposition environment keys are explicitly
  unavailable, not silently removed. The execution adapter retains full pre/
  post deadline, cancellation, immutable-source and strict-UTF-8 checks; the
  internal transport's extra four seconds is cleanup grace, not success budget.
- At `10:56:21Z`, the auditor ran only the **four pure build-receipt tests**,
  all passing in normal and optimized modes with zero skips. No compiler or
  child process was invoked. The initial banner sanitizer accepted arbitrary
  text after `gcc`/`clang`; it now always redacts the full banner and retains
  only bounded version-output digest/size. Build receipts bind selected source,
  builder and compiler driver, explicitly **not** transitive compiler/linker/SDK
  closure, reproducibility, loaded memory or platform qualification.

The lead's independently rerun native log
`/tmp/ktchecker-native22-independent-20261104.log` has SHA-256
`33740475d3e3c7e8e2e56826d607b048b70ca050ef00c0ef65b0c03f93bd38c8`.
The auditor inspected **22/22 passing tests**, 7.769 s, zero skipped methods:
four pure builder tests and eighteen native controls, including actual owner
death, TERM-resistant orphan, retained unrelated group, C+EOF across the grace
interval and Linux syscall-order assertions. The test deletes its raw strace;
this is inspected assertion evidence, not an independently retained/reparsed
syscall trace or a manufactured PID-reuse demonstration.

The first isolated runtime execution at `11:02:15Z` compiled and ran **84 tests:
83 passed, one failed, zero skipped**. Execution17, fixture5, model-binding7 and
transport31 passed; image-loader23/24 passed. The replaced-root test rejected
safely but reported `process_supervisor_bundle_unavailable` instead of expected
`process_supervisor_image_changed`, because leaf access preceded root identity
validation. The lead retained the failure XML, log and fifteen source digests in
`build/evidence/runtime-supervisor/20261104-first/`; the auditor checked all fifteen
against live bytes before correction. The source-index SHA-256 is
`860eafac6c71dad1d981bff1c9c58e621a88d6c7760daa0e2f05d280b0f9fbf2`.
The auditor then checked the four-file correction freeze and reread the changes:
loader `be665e20c5c613a0c553ffb701f776a869dfc7a81f2d5d32a245f36f5461b22a`,
transport `2886151b2b0843523b32877b62c291ee654b3ab48957b2d4059c5b2543744db6`,
loader tests `bd2c832aedaff5f3425e09f607aabf80c2f4c7ee9457f6e906dbf4a5828a4bb4`,
transport tests `eb5b951ae8a75d2feb9c62b707f4e78a98975a224a42c46bcdb2f20b663e96ca`.
The immutable ACK/release decision prevents post-return resurrection; tests
include repeated already-exited unverified helpers, valid pre-fork and in-grace
ACK release, after-return late FINAL, image drift and contradictory failure flags.
The corrected rerun at `11:13:26.806Z`–`11:13:32.406Z` executed **92/92 passing
tests**, zero failures/errors/skips: image25, transport38, execution17, fixture5,
model-binding7. The auditor independently parsed all five XML reports and checked
all sixteen current source references in
`build/evidence/runtime-supervisor/20261104-rerun/`. Its source-index SHA-256 is
`810499cd3187d45a66b61b46fc30859fe394d458b479ae48671d7e148882a518`;
the log records actual compilation/test tasks and a 40-second successful build.
The retained native log matches the independent22 result above. The source index
is not a startup loaded-bytecode attestation. The first run does not certify the
corrections, and its failure remains retained. This supports lifting only the
current Linux-x86_64 runtime hold for separately approved/coordinated work, with
new actual START/execution identity for every provider evidence run. Tiny JVM
probes are not real compiler/provider execution. Linux passes cannot qualify Darwin/Windows,
prove every scheduler interleaving or deliberate group escape, or guarantee
cleanup after externally forced helper SIGKILL. Historical diagnostic bytes do
not certify containment.

### Kernel fairness and contention evidence

The auditor read the stress test and its retained `10:05:00.269642072Z` report
`modules/kernel/build/reports/stress/kernel-100-patches.json`. It exercises 100
distinct archived inputs, 200 subscriptions plus 16 current-head refreshes, 16
clients, four worktrees, three service objects and 12 producer threads. The result
retains 216 reviews, 668 required events and 20 independent cancellations. SQL
checks audit each serialized dispatch against worktree/client ages and sponsor
FIFO; observed provider concurrency stays within four global/two per-worktree.
Reopening, exact event sequences/digests and independent ACK/observation are checked.

This is one bounded run of a **synthetic 20 ms provider**, not three JVMs, multiuser
qualification, CPU-time fairness or an SLA. The reported SQLite writer hold maximum
was 13.36 ms, but writer admission waited up to **229.87 ms** and producers retried
`ingestion_busy` **1,123 times** before acceptance. Those contention costs must not
disappear behind the approximate 66-work/s synthetic throughput. Terminal timing
is SQLite visibility, not model delivery or FIR latency.

### Retained host request/public-response evidence

The auditor independently checked all four retained phases in
`bridges/reference-host/evidence/codex-inbox-synthetic-events.json` and
`codex-inbox-projected-synthetic-events.json`. Actual application request bytes,
request/context digests, public diagnosis/location/verification content and
projected obligations agree. These are real requested `gpt-6-astra` public-response
observations through a controlled read-only receiver, with **synthetic broker
events**: no actual Kotlin compiler/kernel integration or guarded final action.

The historical artifacts retain parsed public responses, not their original
lexical text; the recorded public-response digest therefore cannot be recomputed
offline from them. This limitation is now explicit. New captures will preserve the
exact verified public text as well as parsed content and its digest. Application
`codex-app-request:...` IDs are not inference/Responses IDs, and no internal model
trace is claimed. The official [Codex app-server documentation](https://developers.openai.com/codex/app-server/)
supports owned-thread start/resume and public turn/item observations, not this
integration's qualification. Background host polling is not model polling.

The new actual CLI → kernel → compiler → Codex artifact is
`bridges/reference-host/evidence/cli-kernel-codex-public-fixture.json`, independently
verified at `09:57:13Z` with SHA-256
`f3d9471b184a55bf04f517de472993b0e55c5a8d809508ca85e1dd60b49223e8`.
It records three actual application/public-response phases, not synthetic broker
events: P1 delivery joins A's active turn, B consumes its own independently bound
event/thread, and A consumes actual repaired P2 after adapter/thread/broker resume.

- Rehashed 23 selected bridge source files, 57 executed bridge artifacts, 23
  installed Java artifacts and the installed launcher. The frozen installed CLI
  is identified independently of the dirty checkout; its source commit is
  explicitly unauthenticated. This is **not** execution of forthcoming current
  CodeImage/JDK changes or full classloader/JDK attestation.
- Recomputed all three exact serialized application request hashes, nine framed
  context hashes and model-request bindings, and all three **lexical public text**
  hashes. Independently reconstructed every public observation, finding content,
  location, unrun verification and finding obligation; all match.
- Actual target selection has **1,097 candidate coverage rows**, not all 1,663
  target-independent records. P1 A/B payloads are 644,994 bytes, P2 is 640,774;
  result-context projections are 10,398/10,398/5,661 bytes and full required
  context batches 20,074/20,074/10,660 bytes, below the unchanged 32 KiB limit.
  Recomputed 18 original-reason-array/generated-reason-set digest checks for
  3,290/3,290/3,291 summarized redundant reasons. All other original findings,
  raw diagnostics and failure reasons are retained in the artifact.
- Both P1 consumers retain the actual `ABSTRACT_MEMBER_NOT_IMPLEMENTED` finding;
  their selected coverage is failed/unqualified. P2 has no such finding, but its
  executed selected coverage is **unknown/unqualified**, not certified resolution.
  The ACK-only gate and final gate both deny action. The identified controller
  asserts privilege/cross-client negatives, exact submission replay, disconnected
  execution and two confirmed owned-thread deletions; this audit inspected those
  assertions and retained results, not a fresh backend run.

**Retention limit:** complete original coverage rows/payloads existed in the live
private inbox, which was removed after confirmed owned cleanup. The public artifact
retains original reasons/diagnostics, selected compiler coverage and the full
coverage digest, **not all original rows**. Original payload/full-coverage and
matching-row digests therefore cannot be independently recomputed offline; the
complete-row predicate proof was checked by the live controller, not recreated
from this partial archive. No internal inference trace, authoritative physical
worktree/full-project model, guarded action or release qualification is claimed.

The earlier planning-tool and protocol-schema checks in the design audit are not
implementation tests and are not carried forward as passes.

## Findings and disposition

| ID | Source observation / consequence | Disposition |
| --- | --- | --- |
| IA-001 | Initial `ProviderResult.complete()` accepted an all-optional coverage list because filtering required entries produced an empty set. It also had no expected-set input to establish that every required rule/context was present exactly once. | Non-vacuous required coverage and `completeFor(input)` added and independently read. Independent optional-only, exact-set/context and malformed kernel-result regressions passed. Synthetic qualification fixtures do not qualify real diagnostics. |
| IA-002 | Initial `Coverage.successful()` accepted an empty or whitespace certificate ID. A provider's non-null string alone cannot establish input/provider-bound absence evidence. | Nonblank guard added; independent blank/missing certificate regression passed. Kernel durably binds original/effective input keys, provider identity and result digest. Independent stored-certificate corruption and malformed-result tests passed. These local integrity records are not third-party qualification. |
| IA-003 | Initial `RuleContext.id` joined unrestricted components with `/`; module `:a/b`, source set `c` collided with module `:a`, source set `b/c`. | Replaced with a JSON tuple; independent collision regression passed. |
| IA-004 | Snapshot replay initially keyed receipts by bare patch ID, conflating independent same-worktree clients' local names. | Changed to a digest of client/session/patch tuple and independently read. Independent real replay/isolation regression passed. |
| IA-005 | Materialization used an existence precheck followed by nonexclusive directory creation. A losing concurrent creator could recursively delete the winning creator's output in failure cleanup. | Exclusive root creation plus ownership-conditioned cleanup added and independently read. Independent repeated real contention regression passed. |
| IA-006 | Initial snapshot projection lost declared roots, exclusions and unknown inputs before provider/kernel use. A caller's `fullProject` boolean cannot replace those facts. | Scope fields added to the shared projection. Final-gate enforcement and no-partial-pass integration still require tests. |
| IA-007 | Initial config matching checked file/worktree identity but not the frozen configuration digest or finding context against the analysis scope. Its lifecycle short-circuit also preserved prior fixed/baselined labels before identity checks. | Source correction independently read: policy digest and finding scope are checked before matching; lifecycle uses a deny-default trusted verifier and bound certificate. Independent stale-policy/wrong-scope/lineage/lifecycle regressions passed. |
| IA-008 | Process-descendant polling can miss a quickly orphaned background child; report opening also followed a leaf after separate symlink checks. Later review found unverified group signaling and deadlines checked only while the process remained alive. | Earlier corrections added fixed POSIX launch, group-quiescence checks, anchored report reads and pre/post deadline checks. The historical 28-test XML does not close **IA-038's subsequently identified unsafe post-reap PGID signaling**. The later native22/runtime92 controls support current Linux owned-child runtime scheduling, not the historical PGID behavior or other-platform qualification. New-session escape, hostile same-UID report writers and Windows execution/report qualification remain outside the established evidence. |
| IA-009 | A guarded external action initially ran before consuming its gate in one DB transaction. Throw/crash after an external effect could roll back consumption and replay the effect. | Source correction reread: persist consumed cut and per-generation `action_claimed` intent before invoking the callback, retaining config/source fences; ambiguous return becomes `outcome_unknown`. Claimed/unknown generations cannot replay the action or admit more patches. Owner and independent exception/reopen regressions passed. This is fail-closed unknown outcome, not exactly-once external delivery. |
| IA-010 | Progress journaling serialized a provider DTO, but event fanout could reuse its mutable caller-owned collections. | Canonical detachment now precedes validation and is used for both journal and client fanout; result-policy callbacks also receive detached raw evidence. Source correction reread. Mutation-specific independent execution is not yet recorded. |
| IA-011 | A rule ID hash was initially the default implementation identity, so a code upgrade could retain narrow suppression anchors without a real version identity. | Default identity is unavailable/null; exact and narrow file/range/symbol selectors require/pin actual implementation identity. Source correction reread. Root additionally binds installed compiler artifact identity and pinned Detekt metadata into the policy registry. Independent changed/missing implementation regression passed; broad owner-selected scopes intentionally remain broader. |
| IA-012 | A default changed-file overlay scope could erase exclusions/unknown inputs inherited from its producer base or rebased integrated head. | Snapshot owner now unions base, head and request omissions. Independent ordinary-overlay/rebase/reopen regression passed. Overlay edits are not a complete project reimport. |
| IA-013 | CLI exit classification used structural `result.complete()` without honoring the kernel's incomplete/cancelled verdict. A coordinator-expression precedence error could also omit its preview reason when no rule was missing. | Source corrections reread: exit classification respects authoritative state/reasons/cancellation and explicit preview status; preview reason always appended. Owner CLI regression execution inspected. Exit zero from submission without waiting is admission/command success, not analysis success. |
| IA-014 | Existing private-state paths skipped ACL checks on non-POSIX hosts; directory-fsync errors were swallowed on all operating systems. | Root source correction reread: reject non-owner ACL allows and unsupported private-state filesystems; propagate non-Windows/unknown fsync failure as uncertain durability. This does not establish Windows qualification or hostile same-UID protection. |
| IA-015 | Broker enrollment committed credential hashes before separately persisting the private host credentials, leaving an unrecoverable registration gap on crash/write failure. | Root now persists private random-credential enrollment intent first; kernel exact binding-and-both-token replay is idempotent and cannot replace a registration. Source correction reread; kernel enrollment and CLI owner-intent/reopen replay tests passed. The latter is not an injected hard crash at each write boundary. |
| IA-016 | Coverage inventory could treat locally attested caller-selected cells as `release_qualified`, and omitted source-set/module/toolchain/config identities did not prevent qualification. | Source corrected and independently executed: all necessary identity/digest dimensions required for local cells; scoped `all_requested_cells_qualified` separated from permanently unavailable release certification. Five independent synthetic regressions and 31 owner bookkeeping tests pass. No actual rule or host became qualified from these tests. |
| IA-017 | Default preparation selected a platform inventory including opt-in candidates; installed providers could then execute those IDs without explicit activation or prior qualification. | Lead separated required candidate coverage from experimental activation. Source reread and four owner activation tests passed: no qualified default registry means no default execution; explicit owner subset/config can request experiments; disabled/excluded remain inactive/visible. Exact canonical activation plan is input-bound; absent or malformed plans never default to all candidates. |
| IA-018 | A generated Detekt configuration lived at a fixed output path, but its contents were not necessarily bound to the exact approved subprocess profile. Changed settings could reuse the same input/permit despite different generated YAML. | Owner binds generated configuration and installed artifacts in fixed profile digest arguments. Source reread and the exact same-original-input/permit, changed-settings denial regression executed in the new Detekt integration suite. Changing only the input configuration digest would not exercise this bug. Ktlint and other generated preparation inputs require the same binding. |
| IA-019 | Compiler and Detekt fixture harnesses originally hashed live implementation source at run end, which cannot independently identify the bytecode executed during concurrent source changes. | Historical compiler originals preserved explicitly as post-run archives, not backdated startup evidence. New 74-process compiler corpus startup source/artifact/resource capture, drift checks and exact retained evidence independently verified above. Detekt retains startup source/fixture references and seven selected class-resource anchors, not a full classloader image or source-to-bytecode attestation. Actual failed historical runs remain evidence, not current-source attribution. |
| IA-020 | Host coverage projection accepted whitespace-only certificate strings and could hide required unqualified/untested/unimplemented rows behind a structural-success summary. | Nonblank certificate validation plus separate required qualification/test/implementation counters added. Source correction reread; auditor independently reran the owner projection regressions successfully. A projected receipt applies to delivered projection, not omitted raw coverage bytes. |
| IA-021 | Codex/private JSON-line readers decoded malformed UTF-8 with replacement characters before validating evidence. | Both boundaries now use fatal UTF-8 decoding; source reread and independent rerun of both malformed-byte negative tests passed. This hardens protocol parsing, not model-host qualification. |
| IA-022 | Initial project impact stripped file memberships from its structural digest and compared only snapshot bytes. An unchanged file reassigned between source sets/compilations could produce no affected obligations. | Source correction reread: retain source/resource/compilation/configuration/generator path memberships while neutralizing content bytes for structural comparison. Independent accepted-ingress moved-membership regression and owner impact controls passed, using synthetic origin capabilities. Actual Gradle capture remains unexecuted; membership cannot be inferred from byte equality. |
| IA-023 | Initial model import checked that a dependency producer existed but not its module/variant ownership; newly added associated-compilation lists and generator properties were not deeply frozen. Unknown gaps in unselected modules could also be filtered out of graph checks. | Source corrections reread: actual producer owner/variant and associated references validated, nested collections frozen, all relevant unknown graph gaps retained. Independent producer/deep-freeze/unselected-gap controls and owner importer suite passed on synthetic origins. Complete model metadata is neither source/API semantics nor completed affected-consumer analysis. |
| IA-024 | Semantic assembly initially treated invented/malformed types as known, accepted plausible byte spans without reconciling UTF-16 coordinates, weakly checked symbols/argument bindings, and retained mutable nested DTO collections. | Source corrections reread: closed bounded recursive vocabulary, exact-source callback and coordinate recomputation, canonical symbol identities and parameter relations, detached unmodifiable trees and depth/width guards. Owner 21 unit tests, thirteen independent boundary tests and actual four-case JVM FIR evidence independently inspected above. Synthetic boundary tests do not establish additional FIR vocabulary. |
| IA-025 | Same-spelled generic local declarations could share a parameter-owner identity despite different resolved symbol IDs, because the owner key omitted local source identity. | Source correction adds actual local source identity/span plus signature/context structure to the owner key. The actual positive fixture contains distinct same-spelled local generic owners and calls to both; independently verified IDs and source coordinates. A generic name is not semantic identity, and this narrow fixture is not whole-vocabulary qualification. |
| IA-026 | Native task selection/output used basenames, so another module's similarly named task could masquerade as the selected compilation. Native/Lint source-set ownership used directory prefixes instead of exact model membership. | Source corrections reread: shared exact module-qualified Gradle task proof, duplicate/cache/skip refusal, and exact file-to-source-set compilation maps. Seven shared-runtime binding controls and the new provider-specific controls pass within Native14/Lint22. These are not Apple execution; old standalone XML evidence is retained as historical. |
| IA-027 | Verifying unchanged bytes in a coherent but narrow capture does not prove omitted project/model inputs are absent. An exporter's empty lists could otherwise be mistaken for completeness. | Import requires a separate, non-default `verifyModelInputs` authority callback before and after byte verification, covering complete relevant source/resource/configuration/generated/external inputs. Source reread; independent repeat/revocation test passed. Synthetic callbacks and exporter metadata do not discharge real Gradle capture authority; the fixed owner fixture's first actual invocation was rejected for post-execution input drift, not accepted as a model. |
| IA-028 | The initial parent-plan resolver lacked exact patch/idempotency binding, its context vector omitted ordered classpath/library/policy identity, and unsupported-slot status could hide a child's cancellation. | Source corrections reread: exact `SubmitPlanRequest` binding, all context fields hashed, cancellation visible before unsupported state. All 19 new parent tests passed in the second coordinated execution; synthetic owner/provider/fence/host capabilities do not qualify production plan assembly. |
| IA-029 | Source review found that a later manual same-snapshot submission could leave the old parent vector as the only fenced inputs; the stored parent review frontier was unused. Empty parent rule lists also defaulted to the provider-supported subset. | Source corrections reread: current review frontier must equal the latest parent frontier, and empty parent inventories are rejected. Owner's 19 passing tests include changed-argument/classpath/library/policy and identical-refresh witnesses, corruption/reopen, legacy decoding and fresh-plan recovery. A trusted plan must not silently inherit a smaller provider-defined obligation set. |
| IA-030 | CLI policy registration initially minted narrow implementation identities for catalog-only or plan-less provider routes, including a placeholder in place of selected upstream artifacts. | Source correction reread: nullable trusted per-route identity; installed compiler and verified Detekt bytes contribute, while unplanned Lint/Native and absent/unverified Detekt do not. Owner catalog/uninstalled-provider regression passed within CLI66. Read-only selector generation neither authorizes mutation nor proves that the supplied finding was emitted. |
| IA-031 | Lint/Native normalization replaced the selected compilation scope's source set with each finding file's imported source set. A valid common-source diagnostic then failed the CLI/kernel/config exact-context checks or acquired misleading suppression scope. | Source correction reread: findings retain exactly `input.context.scope`; separately model/snapshot-bound informational membership evidence identifies each primary/related source path without cross-context suppression authority. New Lint transport and production-used pure Native factory regressions passed within the coordinated Native14/Lint22 suites; no Apple execution is claimed. |
| IA-032 | Lint evidence retention validated only the line field while copying arbitrary column/end-range values; arbitrary JUnit testcase names could also reach supposedly safe public metadata. | All four range fields now require exact integers, order and captured UTF-16 bounds; exact maintained testcase names/outcomes and ISO timestamp are required. Nine synthetic Python tests independently pass in normal and optimized modes. Historical detector receipts are unchanged; retained JUnit/private receipts still have only post-run manual association, not direct run-ID/source-to-bytecode proof. |
| IA-033 | Native normalized every known compiler diagnostic even when its rule was outside a requested subset; the coordinator rejects such out-of-plan findings. All-ID fixtures concealed this composition error. | Production normalizer and direct factory guard reread: selected-only findings; known unselected warnings/errors retain raw severity and explicit selection gaps without being mislabeled parser failures. Selection gaps withhold requested certificates. Pure subset regressions passed within Native14; no Apple execution is claimed. |
| IA-034 | Coordinator durable-work version initially hashed provider IDs/version labels only, so orchestration/routing byte changes could reuse a prior work key. | Bounded code/resource image and ordered route/actual nullable implementation identities now contribute to coordinator version. Source review confirms image checks at preparation/admission/observer/source-gate/coordinator pre/post boundaries, strict UTF-8, dot-segment rejection, runtime-versioned JAR lookup and pre-open archive bounds. All 23 CodeImage tests and coordinator identity regression passed within CLI66 after the retained first compilation failure. On-disk images do not attest already-loaded/transformed JVM memory or unselected dependencies. |
| IA-035 | Actual full-catalog host delivery exceeded its unchanged context bound because thousands of top-level kernel coverage-validation reasons repeated facts already present in coverage. Dropping arbitrary failures would hide mandatory information. | Exact three-family derivation proof summarizes only redundant top-level terminal validation strings, preserving all other reasons/findings/raw/parent data. Duplicate/alias/malformed/mismatched inputs refuse compaction. Source review, independent rerun of 40 synthetic tests and the actual three-phase composed host artifact above are verified within stated limits. Full original rows existed in the live inbox but were deleted with disposable state, preventing offline reproof of all-row derivation. A shorter reasons array is not a pass or original-byte observation. |
| IA-036 | Fixed owner-model input preparation called ambient-mode directories private; repeated runtime callbacks missed added Gradle/JDK files, forbidden seed configuration and mutable/addable executable DSL cache `.class` files. Manifest bytes were allocated before their bound was checked. | Owner-private creation, bounded anchored/no-follow/strict-UTF-8 reads, complete original inventories and START/native closure are independently reviewed. The79-reference fresh-execution-home correction separates writable scratch from the exact unchanged seed; only pre-enrolled unchanged dependency copies become artifact authority. All47 model controls now pass. The first actual exit0/exec-confirmed run still failed input verification and is retained unchanged. The second actual fresh-home process passed execution/native/input verification but import correctly rejected its compilation domain; model identity/certificate remain null and qualification false. The auditor rechecked retained derivation evidence, not all433MB of seed/home file contents. |
| IA-037 | Architecture policy overlap validation still used quadratic list membership; graph/witness JSON could allocate far beyond the report bound before the post-serialization check, using allowed long identities and many edges. | Hashed overlap and pre-copy/pre-serialization text bounds independently reread. Scope/origin/edge/transitive budgets, escape-safe JSON upper bounds and finding/SCC preflight now reject before large joins/encoding. All 17 graph tests passed, including three new long/escaped-input bounds cases. These synthetic controls are not actual-project latency/heap or rule qualification. |
| IA-038 | **Critical:** the prior `IsolatedProcessRunner` signaled `kill(-process.pid())` after the JVM could reap the original group leader, including final KILL after `waitFor` and reader joins. `setsid` and later quiescence probes do not pin a reused PGID; an unrelated later same-permission process group could be signaled. | Root stopped real provider/corpus/model-export execution. The final owned-child C/Kotlin replacement is now independently source-reviewed: exact child held through every signal, ownership-loss abstention, irreversible signal-before-reap transition and bounded terminal cleanup, without Java PID signaling. C+EOF and exec-start ambiguity corrections are reread; the lead's native22 result is independently inspected. First runtime83/84 passed, with one safe-reject reason-code failure retained. Audit additionally found slot reuse after unacknowledged cleanup and missing failure-FINAL consistency checks; the four-file correction freeze and fresh92/92 runtime result are independently inspected. The hold is lifted only for this current Linux runtime and separately approved/coordinated provider work; permanent no-ACK slot poisoning and post-run image verification remain enforced. Historical passes and diagnostic bytes do not certify containment or qualify new sources/hosts. |
| IA-039 | Project-model derives `AnalysisContext.toolchainDigest` from canonical `ModelToolchain`, while the current compiler expects its distinct whole-installation/runtime `ktchecker/kotlin-jvm-toolchain-3` digest. The controller forbids rewriting imported identity and the compiler correctly rejects a mismatch. | **Open actual-composition blocker:** the fixed-mode typed bridge is source-reviewed against11 frozen/live files; all43 synthetic bridge tests passed in the80-pass/one-Windows-skip compiler batch. It retains all three digests and requires same-installation/per-attempt live capability plus independent process authority. The source-reviewed CLI typed composition's26 synthetic controls now actually pass within95/95 CLI; both preceding production/test compilation failures and their narrow corrections remain explicit. No identity rewriting, model-to-legacy fallback or inherited qualification is permitted. Actual accepted model export, real compiler composition and live final fences remain unproved. |
| IA-040 | Prior compiler identity sorted classpath by coordinate, omitted selected paths/order, bound only Java launcher bytes and could lack intrinsic profile/toolchain binding when the input omitted its digest. Whole-JDK capture initially normalized a dot-segment selection, changing possible OS lookup semantics. The new TS fixture briefly repeated that bypass before forwarding the selection. | Current ordered coordinate/digest/path identity, bounded link-free whole-JDK tree, intrinsic toolchain profile and literal absolute no-dot selection are independently reviewed. Compiler/runtime-image and CLI installation/order controls passed, most recently within121 compiler unit passes plus one explicit Windows skip. The TS parser and direct constructor now reject literal dot components before normalization; current retained59 tests and an independent19-test no-exec rerun pass. The new actual-host attempt separately failed its privacy guard; these unit passes imply no successful current compiler/host composition, compatibility or qualification. |
| IA-041 | The scheduler could call `PreparedInputObserver.prepared`, allocate authority, then cancel/throw before entering the coordinator's cleanup block; partially prepared grants could outlive that attempt. | Source-reviewed `finished(effective)` runs once whenever preparation began, including partial failure and pre-provider cancellation, outside ledger/config/source locks. Cleanup errors preserve raw terminal results/findings and add broker validation failure. All four real-scheduler/synthetic-grant controls actually passed. Owner cleanup must remain idempotent and exact-attempt scoped, releasing both model and ordinary grants independently; composed model execution remains unproved. |
| IA-042 | First schema2 routing execution exposed a genuine second-broker-open race: SQLite removed its transient WAL between `Files.exists` and `privateFile`/`getFileStore`, raising `NoSuchFileException` before the overlap-route test reached admission. | Exact four-file correction independently reviewed: final initialization connection attaches to WAL and stays open through DB/WAL/SHM hardening; permission/symlink failures still propagate. The48-reopen concurrent regression and overlapping-route method passed in the69/70 second batch and the75/75 current kernel batch. Schema/table and recipient-error controls also pass; both preceding failed batch logs remain retained. |
| IA-043 | **Critical constructor defect, now corrected:** `immutable=1` probed the main file while other live SQLite WAL users could checkpoint/change it, violating SQLite's immutability contract. The second fairness run actually failed there with `SQLITE_CORRUPT`. | Source-reviewed normal `mode=ro` WAL-aware explicit transaction checks version and required tables before any writable connection. No immutable/raw-descriptor/marker substitute remains. All75 current kernel controls, including the previously failing fairness case, latest-WAL schema visibility, actual write denial and checkpoint/reopen concurrency, now pass. Both failures remain retained; no persistent physical corruption or cause beyond the invalid API use is inferred. Transient reader/index infrastructure is not a migration or obligation waiver; old binaries are not dynamically fenced. |
| IA-044 | Former `sqlite-jdbc:3.50.3.0` pin uses SQLite3.50.3, within the upstream WAL-reset corruption bug's affected range. Multiple connections doing writes/checkpoints are part of the required kernel workload. | Independently confirmed in primary SQLite WAL documentation §11: affected3.7.0–3.51.2, fixed3.51.3 and later with3.50.7 backport. The reviewed pin is now3.54.1.0; the actual loaded JDBC/SQLite version test and75 kernel controls pass. This closes that affected-pin exposure for the tested Linux dependency, not source/binary authenticity, other-platform qualification or proof of the observed immutable-probe failure's cause. Independent clients/processes remain supported obligations. |
| IA-045 | New bounded compiler harness unconditionally cleaned registered scratch after outcomes that may retain live descendants, including unverified native shutdown or an entered-provider throw without an observed conclusive outcome. | **Correction source/unit verified.** Irreversible whole-tree quarantine now precedes any job/final-root inventory or deletion on unknown/mismatched/unacknowledged actual child state; no reenrollment is allowed. Returned no-observer refusal was checked against the actual provider's pre-runtime control flow. Current bounded40 controls pass, including both previously failed root-cleanup assertions. Execution confirmation remains distinct from quiescence; these synthetic regressions neither execute a real cohort nor replace the separate native runtime's tested process-slot retention. |
| IA-046 | Bounded harness computed `runComplete` before bulk terminal serialization/publication; the post-publication deadline throw was outside its retained-failure path, so a failed bounded task could leave `run_complete:true` artifacts. | **Correction source/unit verified.** Bulk artifacts always remain candidates; only a bounded, post-write-clock-checked selector together with successful harness return establishes completion. Distinct incomplete correction/fallback preserves the first failure, and failed correction or unknown return leaves a stale positive unconfirmed. New deterministic clock/publication tests now pass within bounded40; no real bounded cohort has run. Qualification false alone never excuses a false completed-cohort claim. |
| IA-047 | Actual-host demo deleted its public/private run tree after confirmed model-thread deletion, although RPC closure/SIGKILL is not a native child-group shutdown receipt. | Root finding independently source-reviewed. The narrow correction removes automatic run-tree deletion entirely and always reports native cleanup unverified/manual owner reconciliation required, independently of exact thread deletion or delivery success. Current59 TS controls and independent19 no-exec controls pass. This is conservative retention, not native-quiescence evidence or permission for later automatic cleanup. |
| IA-048 | The actual new-distribution host attempt failed its unchanged privacy guard because compiler finding toolchain evidence included the owner-private selected JDK root in public summary text. | **Narrow correction source/unit verified; actual host retry pending.** One pure formatter removes only that literal path, retaining both exact identity digests and unchanged disk/not-loaded-code limitations. Inverting the extraction reproduces the prior provider hash exactly; private root/executable/profile/materialization authority is unchanged. The synthetic no-exec public/private regression now passes. The actual failed host report, unobserved delivery obligations and unconfirmed thread/native cleanup remain retained; no privacy guard is weakened or host success inferred. |

Production sources and actual test results are reviewed as they arrive. The
acceptance boundaries above remain open until evidence is recorded here; neither
an implementation promise nor this checklist closes them.

## CLI composition and residual boundaries

Source review confirms that ordinary MCP exposure is submit/status/events/cancel,
not owner preparation, overlay editing, policy mutation, execution approval or
privileged ACK/observation. The broader RPC pipe includes registration and adapter
credentials and therefore must remain a **trusted private host pipe**, never a
public/model endpoint. Owner-prepared submissions are checked against their exact
client, patch/idempotency tuple, registered snapshot and frozen policy. Execution
permits bind effective child analysis keys rather than accepting `approved=true`
from JSON. Policy caches are client-binding plus analysis-key scoped.

The read-only exact-selector helper checks prepared client, registered snapshot
and captured policy binding, but deliberately does not certify caller-supplied
finding emission. Mutating policy still requires a separate explicit owner action.
Reviewed aliases are exact routing equivalences only: ambiguous names, known
excluded owners and semantic refinements cannot silently collapse into another
canonical detector. Aliases do not enable rules or establish qualification.

The ordinary scalar composition imports one owner-declared compilation, marks it
`fullProject=false`, and holds CONFIG → SNAPSHOT at source checks while declaring
`authoritative=false` with explicit model/live-worktree reasons. This appropriately
prevents a partial preview from authorizing a full-project final action. It does
not yet establish complete Gradle/KMP discovery, generated/model inputs, affected
consumers or clean/incremental equivalence. Independently certified execution of
all 1,663 requirements, required platform matrix, precision and latency remain open.

Current compiler selection uses a bounded deterministic installed-JAR inventory;
owner-supplied project classpath order and repeated entries are preserved, and
conflicting same-path digests reject. An explicit `--compiler-java-home` rejection
**never falls back** to the broker JDK or launcher-only identity. Other commands
remain usable with exact unavailable compiler coverage and retained selected
artifact identity; different unavailable reasons affect provider/coordinator
identity. An explicit other parser/compiler version cannot silently borrow the
bundled 2.4.21 standard library. Those unavailable/version/order tests, the six
synthetic installer tests and the CodeImage composition tests actually passed in
CLI66. They do not execute the newly selected real compiler/JDK or provide the
actual model-to-compiler execution proof.

Kernel tests demonstrate mechanical computation sharing; the current CLI allocates
a fresh materialization path and binds each preparation to one client. Since the
path participates in its conservative key, cross-client **CLI-level** deduplication
has not been demonstrated. Real host receipts, model inclusion and guarded final
delivery must not be inferred from synthetic kernel/source/receipt fixtures.

### Typed owner-model CLI composition: synthetic controls passed, real path pending

The auditor read and rehashed the eight-file `ModelPlans`/Workbench/Coordinator
freeze, including **26 authored tests**: ModelPlans sixteen, kernel integration
four, coordinator three and Workbench three, plus the shared owner fixtures.
Production hashes are `2e7d3962cbb1ce8760315cdbef6ecc675a67104291a5af9f3f0e9d3559b164d1`
(`ModelPlans.kt`), `69193eb99b3e6ae486cf7aac374a14a0d3dccfca019fa24ec539081afe7d2e03`
(`Coordinator.kt`) and `ae7d253e69c9161d878f8a6381bd59000a414df873585b43328245d0bc981279`
(`Workbench.kt`). The first compilation attempt rejected two ambiguous
ConcurrentHashMap `in`/`!in` operators. Reversing only the correction to explicit
`containsKey` reproduces the original `ModelPlans.kt` SHA-256
`d7c3fd2b7570e257e0d3a17123e9ea8f72da460f2f136ce3f24ff3d1cf578494`;
the other seven files were unchanged at that checkpoint. The next integrated
batch compiled CLI production but rejected the test's cross-module property
smart cast at line359. The exact test-only correction stores
`assertNotNull(child.execution)` in a local before copying its arguments; every
negative projection remains asserted. Its reviewed hash is
`47ec5c477ade546480e612e66827212a95cbfe2cf5ea304f050f355afbfaa50e`.
Reversing only that edit reproduces archived hash
`ceeea7ae59cd2b4f3176215f3fb3e7fc4fcd0495d212dc35f63afb44e5a6b40d`.
Both original build failures remain retained. The corrected actual **95/95** CLI
rerun includes all **26** typed-model tests (sixteen/four/three/three), as
independently verified above. These are synthetic composition controls, not a
real accepted exporter-to-compiler execution.

`beginModelReview` fixes the exact durable client/request/config revision and
explicit owner consent before import. Only live typed intent/model/materialization
capabilities proceed; constructor DTOs, JSON and restarted records do not restore
authority. The full catalog and every before/after context remain obligations,
including removed/retargeted/native/plugin/unknown-argument unsupported slots.
Provisional preparations are inert until whole-plan durable commit. Generic scalar
submission rejects model-plan records rather than bypassing that commit.

The exact imported final profile/subset goes to the same selected compiler
installation in fixed `VERIFIED_MODEL` mode, with independent model and ordinary
execution permits issued for the effective archived worker attempt. There is no
profile regeneration or legacy fallback. Model routing follows private
attempt/output state, not editable model tags; altered projections deny. Cleanup
uses `try/finally` so both grant registries finish independently. Restart retains
obligations and policy evidence, not live model authority; execution abstains with
`model_live_authority_unavailable`.

These tests use fake JDK/JAR/export/domain inputs. The kernel provider inspects
grants but never invokes a compiler; Workbench tests have zero workers. Shared
cross-client model-input preparation is deliberately denied, not an implemented
production route. Reusing the same exact prepared input for a new request is also
conservatively denied. Source/vector gates remain nonauthoritative and a scalar
gate cannot certify a multi-context plan. Neither this review nor the already
passing synthetic bridge/controller suites establish actual model/compiler/host
composition or live final-action fencing.

### Actual-host fixture selections: argument controls, not an actual host run

The auditor read all three changed fixture/demo/test files and independently
matched their before/after source and compiled-byte maps in
`build/evidence/host-fixture-arguments/20261104-dot-segment-rerun.6ti7jW/`.
The retained TypeScript build completed; its log SHA-256 is
`d6fd5701d06e84a10a85831ce53609dae0358c872d857b621edaba9c9145e806`.
The full TAP has **58 unique passes**, zero failures/cancellations/skips/todos,
SHA-256 `56bf73bcae615129577c2e37a24036021fc77c02bcb925459cb3aa27f24ad0b7`.
At `12:09:37Z` the auditor independently ran only the existing compiled
`actual-cli-arguments.test.js`: **18/18**, no build, CLI, compiler, app-server or
model execution.

Review found that generic `path.resolve()` initially collapsed the literal JDK
selection before the runtime verifier could reject `link/../jdk`, potentially
selecting a different path than the owner's OS lookup. The correction rejects
exact `.`/`..` components using both separator conventions before normalization
in **both** argument parsing and direct fixture construction. Legitimate dotted
names and explicit relative nondot names remain supported. Fixture source hash:
`e4bd9f25e9901e742327b6ddfbd801e4b8249a118dc6ec9f0ca7e7d7a50f11db`.

Inference approval, launcher, complete compiler-JDK selection and a **new**
evidence pathname are independent explicit inputs, without ambient JDK fallback.
Every owner command and lazy RPC restart retains the same selected JDK; conflict
options fail before process launch. A `wx` evidence reservation precedes even
temporary runtime-state allocation. Its single open handle permits one write,
checks inode/device/link/size identity, syncs and never replaces an existing
historical pathname. Tests cover concurrent reservations/writes, symlinks,
replacement, external edits and hardlinks. This is not a hostile-owner sandbox.
The demo correctly labels JDK verification as the **actual CLI runtime verifier's
boundary**, not successful argument parsing. These passes do not reexecute the
historical host demonstration, qualify a JDK/compiler or certify any host/rule.

The root subsequently found **IA-047**: deleting model threads did not justify
deleting run files potentially still held by unverified native descendants. The
auditor independently reviewed the approved **always-retain** correction:
fixture `a798b89de6c9b450ce4650bc8013790f4b9e5fc57b0f86ecaa978243648e5522`,
demo `93e78667bb37905d33c70a7ed31cca21d780ff27a1fc243e5fcfe463ce9f6a8c`.
All three original source copies and compiled-byte references in
`build/evidence/host-fixture-arguments/20261104-retention-rerun.qMj4Fh/` match;
its TAP independently counts **59/59**, no failures/skips, SHA-256
`f102cbd403cee66f3f85c9b3727e74d1f8e04fec187b93eba09616cba6e07312`.
At `12:28:31Z`, the auditor independently reran **19/19** existing-compiled
argument/retention controls without CLI/JVM/compiler/model execution. The demo
now retains the **entire public/private run tree** even with zero threads or
successful delivery. Exact owned-thread deletion remains separate; initial and
final evidence explicitly says native quiescence unobserved, runtime cleanup
unverified and automatic deletion unauthorized. Only owner stderr carries the
private recovery location. Manual owner reconciliation is still required, not
authorized by a successful delivery assertion or this source review.

### New-distribution actual-host attempt: privacy failure retained

The actual attempt at `12:30:14.808Z` failed at
`actual_p1_diagnosis_and_privilege_negatives`, not a completed demonstration.
The auditor checked only bounded public metadata/field-path diagnostics, not
private model transcripts. `build/evidence/actual-host/20261104-first/result.json`
and its separately retained failed copy are byte-identical, SHA-256
`04c2a3a77667f243adf6ac39c05f8b135961f83e1ac649dcd41f60619df37950`.
They record **verified=false, zero completed phases, no guarded action, no
full-project model and releaseQualified=false**. The compiler-completion assertion
was not recorded before the guard failed; its initial false field is not proof
that no native/compiler process ran.

Consumer A retains three required accepted/progress/result events, all inserted
and acknowledged but **none observed**; consumer B retains zero events at this
aborted stage. ACK is not a model-observation receipt. Of two known owned threads,
two deletions were attempted, one confirmed and one unconfirmed. Full run state
is retained; native quiescence remains unobserved and automatic deletion
unauthorized. Recovery needs independent owner reconciliation, not inferred
permission from return, thread deletion or a historical host pass.

The root's field-only diagnostic identifies the private selected JDK root in
`/payload/(parsed)/result/findings/0/evidence/3/summary` and the projected public
context for progress and result. Its SHA-256 is
`7a5d565ac8b9f8aee0473483dbb372b5b5273cfbcfef3217032ce30897481cd3`;
the private value is not reproduced here. The guard remains unchanged.

The auditor independently source-cleared the two-file IA-048 correction in
`/tmp/ktc-compiler-public-evidence-source-freeze-20261104-r1LLZMiW/`:
provider `68be18660f63561d95577da133702c1863bce4edd792bfaebf6c9a914140c920`,
test `903186ec903a4e8097457fecb19660a799c126885dbb096b3c44b180d5cfa5f9`.
Reversing only the helper extraction/path removal reproduces the exact previous
provider `0468cc11353ad5d3f5e2c5cfe07b0465b69ac6d1b4511afeea12dccd56f7762c`.
The formatter keeps runtime-image and toolchain digests, no artifact path, and
the same attestation limitations. The new synthetic-JDK test asserts that public
evidence excludes private JDK/JAR/materialization paths while private binding and
verification remain intact; it never launches those bytes. Neither this source
review nor the now-executed public/private unit pass replaces the still-required
actual host retry.

## Project-model/impact and owner-plan boundaries

The separately reviewed model importer uses a deny-default, exact single-use
owner permit. It binds namespace, snapshot, configuration, exporter and actual
execution, checks anchored file/artifact bytes, repeats trusted origin checks and
returns nonserialized verified capabilities. Caller-controlled JSON flags do not
grant authority. The Gradle exporter runs arbitrary project configuration only
under explicit trusted execution consent; it is not a sandbox.

The impact planner uses both old and new membership/dependency/refinement graphs,
widens on unknowns or identity/configuration changes, and keeps removed consumers
as `removed_unresolved` rather than silently retiring them. Ten independent model
tests and the historical 27-test owner suite passed, including accepted-ingress
IA-022/023 controls; origin/export capabilities in those tests are synthetic and
**those tests execute no real Gradle exporter**. In particular, a merely coherent **narrow** capture must not become a
complete project model because omitted directories look empty to the exporter.
The new mandatory, repeated `verifyModelInputs` callback makes this explicit;
actual authority must establish the whole relevant read set, not just unchanged bytes.
Nor may one modeled compilation's `fullProject` value substitute for durable
obligations across every affected compilation. Atomic parent-plan/vector-fence
integration has separate synthetic-authority tests below, not coverage by earlier
kernel scalar passes. No clean/incremental
semantic-equivalence result is claimed.

The separately reviewed fixed owner-model fixture pins its build/source/domain
contract rather than trusting exporter-reported empty lists. It checks complete
source/resource/configuration/compilation membership and the known empty
generator/auxiliary/composite domain; runtime artifacts must enter a private
registry before execution. The actual pinned Gradle ZIP is compared with all
selected extracted Gradle files and directory topology. Only a real private runner
completion plus fresh report can issue import authority. This remains a **fixed-fixture harness**,
not general project enrollment. The first actual invocation below did not yield
an accepted model. The source
now creates private roots before copying, preserves empty directories, rejects
JDK leaf-link flattening and rechecks full Gradle/JDK inventories on every
callback. The corrected seed checks default-deny every added directory and every
non-lock file, including `.class`, `.bin` and `.properties`, and rehash existing
bytes/size/executable flags rather than purging or enrolling new cache outputs.
Only regular seed `.lock/.lck` files inside registered directories may vary;
aliases and special files are rejected before that exemption. Bounded no-follow
manifest/contract/source reads reject oversize inputs before buffer allocation
and abstain without secure-directory-stream capability. Two tiny Python preparer
tests freshly passed at `10:51:45Z` in normal and optimized modes with explicitly
synthetic ZIP pin/input bytes, no vendor execution.

The newer `11:13:01Z` freeze, SHA-256
`742648beaa58614156522b5829640426b70ab324a4166ce83400398010e5db3d`,
adds strict UTF-8 and native START/completion closure. The auditor rehashed all
**77 references** (71 owner files plus six runtime source dependencies), with no
mismatches, and read the new code and tests. Before any fixture process starts,
the harness preserves its selected authored sources, the actual provider-runtime
code-source JAR or full development class/resource trees, and the native manifest,
binary and build receipt. Fixed class roles must share the runtime code source.
Expected native identity is fixed at START; the private invocation captures the
**actual** runner outcome before validating terminal-confirmed execution and exact
supervisor identity. Ingress reuses that actual outcome, never a later inspector
result or decoded receipt. Captured bytes/tree names are reverified without
enrollment, and evidence allocation is bounded before serialization.

The new **37 model / 35 config / 17 architecture** tests actually passed in the
central `11:15` run; all eleven XML reports and source references were
independently inspected as recorded above. Four new startup/completion tests are
pure synthetic comparisons, not actual captures; malformed-UTF-8 controls add
another model test.
Runtime native22/unit92 passed. This runtime-only image capture does **not** cover all
model-harness/importer/dependency bytecode, source-to-binary builds, loaded JVM
memory or rule/platform qualification. The old `setsid`/`kill` registry is not
retrospectively evidence for the replacement.

The first actual owner-fixture invocation captured START at `11:29:02.081836091Z`
but returned **unavailable**, with **no imported model or certificate**. The
auditor inspected the retained private result (SHA-256
`4e6e35b59ada150fd60ef90f6b357d055672a0fc44922ba085453495e009d8d9`).
Its actual process outcome is `failed` with `snapshot_changed_during_execution`,
exit **zero**, `executionConfirmed=true`, the matching native identity, and no
cancellation/timeout/truncation. The outer
`owner_fixture_supervisor_execution_not_confirmed` reason is misleading shorthand
for the failed completion predicate, not evidence that exec was unconfirmed or
native cleanup failed. The source classification correction now separates actual
execution confirmation, identity mismatch and process-verification failure and
retains the original outcome before rejecting it. The first failed original is
unchanged; mutable original seeds cannot be accepted or enrolled afterward.

The auditor recomputed START identity
`sha256:6983638110c8ba4be79dbde525b275830da3f827ffe59f4f854d4e352f846160`,
verified all **nine preserved inputs** (317,657 bytes), all eight authored-source
originals and the three native entries within the captured runtime JAR. The
manifest and binary match the actual outcome, including binary SHA-256
`c6f16891a34b53847c780d1af7972d78f613e631341e621404ca121c6332fb63`.
The later live runtime JAR was rebuilt at `11:31:03Z`; only its informational
build-receipt entry differs, while the START copy remains intact. These checks
do not rehash the complete prepared Gradle/JDK/seed inventory or establish a
successful model export. Empty returned context lists on this early failure do
not waive the fixture's **four** required compilation contexts.

The subsequent execution-home freeze at `11:47:13Z`, SHA-256
`f52bcfc69c975101ec114490832291a443b1d40e253f243eb2577c0a46af2dd6`,
was independently source-reviewed. All **73 owner references** (523,056 bytes)
and **six runtime references** (102,310 bytes) match. The new 300-line helper,
seven tiny-home tests, runner registration/freshness paths and three additional
completion/classification tests bring model source to **47 test methods**.
All47 actually passed in the `11:49` model batch independently verified above;
the separate CLI compilation failure does not erase that executed model result.

Before launch, a fresh owner-private execution home is copied from the exact
already verified immutable seed. Evidence binds the source-registry digest,
concrete roots, complete initial file/empty-directory mapping, distinct file keys,
permissions, bytes and sizes. Anchored/no-follow operations, no hardlinks,
CREATE_NEW destinations and explicit entry/file/total/evidence bounds prevent
post-hoc enrollment or reuse of a failed partial copy. The exact initial map is
checked again before launch. Original seed/runtime inventories and bytes continue
to be independently checked; only the execution scratch may generate/change
ordinary caches after launch.

Only pre-enrolled `files-2.1` dependency copies may enter artifact authority;
their concrete path, original digest/size, copied inode, private read-only mode
and fresh bytes must remain exact. Generated equal-byte JARs, project outputs
and cache additions do not join the registry. Runtime identity version **three**
includes input, START and home-derivation digests. This is a fixed trusted-fixture
contract, not general project enrollment, hostile-same-user isolation or compiler
qualification. The auditor performed no large preparation/copy or exporter retry;
the first failed seed must remain frozen rather than be rebaselined. Successful
home derivation alone would not close the known exporter/compiler/options/plugin/
project/friend/variant gaps or waive any of the four contexts.

The second actual owner export, using a separately prepared immutable seed and
fresh execution home, **did execute successfully but did not produce an accepted
model**. The auditor independently inspected
`build/evidence/model-export/20261104-fresh-home/`, its **68 contemporaneous source
copies** (535,872 bytes), all three retained evidence/report originals and copies,
and its log. Source-map SHA-256:
`6d8668f6802a5116abd5c339416533a955322ab5e0b356e789df22c4c0bcc5d5`;
actual-result SHA-256:
`64cb528f096b20db36e46f9323646bd3a363a0360e3c5c3ed7159316fb4a167c`;
log SHA-256:
`b6b671e5dcfaa53ea3cfb77555a612419f2a0e9197bbf7ccdfbc2bbac05b4451`.
The real outcome is `executed`, `executionConfirmed=true`, exit **zero**, exact
reviewed native identity, no reasons/cancellation/timeout/truncation, with the
post-run original-seed/runtime verification callbacks successful. Outer state
nevertheless remains **unavailable**, reason
`owner_fixture_compilation_domain_incomplete`, `qualified=false`, and **null model
identity/certificate**. The outer Gradle task truthfully ends `BUILD FAILED in
1m 14s`; successful subprocess exit is not a successful import.

START was captured at `11:56:42.907872225Z`; all **ten preserved inputs**
(341,296 bytes), their originals and the three native entries in the preserved
runtime JAR were independently checked. The auditor recomputed:

- START: `sha256:24b9dbee542b745d7a603babe278ba114ffdea66a66e6bce646fb1d1b952b6b4`;
- home: `sha256:62da7f6a90d6c13f92723ea897b56637992456fd32df21a565b2baf341e00267`;
- input: `sha256:2166ffcc3e4150235b986a7ce4b20139446fce305d6cfc2735229cf3b300a2f1`;
- runtime `/3`: `sha256:f5bab381000eeee81c113385e41f3ddb30abfb89bab5ad477047294e82208e4e`.

The home evidence at `11:56:53.284133808Z` contains **13,589 entries**:
5,124 files totaling433,802,459 bytes and8,465 directories, with distinct source/
copy file keys and106 pre-enrolled dependency copies. The auditor checked the
5,697,821-byte evidence and its digest, **not a new full rehash/copy of the large
seed/home**. Generated caches are still not dependency authority.

The retained raw export (27,154 bytes; SHA-256
`21b973d309ffa065e29bd99627cb868fda89854d5d770d190c42268c3b2cb4d4`)
has three modules, four source sets, four compilations, five configuration inputs,
19 buildscript artifacts and12 issues. The fixed contract requires
`:consumer|jvm|main`, `:consumer|jvm|test`, `:lib|jvm|main`, `:lib|jvm|test`;
the report instead has an **empty target component** in all four identities.
The12 issues also retain unavailable compiler/options/plugins/JDK, missing project/
friend artifacts, produced variants and resolution graph. These are not repaired
or waived by the empty lists in the rejected result envelope. No imported model,
compiler composition, completeness, rule credit or qualification follows from
this verified process execution.

### JVM model-to-compiler bridge: source reviewed, not composed

The auditor checked all eleven frozen copies and live files in
`/tmp/ktchecker-jvm-model-bridge-source-freeze-20261104-mzcofh39/`;
`SHA256SUMS` has SHA-256
`18b34d9986933b5c9bf1882abc94f5ff25bb44060376359a764299636feabf90`.
The reviewed implementation preserves the aggregate model, per-compilation
model-toolchain and installed compiler/JDK digests as **different identities**.
Immutable typed views expose already captured installation data, not authority
or a fresh enrollment. A live importer-created model is bound to the same actual
installation, ordered compiler JAR path/bytes/size and complete JDK file projection;
the selected runtime separately retains directories and executable policy.
Compiler-parent no-link checks before/after verification detect ordinary drift,
not hostile races or continuous inode identity.

The installed provider mode is fixed, not selected from input metadata. Model
mode requires an exact, revocable per-attempt live permit in addition to ordinary
process authority, even after all model tags or digests are replaced. The existing
Workbench label is allowed only on the separate legacy route with exact
`owner-declared-single-compilation`, partial-project coverage and `fullProject=false`,
without another model key or binding property; this shape cannot qualify rules.
Model-mode qualification is always denied rather than inheriting legacy rows.

Preparation freezes the **whole** final owner input and profile map, then retains
the exact compiler-rule projection. Attempt binding accepts only the kernel's
source/output locations and positional `classpath/artifact-N.jar` relocation,
without changing membership, arguments, context, policies or parent obligations.
It verifies the whole frozen archive and installation; accepted attempts never
reopen original source/dependency paths. Unsupported plugins, friends, refinement,
Java source roles, unknown/path-bearing original arguments and any model gap deny
binding. Unsupported actual contexts cannot be silently dropped.

**43 new tests actually passed** at `11:26:47.061Z`–`11:26:48.212Z`:
view4, binding16, attempt10 and provider13, zero failures/errors/skips. The auditor
independently parsed all seven reports from
`build/evidence/compiler-model-bridge/20261104-first/`: **81 occurrences, 80
passes and one explicit Windows-junction skip**, with the existing provider15,
image13 and new native-provenance9 controls. Production/test compilation and test
tasks actually executed; the log ends `BUILD SUCCESSFUL in 40s`. All **66** batch
source references match live files and all eleven bridge-freeze references remain
unchanged. The batch source map SHA-256 is
`20f94d09741755bbbb304b1f175a8868da5d9e15605d187329057aefc398a2bc`;
the log SHA-256 is
`a7f910f9a38a751c84e4243c8277cc9196622ed9ee7dd288c0f0c3847598e147`.

These tests use synthetic export/domain authority and nonexecutable JDK/JAR
bytes, actual importer/snapshot objects and a simulated archive callback. Every
process-authorized test has an unconditional prelaunch or fallback verifier
stop. These tests do not establish real Gradle
argument compatibility, the actual four-context fixture, independent clients'
approvals, composed CLI/kernel/provider execution or the final live source/config
fence. IA-039 remains open at those integration boundaries.

### Parent plans, lifecycle and explicit architecture policies

Source review finds parent acceptance stages every slot before one SQLite
transaction commits parent/children/slot links/required events. Unsupported
contexts remain incomplete obligations without fabricated provider execution or
coverage certificates. The historical schema1 implementation rejected cross-client
required recipients before acceptance; the new schema2 route is reviewed below.
A scalar-only
source fence cannot certify multiple contexts: the trusted vector fence must
hold the entire source/configuration context through the guarded action. The
19-test rerun passed with real SQLite/child-process mechanics but synthetic
registry/provider/source/config/observation capabilities. Installing
archives before commit can leave orphan bytes after failure/crash, outside the
DB-accounted quota; safe stopped-service recovery and a total-disk bound remain
separate concerns, not justification for racing live deletion.

Stored plan definitions are size/digest checked and decoded on status/prior/latest
and gate paths; corrupt or matching-digest malformed JSON fails closed. Current
review frontier equality prevents a same-snapshot manual refresh from borrowing
an older parent vector, including an otherwise identical refresh. Narrower later
plans do not erase earlier context obligations. Parent-acceptance delivery is
mandatory. Null parent data is omitted from legacy scalar wire output, but mixed
old/new parent-aware brokers are unsupported: all processes must stop/upgrade
together. Digests detect accidental corruption, not hostile same-UID database
rewriting, and no general mixed-version safety is established.

The schema2 route source freeze was independently rehashed and read. Routing
requires an explicitly installed, default-deny **whole-route authority**, not a
transport DTO or recipient ID lookup. Every recipient supplies its own exact
canonical local plan, actual adapter credential, distinct binding-sensitive
policy/approval identities and live preparation callback. The complete originating
declaration is preserved, not narrowed to the initiator's projection. Source/model
checks run before and after staging outside locks; actual enrollments, collisions,
all quotas and all clients' admission states are checked again in the one commit.
That transaction publishes every parent/child/slot, route/member link, permanent
marker and required local outbox. A previously accepted recipient prefix cannot
be silently attached to a distinct route. Equivalent immutable computation alone
does not share policy, execution approval, cancellation, delivery or observation.

The private whole-route digest and semantic checks cover every member's binding,
request, complete input/context vector, parent/child/work links and acceptance
outboxes on status, exact retry and gate paths. Other clients' requests, source
paths, preparation identities and route record are not exposed in local payloads.
Exact durable retries after restart do not mint new live owner grants. The
`routed_obligations` marker and redundant route links permanently require
**`cross_client_finalization_unavailable`**, even after completion, cancellation,
ACK/observation, restart or later local/scalar work. No combined live final fence
or production routing-owner installation is claimed.

Schema2 creation reserves only a genuinely new file. Existing empty, unversioned,
schema1 or unknown-version stores are rejected, not migrated/repaired/reset.
The first implementation attempted an immutable main-file compatibility probe
before a normal WAL-aware check. Although all **four** schema tests passed,
including schema1 data in a live WAL and an already-open old owner, the second
batch exposed why this probe is **unsound**: SQLite may checkpoint/change that
main file concurrently. `immutable=1` disables the locking/change detection on
which a live view depends. The observed constructor `SQLITE_CORRUPT` failure is
retained, not interpreted as proof of durable physical corruption.

The SQLite [URI documentation](https://sqlite.org/uri.html#uriimmutable)
explicitly warns about incorrect results and `SQLITE_CORRUPT` if an immutable
file changes. A raw-header discriminator was considered and withdrawn before
implementation: SQLite's [corruption guide §2.2](https://sqlite.org/howtocorrupt.html)
describes how an independent raw read/close can release all same-process POSIX
locks for that DB inode. A separate fresh-only marker was also considered, then
abandoned in favor of the smaller portable WAL-aware `mode=ro` transaction.
The [WAL format documentation §1.4](https://sqlite.org/walformat.html) states
that a last read-only client does not perform final cleanup; §2.3.1 specifies
the continuous shared DB lock. SQLite can update/create its transient reader
index/sidecar infrastructure, which is not schema migration or history repair.
The auditor read and rehashed final `Ledger.kt`
`cf04f5ea93a213c308562b3c0b5aca08cf86a7ef9021cbe62f50a310d8df365e`
and `KernelSchema2Test.kt`
`dab1f95d6d4505981402346b2b0a02d8154c286d91914d276621ef50763f5374`.
The constructor now opens a normal read-only connection and explicit transaction,
verifies version and required tables in the same live-WAL snapshot, then commits
before any writable setup. No raw DB descriptor, immutable URI or marker remains.
Fresh-file exclusive reservation and the held WAL-attached hardening connection
remain intact. The fixed3.54.1.0 JDBC pin and loaded-version test were separately
read and actually executed in the75-pass kernel batch.

All **eight** schema tests pass, including the four new controls: version1 visible
only in committed WAL despite main-file version2, actual JDBC read-only write
denial, cold legacy refusal allowing transient empty infrastructure, and16 bounded
barrier rounds of64KiB writes/PASSIVE checkpoints versus reopens with final JDBC
integrity/history checks. Raw DB byte comparisons occur only after **all** JDBC
connections close, with the read-only client closing last to preserve WAL; the
already-open old-owner scenario performs no raw reads. The durable-byte guarantee
is for preflight-refused versions/missing tables, not SHM/read marks or an
all-files-identical promise. An accepted schema2 file entering writable policy
validation may change checkpoint layout even if that later policy check rejects;
its logical history and obligations still cannot be discharged.
No design here fences an already-running old binary; separate new state never
reconciles or discharges old obligations. The separate upstream affected SQLite
dependency is recorded in IA-044.

The first current batch nevertheless failed **three** kernel methods: the old
`DROP fair_clients` reopen fixture still expected forbidden repair; a malformed
recipient vector omitting its origin expected routing-unavailable rather than
`plan_recipients_invalid`; and real transient-WAL disappearance failed a second
broker open before the overlapping-route assertions. The **15** passing routing
methods include independent credentials/policies/outboxes, whole-batch rollback
and child-JVM death, bounds, exact concurrent retry, permanent gate refusal and
18 private-corruption variants. The overlapping-route method's assertions did
not run during that first failure. The passing synthetic 100-patch stress report retains all
216 reviews and 668 required events, with observed global/per-worktree concurrency
4/2; its timing is not a production throughput or latency guarantee.

The independently reviewed four-file correction added required-table checks,
kept the final WAL-attached connection alive through permission hardening,
replaced forbidden repair expectations with repeated-refusal/unchanged-state/
zero-provider-call assertions, and required zero routing callbacks on malformed
recipient vectors. The corrected `Ledger.kt` hash was
`07f108bdd308a217a6050602b8b4feeb7d9cfd8e51524f053a65ec2f47fd36a7`.
The other **27** first-batch source references were unchanged. Because the root
had preserved original hashes but not these four original source files, their
exact first-run bytes were recovered by reversing the patch and independently
matching every originally witnessed hash. This is **hash-verified reverse-patch
reconstruction**, not a contemporaneous source archive. The before/after manifest
in `/tmp/ktc-kernel-first-run-reconstructed-before-20261104-m08s6baw/` has SHA-256
`f9996de8494c46b404502b72b5e254f727ff7feff14e0f3ced9119ccefe7cab1`.
No first log/XML/result was rewritten.

The second run actually passed all three previously failing methods and the new
**48 real concurrent reopens** across two producers, retaining exact status,
outbox and private modes without provider workers. Routing passed **16/16**,
parent **20/20** and lifecycle **4/4**, but integration **24/25** failed at
`Ledger.<init>:35` in
`clientFairnessPreventsOneAgentBacklogFromStarvingOthersInTheSameWorktree`.
That historical kernel result remains **69/70**, not retrospectively passing.
The subsequent portable correction actually passed **75/75** with the exact
reviewed source and loaded fixed dependency, including the earlier fairness
failure and48-reopen regression. The stress receipt retained216 reviews and668
required events, with observed provider concurrency4/global and2/worktree.
No-migration, whole-batch and independent-client obligations remain intact;
synthetic providers/fences, a bounded stress run and loaded version identifiers
do not establish real host/model composition, qualification or an SLA.

The new lifecycle hook has **four actual passing scheduler tests**: a held
preparation reaches its deadline before provider entry; preparation allocates then
throws; concurrent distinct attempts cannot revoke one another under repeated
cleanup; and cleanup failure retains an earlier raw complete result/finding while
broker validation adds `prepared_input_cleanup_failed`. A cleanup callback can
open an independent DB transaction, demonstrating that it runs outside the writer
lock. These are synthetic grants/providers, not real model/compiler authority.
The attempt key includes the full work key, durable attempt and service-instance
identity; coordinator and scheduler cleanup must be safely repeatable for that
exact effective attempt, without revoking a different one.

The new owner-only `review-controller` library has been independently source-read
and its **27 tests actually passed**. It accepts only live importer-created model
capabilities, exact namespace and same/direct-parent ancestry, the entire new
snapshot and its registered root/source authority. Every before/after compilation
and the complete owner catalog remain required; removed compilations and a changed
old rule context under the same compilation ID retain extra unsupported slots,
without stale old member/classpath inputs. An empty model receives an explicit
nonempty unsupported administrative slot, not vacuous success. Provider-supported
rules or user narrowing cannot shrink obligations.

Policy/execution callbacks bind the exact request object, entire client and input/
configuration/toolchain identity, allowing only sanctioned activation/profile
additions. Source readiness is checked around callbacks and every valid registry
resolve; wrong request tuples do not invoke callbacks. Registry references are
immutable and bounded but **in-memory**, not deserializable durable authority.
All required recipients currently must be the submitting client. Friend,
auxiliary/plugin and unknown/path-bearing compiler arguments remain unsupported.

The four new controller integration scenarios exercise actual kernel workers,
private source/classpath archives, full/incremental source vectors, independently
bound two-client delivery and a deliberately nonauthoritative vector probe. Exact
reason assertions prevent an unexpected provider assertion failure from appearing
as expected unqualified output. Their synthetic provider always returns
implemented/tested/qualified **false**, no certificate; actual Gradle/compiler
execution, live-current fencing and model-host inclusion are not established.
Reopen retains live capabilities in one JVM, not durable capability restoration.
IA-039 remains a concrete blocker to direct production compiler composition.

The architecture provider requires an exact owner-policy capability and verified
model, keeps unknown graph gaps, enforces selected producer/refinement closure,
and uses bounded iterative SCC handling. Only declared dependency-edge/cycle
algorithms are currently implemented. Exported ABI, resolved declaration ownership
and complete symbol-dependency graphs explicitly abstain. Anchors are labeled
build-configuration file starts, not fabricated dependency source ranges. Every
result remains partial/unqualified; no real Gradle or rule qualification follows
from the source or synthetic accepted-ingress tests. Transitive traversal operates
on owner-mapped architectural nodes, not necessarily a continuous actual-module
path when multiple modules map to one node; that abstraction must remain explicit.
Graph algorithms check cancellation during traversal/SCC/witness work, bound
edges to 50,000 and cumulative transitive witness origins to 200,000, and reject
per-rule output beyond 1,024 findings/8 MiB rather than certifying truncated
results. All **17 graph tests** passed after the IA-037 corrections. Bounds now
precede structural copying, graph/witness JSON, SCC label joins and finding
allocation; escape-safe UTF-16 upper bounds, a 512 KiB witness/diagnosis budget and
per-finding reserves preserve rejection without a partial-success prefix. These
are actual algorithm tests on synthetic model/policy inputs, not real architecture
rule qualification or whole-project performance proof.

### Windows private-state candidate, still unqualified

The reviewed MCP helper uses strict UTF-8, pinned no-delete-sharing handles,
bounded handle-based owner/DACL reads and repeated reparse/hardlink/final-path
checks. The parent deny mask includes data, extended-attribute and ordinary
attribute writes. Existing-root ACL mutation follows acceptance of its original
pinned chain; empty-directory detection now stops after the first entry instead
of materializing an unbounded name list. The encoded helper remains below the
30,000-character limit. The 17 compiled Linux MCP tests and source review do not
execute any of the eleven authored native controls. **Native Windows executions:
zero; production `private_state_platform_unqualified` guard retained.** No Windows
ACL/durability or process-supervision qualification follows from these passes.

## Release conclusion

No implementation/release certification is made by this in-progress document.
Missing provider/target/host integration, catalog breadth, adversarial conformance
and precision/latency evidence must not be silently replaced with a smaller claim.

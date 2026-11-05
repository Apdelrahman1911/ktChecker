# Preview implementation evidence

This ledger separates **authored CI**, observed tool availability, infrastructure
unit tests, executed provider adapters, real target compile/link/runtime work,
field qualification, and authenticated host-model observation. A workflow file,
catalog mapping, mocked diagnostic, or successful prerequisite probe does not
establish the next category. Release gates in [validation-ci.md](validation-ci.md)
and [PLAN.md](PLAN.md) remain unchanged.

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

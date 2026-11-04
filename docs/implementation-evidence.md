# Preview implementation evidence

This ledger separates **authored CI**, observed tool availability, infrastructure
unit tests, executed provider adapters, real target compile/link/runtime work,
field qualification, and authenticated host-model observation. A workflow file,
catalog mapping, mocked diagnostic, or successful prerequisite probe does not
establish the next category. Release gates in [validation-ci.md](validation-ci.md)
and [PLAN.md](PLAN.md) remain unchanged.

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

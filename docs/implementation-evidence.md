# Preview implementation evidence

This ledger separates **authored CI**, observed tool availability, infrastructure
unit tests, executed provider adapters, real target compile/link/runtime work,
field qualification, and authenticated host-model observation. A workflow file,
catalog mapping, mocked diagnostic, or successful prerequisite probe does not
establish the next category. Release gates in [validation-ci.md](validation-ci.md)
and [PLAN.md](PLAN.md) remain unchanged.

## Authored execution lanes

| Definition | Deliberate scope | Evidence required before reporting execution |
| --- | --- | --- |
| `.github/workflows/implementation.yml` | JVM-21 engine/broker on hosted Linux, Windows and macOS; actual implementation tests, unsigned preview archives and installed native launcher `doctor` smoke from a spaces/Unicode path | Successful run/attempt, exact executed tasks, real JUnit case occurrences, matching source identity, launcher result and archive hashes |
| `.github/workflows/bridges-validation.yml` | Exact Node 24.20.0; genuine `npm ci`, pinned official MCP SDK and synthetic app-server tests, full/production registry audits, dry-run and actual unsigned npm packing on Linux/Windows/macOS | Actual command outcomes, Node case occurrences, lock identities, audit counts and safe tarball contents/hashes; no live model request or JVM integration is performed by this lane |
| `.github/workflows/platform-validation.yml`: desktop | KMP common/desktop actual-code boundary tests | Actual `desktopTest` execution and nonempty successful target JUnit reports |
| Same workflow: Android | Actual Android KMP compile/host-side JVM tests; clean/invalid `NewApi` controls; immutable-fixture Lint adapter integration | Concrete Android test task and reports, fresh Lint XML, named invalid-source diagnostic, actual provider envelope and matching artifacts |
| Same workflow: iOS | Both `iosArm64` and `iosSimulatorArm64` compilation and framework linking; actual simulator tests; missing/wrong-actual controls and immutable-fixture Native adapter integration | Explicit arm64 Darwin host, selected Xcode/SDK/runtime, executed compile/link/test tasks, framework binaries, simulator JUnit cases and actual provider envelopes |

These are preview lanes, triggered by relevant PR/main changes, a weekly platform
schedule, and manual dispatch. Their authored state is **not a hosted run**. No
run URL, job ID, or uploaded artifact ID is recorded here yet. Hosted Apple and
Windows execution must be recorded from genuine results, not inferred from Linux
tests or runner labels. Android host tests are **not device/emulator execution**.

The project fixture baseline is **KGP 2.4.21 / Gradle 9.6.1 / AGP 9.3.1 / JDK 25**,
with JVM bytecode 17, compileSdk 37, Build Tools 36.0.0 and minSdk 24. The checker's
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

## Observed lightweight checks — 2026-11-04 UTC

The following were executed locally in the Linux working tree, without acquiring
the global Gradle/compiler build slot:

| Actual command/probe | Observed result | What it does not prove |
| --- | --- | --- |
| `python3 -m py_compile scripts/ci/*.py` | Passed | Execution of Gradle, any provider or platform code |
| `python3 -m unittest discover -s scripts/ci -p 'test_*.py' -q` | **63 infrastructure tests passed** in 1.526 seconds; includes eight bridge and 19 dependency-report tests | Real Native/Lint adapter execution; synthetic POM/JARs and mocked provider envelopes are adversarial harness tests only |
| actionlint 1.7.12, `-shellcheck= -pyflakes=`, against all three authored workflows | YAML/workflow static validation passed; reused existing binary with matching recorded SHA-256 `c872d6db8c6bf83a8eaa704fc93999f027d55dffbc63b8a6abdccb47df5f4cd4` | GitHub scheduling, runner availability, action execution, shell/Python linting, or build success |
| `python3 scripts/check_whitespace.py` | Passed | Compilation or execution of any implementation |
| `record_environment.py --require-java-major 21` | Linux x86_64, Python 3.12.3, actual Ubuntu OpenJDK **21.0.12** (`21.0.12+8-1-24.04-Ubuntu`); selected wrapper files pinned | Project JVM-25 availability/execution, or executed wrapper version; recorder never downloads/runs Gradle |
| Actual installed-package probe | API-37 platform and Build Tools 36.0.0 files present | Android compilation, Lint execution or Android runtime |
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
These are **not 63 qualified rules**, a natural precision corpus, or platform tests.

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

The implementation now exists and has executed local tests. The following latest
original JUnit XML was independently read from the coordinated lead's Linux
builds. These are **246 case occurrences across separate in-progress builds**,
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
| Semantic facts | 14; 0/0/0 | 08:52:25.544–08:52:25.734 | Compiler-checked DTO/coordinate/summary tests; actual fact-plugin fixture run still pending |
| CLI | 20; 0/0/0 | 08:51:47.711–08:51:51.303 | Actual composition/registration controls; installed distribution also built and `doctor` starts |
| Independent audit | 35; 0/0/0 | 08:43:36.083–08:43:38.092 | Actual implementation/persistence with explicitly synthetic providers/host receipts |

The actual compiler corpus summary at
`providers/compiler-fixtures/runs/2026-11-04T08-35-03.142916645Z-b73907cd/summary.json`
records **80 actual executions in 80 distinct output directories**, 42 original
fixture IDs, 39 implemented/tested IDs, **14 failed case assessments** and
**zero qualified IDs**. The aggregate corpus task failed; passing subsets do not
hide failed controls or establish all 443 mappings. Its preserved historical
source evidence is not proof that subsequently changed sources were executed.

The delivery agent reports the latest genuine Linux Node tests as **15 MCP and
26 reference-host cases**, with no failures/skips. They use the pinned official
SDK and a synthetic app-server; neither these npm tests nor a local account probe
observe an authenticated model. The lead separately observed a live two-phase
host exercise with a synthetic kernel. Actual compiler-backed host execution is
still pending at this entry; it must be recorded separately, not called an
authentication failure merely because a login method reports `requiresAuth=false`.

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
tool/artifact bytes. The driver, not a mocked envelope, must actually verify its
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
the unuploaded `raw/` folder. Bridge artifacts retain sanitized JSON only.
Unsigned preview distributions are separate artifacts, retained only after the
actual job succeeds. Bridge packs exclude tests/evidence/private configuration
and remain private, unsigned and unpublished. The reference-host local MCP
`file:../mcp` link is still a non-self-contained packaging gap; SDK audits do not
resolve owner licensing or Windows private-state qualification. Hosted jobs use
ephemeral runners, read-only repository
permissions and checkout without persisted credentials; they do not expose
secrets or privileged self-hosted resources to fork PR code.

## Outstanding evidence and unchanged release gates

- Record final-source hosted implementation suites and installed launchers with
  their run/attempt, source identity and artifact hashes; the local observations
  above do not replace an actual hosted run.
- Execute the pinned JDK-25 Desktop/Android fixture and both real Apple targets,
  link outputs and simulator cases; execute provider integration controls and
  repair any genuine DSL/task/diagnostic failures without weakening controls.
- Execute the compiled semantic fixture driver on real compiler/plugin bytes,
  then add its Native Apple controls using only its sanitized `summary.json`;
  JVM DTO/summary tests cannot establish Native semantic facts or link/runtime.
- Execute the authored cross-platform bridge install/test/audit/pack lane; retain
  genuine unsigned tarballs and resolve its local-link/privacy/license gaps.
- Resolve the owner distribution license and actual per-dependency NOTICE/shaded
  inventories. A successful dependency inventory is not a legal release gate.
- Establish exact M0 distributions, provider/libraries/classpath identities and
  required D8/R8, Compose and device/emulator boundaries before widening scope.
- Complete each required per-rule/context qualification, calibrated natural
  precision/recall corpus, field validation and release distribution/recovery
  gates. A maintained provider registry or one fixture never qualifies all IDs.
- Observe a real authenticated Codex/model-host request through the bridge. CI
  does not perform this; the local host probe is not an authenticated observation.
- Do not claim review of every intermediate patch from a final-commit CI run.

Future entries must retain the actual run/report/artifact identifiers, exact
source-byte digest, toolchain versions, test-case counts and failures/skips,
provider status/gaps, and separately named compile/link/runtime contexts. Update
these rows from observed outputs, never from the mere existence of tasks or files.

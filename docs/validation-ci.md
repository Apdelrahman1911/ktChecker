# Validation, compatibility, CI, and benchmarks

Implementation additions: [required executable concurrent histories](implementation-contract.md#required-executable-histories).

**Status: normative release gates; implementation is an unqualified preview.
Research retrieved on 2026-11-04 UTC.**

This document defines acceptance, not evidence that it has passed. Actual
implementation, test and hosted-run results are recorded separately in the
[implementation evidence ledger](implementation-evidence.md) and
[independent implementation audit](implementation-audit.md). The planning
validator checks documents and metadata; it does not execute the checker.
The numerical gates below remain **PROPOSED acceptance thresholds**, not measured
field precision/recall or performance promises. Read this with [the product contract](PLAN.md),
[architecture](architecture.md), [protocol](protocol.md), and
[independent audit](review-audit.md).

## 1. Evidence states and release accounting

Record evidence per `ruleId × applicability context × provider/toolchain profile`,
not once for a rule family. A context is a predeclared semantic partition: target
and source-set/compilation, relevant compiler/library behavior and flags, and
declared policy. It is not an arbitrary cross product of every dependency patch.
Version families can share qualification only after a reviewer records why their
contracts are equivalent and compatibility/conformance tests cover the boundary.

| State | Required evidence | Accounting rule |
|---|---|---|
| Planned | Predicate, exclusions, exact provider ID, proposed tests and evidence route | A catalog entry alone establishes only this stage |
| Implemented | Identified ktChecker revision with adapter/rule and coverage plumbing | Report per rule/context; a provider mapping alone is insufficient |
| Tested | Retained executed integration reports for the declared contexts | Require current source, runtime and applicability bindings |
| Validated: conditional certification | Reviewed proof/contract plus implementation conformance and adversarial evidence | No preview result automatically grants this stage |
| Validated: field evidence | Frozen detector/configuration passing an independent natural-project evaluation | No fixture count substitutes for an independently labeled corpus |

The registry's `validated` status must carry its route, context, limits, reviewers,
run IDs, artifact hashes and raw counts. Conditional certification is not a field
precision result. Source citations, upstream tests, catalog entries, mock MCP
responses and a green metadata validator cannot advance runtime qualification.

At M0, lock the launch manifest to exact atomic IDs, provider aliases, intended
default/advisory/opt-in modes, supported target/depth contexts and required domain
capabilities. Keep the existing planning baseline separate from this qualified
manifest. Every PR compares the locked manifest with actual implemented, tested,
qualified and enabled coverage. Count distinct capabilities after deduplication;
an upstream provider title does not count as all its rules.

The first release must retain meaningful eligible defaults across Kotlin/compiler
correctness, KMP/source-set compatibility, coroutine/concurrency contracts,
null/error/resource correctness, applicable security, evidence-backed performance,
build/dependency correctness, testing and Android/iOS/Desktop platform behavior.
Applicable resolved library packs and explicitly selected project policy/style
also need their promised integrations; architecture preferences do not become
universal defaults. M0 defines the concrete required capabilities within each
domain and target, using the inventories in [the registry guide](rules/README.md).

Insufficient evidence blocks promotion for that context. Required capabilities
cannot be satisfied by disabled, untested or experimental candidates. A missing
gate requires remediation, a reviewed replacement with equivalent coverage, a
visible owner-approved scope change, or delaying the broad release. Internal
previews may expose gaps; they must not claim the first-release contract. Neither
removing IDs from the denominator nor setting all defaults to opt-in solves a
release blocker.

## 2. A local test contract for every rule

Every included atomic rule, including a wrapped compiler/Detekt/Lint/library ID,
needs a ktChecker-owned executable contract. Freeze the provider's exported
descriptor inventory per version; every discovered ID has an explicit mapping or
gap disposition. Newly discovered IDs cannot inherit enablement from a wildcard.
Unknown compiler errors retain raw task evidence and incomplete normalization;
they are never converted into a clean verdict.

| Required test dimension | Assertions and meaningful examples |
|---|---|
| Positive diagnosis | Minimal and realistic violations; exact canonical ID, classification, target, primary/related range and reconstructable evidence |
| Valid negatives and alternatives | Closest legitimate lookalikes, delegated ownership, aliases/wrappers, supported lifecycle/dispatcher choices, native UI without Compose, explicitly permitted architecture |
| Boundaries | Nested scopes, overloads/type aliases/generics, nullable types, visibility, inline/const, syntax recovery, partial edit, generated code and unresolved symbols |
| Applicability | Resolved symbols/artifacts/options, missing dependency, misleading import/alias, inactive variant, unsupported compiler/library, mixed toolkits |
| KMP compilation contexts | commonMain/commonTest; actual intermediate source sets; Android main/host/device tests; iosMain/iosTest and simulator/device compilations; Desktop/JVM; expect/actual, friend modules and custom hierarchies |
| Version contracts | Oldest/latest selected supported tuple, API transition boundaries, compiler flags/plugins, legacy and current Android-KMP DSL, incompatible combinations producing explicit unavailable coverage |
| Configuration and suppression | Canonical/upstream aliases; file/symbol/range scopes; baseline versus new debt; expiry/reason; changed configuration creates a new identity and cannot imply resolution |
| Wrapper behavior | Per-ID provider enable/disable/config, arguments and classpath, source mapping, severity/evidence enrichment, failure propagation, dedup and conflicting diagnostics |
| Incremental regression | Trigger → finding → relevant fix → verified absence; rename/delete/revert; add a new overload/negative lookup; changed graph/options/artifact; full-versus-incremental findings and coverage |
| Recommendations | Advice valid for the resolved API and target; safe preconditions and alternatives; compile/test a proposed repair where that claim is made; withhold a fix requiring unknown intent |

Cases must test the behavioral claim, not reproduce the implementation's predicate.
An independent reviewer adds counterexamples before approval. Upstream fixtures can
seed these cases, but cannot replace local adapter, policy, suppression, evidence
and delivery tests. A single provider smoke test is not per-rule validation.
When a dimension is inapplicable, record a reason rather than inventing a passing
case. Platform/version tests must execute the relevant adapter; mocked type facts
cannot establish Kotlin/Native or Android tooling support.

For compiler/proof rules, compare the diagnosis with the exact supported compiler
or independently checked contract oracle. For probable behavioral rules, verify
the relevant consequence with an independent code review, execution or semantic
oracle; passing compilation alone does not demonstrate the bug. Runtime-only
phenomena such as jank, race timing or battery cost require appropriate execution
evidence, not static certainty.

## 3. Proposed qualification gates

All routes require the local test contract, independent review, supported-context
evidence and conservative unknown-state handling. Deterministic execution of an
AST matcher is not a proof of its correctness.

| Route | PROPOSED promotion gate | Permitted claim |
|---|---|---|
| Exact compiler or closed-world obligation | Explicit implication from fully resolved facts to the precise diagnosis; exact compiler/API/flags and all ownership/alias/dispatch assumptions; independent proof review; oracle conformance; adversarial valid alternatives; unsupported or missing facts cause abstention | Conditionally certified within the stated contract; field precision/recall remain unestablished unless separately measured |
| Explicit project policy | Project-selected policy and configuration; independently reviewed faithful enforcement; valid permitted alternatives and scope/suppression tests; report as policy | Policy violation under that declared configuration, without claiming a universal Kotlin defect |
| Behavioral heuristic default | Frozen natural-project evaluation meeting the per-rule/context precision and recall gates below; independent review and adversarial missed-opportunity checks | Field-validated for the evaluated population and exact configuration |
| Experimental/advisory heuristic | Local integration tests and explicit uncertain/intent-dependent labeling; owner-selected exposure; no default actionable or blocking promotion without the preceding gate | Tested advisory with its evidence limits |

**Conditional certification has an evidence floor, not a natural-alert quota.**
Review the rule against at least 10 real held-out projects from at least 5
organizations in the applicable target family, including a preselected sample of
at least 200 independently reviewed legitimate applicable opportunities per
semantic context. Exercise at least 20 independently designed positive
counterfactuals/mutations on realistic project code, alongside every required
adversarial boundary. These are PROPOSED minimum sampling checks, not substitutes
for the reviewed implication. A reviewer may require more cases for a wider
contract. A rare compiler error need not naturally occur 200 times, but a general
resource-leak warning cannot enter this route merely by being deterministic.
M0 must pilot and calibrate these floors for rare, narrowly enumerated contracts
before locking them. Do not manufacture legitimate opportunities from copied or
synthetic examples to meet a real-project quota; record an unmet floor and the
reviewed certification decision explicitly. A smaller sample alone is not a proof.

Keep natural, synthetic and mutation counts separate. With no natural alerts,
natural precision is undefined. Zero false alarms among valid opportunities
establishes only an observed false-alarm count for that sample; it does not
establish 100% precision, field recall or validity at other prevalence.

**Natural-project gates for actionable heuristics (PROPOSED):**

- At least 200 adjudicated sampling units per rule and material semantic context,
  across at least 10 held-out projects and 5 organizations; observed precision
  ≥98% and one-sided 95% Wilson lower bound ≥95%, subject to the independence
  restrictions in the next section.
- At least 50 independently verified, eligible positive opportunities for recall
  across at least 5 projects; observed recall ≥80% and one-sided 95% lower bound
  ≥65%. This is a modest floor, not a claim to find every bug.
- For high-consequence security heuristics presented as actionable defaults:
  at least 500 natural sampling units across at least 20 projects and 10
  organizations; observed precision ≥99% and lower bound ≥98%; at least 100
  verified positive opportunities, recall ≥90% and lower bound ≥80%, plus
  independently designed bypass/false-negative cases.
- Any unadjudicated promotion sample blocks promotion until resolved. Report
  unresolved counts and verified-only/worst-case metrics together; count unresolved
  alerts as non-confirmations for worst-case precision without using that reporting
  convention to bypass adjudication.

Rare heuristics without sufficient natural evidence remain visible as experimental
or owner-selected advisory gaps. They cannot be quietly enabled or counted toward
required default coverage. Multiple-testing and population limitations must be
reported; individual 95% intervals do not establish joint 95% confidence for
hundreds of rules. The evaluator predeclares any multiplicity adjustment or
domain-level simultaneous analysis before opening the held-out results.

## 4. Independent real-project evaluation

The evaluation lead is independent of the rule author. A second domain expert
reviews labels; a third expert adjudicates disagreements. Compensation or review
instructions must not reward agreeing with the checker. Project maintainers'
acceptance of a suggested change is supporting evidence, not the ground-truth
definition of a defect.

**PROPOSED initial curation floor:** 30 development projects and 30 held-out
projects, from at least 20 organizations overall. Include at least 10 applicable
held-out projects for each of Android, iOS/KMP and Desktop/JVM, with overlap recorded,
and both Compose and native-UI/non-Compose code. Include library-heavy and
library-light projects, custom source-set graphs, generated APIs, legacy/current
supported plugins, small and large modules, and legitimate architecture alternatives.
These diversity minima do not automatically satisfy every rule's sample gate;
additional projects are required when a rule is rare or observations are clustered.

1. Obtain a license/permission and fixed revision for every real repository. Record
   language/library/toolchain profiles, provenance, exclusions and build readiness.
   Public availability alone is not permission to redistribute source fixtures.
   Keep private repositories, labels, code and transcripts out of public artifacts.
2. Split by organization and code lineage before tuning: forks, template clones,
   shared internal libraries and adjacent history cannot leak into both development
   and held-out cohorts. Freeze the held-out project revisions and manifests.
3. Before running the candidate, independently review randomly selected eligible
   regions and specific semantic opportunities for each rule. Find all applicable
   positive opportunities within those bounded reviewed regions; record verified
   valid alternatives as negatives. This supplies recall ground truth independently
   of which findings the checker emits.
4. Separately sample naturally occurring checker alerts using a predeclared random
   or stratified design. Retain selection probabilities and apply justified weights
   for population estimates. Seeded examples and mutations are a separate dataset.
5. Blind two labelers to detector confidence, promotion target, author and proposed
   fix until they independently judge the precise claim from project code, resolved
   context and authoritative contract. For opportunity review, hide checker output
   entirely. Use labels true violation/actionable, valid alternative/false alarm,
   out of declared scope, and unresolved with reasons.
6. Compute agreement before adjudication. Report Cohen's kappa for two complete
   raters (or an appropriate multi-rater statistic), bootstrap confidence intervals,
   raw/positive/negative agreement, label prevalence and disagreement counts.
   **PROPOSED rubric gate:** kappa ≥0.80 and lower 95% bound ≥0.65 for each domain's
   ≥200 double-reviewed labels. Rare-label prevalence can depress kappa; report it
   and improve or expand the sample rather than concealing it behind raw agreement.
7. Adjudicate with a third domain expert and a written rationale. Preserve both
   original judgments. Unresolvable context stays unresolved and prevents heuristic
   promotion; it is neither silently discarded nor called a true positive.
8. Freeze the candidate before evaluation, including provider version, predicate,
   message, evidence, fixes, suppression policy and defaults. Fixes informed by
   held-out failures require a fresh independently held-out confirmation cohort.
   Archive the failed evaluation as well as the replacement.

Deduplication is not independence. Define a sampling unit as one actionable root
cause/incident with an exact snapshot and context. Repeated commits, duplicate
provider reports, copied patterns and related incidents in one project remain
clustered. Repository/organization identifiers and lineage must survive anonymization.
The 10-project/5-organization minima in section 3 ensure diversity, not statistical
independence.

Ordinary Wilson inference is permissible only under an explicitly justified
binomial sampling model. Where clustering remains, publish Wilson as **nominal**
and use a predeclared cluster/hierarchical bootstrap or other reviewed analysis
for the inferential lower bound. **PROPOSED cluster-analysis floor:** at least 30
independent project or organization clusters, using the highest level at which
dependence persists, with leave-one-cluster-out sensitivity. The cluster-aware
lower bound must meet the same 95%/98% precision and 65%/80% recall thresholds.
Too few clusters or an unstable interval is insufficient evidence, even with
thousands of raw alerts. Do not label an ordinary Wilson interval cluster-corrected.

## 5. Metrics and denominators

For each frozen rule/context, define the eligible opportunity before evaluation:
for example a resource acquisition with a known ownership contract, a suspend
call in a verified execution context, or a compilation diagnostic subject.
Lines of code are not automatically semantic opportunities.

| Metric | Definition and required reporting |
|---|---|
| Natural precision | TP/(TP+FP), where TP is a correctly located, applicable, actionable diagnosis and FP is a false claim/valid alternative; retain integer counts, unknown labels, natural sampling design, prevalence and confidence interval |
| Recall on reviewed opportunities | TP/(TP+FN) within independently labeled eligible positive opportunities; retain reviewed-region scope and TP/FN counts; never infer whole-project recall from positive fixtures or alerts alone |
| Opportunity prevalence | Verified positive opportunities/(verified positive + verified valid opportunities) in the independently sampled regions; report corpus selection/weights and naturally occurring versus enriched samples |
| False-alarm rate | FP/(FP+TN) only where valid negative opportunities were independently reviewed; zero alarms among negatives does not establish precision |
| Coverage | Intended applicable rule/context opportunities, actually executed depth/targets, abstained/unavailable/disabled/suppressed/baselined scope; never replace required work by an empty enabled set |
| Advice quality | Independently correct and applicable recommendations/verified recommendations, withheld count, harmful/unsupported recommendations and severity/claim misclassification |
| User burden | Unique new false alarms per patch/project/1,000 reviewed opportunities, repeated unchanged alerts, duplicate-provider noise, summary tokens and required detail requests |

For an ordinary binomial sample, the one-sided 95% Wilson lower bound uses
`z = 1.6448536269514722`, `p = k/n`:

`L = (p + z²/(2n) − z·sqrt(p(1−p)/n + z²/(4n²))) / (1 + z²/n)`.

For illustration, 196/200 gives precision 98% and L ≈95.62%; 200/200 gives
L ≈98.67%, not proof of perfect precision. A 99% point estimate at 495/500 has
L ≈97.96% and therefore misses the proposed stronger 98% lower-bound gate.
These calculations are examples, **not observed ktChecker results**.

An unresolved alert is excluded only from the explicitly named verified-only
precision calculation and remains visible; worst-case precision counts it as a
non-confirmation. A context with zero adjudicated alerts has undefined precision.
If reviewed positive opportunities exist and none is detected, recall is zero.
If there are no verified positive opportunities, recall is undefined.

FN accounting follows the locked intended rule/context scope. Misses caused by a
disabled/unqualified rule, provider failure, lost patch, unsupported required
target, stale cache or wrong applicability decision remain misses in end-to-end
recall for verified eligible opportunities. Retain cause codes for remediation;
they do not blame the corpus or remove misses from the denominator. A separately
reported detector-only result may isolate successful runs, but cannot replace
the end-to-end metric. Explicit user exclusions/suppressions are accounted as
policy dispositions, with intrinsic and policy-adjusted scope both disclosed.

Report per-rule/context intervals, macro and micro aggregates, undefined-cell
counts and the worst domains. Aggregates cannot promote a failing rule. Separate
conditional certification from natural precision, mutation sensitivity from
natural recall, compiler/task execution from normalized diagnostics, and tested
platform contexts from untested ones.

## 6. Deliberate toolchain and provider profiles

### Published constraints observed on 2026-11-04

The fetched [KMP compatibility table](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)
states that KMP/KGP 2.4.20–2.4.21 supports Gradle 7.6.3–9.7.0,
AGP 8.5.2–9.3.1 and Xcode 26.4. For KMP 2.4.0–2.4.10, the maxima are
Gradle 9.5.0 and AGP 9.1.0, also with Xcode 26.4.
[Kotlin releases](https://kotlinlang.org/docs/releases.html) lists stable 2.4.21,
released 2026-10-08.

[AGP 9.3 release notes](https://developer.android.com/build/releases/agp-9-3-0-release-notes)
require Gradle ≥9.5.0 and JDK ≥17, default Build Tools 36.0.0, and support API
level 37. [Gradle 9.6.1 compatibility](https://docs.gradle.org/9.6.1/userguide/compatibility.html)
permits JDK 25 (supported for running Gradle since 9.1.0).
The AGP minimum JDK is not proof that every bundled tool works there: the 9.3
notes record a JDK-17 Lint crash fixed in 9.3.2, beyond the fetched KMP table's
9.3.1 maximum. Use a verified JDK-25 candidate and retain this boundary risk.

The fetched latest Gradle page identifies 9.8.2, and the current AGP URL redirects
to [AGP 9.5.0](https://developer.android.com/build/releases/agp-9-5-0-release-notes).
Those are outside the latest published KMP-tested maxima. They belong in canary
compatibility probes, not silently in the release baseline. Documentation can lag
artifacts; a canary passing once does not automatically extend qualification.

### Proposed exact project tuples — all unvalidated

| Profile | Kotlin/KGP | Gradle | AGP / Android-KMP plugin | Proposed runtime | Apple toolchain | Purpose |
|---|---|---|---|---|---|---|
| Current project | 2.4.21 | 9.6.1 | 9.3.1 | JDK 25, exact vendor/patch/hash locked at M0 | Xcode 26.4.1 build 17E202; SDK/simulator 26.4 | Current supported-range Android+iOS+Desktop feasibility and release qualification |
| Compiler-adapter boundary | 2.4.10 | 9.5.0 | 9.1.0 | JDK 25, same locked distribution | Xcode 26.4.1 build 17E202 | Detekt 2.0.0-alpha.6 compiled-Kotlin boundary and older contract coverage |
| Latest-tool canary | Individually selected after intersecting published constraints | 9.8.2 probe | 9.5.0 probe | Explicit compatible runtime | Newer Xcode separately probed | Non-qualifying probes; invalid combinations expect unsupported/incomplete status |

The current tuple lies within the published KMP, AGP and Gradle ranges.
The secondary tuple also respects the KMP-2.4.10 maxima and AGP-9.1's documented
minimum Gradle 9.3.1. This is compatibility research, not executed certification.
KMP names Xcode 26.4; the proposed 26.4.1 patch is actually present on the fetched
macOS runner and still requires Native conformance tests. Do not interpret a
runner's default Xcode as the chosen version.

For Android fixtures, pin compileSdk 37 and Build Tools 36.0.0; include explicit
minSdk boundaries and API-35 emulator execution before widening runtime claims.
Resolve and record bundled D8/R8: the fetched
[Kotlin-classfile support table](https://developer.android.com/build/kotlin-support)
names required R8 9.1.29 for Kotlin 2.4. Verify the actual resolved tool rather than
assuming an AGP version name alone proves shrinker compatibility. NDK is required
only for relevant fixtures and is then separately pinned.

Use `com.android.kotlin.multiplatform.library` for the current KMP library
fixtures. The [official plugin setup](https://developer.android.com/kotlin/multiplatform/plugin)
lists AGP ≥8.10.0/KGP ≥2.0.0 and current Android main/host/device-test source sets.
Legacy `androidTarget` and older application/library integration are separate
boundary fixtures, not mixed into an unsupported current configuration.

[Compose compatibility](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html)
identifies Compose Multiplatform 1.12.2, its Android Jetpack Compose base 1.12.1,
and a Compose compiler plugin version matching KGP. Use 1.12.2/2.4.21 as a current
Compose candidate, plus equivalent non-Compose fixtures. Lock each library pack's
actual resolved version/API/target variants; do not create a Cartesian product of
incompatible library/Gradle/Kotlin releases. Declared dependency aliases are not
resolved-version evidence.

### Provider versions are a separate compatibility axis

- [Detekt's 2.0.0-alpha.6 table](https://detekt.dev/docs/introduction/compatibility/)
  lists compiled Kotlin 2.4.10, Gradle 9.6.1, AGP 9.3.1 and tested JDK 25.
  The page describes plugin build versions, not a compatible KMP project tuple.
  Its Gradle/AGP values exceed KMP-2.4.10's published maxima. A Detekt worker built
  with 2.4.10 does not establish semantic support for a 2.4.21 project.
- Google's fetched Lint metadata has latest stable `lint-checks:32.5.0` and preview
  32.6.0-alpha02. The official AGP-9.3.1 POM uses Lint model/typedef artifacts 32.3.1;
  a matching 32.3.1 provider is a conservative integration candidate. A standalone
  32.5.0 provider needs its own compiler/UAST/model compatibility spike. New IDs in
  the researched 32.5.0 inventory remain explicit gaps until individually qualified.
- Compiler/Analysis API, Detekt, Lint/UAST, KSP, library packs, Gradle exporter,
  Node/MCP bridge and JVM distribution each have their own version/hash manifest.
  Isolate incompatible providers as specified in [architecture](architecture.md);
  matching a stdlib dependency or emitted bytecode level does not prove runtime or
  compiler compatibility.

**Current-Kotlin release blocker:** demonstrate a functioning, correctly qualified
2.4.21 semantic/provider route. A successful older Detekt tuple or syntax-only
fallback cannot claim the broad current-Kotlin contract. M0 decides adapter
isolation, supported provider builds or reviewed alternatives, with coverage deltas
visible. No provider-version substitution automatically transfers evidence.

M0 retains exact wrapper distribution/checksum, artifact locks and verification
metadata, JDK vendor/patch, SDK/NDK/build-tool packages, Xcode build, simulator
runtime/device, OS image/architecture, compiler flags/plugins, library versions,
provider IDs, MCP profile and exporter/engine revisions. Qualification promotes
only concrete tuples; unsupported states and boundary fixtures are first-class.

## 7. Checker hosts and analyzed targets are different matrices

### Proposed checker-host matrix

| Host lane | Runner candidate observed in official docs | Required checker tests |
|---|---|---|
| Linux x64 | ubuntu-24.04; record image revision | JVM core/CLI, IPC/process groups, case-sensitive paths, watcher overflow, symlinks, crash recovery, offline caches, MCP host bridge |
| Windows x64 | windows-2025, native Windows execution | CLI/daemon/bridge, PowerShell/cmd argv, named pipes/ACLs, drive and UNC roots, long paths, locks/delete/retry, CRLF, UTF-8/Unicode/case behavior, watcher semantics |
| macOS arm64 | macos-26; fetched image 20261026.0455.2 on macOS 26.6.2 | JVM core/CLI, IPC/permissions, case and Unicode normalization, symlinks/process trees, bridge, Apple provider lifecycle |
| Additional distribution architectures | Linux arm64, Windows arm64 or macOS Intel only when explicitly selected | Real native install/launch/update/uninstall and matching process/ABI tests; no automatic support claim from a cross-built archive |

Windows tests run on Windows; WSL/Wine does not satisfy the lane. Use real files
with spaces, non-ASCII names, quotes/metacharacters, different case, NFC/NFD
collisions where representable, cross-drive paths and symlink/junction escapes.
Assert typed argv without shell reinterpretation. Test atomic rename, open-file
locks, duplicate/missing watcher events, delete/recreate and queue overflow.
Detect unsupported/colliding trees before materialization instead of merging them.
Server-runner success alone does not validate a promised Windows desktop installer;
add a supported client-OS install test before making that distribution claim.

### Proposed target-execution matrix

| Analyzed target | Required execution evidence | Principal host |
|---|---|---|
| Common/KMP | Actual metadata/semantic resolution; hierarchy and expect/actual/friend tests; same common code compiled against Android, iOS and Desktop dependencies | All checker hosts for available analysis; real target hosts for compilation |
| Android | Effective variants, manifest/resources/Lint, KMP main/host/device tests, Kotlin+Java interop, KSP/generated sources, debug and minified-release compilation, JVM tests and API-35 emulator smoke | Linux principal lane; Windows/macOS tooling/import smoke |
| Desktop/JVM | Plain Kotlin/JVM and Compose examples; JVM tests and Java interop; relevant Skia/UI runtime smoke; package/install on each promised OS using actual native packaging tools | Linux, Windows and macOS |
| iOS simulator | iosSimulatorArm64 compilation/link and tests; Swift/Objective-C interop/sample app execution on SDK/runtime 26.4 with selected Xcode; source-set/library applicability | macOS arm64 with real Xcode |
| iOS device artifact | iosArm64 framework/XCFramework compile/link and unsigned artifact checks; ABI/headers/export consistency | macOS with real Apple SDK/toolchain |
| Physical-device behavior | Only rules whose claim needs device-only capabilities; explicit device/version and execution evidence | Provisioned Mac/device lane, otherwise unavailable |

The fetched [Apple requirements](https://developer.apple.com/xcode/system-requirements)
put Xcode 26.4.1 on macOS Tahoe 26.2–26.x. The fetched
[macOS-26 arm64 image inventory](https://raw.githubusercontent.com/actions/runner-images/main/images/macos/macos-26-arm64-Readme.md)
contains Xcode 26.4.1 build 17E202 at `/Applications/Xcode_26.4.1.app`, alias
`/Applications/Xcode_26.4.app`, iOS 26.4 SDK and simulator runtime. It defaults to
Xcode 26.6. The lane explicitly selects and verifies 26.4.1, then records
`xcodebuild -version`, SDKs and available simulator runtime/device identifiers.
Image inventory is provisioning evidence, not evidence these tests ran.

If no Mac is available locally, common/syntax work may continue with incomplete
Apple coverage. Schedule the real Mac lane; never mark iOS tests successful on
Linux or substitute a mock. An unavailable or stale Mac lane blocks any release
claim requiring iOS support. Limited Mac time is managed by shared semantic
fixtures, changed-provider PR lanes and serialized full scheduled/release lanes,
not skipped obligations.

Unsigned CLI/Desktop archives, simulator builds and device-framework compilation
must work without signing secrets. Signing/notarization or physical-device
provisioning, if selected for distribution, is a separate protected release job.
No secrets enter fork PRs; unsigned CI success does not establish a signed package.
Library minimum OS support and the runtime versions actually executed are reported
separately. Do not infer all iOS/macOS/Windows minimum-version behavior from one
current simulator or server image.

## 8. Proposed implementation workflows

These are future workflow specifications, **not executable checker workflows
delivered here**. The current planning workflow must keep its narrower name and
claims. Job names below describe suites to implement; they do not assert Gradle
tasks or fixtures already exist.

| Workflow | Trigger and required suites | Evidence retained |
|---|---|---|
| Planning checks | PR/push: schema/registry guards, atomic-ID baseline diff, generated indexes, relative links and protocol-example consistency | Planning reports, explicitly separate from runtime evidence |
| Checker PR | PR and protected-branch push: JVM kernel/CLI tests on Linux/Windows/macOS; changed per-ID adapters plus regression suites; current-tuple importer/provider conformance; snapshot/cache/delivery/state-machine tests; relevant Android/Desktop/iOS target lane; unsigned package smoke; dependency/license diff | Exact commit, tuple/provider/ID inventory, test results, missing contexts, logs sanitized without source/secrets, unsigned artifact hashes |
| Scheduled full matrix | Nightly: all supported tuples and included rule contracts; development corpus, full-versus-incremental histories, cancellation/crash/queue stress, all target lanes and package installation. Weekly: current-source metadata/registry canaries, controlled benchmarks, long memory/fairness stress | Complete matrix with unavailable/failing cells, version/descriptor deltas, reproducible benchmark manifests and raw timings |
| Independent qualification | Frozen candidate on an evaluator-controlled held-out corpus; proof review and blind labels; independent adjudication and statistical reports | Frozen detector/configuration, licensed project revisions, per-rule/context counts/intervals/limits, review sign-off; private artifacts stay private |
| Release candidate | Exact immutable candidate: full supported matrix, required-rule/context qualification and breadth diff, actual host observation/final-barrier proof, controlled benchmark gates, install/upgrade/offline smoke, SBOM/notices/provenance | Release-blocking checklist linked to executed evidence, distributions and checksums; no skipped required target |
| Protected signing/publish | Only the approved release candidate after all required checks; platform signing/notarization if selected; verify final signed artifacts and publish the already-tested identity | Signed-package verification, attestations, final hashes and provenance; this is separate from unsigned build success |

All required matrix cells run to report their outcome; use matrix fail-fast false.
Path filters may avoid unrelated expensive lanes, but a provider/model/kernel,
protocol or toolchain change expands the affected matrix conservatively. A
docs-only change can run only planning checks without being described as runtime
validated. Changed launch contexts require target evidence at the same candidate
revision. Timeouts, unavailable capacity and flaky tests cannot be green skips.

Use workflow/ref/profile concurrency groups and explicit job timeouts. It is
reasonable to cancel superseded PR **CI runs**; this is not permission for the
checker to cancel accepted patch obligations. Serialize constrained Apple/heavy
jobs, preserve required checks for the selected commit, and do not let nightly
canaries starve release validation. A retry retains the original failure and
reason; repeated retries until one passes are not evidence of reliability.

### Workflow security, cache and dependency controls

- Pin every action/reusable workflow to an audited full commit SHA from its
  canonical repository, retaining a readable version comment. Adopt supported
  workflow dependency lockfiles to lock transitive actions as well. Pin wrapper
  checksums, JDK/runtime distributions and binary/tool/container digests; moving
  runner labels require recording the actual image and installed versions.
- Default `GITHUB_TOKEN` permissions to read-only/contents-read and grant writes,
  OIDC/attestation or publishing privileges only to the exact protected job that
  needs them. Disable unnecessary checkout credential persistence. Untrusted PR
  titles, paths, findings and metadata go through typed inputs/environment, not
  expression interpolation into shell code.
- Fork PRs receive no secrets or protected-write permissions. Do not run fork
  project code on a persistent trusted/self-hosted Mac. Use isolated hosted
  secret-free execution; otherwise the required lane awaits an explicitly trusted
  reviewed-commit run. Do not check out untrusted PR content in privileged
  `pull_request_target`/`workflow_run` jobs or trust their artifacts as release inputs.
- Review direct/transitive dependency additions, vulnerability advisories,
  provider maintenance, licenses and notices. Generate an SPDX or CycloneDX SBOM
  for the actual shipped broker, worker providers, JVM/runtime image, Node bridge
  and bundled tooling. A provider's upstream license does not settle redistribution
  obligations for its bundled compiler/IDE/native dependencies.
- Verify Gradle artifacts with independently reviewed checksums/signatures and
  locking. Newly generated verification metadata is a candidate for review, not
  automatic trust in whichever bytes were downloaded. Retain selected npm lockfiles
  and reproducible package-install policies; audit install scripts.
- Dependency caches key OS/architecture, exact runtime/compiler/provider/toolchain
  and lock/verification digests. Do not promote fork-populated caches/artifacts
  into protected release jobs. Keep source/finding/result caches scoped to their
  security domain and qualified immutable input identity.
- Exercise cold/dependency-resident/warm, cache-disabled and cache-enabled runs.
  Compare normalized findings, ranges, applicability, coverage, unknowns and
  provenance. Corrupt/evict cache blobs; mutate an artifact at the same path; reorder
  classpaths; change a negative lookup, suppression, plugin, schema or toolchain.
  A cache hit rate is a performance observation, not a correctness certificate.
- Apply release provenance/attestations to built artifacts and verify provenance
  when promoting them. Protect release branches/tags and signing credentials.
  Platform code signing, GitHub artifact provenance and a checksum are distinct
  controls; none by itself proves rule correctness.

### Kernel, scheduler and real delivery suites

Implement fault injection at durable admission, blob/ledger commit, job claim,
worker result fencing, outbox/inbox persistence, host insertion, insertion ACK,
model observation and final-gate release. Kill/restart broker/worker/bridge at
each boundary. Assert no acknowledged patch disappears, stale attempts cannot
publish authority, retries preserve immutable identity, and source writes by
invoked project tooling invalidate the run.

Replay duplicate/concurrent submissions, idempotency conflicts, malformed input,
external writers/ABA, symlink swaps, model drift, new overloads, generated-source
changes, omitted roots, disk pressure, output caps, OOM, task deadline/cancel races,
transport disconnect/reconnect, cursor expiry, overlapping worktrees and rapid
fix/revert patches. Unknown graph edges widen/recompute or remain incomplete.
An unchanged unrelated successful check cannot resolve an open finding.

Test each supported MCP profile and wrong-era shape, subprocess quoting, client
timeouts and foreground pending/terminal output. For every supported actual host,
demonstrate a completed patch and a review-triggered corrective patch, background
finding arrival during an active turn and a resumed turn, crash reconciliation,
duplicate/stale delivery and a blocked final action on incomplete coverage.

Retain private host evidence that the exact event revision/digest entered the
next **actual model request**, or a tested equivalent atomic inject-and-continue
operation. An insertion ACK, hook firing, UI banner or available resume API is
insufficient. Test insertion-without-model-invocation explicitly: it must not
pass the strict final barrier. Exercise the finite delivery frontier, new
required events/epoch changes invalidating it, and control ACK/observation traffic
that must not generate an endless new obligation loop. Same-UID hostile project
code must not forge receipts where adversarial enforcement is claimed; otherwise
report the trusted/cooperative boundary. See [the host demonstration contract](research/mcp-hosts.md#4-required-demonstration--release-blocking-not-performed).

## 9. Reproducible performance and resource benchmarks

**No benchmark has been run.** The 1–3-second useful warm-result objective is
unproven. Every patch still has coverage obligations, including expensive build,
dependency, Native and runtime changes. A small-patch objective must not be
advertised as the latency of cold setup, full completion or all patch classes.

### Projects, patches and machine profiles

Implement a repository-local benchmark harness with pinned fixture/project hashes,
reviewed patch sequences, a scripted trusted host adapter and machine-readable
results. Synthetic projects provide scale control; independently selected real
projects provide realistic graphs. Keep synthetic and real results distinct.

| Size profile (PROPOSED) | Project shape | Workloads |
|---|---|---|
| Small | 1–3 modules, 2,000–10,000 Kotlin LOC, 20–100 Kotlin files | Plain JVM, Android, small shared Android/iOS/Desktop; Compose and non-Compose |
| Medium | 10–30 modules, 50,000–150,000 Kotlin LOC, 500–1,500 files | Multiple source sets/variants, common+platform libraries, generated APIs and tests |
| Large | 50–150 modules, 300,000–1,000,000 Kotlin LOC, 3,000–10,000 files | High graph fanout, deep hierarchies, many dependencies and several target/test compilations |

Record measured counts rather than assuming every LOC band matches every module
band. Use at least five projects in each size profile for qualification benchmarks.
Run 1, 5 and 20 registered projects, with 1, 2, 5 and 10 actively edited projects;
include unrelated worktrees and incompatible provider profiles.

Patch sequences include clean and known-defect edits; 1–5-file private-body changes;
public signature/inline/const/type-alias and overload changes; common expect/actual
or hierarchy changes; rename/delete/revert; Java interop; resources/manifest;
test-only changes; compiler options/plugins; dependency/catalog/settings/buildSrc
or composite changes; generator/KSP/cinterop changes; and an edited high-fanout
symbol. Preserve intermediate defects that disappear after a rapid corrective
patch. Every accepted snapshot is compared with the clean full oracle, not only
the final tree. An unavailable required target is a correctness/performance
failure state, not an omitted sample.

Use declared 4-core/8-GiB and 8-core/16-GiB developer classes on available actual
Linux/Windows/macOS machines, recording CPU model, cores/limits, RAM, storage,
OS/kernel/image, power mode, runtime/JIT/GC flags, providers and toolchains.
The fetched hosted Mac has 3 M1 cores/7 GiB; it is an additional real profile,
not a fictional 4-core/8-GiB machine. Hosted-CI measurements are regression signals;
controlled developer hardware establishes the absolute claim.

### Cold and warm mean different things

| Profile | Required starting state |
|---|---|
| First install/provision | Fresh machine/VM image; distribution/runtime/project tooling acquisition timed separately; record any preinstalled SDK/Xcode |
| True analysis-dependency cold | No engine/bridge/worker processes, no project model/fact/result caches, isolated empty project/provider/Gradle dependency stores; acquire dependencies and import/initial analysis; provisioned SDK/toolchains disclosed |
| Engine cold, dependencies resident | Processes/JIT and engine caches cold; exact dependencies/toolchain already resident; project import still timed |
| Warm project, uncached patch | Broker/providers alive, initial full required baseline completed, resolved model/dependencies valid; new immutable patch with no result-cache entry |
| Exact reusable-input hit | Existing qualified certificates reused only after input-equivalence proof; count separately from uncached warm patches |
| Evicted/many-project warm | Service alive but model/provider evicted under limits; reload/queue latency included and profile named |

Do not call an engine restart with populated dependency/build caches true cold.
OS page caches, antivirus and network vary; disclose whether controlled rather
than claiming they were cleared. Use isolated task-owned stores and fresh process
instances; do not erase a developer's normal caches. Measure dependency acquisition
with a reproducible controlled mirror/network profile and report real-network
runs separately. Offline runs with resident dependencies must complete equivalent
coverage; missing prerequisites yield explicit unavailable status.

### Latency, correctness and delivered usefulness

Use a monotonic clock from the host's completed-patch handshake (`t0`) through
capture, durable admission, queue, provider execution and host delivery. Report
provider-only timings as components, never as the end-to-end total.
End-to-end durations use one observer clock domain. Keep process/remote-worker
durations with their clock-domain/boot identity; do not subtract unrelated machine
monotonic values or unsynchronized wall timestamps. Remote Mac work includes
transfer, queueing and return time observed by the client. Wall-clock UTC timestamps
support correlation, not an assumed exact cross-host latency calculation.

| Measurement | End point |
|---|---|
| T_ack | Durable acceptance of the immutable patch and its obligations; job ID acceptance is not analysis |
| T_useful | First fresh correct actionable finding attributable to this patch; for a verified clean patch, completed required patch-scope assessment |
| T_required | All mandatory rule/target/depth obligations for that patch successfully settled with exact coverage; retain time to failed/incomplete terminal outcomes separately |
| T_context | Actual model request containing that useful result, proven by the host; separately retain insertion time and observation time |
| T_barrier | Final fence validates current identity, all accepted patch obligations, policy and finite observed-event frontier before final-action release |
| Resources | Whole process-tree CPU seconds, peak/sustained RSS, heap/metaspace/native memory, thread/process/worker counts, disk/read/write/cache activity and summary/detail tokens |

An unrelated old warning, empty pending response or queue status is not a useful
finding. Clean patches cannot win by returning empty results while mandatory
checks remain pending. A known-defect patch with no correct finding fails
usefulness; record it as a miss/timeout instead of removing it from percentiles.
First useful partial findings retain explicit remaining coverage.

For active-host latency, measure at a supported next model-invocation safe point.
Busy/paused host trials retain both total t0-to-observation and wait/resume
components; do not subtract host waiting to claim universal 3-second delivery.
Report active and resumed profiles separately. No hidden reasoning or private
source transcript is needed as a public performance artifact.

**PROPOSED gates for the predeclared warm, 1–5-file implementation-patch profile,
with unchanged model/dependencies and the complete locked rule policy:**

- T_ack p95 ≤250 ms. T_useful p50 ≤1 s and p95 ≤3 s. T_context p95 ≤3 s at the
  tested active-host safe point; capture, queueing and delivery count.
- T_required p95 ≤10 s for small/medium and ≤30 s for large profiles. A clean
  patch whose T_useful equals T_required can miss the 3-second objective; report
  that miss instead of redefining useful as an unfinished empty verdict.
- Report cold, ABI/high-fanout/build/dependency/Native/runtime classes separately,
  including every goal miss and remaining obligation. They remain reviewed;
  these numbers do not promise 3-second full target execution.
- On the 4-core/8-GiB class, start with at most two compiler workers and one
  global heavy job, respecting a proposed total process-tree budget of
  min(3 GiB, 40% RAM) and at most two sustained CPU cores during interactive
  analysis. Measure all children; `-Xmx` alone is not a limit. If a required tool
  cannot execute within an owner-selected cap, expose backpressure/unavailable
  work rather than evading resource accounting.
- PROPOSED usual small-patch summary budget: ≤1,500 tokens, with ≤250 for an
  unchanged clean result. Retain severe findings, coverage gaps and lossless
  paginated detail access; a token cap cannot hide missing work.

Minimum qualification sampling is 200 uncached warm patch trials per size/profile
across at least five independent process runs, with randomized paired order and
project-stratified confidence intervals. Use at least 20 fresh startups for each
cold profile. Report p50/p95/p99, maximum, integer trial counts, failures, censored
timeouts and cache hit/miss proportions. Treat p99 as descriptive until at least
1,000 relevant trials exist. Keep all repetitions; no cherry-picked best run.
Small PR smoke runs are correctness/regression probes, not percentile certification.

Before each timing run, verify expected diagnoses and full/incremental equivalence
for that exact policy/context. Store raw event timestamps and environment manifests
so another developer can replay locally. A fast run with weaker defaults,
unavailable platforms or lost historical coverage fails the comparison.
Investigate a reproducible paired p95 regression >15% or >100 ms, whichever is
larger; a confirmed breach blocks performance qualification. No current baseline
exists against which to claim improvement.

### Background fairness, repeated use and energy

Benchmark a ten-minute sustainable stream and a ten-minute overload stream,
using equal-workload projects and separately realistic mixed-cost projects.
Include continuous foreground edits, slow semantic/native work, old patch
obligations, unavailable Mac capacity, cancellation, failures/retries, and one
project monopolizing the importer. Measure per-project queue delay, service share,
oldest obligation age, admitted/completed rates and backpressure.

Under a predeclared sustainable workload with bounded jobs and available tools,
PROPOSED fairness gates are Jain's service fairness ≥0.90 for equal-weight identical
workloads, no eligible slow lane starved, and p95 slow-job admission-to-start ≤30 s.
For m eligible identical-workload projects, use
`J = (Σ service_i)² / (m × Σ service_i²)`, with service measured in completed
equal-cost work or consumed CPU time under the predeclared workload.
An indivisible running job's bounded cost and reservations are recorded. Overload
must produce bounded admission/spooling and producer pause, preserving every
accepted patch; it does not have the sustainable-load latency SLA. Do not create
unbounded jobs/threads to make a throughput graph look fast.

Run at least 1,000 patches and 20-project churn with idle-session eviction/reload.
Report warm steady-state and post-eviction memory; require no unbounded worker,
thread or RSS growth on fixed-project replays, and investigate >10% RSS growth
after comparable warmed/idle states. Idle daemon CPU should approach zero
(PROPOSED average <1% of one core over
five minutes without admitted work); polling intervals are bounded and host waits
are outside model calls. Retain CPU seconds and tail latency while background work
is active to show whether foreground speed starves required completion.

Where available, measure energy with OS/hardware counters or a repeatable external
power meter, using fixed AC/battery and power-mode conditions and an idle baseline.
Report joules per patch/complete project separately from CPU-time proxies.
Unavailable energy instrumentation remains unmeasured; do not invent battery or
carbon savings. Resource and energy improvements cannot be claimed before this
harness is executed.

## 10. Release and evidence artifacts

Use separate versions for the CLI/service, application protocol/schema, providers,
rule packs and qualification manifests. SemVer the public CLI/protocol behavior;
release notes identify rule/default/applicability changes, provider/tuple changes,
coverage gaps, suppressions and baseline migration effects. A rule can retain its
stable ID while its semantic revision invalidates prior evidence/caches.
Pin defaults in a user's selected version; dependency updates cannot silently
activate new diagnoses. Updates are reviewed and requalified before promotion.

For each supported OS/architecture, test a fresh install, paths with spaces,
runtime availability/bundling, offline start, register/import/review, MCP bridge
startup, concurrent launcher/daemon detection, graceful shutdown, upgrade and
data-schema migration. Test failed/unsupported downgrade and uninstall without
deleting user projects. Validate actual native package launch and licenses/notices,
not only archive contents. The initial software license/redistribution decision
must be settled before distribution, rather than inferred from a public repository.

The release evidence manifest records exact commit and build provenance, compatible
tuples, exported/qualified provider IDs, locked launch capability coverage, all
test run IDs/outcomes, per-context routes/reviewers, corpus hashes/licensing,
TP/FP/FN/TN/unresolved counts, interval method/assumptions, host observation proofs,
benchmark samples/machines and package/SBOM hashes. Public summaries contain no
private code, credentials or raw host transcripts.

The release gate reads actual reports and independent sign-off, not merely
whether an evidence filename exists. Missing target execution, default-domain
coverage, qualification, current-Kotlin compatibility, every-patch accounting,
model-observed delivery or measured performance remains a blocker. Passing the
planning workflow establishes only that this proposed plan is internally consistent.

## 11. Fetched primary sources

All following URLs were actually fetched on **2026-11-04 UTC**. Current pages and
metadata are mutable and sometimes lag each other; source observations do not
substitute for executed compatibility tests. Preserve the exact bodies/redirects
in implementation research artifacts before selecting a release profile.

| Primary source | Observation used |
|---|---|
| [KMP compatibility](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html) | KGP 2.4.21 and 2.4.10 Gradle/AGP/Xcode bounds |
| [Kotlin release process/history](https://kotlinlang.org/docs/releases.html) | Stable 2.4.21, 2026-10-08 release |
| [AGP 9.3 notes](https://developer.android.com/build/releases/agp-9-3-0-release-notes) | Gradle 9.5 minimum, JDK 17 minimum, API 37, Build Tools 36.0.0, JDK-17 Lint regression |
| [AGP 9.1 notes](https://developer.android.com/build/releases/agp-9-1-0-release-notes) | Gradle 9.3.1 minimum for the boundary profile |
| [AGP 9.5 notes](https://developer.android.com/build/releases/agp-9-5-0-release-notes) | Current AGP landing-page redirect; outside fetched KMP range |
| [Gradle 9.6.1 compatibility](https://docs.gradle.org/9.6.1/userguide/compatibility.html) / [current compatibility](https://docs.gradle.org/current/userguide/compatibility.html) | JDK 25 runtime support; current page 9.8.2 differs from the chosen version |
| [Android Kotlin support](https://developer.android.com/build/kotlin-support) | Kotlin 2.4 classfile/R8 requirement; separate resolved-shrinker verification |
| [Android-KMP plugin setup](https://developer.android.com/kotlin/multiplatform/plugin) | Plugin prerequisites, DSL and main/host/device source-set boundaries |
| [Compose compatibility](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html) | 1.12.2, Jetpack base 1.12.1, matching compiler plugin and platform limits |
| [Detekt compatibility](https://detekt.dev/docs/introduction/compatibility/) | 2.0.0-alpha.6 build/compiler/tested-JDK facts, not target tuple certification |
| [Lint Maven metadata](https://dl.google.com/dl/android/maven2/com/android/tools/lint/lint-checks/maven-metadata.xml) / [AGP 9.3.1 POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.3.1/gradle-9.3.1.pom) / [Lint 32.3.1 POM](https://dl.google.com/dl/android/maven2/com/android/tools/lint/lint-checks/32.3.1/lint-checks-32.3.1.pom) / [Lint 32.5.0 POM](https://dl.google.com/dl/android/maven2/com/android/tools/lint/lint-checks/32.5.0/lint-checks-32.5.0.pom) | Latest stable versus preview, AGP-coupled artifacts and separately resolved provider dependencies |
| [Apple Xcode requirements](https://developer.apple.com/xcode/system-requirements) | Redirect from /support/xcode/; 26.4.1 host/SDK/simulator requirements |
| [GitHub hosted runners](https://docs.github.com/en/actions/reference/runners/github-hosted-runners) / [macOS-26 arm64 inventory](https://raw.githubusercontent.com/actions/runner-images/main/images/macos/macos-26-arm64-Readme.md) | Actual labels/architectures/resources; 20261026.0455.2, selected Xcode 26.4.1 and iOS 26.4 runtime |
| [GitHub secure-use reference](https://docs.github.com/en/actions/reference/security/secure-use) | Effective redirect from security-hardening guide; least privilege, untrusted PRs, full SHA pins and workflow dependency locks |
| [GitHub dependency review](https://docs.github.com/en/code-security/concepts/supply-chain-security/dependency-review) / [Gradle verification](https://docs.gradle.org/9.6.1/userguide/dependency_verification.html) | Dependency/vulnerability/license review and reviewed artifact trust |
| [GitHub artifact attestations](https://docs.github.com/en/actions/how-tos/secure-your-work/use-artifact-attestations/use-artifact-attestations) | Provenance controls distinct from code signing and semantic validation |

Critical fetched-body SHA-256 values (body hashes, **not tool distribution checksums**):

| Body | SHA-256 |
|---|---|
| KMP compatibility | `1c4708ec3c12739fe47a1e6dd482582419a97f36b312d5a6b2cae8afa05f8a84` |
| AGP 9.3 notes | `6e8fcbe0bdbcf473ee4df0a171c639e26ffdc5aa974361c09b115422b0badc6f` |
| Gradle 9.6.1 compatibility | `b7febf37933f221fcebac242668c2b7f2e0c5ba6681135f776c9ad06069f1728` |
| macOS-26 arm64 inventory | `a8a01d06211feee22893bcdf1ccd04d8ebaaf857861aeb770a0f85d838eba94e` |
| AGP 9.3.1 POM | `310b87ad50d932c7ce88a424e9828d460536a524a2f91c2b1656915183c426dd` |

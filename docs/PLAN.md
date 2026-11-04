# ktChecker: product contract and implementation plan

**Research baseline: 2026-11-04. Implementation underway; not a qualified release.**

The baseline below records the planning deliverable. The implementation request adds
the normative [implementation acceptance contract](implementation-contract.md).
Executable progress and test evidence must be reported separately from this plan;
catalog requirements are not evidence of implemented or qualified coverage.

This plan preserves three simultaneous launch requirements: **broad relevant rules,
low false positives, and fast useful feedback after every patch**. None can be
traded away silently to label a small ruleset an MVP. Milestones are implementation
stages of the same first release, not progressively redefined product scope.

## 1. Planning baseline and current implementation status

The original planning checkpoint delivered a researched design, atomic candidate rule inventories, explicit
applicability and exclusions, proposed validation gates, a release/CI plan, and
executable checks of the **planning artifacts**. The source repository was inspected
before modification: it was already public at
[Apdelrahman1911/ktChecker](https://github.com/Apdelrahman1911/ktChecker), with a single
README in commit `6bef07933a1113985b4a62d8f3e3da93e809df33`.

That checkpoint did **not** deliver an engine or executable validation. Implementation
has since produced a runnable **unqualified preview**: immutable capture and a
concurrent durable review kernel, installed JVM CLI, isolated provider adapters,
suppression controls, and a reference host. Actual compiler findings have entered
subsequent Codex model requests in the retained three-phase synthetic-project
[delivery experiment](../bridges/reference-host/evidence/cli-kernel-codex-public-fixture.json).
This does not qualify all hosts or rules; its final completion gate deliberately
denied success. Original full coverage event arrays were not retained in that
experiment; its evidence document records that reproducibility limit.

Use the [implementation usage guide](implementation-usage.md),
[independent implementation audit](implementation-audit.md), and
machine-readable coverage report for the exact tested source tuples and remaining
gaps. The Linux CI preview archives are not evidence of Windows/macOS or target
qualification. **A complete release, general incremental/full equivalence,
Android/iOS/Desktop target validation, per-rule real-world precision/recall, and
the warm 1–3 second useful-result objective remain unproven.** Planning records
still describe requirements: an initial `planned` catalog record is neither a
current test result nor a reason to reduce the agreed inventory.

Read the detailed specifications together:

| Document | Owns |
|---|---|
| [Architecture](architecture.md) | Engine, alternatives, snapshots, cache/impact algorithms, scheduling, security |
| [Implementation acceptance contract](implementation-contract.md) | Concurrent agents, suppression authority, scoped completion and executable acceptance histories |
| [Repository and distribution](repository.md) | Proposed module tree, ownership/dependency boundaries, local installation and multi-project state |
| [Protocol](protocol.md) | CLI/MCP examples, foreground/background sequences, delivery state machines |
| [Validation and CI](validation-ci.md) | Rule evidence gates, compatibility tuples, target CI, benchmarks |
| [Rule registry guide](rules/README.md) | Inventory fields, activation, deduplication, exclusions, launch-scope discipline |
| [Independent design audit](review-audit.md) | Correctness/false-positive challenges and remaining obligations |
| [Research](research/README.md) | Fetched primary documentation, observed versions, integration/license findings |

## 2. Precise product contract

### Users, installation, and trust

- A developer installs a versioned local distribution once and registers any number
  of projects. A per-user broker reuses workers subject to global memory/CPU limits;
  it does **not** share source, findings, credentials, or mutable project state
  across users. Different worktrees and rule/toolchain profiles remain isolated.
- Initial project targets are **Android, iOS, and Desktop/JVM**, including mixed
  shared/platform source sets and native-UI projects with no Compose dependency.
  JavaScript, Wasm, watchOS, tvOS, Linux Native, and Windows Native are not launch
  execution targets. Shared rules may incidentally be useful there, without claiming
  validated target support.
- Kotlin/JVM is the analysis engine and CLI implementation. A narrowly scoped
  TypeScript protocol/host bridge is recommended where the current MCP runtime or
  host SDK needs it; the durable review service is transport independent.
- Local deterministic analysis is the default. There is **no required LLM service**,
  source upload, telemetry upload, paid account, or network request per patch.
  Dependency acquisition is an explicit, bounded operation. A machine without
  cached required dependencies reports them unavailable, not passing.
- Executing a project build, plugin, generator, wrapper, or test executes project
  code. Consent and provenance checks are required; immutable copies and worker
  process isolation are **not security sandboxes**. Untrusted projects get a
  non-executing partial mode until a supported sandbox or trust decision exists.

### A patch is a review transaction

1. The external agent declares a **completed logical patch** after its edits have
   been saved; fixing an earlier finding produces another patch and another review.
   A patch can span several file-edit tool calls. A host with no reliable logical
   boundary can submit each completed edit batch instead, but cannot skip a batch.
2. The supported host adapter seals an immutable snapshot before authorizing another
   edit batch, submits it durably with an idempotency key, and inserts the immediate
   result/coverage state into agent context. A watcher is a useful audit signal, not
   a reliable replacement for this handshake.
3. ktChecker analyzes changed code **and affected code**, preserving obligations for
   slow/target-specific checks. The agent may work while background obligations run.
4. The host delivers background findings at the earliest supported safe point in an
   active or resumed turn. It also delivers failures, unavailable work and stale-result
   notices. Polling timers and job tracking live outside the model's reasoning loop.
   A durable inbox/history insertion is not enough: the strict delivery gate requires
   evidence that required events entered an actual subsequent model request (or a
   tested atomic insertion-and-continuation equivalent), not merely an available wake API.
5. Before the agent calls a task finished, a final barrier checks required work on
   the final snapshot **and the outstanding obligations of every accepted patch**,
   or returns explicit failure/incomplete status. No unavailable platform, abandoned
   job or undelivered result can produce an overall success. The gate uses a finite
   required-event frontier; receipt/control messages do not recursively require
   another agent turn. Any source, policy or required-coverage change invalidates it.

The checker cannot force an arbitrary standalone agent to call it. The guarantee is
for **tested host adapters or a client-controlled wrapper** that enforces these
boundaries. Instruction-only integrations are best effort and labeled as such.
The default audit policy seals and accounts for **every submitted patch**. Coalescing
is limited to duplicate requests, pre-completion watcher noise and computation with
proven equivalent inputs; each patch keeps its own coverage binding. A user-selected
latest-only mode must label skipped intermediate coverage and cannot claim this
strict contract; see [Architecture](architecture.md).

### Read-only reviewer; actionable output

ktChecker diagnoses and advises. It never applies a source-code fix, commits changes,
changes dependencies, rewrites suppressions, or invokes a suggested fix command on
the agent's behalf. Build outputs live in isolated execution areas; source-write
violations by invoked tooling are reported and invalidate the result.

Each actionable finding answers, without relying on implicit expert knowledge:

| Agent question | Required content |
|---|---|
| What is wrong? | One precise claim, stable rule ID, diagnostic class and certainty |
| Where? | Snapshot-bound path, source-set/target, symbol and primary/related ranges |
| Why does it matter? | Specific possible or proven consequence; no generic alarmism |
| What proves it? | Relevant code facts, resolution/data-flow/build trace or test outcome, authoritative rule basis |
| How can it be fixed? | Minimal recommendation with preconditions and valid alternatives, or an explicit reason no safe suggestion exists |
| How is the fix verified? | Exact re-review scope plus relevant test/build task and expected result; prerequisites and cost |

Findings distinguish **correctness**, **probable defect**, **declared policy**, and
**style preference**. Severity is independent from certainty: a severe risk can have
weak evidence and must not be presented as a proven defect. Certainty labels are
evidence classes, not invented calibrated percentages. Suggestions with missing
intent/type/ownership knowledge are withheld or conditional. Architecture preferences
are not correctness rules.

The first response groups new/reopened findings and material changes, with short
evidence, bounded excerpts, coverage gaps and pending work. Stable IDs and a detail
endpoint avoid repeatedly sending long unchanged findings. Pagination and truncation
are explicit, deterministic and lossless through follow-up retrieval; severe findings
and incomplete states cannot be silently hidden by a token cap.

### Completion is not the same as passing

An analysis can complete and find errors. A check can fail to execute rather than
diagnose bad code. Pending, failed, canceled, superseded, unavailable, inapplicable,
suppressed, disabled, incomplete and successfully executed are distinguishable.
The protocol defines exact enums and exit codes. Human summaries must preserve them.
An unrelated edit, suppression, cancellation or stale run cannot resolve an open
finding. Resolution needs fresh applicable evidence covering the finding's scope,
or a separately represented disappearance such as removal of the affected code.

## 3. Explicit, reversible assumptions

| Unknown | Planning assumption | Reversal/decision point |
|---|---|---|
| Developer hardware | Benchmark 4-core/8-GiB and 8-core/16-GiB classes, not one high-end workstation; conservative auto budget | User caps and measured resource profiles at M1 |
| Project size | Small, medium, large multi-module corpora; small patches normally 1–5 Kotlin files, not only single-line samples | Record real repo distributions at M0; retain large/many-project tests |
| Agent host | Host-neutral broker plus one fully tested reference wrapper; generic MCP alone is not delivery proof | Pick first native adapter after capability spikes at M0/M3 |
| Local Mac | Not assumed available; macOS/Xcode CI and Mac developers execute iOS target checks | No Mac => truthful iOS execution unavailable locally; optional trusted remote Mac later |
| UI toolkit | Compose and native UI are equally legitimate | Activate rules only for resolved toolkit artifacts/symbols/source sets |
| Architecture | No imposed Clean Architecture, MVVM, repositories/use-cases, or universal layering | User-declared constraints plus evidence; inferred conventions remain suggestions |
| Toolchains | Exact compatible tuples, not 'latest of everything' | Update manifest after compatibility CI; unknown versions are partial/unsupported |
| Connectivity | Warm frequent-use path can run offline | Explicit provisioning/import phase, pinned dependency graph, offline failure states |
| Project trust | Read-only syntax metadata is safe by default; Gradle execution needs trust | Trust decisions scoped to executable inputs and reviewed changes |

No owner answer is needed to complete this plan. Hardware, preferred host, UI mix and
Mac availability become **implementation configuration decisions**, not excuses to
weaken the launch contract. An unavailable required host or Apple validation lane is
a release blocker until there is actual evidence or an explicit owner-approved scope
change.

## 4. Architecture decision summary

**Recommend Kotlin/JVM for the broker/engine/CLI, version-isolated JVM/native-toolchain
providers, and a thin replaceable TypeScript MCP bridge for the current protocol.**

Why Kotlin/JVM: direct access to Kotlin compiler/Analysis API ecosystems, Detekt,
Lint/UAST and Gradle integration; a persistent process amortizes JVM/provider startup;
the product language matches extension authors' expertise. Costs: JVM memory,
compiler/API instability, GC/classloader leaks, incompatible provider versions and
platform-toolchain requirements. These are measured/isolated, not wished away.

Current fetched MCP documentation is not the older protocol remembered by many
examples: the 2026-07-28 revision and the Kotlin SDK's implemented protocol profiles
do not line up. [The protocol evidence](research/mcp-hosts.md) motivates the narrow
bridge rather than reimplementing a new protocol or prematurely promising all-JVM
support. The bridge owns transport, capability profiles and host lifecycle only;
it does not duplicate analysis or become the database authority. Its Node runtime
adds packaging, memory and supply-chain costs to measure. A verified all-JVM adapter
can replace it later without changing review semantics.

Alternatives are real but less suitable for the primary analysis core:

- **Rust/Go:** potentially leaner broker/launcher, but cannot replace Kotlin semantic
  infrastructure; JNI/RPC workers still dominate startup and correctness work. Revisit
  a small launcher only if measurements show the JVM broker is the bottleneck.
- **TypeScript/Python:** strong orchestration and host SDK support, weaker direct
  compiler integration. Good thin bridges/harnesses, not a second semantic engine.
- **Kotlin Multiplatform/native core:** unnecessary platform abstractions for a local
  developer tool whose key providers are JVM libraries; materially harder embedding
  of compiler/Lint/Gradle facilities. Do not confuse analyzing Native with running the
  checker as Native.
- **IDE plugin only:** excellent IDE project models but excludes headless agents and
  multiplies IDE/version integration. Optional frontend after CLI/service correctness.
- **Compiler plugin only:** compiler-accurate findings but too late/heavy for all patch
  feedback, constrained build/library policy visibility and invasive configuration.
- **GraalVM native image:** not a launch dependency. Reflection, provider loading and
  compiler compatibility require proof; try `jlink` runtime packaging first where
  compatible. Bundle managed runtimes or document explicit runtime prerequisites;
  'single binary' is not promised.

Complete algorithms are in [Architecture](architecture.md); the concrete proposed
module tree, dependency boundaries, local installation and per-user/multi-project
layout are in [Repository and distribution](repository.md). These are future
implementation modules, not empty scaffolding presented as completed software.

## 5. Rule coverage and activation contract

The full machine-readable inventories and generated indexes live under
[the registry guide](rules/README.md). They cover Kotlin/compiler checks, maintainability,
KMP/source-set relationships, coroutine/Flow/concurrency, null/error/resource handling,
security, evidence-backed performance, tests, build/dependencies, platform code and
the researched library ecosystems. Inventory records are atomic where provider IDs
exist; an unexpanded tool family is a **coverage gap**, not 'one rule covers everything'.

Key launch principles:

1. Evaluate and reuse maintained compiler diagnostics, Detekt, Lint and library packs
   before duplicating them. Upstream implementation/tests/licenses are recorded;
   ktChecker integration tests are still mandatory. Licenses, notice obligations,
   redistribution rights and provider vulnerability/maintenance status are release
   gates. Publishing this plan does not select a software license on the owner's behalf.
   [Konsist, ArchUnit and diKTat](research/policy-provider-alternatives.md) are also
   evaluated for declared policies; their source/bytecode limits and unexpanded
   mappings are explicit, not silently counted as integrated architecture coverage.
2. Discover resolved artifacts, variants, compiler flags, source-set hierarchy and
   symbols from the actual project. Imports, catalog aliases or build-script strings
   alone are hints, not evidence of a compatible runtime or API.
3. Join rule predicates against the exact target/library/compiler/provider support
   manifest. `commonMain` does not imply all platform APIs are legal. Unknown or
   unvalidated contexts abstain and expose missing coverage rather than guessing.
4. Run correct rules at the appropriate tier: cheap syntax first; semantic/impact
   workers next; build/native/test checks as required background obligations. Broad
   coverage means those applicable obligations actually complete, not that every
   expensive provider blocks every small patch.
5. Deduplicate by normalized defect/subject/evidence and explicit provider aliases;
   keep provenance and combine supporting evidence. Do not collapse two distinct
   defects just because they share a line, or inflate apparent precision by counting
   one bug once per provider. Suppression maps to the canonical rule and scope.
6. Style is opt-in or respects an explicit project formatter policy. Architecture
   checks consume declared allowed dependency edges, API exposure, platform ownership,
   cycles and project-specific contracts. Inferred patterns require confirmation;
   'must use MVVM' and universal repository/use-case requirements are excluded.
7. All non-excluded catalog entries are **planned launch scope** with their stated
   activation mode, not evidence that they exist. M0 reviews and locks the launch
   manifest. Corrections/removals require a versioned reason, coverage delta and
   replacement or explicit accepted gap; delay must not silently redefine broad v1.
8. Enabling a rule in any supported mode requires its integration/applicability tests.
   Default actionable heuristics additionally require the field-precision gates;
   proof-backed diagnostics have separately reviewed proof and adversarial gates.
   Insufficient evidence remains visible, with promotion blocked. Required domain
   coverage cannot be satisfied merely by listing disabled/experimental candidates.

Compiler/Gradle task success or failure remains a first-class observation even when a
new upstream diagnostic has no validated ktChecker mapping. Retain the raw artifact,
mark normalization/coverage incomplete where necessary, and never turn an unknown
error into a pass or fabricate a normalized rule finding.

## 6. Implementation workstreams and dependencies

The following is an actionable work breakdown. Workstream owners can be roles until
a team is assigned. Implementation tasks should update status and evidence atomically.

| Milestone | Primary owner and dependencies | Required output | Exit criteria |
|---|---|---|---|
| **M0 — Compatibility and feasibility** | Engine + provider + host leads; starts now | Exact project/provider tuples; stand-alone semantic/Native spikes; project importer; current/legacy MCP host probes; license audit; locked rule dispositions; benchmark hardware | Real minimal Android/Desktop/iOS projects analyzed through proposed paths; no unsupported semantic claim; one viable host injection path demonstrated; every catalog family has owner and disposition |
| **M1 — Review kernel** | Engine lead; M0 snapshot/project-model choices | Versioned API, immutable snapshot store, project/worktree identity, durable jobs/coverage/events/findings, local daemon/CLI, budgeted scheduling, trusted-execution boundary | Fault-injection and snapshot mutation tests pass; duplicate submits safe; no mixed revisions; canceled/superseded jobs cannot resolve findings; basic Windows/Linux/macOS kernel tests |
| **M2 — Provider and rule breadth** | Provider/rule owners + independent diagnostic reviewer; M0, incremental M1 | Compiler, Detekt, Lint and library adapters; atomic registry manifests; tested mappings, activation, suppressions, provider overlaps; custom graph/data-flow rules where needed | Each planned launch rule has required fixtures and context evidence; provider ID inventory fully dispositioned; Native semantics supported through an actual route; no wildcard enabling of untested diagnostics |
| **M3 — Host delivery** | Protocol/host lead; M0 host choice, M1 durable event log | Tested reference client wrapper and selected host adapter; current/legacy protocol profiles; final completion barrier; disconnect recovery; out-of-model polling | Every patch incl corrective patch acknowledged and sealed; findings demonstrably enter active/resumed model context; duplicate/stale delivery tests; unsupported-host mode reported honestly |
| **M4 — Incremental correctness and speed** | Engine + performance leads; M1 + representative M2 coverage | Impact indexes, provider reuse/cache, cache-key audit, fair queues, benchmark harness, performance regression budgets | Incremental/full normalized findings **and coverage** equivalent across patch histories; no stale result publication; queue starvation tests; measured ack/useful/complete/context/CPU/RSS/tokens, not just job IDs |
| **M5 — Reliability and corpus** | Evaluation lead independent of rule author; M2–M4 | Licensed corpus, heldout labels/adjudication, per-rule/context precision and recall where valid, proof certifications, noise reports, regressions | Required domains retain useful default coverage; all applicable rule gates met; blind corpus review passes; low-evidence checks remain explicitly advisory/opt-in/blocked, never disappear from reports |
| **M6 — Distribution and release** | Release/security lead; all above | Public CI matrices, archives/installers, signatures/checksums/provenance/SBOM/notices, upgrade/uninstall docs, multiple-project install tests, release notes | All release gates below green with exact artifact versions; target tests incl macOS/Xcode iOS pass; clean-machine installation on all three OSes; no unresolved launch-critical coverage/delivery/correctness gap |

M1/M3 and individual M2 rule packs can progress concurrently with explicit interface
ownership. M4 optimizations may not merge without equivalence tests. The plan is not
for one developer to manually write hundreds of replacements: reuse, generated
manifest checks and fixture generation reduce repetitive work, but independent
validation still has real cost.

**Sizing assumption, not a commitment:** with 4–6 experienced engineers and dedicated
evaluation/platform capacity, budget multiple quarters, not a weekend. Re-estimate
after a 2–4 week M0 spike with measured adapter throughput and rule-validation labor.
Native semantic feasibility, the amount of atomic upstream integration testing, and
host delivery are schedule drivers. A date is not a reason to relabel a narrow beta
as the specified first release. Internal builds may use subsets while clearly labeled
incomplete.

### Definition of done for every implementation PR

- Tests accompany code, including negative/applicability and regression cases.
- Affected rule status, version support, provenance and limitations are updated.
- Changes to cache keys, project graphs, scopes, protocols or toolchains include
  mutation/restart/equivalence tests and migration behavior.
- No fabricated 'tested' status, unapproved default-rule removal, weaker precision
  denominator, or performance result measured on an easier invisible workload.
- Security-sensitive parsing/execution/delivery and diagnostic algorithms receive
  independent review; routine implementations can use the requested workhorse model.

## 7. Release gates: conjunction, not a score

Release requires **all** of these. A high score in one column does not compensate for
a failed requirement elsewhere.

| Gate | Evidence required |
|---|---|
| **G1 Broad relevant launch scope** | Versioned atomic provider inventories, all included rules dispositioned, meaningful validated coverage across every required domain, explicit exclusions/gaps and zero hidden disabled defaults |
| **G2 Rule reliability** | Per-rule/context ktChecker integration tests; independently reviewed evidence; proof or empirical gates as appropriate; suppression/configuration/valid-alternative regressions; credible recall where labels permit |
| **G3 Patch and cache correctness** | Sealed snapshots, incremental/full equivalence and coverage equivalence, invalidation mutations, graph expansion, generated-code cases, concurrency/race/restart traces |
| **G4 End-to-end delivery** | Real selected host/context evidence, active and resumed turns, no model polling loop, corrective-patch recurrence, final barrier, duplicate/stale/reconnect recovery |
| **G5 Honest speed/resource reporting** | Reproducible cold/warm benchmark artifacts for declared hardware/project/patch classes; separate ack/useful/complete/context latencies; RSS/CPU/tokens and multi-project contention; target misses disclosed |
| **G6 Checker portability** | Actual checker build/unit/integration/install/uninstall/process/path tests on Windows, Linux and macOS—not only documentation scripts |
| **G7 Target correctness** | Representative Android tooling execution, Desktop/JVM execution, iOS compiler/link/simulator tests on macOS with compatible Xcode; unsupported host-target cells exposed |
| **G8 Distribution/security/operability** | License decisions, notices, pinned providers and actions, artifact verification, trust boundaries, local auth, migrations, crash recovery, cache quotas, redaction and clean-machine use |

The useful-first-findings goal is **1–3 seconds on small warm patches**, still
unproven. M0 establishes a declared benchmark envelope; M4 records p50/p95/p99,
including regressions and no-finding cases. If the target is missed, optimize,
constrain the **disclosed performance envelope**, or delay release; do not announce
success using acknowledgment time, silently skip required checks, or lower diagnostic
quality. Release acceptance must explicitly approve the measured envelope; no target
is converted into a factual claim without data.

## 8. Principal risks and decisions still open

| Risk/assumption | Consequence | Mitigation / decision gate |
|---|---|---|
| Standalone Kotlin semantic APIs and PSI/provider versions evolve | Crashes or subtly incorrect symbol/type results | Version adapters; exact tuple tests; narrow stable API use; fail closed and broaden scope; M0 feasibility |
| Detekt type resolution is not universally Native-capable | False claims of KMP semantic coverage | Compiler/native/qualified Analysis API route and explicit per-context unavailable cells; no launch workaround by hiding iOS |
| Protocol/host support differs and changes | Completed results never enter model context | Protocol profiles, thin bridge, host-owned durable inbox, observed injection tests; notifications alone are insufficient |
| Broad rule catalog has large validation cost | Pressure to ship noisy rules or quietly cut breadth | Distinct proof/empirical evidence lanes, independent reviewers, frozen scope and visible gaps; adequate staffing |
| Dependency/project import dominates tiny-patch latency | Fast engine cannot produce useful results in target time | Persistent project models, correct invalidation, explicit cold/warm separation; expensive tasks do not block fast applicable rules |
| Incorrect snapshot/cache/impact reuse | Wrong revision findings or missed defects | Conservative expansion, executable mutation histories, independent oracle, epoch fencing and coverage receipts |
| macOS/Xcode availability or runner-image drift | No trustworthy iOS evidence | Pinned supported Xcode/SDK selection; macOS scheduled/release lanes; retain logs; no Linux 'iOS pass' |
| Resource pressure across many projects | OOM, GC stalls, starvation | Global budgets, per-project fairness, worker recycling, backpressure, realistic memory tests |
| Project code, scripts, artifacts or findings are malicious | Source modification, credential leakage, command/prompt injection | Non-executing default for untrusted inputs, explicit task trust, sandbox limits, structured evidence, no automatic command execution |
| Actual dependencies are newer than rule knowledge | Wrong API advice | Resolve versions and symbols; bounded support matrix; unsupported-state reporting and maintenance cadence |
| Subjective architecture/performance rules | High noise and harmful rewrites | Declared policies, data-flow evidence or profiling; advisory/opt-in; valid alternatives mandatory |

Questions to resolve at M0, not to block planning: preferred first agent host; whether
the owner can run iOS locally or relies on CI; real target projects/toolchain lines;
resource caps; release-license choice and willingness to redistribute particular
providers. Defaults above remain reversible and explicitly scoped.

## 9. Evidence and maintenance policy

Research distinguishes fetched primary-source facts, proposed choices, and unproven
targets. A release/version appearing in official metadata is **not** proof of its
compatibility with every other latest release. Links and observed versions in the
research records support the decisions, while exact source hashes/artifact coordinates
belong in implementation manifests. A future provider release cannot automatically
activate new diagnostics or inherit validation.

Use a monthly compatibility review, scheduled latest-version canaries, and prompt
security fixes; retain a deliberate stable supported tuple plus a qualified previous
tuple where maintenance cost is justified. Deprecations are announced with migration
advice. Unknown versions can run individually proven context-free checks but remain
partial; users can see precisely which coverage they lost.

Delegation followed the requested split: `gpt-6.1-sol` with `max` was explicitly
requested for routine research/inventory/validation planning; `gpt-6-astra` was
requested for engine/protocol decisions and independent correctness review. The tool
accepted those assignments. Rate-limited work was disclosed rather than silently
substituted; a model request or source fetch is not itself evidence that the resulting
design is correct. Independent review and executable implementation gates remain
necessary.

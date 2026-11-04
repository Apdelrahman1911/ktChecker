# Independent design audit

**Audit date:** 2026-11-04. **Scope:** release design, rule eligibility, evidence claims,
snapshot/incremental correctness, and agent-delivery guarantees. This is **not a
code-validation report**. At the start of the audit the repository contained a
README and no checker implementation, fixtures, benchmark results, or executable
host adapter. Documents and registry validation added during this work do not
change that distinction.

## Evidence and verdict vocabulary

- **Specified:** a design or acceptance condition is written down.
- **Implemented:** executable behavior exists at an identified revision.
- **Tested:** a reproducible test of that behavior ran and its result is retained.
- **Certified (conditional):** a narrowly scoped proof/contract argument and its
  implementation conformance evidence have passed review. This is not a measured
  field-precision claim or a proof about arbitrary builds.
- **Field-validated:** a frozen detector/version/configuration passed its
  predeclared natural-project evaluation. Synthetic fixtures do not qualify.

No rule is promoted through those states by being listed in a catalog, wrapping
an upstream analyzer, citing an API, or naming a future test. A schema validator
can establish registry consistency, not semantic correctness or runtime speed.
The first release must satisfy its promised broad default-coverage matrix; an
empty default set with a large opt-in catalog is not an equivalent release.

## Precision gates without proof laundering

There are distinct, explicitly reported routes to default eligibility. No route
removes per-rule implementation, conformance, adversarial-negative, suppression,
target, compatibility, and delivery tests.

| Route | Required argument/evidence | What it does **not** establish |
| --- | --- | --- |
| Compiler or closed-world semantic contract | Exact resolved compiler/API/configuration contract; supported versions and all assumptions; source evidence; reviewed implication from inputs to diagnosis; adversarial valid-opportunity corpus; unavailable facts cause abstention. | Universal correctness, completeness, or measured field precision. Reusing an upstream engine does not certify the wrapper. |
| Explicit project policy | A user/project-selected rule and its explicit configuration, faithfully enforced and reported as policy rather than a universal Kotlin defect. | That a convention, architecture, formatting choice, or stronger-than-API rule is intrinsically necessary. |
| Heuristic behavioral diagnosis | Frozen rule evaluated on independent held-out, naturally occurring actionable alerts: at least 200 adjudicated sampling units, observed precision at least 98%, and a one-sided 95% Wilson lower bound at least 95%. | Recall, target/version generalization, joint confidence across the whole registry, or correctness on out-of-distribution projects. |

These precision floors alone are not the complete promotion gate. The independent
opportunity/recall assessment, stronger high-consequence security thresholds and
context-specific release requirements in [validation-ci.md](validation-ci.md)
also apply. Its proof-lane real-project opportunity floors need the specified M0
feasibility pilot before locking; copied/synthetic cases cannot fill a real-project
quota, and a smaller sample alone cannot replace a proof argument.

For the last route, use the published Wilson formula with `z = 1.6448536269514722`
and retain the integer counts. For example, 196 confirmed/200 evaluated gives
98% observed precision and approximately 95.62% lower confidence bound. This is
an illustrative calculation, **not a ktChecker result**.

The sampling plan must define the user-actionable incident, repository selection,
deduplication and clustering before labels are seen. Two hundred repetitions of
one bug, generated templates, or correlated alerts from one repository are not
200 independent observations. Ordinary binomial Wilson confidence is valid only
under its sampling assumptions; retain cluster information and apply a justified
cluster-aware analysis, or restrict the predeclared sample to independent units.
Do not publish an ordinary Wilson result as cluster-corrected evidence.

Ground truth requires blinded/adversarial review and a documented dispute process.
Unresolved cases are not silently dropped or labeled true; until adjudicated they
prevent promotion (and may conservatively be counted as non-confirmations).
Report abstentions, unsupported targets, sampling exclusions, finding deduplication,
and per-domain coverage separately. Precision alone can be made vacuous by never
emitting a finding. Do not tune on the held-out corpus and reuse it as validation.
A changed semantic predicate, support boundary, upstream version, or relevant
configuration requires a new evidence review, not inherited green status.

Rare compiler errors need not occur naturally 200 times merely to ship a correct
compiler integration. Conversely, deterministic AST matching, a library's general
documentation, a plausible warning, or a passing fixture is not a proof. In
particular, absence of `close`, a timeout, a dispatcher switch, or a lifecycle
wrapper does not prove a defect without ownership, aliasing, execution-context,
and contract facts. Security/performance/intent advice stays opt-in unless it meets
the stated route; project architecture is not inferred and imposed by default.

## Required adversarial correctness checks

### Coherent snapshots and patch boundaries

- Rehashing before and after analysis, mtimes, debounce, and filesystem watches do
  not by themselves prove a coherent multi-file snapshot. An outside writer can
  perform `A -> B -> A`; a scanner can observe a combination that never existed.
- An exact guarantee needs supplied immutable patch-tree bytes, a real atomic
  filesystem snapshot covering every input root, or a cooperative write lease
  with complete input capture and an explicit outside-writer assumption. A
  seqlock works only if every relevant writer participates, epochs cannot be
  reused, and all changed inputs are covered. Watcher overflow or uncertainty
  invalidates certification; it is not repaired by a quiet interval alone.
- Include build/settings files, compiler/plugin options, dependency artifacts,
  generated sources, target/source-set membership, relevant environment/toolchain
  identity, file deletion/rename, and negative lookups in snapshot identity.
  Sources plus a stale Gradle model are not one coherent semantic world.
- A final gate must name the reviewed immutable patch/tree and analysis identity.
  Findings for a prior revision cannot clear a newer mutation. A best-effort IDE
  observation must not be relabeled an exact patch review.

### Incremental invalidation and cache reuse

- Use old **and** new dependency/source-set graphs. Removal, newly introduced
  declarations, changed overload candidates and negative lookups can invalidate
  consumers that had no previous resolved edge to a symbol.
- Account for Kotlin inline bodies, `const`, friend visibility, type aliases,
  expect/actual and hierarchy changes, compiler plugins, generated APIs, and build
  configuration. Private implementation changes are not safely ignored by every
  rule; a rule-specific dependency proof is needed to use a narrower cache key.
- Unknown dependency edges, failed model import, stale generators, unsupported
  compiler/plugin versions and unavailable target tooling widen scope or produce
  explicit incomplete/unknown coverage. They must not silently select an empty
  affected-module set or reuse green results.
- Cache entries bind their full semantic assumptions and rule/suppression
  configuration. Reusing a finding body does not permit reusing an obsolete
  source location, evidence, delivery status, or patch-completion record.

### Liveness, open findings and real delivery

- Every admitted patch needs a durable review obligation. Pure latest-wins
  scheduling loses obligations and can starve expensive rules under continuous
  edits. Coalesce equivalent computation, not required coverage or acknowledgments;
  use bounded admission and reserved service for slow work.
- A finding is resolved only by successful, comparable coverage of its rule,
  target, and scope establishing absence, or an explicit separately reported
  policy disposition. Cancellation, timeout, supersession, stale data and unrelated
  successful jobs do not establish resolution. A deleted file can retire a finding
  only after the deletion and its semantic consequences are included in coverage.
- Host tool discovery, a successful notification send, a hook firing, and a user
  seeing a UI banner do not prove the next agent decision consumed the finding.
  Delivery requires tested host-specific insertion into subsequent model context,
  revision-bound receipts/acknowledgments and a mutation fence where promised.
- Distinguish strict enforcement from cooperative instructions. Unsupported hosts,
  failed context insertion, dropped sessions, cancellation and reconnect replay
  require explicit degraded/blocked status, never a claim of universal automatic
  patch review. This guarantee also does not prove an agent obeyed advice.
- An insertion receipt alone is not proof that a model request included the item.
  Strict final delivery must require observed request inclusion or a tested atomic
  inject-and-continue guarantee before a final action. Freeze a finite required-event
  frontier after analysis settles; receipt/control events must not recursively add
  new delivery obligations. Recheck source, coverage and policy epochs afterward.
- Host context authority matters: a hook's developer-context field is not equivalent
  to an external tool-data channel. Trusted framing must quote/escape untrusted
  paths, source excerpts and log text; no source-provided instruction becomes an
  adapter directive or an automatically executed repair.

## Audit findings and disposition

Priorities distinguish a **P0 contract failure** from a **P1 diagnostic/release
evidence issue** and **P2 planning-tool hygiene**. A design mitigation below is not
an implementation pass. The rule review is a targeted semantic audit of risky
default candidates and source-derived inventories, not a second implementation
or an exhaustive proof of every catalog entry.

| Priority / issue | Concrete observation and remedy | Disposition |
| --- | --- | --- |
| P0 — Insertion is not actual model delivery | The initial final gate accepted insertion ACK plus a supported opportunity to process an event. A host can persist injected history without issuing a next model request. Require actual request inclusion / `model_observed`, separately from ACK, and a finite event-frontier cut with epoch revalidation. | Corrected in protocol §5 and architecture §§6.5–6.6; independently re-read. Actual host behavior remains untested. |
| P0 — Torn snapshots, lost intermediate patches, stale resolution | Hash scans/watchers cannot rule out ABA; latest-wins jobs erase intermediate obligations; replacing finding lists clears unrelated issues. Exact immutable capture, all-root input identity, old+new impact graphs, reserved slow service, backpressure and same-scope absence certificates are required. | Specified in architecture §§3, 5–7. Runtime race/equivalence/liveness evidence is absent. |
| P1 — Ordinary fresh builds must not require an impossible global hermeticity proof | The initial observed-model wording could leave ordinary Gradle projects permanently incomplete despite a fresh fully specified import. Certify bounded rule-relevant inputs against the frozen observed configuration, distinguish completeness from reuse, and reimport when the full build read set is unknown. | Architecture §4.2 and completion predicates now make that distinction; exporter conformance still unimplemented. |
| P1 — Upstream labels can launder heuristics into proofs | Initial Detekt default candidates classified identity-string output, `error(Throwable)`, empty ranges, redundant null checks and documentation/style choices as high-certainty correctness. An `equals` override delegating to `super.equals` with inherited hash behavior is a valid counterexample to a universal missing-hash defect. | Reclassification and valid-alternative cases independently confirmed in the JSON and final research prose. The check-result default now requires an adapted exact-FQN annotation profile, not upstream annotation globs or Flow/Sequence/Stream return-type heuristics. Native scope and compiler warning/settings limitations remain explicit. |
| P1 — Android/Compose provider severity is not proof either | Initial high/correctness imports included recommended flag style, intentional constant aliases, fuzzy permission-name typos, Java language-level risk and modifier factory conventions. Empty `buildList` and unmodified mutable collections also have legitimate uses. | Independently confirmed owner narrowed the imported exact-contract set, added resolved preconditions, and moved the cited heuristic/convention cases to empirical, advisory or selected-policy routes. No provider integration or field qualification follows from that metadata correction. |
| P1 — A proved effect need not be an unintended defect | Unreachable code, failed/safe casts and ignored unary results can be deliberate. `return i++` can have necessary effects observed by `finally`, an escaping closure or custom `inc`. The compiler also warns on intentional floating-point division by zero and empty ranges. | Five remaining Detekt effect candidates are now advisory, with adversarial controls. Compiler warning plans distinguish genuine no-emission counterparts from legitimate intent controls that still require faithful diagnostic transport. No alternate provider may promote the same warning into proof merely by changing its label. |
| P1 — Exact provider predicates were misidentified | `ManifestResource` concerns forbidden resource-configuration variation, not generic type mismatch; `ReferenceType` concerns resource aliases with exceptions. `TextFieldBufferInternalAppend` enforces an internal foundation-maintainer convention, not a forbidden application API. | Corrected predicates and controls independently re-read. The append rule is selected maintainer policy. Imported severity case normalization now preserves warning/error/info, with fatal mapped to error; this is metadata, not measured impact or precision. |
| P1 — Recommendation and termination semantics were overstated | WorkManager supports nonunique periodic enqueue. Compose runtime explicitly supports one-dimensional linear interpolation in `ArcAnimationSpec`; the lint type whitelist is not a dimensionality prohibition. Unbridged Objective-C exceptions can intentionally terminate on fatal invariants. | Periodic-work uniqueness is selected policy; arc-animation advice is advisory with valid 1D/Size/custom-converter controls. The Objective-C proof candidate now additionally requires an explicit recoverable-boundary contract. All corrections independently re-read against the narrowed records. |
| P1 — Escape/capability is not an invalid-use witness | A public setter alone does not establish post-construction `Immutable` mutation; returning a scoped pointer does not establish later invalid memory access. An ended pin scope with a still-live owner/nonmoving collector is not automatically reclaimed storage. | Exact candidates now require value-changing observable mutation or actual invalidating lifetime/access facts. Address-only escapes are not proved memory defects; pin-lifetime advice stays separate. Four overlapping core Native records are excluded aliases to canonical platform owners, not extra detectors or coverage. |
| P1 — Annotation recognition is not a complete effect proof | The maintained `InvalidReadOnlyComposable` detector recognizes annotated callees; missing read-only metadata alone does not establish a forbidden composer effect under every compiler transformation. | The narrowed candidate requires an actual resolved eager non-read composer effect/group write under the selected compiler. Deferred callbacks and unknown effects do not qualify. The published annotation contract and final record were independently re-read; no effect-aware classifier has run. |
| P1 — Six nonempty test-plan strings are not six concrete tests | Initial source extraction flattened/truncated examples; four Detekt entries had identical positive and negative excerpts, including `MissingSuperCall`. Some snippets ended before the offending override; method extraction could select declarations inside quoted fixture source. | Complete distinct Detekt source excerpts and enclosing test pointers were restored; the copied standalone `@ReplaceWith` negative now correctly uses `@Deprecated(..., replaceWith = ReplaceWith(...))`. Platform citation enrichment and residual gaps are described below. No fixture execution follows from these corrections. |
| P1 — A failing assertion can be the expected test behavior | An empty inner `assertFailsWith` deliberately fails inside a passing outer `assertFailsWith<AssertionError>` calibration test. JVM assertion enablement also depends on the actual compiler assertion strategy, not only `-ea`/`-da`. | The custom empty-block candidate now requires an ordinary reachable assertion expected to pass, excluding enclosing expected-failure/catch/calibration contexts. Assertion policies include actual compiler strategy and runner flags. These are unexecuted fixture plans. |
| P1 — Partial overlap is not an exact provider alias | Mandatory braces on all loops and a multiline-loop formatter need not select the same code. Excluding one as wholly covered by the other hides a coverage delta. | Independently confirmed MandatoryBracesLoops is retained as opt-in with explicit overlap controls. Other aliases still require exact configured equivalence or a visible coverage delta before release. |
| P1 — Precise library claims require adversarial API semantics | Mutex wait can end by cancellation/timeout; independently cancelled `Deferred.await` need not cancel its caller; JSON naming strategies do not transform `JsonNames` alternatives; `ByteArrayInputStream.close` need not invalidate reading; optional unused bytecode references do not prove a runtime linkage failure. | Predicate/negative-case corrections independently confirmed, including Room's effective nested-lease lifetime: inner-block exit alone no longer implies expiry of an enclosing live lease. No detector is implemented or certified by this review. |
| P1 — Changed short-circuit behavior need not violate intent | Consuming Arrow Raise short-circuiting proves a control-flow effect, not that deliberate recovery is a defect. Likewise, a missing close requires bounded ownership and complete modeled exits, not a textual absence heuristic. | Independently confirmed Arrow consumption is advisory/intent-dependent with propagation-contract or empirical promotion; resource candidates specify ownership/transfer effects and route-specific qualification. |
| P1 — Process isolation does not imply separate cache storage | Separate processes, filesystem objects or namespace labels can still access the same backing directory. Treating them as automatic exemptions would miss concurrent Coil DiskCache ownership violations. | Independently confirmed actual backing-storage identity and overlapping lifetime are required; shared-directory Android processes are no longer an exemption. |
| P1 — Source links and current versions are not compatibility evidence | The initial Mutex API URL returned HTTP 404; the corrected API path returned HTTP 200. Current protocol and SDK revisions also differ; an MCP Tasks schema is not a durable task receiver or automatic model continuation. | URL correction accepted. Research/architecture/protocol state exact version limits and future host/adapter tests; runtime compatibility remains untested. |
| P1 — Context channel can promote untrusted code into authority | Official OpenAI Docs describe Codex PostToolUse `additionalContext` as developer context, unlike lower-priority external messages. Raw diagnostic/source instructions must not be concatenated into trusted hook directives. | Protocol explicitly separates channel authority and requires trusted framing/escaping; no malicious-content host test has run. |
| P2 — Planning validator and clean-checkout CI limitations | Malformed status/URL values initially raised uncaught exceptions. Existing `README.md` paths could satisfy all evidence-file presence gates. `git diff --check` in a clean CI checkout checked no committed patch. A subset of catalogs could be accepted. | Corrected structured errors, committed-range whitespace command and catalog/baseline guards were inspected; 31 planning-tool tests passed on Linux. Evidence-file checks remain explicitly structural, never release certification. |

The master plan and registry guide correctly retain broad default-domain coverage as
a conjunction of launch gates. They do not redefine the requested first release as
a small subset, treat every library brand as universal target support, or impose an
architecture such as MVVM. Compiler/closed-world and empirical eligibility lanes
must stay separate in the final rule dispositions as well as prose.

### Remaining fixture-design work

The compiler's 443 planned FIR/IR mappings use tagged source-marker seeds, not
443 passing adapter tests. Sixty-eight selected sources carry
`IGNORE_FIR_DIAGNOSTICS`, and three selected files are explicitly `.disabled.kt`;
their limitations require an independently constructed active configuration and
actual compiler/adapter execution before promotion. The core inventory explicitly
retains 102 entries needing additional boundary-fixture design and two needing
concrete regression selection. Other plans can still describe a counterpart to
construct rather than provide a complete runnable harness. Preserving a genuine
compiler warning is compatible with accepting its intentional use; an adapter
must not fabricate a no-warning exemption.

For the 609 published platform issue occurrences, current source accounting has
593 fetched test-source seeds: 583 contain issue-specific expected-output text and
10 are detector-only seeds. There are 514 clean-assertion seeds; 79 entries with
source still lack a distinct clean-control seed, and another 16 lack fetched test
source. The Compose pack has 43 positive-source seeds and 41 control-source seeds.
These are not ktChecker tests or necessarily release-matched controls: shared
detector tests, changed upstream snapshots and legitimate cases reported by the
unmodified provider still need per-ID qualification. Blank/generic validation
boilerplate, source counts or synthetic copies cannot fulfill that work.

## What this audit actually checked

- Read the master contract, registry guide, architecture, protocol, proposed
  repository layout, final core/platform research reports, relevant source ledgers,
  policy-provider comparison, final validation/CI and benchmark specification,
  planning scripts and selected high-risk rule records. Reviewed all 64 platform
  custom-manifest plans and all
  29 added core contract plans, including excluded aliases; this is not exhaustive
  semantic verification of every imported rule. The independent review
  raised issues to the owning writers instead of modifying their files.
- Reproduced the initial malformed-metadata failures and evidence-file presence
  limitation, then ran the revised **31 planning-tool unit tests**, successfully,
  on the available Linux/Python environment. This does not test Kotlin diagnostics
  or establish Windows/macOS execution.
- Independently parsed an earlier protocol draft, then reran the final repository
  `scripts/validate_contract_examples.py` in normal and `-O` modes using `jsonschema`
  4.26.0 from the researcher's temporary environment. **Nine synthetic application
  examples passed and eight negative fixtures were rejected**: Submit 1, Finding 1,
  Review 5, Ack 1, Observe 1. Removing the Observe example in memory failed the
  required-family guard in both modes. Two MCP envelopes were syntax-checked only.
  Hash-locked planning schema dependencies and a CI step now exist; this audit did
  not execute that workflow on remote runners or verify receipt authenticity,
  model-request inclusion or host behavior.
- Recomputed the Wilson example above; it is gate arithmetic, not measured accuracy.
  Narrow HTTP checks confirmed the bad/corrected Mutex URL's 404/200 statuses.
- Compared all 434 FIR mapping IDs/declaration lines/severities with the retained
  pinned common/JVM/Native factory sources, finding no mismatches. Checked imported
  platform severity normalization and sampled full source/fixture contexts. Direct
  pinned-source reads included the postfix/unary detectors, the compiler's
  floating-point division warning markers, Native handle contracts and Compose's
  actual arc-animation and read-only-composer contracts. These checks establish
  source provenance or expose predicate errors, not executable conformance.
  Parsed the final 1,061-entry compiler disposition ledger and matched its 443
  mapped names/lines/severities to the JSON without mismatches. Its 612 unmapped
  entries, five excluded contracts and one alias are not additional rule coverage.
- Checked 907 distinct platform/Compose-pack source citation triples across 407
  retained files; all named methods occur within the cited line through five lines
  after it. The misplaced `ConvertToWebp` anchor was corrected to the actual
  `testWebpEligible` declaration. This is a source-pointer check, not proof that
  the snippets are complete, legal controls, release-matched or executable tests.
- Inspected retained version-specific MCP/SDK/Tasks source excerpts. For the Codex
  hook authority and background-timing point, independently searched and fetched
  the matching [official OpenAI Docs](https://learn.chatgpt.com/docs/hooks) through
  the official documentation endpoint. Other rule-source checks were targeted,
  not a fresh crawl or verification of every external catalog URL.

No compiler, Kotlin/Native target, Android application, Desktop application, checker
worker, actual host adapter, corpus labeling pipeline or benchmark was run by this
audit. The raw research scratch directories are not portable release evidence.

## Residual release risks

| Risk | Release evidence needed | Present status |
| --- | --- | --- |
| Broad inventory is only nominal coverage | Per-detector implementation and traceable positive, valid-negative, unknown-state and target tests; no catchall counts. | Not demonstrated; runtime not implemented. |
| Source seeds are not complete per-rule suites | Fill missing/disabled/source-only cases, construct genuine legal controls and replay the exact provider/project configuration with local mapping/suppression/target tests. | Explicit fixture-design gaps remain; no Kotlin/provider fixture ran in this audit. |
| Low false positives across KMP ecosystems | Route-specific reviewed evidence; natural-project labels for heuristics; explicit applicability limits and canaries. | Not measured. |
| Warm first useful findings in 1–3 seconds | Reproducible representative local/CI hardware benchmarks with warm definition, percentile/tail, coverage and failures reported. | Target only; no benchmark result. |
| Coherent snapshots and sound incremental reuse | Adversarial concurrent-writer/ABA/model-drift tests and incremental-versus-clean differential results. | Design-only obligation. |
| Every patch reviewed; findings reach agent context | Per-supported-host integration evidence for exact patch fencing, cancellation, reconnect, delivery, and unresolved findings. | Not exercised. |
| Upstream/version regressions and unavailable target tooling | Frozen compatibility matrix, actual API/CLI tests, wrapper contract suites, target-host CI and conservative fallback. | Not validated. |
| Evidence is overstated by documentation/metadata | Current-revision executable artifacts and retained test reports distinguished from planned fixtures and source citations. | This audit does not supply runtime evidence. |

**Release conclusion:** design acceptance and release readiness are different
decisions. Until the executable implementation and the above evidence exist,
neither broad first-release coverage, low field false positives, exact every-patch
review nor the warm latency target is verified.

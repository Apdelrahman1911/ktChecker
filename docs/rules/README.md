# Rule inventory, applicability and coverage accounting

This is a **planned first-release inventory**, not an implemented ruleset and not a
claim of accuracy. Full records are machine-readable JSON; indexes are generated
from those records. No rule has been implemented/tested/validated merely by appearing
here. All initial implementation statuses are `planned`.

## Catalogs

| Area | Human-readable index | Full records and proposed validation cases | Research and source/version limitations |
|---|---|---|---|
| Language/compiler, Detekt, KMP, maintainability and general diagnostics | [Core](core.md) | [rules-core.json](../../data/rules-core.json) | [Core research](../research/core-rules.md) |
| Coroutines, serialization/time, networking, persistence, DI, image loading, testing | [Libraries](libraries.md) | [rules-libraries.json](../../data/rules-libraries.json) | [Library research](../research/libraries-core.md) |
| Android Lint, Compose, lifecycle/navigation, Gradle/KSP and platform/tooling | [Platform](platform.md) | [rules-platform.json](../../data/rules-platform.json) | [Platform/build research](../research/platform-build.md) |
| Other applicable ecosystems and explicit alternatives | [Ecosystem](ecosystem.md) | [rules-ecosystem.json](../../data/rules-ecosystem.json) | [Extended ecosystem research](../research/ecosystem-extensions.md) |

The provider catalogs are inventories of atomic **diagnostic identities**, not a
count of distinct bugs. Providers and custom candidates overlap. Deduplicated useful
coverage, supported contexts and measured reliability matter more than totals.
Do not advertise a record count as implemented rule coverage.

[Konsist, ArchUnit and diKTat](../research/policy-provider-alternatives.md) were
also evaluated as declared-policy providers. Their unexpanded mappings remain
explicit gaps, not extra counted rules or universal architecture requirements.

## Cross-platform applicability matrix

**Proposed capability map, not tested support.** Each cell still requires the
atomic record's exact symbol/version/configuration predicate and qualification.
`common` code is checked in its real consuming compilations; it is not a fourth
runtime target. Desktop here means **Desktop/JVM**, not Native desktop.

| Rule/provider context | Common/shared source | Android | iOS | Desktop/JVM | Activation / execution constraint |
|---|---|---|---|---|---|
| Kotlin syntax, configured maintainability/formatting | Candidate | Candidate | Candidate | Candidate | Supported parser and language features; style/policy selected explicitly; no type claims from syntax |
| Kotlin compiler and semantic contracts | Per consuming compilation | JVM/Android adapter | Native/common adapter | JVM adapter | Exact compiler/plugins/options and complete model; Native route is a launch feasibility gate |
| Detekt type-required rules | Within qualified consuming compilation | Qualified full adapter | Not supplied by documented Detekt Native light tasks | Qualified full adapter | Separate compiler-backed route needed for Native; no blanket Detekt KMP semantic claim |
| Android Lint and embedded AndroidX registries | Only supported Android consumption/source mapping | Qualified variant | No Android-only execution claim | No general claim from Android provider | Resolved AGP/Lint/UAST/library/SDK tuple; inspect each provider's actual scope |
| Compose Multiplatform contracts | Resolved common APIs | Matching Compose APIs | Matching Compose APIs | Matching Compose APIs | No activation in native-UI/non-Compose projects; Android-only Lint implementation does not become a KMP implementation |
| Coroutines/Flow, serialization/time and common-library APIs | Versioned contracts | Actual artifact/API | Actual Native artifact/API | Actual artifact/API | Resolve overloads, ownership, context and target variants; not all checks apply to all artifacts |
| Ktor, persistence, DI, image loading | Only published/resolved common APIs | Platform-specific adapters where applicable | Native-capable APIs only | JVM-capable APIs only | Distinguish Room2/Room3, engines/drivers/plugins and runtime versus compiler validation |
| Android-only Hilt and JVM-specific networking/testing APIs | Do not infer from imports | Qualified JVM/Android context | No brand-based support claim | Where the exact JVM API is applicable | Retrofit/OkHttp, MockK/Mockito/JUnit and KMP alternatives keep separate contracts; runners are versioned, not inferred from annotations alone |
| Testing and reliability | commonTest mapped to consumers | Host/device test distinction | Native/simulator tests | JVM tests | Runner/scheduler/mocking contracts; actual timing/device claims need execution |
| Build, dependencies, KSP and generated code | Whole compilation graph | Android tooling where required | Apple tooling where required | JVM/Desktop tooling | Trusted matching importer/tasks and generator provenance; build-script string matching is insufficient |
| Architecture/module policies | Declared graph and API boundaries | Same policy with platform ownership | Same policy with platform ownership | Same policy with platform ownership | No mandatory MVVM/Clean Architecture; infer suggestions only, enforce declared contracts |
| Security, resource and performance contracts | Bounded supported facts | Applicable API/variant | Applicable Native/interop API | Applicable JVM API | Match the rule's threat/ownership/effect/cost model; runtime-only behavior remains an execution obligation or explicit gap |

The [library research](../research/libraries-core.md),
[platform/build research](../research/platform-build.md) and
[extended ecosystem research](../research/ecosystem-extensions.md) identify exact
observed API versions and exceptions. The [host/target CI matrix](../validation-ci.md)
separately states where tooling can actually execute. A Windows checker can produce
qualified common findings while honestly reporting an unavailable iOS execution
obligation; it cannot convert that obligation into passing or inapplicable work.

## Record contract

The initial structure is defined by [the schema](../../data/rule-record.schema.json)
and checked by [the validator](../../scripts/validate_plan.py).

| Field | Meaning |
|---|---|
| `id`, `category` | Stable ktChecker rule identity and functional family; identity is not regenerated when severity/title changes |
| `problem`, `rationale` | Specific issue and why it matters; prose must not imply a heuristic is a proof |
| `sources` | Authoritative documentation or maintained upstream rule/source references; provider and research notes qualify the versions/access date |
| `applicability` | Required symbols, artifacts, configuration, project intent, source sets or ownership evidence |
| `platforms` | Potential supported analysis scope among common/Android/iOS/Desktop/JVM; **conditional**, not evidence that each version/target works |
| `version_scope` | Observed basis, candidate supported API/compiler/provider range and unresolved compatibility limits; prose predicates become executable qualified ranges during implementation |
| `implementation`, `provider`, optional `upstream_id` | Existing/adapted/custom path and exact upstream diagnostic when mapped; no untested blanket import |
| `depth`, `cost` | Syntax/semantic/graph/build/runtime requirement and fast/medium/slow scheduling estimate; not a benchmark result |
| `kind` | Correctness, probable defect, declared policy, or style; orthogonal to severity |
| `severity`, `certainty` | Error/warning/info impact and high/medium/low/intent-dependent evidence quality; not statistical confidence or observed accuracy |
| `limitations` | Known analysis blind spots, valid alternatives, applicability risks and explicit unsupported contexts |
| `validation` | Proposed positive, negative, boundary, platform/version, suppression/configuration and regression cases; not an executed test report |
| `status` | Planned → implemented → tested → validated; no transition without repository evidence |
| `default_activation` | Intended eligible default, advisory, opt-in, or excluded; **no record currently enables a runtime rule** |
| `disposition_reason` | Why the check belongs in that mode, is excluded, or cannot currently qualify |

`existing` means an existing implementation was identified, **not** that it was
integrated. `adapted` can mean mapping/filtering/enrichment rather than copying code.
No deprecated internal API or newer library signature is assumed compatible merely
because names are similar.

### Cost and semantic depth

- `fast`: intended for incremental lexical/AST or already-available symbol/graph facts.
- `medium`: may need semantic resolution, bounded flow/ownership analysis or an
  affected-module graph; amortized project setup is tracked separately.
- `slow`: usually provider initialization, Gradle/task execution, native compilation,
  target tests or larger whole-project checks. Schedule as coverage obligations.

These are **estimates**, not 'milliseconds per rule'. Several hundred provider IDs
may share one pass; one simple-looking check may force a costly semantic session.
Measure provider/session overhead, changed-scope work and total resource consumption
without summing misleading per-rule estimates. Never run a type-required rule with
missing types just to call it fast.

## Actual activation is a four-way intersection

```text
eligible rules for snapshot =
  project-declared intent and configuration
  ∩ resolved source-set / target / dependency / compiler facts
  ∩ exact provider + rule support manifest
  ∩ per-context integration and reliability qualification
```

For example, a Room2 rule must not advise Room3 APIs; an Android lifecycle or Hilt
diagnostic must not run on a similarly named common/iOS API; a Compose rule must
understand the actual compiler/library version and call semantics; a Koin
configuration using a supported compile-time graph checker must not be forced to
add a redundant runtime graph test. Inability to resolve the predicate produces
`unavailable`/partial coverage, not a guessed violation or a pass.

The runtime rule manifest must use machine-readable artifact coordinates, version
ranges, source-set/target predicates, option requirements, required capabilities and
validation evidence references. The prose planning registry is not sufficient for
runtime version matching. Do not interpret a text phrase such as '2.x' as permission
to execute against all future minor releases.

## Status and enablement gates

| Status | Evidence needed for promotion |
|---|---|
| Planned | Rule contract, sources, applicability/limitations and concrete test requirements |
| Implemented | ktChecker adapter/rule code, source mapping and configuration/coverage plumbing |
| Tested | Executed ktChecker integration tests across the declared contexts, including negative/alternative cases; retained run/toolchain IDs |
| Validated | Independent review and the applicable proof-backed or empirical acceptance lane; corpus/version/context limitations and raw denominators reported |

Upstream tests help develop fixtures but do not advance the ktChecker status.
Passing a few examples only advances the tests that actually ran. A rule can be
validated for one tuple and unavailable for another; implementation will store status
per applicability context rather than upgrading a whole family from one happy path.
The initial single `status` is deliberately `planned` for every record.

The validator rejects a claimed later status without evidence-file references and
rejects runtime enablement without `validated`. This is a **structural guard**, not
an assessor of the truth or sufficiency of those files. Human review and the evaluation
pipeline decide promotion. Release automation must inspect qualified contexts and
sign-off, not only check that a file exists.

The complete [validation strategy](../validation-ci.md) separates proof-backed
closed-world diagnostics from intent-sensitive/statistical heuristics. Deterministic
code is not necessarily sound code. Rare high-confidence compiler errors need not
fabricate hundreds of natural failures, but they do require oracle equivalence,
adversarial alternatives, unsupported-state abstention and an explicit statement
that field precision is unestablished. Heuristic defaults require representative,
independent empirical evidence; advisory output is still tested and clearly labeled.

## Overlap, aliases and suppression

At provider qualification, build a versioned alias table, for example: a custom
missing-close candidate, Detekt/Lint closeable warning and a library-specific owner
check can refer to the same underlying resource. Prefer the strongest applicable
diagnostic and retain all agreeing providers/evidence. Conditional library rules can
add API-specific fix guidance without producing another warning for the same bug.

Deduplication compares semantic subject/defect, cause/evidence, snapshot and target,
not only line numbers or titles. Opposite platform consequences, different data-flow
paths and independent resources on one line remain distinct findings. Contradictory
providers trigger an explicit uncertain/disputed state, not confidence by majority.
Test both over-deduplication and under-deduplication, aliases across provider upgrades,
renames/moved code and suppression scope.

Suppressions are reviewable state: narrow rule/scope, reason, author and optional
expiry, with config and stale-suppression diagnostics. A baseline records accepted
existing debt; it does not mean fixed. New or changed instances are still reviewed.
Changing configuration cannot silently close the old finding or imply a check ran.

## Exclusions and gaps are first-class

Records marked `excluded` remain visible, with reasons; unavailable provider/target
capabilities remain visible even when no source finding exists. Research notes also
record unexpanded registries, version ambiguity, runtime-only phenomena, unsupported
libraries/targets and rules that cannot be made sufficiently actionable.

Examples of **justified limitations**, not stealth scope reductions:

- No automatic truth claims about business requirements, arbitrary deadlock freedom,
  all data races or global information flow from shallow static patterns.
- No mandatory universal MVVM/Clean Architecture/layer-count rule.
- No claim that preview rendering, recomposition counts, jank, battery use, network
  behavior or flaky timing can be proven without appropriate execution/evidence.
- No universal platform support based on a library's brand name; published target
  artifacts and actual source sets determine eligibility.
- No automatic use of obsolete Kotlin/Native freezing advice on modern memory-manager
  contexts, or of future Swift/Compose/Room API advice on older versions.
- No third-party pack redistribution without a verified license, maintenance and
  dependency compatibility assessment.

## Preventing a broad inventory from becoming a tiny release

All non-excluded records are intended first-release scope in their stated mode.
At M0, publish a locked launch manifest with exact atomic IDs, default/advisory/opt-in
disposition, provider aliases, domain/platform capability obligations and exclusions.
Review its diff on every PR. Every included rule needs ktChecker integration tests;
every actionable default needs its additional qualification gate. Default-domain
coverage must be meaningful even after aliases and exclusions are subtracted.

If a required rule/context cannot qualify, the response is remediation, an explicit
coverage gap/replacement decision, or delaying release—not deleting it from the
denominator, declaring an entire domain covered by a title, or quietly turning
everything into opt-in. Internal staged builds are allowed and labeled incomplete;
the first public release claiming the product contract must meet the broad launch
gates. Scope changes must be explicit, reasoned and visible to the owner.

## Executable coverage reports

```bash
python scripts/rule_coverage.py --output build/reports/rule-coverage.json
```

Read the exit status and `inventory_valid` before using any coverage counts.
Invalid or stale evidence still produces exit **2**, unknown current evidence
counts, and false qualification flags. Old reference hashes are never renewed
to make a changed source tree appear tested.

On that failure path, `catalog_visibility` preserves independently validated
**declared** rules and context cells where possible. These rows are not recovered
test evidence: mapping is unknown, stage claims are false, and current evidence
is unavailable/not evaluated. Invalid catalog or context inputs leave the relevant
denominators unknown rather than inventing an empty or default passing scope.
An output left `pending` after interruption is likewise not a passing report.

## Maintaining the planning files

```bash
python -m unittest discover -s scripts -p 'test_*.py' -v
python scripts/validate_plan.py --write-catalogs
python scripts/validate_plan.py
```

These commands validate schema/metadata, status guardrails, duplicate stable IDs,
generated-index consistency, JSON example syntax and relative document links. They do **not** contact the
source URLs, analyze Kotlin, build the checker or validate any proposed rule.

The [planning baseline](../../data/catalog-baseline.json) records exact catalog IDs
and intended activation modes to catch accidental catalog deletion or silent
disabling. Updating it requires an explicit reason and review; it is **not** the M0
release-qualified manifest and does not certify breadth, safety or validated support.

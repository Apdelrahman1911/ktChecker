# Independent implementation audit

**Started:** 2026-11-04. **Status:** implementation review in progress.

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

- The lead's coordinated Gradle 9.6.1 / Java 21 run executed **all 35 independent
  Kotlin tests** successfully. The auditor independently read all four XML reports
  in `tests/audit/build/test-results/test/`: config nine at
  `2026-11-04T08:43:36.083Z`, contracts seven at `08:43:36.554Z`, kernel eight at
  `08:43:36.592Z`, and snapshots eleven at `08:43:38.092Z`. All report zero
  failures/errors/skips. The earlier 17-test run is superseded by this execution.
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
  the later project-model, parent-plan or semantic-extraction work by implication.
- At `2026-11-04T08:37Z`, the auditor executed **five independent Python tests** in
  `tests/audit/test_coverage_audit.py` and reran all **29 owner-authored coverage
  tests** successfully. The miniature evidence is explicitly synthetic: these
  establish inventory bookkeeping boundaries, not actual rule qualification.
  Command: `python3 -m unittest discover -s tests/audit -p 'test_*_audit.py' -v`.
- Other owner-authored suites whose XML was independently inspected:

  | Suite | Executed tests | Report timestamp (UTC) | Evidence limit |
  | --- | ---: | --- | --- |
  | Kernel integration + stress | 24 + 1 | `08:56:13.806`–`08:56:17.697` | Real persistence/scheduling and worker death; synthetic diagnostics and host receipts. |
  | Config (five suites) | 34 | `08:43:44.616`–`08:43:46.983` | Actual policy/authority storage, not live model-host integration. |
  | Snapshot | 42 | `08:43:41.735` | Actual capture/store and filesystem controls, not complete project-model authority. |
  | Compiler provider unit | 11 | `08:35:01.944` | Parser, identity and execution-denial tests; not actual compiler fixture execution. |
  | Runtime (two suites) | 21 | `08:43:48.590`–`08:43:53.346` | Actual child processes, quick-parent orphan, cancellation and deadline checks; cooperative POSIX boundary only. |
  | Detekt unit | 12 | `08:29:12.554` | Adapter/parser tests, not catalog-wide diagnostics. |
  | Detekt integration | 2 | `08:28:49.768` | Real pinned processes; selected syntax fixtures and resolved coroutine guidance, not all mapped rules or library qualification. |
  | Android Lint (three suites) | 17 | `08:52:03.011`–`08:52:08.017` | Includes two actual pinned Lint fixture tests; partial/unqualified results, not Android matrix qualification. |
  | Kotlin/Native | 8 | `08:51:55.009` | Catalog/parser and explicit both-target unavailability on Linux, not iOS compiler execution. |
  | CLI (three suites) | 20 | `08:51:47.711`–`08:51:51.303` | Private command/RPC/activation composition, not complete project coverage. |
  | Semantic facts | 14 | `08:52:25.544`–`08:52:25.734` | Coordinate/validation/summary tests; not actual compiler-plugin semantic fixtures. |

  All of these XML reports have zero failures/errors/skips. Source changes after
  their execution are not retrospectively covered. The Detekt integration source
  explicitly asserts unqualified/partial results; syntax stubs are not semantic
  library evidence, and upstream guidance does not implement a narrower catalog
  contract merely because it reports a related warning.
- The auditor reran **19 existing compiled Node tests** without any build or new
  inference: private RPC nine, synthetic Codex host six, and projection four.
  All passed with zero skips. They exercise malformed UTF-8, exact public-content
  matching, wrong/unowned threads, crash/reconnect, bounded projection and blank
  certificate handling. Synthetic model responses do not establish real inclusion.
- A full repository evidence-inventory generation at `2026-11-04T08:38:29Z`
  **failed closed**, reporting an Android Lint implementation-evidence digest
  mismatch for `AndroidLintProvider.kt` in the `ContentDescription` **mapping**.
  This was initially described as a sidecar; independent reinspection confirms
  there is no executed Lint sidecar. Refreshing the mapping source hash appropriately
  fixes that inventory-input error without granting execution/testing credit.
  A subsequent full generation succeeded: **1,663 records × three JVM contexts =
  4,989 cells**, with 42 implemented/tested cells and **zero qualified cells**;
  `all_requested_cells_qualified=false`, `release_qualified=false`. The newly
  written compiler sidecar accounted for 39 cells; its actual run is reviewed
  below. At `08:59:37Z`, regeneration **failed closed again**, this time on the
  old **executed Detekt sidecar**'s `DetektProvider.kt` digest after the provider
  configuration-binding correction. That owner is preserving history and
  preparing a provenance-aware rerun. The earlier successful inventory is a
  historical report, not a current-source result. Source-hash maintenance alone
  cannot establish that changed implementation bytes passed previous fixtures.

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
end. Source correction was read; a new executed run is still needed. Neither this
39-ID subset nor all 443 mapped IDs is release-qualified.

### Kernel fairness and contention evidence

The auditor read the stress test and its retained
`modules/kernel/build/reports/stress/kernel-100-patches.json`. It exercises 100
distinct archived inputs, 200 subscriptions plus 16 current-head refreshes, 16
clients, four worktrees, three service objects and 12 producer threads. The result
retains 216 reviews, 668 required events and 20 independent cancellations. SQL
checks audit each serialized dispatch against worktree/client ages and sponsor
FIFO; observed provider concurrency stays within four global/two per-worktree.
Reopening, exact event sequences/digests and independent ACK/observation are checked.

This is one bounded run of a **synthetic 20 ms provider**, not three JVMs, multiuser
qualification, CPU-time fairness or an SLA. The reported SQLite writer hold maximum
was 7.70 ms, but writer admission waited up to **930 ms** and producers retried
`ingestion_busy` **995 times** before acceptance. Those contention costs must not
disappear behind the approximate 70-work/s synthetic throughput. Terminal timing
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
integration's qualification. The actual CLI → kernel → compiler → Codex run is
pending independent evidence review; background host polling is not model polling.

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
| IA-008 | Process-descendant polling can miss a quickly orphaned background child; report opening also followed a leaf after separate symlink checks. Later review found unverified group signaling and deadlines checked only while the process remained alive. | Source corrections reread: fixed POSIX process-group launcher, TERM/KILL plus bounded group-quiescence checks, anchored `SecureDirectoryStream` report reads, and pre-spawn/post-verification cancellation/deadline checks. The 21-test owner runtime XML includes actual orphan/cancellation/deadline regressions. New-session escape and hostile same-UID report writers remain outside cooperative containment. Windows execution/report support is not established. |
| IA-009 | A guarded external action initially ran before consuming its gate in one DB transaction. Throw/crash after an external effect could roll back consumption and replay the effect. | Source correction reread: persist consumed cut and per-generation `action_claimed` intent before invoking the callback, retaining config/source fences; ambiguous return becomes `outcome_unknown`. Claimed/unknown generations cannot replay the action or admit more patches. Owner and independent exception/reopen regressions passed. This is fail-closed unknown outcome, not exactly-once external delivery. |
| IA-010 | Progress journaling serialized a provider DTO, but event fanout could reuse its mutable caller-owned collections. | Canonical detachment now precedes validation and is used for both journal and client fanout; result-policy callbacks also receive detached raw evidence. Source correction reread. Mutation-specific independent execution is not yet recorded. |
| IA-011 | A rule ID hash was initially the default implementation identity, so a code upgrade could retain narrow suppression anchors without a real version identity. | Default identity is unavailable/null; exact and narrow file/range/symbol selectors require/pin actual implementation identity. Source correction reread. Root additionally binds installed compiler artifact identity and pinned Detekt metadata into the policy registry. Independent changed/missing implementation regression passed; broad owner-selected scopes intentionally remain broader. |
| IA-012 | A default changed-file overlay scope could erase exclusions/unknown inputs inherited from its producer base or rebased integrated head. | Snapshot owner now unions base, head and request omissions. Independent ordinary-overlay/rebase/reopen regression passed. Overlay edits are not a complete project reimport. |
| IA-013 | CLI exit classification used structural `result.complete()` without honoring the kernel's incomplete/cancelled verdict. A coordinator-expression precedence error could also omit its preview reason when no rule was missing. | Source corrections reread: exit classification respects authoritative state/reasons/cancellation and explicit preview status; preview reason always appended. Owner CLI regression execution inspected. Exit zero from submission without waiting is admission/command success, not analysis success. |
| IA-014 | Existing private-state paths skipped ACL checks on non-POSIX hosts; directory-fsync errors were swallowed on all operating systems. | Root source correction reread: reject non-owner ACL allows and unsupported private-state filesystems; propagate non-Windows/unknown fsync failure as uncertain durability. This does not establish Windows qualification or hostile same-UID protection. |
| IA-015 | Broker enrollment committed credential hashes before separately persisting the private host credentials, leaving an unrecoverable registration gap on crash/write failure. | Root now persists private random-credential enrollment intent first; kernel exact binding-and-both-token replay is idempotent and cannot replace a registration. Source correction reread and owner kernel enrollment test passed. Root CLI crash-boundary execution still pending. |
| IA-016 | Coverage inventory could treat locally attested caller-selected cells as `release_qualified`, and omitted source-set/module/toolchain/config identities did not prevent qualification. | Source corrected and independently executed: all necessary identity/digest dimensions required for local cells; scoped `all_requested_cells_qualified` separated from permanently unavailable release certification. Five independent synthetic regressions and 29 owner bookkeeping tests pass. No actual rule or host became qualified from these tests. |
| IA-017 | Default preparation selected a platform inventory including opt-in candidates; installed providers could then execute those IDs without explicit activation or prior qualification. | Lead separated required candidate coverage from experimental activation. Source reread and four owner activation tests passed: no qualified default registry means no default execution; explicit owner subset/config can request experiments; disabled/excluded remain inactive/visible. Exact canonical activation plan is input-bound; absent or malformed plans never default to all candidates. |
| IA-018 | A generated Detekt configuration lived at a fixed output path, but its contents were not necessarily bound to the exact approved subprocess profile. Changed settings could reuse the same input/permit despite different generated YAML. | Owner binds generated configuration and installed artifacts in fixed profile digest arguments. Source reread and a same-original-input/permit, changed-settings denial regression authored. New execution is pending; changing only the input configuration digest would not exercise this bug. Ktlint and other generated preparation inputs require the same binding. |
| IA-019 | Compiler and Detekt fixture harnesses originally hashed live implementation source at run end, which cannot independently identify the bytecode executed during concurrent source changes. | Historical compiler originals preserved explicitly as post-run archives, not backdated startup evidence. Revised compiler startup source/artifact/resource capture and drift controls independently read. Detekt equivalent and both reruns pending. Actual failed historical runs remain evidence, but not current-source attribution. |
| IA-020 | Host coverage projection accepted whitespace-only certificate strings and could hide required unqualified/untested/unimplemented rows behind a structural-success summary. | Nonblank certificate validation plus separate required qualification/test/implementation counters added. Source correction reread; auditor independently reran the owner projection regressions successfully. A projected receipt applies to delivered projection, not omitted raw coverage bytes. |
| IA-021 | Codex/private JSON-line readers decoded malformed UTF-8 with replacement characters before validating evidence. | Both boundaries now use fatal UTF-8 decoding; source reread and independent rerun of both malformed-byte negative tests passed. This hardens protocol parsing, not model-host qualification. |
| IA-022 | Initial project impact stripped file memberships from its structural digest and compared only snapshot bytes. An unchanged file reassigned between source sets/compilations could produce no affected obligations. | Source correction reread: retain source/resource/compilation/configuration/generator path memberships while neutralizing content bytes for structural comparison. Accepted-ingress adversarial execution is pending; model membership cannot be inferred from byte equality. |
| IA-023 | Initial model import checked that a dependency producer existed but not its module/variant ownership; newly added associated-compilation lists and generator properties were not deeply frozen. Unknown gaps in unselected modules could also be filtered out of graph checks. | Source corrections reread: actual producer owner/variant and associated references validated, nested collections frozen, all relevant unknown graph gaps retained. Accepted-ingress regressions pending. Complete model metadata is neither source/API semantics nor completed affected-consumer analysis. |

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

The reviewed composition imports one owner-declared compilation, marks it
`fullProject=false`, and holds CONFIG → SNAPSHOT at source checks while declaring
`authoritative=false` with explicit model/live-worktree reasons. This appropriately
prevents a partial preview from authorizing a full-project final action. It does
not yet establish complete Gradle/KMP discovery, generated/model inputs, affected
consumers or clean/incremental equivalence. Independently certified execution of
all 1,663 requirements, required platform matrix, precision and latency remain open.

Kernel tests demonstrate mechanical computation sharing; the current CLI allocates
a fresh materialization path and binds each preparation to one client. Since the
path participates in its conservative key, cross-client **CLI-level** deduplication
has not been demonstrated. Real host receipts, model inclusion and guarded final
delivery must not be inferred from synthetic kernel/source/receipt fixtures.

## New project-model/impact boundary under review

The separate, not-yet-executed model importer uses a deny-default, exact single-use
owner permit. It binds namespace, snapshot, configuration, exporter and actual
execution, checks anchored file/artifact bytes, repeats trusted origin checks and
returns nonserialized verified capabilities. Caller-controlled JSON flags do not
grant authority. The Gradle exporter runs arbitrary project configuration only
under explicit trusted execution consent; it is not a sandbox.

The impact planner uses both old and new membership/dependency/refinement graphs,
widens on unknowns or identity/configuration changes, and keeps removed consumers
as `removed_unresolved` rather than silently retiring them. The importer/impact
source corrections in IA-022/023 need actual accepted-ingress tests and real Gradle
exports. In particular, a merely coherent **narrow** capture must not become a
complete project model because omitted directories look empty to the exporter.
Authority must establish complete relevant model inputs, not just unchanged bytes.
Nor may one modeled compilation's `fullProject` value substitute for durable
obligations across every affected compilation. Atomic parent-plan/vector-fence
integration is being implemented separately and is not covered by earlier kernel
passes. No clean/incremental semantic-equivalence result is claimed.

## Release conclusion

No implementation/release certification is made by this in-progress document.
Missing provider/target/host integration, catalog breadth, adversarial conformance
and precision/latency evidence must not be silently replaced with a smaller claim.

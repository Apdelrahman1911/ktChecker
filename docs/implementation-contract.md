# Implementation acceptance addendum

Normative additions to the [baseline plan](PLAN.md), dated 2026-11-04. This is
an implementation contract, **not evidence that a release gate has passed**.
The original 1,663 catalog records, including justified exclusions and aliases,
remain intact. The first-release scope has not been reduced to a preview subset.

## Concurrent local-first operation

Every operation binds an authenticated OS-user owner, client, session, workspace,
worktree, lineage, patch, immutable snapshot and review. Independent worktrees or
immutable agent overlays are preferred. A shared mutable worktree requires an
explicit cooperative edit/capture lease, fencing revision and conflicts. Ordinary
watcher observations or repeated hashes cannot prove a coherent capture; they must
remain inexact. Undeclared/uncooperative writers invalidate strict live-source
guarantees. Same-UID unrestricted processes are not an adversarial security boundary.

Capture/head-commit locks are short and never span analysis or builds. Providers
have isolated mutable sessions and output directories. Analysis inputs include
actual model, dependency, configuration, provider and toolchain identities; unknown
identities prevent proof-based reuse. A manifest must retain partial-scope and
unknown-input limitations through all projections. A caller's `fullProject` flag
cannot upgrade a partial capture or unqualified provider to full-project success.

Durable submissions are idempotent within their authenticated client scope. Every
accepted patch retains its review obligations across disconnects, cancellations,
crashes and supersession. Work can be shared only for proven equivalent immutable
inputs. Cancellation withdraws a subscriber, not another subscriber's computation
or the lineage's required review. Fair queues and admission limits apply across
clients and projects. Shared-API impact must schedule affected consumers; unknown
impact requires conservative full affected-compilation analysis.

Event cursors, delivery receipts and model-request observations belong to each
consumer independently. One client's acknowledgment never consumes another's
obligation. Stale findings remain auditable and cannot resolve open findings on a
newer snapshot. Task completion is scoped to the declared lineage and coverage;
integration requires a **new review of the combined immutable snapshot**. The final
completion operation consumes a finite event frontier and checks source, policy,
coverage and model-observation obligations under a consistent fence. A check made
before releasing a lock is not permission to perform a later unfenced final action.

## Suppressions and warning controls

Review is read-only. Only explicit owner action or a previously authorized policy
may mutate suppression state; untrusted source comments and model arguments do not
grant this authority. Support finding, verified symbol/range, file, module/source
set, target and project scopes where the diagnostic supports them. Controls retain
reason (`accepted-risk` or `reported-false-positive`), author, creation time,
optional expiration and an audit trail. Shared policy and local overlays have
documented precedence; atomic compare-and-swap revisions reject conflicting edits
without lost updates. Default broker storage is outside reviewed source; explicit
import/export is required for version-controlled shared policy.

Stable rule IDs and canonical aliases are required. A fingerprint alone is not
proof that materially changed code is the same finding. Symbol movement/renames
need trusted identity evidence; otherwise narrow controls fail closed. Distinguish
accepted, suppressed, disabled, baselined, fixed, unavailable and failed. Controls
may change blocking policy, never erase execution failures or convert failed
compilation/builds into successful checks. Active reviews retain their captured
configuration; new policy invalidates a final gate and requires re-evaluation.

## Coverage and platform evidence

The machine-readable coverage ledger reports every included catalog rule and its
applicability context, including advisory and opt-in activation. Mapping an upstream
ID does not establish implementation, execution, testing or qualification. Evidence
must bind fixtures/results, provider and toolchain tuple, target/source set and
tested commit. Passing upstream fixtures is insufficient to qualify ktChecker's
integration. No proposed scope reduction is accepted without an explicit owner
decision; unresolved implementation/validation is a visible gap, not an exclusion.

Checker portability is tested on Linux, Windows and macOS. Separate hosted suites
exercise Android, Desktop/JVM and macOS/Xcode Native/iOS compilation, linking and
simulator tests, recording actual tools, runner image, architecture and commit.
CI on one commit is not review of every intermediate agent patch. Remote review
evidence can satisfy a review only when inputs and results bind the exact authorized
immutable snapshot. Missing local Apple tooling must be reported as unavailable.

## Required executable histories

| History | Required assertion |
|---|---|
| Independent module edits by multiple clients | Independent receipts and immutable bytes; no cross-client cancellation |
| Shared API change | Affected consumer compilation included; unknown graph expands scope |
| Overlapping overlay/shared-worktree edits | Explicit conflict or fenced disjoint rebase; no silent lost edit |
| Separate worktrees and OS owners | No mutable outputs, credentials, configuration or delivery leakage |
| Duplicate requests, disconnect and restart | Stable receipt; durable obligations; safe replay after recovery |
| Independent ACK/model observation | Each recipient sees its events in a subsequent model request |
| Stale results | Delivered as stale; cannot resolve current findings or pass current gate |
| Policy change during review | Captured policy remains attributable; changed revision invalidates completion |
| Sustained concurrent load | Bounded active work and queue; other admitted clients make progress |
| Incremental versus full | Equivalent normalized findings **and coverage**, or conservative invalidation |
| Combined integration snapshot | Fresh integrated review; partial lineage success is not project success |
| Crash during capture/config/ledger commit | No torn acceptance; recoverable records and explicit durability limits |

Metrics separately report acceptance, first useful result, required completion,
actual context delivery, CPU, RSS, tokens and contention. Warm 1–3 seconds remains a
target until representative measured evidence supports it. Completion notifications,
mock inference and passing a few unit tests do not establish genuine model delivery
or real-world rule precision.

## Executable preview versus release

`ktchecker/preview-1` is an explicit experimental wire contract used to integrate
working modules; it is not a claim that the complete baseline `ktchecker/1`
contract or full launch coverage has shipped. Preview distributions and evidence
must list incomplete/unavailable work. Release completion requires the unchanged
baseline gates plus these concurrency, suppression and hosted-validation gates.

# ktChecker CLI, MCP, and host-delivery contract

Implementation additions: [concurrent clients and experimental preview contract](implementation-contract.md).

**Status: proposed v1 contract. No commands, server, or host integration described here are implemented by this document.** JSON examples are synthetic contract fixtures, not observed runs. “MUST” below is a requirement for a future conforming implementation, not a present capability claim. Research checked **2026-11-04** is in [MCP/host sources](research/mcp-hosts.md); engine identity and snapshot rules are in [architecture](architecture.md).

## 1. Boundaries and non-negotiable invariants

- **One contract, multiple transports:** `ktchecker/1` is the application contract, independent of MCP's protocol revision. CLI, host adapter, MCP bridge, and broker use the same result/finding types. The JVM broker owns scheduling, immutable evidence, job state, and durable delivery records; an MCP process is replaceable transport, not the owner of a background job.
- **Every completed patch:** the integrated host records and submits each completed mutation transaction, including fixes, failed tools with partial writes, additions/deletions/renames, generated inputs, and build/dependency changes. A model instruction to “remember to review” is not an implementation. Watcher debounce cannot silently discard intermediate patches.
- **One immutable analysis identity:** a finding always names the exact worktree, patch, byte snapshot, project model, toolchain, rule policy, and analysis. `modelId` below means the imported **project compilation model**, not an LLM. No runtime model/API dependency is required.
- **Three separate outcomes:** successful RPC execution, complete analysis coverage, and model-visible delivery are different facts. MCP `resultType: "complete"`, task `completed`, `isError: false`, CLI submit exit 0, or a receipt of a job ID is **not** a clean-review verdict.
- **Unknown is not passed:** missing SDKs, unsupported targets/plugins, incomplete project import, untrusted build execution, timeouts, cancelled work, stale snapshots, and missing host delivery are explicit states. Findings discovered during an incomplete run remain useful; empty findings during an incomplete run prove nothing.
- **No notification-as-wakeup fiction:** the host adapter polls outside the model, owns a durable inbox, injects real context at a supported checkpoint, and schedules an owner-authorized turn if necessary. A generic MCP host without these boundaries gets `automaticDelivery: unavailable`, not the strict guarantee.

## 2. Wire profiles and negotiation

### Candidate implementation pin

Primary bridge: official TypeScript SDK **2.4.0**, ordinary tools on published MCP **2026-07-28**, with explicit **2025-11-25** fallback. Use the SDK serving entry points and legacy routing rather than hand-writing era detection. The observed package requires Node >=20; a release must pin a supported Node runtime and lockfile. A Kotlin-only bridge using official SDK **0.16.0** is an optional **legacy** alternative: its supported constants do not include 2026-07-28. Discovery/task types alone are not a newer runtime. [Research §1](research/mcp-hosts.md#1-published-revision-and-sdk-evidence)

The bridge MUST advertise only its tested revisions and implemented capabilities. Baseline capabilities are tools; no Tasks, sampling, elicitation, or background notification capability is needed for correctness. Optional read-only resources can expose immutable reports but do not replace `review.events` or context injection. No launch support is promised for the deprecated standalone HTTP+SSE transport.

| Profile | Required behavior |
| --- | --- |
| **2026-07-28** | Stateless self-contained requests. Include `io.modelcontextprotocol/protocolVersion`, `clientInfo`, and `clientCapabilities` under request `_meta` using their fully qualified keys. Implement `server/discover`; do not require `initialize`. Normal results have `resultType: "complete"`. Streamable HTTP uses matching protocol/body metadata plus required `Mcp-Method` and operation-specific `Mcp-Name` headers. |
| **2025-11-25** | `initialize` version/capability negotiation, then `notifications/initialized`, then tool calls. Do not emit modern `resultType` fields or silently return modern extension shapes. Reconnect initializes a new transport session; review/event identity survives separately in the broker. |
| **No common supported profile** | Fail visibly with protocol incompatibility. A version error is not a clean analysis or a reason to substitute unsupported wire semantics. HTTP auth errors/outages are not evidence that the server is legacy. |

The pinned TS client defaults to legacy negotiation. A long-lived ktChecker adapter that wants preference/fallback MUST deliberately configure documented `versionNegotiation: { mode: "auto" }` and its supported revisions, or use a pinned profile after a verified probe. A pin does not fall back. Avoid auto-probing on every short CLI invocation: the SDK's stdio probe can spawn a disposable sibling process. Bridge startup must be side-effect-free except connecting to the already-authorized broker; never submit analysis merely because a probe launched the process. Record negotiated profile and invalidate cached verdicts after server configuration/version changes.

### Private preview Finding-wire admission (unadopted SOURCE)

The private `ktchecker/preview-1` proposal is narrower than the prospective
`ktchecker/1` negotiation examples above/below: the bridge is pinned to SDK
**2.4.0** and admits only MCP **2026-07-28**; legacy MCP is rejected. No other
SDK/revision combination is qualified here. Its exact application wire
profile is **`ktchecker/finding-retrieval-attachments-1`**. This declaration means
that the peer accepts the existing optional `Finding.retrievalAttachments` field,
including nonempty arrays, and retains the complete locator-containing Finding
and diagnostic bytes. It does not change attachment validation or default
omission, nor license stripping a field to appease an old strict reader.

- **Private NDJSON:** every request carries top-level
  `"findingWireProfile":"ktchecker/finding-retrieval-attachments-1"`, alongside
  `id`, `method`, and `params`. The producer rejects absent, malformed or different
  profiles with `incompatible_finding_wire_profile` **before handler dispatch**.
  Every successful response echoes the exact profile alongside `contractVersion`,
  `id`, and `result`. Ordinary error envelopes have no required profile echo and
  remain errors. The TypeScript transport rejects any success without the exact
  echo before resolving its application value. An old strict producer rejects
  the new request key; there is no legacy-success fallback.
- **MCP:** discovery advertises
  `capabilities.experimental["ktchecker/finding-retrieval-attachments"] =
  {"profile":"ktchecker/finding-retrieval-attachments-1"}`. Each of the six tool
  requests must independently include that exact one-field declaration under
  `_meta["io.modelcontextprotocol/clientCapabilities"].experimental`, with the
  pinned protocol version. Discovery/tool listing can remain available without
  admission. Missing, malformed, unknown or different declarations are errors
  before broker invocation, never empty/filtered success. SDK-valid but
  incompatible declarations return tool `isError:true`; invalid MCP envelopes
  may instead be rejected by the SDK. A tool argument cannot grant admission.
- **No remembered grant:** both directions are checked on each operation,
  including the first operation after reconnect. Successful earlier requests,
  client/server names, registration and matching `contractVersion` alone do not
  admit a later request or response. Disconnect/mismatch does not replay a
  mutation. If a response is incompatible, the mutation's remote outcome can be
  unknown; only explicit durable reconciliation may decide it.

The reference-host executable uses this same private transport before its
`KernelBroker`. An incompatible page cannot reach the atomic inbox/cursor commit;
an incompatible receipt/gate result cannot confirm local ACK, observation or
completion. Separate ACKs remain separate operations and may be reconciled
independently; a compatible receipt is not fabricated for an incompatible one.
The profile is **only an outer transport envelope**, never part of the application
result, event payload, raw-response identity, Finding checksum or context digest.
No attachment byte is rewritten and no new model-callable capability/ACK tool is
introduced.

This is compatibility admission, not authentication, source/artifact access,
analysis completion, ACK authority or actual-model delivery evidence. The matching
producer and consumer SOURCE changes must be selected together; all controls are
**UNRUN**. It does not fix the ABI of raw in-process Kotlin callers or direct
readers outside these guarded wire surfaces, which still require coordinated
upgrade. Future profiles require an explicit reviewed change, not wildcard or
minor-version guessing.

### Modern discovery example (stdio framing)

Each JSON object is one UTF-8 JSON-RPC message; there are no console banners on stdout.

```json
{"jsonrpc":"2.0","id":1,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientInfo":{"name":"ktchecker-host-adapter","version":"design-v1"},"io.modelcontextprotocol/clientCapabilities":{}}}}
```

```json
{"jsonrpc":"2.0","id":1,"result":{"resultType":"complete","supportedVersions":["2026-07-28","2025-11-25"],"capabilities":{"tools":{}},"_meta":{"io.modelcontextprotocol/serverInfo":{"name":"ktchecker","version":"design-v1"}}}}
```

Self-reported server/client names are not authentication. The legacy endpoint instead negotiates `protocolVersion: "2025-11-25"` through the initialization exchange. Tool input/output schemas remain the same application schemas in both profiles. Return the application object as `structuredContent`, and its JSON serialization in a text content block for clients that only consume text. Errors with valid tool execution but unavailable analysis return the structured review state, not an empty success string. Invalid JSON-RPC/unknown method/schema failures use the appropriate protocol error; recoverable tool-domain failures use `isError: true` with structured details.

### Tasks: optional future adapters, disabled baseline

Do not synthesize an MCP task by returning a custom job object where the selected protocol expects a `CallToolResult`. Application `reviewId` is not an MCP `taskId`.

The **2025-11-25 experimental** Tasks flow uses receiver `capabilities.tasks.requests.tools.call`, per-tool `execution.taskSupport`, client task augmentation, `tasks/get`, `tasks/result`, and its legacy metadata/cancellation rules. The **2026-07-28 extension** uses `capabilities.extensions["io.modelcontextprotocol/tasks"]`, server-directed `resultType: "task"`, `tasks/get` with terminal results, and `tasks/update`; cancellation is cooperative. Their notification names, retention fields, and mapping of tool `isError` differ. The source-verified comparison and current SDK limitations are in [research](research/mcp-hosts.md#do-not-mix-the-two-tasks-generations).

A future adapter may wrap a **review-to-terminal-result** operation in the matching Tasks lifecycle; task completion must not mean merely “submission accepted.” Keep `review.submit` a fast durable submission operation to avoid two conflicting meanings. Native task support needs its own conformance matrix, persisted task-to-review mapping, auth/TTL/reconnect tests, and host proof. **Fallback is always bounded ordinary calls plus the same durable application poller**, never an unbounded tool call or a model asked to poll manually.

## 3. Identity, states, and completeness

### Identity and capture

The wire identity is:

```text
workspaceId + worktreeId + patchId + sourceSnapshotId
  + modelId + toolchainId + policyDigest + analysisId
```

IDs are opaque; digests use a versioned canonical descriptor and SHA-256 (`sha256:` followed by 64 lowercase hex digits). Store the descriptors as well as hashes. Hash raw bytes and relevant metadata, not Git HEAD, modification time, file length, or a textual diff alone. `sourceSnapshotId` binds a full manifest with explicit deletions, approved roots, exclusions, and capture provenance. Model/toolchain/policy identity covers the effective target/source-set graph, ordered compiler options/plugins, dependencies/generated-output contents and provenance, platform SDKs, engine/provider versions, baseline and rule configuration.

The submitter supplies a **broker-issued immutable manifest reference**, not arbitrary file URLs. The broker validates ownership, digest, capture proof, and complete blob availability before acceptance. Strict capture requires producer-owned immutable editor bytes or all relevant writers holding the snapshot lease until publication. Dirty/untracked files and relevant ignored/generated inputs must be included; .gitignore is not an exclusion policy. An unsaved buffer must either be saved/captured coherently or explicitly outside scope, preventing a claim that it was checked.

`modelId`, `toolchainId`, and `analysisId` may initially be `null` while resolving the analysis plan. They resolve once; no finding can be published with an unresolved identity. Checks independent of a project model use an explicit versioned model descriptor for that scope, not an invented full semantic model. A changed resolved plan requires a new analysis identity/review. Reusing identical content-addressed analysis may satisfy multiple patch records only with recorded equivalence; it does not erase any patch.

### Review state

| `state` | Meaning | May final gate pass? |
| --- | --- | --- |
| `pending` | Accepted durably; queued or resolving plan. | No |
| `running` | At least one check running; partial evidence may exist. | No |
| `complete` | Every required check/target/depth has an executed or validated-reuse certificate, or positive not-applicable proof; coherent analysis identity. Findings may still fail policy. | Only with current identity, acceptable findings, all patch obligations and required delivery satisfied |
| `incomplete` | Some useful required coverage exists, but required scope is missing, timed out, unreliable, or truncated. | No |
| `unavailable` | Required review cannot start/provide usable coverage, e.g. no supported project model/SDK or denied execution. | No |
| `failed` | Broker/provider execution failure; report captured evidence and failure reason. A compiler reporting source errors is normally **findings**, not this state. | No |
| `cancelled` | Application cancellation reached a terminal state; unfinished required checks remain explicitly uncovered. | No |

Terminal review states never transition back to running; retry creates a new review linked by `retryOf`. A separate `cancelRequested` flag records cooperative stop intent while running. Every accepted job has a server-controlled absolute deadline. A worker crash/expired lease must lead to recoverable retry or explicit failed/incomplete state, never abandoned eternal `running`.

Coverage is per `(checkId, targetId, compilationId, scopeId, depth)` with `required`, state, reason, and evidence IDs. `scopeId` resolves to an immutable scope descriptor (files/symbols/graph extent and exclusions) under the analysis identity; `compilationId` distinguishes source set, main/test, target, and variant. A file-local result cannot satisfy whole-compilation coverage. `checkId` resolves to a versioned rule/provider in the frozen policy. Depth values match the rule registry: `syntax`, `semantic`, `graph`, `build`, `runtime`. Compiler diagnostics and tests are evidence/verification kinds, not additional depth values. States are `pending`, `running`, `passed`, `findings`, `not_applicable`, `incomplete`, `unavailable`, `failed`, `cancelled`. Unsupported is `unavailable` plus a reason code, **not** `not_applicable` or `passed`. `not_applicable` requires positive applicability evidence. A zero-finding compiler check that never compiled is not passed.

`coveragePlanId` binds the frozen complete obligation inventory; an unknown inventory cannot be complete. Each row's `provenance` says `executed`, `reusedWithProof`, or `notExecuted`. Completed rows require a certificate. Reuse additionally references its source analysis and validated dependency-input equivalence proof; cached findings alone are not reusable coverage. An executed failed attempt may have no completion certificate. These distinctions remain machine-readable, not just prose in a summary.

`outcome` is `unknown`, `clean`, or `findings`: `clean` is legal only with `state: complete`, zero active unsuppressed findings, and no missing required coverage. It means clean **for that declared scope**, not proof of all program behavior. `findingCount` counts active findings; `policyAccounting` separately reports suppressed/baselined/waived findings and excluded scopes. Suppression is not proof of correctness. `freshness` is separately `current`, `stale`, or `unverified`, evaluated against `currentSourceSnapshotId` at `freshnessComparedAt`; `current` requires a coherent equal byte snapshot, and the gate separately compares full model/toolchain/policy identity. Historical evidence is not deleted when it becomes stale.

## 4. Application operations

MCP tool names use dots (`review.submit` etc.); CLI subcommands map to the same methods. Operation schemas are in §9. Wire timestamps are RFC3339 UTC; timeout calculations use monotonic elapsed time. Server limits are returned by `doctor` and are not inferred from host defaults.

| Operation | Input / output | Required semantics |
| --- | --- | --- |
| `review.submit` | `Submit` → `Review` | Validate/seal reference, register patch sequence, persist obligation+job+idempotency record before acknowledgement. `mode: foreground` waits up to `waitMs`; background uses zero. Fast completion returns evidence/coverage immediately; otherwise return pending/running with polling hint. Same key+same canonical input returns the same review; same key+different input is `idempotency_conflict`. |
| `review.status` | `ReadReview` → `Review` | Read durable state, optionally long-poll up to `waitMs <= 10000`. All findings/coverage correspond to the stated revision. Does not acknowledge delivery. |
| `review.events` | `ReadEvents` → `EventPage` | Authorized consumer inbox feed; opaque cursor, limit, optional bounded wait. At-least-once records with stable IDs and per-stream increasing sequence. Event stream includes accepted, coverage, findings, terminal, and supersession records. Reading is not ACK. |
| `review.finding` | `ReadFinding` → `Finding` | Retrieve one immutable finding revision with source evidence, diagnosis, fix direction, and verification plan. No arbitrary artifact paths or code execution. |
| `review.ack` | `Ack` → `AckResult` | **Privileged adapter-only** operation. Acknowledge enumerated event revisions only after authenticated model-context insertion proof is recorded. Idempotent; rejects unknown/cross-consumer event IDs or mismatched payload digests. Not proof a finding was fixed. |
| `review.observe` | `Observe` → `ObserveResult` | **Privileged adapter-only** observation receipt: persist proof that an actual model request included the acknowledged context items, or a tested atomic inject-and-continue equivalent completed before the guarded action. Idempotent by observation ID; insertion ACK alone is insufficient. |
| `review.cancel` | `Cancel` → `CancelResult` | Persist cancellation intent idempotently; stop owned worker process groups cooperatively/escalate per configured bound. Response distinguishes intent from terminal stop. Already-terminal reviews remain unchanged. Does not delete findings, ACK events, or satisfy coverage obligations. |

The daemon additionally provides `doctor`, snapshot capture/registration, adapter registration, and `gate` to the trusted CLI/host. They are control-plane operations, not arbitrary model-facing tools. The coding model's `tools/list` MUST NOT expose `review.ack`, `review.observe`, or privileged registration unless that connection is an authorized delivery adapter with verifiable receipt authority. Merely possessing a review ID grants no access. An `Ack`/`Observe` JSON pasted by the model cannot create a valid receipt.

### Foreground usefulness, not just foreground acceptance

`waitMs` is a transport-response budget (design cap 10 seconds), not the analysis deadline. If analysis cannot finish within it, return a truthful pending/running object with missing checks, partial findings, and `nextPollAfterMs`. A host/CLI foreground `review run` loop continues **outside the model** until terminal result or its explicit deadline, then inserts the actual diagnosis/coverage summary before allowing a “review complete” claim. Returning only `reviewId` to a small model and expecting it to infer next steps does not pass the foreground contract.

Application failures have stable reason codes (`project_model_unavailable`, `unsupported_target`, `sdk_missing`, `permission_denied`, `snapshot_not_coherent`, `stale_snapshot`, `deadline_exceeded`, `worker_crashed`, `queue_full`, `delivery_unavailable`, `delivery_history_lost`, `idempotency_conflict`). Include a concise explanation, retryability, and suggested owner action; never use an empty findings array as an error signal. Detail log references are private opaque artifacts, not terminal transcripts embedded in model context.

### Private preview extension: policy reassessment (unadopted)

This separate extension describes **implemented-in-private, unadopted SOURCE**,
not an installed capability or a change to the proposed `ktchecker/1` schemas and
review-state table above. It follows the private kernel, Workbench and CLI SOURCE;
TypeScript MCP/reference-host wiring is pending. Its [CLI forms and exit values](implementation-usage.md#private-policy-reassessment-cli-unadopted)
do **not** inherit the future commands, stdin convention or verdict exits in §7.

| Private application RPC | Exact parameters | Result |
| --- | --- | --- |
| `review.policy.reassess` | `credential`, `request` | Publish or replay one immutable historical policy view. |
| `review.policy.status` | `credential`, `idempotencyKey` | Read that authenticated client's retained view; no reclassification. |

`request` is a closed object with exactly `reviewId`, `idempotencyKey`,
`generation` and `expectedPolicyDigest`. `generation` is the enrolled client
binding's generation, not a reassessment counter. The digest names the expected
current **full** policy; it is not a caller assertion of analysis equivalence.
No input, raw result, old capture, certificate, permit or execution grant is
accepted in this request. Policy changes still require a separate explicit owner
action under the existing mutation authority; this review request is not that
action and grants no configuration, source, execution or completion authority.

The kernel resolves the client's complete, noncancelled ordinary review and its
certified original input, work, raw result and effective result. Full raw coverage
for that original input is required even for an audit-only view. The trusted
owner adapter validates retained preparation and compatible policy captures:
only unchanged effective rule settings and analysis authority qualify. See
[policy compatibility and retention](suppressions.md#private-same-raw-reassessment-unadopted).

Acceptance atomically adds a `PolicyReassessmentView` with
`state: "policy_reassessed"` and one ordinary **delivery-required per-client event**.
Its `sequence` is the new event sequence; `certified.reviewSequence` still names
the original review sequence. Original input, raw/effective results, captured
policy, certificates, review/work identities, history and receipts remain immutable.
Only `open`, `accepted`, `suppressed` or `baselined` finding dispositions can differ
from the raw result in the new view; execution failures remain open. This is
neither a new execution nor a transition of the original review's state.

An exact accepted request replay returns the retained view without a new event.
Reusing its key with changed fields conflicts; a new key targeting the preceding
view's policy digest (or the original digest before any view) is rejected.
Status is historical, not a currentness or archive-recovery certificate. Its
success, or merely receiving the reassess response, does not ACK or prove model
observation. The new event is an **additional historical delivery obligation**;
old required events/receipts are not replaced, including after later policy edits.
One client's receipt never satisfies another client's obligation.

Only a validated view of the latest ordinary review, with the entire original
input unchanged, can participate in current-policy accounting. It carries the
original coverage, not new execution, recency, absence or fixed-finding evidence.
Full-project scope, current policy/source/analysis authority, complete history,
coverage, delivery and guarded-action fences remain mandatory even when
`auditOnly=false`. Ordinary Workbench is partial, nonauthoritative and always
**audit-only**. Plan/routed/produced reassessment and integrated completion using
these views are unsupported. A status result, `policy_reassessed`, or CLI exit 0
is not full-project success. No runtime or host-delivery validation is claimed.

## 5. Events, replay, acknowledgement, and stale results

### Durability and ordering

Broker transaction: persist result/finding revision and its outbox event atomically. Event IDs are stable opaque random identifiers; `streamId` and `sequence` provide ordering within an owner/worktree consumer stream. They are not global clocks. Concurrent analyses may finish out of patch order. Events carry a `payloadDigest`; never overwrite an acknowledged revision in place.

The adapter keeps two different cursors:

1. **Fetch cursor:** advances only after returned event bytes are durably committed to the local inbox. After reconnect, resume from it; repeated events are allowed.
2. **Context receipt set:** event IDs/revisions confirmed inserted into the correct host's model-visible history. Only these can be ACKed. Do not use a single “last received” cursor as delivery acknowledgement.

`review.events` returning no entries means no new entries in that page, not a clean review. Each event has broker-set `deliveryRequired`: findings and terminal coverage are required; routine acceptance/progress may be observational. Only observational events may be compacted without context insertion when a newer durable state supersedes them. The server retains required unacknowledged events/obligations/results under bounded quotas; at capacity it refuses new strict submissions rather than silently dropping undelivered evidence. Retention policy is explicit. If owner-requested expiry or corruption makes required replay impossible, retain a gap/tombstone and return `delivery_history_lost`; strict final success is blocked until documented recovery/review. An expired cursor returns an explicit error, not the beginning/end of an unrelated stream. Recovery reconciles every outstanding patch/review against durable status and event revisions before issuing a new cursor.

The adapter's dedup key is `(consumerId, eventId, eventRevision, payloadDigest)`. Similar messages from different snapshots are not duplicates. A stable finding fingerprint may help group history in the UI but cannot authorize reuse across different analysis IDs. ACK is per bound consumer: delivery to one agent/thread does not acknowledge another.

### Injection and proof

`consumerId` is bound by trusted registration to owner, worktree, host installation, thread, and generation; a fork/new conversation needs a new binding. Reconnect preserves the logical consumer only after authenticating the same binding. Never guess a session from “latest terminal” or a workspace path.

Persist an insertion intent before using the host's supported context API. Insert a bounded block carrying deterministic marker `ktchecker-event:<eventId>:<eventRevision>:<payloadDigest>`, complete identity, evidence, actionable diagnosis, and coverage caveats. Diagnostic text is **untrusted data**, not instructions overriding user policy. Store the actual inserted bytes' hash and receipt after confirmation. Then call `review.ack`.

Context-channel authority differs by host. Codex PostToolUse `additionalContext` is documented as **developer context**, unlike the SDK's lower-priority `ExternalMessage`. A trusted fixed adapter must quote/escape diagnostic/source fields and keep instructions separate; raw source/log text must never become developer directives. Prefer a supported external-data/tool-result channel. An adapter that cannot preserve this distinction is not strict-mode eligible.

Track separately:

```text
outbox_pending → inbox_received → context_injected → model_observed
                                  └→ broker_acknowledged
```

`model_observed` requires a later actual model request containing the inserted item (or a tested atomic inject-and-continue host mechanism that proves equivalent ordering). The adapter records this through `review.observe`, separately from insertion ACK. It is not established by a transport response or merely having an API that could resume the host. A public answer/action referring correctly to a unique event marker provides additional end-to-end usefulness evidence; hidden reasoning is neither needed nor collected. ACK proves insertion, not model observation, comprehension, acceptance of a fix, or semantic resolution.

There is no atomic transaction across arbitrary host history and the broker. Crash between insertion and ACK requires reconciliation by context marker/receipt; otherwise bounded at-least-once duplicate context is safer than loss. If a host cannot expose or reconcile insertion receipts, record `injection_unconfirmed` and keep the strict delivery gate closed. Do not silently upgrade hook stdout or process exit into `context_injected`.

### Freshness and final barrier

Before injection or applying a suggested edit, compare the finding identity with the current coherent snapshot. Historical findings retain exact old source ranges, are labelled `stale`, and cannot be applied blindly to new bytes. Queue/reuse a valid review of the new snapshot. A rename mapping is a UI hint unless separately verified.

The final barrier acquires the writer fence, seals the latest snapshot, and checks:

1. Every recorded completed patch sequence is accounted for; no accepted obligations were silently skipped/coalesced/cancelled away.
2. The latest analysis identity exactly matches current bytes, model/toolchain, and policy.
3. Every required coverage row is passed/findings/proven-not-applicable; no unavailable/incomplete required target.
4. Findings meet configured policy; waivers/suppressions are explicit and auditable, not inferred from delivery/age.
5. Required diagnostic/coverage events have authenticated context receipts **and `model_observed` proof**: an actual subsequent model request contained the items, or tested atomic inject-and-continue evidence guarantees their inclusion before the guarded mutation/final action. A capability, future opportunity, `thread/inject_items` response, or insertion ACK alone cannot pass. Missing observation proof is `delivery_unavailable`/exit 7, even if the broker analysis is complete.

Use a **finite gate cut**, not a self-generating observation loop: after required review work settles, persist `(consumer generation, streamId, throughSequence, patchSequence, sourceSnapshotId, analysisId, policyDigest, coveragePlanId, coverageRevision)`. All delivery-required review events through that frontier must be observed. ACKs, observation receipts, gate evaluation/summary, and transport bookkeeping are control traffic and **must not create new delivery-required events**. After observation, recheck the cut's identities/epochs and that no newly relevant required review event appeared before releasing the fence. A new mutation, policy/model change, or required review event invalidates the cut and restarts it. A successful model request cannot be inferred from a queued-but-unsent request or a host turn-start event that failed before model invocation.

If new writes occur, restart the barrier for a new patch. If the deadline expires, return incomplete with the outstanding reviews/coverage/delivery reasons. An owner may explicitly stop/waive an obligation, but the ledger records the waiver and the outcome is not mislabeled a clean pass. Stop hooks are helpers; a crash/fail-open hook must not bypass a runtime-controlled commit/final-success gate.

## 6. Host sequences

### Foreground

```text
host executes a completed mutation under a writer lease
  → save/capture immutable bytes and record patch sequence
  → durable review.submit(mode=foreground, waitMs within tool timeout)
  → if pending: adapter polls status/events without model requests
  → complete/incomplete/unavailable result is inserted as actual tool context
  → record insertion receipt and ACK eligible events; retain guarded-action fences
  → release only scheduling needed for the next model invocation with that context
  → record actual model-request inclusion through privileged review.observe
  → release only actions allowed by their policy; final success still requires §5
  → if a fix is made, the exact same submission path runs again
```

Foreground policy may hold the next mutation until the configured required review finishes and its result is observed. A guarded mutation/final-action fence must not prevent the model invocation needed to observe the diagnosis, but insertion ACK alone cannot release that action fence. Repair authorization is separate from final-success eligibility: a blocking diagnosis can be observed and repaired without pretending the review passed. Terminal incomplete/unavailable output is a useful truthful result, not permission to claim the patch passed.

### Background

```text
completed patch P1
  → journal + coherent snapshot + durable submit before next mutation is released
  → model continues allowed independent work; analysis runs in broker
  → out-of-model worker polls and persists inbox (no model polling)
  → at the earliest supported pre-model/safe-point checkpoint: insert ready context
  → if idle: use owner-enabled host start/resume/native wake, or remain pending
  → ACK only after insertion proof; schedule an eligible next model request
  → record actual inclusion before an action whose policy requires observation
  → model repairs finding → completed patch P2 → automatic submission again
  → final barrier waits on current coverage + all patch/delivery obligations
```

Background strict mode need not block all subsequent editing while P1 is running; it MUST fence durable acceptance/capture, enforce configured outstanding-work bounds, drain ready findings at the next supported checkpoint, and hold the final barrier. A result arriving during an active model request cannot change that request's already-sent context or influence an already-sampled tool action. If policy requires observation before that action, defer the action, invoke the model with the inserted context, and record observation before reconsidering authorization; merely inserting context at a pre-mutation hook is insufficient.

Capabilities are explicit: `strict_foreground`, `strict_background`, `cooperative`, or `manual`. A host qualifies for strict modes only after mutation capture, out-of-model polling, real context insertion, session recovery, and final-gate behavior are tested. A capable MCP client without an adapter remains cooperative/manual. Missing automatic turn activation is exposed as `waiting_for_host_turn`/`delivery_unavailable`, never a silent success. Concrete Codex, Claude Code, and VS Code options and their source-supported limitations are in [the host matrix](research/mcp-hosts.md#host-capability-matrix).

## 7. CLI behavior

These are proposed commands, not installation instructions for an existing binary.

```sh
ktchecker doctor --format json
ktchecker snapshot capture --workspace-id ws-demo --patch-id patch-1 --format json
ktchecker review submit --request request.json --format json
ktchecker review run --request request.json --deadline 120s --format ndjson
ktchecker review status review-demo --wait-ms 2000 --format json
ktchecker review events --consumer-id consumer-demo --cursor-file cursor.json --follow --format ndjson
ktchecker review finding finding-demo --revision 1 --format json
ktchecker review ack --receipt receipt.json --format json
ktchecker review observe --observation observation.json --format json
ktchecker review cancel review-demo --reason owner-requested --format json
ktchecker gate --workspace-id ws-demo --require-current --require-delivery --deadline 120s --format json
ktchecker serve --stdio
```

`snapshot capture` succeeds in strict mode only with a validated producer snapshot/lease; a command name is not proof of writer exclusion. Registration/secrets are provided through the trusted local control channel, never a prompt or command-line token. Commands accept request JSON on stdin with `--request -`. Mutating control commands require explicit ownership and validated IDs.

- `--format json`: stdout is exactly one validated object plus newline. `--format ndjson`: each line is one typed/versioned envelope/event; a bounded run ends with one terminal result or a truthful wait/deadline result. Streaming events can reconnect/repeat; consumers deduplicate.
- Human status, logs, timings, progress bars, and stack traces go to stderr. No ANSI codes or mixed prose on machine stdout. `serve --stdio` emits **only MCP frames**, not CLI NDJSON/status output.
- `submit`/read/ACK/observe/cancel exit 0 means that operation succeeded, not that review passed. Only `review run` and `gate` use verdict exits below. Always inspect `state`, `outcome`, coverage, identity, and delivery.
- Bounded ordinary tool calls use `waitMs <= 10000`; analysis has a separate accepted timeout/deadline. CLI polling uses monotonic deadlines and server hints with bounded jitter/backoff; no per-poll model invocation. Background submit returns promptly **after durability**, not before.
- SIGINT while waiting detaches by default; it does not silently cancel a broker job. Explicit `review cancel` or an explicit `--cancel-on-interrupt` policy requests cancellation. Broken stdout pipe disconnects observation, not analysis, and cannot ACK undelivered data. SIGKILL/restart is handled by the durable journal/lease policy.

| Exit | `review run` / `gate` meaning |
| --- | --- |
| `0` | Complete declared coverage, current identity, acceptable findings, and all requested delivery/ledger gates satisfied |
| `1` | Complete review has policy-blocking findings |
| `2` | Invalid input/configuration/schema or incompatible contract/protocol |
| `3` | Pending at wait deadline, incomplete/unavailable required coverage, stale/unverified capture, or unmet patch ledger |
| `4` | Internal broker/provider failure |
| `5` | Command interrupted or explicitly cancelled (job status reported separately) |
| `6` | Authentication/authorization failure |
| `7` | Required host context delivery/activation is unavailable or unconfirmed |

When multiple conditions apply, output all reasons and choose precedence `6 > 2 > 4 > 5 > 7 > 3 > 1 > 0`. A watchdog may terminate an unhealthy process; absence of output is failure, never exit-0-equivalent. `doctor` reports support as unavailable until actually probed, including target/toolchain and host features; it does not silently authorize Gradle/plugin execution.

## 8. Finding quality and context budgets

Every finding includes rule/category, `kind`, severity, certainty **and evidence kind**, exact source byte digest and range, diagnosis, why it matters, cautious fix direction, verification operation, target/compilation/scope/depth, and version identity. `kind` matches the registry: `correctness`, `probable_defect`, `policy`, or `style`. `certainty` also matches it: `high`, `medium`, `low`, or `intent_dependent`; `category` preserves the registered family string. These are independent of severity. Runtime evidence may lower a rule's expected confidence; a registry `high` heuristic must not become a proven defect merely through normalization. Certainty qualifies the stated claim under that snapshot/target; a compiler diagnosis is not a universal runtime theorem, and intent-dependent advice remains labelled as such.

Positions are **1-based lines, 0-based UTF-16 columns, end-exclusive**; files retain raw-byte digests. CRLF, supplementary Unicode, generated sources, and deleted files need tested mappings. Related locations and evidence artifacts are explicit. For fileless errors, `location` is null and the target/configuration diagnostic explains the scope instead of inventing a source range.

The compact model-facing block includes event marker, identity, current/stale status, severity/certainty, one concise diagnosis, a source excerpt/range where available, fix direction, verification, and missing coverage. Deterministic templates—not an LLM summarizer—generate this. Dedup and ranking can reduce repetition, but mandatory blocking summaries and incompleteness cannot disappear under token limits. Include `findingCount`, page cursor, omitted counts, and stable detail IDs. The adapter retrieves required details before a repair checkpoint; it must not rely on a small model discovering a resource URI on its own.

Verification is a structured reference to a configured check/target/compilation (or an owner-approved test), not an arbitrary shell command from diagnostic text. ktChecker **does not apply fixes**. External coding agents/editors own all mutations and must respect their permission policy and preimage hash checks. All applied fixes produce new patch IDs and new checks. ACK/“seen”/stale/superseded is never “resolved”; resolution needs new evidence or an explicit policy waiver.

## 9. Schemas and synthetic examples

The following JSON Schema 2020-12 bundle defines the proposed application shapes. A method's `inputSchema`/`outputSchema` resolves the corresponding local `$defs` and includes the referenced definitions. It is not an MCP envelope schema. `additionalProperties: false` prevents accidental field drift; compatible extensions require an explicit contract minor-version policy before rollout. Implementations must also enforce the semantic invariants above (cross-field identity, authenticated receipts, completeness, and ledger ordering are not fully expressible in JSON Schema).

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "urn:ktchecker:contract:1",
  "$defs": {
    "Id": {"type":"string","minLength":1,"maxLength":256},
    "Digest": {"type":"string","pattern":"^sha256:[0-9a-f]{64}$"},
    "Time": {"type":"string","format":"date-time"},
    "Reason": {
      "type": "object",
      "additionalProperties": false,
      "required": ["code","message","retryable","ownerAction"],
      "properties": {"code":{"$ref":"#/$defs/Id"},"message":{"type":"string","maxLength":2000},"retryable":{"type":"boolean"},"ownerAction":{"type":["string","null"],"maxLength":2000}}
    },
    "Identity": {
      "type": "object",
      "additionalProperties": false,
      "required": ["workspaceId","worktreeId","patchId","sourceSnapshotId","modelId","toolchainId","policyDigest","analysisId"],
      "properties": {
        "workspaceId": {"$ref":"#/$defs/Id"},
        "worktreeId": {"$ref":"#/$defs/Id"},
        "patchId": {"$ref":"#/$defs/Id"},
        "sourceSnapshotId": {"$ref":"#/$defs/Digest"},
        "modelId": {"anyOf":[{"$ref":"#/$defs/Id"},{"type":"null"}]},
        "toolchainId": {"anyOf":[{"$ref":"#/$defs/Id"},{"type":"null"}]},
        "policyDigest": {"$ref":"#/$defs/Digest"},
        "analysisId": {"anyOf":[{"$ref":"#/$defs/Digest"},{"type":"null"}]}
      }
    },
    "Submit": {
      "type": "object",
      "additionalProperties": false,
      "required": ["contractVersion","workspaceId","worktreeId","patchId","patchSequence","predecessorPatchId","sourceSnapshotId","manifestRef","policyDigest","idempotencyKey","mode","waitMs","analysisTimeoutMs"],
      "properties": {
        "contractVersion": {"const":"ktchecker/1"},
        "workspaceId": {"$ref":"#/$defs/Id"},
        "worktreeId": {"$ref":"#/$defs/Id"},
        "patchId": {"$ref":"#/$defs/Id"},
        "patchSequence": {"type":"integer","minimum":1},
        "predecessorPatchId": {"anyOf":[{"$ref":"#/$defs/Id"},{"type":"null"}]},
        "sourceSnapshotId": {"$ref":"#/$defs/Digest"},
        "manifestRef": {"$ref":"#/$defs/Id"},
        "policyDigest": {"$ref":"#/$defs/Digest"},
        "idempotencyKey": {"$ref":"#/$defs/Id"},
        "mode": {"enum":["foreground","background"]},
        "waitMs": {"type":"integer","minimum":0,"maximum":10000},
        "analysisTimeoutMs": {"type":"integer","minimum":1,"maximum":3600000},
        "retryOf": {"$ref":"#/$defs/Id"}
      },
      "allOf": [{"if":{"properties":{"mode":{"const":"background"}}},"then":{"properties":{"waitMs":{"const":0}}}}]
    },
    "Coverage": {
      "type": "object",
      "additionalProperties": false,
      "required": ["checkId","targetId","compilationId","scopeId","depth","required","state","reason","evidenceIds","provenance"],
      "properties": {
        "checkId": {"$ref":"#/$defs/Id"},
        "targetId": {"$ref":"#/$defs/Id"},
        "compilationId": {"$ref":"#/$defs/Id"},
        "scopeId": {"$ref":"#/$defs/Id"},
        "depth": {"enum":["syntax","semantic","graph","build","runtime"]},
        "required": {"type":"boolean"},
        "state": {"enum":["pending","running","passed","findings","not_applicable","incomplete","unavailable","failed","cancelled"]},
        "reason": {"anyOf":[{"$ref":"#/$defs/Reason"},{"type":"null"}]},
        "evidenceIds": {"type":"array","items":{"$ref":"#/$defs/Id"},"uniqueItems":true},
        "provenance": {"$ref":"#/$defs/CoverageProvenance"}
      },
      "allOf": [
        {
          "if": {"properties":{"state":{"enum":["passed","findings","not_applicable"]}}},
          "then": {"properties":{"provenance":{"properties":{"mode":{"enum":["executed","reusedWithProof"]},"certificateId":{"$ref":"#/$defs/Id"}}}}}
        },
        {
          "if": {"properties":{"state":{"enum":["incomplete","unavailable","failed","cancelled","not_applicable"]}}},
          "then": {"properties":{"reason":{"$ref":"#/$defs/Reason"}}}
        }
      ]
    },
    "PolicyAccounting": {
      "type": "object",
      "additionalProperties": false,
      "required": ["suppressedCount","baselinedCount","waivedCount","excludedScopeCount"],
      "properties": {
        "suppressedCount": {"type":"integer","minimum":0},
        "baselinedCount": {"type":"integer","minimum":0},
        "waivedCount": {"type":"integer","minimum":0},
        "excludedScopeCount": {"type":"integer","minimum":0}
      }
    },
    "FindingRef": {
      "type": "object",
      "additionalProperties": false,
      "required": ["findingId","revision"],
      "properties": {"findingId":{"$ref":"#/$defs/Id"},"revision":{"type":"integer","minimum":1}}
    },
    "Review": {
      "type": "object",
      "additionalProperties": false,
      "required": ["contractVersion","reviewId","revision","identity","state","outcome","capture","freshness","currentSourceSnapshotId","freshnessComparedAt","coverage","reasons","policyAccounting","findingCount","findingRefs","findingsCursor","summary","cancelRequested","acceptedAt","updatedAt","deadlineAt","nextPollAfterMs","coveragePlanId"],
      "properties": {
        "contractVersion": {"const":"ktchecker/1"},
        "reviewId": {"$ref":"#/$defs/Id"},
        "revision": {"type":"integer","minimum":1},
        "identity": {"$ref":"#/$defs/Identity"},
        "state": {"enum":["pending","running","complete","incomplete","unavailable","failed","cancelled"]},
        "outcome": {"enum":["unknown","clean","findings"]},
        "capture": {"enum":["coherent","best_effort"]},
        "freshness": {"enum":["current","stale","unverified"]},
        "currentSourceSnapshotId": {"anyOf":[{"$ref":"#/$defs/Digest"},{"type":"null"}]},
        "freshnessComparedAt": {"anyOf":[{"$ref":"#/$defs/Time"},{"type":"null"}]},
        "policyAccounting": {"$ref":"#/$defs/PolicyAccounting"},
        "coverage": {"type":"array","items":{"$ref":"#/$defs/Coverage"}},
        "reasons": {"type":"array","items":{"$ref":"#/$defs/Reason"}},
        "findingCount": {"type":"integer","minimum":0},
        "findingRefs": {"type":"array","items":{"$ref":"#/$defs/FindingRef"},"uniqueItems":true},
        "findingsCursor": {"type":["string","null"]},
        "summary": {"type":"string","maxLength":4000},
        "cancelRequested": {"type":"boolean"},
        "acceptedAt": {"$ref":"#/$defs/Time"},
        "updatedAt": {"$ref":"#/$defs/Time"},
        "deadlineAt": {"$ref":"#/$defs/Time"},
        "nextPollAfterMs": {"type":["integer","null"],"minimum":100,"maximum":30000},
        "coveragePlanId": {"anyOf":[{"$ref":"#/$defs/Digest"},{"type":"null"}]}
      },
      "allOf": [
        {"if":{"properties":{"outcome":{"const":"clean"}}},"then":{"properties":{"state":{"const":"complete"},"findingCount":{"const":0}}}},
        {
          "if": {"properties":{"state":{"const":"complete"}}},
          "then": {
            "properties": {
              "capture": {"const":"coherent"},
              "identity": {"properties":{"modelId":{"$ref":"#/$defs/Id"},"toolchainId":{"$ref":"#/$defs/Id"},"analysisId":{"$ref":"#/$defs/Digest"}}},
              "coveragePlanId": {"$ref":"#/$defs/Digest"},
              "outcome": {"enum":["clean","findings"]},
              "coverage": {"minItems":1,"items":{"if":{"properties":{"required":{"const":true}}},"then":{"properties":{"state":{"enum":["passed","findings","not_applicable"]}}}}}
            }
          }
        },
        {
          "if": {"properties":{"freshness":{"const":"current"}}},
          "then": {"properties":{"currentSourceSnapshotId":{"$ref":"#/$defs/Digest"},"freshnessComparedAt":{"$ref":"#/$defs/Time"}}}
        }
      ]
    },
    "ReadReview": {
      "type": "object",
      "additionalProperties": false,
      "required": ["reviewId","waitMs"],
      "properties": {"reviewId":{"$ref":"#/$defs/Id"},"waitMs":{"type":"integer","minimum":0,"maximum":10000},"findingsCursor":{"type":"string"}}
    },
    "ReadFinding": {
      "type": "object",
      "additionalProperties": false,
      "required": ["findingId","revision"],
      "properties": {"findingId":{"$ref":"#/$defs/Id"},"revision":{"type":"integer","minimum":1}}
    },
    "Position": {
      "type": "object",
      "additionalProperties": false,
      "required": ["line","column"],
      "properties": {"line":{"type":"integer","minimum":1},"column":{"type":"integer","minimum":0}}
    },
    "Location": {
      "type": "object",
      "additionalProperties": false,
      "required": ["path","fileDigest","start","end","excerpt"],
      "properties": {
        "path": {"type":"string","minLength":1,"maxLength":4096},
        "fileDigest": {"$ref":"#/$defs/Digest"},
        "start": {"$ref":"#/$defs/Position"},
        "end": {"$ref":"#/$defs/Position"},
        "excerpt": {"type":"string","maxLength":4000}
      }
    },
    "Evidence": {
      "type": "object",
      "additionalProperties": false,
      "required": ["evidenceId","kind","summary","artifactRef"],
      "properties": {
        "evidenceId": {"$ref":"#/$defs/Id"},
        "kind": {"enum":["compiler_diagnostic","static_rule","test_result","heuristic","applicability_proof"]},
        "summary": {"type":"string","maxLength":2000},
        "artifactRef": {"type":["string","null"]}
      }
    },
    "Verification": {
      "type": "object",
      "additionalProperties": false,
      "required": ["kind","checkId","targetId","compilationId","expected","state"],
      "properties": {
        "kind": {"enum":["recheck","compile","test","owner_review"]},
        "checkId": {"$ref":"#/$defs/Id"},
        "targetId": {"$ref":"#/$defs/Id"},
        "compilationId": {"$ref":"#/$defs/Id"},
        "expected": {"type":"string","maxLength":2000},
        "state": {"enum":["not_run","passed","failed","unavailable"]}
      }
    },
    "Finding": {
      "type": "object",
      "additionalProperties": false,
      "required": ["contractVersion","findingId","revision","reviewId","identity","ruleId","category","severity","certainty","targetId","compilationId","scopeId","depth","location","relatedLocations","diagnosis","whyItMatters","fixHint","verification","evidence","versions","disposition","kind"],
      "properties": {
        "contractVersion": {"const":"ktchecker/1"},
        "findingId": {"$ref":"#/$defs/Id"},
        "revision": {"type":"integer","minimum":1},
        "reviewId": {"$ref":"#/$defs/Id"},
        "identity": {"$ref":"#/$defs/Identity"},
        "ruleId": {"$ref":"#/$defs/Id"},
        "category": {"$ref":"#/$defs/Id"},
        "severity": {"enum":["error","warning","info"]},
        "certainty": {"enum":["high","medium","low","intent_dependent"]},
        "targetId": {"$ref":"#/$defs/Id"},
        "compilationId": {"$ref":"#/$defs/Id"},
        "scopeId": {"$ref":"#/$defs/Id"},
        "depth": {"enum":["syntax","semantic","graph","build","runtime"]},
        "location": {"anyOf":[{"$ref":"#/$defs/Location"},{"type":"null"}]},
        "relatedLocations": {"type":"array","items":{"$ref":"#/$defs/Location"}},
        "diagnosis": {"type":"string","minLength":1,"maxLength":2000},
        "whyItMatters": {"type":"string","minLength":1,"maxLength":2000},
        "fixHint": {"type":"string","minLength":1,"maxLength":2000},
        "verification": {"$ref":"#/$defs/Verification"},
        "evidence": {"type":"array","minItems":1,"items":{"$ref":"#/$defs/Evidence"}},
        "versions": {
          "type": "object",
          "additionalProperties": false,
          "required": ["engine","provider","compiler","rulePack","schema"],
          "properties": {
            "engine": {"$ref":"#/$defs/Id"},
            "provider": {"$ref":"#/$defs/Id"},
            "compiler": {"$ref":"#/$defs/Id"},
            "rulePack": {"$ref":"#/$defs/Id"},
            "schema": {"const":"ktchecker/1"}
          }
        },
        "disposition": {"enum":["active","suppressed","baselined","waived"]},
        "kind": {"enum":["correctness","probable_defect","policy","style"]}
      },
      "allOf": [{"properties":{"identity":{"properties":{"modelId":{"$ref":"#/$defs/Id"},"toolchainId":{"$ref":"#/$defs/Id"},"analysisId":{"$ref":"#/$defs/Digest"}}}}}]
    },
    "ReadEvents": {
      "type": "object",
      "additionalProperties": false,
      "required": ["consumerId","cursor","limit","waitMs"],
      "properties": {
        "consumerId": {"$ref":"#/$defs/Id"},
        "cursor": {"type":["string","null"]},
        "limit": {"type":"integer","minimum":1,"maximum":100},
        "waitMs": {"type":"integer","minimum":0,"maximum":10000}
      }
    },
    "Event": {
      "type": "object",
      "additionalProperties": false,
      "required": ["contractVersion","eventId","eventRevision","streamId","sequence","consumerId","reviewId","identity","type","deliveryRequired","createdAt","payloadDigest","payload"],
      "properties": {
        "contractVersion": {"const":"ktchecker/1"},
        "eventId": {"$ref":"#/$defs/Id"},
        "eventRevision": {"type":"integer","minimum":1},
        "streamId": {"$ref":"#/$defs/Id"},
        "sequence": {"type":"integer","minimum":1},
        "consumerId": {"$ref":"#/$defs/Id"},
        "reviewId": {"$ref":"#/$defs/Id"},
        "identity": {"$ref":"#/$defs/Identity"},
        "type": {"enum":["review.accepted","coverage.updated","finding.published","review.finished","snapshot.superseded"]},
        "deliveryRequired": {"type":"boolean"},
        "createdAt": {"$ref":"#/$defs/Time"},
        "payloadDigest": {"$ref":"#/$defs/Digest"},
        "payload": {"oneOf":[{"$ref":"#/$defs/Review"},{"$ref":"#/$defs/Finding"}]}
      }
    },
    "EventPage": {
      "type": "object",
      "additionalProperties": false,
      "required": ["contractVersion","streamId","events","nextCursor","hasMore","nextPollAfterMs"],
      "properties": {
        "contractVersion": {"const":"ktchecker/1"},
        "streamId": {"$ref":"#/$defs/Id"},
        "events": {"type":"array","maxItems":100,"items":{"$ref":"#/$defs/Event"}},
        "nextCursor": {"type":"string"},
        "hasMore": {"type":"boolean"},
        "nextPollAfterMs": {"type":"integer","minimum":100,"maximum":30000}
      }
    },
    "Receipt": {
      "type": "object",
      "additionalProperties": false,
      "required": ["receiptId","hostKind","hostVersion","threadId","turnId","contextItemId","contextDigest","insertedAt","proofRef"],
      "properties": {
        "receiptId": {"$ref":"#/$defs/Id"},
        "hostKind": {"$ref":"#/$defs/Id"},
        "hostVersion": {"$ref":"#/$defs/Id"},
        "threadId": {"$ref":"#/$defs/Id"},
        "turnId": {"$ref":"#/$defs/Id"},
        "contextItemId": {"$ref":"#/$defs/Id"},
        "contextDigest": {"$ref":"#/$defs/Digest"},
        "insertedAt": {"$ref":"#/$defs/Time"},
        "proofRef": {"$ref":"#/$defs/Id"}
      }
    },
    "AckEntry": {
      "type": "object",
      "additionalProperties": false,
      "required": ["eventId","eventRevision","payloadDigest"],
      "properties": {"eventId":{"$ref":"#/$defs/Id"},"eventRevision":{"type":"integer","minimum":1},"payloadDigest":{"$ref":"#/$defs/Digest"}}
    },
    "Ack": {
      "type": "object",
      "additionalProperties": false,
      "required": ["consumerId","events","receipt"],
      "properties": {
        "consumerId": {"$ref":"#/$defs/Id"},
        "events": {"type":"array","minItems":1,"maxItems":100,"uniqueItems":true,"items":{"$ref":"#/$defs/AckEntry"}},
        "receipt": {"$ref":"#/$defs/Receipt"}
      }
    },
    "AckResult": {
      "type": "object",
      "additionalProperties": false,
      "required": ["contractVersion","consumerId","acknowledged","receiptId"],
      "properties": {
        "contractVersion": {"const":"ktchecker/1"},
        "consumerId": {"$ref":"#/$defs/Id"},
        "acknowledged": {"type":"array","items":{"$ref":"#/$defs/AckEntry"}},
        "receiptId": {"$ref":"#/$defs/Id"}
      }
    },
    "Cancel": {
      "type": "object",
      "additionalProperties": false,
      "required": ["reviewId","idempotencyKey","reason"],
      "properties": {"reviewId":{"$ref":"#/$defs/Id"},"idempotencyKey":{"$ref":"#/$defs/Id"},"reason":{"type":"string","minLength":1,"maxLength":1000}}
    },
    "CancelResult": {
      "type": "object",
      "additionalProperties": false,
      "required": ["contractVersion","reviewId","cancelRequested","terminal","state"],
      "properties": {
        "contractVersion": {"const":"ktchecker/1"},
        "reviewId": {"$ref":"#/$defs/Id"},
        "cancelRequested": {"type":"boolean"},
        "terminal": {"type":"boolean"},
        "state": {"enum":["pending","running","complete","incomplete","unavailable","failed","cancelled"]}
      }
    },
    "CoverageProvenance": {
      "type": "object",
      "additionalProperties": false,
      "required": ["mode","certificateId","sourceAnalysisId","equivalenceProofId"],
      "properties": {
        "mode": {"enum":["executed","reusedWithProof","notExecuted"]},
        "certificateId": {"anyOf":[{"$ref":"#/$defs/Id"},{"type":"null"}]},
        "sourceAnalysisId": {"anyOf":[{"$ref":"#/$defs/Digest"},{"type":"null"}]},
        "equivalenceProofId": {"anyOf":[{"$ref":"#/$defs/Id"},{"type":"null"}]}
      },
      "allOf": [
        {
          "if": {"properties":{"mode":{"const":"reusedWithProof"}}},
          "then": {"properties":{"certificateId":{"$ref":"#/$defs/Id"},"sourceAnalysisId":{"$ref":"#/$defs/Digest"},"equivalenceProofId":{"$ref":"#/$defs/Id"}}}
        }
      ]
    },
    "Observe": {
      "type": "object",
      "additionalProperties": false,
      "required": ["consumerId","observationId","events","contextReceiptId","proofKind","threadId","turnId","modelRequestId","observedAt","proofRef"],
      "properties": {
        "consumerId": {"$ref":"#/$defs/Id"},
        "observationId": {"$ref":"#/$defs/Id"},
        "events": {"type":"array","minItems":1,"maxItems":100,"uniqueItems":true,"items":{"$ref":"#/$defs/AckEntry"}},
        "contextReceiptId": {"$ref":"#/$defs/Id"},
        "proofKind": {"enum":["model_request","atomic_inject_and_continue"]},
        "threadId": {"$ref":"#/$defs/Id"},
        "turnId": {"$ref":"#/$defs/Id"},
        "modelRequestId": {"$ref":"#/$defs/Id"},
        "observedAt": {"$ref":"#/$defs/Time"},
        "proofRef": {"$ref":"#/$defs/Id"}
      }
    },
    "ObserveResult": {
      "type": "object",
      "additionalProperties": false,
      "required": ["contractVersion","consumerId","observationId","observed"],
      "properties": {
        "contractVersion": {"const":"ktchecker/1"},
        "consumerId": {"$ref":"#/$defs/Id"},
        "observationId": {"$ref":"#/$defs/Id"},
        "observed": {"type":"array","items":{"$ref":"#/$defs/AckEntry"}}
      }
    }
  }
}
```

### Complete example submission and a useful finding

All digests, IDs, paths, and provider versions here are **illustrative fixture values**, not claims about a compiled repository.

```json
{
  "contractVersion":"ktchecker/1",
  "workspaceId":"ws-demo","worktreeId":"wt-demo","patchId":"patch-1","patchSequence":1,"predecessorPatchId":null,
  "sourceSnapshotId":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "manifestRef":"manifest-demo-1",
  "policyDigest":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  "idempotencyKey":"submit-demo-1","mode":"background","waitMs":0,"analysisTimeoutMs":120000
}
```

```json
{
  "contractVersion": "ktchecker/1",
  "findingId": "finding-demo-1",
  "revision": 1,
  "reviewId": "review-complete",
  "identity": {
    "workspaceId": "ws-demo",
    "worktreeId": "wt-demo",
    "patchId": "patch-1",
    "sourceSnapshotId": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "modelId": "project-model-fixture",
    "toolchainId": "toolchain-fixture",
    "policyDigest": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
    "analysisId": "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
  },
  "ruleId": "ktc.fixture.initializer_type_mismatch",
  "category": "kotlin.compiler",
  "severity": "error",
  "certainty": "high",
  "targetId": ":app:debug",
  "depth": "build",
  "location": {
    "path": "app/src/main/kotlin/Label.kt",
    "fileDigest": "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
    "start": {
      "line": 18,
      "column": 20
    },
    "end": {
      "line": 18,
      "column": 29
    },
    "excerpt": "val label: String = user.name"
  },
  "relatedLocations": [],
  "diagnosis": "In this fixture, user.name has type String?, but label requires String.",
  "whyItMatters": "This assignment prevents the selected compilation from succeeding. The claim is limited to this snapshot and target.",
  "fixHint": "Handle the null case according to the feature's requirements, or use a nullable label if callers permit it. Do not add !! or invent a fallback value without deciding the intended behavior.",
  "verification": {
    "kind": "compile",
    "checkId": "kotlin.compile",
    "targetId": ":app:debug",
    "expected": "The type-mismatch diagnostic is absent on the new snapshot, with no new required compile errors.",
    "state": "not_run",
    "compilationId": "compilation-fixture-app-debug"
  },
  "evidence": [
    {
      "evidenceId": "evidence-demo-1",
      "kind": "compiler_diagnostic",
      "summary": "Synthetic example of an initializer type mismatch; no compiler was run to produce this document.",
      "artifactRef": null
    }
  ],
  "versions": {
    "engine": "design-example",
    "provider": "fixture-provider",
    "compiler": "fixture-compiler",
    "rulePack": "fixture-rules",
    "schema": "ktchecker/1"
  },
  "disposition": "active",
  "compilationId": "compilation-fixture-app-debug",
  "scopeId": "scope-fixture-app-debug",
  "kind": "correctness"
}
```

### State examples (valid full review objects, not observed output)

Each NDJSON line below is a separate synthetic review. The incomplete example preserves a completed syntax check while reporting unavailable semantic coverage; the failed example represents a worker failure, not source code failing compilation.

```ndjson
{"contractVersion":"ktchecker/1","reviewId":"review-pending","revision":1,"identity":{"workspaceId":"ws-demo","worktreeId":"wt-demo","patchId":"patch-1","sourceSnapshotId":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","modelId":null,"toolchainId":null,"policyDigest":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","analysisId":null},"state":"pending","outcome":"unknown","capture":"coherent","freshness":"unverified","coverage":[{"checkId":"kotlin.compile","targetId":":app:debug","depth":"build","required":true,"state":"pending","reason":null,"evidenceIds":[],"compilationId":"compilation-fixture-app-debug","scopeId":"scope-fixture-app-debug","provenance":{"mode":"notExecuted","certificateId":null,"sourceAnalysisId":null,"equivalenceProofId":null}}],"reasons":[],"findingCount":0,"findingRefs":[],"findingsCursor":null,"summary":"Accepted durably; required compilation has not run.","cancelRequested":false,"acceptedAt":"2026-11-04T00:00:00Z","updatedAt":"2026-11-04T00:00:00Z","deadlineAt":"2026-11-04T00:02:00Z","nextPollAfterMs":500,"currentSourceSnapshotId":null,"freshnessComparedAt":null,"policyAccounting":{"suppressedCount":0,"baselinedCount":0,"waivedCount":0,"excludedScopeCount":0},"coveragePlanId":null}
{"contractVersion":"ktchecker/1","reviewId":"review-unavailable","revision":2,"identity":{"workspaceId":"ws-demo","worktreeId":"wt-demo","patchId":"patch-1","sourceSnapshotId":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","modelId":null,"toolchainId":null,"policyDigest":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","analysisId":null},"state":"unavailable","outcome":"unknown","capture":"coherent","freshness":"unverified","coverage":[{"checkId":"kotlin.native.compile","targetId":"iosArm64","depth":"build","required":true,"state":"unavailable","reason":{"code":"sdk_missing","message":"Required platform SDK is absent.","retryable":true,"ownerAction":"Run on a provisioned supported host."},"evidenceIds":[],"compilationId":"compilation-fixture-iosArm64","scopeId":"scope-fixture-iosArm64","provenance":{"mode":"notExecuted","certificateId":null,"sourceAnalysisId":null,"equivalenceProofId":null}}],"reasons":[{"code":"sdk_missing","message":"Required target could not be checked.","retryable":true,"ownerAction":"Provision the required host/SDK."}],"findingCount":0,"findingRefs":[],"findingsCursor":null,"summary":"No verdict: required iOS compilation unavailable.","cancelRequested":false,"acceptedAt":"2026-11-04T00:00:00Z","updatedAt":"2026-11-04T00:00:01Z","deadlineAt":"2026-11-04T00:02:00Z","nextPollAfterMs":null,"currentSourceSnapshotId":null,"freshnessComparedAt":null,"policyAccounting":{"suppressedCount":0,"baselinedCount":0,"waivedCount":0,"excludedScopeCount":0},"coveragePlanId":null}
{"contractVersion":"ktchecker/1","reviewId":"review-incomplete","revision":3,"identity":{"workspaceId":"ws-demo","worktreeId":"wt-demo","patchId":"patch-1","sourceSnapshotId":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","modelId":"project-model-fixture","toolchainId":"toolchain-fixture","policyDigest":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","analysisId":"sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"},"state":"incomplete","outcome":"unknown","capture":"coherent","freshness":"unverified","coverage":[{"checkId":"kotlin.syntax","targetId":"commonMain","depth":"syntax","required":true,"state":"passed","reason":null,"evidenceIds":["syntax-fixture"],"compilationId":"compilation-fixture-commonMain","scopeId":"scope-fixture-commonMain","provenance":{"mode":"executed","certificateId":"certificate-fixture-kotlin.syntax","sourceAnalysisId":null,"equivalenceProofId":null}},{"checkId":"kotlin.resolve","targetId":"commonMain","depth":"semantic","required":true,"state":"unavailable","reason":{"code":"project_model_unavailable","message":"Generated dependency provenance could not be established.","retryable":true,"ownerAction":"Refresh the approved project model."},"evidenceIds":[],"compilationId":"compilation-fixture-commonMain","scopeId":"scope-fixture-commonMain","provenance":{"mode":"notExecuted","certificateId":null,"sourceAnalysisId":null,"equivalenceProofId":null}}],"reasons":[{"code":"project_model_unavailable","message":"Only syntax coverage is available.","retryable":true,"ownerAction":"Refresh the approved project model."}],"findingCount":0,"findingRefs":[],"findingsCursor":null,"summary":"Syntax passed; semantic coverage unavailable. This patch has not passed review.","cancelRequested":false,"acceptedAt":"2026-11-04T00:00:00Z","updatedAt":"2026-11-04T00:00:02Z","deadlineAt":"2026-11-04T00:02:00Z","nextPollAfterMs":null,"currentSourceSnapshotId":null,"freshnessComparedAt":null,"policyAccounting":{"suppressedCount":0,"baselinedCount":0,"waivedCount":0,"excludedScopeCount":0},"coveragePlanId":"sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"}
{"contractVersion":"ktchecker/1","reviewId":"review-failed","revision":2,"identity":{"workspaceId":"ws-demo","worktreeId":"wt-demo","patchId":"patch-1","sourceSnapshotId":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","modelId":"project-model-fixture","toolchainId":"toolchain-fixture","policyDigest":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","analysisId":"sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"},"state":"failed","outcome":"unknown","capture":"coherent","freshness":"unverified","coverage":[{"checkId":"kotlin.compile","targetId":":app:debug","depth":"build","required":true,"state":"failed","reason":{"code":"worker_crashed","message":"The provider exited before producing a coverage certificate.","retryable":true,"ownerAction":null},"evidenceIds":[],"compilationId":"compilation-fixture-app-debug","scopeId":"scope-fixture-app-debug","provenance":{"mode":"executed","certificateId":null,"sourceAnalysisId":null,"equivalenceProofId":null}}],"reasons":[{"code":"worker_crashed","message":"No completed compile verdict.","retryable":true,"ownerAction":null}],"findingCount":0,"findingRefs":[],"findingsCursor":null,"summary":"Analysis worker failed; code correctness is unknown.","cancelRequested":false,"acceptedAt":"2026-11-04T00:00:00Z","updatedAt":"2026-11-04T00:00:03Z","deadlineAt":"2026-11-04T00:02:00Z","nextPollAfterMs":null,"currentSourceSnapshotId":null,"freshnessComparedAt":null,"policyAccounting":{"suppressedCount":0,"baselinedCount":0,"waivedCount":0,"excludedScopeCount":0},"coveragePlanId":"sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"}
{"contractVersion":"ktchecker/1","reviewId":"review-complete","revision":4,"identity":{"workspaceId":"ws-demo","worktreeId":"wt-demo","patchId":"patch-1","sourceSnapshotId":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","modelId":"project-model-fixture","toolchainId":"toolchain-fixture","policyDigest":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","analysisId":"sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"},"state":"complete","outcome":"findings","capture":"coherent","freshness":"unverified","coverage":[{"checkId":"kotlin.compile","targetId":":app:debug","depth":"build","required":true,"state":"findings","reason":null,"evidenceIds":["evidence-demo-1"],"compilationId":"compilation-fixture-app-debug","scopeId":"scope-fixture-app-debug","provenance":{"mode":"executed","certificateId":"certificate-fixture-kotlin.compile","sourceAnalysisId":null,"equivalenceProofId":null}}],"reasons":[],"findingCount":1,"findingRefs":[{"findingId":"finding-demo-1","revision":1}],"findingsCursor":null,"summary":"Required compilation was checked and has one blocking type mismatch. Complete does not mean passed.","cancelRequested":false,"acceptedAt":"2026-11-04T00:00:00Z","updatedAt":"2026-11-04T00:00:04Z","deadlineAt":"2026-11-04T00:02:00Z","nextPollAfterMs":null,"currentSourceSnapshotId":null,"freshnessComparedAt":null,"policyAccounting":{"suppressedCount":0,"baselinedCount":0,"waivedCount":0,"excludedScopeCount":0},"coveragePlanId":"sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"}
```

Example adapter acknowledgement shape, **usable only after real insertion and proof verification**; these fixture values cannot authorize an ACK:

```json
{
  "consumerId":"consumer-demo",
  "events":[{"eventId":"event-demo-1","eventRevision":1,"payloadDigest":"sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"}],
  "receipt":{"receiptId":"receipt-demo-1","hostKind":"controlled-host-fixture","hostVersion":"fixture-version","threadId":"thread-demo","turnId":"turn-demo","contextItemId":"context-demo","contextDigest":"sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff","insertedAt":"2026-11-04T00:00:05Z","proofRef":"private-proof-demo"}
}
```

Example privileged `review.observe` request, referencing that insertion receipt and a distinct model request. **All values are synthetic; no delivery or model invocation occurred.** The trusted controller must verify that the identified request actually included the acknowledged context/event bytes for this consumer and thread. Schema-valid identifiers, a timestamp, and `proofRef` do not establish authenticity, inclusion, or runtime ordering.

```json
{
  "consumerId":"consumer-demo",
  "observationId":"observation-demo-1",
  "events":[{"eventId":"event-demo-1","eventRevision":1,"payloadDigest":"sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"}],
  "contextReceiptId":"receipt-demo-1",
  "proofKind":"model_request",
  "threadId":"thread-demo",
  "turnId":"turn-demo",
  "modelRequestId":"model-request-demo-1",
  "observedAt":"2026-11-04T00:00:06Z",
  "proofRef":"private-observation-proof-demo"
}
```

## 10. Security and validation gates

- **Local first:** stdio bridge or owner-only local socket/named pipe to broker. Socket directory/file permissions and peer ownership are checked. If Streamable HTTP is enabled, bind loopback, authenticate every request, validate Origin/Host against DNS rebinding, cap sessions/requests, and never equate a session ID with authorization. Remote access is an explicit separately secured product mode, not a side effect of binding `0.0.0.0`.
- **Root/path safety:** register approved roots; reject traversal, NULs, absolute/unapproved source paths, cross-worktree manifest references, and unsafe symlink escapes. Validate paths at open time with directory-relative/no-follow or equivalent protections against TOCTOU. A source excerpt may contain terminal escapes; render as data, not control sequences/Markdown instructions.
- **Command safety:** typed argv and configured check IDs, no `sh -c` built from model/finding/file input. Bound process groups, time, memory, stdout/stderr, temp directories, workers, and dependency downloads. Project Gradle scripts/compiler plugins/generators can execute arbitrary code; permission and isolation precede import/execution. Tool annotations such as read-only do not grant security.
- **Privacy:** source, diagnostics, model manifests, inboxes, receipts, and transcripts are local private artifacts with owner-only ACLs and retention limits. No external LLM/service uploads by default. Logs/notifications omit credentials, source dumps, and terminal transcripts. Do not embed tokens in command lines, tool schemas, receipts, or prompts. Hashes/paths can still reveal information; treat them as scoped private metadata.
- **No forged success:** model clients cannot ACK, change a policy, suppress a check, choose another consumer, or invent a coherent manifest by submitting JSON. The authenticated broker validates policy/snapshot references and receipts. Administrative policy changes are explicit owner actions and invalidate identities/gates.

**Threat boundary:** adapter-only APIs/receipts prevent ordinary tool-client misuse; owner-only file permissions do **not** defeat arbitrary code running as the same owner. A model or untrusted Gradle/plugin process with unrestricted same-UID shell access could read tokens, forge local files, or disable hooks. Strong enforcement requires the trusted host controller/receipt store and control credentials to be outside model/project execution capabilities, with OS/process/sandbox separation and protected configuration. Without that separation, disclose cooperative/trusted-code operation rather than adversarially enforced delivery. Never advertise `chmod 600`, an opaque proof ID, or a localhost endpoint as isolation from a hostile same-user process.

Release tests must cover schema/protocol examples; both negotiated profiles and wrong-era shapes; foreground terminal vs pending behavior; failed/missing targets; idempotency conflicts; deadline/cancellation races; multi-process worker recovery; inbox/outbox crash points; event duplication/cursor expiry; stale snapshots; Unicode/rename/deletion paths; privilege boundaries; and a real external-host **active and resumed turn** delivery demonstration with a fix-triggered second review. [The concrete harness and acceptance observations](research/mcp-hosts.md#4-required-demonstration--release-blocking-not-performed) are release-blocking. Mock consumers or this design's synthetic JSON do not satisfy them.

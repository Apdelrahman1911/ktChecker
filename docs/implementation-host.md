# Host and MCP implementation boundaries

This is a **`ktchecker/preview-1` implementation**, not certification of the
`ktchecker/1` design in [protocol.md](protocol.md). No universal stock-client
background-delivery support or atomic remote finalization is claimed.

## Components and runnable entry points

* [`bridges/mcp`](../bridges/mcp/README.md): actual TypeScript MCP SDK **2.4.0**,
  stdio, current **2026-07-28** profile, legacy explicitly rejected. Only ordinary
  `review.submit/status/events/cancel` tools; owner-prepared submission IDs prevent
  model-selected credentials, arbitrary capture roots and execution settings.
* [`bridges/reference-host`](../bridges/reference-host/README.md): independent
  out-of-model polling, durable SQLite inbox, insertion/ACK, protected observation
  SPI, local scheduling checks and a finite broker gate cut.
* `CodexHostPort`: an owner-run controlled app-server client, pinned to
  **gpt-6-astra**. New threads request `allowProviderModelFallback:false`; new and
  resumed responses must confirm that exact model before any diagnostic turn.
  No pre-existing/unowned thread is enumerated, guessed, imported or resumed.

Pins: Node **24.20.0**, TypeScript **5.9.3**, Node types **24.10.0**, patched Zod
**4.6.7**. Lockfiles are included. Zod was upgraded after the earlier 4.2.0 pin
was reported vulnerable by npm audit (GHSA-mrhx-c7xc-m3w5); both current production
dependency audits reported zero vulnerabilities.

```sh
npm --prefix bridges/mcp ci
npm --prefix bridges/reference-host ci
npm --prefix bridges/mcp test
npm --prefix bridges/reference-host test
```

Package READMEs contain exact mode-0600 configuration shapes and launch commands.
The reference controller defaults to **zero model invocations**. An explicit
`--max-observations N` grants a bounded number of diagnostic inference attempts;
it does not grant editing, compiler execution, source authority or finalization.

## Delivery state machine

1. The trusted capture integration calls `recordCompletedPatch()` **after every
   completed mutation**, including corrective P2. It persists the immutable input
   and idempotency key before `review.submit`. Unknown submit outcomes replay the
   same key, never a newly invented patch. The sample CLI accepts a fixed owner
   prepared list; arbitrary external file writes are not automatically captured.
2. The runtime calls `review.events` outside the model. An entire page and its
   fetch cursor commit in one SQLite transaction (`WAL`, `synchronous=FULL`).
   Fetch progress is independent of insertion, ACK and observation. Duplicate
   event IDs must have identical immutable bytes/digests.
3. The controller prepares an explicitly untrusted-data context, journals the
   insertion intent, reconciles an earlier marker, then inserts. Codex
   `thread/inject_items` appends standalone tool output without starting a turn.
   A confirmed append and durable host receipt permit **ACK only**.
4. Owner-enabled scheduling journals a model invocation ID before it supplies
   exact contexts via `turn/start.toolOutput`. For a live regular turn Codex queues
   the external tool output into that turn. For a resumed idle thread it starts a
   subsequent turn. There is no request asking the model to poll review status.
5. A completed public `agentMessage` must bind the invocation, request/context and
   original payload digests, and all finding diagnosis/location/verification
   content. Projected contexts additionally require every finding obligation and
   the explicit coverage summary (including any derivable-validation-reason
   projection). A non-null parent plan must also be reflected in full, including
   every slot, rule, context, identity and reason. A marker echo, insertion response, turn-start
   response, timeout, mismatched content or test-only witness cannot observe an
   event. Observation is durably recorded before the distinct `review.observe` RPC.
6. Lost ACK/observe responses retry the same receipts. Lost model responses
   reconcile only the saved invocation against bounded history of the owned
   thread; they are **not automatically resubmitted**. Ambiguous outcomes fail
   closed. Injection ambiguity may cause bounded duplicate context (maximum three
   attempted appends per marker), never a claim of exactly-once delivery.

The private subprocess transport reconnects only for a subsequent explicit
operation. It never silently retries the failed mutation. A client/worktree/
lineage/session/generation/thread mismatch cannot inherit another inbox. Stores
have an exclusive expiring controller lease; a displaced controller loses access.
Separate consumer tokens, cursors, receipts and inboxes remain independent.

## Bounded, honest diagnostic projection

Private application JSON-lines accepts up to **16 MiB** per line, independently
of the default **32 KiB** model context/output budget. Kernel pages, including
the full immutable payload, remain in the private durable inbox. Overlarge manual
MCP results return `model_output_exceeds_budget`, not a truncated success.

Automatic context uses `contextSchema: "ktchecker/context-preview-1"`:

* `receiptScope: "projected_context_only"` explicitly limits what insertion and
  observation attest. **Omitted original bytes are not claimed to have been read.**
* `payloadDigest` and `originalPayloadRef` identify the full original event;
  the latter records consumer/generation/event, digest and byte size, with
  `kind: "private_durable_inbox"`. `InboxJournal.readPayload(eventId,digest)` is
  protected local artifact retrieval, not a model tool or an observation.
* `diagnosticPayload` is a separately hashed JSON-string projection;
  `diagnosticPayloadDigest` is **not** the original event payload digest.
* **Every original finding, raw diagnostic, provider reason and nonredundant
  failure reason remains.**
  `findingObligations` enumerates each original finding index, full finding digest
  and event-bound obligation ID. Source ranges keep their original snapshot and
  file digest; stale data is not silently relocated onto current source bytes.
* Repetitive coverage rows are replaced by explicit exact total/required/state
  counts, unsuccessful-required count, context count/digest and full coverage
  digest. Up to eight reason groups are clearly labeled samples; omitted distinct
  reasons/rows are counted and the full rows remain in the original artifact.
  Empty projected `coverage` is **never evidence of passing coverage**.
  Structural success requires a nonblank certificate, separately from explicit
  unqualified/untested/unimplemented-required counts; one never conceals the other.
* Three exact top-level kernel-final-validation reason families may be represented
  by `coverageSummary.validationReasonProjection`: `coverage_unimplemented:ID`,
  `coverage_unqualified:ID`, and `coverage_unsuccessful:ID`. This requires a
  `result` event with exact preview/review/client/patch/snapshot binding, complete
  explicitly typed coverage rows, unique rule/context identities, and equality
  with the complete distinct reason set derived from those rows. All rows are
  considered, not only required ones. Duplicate reasons/rows, same-rule context
  aliases, partial fields, collisions or mismatched predicates fail closed;
  nothing is silently normalized. Kotlin/JVM blank-certificate semantics are
  reproduced rather than incorrectly using JavaScript `trim`.
  The three predicate groups carry exact counts and reason/row/context digests,
  the full original reason-array digest, omission-index digest and retained-count
  totals. The original immutable payload still holds every reason string.
  Provider `result.reasons`, unknown failures, diagnoses, recommendations and
  parent vectors are never compacted by this rule. A shortened top-level reason
  array is not a clean review: `hasCoverageValidationFailures` stays explicit.
* The entire prepared context and aggregate invocation must fit the model budget.
  If mandatory findings/diagnostics cannot fit, insertion/ACK/observation stops
  with `required_context_exceeds_budget`. Multi-part finding delivery is not yet
  implemented; mandatory findings are never silently dropped or truncated.
  A required `plan_accepted` event preserves its complete parent vector. An
  oversized vector is not narrowed to fit and remains unACKed/unobserved.

Model-request digests bind this prepared representation, not inaccessible internal
prompt transformations. Existing saved full-payload contexts remain immutable and
are supported; new contexts use the explicit projection schema.

## Evidence levels and current verification

| Evidence | Verified scope | Not established |
|---|---|---|
| MCP SDK tests | 17 local tests: current discovery/tools, legacy rejection, privilege/argument boundaries, transport bounds/reconnect, strict UTF-8, unqualified Windows privacy rejection, fixed Windows helper invocation/result decoding | Native Windows ACL qualification, host background wakeup or model observation |
| Reference-host tests | 40 local tests: durable replay, independent clients, stale routing, receipt/fence negatives, synthetic Codex recovery, coverage/redundant-reason projection and proof negatives, complete parent vectors, strict UTF-8, private-safe failure telemetry, owned-thread cleanup, independent retained application-evidence validation | Live inference/compiler behavior from test doubles |
| [Live projected Codex evidence](../bridges/reference-host/evidence/codex-inbox-projected-synthetic-events.json) | Real Codex 0.160.0, `gpt-6-astra`: background synthetic result delivered while a real turn was active; exact request joined that turn; subsequent adapter/app-server restart resumed only the owned thread and consumed a second result; explicit projected-context scope | Actual Kotlin kernel/compiler, internal inference input trace, a final guarded action |
| [Actual CLI/kernel/compiler delivery](../bridges/reference-host/evidence/cli-kernel-codex-public-fixture.json) | Real installed CLI/kernel/compiler and Codex 0.160.0 `gpt-6-astra`, three verified public diagnostic observations: active consumer A, independent consumer B, and repaired P2 after A/broker restart; exact public lexical text retained | Full-project qualification, stock-client universality, internal inference tracing, original full-coverage-byte reconstruction offline, final guarded action |

The newer [supervised Linux observation](evidence/actual-host-supervised-20261104.json)
adds a mandatory outer runtime budget, independent cleanup obligations, and
pre-await delivery-request commitments. Fresh compilation and all **129** synthetic
host tests passed; three actual compiler-to-Codex observations were independently
audited. Nine projected contexts and six reconstructable full originals were
checked; three progress originals and the initial barrier's full request bytes
remain absent. The final action still denied success. This is not full-project,
Windows/macOS, native-quiescence, performance, or release qualification.

The earlier live evidence includes exact serialized **application** `turn/start` requests,
SHA-256 digests, public response content and identity bindings. It verifies more
than marker receipt: diagnosis, source location, verification requirement/state
and context/payload/request digests all match. Source events in this demonstration
are deliberately synthetic and carry no assertion that Kotlin was executed.
Private credentials/account payloads and raw notification/reasoning streams were
not retained. The demo created and deleted only its own disposable public thread.
The earlier [full-payload demonstration](../bridges/reference-host/evidence/codex-inbox-synthetic-events.json)
is retained as predecessor evidence, not evidence of the new projection format.
These two historical files retained parsed public JSON, not its original lexical
text: their public-response digests were verified during the live run but cannot
be independently recomputed offline. Their exact application-request digests and
public **content** bindings can be independently verified. New demonstration
captures additionally retain exact verified public text, without reasoning/raw
events, so that its digest can also be recomputed offline.

The separate owner-opt-in `demo:cli:codex` driver uses the real installed CLI,
kernel and compiler with disposable public Kotlin source and two independently
enrolled consumers. It does **not** substitute fixture broker events. The
**2026-11-04 09:48 UTC** run completed three verified diagnostic observations.
Owner preparation retained all **1097 catalog candidates for the declared JVM
target**, not a reduced compiler-only subset. P1's 644,994-byte original result
became a 10,398-byte context with 3290 exactly derivable validation reasons
explicitly summarized; the aggregate request contexts were 20,074 bytes per
consumer. P2's 640,774-byte result became 5661 bytes (10,660 aggregate), with 3291
reasons summarized. A larger 1663-row/4989-reason case is a **synthetic regression**,
not the measured live inventory.

The owner explicitly enabled the experimental compiler rule
`ktc.compiler.common.abstract_member_not_implemented`. Real P1 execution found the
missing implementation; real P2 execution no longer emitted that diagnosis.
P2 still had unknown/unqualified coverage and **did not pass the final gate**.
The run also verified exact replay after loss of a real accepted-submit response,
ordinary-token ACK/observe rejection, cross-consumer event/cursor/receipt
rejection, and independent cursors/inboxes/observations. P2 was queued with zero
workers and then executed while A's adapter/app-server was disconnected, before
resuming only A's saved owned thread. Both disposable threads were confirmed
deleted. No guarded action was performed.

Earlier failures remain separately retained: native catalog startup, shared-policy
duplicate enable, and required-context overflow. The overflow attempt reached
real kernel submission/replay and accepted/progress insertion, but its final
result was correctly unACKed and unobserved; later success does not rewrite it.

New actual runs record independent source, executed bridge, launcher and installed
Java artifact hashes; a current dirty checkout is **not** asserted to be the source
of installed bytecode. The live private inbox retained and checked each full
immutable event while delivery was active. The public artifact retains complete
reason arrays/findings/raw diagnostics, selected compiler coverage and the full
coverage digest, but not every original coverage row. After disposable-state
cleanup, original full-event/coverage digests cannot be independently recomputed
offline from this artifact alone. Exact application requests, projected contexts
and original lexical public-response bytes **can** be independently recomputed.
The future-run driver now retains the original public synthetic payload after
checking its digest and the public-only boundary. That source change has not had
a new live run and does not add missing bytes to the historical artifact.
Deterministic representation failures stop promptly and
retain only fixed-field byte/count/hash telemetry, never private diagnostics or
credentials. Both success and failure attempt deletion once for the exact known
disposable thread IDs. Unknown creation/deletion outcomes retain private recovery
state and remain unverified; no unrelated thread listing/resume is used.

Hosted baseline `bc3208ff` tested MCP15/host26 on Linux and macOS successfully;
Windows had real failures, not skipped tests. New local test counts above must
not be retroactively attributed to that earlier hosted run. Windows native ACL
qualification remains outstanding as described below.

`modelRequestId` values prefixed `codex-app-request:` are **application request
IDs**, not upstream Responses or internal inference IDs. A controlled serialized
application request plus content-bound public completion is genuine integration
evidence, but is not internal inference tracing or proof of general stock-client
behavior. `probe:codex` only checks protocol/account metadata: `account=null` and
`requiresOpenaiAuth=false` do **not** mean the configured provider cannot infer.

## Safety and authority limits

* `HostPort` witnesses are a protected controller SPI, never JSON accepted from a
  model-facing tool. Synthetic positive SPI fixtures are labeled synthetic and
  cannot be cited as real model delivery.
* Local `mutationFence()` is scheduling advice, not a native writer lease. Remote
  `gate.complete` yields a revalidated **decision**, with `actionPerformed:false`;
  it cannot atomically authorize a later external edit/final action. Actual guarded
  action requires the kernel's trusted in-process composite authority/callback.
* Changing source/configuration or receiving new relevant results invalidates the
  kernel cut. ACK and observation cannot manufacture complete required coverage,
  erase a cancelled patch obligation or override blocking findings.
* Owner-private files are not adversarial isolation against arbitrary same-UID
  model/project code. Such code can read credentials or disable hooks. Deploy an
  external supervisor/process/permission boundary before claiming enforcement
  against a hostile coding agent. The demonstrated model workspace is a public,
  disposable, read-only fixture; it does not establish production isolation.
* Windows private-state startup currently fails closed with
  `private_state_platform_unqualified`. POSIX mode checks are not Windows ACL
  checks. Windows private credential/session storage remains an open release
  blocker until native ACL creation/verification and hosted tests are qualified;
  platform-neutral protocol tests cannot establish that protection.
  A fixed-program PowerShell/.NET helper candidate and eleven Windows-only native
  controls are present, but have not executed on Windows and are not wired into
  production. The two local construction/decoder controls are not native evidence.
* Persistent queues are bounded. Capacity, permission, oversized-data, binding,
  stale-generation and unknown-outcome failures remain visible and fail closed.
  There is no silent drop-to-clean or unlimited retry loop.

Official OpenAI documentation used for the integration:
[Codex app server](https://learn.chatgpt.com/docs/app-server) (standalone tool
output, injection, owned-thread resume, public events) and
[Codex SDK](https://learn.chatgpt.com/docs/codex-sdk). The installed 0.160.0
experimental JSON schema was additionally checked. Experimental APIs may change;
future clients/versions require revalidation.

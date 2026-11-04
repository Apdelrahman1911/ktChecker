# MCP and coding-host delivery research

**Checked: 2026-11-04 (UTC). Status: source-verified design, not an implemented or tested integration.** Public documentation and tagged source were fetched during this research. No MCP server, host adapter, background-delivery demo, or model-visible acknowledgement test was run. A successful protocol connection would not establish any of those results.

## Decision

Use a protocol-neutral Kotlin/JVM analysis broker and durable job/event store, with a **thin TypeScript MCP bridge**. The candidate bridge pins `@modelcontextprotocol/server`, `@modelcontextprotocol/client` (where needed), and `@modelcontextprotocol/server-legacy` to **2.4.0**. Its published package requires Node **>=20**; distribution must separately pin a supported runtime and dependency lockfile. Prefer published MCP **2026-07-28**, with an explicitly tested **2025-11-25** legacy profile. Do not advertise other revisions merely because a library recognizes them.

The reliable baseline is **ordinary, bounded tool calls plus an application-level durable review inbox**, polled by code outside the model. MCP Tasks are optional, not a delivery prerequisite. Neither a background job ID, an SSE message, a notification, nor writing a resource makes a finding enter a model's next request. A host-specific adapter must own that last step. See [the protocol contract](../protocol.md) and [architecture](../architecture.md).

This choice is not a claim that TypeScript makes analysis faster. It isolates protocol churn from the JVM engine and avoids claiming a modern protocol implementation that the observed Kotlin SDK does not yet provide.

## 1. Published revision and SDK evidence

| Component, observed pin | Fetched evidence | Consequence |
| --- | --- | --- |
| MCP **2026-07-28** | Official versioning page calls this **Current**; the version-specific page says “There is no negotiation handshake.” Requests carry version, client identity, and capabilities in `_meta`; `server/discover` is mandatory on the server and optional for the client. [M1–M3] | Do not send a 2025 initialization transcript and label it 2026. Modern HTTP also has revision-specific headers and validation. |
| TypeScript SDK **v2.4.0**, released **2026-10-23** | Tagged README describes v2 as the stable release line implementing 2026-07-28. Tagged protocol/legacy guides describe dual-era serving and the separate `server-legacy` adapter. Package metadata requires Node >=20. Source tree resolved to `951fe5603a7ec0b2dcb6a0b95d92cd86f93ad9e5`. [S1–S3] | Candidate primary bridge; pin versions, use the SDK's framing/legacy adapter, and run conformance tests before claiming compatibility. |
| Kotlin SDK **0.16.0**, released **2026-10-27** | Tagged `types/common.kt` sets `LATEST_PROTOCOL_VERSION = "2025-11-25"`; supported list adds `2025-06-18`, `2025-03-26`, `2024-11-05`. Release notes explicitly call new SEP-2575 discovery support “types and codecs only; the client and server do not use them yet.” Tagged `ServerSession.kt` still performs initialization. Tree resolved to `4c55826b6af00d616e1f5a285677ff53867cb941`. [S4–S6] | A credible Kotlin-only **legacy ordinary-tool** alternative, not evidence of full 2026-07-28 support. Do not change a version constant to claim the latter. |
| Kotlin Tasks surface at **0.16.0** | Tagged `types/tasks.kt` provides legacy task schemas; `ServerSession.kt` checks capabilities. The inspected SDK source tree has no equivalent durable task scheduler/store/automatic tool-task receiver demonstrated here. Release notes fix serialization of unlimited `ttl` as `null`. [S4, S6] | Types/capability checks are not a production task engine. Building custom lifecycle handlers is additional work, not a free SDK feature. |
| Tasks extension source **b3d28aceaa6d49f5f92258e3d06b61ad6f111e84** | Official extension README labels schema **2026-07-28 Stable**. Its package manifest says `@modelcontextprotocol/ext-tasks` **0.2.2**. The inspected requester APIs handle two generations; `/receiver` documents **2025-11-25 incoming sampling/elicitation only**, not a durable tools/call server. [T2–T4] | Stable extension schema does not mean turnkey mature server integration. Keep extension advertisement off until ktChecker implements/persists the correct lifecycle and proves it with a matching host. |

`releases/latest` for the Tasks extension returned HTTP 404 during this check. That is **not** evidence that its npm package is unpublished. The 0.2.2 figure above is source-manifest evidence, not an installed/published-package verification. No SDK was built or executed here.

### Do not mix the two Tasks generations

The old version-specific Tasks page is real and still calls Tasks **experimental**. It is not the current extension wire contract. [T1–T3]

| Concern | MCP 2025-11-25 experimental Tasks | 2026-07-28 Tasks extension |
| --- | --- | --- |
| Negotiation | `capabilities.tasks`, including receiver request categories; per-tool `execution.taskSupport` | `capabilities.extensions["io.modelcontextprotocol/tasks"]`; per-request client capabilities and server discovery |
| Creation | Requestor adds `params.task`, optionally `ttl` | Server-directed when client opts in; `CreateTaskResult.resultType = "task"`; no legacy augmentation flag |
| Retrieval | `tasks/get` status, then `tasks/result` for the underlying result | `tasks/get` includes terminal result or JSON-RPC error; no legacy `tasks/result` flow |
| Mid-flight input | `input_required`, related-task metadata, `tasks/result` and associated server requests | `input_required` exposes `inputRequests`; client replies with `tasks/update` |
| Retention field | `ttl`, measured from creation | `ttlMs`; consult the extension's lifetime rules, not an assumed indefinite handle |
| Notifications | Optional `notifications/tasks/status`; requestor **must not rely** on receiving it | Optional `notifications/tasks`; polling remains the portable observation mechanism |
| Cancel | Valid cancellation must transition to `cancelled` before response; terminal cancellation is invalid | Cooperative intent; acknowledgement does **not** guarantee work stops or final state becomes `cancelled` |
| Tool result with `isError: true` | Task reaches `failed` | Task reaches `completed`, containing that tool result; `failed` is for JSON-RPC errors |
| Model continuation | Not mandated by MCP | Not mandated by MCP |

Both generations' wire status `completed` is distinct from ktChecker's **coverage completeness**, and from **delivery to the model**. A compiler failure may be a successfully executed analysis that found defects. A perfectly successful task wrapper may return an application result saying coverage is unavailable.

**Launch decision:** no Tasks capability in either profile. `review.submit`, `review.status`, and `review.events` provide the same durable application semantics on both. A later Tasks adapter must be an explicit versioned feature, not accidental SDK autodetection. This is a fallback of transport mechanics, not a reduction of the delivery or coverage contract.

## 2. What a host actually has to do

There are three different systems:

1. **Analysis broker:** receives an immutable patch snapshot, runs checks, atomically stores results and an outbox event.
2. **Host-side adapter:** polls outside the model, persists an inbox, selects a safe turn boundary, injects bounded diagnostic context, and records a receipt.
3. **Coding model:** receives that context in an actual request and can diagnose/repair the patch. It is not responsible for remembering to poll or rerun reviews after fixes.

An MCP resource update, progress notification, log line, desktop notification, or terminal output in a detached process demonstrates at most a transport/UI event. Calling `sampling/createMessage` would request a new sampling operation where supported; it is not a portable way to append findings to the original coding conversation. ktChecker does not require sampling, an LLM API key, or a runtime explanation model. Findings use deterministic evidence and templates; project source stays local by default.

### Host capability matrix

“Documented” below means the named primary source supports the mechanism. **All ktChecker integrations remain unimplemented and unverified.** None is advertised as working background delivery today.

| Host/profile | Source-supported boundary | Proposed adapter and precise limitation |
| --- | --- | --- |
| **Controlled Codex SDK wrapper** | Official OpenAI documentation says the Python SDK's `ExternalMessage` is treated as tool output, lower priority than user/developer instructions, and can start or join an active regular turn. It requires CLI >=0.151.0 when supplying one's own executable; published SDK builds include a pinned runtime. The TypeScript SDK supports `run()` on the same thread and `resumeThread()`. [O1] | Strong candidate for the first controlled-host demo. Persist thread ID and inbox; send external diagnostic data through `ExternalMessage`; schedule a permitted new/resumed turn when idle. Exact SDK/runtime versions must be locked and tested. This does **not** retroactively grant control over an unrelated stock UI session. |
| **Controlled Codex app-server client** | App-server documents `thread/resume`, `turn/start`, `turn/steer` with `expectedTurnId`, and streamed item/turn events. `thread/inject_items` persists model-visible history but does not start a turn. Generated schemas are specific to the installed Codex version. [O2] | Own the conversation and request coordinator. Use the documented external-data surface where available; otherwise explicitly delimit untrusted findings, never forge assistant/user testimony. Reconcile persisted items after an uncertain write. A successful steer is acceptance, not evidence a later model request actually ran. Never use raw history injection alone as a wake mechanism. |
| **Codex hooks in an existing supported session** | Official hooks documentation covers `apply_patch`/Edit/Write aliases, shell/MCP calls, and unified-exec completion (a later `write_stdin` may deliver the original PostToolUse). Synchronous PostToolUse can add `additionalContext`. Background command hooks deliver at the next safe model request of an active turn; when idle they wait for the next user turn and **do not start a new turn**. Unfinished background hooks and undelivered output are cancelled/discarded at session end. MCP hook errors/unavailable tools do not block the operation. [O3] | Hooks can trigger and inject, but cannot alone supply durable idle wake/restart recovery. Submit synchronously to the broker before allowing the next mutation; do analysis asynchronously. A protected wrapper must own final gating and durable replay. Hook failure defaults are not a fail-closed guarantee. Match all mutating tools, including shell and nested tool paths, not only Edit/Write. |
| **Claude Code hooks / controlled wrapper** | Current official hooks reference documents PostToolUse and PostToolUseFailure, `additionalContext` read on the next model request, and asynchronous output. Ordinary `async` waits for the next turn/user interaction. **`asyncRewake: true` with exit code 2 explicitly wakes idle Claude** and presents stderr (stdout if no stderr) as a system reminder. A `claude -p` teardown kills unfinished async hooks. [C1] | A documented host-specific wake exception, not an MCP feature. A sidecar can finish a hook with a concise event-ID-bearing reminder, but a broker/inbox must survive session teardown. Verify actual supported host version, output truncation, nonce visibility, interruption, permission, and Stop-hook behavior before enabling. Hook execution is not durable context acknowledgement. |
| **VS Code local MCP tool call** | Stable **1.144.0** source (release 2026-10-28) shows `callTool()` recognizing a task response, adopting `McpTask`, internally polling, and awaiting `task.result`; the implementation uses legacy `tasks/get`/`tasks/result`. `McpTaskManager` explicitly stores in-memory state that survives handler reconnects. [V1–V3] | Positive source evidence for **legacy task-aware tool execution**, not evidence for 2026 Tasks or arbitrary detached injection. A long-running *invoked tool* can supply its eventual result to that call. No process-restart durability or separate-turn activation is established by those maps. Use ordinary-tool fallback until the actual VS Code harness/version and any companion extension's supported context/turn API pass tests. Do not simulate UI keystrokes or assume all VS Code-hosted harnesses share capabilities. |
| **Generic MCP host / unknown editor** | MCP defines transport/tool results, not a mandatory UI, automatic tool invocation after edits, or a universal “wake this coding model” operation. [M1, M4, T1–T3] | Bounded foreground result can be useful **when called**. A returned pending handle requires the out-of-model adapter. Without a validated mutation trigger and context/turn boundary, classify `cooperative` or `manual`, report automatic-delivery **unavailable**, and refuse the strict guarantee. Do not bury this behind “MCP compatible.” |

Host docs are mutable and were fetched on the date above. Codex and Claude were not installed/probed for this project; no undocumented minimum version is asserted for their hooks. A release manifest must record **actual** host build, SDK build, transport, hook configuration digest, and capability-test result. Being configured is not being certified.

**Authority matters:** the Codex hooks page calls PostToolUse `additionalContext` **extra developer context**; it does not have the Python SDK `ExternalMessage`'s lower-priority tool-output semantics. A trusted, fixed hook adapter must frame/escape untrusted diagnostic fields as quoted data, never concatenate source comments/logs as developer instructions. Prefer the explicit external-data/tool-result API in controlled wrappers. Claude's system-reminder channel likewise needs trusted framing; its availability is not permission to elevate untrusted input.

### The patch boundary is not “a file changed”

For strict mode, the wrapper controls a worktree and the mutating operation's lifecycle. It records a transaction at **every completed mutation**, including failed commands that left changed files, fixes made in response to findings, renames, deletions, generated sources, build files, and dependency/catalog/lock changes. Explicit atomic multi-file edits form one transaction; unrelated completed edits must not disappear into debounce. Tool-complete is only a usable boundary when the adapter can establish which writers are still active.

Acquire an exclusive/cooperative writer lease through snapshot publication, or obtain immutable bytes from the editor's transaction API. Flush required buffers. Snapshot actual tracked, dirty, untracked, and deleted inputs plus model/configuration identity. A watcher, `git diff` alone, or two identical hash scans cannot prove a coherent point-in-time tree under noncooperating writers. External writers or detached write-capable subprocesses invalidate strict capture unless isolated/accounted for. Such sessions remain best-effort/incomplete, never silently “exact.”

All patch IDs form a durable obligation ledger. Cancelling or superseding an old review does not silently satisfy its obligation. Content-addressed analysis may be reused only with an identical complete analysis identity; each patch still gets its own recorded coverage/disposition. Require the latest coherent snapshot **and** no unexplained earlier patch gaps before the final gate passes.

## 3. Real delivery algorithm

Detailed fields and ordering are in [protocol.md](../protocol.md). The necessary host behavior is:

1. **Before mutation completion is released:** persist `(host thread, worktree, patch sequence, immutable snapshot, submit idempotency key)` in the adapter journal; broker acceptance means the obligation and job are durable. A queue-full/unavailable broker must produce an explicit unresolved gate state.
2. **While the model continues other work:** one adapter worker polls `review.events`, writes returned events to a durable inbox, and advances its **fetch** cursor only after that write. Polling must not consume model turns or depend on model-issued calls. Notifications may shorten sleep, not replace polling.
3. **At the earliest supported context checkpoint:** revalidate workspace/thread identity and freshness, select bounded diagnostic payloads, and insert them via the host's model-visible external-data/tool-context API. Insertion cannot change an already-sent model request or an already-sampled tool action. If policy requires observation before that action, defer it and schedule the next model invocation without releasing its action fence. Do not rewrite immutable source ranges to a newer file without reanalysis. Stale findings are labelled historical and trigger review of the current snapshot, not an automatic edit against new bytes.
4. **At idle:** only an owner-enabled host API/native wake mechanism may start or resume a turn. If none exists, keep events pending, expose `waiting_for_host_turn`, and block the automatic guarantee. Never target a guessed terminal or session.
5. **After confirmed insertion:** journal the host context receipt and then acknowledge the event to the broker. A network ACK, polling result, task completion, hook process exit, log entry, or notification receipt is insufficient. If the host cannot expose/reconcile such a receipt, strict delivery remains unavailable.
6. **Before final success and after each repair:** require actual model-request inclusion (`model_observed`), or tested atomic inject-and-continue evidence before the guarded action, in addition to insertion ACK. Capability or a future opportunity to run is insufficient. Prevent a final answer/commit gate from overtaking unresolved current coverage, required historical obligations, or unobserved required findings/coverage. Run the same submission path after repair. Wait in the runtime, not an endless “please poll” model loop. Deadline expiry returns a truthful incomplete result; owner overrides are explicit recorded waivers, not passes.

Keep `received`, `context_injected`, and `model_observed` separate. Exactly-once delivery across two unrelated durable stores cannot be assumed. Use at-least-once transport, stable event IDs/revisions, deterministic context markers, and host transcript reconciliation. If a crash occurs after injection but before ACK, replay/reconcile the same marker; do not create a new finding identity to hide duplication. Context injection still does not prove the model understood or repaired a finding.

The final gate uses a finite required-event frontier after review work settles. It must observe all required findings/coverage through that cut and revalidate snapshot/policy/coverage epochs before success; new relevant review events invalidate the cut. ACK/observation/gate-control records are not recursively required context. The controller/receipt authority also needs protection from model/project execution: same-owner file permissions alone do not stop an unrestricted same-UID shell from forging state. Without an isolated trusted controller, disclose a cooperative trust assumption rather than adversarial enforcement.

## 4. Required demonstration — release-blocking, not performed

Documentation, synthetic fixtures, schema validation, or an internal consumer are not the requested demonstration. The first integrated release needs a recorded, reproducible run against a **real external coding host**; a mock-only pass is insufficient.

### Controlled-host harness

- Pin one actual host, its SDK/runtime, adapter, MCP profile, JVM engine, and configuration. Use a disposable test repository and controlled writers; no production secrets. Prefer a wrapper with an external-message API and an inspectable model-input history.
- Introduce a small deterministic Kotlin compile defect in patch **P1**, submit automatically, and deliberately delay analysis. The coding model proceeds on an unrelated operation while the adapter polls; count **zero** model-issued polling calls.
- Broker creates a finding with a freshly generated opaque marker available **only** in that finding. Record patch/snapshot/coverage digest and timestamps for acceptance, outbox, inbox, context insertion, next model request, and acknowledgement.
- **Active-turn case:** prove that a subsequent request in the existing turn includes the exact finding/marker and correct source snapshot. Capture the model's public response/action identifying the diagnostic and a valid repair; do not request or record hidden reasoning.
- **Idle/resumed case:** finish/interrupt the first turn before the finding arrives. Restart the adapter and reconnect. The owner-enabled adapter resumes/starts the correct saved thread, inserts the persisted finding, and proves it is present in an actual subsequent request. A badge or stdout line does not pass.
- The repair creates **P2** automatically. Assert P2 has a new coherent identity and required checks run again. An unrelated final message cannot bypass P2's barrier. Record the actual check result; never assert correctness merely because the model says “fixed.”

### Adversarial/recovery matrix

| Injection/failure | Required observation |
| --- | --- |
| Drop every optional notification | Poller still delivers; no model polling. |
| Drop submit response after durable accept | Same idempotency key returns same review, no extra obligation or lost patch. |
| Crash before/after inbox persistence | Unacknowledged events replay; cursor never skips non-durable data. |
| Crash after context insertion, before ACK | Existing context marker reconciled or bounded duplicate explicitly detected; no invented exactly-once guarantee. |
| Duplicate/out-of-order events, reconnect, expired cursor | Stable IDs deduplicated; full durable reconciliation or explicit `delivery_history_lost`, never silent skip. |
| P2 completes before P1 | P1 findings remain tied to P1; stale marker cannot authorize P2 edits; both ledger entries accounted for. |
| Rename/delete/untracked/build/config/plugin change | Snapshot/analysis identity changes; no stale cache reuse or missing patch trigger. |
| Shell partially writes then fails; process keeps writing | Capture changed bytes at accounted completion or report unsupported writer; never treat failed tool as no patch. |
| Agent fixes a finding | New patch and submission occur without an extra prompt. |
| Hook disabled/timeout/untrusted, broker queue full, platform SDK absent | Strict mode visibly unavailable/incomplete; no pass, no background exception lost in logs. |
| Stop/turn-completed races, idle host, owner stops automation | No unauthorized turn restart; pending durable event remains unresolved. |
| Token/output limits and compaction | Bounded payload with mandatory identity/evidence and retrievable detail; required context not silently truncated/forgotten. |
| Forged ACK, cross-worktree event, path traversal, malicious diagnostic text | Rejected or rendered as untrusted data; no receipt without authenticated adapter proof. |

Report measured submit latency, analysis latency, arrival-to-model-request latency, queue depth, context size, replay counts, and successful/failed cases **only after execution**. Set acceptance budgets before the run. No performance or host-reliability numbers are claimed by this research.

## Sources and retrieval notes

All entries below were opened/fetched on **2026-11-04**. Tagged/commit URLs are preferred over moving branches. The source check establishes described APIs/implementation shapes, not ktChecker runtime compatibility.

- **M1:** [Official current versioning](https://modelcontextprotocol.io/docs/2026-07-28/learn/versioning.md).
- **M2:** [2026-07-28 versioning/compatibility](https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning.md), including dual-era matrix and extension negotiation.
- **M3:** [2026-07-28 discovery](https://modelcontextprotocol.io/specification/2026-07-28/server/discover.md), [stdio](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/stdio.md), [Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http.md).
- **M4:** [2026-07-28 tools](https://modelcontextprotocol.io/specification/2026-07-28/server/tools.md); [legacy lifecycle](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle).
- **S1:** [TypeScript v2.4.0 release](https://github.com/modelcontextprotocol/typescript-sdk/releases/tag/v2.4.0), [tagged README](https://raw.githubusercontent.com/modelcontextprotocol/typescript-sdk/v2.4.0/README.md).
- **S2:** [Tagged protocol-version guide](https://raw.githubusercontent.com/modelcontextprotocol/typescript-sdk/v2.4.0/docs/protocol-versions.md), [legacy serving guide](https://raw.githubusercontent.com/modelcontextprotocol/typescript-sdk/v2.4.0/docs/serving/legacy-clients.md).
- **S3:** [Tagged server package metadata](https://raw.githubusercontent.com/modelcontextprotocol/typescript-sdk/v2.4.0/packages/server/package.json).
- **S4:** [Kotlin 0.16.0 release](https://github.com/modelcontextprotocol/kotlin-sdk/releases/tag/0.16.0).
- **S5:** [Kotlin 0.16.0 version constants](https://raw.githubusercontent.com/modelcontextprotocol/kotlin-sdk/0.16.0/kotlin-sdk-core/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/types/common.kt).
- **S6:** [Kotlin 0.16.0 task types](https://raw.githubusercontent.com/modelcontextprotocol/kotlin-sdk/0.16.0/kotlin-sdk-core/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/types/tasks.kt), [ServerSession](https://raw.githubusercontent.com/modelcontextprotocol/kotlin-sdk/0.16.0/kotlin-sdk-server/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/server/ServerSession.kt).
- **T1:** [2025-11-25 experimental Tasks](https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/tasks.md).
- **T2:** [Current Tasks extension overview](https://modelcontextprotocol.io/extensions/tasks/overview.md); [pinned extension README](https://raw.githubusercontent.com/modelcontextprotocol/ext-tasks/b3d28aceaa6d49f5f92258e3d06b61ad6f111e84/README.md).
- **T3:** [Pinned 2026-07-28 Tasks specification](https://raw.githubusercontent.com/modelcontextprotocol/ext-tasks/b3d28aceaa6d49f5f92258e3d06b61ad6f111e84/specification/2026-07-28/tasks.md).
- **T4:** [Pinned package manifest](https://raw.githubusercontent.com/modelcontextprotocol/ext-tasks/b3d28aceaa6d49f5f92258e3d06b61ad6f111e84/packages/ext-tasks/package.json), [receiver guide](https://raw.githubusercontent.com/modelcontextprotocol/ext-tasks/b3d28aceaa6d49f5f92258e3d06b61ad6f111e84/typescript/receiver.md), [adapter guide](https://raw.githubusercontent.com/modelcontextprotocol/ext-tasks/b3d28aceaa6d49f5f92258e3d06b61ad6f111e84/typescript/adapters-and-schemas.md).
- **O1:** [Official OpenAI documentation: Codex SDK](https://learn.chatgpt.com/docs/codex-sdk), especially external messages and pinned Python runtime.
- **O2:** [Official OpenAI documentation: Codex app-server](https://learn.chatgpt.com/docs/app-server), lifecycle, version-specific schemas, steer and injection sections.
- **O3:** [Official OpenAI documentation: Codex hooks](https://learn.chatgpt.com/docs/hooks), tool coverage, MCP hook failure behavior, and background safe-point/idle semantics.
- **C1:** [Claude Code hooks](https://code.claude.com/docs/en/hooks.md), `asyncRewake`, additionalContext, background limitations, PostToolUseFailure, and security sections.
- **V1:** [VS Code 1.144.0 release](https://github.com/microsoft/vscode/releases/tag/1.144.0).
- **V2:** [VS Code 1.144.0 request handler](https://raw.githubusercontent.com/microsoft/vscode/1.144.0/src/vs/workbench/contrib/mcp/common/mcpServerRequestHandler.ts), `callTool`, `McpTask`, polling/result methods.
- **V3:** [VS Code 1.144.0 in-memory task manager](https://raw.githubusercontent.com/microsoft/vscode/1.144.0/src/vs/workbench/contrib/mcp/common/mcpTaskManager.ts); [current server configuration documentation](https://code.visualstudio.com/docs/agents/reference/mcp-configuration).

OpenAI source order followed the **OpenAI Docs** skill: shell JSON-RPC to the official `https://developers.openai.com/mcp` endpoint, `search_openai_docs` for the precise Codex app-server/SDK/hooks topics, then `fetch_openai_doc` on matching official pages. No browser tool was available; the official documentation search endpoint worked, so no search-engine snippets or third-party Codex pages were used as evidence. Exact requested collaboration models are not product runtime dependencies; this document recommends no silent model substitution.

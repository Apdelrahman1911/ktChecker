# Durable reference host (preview)

Out-of-model poller and per-consumer SQLite inbox, with a controlled read-only
Codex app-server integration. It does **not** modify the stock Codex TUI or turn
an MCP notification into a model observation.

```sh
npm --prefix bridges/mcp ci
npm --prefix bridges/reference-host ci
npm --prefix bridges/reference-host test
# Read-only protocol/account inquiry; does NOT test inference availability:
npm --prefix bridges/reference-host run probe:codex
```

Node **24.20.0** is required (`node:sqlite`). `npm test` makes no inference calls.
Linux is the currently verified host. Windows private-state startup and default
Darwin startup fail closed. The [Darwin N-API private-state experiment](../mcp/native/PRIMARY-SOURCES.md)
requires an explicit target-native build and opt-in; it is **not yet natively
qualified**. It preserves FULL/WAL and binding checks, uses bounded same-descriptor
configuration reads, and keeps native authority until confirmed SQLite close.
Use separate Inbox/Codex directories; same-directory live native controllers
cannot displace each other. Native verification is an open blocker, not a skip.
This interim backend is source-checkout-only, not a Darwin distributable: required
native inputs/artifacts are absent from the unchanged MCP npm archive. Separate
reviewed packaging and native qualification remain required for release support.
The controlled Codex runtime was verified with **codex-cli 0.160.0** and explicit
**gpt-6-astra**, without provider model fallback.

## Run a controller

Create an owner-private mode-0600 JSON file:

```json
{
  "broker": {"command": "/absolute/path/to/ktchecker", "args": ["rpc", "--state", "/owner/private/kernel"]},
  "registration": {
    "contractVersion": "ktchecker/preview-1",
    "binding": {"ownerId": "owner", "workspaceId": "workspace", "worktreeId": "tree", "clientId": "client-a", "lineageId": "lineage-a", "sessionId": "session-a", "generation": 1},
    "client": {"clientId": "client-a", "token": "ORDINARY_CLIENT_TOKEN"},
    "adapter": {"clientId": "client-a", "token": "DISTINCT_ADAPTER_TOKEN"}
  },
  "inboxDirectory": "/owner/private/inbox-a",
  "codex": {"sessionDirectory": "/owner/private/codex-a", "cwd": "/owner/public-disposable-fixture"},
  "submissions": []
}
```

Use the real owner enrollment record, not these placeholder values. Optional
`submissions` entries are immutable owner-prepared
`{patchId,idempotencyKey,input}` values. Every completed patch, including repairs,
must be recorded; this controller does not discover external writes or attest
source/configuration authority by scanning a mutable directory.

```sh
# Poll + durable context insertion/ACK only. No inference; no observation:
node bridges/reference-host/dist/src/main.js --config /owner/private/host.json

# Explicit permission for at most TWO diagnostic inference attempts this run:
node bridges/reference-host/dist/src/main.js --config /owner/private/host.json --max-observations 2
```

Positive `--max-observations` automatically schedules a read-only model turn when
a terminal result is in the inbox. The **controller**, not the model, polls. A
completed public response must match all finding diagnosis/location/verification,
finding obligations and context/payload digests before observation is reported.
Failure, missing observation, overflow, stale binding or unknown request outcome
keeps the scheduling/finalization decision closed. No native final action is run.

Stop with SIGINT/SIGTERM; restart using the same private directories, binding and
generation. Only the thread ID created and persisted by this controller is ever
resumed. Separate consumers require independent enrollment, stores and threads.
Changing the binding on an existing store is rejected. Process-crash leases expire
after 15 seconds. Never guess or import an unrelated Codex thread ID.

## Explicit live demonstration

This uses **synthetic broker events and real Codex inference**. It creates only
a disposable public fixture/thread, demonstrates active-turn delivery and
offline/resume recovery, then deletes its own thread. It does not run Kotlin.

```sh
npm --prefix bridges/reference-host run demo:codex:synthetic -- \
  --allow-inference /owner/approved-output/codex-evidence.json
```

The flag authorizes real inference expenditure; do not run it without approval.
Only synthetic application requests and matching **public** responses are saved,
never credentials, account payloads, raw notifications or reasoning.

The separate actual-CLI driver requires an already built owner-selected
`ktchecker` distribution with its pinned compiler. It performs real compiler
execution against disposable public Kotlin source, not a fake broker:

```sh
npm --prefix bridges/reference-host run demo:cli:codex -- \
  --allow-inference --cli /absolute/path/to/ktchecker \
  --compiler-java-home /owner/prepared-link-free-jdk21 \
  --max-runtime-ms 600000 \
  --evidence /owner/approved-output/cli-kernel-codex.json
```

The evidence path must be **new**; existing evidence is never overwritten. The
explicit compiler JDK is used by every owner command and broker restart, with no
ambient-JDK fallback. The CLI validates its contents; a path alone is not proof.
`--max-runtime-ms` is mandatory (1,000–1,800,000, canonical integer milliseconds;
no default). A separate controller bounds the directly owned driver, including
cleanup and finalization reserves. Expiration is sticky: a late result cannot
become success. This is not remote/native descendant-quiescence proof; uncertain
cleanup remains visible and private recovery state is retained.

`--allow-inference` authorizes three diagnostic observations, including an
active-turn barrier, two independent consumers and a disconnected/resumed P2
repair. Owner-side preparation enables only the selected experimental compiler
rule; the full candidate coverage inventory remains visible and unqualified.
The driver injects loss of one **actual accepted** submission response, restarts
the actual broker and reconciles the same durable key. It never manufactures
kernel results or observation witnesses, never builds Gradle, and never runs a
guarded final action. A successful compiler repair is not full-project/release
qualification. Its evidence `verified` field must be checked; launching it alone
is not a passing result.

The [supervised Linux observation](../../docs/evidence/actual-host-supervised-20261104.json)
records three real diagnostic requests, two independent consumers, broker/resume
recovery, and an independent content/receipt audit. All 129 synthetic host tests
also passed on that source tuple. Only six of nine full original event payloads
are reconstructable; the three progress originals are missing. All nine projected
contexts were checked. The final action remained denied, and neither general host
support nor the warm latency target is qualified.

[Evidence and limitations](../../docs/implementation-host.md) distinguish a
genuine application request/public response from an unavailable internal inference
trace, synthetic kernel fixtures from actual compiler integration, and a remote
gate decision from an atomic guarded action.

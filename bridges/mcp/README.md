# ktChecker MCP preview bridge

Thin TypeScript bridge using the **actual MCP SDK 2.4.0**, not a hand-written
MCP-shaped JSON protocol. Runtime contract: `ktchecker/preview-1`.

```sh
npm --prefix bridges/mcp ci
npm --prefix bridges/mcp test
node bridges/mcp/dist/src/main.js --config /owner/private/mcp.json
```

Use Node **24.20.0**. The MCP profile is **2026-07-28**; legacy negotiation is
explicitly rejected. A host supporting only the legacy profile cannot use this
bridge yet. Launch it as a stdio MCP server; stdout belongs exclusively to MCP.
Private configuration startup currently fails closed on Windows: POSIX mode
checks do not validate Windows ACLs. Native ACL support and hosted verification
remain an open release blocker, not a waived privacy check.

Owner-private configuration (copy, fill in, then `chmod 600`):

```json
{
  "broker": {
    "command": "/absolute/path/to/ktchecker",
    "args": ["rpc", "--state", "/owner/private/ktchecker"]
  },
  "credential": {"clientId": "owner-enrolled-client", "token": "ORDINARY_CLIENT_TOKEN"},
  "submissions": {}
}
```

`submissions` maps a chosen ID to an owner-prepared
`{patchId,idempotencyKey,input}`. The input must come from trusted immutable
capture/preparation, not from model-supplied execution settings or source roots.
**Never put the adapter token in this configuration.**

Exposed tools:

* `review.submit({submissionId})`
* `review.status({reviewId})`
* `review.events({cursor?,limit?})`
* `review.cancel({reviewId})`

Registration, capture, configuration changes, ACK, observation and finalization
are **not model tools**. Reading a result is not an observation receipt. A separate
out-of-model [reference host](../reference-host/README.md) owns durable delivery.

The subprocess transport is private application JSON-lines, **not MCP**. It
requires the preview-versioned response envelope. It has a 16 MiB line bound;
model-facing results have a separate 32 KiB bound. Oversize model results return
an explicit error, not a truncated finding or a clean verdict. Disconnection
fails the current request; the next explicit operation may reconnect. There is
no implicit mutation retry. stderr, credentials and arbitrary error transcripts
are not inserted into model context.

See [implementation boundaries and evidence](../../docs/implementation-host.md).

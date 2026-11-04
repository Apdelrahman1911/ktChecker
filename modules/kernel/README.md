# Durable kernel — preview implementation

This module implements `ktchecker/preview-1`, **not** a protocol-1 or production
model-host certification. Public DTOs are in `KernelTypes.kt`.

## Implemented boundaries

- SQLite WAL, `synchronous=FULL`, short `BEGIN IMMEDIATE` writes, per-operation
  connections and owner-only files. Manifest-authorized source/artifact bytes
  are copied, checked and flushed before review acceptance commits. Accepted
  reviews, intermediate patches, raw results and per-client outboxes are retained.
- Concurrent instances share a durable fair worktree queue, global/per-worktree
  execution caps and fenced attempt identities. Only the full provider inventory
  and analysis-input identity share computation. Each client keeps its own
  acceptance, cancellation, policy classification, receipts and delivery duties.
- A cancellation never terminates shared analysis and never clears an accepted
  review obligation. This preview has no cancellation-waiver API.
- Client and trusted-adapter capabilities are independently random; only their
  hashes are stored. Client credentials cannot ACK/observe. Fetch cursors are
  MAC-protected and bound to client, session and generation.
- ACK attests durable context insertion. A separate adapter observation receipt
  binds that insertion digest to a model request. The kernel trusts the adapter's
  attestation; it cannot independently prove that an external model received it.
- Progressive results are bounded, canonicalized, deduplicated and delivered to
  each current or later subscription. They do not satisfy final coverage.
- Final coverage requires the exact nonempty required rule/context set, successful
  certificates, implemented/tested/qualified rows and authorized provider/source
  identity. Empty caller plans expand to the provider's explicit supported set.
  Unknown/unimplemented/unqualified coverage, cancelled requests and missing
  authorities block a final gate. Provider lifecycle labels cannot waive findings.
- A pure trusted `ResultPolicy` may change only dispositions, separately for each
  client. Raw evidence and a broker certificate remain intact. That certificate
  binds a trusted provider assertion to original/effective inputs and the result;
  it is **not** independent proof of semantic correctness of provider code.
- Historical findings are not replaced by the last worker to finish. A later
  complete comparable full-scope check, same rule/context and known ancestry are
  required to close their absence. Historical failed/cancelled reviews still block.

## Integration order

1. A trusted host creates and privately persists a `ClientRegistration` intent,
   then calls `registerClient(registration)`. Exact retries are idempotent; a new
   token or generation cannot claim an existing client. `registerClient(binding)`
   is a convenience for callers that do not need crash-safe host enrollment.
2. `PreparedInputObserver` runs outside locks on each attempt. It can validate the
   original registered snapshot/profile and grant a provider's nonserializable
   execution authority over the private effective input. Request booleans never
   grant process-execution permission. Providers own their execution authorization.
3. `ResultPolicy` uses an **already captured** immutable per-client policy. It runs
   in a short publication transaction: no I/O, config/source locks or kernel reentry.
   Missing captured policy should throw; that consumer becomes incomplete.
4. `SnapshotGate` holds **config → source → kernel DB** fences. It must establish
   authoritative current source plus model/dependency/toolchain/config identity,
   include ABA identity in `analysisEpoch`, and provide policy expiry where relevant.
   A declared immutable overlay, cooperative head alone or before/after live scan
   must not be promoted to certified live-current authority.
5. Prepare a finite gate cut, insert and observe its required events, then complete
   that exact cut. Control receipts and gate traffic do not extend the frontier.
   New work/results/cancellation or authority changes invalidate old cuts.

`completeGate(..., guardedAction)` performs a short trusted synchronous action
under all fences. Before invoking it, the kernel commits a non-replayable action
intent and consumes the cut. A crash or callback failure leaves the client
generation claimed/outcome-unknown, blocking new work or another action attempt;
there is no automatic recovery that could duplicate an irreversible action.
This is **not** an exactly-once external-delivery claim. Operator reconciliation
of unknown outcomes is not implemented. Calls without a callback return only a
momentary preview decision (`actionPerformed=false`), never a transferable permit
to send a later final answer.

## Limits and explicit non-claims

- The trust boundary is a per-user deployment, not hostile same-UID isolation.
  In-process providers/SPIs are trusted code. Source copies are immutable by API;
  digest checks do not make arbitrary external writers ABA-safe.
- Fixed JVM worker counts and durable execution reservations are bounded. A live
  hung provider retains its slot even after a deadline/heartbeat expires. Only
  confirmed process death (PID plus start identity) permits automatic reclamation.
  Arbitrary in-process code has no hard CPU/RSS sandbox. Hard external-process
  deadlines and descendant containment belong to the provider host; OS crash
  recovery does not claim orphan-process containment.
- Quotas reject *before* acceptance; no latest-wins deletion is used to make space.
  Automatic historical retention/compaction and crash-orphan archive GC are not
  implemented. A full/unavailable disk cannot be treated as successful delivery.
- Regular source/artifact files only. Symlinks, directory classpaths and inputs
  outside the manifest are not silently flattened into complete coverage.
- Directory fsync is unavailable through this Java implementation on Windows.
  SQLite FULL and file flushes are used, but universal power-loss durability is
  not certified on any platform by the process-restart tests.
- Tests use synthetic provider/source/config/model authorities where stated.
  SQLite, file capture, threads, multiple kernel instances and a forcibly killed
  worker JVM are real. They do not constitute production model-host qualification.

Run through the root's coordinated build: `./gradlew :kernel:test`.

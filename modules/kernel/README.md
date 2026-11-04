# Durable kernel — preview implementation

This module implements `ktchecker/preview-1`, **not** a protocol-1 or production
model-host certification. Public DTOs are in `KernelTypes.kt` and `PlanTypes.kt`.

## Implemented boundaries

- SQLite WAL, `synchronous=FULL`, short `BEGIN IMMEDIATE` writes, per-operation
  connections and owner-only files. Manifest-authorized source/artifact bytes
  are copied, checked and flushed before review acceptance commits. Accepted
  reviews, intermediate patches, raw results and per-client outboxes are retained.
- Concurrent instances share durable worktree-then-client fair queues,
  global/per-worktree execution caps and fenced attempt identities. Only the full provider inventory
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
  identity. Empty legacy **scalar** rule lists expand to the provider's explicit
  supported set; parent plans require an explicit nonempty controller inventory.
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

## Atomic same-client parent plans

`submitPlan(client, SubmitPlanRequest(patchId, idempotencyKey, planRef))` resolves a
privately registered, immutable owner/controller plan through `ReviewPlanAuthority`.
The default authority denies. A serializable `RequiredReviewPlan`, certificate ID,
caller-provided Boolean or knowledge of a reference is **not** authority on its own.
The kernel checks exact `ClientBinding` and the whole prepared request tuple; a
reference cannot be retargeted to another patch or idempotency key. Accepted exact
retries use the durable plan without calling the resolver again, including after a
restart or loss of the original materialization. Status and gate reads check the
persisted definition against its stored digest; corruption fails explicitly rather
than supplying a replacement vector. This integrity check is not an external trust
grant or hostile same-UID tamper protection.

Controller resolution, metadata freezing, byte validation and archive staging
happen outside config/source/DB locks. One SQLite transaction commits the parent,
**all** child subscriptions/work links and **all** acceptance events. A worker or
reader cannot observe an accepted prefix. Failure, quota rejection or process death
before commit confers no acceptance, even if private unreferenced archive bytes
survive. `planStatus` and `cancelPlan` require the parent's own client capability.
Cancelling a child or parent retains every obligation; shared work is not cancelled.
Parent `complete` means child computations finished, never permission to finalize.

The trusted controller must prepare and privately retain these facts before
registering a reference:

1. Verify model and impact evidence, its owner/workspace/worktree namespace and
   exact captured source/configuration/toolchain facts. The kernel checks binding
   and integrity relationships; it does **not** independently prove the supplied
   model/impact certificate IDs or discover omitted project contexts.
2. Supply every required context, including unavailable or removed contexts, and
   its explicit nonempty required-rule inventory. Do not derive required coverage
   from installed provider support or caller-selected narrowing. Slots are bounded
   to 512, have distinct IDs and executable compilation contexts, and use the exact
   same canonical `SnapshotManifest` and materialization root.
3. Mark non-executable slots `unsupported` with explicit reasons (for example,
   `context_removed_unresolved`), rather than omitting them. They retain the captured
   source and an administrative incomplete result, but never run a provider or get
   a work/coverage certificate. They cannot pass a gate. Their context identity may
   be absent; unknown classpaths should be represented as unknown, not executable
   inputs pointing to missing files.
4. Bind each executable slot to its exact `PlanContextIdentity`. Let `C(value)` be
   `Digests.canonical(ContractJson.encodeToJsonElement(value))`:

   | Identity | Required value |
   | --- | --- |
   | context/model/dependency/toolchain/configuration | Exact non-null per-context values from verified authority |
   | compiler version | Exact nonblank compiler version |
   | compiler arguments | SHA-256 of `C(ordered compilerArguments)` |
   | classpath | SHA-256 of `C(ordered original ArtifactIdentity records)`, including coordinates, digests and paths |
   | libraries / policies | SHA-256 of each canonical map |
   | compilation files | SHA-256 of the ordered `SnapshotFile` records selected by the explicit `compilationFiles` list |

   All paths in `compilationFiles` must be distinct captured members; null is not
   permission to infer a whole compilation. Full-project scope and absence of
   exclusions/unknown inputs are required for executable slots. Different contexts
   may legitimately use different dependency, toolchain and configuration digests.
5. `sourceBindingDigest` is SHA-256 of `C(the entire SnapshotManifest)`, including
   roots, exclusions and unknowns. `generatedInputsDigest` is the controller's
   separately verified complete generated-membership identity. It cannot be
   inferred from an unchecked declaration or omitted generated sources.
6. Preserve provider execution authority through `PreparedInputObserver` on each
   attempt. The kernel freezes regular `context.classpath` artifacts, **not** arbitrary
   mutable paths inside compiler arguments, friend/plugin artifacts or execution
   settings. The trusted provider/controller must freeze and verify every such
   execution dependency itself or report unsupported. Parent membership does not
   grant compiler/process-execution permission.

The full controller-declared required recipient list must currently be exactly the
submitting client. Any required additional client is rejected with
`cross_client_routing_unavailable` **before acceptance**. Cross-client atomic
`RoutingGrant` subscriptions are a release blocker, not an implemented feature.
Separately accepted same-input plans may share computation, never cancellation,
policy permissions, client capabilities, insertion receipts or observation duties.

### Owner/controller API sketch

This illustrates the embedded API, **not** an implemented Workbench model importer.
`ownerRegistry`, `preparedObserver` and `capturedPerClientPolicy` below are trusted
controller responsibilities, not kernel helpers. Before publishing a reference,
the controller must durably prepare every child/profile and frozen per-client
policy, and restore those policies before admission: cached results can be
classified inside the acceptance transaction and workers can start after commit.
Keep client/request/approval records separate from any shared analysis-key cache;
sharing computation must not overwrite another client's authority.

```kotlin
val request = SubmitPlanRequest(patchId, idempotencyKey, registeredPlanRef)
val kernel = KernelService.open(
    dataDir, coordinator,
    planAuthority = ReviewPlanAuthority { binding, exactRequest ->
        ownerRegistry.resolveExact(binding, exactRequest) // Private immutable lookup.
    },
    preparedInputObserver = preparedObserver,
    resultPolicy = capturedPerClientPolicy,
    snapshotGate = SnapshotGate.UNAVAILABLE, // Until a real joint authority exists.
)
val accepted = kernel.submitPlan(clientCredential, request)
val status = kernel.planStatus(clientCredential, accepted.planId)
```

A public RPC may forward credentials plus the exact `SubmitPlanRequest`, never a
caller-authored plan. Do not loop the scalar API or promote an owner-declared
single compilation to `fullProject`. Preserve verified compiler/classpath ordering
and compute identities only after final activation/execution policies are bound.
An executable slot has empty `reasons`; impact widening explanations belong in
controller evidence, while unsupported/removed slots retain explicit blockers.

### Parent delivery and final gate

Every child has the ordinary required accepted/progress/result/cancelled events.
One additional **required** `plan_accepted` event carries a `ReviewStatus` with the
whole `parentPlan` status/slot mapping. Its event review/patch IDs refer to the last
child; the owner patch ID is `payload.parentPlan.patchId`. Adapters must insert,
ACK and attest observation of this event too. It is not discardable control traffic.
Normal review payloads omit the default-null `parentPlan`, preserving the legacy
scalar wire shape; older payloads without the optional field remain decodable.
Parent-plan feature use must be owner-selected with a parent-aware host. A host
unable to fit the complete vector in its context
budget must fail closed, never truncate the required slots. No extra recursive
parent-complete event is generated; child results and `planStatus` expose completion.

For a parent gate, implement `SnapshotGate.withCurrentSnapshots` as **one** joint
config → source fence over the entire vector. The first supplied input is the latest
child/scalar representative: its identity supplies the scalar `SnapshotCheck`
fields, alongside an independently verified exact `contextIdentities` vector,
source-binding and generated-input digests. Do not echo unverified input fields.
Do not loop independent scalar fences. The default vector implementation denies
more than one input, and a scalar-only single-context adapter still lacks parent
membership attestation. All authorities remain fenced across the actual guarded
action. A change to any context, even one not represented by the scalar fields,
invalidates an existing cut.

Every historically accepted context/rule and failed/cancelled obligation remains
required. A later narrower parent does not waive it. **Any later scalar/manual
submission requires a newly accepted current parent plan**, even on the same source
snapshot, so its compiler/classpath/policy changes cannot borrow an old vector's
authority. The legacy scalar endpoint is not a project-model fanout path; collecting
independent scalar results is not a substitute for this atomic parent API.

## Fair dispatch and stress evidence

Selection uses durable worktree service age, then sponsoring client service age,
then that client's oldest eligible review. Only the selected sponsor spends a
client turn: co-subscribers sharing computation do not lose turns for their unrelated
older work. New worktree/client queues join the current dispatch round, and service
ages survive restarts (including backfilling the client table for older ledgers).
Fairness means bounded **dispatch opportunities among finite eligible queues**,
not equal CPU time, fair SQLite writer acquisition or a submission-latency SLA.

`KernelStressTest` exercises 100 distinct full analysis inputs/actual synthetic
executions, 200 primary/mirror subscriptions, 20 independent cancellations and 16
explicit current-head refreshes: 216 retained reviews across 16 clients and four
worktrees. Three kernel instances run in **one JVM**, with 12 producers and six
workers under global cap four/per-worktree cap two. SQL test-only triggers audit
actual dispatch fairness/FIFO/caps; a restart checks retained obligations and
receipts. The provider is a synthetic progressive 20 ms fixture, not a compiler,
FIR, project-model import or production host benchmark. The generated
`build/reports/stress/kernel-100-patches.json` records observed timings and SQLite
writer wait/hold measurements; it is deleted before a rerun to avoid presenting
stale success. Contention and retries are real, but observed throughput/latencies
are neither hard bounds nor an SLA.

The owner-coordinated Linux test rerun on 2026-11-04 passed **44/44** tests: 24
integration, 19 parent-plan, and one stress test, with no failures, errors, or skips.
The earlier 43/44 run exposed a malformed-plan test expecting the later snapshot
error when corrupting the first slot had already invalidated the source digest.
The test now checks that exact early digest error and separately corrupts a later
slot to retain exact whole-snapshot mismatch coverage. No production check was
weakened. This is targeted preview kernel evidence, not review-controller or
production host/model qualification.

## Limits and explicit non-claims

- The trust boundary is a per-user deployment, not hostile same-UID isolation.
  In-process providers/SPIs are trusted code. Source copies are immutable by API;
  digest checks do not make arbitrary external writers ABA-safe.
- Mixed parent-aware/older broker binaries sharing a ledger are **unsupported**.
  The additive tables still use preview schema version 1; there is not yet a
  binary-feature negotiation/guard that prevents an already-running old broker
  from ignoring parent/vector semantics. Stop every instance/worker and upgrade
  all brokers/hosts before owner-selecting parent-plan use. Tests cover cooperating
  instances of the same implementation, not rolling upgrades. A durable mixed-version
  admission guard remains a release gap; preserving scalar JSON alone is not a
  compatibility certificate for old brokers processing parent-plan work.
- Fixed JVM worker counts and durable execution reservations are bounded. A live
  hung provider retains its slot even after a deadline/heartbeat expires. Only
  confirmed process death (PID plus start identity) permits automatic reclamation.
  Arbitrary in-process code has no hard CPU/RSS sandbox. Hard external-process
  deadlines and descendant containment belong to the provider host; OS crash
  recovery does not claim orphan-process containment.
- Whole-plan retained-review/pending/queue/referenced-archive quotas reject *before*
  acceptance; no latest-wins deletion is used to make space. Per-plan staged bytes
  are checked cumulatively, and final referenced-archive capacity is rechecked in
  the commit. This is **not a total disk quota**: ledger/WAL/output/metadata bytes,
  concurrent staging and unreferenced crash/rollback archives are not counted in
  `maxArchiveBytes`. Up to 512-slot fanout can amplify orphan storage. Automatic
  retention/compaction and orphan GC are not implemented. Never delete archives
  while instances/workers may be using or publishing them; operator recovery would
  require stopping every instance/worker and preserving all durable work references.
  No cleanup tool or production bounded-disk claim is provided. A full/unavailable
  disk cannot be treated as successful delivery.
- Regular source/artifact files only. Symlinks, directory classpaths and inputs
  outside the manifest are not silently flattened into complete coverage.
- Directory fsync is unavailable through this Java implementation on Windows.
  SQLite FULL and file flushes are used, but universal power-loss durability is
  not certified on any platform by the process-restart tests.
- Tests use synthetic provider/source/config/model authorities where stated.
  SQLite, file capture, threads, multiple kernel instances and a forcibly killed
  worker JVM are real. They do not constitute production model-host qualification.

Run through the root's coordinated build: `./gradlew :kernel:test`.

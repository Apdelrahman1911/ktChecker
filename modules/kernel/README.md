# Durable kernel — preview implementation

This module implements `ktchecker/preview-1`, **not** a protocol-1 or production
model-host certification. Public DTOs are in `KernelTypes.kt` and `PlanTypes.kt`;
the trusted, nonserialized routing SPI is in `RoutingTypes.kt`.

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
   Its default-no-op `finished(effective)` runs outside locks whenever preparation
   began, including partial preparation failure and cancellation before provider
   entry. Implementations must idempotently revoke only that exact attempt's
   permits, not another client's/equal-input/later attempt's grants. A teardown
   exception retains raw terminal evidence and blocks broker validation with
   `prepared_input_cleanup_failed`.
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

Same-client plans retain this API and normalization behavior. Additional required
recipients require the explicit owner-granted routing SPI below; the default
`ReviewPlanRoutingAuthority.DENY` rejects them with
`cross_client_routing_unavailable` **before acceptance**. Neither a required client
ID nor a decoded grant/plan DTO authorizes routing or credential lookup.

### Owner-granted cross-client admission (not combined finalization)

Install `ReviewPlanRoutingAuthority` only in a trusted owner/controller. It must
resolve an exact privately registered initiating binding/request and return an
`AuthorizedReviewRoute` whose `origin` equals the entire authoritative declaration,
including its full required recipient set. Each `AuthorizedPlanRecipient` contains:

- Its own complete canonical **local** `RequiredReviewPlan`, exact request tuple,
  binding, independently verified model/context and nonempty required-rule vector.
  Local `requiredRecipientClientIds` names only that client; the private original
  declaration retains the complete set. The initiator's local plan must be its
  exact projection with only that list changed. No omitted or additional recipient
  is accepted. Routed plans must already have canonical input/slot ordering.
- Its actual independently enrolled `AdapterCredential`. The kernel authenticates
  all recipients before staging and again within the single commit, checking the
  entire owner/workspace/worktree/client/lineage/session/generation binding.
  The SPI does not resolve capabilities from caller/model-supplied client IDs.
- A `PlanRecipientPreparation` with the canonical local plan digest plus that
  recipient's captured-policy and execution-approval digests. The latter must bind
  the exact recipient/revision/approval identity and cannot be shared between
  recipients just because computation is equal.
- A mandatory `verifyForAdmission` callback which independently rechecks its live
  owner-created model/materialization and durably retains its captured policy and
  execution approval for **every** local slot, including cached-result admission.
  It runs before and after staging, outside locks, and must return the same exact
  identity. Null, throw or changed identity denies the entire route. Returning
  unchecked/copied digests is not an implementation of this trusted SPI.

The kernel bounds a route to 2–64 recipients in one owner/workspace and at most
512 total slots. Different worktrees require explicit independently prepared
recipient bindings/materializations; this does **not** integrate their changes.
The whole private route metadata must fit `maxInputBytes`. Retained-review quotas
sum every recipient's children; queued work/archive quotas deduplicate only exact
full provider+analysis identities. Pending limits and gate claims are checked for
**every** client. A collision with another recipient's existing request rejects the
whole new route rather than attaching that old plan as a partial second batch.

All local parents, children, route/member records, sticky lineage markers and every
required acceptance event commit in one SQLite transaction. Each client receives
only its local `plan_accepted` event/status and its own child IDs. The whole route,
other clients' references/model/policy/approval metadata, capabilities and private
route digest are not returned. Clients independently fetch, cancel, ACK and attest
model observation; one recipient cannot consume another's delivery duty.

Accepted exact origin **and recipient** retries read the durable batch before any
authority lookup or source staging, including after restart/authority loss. Reads
validate the private complete route/digest, every registered binding, local parent,
request, slot/review/work identity and required acceptance outbox. A missing/corrupt
other recipient cannot make a valid local prefix: `routing_plan_corrupt` fails
explicitly. No retry can add/remove recipients or recreate a missing owner grant.
These are corruption checks, not hostile same-UID tamper protection.

**Every lineage which accepted routed obligations is permanently blocked with
`cross_client_finalization_unavailable`.** Completion, cancellation, delivery,
restart, and later scalar/local parent plans cannot clear that marker. Local
`ParentPlanStatus.state == "complete"` means only that client's child computations
finished. A joint client-indexed current-source/model/captured-policy/approval and
delivery fence is **UNIMPLEMENTED**; separate local fences are not its substitute.
Integration still requires a new combined immutable snapshot and fresh complete
review. This bounded admission SPI is not a production routing controller, a
combined completion feature, model/import authority, or provider qualification.

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
ages survive schema2 restarts.
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
production host/model qualification. That is **historical pre-schema2/routing**
evidence, not verification of the new source. `KernelRoutingIntegrationTest`,
`KernelSchema2Test` and `KernelPreparedInputLifecycleTest` add synthetic authority
histories for route atomicity/retries/corruption/independent delivery/sticky denial,
v1 durable-history refusal, and attempt teardown. The routed crash test starts an
explicitly synthetic `-Xmx128m` child JVM.

The first owner-coordinated schema2/routing run on 2026-11-04 passed **65/68**
kernel tests and **27/27** review-controller tests. Its unchanged XML, source
digests, log and stress receipt are retained under
`build/evidence/kernel-routing/20261104-first`. Two legacy assertions still
expected schema repair or the wrong malformed-recipient error; one real failure
exposed WAL removal between a sidecar existence check and permission hardening.
The revised source refuses damaged schema2 tables without repairing obligations,
separates valid default-denied and malformed recipient cases, and holds the short
initialization SQLite connection through sidecar hardening. A bounded two-producer,
48-reopen regression was added.

That correction rerun passed **69/70** kernel and **27/27** review-controller
tests; its original log/XML/source bytes are retained under
`build/evidence/kernel-routing/20261104-rerun`. All three preceding failures passed,
but another concurrent reopen failed with `SQLITE_CORRUPT` inside the immutable
main-file probe. An actively checkpointed WAL database does not satisfy SQLite's
immutable-file assumption. The new correction removes that probe in favor of one
normal `mode=ro` WAL read transaction before any writable connection. It neither
catches/retries corruption nor independently establishes that durable disk bytes
were corrupt. New JDBC tests cover latest-WAL schema visibility, actual read-only
write denial, cold legacy refusal and bounded checkpoint/reopen concurrency.
Legacy byte comparisons now run with all JDBC connections closed and preserve
durable DB/WAL bytes, not transient SHM/read marks.

Separately, the JDBC pin is now **3.54.1.0**, including SQLite's upstream WAL-reset
fix absent from the old 3.50.3 pin. That dependency defect is distinct from the
invalid immutable-probe use; it is not claimed as the observed failure's cause.
The actual driver/native version and source ID have their own JDBC test.

The subsequent owner-coordinated Linux batch on 2026-11-04 passed **75/75**
kernel tests: 25 integration, 20 parent-plan, 16 routing, eight schema2, four
preparation-lifecycle, one stress and one dependency-identity test, with no failures,
errors or skips. Its log, XML, source bytes/digests and stress receipt are retained
under `build/evidence/integrated-kernel-cli/20261104-first`; its 20 captured kernel
source/test/build files match the tested freeze. The JDBC smoke actually loaded driver
**3.54.1.0** and SQLite **3.54.1**, recording the returned source ID in its XML;
these runtime identifiers are not source/binary authenticity or platform qualification.
Both concurrent reopen regressions and all eight schema2 tests passed. The same
batch freshly passed review-controller **27/27** and audit **58/58**; config's
existing **35/35** receipt was up-to-date, not rerun. **The overall batch failed**
at CLI test compilation (`SMARTCAST_IMPOSSIBLE`), so no CLI test execution or
whole-build success is claimed here. Earlier failed runs remain evidence, not erased
by this bounded preview correction run.

## Limits and explicit non-claims

- The trust boundary is a per-user deployment, not hostile same-UID isolation.
  In-process providers/SPIs are trusted code. Source copies are immutable by API;
  digest checks do not make arbitrary external writers ABA-safe.
- Ledger **schema2** is required. Existing v1, unknown-version and unversioned
  stores are refused before a writable connection, journal-mode change or DDL;
  **no migration, automatic reset, copy, drop or obligation waiver is implemented**.
  Normal `mode=ro` preflight checks version and required tables in one live-WAL
  transaction, and every operation rechecks version in its transaction. For
  **formats or missing tables refused by preflight**, preexisting durable DB/WAL
  contents and accepted history are not rewritten or discharged. SQLite may
  create/manage transient SHM/read marks or empty sidecar infrastructure to establish
  a read view: this is **not an all-files-byte-identical promise**. This byte-layout
  guarantee does not cover every later constructor failure: recognized schema2 stores
  can open writable SQLite before a later policy mismatch is reported. Normal
  checkpoint layout changes do not discharge logical obligations.
  No immutable main-file probe, separate raw DB descriptor or format
  marker substitutes for SQLite's locking and snapshot guarantees. New
  schema2 creation commits its complete schema/version before enabling WAL.
  An existing empty/in-progress first-open file is not permission to initialize it:
  retry only after the creating process commits; abandoned state needs owner review.
  Missing required schema2 tables fail with `ledger_corrupt`; no table is recreated.
  Old obligations and bytes must be preserved for offline owner reconciliation.
  A separately selected fresh store does not discharge them.
- **Stop every old instance/worker/process before any upgrade or reconciliation.**
  Mixed binaries, rolling upgrades and in-place replacement of a live ledger are
  forbidden. A version bump cannot stop an already-open v1 binary; schema2's new
  checks do not add dynamic fencing to old code. Cross-version migration and
  compatibility remain **UNIMPLEMENTED**. The unchanged scalar JSON/API is not a
  mixed-binary compatibility certificate. Tests cover cooperating same-version
  instances only, not safe concurrent upgrades.
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

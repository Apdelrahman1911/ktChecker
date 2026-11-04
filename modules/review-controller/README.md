# Owner review-plan controller — preview

An owner-only composition library for the kernel's atomic same-client parent API.
It is **not** a model-facing plan builder, verified CLI model importer, live-current
source authority, or production qualification. The lead-coordinated rerun on
2026-11-04 verified **27/27 controller tests passing** after a test-source-only
compilation fix; the limits below remain in force.

## Authority boundary

`OwnerPlanAssembler` accepts actual importer-created `VerifiedProjectModel`
capabilities for before/after, an explicit complete `OwnerCatalogInventory`, and
owner source/policy/execution callbacks. It calls `ProjectImpactPlanner` itself.
There is no changed-path argument, caller-selected context/rule subset, impact JSON
input, or decoded-model admission path. JSON, a certificate string, or a computed
identity does not recreate these capabilities.

The after model and optional before model must belong to the exact
owner/workspace/worktree/lineage. Before must use the same snapshot or the direct
parent of after; skipped or unrelated ancestry is unsupported. The materialization
capability must reference the **same after-model object**, exact whole manifest,
and normalized absolute registered capture root.

Every before/after compilation is required, even where impact says unaffected:
there is no certified unaffected-result reuse path. Removed compilations remain
unsupported slots. If a stable compilation ID changes its actual rule context,
the previous context also remains an unsupported slot; the new context cannot
silently retire it. Old slots retain their old scope and the whole new snapshot,
but not stale old member/artifact paths. An empty model produces one nonempty
unavailable administrative slot, never a guessed main compilation or vacuous plan.
No importer `fullProject` flag is promoted.

The **entire owner-installed catalog inventory** is applied to every context,
including disabled, unavailable, unimplemented, and unqualified rows. This may
over-require context-inapplicable rules; the preview intentionally does not infer
an applicability filter. Inventory completeness is the owner's attestation, not
proof obtainable from an arbitrary digest. Provider `supportedRuleIds` and user
narrowing never determine required coverage. The full recipient set must currently
be exactly the submitting client; additional recipients fail before preparation.

## Embedded owner usage

All `owner*`, model, capture, and policy variables below are **trusted host
responsibilities**, not helpers provided by this module:

```kotlin
val registry = OwnerReviewPlanRegistry(binding.ownerId)
val request = SubmitPlanRequest(patchId, idempotencyKey, privatePlanRef)
val materialization = OwnerPlanMaterialization(
    afterModel, registeredCaptureRoot, ownerRegisteredCaptureVerifier,
)
val assembly = OwnerPlanAssembler(
    OwnerCatalogInventory(ownerCatalogDigest, allOwnerCatalogRuleIds),
    ownerCapturedPolicyPreparer,
    ownerCapturedExecutionPreparer,
).assemble(
    binding, request, beforeModel, afterModel, materialization,
    requiredRecipientClientIds = listOf(binding.clientId),
)
val preparedRequest = registry.register(assembly)
val kernel = KernelService.open(
    dataDir, providerCoordinator,
    planAuthority = registry,
    preparedInputObserver = ownerPreparedInputObserver,
    resultPolicy = ownerCapturedResultPolicy,
    snapshotGate = SnapshotGate.UNAVAILABLE,
)
val parent = kernel.submitPlan(clientCredential, preparedRequest)
```

Only `AssembledReviewPlan` enrolls in the registry. Its immutable `plan` DTO may be
serialized as evidence; `provenance` retains live model/planner facts, authority
identities, and per-slot explanations without serialization. Executable slots have
empty `reasons`; impact widening explanations are not execution failures. An
unsupported slot has explicit blockers and no executable identity. Neither a
successful assembly nor a parent `complete` status authorizes finalization.

## Preparation callbacks

The defaults deny preparation. Missing policy or execution preparation retains an
unsupported obligation rather than omitting it. Policy preparation also runs for
administrative slots, so an owner can retain their exact per-client classification
policy. Execution preparation never runs for already unsupported slots.

- `OwnerPolicyPreparer` receives a fresh `PolicyPreparationRequest`. Return a
  `CapturedPlanPolicy` referring to **that request object**, the full exact client
  binding, and the captured configuration/revision identity. The configuration
  must equal model ingress configuration; rebinding requires a new verified model
  import, not copying a context digest.
- The only policy mutation allowed is the activation evidence added by
  `request.withActivation(...)`. `PlanActivation` must account for every required
  rule exactly once, including inactive rules. No source, rule, scope, model,
  compiler, classpath, configuration, library, baseline, or process field may change.
- `OwnerExecutionPreparer` receives the policy-enriched input and its own fresh
  request object. `CapturedExecutionIdentity.inputKey` binds this input **before**
  the execution digest addition, avoiding a circular key. Its binding and toolchain
  must match exactly. Only `request.withProfiles(...)`'s execution-plan policy
  addition is sanctioned; `input.execution` and output paths cannot be injected.
- Each `retainForAdmission(finalInput)` callback must retain/restore the captured
  policy or approval and all worker records keyed by **full client binding plus
  final input identity**, returning the same verified revision/approval identity.
  Do this durably before returning where accepted work must survive restart.
  An analysis-key-only cache must never overwrite another client's policy or
  approval. The controller checks the identity returned, not an arbitrary store's
  implementation or durability.

Collections are detached and unmodifiable. Original compiler argument, classpath,
and compilation membership ordering are preserved. Exact context identities are
computed only after both sanctioned additions; `exactPlanContextIdentity` and
`executionProfilesDigest` are pure identity helpers, **not** execution authority.
The source digest binds the entire manifest. The generated-input digest binds the
entire verified generated-input list, including task, membership, properties,
inputs/outputs, roots, and capture state.

`TrustedExecution` profiles and their `approved` booleans are evidence only. On
each worker attempt the host must still issue the provider's nonserialized
execution authority through `PreparedInputObserver`. Kernel freezing covers
regular classpath artifacts, not arbitrary compiler/runtime paths. The owner must
verify and retain the exact toolchain/provider runtime and approval; this module
does not launch a compiler or grant a process-execution permit.

### Deliberately limited executable inputs

Until verified path rewriting exists, nonempty friend artifacts, auxiliary
artifacts/input files, or compiler plugins make the slot unsupported. Arguments
are preserved, never stripped or rewritten. The only accepted path-free grammar is:

- Flags: `-progressive`, `-no-stdlib`, `-no-reflect`, `-Werror`, `-nowarn`,
  `-java-parameters`.
- Values, as `-flag value` or `-flag=value`: `-language-version`, `-api-version`,
  `-jvm-target`, and `-module-name`, with bounded format validation.

Every unknown flag/value, response file, explicit source/path argument, plugin
option, or unrecognized auxiliary input is unsupported. Recognizing a flag is not
proof that every compiler/provider accepts it or that its result is qualified.

## Readiness, registry, and limits

The registered-capture verifier must prove the owner's coherent immutable capture,
not echo the supplied manifest or scan a mutable worktree. It runs before callbacks
and again before/after readiness checks. It must return a stable authority/ABA
identity; source, policy, approval, and toolchain authority changes reject assembly
or later resolution. These checks do not establish a live-current source fence.
All callbacks run outside registry map locks and kernel transactions. Owners remain
responsible for immutable retained authority and joint final config/source fencing.

`OwnerReviewPlanRegistry` is **bounded and in-memory only**. References are immutable
owner-global entries, bound to the entire client and patch/idempotency/ref tuple.
Equivalent registration retains the original object/callbacks; changed definition
or authority provenance conflicts. Wrong bindings/tuples cannot trigger readiness
callbacks. Every valid resolve reverifies readiness outside the map lock. There is
no deletion, eviction, retargeting, or decoded-file capability restoration API.

After a process restart, new admission needs freshly established live authority.
Already accepted exact kernel retries use the durable kernel definition without
the resolver; workers and cached-result policy classification still need their
separately retained owner policy/execution records. This module supplies neither
that persistent host store nor a safe generic capability-deserialization mechanism.

Plans are bounded to 512 slots, 50,000 distinct nonempty catalog rules, a default
4 MiB complete serialized definition, and 1,024 registry entries. Whole-vector
limits reject rather than truncate or register a prefix; preparation side effects
are not kernel acceptance. Kernel quotas apply independently. Cross-client routing,
CLI full-project ingress, production model-host qualification, mixed-version
brokers, and final live-current authority remain out of scope.

## Prerequisites for trusted CLI exposure

- Add an **owner-only typed** preparation entry point over live verified models
  and registered captures. Model-facing RPC may forward only credentials plus
  the exact registered request, never a model/impact/plan JSON authority assertion.
- Establish real owner-approved, bounded Gradle exporter execution and complete
  model-input verification. Capture the client's policy before importing its exact
  configuration identity; do not loop scalar preparation, narrow its rule subset,
  sort imported membership, or flip its partial-project flag.
- Supply model-derived compilation/source-set/task ownership and a reviewed
  provider-toolchain identity bridge. **Currently these identities do not compose
  directly:** project-model hashes `ModelToolchain`, while `KotlinJvmCompilerProvider`
  expects the distinct `ktchecker/kotlin-jvm-toolchain-3` installation/runtime
  digest. Profiles alone cannot bridge that mismatch. Rewriting the imported
  `toolchainDigest` is forbidden; direct JVM compiler execution must stay unavailable
  until an independently verified bridge or reviewed ingress schema exists.
- Persist/restore every final child policy, approval, profile, and necessary live
  model binding before cached-result admission or worker startup. Keep per-client
  approvals separate from shared computation records. The observer receives
  original/effective inputs, not a client binding; its shared profile lookup is not
  a substitute for each client's admission preparation.
- Retain unknown compiler/auxiliary/plugin inputs as unsupported. A separately
  reviewed joint current config/source/vector fence is still needed for a final
  action; matching captured identities and successful assembly do not provide it.

## Validation status

The initial static freeze contained 23 authored tests covering real registered overlays/file checking, model-capability
composition, conservative context retention, callback mutation/retargeting,
immutable output, registry binding/revocation, bounds, and kernel admission.
They use **synthetic** export receipts, dummy artifact bytes, policy/approval
callbacks, and non-executing providers—not actual Gradle/compiler qualification.

Four subsequently added `ControllerKernelIntegrationTest` scenarios bring the
authored total to **27** without changing those 23 assertions. They exercise actual
worker threads and original-to-private-archive binding, whole/full and incremental
source vectors, two-client shared work with independent ACK/observation, retained
removed/retargeted contexts, and a deliberately **nonauthoritative** vector probe.
The synthetic provider reads captured bytes but marks every rule unimplemented,
untested, and unqualified, with no successful coverage certificates. Final actions
must remain denied. Kernel reopen tests retain live capabilities in the same JVM;
they are not durable controller capability restoration or real model-host proof.

The first coordinated build compiled production but failed to compile the
assembler tests because four fixture lambdas needed explicit `ModelCompilation`
parameter types. No controller test executed in that failed build. The narrow fix
changed only those parameter declarations, not production or assertions.

The lead-coordinated rerun on **2026-11-04 at 10:26 UTC** compiled the test sources
and executed **27 tests: 27 passed, 0 failed, 0 errors, 0 skipped**. The three JUnit
XML reports independently confirm 16 assembler, 7 registry, and 4 kernel-integration
tests. This is preview controller evidence with the synthetic authorities/providers
described above, not actual Gradle-to-compiler integration or final authorization.
Reproduce only through the lead's coordinated build: `./gradlew :review-controller:test`.

# Owner configuration and suppressions

`modules/config` implements a **preview** Kotlin/JVM policy engine. It does not edit
application source, run a compiler/build, prove absence of defects, or implement a
host-isolated security boundary. Its read APIs are `inspect`, `capture`,
`findingSelector`, `explain`, and `apply`. All policy changes use `mutate` with a
separate, authenticated explicit-owner permit. Review, cancellation, finding
delivery, ACK and model observation never authorize a policy change.

## Authority

`ConfigBinding(ownerId, workspaceId, worktreeId, lineageId)` comes from trusted
registration. It is not inferred from a path, Git repository, latest terminal,
model-selected consumer, or JSON `approved` flag. A `ConfigMutationRequest` binds
that namespace, a target layer, **both** expected layer revisions/content hashes,
the complete action and a request ID. It contains no owner-role boolean.

The default `MutationAuthority` denies every mutation. A trusted controller can
use `HmacOwnerAuthority`, enrolling exact `ClientIdentity` sessions and allowed
bindings. Only `issueForExplicitOwnerAction` creates a permit for an enrolled
owner session. The signature binds principal/session, request digest, issuer,
nonce and bounded expiration. Changing the rule, reason, payload, layer,
project, worktree, lineage or CAS token invalidates it. The permit is checked
again inside the storage lock; replay after a successful mutation is stale.
An application may instead implement `MutationAuthority` using a protected
owner-control service. That implementation is trusted code, not a tool argument.

The local CLI composition exposes owner operations separately from review RPC
and MCP tools. Its explicit `--owner-action` command is a **cooperative owner
workflow**, not proof that an unrestricted same-user shell was operated by a
human. A model must never enroll itself as an owner or disable warnings to
manufacture a passing review.

**Threat limit:** same-UID model/project/plugin processes with unrestricted shell
access can invoke the local CLI, read credentials, replace state or ignore
advisory locks. HMAC, opaque IDs, localhost and `chmod 600` do not isolate such
processes. Adversarial enforcement needs controller credentials, registration,
receipts and configuration outside model/project execution capabilities, with
appropriate OS/process/sandbox separation. This module does not supply that
separation. See [protocol.md](protocol.md#10-security-and-validation-gates).

## Shared project policy and local precedence

The explicitly configured broker/control state root holds one `shared.json` per
owner/project namespace and separate local documents keyed by owner, worktree
and lineage. It must be outside reviewed application sources. Shared project
policy applies across that owner's registered worktrees; local policy does not
leak to another owner, project, worktree or lineage.

For source-controlled/team defaults, `exportShared` returns a serializable
`SharedProjectConfig`; a user can deliberately store/share that document. An
explicit authorized `ImportSharedConfig` action imports it into the recipient's
project namespace in one shared-layer transaction. Local overrides, credentials
and private audit/session records are not exported. Review does **not** implicitly
import a source-controlled file or interpret source comments as owner consent.
Portable broad policies can be shared across owners; narrow identity anchors are
origin-bound and require a new, verified acceptance in a different namespace.

Precedence is explicit:

1. Local per-rule settings override shared settings by canonical rule ID.
2. Local suppression records override shared records with the same record ID.
3. `MaskSharedSuppression` explicitly hides an inherited shared record.
   `RemoveSuppression` removes only a record in its selected layer;
   `UnmaskSharedSuppression` removes a mask. Removing a local override exposes the
   shared record again unless a separate mask remains.
4. An **expired local override still shadows the same shared record**. Expiration
   does not silently restore an older indefinite risk acceptance.
5. Among matching active records, local comes first, then narrower specificity
   (finding, range, symbol, file, source set, module, target, project), then record
   ID for deterministic ties. A disabled rule is separately classified before
   suppression; matching suppressions precede an explicit baseline.

`inspect` retains expired, masked and overridden records. `explain` returns
candidate match states, canonical identity, disposition, chosen record/layer and
justification. Suppressed findings remain inspectable; they are not deleted.

## Scope, reasons and identity

Scopes are project, target, module, source set, file, symbol and range. Selectors
choose either a registered rule or a canonical exact finding. Alias resolution
uses a trusted, immutable exact `RuleAliases` registry; unknown IDs, ambiguous
aliases and wildcard guesses are rejected. A rule needs a real implementation
digest for narrow policy. Missing implementation identity is **unavailable**, not
`sha256(ruleName)` dressed up as proof; provider/catalog upgrades must change that
identity. Broad rule acceptance deliberately applies to future findings in its
chosen scope.

Each suppression requires a nonempty justification and one of:

- `accepted_risk`: classified as **accepted**, not fixed or false-positive proof.
- `reported_false_positive`: classified as **suppressed**, a user report rather
  than a detector's proven correctness claim.

An optional epoch-millisecond expiration is end-exclusive: the record is expired
when `now >= expiresAtEpochMillis`. New individual records must expire in the
future. Imported expired records remain inspectable but inactive. No expiration
or review API changes the stored configuration as a side effect.

Finding fingerprints are versioned canonical SHA-256 projections. The engine
does **not** trust `Finding.fingerprint`, a symbol name, excerpt, line similarity,
Git rename heuristic or a supplied `semanticDigest` as identity evidence.
Without verified facts, an exact finding binds registered namespace, rule and
implementation, provider, compilation context, severity/kind/certainty, logical
path, **raw file-byte digest**, range and evidence digest. File/range rule scopes
also bind raw bytes. Rule-selecting file/range/symbol acceptances retain the
original implementation digest, rather than silently adopting an upgraded rule;
an older narrow record missing that binding remains inactive. Positions use
1-based lines, 0-based UTF-16 columns and
end-exclusive ranges; paths remain significant.

The default `FindingIdentityVerifier` supplies no movement proof. A trusted
provider adapter may certify a particular immutable snapshot, owner/project/
worktree/lineage, canonical rule, provider artifact, stable symbol, relevant
material/body/bindings, distinct evidence occurrence and evidence content.
Only matching verified symbol **and material/evidence identity** can preserve a
narrow acceptance across line movement or a rename within that binding.
Certificate/snapshot IDs can change; the independently verified stable identity
must not. A changed body/binding, evidence, occurrence, provider artifact or rule
implementation does not inherit the suppression. A stored certificate cannot
verify itself; missing current facts leave the finding open. This preview does
not automatically enable a compiler-backed identity verifier.

Policy matching additionally requires the exact non-null frozen
`AnalysisInput.context.configurationDigest`, registered snapshot namespace,
finding/file byte identity and `Finding.context == input.context.scope`. Another
target, lineage policy or same-worktree configuration cannot be substituted.

## Accounting is not execution success

`open`, `accepted`, `suppressed`, `disabled`, `baselined`, `fixed` and `unavailable`
are distinct. Explicit baselines use canonical narrow finding selectors. Source
movement, cancellation, changed configuration or an unrelated/partial result
does not resolve a finding. Provider-supplied disposition strings cannot certify
fixed/baselined/unavailable status. A separately injected trusted
`FindingLifecycleVerifier` needs a current snapshot/policy/provider/fingerprint-
bound lifecycle certificate; it denies by default. Resolution requires comparable
successful coverage and absence proof in that trusted adapter, not a config edit.

`apply` changes only finding policy accounting. It **never rewrites** provider
status, coverage, failure reasons or raw diagnostics. Registered protected checks,
current compiler errors, build/execution/test failure evidence and failed
verification cannot be waived by a suppression, baseline or rule disable.
An accepted static warning does not turn an unavailable compiler, failed build,
crashed worker or missing execution certificate into success. Required coverage
must still be enforced by the kernel separately; an empty/disabled finding list
is not a passing review.

## Concurrent edits, durability and active reviews

Every cooperating reader/writer uses one project-wide lock, including all local
overlays: a canonical-path JVM coordinator prevents overlapping same-JVM file
locks, and an OS `FileLock` coordinates separate processes. Both layers are read
and CAS-checked under that lock. A mutation targets **exactly one layer/file**;
there is no partially committed multi-file batch.

A successful write forces a same-directory temporary file, atomically replaces
the target, and forces the parent directory. Newly created directory links are
also forced. There is no non-atomic rename fallback. Malformed, unknown-schema,
oversized, namespace-confused or symlinked policy is an explicit error, not empty
configuration. Unsupported atomic replacement/directory durability is reported;
it is not silently advertised as durable on every JVM filesystem.

`ConfigWriteException.possiblyApplied == false` means replacement did not happen.
If replacement happened but final durability could not be confirmed,
`possiblyApplied == true`: **inspect before retrying**, do not replay an old CAS
request or assume rollback. Reopening reads the complete installed old/new file,
never a partially serialized policy. Policy history is bounded (10,000 mutations
per layer and 16 MiB documents); reaching the bound requires explicit migration.
The audit chain records actor, request digest/ID, revision and timestamp, but is
not tamper-proof against a process able to rewrite the entire state.

`capture` freezes effective policy for a review. Store its complete revision token
and policy digest in the immutable analysis identity. `exportSnapshot` supplies
serializable `FrozenConfig` with the exact raw shared/local documents;
`restoreSnapshot(frozen, expectedTrustedToken)` recomputes schema, namespace,
content/generation/revisions and expiration activity at the captured time.
Recovery never substitutes today's policy for an accepted historical review.
An unavailable old rule registry is an explicit recovery error. This is a trusted
recovery API, not a model-supplied policy capability.

Every configuration change changes the policy digest, even when another layer
masks its visible effect. Expiration also changes effective policy identity
without manufacturing a stored mutation. Historical reviews keep their captured
accounting; a current final gate must reject the old policy.

Use `withCurrentConfig(binding, fullRevisionToken, block)` only for a short final
gate with lock order **config → snapshot/source → kernel transaction**. Do not
hold a database transaction when entering external locks, run provider analysis
inside the fence, or recursively enter config storage. Read `check.matches` in
the innermost callback: it also checks an expiration/clock drift that can occur
while acquiring source locks. The numeric-revision overload is a cooperative
convenience, not the full strict content/generation/expiration fence. No successful
gate remains current after a later edit, policy change or expiration.

## Tests and release limits

Module tests cover signed authority/payload/scope tampering, forged lifecycle
labels, alias/range/identity regressions, explicit shared import, local precedence,
expiration, old-review archives, before/after-replace fault injection, separate
store and independent-JVM CAS races, plus seeded property-style owner histories.
The independent audit also exercises cross-project/lineage and stale-policy
boundaries. See actual build/test reports for execution results; this document
is not evidence that every filesystem, OS, host controller or provider adapter
has been certified.

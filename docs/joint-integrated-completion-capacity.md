# Joint integrated completion: declared scope and finite capacity

## Status and non-claims

The optional `IntegratedCompletionBarrier` is a bounded, fail-closed completion
boundary, **not a sustained release-scale history implementation**. Its v2 audit
codec removes representation duplication, not history obligations. Its default
authority denies. Real live source/configuration/integration provenance and an
actual guarded final-delivery receipt must be supplied by owner-installed,
nonserialized host capabilities; this implementation does not supply them.

A success is restricted to `declared_routed_lineages_and_catalog`: the complete
historical routed closure, every member's newest whole plan in one final integrated
batch, every declared final context, and the complete owner catalog. It is not an
unqualified project-wide success. Every member retains independent policy,
insertion, and model-observation obligations. Earlier reviews and parent patches
remain obligations, including failed, pending, cancelled, and unavailable history.

## Actual 1,663-ID test inventory

The test resource `modules/kernel/src/test/resources/joint-completion-full-catalog-1663.txt`
contains **every ID** from these four 2026-11-05 source inventories, sorted uniquely.
There is no activation, applicability, target, or provider-support filtering.

| Source | IDs | SHA-256 of exact source bytes |
| --- | ---: | --- |
| `data/rules-core.json` | 797 | `e49d7317951077d55a68df30387915c263f21dd3972caecf58f47138d885e014` |
| `data/rules-libraries.json` | 125 | `3e671d030bb1734242c4f51c2de8723c11abb002b9c8a743176e7cc957592c9e` |
| `data/rules-platform.json` | 716 | `0bfffdd0749353cb5b9b41485e29456037a5c669e232c6915fa5ca91aa6127af` |
| `data/rules-ecosystem.json` | 25 | `261fb5c1fadbed7b9ae3b7768cba19e9716ba5067c4932e671183d13513fd2ac` |

The LF-terminated UTF-8 ID resource is 75,621 bytes, SHA-256
`4c00ec83059325b8ee2fac672e6c53948746bda6920d900a9d1942ce1b03520d`.
It is a pinned **inventory fixture**, not a production authority or rule-coverage
certificate. Catalog changes require an explicit new fixture/provenance review.
The full-inventory provider, certificates, model, policy, integration fence, and
delivery effect in these tests are deliberately **synthetic**. Passing such a test
would not establish implementation or qualification of the 1,663 actual rules.

## What the present limits actually bound

| Default bound | Scope of the bound |
| --- | --- |
| 64 members / 128 routes | Entire transitive historical route closure |
| 1,024 reviews / 512 parents / 8,192 events | Totals across the entire closure, not per member |
| 32 MiB history bytes | Conservative sum across the closure of stored inputs, requests, raw/effective results, validation/certificates, parents, routes, and event payloads |
| 65,536 coverage pairs | Separately: each member's **cumulative historical** `requiredRuleIds` counts, and the final context-by-catalog vector |
| 4 MiB audit bytes | Each expanded request, check, encoded audit and readback; new v2 prepared records also reserve the largest legal terminal-state/receipt encoding |
| 1,024 cuts | Aggregate of all retained v1 AND v2 joint audits, including not-ready prepared cuts |
| 5 seconds action time | Receipt acceptance bound, not an interrupt/cancellation or automatic recovery mechanism |

The history-byte sum intentionally charges shared work's raw input/result again
for every consuming review. It is not a measurement of unique physical DB bytes.
The review, parent, event, history-byte, audit-byte, and cut bounds can therefore
deny **before** the coverage-pair ceiling below.

At 1,663 rules, the cumulative per-member pair ceiling permits at most **39
single-context reviews** (`39 * 1663 = 64,857`); the 40th would require 66,520.
With four contexts per whole patch, it permits at most **nine total whole rounds**
(`9 * 4 * 1663 = 59,868`), **including the final integrated round**: at most eight
previous four-context rounds. The tenth needs 66,520. These are arithmetic upper
bounds, **not evidence that that many complete rounds fit the other limits**.

The legacy v1 audit embeds the full scope twice, in `cut.scope` and
`request.scope`; each scope repeats a `RuleContext` object for every rule. Using
the exact authored fixture strings, a four-context 6,652-pair vector alone is
1,010,923 canonical JSON bytes (2,021,846 for both copies). A nine-context
14,967-pair vector alone is 2,461,663 bytes: two copies need 4,923,326 bytes,
already beyond 4 MiB before any history, checks, or receipts. These are stdlib
JSON data-size calculations, not Kotlin runtime measurements.

The v2 metadata representation stores **one** scope, an ordered canonical
context dictionary, the exact full catalog ID vector, and the explicit
`all_declared_contexts_x_full_catalog` mode. It contains no coverage mask,
applicability filter, provider subset, or history summary replacing old records.
Cut and request bind the same restored scope and original semantic digest.
Public cut/scope/request/fence/action DTOs and the `integratedScopeDigest` recipe
are unchanged. Input vectors with holes, duplicates or noncanonical order are
rejected, not normalized into a different successful scope.

The decoder selects exactly one typed serializer from the validated requested
cut ID/key version, then requires that exact embedded format and cut ID. It does
not build a generic JSON tree to discover a version or try alternate decoders.
Before canonical compact reencoding it applies all original collection and nested
attestation caps, then checks the unchanged pair ceiling and **exact expanded
request bytes**, including all frontiers, historical obligations, routes and the
scope skeleton. It counts canonical UTF-8 context/rule strings with their actual
JSON escaping. Cheap caps precede default-expanding serialization; expanded-byte
denial precedes canonical dictionary sorting. Compact bytes are separately
bounded, and canonical reencoding must match the original record **before
Cartesian allocation or scope hashing**. Unknown/missing modes, unknown
versions/fields, duplicate keys, omitted defaults, noncanonical order and partial
alternate decoding do not acquire authority.

That byte-before-allocation guarantee applies to **compact dictionary decoding**.
The existing live `readFacts` path is unchanged: it caps the pair count before
constructing the final vector, then bounds the expanded request after construction
and scope hashing. This codec increment does not supply streamed fresh-scope
construction or hashing, nor does it change inherited v1 readback behavior.

Reasons and attestations are NOT truncated or factored away. In the nine-context
pending fixture, the 29,934 `current_coverage_missing` reasons alone occupy
4,025,305 canonical JSON bytes, before the additional pending/delivery reasons,
catalog, checks and all20 review/parent obligations. Its proposed v2 audit has
little headroom under 4 MiB; the exact current Kotlin control remains the test
of whether it fits. More/larger diagnostics can still visibly deny. Expanded
scope/request and encoded-audit budgets are independent; the v2 codec does not
promise that an arbitrary external serialization of the full cut or reconstructed
legacy doubled audit is under 4 MiB. No model-facing transport is added here.

New v2 preparation reserves the exact maximum legal terminal representation:
`outcome_unknown` plus a full valid receipt with the fixed cut/scope identities.
The placeholder receipt is only size arithmetic and is **never persisted**.
Insufficient room denies before any claim/effect; a real receipt is still
mandatory for completion. No bound is raised to make a transition fit.

Legacy `joint-v1` records remain readable at their original metadata keys and
count toward the same cut quota. Every legacy state, including `prepared`, is
**audit-only**: complete/status give `joint_legacy_audit_only_no_permission`
without owner resolution, readiness, fence, claim, action, rewrite or migration.
No old claim is replayed or reset. Only an explicit new `joint-v2` preparation
can request a newly verified scope, and any retained nonopen member gate still
prevents its completion. Audit bytes cannot restore live owner authority.

Aggregate history/cardinality budget overflow throws `joint_history_limit`; an
individual missing/oversized route can throw `joint_route_unavailable`. An oversized
full audit throws `joint_audit_limit`; exhausted cut storage throws `joint_cut_limit`.
No prefix is accepted, no earlier obligation or cut is silently removed, no cap
is raised, no member is claimed, and no final effect runs to overcome a prepare
denial. Owners must see the denial; retrying unchanged data cannot improve it.
Unknown/claimed completion is separately nonreplayable and cannot be reopened to
work around capacity. No cancellation of another task is a capacity mechanism.

## Authored controls and their exact claims

The predecessor R2's exact26 controls (22 kernel, four controller) were executed
by the lead with no failures/errors/skips; its focused result is retained at
`build/ci-evidence/joint-barrier-r2-focused-20261105/summary.json`. That run includes
the old nine-context **audit-limit denial**, not evidence for the new codec.

The codec successor has 36 authored controls across the two kernel suites and
unchanged controller suite:25 barrier,7 codec,4 registry. **Zero current-codec
tests were executed at private source handoff**. Its full-inventory controls are:

1. A fresh two-client, four-context final routed batch uses all 1,663 IDs in every
   context at **unchanged default limits**. It checks that missing peer model
   observation denies, then checks the full vector, atomic claim, retained
   history, one guarded synthetic effect, and no replay. This is a small-route
   control, not a sustained-history benchmark or real compiler/host evidence.
2. Two complete one-context historical/current batches retain four child reviews
   **and** four parent obligations. The default path is authored to complete;
   a **smaller owner pair bound** admits the final-vector size but rejects the
   cumulative history without mutation. Separate assertions state the 39/40 and
   nine/ten default arithmetic ceilings. This does not run 40 large batches or
   claim that their byte budgets fit.
3. A nine-context two-client route has **workers not started**, to isolate
   representation capacity from large result-history bytes. It is authored to
   retain exactly one compact **not-ready** cut, the full14,967-pair external scope,
   all18 pending review reasons and all29,934 missing-coverage reasons. All18
   reviews,9 queued shared works,2 parents and1 route remain; no claim, prefix,
   cancellation or effect occurs. Pending history is not successful or waived.

Additional codec controls compare the complete restored full1663 semantic scope,
digest and historical vectors; reject malformed/non-Cartesian/duplicate/unknown
representations; test exact Unicode/escaping byte boundaries and preallocation
expansion denial, including 65,537 omitted-default contexts and every original
nested attestation cap before canonical reencoding; reject wrong requested
ID/version reads; retain both original ceilings; and read all terminal states
back at the exact reserved bound. Barrier controls cover every legacy state's
read-only behavior, mixed-format quota, and near-cap actual synthetic receipt
persistence for both complete and post-effect unknown. No placeholder receipt
or legacy DTO is permission to perform a new effect.

## Proposed release-scale fix — NOT implemented in this boundary

Increasing constants, dropping old obligations, filtering the 1,663-ID catalog,
or treating a clean latest batch as a history waiver are not acceptable fixes.
A realistic successor needs a separately reviewed, lossless representation and
verification protocol, not merely larger synchronous scans:

1. **Factor and stream the exact scope.** The v2 metadata codec implements only
   the lossless dictionary/single-scope representation with unchanged external
   DTOs and bounded full reconstruction. It does NOT implement streamed semantic
   hashing, large external vectors or resumable history validation. Those require
   additional protocol work and proof of the exact current digest recipe; no
   mask, applicability exclusion or provider-supported subset may be introduced.
2. **Retain an exact, paged obligation log.** Keep every review, parent, routed
   edge, certificate, failure, finding, required event, insertion, and observation
   durably addressable. Build bounded immutable page manifests with counts,
   sequence ranges, and content digests over the complete prefix. A root digest
   or cached "OK" is not authority: its construction/validation must be tied to
   actual ledger facts and all immutable client bindings. Persist unresolved and
   unavailable obligations explicitly; a newer success never overwrites them.
   This is lossless indexing/verification, **not deleting or retiring history**.
3. **Share and page the existing validator.** Refactor historical validation into
   one common incremental implementation, preserving current certificate,
   coverage, findings, ancestry, route, and parent checks exactly. Prefix caches
   require exact dependency identities: provider/policy certificates, source and
   configuration generations, bindings, catalog versions, record digests, latest
   integrated plan/composition identity, and frontiers. Finding-resolution state
   must remain dependent on the exact current ancestry/integration proof. Every
   supported publication, cancellation, policy restoration,
   recovery, or admission that can change an outcome must transactionally
   invalidate/update the relevant root. A skip, hole, mismatch, stale root, or
   interrupted validation leaves the prefix unavailable, never implicitly clean.
4. **Verify before the short joint claim.** Complete resumable bounded-page
   history verification outside the irreversible action interval, anchored to
   an exact ledger snapshot/frontier. Under the one real composite owner fence,
   compare the exact root/count/version vector, latest all-member integrated
   plans, closure, and independent receipt/observation proofs in the atomic
   claim transaction. Transactionally maintained receipt-prefix indexes would
   need separate proofs that they include every required event's generation and
   payload binding; receipt counts alone are insufficient. Owner integration
   provenance must disposition **all** paged historical obligations, not just
   their latest representatives. A changed frontier discards/restarts affected
   verification; it must not authorize a partial vector.
5. **Keep all existing no-replay boundaries.** Live nonserialized authority and
   readiness remain mandatory; an audit/manifest cannot reconstruct them after
   restart. The owner action stays outside DB transactions, inside the composite
   fence, under all-client persistent claims. Crash/throw/late/unknown retains
   the freeze. Resumable history verification is not resumable final delivery.
   No unapproved generation reset or automatic reconciliation is introduced.
6. **Require separate migration, race, and scale qualification.** A maintained
   obligation/root index or new wire/audit version needs explicit root approval,
   migration/bootstrap/corruption handling, and a full writer-path audit before
   adoption. Test all 1,663 rules over multi-context histories beyond current
   ceilings, including 40/100+ rounds and multiple recipients; compare streamed
   results to an independent full enumeration. Exercise recovery, pending and
   historical failures, stale caches, source/configuration ABA, receipt gaps,
   old-client admission, unknown delivery, and no-replay races. Measure bounded
   memory, transaction duration, storage growth, and end-to-end work explicitly.

No paged/streamed history protocol, index, migration or retirement proof is part
of this codec increment. Until those are implemented and independently verified,
visible finite-capacity denial remains a product/usability gap rather than
release-scale completion support. Metadata deduplication alone does not solve
the cumulative-pair, result-history, diagnostic-size or synchronous-validation
limits described above.

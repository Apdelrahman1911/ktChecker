# Exception observability policies: additive THREE corpus

**Source candidate only; Kotlin-uncompiled, detector/native-unexecuted, not
adopted, admitted or qualified.** Separately recorded authored-source checks do
not populate this immutable plan's execution stages.

The closed selector `exception-observability-policy-v1` adds three existing exact included156
syntax/LIGHT mappings. **All three remain opt_in, info, intent_dependent**:
RethrowCaughtException and ThrowingExceptionsWithoutMessageOrCause are style;
PrintStackTrace is policy. A named selection acknowledges each convention,
not universal correctness, safe logging, execution consent or an activation
change. There is no implicit, arbitrary subset, multi-cohort or all selector.

| Existing rule | Bounded predicate and retained independent limits |
| --- | --- |
| `RethrowCaughtException` | First-child throw of exact caught-name text, only within the trailing contiguous violating catch suffix; whole-throw entity. Parenthesized rethrow is missed. Later worked catch exempts earlier rethrow; earlier work does not waive a trailing rethrow. The documentation's broader any-work exemption is not the actual predicate. |
| `PrintStackTrace` | Exact `Thread` receiver plus `dumpStack` call reports only `dumpStack()`; caught-name `printStackTrace(` sibling reports only its receiver. Benign non-JDK Thread is an anticipated false positive, while caught aliases and qualified JDK Thread calls are missed. No destination, type, alias, taint or redaction analysis. |
| `ThrowingExceptionsWithoutMessageOrCause` | Any no-value-argument call whose callee text is in the genuine `exceptions` list; whole-call entity, no throw/type requirement. A same-named String factory is an anticipated false positive and a real import-aliased exception is missed. The enabled `exceptions: [IllegalStateException]` control on identical positive bytes keeps only the state-exception finding. |

## Fixed bounded plan

- Three new rules: **27 authored analyses / nine independent controls**.
- Each rule keeps positive, same-rule enabled negative, disabled-positive
  configuration, pinned-version replay, genuine upstream file suppression
  with unchanged unsuppressed neighbor, negative child-snapshot regression,
  then three independent controls.
- **37 total calls**: 27 real detector analyses, one fresh CLI probe, six
  Common/Native abstentions and three changed-configuration authorization
  denials. At most **28 native launches**, **31 callbacks**, **113** success-path
  progress events. Hard maxima remain **40 / 1,200,000 ms / 60,000 ms / 124**.
- Source-authored expectations: **13 alert-bearing / 14 zero-finding analyses,
  19 finding observations, 17 unique rule/source sets, 21 rule/source/config
  sets**. These are plan counts, not actual diagnostics or natural precision.
- The nine controls retain two anticipated false positives, four anticipated
  false negatives, one predicate boundary, one opt-in policy limit and one
  genuinely configured policy boundary. A matched expectation for a miss
  never closes that heuristic gap.

Reservation precedes provider `executionPlan` construction and isolated runtime
preparation, not all source work. Snapshot sealing/materialization and
`AnalysisInput` construction occur first but remain in the original monotonic
aggregate; they do not refresh its deadline.

Plan identity SHA-256: `54c13bc47181b6922365fc37b718b9d6d87a057add32b1a9170972484b95155b`.
Hashes bind exact bytes. Canonical JSON is sorted-key ASCII-escaped compact
UTF-8 without trailing LF. The identity binds fixtures, matrix, preserved/full
scope, integration contract, limits, counts and each explicit opt-in rule ID.

## Preserved source and evidence scope

The prior **19 rules / 172 analyses / seven identities** remain unchanged. All
ten baseline fixture bytes, the entire twelve-file `syntax-expansion-v1` and
eight-file `syntax-protocol-v1` namespaces stay exact, including registries and
docs. Eight new immutable pins extend the original thirty to **38**; byte
hashes and closed membership are checked before parsing. Full authoring is
**22 / 199** across eight closed plans, not an aggregate execution path.
Explicit historical subsets retain **10/91**, **16/145** and **19/172** with
their unchanged identities. Nonselected rows remain `not_run`, not excluded.

The real provider and isolated runtime still use `LEGACY_EXPLICIT_INPUT`,
LIGHT, JVM, synthetic source sets and exact immutable snapshots. Active
zero-finding and genuine-option cases require the selected enabled SARIF
descriptor. Every range has one-based lines, zero-based end-exclusive columns
and exact source hashes. A disabled lifecycle case is not an enabled option
case. Denial/abstention tuples and actual native terminal checks stay strict;
no host diagnostic filter, raw JVM bypass, mock detector or retry is added.

Fresh owner-admitted source/code/data witnesses cover all four namespaces,
supporting tests/pins, actual loaded project images and pinned external tuple.
Only the selected `syntax-exception-observability` evidence path may be changed under the
unchanged anchored custody protocol; no other cohort or CURRENT pointer is a
fallback. Local successful-test publication is not independent external
task/build completion proof: the owner separately verifies the actual terminal
and exact run identity. Historical stage transfer is **zero**, including when
an old plan is selected under the changed connected source image.

## Explicit limits and separate capabilities

These are three conventions, not exception-flow or semantic correctness
proofs. A propagation boundary may be intentional. Print destinations,
secrets, retention and redaction are not evaluated. A fixed-context factory or
later `initCause` can retain useful context despite a construction warning;
a nonempty null argument may silence the matcher without useful context.
Those two latter forms are documented limits, not extra independent cases.
Rethrow/Print have no rule-specific options; no fake option is invented.

The following canonical gaps remain unavailable **in this LIGHT context**:

- `ktc.coroutines.swallowed-cancellation` and
  `ktc.coroutines.result-swallowing-cancellation`: graph refinements of
  `SuspendFunSwallowedCancellation`.
- `ktc.coroutines.suspending-finally-cleanup`: cleanup-effect graph refinement
  of `SuspendFunInFinallySection`.
- `ktc.resources.owned_autocloseable_exit`: semantic ownership refinement of
  `MissingUseCall`.
- Exact `ktc.kotlin.detekt.potential-bugs.missing-use-call`: semantic,
  Analysis-API-required and outside included156 LIGHT.

A distinct owner-bound verified-model full-analysis capability requires exact
source-set/compiler/JDK/classpath and separately admitted produced-input
closure. Its separate source/execution status is not inferred or imported
here. Even a future exact MissingUseCall run is not the owned-resource alias
graph or coroutine refinement. Root owns production integration and admission.

The unchanged **1,663 IDs x eight contexts**, all gaps and zero stage transfer
remain in scope. No natural-project, compiler, model, semantic/flow/ownership,
coroutine, taint/security/redaction, parser compatibility (modeled2.4.21 versus
embedded2.4.10), performance, cross-platform or release qualification is claimed.

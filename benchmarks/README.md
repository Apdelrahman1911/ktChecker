# Measured observations

These are retained **observations**, not a qualified performance benchmark or a
claim that the warm 1–3-second objective has been met. The required representative
matrix remains in [the validation specification](../docs/validation-ci.md).

## Opt-in small-patch collector

`scripts/run_preview_example.py` also accepts the closed data scenarios in
[`scenarios/preview-small-patch-v1.json`](scenarios/preview-small-patch-v1.json).
This reuses the actual owner `overlay`/`prepare` commands and the bounded
`review.submit`/`review.status`/`review.events` transport, not simulated job IDs.
The default two-client example and its historical measurements below are separate.

After selecting and authorizing a real distribution and compiler JDK, an owner
can run **one** explicitly selected scenario in a new private directory:

```sh
python3 scripts/run_preview_example.py \
  --distribution /owner/selected-preview-distribution \
  --compiler-java-home /owner/prepared-jdk21 \
  --output /owner/new-private-observation \
  --benchmark-scenarios benchmarks/scenarios/preview-small-patch-v1.json \
  --benchmark-scenario warm-history-concurrent
```

This executes owner-authorized compiler work; it does not build a distribution,
install a JDK, acquire dependencies, run a model host, or authorize execution in a
separately coordinated build/test lane. Do not use protected previous handoffs as
mutable benchmark state. The output directory must not already exist. It contains
private owner credentials and prepared records: **never publish the directory**.

| Requested input profile | Actual controller schedule |
|---|---|
| `fresh-broker-sequential` | New broker for each of two distinct one-file patches, with prior durable state retained |
| `fresh-broker-concurrent` | New broker, two patches admitted before polling, two worker slots requested |
| `warm-history-sequential` | One broker, one two-client baseline round, then two sequential rounds of new patches |
| `warm-history-concurrent` | One broker, baseline then two two-client batches of new patches; all historical reviews/events retained |

Fresh-broker profiles have resident owner-selected dependencies and do **not**
claim true-cold dependency/OS caches. Warm-history is a **requested schedule**,
not proof of compiler reuse, successful full-project baseline, an uncached warm
project, or the proposed 1–3-second profile. The current one-rule partial model
cannot establish that baseline; its unestablished warm prerequisite stays in the
report. The defect and clean sources both change each round; old warnings do not
become fresh findings just because a new review ID was issued.

The collector records one controller clock domain and keeps these endpoints
separate:

- `T_ack`: completed synthetic-patch handshake, **before capture**, to validated
  durable scalar-submit return. `submitStarted` and submit round trip are also
  retained, as is later accepted-event observation. The kernel commits immutable
  input and obligations before returning; this is not an independent crash test.
- `T_useful`: first matching current-source, actionable known finding, or a clean
  result only when its exact required rule/scope/build-depth assessment succeeds.
  A pending job ID, incomplete empty result, or unrelated diagnostic never counts.
- `T_required`: successful kernel and provider completion with exact coverage,
  certificates, provenance and qualification flags. Failed/incomplete/cancelled
  terminal outcomes have a separate timestamp, including cancellation without a
  result. A known-defect miss is retained even if coverage says complete.
- `T_context`, durable context insertion, and actual intended-request inclusion:
  **not attempted/unavailable**, because this controller is not a model host.
  No receipt-shaped input or remote-clock subtraction can manufacture them.

For required completion and a successful clean assessment the collector uses a
current submit/status response. A terminal event is retained at its first fetch
time, but is confirmed by a current status poll before settling that obligation;
an older complete event must not erase a newer incomplete/cancelled state. This
conservative confirmation delay remains in `T_required`/clean `T_useful`.
Even a previously complete review becomes confirmation-pending when a later
terminal event is fetched, including a review from an earlier batch. Until a
subsequent valid scalar response, `requiredAnalysisComplete` is false and the
obligation remains unsettled; a failed confirmation cannot restore old completion.
The last scalar state/facts and first valid success/usefulness timings remain
explicit history, not a guarantee of continued completion. Events are confirmed
within the original patch/run bounds, never a newly started timeout; terminal
active reviews remain bounded through the final event drain.

Every planned baseline/measured trial remains in the denominator, including
unstarted trials after a run failure. A lost submit response remains an **unknown
admission outcome**, never presumed rejected or automatically replayed. Timeouts
censor the controller's observation; they do not prove the underlying provider
failed or stopped. Accepted, incomplete older obligations remain in history.
`benchmark-observations.json` retains sanitized event IDs/payload digests,
observation timestamps, exact snapshot/context/config-revision joins, and outcome
counts even when collection fails. Per-admission files retain earlier observed
joins if a later batch fails; they are not a crash-recovery journal. Sudden process
loss can still leave an unfinished run and must not be reported as successful.

Provider-reported CPU/RSS/output counters are separate from unmeasured whole-tree
resources. Null remains unavailable; repeated polling does not sum the same
counter again. Actual model/token usage is unavailable and bytes are **not**
tokens. Accepted-but-not-currently-confirmed-terminal overlap is recorded. Event
re-confirmation and polling lag mean this is not measured simultaneous provider
execution, queue delay, sustained contention, fairness, or starvation proof.

This source adds a collector, not new benchmark results. Exit zero means collection
finished, **not** successful required analysis. No percentiles, performance gates,
rule inventory status, or release qualification are promoted by these smoke runs.
The authored synthetic accounting/transport tests likewise are not measurements.

## 2026-11-04: two clients, real JVM compiler

[Original public observations](observations/2026-11-04-two-clients.json) came from
`scripts/run_preview_example.py` and the freshly built Linux x86_64 distribution.
[Artifact identities](observations/2026-11-04-two-clients-inputs.json) bind the
script and distribution bytes; this was a dirty development tree, not a hosted
test of the eventual commit. Only public synthetic source was analyzed.

| Measurement | Observed |
|---|---:|
| Client A submission acknowledgment | 129 ms |
| Client A first useful finding, observed by application poller | 4,229 ms |
| Client A terminal result | 4,303 ms |
| Client B submission acknowledgment | 36 ms |
| Client B terminal result, no finding expected | 9,511 ms |

Both clients received distinct reviews and idempotent duplicate receipts from one
persistent broker. Both selected compiler executions were observed. Each review
remained **incomplete**: one explicitly selected, unqualified rule in a partial
compilation is not required full-project coverage. Client B's absence of a finding
is not a passing certificate. These are two small immutable synthetic compilation
snapshots, not a representative Gradle module graph or integrated project.

Compiler warmth, sustained contention, whole-process-tree CPU/RSS, required
analysis completion, model delivery and agent tokens were **not measured**.
Transport byte counts are not token counts. Preparation (48.9 seconds) and broker
readiness (3.0 seconds) are reported separately; preparation includes seven owner
command teardown grace periods. The sample therefore does not prove warm-engine
latency, fair scheduling under sustained load, or model-context delivery.

# Measured observations

These are retained **observations**, not a qualified performance benchmark or a
claim that the warm 1–3-second objective has been met. The required representative
matrix remains in [the validation specification](../docs/validation-ci.md).

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

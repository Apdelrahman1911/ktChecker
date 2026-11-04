# Retained semantic feasibility evidence

These are executed **M0 observations, not rule, tuple, or release qualification**.
Raw facts, compiler commands/output, source locations and machine-local paths stay
in private build output; only the allowlisted metadata projection is retained here.

## 2026-11-04 JVM observation

[`2026-11-04T091649Z-jvm/`](2026-11-04T091649Z-jvm/) contains:

- `summary.json`: the exact driver-produced metadata summary, including immutable
  fixture snapshot, request, analysis and selected artifact digests, assertion
  results, process exits and separately available metrics.
- `provenance.json`: actual unit-test counts/report digests, after-run owned-source
  and bytecode observations, reproduction command and evidence limitations.
- `vocabulary-coverage.json`: compiler-exercised facts, implemented but unexercised
  branches, and unavailable capabilities. **This is not diagnostic-rule coverage.**

Verified: **21 unit tests passed; 4/4 semantic cases passed using five serial
compiler processes** (binary dependency, positive hierarchy, unresolved source,
malformed source and missing dependency). Negative compilations retained their
nonzero exits and incomplete semantics. The positive compilation emitted no
unknown facts for the adapter's supported vocabulary.

Actual tuple: **Linux amd64, Ubuntu JDK 21.0.12, Kotlin 2.4.21, default JVM 1.8
target**. This is not the release project JDK 25/JVM 17 tuple or Native/iOS
evidence. The recorded HEAD had uncommitted semantic changes; the digests bind the
actual selected inputs, not an exact-commit or full-repository attestation.

Per-process elapsed times were 3797, 4528, 3094, 2870 and 3778 ms. CPU and peak RSS
were unavailable, not zero. No acceptance, useful-result, completion, context
delivery or contention benchmark was measured. These cold observations do not
demonstrate the warm 1–3-second target.

Reproduction command (new runs produce new evidence rather than extending this
historical observation):

```sh
./gradlew --no-daemon --max-workers=1 \
  :semantic-facts:test :semantic-facts:semanticFixtures
```

Hosted Native execution, complete dependency/API ownership, dataflow and rule
qualification remain separate open gates. Evidence directories are deliberately
outside the fixture source root, preventing recursive capture of earlier results.

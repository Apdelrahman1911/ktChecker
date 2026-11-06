# Explicit current445 diagnostic-gap observations

This is a **separate PRIVATE synthetic observation route**, not a new selector for
`CompilerFixtureRunner`. Every legacy443 selector still refuses the current445
mapping before resolving/allocating its fixture paths. The original
`diagnostic-gaps-445-source-v1.json`, its8 SOURCE files, legacy fixtures, legacy
SOURCE/DATA joins and old controls/provenance are unchanged.

## What the new route does (and does not do)

`compilerCurrent445Fixtures` is an explicitly consented, noncached JUnit task.
Its one integration method calls the separate current445 runner. Ordinary
`test`/`check` compile the source and may run synthetic contracts, but exclude the
tagged real compiler integration; they do not depend on this task.

The successor execution binding pins the exact current445 resource, all4
catalogs, source-v1, the8 case files, the98-pair ordinary and16-pair planned KMP
manifests, and this route's source/build/test files. The owner must independently
review and supply the **binding file SHA256**; a hash read from project JSON is
not the consent. Both flags and the fixed9 ordered cases come only from the
unchanged exact source-v1, not free-form arguments, source comments or manifests
that choose another runtime mapping. Binding metadata alone grants no execution
authority. Each call receives a fresh exact in-memory permit over its registered
immutable snapshot, analysis key, current mapping-specific profile and original
pre-write output registration.

The selected stable/upstream pairs are only:

- `ktc.compiler.common.error_suppression` / `ERROR_SUPPRESSION`;
- `ktc.compiler.common.inapplicable_operator_modifier` /
  `INAPPLICABLE_OPERATOR_MODIFIER`.

They are common factories observed on this JVM route. The warning sibling is
not an alias. The warning's intent-dependent/advisory classification is not
upgraded to a high-confidence correctness claim.

All9 scheduled rows are visible from admission, including remaining `not_run`
rows after startup, custody, budget or child-lifetime failure. The runner keeps
actual process/result returns, full parser inventory separately from the
provider's residual `rawDiagnostics`, exact selected point-message multisets,
UTF16 point-region checks and cleanup receipts. It never fabricates token end
spans, retries a case, launches control/probe jobs or reuses another run.

Eight cases have known source oracles. Expected operator ERROR must preserve
nonzero compiler exit and failed provider status. The ninth hard-error
suppression case always retains `expected_outcome_matched: null`, including
exit0. Its known warning oracle and parser/normalization invariants still must
hold. Missing/failed cases cannot be laundered by the unknown. A successful
single JUnit method verifies the bounded valid-UTF8 observation route and8 known
oracles, **not9passed or445qualification**. `run_complete` and source-plan
expected-outcome closure stay **false**. A stale candidate receipt, interrupted
task, failed JUnit method, or terminal-publication error is not a successful
observation collection.

## Unresolved raw-byte evidence capability

The maintained runtime API exposes decoded `ProcessOutcome.stdout/stderr`
Strings only. On invalid UTF8 it records `process_output_invalid_utf8` and loses
the offending stream's bytes before the fixture observer. This fixture-scoped
change does **not** modify that API, recover the lost bytes, or weaken/rewrite
source-v1's requirement to retain every actual byte.

Files named `*.runtime-string.utf8` are explicitly UTF8 reencodings of the exact
returned Strings, **not native raw-byte witnesses**. Valid UTF8 is a lossless
round-trip at this boundary; invalid/truncated/malformed output cannot match an
oracle or establish collection success. The unresolved raw-retention capability
stays visible even for an otherwise healthy run. Bounded evidence/persistence
failure also fails closed, with retained companions/indexes and the incomplete
ledger; it never substitutes hashes of empty Strings for missing raw evidence.

## Broad scope and evidence meaning

Every run preserves all445 mapping rows (402 JVM applicable,43 Native) and all
1665 catalog rows, including the existing46 exclusions without expanding them.
The other443 mappings, including Native43, are unselected/not run, not removed
from planned launch scope. Ordinary98 and planned KMP16 fixture pairs are
visible and unrun. The declared13320 obligation cells are unchanged.

Catalog implementation/test/qualification stages stay null/unknown, not
measured zero; current evidence-reference lists remain empty. Any actual
provider `implemented` finding flag is retained in its original result, not
promoted into catalog delivery. No certificates, independent precision
qualification, benchmark/latency evidence, general Android/Desktop/Native
coverage, ordinary six-kind closure or current report-scope change is produced.

## Exact prerequisites for a later owner-authorized execution

No execution is implied by adding these files.

1. Root reviews/adopts this source packet and independently reviews the successor
   execution-binding digest. Any changed bound source/build/catalog/fixture
   requires a new reviewed binding. Preserve old443 paths and original source-v1.
2. Root performs a fresh offline build/test compilation for the adopted sources.
   Startup captures actual loaded class-directory/jar bytes, mapping and installed
   native supervisor resources, anchors the new classes to that artifact
   inventory, and checks drift. Captured SOURCE is **not** source-to-bytecode proof.
3. Supply an explicit Linux JDK21 fixture host, owner-prepared **whole link-free
   JDK21** home, exact local Kotlin2.4.21 compiler/stdlib dependency artifacts, and
   the correct locally installed owned-process supervisor with its manifest,
   binary/build receipt, START identity, FINAL/EOF and verified helper-exit path.
   No system-JDK fallback, downloads, native build, availability inspector or
   legacy compiler install is hidden inside the route.
4. Owner approves exactly this finite native/compiler launch scope. Example
   template (substitute the independently reviewed64-hex binding digest and
   prepared JDK path; this document supplies neither approval nor a digest):

   ```
   ./gradlew --offline :provider-compiler:compilerCurrent445Fixtures \
     -PcompilerCurrent445Consent=current445-nine-observations-not-qualification-v1 \
     -PcompilerCurrent445BindingSha256=<root-reviewed-binding-sha256> \
     -PcompilerFixtureJavaHome=<absolute-owner-prepared-link-free-JDK21>
   ```

The limits are9 serial isolated compiler calls,20min cooperative total
(startup/capture/cleanup/publication included),90s per provider job including
preparation,512MiB child heap,384MiB fixture-host heap,2MiB combined process output,
256MiB retained evidence including8MiB terminal reserve, and16MiB per-job evidence
reservation. Owners may tighten total/job/evidence limits using the three
`compilerCurrent445*` properties; they cannot widen them or request a tenth call.
Very small tightened limits can refuse before any provider call. These are
cooperative bounds, not an OS hard-kill or disk-quota/latency certificate.

Each job uses the existing bounded owned-scratch manager; compiler/dependency
copy bytes obey its provider bounds and outputs are cleaned serially, not
retained9-fold. Cleanup only uses registered original identities. Unknown child
quiescence immediately quarantines the entire private tree before fallible
persistence and prevents further calls; no guessed PID, path adoption, unsafe
delete, resumption or external latest-selector write is used. Evidence is under
a new `build/compiler-fixtures/runs/current445-<uuid>/` directory. The printed
private `control/stop` marker is cooperative between calls; current-child
deadline still applies. No external notification contains these private bytes.

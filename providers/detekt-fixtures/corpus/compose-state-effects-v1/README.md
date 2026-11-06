# Compose state/effects SOURCE corpus v1

**Authored and unrun. Main runtime lane remains HOLD.** This private proposal supplies
61 Kotlin SOURCE cases for eight approved rules, an opt-in bounded native runner, and
a separately labeled task-wiring proposal requiring independent adoption. No Kotlin compiler, detector, test,
build or native helper was run while authoring. `pending-coverage.json` records this;
it is not a live coverage sidecar. Nothing renews earlier evidence or changes an
existing cohort, catalog, 31-rule/287-analysis SOURCE inventory, activation or certificate.

## What is and is not supported

The exact upstream oracle is Compose rules **v0.6.8**, commit
`84794e3175a8ab6e1d024a5db6c299be2454ae6b`. Implementation, wrappers, tests and helpers
are pinned in `source-oracles.json`. Detekt alpha6 Entity/Location/Suppressions SOURCE
establishes parameter-name versus type/call entities, exact child-rule suppressions,
and one-based lines / zero-based columns / exclusive end positions. These are literal
SOURCE predictions, not detector observations or a compiler typecheck.

| Rule | Cases | Original activation | Future exact-owner behavior |
|---|---:|---|---|
| RememberMissing | 8 | advisory | Six reviewed generic/scalar message families; whole rule unavailable |
| RememberContentMissing | 8 | advisory | Reviewed content/remember API surface; low-certainty advisory |
| LambdaParameterInRestartableEffect | 8 | advisory | Unattributed full effect surface unavailable; no native invocation |
| ContentSlotReused | 8 | opt_in | Reviewed content/remember API surface; low-certainty advisory |
| MutableStateAutoboxing | 8 | advisory | Four reviewed scalar message families; whole rule unavailable |
| StateParam | 7 | opt_in | Reviewed generic/scalar API surface; low-certainty advisory |
| MutableStateParam | 7 | opt_in | Unattributed holder surface unavailable; no native invocation |
| UnstableCollections | 7 | advisory | Reviewed Immutable contract/collection APIs; low-certainty advisory |

“Reviewed API surface” never means resolved symbol identity, application intent,
parser compatibility, safe remediation, complete project analysis or qualification.
Every admitted finding stays warning/advisory/low, without a fix hint or proof.
For the two partial rules, even actual facet observations leave whole-rule coverage
`unavailable`, `implemented=false`, `tested=false`, `qualified=false`. A negative for
a fully reviewed surface is `unknown`, not passed. All absence/unknown/unsupported
library controls remain visible, not `not_applicable`.

The tagged sample BOM2026.09.00 selects runtime1.12.1. Published module metadata maps
its JVM compile variant to `androidx.compose.runtime:runtime-desktop:1.12.1`, with
`runtime-annotation-jvm:1.12.1`; immutable guidance additionally uses
`org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm:0.3.8`. Their expected binary
sizes/digests are advertised metadata only. **No binary was downloaded or verified.**
A SOURCE archive is never an eligible classpath/runtime pin. The complete actual
ordered dependency vector, including stdlib/coroutines and other transitive binaries,
must be independently owner-resolved and reverified, not reduced to three API labels.

`api-surface-review.json` preserves exact SOURCE/DATA pins and scope:

- Primitive scalar state exists, is snapshot-observed, and has primitive JVM storage.
  The older1.4.3/1.5.0 SOURCE contrast is retained, but scalar presence licenses neither
  a newer full runtime tuple nor the entire autoboxing/holder/factory rule surface.
- All15 primitive collection factories and15 holders exist in `androidx.collection`
  1.5.0, not as observed Compose state declarations in reviewed runtime SOURCE.
  The array-backed containers do not prove that replacing `MutableState<List/...>`
  preserves Compose observation or type/ownership contracts. Lifetime/ownership and
  unsafe autobox replacement are separate questions; existence alone decides neither.
- Effects include runtime3, lifecycle3, Circuit `produceRetainedState`, and a conventional
  arbitrary project `KeyedEffect`. There is no selected/pinned lifecycle/Circuit or
  closed owner-bound effect-site inventory here. The diagnostic aggregates at a
  parameter, not one attributable call. Missing imports do not prove absence.
- MutableStateParam shares one message across generic/scalar and primitive holder
  types. No owner-bound resolved-type site witness exists. Its current denial and the
  effect denial are **not permanent requirements to install every optional library**.
  A future exact site/whole-composable inventory could admit known present or absent
  facets; SOURCE/name/coordinate scans cannot fabricate that witness now.
- `@Immutable` is a programmer promise, not validation. No strong-skipping behavior,
  compiler inference, recomposition count, performance gain or real-world precision
  is established by UnstableCollections fixtures.

## Concrete cases and controls

Every rule has a triggering source, a real API/policy alternative, an exact child
suppression with an unsuppressed same-file neighbor, aliases, deliberate unrelated
homonyms, and a captured positive test source excluded by **exact main membership**.
Same-file function typealiases are retained where upstream actually discovers them.
Import alias misses and homonym hits remain honest syntax false-negative/false-positive
probes. They are not fake APIs used to justify a fix or a passing clean result.

MutableStateAutoboxing positives/suppressions deliberately work outside `@Composable`;
a blanket annotation guard would be incorrect. Two existing cases also contain an
unreviewed collection-family diagnostic: the real native runner must retain its raw
SARIF location while withholding its advice from both findings and provider-facing
raw diagnostics (including console-output transport). These do not increase61 cases.
For the two no-launch rules, admitted and withheld-output arrays are empty. Their
nine predicted raw sites remain explicitly `uninvoked_raw_source_oracle`, not admitted
findings or fabricated detector observations; all38 raw SOURCE sites remain retained.

`admission-contexts.json` describes13 concrete no-runner controls: untrusted labels,
copied approval, missing runtime/annotations, a real separately pinned unsupported
runtime, scope/membership/dependency-order mismatches, profile-before-preparation,
changed independent owner resolution, cross-provider rebinding, revocation and owner
disablement. UnstableCollections adds a missing-immutable-family control. Structural
preparation exceptions are recorded as such, never relabeled as successful negatives.

The runner uses public `DetektLibraryPermit.prepare`, `DetektComposeCompilationOwner`,
`DetektComposeCompilation`, `DetektProvider.executionPlan/analyze` and
`DetektReportParser.parse`; it does not import test-output helpers or internal
projection APIs. One provider instance is retained across preparation/planning/analyze.
The library permit and exact execution permit are separate and closed per attempt.
The fixture owner independently resolves its fixed scope/source-set roster and
registered immutable SnapshotStore membership on every check, and rereads a separately
owner-selected digest-pinned dependency descriptor; it never accepts an AnalysisInput
argument to reflect. This synthetic owner is not a production model-importer proof.

## Separate task amendment and future use

The separately labeled amendment to `modules/provider-detekt/build.gradle.kts` adds
`composeStateEffectsCorpusTest` and excludes this class from legacy `corpusTest`.
It does **not** alter the closed cohort selector. No ordinary test/check, generic
Detekt flag, or earlier corpus task selects it. Missing consent/input is a hard
failure, never an always-skipped placeholder. Source adoption is not execution consent;
**do not run while HOLD applies**.

A later owner-admitted run selects exactly one full rule ID and supplies all properties:

```text
:provider-detekt:composeStateEffectsCorpusTest
-PdetektComposeConsent=compose-state-effects-v1-one-rule
-PdetektComposeRule=ktc.compose.pack.RememberMissing
-PdetektComposeJavaHome=<exact absolute owner-selected JDK21 home>
-PdetektComposeArtifacts=<exact absolute pinned Detekt artifact directory>
-PdetektComposeOwnerCompilation=<exact absolute owner compilation descriptor>
-PdetektComposeOwnerCompilationSha256=sha256:<independently selected descriptor SHA256>
-PdetektComposeEvidenceRoot=<exact absolute dedicated directory under this workspace build/>
-PdetektComposeMaxInvocations=8
-PdetektComposeAggregateMillis=300000
-PdetektComposeChildMillis=30000
```

The descriptor format is documented in `owner-input-format.json`; the packet contains
**no filled descriptor or placeholder that grants authority**. It must include an
actual reviewed complete JVM compilation vector and a separately pinned real
other-runtime-version vector for the unsupported-version control. Do not relabel
reviewed bytes or substitute sources. All bytes are checked by live preparation.

The selected rule needs at most8 native analyses (7 for StateParam/UnstableCollections,
0 for the two unattributed rules). Guard controls launch none; no hidden version probe,
retry, all-rule run or fallback is added. The aggregate budget counts fixture/owner
preparation and evidence work inside the test, not the surrounding build. Owner/file
callbacks are cooperative; overruns fail the attempt rather than claim hard preemption.
The task uses one fork, an exact class/tag/test-name filter that fails on no match,
no up-to-date or build-cache reuse, and requires JDK21.

Actual runs retain fresh private per-attempt source/config/SARIF/provider-result hashes,
ProcessOutcome data and actual supervisor identities. A final private completed-write
hash index records written bytes, not custody, execution authority or build success.
They check exact raw and admitted
locations, suppression isolation, enabled negative descriptors, no unsupported advice,
unchanged materializations/parents, exact owner bindings and invocation/deadline limits.
Failed/uncertain outcomes stop without retry and retain scratch/output ancestors. A
final assertions file explicitly leaves the surrounding build outcome unknown and
publishes no live sidecar/certificate. This packet records **no actual pass/fail/skip**.

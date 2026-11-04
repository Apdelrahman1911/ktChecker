# Pinned Kotlin/JVM compiler integration

The adapter starts Kotlin **2.4.21** in a separate bounded Java process. It never
loads compiler internals into the host. The 443 exact catalog mappings are a
transport ledger: 400 are JVM candidates; 43 Native mappings are **not executed
or supported by this JVM provider**. A mapping is not a fixture result.

## Run

```sh
./gradlew --no-daemon --max-workers=1 :provider-compiler:test :provider-compiler:compilerFixtures
```

The fixture task uses JDK21, a resolved pinned compiler classpath, explicit
Kotlin standard-library identities and serial child JVMs. The compiler child
heap defaults to 512 MiB (`-PcompilerFixtureHeapMiB=...`). Production hosts select
`compilerHeapMiB` and exact execution bounds; a JSON `approved` boolean is not
authority. Only an authenticated, non-serializable permit naming the entire
`AnalysisInput.analysisKey()` can execute.

`manifest.json` and `cases/` contain original synthetic inputs, not copied or
disabled upstream execution evidence. The manifest's initial input status is
not updated to claim execution. Each actual run writes unique `runs/<run>/`
input manifests, captured source bytes, exact commands, version banners,
stdout/stderr, provider results and explicit assessments. The sidecar
`evidence/compiler-jvm-2.4.21.json` references those real files with SHA-256 and
reports **every** mapped rule, including unrun and Native gaps.

Six independent assessment kinds cover positive, corrected negative,
configuration (warning promotion and disable, or JVM target change), executed
version/renderer plus mismatched-version denial, real source suppression, and
changed/moved-source regression. Error suppression is executed individually:
failed compilation remains failed, and compiler-accepted suppression cannot
create successful coverage. Additional actual controls exercise unmapped syntax,
unnamed `-Werror` failure, quoted IDs, and intentional NaN/infinity/empty ranges.
No absence-of-warning claim is made for intentional floating-point/sentinel code.

## Boundaries

* Sources come only from an immutable manifest and explicit imported
  `context.compilationFiles`. Captured build metadata is retained for identity,
  but is never executed or inferred to be a compiler source. A source-only
  manifest without imported membership permits limited transport, not complete
  Gradle/KMP scope. `.kts`, source-root inference and directory classpaths are
  unsupported.
* Compiler, dependency and source bytes are verified and copied into fresh job
  outputs. Flags are allowlisted; response files, plugins, `-J`, output and
  classpath escapes are rejected. This is trusted-process isolation, **not an
  adversarial same-UID OS sandbox**.
* Named headers are resolved against the job's sealed sources. Reported locations
  are one-based lines and zero-based UTF-16 point columns, not guessed token
  spans. Unmapped, unnamed, unknown-path and malformed output stays raw and
  prevents completeness. Actual emitted severity is never replaced by factory
  declaration severity or inferred behavioral intent.
* Disabled/unenabled checker settings, unknown compilation/dependency/model
  identities, capture exclusions and source suppression block clean coverage.
  No rule/context is qualified by default. Exact owner-supplied qualification
  must additionally match dependency, model, compiler options and toolchain.
* Synthetic JVM/JDK21 results do not qualify real-world precision, warm latency,
  Windows/macOS portability, Android, Native/iOS, linking or simulator tests.
  CPU/RSS and other unmeasured quantities remain unknown; missing fixtures and
  hosted contexts are gaps, never exclusions or automatic default activation.

Refresh mapping references after implementation changes, without changing any
catalog status/default:

```sh
python3 providers/compiler-fixtures/generate_rule_map.py
```

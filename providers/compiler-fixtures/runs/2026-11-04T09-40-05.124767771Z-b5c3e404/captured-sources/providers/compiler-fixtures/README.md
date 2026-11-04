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

Before reading the manifest/cases, the runner preserves startup copies of the
provider, fixture, runtime and contracts sources plus relevant build metadata.
It separately preserves actual provider/runner/contracts class directories or
jars, host runtime jars and loaded registry bytes, with manifest digests and
class-resource anchors. Those artifact identities are **not** inferred from
live source hashes. Artifacts/resources are checked before each compiler job;
sources and artifacts are checked at run end. Drift fails the task and withholds
implementation/test attribution while keeping original copies and actual
attempt results. Each run also keeps its own sidecar so later runs cannot erase
historical failures. Raw stdout/stderr and bytecode remain byte-preserving Git
evidence, not whitespace-cleaned text.

Configured repository and actual class-source outer directories are canonicalized
once to accept operating-system ancestor aliases such as macOS `/var` versus
`/private/var`. Linked outer-directory leaves and all evidence-file or inventory
descendant symlinks remain rejected; arbitrary evidence paths are never silently
canonicalized by the bounded reader.

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
* Every normalized finding supplies a future `exact_context_recompile`
  Verification with `state="not_run"`. After edits it requires a new sealed
  snapshot and new owner permit, the same pinned compiler/explicit context,
  actual successful compilation and comparable diagnostics with the checker
  still enabled. Suppression, disabled checks, omitted inputs and failed builds
  are not proof of resolution. No Gradle task, shell command or reusable old
  execution permit is invented.
* Synthetic JVM/JDK21 results do not qualify real-world precision, warm latency,
  Windows/macOS portability, Android, Native/iOS, linking or simulator tests.
  CPU/RSS and other unmeasured quantities remain unknown; missing fixtures and
  hosted contexts are gaps, never exclusions or automatic default activation.

Refresh mapping references after implementation changes, without changing any
catalog status/default:

```sh
python3 providers/compiler-fixtures/generate_rule_map.py
```

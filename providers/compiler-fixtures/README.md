# Pinned Kotlin/JVM compiler integration

The adapter starts Kotlin **2.4.21** in a separate bounded Java process. It never
loads compiler internals into the host. The 443 exact catalog mappings are a
transport ledger: 400 are JVM candidates; 43 Native mappings are **not executed
or supported by this JVM provider**. A mapping is not a fixture result.

## Run

```sh
./gradlew --no-daemon --max-workers=1 :provider-compiler:test :provider-compiler:compilerFixtures \
  -PcompilerFixtureJavaHome=/absolute/owner-prepared/link-free-jdk21
```

The fixture host uses JDK21. The task requires an explicit owner-selected,
link-free JDK21 home via `compilerFixtureJavaHome`; it never silently selects
the Gradle launcher's ordinary system JDK or copies/prepares a JDK itself.
The child uses that selected image, a resolved pinned compiler classpath, explicit
Kotlin standard-library identities and serial child JVMs. The compiler child
heap defaults to 512 MiB (`-PcompilerFixtureHeapMiB=...`). Production hosts select
`compilerHeapMiB` and exact execution bounds; a JSON `approved` boolean is not
authority. Only an authenticated, non-serializable permit naming the entire
`AnalysisInput.analysisKey()` can execute.

Compiler installation identities preserve the ordered coordinate/content/path
list, framed as canonical JSON. The exact installation digest is intrinsic to
the approved JVM profile even when a limited request has no imported toolchain
digest; a declared mismatching toolchain is denied before execution.

The Java identity inventories the **whole selected JDK home**, not just
`bin/java`: modular runtime bytes, native VM libraries, configuration, release
metadata, all other files, entry names, empty directories and executable policy.
Only supported JDK21 layouts are accepted. Dot/parent-traversal selections,
linked home leaves, symlinks, junctions and special descendants are denied.
Aliases above the explicit ordinary outer home are canonicalized once and their
target stays pinned. Each full streaming scan bounds entries, depth, UTF-8 names,
individual/total file bytes and cooperative elapsed time; no mtime-only cache is
used. The image is reverified during preparation and after the child. Runtime
drift fails closed while actual command, exit code and output remain retained.
`toolchain.json` includes the captured full inventory and its digest. This is
trusted quiescent disk identity, **not** loaded-code or external OS/JNI attestation.
Normal symlinked system JDKs are intentionally unsupported; an explicit prepared
image is required for compiler execution, with no launcher-only fallback.

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

The preserved `2026-11-04T09-40-05.124767771Z-b5c3e404` startup tuple executed
74 actual compiler children: all 42 authored IDs were observed, 41 passed all
six kinds, and two `EMPTY_RANGE` negative/configuration assessments failed.
Its deprecated experimental-checker flag emitted a global `DEPRECATED_CLI_ARG`
warning, correctly retained raw rather than treated as clean absence. All 187
normalized Findings had truthful not-run Verification; source/artifact drift
checks passed. Subsequent toolchain-identity changes require new validation;
these historical results are not attributed to later implementation edits.
The latest sidecar therefore withholds all current-source implementation/test
flags and references the immutable historical run separately. Existing 14-test
JUnit XML is preserved under `unit-evidence/` with an explicit post-run
preservation label; it is not a startup receipt or evidence for the new sources.
The new runtime-identity tests use tiny non-executed synthetic layouts, never a
fake JVM/compiler execution. The central `2026-11-04T10:22:16Z` compiler unit
reports independently verified **28 passed, one explicit native-Windows junction
skip, zero failures/errors**; exact XML and matching frozen sources are preserved
under `unit-evidence/2026-11-04T102216Z-central-compiler-units/`, labeled post-run
rather than startup bytecode attestation. The overall central batch failed in
other modules; it is not an all-modules passing receipt. No current-source real
compiler child/corpus has run yet, so unit success does not reattribute the
historical per-ID results or create qualification.

## Boundaries

* Sources come only from an immutable manifest and explicit imported
  `context.compilationFiles`. Captured build metadata is retained for identity,
  but is never executed or inferred to be a compiler source. A source-only
  manifest without imported membership permits limited transport, not complete
  Gradle/KMP scope. `.kts`, source-root inference and directory classpaths are
  unsupported.
* Compiler, dependency and source bytes are verified and copied into fresh job
  outputs. The separately selected JDK runtime image is not copied per job; its
  full inventory is reverified before/after execution. Flags are allowlisted;
  response files, plugins, `-J`, output and
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

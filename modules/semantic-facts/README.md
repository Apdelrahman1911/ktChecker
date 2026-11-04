# Kotlin 2.4.21 semantic-fact feasibility adapter

**M0 spike, not release qualification.** This module executes a version-isolated
Kotlin compiler plugin. It does not qualify a custom rule, establish a complete
project model, or substitute JVM analysis for Native/iOS. See
[architecture.md](../../docs/architecture.md#provider-architecture) and its Native
semantic feasibility gate. Until an actual fixture run is retained, source code
and declared test cases are not execution evidence.

## Boundary and deliberate unstable-API exception

The public API consists of serializable `SemanticFactsRequest`, `SemanticFactShard`
and `SemanticFactBundle` DTOs. No FIR, PSI, IntelliJ, compiler-session or Analysis API
objects escape. Only `adapters.kotlin2421` accesses pinned FIR internals through
`CompilerPluginRegistrar`, `FirAdditionalCheckersExtension`, post-resolution file
checkers, resolved references/cone types and `expectForActual` attributes.

This explicit M0 exception tests whether the required Native facts are attainable
through a real compiler route. It is **not** a claim of stable standalone Analysis
API integration or cross-version compatibility. The plugin checks the actual
compiler version, executing compiler JAR digest, its own plugin digest, request
digest and exact source bytes. The compiler is `compileOnly` and excluded from the
plugin JAR. Each authorized invocation gets a separate compiler process, collector
and output tree; no FIR session or mutable cache is reused between projects.

Ordinary compiler-provider flags continue to prohibit arbitrary `-Xplugin`, `-P`
and response files. This fixture task alone constructs owner-selected plugin flags
and a nonserializable `ExecutionPermit` bound to the exact analysis key, selected
artifacts, immutable snapshot and output setup. Neither project JSON nor a model's
`approved` boolean grants execution authority.

## Facts and abstention

The adapter emits:

- Fully qualified compiler symbols, declaration kind/origin, overload-sensitive
  signature IDs, parameters, receivers, annotations, flags, supertypes and aliases.
- Statically resolved calls/property accesses/callable references, inferred result
  and receiver types, containing declaration and resolved argument-to-parameter map.
- Structured class/type-parameter/flexible/intersection/definitely-not-null types
  and generic projections. Unresolved/error/captured or otherwise unmodeled types
  remain explicitly unknown; there is no spelling/import regex substitute.
- Per-source actual compiler module/platform/refinement plus separately supplied
  source-set/compilation context. Contradictory or ambiguous graphs are incomplete.
- Compiler expect/actual **candidate matching**, including intermediate actuals and
  actual typealiases. Matching is not final compatibility; compilation failure
  remains failure even when some candidate facts were emitted.
- Source byte digests and zero-based, end-exclusive UTF-8 spans mapped from actual
  compiler line/UTF-16 columns. CRLF and supplementary Unicode are tested. Invalid
  UTF-8, surrogate-split locations and unqualified BOM mapping fail closed.

A successful compiler exit alone is insufficient. Assembly also checks the frozen
request/tuple, complete selected source inventory, source bindings, symbol edges,
graph consistency and explicit unknowns. Missing output, malformed code, unresolved
references, provider errors or changed artifacts cannot become complete semantics.
Qualified tuple/rule flags are always false in this M0 adapter.

Not implemented here: complete dynamic/candidate/negative dependency edges;
interprocedural CFG/data-flow/effects/lifetime/thread proofs; general generator,
arbitrary compiler-plugin, cinterop/header/SDK completeness; persistent semantic
sessions or cache reuse. These remain release gaps, not excluded rules. Fact
availability is only a prerequisite for the full custom rule inventory.

## Executable fixtures

The root coordinator owns local heavy builds. The default fixture task runs five
serial child compiler processes: a separate binary dependency, a positive
common→intermediate→platform compilation, unresolved and malformed code, and a
missing-binary-dependency replay. It asserts overloads, import aliases, local name
shadowing, resolved generic dependency types, actual/typealias relations and exact
CRLF/Unicode spans. Negatives must abstain and retain compiler failure.

```sh
./gradlew --no-daemon :semantic-facts:test :semantic-facts:semanticFixtures
```

Default compiler JDK is the explicitly selected Gradle Java launcher, separately
recorded from compiler artifacts. Select another project JDK explicitly with
`-PsemanticJavaHome=/qualified/project/jdk`. The broker JVM remains separate.

On the authorized GitHub-hosted macOS lane, after provisioning exact Native 2.4.21,
Xcode/SDK and project JDK 25:

```sh
./gradlew --no-daemon :semantic-facts:semanticFixtures \
  -PsemanticMode=native -PsemanticTarget=iosSimulatorArm64 \
  -PsemanticNativeHome="$NATIVE_HOME" -PsemanticJavaHome="$PROJECT_JAVA_HOME" \
  -PsemanticResult="$EVIDENCE_DIR/results.json"
```

`iosArm64` is also selectable. `linuxX64` is an explicit exploratory Native host
probe, **not** launch-target expansion or iOS evidence. Native execution produces
and consumes a real custom KLIB; it does not use JVM stubs. Apple targets fail
unavailable on non-Apple hosts. This task compiles libraries, not linking, simulator,
runtime or cinterop qualification; the platform suite owns those separate gates.

Each compiler is heap-bounded (512 MiB), output-bounded, timeout-bounded and executed
serially. Sources are private CAS-backed immutable fixture materializations with
before/after verification. JVM compiler/dependency/plugin artifacts are copied to
private outputs; the selected Native distribution is verified before/after.
Native dependency auto-download is disabled with `airplaneMode=true` and the caller
must provision target inputs first. These are trusted cooperative processes, not an
OS security sandbox or a proof against transient restored writes.

The driver writes two deliberately distinct evidence forms:

- **Local/private:** `results.json`, `*-facts.json`, `*-stdout/stderr.txt` contain
  fact bundles, selected machine paths, actual commands and compiler text. Retain
  locally when authorized; do not upload these as sanitized CI artifacts.
- **CI metadata:** sibling `summary.json` (schema 1,
  `evidenceKind=executed_semantic_fact_fixtures`,
  `metadataProjection=metadata-only-v1`) is an explicit allowlist projection.
  It includes state/target/time/tested commit; approved runner and separate
  fixture-host/project-JDK versions; logical artifact coordinates/digests without
  machine-local paths; case assertion booleans, snapshot/request/analysis digests,
  compiler exit, completion state, version list, counts and unknown reason codes;
  execution snapshot/key/status/reasons/exit, separate CPU/time/memory metrics and
  stdout/stderr digests. It binds the private report digest but excludes command
  arrays, fact bundles, source paths/text, symbol names, output text and arbitrary
  environment. Native component coordinates can contain portable distribution-
  relative names, not host directories. Upload **only this file**. Apple job
  evidence separately records actual Xcode/SDK/simulator identity and exact source
  tree. `summary.json` cannot be selected as the raw result filename.

Both forms retain `tupleQualified=false` and `customRulesQualified=false`. A
passing fixture is integration evidence only, not release or benchmark
qualification. A tested HEAD may have uncommitted changes; the source snapshot and
artifact digests bind actual inputs, not a claim that HEAD contains all changes.

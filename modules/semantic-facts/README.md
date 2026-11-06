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

Raw serializable DTOs are not provenance certificates. `assemble` detaches and
recursively freezes their collections, validates a closed type vocabulary, symbol
identity/argument edges, and recomputes every byte span from an explicitly supplied
immutable-source byte reader. Without that reader a decoded envelope remains
`semantic_source_bytes_unverified`, never complete. Local generic owner identity
includes compiler-owned source affiliation and signature/context/bounds; identical
local names in different enclosing bodies cannot stand in for one another.

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

Selected Native artifacts include `bin`, `tools` (the launcher's `env_blacklist`),
`konan`, common KLIBs and the chosen target's platform KLIBs. Their digests are **not**
a proof of the complete external LLVM/libffi/sysroot/cache dependency closure. The
Native distribution's configured target sysroot is also distinct from the selected
host Xcode/SDK. The Apple lane must observe its actual prerequisites; this library-
only feasibility task cannot qualify omitted SDK/header/link/runtime coverage.

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


## Optional JVM physical-container survey (source candidate)

`SemanticFactsRequest.observeJvmBinaryOrigins` defaults to `false` and is omitted
from default serialization. `SemanticFactShard.jvmBinaryOrigins` defaults to null
and is likewise omitted. All previous fields and structural symbol IDs stay
unchanged. Default invocations create no observer and perform no new VFS reads.
An opt-in request has a different request digest; old outputs cannot satisfy it.

The versioned optional survey records every original symbol ID within its shard with either a
physical-container **observation** or an explicit unknown reason. It only follows
these exact Kotlin 2.4.21 compiler-object edges:

- Direct `Library` named functions: `containerSource` through
  `JvmPackagePartSource.knownJvmBinaryClass` or
  `KotlinJvmBinarySourceElement.binaryClass`, then `VirtualFileKotlinClass.file`.
- Direct `FirJavaMethod`: its actual `containingClassSymbol` and the class's
  `VirtualFileBasedSourceElement.virtualFile`. Ordinary enhanced Java call targets
  may not be this direct shape and remain `java_enhancement_origin_unsupported`.

No FQN lookup, classpath search, class loading, reflection, or fake-override unwrap
is used to invent an origin. Source/local/noncallable/unsupported declarations,
Native/common contexts, enhancement/override wrappers, absent physical sources,
other VFS protocols, exploded directories, unsafe paths, oversize/read failures,
and ambiguous equal structural IDs all remain explicit unknown observations.

Only actual `jar` or `jrt` VFS coordinates are decoded. The compiler VFS path is
retained verbatim; archive entry or actual JDK home/module/class-entry components
come from that path, never a class-name-to-filename convention. A bounded VFS read
retains size and SHA-256 of the class bytes at observation time. It is not evidence
that those were the bytes previously loaded into a compiler cache. The observer
accepts class payloads up to 4 MiB and reserves at most 32 MiB per invocation for
its own stream consumption, including one EOF probe per attempt. Failed reads
consume their full reservation. This is **not** a bound on compiler VFS internal
allocations: exact `FastJarVirtualFile.getInputStream` first materializes its own
byte array. Existing isolated-process memory/time limits remain necessary. Whole
incoming surveys also have shared record/text bounds and are recursively detached
by assembly.

This data is **not an authenticated extraction or binary-ownership receipt**.
The owner must still retain a genuine successful exact invocation, immutable
source/argv/plugin/output custody, every actual dependency/container byte mapping,
and the entire selected JDK image. A JRT class digest is not a JDK-image digest;
archive coordinates are not whole-JAR identity. The original bundle completeness
flag concerns its original vocabulary, not binary-ownership completeness. Unknown
survey rows stay unknown even when the original facts are complete. Optional
survey structural validation performs no compiler execution or artifact read.
Within-shard equal-ID disagreements become unknown; the separate owner must also
check cross-shard/context agreement rather than treating structural IDs as global
binary identities. VFS coordinates are not canonical, symlink-free custody paths.

All physical paths/class identities remain PRIVATE fact evidence. They must not
be added to the metadata-only CI summary or used as a permission source. This
candidate installs no provider route, authority issuer, coroutine qualification,
new catalog ID, or context. No compilation or extraction of this candidate has
been performed by its author.

The **existing** genuine fixture driver accepts optional
`-PsemanticJvmBinaryOrigins=true` (paired runner argument
`--jvm-binary-origins true`) only in JVM mode. Absence retains the original argument
vector and default request/shard encoding for identical original fields. This
flag changes no source fixture, language/target option, attempt schedule,
timeout, heap, or output limit; its request-digest binding does change: the
driver still makes **five serial compiler attempts**, one dependency and four
plugin-bearing compilations. It is not a one-process probe. Root must independently
review and admit each exact actual extraction run; no automatic probe is added.
The flag requests observation only: existing positive-case success does not prove
that any particular target has a supported physical origin. Inspect the PRIVATE
survey and preserve unknowns. The data-only authored controls do not load FIR,
read class bytes, execute a compiler, or qualify a binary ownership tuple.

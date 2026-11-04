# Engine evidence ledger

**Research observed 2026-11-04 UTC. This is source research, not a working ktChecker implementation or a compatibility test.** The pages below were actually fetched over HTTPS during this planning task. Successful responses were HTTP 200. Live documentation can change: dates, named versions, retrieval digests, and limitations are recorded rather than treating `/current/` or an API reference as a frozen release contract.

The architectural decisions derived from this evidence are in [architecture.md](../architecture.md). Statements about what ktChecker *should* do are design conclusions, not claims made by the upstream projects. Source retrieval does not establish a performance result, confirm an artifact resolves, or establish compiler/plugin binary compatibility.

<a id="e01"></a>
## E01 — Kotlin release baseline

- Source: [Kotlin releases](https://kotlinlang.org/docs/releases.html).
- Observed version: **2.4.21**, listed as released **2026-10-08**; the update examples and current release table name this version.
- Relevant fact: the language/compiler version is an explicit build-system input, not necessarily the version embedded in an analysis tool.
- Consequence: use a versioned compatibility manifest. Do not claim a provider compiled against another Kotlin version is compatible merely because both are Kotlin 2.x.
- Limit: this establishes the documented release baseline, not the versions used by a particular workspace or a tested ktChecker support range.

<a id="e02"></a>
## E02 — Analysis API feasibility and standalone warning

- Source: [Kotlin Analysis API introduction](https://kotlin.github.io/analysis-api/), especially **Using in Command-Line Tools**.
- Relevant facts: the API supplies semantic information over Kotlin PSI; stable parts have source and binary backward-compatibility promises. Standalone mode is available for command-line tools, but the page explicitly describes it as actively developed and subject to incompatible changes.
- Consequence: a standalone semantic worker is a credible implementation route; a universally stable, drop-in standalone SDK is **not** established. Pin the implementation and maintain adapter fixtures.
- Limit: the introduction does not certify ktChecker's required Native/KLIB, cinterop, compiler-plugin, incremental-lifetime, or source-set behavior.

<a id="e03"></a>
## E03 — Analysis API stability boundaries

- Source: [Analysis API evolution](https://kotlin.github.io/analysis-api/contribution-guide/api-evolution/).
- Relevant facts: the stable category covers the user-facing Analysis API surface without opt-ins. The Platform Interface and Standalone API are in the unstable category. Kotlin PSI is described as not formally stable despite practical stability. Low-level API and implementation details have no cross-version compatibility guarantees and are reserved for internal use.
- Consequence: keep `Ka*`, `Kt*`, FIR, and IntelliJ types out of the durable engine protocol; do not base the initial design on directly exposing FIR internals to every rule. Any unavoidable internal access needs an explicit exception, exact version pin, owner, and regression tests.
- Limit: a public class appearing in generated reference documentation is not, by itself, a stability guarantee.

<a id="e04"></a>
## E04 — PSI is not type resolution; semantic lifetimes

- Source: [Analysis API fundamentals](https://kotlin.github.io/analysis-api/topics/Fundamentals/).
- Relevant facts: Kotlin separates its PSI syntax representation from semantic information. A `KaSession` sees the use-site module and its dependencies. Analysis runs inside read actions; sessions and most derived objects have lifetimes confined to their analysis block/read action. The guide warns against retaining them in long-lived caches and documents symbol/type pointers for later restoration.
- Consequence: serialize owned facts, not live PSI/symbol objects; enforce session ownership and disposal. A pointer can fail restoration and is not a durable cross-version symbol identity. A syntax visitor cannot establish overload resolution or platform-specific type facts.
- Limit: these rules do not prove a particular standalone implementation is safe for concurrent mutable sessions; certify that separately.

<a id="e05"></a>
## E05 — Detekt light/full analysis and target limitations

- Source: [Detekt type resolution](https://detekt.dev/docs/gettingstarted/type-resolution/).
- Observed documentation version: **2.0.0-alpha.6**.
- Relevant facts: type-requiring rules use `RequiresAnalysisApi`; the K1 `BindingContext` was removed in Detekt 2.0. CLI full analysis requires `--analysis-mode full`, classpath, and JVM target; default mode is light. In KMP projects, documented type-resolution tasks are generated for **JVM and Android** compilations; Native, JS, and Wasm use source-set tasks **without type resolution**.
- Consequence: reuse audited Detekt checks where their requirements are met, but never count a light run as semantic coverage or claim this integration alone supplies Native semantic analysis.
- Limit: task availability and a marker interface do not guarantee a complete, correct model or semantic support for every language/compiler version.

<a id="e06"></a>
## E06 — Detekt compatibility is a tuple, not a brand name

- Source: [Detekt compatibility table](https://detekt.dev/docs/introduction/compatibility/).
- Observed row: **Detekt 2.0.0-alpha.6 / Gradle 9.6.1 / Kotlin 2.4.10 / AGP 9.3.1 / Java target 1.8 / tested JDK 25**. The page describes the table as tool versions used to compile the Gradle plugin; its JDK column is the highest JDK tested, not a minimum runtime version.
- Relevant fact: the project attempts compatibility but does not present arbitrary version mixing as guaranteed.
- Consequence: the observed Detekt Kotlin version differs from E01's latest Kotlin version. An adapter must distinguish the project's compiler, the provider's compiler libraries, the provider runtime JDK, the Gradle runtime JDK, and the build target.
- Limit: the table is not proof that an alpha provider covers Kotlin 2.4.21 or that Java 25 is mandatory for every Detekt operation.

<a id="e07"></a>
## E07 — Compiler classpath isolation is necessary

- Source: [Detekt Gradle integration: Gradle runtime dependencies](https://detekt.dev/docs/gettingstarted/gradle/).
- Observed documentation version: **2.0.0-alpha.6**.
- Relevant fact: Detekt describes tight coupling to a specific Kotlin compiler runtime; replacing its compiler dependency through dependency alignment can produce a compiler-version mismatch error.
- Consequence: do not let project dependency resolution replace a provider's compiler JARs; do not put multiple incompatible compiler stacks into the broker classloader. Separate worker processes and exact dependency manifests avoid accidental linkage mixing, though compatibility still needs tests.
- Limit: a process boundary prevents classloader collisions, not malicious project code or incompatible language semantics.

<a id="e08"></a>
## E08 — Gradle Tooling API capabilities and limitations

- Source: [Embedding Gradle using the Tooling API](https://docs.gradle.org/current/userguide/tooling_api.html).
- Relevant facts: the API queries build models, can run/cancel builds, understands the wrapper, can install a Gradle distribution, and uses the Gradle daemon. Build actions can query models. Current documentation distinguishes version-dependent resilient model fetching: `fetch` requires client/target support from 9.3, partial daemon-side models from 9.4, and changed operation-failure behavior from 9.7.
- Consequence: use a version-aware importer and explicit partial-model records. Disable implicit provisioning unless separately authorized. Querying a generic project model must not be equated with reconstructing every KMP/AGP compilation's effective compiler arguments, friend paths, generated sources, cinterop, or convention-plugin behavior; a tested custom exporter is a **ktChecker design requirement**, not an upstream guarantee.
- Limit: Tooling API cross-version connectivity does not imply every requested model exists, every target is supported, or configuration is side-effect-free.

<a id="e09"></a>
## E09 — Gradle import is code execution

- Source: [Gradle security best practices](https://docs.gradle.org/current/userguide/best_practices_security.html), especially **Do not Run ./gradlew on Untrusted Projects**.
- Relevant facts: wrapper scripts/JARs can be modified; build scripts can execute arbitrary code during configuration; IDE Gradle sync also executes build logic. Wrapper/distribution verification is recommended.
- Consequence: no automatic Gradle import, build, plugin load, or dependency download for an untrusted checkout. A separate JVM is not a security sandbox. Offline resolution is not an operating-system network restriction.
- Limit: a valid upstream wrapper checksum does not make the project's build logic trustworthy, nor does dependency verification prove a dependency benign.

<a id="e10"></a>
## E10 — Effective compiler options are per compilation

- Source: [Kotlin Gradle compiler options](https://kotlinlang.org/docs/gradle-compiler-options.html).
- Relevant facts: configuration exists at extension, target, and compilation/task levels; more specific settings override defaults. The documentation explains how to inspect effective compiler arguments in Gradle logs.
- Consequence: hashing only a top-level Kotlin version or parsing a `kotlin {}` block is insufficient. Import the effective ordered option set for each compilation, including language/API versions, target, opt-ins, plugin options, and additional arguments.
- Limit: debug logs can contain private data and are a troubleshooting aid, not a stable machine protocol. Prefer a versioned structured exporter.

<a id="e11"></a>
## E11 — Kotlin incremental compilation is useful precedent, not a lint proof

- Source: [Compilation and caches in the Kotlin Gradle plugin](https://kotlinlang.org/docs/gradle-compilation-and-caches.html).
- Relevant facts: JVM incremental compilation uses classpath snapshots; the documentation distinguishes member-level fine-grained snapshots and class-ABI coarse-grained snapshots. Kotlin/JS uses a different mechanism. The Kotlin compiler daemon is distinct from the Gradle daemon and can have its own lifetime and memory configuration.
- Consequence: reuse proven compiler facilities where appropriate, but model rule dependencies separately: a rule about side effects or an inline body can depend on more than the ABI needed for compilation. Account for all daemon memory, not just the broker heap.
- Limit: this page does not establish an incremental analysis API contract for all targets or guarantee equivalence of ktChecker's incremental rule results.

<a id="e12"></a>
## E12 — Expect/actual and source-set topology

- Source: [Expected and actual declarations](https://kotlinlang.org/docs/multiplatform/multiplatform-expect-actual.html).
- Relevant facts: common expected declarations are matched to platform actual declarations; actual declarations can live in intermediate source sets, and actual typealiases can satisfy expected declarations.
- Consequence: use the compilation/source-set graph and selected target context, not folder-name heuristics or a single project-wide symbol map. Changes in either side, typealias targets, or hierarchy edges invalidate the corresponding target analyses.
- Limit: absence of a platform declaration from the checked files is not evidence that the project has no valid actual declaration.

<a id="e13"></a>
## E13 — Generated-output dependency propagation

- Source: [KSP incremental processing](https://kotlinlang.org/docs/ksp-incremental.html).
- Relevant facts: KSP tracks source/output dependencies; outputs are classified as aggregating or isolating, per output rather than only per processor. Resolution tracing and shared outputs propagate dirtiness. Aggregating outputs can depend on changes beyond a single source file.
- Consequence: carry generator inputs, processor/options versions, output manifests, and dependency provenance; do not treat an unchanged generated directory timestamp as freshness. Unknown, untracked, or non-incremental generators force conservative regeneration/invalidity, subject to execution consent.
- Limit: KSP's dependency contract is not automatically available to ktChecker, and third-party processors may declare inputs incorrectly. Validate adapters and retain a full-generation comparison test.

<a id="e14"></a>
## E14 — C interop has inputs beyond Kotlin source

- Source: [Interoperability with C](https://kotlinlang.org/docs/native-c-interop.html).
- Relevant facts: `cinterop` consumes native library/header information and generates Kotlin bindings. The companion definition-file documentation in E19 describes configuration and compiler/linker inputs.
- Consequence: include transitive headers, definition files, module maps/frameworks, SDK/sysroot, toolchain, target, macro/compiler flags, generated binding artifacts, and provenance in the relevant input closure. A `.kt`-only diff cannot prove unaffected Native semantics.
- Limit: those complete transitive dependency fingerprints are a required implementation task, not supplied to ktChecker by this documentation.

<a id="e15"></a>
## E15 — Host-dependent Native coverage

- Source: [Kotlin/Native supported targets and hosts](https://kotlinlang.org/docs/native-target-support.html).
- Relevant facts: targets have different support tiers; listed Apple targets require Apple macOS hosts. Test-running availability is explicitly host/target-specific.
- Consequence: cross-platform source inspection is not proof that iOS compilation, linking, simulator/device behavior, or desktop packaging was checked. Report the exact host/SDK/target capabilities and unmet prerequisites.
- Limit: a Kotlin target's upstream tier is neither a ktChecker support certification nor a promise that all target operations are possible on Linux/Windows.

<a id="e16"></a>
## E16 — jlink is packaging, not a different analysis engine

- Source: [JDK 25 `jlink` manual](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jlink.html).
- Relevant fact: `jlink` builds a custom runtime image from modules and dependencies; the maintainer remains responsible for runtime updates.
- Consequence: evaluate a trimmed runtime for installation convenience after the provider/JDK matrix works. It does not remove compiler dependencies, prove small resident memory, or replace a project's separately required JDK/SDK.
- Limit: ktChecker image size, module completeness, startup time, and redistribution/patching process remain untested.

<a id="e17"></a>
## E17 — Native Image introduces a compatibility project

- Source: [GraalVM Native Image reachability metadata](https://www.graalvm.org/latest/reference-manual/native-image/metadata/).
- Relevant fact: dynamic JVM features such as reflection and resource access require reachability handling/metadata when building a native image.
- Consequence: a native launcher/bridge may be reasonable after measurement, but converting dynamically loaded Kotlin compiler/IntelliJ/provider stacks is not a cost-free first-release performance fix. Keep versioned JVM workers unless an experiment proves a net benefit and maintained compatibility.
- Limit: no ktChecker Native Image experiment or speed comparison has been run.

<a id="e18"></a>
## E18 — Dependency integrity does not establish semantic identity by version alone

- Source: [Gradle dependency verification](https://docs.gradle.org/current/userguide/dependency_verification.html).
- Relevant facts: verification can use checksums and signatures for dependency artifacts, with committed verification metadata and explicit configuration.
- Consequence: record artifact byte digests and verification policy, not just Maven coordinates or filenames. Dependency refresh and mutable/snapshot artifacts need explicit handling.
- Limit: verification is not a source-input completeness proof, a permission to download, or a guarantee that an artifact contains no malicious code.

<a id="e19"></a>
## E19 — Native definition-file options

- Source: [Native definition files](https://kotlinlang.org/docs/native-definition-file.html).
- Relevant facts: the documented properties include `headers`, `headerFilter`, Clang `modules`, `language`, `compilerOpts`, and `linkerOpts`, with platform-specific option examples. Compiler and linker options are passed to the corresponding native tools.
- Consequence: cinterop identity includes effective per-platform configuration and the native inputs selected by those options, not merely the `.def` filename or generated Kotlin files. Include Objective-C module/framework inputs where used.
- Limit: the complete transitive header/module dependency capture and SDK/toolchain fingerprinting still require implementation and tests.

## Retrieval record

The SHA-256 column fingerprints the bytes retrieved for the cited page, **not** a release artifact. These are live-documentation observations; refresh them at implementation and release gates. No downloaded compiler, SDK, plugin, or binary has been certified by this research.

| Source | Fetched UTC | SHA-256 of response |
|---|---|---|
| E01 | 2026-11-04T05:44:58Z | `5f37dd53eea581c8cb10e01689a1b7b6d998983fa2badb75320d9b3007128816` |
| E02 | 2026-11-04T05:43:57Z | `490f9ea9b1f8fc7e68d136ec0c326157c86832bbf193cdd535717165bba2e893` |
| E03 | 2026-11-04T05:44:58Z | `3825b61058a908ba14bffd5a802db7a2f1f478b792bf4cf8cb0a4b8324d365e1` |
| E04 | 2026-11-04T05:44:58Z | `9663885cadade908bfa4074c112af7edcf8fa05398e5cbb7105f89c8dcac4bf9` |
| E05 | 2026-11-04T05:43:57Z | `2cea54acec0b819eef3a6ad96ae915b59199a175d3b192c51c5e3be91aa178fb` |
| E06 | 2026-11-04T05:43:57Z | `3a8fa2751e4f405763c9013a2493fbb640e5b331be3ea86eecce8e62ae9c0ffd` |
| E07 | 2026-11-04T05:45:00Z | `a916d988c269b1281467874f97dffa9d1b4631211b2808d366ce7fd655cab9a1` |
| E08 | 2026-11-04T05:43:57Z | `4c941bc37dccc9d8ebcfe407e168511db8b407e77afe252eb927e92230a32050` |
| E09 | 2026-11-04T05:44:58Z | `ab0835fcd5a120d73a4897b15f6efd72a8c826f9cc1e5f700acfb27d57ed6e7c` |
| E10 | 2026-11-04T05:43:59Z | `608c02f198e9d7dd0d3604bf4921e7ee5dcf3dd5ab2eb0fa948fe264eac8234f` |
| E11 | 2026-11-04T05:43:58Z | `f103559ce2fb4d9d61d5e1a06ac33778facccbd28e75d2266815501298ddbc4f` |
| E12 | 2026-11-04T05:43:58Z | `a736cbe1e04c4da3a648f1f9418b2d687fcadb625a31d56dfb04df8fa8157d6a` |
| E13 | 2026-11-04T05:43:58Z | `548bdcf1223279ecf570528573bf905c877c446792942bc1891ed09b12beb2b1` |
| E14 | 2026-11-04T05:43:58Z | `945d4961a8ba6972211836e6205d673675e89a47671cc2b92f15c7e2bd508b4a` |
| E15 | 2026-11-04T05:43:58Z | `76f26fc62ab3b97068342c09e276bab7f391e37e9961ffe7b8494fba10fba3fc` |
| E16 | 2026-11-04T05:45:00Z | `c2142840c7fb44f6adbeb3e038713538b15c30eaf835cc507da71e5ac59a8a51` |
| E17 | 2026-11-04T05:45:00Z | `34789666fb65582febbaf112a2a75355996103756696fb6090f1c3766c6eab3d` |
| E18 | 2026-11-04T05:44:59Z | `7691a29b79971e952d34e0025356bf9e6fe895a5f10fcf24f080e5bf8e03cf7b` |
| E19 | 2026-11-04T06:03:03Z | `0e370e54122fb1799ecb1721f42eeaf4180d33706c89565773a321f5d160a01a` |

## Unsuccessful probes and limits of the research

- `https://kotlin.github.io/analysis-api/standalone-usage.html` — HTTP 404 on 2026-11-04; not used as evidence.
- `https://kotlin.github.io/analysis-api/api-stability.html` — HTTP 404 on 2026-11-04; replaced with the successful current introduction and API-evolution pages.
- `https://detekt.dev/docs/gettingstarted/compiler-plugin/` — HTTP 404 on 2026-11-04; no compiler-plugin feasibility claim relies on it.
- The generated [StandaloneAnalysisAPISession reference](https://kotlin.github.io/analysis-api/api/analysis-api-standalone/org.jetbrains.kotlin.analysis.api.standalone/-standalone-analysis-a-p-i-session/) was also successfully fetched. Its existence confirms an API reference, not a working ktChecker adapter or Native compatibility.
- No runnable analysis prototype, Gradle importer, host build, incremental-equivalence suite, or timing experiment was completed for this ledger. Those are explicit implementation gates, not implied by successful HTTP requests.

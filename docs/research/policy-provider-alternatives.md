# Declared policy/provider alternatives

**Research retrieved 2026-11-04 UTC; proposed reuse only, not executed or qualified support.**
This focused comparison evaluates Konsist, ArchUnit and diKTat before writing overlapping custom checks.
It is **not a full atomic rule inventory** and adds no rule IDs, launch counts or runtime claims.
Architecture and style must be explicitly selected project policy; library adoption does not make
Clean Architecture, MVI, naming suffixes, package layers or one UI toolkit universally correct.

## Observed versions, licensing and maintenance

| Provider | Observed latest non-prerelease | Inspected own-source license |
| --- | --- | --- |
| Konsist | [v0.18.1; 2026-10-10](https://api.github.com/repos/LemonAppDev/konsist/releases/latest) | [Apache-2.0](https://raw.githubusercontent.com/LemonAppDev/konsist/v0.18.1/LICENSE) |
| ArchUnit | [v1.5.1; 2026-09-25](https://api.github.com/repos/TNG/ArchUnit/releases/latest) | [Apache-2.0](https://raw.githubusercontent.com/TNG/ArchUnit/v1.5.1/LICENSE) |
| diKTat | [v2.0.0; 2023-12-18](https://api.github.com/repos/saveourtool/diktat/releases/latest) | [MIT](https://raw.githubusercontent.com/saveourtool/diktat/v2.0.0/LICENSE) |

The fetched repository APIs report `archived: false` for all three. Recorded last pushes were
[Konsist: 2026-11-04](https://api.github.com/repos/LemonAppDev/konsist),
[ArchUnit: 2026-11-03](https://api.github.com/repos/TNG/ArchUnit) and
[diKTat: 2026-06-03](https://api.github.com/repos/saveourtool/diktat).
Those are maintenance observations, not an SLA, proof of maintained releases or compatibility.
diKTat's older release is a compatibility review signal, not proof of abandonment or vulnerability.
ArchUnit's [README](https://raw.githubusercontent.com/TNG/ArchUnit/main/README.md) also records
redistributed ASM (BSD) and Guava (Apache-2.0); retain applicable third-party notices for any bundling.

## Konsist: reuse source/declaration policy assertions

The [tagged README](https://raw.githubusercontent.com/LemonAppDev/konsist/v0.18.1/README.md) supplies
JUnit/Kotest assertions over classes, declarations, naming, packages and explicitly defined layers.
The [file model](https://raw.githubusercontent.com/LemonAppDev/konsist/v0.18.1/lib/src/main/kotlin/com/lemonappdev/konsist/core/declaration/KoFileDeclarationCore.kt)
uses Kotlin PSI `KtFile`; it is a source-policy model, not evidence that target code compiled.
Published [0.18.1 metadata](https://repo.maven.apache.org/maven2/com/lemonappdev/konsist/0.18.1/konsist-0.18.1.module)
is **JVM 17**, with Kotlin stdlib/compiler-embeddable **2.4.21** dependencies.
The [KMP starter's policy test module](https://raw.githubusercontent.com/LemonAppDev/konsist/v0.18.1/samples/starter-projects/kmp-gradle-kotlin-junit5/konsistTest/build.gradle.kts)
is itself Kotlin/JVM with a JVM toolchain; reading common/Native `.kt` files is not Native execution.
[Scope creation](https://raw.githubusercontent.com/LemonAppDev/konsist/v0.18.1/lib/src/main/kotlin/com/lemonappdev/konsist/core/container/KoScopeCreatorCore.kt)
selects modules/source sets by filesystem paths. Reconcile that scope with the actual Gradle graph,
custom hierarchies, generated files and active compilations; directory matching cannot certify them.
Prefer an adapter for already declared Konsist policies over duplicate custom package/name checks.
Do not treat a declaration-name/parent query as a replacement for compiler-resolved library semantics,
actual expect/actual compatibility, ownership/effect analysis or an iOS compiler/toolchain check.

## ArchUnit: reuse imported JVM bytecode architecture tests

The [official guide](https://www.archunit.org/userguide/html/000_Index.html) documents package/class
relationships, layers, cycles and Kotlin JUnit integration over imported **Java bytecode**.
Its [released artifact](https://repo.maven.apache.org/maven2/com/tngtech/archunit/archunit/1.5.1/archunit-1.5.1.pom)
is `com.tngtech.archunit:archunit:1.5.1`; Kotlin/JVM classes are analyzed after compiler lowering.
This is useful for declared JVM class/package dependencies, not Kotlin source formatting or all
source-level semantics. Imports must identify the exact successful compilation, classpath, filters,
compiler/JDK tuple and artifact hashes; stale or partially built classes cannot imply a clean snapshot.
The guide explicitly explains missing-class resolution and **stubs** that omit superclass/annotation
facts. Preserve such incompleteness instead of certifying a dependency policy from incomplete imports.
Generated/synthetic Kotlin classes and optional reflection/custom conditions need reviewed policies.
Native/iOS binaries and `.klib`, JS/Wasm outputs and Android DEX are not JVM classfile coverage.
An Android pre-DEX classfile profile needs its own artifact mapping/tests; it does not validate DEX/R8
or device behavior. Source ranges and rule aliases require a wrapper, not guesses from class names.

## diKTat: evaluate reusable syntax/style checks under selected policy

The [tagged README](https://raw.githubusercontent.com/saveourtool/diktat/v2.0.0/README.md) identifies
AST visitors using ktlint parsing, strict coding conventions, configuration and suppression.
It says rules are enabled by default; **ktChecker must not silently adopt that entire strict policy**.
The [released rules metadata](https://repo.maven.apache.org/maven2/com/saveourtool/diktat/diktat-rules/2.0.0/diktat-rules-2.0.0.module)
is JVM 8 with compiler-embeddable **1.9.21**; the
[tagged version catalog](https://raw.githubusercontent.com/saveourtool/diktat/v2.0.0/gradle/libs.versions.toml)
records ktlint **1.0.1**. A newer project's Kotlin/parser/JDK/plugin tuple is not thereby supported.
The release notes announce a standalone CLI and preparatory Kotlin 2/FIR/IR work; preparation is not
proof of a released compiler-semantic or Native adapter. Parsing Native source is still source analysis.
Use check-only, pinned tooling and explicitly selected IDs/configuration before considering fixes.
The [released inspection list](https://raw.githubusercontent.com/saveourtool/diktat/v2.0.0/info/available-rules.md)
was retrieved, but not fully atomized, deduplicated or tested here; its size is not qualified coverage.

## Reuse, execution trust and remaining gaps

Route overlapping functionality through the existing core/style and platform/library catalogs;
compare selected Detekt/ktlint/Lint/custom ownership before introducing replacement checks.
Konsist and ArchUnit user policies are executable **test code**, not inert configuration. Their build
scripts, test discovery, custom predicates and possible reflection require the repository-execution
trust contract: reviewed revision, approved allowlisted commands, bounded inputs/resources and isolation.
Do not run newly discovered/generated policy tests or download-and-execute helpers merely because
an MCP client asked for analysis; unavailable execution must remain a visible coverage gap.
Adapters must preserve named policy identity, scope/configuration, raw provider evidence, exclusions,
suppression/baselines and per-ID failure/abstention. Normalize duplicates rather than count each provider.
All integrations still need the independent gates in [validation-ci.md](../validation-ci.md), including
valid architectural alternatives, current syntax, partial builds, empty scopes and incremental changes.
Unresearched/material gaps include full atomic mappings, precise source ranges, supported version/target
matrices, custom policy execution isolation and unsupported Native semantics. These are not satisfied
by a provider title. Reserve custom semantic/graph rules for genuinely unmet contracts with evidence.
Source snapshots and URL/date/SHA-256/failure records were retained in temporary
research scratch storage, not committed as portable implementation evidence.
Guessed Konsist build and diKTat root-POM paths returned 404; verified tagged paths/metadata replaced
them as evidence. No provider tests, Kotlin builds, Native execution or precision measurements ran.

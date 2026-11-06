# Explicit KGP 2.4.10 configured-model experiment

## Status and non-claims

This is a separately selected, source-authored experiment, **not** a qualified
model exporter or an authentic COMPLETE-model demonstration. Its initial packet
contains 48 authored test methods; none were compiled or executed by the packet
author. The helper controls use named API fakes, and importer controls use
synthetic owner callbacks and dummy artifact bytes. They are not a real Gradle,
KGP, compiler, provider, FIR, or configuration-read-closure receipt.

No rule coverage, qualification, corpus, release scope, or platform readiness is
promoted by this addition. The original default/observed model route is preserved.
No CLI route, owner receipt issuer, FULL-provider authority, compiler layout,
produced-output authority, binary-symbol ownership, or configuration-read closure
is supplied here. A genuine owner configuration/input closure remains required
and unestablished by this work; it must not be replaced with scanned files,
matching JSON/digests, successful unit tests, or an observed-only model.

## Closed, explicit selection

| Route | Resource / report identity |
| --- | --- |
| Existing `ProjectModelImporter.import` and `ObservedProjectModelImporter.import` | Existing `ktchecker-model.init.gradle`, exporter `ktchecker-gradle-kmp-model/1` |
| Explicit `ProjectModelImporter.importKgp2410` | New `ktchecker-model-kgp-2.4.10.init.gradle`, exporter `ktchecker-gradle-kgp-2.4.10-model/1` |

`Kgp2410ModelExporter` exposes that new script, report
`model-export-kgp-2.4.10.json`, and task `ktCheckerKgp2410ModelExport`.
The internal descriptor is a closed two-case enum, not a caller-supplied adapter
or version allowlist. Neither JSON, the reported compiler version, nor a digest
automatically selects the new route. The selected resource digest is checked
before ingress authorization; an old request pin cannot enter the new path and
vice versa. The original resource and exporter object remain byte-identical.

The new profile requests only the new export task, offline, with one worker and
the existing bounded runtime settings. It exposes no prerequisite-task list.
This is a command profile, not execution permission or proof that arbitrary
project configuration, hooks, or project-defined task dependencies did not run.
The owner must authorize and verify the exact actual successful/quiescent
invocation and all its inputs. No Gradle version is silently substituted or
inferred from the KGP source-variant name; the exact owner runtime is separate.

`importKgp2410` still defaults to DENY and consumes the original exact single-use
`ModelIngressPermit`. Its COMPLETE branch preserves both original execution and
complete-input checks, before and after model reading. Exporter issues and all
compilations remain present. An issue in an unselected compilation remains a
whole-model applicability gap. The context policy reports the actual validated
exporter identity instead of labeling the new route as the old exporter.

The new route rejects nonnull `jvmArgumentRoles` or nonempty
`deferredJvmOutputs` **before** any legacy role-origin callback. The old 2.4.21
role vocabulary is not transferable to 2.4.10. The configured-input helper
returns only a Boolean check result; no `VerifiedJvmArgumentLayout`, produced
graph, projection, or authority is manufactured. There is no new observed path.

## Actual 2.4.10 producer semantics, not signature equivalence

The following official source artifact was fetched once from Maven Central and
read only as bounded ZIP data. No class was loaded, and no second network request
was made for this implementation:

- `org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10`,
  `kotlin-gradle-plugin-2.4.10-gradle813-sources.jar`, 3,538,728 bytes,
  SHA-256 `70e277a44fbcf4d58444bb8b1459cb80686380537aa7414b80745a71607b36bb`.
- The cached `.module` metadata independently names `gradle813SourcesElements`
  and that exact file/size/hash; its SHA-256 is
  `0e7713d84605da0bee038b578f31795882ccd721220a79cc36736ac9af9cb412`.
- Matching locally inspected binary: `kotlin-gradle-plugin-2.4.10-gradle813.jar`,
  26,714,711 bytes, SHA-256
  `13e22c869df3972db9496b47bf2815fbcfb61e0439f96cbd8d6aeb912b481375`.
- API artifact: `kotlin-gradle-plugin-api-2.4.10-gradle813.jar`, 275,012 bytes,
  SHA-256 `74c4e4f7d037e850bab666db12c90a49e065d9495fd2dfdaa7bc39dd963083d6`.

These are primary-source/static-API review pins, **not** proof that a future
Gradle task actually used those loaded bytes. That coherent runtime origin must
be established by the owner independently. Matching method names alone are not
such authority.

Source paths below are inside the pinned source JAR:

1. `org/jetbrains/kotlin/gradle/plugin/KotlinCompilerArgumentsProducer.kt:17–24`
   defines exactly `Primitive`, `PluginClasspath`, `DependencyClasspath`, and
   `Sources`; `:59–79` creates one arguments object; `:87–125` applies each
   included contribution to that object and rethrows failures when nonlenient.
   The helper loads the APIs through the actual task's loader, requires that exact
   enum domain and static `(Set, boolean)` factory, includes all four types, and
   passes `false`. It serializes the same returned object, once. There is no
   partial getter, alternate loader, guessed overload, or lenient fallback.
   `org/jetbrains/kotlin/gradle/internal/CompilerArgumentAware.kt:37–87` confirms
   that the deprecated serialized getter includes only Primitive/PluginClasspath;
   its ignore-classpath-errors variant is lenient, not a complete substitute.
2. `org/jetbrains/kotlin/gradle/tasks/KotlinCompile.kt:199–250` contributes
   destination, task/plugin options, the required nonnull module name, frontend
   and refinement settings to that same object. `:252–258` composes the default
   plugin files first and nullable plugin-data classpath second. Default scripting
   plugins must therefore remain facts; they are not stripped to force an empty
   plugin domain. `:260–264` contributes friends and filters missing libraries.
   `:267–295` appends script, Java, then Kotlin FileTree iteration order.
3. `org/jetbrains/kotlin/gradle/dsl/KotlinCommonCompilerToolOptionsHelper.kt:10–19`
   appends configured `freeCompilerArgs` to `freeArgs`. Consequently the new
   ordinary-source check also rejects extra free arguments rather than silently
   treating them as source members. The JVM helper `KotlinJvmCompilerOptionsHelper.kt:10–24`
   sets typed target/module/no-JDK options and build-tool `noStdlib`, `noReflect`,
   and `allowNoSourceFiles`. The common helper forwards API/language/opt-in options.
4. `org/jetbrains/kotlin/compilerRunner/ArgumentUtils.java:29–44` delegates
   serialization to `ArgumentsToStrings.toArgumentStrings(arguments, false)`.
   The exporter preserves the returned vector; it does not parse, sort, strip,
   or normalize it into a standalone invocation.
5. `KotlinCompile.kt:395–445` calls the runner later with the selected task
   `buildJvm.javaHome`. In
   `org/jetbrains/kotlin/compilerRunner/GradleKotlinCompilerRunner.kt:133–141`,
   the runner fills `args.jdkHome` only if it is null and `noJdk` is false. It
   serializes later at `:179–192`. Therefore configured arguments with a null
   JDK operand are **not an executed argv**. The exporter separately captures the
   selected JDK and never injects that later default into the configured vector.

The private review packet records exact hashes of these source entries, the
nullable metadata sources, selected classfile declarations, retained source
context, focused diff, and source-only checks. Binary/API metadata is supplemental;
the semantic explanation above depends on the actual 2.4.10 primary sources.

## Deliberately narrow applicability

The helper checks ordinary JVM producer and lowering declarations/bridges on the
actual task and requires the exact arguments implementation. It rejects unknown
dispatch; same class names without owner-verified byte origin are not authority.
Configured source operands must equal the actual task's Kotlin FileTree order
and be a bijection of all model source members, whose separate membership order
is retained. This narrow checker accepts only regular lower-case `.kt` files.
Declared classpath members must match the model in order and be
unaliasable regular `.jar` paths. Missing entries cannot be repaired by replaying
KGP's `exists()` filter. Destination and an optional original JDK operand must
match independently captured task selections. No checked paths are rewritten.

The initial route deliberately retains real applicability issues for:

- KGP/compiler versions other than exactly 2.4.10; non-JVM platforms; source
  refinement/resource members; Java/scripts/alternate frontend inputs; generated
  inputs and source-generator provenance gaps;
- all actual association, friend, or project-produced domains, including resolved
  transitive project components; known direct edges are retained with unknown
  producer compilation rather than guessing a `main` producer or output state;
- nonempty default/plugin classpaths, plugin operands, auxiliary inputs, and
  nonnull plugin-data objects **even if apparently empty**;
- a plugin-options value other than an actually empty task List, response-file
  operands, unsupported domain arrays, missing getter/value observations, or
  any failed all-type capture.

`CompilerPluginConfig` primary source was absent from the reviewed archive.
The adapter therefore does not infer lazy `allOptions()` semantics or admit a
nonempty List of empty-looking option containers. Only the distinct actual
`provider_absent` and `value_absent` observations plus independent empty
task/arguments/plugin-file observations can pass the narrow empty-domain check.
Actual unknown/nonnull states remain gaps, never aliases for an empty model.

Source and artifact capture rejects path aliases instead of granting a new
canonical role. Runtime capture observes bounded, link-free FILE contents and
retains failures as model gaps; it is not the separate live whole-JDK image
capability (directory shape, executable state, invocation custody, and repeated
live verification remain other obligations).

A normal main/test project may retain associations, output directories or default
scripting plugins and therefore remain unavailable as a **whole** model here.
No such facts or unrelated compilation obligations may be removed to make an
example succeed. A successfully emitted report alone establishes neither a
genuine COMPLETE model nor an accepted FULL or FIR execution.

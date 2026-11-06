# Exact Kotlin 2.4.10 FULL experiment — not a release route

This separate, opt-in seam exposes the pinned Detekt 2.0.0-alpha.6 **FULL** Analysis
API to one initial representative, `potential-bugs:MissingUseCall`. It is not
installed in the ordinary controller or CLI. Existing LIGHT behavior, all 1,663
catalog obligations, and existing coverage/compatibility gaps remain intact.

**Current status:** source-authored experiment; native evidence must come from a
separately reviewed, owner-admitted run. Unit projection tests, pins, metadata,
and this document are not Detekt/compiler execution or qualification evidence.

## The actual-exporter gap is intentional and remains open

The existing `ktchecker-model.init.gradle` exporter and its nominal ingress are
pinned to KGP/compiler **2.4.21**. Actual 2.4.10 exports carry
`kotlin_plugin_version_unsupported` / `kotlin_compiler_version_unsupported`
issues; its original-JVM-argument adapter is also explicitly 2.4.21. This seam
does not remove those issues, change exporter acceptance, or transplant that
adapter. It rejects modeled compiler 2.4.21 rather than calling embedded 2.4.10
compatible with it.

Consequently, the current native harness is honestly a **synthetic imported
model / actual Detekt engine** experiment. Its importer, registered source
overlay, pinned artifacts, and actual engine are real code; its export,
configuration-closure, artifact-enrollment and kernel-archive receipts are
explicitly synthetic. It does not claim an actual successful Gradle export.
Future actual-exporter work requires separate owner design and review; no
nominal ingress is weakened here. Host code built with Kotlin 2.4.21 is likewise
not evidence that Detekt's embedded compiler supports a 2.4.21 project.

## Closed authority and input boundary

* Retain the same strict `VerifiedProjectModel`, whole parent input, actual
  compilation identity, owner source root, independently retained compile task
  and original compiler destination. The origin is a live trusted-owner object,
  not a JSON report or an output path inferred from `-d`.
* Require issue-free strict model prerequisites, exact modeled compiler 2.4.10,
  actual JVM platform, explicit language/API 2.4 and JVM target 21. No Common,
  Native, generated/Java/auxiliary, compiler-plugin, resource, friend, associated
  compilation, project-produced/deferred output or source refinement inference.
* Preserve the **entire original argv**, source membership order, scope (including
  an unnamed JVM target), classpath order, model/ingress identities, all parent
  obligations and the parent's empty execution-plan map. Model argv describes
  intended task configuration, not an observed standalone compiler invocation.
* A total bounded projection accounts for every original token. Required roles
  are language/API/JVM target, full ordered classpath, independent output, an
  explicit bounded `-module-name`, `-no-stdlib`, `-no-reflect`, and a bijection of
  captured `.kt` operands. Optional exact JDK home,
  `-Xrender-internal-diagnostic-names` and `-Xallow-no-source-files` are supported.
  Equivalence between omitted standalone-compiler and Detekt module-name
  defaults has **not** been established from reviewed source. Omission therefore
  remains an explicit applicability gap: no default is injected and no catalog
  obligation is removed. Unknown/duplicate flags, scripts,
  response files, plugins, friends, progressive/Werror/nowarn and unaccounted
  operands abstain, not silently disappear. Configured source-operand order is
  retained separately from modeled membership order and feeds the projected
  Detekt input; it is not an observed compiler invocation. Compiler output is
  **not emitted**.
* Every selected compiler artifact has exact owner-selected path, coordinate,
  byte digest, size and order. The compiler-embeddable 2.4.10 pin is
  `sha256:9309638a2ee03e6bde9ef4b7444055a94b84ab906563675d71ff9aecb64da913`
  (60,352,373 bytes); the separate stdlib pin is
  `sha256:4ec0293bc3751423b203f1d8493251c57c42e73eb6377a6b8560d0974ff0a6df`
  (1,841,933 bytes). These modeled external compiler artifacts are never loaded
  or executed by this adapter. Detekt executes its separate five pinned artifacts.
* Reuse the live `KotlinJvmRuntimeImage`, **not** the 2.4.21 compiler toolchain or
  a reconstructed image from JSON. The complete owner-selected, link-free Linux
  JDK21 home is bound, including file bytes, directories/empty directories,
  release version/OS/architecture and executable bits. Modeled
  `compilation-jdk:<relative>` artifacts must project every image FILE uniquely
  at the exact path/digest/size. No launcher-only or linked-system-JDK fallback.
* Reuse the existing exact kernel source/regular-JAR archive relation. Only
  source root, dependency paths at `classpath/artifact-<ordinal>.jar`, and the
  disjoint output parent relocate. Original context/argv stays unchanged; the
  genuine CLI uses archive templates and the original source-token order.
* Require a live, revocable, provider-instance/exact-child-bound FULL permit and
  the **same** execution-permit object. Equal JSON/profile/grant copies do not
  create authority. A FULL marker with erased policies is a one-way rejection
  on legacy/LIGHT routes, never a downgrade into them.
* Retain the owner's **original** capture-to-attempt cancellation/deadline
  checkpoint through image capture, verification, process polling and final
  projection verification. A new child or image scan cannot refresh that budget.

Identity is cooperative quiescent disk verification, **not** loaded-code/JNI,
hostile-same-UID sandbox or transient fully-restored ABA attestation. Artifact
hashing adds drift checks; it does not replace owner immutable provenance.

## Seven fixed, independently authored native controls

The native task has seven reservations, no retries or cohort widening, one
original aggregate deadline (at most 600,000 ms) and at most 60,000 ms per child.
All scratch, original/effective/child descriptors, configuration, genuine report,
process outcome and exact pre-authored oracles are retained on **every** outcome.
Every negative must also have actual execution confirmation, owned supervisor,
exit 0 and the exact enabled MissingUseCall descriptor.

| Control | Expected findings | Distinction |
|---|---:|---|
| Direct and aliased imported JDK constructors | 2 | Same resolved Closeable type, different call spelling |
| Real standard `.use` on both streams | 0 | Resolved standard closure, not mere text |
| Local non-Closeable `FileInputStream` homonym | 0 | Same call spelling, different resolved type |
| Identical positive bytes with resolved `ignoreClass` | 0 | Owner config bound separately; exact class identity |
| File suppression plus unchanged separate-package neighbor | 1 | Real upstream suppression does not suppress the neighbor |
| Source-defined `java.lang.AutoCloseable` subtype | 1 | Type/supertype recognition, not an imported-name list |
| Local same-spelled `.use` that does not close | 1 | Only `kotlin.io.use` / `kotlin.use` are recognized |

A positive foreign test-compilation file remains in each **whole snapshot**, but
never in selected main compilation input. The whole parent retains all **1,663**
catalog IDs; the provider child retains all **276** mapped Detekt obligations.
The other 275 mapped rows explicitly abstain (including aliases/refinements).
This is a validation slice, not a reduced release scope.

The source-defined `Owned.close() = Unit` fixture proves subtype recognition
only; it does not establish a real resource lifetime or leak. The upstream rule
is a preference for recognized standard `use`, **not** ownership analysis:

* Explicit/manual `try/finally` + `close()` can still warn despite correct closure.
* A Closeable-returning enclosing function is broadly exempt and can hide an
  unrelated leak; return types that are not class symbols can also be missed.
* A legitimate ownership transfer to a Unit-returning consumer can warn, while
  a Closeable-returning wrapper is presumed to transfer ownership.
* Class-property initializers and other upstream special branches can be exempt.

Manual-close/transfer examples are explanatory FP/FN boundaries, **not** extra
native invocations or claimed tested ownership-refinement obligations. Findings
are always **warning / advisory / low**. Negatives are **unknown**, never passed.
All results remain partial, tested/qualified flags false, no certificates and no
complete-resolution, compatibility, compiler-emission or release claim.

## Explicit native execution surface (requires separate owner admission)

Ordinary `test` / `check`, `detektIntegration`, `detektModelIntegration`, and corpus
flags cannot select this task. An admitted run must supply exact already-present
compiler/stdlib paths and a genuinely prepared link-free JDK home; no download,
auto-selection or synthetic launcher is permitted in the native harness:

```text
./gradlew --offline --no-daemon --max-workers=1 :provider-detekt:fullSemanticTest \
  -PdetektFullConsent=missing-use-call-seven-2.4.10 \
  -PdetektFullJavaHome=<absolute-owner-selected-link-free-JDK21-home> \
  -PdetektFullCompiler=<absolute-pinned-compiler-embeddable-2.4.10.jar> \
  -PdetektFullStdlib=<absolute-pinned-stdlib-2.4.10.jar>
```

This template is **not execution authorization**. The task never uses cached or
up-to-date native outcomes. It runs the embedded compiler Analysis API inside
Detekt, but no standalone Kotlin compiler, bytecode-emission or Gradle model task.
The `--` compiler-tail integration is based on pinned source/ZIP metadata until
the admitted actual engine run verifies it. Failed native checks must remain
failed; do not relax pre-authored oracles after observing the detector.

## Reviewed source, not execution proof

The pinned potential-bugs sources archive is
`a983bd98eab31b6594992e2478110ef868cae5e956e38fc4d3d7c0b5bf610824`;
`MissingUseCall.kt` is
`e9961db6910a337c8f3e50d2a698c9cb9b75a4d5822b921c1f905ff27fea7a3e`.
Its `RequiresAnalysisApi` marker is FULL-only; it resolves call return-type
class symbols/supertypes and standard-use callable IDs. Reading those sources,
ZIP/compiler-version metadata, or synthetic projection outputs is not native
type-resolution or compatibility evidence.

# Authored Native throws cohort (source-only until separately executed)

This independent, opt-in Kotlin **2.4.21** KMP fixture adds four Native-specific
exception-export/override compiler contracts beyond expect/actual matching.
It uses the real Native stdlib, an explicitly selected nested **JDK 25**, and
actual `iosArm64` / `iosSimulatorArm64` compile tasks on an Apple host. There is
no Android plugin/SDK configuration and no JVM stand-in. The broker remains
JDK 21. Neither this source batch nor a small fixture assertion qualifies a
rule, target, provider, whole project or release. Full-release scope is unchanged.

## Exact four-ID scope

| Upstream FIR Native ID (declared ERROR) | Stable adapter ID |
| --- | --- |
| `THROWS_LIST_EMPTY` | `ktc.compiler.native.throws_list_empty` |
| `INCOMPATIBLE_THROWS_OVERRIDE` | `ktc.compiler.native.incompatible_throws_override` |
| `INCOMPATIBLE_THROWS_INHERITED` | `ktc.compiler.native.incompatible_throws_inherited` |
| `MISSING_EXCEPTION_IN_THROWS_ON_SUSPEND` | `ktc.compiler.native.missing_exception_in_throws_on_suspend` |

`cohort.json` specifies each authored case, its exact selected ID and expected
source anchor. Anchors are source ranges, **not previously observed emissions**.
`source-manifest.json` binds every authored input (excluding only itself), and
the separate driver pins that manifest digest before executing. Modifying any
input requires an explicitly reviewed new source freeze; it never upgrades old
execution receipts. The fixture model declares exactly the common file and the
selected case's `iosMain` file, not every captured alternative.

The pinned `FirNativeThrowsChecker` compares **exact exception ClassId sets**,
not Java-style narrowing/assignability. Annotation order does not matter.
Distinct inherited filters are diagnosed before an explicit override filter.
An absent annotation is distinct from an explicit annotation, including a
suspend override whose implicit cancellation behavior looks equivalent.
Suspend lists must contain `kotlin.coroutines.cancellation.CancellationException`
or one of the checker's fixed real stdlib superclasses (`IllegalStateException`,
`RuntimeException`, `Exception`, `Throwable`). Ordinary nonsuspend methods do not
have that requirement. These are compiler-checker contracts relevant to the
exception-export boundary; no NSError/Swift runtime behavior is measured here.

The upstream `throws.kt` seed uses `IGNORE_FIR_DIAGNOSTICS`, `ALLOW_KOTLIN_PACKAGE`
and synthetic stdlib declarations. It is attribution, **not K2 Native execution
evidence**, and is not copied into this fixture. Exact pinned checker,
registration, factory, suppression/configuration sources and hashes are in
`cohort.json`; repository Kotlin notices apply.

## Source-only checks

```sh
python3 -B providers/platform-fixtures/native/throws/test_sources.py
python3 -B -O providers/platform-fixtures/native/throws/test_sources.py
```

These thirteen checks inspect source/manifest contracts only. `-B` prevents
generated Python cache files from entering the authored tree. The separate
module suite has fifteen pure model and synthetic receipt methods; their JVM
execution must be recorded separately from the thirteen source-only checks.
They can run on Linux with broker JDK 21 and do not invoke the authored Native
fixture task or nested Apple compilation:

```sh
./gradlew :provider-native:test --tests dev.ktchecker.providers.native.NativeThrowsFixtureCasesTest
```

Neither suite proves actual Native diagnostic emission, framework behavior or
per-ID qualification. JVM compilation of the driver is not a Native fixture
compilation substitute.

## Explicit owner command

Provision and trust all paths first. The fixture never prepares a distribution.
The nested Gradle invocation is offline; missing cached plugins, Native target
dependencies or Apple prerequisites are gaps, never success. Kotlin/Native may
have additional external dependencies: the owner must preprovision and control
that closure; Gradle offline mode is **not a network sandbox** for konanc.

```sh
./gradlew :provider-native:nativeThrowsFixture \
  -PplatformThrowsFixture="$PWD/providers/platform-fixtures/native/throws" \
  -PplatformGradle="$PWD/gradlew" \
  -PplatformJavaHome=/absolute/owned-jdk-25-home \
  -PplatformNativeHome=/absolute/owned-kotlin-native-2.4.21-home \
  -PplatformTarget=iosArm64 \
  -PplatformThrowsCase=override-filter \
  -PplatformResult=/absolute/private-results/native-throws-override.json
```

Every property is required. The target must be one of the two actual iOS targets;
the case must be in the closed authored list. There is no fallback JDK/Native
home, guessed task, unversioned provider, alternative fixture or current-case
extension. The original `nativeFixture` task and its three cases stay unchanged.
Results must be NEW files in a private directory outside this source tree.
The driver prints only a sanitized static summary, never compiler output,
source excerpts, commands, local paths or exception transcripts.

## Honest result contract

The private result preserves the ordinary fixture envelope plus `cohortId`,
`releaseQualified: false`, `targetQualificationEstablished: false`,
`sourceManifestDigest`, `processReceipts`, `assertionFailures`,
`requestedRuleIds`,
`providerProcessExecutionConfirmed`, and
`nativeCompilationTaskExecutionObserved`. Each process receipt distinguishes
JDK probe, Native version probe and selected Gradle provider execution, retaining
the actual `executionConfirmed` and loaded `supervisorIdentity`, source snapshot,
analysis key, bounded raw stdout/stderr and their digests. Raw diagnostics,
unselected/unknown names, task/model gaps and backend failures remain visible.
These private receipts must not be published unsanitized.

* `passed`: only the small explicit named-error or compatible-counterpart
  assertions passed. Actual confirmed process starts, valid matching loaded
  supervisor identities, exact executed common and selected iOS tasks, exact
  context/file/digest/anchor, correct compiler versions and no unexplained gaps
  are required. Provider coverage remains `incomplete`, tested/qualified false.
* `observed_incomplete`: a compiler suppression or severity-demotion observation
  was recorded with confirmed owned process evidence. It is **never a passing
  control or clean certificate**, even if a diagnostic disappears.
* `unavailable` / `failed`: prerequisites, source/model identity, process
  confirmation/cleanup, target task execution or result assertions were missing
  or failed. An exit code, negative signal exit, fork-child exit or task basename
  alone cannot turn an attempt into passing evidence.

All four IDs occur in `FirNonSuppressibleErrorNames` at the pinned version.
The source `@Suppress` case contains an adjacent unsuppressed violation; actual
warnings, retained Native errors, suppression/backend failures and gaps are
recorded without assuming successful compilation. `-nowarn` does not make an
ERROR contract legal. The separate `-Xwarning-level` attempt is observation-only:
the pinned configuration checker prohibits changing ERROR severity. Owner-only
report disposition is an additional downstream control plan, not a compiler
exemption or an implemented Native provider suppression feature.

Version controls are explicit 2.4.21 probes, real JDK 25 selection, and rejection
of wrong compiler/JDK versions, missing helper identities, wrong targets,
cached/skipped/no-source/duplicate/unrelated task observations and mismatched
snapshot/model digests. Supporting a new version requires a new reviewed freeze;
no compiler option or source tag silently changes the four-ID scope.

No linking, generated ObjC/Swift headers, cinterop, framework export execution,
simulator/device run, Apple SDK/LLVM/libffi dependency qualification, full
compilation closure or warm/incremental latency measurement is claimed. Native
artifact fingerprints retain the selected compiler/runtime/stdlib/target KLIB
inputs; they do not attest that broader external closure. The outer owner/CI
must independently freeze the driver/provider/runtime source and actual loaded
build artifacts, including resolved Gradle/plugin/distribution inputs not fully
attested by this fixture's selected artifact fingerprints. Current source bytes never rewrite historical execution
attribution. No Apple execution or per-ID qualification has occurred here.

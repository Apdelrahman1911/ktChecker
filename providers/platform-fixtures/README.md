# Platform provider controls (preview, not release qualification)

The adapters preserve the frozen inventory: **609 artifact-scoped Android Lint
records (603 upstream names)** and **344 common/Native/IR compiler names**, with no
JVM-only substitution for Native. The planning files remain unchanged. These
counts describe mappings, not detector integration tests or qualified coverage.

Every provider currently returns `incomplete`, `unavailable`, `failed`, or
`cancelled`; no row is `qualified` or release-successful. A fixture envelope with
`state: "passed"` means only that its small, explicit assertions passed. It does
not change the underlying `ProviderResult.status: "incomplete"`, and does not
qualify a rule or establish full-project review. Synthetic report/parser tests
never count as per-ID detector fixtures.

## Explicit fixture commands

Root adapters run on **JDK 21**. Nested Kotlin 2.4.21 KMP project/compiler fixtures
use an explicitly selected **JDK 25**. Gradle is pinned by the repository wrapper.
The owner must trust these fixture projects and wrapper before executing them.
Coordinate the shared build slot before running Gradle locally.

```sh
# Real, separately loaded Lint 32.3.1 XML controls: positive, negative, suppression.
./gradlew :provider-lint:lintFixture \
  -PplatformCase=positive \
  -PplatformResult=/absolute/private-results/lint-positive.json

# Actual Android KMP compilation + maintained Lint NewApi control.
./gradlew :provider-lint:lintFixture \
  -PplatformFixture="$PWD/fixtures/kmp" \
  -PplatformGradle="$PWD/gradlew" \
  -PplatformJavaHome=/absolute/jdk-25-home \
  -PplatformCase=new-api \
  -PplatformResult=/absolute/private-results/android-new-api.json

# Actual Apple-host common+iOS Native compilation, never a JVM approximation.
./gradlew :provider-native:nativeFixture \
  -PplatformFixture="$PWD/fixtures/kmp" \
  -PplatformGradle="$PWD/gradlew" \
  -PplatformJavaHome=/absolute/jdk-25-home \
  -PplatformNativeHome=/absolute/kotlin-native-2.4.21-home \
  -PplatformTarget=iosArm64 \
  -PplatformCase=missing-actual \
  -PplatformResult=/absolute/private-results/ios-missing-actual.json
```

KMP Android cases are `positive` and `new-api`. Native targets are `iosArm64` and
`iosSimulatorArm64`; cases are `positive`, `missing-actual`, and `wrong-actual`.
Native's two required tasks are `compileCommonMainKotlinMetadata` and the selected
`compileKotlinIosArm64` / `compileKotlinIosSimulatorArm64`. Cached, skipped,
no-source or aggregate task output is not actual compilation evidence. Linking,
simulator execution, and broader platform CI are separate validation obligations.

If `platformJavaHome` is omitted for a KMP fixture, the driver selects active
`JAVA_HOME`, **not** the broker JVM's `java.home`. It fingerprints project runtime
inputs, runs an actual Java 25 version probe, and pins both nested `JAVA_HOME` and
`org.gradle.java.home`. Native also runs the selected `konanc -version`, and passes
that same distribution through `kotlin.native.home` to Gradle. Missing/incorrect
tooling becomes structured unavailability. Linux Native execution is explicitly
unavailable; it is never made successful by compiling JVM controls.

The common envelope is `schemaVersion`, `state`, `case`, `target`, `snapshotId`,
`providerResult`, `artifactIdentities`, `assertions`, and `limitations`. A
prerequisite failure may have an empty `snapshotId` only if capture never occurred.
Consumers must validate the envelope, selected target, version, actual artifacts,
source identities, executed provenance, and certificates—not the process exit
status alone. Results include local paths/source messages and should remain
private unless deliberately sanitized for sharing.

## Trusted host integration

Production adapters take nonserializable `ExecutionAuthority` plus an
owner-imported plan. Serialized `approved: true`, source configuration, comments,
or model-supplied command arguments cannot grant authority.

* `LintRunPlan` binds Lint version, actual artifact-scoped registry identities,
  selected IDs, report path, Android source-set/variant mapping, explicit
  inclusions/exclusions, and disabled IDs. AGP **9.3.1 embeds Lint 32.3.1**; its XML
  client header is **`lint 9.3.1`**, not the Lint artifact version. Lint 32.5.0 uses
  client 9.5.0. Navigation's duplicate upstream IDs require registry-origin
  disambiguation; they must not be collapsed into a single canonical name.
* `NativeRunPlan` binds actual iOS target, Native compiler artifacts, compiler
  version, execution mode, required tasks, and common/iOS source-set mapping.
  Native CLI mode requires actual target and expect/actual diagnostic-name
  arguments. Only named catalogued diagnostics normalize to findings; unknown,
  unnamed, out-of-scope, or invalid locations remain explicit gaps.
* Both providers require resolved model, dependency, toolchain and configuration
  identities. When supplied, `AnalysisContext.compilationFiles` restricts captured
  members of the selected compilation; capture alone does not make another target
  source applicable.

The authenticated host obtains an integrated read-only materialization, constructs
the complete `AnalysisInput`, and creates an `ExecutionPermit` bound to its
`analysisKey()`, snapshot ID, exact root and pinned executable/profile. The permit
callback verifies that **registered materialization** before and after execution.
Do not construct it by hashing a live directory and pretending that was a coherent
capture. The fixture driver is an explicit producer of submitted bytes frozen
through the shared `SnapshotStore`; it makes no live-current/ABA guarantee.

The default preview workbench does not yet import a trusted Android or Apple
project model. Merely submitting those targets therefore retains unavailable
coverage; installing JARs or setting JSON flags does not enable full review.

## Isolation and evidence limits

Sources stay separate from fresh unique outputs; fixed Gradle init scripts
relocate build, project-cache and Kotlin persistent outputs. Provider runtime
dependencies are loaded in a separate process/classpath from the broker. Source
and toolchain bytes are checked pre/post. Anchored no-follow report reads reject
traversal, symlinks, oversized output, and replaced output roots.

Linux and macOS use owned process groups, bounded diagnostic output and bounded
group-quiescence checks. Missing containment/report tooling fails closed. The
deadline includes preparation/post-verification checks, but trusted owner callbacks
can delay return and cannot be forcibly time-bounded inside this JVM. A process
that creates a new session can escape cooperative group containment. Same-user
filesystem permissions, process groups and caches are **not a hostile-code security
sandbox**. Windows execution containment and actual Apple-host fixture execution
need their own evidence; no portability or precision qualification is inferred.

Report locations without provider line/column information are file anchors with
empty excerpts; the contract's default `1:0` is not asserted as a real binary/text
range. Explicit ranges require valid captured UTF-8 source and bounds.

`freeze_runtime_maps.py` refreshes only exact mapping resources and current
implementation source hashes. It never upgrades executed evidence, `tested`,
`qualified`, or the frozen rule inventory. Real controls, the full six case types,
precise version/source-set contexts, independent precision/corpus review, and a
fresh integrated review remain separate release gates.

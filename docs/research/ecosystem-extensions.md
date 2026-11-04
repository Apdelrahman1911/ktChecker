# Practical ecosystem extensions

**Status: proposed research and rule plan. Access date: 2026-11-04 UTC.**

This document extends the Kotlin, coroutines, Compose, Android, persistence,
networking, and testing inventories. The accompanying
[`data/rules-ecosystem.json`](../../data/rules-ecosystem.json) contains **25 atomic
planned rules**: 22 conditionally eligible after validation and 3 advisory. None
is implemented, runtime-enabled, tested against Kotlin projects, or field-validated
by this research. Fixture descriptions are proposals; `cost` is an engineering
estimate, not a benchmark. The libraries below are a practical starting point for
versioned contract adapters, not a cap on ecosystem coverage or the first release.

Activation requires resolved Maven coordinates, callable/annotation identities,
library versions, actual source-set compilations and target metadata, plus the
ownership, equality, control-flow, configuration or process facts required by the
rule. A dependency, matching import, class suffix or repeated destination name
alone cannot establish a defect. Missing facts yield an explicit coverage gap.
An adopted library does not impose one preferred application architecture.

## 1. Observed release and licensing snapshot

These are retrieved upstream facts, **not tested compatibility promises**. Release
API snapshots exclude prereleases; changelog and AndroidX release-page snapshots
are dated observations. Licenses describe inspected upstream source, not a blanket
legal judgment about every transitive dependency or distribution.

| Library/artifact | Observed stable reference and date | Primary release evidence | Inspected source license |
| --- | --- | --- | --- |
| Okio | 3.18.2; 2026-09-04 | [Official changelog](https://raw.githubusercontent.com/square/okio/master/CHANGELOG.md) | [Apache-2.0](https://raw.githubusercontent.com/square/okio/master/LICENSE.txt) |
| DataStore | 1.3.0; 2026-10-21 | [AndroidX releases](https://developer.android.com/jetpack/androidx/releases/datastore) | Apache-2.0 source headers in the released archives below |
| Paging | 3.5.3; 2026-10-12 | [AndroidX releases](https://developer.android.com/jetpack/androidx/releases/paging) | Apache-2.0 source headers in the released archive below |
| Arrow Core / FX Coroutines | 2.3.0.1; 2026-10-27 | [Official release API](https://api.github.com/repos/arrow-kt/arrow/releases/latest) | [Apache-2.0 plus included third-party notices](https://raw.githubusercontent.com/arrow-kt/arrow/main/LICENSE) |
| Decompose | 3.5.0; 2026-03-15 | [Official release API](https://api.github.com/repos/arkivanov/Decompose/releases/latest) | [Apache-2.0](https://raw.githubusercontent.com/arkivanov/Decompose/master/LICENSE) |
| Essenty | 2.7.1; 2026-10-30 | [Official release API](https://api.github.com/repos/arkivanov/Essenty/releases/latest) | [Apache-2.0](https://raw.githubusercontent.com/arkivanov/Essenty/master/LICENSE) |
| OkHttp | 5.5.1; 2026-10-22 | [Official changelog](https://raw.githubusercontent.com/square/okhttp/master/CHANGELOG.md) | [Apache-2.0](https://raw.githubusercontent.com/square/okhttp/master/LICENSE.txt) |
| Retrofit | 3.0.0; 2025-05-15 | [Official release API](https://api.github.com/repos/lysine-dev/retrofit/releases/latest) | [Apache-2.0](https://raw.githubusercontent.com/lysine-dev/retrofit/trunk/LICENSE.txt) |
| Voyager | 1.0.1; 2024-12-19 | [Official release API](https://api.github.com/repos/adrielcafe/voyager/releases/latest) | [MIT](https://raw.githubusercontent.com/adrielcafe/voyager/main/LICENSE.md) |
| Circuit | 0.39.0; 2026-09-15 | [Official release API](https://api.github.com/repos/slackhq/circuit/releases/latest) | [Apache-2.0](https://raw.githubusercontent.com/slackhq/circuit/main/LICENSE.txt) |
| Kermit | 2.2.0; 2026-09-08 | [Official release API](https://api.github.com/repos/touchlab/Kermit/releases/latest) | [Apache-2.0](https://raw.githubusercontent.com/touchlab/Kermit/main/LICENSE.txt) |
| Napier | 2.7.1; 2024-01-04 | [Official release API](https://api.github.com/repos/AAkira/Napier/releases/latest) | [Apache-2.0](https://raw.githubusercontent.com/AAkira/Napier/master/LICENSE) |
| SLF4J | 2.0.20; 2026-09-22 | [Official release API](https://api.github.com/repos/qos-ch/slf4j/releases/latest) | [MIT](https://raw.githubusercontent.com/qos-ch/slf4j/master/LICENSE.txt) |
| Ktorfit | 2.7.5; 2026-06-07 | [Official release API](https://api.github.com/repos/Foso/Ktorfit/releases/latest) | [Apache-2.0](https://raw.githubusercontent.com/Foso/Ktorfit/master/LICENSE) |
| WorkManager | 2.12.0; 2026-09-23 | [AndroidX releases](https://developer.android.com/jetpack/androidx/releases/work) | Apache-2.0 AndroidX detector/source headers |

SLF4J [Maven metadata](https://repo.maven.apache.org/maven2/org/slf4j/slf4j-api/maven-metadata.xml)
labels **2.1.0-alpha1** as latest/release; it must not be substituted for the
observed stable 2.0.20 adapter. Retrofit's official
[repository API](https://api.github.com/repos/square/retrofit)
now redirects Square ownership to **`lysine-dev/retrofit`**; tagged 3.0.0
sources retain `retrofit2` API identities. The old Square documentation URL returned
404 and is not contract evidence. A release's age, including Napier's and
Voyager's older stable snapshots, does not by itself prove abandonment,
vulnerability, or unsupported code. Maintainer cadence and compatibility must be
reviewed independently before accepting an adapter maintenance commitment.

## 2. Published targets are artifact-specific

Published `.module` metadata was fetched for the exact versions, not inferred from
library marketing or a `commonMain` directory:

- [Okio 3.18.2](https://repo.maven.apache.org/maven2/com/squareup/okio/okio/3.18.2/okio-3.18.2.module)
  publishes JVM, JS, Wasm-JS/WASI and several Apple/Linux/MinGW Native variants, plus
  common metadata. Android can consume its JVM artifact subject to actual Android
  compatibility. Target publication does **not** prove that a particular browser
  environment has a modeled file-backed `FileSystem` implementation.
- [OkHttp 5.5.1](https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp/5.5.1/okhttp-5.5.1.module)
  publishes **Android/JVM implementations**, plus common metadata. Common metadata
  is not an iOS/Native transport. OkHttp, Retrofit and SLF4J rows use `desktop` only
  for **JVM desktop**, not arbitrary Native desktop targets.
- [DataStore core 1.3.0](https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-core/1.3.0/datastore-core-1.3.0.module)
  and [Preferences core 1.3.0](https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-preferences-core/1.3.0/datastore-preferences-core-1.3.0.module)
  publish Android/JVM, selected Apple/Linux Native, JS and Wasm-JS variants. The
  [official KMP setup guide](https://developer.android.com/kotlin/multiplatform/datastore)
  documents KMP since **1.1.0** and currently explicitly says **only Preferences
  DataStore is supported in KMP projects**. Do not promise every typed serializer,
  custom `Storage` or coordinator on every target. New browser storage in 1.3.0 is
  outside the file-identity and Android-process rules.
- [Paging common 3.5.3](https://dl.google.com/dl/android/maven2/androidx/paging/paging-common/3.5.3/paging-common-3.5.3.module)
  publishes Android/JVM, selected Apple/Linux/MinGW Native, JS and Wasm-JS variants.
  The 3.3.0 release notes established common/JVM/iOS support; those historical notes
  are **not** an exhaustive current target list. Android-only `paging-runtime`,
  Guava and Rx artifacts do not inherit `paging-common` coverage.
- [Arrow Core](https://repo.maven.apache.org/maven2/io/arrow-kt/arrow-core/2.3.0.1/arrow-core-2.3.0.1.module)
  and [FX Coroutines](https://repo.maven.apache.org/maven2/io/arrow-kt/arrow-fx-coroutines/2.3.0.1/arrow-fx-coroutines-2.3.0.1.module)
  2.3.0.1 publish common, Android/JVM, JS, Wasm-JS/WASI and selected Native variants,
  including Native Android. Native Android is not the Android/JVM compilation.
- [Decompose 3.5.0](https://repo.maven.apache.org/maven2/com/arkivanov/decompose/decompose/3.5.0/decompose-3.5.0.module)
  and [Essenty StateKeeper 2.7.1](https://repo.maven.apache.org/maven2/com/arkivanov/essenty/state-keeper/2.7.1/state-keeper-2.7.1.module)
  publish common, Android/JVM, JS/Wasm-JS and selected Apple Native variants;
  StateKeeper also publishes Linux Native variants. Do not extrapolate a target
  from one Essenty or Decompose module to all extension modules.
- [Voyager navigator](https://repo.maven.apache.org/maven2/cafe/adriel/voyager/voyager-navigator/1.0.1/voyager-navigator-1.0.1.module)
  and [transitions](https://repo.maven.apache.org/maven2/cafe/adriel/voyager/voyager-transitions/1.0.1/voyager-transitions-1.0.1.module)
  1.0.1 publish Android, JVM desktop, JS and selected iOS/macOS Native variants.
  An available library target is not proof of a working Compose runtime/version
  tuple or transition timing contract.
- [Circuit runtime 0.39.0](https://repo.maven.apache.org/maven2/com/slack/circuit/circuit-runtime/0.39.0/circuit-runtime-0.39.0.module)
  publishes Android/JVM, JS/Wasm-JS and selected Apple/Linux/MinGW Native variants
  plus common metadata. Android-only integrations must be scoped separately.

The catalog's coarse platform enum is only `common/android/ios/desktop/jvm`.
It cannot faithfully enumerate JS, Wasm, watchOS, tvOS or every Native architecture.
`common` means a candidate source-set contract, not support for all consumers.
Even `ios` cannot imply both device and every simulator architecture: current
AndroidX metadata does not necessarily retain every older iOS target. Exact
compilation/target tuples and version boundaries must be recorded by the future
applicability manifest; absent or unsupported variants are visible gaps.

## 3. Selected atomic rule families

All rows currently use `implementation: custom` and `provider: ktChecker` for the
proposed contract adapter, not an invented upstream plugin. The machine-readable
inventory supplies exact IDs, sources, exclusions, severity/kind/certainty, depth,
and six proposed validation dimensions per row.

| Family | Rows | Precise candidate contracts and required facts |
| --- | ---: | --- |
| Okio | 1 | Owned file source/sink/handle lifetime, including wrappers and exceptional exits. Require a certified bounded ownership obligation, not absent lexical `close`; exclude `Buffer`, borrowed parameters and modeled transfers. |
| DataStore | 4 | Same file with overlapping active instances in one process; mutation of persisted snapshot identity; nested `updateData` on the same instance/context; shared Android-process file using single-process coordination. Require actual file/connection identity, alias/context proof or merged-manifest reachability, respectively. |
| Paging | 3 | Jumping-enabled pages need both item counts; directional Append/Prepend key reuse requires the effective support flag; regenerated `Pager` factories must return a fresh source. Require returned page/path facts, actual key equality/flags and proven factory identity/regeneration. |
| Arrow | 3 | Eager `Raise` used after its owning scope; swallowed typed short-circuiting; lost manual `Resource.allocate` release obligation. Require token lifetime, handler propagation or a known installed finalizer/owner. Swallowed Raise remains intent-dependent advisory. |
| Decompose | 3 | Equal child-stack registration keys in one host; equal configurations when duplicate mode is proven disabled; root context recreation inside composition. The retention recommendation is advisory, not a universal lifecycle failure claim. |
| Essenty | 1 | Duplicate active `StateKeeper.register` supplier key on the actual default/equivalent dispatcher. `consume` restores saved data and does not unregister a supplier; custom replacement semantics are excluded. |
| OkHttp | 4 | Owned response-body closure, second whole-body consumption, second execution/enqueue of one `Call`, and network-interceptor `proceed` cardinality. Resolve the actual Android/JVM implementation, ownership/aliases/order and network registration category. |
| Retrofit | 4 | Parser-rejected request-body/encoding declarations; parser-rejected URL parameter binding/order; suspend service directly returning `retrofit2.Call`; base URL final parsed path segment lacking a slash. Resolve actual service annotations/types and the exact parser/`HttpUrl` versions. |
| Voyager | 1 | Two distinct non-coalesced transition screens simultaneously register the same computed key on the same holder. Require certified composition overlap, holder identity, stable keys and exact transition/runtime contracts. Repeated classes or stack entries alone are insufficient. |
| SLF4J | 1 | More unescaped formatting anchors than proven arguments in a parameterized traditional logging call, with exact overload/Throwable normalization. Advisory because literal/structured logging intent can be valid. |

### Important contract boundaries

- The [DataStore guide](https://developer.android.com/topic/libraries/architecture/datastore)
  explicitly requires immutable persisted values and prohibits concurrent same-file
  instances and mixed single/multiprocess storage. `Preferences.edit` intentionally
  supplies a mutable transaction copy and is a valid alternative. Released 1.3.0
  `DataStoreImpl` rejects active same-instance nested updates while permitting
  different-instance nesting; older releases' deadlock/rejection behavior must not
  be conflated.
- Released Paging sources contain the directional key-reuse check and factory
  freshness check. A Refresh key is not an Append/Prepend violation, zero item
  counts are valid, and unknown helper freshness is not a singleton diagnosis.
- [Arrow's tagged fold](https://raw.githubusercontent.com/arrow-kt/arrow/2.3.0.1/arrow-libs/core/arrow-core/src/commonMain/kotlin/arrow/core/raise/Fold.kt)
  ties eager Raise failure to a specific token lifetime. Lazy `Effect` construction
  and a never-invoked escaped closure do not prove late use. Swallowing Raise proves
  short-circuit consumption, not that propagation rather than deliberate recovery
  was intended; `recover`/`fold` and cancellation-transparent catches are legitimate.
  [Manual resource allocation](https://raw.githubusercontent.com/arrow-kt/arrow/2.3.0.1/arrow-libs/fx/arrow-fx-coroutines/src/commonMain/kotlin/arrow/fx/coroutines/Resource.kt)
  transfers a release obligation only where a modeled finalizer exists.
- Decompose's [child-stack contract](https://raw.githubusercontent.com/arkivanov/Decompose/3.5.0/decompose/src/commonMain/kotlin/com/arkivanov/decompose/router/stack/ChildStackFactory.kt)
  depends on effective equality and duplicate settings. The
  [settings API](https://raw.githubusercontent.com/arkivanov/Decompose/3.5.0/decompose/src/commonMain/kotlin/com/arkivanov/decompose/DecomposeSettings.kt)
  changed from earlier experiment flags starting at 3.5.0-alpha01. Unknown external
  settings writes are a gap, not evidence of default false. Child Items has different
  duplicate support. Essenty collisions underneath one Decompose collision supply
  related evidence, not a second root-cause finding.
- OkHttp [response-body contracts](https://raw.githubusercontent.com/square/okhttp/parent-5.5.1/okhttp/src/commonJvmAndroid/kotlin/okhttp3/ResponseBody.kt)
  include consuming methods that close the source. Partial streaming reads and
  cached `String`/`ByteArray` reuse are not repeated whole-body consumption.
  `clone()` creates a different executable `Call`. The
  [network-only chain guard](https://raw.githubusercontent.com/square/okhttp/parent-5.5.1/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/http/RealInterceptorChain.kt)
  does not prohibit application interceptors from short-circuiting or retrying;
  ordinary exception exits do not need to return a response. A second network
  `proceed` attempt throws immediately, so it must not be modeled as a successful
  second call on a normal-return path; zero-call normal returns are a separate
  enclosing-check violation.
- Retrofit's [released request parser](https://raw.githubusercontent.com/lysine-dev/retrofit/3.0.0/retrofit/src/main/java/retrofit2/RequestFactory.java)
  permits an explicit `@HTTP(hasBody=true)` independent of verb spelling. It must
  not be replaced by a blanket GET-body ban or an invented requirement that every
  URL token has a matching `@Path`. Base URL checking uses the final **parsed
  `HttpUrl` path segment**, not `endsWith("/")`: `https://example.test/api/?tenant=one`
  is valid.
- Voyager 1.0.1 [ScreenTransition](https://raw.githubusercontent.com/adrielcafe/voyager/1.0.1/voyager-transitions/src/commonMain/kotlin/cafe/adriel/voyager/transitions/ScreenTransition.kt)
  uses `AnimatedContent` and
  [Navigator](https://raw.githubusercontent.com/adrielcafe/voyager/1.0.1/voyager-navigator/src/commonMain/kotlin/cafe/adriel/voyager/navigator/Navigator.kt)
  derives `${screen.key}:transition`. Released Compose holder sources below reject
  duplicate *active providers*, not sequential reuse. Equal animated states can
  coalesce to one provider; nested Navigators can share a holder. The cited releases
  are reference evidence, **not a certified supported compatibility tuple**.

## 4. Provider reuse and deduplication

### WorkManager: reuse the actual embedded Android Lint provider

The released `androidx.work:work-runtime:2.12.0` AAR contains `lint.jar`, with service
registration for **`androidx.work.lint.WorkManagerIssueRegistry`**. Artifact inspection
confirmed these exact exported issue IDs:

`BadConfigurationProvider`, `IdleBatteryChargingConstraints`,
`InvalidPeriodicWorkRequestInterval`, `BadPeriodicWorkRequestEnqueue`,
`RemoveWorkManagerInitializer`, `UseRxSetProgress2`, `SpecifyForegroundServiceType`,
`SpecifyJobSchedulerIdRange`, `WorkerHasAPublicModifier`.

The platform inventory owns the Android Lint mappings; this ecosystem catalog
intentionally adds **no duplicate WorkManager custom rules**. BuiltinLint's registry
must not be assumed to contain a library-embedded registry. The future wrapper must
load the resolved AAR's provider, preserve exact aliases/defaults/configuration,
validate each exported ID and its Android variants, and report absent providers or
unsupported Lint API versions as coverage gaps. Inspecting bytecode is not executing
Lint, proving an adapter works, or passing its suppression/delivery fixtures.

### Ktorfit: preserve compile-time ownership

[Official configuration documentation](https://foso.github.io/Ktorfit/configuration/)
explicitly says Ktorfit performs compile-time error checking by default, configurable
with `ErrorCheckingMode.NONE`, `ERROR` and `WARNING`. Preserve generator/KSP/compiler
ownership and actual build diagnostic evidence for these checks rather than clone
Retrofit predicates under lookalike annotation names. No atomic Ktorfit compiler-ID
inventory was established here; that export/mapping is remaining integration work.
Ktor runtime adapters can be reused only after resolving the injected client, engine,
plugins, actual call path and versions. Ktorfit adoption alone is not runtime proof.

### Shared engines, not one warning per interface

One resource-lifetime engine should serve existing Kotlin/JVM resource coverage,
Okio, OkHttp, Arrow finalizers and applicable Ktor ownership contracts. Follow shared
body/source/stream/wrapper identities, model both `FileHandle` and derived-stream
lifetimes, honor transfers and all feasible exits, and produce one primary finding
per obligation/root cause. Unclosed ownership and a separately proved second read
are distinct claims, but one leak must not become an interface-count multiplier.

Similarly, Arrow swallowed cancellation and generic coroutine cancellation coverage,
Decompose/Essenty registration collisions, and Voyager/generic Compose holder-key
collisions need explicit precedence and evidence merging. Preserve provider raw
identity and context while normalizing aliases; titles or wildcard providers cannot
claim every exported rule. Slow graph adapters and Android process/build adapters
need bounded analysis budgets with visible incompleteness, not syntax fallbacks.

## 5. Justified exclusions and remaining coverage

- **No blanket lazy-logging rewrite.** Tagged
  [Kermit 2.2.0 BaseLogger](https://raw.githubusercontent.com/touchlab/Kermit/2.2.0/kermit-core/src/commonMain/kotlin/co/touchlab/kermit/BaseLogger.kt)
  evaluates message blocks after its `minSeverity` gate, but writer-specific
  `isLoggable` is checked later. Tagged
  [Napier 2.7.1](https://raw.githubusercontent.com/AAkira/Napier/2.7.1/napier/src/commonMain/kotlin/io/github/aakira/napier/Napier.kt)
  calls `message()` before forwarding to `log`; a lambda is **not inherently lazy**
  there. Expensive logging needs actual evaluation/cost/configuration facts, not a
  universal performance claim or an unsafe lambda conversion.
- **No logging API conflation.** SLF4J
  [MessageFormatter](https://raw.githubusercontent.com/qos-ch/slf4j/v_2.0.20/slf4j-api/src/main/java/org/slf4j/helpers/MessageFormatter.java)
  and [AbstractLogger](https://raw.githubusercontent.com/qos-ch/slf4j/v_2.0.20/slf4j-api/src/main/java/org/slf4j/helpers/AbstractLogger.java)
  have overload-sensitive trailing-Throwable and escape behavior. Extra arguments
  may be useful to structured backends; plain-message literal braces, dynamic
  spreads, custom backends and fluent builders cannot inherit the traditional-call
  counting predicate. Kermit/Napier do not inherit SLF4J placeholder semantics.
  Secret/PII logging needs a declared data-flow/policy source, not keyword guesses.
- **No Circuit architecture mandate.** Circuit's
  [presenter documentation](https://raw.githubusercontent.com/slackhq/circuit/main/docs/docs/presenter.md)
  and [state/event guide](https://raw.githubusercontent.com/slackhq/circuit/main/docs/getting-started/states-and-events.md)
  allow alternative state and event designs and discuss Compose lambda memoization.
  Do not demand manual `remember` for every event lambda or universally require
  Parcelable, serialization, a specific event sealed class or MVI shape. Actual
  Composable `present()` execution can use the generic Compose/effect engines;
  adding a duplicate library rule ID does not add coverage.
- **No universal navigation/lifecycle ban.** Decompose root retention guidance stays
  advisory when preview/transient ownership is legitimate; `remember` does not prove
  UI-thread safety. Voyager's current
  [transition guide](https://voyager.adriel.cafe/transitions-api/) also describes a
  lifecycle/disposal bug and fixes introduced in 1.1 betas. Those newer APIs must
  not be recommended to stable 1.0.1 code without version checks. A retained
  `override val key = uniqueScreenKey` differs from generating a new random key on
  every getter read. Repeated destinations, independent holders and sequential reuse
  are not globally prohibited.
- **No mandatory paging/cache pattern.** Factory freshness, item-count and directional
  key contracts are selected; missing `cachedIn`, one particular key-increment
  convention or Android UI adapter is not by itself a defect. Actual collection
  lifetimes, regenerated pages and resolved runtime artifacts are needed for
  additional contracts.
- **No library-name ownership or platform guessing.** Synthetic in-memory response
  bodies, custom file systems/calls/dispatchers, unknown DI scope/aliases, browser
  storage, unsupported serializers and incomplete process reachability stay gaps.
  Same basenames do not prove one file; a remote manifest service does not prove it
  reaches the DataStore. Native UI code without Compose is a valid application.
- **No release-age or compiler-error duplication.** An older dependency is not an
  automatic defect. Kotlin/compiler errors, generator errors and existing Lint IDs
  retain their providers; planned custom library contracts do not manufacture new
  claims from spellings. Selected project policies may add scoped advisories or
  opt-in enforcement, but do not become universal Kotlin correctness rules.

These exclusions narrow particular diagnostic claims, not the broad first-release
product contract. Unimplemented or unqualified capabilities remain visible in the
launch manifest and must be remediated, replaced equivalently, explicitly rescoped
with owner approval, or delay the broad release as specified in
[validation and CI](../validation-ci.md). This inventory cannot satisfy a required
capability merely by listing a planned ID or disabling every candidate.

## 6. Exact-release evidence and reproducibility

Primary-source downloads, extracted source entries, release API/metadata snapshots
and the fetch journal are cached in `/tmp/ktc-ecosystem-research/` in this workspace.
The journal records URLs, access dates, SHA-256 values and failures; failed URLs are
not used as contract evidence. This cache is not a shipped provider or an executed
validation report. The checked-in catalog links tagged/released sources; mutable
release/docs/license snapshots remain dated observations.

Selected inspected released artifacts:

| Artifact and exact URL | SHA-256 | Relevant inspected entries |
| --- | --- | --- |
| [DataStore core 1.3.0 sources](https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-core/1.3.0/datastore-core-1.3.0-sources.jar) | `0c2dd01f49a401b8f290c898b765118cda5ac4bf2c9f71bcc9545058ac8eed38` | `commonMain/androidx/datastore/core/`: `DataStore.kt`, `DataStoreFactory.kt`, `DataStoreImpl.kt` |
| [DataStore Okio 1.3.0 sources](https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-core-okio/1.3.0/datastore-core-okio-1.3.0-sources.jar) | `23950fde38ed573c01a4a564117501bd83896fe4d1e7aa376890582e43eb3eeb` | `commonMain/androidx/datastore/core/okio/OkioStorage.kt` |
| [Preferences core 1.3.0 sources](https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-preferences-core/1.3.0/datastore-preferences-core-1.3.0-sources.jar) | `9cab0542d0423f0a255a82af4a159bbc05f76dc856dcaa0630c10d8a6f8f2dbf` | `commonMain/androidx/datastore/preferences/core/`: `Preferences.kt`, `PreferenceDataStoreFactory.kt` |
| [Paging common 3.5.3 sources](https://dl.google.com/dl/android/maven2/androidx/paging/paging-common/3.5.3/paging-common-3.5.3-sources.jar) | `986c4f8b6de352061fbdb664a546eb6e8561df9eb0c8f7cf5bf5e2521dec7a94` | `commonMain/androidx/paging/`: `PagingSource.kt`, `PageFetcherSnapshot.kt`, `PageFetcher.kt` |
| [AndroidX runtime-saveable 1.5.0 sources](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-saveable/1.5.0/runtime-saveable-1.5.0-sources.jar) and [JetBrains runtime-saveable-desktop 1.5.1 sources](https://repo.maven.apache.org/maven2/org/jetbrains/compose/runtime/runtime-saveable-desktop/1.5.1/runtime-saveable-desktop-1.5.1-sources.jar) | Both `d8472468b4b710ce2be678dbf55a771abdd8aeec207ed630b9e54e0125872466` | `commonMain/androidx/compose/runtime/saveable/SaveableStateHolder.kt`; duplicate-active-provider guard |
| [WorkManager runtime 2.12.0 AAR](https://dl.google.com/dl/android/maven2/androidx/work/work-runtime/2.12.0/work-runtime-2.12.0.aar) | `a967c776d2c2ff9a7a541679d2fb51cefdc78d4010fcf68f3fdd4c57c29e89dc` | Embedded `lint.jar` and its registry service/exported issue IDs |
| WorkManager 2.12.0 embedded `lint.jar` | `e7af13eca1146cbf479ff900fd1b9155885b5a69ee859143b9b0bd2d5cd43736` | `androidx.work.lint.WorkManagerIssueRegistry` |

Tagged sources in the catalog additionally pin Okio `parent-3.18.2`, OkHttp
`parent-5.5.1`, Arrow `2.3.0.1`, Decompose `3.5.0`, Essenty `2.7.1`, Voyager `1.0.1`,
Retrofit `3.0.0` and SLF4J `v_2.0.20`. A production adapter should also retain resolved
artifact/commit hashes and review the exact dependency/toolchain tuple, since a tag
or a source citation alone is not implementation conformance.

## 7. Qualification and remaining implementation work

High certainty in a row refers to the specificity of its **conditional upstream
contract**, not measured precision. `enabled_when_validated` requires the independent
proof-or-empirical precision route in [validation-ci.md](../validation-ci.md),
released-version/source-set evidence and per-ID integration fixtures. Fixture pairs
alone are insufficient. General ownership warnings cannot claim a proof route
without certified bounded ownership and complete normal/exceptional control-flow
assumptions. Raise swallowing requires an explicit propagation contract for a proof
route; otherwise intentional recovery must enter the independent empirical review.

Each row proposes positive, valid-negative, boundary, platform/version, suppression
and regression cases. Future runs must verify source mapping, exact IDs/aliases,
configuration, complete-versus-incremental behavior, independent valid alternatives,
provider deduplication, recommendations and absence of diagnostics with misleading
names or unresolved facts. Structural JSON/schema checks only validate planning
metadata. They do not run library detectors, execute Native/Android tooling,
establish natural-project precision/recall, or certify any of the above tuples.

Remaining work is explicit: implement adapters and coverage plumbing, establish
reviewed bounded contracts or held-out field evidence, execute exact target/version
and behavioral-oracle fixtures, qualify suppression/delivery/incremental behavior,
and measure performance. No missing fact is to be silently converted into a match.

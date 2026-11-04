# Platform, UI, Native and build research

**Access date: 2026-11-04 UTC. Status: final research inventory, not implemented checker coverage.**

[rules-platform.json](../../data/rules-platform.json) contains **716 atomic planned records**, all `runtime_enabled=false`, with **4,296 proposed validation cases**. No ktChecker detector, consumer-project integration, precision corpus, runtime probe or platform CI was executed for this research. Registry loading/export and source inspection establish upstream identities and evidence only; they do not demonstrate detection correctness or working KMP interoperability.

The inventory combines **609 published Android Lint/AndroidX occurrences** across the baseline/current union, **43 individually registered maintained Compose Rules children** with **35 Ktlint aliases**, and **64 custom/contract candidates**. The 609 provider occurrences have 603 distinct upstream IDs; artifact-scoped duplicates are intentional provenance records, not independent findings. No family wildcard enables unknown or unreviewed checks.

## 1. Activation and evidence decisions

Severity, kind, certainty and activation are independent. Imported severity faithfully maps `warning` to warning, `error`/`fatal` to error and `informational` to info; neither severity, upstream category nor upstream default establishes a proof. `enabled_when_validated` means future eligibility, **not current enablement**.

| Inventory | Planned | Eligible after validation | Opt-in | Advisory | Excluded |
|---|---:|---:|---:|---:|---:|
| Published provider union | 609 | 389 | 129 | 70 | 21 |
| Compose Rules 0.6.8 | 43 | 1 | 25 | 15 | 2 |
| Custom/contract candidates | 64 | 16 | 15 | 30 | 3 |
| **Total** | 716 | 406 | 169 | 115 | 26 |

The **406 eligible candidates** comprise **35 high-evidence correctness contracts** and **371 medium-evidence probable defects**. The 35 comprise 20 imported predicates, one maintained-pack predicate and 14 custom predicates. These labels are reviewed planning judgments, not calibrated probabilities or completed qualification. The medium lane needs an independently measured accepted/rejected corpus and its own published limitations; failure of that gate means advisory routing or exclusion, not promotion through severity.

Use fully qualified callable/annotation identity, overload, target, dependency/compiler version and effective Gradle/source-set model. Establish the actual receiver, ownership, reachable path, API contract and configuration facts each predicate needs. A name, import, declaration, missing dependency coordinate or matching package prefix is insufficient. Unknown symbols, incomplete generation/classpaths and unsupported tuples produce abstention with a coverage explanation. Generated/test/example sources retain distinct provenance and are not actionable production findings unless explicitly selected.

### Corrections important to the proof boundary

- `BuildListAdds` reports legitimate empty builders; keep advisory. `MutableCollectionMutableState` does not prove an actual mutation or missing independent invalidation; keep advisory.
- `ArcAnimationSpecTypeIssue` is **advisory**, not a dimensionality prohibition. The lint source whitelists `Offset`, `IntOffset` and `DpOffset`; published runtime `AnimationSpec.kt` recommends positional use and explicitly supports one-dimensional linear interpolation. `Size`/custom two-dimensional converters can be valid. The original overstrong explanation is retained separately as `upstream_rationale`, not reasserted as the plan's rationale.
- `TextFieldBufferInternalAppend` is **opt-in foundation-maintainer policy**. The detector checks a caller-package prefix for internal hardware-source tracking; normal application `TextFieldBuffer.append` is legal.
- `BadPeriodicWorkRequestEnqueue` is **opt-in scheduling uniqueness policy**. `WorkManager.enqueue(PeriodicWorkRequest)` is supported; unique periodic scheduling is not a universal framework precondition. Short intervals can be clamped/logged; public Worker visibility depends on the actual WorkerFactory.
- `ManifestResource` concerns forbidden configuration-varying resources, **not resource types**. Preserve delayed-resource-ID attribute, metadata and density/version exemptions; a narrowed high lane must establish actual forbidden variation. `ReferenceType` concerns resource aliases with drawable/color/mipmap and macro exceptions, not arbitrary custom attribute types.
- Modifier/API naming and `ShiftFlags` conventions are style; unique-constant/literal-interpolation preferences are policy. Permission typo heuristics are empirical. `Cipher.getInstance("RSA/ECB/...")` is a documented legal control: the published ECB detector excludes RSA.
- Custom `Immutable` proof requires actual observable value-changing mutation after construction; an unused public setter is not a proved occurrence. `memScoped` proof requires reachable post-scope access to the same freed allocation; preserving/comparing an address alone is insufficient. StableRef proof likewise requires actual access after disposal, not raw pointer transport.
- An unbridged Objective-C exception terminates the call, but deliberate fail-fast invariants are valid. The high custom boundary requires an **explicit recoverability contract**, actual framework export and a known escaping unmatched exception. Suspend cancellation and inherited `@Throws`/subtype rules remain accepted controls.

Imported high predicates are narrower qualification predicates, not a promise that the unmodified detector establishes every fact. Where filtering/extra contract evidence is needed, preserve original output and its provenance while qualifying the additional classifier. Do not invent upstream exemptions or count a provider alert on a legitimate control as proof.

## 2. Baseline and published registry manifests

The proposed consumer qualification tuple is **KGP 2.4.21 / Gradle 9.6.1 / AGP 9.3.1 / JDK 25 / Xcode 26.4.1**. This is a proposed test profile, not a working integration established here. [AGP 9.3.1's published dependency model](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.3.1/gradle-9.3.1.pom) belongs to **Lint 32.3.1**, not the newer 32.5.0 catalog.

A temporary Java/Gradle exporter instantiated all **20 named registries** against separate Lint **32.3.1** and **32.5.0** classpaths without a registry-load error. It read `IssueRegistry.issues`, API/minApi, descriptor, category, severity, default, detector and scopes. Baseline exports contain **602 occurrences** (512 builtin + 90 AndroidX); current exports contain **607** (517 + 90). Their union is **609** (519 builtin + 90). A successful constructor/introspection is **not** an AGP task, Kotlin/UAST resolution or Android/common/Native consumer test.

The API/minApi column below is the **Lint registry API**, not Android minSdk. Source/AAR bytes and actual provider-binary checksums are recorded per JSON record and in the checksum manifest below.

| Registry | Pinned provider | 32.3.1 / 32.5.0 occurrences | API / minApi | Primary descriptor artifact |
|---|---|---:|---|---|
| `com.android.tools.lint.checks.BuiltinIssueRegistry` | `com.android.tools.lint:lint-checks:32.5.0` | 512 / 517 | 16 / 16 | [source JAR](https://dl.google.com/dl/android/maven2/com/android/tools/lint/lint-checks/32.5.0/lint-checks-32.5.0-sources.jar) |
| `androidx.compose.runtime.lint.RuntimeIssueRegistry` | `androidx.compose.runtime:runtime-lint:1.12.1` | 16 / 16 | 16 / 16 | [source JAR](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-lint/1.12.1/runtime-lint-1.12.1-sources.jar) |
| `androidx.compose.ui.lint.UiIssueRegistry` | `androidx.compose.ui:ui-lint:1.12.1` | 15 / 15 | 16 / 16 | [source JAR](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-lint/1.12.1/ui-lint-1.12.1-sources.jar) |
| `androidx.compose.foundation.lint.FoundationIssueRegistry` | `androidx.compose.foundation:foundation-lint:1.12.1` | 3 / 3 | 16 / 16 | [source JAR](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-lint/1.12.1/foundation-lint-1.12.1-sources.jar) |
| `androidx.compose.animation.lint.AnimationIssueRegistry` | `androidx.compose.animation:animation-lint:1.12.1` | 6 / 6 | 16 / 14 | [source JAR](https://dl.google.com/dl/android/maven2/androidx/compose/animation/animation-lint/1.12.1/animation-lint-1.12.1-sources.jar) |
| `androidx.compose.material.lint.MaterialIssueRegistry` | `androidx.compose.material:material-lint:1.10.0` | 2 / 2 | 16 / 14 | [source JAR](https://dl.google.com/dl/android/maven2/androidx/compose/material/material-lint/1.10.0/material-lint-1.10.0-sources.jar) |
| `androidx.compose.material3.lint.Material3IssueRegistry` | `androidx.compose.material3:material3-lint:1.4.0` | 2 / 2 | 16 / 14 | [source JAR](https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3-lint/1.4.0/material3-lint-1.4.0-sources.jar) |
| `androidx.compose.animation.core.lint.AnimationCoreIssueRegistry` | `androidx.compose.animation:animation-core-lint:1.12.1` | 2 / 2 | 16 / 14 | [source JAR](https://dl.google.com/dl/android/maven2/androidx/compose/animation/animation-core-lint/1.12.1/animation-core-lint-1.12.1-sources.jar) |
| `androidx.compose.runtime.saveable.lint.RuntimeSaveableIssueRegistry` | `androidx.compose.runtime:runtime-saveable-lint:1.12.1` | 1 / 1 | 16 / 14 | [source JAR](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-saveable-lint/1.12.1/runtime-saveable-lint-1.12.1-sources.jar) |
| `androidx.lifecycle.lint.LifecycleRuntimeIssueRegistry` | `androidx.lifecycle:lifecycle-runtime-android:2.11.0` | 2 / 2 | 16 / 14 | [AAR containing lint.jar](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-android/2.11.0/lifecycle-runtime-android-2.11.0.aar) |
| `androidx.lifecycle.runtime.compose.lint.LifecycleRuntimeComposeIssueRegistry` | `androidx.lifecycle:lifecycle-runtime-compose-android:2.11.0` | 1 / 1 | 16 / 14 | [AAR containing lint.jar](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-compose-android/2.11.0/lifecycle-runtime-compose-android-2.11.0.aar) |
| `androidx.lifecycle.lint.LiveDataCoreIssueRegistry` | `androidx.lifecycle:lifecycle-livedata-core:2.11.0` | 1 / 1 | 16 / 16 | [AAR containing lint.jar](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-livedata-core/2.11.0/lifecycle-livedata-core-2.11.0.aar) |
| `androidx.navigation.common.lint.NavigationCommonIssueRegistry` | `androidx.navigation:navigation-common-android:2.10.2` | 4 / 4 | 16 / 14 | [AAR containing lint.jar](https://dl.google.com/dl/android/maven2/androidx/navigation/navigation-common-android/2.10.2/navigation-common-android-2.10.2.aar) |
| `androidx.navigation.runtime.lint.NavigationRuntimeIssueRegistry` | `androidx.navigation:navigation-runtime-android:2.10.2` | 5 / 5 | 16 / 14 | [AAR containing lint.jar](https://dl.google.com/dl/android/maven2/androidx/navigation/navigation-runtime-android/2.10.2/navigation-runtime-android-2.10.2.aar) |
| `androidx.navigation.compose.lint.NavigationComposeIssueRegistry` | `androidx.navigation:navigation-compose-android:2.10.2` | 6 / 6 | 16 / 14 | [AAR containing lint.jar](https://dl.google.com/dl/android/maven2/androidx/navigation/navigation-compose-android/2.10.2/navigation-compose-android-2.10.2.aar) |
| `androidx.navigation3.ui.lint.Navigation3UiIssueRegistry` | `androidx.navigation3:navigation3-ui-android:1.2.0` | 1 / 1 | 16 / 16 | [AAR containing lint.jar](https://dl.google.com/dl/android/maven2/androidx/navigation3/navigation3-ui-android/1.2.0/navigation3-ui-android-1.2.0.aar) |
| `androidx.fragment.lint.FragmentIssueRegistry` | `androidx.fragment:fragment:1.9.1` | 9 / 9 | 16 / 14 | [AAR containing lint.jar](https://dl.google.com/dl/android/maven2/androidx/fragment/fragment/1.9.1/fragment-1.9.1.aar) |
| `androidx.activity.lint.ActivityIssueRegistry` | `androidx.activity:activity:1.13.0` | 2 / 2 | 16 / 14 | [AAR containing lint.jar](https://dl.google.com/dl/android/maven2/androidx/activity/activity/1.13.0/activity-1.13.0.aar) |
| `androidx.activity.compose.lint.ActivityComposeIssueRegistry` | `androidx.activity:activity-compose:1.13.0` | 3 / 3 | 16 / 14 | [AAR containing lint.jar](https://dl.google.com/dl/android/maven2/androidx/activity/activity-compose/1.13.0/activity-compose-1.13.0.aar) |
| `androidx.work.lint.WorkManagerIssueRegistry` | `androidx.work:work-runtime:2.12.0` | 9 / 9 | 16 / 14 | [AAR containing lint.jar](https://dl.google.com/dl/android/maven2/androidx/work/work-runtime/2.12.0/work-runtime-2.12.0.aar) |

Standalone `*-lint` coordinates do not enumerate every AndroidX pack. Lifecycle, Navigation, Fragment, Activity and WorkManager rules here are extracted from **actual embedded AAR lint.jar registries**. The inspected lifecycle-viewmodel(-savedstate), Navigation UI/Fragment, Navigation 3 runtime and Hilt Work AARs had no embedded lint.jar; this is an artifact-specific observation, not proof that their compiler checks or other releases have no diagnostics. Guessed standalone coordinates that returned 404 are not absent-rule evidence.

### Lint 32.3.1 → 32.5.0 builtin delta

The JSON preserves per-ID baseline/current membership, baseline source/default/severity/scope and observed changed descriptors. Never silently apply a newer-only issue to the baseline or use a newer descriptor/default when interpreting old output.

| Delta | IDs / observed change |
|---|---|
| Current-only (7) | `CompileTimeConstant`, `GuardedBy`, `Immutable`, `MissingExplicitUriGrant`, `SynchronizeOnValueBasedClass`, `ThreadPriorityConfusion`, `TilePreviews` |
| Baseline-only (2) | `DalvikOverride`, `SquareAndRoundTilePreviews` |
| Same-ID rationale changes (2) | `RelativeClassResolution` whitespace normalization; `WearMaterialTheme` now recommends Wear Material3 rather than the older Wear Material2 reference |
| Same-ID defaults change (4) | `WatchFaceEditor`, `WatchFaceForAndroidX`, `WearableActionDuplicate`, `WearableConfigurationAction`: default true → false |

`DalvikOverride` remains baseline-only legacy advice; `SquareAndRoundTilePreviews` remains an explicit baseline UI policy record. Do not assume it is an automatic alias of `TilePreviews`. The seven newer-only IDs require a separately qualified 32.5.0 tuple/adapter; baseline absence is a reported coverage limitation, not an invented clean result.

Google Maven metadata observed stable Lint **32.5.0** and prerelease head **32.6.0-alpha02**. [AGP release notes](https://developer.android.com/build/releases/agp-9-5-0-release-notes) identify **9.5.0** as the current October 2026 release. [KGP compatibility](https://kotlinlang.org/docs/gradle-configure-project.html) and the [KMP compatibility guide](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html) list KGP 2.4.20–2.4.21 with Gradle 7.6.3–9.7.0, AGP 8.5.2–9.3.1 and Xcode 26.4. AGP 9.5.0 lies **outside that tested KMP table**; this is support-range advice, not proof that every such build is incompatible. The selected Xcode patch/JDK/Gradle combination still needs actual host/target CI.

## 3. Compose, lifecycle/navigation and maintained pack

### First-party and common candidates

The eight pinned Compose registries provide **47 atomic occurrences** covering runtime state/remember usage, modifier contracts/conventions, pointer/effect scopes, animation/material checks and saveable-state entrypoints. The JSON identifies every issue and source scope; Android lint execution on commonMain/iOS is unverified. Runtime/performance guidance about instability, strong skipping, autoboxing, missing remember or restartability is not a universal semantic defect.

[Compose Multiplatform compatibility](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html) identifies **CMP 1.12.2**, whose Android side maps to **AndroidX Compose 1.12.1**. The Compose compiler Gradle plugin follows **Kotlin's version**, not CMP's/runtime's version. The documentation requires K2/Kotlin 2.1.0+ and recommends 2.2.20+ for rapidly evolving targets. Desktop runtime requires JDK 11+, packaging JDK 17+; the documented iOS minimum is 14. These upstream ranges do not establish a ktChecker-supported tuple.

The **seven custom Compose candidates** separately cover exact duplicate constant lazy-item keys, actual `Immutable` promise violation, stale effect callbacks, disposal ownership, target-specific saveability, declared locale fallback and meaningful-icon-label policy. Constant/Unit effects, deliberate initial capture, ownership transfer, custom Saver/registry, decorative icons and target-specific resource choices are valid controls. Bundle/Parcelable restrictions must not be imposed on arbitrary common/iOS/desktop saveable-state registries.

Lifecycle has **4 published occurrences** plus **3 custom candidates**. [Published Lifecycle 2.11.0 common source](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime/2.11.0/lifecycle-runtime-2.11.0-sources.jar) explicitly rejects `repeatOnLifecycle(INITIALIZED)`; the high rule is scoped to that resolved guard. Choice of STARTED versus RESUMED, Fragment view-owner collection, desktop Main dispatcher and Android lifecycle-state expectations remain ownership/policy/advice questions. [CMP lifecycle mappings](https://kotlinlang.org/docs/multiplatform/compose-lifecycle.html) must control desktop/iOS adaptation, not copied Android transitions.

Navigation 2 contributes **15 artifact-scoped occurrences**, Navigation 3 **1**, plus **2 custom navigation candidates**. Shared serialization/Keep IDs need one canonical owner; actual shrinker settings, route serializers/custom registries and runtime key identity matter. Android `@Keep` advice is not a Native requirement. See [typed Navigation](https://developer.android.com/guide/navigation/design/type-safety) and [CMP navigation](https://kotlinlang.org/docs/multiplatform/compose-navigation-routing.html).

### Maintained Compose Rules 0.6.8

The [tagged Detekt provider](https://github.com/mrmans0n/compose-rules/blob/v0.6.8/rules/detekt/src/main/kotlin/io/nlopez/compose/rules/detekt/ComposeRuleSetProvider.kt) individually registers **43 children**; the [tagged Ktlint provider](https://github.com/mrmans0n/compose-rules/blob/v0.6.8/rules/ktlint/src/main/kotlin/io/nlopez/compose/rules/ktlint/ComposeRuleSetProvider.kt) registers **35 matching aliases**. Each exact child/alias is listed in the JSON with its tagged implementation/test/provider/configuration source; aliases are not counted as extra coverage. The rule-pack family itself is never a wildcard enable.

The [tagged version catalog](https://github.com/mrmans0n/compose-rules/blob/v0.6.8/gradle/libs.versions.toml) pins **Detekt 2.0.0-alpha.6 / Kotlin analysis syntax 2.4.10** and **Ktlint 1.8.0 / Kotlin parser 2.2.21** (rule implementation compiler 2.4.0). This cannot be assumed load-compatible with stable Detekt 1.23.8, nor does a parser version certify the consumer KGP 2.4.21 syntax/analysis profile. The upstream older 0.4.23 line is a separate tuple, not interchangeable coverage. Qualify pack/frontend/provider versions independently of the main analyzer.

Pack dispositions are **1 eligible, 25 opt-in, 15 advisory and 2 excluded duplicates**. Naming/parameter/modifier API conventions are style; emitter nesting/forwarding/ViewModel/CompositionLocal preferences are explicit policy. Lambda remembering, unstable collection and recomposition performance heuristics remain advice under the selected compiler/runtime. `InvalidReadOnlyComposable` is the single prospective exact-contract child and requires resolved eager composer-effect/call facts; deferred callbacks, unresolved or actually read-only callees are not inferred writes from syntax alone.

First-party overlap includes composable/CompositionLocal naming, remembered-state construction, modifier parameter/composed usage and state autoboxing. The duplicate pack naming children are excluded; other records name `overlap_with` and a one-owner behavior. On Android prefer the exact resolved first-party provider where its pattern matches; a separately qualified common PSI advice path may use the pack, but must not emit both causes or bypass suppression. Analysis-API rules need a qualified JVM/Android classpath; syntax-only common/Native parsing is not Native semantic support.

## 4. Hilt, Gradle/KSP, Native and desktop candidates

The remaining custom inventory is **Hilt 5, Gradle 13, KSP 5, Native/iOS 20, desktop 7 and Android 2** (plus the Compose/lifecycle/navigation 12 above). All have explicit positive/negative/boundary/version/suppression/regression plans and per-rule primary sources.

| Area | Practical candidates / primary evidence | Required boundary or valid alternative |
|---|---|---|
| Hilt/Dagger | Retained injected Fragments, effective plugin/setup route, version alignment and aggregation policy; [entry points](https://dagger.dev/hilt/android-entry-point.html), [Gradle setup](https://dagger.dev/hilt/gradle-setup.html), [KSP support](https://dagger.dev/dev-guide/ksp.html) | Resolve real injected Fragment/self lifetime and configuration. Root/included-build plugin routes and custom generation are legitimate; missing bindings/scopes/cycles remain actual compiler diagnostics, not invented rule IDs. |
| Gradle/KMP | Compose compiler/Kotlin alignment, qualified support pairs, Android KMP Java/resources/layout, actual configuration-cache execution access, eager task realization, declared checksum format and selected verification/repository policy | Resolve plugin aliases, effective flags, task phase/providers, cache and selected toolchain, not catalog strings. Configuration-phase Project access is legal; eager realization is performance advice. A malformed declared wrapper checksum needs an actual required verification path, not an already-cached distribution. |
| KSP | Same-module kapt/javac generated-type visibility barrier, target processor configuration, legacy all-target setup, KSP1 switch and incremental metadata policy | A separate compiled module or KSP producer can be valid. Mixed kapt/KSP alone, unused processors or pending generation do not prove missing types. Resolve actual producer/consumer/output/round/target graph. |
| Native/iOS | Post-scope allocation access, actual disposed StableRef access, pin lifetime advice, explicitly recoverable ObjC export, cinterop schema/inputs, API export edges, required XCFramework slices, supported Xcode/iOS and Swift-export configuration | Exact ownership, target ABI and completed artifact/model facts are required. Caller-owned/C-owned pointers, address-only escape, deliberate fatal invariants, device-only distribution and documented wrapper headers are legal controls. |
| Native performance | Transitive export size/time, ObjC collection bridge copies, ARC cycles and autoreleasepool budgets; [ARC integration](https://kotlinlang.org/docs/native-arc-integration.html) | These are bounded runtime/profile advice, not leaks or unacceptable cost proved by syntax. Verify actual retention/collection/callback graph, pool lifetime and an explicit budget. |
| Desktop | Actual MainDispatcherFactory availability, jpackage JDK/format/host, jlink runtime modules, keyboard accessibility and release signing policy; [native distributions](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html) | Coroutines-swing is one provider, not the only one. Multi-format build declarations are legitimate; requested foreign-host output can be skipped. Reflection/service loading and actual packaged image matter. Do not automatically sign/publish/contact services. |
| Android | Resolved WebView URI trust-gate advice and declared sensitive backup-path policy; [unsafe URI loading](https://developer.android.com/privacy-and-security/risks/unsafe-uri-loading), [Auto Backup](https://developer.android.com/identity/data/autobackup) | Explicit trust/transport/path policy and actual merged SDK/rules are required; do not infer secrets from filenames or read secret contents. Decorative/intentional broad URI matching is not automatically an exploit. |

[Dagger/Hilt Maven metadata](https://repo.maven.apache.org/maven2/com/google/dagger/hilt-android/maven-metadata.xml) observed **2.60.1**; [KSP metadata](https://repo.maven.apache.org/maven2/com/google/devtools/ksp/symbol-processing-gradle-plugin/maven-metadata.xml) observed **2.3.13**. Current Dagger KSP docs identify stable support in **Dagger 2.60+ / KSP 2.3.9+**; AndroidX Hilt KSP belongs to its separate **1.4.x** line. These independent versions cannot be forced to equal Kotlin or each other. An actual rejecting compiler/task owns its errors.

[Android's KMP library plugin guide](https://developer.android.com/kotlin/multiplatform/plugin) requires AGP 8.10+/KGP 2.0+, uses `com.android.kotlin.multiplatform.library` and requires explicit Java/resources/tests setup. It does not support product flavors/build types or data/view binding; a separate Android module is a documented alternative. `androidLibrary {}` is deprecated from AGP 9.1.0-alpha09; `android {}` exists from 8.12. Migration advice is versioned, not proof that every old layout currently fails.

[Swift export](https://kotlinlang.org/docs/native-swift-export.html) is currently **Alpha**, with **suspend → async and Flow → AsyncSequence support**. Historical blanket exclusions for those APIs are incorrect. Direct Xcode integration, generic erasure, collection-inheritor and opt-in constraints remain versioned qualifications; Objective-C framework export is a different ABI/error-bridging path. [Native framework export](https://kotlinlang.org/docs/multiplatform/multiplatform-build-native-binaries.html) requires applicable API dependencies; transitive export is a size/time recommendation, not universal forbidden behavior. XCFramework slice proof requires an explicitly known consumer platform/variant/architecture and its completed Info.plist, not a package-name guess.

A separately implemented and qualified **Kotlin/Native resolver** is mandatory for semantic Native claims. JVM Detekt analysis/UAST, common PSI and parsed `.def` files do not supply Native lifetime, ownership, actual library ABI or generated export facts. Common-source analysis retains the source-set `dependsOn` DAG and resolves each actual consuming target independently. A common label does not establish Android, iOS, JS/Wasm or every Native target support.

Four overlapping core Native disposition aliases point to the platform canonical owners (`memscoped_pointer_escape`, `pinned_pointer_lifetime`, `stable_ref_use_after_dispose`, `objc_unbridged_explicit_exception`). Those excluded aliases are not extra semantic coverage or alternative proof lanes; target/cause/provenance/suppression must be deduplicated.

## 5. Concrete source seeds and intentional qualification gaps

The import lookup was corrected to ignore quoted fixture programs/comments and to identify real test declarations rather than methods inside source strings. Across **609 imported records**:

- **593** have a concrete pinned secondary test-source pointer: **583** include issue-ID expected-output source assertions; **10** are detector-level source seeds without a selected issue-specific expected-output assertion.
- **514** have a selected test source containing a clean assertion. These are **source assertions**, not executed tests, and many detectors own multiple issues; verify the chosen legal control is specific to the atomic issue.
- **79** have a positive/detector source but no selected distinct clean control; **16** have no fetched issue-specific test source. Together these leave **95 records without a selected control-source pointer**.
- The **43 pack children** have tagged reporting-test source pointers; **41** have tagged empty-findings control source pointers. `ContentEmitterReturningValues` and `ModifierComposed` lack a selected control and need explicit legitimate alternatives before qualification.
- Every custom record has concrete proposed accepted/rejected cases, but **none** has an implemented or executed ktChecker fixture.

The secondary source snapshots are [AOSP `41322e9dcbb9b6751c629e83d6e10eb0bc9d8f92`](https://android.googlesource.com/platform/tools/base/+/41322e9dcbb9b6751c629e83d6e10eb0bc9d8f92/lint/libs/lint-tests/src/test/java/com/android/tools/lint/checks/) and [AndroidX `a7fbd040a3238f6660032dc2b1256ffcf75c40c6`](https://github.com/androidx/androidx/tree/a7fbd040a3238f6660032dc2b1256ffcf75c40c6). They may differ from the published provider release. Per-rule paths/method anchors are lookup seeds; source compatibility and actual assertion behavior must be verified before adoption. The Compose pack archive is exactly tag **v0.6.8**.

The 16 missing imported source pointers are retained explicitly in `upstream_fixture_gap`:

- `ktc.compose.lint.animation_lint.DisallowLookaheadAnimationVisualDebug`
- `ktc.lifecycle.lint.lifecycle_runtime_android.UnsafeLifecycleWhenUsage`
- `ktc.lifecycle.lint.lifecycle_runtime_android.RepeatOnLifecycleWrongUsage`
- `ktc.lifecycle.lint.lifecycle_livedata_core.NullSafeMutableLiveData`
- `ktc.navigation.lint.navigation_common_android.MissingSerializableAnnotation`
- `ktc.navigation.lint.navigation_common_android.MissingKeepAnnotation`
- `ktc.navigation.lint.navigation_runtime_android.MissingKeepAnnotation`
- `ktc.navigation.lint.navigation_runtime_android.MissingSerializableAnnotation`
- `ktc.navigation.lint.navigation_compose_android.MissingKeepAnnotation`
- `ktc.navigation.lint.navigation_compose_android.MissingSerializableAnnotation`
- `ktc.lifecycle.lint.fragment.FragmentAddMenuProvider`
- `ktc.lifecycle.lint.fragment.FragmentBackPressedCallback`
- `ktc.lifecycle.lint.fragment.FragmentLiveDataObserve`
- `ktc.android.lint.activity.InvalidUseOfOnBackPressed`
- `ktc.android.lint.work_runtime.BadConfigurationProvider`
- `ktc.android.lint.DalvikOverride`

No source seed substitutes for the six own consumer tests, per-ID enable/suppression mapping, alias/overlap ownership, generated/test source treatment, unsupported-tuple behavior or accepted/rejected/adversarial precision corpus. Tests must retain **actual** upstream output: if a legitimate control still reports, narrow/adapt the provider, route advice/policy or exclude it. Do not claim a no-alert exemption that was only proposed.

### Known exclusions and provider ownership

**26 platform records are deliberately excluded**, not forgotten. Twenty-one builtin records concern selected-out historical/special products or detector-development diagnostics (including C2DM/Cordova/Instant Apps/old Firebase hooks, FE10 and Lint/UAST implementation checks, and WatchFace editor/API checks); this is v1 scope selection, not proof that legacy applications never have those defects. Two pack naming duplicates are excluded in favor of first-party ownership. Three custom records exclude duplicate Hilt compiler graph findings, unsupported Android KMP features already owned by the plugin/compiler, and historical universal Native freezing requirements. Current Native's memory manager does not require universal freezing; real race contracts remain separate.

Android Lint is the resolved Android/Gradle provider, not a portable Native engine. Consume the actual trusted/selected AGP task/model output in a versioned isolated adapter, preserving provider, source set, variant, SDK, resolved dependencies, generated provenance and source locations. Do not load arbitrary lint artifacts into the main analyzer process. Online library/compliance/App Links metadata and runtime UI/memory probes are optional slow/advisory modes with explicit environment and selected oracle, not inferred static proof.

Failed compilation/resolution, malformed output, unsupported API/provider, unknown diagnostic IDs and failed generation are **task/model errors or coverage gaps**, never a successful clean run. Kotlin/Hilt/KSP/native linker and plugin restrictions remain their actual task/compiler-owned diagnostics until exact catalogs are exported, dispositioned and consumer-tested. No invented diagnostic IDs, generic compiler-error findings or wildcard family enablement is allowed.

## 6. Release qualification and licensing

Before claiming coverage or enabling any record:

1. Lock actual provider/compiler/runtime/SDK/Gradle/host/source-set tuples, input artifact checksums, upstream IDs and applicable predicates. Compare descriptors/defaults on upgrades atomically; an unknown ID remains an error until separately dispositioned/tested.
2. Implement the isolated consumer/model adapter and every extra classifier/narrowing predicate. Execute the six own issue-specific cases with actual output, target/location/provenance, local enable/suppression, overlap/canonical ownership and unsupported/partial-model controls.
3. Verify source-seed compatibility with the pinned published release and fill the stated expected-output/control/source gaps. Complete the empirical/adversarial precision gates for medium candidates and witness/precondition review for high candidates.
4. Execute actual supported Android/JVM/common target compilations and macOS/iOS/export/package consumers. Do not count constructor loading or Linux common parsing as Native/iOS CI. Runtime advice needs the requested environment/budget/oracle and retained measurements.
5. Record successful test runs/evidence, source/license obligations and version ranges before moving `planned` through implementation/test/validation status. All gates remain unexecuted here; see [validation and CI](../validation-ci.md).

AOSP Android Lint and the inspected AndroidX lint sources carry Apache 2.0 headers/license material. This catalog **already incorporates upstream descriptors/rationale**, so attribution applies now, not only when a future binary ships. [Third-party notices](../../THIRD_PARTY_NOTICES.md), [Apache 2.0](../../third_party/licenses/Apache-2.0.txt) and the [exact published Lint NOTICE](../../third_party/licenses/AOSP-Lint-NOTICE.txt) retain the AOSP copyright/terms. Compose Rules' [tagged Apache license](https://github.com/mrmans0n/compose-rules/blob/v0.6.8/LICENSE.md) and README credit Twitter, Inc. (2022) and Nacho Lopez (2026); source-file notices remain linked per child.

A shipped adapter must separately audit bundled/modified code, all transitive compiler/UAST/provider dependencies, NOTICE obligations and SBOM. Consuming task output does not authorize redistribution of Android/Apple SDKs or arbitrary downloaded toolchains; those have separate terms. No provider binaries or full upstream implementations are added to this repository by this research.

### Exact retrieved artifact checksums

SHA-256 below applies to the **linked source JAR or complete AAR**, not interchangeably to its embedded lint.jar. Actual published provider JAR/embedded lint.jar hashes are also attached per JSON record as `upstream_provider_binary_sha256`; baseline hashes use separate fields. This is an input identity lock, not an executed integration attestation.

| Artifact (same links as registry manifest) | Kind | Bytes | SHA-256 |
|---|---|---:|---|
| `androidx.compose.runtime:runtime-lint:1.12.1` | sources.jar | 40078 | `a035ead2dbd48ad76e547f6802df04f666139e5af5f2aedab6c0e17a372e6c19` |
| `androidx.compose.ui:ui-lint:1.12.1` | sources.jar | 36633 | `33a7ac6c85c67b5ccb90f25c3465216dd2b5ccc854b385b2714cc2f07147a766` |
| `androidx.compose.foundation:foundation-lint:1.12.1` | sources.jar | 13428 | `c2241221c39851da9f52177183d9161e238532449e055bd2b0057bd354e8c141` |
| `androidx.compose.material:material-lint:1.10.0` | sources.jar | 12114 | `583505150c31636aa2aba792fd89a2bcb1f828d549395ea14b3c56104717e401` |
| `androidx.compose.material3:material3-lint:1.4.0` | sources.jar | 10524 | `1956130a8a38b457152bd8d7680bcccfc4a57fd79c3246fb3de2069c82609ee6` |
| `androidx.compose.animation:animation-lint:1.12.1` | sources.jar | 17111 | `1de407f5ca725ea6daeb04e26a1d9d279de2d82fbac23042bf24e7e07b4bc168` |
| `androidx.lifecycle:lifecycle-runtime-android:2.11.0` | AAR | 85066 | `8711865f8d3603bc05decc03bb42aab2faa8d8bc55b3b12a0a8e516b562b60ca` |
| `androidx.lifecycle:lifecycle-runtime-compose-android:2.11.0` | AAR | 95484 | `7b31489e6e162143c481f14e1ae5a4dc089f8ec58e65976a0502b910c641987f` |
| `androidx.lifecycle:lifecycle-livedata-core:2.11.0` | AAR | 34157 | `39b26ef1e081fc88d7054ff1a63d0a5d71876373c33a711ab789c8228d025ad1` |
| `androidx.navigation:navigation-common-android:2.10.2` | AAR | 350078 | `b231c0345d98d62de551ea5ffd94a955910ac2bb3cc61ae94fca4219e7c323f2` |
| `androidx.navigation:navigation-runtime-android:2.10.2` | AAR | 177942 | `91a240425d3ea6702e5d8eabef52d23155e5856f6528ea33f06a5f60e3ef4b4f` |
| `androidx.navigation:navigation-compose-android:2.10.2` | AAR | 177391 | `659fd5bb30acf15f52c5a21475cf85618af603aca78660110e514b2df7df3110` |
| `androidx.navigation3:navigation3-ui-android:1.2.0` | AAR | 115891 | `ce00086ea93af85e1adc332afae5b260707b8c6c75faa05f0fdc22995bad3173` |
| `androidx.fragment:fragment:1.9.1` | AAR | 431225 | `72f70815c0a4d502f15c57e5e6bc861e64c5338ea06de75aed83969b17a2a727` |
| `androidx.activity:activity:1.13.0` | AAR | 222940 | `bd4f19a5f00effed0eecad0a310b1f2169b9fc66236a3ba743a222eb77747ee1` |
| `androidx.activity:activity-compose:1.13.0` | AAR | 144321 | `2b5da3033d4924e833868e140c3edfc0c03208710b6b9fb2c9d9a47560ba55fa` |
| `androidx.compose.animation:animation-core-lint:1.12.1` | sources.jar | 10916 | `7437392141dc741aacb896b595f5d500f0fd80ae3db720a4ee63cd2a5801e0b6` |
| `androidx.compose.runtime:runtime-saveable-lint:1.12.1` | sources.jar | 8950 | `eca3f7ee44d15cfc2682a2470f14796833a4235ca0534e7db18af17914e2c4be` |
| `com.android.tools.lint:lint-checks:32.5.0` | sources.jar | 1499552 | `2cbb65ed8dc7fb6348e76e68b1dcad5d56e6984e09ebe6765dcd220d7c47117d` |
| `androidx.work:work-runtime:2.12.0` | AAR | 1264436 | `a967c776d2c2ff9a7a541679d2fb51cefdc78d4010fcf68f3fdd4c57c29e89dc` |
| [`com.android.tools.lint:lint-checks:32.3.1`](https://dl.google.com/dl/android/maven2/com/android/tools/lint/lint-checks/32.3.1/lint-checks-32.3.1-sources.jar) | sources.jar | 1428844 | `d58e65882bdd1fe570a8d5012e77c815699b8fe120ac8193cef4a69d5468abc6` |

| Additional source/input | Bytes | SHA-256 |
|---|---:|---|
| Lint API 32.5.0 source | 529520 | `644bea26e6209f25f747bfa5fc87b06da503db0348c38e588d961979f9abe20e` |
| Compose Rules v0.6.8 tagged source archive | 448731 | `37539d9558b9e5abdcc18d7a480f6c37fe48e4421624e38c8649aeeeb21557f6` |
| AOSP test snapshot archive | 1224393 | `616ebacb8ce8312fe717fc6aa9188ddd4502dccf561d7911eca8c67c8605abac` |
| 32.5.0 metadata export | 498307 | `004abc1b9cf8d119f1df03feee63ae302de16fe4f4d24cd4d0b16c0353322dad` |
| 32.3.1 metadata export | 493515 | `c44f5887f3600912fdc6985453e71630ba5639e90bf3ddfef4f106fd8e4e9a18` |
| [lifecycle-runtime-source-2.11.0](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime/2.11.0/lifecycle-runtime-2.11.0-sources.jar) | 24822 | `b7d5ffbea735479b5a2a52a2ffb410a199d846a076b3786bd24acf13d7ecb3dd` |
| [lifecycle-runtime-android-source-2.11.0](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-android/2.11.0/lifecycle-runtime-android-2.11.0-sources.jar) | 27281 | `2606b753031b51b71d7a5f36ff6596fd410e886bda4ef3487a7ee2bd2bdfab34` |
| [compose-runtime-api-source-1.12.1](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime/1.12.1/runtime-1.12.1-sources.jar) | 594365 | `0ba59f493f3d13e4078bdd9ed80c6567f85346fe6a3ea2481548b68fc6200661` |
| [compose-foundation-api-source-1.12.1](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation/1.12.1/foundation-1.12.1-sources.jar) | 1100543 | `52a6d0cc33f64d1c839f75836e6c20cd8193ab39b6a9ee761a7caaf7da93016a` |
| [Compose Immutable annotation 1.12.1 source](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-annotation/1.12.1/runtime-annotation-1.12.1-sources.jar) | 12735 | `272b2b47cf1d938a66fa4c92e73ab8820c91f9c0d07208050aa5986988bc73b9` |

The two metadata-export hashes describe this research's serialized introspection output; they do not attest to a consumer test run. Temporary fetched artifacts/exports are not bundled. The repository retains atomic extracted metadata and source/hash links; release reproducibility still requires a maintained exporter/input lock and validation evidence. Mutable official documentation was accessed on the stated date and must be rechecked against the selected release before widening support.

# Library rules for the first release

This is a **research catalog**, not implemented checker coverage. All **125** records in [rules-libraries.json](../../data/rules-libraries.json) have `status: planned`. Their six validation cases are **proposed fixtures**; no ktChecker detector, provider integration or rule fixture was executed for this research. Primary documentation, tagged source and release metadata were retrieved on **2026-11-04 UTC**.

The scope is library-specific behavior and test integration. General Kotlin/style rules and Compose/lifecycle/navigation/Hilt/Dagger/Gradle/KSP research are separate. A library appearing here does not imply every API, release, source set or target is supported.

## Activation and evidence contract

Resolve the actual compilation source set, its `dependsOn` closure, target, resolved dependency graph and library/compiler/provider versions. Match fully qualified callable identity, overload, receiver and effective configuration before considering a rule. Names, imports, dependency declarations and a library appearing elsewhere in the repository do not establish applicability. Abstain on unresolved calls, unsupported tuples, opaque factories/plugins/modules, incomplete ownership or insufficient effect information.

A `common` entry describes a candidate common API/source-set analysis, not a universally runnable provider. Check the actual compilations consuming that source set. JVM-only Detekt/UAST/reflection providers do not certify common/iOS code. Conversely, a library having Native/JS/Wasm artifacts does not prove that a ktChecker provider can analyze or execute all of them.

Severity, kind, certainty, implementation choice and activation are independent:

- **Correctness / high:** evidence supports an exact documented API/guard contract. Future default activation still requires a reviewed predicate, demonstrated preconditions and own version/target integration fixtures. The label is neither a calibrated probability nor completed detector validation.
- **Probable defect / medium:** bounded semantic/graph heuristics can preserve many valid alternatives but are not proof-certified. The 31 such default candidates require an empirical/adversarial false-positive gate per supported tuple, with reportable corpus, denominators and limitations; otherwise keep them advisory or abstain.
- **Policy:** ownership boundaries, dispatchers, clock choice, performance, logging, retries, timeout budgets, fixture sharing and storage expectations are intent-dependent unless the owner selects a policy. They are opt-in here, including the explicit `DoNotMock` adapter.
- **Runtime evidence:** execute only explicitly selected probes/tests. Normalize an actual provider result against its selected oracle/environment, not an inferred static outcome. Neither a pixel difference nor any failing process automatically proves a source defect.

`enabled_when_validated` is a future eligibility disposition, **not enabled functionality**. Missing `close`, missing timeouts, more than one loader, a Java matcher import or absence of `runTest` alone proves none of the corresponding defects. Compiler/linker/provider diagnostics must be attributed and deduplicated rather than counted as fresh independent discoveries.

## Coverage inventory

| Category | Planned | Correctness | Probable defect | Policy | Future default candidates | Advisory | Opt-in |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| coroutines | 31 | 6 | 13 | 12 | 12 | 7 | 12 |
| serialization | 12 | 4 | 5 | 3 | 9 | 0 | 3 |
| datetime | 9 | 2 | 2 | 5 | 4 | 0 | 5 |
| ktor | 15 | 0 | 7 | 8 | 6 | 1 | 8 |
| room | 10 | 4 | 2 | 4 | 6 | 0 | 4 |
| sqldelight | 7 | 2 | 2 | 3 | 3 | 0 | 4 |
| koin | 6 | 2 | 3 | 1 | 3 | 0 | 3 |
| coil | 8 | 3 | 2 | 3 | 5 | 0 | 3 |
| kamel | 2 | 0 | 2 | 0 | 2 | 0 | 0 |
| librarytest | 25 | 5 | 12 | 8 | 9 | 6 | 10 |
| **Total** | **125** | **28** | **50** | **47** | **59** | **14** | **52** |

Implementation routes are 105 proposed custom adapters, 14 proposed adaptations and 6 proposed existing-provider adapters. These describe reuse strategy, not installed providers. All 28 correctness records are high-evidence future default candidates; the other 31 future default candidates are medium-evidence probable defects. Every record carries applicability, limitations, version scope, provider, depth/cost, sources and six fixture plans.

## Observed release snapshot

| Library | Observed upstream version | Release/source |
| --- | --- | --- |
| kotlinx.coroutines | 1.11.0 | [release](https://github.com/Kotlin/kotlinx.coroutines/releases/tag/1.11.0) |
| kotlinx.serialization | 1.12.0 | [release](https://github.com/Kotlin/kotlinx.serialization/releases/tag/v1.12.0) |
| kotlinx.datetime | 0.8.0 | [release](https://github.com/Kotlin/kotlinx-datetime/releases/tag/v0.8.0) |
| Ktor | 3.6.1 | [release](https://github.com/ktorio/ktor/releases/tag/3.6.1) |
| Room 2 | 2.8.5 | [release page](https://developer.android.com/jetpack/androidx/releases/room) |
| Room 3 | stable 3.0.3; preview 3.1.0-alpha03 | [release page](https://developer.android.com/jetpack/androidx/releases/room3) |
| SQLDelight | 2.4.1 | [release](https://github.com/sqldelight/sqldelight/releases/tag/2.4.1) |
| Koin core | 4.2.2 | [release](https://github.com/InsertKoinIO/koin/releases/tag/4.2.2) |
| Coil | 3.6.3 | [release](https://github.com/coil-kt/coil/releases/tag/3.6.3) |
| Kamel | 1.0.9 | [release](https://github.com/Kamel-Media/Kamel/releases/tag/v1.0.9) |
| Kotest | 6.2.6 | [release](https://github.com/kotest/kotest/releases/tag/v6.2.6) |
| MockK | 1.14.11 | [release](https://github.com/mockk/mockk/releases/tag/v1.14.11) |
| Mockito | 5.24.0 | [release](https://github.com/mockito/mockito/releases/tag/v5.24.0) |
| Mockito Kotlin | 6.4.0 | [release](https://github.com/mockito/mockito-kotlin/releases/tag/v6.4.0) |
| Turbine | 1.2.1 | [release](https://github.com/cashapp/turbine/releases/tag/1.2.1) |
| Roborazzi | 1.77.0 | [release](https://github.com/takahirom/roborazzi/releases/tag/1.77.0) |
| JUnit | 6.1.3; JUnit 4 API 4.13.2 | [current release](https://github.com/junit-team/junit-framework/releases/tag/r6.1.3), [JUnit 4 contract](https://junit.org/junit4/javadoc/4.13.2/org/junit/Test.html) |
| Detekt | stable 1.23.8; current docs 2.0.0-alpha.6 | [stable release](https://github.com/detekt/detekt/releases/tag/v1.23.8), [current docs](https://detekt.dev/docs/rules/coroutines/) |
| Slack lint | latest release observed 0.11.1; inspected main source commit `c0e92cb74b103073b5dcad02f8c48c4a1ba21c6d` | [releases](https://github.com/slackhq/slack-lints/releases), [inspected source](https://github.com/slackhq/slack-lints/tree/c0e92cb74b103073b5dcad02f8c48c4a1ba21c6d) |

These are retrieved version facts, not compatibility promises. Independently versioned Kotlin compiler/stdlib, serialization compiler plugin, Koin Compiler Plugin, KSP, test runners, renderers and network adapters need their own resolved tuples. Unversioned official docs can move after this date; use a source/tag snapshot and actual artifact/API checks when implementing.

### Version and target corrections that matter

- **Room 2 and Room 3 are separate families.** Room 2 KMP begins at 2.7; the current KMP guide uses `androidx.room3`. Room 3 stable APIs removed legacy SupportSQLite support except the wrapper and use coroutine/Kotlin-generation/KSP paths. Do not copy Android blocking compatibility guidance into arbitrary Room 3 projects or apply one namespace's builder/lease contract to the other.
- **Ktor engine declarations are not engine absence evidence.** Current Ktor offers `ktor-client-engine-defaults`, which supplies curated target engines. Engines can also be transitive or supplied by a client factory. Current CIO supports more than JVM, including Native/JS/WasmJs; engine capability/version metadata must replace old target-name assumptions.
- **Datetime migration needs source and binary distinctions.** The 0.7 line removed the old Instant/Clock classes, 0.7.1 supplied source aliases, and compatibility releases supplied old binary classes. The 0.8 changelog mentions a separate compatibility artifact while the README retains older migration instructions. Do not invent 0.8 compatibility coordinates: inspect resolved artifact contents. A legacy constant-pool reference alone is not a reachable failing load/use. Built-in serialization support for stdlib Instant is documented from serialization 1.9; custom serializers can be valid.
- **JSON naming alternatives are distinct.** A Json naming strategy transforms primary names, including `@SerialName`, but `@JsonNames` alternative names remain literal/untransformed. A collision predicate must follow the effective naming/module/descriptor semantics rather than annotation spelling.
- **Kotest 6 is multiplatform.** Current setup describes JVM/JS/Native/Wasm support, plugin id `io.kotest` and KSP-based compiler integration. This does not make JVM-specific package configuration or a JUnit engine universally portable. `InstancePerRoot` is available since 6.0; older per-test/per-leaf modes are deprecated and are not safe blanket recommendations.
- **JUnit 6.1.3 supports suspend test/lifecycle methods.** Its documented prerequisites are Kotlin stdlib, reflection and coroutines; it invokes them via `runBlocking`. The unsupported-signature candidate is scoped to an actual rejecting runner such as standard JUnit 4, not every suspend `@Test`. Virtual-time expectations remain a separate selected policy.
- **Image defaults differ.** Coil 3 does not bundle network fetching by default, but custom components are valid. Kamel's `kamel-image-default` bundles engines per target; granular `kamel-image` does not. In tagged Kamel 1.0.9 source, `httpFetcher(client)` is a deprecated alias of `httpUrlFetcher(client)`, not a separate missing API.
- **SQLDelight guide/version skew is explicit.** Rendered 2.4.1 guide URLs returned 404 during retrieval; the referenced rendered guides are 2.1.0, supplemented with tagged 2.4.1 runtime/driver source. This is not a certified 2.1-to-2.4 compatibility range.

## Provider integration and useful candidate families

| Family | Useful evidence and integration route | Semantic/maintenance limit |
| --- | --- | --- |
| Coroutines/Flow | Reuse/adapt identified Detekt coroutine rules; custom versioned Job/context, callback, continuation, Mutex and Flow effect models | Resolve actual scope/parent, cancellation origin, callable and context. No verified independent provider named `coroutineslint`; do not invent its coverage. |
| Serialization | Normalize compiler-owned findings; custom serializer/module/descriptor/Json analysis for reachable unsupported serializer, registration, naming and structure contracts | Static types are insufficient for opaque polymorphic/contextual modules and custom serializers. Format/configuration versions are separate. |
| Datetime | Artifact/ABI diagnostic normalization and versioned constant/operation semantics; selected time/clock/precision policies | Calendar arithmetic, elapsed time, clock injection and precision loss depend on intent. Actual runtime load paths and stdlib ABI are needed for migration claims. |
| Ktor | Versioned engine/plugin/body/media adapters; bounded effective client/server config and request pipeline | `ContentNegotiation` names/dependencies alone do not establish a converter. Custom transformations, status handling and middleware can be valid; retry/logging/time budgets are policies. |
| Room | Normalize actual Room compiler warnings; custom pooled lease, permanent-close, reader/writer upgrade and exact builder-contract analysis | Different namespaces/versioned APIs; nested lease reuse can remain valid under an enclosing lease. Relation/index warnings are not invented universal runtime bugs. |
| SQLDelight | Normalize generated-query/dialect/compiler/verifier results; exact schema/driver and transaction lifetime guards | Match runtime artifact, `generateAsync`, driver creation and migration verification task. A verifier can check modeled migrations, not unknown production data. |
| Koin | Actual validation/compiler-plugin evidence or explicitly selected closed module/scope graph validation | JVM reflection `verify()` does not certify Native/common. Dynamic modules, parameters, links, overrides and local-vs-global containers require configuration facts. |
| Coil/Kamel | Resolve active component registry, mapper, decoder/fetcher/client, cache paths and ownership | Custom/default providers are alternatives. DiskCache conflict requires shared actual backing directory plus overlapping independent active caches, including cross-process shared storage. |
| Coroutine/framework tests | Selected engine/runner signatures and discovery; actual scheduler/Job/assertion boundaries; versioned Mockito matcher state | Structured `runBlocking`, supported Jupiter suspend methods, Kotest contexts, custom harnesses and explicit joins are valid; no blanket `runTest` mandate. |
| MockK/Mockito lifecycle | Versioned spy/relaxed-answer advisories; explicitly selected test isolation policies and effective extension cleanup | No universal missing-close/unmock diagnosis. MockKExtension cleanup settings and thread/global registries matter; documented issue 554 is an advisory, not guaranteed failure. |
| Turbine/Roborazzi | Optional adapters for actual upstream runtime assertions/verification reports, plus exact Turbine context guard and selected CI verification policy | Resolve context propagation, scope ownership and oracle/environment. Do not fabricate dynamic event counts or UI correctness from static source. |
| Slack `DoNotMock` | Adapt the real lint issue with exact supported marker annotations and configured mock factories | Inspected defaults are Mockito-oriented. MockK/custom factories need explicit provider configuration/adaptation; Android/JVM UAST is not Native certification. |

Concrete contract/defect candidates include Flow emission/context and exception-transparency constraints, invalid continuation resumption, fixed descriptor index/structure contracts, duplicate transformed JSON names, reachable legacy Instant ABI incompatibility, actual Room lease expiry/forbidden connection upgrade, SQLDelight schema/transaction guards, Koin disallowed overrides/global duplicate startup, prohibited snapshot mutation/unfinished cache entry protocol, conflicting test schedulers, the context-bearing `runTest` marker guard, exact unsupported runner signatures, proven matcher count mismatch and the actual Turbine missing-registry guard. Each needs its record's preconditions; even a documented guard can be deliberately tested.

The cache protocol candidates are not generic absent-cleanup checks. They require a bounded proven owner, actual acquisition/use, no recognized transfer/completion/shutdown and the relevant active cache lifetime. Sharing a cache is valid. Separate FileSystem objects or processes do not by themselves establish separate backing storage. Room handle invalidity likewise requires the effective authorizing lease to expire, not just an inner syntactic block exit.

### Dependency and execution requirements

1. Build adapters consume resolved Gradle compilation/task/model information, including target variants, source-set inheritance and effective configuration. Never treat version-catalog declarations as resolved dependencies.
2. Semantic adapters require the matching compiler analysis/classpath and callable/overload identities. Provider compatibility and analysis completeness are independently reported; partial resolution causes abstention rather than silent all-target success.
3. Normalize compiler, Room/SQLDelight/Koin validator, runner discovery and runtime oracle results with provider id/version, target/task, source attribution and stable cause identity. Underlying Ktor/Kamel engine and generic/Turbine attached-child recognizers must not double-report the same cause.
4. Probe execution is explicit, bounded and isolated with fixtures, permitted dependencies and selected test tasks. Do not execute arbitrary application factories/tests merely to fill an opaque graph; avoid real network, production data or uncontrolled global-mock state in checker validation.

## Deliberate exclusions and gaps

This inventory is broad, not exhaustive. It does not claim every library-specific antipattern or generic DI/database/test architecture can be detected. The following are not universal default defects:

- GlobalScope, explicit dispatchers, NonCancellable lifetime separation, missing timeout, large buffers, discarded send results, image-loader reuse or original-size painters without an established policy/ownership obligation.
- Catching cancellation from `Deferred.await()` when the deferred is cancelled but the caller is still active; intentional boundaries checking caller `ensureActive()` remain valid.
- Lazy stream decoding after a `use` block without proving the close invalidates a subsequent required read: `ByteArrayInputStream.close()` is a no-op, and buffering may avoid a later read.
- A hot flow by name alone, wall-clock/calendar arithmetic, schema replacement/destructive migrations, response logging/retries, HTTP image caching or fixture sharing without application intent.
- Missing serializer/DI definition/engine inferred from dependency names or incomplete modules; custom serializers, scopes, converters, factories and default engine bundles can be valid.
- Runtime races, deadlocks, network failures, payload/data-dependent serializer failures, migration safety on arbitrary data or pixel-oracle success inferred from a static graph. Optional targeted probes can establish narrower evidence, not certify all executions.
- `every`/`verify` used where a suspend call is compiler-rejected: do not repackage Kotlin's compiler-owned suspend-call diagnostic as a new MockK defect. Similarly, unavailable target symbols and generated-query compiler errors are normalized once.
- Universal bans on mocking data/sealed/value classes or using spies. Only the explicit selected `DoNotMock` marker policy is included; MockK suspending spies/relaxed generics and Mockito eager spy stubbing preserve reviewed alternatives.
- Requiring a fixed Kotest property seed on every randomized test. Random exploration is valid; preserve failing seeds/counterexamples as regression evidence when using property tests rather than reduce coverage through a universal deterministic-seed policy.

Other libraries, unsupported provider tuples, opaque runtime registrations and target-specific features outside the documented predicates are gaps, not implicit passes. A targeted fixture and source review must precede adding support or widening a range.

## Proposed validation program

For every claimed rule/provider/version/target tuple, implement and execute **own ktChecker fixtures** for the six catalog cases: positive, negative, boundary, platform/version, suppression and regression. Upstream docs/examples/tests are evidence and reproduction inputs, not ktChecker integration tests.

- Include complete resolved classpaths, common source-set consumption and real target compilations; test supported current tuples and deliberately unsupported/mixed tuples. Do not infer Native support from successful JVM lint.
- Adversarial controls include same-name unrelated APIs/import aliases, overloads, unreachable or optional code, cached/intercepted image paths, custom component/module factories, nested valid leases, ownership transfers, built-in cleanup helpers and deliberate failing-guard tests.
- Test exact context identity/propagation, scheduler sharing, Main replacement ownership, child-vs-caller cancellation, structured failure propagation, foreground/backgroundScope distinctions and Turbine member-vs-top-level overloads.
- Mockito fixtures need recorded matcher state, combinators, Kotlin helpers, vararg versions, emitted null checks and mock-maker/interception modes. MockK fixtures need effective extensions/properties, scoped mocks, thread switches and serialized-vs-overlapping registry access.
- Run serializer/date/schema/migration property or boundary probes only where selected and meaningful; isolate format versions, time zones, clocks, dialects and migration data. Preserve failing generated inputs/seeds and establish deterministic reproduction separately from random exploration.
- Runtime adapters require an actual attributable report, exact runner/target and oracle configuration, expected-failure controls and stale-report rejection. Screenshot fixtures pin baseline, renderer/OS, fonts, density, locale, clock/data and tolerance; record/compare/verify/verifyAndRecord modes are distinct.
- Suppression fixtures prove narrow rule/provider/source/task scope, while independent unsuppressed violations still appear. Deduplication fixtures prove that compiler/provider and library-specific recognizers retain provenance but report one underlying cause.
- High-contract rules require reviewed predicates and valid-alternative controls. Medium heuristics additionally require measured false-positive/false-negative behavior on independent representative/adversarial corpora, with denominators and unsupported cases disclosed. Only documented validation evidence can promote `planned` to implemented/tested/validated.

Independent catalog review tightened cancellation, Mutex, JSON naming, stream closure, legacy ABI reachability, nested Room leases and cross-process cache backing-storage controls. Review of catalog wording is **not detector validation** or a release-qualification certificate.

## Licensing and maintenance

Observed upstream repository licenses are below; this is not a complete transitive artifact or legal audit. No upstream detector implementation was vendored for this research. Future reuse/adaptation must retain required licenses/notices and review copied source/tests independently. Android documentation has separate [content/sample terms](https://developer.android.com/license).

| Upstream | Observed license evidence |
| --- | --- |
| Coroutines | [Apache-2.0](https://github.com/Kotlin/kotlinx.coroutines/blob/1.11.0/LICENSE.txt) |
| Serialization | [Apache-2.0](https://github.com/Kotlin/kotlinx.serialization/blob/v1.12.0/LICENSE.txt) |
| Datetime | [Apache-2.0](https://github.com/Kotlin/kotlinx-datetime/blob/v0.8.0/LICENSE.txt) |
| Ktor | [Apache-2.0](https://github.com/ktorio/ktor/blob/3.6.1/LICENSE) |
| Room/AndroidX | Confirm pinned artifact/source notices before reuse; documentation licensing is separate from generated/runtime code |
| SQLDelight | [Apache-2.0](https://github.com/sqldelight/sqldelight/blob/2.4.1/LICENSE.txt) |
| Koin | [Apache-2.0](https://github.com/InsertKoinIO/koin/blob/4.2.2/LICENSE) |
| Coil | [Apache-2.0](https://github.com/coil-kt/coil/blob/3.6.3/LICENSE.txt) |
| Kamel | [Apache-2.0](https://github.com/Kamel-Media/Kamel/blob/v1.0.9/LICENSE) |
| Kotest | [Apache-2.0](https://github.com/kotest/kotest/blob/v6.2.6/LICENSE) |
| MockK | [Apache-2.0](https://github.com/mockk/mockk/blob/v1.14.11/LICENSE) |
| Mockito | [MIT](https://github.com/mockito/mockito/blob/v5.24.0/LICENSE) |
| Mockito Kotlin | [MIT](https://github.com/mockito/mockito-kotlin/blob/v6.4.0/LICENSE) |
| Turbine | [Apache-2.0](https://github.com/cashapp/turbine/blob/1.2.1/LICENSE.txt) |
| Roborazzi | [Apache-2.0](https://github.com/takahirom/roborazzi/blob/1.77.0/LICENSE) |
| JUnit | [EPL-2.0](https://github.com/junit-team/junit-framework/blob/r6.1.3/LICENSE.md) |
| Detekt | [Apache-2.0](https://github.com/detekt/detekt/blob/v1.23.8/LICENSE) |
| Slack lint | [Apache-2.0](https://github.com/slackhq/slack-lints/blob/main/LICENSE.txt); inspect the pinned adapter version's notices |

Use upstream tagged sources and release notes to maintain per-provider tuple manifests and test matrices. Track artifact/API, engine, compiler-plugin and provider changes rather than broad name-based version ranges. Stable Detekt and current alpha docs are separate; issue-based advisories need a reproducible versioned fixture before claiming an issue fixed or universally present. Disable/abstain on unsupported changes until fixtures and evidence are updated.

## Primary source bibliography

The JSON records link the evidence for each predicate; these are the main entry points retrieved on 2026-11-04 UTC:

- **Coroutines:** [tagged cancellation guide](https://github.com/Kotlin/kotlinx.coroutines/blob/1.11.0/docs/topics/cancellation-and-timeouts.md), [tagged Flow guide](https://github.com/Kotlin/kotlinx.coroutines/blob/1.11.0/docs/topics/flow.md), [core API](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/), [test source](https://github.com/Kotlin/kotlinx.coroutines/tree/1.11.0/kotlinx-coroutines-test/common/src), [Detekt stable rules](https://detekt.dev/docs/1.23.8/rules/coroutines/).
- **Serialization:** tagged [Json](https://github.com/Kotlin/kotlinx.serialization/blob/v1.12.0/docs/json.md), [serializers](https://github.com/Kotlin/kotlinx.serialization/blob/v1.12.0/docs/serializers.md), [polymorphism](https://github.com/Kotlin/kotlinx.serialization/blob/v1.12.0/docs/polymorphism.md), and [1.9 release notes](https://github.com/Kotlin/kotlinx.serialization/releases/tag/v1.9.0).
- **Datetime:** [0.8 README](https://github.com/Kotlin/kotlinx-datetime/blob/v0.8.0/README.md), [changelog](https://github.com/Kotlin/kotlinx-datetime/blob/v0.8.0/CHANGELOG.md) and per-operation API URLs in the records.
- **Ktor:** current [engines](https://ktor.io/docs/client-engines.html), [timeouts](https://ktor.io/docs/client-timeout.html), [retry](https://ktor.io/docs/client-request-retry.html), [serialization](https://ktor.io/docs/client-serialization.html), [responses](https://ktor.io/docs/client-responses.html) and [tagged client source](https://github.com/ktorio/ktor/blob/3.6.1/ktor-client/ktor-client-core/common/src/io/ktor/client/HttpClient.kt).
- **Room:** [KMP guide](https://developer.android.com/kotlin/multiplatform/room), [Room 2 API](https://developer.android.com/reference/kotlin/androidx/room/package-summary), [warnings](https://developer.android.com/reference/androidx/room/RoomWarnings), [migrations](https://developer.android.com/training/data-storage/room/migrating-db-versions) and the separate release pages above.
- **SQLDelight:** [2.1 rendered transactions](https://sqldelight.github.io/sqldelight/2.1.0/common/transactions/), [migrations](https://sqldelight.github.io/sqldelight/2.1.0/common/migrations/), [coroutines](https://sqldelight.github.io/sqldelight/2.1.0/common/coroutines/) and tagged [2.4.1 runtime](https://github.com/sqldelight/sqldelight/tree/2.4.1/runtime/src/commonMain/kotlin/app/cash/sqldelight).
- **Koin:** [modules](https://insert-koin.io/docs/reference/koin-core/modules/), [scopes](https://insert-koin.io/docs/reference/koin-core/scopes/), [verify](https://insert-koin.io/docs/reference/koin-test/verify/) and [tagged core source](https://github.com/InsertKoinIO/koin/tree/4.2.2/projects/core/koin-core).
- **Coil:** [network](https://coil-kt.github.io/coil/network/), [loaders](https://coil-kt.github.io/coil/image_loaders/), [Compose](https://coil-kt.github.io/coil/compose/), [tagged DiskCache contract](https://github.com/coil-kt/coil/blob/3.6.3/coil-core/src/commonMain/kotlin/coil3/disk/DiskCache.kt) and adapter build-source links in the records.
- **Kamel:** [tagged README](https://github.com/Kamel-Media/Kamel/blob/v1.0.9/README.md), [configuration builder](https://github.com/Kamel-Media/Kamel/blob/v1.0.9/kamel-core/src/commonMain/kotlin/io/kamel/core/config/KamelConfigBuilder.kt), [component lookup](https://github.com/Kamel-Media/Kamel/blob/v1.0.9/kamel-core/src/commonMain/kotlin/io/kamel/core/utils/ConfigUtils.kt).
- **Tests:** [JUnit 6 test/suspend method contract](https://docs.junit.org/6.1.3/writing-tests/test-classes-and-methods.html), [build discovery](https://docs.junit.org/6.1.3/running-tests/build-support.html), [JUnit 4 runner](https://github.com/junit-team/junit4/blob/r4.13.2/src/main/java/org/junit/runners/BlockJUnit4ClassRunner.java), [Gradle Test options](https://docs.gradle.org/current/dsl/org.gradle.api.tasks.testing.Test.html), [Kotlin assert](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/assert.html).
- **Mockito/MockK:** [Mockito matcher source](https://github.com/mockito/mockito/blob/v5.24.0/mockito-core/src/main/java/org/mockito/ArgumentMatchers.java), [matcher binder](https://github.com/mockito/mockito/blob/v5.24.0/mockito-core/src/main/java/org/mockito/internal/invocation/MatchersBinder.java), [scoped static mock](https://github.com/mockito/mockito/blob/v5.24.0/mockito-core/src/main/java/org/mockito/MockedStatic.java), [Kotlin helpers](https://github.com/mockito/mockito-kotlin/blob/v6.4.0/mockito-kotlin/src/main/kotlin/org/mockito/kotlin/Matchers.kt), [MockK tagged README](https://github.com/mockk/mockk/blob/v1.14.11/README.md), [issue 554](https://github.com/mockk/mockk/issues/554).
- **Kotest/Turbine/Roborazzi:** [Kotest setup](https://kotest.io/docs/framework/project-setup.html), [isolation](https://kotest.io/docs/framework/isolation-mode.html), [current concurrency](https://kotest.io/docs/framework/concurrency6.html), [property configuration](https://kotest.io/docs/proptest/property-test-config.html), [Turbine README](https://github.com/cashapp/turbine/blob/1.2.1/README.md), [Turbine guarded overloads](https://github.com/cashapp/turbine/blob/1.2.1/src/commonMain/kotlin/app/cash/turbine/flow.kt), [Roborazzi tagged README](https://github.com/takahirom/roborazzi/blob/1.77.0/README.md).
- **Explicit mock policy:** inspected [Slack DoNotMock detector](https://github.com/slackhq/slack-lints/blob/c0e92cb74b103073b5dcad02f8c48c4a1ba21c6d/slack-lint-checks/src/main/java/slack/lint/mocking/DoNotMockMockDetector.kt) and [base factory/configuration source](https://github.com/slackhq/slack-lints/blob/c0e92cb74b103073b5dcad02f8c48c4a1ba21c6d/slack-lint-checks/src/main/java/slack/lint/mocking/MockDetector.kt).

Some initial unversioned guide fetches returned empty/redirect content; pinned coroutines guides replaced them. Old Kotest/JUnit routes were replaced with current documented routes. SQLDelight's rendered-guide gap is disclosed above. A successful HTTP retrieval alone is not normative evidence; relevant contracts were read, but every future implementation still needs exact artifact/version review.

## Verification of this research artifact

Verification is limited to catalog structure and consistency: unique ids, required nonempty fields, allowed enums/platforms, six nonempty fixture plans per record, all statuses planned and matching inventory counts. Source retrieval and independent wording review are not checker tests. No detector behavior, target support, compatibility tuple or release qualification is claimed as verified.

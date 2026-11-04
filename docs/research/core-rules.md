# Core Kotlin rule research

Research date: **2026-11-04 UTC**, matching the supplied environment. [rules-core.json](../../data/rules-core.json) is the machine-readable planning source of truth. **All 797 records are `planned`; no ktChecker implementation, compiler/rule test, integration test, natural-alert precision review or performance benchmark has run.** Source reading and metadata checks are not tested or validated support. The `implementation` field describes a proposed reuse strategy (`existing` upstream, `adapted`, or `custom`), not a shipped ktChecker implementation.

Launch scope is **Android, iOS and Desktop/JVM**. `desktop` means JVM desktop only; it never means Linux/Windows/macOS Native execution. `common` describes shared source evaluated in its actual target compilation graph, and `jvm` identifies JVM applicability. Native-specific records apply only to enabled iOS targets. JS, Wasm and Native desktop are outside launch scope. Checker host operating systems and Kotlin execution targets are separate facts.

## Final inventory and selection contract

| Owned planning inventory | Records | Meaning |
|---|---:|---|
| Detekt 2.0.0-alpha.6 | 223 | Eight core catalogs 218, optional libraries 3 and excluded ruleauthors 2; exact registration IDs, not catalog headings. |
| Ktlint 1.8.0 | 102 | Standard provider 101 plus excluded internal suppression infrastructure 1. |
| Kotlin 2.4.21 compiler | 443 | FIR common 292, FIR JVM 99, FIR Native/iOS 43 and target IR actualization 9; exact emitted-ID adapter candidates. |
| Declared-contract/provider additions | 29 | Architecture 4, resources 3, security 8, Native 7, kotlin.test/testing 5 and exact Gitleaks predicates 2. Four Native rows are excluded aliases of platform owners. |
| **Total records** | **797** | **777 non-excluded candidates and 20 excluded disposition records**, not 797 implemented or independently measured checks. |

Activation counts are 334 `enabled_when_validated`, 122 `advisory`, 321 `opt_in` and 20 `excluded`. Most proposed defaults are already compiler-owned compilation contracts under the project's actual settings, not new behavioral defect detectors. Non-excluded candidates may be provider alternatives or scoped extensions; they are not permission to emit overlapping findings twice. Individual fixture/suppression/configuration/target/representative-project gates still apply to every candidate.

`enabled_when_validated` is an intended default only after its own gates. `advisory` requires evidence and intent review, not a correctness verdict. `opt_in` requires an owner-selected repository contract. Exclusions, same-owner aliases and unmapped provider/compiler IDs add no coverage. Unknown prerequisites, failed/missing targets, incomplete classpaths and parser/transport failures must remain visible as unknown/unsupported/failure, never a clean review.

The final owned-file metadata checks verified JSON parsing, required fields/enums, stable unique IDs, all-planned status, six nonempty validation-plan fields per row, and one-to-one correspondence between 443 mapped compiler ledger entries and 443 catalog IDs. These are **metadata checks only**. Every positive/negative is an unrun example or proposed scenario; 102 records still explicitly require additional boundary-fixture design and 2 require concrete regression selection. A filled plan field is not a passed test.

## Provider evidence and feasibility

| Provider | Primary-source facts | Proposed use and unresolved gates |
|---|---|---|
| Kotlin compiler | Official release table lists **2.4.21**, released **2026-10-08**. Pinned common/JVM/Native FIR factories/messages, target IR actualization and tagged diagnostic fixtures were read. | Consume actual per-task diagnostic IDs, severity, locations, phase, source snapshot and effective settings. Do not recreate type checking or force flags to obtain planned coverage. These four containers are not the whole compiler/plugin/backend universe. |
| Detekt | **2.0.0-alpha.6**, prerelease published **2026-08-04**. Compatibility table: Kotlin 2.4.10, Gradle 9.6.1, AGP 9.3.1, Java target 1.8, highest tested JDK 25. Apache-2.0. | Individually addressable Kotlin predicates with isolated workers. An alpha built against 2.4.10 is not proven compatible with project 2.4.21. Worker isolation does not prove parser/compiler/API compatibility or iOS semantics. |
| Detekt 1.x | Latest stable release endpoint still returns **v1.23.8**, published **2025-02-21**; versioned documentation says no longer actively maintained. Table: Kotlin 2.0.21, Gradle 8.12.1, AGP 8.8.1, highest tested JDK 21. | Legacy research only. K1 BindingContext and 2.x Analysis API adapters are distinct; exact rename/removal/configuration maps are required, not package-name substitution. |
| Ktlint | **1.8.0**, published **2025-11-14**. Pinned catalog uses Kotlin/compiler 2.2.21, compilation JDK 24 and Java target 8. MIT. | Optional configured formatting/naming profile, without review-mode rewriting. Kotlin 2.4 parsing, rule dependencies and .editorconfig semantics must be independently validated. |
| Kotlin Analysis API | Official API/migration docs and Detekt 2.x `RequiresAnalysisApi`/`analyze` sources were read. | Semantic backend candidate, not validated ktChecker support. Do not retain lifetime-bound KaSession/KaLifetimeOwner objects; copy facts or appropriate symbol pointers. Native/version/cache correctness need target-specific integration evidence. |
| Konsist | Primary release API lists **v0.18.1**, published **2026-10-10**; architecture/code documentation and pinned license were read. **Apache-2.0**, not MIT. | A Kotlin-source architecture assertion DSL/test provider, not a shipped canonical rule-ID registry or proof of whole-project dependency semantics. Four explicit architecture policies below are custom candidates; none counts a Konsist family as an implementation. Parser/source-set/build-graph fidelity, newer syntax and JVM-host packaging remain untested. |
| Gitleaks | Primary release/config/license fetched for **v8.30.1**, published **2026-03-21**. Pinned configuration has 222 distinct rule IDs; MIT, Copyright 2019 Zachary Rice. | Only exact `private-key` and `generic-api-key` predicates are cataloged as advisory local/redacted candidates. Other 220 IDs remain unmapped, not hidden behind an aggregate secrets rule. Binary/config/allowlist/regex/encoding/history scope and checker-host packaging require validation. No real-secret or remote credential-validity checks are authorized. |

Detekt's current type-resolution documentation explicitly limits **full KMP analysis tasks to JVM and Android compilations**, including launch Desktop/JVM. Generic `detekt` and per-source-set tasks are light tasks without type resolution; Native uses those light tasks. Common source inspected through JVM/Android does not prove direct iOS/Native semantic coverage. The registry's platform list is conditional applicability, not shipped backend support.

Costs are estimates, not measurements: PSI scans generally `fast`, resolved facts `medium`, graph/build/runtime work generally `slow`. Share resolution only within compatible module/source-set/target/settings contexts. Cache keys need source contents, classpaths, Kotlin/language/API/plugin versions, target, generated sources, rule configuration and suppressions. Lexical rejection filters may avoid analysis; they cannot establish semantic matches.

## Exact upstream ownership and false-positive boundaries

The eight Detekt core catalogs reconcile 218 registrations: comments 10, complexity 15, empty blocks 15, exceptions 15, naming 21, performance 8, potential bugs 39 and style 95. Optional libraries adds 3; ruleauthors adds 2 excluded specialist practices. Coroutines belong to the separate library inventory. Every owned registration has an exact `upstream_id`, such as `style:VarCouldBeVal`. All 101 Ktlint standard-provider registrations have exact `standard:*` IDs. `internal:ktlint-suppression` is execution infrastructure despite the documentation's displayed standard alias.

Sixteen upstream records are excluded:10 Detekt formatting alternatives owned by configured Ktlint equivalents; Detekt Deprecation owned by compiler DEPRECATION; 2 specialized ruleauthors rules; 2 Ktlint deprecated no-ops (`condition-wrapping`, `discouraged-comment-location`); and internal suppression infrastructure. `MandatoryBracesLoops` is **not** an exact alias of Ktlint multiline-loop: its configured coverage delta must be verified. Naming similarities are not sufficient for alias merging.

Use compiler ownership for genuine emitted type/nullability/exhaustiveness/expect-actual/deprecation/API-mode contracts. Retain one owner per equivalent alert, with verified alias/configuration/suppression migration. Unmapped IDs remain raw compiler/task evidence; importing a mapped ID never makes the whole build clean. Provider initialization must compare the **actual loaded exact-ID manifest** with include/exclude/alias dispositions; no wildcard, category or family counts as supported rules. New IDs cannot silently become enabled.

The high-certainty default lane is restricted to established resolved contracts, not a deterministic local pattern. Annotation-based IgnoredReturnValue is an **adapted** candidate: pinned Detekt defaults use CheckResult/CheckReturnValue/CanIgnoreReturnValue globs plus function/Sequence/Flow/Stream return-type heuristics. A contractual profile must resolve a documented exact annotation-FQN allowlist and disable type-only heuristics; same-simple-name annotations and unannotated side-effecting factories are legitimate controls. Detekt does not configure Kotlin's must-use/ignorable annotations equivalently by default. Prefer the actual compiler return-value checker, without enabling it on the user's behalf; reconcile Android CheckResult with Android Lint ownership.

CharArray.toString, error(Throwable), equals/hashCode pairs and empty/invalid ranges remain intent-dependent advisory candidates. Identity/debug rendering, intentional new exception boundaries, super.equals with inherited compatible hashCode, and empty sentinels are legitimate. Redundant null operators and EqualsNullCall are selected style, not universal defects; Java defensive boundaries and observable equals/getter behavior preclude blanket removal. DeprecatedBlockTag is a documentation policy. Its upstream standalone `@ReplaceWith` example is invalid; the plan uses a corrected `@Deprecated(..., replaceWith = ReplaceWith(...))` fixture, explicitly uncompiled.

UnreachableCode, UnsafeCast, UnreachableCatchBlock, UnusedUnaryOperator and UselessPostfixExpression are **intent-dependent advisory** candidates, not high-correctness defaults. Deliberate unreachable/cast-failure/operator regression tests, safe casts intentionally producing null and legitimate redundant catches can retain upstream warnings. PSI-only UselessPostfixExpression does not establish effects/liveness: `try { return i++ } finally { observe(i) }`, captured aliases and side-effectful custom inc/dec can require the update. No mandatory source rewrite is proposed. True no-finding counterparts and legitimate intent controls that still emit are different validation cases.

MissingUseCall is not a universal ownership proof. Returned/transferred resources, borrowed owners and known cleanup helpers may be correct. Missing iterator exceptions, mutable-property re-reads, nullable.toString, map !!, singleton throwables, TODO stubs, terminal process calls, default locale and reflective private usage require bounded contract/scope facts or remain advisory/policy. Syntax-only synthetic EmptyCodeSpec snippets use `compile=false`; empty Boolean when and other noncompilable seeds must not be represented as current Kotlin conformance.

Performance rules report operations or benchmark opportunities, not guaranteed speedups. Sequence conversion, spread copies, boxing, wrappers and inlining depend on active compiler/backend/collection sizes and evaluation order. Initializers, boolean simplification and primitive-array replacement must preserve effects, nullable/generic API behavior and nonlocal returns. `severity`, `kind` and prospective `certainty` are independent; even high-certainty policy is policy, and precision is unmeasured.

## Declared-contract additions and cross-catalog scope

The 29 additional rows have specific positive/legitimate-negative/boundary/settings/suppression/regression plans, all unrun. Architecture does **not** impose MVVM, Clean Architecture, package layouts or a universal dependency direction. The four checks require declared allowed dependency edges, exported API restrictions, forbidden cycle scope and source-set ownership; aliases, generated code, public API and actual build dependencies must be resolved. Konsist can inform tests but cannot replace those complete graph facts.

The three resource candidates distinguish proven owner-exit release obligations, actual access after terminal close and forbidden closure of a declared borrowed resource. Ownership/transfer/reopen/cleanup models are mandatory. Security checks require declared sensitive values, security-purpose randomness/algorithm contracts, same-key nonce facts or real trust-boundary/validation/sink models. Random use, old cryptographic names, logging, Runtime.exec argv and path normalization alone do not prove exploits; shell interpretation, symlinks, trust sources and actual serialization filters matter. Unknown flow/ownership abstains. JVM assertion security/testing policies inspect the **effective compiler -Xassertions strategy as well as -ea/-da/Gradle flags**; -da alone does not prove assertions are disabled, and ordinary Gradle/JUnit tests often enable them.

Native launch additions cover nativeHeap ownership/free and StableRef ownership as narrowly modeled iOS candidates. A local StableRef scope is not its callback owner lifetime, and disposing immediately can break asynchronous callbacks. The following four core records are **excluded same-owner aliases**, not extra checks:

| Excluded core planning ID | Canonical platform owner |
|---|---|
| `ktc.native.scoped_pointer_escape` | `ktc.native.memscoped_pointer_escape` |
| `ktc.native.pinned_pointer_escape` | `ktc.native.pinned_pointer_lifetime` |
| `ktc.native.stable_ref_after_dispose` | `ktc.native.stable_ref_use_after_dispose` |
| `ktc.native.swift_exception_boundary_policy` | `ktc.native.objc_unbridged_explicit_exception` |

Scoped pointer safety needs actual post-scope access to the **same freed allocation**, not address transport or C retention alone. Disposed StableRef safety needs actual get/unwrap-and-get of the **same handle**, not use of an earlier copied Kotlin object or raw pointer transport. `usePinned` escape/unpin alone does not prove invalidity under a nonmoving collector with a still-live array; actual invalidating storage/owner/runtime facts are required and the canonical candidate remains advisory. Swift/ObjC recoverability is an explicit exported-boundary/error policy with real @Throws/export/exception facts, not a requirement on every public Kotlin method. Legacy blanket freezing/thread-confinement rules are invalid under the current shared-heap memory model.

General testing candidates are deliberately narrow: an ordinary reachable assertion required to pass whose resolved assertFailsWith block is empty/Unit-only; identical stable primitive/String assertEquals operands as advice; and owner-selected constant-oracle, exception-type and always-on-JVM-assertion policies. A nested `assertFailsWith<AssertionError> { assertFailsWith<IllegalArgumentException> {} }` is a legitimate passing calibration control, as are intentional caught/expected failures. Unknown enclosing flow/calibration scope abstains. No blanket test-without-visible-assertions rule is proposed; smoke, snapshots, property tests, external verifiers and compiler-failure tests can be legitimate.

## Compiler reconciliation: exact factories, not family coverage

| Pinned container | Factory entries | Mapped planned adapters | Unmapped | Excluded task/meta contract | Same-owner alias |
|---|---:|---:|---:|---:|---:|
| FIR common |894|292|598|4|0|
| FIR JVM |111|99|11|1|0|
| FIR Native/iOS |46|43|3|0|0|
| IR actualization |10|9|0|0|1|
| **Total** |**1,061**|**443**|**612**|**5**|**1**|

The three FIR containers total 1,051 entries: common 179 direct WARNING, 677 direct ERROR and 38 feature-dependent factories; JVM 26 warnings, 79 errors and 6 feature-dependent; Native 3 warnings and 43 errors. IR adds 9 ERROR factories and 1 WARNING factory (ACTUAL_ANNOTATIONS_NOT_MATCH_EXPECT). That IR warning is a same-owner alias of its common FIR adapter, not another rule. This limited source reconciliation is **not** a complete compiler/plugin/backend diagnostic catalog.

Of 612 unmapped entries, 542 atomic factories are outside this bounded normalized-contract selection, 44 are feature-dependent base factories, 24 selected factories lack a suitable tagged fixture selection and 2 require manual renderer/contract reconciliation. Feature factories generate emitted `_WARNING`/`_ERROR` branches (verified in pinned KtDiagnosticFactory source); base names alone are not emitted IDs. No branch is counted until exact option/feature/emission mapping is validated. Five meta/nonlaunch exclusions remain visible compilation evidence. Every entry and its disposition is enumerated below, not hidden behind broad families.

Mapped records preserve source factory/message metadata and complete tagged upstream fixture paths/line markers/directives; they propose real emission transport at `depth=build`, with unmeasured `cost=slow`. Preserve actual severity and -Xwarning-level/-Werror/-nowarn/progressive/language/API/plugin settings; ktChecker must not impose -Wextra, explicit API or unused-return modes merely to generate findings. Errors still mean the real compilation contract failed; warnings can be advisory, style or owner-selected policy. Deliberate floating-point `1 / 0.0f` and `1 / 0.0` are tagged DIVISION_BY_ZERO warnings; intentional infinity/NaN and EMPTY_RANGE sentinels can therefore still emit legitimate advisory diagnostics. Negative plans require absence only for genuine compatible no-emission counterparts, not intent controls, and never invent compiler exemptions.

KMP requires each enabled target's actual source-set/dependency graph and phase. Common FIR success on JVM does not prove iOS actualization or downstream actual signatures/members/visibility/annotations. The 9 IR adapters preserve FIR2IR/backend phase and meaningful markers such as `NO_ACTUAL_FOR_EXPECT{JVM}`. Direct Java actualization is JVM/Android/Desktop-JVM only under the actual selected feature and annotation artifact. The IR renderer mentions `@kotlin.jvm.KotlinActual` while tagged Java fixtures use `@kotlin.annotations.jvm.KotlinActual`; do not promise guessed FQN equivalence.

Fixture evidence is explicitly limited:68 selected compiler rows reference sources with IGNORE_FIR_DIAGNOSTICS and are seed-only, not proof of current K2 execution. API_NOT_AVAILABLE, DEPRECATION_ERROR and TYPEALIAS_EXPANSION_DEPRECATION_ERROR select `.disabled.kt` files; their records explicitly require independently supported active fixtures, not disabled-source conformance claims. Full MODULE/FILE companion sources, target headers, phase and directives must be reconstructed before activation. No upstream suite was compiled or executed.

Two primary-source discrepancies remain recorded rather than silently resolved:

- Pinned 2.4.21 ReturnValue.kt declares `kotlin.MustUseReturnValues` for **FILE and CLASS only** (SinceKotlin 2.3); moving official checker documentation additionally says FUNCTION. `kotlin.IgnorableReturnValue` targets FUNCTION (SinceKotlin 2.2). Do not invent a compilable function-target must-use fixture. Checker default is **disable**; preserve actual disable/check/full mode.
- The official Kotlin 2.4 release table says **2026-06-03**, while its what's-new text says **2026-07-14**. This is a documentation disagreement, not a basis to change the pinned 2.4.21 release date or pretend validation of an unknown version.

### Complete pinned compiler factory ledger

Disposition keys: **M** = mapped planned exact adapter; **U** = unmapped atomic contract outside selection; **F** = unmapped feature-dependent base, emitted branches pending; **T** = selected but suitable tagged fixture mapping missing; **R** = manual renderer/contract reconciliation needed; **E** = excluded meta/nonlaunch rule, raw task evidence retained; **A** = same-owner alias. None means implemented/tested/validated. Source severities are declaration metadata only; effective emissions follow the actual compiler/settings. The JSON holds mapped per-ID messages, source links and proposed full fixture scenarios.

<details>
<summary>FIR common: 894 exact factory entries and dispositions</summary>

[Pinned factory source](https://raw.githubusercontent.com/JetBrains/kotlin/v2.4.21/compiler/fir/checkers/gen/org/jetbrains/kotlin/fir/analysis/diagnostics/FirErrors.kt). Line numbers below refer to that exact file.

| Exact factory name | Source line | Declared severity / feature | Disposition |
|---|---:|---|---|
| `UNSUPPORTED` |158|ERROR|U|
| `UNSUPPORTED_FEATURE` |159|ERROR|U|
| `UNSUPPORTED_SUSPEND_TEST` |160|ERROR|U|
| `NEW_INFERENCE_ERROR` |161|ERROR|U|
| `ESCAPING_CAPTURED_VARIABLE` |162|WARNING|U|
| `OTHER_ERROR` |165|ERROR|U|
| `OTHER_ERROR_WITH_REASON` |166|ERROR|U|
| `ILLEGAL_CONST_EXPRESSION` |169|ERROR|U|
| `ILLEGAL_UNDERSCORE` |170|ERROR|U|
| `EXPRESSION_EXPECTED` |171|ERROR|U|
| `ASSIGNMENT_IN_EXPRESSION_CONTEXT` |172|ERROR|U|
| `BREAK_OR_CONTINUE_OUTSIDE_A_LOOP` |173|ERROR|U|
| `NOT_A_LOOP_LABEL` |174|ERROR|U|
| `BREAK_OR_CONTINUE_JUMPS_ACROSS_FUNCTION_BOUNDARY` |175|ERROR|U|
| `VARIABLE_EXPECTED` |176|ERROR|U|
| `DELEGATION_IN_INTERFACE` |177|ERROR|U|
| `DELEGATION_NOT_TO_INTERFACE` |178|ERROR|U|
| `NESTED_CLASS_NOT_ALLOWED` |179|ERROR|U|
| `NESTED_CLASS_NOT_ALLOWED_IN_LOCAL` |180|feature: `ForbidCompanionInLocalInnerClass`|F|
| `INCORRECT_CHARACTER_LITERAL` |181|ERROR|U|
| `EMPTY_CHARACTER_LITERAL` |182|ERROR|U|
| `TOO_MANY_CHARACTERS_IN_CHARACTER_LITERAL` |183|ERROR|U|
| `ILLEGAL_ESCAPE` |184|ERROR|U|
| `INT_LITERAL_OUT_OF_RANGE` |185|ERROR|M: `ktc.compiler.common.int_literal_out_of_range`|
| `INT_LITERAL_WITH_LEADING_ZEROS` |186|ERROR|U|
| `FLOAT_LITERAL_OUT_OF_RANGE` |187|ERROR|T|
| `WRONG_LONG_SUFFIX` |188|ERROR|U|
| `UNSIGNED_LITERAL_WITHOUT_DECLARATIONS_ON_CLASSPATH` |189|ERROR|U|
| `DIVISION_BY_ZERO` |190|WARNING|M: `ktc.compiler.common.division_by_zero`|
| `TRIM_MARGIN_BLANK_PREFIX` |191|WARNING|M: `ktc.compiler.common.trim_margin_blank_prefix`|
| `VAL_OR_VAR_ON_LOOP_PARAMETER` |192|ERROR|U|
| `VAL_OR_VAR_ON_FUN_PARAMETER` |193|ERROR|U|
| `VAL_OR_VAR_ON_CATCH_PARAMETER` |194|ERROR|U|
| `VAL_OR_VAR_ON_SECONDARY_CONSTRUCTOR_PARAMETER` |195|ERROR|U|
| `INNER_ON_TOP_LEVEL_SCRIPT_CLASS` |196|feature: `ProhibitScriptTopLevelInnerClasses`|F|
| `ERROR_SUPPRESSION` |197|WARNING|U|
| `MISSING_CONSTRUCTOR_KEYWORD` |198|ERROR|U|
| `REDUNDANT_INTERPOLATION_PREFIX` |199|WARNING|U|
| `WRAPPED_LHS_IN_ASSIGNMENT` |200|feature: `ForbidParenthesizedLhsInAssignments`|F|
| `PARENTHESIZED_PACKAGE_QUALIFIER` |201|feature: `ForbidAnnotationsTypeArgumentsAndParenthesesForPackageQualifier`|F|
| `KOTLIN_PACKAGE_USAGE` |202|ERROR|U|
| `UNSUPPORTED_ARRAY_LITERAL_OUTSIDE_OF_ANNOTATION` |203|feature: `ForbidArrayLiteralsInNonAnnotationContexts`|F|
| `UNRESOLVED_REFERENCE` |206|ERROR|M: `ktc.compiler.common.unresolved_reference`|
| `UNRESOLVED_REFERENCE_WRONG_RECEIVER` |207|ERROR|M: `ktc.compiler.common.unresolved_reference_wrong_receiver`|
| `INACCESSIBLE_OUTER_CLASS_RECEIVER` |208|ERROR|U|
| `UNRESOLVED_IMPORT` |209|ERROR|M: `ktc.compiler.common.unresolved_import`|
| `INVISIBLE_REFERENCE` |210|ERROR|M: `ktc.compiler.common.invisible_reference`|
| `INVISIBLE_REFERENCE_WARNING` |211|WARNING|U|
| `INVISIBLE_SETTER` |212|ERROR|M: `ktc.compiler.common.invisible_setter`|
| `UNRESOLVED_LABEL` |213|ERROR|U|
| `AMBIGUOUS_LABEL` |214|ERROR|U|
| `LABEL_NAME_CLASH` |215|WARNING|U|
| `DESERIALIZATION_ERROR` |216|ERROR|U|
| `ERROR_FROM_JAVA_RESOLUTION` |217|ERROR|U|
| `MISSING_STDLIB_CLASS` |218|ERROR|U|
| `NO_THIS` |219|ERROR|U|
| `API_NOT_AVAILABLE` |220|ERROR|M: `ktc.compiler.common.api_not_available`|
| `PLACEHOLDER_PROJECTION_IN_QUALIFIER` |221|ERROR|U|
| `DUPLICATE_PARAMETER_NAME_IN_FUNCTION_TYPE` |222|ERROR|U|
| `MISSING_DEPENDENCY_CLASS` |223|ERROR|U|
| `MISSING_DEPENDENCY_CLASS_IN_EXPRESSION_TYPE` |224|WARNING|U|
| `MISSING_DEPENDENCY_SUPERCLASS` |225|ERROR|U|
| `MISSING_DEPENDENCY_SUPERCLASS_WARNING` |226|WARNING|U|
| `MISSING_DEPENDENCY_CLASS_IN_LAMBDA_PARAMETER` |227|WARNING|U|
| `MISSING_DEPENDENCY_CLASS_IN_LAMBDA_RECEIVER` |228|WARNING|U|
| `MISSING_DEPENDENCY_CLASS_IN_TYPEALIAS` |229|WARNING|U|
| `MISSING_DEPENDENCY_IN_INFERRED_TYPE_ANNOTATION` |230|feature: `ForbidImplicitTypeAnnotationWithMissingDependency`|F|
| `ROOT_IDE_PACKAGE_DEPRECATED` |231|WARNING|U|
| `CREATING_AN_INSTANCE_OF_ABSTRACT_CLASS` |234|ERROR|U|
| `NO_CONSTRUCTOR` |235|ERROR|U|
| `NO_IMPLICIT_DEFAULT_CONSTRUCTOR_ON_EXPECT_CLASS` |236|ERROR|U|
| `FUNCTION_CALL_EXPECTED` |237|ERROR|U|
| `ILLEGAL_SELECTOR` |238|ERROR|U|
| `NO_RECEIVER_ALLOWED` |239|ERROR|U|
| `FUNCTION_EXPECTED` |240|ERROR|U|
| `INTERFACE_AS_FUNCTION` |241|ERROR|U|
| `EXPECT_CLASS_AS_FUNCTION` |242|ERROR|U|
| `INNER_CLASS_CONSTRUCTOR_NO_RECEIVER` |243|ERROR|U|
| `PLUGIN_AMBIGUOUS_INTERCEPTED_SYMBOL` |244|ERROR|U|
| `RESOLUTION_TO_CLASSIFIER` |245|ERROR|U|
| `AMBIGUOUS_ALTERED_ASSIGN` |246|ERROR|U|
| `SELF_CALL_IN_NESTED_OBJECT_CONSTRUCTOR_ERROR` |247|ERROR|U|
| `AMBIGUOUS_COLLECTION_LITERAL` |248|ERROR|U|
| `UNRESOLVED_COLLECTION_LITERAL` |249|ERROR|U|
| `IMPLICIT_PROPERTY_TYPE_MAKES_BEHAVIOR_ORDER_DEPENDANT` |250|WARNING|U|
| `IMPLICIT_PROPERTY_TYPE_MAKES_BEHAVIOR_ORDER_DEPENDANT_ERROR` |251|ERROR|U|
| `SUPER_IS_NOT_AN_EXPRESSION` |254|ERROR|U|
| `SUPER_NOT_AVAILABLE` |255|ERROR|U|
| `ABSTRACT_SUPER_CALL` |256|ERROR|U|
| `INSTANCE_ACCESS_BEFORE_SUPER_CALL` |257|ERROR|U|
| `SUPER_CALL_WITH_DEFAULT_PARAMETERS` |258|ERROR|U|
| `NOT_A_SUPERTYPE` |261|ERROR|U|
| `TYPE_ARGUMENTS_REDUNDANT_IN_SUPER_QUALIFIER` |262|WARNING|U|
| `SUPERCLASS_NOT_ACCESSIBLE_FROM_INTERFACE` |263|ERROR|U|
| `SUPERTYPE_INITIALIZED_IN_INTERFACE` |264|ERROR|U|
| `INTERFACE_WITH_SUPERCLASS` |265|ERROR|U|
| `FINAL_SUPERTYPE` |266|ERROR|U|
| `CLASS_CANNOT_BE_EXTENDED_DIRECTLY` |267|ERROR|U|
| `SUPERTYPE_IS_EXTENSION_OR_CONTEXT_FUNCTION_TYPE` |268|ERROR|U|
| `SINGLETON_IN_SUPERTYPE` |269|ERROR|U|
| `NULLABLE_SUPERTYPE` |270|ERROR|U|
| `NULLABLE_SUPERTYPE_THROUGH_TYPEALIAS` |271|feature: `ProhibitNullableTypeThroughTypealias`|F|
| `MANY_CLASSES_IN_SUPERTYPE_LIST` |272|ERROR|U|
| `SUPERTYPE_APPEARS_TWICE` |273|ERROR|U|
| `CLASS_IN_SUPERTYPE_FOR_ENUM` |274|ERROR|U|
| `SEALED_SUPERTYPE` |275|ERROR|U|
| `SEALED_SUPERTYPE_IN_LOCAL_CLASS` |276|ERROR|M: `ktc.compiler.common.sealed_supertype_in_local_class`|
| `SEALED_INHERITOR_IN_DIFFERENT_PACKAGE` |277|ERROR|M: `ktc.compiler.common.sealed_inheritor_in_different_package`|
| `SEALED_INHERITOR_IN_DIFFERENT_MODULE` |278|ERROR|M: `ktc.compiler.common.sealed_inheritor_in_different_module`|
| `CLASS_INHERITS_JAVA_SEALED_CLASS` |279|ERROR|U|
| `UNSUPPORTED_SEALED_FUN_INTERFACE` |280|ERROR|U|
| `SUPERTYPE_NOT_A_CLASS_OR_INTERFACE` |281|ERROR|U|
| `UNSUPPORTED_INHERITANCE_FROM_JAVA_MEMBER_REFERENCING_KOTLIN_FUNCTION` |282|ERROR|U|
| `CYCLIC_INHERITANCE_HIERARCHY` |283|ERROR|M: `ktc.compiler.common.cyclic_inheritance_hierarchy`|
| `PROJECTION_IN_IMMEDIATE_ARGUMENT_TO_SUPERTYPE` |284|ERROR|U|
| `INCONSISTENT_TYPE_PARAMETER_VALUES` |285|ERROR|U|
| `INCONSISTENT_TYPE_PARAMETER_BOUNDS` |286|ERROR|U|
| `AMBIGUOUS_SUPER` |287|ERROR|U|
| `CONSTRUCTOR_IN_OBJECT` |290|ERROR|U|
| `CONSTRUCTOR_IN_INTERFACE` |291|ERROR|U|
| `NON_PRIVATE_CONSTRUCTOR_IN_ENUM` |292|ERROR|U|
| `NON_PRIVATE_OR_PROTECTED_CONSTRUCTOR_IN_SEALED` |293|ERROR|U|
| `CYCLIC_CONSTRUCTOR_DELEGATION_CALL` |294|ERROR|M: `ktc.compiler.common.cyclic_constructor_delegation_call`|
| `PRIMARY_CONSTRUCTOR_DELEGATION_CALL_EXPECTED` |295|ERROR|U|
| `PROTECTED_CONSTRUCTOR_NOT_IN_SUPER_CALL` |296|ERROR|U|
| `SUPERTYPE_NOT_INITIALIZED` |297|ERROR|M: `ktc.compiler.common.supertype_not_initialized`|
| `SUPERTYPE_INITIALIZED_WITHOUT_PRIMARY_CONSTRUCTOR` |298|ERROR|U|
| `DELEGATION_SUPER_CALL_IN_ENUM_CONSTRUCTOR` |299|ERROR|U|
| `EXPLICIT_DELEGATION_CALL_REQUIRED` |300|ERROR|U|
| `SEALED_CLASS_CONSTRUCTOR_CALL` |301|ERROR|U|
| `DATA_CLASS_CONSISTENT_COPY_AND_EXPOSED_COPY_ARE_INCOMPATIBLE_ANNOTATIONS` |302|ERROR|U|
| `DATA_CLASS_CONSISTENT_COPY_WRONG_ANNOTATION_TARGET` |303|ERROR|U|
| `DATA_CLASS_COPY_VISIBILITY_WILL_BE_CHANGED` |304|feature: `ErrorAboutDataClassCopyVisibilityChange`|F|
| `DATA_CLASS_INVISIBLE_COPY_USAGE` |305|feature: `ErrorAboutDataClassCopyVisibilityChange`|F|
| `DATA_CLASS_WITHOUT_PARAMETERS` |306|ERROR|U|
| `DATA_CLASS_VARARG_PARAMETER` |307|ERROR|U|
| `DATA_CLASS_NOT_PROPERTY_PARAMETER` |308|ERROR|U|
| `ANNOTATION_ARGUMENT_KCLASS_LITERAL_OF_TYPE_PARAMETER_ERROR` |311|ERROR|U|
| `ANNOTATION_ARGUMENT_MUST_BE_CONST` |312|ERROR|U|
| `ANNOTATION_ARGUMENT_MUST_BE_ENUM_CONST` |313|ERROR|U|
| `ANNOTATION_ARGUMENT_MUST_BE_KCLASS_LITERAL` |314|ERROR|U|
| `ANNOTATION_CLASS_MEMBER` |315|ERROR|U|
| `ANNOTATION_PARAMETER_DEFAULT_VALUE_MUST_BE_CONSTANT` |316|ERROR|U|
| `INVALID_TYPE_OF_ANNOTATION_MEMBER` |317|ERROR|U|
| `PROJECTION_IN_TYPE_OF_ANNOTATION_MEMBER` |318|feature: `ForbidProjectionsInAnnotationProperties`|F|
| `LOCAL_ANNOTATION_CLASS_ERROR` |319|ERROR|U|
| `MISSING_VAL_ON_ANNOTATION_PARAMETER` |320|ERROR|U|
| `NON_CONST_VAL_USED_IN_CONSTANT_EXPRESSION` |321|ERROR|U|
| `CYCLE_IN_ANNOTATION_PARAMETER_ERROR` |322|ERROR|U|
| `ANNOTATION_CLASS_CONSTRUCTOR_CALL` |323|ERROR|U|
| `ENUM_CLASS_CONSTRUCTOR_CALL` |324|ERROR|U|
| `NOT_AN_ANNOTATION_CLASS` |325|ERROR|U|
| `NULLABLE_TYPE_OF_ANNOTATION_MEMBER` |326|ERROR|U|
| `VAR_ANNOTATION_PARAMETER` |327|ERROR|U|
| `SUPERTYPES_FOR_ANNOTATION_CLASS` |328|ERROR|U|
| `ANNOTATION_USED_AS_ANNOTATION_ARGUMENT` |329|ERROR|U|
| `ANNOTATION_ON_ANNOTATION_ARGUMENT` |330|ERROR|U|
| `ILLEGAL_KOTLIN_VERSION_STRING_VALUE` |331|ERROR|U|
| `NEWER_VERSION_IN_SINCE_KOTLIN` |332|WARNING|U|
| `DEPRECATED_SINCE_KOTLIN_WITH_UNORDERED_VERSIONS` |333|ERROR|U|
| `DEPRECATED_SINCE_KOTLIN_WITHOUT_ARGUMENTS` |334|ERROR|U|
| `DEPRECATED_SINCE_KOTLIN_WITHOUT_DEPRECATED` |335|ERROR|U|
| `DEPRECATED_SINCE_KOTLIN_WITH_DEPRECATED_LEVEL` |336|ERROR|U|
| `DEPRECATED_SINCE_KOTLIN_OUTSIDE_KOTLIN_SUBPACKAGE` |337|ERROR|U|
| `KOTLIN_ACTUAL_ANNOTATION_HAS_NO_EFFECT_IN_KOTLIN` |338|ERROR|U|
| `DEPRECATION_ERROR` |339|ERROR|M: `ktc.compiler.common.deprecation_error`|
| `DEPRECATION` |340|WARNING|M: `ktc.compiler.common.deprecation`|
| `DEPRECATION_ERROR_MIGRATION_PERIOD_WARNING` |341|WARNING|U|
| `OVERRIDE_DEPRECATION` |342|WARNING|U|
| `EXTENDING_AN_ANNOTATION_CLASS` |343|feature: `ProhibitExtendingAnnotationClasses`|F|
| `TYPEALIAS_EXPANSION_DEPRECATION_ERROR` |344|ERROR|M: `ktc.compiler.common.typealias_expansion_deprecation_error`|
| `TYPEALIAS_EXPANSION_DEPRECATION` |345|WARNING|M: `ktc.compiler.common.typealias_expansion_deprecation`|
| `VERSION_REQUIREMENT_DEPRECATION_ERROR` |346|ERROR|T|
| `VERSION_REQUIREMENT_DEPRECATION` |347|WARNING|T|
| `REDUNDANT_ANNOTATION` |348|WARNING|U|
| `ANNOTATION_ON_SUPERCLASS_ERROR` |349|ERROR|U|
| `RESTRICTED_RETENTION_FOR_EXPRESSION_ANNOTATION_ERROR` |350|ERROR|U|
| `WRONG_ANNOTATION_TARGET` |351|ERROR|M: `ktc.compiler.common.wrong_annotation_target`|
| `WRONG_ANNOTATION_TARGET_WARNING` |352|WARNING|U|
| `WRONG_ANNOTATION_TARGET_WITH_USE_SITE_TARGET` |353|ERROR|M: `ktc.compiler.common.wrong_annotation_target_with_use_site_target`|
| `ANNOTATION_WITH_USE_SITE_TARGET_ON_EXPRESSION` |354|feature: `ForbidAnnotationsWithUseSiteTargetOnExpressions`|F|
| `INAPPLICABLE_TARGET_ON_PROPERTY` |355|ERROR|U|
| `INAPPLICABLE_TARGET_ON_PROPERTY_WARNING` |356|WARNING|U|
| `INAPPLICABLE_TARGET_PROPERTY_IMMUTABLE` |357|ERROR|U|
| `INAPPLICABLE_TARGET_PROPERTY_HAS_NO_DELEGATE` |358|ERROR|U|
| `INAPPLICABLE_TARGET_PROPERTY_HAS_NO_BACKING_FIELD` |359|ERROR|U|
| `INAPPLICABLE_PARAM_TARGET` |360|ERROR|U|
| `INAPPLICABLE_FILE_TARGET` |361|ERROR|U|
| `INAPPLICABLE_ALL_TARGET` |362|ERROR|M: `ktc.compiler.common.inapplicable_all_target`|
| `INAPPLICABLE_ALL_TARGET_IN_MULTI_ANNOTATION` |363|ERROR|U|
| `REPEATED_ANNOTATION` |364|ERROR|M: `ktc.compiler.common.repeated_annotation`|
| `REPEATED_ANNOTATION_WARNING` |365|WARNING|U|
| `REDUNDANT_ANNOTATION_TARGET` |366|WARNING|U|
| `NOT_A_CLASS` |367|ERROR|U|
| `WRONG_EXTENSION_FUNCTION_TYPE` |368|ERROR|U|
| `ANNOTATION_IN_WHERE_CLAUSE_ERROR` |369|ERROR|U|
| `ANNOTATION_IN_CONTRACT_ERROR` |370|ERROR|U|
| `AMBIGUOUS_ANNOTATION_ARGUMENT` |371|ERROR|U|
| `VOLATILE_ON_VALUE` |372|ERROR|U|
| `VOLATILE_ON_DELEGATE` |373|ERROR|U|
| `NON_INTERNAL_PUBLISHED_API` |374|ERROR|U|
| `NON_SOURCE_ANNOTATION_ON_INLINED_LAMBDA_EXPRESSION` |375|ERROR|U|
| `POTENTIALLY_NON_REPORTED_ANNOTATION` |376|WARNING|U|
| `ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD` |377|WARNING|U|
| `ANNOTATIONS_ON_BLOCK_LEVEL_EXPRESSION_ON_THE_SAME_LINE` |378|WARNING|U|
| `IGNORABILITY_ANNOTATIONS_WITH_CHECKER_DISABLED` |379|ERROR|U|
| `DSL_MARKER_PROPAGATES_TO_MANY` |380|WARNING|U|
| `DSL_MARKER_APPLIED_TO_WRONG_TARGET` |381|WARNING|U|
| `OPT_IN_USAGE` |384|WARNING|M: `ktc.compiler.common.opt_in_usage`|
| `OPT_IN_USAGE_ERROR` |385|ERROR|M: `ktc.compiler.common.opt_in_usage_error`|
| `OPT_IN_TO_INHERITANCE` |386|WARNING|M: `ktc.compiler.common.opt_in_to_inheritance`|
| `OPT_IN_TO_INHERITANCE_ERROR` |387|ERROR|M: `ktc.compiler.common.opt_in_to_inheritance_error`|
| `OPT_IN_OVERRIDE` |388|WARNING|M: `ktc.compiler.common.opt_in_override`|
| `OPT_IN_OVERRIDE_ERROR` |389|ERROR|M: `ktc.compiler.common.opt_in_override_error`|
| `OPT_IN_CAN_ONLY_BE_USED_AS_ANNOTATION` |390|ERROR|M: `ktc.compiler.common.opt_in_can_only_be_used_as_annotation`|
| `OPT_IN_MARKER_CAN_ONLY_BE_USED_AS_ANNOTATION_OR_ARGUMENT_IN_OPT_IN` |391|ERROR|M: `ktc.compiler.common.opt_in_marker_can_only_be_used_as_annotation_or_argument_in_opt_in`|
| `OPT_IN_WITHOUT_ARGUMENTS` |392|WARNING|M: `ktc.compiler.common.opt_in_without_arguments`|
| `OPT_IN_ARGUMENT_IS_NOT_MARKER` |393|WARNING|M: `ktc.compiler.common.opt_in_argument_is_not_marker`|
| `OPT_IN_MARKER_WITH_WRONG_TARGET` |394|ERROR|M: `ktc.compiler.common.opt_in_marker_with_wrong_target`|
| `OPT_IN_MARKER_WITH_WRONG_RETENTION` |395|ERROR|M: `ktc.compiler.common.opt_in_marker_with_wrong_retention`|
| `OPT_IN_MARKER_ON_WRONG_TARGET` |396|ERROR|M: `ktc.compiler.common.opt_in_marker_on_wrong_target`|
| `OPT_IN_MARKER_ON_OVERRIDE` |397|ERROR|T|
| `OPT_IN_MARKER_ON_OVERRIDE_WARNING` |398|WARNING|T|
| `SUBCLASS_OPT_IN_INAPPLICABLE` |399|ERROR|M: `ktc.compiler.common.subclass_opt_in_inapplicable`|
| `SUBCLASS_OPT_IN_ARGUMENT_IS_NOT_MARKER` |400|ERROR|M: `ktc.compiler.common.subclass_opt_in_argument_is_not_marker`|
| `EXPOSED_TYPEALIAS_EXPANDED_TYPE` |403|ERROR|M: `ktc.compiler.common.exposed_typealias_expanded_type`|
| `EXPOSED_FUNCTION_RETURN_TYPE` |404|ERROR|M: `ktc.compiler.common.exposed_function_return_type`|
| `EXPOSED_RECEIVER_TYPE` |405|ERROR|M: `ktc.compiler.common.exposed_receiver_type`|
| `EXPOSED_PROPERTY_TYPE` |406|ERROR|M: `ktc.compiler.common.exposed_property_type`|
| `EXPOSED_PROPERTY_TYPE_IN_CONSTRUCTOR_ERROR` |407|ERROR|M: `ktc.compiler.common.exposed_property_type_in_constructor_error`|
| `EXPOSED_PARAMETER_TYPE` |408|ERROR|M: `ktc.compiler.common.exposed_parameter_type`|
| `EXPOSED_SUPER_INTERFACE` |409|ERROR|M: `ktc.compiler.common.exposed_super_interface`|
| `EXPOSED_SUPER_CLASS` |410|ERROR|M: `ktc.compiler.common.exposed_super_class`|
| `EXPOSED_TYPE_PARAMETER_BOUND` |411|ERROR|M: `ktc.compiler.common.exposed_type_parameter_bound`|
| `EXPOSED_TYPE_PARAMETER_BOUND_DEPRECATION_WARNING` |412|WARNING|M: `ktc.compiler.common.exposed_type_parameter_bound_deprecation_warning`|
| `REPEATED_MODIFIER` |415|ERROR|U|
| `WRONG_MODIFIER_TARGET` |416|ERROR|U|
| `WRONG_MODIFIER_CONTAINING_DECLARATION` |417|ERROR|U|
| `DEPRECATED_MODIFIER` |418|ERROR|U|
| `DEPRECATED_MODIFIER_FOR_TARGET` |419|WARNING|U|
| `DEPRECATED_MODIFIER_CONTAINING_DECLARATION` |420|WARNING|U|
| `INCOMPATIBLE_MODIFIERS` |421|ERROR|U|
| `DEPRECATED_MODIFIER_PAIR` |422|WARNING|U|
| `REDUNDANT_MODIFIER` |423|WARNING|U|
| `REDUNDANT_MODIFIER_FOR_TARGET` |424|WARNING|U|
| `INFIX_MODIFIER_REQUIRED` |425|ERROR|U|
| `OPERATOR_MODIFIER_REQUIRED` |426|ERROR|U|
| `INAPPLICABLE_INFIX_MODIFIER` |427|ERROR|U|
| `INAPPLICABLE_OPERATOR_MODIFIER` |428|ERROR|U|
| `INAPPLICABLE_OPERATOR_MODIFIER_WARNING` |429|WARNING|U|
| `INAPPLICABLE_LATEINIT_MODIFIER` |430|ERROR|U|
| `POTENTIALLY_NULLABLE_RETURN_TYPE_OF_OPERATOR_OF` |431|ERROR|U|
| `NULLABLE_RETURN_TYPE_OF_OPERATOR_OF` |432|ERROR|U|
| `RETURN_TYPE_MISMATCH_OF_OPERATOR_OF` |433|ERROR|U|
| `NO_VARARG_OVERLOAD_OF_OPERATOR_OF` |434|ERROR|U|
| `MULTIPLE_VARARG_OVERLOADS_OF_OPERATOR_OF` |435|ERROR|U|
| `INCONSISTENT_RETURN_TYPES_IN_OF_OVERLOADS` |436|ERROR|U|
| `INCONSISTENT_PARAMETER_TYPES_IN_OF_OVERLOADS` |437|ERROR|U|
| `INCONSISTENT_VISIBILITY_IN_OF_OVERLOADS` |438|ERROR|U|
| `INCONSISTENT_SUSPEND_IN_OF_OVERLOADS` |439|ERROR|U|
| `OF_OVERLOADS_IN_BLOCK_AND_OBJECT` |440|ERROR|U|
| `INCONSISTENT_TYPE_PARAMETERS_IN_OF_OVERLOADS` |441|ERROR|U|
| `REDUNDANT_OPEN_IN_INTERFACE` |442|WARNING|U|
| `OPERATOR_CALL_ON_CONSTRUCTOR` |443|ERROR|U|
| `NO_EXPLICIT_VISIBILITY_IN_API_MODE` |444|ERROR|M: `ktc.compiler.common.no_explicit_visibility_in_api_mode`|
| `NO_EXPLICIT_VISIBILITY_IN_API_MODE_WARNING` |445|WARNING|T|
| `NO_EXPLICIT_RETURN_TYPE_IN_API_MODE` |446|ERROR|M: `ktc.compiler.common.no_explicit_return_type_in_api_mode`|
| `NO_EXPLICIT_RETURN_TYPE_IN_API_MODE_WARNING` |447|WARNING|T|
| `ANONYMOUS_SUSPEND_FUNCTION` |448|ERROR|U|
| `VALUE_CLASS_NOT_TOP_LEVEL` |451|ERROR|U|
| `VALUE_CLASS_NOT_FINAL` |452|ERROR|U|
| `VALUE_CLASS_OPEN` |453|ERROR|U|
| `ABSENCE_OF_PRIMARY_CONSTRUCTOR_FOR_VALUE_CLASS` |454|ERROR|U|
| `EXPECT_VALUE_CLASS_WITH_NO_PRIMARY_CONSTRUCTOR_HAS_SECONDARY` |455|ERROR|U|
| `INLINE_CLASS_CONSTRUCTOR_WRONG_PARAMETERS_SIZE` |456|ERROR|U|
| `VALUE_CLASS_EMPTY_CONSTRUCTOR` |457|ERROR|U|
| `VALUE_CLASS_CONSTRUCTOR_NOT_FINAL_READ_ONLY_PARAMETER` |458|ERROR|U|
| `ABSTRACT_VALUE_CLASS_CONSTRUCTOR_PROPERTY_PARAMETER` |459|ERROR|U|
| `SEALED_VALUE_CLASS_CONSTRUCTOR_PROPERTY_PARAMETER` |460|ERROR|U|
| `PROPERTY_WITH_BACKING_FIELD_INSIDE_VALUE_CLASS` |461|ERROR|U|
| `DELEGATED_PROPERTY_INSIDE_VALUE_CLASS` |462|ERROR|U|
| `VALUE_CLASS_HAS_INAPPLICABLE_PARAMETER_TYPE` |463|ERROR|U|
| `VALUE_CLASS_CANNOT_IMPLEMENT_INTERFACE_BY_DELEGATION` |464|ERROR|U|
| `VALUE_CLASS_CANNOT_EXTEND_CLASSES` |465|ERROR|U|
| `VALUE_CLASS_CANNOT_EXTEND_IDENTITY_CLASSES` |466|ERROR|U|
| `VALUE_CLASS_CANNOT_BE_RECURSIVE` |467|ERROR|U|
| `VALUE_CLASS_CANNOT_BE_RECURSIVE_VIA_TYPE_PARAMETERS` |468|feature: `ForbidValueClassRecursionViaTypeParameters`|F|
| `SECONDARY_CONSTRUCTOR_WITH_BODY_INSIDE_VALUE_CLASS` |469|ERROR|U|
| `RESERVED_MEMBER_INSIDE_VALUE_CLASS` |470|ERROR|U|
| `RESERVED_MEMBER_FROM_INTERFACE_INSIDE_VALUE_CLASS` |471|ERROR|U|
| `TYPE_ARGUMENT_ON_TYPED_VALUE_CLASS_EQUALS` |472|ERROR|U|
| `INNER_CLASS_INSIDE_VALUE_CLASS` |473|ERROR|U|
| `VALUE_CLASS_CANNOT_BE_CLONEABLE` |474|ERROR|U|
| `VALUE_CLASS_CANNOT_HAVE_CONTEXT_RECEIVERS` |475|ERROR|U|
| `NONE_APPLICABLE` |478|ERROR|M: `ktc.compiler.common.none_applicable`|
| `INAPPLICABLE_CANDIDATE` |479|ERROR|U|
| `HAS_NEXT_FUNCTION_NONE_APPLICABLE` |480|ERROR|U|
| `NEXT_NONE_APPLICABLE` |481|ERROR|U|
| `DELEGATE_SPECIAL_FUNCTION_NONE_APPLICABLE` |482|ERROR|U|
| `TYPE_INFERENCE_ONLY_INPUT_TYPES_ERROR` |483|ERROR|U|
| `MEMBER_PROJECTED_OUT` |484|ERROR|U|
| `NO_VALUE_FOR_PARAMETER` |485|ERROR|M: `ktc.compiler.common.no_value_for_parameter`|
| `TOO_MANY_ARGUMENTS` |486|ERROR|M: `ktc.compiler.common.too_many_arguments`|
| `NAMED_PARAMETER_NOT_FOUND` |487|ERROR|M: `ktc.compiler.common.named_parameter_not_found`|
| `NAME_FOR_AMBIGUOUS_PARAMETER` |488|ERROR|U|
| `ARGUMENT_PASSED_TWICE` |489|ERROR|M: `ktc.compiler.common.argument_passed_twice`|
| `NAMED_ARGUMENTS_NOT_ALLOWED` |490|ERROR|M: `ktc.compiler.common.named_arguments_not_allowed`|
| `MIXING_NAMED_AND_POSITIONAL_ARGUMENTS` |491|ERROR|U|
| `VARARG_OUTSIDE_PARENTHESES` |492|ERROR|U|
| `NON_VARARG_SPREAD` |493|ERROR|M: `ktc.compiler.common.non_vararg_spread`|
| `SPREAD_OF_NULLABLE` |494|ERROR|M: `ktc.compiler.common.spread_of_nullable`|
| `UNEXPECTED_TRAILING_LAMBDA_ON_A_NEW_LINE` |495|ERROR|U|
| `MANY_LAMBDA_EXPRESSION_ARGUMENTS` |496|ERROR|U|
| `ASSIGNING_SINGLE_ELEMENT_TO_VARARG_IN_NAMED_FORM_FUNCTION` |497|feature: `ProhibitAssigningSingleElementsToVarargsInNamedForm`|F|
| `ASSIGNING_SINGLE_ELEMENT_TO_VARARG_IN_NAMED_FORM_ANNOTATION` |498|feature: `ProhibitAssigningSingleElementsToVarargsInNamedForm`|F|
| `REDUNDANT_SPREAD_OPERATOR_IN_NAMED_FORM_IN_FUNCTION` |499|WARNING|U|
| `REDUNDANT_SPREAD_OPERATOR_IN_NAMED_FORM_IN_ANNOTATION` |500|WARNING|U|
| `ILLEGAL_TYPE_ARGUMENT_FOR_VARARG_PARAMETER_WARNING` |501|WARNING|U|
| `NESTED_CLASS_ACCESSED_VIA_INSTANCE_REFERENCE` |502|ERROR|U|
| `TYPE_MISMATCH` |505|ERROR|M: `ktc.compiler.common.type_mismatch`|
| `ARGUMENT_TYPE_MISMATCH` |506|ERROR|M: `ktc.compiler.common.argument_type_mismatch`|
| `RETURN_TYPE_MISMATCH` |507|ERROR|M: `ktc.compiler.common.return_type_mismatch`|
| `EXPECTED_PARAMETER_TYPE_MISMATCH` |508|ERROR|M: `ktc.compiler.common.expected_parameter_type_mismatch`|
| `INITIALIZER_TYPE_MISMATCH` |509|ERROR|M: `ktc.compiler.common.initializer_type_mismatch`|
| `FIELD_INITIALIZER_TYPE_MISMATCH` |510|ERROR|M: `ktc.compiler.common.field_initializer_type_mismatch`|
| `ASSIGNMENT_TYPE_MISMATCH` |511|ERROR|M: `ktc.compiler.common.assignment_type_mismatch`|
| `CONDITION_TYPE_MISMATCH` |512|ERROR|M: `ktc.compiler.common.condition_type_mismatch`|
| `THROWABLE_TYPE_MISMATCH` |513|ERROR|M: `ktc.compiler.common.throwable_type_mismatch`|
| `RESULT_TYPE_MISMATCH` |514|ERROR|M: `ktc.compiler.common.result_type_mismatch`|
| `COMPARE_TO_TYPE_MISMATCH` |515|ERROR|M: `ktc.compiler.common.compare_to_type_mismatch`|
| `HAS_NEXT_FUNCTION_TYPE_MISMATCH` |516|ERROR|M: `ktc.compiler.common.has_next_function_type_mismatch`|
| `COMPONENT_FUNCTION_RETURN_TYPE_MISMATCH` |517|ERROR|M: `ktc.compiler.common.component_function_return_type_mismatch`|
| `DELEGATE_SPECIAL_FUNCTION_RETURN_TYPE_MISMATCH` |518|ERROR|M: `ktc.compiler.common.delegate_special_function_return_type_mismatch`|
| `OVERLOAD_RESOLUTION_AMBIGUITY` |521|ERROR|M: `ktc.compiler.common.overload_resolution_ambiguity`|
| `ASSIGN_OPERATOR_AMBIGUITY` |522|ERROR|U|
| `ITERATOR_AMBIGUITY` |523|ERROR|M: `ktc.compiler.common.iterator_ambiguity`|
| `HAS_NEXT_FUNCTION_AMBIGUITY` |524|ERROR|U|
| `NEXT_AMBIGUITY` |525|ERROR|U|
| `COMPONENT_FUNCTION_AMBIGUITY` |526|ERROR|M: `ktc.compiler.common.component_function_ambiguity`|
| `DELEGATE_SPECIAL_FUNCTION_AMBIGUITY` |527|ERROR|M: `ktc.compiler.common.delegate_special_function_ambiguity`|
| `COMPILER_REQUIRED_ANNOTATION_AMBIGUITY` |528|ERROR|U|
| `AMBIGUOUS_FUNCTION_TYPE_KIND` |529|ERROR|U|
| `CONTEXT_SENSITIVE_RESOLUTION_AMBIGUITY` |530|ERROR|U|
| `NO_CONTEXT_ARGUMENT` |533|ERROR|M: `ktc.compiler.common.no_context_argument`|
| `AMBIGUOUS_CONTEXT_ARGUMENT` |534|ERROR|M: `ktc.compiler.common.ambiguous_context_argument`|
| `CONTEXTUAL_OVERLOAD_SHADOWED` |535|WARNING|M: `ktc.compiler.common.contextual_overload_shadowed`|
| `MULTIPLE_CONTEXT_LISTS` |536|ERROR|M: `ktc.compiler.common.multiple_context_lists`|
| `CONTEXT_PARAMETER_WITHOUT_NAME` |537|ERROR|M: `ktc.compiler.common.context_parameter_without_name`|
| `CONTEXT_PARAMETERS_WITH_BACKING_FIELD` |538|ERROR|M: `ktc.compiler.common.context_parameters_with_backing_field`|
| `CALLABLE_REFERENCE_TO_CONTEXTUAL_DECLARATION` |539|ERROR|M: `ktc.compiler.common.callable_reference_to_contextual_declaration`|
| `NAMED_CONTEXT_PARAMETER_IN_FUNCTION_TYPE` |540|ERROR|M: `ktc.compiler.common.named_context_parameter_in_function_type`|
| `CONTEXT_PARAMETER_WITH_DEFAULT` |541|ERROR|M: `ktc.compiler.common.context_parameter_with_default`|
| `UNSUPPORTED_CONTEXTUAL_DECLARATION_CALL` |542|ERROR|M: `ktc.compiler.common.unsupported_contextual_declaration_call`|
| `AMBIGUOUS_CALL_WITH_IMPLICIT_CONTEXT_RECEIVER` |543|ERROR|T|
| `CONTEXT_RECEIVERS_DEPRECATED` |544|ERROR|T|
| `CONTEXT_CLASS_OR_CONSTRUCTOR` |545|ERROR|T|
| `COROUTINE_CONTEXT_AS_CONTEXT_PARAMETER_IS_RESERVED` |546|ERROR|M: `ktc.compiler.common.coroutine_context_as_context_parameter_is_reserved`|
| `RECURSION_IN_IMPLICIT_TYPES` |549|ERROR|U|
| `INFERENCE_ERROR` |550|ERROR|U|
| `PROJECTION_ON_NON_CLASS_TYPE_ARGUMENT` |551|ERROR|U|
| `UPPER_BOUND_VIOLATED` |552|ERROR|M: `ktc.compiler.common.upper_bound_violated`|
| `UPPER_BOUND_VIOLATED_DEPRECATION_WARNING` |553|WARNING|U|
| `UPPER_BOUND_VIOLATED_IN_TYPE_OPERATOR_OR_PARAMETER_BOUNDS` |554|feature: `ForbidUpperBoundsViolationOnTypeOperatorAndParameterBounds`|F|
| `UPPER_BOUND_VIOLATED_IN_TYPEALIAS_EXPANSION` |555|ERROR|M: `ktc.compiler.common.upper_bound_violated_in_typealias_expansion`|
| `UPPER_BOUND_VIOLATED_IN_TYPEALIAS_EXPANSION_DEPRECATION_WARNING` |556|WARNING|U|
| `UPPER_BOUND_VIOLATED_IN_LHS_OF_CLASS_LITERAL_WARNING` |557|WARNING|U|
| `TYPE_ARGUMENTS_NOT_ALLOWED` |558|ERROR|U|
| `TYPE_ARGUMENTS_NOT_ALLOWED_WARNING` |559|WARNING|U|
| `TYPE_ARGUMENTS_NOT_ALLOWED_IN_PACKAGE_QUALIFIER_WARNING` |560|WARNING|U|
| `TYPE_ARGUMENTS_FOR_OUTER_CLASS_WHEN_NESTED_REFERENCED` |561|ERROR|U|
| `WRONG_NUMBER_OF_TYPE_ARGUMENTS` |562|ERROR|U|
| `WRONG_NUMBER_OF_TYPE_ARGUMENTS_WARNING` |563|WARNING|U|
| `WRONG_NUMBER_OF_TYPE_ARGUMENTS_IN_LOCAL_CLASS_IN_LHS_WARNING` |564|WARNING|U|
| `WRONG_NUMBER_OF_TYPE_ARGUMENTS_IN_GET_CLASS_WARNING` |565|WARNING|U|
| `INVALID_QUALIFIER_IN_LHS_OF_CALLABLE_REFERENCE_TO_STATIC` |566|feature: `ProhibitCallableReferencesToStaticsWithTypeArgumentsOrNullMarkInLhs`|F|
| `NO_TYPE_ARGUMENTS_ON_RHS` |567|ERROR|U|
| `OUTER_CLASS_ARGUMENTS_REQUIRED` |568|ERROR|U|
| `TYPE_PARAMETERS_IN_OBJECT` |569|ERROR|U|
| `TYPE_PARAMETERS_IN_ANONYMOUS_OBJECT` |570|ERROR|U|
| `ILLEGAL_PROJECTION_USAGE` |571|ERROR|U|
| `TYPE_PARAMETERS_IN_ENUM` |572|ERROR|U|
| `CONFLICTING_PROJECTION` |573|ERROR|U|
| `CONFLICTING_PROJECTION_IN_TYPEALIAS_EXPANSION` |574|ERROR|U|
| `CONFLICTING_PROJECTION_IN_CALLABLE_REFERENCE_WARNING` |575|WARNING|U|
| `REDUNDANT_PROJECTION` |576|WARNING|U|
| `VARIANCE_ON_TYPE_PARAMETER_NOT_ALLOWED` |577|ERROR|U|
| `CATCH_PARAMETER_WITH_DEFAULT_VALUE` |578|ERROR|U|
| `TYPE_PARAMETER_IN_CATCH_CLAUSE` |579|ERROR|U|
| `GENERIC_THROWABLE_SUBCLASS` |580|ERROR|U|
| `INNER_CLASS_OF_GENERIC_THROWABLE_SUBCLASS` |581|ERROR|U|
| `KCLASS_WITH_NULLABLE_TYPE_PARAMETER_IN_SIGNATURE` |582|ERROR|U|
| `TYPE_PARAMETER_AS_REIFIED` |583|ERROR|U|
| `TYPE_PARAMETER_AS_REIFIED_DEPRECATION_WARNING` |584|WARNING|U|
| `TYPE_PARAMETER_AS_REIFIED_ARRAY_ERROR` |585|ERROR|U|
| `REIFIED_TYPE_FORBIDDEN_SUBSTITUTION` |586|ERROR|U|
| `DEFINITELY_NON_NULLABLE_AS_REIFIED` |587|ERROR|U|
| `TYPE_INTERSECTION_AS_REIFIED` |588|feature: `ProhibitIntersectionReifiedTypeParameter`|F|
| `TYPE_INTERSECTION_AS_REIFIED_DEPRECATION_WARNING` |589|WARNING|U|
| `FINAL_UPPER_BOUND` |590|WARNING|U|
| `UPPER_BOUND_IS_EXTENSION_OR_CONTEXT_FUNCTION_TYPE` |591|ERROR|U|
| `BOUNDS_NOT_ALLOWED_IF_BOUNDED_BY_TYPE_PARAMETER` |592|ERROR|U|
| `ONLY_ONE_CLASS_BOUND_ALLOWED` |593|ERROR|U|
| `REPEATED_BOUND` |594|ERROR|U|
| `CONFLICTING_UPPER_BOUNDS` |595|ERROR|M: `ktc.compiler.common.conflicting_upper_bounds`|
| `NAME_IN_CONSTRAINT_IS_NOT_A_TYPE_PARAMETER` |596|ERROR|U|
| `BOUND_ON_TYPE_ALIAS_PARAMETER_NOT_ALLOWED` |597|ERROR|U|
| `REIFIED_TYPE_PARAMETER_NO_INLINE` |598|ERROR|U|
| `REIFIED_TYPE_PARAMETER_ON_ALIAS` |599|feature: `ForbidReifiedTypeParametersOnTypeAliases`|F|
| `TYPE_PARAMETERS_NOT_ALLOWED` |600|ERROR|U|
| `INCORRECT_TYPE_PARAMETER_OF_PROPERTY` |601|ERROR|U|
| `IMPLICIT_NOTHING_RETURN_TYPE` |602|ERROR|U|
| `IMPLICIT_NOTHING_PROPERTY_TYPE` |603|ERROR|U|
| `ABBREVIATED_NOTHING_RETURN_TYPE` |604|ERROR|U|
| `ABBREVIATED_NOTHING_PROPERTY_TYPE` |605|ERROR|U|
| `CYCLIC_GENERIC_UPPER_BOUND` |606|ERROR|U|
| `FINITE_BOUNDS_VIOLATION` |607|ERROR|U|
| `FINITE_BOUNDS_VIOLATION_IN_JAVA` |608|WARNING|U|
| `EXPANSIVE_INHERITANCE` |609|ERROR|U|
| `EXPANSIVE_INHERITANCE_IN_JAVA` |610|WARNING|U|
| `DEPRECATED_TYPE_PARAMETER_SYNTAX` |611|ERROR|U|
| `MISPLACED_TYPE_PARAMETER_CONSTRAINTS` |612|WARNING|U|
| `DYNAMIC_SUPERTYPE` |613|ERROR|U|
| `DYNAMIC_UPPER_BOUND` |614|ERROR|U|
| `DYNAMIC_RECEIVER_NOT_ALLOWED` |615|ERROR|U|
| `DYNAMIC_RECEIVER_EXPECTED_BUT_WAS_NON_DYNAMIC` |616|ERROR|U|
| `INCOMPATIBLE_TYPES` |617|ERROR|U|
| `INCOMPATIBLE_TYPES_WARNING` |618|WARNING|U|
| `TYPE_VARIANCE_CONFLICT_ERROR` |619|ERROR|M: `ktc.compiler.common.type_variance_conflict_error`|
| `TYPE_VARIANCE_CONFLICT_IN_EXPANDED_TYPE` |620|ERROR|U|
| `SMARTCAST_IMPOSSIBLE` |621|ERROR|M: `ktc.compiler.common.smartcast_impossible`|
| `SMARTCAST_IMPOSSIBLE_ON_IMPLICIT_INVOKE_RECEIVER` |622|ERROR|M: `ktc.compiler.common.smartcast_impossible_on_implicit_invoke_receiver`|
| `DEPRECATED_SMARTCAST_ON_DELEGATED_PROPERTY` |623|WARNING|U|
| `PLATFORM_CLASS_MAPPED_TO_KOTLIN` |624|WARNING|U|
| `INFERRED_TYPE_VARIABLE_INTO_EMPTY_INTERSECTION` |625|feature: `ForbidInferringTypeVariablesIntoEmptyIntersection`|F|
| `INFERRED_TYPE_VARIABLE_INTO_POSSIBLE_EMPTY_INTERSECTION` |626|WARNING|U|
| `INCORRECT_LEFT_COMPONENT_OF_INTERSECTION` |627|ERROR|U|
| `INCORRECT_RIGHT_COMPONENT_OF_INTERSECTION` |628|ERROR|U|
| `NULLABLE_ON_DEFINITELY_NOT_NULLABLE` |629|ERROR|U|
| `REDUNDANT_NULLABLE` |630|WARNING|U|
| `INFERRED_INVISIBLE_REIFIED_TYPE_ARGUMENT_WARNING` |631|WARNING|U|
| `INFERRED_INVISIBLE_VARARG_TYPE_ARGUMENT_WARNING` |632|WARNING|U|
| `INFERRED_INVISIBLE_RETURN_TYPE_WARNING` |633|WARNING|U|
| `GENERIC_QUALIFIER_ON_CONSTRUCTOR_CALL` |634|feature: `ProhibitGenericQualifiersOnConstructorCalls`|F|
| `ATOMIC_REF_WITHOUT_CONSISTENT_IDENTITY` |635|WARNING|U|
| `ATOMIC_REF_CALL_ARGUMENT_WITHOUT_CONSISTENT_IDENTITY` |636|WARNING|U|
| `EXTENSION_IN_CLASS_REFERENCE_NOT_ALLOWED` |639|ERROR|U|
| `CALLABLE_REFERENCE_LHS_NOT_A_CLASS` |640|ERROR|U|
| `CALLABLE_REFERENCE_TO_ANNOTATION_CONSTRUCTOR` |641|ERROR|U|
| `ADAPTED_CALLABLE_REFERENCE_AGAINST_REFLECTION_TYPE` |642|ERROR|U|
| `CLASS_LITERAL_LHS_NOT_A_CLASS` |643|ERROR|U|
| `CLASS_LITERAL_LHS_NOT_A_CLASS_WARNING` |644|WARNING|U|
| `NULLABLE_TYPE_IN_CLASS_LITERAL_LHS` |645|ERROR|U|
| `EXPRESSION_OF_NULLABLE_TYPE_IN_CLASS_LITERAL_LHS` |646|ERROR|U|
| `EXPRESSION_OF_NULLABLE_TYPE_IN_CLASS_LITERAL_LHS_WARNING` |647|WARNING|U|
| `UNSUPPORTED_CLASS_LITERALS_WITH_EMPTY_LHS` |648|ERROR|U|
| `UNSUPPORTED_ARRAY_OF_NOTHING_IN_CLASS_LITERAL_LHS` |649|WARNING|U|
| `MUTABLE_PROPERTY_WITH_CAPTURED_TYPE` |650|WARNING|U|
| `NOTHING_TO_OVERRIDE` |653|ERROR|M: `ktc.compiler.common.nothing_to_override`|
| `CANNOT_OVERRIDE_INVISIBLE_MEMBER` |654|ERROR|M: `ktc.compiler.common.cannot_override_invisible_member`|
| `DATA_CLASS_OVERRIDE_CONFLICT` |655|ERROR|M: `ktc.compiler.common.data_class_override_conflict`|
| `DATA_CLASS_OVERRIDE_DEFAULT_VALUES` |656|ERROR|M: `ktc.compiler.common.data_class_override_default_values`|
| `CANNOT_WEAKEN_ACCESS_PRIVILEGE` |657|ERROR|M: `ktc.compiler.common.cannot_weaken_access_privilege`|
| `CANNOT_WEAKEN_ACCESS_PRIVILEGE_WARNING` |658|WARNING|M: `ktc.compiler.common.cannot_weaken_access_privilege_warning`|
| `CANNOT_CHANGE_ACCESS_PRIVILEGE` |659|ERROR|M: `ktc.compiler.common.cannot_change_access_privilege`|
| `CANNOT_CHANGE_ACCESS_PRIVILEGE_WARNING` |660|WARNING|M: `ktc.compiler.common.cannot_change_access_privilege_warning`|
| `CANNOT_INFER_VISIBILITY` |661|ERROR|M: `ktc.compiler.common.cannot_infer_visibility`|
| `CANNOT_INFER_VISIBILITY_WARNING` |662|WARNING|M: `ktc.compiler.common.cannot_infer_visibility_warning`|
| `MULTIPLE_DEFAULTS_INHERITED_FROM_SUPERTYPES` |663|ERROR|R|
| `MULTIPLE_DEFAULTS_INHERITED_FROM_SUPERTYPES_WHEN_NO_EXPLICIT_OVERRIDE` |664|ERROR|R|
| `MULTIPLE_DEFAULTS_INHERITED_FROM_SUPERTYPES_DEPRECATION` |665|feature: `ProhibitAllMultipleDefaultsInheritedFromSupertypes`|F|
| `MULTIPLE_DEFAULTS_INHERITED_FROM_SUPERTYPES_WHEN_NO_EXPLICIT_OVERRIDE_DEPRECATION` |666|feature: `ProhibitAllMultipleDefaultsInheritedFromSupertypes`|F|
| `TYPEALIAS_EXPANDS_TO_ARRAY_OF_NOTHINGS` |667|ERROR|M: `ktc.compiler.common.typealias_expands_to_array_of_nothings`|
| `OVERRIDING_FINAL_MEMBER` |668|ERROR|M: `ktc.compiler.common.overriding_final_member`|
| `RETURN_TYPE_MISMATCH_ON_OVERRIDE` |669|ERROR|M: `ktc.compiler.common.return_type_mismatch_on_override`|
| `PROPERTY_TYPE_MISMATCH_ON_OVERRIDE` |670|ERROR|M: `ktc.compiler.common.property_type_mismatch_on_override`|
| `VAR_TYPE_MISMATCH_ON_OVERRIDE` |671|ERROR|M: `ktc.compiler.common.var_type_mismatch_on_override`|
| `RETURN_TYPE_MISMATCH_ON_INHERITANCE` |672|ERROR|M: `ktc.compiler.common.return_type_mismatch_on_inheritance`|
| `PROPERTY_TYPE_MISMATCH_ON_INHERITANCE` |673|ERROR|M: `ktc.compiler.common.property_type_mismatch_on_inheritance`|
| `VAR_TYPE_MISMATCH_ON_INHERITANCE` |674|ERROR|M: `ktc.compiler.common.var_type_mismatch_on_inheritance`|
| `RETURN_TYPE_MISMATCH_BY_DELEGATION` |675|ERROR|M: `ktc.compiler.common.return_type_mismatch_by_delegation`|
| `PROPERTY_TYPE_MISMATCH_BY_DELEGATION` |676|ERROR|M: `ktc.compiler.common.property_type_mismatch_by_delegation`|
| `VAR_OVERRIDDEN_BY_VAL_BY_DELEGATION` |677|ERROR|M: `ktc.compiler.common.var_overridden_by_val_by_delegation`|
| `CONFLICTING_INHERITED_MEMBERS` |678|ERROR|M: `ktc.compiler.common.conflicting_inherited_members`|
| `ABSTRACT_MEMBER_NOT_IMPLEMENTED` |679|ERROR|M: `ktc.compiler.common.abstract_member_not_implemented`|
| `ABSTRACT_MEMBER_INCORRECTLY_DELEGATED` |680|feature: `ForbidObjectDelegationToItself`|F|
| `ABSTRACT_MEMBER_NOT_IMPLEMENTED_BY_ENUM_ENTRY` |681|ERROR|M: `ktc.compiler.common.abstract_member_not_implemented_by_enum_entry`|
| `ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED` |682|ERROR|M: `ktc.compiler.common.abstract_class_member_not_implemented`|
| `INVISIBLE_ABSTRACT_MEMBER_FROM_SUPER_ERROR` |683|ERROR|M: `ktc.compiler.common.invisible_abstract_member_from_super_error`|
| `AMBIGUOUS_ANONYMOUS_TYPE_INFERRED` |684|ERROR|M: `ktc.compiler.common.ambiguous_anonymous_type_inferred`|
| `MANY_IMPL_MEMBER_NOT_IMPLEMENTED` |685|ERROR|M: `ktc.compiler.common.many_impl_member_not_implemented`|
| `MANY_INTERFACES_MEMBER_NOT_IMPLEMENTED` |686|ERROR|M: `ktc.compiler.common.many_interfaces_member_not_implemented`|
| `OVERRIDING_FINAL_MEMBER_BY_DELEGATION` |687|ERROR|M: `ktc.compiler.common.overriding_final_member_by_delegation`|
| `DELEGATED_MEMBER_HIDES_SUPERTYPE_OVERRIDE` |688|WARNING|M: `ktc.compiler.common.delegated_member_hides_supertype_override`|
| `VAR_OVERRIDDEN_BY_VAL` |689|ERROR|M: `ktc.compiler.common.var_overridden_by_val`|
| `VAR_IMPLEMENTED_BY_INHERITED_VAL` |690|feature: `ProhibitImplementingVarByInheritedVal`|F|
| `NON_FINAL_MEMBER_IN_FINAL_CLASS` |691|WARNING|M: `ktc.compiler.common.non_final_member_in_final_class`|
| `NON_FINAL_MEMBER_IN_OBJECT` |692|WARNING|M: `ktc.compiler.common.non_final_member_in_object`|
| `VIRTUAL_MEMBER_HIDDEN` |693|ERROR|M: `ktc.compiler.common.virtual_member_hidden`|
| `PARAMETER_NAME_CHANGED_ON_OVERRIDE` |694|WARNING|M: `ktc.compiler.common.parameter_name_changed_on_override`|
| `DIFFERENT_NAMES_FOR_THE_SAME_PARAMETER_IN_SUPERTYPES` |695|WARNING|M: `ktc.compiler.common.different_names_for_the_same_parameter_in_supertypes`|
| `SUSPEND_OVERRIDDEN_BY_NON_SUSPEND` |696|ERROR|M: `ktc.compiler.common.suspend_overridden_by_non_suspend`|
| `NON_SUSPEND_OVERRIDDEN_BY_SUSPEND` |697|ERROR|M: `ktc.compiler.common.non_suspend_overridden_by_suspend`|
| `OVERRIDING_IGNORABLE_WITH_MUST_USE` |698|WARNING|M: `ktc.compiler.common.overriding_ignorable_with_must_use`|
| `MANY_COMPANION_OBJECTS` |701|ERROR|U|
| `CONFLICTING_OVERLOADS` |702|ERROR|U|
| `REDECLARATION` |703|ERROR|U|
| `CLASSIFIER_REDECLARATION` |704|ERROR|U|
| `PACKAGE_CONFLICTS_WITH_CLASSIFIER` |705|ERROR|U|
| `EXPECT_AND_ACTUAL_IN_THE_SAME_MODULE` |706|ERROR|U|
| `METHOD_OF_ANY_IMPLEMENTED_IN_INTERFACE` |707|ERROR|U|
| `EXTENSION_SHADOWED_BY_MEMBER` |708|WARNING|U|
| `EXTENSION_FUNCTION_SHADOWED_BY_MEMBER_PROPERTY_WITH_INVOKE` |709|WARNING|U|
| `LOCAL_OBJECT_NOT_ALLOWED` |712|ERROR|U|
| `LOCAL_INTERFACE_NOT_ALLOWED` |713|ERROR|U|
| `ABSTRACT_FUNCTION_IN_NON_ABSTRACT_CLASS` |716|ERROR|U|
| `ABSTRACT_FUNCTION_WITH_BODY` |717|ERROR|U|
| `NON_ABSTRACT_FUNCTION_WITH_NO_BODY` |718|ERROR|U|
| `PRIVATE_FUNCTION_WITH_NO_BODY` |719|ERROR|U|
| `NON_MEMBER_FUNCTION_NO_BODY` |720|ERROR|U|
| `FUNCTION_DECLARATION_WITH_NO_NAME` |721|ERROR|U|
| `ANONYMOUS_FUNCTION_WITH_NAME` |722|ERROR|U|
| `SINGLE_ANONYMOUS_FUNCTION_WITH_NAME` |723|feature: `ProhibitSingleNamedFunctionAsExpression`|F|
| `ANONYMOUS_FUNCTION_PARAMETER_WITH_DEFAULT_VALUE` |724|ERROR|U|
| `USELESS_VARARG_ON_PARAMETER` |725|WARNING|U|
| `MULTIPLE_VARARG_PARAMETERS` |726|ERROR|U|
| `FORBIDDEN_VARARG_PARAMETER_TYPE` |727|ERROR|U|
| `VALUE_PARAMETER_WITHOUT_EXPLICIT_TYPE` |728|ERROR|U|
| `CANNOT_INFER_PARAMETER_TYPE` |729|ERROR|U|
| `CANNOT_INFER_VALUE_PARAMETER_TYPE` |730|ERROR|U|
| `CANNOT_INFER_IT_PARAMETER_TYPE` |731|ERROR|U|
| `CANNOT_INFER_RECEIVER_PARAMETER_TYPE` |732|ERROR|U|
| `NO_TAIL_CALLS_FOUND` |733|WARNING|U|
| `TAILREC_ON_VIRTUAL_MEMBER_ERROR` |734|ERROR|U|
| `NON_TAIL_RECURSIVE_CALL` |735|WARNING|U|
| `TAIL_RECURSION_IN_TRY_IS_NOT_SUPPORTED` |736|WARNING|U|
| `DATA_OBJECT_CUSTOM_EQUALS_OR_HASH_CODE` |737|ERROR|U|
| `DEFAULT_VALUE_NOT_ALLOWED_IN_OVERRIDE` |740|ERROR|U|
| `FUN_INTERFACE_WRONG_COUNT_OF_ABSTRACT_MEMBERS` |743|ERROR|U|
| `FUN_INTERFACE_CANNOT_HAVE_ABSTRACT_PROPERTIES` |744|ERROR|U|
| `FUN_INTERFACE_ABSTRACT_METHOD_WITH_TYPE_PARAMETERS` |745|ERROR|U|
| `FUN_INTERFACE_ABSTRACT_METHOD_WITH_DEFAULT_VALUE` |746|ERROR|U|
| `FUN_INTERFACE_WITH_SUSPEND_FUNCTION` |747|ERROR|U|
| `ABSTRACT_PROPERTY_IN_NON_ABSTRACT_CLASS` |750|ERROR|U|
| `PRIVATE_PROPERTY_IN_INTERFACE` |751|ERROR|U|
| `ABSTRACT_PROPERTY_WITH_INITIALIZER` |752|ERROR|U|
| `PROPERTY_INITIALIZER_IN_INTERFACE` |753|ERROR|U|
| `PROPERTY_WITH_NO_TYPE_NO_INITIALIZER` |754|ERROR|U|
| `ABSTRACT_PROPERTY_WITHOUT_TYPE` |755|ERROR|U|
| `LATEINIT_PROPERTY_WITHOUT_TYPE` |756|ERROR|U|
| `MUST_BE_INITIALIZED` |757|ERROR|U|
| `MUST_BE_INITIALIZED_WARNING` |758|WARNING|U|
| `MUST_BE_INITIALIZED_OR_BE_FINAL` |759|ERROR|U|
| `MUST_BE_INITIALIZED_OR_BE_FINAL_WARNING` |760|WARNING|U|
| `MUST_BE_INITIALIZED_OR_BE_ABSTRACT` |761|ERROR|U|
| `MUST_BE_INITIALIZED_OR_BE_ABSTRACT_WARNING` |762|WARNING|U|
| `MUST_BE_INITIALIZED_OR_FINAL_OR_ABSTRACT` |763|ERROR|U|
| `MUST_BE_INITIALIZED_OR_FINAL_OR_ABSTRACT_WARNING` |764|WARNING|U|
| `EXPLICIT_FIELD_MUST_BE_INITIALIZED` |765|ERROR|U|
| `EXTENSION_PROPERTY_MUST_HAVE_ACCESSORS_OR_BE_ABSTRACT` |766|ERROR|U|
| `UNNECESSARY_LATEINIT` |767|WARNING|U|
| `BACKING_FIELD_IN_INTERFACE` |768|ERROR|U|
| `EXTENSION_PROPERTY_WITH_BACKING_FIELD` |769|ERROR|U|
| `PROPERTY_INITIALIZER_NO_BACKING_FIELD` |770|ERROR|U|
| `ABSTRACT_DELEGATED_PROPERTY` |771|ERROR|U|
| `DELEGATED_PROPERTY_IN_INTERFACE` |772|ERROR|U|
| `ABSTRACT_PROPERTY_WITH_GETTER` |773|ERROR|U|
| `ABSTRACT_PROPERTY_WITH_SETTER` |774|ERROR|U|
| `PRIVATE_SETTER_FOR_ABSTRACT_PROPERTY` |775|ERROR|U|
| `PRIVATE_SETTER_FOR_OPEN_PROPERTY` |776|ERROR|U|
| `VAL_WITH_SETTER` |777|ERROR|U|
| `CONST_VAL_NOT_TOP_LEVEL_OR_OBJECT` |778|ERROR|U|
| `CONST_VAL_WITH_GETTER` |779|ERROR|U|
| `CONST_VAL_WITH_DELEGATE` |780|ERROR|U|
| `TYPE_CANT_BE_USED_FOR_CONST_VAL` |781|ERROR|U|
| `CONST_VAL_WITHOUT_INITIALIZER` |782|ERROR|U|
| `CONST_VAL_WITH_EBF` |783|ERROR|U|
| `CONST_VAL_WITH_NON_CONST_INITIALIZER` |784|ERROR|U|
| `DELEGATE_USES_EXTENSION_PROPERTY_TYPE_PARAMETER_ERROR` |785|ERROR|U|
| `GETTER_VISIBILITY_DIFFERS_FROM_PROPERTY_VISIBILITY` |786|ERROR|U|
| `SETTER_VISIBILITY_INCONSISTENT_WITH_PROPERTY_VISIBILITY` |787|ERROR|U|
| `WRONG_GETTER_RETURN_TYPE` |788|ERROR|U|
| `WRONG_SETTER_RETURN_TYPE` |789|ERROR|U|
| `WRONG_SETTER_PARAMETER_TYPE` |790|ERROR|U|
| `ACCESSOR_FOR_DELEGATED_PROPERTY` |791|ERROR|U|
| `PROPERTY_INITIALIZER_WITH_EXPLICIT_FIELD_DECLARATION` |792|ERROR|U|
| `PROPERTY_FIELD_DECLARATION_MISSING_INITIALIZER` |793|ERROR|U|
| `LATEINIT_NULLABLE_BACKING_FIELD` |794|ERROR|U|
| `BACKING_FIELD_FOR_DELEGATED_PROPERTY` |795|ERROR|U|
| `VAR_PROPERTY_WITH_EXPLICIT_BACKING_FIELD` |796|ERROR|U|
| `NON_FINAL_PROPERTY_WITH_EXPLICIT_BACKING_FIELD` |797|ERROR|U|
| `EXPECT_PROPERTY_WITH_EXPLICIT_BACKING_FIELD` |798|ERROR|U|
| `INCONSISTENT_BACKING_FIELD_TYPE` |799|ERROR|U|
| `EXPLICIT_FIELD_VISIBILITY_MUST_BE_LESS_PERMISSIVE` |800|ERROR|U|
| `PROPERTY_WITH_EXPLICIT_FIELD_AND_ACCESSORS` |801|ERROR|U|
| `EXPLICIT_BACKING_FIELD_IN_INTERFACE` |802|ERROR|U|
| `EXPLICIT_BACKING_FIELD_IN_ABSTRACT_PROPERTY` |803|ERROR|U|
| `EXPLICIT_BACKING_FIELD_IN_EXTENSION` |804|ERROR|U|
| `REDUNDANT_EXPLICIT_BACKING_FIELD` |805|WARNING|U|
| `ABSTRACT_PROPERTY_IN_PRIMARY_CONSTRUCTOR_PARAMETERS` |806|ERROR|U|
| `LOCAL_VARIABLE_WITH_TYPE_PARAMETERS_WARNING` |807|WARNING|U|
| `LOCAL_VARIABLE_WITH_TYPE_PARAMETERS` |808|ERROR|U|
| `EXPLICIT_TYPE_ARGUMENTS_IN_PROPERTY_ACCESS` |809|ERROR|U|
| `EXPLICIT_TYPE_ARGUMENTS_IN_PROPERTY_ACCESS_WARNING` |810|WARNING|U|
| `SAFE_CALLABLE_REFERENCE_CALL` |811|ERROR|U|
| `LATEINIT_INTRINSIC_CALL_ON_NON_LITERAL` |812|ERROR|U|
| `LATEINIT_INTRINSIC_CALL_ON_NON_LATEINIT` |813|ERROR|U|
| `LATEINIT_INTRINSIC_CALL_IN_INLINE_FUNCTION` |814|ERROR|U|
| `LATEINIT_INTRINSIC_CALL_ON_NON_ACCESSIBLE_PROPERTY` |815|ERROR|U|
| `LOCAL_EXTENSION_PROPERTY` |816|ERROR|U|
| `UNNAMED_VAR_PROPERTY` |817|ERROR|U|
| `UNNAMED_DELEGATED_PROPERTY` |818|ERROR|U|
| `UNNAMED_PROPERTY_WITH_IMPLICIT_IGNORABLE_TYPE` |819|WARNING|U|
| `DESTRUCTURING_SHORT_FORM_NAME_MISMATCH` |820|WARNING|U|
| `DESTRUCTURING_SHORT_FORM_OF_NON_DATA_CLASS` |821|WARNING|U|
| `DESTRUCTURING_SHORT_FORM_UNDERSCORE` |822|WARNING|U|
| `NAME_BASED_DESTRUCTURING_UNDERSCORE_WITHOUT_RENAMING` |823|ERROR|U|
| `EXPECTED_DECLARATION_WITH_BODY` |826|ERROR|M: `ktc.compiler.common.expected_declaration_with_body`|
| `EXPECTED_CLASS_CONSTRUCTOR_DELEGATION_CALL` |827|ERROR|M: `ktc.compiler.common.expected_class_constructor_delegation_call`|
| `EXPECTED_CLASS_CONSTRUCTOR_PROPERTY_PARAMETER` |828|ERROR|M: `ktc.compiler.common.expected_class_constructor_property_parameter`|
| `EXPECTED_ENUM_CONSTRUCTOR` |829|ERROR|M: `ktc.compiler.common.expected_enum_constructor`|
| `EXPECTED_ENUM_ENTRY_WITH_BODY` |830|ERROR|M: `ktc.compiler.common.expected_enum_entry_with_body`|
| `EXPECTED_PROPERTY_INITIALIZER` |831|ERROR|M: `ktc.compiler.common.expected_property_initializer`|
| `EXPECTED_DELEGATED_PROPERTY` |832|ERROR|M: `ktc.compiler.common.expected_delegated_property`|
| `EXPECTED_LATEINIT_PROPERTY` |833|ERROR|M: `ktc.compiler.common.expected_lateinit_property`|
| `SUPERTYPE_INITIALIZED_IN_EXPECTED_CLASS` |834|ERROR|M: `ktc.compiler.common.supertype_initialized_in_expected_class`|
| `EXPECTED_PRIVATE_DECLARATION` |835|ERROR|M: `ktc.compiler.common.expected_private_declaration`|
| `EXPECTED_EXTERNAL_DECLARATION` |836|ERROR|M: `ktc.compiler.common.expected_external_declaration`|
| `EXPECTED_TAILREC_FUNCTION` |837|ERROR|M: `ktc.compiler.common.expected_tailrec_function`|
| `IMPLEMENTATION_BY_DELEGATION_IN_EXPECT_CLASS` |838|ERROR|M: `ktc.compiler.common.implementation_by_delegation_in_expect_class`|
| `ACTUAL_TYPE_ALIAS_NOT_TO_CLASS` |839|ERROR|M: `ktc.compiler.common.actual_type_alias_not_to_class`|
| `ACTUAL_TYPE_ALIAS_TO_CLASS_WITH_DECLARATION_SITE_VARIANCE` |840|ERROR|M: `ktc.compiler.common.actual_type_alias_to_class_with_declaration_site_variance`|
| `ACTUAL_TYPE_ALIAS_WITH_USE_SITE_VARIANCE` |841|ERROR|M: `ktc.compiler.common.actual_type_alias_with_use_site_variance`|
| `ACTUAL_TYPE_ALIAS_WITH_COMPLEX_SUBSTITUTION` |842|ERROR|M: `ktc.compiler.common.actual_type_alias_with_complex_substitution`|
| `ACTUAL_TYPE_ALIAS_TO_NULLABLE_TYPE` |843|ERROR|M: `ktc.compiler.common.actual_type_alias_to_nullable_type`|
| `ACTUAL_TYPE_ALIAS_TO_NOTHING` |844|ERROR|M: `ktc.compiler.common.actual_type_alias_to_nothing`|
| `ACTUAL_FUNCTION_WITH_DEFAULT_ARGUMENTS` |845|ERROR|M: `ktc.compiler.common.actual_function_with_default_arguments`|
| `DEFAULT_ARGUMENTS_IN_EXPECT_WITH_ACTUAL_TYPEALIAS` |846|ERROR|M: `ktc.compiler.common.default_arguments_in_expect_with_actual_typealias`|
| `DEFAULT_ARGUMENTS_IN_EXPECT_ACTUALIZED_BY_FAKE_OVERRIDE` |847|ERROR|M: `ktc.compiler.common.default_arguments_in_expect_actualized_by_fake_override`|
| `EXPECTED_FUNCTION_SOURCE_WITH_DEFAULT_ARGUMENTS_NOT_FOUND` |848|ERROR|T|
| `ACTUAL_WITHOUT_EXPECT` |849|ERROR|M: `ktc.compiler.common.actual_without_expect`|
| `EXPECT_ACTUAL_INCOMPATIBLE_CLASS_TYPE_PARAMETER_COUNT` |850|ERROR|M: `ktc.compiler.common.expect_actual_incompatible_class_type_parameter_count`|
| `EXPECT_ACTUAL_INCOMPATIBLE_RETURN_TYPE` |851|ERROR|M: `ktc.compiler.common.expect_actual_incompatible_return_type`|
| `EXPECT_ACTUAL_INCOMPATIBLE_PARAMETER_NAMES` |852|ERROR|M: `ktc.compiler.common.expect_actual_incompatible_parameter_names`|
| `EXPECT_ACTUAL_INCOMPATIBLE_CONTEXT_PARAMETER_NAMES` |853|ERROR|M: `ktc.compiler.common.expect_actual_incompatible_context_parameter_names`|
| `EXPECT_ACTUAL_INCOMPATIBLE_TYPE_PARAMETER_NAMES` |854|ERROR|M: `ktc.compiler.common.expect_actual_incompatible_type_parameter_names`|
| `EXPECT_ACTUAL_INCOMPATIBLE_VALUE_PARAMETER_VARARG` |855|ERROR|M: `ktc.compiler.common.expect_actual_incompatible_value_parameter_vararg`|
| `EXPECT_ACTUAL_INCOMPATIBLE_VALUE_PARAMETER_NOINLINE` |856|ERROR|M: `ktc.compiler.common.expect_actual_incompatible_value_parameter_noinline`|
| `EXPECT_ACTUAL_INCOMPATIBLE_VALUE_PARAMETER_CROSSINLINE` |857|ERROR|M: `ktc.compiler.common.expect_actual_incompatible_value_parameter_crossinline`|
| `EXPECT_ACTUAL_INCOMPATIBLE_FUNCTION_MODIFIERS_DIFFERENT` |858|ERROR|T|
| `EXPECT_ACTUAL_INCOMPATIBLE_FUNCTION_MODIFIERS_NOT_SUBSET` |859|ERROR|M: `ktc.compiler.common.expect_actual_incompatible_function_modifiers_not_subset`|
| `EXPECT_ACTUAL_INCOMPATIBLE_PARAMETERS_WITH_DEFAULT_VALUES_IN_EXPECT_ACTUALIZED_BY_FAKE_OVERRIDE` |860|ERROR|T|
| `EXPECT_ACTUAL_INCOMPATIBLE_PROPERTY_KIND` |861|ERROR|M: `ktc.compiler.common.expect_actual_incompatible_property_kind`|
| `EXPECT_ACTUAL_INCOMPATIBLE_PROPERTY_LATEINIT_MODIFIER` |862|ERROR|M: `ktc.compiler.common.expect_actual_incompatible_property_lateinit_modifier`|
| `EXPECT_ACTUAL_INCOMPATIBLE_PROPERTY_CONST_MODIFIER` |863|ERROR|T|
| `EXPECT_ACTUAL_INCOMPATIBLE_PROPERTY_SETTER_VISIBILITY` |864|ERROR|M: `ktc.compiler.common.expect_actual_incompatible_property_setter_visibility`|
| `EXPECT_ACTUAL_INCOMPATIBLE_CLASS_KIND` |865|ERROR|M: `ktc.compiler.common.expect_actual_incompatible_class_kind`|
| `EXPECT_ACTUAL_INCOMPATIBLE_CLASS_MODIFIERS` |866|ERROR|M: `ktc.compiler.common.expect_actual_incompatible_class_modifiers`|
| `EXPECT_ACTUAL_INCOMPATIBLE_FUN_INTERFACE_MODIFIER` |867|ERROR|M: `ktc.compiler.common.expect_actual_incompatible_fun_interface_modifier`|
| `EXPECT_ACTUAL_INCOMPATIBLE_SUPERTYPES` |868|ERROR|M: `ktc.compiler.common.expect_actual_incompatible_supertypes`|
| `EXPECT_ACTUAL_INCOMPATIBLE_NESTED_TYPE_ALIAS` |869|ERROR|M: `ktc.compiler.common.expect_actual_incompatible_nested_type_alias`|
| `EXPECT_ACTUAL_INCOMPATIBLE_ENUM_ENTRIES` |870|ERROR|M: `ktc.compiler.common.expect_actual_incompatible_enum_entries`|
| `EXPECT_ACTUAL_INCOMPATIBLE_ILLEGAL_REQUIRES_OPT_IN` |871|ERROR|M: `ktc.compiler.common.expect_actual_incompatible_illegal_requires_opt_in`|
| `EXPECT_ACTUAL_INCOMPATIBLE_MODALITY` |872|ERROR|M: `ktc.compiler.common.expect_actual_incompatible_modality`|
| `EXPECT_ACTUAL_INCOMPATIBLE_VISIBILITY` |873|ERROR|M: `ktc.compiler.common.expect_actual_incompatible_visibility`|
| `EXPECT_ACTUAL_INCOMPATIBLE_CLASS_TYPE_PARAMETER_UPPER_BOUNDS` |874|ERROR|T|
| `EXPECT_ACTUAL_INCOMPATIBLE_TYPE_PARAMETER_VARIANCE` |875|ERROR|M: `ktc.compiler.common.expect_actual_incompatible_type_parameter_variance`|
| `EXPECT_ACTUAL_INCOMPATIBLE_TYPE_PARAMETER_REIFIED` |876|ERROR|T|
| `EXPECT_ACTUAL_INCOMPATIBLE_CLASS_SCOPE` |877|ERROR|M: `ktc.compiler.common.expect_actual_incompatible_class_scope`|
| `EXPECT_REFINEMENT_ANNOTATION_WRONG_TARGET` |878|ERROR|M: `ktc.compiler.common.expect_refinement_annotation_wrong_target`|
| `AMBIGUOUS_EXPECTS` |879|ERROR|M: `ktc.compiler.common.ambiguous_expects`|
| `NO_ACTUAL_CLASS_MEMBER_FOR_EXPECTED_CLASS` |880|ERROR|M: `ktc.compiler.common.no_actual_class_member_for_expected_class`|
| `ACTUAL_MISSING` |881|ERROR|M: `ktc.compiler.common.actual_missing`|
| `EXPECT_REFINEMENT_ANNOTATION_MISSING` |882|ERROR|M: `ktc.compiler.common.expect_refinement_annotation_missing`|
| `EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING` |883|WARNING|M: `ktc.compiler.common.expect_actual_classifiers_are_in_beta_warning`|
| `NOT_A_MULTIPLATFORM_COMPILATION` |884|ERROR|E|
| `EXPECT_ACTUAL_OPT_IN_ANNOTATION` |885|ERROR|M: `ktc.compiler.common.expect_actual_opt_in_annotation`|
| `ACTUAL_TYPEALIAS_TO_SPECIAL_ANNOTATION` |886|ERROR|M: `ktc.compiler.common.actual_typealias_to_special_annotation`|
| `ACTUAL_ANNOTATIONS_NOT_MATCH_EXPECT` |887|WARNING|M: `ktc.compiler.common.actual_annotations_not_match_expect`|
| `ACTUAL_IGNORABILITY_NOT_MATCH_EXPECT` |888|WARNING|M: `ktc.compiler.common.actual_ignorability_not_match_expect`|
| `OPTIONAL_DECLARATION_OUTSIDE_OF_ANNOTATION_ENTRY` |889|ERROR|M: `ktc.compiler.common.optional_declaration_outside_of_annotation_entry`|
| `OPTIONAL_DECLARATION_USAGE_IN_NON_COMMON_SOURCE` |890|ERROR|M: `ktc.compiler.common.optional_declaration_usage_in_non_common_source`|
| `OPTIONAL_EXPECTATION_NOT_ON_EXPECTED` |891|ERROR|M: `ktc.compiler.common.optional_expectation_not_on_expected`|
| `UNINITIALIZED_VARIABLE` |894|ERROR|M: `ktc.compiler.common.uninitialized_variable`|
| `UNINITIALIZED_PARAMETER` |895|ERROR|E|
| `UNINITIALIZED_ENUM_ENTRY` |896|ERROR|M: `ktc.compiler.common.uninitialized_enum_entry`|
| `UNINITIALIZED_ENUM_COMPANION` |897|ERROR|M: `ktc.compiler.common.uninitialized_enum_companion`|
| `VAL_REASSIGNMENT` |898|ERROR|M: `ktc.compiler.common.val_reassignment`|
| `VAL_REASSIGNMENT_VIA_BACKING_FIELD_ERROR` |899|ERROR|M: `ktc.compiler.common.val_reassignment_via_backing_field_error`|
| `CAPTURED_VAL_INITIALIZATION` |900|ERROR|M: `ktc.compiler.common.captured_val_initialization`|
| `CAPTURED_MEMBER_VAL_INITIALIZATION` |901|ERROR|M: `ktc.compiler.common.captured_member_val_initialization`|
| `NON_INLINE_MEMBER_VAL_INITIALIZATION` |902|ERROR|M: `ktc.compiler.common.non_inline_member_val_initialization`|
| `SETTER_PROJECTED_OUT` |903|ERROR|M: `ktc.compiler.common.setter_projected_out`|
| `WRONG_INVOCATION_KIND` |904|WARNING|M: `ktc.compiler.common.wrong_invocation_kind`|
| `LEAKED_IN_PLACE_LAMBDA` |905|WARNING|M: `ktc.compiler.common.leaked_in_place_lambda`|
| `VARIABLE_WITH_NO_TYPE_NO_INITIALIZER` |906|ERROR|M: `ktc.compiler.common.variable_with_no_type_no_initializer`|
| `INITIALIZATION_BEFORE_DECLARATION` |907|ERROR|M: `ktc.compiler.common.initialization_before_declaration`|
| `INITIALIZATION_BEFORE_DECLARATION_WARNING` |908|WARNING|M: `ktc.compiler.common.initialization_before_declaration_warning`|
| `UNREACHABLE_CODE` |909|WARNING|M: `ktc.compiler.common.unreachable_code`|
| `SENSELESS_COMPARISON` |910|WARNING|M: `ktc.compiler.common.senseless_comparison`|
| `SENSELESS_NULL_IN_WHEN` |911|WARNING|M: `ktc.compiler.common.senseless_null_in_when`|
| `TYPECHECKER_HAS_RUN_INTO_RECURSIVE_PROBLEM` |912|ERROR|E|
| `RETURN_VALUE_NOT_USED` |913|WARNING|M: `ktc.compiler.common.return_value_not_used`|
| `RETURN_VALUE_NOT_USED_COERCION` |914|WARNING|M: `ktc.compiler.common.return_value_not_used_coercion`|
| `NULL_FOR_NONNULL_TYPE` |917|ERROR|M: `ktc.compiler.common.null_for_nonnull_type`|
| `UNSAFE_CALL` |918|ERROR|M: `ktc.compiler.common.unsafe_call`|
| `UNSAFE_IMPLICIT_INVOKE_CALL` |919|ERROR|M: `ktc.compiler.common.unsafe_implicit_invoke_call`|
| `UNSAFE_INFIX_CALL` |920|ERROR|M: `ktc.compiler.common.unsafe_infix_call`|
| `UNSAFE_OPERATOR_CALL` |921|ERROR|M: `ktc.compiler.common.unsafe_operator_call`|
| `UNSAFE_CALLABLE_REFERENCE` |922|ERROR|M: `ktc.compiler.common.unsafe_callable_reference`|
| `ITERATOR_ON_NULLABLE` |923|ERROR|M: `ktc.compiler.common.iterator_on_nullable`|
| `COMPONENT_FUNCTION_ON_NULLABLE` |924|ERROR|M: `ktc.compiler.common.component_function_on_nullable`|
| `UNEXPECTED_SAFE_CALL` |925|ERROR|M: `ktc.compiler.common.unexpected_safe_call`|
| `UNNECESSARY_SAFE_CALL` |926|WARNING|M: `ktc.compiler.common.unnecessary_safe_call`|
| `UNNECESSARY_NOT_NULL_ASSERTION` |927|WARNING|M: `ktc.compiler.common.unnecessary_not_null_assertion`|
| `NOT_NULL_ASSERTION_ON_LAMBDA_EXPRESSION` |928|WARNING|M: `ktc.compiler.common.not_null_assertion_on_lambda_expression`|
| `NOT_NULL_ASSERTION_ON_CALLABLE_REFERENCE` |929|WARNING|M: `ktc.compiler.common.not_null_assertion_on_callable_reference`|
| `USELESS_ELVIS` |930|WARNING|M: `ktc.compiler.common.useless_elvis`|
| `USELESS_ELVIS_RIGHT_IS_NULL` |931|WARNING|M: `ktc.compiler.common.useless_elvis_right_is_null`|
| `USELESS_ELVIS_LEFT_IS_NULL` |932|WARNING|M: `ktc.compiler.common.useless_elvis_left_is_null`|
| `CANNOT_CHECK_FOR_ERASED` |935|ERROR|M: `ktc.compiler.common.cannot_check_for_erased`|
| `UNSAFE_CAST_RELYING_ON_NULL` |936|WARNING|M: `ktc.compiler.common.unsafe_cast_relying_on_null`|
| `SAFE_CAST_RELYING_ON_NULL` |937|WARNING|M: `ktc.compiler.common.safe_cast_relying_on_null`|
| `CAST_NEVER_SUCCEEDS` |938|WARNING|M: `ktc.compiler.common.cast_never_succeeds`|
| `USELESS_CAST` |939|WARNING|M: `ktc.compiler.common.useless_cast`|
| `UNCHECKED_CAST` |940|WARNING|M: `ktc.compiler.common.unchecked_cast`|
| `NUMERIC_CAST_NEVER_SUCCEEDS_BUT_CAN_BE_REPLACED_WITH_TO_CALL` |941|WARNING|M: `ktc.compiler.common.numeric_cast_never_succeeds_but_can_be_replaced_with_to_call`|
| `INTEGER_LITERAL_CAST_INSTEAD_OF_TO_CALL` |942|WARNING|M: `ktc.compiler.common.integer_literal_cast_instead_of_to_call`|
| `IMPOSSIBLE_IS_CHECK` |943|feature: `TurnTypeCheckWarningsIntoErrors`|F|
| `IMPOSSIBLE_IS_CHECK_RELYING_ON_NULL` |944|feature: `TurnTypeCheckWarningsIntoErrors`|F|
| `USELESS_IS_CHECK` |945|WARNING|M: `ktc.compiler.common.useless_is_check`|
| `IS_ENUM_ENTRY` |946|ERROR|M: `ktc.compiler.common.is_enum_entry`|
| `DYNAMIC_NOT_ALLOWED` |947|ERROR|E|
| `ENUM_ENTRY_AS_TYPE` |948|ERROR|M: `ktc.compiler.common.enum_entry_as_type`|
| `EXPECTED_CONDITION` |951|ERROR|M: `ktc.compiler.common.expected_condition`|
| `NO_ELSE_IN_WHEN` |952|ERROR|M: `ktc.compiler.common.no_else_in_when`|
| `MISSING_BRANCH_FOR_NON_ABSTRACT_SEALED_CLASS` |953|WARNING|M: `ktc.compiler.common.missing_branch_for_non_abstract_sealed_class`|
| `INVALID_IF_AS_EXPRESSION` |954|ERROR|M: `ktc.compiler.common.invalid_if_as_expression`|
| `ELSE_MISPLACED_IN_WHEN` |955|ERROR|M: `ktc.compiler.common.else_misplaced_in_when`|
| `REDUNDANT_ELSE_IN_WHEN` |956|WARNING|M: `ktc.compiler.common.redundant_else_in_when`|
| `ILLEGAL_DECLARATION_IN_WHEN_SUBJECT` |957|ERROR|M: `ktc.compiler.common.illegal_declaration_in_when_subject`|
| `COMMA_IN_WHEN_CONDITION_WITHOUT_ARGUMENT` |958|ERROR|M: `ktc.compiler.common.comma_in_when_condition_without_argument`|
| `DUPLICATE_BRANCH_CONDITION_IN_WHEN` |959|WARNING|M: `ktc.compiler.common.duplicate_branch_condition_in_when`|
| `CONFUSING_BRANCH_CONDITION_ERROR` |960|ERROR|M: `ktc.compiler.common.confusing_branch_condition_error`|
| `WRONG_CONDITION_SUGGEST_GUARD` |961|ERROR|M: `ktc.compiler.common.wrong_condition_suggest_guard`|
| `COMMA_IN_WHEN_CONDITION_WITH_WHEN_GUARD` |962|ERROR|M: `ktc.compiler.common.comma_in_when_condition_with_when_guard`|
| `WHEN_GUARD_WITHOUT_SUBJECT` |963|ERROR|M: `ktc.compiler.common.when_guard_without_subject`|
| `INFERRED_INVISIBLE_WHEN_TYPE_WARNING` |964|WARNING|M: `ktc.compiler.common.inferred_invisible_when_type_warning`|
| `TYPE_PARAMETER_IS_NOT_AN_EXPRESSION` |967|ERROR|U|
| `TYPE_PARAMETER_ON_LHS_OF_DOT` |968|ERROR|U|
| `NO_COMPANION_OBJECT` |969|ERROR|U|
| `EXPRESSION_EXPECTED_PACKAGE_FOUND` |970|ERROR|U|
| `ERROR_IN_CONTRACT_DESCRIPTION` |973|ERROR|M: `ktc.compiler.common.error_in_contract_description`|
| `CONTRACT_NOT_ALLOWED` |974|ERROR|M: `ktc.compiler.common.contract_not_allowed`|
| `NO_GET_METHOD` |977|ERROR|M: `ktc.compiler.common.no_get_method`|
| `NO_SET_METHOD` |978|ERROR|M: `ktc.compiler.common.no_set_method`|
| `ITERATOR_MISSING` |979|ERROR|M: `ktc.compiler.common.iterator_missing`|
| `HAS_NEXT_MISSING` |980|ERROR|M: `ktc.compiler.common.has_next_missing`|
| `NEXT_MISSING` |981|ERROR|M: `ktc.compiler.common.next_missing`|
| `COMPONENT_FUNCTION_MISSING` |982|ERROR|M: `ktc.compiler.common.component_function_missing`|
| `DELEGATE_SPECIAL_FUNCTION_MISSING` |983|ERROR|M: `ktc.compiler.common.delegate_special_function_missing`|
| `UNDERSCORE_IS_RESERVED` |984|ERROR|U|
| `UNDERSCORE_USAGE_WITHOUT_BACKTICKS` |985|ERROR|U|
| `RESOLVED_TO_UNDERSCORE_NAMED_CATCH_PARAMETER` |986|WARNING|U|
| `INVALID_CHARACTERS` |987|ERROR|U|
| `EQUALITY_NOT_APPLICABLE` |988|ERROR|M: `ktc.compiler.common.equality_not_applicable`|
| `EQUALITY_NOT_APPLICABLE_WARNING` |989|WARNING|U|
| `INCOMPATIBLE_ENUM_COMPARISON_ERROR` |990|ERROR|M: `ktc.compiler.common.incompatible_enum_comparison_error`|
| `INCOMPATIBLE_ENUM_COMPARISON` |991|WARNING|U|
| `FORBIDDEN_IDENTITY_EQUALS` |992|ERROR|M: `ktc.compiler.common.forbidden_identity_equals`|
| `FORBIDDEN_IDENTITY_EQUALS_WARNING` |993|WARNING|U|
| `DEPRECATED_IDENTITY_EQUALS` |994|WARNING|U|
| `IMPLICIT_BOXING_IN_IDENTITY_EQUALS` |995|WARNING|M: `ktc.compiler.common.implicit_boxing_in_identity_equals`|
| `INC_DEC_SHOULD_NOT_RETURN_UNIT` |996|ERROR|U|
| `ASSIGNMENT_OPERATOR_SHOULD_RETURN_UNIT` |997|ERROR|U|
| `INITIALIZER_REQUIRED_FOR_DESTRUCTURING_DECLARATION` |998|ERROR|U|
| `NOT_FUNCTION_AS_OPERATOR` |999|ERROR|U|
| `DSL_SCOPE_VIOLATION` |1000|ERROR|M: `ktc.compiler.common.dsl_scope_violation`|
| `RECEIVER_SHADOWED_BY_CONTEXT_PARAMETER` |1001|ERROR|U|
| `RECURSIVE_TYPEALIAS_EXPANSION` |1004|ERROR|M: `ktc.compiler.common.recursive_typealias_expansion`|
| `TYPEALIAS_SHOULD_EXPAND_TO_CLASS` |1005|ERROR|U|
| `CONSTRUCTOR_OR_SUPERTYPE_ON_TYPEALIAS_WITH_TYPE_PROJECTION` |1006|feature: `ProhibitConstructorAndSupertypeOnTypealiasWithTypeProjection`|F|
| `TYPEALIAS_EXPANSION_CAPTURES_OUTER_TYPE_PARAMETERS` |1007|ERROR|U|
| `TYPEALIAS_EXPANDS_TO_COMPILER_REQUIRED_ANNOTATION` |1008|feature: `ForbidTypeAliasToCompilerRequiredAnnotation`|F|
| `EXPECTED_TYPEALIAS` |1009|ERROR|U|
| `REDUNDANT_VISIBILITY_MODIFIER` |1012|WARNING|M: `ktc.compiler.common.redundant_visibility_modifier`|
| `REDUNDANT_MODALITY_MODIFIER` |1013|WARNING|M: `ktc.compiler.common.redundant_modality_modifier`|
| `REDUNDANT_RETURN_UNIT_TYPE` |1014|WARNING|M: `ktc.compiler.common.redundant_return_unit_type`|
| `REDUNDANT_SINGLE_EXPRESSION_STRING_TEMPLATE` |1015|WARNING|M: `ktc.compiler.common.redundant_single_expression_string_template`|
| `CAN_BE_VAL` |1016|WARNING|M: `ktc.compiler.common.can_be_val`|
| `CAN_BE_VAL_LATEINIT` |1017|WARNING|M: `ktc.compiler.common.can_be_val_lateinit`|
| `CAN_BE_VAL_DELAYED_INITIALIZATION` |1018|WARNING|M: `ktc.compiler.common.can_be_val_delayed_initialization`|
| `REDUNDANT_CALL_OF_CONVERSION_METHOD` |1019|WARNING|M: `ktc.compiler.common.redundant_call_of_conversion_method`|
| `ARRAY_EQUALITY_OPERATOR_CAN_BE_REPLACED_WITH_CONTENT_EQUALS` |1020|WARNING|M: `ktc.compiler.common.array_equality_operator_can_be_replaced_with_content_equals`|
| `EMPTY_RANGE` |1021|WARNING|M: `ktc.compiler.common.empty_range`|
| `REDUNDANT_SETTER_PARAMETER_TYPE` |1022|WARNING|M: `ktc.compiler.common.redundant_setter_parameter_type`|
| `UNUSED_VARIABLE` |1023|WARNING|M: `ktc.compiler.common.unused_variable`|
| `ASSIGNED_VALUE_IS_NEVER_READ` |1024|WARNING|M: `ktc.compiler.common.assigned_value_is_never_read`|
| `VARIABLE_INITIALIZER_IS_REDUNDANT` |1025|WARNING|M: `ktc.compiler.common.variable_initializer_is_redundant`|
| `VARIABLE_NEVER_READ` |1026|WARNING|M: `ktc.compiler.common.variable_never_read`|
| `USELESS_CALL_ON_NOT_NULL` |1027|WARNING|M: `ktc.compiler.common.useless_call_on_not_null`|
| `UNUSED_ANONYMOUS_PARAMETER` |1028|WARNING|M: `ktc.compiler.common.unused_anonymous_parameter`|
| `UNUSED_EXPRESSION` |1029|WARNING|M: `ktc.compiler.common.unused_expression`|
| `UNUSED_LAMBDA_EXPRESSION` |1030|WARNING|M: `ktc.compiler.common.unused_lambda_expression`|
| `RETURN_NOT_ALLOWED` |1033|ERROR|U|
| `NOT_A_FUNCTION_LABEL` |1034|ERROR|U|
| `RETURN_IN_FUNCTION_WITH_EXPRESSION_BODY` |1035|ERROR|U|
| `RETURN_IN_FUNCTION_WITH_EXPRESSION_BODY_WARNING` |1036|WARNING|U|
| `RETURN_IN_FUNCTION_WITH_EXPRESSION_BODY_AND_IMPLICIT_TYPE` |1037|ERROR|U|
| `NO_RETURN_IN_FUNCTION_WITH_BLOCK_BODY` |1038|ERROR|U|
| `REDUNDANT_RETURN` |1039|WARNING|U|
| `ANONYMOUS_INITIALIZER_IN_INTERFACE` |1040|ERROR|U|
| `USAGE_IS_NOT_INLINABLE` |1043|ERROR|U|
| `NON_LOCAL_RETURN_NOT_ALLOWED` |1044|ERROR|U|
| `NOT_YET_SUPPORTED_IN_INLINE` |1045|ERROR|U|
| `NOT_YET_SUPPORTED_IN_INLINE_WARNING` |1046|WARNING|U|
| `NOTHING_TO_INLINE` |1047|WARNING|U|
| `NULLABLE_INLINE_PARAMETER` |1048|ERROR|U|
| `RECURSION_IN_INLINE` |1049|ERROR|M: `ktc.compiler.common.recursion_in_inline`|
| `NON_PUBLIC_CALL_FROM_PUBLIC_INLINE` |1050|ERROR|M: `ktc.compiler.common.non_public_call_from_public_inline`|
| `NON_PUBLIC_INLINE_CALL_FROM_PUBLIC_INLINE` |1051|ERROR|M: `ktc.compiler.common.non_public_inline_call_from_public_inline`|
| `NON_PUBLIC_CALL_FROM_PUBLIC_INLINE_DEPRECATION` |1052|WARNING|U|
| `NON_PUBLIC_DATA_COPY_CALL_FROM_PUBLIC_INLINE` |1053|feature: `ErrorAboutDataClassCopyVisibilityChange`|F|
| `PROTECTED_CONSTRUCTOR_CALL_FROM_PUBLIC_INLINE` |1054|ERROR|M: `ktc.compiler.common.protected_constructor_call_from_public_inline`|
| `PROTECTED_CALL_FROM_PUBLIC_INLINE_ERROR` |1055|ERROR|M: `ktc.compiler.common.protected_call_from_public_inline_error`|
| `PRIVATE_CLASS_MEMBER_FROM_INLINE` |1056|ERROR|U|
| `SUPER_CALL_FROM_PUBLIC_INLINE` |1057|ERROR|U|
| `DECLARATION_CANT_BE_INLINED` |1058|ERROR|U|
| `DECLARATION_CANT_BE_INLINED_DEPRECATION` |1059|feature: `ProhibitInlineModifierOnPrimaryConstructorParameters`|F|
| `OVERRIDE_BY_INLINE` |1060|WARNING|U|
| `INVALID_DEFAULT_FUNCTIONAL_PARAMETER_FOR_INLINE` |1061|ERROR|U|
| `NOT_SUPPORTED_INLINE_PARAMETER_IN_INLINE_PARAMETER_DEFAULT_VALUE` |1062|ERROR|U|
| `REIFIED_TYPE_PARAMETER_IN_OVERRIDE` |1063|ERROR|U|
| `INLINE_PROPERTY_WITH_BACKING_FIELD` |1064|ERROR|M: `ktc.compiler.common.inline_property_with_backing_field`|
| `INLINE_PROPERTY_WITH_BACKING_FIELD_DEPRECATION` |1065|feature: `ProhibitInlineModifierOnPrimaryConstructorParameters`|F|
| `ILLEGAL_INLINE_PARAMETER_MODIFIER` |1066|ERROR|U|
| `INLINE_SUSPEND_FUNCTION_TYPE_UNSUPPORTED` |1067|ERROR|U|
| `INEFFICIENT_EQUALS_OVERRIDING_IN_VALUE_CLASS` |1068|WARNING|U|
| `INLINE_CLASS_DEPRECATED` |1069|WARNING|U|
| `LESS_VISIBLE_TYPE_ACCESS_IN_INLINE` |1070|feature: `ForbidExposingLessVisibleTypesInInline`|F|
| `LESS_VISIBLE_TYPE_IN_INLINE_ACCESSED_SIGNATURE` |1071|feature: `ForbidExposingLessVisibleTypesInInline`|F|
| `CALLABLE_REFERENCE_TO_LESS_VISIBLE_DECLARATION_IN_INLINE` |1072|feature: `ForbidExposingLessVisibleTypesInInline`|F|
| `CONTEXT_PARAMETER_MUST_BE_NOINLINE` |1073|ERROR|U|
| `CANNOT_ALL_UNDER_IMPORT_FROM_SINGLETON` |1076|ERROR|U|
| `PACKAGE_CANNOT_BE_IMPORTED` |1077|ERROR|U|
| `CANNOT_BE_IMPORTED` |1078|ERROR|U|
| `CONFLICTING_IMPORT` |1079|ERROR|U|
| `FUNCTION_TYPE_OF_TOO_LARGE_ARITY` |1080|ERROR|U|
| `K_SUSPEND_FUNCTION_TYPE_OF_DANGEROUSLY_LARGE_ARITY` |1081|WARNING|U|
| `OPERATOR_RENAMED_ON_IMPORT` |1082|WARNING|U|
| `TYPEALIAS_AS_CALLABLE_QUALIFIER_IN_IMPORT` |1083|feature: `ProhibitTypealiasAsCallableQualifierInImport`|F|
| `ILLEGAL_SUSPEND_FUNCTION_CALL` |1086|ERROR|U|
| `ILLEGAL_SUSPEND_PROPERTY_ACCESS` |1087|ERROR|U|
| `NON_LOCAL_SUSPENSION_POINT` |1088|ERROR|U|
| `ILLEGAL_RESTRICTED_SUSPENDING_FUNCTION_CALL` |1089|ERROR|U|
| `NON_MODIFIER_FORM_FOR_BUILT_IN_SUSPEND` |1090|ERROR|U|
| `MODIFIER_FORM_FOR_NON_BUILT_IN_SUSPEND` |1091|ERROR|U|
| `MODIFIER_FORM_FOR_NON_BUILT_IN_SUSPEND_FUN_ERROR` |1092|ERROR|U|
| `RETURN_FOR_BUILT_IN_SUSPEND` |1093|ERROR|U|
| `MIXING_SUSPEND_AND_NON_SUSPEND_SUPERTYPES` |1094|ERROR|U|
| `MIXING_FUNCTIONAL_KINDS_IN_SUPERTYPES` |1095|ERROR|U|
| `REDUNDANT_LABEL_WARNING` |1098|WARNING|U|
| `MULTIPLE_LABELS_ARE_FORBIDDEN` |1099|ERROR|U|
| `DEPRECATED_ACCESS_TO_ENUM_ENTRY_COMPANION_PROPERTY` |1102|WARNING|U|
| `DEPRECATED_ACCESS_TO_ENTRY_PROPERTY_FROM_ENUM` |1103|WARNING|U|
| `DEPRECATED_ACCESS_TO_ENTRIES_PROPERTY` |1104|WARNING|U|
| `DEPRECATED_ACCESS_TO_ENUM_ENTRY_PROPERTY_AS_REFERENCE` |1105|WARNING|U|
| `DEPRECATED_ACCESS_TO_ENTRIES_AS_QUALIFIER` |1106|WARNING|U|
| `DECLARATION_OF_ENUM_ENTRY_ENTRIES` |1107|feature: `ForbidEnumEntryNamedEntries`|F|
| `INCOMPATIBLE_CLASS` |1110|ERROR|U|
| `PRE_RELEASE_CLASS` |1111|ERROR|U|
| `IR_WITH_UNSTABLE_ABI_COMPILED_CLASS` |1112|ERROR|U|
| `BUILDER_INFERENCE_STUB_RECEIVER` |1115|ERROR|U|
| `BUILDER_INFERENCE_MULTI_LAMBDA_RESTRICTION` |1116|ERROR|U|
| `INVALID_VERSIONING_ON_NON_OPTIONAL` |1119|ERROR|U|
| `INVALID_VERSIONING_ON_NONFINAL_FUNCTION` |1120|ERROR|U|
| `INVALID_VERSIONING_ON_NONFINAL_CLASS` |1121|ERROR|U|
| `INVALID_VERSIONING_ON_LOCAL_FUNCTION` |1122|ERROR|U|
| `INVALID_VERSIONING_ON_ANNOTATION_CLASS` |1123|ERROR|U|
| `INVALID_DEFAULT_VALUE_DEPENDENCY` |1124|ERROR|U|
| `INVALID_NON_OPTIONAL_PARAMETER_POSITION` |1125|ERROR|U|
| `INVALID_VERSIONING_ON_RECEIVER_OR_CONTEXT_PARAMETER_POSITION` |1126|ERROR|U|
| `INVALID_VERSIONING_ON_VARARG` |1127|ERROR|U|
| `INVALID_VERSIONING_ON_VALUE_CLASS_PARAMETER` |1128|ERROR|U|
| `NON_ASCENDING_VERSION_ANNOTATION` |1129|ERROR|U|
| `VERSION_OVERLOADS_TOO_COMPLEX_EXPRESSION` |1130|ERROR|U|
| `COMPANION_BLOCK_MEMBER_EXTENSION` |1133|ERROR|U|
| `ILLEGAL_COMPANION_BLOCK` |1134|ERROR|U|
| `COMPANION_BLOCK_NESTED` |1135|ERROR|U|
| `ILLEGAL_COMPANION_BLOCK_MEMBER` |1136|ERROR|U|
| `COMPANION_EXTENSION_RECEIVER_WITH_TYPE_ARGUMENTS` |1137|ERROR|U|
| `COMPANION_EXTENSION_RECEIVER_IS_OBJECT` |1138|ERROR|U|
| `COMPANION_EXTENSION_RECEIVER_IS_TYPE_PARAMETER` |1139|ERROR|U|
| `COMPANION_EXTENSION_RECEIVER_ANNOTATED` |1140|ERROR|U|
| `COMPANION_EXTENSION_NULLABLE_RECEIVER` |1141|ERROR|U|

</details>

<details>
<summary>FIR JVM: 111 exact factory entries and dispositions</summary>

[Pinned factory source](https://raw.githubusercontent.com/JetBrains/kotlin/v2.4.21/compiler/fir/checkers/checkers.jvm/gen/org/jetbrains/kotlin/fir/analysis/diagnostics/jvm/FirJvmErrors.kt). Line numbers below refer to that exact file.

| Exact factory name | Source line | Declared severity / feature | Disposition |
|---|---:|---|---|
| `OVERRIDE_CANNOT_BE_STATIC` |52|ERROR|M: `ktc.compiler.jvm.override_cannot_be_static`|
| `JVM_STATIC_NOT_IN_OBJECT_OR_CLASS_COMPANION` |53|ERROR|T|
| `JVM_STATIC_NOT_IN_OBJECT_OR_COMPANION` |54|ERROR|M: `ktc.compiler.jvm.jvm_static_not_in_object_or_companion`|
| `JVM_STATIC_ON_NON_PUBLIC_MEMBER` |55|ERROR|M: `ktc.compiler.jvm.jvm_static_on_non_public_member`|
| `JVM_STATIC_ON_CONST_OR_JVM_FIELD` |56|ERROR|M: `ktc.compiler.jvm.jvm_static_on_const_or_jvm_field`|
| `JVM_STATIC_ON_EXTERNAL_IN_INTERFACE` |57|ERROR|M: `ktc.compiler.jvm.jvm_static_on_external_in_interface`|
| `INAPPLICABLE_JVM_NAME` |58|ERROR|M: `ktc.compiler.jvm.inapplicable_jvm_name`|
| `ILLEGAL_JVM_NAME` |59|ERROR|M: `ktc.compiler.jvm.illegal_jvm_name`|
| `FUNCTION_DELEGATE_MEMBER_NAME_CLASH` |60|ERROR|M: `ktc.compiler.jvm.function_delegate_member_name_clash`|
| `VALUE_CLASS_WITHOUT_JVM_INLINE_ANNOTATION` |61|ERROR|M: `ktc.compiler.jvm.value_class_without_jvm_inline_annotation`|
| `JVM_INLINE_WITHOUT_VALUE_CLASS` |62|ERROR|M: `ktc.compiler.jvm.jvm_inline_without_value_class`|
| `INAPPLICABLE_JVM_EXPOSE_BOXED_WITH_NAME` |63|ERROR|M: `ktc.compiler.jvm.inapplicable_jvm_expose_boxed_with_name`|
| `USELESS_JVM_EXPOSE_BOXED` |64|WARNING|M: `ktc.compiler.jvm.useless_jvm_expose_boxed`|
| `JVM_EXPOSE_BOXED_CANNOT_EXPOSE_SUSPEND` |65|ERROR|M: `ktc.compiler.jvm.jvm_expose_boxed_cannot_expose_suspend`|
| `JVM_EXPOSE_BOXED_REQUIRES_NAME` |66|ERROR|M: `ktc.compiler.jvm.jvm_expose_boxed_requires_name`|
| `JVM_EXPOSE_BOXED_CANNOT_BE_THE_SAME` |67|ERROR|M: `ktc.compiler.jvm.jvm_expose_boxed_cannot_be_the_same`|
| `JVM_EXPOSE_BOXED_CANNOT_BE_THE_SAME_AS_JVM_NAME` |68|ERROR|M: `ktc.compiler.jvm.jvm_expose_boxed_cannot_be_the_same_as_jvm_name`|
| `JVM_EXPOSE_BOXED_CANNOT_EXPOSE_OPEN_ABSTRACT` |69|ERROR|M: `ktc.compiler.jvm.jvm_expose_boxed_cannot_expose_open_abstract`|
| `JVM_EXPOSE_BOXED_CANNOT_EXPOSE_SYNTHETIC` |70|ERROR|M: `ktc.compiler.jvm.jvm_expose_boxed_cannot_expose_synthetic`|
| `JVM_EXPOSE_BOXED_CANNOT_EXPOSE_LOCALS` |71|ERROR|M: `ktc.compiler.jvm.jvm_expose_boxed_cannot_expose_locals`|
| `JVM_EXPOSE_BOXED_CANNOT_EXPOSE_REIFIED` |72|ERROR|M: `ktc.compiler.jvm.jvm_expose_boxed_cannot_expose_reified`|
| `JVM_EXPOSE_BOXED_CANNOT_EXPOSE_PRIVATE` |73|ERROR|M: `ktc.compiler.jvm.jvm_expose_boxed_cannot_expose_private`|
| `WRONG_TYPE_FOR_JAVA_OVERRIDE` |74|WARNING|M: `ktc.compiler.jvm.wrong_type_for_java_override`|
| `ACCIDENTAL_OVERRIDE_CLASH_BY_JVM_SIGNATURE` |75|ERROR|M: `ktc.compiler.jvm.accidental_override_clash_by_jvm_signature`|
| `IMPLEMENTATION_BY_DELEGATION_WITH_DIFFERENT_GENERIC_SIGNATURE` |76|feature: `ForbidImplementationByDelegationWithDifferentGenericSignature`|F|
| `NOT_YET_SUPPORTED_LOCAL_INLINE_FUNCTION` |77|ERROR|M: `ktc.compiler.jvm.not_yet_supported_local_inline_function`|
| `PROPERTY_HIDES_JAVA_FIELD` |78|WARNING|M: `ktc.compiler.jvm.property_hides_java_field`|
| `CONFLICT_VERSION_AND_JVM_OVERLOADS_ANNOTATION` |79|WARNING|M: `ktc.compiler.jvm.conflict_version_and_jvm_overloads_annotation`|
| `JAVA_TYPE_MISMATCH` |82|ERROR|M: `ktc.compiler.jvm.java_type_mismatch`|
| `RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS` |83|WARNING|M: `ktc.compiler.jvm.receiver_nullability_mismatch_based_on_java_annotations`|
| `RECEIVER_MUTABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS` |84|WARNING|M: `ktc.compiler.jvm.receiver_mutability_mismatch_based_on_java_annotations`|
| `TYPE_MISMATCH_BASED_ON_JAVA_ANNOTATIONS` |85|WARNING|M: `ktc.compiler.jvm.type_mismatch_based_on_java_annotations`|
| `NULLABILITY_MISMATCH_BASED_ON_EXPLICIT_TYPE_ARGUMENTS_FOR_JAVA` |86|WARNING|M: `ktc.compiler.jvm.nullability_mismatch_based_on_explicit_type_arguments_for_java`|
| `TYPE_MISMATCH_WHEN_FLEXIBILITY_CHANGES` |87|WARNING|M: `ktc.compiler.jvm.type_mismatch_when_flexibility_changes`|
| `JAVA_CLASS_ON_COMPANION` |88|WARNING|M: `ktc.compiler.jvm.java_class_on_companion`|
| `UNEXHAUSTIVE_WHEN_BASED_ON_JAVA_ANNOTATIONS` |89|WARNING|M: `ktc.compiler.jvm.unexhaustive_when_based_on_java_annotations`|
| `UPPER_BOUND_CANNOT_BE_ARRAY` |92|ERROR|M: `ktc.compiler.jvm.upper_bound_cannot_be_array`|
| `UPPER_BOUND_VIOLATED_BASED_ON_JAVA_ANNOTATIONS` |93|WARNING|M: `ktc.compiler.jvm.upper_bound_violated_based_on_java_annotations`|
| `UPPER_BOUND_VIOLATED_IN_TYPEALIAS_EXPANSION_BASED_ON_JAVA_ANNOTATIONS` |94|WARNING|T|
| `STRICTFP_ON_CLASS` |97|ERROR|M: `ktc.compiler.jvm.strictfp_on_class`|
| `SYNCHRONIZED_ON_ABSTRACT` |98|ERROR|M: `ktc.compiler.jvm.synchronized_on_abstract`|
| `SYNCHRONIZED_IN_INTERFACE` |99|ERROR|M: `ktc.compiler.jvm.synchronized_in_interface`|
| `SYNCHRONIZED_IN_ANNOTATION` |100|feature: `ForbidJvmAnnotationsOnAnnotationParameters`|F|
| `SYNCHRONIZED_ON_INLINE` |101|WARNING|M: `ktc.compiler.jvm.synchronized_on_inline`|
| `SYNCHRONIZED_ON_VALUE_CLASS` |102|feature: `ProhibitSynchronizationByValueClassesAndPrimitives`|F|
| `SYNCHRONIZED_ON_SUSPEND_ERROR` |103|ERROR|M: `ktc.compiler.jvm.synchronized_on_suspend_error`|
| `OVERLOADS_WITHOUT_DEFAULT_ARGUMENTS` |104|WARNING|M: `ktc.compiler.jvm.overloads_without_default_arguments`|
| `OVERLOADS_ABSTRACT` |105|ERROR|M: `ktc.compiler.jvm.overloads_abstract`|
| `OVERLOADS_INTERFACE` |106|ERROR|M: `ktc.compiler.jvm.overloads_interface`|
| `OVERLOADS_LOCAL` |107|ERROR|M: `ktc.compiler.jvm.overloads_local`|
| `OVERLOADS_ANNOTATION_CLASS_CONSTRUCTOR_ERROR` |108|ERROR|M: `ktc.compiler.jvm.overloads_annotation_class_constructor_error`|
| `OVERLOADS_PRIVATE` |109|WARNING|M: `ktc.compiler.jvm.overloads_private`|
| `DEPRECATED_JAVA_ANNOTATION` |110|WARNING|M: `ktc.compiler.jvm.deprecated_java_annotation`|
| `JVM_PACKAGE_NAME_CANNOT_BE_EMPTY` |111|ERROR|M: `ktc.compiler.jvm.jvm_package_name_cannot_be_empty`|
| `JVM_PACKAGE_NAME_MUST_BE_VALID_NAME` |112|ERROR|M: `ktc.compiler.jvm.jvm_package_name_must_be_valid_name`|
| `JVM_PACKAGE_NAME_NOT_SUPPORTED_IN_FILES_WITH_CLASSES` |113|ERROR|M: `ktc.compiler.jvm.jvm_package_name_not_supported_in_files_with_classes`|
| `POSITIONED_VALUE_ARGUMENT_FOR_JAVA_ANNOTATION` |114|ERROR|M: `ktc.compiler.jvm.positioned_value_argument_for_java_annotation`|
| `REDUNDANT_REPEATABLE_ANNOTATION` |115|WARNING|M: `ktc.compiler.jvm.redundant_repeatable_annotation`|
| `THROWS_IN_ANNOTATION` |116|feature: `ForbidJvmAnnotationsOnAnnotationParameters`|F|
| `JVM_SERIALIZABLE_LAMBDA_ON_INLINED_FUNCTION_LITERALS` |117|feature: `ForbidJvmSerializableLambdaOnInlinedFunctionLiterals`|F|
| `INCOMPATIBLE_ANNOTATION_TARGETS` |118|WARNING|M: `ktc.compiler.jvm.incompatible_annotation_targets`|
| `ANNOTATION_TARGETS_ONLY_IN_JAVA` |119|WARNING|M: `ktc.compiler.jvm.annotation_targets_only_in_java`|
| `INTERFACE_CANT_CALL_DEFAULT_METHOD_VIA_SUPER` |122|ERROR|M: `ktc.compiler.jvm.interface_cant_call_default_method_via_super`|
| `JAVA_CLASS_INHERITS_KT_PRIVATE_CLASS` |123|ERROR|M: `ktc.compiler.jvm.java_class_inherits_kt_private_class`|
| `LOCAL_JVM_RECORD` |126|ERROR|M: `ktc.compiler.jvm.local_jvm_record`|
| `NON_FINAL_JVM_RECORD` |127|ERROR|M: `ktc.compiler.jvm.non_final_jvm_record`|
| `ENUM_JVM_RECORD` |128|ERROR|M: `ktc.compiler.jvm.enum_jvm_record`|
| `JVM_RECORD_WITHOUT_PRIMARY_CONSTRUCTOR_PARAMETERS` |129|ERROR|M: `ktc.compiler.jvm.jvm_record_without_primary_constructor_parameters`|
| `NON_DATA_CLASS_JVM_RECORD` |130|ERROR|M: `ktc.compiler.jvm.non_data_class_jvm_record`|
| `NON_DATA_VALUE_CLASS_JVM_RECORD` |131|ERROR|M: `ktc.compiler.jvm.non_data_value_class_jvm_record`|
| `JVM_RECORD_NOT_VAL_PARAMETER` |132|ERROR|M: `ktc.compiler.jvm.jvm_record_not_val_parameter`|
| `JVM_RECORD_NOT_LAST_VARARG_PARAMETER` |133|ERROR|M: `ktc.compiler.jvm.jvm_record_not_last_vararg_parameter`|
| `INNER_JVM_RECORD` |134|ERROR|M: `ktc.compiler.jvm.inner_jvm_record`|
| `FIELD_IN_JVM_RECORD` |135|ERROR|M: `ktc.compiler.jvm.field_in_jvm_record`|
| `DELEGATION_BY_IN_JVM_RECORD` |136|ERROR|M: `ktc.compiler.jvm.delegation_by_in_jvm_record`|
| `JVM_RECORD_EXTENDS_CLASS` |137|ERROR|M: `ktc.compiler.jvm.jvm_record_extends_class`|
| `ILLEGAL_JAVA_LANG_RECORD_SUPERTYPE` |138|ERROR|M: `ktc.compiler.jvm.illegal_java_lang_record_supertype`|
| `JVM_RECORDS_ILLEGAL_BYTECODE_TARGET` |139|ERROR|M: `ktc.compiler.jvm.jvm_records_illegal_bytecode_target`|
| `JAVA_MODULE_DOES_NOT_DEPEND_ON_MODULE` |142|ERROR|T|
| `JAVA_MODULE_DOES_NOT_READ_UNNAMED_MODULE` |143|ERROR|T|
| `JAVA_MODULE_DOES_NOT_EXPORT_PACKAGE` |144|ERROR|M: `ktc.compiler.jvm.java_module_does_not_export_package`|
| `JVM_DEFAULT_WITHOUT_COMPATIBILITY_NOT_IN_ENABLE_MODE` |147|ERROR|M: `ktc.compiler.jvm.jvm_default_without_compatibility_not_in_enable_mode`|
| `JVM_DEFAULT_WITH_COMPATIBILITY_NOT_IN_NO_COMPATIBILITY_MODE` |148|ERROR|M: `ktc.compiler.jvm.jvm_default_with_compatibility_not_in_no_compatibility_mode`|
| `EXTERNAL_DECLARATION_CANNOT_BE_ABSTRACT` |151|ERROR|M: `ktc.compiler.jvm.external_declaration_cannot_be_abstract`|
| `EXTERNAL_DECLARATION_CANNOT_HAVE_BODY` |152|ERROR|M: `ktc.compiler.jvm.external_declaration_cannot_have_body`|
| `EXTERNAL_DECLARATION_IN_INTERFACE` |153|ERROR|M: `ktc.compiler.jvm.external_declaration_in_interface`|
| `EXTERNAL_DECLARATION_CANNOT_BE_INLINED` |154|ERROR|M: `ktc.compiler.jvm.external_declaration_cannot_be_inlined`|
| `NON_SOURCE_REPEATED_ANNOTATION` |157|ERROR|T|
| `REPEATED_ANNOTATION_WITH_CONTAINER` |158|ERROR|M: `ktc.compiler.jvm.repeated_annotation_with_container`|
| `REPEATABLE_CONTAINER_MUST_HAVE_VALUE_ARRAY_ERROR` |159|ERROR|M: `ktc.compiler.jvm.repeatable_container_must_have_value_array_error`|
| `REPEATABLE_CONTAINER_HAS_NON_DEFAULT_PARAMETER_ERROR` |160|ERROR|M: `ktc.compiler.jvm.repeatable_container_has_non_default_parameter_error`|
| `REPEATABLE_CONTAINER_HAS_SHORTER_RETENTION_ERROR` |161|ERROR|M: `ktc.compiler.jvm.repeatable_container_has_shorter_retention_error`|
| `REPEATABLE_CONTAINER_TARGET_SET_NOT_A_SUBSET_ERROR` |162|ERROR|M: `ktc.compiler.jvm.repeatable_container_target_set_not_a_subset_error`|
| `REPEATABLE_ANNOTATION_HAS_NESTED_CLASS_NAMED_CONTAINER_ERROR` |163|ERROR|M: `ktc.compiler.jvm.repeatable_annotation_has_nested_class_named_container_error`|
| `SUSPENSION_POINT_INSIDE_CRITICAL_SECTION` |166|ERROR|M: `ktc.compiler.jvm.suspension_point_inside_critical_section`|
| `INLINE_FROM_HIGHER_PLATFORM` |169|ERROR|M: `ktc.compiler.jvm.inline_from_higher_platform`|
| `INAPPLICABLE_JVM_FIELD` |172|ERROR|M: `ktc.compiler.jvm.inapplicable_jvm_field`|
| `INAPPLICABLE_JVM_FIELD_WARNING` |173|WARNING|M: `ktc.compiler.jvm.inapplicable_jvm_field_warning`|
| `IDENTITY_SENSITIVE_OPERATIONS_WITH_VALUE_TYPE` |174|WARNING|M: `ktc.compiler.jvm.identity_sensitive_operations_with_value_type`|
| `SYNCHRONIZED_BLOCK_ON_JAVA_VALUE_BASED_CLASS` |175|WARNING|M: `ktc.compiler.jvm.synchronized_block_on_java_value_based_class`|
| `SYNCHRONIZED_BLOCK_ON_VALUE_CLASS_OR_PRIMITIVE` |176|feature: `ProhibitSynchronizationByValueClassesAndPrimitives`|F|
| `JVM_SYNTHETIC_ON_DELEGATE` |177|ERROR|M: `ktc.compiler.jvm.jvm_synthetic_on_delegate`|
| `SUBCLASS_CANT_CALL_COMPANION_PROTECTED_NON_STATIC` |178|ERROR|M: `ktc.compiler.jvm.subclass_cant_call_companion_protected_non_static`|
| `CONCURRENT_HASH_MAP_CONTAINS_OPERATOR_ERROR` |179|ERROR|M: `ktc.compiler.jvm.concurrent_hash_map_contains_operator_error`|
| `SPREAD_ON_SIGNATURE_POLYMORPHIC_CALL_ERROR` |180|ERROR|M: `ktc.compiler.jvm.spread_on_signature_polymorphic_call_error`|
| `JAVA_SAM_INTERFACE_CONSTRUCTOR_REFERENCE` |181|ERROR|M: `ktc.compiler.jvm.java_sam_interface_constructor_reference`|
| `NO_REFLECTION_IN_CLASS_PATH` |182|WARNING|M: `ktc.compiler.jvm.no_reflection_in_class_path`|
| `SYNTHETIC_PROPERTY_WITHOUT_JAVA_ORIGIN` |183|WARNING|M: `ktc.compiler.jvm.synthetic_property_without_java_origin`|
| `JAVA_FIELD_SHADOWED_BY_KOTLIN_PROPERTY` |184|ERROR|M: `ktc.compiler.jvm.java_field_shadowed_by_kotlin_property`|
| `MISSING_BUILT_IN_DECLARATION` |185|ERROR|E|
| `DANGEROUS_CHARACTERS` |186|WARNING|M: `ktc.compiler.jvm.dangerous_characters`|

</details>

<details>
<summary>FIR Native/iOS: 46 exact factory entries and dispositions</summary>

[Pinned factory source](https://raw.githubusercontent.com/JetBrains/kotlin/v2.4.21/compiler/fir/checkers/checkers.native/gen/org/jetbrains/kotlin/fir/analysis/diagnostics/native/FirNativeErrors.kt). Line numbers below refer to that exact file.

| Exact factory name | Source line | Declared severity / feature | Disposition |
|---|---:|---|---|
| `THROWS_LIST_EMPTY` |34|ERROR|M: `ktc.compiler.native.throws_list_empty`|
| `INCOMPATIBLE_THROWS_OVERRIDE` |35|ERROR|M: `ktc.compiler.native.incompatible_throws_override`|
| `INCOMPATIBLE_THROWS_INHERITED` |36|ERROR|M: `ktc.compiler.native.incompatible_throws_inherited`|
| `MISSING_EXCEPTION_IN_THROWS_ON_SUSPEND` |37|ERROR|M: `ktc.compiler.native.missing_exception_in_throws_on_suspend`|
| `INAPPLICABLE_SHARED_IMMUTABLE_PROPERTY` |38|ERROR|M: `ktc.compiler.native.inapplicable_shared_immutable_property`|
| `INAPPLICABLE_SHARED_IMMUTABLE_TOP_LEVEL` |39|ERROR|M: `ktc.compiler.native.inapplicable_shared_immutable_top_level`|
| `INAPPLICABLE_THREAD_LOCAL` |40|ERROR|M: `ktc.compiler.native.inapplicable_thread_local`|
| `INAPPLICABLE_THREAD_LOCAL_TOP_LEVEL` |41|ERROR|M: `ktc.compiler.native.inapplicable_thread_local_top_level`|
| `INVALID_CHARACTERS_NATIVE_ERROR` |42|ERROR|M: `ktc.compiler.native.invalid_characters_native_error`|
| `REDUNDANT_SWIFT_REFINEMENT` |43|ERROR|M: `ktc.compiler.native.redundant_swift_refinement`|
| `INCOMPATIBLE_OBJC_REFINEMENT_OVERRIDE` |44|ERROR|M: `ktc.compiler.native.incompatible_objc_refinement_override`|
| `INAPPLICABLE_OBJC_NAME` |45|ERROR|M: `ktc.compiler.native.inapplicable_objc_name`|
| `INVALID_OBJC_NAME` |46|ERROR|M: `ktc.compiler.native.invalid_objc_name`|
| `INVALID_OBJC_NAME_CHARS` |47|ERROR|M: `ktc.compiler.native.invalid_objc_name_chars`|
| `INVALID_OBJC_NAME_FIRST_CHAR` |48|ERROR|M: `ktc.compiler.native.invalid_objc_name_first_char`|
| `EMPTY_OBJC_NAME` |49|ERROR|M: `ktc.compiler.native.empty_objc_name`|
| `INCOMPATIBLE_OBJC_NAME_OVERRIDE` |50|ERROR|M: `ktc.compiler.native.incompatible_objc_name_override`|
| `INAPPLICABLE_EXACT_OBJC_NAME` |51|ERROR|M: `ktc.compiler.native.inapplicable_exact_objc_name`|
| `MISSING_EXACT_OBJC_NAME` |52|ERROR|M: `ktc.compiler.native.missing_exact_objc_name`|
| `NON_LITERAL_OBJC_NAME_ARG` |53|ERROR|T|
| `INVALID_OBJC_HIDES_TARGETS` |54|ERROR|M: `ktc.compiler.native.invalid_objc_hides_targets`|
| `INVALID_REFINES_IN_SWIFT_TARGETS` |55|ERROR|M: `ktc.compiler.native.invalid_refines_in_swift_targets`|
| `SUBTYPE_OF_HIDDEN_FROM_OBJC` |56|ERROR|M: `ktc.compiler.native.subtype_of_hidden_from_objc`|
| `CANNOT_CHECK_FOR_FORWARD_DECLARATION` |57|ERROR|M: `ktc.compiler.native.cannot_check_for_forward_declaration`|
| `UNCHECKED_CAST_TO_FORWARD_DECLARATION` |58|WARNING|M: `ktc.compiler.native.unchecked_cast_to_forward_declaration`|
| `FORWARD_DECLARATION_AS_REIFIED_TYPE_ARGUMENT` |59|ERROR|M: `ktc.compiler.native.forward_declaration_as_reified_type_argument`|
| `FORWARD_DECLARATION_AS_CLASS_LITERAL` |60|ERROR|M: `ktc.compiler.native.forward_declaration_as_class_literal`|
| `TWO_OR_LESS_PARAMETERS_ARE_SUPPORTED_HERE` |61|ERROR|M: `ktc.compiler.native.two_or_less_parameters_are_supported_here`|
| `PROPERTY_MUST_BE_VAR` |62|ERROR|M: `ktc.compiler.native.property_must_be_var`|
| `MUST_NOT_HAVE_EXTENSION_RECEIVER` |63|ERROR|M: `ktc.compiler.native.must_not_have_extension_receiver`|
| `MUST_BE_OBJC_OBJECT_TYPE` |64|ERROR|M: `ktc.compiler.native.must_be_objc_object_type`|
| `MUST_BE_UNIT_TYPE` |65|ERROR|M: `ktc.compiler.native.must_be_unit_type`|
| `CONSTRUCTOR_OVERRIDES_ALREADY_OVERRIDDEN_OBJC_INITIALIZER` |66|ERROR|M: `ktc.compiler.native.constructor_overrides_already_overridden_objc_initializer`|
| `CONSTRUCTOR_DOES_NOT_OVERRIDE_ANY_SUPER_CONSTRUCTOR` |67|ERROR|M: `ktc.compiler.native.constructor_does_not_override_any_super_constructor`|
| `CONSTRUCTOR_MATCHES_SEVERAL_SUPER_CONSTRUCTORS` |68|ERROR|T|
| `CONFLICTING_OBJC_OVERLOADS` |69|ERROR|M: `ktc.compiler.native.conflicting_objc_overloads`|
| `INAPPLICABLE_OBJC_OVERRIDE` |70|ERROR|M: `ktc.compiler.native.inapplicable_objc_override`|
| `NATIVE_SPECIFIC_ATOMIC` |71|WARNING|M: `ktc.compiler.native.native_specific_atomic`|
| `IDENTITY_HASH_CODE_ON_VALUE_TYPE` |72|WARNING|M: `ktc.compiler.native.identity_hash_code_on_value_type`|
| `VARIADIC_FUNCTION_POINTERS_ARE_NOT_SUPPORTED` |73|ERROR|M: `ktc.compiler.native.variadic_function_pointers_are_not_supported`|
| `OVERRIDING_VARIADIC_OBJECTIVE_C_METHODS_IS_NOT_SUPPORTED` |74|ERROR|T|
| `CALLABLE_REFERENCES_TO_VARIADIC_C_FUNCTIONS_ARE_NOT_SUPPORTED` |75|ERROR|M: `ktc.compiler.native.callable_references_to_variadic_c_functions_are_not_supported`|
| `CALLABLE_REFERENCES_TO_VARIADIC_OBJECTIVE_C_METHODS_ARE_NOT_SUPPORTED` |76|ERROR|M: `ktc.compiler.native.callable_references_to_variadic_objective_c_methods_are_not_supported`|
| `STRING_AS_VARIADIC_OBJC_PARAM_IS_AMBIGUOUS` |77|ERROR|M: `ktc.compiler.native.string_as_variadic_objc_param_is_ambiguous`|
| `VARIADIC_OBJC_SPREAD_IS_SUPPORTED_ONLY_FOR_ARRAYOF` |78|ERROR|M: `ktc.compiler.native.variadic_objc_spread_is_supported_only_for_arrayof`|
| `VARIADIC_C_SPREAD_IS_SUPPORTED_ONLY_FOR_ARRAYOF` |79|ERROR|M: `ktc.compiler.native.variadic_c_spread_is_supported_only_for_arrayof`|

</details>

<details>
<summary>IR actualization: 10 exact factory entries and dispositions</summary>

[Pinned factory source](https://raw.githubusercontent.com/JetBrains/kotlin/v2.4.21/compiler/ir/ir.actualization/src/main/kotlin/org/jetbrains/kotlin/backend/common/actualizer/IrActualizationErrors.kt). Line numbers below refer to that exact file.

| Exact factory name | Source line | Declared severity / feature | Disposition |
|---|---:|---|---|
| `NO_ACTUAL_FOR_EXPECT` |27|ERROR|M: `ktc.compiler.ir.no_actual_for_expect`|
| `AMBIGUOUS_ACTUALS` |28|ERROR|M: `ktc.compiler.ir.ambiguous_actuals`|
| `EXPECT_ACTUAL_IR_MISMATCH` |29|ERROR|M: `ktc.compiler.ir.expect_actual_ir_mismatch`|
| `EXPECT_ACTUAL_IR_INCOMPATIBILITY` |32|ERROR|M: `ktc.compiler.ir.expect_actual_ir_incompatibility`|
| `ACTUAL_ANNOTATIONS_NOT_MATCH_EXPECT` |35|WARNING|A: `ktc.compiler.common.actual_annotations_not_match_expect`|
| `ACTUAL_ANNOTATION_CONFLICTING_DEFAULT_ARGUMENT_VALUE` |38|ERROR|M: `ktc.compiler.ir.actual_annotation_conflicting_default_argument_value`|
| `JAVA_DIRECT_ACTUAL_WITHOUT_EXPECT` |39|ERROR|M: `ktc.compiler.ir.java_direct_actual_without_expect`|
| `KOTLIN_ACTUAL_ANNOTATION_MISSING` |40|ERROR|M: `ktc.compiler.ir.kotlin_actual_annotation_missing`|
| `JAVA_DIRECT_ACTUALIZATION_DEFAULT_PARAMETERS_IN_EXPECT_FUNCTION` |42|ERROR|M: `ktc.compiler.ir.java_direct_actualization_default_parameters_in_expect_function`|
| `JAVA_DIRECT_ACTUALIZATION_DEFAULT_PARAMETERS_IN_ACTUAL_FUNCTION` |43|ERROR|M: `ktc.compiler.ir.java_direct_actualization_default_parameters_in_actual_function`|

</details>

## Exact Gitleaks scope reconciliation

Only 2 of 222 pinned configuration IDs have planned records here. The other 220 are explicitly **unmapped**, not selected rule coverage, and an actual loaded-provider manifest must be independently checked before any expansion. The 2 selected rules remain advisory, local and redacted; allowlisted fake/test data and public certificates/keys are legitimate negatives. Invalid/unloaded configuration is a provider error, not a clean scan. Do not transmit raw candidates in logs, SARIF/MCP/Telegram or validate them remotely.

<details>
<summary>All 222 pinned Gitleaks configuration IDs:2 planned, 220 unmapped</summary>

Source: [v8.30.1 config/gitleaks.toml](https://raw.githubusercontent.com/gitleaks/gitleaks/v8.30.1/config/gitleaks.toml).

| Exact provider ID | Disposition |
|---|---|
| `1password-secret-key` | Unmapped; no coverage counted |
| `1password-service-account-token` | Unmapped; no coverage counted |
| `adafruit-api-key` | Unmapped; no coverage counted |
| `adobe-client-id` | Unmapped; no coverage counted |
| `adobe-client-secret` | Unmapped; no coverage counted |
| `age-secret-key` | Unmapped; no coverage counted |
| `airtable-api-key` | Unmapped; no coverage counted |
| `airtable-personnal-access-token` | Unmapped; no coverage counted |
| `algolia-api-key` | Unmapped; no coverage counted |
| `alibaba-access-key-id` | Unmapped; no coverage counted |
| `alibaba-secret-key` | Unmapped; no coverage counted |
| `anthropic-admin-api-key` | Unmapped; no coverage counted |
| `anthropic-api-key` | Unmapped; no coverage counted |
| `artifactory-api-key` | Unmapped; no coverage counted |
| `artifactory-reference-token` | Unmapped; no coverage counted |
| `asana-client-id` | Unmapped; no coverage counted |
| `asana-client-secret` | Unmapped; no coverage counted |
| `atlassian-api-token` | Unmapped; no coverage counted |
| `authress-service-client-access-key` | Unmapped; no coverage counted |
| `aws-access-token` | Unmapped; no coverage counted |
| `aws-amazon-bedrock-api-key-long-lived` | Unmapped; no coverage counted |
| `aws-amazon-bedrock-api-key-short-lived` | Unmapped; no coverage counted |
| `azure-ad-client-secret` | Unmapped; no coverage counted |
| `beamer-api-token` | Unmapped; no coverage counted |
| `bitbucket-client-id` | Unmapped; no coverage counted |
| `bitbucket-client-secret` | Unmapped; no coverage counted |
| `bittrex-access-key` | Unmapped; no coverage counted |
| `bittrex-secret-key` | Unmapped; no coverage counted |
| `cisco-meraki-api-key` | Unmapped; no coverage counted |
| `clickhouse-cloud-api-secret-key` | Unmapped; no coverage counted |
| `clojars-api-token` | Unmapped; no coverage counted |
| `cloudflare-api-key` | Unmapped; no coverage counted |
| `cloudflare-global-api-key` | Unmapped; no coverage counted |
| `cloudflare-origin-ca-key` | Unmapped; no coverage counted |
| `codecov-access-token` | Unmapped; no coverage counted |
| `cohere-api-token` | Unmapped; no coverage counted |
| `coinbase-access-token` | Unmapped; no coverage counted |
| `confluent-access-token` | Unmapped; no coverage counted |
| `confluent-secret-key` | Unmapped; no coverage counted |
| `contentful-delivery-api-token` | Unmapped; no coverage counted |
| `curl-auth-header` | Unmapped; no coverage counted |
| `curl-auth-user` | Unmapped; no coverage counted |
| `databricks-api-token` | Unmapped; no coverage counted |
| `datadog-access-token` | Unmapped; no coverage counted |
| `defined-networking-api-token` | Unmapped; no coverage counted |
| `digitalocean-access-token` | Unmapped; no coverage counted |
| `digitalocean-pat` | Unmapped; no coverage counted |
| `digitalocean-refresh-token` | Unmapped; no coverage counted |
| `discord-api-token` | Unmapped; no coverage counted |
| `discord-client-id` | Unmapped; no coverage counted |
| `discord-client-secret` | Unmapped; no coverage counted |
| `doppler-api-token` | Unmapped; no coverage counted |
| `droneci-access-token` | Unmapped; no coverage counted |
| `dropbox-api-token` | Unmapped; no coverage counted |
| `dropbox-long-lived-api-token` | Unmapped; no coverage counted |
| `dropbox-short-lived-api-token` | Unmapped; no coverage counted |
| `duffel-api-token` | Unmapped; no coverage counted |
| `dynatrace-api-token` | Unmapped; no coverage counted |
| `easypost-api-token` | Unmapped; no coverage counted |
| `easypost-test-api-token` | Unmapped; no coverage counted |
| `etsy-access-token` | Unmapped; no coverage counted |
| `facebook-access-token` | Unmapped; no coverage counted |
| `facebook-page-access-token` | Unmapped; no coverage counted |
| `facebook-secret` | Unmapped; no coverage counted |
| `fastly-api-token` | Unmapped; no coverage counted |
| `finicity-api-token` | Unmapped; no coverage counted |
| `finicity-client-secret` | Unmapped; no coverage counted |
| `finnhub-access-token` | Unmapped; no coverage counted |
| `flickr-access-token` | Unmapped; no coverage counted |
| `flutterwave-encryption-key` | Unmapped; no coverage counted |
| `flutterwave-public-key` | Unmapped; no coverage counted |
| `flutterwave-secret-key` | Unmapped; no coverage counted |
| `flyio-access-token` | Unmapped; no coverage counted |
| `frameio-api-token` | Unmapped; no coverage counted |
| `freemius-secret-key` | Unmapped; no coverage counted |
| `freshbooks-access-token` | Unmapped; no coverage counted |
| `gcp-api-key` | Unmapped; no coverage counted |
| `generic-api-key` | Planned advisory `ktc.security.gitleaks.generic-api-key` |
| `github-app-token` | Unmapped; no coverage counted |
| `github-fine-grained-pat` | Unmapped; no coverage counted |
| `github-oauth` | Unmapped; no coverage counted |
| `github-pat` | Unmapped; no coverage counted |
| `github-refresh-token` | Unmapped; no coverage counted |
| `gitlab-cicd-job-token` | Unmapped; no coverage counted |
| `gitlab-deploy-token` | Unmapped; no coverage counted |
| `gitlab-feature-flag-client-token` | Unmapped; no coverage counted |
| `gitlab-feed-token` | Unmapped; no coverage counted |
| `gitlab-incoming-mail-token` | Unmapped; no coverage counted |
| `gitlab-kubernetes-agent-token` | Unmapped; no coverage counted |
| `gitlab-oauth-app-secret` | Unmapped; no coverage counted |
| `gitlab-pat` | Unmapped; no coverage counted |
| `gitlab-pat-routable` | Unmapped; no coverage counted |
| `gitlab-ptt` | Unmapped; no coverage counted |
| `gitlab-rrt` | Unmapped; no coverage counted |
| `gitlab-runner-authentication-token` | Unmapped; no coverage counted |
| `gitlab-runner-authentication-token-routable` | Unmapped; no coverage counted |
| `gitlab-scim-token` | Unmapped; no coverage counted |
| `gitlab-session-cookie` | Unmapped; no coverage counted |
| `gitter-access-token` | Unmapped; no coverage counted |
| `gocardless-api-token` | Unmapped; no coverage counted |
| `grafana-api-key` | Unmapped; no coverage counted |
| `grafana-cloud-api-token` | Unmapped; no coverage counted |
| `grafana-service-account-token` | Unmapped; no coverage counted |
| `harness-api-key` | Unmapped; no coverage counted |
| `hashicorp-tf-api-token` | Unmapped; no coverage counted |
| `hashicorp-tf-password` | Unmapped; no coverage counted |
| `heroku-api-key` | Unmapped; no coverage counted |
| `heroku-api-key-v2` | Unmapped; no coverage counted |
| `hubspot-api-key` | Unmapped; no coverage counted |
| `huggingface-access-token` | Unmapped; no coverage counted |
| `huggingface-organization-api-token` | Unmapped; no coverage counted |
| `infracost-api-token` | Unmapped; no coverage counted |
| `intercom-api-key` | Unmapped; no coverage counted |
| `intra42-client-secret` | Unmapped; no coverage counted |
| `jfrog-api-key` | Unmapped; no coverage counted |
| `jfrog-identity-token` | Unmapped; no coverage counted |
| `jwt` | Unmapped; no coverage counted |
| `jwt-base64` | Unmapped; no coverage counted |
| `kraken-access-token` | Unmapped; no coverage counted |
| `kubernetes-secret-yaml` | Unmapped; no coverage counted |
| `kucoin-access-token` | Unmapped; no coverage counted |
| `kucoin-secret-key` | Unmapped; no coverage counted |
| `launchdarkly-access-token` | Unmapped; no coverage counted |
| `linear-api-key` | Unmapped; no coverage counted |
| `linear-client-secret` | Unmapped; no coverage counted |
| `linkedin-client-id` | Unmapped; no coverage counted |
| `linkedin-client-secret` | Unmapped; no coverage counted |
| `lob-api-key` | Unmapped; no coverage counted |
| `lob-pub-api-key` | Unmapped; no coverage counted |
| `looker-client-id` | Unmapped; no coverage counted |
| `looker-client-secret` | Unmapped; no coverage counted |
| `mailchimp-api-key` | Unmapped; no coverage counted |
| `mailgun-private-api-token` | Unmapped; no coverage counted |
| `mailgun-pub-key` | Unmapped; no coverage counted |
| `mailgun-signing-key` | Unmapped; no coverage counted |
| `mapbox-api-token` | Unmapped; no coverage counted |
| `mattermost-access-token` | Unmapped; no coverage counted |
| `maxmind-license-key` | Unmapped; no coverage counted |
| `messagebird-api-token` | Unmapped; no coverage counted |
| `messagebird-client-id` | Unmapped; no coverage counted |
| `microsoft-teams-webhook` | Unmapped; no coverage counted |
| `netlify-access-token` | Unmapped; no coverage counted |
| `new-relic-browser-api-token` | Unmapped; no coverage counted |
| `new-relic-insert-key` | Unmapped; no coverage counted |
| `new-relic-user-api-id` | Unmapped; no coverage counted |
| `new-relic-user-api-key` | Unmapped; no coverage counted |
| `notion-api-token` | Unmapped; no coverage counted |
| `npm-access-token` | Unmapped; no coverage counted |
| `nuget-config-password` | Unmapped; no coverage counted |
| `nytimes-access-token` | Unmapped; no coverage counted |
| `octopus-deploy-api-key` | Unmapped; no coverage counted |
| `okta-access-token` | Unmapped; no coverage counted |
| `openai-api-key` | Unmapped; no coverage counted |
| `openshift-user-token` | Unmapped; no coverage counted |
| `perplexity-api-key` | Unmapped; no coverage counted |
| `pkcs12-file` | Unmapped; no coverage counted |
| `plaid-api-token` | Unmapped; no coverage counted |
| `plaid-client-id` | Unmapped; no coverage counted |
| `plaid-secret-key` | Unmapped; no coverage counted |
| `planetscale-api-token` | Unmapped; no coverage counted |
| `planetscale-oauth-token` | Unmapped; no coverage counted |
| `planetscale-password` | Unmapped; no coverage counted |
| `postman-api-token` | Unmapped; no coverage counted |
| `prefect-api-token` | Unmapped; no coverage counted |
| `private-key` | Planned advisory `ktc.security.gitleaks.private-key` |
| `privateai-api-token` | Unmapped; no coverage counted |
| `pulumi-api-token` | Unmapped; no coverage counted |
| `pypi-upload-token` | Unmapped; no coverage counted |
| `rapidapi-access-token` | Unmapped; no coverage counted |
| `readme-api-token` | Unmapped; no coverage counted |
| `rubygems-api-token` | Unmapped; no coverage counted |
| `scalingo-api-token` | Unmapped; no coverage counted |
| `sendbird-access-id` | Unmapped; no coverage counted |
| `sendbird-access-token` | Unmapped; no coverage counted |
| `sendgrid-api-token` | Unmapped; no coverage counted |
| `sendinblue-api-token` | Unmapped; no coverage counted |
| `sentry-access-token` | Unmapped; no coverage counted |
| `sentry-org-token` | Unmapped; no coverage counted |
| `sentry-user-token` | Unmapped; no coverage counted |
| `settlemint-application-access-token` | Unmapped; no coverage counted |
| `settlemint-personal-access-token` | Unmapped; no coverage counted |
| `settlemint-service-access-token` | Unmapped; no coverage counted |
| `shippo-api-token` | Unmapped; no coverage counted |
| `shopify-access-token` | Unmapped; no coverage counted |
| `shopify-custom-access-token` | Unmapped; no coverage counted |
| `shopify-private-app-access-token` | Unmapped; no coverage counted |
| `shopify-shared-secret` | Unmapped; no coverage counted |
| `sidekiq-secret` | Unmapped; no coverage counted |
| `sidekiq-sensitive-url` | Unmapped; no coverage counted |
| `slack-app-token` | Unmapped; no coverage counted |
| `slack-bot-token` | Unmapped; no coverage counted |
| `slack-config-access-token` | Unmapped; no coverage counted |
| `slack-config-refresh-token` | Unmapped; no coverage counted |
| `slack-legacy-bot-token` | Unmapped; no coverage counted |
| `slack-legacy-token` | Unmapped; no coverage counted |
| `slack-legacy-workspace-token` | Unmapped; no coverage counted |
| `slack-user-token` | Unmapped; no coverage counted |
| `slack-webhook-url` | Unmapped; no coverage counted |
| `snyk-api-token` | Unmapped; no coverage counted |
| `sonar-api-token` | Unmapped; no coverage counted |
| `sourcegraph-access-token` | Unmapped; no coverage counted |
| `square-access-token` | Unmapped; no coverage counted |
| `squarespace-access-token` | Unmapped; no coverage counted |
| `stripe-access-token` | Unmapped; no coverage counted |
| `sumologic-access-id` | Unmapped; no coverage counted |
| `sumologic-access-token` | Unmapped; no coverage counted |
| `telegram-bot-api-token` | Unmapped; no coverage counted |
| `travisci-access-token` | Unmapped; no coverage counted |
| `twilio-api-key` | Unmapped; no coverage counted |
| `twitch-api-token` | Unmapped; no coverage counted |
| `twitter-access-secret` | Unmapped; no coverage counted |
| `twitter-access-token` | Unmapped; no coverage counted |
| `twitter-api-key` | Unmapped; no coverage counted |
| `twitter-api-secret` | Unmapped; no coverage counted |
| `twitter-bearer-token` | Unmapped; no coverage counted |
| `typeform-api-token` | Unmapped; no coverage counted |
| `vault-batch-token` | Unmapped; no coverage counted |
| `vault-service-token` | Unmapped; no coverage counted |
| `yandex-access-token` | Unmapped; no coverage counted |
| `yandex-api-key` | Unmapped; no coverage counted |
| `yandex-aws-access-token` | Unmapped; no coverage counted |
| `zendesk-secret-key` | Unmapped; no coverage counted |

</details>

## Fetch evidence, licensing and maintenance

The following primary sources were fetched successfully on 2026-11-04. These are document/release/source observations, not runtime support. Moving documentation was reconciled against pinned release sources where available.

| Source | Evidence fetched |
|---|---|
| [Kotlin releases](https://kotlinlang.org/docs/releases.html) | Current release table and dates, 60,152 bytes. |
| [Detekt compatibility](https://detekt.dev/docs/introduction/compatibility/) | Current 2.0.0-alpha.6 table, 24,692 bytes. |
| [Detekt legacy compatibility](https://detekt.dev/docs/1.23.8/introduction/compatibility/) | 1.23.8 table and maintenance banner, 24,465 bytes. |
| [Detekt type resolution](https://detekt.dev/docs/gettingstarted/type-resolution/) | Full/light Analysis API and KMP task scope, 42,990 bytes. |
| [Detekt extension API](https://detekt.dev/docs/introduction/extensions/) | Plugins, tests, CLI/Gradle integration and pitfalls, 81,526 bytes. |
| [Detekt migration](https://detekt.dev/docs/next/introduction/migration/) | Moving next documentation, 105,652 bytes; recommendations must be reconciled against the pinned release. |
| [Detekt alpha release API](https://api.github.com/repos/detekt/detekt/releases/tags/v2.0.0-alpha.6) | Published time and prerelease status, 18,837 bytes. |
| [Detekt stable release API](https://api.github.com/repos/detekt/detekt/releases/latest) | v1.23.8 stable release metadata, 16,001 bytes. |
| [Detekt pinned tree](https://api.github.com/repos/detekt/detekt/git/trees/v2.0.0-alpha.6?recursive=1) | Untruncated release tree, SHA `401c64bf232db0dcb054a7cfd0ca5fed3a095bc6`; all eleven non-coroutine owned RuleSetProvider files fetched. |
| [Ktlint standard rules](https://ktlint.github.io/ktlint/1.8.0/rules/standard/) | Pinned official standard documentation, 784,945 bytes; latest redirect also checked separately. |
| [Ktlint experimental rules](https://ktlint.github.io/ktlint/1.8.0/rules/experimental/) | Experimental formatting scope, 36,544 bytes. |
| [Ktlint release API](https://api.github.com/repos/pinterest/ktlint/releases/latest) | Redirected to the transferred official repository; 1.8.0 release metadata, 13,335 bytes. |
| [Ktlint pinned tree](https://api.github.com/repos/ktlint/ktlint/git/trees/1.8.0?recursive=1) | Untruncated tree, SHA `c26a4ffd277863086538ec8befd1cb20fd0b63e0`; exact provider registry and all its individual rule sources fetched. |
| [Ktlint version catalog](https://raw.githubusercontent.com/ktlint/ktlint/1.8.0/gradle/libs.versions.toml) | Compiler/JDK build versions, 2,311 bytes. |
| [Common FIR factory source](https://raw.githubusercontent.com/JetBrains/kotlin/v2.4.21/compiler/fir/checkers/gen/org/jetbrains/kotlin/fir/analysis/diagnostics/FirErrors.kt) | 228,957 bytes; individual factory names and severities. |
| [JVM FIR factory source](https://raw.githubusercontent.com/JetBrains/kotlin/v2.4.21/compiler/fir/checkers/checkers.jvm/gen/org/jetbrains/kotlin/fir/analysis/diagnostics/jvm/FirJvmErrors.kt) | 29,582 bytes. |
| [Native FIR factory source](https://raw.githubusercontent.com/JetBrains/kotlin/v2.4.21/compiler/fir/checkers/checkers.native/gen/org/jetbrains/kotlin/fir/analysis/diagnostics/native/FirNativeErrors.kt) | 12,115 bytes. |
| [Common FIR messages](https://raw.githubusercontent.com/JetBrains/kotlin/v2.4.21/compiler/fir/checkers/src/org/jetbrains/kotlin/fir/analysis/diagnostics/FirErrorsDefaultMessages.kt) | 249,261 bytes; actionable diagnostic messages rather than inferred prose from IDs. |
| [JVM FIR messages](https://raw.githubusercontent.com/JetBrains/kotlin/v2.4.21/compiler/fir/checkers/checkers.jvm/src/org/jetbrains/kotlin/fir/analysis/diagnostics/jvm/FirJvmErrorsDefaultMessages.kt) | 33,439 bytes. |
| [Native FIR messages](https://raw.githubusercontent.com/JetBrains/kotlin/v2.4.21/compiler/fir/checkers/checkers.native/src/org/jetbrains/kotlin/fir/analysis/diagnostics/native/FirNativeErrorsDefaultMessages.kt) | 13,517 bytes. |

Additional successful primary evidence:

| Source | Evidence read |
|---|---|
| [Kotlin tagged source archive](https://codeload.github.com/JetBrains/kotlin/tar.gz/refs/tags/v2.4.21) |48,793,351 bytes; SHA256 `6ee2c2026ab8fdde96e6f14b84262dfa3ac22ea7510e82b42489d846735b9202`.13,755 relevant source/test files (16,051,435 bytes) extracted into temporary research storage and read; never compiled/executed. |
| [IR actualization factories/messages](https://raw.githubusercontent.com/JetBrains/kotlin/v2.4.21/compiler/ir/ir.actualization/src/main/kotlin/org/jetbrains/kotlin/backend/common/actualizer/IrActualizationErrors.kt) |10 exact factories, phase-specific renderer messages and nine independently owned mappings. |
| [Return annotations](https://raw.githubusercontent.com/JetBrains/kotlin/v2.4.21/libraries/stdlib/src/kotlin/annotations/ReturnValue.kt) and [moving checker docs](https://kotlinlang.org/docs/unused-return-value-checker.html) |Actual annotation packages/targets/SinceKotlin metadata and observed documentation disagreement; default checker disable/check/full modes. |
| [Feature diagnostic factory](https://raw.githubusercontent.com/JetBrains/kotlin/v2.4.21/compiler/frontend.common/src/org/jetbrains/kotlin/diagnostics/KtDiagnosticFactory.kt) |Feature factories derive emitted _WARNING/_ERROR names; base declaration IDs are not normalized coverage. |
| [Detekt return-value predicate](https://raw.githubusercontent.com/detekt/detekt/v2.0.0-alpha.6/detekt-rules-potential-bugs/src/main/kotlin/dev/detekt/rules/potentialbugs/IgnoredReturnValue.kt) |Exact glob/type configuration and resolution scope read; contractual adaptation required. |
| [Detekt postfix predicate](https://raw.githubusercontent.com/detekt/detekt/v2.0.0-alpha.6/detekt-rules-potential-bugs/src/main/kotlin/dev/detekt/rules/potentialbugs/UselessPostfixExpression.kt) |4,277 bytes; PSI-only predicate, not complete effect/liveness analysis. |
| [Konsist release](https://github.com/LemonAppDev/konsist/releases/tag/v0.18.1), [architecture assertions](https://docs.konsist.lemonappdev.com/writing-tests/architecture-assert) and [pinned license](https://raw.githubusercontent.com/LemonAppDev/konsist/v0.18.1/LICENSE) |Release API metadata, architecture DSL documentation and Apache-2.0 license; no tests run. Architecture link fetched again successfully during finalization. |
| [Gitleaks release](https://github.com/gitleaks/gitleaks/releases/tag/v8.30.1), [pinned config](https://raw.githubusercontent.com/gitleaks/gitleaks/v8.30.1/config/gitleaks.toml) and [pinned license](https://raw.githubusercontent.com/gitleaks/gitleaks/v8.30.1/LICENSE) |Exact release/predicates/configuration and MIT license; no scanner or credential check run. |
| [Kotlin visibility](https://kotlinlang.org/docs/visibility-modifiers.html), [source-set hierarchy](https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html) and [sharing sources](https://kotlinlang.org/docs/multiplatform/multiplatform-share-on-platforms.html) |Successful final primary-source fetches, 12,132/24,508/13,366 bytes respectively; actual graph/visibility contracts, not a forced architecture. |
| Kotlin/Native C/ObjC interop, memory manager and StableRef API; Java 25 AutoCloseable/FileInputStream/Runtime/Path/ObjectInputStream/SecureRandom; Kotlin use/AutoCloseable/assert/kotlin.test; OWASP named cheat sheets |Primary API/guidance read; per-record source URLs preserve exact attribution. Custom guidance is paraphrased rather than copied long documentation prose. No application trust/ownership/runtime guarantee is inferred from a link alone. |

GitHub's Kotlin API tree fetch encountered **403 rate limiting**; the pinned codeload archive supplied primary source/test evidence instead. Earlier 404s for obsolete pinterest.github.io Ktlint URLs, Detekt 2.x formatting docs and guessed migration/compiler paths are not supporting sources; canonical successful URLs replaced them. No automatic retry loop or guessed session context was used.

Pinned license provenance is recorded in [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md) and the repository's retained license files. Kotlin [LICENSE](https://raw.githubusercontent.com/JetBrains/kotlin/v2.4.21/license/LICENSE.txt), [NOTICE](https://raw.githubusercontent.com/JetBrains/kotlin/v2.4.21/license/NOTICE.txt), [COPYRIGHT](https://raw.githubusercontent.com/JetBrains/kotlin/v2.4.21/license/COPYRIGHT.txt) and [third-party applicability README](https://raw.githubusercontent.com/JetBrains/kotlin/v2.4.21/license/README.md) were read. Detekt's [pinned Apache-2.0 license](https://raw.githubusercontent.com/detekt/detekt/v2.0.0-alpha.6/LICENSE) and Ktlint's [pinned MIT license](https://raw.githubusercontent.com/ktlint/ktlint/1.8.0/LICENSE) were checked; untruncated pinned Detekt/Ktlint trees have no NOTICE paths. Ktlint copyright is 2019 Pinterest, Inc. and 2016–2019 Stanley Shyiko. Descriptions/examples copied from those licensed upstream sources retain per-rule attribution and repository notices. The archive/compiler/provider transitive distribution needs an artifact-specific SBOM/license review; top-level licenses do not establish all packaged dependency obligations. Reference-only OWASP/Kotlin-document guidance is paraphrased, not a claim to redistribute their entire documentation.

Remaining release gates are explicit: actual provider ID/configuration exports and alias checks; compiler transport/phase/snapshot fidelity; the 612 unmapped factory contracts and unknown compiler/plugin/backend IDs; feature-emission variants; active replacement of ignored/disabled/synthetic fixture seeds; Kotlin 2.4 parser and compiler/API compatibility; direct iOS semantic/backend/cinterop/export/test support; complete graph/ownership/trust contracts; suppression/baseline and immutable review behavior; real per-rule integration/adversarial/unknown/settings/regression tests; representative-project precision; measured incremental/full correctness and latency; packaged-artifact licensing. Catalog updates cannot silently enable new IDs or turn missing evidence into clean coverage.

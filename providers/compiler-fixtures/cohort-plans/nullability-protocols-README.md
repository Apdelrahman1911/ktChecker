# Nullability protocols: additive source-only cohort v1

This is an **unrun, unscheduled source plan**, not compiler evidence or ordinary
six-kind coverage. Its required selector is `nullability_protocols`. Neither the
ordinary manifest, runner, compiler map, catalog, existing authored inventory nor
historical evidence is changed by this addition.

## Exact bounded source scope

Six already-mapped common direct `ERROR` diagnostics each have five independently
stored Kotlin files: positive, negative, boundary, applicability and suppression.
The stable rule IDs are `ktc.compiler.common.` followed by the lowercase name.

| Diagnostic | Boundary plan | Applicability plan |
| --- | --- | --- |
| `UNSAFE_IMPLICIT_INVOKE_CALL` | Check a stable local nullable-function alias before invoking it. | Explicit safe `?.invoke()` with null handling. |
| `UNSAFE_INFIX_CALL` | Check the nullable receiver before an infix member call. | An infix extension expressly accepts a nullable receiver. |
| `UNSAFE_OPERATOR_CALL` | Check a nullable typealias receiver before the symbolic `+` call. | An operator extension expressly accepts a nullable receiver. |
| `UNSAFE_CALLABLE_REFERENCE` | Check the nullable receiver before forming a bound reference. | Use a typed unbound reference instead. |
| `ITERATOR_ON_NULLABLE` | Check the nullable range before the `for` loop. | A nullable-receiver `iterator` extension supplies a complete local protocol. |
| `UNEXPECTED_SAFE_CALL` | Use a legitimate non-null `this` receiver. | Use a legitimate nullable ordinary receiver, not `super`. |

The 30 files live in
`cohort-plans/nullability-protocols-cases/<diagnostic>/<kind>.kt`, relative to
`providers/compiler-fixtures`. They deliberately live **outside** the historical
`cases/` and `planned-kmp-cases/` roots, whose 278 files and byte inventory remain
unchanged. Each selected file already exists as independent UTF-8/LF bytes;
selection performs no generation, rewriting, concatenation or batching. The
suppression files include their exact file annotation and complete source body
at authoring time; no runtime annotation insertion is allowed.

The proposed source profile is Kotlin compiler **2.4.21**, JVM target **17**,
language **2.4** and API **2.4**, with these exact explicit flags:

```
-jvm-target 17 -language-version 2.4 -api-version 2.4
```

There are no additional checker/preview/contextual feature flags, plugins, Java
sources, user/test libraries or KMP/Native module roles. Matching trusted compiler
and Kotlin stdlib inputs would be a prerequisite for any later authorized JVM
execution. Android/desktop-JVM integration, Native/iOS, KMP, other targets and
other compiler versions are **not demonstrated**. Tagged source predicates and
seed markers support authoring choices only; Kotlin syntax and compiler outcomes
have not been tested by this source cut.

## Selection is not scheduling

The new `nullability_protocols.py` helper is standalone and read-only. It requires
the exact selector, verifies literal paths, 30 source SHA-256 pins, 12 proposed
source anchors, manifest/profile identities, fixed source metadata and preserved
baseline inventories. It selects by kind first, then the six IDs in the table
order. Manifest row/input reordering does not change that selection. Positions
1–30 are **source-selection positions**, not attempts, reservations or permits.
Its guards use exceptions and remain effective under Python `-O`.

A source-only inspection from the repository/source-review root is:

```
PYTHONDONTWRITEBYTECODE=1 python3 providers/compiler-fixtures/nullability_protocols.py --selector nullability_protocols
```

This command prints identities, hashes and explicit non-execution state, never
source bodies. It does not invoke a compiler/provider/process/native helper or
write any evidence, receipt, run directory or external latest sidecar. It has no
`all`, default or ordinary/supplemental runner route. A future executable selector,
START-capture support for this new directory, exact bounded sealed schedule and
integration review require separate lead authorization. None is supplied here.
No retries, replacements, resume or merge are authorized by source selection.

## Proposed outcomes, especially suppression

Positive inputs propose the exact selected error and a failed compiler task.
Negative, boundary and applicability inputs propose exit zero and selected-ID
absence in both raw and normalized diagnostics. All are **proposals, not observed
results**; every actual companion diagnostic and failure must be retained later.
The 12 pinned anchors are authored token positions, **not observed diagnostic
locations**.

All six suppression expectations stay **unknown/unvalidated**. Kotlin's
`Suppress` API documents compilation warnings; the tagged error-suppression
renderer warns that the behavior is **UNSPECIFIED and WILL NOT BE PRESERVED**.
Hard errors may remain, disappear, or produce compilation failure/companion
warnings. Even a hypothetical exit-zero suppression result does not validate
hard-error suppression or make this cohort clean, complete or qualified.

Initial records remain `execution_state=not_run`,
`executed_by_current_pair_harness=false`, `expected_outcome_matched=null` and
`qualification=none`. The helper reports zero compiler launches/reservations,
`executable_schedule=null`, `run_complete=false`, ordinary-six-kind completion
false and `qualified=false`. Positive/negative/boundary/applicability/suppression
is **not** the ordinary positive/negative/configuration/version/suppression/
regression inventory. Existing type-boundary supplemental execution results, if
produced independently, must remain in their own run/cohort tuple and cannot be
borrowed as evidence for these 30 files.

## Source-only arithmetic and explicit gaps

The captured reference baseline is 98 ordinary pairs, 16 planned unsupported
module graphs, 443 existing exact mappings and 278 Kotlin fixture files, including
18 initial type-boundary supplemental plans. The six new source plans add 30
standalone files, giving **308 authored Kotlin sources in this sparse source
copy**. They do not append the ordinary manifest. Only a later reviewed append
would make 104 ordinary pairs; that number is prospective, not current coverage.

`nullability-protocols-gap-inventory.json` enumerates all **329** remaining mapped
IDs before this source plan (common 202, JVM 76, IR 8, Native 43). If separately
approved source plans were appended, the source gap arithmetic would become
**323** (common 196, JVM 76, IR 8, Native 43), not an execution/qualification count.
The file also retains all **609** unmapped common/JVM primary factory rows as
explicit gaps, not newly emitted rule IDs or supported mappings. Its bounded
four-container ledger has 1,061 entries: 443 mapped, 612 unmapped, five excluded
contracts and one alias; it is not a complete plugin/backend catalog.
`COMPONENT_FUNCTION_ON_NULLABLE` is deferred because the catalog-selected seed
requires unmodeled name-based-destructuring flags, not declared impossible in all
configurations.

These are source-presence and manifest-arithmetic claims at the pinned baseline.
If preserved ordinary authoring bytes or counts legitimately advance, this v1
helper fails closed; they must not be replaced with its stale references.

## Review files and pure-Python controls

- `nullability-protocols-v1.json`: standalone six-ID/30-file plan, exact profile,
  source pins, proposed outcomes and initial non-execution fields.
- `nullability-protocols-source-evidence.json`: pinned Kotlin v2.4.21 factories,
  renderers, checker predicates, active tagged seed markers and API/docs scope.
  The already-cached public tagged source archive was fully SHA-256 checked;
  no network download or binary execution was needed. Full upstream text and
  the cached derived ledger are review references, not integration additions.
- `nullability-protocols-gap-inventory.json`: complete bounded mapped/unmapped
  gap bookkeeping without support or execution promotion.
- `../test_nullability_protocols.py`: 16 pure-Python source controls, covering
  exact identities/order/read-once hashes, proposed expectations, feature-free
  profile, unknown suppressions, preservation, gaps, tampering, read-only output
  and portable no-symlink paths. Historical 24 controls are preserved unchanged.

The immutable review freeze retains separate normal and optimized logs for all
40 unique Python cases. This verifies source contracts only: no Gradle/JVM,
compiler, native supervisor or provider execution, compiler emission evidence,
ordinary six-kind completion or qualification is claimed.

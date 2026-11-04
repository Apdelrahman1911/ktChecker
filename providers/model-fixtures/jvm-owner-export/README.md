# Fixed JVM owner fixture: intended-input classification

This fixture's existing owner driver exports a Gradle model and passes it through
the existing trusted importer. It **does not compile Kotlin**. All four imported
contexts remain unavailable, `fullProject=false`, and `qualified=false`; missing
project/friend outputs, variant gaps and compiler-plugin metadata issues remain.

The fixture-only helpers in `project-model` test sources add a separate,
nonexecuting classification for `:lib||main` / `:lib:compileKotlin`. A classified
plan is not an execution permit, complete compiler input closure, successful
compiler result, generated artifact, ABI identity or qualified runtime tuple.
No production importer, compiler/provider binding or argument-policy gate is
relaxed, and no additional process is launched by these helpers.

## Contracts and evidence lifecycle

* The original `input-contract.json` is unchanged, pinned by
  `sha256:ce8db3ddfcba040f71b3fa6177af540da743c2b44fedaded327816a1f03adb53`.
* The new `executable-input-contract.json` is independently authored and pinned by
  `sha256:5a085721f56fbee0a9b7a6826a421dc0fc4d234184c4679a3f73df53290e4a88`.
  It references the original contract, adds fourteen compiler-runtime and two
  compilation-classpath pins, and reuses the original seven scripting pins.
  The incoming model cannot enroll or update these pins.
* START captures the three helper sources and the new contract before export.
  An uncapturable contract disables the separate plan; its failure is retained
  in START evidence. A changed pin or failed plan preparation does not turn an
  otherwise valid original import into a plan-dependent import failure. Existing
  START/runtime freshness checks still reject drift in captured inputs.
* Before export, plan binding checks exact already-enrolled original seed and
  execution-home dependency copies and the complete selected owner JDK inventory.
  It adds no registry entries. Helper sources and the contract participate in
  START identity, **not** source-to-binary or loaded-memory attestation.
* Only after the existing trusted import does classification use that model,
  original request and the actual owner exporter's output directory. Preparation,
  classification and publication errors remain separate from the import result.
* The result envelope's `intendedInputPlan` identifies a separate
  `owner-lib-main-input-plan-<owner-execution-id>.json` beside the result. Without
  a trusted import, it records unavailability; without an execution ID a fresh
  owner UUID names that failure evidence. Publication uses `CREATE_NEW`, read-only
  permissions and anchored hash readback, and refuses to overwrite an old plan.
  Failure is reported as `publication_failed`. The plan is bounded to 1 MiB before
  serialization. It does not retroactively qualify historical exporter-only results.

`classified_intended_operands` means only that this exact grammar and the bound
file metadata matched. `rejected_intended_operands` carries a bounded role/token
denial; `unavailable_intended_operands` and `unavailable_without_trusted_import`
are not partial success. Every plan has `executionState="unavailable"` and
`qualified=false`. Original context statuses and issue kinds are retained.
JSON evidence cannot reconstruct owner captures, registries or execution rights.

## Exact thirteen-token grammar

| Index | Role / exact intended value |
| --- | --- |
| 0 | `-Xallow-no-source-files` |
| 1–2 | `-classpath`, ordered stdlib 2.4.21 and annotations 13.0 paths joined by POSIX `:` |
| 3–4 | `-d`, the owner-bound Kotlin classes output-tree path |
| 5–6 | `-jvm-target`, `21` |
| 7–8 | `-module-name`, the single scalar `owner-model-fixture:lib` |
| 9–10 | `-no-reflect`, `-no-stdlib` |
| 11 | One `-Xplugin=` token containing seven ordered paths joined by `,` |
| 12 | Captured `lib/src/main/kotlin/Library.kt`, 58 bytes |

Every token is classified once, and rendering with the original bindings gives
the original vector without rewriting it. Missing, extra, duplicated, reordered,
alternative or unknown tokens fail closed. No `-P`, `@argfile`, disabling flag or
language/API option is inserted. Nullable language/API fields retain their
meaning of compiler defaults tied to the pinned compiler. The module-name colon
is not a path-list separator. Resources remain captured but are not source operands.

The 24 file occurrences have distinct roles: one source, two compilation entries,
fourteen compiler-runtime entries and seven scripting entries. Shared stdlib,
script-runtime and annotations bytes retain separate occurrences; classpaths are
not merged, sorted, deduplicated or numerically renamed. The compiler runtime
retains reflect **1.6.10** and coroutines **1.8.0**, not guessed Kotlin 2.4.21 versions.
The selected JDK is a separate complete owner-manifest tree identity including
directories, files, executable `bin/java` and `java.home`; target `21` is not a JDK
identity. Report-selected paths, basenames, coordinates, sizes or digests cannot
replace any role's exact pins. There is no relocation implementation in this cut.

## Default scripting: reviewed requirements, not effective launch facts

Adapter `ktchecker/owner-default-scripting/jvm-2.4.21/1` describes **one**
`kotlin.scripting` plugin with seven ordered files. It records the reviewed K2
`CompilerPluginRegistrar` and `CommandLineProcessor` implementations separately
from the legacy `ComponentRegistrar` descriptor. Those are requirements associated
with the pinned bytes, not observations of executed services or loader origins.
Changing a service, template marker, manifest, archive index or member changes the
whole-file pin; no weaker name-only archive scan can authorize replacement bytes.

The adapter records required empty user definitions/classpaths/templates/resolver
settings and the actual enabled standard-definition, discovery and autoloading
defaults. It does not add disabling options to simplify closure. Missing
`kotlinPluginData` does not establish those effective defaults. Empty user resolver
settings do not prove an empty effective environment: `projectRoot` can be injected
from IntelliJ project `basePath`/`baseDir` independently of `-P`.

Pinned-source/binary review is not runtime proof. Default discovery reads classpath
template markers and enumerates the implementing JAR's siblings, with cwd fallback.
Compiler/plugin basenames, `kotlin.dist.path`, JDK multi-release interpretation,
parent-loader origins, negative lookups and closed fresh namespaces still matter.
A current directory listing is not historical prelaunch evidence. Conditional
ordinary-`.kt` behavior under the default provider does not establish arbitrary
host readsets, supplied caches or output effects.

All fifteen missing-fact roles remain explicit:

* KGP plugin input properties, script-definition files and script extensions;
* BTA compilation-operation fields, incremental/reference-index settings and
  renderer/logging/metrics settings;
* compiler entrypoint/services and compiler/plugin classloader origins;
* scripting host configuration, effective project root, discovery/fallback
  namespaces, auxiliary readset and cache/output effects;
* compiler-attempt freshness and compiler execution/derived output.

KGP reads script-definition files outside argv. BTA extracts source/destination
fields, clears destination, sets `KOTLINSCRIPT_EXTENSIONS`, reserializes arguments
and supplies other operation options. Therefore intended Gradle task argv is not
the observed BTA operation and not a direct K2 CLI `Services.EMPTY` invocation.
`DIRECT_K2_CLI_REPLAY` is an explicit **unsupported** semantics value, not a relabeling
of the retained export. No supplied boolean clears these unknowns.

## Output declarations only

The output role is Kotlin classes at
`gradle-build/10557602b86b871f06774e27e6fa94f53ef2b8e12a902e6d301ae14933f78cd8/classes/kotlin/main`.
The hash is SHA-256 of `:lib`. This is not `lib.jar`, resources, Java output, or an
`apiElements`/ABI proof. A classified plan leaves it **unobserved**, with no compiler
attempt ID, and never treats it as an input `ModelArtifact`.

`OwnerFixtureDerivedOutputTree` provides data-only output specs, declarations and
distinct unobserved/absent/empty-directory/tree states. Absence is not SHA-256 of
empty bytes. A declaration requires caller-declared absence, refuses present or
unknown initial state and source/output overlap, and performs no observation,
deletion, output creation or prelaunch preparation. Anchors in these DTOs are
caller data, not secured filesystem handles or freshness witnesses.

Declaration identity binds input-core, snapshot, recipe, attempt, root, fixed
producer/spec and initial absence data. Input-core includes the output spec, never
a future declaration/content digest, avoiding an identity cycle. Pure tree-data
validation requires sorted unique names, directory parents and file digests/sizes;
symlinks, special entries and escapes are rejected. Bounds are 1,024 entries, depth
32, 32 MiB/file, 64 MiB total file bytes and 1 MiB metadata.

No function accepts a process outcome or JSON success claim and produces a derived
tree certificate. A future executor would need independent owner launch settings,
fresh anchored namespaces/absence, same-attempt terminal-confirmed quiescent
completion and bounded no-follow post-run inventory. None is established here.
Derived files are not enrolled, producer artifacts are not filled, and no model
context is upgraded by declaration or classification.

## Test scope and limits

The three helper test classes cover the exact grammar, role/pin mutations, explicit
scripting gaps, START-pin comparison, unchanged unavailable contexts, evidence
bounds/publication and output declaration/topology denials. Their JDK/model/START
fixtures are explicitly synthetic metadata; the tests do not load a compiler or
plugin, launch Gradle/native processes, or turn synthetic `VerifiedProjectModel`
objects into actual imported evidence. Source-wiring checks are not execution
coverage. These tests cannot qualify any Linux or Apple runtime tuple.

START identities, read-only owner copies and output declarations do not attest
source-to-binary builds, loaded JVM memory, OS loaders/libraries or hostile
same-user isolation. All existing production execution and completeness gates
remain required.

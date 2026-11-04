# Verified bounded Gradle project model

`ProjectModelImporter` defaults to deny. `ModelImportRequest`, Gradle export JSON,
`fullProject` flags, configuration strings and exporter profiles are data, not
authority. A host issues a nonserialized single-use `ModelIngressPermit` only after
verifying the registered coherent capture, actual successful/quiescent pinned
exporter execution, immutable resolved artifacts and **complete bounded observed
model input domain**. Partial roots or self-consistent report digests do not satisfy
the last callback. Loading JSON cannot recreate `VerifiedProjectModel`.

The model retains actual Gradle modules, source/resource roots and byte memberships,
refinement/association edges, targets/compilations, ordered compiler classpaths,
resolved producer variants, compiler arguments/plugins/JDK inputs, generation
properties/files and executable configuration inputs. Unknown vocabulary and
uncaptured inputs are explicit gaps. Only a gap-free complete observed model can
yield a compilation context whose `fullProject` model bit is true. This bit is not
a semantic resolver, compiler success or release coverage certificate.

```kotlin
val model = ProjectModelImporter(hostIngressAuthority).import(exactRequest, reportBytes)
val context = model.context(actualCompilationId, ownerConfigurationDigest)
val impact = ProjectImpactPlanner().impact(previousVerifiedModel, model)
```

Impact uses OLD and NEW verified memberships and reverse dependency/refinement/
association edges. Source/resource body changes still reach every reverse consumer;
no API/body or negative-lookup optimization is inferred. Changed topology, roots,
configuration, toolchains, runtime identities or unknown model inputs widen the
obligations. A removed compilation remains `removed_unresolved` until separate
authenticated retirement evidence exists. No-op impact is not successful coverage
and cannot erase historical required review slots or their reuse proofs.

## Execution integration boundary

`AnalysisContext.classpath` represents normal compiler classpath only. Model digests
also bind friends, plugins, selected runtime, auxiliary files/artifacts and paths
embedded in actual compiler arguments. A kernel/compiler integration must explicitly
relocate or attest **all** of those executable inputs; a model context certificate
does not do that work. Admit all required compilation slots atomically rather than
certifying multiple targets with one scalar `AnalysisInput`.

The version-isolated exporter targets Kotlin Gradle plugin 2.4.21. It observes actual
Gradle/Kotlin APIs and resolved variants, never guesses producer `main` from names.
Android/composite/buildSrc and unavailable Native/toolchain vocabulary fail closed.
Configuration executes arbitrary project code: owner-selected process groups and
read-only same-UID materializations are cooperative isolation, not an OS sandbox.
No exporter/runtime tuple is qualified. The concrete known-fixture integration and
its current verification state are in `providers/model-fixtures/README.md`.

The owner fixture registry freezes every original runtime/seed directory and non-lock
file, including executable DSL cache classes. Before profile construction it derives
a fresh concrete execution home with bound initial topology, bytes, permissions and
independent source/copy identities. Only exact pre-enrolled dependency copies under
`caches/modules-2/files-2.1/` may supply model artifacts. Other copied metadata/DSL
caches are writable derivation scratch; newly generated native/DSL/transformed
outputs never become artifact authority, even with equal bytes. The final initial-copy
recheck is preparation, not execution evidence. Original byte freshness and the
execution-home map/dependency checks remain inseparable at subsequent verification;
there is no post-execution enrollment, input purging or failed-root reuse. Owner
manifest, contract and source reads are bounded before allocation and anchored/no-follow.
Manifest/contract/JDK release text is strictly UTF-8 decoded. The private owner
driver also binds authored native/build and JVM runtime sources plus the actual
installed JVM/native image at START. It checks that the actual runner completion's
terminal-derived confirmation and native identity match that capture, including
at importer freshness checks. Startup identity and read-only copies are not
source-to-binary, loaded-memory or host-runtime attestation; no later inspection
or serialized success claim can replace actual completion. Failed outcomes remain
local evidence, never importer authority.
Hosts without anchored reads remain unavailable; capability-specific test skips
and portable profile-path assertions are not successful model/runtime coverage.

The current 47-test model suite passed centrally on 2026-11-04 at 11:49 UTC,
with zero failures/errors/skips. Exact XML/logs and source evidence are retained in
`build/evidence/model-cli-composition/20261104-first/`; this does not claim the
separate CLI tests in that command passed. The historical 37-test model/config 35/35/
architecture 17/17 run at 11:15 UTC remains separately recorded under
`build/evidence/model-native-start/20261104-first/`. README-only status updates are
separate from both source checkpoints; synthetic completion/home tests are not
native execution or exporter/import proof.

The first actual exporter was terminal-confirmed and exited zero, but immutable-seed
mutation caused rejection. With the fresh-home contract, the second actual exporter
was `executed`, terminal-confirmed, exit zero, without failure flags/reasons and with
the exact START native identity; seed authority verification no longer rejected it.
The owner importer still rejected the exact compilation domain: the four actual
single-JVM target names were empty, unlike the pinned `jvm` expectations. No model or
model certificate was issued, and compiler/artifact/variant/API gaps remain. Raw
evidence is retained under `build/evidence/model-export/20261104-fresh-home/`.
Neither run qualifies a tuple or waives any required compilation obligation.

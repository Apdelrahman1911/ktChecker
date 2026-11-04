# Live JVM model → installed toolchain binding

**Implemented in source; uncompiled and untested as of this source freeze.** The
controller's historical 27 passing tests and compiler's historical 28 passes/one
Windows skip do not exercise this new bridge or a real Gradle-to-compiler
invocation. Forty-three new focused synthetic test methods have been authored, not
executed. No shared-contract, importer, or controller production rewrite allowance
was added. A joint current Snapshot/Capture/Config fence remains a separate,
subsequent requirement for any final action. Source review is not runtime evidence.

## Approved bounded implementation

Keep the imported context unchanged. Add a compiler-owned, nonserialized checked
binding from an actual `VerifiedProjectModel` to an actual `KotlinJvmToolchain`,
and require its exact per-attempt permit **in addition to** `ExecutionPermit`.
The CLI owner composes these capabilities through the existing preparation and
kernel-observer callbacks. Do not teach the assembler to rewrite contexts.

`provider-compiler` now depends explicitly on `project-model`; its checked factory
takes the live model type, not a decoded DTO or an owner-provided pair of digests.
The reverse dependency does not exist, so this direction creates no cycle. A
test-only `snapshot` dependency supports real registered overlay/importer objects
inside explicitly synthetic authority tests. Both additions were approved by the
lead. There is no public `matches=true` or `declaredDigest → installedDigest`
assertion accepted as model authority.

## Three different identities, not aliases

| Existing value | Meaning that must be retained |
| --- | --- |
| `ProjectModelIdentity.toolchainDigest` | Aggregate of all exported compilation IDs and their `ModelToolchain` declarations. |
| A verified compilation's `AnalysisContext.toolchainDigest` | Digest of that compilation's entire `ModelToolchain`: kind/version, ordered compiler artifacts, runtime file records, and compiler plugins. |
| `KotlinJvmToolchain.digest` | `ktchecker/kotlin-jvm-toolchain-3`: exact ordered installed compiler JAR identities, Java executable path/bytes, and whole selected runtime-image digest. |

The runtime image additionally covers directory entries (including empty ones),
layout, names, executable policy/bits, and file bytes. Exported `runtimeArtifacts`
are only regular-file records; their equality cannot prove the extra properties.
The model exporter can canonicalize aliases that the runtime-image checker rejects.
Consequently, neither digest equality nor rehashing one schema as another is the
bridge. The aggregate model toolchain digest must not be confused with the
per-compilation one either.

The legacy compiler path still rejects a nonnull mismatched installed digest.
The fixed model path instead requires its live checked permit, uses the verified
JVM platform/kind, and forbids all inherited qualification. Changing only the first
digest comparison would be incorrect.

## Typed compiler-owned installation view

Expose an immutable typed view of the **already captured** installation, rather
than parsing `KotlinJvmRuntimeImage.description()` or accepting an inventory JSON
as authority. Implemented data shape:

```kotlin
// Accessors return captured detached data, not capabilities or a fresh scan.
enum class JvmRuntimeEntryKind { FILE, DIRECTORY }
data class JvmRuntimeEntryView(
    val relativePath: String, val kind: JvmRuntimeEntryKind,
    val size: Long, val digest: String?,
    val executable: Boolean, val executableBits: Int?,
)
data class InstalledCompilerJarView(
    val ordinal: Int, val identity: ArtifactIdentity, val size: Long,
)
class JvmRuntimeImageView internal constructor(/* frozen fields */) {
    val root: Path
    val javaExecutable: Path
    val executableDigest: String
    val digest: String
    val javaVersion: String
    val osName: String
    val architecture: String
    val entries: List<JvmRuntimeEntryView>
}
class KotlinJvmInstallationView internal constructor(/* frozen fields */) {
    val toolchainDigest: String
    val runtime: JvmRuntimeImageView
    val compilerClasspath: List<InstalledCompilerJarView>
}
// KotlinJvmRuntimeImage.view(): JvmRuntimeImageView
// KotlinJvmToolchain.installationView(): KotlinJvmInstallationView
```

All collections and nested identities are detached/unmodifiable. The accessor
does not silently capture a new installation or change existing digest schemas.
The checked binding retains the actual toolchain object and invokes its whole
runtime/JAR verification; possession of the view or an equal digest is not enough.
Sizes must be captured and verified against the same installed JAR bytes, not
filled from an unrelated later directory listing.

The bridge additionally checks ordinary no-link compiler JAR ancestors before and
after whole installation verification, comparing available file keys during the
check. An equal-byte compiler-parent symlink does not preserve the anchored
model path relation. This narrow bridge check does not rewrite the legacy reader
or claim continuous parent-inode identity, hostile same-UID isolation, or transient
fully restored write detection.

## Minimal live API

The following implemented API has capability boundaries, not a new wire protocol:

```kotlin
// All constructors below are private/internal to the checked implementation.
// No Serializable, copy, fromJson, fromDigest, or caller-supplied acceptance predicate.
enum class KotlinJvmInputMode { LEGACY_EXPLICIT_INPUT, VERIFIED_MODEL }

class KotlinJvmModelBinding private constructor(/* live model + installation */) {
    val model: VerifiedProjectModel
    val compilationId: String
    val identity: JvmModelBindingIdentity // evidence, not authority

    companion object {
        fun verifyAndBind(
            model: VerifiedProjectModel,
            compilationId: String,
            installation: KotlinJvmToolchain,
            checkpoint: () -> Unit = {},
        ): KotlinJvmModelBinding
    }

    fun verifyInstallation(checkpoint: () -> Unit = {})

    // Called from retainForAdmission with the assembler's FINAL, unchanged input.
    // compilerRuleIds is the owner's exact installed coordinator route, not a
    // caller-selected replacement for the parent's full required rule inventory.
    fun bindPreparedInput(
        original: AnalysisInput,
        compilerRuleIds: List<String>,
        provider: KotlinJvmCompilerProvider,
        profiles: Map<String, TrustedExecution>, // complete map, not just compiler
    ): PreparedJvmModelInput
}

class PreparedJvmModelInput internal constructor(/* retained exact original */) {
    val original: AnalysisInput
    val originalAnalysisKey: String
    val compilerRuleIds: List<String>
    val compilerProfile: TrustedExecution
    val bindingIdentity: JvmModelBindingIdentity
    fun bindAttempt(
        kernelOriginal: AnalysisInput,
        kernelEffective: AnalysisInput,
        compilerChild: AnalysisInput,
        verifyFrozen: () -> Unit,
    ): KotlinJvmModelPermit
}

class KotlinJvmModelPermit internal constructor(/* exact attempt + live binding */) : AutoCloseable {
    val expectedAnalysisKey: String
    val bindingIdentity: JvmModelBindingIdentity
    fun accepts(input: AnalysisInput): Boolean // pure exact-identity lookup
    override fun close() // permanent per-attempt revocation
}
fun interface KotlinJvmModelAuthority {
    fun authorize(input: AnalysisInput): KotlinJvmModelPermit?
    // DENY is the default.
}
```

`KotlinJvmCompilerProvider` accepts `inputMode` and the additional default-deny
`modelAuthority`. Mode is fixed per installed provider instance, never selected
from the request's policies, toolchain digest, or profile flags. The legacy profile
overload rejects verified-model input; the model overload is:

```kotlin
provider.executionProfile(originalCompilerProjection, binding,
    timeoutMillis = 60_000, maxOutputBytes = 1_048_576)
```

This overload requires original model locations and no injected execution/output.
An archived child must use **`prepared.compilerProfile` verbatim**, not regenerate
an original-input profile from relocated JSON. Model provider identity is
`2.4.21/verified-model-1`; actual context/compiler-reported version comparisons
remain exactly `2.4.21`, not this decorated provider version.

The ordinary `ExecutionAuthority` is still mandatory: a correct model/installation
binding does **not** grant permission to run a process. Conversely, a generic
execution permit does not certify the imported model's compiler semantics.

The prepared-input factory validates the model-bearing fields against the live
binding, preserves model ingress policies, and freezes the entire final input and
exact compiler route. It recognizes only the controller's existing catalog,
activation, and execution policy additions; it is not a generic policy-stripping
normalizer. The final execution-plan digest must equal the entire frozen supplied
profile map, including noncompiler routes. It requires the actual provider to use the binding's installation
object and checks the profile against that provider's generated profile (including
its heap and execution bounds), not against `approved=true`. The prepared handle
retains that exact provider as well. The assembler independently continues
checking every field and both allowed preparation additions.

### Binding checks

The checked factory obtains `model.context(compilationId,
model.identity.configurationDigest)` itself. It must reject unavailable/incomplete
contexts, absent IDs, model issues, incomplete source scopes, and any attempt to
substitute a detached compilation/context DTO. It retains the live model object,
namespace, snapshot, ingress evidence, and exact compilation identity.

1. **Actual JVM only.** Require the exported `compilation.platform` and
   `toolchain.kind` to be exactly the supported JVM kind, with pinned Kotlin
   2.4.21 and a supported whole link-free JDK21 installation. An `android`,
   `desktop`, or `jvm` target *name* proves no platform. Native, Android, metadata,
   JS/Wasm, plugins, and unknown task vocabularies cannot enter through this path.
   A genuinely JVM custom target may retain its real name; do not rename it to fit
   the compiler's current target-label allowlist.
2. **Compiler JARs, by ordinal.** Require the actual installed `(absolute path,
   digest, size)` vector to equal the verified model compiler-artifact projection
   in order, with no additions, omissions, sorting, deduplication, or relocation.
   Model coordinates and installation labels such as `installed:<filename>` have
   different meanings: retain and bind both, rather than renaming either. The
   model's resolved compiler version and the owner's approved hash-pinned artifact
   inventory must agree; a JAR filename alone is not version authority. The actual
   compiler's reported version remains checked at execution.
3. **Complete runtime file projection.** Match every exported runtime artifact
   bijectively to a FILE entry of the explicitly selected runtime image by exact
   relative name, absolute path, bytes, and size. Require the pinned exporter
   vocabulary (`compilation-jdk:<relative>`), reject duplicates/unknown records,
   and reject any missing or extra file. Do not infer a JDK home from a few paths.
   Runtime file-list order is retained in the model identity but is not compiler
   classpath order. Directory/layout/permission facts remain separately bound to
   the complete installed descriptor, never attributed to the weaker model list.
4. **Compilation inputs.** Preserve the exact model dependency classpath,
   source-member order, scope, configuration, language/API facts, and compiler
   argument token list. Dependencies must be bounded regular JARs with exact
   model sizes/digests. Initially require no compiler plugins, friend artifacts,
   associated compilations, auxiliary artifacts, or auxiliary input files. The
   compilation must contain only Kotlin sources and consume no source-set
   refinement edges; common/actual or Java roles are not flattened by guessing.
   Unknown settings are rejection,
   not an invitation to reconstruct a likely command line.
5. **Actual invocation grammar.** Both the controller's grammar and the provider's
   validator must accept the original tokens **without changing them**. The
   provider currently rejects some flags/forms the controller accepts. Bind the
   original vector and the exact generated process profile, including fixed
   compiler isolation flags; do not claim to replay the Gradle task verbatim.
   V1 accepts only `-progressive`, `-Werror`, `-nowarn` and bounded paired
   `-language-version`, `-api-version`, `-jvm-target`, `-module-name` tokens. Exported
   language/API facts must agree with their original explicit argument tokens.

The binding identity separately records the per-compilation model toolchain
digest, installed toolchain digest, full runtime-image digest, full model/ingress
identity, compilation ID/context, exact compiler-artifact correspondence, ordered
dependency/membership/argument identities, and a versioned binding algorithm.
It is not a successful-rule certificate. The live object, not this evidence DTO,
is retained by the owner.

## Unchanged controller → profiles → exact archive attempt

1. Inside the existing `OwnerExecutionPreparer`, create the binding using the
   **same** request model and actual compilation ID. Reverify the owner's selected
   installation and source/artifact preparation. Reject mismatches; never replace
   `request.input.context.toolchainDigest`, classpath, arguments, or membership.
2. Generate the model-aware compiler profile. In addition to its current installed
   toolchain property, bind the new identity in a fixed profile argument such as
   `-Dktchecker.compiler.modelBinding=<digest>`. This is cache/approval evidence,
   not authority. The existing `withProfiles(...)` policy addition then binds the
   approved installed/runtime/model pair without changing the model context.
3. Keep `CapturedExecutionIdentity.toolchainDigest` equal to the original
   **per-compilation model** digest, as the assembler requires. Retain the installed
   digest and live binding separately in the owner record. The full per-client
   approval must cover that binding/profile pair, not just echo the model digest.
4. In `retainForAdmission(finalInput)`, retain the exact prepared model-input
   capability plus the client's captured policy/approval before returning the
   unchanged `CapturedExecutionIdentity`. Record keys include the full client
   binding and final input identity. Readiness failures still reject resolution.
5. On **every** `PreparedInputObserver` invocation, require `kernelOriginal` to
   equal that retained final input. Verify the kernel's private archive, then
   construct a new attempt permit for the exact compiler child and profile.
   Callbacks and filesystem work stay outside registry/kernel locks.

Only these original → effective changes are admitted:

- The materialized source root is the exact private archive root supplied by the
  trusted kernel callback. The entire snapshot and compilation membership remain
  unchanged; the callback verifies the whole manifest, not just compiler sources.
- Each dependency path changes to its corresponding private archive file **at the
  same ordinal**. Coordinates, bytes, model size, count, and order remain exact.
  Equal file digests never authorize an arbitrary path permutation or substitute
  artifact. The compiler's existing verified copy to `dependencies/<ordinal>.jar`
  remains a further checked step.
  The bridge is deliberately coupled to
  `ktchecker/kernel-regular-jar-archive-1`: source-root leaf `sources`, then
  `sources.parent/classpath/artifact-<ordinal>.jar`. This is not a generic
  relocation callback or a digest-only path search.
- The output parent is the exact owner-created directory for that attempt.
- The coordinator's child contains exactly the retained compiler-rule vector and
  generated execution profile. Every other field equals the full effective input.
  This provider projection cannot remove obligations from the parent plan.

All other fields, including model/context/toolchain/configuration identities,
policies, ordered arguments, baseline, and changed paths, are compared exactly.
Permits bind the complete child key, snapshot, materialization, output parent,
profile, and actual installation object. Another attempt, even with equal source
bytes, needs its own observer-issued permit. The owner removes attempt grants on
completion/cancellation and never restores them from JSON.

After acceptance, original source/dependency paths need not remain readable: the
kernel's verified frozen copies are the execution inputs. Do not accidentally
recheck their old locations while validating a recovered attempt. Compiler JARs
and the JDK are **not** covered by that archive; their selected installation must
still verify before preparation, immediately before execution, and afterward.
The bridge does not add arbitrary compiler/runtime relocation.

Sharing exact computation may reuse compatible prepared computation records;
each client must independently retain its admission policy and approval. The
observer has no client binding, so its lookup cannot invent another client's
authorization. After process restart, absent live model/binding capabilities mean
unavailable work until the owner reestablishes authority. Durable accepted-plan
retry is not durable capability deserialization.

## Compiler acceptance, completeness, and qualification

The implementation updates the three checks together:

- Imported-model requests require a matching live model permit; missing/invalid
  permits cannot fall back to a label or an installation digest copied into the
  request. The installed object and the permit's declared/installed identities
  must match exactly, in addition to the ordinary execution permit/profile checks.
  Existing limited non-model execution keeps its separate fixed legacy path; it
  does not become verified project-model ingress.
- The bridged JVM decision uses the verified platform/kind, not `JVM_TARGETS`.
  Completeness derives from the permit's complete verified context and exact
  original → archive binding, together with the existing source checks. It is
  never `context.toolchainDigest = installation.digest` by mutation or fiat.
- **Initially forbid legacy qualifications on the bridged path.** A correct
  binding may permit real diagnostics, but no existing `CompilerQualification`
  proves this new relation. Bridged rows remain unqualified and have no successful
  coverage certificate. Any later qualification API must explicitly bind both
  toolchain identities plus the binding algorithm, model/dependency/configuration,
  scope, and actual argument/profile tuple. Do not merely remove the old digest
  comparison or reuse a certificate over the installation identity alone.

Profile, evidence, cache, and implementation identities must change together when
the binding algorithm or route changes. The fixed profile binding property is not
optional merely because the same compiler executable is used. Neither successful
compilation nor a parent terminal status supplies a final live-current fence.

### Backwards-compatible limited Workbench label

Existing Workbench records intentionally use `ktchecker.model` for the literal
`owner-declared-single-compilation`. Only that exact label together with
`ktchecker.coverage=partial-project`, `fullProject=false`, **no other
`ktchecker.model*` key** and no model-binding profile property is allowed on the
legacy route. It remains explicitly ineligible for qualification. Unknown labels,
certificate presence (even empty), other model keys, full-project claims or a
binding property are not admitted by this exception. A fixed `VERIFIED_MODEL`
provider still requires its exact permit if an attacker replaces every field with
this legacy shape. The coordinator must retain the installed route/provenance and
never try another provider mode based on a DTO or after a denial.

## A real known-model proving run, without fictitious narrowing

The current `jvm-owner-export` contract describes **four** actual compilations
(`lib`/`consumer`, main/test), not a one-compilation model. All four must remain in
the parent. Friend/project-output directory or unsupported-argument obligations
stay explicit; executing one supported compilation does not discharge the others.
If the desired milestone is literally one context, prepare and independently
verify a genuinely one-compilation Gradle model. Disabling a task or selecting one
ID from a four-compilation export does not establish that domain.

Use the same explicitly owner-selected JDK home and ordered compiler JAR locations
for exporter and provider. The CLI's current filename-sorted directory installation
is not automatically the exporter's ordered compiler classpath. Do not sort the
model or copy its artifacts into a different installation and call the digest
renamed. Any explicit installation selection must be from independently approved
known bytes, not merely enroll whatever a model report names.

Before claiming the run is possible, inspect the **actual** successful pinned
exporter's `serializedCompilerArguments`. They may contain output/classpath/JDK,
plugin, or other tokens outside both current grammars. This proposal does not
strip them, replace them with a guessed minimal vector, or promise that the
current fixture is executable. A narrow verified invocation-lowering capability,
or an actually observed supported fixture configuration, would be a separately
reviewed prerequisite. Keep the original model identity and original argument
vector in either case. A digest bridge alone cannot solve this incompatibility.

The proving run must cover live importer capability → complete assembler plan →
registered parent → captured per-client approvals/profiles → exact kernel archive
attempt → real pinned compiler execution. Preserve actual process outcome and
diagnostic evidence. Report unsupported siblings and unqualified coverage honestly;
keep final actions denied. Synthetic factory/permit tests are additional negative
boundary evidence, not a substitute for this run.

## Authored tests and remaining verification

The new `KotlinJvmInstallationViewTest`, `KotlinJvmModelBindingTest`,
`KotlinJvmModelAttemptTest` and `KotlinJvmModelProviderTest` currently contain 43
test methods. They use synthetic JDK/compiler JAR bytes and synthetic exporter
execution/domain receipts, real registered snapshot/importer objects where
available, and a byte-copy simulation of the trusted archive observer. Unsupported
anchored model I/O causes an explicit JUnit assumption skip, not a success claim.
Every provider path with ordinary permission has an unconditional prelaunch or
runner-verifier fallback stop; the synthetic launcher must never execute.

These tests are authored but **not compiled or run**. Historical runtime/controller
tests do not supply their evidence. The focused negative coverage includes:

- Positive live binding with distinct unchanged model/installation digests;
  immutable views and no decoded-evidence/copy-based capability restoration.
- Ordered compiler JAR change, same-byte different-path substitution, extra or
  omitted JAR, size mismatch, wrong pinned version, selected-runtime mutation,
  and equal-byte compiler-parent alias substitution after model ingress.
- Missing/extra/duplicate JDK file; changed empty directory, executable bits and
  runtime policy. Existing whole-runtime tests separately exercise launcher,
  runtime library, alias and unsupported-layout cases; rerun them after changes.
- Native/Android/metadata dressed with a JVM-looking target label; unknown args,
  plugin/friend/auxiliary inputs; foreign model, configuration, snapshot, or scope.
- Fake profile property/`approved=true`/digest pair without either live permit;
  installation replacement and stale or wrong-attempt permit replay.
- Exact original → archive positional mapping, reordered duplicate-byte
  dependencies, changed membership/arguments/policies, and wrong output root.
- Another context's model gap prevents binding the healthy sibling; full parent
  catalog obligations stay intact while only the exact compiler child is narrowed.
- Existing limited Workbench-shaped legacy profiles retain their process-authority
  gate, with no qualification; replacing a retained model input with that shape
  cannot downgrade a fixed model provider.
- Legacy qualification cannot qualify bridged coverage; matching captured
  identities and successful real execution still cannot authorize a final action.

Actual per-client approval independence, whole parent execution, removed/retargeted
administrative obligations, actual pinned export argument compatibility, combined
controller/kernel/provider operation and the final live-current barrier require
their separate integration tests and proving run. These unit tests do not claim
those milestones.

## Implementation ownership and sequence

| Area | Approved owner/change |
| --- | --- |
| `provider-compiler` | Compiler owner: typed runtime/JAR view, checked live model binding, exact attempt permits, model-aware profile, all three acceptance/completeness/qualification changes, focused negative tests. |
| `project-model` | Model owner: confirm actual exporter/platform/argument vocabulary and known full fixture domain; no schema change in v1. Any lowering/export change needs separate approval. |
| `review-controller` | Engine/controller owner: keep current context-rewrite rejection and complete-vector semantics; add integration tests only after API agreement. No new production mutation allowance in v1. |
| CLI/Workbench/Coordinator | Lead: live owner-only composition, explicit installed selection, per-client retained approval/policy records, shared computation records, profile and attempt authority routing. No JSON model admission. |
| Shared contracts/kernel/settings | Lead/coordinated owners: no wire/kernel changes made for the bridge; module dependency and test wiring explicitly approved. |
| Final source/config/vector authority | Separate owner-reviewed Snapshot/Capture/Config barrier after bridge work; no final permission is part of this proposal. |

API/dependency ownership was agreed first; keep these file scopes and use the
lead's single coordinated build/test queue. Inspect failures before claiming a milestone.
Whole-runtime verification remains cooperative disk identity, not loaded-code,
OS/JNI, adversarial same-UID, or fully restored transient-write attestation.

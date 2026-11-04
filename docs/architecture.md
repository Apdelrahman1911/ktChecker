# ktChecker engine architecture

**Status: proposed, not implemented.** This repository is a planning skeleton. Nothing here establishes a working CLI, daemon, compiler adapter, incremental cache, MCP integration, sandbox, or performance result. The intended first release covers **Android, iOS, and Desktop/JVM** with a broad rule catalogue, low false positives, and review after **every completed patch, including review-driven fixes**. Implementation may be staged internally; publishing a narrow JVM-only prototype must not be described as meeting that release scope.

**Recommendation:** Kotlin/JVM analysis core and CLI, a durable per-user local broker, version-isolated compiler/provider workers, content-addressed immutable inputs, and conservative incremental analysis. Keep the host-facing bridge thin; the evidence-dependent MCP SDK/profile choice belongs in [protocol.md](protocol.md). At the researched baseline, a small TypeScript bridge can provide the current protocol while the engine stays JVM-based. Do not make a language-server connection, an accepted job ID, or a progress notification stand in for completed review or agent-visible delivery.

**Performance objective, unproven:** useful results in roughly **1–3 seconds for a defined warm, small-patch workload**. Snapshotting, queueing, and delivery count. Full project initialization, build-model changes, broad invalidation, cold workers, platform builds, and runtime checks may take longer; they remain visible coverage obligations, not silently skipped work.

## 1. Non-negotiable correctness contract

1. **Diagnose, do not edit.** External agents edit the application. ktChecker returns evidence, relevant locations, and repair guidance; it does not apply patches, reformat files, silently change Gradle configuration, or install dependencies as a side effect of review.
2. **One result names one immutable analysis identity.** No worker combines live files from different revisions or a new source tree with unverified old generated outputs/classpaths.
3. **Every accepted completed patch has durable coverage obligations.** “Latest wins” may change display priority but cannot erase an intermediate patch's promised review. Watcher events before patch completion may be coalesced; completed patches may not be silently dropped.
4. **Depth, coverage, and findings are separate.** A syntax scan is not type resolution; an empty result is not successful coverage; unsupported, blocked, failed, cancelled, or stale work is not a clean check.
5. **Incremental is an optimization of the same review.** For identical frozen inputs and policy, incremental and clean full analysis must produce equivalent findings **and coverage**. If the engine cannot prove a safe impact boundary, it widens the work, not the claim.
6. **Absence needs proof.** Cancellation, a newer patch, unrelated edits, changed rule configuration, or an expired worker must not resolve an existing finding.
7. **Trust and target prerequisites are explicit.** Gradle configuration executes project code; separate processes provide failure/ABI isolation, not a security sandbox. Missing Apple hosts, SDKs, generated inputs, or compatible providers remain visible.
8. **Review completion is not a pass.** A completely reviewed patch can contain findings. An external agent's final completion barrier must check current identity, required coverage, finding policy, and actual host delivery separately.

## 2. Technology and provider decisions

### 2.1 Language and packaging tradeoffs

| Candidate | Real advantage | Consequential cost | Decision |
|---|---|---|---|
| **Kotlin/JVM core** | Direct access to Kotlin PSI, Analysis API, Detekt, Gradle-facing adapters; one semantic ecosystem | Startup, compiler memory, distribution/JDK management, version-sensitive compiler integration | Initial core, workers, CLI, and durable broker |
| **Rust core** | Native startup, strong systems tooling, good scanner/resource-manager option | Still needs JVM workers for compiler-backed Kotlin semantics; a second implementation language and IPC surface do not remove compiler cost | Revisit only if measured broker/scanner overhead dominates; not a prerequisite |
| **TypeScript/Node core** | Strong host/MCP SDK integration; convenient thin bridges | Does not supply Kotlin semantics; moving scheduling/cache logic here adds a second large runtime without avoiding JVM workers | Thin protocol adapter where supported SDK maturity warrants it; not the analysis engine |
| **.NET core** | Capable cross-platform service/runtime, strong async/diagnostic tooling | Kotlin compiler ecosystem still out of process; no demonstrated project-specific advantage to offset duplicate deployment/interop | Viable alternative, not preferred for this Kotlin-centric engine |
| **Python core** | Fast research, corpus/harness automation, simple process orchestration | No compiler integration advantage; another production environment/protocol boundary; hot paths would still need JVM/native implementation | Research and test tooling, not the first production engine |

Do not infer the analyzer's platform support from its implementation language. A JVM process can inspect iOS source; that does not mean it has resolved Native symbols or run an iOS test.

Use a pinned, supported broker JDK and declare its minimum in the release manifest; certify provider/Gradle JDKs **separately**. Do not force the analyzed application to adopt the broker's Kotlin or JDK version. A trimmed `jlink` image is a packaging option, not a promise to eliminate compiler memory or project toolchains ([E16](research/engine-sources.md#e16)). Native Image is an experiment only after profiling: compiler/plugin reflection, resource discovery, and version loading need compatibility work ([E17](research/engine-sources.md#e17)). A native launcher cannot make the underlying semantic work disappear.

### 2.2 Hybrid compiler/provider strategy

Use a small provider interface whose durable output is **owned, versioned facts and diagnostics**, not compiler objects:

- `capabilities`: exact provider build, compiler artifacts, supported language/target/model combinations, available depth, limitations, required host/toolchains.
- `analyze`: immutable analysis ID, scope/obligation IDs, cancellation lease, rule/config digests, fact requirements; no live workspace path reads.
- `result`: source spans tied to content digests, semantic facts/evidence, findings, explicit checked/unknown scopes, dependency certificates, telemetry.
- A provider must report missing resolution and unsupported constructs, not turn them into high-confidence violations or empty success.

| Layer | Proposed provider | What it must not claim |
|---|---|---|
| Source/syntax | Version-pinned Kotlin PSI parser and audited Detekt light checks; separate parsers for supported config/resource formats | Regex/name matching or a PSI visitor does not prove overload, receiver, nullability, coroutine ownership, or platform type facts |
| JVM/Android semantics | Version-matched Analysis API adapter; audited Detekt full checks where prerequisites and compiler compatibility are certified | A default Detekt CLI run is light; Detekt's compiler libraries cannot be replaced with the project's versions arbitrarily |
| Common/KMP/Native semantics | Dedicated, pinned standalone/compiler adapter for common metadata, source-set refinement, KLIB/Native types, and expect/actual contexts | Detekt's documented KMP type-resolution tasks cover JVM/Android, not Native; Native semantics are a **release-critical feasibility gate** |
| Cross-module analysis | ktChecker-owned fact/summary graph above certified compiler adapters | Compiler incremental compilation or ABI hashes alone do not prove rule-level soundness |
| Build/platform/runtime evidence | Explicitly authorized matching Gradle/compiler/lint/test providers, potentially on a designated compatible host | A local source check is not a successful build, device test, simulator run, or desktop packaging check |

The official Analysis API is a credible semantic foundation, but standalone integration and the platform interface remain unstable; PSI is not formally a stable API either ([E02](research/engine-sources.md#e02), [E03](research/engine-sources.md#e03)). Prefer supported semantic endpoints; isolate unavoidable unstable APIs behind a documented adapter exception. Do not build a public rule API directly on FIR internals. Native/KLIB, compiler plugins, source-set topology, partial code, and session update behavior require executable spikes before committing to coverage/performance claims.

**Observed version trap:** Kotlin's release page names **2.4.21**; Detekt's **2.0.0-alpha.6** compatibility row names **Kotlin 2.4.10**. Neither a Kotlin 2.x label nor successful dependency resolution establishes compatibility. Alpha status is an additional release risk, not silently equivalent to stable. A legacy provider may be offered only for an explicitly certified older matrix; do not silently downgrade semantics for a modern project ([E01](research/engine-sources.md#e01), [E05–E07](research/engine-sources.md#e05)).

### 2.3 Process topology

```text
external editor/agent -> completed-patch adapter / CLI / MCP bridge
                                      |
                  per-user broker: admission + durable patch ledger
                                      |
         immutable source CAS -> model resolver/importer -> analysis identity
                                      |
                 dependency graph + result cache + bounded scheduler
                          /             |               \
                 syntax worker   compiler workers   authorized build/host workers
                          \             |               /
                   validated results + coverage + finding lifecycle
                                      |
                       durable delivery outbox -> host bridge
```

- The broker has no compiler/IntelliJ/Detekt classes on its application classpath. Its protocol is independent of their binary APIs.
- A worker is keyed by **security namespace, workspace/worktree, provider artifact set, compiler/runtime tuple, adapter schema, and target/model family**. Incompatible stacks are never loaded into one application classloader. Project dependencies supply analyzed symbols, not replacements for provider implementation JARs ([E07](research/engine-sources.md#e07)).
- Default: one active analysis transaction per worker/session. No concurrent mutation while another analysis reads that session. Different compatible workers may run concurrently within resource limits. Reuse an in-memory session across snapshots only through a certified invalidation adapter; otherwise rebuild the session while keeping the JVM warm.
- `KaSession`, symbols, types, and PSI stay within provider lifetime rules. Extract detached DTOs within read actions; do not serialize/cross-cache compiler objects. Symbol pointers are transient adapter aids, may fail restoration, and are not cross-version persistent IDs ([E04](research/engine-sources.md#e04)).
- CLI invocations connect to a compatible local broker; a one-shot mode uses the same engine and result schema. An incompatible broker protocol gets a separate versioned endpoint or a visible migration, not silent state reuse.
- Run one broker per OS user/security domain with authenticated owner-only local IPC. Multiple worktrees of the same Git repository have separate live state; a developer's checkout is not another developer's analysis session. Sharing dependency blobs is optional within an authorized namespace; no cross-user source/findings cache by default.
- Child/Gradle/Kotlin daemons, native tools, and detached descendants count toward resource budgets. Own their process groups or equivalent job containment; never kill unrelated user Gradle daemons to enforce a ktChecker timeout.

## 3. Immutable snapshots and identity

### 3.1 Identities are layered, not just a Git SHA

Use canonical, length-delimited/versioned encoding for hashes. Store the full descriptors beside digests. The following are conceptual engine fields; [protocol.md](protocol.md) owns wire naming and negotiation.

| Identity | Required contents |
|---|---|
| `workspaceId` / `worktreeId` | Owner/security namespace, registered root IDs and path mappings, actual worktree identity; linked `.git` files and shared Git object stores do not collapse distinct checkouts |
| `patchId` | Unique completed-edit event with declared predecessor/branch lineage; repeated submission must be idempotent; the same ID with different bytes is a conflict |
| `sourceSnapshotId` | Full approved input manifest: logical paths, entry kinds, raw byte digests, relevant modes, symlink descriptors/approved targets, root mappings, capture provenance and explicit exclusions; Git state is provenance, not content identity |
| `modelId` | Effective compilation/source-set graph, selected targets/variants, ordered options, roots, friend/refinement/dependency relationships, frozen classpath/KLIB/generated-output manifests, model-importer version and input-closure/provenance status |
| `toolchainId` | Actual compiler/provider/plugin/JDK/SDK/Native toolchain artifact fingerprints and target triple/options; version strings alone are insufficient |
| `policyDigest` | Rule-pack implementation and schema versions, per-rule configuration, suppression/baseline/exclusion policy, required target/depth matrix, evidence-catalogue version |
| `analysisId` | Hash of the source, model, toolchain, policy and engine/adapter schema descriptors; immutable binding for any result and coverage certificate |

Hash raw source bytes, not normalized whitespace or timestamps. Preserve the encoding/line ending mapping used for diagnostics. Logical paths are significant for packages, resource lookup, script semantics, generated names, and suppressions. Detect case/Unicode normalization collisions when materializing on another filesystem rather than silently merging files. Where a tool genuinely observes absolute paths or file metadata, include them in its input contract or mark that work nonportable/noncacheable.

### 3.2 What must be captured

- Actual tracked **working-tree bytes**, including staged and unstaged edits, and all relevant untracked inputs. The Git index/HEAD alone omits the code an agent just wrote. Unsaved buffers are checked only if their immutable bytes are explicitly included; otherwise disclose that they are outside scope. A failed edit tool that left partial writes still creates a changed-state review obligation. Git status/watch events are discovery hints, not an input-completeness oracle.
- Deletion is absence in the full manifest plus an explicit diff tombstone. A rename is delete/add for correctness; similarity is optional UI information. Copy, move, permission, file-kind, package, and case-only changes may invalidate consumers.
- Source sets, Java sources, scripts, resources/manifests, native headers/definition files, schema/config inputs, generated Kotlin/Java/metadata, and test inputs when relevant. `.gitignore` is not authority to exclude a generated source or other actual compilation input.
- Settings/build scripts, convention plugins, `buildSrc`, included/composite builds, version catalogues, lockfiles, verification metadata, wrapper descriptors, compiler/plugin configuration, and approved environment/toolchain inputs.
- Generated outputs are included only with content manifests and provenance connecting them to **this** generator input set. Their mere presence in `build/` does not prove freshness. A missing/outdated output blocks dependent semantics; it does not authorize running a generator.
- Repository paths can lead outside the checkout. Register each off-root source/dependency/composite root explicitly; capture its immutable content under a separate root ID and include it in the same consistency boundary. Do not recursively follow arbitrary symlinks, device files, FIFOs, sockets, or secrets. Reject cycles/escapes and report omitted required roots.
- Dependency artifacts are read from sealed, content-addressed copies, not a mutable Gradle cache path that may change during analysis. Hash artifact contents and ordering, including duplicate-resolution precedence. Copying with mutable hard links is not sealing.

A practical input-scope policy excludes large tool caches and protected files, but exclusions remain explicit. If an importer/provider discovers a required input outside that captured scope, the snapshot is **incomplete for that obligation**. Never fetch live missing inputs halfway through and label the combination as the old patch. Request the original producer's immutable input or a newly sealed patch; an unrecoverable intermediate snapshot remains visibly blocked/failed, not reviewed.

### 3.3 Coherence: watchers and double scans are not enough

Accept exact snapshot provenance from one of:

1. **Producer-owned immutable manifest:** an editor/wrapper supplies a complete base manifest plus an atomic completed-edit batch with immutable content blobs. Validate all blobs and the resulting full manifest. It defines exactly what is being reviewed; matching it to a physical workspace is a separate assertion.
2. **Coordinated capture:** every relevant writer participates in a workspace edit lease/epoch. The completed-patch hook holds the exclusive freeze through full manifest capture over all approved roots; no next patch starts until blobs are sealed. Build/generator writers participate too. The manifest can then be analyzed after the lease is released.
3. **A true immutable filesystem/VCS snapshot:** exported committed objects plus an immutable dirty overlay, or an OS snapshot spanning all inputs. A writable Git worktree or an uncoordinated set of per-volume snapshots is not this.

Before/after size/mtime checks, two full hash passes, or a quiet watcher interval can detect many races, **but cannot rule out ABA changes or a tree assembled from states that never coexisted**. A watcher overflow forces discovery/rescan, not a claim of atomicity. Arbitrary same-user processes may ignore advisory locks; strong mode needs actual isolation/cooperation, not just a lock file. Uncoordinated live capture is marked `bestEffort`/unknown and cannot satisfy an exact-current-snapshot final gate.

Each result exposes capture mode, participating roots, source epoch, exclusions, and uncertainty. Beginning a new edit invalidates “current” status immediately if the host provides edit-start events; otherwise a watcher flags possible drift. The final barrier reseals/compares under the same exact-capture contract. No watcher can guarantee that a result remains current after the next edit begins.

### 3.4 Snapshot/admission algorithm (specification pseudocode)

```text
submitCompletedPatch(request):
    authorize(owner, workspace, roots, operations)
    require unique patchId or exact replay of the same request
    reserve bounded ledger, queue, and CAS capacity
    if immutable producer manifest:
        verify every blob, entry, base link, and approved root
        source = applyCompletedBatchToImmutableBase(request)
    else:
        require certified freeze across all relevant writers/roots
        with freeze(request.patchEpoch):
            enumerate complete declared input scope; record exclusions
            read through validated file descriptors; seal raw blobs in CAS
            verify namespace/epoch still matches the held freeze
            source = canonicalManifest(entries, provenance, unknownInputs)
    if coherence cannot be certified:
        return explicit unaccepted/inexact outcome; never claim exact review
    fsync new CAS objects and manifest; atomically install immutable objects
    transaction:
        check replay/conflict and capacity again
        persist patch -> source mapping and required policy
        persist planExpansionPending = true and durable review obligation
        pin inputs; set current completed-patch pointer for its lineage
    acknowledge durable acceptance, not analysis success
```

The coherence argument rests on immutable producer data or a genuinely obeyed freeze—not the verification scan. Interrupted pre-commit CAS writes are collectible orphans; a committed patch must never reference unsealed input. If storage/admission fails, the producer retains the immutable patch and retries idempotently; the hook must report the failure or apply backpressure before continuing, rather than losing that patch's review event.

This algorithm registers a completed patch once. Later retries/reanalyses use new review/attempt IDs referring to that immutable patch; they cannot change its source mapping. A terminal review remains historical rather than transitioning back to running. A replacement review can satisfy an outstanding patch obligation only with the required scope/policy/input proof; it does not erase the prior failed/cancelled outcome. A changed rule policy or toolchain creates a new analysis identity and explicit disposition, not a fabricated successful retry under the old identity.

## 4. Project model, trust, and platform prerequisites

### 4.1 Import effective compilations, not folder-name guesses

The Tooling API is the integration transport, **not a guaranteed complete KMP model**. Plan a versioned build-side exporter, run only with authorization, that describes each compilation's effective options and graph. Generic `IdeaProject`/`EclipseProject`/`GradleBuild` models can help discovery but are not assumed to contain the Native, AGP, friend, plugin, or generated-input detail required here. Partial sync is useful, but its behavior varies with Gradle versions and must preserve structured failures ([E08](research/engine-sources.md#e08)).

Required importer output:

- Root/included builds and project/source-set/compilation identity, hierarchy and `dependsOn`/refinement edges, main/test associations, friend/internal visibility edges, source/resource/generated roots, target/variant identity.
- **Effective** compiler arguments after extension/target/task overrides, including language/API versions, opt-ins, context/experimental features, JVM/native target options, plugins and plugin options ([E10](research/engine-sources.md#e10)).
- Ordered resolved classpaths, Kotlin metadata/KLIBs, substituted/composite dependencies, platform capabilities/variants, dependency artifact contents and verification provenance; JDK boot APIs, Android SDK/AGP/manifest/resource context, Native SDK/sysroot/cinterop configuration.
- Generator task/output dependency information where available; uncertain/custom generation explicitly classified rather than presumed incremental.
- Importer/exporter/plugin versions, build-logic/environment read set or declared input contract, missing inputs, unsupported model features, and evidence of how the model was obtained.

Do not retain a stale model as authoritative when build scripts, compiler options, hierarchy, version catalogues, locks, plugins, toolchains, relevant environment, or included builds change. An unknown effective configuration makes the affected semantic scopes incomplete; syntax checks may still run with explicit depth.

### 4.2 Model freshness and the arbitrary-build-logic problem

Gradle build logic can read arbitrary files/environment, branch on source contents or time, contact services, and launch other programs. Hashing `build.gradle.kts` alone cannot establish model freshness. Even a configuration cache is not, by itself, a ktChecker input-completeness proof.

Support explicit provenance modes:

- **Certified closed input contract:** a controlled/exported build model has a complete declared/observed input closure, sealed tool/dependency outputs, and an enforced or otherwise certified environment. Reuse only if every input certificate still matches. If source contents are inputs to configuration, include them; this may force import on every patch.
- **Fresh observed import for this snapshot:** the authorized importer configured the sealed snapshot and returned a fully specified, frozen model under a supported exporter contract, but the complete arbitrary build-logic read set is not certified. Supported obligations can finish against that exact configured model when all of their relevant source/options/artifact/generated inputs are sealed and known. Record the import invocation and environment/provenance limitations; do **not** reuse the model across patches from a build-script hash alone or claim universal build reproducibility/equivalence. An unknown ambient read set is a reuse limitation, not automatic failure of every otherwise well-specified rule.
- **Supplied/stale/partial model:** retain provenance and exact missing/unknown parts. Only obligations whose necessary model closure is certified may count as complete. Never silently use last week's classpath to pronounce today's patch clean.

If a project cannot supply a reusable model contract, the cost/coverage tradeoff is real: perform a fresh authorized import for each patch or stay explicitly incomplete. Unknown **relevant** source roots, effective arguments, late task mutations, friend relationships, generation provenance, or dependency artifacts still block the dependent obligations; importing afresh is not a cure for an exporter that cannot describe them. The final review gate certifies the selected source snapshot under its recorded configured model/targets and evidence requirements, not every possible environment-dependent Gradle outcome. A rule/policy requiring reproducible builds or a certified closed environment must request that stronger evidence separately. The 1–3 second objective must include fresh-import cost where applicable. A trusted project is not automatically hermetic; report separately whether a model is complete for an obligation and whether it is safe to reuse.

### 4.3 Execution trust and no implicit network builds

Default review reads approved source and already installed trusted providers. It does **not** execute Gradle, arbitrary repository scripts, compiler plugins/processors, formatters, external diff/textconv/fsmonitor helpers, or provider JARs named by an untrusted checkout.

- Register trust grants separately for reading roots, executing particular build logic/toolchains/providers, generating outputs, running tests, accessing off-root inputs, and network/provisioning. Scope grants to owner, roots, operation and relevant executable/configuration digests; changed executable build logic invalidates a digest-scoped grant.
- Verify wrapper/distribution/provider/dependency integrity before authorized execution. The Gradle security guide explicitly warns that configuration/sync executes arbitrary code ([E09](research/engine-sources.md#e09)); checksums verify identity, not benign behavior ([E18](research/engine-sources.md#e18)).
- Default importer uses already available distributions/artifacts with offline resolution. Missing dependencies/toolchains are reported, not auto-downloaded. `--offline` controls Gradle resolution; it does **not** prevent malicious build logic from opening a network socket. An actual no-egress guarantee requires OS/container/VM policy.
- Materialize a private snapshot/work directory for authorized builds with separately writable outputs. Read-only mounts/ACLs and a restricted environment are meaningful only where actually enforced. A forked JVM, classloader, or working-directory change is **not** a filesystem/network security boundary.
- No source upload, hosted worker, cloud model, telemetry, or remote cache by default. Remote platform checks require explicit authorization for source/dependency transfer and a disclosed host/security model; do not transfer developer credentials by convenience.
- Keep source CAS, findings, generated outputs, and logs private to their owner/security namespace. Validate local IPC peer ownership, paths and symlink handling; use restrictive POSIX permissions/Windows ACLs. Do not expose an unauthenticated public HTTP daemon.
- Treat source comments, diagnostic strings, and build logs as untrusted data in agent-visible output. Redact secrets, bound payloads and log retention, and do not turn embedded instructions or suggested shell commands into execution authority.

### 4.4 Platform capability accounting

The first release must publish a tested capability matrix, not just “KMP supported.” Planned rows include common/shared source, Android variants, iOS simulator/device compilations and interop, and **Desktop/JVM** (Compose Desktop and resolved native-UI toolkits such as Swing/JavaFX where applicable). Native Windows/Linux/macOS desktop execution targets are not implied launch scope. Each row names compiler/toolchain/provider, source-set context, rule depth, host prerequisites, and known gaps.

- Common syntax can be inspected on any supported engine host. Common semantics must use the actual refinement/dependency graph, including intermediate actual declarations/typealiases ([E12](research/engine-sources.md#e12)).
- JVM desktop and Android resolution require their real classpaths/options. Android lint/resource/build checks may require AGP/SDK/generated resources; emulator/device checks require explicit execution facilities.
- Native semantic support is a dedicated adapter gate. Apple builds/link/tests need compatible macOS/Xcode/SDK facilities; Linux inspection does not establish iOS build/runtime coverage ([E15](research/engine-sources.md#e15)).
- C interop requires more than `.kt` files: transitive headers, `.def`, frameworks/module maps, compiler flags, SDK/sysroot and generated artifacts are semantic inputs ([E14](research/engine-sources.md#e14), [E19](research/engine-sources.md#e19)).
- Desktop JVM source portability, host-native application packaging, rendering, filesystem/keychain integration, and runtime behavior are different coverage claims. Test Windows/macOS/Linux where a rule actually depends on them; a native desktop package containing a JVM is not a Kotlin/Native desktop target.

A missing host is `blocked`/`unsupported` for the named obligation, not “not applicable.” Only a verified applicability predicate can mark a rule not applicable. Platform-independent rules can still finish; the report clearly distinguishes their success from unfinished platform coverage.

## 5. Dependency graph, cache keys, and invalidation

### 5.1 Cache only immutable evidence with sufficient inputs

Use a local content-addressed blob store plus a transactional metadata/job index (SQLite/WAL is a reasonable initial candidate). Install blobs atomically; verify digests on ingestion and suspicious reads. Crash-safe metadata must not reference partial outputs. Ownership/permission checks and namespace separation prevent accidental cross-workspace disclosure; checksums alone do not prevent malicious cache substitution.

Keep these layers separate:

1. Raw source/dependency/toolchain blobs and manifests.
2. Detached parse/index facts, keyed by content, logical file kind/path, parser build/options/schema.
3. Resolved facts and summaries, keyed by certified compilation context plus their complete dependency projections.
4. Rule results and **coverage certificates**, keyed by rule implementation/configuration, facts, target/scope and completeness requirements.
5. Finding lifecycle, project-current pointers, agent delivery/acknowledgment and UI formatting. These are mutable state and **must not be copied from a cached diagnostic run**.

A complete `analysisId` is frozen before workers run. Model/generated/dependency inputs may be derived after source capture only from the sealed input contract, with recorded provenance; no worker fills a missing dependency from the live workspace. If new artifacts, SDKs, rule packs, or model information become available, create a new analysis identity and explicit relationship to the earlier request. Do not mutate an old identity in place or pretend a later environment was the one originally checked.

Cache scopes may be smaller than a whole snapshot **only when dependency projection is certified for that provider/rule**. Otherwise include the entire compilation/project input descriptor and accept the miss. Default artifact identity is its full content hash; ABI-only projection is an optimization requiring rule-specific proof. External vulnerability/advisory/configuration data and evidence-catalogue revisions also belong in the key if a rule uses them.

Never persist `Ka*` objects or assume an old symbol pointer restores into a different compiler/model. Durable symbol IDs are engine-owned, versioned logical identities with a disambiguation strategy; a failed/ambiguous mapping means recomputation, not guessed equality.

### 5.2 Graph shape and required invalidation edges

Orient edges **input -> consumer** so a change traverses toward affected facts/rules. Use old **and** new graphs: a deleted declaration has no new outgoing lookup result, but its old consumers still require invalidation.

```text
bytes / options / artifacts / scope inventories
             -> syntax + declarations + generated-output provenance
             -> resolved types/calls/expect-actual relations
             -> data-flow/effect/ownership/API summaries
             -> rule predicates and coverage atoms
             -> immutable diagnostic occurrences
```

| Changed input | Required impact, beyond “changed `.kt` files” |
|---|---|
| Function/property body, even private | Local control/data flow, captured variables, call/effect/ownership summaries, transitive consumers of those summaries; private does not mean invisible to project rules |
| Public/internal API or inferred return type | Resolved references and overrides, type inference, overload choices, subtype/call consumers, downstream compilations |
| Inline body, `const` value, default argument, contract, `@PublishedApi` | Relevant call-site/body/constant/effect consumers across file/module boundaries, even where ordinary signature/ABI is unchanged |
| New/deleted overload, extension, import, alias, context receiver or declaration | **Candidate-set/negative-lookup dependencies**, not only previously resolved symbols; a new declaration can change an untouched call's meaning |
| Sealed hierarchy, typealias, generic bounds, annotations affecting semantics | Exhaustiveness/subtype/type-substitution consumers; plugin-generated API consumers; related scopes |
| Visibility or friend/associated compilation | Tests and other friend consumers of `internal`, access resolution, summaries that relied on visibility boundaries |
| `expect` / `actual` / actual typealias / hierarchy | Matching declarations and every affected target compilation, intermediate source-set refinement, platform-specific consumers |
| Source addition/deletion/move or root exclusion | Old/new scopes, package/file inventories, global rules, source-set membership, missing-declaration/duplicate findings; path-sensitive keys |
| Classpath/KLIB/JDK/SDK/artifact order or substituted dependency | Resolving compilations and their dependants; full artifact bodies unless a narrower projection is proven safe for the consuming rule |
| Language/API version, compiler/plugin build, options/opt-ins | All affected parse/semantic contexts and results; unsupported syntax/options invalidate compatibility claims, not merely a cache entry |
| KSP/KAPT/other generator input, processor, options or output | Generator dependency closure and consuming compilations; aggregating/unknown outputs widen to the relevant compilation or project |
| Cinterop definition/header/framework/module map, macros, SDK/sysroot | Regenerated bindings/metadata and Native consumers; transitive includes and tool/target flags matter |
| Resource/manifest/schema/build configuration | Related generated declarations and resource/build predicates, compilation model, variants and source-set topology |
| Rule implementation/configuration/severity/suppression/baseline | Re-evaluate the affected rule/results or policy projection as appropriate; disabled/suppressed is a policy state, not proof a defect was fixed |
| Evidence catalogue/library compatibility metadata | Rules whose interpretation or applicability changed; retain the old evidence version on historical findings |

KSP documents isolating/aggregating output semantics, but ktChecker still needs a reliable exporter and correctness tests ([E13](research/engine-sources.md#e13)). Do not guess them from directory layout. Unknown custom generators, annotation processors, compiler plugins, reflective/dynamic calls, unmodeled libraries, or unrecognized compiler features make the relevant dependency closure unknown. Some rules can conservatively inspect a larger scope; others must say “insufficient evidence.” Larger scope does not magically make an unavailable semantic fact known.

### 5.3 Negative dependencies and completeness

A resolved-reference graph alone is unsound. Record lookup dependencies such as `(compilation, scope, name/kind, candidate-set digest)`, including **empty** results. Also record package/star-import inventories, receiver/member scopes, sealed-family membership, source-root membership and relevant target universes. Candidate-set changes invalidate lookups even if the old chosen symbol still exists.

If an adapter cannot emit a sound fine-grained lookup dependency, attach a broader package/module/compilation inventory dependency. If even that boundary is uncertain, invalidate the project/target closure. Do not claim precise impact analysis for unsupported reflection, language constructs, plugins, or global rules. Each graph/node carries a `dependencyCompleteness` status and a reason; “edges not observed” is not the same as “there are no edges.”

For recursive summaries, use strongly connected components and a conservative fixed point. Bound work, but if a bound is hit return unknown/incomplete—not an empty summary interpreted as safety. A rule must declare its fact requirements, scope, supported library/compiler versions, and whether it needs bodies, ABI, build or runtime evidence. Absence of a declared scope defaults to full-project work, not local-file reuse.

### 5.4 Impact and cache algorithm (specification pseudocode)

```text
planReview(oldAnalysis, newAnalysis, policy):
    freeze and validate all new identity descriptors
    if model/target/rule inventory is incomplete:
        create explicit blocked/unknown obligations; keep expansion incomplete
        return partialPlan(known independent atoms, unresolved inventory)
    universe = requiredAtoms(policy, certifiedModel, allRelevantTargetsAndScopes)
    # A first review has no established unaffected baseline: start full.
    seeds = changedInputNodes(oldAnalysis, newAnalysis)   # includes removals
    graph = validatedOldGraph UNION newlyDiscoveredGraph
    dirty = transitiveConsumers(seeds, graph)
    for each missing/uncertified edge boundary:
        dirty += conservativeContainingScopeAndConsumers(boundary)
    for each atom in universe:
        if no complete dependency certificate exists or atom intersects dirty:
            schedule(atom, newAnalysis)
        else if validateCertificateAgainstNewInputs(atom.previousCertificate):
            bind reusable immutable result to this patch with proof lineage
        else:
            schedule(atom, newAnalysis)
    persist the complete obligation inventory before clearing expansionPending

compute(node, frozenAnalysis):
    resolve complete declared and discovered dependencies
    if input closure/model/semantics is unknown:
        widen if sound; otherwise return INCOMPLETE(reason)
    inputDigest = H(node schema, provider + rule builds, target/context,
                    ordered dependency digests, relevant options/config)
    if verified complete cache entry exists for inputDigest:
        return cached immutable facts/findings/coverage  # not lifecycle state
    result = execute against frozen inputs, within lease/resource bounds
    if cancellation/error/unknown required facts/incomplete traversal:
        persist progress or failure; do not mint complete coverage certificate
    else:
        certificate = H(inputDigest, exact checked scope, completeness proof)
        atomically persist immutable result + discovered edges + certificate
        if a summary's semantic digest is unchanged:
            downstream recomputation may stop only after validating its new
            dependency certificate and any scope/candidate-set dependencies
    return result
```

Cache lookup is not circular proof: candidate certificates name concrete upstream inputs that are revalidated/recomputed in dependency order; strongly connected regions are handled together. Unknown graph provenance or a changed adapter schema invalidates the shortcut. A newly discovered dependency broadens impact and can require replanning before completion. A suspected unsound cache family is quarantined and its affected reviews are marked for revalidation, not merely fixed for future runs.

The Kotlin compiler's own incremental facilities are useful precedent, not the correctness oracle for all lint rules ([E11](research/engine-sources.md#e11)). The independent oracle is clean full analysis of the same frozen inputs, with caches disabled, compared at the **findings and coverage** level.

## 6. Every-patch scheduling, durability, and liveness

### 6.1 Coverage atoms and tiers

An obligation binds **patch + analysis identity + rule version + target/compilation + scope + required evidence depth**. Its inventory comes from the frozen rule policy and model. Unknown targets/scope expansion keep the patch incomplete. A result on one Android variant or one Native target does not cover all variants/targets without an explicit equivalence proof.

Depth values are `syntax`, `semantic`, `graph`, `build`, and `runtime`; cost labels `fast`, `medium`, `slow` are **estimates until measured**. Depth and scheduling tier are not interchangeable: a warm semantic cache hit can be cheap, and parsing a huge input can be expensive.

| Work lane | Purpose | Coverage obligation |
|---|---|---|
| Admission/prerequisites | Source coherence, trust, model/target inventory, capability checks | No accepted job ID counts as a rule result |
| Interactive | Local/syntax and cheap already-modeled checks; return actual findings or verified checked-scope coverage first | Reports precise completed/remaining scopes; not “all clear” |
| Semantic/graph | Impacted compiler-backed rules, transitive summaries, expect/actual and cross-module checks | Runs/reuses every required affected atom; conservative widening allowed |
| Build/platform/runtime | Authorized generation/build/lint/test or host-dependent evidence | Remains pending/blocked until genuinely completed or explicitly waived as incomplete |

After an initial full baseline, unchanged scopes can be satisfied by validated reusable certificates. This counts as review of each patch because the necessary inputs are proved equivalent; it is not a claim that all workers executed again. Reports distinguish `executed` from `reusedWithProof`.

### 6.2 What may and may not be coalesced

**Safe:** duplicate submissions with the same ID/content; pre-completion watcher noise; identical work keys whose complete dependency inputs match across multiple obligations. One computation can satisfy several patches, each with its own audited binding.

**Unsafe:** cancelling every old patch whenever a new patch arrives; running only the latest source tree and calling earlier trees checked; carrying only the newest finding list; giving fast work endless priority while graph/platform work never runs. A defect can exist in an intermediate patch and disappear in the next; a current-snapshot scan cannot prove the intermediate review happened.

Default contract is **strict every accepted patch**. A later patch can make an older result obsolete for display but does not cancel its historical obligations. Speculative work that is not the sole route to an obligation may be cancelled/replaced. Explicit owner/client cancellation is recorded as cancelled/incomplete; it cannot satisfy strict review or resolve findings. If a user elects latest-only mode, the report must label skipped patches and must not claim the every-patch contract.

### 6.3 Bounded resources and backpressure

A finite machine cannot fully review an arbitrarily fast infinite edit stream with unboundedly expensive checks. State the liveness assumptions: admitted work has bounded cost or a timeout; required tools/hosts are eventually available; the broker continues running; owner approval is eventually supplied where required. Missing prerequisites and exhausted retries become visible terminal failures/blocks, not perpetual progress indicators or successful coverage.

**Proposed initial limits—not implemented or calibrated:** start with at most two active compiler workers, one active analysis mutation per workspace, and one global heavy Gradle/build job. Reduce concurrency when the memory budget cannot support these. Separate platform-host capacity from local capacity. Release configuration must also supply hard global/per-workspace limits for pending patches, queued work, snapshot bytes, worker RSS, CPU, output bytes, open files, and execution time. Do not create an unbounded coroutine per rule/file.

- Reserve scheduling service for old semantic/slow obligations; use weighted fair/deficit queues between workspaces and aging/oldest-unsatisfied preference within a workspace. With multiple slots, reserve slow-lane capacity; with one slot, alternate bounded service so new interactive jobs cannot monopolize it.
- Expensive indivisible compiler tasks have deadlines and resource reservations. Cooperative cancellation is preferred; enforce a kill boundary for hung owned processes. Timeouts fail the obligation unless a valid replacement later completes it.
- Admission reserves durable capacity before acknowledgment. At a queue/disk limit, return structured backpressure and make the patch producer **pause completed-edit admission or retain a durable bounded local spool with the same pause behavior**. Continuing unlimited edits while dropping events is not an implementation of every-patch review.
- Pin immutable inputs and prerequisite artifacts for pending/admitted obligations. Never delete an old pending snapshot simply to make room for a newer one. Apply retention/GC only after references are safely released under a disclosed completed/failed/cancelled archival policy; preserve identity, outcome and proof metadata.
- Account for JVM heap **and** metaspace/native memory/mapped files plus Gradle, Kotlin daemon, compiler and platform child processes. `-Xmx` is not a total-memory limit. Track RSS/pressure, evict idle sessions with LRU/TTL, and spill detached facts/results to disk. Full process containment depends on OS facilities; disclose when only soft limits are available.
- A worker pool may retain compatible JVMs, but cannot retain one full compiler model indefinitely for every project. Cold eviction/reload is an observable latency cause; do not omit those requests from latency reports.

### 6.4 Durable job/lease state machine

Persist patch/obligation/job state and the delivery outbox in one transactional database, with CAS references committed only after input/output durability. Suggested job states: `queued -> leased -> running -> succeeded`, with explicit `blocked`, `failed`, `cancelled`, and retry transitions. Coverage is separate: a succeeded process can still return incomplete semantic scopes.

```text
claimWork(worker):
    transaction:
        choose eligible job under workspace fairness and resource reservations
        bump job.attempt / fencingToken
        set leaseOwner, brokerBootEpoch, expiry, reservedResources
        return immutable job descriptor + fencingToken

commitResult(jobId, token, result):
    verify envelope identity, checksums, scope, provider/capability/rule versions
    stage immutable output in CAS and fsync
    transaction:
        reject authoritative commit if lease/token/bootEpoch is no longer current
        compare result dependencies to the job's frozen identity
        persist result, exact covered atoms and non-success reasons
        satisfy only matching obligations; persist this analysis's scoped history
        reconcile current lifecycle only if identity/epoch still matches
        # Obsolete success never resolves a finding on a newer analysis.
        append monotonic durable outbox event with stable deduplication ID
        release lease/resources and commit

onCrashOrRestart():
    revoke leases from earlier broker boot epoch; fence their later responses
    reclaim/terminate owned orphan workers safely
    requeue unfinished idempotent jobs within retry policy
    preserve every patch, open finding, obligation and unacknowledged event
```

A heartbeat updates liveness, not correctness. Retries are at-least-once execution with idempotent/fenced commit; do not promise exactly-once compiler execution. A repeated request joins existing work if its immutable key matches. A late duplicate may be a cache candidate after full validation, but cannot overwrite current lifecycle state or emit duplicate delivery under an expired lease. Cancellation/deadline changes invalidate the attempt's authority before killing it.

Failed jobs retain actionable reasons and retry/owner-decision paths. Automatic retry is bounded; compiler OOM loops must not starve other workspaces. Broker shutdown drains or durably leaves pending work; an accepted patch is not silently forgotten because the MCP connection or editor closes.

### 6.5 Completion gates

Define independently:

- `reviewComplete(p)`: plan expansion finished and every required atom has successful exact/reusable coverage or a **proved** not-applicable outcome against the recorded source/configured model/targets. Blocked/unsupported/failed/cancelled/unknown atoms make this false. A fresh observed model can satisfy supported, fully specified obligations without a universal build-hermeticity proof; stronger build/reproducibility evidence is required only where the rule/policy actually requests it.
- `reviewPass(p)`: review is complete and no finding violates the selected gating policy. Suppressions/waivers remain separately visible; changing policy is not repairing code.
- `currentResult(p)`: the complete analysis identity and source/capture epoch match the explicitly selected current lineage; new edits or toolchain/model/policy changes invalidate this assertion.
- `everyPatchSatisfied(session)`: all accepted completed patches in the contracted session have their required review coverage. Superseded patches are still included; the final snapshot alone is insufficient.
- `contextInserted(event, consumer)`: an authenticated insertion receipt/ACK proves that the event's context bytes were inserted for the bound consumer/thread generation. This does **not** prove a model request included them.
- `agentDeliverySatisfied(frontier, consumer)`: every delivery-required finding/coverage event in the fixed gate frontier has authenticated insertion receipts **and `model_observed` proof**: actual model-request inclusion, or a tested atomic inject-and-continue path proving equivalent inclusion before the guarded action. Capability discovery, a queued turn, an injection API response, a future opportunity to continue, or insertion ACK alone is insufficient. Observation is not a claim of comprehension, obedience, or defect resolution.

An external agent's final barrier checks the current snapshot and required historical obligations, finding policy, and the required context's model-observation proof. A wait timeout or absent observation proof returns incomplete/delivery-unavailable details; it does not turn pending review or insertion-only delivery into success. An owner can explicitly accept an incomplete outcome, but the record must say so. More edits reopen the relevant obligations and require another completed-patch submission, including when those edits fix a previous finding.

### 6.6 Finite gate cut and guarded final action

The barrier must not require an infinite chain of “observe the ACK of the observation of the gate.” Fix a finite **review-event frontier**, not every control message ever generated:

```text
prepareGate(session, consumer):
    seal/compare current source under the exact capture contract
    settle required current and historical review obligations
    transaction:
        require complete coverage inventory and successful required coverage
        cut = (current source/analysis/policy identity, source epoch,
               coverage/plan epochs, per-stream review-event frontier)
        required = deliveryRequired finding/coverage events through cut.frontier
        persist immutable cut and required event revisions/payload digests
    arrange insertion for any missing required context
    wait for authenticated model_observed proofs for required events
        # Actual request inclusion, or tested atomic inject-and-continue ordering.
        # Previously proven observations for this bound consumer may be reused.
    at the runtime-controlled final-action fence:
        recheck source/analysis/policy identity and source/coverage/plan epochs
        require no new relevant obligation/event invalidated the cut
        require acceptable finding policy and all required observation proofs
        allow final action only while that fence remains valid
```

New writes, model/policy changes, or relevant coverage/finding revisions invalidate the cut and require a new barrier; a historical successful gate is not a reusable permission for a newer state. The supported host must hold or revalidate the mutation/final-action fence at action execution, not leave a gap between checking and acting. Missing prerequisites or failed review work return their explicit non-success state rather than preparing a passing cut.

ACKs, observation receipts, gate status/results, and other control-plane messages do **not** create recursively delivery-required review events. They may be recorded in the control audit log; observational progress is handled according to the protocol's explicit policy. A new substantive diagnostic/coverage revision is different: it advances the relevant epoch and invalidates the earlier cut. Observation receipts bind exact event revisions/payloads, inserted-context identity, actual request or equivalent host proof, and the authenticated consumer/thread generation. The coding model cannot mint them by asserting it has seen a finding. [protocol.md](protocol.md) owns the wire/control-operation schema; no host observation mechanism is verified by this design.

## 7. Results, finding lifecycle, and obsolete publication

### 7.1 Result provenance and precision

Every result includes source/model/toolchain/analysis/policy identities, provider versions, rule/evidence versions, checked scope/targets/depth, reuse provenance, coverage gaps, elapsed/queue times, and source spans with file content digests. A finding includes the concrete predicate/evidence that fired and distinguishes compiler-confirmed facts from heuristics. Do not invent numerical confidence probabilities without calibration. When a high-precision rule's semantic prerequisites are absent, return insufficient evidence rather than an eager guess.

Multiple providers may detect the same issue. Deduplicate only through a tested semantic equivalence mapping while preserving contributing evidence and their coverage; message-text similarity is not enough. A compiler failure can coexist with valid local syntax findings, but it does not establish successful coverage for skipped semantic rules.

Cache raw immutable diagnostic occurrences, not the user's handling state. Diagnostic occurrences are scoped to an analysis; a stable finding lineage connects them only when rule/subject/target identity and location mapping are unambiguous. Offset alone is unstable. Rename/symbol mapping can fail; preserve uncertainty rather than merging two unrelated issues or resolving one by accident.

### 7.2 Finding states

| Event | Correct lifecycle behavior |
|---|---|
| Finding observed on snapshot A | Store open occurrence with exact evidence, location, rule version and scope |
| Snapshot B arrives; affected work pending | Preserve A as last-seen evidence; mark current validity pending/stale where appropriate, not fixed |
| Unrelated B changes; verified input-equivalent coverage reused | Carry the same open finding forward with proof of its unchanged inputs |
| Corresponding rule/scope/target finishes successfully on B with no matching finding | Resolve only with B's absence/coverage proof and precise lineage; if mapping is ambiguous, retain uncertainty |
| Relevant entity/file deleted | Record verified deletion/not-applicability evidence, retain history, and still check affected consumers; absence from an arbitrary changed-file list proves nothing |
| Rule disabled, severity downgraded, suppression/baseline added | Record policy-disabled/suppressed/waived state and reason/version/expiry as applicable; do not call the code defect fixed |
| Rule/semantic identity changes | Re-evaluate/remap with version provenance; invalidated evidence is not a confirmed resolution |
| Cancel, timeout, crash, unsupported target, newer patch, empty partial response | No resolution; open history and missing coverage remain |

Resolution must be a scoped reconciliation operation, not “replace all findings with the last job's list.” A successful file-local rule cannot resolve a project-global or platform-specific finding it did not cover. Suppressed findings remain inspectable separately from actionable findings. Guidance from ktChecker is not a code patch; once an external agent applies a repair, that completed patch goes through the entire contract again.

### 7.3 Exact versus current versus obsolete

Persist exact results for their original immutable analysis even when that patch is no longer current. Publication includes a relation such as `current`, `obsolete`, or `unverifiedAgainstLiveWorkspace`, computed from the current ledger/epoch—not cached from the old run. An obsolete result may fulfill a historical every-patch obligation and expose a real regression in that patch, but must not be presented as a present-file diagnosis without revalidation.

When moving an old diagnostic into current context, verify its dependency closure and source mapping against the current analysis first. Otherwise show it as historical evidence with old locations. Reject a “current” update if the current pointer changed during commit; store the exact historical result instead. Do not drop important old events merely because a newer fast job completed.

The durable outbox carries exact result IDs and lifecycle/coverage transitions with per-stream sequence numbers. The host bridge deduplicates delivery and acknowledges only the agreed context-insertion point; it records model-request observation separately through the privileged protocol operation. It may summarize multiple historical events only while preserving required content, revision/payload accounting, and observation evidence. Insertion-only ACK is not sufficient for the strict final gate. Transport notifications and optional protocol task APIs do not replace durable status/event retrieval or model-observation proof. See [protocol.md](protocol.md) for delivery adapters and host-specific limits.

## 8. Validation, observability, and release gates

### 8.1 Executable correctness tests required before claiming the architecture works

| Gate | Required evidence |
|---|---|
| Standalone/provider feasibility | Compile and run a pinned adapter on JVM, Android, common, and Native/KLIB fixtures; validate symbol/type/expect-actual/plugin behavior, malformed code, session disposal and supported API use; document unsupported combinations |
| Model conformance | Compare exported effective compilation options/roots/artifacts/variants to matching authoritative Gradle/compiler tasks across the certified matrix; include buildSrc/composites, friends, task-level overrides, generated inputs, partial sync and changing environment |
| Snapshot coherence | Adversarial multi-file edits, writer exclusion, independent roots, symlink swap/escape, worktrees, untracked/ignored generated sources, deletes/renames, case changes, watcher overflow, ABA, files changing during copy, crash before/after durability boundary; reject uncertified capture |
| Incremental equivalence | Clean full oracle versus incremental analysis for **every snapshot** in generated and hand-written patch sequences, with identical frozen model/toolchain/policy; compare findings, locations, applicability, coverage and unknowns—not merely compilation success |
| Graph completeness | Replays for inline/const/contracts/private bodies, overload additions and unresolved-to-resolved names, star imports, sealed subclasses, friends, expect/actual, hierarchy, Java/KLIB/classpath order, plugin/generator/cinterop changes; unknown-edge fallbacks |
| Cache validity | Change one input dimension at a time; verify intended hits/misses; corrupt/evict blobs, change compiler/rule/schema/evidence versions, mutate dependency JAR at the same path, reorder classpath, change suppressions, restart between stages |
| Scheduler/durability | Duplicate submission/response, concurrent projects and patches, broker/worker crash and orphan lease, stale fencing token, OOM, queue/disk saturation, slow/unavailable hosts, retry exhaustion, disconnect/reconnect, cancellation and supersession; no lost accepted patch and no starvation under stated assumptions |
| Finding lifecycle | Partial/cancelled/unrelated analysis never resolves an open issue; successful same-scope absence does; ambiguous rename/config disable/evidence changes retain honest history; current/obsolete races cannot overwrite newer evidence |
| Delivery/final barrier | Replay/deduplicate events; distinguish insertion ACK from actual request observation; an accepted injection with no subsequent model request must fail. Verify finite frontier cuts, authenticated observation receipts, no recursive receipt/control-event obligations, and source/coverage epoch races at the final-action fence, including historical patch obligations |
| Platform/security | On each advertised OS/architecture, verify actual containment mode, process cleanup, local IPC ACLs, no unintended Gradle/plugins/network execution, redaction, target prerequisites, and cold installation/toolchain behavior |

The full-analysis oracle validates analysis incrementality **only for its frozen model**. It does not prove that the model correctly reflects the real project; model-conformance/freshness testing is a separate gate. A compiler/adapter upgrade reruns both. No runtime tests in this table have been executed in this planning repository.

### 8.2 Performance measurements must include useful work

Instrument these milestones independently:

```text
completed patch marker
  -> immutable capture finished
  -> durable admission
  -> model ready / capability and coverage inventory ready
  -> queue wait ended
  -> first actual checked-scope finding or verified no-finding coverage available
  -> first useful result injected into model-visible host context
  -> actual model-request inclusion (or certified atomic continuation equivalent)
  -> all required coverage complete
  -> final barrier satisfied
```

Report p50/p95/p99, first useful result and full completion, separate context-insertion and model-observation latencies, queue time, snapshot/model time, cache-hit rates by layer, invalidation size, worker starts/evictions, peak total RSS, CPU, disk IO and missed coverage. A job ID, “analysis started,” an empty unqualified response, or a delivery notification does **not** stop the useful-result clock. Partial no-finding coverage can be reported as scoped progress, with explicit remaining work; it does not stop the clean-patch benchmark's `T_useful`. That metric requires completed required patch-scope assessment, as defined in [validation-ci.md](validation-ci.md#latency-correctness-and-delivered-usefulness). Insertion alone does not stop the model-observation clock. These are separately timed milestones, not a universal ordering: coverage may finish before a busy host can issue its next model request.

Benchmarks must separate:

- Cold installation/distribution availability, cold process, cold model/dependency cache, and fully warm worker/model/cache.
- Small body patch, signature/inline/const/overload change, common/actual change, file move/delete/untracked addition, build/dependency/plugin/generator/cinterop change, and broad formatting/large refactor.
- Representative small/medium/large KMP projects and shared-source fanout; Android variants, iOS targets, desktop; multiple projects/developers and memory-limited machines.
- Idle versus sustained accepted patch traffic, backlog/starvation behavior, crash/restart, cache eviction, and missing host/SDK prerequisites.
- CLI-only analyzer latency versus actual host delivery latency. Do not subtract queueing or publish only cache-hit runs.

Define the exact “warm small patch” fixture, hardware, compiler/provider matrix, rule set and required coverage before evaluating the **1–3 second objective**. Report misses and the longer time to full coverage. If the target fails, first inspect snapshot/import cost, reuse correctness, worker churn, rule scheduling, and graph fanout; do not reduce promised coverage or rewrite the core language by intuition.

### 8.3 Observable assumptions, not hidden shortcuts

| Assumption | Observable evidence | Failure response |
|---|---|---|
| Coherent source capture | Capture mode, epoch/producer manifest, root list and exclusion/unknown-input records | Inexact/incomplete; final-current gate cannot pass |
| Model sufficient for a rule; separately, safe model reuse | Exporter contract/version, exact options/artifact graph, fresh-import invocation or complete reusable-input certificate, provenance and unknown relevant inputs | Fresh import can satisfy known scoped inputs; reimport before reuse, or mark obligations with unknown relevant inputs incomplete |
| Compatible provider | Exact artifact/JDK/compiler tuple, startup self-test, certified matrix and capability handshake | Unsupported; no silent compiler downgrade |
| Sound dependency projection | Graph schema/completeness flags, input-certificate audit trail, differential oracle results | Widen/recompute; quarantine unsound cache family |
| Deterministic cacheable rule | Versioned input contract, normalized output equivalence tests | Disable result reuse or expand inputs; retain nondeterminism warning |
| Eventual service | Oldest pending age, per-lane/per-workspace service, resource queues, retries, host availability | Backpressure, bounded failure or explicit owner action; never false completion |
| Valid resolution/publication | Same-scope absence certificate, current pointer/epoch, old/new occurrence mapping | Preserve open/historical evidence; reject stale current update |
| Actual execution isolation | OS enforcement mode, granted capabilities, process ownership and resource accounting | Disclose soft isolation; refuse untrusted execution requiring stronger guarantees |
| Actual agent delivery | Finite required-event frontier, insertion receipts and separate authenticated actual-request/atomic-continuation observation proofs for the bound consumer, final source/coverage epoch recheck | Keep strict delivery/final action pending; retrieval, notification, injection response and insertion ACK alone cannot pass |

### 8.4 Deliberate implementation order and go/no-go decisions

1. **Feasibility before infrastructure sprawl:** prove pinned JVM/common/Native semantic adapters and representative Gradle model export. If a required initial target cannot be supported reliably, revise the provider plan explicitly before claiming the promised release scope. A Detekt-only implementation is not an adequate hidden substitute.
2. **Correct full mode:** immutable snapshots, capability/coverage schema, full analysis, scoped finding lifecycle and deterministic fixtures. This is the reference behavior, not a marketed fast release.
3. **Durable every-patch pipeline:** transactional admission/jobs/leases/outbox, host after-patch contract, restart and final barriers, backpressure/security tests.
4. **Safe incremental acceleration:** complete graph dependencies, conservative fallback, detached fact/result caches, strict full-vs-incremental equivalence on replayed patches. Introduce each narrower dependency projection only after its tests pass.
5. **Performance and broad rule/target qualification:** profile representative rule breadth, certify library/target/toolchain combinations, measure warm and cold distribution tails, tune worker/session memory and scheduling. Package/launch improvements follow data.
6. **Release only against the actual matrix:** disclose every unavailable rule/target/depth/provider and current protocol/host limits. No documentation checkbox, source citation, mocked response, or quick submission acknowledgment is evidence that the engine meets its review, correctness, security, or latency contract.

## 9. Evidence and open risks

Primary-source facts, observed versions/dates, retrieval hashes and failed probes are in [engine-sources.md](research/engine-sources.md). In particular, upstream documentation supports the feasibility of semantic analysis, but does **not** prove the proposed persistent multi-project standalone integration, sound incremental graph, Native coverage, no-egress sandbox, or 1–3 second goal.

High-risk work to retire explicitly: unstable standalone/PSI integration; project/provider compiler mismatch; non-hermetic Gradle model freshness; Native/KLIB/cinterop and compiler-plugin facts; complete negative/candidate dependency edges; JVM/Gradle memory pressure; every-patch backlog under sustained edits; and host-visible delivery after asynchronous completion. Each has a failure mode and gate above. Keeping those limitations visible is part of producing a trustworthy diagnosis tool, not an excuse to report missing work as clean.

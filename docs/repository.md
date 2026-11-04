# Proposed repository, modules and local distribution

**Status: implementation layout, not existing engine code.** The current repository
contains research, specifications, candidate catalogs and planning-validation
scripts. The tree below is the proposed implementation structure; directories and
commands shown here are not claims of a working distribution.

## 1. One public repository; isolated provider builds

```text
ktChecker/
  settings.gradle.kts                 # broker/CLI and compiler-independent modules
  build.gradle.kts
  gradle/                            # verified wrapper, locks, version catalogs
  modules/
    contracts/                       # versioned detached DTOs, schemas, errors
    domain/                          # identities, obligations, findings, state machines
    snapshot/                        # manifests, capture ports, content-addressed inputs
    project-model/                   # compilation graph, import contract, provenance
    facts/                           # detached symbol/effect/ownership facts and schemas
    impact/                          # old/new graphs, dependency certificates, invalidation
    rule-api/                        # predicates, capabilities, evidence and coverage contracts
    rules-contracts/                 # certified language/library contract checks
    rules-flow/                      # bounded flow, cancellation, ownership analyses
    rules-policy/                    # declared architecture/build/style policies
    storage/                         # local journal/index, CAS, migrations and quotas
    scheduler/                       # fair queues, leases, budgets, worker supervision
    service/                         # admission, orchestration, lifecycle, delivery outbox
    daemon/                          # per-user IPC/authentication and process lifecycle
    cli/                             # human, JSON and NDJSON front end
  providers/                         # independent builds/distributions, no shared compiler ABI
    compiler/<adapter-line>/         # matched Kotlin/Analysis API, JVM/common/Native routes
    detekt/<provider-line>/           # pinned upstream APIs, atomic rule mappings
    lint/<provider-line>/             # pinned Lint/UAST and qualified embedded registries
    gradle-model/                    # trusted exporter/task instrumentation, isolated execution
    platform-runner/                 # authorized build/test commands and target receipts
  bridges/
    mcp/                             # thin TypeScript bridge, exact SDK/profile, lockfile
    reference-host/                  # out-of-model polling, context observation, action fence
    hosts/<qualified-host>/          # only adapters with tested capability manifests
  fixtures/
    rules/<stable-id>/               # positive/negative/boundary/config/version cases
    projects/                       # Android/iOS/Desktop; Compose and non-Compose
    histories/                      # edits, races, cache mutations and delivery replays
  evaluation/                       # corpus manifests, labeling protocol, metrics; no private code
  benchmarks/                       # repeatable cold/warm/contended patch workloads
  packaging/                        # OS launchers, runtime images, install/update/uninstall
  data/                             # rule catalogs, support/qualification manifests, scope baseline
  docs/                             # contracts, research, decisions, limitations, evidence indexes
  scripts/                          # deterministic inventory/evidence/maintenance tooling
  .github/workflows/                 # planning + incremental implementation/release suites
```

These are dependency boundaries, not an instruction to create dozens of empty
modules in M1. Combine closely related compiler-independent packages until separate
build/test ownership is useful. **Do not combine incompatible providers to simplify
the tree.** Each provider line has independent dependency locks, toolchain manifest,
license inventory, tests and distribution; a root composite may orchestrate its
build without aligning or overriding its compiler dependencies.

### Dependency and ownership rules

| Boundary | Owner / permitted dependencies | Forbidden coupling |
|---|---|---|
| Contracts and domain | Kernel lead; immutable value types and explicit ports | Compiler/PSI/Gradle/SDK types, CLI formatting or host session objects |
| Snapshot, storage and project-model | Kernel/import leads; implement domain ports | Reading live project files after snapshot sealing, reusable models with unknown relevant inputs |
| Facts, impact and custom rules | Analysis/rule leads; versioned fact and rule contracts | Durable compiler objects, unsound cross-version symbol IDs, provider-specific “success” as complete coverage |
| Scheduler/service | Kernel lead; domain/ports, detached outcomes, global resource accounting | Provider libraries on broker classpath or transport-specific job state |
| Provider processes | Per-provider owner; own compiler stack, read-only sealed inputs, worker RPC | Unauthenticated self-upgrade, untrusted arbitrary rule JAR loading, live-workspace reads |
| CLI/MCP/host adapters | Delivery lead; public application API and authenticated local control plane | Separate authoritative job database, model-created observation proofs, analysis duplicated in TypeScript |
| Evaluation/release | Independent evaluator and release lead | Rule authors self-certifying ground truth or a release job silently changing the tested candidate |

Apply the architecture's versioned worker protocol at every process boundary.
Provider facts, coverage and diagnostics cross as validated detached data. Worker
stdout is framed data, stderr is bounded diagnostics; no accidental compiler output
may corrupt MCP or CLI machine output. Keep the authoritative application schema in
one source of truth; generate language bindings/examples rather than maintaining
divergent Kotlin and TypeScript definitions. The present schema embedded in
[the protocol specification](protocol.md) is a design artifact to migrate with
compatibility tests, not a second future runtime schema.

## 2. Local installation and multiple projects

**Proposed distribution:** signed/checksummed versioned archives with OS launchers,
a qualified JVM runtime image when redistribution permits, and an explicit managed
Node runtime or documented prerequisite for the thin bridge. Choose archives before
adding Homebrew/WinGet/package-manager convenience. Validate those channels before
advertising them. Do not claim a single native binary or require the application to
upgrade its JDK to match the broker.

The developer workflow is:

1. Verify/install a release once; run the proposed `ktchecker doctor` command.
2. Register a checkout/worktree in the trusted local control plane, selecting its
   permitted roots, project configuration and execution trust. Registration/import
   is explicit; MCP discovery must not silently execute Gradle.
3. Provision the chosen qualified toolchain/provider artifacts with approval, then
   import/seal a model and run the initial full baseline. A baseline identifies
   pre-existing debt; it does not resolve that debt or qualify unknown rules.
4. Attach a tested host adapter or use the CLI. Every completed patch produces the
   immutable submission described in [the protocol](protocol.md).
5. Register further projects without reinstalling ktChecker. Share only authorized,
   immutable artifacts; each worktree retains separate policy, model, finding,
   patch and delivery state. Enforce global resource limits and fair service.
6. Upgrade through an explicit compatible version. Preserve the patch ledger,
   findings and unacknowledged events; back up/migrate metadata transactionally.
   Reject unsupported downgrade instead of reading a newer database incorrectly.

Administrative registration, trust, provisioning and upgrades are separate from
agent-facing review operations. Their concrete command schema is an M1 deliverable;
the current review examples are not a complete installer/admin API. Credentials and
trust decisions never come from a project's source text or the model's assertions.

### State, configuration and privacy

| Concern | Proposed location / rule |
|---|---|
| Project-shared settings | Optional versioned `ktchecker.toml`: selected targets, declared module boundaries, rule configuration and suppression policy; no credentials or arbitrary executable provider URLs |
| Owner controls | Per-user config: resource caps, trusted roots/toolchain hashes, approved execution/remote hosts, retention and host registrations; project settings cannot grant more trust or weaken protected host fences |
| Linux | XDG config/state/cache locations; owner-only runtime endpoint under `XDG_RUNTIME_DIR` or a securely created fallback |
| macOS | Per-user Application Support/ktChecker for durable state and Library/Caches/ktChecker for disposable cache; owner-only local endpoint |
| Windows | Per-user LocalAppData/ktChecker for local state/cache and a documented per-user config location; authenticated named pipe with restrictive DACL |
| Durable state | Patch/coverage/finding/delivery database and referenced immutable manifests; not disposable cache. Prefer local filesystems, not network shares or cloud-synchronized SQLite/WAL directories |
| Secrets | OS credential facilities or a permission-restricted secret store; never command-line arguments, project config, logs, benchmark artifacts or shared caches |
| Disposal | Explicit retention/GC honors outstanding obligations and unobserved events. Unregister/uninstall never deletes application source; source-bearing diagnostic archives are opt-in exports |

Resolve configuration precedence explicitly: protected owner/security limits first,
then validated workspace policy, then safe invocation options. Record the complete
effective configuration and digest in each analysis identity. Changes may select a
different policy, but cannot silently transform old findings into fixed findings.
Describe disabled coverage and exemptions in every relevant result.

## 3. Turning the plan into executable repository gates

The existing [planning workflow](../.github/workflows/plan-validation.yml) validates
metadata/scripts/examples only. Add actual kernel tests with M1, provider and
per-rule fixtures with M2, host conformance with M3, mutation/equivalence and measured
benchmarks with M4, independent qualification with M5, and clean-machine packaging
with M6. Keep the planning workflow until it is superseded by equivalent tested
registry/contract guards. Do not add no-op Kotlin workflows that imply portability.

Version control the public support manifest, qualification status and coverage
delta. Retain exact run/toolchain/artifact IDs for real tests, with public redacted
summaries and access-controlled private evaluation evidence. The complete PR,
scheduled and release matrices are in [Validation and CI](validation-ci.md).

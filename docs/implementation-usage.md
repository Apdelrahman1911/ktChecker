# Running the implementation preview

`0.1.0-dev.1` / `ktchecker/preview-1` is an **unqualified development build**.
The release inventory has not been reduced. Catalog membership does not imply
implementation, integration testing, precision validation, or default activation.
The [implementation contract](implementation-contract.md) and baseline gates still
apply. This document describes actual commands, not the future protocol-1 CLI.

## Build, install and remove

Use JDK **21** for the checker, not the independently selected project toolchain.
The checksum-pinned Gradle **9.6.1** wrapper resolves Kotlin **2.4.21**. Explicit
builds can download dependencies; reviews do not silently install toolchains.
Linux/macOS source builds also require Python 3 and an explicitly selected C11
compiler (default `/usr/bin/cc`; override with
`-PnativeSupervisorCc=/absolute/compiler`). `-PnativeSupervisorPython=python3`
selects the build interpreter (`python` is the Windows default). The helper is
rebuilt locally; it is never downloaded or compiled by a review operation.

```sh
./gradlew --no-daemon --max-workers=1 test :cli:installDist :cli:distZip :cli:distTar
modules/cli/build/install/ktchecker/bin/ktchecker doctor --format json
modules/cli/build/install/ktchecker/bin/ktchecker rules
```

Windows equivalents use `gradlew.bat` and `bin\ktchecker.bat`. Add the installed
`bin` directory to your own PATH, or unpack a tested CI preview archive and use its
launcher. Keep **the whole distribution** together: `lib` contains the engine and
`providers/compiler` contains the isolated compiler, not engine-classloader
plugins. Java is not bundled. Archives are unsigned previews; check actual
commit/SHA-256 evidence rather than treating the file name as provenance.

CI continues building, testing and inventorying packages, but **archive uploads
default to off**. The repository owner must explicitly set
`KTC_PREVIEW_DISTRIBUTION_UPLOAD_APPROVED=true`; upload also requires a successful
`report_distribution.py --require-license-review` gate. Owner licensing and
complete dependency/NOTICE review remain unresolved, so opt-in alone cannot
authorize publication. Diagnostic reports remain available on failed runs. This
license gate inspects `installDist`, not the contents of ZIP/TAR archives.

Archives now include a **host-specific** native owned-child supervisor in the
provider-runtime JAR. Select the correct OS/architecture artifact; this is not a
universal native binary. `doctor` reports bounded installed-helper inspection
separately from provider execution. Windows emits an explicit unavailable
manifest until native Job Objects containment is implemented. A matching binary
digest does not establish ABI/runtime qualification or a successful analysis.
The build receipt identifies source, binary and compiler driver, not the entire
external linker/SDK/shared-library closure. Native cleanup assumes cooperative
children do not escape their process group/session; it is **not a sandbox** and
cannot guarantee recovery after the supervisor itself is forcibly killed.

### Compiler runtime selection

The broker JDK and the compiler JDK are separate inputs. The hardened compiler
requires an explicitly identified **complete, link-free JDK 21 image**, not just a
hash of `bin/java`. Select it consistently on owner preparation, submissions and
the persistent broker:

```sh
ktchecker --compiler-java-home /owner/prepared-jdk21 providers
ktchecker --compiler-java-home /owner/prepared-jdk21 rpc
```

`providers` inspects the JVM compiler installation and the bundled process
supervisor only. `toolchain_bound` is not
an execution test, rule qualification or target pass. `doctor` without a broker
does not inspect installed providers. An unsupported or missing compiler runtime
produces explicit unavailable coverage; registration, capture, suppression
inspection and other providers remain usable. A rejected explicit JDK **never
falls back** to the broker's JDK or to a launcher-only identity.

For a trusted vendor image containing links, the POSIX-only explicit owner utility
`python3 scripts/prepare_jdk_image.py --help` constructs a new bounded copy and a
private provenance receipt. Link targets outside the source require explicit
`--external-root` grants; only exact, justified dangling links can be excluded.
Review never runs this utility, downloads a JDK, or invents these permissions.
Its optional `--verify-runtime` is a real owner-authorized `java -version` probe,
not compiler or feature qualification. Do not modify the prepared image in place.

State defaults outside project sources:

| OS | Per-user state |
|---|---|
| Linux | `$XDG_STATE_HOME/ktchecker`, or `~/.local/state/ktchecker` |
| macOS | `~/Library/Application Support/ktChecker` |
| Windows | `%LOCALAPPDATA%\ktChecker` |

`--state DIR` selects a separate private store. Do not point it at a project,
home directory, another user's store, or a network-shared mutable workspace.
Existing broad permissions are rejected, not silently repaired. Different users
run separate brokers/stores. Same-UID processes with unrestricted shell access
are not mutually isolated adversaries. To uninstall, stop your controller and
workers, remove the installation, then deliberately archive/remove that user's
state if no review/audit history must be retained. Review never deletes it for you.

## Explicit owner/controller preparation

The preview imports **one explicitly declared compilation** at a time. It does not
yet automatically reconstruct an arbitrary Gradle/KMP project. A controller must
capture every compilation input, classpath artifact, target/source-set membership
and applicable settings. This manual path is always partial-project and cannot
certify a final live-worktree action. Do not compile only edited lines.

```sh
ktchecker register --workspace sample --worktree task-tree --client agent-a \
  --session session-a --lineage feature-a --out agent-a-private.json
ktchecker overlay --request overlay.json --out capture.json
ktchecker --compiler-java-home /owner/prepared-jdk21 prepare \
  --request compilation.json --authorize-execution --out prepared.json
ktchecker --compiler-java-home /owner/prepared-jdk21 submit \
  --prepared prepared.json --wait-ms 10000
```

Owner-selected output files must be **new**; no implicit overwrite. Registration
outputs contain separate client and adapter secrets. Keep them outside model
context. `--authorize-execution` explicitly permits the generated exact profiles;
source text, JSON `approved:true`, review requests and MCP calls cannot grant it.
It is not a sandbox for arbitrary Gradle/project/plugin code.

Example `overlay.json`:

```json
{
  "workspaceId": "sample", "worktreeId": "task-tree",
  "clientId": "agent-a", "sessionId": "session-a", "patchId": "patch-1",
  "baseSnapshotId": null, "expectedHeadRevision": 0,
  "changes": [{"operation": "put", "path": "src/Example.kt",
    "content": "interface Contract { fun answer(): Int }\nclass Broken : Contract\n"}]
}
```

Copy `capture.json.manifest.snapshotId` into `compilation.json`:

```json
{
  "clientId": "agent-a", "snapshotId": "COPY_THE_CAPTURED_ID",
  "patchId": "patch-1", "idempotencyKey": "review-patch-1",
  "compilationFiles": ["src/Example.kt"],
  "scope": {"module": ":sample", "sourceSet": "main", "target": "jvm", "compilation": "main"},
  "requiredRuleIds": ["ktc.compiler.common.abstract_member_not_implemented"]
}
```

An explicit `requiredRuleIds` list is an owner-selected **experimental subset**,
not full-release coverage. Without a subset the target's whole candidate inventory
is retained, but unqualified defaults do not run. To experiment with a rule while
retaining the full inventory, explicitly enable that stable ID in owner policy
before preparation. Config-disabled rules take precedence, and catalog exclusions
never execute. Unknown compatibility/implementation remains unavailable; these
rows cannot disappear to make coverage look complete. Rule enabling and process
authorization are separate decisions.

`submit` without waiting returns exit **0 for acceptance only**. A waited preview
review returns **2** when pending, unavailable, incomplete or unqualified, even
when a useful compiler finding is present. Exit 3 means an operation failed. In a
qualified contract, a complete blocking finding uses exit 1; this preview cannot
manufacture exit 0 full-project success from a clean-looking finding array.

## Concurrent agents and background polling

Run an owner-managed persistent `ktchecker rpc` process. The MCP bridge and
reference host use private, bounded NDJSON application RPC; this command is **not
an MCP server** by itself. Independent credentials bind client/session/project/
worktree/lineage, and each client has its own cursor, cancellation and delivery
receipts. One agent's ACK does not consume another's observation obligation.

For a runnable synthetic two-client example:

```sh
python3 scripts/run_preview_example.py --compiler-java-home /owner/prepared-jdk21 \
  --output /tmp/ktchecker-example
```

This example is POSIX-only; its transport needs no-follow private files,
nonblocking pipes and `waitid(WNOWAIT)`. It polls outside any model reasoning loop
and separately reports startup, acknowledgment, application-observed useful
findings and terminal times, with explicit unmeasured model-context/resource
fields. It does **not** claim model-context delivery or full-project success.
`--prepare-only` creates owner documents for a separate host experiment. Do not
share credential files when sharing the sanitized report.

Prefer independent worktrees or immutable overlays. Each next patch supplies its
base snapshot and expected head revision. `STRICT_HEAD` rejects a stale base;
`REBASE_DISJOINT` may merge only proven disjoint paths and rejects overlapping/ABA
changes. A physical shared worktree needs the snapshot module's cooperative
edit/capture fencing; ordinary edits plus a watcher do not provide that guarantee.
No capture lock spans a compiler/build. After integration, submit **a new combined
snapshot and all affected compilations**—two successful partial tasks would not
prove the integrated project. Unknown impact is a coverage gap, not an all-clear.

Use a new `patchId` for every completed fix and re-review it. Retrying the same
request uses the same idempotency key and immutable input. Do not reuse a key for
changed code. Cancellation, disconnection, supersession and unrelated edits do
not resolve open findings or erase the accepted patch's obligations.

## Suppression operations

Only an explicit owner/controller action may change policy. Review/MCP tools do
not expose those mutation capabilities. Example broad, deliberate risk acceptance:

```json
{
  "id": "team-review-exception",
  "selector": {"type": "rule", "ruleId": "ktc.compose.pack.ComposableParamOrder"},
  "scope": {"type": "module", "module": ":legacy-ui"},
  "reason": "accepted_risk",
  "justification": "Owner-approved compatibility API; tracked migration ticket UI-42.",
  "expiresAtEpochMillis": null
}
```

```sh
ktchecker suppressions inspect --client agent-a --out revision.json
ktchecker suppressions add --client agent-a --request acceptance.json \
  --expected revision.json --layer shared --owner-action
ktchecker suppressions inspect --client agent-a --out next-revision.json
ktchecker suppressions remove --client agent-a --id team-review-exception \
  --expected next-revision.json --layer shared --owner-action
ktchecker suppressions explain --client agent-a --prepared prepared.json --finding finding.json
ktchecker suppressions selector --client agent-a --prepared prepared.json --finding finding.json \
  --out exact-selector.json
ktchecker suppressions export --client agent-a --out shared-policy.json
```

Use `reported_false_positive` for a reported detector problem, not a claim the
detector is proven wrong. Inspect again after conflicts; never silently overwrite
another client's policy. To import shared policy use `suppressions import
--request shared-policy.json --expected revision.json --owner-action`.
For enable/disable, use `suppressions enable|disable --rule STABLE_ID` with the same
expected revision and explicit owner flag. Shared/local precedence, narrow finding/
file/range/symbol binding and expiration are specified in [suppressions.md](suppressions.md).
Compiler/build failures and unavailability cannot be accepted into success.

`selector` is a read-only helper: insert its complete JSON object as the
`selector` of a separately owner-approved suppression with a reason,
justification and chosen scope. It checks the captured policy, consumer and
source identity but does **not** certify that owner-supplied finding JSON came
from an executed detector. It does not add or activate a suppression. Without a
selected, byte-identified provider installation it refuses narrow selectors;
catalog metadata or a plan-less adapter is not an implementation certificate.

Policy controls accept exact provider routing aliases (for example
`style:MagicNumber`, `detekt.style.MagicNumber`, `ABSTRACT_MEMBER_NOT_IMPLEMENTED`,
and unique Lint IDs such as `NewApi`) and store the stable ktChecker ID. Ambiguous
names such as navigation's `WrongStartDestinationType`, excluded duplicates,
and upstream checks needing a narrower custom detector are not silently merged.
Use the stable ID when unsure. These aliases do not qualify or enable a rule.
Registry/provider upgrades change narrow-policy identities; an unavailable
historical registry is reported, never replaced silently for accepted work.

Owner input documents use bounded, strict UTF-8 JSON: duplicate keys (including
escaped aliases), unknown DTO fields and malformed Unicode fail closed. Machine
output is UTF-8 regardless of the console encoding. An invalid wait limit is
rejected before accepting a patch.

## Providers, host delivery and honest limitations

- Compiler: bundled exact Kotlin/JVM 2.4.21 subprocess; current compilation inputs
  and output locations are isolated. A persistent broker is not a persistent
  compiler daemon or proof of the warm latency target.
- Detekt: optional pinned install (`--detekt-artifacts DIR`); execute the explicit
  resolver in `providers/detekt-fixtures` only as an owner setup action. Its
  Kotlin frontend differs from 2.4.21; compatibility remains unqualified.
- Android Lint and Native: adapters require trusted exact plans, models and actual
  platform tools. The generic manual Workbench path reports these unavailable
  rather than substituting a JVM analysis for iOS. See `providers/platform-fixtures`.
- Windows checker portability is separate from provider subprocess containment
  and Node host ACL support. Unsupported security primitives fail closed. See
  actual CI results and host limitations, not just the operating-system label.
- MCP and genuine model delivery: [implementation-host.md](implementation-host.md).
  Notifications alone are not model observation. Large events use digest-bound
  projections; omitted mandatory findings cannot be ACKed as observed.
- Coverage/precision: generate `scripts/rule_coverage.py` output; current tuples
  and tests are not a global release matrix. Strict coverage/qualification gates
  intentionally fail while obligations remain unmet.

Coverage-report consumers must check the invocation's exit code and
`inventory_valid`, not reuse a file from an earlier run. With `--output`, the
generator atomically publishes `pending` before validation, then a valid inventory
or explicit `unavailable` with unknown counts. A valid inventory can still fail
`--require-tested`/`--require-qualified`; that complete gap report is retained.
Select a dedicated report path, never an input/evidence file, and use distinct
paths for concurrent invocations. Report-file replacement is not a durable ledger.

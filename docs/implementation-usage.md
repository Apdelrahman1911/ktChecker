# Running the implementation preview

`0.1.0-dev.1` / `ktchecker/preview-1` is an **unqualified development build**.
The release inventory has not been reduced. Catalog membership does not imply
implementation, integration testing, precision validation, or default activation.
The [implementation contract](implementation-contract.md) and baseline gates still
apply. This document describes preview commands, not the future protocol-1 CLI.
The explicitly labelled private reassessment section is an unadopted SOURCE
proposal, not a capability of the retained installation.

## Fresh-source quickstart: two independent clients

This checkout-relative recipe is for a **new source build**, not an update of the
retained local handoff below. It is an API recipe, **not a fresh execution receipt**.
It reviews two synthetic, one-file JVM compilations from immutable JSON overlays;
there is no project discovery, live-worktree capture or full-project certification.

### Build and inspect

From the selected fresh checkout, choose a JDK 21 for `JAVA_HOME`, with Python 3
and a C11 compiler available for a Linux/macOS build (see [build options](#build-install-and-remove)).
Use a fresh POSIX shell; `set -eu` stops this recipe on unexpected failures:

```sh
set -eu
./gradlew --no-daemon --max-workers=1 :cli:installDist
KTC_HOME="$PWD/modules/cli/build/install/ktchecker"
"$KTC_HOME/bin/ktchecker" doctor --format json
```

Keep the **whole distribution** together if you move it; Java is not bundled.
`doctor` reports the preview contract/catalog, not installed-provider readiness or
a passing review. Windows uses `gradlew.bat` and `bin\ktchecker.bat`; the shell
recipe below is POSIX, and unavailable Windows provider containment is not a pass.

Choose a **new** private directory outside all reviewed sources, with an existing
parent. Keep request/credential files beside, not inside, the initially empty
state directory. Replace both example absolute paths before continuing:

```sh
RUN="/absolute/private/ktchecker-demo-new"
COMPILER_JAVA_HOME="/absolute/path/to/complete-link-free-jdk21"
umask 077
mkdir "$RUN"
STATE="$RUN/state"
ktc() {
  "$KTC_HOME/bin/ktchecker" --state "$STATE" \
    --compiler-java-home "$COMPILER_JAVA_HOME" "$@"
}
ktc --workers 0 providers
```

Stop on a failed command. Read the provider response: `toolchain_bound` is an
inspection result, not observed execution. The compiler needs a **complete,
link-free JDK 21 image**, independently of the launcher JDK. A missing/rejected
image or unsupported containment stays unavailable; it never grants a fallback.
See [compiler runtime selection](#compiler-runtime-selection) for the explicit
source-checkout image-preparation utility. Utilities under `scripts/` are not
bundled in the installed distribution. Zero workers prevents provider worker
loops, **not** private-state initialization, heartbeat or recovery maintenance.

### Register and capture exactly the supplied source

Use different, state-global client IDs and independent worktrees. The loop below
creates two owner request files per client; its Python command only reads the
returned snapshot ID. `workspace`/`worktree` are logical identities, not paths to
scan. Registration files contain private client/adapter credentials: keep them
out of model context and version control.

```sh
for c in a b; do
  ktc --workers 0 register --workspace demo --worktree "tree-$c" \
    --client "demo-agent-$c" --session "session-$c" --lineage "task-$c" \
    --out "$RUN/$c-credentials.json"
  cat > "$RUN/$c-overlay.json" <<EOF
{
  "workspaceId": "demo", "worktreeId": "tree-$c",
  "clientId": "demo-agent-$c", "sessionId": "session-$c", "patchId": "patch-1",
  "baseSnapshotId": null, "expectedHeadRevision": 0,
  "changes": [{"operation": "put", "path": "src/Example.kt",
    "content": "interface Contract { fun answer(): Int }\nclass Broken : Contract\n"}]
}
EOF
  ktc --workers 0 overlay --request "$RUN/$c-overlay.json" --out "$RUN/$c-capture.json"
  snapshot_id="$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["manifest"]["snapshotId"])' "$RUN/$c-capture.json")"
  cat > "$RUN/$c-compilation.json" <<EOF
{
  "clientId": "demo-agent-$c", "snapshotId": "$snapshot_id",
  "patchId": "patch-1", "idempotencyKey": "review-patch-1",
  "compilationFiles": ["src/Example.kt"], "compilerVersion": "2.4.21",
  "scope": {"module": ":demo-$c", "sourceSet": "main", "target": "jvm", "compilation": "main"},
  "requiredRuleIds": ["ktc.compiler.common.abstract_member_not_implemented"]
}
EOF
done
```

The source is the complete `content` string, not an on-disk `src/Example.kt`.
For a real manual compilation, explicitly capture **all** compilation inputs,
classpath and settings, not only changed lines. This path remains partial-project.
The one-rule list is an **experimental subset**, not reduced release coverage.
For a later patch, use a new patch/idempotency identity and the prior capture's
`manifest.snapshotId` and `headRevision` as its base; do not reset an existing
worktree to revision zero. Output filenames must be new; do not overwrite a
retained capture, preparation or credential file.

### Explicit consent, then two queued clients and one persistent broker

Only proceed with the following flag if the owner permits execution of the
exact prepared provider profiles. **Omit `--authorize-execution` to prepare
without granting that permission**; editing a prepared JSON file or submitting a
review cannot grant it. Missing providers and unqualified rules still do not
become available/qualified merely because execution is authorized.

```sh
for c in a b; do
  ktc --workers 0 prepare --request "$RUN/$c-compilation.json" \
    --authorize-execution --out "$RUN/$c-prepared.json"
  ktc --workers 0 submit --prepared "$RUN/$c-prepared.json" \
    --out "$RUN/$c-submitted.json"
done
```

Both clients are admitted without provider workers in these commands. `submit`
without `--wait-ms` exits **0 for acceptance only**. Now keep this owner broker
running in a terminal, with its standard input open:

```sh
ktc --workers 2 rpc
```

`rpc` is private NDJSON application RPC, **not an MCP server**. Do not launch it
with a bare `&` and assume closed/background stdin will keep it alive. In another
terminal, use the same `KTC_HOME`, `RUN`, `STATE`, `COMPILER_JAVA_HOME` and `ktc`
function, then poll each client separately while the broker runs:

```sh
ktc --workers 0 events --client demo-agent-a --limit 20
ktc --workers 0 events --client demo-agent-b --limit 20
ktc --workers 0 status --client demo-agent-a --review REVIEW_ID_FROM_A_SUBMITTED_JSON
ktc --workers 0 status --client demo-agent-b --review REVIEW_ID_FROM_B_SUBMITTED_JSON
```

Each event stream has its **own** returned cursor for the next `--cursor` poll.
Event reads are neither ACKs nor proof that a model observed anything; status/
event exit 0 means retrieval, not passing analysis. Two workers permit these
independent clients to progress concurrently, but do not promise simultaneous
execution or release-scale concurrency. Pending, unavailable, incomplete and
unqualified results remain visible, including a clean-looking finding array.
A waited `submit --wait-ms 10000` returns **2** for those states; **3** is an
operation failure. This preview cannot certify final live-worktree success.

## Use the retained local handoff

This local-only path is distinct from the preserved
`modules/cli/build/install/ktchecker` in the original checkout. Do not overwrite
that old installation or substitute its receipts. From the original repository
root (the checkout containing `build/checkpoints`), select the retained entry
point below; the recorded smoke used a disposable copy, not another invocation
of this recipe:

```sh
REPO="$(pwd -P)"
HANDOFF="$REPO/build/checkpoints/integration-5e00407-20261104/build/preview-handoffs/ktchecker-20261105-f909k8w0"
KTC_HOME="$HANDOFF/install/ktchecker"
(
  cd "$KTC_HOME" || exit
  env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS \
      -u JAVA_OPTS -u KTCHECKER_OPTS \
      JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
      ./bin/ktchecker doctor --format json
)
```

Java is not bundled. This selects the observed local JDK 21 for the launcher,
not the separate complete compiler image required below. Do not reuse the
disposable Unicode smoke directory or start a build merely to run this entry point.

The local archives are `$HANDOFF/archives/ktchecker-updated-preview.zip` and
`$HANDOFF/archives/ktchecker-updated-preview.tar`.
Fresh postvalidation passed the actual spaces/Unicode-path `doctor`, dependency
inventory and archive-byte checks; this establishes a **local doctor-runnable
preview only**.
See the [exact results and failures](implementation-evidence.md#latest-preview-handoff-2026-11-05).
These are Linux x86_64 unsigned preview artifacts, not universal binaries or a
publication/license grant. Keep their installation unchanged while its receipts
are evidence; remove/archive it deliberately when no longer needed.

Except for explicitly labelled unadopted SOURCE, commands below are **current
API recipes, not a fresh execution claim**.
Add the selected `"$KTC_HOME/bin"` to your own PATH before using `ktchecker` below,
or invoke its absolute launcher. Replace `/owner/...` with owner-selected paths;
use the same private `--state DIR` and compiler JDK for every operation in a run.
Output files/directories must be new and outside the reviewed source. The generic
source-build recipe below targets a default install; do not use it to refresh
the preserved install or this evidence-bound handoff in place.

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
./gradlew --no-daemon --max-workers=1 test \
  :worker-protocol:modelWireTest :provider-compiler:modelWorkerTest \
  :cli:installDist :cli:distZip :cli:distTar
modules/cli/build/install/ktchecker/bin/ktchecker doctor --format json
modules/cli/build/install/ktchecker/bin/ktchecker rules
```

Windows equivalents use `gradlew.bat` and `bin\ktchecker.bat`. Add the installed
`bin` directory to your own PATH, or unpack a tested CI preview archive and use its
launcher. Keep **the whole distribution** together: `lib` contains the engine and
`providers/compiler` contains the isolated compiler, not engine-classloader
plugins. New source builds also package the exact
`:provider-compiler:compilerModelApplicationWorkerJar` output at
`providers/compiler-model/ktchecker-compiler-model-application-preview-1.jar`.
This four-family JDK-only worker stays outside both `lib` and the ordinary
`providers/compiler` scan; packaging it does **not** authorize or activate a model
compiler job. The retained doctor-only handoff above predates this wiring and
must not be credited with the new worker or a new runtime command.
Java is not bundled. Archives are unsigned previews; check actual
commit/SHA-256 evidence rather than treating the file name as provenance.

CI continues building, testing and inventorying packages, but **archive uploads
default to off**. The repository owner must explicitly set
`KTC_PREVIEW_DISTRIBUTION_UPLOAD_APPROVED=true`; upload also requires a successful
`report_distribution.py --require-license-review` gate. Owner licensing and
complete dependency/NOTICE review remain unresolved, so opt-in alone cannot
authorize publication. Diagnostic reports remain available on failed runs. This
license gate inspects `installDist`, not the contents of ZIP/TAR archives.

The workflow separately invokes `verify_distribution_archives.py` on Linux and
macOS after successful preceding steps, even when uploads are not approved. It
compares both exact Gradle outputs `ktchecker-0.1.0-dev.1.zip` and
`ktchecker-0.1.0-dev.1.tar` against `modules/cli/build/install/ktchecker`, including
decoded bytes, relative names, executable flags and the exact
`ktchecker-0.1.0-dev.1` archive prefix. Upload additionally requires that verifier
step's successful outcome and lists only those two checked archives, never a
wildcard that could include unchecked neighbors. The separate metadata-only
`distribution-archives.json` is retained with existing sanitized evidence; its
`verified` state is not a generic command, JVM or native test pass.

Windows archive verification remains **unavailable**: the POSIX descriptor-relative
no-follow interface is not emulated or treated as a pass. Windows still builds,
tests and inventories distributions, but its skipped archive-verifier step blocks
archive upload even with owner opt-in. Unsupported formats, unavailable I/O,
detected drift, mismatch or unconfirmed report delivery cannot pass the verifier.
This is authored workflow wiring, not evidence of a hosted run. The comparison
does not establish historical source/build provenance, an atomic snapshot through
upload, licensing, signing or release qualification.

### Read-only model-worker packaging acceptance

The current dependency manifest includes one `bundledCompilerModel` owner-project
record from the actual reviewed task. `report_distribution.py` now requires that
record and checks the isolated installed worker against its built bytes, manifest,
four class families and Java 21 headers. It rejects test/host/dependency resources
and worker classes in ordinary engine/compiler JARs. Old manifests are historical
evidence, not current packaging acceptance. Owner licensing and all existing
dependency/NOTICE/publication gates remain separate and unchanged.

After an explicitly authorized **fresh** build, select the `file` recorded for
artifact identity `:provider-compiler:compilerModelApplicationWorkerJar` in the
Gradle dependency manifest; do not infer task provenance from a JAR's filename.
The same read-only check is independently available below. The report's existing
parent must be link-free; the report itself must be new and outside the install:

```sh
python3 scripts/ci/verify_model_worker_distribution.py \
  --installation /owner/staged/ktchecker \
  --built-worker /owner/task-output/ktchecker-compiler-model-application-preview-1.jar \
  --output /owner/reports/model-worker-packaging.json
```

Exit `0` requires matching observed independent files, supported bounded JAR
inventories and confirmed new-report delivery. No files are extracted and no JVM,
compiler, native helper or model is launched. The check inspects class **headers**,
not JVM bytecode validity or historical source/build provenance. Before/after path
and stat checks reject observed links/drift; they do not promise hostile-writer
atomicity, runtime success, licensing approval or release qualification. Usable
filesystem identities are required: same-file aliases cannot prove independent
copies, and a report-parent alias into any observed image directory is rejected.

### Whole-distribution archive comparison

For an explicit staged installation and existing local archives, the read-only
archive verifier compares decoded file bytes, names and executable flags without
extracting or running them. The report parent must already exist and the report
must be new:

```sh
python3 scripts/ci/verify_distribution_archives.py \
  --installation /owner/staged/ktchecker \
  --archive /owner/packages/ktchecker.zip --archive /owner/packages/ktchecker.tar \
  --output /owner/reports/archive-comparison.json
```

Exit `0` requires verified inputs **and** confirmed report delivery. Unsupported
platforms/formats, observed changes and incomplete reads are non-success. The
current implementation supports POSIX no-follow I/O, ZIP32 stored/deflate and
uncompressed USTAR—not Windows, ZIP64, GNU/PAX or hostile-writer atomicity. This is
neither licensing approval nor evidence that the archived code was built, tested,
signed or qualified. Local synthetic and retained-preview byte-comparison results
are recorded [separately](evidence/local-python-archives-20261104.json).

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

### Several projects in one per-user store

`--workspace` is a logical project identity, not a source-directory discovery
command. `register` binds a client; it does not capture a project, run Gradle or
permit provider execution. **Client IDs are unique across the chosen state**,
not just within one workspace. Use project-specific client IDs; worktree names
may repeat under different workspace IDs:

```sh
ktchecker --state /owner/private/ktchecker-state --workers 0 register \
  --workspace app-one --worktree task-tree --client app-one-agent-a \
  --session session-one --lineage feature-one --out /owner/private/app-one-agent-a.json
ktchecker --state /owner/private/ktchecker-state --workers 0 register \
  --workspace app-two --worktree task-tree --client app-two-agent-a \
  --session session-two --lineage feature-two --out /owner/private/app-two-agent-a.json
```

Use that same state and the matching client/workspace/worktree/session IDs for
subsequent overlay and preparation requests. Zero workers avoids starting queued
provider work during registration; broker maintenance may still update private
state. Preparation remains read-only with respect to project source, and running
project/provider code still requires the separate explicit execution consent.

Registration retries must repeat the **entire original binding**; choose a new
output filename if the first credential file already exists. A different project,
worktree, session or lineage requires a different client ID, not moving the old
binding. The source registration path rejects malformed binding identifiers
before retaining an enrollment intent, so an invalid identifier can be corrected
without stranding the client ID. Existing intents/bindings are never silently
replaced or deleted; a historical conflicting intent requires a distinct client
ID or a separately chosen state, not an automatic overwrite.

Example `overlay.json` for the earlier `sample`/`agent-a` registration:

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
The CLI supports `--workers 0|1|2`; this limits this process's worker threads, not
the number of registered clients or a release-scale concurrency guarantee.

For a second independent client, the owner registers different client/session/
lineage/worktree identities in the **same chosen private state**, for example:

```sh
ktchecker register --workspace sample --worktree task-tree-b --client agent-b \
  --session session-b --lineage feature-b --out agent-b-private.json
ktchecker --compiler-java-home /owner/prepared-jdk21 --workers 2 rpc
```

Use separate preparations and patch/idempotency identities for each client.
Owner-side `ktchecker events --client agent-a --limit 20` and the corresponding
`--client agent-b` read each client's stream; later polls use that stream's own
returned cursor. Event reads alone are not ACK or model-observation receipts.
The private RPC/host adapter must retain each recipient's own delivery and
subsequent model-request observations; the standalone CLI has no `ack` command.

For an owner-authorized controlled-source two-client example (not rerun against
this handoff by the documentation update):

```sh
python3 "$REPO/build/checkpoints/integration-5e00407-20261104/scripts/run_preview_example.py" \
  --distribution "$KTC_HOME" --compiler-java-home /owner/prepared-jdk21 \
  --output /owner/private/ktchecker-example-new
```

Do not omit `--distribution` when selecting this handoff: the script otherwise
uses `modules/cli/build/install/ktchecker` relative to its working directory.
The example performs real JVM compiler work on controlled source unless
`--prepare-only` is selected; it is not the new imported-model live route.

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
another client's policy. Import and activation also require the client identity,
a newly inspected revision and an explicit owner decision, for example:

```sh
ktchecker suppressions inspect --client agent-a --out import-revision.json
ktchecker suppressions import --client agent-a --request shared-policy.json \
  --expected import-revision.json --owner-action
ktchecker suppressions inspect --client agent-a --out enable-revision.json
ktchecker suppressions enable --client agent-a \
  --rule ktc.compiler.common.abstract_member_not_implemented \
  --expected enable-revision.json --layer local --owner-action
```

`disable` uses the same arguments as `enable`. Enabling is policy, not execution
authorization or qualification; prepare a new review against the changed policy.
Shared/local precedence, narrow finding/file/range/symbol binding and expiration
are specified in [suppressions.md](suppressions.md).
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

## Private policy reassessment CLI (unadopted)

**SOURCE-only, implemented-in-private and unadopted:** the following is syntax
for the private CLI/kernel/Workbench proposal, not an invocation supported by the
retained handoff above. No build, command execution or runtime validation is
claimed here. TypeScript MCP/reference-host wiring is pending; these are not
advertised as available MCP tools. Do not rebuild or replace an evidence-bound
installation merely because these forms are documented.

After a **separate explicit owner policy action**, compatible suppression/baseline
accounting can be requested against the same certified raw result. The action is
not part of `reassess`: no configuration, source, execution or completion authority
is granted by a review request. Only unchanged effective rule settings and
analysis authority qualify; enabling/disabling a rule or changing source/analysis
inputs requires the appropriate new preparation/review, not this shortcut.

Exact command forms (syntax only):

```text
ktchecker reassess --client ID --request policy-review.json [--out result.json]
ktchecker policy-status --client ID --id REQUEST_ID [--out result.json]
```

`--id` is the reassessment **idempotency key**, not a review ID or returned view ID.
Use the same chosen private state and enrolled client binding. The request file
is a closed JSON object with **exactly four fields**; this example is synthetic,
not a captured policy or executed review:

```json
{
  "reviewId": "OWNER_SELECTED_REVIEW_ID",
  "idempotencyKey": "policy-review-1",
  "generation": 1,
  "expectedPolicyDigest": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
}
```

Replace the example review/digest with the owned original review and owner-observed
current full policy digest. `generation` must equal the enrolled client's current
generation; it is not incremented for each reassessment. IDs must be nonblank,
at most 512 characters and contain no NUL/control characters. Generation is a
positive ASCII decimal JSON integer fitting a signed 64-bit `Long`, not a quoted,
fractional or exponent-form value. The digest is `sha256:` plus 64 lowercase hex
digits. Unknown/duplicate fields and malformed JSON fail closed. Do not add raw
results, input/prepared documents, certificates, policy captures or permits.

`--request` selects an explicit bounded regular owner file (at most 8,388,608
bytes), **not stdin `-`**. JSON is the only format (`--format json` is optional).
Both commands default to `--workers 0` and reject a nonzero worker count before
opening Workbench. Zero workers disables provider worker loops, not all broker
startup maintenance or writes. Reassessment/status themselves do not authorize
new analysis. Unrelated options, positional arguments, duplicate options,
`--owner-action` and `--authorize-execution` are rejected for these two commands.

An optional `--out` must name a **new, nonexistent file in an existing writable
directory**, checked before acceptance. That check is not an output reservation.
If later output I/O fails after publication, recover with `policy-status` using
the same request ID, or replay the exact request; do not invent a new key to hide
an uncertain result. Exact accepted replay is historical and emits no new event;
changed fields under that key conflict. A new key with the last view's policy
digest (or the original policy digest if no view exists) is rejected. Neither
replay nor status reclassifies or substitutes current policy.

The CLI obtains the retained client credential itself and calls
`review.policy.reassess` with `{credential, request}`, or `review.policy.status`
with `{credential, idempotencyKey}`. Never put credential tokens in command
examples or model context. Exit **0** means only that a historical policy view was
published/replayed/fetched, not a current or passing full-project review;
operation errors emit a failed object and exit **3**. The returned
`state: "policy_reassessed"` and `auditOnly` are preserved, not normalized into a
passing review. Status is not a fresh policy/archive/currentness validation, ACK
or model-observation receipt.

Original input, raw/effective results, captured policy and certified execution stay
immutable. The new view adds its own required per-client historical delivery
event; older obligations/receipts persist. Original raw coverage must be complete
for its original input even when the view is audit-only. Ordinary Workbench still
has partial-project, nonauthoritative scope and therefore **audit-only** views.
Plan/routed/produced reassessment and integrated use are unsupported.
`auditOnly=false` is not full-project success either: live source/policy authority,
all coverage/history/delivery and guarded-action checks still apply. See
[capture retention and bounds](suppressions.md#retained-captures-and-bounds).
This documentation upgrades no provider qualification or runtime/host evidence.

## Providers, host delivery and honest limitations

- Build-contracts B1 and the model-backed/live compiler route remain separate
  from default activation. This packaging update does not establish an exposed
  owner-runtime command or B1 installation switch. Companion runtime/read-closure
  integration and actual acceptance remain pending; no invocation is documented
  ahead of that integration. Ordinary examples use the manual one-compilation
  route, not a completed genuine model/compiler/host acceptance. Bundling the
  isolated worker does not authorize or execute it.
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
- Coverage/precision: the [latest valid gap inventory](implementation-evidence.md#current-rule-coverage-2026-11-05)
  retains **1,663 IDs × eight contexts = 13,304 cells: two implemented, zero tested,
  zero qualified**. Broad JVM controls and the separate eight actual wrapper
  checksum API calls do not complete per-rule tests. Current tuples are not an
  approved Android/iOS/Desktop release matrix; strict coverage/qualification gates
  intentionally fail while obligations remain unmet.

Coverage-report consumers must check the invocation's exit code and
`inventory_valid`, not reuse a file from an earlier run. With `--output`, the
generator atomically publishes `pending` before validation, then a valid inventory
or explicit `unavailable` with unknown counts. A valid inventory can still fail
`--require-tested`/`--require-qualified`; that complete gap report is retained.
Select a dedicated report path, never an input/evidence file, and use distinct
paths for concurrent invocations. Report-file replacement is not a durable ledger.

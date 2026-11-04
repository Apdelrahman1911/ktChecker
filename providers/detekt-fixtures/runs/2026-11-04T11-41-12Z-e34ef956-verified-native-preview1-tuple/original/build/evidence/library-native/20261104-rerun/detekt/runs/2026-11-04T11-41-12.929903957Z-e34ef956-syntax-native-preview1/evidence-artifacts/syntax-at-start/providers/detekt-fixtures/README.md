# Pinned isolated Detekt fixtures

This adapter runs the real Detekt **2.0.0-alpha.6** CLI in a separate, bounded
JDK21 process. The engine embeds Kotlin **2.4.10**; the project is built using
Kotlin **2.4.21**. Isolation avoids dependency contamination, but does not prove
compiler/language/API compatibility. No tuple or release is qualified.

## Execution hosts

The runtime requires its installed, host-matching owned-child native supervisor
bundle: exact `manifest`, `<target>/supervisor` and informational
`build-provenance.json` under `dev/ktchecker/providers/runtime/native/`.
Supported build targets are Linux/macOS x86_64 and aarch64. Gradle builds the
helper explicitly using Python and an absolute owner-selected C compiler
(`/usr/bin/cc` by default); no runtime compilation, PATH helper discovery,
`setsid`/`kill` launcher or Perl fallback is used. See
[the native runtime contract](../../modules/provider-runtime/native/README.md).
A matching bundle is availability, not native-platform or provider qualification;
Linux checks do not qualify macOS. Windows has no verified Job Objects backend
and is unsupported for real-provider execution. CI must record that limitation and use
`-PdetektIntegration=false` for visible skipped integration methods, not count
them as executed or bypass containment to obtain a green result.

## Explicit installation and test execution

```sh
python3 providers/detekt-fixtures/resolve_artifacts.py --download
./gradlew :provider-detekt:test -PdetektIntegration=true --no-daemon
python3 scripts/rule_coverage.py --output build/reports/rule-coverage.json
```

The resolver downloads only five pinned Maven Central artifacts into the ignored
`.plan-deps/detekt` cache. It checks the official published SHA-1 plus a locally
computed and committed SHA-256 pin and checks **all cached bytes on every use**.
The SHA-256 pins are not invented upstream checksums. No download happens
without `--download`; corrupt cached artifacts are rejected, not silently
replaced. The provider never downloads or loads these JARs into the host JVM.

For CI-only archival destinations, add
`-PlibraryFixtureEvidenceRoot=build/ci-evidence/raw/library-fixtures`.
Gradle resolves the value against the repository root. Detekt then writes only
artifacts in fresh `detekt/runs/<run-id>/evidence-artifacts/` archives and
sidecars in `detekt/evidence/`; source fixtures and START source witnesses still come
from their original repository paths. The selected destination must stay inside
repository `build/` without symlink path components below the repository root.
Without this property, fresh archives and sidecars are under this fixture
directory's `runs/` and `evidence/`. Old shared `evidence-artifacts/` bytes are
historical and are not overwritten. Redirecting archives grants no execution
authority or test result. The next verified native integration must exercise
this override, not merely test property forwarding.

For an installed CLI, select the resolved artifact directory using
`--detekt-artifacts` and explicitly authorize execution. A serialized
`approved=true` cannot supply authority. The authenticated host must create an
exact `ExecutionPermit` binding the prepared input, immutable materialization,
verified classpath, owner-selected rules, settings and profile. The default
provider denies execution. No project code, comment, arbitrary plugin or shell
command is accepted as a provider profile.

## What the fixtures establish

The opt-in JVM test is authored to run real positive, negative, configuration, version,
declaration-scoped suppression (with adjacent unsuppressed findings), and
snapshot-edit regression cases. The syntax suite covers EmptyCatchBlock,
ForbiddenPublicDataClass and the Compose pack's
ComposableParamOrder. The excluded UseEntityAtName identifier stays explicitly
inactive even with an otherwise reportable upstream syntax fixture. The Compose
annotation and rule-author Entity APIs are
**explicit syntax stubs**, not genuine Compose compiler/library or Detekt
rule-author API compatibility evidence. Suppression cases test upstream IDs;
they do not claim arbitrary ktChecker-ID suppression aliases are implemented.

Integration tests are skipped visibly unless `-PdetektIntegration=true`; no
download is hidden inside a test. Historical **14/14 Detekt tests** are retained
facts from the older runtime, not native-supervisor execution proof. The first
redirected native task compiled the prior source freeze but failed on an incorrect
replay callback-null assertion: the actual callback was an authorization refusal,
with no command, output directory, exit or supervisor identity and native
confirmation false. Its original XML/log/START/process/report bytes remain under
`build/evidence/library-native-tests/20261104-first/` and
`build/evidence/library-native/20261104-first/`; it is not passing-suite evidence.
The corrected test/provenance sources require a fresh verified rerun: the existing
`verified-jvm-syntax.json` and `verified-jvm-coroutine-upstream.json` sidecars have
all current per-rule implementation/testing/qualification/attempt flags withdrawn.
Their original tuples, contexts, receipts and referenced bytes are retained in
`runs/2026-11-04T11-09-38Z-4c26474e-historical-pre-native-attribution-withdrawal/`;
its hash-backed `historical-tuple.json` indexes the immutable originals.

Fresh passing native runs use only `verified-jvm-syntax-native-preview1.json`
and `verified-jvm-coroutine-upstream-native-preview1.json`. START captures the
actual internal helper loader's exact CodeSource JAR, or its literal same-module
`build/classes/kotlin/main` to `build/resources/main` resources, together with
source originals and bounded project code images. The manifest is bounded to
512 bytes, binary to 1 MiB and informational build receipt to 16 KiB. Binary
bytes are independently hashed; the C-source digest remains a build-manifest
claim, not reproducible source-to-binary proof. Each executed process and CLI
probe must record explicit `executionConfirmed` and the **actual outcome's**
`supervisorIdentity`, matching START. Reports use the runtime's anchored reader,
not directory scans. Sources, images, tools and native resources are checked
through END. Unsupported targets invoke no runner; changed-permit replays retain
the actual runtime denial callback and require full no-execution invariants,
including unavailable/exact denial reason, native confirmation false, absent
identity/exit/output directory, empty command/stdout/stderr and zero output bytes.
The callback itself is not process-start evidence; a false confirmation alone
would also be insufficient to exclude an unconfirmed fork.

New contexts bind protocol/target/C-source/binary identity plus
`runtime_execution_source_digest` and `runtime_code_image_digest`. A different
filename alone cannot isolate an identical context, even with false old flags;
changed digests cannot borrow old tested evidence. Partial/failing runs retain
their fresh archives and withdraw only new native-suite live sidecars. Ordinary
parser/bookkeeping tests do not establish detector testing, and no historical
receipt or context is rewritten to look native-supervised.

The full-mode coroutine fixture uses the actual JDK Thread callable through an
alias and a resolved local-owner negative. It can emit upstream guidance, but
the narrowed catalog row remains unsupported/unimplemented. Other library
refinements are not silently promoted by this example. A Kotlin2.4.21 stdlib
classpath is hash-verified separately from the embedded Kotlin2.4.10 engine.

## Coverage and gaps

The frozen map contains all 223 catalog core identifiers, 43 Compose identifiers,
nine narrower coroutine/library rows and the owned-resource refinement:
**276 mappings**, 251 exact routes, 15 excluded aliases, 10 refinement gaps.
Optional `libraries` and `ruleauthors` rule sets require their two separately
pinned plugin JARs; they are not bundled in the CLI. Mapping is not testing.

Narrowed cancellation, Result recovery, dispatcher policy, coroutine ownership,
cold-Flow and resource rules remain **unimplemented refinements**. Raw upstream
diagnostics cannot prove those catalog contracts. There is no lexical/regex
replacement for resolved library semantics, no fabricated Room/Coil/JUnit
detector, and no claim of Native/common-target completeness. A full semantic
analysis needs actual pinned artifacts, exact compilation members, known model,
configuration/toolchain/dependency identities and a complete declared graph.
Unknown prerequisites abstain; disabled/skipped/unqualified rules never produce
successful coverage certificates.

`scripts/rule_coverage.py` emits every catalog record in every supplied/observed
context, including advisory, opt-in and excluded records. Its default unknown
context contains all **1,663** records. Six hash-backed fixture types are required
for `tested`; explicit separate qualification evidence, known module/compilation/
compiler/JDK/library and model/classpath/toolchain/configuration/policy identities,
and no gaps are additionally required for `qualified`. The scoped
`all_requested_cells_qualified` flag is not a complete release matrix;
`release_qualified` remains false without independent required-matrix and release
gates. Local evidence sidecars are attestations, not execution permissions or
independent release certificates.

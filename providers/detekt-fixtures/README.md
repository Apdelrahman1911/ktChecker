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
authority or test result. The corrected 2026-11-04 native rerun exercised this override with genuine
processes at `build/evidence/library-native/20261104-rerun`, not merely property
forwarding.

For an installed CLI, select the resolved artifact directory using
`--detekt-artifacts` and explicitly authorize execution. A serialized
`approved=true` cannot supply authority. The authenticated host must create an
exact `ExecutionPermit` binding the prepared input, immutable materialization,
verified classpath, owner-selected rules, settings and profile. The default
provider denies execution. No project code, comment, arbitrary plugin or shell
command is accepted as a provider profile.

## What the fixtures establish

The verified opt-in JVM test runs real positive, negative, configuration, version,
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
download is hidden inside a test. The corrected redirected native rerun on
2026-11-04 passed **14/14 Detekt tests, with zero skips/failures/errors**. Six
syntax analyses, one CLI version probe and two raw-coroutine analyses produced
**nine actual native exec confirmations**. One changed-configuration replay
retained the exact runtime authorization-denial callback; it launched no child.
Two Common/Native requests abstained without invoking the runner.

The first redirected native task failed solely on an incorrect replay
callback-null assertion (13/14, no skips/errors), not a native child launch on
replay. Its original XML/log/START/process/report bytes remain under
`build/evidence/library-native-tests/20261104-first/` and
`build/evidence/library-native/20261104-first/`; it is not passing-suite evidence.
Correcting the assertion did not rewrite that failure. The successful rerun's
JUnit/log/count/source records remain under
`build/evidence/library-native-tests/20261104-rerun/`.

The immutable rerun publication is
`runs/2026-11-04T11-41-12Z-e34ef956-verified-native-preview1-tuple/`:
**254 hash-exact originals**, with separate **45 current-source guards** in
`native-tuple.json`. The two new live native sidecars relocate reference paths
only; original SHA strings, context and raw semantics remain unchanged. They
justify just these three local, unqualified provider/context rows:

- `ktc.kotlin.detekt.empty-blocks.empty-catch-block`
- `ktc.maintainability.detekt.libraries.forbidden-public-data-class`
- `ktc.compose.pack.ComposableParamOrder`

Raw coroutine guidance remains **unimplemented/untested/unqualified**. Across
both providers, the corrected rerun justifies **seven provider/context rows,
six unique catalog IDs**, not seven unique rules.

The old `verified-jvm-syntax.json` and `verified-jvm-coroutine-upstream.json`
sidecars still have all current per-rule implementation/testing/qualification/
attempt flags withdrawn. Their original tuples, contexts, receipts and referenced
bytes remain unchanged in
`runs/2026-11-04T11-09-38Z-4c26474e-historical-pre-native-attribution-withdrawal/`,
indexed by `historical-tuple.json`; the native rerun does not renew them.

The tested native tuple is specifically Linux x86_64, protocol
`ktchecker-owned-child-supervisor/preview-1`, with:

| Captured identity | SHA-256 |
|---|---|
| C source claim | `453468d9b07489c79ac4a061e9ae9fc6d00d58d98c473670bb6885e3f23ea0fe` |
| Actual helper binary | `c6f16891a34b53847c780d1af7972d78f613e631341e621404ca121c6332fb63` |
| `Execution.kt` | `38b51316e03eb622099fede43ff678e6417aed3c347e0a9acfa1e63f6e7eebfd` |
| Actual START runtime JAR | `f0de15bd471311772028bbe08425588a2fb76702daa764b4a0f440ac8f868b31` |

At publication a later build's JAR differed only in its informational
`native/build-provenance.json` entry. The archived original JAR was recovered
byte-for-byte from its complete retained START image, not reconstructed or
assigned a new hash. That later JAR/context cannot borrow this execution.
Neither source hashes nor this Linux run establish reproducible-build,
macOS/Windows, compiler/library or native-platform qualification.

The verified native run uses only `verified-jvm-syntax-native-preview1.json`
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

## Separate bounded corpus

The ten-rule/91-analysis independent corpus remains **not_run**, not excluded
from scope. Source-only controls select the approved `exceptions-core-v1` cohort
(three IDs, 28 analyses plus one CLI, six abstentions and three denials: 38 calls),
with hard 40-call/20-minute/60-second limits and separate evidence/context.
Corpus compilation and real execution require the lead's explicit schedule; the
ordinary verified run above supplies no corpus evidence. See
[the corpus plan](corpus/syntax-v1/README.md).

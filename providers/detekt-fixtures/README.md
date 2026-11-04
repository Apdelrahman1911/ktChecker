# Pinned isolated Detekt fixtures

This adapter runs the real Detekt **2.0.0-alpha.6** CLI in a separate, bounded
JDK21 process. The engine embeds Kotlin **2.4.10**; the project is built using
Kotlin **2.4.21**. Isolation avoids dependency contamination, but does not prove
compiler/language/API compatibility. No tuple or release is qualified.

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

For an installed CLI, select the resolved artifact directory using
`--detekt-artifacts` and explicitly authorize execution. A serialized
`approved=true` cannot supply authority. The authenticated host must create an
exact `ExecutionPermit` binding the prepared input, immutable materialization,
verified classpath, owner-selected rules, settings and profile. The default
provider denies execution. No project code, comment, arbitrary plugin or shell
command is accepted as a provider profile.

## What the fixtures establish

The opt-in JVM test runs real positive, negative, configuration, version,
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
download is hidden inside a test. Hash-backed sidecars attest only cases actually
executed. Ordinary parser/bookkeeping tests do not establish detector testing.

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

# Pinned isolated Ktlint fixtures

This optional adapter runs the real **Ktlint 1.8.0** CLI and **Compose rules
0.6.8** in a separate, bounded JDK21 process. Ktlint embeds Kotlin **2.2.21**;
the ktChecker test harness is compiled with Kotlin **2.4.21**. Isolation and
artifact hashes do not establish parser/compiler/library compatibility.

## Execution hosts

Linux/macOS execution requires the installed host-matching owned-child native
bundle: exact `manifest`, `<target>/supervisor` and informational
`build-provenance.json` under `dev/ktchecker/providers/runtime/native/`. Supported
build targets are x86_64/aarch64 Linux and macOS. Gradle builds it explicitly
using Python and an absolute owner-selected C compiler (`/usr/bin/cc` by default).
There is no runtime compilation, PATH helper discovery or `setsid`/`kill`/Perl
fallback. See [the native contract](../../modules/provider-runtime/native/README.md).
The selected JDK21
must provide regular, non-symlink `bin/java`, `release`, `lib/modules`, and
`lib/server/libjvm.so` (Linux) or `lib/server/libjvm.dylib` (macOS). These
prerequisites are not evidence that a hosted fixture run passed. A matching
bundle is availability, not platform qualification; Linux checks do not qualify macOS.

Windows is unsupported: the runtime has no verified Windows containment backend,
and this adapter additionally binds Unix JDK inputs. Merely substituting
`java.exe` cannot establish safe execution. CI must retain explicit unsupported
host metrics and `-PktlintIntegration=false` skips, not count those methods as
executed or disable containment to obtain a green result.

## Installation and explicit execution

```sh
python3 providers/ktlint-fixtures/resolve_artifacts.py --download
python3 -m unittest discover -s providers/ktlint-fixtures -p test_resolve_artifacts.py -q
./gradlew :provider-ktlint:test -PktlintIntegration=true --no-daemon
python3 scripts/rule_coverage.py --output build/reports/rule-coverage.json
```

The resolver is offline by default. With explicit `--download` it fetches only
three pinned official Maven Central JARs into the ignored `.plan-deps/ktlint`
cache. Published SHA-1 and independently computed, committed SHA-256 pins are
both verified; cached bytes are rechecked every time. Corrupt caches, symlinks,
redirects and partial downloads are rejected, not silently replaced. The adapter
never downloads or loads the engine/plugins into the host JVM. These optional
external bytes are **not bundled** in ktChecker; the direct license inventory
does not constitute an exhaustive shaded/transitive license review.
The dependency-only `common-ktlint` JAR belongs to the child's fixed CLI classpath;
only the actual Compose provider JAR is passed as an upstream ruleset.

CI can add
`-PlibraryFixtureEvidenceRoot=build/ci-evidence/raw/library-fixtures`.
Gradle resolves this path against the repository root; only artifact and sidecar
destinations move to fresh `ktlint/runs/<run-id>/evidence-artifacts/` archives
and `ktlint/evidence/` sidecars. Source fixtures and START source witnesses remain at their
original repository paths. The destination must be inside repository `build/`
with no symlink path components below the repository root. Omitting the property
retains fresh archives and sidecars under this directory's `runs/` and `evidence/`;
old shared `evidence-artifacts/` bytes remain historical, not overwritten. This
archival setting neither authorizes execution nor establishes that a test passed.
The next verified native integration must exercise the override with real cases.

`KtlintInstallation` selects the JDK executable/home and artifact directory;
it grants no permission. The authenticated host must obtain
`KtlintProvider.executionPlan(input)` and inject an exact, non-serializable
`ExecutionPermit` binding the immutable registered materialization, complete
analysis key, selected rules, generated configuration and artifact/JDK digests.
The default authority denies execution. Transport `approved=true`, project
comments and a configured installation cannot authorize a process.

## Profile boundaries

Execution deliberately requires target `jvm`, declared syntax/compiler version
`2.2.21`, a selected JDK21 and exact captured Kotlin compilation members.
**Project Kotlin 2.4.21, unknown versions, Common, Native, Android and Desktop
profiles abstain** rather than inherit an unverified parser compatibility claim.
Even the exercised synthetic JVM syntax tuple is partial and unqualified; it
does not certify a real project KGP, Compose compiler, library or release.

A captured root `.editorconfig` with exactly one `root = true` boundary is
mandatory, preventing ambient parent inheritance. Upstream ec4j handles captured
child configuration/style inheritance. Generated fallback configuration disables
all known application rules except explicitly selected IDs. Project
configuration cannot enable groups, unknown, excluded, refined or unselected
rules. Selected rules may be disabled by captured scope policy. Old disabled-rule
aliases and disabling upstream suppression infrastructure are rejected. Host
settings accept safe EditorConfig properties, not arbitrary CLI arguments,
reporters or plugins.

The process uses real lint-only JSON output and debug scan/error counts. It
receives no formatting option, shell or project code execution. Every reported
range must belong to exact manifest-bound compilation bytes. Empty `[]` is not
clean evidence without matching genuine completion and exit status. Parse/rule
failures, unknown IDs, missing inputs and unqualified runs cannot produce
successful coverage certificates. Sources are verified unchanged afterward.

## What the opt-in tests exercise

Four selected syntax routes have authored real positive, negative, disabled configuration,
CLI-version/parser-mismatch, upstream file-scoped suppression with unsuppressed
neighboring files, and immutable snapshot-edit regression fixtures:

- `standard:no-wildcard-imports`
- `standard:no-trailing-spaces`
- `standard:final-newline`
- `compose:param-order-check` (catalog `ComposableParamOrder`)

Additional real cases exercise child EditorConfig inheritance, the wildcard
allowed-package exemption, malformed Kotlin and an explicit declaration-scope
Compose suppression gap. The Compose annotation is an
**explicit syntax stub**, not an actual Compose API/compiler compatibility test.
Suppressions use genuine upstream IDs. Compose 0.6.8's ParameterOrder rule scans
functions from its file visitor; Ktlint 1.8.0 checks suppression at that visited
file rather than each emitted function offset. A declaration annotation therefore
does **not** suppress this rule. The gap control retains both findings from the
original failing declaration fixture, without host filtering. The six-case
suppression evidence is file-scoped only, not declaration-scope compatibility.
The original five-finding failure and START bytes are preserved in
`runs/2026-11-04T10-03-07Z-failed-compose-declaration-suppression/`.
Arbitrary ktChecker-ID suppression aliases remain unimplemented.
Fixture JSON preserves intentional trailing spaces
and missing final newlines without repository whitespace violations.

Integration methods are visibly skipped unless `-PktlintIntegration=true`;
there is no test-time download. Historical **18/18 Ktlint tests** remain genuine
older-runtime facts, including the retained declaration-gap findings at lines
**6 and 10**, not native execution proof. The first redirected native task compiled
the prior source freeze but failed on an incorrect replay callback-null assertion.
The actual runtime callback was an authorization refusal with no command, output
directory, exit or supervisor identity and native confirmation false. Original
XML/log/START/process/report bytes remain under
`build/evidence/library-native-tests/20261104-first/` and
`build/evidence/library-native/20261104-first/`, not passing-suite evidence.
Corrected test/provenance sources require a fresh verified rerun.
`verified-jvm-syntax.json` has all current per-rule
implementation/testing/qualification/attempt flags withdrawn. Its unmodified
original tuple, context, receipts and referenced bytes remain in
`runs/2026-11-04T11-09-38Z-e2998f0c-historical-pre-native-attribution-withdrawal/`,
indexed by hash-backed `historical-tuple.json`.

Only a fresh verified run may produce `verified-jvm-syntax-native-preview1.json`.
START retains the actual internal helper loader's exact CodeSource JAR, or
literal same-module `build/classes/kotlin/main` to `build/resources/main` resources,
plus source originals and bounded project code images. The manifest is bounded
to 512 bytes, native binary to 1 MiB and informational build receipt to 16 KiB.
Binary bytes are independently hashed. The C-source digest is the installed
build-manifest claim, not source-to-binary reproducibility proof. Every genuine
syntax/configuration/parse-failure/gap control and CLI probe requires explicit
`executionConfirmed` and actual outcome `supervisorIdentity` matching START.
Reports use the anchored runtime reader, not scans. Unsupported/parser-mismatch
requests invoke no runner. Changed-permit replays retain the actual runtime denial
callback and require unavailable/exact denial reason, native confirmation false,
absent identity/exit/output directory, empty command/stdout/stderr and zero output
bytes. A callback is not process-start evidence, and false confirmation alone
does not exclude an unconfirmed fork. Drift checks cover
sources, images, tools and installed resources through END.

Native contexts bind protocol/target/C-source/binary identity and
`runtime_execution_source_digest`/`runtime_code_image_digest`; different filenames
cannot isolate identical tuples or transfer old testing to changed digests.
Failed/partial runs retain their fresh archives and withdraw only the new native
live sidecar. No old receipt or context is rewritten or source hash renewed.
Unit/parser tests do not establish detector testing.

## Complete retained map, incomplete qualification

All **137** catalog aliases are retained: 102 standard/internal and 35 Compose.
There are **131 exact syntax routes**, five explicit excluded aliases (two
deprecated no-ops, suppression infrastructure, ComposableNaming and
CompositionLocalNaming), and one semantic-depth refinement:
`ModifierShouldBeForwardedToEveryBranch`. A Ktlint PSI alias is not equivalent to
resolved Detekt Analysis API semantics. Exclusions and refinements cannot activate.

Mapping is not implementation, testing or qualification. Four historically
exercised routes do not validate the other 127 or current edited controls. The complete coverage inventory retains
all 1,663 catalog records per context; only hash-backed cases actually executed
may claim `tested`. Runtime results remain partial, `tested=false`,
`qualified=false`, with no successful certificates. Release qualification remains
false without an independent complete required matrix and release gates.

# Project-model owner fixtures

These are explicitly selected trusted fixtures, **not** arbitrary-project enrollment,
live-worktree capture, architecture-rule qualification or a security sandbox.

| Fixture | Purpose | Current evidence |
|---|---|---|
| `jvm-owner-export` | Concrete owner exporter → trusted importer path; two JVM modules, production/test associations, project producer variant and resources | Model 47/47 units passed at 11:49 UTC; fresh-home actual process verified, but exact compilation domain rejected; no imported model/certificate or qualification |
| `shared-kmp` | Common → intermediate → server/desktop JVM targets, shared resource, reverse project consumers and independent module | Source bytes pinned; actual metadata compilation domain not yet executed/reviewed |

The first central model unit run compiled and ran 26 tests: 24 passed and two
rejection probes failed on expected exception precedence (the malicious inputs
were rejected). Those probes were corrected without weakening their controls;
the corrected 27-test suite, including then-current runtime-freshness assertions, passed a
fresh central run on 2026-11-04 at 10:22 UTC (zero failures/errors/skips). The small
Python preparation-boundary suite passed 2 tests, freshly rerun normally and under
`-O` after the regular-lock/class-copy and five-launcher-helper corrections. The historical model source had
37 tests after immutable-seed, bounded/strict-UTF-8 reads, capability-specific host
corrections and four pure native-completion/startup-evidence regressions;
all 37 passed the fresh central run on 2026-11-04 at 11:15 UTC. The same run passed
config 35/35 and architecture 17/17, with zero failures/errors/skips in every suite.
Exact XML, build log and the executable/test-source digest checkpoint are retained
under `build/evidence/model-native-start/20261104-first/`. The pre-run 71-owned-file
plus six-runtime-source freeze remains separate from subsequent README-only status
updates. The current 47-test model suite, including precise completion/callback
rejections and seven fresh execution-home regressions, passed centrally at 11:49 UTC
with zero failures/errors/skips. Its exact XML/logs and source evidence are retained
under `build/evidence/model-cli-composition/20261104-first/`; separate CLI failures
in that command are not included in this model-suite success. Neither unit result
is an actual Gradle exporter invocation or runtime qualification.

`input-contract.json` for the basic JVM case is independently pinned by the trusted
test-driver source. It specifies every producer source/configuration file, source
and resource root, expected compilation identity, association and project producer.
Editing build scripts, omitting resources, dropping contexts, inventing generators,
or adding a JSON approval flag cannot relax this contract. The known fixture's
closed configuration code/read set is the completeness basis; an export's empty
lists or a coherent partial source overlay are not that basis.

## Explicit owner input preparation

First obtain a dedicated, owner-controlled offline seed with the fixture's pinned
Kotlin 2.4.21 dependencies and required Gradle metadata. Do not copy an entire
multi-gigabyte ambient Gradle cache or run concurrent Gradle workloads. Select the
actual Gradle 9.6.1 bin ZIP matching the repository wrapper SHA-256, its extracted
distribution, an explicitly prepared **link-free JDK 21 image** and the dedicated
seed. `scripts/prepare_jdk_image.py` owns source-link topology, explicit external
lookup roots and reasoned dangling-link exclusions; it does not execute Java by
default. Its outside private receipt is bookkeeping, never model authority. The
model preparer independently copies/hashes the resulting selected image and
rejects any remaining file, directory or parent-path aliases:

```sh
python3 providers/model-fixtures/prepare_owner_inputs.py \
  --gradle-home /owner/gradle-9.6.1 \
  --gradle-archive /owner/gradle-9.6.1-bin.zip \
  --java-home /owner/prepared-link-free-jdk-21 \
  --gradle-seed /owner/model-offline-seed \
  --output /owner/fresh-private-model-inputs
```

Preparation makes real private copies, not symlinks/hardlinks to mutable artifacts.
Its new output root is explicitly owner-only (`0700`), regardless of ambient umask;
files are owner-readable or owner-readable/executable. It preserves and records
directory topology, including empty directories.
It does not download, launch a process or authorize a model. Only actual regular
cache `.lock`/`.lck` files may be omitted automatically; aliases and special files
cannot use that exemption. User init scripts/properties are forbidden in the
seed. JDK omissions or alias resolution must occur only in the separate explicit
JDK-image preparation step: the selected image is not a claim of original-vendor
JDK equivalence. Total copied inputs are bounded to 2 GiB and 20,000 file/directory
entries; the intended focused seed keeps a run near 1 GiB.

One explicitly approved local preparation completed on 2026-11-04 at 11:24 UTC:
6,136 registered files and 8,557 directories, 1,030,768,730 file/archive bytes,
and 329 actual regular seed locks omitted. The real 140,682,664-byte Gradle ZIP
matched the independent wrapper pin. Selected source inputs, copied seed and
runtime/archive identities matched independent before/after checks. No Java,
Gradle, download or exporter execution occurred during that preparation. This
bounded cached selection is not offline-resolution or selected-variant closure
proof; no additional ambient cache expansion was performed.

A distinct approved fresh-input preparation completed at 11:55 UTC using the same
selected Gradle/JDK/ZIP and the untouched preserved selected seed. It did not reuse
or rebaseline the first export's mutated prepared seed. The same file/directory/byte
counts and independent original/copy before/after identities were verified; no
Java, Gradle, download or exporter execution occurred during this preparation.

Run only after coordinating the repository's single resource-heavy build slot:

```sh
./gradlew --no-daemon --max-workers=1 :project-model:ownerModelFixture \
  -PmodelFixtureInputs=/owner/fresh-private-model-inputs/owner-inputs.json \
  -PmodelFixtureResult=/owner/private-model-evidence/result.json
```

The trusted driver independently hashes the pinned Gradle ZIP, derives its entry
identities and checks **all actual extracted runtime bytes**, not a digest assertion
inside input JSON. Before loading those owner inputs or launching any process, a
private START capture binds the authored C/build and JVM adapter/transport/image
sources, the actual provider-runtime CodeSource (bounded installed JAR or exact
same-module classes/resources trees), and that image's native manifest, binary and
informational build receipt. Fixed runtime class roles must share that CodeSource;
there is no classloader-wide lookup or PATH `setsid`/`kill` installation. The five
registered system helpers are only Gradle launcher helpers. START copies preserve
file bytes and directory topology read-only; image capture is bounded to 64 MiB,
8,000 entries, 32 MiB/file and 4 MiB of evidence, guarded before serialization.
Native manifest/binary/receipt bounds are 512 bytes/1 MiB/16 KiB.

The owner runtime identity combines the pre-execution input registry, START and
initial execution-home digests in its separate `/3` domain. Runtime/START freshness callbacks recheck these
originals and their preserved copies without recapture or enrollment. The actual
runner's returned/onComplete outcome must have terminal-derived execution
confirmation, successful zero exit, no failure flags/reasons and exactly the START
supervisor identity. A later bundle inspection, a zero fork-child exit or a JSON
success assertion cannot substitute. Failed actual outcomes and local process
logs are retained even when that comparison fails. Pure synthetic positive tests
explicitly set confirmation; production never constructs a stand-in completion.
The C hash is checked only against the bundle's **declared** source claim: neither
the source/image identities nor build receipt attest a source-to-binary build,
compiler-transitive closure, loaded JVM memory, host loader/libraries or a runtime
tuple. Only provider-runtime bytecode/resources are captured; model-harness/importer
and other classloader dependency bytecode are not. Authored harness source remains
a separate identity. Windows containment remains unavailable.

The driver registers private runtime and all immutable non-lock seed input identities before launching
the exact offline exporter profile against the registered immutable CAS overlay.
Only the actual successful, zero-exit, quiescent process completion and its fresh
anchored report can supply the importer callback. External artifact identities must
match the pre-execution registry. The complete-domain callback checks the entire
fixed source/resource/configuration/compilation domain; the pinned exporter script
is the sole external configuration input. Every permit/import verification rechecks
the closed Gradle/JDK/seed file and directory inventories, registered immutable
non-lock input paths/bytes and executable flags, private-root permissions and absence
of external seed configuration. Cached Kotlin/Groovy DSL `.class` files, metadata
and other existing non-lock cache files are immutable inputs, not bookkeeping.
Only regular original-seed `.lock`/`.lck` files inside already registered directories
may appear or change. Original new directories, aliases, new non-lock files and
changed existing non-lock bytes fail closed, even when Gradle could legitimately
generate them. The separately reviewed derivation creates a fresh concrete
`execution-home` before constructing the profile; `GRADLE_USER_HOME` is that literal
path, not an environment placeholder. Its initial complete source/copy mapping,
empty-directory topology, sizes, bytes, modes and independent inode identities are
bound in immutable evidence, with anchored/no-follow copying and `nlink == 1`.
The final preparation callback rechecks the whole initial map before allowing
launch; this phase marker does not prove execution.

Only exact preselected files under `caches/modules-2/files-2.1/` are pre-enrolled
read-only dependency copies. Other copied metadata/DSL caches are explicitly
writable derivation scratch, not artifact authority. New native/DSL/transformed
outputs are never enrolled, even when bytes match an existing artifact. Later checks
retain the initial source map and dependency-copy inode/mode/size/byte checks,
composed with complete original registry byte freshness. Derivation is bounded to
512 MiB/file, 2 GiB/copy, 20,000 initial entries, 50,000 walked entries and 32 MiB of
evidence guarded before serialization. Failed partial roots remain distinct private
attempts, never reused, purged or silently substituted. Manifest (8 MiB), pinned
contract (1 MiB) and source (32 MiB) reads are anchored/no-follow and bounded before
content allocation, with file identity and channel drift checks. Manifest, pinned
contract and JDK release text reject malformed UTF-8 rather than decode replacements.

Synthetic exporter-profile tests use host-native absolute paths but do not execute
them. Positive model/architecture tests require actual anchored filesystem reads;
unsupported hosts report explicit capability-specific JUnit skips, and a separate
test checks that unavailable anchored reads never fall back to trusting inputs.
Only fresh trusted outer temp roots are canonicalized; arbitrary artifact/source
descendants are not dereferenced to bypass the no-follow boundary. No Windows
model/runtime support or qualification is inferred from portable profile tests.

Results retain the exact report, model identities, every initial compilation
obligation and structured gaps. Unbuilt project/friend outputs or unsupported
exporter API vocabulary remain unavailable, never silently enrolled after execution
or claimed as complete compiler coverage. Private process logs stay local. A passing
fixture is not qualification: `qualified` remains false, and model identities do
not prove executable relocation of friends/plugins/runtime/auxiliary compiler inputs.

## Actual exporter attempts

The first actual invocation was terminal-confirmed, exited zero and matched the
independent START native identity, but its process outcome was rejected after
immutable-seed mutation. New semantic cache directories/files and changed existing
metadata were not enrolled or deleted. The failed attempt and original inputs remain
preserved; the earlier generic execution-not-confirmed classification was corrected
only in later source, not rewritten in historical evidence.

The second actual invocation used the fresh writable-home contract. Its process
outcome was `executed`, terminal-confirmed, exit zero, with no failure flags/reasons
and the exact START native identity; original seed/dependency authority verification
did not reject it. The owner importer nevertheless returned
`owner_fixture_compilation_domain_incomplete`: all four actual single-JVM target
names were empty (`:consumer||main/test`, `:lib||main/test`), not the pinned `jvm`
identities. The raw report also retained compiler, missing-artifact, produced-variant
and dependency-resolution gaps. No model identity/certificate was issued. Exact
raw result/report, source copies and process/home evidence are retained under
`build/evidence/model-export/20261104-fresh-home/`; private process logs stay local.
Empty context/obligation fields in this unavailable envelope do not retire or waive
the four fixed required compilations. Neither attempt claims compiler success or
runtime qualification. Any target/API/domain correction requires separate review;
no report-driven normalization, new artifact enrollment or rerun is implied.

Generation, deleted contexts, unknown Native/Android/composite graphs and narrow
suppression provenance have separate fail-closed unit boundaries. The KMP fixture
must obtain an independently reviewed exact metadata compilation contract before
the owner importer harness accepts it; observing JSON alone cannot create that
authority.

# Independent Detekt syntax corpus, batch 1

**Authoring only: corpus compilation, control-test execution and detector
execution are not claimed yet.** Twelve lightweight Python checks validate
manifest identities, spans, suppression neighbors and source-only bounded-driver
guards; they do not compile Kotlin or run Detekt. The Kotlin sources additionally
author 20 non-process control tests and one explicitly opted-in integration test.

This separate corpus contains **10 existing exact syntax routes**: eight advisory
rules and two explicitly opted-in conventions. It does not change the 276-rule
inventory, catalog activation, provider implementation, existing two integration
methods, or their historical evidence. No extra IDs were selected to inflate
coverage. Ktlint style expansion is deferred behind useful correctness/library
work; its deprecated `no-unused-imports` alias remains visible and unvalidated.

## Predicate and valid-alternative matrix

Each JSON file retains its complete catalog ID, exact upstream ID, tagged
implementation URL, research source-file SHA-256, source bytes and independently
authored inclusive-start/exclusive-end locations. Coordinates use one-based lines
and zero-based columns. The source hashes were researched from the official
Detekt 2.0.0-alpha.6 source JARs after Maven Central SHA-1 checks; those optional
JARs are not bundled or execution evidence.

| Upstream ID | Selection | Bounded predicate | Independent alternatives and boundaries |
|---|---|---|---|
| `exceptions:ThrowingExceptionFromFinally` | Advisory | Every descendant `throw` in a finally block | Catch rethrow is legal; deferred callback and locally handled throws expose missing effect/control-flow reasoning. |
| `exceptions:SwallowedException` | Advisory | Unused caught name or new throw using only derived exception fields | Original/cause-preserving rethrows, a local cause alias, real ignored-type/name settings, and an inner shadowed name. Default ignored interruption is not cancellation safety. |
| `exceptions:NotImplementedDeclaration` | Advisory | Calls named `TODO` with zero/one arguments; thrown constructor named `NotImplementedError` | Comments/string text, custom normally returning `TODO`, custom domain exception with the same name, and a two-argument custom function. |
| `potential-bugs:EqualsAlwaysReturnsTrueOrFalse` | Advisory | Proper equals shape with Boolean literal return | Identity equality, conditional results, an unrelated String overload, and computed nonliteral constants. |
| `potential-bugs:InvalidRange` | Advisory | Int constant text for `..`, `downTo`, `until`, `..<` bounds | Inclusive versus exclusive equality, dynamic bounds, Long suffixes and unary-negative expressions; no general evaluation claim. |
| `potential-bugs:IteratorHasNextCallsNextMethod` | Advisory | Textual `Iterator` supertype and any call named `next` in `hasNext` | Legal helper implementation, reading a different iterator, and a custom stateless protocol named Iterator. No receiver/symbol or interprocedural proof. |
| `potential-bugs:UnconditionalJumpStatementInLoop` | Advisory | Direct jump shapes with limited label/Elvis exceptions | Conditional returns, expression loops, matching labeled continue, Elvis continue/return, and a continue that does not actually exit the loop. |
| `potential-bugs:UselessPostfixExpression` | Advisory | Same-spelled assignment or local return postfix | Standalone retained increment, persistent object property, and an overloaded `inc` mutating the returned object. |
| `style:DataClassShouldBeImmutable` | Opt-in | `var` constructor/body data-class properties | Immutable primitive/String values, mutable references held by `val`, ordinary mutable classes and a deliberately mutable DTO. Not deep immutability or universal correctness. |
| `libraries:LibraryEntitiesShouldNotBePublic` | Opt-in | Public classes, aliases and top-level functions | Internal implementations, a required exported immutable API, public properties and inner-class bounds. Actual public API visibility is an owner policy. |

The anticipatory gap labels describe authored intent and the pinned source
predicate, **not observations from an already executed test**. If the eventual
upstream run emits a finding on a valid alternative, the runner retains it with
its raw report and exact range. It must not filter it, relax counts, silently
renew evidence, or convert a tested heuristic into qualified defect semantics.
Unexpected behavior fails the corpus and preserves the partial run for review.

## Explicit bounded cohort; full scope stays not_run

The approved ten-rule corpus still plans **91 detector analyses plus one CLI
probe**; the full corpus remains **not_run**, not a scope exclusion. There is no
`all`/full-ten execution selector. Enabling integration alone fails before any
provider call unless the owner explicitly selects **`exceptions-core-v1`**:

| Selected existing catalog ID | Standard + independent controls | Detector analyses |
|---|---|---|
| `ktc.kotlin.detekt.exceptions.throwing-exception-from-finally` | 6 + 2 | 8 |
| `ktc.kotlin.detekt.exceptions.swallowed-exception` | 6 + 5 | 11 |
| `ktc.kotlin.detekt.exceptions.not-implemented-declaration` | 6 + 3 | 9 |

That cohort has **28 analyses and one real CLI version probe**, at most **29
native launches**. Six Common/Native requests and three changed-configuration
replays are also reserved: **all 38 analyze/probe invocations count** against the
configured ceiling. Replays must retain the exact runtime denial callback,
not a missing callback: unavailable/exact `execution_not_authorized`, confirmation
false, absent identity/exit/output directory, empty command/stdout/stderr, zero
output bytes, no cancellation/timeout/truncation or invented CPU/RSS.

The driver has hard maxima of **40 total invocations**, **1,200,000 ms aggregate
monotonic time** and **60,000 ms per child**. Ceilings above those values, unknown
cohorts, and fewer than 38 available reservations fail before execution. A
child's exact trusted profile is capped to the current remaining aggregate
budget; the same aggregate-deadline cancellation token also covers provider
preparation and native execution. START capture, drift checks and between-call
preparation consume the aggregate budget. Native cleanup may need its runtime
grace after cancellation; it is not bypassed to satisfy a deadline. No subsequent
reservation, retry or passing sidecar is permitted after exhaustion/failure.
The lead's separate outer watchdog must include compilation and cleanup time.

Progress uses bounded, immutable **CREATE_NEW** event files: reservation before a
call, actual process/abstention outcome before assertions, then a checked result.
The next reservation requires a checked actual native terminal/no-execution
signal, never a reused `ExecutionPermit`, callback presence or false exec flag.
For launched children, the runtime's executed/empty-reasons outcome validates
its owned-child terminal/cleanup, together with actual confirmation and full
START-matching supervisor identity. The driver claims no separate raw cleanup
ACK and never kills/enumerates a numeric PID/PGID. Incomplete calls retain their
reservation and actual partial artifacts rather than become inferred successes.

## Separate task; compile and controls before a granted real run

The sources live only in `modules/provider-detekt/src/corpusTest/kotlin/`; the
`corpusTest` source set/task never joins ordinary `test`/`check` or changes the
older two methods' scope. Gradle registration and Kotlin compilation still need
verification. Example commands are plans, **not execution receipts**:

```sh
python3 -m unittest discover -s providers/detekt-fixtures -p test_syntax_corpus.py -q
# Only after the lead grants the single heavy-process slot:
./gradlew --no-daemon --max-workers=1 :provider-detekt:compileCorpusTestKotlin
./gradlew --no-daemon --max-workers=1 :provider-detekt:corpusTest \
  -PdetektCorpusIntegration=false
# Only after compile/control acceptance and a separate real-run grant:
./gradlew --no-daemon --max-workers=1 :provider-detekt:corpusTest \
  -PdetektCorpusIntegration=true -PdetektCorpusCohort=exceptions-core-v1 \
  -PdetektCorpusMaxInvocations=40 -PdetektCorpusAggregateMillis=1200000 \
  -PdetektCorpusChildMillis=60000 \
  -PlibraryFixtureEvidenceRoot=build/evidence/detekt-corpus/selected-run
```

The accepted ordinary Linux native fixture run does not compile or execute this
corpus. Windows remains unsupported, and matching native resources or Linux
checks never qualify another platform. Each executed case requires actual
`executionConfirmed` and the launched helper's complete protocol/target/C-source/
binary identity, matching the exact installed START image, not inspector output.
The declared Kotlin2.4.21 versus embedded Kotlin2.4.10 mismatch remains partial
and unqualified; exception-name/PSI predicates cannot prove resolved library,
control-flow, cancellation, coroutine ownership, Compose or resource semantics.

Every source set is sealed through the real `SnapshotStore` and registered
materialization. A fresh exact permit binds the analysis key, owner-selected rule,
settings/configuration, artifacts, executable and time/output limits. SARIF is
lint-only. Snapshot regression deletes obsolete suppression files and verifies
both child and parent unchanged. Suppression uses upstream **file annotations
with unsuppressed neighboring files**; declaration scope and ktChecker-ID aliases
are not attested. Genuine heuristic-gap findings retain their exact ranges;
there is no count relaxation or filtering of valid-alternative controls.

## Cohort-bound evidence and honest stages

A fresh `runs/<run-id>-syntax-corpus-exceptions-core-v1/` retains immutable START
originals, current-source guards, bounded class-resource/project code images,
exact installed native manifest/binary/informational build receipt and tool/JDK
file digests. Native resources come from the actual loader's CodeSource JAR or
literal same-module `build/classes/kotlin/main` to `build/resources/main` mapping.
Reports use the anchored runtime reader. Source/resource/fixture membership,
class images, installed native bytes, engine pins and Java executable are checked
through END. None proves a reproducible source-to-loaded-bytecode/native build,
transitive toolchain closure, compiler compatibility or platform qualification.

Sealed input, source bytes, generated configuration, raw report, process outcome,
snapshot-bound result, denial/abstention result and checked receipts are retained
before promotion. Evidence I/O is rooted in no-follow directory handles with
same-identity checks; existing files are never overwritten/appended. There are
fixed bounds of 2,000 files, 128 MB aggregate and 32 MB per file, and at most
124 progress events. Directory creation requires stable selected-owner ancestors;
this is not hostile same-user/ABA attestation. Cleanup can remove only a regular,
single-link sidecar with this cohort's exact context; symlinks, wrong cohorts and
replacement identities are refused. Prior cohort live bytes, if present, are
archived exactly before withdrawal. No ordinary or historical sidecar is touched.

Only all 38 checked results, exact counts, full drift/deadline checks and the
successful-test gate can publish
`evidence/verified-jvm-syntax-corpus-exceptions-core-v1-native-preview1.json`.
The context binds the cohort/plan, complete source tuple, corpus code image,
fixture set, configured limits, native identity and runtime execution-source/JAR
digests. Seven remaining authored rows retain `not_run`, all stage/attempt flags
false and no passing cases; only these **three** rows could become locally
implemented/tested, **qualified=false**. A changed context cannot borrow any
ordinary, historical or prior corpus execution. Raw runtime results remain
partial, tested=false, qualified=false, with no successful certificates.

The integration runner has no framework TempDir or unconditional permission/
recursive cleanup. Each run owns a one-shot private `0700` scratch leaf under
its fresh archive; it and every ancestor remain on success as well as failure.
Uncertain throws, poisoned-slot/cleanup-unverified outcomes, cancellation and
incomplete runs quarantine that scratch, never infer quiescence from a return or
exec confirmation. Only an explicit owner audit can authorize later cleanup.

All pre-publication completion/budget/sidecar artifacts explicitly remain
candidate-only with `run_complete=false`; all-invocations-checked is not overall
run completion. Live publication performs final clocks after serialization,
hashing, output bounds, writes and handle closure inside its failure catch.
Late deadline/evidence errors withdraw only this cohort's live file even if the
failure-checkpoint writer also fails. No I/O handle is closed outside that caught
commit boundary. Failed/aborted/deadline-exhausted runs retain bounded progress,
candidates and partial artifacts with no passing live cohort sidecar. The lead must independently retain
and verify real JUnit/build completion; a START witness or checked local receipt
is not a green task, complete ten-rule run or release certificate. The optional
`libraryFixtureEvidenceRoot` above moves only new archive/sidecar output inside
repository `build/` without symlink descendants; source references remain original
repository paths. No corpus compilation or execution has yet been attributed.

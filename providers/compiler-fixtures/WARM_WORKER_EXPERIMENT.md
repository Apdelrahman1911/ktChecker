# Private finite warm-generation experiment

**Source-only feasibility cut.** At authoring/freeze time no new source was
compiled or tested, and no compiler, worker JVM, benchmark, or native execution
was performed for this cut. An independently reviewed source freeze and a
separate owner execution decision are required before any such work. This
document is a contract/proposal, not a passing runtime receipt.

Protocol: `ktchecker/compiler-warm-generation/preview-1`.
Adapter: `kotlin-2.4.21/caller-owned-JvmCliPipeline-1`.

## Deliberately narrow boundary

One explicitly authorized external compiler JVM retains one compiler
`URLClassLoader` for exactly four serial calls:

1. Existing tiny `unresolved_reference/positive.kt` bytes.
2. Existing tiny `unresolved_reference/negative.kt` bytes (clean).
3. The same clean bytes in a different owner-registered job directory.
4. The same positive bytes in another owner-registered job directory.

Then the loader closes and the generation retires. Each call constructs fresh
compiler, arguments, performance manager, JVM pipeline, collector, and
caller-owned disposable. Only class loading/JIT opportunity is retained. No
project, PSI, result cache, daemon, RPC endpoint, pool, reconnect, prewarm process,
retry, replacement generation, or cross-generation reuse is provided.

The private host is independent of ordinary fixture scheduling. It neither
changes the ordinary 98-call suite/graph controls nor invokes a shipped provider
route, changes a registry, grants a certificate, or overwrites ordinary receipts.
Frozen A1/B/C timing and supplemental-selector work are not inputs to a claimed
speedup. `ordinary_fixture_run_complete=false` and `qualification=none` always.

## Exact API and disposal source gate

The installed reviewed Maven coordinate is
`org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21`, 58,593,291 bytes:

* JAR SHA256:
  `ef19419c765e7ac8404465fa026ca8fc4dbb8a822de0fc79aa53c8dce29f1d02`.
* Installed JAR SHA1/cache-directory identity:
  `3cd9c37c505fb9879e03ebeaf91d9c9500ab6eca`.
* Official sources JAR SHA256:
  `c7d2fe069589de7cefdc9aaea962b12ee0b61022749dee15e64ff809f2cd6c0e`.
* Official sources JAR SHA1:
  `5e4d3236e455e4aded0baf7a07224d39c207ff30`.

The sources and SHA1 metadata were acquired once from the exact Maven Central
`https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/2.4.21/`
coordinates: 11,392,134 response bytes, about 2.21 seconds, no redirects/retries,
within the authorized 64MiB/120-second bound. Manifest, installed POM coordinate,
cache SHA1, and official repository checksum consistency were checked. This is
not independent publisher-signature or source-to-bytecode reproducibility proof.
Original artifacts and bounded review receipts are privately retained.

Static Python class-file inspection (no bytecode execution) corroborated the
public descriptors in the installed binary. The exact review is pinned by:

* `installed-classfile-api.json` SHA256:
  `ab58b3f18e402ad99c0e27bf1d79161ee18b5d9ad38e14ec10bc07c46332373d`.
* `api-disposal-gate.md` SHA256:
  `bd1457f10f689266e9542c16be6f6b3285a932f6f3866159dfc2dee79a7617fd`.
* `api-disposal-gate-manifest.json` SHA256:
  `ab8a2511c25198079658f341c7a72ac2041a73ed747d4d3e8ef90f97dd52d17b`.
* Additional five installed class descriptors in `installed-adapter-api.json`
  (43,390 class bytes; no JVM/network activity) SHA256:
  `ccb8b98e36c395d387ac69751770a96e26a73975399d14777601ad18c41fc2ed`.

Reviewed official source locations:

* `CLICompiler.kt:134–146` and `K2JVMCompiler.kt:25–37`: phased execution and
  fresh JVM pipeline/arguments.
* `AbstractCliPipeline.kt:45–74`: public
  `executeAndReturnPipeLineArtifact(arguments, services, collector, providedDisposable)`
  explicitly gives the supplied disposable's ownership to its caller.
* `AbstractCliPipeline.kt:87–122`: upstream setup partly precedes its internal
  try, and collector flush precedes automatic disposal. The adapter therefore
  supplies its own root and closes it from an **external finally**, not merely
  by creating another CLI wrapper.
* `common/utils.kt:113–121`: public `UtilsKt.disposeRootInWriteAction`, using the
  installed shaded `org.jetbrains.kotlin.com.intellij.openapi.Disposable`.
* `JvmFrontendPipelinePhase.kt:196–226,547–560`: project environments attach to
  that root in both light-tree and PSI branches.
* `KotlinCoreEnvironment.kt:539–604`: with keepalive disabled, last-project
  disposal clears the application environment/ApplicationManager references
  and selected caches.

The adapter uses a platform-parent loader for the exact ordered compiler
closure. Reviewed compiler classes must originate from the pinned compiler JAR;
compiler classes never enter the host/broker classloader. It calls the reviewed
pipeline with `Services.EMPTY`, not an arbitrary plugin/service/profile. It does
not call a `System.exit` CLI entry. **This is not CLI byte/behavior equivalence.**

Before another call, the adapter requires a returned disposal invocation, its
own disposable callback observed exactly once, absent application/core-environment
references, and `kotlin.environment.keepalive=false`. There is no public
`Disposer.isDisposed` in this stripped installed binary; no private reflection
workaround is used. These are narrow postconditions, **not proof that arbitrary
static state, compiler threads, logging sinks, or every child disposable reset**.
Disposer can aggregate child errors to logging; unexpected raw stdout/stderr
poisons this generation, but source inspection alone does not prove all such
failures are observable. Actual contamination/reset behavior remains unverified.

## Authority, identity, and cancellation

Four distinct in-process, owner-registered job capability objects bind the job,
generation/owner epoch, implementation, original/effective input digests, fixed
profile, and deadlines. Deserialized equal values cannot replace the objects.
A **separate** exact generation `ExecutionPermit` authorizes the one process
launch. Counts are four job admissions plus one launch grant, not one reused
ordinary one-shot compiler permit. All four job capabilities are consumed
together after the original output callback registers fresh directory keys and
before worker dispatch; admission is not an exec/readiness observation.

The implementation key covers actual captured host component identities, exact
bridge bytes, ordered compiler closure, selected executable/whole JDK image,
installed supervisor identity, fixed launch/environment/resource template,
owner domain, OS/architecture, and private synthetic mode—not version labels.
The host verifies sources, inputs, dependency bytes, toolchain, bridge, and
registered directories before and after the generation. The worker verifies
copied plan/source bytes and fixed directory identities around each call and
the compiler image before/after the generation. Bridges contain only the three
Java worker class families; their bytes must match actual host class files.

Any capability revocation, owner stop, earliest deadline, unknown code,
collector-protocol rejection, cancellation indication, uncertain disposal,
unexpected raw output, integrity drift, or native failure poisons the whole
generation. No independent-client cancellation guarantee exists. In particular,
the reviewed pipeline can report `Compilation was canceled` while returning
API code 0; the collector records that observation and cannot clear it away.
Rejected collector callbacks stay invalid even if upstream catches the error.
Already observed compiler codes survive later disposal/observation failure.

All jobs share a 120-second owner deadline starting **before preparation**.
The existing runner accepts a relative integer-millisecond lifetime, not an
externally armed absolute deadline. The host passes the remaining whole
milliseconds, uses absolute cancellation/post-gates, and rejects late results.
This does not assert nanosecond dispatch equality, independent per-job timers,
or a forcibly bounded trusted in-process callback. Existing bounded JDK capture
may finish after an owner checkpoint expires; that is a failed attempt, never
late authorization. Safe denial/cleanup/publication may outlive the budget.

## Receipts and native terminal boundary

Worker receipts are immutable bounded data frames, **not** authority, a live
process handle, or a per-job native FINAL. They bind version, generation,
implementation, plan, ordered job, input keys, source bytes, and output/job-root
keys. A shared loader token and monotonic call/disposal offsets provide worker
observations, not independent proof of loader/process identity or phase costs.

Only the real `IsolatedProcessRunner` outcome with matching installed supervisor
identity and successful native terminal permits anchored `ProcessOutcome.report`
reads. Native supervision covers the **whole JVM generation**. No receipt is
joined while a worker might remain live. Missing proof quarantines the whole
scratch tree; there is no PID-based cleanup or later path-based resurrection.
This is conservative: even a snapshot/deadline failure after actual shutdown
can leave the generation quarantined because the available outcome is not a
successful native proof. Registered nested-root replacement also forbids cleanup.

Keep three results separate:

* Positive API code **1** is `compiler_failed`, even if the worker OS exit is 0.
  This fixed source must report `UNRESOLVED_REFERENCE` at its exact path/line 3.
* Clean API code **0** without errors is only a clean **unqualified observation**.
* Worker OS exit **0** means its finite sequence and loader retirement returned;
  actual native terminal/EOF/helper cleanup must still be established externally.
  Poisoned/uncertain generations use worker OS exit 70. Early failures may leave
  only a prefix of receipts, or none; missing receipts never imply success.

The summary describes eligibility at its pre-write checkpoint. Successful host
task return after final persistence/deadline checks is separately required;
the summary cannot attest its own subsequent write completion. No ordinary
fixture completion, rule certificate, platform availability, SLO, speedup,
causality, startup-cost attribution, or representative-project claim follows.

## Bounds, source-only tests, and later execution gate

This initial private cut requires Linux and an explicitly selected link-free
JDK21. The child heap is fixed at 512MiB and host at 384MiB. Plans are at most
64KiB; receipts 128KiB; each source 16KiB; per-job diagnostics 128 entries/64KiB;
individual artifact 64MiB and compiler closure 128MiB. Worker output membership,
depth, byte totals, and cleanup entries are bounded. Private evidence uses the
existing quota writer with 128MiB total, a terminal reserve, unique run directory,
and **no external latest sidecar**. This is cooperative owned-tree execution,
not an adversarial same-UID/ABA sandbox or filesystem hard quota. Whole selected
JDK disk identity does not attest loaded/transformed runtime code or OS/JNI
dependencies outside that image.

`CompilerWarmWorkerContractTest` authors codec, capabilities/replay/revocation,
deadline/identity/native-join, and **fake** session/disposal tests. Fake descriptors
do not assert that fake bytes match the compiler JAR. The fake session seam omits
image validation and never constructs the pinned adapter or launches a process.
POSIX-only fake filesystem writer tests are explicitly scoped; pure data/authority
tests remain portable. None of these newly authored tests have yet been run.

The additive `compilerWarmWorkerBridge` and `compilerWarmGeneration` tasks do
not become dependencies of `check`, `test`, ordinary fixtures, or launchers.
The generation task requires explicit
`-PcompilerWarmGenerationConsent=finite-four-2.4.21` plus
`-PcompilerFixtureJavaHome=<absolute owner-prepared link-free JDK21 home>`;
merely adding this task or passing a property is not an owner scheduling grant.
Independent source review comes first, then owner-scheduled build/fake tests,
then a separately authorized single four-job generation, if granted. Preserve
the existing `compilerTimingProbe` task unchanged when integrating this append.

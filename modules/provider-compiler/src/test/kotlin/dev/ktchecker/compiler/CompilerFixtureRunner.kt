package dev.ktchecker.compiler

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Original, executable provider-integration fixtures. No independent qualification is inferred. */
object CompilerFixtureRunner {
    private const val VERSION = CompilerRuleRegistry.VERSION
    private lateinit var registry: CompilerRuleRegistry
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private lateinit var repository: Path
    private lateinit var fixtureRoot: Path
    private lateinit var runRoot: Path
    private lateinit var settings: CompilerFixtureSettings
    private lateinit var budget: CompilerFixtureBudget
    private lateinit var evidence: CompilerFixtureEvidence
    private lateinit var scratch: CompilerFixtureScratch
    private lateinit var toolchain: KotlinJvmToolchain
    private lateinit var dependencies: List<ArtifactIdentity>
    private lateinit var provenance: CompilerFixtureProvenance
    private val jobs = mutableListOf<Job>()
    private val orphanedOutcomes = mutableListOf<OrphanedOutcome>()
    private val outputDirectories = mutableSetOf<Path>()
    private val assessments = linkedMapOf<String, MutableList<Assessment>>()
    private val fixtureRuleIdsByPath = linkedMapOf<String, String>()
    private val rejectedJobAttribution = mutableSetOf<Path>()
    private val controls = linkedMapOf<String, Boolean?>()
    private var allFixtures = emptyList<Fixture>()
    private var plannedFixtures = emptyList<JsonObject>()
    private var runFailure: Throwable? = null
    private var checkpointSerial = 0
    private var serial = 0

    private data class Fixture(
        val ruleId: String, val upstreamId: String, val positive: String,
        val negative: String, val severity: String, val flags: List<String>,
        val authoredCohort: String?, val positivePath: String, val negativePath: String,
    )
    private data class Job(
        val label: String, val directory: Path, val input: AnalysisInput, val result: ProviderResult,
        val process: ProcessOutcome?, val sources: Map<String, String>,
        val fixtureRuleIds: Set<String>,
        val attempt: CompilerFixtureAttempt,
        var persistenceComplete: Boolean = false, var receiptInvariantsPassed: Boolean = false,
    )
    private data class Assessment(
        val kind: String, val passed: Boolean, val summary: String, val jobs: List<Job>,
        val receipt: Path,
    )
    private data class OrphanedOutcome(val attempt: CompilerFixtureAttempt, val directory: Path, val process: ProcessOutcome)

    @JvmStatic fun main(args: Array<String>) {
        val started = System.nanoTime()
        check(!::budget.isInitialized) { "A fixture invocation cannot resume or merge an earlier run" }
        settings = CompilerFixtureSettings.parse(args)
        val suppliedFixtureRoot = Path.of(settings.fixtureDirectory).toAbsolutePath().normalize()
        val suppliedRepository = suppliedFixtureRoot.parent.parent
        repository = CompilerFixtureProvenance.canonicalOuterDirectory(suppliedRepository)
        fixtureRoot = repository.resolve(suppliedRepository.relativize(suppliedFixtureRoot))
        require(Files.isDirectory(fixtureRoot, NOFOLLOW_LINKS) && fixtureRoot.toRealPath() == fixtureRoot) { "Unsafe fixture directory" }
        val runId = Instant.now().toString().replace(":", "-") + "-" + settings.cohort + "-" + UUID.randomUUID()
        val runs = fixtureRoot.resolve("runs")
        if (!Files.exists(runs, NOFOLLOW_LINKS)) Files.createDirectory(runs)
        fixtureDirectoryIdentity(runs)
        runRoot = Files.createDirectory(runs.resolve(runId))
        privateFixturePermissions(runRoot)
        evidence = CompilerFixtureEvidence(repository, runRoot, fixtureRoot.resolve("evidence/compiler-jvm-$VERSION.json"), settings.evidenceBytes)
        evidence.directory(runRoot.resolve("control"))
        budget = CompilerFixtureBudget(settings, startedNanos = started, ownerStop = { evidence.ownerStopRequested() })
        var terminalDrift = emptyList<Map<String, String>>()
        var terminalCommitted = false
        try {
            save(runRoot.resolve("invocation.json"), mapOf("schema_version" to 1, "run_uuid" to runId,
                "settings" to settings.description(), "expected_visible_scope" to mapOf("ordinary_pairs" to 92,
                    "planned_unsupported_common_jvm_graphs" to 16, "catalog_mappings" to 443),
                "execution_authority" to "Each child-capable provider call still needs a new exact nonserializable permit; invocation is not authority."))
            save(runRoot.resolve("control/README.json"), mapOf("stop_marker" to "control/stop",
                "owner_action" to "Create a bounded regular non-symlink file here to stop admission between jobs; the current job retains its deadline.",
                "not_authority" to true, "no_pid_signalling" to true, "no_resume_or_merge" to true))
            println("Bounded compiler cohort ${settings.cohort}; owner stop marker: ${runRoot.resolve("control/stop")}")
            budget.requireActive()
            provenance = CompilerFixtureProvenance.capture(repository, runRoot, evidence) { budget.requireActive() }
            save(provenance.manifestPath, provenance.description())
            registry = CompilerRuleRegistry.parse(provenance.compilerRegistryText)
            loadFixtureInventory()
            controls.putAll(listOf("intent", "unmapped_syntax", "quoted_name", "unnamed_werror").associateWith { null })
            save(runRoot.resolve("fixture-scope.json"), fixtureScope())
            budget.requireActive()
            val compilerPaths = System.getProperty("ktchecker.compiler.classpath")?.split(File.pathSeparator)
                ?.filter { it.isNotBlank() }?.map { Path.of(it).toAbsolutePath().normalize() }
                ?: error("Explicit isolated compiler classpath is required")
            toolchain = KotlinJvmToolchain(Path.of(System.getProperty("ktchecker.compiler.java")
                ?: error("Explicit Java executable is required")), compilerPaths.map {
                    ArtifactIdentity("selected-compiler:${it.fileName}", fileDigest(it), it.toString())
                }, KotlinJvmRuntimeLimits(maxScanMillis = minOf(60_000, budget.nextTimeoutMillis())))
            dependencies = toolchain.compilerClasspath.filter {
                Path.of(it.path).fileName.toString() == "kotlin-stdlib-$VERSION.jar" ||
                    Path.of(it.path).fileName.toString().matches(Regex("annotations-[0-9.]+\\.jar"))
            }
            require(dependencies.any { Path.of(it.path).fileName.toString() == "kotlin-stdlib-$VERSION.jar" }) {
                "No explicit pinned Kotlin standard-library dependency"
            }
            require(System.getProperty("java.specification.version") == "21") { "Fixture host must use JDK21" }
            budget.requireActive()
            scratch = CompilerFixtureScratch.create(Path.of(System.getProperty("java.io.tmpdir")), "ktc-compiler-fixtures-")
            save(runRoot.resolve("toolchain.json"), mapOf(
                "provider_id" to registryProvider(), "provider_version" to VERSION,
                "java_executable" to toolchain.javaExecutable.toString(), "java_executable_digest" to toolchain.executableDigest,
                "selected_java_runtime_image" to toolchain.runtimeImage.description(),
                "jdk_host_version" to System.getProperty("java.version"), "toolchain_digest" to toolchain.digest,
                "tested_revision" to testedRevision(),
                "source_state" to "Startup source copies and actual executed class-directory/jar identities are independent; HEAD/live sources are not bytecode or release certificates.",
                "startup_provenance" to reference(provenance.manifestPath),
                "compiler_classpath" to toolchain.compilerClasspath.map { artifactJson(it) },
                "dependency_classpath" to dependencies.map { artifactJson(it) },
                "compiler_heap_mib" to compilerHeap(), "execution_mode" to "serial isolated child JVMs",
                "bounded_invocation" to settings.description(),
                "qualification" to "none; synthetic input integration only; not a release/performance/precision certificate",
            ))
            writeEvidence("start")
            runFixtures(allFixtures.filter { settings.selects(it.authoredCohort) })
            runIntentControls()
            runTransportControls()
            budget.requireActive()
            check(selectedMissingKinds().isEmpty()) { "Selected cohort has missing/failed kinds; not a completed run" }
            check(controls.values.all { it == true }) { "Selected run has missing/failed actual controls" }
        } catch (failure: Throwable) {
            runFailure = failure
            if (failure is CompilerFixtureStopped) budget.stop(failure.reason, failure.message ?: failure.reason.code)
        } finally {
            if (::scratch.isInitialized) {
                val cleanup = scratch.cleanup(scratch.rootIdentity)
                try { save(runRoot.resolve("private-root-cleanup.json"), cleanup.description(), terminal = true) }
                catch (failure: Throwable) { runFailure = preserveCompilerFixtureFailure(runFailure, failure) }
                if (!cleanup.passed) {
                    val stopped = budget.stop(if (cleanup.quarantined) CompilerFixtureStopReason.CHILD_QUIESCENCE
                        else CompilerFixtureStopReason.SCRATCH_CLEANUP,
                        cleanup.failure ?: "Final private root cleanup did not verify disappearance")
                    runFailure = preserveCompilerFixtureFailure(runFailure, stopped)
                }
            }
            if (budget.deadlineReached()) runFailure = preserveCompilerFixtureFailure(runFailure,
                budget.stop(CompilerFixtureStopReason.OVERALL_DEADLINE, "Overall budget elapsed before terminal checkpoint"))
            try {
                runFailure?.let { failure -> save(runRoot.resolve("failure.json"), mapOf("type" to failure.javaClass.name,
                    "message" to failure.message, "completed_jobs" to jobs.size, "budget" to budget.description(),
                    "qualification" to "not established; partial/failure retained"), terminal = true) }
                if (::provenance.isInitialized && ::registry.isInitialized && ::toolchain.isInitialized && allFixtures.isNotEmpty()) {
                    val candidate = writeEvidence("terminal-candidate", terminal = true)
                    terminalDrift = candidate.drift
                    terminalCommitted = publishTerminal(candidate)
                } else save(runRoot.resolve("summary.json"), mapOf("schema_version" to 1, "run_complete" to false,
                    "startup_capture_complete" to ::provenance.isInitialized, "budget" to budget.description(),
                    "evidence_accounting" to evidence.statistics(), "qualification" to "none; startup did not finish"),
                    terminal = true, replace = true)
            } catch (failure: Throwable) {
                runFailure = preserveCompilerFixtureFailure(runFailure, failure)
                if (failure is CompilerFixtureStopped) budget.stop(failure.reason, failure.message ?: failure.reason.code)
                System.err.println("Terminal evidence publication failed: ${failure.javaClass.simpleName}: ${failure.message}; retained candidates or a stale selector are not completion proof without successful harness return")
                try { saveTerminal(runRoot.resolve("bounded-terminal-status.json"), mapOf("run_complete" to false,
                    "publication_committed" to false, "final_selector_may_be_stale" to true,
                    "terminal_publication_failed" to true, "failure_type" to failure.javaClass.name,
                    "budget" to terminalBudget(), "qualification" to "none; inspect retained immutable checkpoints/raw files",
                    "completion_validity" to TERMINAL_COMPLETION_VALIDITY)) }
                catch (statusFailure: Throwable) {
                    preserveCompilerFixtureFailure(runFailure, statusFailure)
                    System.err.println("Small terminal status could not persist: ${statusFailure.javaClass.simpleName}; terminal publication remains unconfirmed")
                }
            }
        }
        val failures = assessments.values.flatten().count { !it.passed }
        val tested = assessments.values.count { cases -> CASE_KINDS.all { kind -> cases.any { it.kind == kind && it.passed } } }
        println("Compiler $VERSION cohort ${settings.cohort}: ${budget.attempts.size}/${settings.maxAttempts} reserved attempts; " +
            "${jobs.count { it.process?.executed == true } + orphanedOutcomes.count { it.process.executed }} confirmed isolated executions; " +
            "92 ordinary / 16 planned unsupported / 443 visible mappings; $tested passed all six kinds; $failures failed cases; 0 qualified.")
        println("Immutable run/checkpoints: $runRoot; latest sidecar: ${fixtureRoot.resolve("evidence/compiler-jvm-$VERSION.json")}")
        runFailure?.let { throw it }
        check(budget.stopReason == null) { "Recorded stop is not a successful bounded completion" }
        check(terminalDrift.isEmpty()) { "Source/artifact or receipt gaps invalidated attribution; originals and actual results retained" }
        check(selectedMissingKinds().isEmpty() && controls.values.all { it == true }) { "Bounded selected run is partial; actual results and gaps retained" }
        check(terminalCommitted) { "Terminal publication did not commit; candidates or an unsettled selector are not completion proof" }
    }

    private fun loadFixtureInventory() {
        val manifest = Json.parseToJsonElement(provenance.readFixture("manifest.json")).jsonObject
        require(manifest.getValue("kotlin_version").jsonPrimitive.content == VERSION)
        allFixtures = manifest.getValue("fixtures").jsonArray.map { element ->
            val row = element.jsonObject
            val positive = row.getValue("positive").jsonPrimitive.content
            val negative = row.getValue("negative").jsonPrimitive.content
            Fixture(row.getValue("rule_id").jsonPrimitive.content, row.getValue("upstream_id").jsonPrimitive.content,
                provenance.readFixture(positive), provenance.readFixture(negative), row.getValue("declared_severity").jsonPrimitive.content,
                row.getValue("flags").jsonArray.map { it.jsonPrimitive.content }, row["authored_cohort"]?.jsonPrimitive?.content,
                positive, negative)
        }
        require(allFixtures.size == 92 && allFixtures.map { it.ruleId }.toSet().size == allFixtures.size)
        require(allFixtures.groupingBy { CompilerFixtureSettings.authoredName(it.authoredCohort) }.eachCount() == mapOf(
            "original" to 42, "overrides" to 13, "visibility" to 8, "types_conventions" to 11,
            "inline_constructors" to 5, "opt_in" to 4, "jvm" to 9)) { "Authored inventory changed; cannot silently narrow scope" }
        allFixtures.forEach { fixture ->
            require(registry.byRuleId.getValue(fixture.ruleId).upstreamId == fixture.upstreamId)
            assessments[fixture.ruleId] = mutableListOf()
            listOf(path(fixture), path(fixture, true), "unsuppressed/${fixture.upstreamId.lowercase()}.kt").forEach {
                fixtureRuleIdsByPath[it] = fixture.ruleId
            }
        }
        val planned = Json.parseToJsonElement(provenance.readFixture("planned-kmp-manifest.json")).jsonObject
        require(planned.getValue("kotlin_version").jsonPrimitive.content == VERSION)
        plannedFixtures = planned.getValue("fixtures").jsonArray.map { it.jsonObject }
        require(plannedFixtures.size == 16 && plannedFixtures.map { it.getValue("rule_id").jsonPrimitive.content }.toSet().size == 16)
        plannedFixtures.forEach { row ->
            require(registry.byRuleId.getValue(row.getValue("rule_id").jsonPrimitive.content).upstreamId == row.getValue("upstream_id").jsonPrimitive.content)
            require(row.getValue("status").jsonPrimitive.content == "planned_unsupported_sealed_source_roles_not_available")
            for (kind in listOf("positive_sources", "negative_sources")) for (source in row.getValue(kind).jsonArray) {
                provenance.readFixture(source.jsonObject.getValue("path").jsonPrimitive.content)
            }
        }
        require(registry.mappings.size == 443 && allFixtures.any { settings.selects(it.authoredCohort) })
    }

    private fun fixtureScope(): Map<String, Any?> = mapOf("schema_version" to 1, "named_cohort" to settings.cohort,
        "ordinary_pairs" to allFixtures.map { fixture -> mapOf("rule_id" to fixture.ruleId, "upstream_id" to fixture.upstreamId,
            "authored_cohort" to CompilerFixtureSettings.authoredName(fixture.authoredCohort), "selected" to settings.selects(fixture.authoredCohort),
            "selection_state" to if (settings.selects(fixture.authoredCohort)) "selected_pending_not_run" else "unselected_not_run_not_excluded",
            "positive" to provenance.fixtureReference(fixture.positivePath), "negative" to provenance.fixtureReference(fixture.negativePath)) },
        "planned_unsupported_graphs" to plannedFixtures, "planned_manifest_evidence" to provenance.fixtureReference("planned-kmp-manifest.json"),
        "ordinary_manifest_evidence" to provenance.fixtureReference("manifest.json"), "catalog_mappings" to registry.mappings.size,
        "planned_execution" to "none; sealed common/JVM roles and refines capability still unsupported; never merged into ordinary sources",
        "selection_scope" to "trusted bounded harness scheduling only; all unselected/planned/Native rules remain visible gaps, not exclusions")

    private fun runFixtures(fixtures: List<Fixture>) {
        val positive = cohort(fixtures, "positive", { it.positive }, { it.flags }, splitSeverity = true)
        fixtures.forEach { fixture ->
            val job = ensureEmit(fixture, "positive", positive.getValue(fixture.ruleId), fixture.positive, fixture.flags)
            positive[fixture.ruleId] = job
            assess(fixture, "positive", emits(job, fixture),
                "Actual exact internal ID normalized to its reviewed rule with sealed UTF-8 point provenance and truthful not-run future Verification; compilation failure remains failure.", listOf(job))
        }
        finishPhase("positive")
        val negative = cohort(fixtures, "negative", { it.negative }, { it.flags })
        fixtures.forEach { fixture ->
            var job = negative.getValue(fixture.ruleId)
            if (!cleanAbsence(job, fixture)) job = execute("negative-single-${fixture.upstreamId}",
                mapOf(path(fixture) to fixture.negative), fixture.flags)
            assess(fixture, "negative", cleanAbsence(job, fixture),
                "Corrected control actually compiled successfully without the selected internal ID; unexecuted/failed builds do not count as absence.", listOf(job))
        }
        finishPhase("negative")
        val disabled = cohort(fixtures.filter { it.severity == "warning" }, "warning-disabled", { it.positive }, {
            it.flags + "-Xwarning-level=${it.upstreamId}:disabled"
        }, splitSeverity = true)
        val configured = cohort(fixtures, "configuration", { it.positive }, { fixture ->
            if (fixture.severity == "warning") fixture.flags + "-Xwarning-level=${fixture.upstreamId}:error"
            else replaceJvmTarget(fixture.flags, "21")
        }, splitSeverity = true)
        fixtures.forEach { fixture ->
            val flags = if (fixture.severity == "warning") fixture.flags + "-Xwarning-level=${fixture.upstreamId}:error"
                else replaceJvmTarget(fixture.flags, "21")
            // Each cohort receives all explicit per-ID warning levels, not just one selected member's.
            var job = configured.getValue(fixture.ruleId)
            if (!emits(job, fixture, if (fixture.severity == "warning") "error" else null)) {
                job = execute("configuration-single-${fixture.upstreamId}", mapOf(path(fixture) to fixture.positive), flags)
            }
            val disableJob = disabled[fixture.ruleId]
            val disabledPass = disableJob == null || (cleanAbsence(disableJob, fixture) && disableJob.result.coverage
                .single { it.ruleId == fixture.ruleId }.reason == "compiler_warning_disabled_under_selected_configuration")
            assess(fixture, "configuration", emits(job, fixture, if (fixture.severity == "warning") "error" else null) && disabledPass,
                if (fixture.severity == "warning") "The actual compiler warning was explicitly promoted to error without rewriting source-declared severity."
                else "The exact error was re-emitted under explicit JVM target21; target17 baseline remains separate.",
                listOfNotNull(job, positive.getValue(fixture.ruleId), disableJob))
        }
        finishPhase("configuration")
        val version = cohort(fixtures, "version", { it.positive }, { it.flags + listOf("-language-version", "2.4", "-api-version", "2.4") }, splitSeverity = true)
        val mismatch = execute("wrong-requested-compiler-version", mapOf("version-control.kt" to "fun versionControl() = 1\n"),
            listOf("-jvm-target", "17"), requestedVersion = "2.4.20")
        fixtures.forEach { fixture ->
            val flags = fixture.flags + listOf("-language-version", "2.4", "-api-version", "2.4")
            val job = ensureEmit(fixture, "version", version.getValue(fixture.ruleId), fixture.positive, flags)
            assess(fixture, "version", emits(job, fixture) && mismatch.process == null &&
                mismatch.result.status == "unavailable" && "compiler_version_not_pinned_2_4_21" in mismatch.result.reasons,
                "Executed Kotlin2.4.21 banner and internal-name renderer under explicit language/API2.4. A2.4.20 request was denied before execution; no alternate compiler version is claimed.", listOf(job, mismatch))
        }
        finishPhase("version")
        val suppressed = cohort(fixtures, "suppression", { fixture -> suppress(fixture.positive, fixture.upstreamId) }, { it.flags }, splitSeverity = true,
            additionalSources = { fixture -> if (fixture.severity == "warning") mapOf(
                "unsuppressed/${fixture.upstreamId.lowercase()}.kt" to changePackage(fixture.positive, ".unsuppressed")) else emptyMap() })
        fixtures.forEach { fixture ->
            var job = suppressed.getValue(fixture.ruleId)
            val suppressedPath = path(fixture)
            if (fixture.severity == "warning") {
                var passed = warningSuppressedWithControl(job, fixture, suppressedPath)
                if (!passed) {
                    job = execute("suppression-single-${fixture.upstreamId}", mapOf(suppressedPath to suppress(fixture.positive, fixture.upstreamId),
                        "unsuppressed/${fixture.upstreamId.lowercase()}.kt" to changePackage(fixture.positive, ".unsuppressed")), fixture.flags)
                    passed = warningSuppressedWithControl(job, fixture, suppressedPath)
                }
                assess(fixture, "suppression", passed,
                    "Real @file:Suppress removed only this warning from its sealed file while an adjacent unsuppressed control still emitted the same ID; coverage remained non-complete.", listOf(job))
            } else {
                // A group failure cannot prove one suppressed error's behavior. Execute each separately.
                job = execute("suppression-error-single-${fixture.upstreamId}",
                    mapOf(suppressedPath to suppress(fixture.positive, fixture.upstreamId)), fixture.flags)
                val actual = job.process
                val passed = actual?.successfulExecution == true && actualVersion(job) == VERSION &&
                    !job.result.complete() && job.result.coverage.none { it.successful() } &&
                    "compiler_source_suppression_requires_scoped_coverage_evidence" in job.result.reasons &&
                    (actual.exitCode == 0 || job.result.status == "failed")
                assess(fixture, "suppression", passed,
                    "Real error-suppression input was executed individually. Any failed compilation remained failed; even compiler-accepted suppression did not produce successful ktChecker coverage. See actual exit/diagnostics; no clean compilation is fabricated.", listOf(job, positive.getValue(fixture.ruleId)))
            }
        }
        finishPhase("suppression")
        val regression = cohort(fixtures, "regression", { "\n\n" + it.positive }, { it.flags }, splitSeverity = true, moved = true)
        fixtures.forEach { fixture ->
            val shifted = "\n\n" + fixture.positive
            var job = regression.getValue(fixture.ruleId)
            if (!emits(job, fixture)) job = execute("regression-single-${fixture.upstreamId}",
                mapOf(path(fixture, true) to shifted), fixture.flags)
            val old = positive.getValue(fixture.ruleId)
            val before = findings(old, fixture).minByOrNull { it.location.line }
            val after = findings(job, fixture).minByOrNull { it.location.line }
            val passed = emits(job, fixture) && before != null && after != null &&
                after.location.path == path(fixture, true) && after.location.line == before.location.line + 2 &&
                after.location.column == before.location.column && after.location.fileDigest == Digests.sha256(shifted) &&
                after.location.fileDigest != before.location.fileDigest && job.input.snapshot.snapshotId != old.input.snapshot.snapshotId &&
                job.input.analysisKey() != old.input.analysisKey() && job.process?.outputDirectory != old.process?.outputDirectory
            assess(fixture, "regression", passed,
                "Fresh changed/moved snapshot re-emitted the exact ID at the verified two-line shift with new source digest/key and a unique job output; no stale span or result was reused.", listOf(old, job))
        }
        finishPhase("regression")
    }

    private fun cohort(
        fixtures: List<Fixture>, kind: String, source: (Fixture) -> String, flags: (Fixture) -> List<String>,
        splitSeverity: Boolean = false, moved: Boolean = false,
        additionalSources: (Fixture) -> Map<String, String> = { emptyMap() },
    ): MutableMap<String, Job> {
        val result = mutableMapOf<String, Job>()
        // A compiler can stop before later JVM phases after front-end errors.
        // Compatible flags and backend/severity are grouped; missing IDs get real individual reruns.
        fixtures.groupBy { fixture ->
            val stableFlags = flags(fixture).filterNot { it.startsWith("-Xwarning-level=") }
            stableFlags.joinToString("\u0000") + if (splitSeverity) "|${fixture.severity}|${registry.byRuleId.getValue(fixture.ruleId).backend}" else ""
        }.values.forEachIndexed { index, group ->
            val sources = linkedMapOf<String, String>()
            group.forEach { fixture -> sources[path(fixture, moved)] = source(fixture); sources.putAll(additionalSources(fixture)) }
            val effectiveFlags = flags(group.first()).filterNot { it.startsWith("-Xwarning-level=") } +
                group.flatMap { flags(it).filter { arg -> arg.startsWith("-Xwarning-level=") } }.distinct()
            val job = execute("$kind-cohort-$index", sources, effectiveFlags)
            group.forEach { result[it.ruleId] = job }
        }
        return result
    }

    private fun execute(label: String, suppliedSources: Map<String, String>, flags: List<String>, requestedVersion: String = VERSION): Job {
        budget.requireActive()
        evidence.quota.ensureRoom(CompilerFixtureSettings.JOB_RESERVE_BYTES)
        val subjects = suppliedSources.keys.mapNotNull(fixtureRuleIdsByPath::get).toSet()
        val attempt = budget.reserveAttempt(label, subjects) // Includes provider refusals; never an execution witness.
        val reservation = evidence.quota.reserveJob(CompilerFixtureSettings.JOB_RESERVE_BYTES)
        val number = ++serial
        println("compiler fixture job $number/${settings.maxAttempts}: $label (${suppliedSources.size} sealed source files)")
        val directory = runRoot.resolve("%03d-%s".format(number, label.lowercase()))
        var rootIdentity: CompilerFixtureDirectoryIdentity? = null
        var outputIdentity: CompilerFixtureDirectoryIdentity? = null
        var observed: ProcessOutcome? = null
        var observedPreparedMatches = true
        var providerEntered = false
        var providerReturned = false
        var job: Job? = null
        var failure: Throwable? = null
        try {
            evidence.directory(directory.resolve("sources"))
            rootIdentity = scratch.newSnapshot(number)
            val registeredSnapshot = checkNotNull(rootIdentity)
            val root = registeredSnapshot.path
            provenance.requireExecutedArtifactsStable()
            budget.requireActive(checkOwner = false)
            val sources = suppliedSources.toMap()
            // Captured metadata is never executed or compiled.
            val captured = sources + ("build.gradle.kts" to "// Synthetic captured fixture metadata; never executed or compiled.\n")
            val sealedBytes = captured.mapValues { (_, text) -> text.toByteArray(Charsets.UTF_8) }
            val files = sealedBytes.toSortedMap().map { (relative, bytes) ->
                CompilerSnapshot.relative(relative)
                scratch.verify(registeredSnapshot)
                val destination = root.resolve(relative)
                Files.createDirectories(destination.parent)
                Files.write(destination, bytes, java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE, NOFOLLOW_LINKS)
                evidence.writeNew(directory.resolve("sources").resolve(relative), bytes, reservation = reservation)
                if (destination.fileSystem.supportedFileAttributeViews().contains("posix")) {
                    Files.setPosixFilePermissions(destination, PosixFilePermissions.fromString("r--------"))
                }
                SnapshotFile(relative, Digests.sha256(bytes), bytes.size.toLong())
            }
            val manifest = SnapshotManifest(Digests.sha256(ContractJson.encodeToString(files)), "compiler-fixtures", "synthetic-originals", files,
                ownerId = "compiler-fixture-harness", captureKind = "immutable_overlay", declaredRoots = listOf("."))
            val dependencyDigest = Digests.sha256(ContractJson.encodeToString(dependencies))
            val context = AnalysisContext(RuleContext("jvm", "synthetic", ":compiler-fixtures", "main"), requestedVersion,
                classpath = dependencies, compilerArguments = flags.toList(), libraries = mapOf("kotlin-stdlib" to VERSION),
                modelDigest = Digests.sha256("synthetic-owner-imported-members:" + sources.keys.sorted().joinToString("\n")),
                dependencyDigest = dependencyDigest, toolchainDigest = toolchain.digest,
                configurationDigest = Digests.sha256(ContractJson.encodeToString(flags)), fullProject = false,
                compilationFiles = sources.keys.toList())
            val base = AnalysisInput(manifest, root.toString(), context, registry.jvmRuleIds.sorted(), outputRoot = scratch.outputRoot.toString())
            var exact: ExecutionAuthority = ExecutionAuthority.DENY
            val provider = KotlinJvmCompilerProvider(toolchain, ExecutionAuthority { input -> exact.authorize(input) }, registry = registry,
                compilerHeapMiB = compilerHeap(), executionObserver = { prepared, outcome ->
                    observed = outcome
                    observedPreparedMatches = prepared.snapshot == manifest && prepared.context == context
                })
            val timeout = budget.nextTimeoutMillis()
            val input = base.copy(execution = provider.executionProfile(base, timeoutMillis = timeout, maxOutputBytes = 2_097_152))
            val sealedRoot = root.toRealPath()
            exact = ExactExecutionAuthority(ExecutionPermit(input.execution!!, manifest.snapshotId, sealedRoot, input.analysisKey(), {
                budget.requireActive(checkOwner = false)
                scratch.verify(registeredSnapshot)
                require(root.toRealPath() == sealedRoot && !Files.isSymbolicLink(root))
                // Expected bytes were authored before materialization, never recaptured from a live worktree.
                for ((relative, expected) in sealedBytes) {
                    val actual = root.resolve(relative)
                    require(Files.isRegularFile(actual, NOFOLLOW_LINKS) && !Files.isSymbolicLink(actual))
                    require(Files.size(actual) == expected.size.toLong() && Files.readAllBytes(actual).contentEquals(expected))
                }
            }, { output ->
                budget.requireActive(checkOwner = false)
                check(outputIdentity == null) { "Output preparation callback reused" }
                outputIdentity = scratch.registerOutput(output) // Original permit callback, before provider compiler writes.
            }))
            evidence.writeNew(directory.resolve("input.json"), (ContractJson.encodeToString(input) + "\n").toByteArray(),
                reservation = reservation)
            save(directory.resolve("attempt-start.json"), mapOf("attempt" to attempt.description(),
                "profile_timeout_millis" to timeout, "requested_version" to requestedVersion,
                "fresh_exact_permit" to true, "actual_launch_claim" to "none; read the eventual actual process receipt"),
                reservation = reservation)
            attempt.state = "provider_call_entered"
            providerEntered = true
            val result = provider.analyze(input, CancellationToken { budget.deadlineReached() })
            providerReturned = true
            val process = observed
            // Register the actual return before any persistence or attribution assertion can fail.
            val actualJob = Job(label, directory, input, result, process, sources, subjects, attempt)
            job = actualJob
            jobs += actualJob
            attempt.state = "provider_returned_unassessed"
            evidence.writeNew(directory.resolve("stdout.txt"), (process?.stdout ?: "").toByteArray(), reservation = reservation)
            evidence.writeNew(directory.resolve("stderr.txt"), (process?.stderr ?: "").toByteArray(), reservation = reservation)
            save(directory.resolve("process.json"), mapOf(
                "analysis_key" to input.analysisKey(), "snapshot_id" to manifest.snapshotId,
                "startup_provenance" to reference(provenance.manifestPath),
                "context_id" to context.scope.id, "status" to (process?.status ?: "not_executed"),
                "reasons" to (process?.reasons ?: result.reasons), "exit_code" to process?.exitCode,
                "command" to (process?.command ?: emptyList<String>()), "output_directory" to process?.outputDirectory?.toString(),
                "cancelled" to (process?.cancelled ?: false), "timed_out" to (process?.timedOut ?: false),
                "truncated" to (process?.truncated ?: false), "elapsed_millis" to result.metrics.elapsedMillis,
                "output_bytes" to result.metrics.outputBytes, "stdout_sha256" to Digests.sha256(process?.stdout ?: ""),
                "stderr_sha256" to Digests.sha256(process?.stderr ?: ""), "reserved_attempt_number" to attempt.number,
                "registered_pre_write_output_directory" to outputIdentity?.path?.toString(),
                "profile_timeout_millis" to timeout,
                "sealed_source_files" to files.map { mapOf("path" to it.path, "sha256" to it.digest, "size" to it.size) },
            ) + CompilerFixtureProcessEvidence.fields(process, provenance.supervisorIdentity), reservation = reservation)
            evidence.writeNew(directory.resolve("result.json"), (ContractJson.encodeToString(result) + "\n").toByteArray(),
                reservation = reservation)
            actualJob.persistenceComplete = true
            check(observedPreparedMatches) { "Observed execution input differs from the sealed fixture input" }
            if (process != null) {
                check(process.supervisorIdentity == provenance.supervisorIdentity) {
                    "Actual helper identity does not match START manifest/binary; exit/output retained without attribution"
                }
                check(process.executionConfirmed) {
                    "Selected compiler exec was not confirmed; fork-child exit/output retained without execution proof"
                }
            }
            process?.outputDirectory?.let { output ->
                check(output == outputIdentity?.path) { "Observed output does not match the original permit's pre-write owned registration" }
                check(outputDirectories.add(output)) { "Reused mutable compiler output" }
            }
            check(result.providerId == registryProvider() && result.providerVersion == VERSION)
            check(!result.complete()) { "Unqualified synthetic fixture cannot claim complete project coverage" }
            check(result.coverage.size == input.requiredRuleIds.size && result.coverage.all { it.context == context.scope })
            check(result.coverage.none { it.qualified || it.tested || it.successful() })
            check(process?.exitCode == null || process.exitCode == 0 || result.status == "failed") { "Compiler failure hidden by provider" }
            check(result.findings.all { finding -> finding.upstreamId == registry.byRuleId.getValue(finding.ruleId).upstreamId &&
                pointMatches(finding, sources) && finding.semanticDigest == input.analysisKey() &&
                finding.evidence.any { it.kind == "compiler-options" && it.digest == input.analysisKey() } &&
                finding.verification.isNotEmpty() && finding.verification.all { it.state == "not_run" && it.command.isEmpty() } &&
                finding.verification == CompilerVerification.recompile(input, finding.upstreamId!!, toolchain.digest,
                    sources.keys.toList()) }) { "Unsealed, mislabeled, or falsely verified finding" }
            actualJob.receiptInvariantsPassed = true
            attempt.state = "provider_returned_receipt_invariants_passed_unassessed"
        } catch (problem: Throwable) {
            failure = problem
            if (job == null && observed != null) {
                // An exception after the observer fired still cannot discard an actual child outcome.
                val actual = observed!!
                orphanedOutcomes += OrphanedOutcome(attempt, directory, actual)
                try {
                    if (!evidence.contains(directory.resolve("stdout.txt"))) evidence.writeNew(directory.resolve("stdout.txt"),
                        actual.stdout.toByteArray(Charsets.UTF_8), reservation = reservation)
                    if (!evidence.contains(directory.resolve("stderr.txt"))) evidence.writeNew(directory.resolve("stderr.txt"),
                        actual.stderr.toByteArray(Charsets.UTF_8), reservation = reservation)
                    save(directory.resolve("exception-observer-process.json"), mapOf("provider_result_returned" to false,
                        "attribution_withheld" to true, "status" to actual.status, "reasons" to actual.reasons,
                        "exit_code" to actual.exitCode, "command" to actual.command,
                        "output_directory" to actual.outputDirectory?.toString(), "attempt" to attempt.description(),
                        "startup_provenance" to reference(provenance.manifestPath)) +
                        CompilerFixtureProcessEvidence.fields(actual, provenance.supervisorIdentity), reservation = reservation)
                } catch (persistence: Throwable) { problem.addSuppressed(persistence) }
            }
        } finally {
            compilerFixtureChildQuiescenceProblem(providerEntered, providerReturned, observed,
                provenance.supervisorIdentity, observedPreparedMatches)?.let { problem ->
                // Returning/throwing from analyze is not a child cleanup witness. Retain the whole
                // private tree, including output paths created before the callback could register them.
                scratch.quarantine(problem)
                failure = preserveCompilerFixtureFailure(failure, budget.stop(CompilerFixtureStopReason.CHILD_QUIESCENCE, problem))
            }
            val cleanup = listOfNotNull(outputIdentity, rootIdentity).map(scratch::cleanup)
            try { save(directory.resolve("scratch-cleanup.json"), mapOf("receipts" to cleanup.map { it.description() },
                "passed" to cleanup.all { it.passed }, "raw_persistence_complete" to (job?.persistenceComplete ?: false),
                "provider_call_entered" to providerEntered, "provider_returned" to providerReturned),
                reservation = reservation) }
            catch (problem: Throwable) { failure = preserveCompilerFixtureFailure(failure, problem) }
            reservation.close()
            if (cleanup.any { !it.passed && !it.quarantined }) {
                val stopped = budget.stop(CompilerFixtureStopReason.SCRATCH_CLEANUP,
                    "Per-job output/snapshot cleanup rejected a replaced, linked, special, over-bound or remaining subtree")
                failure = preserveCompilerFixtureFailure(failure, stopped)
            }
        }
        failure?.let { problem ->
            job?.let { rejectedJobAttribution.add(directory); it.receiptInvariantsPassed = false }
            attempt.state = when {
                job != null -> "provider_returned_receipt_rejected_or_incomplete"
                providerReturned -> "provider_returned_result_registration_failed"
                observed != null -> "provider_threw_after_actual_observer_no_result_returned"
                providerEntered -> "provider_threw_without_observer_child_state_unknown"
                else -> "preparation_failed_before_provider_call"
            }
            if (problem is CompilerFixtureStopped) budget.stop(problem.reason, problem.message ?: problem.reason.code)
            try { save(directory.resolve("receipt-invariant-failure.json"), mapOf("type" to problem.javaClass.name,
                "message" to problem.message, "attribution_withheld" to true, "provider_return_retained" to (job != null),
                "raw_persistence_complete" to (job?.persistenceComplete ?: false),
                "attempt" to attempt.description(), "evidence_retained" to evidence.immutableFilesUnder(directory).map(::reference)),
                terminal = true) }
            catch (persistence: Throwable) { problem.addSuppressed(persistence) }
        }
        try { save(directory.resolve("job-evidence.json"), mapOf("schema_version" to 1,
            "attempt" to attempt.description(), "raw_persistence_complete" to (job?.persistenceComplete ?: false),
            "startup_provenance" to reference(provenance.manifestPath),
            "raw_files" to evidence.immutableFilesUnder(directory).map(::reference),
            "scope" to "Immutable raw byte index; recursive references name retained sources/input/actual stdout/stderr/process/result and cleanup, never deleted scratch"),
            terminal = failure != null) }
        catch (problem: Throwable) { failure = preserveCompilerFixtureFailure(failure, problem) }
        try { writeCheckpoint("job-%03d".format(number), terminal = failure != null) }
        catch (problem: Throwable) {
            if (problem is CompilerFixtureStopped) budget.stop(problem.reason, problem.message ?: problem.reason.code)
            failure = preserveCompilerFixtureFailure(failure, problem)
        }
        failure?.let { throw it }
        budget.requireActive(checkOwner = false)
        return checkNotNull(job)
    }

    private fun pointMatches(finding: Finding, sources: Map<String, String>): Boolean {
        val location = finding.location
        val text = sources[location.path] ?: return false
        val line = text.split('\n').getOrNull(location.line - 1)?.removeSuffix("\r") ?: return false
        return location.fileDigest == Digests.sha256(text) && location.column in 0..line.length &&
            location.endLine == location.line && location.endColumn == location.column
    }
    private fun actualVersion(job: Job): String? = CompilerDiagnosticParser().parse(
        (job.process?.stderr ?: "") + "\n" + (job.process?.stdout ?: ""), emptyMap()).reportedVersion
    private fun findings(job: Job, fixture: Fixture) = job.result.findings.filter { it.ruleId == fixture.ruleId && it.upstreamId == fixture.upstreamId }
    private fun emits(job: Job, fixture: Fixture, severity: String? = null): Boolean = job.process?.successfulExecution == true &&
        actualVersion(job) == VERSION && findings(job, fixture).any { severity == null || it.severity == severity }
    private fun cleanAbsence(job: Job, fixture: Fixture): Boolean = job.process?.successfulExecution == true &&
        job.process.exitCode == 0 && actualVersion(job) == VERSION && findings(job, fixture).isEmpty() && job.result.rawDiagnostics.isEmpty()
    private fun warningSuppressedWithControl(job: Job, fixture: Fixture, suppressedPath: String): Boolean = emits(job, fixture) &&
        job.process?.exitCode == 0 && findings(job, fixture).none { it.location.path == suppressedPath } &&
        findings(job, fixture).any { it.location.path.startsWith("unsuppressed/") } && !job.result.complete() &&
        job.result.coverage.none { it.successful() }
    private fun ensureEmit(fixture: Fixture, kind: String, job: Job, source: String, flags: List<String>): Job =
        if (emits(job, fixture)) job else execute("$kind-single-${fixture.upstreamId}", mapOf(path(fixture) to source), flags)
    private fun assess(fixture: Fixture, kind: String, passed: Boolean, summary: String, jobs: List<Job>) {
        require(kind in CASE_KINDS && assessments.getValue(fixture.ruleId).none { it.kind == kind })
        val actualJobs = jobs.distinct()
        val truthfulPassed = passed && actualJobs.all { it.persistenceComplete && it.receiptInvariantsPassed && it.directory !in rejectedJobAttribution }
        val receipt = runRoot.resolve("assessments/${fixture.upstreamId.lowercase()}-$kind.json")
        val refs = actualJobs.flatMap(::references).distinct()
        save(receipt, mapOf("rule_id" to fixture.ruleId, "upstream_id" to fixture.upstreamId, "kind" to kind,
            "passed" to truthfulPassed, "assertions" to summary, "named_cohort" to settings.cohort,
            "jobs" to actualJobs.map { job -> mapOf("label" to job.label, "analysis_key" to job.input.analysisKey(),
                "snapshot_id" to job.input.snapshot.snapshotId, "actual_exit_code" to job.process?.exitCode,
                "provider_status" to job.result.status, "raw_persistence_complete" to job.persistenceComplete,
                "receipt_invariants_passed" to job.receiptInvariantsPassed) +
                CompilerFixtureProcessEvidence.fields(job.process, provenance.supervisorIdentity) },
            "evidence" to refs, "qualification" to "not established by synthetic fixtures"))
        assessments.getValue(fixture.ruleId) += Assessment(kind, truthfulPassed, summary, actualJobs, receipt)
        if (!truthfulPassed) println("FAILED ${fixture.upstreamId}/$kind; actual evidence retained")
        writeCheckpoint("assessment-${fixture.upstreamId.lowercase()}-$kind")
    }

    private fun finishPhase(kind: String) {
        budget.requireActive()
        writeEvidence(kind)
        budget.requireActive()
    }

    private fun selectedMissingKinds(): List<Map<String, String>> = allFixtures.filter { settings.selects(it.authoredCohort) }.flatMap { fixture ->
        CASE_KINDS.filter { kind -> assessments[fixture.ruleId].orEmpty().none { it.kind == kind && it.passed } }
            .map { mapOf("rule_id" to fixture.ruleId, "kind" to it, "state" to assessmentState(fixture, it, terminal = true)) }
    }

    private fun assessmentState(fixture: Fixture, kind: String, terminal: Boolean): String {
        val case = assessments[fixture.ruleId].orEmpty().singleOrNull { it.kind == kind }
        if (case != null) return if (case.passed) "passed" else "failed"
        if (!settings.selects(fixture.authoredCohort)) return "not_run_unselected_cohort_not_excluded"
        return if (!terminal) "selected_pending_not_run" else "not_run_incomplete_" + (budget.stopReason?.code ?: "run_failure_or_missing_assessment")
    }

    private fun runIntentControls() {
        val job = execute("intent-preserving-warning-controls", mapOf(
            "intent/division.kt" to "package fixtures.intent\nval deliberateInfinity = 1.0 / 0.0\nval deliberateNaN = 0.0 / 0.0\n",
            "intent/sentinel.kt" to "package fixtures.intent\nval deliberateEmptySentinel = 5..1\n"), listOf("-jvm-target", "17", "-Wextra"))
        val passed = job.process?.successfulExecution == true && job.process.exitCode == 0 && actualVersion(job) == VERSION
        recordControl("intent", passed, "Compiler warning transport only; no intentional/advisory behavior is inferred", listOf(job))
        save(runRoot.resolve("intent-controls.json"), mapOf(
            "passed" to passed, "scope" to "Actual compiler warning transport only; intentional NaN/infinity/empty sentinels may still warn.",
            "absence_required" to false, "no_behavioral_intent_inferred" to true,
            "emitted_ids" to job.result.findings.map { it.upstreamId }, "evidence" to references(job)))
        finishPhase("intent-controls")
        require(passed) { "Real intent control did not execute cleanly; actual outcome retained" }
    }

    private fun runTransportControls() {
        val syntax = execute("unmapped-syntax-control", mapOf("transport/syntax.kt" to "fun malformed() =\n"), listOf("-jvm-target", "17"))
        val syntaxPassed = syntax.process?.successfulExecution == true && syntax.process.exitCode != 0 &&
            syntax.result.status == "failed" && syntax.result.rawDiagnostics.any { it.upstreamId != null && it.upstreamId !in registry.byUpstreamId } &&
            syntax.result.coverage.none { it.successful() }
        recordControl("unmapped_syntax", syntaxPassed, "Unmapped syntax remains raw, failed and non-complete", listOf(syntax))
        val quote = execute("quoted-name-is-not-emission", mapOf("transport/quote.kt" to
            "fun quotedText() = \"[UNRESOLVED_REFERENCE] is just quoted source text\"\n"),
            listOf("-jvm-target", "17", "-Xreturn-value-checker=check"))
        val quotePassed = quote.process?.successfulExecution == true && quote.process.exitCode == 0 &&
            quote.result.findings.isEmpty() && quote.result.rawDiagnostics.none { it.upstreamId == "UNRESOLVED_REFERENCE" } &&
            (quote.process.stderr + quote.process.stdout).let { !it.contains("flag is not supported") && !it.contains("invalid argument") }
        recordControl("quoted_name", quotePassed, "Quoted diagnostic text is not an emission; option acceptance is transport-only", listOf(quote))
        val werror = execute("unnamed-werror-failure-control", mapOf("transport/werror.kt" to "val division = 1 / 0\n"),
            listOf("-jvm-target", "17", "-Werror"))
        val werrorPassed = werror.process?.successfulExecution == true && werror.process.exitCode != 0 && werror.result.status == "failed" &&
            werror.result.rawDiagnostics.any { it.severity == "error" && it.upstreamId == null } &&
            werror.result.findings.any { it.upstreamId == "DIVISION_BY_ZERO" && it.severity == "warning" } &&
            werror.result.coverage.none { it.successful() }
        recordControl("unnamed_werror", werrorPassed, "Unnamed Werror failure stays raw/failed without rewriting warning severity", listOf(werror))
        save(runRoot.resolve("transport-controls.json"), mapOf("passed" to (syntaxPassed && quotePassed && werrorPassed),
            "unmapped_is_raw_and_failed" to syntaxPassed, "quoted_name_does_not_normalize" to quotePassed,
            "werror_failure_stays_unnamed_raw_and_failed" to werrorPassed,
            "return_value_checker_option_scope" to "Actual Kotlin2.4.21 option acceptance only; not a RETURN_VALUE_NOT_USED rule fixture or qualification.",
            "evidence" to (references(syntax) + references(quote) + references(werror))))
        finishPhase("transport-controls")
        require(syntaxPassed && quotePassed && werrorPassed) { "Real compiler transport invariants failed; actual reports retained" }
    }

    private fun recordControl(name: String, passed: Boolean, summary: String, actualJobs: List<Job>) {
        require(name in controls && controls[name] == null)
        save(runRoot.resolve("controls/$name.json"), mapOf("control" to name, "passed" to passed,
            "assertions" to summary, "evidence" to actualJobs.flatMap(::references).distinct(), "qualification" to "none"))
        controls[name] = passed
        writeCheckpoint("control-$name")
    }

    private data class Checkpoint(val directory: Path, val ledger: Path, val inventory: Path, val receipt: Path)
    private data class EvidenceCandidate(
        val drift: List<Map<String, String>>, val completionEligible: Boolean,
        val checkpoint: Checkpoint, val sidecar: Path, val summary: Path,
    )

    private fun writeCheckpoint(phase: String, terminal: Boolean = false): Checkpoint {
        require(phase.matches(Regex("[a-z0-9_-]{1,200}")))
        val directory = evidence.directory(runRoot.resolve("checkpoints/%04d-%s".format(++checkpointSerial, phase)))
        val ledger = directory.resolve("job-ledger.json")
        save(ledger, mapOf("schema_version" to 1, "phase" to phase, "terminal" to terminal,
            "budget" to budget.description(), "jobs" to jobs.map { job -> mapOf(
                "label" to job.label, "fixture_rule_ids" to job.fixtureRuleIds.sorted(), "analysis_key" to job.input.analysisKey(),
                "actual_exit_code" to job.process?.exitCode, "provider_status" to job.result.status,
                "raw_persistence_complete" to job.persistenceComplete,
                "receipt_invariants_passed" to (job.receiptInvariantsPassed && job.directory !in rejectedJobAttribution),
                "evidence" to references(job),
            ) + CompilerFixtureProcessEvidence.fields(job.process, provenance.supervisorIdentity) },
            "observed_outcomes_without_provider_result" to orphanedOutcomes.map { orphan -> mapOf("attempt" to orphan.attempt.description(),
                "provider_result_returned" to false, "actual_exit_code" to orphan.process.exitCode,
                "evidence" to evidence.immutableFilesUnder(orphan.directory).map(::reference)) +
                CompilerFixtureProcessEvidence.fields(orphan.process, provenance.supervisorIdentity) },
            "qualification" to "none; reservations/preparations/denials and actual confirmed executions remain distinct"), terminal = terminal)
        val inventory = directory.resolve("assessment-inventory.json")
        save(inventory, mapOf("schema_version" to 1, "phase" to phase, "terminal" to terminal,
            "ordinary_pairs" to allFixtures.map { fixture -> mapOf("rule_id" to fixture.ruleId,
                "authored_cohort" to CompilerFixtureSettings.authoredName(fixture.authoredCohort),
                "selected" to settings.selects(fixture.authoredCohort),
                "kinds" to CASE_KINDS.associateWith { assessmentState(fixture, it, terminal) },
                "assessment_receipts" to assessments[fixture.ruleId].orEmpty().map { reference(it.receipt) }) },
            "planned_unsupported_rule_ids" to plannedFixtures.map { it.getValue("rule_id").jsonPrimitive.content },
            "controls" to controls.mapValues { (_, passed) -> if (passed == null) {
                if (terminal) "not_run_incomplete_" + (budget.stopReason?.code ?: "run_failure_or_missing_control") else "pending_not_run"
            } else if (passed) "passed" else "failed" },
            "unselected_scope" to "not_run_not_excluded"), terminal = terminal)
        val receipt = directory.resolve("checkpoint.json")
        save(receipt, mapOf("schema_version" to 1, "sequence" to checkpointSerial, "phase" to phase,
            "terminal" to terminal, "captured_at" to Instant.now().toString(), "named_cohort" to settings.cohort,
            "candidate_only" to true, "run_complete" to false, "publication_committed" to false,
            "ledger" to reference(ledger), "assessment_inventory" to reference(inventory),
            "startup_provenance" to reference(provenance.manifestPath), "qualification" to "none"), terminal = terminal)
        save(runRoot.resolve("latest-checkpoint.json"), mapOf("checkpoint" to reference(receipt), "phase" to phase,
            "candidate_only" to true, "run_complete" to false, "publication_committed" to false),
            terminal = terminal, replace = true)
        return Checkpoint(directory, ledger, inventory, receipt)
    }

    private fun writeEvidence(phase: String, terminal: Boolean = false): EvidenceCandidate {
        val checkpoint = writeCheckpoint(phase, terminal)
        val executionProofGaps = jobs.filter { job -> job.process?.let {
            !it.executionConfirmed || it.supervisorIdentity != provenance.supervisorIdentity
        } == true }.map { mapOf("kind" to "actual_outcome_missing_exec_confirmation_or_START_supervisor_identity",
            "path" to repository.relativize(it.directory.resolve("process.json")).toString()) }
        val invariantGaps = rejectedJobAttribution.map { mapOf("kind" to "actual_job_receipt_invariant_failed",
            "path" to repository.relativize(it.resolve("receipt-invariant-failure.json")).toString()) }
        val persistenceGaps = jobs.filterNot { it.persistenceComplete }.map { mapOf("kind" to "actual_provider_return_raw_persistence_incomplete",
            "path" to repository.relativize(it.directory).toString()) }
        val orphanGaps = orphanedOutcomes.map { mapOf("kind" to "actual_observer_outcome_without_provider_result_return_attribution_withheld",
            "path" to repository.relativize(it.directory).toString()) }
        val provenanceDrift = provenance.drift() + executionProofGaps + invariantGaps + persistenceGaps + orphanGaps
        val intactProvenance = provenanceDrift.isEmpty()
        val provenanceCheck = checkpoint.directory.resolve("provenance-check.json")
        save(provenanceCheck, mapOf("schema_version" to 1, "checked_at" to Instant.now().toString(), "phase" to phase,
            "startup_provenance" to reference(provenance.manifestPath), "passed" to intactProvenance,
            "drift" to provenanceDrift, "claim" to "Independent START sources/class artifacts/native resources and actual executionConfirmed/native identity; no inspector, matching source, or fork-exit inference."),
            terminal = terminal)
        val implementation = provenance.implementationReferences() + listOf(reference(provenanceCheck))
        val fixturesById = allFixtures.associateBy { it.ruleId }
        val plannedIds = plannedFixtures.map { it.getValue("rule_id").jsonPrimitive.content }.toSet()
        if (terminal && budget.deadlineReached()) runFailure = preserveCompilerFixtureFailure(runFailure,
            budget.stop(CompilerFixtureStopReason.OVERALL_DEADLINE, "Overall deadline elapsed during terminal capture"))
        val completionEligible = terminal && runFailure == null && budget.stopReason == null && intactProvenance &&
            selectedMissingKinds().isEmpty() && controls.values.all { it == true }
        val rules = registry.mappings.map { mapping ->
            val fixture = fixturesById[mapping.ruleId]
            val cases = assessments[mapping.ruleId].orEmpty()
            val attemptedJobs = jobs.filter { mapping.ruleId in it.fixtureRuleIds }
            val inputPreparations = budget.attempts.filter { mapping.ruleId in it.ruleIds }
            val attempted = cases.isNotEmpty() || attemptedJobs.isNotEmpty() ||
                inputPreparations.any { it.state != "reserved_before_provider_call" }
            val implementationObserved = cases.any { it.kind == "positive" && it.passed }
            val implemented = implementationObserved && intactProvenance
            val allCases = CASE_KINDS.all { kind -> cases.any { it.kind == kind && it.passed } }
            val renderedCases = cases.map { assessment -> mapOf("kind" to assessment.kind, "passed" to assessment.passed,
                "summary" to assessment.summary, "evidence" to listOf(reference(assessment.receipt))) }
            val gaps = mutableListOf("No independent precision/performance/compatibility qualification; synthetic JVM/JDK21 integration is not full-project or release coverage.")
            if (!mapping.jvmApplicable) gaps += mapping.ruleId + " (" + mapping.upstreamId + ") is a Native factory at " + mapping.source +
                "; this JVM tuple cannot run it. Requires an owner-authorized Kotlin/Native2.4.21 target/source-set/compilation, real Native/iOS dependencies and six per-ID fixtures; no Native compiler/link/simulator evidence."
            if (mapping.ruleId in plannedIds) gaps += "Planned common/JVM graph is unsupported: no sealed fragment-role/refines capability; no concatenation or same-module pseudo-KMP run is admitted."
            else if (fixture == null) gaps += "No ordinary executable per-ID fixture authored/run for this mapping yet."
            if (fixture != null && !settings.selects(fixture.authoredCohort)) gaps += "Authored ordinary pair was not selected by this bounded cohort; not_run is a gap, not a scope exclusion."
            if (attempted && !implementationObserved) gaps += "Per-ID preparation/input attempts did not finish a passed expected exact-diagnostic assessment; inspect actual raw receipts."
            if (attempted && !intactProvenance) gaps += "Source/artifact/native/exec/receipt gaps detected; implementation/test attribution withheld while originals and actual outcomes remain retained."
            if (attemptedJobs.isNotEmpty() && cases.isEmpty()) gaps += "Actual provider return was retained but no complete per-ID assessment finished; exit/output alone is not an execution witness."
            if (terminal && !completionEligible && fixture != null && settings.selects(fixture.authoredCohort)) {
                gaps += "Selected bounded run is partial/failed (" + (budget.stopReason?.code ?: "missing_or_failed_assessments_controls_or_provenance") + "); never treated as completed coverage."
            }
            CASE_KINDS.filter { kind -> cases.none { it.kind == kind && it.passed } }.forEach { kind ->
                gaps += "Missing or failed real " + kind + " fixture evidence; state=" +
                    (fixture?.let { assessmentState(it, kind, terminal) } ?: "not_run_unauthored_or_unsupported") + "."
            }
            val providerStatus = when {
                !mapping.jvmApplicable -> "not_supported_on_jvm"
                mapping.ruleId in plannedIds -> "planned_unsupported_not_executed"
                attempted && !intactProvenance -> "attempted_with_provenance_gaps"
                implemented && allCases -> "tested_unqualified"
                implemented -> "implemented_with_test_gaps"
                attempted -> "attempted_with_test_gaps"
                else -> "not_executed"
            }
            mapOf("rule_id" to mapping.ruleId, "implemented" to implemented, "tested" to (implemented && allCases),
                "qualified" to false, "attempted" to attempted, "provider_status" to providerStatus,
                "selection_state" to if (fixture == null) "not_run_unauthored_or_unsupported" else
                    if (settings.selects(fixture.authoredCohort)) "selected_bounded" else "not_run_unselected_not_excluded",
                "diagnostic_observation" to if (implementationObserved) "observed_expected_diagnostic" else if (attempted)
                    "unobserved_or_unassessed_expected_diagnostic" else "not_executed",
                "cases" to renderedCases,
                "case_inventory" to CASE_KINDS.associateWith { kind -> fixture?.let { assessmentState(it, kind, terminal) }
                    ?: "not_run_unauthored_or_unsupported" },
                "attempt_evidence" to attemptedJobs.flatMap(::references).distinct(),
                "attempt_reservation_numbers" to inputPreparations.map { it.number },
                "implementation_evidence" to if (implemented) implementation else emptyList<Any>(),
                "qualification_evidence" to emptyList<Any>(), "gaps" to gaps)
        }
        val state = when {
            !intactProvenance -> "attempted_provenance_or_receipt_gaps"
            completionEligible -> "bounded_selected_cohort_completion_candidate_unqualified"
            terminal -> "bounded_selected_cohort_partial_or_failed"
            jobs.isEmpty() -> "bounded_startup_captured_not_run"
            else -> "bounded_selected_cohort_in_progress_unqualified"
        }
        val sidecar = mapOf("schema_version" to 1, "provider_id" to registryProvider(), "provider_version" to VERSION,
            "run_complete" to false, "candidate_only" to true, "publication_committed" to false,
            "completion_candidate_eligible" to completionEligible, "checkpoint_phase" to phase,
            "completion_validity" to TERMINAL_COMPLETION_VALIDITY,
            "context" to mapOf("id" to ("bounded-" + settings.cohort + "-synthetic-jvm-jdk21-" + runRoot.fileName),
                "target" to "jvm", "source_set" to "synthetic", "module" to ":compiler-fixtures", "compilation" to "main",
                "kotlin_version" to VERSION, "jdk_version" to System.getProperty("java.version"),
                "library_versions" to mapOf("kotlin-stdlib" to VERSION), "toolchain_digest" to toolchain.digest,
                "base_flags" to listOf("-jvm-target", "17"), "named_cohort" to settings.cohort,
                "fixture_scope" to "All92 ordinary pairs/16 unsupported graphs/443 mappings remain visible; named cohort is bounded trusted scheduling, not an exclusion or complete project.",
                "evidence_state" to state, "source_provenance" to "Independent START originals and actual executed artifacts/native resources; no source-to-bytecode inference.",
                "run" to repository.relativize(runRoot).toString(), "resume_or_merge" to false),
            "bounded_invocation" to settings.description(), "budget" to budget.description(),
            "provenance_evidence" to listOf(reference(provenance.manifestPath), reference(provenanceCheck),
                reference(checkpoint.ledger), reference(checkpoint.inventory), reference(runRoot.resolve("fixture-scope.json"))),
            "historical_tuple_policy" to "Other run UUIDs and their raw/sidecar bytes remain separate immutable evidence; no partial successes or historical failures are merged into this tuple.",
            "rules" to rules)
        val sidecarPath = checkpoint.directory.resolve("compiler-jvm-$VERSION.json")
        save(sidecarPath, sidecar, terminal = terminal)
        val summaryPath = checkpoint.directory.resolve("summary.json")
        val summary = mapOf("schema_version" to 1, "phase" to phase, "terminal" to terminal, "run_complete" to false,
            "candidate_only" to true, "publication_committed" to false, "completion_candidate_eligible" to completionEligible,
            "completion_validity" to TERMINAL_COMPLETION_VALIDITY,
            "evidence_state" to state, "mappings" to registry.mappings.size, "jvm_available_mappings" to registry.jvmRuleIds.size,
            "original_fixture_ids" to 42, "ordinary_fixture_ids" to allFixtures.size,
            "selected_fixture_ids" to allFixtures.count { settings.selects(it.authoredCohort) },
            "planned_unsupported_graph_ids" to plannedFixtures.size,
            "implemented" to rules.count { it["implemented"] == true }, "tested" to rules.count { it["tested"] == true },
            "qualified" to 0, "failed_case_count" to assessments.values.flatten().count { !it.passed },
            "selected_missing_or_failed_kinds" to selectedMissingKinds(), "controls" to controls,
            "budget" to budget.description(), "evidence_accounting_at_checkpoint_write" to evidence.statistics(),
            "actual_executions" to (jobs.count { it.process?.executed == true } + orphanedOutcomes.count { it.process.executed }),
            "successful_supervised_executions" to (jobs.count { it.process?.successfulExecution == true } + orphanedOutcomes.count { it.process.successfulExecution }),
            "observed_process_outcomes" to (jobs.count { it.process != null } + orphanedOutcomes.size),
            "unconfirmed_process_outcomes" to (jobs.count { it.process?.executionConfirmed == false } + orphanedOutcomes.count { !it.process.executionConfirmed }),
            "job_ledger_evidence" to reference(checkpoint.ledger),
            "assessment_inventory_evidence" to reference(checkpoint.inventory),
            "sidecar_evidence" to reference(sidecarPath), "unique_output_directories" to outputDirectories.size,
            "startup_provenance_evidence" to reference(provenance.manifestPath),
            "provenance_check_evidence" to reference(provenanceCheck), "provenance_check_passed" to intactProvenance,
            "toolchain_evidence" to reference(runRoot.resolve("toolchain.json")),
            "private_root_cleanup_evidence" to runRoot.resolve("private-root-cleanup.json").takeIf(evidence::contains)?.let(::reference),
            "intent_control_evidence" to runRoot.resolve("intent-controls.json").takeIf(evidence::contains)?.let(::reference),
            "transport_control_evidence" to runRoot.resolve("transport-controls.json").takeIf(evidence::contains)?.let(::reference))
        save(summaryPath, summary, terminal = terminal)
        // Only convenience destinations change. All hash references above name immutable checkpoint/job/assessment files.
        save(fixtureRoot.resolve("evidence/compiler-jvm-$VERSION.json"), sidecar, terminal = terminal, replace = true)
        save(runRoot.resolve("compiler-jvm-$VERSION.json"), sidecar, terminal = terminal, replace = true)
        save(runRoot.resolve("summary.json"), summary, terminal = terminal, replace = true)
        save(runRoot.resolve("latest-checkpoint.json"), mapOf("checkpoint" to reference(checkpoint.receipt),
            "sidecar" to reference(sidecarPath), "summary" to reference(summaryPath), "phase" to phase,
            "candidate_only" to true, "run_complete" to false, "publication_committed" to false),
            terminal = terminal, replace = true)
        return EvidenceCandidate(provenanceDrift, completionEligible, checkpoint, sidecarPath, summaryPath)
    }

    private const val TERMINAL_RECORD_BYTES = 65_536
    private const val TERMINAL_COMPLETION_VALIDITY =
        "All bulk sidecars/summaries are candidates. Completion requires the settled latest-checkpoint.json selector AND successful harness return; any publication error, failed correction or unknown return makes a positive selector unconfirmed. Qualification remains none."

    private fun terminalBudget(): Map<String, Any?> = mapOf("overall_timeout_millis" to settings.overallMillis,
        "elapsed_millis" to budget.elapsedMillis(), "remaining_millis" to budget.remainingMillis(),
        "stop_reason" to budget.stopReason?.code, "reserved_attempts" to budget.attempts.size)

    private fun publishTerminal(candidate: EvidenceCandidate): Boolean {
        // Hashing the immutable bulk references also precedes, and consumes, the final clock gate.
        val selector = mapOf("schema_version" to 1, "phase" to "terminal", "terminal" to true,
            "checkpoint" to reference(candidate.checkpoint.receipt), "sidecar" to reference(candidate.sidecar),
            "summary" to reference(candidate.summary), "named_cohort" to settings.cohort,
            "successful_harness_return_required" to true, "standalone_completion_proof" to false,
            "completion_validity" to TERMINAL_COMPLETION_VALIDITY, "qualification" to "none")
        return CompilerFixtureTerminalPublication(budget).publish(candidate.completionEligible, {
            // Serial cooperative accounting: no unrelated writes intervene. This bounds the positive
            // selector, immutable correction, corrected selector and one distinct fallback status,
            // including atomic-write peaks and conservatively retained failed-write temporaries.
            evidence.quota.ensureRoom(4L * TERMINAL_RECORD_BYTES, terminal = true)
        }, {
            saveTerminal(runRoot.resolve("latest-checkpoint.json"), selector + mapOf("run_complete" to true,
                "candidate_only" to false, "publication_committed" to true,
                "budget_before_final_write" to terminalBudget()), replace = true)
        }, { failure ->
            val incomplete = selector + mapOf("run_complete" to false, "candidate_only" to false,
                "publication_committed" to false, "completion_candidate_eligible" to candidate.completionEligible,
                "publication_failure_type" to failure?.javaClass?.name, "publication_failure" to failure?.message?.take(1_024),
                "first_run_failure_type" to runFailure?.javaClass?.name, "budget" to terminalBudget())
            val status = runRoot.resolve("terminal-decision-incomplete.json")
            saveTerminal(status, incomplete)
            saveTerminal(runRoot.resolve("latest-checkpoint.json"), incomplete + mapOf("terminal_decision" to reference(status)), replace = true)
        })
    }

    private fun saveTerminal(path: Path, value: Any?, replace: Boolean = false) {
        val bytes = evidenceBytes(value)
        if (bytes.size > TERMINAL_RECORD_BYTES) throw CompilerFixtureStopped(CompilerFixtureStopReason.EVIDENCE_BUDGET,
            "Terminal decision exceeds its reserved bounded record size")
        if (replace) evidence.replaceLatest(path, bytes, terminal = true)
        else evidence.writeNew(path, bytes, terminal = true)
    }

    private fun references(job: Job): List<Map<String, String>> {
        val index = job.directory.resolve("job-evidence.json")
        if (evidence.contains(index)) return listOf(reference(index))
        return listOf(reference(provenance.manifestPath)) + evidence.immutableFilesUnder(job.directory).map(::reference)
    }
    private fun reference(path: Path): Map<String, String> = evidence.reference(path)
    private fun artifactJson(it: ArtifactIdentity) = mapOf("coordinate" to it.coordinate, "sha256" to it.digest, "path" to it.path)
    private fun suppress(source: String, id: String) = "@file:Suppress(\"$id\")\n" + source
    private fun changePackage(source: String, suffix: String): String = source.replace(Regex("(?m)^package ([A-Za-z0-9_.]+)")) {
        "package " + it.groupValues[1] + suffix
    }
    private fun path(fixture: Fixture, moved: Boolean = false) = (if (moved) "moved/" else "cases/") + fixture.upstreamId.lowercase() + ".kt"
    private fun replaceJvmTarget(flags: List<String>, value: String): List<String> = flags.toMutableList().also { result ->
        val position = result.indexOf("-jvm-target")
        require(position >= 0)
        result[position + 1] = value
    }
    private fun compilerHeap() = System.getProperty("ktchecker.compiler.heapMiB", "512").toInt()
    private fun testedRevision(): String {
        return try {
            // Informational HEAD hint only; do not launch a metadata subprocess outside the admission ledger.
            val git = repository.resolve(".git")
            val head = CompilerFixtureProvenance.readStable(git.resolve("HEAD"), 4_096).toString(Charsets.UTF_8).trim()
            val revision = if (head.startsWith("ref: ")) {
                val ref = CompilerSnapshot.relative(head.removePrefix("ref: "))
                require(ref.startsWith("refs/"))
                CompilerFixtureProvenance.readStable(git.resolve(ref), 4_096).toString(Charsets.UTF_8).trim()
            } else head
            revision.takeIf { it.matches(Regex("[0-9a-f]{40,64}")) } ?: "unknown"
        } catch (_: Exception) { "unknown" }
    }
    private fun registryProvider() = CompilerRuleRegistry.PROVIDER_ID
    private val CASE_KINDS = listOf("positive", "negative", "configuration", "version", "suppression", "regression")
    private fun save(path: Path, value: Any?, terminal: Boolean = false, replace: Boolean = false,
        reservation: CompilerFixtureEvidenceQuota.Reservation? = null) {
        val bytes = evidenceBytes(value)
        if (replace) { require(reservation == null); evidence.replaceLatest(path, bytes, terminal) }
        else evidence.writeNew(path, bytes, terminal, reservation)
    }
    private fun evidenceBytes(value: Any?): ByteArray =
        (json.encodeToString(JsonElement.serializer(), element(value)) + "\n").toByteArray(Charsets.UTF_8)
    private fun element(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to element(it.value) })
        is Iterable<*> -> JsonArray(value.map(::element))
        else -> error("Unsupported evidence value ${value::class.java.name}")
    }
}

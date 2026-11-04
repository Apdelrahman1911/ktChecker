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
    private lateinit var privateRoot: Path
    private lateinit var toolchain: KotlinJvmToolchain
    private lateinit var dependencies: List<ArtifactIdentity>
    private lateinit var provenance: CompilerFixtureProvenance
    private val jobs = mutableListOf<Job>()
    private val outputDirectories = mutableSetOf<Path>()
    private val assessments = linkedMapOf<String, MutableList<Assessment>>()
    private var serial = 0

    private data class Fixture(
        val ruleId: String, val upstreamId: String, val positive: String,
        val negative: String, val severity: String, val flags: List<String>,
    )
    private data class Job(
        val label: String, val directory: Path, val input: AnalysisInput, val result: ProviderResult,
        val process: ProcessOutcome?, val sources: Map<String, String>,
    )
    private data class Assessment(
        val kind: String, val passed: Boolean, val summary: String, val jobs: List<Job>,
    )

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1) { "Fixture root is required" }
        val suppliedFixtureRoot = Path.of(args.single()).toAbsolutePath().normalize()
        val suppliedRepository = suppliedFixtureRoot.parent.parent
        repository = CompilerFixtureProvenance.canonicalOuterDirectory(suppliedRepository)
        fixtureRoot = repository.resolve(suppliedRepository.relativize(suppliedFixtureRoot))
        require(Files.isDirectory(fixtureRoot, NOFOLLOW_LINKS) && fixtureRoot.toRealPath() == fixtureRoot) { "Unsafe fixture directory" }
        val runId = Instant.now().toString().replace(":", "-") + "-" + UUID.randomUUID().toString().take(8)
        runRoot = fixtureRoot.resolve("runs").resolve(runId)
        Files.createDirectories(runRoot)
        var fixtures = emptyList<Fixture>()
        try {
            provenance = CompilerFixtureProvenance.capture(repository, runRoot)
            save(provenance.manifestPath, provenance.description())
            registry = CompilerRuleRegistry.parse(provenance.compilerRegistryText)
            val compilerPaths = System.getProperty("ktchecker.compiler.classpath")?.split(File.pathSeparator)
                ?.filter { it.isNotBlank() }?.map { Path.of(it).toAbsolutePath().normalize() }
                ?: error("Explicit isolated compiler classpath is required")
            toolchain = KotlinJvmToolchain(Path.of(System.getProperty("ktchecker.compiler.java")
                ?: error("Explicit Java executable is required")), compilerPaths.map {
                    ArtifactIdentity("selected-compiler:${it.fileName}", fileDigest(it), it.toString())
                })
            dependencies = toolchain.compilerClasspath.filter {
                Path.of(it.path).fileName.toString() == "kotlin-stdlib-$VERSION.jar" ||
                    Path.of(it.path).fileName.toString().matches(Regex("annotations-[0-9.]+\\.jar"))
            }
            require(dependencies.any { Path.of(it.path).fileName.toString() == "kotlin-stdlib-$VERSION.jar" }) {
                "No explicit pinned Kotlin standard-library dependency"
            }
            require(System.getProperty("java.specification.version") == "21") { "Fixture host must use JDK21" }
            val fixtureManifest = Json.parseToJsonElement(provenance.readFixture("manifest.json")).jsonObject
            require(fixtureManifest.getValue("kotlin_version").jsonPrimitive.content == VERSION)
            fixtures = fixtureManifest.getValue("fixtures").jsonArray.map { element ->
                val row = element.jsonObject
                Fixture(row.getValue("rule_id").jsonPrimitive.content, row.getValue("upstream_id").jsonPrimitive.content,
                    provenance.readFixture(row.getValue("positive").jsonPrimitive.content),
                    provenance.readFixture(row.getValue("negative").jsonPrimitive.content),
                    row.getValue("declared_severity").jsonPrimitive.content,
                    row.getValue("flags").jsonArray.map { it.jsonPrimitive.content })
            }
            require(fixtures.map { it.ruleId }.toSet().size == fixtures.size)
            fixtures.forEach { fixture ->
                require(registry.byRuleId.getValue(fixture.ruleId).upstreamId == fixture.upstreamId)
                assessments[fixture.ruleId] = mutableListOf()
            }
            privateRoot = CompilerSnapshot.privateDirectory(Path.of(System.getProperty("java.io.tmpdir")), "ktc-compiler-fixtures-")
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
                "qualification" to "none; synthetic input integration only; not a release/performance/precision certificate",
            ))
            runFixtures(fixtures)
            runIntentControls()
            runTransportControls()
            val provenanceDrift = writeEvidence(fixtures)
            val failures = assessments.values.flatten().count { !it.passed }
            val tested = assessments.values.count { cases -> CASE_KINDS.all { kind -> cases.any { it.kind == kind && it.passed } } }
            println("Compiler $VERSION: ${jobs.count { it.process?.successfulExecution == true }} actual isolated executions; " +
                "${fixtures.size} per-ID fixture sets; $tested passed all six case kinds; $failures failed cases; 0 qualified.")
            println("Hash-backed evidence: " + fixtureRoot.resolve("evidence/compiler-jvm-$VERSION.json"))
            check(provenanceDrift.isEmpty()) { "Source/artifact drift invalidated current-run attribution; startup originals and actual results retained" }
            check(failures == 0) { "$failures compiler fixture cases failed; actual results and gaps retained" }
        } catch (failure: Throwable) {
            save(runRoot.resolve("failure.json"), mapOf("type" to failure.javaClass.name, "message" to failure.message,
                "completed_jobs" to jobs.size, "qualification" to "not established; failure retained"))
            if (::privateRoot.isInitialized) writeEvidence(fixtures)
            throw failure
        } finally {
            if (::privateRoot.isInitialized) privateRoot.toFile().deleteRecursively()
        }
    }

    private fun runFixtures(fixtures: List<Fixture>) {
        val positive = cohort(fixtures, "positive", { it.positive }, { it.flags }, splitSeverity = true)
        fixtures.forEach { fixture ->
            val job = ensureEmit(fixture, "positive", positive.getValue(fixture.ruleId), fixture.positive, fixture.flags)
            positive[fixture.ruleId] = job
            assess(fixture, "positive", emits(job, fixture),
                "Actual exact internal ID normalized to its reviewed rule with sealed UTF-8 point provenance and truthful not-run future Verification; compilation failure remains failure.", listOf(job))
        }
        val negative = cohort(fixtures, "negative", { it.negative }, { it.flags })
        fixtures.forEach { fixture ->
            var job = negative.getValue(fixture.ruleId)
            if (!cleanAbsence(job, fixture)) job = execute("negative-single-${fixture.upstreamId}",
                mapOf(path(fixture) to fixture.negative), fixture.flags)
            assess(fixture, "negative", cleanAbsence(job, fixture),
                "Corrected control actually compiled successfully without the selected internal ID; unexecuted/failed builds do not count as absence.", listOf(job))
        }
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
        provenance.requireExecutedArtifactsStable()
        val number = ++serial
        println("compiler fixture job $number: $label (${suppliedSources.size} sealed source files)")
        val directory = runRoot.resolve("%03d-%s".format(number, label.lowercase()))
        Files.createDirectories(directory.resolve("sources"))
        val root = CompilerSnapshot.privateDirectory(privateRoot.resolve("snapshots"), "job-$number-")
        val sources = suppliedSources.toMap()
        // Build metadata is captured for identity, but excluded only by an explicit imported selection.
        val captured = sources + ("build.gradle.kts" to "// Synthetic captured fixture metadata; never executed or compiled.\n")
        val sealedBytes = captured.mapValues { (_, text) -> text.toByteArray(Charsets.UTF_8) }
        val files = sealedBytes.toSortedMap().map { (relative, bytes) ->
            CompilerSnapshot.relative(relative)
            val destination = root.resolve(relative)
            Files.createDirectories(destination.parent)
            Files.write(destination, bytes)
            val evidenceFile = directory.resolve("sources").resolve(relative)
            Files.createDirectories(evidenceFile.parent)
            Files.write(evidenceFile, bytes)
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
        val base = AnalysisInput(manifest, root.toString(), context, registry.jvmRuleIds.sorted(), outputRoot = privateRoot.resolve("outputs").toString())
        var exact: ExecutionAuthority = ExecutionAuthority.DENY
        var observed: ProcessOutcome? = null
        val provider = KotlinJvmCompilerProvider(toolchain, ExecutionAuthority { input -> exact.authorize(input) }, registry = registry,
            compilerHeapMiB = compilerHeap(), executionObserver = { prepared, outcome ->
                require(prepared.snapshot == manifest && prepared.context == context)
                observed = outcome
            })
        val input = base.copy(execution = provider.executionProfile(base, timeoutMillis = 90_000, maxOutputBytes = 2_097_152))
        val sealedRoot = root.toRealPath()
        exact = ExactExecutionAuthority(ExecutionPermit(input.execution!!, manifest.snapshotId, sealedRoot, input.analysisKey(), {
            require(root.toRealPath() == sealedRoot && !Files.isSymbolicLink(root))
            // Expected bytes were authored before materialization; never recapture a live worktree.
            for ((relative, expected) in sealedBytes) {
                val actual = root.resolve(relative)
                require(Files.isRegularFile(actual, NOFOLLOW_LINKS) && !Files.isSymbolicLink(actual))
                require(Files.size(actual) == expected.size.toLong() && Files.readAllBytes(actual).contentEquals(expected))
            }
        }))
        Files.writeString(directory.resolve("input.json"), ContractJson.encodeToString(input) + "\n")
        val result = provider.analyze(input, CancellationToken.NONE)
        val process = observed
        Files.writeString(directory.resolve("result.json"), ContractJson.encodeToString(result) + "\n")
        Files.writeString(directory.resolve("stdout.txt"), process?.stdout ?: "")
        Files.writeString(directory.resolve("stderr.txt"), process?.stderr ?: "")
        save(directory.resolve("process.json"), mapOf(
            "analysis_key" to input.analysisKey(), "snapshot_id" to manifest.snapshotId,
            "startup_provenance" to reference(provenance.manifestPath),
            "context_id" to context.scope.id, "status" to (process?.status ?: "not_executed"),
            "reasons" to (process?.reasons ?: result.reasons), "exit_code" to process?.exitCode,
            "command" to (process?.command ?: emptyList<String>()), "output_directory" to process?.outputDirectory?.toString(),
            "cancelled" to (process?.cancelled ?: false), "timed_out" to (process?.timedOut ?: false),
            "truncated" to (process?.truncated ?: false), "elapsed_millis" to result.metrics.elapsedMillis,
            "output_bytes" to result.metrics.outputBytes, "stdout_sha256" to Digests.sha256(process?.stdout ?: ""),
            "stderr_sha256" to Digests.sha256(process?.stderr ?: ""),
            "sealed_source_files" to files.map { mapOf("path" to it.path, "sha256" to it.digest, "size" to it.size) },
        ))
        process?.outputDirectory?.let { directory ->
            check(outputDirectories.add(directory)) { "Reused mutable compiler output" }
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
        return Job(label, directory, input, result, process, sources).also {
            jobs += it
            // Persist sealed source/command/output/result evidence above, then
            // release ephemeral compiler copies. Disk use is bounded per job,
            // not proportional to hundreds of copied toolchain installations.
            process?.outputDirectory?.toFile()?.deleteRecursively()
            root.toFile().deleteRecursively()
        }
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
        assessments.getValue(fixture.ruleId) += Assessment(kind, passed, summary, jobs.distinct())
        if (!passed) println("FAILED ${fixture.upstreamId}/$kind; actual evidence retained")
    }

    private fun runIntentControls() {
        val job = execute("intent-preserving-warning-controls", mapOf(
            "intent/division.kt" to "package fixtures.intent\nval deliberateInfinity = 1.0 / 0.0\nval deliberateNaN = 0.0 / 0.0\n",
            "intent/sentinel.kt" to "package fixtures.intent\nval deliberateEmptySentinel = 5..1\n"), listOf("-jvm-target", "17", "-Wextra"))
        require(job.process?.successfulExecution == true && job.process.exitCode == 0 && actualVersion(job) == VERSION)
        save(runRoot.resolve("intent-controls.json"), mapOf(
            "passed" to true, "scope" to "Actual compiler warning transport only; intentional NaN/infinity/empty sentinels may still warn.",
            "absence_required" to false, "no_behavioral_intent_inferred" to true,
            "emitted_ids" to job.result.findings.map { it.upstreamId }, "evidence" to references(job)))
    }

    private fun runTransportControls() {
        val syntax = execute("unmapped-syntax-control", mapOf("transport/syntax.kt" to "fun malformed() =\n"), listOf("-jvm-target", "17"))
        val syntaxPassed = syntax.process?.successfulExecution == true && syntax.process.exitCode != 0 &&
            syntax.result.status == "failed" && syntax.result.rawDiagnostics.any { it.upstreamId != null && it.upstreamId !in registry.byUpstreamId } &&
            syntax.result.coverage.none { it.successful() }
        val quote = execute("quoted-name-is-not-emission", mapOf("transport/quote.kt" to
            "fun quotedText() = \"[UNRESOLVED_REFERENCE] is just quoted source text\"\n"),
            listOf("-jvm-target", "17", "-Xreturn-value-checker=check"))
        val quotePassed = quote.process?.successfulExecution == true && quote.process.exitCode == 0 &&
            quote.result.findings.isEmpty() && quote.result.rawDiagnostics.none { it.upstreamId == "UNRESOLVED_REFERENCE" } &&
            (quote.process.stderr + quote.process.stdout).let { !it.contains("flag is not supported") && !it.contains("invalid argument") }
        val werror = execute("unnamed-werror-failure-control", mapOf("transport/werror.kt" to "val division = 1 / 0\n"),
            listOf("-jvm-target", "17", "-Werror"))
        val werrorPassed = werror.process?.successfulExecution == true && werror.process.exitCode != 0 && werror.result.status == "failed" &&
            werror.result.rawDiagnostics.any { it.severity == "error" && it.upstreamId == null } &&
            werror.result.findings.any { it.upstreamId == "DIVISION_BY_ZERO" && it.severity == "warning" } &&
            werror.result.coverage.none { it.successful() }
        save(runRoot.resolve("transport-controls.json"), mapOf("passed" to (syntaxPassed && quotePassed && werrorPassed),
            "unmapped_is_raw_and_failed" to syntaxPassed, "quoted_name_does_not_normalize" to quotePassed,
            "werror_failure_stays_unnamed_raw_and_failed" to werrorPassed,
            "return_value_checker_option_scope" to "Actual Kotlin2.4.21 option acceptance only; not a RETURN_VALUE_NOT_USED rule fixture or qualification.",
            "evidence" to (references(syntax) + references(quote) + references(werror))))
        require(syntaxPassed && quotePassed && werrorPassed) { "Real compiler transport invariants failed; actual reports retained" }
    }

    private fun writeEvidence(fixtures: List<Fixture>): List<Map<String, String>> {
        val provenanceDrift = provenance.drift()
        val intactProvenance = provenanceDrift.isEmpty()
        val provenanceCheck = runRoot.resolve("provenance-check.json")
        save(provenanceCheck, mapOf("schema_version" to 1, "checked_at" to Instant.now().toString(),
            "startup_provenance" to reference(provenance.manifestPath), "passed" to intactProvenance,
            "drift" to provenanceDrift, "claim" to "Startup source identities and executed-artifact identities are independent; unchanged live sources alone are not executed bytecode proof."))
        val implementation = provenance.implementationReferences() + listOf(reference(provenanceCheck))
        val fixtureRules = fixtures.map { it.ruleId }.toSet()
        val rules = registry.mappings.map { mapping ->
            val cases = assessments[mapping.ruleId].orEmpty()
            val attempted = cases.isNotEmpty()
            val implementationObserved = cases.any { it.kind == "positive" && it.passed }
            val implemented = implementationObserved && intactProvenance
            val allCases = CASE_KINDS.all { kind -> cases.any { it.kind == kind && it.passed } }
            val renderedCases = cases.map { assessment ->
                val assessmentPath = runRoot.resolve("assessments").resolve(mapping.upstreamId.lowercase() + "-" + assessment.kind + ".json")
                val refs = assessment.jobs.flatMap(::references).distinct()
                save(assessmentPath, mapOf(
                    "rule_id" to mapping.ruleId, "upstream_id" to mapping.upstreamId, "kind" to assessment.kind,
                    "passed" to assessment.passed, "assertions" to assessment.summary,
                    "jobs" to assessment.jobs.map { mapOf("label" to it.label, "analysis_key" to it.input.analysisKey(),
                        "snapshot_id" to it.input.snapshot.snapshotId, "actual_exit_code" to it.process?.exitCode,
                        "provider_status" to it.result.status) }, "evidence" to refs,
                    "qualification" to "not established by synthetic fixtures",
                ))
                mapOf("kind" to assessment.kind, "passed" to assessment.passed, "summary" to assessment.summary,
                    "evidence" to listOf(reference(assessmentPath)) + refs)
            }
            val gaps = mutableListOf("No independent precision/performance/compatibility qualification; synthetic JVM/JDK21 integration is not full-project or release coverage.")
            if (!mapping.jvmApplicable) gaps += "${mapping.ruleId} (${mapping.upstreamId}) is a Native factory at ${mapping.source}; " +
                "this JVM execution cannot run it. Requires an owner-authorized Kotlin/Native2.4.21 target/source-set/compilation with real Native/iOS dependencies and six per-ID fixtures; no Native compiler/link/simulator evidence exists in this JVM tuple."
            if (mapping.ruleId !in fixtureRules) gaps += "No original executable per-ID fixture authored/run for this mapping yet."
            if (attempted && !implementationObserved) gaps += "Actual per-ID input attempts did not observe the expected exact compiler diagnostic; inspect emitted diagnostics and actual process outcomes."
            if (attempted && !intactProvenance) gaps += "Startup source/artifact drift was detected; implementation/test attribution is withheld, while preserved originals and actual assessments remain evidence of the attempt."
            CASE_KINDS.filter { kind -> cases.none { it.kind == kind && it.passed } }.forEach { gaps += "Missing or failed real $it fixture evidence." }
            val providerStatus = when {
                !mapping.jvmApplicable -> "not_supported_on_jvm"
                attempted && !intactProvenance -> "attempted_with_provenance_gaps"
                implemented && allCases -> "tested_unqualified"
                implemented -> "implemented_with_test_gaps"
                attempted -> "attempted_with_test_gaps"
                else -> "not_executed"
            }
            mapOf("rule_id" to mapping.ruleId, "implemented" to implemented, "tested" to (implemented && allCases),
                "qualified" to false, "attempted" to attempted, "provider_status" to providerStatus,
                "diagnostic_observation" to if (implementationObserved) "observed_expected_diagnostic" else if (attempted)
                    "unobserved_expected_diagnostic" else "not_executed",
                "cases" to renderedCases, "implementation_evidence" to if (implemented) implementation else emptyList<Any>(),
                "qualification_evidence" to emptyList<Any>(), "gaps" to gaps)
        }
        val sidecar = mapOf(
            "schema_version" to 1, "provider_id" to registryProvider(), "provider_version" to VERSION,
            "context" to mapOf("id" to "original-synthetic-jvm-jdk21", "target" to "jvm", "source_set" to "synthetic",
                "module" to ":compiler-fixtures", "compilation" to "main", "kotlin_version" to VERSION,
                "jdk_version" to System.getProperty("java.version"), "library_versions" to mapOf("kotlin-stdlib" to VERSION),
                "toolchain_digest" to toolchain.digest, "base_flags" to listOf("-jvm-target", "17"),
                "fixture_scope" to "Original synthetic per-ID compiler-provider integration, with separate explicit variant configurations; not full project.",
                "evidence_state" to if (intactProvenance) "startup_tuple_executed_not_qualified" else "attempted_provenance_drift",
                "source_provenance" to "Captured startup originals and actual executed provider/runner/contracts artifacts, independently identified; no live-source-to-bytecode inference.",
                "run" to repository.relativize(runRoot).toString()),
            "provenance_evidence" to listOf(reference(provenance.manifestPath), reference(provenanceCheck)),
            "rules" to rules,
        )
        save(fixtureRoot.resolve("evidence/compiler-jvm-$VERSION.json"), sidecar)
        save(runRoot.resolve("compiler-jvm-$VERSION.json"), sidecar)
        save(runRoot.resolve("summary.json"), mapOf("schema_version" to 1, "mappings" to registry.mappings.size,
            "jvm_available_mappings" to registry.jvmRuleIds.size, "original_fixture_ids" to fixtureRules.size,
            "implemented" to rules.count { it["implemented"] == true }, "tested" to rules.count { it["tested"] == true },
            "qualified" to 0, "failed_case_count" to assessments.values.flatten().count { !it.passed },
            "actual_executions" to jobs.count { it.process?.successfulExecution == true },
            "unique_output_directories" to outputDirectories.size,
            "startup_provenance_evidence" to reference(provenance.manifestPath),
            "provenance_check_evidence" to reference(provenanceCheck), "provenance_check_passed" to intactProvenance,
            "toolchain_evidence" to reference(runRoot.resolve("toolchain.json")),
            "intent_control_evidence" to runRoot.resolve("intent-controls.json").takeIf { Files.isRegularFile(it) }?.let(::reference),
            "transport_control_evidence" to runRoot.resolve("transport-controls.json").takeIf { Files.isRegularFile(it) }?.let(::reference)))
        return provenanceDrift
    }

    private fun references(job: Job): List<Map<String, String>> {
        val files = mutableListOf(provenance.manifestPath, job.directory.resolve("input.json"), job.directory.resolve("process.json"),
            job.directory.resolve("result.json"), job.directory.resolve("stdout.txt"), job.directory.resolve("stderr.txt"))
        Files.walk(job.directory.resolve("sources")).use { stream -> files += stream.filter { Files.isRegularFile(it) }.sorted().toList() }
        return files.map(::reference)
    }
    private fun reference(path: Path) = mapOf("path" to repository.relativize(path.toAbsolutePath().normalize()).toString(),
        "sha256" to fileDigest(path).removePrefix("sha256:"))
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
        val git = Path.of("/usr/bin/git")
        if (!Files.isExecutable(git)) return "unknown"
        return try {
            val process = ProcessBuilder(git.toString(), "-C", repository.toString(), "rev-parse", "--verify", "HEAD").start()
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly(); "unknown"
            } else process.inputStream.bufferedReader().use { it.readText().trim() }
                .takeIf { process.exitValue() == 0 && it.matches(Regex("[0-9a-f]{40,64}")) } ?: "unknown"
        } catch (_: Exception) { "unknown" }
    }
    private fun registryProvider() = CompilerRuleRegistry.PROVIDER_ID
    private val CASE_KINDS = listOf("positive", "negative", "configuration", "version", "suppression", "regression")
    private fun save(path: Path, value: Any?) {
        Files.createDirectories(path.parent)
        Files.writeString(path, json.encodeToString(JsonElement.serializer(), element(value)) + "\n")
    }
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

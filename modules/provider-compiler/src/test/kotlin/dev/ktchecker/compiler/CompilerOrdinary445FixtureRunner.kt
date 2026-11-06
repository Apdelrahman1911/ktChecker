package dev.ktchecker.compiler

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.jar.JarFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

internal data class CompilerOrdinary445RunReceipt(val directory: Path, val cohort: String,
    val compilerCalls: Int, val preRuntimeRefusals: Int, val unknownSuppressionRules: List<String>)

/** Explicit ADD-only ordinary route. No old selector, observation import, fixture history, retry or cache. */
object CompilerOrdinary445FixtureRunner {
    @JvmStatic fun main(args: Array<String>) {
        val receipt = run(args)
        println("ordinary445 " + receipt.cohort + " schedule recorded at " + receipt.directory +
            "; actual compiler calls=" + receipt.compilerCalls + "; pre-runtime refusals=" + receipt.preRuntimeRefusals +
            "; unknown hard-error suppression rules=" + receipt.unknownSuppressionRules.size + "; not qualification or full98 completion")
    }
    internal fun run(args: Array<String>): CompilerOrdinary445RunReceipt {
        val started = System.nanoTime()
        val settings = CompilerOrdinary445Settings.parse(args)
        // The exact current445 gate precedes path resolution, allocation and all provider/toolchain work.
        val registry = CompilerRuleRegistry.load()
        val contract = CompilerOrdinary445FixtureContract
        contract.requireMapping(registry.mappingIdentity)
        val repository = CompilerFixtureProvenance.canonicalOuterDirectory(Path.of(settings.repositoryDirectory))
        val planBytes = CompilerFixtureProvenance.readStable(repository.resolve(contract.PLAN_PATH), contract.MAX_INPUT_BYTES)
        val manifestBytes = CompilerFixtureProvenance.readStable(repository.resolve(contract.MANIFEST_PATH), contract.MAX_INPUT_BYTES)
        val plan = contract.plan(planBytes, manifestBytes, registry)
        val bindingBytes = CompilerFixtureProvenance.readStable(repository.resolve(contract.BINDING_PATH), 131_072)
        val pins = contract.binding(bindingBytes, settings.bindingSha256, plan)
        val snapshotBytes = CompilerFixtureProvenance.readStable(repository.resolve(settings.sourceSnapshotPath), contract.MAX_INPUT_BYTES)
        val ownerSnapshot = contract.sourceSnapshot(snapshotBytes, settings.sourceSnapshotSha256, pins, bindingBytes, settings.bindingSha256)
        val jobs = contract.schedule(plan, settings.cohort) { path ->
            CompilerFixtureProvenance.readStable(repository.resolve(path), contract.MAX_INPUT_BYTES)
        }
        return Ordinary445Run(repository, settings, registry, bindingBytes, pins, plan, jobs, snapshotBytes, ownerSnapshot, started).execute()
    }
}

private class Ordinary445Record(val number: Int, val job: CompilerOrdinary445Job) {
    var state = "not_run"
    var providerEntered = false
    var providerReturned = false
    var observedProcesses = 0
    var executionConfirmed = false
    var persistenceComplete = false
    var receiptInvariantsMatched = false
    var cleanupPassed = false
    var assessment: CompilerOrdinary445Assessment? = null
    var failure: String? = null
    var evidenceIndex: Map<String, String>? = null
    var stamp: CompilerOrdinary445Stamp? = null
    fun fact() = CompilerOrdinary445Fact(job.id, job.ruleIds, job.kind, job.control,
        persistenceComplete, receiptInvariantsMatched, cleanupPassed, assessment, evidenceIndex)
    fun description(): Map<String, Any?> = mapOf("number" to number, "job_id" to job.id, "kind" to job.kind,
        "control" to job.control, "rule_ids" to job.ruleIds, "state" to state,
        "provider_call_entered" to providerEntered, "provider_returned" to providerReturned,
        "actual_observer_outcomes" to observedProcesses, "execution_confirmed" to executionConfirmed,
        "pre_runtime_refusal_expected_not_compiler_execution" to job.refusal,
        "returned_string_and_result_persistence_complete" to persistenceComplete,
        "receipt_invariants_matched" to receiptInvariantsMatched, "cleanup_passed" to cleanupPassed,
        "assessment" to assessment?.description(), "failure" to failure, "job_evidence_index" to evidenceIndex,
        "receipt_candidate_only_until_terminal_and_outer_command" to true, "qualified" to false)
}

private class Ordinary445Run(
    private val repository: Path, private val settings: CompilerOrdinary445Settings,
    private val registry: CompilerRuleRegistry, private val bindingBytes: ByteArray,
    private val pins: List<CompilerOrdinary445Pin>, private val plan: CompilerOrdinary445Plan,
    private val jobs: List<CompilerOrdinary445Job>, private val snapshotBytes: ByteArray,
    private val ownerSnapshot: CompilerOrdinary445SourceSnapshot, started: Long,
) {
    private val contract = CompilerOrdinary445FixtureContract
    private val root = compilerFixtureCreateRunDirectory(repository, settings.cohort + "-" + UUID.randomUUID().toString())
    private val evidence = CompilerFixtureEvidence(repository, root, null, settings.evidenceBytes, contract.TERMINAL_RESERVE_BYTES)
    private val budget = CompilerOrdinary445Budget(settings, jobs.map { it.id }, started, ownerStop = evidence::ownerStopRequested)
    private val records = jobs.mapIndexed { index, job -> Ordinary445Record(index + 1, job) }
    private val captured = linkedMapOf<String, ByteArray>()
    private val selectedFixtures = plan.selected(settings.cohort)
    private var inputsCaptured = false
    private var catalogs = emptyList<JsonObject>()
    private var provenance: CompilerFixtureProvenance? = null
    private var toolchain: KotlinJvmToolchain? = null
    private var dependencies = emptyList<ArtifactIdentity>()
    private var scratch: CompilerFixtureScratch? = null
    private val usedOutputs = mutableSetOf<Path>()
    private var rootCleanupPassed = false

    fun execute(): CompilerOrdinary445RunReceipt {
        var failure: Throwable? = null
        var settled = false
        val drift = mutableListOf<Map<String, String>>()
        try {
            evidence.directory(root.resolve("control"))
            save(root.resolve("control/README.json"), mapOf("owner_stop_path" to root.resolve("control/stop").toString(),
                "scope" to "Bounded regular non-symlink stop marker checked between calls; no PID signalling or resume."))
            println("ordinary445 cohort=" + settings.cohort + "; provider calls=" + jobs.size + "; run=" + root +
                "; owner stop=" + root.resolve("control/stop"))
            checkpoint("admission") // Complete selected schedule exists even if startup later fails.
            budget.requireActive()
            captureInputs()
            val capturedRegistry = CompilerRuleRegistry.parse(captured.getValue(contract.MAP_PATH))
            contract.requireMapping(capturedRegistry.mappingIdentity)
            check(capturedRegistry.mappingIdentity == registry.mappingIdentity)
            require(registry.mappings.size == 445 && registry.jvmRuleIds.size == 402)
            val capturedPlan = contract.plan(captured.getValue(contract.PLAN_PATH), captured.getValue(contract.MANIFEST_PATH), capturedRegistry)
            require(capturedPlan == plan && contract.schedule(capturedPlan, settings.cohort) { captured.getValue(it) } == jobs)
            require("INAPPLICABLE_OPERATOR_MODIFIER_WARNING" !in registry.byUpstreamId)
            catalogs = contract.CATALOG_COUNTS.flatMap { (path, count) ->
                Json.parseToJsonElement(strictUtf8(captured.getValue(path))).jsonArray.also { require(it.size == count) }.map { it.jsonObject }
            }
            require(catalogs.size == 1665 && catalogs.map { it.getValue("id").jsonPrimitive.content }.toSet().size == 1665)
            require(catalogs.all { it.getValue("status").jsonPrimitive.content == "planned" })
            require(catalogs.count { it.getValue("default_activation").jsonPrimitive.content == "excluded" } == 46)
            require(plan.fixtures.all { f -> catalogs.any { it.getValue("id").jsonPrimitive.content == f.ruleId } })
            require(Json.parseToJsonElement(strictUtf8(captured.getValue(contract.KMP_PATH))).jsonObject.getValue("fixtures").jsonArray.size == 16)
            save(root.resolve("scope.json"), scope())
            val start = CompilerFixtureProvenance.capture(repository, root, evidence) { budget.requireActive() }
            provenance = start
            save(start.manifestPath, start.description())
            check(CompilerRuleRegistry.parse(start.compilerRegistryText).mappingIdentity == registry.mappingIdentity)
            captureRouteAnchors()
            prepareToolchain()
            scratch = CompilerFixtureScratch.create(Path.of(System.getProperty("java.io.tmpdir")), "ktc-ordinary445-")
            checkpoint("start")
            for (record in records) executeCase(record.job, record)
        } catch (problem: Throwable) { failure = problem }
        finally {
            scratch?.let { owned ->
                try {
                    val cleanup = owned.cleanup(owned.rootIdentity)
                    rootCleanupPassed = cleanup.passed
                    try { save(root.resolve("private-root-cleanup.json"), cleanup.description(), terminal = true) }
                    catch (problem: Throwable) { failure = preserveCompilerFixtureFailure(failure, problem) }
                    if (!cleanup.passed) failure = preserveCompilerFixtureFailure(failure,
                        IllegalStateException(if (cleanup.quarantined) "ordinary445_child_quiescence_unverified" else "ordinary445_scratch_cleanup_failed"))
                } catch (problem: Throwable) { failure = preserveCompilerFixtureFailure(failure, problem) }
            }
            try { provenance?.let { drift += it.drift() }; drift += inputDrift() }
            catch (problem: Throwable) { failure = preserveCompilerFixtureFailure(failure, problem) }
            if (drift.isNotEmpty()) failure = preserveCompilerFixtureFailure(failure, IllegalStateException("ordinary445_startup_identity_drift"))
            try { budget.requireActive() } catch (problem: Throwable) { failure = preserveCompilerFixtureFailure(failure, problem) }
            records.filter { it.state == "not_run" }.forEach { it.state = "not_run_after_incomplete_prefix" }
            val eligible = failure == null && rootCleanupPassed && inputsCaptured && records.size == jobs.size &&
                records.all { it.fact().attributable && it.assessment?.knownAssertionsMatched == true }
            if (!eligible && failure == null) failure = IllegalStateException("ordinary445_schedule_or_known_expectations_incomplete")
            try {
                save(root.resolve("terminal-ledger.json"), summary(false, failure, drift) + mapOf("documentary_candidate_only" to true), terminal = true)
                val publication = CompilerOrdinary445Publication { budget.requireActive() }
                settled = publication.publish(eligible,
                    collected = { saveSummary(summary(true, null, drift)) },
                    incomplete = { late -> saveSummary(summary(false, late ?: failure, drift)) })
            } catch (problem: Throwable) {
                failure = preserveCompilerFixtureFailure(failure, problem)
                try { save(root.resolve("publication-unconfirmed.json"), mapOf("cohort_schedule_complete" to false,
                    "publication_unconfirmed" to true, "local_stage_attribution_valid" to false,
                    "successful_task_return_also_required" to true), terminal = true) }
                catch (correction: Throwable) { failure = preserveCompilerFixtureFailure(failure, correction) }
            }
        }
        failure?.let { throw it }
        check(settled) { "Ordinary445 publication did not settle" }
        return CompilerOrdinary445RunReceipt(root, settings.cohort, records.count { it.executionConfirmed },
            records.count { it.job.refusal && it.assessment?.diagnosticExpectedOutcomeMatched == true },
            selectedFixtures.filter { it.severity == "error" }.map { it.ruleId })
    }
    private fun captureInputs() {
        saveBytes(root.resolve("inputs").resolve(contract.BINDING_PATH), bindingBytes)
        saveBytes(root.resolve("inputs/owner-source-snapshot.json"), snapshotBytes)
        var total = 0L
        for (pin in pins) {
            budget.requireActive()
            val bytes = CompilerFixtureProvenance.readStable(repository.resolve(pin.path), contract.MAX_INPUT_BYTES)
            contract.requireBytes(pin, bytes)
            total += bytes.size; require(total <= contract.MAX_CAPTURE_BYTES)
            saveBytes(root.resolve("inputs").resolve(pin.path), bytes)
            captured[pin.path] = bytes.copyOf()
        }
        inputsCaptured = true
        save(root.resolve("ordinary445-inputs.json"), mapOf("owner_binding_sha256" to settings.bindingSha256,
            "binding" to evidence.reference(root.resolve("inputs").resolve(contract.BINDING_PATH)),
            "owner_source_snapshot" to ownerSnapshot.description(), "owner_source_snapshot_path" to settings.sourceSnapshotPath,
            "owner_source_snapshot_sha256" to settings.sourceSnapshotSha256,
            "owner_source_snapshot_bytes" to evidence.reference(root.resolve("inputs/owner-source-snapshot.json")),
            "pins" to pins.map { mapOf("path" to it.path, "bytes" to it.size,
                "preserved" to evidence.reference(root.resolve("inputs").resolve(it.path))) },
            "source_to_bytecode_claim" to "none; source and actual executed artifacts captured independently"))
    }
    private fun inputDrift(): List<Map<String, String>> {
        val changes = mutableListOf<Map<String, String>>()
        fun check(path: Path, expected: String, kind: String) {
            val digest = runCatching { Digests.sha256(CompilerFixtureProvenance.readStable(path, contract.MAX_INPUT_BYTES)) }.getOrNull()
            if (digest != "sha256:" + expected) changes += mapOf("kind" to kind, "path" to path.toString())
        }
        check(repository.resolve(contract.BINDING_PATH), settings.bindingSha256, "binding_changed")
        check(repository.resolve(settings.sourceSnapshotPath), settings.sourceSnapshotSha256, "owner_snapshot_changed")
        for ((path, expected) in listOf(root.resolve("inputs").resolve(contract.BINDING_PATH) to settings.bindingSha256,
            root.resolve("inputs/owner-source-snapshot.json") to settings.sourceSnapshotSha256)) {
            if (evidence.contains(path)) check(path, expected, "preserved_admission_changed")
            else changes += mapOf("kind" to "admission_capture_missing", "path" to path.toString())
        }
        for (pin in pins) {
            check(repository.resolve(pin.path), pin.sha256, "pinned_input_changed")
            val copy = root.resolve("inputs").resolve(pin.path)
            if (evidence.contains(copy)) check(copy, pin.sha256, "preserved_input_changed")
            else changes += mapOf("kind" to "pinned_input_capture_missing", "path" to pin.path)
        }
        return changes
    }
    private fun captureRouteAnchors() {
        val old = checkNotNull(CompilerFixtureRunner::class.java.protectionDomain.codeSource?.location)
        val location = Path.of(old.toURI()).toAbsolutePath().normalize()
        val classes = listOf(CompilerOrdinary445FixtureRunner::class.java, CompilerOrdinary445FixtureContract::class.java,
            CompilerOrdinary445FixtureOracle::class.java, CompilerOrdinary445Budget::class.java,
            CompilerOrdinary445Publication::class.java, CompilerOrdinary445FixtureLedger::class.java,
            CompilerOrdinary445FixtureContractTest::class.java, CompilerOrdinary445FixtureOracleTest::class.java)
        val anchors = classes.map { clazz ->
            budget.requireActive()
            check(clazz.protectionDomain.codeSource?.location == old && clazz.classLoader == CompilerFixtureRunner::class.java.classLoader)
            val entry = clazz.name.replace('.', '/') + ".class"
            val bytes = checkNotNull(clazz.getResourceAsStream("/" + entry)).use { it.readNBytes(4_194_305) }
            require(bytes.size <= 4_194_304)
            val artifactBytes = if (Files.isDirectory(location, NOFOLLOW_LINKS))
                CompilerFixtureProvenance.readStable(location.resolve(entry), 4_194_304)
            else JarFile(location.toFile()).use { jar ->
                jar.getInputStream(checkNotNull(jar.getJarEntry(entry))).use { it.readNBytes(4_194_305) }
            }
            check(bytes.contentEquals(artifactBytes)) { "Ordinary445 loaded class differs from captured CodeSource" }
            val copy = root.resolve("ordinary445-class-anchors").resolve(entry)
            saveBytes(copy, bytes)
            mapOf("class_name" to clazz.name, "code_source" to location.toString(), "preserved_class_bytes" to evidence.reference(copy),
                "enclosing_artifact_inventory" to evidence.reference(checkNotNull(provenance).manifestPath))
        }
        save(root.resolve("ordinary445-class-anchors.json"), mapOf("anchors" to anchors,
            "source_to_bytecode_claim" to "none; owner must separately review a fresh build"))
    }

    private fun prepareToolchain() {
        budget.requireActive()
        require(System.getProperty("java.specification.version") == "21" && System.getProperty("os.name") == "Linux") {
            "Explicit Linux JDK21 fixture host required"
        }
        require(System.getProperty("ktchecker.compiler.heapMiB") == "512")
        val paths = requireNotNull(System.getProperty("ktchecker.compiler.classpath")).split(File.pathSeparator).map { Path.of(it) }
        require(paths.isNotEmpty() && paths.size <= 64 && paths.toSet().size == paths.size)
        val artifacts = paths.map { path ->
            budget.requireActive()
            require(path.isAbsolute && path.normalize() == path && path.toRealPath() == path &&
                Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) in 1..CompilerSnapshot.MAX_ARTIFACT_BYTES)
            ArtifactIdentity("selected-compiler:" + path.fileName, fileDigest(path), path.toString())
        }
        require(artifacts.sumOf { Files.size(Path.of(it.path)) } <= CompilerSnapshot.MAX_TOTAL_ARTIFACT_BYTES)
        val selected = KotlinJvmToolchain(Path.of(requireNotNull(System.getProperty("ktchecker.compiler.java"))), artifacts,
            KotlinJvmRuntimeLimits(maxScanMillis = minOf(60_000, budget.timeoutMillis())))
        toolchain = selected
        dependencies = selected.compilerClasspath.filter {
            val name = Path.of(it.path).fileName.toString()
            name == "kotlin-stdlib-2.4.21.jar" || name.matches(Regex("annotations-[0-9.]+\\.jar"))
        }
        require(dependencies.count { Path.of(it.path).fileName.toString() == "kotlin-stdlib-2.4.21.jar" } == 1)
        require(Path.of(System.getProperty("java.home")).toRealPath().resolve("bin/java") == selected.javaExecutable)
        require(Runtime.getRuntime().maxMemory() <= 402_653_184L) { "Fixture parent heap cannot exceed384MiB" }
        selected.verify { budget.requireActive() }
        save(root.resolve("toolchain.json"), mapOf("toolchain_digest" to selected.digest,
            "runtime_image" to selected.runtimeImage.description(), "java_executable" to selected.javaExecutable.toString(),
            "compiler_classpath" to ContractJson.encodeToJsonElement(selected.compilerClasspath),
            "dependency_classpath" to ContractJson.encodeToJsonElement(dependencies), "compiler_heap_mib" to 512,
            "qualification" to "none", "execution_mode" to "schedule_derived_serial_isolated_JVMs_plus_one_pre_runtime_refusal"))
    }

    private fun executeCase(c: CompilerOrdinary445Job, record: Ordinary445Record) {
        budget.requireActive()
        evidence.quota.ensureRoom(contract.JOB_RESERVE_BYTES)
        budget.reserve(c.id)
        record.state = "reserved_before_provider_call"
        val reservation = evidence.quota.reserveJob(contract.JOB_RESERVE_BYTES)
        val directory = root.resolve("jobs").resolve("%02d-".format(record.number) + c.id)
        val owned = checkNotNull(scratch)
        val start = checkNotNull(provenance)
        val selected = checkNotNull(toolchain)
        var snapshot: CompilerFixtureDirectoryIdentity? = null
        var output: CompilerFixtureDirectoryIdentity? = null
        var input: AnalysisInput? = null
        var result: ProviderResult? = null
        val outcomes = mutableListOf<Pair<AnalysisInput, ProcessOutcome>>()
        var assessment: CompilerOrdinary445Assessment? = null
        var failure: Throwable? = null
        fun retain(problem: Throwable) { failure = preserveCompilerFixtureFailure(failure, problem) }
        fun persist(name: String, bytes: ByteArray) {
            try { saveBytes(directory.resolve(name), bytes, reservation = reservation) }
            catch (problem: Throwable) { retain(problem) }
        }
        try {
            evidence.directory(directory)
            start.requireExecutedArtifactsStable()
            require(inputDrift().isEmpty()) { "Pinned ordinary445 source/catalog/plan drift before launch" }
            budget.requireActive()
            val registered = owned.newSnapshot(record.number)
            snapshot = registered
            val materialized = c.sources.toSortedMap().map { (path, text) ->
                val bytes = text.toByteArray(Charsets.UTF_8)
                val source = registered.path.resolve(path)
                owned.verify(registered)
                Files.createDirectories(source.parent)
                Files.write(source, bytes, CREATE_NEW, WRITE, NOFOLLOW_LINKS)
                if (source.fileSystem.supportedFileAttributeViews().contains("posix"))
                    Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("r--------"))
                saveBytes(directory.resolve("sources").resolve(path), bytes, reservation = reservation)
                SnapshotFile(path, Digests.sha256(bytes), bytes.size.toLong())
            }
            val manifest = SnapshotManifest(Digests.sha256(root.fileName.toString() + "\u0000" + c.id + "\u0000" +
                ContractJson.encodeToString(materialized)), "ordinary445-private-fixtures", root.fileName.toString(), materialized,
                ownerId = "ordinary445-owner-harness", captureKind = "immutable_overlay", declaredRoots = listOf("."))
            val context = AnalysisContext(RuleContext("jvm", "synthetic", ":ordinary445-fixtures", "main"), c.requestedVersion,
                classpath = dependencies, compilerArguments = c.flags, libraries = mapOf("kotlin-stdlib" to "2.4.21"),
                modelDigest = Digests.sha256("explicit_synthetic_source_batch:" + c.sources.keys.sorted().joinToString("\u0000")),
                dependencyDigest = Digests.sha256(ContractJson.encodeToString(dependencies)), toolchainDigest = selected.digest,
                configurationDigest = Digests.sha256(ContractJson.encodeToString(c.flags)),
                fullProject = false, compilationFiles = c.sources.keys.sorted())
            val base = AnalysisInput(manifest, registered.path.toString(), context, c.ruleIds, outputRoot = owned.outputRoot.toString())
            var authority: ExecutionAuthority = ExecutionAuthority.DENY
            val provider = KotlinJvmCompilerProvider(selected, ExecutionAuthority { authority.authorize(it) }, registry = registry,
                compilerHeapMiB = 512, executionObserver = { prepared, actual -> outcomes += prepared to actual })
            // Requested2.4.20 is an actual provider refusal with DENY and no profile/permit; never an alternate compiler.
            val prepared = if (c.refusal) base else base.copy(execution = provider.executionProfile(base,
                timeoutMillis = budget.timeoutMillis(), maxOutputBytes = 2_097_152))
            input = prepared
            val sealedRoot = registered.path.toRealPath()
            if (!c.refusal) authority = ExactExecutionAuthority(ExecutionPermit(checkNotNull(prepared.execution), manifest.snapshotId, sealedRoot,
                prepared.analysisKey(), {
                    budget.requireActive(false)
                    owned.verify(registered)
                    require(registered.path.toRealPath() == sealedRoot)
                    c.sources.forEach { (path, text) ->
                        val bytes = text.toByteArray(Charsets.UTF_8)
                        check(CompilerFixtureProvenance.readStable(registered.path.resolve(path), bytes.size).contentEquals(bytes))
                    }
                }, { path ->
                    budget.requireActive(false)
                    check(output == null) { "No output registration reuse" }
                    output = owned.registerOutput(path) // Original permit, before any compiler writes.
                }))
            saveBytes(directory.resolve("input.json"), (ContractJson.encodeToString(prepared) + "\n").toByteArray(), reservation = reservation)
            save(directory.resolve("attempt-start.json"), record.description() + mapOf("analysis_key" to prepared.analysisKey(),
                "source_plan_job_id" to c.id, "source_origins" to c.sourceOrigins,
                "execution_authority" to if (c.refusal) "DENY_no_profile_no_permit" else "fresh_exact_nonserializable_permit",
                "startup_provenance" to evidence.reference(start.manifestPath)), reservation = reservation)
            record.state = "provider_call_entered"
            record.providerEntered = true
            result = provider.analyze(prepared, CancellationToken { budget.remainingMillis() == 0L })
            record.providerReturned = true // Register the real return before any assertion/persistence.
            record.state = "provider_returned_unassessed"
        } catch (problem: Throwable) { retain(problem) }
        finally {
            record.observedProcesses = outcomes.size
            record.executionConfirmed = outcomes.any { it.second.executionConfirmed }
            // Establish/quarantine the child lifetime before any fallible serialization. Quarantine
            // is not deletion; even a persistence exception cannot let final-root cleanup erase an unknown child tree.
            val inputMatches = outcomes.size <= 1 && outcomes.all { it.first == input }
            val quiescence = if (outcomes.size > 1) "Multiple observer outcomes for one admitted call"
                else compilerFixtureChildQuiescenceProblem(record.providerEntered, record.providerReturned,
                    outcomes.singleOrNull()?.second, start.supervisorIdentity, inputMatches)
            if (quiescence != null) {
                owned.quarantine(quiescence)
                retain(IllegalStateException("ordinary445_child_quiescence_unverified:" + quiescence))
            }
            try {
                val parsed = mutableListOf<CompilerParseResult>()
                // Persist all observer outcomes, including an orphan after provider throw; never synthesize empty raw bytes.
                for ((index, observed) in outcomes.withIndex()) {
                    val actual = observed.second
                    val suffix = (index + 1).toString()
                    persist("stdout-" + suffix + ".runtime-string.utf8", actual.stdout.toByteArray(Charsets.UTF_8))
                    persist("stderr-" + suffix + ".runtime-string.utf8", actual.stderr.toByteArray(Charsets.UTF_8))
                    persist("process-" + suffix + ".json", ordinary445Bytes(mapOf("status" to actual.status, "reasons" to actual.reasons,
                        "exit_code" to actual.exitCode, "command" to actual.command, "output_directory" to actual.outputDirectory?.toString(),
                        "registered_pre_write_output" to output?.path?.toString(), "cancelled" to actual.cancelled,
                        "timed_out" to actual.timedOut, "truncated" to actual.truncated, "metrics" to ContractJson.encodeToJsonElement(actual.metrics),
                        "observed_analysis_key" to observed.first.analysisKey(), "admitted_analysis_key" to input?.analysisKey(),
                        "provider_returned" to record.providerReturned,
                        "stream_retention" to CompilerOrdinary445FixtureOracle.streamRetention(actual)) +
                        CompilerFixtureProcessEvidence.fields(actual, start.supervisorIdentity)))
                    try {
                        val paths = actual.outputDirectory?.let { path ->
                            c.sources.map { (sourcePath, text) -> path.resolve("sources").resolve(sourcePath) to
                                CompilerSourceIdentity(sourcePath, Digests.sha256(text), text) }.toMap()
                        }.orEmpty()
                        val inventory = CompilerDiagnosticParser().parse(actual.stderr + "\n" + actual.stdout, paths)
                        parsed += inventory
                        persist("full-parser-" + suffix + ".json", ordinary445Bytes(CompilerOrdinary445FixtureOracle.parserDescription(inventory)))
                    } catch (problem: Throwable) { retain(problem) }
                }
                result?.let { persist("result.json", (ContractJson.encodeToString(it) + "\n").toByteArray(Charsets.UTF_8)) }
                if (outcomes.isEmpty()) persist("no-process-observation.json", ordinary445Bytes(mapOf(
                    "actual_process_observed" to false, "provider_entered" to record.providerEntered, "provider_returned" to record.providerReturned)))
                record.persistenceComplete = failure == null && result != null &&
                    (if (c.refusal) outcomes.isEmpty() else outcomes.size == 1 && parsed.size == 1)

                try {
                    if (c.refusal && record.providerReturned && input != null && result != null) {
                        assessment = CompilerOrdinary445FixtureOracle.assessRefusal(c, checkNotNull(input), checkNotNull(result), outcomes.size, output != null)
                        record.receiptInvariantsMatched = assessment.receiptTrusted
                    } else if (!c.refusal && record.providerReturned && outcomes.size == 1 && parsed.size == 1 && input != null && result != null) {
                        val actual = outcomes.single().second
                        val admitted = checkNotNull(input)
                        val returned = checkNotNull(result)
                        check(outcomes.single().first == admitted) { "Observed prepared input differs from exact admitted input" }
                        check(actual.supervisorIdentity == start.supervisorIdentity && actual.executionConfirmed)
                        check(actual.outputDirectory != null && actual.outputDirectory == output?.path)
                        check(usedOutputs.add(checkNotNull(actual.outputDirectory))) { "Reused mutable output directory" }
                        check(returned.coverage.all { it.context == admitted.context.scope })
                        check(returned.findings.all { finding ->
                            finding.ruleId in c.ruleIds &&
                                registry.byRuleId.getValue(finding.ruleId).upstreamId == finding.upstreamId &&
                                finding.providerId == registryProvider() && finding.context == admitted.context.scope &&
                                finding.kind == registry.byRuleId.getValue(finding.ruleId).kind &&
                                finding.certainty == registry.byRuleId.getValue(finding.ruleId).certainty &&
                                finding.semanticDigest == admitted.analysisKey() &&
                                finding.evidence.any { it.kind == "compiler-options" && it.digest == admitted.analysisKey() } &&
                                finding.evidence.any { it.kind == "compiler-emission" && it.digest == Digests.sha256(actual.stderr + "\n" + actual.stdout) } &&
                                finding.verification == CompilerVerification.recompile(admitted, checkNotNull(finding.upstreamId),
                                    selected.digest, c.sources.keys.sorted()) &&
                                finding.verification.isNotEmpty() && finding.verification.all { it.state == "not_run" && it.command.isEmpty() }
                        }) { "Unbound or falsely verified normalized finding" }
                        record.receiptInvariantsMatched = true
                        assessment = CompilerOrdinary445FixtureOracle.assess(c, parsed.single(), returned, actual, registry)
                        val stamp = CompilerOrdinary445Stamp(c.id, admitted.snapshot.snapshotId, admitted.analysisKey(),
                            checkNotNull(actual.outputDirectory).toString(), c.primaryPath, c.sources.getValue(c.primaryPath),
                            returned.findings.map { CompilerOrdinary445Point(it.ruleId, it.upstreamId, it.severity, it.diagnosis, it.location) })
                        if (c.control == "regression") {
                            val before = records.singleOrNull { it.job.fixture?.ruleId == c.fixture?.ruleId && it.job.control == "positive" }
                                ?.takeIf { it.fact().attributable && it.assessment?.diagnosticExpectedOutcomeMatched == true }?.stamp
                            if (!CompilerOrdinary445FixtureOracle.regressionMatches(before, stamp))
                                assessment = checkNotNull(assessment).failBehavior("fresh_moved_snapshot_exact_occurrence_regression_failed")
                        }
                        record.stamp = stamp
                    }
                } catch (problem: Throwable) { retain(problem) }
            } catch (problem: Throwable) { retain(problem) }
            finally {
                val cleanup = mutableListOf<CompilerFixtureCleanupReceipt>()
                for (identity in listOfNotNull(output, snapshot)) {
                    try { cleanup += owned.cleanup(identity) } catch (problem: Throwable) { retain(problem) }
                }
                record.cleanupPassed = cleanup.isNotEmpty() && cleanup.all { it.passed } &&
                    cleanup.size == listOfNotNull(output, snapshot).size
                try {
                    persist("scratch-cleanup.json", ordinary445Bytes(mapOf("receipts" to cleanup.map { it.description() },
                        "passed" to record.cleanupPassed, "provider_entered" to record.providerEntered,
                        "provider_returned" to record.providerReturned)))
                } catch (problem: Throwable) { retain(problem) }
                if (!record.cleanupPassed) retain(IllegalStateException("ordinary445_owned_scratch_cleanup_failed"))
                if (failure != null || !record.persistenceComplete || !record.receiptInvariantsMatched) {
                    assessment = (assessment ?: CompilerOrdinary445Assessment(false,
                        if (c.hardErrorSuppression) null else false, if (c.kind == "suppression") false else null,
                        emptyList(), 0, 0)).invalidate("receipt_failed_or_unavailable")
                    record.stamp = null
                }
                record.assessment = assessment
                record.failure = failure?.let { it.javaClass.name + ":" + it.message?.take(2_048) }
                record.state = when {
                    failure != null -> "failed_retained_no_further_calls"
                    assessment?.knownAssertionsMatched != true -> "observed_known_expectation_mismatch"
                    c.hardErrorSuppression -> "executed_non_complete_safety_control_diagnostic_behavior_unknown"
                    else -> "actual_known_fixture_control_matched_not_qualified"
                }
                try {
                    persist("assessment-candidate.json", ordinary445Bytes(record.description() + mapOf("assessment" to assessment?.description(),
                        "receipt_candidate_only" to true,
                        "validity" to "Requires reconciled terminal ledger, settled summary and successful task return; never rule qualification.",
                        "source_plan" to evidence.reference(root.resolve("inputs").resolve(contract.PLAN_PATH)))))
                } catch (problem: Throwable) { retain(problem) }
                finally { reservation.close() }
            }
            fun rejectLateFailure() {
                if (failure != null) {
                    record.persistenceComplete = false
                    record.receiptInvariantsMatched = false
                    record.failure = failure?.let { it.javaClass.name + ":" + it.message?.take(2_048) }
                    record.state = "failed_retained_no_further_calls"
                    record.stamp = null
                    record.assessment = (record.assessment ?: CompilerOrdinary445Assessment(false,
                        if (c.hardErrorSuppression) null else false, if (c.kind == "suppression") false else null,
                        emptyList(), 0, 0)).invalidate("receipt_or_late_publication_failed")
                }
            }
            rejectLateFailure()
            try {
                val index = directory.resolve("job-evidence.json")
                save(index, mapOf("job_id" to c.id, "files" to evidence.immutableFilesUnder(directory).map(evidence::reference),
                    "representation" to "actual_returned_runtime_Strings_not_unavailable_native_raw_bytes",
                    "native_invalid_utf8_raw_bytes_available" to false), terminal = failure != null)
                record.evidenceIndex = evidence.reference(index)
                checkpoint("job-" + record.number, terminal = failure != null)
            } catch (problem: Throwable) { retain(problem) }
            // A late index/checkpoint failure must invalidate the eventual terminal ledger too.
            rejectLateFailure()
        }
        failure?.let { throw it }
        budget.requireActive()
    }

    private fun scope(): Map<String, Any?> = mapOf(
        "mapping_contract" to registry.mappingIdentity.contractId, "mapping_sha256" to registry.mappingIdentity.resourceDigest,
        "mapping_count" to 445, "jvm_applicable_mapping_count" to 402, "native_mapping_count" to 43,
        "selected_cohort" to settings.cohort, "all25_selectors" to contract.SELECTORS,
        "selected_rule_ids" to selectedFixtures.map { it.ruleId }, "selected_provider_calls" to jobs.size,
        "selected_compiler_calls" to jobs.count { !it.refusal }, "selected_pre_runtime_refusals" to jobs.count { it.refusal },
        "all98_provider_call_obligation" to 632, "all98_compiler_call_obligation" to 607, "all98_pre_runtime_refusal_obligation" to 25,
        "ordinary_fixture_roster" to plan.fixtures.map { f -> mapOf("rule_id" to f.ruleId, "upstream_id" to f.upstreamId,
            "declared_severity" to f.severity, "positive_source" to f.positive.description(), "negative_source" to f.negative.description(),
            "authored_status_not_execution" to f.authoredStatus, "selected_in_this_cohort" to (f in selectedFixtures)) },
        "ordinary_pairs_visible" to 98, "ordinary_source_files_visible" to 196, "planned_kmp_pairs_visible_not_run" to 16,
        "catalog_count" to 1665, "existing_exclusion_count_unchanged" to 46, "declared_obligation_cells_unchanged" to 13320,
        "compiler_mappings" to registry.mappings.map { mapping -> mapOf("rule_id" to mapping.ruleId,
            "upstream_id" to mapping.upstreamId, "backend" to mapping.backend, "jvm_applicable" to mapping.jvmApplicable,
            "scope_state" to when {
                mapping.ruleId in selectedFixtures.map { it.ruleId } -> "selected_synthetic_fixture_not_whole_context_qualification"
                !mapping.jvmApplicable -> "not_applicable_to_this_JVM_route_not_removed_from_scope"
                else -> "unselected_not_run_not_excluded"
            }, "global_implemented_stage" to null, "global_tested_stage" to null, "global_qualified_stage" to null) },
        "catalog_rules" to catalogs.map { row -> mapOf("id" to row.getValue("id").jsonPrimitive.content,
            "catalog_status" to row.getValue("status").jsonPrimitive.content,
            "implementation_origin_not_stage" to row.getValue("implementation").jsonPrimitive.content,
            "default_activation" to row.getValue("default_activation").jsonPrimitive.content,
            "global_implemented_stage" to null, "global_tested_stage" to null, "global_qualified_stage" to null) },
        "whole_context_evidence_selected" to false, "qualified" to false, "activation_changed" to false,
        "supplemental_or_planned_KMP_sources_executed" to false, "nine_observation_or_legacy443_credit_imported" to false)
    private fun summary(recorded: Boolean, failure: Throwable?, drift: List<Map<String, String>>): Map<String, Any?> = mapOf(
        "schema_version" to 1, "route" to contract.ROUTE, "cohort" to settings.cohort, "run_id" to root.fileName.toString(),
        "cohort_schedule_complete" to recorded, "full98_schedule_complete" to false, "whole_context_run_complete" to false,
        "local_stage_attribution_candidate" to recorded,
        "owner_source_snapshot" to ownerSnapshot.description(), "owner_binding_sha256" to settings.bindingSha256,
        "full_catalog_scope_count_unchanged" to 1665, "existing_exclusions_unchanged" to 46,
        "full_mapping_scope_count_unchanged" to 445, "jvm_applicable_mapping_count" to 402, "native_mapping_count" to 43,
        "ordinary_pairs_still_visible" to 98, "planned_kmp_pairs_still_visible" to 16,
        "actual_compiler_calls" to records.count { it.executionConfirmed },
        "recorded_pre_runtime_refusals_not_compiler_calls" to records.count { it.job.refusal && it.assessment?.diagnosticExpectedOutcomeMatched == true },
        "jobs" to records.map { it.description() },
        "local_rule_receipts" to CompilerOrdinary445FixtureLedger.rows(selectedFixtures, records.map { it.fact() }, recorded),
        "scope" to if (evidence.contains(root.resolve("scope.json"))) evidence.reference(root.resolve("scope.json")) else null,
        "startup_inputs_captured" to inputsCaptured, "startup_artifact_capture_complete" to (provenance != null),
        "root_cleanup_passed" to rootCleanupPassed, "drift" to drift, "budget" to budget.description(),
        "evidence_accounting" to evidence.statistics(), "failure_type" to failure?.javaClass?.name, "failure_message" to failure?.message?.take(2048),
        "completion_validity" to "This cohort only: settled summary, reconciled terminal/job ledgers, no publication-unconfirmed marker, successful exact outer command and separately reviewed build required.",
        "hard_error_suppression_diagnostic_unknown_never_counted_as_pass" to true,
        "ktchecker_owner_accepted_risk_workflow_exercised" to false,
        "global_implemented_tested_qualified_credit" to false, "qualified" to false, "activation_changed" to false,
        "historical1663x8_not_relabelled_as_declared1665x8" to true, "resume_or_merge" to false)
    private fun checkpoint(label: String, terminal: Boolean = false) {
        save(root.resolve("checkpoint-" + label + ".json"), mapOf("route" to contract.ROUTE, "cohort" to settings.cohort,
            "jobs" to records.map { it.description() }, "budget" to budget.description(),
            "local_stage_attribution_valid" to false, "qualified" to false), terminal = terminal)
    }
    private fun saveSummary(value: Any?) = evidence.replaceLatest(root.resolve("summary.json"), ordinary445Bytes(value), true)
    private fun save(path: Path, value: Any?, terminal: Boolean = false, reservation: CompilerFixtureEvidenceQuota.Reservation? = null) =
        saveBytes(path, ordinary445Bytes(value), terminal, reservation)
    private fun saveBytes(path: Path, bytes: ByteArray, terminal: Boolean = false,
        reservation: CompilerFixtureEvidenceQuota.Reservation? = null) {
        evidence.directory(path.parent)
        evidence.writeNew(path, bytes, terminal, reservation)
    }
    private fun registryProvider() = CompilerRuleRegistry.PROVIDER_ID
}

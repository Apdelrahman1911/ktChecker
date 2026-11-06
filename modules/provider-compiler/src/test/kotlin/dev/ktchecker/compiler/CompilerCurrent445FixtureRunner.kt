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

internal data class CompilerCurrent445RunReceipt(
    val directory: Path, val validUtf8ScheduleRecorded: Boolean, val knownCasesMatched: Int,
    val unknownCaseIds: List<String>, val runComplete: Boolean = false,
    val sourcePlanRawByteRetentionCapabilityResolved: Boolean = false,
)

/**
 * Explicit finite current445 observations only. No legacy selector fallback, fixture-history read,
 * qualification install, benchmark route, cache reuse, source rewrite, rerun or probe subprocess.
 */
object CompilerCurrent445FixtureRunner {
    @JvmStatic fun main(args: Array<String>) {
        val receipt = run(args)
        println("current445 valid-UTF8 observation schedule retained at " + receipt.directory +
            "; source-plan closure remains incomplete, one unknown oracle and raw-byte capability gap; no qualification")
    }

    internal fun run(args: Array<String>): CompilerCurrent445RunReceipt {
        val started = System.nanoTime()
        val settings = CompilerCurrent445Settings.parse(args)
        // No path resolution, run/scratch allocation or toolchain/provider work before this exact gate.
        val loaded = CompilerRuleRegistry.load()
        CompilerCurrent445FixtureContract.requireMapping(loaded.mappingIdentity)
        val repository = CompilerFixtureProvenance.canonicalOuterDirectory(Path.of(settings.repositoryDirectory))
        val bindingBytes = CompilerFixtureProvenance.readStable(
            repository.resolve(CompilerCurrent445FixtureContract.BINDING_PATH), 131_072)
        val pins = CompilerCurrent445FixtureContract.binding(bindingBytes, settings.bindingSha256)
        return Current445Run(repository, settings, loaded, bindingBytes, pins, started).execute()
    }
}

private class Current445Record(val number: Int, val id: String) {
    var state = "not_run"
    var providerEntered = false
    var providerReturned = false
    var observedProcesses = 0
    var executionConfirmed = false
    var persistenceComplete = false
    var receiptInvariantsMatched = false
    var cleanupPassed = false
    var assessment: CompilerCurrent445Assessment? = null
    var failure: String? = null
    var evidenceIndex: Map<String, String>? = null
    fun description(): Map<String, Any?> = mapOf(
        "number" to number, "case_id" to id, "state" to state,
        "provider_call_entered" to providerEntered, "provider_returned" to providerReturned,
        "actual_observer_outcomes" to observedProcesses, "execution_confirmed" to executionConfirmed,
        "returned_string_and_result_persistence_complete" to persistenceComplete,
        "receipt_invariants_matched" to receiptInvariantsMatched, "cleanup_passed" to cleanupPassed,
        "expected_outcome_matched" to assessment?.expectedOutcomeMatched,
        "known_assertions_matched" to assessment?.knownAssertionsMatched,
        "failure" to failure, "job_evidence_index" to evidenceIndex,
        "qualification" to "none", "ordinary_six_kind_assessment" to false,
        "counts_as_current_test_or_implementation_evidence" to false,
    )
}

private class Current445Run(
    private val repository: Path, private val settings: CompilerCurrent445Settings,
    private val registry: CompilerRuleRegistry, private val bindingBytes: ByteArray,
    private val pins: List<CompilerCurrent445Pin>, started: Long,
) {
    private val contract = CompilerCurrent445FixtureContract
    private val root = compilerFixtureCreateRunDirectory(repository, "current445-" + UUID.randomUUID().toString())
    private val evidence = CompilerFixtureEvidence(repository, root, null, settings.evidenceBytes, contract.TERMINAL_RESERVE_BYTES)
    private val budget = CompilerCurrent445Budget(settings, started, ownerStop = evidence::ownerStopRequested)
    private val records = contract.CASE_IDS.mapIndexed { index, id -> Current445Record(index + 1, id) }
    private val captured = linkedMapOf<String, ByteArray>()
    private var inputsCaptured = false
    private var cases = emptyList<CompilerCurrent445Case>()
    private var catalogs = emptyList<JsonObject>()
    private var provenance: CompilerFixtureProvenance? = null
    private var toolchain: KotlinJvmToolchain? = null
    private var dependencies = emptyList<ArtifactIdentity>()
    private var scratch: CompilerFixtureScratch? = null
    private val usedOutputs = mutableSetOf<Path>()
    private var rootCleanupPassed = false

    fun execute(): CompilerCurrent445RunReceipt {
        var failure: Throwable? = null
        var settled = false
        val drift = mutableListOf<Map<String, String>>()
        try {
            evidence.directory(root.resolve("control"))
            save(root.resolve("control/README.json"), mapOf("owner_stop_path" to root.resolve("control/stop").toString(),
                "scope" to "Bounded regular non-symlink stop marker checked between calls; no PID signalling or resume."))
            println("current445 fixed9 private observations; run=" + root + "; owner stop=" + root.resolve("control/stop"))
            // The full nine-row ledger exists even when later startup/materialization fails.
            checkpoint("admission")
            budget.requireActive()
            captureInputs()
            cases = contract.cases(captured.getValue(contract.PLAN_PATH))
            cases.forEach { contract.requireSource(it, captured.getValue(it.path)) }
            val capturedRegistry = CompilerRuleRegistry.parse(captured.getValue(contract.MAP_PATH))
            contract.requireMapping(capturedRegistry.mappingIdentity)
            check(capturedRegistry.mappingIdentity == registry.mappingIdentity)
            require(registry.mappings.size == 445 && registry.jvmRuleIds.size == 402)
            contract.RULE_IDS.forEachIndexed { index, id ->
                val mapping = registry.byRuleId.getValue(id)
                check(mapping.upstreamId == contract.UPSTREAM_IDS[index] && mapping.backend == "common" && mapping.jvmApplicable)
            }
            require("INAPPLICABLE_OPERATOR_MODIFIER_WARNING" !in registry.byUpstreamId)
            catalogs = contract.CATALOG_COUNTS.flatMap { (path, count) ->
                Json.parseToJsonElement(strictUtf8(captured.getValue(path))).jsonArray.also {
                    require(it.size == count)
                }.map { it.jsonObject }
            }
            require(catalogs.size == 1665 && catalogs.map { it.getValue("id").jsonPrimitive.content }.toSet().size == 1665)
            require(catalogs.all { it.getValue("status").jsonPrimitive.content == "planned" })
            require(catalogs.count { it.getValue("default_activation").jsonPrimitive.content == "excluded" } == 46)
            require(contract.RULE_IDS.all { id -> catalogs.any { it.getValue("id").jsonPrimitive.content == id } })
            require(Json.parseToJsonElement(strictUtf8(captured.getValue("providers/compiler-fixtures/manifest.json")))
                .jsonObject.getValue("fixtures").jsonArray.size == 98)
            require(Json.parseToJsonElement(strictUtf8(captured.getValue("providers/compiler-fixtures/planned-kmp-manifest.json")))
                .jsonObject.getValue("fixtures").jsonArray.size == 16)
            save(root.resolve("scope.json"), scope())
            // Reuse immutable custody utilities, not the old main/settings/manifest dispatcher.
            val start = CompilerFixtureProvenance.capture(repository, root, evidence) { budget.requireActive() }
            provenance = start
            save(start.manifestPath, start.description())
            check(CompilerRuleRegistry.parse(start.compilerRegistryText).mappingIdentity == registry.mappingIdentity)
            captureRouteAnchors()
            prepareToolchain()
            scratch = CompilerFixtureScratch.create(Path.of(System.getProperty("java.io.tmpdir")), "ktc-current445-")
            checkpoint("start")
            for ((index, c) in cases.withIndex()) executeCase(c, records[index])
        } catch (problem: Throwable) {
            failure = problem
        } finally {
            scratch?.let { owned ->
                try {
                    val cleanup = owned.cleanup(owned.rootIdentity)
                    rootCleanupPassed = cleanup.passed
                    try { save(root.resolve("private-root-cleanup.json"), cleanup.description(), terminal = true) }
                    catch (problem: Throwable) { failure = preserveCompilerFixtureFailure(failure, problem) }
                    if (!cleanup.passed) failure = preserveCompilerFixtureFailure(failure,
                        IllegalStateException(if (cleanup.quarantined) "current445_child_quiescence_unverified" else "current445_scratch_cleanup_failed"))
                } catch (problem: Throwable) { failure = preserveCompilerFixtureFailure(failure, problem) }
            }
            try { provenance?.let { drift += it.drift() }; drift += inputDrift() }
            catch (problem: Throwable) { failure = preserveCompilerFixtureFailure(failure, problem) }
            if (drift.isNotEmpty()) failure = preserveCompilerFixtureFailure(failure, IllegalStateException("current445_startup_identity_drift"))
            try { budget.requireActive() } catch (problem: Throwable) { failure = preserveCompilerFixtureFailure(failure, problem) }
            records.filter { it.state == "not_run" }.forEach { it.state = "not_run_after_incomplete_prefix" }
            val eligible = failure == null && rootCleanupPassed && inputsCaptured && records.size == 9 &&
                records.all { it.persistenceComplete && it.receiptInvariantsMatched && it.cleanupPassed &&
                    it.assessment?.knownAssertionsMatched == true } &&
                records.dropLast(1).all { it.assessment?.expectedOutcomeMatched == true } &&
                records.last().assessment?.expectedOutcomeMatched == null
            if (!eligible && failure == null) failure = IllegalStateException("current445_required_observations_or_known_expectations_incomplete")
            try {
                save(root.resolve("terminal-ledger.json"), summary(false, failure, drift) + mapOf("documentary_candidate_only" to true), terminal = true)
                val publication = CompilerCurrent445Publication { budget.requireActive() }
                settled = publication.publish(eligible,
                    collected = { saveSummary(summary(true, null, drift)) },
                    incomplete = { late -> saveSummary(summary(false, late ?: failure, drift)) })
            } catch (problem: Throwable) {
                failure = preserveCompilerFixtureFailure(failure, problem)
                try { save(root.resolve("publication-unconfirmed.json"), mapOf(
                    "valid_utf8_schedule_recorded" to false, "run_complete" to false, "publication_unconfirmed" to true,
                    "successful_task_return_also_required" to true), terminal = true) }
                catch (correction: Throwable) { failure = preserveCompilerFixtureFailure(failure, correction) }
            }
        }
        failure?.let { throw it }
        check(settled) { "Current445 observation publication did not settle" }
        return CompilerCurrent445RunReceipt(root, true, records.dropLast(1).count { it.assessment?.expectedOutcomeMatched == true },
            listOf(contract.CASE_IDS.last()))
    }

    private fun captureInputs() {
        saveBytes(root.resolve("inputs").resolve(contract.BINDING_PATH), bindingBytes)
        var total = 0L
        for (pin in pins) {
            budget.requireActive()
            val bytes = CompilerFixtureProvenance.readStable(repository.resolve(pin.path), contract.MAX_INPUT_BYTES)
            require(bytes.size == pin.size && Digests.sha256(bytes) == "sha256:" + pin.sha256) { "Current445 pinned source/catalog bytes differ: " + pin.path }
            total += bytes.size
            require(total <= contract.MAX_CAPTURE_BYTES)
            saveBytes(root.resolve("inputs").resolve(pin.path), bytes)
            captured[pin.path] = bytes.copyOf()
        }
        requireCaseMembership()
        inputsCaptured = true
        save(root.resolve("current445-inputs.json"), mapOf("owner_binding_sha256" to settings.bindingSha256,
            "binding" to evidence.reference(root.resolve("inputs").resolve(contract.BINDING_PATH)),
            "pins" to pins.map { mapOf("path" to it.path, "bytes" to it.size,
                "preserved" to evidence.reference(root.resolve("inputs").resolve(it.path))) },
            "source_to_bytecode_claim" to "none; source and actual executed artifacts captured independently"))
    }

    private fun requireCaseMembership() {
        val directory = repository.resolve(contract.CASE_PREFIX)
        val paths = mutableListOf<String>()
        Files.walk(directory, 4).use { walk ->
            val iterator = walk.iterator()
            var visited = 0
            while (iterator.hasNext()) {
                val path = iterator.next()
                require(++visited <= 64 && !Files.isSymbolicLink(path) && path.toRealPath() == path)
                if (Files.isRegularFile(path, NOFOLLOW_LINKS)) paths += repository.relativize(path).toString()
                else require(Files.isDirectory(path, NOFOLLOW_LINKS))
            }
        }
        require(paths.toSet() == contract.CASE_PATHS.toSet() && paths.size == 8) { "Current445 case SOURCE membership drift" }
    }

    private fun inputDrift(): List<Map<String, String>> {
        val changes = mutableListOf<Map<String, String>>()
        fun check(path: Path, expected: String, kind: String) {
            val digest = runCatching { Digests.sha256(CompilerFixtureProvenance.readStable(path, contract.MAX_INPUT_BYTES)) }.getOrNull()
            if (digest != "sha256:" + expected) changes += mapOf("kind" to kind, "path" to path.toString())
        }
        check(repository.resolve(contract.BINDING_PATH), settings.bindingSha256, "binding_changed")
        if (evidence.contains(root.resolve("inputs").resolve(contract.BINDING_PATH)))
            check(root.resolve("inputs").resolve(contract.BINDING_PATH), settings.bindingSha256, "preserved_binding_changed")
        for (pin in pins) {
            check(repository.resolve(pin.path), pin.sha256, "pinned_input_changed")
            val copy = root.resolve("inputs").resolve(pin.path)
            if (evidence.contains(copy)) check(copy, pin.sha256, "preserved_input_changed")
            else changes += mapOf("kind" to "pinned_input_capture_missing", "path" to pin.path)
        }
        if (runCatching { requireCaseMembership() }.isFailure)
            changes += mapOf("kind" to "case_source_membership_changed", "path" to contract.CASE_PREFIX)
        return changes
    }

    private fun captureRouteAnchors() {
        val old = checkNotNull(CompilerFixtureRunner::class.java.protectionDomain.codeSource?.location)
        val location = Path.of(old.toURI()).toAbsolutePath().normalize()
        val classes = listOf(CompilerCurrent445FixtureRunner::class.java, CompilerCurrent445FixtureContract::class.java,
            CompilerCurrent445FixtureOracle::class.java, CompilerCurrent445Budget::class.java,
            CompilerCurrent445Publication::class.java, CompilerCurrent445FixtureIntegrationTest::class.java)
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
            check(bytes.contentEquals(artifactBytes)) { "Current445 loaded class differs from captured CodeSource" }
            val copy = root.resolve("current445-class-anchors").resolve(entry)
            saveBytes(copy, bytes)
            mapOf("class_name" to clazz.name, "code_source" to location.toString(), "preserved_class_bytes" to evidence.reference(copy),
                "enclosing_artifact_inventory" to evidence.reference(checkNotNull(provenance).manifestPath))
        }
        save(root.resolve("current445-class-anchors.json"), mapOf("anchors" to anchors,
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
        selected.verify { budget.requireActive() }
        save(root.resolve("toolchain.json"), mapOf("toolchain_digest" to selected.digest,
            "runtime_image" to selected.runtimeImage.description(), "java_executable" to selected.javaExecutable.toString(),
            "compiler_classpath" to ContractJson.encodeToJsonElement(selected.compilerClasspath),
            "dependency_classpath" to ContractJson.encodeToJsonElement(dependencies), "compiler_heap_mib" to 512,
            "qualification" to "none", "execution_mode" to "nine_serial_isolated_JVMs_no_probe_or_retry"))
    }

    private fun executeCase(c: CompilerCurrent445Case, record: Current445Record) {
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
        var assessment: CompilerCurrent445Assessment? = null
        var failure: Throwable? = null
        fun retain(problem: Throwable) { failure = preserveCompilerFixtureFailure(failure, problem) }
        fun persist(name: String, bytes: ByteArray) {
            try { saveBytes(directory.resolve(name), bytes, reservation = reservation) }
            catch (problem: Throwable) { retain(problem) }
        }
        try {
            evidence.directory(directory)
            start.requireExecutedArtifactsStable()
            require(inputDrift().isEmpty()) { "Pinned current445 source/catalog/plan drift before launch" }
            budget.requireActive()
            val registered = owned.newSnapshot(record.number)
            snapshot = registered
            val bytes = captured.getValue(c.path).copyOf()
            val source = registered.path.resolve(c.path)
            owned.verify(registered)
            Files.createDirectories(source.parent)
            Files.write(source, bytes, CREATE_NEW, WRITE, NOFOLLOW_LINKS)
            if (source.fileSystem.supportedFileAttributeViews().contains("posix"))
                Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("r--------"))
            saveBytes(directory.resolve("source.kt"), bytes, reservation = reservation)
            val file = SnapshotFile(c.path, Digests.sha256(bytes), bytes.size.toLong())
            val manifest = SnapshotManifest(Digests.sha256(root.fileName.toString() + "\u0000" + c.id + "\u0000" + file.digest),
                "current445-private-fixtures", root.fileName.toString(), listOf(file), ownerId = "current445-owner-harness",
                captureKind = "immutable_overlay", declaredRoots = listOf("."))
            val context = AnalysisContext(RuleContext("jvm", "synthetic", ":current445-observations", "main"), "2.4.21",
                classpath = dependencies, compilerArguments = c.flags, libraries = mapOf("kotlin-stdlib" to "2.4.21"),
                modelDigest = Digests.sha256("explicit_one_file_synthetic_model:" + c.path),
                dependencyDigest = Digests.sha256(ContractJson.encodeToString(dependencies)), toolchainDigest = selected.digest,
                configurationDigest = Digests.sha256(ContractJson.encodeToString(c.flags)),
                fullProject = false, compilationFiles = listOf(c.path))
            val base = AnalysisInput(manifest, registered.path.toString(), context, contract.RULE_IDS, outputRoot = owned.outputRoot.toString())
            var authority: ExecutionAuthority = ExecutionAuthority.DENY
            val provider = KotlinJvmCompilerProvider(selected, ExecutionAuthority { authority.authorize(it) }, registry = registry,
                compilerHeapMiB = 512, executionObserver = { prepared, actual -> outcomes += prepared to actual })
            val prepared = base.copy(execution = provider.executionProfile(base, timeoutMillis = budget.timeoutMillis(), maxOutputBytes = 2_097_152))
            input = prepared
            val sealedRoot = registered.path.toRealPath()
            authority = ExactExecutionAuthority(ExecutionPermit(checkNotNull(prepared.execution), manifest.snapshotId, sealedRoot,
                prepared.analysisKey(), {
                    budget.requireActive(false)
                    owned.verify(registered)
                    require(registered.path.toRealPath() == sealedRoot)
                    check(CompilerFixtureProvenance.readStable(source, bytes.size).contentEquals(bytes))
                }, { path ->
                    budget.requireActive(false)
                    check(output == null) { "No output registration reuse" }
                    output = owned.registerOutput(path) // Original permit, before any compiler writes.
                }))
            saveBytes(directory.resolve("input.json"), (ContractJson.encodeToString(prepared) + "\n").toByteArray(), reservation = reservation)
            save(directory.resolve("attempt-start.json"), record.description() + mapOf("analysis_key" to prepared.analysisKey(),
                "source_plan_case_id" to c.id, "execution_authority" to "fresh_exact_nonserializable_permit",
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
                retain(IllegalStateException("current445_child_quiescence_unverified:" + quiescence))
            }
            try {
                val parsed = mutableListOf<CompilerParseResult>()
                // Persist all observer outcomes, including an orphan after provider throw; never synthesize empty raw bytes.
                for ((index, observed) in outcomes.withIndex()) {
                    val actual = observed.second
                    val suffix = (index + 1).toString()
                    persist("stdout-" + suffix + ".runtime-string.utf8", actual.stdout.toByteArray(Charsets.UTF_8))
                    persist("stderr-" + suffix + ".runtime-string.utf8", actual.stderr.toByteArray(Charsets.UTF_8))
                    persist("process-" + suffix + ".json", current445Bytes(mapOf("status" to actual.status, "reasons" to actual.reasons,
                        "exit_code" to actual.exitCode, "command" to actual.command, "output_directory" to actual.outputDirectory?.toString(),
                        "registered_pre_write_output" to output?.path?.toString(), "cancelled" to actual.cancelled,
                        "timed_out" to actual.timedOut, "truncated" to actual.truncated, "metrics" to ContractJson.encodeToJsonElement(actual.metrics),
                        "observed_analysis_key" to observed.first.analysisKey(), "admitted_analysis_key" to input?.analysisKey(),
                        "provider_returned" to record.providerReturned,
                        "stream_retention" to CompilerCurrent445FixtureOracle.streamRetention(actual)) +
                        CompilerFixtureProcessEvidence.fields(actual, start.supervisorIdentity)))
                    try {
                        val paths = actual.outputDirectory?.let { path ->
                            mapOf(path.resolve("sources").resolve(c.path) to CompilerSourceIdentity(c.path,
                                "sha256:" + c.sourceSha256, strictUtf8(captured.getValue(c.path))))
                        }.orEmpty()
                        val inventory = CompilerDiagnosticParser().parse(actual.stderr + "\n" + actual.stdout, paths)
                        parsed += inventory
                        persist("full-parser-" + suffix + ".json", current445Bytes(CompilerCurrent445FixtureOracle.parserDescription(inventory)))
                    } catch (problem: Throwable) { retain(problem) }
                }
                result?.let { persist("result.json", (ContractJson.encodeToString(it) + "\n").toByteArray(Charsets.UTF_8)) }
                if (outcomes.isEmpty()) persist("no-process-observation.json", current445Bytes(mapOf(
                    "actual_process_observed" to false, "provider_entered" to record.providerEntered, "provider_returned" to record.providerReturned)))
                record.persistenceComplete = failure == null && result != null && outcomes.size == 1 && parsed.size == 1

                try {
                    if (record.providerReturned && outcomes.size == 1 && parsed.size == 1 && input != null && result != null) {
                        val actual = outcomes.single().second
                        val admitted = checkNotNull(input)
                        val returned = checkNotNull(result)
                        check(outcomes.single().first == admitted) { "Observed prepared input differs from exact admitted input" }
                        check(actual.supervisorIdentity == start.supervisorIdentity && actual.executionConfirmed)
                        check(actual.outputDirectory != null && actual.outputDirectory == output?.path)
                        check(usedOutputs.add(checkNotNull(actual.outputDirectory))) { "Reused mutable output directory" }
                        check(returned.coverage.all { it.context == admitted.context.scope })
                        check(returned.findings.all { finding ->
                            finding.ruleId in contract.RULE_IDS &&
                                registry.byRuleId.getValue(finding.ruleId).upstreamId == finding.upstreamId &&
                                finding.providerId == registryProvider() && finding.context == admitted.context.scope &&
                                finding.kind == registry.byRuleId.getValue(finding.ruleId).kind &&
                                finding.certainty == registry.byRuleId.getValue(finding.ruleId).certainty &&
                                finding.semanticDigest == admitted.analysisKey() &&
                                finding.evidence.any { it.kind == "compiler-options" && it.digest == admitted.analysisKey() } &&
                                finding.evidence.any { it.kind == "compiler-emission" && it.digest == Digests.sha256(actual.stderr + "\n" + actual.stdout) } &&
                                finding.verification == CompilerVerification.recompile(admitted, checkNotNull(finding.upstreamId),
                                    selected.digest, listOf(c.path)) &&
                                finding.verification.isNotEmpty() && finding.verification.all { it.state == "not_run" && it.command.isEmpty() }
                        }) { "Unbound or falsely verified normalized finding" }
                        record.receiptInvariantsMatched = true
                        assessment = CompilerCurrent445FixtureOracle.assess(c, parsed.single(), returned, actual, registry)
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
                    persist("scratch-cleanup.json", current445Bytes(mapOf("receipts" to cleanup.map { it.description() },
                        "passed" to record.cleanupPassed, "provider_entered" to record.providerEntered,
                        "provider_returned" to record.providerReturned)))
                } catch (problem: Throwable) { retain(problem) }
                if (!record.cleanupPassed) retain(IllegalStateException("current445_owned_scratch_cleanup_failed"))
                if (failure != null || !record.persistenceComplete || !record.receiptInvariantsMatched) {
                    assessment = CompilerCurrent445Assessment(if (c.observationOnly) null else false, false,
                        (assessment?.problems.orEmpty() + "receipt_failed_or_unavailable").distinct(),
                        assessment?.selectedCounts.orEmpty())
                }
                record.assessment = assessment
                record.failure = failure?.let { it.javaClass.name + ":" + it.message?.take(2_048) }
                record.state = when {
                    failure != null -> "failed_retained_no_further_calls"
                    assessment?.knownAssertionsMatched != true -> "observed_known_expectation_mismatch"
                    c.observationOnly -> "observed_hard_error_suppression_still_unknown"
                    else -> "observed_known_source_expectation_matched_not_qualified"
                }
                try {
                    persist("observation.json", current445Bytes(record.description() + mapOf("assessment" to assessment?.description(),
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
                    record.assessment = CompilerCurrent445Assessment(if (c.observationOnly) null else false, false,
                        (record.assessment?.problems.orEmpty() + "receipt_or_late_publication_failed").distinct(),
                        record.assessment?.selectedCounts.orEmpty())
                }
            }
            rejectLateFailure()
            try {
                val index = directory.resolve("job-evidence.json")
                save(index, mapOf("case_id" to c.id, "files" to evidence.immutableFilesUnder(directory).map(evidence::reference),
                    "representation" to "actual_returned_runtime_Strings_not_unavailable_native_raw_bytes",
                    "source_plan_raw_retention_capability_resolved" to false), terminal = failure != null)
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
        "selected_rule_ids" to contract.RULE_IDS, "source_case_count" to 9, "unique_source_file_count" to 8,
        "ordinary_legacy_fixture_pairs_visible_not_run" to 98, "planned_kmp_fixture_pairs_visible_not_run" to 16,
        "catalog_count" to 1665, "existing_exclusion_count_unchanged" to 46, "declared_obligation_cells_unchanged" to 13320,
        "compiler_mappings" to registry.mappings.map { mapping -> mapOf("rule_id" to mapping.ruleId,
            "upstream_id" to mapping.upstreamId, "backend" to mapping.backend, "jvm_applicable" to mapping.jvmApplicable,
            "scope_state" to when {
                mapping.ruleId in contract.RULE_IDS -> "selected_for_private_observation_not_rule_qualification"
                !mapping.jvmApplicable -> "not_applicable_to_this_JVM_route_not_excluded_from_launch_scope"
                else -> "unselected_not_run_not_excluded"
            }, "implemented_stage" to null, "tested_stage" to null, "qualified_stage" to null) },
        "catalog_rules" to catalogs.map { row -> mapOf("id" to row.getValue("id").jsonPrimitive.content,
            "catalog_status" to row.getValue("status").jsonPrimitive.content,
            "implementation_origin_not_stage" to row.getValue("implementation").jsonPrimitive.content,
            "default_activation" to row.getValue("default_activation").jsonPrimitive.content,
            "implemented_stage" to null, "tested_stage" to null, "qualified_stage" to null,
            "current_evidence_refs" to emptyList<String>()) },
        "ordinary_six_kind_assessment" to false, "qualification" to "none", "scope_removals" to emptyList<String>(),
        "jvm_observation_does_not_qualify_common_on_native_android_or_desktop" to true,
    )

    private fun summary(recorded: Boolean, failure: Throwable?, drift: List<Map<String, String>>): Map<String, Any?> = mapOf(
        "schema_version" to 1, "route" to contract.SELECTOR, "run_id" to root.fileName.toString(),
        "valid_utf8_schedule_recorded" to recorded, "run_complete" to false,
        "source_plan_expected_outcome_closure_complete" to false,
        "source_plan_raw_byte_retention_capability_resolved" to false,
        "full_catalog_scope_count_unchanged" to 1665, "existing_exclusions_unchanged" to 46,
        "full_mapping_scope_count_unchanged" to 445, "jvm_applicable_mapping_count" to 402, "native_mapping_count" to 43,
        "legacy_ordinary_fixture_pairs_still_visible" to 98, "planned_kmp_fixture_pairs_still_visible" to 16,
        "scope_reduction" to false,
        "mandatory_unknown_case_ids" to listOf(contract.CASE_IDS.last()),
        "unknown_is_never_counted_as_pass" to true, "cases" to records.map { it.description() },
        "known_case_matches" to records.dropLast(1).count { it.assessment?.expectedOutcomeMatched == true },
        "scope" to if (evidence.contains(root.resolve("scope.json"))) evidence.reference(root.resolve("scope.json")) else null,
        "startup_inputs_captured" to inputsCaptured, "startup_artifact_capture_complete" to (provenance != null),
        "root_cleanup_passed" to rootCleanupPassed, "drift" to drift, "budget" to budget.description(),
        "evidence_accounting" to evidence.statistics(), "failure_type" to failure?.javaClass?.name,
        "failure_message" to failure?.message?.take(2_048),
        "completion_validity" to "Documentary valid-UTF8 schedule only; final selector plus successful task return required. Not source-plan9closure or rule delivery.",
        "qualification" to "none", "ordinary_six_kind_assessment" to false,
        "counts_as_current_test_or_implementation_evidence" to false, "resume_or_merge" to false,
    )

    private fun checkpoint(label: String, terminal: Boolean = false) {
        save(root.resolve("checkpoint-" + label + ".json"), mapOf("route" to contract.SELECTOR,
            "cases" to records.map { it.description() }, "budget" to budget.description(),
            "run_complete" to false, "qualification" to "none"), terminal = terminal)
    }
    private fun saveSummary(value: Any?) = evidence.replaceLatest(root.resolve("summary.json"), current445Bytes(value), true)
    private fun save(path: Path, value: Any?, terminal: Boolean = false, reservation: CompilerFixtureEvidenceQuota.Reservation? = null) =
        saveBytes(path, current445Bytes(value), terminal, reservation)
    private fun saveBytes(path: Path, bytes: ByteArray, terminal: Boolean = false,
        reservation: CompilerFixtureEvidenceQuota.Reservation? = null) {
        evidence.directory(path.parent)
        evidence.writeNew(path, bytes, terminal, reservation)
    }
    private fun registryProvider() = CompilerRuleRegistry.PROVIDER_ID
}

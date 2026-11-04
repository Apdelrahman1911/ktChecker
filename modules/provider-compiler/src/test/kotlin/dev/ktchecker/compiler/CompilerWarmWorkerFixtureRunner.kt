package dev.ktchecker.compiler

import dev.ktchecker.compilerworker.CompilerWarmWorkerMain
import dev.ktchecker.compilerworker.CompilerWarmWorkerProtocol as Wire
import dev.ktchecker.compilerworker.PinnedKotlinCompilerAdapter
import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.encodeToString

/** Explicit private test host. Does not call a shipped provider or modify any ordinary fixture evidence. */
object CompilerWarmWorkerFixtureRunner {
    private const val GENERATION_MILLIS = 120_000L
    private const val EVIDENCE_BYTES = 134_217_728L
    private const val SOURCE_NAME = "Fixture.kt"
    // The existing runner otherwise inherits optional SystemRoot; fix that value too, even on Linux.
    private val environment = mapOf("PATH" to "", "SystemRoot" to "", "LANG" to "C", "LC_ALL" to "C")
    private val invoked = java.util.concurrent.atomic.AtomicBoolean()

    @JvmStatic fun main(args: Array<String>) {
        val started = System.nanoTime()
        val deadline = Math.addExact(started, GENERATION_MILLIS * 1_000_000)
        check(invoked.compareAndSet(false, true)) { "No resumed or replacement warm generation" }
        require(args.size == 1 && System.getProperty("ktchecker.compiler.warm.consent") == "finite-four-2.4.21") {
            "Explicit owner finite-four-2.4.21 experiment consent and one fixture root are required"
        }
        require(System.getProperty("java.specification.version") == "21" && System.getProperty("os.name") == "Linux") {
            "This private feasibility cut is Linux JDK21 only; no new platform availability claim"
        }
        val supplied = Path.of(args.single()).toAbsolutePath().normalize()
        require(supplied.fileName.toString() == "compiler-fixtures" && supplied.parent.fileName.toString() == "providers")
        val repository = CompilerFixtureProvenance.canonicalOuterDirectory(supplied.parent.parent)
        val fixtureRoot = repository.resolve("providers/compiler-fixtures")
        require(fixtureRoot.toRealPath() == fixtureRoot && Files.isDirectory(fixtureRoot, NOFOLLOW_LINKS))
        val runId = Instant.now().toString().replace(':', '-') + "-warm-generation-" + UUID.randomUUID()
        val runs = fixtureRoot.resolve("runs")
        if (!Files.exists(runs, NOFOLLOW_LINKS)) Files.createDirectory(runs)
        fixtureDirectoryIdentity(runs)
        val runRoot = Files.createDirectory(runs.resolve(runId))
        privateFixturePermissions(runRoot)
        val evidence = CompilerFixtureEvidence(repository, runRoot, latestSidecar = null, maximumBytes = EVIDENCE_BYTES)
        evidence.directory(runRoot.resolve("control"))
        evidence.writeNew(runRoot.resolve("invocation.json"), warmJsonBytes(mapOf(
            "contract" to Wire.VERSION, "api" to Wire.API, "generation_millis_including_preparation" to GENERATION_MILLIS,
            "schedule" to Wire.KINDS, "compiler_heap_mib" to 512, "host_heap_mib" to 384,
            "evidence_maximum_bytes" to EVIDENCE_BYTES, "external_latest_sidecar" to false,
            "authorization" to "four distinct warm-job capabilities plus one generation launch grant; not one-shot CLI permits",
            "owner_stop" to "control/stop cancels the entire generation; no independent job cancellation",
            "ordinary_fixture_run_complete" to false, "qualification" to "none",
        )))
        var scratch: CompilerFixtureScratch? = null
        var contract: CompilerWarmWorkerContract? = null
        var provenance: CompilerFixtureProvenance? = null
        var actual: ProcessOutcome? = null
        var entered = false
        var returned = false
        var outputIdentity: CompilerFixtureDirectoryIdentity? = null
        val ownedDescendants = mutableListOf<CompilerFixtureDirectoryIdentity>()
        var failure: Throwable? = null
        val diagnostics = CompilerWarmHostDiagnostics()
        var ownerInputsVerified = false
        var expectedOutcomes = false
        val receipts = mutableListOf<Wire.Receipt>()
        var cleanup: CompilerFixtureCleanupReceipt? = null
        fun recordFailure(problem: Throwable) {
            if (failure == null) {
                failure = problem
                diagnostics.record(problem)
            }
        }
        fun checkpoint() {
            if (System.nanoTime() >= deadline) throw CompilerWarmHostRejection(CompilerWarmHostFailureCode.GENERATION_DEADLINE)
            if (evidence.ownerStopRequested()) throw CompilerWarmHostRejection(CompilerWarmHostFailureCode.OWNER_STOPPED)
        }
        fun save(name: String, value: Any?, terminal: Boolean = false) =
            evidence.writeNew(runRoot.resolve(name), warmJsonBytes(value), terminal = terminal)
        try {
            checkpoint()
            val captured = CompilerFixtureProvenance.capture(repository, runRoot, evidence, ::checkpoint)
            provenance = captured
            save("startup-provenance.json", captured.description())
            diagnostics.stage = CompilerWarmHostStage.FIXTURE_INPUTS
            val positive = captured.readFixture("cases/unresolved_reference/positive.kt")
            val clean = captured.readFixture("cases/unresolved_reference/negative.kt")
            require(positive == "package fixtures.unresolved_reference\n\nfun broken() = absentSymbol()\n")
            require(clean == "package fixtures.unresolved_reference\n\nfun absentSymbol() = 1\nfun broken() = absentSymbol()\n")
            val contents = listOf(positive, clean, clean, positive).map { it.toByteArray(Charsets.UTF_8) }
            diagnostics.stage = CompilerWarmHostStage.COMPILER_CLOSURE
            val compilerPaths = checkNotNull(System.getProperty("ktchecker.compiler.classpath")).split(File.pathSeparator).map {
                require(it.isNotBlank()); Path.of(it).toAbsolutePath().normalize()
            }
            require(compilerPaths.size in 1..16 && compilerPaths.distinct().size == compilerPaths.size)
            val compilerSizes = compilerPaths.map { path ->
                require(path.fileName.toString().matches(Regex("[A-Za-z0-9._-]+\\.jar")))
                Files.size(path).also { require(it in 1L..Wire.MAX_ARTIFACT_BYTES) }
            }
            require(compilerSizes.sum() <= Wire.MAX_ARTIFACT_TOTAL_BYTES)
            diagnostics.stage = CompilerWarmHostStage.TOOLCHAIN_CAPTURE
            val toolchain = KotlinJvmToolchain(Path.of(checkNotNull(System.getProperty("ktchecker.compiler.java"))), compilerPaths.map {
                ArtifactIdentity("selected-compiler:${it.fileName}", fileDigest(it), it.toString())
            }, KotlinJvmRuntimeLimits(maxScanMillis = 60_000))
            checkpoint()
            diagnostics.stage = CompilerWarmHostStage.BRIDGE_VERIFICATION
            val bridgePath = Path.of(checkNotNull(System.getProperty("ktchecker.compiler.warm.bridge"))).toAbsolutePath().normalize()
            val bridge = ArtifactIdentity("ktchecker-private-warm-bridge:preview-1",
                Digests.sha256(CompilerFixtureProvenance.readStable(bridgePath, 2_097_152)), bridgePath.toString())
            verifyBridge(bridge)
            diagnostics.stage = CompilerWarmHostStage.BRIDGE_PERSISTENCE
            val bridgeBytes = CompilerFixtureProvenance.readStable(bridgePath, 2_097_152)
            require(Digests.sha256(bridgeBytes) == bridge.digest)
            evidence.writeNew(runRoot.resolve("worker.jar"), bridgeBytes)
            diagnostics.stage = CompilerWarmHostStage.IMPLEMENTATION_IDENTITY
            val compilerArtifacts = toolchain.compilerClasspath.map {
                Wire.Artifact(Path.of(it.path).fileName.toString(), it.digest, Files.size(Path.of(it.path)))
            }
            val dependencyIndexes = compilerArtifacts.indices.filter {
                compilerArtifacts[it].fileName() == "kotlin-stdlib-2.4.21.jar" || compilerArtifacts[it].fileName().matches(Regex("annotations-[0-9.]+\\.jar"))
            }
            val dependencies = dependencyIndexes.map { toolchain.compilerClasspath[it] }
            val dependencyDigest = Digests.sha256(ContractJson.encodeToString(dependencies))
            val ownerDomain = UUID.randomUUID().toString().replace("-", "")
            val generation = UUID.randomUUID().toString().replace("-", "")
            val artifacts = captured.description().getValue("executed_artifacts") as List<*>
            val hostComponents = artifacts.map { row ->
                val fields = row as Map<*, *>
                mapOf("code_source" to fields["code_source"], "kind" to fields["kind"], "roles" to fields["roles"], "digest" to fields["manifest_sha256"])
            }
            val implementation = WarmImplementationKey(
                Digests.sha256(Digests.canonical(warmJson(hostComponents))), bridge.digest,
                Digests.sha256(ContractJson.encodeToString(toolchain.compilerClasspath)), toolchain.runtimeImage.digest,
                toolchain.executableDigest, Digests.sha256(Digests.canonical(warmJson(
                    CompilerFixtureProvenance.supervisorIdentityFields(captured.supervisorIdentity)))),
                Digests.sha256(Digests.canonical(warmJson(mapOf("argv" to arguments(toolchain.digest, "{plan-digest}", "{generation}", "{implementation}"),
                    "environment" to environment, "temporary_environment" to "TMPDIR,TMP,TEMP={output}/tmp",
                    "generation_millis_including_preparation" to GENERATION_MILLIS, "max_raw_output_bytes" to 131_072,
                    "host_heap_mib" to 384, "schedule" to Wire.KINDS)))),
                ownerDomain, System.getProperty("os.name"), System.getProperty("os.arch"),
            )
            diagnostics.stage = CompilerWarmHostStage.INPUT_MATERIALIZATION
            val owned = CompilerFixtureScratch.create(Path.of(System.getProperty("java.io.tmpdir")), "ktc-warm-generation-",
                maxEntries = 8_192, maxDepth = 20)
            scratch = owned
            val materialized = owned.newSnapshot(0)
            val jobDirectories = contents.indices.map { index ->
                val path = Files.createDirectory(materialized.path.resolve("job-$index"))
                privateFixturePermissions(path)
                fixtureDirectoryIdentity(path).also(ownedDescendants::add)
            }
            val inputs = contents.mapIndexed { index, bytes ->
                val path = jobDirectories[index].path.resolve(SOURCE_NAME)
                Files.write(path, bytes, CREATE_NEW, WRITE, NOFOLLOW_LINKS)
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r--------"))
                val files = listOf(SnapshotFile(SOURCE_NAME, Digests.sha256(bytes), bytes.size.toLong()))
                val snapshot = SnapshotManifest(Digests.sha256(ContractJson.encodeToString(files)), "private-warm-fixtures", "synthetic-originals", files,
                    ownerId = ownerDomain, captureKind = "immutable_overlay", declaredRoots = listOf("."))
                val context = AnalysisContext(RuleContext("jvm", "synthetic", ":private-warm-fixtures", "main"), "2.4.21", dependencies,
                    compilerArguments = listOf("-jvm-target", "17"), modelDigest = Digests.sha256("limited-synthetic-members:Fixture.kt"),
                    dependencyDigest = dependencyDigest, toolchainDigest = toolchain.digest,
                    configurationDigest = Digests.sha256("private-warm-fixed-jvm17-no-plugins"), fullProject = false, compilationFiles = listOf(SOURCE_NAME))
                AnalysisInput(snapshot, jobDirectories[index].path.toString(), context)
            }
            diagnostics.stage = CompilerWarmHostStage.PLAN_AUTHORITY
            val jobs = inputs.mapIndexed { index, input ->
                val source = input.snapshot.files.single()
                val draft = Wire.Job(index, "$generation-job-$index", Wire.KINDS[index], input.analysisKey(), input.analysisKey(),
                    input.snapshot.snapshotId, checkNotNull(input.context.modelDigest), dependencyDigest,
                    checkNotNull(input.context.configurationDigest), Digests.sha256("unsealed-profile"), source.digest, source.size)
                Wire.Job(index, draft.jobId(), draft.kind(), draft.originalKey(), draft.effectiveKey(), draft.snapshotDigest(),
                    draft.modelDigest(), draft.dependencyDigest(), draft.configurationDigest(), Wire.profileDigest(draft, dependencyIndexes),
                    draft.sourceDigest(), draft.sourceSize())
            }
            val plan = Wire.Plan(generation, ownerDomain, implementation.digest, bridge.digest, toolchain.digest, compilerArtifacts, dependencyIndexes, jobs)
            val planBytes = Wire.encodePlan(plan)
            val selected = CompilerWarmWorkerContract(plan, List(Wire.JOB_COUNT) { deadline })
            contract = selected
            val permits = selected.grantJobs()
            val jobOutputIdentities = mutableListOf<CompilerFixtureDirectoryIdentity>()
            val outputDirectories = linkedMapOf<String, CompilerFixtureDirectoryIdentity>()
            fun registerDirectory(output: Path, relative: String, create: Boolean = true): CompilerFixtureDirectoryIdentity {
                val path = output.resolve(relative)
                if (create) Files.createDirectory(path)
                val identity = fixtureDirectoryIdentity(path)
                check(outputDirectories.putIfAbsent(relative, identity) == null)
                ownedDescendants += identity
                privateFixturePermissions(path)
                return identity
            }
            fun verifyOwners() {
                checkpoint()
                selected.verifyLive()
                captured.requireExecutedArtifactsStable()
                verifyBridge(bridge)
                toolchain.verify(::checkpoint)
                CompilerSnapshot.classpath(dependencies, ::checkpoint)
                owned.verify(materialized)
                require(CompilerWarmWorkerMain.entries(materialized.path) == jobDirectories.indices.map { "job-$it" }.toSet())
                inputs.forEachIndexed { index, input ->
                    requireFixtureDirectory(jobDirectories[index])
                    require(input.analysisKey() == plan.jobs()[index].originalKey())
                    require(CompilerWarmWorkerMain.entries(jobDirectories[index].path) == setOf(SOURCE_NAME))
                    val sources = CompilerSnapshot.read(input, ::checkpoint)
                    require(sources.single().text.toByteArray(Charsets.UTF_8).contentEquals(contents[index]))
                }
                outputIdentity?.let { output ->
                    owned.verify(output)
                    outputDirectories.values.forEach(::requireFixtureDirectory)
                    require(CompilerWarmWorkerMain.entries(output.path) ==
                        setOf("tmp", "worker.jar", "plan.bin", "compiler", "jobs"))
                    require(CompilerWarmWorkerMain.entries(output.path.resolve("compiler")) ==
                        compilerArtifacts.indices.map { "$it.jar" }.toSet())
                    require(CompilerWarmWorkerMain.entries(output.path.resolve("jobs")) == jobs.indices.map { it.toString() }.toSet())
                    require(Wire.readBounded(output.path, "plan.bin", Wire.MAX_PLAN_BYTES).contentEquals(planBytes))
                    jobs.forEach { CompilerWarmWorkerMain.verifySource(it, output.path) }
                    compilerArtifacts.forEachIndexed { index, artifact ->
                        checkpoint()
                        Wire.verifyArtifact(output.path, "compiler/$index.jar", artifact.digest(), artifact.size())
                    }
                    Wire.verifyArtifact(output.path, "worker.jar", bridge.digest, Files.size(bridgePath))
                    CompilerWarmWorkerMain.boundedTree(output.path, Wire.MAX_ARTIFACT_TOTAL_BYTES + 8_388_608L, 4_096)
                }
            }
            diagnostics.stage = CompilerWarmHostStage.PRELAUNCH_VERIFICATION
            verifyOwners()
            diagnostics.stage = CompilerWarmHostStage.PLAN_PERSISTENCE
            evidence.writeNew(runRoot.resolve("plan.bin"), planBytes)
            save("plan.json", mapOf("generation" to generation, "owner_domain" to ownerDomain, "implementation_key" to implementation.digest,
                "plan_digest" to selected.planDigest, "ordered_compiler_artifacts" to compilerArtifacts.map { mapOf(
                    "file" to it.fileName(), "digest" to it.digest(), "bytes" to it.size()) }, "dependency_indexes" to dependencyIndexes,
                "schedule" to jobs.map { mapOf("ordinal" to it.ordinal(), "job_id" to it.jobId(), "kind" to it.kind(),
                    "profile_digest" to it.profileDigest(), "source_digest" to it.sourceDigest()) }, "original_effective_inputs" to inputs.map {
                    ContractJson.encodeToJsonElement(AnalysisInput.serializer(), it) }))
            save("toolchain.json", mapOf("digest" to toolchain.digest, "runtime_image" to toolchain.runtimeImage.description(),
                "java_executable" to toolchain.javaExecutable.toString(), "bridge_digest" to bridge.digest,
                "component_identity" to hostComponents, "api" to Wire.API, "compiler_pipeline_not_cli_equivalence" to true))
            diagnostics.stage = CompilerWarmHostStage.EXECUTION_REQUEST
            val generationFiles = inputs.flatMapIndexed { index, input -> input.snapshot.files.map { it.copy(path = "job-$index/${it.path}") } }
            val generationSnapshot = SnapshotManifest(Digests.sha256(ContractJson.encodeToString(generationFiles)), "private-warm-fixtures", generation,
                generationFiles, ownerId = ownerDomain, captureKind = "immutable_overlay", declaredRoots = listOf("."))
            val execution = TrustedExecution(toolchain.javaExecutable.toString(), arguments(toolchain.digest, selected.planDigest, generation, implementation.digest),
                true, toolchain.executableDigest, minOf(GENERATION_MILLIS, selected.remainingMillis()), 131_072, environment)
            val request = AnalysisInput(generationSnapshot, materialized.path.toString(), AnalysisContext(
                RuleContext("jvm", "private-warm-generation", ":private-warm-fixtures", "main"), "2.4.21", toolchainDigest = toolchain.digest,
                configurationDigest = implementation.digest, fullProject = false, compilationFiles = generationFiles.map { it.path }),
                execution = execution, outputRoot = owned.outputRoot.toString())
            selected.recordLaunchGrant()
            val launch = ExecutionPermit(execution, generationSnapshot.snapshotId, materialized.path, request.analysisKey(), ::verifyOwners, { output ->
                checkpoint()
                check(outputIdentity == null) { "Generation output preparation cannot be replayed" }
                val outputRegistration = owned.registerOutput(output)
                outputIdentity = outputRegistration
                registerDirectory(output, "tmp", create = false)
                registerDirectory(output, "compiler")
                registerDirectory(output, "jobs")
                CompilerSnapshot.copyArtifact(bridge, output.resolve("worker.jar"), ::checkpoint)
                toolchain.compilerClasspath.forEachIndexed { index, artifact ->
                    CompilerSnapshot.copyArtifact(artifact, output.resolve("compiler/$index.jar"), ::checkpoint)
                }
                Files.write(output.resolve("plan.bin"), planBytes, CREATE_NEW, WRITE, NOFOLLOW_LINKS)
                Files.setPosixFilePermissions(output.resolve("plan.bin"), PosixFilePermissions.fromString("r--------"))
                jobs.forEachIndexed { index, job ->
                    jobOutputIdentities += registerDirectory(output, "jobs/$index")
                    registerDirectory(output, "jobs/$index/sources")
                    registerDirectory(output, "jobs/$index/classes")
                    Files.write(output.resolve(job.sourcePath()), contents[index], CREATE_NEW, WRITE, NOFOLLOW_LINKS)
                    Files.setPosixFilePermissions(output.resolve(job.sourcePath()), PosixFilePermissions.fromString("r--------"))
                }
                verifyOwners()
                selected.admit(permits, outputRegistration.key, jobOutputIdentities.map { it.key })
                selected.starting()
                save("admissions.json", selected.description() + mapOf("actual_output_root" to output.toString(),
                    "actual_output_root_key" to outputRegistration.key, "job_root_keys" to jobOutputIdentities.map { it.key },
                    "registered_nested_directories" to outputDirectories.map { (relative, identity) -> mapOf("path" to relative, "key" to identity.key) },
                    "process_execution_claim" to "none; admissions precede actual supervised exec"))
            })
            save("generation-input.json", ContractJson.encodeToJsonElement(AnalysisInput.serializer(), request))
            checkpoint()
            diagnostics.stage = CompilerWarmHostStage.GENERATION_EXECUTION
            entered = true
            val process = IsolatedProcessRunner { observed -> actual = observed }.run(request, ExactExecutionAuthority(launch), CancellationToken {
                val stopped = runCatching { evidence.ownerStopRequested() }.getOrElse { selected.poison("owner_control_unavailable"); true }
                if (stopped) selected.poison("owner_stopped_generation")
                selected.cancelled()
            })
            returned = true
            actual = process
            diagnostics.stage = CompilerWarmHostStage.PROCESS_OBSERVATION
            save("process.json", processFields(process, captured.supervisorIdentity))
            evidence.writeNew(runRoot.resolve("stdout.txt"), process.stdout.toByteArray())
            evidence.writeNew(runRoot.resolve("stderr.txt"), process.stderr.toByteArray())
            check(process.successfulExecution && process.supervisorIdentity == captured.supervisorIdentity) {
                "No matching successful native generation terminal; no receipt reads or scratch deletion"
            }
            check(process.outputDirectory == checkNotNull(outputIdentity).path)
            diagnostics.stage = CompilerWarmHostStage.RECEIPT_OBSERVATION
            jobs.forEach { job ->
                val bytes = process.report(job.receiptPath(), Wire.MAX_RECEIPT_BYTES)
                evidence.writeNew(runRoot.resolve("receipt-${job.ordinal()}.bin"), bytes)
                receipts += Wire.decodeReceipt(bytes)
            }
            diagnostics.stage = CompilerWarmHostStage.POSTFLIGHT_VERIFICATION
            verifyOwners()
            check(captured.drift().isEmpty()) { "Authored or executed component provenance drifted" }
            ownerInputsVerified = true
            diagnostics.stage = CompilerWarmHostStage.OUTCOME_OBSERVATION
            expectedOutcomes = jobs.indices.all { index -> expectedOutcome(jobs[index], receipts[index], checkNotNull(outputIdentity).path) }
            save("observations.json", mapOf("job_observations" to receipts.map(::receiptFields), "expected_outcomes" to expectedOutcomes,
                "comparison_scope" to "single fixed four-job generation; no speedup, causality, representative-project or SLO claim"))
        } catch (problem: Throwable) {
            recordFailure(problem)
            contract?.poison("host_preparation_or_observation_failed")
        } finally {
            val expectedSupervisor = provenance?.supervisorIdentity
            diagnostics.stage = CompilerWarmHostStage.CONTRACT_FINALIZATION
            if (contract != null && expectedSupervisor != null) {
                runCatching { contract!!.finish(actual, expectedSupervisor, receipts, ownerInputsVerified, expectedOutcomes) }
                    .onFailure(::recordFailure)
            }
            val quiescent = actual?.successfulExecution == true && actual?.supervisorIdentity == expectedSupervisor &&
                "owned_process_group_shutdown_unverified" !in actual!!.reasons
            if (entered && !quiescent) scratch?.quarantine("Whole-generation native quiescence is not established")
            diagnostics.stage = CompilerWarmHostStage.SCRATCH_CLEANUP
            try {
                // Nested roots are also owner registrations; cleanup may not inventory replacements as fresh authority.
                if (!entered || quiescent) ownedDescendants.forEach(::requireFixtureDirectory)
                scratch?.let { owned -> cleanup = owned.cleanup(owned.rootIdentity) }
            } catch (problem: Throwable) {
                scratch?.quarantine("Registered descendant ownership or scratch cleanup is uncertain")
                contract?.poison("owned_scratch_cleanup_uncertain")
                recordFailure(problem)
                // A quarantined call returns an explicit no-new-inventory receipt; it performs no deletion.
                scratch?.let { owned -> cleanup = owned.cleanup(owned.rootIdentity) }
            }
            diagnostics.stage = CompilerWarmHostStage.FINAL_CHECKPOINT
            try { checkpoint() } catch (problem: Throwable) {
                contract?.poison("publication_deadline_or_owner_stop")
                recordFailure(problem)
            }
            diagnostics.stage = CompilerWarmHostStage.SUMMARY_PUBLICATION
            try {
                save("summary.json", mapOf("contract" to contract?.description(), "api" to Wire.API,
                    "host_call_entered" to entered, "host_call_returned" to returned,
                    "process" to actual?.let { processFields(it, expectedSupervisor) }, "job_observations" to receipts.map(::receiptFields),
                    "scratch_cleanup" to cleanup?.description(), "failure_type" to failure?.javaClass?.name,
                    "failure_stage" to diagnostics.failureStage?.code, "failure_code" to diagnostics.failureCode?.code,
                    "failure_diagnostic_scope" to "failure_stage/failure_code describe first caught host failure only; no message, stack, cause, or path",
                    "elapsed_millis" to (System.nanoTime() - started) / 1_000_000,
                    "observations_complete_before_summary_publication" to (failure == null && contract?.observationsComplete == true && cleanup?.passed == true),
                    "publication_scope" to "pre-write checkpoint only; caller must also observe successful host task return",
                    "terminal_denial_and_safe_cleanup_may_outlive_generation_budget" to true,
                    "evidence_accounting_before_summary_write" to evidence.statistics(),
                    "ordinary_fixture_run_complete" to false, "qualification" to "none", "replacement_generations" to 0), terminal = true)
            } catch (problem: Throwable) { recordFailure(problem) }
        }
        println("Private warm-generation receipt: $runRoot; qualification=none")
        // A late/failed final write is never a successful host experiment even if its pre-write snapshot was eligible.
        checkpoint()
        check(failure == null && contract?.observationsComplete == true && cleanup?.passed == true) { "Private warm generation incomplete; inspect immutable receipts" }
    }

    private fun arguments(toolchain: String, plan: String, generation: String, implementation: String): List<String> = listOf(
        "-Xmx512m", "-Dktchecker.compiler.toolchain=$toolchain", "-Dfile.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US",
        "-Djava.awt.headless=true", "-Dkotlin.environment.keepalive=false", "-Dkotlin.colors.enabled=false", "-Djava.io.tmpdir={output}/tmp",
        "-cp", "{output}/worker.jar", "dev.ktchecker.compilerworker.CompilerWarmWorkerMain", "{output}", plan, generation, implementation,
    )

    private fun verifyBridge(artifact: ArtifactIdentity) {
        val classes = listOf(Wire::class.java, CompilerWarmWorkerMain::class.java, PinnedKotlinCompilerAdapter::class.java)
        val roots = classes.map { Path.of(it.protectionDomain.codeSource.location.toURI()) }.distinct()
        if (roots.size != 1) throw CompilerWarmHostRejection(CompilerWarmHostFailureCode.BRIDGE_CODE_ROOT)
        CompilerWarmWorkerBridgeImage.verify(artifact, roots.single())
    }

    private fun expectedOutcome(job: Wire.Job, receipt: Wire.Receipt, output: Path): Boolean {
        if (receipt.state() != "sealed") return false
        val errors = receipt.diagnostics().filter { it.severity() in setOf("ERROR", "EXCEPTION") }
        return if (job.kind() == "positive") receipt.compilerCode() == 1 && errors.any {
            it.diagnosticId() == "UNRESOLVED_REFERENCE" && it.path() == output.resolve(job.sourcePath()).toString() && it.line() == 3
        } else receipt.compilerCode() == 0 && errors.isEmpty() && receipt.diagnostics().none { it.diagnosticId() == "UNRESOLVED_REFERENCE" }
    }

    private fun receiptFields(value: Wire.Receipt): Map<String, Any?> = mapOf(
        "ordinal" to value.ordinal(), "job_id" to value.jobId(), "state" to value.state(), "compiler_api_code" to value.compilerCode(),
        "compiler_version" to value.compilerVersion(), "compiler_interpretation" to when {
            value.state() != "sealed" -> value.state()
            value.compilerCode() == 1 -> "compiler_failed"
            else -> "clean_unqualified"
        }, "worker_clock_start_nanos" to value.startedNanos(), "worker_clock_return_nanos" to value.returnedNanos(),
        "worker_clock_disposal_return_nanos" to value.disposedNanos(), "disposal_observed" to value.disposalObserved(),
        "cancellation_observed" to value.cancellationObserved(), "failure_code" to value.failureCode(),
        "generation" to value.generation(), "plan_digest" to value.planDigest(), "implementation_key" to value.implementationKey(),
        "loader_token" to value.loaderToken(), "output_root_key" to value.outputRootKey(), "job_root_key" to value.jobRootKey(),
        "diagnostics" to value.diagnostics().map { mapOf("severity" to it.severity(), "id" to it.diagnosticId(), "message" to it.message(),
            "path" to it.path(), "line" to it.line(), "column" to it.column()) },
        "per_job_process_exit" to null, "qualification" to "none",
    )

    private fun processFields(value: ProcessOutcome, expected: OwnedSupervisorIdentity?): Map<String, Any?> = mapOf(
        "status" to value.status, "reasons" to value.reasons, "worker_os_exit_code" to value.exitCode,
        "execution_confirmed" to value.executionConfirmed, "successful_supervised_execution" to value.successfulExecution,
        "actual_supervisor" to value.supervisorIdentity?.let(CompilerFixtureProvenance::supervisorIdentityFields),
        "expected_supervisor" to expected?.let(CompilerFixtureProvenance::supervisorIdentityFields),
        "command" to value.command, "output_directory" to value.outputDirectory?.toString(), "timed_out" to value.timedOut,
        "cancelled" to value.cancelled, "truncated" to value.truncated, "runner_elapsed_millis" to value.metrics.elapsedMillis,
        "terminal_scope" to "entire_generation_only_not_individual_compiler_jobs",
    )
}

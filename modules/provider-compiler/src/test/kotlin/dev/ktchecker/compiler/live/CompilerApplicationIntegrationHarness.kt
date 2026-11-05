package dev.ktchecker.compiler.live

import dev.ktchecker.compiler.CompilerRuleRegistry
import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import dev.ktchecker.providers.runtime.OwnedApplicationOwner
import dev.ktchecker.providers.runtime.OwnedApplicationCleanupDisposition
import dev.ktchecker.providers.runtime.OwnedApplicationRetirement
import dev.ktchecker.workerprotocol.ApplicationWire as Wire
import dev.ktchecker.workerprotocol.CompilerJobWire as CompilerWire
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess
import kotlinx.serialization.json.*

/**
 * Explicitly opt-in, test-side SAME-CORE integration entry. No ordinary @Test invokes runOnce.
 * The only execution route is actual KotlinJvmCompilerApplication.open + provider.analyze.
 * No launcher, process factory, fake session, internal compile call or worker-main bypass exists.
 */
object CompilerApplicationIntegrationHarness {
    @JvmStatic fun main(args: Array<String>) {
        // No filesystem reads or preparation before runOnce's original deadline.
        val exit = try {
            harnessCheck(args.size == 5, HarnessCode.INGRESS)
            val ingress = CompilerApplicationHarnessIngress(args[1], args[2],
                args[3].toLongOrNull() ?: throw HarnessProblem(HarnessCode.INGRESS),
                args[4].toLongOrNull() ?: throw HarnessProblem(HarnessCode.INGRESS))
            val consent = CompilerApplicationHarnessConsent.confirm(ingress, args[0])
            val report = runOnce(consent, ingress)
            println("compiler_application_harness verified=${report.verified} qualification=none retained_root=${report.retainedRoot ?: "uncreated"} failures=${report.failureCodes.joinToString(",")}")
            if (report.verified) 0 else 2
        } catch (failure: Throwable) {
            println("compiler_application_harness not_run_or_unresolved code=${harnessCode(failure).name.lowercase()}")
            2
        }
        exitProcess(exit)
    }

    fun runOnce(consent: CompilerApplicationHarnessConsent, ingress: CompilerApplicationHarnessIngress,
        cancellation: CancellationToken = CancellationToken.NONE): CompilerApplicationHarnessReport {
        consent.consume(ingress)
        // FIRST ingress clock: before manifest IO, hashing, runtime-image capture, preparation or HELLO.
        val generationDeadline = OwnedApplicationDeadline.afterMillis(ingress.generationMillis)
        val clock = HarnessClock(generationDeadline, cancellation)
        val first = HarnessAttemptOwner(1, generationDeadline) // The SAME original object, not remaining-time arithmetic.
        var second: HarnessAttemptOwner? = null // No source2 bytes/input/root/deadline/permit yet.
        val failures = linkedSetOf<HarnessCode>()
        val authority = HarnessRetainedAuthority()
        val order = HarnessOrder()
        var rootPath: Path? = null
        var journal: HarnessJournal? = null
        var closure: HarnessClosure? = null
        var owned: HarnessOwnedGeneration? = null
        var request: CompilerGenerationRequest? = null
        var generationPermit: CompilerGenerationPermit? = null
        var opened: CompilerApplicationOpenResult? = null
        var application: KotlinJvmCompilerApplication? = null
        var provider: KotlinJvmLiveCompilerProvider? = null
        var retirement: OwnedApplicationRetirement? = null
        var retirementEntered = false

        fun report(): CompilerApplicationHarnessReport = CompilerApplicationHarnessReport(ingress, rootPath,
            generationDeadline, closure?.installation, request, generationPermit, opened, application, provider,
            listOf(first.evidence(), second?.evidence() ?: unresolvedSecond()), retirement, retirementEntered,
            failures, journal?.healthy == true)
        fun checkpoint() = clock.check()
        fun record(event: String, value: JsonElement) { checkNotNull(journal).append(event, value, ::checkpoint) }
        fun terminalRecord(event: String, value: JsonElement) {
            // Retirement remains mandatory even when journaling or the original request has failed.
            if (journal?.healthy == true) try { journal?.append(event, value) } catch (failure: Throwable) { failures += harnessCode(failure) }
        }

        try {
            checkpoint()
            val admittedClosure = HarnessClosure.capture(ingress, ::checkpoint)
            closure = admittedClosure
            // Parent registration precedes any creation; rootPath is retained immediately on return.
            admittedClosure.parent.verify(); checkpoint()
            rootPath = Files.createTempDirectory(admittedClosure.parent.path, "compiler-application-harness-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            val retained = checkNotNull(rootPath)
            admittedClosure.parent.verify(); checkpoint()
            val runRoot = HarnessRootPin.capture(retained, expectedPermissions = "rwx------")
            journal = HarnessJournal.create(retained.resolve("evidence"))
            record("ingress", buildJsonObject {
                put("schema", "ktchecker/compiler-application-harness-start/1")
                put("qualification", "none"); put("manifest_digest", ingress.manifestDigest)
                put("original_generation_deadline", deadlineJson(generationDeadline))
                put("elapsed_clock_origin", "post_original_deadline_object_construction_observation_only_not_budget_authority")
                put("first_request_uses_same_deadline_object", first.deadline === generationDeadline)
                put("second_obligation", "planned_but_no_source_input_root_deadline_or_permit_constructed")
                put("root", runRoot.json()); put("manifest", admittedClosure.manifest.document)
            })
            record("exact-installation-closure", admittedClosure.installationJson(::checkpoint))
            record("generation-prepare-intent", buildJsonObject { put("root", runRoot.json()); put("elapsed_millis", clock.elapsedMillis()) })
            val owner = OwnedApplicationOwner()
            val generation = HarnessOwnedGeneration.prepare(runRoot, admittedClosure, owner, ::checkpoint)
            owned = generation
            val retainedRequest = CompilerGenerationRequest(owner, admittedClosure.manifest.namespace, admittedClosure.installation,
                generation.launch, generationDeadline)
            request = retainedRequest
            val retainedGrant = CompilerGenerationPermit(retainedRequest,
                verifyBeforeStart = { checkpoint(); admittedClosure.verify(::checkpoint); generation.verify(::checkpoint); checkpoint() },
                verifyLive = { checkpoint(); admittedClosure.verify(::checkpoint); generation.verify(::checkpoint); checkpoint() })
            generationPermit = retainedGrant
            authority.retainGeneration(retainedRequest, retainedGrant)
            record("owner-generation-retained", launchJson(retainedRequest))

            val mapping = CompilerRuleRegistry.load().byUpstreamId["UNRESOLVED_REFERENCE"]
                ?: throw HarnessProblem(HarnessCode.RESULT_PROFILE)
            harnessCheck(mapping.jvmApplicable, HarnessCode.RESULT_PROFILE)
            checkpoint()
            first.capture(generation, retainedRequest, mapping.ruleId,
                "package fixtures.unresolved_reference\nfun broken() = absentSymbol()\n".toByteArray(Charsets.UTF_8), clock)
            record("first-original-captured", first.originalJson())
            // Durable intent BEFORE the only possibly-starting call. No automatic retry if it fails.
            record("application-open-intent", buildJsonObject {
                put("launch", launchJson(retainedRequest)); put("elapsed_millis", clock.elapsedMillis())
            })
            val actualOpen = KotlinJvmCompilerApplication.open(retainedRequest, authority, cancellation)
            opened = actualOpen // Preserve the actual public return, including unavailable cleanup.
            val app = when (actualOpen) {
                is CompilerApplicationOpenResult.Ready -> actualOpen.application.also { application = it }
                is CompilerApplicationOpenResult.Unavailable -> {
                    retirement = actualOpen.cleanup
                    record("application-open-unavailable", buildJsonObject {
                        put("reasons", stringsJson(actualOpen.reasons)); put("cleanup", retirementJson(actualOpen.cleanup))
                    })
                    throw HarnessProblem(HarnessCode.OPEN_UNAVAILABLE)
                }
            }
            record("application-open-returned-ready", helloJson(app.hello))
            CompilerWire.matchHello(retainedRequest.launch, retainedRequest.generation, app.hello)
            checkpoint()
            val actualProvider = KotlinJvmLiveCompilerProvider(app, authority)
            provider = actualProvider

            fun analyzeOne(attempt: HarnessAttemptOwner) {
                clock.check(attempt.deadline)
                order.begin(attempt.sequence)
                record("attempt-${attempt.sequence}-permit-capture-intent", attempt.originalJson())
                val input = checkNotNull(attempt.input)
                val retainedAttempt = CompilerAttemptPermit(owner, actualProvider, app, input, attempt.deadline,
                    verifyCapturedInput = { attempt.verifyOriginal(clock) },
                    registerMaterialization = { value ->
                        // Retain THIS public object even if an owner check below rejects it.
                        attempt.register(value, generation, retainedRequest, clock)
                        record("attempt-${attempt.sequence}-materialization", materializationJson(value))
                    },
                    verifyMaterialization = { value -> attempt.verifyMaterialization(value, generation, retainedRequest, clock) })
                attempt.permit = retainedAttempt // Owner retains it BEFORE analyze/authority lookup.
                harnessCheck(retainedAttempt.original == input && retainedAttempt.originalDeadline === attempt.deadline, HarnessCode.ATTEMPT_BINDING)
                authority.retainAttempt(retainedAttempt)
                record("attempt-${attempt.sequence}-analyze-intent", buildJsonObject {
                    put("sequence", attempt.sequence); put("original_deadline", deadlineJson(attempt.deadline))
                    put("original", ContractJson.encodeToJsonElement(input)); put("permit_original", ContractJson.encodeToJsonElement(retainedAttempt.original))
                    put("elapsed_millis", clock.elapsedMillis())
                })
                clock.check(attempt.deadline)
                attempt.entered = true
                // The only job execution call. No substitute backend or separate wire request.
                val actualResult = actualProvider.analyze(input, cancellation)
                attempt.result = actualResult // Unchanged reference FIRST, before any late harness failure.
                order.returned(attempt.sequence)
                authority.releaseAttempt(retainedAttempt)
                record("attempt-${attempt.sequence}-analyze-returned", buildJsonObject {
                    put("sequence", attempt.sequence); put("provider_result", ContractJson.encodeToJsonElement(actualResult))
                    put("elapsed_millis", clock.elapsedMillis())
                })
                clock.check(attempt.deadline)
                attempt.verifyAfterReturn(generation, retainedRequest, clock)
                verifyHarnessPublicProfile(attempt.sequence, input, actualResult, mapping)
                admittedClosure.verify { clock.check(attempt.deadline) }
                clock.check(attempt.deadline)
                attempt.profileVerified = true
                order.passed(attempt.sequence)
                record("attempt-${attempt.sequence}-public-profile-verified", buildJsonObject {
                    put("sequence", attempt.sequence); put("qualification", "none")
                    put("scope", "public provider result and owner materialization; not raw runtime acceptance")
                    put("retained_output", attempt.output?.json() ?: JsonNull)
                })
                clock.check(attempt.deadline)
            }

            analyzeOne(first) // Must RETURN a real result and pass every first gate.
            harnessCheck(order.mayConstructSecond() && first.result != null && first.profileVerified, HarnessCode.RESULT_PROFILE)
            checkpoint()
            // SECOND original clock: created NOW, before source2 bytes, snapshot, input or permit.
            val secondDeadline = OwnedApplicationDeadline.afterMillis(ingress.secondRequestMillis)
            val later = HarnessAttemptOwner(2, secondDeadline)
            second = later
            record("second-ingress-after-real-first-result", buildJsonObject {
                put("original_deadline", deadlineJson(secondDeadline)); put("first_provider_call_returned", true)
                put("first_public_profile_verified", first.profileVerified); put("source2_not_yet_constructed", true)
            })
            clock.check(secondDeadline)
            // There is no prequeued job list. This byte array is first constructed only here.
            val secondBytes = "package fixtures.unresolved_reference\nfun absentSymbol() = 1\nfun broken() = absentSymbol()\n".toByteArray(Charsets.UTF_8)
            later.capture(generation, retainedRequest, mapping.ruleId, secondBytes, clock)
            harnessCheck(later.input?.snapshot?.files?.single()?.digest != first.input?.snapshot?.files?.single()?.digest &&
                later.source?.file?.key != first.source?.file?.key, HarnessCode.ALIAS)
            record("second-original-captured", later.originalJson())
            analyzeOne(later) // SAME actual app/provider objects as the first call.
            checkpoint()
        } catch (failure: Throwable) {
            failures += harnessCode(failure)
        } finally {
            authority.denyFurtherAdmission()
            val app = application
            if (app != null) {
                var cancelled = false
                fun cancelAfterFailure() {
                    if (failures.isNotEmpty() && !cancelled) {
                        cancelled = true
                        // Do not let optional journal IO delay failure cancellation.
                        try { app.cancel() } catch (_: Throwable) { failures += HarnessCode.CUSTODY }
                        terminalRecord("application-cancel-called", buildJsonObject {
                            put("harness_failures", stringsJson(failures.map { it.name.lowercase() }))
                        })
                    }
                }
                cancelAfterFailure()
                terminalRecord("application-retire-intent", buildJsonObject {
                    put("scope", "native generation custody only"); put("prior_failures", stringsJson(failures.map { it.name.lowercase() }))
                })
                if (failures.isEmpty()) try { checkpoint() } catch (failure: Throwable) { failures += harnessCode(failure) }
                cancelAfterFailure() // Also cover an intent-write/deadline failure; never cancel twice.
                retirementEntered = true
                try {
                    retirement = app.retire() // Exactly one facade retirement; no outer kill/supervisor/deletion.
                    if (retirement?.disposition != OwnedApplicationCleanupDisposition.CLOSED) failures += HarnessCode.CUSTODY
                } catch (_: Throwable) { failures += HarnessCode.CUSTODY }
            } else {
                try { generationPermit?.revoke() } catch (_: Throwable) { failures += HarnessCode.CUSTODY }
            }
            terminalRecord("native-custody-observed", buildJsonObject {
                put("retire_call_entered", retirementEntered); put("actual_cleanup", retirementJson(retirement))
                put("roots_deleted", false); put("no_automatic_cleanup_even_if_closed", true)
            })
        }
        if (failures.isEmpty() && retirement?.disposition == OwnedApplicationCleanupDisposition.CLOSED) {
            try {
                // Conservative retained-root recheck after actual native retirement, still under the
                // original generation clock. No new budget and no cleanup/delete permission.
                owned?.verify(::checkpoint); closure?.verify(::checkpoint); checkpoint()
            } catch (failure: Throwable) { failures += harnessCode(failure) }
        }
        // A late journal failure cannot remove actual returned results or turn them into Accepted.
        // This candidate summary is not a process-exit receipt; root also checks main's final status.
        val summary = JsonObject(report().json() + mapOf(
            "record_kind" to JsonPrimitive("candidate_owner_summary_not_process_completion")))
        if (failures.isEmpty()) {
            try {
                record("owner-summary", summary)
                checkpoint() // A successful summary write also stays inside the original generation clock.
            } catch (failure: Throwable) { failures += harnessCode(failure) }
        } else terminalRecord("owner-summary", summary) // Failure/custody evidence only, no renewed success budget.
        // Keep all references reachable in the returned owner report, all roots retained on disk.
        return report()
    }
}

private fun unresolvedSecond(): CompilerApplicationHarnessAttempt = CompilerApplicationHarnessAttempt(2, null, null,
    null, null, false, null, false, null)

/** Capability lookup only. Neither callback constructs a clock/grant nor runs a renderer. */
private class HarnessRetainedAuthority : KotlinJvmApplicationAuthority {
    private val live = AtomicBoolean(true)
    private var generation: CompilerGenerationRequest? = null
    private var grant: CompilerGenerationPermit? = null
    private var attempt: CompilerAttemptPermit? = null
    fun retainGeneration(request: CompilerGenerationRequest, permit: CompilerGenerationPermit) {
        harnessCheck(live.get() && generation == null && permit.request === request, HarnessCode.ATTEMPT_BINDING)
        generation = request; grant = permit
    }
    fun retainAttempt(value: CompilerAttemptPermit) {
        harnessCheck(live.get() && attempt == null, HarnessCode.ATTEMPT_BINDING)
        attempt = value
    }
    fun releaseAttempt(value: CompilerAttemptPermit) {
        harnessCheck(attempt === value, HarnessCode.ATTEMPT_BINDING); attempt = null
    }
    fun denyFurtherAdmission() { live.set(false) }
    override fun authorizeGeneration(request: CompilerGenerationRequest): CompilerGenerationPermit? =
        if (live.get() && request === generation) grant else null
    override fun authorizeAttempt(provider: KotlinJvmLiveCompilerProvider, request: CompilerAttemptRequest): CompilerAttemptPermit? {
        val value = attempt ?: return null
        return if (live.get() && provider === value.provider && request.application === value.application &&
            request.input == value.original && request.analysisKey == value.originalKey) value else null
    }
}

private class HarnessSource(val root: HarnessRootPin, val file: HarnessFilePin) {
    fun verify(checkpoint: () -> Unit) {
        checkpoint(); root.verify()
        harnessCheck(harnessNames(root.path, 1, checkpoint) == setOf("Fixture.kt"), HarnessCode.MEMBER_DRIFT)
        harnessCheck(Files.getPosixFilePermissions(root.path, NOFOLLOW_LINKS) == PosixFilePermissions.fromString("r-x------") &&
            Files.getPosixFilePermissions(file.path, NOFOLLOW_LINKS) == PosixFilePermissions.fromString("r--------"), HarnessCode.FILE_DRIFT)
        file.verify(checkpoint); root.verify(); checkpoint()
    }
}

private class HarnessOwnedGeneration private constructor(val runRoot: HarnessRootPin, val snapshots: HarnessRootPin,
    val root: HarnessRootPin, private val compilerRoot: HarnessRootPin, private val jobs: HarnessRootPin, private val tmp: HarnessRootPin,
    val launch: Wire.Launch, val staticFiles: List<HarnessFilePin>) {
    private val sources = ArrayList<HarnessSource>()
    private val registered = ArrayList<HarnessAttemptOwner>()
    private val keys = (listOf(runRoot, snapshots, root, compilerRoot, jobs, tmp).map { it.root.fileKey() } + staticFiles.map { it.key }).toMutableSet()
    fun registerSource(source: HarnessSource) {
        harnessCheck(sources.size < 2 && keys.add(source.root.root.fileKey()) && keys.add(source.file.key), HarnessCode.ALIAS)
        sources += source
    }
    fun registerAttempt(value: HarnessAttemptOwner, roots: List<HarnessRootPin>, source: HarnessFilePin) {
        harnessCheck(registered.size == value.sequence - 1 && roots.all { keys.add(it.root.fileKey()) } && keys.add(source.key), HarnessCode.ALIAS)
        registered += value
    }
    fun verify(checkpoint: () -> Unit) {
        checkpoint(); listOf(runRoot, snapshots, root, compilerRoot, jobs, tmp).forEach { it.verify() }
        harnessCheck(harnessNames(runRoot.path, 3, checkpoint) == setOf("evidence", "generation", "snapshots") &&
            harnessNames(root.path, 5, checkpoint) == setOf("bridge.jar", "launch.bin", "compiler", "jobs", "tmp") &&
            harnessNames(compilerRoot.path, CompilerWire.MAX_ARTIFACTS, checkpoint) == (0 until staticFiles.size - 2).map { "$it.jar" }.toSet() &&
            harnessNames(snapshots.path, 2, checkpoint) == (1..sources.size).map(Int::toString).toSet(), HarnessCode.MEMBER_DRIFT)
        staticFiles.forEach { it.verify(checkpoint) }; sources.forEach { it.verify(checkpoint) }
        // Host core owns exact active-job membership during CREATE_NEW/register. This owner check
        // additionally forbids foreign sequence names and revalidates every retained known root.
        harnessCheck(harnessNames(jobs.path, 2, checkpoint).all { it == "1" || it == "2" }, HarnessCode.MEMBER_DRIFT)
        registered.forEach { it.verifyRetained(checkpoint) }
        HarnessTreePin.capture(tmp.path, CompilerWire.MAX_TEMP_FILES, CompilerWire.MAX_TEMP_BYTES, HarnessTreeUse.TEMP, checkpoint)
        checkpoint()
    }
    companion object {
        fun prepare(runRoot: HarnessRootPin, closure: HarnessClosure, owner: OwnedApplicationOwner, checkpoint: () -> Unit): HarnessOwnedGeneration {
            checkpoint(); runRoot.verify()
            val snapshotsPath = runRoot.path.resolve("snapshots"); privateHarnessDirectory(snapshotsPath)
            val generationPath = runRoot.path.resolve("generation"); privateHarnessDirectory(generationPath)
            listOf("compiler", "jobs", "tmp").forEach { checkpoint(); privateHarnessDirectory(generationPath.resolve(it)) }
            val root = HarnessRootPin.capture(generationPath, expectedPermissions = "rwx------")
            val copies = closure.compiler.mapIndexed { index, file -> copyHarnessFile(file, generationPath.resolve("compiler/$index.jar"), checkpoint) }
            val bridgeCopy = copyHarnessFile(closure.bridge, generationPath.resolve("bridge.jar"), checkpoint)
            val admitted = closure.manifest
            val wireGeneration = CompilerWire.Generation(CompilerWire.PROFILE, CompilerWire.API, CompilerWire.COMPILER_VERSION, 17,
                CompilerWire.Jdk(closure.jdkRoot.root, admitted.javaExecutable.toString(), admitted.executableDigest, admitted.runtimeDigest, 21),
                CompilerWire.Artifact(admitted.bridge.coordinate, admitted.bridge.path.fileName.toString(), bridgeCopy.digest, bridgeCopy.size, bridgeCopy.key),
                copies.mapIndexed { index, file -> CompilerWire.Artifact(admitted.compiler[index].coordinate,
                    admitted.compiler[index].path.fileName.toString(), file.digest, file.size, file.key) }, admitted.dependencies)
            val identity = Wire.Identity(UUID.randomUUID().toString().replace("-", ""), owner.identity, admitted.namespace.digest,
                admitted.implementationDigest, bridgeCopy.digest, CompilerWire.compilerClosureDigest(wireGeneration),
                CompilerWire.toolchainDigest(wireGeneration), CompilerWire.API, CompilerWire.PROFILE)
            val launch = Wire.Launch(identity, Wire.Limits(2, 1_048_576, 1_048_576), root.root, root.path.toString(),
                CompilerWire.command(root.root, wireGeneration), CompilerWire.environment(root.root, wireGeneration), CompilerWire.encodeGeneration(wireGeneration))
            val encoded = Wire.encodeLaunch(launch)
            val launchFile = generationPath.resolve("launch.bin")
            checkpoint()
            Files.newOutputStream(launchFile, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { it.write(encoded) }
            Files.setPosixFilePermissions(launchFile, PosixFilePermissions.fromString("r--------"))
            val launchPin = HarnessFilePin.capture(launchFile, encoded.size.toLong(), Wire.sha256(encoded), checkpoint)
            val result = HarnessOwnedGeneration(runRoot, HarnessRootPin.capture(snapshotsPath, expectedPermissions = "rwx------"), root,
                HarnessRootPin.capture(generationPath.resolve("compiler"), expectedPermissions = "rwx------"),
                HarnessRootPin.capture(generationPath.resolve("jobs"), expectedPermissions = "rwx------"),
                HarnessRootPin.capture(generationPath.resolve("tmp"), expectedPermissions = "rwx------"), launch, harnessList(copies + bridgeCopy + launchPin))
            harnessCheck(harnessNames(generationPath.resolve("jobs"), 0, checkpoint).isEmpty() &&
                result.staticFiles.map { it.key }.distinct().size == result.staticFiles.size, HarnessCode.ALIAS)
            result.verify(checkpoint); closure.verify(checkpoint)
            return result
        }
    }
}

private class HarnessAttemptOwner(val sequence: Int, val deadline: OwnedApplicationDeadline) {
    var input: AnalysisInput? = null
        private set
    var source: HarnessSource? = null
        private set
    var permit: CompilerAttemptPermit? = null
    private var materialization: CompilerJobMaterialization? = null
    private var materializedRoots: List<HarnessRootPin> = emptyList()
    private var materializedSource: HarnessFilePin? = null
    private var lastCheckedOutput: HarnessTreePin? = null
    var output: HarnessTreePin? = null
        private set
    var entered = false
    var result: ProviderResult? = null
    var profileVerified = false

    fun evidence(): CompilerApplicationHarnessAttempt = CompilerApplicationHarnessAttempt(sequence, deadline, input, permit,
        materialization, entered, result, profileVerified, output?.json(), originalJson())
    fun originalJson(): JsonObject = buildJsonObject {
        put("sequence", sequence); put("original_deadline", deadlineJson(deadline))
        put("input", input?.let { ContractJson.encodeToJsonElement(it) } ?: JsonNull)
        put("root", source?.root?.json() ?: JsonNull); put("file", source?.file?.json() ?: JsonNull)
    }
    fun capture(generation: HarnessOwnedGeneration, request: CompilerGenerationRequest, ruleId: String, bytes: ByteArray, clock: HarnessClock) {
        fun check() = clock.check(deadline)
        check(); harnessCheck(input == null && source == null && bytes.size in 1..CompilerWire.MAX_SOURCE_BYTES, HarnessCode.ATTEMPT_BINDING)
        val snapshotPath = generation.snapshots.path.resolve(sequence.toString())
        privateHarnessDirectory(snapshotPath)
        val file = snapshotPath.resolve("Fixture.kt")
        check()
        Files.newOutputStream(file, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { it.write(bytes) }
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"))
        Files.setPosixFilePermissions(snapshotPath, PosixFilePermissions.fromString("r-x------"))
        val digest = Digests.sha256(bytes)
        val captured = HarnessSource(HarnessRootPin.capture(snapshotPath, expectedPermissions = "r-x------"),
            HarnessFilePin.capture(file, bytes.size.toLong(), digest, ::check))
        source = captured
        generation.registerSource(captured)
        val fileDescriptor = SnapshotFile("Fixture.kt", digest, bytes.size.toLong())
        val snapshotId = Wire.digest("ktchecker/compiler-application-harness-snapshot/1", Digests.canonical(buildJsonObject {
            put("namespace", request.namespace.digest); put("sequence", sequence); put("file", ContractJson.encodeToJsonElement(fileDescriptor))
        }).toByteArray(Charsets.UTF_8))
        input = AnalysisInput(SnapshotManifest(snapshotId, request.namespace.workspaceId, request.namespace.worktreeId,
            listOf(fileDescriptor), request.namespace.ownerId, declaredRoots = listOf(".")), snapshotPath.toString(),
            AnalysisContext(RuleContext("jvm", "main", ":compiler-application-harness", "main"), CompilerWire.COMPILER_VERSION,
                request.dependencyClasspath, CompilerWire.M0_OPTIONS, dependencyDigest = request.dependencyDigest,
                toolchainDigest = request.installation.digest, configurationDigest = request.configurationDigest, compilationFiles = listOf("Fixture.kt")),
            requiredRuleIds = listOf(ruleId), changedPaths = listOf("Fixture.kt"))
        verifyOriginal(clock); generation.verify(::check)
    }
    fun verifyOriginal(clock: HarnessClock) {
        fun check() = clock.check(deadline)
        check()
        val original = input ?: throw HarnessProblem(HarnessCode.ATTEMPT_BINDING)
        val captured = source ?: throw HarnessProblem(HarnessCode.ATTEMPT_BINDING)
        captured.verify(::check)
        harnessCheck(original.materializedRoot == captured.root.path.toString() && original.snapshot.files.single().let {
            it.path == "Fixture.kt" && it.digest == captured.file.digest && it.size == captured.file.size
        }, HarnessCode.ATTEMPT_BINDING)
        check()
    }
    fun register(value: CompilerJobMaterialization, generation: HarnessOwnedGeneration, request: CompilerGenerationRequest, clock: HarnessClock) {
        harnessCheck(materialization == null, HarnessCode.MATERIALIZATION)
        materialization = value
        fun check() = clock.check(deadline)
        verifyRelation(value, generation, request)
        val roots = listOf(value.jobRoot, value.sourceRoot, value.outputRoot).map {
            HarnessRootPin.capture(harnessPath(it.path()), it.fileKey(), "rwx------")
        }
        val sourceFile = HarnessFilePin.capture(harnessPath(value.sourceRoot.path()).resolve("Fixture.kt"),
            value.sourceIdentity.size(), value.sourceIdentity.digest(), ::check)
        harnessCheck(sourceFile.key == value.sourceIdentity.fileKey() && sourceFile.key != source?.file?.key &&
            harnessNames(harnessPath(value.outputRoot.path()), 0, ::check).isEmpty(), HarnessCode.MATERIALIZATION)
        materializedRoots = roots; materializedSource = sourceFile
        generation.registerAttempt(this, roots, sourceFile)
        verifyMaterialization(value, generation, request, clock)
    }
    private fun verifyRelation(value: CompilerJobMaterialization, generation: HarnessOwnedGeneration, request: CompilerGenerationRequest) {
        val original = checkNotNull(input)
        val expectedJob = generation.root.path.resolve("jobs/$sequence")
        val expectedSource = expectedJob.resolve("sources")
        val expectedOutput = expectedJob.resolve("classes")
        val expectedEffective = original.copy(materializedRoot = expectedSource.toString(), outputRoot = expectedOutput.toString(),
            context = original.context.copy(classpath = original.context.classpath.mapIndexed { index, artifact ->
                artifact.copy(path = generation.root.path.resolve("compiler/${request.generation.dependencyIndexes()[index]}.jar").toString())
            }))
        harnessCheck(value === materialization && value.original === permit?.original && value.original == original &&
            value.effective == expectedEffective && value.snapshotRoot == source?.root?.root &&
            value.jobRoot.path() == expectedJob.toString() && value.sourceRoot.path() == expectedSource.toString() &&
            value.outputRoot.path() == expectedOutput.toString() && value.sourceIdentity.logicalPath() == "Fixture.kt" &&
            value.sourceIdentity.digest() == source?.file?.digest && value.sourceIdentity.size() == source?.file?.size,
            HarnessCode.MATERIALIZATION)
    }
    fun verifyMaterialization(value: CompilerJobMaterialization, generation: HarnessOwnedGeneration, request: CompilerGenerationRequest, clock: HarnessClock) {
        fun check() = clock.check(deadline)
        check(); verifyRelation(value, generation, request); verifyOriginal(clock); verifyRetained(::check)
        lastCheckedOutput = HarnessTreePin.capture(harnessPath(value.outputRoot.path()), CompilerWire.MAX_JOB_OUTPUT_FILES,
            CompilerWire.MAX_JOB_OUTPUT_BYTES, HarnessTreeUse.OUTPUT, ::check)
        check()
    }
    fun verifyRetained(checkpoint: () -> Unit) {
        checkpoint(); materializedRoots.forEach { it.verify() }; materializedSource?.verify(checkpoint)
        materialization?.let { value ->
            harnessCheck(harnessNames(harnessPath(value.jobRoot.path()), 2, checkpoint) == setOf("sources", "classes") &&
                harnessNames(harnessPath(value.sourceRoot.path()), 1, checkpoint) == setOf("Fixture.kt"), HarnessCode.MEMBER_DRIFT)
        }
        output?.verify(checkpoint); checkpoint()
    }
    fun verifyAfterReturn(generation: HarnessOwnedGeneration, request: CompilerGenerationRequest, clock: HarnessClock) {
        fun check() = clock.check(deadline)
        check()
        val value = materialization ?: throw HarnessProblem(HarnessCode.MATERIALIZATION)
        verifyRelation(value, generation, request); verifyOriginal(clock); verifyRetained(::check)
        val previous = lastCheckedOutput ?: throw HarnessProblem(HarnessCode.MATERIALIZATION)
        previous.verify(::check) // Last in-core owner check must agree with the retained post-return tree.
        output = previous
        if (sequence == 2) harnessCheck(previous.entries.any { !it.directory && it.relative ==
            "fixtures/unresolved_reference/FixtureKt.class" }, HarnessCode.RESULT_PROFILE)
        generation.verify(::check); check()
    }
}

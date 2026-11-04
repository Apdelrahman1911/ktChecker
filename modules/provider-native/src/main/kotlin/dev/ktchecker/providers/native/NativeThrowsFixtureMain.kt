package dev.ktchecker.providers.native

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.json.*
import kotlin.system.exitProcess

/** Separate opt-in authored fixture; the historical three-case Native driver is unchanged. */
object NativeThrowsFixtureMain {
    @JvmStatic fun main(args: Array<String>) {
        val options = try { NativeThrowsFixtureCases.parseOptions(args) } catch (_: Exception) {
            println("""{"state":"unavailable","code":"native_throws_arguments_invalid","releaseQualified":false}""")
            exitProcess(1)
        }
        val receipts = mutableListOf<NativeThrowsProcessReceipt>()
        var snapshotId = ""
        var artifacts = emptyList<ArtifactIdentity>()
        var sourceManifestDigest: String? = null
        var binding: FixtureModelBinding? = null
        var observedTasks = false
        var assertionFailures = emptyList<String>()
        val envelope = try {
            run(options, receipts, onSnapshot = { snapshotId = it }, onArtifacts = { artifacts = it },
                onSourceManifest = { sourceManifestDigest = it }, onModel = { binding = it }, onAssertions = {
                    observedTasks = it.nativeTasksObserved
                    assertionFailures = it.failures
                })
        } catch (error: Exception) {
            val failure = fixtureFailureEnvelope("kotlin-native", NativeThrowsFixtureCases.VERSION, options.getValue("case"), options.getValue("target"),
                snapshotId, artifacts, error)
            failure.copy(providerResult = failure.providerResult.copy(coverage = listOf(Coverage(
                NativeThrowsFixtureCases.ruleForCase(options.getValue("case")),
                binding?.scope ?: RuleContext(options.getValue("target"), "iosMain", ":", "main"), "unavailable",
                reason = failure.providerResult.reasons.firstOrNull(), depth = "build", implemented = true))))
        }
        val document = buildJsonObject {
            ContractJson.encodeToJsonElement(envelope).jsonObject.forEach { (key, value) -> put(key, value) }
            put("cohortId", NativeThrowsFixtureCases.COHORT)
            put("releaseQualified", false)
            put("targetQualificationEstablished", false)
            put("requestedRuleIds", ContractJson.encodeToJsonElement(listOf(NativeThrowsFixtureCases.ruleForCase(options.getValue("case")))))
            put("sourceManifestDigest", sourceManifestDigest?.let(::JsonPrimitive) ?: JsonNull)
            put("modelBinding", binding?.let { ContractJson.encodeToJsonElement(it) } ?: JsonNull)
            put("processReceipts", ContractJson.encodeToJsonElement(receipts))
            put("assertionFailures", ContractJson.encodeToJsonElement(assertionFailures))
            put("providerProcessExecutionConfirmed", receipts.singleOrNull { it.phase == "provider" }?.executionConfirmed == true)
            put("nativeCompilationTaskExecutionObserved", observedTasks)
            put("processConfirmationScope", "Selected executable start only; required Native task proof is separate")
        }
        try { writePrivateResult(Path.of(options.getValue("result")), document) } catch (_: Exception) {
            println("""{"state":"failed","code":"native_throws_private_result_io_failed","releaseQualified":false}""")
            exitProcess(1)
        }
        // Never expose private compiler output, source excerpts, paths or exception transcripts.
        println(buildJsonObject {
            put("state", envelope.state)
            put("cohortId", NativeThrowsFixtureCases.COHORT)
            put("case", options.getValue("case"))
            put("target", options.getValue("target"))
            put("releaseQualified", false)
            put("nativeCompilationTaskExecutionObserved", observedTasks)
        })
        if (envelope.state in setOf("failed", "unavailable")) exitProcess(1)
    }

    private fun run(
        options: Map<String, String>,
        receipts: MutableList<NativeThrowsProcessReceipt>,
        onSnapshot: (String) -> Unit,
        onArtifacts: (List<ArtifactIdentity>) -> Unit,
        onSourceManifest: (String) -> Unit,
        onModel: (FixtureModelBinding) -> Unit,
        onAssertions: (NativeThrowsFixtureAssertions) -> Unit,
    ): FixtureCheckEnvelope {
        val fixture = Path.of(options.getValue("fixture")).toAbsolutePath().toRealPath()
        val result = Path.of(options.getValue("result")).toAbsolutePath().normalize()
        var ancestor = result.parent ?: fixtureProblem("native_throws_result_parent_unavailable")
        val missing = mutableListOf<String>()
        while (!Files.exists(ancestor, NOFOLLOW_LINKS)) {
            missing += ancestor.fileName.toString()
            ancestor = ancestor.parent ?: fixtureProblem("native_throws_result_parent_unavailable")
        }
        val actualParent = missing.asReversed().fold(ancestor.toRealPath()) { path, part -> path.resolve(part) }
        if (actualParent.resolve(result.fileName).startsWith(fixture)) fixtureProblem("native_throws_result_source_overlap")
        val work = Files.createTempDirectory("ktchecker-native-throws-")
        val frozen = FixtureSnapshots.materialize(fixture, work)
        onSnapshot(frozen.manifest.snapshotId)
        val cohort = NativeThrowsFixtureCases.load(frozen)
        onSourceManifest(NativeThrowsFixtureCases.SOURCE_MANIFEST_DIGEST)
        val case = cohort.cases.single { it.name == options.getValue("case") }
        val target = options.getValue("target")
        val binding = NativeThrowsFixtureCases.modelBinding(frozen.manifest, case, target)
        onModel(binding)
        val scope = binding.scope
        val blank = AnalysisInput(frozen.manifest, frozen.root.toString(),
            AnalysisContext(scope, compilerVersion = NativeThrowsFixtureCases.VERSION), listOf(case.ruleId),
            outputRoot = work.resolve("outputs").toString())
        val host = System.getProperty("os.name").lowercase()
        if (!(host.contains("mac") || host.contains("darwin"))) {
            return FixtureCheckEnvelope(state = "unavailable", case = case.name, target = target, snapshotId = frozen.manifest.snapshotId,
                providerResult = KotlinNativeProvider(plan = NativeRunPlan(target, emptyList())).analyze(blank),
                assertions = listOf("Authored fixture frozen; no Apple-host Native execution and no JVM substitution."), modelBinding = binding)
        }
        val selectedSupervisor = OwnedProcessContainment.inspect()
        val inspected = selectedSupervisor.identity?.let(NativeThrowsFixtureCases::supervisor)
            ?: fixtureProblem("native_throws_bundled_supervisor_unavailable")
        if (!selectedSupervisor.available || !NativeThrowsFixtureCases.validSupervisor(inspected))
            fixtureProblem("native_throws_bundled_supervisor_unavailable")
        val projectJava = fixtureProjectJava(options.getValue("java-home"))
        onArtifacts(projectJava.artifacts)
        val java = projectJava.home.resolve("bin/java").toRealPath()
        val javaExecution = TrustedExecution(java.toString(), listOf("-version"), true, fileDigest(java),
            timeoutMillis = 60_000, maxOutputBytes = 65_536, environment = projectJava.environment(emptyList()))
        val javaInput = blank.copy(execution = javaExecution)
        val javaAuthority = ExactExecutionAuthority(ExecutionPermit(javaExecution, frozen.manifest.snapshotId, frozen.root,
            javaInput.analysisKey(), { frozen.verify(); projectJava.verifyArtifacts() }))
        val javaResult = runPhase("jdk-version", javaInput, javaAuthority, receipts)
        if (!confirmed(javaResult, inspected) || javaResult.exitCode != 0 ||
            !NativeThrowsFixtureCases.acceptsJdkVersion(javaResult.stdout + "\n" + javaResult.stderr))
            fixtureProblem("native_throws_project_jdk25_unverified")
        val nativeHome = Path.of(options.getValue("native-home")).toAbsolutePath().toRealPath()
        val compilerArtifacts = nativeArtifacts(nativeHome, target)
        onArtifacts(compilerArtifacts + projectJava.artifacts)
        val konanc = nativeHome.resolve("bin/konanc").toRealPath()
        val nativeExecution = TrustedExecution(konanc.toString(), listOf("-version"), true, fileDigest(konanc),
            timeoutMillis = 60_000, maxOutputBytes = 1_048_576, environment = projectJava.environment(nativeEnvironmentKeys))
        val nativeInput = blank.copy(execution = nativeExecution)
        val nativeAuthority = ExactExecutionAuthority(ExecutionPermit(nativeExecution, frozen.manifest.snapshotId, frozen.root,
            nativeInput.analysisKey(), { frozen.verify(); projectJava.verifyArtifacts(); verifyArtifacts(compilerArtifacts) }))
        val nativeResult = runPhase("native-version", nativeInput, nativeAuthority, receipts)
        if (!confirmed(nativeResult, inspected) || nativeResult.exitCode != 0 ||
            !NativeThrowsFixtureCases.acceptsNativeVersion(nativeResult.stdout + "\n" + nativeResult.stderr))
            fixtureProblem("native_throws_compiler_version_unverified")
        val gradle = Path.of(options.getValue("gradle")).toAbsolutePath().toRealPath()
        val tasks = binding.requiredTaskPaths.toSet()
        val execution = TrustedExecution(gradle.toString(), gradleFixtureArguments(binding.requiredTaskPaths, case.name) + listOf(
            "--offline", "-Dorg.gradle.java.home=${projectJava.home}", "-Pkotlin.native.home=$nativeHome",
            "-Porg.gradle.java.installations.auto-download=false", "-Porg.gradle.java.installations.auto-detect=false",
            "-Porg.gradle.java.installations.paths=${projectJava.home}"),
            true, fileDigest(gradle), timeoutMillis = 3_600_000, maxOutputBytes = 8_388_608,
            environment = projectJava.environment(nativeEnvironmentKeys))
        val artifacts = compilerArtifacts + gradleArtifacts(gradle) + projectJava.artifacts
        onArtifacts(artifacts)
        val toolchainDigest = Digests.sha256(Digests.canonical(ContractJson.encodeToJsonElement(artifacts)))
        val context = AnalysisContext(scope, compilerVersion = NativeThrowsFixtureCases.VERSION, classpath = artifacts,
            compilerArguments = NativeThrowsFixtureCases.compilerArguments(case),
            libraries = mapOf("kotlin-stdlib-native" to NativeThrowsFixtureCases.VERSION),
            modelDigest = binding.modelDigest, dependencyDigest = toolchainDigest, toolchainDigest = toolchainDigest,
            configurationDigest = NativeThrowsFixtureCases.configurationDigest(case, target),
            fullProject = true, compilationFiles = binding.sourceSetByFile.keys.sorted())
        val input = blank.copy(context = context, execution = execution)
        val verify = { frozen.verify(); verifyArtifacts(artifacts) }
        val authority = ExactExecutionAuthority(ExecutionPermit(execution, frozen.manifest.snapshotId, frozen.root,
            input.analysisKey(), verify, ::prepareGradleOutput))
        val plan = NativeRunPlan(target, compilerArtifacts, requiredTasks = tasks,
            compilationModel = ExactCompilationModel(scope, binding.modelDigest, binding.sourceSetByFile))
        requireNativeTaskSelection(plan, execution.arguments, scope.module)
        val runner = IsolatedProcessRunner { receipts += NativeThrowsFixtureCases.receipt("provider", input, it) }
        val provider = KotlinNativeProvider(authority, plan, runner).analyze(input)
        val assertions = NativeThrowsFixtureCases.assertions(case, input, provider, receipts, inspected)
        onAssertions(assertions)
        val state = when {
            assertions.passed -> "passed"
            case.expectation == "observation_only" && assertions.processEvidenceConfirmed -> "observed_incomplete"
            provider.status == "unavailable" -> "unavailable"
            else -> "failed"
        }
        return FixtureCheckEnvelope(state = state, case = case.name, target = target, snapshotId = input.snapshot.snapshotId,
            providerResult = provider, artifactIdentities = artifacts, modelBinding = binding,
            assertions = listOf(
                "Exact pinned authored source manifest and immutable materialization verified; unrelated case files are not compilation members.",
                "Actual selected project JDK 25 and Kotlin/Native 2.4.21 probes require execution confirmation and the actual loaded supervisor identity.",
                "Provider process start, required common+iOS task execution and diagnostic/counterpart observations are separate proofs.",
                "Selected rule ${case.ruleId}; ${case.expectation}; all raw diagnostics and gaps remain in the private result.",
                "Fixture assertion success is not rule precision, Native export behavior or target/release qualification.",
            ), limitations = cohort.limitations + listOf(
                "The private process receipts contain commands, local paths, source messages and raw compiler output; do not publish them unsanitized.",
                "This receipt does not attest fresh compilation of driver sources; the outer owner/CI must freeze and bind actual loaded build artifacts.",
            ))
    }

    private fun runPhase(phase: String, input: AnalysisInput, authority: ExecutionAuthority,
                         receipts: MutableList<NativeThrowsProcessReceipt>): ProcessOutcome =
        IsolatedProcessRunner { receipts += NativeThrowsFixtureCases.receipt(phase, input, it) }.run(input, authority)

    private fun confirmed(outcome: ProcessOutcome, inspected: NativeThrowsSupervisorReceipt): Boolean =
        outcome.successfulExecution && outcome.executionConfirmed && !outcome.cancelled && !outcome.timedOut && !outcome.truncated &&
            outcome.supervisorIdentity?.let(NativeThrowsFixtureCases::supervisor) == inspected

    private fun nativeArtifacts(home: Path, target: String): List<ArtifactIdentity> {
        val directories = listOf("bin", "tools", "konan", "klib/common",
            "klib/platform/" + if (target == "iosArm64") "ios_arm64" else "ios_simulator_arm64").map(home::resolve)
        if (directories.any { !Files.isDirectory(it, NOFOLLOW_LINKS) || Files.isSymbolicLink(it) })
            fixtureProblem("native_throws_compiler_artifacts_unavailable")
        val files = directories.flatMap { directory ->
            Files.walk(directory).use { paths -> paths.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) }.map { path ->
                if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path))
                    fixtureProblem("native_throws_compiler_artifacts_unsafe")
                path
            }.toList() }
        }.distinct().sorted()
        if (files.none { it.fileName.toString().endsWith(".jar") } || files.none { it == home.resolve("bin/konanc") } ||
            files.none { it.fileName.toString() == "env_blacklist" }) fixtureProblem("native_throws_compiler_artifacts_unavailable")
        return files.map { ArtifactIdentity("org.jetbrains.kotlin.native:${home.relativize(it).toString().replace('\\', '/')}:2.4.21",
            fileDigest(it), it.toString()) }
    }

    private fun gradleArtifacts(gradle: Path): List<ArtifactIdentity> = listOf(gradle,
        gradle.parent.resolve("gradle/wrapper/gradle-wrapper.jar"), gradle.parent.resolve("gradle/wrapper/gradle-wrapper.properties")).map {
        if (!Files.isRegularFile(it, NOFOLLOW_LINKS) || Files.isSymbolicLink(it)) fixtureProblem("native_throws_gradle_artifacts_unavailable")
        ArtifactIdentity("trusted-gradle:${it.fileName}", fileDigest(it), it.toString())
    }

    private fun verifyArtifacts(artifacts: List<ArtifactIdentity>) {
        artifacts.forEach { if (!Digests.valid(it.digest) || fileDigest(Path.of(it.path)) != it.digest)
            fixtureProblem("native_throws_toolchain_changed") }
    }

    private fun writePrivateResult(path: Path, document: JsonObject) {
        val absolute = path.toAbsolutePath().normalize()
        val parent = absolute.parent ?: fixtureProblem("native_throws_result_parent_unavailable")
        Files.createDirectories(parent, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val permissions = Files.getPosixFilePermissions(parent, NOFOLLOW_LINKS)
        if (!Files.isDirectory(parent, NOFOLLOW_LINKS) || Files.isSymbolicLink(parent) ||
            permissions.any { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") })
            fixtureProblem("native_throws_result_parent_not_private")
        val bytes = ByteBuffer.wrap((document.toString() + "\n").toByteArray(Charsets.UTF_8))
        FileChannel.open(parent.toRealPath().resolve(absolute.fileName), setOf<OpenOption>(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use { channel ->
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
    }

    private fun fixtureProblem(code: String): Nothing = throw ExecutionProblem(code, "Selected Native throws fixture prerequisite is unavailable")
    private val nativeEnvironmentKeys = listOf("GRADLE_USER_HOME", "KONAN_DATA_DIR", "DEVELOPER_DIR", "HOME")
}

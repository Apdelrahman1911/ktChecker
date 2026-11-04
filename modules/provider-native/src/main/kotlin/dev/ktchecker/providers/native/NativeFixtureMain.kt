package dev.ktchecker.providers.native

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.serialization.json.encodeToJsonElement

/** Explicit trusted fixture task only; not a model-facing arbitrary execution endpoint. */
object NativeFixtureMain {
    @JvmStatic fun main(args: Array<String>) {
        val options = parseFixtureArguments(args)
        val resultPath = Path.of(requireNotNull(options["result"]) { "--result is required" }).toAbsolutePath()
        var snapshotId = ""
        var artifactIdentities: List<ArtifactIdentity> = emptyList()
        val envelope = try {
            run(options, onSnapshot = { snapshotId = it }, onArtifacts = { artifactIdentities = it })
        } catch (error: Exception) {
            fixtureFailureEnvelope("kotlin-native", "2.4.21", options["case"] ?: "positive", options["target"] ?: "iosArm64",
                snapshotId, artifactIdentities, error)
        }
        writeFixtureEnvelope(resultPath, envelope)
        check(envelope.state != "failed") { "Native fixture provider assertions failed; see structured result." }
    }

    private fun run(options: Map<String, String>, onSnapshot: (String) -> Unit,
                    onArtifacts: (List<ArtifactIdentity>) -> Unit): FixtureCheckEnvelope {
        val fixture = Path.of(requireNotNull(options["fixture"]) { "--fixture is required" }).toAbsolutePath()
        val target = options["target"] ?: "iosArm64"
        val case = options["case"] ?: "positive"
        require(target in setOf("iosArm64", "iosSimulatorArm64"))
        require(case in setOf("positive", "missing-actual", "wrong-actual"))
        val work = Files.createTempDirectory("ktchecker-native-fixture-")
        val frozen = FixtureSnapshots.materialize(fixture, work)
        onSnapshot(frozen.manifest.snapshotId)
        val scope = RuleContext(target, "commonMain", ":fixture", "main")
        val blank = AnalysisInput(frozen.manifest, frozen.root.toString(), AnalysisContext(scope, compilerVersion = "2.4.21"),
            outputRoot = work.resolve("outputs").toString())
        val host = System.getProperty("os.name").lowercase()
        if (!(host.contains("mac") || host.contains("darwin"))) {
            val unavailable = KotlinNativeProvider(plan = NativeRunPlan(target, emptyList())).analyze(blank)
            return FixtureCheckEnvelope(state = "unavailable", case = case, target = target,
                snapshotId = frozen.manifest.snapshotId, providerResult = unavailable,
                assertions = listOf("No JVM substitution; Apple-host Native execution is explicitly unavailable."))
        }
        val projectJava = fixtureProjectJava(options["java-home"])
        onArtifacts(projectJava.artifacts)
        projectJava.verifyVersion(blank, frozen.verify)
        val nativeHome = nativeHome(options["native-home"])
        val compilerArtifacts = nativeArtifacts(nativeHome, target)
        onArtifacts(compilerArtifacts + projectJava.artifacts)
        val konanc = nativeHome.resolve("bin/konanc").toRealPath()
        val versionExecution = TrustedExecution(konanc.toString(), listOf("-version"), true, fileDigest(konanc),
            timeoutMillis = 60000, maxOutputBytes = 1_048_576, environment = projectJava.environment(nativeEnvironmentKeys))
        val versionInput = blank.copy(execution = versionExecution)
        val versionAuthority = ExactExecutionAuthority(ExecutionPermit(versionExecution, frozen.manifest.snapshotId,
            frozen.root, versionInput.analysisKey(), {
                frozen.verify()
                projectJava.verifyArtifacts()
                compilerArtifacts.forEach { if (fileDigest(Path.of(it.path)) != it.digest)
                    throw ExecutionProblem("native_fixture_toolchain_changed", "Native compiler changed during its version probe") }
            }))
        val versionResult = IsolatedProcessRunner().run(versionInput, versionAuthority)
        if (!(versionResult.successfulExecution && versionResult.exitCode == 0 &&
            Regex("(?:kotlinc-native|Kotlin/Native(?: compiler)?(?: version)?)\\s+2\\.4\\.21(?:\\s|$|\\()", RegexOption.IGNORE_CASE)
                .containsMatchIn(versionResult.stdout + "\n" + versionResult.stderr))) {
            throw ExecutionProblem("native_compiler_version_probe_failed", "Actual selected Native compiler did not verify Kotlin/Native 2.4.21")
        }
        val gradle = Path.of(requireNotNull(options["gradle"]) { "--gradle is required on Apple hosts" }).toAbsolutePath().toRealPath()
        val targetTask = if (target == "iosArm64") "compileKotlinIosArm64" else "compileKotlinIosSimulatorArm64"
        val execution = TrustedExecution(gradle.toString(), gradleFixtureArguments(listOf("compileCommonMainKotlinMetadata", targetTask), case) +
            listOf("-Dorg.gradle.java.home=${projectJava.home}", "-Pkotlin.native.home=$nativeHome"),
            true, fileDigest(gradle), timeoutMillis = 3_600_000, maxOutputBytes = 8_388_608,
            environment = projectJava.environment(nativeEnvironmentKeys))
        val artifacts = compilerArtifacts + gradleArtifacts(gradle) + projectJava.artifacts
        onArtifacts(artifacts)
        val toolchain = Digests.sha256(ContractJson.encodeToJsonElement(artifacts).toString())
        val context = AnalysisContext(scope, compilerVersion = "2.4.21", classpath = artifacts,
            compilerArguments = listOf("-Xrender-internal-diagnostic-names", "actual Native target $target"),
            libraries = mapOf("kotlin-stdlib-native" to "2.4.21"),
            modelDigest = Digests.sha256("fixture-native-model/v1|${frozen.manifest.snapshotId}|$target|$case"),
            dependencyDigest = toolchain, toolchainDigest = toolchain,
            configurationDigest = Digests.sha256("fixture-native-policy/v1|$case"), fullProject = true,
            compilationFiles = frozen.manifest.files.map { it.path }.filter { it.endsWith(".kt") && (
                it.startsWith("src/commonMain/") ||
                (case == "positive" && it.startsWith("src/iosMain/")) ||
                (case == "wrong-actual" && it.startsWith("negative/wrong-actual/iosMain/")) ||
                it.startsWith("src/${target}Main/")) })
        val input = blank.copy(context = context, execution = execution)
        val verify = {
            frozen.verify()
            artifacts.forEach { artifact ->
                if (fileDigest(Path.of(artifact.path)) != artifact.digest) {
                    throw ExecutionProblem("native_fixture_toolchain_changed", "Selected fixture toolchain artifact changed")
                }
            }
        }
        val authority = ExactExecutionAuthority(ExecutionPermit(execution, input.snapshot.snapshotId, frozen.root,
            input.analysisKey(), verify, ::prepareGradleOutput))
        val plan = NativeRunPlan(target, compilerArtifacts,
            sourceSets = mapOf("src/commonMain/" to "commonMain", "src/iosMain/" to "iosMain",
                "negative/wrong-actual/iosMain/" to "iosMain"))
        val provider = KotlinNativeProvider(authority, plan).analyze(input)
        val diagnosed = provider.findings.filter { it.severity == "error" }
        val expected = when (case) {
            "missing-actual" -> setOf("NO_ACTUAL_FOR_EXPECT")
            "wrong-actual" -> setOf("ACTUAL_WITHOUT_EXPECT", "EXPECT_ACTUAL_INCOMPATIBLE_RETURN_TYPE")
            else -> emptySet()
        }
        val assertions = mutableListOf("Actual Kotlin/Native 2.4.21 version probe bound to fingerprinted compiler artifacts.",
            "Native target is $target, not JVM.", "Immutable fixture materialization verified before and after execution.",
            "Project JDK 25 selected explicitly, pinned and version-probed; Gradle uses that JAVA_HOME.",
            "Gradle kotlin.native.home is explicitly the same distribution as the Native version probe.")
        val taskGap = provider.reasons.any { it.startsWith("native_task_not_executed:") }
        val executed = provider.coverage.isNotEmpty() && provider.coverage.all { it.provenance == "executed" }
        val passed = provider.status == "incomplete" && executed && !taskGap && provider.rawDiagnostics.isEmpty() && when (case) {
            "positive" -> diagnosed.isEmpty() && provider.coverage.all { it.certificateId != null }
            else -> diagnosed.any { it.upstreamId in expected }
        }
        assertions += if (case == "positive") "Positive expects actual common+iOS compile tasks and no source-error diagnosis."
            else "Negative expects an individually mapped expect/actual source-error diagnosis from the actual target task."
        assertions += "Per-ID Apple precision/corpus qualification remains false for all 344 mappings."
        return FixtureCheckEnvelope(state = if (passed) "passed" else if (provider.status == "unavailable") "unavailable" else "failed", case = case,
            target = target, snapshotId = input.snapshot.snapshotId, providerResult = provider,
            artifactIdentities = artifacts, assertions = assertions)
    }

    private fun nativeHome(explicit: String?): Path {
        if (explicit != null && explicit.isNotBlank()) return Path.of(explicit).toAbsolutePath().toRealPath()
        val base = Path.of(System.getenv("KONAN_DATA_DIR") ?: Path.of(System.getProperty("user.home"), ".konan").toString())
        val homes = if (Files.isDirectory(base)) Files.list(base).use { files -> files.filter {
            Files.isDirectory(it, NOFOLLOW_LINKS) && it.fileName.toString().startsWith("kotlin-native-prebuilt-macos-") &&
                it.fileName.toString().endsWith("-2.4.21")
        }.toList() } else emptyList()
        require(homes.size == 1) { "Select the actual provisioned Kotlin/Native 2.4.21 distribution with --native-home" }
        return homes.single().toRealPath()
    }

    private fun nativeArtifacts(home: Path, target: String): List<ArtifactIdentity> {
        val dirs = listOf(home.resolve("bin"), home.resolve("konan"), home.resolve("klib/common"),
            home.resolve("klib/platform/" + if (target == "iosArm64") "ios_arm64" else "ios_simulator_arm64"))
        val files = dirs.flatMap { directory ->
            if (!Files.isDirectory(directory)) emptyList() else Files.walk(directory).use { paths ->
                paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.toList()
            }
        }.distinct().sorted()
        require(files.any { it.fileName.toString().endsWith(".jar") } && files.any { it.fileName.toString() == "konanc" }) {
            "Native compiler implementation artifacts are unavailable"
        }
        return files.map { ArtifactIdentity("org.jetbrains.kotlin.native:${home.relativize(it).toString().replace('\\', '/')}:2.4.21",
            fileDigest(it), it.toString()) }
    }

    private fun gradleArtifacts(gradle: Path): List<ArtifactIdentity> = (listOf(gradle) + listOf(
        gradle.parent.resolve("gradle/wrapper/gradle-wrapper.jar"), gradle.parent.resolve("gradle/wrapper/gradle-wrapper.properties"))
        .filter { Files.isRegularFile(it) }).map {
            ArtifactIdentity("trusted-gradle:${it.fileName}", fileDigest(it), it.toString())
        }

    private val nativeEnvironmentKeys = listOf("ANDROID_HOME", "ANDROID_SDK_ROOT", "GRADLE_USER_HOME", "KONAN_DATA_DIR", "DEVELOPER_DIR", "HOME")
}

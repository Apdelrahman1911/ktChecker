package dev.ktchecker.providers.lint

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.encodeToJsonElement

/** Real version-isolated Lint fixture driver, invoked only by explicitly selected trusted task. */
object LintFixtureMain {
    @JvmStatic fun main(args: Array<String>) {
        val options = parseFixtureArguments(args)
        val resultPath = Path.of(requireNotNull(options["result"]) { "--result is required" }).toAbsolutePath()
        var snapshotId = ""
        var artifactIdentities: List<ArtifactIdentity> = emptyList()
        val envelope = try {
            run(options, onSnapshot = { snapshotId = it }, onArtifacts = { artifactIdentities = it })
        } catch (error: Exception) {
            fixtureFailureEnvelope("android-lint", "32.3.1", options["case"] ?: "positive", "android",
                snapshotId, artifactIdentities, error)
        }
        writeFixtureEnvelope(resultPath, envelope)
        check(envelope.state != "failed") { "Lint fixture provider assertions failed; inspect structured provider result." }
    }

    private fun run(options: Map<String, String>, onSnapshot: (String) -> Unit,
                    onArtifacts: (List<ArtifactIdentity>) -> Unit): FixtureCheckEnvelope {
        val fixture = Path.of(requireNotNull(options["fixture"])).toAbsolutePath().toRealPath()
        val case = options["case"] ?: "positive"
        val classpath = requireNotNull(options["lint-classpath"]).split(File.pathSeparator).map { Path.of(it).toRealPath() }
        val kmp = Files.exists(fixture.resolve("settings.gradle.kts"))
        require(case in if (kmp) setOf("positive", "new-api") else setOf("positive", "negative", "suppression"))
        val work = Files.createTempDirectory("ktchecker-lint-fixture-")
        val selectedFixture = if (kmp) fixture else fixture.resolve(case)
        val frozen = FixtureSnapshots.materialize(selectedFixture, work)
        onSnapshot(frozen.manifest.snapshotId)
        val projectJava = if (kmp) fixtureProjectJava(options["java-home"]) else null
        projectJava?.verifyVersion(AnalysisInput(frozen.manifest, frozen.root.toString(),
            outputRoot = work.resolve("outputs").toString()), frozen.verify)
        val artifacts = classpath.map { ArtifactIdentity("isolated-lint-runtime:${it.fileName}", fileDigest(it), it.toString()) }
        val checks = classpath.single { it.fileName.toString() == "lint-checks-32.3.1.jar" }
        val registry = LintRegistryArtifact("com.android.tools.lint.checks.BuiltinIssueRegistry",
            ArtifactIdentity("com.android.tools.lint:lint-checks:32.3.1", fileDigest(checks), checks.toString()))
        val selected = if (kmp) setOf("ktc.android.lint.NewApi")
            else setOf("ktc.android.lint.ContentDescription", "ktc.android.lint.HardcodedText")
        val executable = if (kmp) Path.of(requireNotNull(options["gradle"])).toAbsolutePath().toRealPath()
            else Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toRealPath()
        val command = if (kmp) gradleFixtureArguments(listOf("lint"), case) +
            listOf("-Dorg.gradle.java.home=${requireNotNull(projectJava).home}") else listOf(
            "-Xmx512m", "-cp", classpath.joinToString(File.pathSeparator), "com.android.tools.lint.Main",
            "--check", "ContentDescription,HardcodedText", "--xml", "{output}/lint-results.xml", "--exitcode", "--fullpath", "--offline", "{snapshot}")
        val allArtifacts = artifacts + registry.artifact + projectJava?.artifacts.orEmpty() +
            listOf(ArtifactIdentity("fixture-lint-executable", fileDigest(executable), executable.toString())) + (if (kmp) listOf(
            executable.parent.resolve("gradle/wrapper/gradle-wrapper.jar"),
            executable.parent.resolve("gradle/wrapper/gradle-wrapper.properties"))
            .filter { Files.isRegularFile(it) }.map { ArtifactIdentity("trusted-gradle:${it.fileName}", fileDigest(it), it.toString()) } else emptyList())
        onArtifacts(allArtifacts)
        val toolchain = Digests.sha256(ContractJson.encodeToJsonElement(allArtifacts).toString())
        val execution = TrustedExecution(executable.toString(), command, true, fileDigest(executable),
            timeoutMillis = if (kmp) 3_600_000 else 120000, maxOutputBytes = 8_388_608,
            environment = projectJava?.environment(lintEnvironmentKeys) ?: lintEnvironment())
        val context = AnalysisContext(RuleContext("android", if (kmp) "androidMain" else "main", ":fixture", if (kmp) "androidMain" else "xml"),
            compilerVersion = if (kmp) "2.4.21" else null, classpath = allArtifacts,
            modelDigest = Digests.sha256("fixture-lint-model/v1|${frozen.manifest.snapshotId}|$case|$kmp"),
            dependencyDigest = toolchain, toolchainDigest = toolchain,
            configurationDigest = Digests.sha256("fixture-lint-policy/v1|$case"), fullProject = true,
            compilationFiles = frozen.manifest.files.map { it.path }.filter { !kmp ||
                it.startsWith("src/androidMain/") || it.startsWith("src/commonMain/") ||
                (case == "new-api" && it.startsWith("negative/new-api/androidMain/")) })
        val input = AnalysisInput(frozen.manifest, frozen.root.toString(), context, selected.toList(),
            execution = execution, outputRoot = work.resolve("outputs").toString())
        val verify = {
            frozen.verify()
            allArtifacts.forEach {
                if (fileDigest(Path.of(it.path)) != it.digest) throw ExecutionProblem("lint_fixture_toolchain_changed", "Pinned fixture Lint toolchain changed")
            }
        }
        val authority = ExactExecutionAuthority(ExecutionPermit(execution, input.snapshot.snapshotId, frozen.root,
            input.analysisKey(), verify, if (kmp) ::prepareGradleOutput else { _: Path -> }))
        val plan = LintRunPlan("32.3.1", listOf(registry), selected,
            reportPath = if (kmp) "build/reports/lint-results.xml" else "lint-results.xml",
            sourceSets = if (kmp) mapOf("src/androidMain/" to "androidMain", "negative/new-api/androidMain/" to "androidMain") else emptyMap(),
            requiredTaskPrefixes = if (kmp) setOf("lint") else emptySet())
        var attempt: ProcessOutcome? = null
        val provider = AndroidLintProvider(authority, plan, IsolatedProcessRunner { attempt = it }).analyze(input)
        val expected = if (kmp) setOf("NewApi") else setOf("ContentDescription", "HardcodedText")
        val diagnosed = provider.findings.mapNotNull { it.upstreamId }.toSet()
        val expectFindings = case == "new-api" || (!kmp && case == "positive")
        val passed = provider.status == "incomplete" && provider.rawDiagnostics.isEmpty() &&
            provider.coverage.all { it.provenance == "executed" && it.certificateId != null && !it.qualified } &&
            (if (expectFindings) diagnosed == expected else diagnosed.isEmpty())
        return FixtureCheckEnvelope(state = if (passed) "passed" else if (provider.status == "unavailable") "unavailable" else "failed",
            case = case, target = "android", snapshotId = input.snapshot.snapshotId, providerResult = provider,
            artifactIdentities = allArtifacts,
            assertions = listOf("Actual maintained Lint32.3.1 (client9.3.1), isolated from the broker/compiler classpath.",
                "Immutable source materialization and selected provider binary verified before and after execution.",
                "Explicit fixture expectation: " + if (expectFindings) expected.joinToString() else "no selected IDs",
                "Execution output digest: " + Digests.sha256((attempt?.stdout ?: "") + "\n" + (attempt?.stderr ?: "")),
                if (kmp) "Project JDK 25 explicitly selected, pinned and version-probed; broker JVM is not the project toolchain." else "Standalone XML controls use the pinned isolated Lint JVM.",
                "No per-ID corpus/precision qualification or full inventory release coverage claimed."))
    }

    private val lintEnvironmentKeys = listOf("JAVA_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "GRADLE_USER_HOME", "HOME", "DEVELOPER_DIR")
    private fun lintEnvironment(): Map<String, String> = lintEnvironmentKeys.mapNotNull { key -> System.getenv(key)?.let { key to it } }.toMap()
}

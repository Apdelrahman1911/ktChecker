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
        var capturedAttempt: Pair<AnalysisInput, ProcessOutcome>? = null
        val envelope = try {
            run(options, onSnapshot = { snapshotId = it }, onArtifacts = { artifactIdentities = it },
                onAttempt = { input, outcome -> capturedAttempt = input to outcome })
        } catch (error: Exception) {
            fixtureFailureEnvelope("android-lint", "32.3.1", options["case"] ?: "positive", "android",
                snapshotId, artifactIdentities, error)
        }
        writeFixtureEnvelope(resultPath, envelope)
        // Explicit private diagnostics only: never append process output to the
        // public envelope/console, and never turn a diagnostic into authority.
        options["private-attempt-directory"]?.let {
            try {
                LintFixturePrivateAttempt.retain(Path.of(it), Path.of(requireNotNull(options["fixture"])),
                    snapshotId, capturedAttempt?.first, capturedAttempt?.second)
            } catch (error: ExecutionProblem) { throw error }
            catch (_: Exception) {
                // Even invalid option paths must not escape through an InvalidPathException.
                throw ExecutionProblem("lint_private_attempt_write_failed", "Requested private diagnostics could not be retained")
            }
        }
        check(envelope.state != "failed") { "Lint fixture provider assertions failed; inspect structured provider result." }
    }

    private fun run(options: Map<String, String>, onSnapshot: (String) -> Unit,
                    onArtifacts: (List<ArtifactIdentity>) -> Unit,
                    onAttempt: (AnalysisInput, ProcessOutcome) -> Unit): FixtureCheckEnvelope {
        val fixture = Path.of(requireNotNull(options["fixture"])).toAbsolutePath().toRealPath()
        val case = options["case"] ?: "positive"
        val classpath = requireNotNull(options["lint-classpath"]).split(File.pathSeparator).map { Path.of(it).toRealPath() }
        val kmp = Files.exists(fixture.resolve("settings.gradle.kts"))
        require(case in if (kmp) setOf("positive", "new-api") else setOf("positive", "negative", "suppression"))
        val work = Files.createTempDirectory("ktchecker-lint-fixture-")
        val selectedFixture = if (kmp) fixture else fixture.resolve(case)
        val frozen = FixtureSnapshots.materialize(selectedFixture, work)
        onSnapshot(frozen.manifest.snapshotId)
        val requiredTasks = if (kmp) setOf(
            options["lint-analysis-task"] ?: throw ExecutionProblem("lint_fixture_exact_tasks_required", "Select the actual qualified Android variant analysis task"),
            options["lint-report-task"] ?: throw ExecutionProblem("lint_fixture_exact_tasks_required", "Select the actual qualified Android variant report task"),
        ).also { if (it.size != 2) throw ExecutionProblem("lint_fixture_exact_tasks_required", "Distinct Android analysis and report model task paths are required") }
        else emptySet()
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
        val command = if (kmp) gradleFixtureArguments(requiredTasks.toList(), case) +
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
        val scope = RuleContext("android", if (kmp) "androidMain" else "main", if (kmp) ":" else ":fixture", if (kmp) "androidMain" else "xml")
        // Fixed fixture producer declarations only; the production adapter receives exact files,
        // never this directory-prefix enumeration or an inferred arbitrary project model.
        val declaredRoots = mapOf("src/androidMain/kotlin/" to "androidMain", "src/commonMain/kotlin/" to "commonMain") +
            (if (case == "new-api") mapOf("negative/new-api/androidMain/" to "androidMain") else emptyMap())
        val sourceSetByFile = if (kmp) frozen.manifest.files.filter { it.path.endsWith(".kt") }.mapNotNull { file ->
            declaredRoots.entries.singleOrNull { file.path.startsWith(it.key) }?.let { file.path to it.value }
        }.toMap() else frozen.manifest.files.associate { it.path to "main" }
        val modelDigest = Digests.sha256("fixture-lint-model/v2\n${frozen.manifest.snapshotId}\n${scope.id}\n$case\n" +
            Digests.canonical(ContractJson.encodeToJsonElement(sourceSetByFile)) + "\n" + requiredTasks.sorted().joinToString("\n"))
        val compilationModel = ExactCompilationModel(scope, modelDigest, sourceSetByFile)
        val context = AnalysisContext(scope,
            compilerVersion = if (kmp) "2.4.21" else null, classpath = allArtifacts,
            modelDigest = modelDigest,
            dependencyDigest = toolchain, toolchainDigest = toolchain,
            configurationDigest = Digests.sha256("fixture-lint-policy/v1|$case"), fullProject = true,
            compilationFiles = sourceSetByFile.keys.sorted())
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
            compilationModel = compilationModel, mode = if (kmp) "gradle" else "standalone", requiredTaskPaths = requiredTasks)
        var attempt: ProcessOutcome? = null
        val provider = AndroidLintProvider(authority, plan, IsolatedProcessRunner {
            attempt = it
            onAttempt(input, it)
        }).analyze(input)
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
                "Exact per-file source-set membership and model scope bind the explicit fixture producer model, not a general project importer.",
                "No per-ID corpus/precision qualification or full inventory release coverage claimed."),
            modelBinding = FixtureModelBinding(scope, modelDigest, sourceSetByFile, requiredTasks.sorted()))
    }

    private val lintEnvironmentKeys = listOf("JAVA_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "GRADLE_USER_HOME", "HOME", "DEVELOPER_DIR")
    private fun lintEnvironment(): Map<String, String> = lintEnvironmentKeys.mapNotNull { key -> System.getenv(key)?.let { key to it } }.toMap()
}

package dev.ktchecker.providers.runtime

import dev.ktchecker.contracts.*
import dev.ktchecker.snapshot.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

data class FixtureMaterialization(val root: Path, val manifest: SnapshotManifest, val verify: () -> Unit)

/**
 * Test-driver producer overlay, not a mutable-worktree analysis API. These explicitly submitted
 * fixture bytes are frozen into the integrated CAS before execution. It never claims live freshness.
 */
object FixtureSnapshots {
    fun materialize(fixture: Path, work: Path): FixtureMaterialization {
        val source = fixture.toAbsolutePath().normalize().toRealPath()
        if (!Files.isDirectory(source, NOFOLLOW_LINKS)) throw ExecutionProblem("fixture_missing", "Fixture directory is unavailable")
        Files.createDirectories(work)
        val excluded = setOf("build", ".gradle", ".kotlin", ".git", ".idea")
        val changes = Files.walk(source).use { paths ->
            paths.filter { it != source }.sorted().filter { path ->
                source.relativize(path).any { it.toString() in excluded }.not()
            }.filter { path -> !Files.isDirectory(path, NOFOLLOW_LINKS) }.map { path ->
                if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                    throw ExecutionProblem("fixture_entry_unsupported", "Fixture sources must be regular producer-owned files")
                }
                val relative = source.relativize(path).toString().replace(java.io.File.separatorChar, '/')
                SnapshotChange.PutFile(SnapshotPath(path = relative), ImmutableBlob.copyOf(Files.readAllBytes(path)), Files.isExecutable(path))
            }.toList()
        }
        if (changes.isEmpty()) throw ExecutionProblem("fixture_empty", "No fixture producer inputs")
        val identity = SnapshotIdentity("fixture-owner", "platform-fixture", "run-" + UUID.randomUUID())
        val store = SnapshotStore(work.resolve("cas"), identity.ownerId)
        store.registerWorktree(identity)
        val commit = store.sealOverlay(OverlayRequest(identity, "fixture-driver", "fixture-session", "fixture-patch",
            null, 0, changes, scope = SnapshotScope("Explicit fixture producer overlay, not live-current capture",
                excluded.map { CaptureExclusion("project", it, "Generated/cache/VCS directory is outside this declared fixture source scope") })))
        val materialized = store.materialize(identity, commit.snapshotId, work.resolve("snapshot"))
        return FixtureMaterialization(materialized.root, materialized.manifest.toContractManifest()) {
            val verified = store.verifyMaterialization(materialized)
            if (!verified.valid) throw ExecutionProblem("snapshot_materialization_changed", "Registered fixture source materialization was modified")
        }
    }
}

@Serializable data class FixtureCheckEnvelope(
    val schemaVersion: Int = 1,
    val state: String,
    val case: String,
    val target: String,
    val snapshotId: String,
    val providerResult: ProviderResult,
    val artifactIdentities: List<ArtifactIdentity> = emptyList(),
    val assertions: List<String> = emptyList(),
    val limitations: List<String> = listOf("Fixture driver success is not runtime qualification or release coverage.",
        "Same-user permissions and trusted process groups are cooperative isolation, not a security sandbox."),
    val modelBinding: FixtureModelBinding? = null,
)

/** Explicit fixture producer model receipt, not a general project-model import attestation. */
@Serializable data class FixtureModelBinding(
    val scope: RuleContext,
    val modelDigest: String,
    val sourceSetByFile: Map<String, String>,
    val requiredTaskPaths: List<String>,
    val provenance: String = "explicit_fixture_producer_model_not_general_project_import",
)

fun writeFixtureEnvelope(path: Path, envelope: FixtureCheckEnvelope) {
    Files.createDirectories(path.toAbsolutePath().parent)
    val encoded = ContractJson.encodeToString(envelope)
    Files.writeString(path, encoded + "\n")
    println(encoded)
}

/** Fixed, driver-owned Gradle relocation script, written only into this review's unique outputs. */
fun prepareGradleOutput(output: Path) {
    val dir = output.resolve("build").toString().replace('\\', '/').replace("'", "\\'")
    Files.writeString(output.resolve("relocate.gradle"), """
        gradle.beforeProject { p ->
            def suffix = p.path == ':' ? '' : p.path.replace(':', '/')
            p.layout.buildDirectory.set(new File('$dir', suffix))
        }
    """.trimIndent() + "\n")
}

fun gradleFixtureArguments(tasks: List<String>, case: String = "positive"): List<String> = listOf(
    "--no-daemon", "--no-configuration-cache", "--console=plain", "--rerun-tasks", "--max-workers=1",
    "-p", "{snapshot}", "--project-cache-dir", "{output}/project-cache", "--init-script", "{output}/relocate.gradle",
    "-PfixtureBuildDir={output}/build", "-Pkotlin.project.persistent.dir={output}/kotlin-state",
    "-Dkotlin.project.persistent.dir={output}/kotlin-state",
    "-PfixtureNegativeCase=" + if (case == "positive") "none" else case,
) + tasks

fun parseFixtureArguments(args: Array<String>): Map<String, String> {
    if (args.size % 2 != 0 || args.indices.step(2).any { !args[it].startsWith("--") }) {
        throw IllegalArgumentException("Expected explicit --name value fixture arguments")
    }
    if (args.indices.step(2).map { args[it] }.toSet().size != args.size / 2) {
        throw IllegalArgumentException("Duplicate fixture argument")
    }
    return args.indices.step(2).associate { args[it].removePrefix("--") to args[it + 1] }
}

/** Project compiler JDK is explicitly selected; the broker's java.home is not that selection. */
data class FixtureProjectJava(val home: Path, val artifacts: List<ArtifactIdentity>) {
    fun environment(inheritedKeys: List<String>): Map<String, String> = inheritedKeys
        .filter { it != "JAVA_HOME" && it != "PATH" }
        .mapNotNull { key -> System.getenv(key)?.let { key to it } }.toMap() + mapOf(
            "JAVA_HOME" to home.toString(),
            "PATH" to home.resolve("bin").toString() + java.io.File.pathSeparator + (System.getenv("PATH") ?: "/usr/bin:/bin"),
        )

    fun verifyArtifacts() {
        artifacts.forEach {
            if (fileDigest(Path.of(it.path)) != it.digest) throw ExecutionProblem("fixture_project_jdk_changed", "Selected project JDK changed")
        }
    }

    fun verifyVersion(input: AnalysisInput, verifySnapshot: () -> Unit) {
        val executable = home.resolve("bin/java").toRealPath()
        val execution = TrustedExecution(executable.toString(), listOf("-version"), true, fileDigest(executable),
            timeoutMillis = 60000, maxOutputBytes = 65536, environment = environment(emptyList()))
        val probeInput = input.copy(execution = execution)
        val verify = { verifySnapshot(); verifyArtifacts() }
        val authority = ExactExecutionAuthority(ExecutionPermit(execution, input.snapshot.snapshotId,
            Path.of(input.materializedRoot), probeInput.analysisKey(), verify))
        val result = IsolatedProcessRunner().run(probeInput, authority)
        if (!result.successfulExecution || result.exitCode != 0 ||
            !Regex("""(?:openjdk|java) version "25(?:[."+]|-)""").containsMatchIn(result.stdout + "\n" + result.stderr)) {
            throw ExecutionProblem("fixture_project_jdk25_unavailable", "Selected project Java did not verify JDK 25")
        }
    }
}

fun fixtureProjectJava(explicit: String?): FixtureProjectJava {
    val selected = explicit?.takeIf { it.isNotBlank() } ?: System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() }
        ?: throw ExecutionProblem("fixture_project_java_home_required", "Select project JDK 25 using --java-home or active JAVA_HOME")
    val home = try { Path.of(selected).toAbsolutePath().toRealPath() } catch (_: Exception) {
        throw ExecutionProblem("fixture_project_jdk_unavailable", "Selected project JDK is unavailable")
    }
    val runtime = listOf(home.resolve("bin/java"), home.resolve("release"), home.resolve("lib/modules"),
        home.resolve("lib/server/" + if (System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("darwin") }) "libjvm.dylib" else "libjvm.so"))
    if (runtime.any { !Files.isRegularFile(it) }) throw ExecutionProblem("fixture_project_jdk_unavailable", "Complete selected project JDK inputs are required")
    return FixtureProjectJava(home, runtime.map {
        ArtifactIdentity("project-jdk:${home.relativize(it).toString().replace('\\', '/')}", fileDigest(it), it.toString())
    })
}

/** Prerequisite failures are structured gaps even when no snapshot was captured. */
fun fixtureFailureEnvelope(providerId: String, providerVersion: String, case: String, target: String,
                           snapshotId: String, artifacts: List<ArtifactIdentity>, error: Exception): FixtureCheckEnvelope {
    val reason = problemCode(error, "fixture_driver_prerequisite_failed")
    return FixtureCheckEnvelope(state = "unavailable", case = case, target = target, snapshotId = snapshotId,
        providerResult = ProviderResult(providerId, "unavailable", reasons = listOf(reason), providerVersion = providerVersion),
        artifactIdentities = artifacts, assertions = listOf(
            "Fixture prerequisite/driver failure: $reason; no successful execution or qualification claimed.",
            if (snapshotId.isEmpty()) "No source snapshot was captured." else "Source snapshot identity retained; provider attempt did not certify coverage.",
        ))
}

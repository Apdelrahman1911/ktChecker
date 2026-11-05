package dev.ktchecker.cli

import dev.ktchecker.compiler.CompilerSnapshot
import dev.ktchecker.compiler.KotlinJvmToolchain
import dev.ktchecker.contracts.ArtifactIdentity
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import dev.ktchecker.workerprotocol.CompilerModelWire as ModelWire
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Collections
import kotlinx.serialization.json.*

/**
 * Explicit bounded cache-root selection copied BEFORE choosing the compiler installation.
 * This is neither configuration-read closure nor permission to create/download new artifacts.
 * Exact ordered compiler paths are owner input, never learned from either Gradle report.
 */
internal class OwnerModelCacheSeed(
    selectedRoot: Path,
    val runRoot: Path,
    private val originalDeadline: OwnedApplicationDeadline,
) {
    val seed: OwnerModelTreePin
    val userHome: Path = runRoot.resolve("gradle-home")
    private val roots: List<OwnerModelRootPin>
    val copies: Map<String, OwnerModelFilePin>
    val exclusions: List<String>
    private var selection: List<ArtifactIdentity>? = null
    private var boundInstallation: KotlinJvmToolchain? = null

    init {
        checkpoint()
        val selected = ownerModelPath(selectedRoot)
        ownerModelPath(runRoot)
        ownerModelRequire(!selected.startsWith(runRoot) && !runRoot.startsWith(selected), "owner_model_selected_roots_overlap")
        seed = OwnerModelTreePin.capture(selected, 50_000, CompilerSnapshot.MAX_TOTAL_ARTIFACT_BYTES,
            checkpoint = ::checkpoint)
        ownerModelRequire("modules-2" in seed.directories, "owner_model_cache_seed_layout_unsupported")
        val home = ownerModelFreshDirectory(userHome)
        val caches = ownerModelFreshDirectory(userHome.resolve("caches"))
        roots = listOf(home, caches) + seed.directories.keys
            .sortedWith(compareBy<String> { it.count { c -> c == '/' } }.thenBy { it }).map { relative ->
                checkpoint(); ownerModelFreshDirectory(caches.path.resolve(relative))
            }
        // Shared lock ownership is NEVER copied. Every exclusion is retained in the receipt.
        exclusions = Collections.unmodifiableList(seed.files.keys.filter {
            Path.of(it).fileName.toString().endsWith(".lock")
        }.sorted())
        copies = Collections.unmodifiableMap(seed.files.filterKeys { it !in exclusions }.mapValues { (relative, pin) ->
            pin.copyTo(caches.path.resolve(relative), ::checkpoint).also { copied ->
                // Private Gradle metadata can change. That is part of the permanent config-read
                // closure gap, not authority over newly generated code/artifact bytes.
                if (!relative.endsWith(".jar")) Files.setPosixFilePermissions(copied.path,
                    PosixFilePermissions.fromString("rw-------"))
            }
        })
        seed.verify(::checkpoint)
        verifyArtifacts()
    }

    /** A single prelaunch ordered selection; no report-driven fallback or second selection. */
    fun selectCompiler(relativePaths: List<String>): List<ArtifactIdentity> {
        checkpoint()
        ownerModelRequire(selection == null && relativePaths.size in 1..ModelWire.MAX_ARTIFACTS &&
            relativePaths.distinct().size == relativePaths.size, "owner_model_compiler_selection_invalid")
        var bytes = 0L
        val chosen = relativePaths.map { relative ->
            val path = Path.of(relative)
            val parts = relative.split('/')
            ownerModelRequire(!path.isAbsolute && path.normalize() == path && path.toString() == relative &&
                parts.size == 7 && parts.take(2) == listOf("modules-2", "files-2.1") &&
                parts.none { it.isEmpty() || it == "." || it == ".." || it.any { c -> c.code < 32 || c == '\\' } } &&
                parts.slice(2..4).all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) } &&
                parts[5].matches(Regex("[0-9a-f]{40,64}")) &&
                parts.last().matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.jar")),
                "owner_model_compiler_selection_invalid")
            val pin = copies[relative] ?: throw CliProblem("owner_model_compiler_artifact_unregistered",
                "Select each compiler JAR from the exact copied seed before export; the report cannot enroll it.")
            ownerModelRequire(pin.path == userHome.resolve("caches").resolve(path) && pin.size in 1..ModelWire.MAX_ARTIFACT_BYTES &&
                pin.size <= ModelWire.MAX_ARTIFACT_TOTAL_BYTES - bytes, "owner_model_compiler_selection_limit")
            bytes += pin.size
            pin.verify(::checkpoint)
            ArtifactIdentity("${parts[2]}:${parts[3]}:${parts[4]}", pin.digest, pin.path.toString())
        }
        ownerModelRequire(chosen.map { it.coordinate }.distinct().size == chosen.size &&
            chosen.map { Path.of(it.path).fileName.toString() }.distinct().size == chosen.size &&
            chosen.singleOrNull { it.coordinate == "org.jetbrains.kotlin:kotlin-compiler-embeddable:${ModelWire.COMPILER_VERSION}" }
                ?.let { it.digest == ModelWire.COMPILER_SHA256 && Path.of(it.path).fileName.toString() ==
                    "kotlin-compiler-embeddable-${ModelWire.COMPILER_VERSION}.jar" } == true,
            "owner_model_compiler_selection_unpinned")
        return Collections.unmodifiableList(chosen).also { selection = it }
    }

    /** Bind ONCE before runtime/Workbench/export; later identical descriptors cannot replace it. */
    fun bindInstallation(installation: KotlinJvmToolchain, deadline: OwnedApplicationDeadline) {
        checkpoint()
        ownerModelRequire(boundInstallation == null && originalDeadline === deadline && selection != null &&
            selection == installation.compilerClasspath, "owner_model_cache_installation_mismatch")
        boundInstallation = installation
        verifyInstallation(installation, deadline)
    }

    fun verifyInstallation(installation: KotlinJvmToolchain, deadline: OwnedApplicationDeadline) {
        checkpoint()
        ownerModelRequire(boundInstallation === installation && originalDeadline === deadline && selection != null &&
            selection == installation.compilerClasspath, "owner_model_cache_installation_mismatch")
    }

    /** Original shared seed is not touched again after its exact copy receipt is frozen. */
    fun verifyArtifacts() {
        checkpoint()
        roots.forEach { it.verify() }
        copies.filterKeys { it.endsWith(".jar") }.values.forEach { it.verify(::checkpoint) }
        checkpoint()
    }

    fun evidence(): JsonObject = buildJsonObject {
        put("schema", "ktchecker/owner-model-cache-seed/1"); put("seedTree", seed.digest)
        put("fileCount", seed.files.size); put("byteCount", seed.bytes)
        put("seedExclusions", ContractJson.encodeToJsonElement(exclusions))
        put("compilerSelection", selection?.let { ContractJson.encodeToJsonElement(it) } ?: JsonNull)
        put("configurationInputClosureProven", false)
    }

    private fun checkpoint() = ownerModelCheckpoint(originalDeadline)
}

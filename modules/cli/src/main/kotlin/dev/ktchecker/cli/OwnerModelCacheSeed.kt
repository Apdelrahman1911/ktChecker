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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*

/** Immutable ordered artifact DATA, not an installation/model grant or a directory-derived set. */
internal class OwnerModelOrderedCompilerArtifacts(artifacts: List<ArtifactIdentity>) {
    val artifacts: List<ArtifactIdentity> = Collections.unmodifiableList(artifacts.toList())
    fun matches(candidate: List<ArtifactIdentity>): Boolean = artifacts == candidate
}

/**
 * One invocation-specific use of a private artifact installation. Only immutable selected JAR
 * bytes/paths persist across patches; no clock, permit, model, nonce, generation or output does.
 * The owning lineage service serializes this private Gradle home and requires actual previous
 * child-group quiescence plus CLOSED compiler retirement before issuing the next use.
 */
internal class OwnerModelCacheSeed private constructor(
    private val artifacts: OwnerModelCacheArtifacts,
    val runRoot: Path,
    private val clock: OwnerModelCommandClock,
) {
    constructor(selectedRoot: Path, storageRoot: Path, runRoot: Path, clock: OwnerModelCommandClock) :
        this(OwnerModelCacheArtifacts(selectedRoot, storageRoot, clock::checkpoint), runRoot, clock)

    private val originalDeadline: OwnedApplicationDeadline get() = clock.originalDeadline

    val seed: OwnerModelTreePin get() = artifacts.seed
    val userHome: Path get() = artifacts.userHome
    val copies: Map<String, OwnerModelFilePin> get() = artifacts.copies
    val exclusions: List<String> get() = artifacts.exclusions

    fun selectCompiler(relativePaths: List<String>): List<ArtifactIdentity> = artifacts.selectCompiler(relativePaths, ::checkpoint)

    fun bindInstallation(installation: KotlinJvmToolchain, deadline: OwnedApplicationDeadline) {
        checkpoint()
        ownerModelRequire(deadline === originalDeadline, "owner_model_cache_clock_mismatch")
        artifacts.bindInstallation(installation, ::checkpoint)
        verifyInstallation(installation, deadline)
    }

    fun verifyInstallation(installation: KotlinJvmToolchain, deadline: OwnedApplicationDeadline) {
        checkpoint()
        ownerModelRequire(deadline === originalDeadline, "owner_model_cache_clock_mismatch")
        artifacts.verifyInstallation(installation)
    }

    /** Artifact-only re-verification under the NEW invocation's clock; never call the old clock. */
    fun verifyArtifacts() = artifacts.verifyArtifacts(::checkpoint)

    /** Owner-lineage gate ONLY; creating this object cannot enroll execution/model authority. */
    fun nextUse(runRoot: Path, clock: OwnerModelCommandClock, installation: KotlinJvmToolchain): OwnerModelCacheSeed {
        ownerModelRequire(clock.originalDeadline !== originalDeadline && runRoot != this.runRoot, "owner_model_cache_use_replayed")
        return OwnerModelCacheSeed(artifacts, runRoot, clock).also {
            it.verifyInstallation(installation, clock.originalDeadline)
            it.verifyArtifacts()
        }
    }

    fun evidence(): JsonObject = buildJsonObject {
        put("schema", "ktchecker/owner-model-cache-use/1")
        put("originalDeadlineIdentity", originalDeadline.identity)
        put("runRoot", runRoot.toString()); put("artifactSelection", artifacts.evidence())
        put("artifactOnlyReuse", true); put("configurationInputClosureProven", false)
    }

    private fun checkpoint() = clock.checkpoint()
}

/** Persistent artifact custody only. No stored callback/deadline or executable capability. */
private class OwnerModelCacheArtifacts(selectedRoot: Path, storageRoot: Path, checkpoint: () -> Unit) {
    val seed: OwnerModelTreePin
    val userHome: Path = storageRoot.resolve("gradle-home")
    private val roots: List<OwnerModelRootPin>
    val copies: Map<String, OwnerModelFilePin>
    val exclusions: List<String>
    private var selection: OwnerModelOrderedCompilerArtifacts? = null
    private val selectionAttempted = AtomicBoolean()
    private var boundInstallation: KotlinJvmToolchain? = null

    init {
        checkpoint()
        val selected = ownerModelPath(selectedRoot)
        ownerModelPath(storageRoot)
        ownerModelRequire(!selected.startsWith(storageRoot) && !storageRoot.startsWith(selected), "owner_model_selected_roots_overlap")
        seed = OwnerModelTreePin.capture(selected, 50_000, CompilerSnapshot.MAX_TOTAL_ARTIFACT_BYTES, checkpoint = checkpoint)
        ownerModelRequire("modules-2" in seed.directories, "owner_model_cache_seed_layout_unsupported")
        val home = ownerModelFreshDirectory(userHome)
        val caches = ownerModelFreshDirectory(userHome.resolve("caches"))
        roots = listOf(home, caches) + seed.directories.keys
            .sortedWith(compareBy<String> { it.count { c -> c == '/' } }.thenBy { it }).map { relative ->
                checkpoint(); ownerModelFreshDirectory(caches.path.resolve(relative))
            }
        exclusions = Collections.unmodifiableList(seed.files.keys.filter {
            Path.of(it).fileName.toString().endsWith(".lock")
        }.sorted())
        copies = Collections.unmodifiableMap(seed.files.filterKeys { it !in exclusions }.mapValues { (relative, pin) ->
            pin.copyTo(caches.path.resolve(relative), checkpoint).also { copied ->
                // Mutable private Gradle metadata is NOT reused evidence or proven input closure.
                if (!relative.endsWith(".jar")) Files.setPosixFilePermissions(copied.path,
                    PosixFilePermissions.fromString("rw-------"))
            }
        })
        seed.verify(checkpoint)
        verifyArtifacts(checkpoint)
    }

    /** One prelaunch ordered selection for this owner/session; never learned from either report. */
    fun selectCompiler(relativePaths: List<String>, checkpoint: () -> Unit): List<ArtifactIdentity> {
        ownerModelRequire(selectionAttempted.compareAndSet(false, true), "owner_model_compiler_selection_replayed")
        checkpoint()
        val requested = relativePaths.toList()
        ownerModelRequire(selection == null && requested.size in 1..ModelWire.MAX_ARTIFACTS &&
            requested.distinct().size == requested.size, "owner_model_compiler_selection_invalid")
        var bytes = 0L
        val chosen = requested.map { relative ->
            val path = Path.of(relative)
            val parts = relative.split('/')
            ownerModelRequire(!path.isAbsolute && path.normalize() == path && path.toString() == relative &&
                parts.size == 7 && parts.take(2) == listOf("modules-2", "files-2.1") &&
                parts.none { it.isEmpty() || it == "." || it == ".." || it.any { c -> c.code < 32 || c == '\\' } } &&
                parts.slice(2..4).all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) } &&
                parts[5].matches(Regex("[0-9a-f]{40,64}")) &&
                parts.last().matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.jar")), "owner_model_compiler_selection_invalid")
            val pin = copies[relative] ?: throw CliProblem("owner_model_compiler_artifact_unregistered",
                "Select each compiler JAR before export; a report cannot enroll it.")
            ownerModelRequire(pin.path == userHome.resolve("caches").resolve(path) && pin.size in 1..ModelWire.MAX_ARTIFACT_BYTES &&
                pin.size <= ModelWire.MAX_ARTIFACT_TOTAL_BYTES - bytes, "owner_model_compiler_selection_limit")
            bytes += pin.size; pin.verify(checkpoint)
            ArtifactIdentity("${parts[2]}:${parts[3]}:${parts[4]}", pin.digest, pin.path.toString())
        }
        ownerModelRequire(chosen.map { it.coordinate }.distinct().size == chosen.size &&
            chosen.map { Path.of(it.path).fileName.toString() }.distinct().size == chosen.size &&
            chosen.singleOrNull { it.coordinate == "org.jetbrains.kotlin:kotlin-compiler-embeddable:${ModelWire.COMPILER_VERSION}" }
                ?.let { it.digest == ModelWire.COMPILER_SHA256 && Path.of(it.path).fileName.toString() ==
                    "kotlin-compiler-embeddable-${ModelWire.COMPILER_VERSION}.jar" } == true, "owner_model_compiler_selection_unpinned")
        return OwnerModelOrderedCompilerArtifacts(chosen).also { selection = it }.artifacts
    }

    fun bindInstallation(installation: KotlinJvmToolchain, checkpoint: () -> Unit) {
        checkpoint()
        ownerModelRequire(boundInstallation == null && selection?.matches(installation.compilerClasspath) == true,
            "owner_model_cache_installation_mismatch")
        boundInstallation = installation
        verifyInstallation(installation)
    }

    fun verifyInstallation(installation: KotlinJvmToolchain) {
        ownerModelRequire(boundInstallation === installation && selection?.matches(installation.compilerClasspath) == true,
            "owner_model_cache_installation_mismatch")
    }

    /** Original external seed is never reread or copied again after initial frozen installation. */
    fun verifyArtifacts(checkpoint: () -> Unit) {
        checkpoint(); roots.forEach { it.verify() }
        copies.filterKeys { it.endsWith(".jar") }.values.forEach { it.verify(checkpoint) }
        checkpoint()
    }

    fun evidence(): JsonObject = buildJsonObject {
        put("schema", "ktchecker/owner-model-artifact-installation/1"); put("seedTree", seed.digest)
        put("userHome", userHome.toString()); put("fileCount", seed.files.size); put("byteCount", seed.bytes)
        put("seedExclusions", ContractJson.encodeToJsonElement(exclusions))
        put("compilerSelectionAttempted", selectionAttempted.get())
        put("compilerSelection", selection?.let { ContractJson.encodeToJsonElement(it.artifacts) } ?: JsonNull)
        put("configurationInputClosureProven", false)
    }
}

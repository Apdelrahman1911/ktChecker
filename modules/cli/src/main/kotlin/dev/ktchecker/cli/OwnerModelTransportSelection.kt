package dev.ktchecker.cli

import dev.ktchecker.compiler.KotlinJvmToolchain
import dev.ktchecker.contracts.ArtifactIdentity
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import dev.ktchecker.workerprotocol.CompilerModelWire as ModelWire
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Collections
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable internal data class OwnerModelTransportImagePin(val label: String, val path: String, val digest: String, val bytes: Long)
@Serializable internal data class OwnerModelTransportImageSelection(
    val schema: String, val approvalId: String,
    val sourceInputs: List<OwnerModelTransportImagePin>, val installedOutputs: List<OwnerModelTransportImagePin>,
    val controllerOutputs: List<OwnerModelTransportImagePin>, val compilerArtifactPaths: List<String>,
    val node: OwnerModelTransportImagePin, val nodeVersion: String, val codex: OwnerModelTransportImagePin,
    val buildRecord: OwnerModelTransportImagePin, val ownerCodeImageDigest: String,
)
@Serializable internal data class OwnerModelTransportArtifactPair(
    val installedAbsolutePath: String, val cacheRelativePath: String, val digest: String, val bytes: Long,
    val installedIdentity: String, val cacheIdentity: String,
)
@Serializable internal data class OwnerModelTransportSelection(
    val schema: String,
    val attemptId: String,
    val ownerStateDirectory: String,
    val invocationRoot: String,
    val registration: OwnerModelLineageRegistration,
    val invocationSelection: OwnerModelInvocationSelection,
    val distributionHome: String,
    val jvmExecutable: OwnerModelTransportImagePin,
    val jvmClasspath: List<OwnerModelTransportImagePin>,
    val imageSelectionPath: String,
    val imageSelectionDigest: String,
    val ownerCodeImageDigest: String,
    val compilerArtifacts: List<OwnerModelTransportArtifactPair>,
)

/** A typed mapping VERIFIED from explicit selected path pairs before owner installation. It is
 * DATA custody, never an execution/model/receipt grant. No report or directory scan selects it.
 */
internal class OwnerModelTransportPreparedSelection private constructor(
    val selection: OwnerModelTransportSelection,
    val invocation: OwnerModelInvocationSelection,
    private val originalPins: List<OwnerModelFilePin>,
    private val installedPins: List<OwnerModelFilePin>,
    private val seedPins: List<OwnerModelFilePin>,
    private val copied: List<ArtifactIdentity>,
) {
    private var copiedPins: List<OwnerModelFilePin>? = null

    fun verifyInitialCopies(installation: KotlinJvmToolchain, checkpoint: () -> Unit) {
        checkpoint()
        ownerModelRequire(copiedPins == null && installation.compilerClasspath == copied &&
            installation.javaExecutable == Path.of(selection.jvmExecutable.path) &&
            installation.executableDigest == selection.jvmExecutable.digest,
            "owner_transport_copied_toolchain_mismatch")
        val actual = copied.mapIndexed { index, artifact ->
            val pin = OwnerModelFilePin.capture(Path.of(artifact.path), ModelWire.MAX_ARTIFACT_BYTES, checkpoint)
            val pair = selection.compilerArtifacts[index]
            ownerModelRequire(pin.digest == pair.digest && pin.size == pair.bytes &&
                pin.fileKey != installedPins[index].fileKey && pin.fileKey != seedPins[index].fileKey, "owner_transport_copied_artifact_mismatch")
            pin
        }
        // Recheck BOTH original paths after actual copy/installation. This detects replacement;
        // not an ABA theorem or loaded-memory attestation. Later patches never recapture the seed.
        originalPins.forEach { it.verify(checkpoint) }
        copiedPins = Collections.unmodifiableList(actual)
    }
    fun verifyCopiedArtifacts(checkpoint: () -> Unit) {
        val pins = copiedPins ?: throw CliProblem("owner_transport_copy_not_bound", "Actual copied installation is not bound.")
        pins.forEach { it.verify(checkpoint) }
    }
    fun sameInvocation(input: OwnerModelInvocationSelection): Boolean = input == selection.invocationSelection

    companion object {
        fun read(path: Path, digest: String, checkpoint: () -> Unit): OwnerModelTransportPreparedSelection {
            checkpoint(); ownerModelRequire(Digests.valid(digest), "owner_transport_selection_digest")
            val selectedPin = OwnerModelFilePin.capture(ownerModelPath(path), OWNER_MODEL_REQUEST_BYTES.toLong(), checkpoint)
            ownerModelRequire(selectedPin.digest == digest, "owner_transport_selection_changed")
            val input = ownerModelTransportExact<OwnerModelTransportSelection>(ContractJson.parseToJsonElement(strictJsonDocument(
                selectedPin.read(OWNER_MODEL_REQUEST_BYTES, checkpoint), "owner_transport_selection_invalid")))
            val choice = input.copy(jvmClasspath = Collections.unmodifiableList(input.jvmClasspath.toList()),
                compilerArtifacts = Collections.unmodifiableList(input.compilerArtifacts.toList()),
                invocationSelection = input.invocationSelection.copy(
                    compilerArtifactPaths = Collections.unmodifiableList(input.invocationSelection.compilerArtifactPaths.toList())))
            ownerModelRequire(choice.schema == "ktchecker-owner-session-selection/1", "owner_transport_selection_schema")
            ownerModelIdentity(choice.attemptId)
            listOf(choice.registration.workspaceId, choice.registration.worktreeId, choice.registration.clientId,
                choice.registration.sessionId, choice.registration.lineageId).forEach(::ownerModelIdentity)
            val state = ownerModelPath(Path.of(choice.ownerStateDirectory), false)
            val invocationRoot = Path.of(choice.invocationRoot)
            ownerModelRequire(invocationRoot == state.resolve("model-command/runs") && !Files.exists(state, NOFOLLOW_LINKS),
                "owner_transport_fresh_state_required")
            val supplied = choice.invocationSelection
            ownerModelRequire(supplied.authorizeExecution, "owner_transport_execution_not_selected")
            val home = ownerModelPath(Path.of(choice.distributionHome))
            val javaHome = ownerModelPath(Path.of(supplied.compilerJavaHome))
            val seed = ownerModelPath(Path.of(supplied.gradleCacheSeed))
            ownerModelPath(Path.of(supplied.gradleHome))
            ownerModelRequire(Workbench.distributionHome() == home && Path.of(choice.jvmExecutable.path) == javaHome.resolve("bin/java") &&
                Path.of(System.getProperty("java.home")) == javaHome &&
                Path.of(System.getProperty("user.home")) == state.parent.resolve("owner-environment-home") &&
                Path.of(System.getProperty("java.io.tmpdir")) == state.parent.resolve("owner-environment-tmp"), "owner_transport_jvm_image_not_selected")
            ownerModelRequire(choice.jvmClasspath.size in 1..128 && choice.jvmClasspath.map { it.path }.distinct().size == choice.jvmClasspath.size &&
                System.getProperty("java.class.path").split(File.pathSeparator) == choice.jvmClasspath.map { it.path } &&
                choice.jvmClasspath.all { Path.of(it.path).parent == home.resolve("lib") }, "owner_transport_classpath_not_selected")
            val imagePath = ownerModelPath(Path.of(choice.imageSelectionPath))
            val imagePin = OwnerModelFilePin.capture(imagePath, 2_097_152L, checkpoint)
            ownerModelRequire(imagePin.digest == choice.imageSelectionDigest && Digests.valid(choice.ownerCodeImageDigest),
                "owner_transport_image_selection_changed")
            val image = ownerModelTransportExact<OwnerModelTransportImageSelection>(ContractJson.parseToJsonElement(
                strictJsonDocument(imagePin.read(2_097_152, checkpoint), "owner_transport_image_invalid")), 2_097_152)
            ownerModelRequire(image.schema == "ktchecker-current-image-owner-selection/1" &&
                image.ownerCodeImageDigest == choice.ownerCodeImageDigest, "owner_transport_image_selection_mismatch")
            val installed = image.installedOutputs
            ownerModelRequire(installed.size in 1..8192 && installed.map { it.path }.distinct().size == installed.size &&
                installed.map { it.label }.distinct().size == installed.size, "owner_transport_image_pin_domain")
            installed.forEach(::validatePin)
            ownerModelRequire(image.compilerArtifactPaths == supplied.compilerArtifactPaths,
                "owner_transport_image_compiler_order")
            val jvm = listOf(choice.jvmExecutable) + choice.jvmClasspath
            ownerModelRequire(jvm.map { it.path }.distinct().size == jvm.size && jvm.all { it in installed },
                "owner_transport_unselected_jvm_pin")
            val jvmPins = jvm.map { selected ->
                validatePin(selected)
                OwnerModelFilePin.capture(ownerModelPath(Path.of(selected.path)), selected.bytes, checkpoint).also {
                    ownerModelRequire(it.digest == selected.digest && it.size == selected.bytes, "owner_transport_jvm_pin_changed")
                }
            }
            val pairs = choice.compilerArtifacts
            val artifacts = mapCopiedArtifacts(state, supplied.compilerArtifactPaths, pairs, installed)
            val installedPins = mutableListOf<OwnerModelFilePin>()
            val seedPins = mutableListOf<OwnerModelFilePin>()
            pairs.forEach { pair ->
                checkpoint()
                val relative = relativeArtifact(pair.cacheRelativePath)
                val installedPath = ownerModelPath(Path.of(pair.installedAbsolutePath))
                val seedPath = ownerModelPath(seed.resolve(relative))
                ownerModelRequire(seedPath.startsWith(seed) && installedPath != seedPath, "owner_transport_artifact_role_alias")
                val installedPin = captureIdentity(installedPath, pair.installedIdentity, pair, checkpoint)
                val seedPin = captureIdentity(seedPath, pair.cacheIdentity, pair, checkpoint)
                ownerModelRequire(installedPin.fileKey != seedPin.fileKey, "owner_transport_artifact_role_alias")
                installedPins.add(installedPin); seedPins.add(seedPin)
            }
            val inputs = listOf(path, imagePath, home, javaHome, seed, Path.of(supplied.gradleHome)) + jvm.map { Path.of(it.path) } +
                pairs.map { Path.of(it.installedAbsolutePath) }
            ownerModelRequire(inputs.none { it == state || it.startsWith(state) || state.startsWith(it) },
                "owner_transport_input_inside_state")
            val allPins = listOf(selectedPin, imagePin) + jvmPins + installedPins + seedPins
            allPins.forEach { it.verify(checkpoint) }
            return OwnerModelTransportPreparedSelection(choice,
                supplied.copy(compilerArtifactPaths = Collections.unmodifiableList(pairs.map { it.cacheRelativePath })),
                Collections.unmodifiableList(allPins), Collections.unmodifiableList(installedPins), Collections.unmodifiableList(seedPins),
                Collections.unmodifiableList(artifacts))
        }
        /** Pure ordered DATA projection from explicitly selected pairs. The read/open path still
         * independently captures both real file identities/bytes and compares the ACTUAL copied
         * toolchain later; these expected tuples alone cannot establish installation authority. */
        internal fun mapCopiedArtifacts(state: Path, invocationPaths: List<String>, pairs: List<OwnerModelTransportArtifactPair>,
                                        installed: List<OwnerModelTransportImagePin>): List<ArtifactIdentity> {
            ownerModelRequire(state.isAbsolute && state.normalize() == state && state.parent != null &&
                pairs.size in 1..ModelWire.MAX_ARTIFACTS && pairs.map { it.installedAbsolutePath } == invocationPaths &&
                pairs.map { it.installedAbsolutePath }.distinct().size == pairs.size &&
                pairs.map { it.cacheRelativePath }.distinct().size == pairs.size, "owner_transport_artifact_order")
            var bytes = 0L
            val artifacts = pairs.map { pair ->
                val relative = relativeArtifact(pair.cacheRelativePath)
                val parts = pair.cacheRelativePath.split('/')
                val path = Path.of(pair.installedAbsolutePath)
                ownerModelRequire(path.isAbsolute && path.normalize() == path && path.parent != null &&
                    path.fileName.toString() == parts.last(), "owner_transport_artifact_alias_or_name")
                ownerModelRequire(Digests.valid(pair.digest) && pair.bytes in 1..ModelWire.MAX_ARTIFACT_BYTES &&
                    pair.bytes <= ModelWire.MAX_ARTIFACT_TOTAL_BYTES - bytes &&
                    pair.installedIdentity.matches(Regex("[0-9]{1,20}:[0-9]{1,20}")) &&
                    pair.cacheIdentity.matches(Regex("[0-9]{1,20}:[0-9]{1,20}")) && pair.installedIdentity != pair.cacheIdentity,
                    "owner_transport_artifact_pin_invalid")
                bytes += pair.bytes
                ownerModelRequire(installed.singleOrNull { it.path == pair.installedAbsolutePath }?.let {
                    it.digest == pair.digest && it.bytes == pair.bytes
                } == true, "owner_transport_artifact_not_in_image")
                ArtifactIdentity("${parts[2]}:${parts[3]}:${parts[4]}", pair.digest,
                    state.resolve("model-command/artifacts/gradle-home/caches").resolve(relative).toString())
            }
            ownerModelRequire(artifacts.map { it.coordinate }.distinct().size == artifacts.size &&
                artifacts.map { Path.of(it.path).fileName.toString() }.distinct().size == artifacts.size,
                "owner_transport_artifact_coordinate_alias")
            return Collections.unmodifiableList(artifacts)
        }
        private fun validatePin(pin: OwnerModelTransportImagePin) {
            ownerModelRequire(pin.label.isNotBlank() && pin.label.toByteArray(Charsets.UTF_8).size <= 4096 &&
                !pin.label.startsWith('/') && '\\' !in pin.label && pin.label.split('/').none { it in setOf("", ".", "..") } &&
                Digests.valid(pin.digest) && pin.bytes in 1..536_870_912L,
                "owner_transport_image_pin_invalid")
            val path = Path.of(pin.path)
            ownerModelRequire(path.isAbsolute && path.normalize() == path, "owner_transport_image_pin_path")
        }
        internal fun relativeArtifact(value: String): Path {
            val path = Path.of(value); val parts = value.split('/')
            ownerModelRequire(!path.isAbsolute && path.normalize() == path && path.toString() == value &&
                parts.size == 7 && parts.take(2) == listOf("modules-2", "files-2.1") &&
                parts.none { it.isEmpty() || it == "." || it == ".." || it.any { c -> c.code < 32 || c == '\\' } } &&
                parts.slice(2..4).all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) } &&
                parts[5].matches(Regex("[0-9a-f]{40,64}")) &&
                parts.last().matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.jar")), "owner_transport_cache_relative_invalid")
            return path
        }
        private fun captureIdentity(path: Path, identity: String, pair: OwnerModelTransportArtifactPair,
                                    checkpoint: () -> Unit): OwnerModelFilePin {
            ownerModelRequire(unixIdentity(path) == identity, "owner_transport_artifact_identity_changed")
            val pin = OwnerModelFilePin.capture(path, pair.bytes, checkpoint)
            ownerModelRequire(pin.digest == pair.digest && pin.size == pair.bytes && unixIdentity(path) == identity,
                "owner_transport_artifact_bytes_changed")
            return pin
        }
        private fun unixIdentity(path: Path): String {
            ownerModelRequire(path.fileSystem.supportedFileAttributeViews().contains("unix"), "owner_transport_identity_unavailable")
            val attributes = Files.readAttributes(path, "unix:dev,ino", NOFOLLOW_LINKS)
            val dev = (attributes["dev"] as? Number)?.toLong()
            val ino = (attributes["ino"] as? Number)?.toLong()
            ownerModelRequire(dev != null && ino != null && dev >= 0 && ino >= 0, "owner_transport_identity_unavailable")
            return "$dev:$ino"
        }
    }
}

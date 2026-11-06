package dev.ktchecker.providers.detekt

import dev.ktchecker.compiler.JvmRuntimeEntryKind
import dev.ktchecker.compiler.KotlinJvmRuntimeImage
import dev.ktchecker.contracts.ArtifactIdentity
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import dev.ktchecker.projectmodel.ModelArtifact
import dev.ktchecker.projectmodel.ModelToolchain
import dev.ktchecker.providers.runtime.fileDigest
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.serialization.json.*

internal data class DetektFullPinnedArtifact(val identity: ArtifactIdentity, val size: Long)

/**
 * Owner-selected read-only identities. The compiler JARs are never loaded, imported, or executed;
 * the actual Detekt process uses ONLY its separately pinned all-JAR/plugins and the selected JDK.
 * This is neither a compatibility certificate nor an execution permit.
 *
 * Whole-JDK security logic is reused, not replaced by a launcher hash or a copied inventory.
 * The SAME original owner deadline/cancellation checkpoint is retained at capture and all later
 * verification phases; a per-scan timeout never starts a fresh overall owner budget.
 */
class DetektFullInstallation private constructor(
    val detekt: DetektInstallation,
    private val runtimeImage: KotlinJvmRuntimeImage,
    compilerArtifacts: List<DetektFullPinnedArtifact>,
    engineArtifacts: List<ArtifactIdentity>,
    private val checkpoint: () -> Unit,
) {
    internal val compilerArtifacts = immutableDetektList(compilerArtifacts)
    internal val engineArtifacts = immutableDetektList(engineArtifacts)
    val javaExecutable: Path get() = runtimeImage.javaExecutable
    val jdkHome: Path get() = runtimeImage.root
    val runtimeImageDigest: String get() = runtimeImage.digest
    val executableDigest: String get() = runtimeImage.executableDigest
    val digest: String = fullJsonDigest(buildJsonObject {
        put("schema", "ktchecker/detekt-full-installation-2.4.10/1")
        put("projectCompiler", DETEKT_EMBEDDED_KOTLIN)
        put("detektVersion", DETEKT_VERSION); put("embeddedCompiler", DETEKT_EMBEDDED_KOTLIN)
        put("javaExecutable", javaExecutable.toString()); put("jdkHome", jdkHome.toString())
        put("javaExecutableDigest", executableDigest); put("wholeJdkImageDigest", runtimeImageDigest)
        put("engineArtifacts", ContractJson.encodeToJsonElement(this@DetektFullInstallation.engineArtifacts))
        putJsonArray("modeledCompilerClasspath") { this@DetektFullInstallation.compilerArtifacts.forEach { artifact ->
            add(buildJsonObject {
                put("identity", ContractJson.encodeToJsonElement(artifact.identity)); put("size", artifact.size)
            })
        } }
        put("compilerClasspathExecuted", false); put("compatibilityQualified", false)
    })

    /** Cooperative quiescent disk identity only: no loaded-code, JNI, same-UID or ABA attestation. */
    fun verify() {
        checkpoint()
        runtimeImage.verify(checkpoint)
        compilerArtifacts.forEach { verifyFullArtifact(it, checkpoint) }
        fullCheck(DetektCatalog.verifyArtifacts(detekt.artifactDirectory) == engineArtifacts,
            "detekt_full_engine_changed", "The exact engine/plugin installation changed after selection.")
        checkpoint()
    }

    /** The original owner checkpoint is also polled during the owned native process lifetime. */
    internal fun checkOwner() = checkpoint()

    internal fun requireModelToolchain(chain: ModelToolchain) {
        checkpoint()
        fullCheck(chain.kind == "jvm" && chain.compilerVersion == DETEKT_EMBEDDED_KOTLIN,
            "detekt_full_compiler_unsupported", "Modeled Kotlin must be exactly 2.4.10, never relabeled 2.4.21.")
        fullCheck(chain.compilerArtifacts.size == compilerArtifacts.size &&
            chain.compilerArtifacts.zip(compilerArtifacts).all { (declared, installed) ->
                declared.coordinate == installed.identity.coordinate && declared.path == installed.identity.path &&
                    declared.digest == installed.identity.digest && declared.size == installed.size
            }, "detekt_full_compiler_installation_mismatch",
            "Every modeled compiler JAR must retain the owner's exact path/coordinate/bytes/size/order; no sorting or relocation.")
        val view = runtimeImage.view()
        val files = view.entries.filter { it.kind == JvmRuntimeEntryKind.FILE }.associateBy { it.relativePath }
        val names = chain.runtimeArtifacts.map { it.coordinate.removePrefix("compilation-jdk:") }
        fullCheck(chain.runtimeArtifacts.all { it.coordinate.startsWith("compilation-jdk:") } &&
            names.distinct().size == names.size && names.toSet() == files.keys,
            "detekt_full_runtime_domain_mismatch", "Modeled JDK files must project the entire selected captured image exactly once.")
        chain.runtimeArtifacts.zip(names).forEach { (declared, name) ->
            checkpoint()
            val actual = files.getValue(name)
            fullCheck(declared.path == view.root.resolve(name).toString() && declared.digest == actual.digest &&
                declared.size == actual.size, "detekt_full_runtime_artifact_mismatch",
                "Every modeled JDK file must match its exact selected path, bytes and size.")
        }
        // The retained live image, not this FILE projection, additionally binds directories,
        // empty directories, release OS/architecture/version and executable policy/bits.
        checkpoint()
    }

    /** Data projection for owner model enrollment; it never recreates this live installation. */
    fun runtimeArtifacts(): List<ModelArtifact> = immutableDetektList(runtimeImage.view().entries
        .filter { it.kind == JvmRuntimeEntryKind.FILE }.map { entry ->
            ModelArtifact("compilation-jdk:" + entry.relativePath, jdkHome.resolve(entry.relativePath).toString(),
                entry.digest, entry.size)
        })

    companion object {
        const val COMPILER_COORDINATE = "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10"
        const val COMPILER_SHA256 = "sha256:9309638a2ee03e6bde9ef4b7444055a94b84ab906563675d71ff9aecb64da913"
        const val COMPILER_BYTES = 60_352_373L
        const val STDLIB_COORDINATE = "org.jetbrains.kotlin:kotlin-stdlib:2.4.10"
        const val STDLIB_SHA256 = "sha256:4ec0293bc3751423b203f1d8493251c57c42e73eb6377a6b8560d0974ff0a6df"
        const val STDLIB_BYTES = 1_841_933L

        /**
         * Trusted owner call only. Pass its ORIGINAL capture-to-attempt deadline/cancellation
         * checkpoint, not a callback that refreshes a budget. No shell, compiler, JVM or provider
         * process is started here; hashing a JAR is not authorization to execute its contents.
         */
        fun capture(detekt: DetektInstallation, compilerClasspath: List<ArtifactIdentity>,
                    checkpoint: () -> Unit): DetektFullInstallation {
            checkpoint()
            fullPath(detekt.javaExecutable.toString()); fullPath(detekt.jdkHome.toString())
            fullPath(detekt.artifactDirectory.toString())
            fullCheck(compilerClasspath.size in 1..16 && compilerClasspath.map { it.path }.distinct().size == compilerClasspath.size,
                "detekt_full_compiler_installation_mismatch", "An exact nonempty ordered compiler JAR installation is required.")
            val pins = compilerClasspath.map { artifact ->
                val path = fullPath(artifact.path)
                fullCheck(path.toString().endsWith(".jar") && Digests.valid(artifact.digest),
                    "detekt_full_compiler_installation_mismatch", "Compiler paths must identify exact regular JAR bytes.")
                DetektFullPinnedArtifact(artifact.copy(), Files.size(path))
            }
            fullCheck(pins.singleOrNull { it.identity.coordinate == COMPILER_COORDINATE }?.let {
                it.identity.digest == COMPILER_SHA256 && it.size == COMPILER_BYTES
            } == true, "detekt_full_compiler_pin_mismatch", "A version or filename cannot replace the exact reviewed 2.4.10 compiler pin.")
            pins.forEach { verifyFullArtifact(it, checkpoint) }
            val runtime = KotlinJvmRuntimeImage.capture(detekt.javaExecutable, checkpoint = checkpoint)
            fullCheck(runtime.javaExecutable == detekt.javaExecutable && runtime.root == detekt.jdkHome &&
                runtime.view().osName == "Linux",
                "detekt_full_runtime_selection_mismatch", "This initial Linux/JDK21 seam requires the identical explicit whole JDK, not an alias or launcher-only selection.")
            val engines = DetektCatalog.verifyArtifacts(detekt.artifactDirectory)
            checkpoint()
            return DetektFullInstallation(detekt.copy(), runtime, pins, engines, checkpoint)
        }
    }
}

/** Additional byte-drift checks; registered immutable artifact provenance remains owner-supplied. */
internal fun verifyFullArtifact(pin: DetektFullPinnedArtifact, checkpoint: () -> Unit) {
    checkpoint()
    val path = fullPath(pin.identity.path)
    fullCheck(pin.size in 1..536_870_912 && Digests.valid(pin.identity.digest) &&
        Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) &&
        Files.size(path) == pin.size && fileDigest(path) == pin.identity.digest &&
        Files.size(path) == pin.size && Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path),
        "detekt_full_artifact_changed", "An exact regular artifact's bytes, size or path kind no longer match.")
    checkpoint()
}

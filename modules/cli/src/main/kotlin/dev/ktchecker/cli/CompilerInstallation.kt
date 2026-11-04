package dev.ktchecker.cli

import dev.ktchecker.compiler.CompilerRuleRegistry
import dev.ktchecker.compiler.CompilerSnapshot
import dev.ktchecker.compiler.KotlinJvmRuntimeException
import dev.ktchecker.compiler.KotlinJvmToolchain
import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest
import java.util.Collections

/** Selecting installed bytes is not execution authority or per-rule qualification. */
internal data class CompilerSelection(
    val toolchain: KotlinJvmToolchain?,
    val artifacts: List<ArtifactIdentity> = emptyList(),
    val unavailableReason: String? = null,
) {
    init { require((toolchain == null) == (unavailableReason != null)) }
}

/**
 * Keep non-executing commands and other providers usable when the selected JDK is
 * missing/unsupported. In particular, NEVER fall back from a rejected full runtime
 * identity to a launcher-only identity or to the broker's JDK.
 */
internal fun selectCompiler(directory: Path?, javaHome: Path): CompilerSelection {
    if (directory == null || !Files.isDirectory(directory, NOFOLLOW_LINKS)) {
        return CompilerSelection(null, unavailableReason = "compiler_artifacts_unavailable")
    }
    val artifacts = try { installedCompilerArtifacts(directory) } catch (_: Exception) {
        return CompilerSelection(null, unavailableReason = "compiler_artifacts_invalid")
    }
    if (artifacts.none { Path.of(it.path).fileName.toString() == "kotlin-compiler-embeddable-2.4.21.jar" }) {
        return CompilerSelection(null, unavailableReason = "compiler_artifacts_invalid")
    }
    val java = javaHome.resolve("bin").resolve(if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
    return try { CompilerSelection(KotlinJvmToolchain(java, artifacts), artifacts) }
    catch (e: KotlinJvmRuntimeException) { CompilerSelection(null, artifacts, e.code) }
    catch (_: Exception) { CompilerSelection(null, artifacts, "compiler_installation_invalid") }
}

/** Directory installs choose a documented deterministic order; supplied classpaths stay ordered. */
internal fun installedCompilerArtifacts(directory: Path): List<ArtifactIdentity> {
    require(!Files.isSymbolicLink(directory) && Files.isDirectory(directory, NOFOLLOW_LINKS))
    val entries = mutableListOf<Path>()
    Files.newDirectoryStream(directory).use { files ->
        var seen = 0
        files.forEach { file ->
            require(++seen <= 256) { "Compiler installation directory exceeds its entry limit" }
            if (file.fileName.toString().endsWith(".jar")) {
                require(!Files.isSymbolicLink(file) && Files.isRegularFile(file, NOFOLLOW_LINKS))
                entries.add(file.toAbsolutePath().normalize())
            }
        }
    }
    var total = 0L
    val artifacts = entries.sortedBy { it.fileName.toString() }.map { path ->
        val size = Files.size(path)
        require(size in 0..CompilerSnapshot.MAX_ARTIFACT_BYTES)
        total += size
        require(total <= CompilerSnapshot.MAX_TOTAL_ARTIFACT_BYTES)
        val hash = MessageDigest.getInstance("SHA-256")
        var read = 0L
        Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { stream ->
            val buffer = ByteArray(65_536)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                read += count
                require(read <= size) { "Compiler artifact grew during selection" }
                hash.update(buffer, 0, count)
            }
        }
        require(read == size && Files.size(path) == size) { "Compiler artifact changed during selection" }
        ArtifactIdentity("installed:${path.fileName}", "sha256:" + hash.digest().joinToString("") { "%02x".format(it) }, path.toString())
    }
    return Collections.unmodifiableList(artifacts)
}

/** An unavailable installation still produces precise, addressable coverage gaps. */
internal class UnavailableCompilerProvider(private val reason: String) : AnalysisProvider {
    init { require(reason.matches(Regex("compiler_[a-z_]+"))) }
    override val id = CompilerRuleRegistry.PROVIDER_ID
    // Different unavailable states must not share an indistinguishable coordinator cache key.
    override val version = "${CompilerRuleRegistry.VERSION}:unavailable:$reason"
    override val supportedRuleIds = CompilerRuleRegistry.load().jvmRuleIds

    override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
        val cancelled = cancellation.isCancelled()
        val state = if (cancelled) "cancelled" else "unavailable"
        val explanation = if (cancelled) "cancelled_before_execution" else reason
        return ProviderResult(id, state, coverage = input.requiredRuleIds.distinct().map {
            Coverage(it, input.context.scope, state, explanation)
        }, reasons = listOf(explanation), providerVersion = version)
    }
}

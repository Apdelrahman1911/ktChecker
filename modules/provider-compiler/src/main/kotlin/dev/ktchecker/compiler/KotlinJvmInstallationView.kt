package dev.ktchecker.compiler

import dev.ktchecker.contracts.ArtifactIdentity
import java.nio.file.Path
import java.util.Collections

/** Typed data views, not installation, execution, model, or qualification capabilities. */
enum class JvmRuntimeEntryKind { FILE, DIRECTORY }

data class JvmRuntimeEntryView(
    val relativePath: String,
    val kind: JvmRuntimeEntryKind,
    val size: Long,
    val digest: String?,
    val executable: Boolean,
    val executableBits: Int?,
)

class JvmRuntimeImageView internal constructor(
    val root: Path,
    val javaExecutable: Path,
    val digest: String,
    val executableDigest: String,
    val javaVersion: String,
    val osName: String,
    val architecture: String,
    entries: List<JvmRuntimeEntryView>,
) {
    val entries: List<JvmRuntimeEntryView> = Collections.unmodifiableList(entries.map { it.copy() })
}

data class InstalledCompilerJarView(val ordinal: Int, val identity: ArtifactIdentity, val size: Long)

/** Captured schema-3 installation data; only its live KotlinJvmToolchain can reverify it. */
class KotlinJvmInstallationView internal constructor(
    val toolchainDigest: String,
    val runtime: JvmRuntimeImageView,
    compilerClasspath: List<InstalledCompilerJarView>,
) {
    val compilerClasspath: List<InstalledCompilerJarView> = Collections.unmodifiableList(
        compilerClasspath.map { it.copy(identity = it.identity.copy()) },
    )
}

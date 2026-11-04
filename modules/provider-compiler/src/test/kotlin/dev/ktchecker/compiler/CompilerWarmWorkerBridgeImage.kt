package dev.ktchecker.compiler

import dev.ktchecker.compilerworker.CompilerWarmWorkerProtocol as Wire
import dev.ktchecker.contracts.ArtifactIdentity
import dev.ktchecker.contracts.Digests
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.jar.Manifest
import java.util.zip.ZipFile

/** Closed host-code boundary labels, never exception text, readiness/exec proof, or recovered execution evidence. */
internal enum class CompilerWarmHostStage {
    STARTUP_CAPTURE, FIXTURE_INPUTS, COMPILER_CLOSURE, TOOLCHAIN_CAPTURE, BRIDGE_VERIFICATION, BRIDGE_PERSISTENCE,
    IMPLEMENTATION_IDENTITY, INPUT_MATERIALIZATION, PLAN_AUTHORITY, PRELAUNCH_VERIFICATION, PLAN_PERSISTENCE, EXECUTION_REQUEST,
    GENERATION_EXECUTION, PROCESS_OBSERVATION, RECEIPT_OBSERVATION, POSTFLIGHT_VERIFICATION, OUTCOME_OBSERVATION,
    CONTRACT_FINALIZATION, SCRATCH_CLEANUP, FINAL_CHECKPOINT, SUMMARY_PUBLICATION;

    val code: String get() = name.lowercase(Locale.ROOT)
}

internal enum class CompilerWarmHostFailureCode {
    INVALID_ARGUMENT, INVALID_STATE, IO_FAILURE, SECURITY_FAILURE, CANCELLED, UNEXPECTED_FAILURE,
    GENERATION_DEADLINE, OWNER_STOPPED,
    BRIDGE_CODE_ROOT, BRIDGE_PATH, BRIDGE_DIRECTORY, BRIDGE_DIRECTORY_CHANGED, BRIDGE_FILE, BRIDGE_FILE_CHANGED,
    BRIDGE_ARCHIVE_IDENTITY, BRIDGE_ARCHIVE_CHANGED, BRIDGE_CLASS_SET, BRIDGE_ENTRY_COUNT, BRIDGE_ENTRY_NAME,
    BRIDGE_ENTRY_SIZE, BRIDGE_MANIFEST_CLASSPATH, BRIDGE_CLASS_BYTES;

    val code: String get() = name.lowercase(Locale.ROOT)
}

internal class CompilerWarmHostRejection(val code: CompilerWarmHostFailureCode) : IllegalArgumentException(code.code)

internal class CompilerWarmHostDiagnostics {
    var stage: CompilerWarmHostStage = CompilerWarmHostStage.STARTUP_CAPTURE
    var failureStage: CompilerWarmHostStage? = null
        private set
    var failureCode: CompilerWarmHostFailureCode? = null
        private set

    /** First caught failure wins. Never inspect message, cause, suppressed exceptions, or stack frames. */
    fun record(problem: Throwable) {
        if (failureCode != null) return
        failureStage = stage
        failureCode = when (problem) {
            is CompilerWarmHostRejection -> problem.code
            is CancellationException -> CompilerWarmHostFailureCode.CANCELLED
            is IllegalArgumentException -> CompilerWarmHostFailureCode.INVALID_ARGUMENT
            is IllegalStateException -> CompilerWarmHostFailureCode.INVALID_STATE
            is IOException -> CompilerWarmHostFailureCode.IO_FAILURE
            is SecurityException -> CompilerWarmHostFailureCode.SECURITY_FAILURE
            else -> CompilerWarmHostFailureCode.UNEXPECTED_FAILURE
        }
    }
}

/**
 * Data-only host verifier, not a compiler loader. The production caller supplies the actual three-class CodeSource.
 * '$' is accepted only in a validated basename below this one fixed package; worker wire path grammar is unchanged.
 * Directory/file keys plus bounded no-follow reads detect cooperative drift, not adversarial same-UID/ABA races.
 */
internal object CompilerWarmWorkerBridgeImage {
    private const val PREFIX = "dev/ktchecker/compilerworker/"
    private const val MAX_ARCHIVE_BYTES = 2_097_152
    private const val MAX_ENTRY_BYTES = 1_048_576
    private const val MAX_CLASSES = 128
    private val className = Regex("(?:CompilerWarmWorkerProtocol|CompilerWarmWorkerMain|PinnedKotlinCompilerAdapter)(?:\\$[A-Za-z0-9_$]+)?\\.class")
    private val requiredClasses = setOf("CompilerWarmWorkerProtocol.class", "CompilerWarmWorkerMain.class", "PinnedKotlinCompilerAdapter.class")
    private val allowedDirectories = setOf("META-INF/", "dev/", "dev/ktchecker/", PREFIX)
    private data class DirectoryPin(val path: Path, val key: Any)
    private data class FilePin(val key: Any, val size: Long)

    fun verify(artifact: ArtifactIdentity, classRoot: Path) {
        val archive = Path.of(artifact.path)
        demand(archive.isAbsolute && archive.normalize() == archive && archive.nameCount in 1..64,
            CompilerWarmHostFailureCode.BRIDGE_PATH)
        val archiveDirectories = pinDirectories(checkNotNull(archive.parent))
        val packageRoot = classRoot.resolve(PREFIX.removeSuffix("/"))
        val classDirectories = pinDirectories(classRoot) + pinDirectories(packageRoot)
        val archiveBytes = readFile(archive, MAX_ARCHIVE_BYTES, archiveDirectories)
        demand(archiveBytes.isNotEmpty() && Digests.sha256(archiveBytes) == artifact.digest,
            CompilerWarmHostFailureCode.BRIDGE_ARCHIVE_IDENTITY)
        val names = classNames(packageRoot)
        demand(names.size in requiredClasses.size..MAX_CLASSES && requiredClasses.all { PREFIX + it in names },
            CompilerWarmHostFailureCode.BRIDGE_CLASS_SET)
        val observed = mutableSetOf<String>()
        ZipFile(archive.toFile()).use { zip ->
            demand(zip.size() <= 160, CompilerWarmHostFailureCode.BRIDGE_ENTRY_COUNT)
            val entries = zip.entries()
            val seen = mutableSetOf<String>()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                demand(seen.add(entry.name) && '\\' !in entry.name && '\u0000' !in entry.name,
                    CompilerWarmHostFailureCode.BRIDGE_ENTRY_NAME)
                if (entry.isDirectory) {
                    demand(entry.name in allowedDirectories, CompilerWarmHostFailureCode.BRIDGE_ENTRY_NAME)
                    continue
                }
                val manifest = entry.name == "META-INF/MANIFEST.MF"
                demand(manifest || entry.name in names, CompilerWarmHostFailureCode.BRIDGE_ENTRY_NAME)
                val maximum = if (manifest) MAX_ENTRY_BYTES else Wire.MAX_RECEIPT_BYTES
                demand(entry.size in 0L..maximum.toLong(), CompilerWarmHostFailureCode.BRIDGE_ENTRY_SIZE)
                val bytes = zip.getInputStream(entry).use { it.readNBytes(maximum + 1) }
                demand(bytes.size.toLong() == entry.size, CompilerWarmHostFailureCode.BRIDGE_ENTRY_SIZE)
                if (manifest) {
                    demand(Manifest(bytes.inputStream()).mainAttributes.getValue("Class-Path") == null,
                        CompilerWarmHostFailureCode.BRIDGE_MANIFEST_CLASSPATH)
                } else {
                    // Revalidate the complete spelling before resolving a basename; no generic '$' path allowance.
                    val basename = entry.name.removePrefix(PREFIX)
                    demand(entry.name.startsWith(PREFIX) && className.matches(basename), CompilerWarmHostFailureCode.BRIDGE_ENTRY_NAME)
                    demand(bytes.contentEquals(readFile(packageRoot.resolve(basename), Wire.MAX_RECEIPT_BYTES, classDirectories)),
                        CompilerWarmHostFailureCode.BRIDGE_CLASS_BYTES)
                    observed += entry.name
                }
            }
        }
        demand(observed == names && classNames(packageRoot) == names, CompilerWarmHostFailureCode.BRIDGE_CLASS_SET)
        verifyDirectories(classDirectories)
        demand(readFile(archive, MAX_ARCHIVE_BYTES, archiveDirectories).contentEquals(archiveBytes),
            CompilerWarmHostFailureCode.BRIDGE_ARCHIVE_CHANGED)
    }

    private fun classNames(root: Path): Set<String> {
        val names = mutableSetOf<String>()
        var entries = 0
        Files.newDirectoryStream(root).use { stream ->
            for (path in stream) {
                demand(++entries <= 4_096, CompilerWarmHostFailureCode.BRIDGE_ENTRY_COUNT)
                val name = path.fileName.toString()
                if (className.matches(name)) {
                    names += PREFIX + name
                    demand(names.size <= MAX_CLASSES, CompilerWarmHostFailureCode.BRIDGE_CLASS_SET)
                }
            }
        }
        return names
    }

    private fun pinDirectories(path: Path): List<DirectoryPin> {
        demand(path.isAbsolute && path.normalize() == path && path.nameCount <= 64, CompilerWarmHostFailureCode.BRIDGE_PATH)
        val root = checkNotNull(path.root)
        val paths = listOf(root) + (1..path.nameCount).map { root.resolve(path.subpath(0, it)) }
        val pins = paths.map { directory ->
            val attributes = Files.readAttributes(directory, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            demand(attributes.isDirectory && !attributes.isSymbolicLink && attributes.fileKey() != null,
                CompilerWarmHostFailureCode.BRIDGE_DIRECTORY)
            DirectoryPin(directory, checkNotNull(attributes.fileKey()))
        }
        demand(path.toRealPath() == path, CompilerWarmHostFailureCode.BRIDGE_PATH)
        return pins
    }

    private fun verifyDirectories(pins: List<DirectoryPin>) {
        pins.forEach { pin ->
            val attributes = Files.readAttributes(pin.path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            demand(attributes.isDirectory && !attributes.isSymbolicLink && attributes.fileKey() == pin.key,
                CompilerWarmHostFailureCode.BRIDGE_DIRECTORY_CHANGED)
        }
    }

    private fun filePin(path: Path, maximum: Int): FilePin {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        demand(attributes.isRegularFile && !attributes.isSymbolicLink && attributes.fileKey() != null && attributes.size() in 0L..maximum.toLong(),
            CompilerWarmHostFailureCode.BRIDGE_FILE)
        return FilePin(checkNotNull(attributes.fileKey()), attributes.size())
    }

    private fun readFile(path: Path, maximum: Int, directories: List<DirectoryPin>): ByteArray {
        verifyDirectories(directories)
        val before = filePin(path, maximum)
        val bytes = Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { it.readNBytes(maximum + 1) }
        demand(filePin(path, maximum) == before && bytes.size.toLong() == before.size, CompilerWarmHostFailureCode.BRIDGE_FILE_CHANGED)
        verifyDirectories(directories)
        return bytes
    }

    private fun demand(accepted: Boolean, code: CompilerWarmHostFailureCode) {
        if (!accepted) throw CompilerWarmHostRejection(code)
    }
}

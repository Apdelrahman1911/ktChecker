package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant

@Serializable internal data class OwnerExecutionHomeEntry(val relativePath: String, val directory: Boolean,
    val sourceFileKey: String, val copyFileKey: String, val sourcePermissions: List<String>,
    val initialPermissions: List<String>, val digest: String? = null, val size: Long? = null,
    val preEnrolledDependency: Boolean = false)

/** Concrete roots plus each relative path define the exact source -> copy mapping. Data only. */
@Serializable internal data class OwnerExecutionHomeEvidence(val schemaVersion: Int, val derivation: String,
    val capturedAt: String, val sourceRuntimeInputDigest: String, val sourceRoot: String, val executionHome: String,
    val entries: List<OwnerExecutionHomeEntry>, val limitations: List<String> = listOf(
        "Initial bytes/topology are bound derivation inputs; the copied home is writable execution scratch.",
        "Only pre-enrolled original files-2.1 dependency copies are subsequent model artifact authority.",
        "Generated DSL/native/transformed caches are outputs, not source-to-binary or loaded-memory attestations.",
        "Same-user private copies and owned process groups are not an OS security sandbox."))

/** One owner-created attempt only. Never rebaseline scratch or enroll report-selected output files. */
internal class OwnerFixtureExecutionHome private constructor(val root: Path, val evidencePath: Path,
    private val source: Path, private val evidence: OwnerExecutionHomeEvidence) {
    private data class Entry(val directory: Boolean, val size: Long, val key: String, val permissions: List<String>)
    private var initialClosed = false
    val digest: String = Digests.sha256("ktchecker/owner-fixture-execution-home/1\u0000" + ContractJson.encodeToString(evidence))
    val dependencyArtifacts: List<ModelArtifact> = evidence.entries.filter { it.preEnrolledDependency }.map { entry ->
        ModelArtifact("owner-fixture-derived-dependency:${entry.relativePath}", root.resolve(entry.relativePath).toString(), entry.digest, entry.size)
    }
    private val dependencies = evidence.entries.filter { it.preEnrolledDependency }.associateBy { root.resolve(it.relativePath).toString() }

    /** Exact initial map is checked again in the final preparation callback, before any launch. */
    fun prepareForLaunch() {
        homeExpect(!initialClosed, "owner_fixture_execution_home_already_prepared")
        verifyInitial()
        // This is a preparation phase marker, never evidence that execution occurred.
        initialClosed = true
    }

    fun verifyInputs() {
        if (!initialClosed) verifyInitial() else {
            verifyEvidence()
            verifySourceMap()
            val actual = tree(root)
            val initialRoot = evidence.entries.single { it.relativePath.isEmpty() }
            verifyEntry(initialRoot, actual.getValue(""), copied = true)
            dependencies.values.forEach { verifyDependency(it, actual[it.relativePath]) }
        }
    }

    fun containsDependency(path: String): Boolean = path in dependencies

    fun verifyDependencyArtifact(actual: ModelArtifact) {
        val entry = dependencies[actual.path] ?: throw OwnerFixtureProblem("owner_fixture_artifact_unregistered")
        homeExpect(actual.digest == entry.digest && actual.size == entry.size, "owner_fixture_derived_dependency_changed")
        verifyDependency(entry, treeEntry(root.resolve(entry.relativePath)))
    }

    private fun verifyInitial() {
        verifyEvidence()
        verifySourceMap()
        val actual = tree(root)
        homeExpect(actual.keys == evidence.entries.map { it.relativePath }.toSet(), "owner_fixture_execution_home_initial_inventory_changed")
        evidence.entries.forEach { entry ->
            verifyEntry(entry, actual.getValue(entry.relativePath), copied = true)
            if (!entry.directory) homeExpect(hashFile(root.resolve(entry.relativePath), FILE_LIMIT, entry.size) == entry.digest,
                "owner_fixture_execution_home_initial_bytes_changed")
        }
    }

    private fun verifySourceMap() {
        val actual = tree(source).filter { (path, entry) -> entry.directory || !lockFile(path) }
        homeExpect(actual.keys == evidence.entries.map { it.relativePath }.toSet(), "owner_fixture_execution_home_source_inventory_changed")
        evidence.entries.forEach { verifyEntry(it, actual.getValue(it.relativePath), copied = false) }
    }

    private fun verifyDependency(entry: OwnerExecutionHomeEntry, actual: Entry?) {
        homeExpect(actual != null, "owner_fixture_derived_dependency_changed")
        verifyEntry(entry, actual!!, copied = true)
        homeExpect(hashFile(root.resolve(entry.relativePath), FILE_LIMIT, entry.size) == entry.digest,
            "owner_fixture_derived_dependency_changed")
    }

    private fun verifyEntry(expected: OwnerExecutionHomeEntry, actual: Entry, copied: Boolean) {
        homeExpect(actual.directory == expected.directory && actual.key == (if (copied) expected.copyFileKey else expected.sourceFileKey),
            "owner_fixture_execution_home_identity_changed")
        homeExpect(actual.permissions == (if (copied) expected.initialPermissions else expected.sourcePermissions),
            "owner_fixture_execution_home_permissions_changed")
        if (!expected.directory) homeExpect(actual.size == expected.size, if (copied && expected.preEnrolledDependency)
            "owner_fixture_derived_dependency_changed" else "owner_fixture_execution_home_initial_bytes_changed")
    }

    private fun verifyEvidence() {
        homeExpect(treeEntry(evidencePath).permissions == names(READ_ONLY) &&
            ownerFixtureUtf8(readOwnerFixtureInput(evidencePath, EVIDENCE_LIMIT)) == ContractJson.encodeToString(evidence) + "\n",
            "owner_fixture_execution_home_evidence_changed")
    }

    companion object {
        private const val FILE_LIMIT = 512L * 1024 * 1024
        private const val COPY_LIMIT = 2L * 1024 * 1024 * 1024
        private const val ENTRY_LIMIT = 20_000
        private const val TREE_LIMIT = 50_000
        private const val EVIDENCE_LIMIT = 32 * 1024 * 1024
        private val PRIVATE_DIRECTORY = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
        private val READ_ONLY = setOf(PosixFilePermission.OWNER_READ)
        private val PRIVATE_FILE = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        private fun lockFile(path: String) = path.endsWith(".lock") || path.endsWith(".lck")
        private fun names(permissions: Set<PosixFilePermission>) = permissions.map { it.name }.sorted()
        private fun permissions(file: OwnerRuntimeFile): Set<PosixFilePermission> =
            (if (file.relativePath.startsWith("caches/modules-2/files-2.1/")) READ_ONLY else PRIVATE_FILE) +
                if (file.executable) setOf(PosixFilePermission.OWNER_EXECUTE) else emptySet()

        /** Caller has already verified the immutable source registry; no ambient input discovery. */
        fun derive(source: Path, files: List<OwnerRuntimeFile>, directories: List<OwnerRuntimeDirectory>,
                   sourceRuntimeInputDigest: String, root: Path, evidencePath: Path): OwnerFixtureExecutionHome {
            val capturedAt = Instant.now().toString()
            val selectedFiles = files.filter { it.kind == "seed" }
            val selectedDirectories = directories.filter { it.kind == "seed" }
            homeExpect(Digests.valid(sourceRuntimeInputDigest) && selectedFiles.size + selectedDirectories.size + 1 <= ENTRY_LIMIT &&
                selectedFiles.all { it.size in 0..FILE_LIMIT && Digests.valid(it.digest) && !lockFile(it.relativePath) } &&
                selectedFiles.sumOf { it.size } <= COPY_LIMIT, "owner_fixture_execution_home_bound")
            (selectedFiles.map { it.relativePath } + selectedDirectories.map { it.relativePath }).forEach { logicalPath(it) }
            homeExpect(root.isAbsolute && root.normalize() == root && evidencePath.isAbsolute && evidencePath.normalize() == evidencePath &&
                !root.startsWith(source) && !source.startsWith(root) && !evidencePath.startsWith(root) &&
                !evidencePath.startsWith(source), "owner_fixture_execution_home_roots_invalid")
            homeExpect(!Files.exists(root, NOFOLLOW_LINKS) && !Files.exists(evidencePath, NOFOLLOW_LINKS),
                "owner_fixture_execution_home_not_fresh")
            try {
                val original = tree(source).filter { (path, entry) -> entry.directory || !lockFile(path) }
                val expected = selectedFiles.associate { it.relativePath to false } + selectedDirectories.associate { it.relativePath to true } + ("" to true)
                homeExpect(expected.size == selectedFiles.size + selectedDirectories.size + 1 &&
                    original.mapValues { it.value.directory } == expected, "owner_fixture_execution_home_source_inventory_changed")
                createDirectory(root)
                selectedDirectories.sortedWith(compareBy<OwnerRuntimeDirectory> { it.relativePath.count { char -> char == '/' } }.thenBy { it.relativePath })
                    .forEach { createDirectory(root.resolve(it.relativePath)) }
                selectedFiles.forEach { copy(source.resolve(it.relativePath), root.resolve(it.relativePath), it) }
                val copied = tree(root)
                homeExpect(copied.mapValues { it.value.directory } == expected, "owner_fixture_execution_home_initial_inventory_changed")
                val filesByPath = selectedFiles.associateBy { it.relativePath }
                val entries = expected.keys.sorted().map { relative ->
                    val file = filesByPath[relative]
                    val before = original.getValue(relative); val after = copied.getValue(relative)
                    homeExpect(before.key != after.key, "owner_fixture_execution_home_copy_not_independent")
                    val policy = names(if (before.directory) PRIVATE_DIRECTORY else permissions(file!!))
                    homeExpect(after.permissions == policy, "owner_fixture_execution_home_permissions_changed")
                    OwnerExecutionHomeEntry(relative, before.directory, before.key, after.key, before.permissions,
                        policy, file?.digest, file?.size, file?.relativePath?.startsWith("caches/modules-2/files-2.1/") == true)
                }
                val evidence = OwnerExecutionHomeEvidence(1, "exact-seed-copy-to-writable-scratch/1", capturedAt,
                    sourceRuntimeInputDigest, source.toString(), root.toString(), entries)
                // Conservative escape-safe guard before serialization/allocation.
                val textCharacters = entries.sumOf { it.relativePath.length.toLong() + it.sourceFileKey.length + it.copyFileKey.length +
                    it.sourcePermissions.sumOf(String::length) + it.initialPermissions.sumOf(String::length) + (it.digest?.length ?: 0) } +
                    source.toString().length + root.toString().length + sourceRuntimeInputDigest.length + capturedAt.length + evidence.limitations.sumOf(String::length)
                homeExpect(entries.size * 256L + textCharacters * 6L + 4096L <= EVIDENCE_LIMIT,
                    "owner_fixture_execution_home_evidence_bound")
                anchoredDirectory(evidencePath.parent) { directory ->
                    directory.newByteChannel(evidencePath.fileName, setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
                        PosixFilePermissions.asFileAttribute(READ_ONLY)).use { channel ->
                        val bytes = (ContractJson.encodeToString(evidence) + "\n").toByteArray(Charsets.UTF_8)
                        homeExpect(bytes.size <= EVIDENCE_LIMIT, "owner_fixture_execution_home_evidence_bound")
                        write(channel, ByteBuffer.wrap(bytes))
                    }
                }
                return OwnerFixtureExecutionHome(root, evidencePath, source, evidence).also { it.verifyInitial() }
            } catch (error: OwnerFixtureProblem) { throw error }
            catch (error: Exception) { throw OwnerFixtureProblem("owner_fixture_execution_home_preparation_failed").apply { initCause(error) } }
            // Partial copies are retained in this failed private attempt, never cleaned/reused as inputs.
        }

        private fun createDirectory(path: Path) = anchoredDirectory(path.parent) { directory ->
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY))
            val attributes = directory.getFileAttributeView(path.fileName, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()
            homeExpect(attributes.isDirectory && !attributes.isSymbolicLink && attributes.fileKey() != null,
                "owner_fixture_execution_home_identity_changed")
        }

        private fun copy(source: Path, target: Path, expected: OwnerRuntimeFile) = anchoredDirectory(source.parent) { input ->
            anchoredDirectory(target.parent) { output ->
                val view = input.getFileAttributeView(source.fileName, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
                val before = view.readAttributes()
                homeExpect(before.isRegularFile && !before.isSymbolicLink && before.fileKey() != null && before.size() == expected.size &&
                    before.permissions().any { it.name.endsWith("EXECUTE") } == expected.executable,
                    "owner_fixture_execution_home_source_changed")
                val sha = MessageDigest.getInstance("SHA-256")
                input.newByteChannel(source.fileName, setOf(READ, NOFOLLOW_LINKS)).use { from ->
                    output.newByteChannel(target.fileName, setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
                        PosixFilePermissions.asFileAttribute(permissions(expected))).use { to ->
                        val buffer = ByteBuffer.allocate(64 * 1024); var count = 0L; var noProgress = 0
                        while (true) {
                            buffer.clear(); val amount = from.read(buffer)
                            if (amount < 0) break
                            if (amount == 0) { homeExpect(++noProgress <= 16, "owner_fixture_execution_home_copy_changed"); continue }
                            noProgress = 0; count += amount
                            homeExpect(count <= expected.size, "owner_fixture_execution_home_copy_changed")
                            buffer.flip(); sha.update(buffer.asReadOnlyBuffer()); write(to, buffer)
                        }
                        homeExpect(count == expected.size && from.size() == expected.size && to.size() == expected.size,
                            "owner_fixture_execution_home_copy_changed")
                    }
                }
                val after = view.readAttributes()
                homeExpect(sameFile(before, after) && before.permissions() == after.permissions() &&
                    "sha256:" + sha.digest().joinToString("") { "%02x".format(it) } == expected.digest,
                    "owner_fixture_execution_home_source_changed")
                val actual = treeEntry(target)
                homeExpect(actual.key != before.fileKey().toString() && actual.permissions == names(permissions(expected)),
                    "owner_fixture_execution_home_copy_not_independent")
            }
        }

        private fun write(channel: java.nio.channels.SeekableByteChannel, bytes: ByteBuffer) {
            var noProgress = 0
            while (bytes.hasRemaining()) {
                if (channel.write(bytes) == 0) homeExpect(++noProgress <= 16, "owner_fixture_execution_home_copy_changed") else noProgress = 0
            }
        }

        private fun sameFile(before: BasicFileAttributes, after: BasicFileAttributes) = before.fileKey() != null &&
            before.fileKey() == after.fileKey() && before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime() &&
            before.isRegularFile == after.isRegularFile && before.isDirectory == after.isDirectory && !after.isSymbolicLink

        private fun treeEntry(path: Path): Entry = anchoredDirectory(path.parent) { directory -> entry(directory, path.fileName, path) }

        private fun entry(directory: SecureDirectoryStream<Path>, name: Path?, path: Path): Entry {
            val view = if (name == null) directory.getFileAttributeView(PosixFileAttributeView::class.java)
                else directory.getFileAttributeView(name, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
            val before = view.readAttributes()
            homeExpect(!before.isSymbolicLink && (before.isRegularFile || before.isDirectory) && before.fileKey() != null,
                "owner_fixture_execution_home_alias_or_nonregular")
            if (before.isRegularFile) {
                homeExpect((Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as? Number)?.toLong() == 1L &&
                    sameFile(before, Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)),
                    "owner_fixture_execution_home_copy_not_independent")
            }
            homeExpect(sameFile(before, view.readAttributes()), "owner_fixture_execution_home_identity_changed")
            return Entry(before.isDirectory, before.size(), before.fileKey().toString(), names(before.permissions()))
        }

        private fun tree(root: Path): Map<String, Entry> = anchoredDirectory(root) { directory ->
            val entries = linkedMapOf("" to entry(directory, null, root))
            fun visit(parent: SecureDirectoryStream<Path>, relative: String) {
                parent.forEach { path ->
                    homeExpect(entries.size < TREE_LIMIT, "owner_fixture_execution_home_bound")
                    val name = path.fileName; val child = if (relative.isEmpty()) name.toString() else "$relative/$name"
                    logicalPath(child)
                    val attributes = entry(parent, name, root.resolve(child))
                    entries[child] = attributes
                    if (attributes.directory) parent.newDirectoryStream(name, NOFOLLOW_LINKS).use { opened ->
                        homeExpect(entry(opened, null, root.resolve(child)).key == attributes.key, "owner_fixture_execution_home_identity_changed")
                        visit(opened, child)
                        homeExpect(entry(parent, name, root.resolve(child)).key == attributes.key, "owner_fixture_execution_home_identity_changed")
                    }
                }
            }
            visit(directory, ""); entries
        }

        private fun <T> anchoredDirectory(path: Path, action: (SecureDirectoryStream<Path>) -> T): T {
            homeExpect(path.isAbsolute && path.normalize() == path && path.nameCount > 0, "owner_fixture_execution_home_roots_invalid")
            Files.newDirectoryStream(path.root).use { stream ->
                val root = stream as? SecureDirectoryStream<Path> ?: throw OwnerFixtureProblem("owner_fixture_execution_home_secure_io_unavailable")
                fun descend(parent: SecureDirectoryStream<Path>, index: Int): T {
                    if (index == path.nameCount) return action(parent)
                    val view = parent.getFileAttributeView(path.getName(index), BasicFileAttributeView::class.java, NOFOLLOW_LINKS)
                    val before = view.readAttributes()
                    homeExpect(before.isDirectory && !before.isSymbolicLink && before.fileKey() != null,
                        "owner_fixture_execution_home_alias_or_nonregular")
                    return parent.newDirectoryStream(path.getName(index), NOFOLLOW_LINKS).use { child ->
                        homeExpect(child.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes().fileKey() == before.fileKey(),
                            "owner_fixture_execution_home_identity_changed")
                        descend(child, index + 1).also {
                            homeExpect(view.readAttributes().fileKey() == before.fileKey(), "owner_fixture_execution_home_identity_changed")
                        }
                    }
                }
                return descend(root, 0)
            }
        }
    }
}

private fun homeExpect(condition: Boolean, code: String) { if (!condition) throw OwnerFixtureProblem(code) }

package dev.ktchecker.compiler

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes

internal data class CompilerFixtureCleanupReceipt(
    val directory: Path, val expectedKey: String, val passed: Boolean, val visitedEntries: Int,
    val deletedEntries: Int, val failureType: String? = null, val failure: String? = null,
    val maxEntries: Int = CompilerFixtureScratch.MAX_ENTRIES, val maxDepth: Int = CompilerFixtureScratch.MAX_DEPTH,
    val quarantined: Boolean = false,
) {
    fun description(): Map<String, Any?> = mapOf("directory" to directory.toString(), "expected_file_key" to expectedKey,
        "passed" to passed, "disappearance_checked" to passed, "visited_entries" to visitedEntries,
        "deleted_entries" to deletedEntries, "failure_type" to failureType, "failure" to failure,
        "maximum_entries" to maxEntries, "maximum_depth" to maxDepth,
        "quarantined" to quarantined,
        "cleanup_scope" to "registered_run_private_subtree_only; secure_no_follow_reverse_depth_deletion",
        "limitation" to "cooperative_quiescent_owned_tree_not_adversarial_same_UID_ABA_attestation")
}

/** Identity is registered immediately after creation, or in the original permit's pre-write output callback. */
internal class CompilerFixtureScratch private constructor(
    val rootIdentity: CompilerFixtureDirectoryIdentity,
    private val outerIdentity: CompilerFixtureDirectoryIdentity,
    private val maxEntries: Int, private val maxDepth: Int,
) {
    val root: Path get() = rootIdentity.path
    private val registered = linkedMapOf(root to rootIdentity)
    private val retired = mutableSetOf<Path>()
    private var quarantineReason: String? = null
    private val snapshots = createGroup("snapshots")
    private val outputs = createGroup("outputs")
    val outputRoot: Path get() = outputs.path

    /** No later outcome/path observation may authorize deletion after child quiescence was lost. */
    fun quarantine(reason: String) {
        require(reason.isNotBlank())
        if (quarantineReason == null) quarantineReason = reason.take(1_024)
    }

    private fun createGroup(name: String): CompilerFixtureDirectoryIdentity {
        requireFixtureDirectory(outerIdentity)
        requireFixtureDirectory(rootIdentity)
        val path = Files.createDirectory(root.resolve(name))
        val identity = fixtureDirectoryIdentity(path)
        registered[path] = identity
        privateFixturePermissions(path)
        return identity
    }

    fun newSnapshot(number: Int): CompilerFixtureDirectoryIdentity {
        verify(snapshots)
        val path = Files.createTempDirectory(snapshots.path, "job-$number-")
        val identity = fixtureDirectoryIdentity(path)
        registered[path] = identity
        privateFixturePermissions(path)
        return identity
    }

    fun registerOutput(path: Path): CompilerFixtureDirectoryIdentity {
        verify(outputs)
        require(path.isAbsolute && path.normalize() == path && path.parent == outputs.path &&
            path.fileName.toString().startsWith("provider-") && path !in registered) { "Output is not a fresh owned run descendant" }
        return fixtureDirectoryIdentity(path).also { registered[path] = it; privateFixturePermissions(path) }
    }

    fun verify(identity: CompilerFixtureDirectoryIdentity) {
        check(quarantineReason == null) { "Owned scratch is quarantined: $quarantineReason" }
        require(identity.path.startsWith(root) && registered[identity.path] == identity && identity.path !in retired) { "Unregistered or retired scratch root" }
        requireFixtureDirectory(outerIdentity)
        var path = root
        requireFixtureDirectory(rootIdentity)
        for (part in root.relativize(identity.path)) {
            path = path.resolve(part)
            requireFixtureDirectory(checkNotNull(registered[path]) { "Unregistered scratch ancestor" })
        }
    }

    /** Preflight the entire bounded ordinary tree before deleting anything, then recheck identities at each deletion. */
    fun cleanup(identity: CompilerFixtureDirectoryIdentity): CompilerFixtureCleanupReceipt {
        quarantineReason?.let { reason ->
            // Deliberately do not even inventory a possibly live tree. This includes all job roots,
            // an output created before its registration callback, and the final private ancestor.
            return CompilerFixtureCleanupReceipt(identity.path, identity.key, false, 0, 0,
                CompilerFixtureStopReason.CHILD_QUIESCENCE.code, reason, maxEntries, maxDepth, quarantined = true)
        }
        var visited = 0
        var deleted = 0
        data class Entry(val name: Path, val key: String, val directory: Boolean, val size: Long, val children: List<Entry>)
        fun attributes(parent: SecureDirectoryStream<Path>, name: Path): BasicFileAttributes =
            parent.getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()
        fun stableKey(attributes: BasicFileAttributes): String {
            require(!attributes.isSymbolicLink && (attributes.isDirectory || attributes.isRegularFile)) { "Linked or special scratch descendant" }
            return checkNotNull(attributes.fileKey()?.toString()) { "Scratch descendant has no stable identity" }
        }
        fun secure(stream: java.nio.file.DirectoryStream<Path>): SecureDirectoryStream<Path> =
            (stream as? SecureDirectoryStream<Path>) ?: run { stream.close(); error("Anchored no-follow cleanup is unavailable on this host") }
        fun inventory(stream: SecureDirectoryStream<Path>, depth: Int): List<Entry> {
            require(depth <= maxDepth) { "Scratch cleanup exceeds depth bound" }
            return stream.map { path ->
                require(++visited <= maxEntries) { "Scratch cleanup exceeds entry bound" }
                val name = path.fileName
                require(name.toString() !in setOf("", ".", "..") && name.nameCount == 1) { "Invalid scratch entry name" }
                val attr = attributes(stream, name)
                val key = stableKey(attr)
                val children = if (attr.isDirectory) secure(stream.newDirectoryStream(name, NOFOLLOW_LINKS)).use { child ->
                    check(child.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes().fileKey()?.toString() == key)
                    inventory(child, depth + 1)
                } else emptyList()
                Entry(name, key, attr.isDirectory, attr.size(), children)
            }.sortedBy { it.name.toString() }
        }
        fun delete(stream: SecureDirectoryStream<Path>, entries: List<Entry>) {
            for (entry in entries) {
                val attr = attributes(stream, entry.name)
                check(stableKey(attr) == entry.key && attr.isDirectory == entry.directory &&
                    (entry.directory || attr.size() == entry.size)) { "Scratch descendant changed after cleanup inventory" }
                if (entry.directory) {
                    secure(stream.newDirectoryStream(entry.name, NOFOLLOW_LINKS)).use { child ->
                        check(child.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes().fileKey()?.toString() == entry.key)
                        delete(child, entry.children)
                    }
                    check(stableKey(attributes(stream, entry.name)) == entry.key)
                    stream.deleteDirectory(entry.name)
                } else stream.deleteFile(entry.name)
                deleted++
            }
        }
        try {
            verify(identity)
            // Final-root cleanup must not turn a rejected/reappeared per-job path into newly owned authority.
            registered.values.filter { it.path.startsWith(identity.path) }.forEach { descendant ->
                if (descendant.path in retired) require(!Files.exists(descendant.path, NOFOLLOW_LINKS)) { "Retired scratch path reappeared" }
                else requireFixtureDirectory(descendant)
            }
            val opened = mutableListOf<SecureDirectoryStream<Path>>()
            try {
                var parent = secure(Files.newDirectoryStream(outerIdentity.path)).also(opened::add)
                check(parent.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes().fileKey()?.toString() == outerIdentity.key)
                var path = outerIdentity.path
                val ancestors = outerIdentity.path.relativize(identity.path.parent)
                // Java's empty relative Path iterates one empty component. The outer handle is
                // already anchored above; final-root cleanup must not reopen it as a run descendant.
                if (ancestors.toString().isNotEmpty()) {
                    for (part in ancestors) {
                        path = path.resolve(part)
                        val expected = checkNotNull(registered[path]) { "Unregistered cleanup ancestor" }
                        check(stableKey(attributes(parent, part)) == expected.key)
                        parent = secure(parent.newDirectoryStream(part, NOFOLLOW_LINKS)).also(opened::add)
                        check(parent.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes().fileKey()?.toString() == expected.key)
                    }
                }
                val name = identity.path.fileName
                check(stableKey(attributes(parent, name)) == identity.key)
                secure(parent.newDirectoryStream(name, NOFOLLOW_LINKS)).use { directory ->
                    check(directory.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes().fileKey()?.toString() == identity.key)
                    visited = 1
                    val entries = inventory(directory, 0)
                    verify(identity)
                    delete(directory, entries)
                }
                verify(identity)
                check(stableKey(attributes(parent, name)) == identity.key)
                parent.deleteDirectory(name)
                deleted++
                val absent = try { attributes(parent, name); false } catch (_: NoSuchFileException) { true }
                check(absent && !Files.exists(identity.path, NOFOLLOW_LINKS)) { "Owned scratch root did not disappear" }
                requireFixtureDirectory(outerIdentity)
                retired.add(identity.path)
            } finally { opened.asReversed().forEach { it.close() } }
            return CompilerFixtureCleanupReceipt(identity.path, identity.key, true, visited, deleted, maxEntries = maxEntries, maxDepth = maxDepth)
        } catch (failure: Exception) {
            return CompilerFixtureCleanupReceipt(identity.path, identity.key, false, visited, deleted,
                failure.javaClass.name, failure.message?.take(1_024), maxEntries, maxDepth)
        }
    }

    companion object {
        const val MAX_ENTRIES = 20_000
        const val MAX_DEPTH = 32
        fun create(parent: Path, prefix: String, maxEntries: Int = MAX_ENTRIES, maxDepth: Int = MAX_DEPTH): CompilerFixtureScratch {
            require(maxEntries in 1..MAX_ENTRIES && maxDepth in 1..MAX_DEPTH)
            val outer = fixtureDirectoryIdentity(CompilerFixtureProvenance.canonicalOuterDirectory(parent))
            val path = Files.createTempDirectory(outer.path, prefix)
            val identity = fixtureDirectoryIdentity(path)
            privateFixturePermissions(path)
            return CompilerFixtureScratch(identity, outer, maxEntries, maxDepth)
        }
    }
}

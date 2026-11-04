package dev.ktchecker.snapshot

import java.text.Normalizer
import java.util.Locale

/** A portable logical namespace, deliberately stricter than any individual host filesystem. */
internal object SnapshotPaths {
    private val windowsDevice = Regex("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")
    private val rootName = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")

    fun rootId(id: String) {
        if (!rootName.matches(id)) fail("invalid_root", "Invalid snapshot root ID")
    }

    fun identifier(value: String, label: String) {
        if (value.isBlank() || value.length > 256 || value.any { it.isISOControl() }) {
            fail("invalid_identity", "$label must be nonempty and at most 256 characters without control bytes")
        }
    }

    fun relative(value: String, allowEmpty: Boolean = false): String {
        if (allowEmpty && value.isEmpty()) return value
        if (value.isEmpty() || value.toByteArray(Charsets.UTF_8).size > 4096 || value.startsWith('/')) {
            fail("unsafe_path", "Snapshot paths must be bounded relative paths")
        }
        if (!Normalizer.isNormalized(value, Normalizer.Form.NFC)) {
            fail("noncanonical_path", "Snapshot paths must use NFC Unicode normalization")
        }
        value.split('/').forEach { segment ->
            if (segment.isEmpty() || segment in setOf(".", "..") ||
                segment.any { it.isISOControl() || it in "\\:*?\"<>|" } || hasUnpairedSurrogate(segment) ||
                segment.endsWith('.') || segment.endsWith(' ') ||
                segment.toByteArray(Charsets.UTF_8).size > 255 || windowsDevice.matches(segment)) {
                fail("unsafe_path", "Snapshot path contains a nonportable or escaping component")
            }
        }
        return value
    }

    fun key(path: SnapshotPath): String = path.rootId + "\u0000" + path.path
    fun key(entry: SnapshotEntry): String = entry.rootId + "\u0000" + entry.path
    fun decodeKey(value: String): SnapshotPath {
        val split = value.indexOf('\u0000')
        return SnapshotPath(value.substring(0, split), value.substring(split + 1))
    }

    fun validate(path: SnapshotPath, roots: List<SnapshotRoot>) {
        rootId(path.rootId)
        if (roots.none { it.id == path.rootId }) fail("unregistered_root", "Snapshot change names an unregistered root")
        relative(path.path)
    }

    fun mounted(entry: SnapshotEntry, roots: List<SnapshotRoot>): String {
        val mount = roots.single { it.id == entry.rootId }.mountPath
        return if (mount.isEmpty()) entry.path else "$mount/${entry.path}"
    }

    /** Link target text may use .. only when it remains inside the same registered root. */
    fun linkTarget(linkPath: String, target: String): String {
        if (target.isEmpty() || target.startsWith('/') || target.contains('\\') || target.contains(':') ||
            target.toByteArray(Charsets.UTF_8).size > 4096 || target.any { it.isISOControl() }) {
            fail("symlink_escape", "Symlink target must be a relative path within its captured root")
        }
        val components = linkPath.split('/').dropLast(1).toMutableList()
        target.split('/').forEach { component ->
            when (component) {
                "." -> Unit
                ".." -> {
                    if (components.isEmpty()) fail("symlink_escape", "Symlink target escapes its captured root")
                    components.removeAt(components.lastIndex)
                }
                "" -> fail("unsafe_path", "Symlink target contains an empty path component")
                else -> { relative(component); components.add(component) }
            }
        }
        return relative(components.joinToString("/"))
    }

    fun overlapping(a: SnapshotPath, b: SnapshotPath): Boolean = a.rootId == b.rootId &&
        (a.path == b.path || a.path.startsWith(b.path + "/") || b.path.startsWith(a.path + "/"))

    fun validateManifest(entries: List<SnapshotEntry>, roots: List<SnapshotRoot>, limits: SnapshotLimits) {
        if (entries.size > limits.maxFiles) fail("snapshot_too_large", "Snapshot file count exceeds configured limit")
        var bytes = 0L
        val namespace = mutableMapOf<String, String>()
        val filePaths = mutableSetOf<String>()
        val byKey = mutableMapOf<String, SnapshotEntry>()
        entries.forEach { entry ->
            validate(SnapshotPath(entry.rootId, entry.path), roots)
            if (byKey.put(key(entry), entry) != null) fail("duplicate_path", "Snapshot contains duplicate logical paths")
            if (entry.size < 0 || entry.size > limits.maxBlobBytes || entry.size > limits.maxSnapshotBytes - bytes) {
                fail("snapshot_too_large", "Snapshot byte count exceeds configured limit")
            }
            bytes += entry.size
            when (entry.kind) {
                SnapshotEntryKind.FILE -> if (entry.digest == null || !dev.ktchecker.contracts.Digests.valid(entry.digest) ||
                    entry.symlinkTarget != null) fail("invalid_entry", "File entry requires a valid byte digest and no symlink target")
                SnapshotEntryKind.SYMLINK -> {
                    if (entry.digest != null || entry.symlinkTarget == null || entry.executable || entry.size != 0L) {
                        fail("invalid_entry", "Symlink entry has contradictory metadata")
                    }
                    linkTarget(entry.path, entry.symlinkTarget)
                }
            }
            val mounted = mounted(entry, roots)
            relative(mounted)
            var prefix = ""
            mounted.split('/').forEach { component ->
                prefix = if (prefix.isEmpty()) component else "$prefix/$component"
                // Upper/lower also conservatively catches expanding folds such as sharp-s/SS.
                val folded = Normalizer.normalize(prefix.uppercase(Locale.ROOT).lowercase(Locale.ROOT), Normalizer.Form.NFC)
                val previous = namespace.putIfAbsent(folded, prefix)
                if (previous != null && previous != prefix) {
                    fail("case_collision", "Snapshot paths collide under case-insensitive materialization")
                }
            }
            if (!filePaths.add(mounted)) fail("mount_collision", "Registered root mounts produce duplicate physical paths")
        }
        filePaths.forEach { path ->
            var ancestor = path.substringBeforeLast('/', "")
            while (ancestor.isNotEmpty()) {
                if (ancestor in filePaths) fail("file_directory_collision", "A snapshot file is also used as a directory")
                ancestor = ancestor.substringBeforeLast('/', "")
            }
        }
        var expandedBytes = bytes
        entries.filter { it.kind == SnapshotEntryKind.SYMLINK }.forEach { link ->
            val seen = mutableSetOf<String>()
            var current = link
            while (current.kind == SnapshotEntryKind.SYMLINK) {
                if (!seen.add(key(current))) fail("symlink_cycle", "Snapshot contains a symlink cycle")
                if (seen.size > 64) fail("symlink_depth", "Snapshot symlink chain exceeds supported depth")
                val target = linkTarget(current.path, requireNotNull(current.symlinkTarget))
                current = byKey[key(SnapshotPath(current.rootId, target))]
                    ?: fail("symlink_target_missing", "Symlink target is not a captured regular file")
            }
            if (current.size > limits.maxSnapshotBytes - expandedBytes) {
                fail("snapshot_too_large", "Dereferenced symlink inputs exceed the configured snapshot byte limit")
            }
            expandedBytes += current.size
        }
    }

    private fun hasUnpairedSurrogate(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val character = value[index++]
            if (character.isLowSurrogate()) return true
            if (character.isHighSurrogate()) {
                if (index == value.length || !value[index].isLowSurrogate()) return true
                index++
            }
        }
        return false
    }

    private fun fail(code: String, message: String): Nothing = throw SnapshotException(code, message)
}

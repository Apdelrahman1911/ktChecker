package dev.ktchecker.compiler

import dev.ktchecker.contracts.Digests
import dev.ktchecker.providers.runtime.OwnedSupervisorIdentity
import dev.ktchecker.providers.runtime.strictUtf8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.zip.ZipFile

/** Fixture-only, byte-only reader of the native helper's exact installed CodeSource. */
internal class CompilerFixtureSupervisorResources private constructor(private val suppliedSource: Path) {
    val codeSource: Path = canonicalSource(suppliedSource)
    val kind: String = if (Files.isDirectory(codeSource, NOFOLLOW_LINKS)) "development_resources" else "jar_entry"
    private val originStamp = stamp(codeSource)

    init {
        if (kind == "development_resources") {
            require(codeSource.endsWith(Path.of("build/classes/kotlin/main"))) {
                "Native evidence supports only the runtime's literal development CodeSource mapping"
            }
        } else require(codeSource.toString().endsWith(".jar") && Files.size(codeSource) in 1..MAX_JAR_BYTES.toLong()) {
            "Native evidence requires an ordinary bounded installed JAR"
        }
    }

    fun installedOrigin(name: String): Path {
        maximum(name)
        requireOriginStable()
        return if (kind == "jar_entry") codeSource else developmentPath(name)
    }

    fun read(name: String): ByteArray {
        val bound = maximum(name)
        requireOriginStable()
        val bytes = if (kind == "development_resources") {
            CompilerFixtureProvenance.readStable(developmentPath(name), bound)
        } else {
            val before = stamp(codeSource)
            require(Files.size(codeSource) in 1..MAX_JAR_BYTES.toLong())
            val value = ZipFile(codeSource.toFile()).use { archive ->
                require(archive.size() <= MAX_JAR_ENTRIES) { "Native evidence JAR exceeds entry bound" }
                var selected: java.util.zip.ZipEntry? = null
                val entries = archive.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name == name) {
                        require(selected == null) { "Ambiguous native evidence resource in installed JAR" }
                        selected = entry
                    }
                }
                val entry = requireNotNull(selected) { "Native evidence resource is absent from exact installed JAR" }
                require(!entry.isDirectory && entry.size in 1..bound.toLong()) { "Native evidence resource exceeds byte bound" }
                archive.getInputStream(entry).use { it.readNBytes(bound + 1) }.also {
                    require(it.size.toLong() == entry.size) { "Native evidence JAR resource size changed" }
                }
            }
            check(stamp(codeSource) == before) { "Native evidence JAR changed while reading" }
            value
        }
        require(bytes.isNotEmpty() && bytes.size <= bound) { "Native evidence resource exceeds byte bound" }
        requireOriginStable()
        return bytes
    }

    private fun developmentPath(name: String): Path {
        val build = codeSource.parent.parent.parent
        val path = build.resolve("resources/main").resolve(name)
        var current = build
        for (part in build.relativize(path)) {
            current = current.resolve(part)
            require(!Files.isSymbolicLink(current) && current.toRealPath() == current) { "Linked native evidence resource descendant" }
            require(if (current == path) Files.isRegularFile(current, NOFOLLOW_LINKS) else Files.isDirectory(current, NOFOLLOW_LINKS)) {
                "Unsafe native evidence resource descendant"
            }
        }
        return path
    }

    private fun requireOriginStable() {
        check(canonicalSource(suppliedSource) == codeSource && stamp(codeSource) == originStamp) {
            "Native evidence installed CodeSource changed after START"
        }
    }

    companion object {
        const val PREFIX = "dev/ktchecker/providers/runtime/native/"
        const val MANIFEST = PREFIX + "manifest"
        const val BUILD_PROVENANCE = PREFIX + "build-provenance.json"
        const val PROTOCOL = "ktchecker-owned-child-supervisor/preview-1"
        const val MAX_MANIFEST_BYTES = 512
        const val MAX_BINARY_BYTES = 1_048_576
        const val MAX_BUILD_PROVENANCE_BYTES = 16_384
        const val MAX_JAR_BYTES = 33_554_432
        const val MAX_JAR_ENTRIES = 100_000
        private val TARGETS = setOf("linux-x86_64", "linux-aarch64", "macos-x86_64", "macos-aarch64")

        fun installed(type: Class<*>): CompilerFixtureSupervisorResources {
            val source = requireNotNull(type.protectionDomain.codeSource?.location) { "Missing native helper CodeSource" }
            require(source.protocol == "file" && source.query == null && source.ref == null) { "Unsupported native helper CodeSource" }
            return fromCodeSource(Path.of(source.toURI()))
        }

        /** Internal synthetic filesystem seam; never supplies a production runtime resource root. */
        internal fun fromCodeSource(source: Path) = CompilerFixtureSupervisorResources(source.toAbsolutePath())

        fun binaryName(identity: OwnedSupervisorIdentity): String {
            require(identity.target in TARGETS)
            return PREFIX + identity.target + "/supervisor"
        }

        fun identity(bytes: ByteArray, expectedTarget: String = hostTarget()): OwnedSupervisorIdentity {
            require(bytes.isNotEmpty() && bytes.size <= MAX_MANIFEST_BYTES)
            val lines = strictUtf8(bytes).split('\n')
            require(lines.size == 5 && lines.last().isEmpty() && lines[0] == PROTOCOL &&
                lines[1] in TARGETS && lines[1] == expectedTarget && Digests.valid(lines[2]) && Digests.valid(lines[3])) {
                "Native evidence requires a supported four-line LF-terminated installed manifest"
            }
            return OwnedSupervisorIdentity(lines[0], lines[1], lines[2], lines[3])
        }

        private fun maximum(name: String): Int = when (name) {
            MANIFEST -> MAX_MANIFEST_BYTES
            BUILD_PROVENANCE -> MAX_BUILD_PROVENANCE_BYTES
            else -> {
                require(TARGETS.any { name == PREFIX + it + "/supervisor" }) { "Unowned native evidence resource name" }
                MAX_BINARY_BYTES
            }
        }

        private fun hostTarget(): String {
            val cpu = when (System.getProperty("os.arch", "").lowercase(Locale.ROOT)) {
                "amd64", "x86_64" -> "x86_64"
                "aarch64", "arm64" -> "aarch64"
                else -> "unsupported"
            }
            val os = when (System.getProperty("os.name", "").lowercase(Locale.ROOT)) {
                "linux" -> "linux"
                "mac os x", "darwin", "macos" -> "macos"
                else -> "unsupported"
            }
            return "$os-$cpu"
        }

        private fun canonicalSource(source: Path): Path {
            require(source.isAbsolute && source.none { it.toString() in setOf(".", "..") } && !Files.isSymbolicLink(source)) {
                "Unsafe native evidence CodeSource selection"
            }
            val parent = requireNotNull(source.parent).toRealPath()
            return parent.resolve(source.fileName).also {
                require(!Files.isSymbolicLink(it) && (Files.isDirectory(it, NOFOLLOW_LINKS) || Files.isRegularFile(it, NOFOLLOW_LINKS)) &&
                    it.toRealPath() == it) { "Unsafe native evidence CodeSource leaf" }
            }
        }

        private fun stamp(path: Path): List<Any?> = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).let {
            listOf(it.fileKey(), it.size(), it.lastModifiedTime(), it.creationTime(), it.isRegularFile, it.isDirectory, it.isSymbolicLink)
        }
    }
}

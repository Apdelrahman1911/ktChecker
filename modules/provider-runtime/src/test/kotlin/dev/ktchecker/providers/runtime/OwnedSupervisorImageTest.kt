package dev.ktchecker.providers.runtime

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*
import org.junit.jupiter.api.Assumptions.assumeTrue

class OwnedSupervisorImageTest {
    private val temporary = mutableListOf<Path>()
    private val target = "linux-x86_64"
    private val sourceDigest = "sha256:" + "a".repeat(64)
    private val binary = "not executed: byte-only image fixture".toByteArray()

    @AfterTest fun cleanup() {
        for (root in temporary.reversed()) {
            if (!Files.exists(root, NOFOLLOW_LINKS)) continue
            Files.walk(root).use { paths ->
                val selected = paths.toList()
                selected.filter { Files.isDirectory(it, NOFOLLOW_LINKS) }.forEach {
                    runCatching { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------")) }
                }
                selected.sortedByDescending { it.nameCount }.forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun directory(): Path = Files.createTempDirectory("ktchecker-supervisor-image-test-").also { temporary.add(it) }
    private fun posixDirectory(): Path {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Private POSIX image extraction is unavailable on Windows")
        return directory()
    }
    private fun moveProtectedImageRoot(root: Path, destination: Path) {
        // Stage the fixture mutation without relying on a privileged user's permission bypass.
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"))
        Files.move(root, destination)
        Files.setPosixFilePermissions(destination, PosixFilePermissions.fromString("r-x------"))
    }
    private fun manifest(content: ByteArray = binary, platform: String = target, digest: String = supervisorDigest(content)): ByteArray =
        "${SupervisorBundleFormat.VERSION}\n$platform\n$sourceDigest\n$digest\n".toByteArray()

    private fun resources(manifestBytes: ByteArray = manifest(), binaryBytes: ByteArray? = binary): SupervisorResources =
        SupervisorResources { name, _ ->
            when (name) {
                SupervisorBundleFormat.MANIFEST -> manifestBytes
                SupervisorBundleFormat.PREFIX + target + "/supervisor" -> binaryBytes
                else -> null
            }
        }

    private fun bundle(manifestBytes: ByteArray = manifest(), binaryBytes: ByteArray? = binary): VerifiedSupervisorBundle =
        SupervisorImageLoader(target) { resources(manifestBytes, binaryBytes) }.load()

    private fun fails(code: String, block: () -> Unit) {
        val error = assertFailsWith<ExecutionProblem> { block() }
        assertEquals(code, error.code)
        assertFalse(error.message.orEmpty().contains("ktchecker-supervisor-image-test-"))
    }

    @Test fun `inspector reports exact verified bundle without extracting or executing`() {
        val result = SupervisorImageLoader(target) { resources() }.inspect()
        assertTrue(result.available)
        assertEquals("available", result.status)
        assertEquals(OwnedSupervisorIdentity(SupervisorBundleFormat.VERSION, target, sourceDigest, supervisorDigest(binary)), result.identity)
        assertTrue(result.reasons.isEmpty())
    }

    @Test fun `Windows and unknown platforms do not search for substitute helpers`() {
        val calls = AtomicInteger()
        for (platform in listOf("windows-x86_64", "windows-aarch64", "unsupported", "linux-mips")) {
            val result = SupervisorImageLoader(platform) { calls.incrementAndGet(); resources() }.inspect()
            assertEquals("unavailable", result.status)
            assertEquals(listOf("process_group_containment_unavailable"), result.reasons)
            assertNull(result.identity)
        }
        assertEquals(0, calls.get())
    }

    @Test fun `platform aliases resolve to only the frozen supported targets`() {
        assertEquals(target, SupervisorBundleFormat.target("Linux", "amd64"))
        assertEquals("linux-aarch64", SupervisorBundleFormat.target("Linux", "arm64"))
        assertEquals("macos-aarch64", SupervisorBundleFormat.target("Mac OS X", "aarch64"))
        assertEquals("macos-x86_64", SupervisorBundleFormat.target("Darwin", "x86_64"))
        assertEquals("windows-x86_64", SupervisorBundleFormat.target("Windows 11", "amd64"))
        assertEquals("unsupported", SupervisorBundleFormat.target("Linux", "x86"))
        assertEquals("unsupported", SupervisorBundleFormat.target("Other", "aarch64"))
    }

    @Test fun `missing manifest missing binary and explicit unavailable fail closed`() {
        val absent = SupervisorImageLoader(target) { SupervisorResources { _, _ -> null } }.inspect()
        assertEquals(listOf("process_group_containment_unavailable"), absent.reasons)
        fails("process_group_containment_unavailable") { bundle(binaryBytes = null) }
        fails("process_group_containment_unavailable") { bundle(manifest(digest = "unavailable")) }
    }

    @Test fun `manifest requires exact version target lowercase digests and four LF lines`() {
        val normal = manifest().toString(Charsets.UTF_8)
        val variants = listOf(
            normal.trimEnd(), normal + "\n", normal.replace("\n", "\r\n"), "\uFEFF$normal",
            normal.replace(SupervisorBundleFormat.VERSION, "ktchecker-owned-child-supervisor/preview-2"),
            normal.replace(target, "macos-x86_64"), normal.replace(sourceDigest, sourceDigest.uppercase()),
            normal.replace(sourceDigest, "sha256:not-a-digest"), normal.replace("sha256:", "SHA256:"),
            normal.replace(supervisorDigest(binary), "unavailable "), normal.replace(target, "$target\u0000"),
        )
        for (variant in variants) fails("process_supervisor_manifest_invalid") { bundle(variant.toByteArray()) }
        fails("process_supervisor_manifest_invalid") { bundle(byteArrayOf(0xc3.toByte(), 0x28)) }
    }

    @Test fun `manifest and binary sizes are independently bounded`() {
        fails("process_supervisor_manifest_invalid") { bundle(ByteArray(513)) }
        fails("process_supervisor_binary_invalid") { bundle(manifest(byteArrayOf()), byteArrayOf()) }
        val huge = ByteArray(SupervisorBundleFormat.MAX_BINARY + 1)
        fails("process_supervisor_binary_invalid") { bundle(manifest(huge), huge) }
    }

    @Test fun `changed binary cannot borrow a matching manifest from other bytes`() {
        fails("process_supervisor_binary_digest_mismatch") { bundle(binaryBytes = binary + 1) }
    }

    @Test fun `manifest change during image read is not a consistent bundle`() {
        val calls = AtomicInteger()
        val changing = SupervisorResources { name, _ ->
            if (name.endsWith("manifest")) {
                if (calls.incrementAndGet() == 1) manifest() else manifest().toString(Charsets.UTF_8).replace(sourceDigest, "sha256:" + "b".repeat(64)).toByteArray()
            } else binary
        }
        fails("process_supervisor_bundle_changed") { SupervisorImageLoader(target) { changing }.load() }
    }

    @Test fun `extracted bytes are detached privately read only and freshly verified`() {
        val original = binary.clone()
        val selected = bundle(manifest(original), original)
        original.fill(0)
        selected.extract(posixDirectory()).use { image ->
            assertContentEquals(binary, Files.readAllBytes(image.executable))
            assertEquals(PosixFilePermissions.fromString("r-x------"), Files.getPosixFilePermissions(image.root, NOFOLLOW_LINKS))
            assertEquals(PosixFilePermissions.fromString("r-x------"), Files.getPosixFilePermissions(image.executable, NOFOLLOW_LINKS))
            image.verify()
            assertFalse(Files.isSymbolicLink(image.executable))
        }
    }

    @Test fun `same length and mtime rewrites fail the fresh byte hash`() {
        val image = bundle().extract(posixDirectory())
        val modified = Files.getLastModifiedTime(image.executable)
        Files.setPosixFilePermissions(image.executable, PosixFilePermissions.fromString("rw-------"))
        Files.write(image.executable, ByteArray(binary.size) { 7 })
        Files.setLastModifiedTime(image.executable, modified)
        Files.setPosixFilePermissions(image.executable, PosixFilePermissions.fromString("r-x------"))
        fails("process_supervisor_image_changed") { image.verify() }
        assertTrue(image.closeSafely())
        assertFalse(Files.exists(image.root))
    }

    @Test fun `permission change is rejected and close is idempotent`() {
        val image = bundle().extract(posixDirectory())
        Files.setPosixFilePermissions(image.root, PosixFilePermissions.fromString("rwx------"))
        fails("process_supervisor_image_changed") { image.verify() }
        image.close()
        image.close()
        fails("process_supervisor_image_changed") { image.verify() }
    }

    @Test fun `unexpected entries are not deleted recursively`() {
        val image = bundle().extract(posixDirectory())
        Files.setPosixFilePermissions(image.root, PosixFilePermissions.fromString("rwx------"))
        val other = image.root.resolve("not-owned")
        Files.writeString(other, "keep")
        Files.setPosixFilePermissions(image.root, PosixFilePermissions.fromString("r-x------"))
        fails("process_supervisor_image_changed") { image.verify() }
        assertFalse(image.closeSafely())
        assertEquals("keep", Files.readString(other))
        Files.delete(other)
        assertTrue(image.closeSafely())
    }

    @Test fun `replaced leaf link does not cause outside reads or deletes`() {
        val parent = posixDirectory()
        val outside = parent.resolve("outside")
        Files.writeString(outside, "keep")
        val image = bundle().extract(parent)
        Files.setPosixFilePermissions(image.root, PosixFilePermissions.fromString("rwx------"))
        Files.delete(image.executable)
        Files.createSymbolicLink(image.executable, outside)
        Files.setPosixFilePermissions(image.root, PosixFilePermissions.fromString("r-x------"))
        fails("process_supervisor_image_changed") { image.verify() }
        assertFalse(image.closeSafely())
        assertEquals("keep", Files.readString(outside))
        assertTrue(Files.isSymbolicLink(image.executable))
    }

    @Test fun `replaced root is not treated as this invocation's cleanup directory`() {
        val parent = posixDirectory()
        val image = bundle().extract(parent)
        moveProtectedImageRoot(image.root, parent.resolve("original-image"))
        Files.createDirectory(image.root)
        val other = image.root.resolve("other")
        Files.writeString(other, "keep")
        Files.setPosixFilePermissions(image.root, PosixFilePermissions.fromString("r-x------"))
        fails("process_supervisor_image_changed") { image.verify() }
        assertFalse(image.closeSafely())
        assertEquals(PosixFilePermissions.fromString("r-x------"), Files.getPosixFilePermissions(image.root, NOFOLLOW_LINKS))
        assertEquals("keep", Files.readString(other))
    }

    @Test fun `missing owned root or binary is image drift not a missing installed bundle`() {
        val parent = posixDirectory()
        val missingRoot = bundle().extract(parent)
        moveProtectedImageRoot(missingRoot.root, parent.resolve("saved-image"))
        assertFalse(Files.exists(missingRoot.root, NOFOLLOW_LINKS))
        fails("process_supervisor_image_changed") { missingRoot.verify() }
        assertFalse(missingRoot.closeSafely())

        val missingBinary = bundle().extract(parent)
        Files.setPosixFilePermissions(missingBinary.root, PosixFilePermissions.fromString("rwx------"))
        Files.delete(missingBinary.executable)
        Files.setPosixFilePermissions(missingBinary.root, PosixFilePermissions.fromString("r-x------"))
        fails("process_supervisor_image_changed") { missingBinary.verify() }
        assertTrue(missingBinary.closeSafely())
    }

    private fun development(): Pair<Path, Path> {
        val module = directory().resolve("module")
        val classes = Files.createDirectories(module.resolve("build/classes/kotlin/main"))
        val resourceRoot = Files.createDirectories(module.resolve("build/resources/main"))
        return classes to resourceRoot
    }

    private fun writeResources(root: Path) {
        val manifestPath = root.resolve(SupervisorBundleFormat.MANIFEST)
        Files.createDirectories(manifestPath.parent)
        Files.write(manifestPath, manifest())
        val helper = root.resolve(SupervisorBundleFormat.PREFIX + target + "/supervisor")
        Files.createDirectories(helper.parent)
        Files.write(helper, binary)
    }

    @Test fun `development mapping is the owning module's exact sibling resources root`() {
        val (classes, resources) = development()
        writeResources(resources)
        val image = SupervisorImageLoader(target) { supervisorCodeSourceResources(classes) }.load()
        assertEquals(supervisorDigest(binary), image.identity.binaryDigest)
    }

    @Test fun `arbitrary resource directory cannot masquerade as development code origin`() {
        val root = directory()
        writeResources(root)
        fails("process_supervisor_bundle_origin_unavailable") { supervisorCodeSourceResources(root) }
    }

    @Test fun `missing development resources do not search another classpath directory`() {
        val (classes, _) = development()
        val decoy = directory()
        writeResources(decoy)
        fails("process_group_containment_unavailable") { SupervisorImageLoader(target) { supervisorCodeSourceResources(classes) }.load() }
    }

    @Test fun `development leaf and parent resource links are rejected`() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"))
        val (classes, resources) = development()
        writeResources(resources)
        val manifest = resources.resolve(SupervisorBundleFormat.MANIFEST)
        val saved = resources.resolve("saved-manifest")
        Files.move(manifest, saved)
        Files.createSymbolicLink(manifest, saved)
        fails("process_supervisor_bundle_origin_unavailable") { SupervisorImageLoader(target) { supervisorCodeSourceResources(classes) }.load() }
        Files.delete(manifest)
        Files.move(saved, manifest)
        val native = manifest.parent
        val renamed = native.resolveSibling("native-saved")
        Files.move(native, renamed)
        Files.createSymbolicLink(native, renamed)
        fails("process_supervisor_bundle_origin_unavailable") { SupervisorImageLoader(target) { supervisorCodeSourceResources(classes) }.load() }
    }

    private fun jar(entries: Map<String, ByteArray>): Path {
        val jar = directory().resolve("provider-runtime.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { output ->
            for ((name, bytes) in entries) {
                output.putNextEntry(ZipEntry(name)); output.write(bytes); output.closeEntry()
            }
        }
        return jar
    }

    @Test fun `packaged lookup uses exact selected JAR not a context classloader resource`() {
        val selected = jar(mapOf(SupervisorBundleFormat.MANIFEST to manifest(),
            SupervisorBundleFormat.PREFIX + target + "/supervisor" to binary))
        val before = Thread.currentThread().contextClassLoader
        val loads = AtomicInteger()
        Thread.currentThread().contextClassLoader = object : ClassLoader(before) {
            override fun getResourceAsStream(name: String): InputStream { loads.incrementAndGet(); return ByteArrayInputStream(byteArrayOf(7)) }
        }
        try {
            val image = SupervisorImageLoader(target) { supervisorCodeSourceResources(selected) }.load()
            assertEquals(supervisorDigest(binary), image.identity.binaryDigest)
            assertEquals(0, loads.get())
        } finally { Thread.currentThread().contextClassLoader = before }
    }

    @Test fun `oversized archive entry is rejected before decompression`() {
        val selected = jar(mapOf(SupervisorBundleFormat.MANIFEST to ByteArray(513)))
        fails("process_supervisor_bundle_limit") { supervisorCodeSourceResources(selected).read(SupervisorBundleFormat.MANIFEST, 512) }
    }

    @Test fun `duplicate exact archive resource names are ambiguous`() {
        val name = SupervisorBundleFormat.MANIFEST
        val other = name.dropLast(1) + "X"
        val selected = jar(linkedMapOf(name to manifest(), other to manifest()))
        val bytes = Files.readAllBytes(selected)
        val old = other.toByteArray()
        val replacement = name.toByteArray()
        for (offset in 0..bytes.size - old.size) {
            if (old.indices.all { bytes[offset + it] == old[it] }) replacement.copyInto(bytes, offset)
        }
        Files.write(selected, bytes)
        fails("process_supervisor_bundle_origin_ambiguous") { supervisorCodeSourceResources(selected).read(name, 512) }
    }

    @Test fun `leaf code source link and lexical parent bypass are rejected`() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"))
        val selected = jar(mapOf(SupervisorBundleFormat.MANIFEST to manifest()))
        val link = selected.resolveSibling("alias.jar")
        Files.createSymbolicLink(link, selected)
        fails("process_supervisor_bundle_origin_unavailable") { supervisorCodeSourceResources(link) }
        fails("process_supervisor_bundle_origin_unavailable") { supervisorCodeSourceResources(selected.parent.resolve("child/../provider-runtime.jar")) }
    }

    @Test fun `trusted ancestor aliases are pinned and retargeting is detected`() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"))
        val first = jar(mapOf(SupervisorBundleFormat.MANIFEST to manifest()))
        val second = jar(mapOf(SupervisorBundleFormat.MANIFEST to manifest()))
        val alias = directory().resolve("installation")
        Files.createSymbolicLink(alias, first.parent)
        val source = supervisorCodeSourceResources(alias.resolve("provider-runtime.jar"))
        assertContentEquals(manifest(), source.read(SupervisorBundleFormat.MANIFEST, 512))
        Files.delete(alias)
        Files.createSymbolicLink(alias, second.parent)
        fails("process_supervisor_bundle_changed") { source.read(SupervisorBundleFormat.MANIFEST, 512) }
    }

    @Test fun `bounded readers allow exact bound and reject excess or no progress`() {
        assertContentEquals(binary, supervisorReadBounded(ByteArrayInputStream(binary), binary.size))
        fails("process_supervisor_bundle_limit") { supervisorReadBounded(ByteArrayInputStream(binary), binary.size - 1) }
        val stalled = object : InputStream() {
            override fun read(): Int = 0
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = 0
        }
        fails("process_supervisor_bundle_unavailable") { supervisorReadBounded(stalled, 10) }
    }
}

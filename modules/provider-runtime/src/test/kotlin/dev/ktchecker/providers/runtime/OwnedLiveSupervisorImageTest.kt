package dev.ktchecker.providers.runtime

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Byte-only image tests. These fixtures are deliberately not executable programs. */
class OwnedLiveSupervisorImageTest {
    private val temporary = ArrayList<Path>()
    private val target = "linux-x86_64"
    private val binary = "never executed: v2 image fixture".toByteArray()
    private val sourceDigest = "sha256:" + "a".repeat(64)

    @AfterTest fun removeOnlyTestDirectories() {
        for (root in temporary.reversed()) {
            if (!Files.exists(root, NOFOLLOW_LINKS)) continue
            Files.walk(root).use { stream ->
                val paths = stream.toList()
                paths.filter { Files.isDirectory(it, NOFOLLOW_LINKS) }.forEach {
                    if (Files.getFileAttributeView(it, PosixFileAttributeView::class.java, NOFOLLOW_LINKS) != null) {
                        Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------"))
                    }
                }
                paths.sortedByDescending { it.nameCount }.forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun directory(): Path = Files.createTempDirectory("ktchecker-live-image-test-").also { temporary.add(it) }

    private fun posixDirectory(): Path = directory().also {
        assumeTrue(Files.getFileAttributeView(it, PosixFileAttributeView::class.java, NOFOLLOW_LINKS) != null,
            "Private image extraction requires POSIX attributes; byte-only loader tests remain applicable")
    }

    private fun manifest(version: String = OwnedLiveSupervisorBundleFormat.VERSION, platform: String = target,
                         digest: String = supervisorDigest(binary)): ByteArray =
        "$version\n$platform\n$sourceDigest\n$digest\n".toByteArray()

    private fun resources(manifestBytes: ByteArray? = manifest(), binaryBytes: ByteArray? = binary): SupervisorResources =
        SupervisorResources { name, maximum ->
            when (name) {
                OwnedLiveSupervisorBundleFormat.MANIFEST -> { assertEquals(512, maximum); manifestBytes }
                OwnedLiveSupervisorBundleFormat.PREFIX + target + "/supervisor" -> {
                    assertEquals(1_048_576, maximum); binaryBytes
                }
                else -> fail("Unexpected resource name in a byte-only test")
            }
        }

    private fun bundle(manifestBytes: ByteArray = manifest(), binaryBytes: ByteArray? = binary): VerifiedSupervisorBundle =
        OwnedLiveSupervisorImageLoader(target) { resources(manifestBytes, binaryBytes) }.load()

    private fun unavailable(block: () -> Unit) {
        val error = assertFailsWith<ExecutionProblem> { block() }
        assertEquals(OwnedSessionReason.BUNDLE_UNAVAILABLE.code, error.code)
    }

    @Test fun `byte-only inspection binds exact v2 protocol target manifest claim and fresh binary digest`() {
        val result = OwnedLiveSupervisorImageLoader(target) { resources() }.inspect()
        assertTrue(result.available)
        assertEquals(OwnedSupervisorIdentity(OwnedLiveSupervisorBundleFormat.VERSION, target, sourceDigest, supervisorDigest(binary)), result.identity)
        assertEquals(target, result.target)
        assertTrue(result.reasons.isEmpty())
        assertFailsWith<UnsupportedOperationException> {
            (OwnedLiveSupervisorBundleFormat.targets as MutableSet<String>).add("macos-x86_64")
        }
    }

    @Test fun `nonLinux and unknown targets reject before resource access and never substitute v1`() {
        val calls = AtomicInteger()
        for (platform in listOf("macos-x86_64", "macos-aarch64", "windows-x86_64", "windows-aarch64", "unsupported", "linux-mips")) {
            val result = OwnedLiveSupervisorImageLoader(platform) { calls.incrementAndGet(); resources() }.inspect()
            assertFalse(result.available)
            assertNull(result.identity)
            assertEquals(listOf(OwnedSessionReason.BUNDLE_UNAVAILABLE.code), result.reasons)
        }
        assertEquals(0, calls.get())
        assertEquals(setOf("linux-x86_64", "linux-aarch64"), OwnedLiveSupervisorBundleFormat.targets)
        assertTrue("macos-aarch64" in SupervisorBundleFormat.targets) // Existing v1 platform behavior is unchanged.
    }

    @Test fun `absent or explicit unavailable v2 resources never fall back to installed v1 names`() {
        val seen = ArrayList<String>()
        val v1Only = SupervisorResources { name, _ ->
            seen.add(name)
            when (name) {
                SupervisorBundleFormat.MANIFEST -> manifest(version = SupervisorBundleFormat.VERSION)
                SupervisorBundleFormat.PREFIX + target + "/supervisor" -> binary
                else -> null
            }
        }
        unavailable { OwnedLiveSupervisorImageLoader(target) { v1Only }.load() }
        assertEquals(listOf(OwnedLiveSupervisorBundleFormat.MANIFEST), seen)
        unavailable { bundle(binaryBytes = null) }
        unavailable { bundle(manifest(digest = "unavailable")) }
    }

    @Test fun `both versions reject the other protocol even at their own exact resource path`() {
        unavailable { bundle(manifest(version = SupervisorBundleFormat.VERSION)) }
        val v1Resource = SupervisorResources { name, _ ->
            when (name) {
                SupervisorBundleFormat.MANIFEST -> manifest()
                SupervisorBundleFormat.PREFIX + target + "/supervisor" -> binary
                else -> null
            }
        }
        val error = assertFailsWith<ExecutionProblem> { SupervisorImageLoader(target) { v1Resource }.load() }
        assertEquals("process_supervisor_manifest_invalid", error.code)
    }

    @Test fun `manifest requires exact lines strict UTF8 target and lowercase digest grammar`() {
        val normal = manifest().toString(Charsets.UTF_8)
        for (variant in listOf(
            normal.trimEnd(), normal + "\n", normal.replace("\n", "\r\n"), "\uFEFF$normal",
            normal.replace(target, "linux-aarch64"), normal.replace(target, "$target\u0000"),
            normal.replace(sourceDigest, sourceDigest.uppercase()), normal.replace(sourceDigest, "sha256:invalid"),
            normal.replace("sha256:", "SHA256:"), normal.replace(supervisorDigest(binary), "unavailable "),
        )) unavailable { bundle(variant.toByteArray()) }
        unavailable { bundle(byteArrayOf(0xc3.toByte(), 0x28)) }
    }

    @Test fun `manifest and binary independent maxima reject before trusting content or source claims`() {
        unavailable { bundle(ByteArray(513)) }
        unavailable { bundle(manifest(digest = supervisorDigest(byteArrayOf())), byteArrayOf()) }
        val huge = ByteArray(SupervisorBundleFormat.MAX_BINARY + 1)
        unavailable { bundle(manifest(digest = supervisorDigest(huge)), huge) }
        val changed = binary.clone().also { it[0] = (it[0].toInt() xor 1).toByte() }
        unavailable { bundle(binaryBytes = changed) }
    }

    @Test fun `same-size manifest change during binary reading fails its mandatory reread`() {
        val reads = AtomicInteger()
        val after = manifest().toString(Charsets.UTF_8).replace(sourceDigest, "sha256:" + "b".repeat(64)).toByteArray()
        val changing = SupervisorResources { name, _ ->
            if (name == OwnedLiveSupervisorBundleFormat.MANIFEST) {
                if (reads.incrementAndGet() == 1) manifest() else after
            } else binary
        }
        unavailable { OwnedLiveSupervisorImageLoader(target) { changing }.load() }
        assertEquals(2, reads.get())
    }

    @Test fun `verified v2 image reuses detached read-only exact-image extraction and safe deletion`() {
        val original = binary.clone()
        val selected = bundle(binaryBytes = original)
        original.fill(0)
        val image = selected.extract(posixDirectory())
        assertContentEquals(binary, Files.readAllBytes(image.executable))
        assertEquals(OwnedLiveSupervisorBundleFormat.VERSION, image.identity.protocolVersion)
        assertEquals(PosixFilePermissions.fromString("r-x------"), Files.getPosixFilePermissions(image.root, NOFOLLOW_LINKS))
        assertEquals(PosixFilePermissions.fromString("r-x------"), Files.getPosixFilePermissions(image.executable, NOFOLLOW_LINKS))
        image.verify()
        assertTrue(image.closeSafely())
        assertTrue(image.closeSafely())
        assertFalse(Files.exists(image.root, NOFOLLOW_LINKS))
    }

    @Test fun `same-length same-mtime v2 image rewrite fails fresh byte verification`() {
        val image = bundle().extract(posixDirectory())
        val before = Files.getLastModifiedTime(image.executable, NOFOLLOW_LINKS)
        Files.setPosixFilePermissions(image.executable, PosixFilePermissions.fromString("rw-------"))
        Files.write(image.executable, ByteArray(binary.size) { 3 })
        Files.setLastModifiedTime(image.executable, before)
        Files.setPosixFilePermissions(image.executable, PosixFilePermissions.fromString("r-x------"))
        assertEquals("process_supervisor_image_changed", assertFailsWith<ExecutionProblem> { image.verify() }.code)
        assertTrue(image.closeSafely())
    }

    @Test fun `unexpected entries and replaced v2 image links are not recursively followed or deleted`() {
        val parent = posixDirectory()
        val image = bundle().extract(parent)
        Files.setPosixFilePermissions(image.root, PosixFilePermissions.fromString("rwx------"))
        val other = image.root.resolve("not-owned")
        Files.writeString(other, "keep")
        Files.setPosixFilePermissions(image.root, PosixFilePermissions.fromString("r-x------"))
        assertFailsWith<ExecutionProblem> { image.verify() }
        assertFalse(image.closeSafely())
        assertEquals("keep", Files.readString(other))
        Files.delete(other)
        assertTrue(image.closeSafely())

        val outside = parent.resolve("outside")
        Files.writeString(outside, "keep")
        val replaced = bundle().extract(parent)
        Files.setPosixFilePermissions(replaced.root, PosixFilePermissions.fromString("rwx------"))
        Files.delete(replaced.executable)
        Files.createSymbolicLink(replaced.executable, outside)
        Files.setPosixFilePermissions(replaced.root, PosixFilePermissions.fromString("r-x------"))
        assertFailsWith<ExecutionProblem> { replaced.verify() }
        assertFalse(replaced.closeSafely())
        assertEquals("keep", Files.readString(outside))
        assertTrue(Files.isSymbolicLink(replaced.executable))
    }

    private fun entries(): Map<String, ByteArray> = linkedMapOf<String, ByteArray>().also { entries ->
        entries[SupervisorBundleFormat.MANIFEST] = manifest(version = SupervisorBundleFormat.VERSION)
        entries[OwnedLiveSupervisorBundleFormat.MANIFEST] = manifest()
        SupervisorBundleFormat.targets.forEach { entries[SupervisorBundleFormat.PREFIX + it + "/supervisor"] = binary }
        OwnedLiveSupervisorBundleFormat.targets.forEach { entries[OwnedLiveSupervisorBundleFormat.PREFIX + it + "/supervisor"] = binary }
    }

    private fun jar(content: Map<String, ByteArray>): Path = directory().resolve("provider-runtime.jar").also { path ->
        ZipOutputStream(Files.newOutputStream(path)).use { stream ->
            for ((name, bytes) in content) { stream.putNextEntry(ZipEntry(name)); stream.write(bytes); stream.closeEntry() }
        }
    }

    private fun development(content: Map<String, ByteArray> = entries()): Pair<Path, Path> {
        val module = directory().resolve("module")
        val classes = Files.createDirectories(module.resolve("build/classes/kotlin/main"))
        val resources = Files.createDirectories(module.resolve("build/resources/main"))
        for ((name, bytes) in content) {
            val path = resources.resolve(name)
            Files.createDirectories(path.parent)
            Files.write(path, bytes)
        }
        return classes to resources
    }

    @Test fun `JAR and development origins admit only the closed combined v1 and v2 resource names`() {
        val entries = entries()
        val sources = listOf(supervisorCodeSourceResources(jar(entries)), supervisorCodeSourceResources(development().first))
        for (source in sources) {
            for ((name, bytes) in entries) assertContentEquals(bytes, source.read(name, 512))
            for (bad in listOf(
                OwnedLiveSupervisorBundleFormat.PREFIX + "macos-x86_64/supervisor",
                OwnedLiveSupervisorBundleFormat.PREFIX + "linux-mips/supervisor",
                OwnedLiveSupervisorBundleFormat.PREFIX + "linux-x86_64/other",
                OwnedLiveSupervisorBundleFormat.MANIFEST + "/", "/" + OwnedLiveSupervisorBundleFormat.MANIFEST,
                OwnedLiveSupervisorBundleFormat.PREFIX + "../native/manifest", "arbitrary/manifest",
            )) {
                val error = assertFailsWith<ExecutionProblem> { source.read(bad, 512) }
                assertEquals("process_supervisor_bundle_origin_unavailable", error.code)
            }
            assertEquals(supervisorDigest(binary), OwnedLiveSupervisorImageLoader(target) { source }.load().identity.binaryDigest)
            assertEquals(SupervisorBundleFormat.VERSION, SupervisorImageLoader(target) { source }.load().identity.protocolVersion)
        }
    }

    @Test fun `packaged v2 lookup ignores a context classloader and arbitrary resource directory`() {
        val selected = jar(entries())
        val previous = Thread.currentThread().contextClassLoader
        val calls = AtomicInteger()
        Thread.currentThread().contextClassLoader = object : ClassLoader(previous) {
            override fun getResourceAsStream(name: String): InputStream {
                calls.incrementAndGet()
                return ByteArrayInputStream(byteArrayOf(7))
            }
        }
        try {
            val result = OwnedLiveSupervisorImageLoader(target) { supervisorCodeSourceResources(selected) }.load()
            assertEquals(supervisorDigest(binary), result.identity.binaryDigest)
            assertEquals(0, calls.get())
        } finally { Thread.currentThread().contextClassLoader = previous }
        val (_, resources) = development()
        assertEquals("process_supervisor_bundle_origin_unavailable", assertFailsWith<ExecutionProblem> {
            supervisorCodeSourceResources(resources)
        }.code)
    }

    @Test fun `v2 development resource links cannot redirect installed image selection`() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "POSIX symlink fixture")
        val (classes, resources) = development()
        val path = resources.resolve(OwnedLiveSupervisorBundleFormat.MANIFEST)
        val saved = path.resolveSibling("saved-manifest")
        Files.move(path, saved)
        Files.createSymbolicLink(path, saved)
        assertEquals("process_supervisor_bundle_origin_unavailable", assertFailsWith<ExecutionProblem> {
            OwnedLiveSupervisorImageLoader(target) { supervisorCodeSourceResources(classes) }.load()
        }.code)
    }
}

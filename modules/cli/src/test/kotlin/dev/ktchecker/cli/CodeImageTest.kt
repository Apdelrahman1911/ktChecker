package dev.ktchecker.cli

import dev.ktchecker.contracts.Digests
import java.net.URI
import java.net.URL
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFileAttributeView
import java.security.CodeSource
import java.security.ProtectionDomain
import java.security.cert.Certificate
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/** Synthetic loaded classes and real files/JARs; not a loaded-memory or release attestation. */
class CodeImageTest {
    @TempDir lateinit var temporary: Path
    private class FirstMarker
    private class SecondMarker

    private fun entry(type: Class<*>) = type.name.replace('.', '/') + ".class"
    private fun bytes(type: Class<*>): ByteArray = requireNotNull(type.getResourceAsStream("/" + entry(type))).use { it.readBytes() }

    private fun directory(name: String, vararg types: Class<*>): Path = Files.createDirectories(temporary.resolve(name)).also { root ->
        types.forEach { type ->
            val file = root.resolve(entry(type))
            Files.createDirectories(file.parent)
            Files.write(file, bytes(type))
        }
    }

    private fun file(root: Path, name: String, text: String): Path = root.resolve(name).also {
        Files.createDirectories(it.parent)
        Files.writeString(it, text)
    }

    private fun jar(path: Path, entries: Map<String, ByteArray>): Path {
        Files.createDirectories(path.parent)
        ZipOutputStream(Files.newOutputStream(path)).use { output ->
            entries.toSortedMap().forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name).apply { time = 0 })
                output.write(bytes)
                output.closeEntry()
            }
        }
        return path
    }

    private class ImageLoader(
        private val source: URL?,
        private val resources: List<Path> = emptyList(),
        private val resourceOverrides: Map<String, URL> = emptyMap(),
    ) : ClassLoader(CodeImageTest::class.java.classLoader) {
        fun define(type: Class<*>, bytes: ByteArray): Class<*> = defineClass(type.name, bytes, 0, bytes.size,
            ProtectionDomain(source?.let { CodeSource(it, null as Array<Certificate>?) }, null))

        override fun getResource(name: String): URL? {
            resourceOverrides[name]?.let { return it }
            for (root in resources) {
                if (Files.isDirectory(root, NOFOLLOW_LINKS)) {
                    val file = root.resolve(name)
                    if (Files.exists(file)) return file.toUri().toURL()
                } else {
                    val found = JarFile(root.toFile(), false, ZipFile.OPEN_READ, Runtime.version()).use { it.getJarEntry(name) != null }
                    if (found) {
                        val escaped = URI(null, null, "/$name", null).rawPath.substring(1)
                        return URI.create("jar:${root.toUri()}!/$escaped").toURL()
                    }
                }
            }
            return null
        }
    }

    private fun loaded(root: Path, type: Class<*> = FirstMarker::class.java,
                       resources: List<Path> = listOf(root), overrides: Map<String, URL> = emptyMap()): Class<*> =
        ImageLoader(root.toUri().toURL(), resources, overrides).define(type, bytes(type))

    private fun capture(root: Path, limits: CodeImageLimits = CodeImageLimits()) =
        CodeImage.capture(emptyList(), mapOf("fixture" to root), limits)

    private fun link(path: Path, target: Path) {
        try { Files.createSymbolicLink(path, target) }
        catch (_: UnsupportedOperationException) { assumeTrue(false, "Filesystem symbolic links unavailable") }
        catch (failure: FileSystemException) {
            val reason = failure.reason.orEmpty().lowercase()
            if (reason.contains("privilege") || reason.contains("not supported") || reason.contains("not permitted")) {
                assumeTrue(false, "Filesystem symbolic-link creation unavailable")
            } else throw failure
        }
    }

    @Test fun actualCliClassesHaveAReadableSharedCodeSource() {
        val image = CodeImage.capture(listOf(CodeImage::class.java, CliProblem::class.java))
        assertTrue(Digests.valid(image.digest))
        assertEquals(1, image.roots.size)
        assertEquals(2, image.roots.single().selectors.size)
        image.verify()
    }

    @Test fun selectorsAreOrderIndependentAndPhysicalRootsAreDeduplicated() {
        val root = directory("classes", FirstMarker::class.java, SecondMarker::class.java)
        val first = loaded(root)
        val second = loaded(root, SecondMarker::class.java)
        val a = CodeImage.capture(listOf(first, second, first), mapOf("same-root" to root))
        val b = CodeImage.capture(listOf(second, first), mapOf("same-root" to root))
        assertEquals(a.digest, b.digest)
        assertEquals(1, a.roots.size)
        assertEquals(3, a.roots.single().selectors.size)
        assertTrue(a.roots.single().selectors.contains("resource:same-root"))
    }

    @Test fun changedBytesWithSameLengthAndTimestampInvalidateOldCaptureAndNewVersion() {
        val root = directory("classes", FirstMarker::class.java)
        val resource = file(root, "rules/map.json", "alpha")
        val type = loaded(root)
        val original = CodeImage.capture(listOf(type))
        val modified = Files.getLastModifiedTime(resource)
        Files.writeString(resource, "omega")
        Files.setLastModifiedTime(resource, modified)
        assertEquals("code_image_changed", assertFailsWith<CliProblem> { original.verify() }.code)
        val current = CodeImage.capture(listOf(type))
        assertNotEquals(original.digest, current.digest)
        assertEquals(current.digest, CodeImage.capture(listOf(type)).digest)
        current.verify()
    }

    @Test fun directoryEntryRenamesAndEmptyDirectoriesArePartOfIdentity() {
        val root = directory("classes", FirstMarker::class.java)
        val resource = file(root, "rules-a.json", "same bytes")
        val type = loaded(root)
        val original = CodeImage.capture(listOf(type))
        Files.move(resource, root.resolve("rules-b.json"))
        val renamed = CodeImage.capture(listOf(type))
        assertNotEquals(original.digest, renamed.digest)
        assertFailsWith<CliProblem> { original.verify() }
        Files.createDirectory(root.resolve("empty"))
        assertNotEquals(renamed.digest, CodeImage.capture(listOf(type)).digest)
    }

    @Test fun installationPathMovesAndMetadataOnlyChangesDoNotChangeContentIdentity() {
        val left = directory("first install-雪", FirstMarker::class.java)
        val right = directory("second install-🌎", FirstMarker::class.java)
        file(left, "rules/雪 name.json", "identical")
        val resource = file(right, "rules/雪 name.json", "identical")
        val a = CodeImage.capture(listOf(loaded(left)))
        val b = CodeImage.capture(listOf(loaded(right)))
        assertEquals(a.digest, b.digest)
        Files.setLastModifiedTime(resource, FileTime.fromMillis(Files.getLastModifiedTime(resource).toMillis() + 10_000))
        b.verify()
        assertEquals(a.digest, CodeImage.capture(listOf(loaded(right))).digest)
        assertFalse(a.roots.toString().contains(temporary.toString()))
    }

    @Test fun entireJarBytesAreBoundAndJarFilenameIsNotAnImplementationVersion() {
        val entries = mapOf(entry(FirstMarker::class.java) to bytes(FirstMarker::class.java), "rules.json" to "alpha".toByteArray())
        val left = jar(temporary.resolve("provider-first.jar"), entries)
        val right = Files.copy(left, temporary.resolve("provider renamed-雪.jar"))
        val type = loaded(left)
        val a = CodeImage.capture(listOf(type))
        val b = CodeImage.capture(listOf(loaded(right)))
        assertEquals(a.digest, b.digest)
        assertEquals(Digests.sha256(Files.readAllBytes(left)), a.roots.single().digest)
        jar(left, entries + ("rules.json" to "omega".toByteArray()))
        assertEquals("code_image_changed", assertFailsWith<CliProblem> { a.verify() }.code)
        assertNotEquals(a.digest, CodeImage.capture(listOf(type)).digest)
    }

    @Test fun runtimeVersionedJarClassEntriesAreSupportedAndTheWholeArchiveIsBound() {
        val archive = jar(temporary.resolve("multi-release.jar"), mapOf(
            "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n".toByteArray(),
            "META-INF/versions/21/${entry(FirstMarker::class.java)}" to bytes(FirstMarker::class.java),
        ))
        val image = CodeImage.capture(listOf(loaded(archive)))
        assertEquals(Digests.sha256(Files.readAllBytes(archive)), image.roots.single().digest)
        image.verify()
    }

    @Test fun separateDevelopmentResourceRootsComeFromActualLookupAndAreExplicitlyBound() {
        val classes = directory("classes", FirstMarker::class.java)
        val resources = directory("actual resources-雪")
        val mapping = file(resources, "catalog/rules-🌎.json", "alpha")
        val type = loaded(classes, resources = listOf(resources))
        val observed = CodeImage.resourceRoot(type, "/catalog/rules-🌎.json")
        assertEquals(resources.toRealPath(), observed.toRealPath())
        val noResources = CodeImage.capture(listOf(type))
        val selected = CodeImage.capture(listOf(type), mapOf("catalog" to observed))
        assertEquals(2, selected.roots.size)
        Files.writeString(mapping, "omega")
        // No automatic resource closure is advertised: callers must supply these actual roots.
        noResources.verify()
        assertFailsWith<CliProblem> { selected.verify() }
        assertNotEquals(selected.digest, CodeImage.capture(listOf(type), mapOf("catalog" to observed)).digest)
    }

    @Test fun packedResourceLookupReturnsTheSameJarRootInsteadOfAnInventedDirectory() {
        val archive = jar(temporary.resolve("packed code-雪.jar"), mapOf(
            entry(FirstMarker::class.java) to bytes(FirstMarker::class.java), "catalog/rules.json" to "rules".toByteArray(),
        ))
        val type = loaded(archive)
        val resource = CodeImage.resourceRoot(type, "/catalog/rules.json")
        assertEquals(archive.toRealPath(), resource.toRealPath())
        val image = CodeImage.capture(listOf(type), mapOf("catalog" to resource))
        assertEquals(1, image.roots.size)
        assertEquals(2, image.roots.single().selectors.size)
        image.verify()
    }

    @Test fun absentAmbiguousOrNoncanonicalResourceLookupsFailWithoutDirectoryGuesses() {
        val root = directory("classes", FirstMarker::class.java)
        val type = loaded(root)
        assertEquals("code_image_resource_missing", assertFailsWith<CliProblem> { CodeImage.resourceRoot(type, "/missing.json") }.code)
        listOf("", "relative.json", "/", "/a/../b", "/a/./b", "/a//b", "/a\\b", "/bad\uD800.json").forEach { name ->
            assertFailsWith<CliProblem> { CodeImage.resourceRoot(type, name) }
        }
        val other = file(root, "actual.json", "not the requested entry")
        val remapped = loaded(root, overrides = mapOf("expected.json" to other.toUri().toURL()))
        assertEquals("code_image_resource_invalid", assertFailsWith<CliProblem> { CodeImage.resourceRoot(remapped, "/expected.json") }.code)
        val remote = loaded(root, overrides = mapOf("rules.json" to URI("https://example.invalid/rules.json").toURL()))
        assertEquals("code_image_resource_unsupported", assertFailsWith<CliProblem> { CodeImage.resourceRoot(remote, "/rules.json") }.code)
        val fragment = loaded(root, overrides = mapOf("actual.json" to URI.create("${other.toUri()}#other").toURL()))
        assertEquals("code_image_resource_invalid", assertFailsWith<CliProblem> { CodeImage.resourceRoot(fragment, "/actual.json") }.code)
    }

    @Test fun jarResourceEntryRemappingIsRejectedRatherThanHashingAnUnrelatedEntry() {
        val archive = jar(temporary.resolve("packed.jar"), mapOf(entry(FirstMarker::class.java) to bytes(FirstMarker::class.java),
            "actual.json" to "not expected".toByteArray()))
        val wrong = URI.create("jar:${archive.toUri()}!/actual.json").toURL()
        val type = loaded(archive, overrides = mapOf("expected.json" to wrong))
        assertEquals("code_image_resource_invalid", assertFailsWith<CliProblem> { CodeImage.resourceRoot(type, "/expected.json") }.code)
    }

    @Test fun resourceLookupAppliesArchiveAndEntryBoundsBeforeIdentityCapture() {
        val archive = jar(temporary.resolve("resource bounds.jar"), mapOf(entry(FirstMarker::class.java) to bytes(FirstMarker::class.java),
            "rules.json" to "rules".toByteArray()))
        val type = loaded(archive)
        assertEquals("code_image_limit_exceeded", assertFailsWith<CliProblem> {
            CodeImage.resourceRoot(type, "/rules.json", CodeImageLimits(maxFileBytes = 1))
        }.code)
        assertEquals("code_image_limit_exceeded", assertFailsWith<CliProblem> {
            CodeImage.resourceRoot(type, "/rules.json", CodeImageLimits(maxEntries = 1))
        }.code)
    }

    @Test fun captureCollectionsAreDetachedAndCannotBeMutatedToChangeVerification() {
        val root = directory("resources")
        file(root, "rules.json", "rules")
        val selected = linkedMapOf("rules" to root)
        val image = CodeImage.capture(emptyList(), selected)
        val digest = image.digest
        selected.clear()
        assertEquals(digest, image.digest)
        assertFailsWith<UnsupportedOperationException> { (image.roots as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (image.roots.single().selectors as MutableList).clear() }
        image.verify()
    }

    @Test fun missingFilesClassesAndUnsupportedCodeSourcesFailClosedWithPathFreeErrors() {
        assertEquals("code_image_empty", assertFailsWith<CliProblem> { CodeImage.capture(emptyList()) }.code)
        assertEquals("code_image_source_unavailable", assertFailsWith<CliProblem> { CodeImage.capture(listOf(String::class.java)) }.code)
        assertEquals("code_image_class_unsupported", assertFailsWith<CliProblem> { CodeImage.capture(listOf(Int::class.java)) }.code)
        val root = directory("empty code source")
        val missingClass = assertFailsWith<CliProblem> { CodeImage.capture(listOf(loaded(root))) }
        assertEquals("code_image_class_missing", missingClass.code)
        assertFalse(missingClass.message.contains(temporary.toString()))
        val remote = ImageLoader(URI("https://example.invalid/compiler.jar").toURL()).define(FirstMarker::class.java, bytes(FirstMarker::class.java))
        assertEquals("code_image_source_unsupported", assertFailsWith<CliProblem> { CodeImage.capture(listOf(remote)) }.code)
        val artifact = file(root, "actual.bin", "data")
        val image = capture(artifact)
        Files.delete(artifact)
        assertEquals("code_image_source_unavailable", assertFailsWith<CliProblem> { image.verify() }.code)
    }

    @Test fun differentClassloadersCannotAliasTheSameBinarySelector() {
        val left = directory("first", FirstMarker::class.java)
        val right = directory("second", FirstMarker::class.java)
        assertEquals("code_image_class_ambiguous", assertFailsWith<CliProblem> {
            CodeImage.capture(listOf(loaded(left), loaded(right)))
        }.code)
    }

    @Test fun symbolicRootsEntriesAndDotSegmentBypassesAreRejected() {
        val root = directory("real", FirstMarker::class.java)
        val alias = temporary.resolve("root-link")
        link(alias, root)
        assertEquals("code_image_symlink", assertFailsWith<CliProblem> { capture(alias) }.code)
        assertEquals("code_image_path_invalid", assertFailsWith<CliProblem> { capture(alias.resolve(".")) }.code)
        assertEquals("code_image_path_invalid", assertFailsWith<CliProblem> { capture(root.resolve("../real")) }.code)
        val external = file(temporary, "outside.txt", "outside")
        val internal = root.resolve("redirect.txt")
        link(internal, external)
        assertEquals("code_image_symlink", assertFailsWith<CliProblem> { CodeImage.capture(listOf(loaded(root))) }.code)
        Files.delete(internal)
        link(root.resolve("redirect-dir"), temporary)
        assertEquals("code_image_symlink", assertFailsWith<CliProblem> { CodeImage.capture(listOf(loaded(root))) }.code)
    }

    @Test fun trustedAncestorAliasesArePinnedAndCannotRetargetAnExistingCapture() {
        val first = directory("first-parent")
        val second = directory("second-parent")
        file(first, "actual/image.bin", "same")
        file(second, "actual/image.bin", "same")
        val alias = temporary.resolve("parent-alias")
        link(alias, first)
        val selected = capture(alias.resolve("actual"))
        selected.verify()
        Files.delete(alias)
        link(alias, second)
        assertEquals("code_image_changed", assertFailsWith<CliProblem> { selected.verify() }.code)
    }

    @Test fun resourceLookupCannotFollowAnInternalSymbolicLink() {
        val classes = directory("classes", FirstMarker::class.java)
        val resources = directory("resources")
        val outside = file(temporary, "external.json", "external")
        link(resources.resolve("rules.json"), outside)
        val type = loaded(classes, resources = listOf(resources))
        assertEquals("code_image_symlink", assertFailsWith<CliProblem> { CodeImage.resourceRoot(type, "/rules.json") }.code)
    }

    @Test fun explicitlyUnreadableModeIsRejectedEvenForAPrivilegedProcess() {
        val root = directory("permissions")
        val artifact = file(root, "image.bin", "bytes")
        val view = Files.getFileAttributeView(artifact, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
        assumeTrue(view != null, "POSIX permission-control fixture unavailable")
        val original = requireNotNull(view).readAttributes().permissions()
        try {
            view.setPermissions(emptySet())
            assertEquals("code_image_unreadable", assertFailsWith<CliProblem> { capture(artifact) }.code)
        } finally { view.setPermissions(original) }
    }

    @Test fun malformedLogicalNamesAndUnboundedSelectionsNeverProduceAnIdentity() {
        val root = directory("bounded", FirstMarker::class.java)
        for (name in listOf("", "/absolute", "C:/absolute", "bad\nname", "parent/../escape", "broken\uD800")) {
            assertEquals("code_image_selector_invalid", assertFailsWith<CliProblem> { CodeImage.capture(emptyList(), mapOf(name to root)) }.code)
        }
        val type = loaded(root)
        assertEquals("code_image_limit_exceeded", assertFailsWith<CliProblem> {
            CodeImage.capture(generateSequence { type }.asIterable(), limits = CodeImageLimits(maxSelections = 2))
        }.code)
    }

    @Test fun filesystemByteEntryDepthAndRootBudgetsAreEnforced() {
        val root = directory("bounded")
        val data = file(root, "one.bin", "12345")
        listOf(CodeImageLimits(maxFileBytes = 4), CodeImageLimits(maxRootBytes = 4), CodeImageLimits(maxTotalBytes = 4)).forEach { limits ->
            assertEquals("code_image_limit_exceeded", assertFailsWith<CliProblem> { capture(data, limits) }.code)
        }
        val second = file(root, "two.bin", "12345")
        assertEquals("code_image_limit_exceeded", assertFailsWith<CliProblem> { capture(root, CodeImageLimits(maxEntries = 1)) }.code)
        assertEquals("code_image_limit_exceeded", assertFailsWith<CliProblem> {
            CodeImage.capture(emptyList(), mapOf("one" to data, "two" to second), CodeImageLimits(maxRoots = 1))
        }.code)
        assertEquals("code_image_limit_exceeded", assertFailsWith<CliProblem> {
            CodeImage.capture(emptyList(), mapOf("one" to data, "two" to second), CodeImageLimits(maxTotalBytes = 9))
        }.code)
        file(root, "nested/deeper/value", "a")
        assertEquals("code_image_limit_exceeded", assertFailsWith<CliProblem> { capture(root, CodeImageLimits(maxDepth = 1)) }.code)
        assertEquals("code_image_limit_exceeded", assertFailsWith<CliProblem> { capture(root, CodeImageLimits(maxEntryNameBytes = 2)) }.code)
    }

    @Test fun interruptedCaptureAbstainsWithoutClearingCallerInterruptState() {
        val root = directory("interrupted")
        file(root, "one", "value")
        try {
            Thread.currentThread().interrupt()
            assertEquals("code_image_interrupted", assertFailsWith<CliProblem> { capture(root) }.code)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun concurrentVerificationAndCaptureDoNotShareMutableHashers() {
        val root = directory("concurrent", FirstMarker::class.java)
        file(root, "rules.json", "rules")
        val type = loaded(root)
        val image = CodeImage.capture(listOf(type))
        val pool = Executors.newFixedThreadPool(3)
        try {
            val jobs = pool.invokeAll(List(12) { Callable { image.verify(); CodeImage.capture(listOf(type)).digest } }, 10, TimeUnit.SECONDS)
            jobs.forEach { assertFalse(it.isCancelled); assertEquals(image.digest, it.get()) }
        } finally { pool.shutdownNow() }
    }
}

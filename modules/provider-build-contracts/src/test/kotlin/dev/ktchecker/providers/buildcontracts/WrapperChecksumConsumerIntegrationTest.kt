package dev.ktchecker.providers.buildcontracts

import dev.ktchecker.contracts.Digests
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/**
 * Eight direct calls to the exact pinned Install.verifyDownloadChecksum API, not a wrapper
 * entrypoint. The build supplies the existing repository wrapper as an inert test resource;
 * only a hash-checked temporary copy is loaded, with the platform class loader as parent.
 *
 * Each case compares the real API outcome with the source-only provider's format observation.
 * Synthetic owner callbacks remain synthetic: this does not prove selected-wrapper/version
 * provenance, Gradle/project execution, downloads, default activation or release qualification.
 * Temporary-file ownership and byte bounds are controls, not a hostile same-UID sandbox.
 */
class WrapperChecksumConsumerIntegrationTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test fun matching_lowercase_digest_returns_normally_and_retains_the_owned_payload() {
        compareCase("matching-lowercase", PAYLOAD_DIGEST, Expectation.MATCHING)
    }

    @Test fun uppercase_digest_is_a_format_contradiction_and_deletes_only_the_owned_payload() {
        compareCase("uppercase",
            "E12EAE56AB8AB26EFD91D465B9777C0B7DC898165CA0C9864D419B110B514C5D",
            Expectation.MALFORMED_FORMAT)
    }

    @Test fun escaped_leading_space_survives_properties_parsing_and_fails_comparison() {
        compareCase("leading-space", " " + PAYLOAD_DIGEST, Expectation.MALFORMED_FORMAT)
    }

    @Test fun trailing_space_survives_properties_parsing_and_fails_comparison() {
        compareCase("trailing-space", PAYLOAD_DIGEST + " ", Expectation.MALFORMED_FORMAT)
    }

    @Test fun short_63_character_digest_is_a_format_contradiction() {
        compareCase("short63", PAYLOAD_DIGEST.dropLast(1), Expectation.MALFORMED_FORMAT)
    }

    @Test fun nonhex_64_character_digest_is_a_format_contradiction() {
        compareCase("nonhex64", "g" + PAYLOAD_DIGEST.drop(1), Expectation.MALFORMED_FORMAT)
    }

    @Test fun wrong_lowercase_64_hex_digest_fails_integrity_but_is_not_a_format_finding() {
        compareCase("valid-lowercase-wrong-integrity", "0" + PAYLOAD_DIGEST.drop(1),
            Expectation.WRONG_INTEGRITY)
    }

    @Test fun null_checksum_returns_normally_with_the_owned_target_absent_before_and_after() {
        // This observes absence before/after; it does not claim syscall-level no-file-access tracing.
        compareCase("null-checksum-missing-owned-file", null, Expectation.ABSENT)
    }

    private fun compareCase(id: String, checksum: String?, expected: Expectation) {
        assertTrue(id.matches(Regex("[a-z0-9-]+")))
        assertTrue(PAYLOAD.size <= 64)
        assertEquals("sha256:" + PAYLOAD_DIGEST, Digests.sha256(PAYLOAD))

        // A raw leading space after '=' is delimiter whitespace, not part of the consumed value.
        // Escape that one space so the provider and direct API receive the exact same value.
        val declaration = checksum?.let {
            "distributionSha256Sum=" + (if (it.startsWith(" ")) "\\" + it else it) + "\n"
        }.orEmpty()
        val properties = WrapperContractFixture.BASE_URL + declaration
        assertEquals(checksum, parseWrapperProperties(properties.toByteArray(Charsets.ISO_8859_1)).checksum)
        val fixture = WrapperContractFixture(properties)
        val result = fixture.analyze()
        assertNoncertifying(result)
        assertFalse(result.completeFor(fixture.input))
        val coverage = result.coverage.single()
        assertEquals(WRAPPER_CHECKSUM_FORMAT, coverage.ruleId)
        assertEquals(fixture.scope, coverage.context)
        assertEquals(expected.state, coverage.state)
        assertEquals(expected.reason, coverage.reason)
        assertEquals("not_executed", coverage.provenance)
        if (expected == Expectation.MALFORMED_FORMAT) {
            val finding = result.findings.single()
            assertEquals(WRAPPER_CHECKSUM_FORMAT, finding.ruleId)
            assertEquals(WrapperContractFixture.PROPERTIES_PATH, finding.location.path)
            assertEquals(fixture.scope, finding.context)
            assertTrue(finding.verification.all { it.state == "not_run" && it.command.isEmpty() })
        } else {
            assertTrue(result.findings.isEmpty())
        }
        if (checksum != null) {
            assertEquals(expected != Expectation.MALFORMED_FORMAT,
                WrapperConsumerProfile.acceptsChecksumShape(checksum))
        }

        val root = newOwnedDirectory()
        val rootBefore = directoryAttributes(root)
        val jarBytes = pinnedConsumerResource()
        val jar = root.resolve("consumer.jar")
        val jarBefore = createOwnedFile(jar, jarBytes)
        val sibling = root.resolve("sibling-control.fixture")
        val siblingBefore = createOwnedFile(sibling, SIBLING_CONTROL)
        val target = root.resolve(id + ".fixture")
        assertEquals(root, target.parent)
        assertTrue(Files.notExists(target, NOFOLLOW_LINKS))
        val targetBefore = if (expected == Expectation.ABSENT) null else createOwnedFile(target, PAYLOAD)

        // The sole API invocation for this test receives only a freshly owned tiny payload path.
        // Mismatch deletion must not affect the independently owned sibling or consumer input.
        val failure = try {
            invokePinnedApi(jar, target, checksum)
        } finally {
            assertOwnedFile(jar, jarBytes, jarBefore)
            assertOwnedFile(sibling, SIBLING_CONTROL, siblingBefore)
            assertSameIdentity(rootBefore, directoryAttributes(root))
        }
        if (expected == Expectation.MALFORMED_FORMAT || expected == Expectation.WRONG_INTEGRITY) {
            assertEquals<Class<*>>(RuntimeException::class.java, assertNotNull(failure).javaClass)
        } else {
            assertNull(failure)
        }
        if (expected == Expectation.MATCHING) {
            assertOwnedFile(target, PAYLOAD, assertNotNull(targetBefore))
        } else {
            assertTrue(Files.notExists(target, NOFOLLOW_LINKS))
        }
        val expectedNames = setOf(jar.fileName.toString(), sibling.fileName.toString()) +
            if (expected == Expectation.MATCHING) setOf(target.fileName.toString()) else emptySet()
        val actualNames = Files.list(root).use { entries ->
            entries.map { it.fileName.toString() }.toList().toSet()
        }
        assertEquals(expectedNames, actualNames)
    }

    private fun pinnedConsumerResource(): ByteArray {
        val bytes = assertNotNull(javaClass.getResourceAsStream(CONSUMER_RESOURCE),
            "Pinned consumer fixture resource is required").use { it.readNBytes(PINNED_JAR_BYTES + 1) }
        assertEquals(PINNED_JAR_BYTES, bytes.size)
        assertEquals(PINNED_JAR_DIGEST, Digests.sha256(bytes))
        WrapperConsumerProfile.verify(bytes)
        return bytes
    }

    private fun invokePinnedApi(jar: Path, target: Path, checksum: String?): Throwable? =
        URLClassLoader(arrayOf(jar.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            // No ambient Gradle classpath, constructor, wrapper entrypoint, URL/download or install call.
            val install = Class.forName("org.gradle.wrapper.Install", false, loader)
            assertSame(loader, install.classLoader)
            assertEquals(jar.toRealPath(), Path.of(install.protectionDomain.codeSource.location.toURI()).toRealPath())
            val method = install.getMethod("verifyDownloadChecksum",
                String::class.java, File::class.java, String::class.java)
            assertTrue(Modifier.isPublic(method.modifiers) && Modifier.isStatic(method.modifiers))
            assertEquals(Void.TYPE, method.returnType)
            try {
                assertNull(method.invoke(null, "ktchecker-checksum-control", target.toFile(), checksum))
                null
            } catch (failure: InvocationTargetException) {
                // Reflection/infrastructure errors are not accepted as the expected comparison failure.
                failure.targetException
            }
        }

    private fun newOwnedDirectory(): Path {
        assertFalse(Files.isSymbolicLink(temporaryDirectory))
        val parent = temporaryDirectory.toRealPath()
        assertTrue(Files.isDirectory(parent, NOFOLLOW_LINKS))
        val workingDirectory = Path.of("").toRealPath()
        assertFalse(parent.startsWith(workingDirectory), "Consumer fixtures must not be written in the working tree")
        generateSequence(workingDirectory) { it.parent }.forEach { ancestor ->
            if (Files.isRegularFile(ancestor.resolve("settings.gradle.kts"), NOFOLLOW_LINKS)) {
                assertFalse(parent.startsWith(ancestor), "Consumer fixtures must stay outside the Gradle source tree")
            }
        }
        val root = parent.resolve("owned-checksum-consumer")
        assertTrue(Files.notExists(root, NOFOLLOW_LINKS))
        Files.createDirectory(root, *ownerAttributes(parent, directory = true))
        directoryAttributes(root)
        return root
    }

    private fun createOwnedFile(path: Path, bytes: ByteArray): BasicFileAttributes {
        assertTrue(Files.notExists(path, NOFOLLOW_LINKS))
        Files.newByteChannel(path, setOf<OpenOption>(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
            *ownerAttributes(path.parent, directory = false)).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
        return assertOwnedFile(path, bytes)
    }

    private fun assertOwnedFile(
        path: Path,
        bytes: ByteArray,
        before: BasicFileAttributes? = null,
    ): BasicFileAttributes {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        assertTrue(attributes.isRegularFile && !attributes.isSymbolicLink)
        assertEquals(bytes.size.toLong(), attributes.size())
        if (supportsPosix(path)) {
            assertEquals(PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(path, NOFOLLOW_LINKS))
        }
        val observed = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(bytes.size + 1) }
        assertContentEquals(bytes, observed)
        if (before != null) assertSameIdentity(before, attributes)
        return attributes
    }

    private fun directoryAttributes(root: Path): BasicFileAttributes {
        val attributes = Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        assertTrue(attributes.isDirectory && !attributes.isSymbolicLink)
        assertEquals(root, root.toRealPath())
        if (supportsPosix(root)) {
            assertEquals(PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(root, NOFOLLOW_LINKS))
        }
        return attributes
    }

    private fun assertSameIdentity(before: BasicFileAttributes, after: BasicFileAttributes) {
        // fileKey and POSIX permissions are not available on every supported JDK filesystem.
        // Their absence does not turn these ownership controls into an OS-sandbox claim.
        before.fileKey()?.let { assertEquals(it, after.fileKey()) }
    }

    private fun supportsPosix(path: Path): Boolean =
        Files.getFileStore(path).supportsFileAttributeView(PosixFileAttributeView::class.java)

    private fun ownerAttributes(parent: Path, directory: Boolean): Array<FileAttribute<*>> =
        if (supportsPosix(parent)) arrayOf<FileAttribute<*>>(PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------")))
        else emptyArray()

    private enum class Expectation(val state: String, val reason: String) {
        MATCHING("unqualified", "wrapper_checksum_shape_accepted_integrity_unverified"),
        MALFORMED_FORMAT("findings", "wrapper_checksum_format_observed_unqualified"),
        WRONG_INTEGRITY("unqualified", "wrapper_checksum_shape_accepted_integrity_unverified"),
        ABSENT("not_applicable", "wrapper_checksum_absent_optional_policy_separate"),
    }

    private companion object {
        const val CONSUMER_RESOURCE = "/fixtures/gradle-wrapper-7a9ce74c.jar"
        const val PINNED_JAR_BYTES = 47_505
        const val PINNED_JAR_DIGEST = "sha256:7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d"
        const val PAYLOAD_DIGEST = "e12eae56ab8ab26efd91d465b9777c0b7dc898165ca0c9864d419b110b514c5d"
        val PAYLOAD = "ktchecker-wrapper-consumer-control\n".toByteArray(Charsets.US_ASCII)
        val SIBLING_CONTROL = "owned-sibling-must-remain\n".toByteArray(Charsets.US_ASCII)
    }
}

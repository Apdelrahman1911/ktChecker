package dev.ktchecker.compiler

import dev.ktchecker.contracts.Digests
import dev.ktchecker.providers.runtime.OwnedSupervisorIdentity
import dev.ktchecker.providers.runtime.ProcessOutcome
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Tiny filesystem and receipt-serialization controls only: no helper/JVM/compiler is launched. */
class CompilerFixtureSupervisorResourcesTest {
    private val target = "linux-x86_64"
    private val binary = "source-only, non-executed native image test bytes".toByteArray()
    private val identity = OwnedSupervisorIdentity(CompilerFixtureSupervisorResources.PROTOCOL, target,
        Digests.sha256("source claim, not reproducibility"), Digests.sha256(binary))
    private val manifest = "${identity.protocolVersion}\n$target\n${identity.sourceDigest}\n${identity.binaryDigest}\n".toByteArray()
    private val binaryName = CompilerFixtureSupervisorResources.binaryName(identity)
    private val content = linkedMapOf(CompilerFixtureSupervisorResources.MANIFEST to manifest, binaryName to binary,
        CompilerFixtureSupervisorResources.BUILD_PROVENANCE to "{\"informational_only\":true}".toByteArray())

    @Test fun strictManifestRetainsInstalledClaimAndIndependentlySelectedBinaryDigest() {
        val parsed = CompilerFixtureSupervisorResources.identity(manifest, target)
        assertEquals(identity, parsed)
        assertEquals(Digests.sha256(binary), parsed.binaryDigest)
        assertEquals(binaryName, CompilerFixtureSupervisorResources.binaryName(parsed))
    }

    @Test fun malformedWrongTargetUnavailableOrExtraLineManifestsCannotSupplyExecutionIdentity() {
        val text = manifest.toString(Charsets.UTF_8)
        val malformed = listOf(text.removeSuffix("\n"), text + "\n", text.replace("\n", "\r\n"),
            "\uFEFF" + text, text.replace(target, "windows-x86_64"), text.replace(identity.binaryDigest, "unavailable"),
            text.replace(identity.sourceDigest, "sha256:" + "A".repeat(64)), text.replace(target, "linux-mips"))
        malformed.forEach { assertFails { CompilerFixtureSupervisorResources.identity(it.toByteArray(), target) } }
        assertFails { CompilerFixtureSupervisorResources.identity(manifest, "macos-aarch64") }
        assertFails { CompilerFixtureSupervisorResources.identity(byteArrayOf(0xc3.toByte(), 0x28), target) }
        assertFails { CompilerFixtureSupervisorResources.identity(ByteArray(513), target) }
    }

    @Test fun literalDevelopmentMappingReadsOnlyOwnedInstalledResources() = withRoot { root ->
        val source = development(root.resolve("runtime"))
        val reader = CompilerFixtureSupervisorResources.fromCodeSource(source)
        assertEquals("development_resources", reader.kind)
        for ((name, bytes) in content) {
            assertContentEquals(bytes, reader.read(name))
            assertEquals(source.parent.parent.parent.resolve("resources/main/$name"), reader.installedOrigin(name))
        }
        assertFails { reader.read(CompilerFixtureSupervisorResources.PREFIX + "../manifest") }
        assertFails { reader.read(CompilerFixtureSupervisorResources.PREFIX + "other") }
        assertFails { CompilerFixtureSupervisorResources.fromCodeSource(source.parent) }
    }

    @Test fun exactJarResourcesNeverConsultFirstMatchContextClassLoader() = withRoot { root ->
        val jar = root.resolve("runtime.jar")
        Files.write(jar, archive(content))
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        var calls = 0
        try {
            thread.contextClassLoader = object : ClassLoader(null) {
                override fun getResourceAsStream(name: String): ByteArrayInputStream {
                    calls++
                    return ByteArrayInputStream("decoy must not be selected".toByteArray())
                }
            }
            val reader = CompilerFixtureSupervisorResources.fromCodeSource(jar)
            assertEquals("jar_entry", reader.kind)
            for ((name, bytes) in content) {
                assertContentEquals(bytes, reader.read(name))
                assertEquals(jar, reader.installedOrigin(name))
            }
            assertEquals(0, calls)
        } finally { thread.contextClassLoader = previous }
    }

    @Test fun duplicateJarResourcesAndOversizedFilesAreRejectedBeforeAttribution() = withRoot { root ->
        val duplicate = root.resolve("duplicate.jar")
        val name = CompilerFixtureSupervisorResources.MANIFEST
        val alias = name.dropLast(1) + "x"
        val bytes = archive(linkedMapOf(name to manifest, alias to manifest))
        val needle = alias.toByteArray()
        val replacement = name.toByteArray()
        var replacements = 0
        for (offset in 0..bytes.size - needle.size) {
            if (needle.indices.all { bytes[offset + it] == needle[it] }) {
                replacement.copyInto(bytes, offset)
                replacements++
            }
        }
        assertEquals(2, replacements, "Rename both local and central entry names without changing payload CRCs")
        Files.write(duplicate, bytes)
        assertFails { CompilerFixtureSupervisorResources.fromCodeSource(duplicate).read(name) }
        val oversizedJar = root.resolve("oversized.jar")
        RandomAccessFile(oversizedJar.toFile(), "rw").use { it.setLength(CompilerFixtureSupervisorResources.MAX_JAR_BYTES.toLong() + 1) }
        assertFails { CompilerFixtureSupervisorResources.fromCodeSource(oversizedJar) }
        val source = development(root.resolve("runtime"))
        val reader = CompilerFixtureSupervisorResources.fromCodeSource(source)
        for ((resource, bound) in listOf(name to 512, binaryName to 1_048_576,
                CompilerFixtureSupervisorResources.BUILD_PROVENANCE to 16_384)) {
            RandomAccessFile(reader.installedOrigin(resource).toFile(), "rw").use { it.setLength(bound.toLong() + 1) }
            assertFails { reader.read(resource) }
        }
    }

    @Test fun linkedNativeDescendantsAndLinkedCodeSourceLeavesAreNeverCanonicalized() = withRoot { root ->
        assumeTrue(root.fileSystem.supportedFileAttributeViews().contains("posix"), "Symlink controls require a POSIX host")
        val source = development(root.resolve("runtime"))
        val reader = CompilerFixtureSupervisorResources.fromCodeSource(source)
        val binaryPath = reader.installedOrigin(binaryName)
        val original = binaryPath.resolveSibling("original-supervisor")
        Files.move(binaryPath, original)
        Files.createSymbolicLink(binaryPath, original.fileName)
        assertFails { reader.read(binaryName) }
        Files.delete(binaryPath)
        Files.move(original, binaryPath)
        val resources = source.parent.parent.parent.resolve("resources")
        Files.move(resources, resources.resolveSibling("ordinary-resources"))
        Files.createSymbolicLink(resources, Path.of("ordinary-resources"))
        assertFails { reader.read(CompilerFixtureSupervisorResources.MANIFEST) }
        val linkedSource = Files.createSymbolicLink(root.resolve("linked-code-source"), source)
        assertFails { CompilerFixtureSupervisorResources.fromCodeSource(linkedSource) }
    }

    @Test fun declaredOuterAliasIsPinnedAndRetargetCannotSubstituteAnotherInstalledImage() = withRoot { root ->
        assumeTrue(root.fileSystem.supportedFileAttributeViews().contains("posix"), "Ancestor-alias controls require a POSIX host")
        val source = development(root.resolve("first/runtime"))
        development(root.resolve("second/runtime"))
        val alias = Files.createSymbolicLink(root.resolve("ancestor"), Path.of("first"))
        val reader = CompilerFixtureSupervisorResources.fromCodeSource(alias.resolve("runtime/build/classes/kotlin/main"))
        assertEquals(source, reader.codeSource)
        assertContentEquals(manifest, reader.read(CompilerFixtureSupervisorResources.MANIFEST))
        Files.delete(alias)
        Files.createSymbolicLink(alias, Path.of("second"))
        assertFails { reader.read(CompilerFixtureSupervisorResources.MANIFEST) }
    }

    @Test fun receiptSerializationDoesNotTurnForkExitOrAvailabilityIntoExecProof() {
        // These are outcome data values only, not witnesses of any launched process.
        for (exit in listOf(0, 1, 126)) {
            val value = ProcessOutcome("executed", exitCode = exit, supervisorIdentity = identity)
            val fields = CompilerFixtureProcessEvidence.fields(value, identity)
            assertEquals(false, fields["execution_confirmed"])
            assertEquals(false, fields["executed"])
            assertEquals(false, fields["successful_execution"])
            assertEquals(true, fields["supervisor_identity_matches_start_capture"])
        }
        val denied = CompilerFixtureProcessEvidence.fields(null, identity)
        assertEquals(false, denied["executed"])
        assertNull(denied["supervisor_identity"])
        assertNull(denied["supervisor_identity_matches_start_capture"])
    }

    @Test fun receiptSerializationKeepsNonzeroConfirmedOutcomeAndActualMismatchedIdentityDistinct() {
        val value = ProcessOutcome("executed", exitCode = 1, supervisorIdentity = identity, executionConfirmed = true)
        val fields = CompilerFixtureProcessEvidence.fields(value, identity)
        assertEquals(true, fields["executed"])
        assertEquals(true, fields["successful_execution"])
        val different = identity.copy(binaryDigest = Digests.sha256("different unexecuted image"))
        val mismatch = CompilerFixtureProcessEvidence.fields(value.copy(supervisorIdentity = different), identity)
        assertEquals(false, mismatch["supervisor_identity_matches_start_capture"])
        val actual = mismatch["supervisor_identity"] as Map<*, *>
        assertEquals(different.binaryDigest, actual["binary_digest"])
        assertEquals("actual_launched_outcome_not_inspector", actual["identity_origin"])
        assertEquals(false, actual["native_qualified"])
    }

    private fun development(module: Path): Path {
        val source = Files.createDirectories(module.resolve("build/classes/kotlin/main"))
        for ((name, bytes) in content) {
            val path = module.resolve("build/resources/main/$name")
            Files.createDirectories(path.parent)
            Files.write(path, bytes)
        }
        return source
    }

    private fun archive(entries: Map<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun withRoot(action: (Path) -> Unit) {
        val root = Files.createTempDirectory("ktc-native-provenance-unit-").toRealPath()
        try { action(root) }
        finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
}

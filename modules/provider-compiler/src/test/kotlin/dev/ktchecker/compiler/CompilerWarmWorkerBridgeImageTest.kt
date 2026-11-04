package dev.ktchecker.compiler

import dev.ktchecker.compilerworker.CompilerWarmWorkerProtocol as Wire
import dev.ktchecker.contracts.ArtifactIdentity
import dev.ktchecker.contracts.Digests
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import kotlin.test.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/** Bounded fake ZIP/class-file data only. No compiler adapter, process launch, loading of archived classes, or real-generation claim. */
class CompilerWarmWorkerBridgeImageTest {
    @TempDir lateinit var temporary: Path
    private val prefix = "dev/ktchecker/compilerworker/"
    private val classes = linkedMapOf(
        prefix + "CompilerWarmWorkerProtocol.class" to byteArrayOf(0, 1, 2),
        prefix + "CompilerWarmWorkerMain.class" to byteArrayOf(0, 3, 4),
        prefix + "PinnedKotlinCompilerAdapter.class" to byteArrayOf(0, 5, 6),
        prefix + "CompilerWarmWorkerMain\$Session.class" to byteArrayOf(0, 7, 8),
        prefix + "PinnedKotlinCompilerAdapter\$1.class" to byteArrayOf(0, 9, 10),
        prefix + "CompilerWarmWorkerProtocol\$Job\$Nested.class" to byteArrayOf(0, 11, 12),
    )

    @Test fun `exact outer numeric and nested class names pass without widening wire paths`() {
        val root = classRoot()
        val artifact = archive(metadata() + classes.toList())
        CompilerWarmWorkerBridgeImage.verify(artifact, root)
        val nested = prefix + "CompilerWarmWorkerMain\$Session.class"
        assertEquals("unsafe_relative_path", assertFailsWith<IllegalArgumentException> { Wire.safePath(root, nested) }.message)
        assertEquals("unsafe_relative_path", assertFailsWith<IllegalArgumentException> {
            Wire.readBounded(root, nested, Wire.MAX_RECEIPT_BYTES)
        }.message)
    }

    @Test fun `omitted nested entries and absent mandatory outer classes are rejected`() {
        val root = classRoot()
        val nested = prefix + "CompilerWarmWorkerMain\$Session.class"
        assertCode(CompilerWarmHostFailureCode.BRIDGE_CLASS_SET) {
            CompilerWarmWorkerBridgeImage.verify(archive(classes.filterKeys { it != nested }.toList()), root)
        }
        Files.delete(root.resolve(prefix + "CompilerWarmWorkerMain.class"))
        assertCode(CompilerWarmHostFailureCode.BRIDGE_CLASS_SET) {
            CompilerWarmWorkerBridgeImage.verify(archive(classes.toList()), root)
        }
    }

    @Test fun `foreign class families and malformed class basenames or directory spellings are rejected`() {
        val root = classRoot()
        for (name in listOf(
            prefix + "CompilerWarmWorkerMainExtra.class", prefix + "Other.class",
            prefix + "CompilerWarmWorkerMain\$Bad name.class", prefix + "CompilerWarmWorkerMain\$../outside.class",
            prefix + "../CompilerWarmWorkerMain.class", "dev/\$other/CompilerWarmWorkerMain.class",
            "dev\\ktchecker\\compilerworker\\CompilerWarmWorkerMain.class", "/" + classes.keys.first(),
            "dev//ktchecker/compilerworker/", "dev/other/",
        )) {
            assertCode(CompilerWarmHostFailureCode.BRIDGE_ENTRY_NAME) {
                CompilerWarmWorkerBridgeImage.verify(archive(classes.toList() + (name to byteArrayOf(0))), root)
            }
        }
        // A ZIP implementation may reject a NUL spelling while opening, before the host entry check.
        assertFails {
            CompilerWarmWorkerBridgeImage.verify(archive(classes.toList() + ((classes.keys.first() + '\u0000') to byteArrayOf(0))), root)
        }
    }

    @Test fun `duplicate central directory names are rejected rather than merged`() {
        val root = classRoot()
        assertCode(CompilerWarmHostFailureCode.BRIDGE_ENTRY_NAME) {
            CompilerWarmWorkerBridgeImage.verify(archive(classes.toList() + classes.toList().first()), root)
        }
    }

    @Test fun `same length host class byte mutation cannot match the bridge`() {
        val root = classRoot()
        val artifact = archive(classes.toList())
        val entry = classes.entries.first()
        val changed = entry.value.copyOf().also { it[0] = 99 }
        Files.write(root.resolve(entry.key), changed)
        assertCode(CompilerWarmHostFailureCode.BRIDGE_CLASS_BYTES) { CompilerWarmWorkerBridgeImage.verify(artifact, root) }
    }

    @Test fun `wrong selected archive digest is rejected even for matching class entries`() {
        val root = classRoot()
        val artifact = archive(classes.toList()).copy(digest = Digests.sha256("not-the-selected-archive"))
        assertCode(CompilerWarmHostFailureCode.BRIDGE_ARCHIVE_IDENTITY) { CompilerWarmWorkerBridgeImage.verify(artifact, root) }
    }

    @Test fun `class leaf symlinks are denied without accepting their target bytes`() {
        val root = classRoot()
        val artifact = archive(classes.toList())
        val leaf = root.resolve(classes.keys.first())
        val preserved = leaf.resolveSibling("preserved-bytes.bin")
        Files.move(leaf, preserved)
        Files.createSymbolicLink(leaf, preserved)
        assertCode(CompilerWarmHostFailureCode.BRIDGE_FILE) { CompilerWarmWorkerBridgeImage.verify(artifact, root) }
        assertContentEquals(classes.values.first(), Files.readAllBytes(preserved))
    }

    @Test fun `class package ancestor symlinks are denied`() {
        val root = classRoot()
        val artifact = archive(classes.toList())
        val ancestor = root.resolve("dev")
        val preserved = root.resolve("preserved-dev")
        Files.move(ancestor, preserved)
        Files.createSymbolicLink(ancestor, preserved)
        assertCode(CompilerWarmHostFailureCode.BRIDGE_DIRECTORY) { CompilerWarmWorkerBridgeImage.verify(artifact, root) }
    }

    @Test fun `archive leaf and ancestor symlinks are denied`() {
        val root = classRoot()
        val artifact = archive(classes.toList())
        val path = Path.of(artifact.path)
        val preserved = path.resolveSibling(path.fileName.toString() + ".preserved")
        Files.move(path, preserved)
        Files.createSymbolicLink(path, preserved)
        assertCode(CompilerWarmHostFailureCode.BRIDGE_FILE) { CompilerWarmWorkerBridgeImage.verify(artifact, root) }
        val alias = Files.createSymbolicLink(root.resolve("archive-alias"), path.parent)
        assertCode(CompilerWarmHostFailureCode.BRIDGE_DIRECTORY) {
            CompilerWarmWorkerBridgeImage.verify(artifact.copy(path = alias.resolve(preserved.fileName).toString()), root)
        }
    }

    @Test fun `noncanonical roots cannot be normalized into bridge authority`() {
        val root = classRoot()
        val artifact = archive(classes.toList())
        assertCode(CompilerWarmHostFailureCode.BRIDGE_PATH) { CompilerWarmWorkerBridgeImage.verify(artifact, root.resolve(".")) }
        assertCode(CompilerWarmHostFailureCode.BRIDGE_PATH) {
            CompilerWarmWorkerBridgeImage.verify(artifact.copy(path = "relative.jar"), root)
        }
    }

    @Test fun `archive entry count class bytes and whole archive bytes are bounded`() {
        val root = classRoot()
        assertCode(CompilerWarmHostFailureCode.BRIDGE_ENTRY_COUNT) {
            CompilerWarmWorkerBridgeImage.verify(archive(List(161) { "entry-$it" to byteArrayOf(0) }), root)
        }
        val oversized = classes.toMutableMap().also { it[classes.keys.first()] = ByteArray(Wire.MAX_RECEIPT_BYTES + 1) }
        assertCode(CompilerWarmHostFailureCode.BRIDGE_ENTRY_SIZE) {
            CompilerWarmWorkerBridgeImage.verify(archive(oversized.toList()), root)
        }
        val archivePath = Files.createTempFile(temporary.toRealPath(), "oversized-", ".jar")
        val bytes = ByteArray(2_097_153)
        Files.write(archivePath, bytes)
        assertCode(CompilerWarmHostFailureCode.BRIDGE_FILE) {
            CompilerWarmWorkerBridgeImage.verify(ArtifactIdentity("fake-data-only", Digests.sha256(bytes), archivePath.toString()), root)
        }
    }

    @Test fun `manifest classpath cannot introduce another code source`() {
        val root = classRoot()
        val manifest = "Manifest-Version: 1.0\r\nClass-Path: outside.jar\r\n\r\n".toByteArray()
        assertCode(CompilerWarmHostFailureCode.BRIDGE_MANIFEST_CLASSPATH) {
            CompilerWarmWorkerBridgeImage.verify(archive(listOf("META-INF/MANIFEST.MF" to manifest) + classes.toList()), root)
        }
    }

    @Test fun `diagnostics retain the first closed stage and typed code through later cleanup failures`() {
        val diagnostics = CompilerWarmHostDiagnostics()
        assertNull(diagnostics.failureStage)
        assertNull(diagnostics.failureCode)
        diagnostics.stage = CompilerWarmHostStage.BRIDGE_VERIFICATION
        diagnostics.record(CompilerWarmHostRejection(CompilerWarmHostFailureCode.BRIDGE_CLASS_BYTES))
        diagnostics.stage = CompilerWarmHostStage.SCRATCH_CLEANUP
        diagnostics.record(IOException("private cleanup path and exception text"))
        assertEquals(CompilerWarmHostStage.BRIDGE_VERIFICATION, diagnostics.failureStage)
        assertEquals(CompilerWarmHostFailureCode.BRIDGE_CLASS_BYTES, diagnostics.failureCode)
    }

    @Test fun `generic diagnostic classification never reads exception messages or traverses causes`() {
        val problem = object : IllegalArgumentException("private path, secret, and \u0000\n".repeat(1_000), IOException("private cause")) {
            override val message: String get() = error("message access is forbidden")
        }
        problem.addSuppressed(IOException("private suppressed text"))
        val cases = listOf(
            problem to CompilerWarmHostFailureCode.INVALID_ARGUMENT,
            IllegalStateException("private") to CompilerWarmHostFailureCode.INVALID_STATE,
            IOException("private") to CompilerWarmHostFailureCode.IO_FAILURE,
            SecurityException("private") to CompilerWarmHostFailureCode.SECURITY_FAILURE,
            CancellationException("private") to CompilerWarmHostFailureCode.CANCELLED,
            AssertionError("private") to CompilerWarmHostFailureCode.UNEXPECTED_FAILURE,
        )
        for ((failure, expected) in cases) {
            val diagnostics = CompilerWarmHostDiagnostics()
            diagnostics.record(failure)
            assertEquals(expected, diagnostics.failureCode)
            assertEquals(CompilerWarmHostStage.STARTUP_CAPTURE, diagnostics.failureStage)
        }
    }

    @Test fun `all exported diagnostic stage and failure code spellings are finite bounded constants`() {
        val codes = CompilerWarmHostStage.entries.map { it.code } + CompilerWarmHostFailureCode.entries.map { it.code }
        assertTrue(codes.size <= 64)
        assertTrue(codes.all { it.matches(Regex("[a-z_]{1,64}")) })
        for (code in CompilerWarmHostFailureCode.entries) {
            val diagnostics = CompilerWarmHostDiagnostics()
            diagnostics.record(CompilerWarmHostRejection(code))
            assertEquals(code, diagnostics.failureCode)
        }
    }

    private fun classRoot(): Path {
        assumeTrue(temporary.fileSystem.supportedFileAttributeViews().contains("posix"),
            "This private no-follow/keyed filesystem seam is POSIX-scoped; diagnostic tests remain portable")
        val root = Files.createTempDirectory(temporary, "fake-bridge-classes-").toRealPath()
        Files.createDirectories(root.resolve(prefix))
        classes.forEach { (name, bytes) -> Files.write(root.resolve(name), bytes) }
        return root
    }

    private fun metadata(): List<Pair<String, ByteArray>> = listOf(
        "META-INF/" to byteArrayOf(), "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\r\n\r\n".toByteArray(),
        "dev/" to byteArrayOf(), "dev/ktchecker/" to byteArrayOf(), prefix to byteArrayOf(),
    )

    private fun assertCode(code: CompilerWarmHostFailureCode, action: () -> Unit) {
        assertEquals(code, assertFailsWith<CompilerWarmHostRejection>(block = action).code)
    }

    /** Minimal stored ZIP data permits exact duplicate/NUL central-name fixtures without executing archived bytes. */
    private fun archive(entries: List<Pair<String, ByteArray>>): ArtifactIdentity {
        val local = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        fun ByteArrayOutputStream.word(value: Int) { write(value and 255); write((value ushr 8) and 255) }
        fun ByteArrayOutputStream.dword(value: Long) { word((value and 65_535).toInt()); word(((value ushr 16) and 65_535).toInt()) }
        entries.forEach { (name, bytes) ->
            val encoded = name.toByteArray(Charsets.UTF_8)
            val crc = CRC32().also { it.update(bytes) }.value
            val offset = local.size()
            local.dword(0x04034b50); local.word(20); local.word(0x0800); local.word(0); local.word(0); local.word(0)
            local.dword(crc); local.dword(bytes.size.toLong()); local.dword(bytes.size.toLong())
            local.word(encoded.size); local.word(0); local.write(encoded); local.write(bytes)
            central.dword(0x02014b50); central.word(20); central.word(20); central.word(0x0800); central.word(0); central.word(0); central.word(0)
            central.dword(crc); central.dword(bytes.size.toLong()); central.dword(bytes.size.toLong())
            central.word(encoded.size); central.word(0); central.word(0); central.word(0); central.word(0); central.dword(0)
            central.dword(offset.toLong()); central.write(encoded)
        }
        val centralOffset = local.size()
        local.write(central.toByteArray())
        local.dword(0x06054b50); local.word(0); local.word(0); local.word(entries.size); local.word(entries.size)
        local.dword(central.size().toLong()); local.dword(centralOffset.toLong()); local.word(0)
        val bytes = local.toByteArray()
        val path = Files.createTempFile(temporary.toRealPath(), "fake-bridge-", ".jar")
        Files.write(path, bytes)
        return ArtifactIdentity("fake-data-only-not-compiler-bytecode", Digests.sha256(bytes), path.toString())
    }
}

package dev.ktchecker.cli

import dev.ktchecker.contracts.Digests
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** Real temporary inode/byte/topology custody. No installed compiler/Gradle inputs are touched. */
class OwnerModelCommandCustodyTest {
    @TempDir lateinit var temporary: Path
    private val directory: Path get() = temporary.toRealPath()
    @BeforeTest fun supportedFilesystem() { assumeTrue(temporary.fileSystem.supportedFileAttributeViews().contains("unix")) }

    @Test fun copiedBytesHaveDistinctIdentityAndBothOriginalAndCopyStayPinned() {
        val source = directory.resolve("source.jar")
        val bytes = "NON-EXECUTABLE SYNTHETIC ARTIFACT\n".toByteArray()
        Files.write(source, bytes)
        val pin = OwnerModelFilePin.capture(source, 1024) { }
        val copy = pin.copyTo(directory.resolve("copy.jar")) { }
        assertEquals(pin.digest, copy.digest); assertEquals(pin.size, copy.size)
        assertNotEquals(pin.fileKey, copy.fileKey)
        assertEquals(1L, (Files.getAttribute(copy.path, "unix:nlink") as Number).toLong())
        assertContentEquals(bytes, copy.read(1024) { })
        pin.verify { }; copy.verify { }
        Files.writeString(source, "different original artifact")
        assertFailsWith<CliProblem> { pin.verify { } }
        copy.verify { } // Copy custody is independent, not a link back to its source.
        Files.setPosixFilePermissions(copy.path, PosixFilePermissions.fromString("rw-------"))
        Files.writeString(copy.path, "different private artifact")
        assertFailsWith<CliProblem> { copy.verify { } }
    }

    @Test fun identicalBytesAtAReplacedInodeOrChangedMetadataDoNotMatchAPin() {
        val path = directory.resolve("input.jar")
        Files.writeString(path, "same bytes")
        val pin = OwnerModelFilePin.capture(path, 1024) { }
        Files.setLastModifiedTime(path, FileTime.fromMillis(Files.getLastModifiedTime(path).toMillis() + 2000))
        assertFailsWith<CliProblem> { pin.verify { } }
        val repinned = OwnerModelFilePin.capture(path, 1024) { }
        // Keep the original inode alive at another path so this is not an inode-reuse assumption.
        Files.move(path, directory.resolve("original-retained.jar"))
        Files.writeString(path, "same bytes")
        assertEquals(repinned.digest, Digests.sha256(Files.readAllBytes(path)))
        assertFailsWith<CliProblem> { repinned.verify { } }
    }

    @Test fun symlinkAncestorsSymlinkFilesAndHardlinkedBytesAreNeverRepairedOrCopied() {
        val root = ownerModelFreshDirectory(directory.resolve("selected"))
        val original = root.path.resolve("source.jar")
        Files.writeString(original, "protected artifact")
        Files.createSymbolicLink(directory.resolve("alias"), root.path)
        assertFailsWith<CliProblem> { ownerModelPath(directory.resolve("alias/source.jar")) }
        val symbolic = directory.resolve("symbolic.jar")
        Files.createSymbolicLink(symbolic, original)
        assertFailsWith<CliProblem> { OwnerModelFilePin.capture(symbolic, 1024) { } }
        val linked = directory.resolve("linked.jar")
        Files.createLink(linked, original)
        assertFailsWith<CliProblem> { OwnerModelFilePin.capture(original, 1024) { } }
        assertFailsWith<CliProblem> { OwnerModelFilePin.capture(linked, 1024) { } }
        assertEquals("protected artifact", Files.readString(original))
        assertTrue(Files.isSymbolicLink(symbolic)); assertTrue(Files.isSymbolicLink(directory.resolve("alias")))
    }

    @Test fun completeTreeIncludesEmptyDirectoriesAndRejectsMembershipChangesAndAliases() {
        val root = ownerModelFreshDirectory(directory.resolve("tree"))
        Files.createDirectory(root.path.resolve("empty"))
        Files.createDirectory(root.path.resolve("files"))
        Files.writeString(root.path.resolve("files/a.jar"), "NON-EXECUTABLE a")
        val pin = OwnerModelTreePin.capture(root.path, 16, 4096, 1024) { }
        assertEquals(setOf("empty", "files"), pin.directories.keys)
        assertEquals(setOf("files/a.jar"), pin.files.keys)
        pin.verify { }
        Files.createDirectory(root.path.resolve("added-empty"))
        assertFailsWith<CliProblem> { pin.verify { } }
        Files.delete(root.path.resolve("added-empty"))
        pin.verify { }
        Files.createSymbolicLink(root.path.resolve("unknown-link"), root.path.resolve("files/a.jar"))
        assertFailsWith<CliProblem> { pin.verify { } }
    }

    @Test fun rootInodeFileCountAndByteBoundsAreCheckedWithoutWideningLimits() {
        val root = ownerModelFreshDirectory(directory.resolve("tree"))
        Files.writeString(root.path.resolve("one.jar"), "1234")
        Files.writeString(root.path.resolve("two.jar"), "5678")
        assertFailsWith<CliProblem> { OwnerModelTreePin.capture(root.path, 1, 100) { } }
        assertFailsWith<CliProblem> { OwnerModelTreePin.capture(root.path, 10, 7) { } }
        assertFailsWith<CliProblem> { OwnerModelTreePin.capture(root.path, 10, 100, 3) { } }
        val saved = directory.resolve("retained-original-root")
        Files.move(root.path, saved)
        Files.createDirectory(root.path)
        assertFailsWith<CliProblem> { root.verify() }
        assertTrue(Files.exists(saved.resolve("one.jar")))
    }

    @Test fun failedCopyRetainsItsPartialOutputAndNeverOverwritesOnRetry() {
        val source = directory.resolve("source.jar")
        Files.write(source, ByteArray(70_000) { 3 })
        val original = OwnerModelFilePin.capture(source, 100_000) { }
        val destination = directory.resolve("incomplete.jar")
        var fault = false
        assertFailsWith<IllegalStateException> {
            original.copyTo(destination) {
                if (Files.exists(destination) && !fault) { fault = true; throw IllegalStateException("SYNTHETIC copy failure") }
            }
        }
        assertTrue(fault && Files.exists(destination))
        val retained = Files.readAllBytes(destination)
        assertFailsWith<CliProblem> { original.copyTo(destination) { } }
        assertContentEquals(retained, Files.readAllBytes(destination))
        original.verify { }
    }
}

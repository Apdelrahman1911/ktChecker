package dev.ktchecker.compiler

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import kotlin.test.*
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Tiny non-executed owned trees only. No JDK copying, compiler/provider/helper launch or PID signalling. */
class CompilerFixtureScratchTest {
    @Test fun registeredSnapshotAndPreWriteOutputCleanupCheckDisappearanceAndKeepOuterRoot() = withRoot { outer ->
        val scratch = CompilerFixtureScratch.create(outer, "scratch-")
        val snapshot = scratch.newSnapshot(1)
        Files.createDirectories(snapshot.path.resolve("nested"))
        Files.writeString(snapshot.path.resolve("nested/source.kt"), "authored source bytes")
        val output = scratch.registerOutput(Files.createTempDirectory(scratch.outputRoot, "provider-"))
        Files.createDirectories(output.path.resolve("compiler"))
        Files.writeString(output.path.resolve("compiler/0.jar"), "non-executed bytes, not a JVM or JAR")
        val outputReceipt = scratch.cleanup(output)
        val snapshotReceipt = scratch.cleanup(snapshot)
        assertTrue(outputReceipt.passed)
        assertTrue(snapshotReceipt.passed)
        assertEquals(outputReceipt.visitedEntries, outputReceipt.deletedEntries)
        assertEquals(snapshotReceipt.visitedEntries, snapshotReceipt.deletedEntries)
        assertFalse(Files.exists(output.path))
        assertFalse(Files.exists(snapshot.path))
        assertTrue(scratch.cleanup(scratch.rootIdentity).passed)
        assertFalse(Files.exists(scratch.root))
        assertTrue(Files.isDirectory(outer))
    }

    @Test fun unregisteredOutsidePathCannotAcquireCleanupAuthority() = withRoot { outer ->
        val scratch = CompilerFixtureScratch.create(outer, "scratch-")
        val outside = Files.createDirectory(outer.resolve("not-scratch"))
        Files.writeString(outside.resolve("sentinel"), "retain")
        assertFails { scratch.registerOutput(outside) }
        val receipt = scratch.cleanup(fixtureDirectoryIdentity(outside))
        assertFalse(receipt.passed)
        assertEquals(0, receipt.deletedEntries)
        assertEquals("retain", Files.readString(outside.resolve("sentinel")))
        assertTrue(scratch.cleanup(scratch.rootIdentity).passed)
    }

    @Test fun replacedSnapshotIsNotDeletedAndFinalRootCannotReauthorizeTheSubstitution() = withRoot { outer ->
        val scratch = CompilerFixtureScratch.create(outer, "scratch-")
        val snapshot = scratch.newSnapshot(1)
        Files.writeString(snapshot.path.resolve("original"), "authored")
        val retained = snapshot.path.resolveSibling("retained-original")
        Files.move(snapshot.path, retained)
        Files.createDirectory(snapshot.path)
        Files.writeString(snapshot.path.resolve("substitute"), "do not delete")
        val rejected = scratch.cleanup(snapshot)
        assertFalse(rejected.passed)
        assertEquals(0, rejected.deletedEntries)
        val finalReceipt = scratch.cleanup(scratch.rootIdentity)
        assertFalse(finalReceipt.passed)
        assertEquals(0, finalReceipt.deletedEntries)
        assertEquals("authored", Files.readString(retained.resolve("original")))
        assertEquals("do not delete", Files.readString(snapshot.path.resolve("substitute")))
    }

    @Test fun linkedDescendantIsRejectedBeforeAnyDeletionAndItsTargetIsNeverFollowed() = withRoot { outer ->
        assumeTrue(outer.fileSystem.supportedFileAttributeViews().contains("posix"), "Link controls require POSIX")
        val scratch = CompilerFixtureScratch.create(outer, "scratch-")
        val snapshot = scratch.newSnapshot(1)
        val outside = Files.createDirectory(outer.resolve("outside"))
        Files.writeString(outside.resolve("sentinel"), "untouched")
        Files.writeString(snapshot.path.resolve("ordinary"), "also retained")
        Files.createSymbolicLink(snapshot.path.resolve("linked"), outside)
        val receipt = scratch.cleanup(snapshot)
        assertFalse(receipt.passed)
        assertEquals(0, receipt.deletedEntries)
        assertEquals("untouched", Files.readString(outside.resolve("sentinel")))
        assertEquals("also retained", Files.readString(snapshot.path.resolve("ordinary")))
    }

    @Test fun tinyInjectedEntryAndDepthBoundsFailBeforeMutation() = withRoot { outer ->
        val entries = CompilerFixtureScratch.create(outer, "entry-bound-", maxEntries = 3)
        val entryRoot = entries.newSnapshot(1)
        repeat(3) { Files.writeString(entryRoot.path.resolve("file-$it"), "one") }
        val entryReceipt = entries.cleanup(entryRoot)
        assertFalse(entryReceipt.passed)
        assertEquals(0, entryReceipt.deletedEntries)
        assertEquals(3, entryReceipt.maxEntries)
        assertTrue(Files.exists(entryRoot.path.resolve("file-0")))
        val depth = CompilerFixtureScratch.create(outer, "depth-bound-", maxDepth = 1)
        val depthRoot = depth.newSnapshot(1)
        Files.createDirectories(depthRoot.path.resolve("one/two"))
        val depthReceipt = depth.cleanup(depthRoot)
        assertFalse(depthReceipt.passed)
        assertEquals(0, depthReceipt.deletedEntries)
        assertEquals(1, depthReceipt.maxDepth)
        assertTrue(Files.isDirectory(depthRoot.path.resolve("one/two")))
    }

    @Test fun retiredPathReappearanceBlocksFinalCleanupInsteadOfDeletingNewBytes() = withRoot { outer ->
        val scratch = CompilerFixtureScratch.create(outer, "scratch-")
        val snapshot = scratch.newSnapshot(1)
        assertTrue(scratch.cleanup(snapshot).passed)
        Files.createDirectory(snapshot.path)
        Files.writeString(snapshot.path.resolve("later"), "not the retired root")
        val receipt = scratch.cleanup(scratch.rootIdentity)
        assertFalse(receipt.passed)
        assertEquals(0, receipt.deletedEntries)
        assertEquals("not the retired root", Files.readString(snapshot.path.resolve("later")))
    }

    @Test fun replacedPrivateRootDoesNotAuthorizeDeletionOfTheNewOrdinaryDirectory() = withRoot { outer ->
        val scratch = CompilerFixtureScratch.create(outer, "scratch-")
        Files.move(scratch.root, outer.resolve("original-private-root"))
        Files.createDirectory(scratch.root)
        Files.writeString(scratch.root.resolve("substitute"), "not owned")
        val receipt = scratch.cleanup(scratch.rootIdentity)
        assertFalse(receipt.passed)
        assertEquals(0, receipt.deletedEntries)
        assertEquals("not owned", Files.readString(scratch.root.resolve("substitute")))
    }

    @Test fun finalPrivateRootCleanupSkipsTheEmptyOuterRelativePathComponent() = withRoot { outer ->
        val scratch = CompilerFixtureScratch.create(outer, "scratch-")
        val receipt = scratch.cleanup(scratch.rootIdentity)
        assertTrue(receipt.passed, receipt.description().toString())
        assertEquals(3, receipt.visitedEntries) // Private root, snapshots group, outputs group.
        assertEquals(3, receipt.deletedEntries)
        assertTrue(Files.isDirectory(outer))
        assertFalse(Files.exists(scratch.root))
    }

    @Test fun uncertainChildQuarantineRetainsEveryJobAndAncestorWithoutInventoryOrDeletion() = withRoot { outer ->
        val scratch = CompilerFixtureScratch.create(outer, "scratch-")
        val snapshot = scratch.newSnapshot(1)
        val output = scratch.registerOutput(Files.createTempDirectory(scratch.outputRoot, "provider-"))
        val beforeCallback = Files.createTempDirectory(scratch.outputRoot, "provider-unregistered-")
        Files.writeString(snapshot.path.resolve("source.kt"), "retained source")
        Files.writeString(output.path.resolve("partial-output"), "retained output")
        Files.writeString(beforeCallback.resolve("partial-preparation"), "retained unknown output")
        scratch.quarantine("Native child shutdown was not acknowledged")
        for (identity in listOf(output, snapshot, scratch.rootIdentity)) {
            val receipt = scratch.cleanup(identity)
            assertFalse(receipt.passed)
            assertTrue(receipt.quarantined)
            assertEquals(0, receipt.visitedEntries)
            assertEquals(0, receipt.deletedEntries)
        }
        assertEquals("retained source", Files.readString(snapshot.path.resolve("source.kt")))
        assertEquals("retained output", Files.readString(output.path.resolve("partial-output")))
        assertEquals("retained unknown output", Files.readString(beforeCallback.resolve("partial-preparation")))
        assertFails { scratch.newSnapshot(2) }
        assertFails { scratch.registerOutput(beforeCallback) }
    }

    @Test fun quarantineIsIrreversibleAndCannotBeReleasedByASecondReasonOrLateObservation() = withRoot { outer ->
        val scratch = CompilerFixtureScratch.create(outer, "scratch-")
        scratch.quarantine("first missing child outcome")
        scratch.quarantine("a later path or exit observation does not release ownership")
        val receipt = scratch.cleanup(scratch.rootIdentity)
        assertEquals("first missing child outcome", receipt.failure)
        assertTrue(receipt.quarantined)
        assertFalse(receipt.passed)
        val unrelated = CompilerFixtureScratch.create(outer, "independent-")
        assertTrue(unrelated.cleanup(unrelated.rootIdentity).passed)
        assertTrue(Files.isDirectory(scratch.root))
    }

    private fun withRoot(action: (Path) -> Unit) {
        val root = Files.createTempDirectory("ktc-bounded-scratch-unit-").toRealPath()
        try {
            assumeTrue(Files.newDirectoryStream(root).use { it is SecureDirectoryStream<*> },
                "Bounded scratch cleanup requires secure no-follow directory streams; unsupported hosts are explicit gaps")
            action(root)
        } finally {
            // Unit-test-owned temporary tree only; Files.walk does not follow the deliberately inserted links.
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}

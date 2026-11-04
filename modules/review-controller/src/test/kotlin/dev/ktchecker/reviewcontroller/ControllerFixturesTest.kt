package dev.ktchecker.reviewcontroller

import dev.ktchecker.contracts.Digests
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** Test-owned permission perturbation only; not runtime or hosted failure qualification. */
class ControllerFixturesTest {
    @TempDir lateinit var temp: Path

    @Test fun sourcePerturbationChangesBytesButRestoresExactSealedPermissionsAndFailsVerification() {
        val f = ControllerFixtures(temp.resolve("fixture"))
        val capture = f.capture()
        val path = capture.root.resolve("lib/src/commonMain/kotlin/Common.kt")
        val expected = capture.snapshot.files.single { it.path == "lib/src/commonMain/kotlin/Common.kt" }
        val originalBytes = Files.readAllBytes(path)
        assertEquals(expected.digest, Digests.sha256(originalBytes))
        assertTrue(capture.verifyRegistered())
        assertTrue(Files.isRegularFile(path, NOFOLLOW_LINKS))

        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
        val originalPermissions = posix?.readAttributes()?.permissions()?.toSet()
        val dos = if (posix == null) assertNotNull(
            Files.getFileAttributeView(path, DosFileAttributeView::class.java, NOFOLLOW_LINKS)
        ) else null
        val originalReadOnly = dos?.readAttributes()?.isReadOnly
        if (originalPermissions != null) assertEquals(setOf(OWNER_READ), originalPermissions)
        else assertEquals(true, originalReadOnly)

        val replacement = "fun commonValue() = 999\n"
        capture.replaceCommonSourceForTest(replacement)

        assertEquals(replacement, Files.readString(path))
        assertNotEquals(expected.digest, Digests.sha256(Files.readAllBytes(path)))
        if (posix != null) assertEquals(originalPermissions, posix.readAttributes().permissions())
        else assertEquals(originalReadOnly, assertNotNull(dos).readAttributes().isReadOnly)
        assertFalse(capture.verifyRegistered())
        assertContentEquals(originalBytes, f.store.readBlob(f.identity, capture.snapshot.snapshotId, expected.digest))
    }
}

package dev.ktchecker.cli

import dev.ktchecker.contracts.ArtifactIdentity
import dev.ktchecker.contracts.Digests
import dev.ktchecker.workerprotocol.CompilerModelWire as ModelWire
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** Actual copies of NON-EXECUTABLE SYNTHETIC jar bytes; never a fake valid fixed compiler install. */
class OwnerModelCacheSeedTest {
    @TempDir lateinit var temporary: Path
    @BeforeTest fun supportedFilesystem() { assumeTrue(temporary.fileSystem.supportedFileAttributeViews().contains("unix")) }
    private var next = 0
    private val jar = "modules-2/files-2.1/unit.example/support/1/" + "0".repeat(40) + "/support-1.jar"
    private val metadata = "modules-2/metadata-2.1/descriptor.bin"

    private data class Seed(val cache: OwnerModelCacheSeed, val external: Path)
    private fun seed(): Seed {
        val root = ownerModelFreshDirectory(temporary.toRealPath().resolve("case-${next++}")).path
        val selected = ownerModelFreshDirectory(root.resolve("external-cache")).path
        val artifact = selected.resolve(jar)
        Files.createDirectories(artifact.parent)
        Files.writeString(artifact, "NON-EXECUTABLE SYNTHETIC JAR BYTES")
        Files.createDirectories(selected.resolve(metadata).parent)
        Files.writeString(selected.resolve(metadata), "mutable Gradle cache metadata, not read-closure proof")
        Files.writeString(selected.resolve("modules-2/modules-2.lock"), "original lock must not be copied")
        val storage = ownerModelFreshDirectory(root.resolve("artifacts")).path
        val run = ownerModelFreshDirectory(root.resolve("run")).path
        return Seed(OwnerModelCacheSeed(selected, storage, run, commandTestClock()), selected)
    }

    @Test fun copiedJarAndExcludedLockAreExactWhilePrivateMetadataIsExplicitlyMutable() {
        val (cache, external) = seed()
        assertEquals(listOf("modules-2/modules-2.lock"), cache.exclusions)
        assertEquals(setOf(jar, metadata), cache.copies.keys)
        assertFalse(Files.exists(cache.userHome.resolve("caches/modules-2/modules-2.lock")))
        val original = cache.seed.files.getValue(jar)
        val copy = cache.copies.getValue(jar)
        assertEquals(original.digest, copy.digest); assertNotEquals(original.fileKey, copy.fileKey)
        Files.writeString(cache.copies.getValue(metadata).path, "private updated metadata")
        // Original seed is not polled/re-imported on a later artifact audit.
        Files.writeString(external.resolve(jar), "external seed changed after installation")
        Files.writeString(external.resolve(metadata), "external metadata changed")
        cache.verifyArtifacts()
        assertEquals(original.digest, Digests.sha256(Files.readAllBytes(copy.path)))
        assertEquals(JsonPrimitive(false), cache.evidence()["configurationInputClosureProven"])
    }

    @Test fun changedInstalledJarOrPrivateRootCannotBeReusedAsTheOriginalArtifact() {
        val (cache, _) = seed()
        val copied = cache.copies.getValue(jar)
        Files.setPosixFilePermissions(copied.path, PosixFilePermissions.fromString("rw-------"))
        Files.writeString(copied.path, "tampered private jar")
        assertFailsWith<CliProblem> { cache.verifyArtifacts() }
        val (other, _) = seed()
        val caches = other.userHome.resolve("caches")
        Files.move(caches, other.userHome.resolve("retained-original-caches"))
        Files.createDirectory(caches)
        assertFailsWith<CliProblem> { other.verifyArtifacts() }
    }

    @Test fun syntheticUnpinnedCompilerSelectionFailsAndCannotBeRetriedOrLearnedFromAReport() {
        val (cache, _) = seed()
        assertEquals("owner_model_compiler_selection_unpinned", assertFailsWith<CliProblem> { cache.selectCompiler(listOf(jar)) }.code)
        assertEquals("owner_model_compiler_selection_replayed", assertFailsWith<CliProblem> { cache.selectCompiler(listOf(jar)) }.code)
        assertEquals(JsonNull, cache.evidence().getValue("artifactSelection").jsonObject["compilerSelection"])
    }

    @Test fun missingDuplicateTraversalNewCoordinatesAndOverlargeSelectionsNeverRegisterCompilerArtifacts() {
        val invalid = listOf(emptyList(), listOf(jar, jar), listOf("../$jar"), listOf("/absolute.jar"),
            listOf("modules-2/files-2.1/unit.example/support/1/not-a-hash/support-1.jar"),
            (0..ModelWire.MAX_ARTIFACTS).map { jar.replace("/support/", "/module$it/") })
        for (selection in invalid) {
            val (cache, _) = seed()
            assertEquals("owner_model_compiler_selection_invalid", assertFailsWith<CliProblem> { cache.selectCompiler(selection) }.code)
            assertEquals("owner_model_compiler_selection_replayed", assertFailsWith<CliProblem> { cache.selectCompiler(listOf(jar)) }.code)
        }
        val (cache, _) = seed()
        assertEquals("owner_model_compiler_artifact_unregistered", assertFailsWith<CliProblem> {
            cache.selectCompiler(listOf(jar.replace("support-1.jar", "not-registered.jar")))
        }.code)
    }

    @Test fun immutableArtifactComparatorRequiresExactPathCoordinateDigestAndOrderNotByteSetEquality() {
        val sameBytes = Digests.sha256("NON-EXECUTABLE equal artifact bytes")
        val first = ArtifactIdentity("unit:z:1", sameBytes, "/synthetic/z.jar")
        val second = ArtifactIdentity("unit:a:1", sameBytes, "/synthetic/a.jar")
        val supplied = mutableListOf(first, second)
        val selected = OwnerModelOrderedCompilerArtifacts(supplied)
        supplied.reverse(); supplied.clear()
        assertEquals(listOf(first, second), selected.artifacts)
        assertTrue(selected.matches(listOf(first, second)))
        assertFalse(selected.matches(listOf(second, first)))
        assertFalse(selected.matches(listOf(first.copy(path = "/elsewhere/z.jar"), second)))
        assertFalse(selected.matches(listOf(first.copy(coordinate = "unit:other:1"), second)))
        assertFalse(selected.matches(listOf(first.copy(digest = Digests.sha256("changed")), second)))
        assertFalse(selected.matches(listOf(first))); assertFalse(selected.matches(listOf(first, second, second)))
        assertFailsWith<UnsupportedOperationException> { (selected.artifacts as MutableList<*>).clear() }
    }
}

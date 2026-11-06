package dev.ktchecker.compiler

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Tiny owned-filesystem/output controls only; no runner, provider, compiler, or historical evidence execution. */
class CompilerFixtureRunDirectoryTest {
    @Test fun freshRunsUseRepositoryBuildWithoutChangingFixtureSourcesOrTrackedHistory() = withRoot { repository ->
        val fixtures = Files.createDirectories(repository.resolve("providers/compiler-fixtures"))
        val source = Files.createDirectories(fixtures.resolve("cases/unit-only")).resolve("source.kt")
        Files.writeString(source, "// synthetic filesystem marker, not a compiler input\n")
        val history = Files.createDirectories(fixtures.resolve("runs/historical-unit-marker"))
        val historicalBytes = "synthetic historical marker, not execution evidence\n".toByteArray()
        Files.write(history.resolve("receipt.json"), historicalBytes)
        val fixtureIdentity = fixtureDirectoryIdentity(fixtures)
        val historyIdentity = fixtureDirectoryIdentity(history)
        val runId = "2026-11-05T00-00-00.000000000Z-diagnostic_breadth-00000000-0000-0000-0000-000000000000"

        val run = compilerFixtureCreateRunDirectory(repository, runId)

        assertEquals(repository.resolve("build/compiler-fixtures/runs").resolve(runId), run)
        assertEquals(run, run.toRealPath())
        assertFalse(run.startsWith(fixtures))
        assertEquals(emptySet(), children(run))
        assertEquals(setOf("historical-unit-marker"), children(history.parent))
        assertEquals(setOf("receipt.json"), children(history))
        assertContentEquals(historicalBytes, Files.readAllBytes(history.resolve("receipt.json")))
        assertEquals("// synthetic filesystem marker, not a compiler input\n", Files.readString(source))
        requireFixtureDirectory(fixtureIdentity)
        requireFixtureDirectory(historyIdentity)
        if (repository.fileSystem.supportedFileAttributeViews().contains("posix"))
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(run, NOFOLLOW_LINKS))
    }

    @Test fun safeParentsAreReusableButExistingRunsAndFilesCannotBeResumedOrMerged() = withRoot { repository ->
        val runs = Files.createDirectories(repository.resolve("build/compiler-fixtures/runs"))
        val first = compilerFixtureCreateRunDirectory(repository, "unit-first")
        Files.writeString(first.resolve("sentinel"), "keep first")
        val firstIdentity = fixtureDirectoryIdentity(first)
        assertFailsWith<FileAlreadyExistsException> { compilerFixtureCreateRunDirectory(repository, "unit-first") }
        Files.writeString(runs.resolve("occupied-file"), "keep file")
        assertFailsWith<FileAlreadyExistsException> { compilerFixtureCreateRunDirectory(repository, "occupied-file") }
        val second = compilerFixtureCreateRunDirectory(repository, "unit-second")
        assertNotEquals(first, second)
        assertEquals(setOf("unit-first", "unit-second", "occupied-file"), children(runs))
        assertEquals("keep first", Files.readString(first.resolve("sentinel")))
        assertEquals("keep file", Files.readString(runs.resolve("occupied-file")))
        requireFixtureDirectory(firstIdentity)
    }

    @Test fun linkedFixedAncestorsFailBeforeAnyRedirectedDescendantWrite() {
        outputComponents.forEach { relative -> withRoot { repository ->
            assumeTrue(repository.fileSystem.supportedFileAttributeViews().contains("posix"), "Link control requires POSIX")
            val blocked = repository.resolve(relative)
            Files.createDirectories(blocked.parent)
            val outside = Files.createDirectory(repository.resolve("outside"))
            Files.writeString(outside.resolve("sentinel"), "keep outside")
            Files.createSymbolicLink(blocked, outside)
            assertFails { compilerFixtureCreateRunDirectory(repository, "unit-run") }
            assertTrue(Files.isSymbolicLink(blocked))
            assertEquals(setOf("sentinel"), children(outside))
            assertEquals("keep outside", Files.readString(outside.resolve("sentinel")))
        } }
    }

    @Test fun danglingFixedAncestorsAreNeitherReplacedNorFollowed() {
        outputComponents.forEach { relative -> withRoot { repository ->
            assumeTrue(repository.fileSystem.supportedFileAttributeViews().contains("posix"), "Link control requires POSIX")
            val blocked = repository.resolve(relative)
            Files.createDirectories(blocked.parent)
            val missing = repository.resolve("missing-target")
            Files.createSymbolicLink(blocked, missing)
            assertFails { compilerFixtureCreateRunDirectory(repository, "unit-run") }
            assertTrue(Files.isSymbolicLink(blocked))
            assertFalse(Files.exists(missing, NOFOLLOW_LINKS))
        } }
    }

    @Test fun nonDirectoryFixedAncestorsAreRejectedWithoutReplacement() {
        outputComponents.forEach { relative -> withRoot { repository ->
            val blocked = repository.resolve(relative)
            Files.createDirectories(blocked.parent)
            Files.writeString(blocked, "keep ordinary file")
            assertFails { compilerFixtureCreateRunDirectory(repository, "unit-run") }
            assertTrue(Files.isRegularFile(blocked, NOFOLLOW_LINKS))
            assertEquals("keep ordinary file", Files.readString(blocked))
        } }
    }

    @Test fun repositoryMustAlreadyBeCanonicalAndUnlinkedBeforeBuildCreation() = withRoot { root ->
        val repository = Files.createDirectory(root.resolve("repository"))
        assertFails { compilerFixtureCreateRunDirectory(repository.resolve("."), "unit-run") }
        assertFalse(Files.exists(repository.resolve("build"), NOFOLLOW_LINKS))
        assumeTrue(root.fileSystem.supportedFileAttributeViews().contains("posix"), "Link control requires POSIX")
        val linked = Files.createSymbolicLink(root.resolve("alias"), repository)
        assertFails { compilerFixtureCreateRunDirectory(linked, "unit-run") }
        assertFalse(Files.exists(repository.resolve("build"), NOFOLLOW_LINKS))
    }

    @Test fun runNameMustBeOneNonDotPathComponentBeforeAnyOutputCreation() = withRoot { repository ->
        listOf("", " ", ".", "..", "../escape", "child/run", "run/", repository.resolve("escape").toString()).forEach { name ->
            assertFails { compilerFixtureCreateRunDirectory(repository, name) }
            assertFalse(Files.exists(repository.resolve("build"), NOFOLLOW_LINKS))
            assertFalse(Files.exists(repository.resolve("escape"), NOFOLLOW_LINKS))
        }
    }

    @Test fun breadthLatestStaysInBuildWhileOrdinaryConvenienceSidecarRemainsExplicit() = withRoot { repository ->
        val fixtures = Files.createDirectories(repository.resolve("providers/compiler-fixtures"))
        val ordinary = checkNotNull(compilerFixtureLatestSidecar(fixtures, null))
        assertEquals(fixtures.resolve("evidence/compiler-jvm-${CompilerRuleRegistry.VERSION}.json"), ordinary)
        Files.createDirectory(ordinary.parent)
        Files.writeString(ordinary, "ordinary unit marker, not execution evidence")
        val breadthLatest = compilerFixtureDiagnosticBreadthLatestSidecar(true,
            compilerFixtureNullabilityLatestSidecar(false,
                compilerFixtureSupplementalLatestSidecar(false, ordinary)))
        assertNull(breadthLatest)
        val run = compilerFixtureCreateRunDirectory(repository, "unit-breadth")
        val store = CompilerFixtureEvidence(repository, run, breadthLatest, maximumBytes = 262_144, terminalReserveBytes = 16_384)
        store.writeNew(run.resolve("invocation.json"), "synthetic filesystem control\n".toByteArray())
        store.replaceLatest(run.resolve("latest-checkpoint.json"), "private unit pointer\n".toByteArray())
        assertFails { store.replaceLatest(ordinary, "forbidden".toByteArray()) }
        assertEquals("ordinary unit marker, not execution evidence", Files.readString(ordinary))
        assertFalse(Files.exists(fixtures.resolve("runs"), NOFOLLOW_LINKS))
        val ordinaryRun = compilerFixtureCreateRunDirectory(repository, "unit-ordinary")
        val ordinaryStore = CompilerFixtureEvidence(repository, ordinaryRun, ordinary, maximumBytes = 262_144, terminalReserveBytes = 16_384)
        ordinaryStore.replaceLatest(ordinary, "explicit ordinary unit update\n".toByteArray())
        assertEquals("explicit ordinary unit update\n", Files.readString(ordinary))
    }

    private val outputComponents = listOf("build", "build/compiler-fixtures", "build/compiler-fixtures/runs")

    private fun children(path: Path): Set<String> = Files.list(path).use { entries ->
        entries.map { it.fileName.toString() }.toList().toSet()
    }

    private fun withRoot(action: (Path) -> Unit) {
        val root = Files.createTempDirectory("ktc-fixture-run-directory-unit-").toRealPath()
        try { action(root) }
        finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
}

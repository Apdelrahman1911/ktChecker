package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.*

/** Tiny synthetic copy/authority-boundary tests only; no Gradle, JVM child or native process. */
class OwnerFixtureExecutionHomeTest {
    private val directoryMode = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
    private val readOnly = setOf(PosixFilePermission.OWNER_READ)
    private val writable = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

    private fun overwriteReadOnly(path: Path, text: String) {
        val original = Files.getPosixFilePermissions(path)
        Files.setPosixFilePermissions(path, original + PosixFilePermission.OWNER_WRITE)
        try { Files.writeString(path, text) } finally { Files.setPosixFilePermissions(path, original) }
    }

    private inner class Fixture {
        val work = Files.createTempDirectory("ktchecker-tiny-execution-home-").toRealPath()
        val seed: Path
        val dependency = "caches/modules-2/files-2.1/synthetic/artifact/1/hash/artifact.jar"
        val metadata = "caches/9.6.1/metadata.bin"
        val dsl = "caches/9.6.1/dsl/Program.class"
        val files: List<OwnerRuntimeFile>
        val directories: List<OwnerRuntimeDirectory>
        val inputDigest = Digests.sha256("synthetic fixed owner input registry, not an actual preparation")
        init {
            assumeOwnerFixtureAnchoredReads(work)
            assumeTrue("posix" in work.fileSystem.supportedFileAttributeViews() && "unix" in work.fileSystem.supportedFileAttributeViews(),
                "This boundary requires POSIX permissions and Unix link counts; no unsupported-host copy proof is claimed")
            seed = Files.createDirectory(work.resolve("seed"))
            Files.setPosixFilePermissions(seed, directoryMode)
            val content = linkedMapOf(dependency to "JarA", metadata to "meta", dsl to "code", "caches/9.6.1/helper" to "exec")
            content.forEach { (relative, bytes) ->
                val path = seed.resolve(relative); Files.createDirectories(path.parent); Files.writeString(path, bytes)
                Files.setPosixFilePermissions(path, readOnly + if (relative.endsWith("helper")) setOf(PosixFilePermission.OWNER_EXECUTE) else emptySet())
            }
            Files.createDirectory(seed.resolve("caches/9.6.1/empty"))
            directories = Files.walk(seed).use { paths -> paths.filter { it != seed && Files.isDirectory(it) }.map { path ->
                Files.setPosixFilePermissions(path, directoryMode)
                OwnerRuntimeDirectory("seed", seed.relativize(path).toString().replace('\\', '/'))
            }.toList() }
            files = content.map { (relative, bytes) -> OwnerRuntimeFile("seed", relative, Digests.sha256(bytes), bytes.toByteArray().size.toLong(), relative.endsWith("helper")) }
        }
        fun derive(name: String = "execution-home", selectedFiles: List<OwnerRuntimeFile> = files,
                   selectedDirectories: List<OwnerRuntimeDirectory> = directories): OwnerFixtureExecutionHome =
            OwnerFixtureExecutionHome.derive(seed, selectedFiles, selectedDirectories, inputDigest, work.resolve(name), work.resolve("$name-evidence.json"))
    }

    @Test fun initialExactCopyBindsConcreteMappingModesTopologyAndOnlySelectedDependencies(): Unit {
        val fixture = Fixture(); val home = fixture.derive()
        val evidence = ContractJson.decodeFromString<OwnerExecutionHomeEvidence>(Files.readString(home.evidencePath))
        assertEquals(fixture.seed.toString(), evidence.sourceRoot)
        assertEquals(home.root.toString(), evidence.executionHome)
        assertEquals(fixture.inputDigest, evidence.sourceRuntimeInputDigest)
        assertEquals(fixture.files.size + fixture.directories.size + 1, evidence.entries.size)
        assertTrue(Files.isDirectory(home.root.resolve("caches/9.6.1/empty")))
        evidence.entries.forEach { entry ->
            assertNotEquals(entry.sourceFileKey, entry.copyFileKey)
            if (!entry.directory) {
                assertEquals(1L, (Files.getAttribute(home.root.resolve(entry.relativePath), "unix:nlink") as Number).toLong())
                assertEquals(entry.digest, hashFile(home.root.resolve(entry.relativePath), 1024, entry.size))
                assertEquals(entry.digest, hashFile(fixture.seed.resolve(entry.relativePath), 1024, entry.size))
            }
        }
        assertEquals(listOf(home.root.resolve(fixture.dependency).toString()), home.dependencyArtifacts.map { it.path })
        assertEquals(readOnly, Files.getPosixFilePermissions(home.root.resolve(fixture.dependency)))
        assertEquals(writable, Files.getPosixFilePermissions(home.root.resolve(fixture.metadata)))
        assertTrue(Files.isExecutable(home.root.resolve("caches/9.6.1/helper")))
        home.dependencyArtifacts.forEach(home::verifyDependencyArtifact)
        home.verifyInputs()
        val other = fixture.derive("other-execution-home")
        assertNotEquals(home.digest, other.digest)
        val profile = GradleModelExporter.profile(fixture.work.resolve("synthetic-gradle"), Digests.sha256("synthetic launcher"),
            fixture.work.resolve("synthetic-jdk"), home.root)
        assertEquals(home.root.toString(), profile.environment["GRADLE_USER_HOME"])
        assertNotEquals(fixture.seed.toString(), profile.environment["GRADLE_USER_HOME"])
        assertFalse(profile.environment.values.any { "{output}" in it })
    }

    @Test fun existingHomesParentAliasesAndSeedAliasesCannotBeDerivationInputs(): Unit {
        val existing = Fixture(); Files.createDirectory(existing.work.resolve("execution-home"))
        assertEquals("owner_fixture_execution_home_not_fresh", assertFailsWith<OwnerFixtureProblem> { existing.derive() }.code)
        val parentAlias = Fixture(); val parent = Files.createDirectory(parentAlias.work.resolve("real-parent"))
        createOwnerFixtureSymbolicLink(parentAlias.work.resolve("aliased-parent"), parent)
        assertEquals("owner_fixture_execution_home_alias_or_nonregular", assertFailsWith<OwnerFixtureProblem> {
            parentAlias.derive("aliased-parent/execution-home")
        }.code)
        val seedAlias = Fixture()
        createOwnerFixtureSymbolicLink(seedAlias.seed.resolve("caches/alias.lock"), seedAlias.seed.resolve(seedAlias.dependency))
        assertEquals("owner_fixture_execution_home_alias_or_nonregular", assertFailsWith<OwnerFixtureProblem> { seedAlias.derive() }.code)
    }

    @Test fun incompleteInitialDirectoriesBadDigestsAndCopyBoundsFailWithoutReuse(): Unit {
        val missing = Fixture()
        assertEquals("owner_fixture_execution_home_source_inventory_changed", assertFailsWith<OwnerFixtureProblem> {
            missing.derive(selectedDirectories = missing.directories.filterNot { it.relativePath.endsWith("/empty") })
        }.code)
        val badBytes = Fixture()
        assertEquals("owner_fixture_execution_home_source_changed", assertFailsWith<OwnerFixtureProblem> {
            badBytes.derive(selectedFiles = badBytes.files.map { if (it.relativePath == badBytes.dsl) it.copy(digest = Digests.sha256("bad!")) else it })
        }.code)
        assertTrue(Files.exists(badBytes.work.resolve("execution-home"))) // Failed partial attempt retained, not reusable.
        assertEquals("owner_fixture_execution_home_not_fresh", assertFailsWith<OwnerFixtureProblem> { badBytes.derive() }.code)
        val bounded = Fixture()
        assertEquals("owner_fixture_execution_home_bound", assertFailsWith<OwnerFixtureProblem> {
            bounded.derive(selectedFiles = bounded.files.map { it.copy(size = 513L * 1024 * 1024) })
        }.code)
        assertFalse(Files.exists(bounded.work.resolve("execution-home")))
    }

    @Test fun initialMapIsRecheckedBeforeLaunchIncludingEmptyDirectoriesAndBytes(): Unit {
        val added = Fixture(); val addedHome = added.derive()
        Files.createDirectory(addedHome.root.resolve("caches/new-empty"))
        assertEquals("owner_fixture_execution_home_initial_inventory_changed", assertFailsWith<OwnerFixtureProblem> { addedHome.prepareForLaunch() }.code)
        val changed = Fixture(); val changedHome = changed.derive()
        Files.writeString(changedHome.root.resolve(changed.dsl), "edit")
        assertEquals("owner_fixture_execution_home_initial_bytes_changed", assertFailsWith<OwnerFixtureProblem> { changedHome.prepareForLaunch() }.code)
        val permissions = Fixture(); val permissionHome = permissions.derive()
        Files.setPosixFilePermissions(permissionHome.root.resolve(permissions.dsl), directoryMode)
        assertEquals("owner_fixture_execution_home_permissions_changed", assertFailsWith<OwnerFixtureProblem> { permissionHome.prepareForLaunch() }.code)
    }

    @Test fun postLaunchMetadataDslNativeAndEqualByteGeneratedJarsNeverBecomeArtifactAuthority(): Unit {
        val fixture = Fixture(); val home = fixture.derive(); home.prepareForLaunch()
        Files.writeString(home.root.resolve(fixture.metadata), "generated metadata with different size")
        Files.writeString(home.root.resolve(fixture.dsl), "generated executable cache content")
        val generated = home.root.resolve("native/generated.jar"); Files.createDirectory(generated.parent)
        Files.writeString(generated, "JarA")
        home.verifyInputs()
        assertEquals("owner_fixture_artifact_unregistered", assertFailsWith<OwnerFixtureProblem> {
            home.verifyDependencyArtifact(ModelArtifact("report-selected equal-byte output", generated.toString(), Digests.sha256("JarA"), 4))
        }.code)
        val output = fixture.work.resolve("project-build-output.jar"); Files.writeString(output, "JarA")
        assertEquals("owner_fixture_artifact_unregistered", assertFailsWith<OwnerFixtureProblem> {
            home.verifyDependencyArtifact(ModelArtifact("report-selected project output", output.toString(), Digests.sha256("JarA"), 4))
        }.code)
        assertEquals(1, home.dependencyArtifacts.size)
        fixture.files.forEach { assertEquals(it.digest, hashFile(fixture.seed.resolve(it.relativePath), 1024, it.size)) }
        assertEquals("owner_fixture_execution_home_already_prepared", assertFailsWith<OwnerFixtureProblem> { home.prepareForLaunch() }.code)
    }

    @Test fun preEnrolledDependencyBytesSizesPermissionsReplacementAndHardlinksRemainRejected(): Unit {
        val mutations = listOf<Pair<String, (Path, Path) -> Unit>>(
            "owner_fixture_derived_dependency_changed" to { path, _ -> overwriteReadOnly(path, "JarB") },
            "owner_fixture_derived_dependency_changed" to { path, _ -> overwriteReadOnly(path, "larger") },
            "owner_fixture_execution_home_permissions_changed" to { path, _ -> Files.setPosixFilePermissions(path, readOnly + PosixFilePermission.OWNER_EXECUTE); Unit },
            "owner_fixture_execution_home_identity_changed" to { path, _ ->
                val replacement = path.resolveSibling("replacement.jar"); Files.writeString(replacement, "JarA")
                Files.setPosixFilePermissions(replacement, readOnly); Files.delete(path); Files.move(replacement, path); Unit
            },
            "owner_fixture_execution_home_copy_not_independent" to { path, root -> Files.createLink(root.resolve("new-hardlink.jar"), path); Unit })
        mutations.forEach { (code, mutate) ->
            val fixture = Fixture(); val home = fixture.derive(); home.prepareForLaunch()
            mutate(home.root.resolve(fixture.dependency), home.root)
            assertEquals(code, assertFailsWith<OwnerFixtureProblem> { home.verifyInputs() }.code)
        }
    }

    @Test fun sourceSeedDriftStillFailsItsIndependentImmutableInputVerifier(): Unit {
        val fixture = Fixture(); val home = fixture.derive(); home.prepareForLaunch()
        overwriteReadOnly(fixture.seed.resolve(fixture.dsl), "edit")
        assertEquals("owner_fixture_artifact_registry_mismatch", assertFailsWith<OwnerFixtureProblem> {
            verifyOwnerRuntimeFileBytes(mapOf("seed" to fixture.seed), fixture.files)
        }.code)
        // A generated copy with the original digest does not repair or re-enroll the authoritative seed.
        assertEquals("code", Files.readString(home.root.resolve(fixture.dsl)))
        assertEquals(fixture.files.single { it.relativePath == fixture.dsl }.digest, hashFile(home.root.resolve(fixture.dsl), 1024, 4))
    }
}

package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.Digests
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.*

/** Fixed source/runtime-boundary tests only; they do not run or attest Gradle. */
class OwnerFixtureDomainTest {
    private fun temporaryDirectory(prefix: String): Path = Files.createTempDirectory(prefix).toRealPath()
    private fun fixture(): Path {
        val selected = (System.getProperty("ktchecker.model.fixtures")?.let(Path::of)
            ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .map { it.resolve("providers/model-fixtures") }.first { Files.isDirectory(it) }).toRealPath()
        assumeOwnerFixtureAnchoredReads(selected)
        return selected
    }

    @Test fun fixedContractCannotEnrollEditedBuildScriptsExtraInputsOrJsonApproval(): Unit {
        val directory = fixture().resolve("jvm-owner-export")
        val contract = OwnerFixtureContract.load(directory)
        fun copySources(): Path {
            val result = temporaryDirectory("ktchecker-model-owner-source-")
            val project = directory.resolve("project")
            contract.value.files.forEach { file ->
                val target = result.resolve(file.path); Files.createDirectories(target.parent)
                Files.copy(project.resolve(file.path), target)
            }
            return result
        }
        val extra = copySources(); Files.writeString(extra.resolve("uncaptured-generator-input.txt"), "not in the fixed producer contract")
        assertEquals("owner_fixture_source_inventory_changed", assertFailsWith<OwnerFixtureProblem> {
            contract.capture(extra, temporaryDirectory("ktchecker-owner-domain-work-"))
        }.code)
        val changed = copySources()
        val buildFile = changed.resolve("build.gradle.kts")
        val original = Files.readString(buildFile)
        val edited = original.replace("\"2.4.21\"", "\"2.4.20\"")
        assertNotEquals(original, edited)
        assertEquals(original.toByteArray().size, edited.toByteArray().size)
        Files.writeString(buildFile, edited)
        assertEquals("owner_fixture_source_bytes_changed", assertFailsWith<OwnerFixtureProblem> {
            contract.capture(changed, temporaryDirectory("ktchecker-owner-domain-work-"))
        }.code)
        // A size-changing edit must also be rejected, before a digest can be checked.
        val resized = copySources(); Files.writeString(resized.resolve("build.gradle.kts"), "// edited executable configuration\n")
        assertEquals("model_invalid", assertFailsWith<ProjectModelException> {
            contract.capture(resized, temporaryDirectory("ktchecker-owner-domain-work-"))
        }.code)
        val editedContract = temporaryDirectory("ktchecker-model-edited-contract-")
        Files.writeString(editedContract.resolve("input-contract.json"),
            Files.readString(directory.resolve("input-contract.json")).trimEnd().dropLast(1) + ",\"approved\":true}\n")
        assertEquals("owner_fixture_contract_pin_changed", assertFailsWith<OwnerFixtureProblem> { OwnerFixtureContract.load(editedContract) }.code)
    }

    @Test fun sourceDomainRejectsCoherentPartialRootsResourcesConfigAndDeletedContexts(): Unit {
        val directory = fixture().resolve("jvm-owner-export")
        val contract = OwnerFixtureContract.load(directory)
        val capture = contract.capture(directory.resolve("project"), temporaryDirectory("ktchecker-owner-domain-work-"))
        val graph = sourceGraph(contract)
        contract.verifySourceDomain(capture.snapshot, graph)
        val variants = listOf(
            graph.copy(sourceSets = graph.sourceSets.map { it.copy(sourceRoots = emptyList(), sources = emptyList()) }) to "owner_fixture_source_root_domain_incomplete",
            graph.copy(sourceSets = graph.sourceSets.map { it.copy(resources = emptyList()) }) to "owner_fixture_source_resource_members_incomplete",
            graph.copy(configurationInputs = graph.configurationInputs.drop(1)) to "owner_fixture_executable_configuration_readset_incomplete",
            graph.copy(compilations = graph.compilations.drop(1)) to "owner_fixture_compilation_domain_incomplete",
            graph.copy(compilations = graph.compilations.map { it.copy(sourceFiles = emptyList()) }) to "owner_fixture_actual_compile_members_incomplete",
            graph.copy(compilations = graph.compilations.map { it.copy(associatedCompilationIds = emptyList()) }) to "owner_fixture_actual_compilation_graph_changed",
            graph.copy(includedBuilds = listOf("not-in-fixed-contract")) to "owner_fixture_generation_or_composite_domain_changed",
        )
        variants.forEach { (changed, code) ->
            assertEquals(code, assertFailsWith<OwnerFixtureProblem> { contract.verifySourceDomain(capture.snapshot, changed) }.code)
        }
        assertEquals("owner_fixture_capture_domain_incomplete", assertFailsWith<OwnerFixtureProblem> {
            contract.verifySourceDomain(capture.snapshot.copy(files = capture.snapshot.files.drop(1)), graph)
        }.code)
    }

    @Test fun generatorAndAuxiliaryInputAbsenceMustMatchTheIndependentlyPinnedDomain(): Unit {
        val directory = fixture().resolve("jvm-owner-export")
        val contract = OwnerFixtureContract.load(directory)
        val capture = contract.capture(directory.resolve("project"), temporaryDirectory("ktchecker-owner-domain-work-"))
        val graph = sourceGraph(contract)
        val generated = graph.copy(generatedInputs = listOf(GeneratedSourceInput(":opaqueGenerate", listOf(":lib|main"),
            emptyList(), emptyList(), listOf("generated"), "pending")))
        assertEquals("owner_fixture_generation_or_composite_domain_changed", assertFailsWith<OwnerFixtureProblem> {
            contract.verifySourceDomain(capture.snapshot, generated)
        }.code)
        val auxiliary = graph.copy(compilations = graph.compilations.map { it.copy(auxiliaryInputFiles = listOf(contract.value.files.first())) })
        assertEquals("owner_fixture_auxiliary_domain_unknown", assertFailsWith<OwnerFixtureProblem> {
            contract.verifySourceDomain(capture.snapshot, auxiliary)
        }.code)
    }

    @Test fun runtimeFreshnessRejectsAddedFilesDirectoriesAndSeedConfiguration(): Unit {
        val work = temporaryDirectory("ktchecker-owner-runtime-inventory-")
        val roots = listOf("gradle", "jdk", "seed").associateWith { Files.createDirectory(work.resolve(it)) }
        val files = listOf(OwnerRuntimeFile("gradle", "launcher", "unit-only", 0, false),
            OwnerRuntimeFile("jdk", "java", "unit-only", 0, false), OwnerRuntimeFile("seed", "caches/selected.jar", "unit-only", 0, false))
        val directories = listOf(OwnerRuntimeDirectory("seed", "caches"))
        Files.createFile(roots.getValue("gradle").resolve("launcher")); Files.createFile(roots.getValue("jdk").resolve("java"))
        Files.createDirectory(roots.getValue("seed").resolve("caches")); Files.createFile(roots.getValue("seed").resolve("caches/selected.jar"))
        fun verify() = verifyOwnerRuntimeInventories(roots, files, directories)
        verify()
        for (kind in listOf("gradle", "jdk")) {
            val added = Files.createFile(roots.getValue(kind).resolve("new-semantic-input"))
            assertEquals("owner_fixture_runtime_inventory_changed", assertFailsWith<OwnerFixtureProblem> { verify() }.code)
            Files.delete(added)
            val addedDirectory = Files.createDirectory(roots.getValue(kind).resolve("new-empty-directory"))
            assertEquals("owner_fixture_runtime_inventory_changed", assertFailsWith<OwnerFixtureProblem> { verify() }.code)
            Files.delete(addedDirectory)
        }
        for (name in listOf("gradle.properties", "init.d")) {
            val added = if (name == "init.d") Files.createDirectory(roots.getValue("seed").resolve(name)) else Files.createFile(roots.getValue("seed").resolve(name))
            assertEquals("owner_fixture_external_seed_configuration_forbidden", assertFailsWith<OwnerFixtureProblem> { verify() }.code)
            Files.delete(added)
        }
        for (name in listOf("unregistered.jar", "mutable-bookkeeping.bin", "Program.class")) {
            val added = Files.createFile(roots.getValue("seed").resolve("caches/$name"))
            assertEquals("owner_fixture_seed_inventory_changed", assertFailsWith<OwnerFixtureProblem> { verify() }.code)
            Files.delete(added)
        }
        // A directory named like a lock is still an unregistered semantic input.
        for (name in listOf("new-empty-directory", "not-a-file.lock")) {
            val added = Files.createDirectory(roots.getValue("seed").resolve("caches/$name"))
            assertEquals("owner_fixture_seed_inventory_changed", assertFailsWith<OwnerFixtureProblem> { verify() }.code)
            Files.delete(added)
        }
        for (name in listOf("cache.lock", "cache.lck")) {
            val lock = Files.createFile(roots.getValue("seed").resolve("caches/$name"))
            verify()
            Files.writeString(lock, "lock bookkeeping may change only inside registered directories")
            verify()
            Files.delete(lock)
            verify()
        }
    }

    @Test fun runtimeFreshnessRejectsAliasesIncludingLockNames(): Unit {
        val work = temporaryDirectory("ktchecker-owner-runtime-alias-")
        val roots = listOf("gradle", "jdk", "seed").associateWith { Files.createDirectory(work.resolve(it)) }
        Files.createDirectory(roots.getValue("seed").resolve("caches"))
        val selected = Files.createFile(roots.getValue("seed").resolve("caches/selected.jar"))
        val files = listOf(OwnerRuntimeFile("seed", "caches/selected.jar", "unit-only", 0, false))
        val directories = listOf(OwnerRuntimeDirectory("seed", "caches"))
        fun verify() = verifyOwnerRuntimeInventories(roots, files, directories)
        verify()
        for ((kind, name) in listOf("gradle" to "alias", "jdk" to "alias", "seed" to "caches/alias.lock")) {
            val alias = roots.getValue(kind).resolve(name)
            createOwnerFixtureSymbolicLink(alias, selected)
            assertEquals("owner_fixture_input_alias_unqualified", assertFailsWith<OwnerFixtureProblem> { verify() }.code)
            Files.delete(alias)
            verify()
        }
    }

    @Test fun runtimeFreshnessRejectsChangedExistingCachedClassBytes(): Unit {
        val work = temporaryDirectory("ktchecker-owner-runtime-bytes-")
        assumeOwnerFixtureAnchoredReads(work)
        val roots = listOf("gradle", "jdk", "seed").associateWith { Files.createDirectory(work.resolve(it)) }
        Files.createDirectory(roots.getValue("seed").resolve("caches"))
        val path = roots.getValue("seed").resolve("caches/Program.class")
        val original = ByteArray(8) { it.toByte() }
        Files.write(path, original)
        val files = listOf(OwnerRuntimeFile("seed", "caches/Program.class", Digests.sha256(original), original.size.toLong(), false))
        val directories = listOf(OwnerRuntimeDirectory("seed", "caches"))
        fun verify() {
            verifyOwnerRuntimeInventories(roots, files, directories)
            verifyOwnerRuntimeFileBytes(roots, files)
        }
        verify()
        Files.write(path, original.copyOf().apply { this[0] = 42 })
        assertEquals("owner_fixture_artifact_registry_mismatch", assertFailsWith<OwnerFixtureProblem> { verify() }.code)
        Files.write(path, original)
        verify()
        Files.delete(path)
        assertEquals("owner_fixture_seed_inventory_changed", assertFailsWith<OwnerFixtureProblem> { verify() }.code)
    }

    @Test fun runtimeFreshnessRejectsChangedExecutableFlags(): Unit {
        val work = temporaryDirectory("ktchecker-owner-runtime-permissions-")
        assumeOwnerFixtureAnchoredReads(work)
        assumeTrue(Files.getFileAttributeView(work, PosixFileAttributeView::class.java) != null,
            "POSIX executable-bit control unavailable; no permission-change execution coverage is claimed")
        Files.createDirectory(work.resolve("caches"))
        val path = work.resolve("caches/Program.class")
        val original = byteArrayOf(0)
        Files.write(path, original)
        val files = listOf(OwnerRuntimeFile("seed", "caches/Program.class", Digests.sha256(original), 1, false))
        fun verify() = verifyOwnerRuntimeFileBytes(mapOf("seed" to work), files)
        verify()
        val permissions = Files.getPosixFilePermissions(path)
        Files.setPosixFilePermissions(path, permissions + PosixFilePermission.OWNER_EXECUTE)
        assertTrue(Files.isExecutable(path))
        assertEquals("owner_fixture_runtime_permissions_changed", assertFailsWith<OwnerFixtureProblem> { verify() }.code)
        Files.setPosixFilePermissions(path, permissions)
        verify()
    }

    @Test fun boundedInputReadsRejectOversizeLeafParentAliasesAndNonNormalizedPaths(): Unit {
        val work = temporaryDirectory("ktchecker-owner-bounded-input-")
        assumeOwnerFixtureAnchoredReads(work)
        val path = work.resolve("input.bin")
        val bytes = byteArrayOf(0, 1, 2, 3)
        Files.write(path, bytes)
        assertContentEquals(bytes, readOwnerFixtureInput(path, 4, 4))
        assertEquals("owner_fixture_input_bound", assertFailsWith<OwnerFixtureProblem> {
            readOwnerFixtureInput(path, 3)
        }.code)
        assertEquals("owner_fixture_input_bound", assertFailsWith<OwnerFixtureProblem> {
            readOwnerFixtureInput(path, 8, 3)
        }.code)
        val leafAlias = work.resolve("alias.bin")
        createOwnerFixtureSymbolicLink(leafAlias, path)
        assertEquals("owner_fixture_input_unsafe", assertFailsWith<OwnerFixtureProblem> {
            readOwnerFixtureInput(leafAlias, 4)
        }.code)
        val parentAlias = work.resolve("alias-directory")
        createOwnerFixtureSymbolicLink(parentAlias, work)
        assertEquals("owner_fixture_input_unsafe", assertFailsWith<OwnerFixtureProblem> {
            readOwnerFixtureInput(parentAlias.resolve("input.bin"), 4)
        }.code)
        for (unsafe in listOf(path.parent.resolve(".").resolve(path.fileName), path.fileName, work)) {
            assertEquals("owner_fixture_input_unsafe", assertFailsWith<OwnerFixtureProblem> {
                readOwnerFixtureInput(unsafe, 4)
            }.code)
        }
        val empty = Files.createFile(work.resolve("empty.bin"))
        assertContentEquals(byteArrayOf(), readOwnerFixtureInput(empty, 1, 0))
    }

    @Test fun missingAnchoredReadCapabilityAbstainsWithoutFollowingOrTrustingInputs(): Unit {
        val work = temporaryDirectory("ktchecker-owner-input-capability-")
        val path = work.resolve("input.bin")
        val bytes = byteArrayOf(0, 1)
        Files.write(path, bytes)
        if (ownerFixtureAnchoredReadsSupported(work)) {
            assertContentEquals(bytes, readOwnerFixtureInput(path, 2, 2))
            assertEquals(Digests.sha256(bytes), hashFile(path, 2, 2))
        } else {
            assertEquals("owner_fixture_secure_io_unavailable", assertFailsWith<OwnerFixtureProblem> {
                readOwnerFixtureInput(path, 2, 2)
            }.code)
            assertEquals("model_secure_io_unavailable", assertFailsWith<ProjectModelException> {
                hashFile(path, 2, 2)
            }.code)
        }
    }

    @Test fun malformedOwnerManifestAndReleaseUtf8NeverUseReplacementDecoding(): Unit {
        for (bytes in listOf(byteArrayOf(0xff.toByte()), byteArrayOf(0xc0.toByte(), 0xaf.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()), byteArrayOf(0xe2.toByte(), 0x82.toByte()))) {
            assertEquals("owner_fixture_input_utf8", assertFailsWith<OwnerFixtureProblem> { ownerFixtureUtf8(bytes) }.code)
        }
        assertEquals("JAVA_VERSION=\"21\"\n", ownerFixtureUtf8("JAVA_VERSION=\"21\"\n".toByteArray()))
        assertEquals("\ufffd", ownerFixtureUtf8("\ufffd".toByteArray())) // An actually encoded character is not decoder substitution.
        val work = temporaryDirectory("ktchecker-owner-manifest-utf8-")
        if (ownerFixtureAnchoredReadsSupported(work)) {
            val path = work.resolve("owner-inputs.json")
            Files.write(path, byteArrayOf(0xff.toByte()))
            assertEquals("owner_fixture_input_utf8", assertFailsWith<OwnerFixtureProblem> {
                OwnerFixtureRuntimeRegistry.load(path)
            }.code)
        }
    }

    private fun sourceGraph(contract: OwnerFixtureContract): GradleProjectExport {
        val data = contract.value
        fun members(roots: List<String>) = data.files.filter { file -> roots.any { file.path.startsWith("$it/") } }
        val sets = data.sourceSets.map { ModelSourceSet(it.id, it.moduleId, it.name, it.dependsOn,
            it.sourceRoots, it.resourceRoots, members(it.sourceRoots), members(it.resourceRoots)) }
        return GradleProjectExport(gradleVersion = data.gradleVersion,
            modules = data.modules.map { ModelModule(it.id, it.directory, it.buildFile, emptyList()) }, sourceSets = sets,
            compilations = data.compilations.map { expected ->
                ModelCompilation(expected.id, expected.moduleId, expected.target, expected.platform, expected.name,
                    expected.defaultSourceSetId, expected.sourceSetIds, sets.filter { it.id in expected.sourceSetIds }.flatMap { it.sources }.sortedBy { it.path },
                    expected.compileTaskPath, expected.dependencyConfiguration, emptyList(), expected.projectProducerIds.map { producer ->
                        ModelProjectDependency(producer.substringBefore('|'), "apiElements", emptyMap(), producer)
                    }, null, emptyList(), null, null, associatedCompilationIds = expected.associatedCompilationIds)
            }, configurationInputs = data.files.filter { it.path in data.configurationPaths }, generatedInputs = emptyList(),
            buildscriptArtifacts = emptyList(), includedBuilds = emptyList(), issues = emptyList())
    }
}

package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Path
import kotlin.test.*

/** Pure output declaration/topology data. No filesystem observation, output creation,
 * compiler completion, generated artifact registry or successful derivation is exercised. */
class OwnerFixtureDerivedOutputTreeTest {
    @BeforeTest fun declaredPosixPathDialectOnly(): Unit {
        assumeTrue(java.io.File.pathSeparatorChar == ':' && java.io.File.separatorChar == '/')
    }
    private val outputRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().resolve("synthetic-derived-output-never-created")
    private val sourceRoot = outputRoot.resolveSibling("synthetic-derived-input-never-created")
    private val inputCore = Digests.sha256("synthetic input core")
    private val snapshot = Digests.sha256("synthetic snapshot")
    private val recipe = Digests.sha256("synthetic declared recipe, not a launch")
    private val absent = OwnerJvmTreePresence.Absent("synthetic-parent-anchor-not-an-observation", OwnerFixtureDerivedOutputTree.RELATIVE_PATH)
    private fun declare(inputCore: String = this.inputCore, snapshot: String = this.snapshot, recipe: String = this.recipe,
        attempt: String = "synthetic-attempt", output: Path = outputRoot, source: Path = sourceRoot,
        presence: OwnerJvmTreePresence = absent) = OwnerFixtureDerivedOutputTree.declare(inputCore, snapshot, recipe, attempt, output, source, presence)
    private fun file(path: String, size: Long = 1) = OwnerJvmTreeEntry(path, OwnerJvmTreeEntryKind.FILE, size, Digests.sha256("synthetic output bytes"))
    private fun directory(path: String) = OwnerJvmTreeEntry(path, OwnerJvmTreeEntryKind.DIRECTORY)
    private fun tree(entries: List<OwnerJvmTreeEntry>) = OwnerJvmTreePresence.Tree("synthetic-tree-anchor", entries)
    private fun reject(presence: OwnerJvmTreePresence) = assertFailsWith<OwnerJvmPlanProblem> { OwnerFixtureDerivedOutputTree.validatePresence(presence) }.code

    @Test fun fixedKotlinClassesSpecIsNeitherInputJarResourcesNorAbiIdentity(): Unit {
        val spec = OwnerFixtureDerivedOutputTree.spec()
        assertEquals(":lib||main", spec.compilationId); assertEquals(":lib:compileKotlin", spec.taskPath)
        assertEquals("kotlin-classes", spec.purpose); assertEquals("owner-isolated-output", spec.ownerRootRole)
        assertEquals("gradle-build/${Digests.sha256(":lib").removePrefix("sha256:")}/classes/kotlin/main", spec.relativePath)
        val intended = OwnerFixtureDerivedOutputTree.intended(outputRoot)
        assertEquals(spec, intended.spec)
        assertEquals(outputRoot.resolve(spec.relativePath).toString(), intended.intendedPath)
        assertTrue(Digests.valid(intended.specDigest)); assertNull(intended.compilerAttemptId)
        assertIs<OwnerJvmTreePresence.Unobserved>(intended.presence)
        assertFalse(intended.intendedPath.endsWith(".jar")); assertFalse(spec.relativePath.contains("resources"))
        assertFalse(spec.relativePath.contains("classes/java"))
        val otherRoot = OwnerFixtureDerivedOutputTree.intended(outputRoot.resolveSibling("another-synthetic-output"))
        assertEquals(intended.specDigest, otherRoot.specDigest)
        assertNotEquals(intended.intendedPath, otherRoot.intendedPath)
    }

    @Test fun unobservedAbsentEmptyDirectoryAndPresentEmptyFileHaveDistinctIdentities(): Unit {
        val unobserved = OwnerJvmTreePresence.Unobserved("No tree observation exists")
        val emptyDirectory = OwnerJvmTreePresence.EmptyDirectory("synthetic-tree-anchor")
        val emptyFile = tree(listOf(file("empty.class", 0).copy(digest = Digests.sha256(byteArrayOf()))))
        val nestedEmptyDirectory = tree(listOf(directory("empty")))
        val identities = listOf(unobserved, absent, emptyDirectory, emptyFile, nestedEmptyDirectory).map(OwnerFixtureDerivedOutputTree::presenceIdentity)
        assertEquals(5, identities.distinct().size)
        assertTrue(identities.all(Digests::valid)); assertTrue(identities.none { it == Digests.sha256(byteArrayOf()) })
        assertNotEquals(OwnerFixtureDerivedOutputTree.presenceIdentity(absent),
            OwnerFixtureDerivedOutputTree.presenceIdentity(absent.copy(parentAnchor = "changed-parent-anchor")))
        assertNotEquals(OwnerFixtureDerivedOutputTree.presenceIdentity(emptyDirectory),
            OwnerFixtureDerivedOutputTree.presenceIdentity(emptyDirectory.copy(rootAnchor = "changed-tree-anchor")))
        assertEquals("owner_jvm_output_tree_bound", reject(tree(emptyList()))) // Use the explicit empty-directory state.
    }

    @Test fun declarationRequiresFreshAbsenceAndNeverDeletesOrRebaselinesAPresentTree(): Unit {
        val declaration = declare()
        assertEquals("declaration_data_only", declaration.state)
        assertEquals(absent, declaration.initialPresence)
        assertEquals(inputCore, declaration.inputCoreIdentity)
        assertEquals(snapshot, declaration.snapshotId); assertEquals(recipe, declaration.launchRecipeIdentity)
        assertTrue(Digests.valid(declaration.declarationId))
        for (presence in listOf(OwnerJvmTreePresence.Unobserved("unknown"), OwnerJvmTreePresence.EmptyDirectory("preexisting"),
            tree(listOf(file("LibraryKt.class"))), absent.copy(relativeName = "other-output"))) {
            assertEquals("owner_jvm_output_fresh_absence_required", assertFailsWith<OwnerJvmPlanProblem> { declare(presence = presence) }.code)
        }
        assertEquals(absent, declaration.initialPresence) // Caller data remains data, not a post-run inventory.
    }

    @Test fun declarationIdentityBindsInputSnapshotRecipeAttemptRootAndAbsenceAnchorWithoutACycle(): Unit {
        val baseline = declare()
        val changes = listOf(declare(inputCore = Digests.sha256("other input core")), declare(snapshot = Digests.sha256("other snapshot")),
            declare(recipe = Digests.sha256("other launch semantics")), declare(attempt = "other-synthetic-attempt"),
            declare(output = outputRoot.resolveSibling("other-synthetic-output")), declare(presence = absent.copy(parentAnchor = "replacement-parent")))
        assertTrue(changes.all { it.declarationId != baseline.declarationId })
        assertEquals(changes.size, changes.map { it.declarationId }.distinct().size)
        assertEquals(baseline, declare())
        assertEquals(OwnerFixtureDerivedOutputTree.spec(), baseline.spec) // The producer/task is fixed, not report-selected.
        assertNotEquals(baseline.inputCoreIdentity, baseline.declarationId)
        assertEquals(inputCore, changes.last().inputCoreIdentity) // No future tree/declaration digest feeds back into input-core.
    }

    @Test fun rootOverlapUnsafePathsAndInvalidAttemptIdentitiesAreRejected(): Unit {
        for ((output, source) in listOf(outputRoot to outputRoot, outputRoot to outputRoot.resolve("inputs"),
            sourceRoot.resolve("outputs") to sourceRoot)) {
            assertEquals("owner_jvm_output_source_overlap", assertFailsWith<OwnerJvmPlanProblem> { declare(output = output, source = source) }.code)
        }
        for (output in listOf(Path.of("relative"), outputRoot.resolve(".."), Path.of("/"), Path.of("/bad\\root"))) {
            assertEquals("owner_jvm_path_unsafe", assertFailsWith<OwnerJvmPlanProblem> { declare(output = output) }.code)
        }
        for (attempt in listOf("", "a".repeat(129), "attempt\nforged")) {
            assertEquals("owner_jvm_output_attempt_invalid", assertFailsWith<OwnerJvmPlanProblem> { declare(attempt = attempt) }.code)
        }
        for (which in 0..2) {
            assertEquals("owner_jvm_output_identity_invalid", assertFailsWith<OwnerJvmPlanProblem> {
                declare(inputCore = if (which == 0) "unbound" else inputCore, snapshot = if (which == 1) "unbound" else snapshot,
                    recipe = if (which == 2) "unbound" else recipe)
            }.code)
        }
    }

    @Test fun canonicalTopologyRequiresUniqueOrderedEntriesAndEveryDirectoryParent(): Unit {
        val entries = listOf(directory("META-INF"), file("META-INF/module.kotlin_module"), directory("fixture"),
            file("fixture/LibraryKt.class"), directory("fixture/empty"))
        val valid = tree(entries)
        OwnerFixtureDerivedOutputTree.validatePresence(valid)
        assertTrue(Digests.valid(OwnerFixtureDerivedOutputTree.presenceIdentity(valid)))
        for (changed in listOf(entries.reversed(), entries + entries.last())) {
            assertEquals("owner_jvm_output_tree_noncanonical", reject(tree(changed)))
        }
        assertEquals("owner_jvm_output_tree_parent_missing", reject(tree(listOf(file("missing/Library.class")))))
        assertEquals("owner_jvm_output_tree_parent_missing", reject(tree(listOf(file("pkg"), file("pkg/Library.class")))))
        assertNotEquals(OwnerFixtureDerivedOutputTree.presenceIdentity(valid),
            OwnerFixtureDerivedOutputTree.presenceIdentity(valid.copy(entries = entries.map { if (it.kind == OwnerJvmTreeEntryKind.FILE) it.copy(digest = Digests.sha256("changed output bytes")) else it })))
    }

    @Test fun symlinksSpecialEntriesInvalidFileMetadataAndEscapingNamesNeverBecomeTreeData(): Unit {
        for (kind in listOf(OwnerJvmTreeEntryKind.SYMLINK, OwnerJvmTreeEntryKind.OTHER)) {
            assertEquals("owner_jvm_output_entry_unsafe", reject(tree(listOf(OwnerJvmTreeEntry("unsafe", kind)))))
        }
        for (entry in listOf(directory("bad").copy(size = 0), directory("bad").copy(digest = Digests.sha256("not directory bytes")))) {
            assertEquals("owner_jvm_output_directory_invalid", reject(tree(listOf(entry))))
        }
        for (entry in listOf(file("bad").copy(size = null), file("bad").copy(size = -1), file("bad").copy(digest = null),
            file("bad").copy(digest = "unknown"), file("bad").copy(size = OwnerFixtureDerivedOutputTree.MAX_FILE_BYTES + 1))) {
            assertEquals("owner_jvm_output_file_invalid", reject(tree(listOf(entry))))
        }
        for (name in listOf("", "/escape", "../escape", "a/../escape", "a/./b", "a//b", "a/", "C:/escape", "a\\b", "a\nb", "a\u0000b")) {
            assertEquals("owner_jvm_output_path_unsafe", reject(absent.copy(relativeName = name)))
            assertEquals("owner_jvm_output_path_unsafe", reject(tree(listOf(file(name)))))
        }
        for (anchor in listOf("", "a\nforged", "a".repeat(513))) {
            assertEquals("owner_jvm_output_anchor_invalid", reject(absent.copy(parentAnchor = anchor)))
            assertEquals("owner_jvm_output_anchor_invalid", reject(OwnerJvmTreePresence.EmptyDirectory(anchor)))
        }
        assertEquals("owner_jvm_output_observation_invalid", reject(OwnerJvmTreePresence.Unobserved("")))
    }

    @Test fun entryDepthFileAggregateAndMetadataBoundsAreCheckedBeforeIdentitySerialization(): Unit {
        val maximum = OwnerFixtureDerivedOutputTree.MAX_FILE_BYTES
        OwnerFixtureDerivedOutputTree.validatePresence(tree(listOf(file("a", maximum), file("b", maximum))))
        assertEquals("owner_jvm_output_tree_bound", reject(tree(listOf(file("a", maximum), file("b", maximum), file("c", 1)))))
        val tooMany = List(OwnerFixtureDerivedOutputTree.MAX_ENTRIES + 1) { file("f${it.toString().padStart(4, '0')}", 0) }
        assertEquals("owner_jvm_output_tree_bound", reject(tree(tooMany)))
        val deepest = (1..OwnerFixtureDerivedOutputTree.MAX_DEPTH).map { depth -> directory(List(depth) { "d" }.joinToString("/")) }
        OwnerFixtureDerivedOutputTree.validatePresence(tree(deepest))
        assertEquals("owner_jvm_output_path_unsafe", reject(tree(listOf(file(List(OwnerFixtureDerivedOutputTree.MAX_DEPTH + 1) { "d" }.joinToString("/"))))))
        assertEquals("owner_jvm_output_path_unsafe", reject(absent.copy(relativeName = "x".repeat(4_097))))
        val excessiveText = List(100) { file("f${it.toString().padStart(4, '0')}-" + "x".repeat(1_990), 0) }
        assertEquals("owner_jvm_output_tree_bound", reject(tree(excessiveText)))
    }

    @Test fun serializedDeclarationsRemainDataAndDoNotAcceptAnAttachedSuccessReceipt(): Unit {
        val declaration = declare()
        val bytes = ContractJson.encodeToString(declaration)
        val decoded = ContractJson.decodeFromString<OwnerJvmOutputDeclaration>(bytes)
        assertEquals(declaration, decoded)
        assertEquals("declaration_data_only", decoded.state)
        assertIs<OwnerJvmTreePresence.Absent>(decoded.initialPresence)
        assertFailsWith<SerializationException> {
            ContractJson.decodeFromString<OwnerJvmOutputDeclaration>(bytes.dropLast(1) + ",\"successfulExecution\":true,\"exitCode\":0}")
        }
        // There is deliberately no API here that accepts a ProcessOutcome/JSON/zero
        // exit and returns a derivation certificate or fills any model artifact nulls.
    }
}

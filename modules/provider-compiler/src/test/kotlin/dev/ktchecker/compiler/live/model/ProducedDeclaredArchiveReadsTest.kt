package dev.ktchecker.compiler.live.model

import dev.ktchecker.compiler.CompilerSnapshot
import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import kotlin.test.*

/** Production location gate + OPAQUE capabilities and synthetic archive bytes. No imported graph,
 * fake compiler pin, application, output-success token or execution is manufactured by these tests. */
class ProducedDeclaredArchiveReadsTest {
    @Test fun heldBytesWorkBeforeFinalPathExistsAndAfterOriginalInputsAreRemoved(): Unit = Fixture().use { f ->
        assertFalse(Files.exists(Path.of(f.frozen.materializedRoot)))
        f.removeOriginals()
        assertEquals(f.held, f.reads.read(f.preview, f.admission, f.archive))
        assertEquals(listOf("src/A.kt"), CompilerSnapshot.read(f.reads.read(f.preview, f.admission, f.archive)).map { it.relativePath })
        CompilerSnapshot.classpath(f.reads.read(f.preview, f.admission, f.archive).context.classpath)
        f.install()
        assertEquals(f.frozen, f.reads.read(f.preview, f.admission, f.archive))
        assertEquals(f.key, f.reads.archiveKey)
        assertEquals(f.original, f.reads.original)
    }

    @Test fun allThreeForeignIdentitiesRejectBeforeAnyClockReadOrVerifier(): Unit = Fixture().use { f ->
        val identities = listOf(Triple(Any(), f.admission, f.archive), Triple(f.preview, Any(), f.archive),
            Triple(f.preview, f.admission, Any()))
        identities.forEach { (preview, admission, archive) ->
            var checkpoints = 0
            expectModelLiveFailure("compiler_model_produced_archive_foreign_identity") {
                f.reads.read(preview, admission, archive) { checkpoints++ }
            }
            assertEquals(0, checkpoints)
        }
        assertEquals(0, f.readCalls); assertEquals(0, f.verifyCalls)
    }

    @Test fun equalButDifferentCapabilityObjectsCannotBorrowArchiveCustody(): Unit = Fixture().use { f ->
        assertEquals(f.archive, OpaqueIdentity("archive"))
        assertNotSame(f.archive, OpaqueIdentity("archive"))
        expectModelLiveFailure("compiler_model_produced_archive_foreign_identity") {
            f.reads.read(f.preview, f.admission, OpaqueIdentity("archive"))
        }
        assertEquals(0, f.readCalls); assertEquals(0, f.verifyCalls)
    }

    @Test fun finalToHeldRollbackIsDeniedEvenIfTheBytesWouldBeEqual(): Unit = Fixture().use { f ->
        f.install(); f.reads.read(f.preview, f.admission, f.archive)
        val before = f.verifyCalls
        f.current = f.held
        expectModelLiveFailure("compiler_model_produced_archive_read_rollback") { f.reads.read(f.preview, f.admission, f.archive) }
        assertEquals(before, f.verifyCalls)
    }

    @Test fun aFreshByteEqualReadRootIsNotAnEnrolledProjection(): Unit = Fixture().use { f ->
        f.current = f.project(f.root.resolve("inputs/byte-equal-unenrolled"))
        expectModelLiveFailure("compiler_model_produced_archive_read_mismatch") { f.reads.read(f.preview, f.admission, f.archive) }
        assertEquals(0, f.verifyCalls)
    }

    @Test fun callbackCannotChangeTheReadProjectionMidVerification(): Unit = Fixture().use { f ->
        val reads = f.newReads(verify = { f.install() })
        expectModelLiveFailure("compiler_model_produced_archive_read_changed") { reads.read(f.preview, f.admission, f.archive) }
    }

    @Test fun declaredCollectionsAreDetachedAndEveryKnownOrdinalIsRetained(): Unit = Fixture().use { f ->
        val rules = f.original.requiredRuleIds.toMutableList()
        val arguments = f.original.context.compilerArguments.toMutableList()
        val original = f.original.copy(requiredRuleIds = rules, context = f.original.context.copy(compilerArguments = arguments))
        val frozen = f.frozen.copy(requiredRuleIds = rules, context = f.frozen.context.copy(compilerArguments = arguments))
        val held = f.held.copy(requiredRuleIds = rules, context = f.held.context.copy(compilerArguments = arguments))
        var current = held
        val reads = ProducedDeclaredArchiveReads(f.preview, f.admission, f.archive, f.key, original, frozen, held, { current }, {})
        rules += "smuggled.rule"; arguments.reverse()
        assertEquals(f.original, reads.original)
        assertFailsWith<UnsupportedOperationException> { (reads.original.requiredRuleIds as MutableList<String>).clear() }
        expectModelLiveFailure("compiler_model_produced_archive_read_mismatch") { reads.read(f.preview, f.admission, f.archive) }
        current = f.held
        assertEquals(f.held, reads.read(f.preview, f.admission, f.archive))
    }

    @Test fun admissionRejectsFrozenSnapshotRawArgumentsPoliciesOrRuleVectorMutation(): Unit = Fixture().use { f ->
        val variants = listOf(f.frozen.copy(requiredRuleIds = f.frozen.requiredRuleIds.dropLast(1)),
            f.frozen.copy(context = f.frozen.context.copy(compilerArguments = listOf("-nowarn"))),
            f.frozen.copy(context = f.frozen.context.copy(policies = mapOf("invented" to "value"))),
            f.frozen.copy(snapshot = f.frozen.snapshot.copy(files = emptyList())),
            f.frozen.copy(context = f.frozen.context.copy(compilationFiles = emptyList())),
            f.frozen.copy(context = f.frozen.context.copy(fullProject = true)))
        variants.forEach { changed -> expectModelLiveFailure("compiler_model_produced_archive_layout_mismatch") { f.newReads(frozen = changed) } }
    }

    @Test fun sameDigestArtifactAtWrongOrdinalOrCoordinateCannotEnterTheArchive(): Unit = Fixture().use { f ->
        val artifact = f.frozen.context.classpath.single()
        val variants = listOf(artifact.copy(path = Path.of(artifact.path).resolveSibling("artifact-1.jar").toString()),
            artifact.copy(coordinate = "not-the-original-coordinate"), artifact.copy(digest = Digests.sha256("other")))
        variants.forEach { changed -> expectModelLiveFailure("compiler_model_produced_archive_classpath_mismatch") {
            f.newReads(frozen = f.frozen.copy(context = f.frozen.context.copy(classpath = listOf(changed))))
        } }
        expectModelLiveFailure("compiler_model_produced_archive_layout_mismatch") {
            f.newReads(frozen = f.frozen.copy(context = f.frozen.context.copy(classpath = emptyList())))
        }
    }

    @Test fun exactDeclaredKeyAndSharedHeldFinalParentAreRequired(): Unit = Fixture().use { f ->
        expectModelLiveFailure("compiler_model_produced_archive_layout_mismatch") {
            f.newReads(frozen = f.project(f.root.resolve("inputs/not-the-declared-key")))
        }
        expectModelLiveFailure("compiler_model_produced_archive_layout_mismatch") {
            f.newReads(held = f.project(f.root.resolve("other-parent/held")))
        }
    }

    @Test fun aWorkKeyRequiresItsExactKernelRootNotMerelyAValidLayout(): Unit = Fixture().use { f ->
        val workKey = Digests.sha256("distinct declared execution certificate and P")
        val effective = f.project(f.root.resolve("inputs").resolve(workKey.removePrefix("sha256:")))
        f.reads.requireKernelArchive(effective, workKey)
        expectModelLiveFailure("compiler_model_produced_work_identity_invalid") { f.reads.requireKernelArchive(f.frozen, f.key) }
        expectModelLiveFailure("compiler_model_produced_work_archive_mismatch") {
            f.reads.requireKernelArchive(f.project(f.root.resolve("inputs/another-regular-archive")), workKey)
        }
        expectModelLiveFailure("compiler_model_produced_work_archive_mismatch") {
            f.reads.requireKernelArchive(effective.copy(materializedRoot = f.root.resolve("foreign/inputs/${workKey.removePrefix("sha256:")}/sources").toString()), workKey)
        }
        assertEquals(0, f.readCalls); assertEquals(0, f.verifyCalls)
    }

    @Test fun retainedSourceAndDependencyMutationRemainVerificationFailures(): Unit = Fixture().use { f ->
        f.removeOriginals()
        val source = Path.of(f.held.materializedRoot).resolve("src/A.kt")
        Files.writeString(source, "fun changed() = 2\n")
        assertFails { f.reads.read(f.preview, f.admission, f.archive) }
        Files.writeString(source, f.source)
        Files.writeString(Path.of(f.held.context.classpath.single().path), "changed")
        assertFails { f.reads.read(f.preview, f.admission, f.archive) }
    }

    private data class OpaqueIdentity(val label: String)
    private class Fixture : AutoCloseable {
        val root: Path = Files.createTempDirectory("ktc-declared-read-unit-").toRealPath()
        val preview = OpaqueIdentity("preview")
        val admission = OpaqueIdentity("admission")
        val archive = OpaqueIdentity("archive")
        val key = Digests.sha256("synthetic declared archive identity, never an execution grant")
        val source = "fun a() = 1\n"
        private val dependency = "opaque dependency bytes: not a compiler or executable archive"
        val original: AnalysisInput
        val frozen: AnalysisInput
        val held: AnalysisInput
        var current: AnalysisInput
        var readCalls = 0
        var verifyCalls = 0
        val reads: ProducedDeclaredArchiveReads
        init {
            val sources = Files.createDirectories(root.resolve("original/src"))
            Files.writeString(sources.resolve("A.kt"), source)
            val dependencyPath = root.resolve("original-dependency.jar")
            Files.writeString(dependencyPath, dependency)
            original = AnalysisInput(SnapshotManifest(Digests.sha256("synthetic D"), "workspace", "worktree",
                listOf(SnapshotFile("src/A.kt", Digests.sha256(source), source.toByteArray().size.toLong())), "owner"),
                root.resolve("original").toString(), AnalysisContext(scope = RuleContext("", "main", ":", "main"),
                    compilerVersion = "2.4.21", classpath = listOf(ArtifactIdentity("known:dep", Digests.sha256(dependency), dependencyPath.toString())),
                    compilerArguments = listOf("-classpath", dependencyPath.toString(), sources.resolve("A.kt").toString()),
                    fullProject = false, compilationFiles = listOf("src/A.kt")), listOf("catalog.one", "catalog.two"))
            frozen = project(root.resolve("inputs").resolve(key.removePrefix("sha256:")))
            held = project(root.resolve("inputs/.ingest-owned-test"))
            Files.createDirectories(Path.of(held.materializedRoot).resolve("src"))
            Files.writeString(Path.of(held.materializedRoot).resolve("src/A.kt"), source)
            Files.createDirectories(Path.of(held.context.classpath.single().path).parent)
            Files.writeString(Path.of(held.context.classpath.single().path), dependency)
            current = held
            reads = newReads()
        }
        fun project(archiveRoot: Path) = original.copy(materializedRoot = archiveRoot.resolve("sources").toString(),
            context = original.context.copy(classpath = original.context.classpath.mapIndexed { index, artifact ->
                artifact.copy(path = archiveRoot.resolve("classpath/artifact-$index.jar").toString())
            }))
        fun newReads(frozen: AnalysisInput = this.frozen, held: AnalysisInput = this.held, verify: () -> Unit = ::verify) =
            ProducedDeclaredArchiveReads(preview, admission, archive, key, original, frozen, held, { readCalls++; current }, verify)
        private fun verify() {
            verifyCalls++
            CompilerSnapshot.read(current)
            CompilerSnapshot.classpath(current.context.classpath)
        }
        fun install() {
            Files.move(Path.of(held.materializedRoot).parent, Path.of(frozen.materializedRoot).parent, ATOMIC_MOVE)
            current = frozen
        }
        fun removeOriginals() {
            check(Path.of(original.materializedRoot).toFile().deleteRecursively())
            Files.delete(Path.of(original.context.classpath.single().path))
        }
        override fun close() { root.toFile().deleteRecursively() }
    }
}

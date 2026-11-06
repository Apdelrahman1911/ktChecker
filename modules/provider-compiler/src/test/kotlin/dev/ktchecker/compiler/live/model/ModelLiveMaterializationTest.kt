package dev.ktchecker.compiler.live.model

import dev.ktchecker.compiler.live.*
import dev.ktchecker.contracts.*
import dev.ktchecker.workerprotocol.CompilerModelWire as ModelWire
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import kotlin.test.*

/** Real bounded file-copy/pin helpers, SYNTHETIC inputs. No worker/compiler/native call is made. */
class ModelLiveMaterializationTest {
    @Test fun noJobDirectoryExistsBeforeAdmissionAndCopiesPreserveTheWholePkcERelation(): Unit = ModelLiveHostFixture().use { f ->
        val archive = f.archive()
        val permit = f.grant(archive)
        expectModelLiveFailure("compiler_live_attempt_no_longer_active") {
            ModelCompilerJobMaterialization.create(f.request, permit, 1) {}
        }
        assertTrue(liveNames(f.generationRoot.resolve("jobs"), 0).isEmpty())
        permit.consume()
        for (sequence in listOf(0, f.launch.limits().maxJobs() + 1)) {
            expectModelLiveFailure("compiler_model_live_job_limit") {
                ModelCompilerJobMaterialization.create(f.request, permit, sequence) {}
            }
        }
        expectModelLiveFailure("compiler_model_live_application_binding_mismatch") {
            ModelCompilerJobMaterialization.create(f.requestFor(), permit, 1) {}
        }
        assertTrue(liveNames(f.generationRoot.resolve("jobs"), 0).isEmpty())
        val materialized = ModelCompilerJobMaterialization.create(f.request, permit, 1) {}
        permit.register(materialized) {}
        permit.verifyRegistered(materialized) {}
        assertEquals(1, archive.registrations)
        assertEquals(1, archive.materializationVerifications)
        assertSame(permit.original, materialized.original)
        assertSame(permit.archived, materialized.archived)
        assertSame(permit.input, materialized.input)
        assertEquals(4, setOf(materialized.original.analysisKey(), materialized.archived.analysisKey(),
            materialized.input.analysisKey(), materialized.effective.analysisKey()).size)
        assertEquals(f.original.snapshot, materialized.effective.snapshot)
        assertEquals(f.original.context.scope, materialized.effective.context.scope)
        assertEquals(f.original.context.policies, materialized.effective.context.policies)
        assertEquals(f.original.context.compilationFiles, materialized.effective.context.compilationFiles)
        assertEquals(f.original.context.compilerArguments, materialized.effective.context.compilerArguments)
        assertEquals(f.original.context.toolchainDigest, materialized.effective.context.toolchainDigest)
        assertEquals(f.prepared.compilerRuleIds, materialized.effective.requiredRuleIds)
        assertEquals(f.original.changedPaths, materialized.effective.changedPaths)
        assertNull(materialized.effective.execution)
        assertEquals(f.original.snapshot.files.map { it.path }, materialized.snapshotMembers.map { it.logicalPath() })
        val beforeRoot = Path.of(archive.child.materializedRoot)
        val copiedRoot = Path.of(materialized.sourceRoot.path())
        f.original.snapshot.files.forEach { member ->
            assertContentEquals(Files.readAllBytes(beforeRoot.resolve(member.path)), Files.readAllBytes(copiedRoot.resolve(member.path)))
            assertNotEquals(modelTestFileKey(beforeRoot.resolve(member.path)), modelTestFileKey(copiedRoot.resolve(member.path)))
        }
        assertEquals(listOf("src/main/kotlin/B.kt", "src/main/kotlin/A.kt"), materialized.compilationPaths)
        val body = materialized.wireRequest
        assertEquals(f.prepared.originalAnalysisKey, body.ownerInputKey())
        assertEquals(permit.archivedInputKey, body.archivedInputKey())
        assertEquals(f.prepared.bindingIdentity.digest, body.modelBindingDigest())
        assertEquals(permit.providerInputKey, body.original().analysisKey())
        assertEquals(materialized.effective.analysisKey(), body.effective().analysisKey())
        assertEquals(f.original.snapshot.snapshotId, body.original().snapshotDigest())
        assertEquals(ModelWire.membersDigest(body.files()), body.original().memberDigest())
        assertEquals(f.original.context.modelDigest, body.original().modelDigest())
        assertEquals(f.original.context.toolchainDigest, body.effective().toolchainDigest())
        assertEquals(body, ModelWire.decodeRequest(ModelWire.encodeRequest(body)))
        materialized.verifyInputs {}
        materialized.verifyOutput(false) {}
        assertEquals(0, f.session.submissions)
    }

    @Test fun orderedDuplicateDependenciesAreSeparateCopiesAndOnlySelectedSourcesEnterArgv(): Unit = ModelLiveHostFixture().use { f ->
        val archive = f.archive()
        val permit = f.grant(archive)
        permit.consume()
        val materialized = ModelCompilerJobMaterialization.create(f.request, permit, 1) {}
        val dependencies = materialized.dependencyMembers
        assertEquals(2, dependencies.size)
        assertEquals(dependencies[0].coordinate(), dependencies[1].coordinate())
        assertEquals(dependencies[0].digest(), dependencies[1].digest())
        assertNotEquals(dependencies[0].fileKey(), dependencies[1].fileKey())
        assertTrue(dependencies.none { copy -> permit.capture.dependencies.any { it.fileKey == copy.fileKey() } })
        assertEquals(listOf("artifact-0.jar", "artifact-1.jar"), materialized.effective.context.classpath.map { Path.of(it.path).fileName.toString() })
        val argv = ModelWire.arguments(f.launch, f.request.generation, materialized.wireRequest)
        val classpath = argv[argv.indexOf("-classpath") + 1]
        assertEquals(materialized.effective.context.classpath.joinToString(java.io.File.pathSeparator) { it.path }, classpath)
        assertTrue(f.installation.compilerClasspath.none { it.path in classpath })
        val sources = materialized.compilationPaths.map { Path.of(materialized.sourceRoot.path()).resolve(it).toString() }
        assertTrue(argv.indexOf(sources[0]) >= 0 && argv.indexOf(sources[0]) < argv.indexOf(sources[1]))
        assertFalse(argv.any { it.endsWith("build.gradle.kts") || it.endsWith("state.txt") })
        assertEquals(0, f.session.submissions)
    }

    @Test fun emptyModelClasspathDoesNotInventOriginalDirectoryOrInjectStdlib(): Unit = ModelLiveHostFixture(transformGraph = { graph ->
        graph.copy(compilations = graph.compilations.map { it.copy(classpath = emptyList()) })
    }).use { f ->
        val archive = f.archive()
        val archiveClasspath = Path.of(archive.child.materializedRoot).parent.resolve("classpath")
        assertFalse(Files.exists(archiveClasspath))
        val permit = f.grant(archive)
        assertNull(permit.capture.classpathRoot)
        permit.consume()
        val materialized = ModelCompilerJobMaterialization.create(f.request, permit, 1) {}
        assertNull(materialized.originalClasspathRoot)
        assertTrue(materialized.dependencyMembers.isEmpty())
        assertTrue(materialized.effective.context.classpath.isEmpty())
        assertFalse(Files.exists(archiveClasspath))
        assertTrue(liveNames(Path.of(materialized.classpathRoot.path()), 0).isEmpty())
        val argv = ModelWire.arguments(f.launch, f.request.generation, materialized.wireRequest)
        assertEquals(materialized.classpathRoot.path(), argv[argv.indexOf("-classpath") + 1])
        assertFalse(argv.any { it.isEmpty() })
        assertEquals(0, f.session.submissions)
    }

    @Test fun effectiveModelRelationRejectsEveryChangeOutsideCopiedLocations(): Unit = ModelLiveHostFixture().use { f ->
        val permit = f.grant(f.archive())
        permit.consume()
        val materialized = ModelCompilerJobMaterialization.create(f.request, permit, 1) {}
        val effective = materialized.effective
        val alternatives = listOf(effective.copy(requiredRuleIds = f.original.requiredRuleIds), effective.copy(changedPaths = emptyList()),
            effective.copy(snapshot = effective.snapshot.copy(declaredRoots = listOf("src"))),
            effective.copy(context = effective.context.copy(modelDigest = Digests.sha256("foreign-model"))),
            effective.copy(context = effective.context.copy(toolchainDigest = f.installation.digest)),
            effective.copy(context = effective.context.copy(classpath = effective.context.classpath.reversed())),
            effective.copy(context = effective.context.copy(compilationFiles = effective.context.compilationFiles!!.reversed())),
            effective.copy(context = effective.context.copy(compilerArguments = effective.context.compilerArguments + "-nowarn")),
            effective.copy(outputRoot = f.root.resolve("foreign-output").toString()), effective.copy(execution = f.cold.prepared.compilerProfile))
        alternatives.forEach { changed -> expectModelLiveFailure("compiler_model_live_effective_mismatch") {
            requireModelEffectiveRelation(permit.input, changed, Path.of(materialized.sourceRoot.path()),
                Path.of(materialized.classpathRoot.path()), Path.of(materialized.outputRoot.path()))
        } }
    }

    @Test fun sourceResourceAndDependencySameLengthDriftInvalidateOriginalReceipts(): Unit = ModelLiveHostFixture().use { f ->
        val archive = f.archive()
        val permit = f.grant(archive)
        val paths = listOf(Path.of(archive.child.materializedRoot).resolve("src/main/kotlin/A.kt"),
            Path.of(archive.child.materializedRoot).resolve("src/main/resources/state.txt"), Path.of(archive.child.context.classpath.first().path))
        paths.forEach { path ->
            val before = Files.readAllBytes(path)
            try {
                Files.write(path, before.map { (it.toInt() xor 1).toByte() }.toByteArray())
                assertTrue(permit.accepts(archive.child)) // Identity lookup does not attest bytes.
                assertFailsWith<CompilerApplicationProblem> { permit.capture.verify {} }
            } finally { Files.write(path, before) }
            permit.capture.verify {}
        }
        assertEquals(0, f.session.submissions)
    }

    @Test fun extraMissingMembersAndEqualByteRootReplacementFailClosed(): Unit = ModelLiveHostFixture().use { f ->
        val archive = f.archive()
        val permit = f.grant(archive)
        val root = Path.of(archive.child.materializedRoot)
        val extra = root.resolve("unregistered-empty-directory")
        Files.createDirectory(extra)
        assertFailsWith<CompilerApplicationProblem> { permit.capture.verify {} }
        Files.delete(extra)
        val member = root.resolve("src/main/resources/state.txt")
        val saved = f.root.resolve("saved-resource")
        Files.move(member, saved)
        assertFailsWith<CompilerApplicationProblem> { permit.capture.verify {} }
        Files.move(saved, member)
        permit.capture.verify {}
        val oldRoot = root.resolveSibling("old-sources")
        Files.move(root, oldRoot)
        Files.createDirectory(root)
        f.original.snapshot.files.forEach { file ->
            val path = root.resolve(file.path)
            Files.createDirectories(path.parent); Files.copy(oldRoot.resolve(file.path), path)
        }
        expectModelLiveFailure("compiler_live_path_drift") { permit.capture.verify {} }
    }

    @Test fun originalAndCopiedClasspathDomainsCannotGainOrReplaceAnOrdinal(): Unit = ModelLiveHostFixture().use { f ->
        val archive = f.archive()
        val permit = f.grant(archive)
        val extra = Path.of(archive.child.context.classpath.first().path).parent.resolve("artifact-2.jar")
        Files.writeString(extra, "unregistered")
        expectModelLiveFailure("compiler_model_live_archive_classpath_drift") { permit.capture.verify {} }
        Files.delete(extra)
        permit.consume()
        val materialized = ModelCompilerJobMaterialization.create(f.request, permit, 1) {}
        val copied = Path.of(materialized.effective.context.classpath.first().path)
        val saved = f.root.resolve("saved-copied-dependency.jar")
        Files.move(copied, saved); Files.copy(saved, copied)
        assertFailsWith<CompilerApplicationProblem> { materialized.verifyInputs {} }
    }

    @Test fun wholeUncompiledBinaryBytesStayOpaqueAndOnlySelectedTextIsDecoded(): Unit = ModelLiveHostFixture().use { f ->
        val archive = f.archive()
        // Direct capture control ONLY: this altered DTO is not a live importer binding or permit.
        val bytes = byteArrayOf(0xff.toByte(), 0x00, 0x0d, 0xfe.toByte())
        val relative = "src/main/resources/opaque.bin"
        Files.write(Path.of(archive.child.materializedRoot).resolve(relative), bytes)
        val input = archive.child.copy(snapshot = archive.child.snapshot.copy(snapshotId = Digests.sha256("synthetic-binary-manifest"),
            files = archive.child.snapshot.files + SnapshotFile(relative, Digests.sha256(bytes), bytes.size.toLong())))
        val capture = ModelArchiveCapture.capture(input) {}
        assertEquals(input.snapshot.files.size, capture.files.size)
        assertEquals(input.context.compilationFiles!!.toSet(), capture.sourceTexts.keys)
        assertFalse(relative in capture.sourceTexts)
        assertContentEquals(bytes, Files.readAllBytes(capture.files.single { it.path.endsWith(relative) }.path))
        capture.verify {}
    }

    @Test fun selectedMalformedUtf8BomNulAndLoneCrAreRejectedWithoutNormalization(): Unit = ModelLiveHostFixture().use { f ->
        val archive = f.archive()
        val relative = "src/main/kotlin/A.kt"
        val path = Path.of(archive.child.materializedRoot).resolve(relative)
        val values = listOf(byteArrayOf(0xc3.toByte()) to "compiler_model_live_source_not_utf8",
            "\uFEFFfun a() = 1\n".toByteArray() to "compiler_model_live_source_encoding_unsupported",
            "fun a() = 1\u0000\n".toByteArray() to "compiler_model_live_source_encoding_unsupported",
            "fun a() = 1\rfun b() = 2".toByteArray() to "compiler_model_live_bare_cr_unsupported",
            "fun a() = 1\r".toByteArray() to "compiler_model_live_bare_cr_unsupported")
        values.forEach { (bytes, code) ->
            Files.write(path, bytes)
            val input = archive.child.copy(snapshot = archive.child.snapshot.copy(snapshotId = Digests.sha256("synthetic-selected-bytes:$code"),
                files = archive.child.snapshot.files.map { if (it.path == relative) it.copy(digest = Digests.sha256(bytes), size = bytes.size.toLong()) else it }))
            expectModelLiveFailure(code) { ModelArchiveCapture.capture(input) {} }
            assertContentEquals(bytes, Files.readAllBytes(path))
        }
    }

    @Test fun selectedEmptyAndCrlfSupplementaryUtf16SourcesArePreservedExactly(): Unit = ModelLiveHostFixture().use { f ->
        val archive = f.archive()
        val relative = "src/main/kotlin/A.kt"
        val path = Path.of(archive.child.materializedRoot).resolve(relative)
        for (text in listOf("", "val emoji = \"😀\"\r\nfun a() = 1\r\n")) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            Files.write(path, bytes)
            val input = archive.child.copy(snapshot = archive.child.snapshot.copy(snapshotId = Digests.sha256("synthetic-selected:$text"),
                files = archive.child.snapshot.files.map { if (it.path == relative) it.copy(digest = Digests.sha256(bytes), size = bytes.size.toLong()) else it }))
            val capture = ModelArchiveCapture.capture(input) {}
            assertEquals(text, capture.sourceTexts.getValue(relative))
            assertContentEquals(bytes, Files.readAllBytes(path))
        }
    }

    @Test fun dependencyMinimumAndMaximumSizesAreRejectedBeforeUnboundedHashing(): Unit = ModelLiveHostFixture().use { f ->
        val archive = f.archive()
        val dependency = Path.of(archive.child.context.classpath.first().path)
        Files.write(dependency, byteArrayOf())
        expectModelLiveFailure("compiler_model_live_dependency_limit") { ModelArchiveCapture.capture(archive.child) {} }
        Files.newByteChannel(dependency, WRITE).use { file ->
            file.position(ModelLiveLimits.DEPENDENCY_BYTES)
            file.write(ByteBuffer.wrap(byteArrayOf(1))) // Sparse over-limit fixture, not a huge resident allocation.
        }
        expectModelLiveFailure("compiler_model_live_dependency_limit") { ModelArchiveCapture.capture(archive.child) {} }
    }

    @Test fun outputSealingDetectsSameLengthDriftUnreviewedProductsAndReplay(): Unit = ModelLiveHostFixture().use { f ->
        val permit = f.grant(f.archive())
        permit.consume()
        val materialized = ModelCompilerJobMaterialization.create(f.request, permit, 1) {}
        val output = Path.of(materialized.outputRoot.path())
        Files.write(output.resolve("AKt.class"), byteArrayOf(1, 2, 3))
        assertFailsWith<CompilerApplicationProblem> { materialized.verifyOutput(false) {} }
        materialized.sealOutput {}
        assertTrue(materialized.outputSealed())
        assertEquals(3L, materialized.outputBytes())
        assertEquals(1, materialized.outputEntries())
        materialized.verifyOutput(true) {}
        Files.write(output.resolve("AKt.class"), byteArrayOf(1, 2, 4))
        expectModelLiveFailure("compiler_model_live_output_drift") { materialized.verifyOutput(true) {} }
        Files.write(output.resolve("AKt.class"), byteArrayOf(1, 2, 3))
        expectModelLiveFailure("compiler_model_live_result_replayed") { materialized.sealOutput {} }
        Files.writeString(output.resolve("unreviewed.txt"), "not a compiler product")
        expectModelLiveFailure("compiler_live_output_member_unsupported") { materialized.verifyOutput(true) {} }
        assertEquals(0, f.session.submissions)
    }
}

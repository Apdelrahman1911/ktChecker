package dev.ktchecker.compiler.live.model

import dev.ktchecker.compiler.CompilerSnapshot
import dev.ktchecker.compiler.live.liveNames
import dev.ktchecker.contracts.*
import dev.ktchecker.reviewcontroller.OwnerCatalogInventory
import dev.ktchecker.reviewcontroller.PlanActivation
import dev.ktchecker.reviewcontroller.PlanRuleActivation
import dev.ktchecker.workerprotocol.CompilerModelWire as ModelWire
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Real bounded selection/archive/copy helpers, SYNTHETIC importer authority and baseline hints.
 * No Gradle, compiler, worker submission or incremental cache executes. Equal logical work is
 * deliberately NOT equal request authority, a successful compiler outcome or a reuse proof.
 */
class ModelLiveIncrementalWorkTest {
    @Test fun fullAndIncrementalHintsKeepAll1663ParentRowsAndTheWholeCompilerBatch(): Unit = ModelLiveHostFixture().use { f ->
        val inventory = currentCatalog()
        val compilerRules = inventory.ruleIds.filter { it in f.provider.supportedRuleIds }
        assertEquals(400, compilerRules.size, "The compiler child is not the full 1663-row owner obligation")
        assertTrue(compilerRules.size <= ModelLiveLimits.COMPILER_RULES)
        assertTrue(inventory.ruleIds.size <= ModelLiveLimits.PARENT_RULES)
        val activation = PlanActivation(f.modelFixture.configuration, inventory.ruleIds.map {
            PlanRuleActivation(it, true, "synthetic-incremental-policy", "Parent-vector preservation only; no execution credit")
        })
        val activationText = Digests.canonical(ContractJson.encodeToJsonElement(activation))
        val full = f.original.copy(requiredRuleIds = inventory.ruleIds, changedPaths = emptyList(), baselineSnapshotId = null,
            context = f.original.context.copy(policies = f.original.context.policies + mapOf(
                "ktchecker.catalog-inventory" to inventory.catalogDigest, "ktchecker.activation-plan" to activationText)))
        val incremental = full.copy(changedPaths = listOf("src/main/kotlin/A.kt"),
            baselineSnapshotId = Digests.sha256("synthetic-baseline-hint-not-a-reuse-grant"))
        val fullPrepared = f.cold.binding.bindPreparedLiveInput(full, compilerRules, f.provider, f.originalDeadline)
        val incrementalPrepared = f.cold.binding.bindPreparedLiveInput(incremental, compilerRules, f.provider, f.originalDeadline)
        val fullArchive = f.archive(fullPrepared)
        val incrementalArchive = f.archive(incrementalPrepared)
        val fullPermit = f.grant(fullArchive, fullPrepared)
        val incrementalPermit = f.grant(incrementalArchive, incrementalPrepared)
        try {
            val selected = listOf("src/main/kotlin/B.kt", "src/main/kotlin/A.kt")
            assertEquals(selected, fullPrepared.executableCompilationFiles)
            assertEquals(selected, incrementalPrepared.executableCompilationFiles)
            assertEquals(fullPrepared.normalizedCompilerOptions, incrementalPrepared.normalizedCompilerOptions)
            assertSame(fullPrepared.binding, incrementalPrepared.binding)
            assertNotEquals(fullPrepared.originalAnalysisKey, incrementalPrepared.originalAnalysisKey)
            assertEquals(fullPermit.capture.sourceTexts, incrementalPermit.capture.sourceTexts)
            assertEquals(selected, incrementalPermit.capture.sourceTexts.keys.toList())
            assertEquals(CompilerSnapshot.read(fullArchive.child), CompilerSnapshot.read(incrementalArchive.child))
            assertEquals(selected, CompilerSnapshot.read(incrementalArchive.child).map { it.relativePath })
            assertTrue(fullPermit.accepts(fullArchive.child))
            assertTrue(incrementalPermit.accepts(incrementalArchive.child))
            // Keep exactly the same archive paths/bytes: metadata alone prevents borrowing a permit.
            assertFalse(fullPermit.accepts(fullArchive.child.copy(changedPaths = incremental.changedPaths,
                baselineSnapshotId = incremental.baselineSnapshotId)))
            assertFalse(incrementalPermit.accepts(incrementalArchive.child.copy(changedPaths = emptyList(), baselineSnapshotId = null)))

            val fullWork = capturedWork(fullPermit.capture, fullPrepared.executableCompilationFiles,
                fullPrepared.normalizedCompilerOptions)
            assertEquals(fullWork, capturedWork(incrementalPermit.capture, incrementalPrepared.executableCompilationFiles,
                incrementalPrepared.normalizedCompilerOptions))
            incrementalPermit.consume()
            val materialized = ModelCompilerJobMaterialization.create(f.request, incrementalPermit, 1) {}
            val request = materialized.wireRequest
            assertEquals(fullWork, ModelWire.workDigest(request.files(), request.compilationPaths(),
                request.dependencies(), request.originalOptions()))
            assertEquals(incrementalPrepared.originalAnalysisKey, request.ownerInputKey())
            assertEquals(incrementalPermit.archivedInputKey, request.archivedInputKey())
            assertEquals(incrementalPermit.providerInputKey, request.original().analysisKey())
            assertEquals(materialized.effective.analysisKey(), request.effective().analysisKey())

            for (input in listOf(fullPrepared.original, fullPermit.archived, incrementalPrepared.original, incrementalPermit.archived)) {
                assertEquals(inventory.ruleIds, input.requiredRuleIds)
            }
            for (input in listOf(fullPermit.input, incrementalPermit.input, materialized.effective)) {
                assertEquals(compilerRules, input.requiredRuleIds)
            }
            for (input in listOf(fullPrepared.original, fullPermit.archived, fullPermit.input,
                    incrementalPrepared.original, incrementalPermit.archived, incrementalPermit.input, materialized.effective)) {
                assertEquals(full.snapshot, input.snapshot)
                assertEquals(full.context.policies, input.context.policies)
                assertEquals(full.context.scope, input.context.scope)
                assertEquals(full.context.modelDigest, input.context.modelDigest)
                assertEquals(full.context.compilationFiles, input.context.compilationFiles)
                assertEquals(1663, ContractJson.parseToJsonElement(input.context.policies.getValue("ktchecker.activation-plan"))
                    .jsonObject.getValue("decisions").jsonArray.size)
                assertNull(input.execution)
            }
            for (input in listOf(incrementalPrepared.original, incrementalPermit.archived, incrementalPermit.input, materialized.effective)) {
                assertEquals(incremental.changedPaths, input.changedPaths)
                assertEquals(incremental.baselineSnapshotId, input.baselineSnapshotId)
            }
            for (input in listOf(fullPrepared.original, fullPermit.archived, fullPermit.input)) {
                assertTrue(input.changedPaths.isEmpty())
                assertNull(input.baselineSnapshotId)
            }
            assertEquals(full.snapshot.files.map { it.path }, materialized.snapshotMembers.map { it.logicalPath() })
            val argv = ModelWire.arguments(f.launch, f.request.generation, request)
            val physicalSources = selected.map { Path.of(materialized.sourceRoot.path()).resolve(it).toString() }
            assertEquals(physicalSources, argv.takeLast(selected.size))
            assertFalse(argv.any { it.endsWith("build.gradle.kts") || it.endsWith("state.txt") })
            assertFalse("src/main/kotlin/B.kt" in materialized.effective.changedPaths)
            materialized.verifyInputs {}
            fullPermit.capture.verify {}
            assertEquals(0, f.session.submissions)
        } finally {
            fullPermit.close()
            incrementalPermit.close() // Synthetic cancellation only; no accepted/retired runtime is claimed.
        }
    }

    @Test fun emptyResourceOnlyAndRemovedPathHintsNeverCreateAnEmptyOrChangedOnlyBatch(): Unit = ModelLiveHostFixture().use { f ->
        val archive = f.archive()
        val full = archive.child.copy(changedPaths = emptyList(), baselineSnapshotId = null)
        val selected = requireNotNull(full.context.compilationFiles)
        val expectedSources = CompilerSnapshot.read(full)
        val fullCapture = ModelArchiveCapture.capture(full, f.prepared.binding) {}
        val expectedWork = capturedWork(fullCapture, selected, f.prepared.normalizedCompilerOptions)
        val hints = listOf("no current change" to emptyList(),
            "resource only" to listOf("src/main/resources/state.txt"),
            "removed prior member" to listOf("src/main/kotlin/Removed.kt"))
        for ((label, paths) in hints) {
            // These are synthetic scheduling hints, not proof that a baseline/deletion was imported.
            val input = full.copy(changedPaths = paths, baselineSnapshotId = Digests.sha256("synthetic-baseline:$label"))
            val capture = ModelArchiveCapture.capture(input, f.prepared.binding) {}
            assertEquals(selected, CompilerSnapshot.compilationPaths(input), label)
            assertEquals(expectedSources, CompilerSnapshot.read(input), label)
            assertEquals(selected, capture.sourceTexts.keys.toList(), label)
            assertEquals(fullCapture.sourceTexts, capture.sourceTexts, label)
            assertEquals(full.snapshot.files.size, capture.files.size, label)
            assertEquals(expectedWork, capturedWork(capture, selected, f.prepared.normalizedCompilerOptions), label)
            assertNotEquals(full.analysisKey(), input.analysisKey(), label)
            assertFalse("src/main/kotlin/Removed.kt" in capture.sourceTexts)
            capture.verify {}
        }
        assertTrue(liveNames(f.generationRoot.resolve("jobs"), 0).isEmpty())
        assertEquals(0, f.session.submissions)
    }

    @Test fun sameLengthDriftInAnUnchangedProducerFailsBothFullAndIncrementalAdmission(): Unit = ModelLiveHostFixture().use { f ->
        val archive = f.archive()
        val full = archive.child.copy(changedPaths = emptyList(), baselineSnapshotId = null)
        val incremental = full.copy(changedPaths = listOf("src/main/kotlin/B.kt"),
            baselineSnapshotId = Digests.sha256("synthetic-unchanged-producer-baseline"))
        val inputs = listOf(full, incremental)
        val captures = inputs.map { ModelArchiveCapture.capture(it, f.prepared.binding) {} }
        val expected = CompilerSnapshot.read(full)
        inputs.forEach { assertEquals(expected, CompilerSnapshot.read(it)) }
        val relative = "src/main/kotlin/A.kt"
        assertFalse(relative in incremental.changedPaths)
        assertTrue(relative in requireNotNull(incremental.context.compilationFiles))
        val path = Path.of(archive.child.materializedRoot).resolve(relative)
        val before = Files.readAllBytes(path)
        val changed = before.copyOf()
        val point = before.indexOf('1'.code.toByte())
        assertTrue(point >= 0, "The fixture's producer body must have a same-length edit control")
        changed[point] = '2'.code.toByte()
        // SnapshotStore.materialize defaults to read-only, and Files.copy may retain that
        // mode. Grant write access ONLY to this owned test archive; do not depend on UID 0.
        // This no-follow/SecureDirectoryStream fixture's mutation control explicitly requires
        // POSIX permission restoration. A non-POSIX host fails this prerequisite, never skips
        // the negative control, guesses writability or silently reports a successful no-op.
        val permissionView = assertNotNull(Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS),
            "This deliberate archive-drift control requires no-follow POSIX permission restoration")
        val originalPermissions = permissionView.readAttributes().permissions().toSet()
        try {
            permissionView.setPermissions(originalPermissions + OWNER_WRITE)
            try {
                Files.write(path, changed)
                assertEquals(before.size.toLong(), Files.size(path))
                assertNotEquals(Digests.sha256(before), Digests.sha256(changed))
                inputs.zip(captures).forEach { (input, capture) ->
                    assertEquals("Source snapshot size/digest changed or exceeds limit",
                        assertFailsWith<IllegalArgumentException> { CompilerSnapshot.read(input) }.message)
                    expectModelLiveFailure("compiler_model_live_snapshot_member_drift") { capture.verify {} }
                    expectModelLiveFailure("compiler_model_live_snapshot_member_drift") {
                        ModelArchiveCapture.capture(input, f.prepared.binding) {}
                    }
                }
            } finally {
                Files.write(path, before)
            }
        } finally {
            // Still attempted if mutation, an assertion or even byte restoration throws.
            permissionView.setPermissions(originalPermissions)
        }
        assertContentEquals(before, Files.readAllBytes(path))
        assertEquals(originalPermissions, permissionView.readAttributes().permissions())
        inputs.zip(captures).forEach { (input, capture) ->
            assertEquals(expected, CompilerSnapshot.read(input))
            capture.verify {}
        }
        assertEquals(0, f.session.submissions)
    }

    private fun currentCatalog(): OwnerCatalogInventory {
        val records = listOf("core", "libraries", "platform", "ecosystem").flatMap { group ->
            val resource = requireNotNull(javaClass.getResourceAsStream("/model-live-control-catalog/rules-$group.json")) {
                "Use the current distributed catalog, never 1663 fabricated replacement IDs"
            }
            resource.use { ContractJson.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)).jsonArray.map { value -> value.jsonObject } }
        }
        assertEquals(1663, records.size, "Inventory changes require deliberate review of this preservation control")
        return OwnerCatalogInventory(Digests.sha256(Digests.canonical(JsonArray(records))),
            records.map { it.getValue("id").jsonPrimitive.content }).also { assertEquals(1663, it.ruleIds.size) }
    }

    private fun capturedWork(capture: ModelArchiveCapture, selected: List<String>, options: List<String>): String {
        val members = capture.input.snapshot.files.mapIndexed { index, member ->
            ModelWire.Member(member.path, member.digest, member.size, capture.files[index].fileKey)
        }
        val dependencies = capture.input.context.classpath.mapIndexed { index, artifact ->
            val pin = capture.dependencies[index]
            ModelWire.Dependency(artifact.coordinate, artifact.digest, pin.size, pin.fileKey)
        }
        return ModelWire.workDigest(members, selected, dependencies, options)
    }
}

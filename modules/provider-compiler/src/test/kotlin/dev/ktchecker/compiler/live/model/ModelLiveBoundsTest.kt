package dev.ktchecker.compiler.live.model

import dev.ktchecker.contracts.*
import dev.ktchecker.workerprotocol.CompilerModelWire as ModelWire
import kotlin.test.*

/** Pure finite-shape controls; no model/worker execution or qualification is inferred. */
class ModelLiveBoundsTest {
    @Test fun hostSnapshotDependencyAndPathCapsMatchTheSeparateModelProtocol() {
        assertEquals(ModelWire.MAX_SNAPSHOT_FILES, ModelLiveLimits.SNAPSHOT_FILES)
        assertEquals(ModelWire.MAX_COMPILATION_FILES, ModelLiveLimits.COMPILATION_FILES)
        assertEquals(ModelWire.MAX_SOURCE_BYTES.toLong(), ModelLiveLimits.SOURCE_BYTES)
        assertEquals(ModelWire.MAX_SNAPSHOT_BYTES, ModelLiveLimits.SNAPSHOT_BYTES)
        assertEquals(ModelWire.MAX_DEPENDENCIES, ModelLiveLimits.DEPENDENCIES)
        assertEquals(ModelWire.MAX_DEPENDENCY_BYTES, ModelLiveLimits.DEPENDENCY_BYTES)
        assertEquals(ModelWire.MAX_DEPENDENCY_TOTAL_BYTES, ModelLiveLimits.DEPENDENCY_TOTAL_BYTES)
        assertEquals(ModelWire.MAX_LOGICAL_PATH_BYTES, ModelLiveLimits.PATH_BYTES)
        assertEquals(ModelWire.MAX_PATH_DEPTH, ModelLiveLimits.PATH_DEPTH)
        assertEquals(16, ModelWire.MAX_OPTIONS)
    }

    @Test fun snapshotsCannotExceedFileCountPerMemberOrWholeSnapshotBytes() {
        val input = ModelRenderingControl().original
        expectModelLiveFailure("compiler_model_live_input_limit") {
            freezeModelLiveInput(input.copy(snapshot = input.snapshot.copy(files = List(257) {
                SnapshotFile("source$it.kt", Digests.sha256("synthetic-$it"), 0)
            })))
        }
        expectModelLiveFailure("compiler_model_live_snapshot_limit") {
            requireModelLiveShape(input.copy(snapshot = input.snapshot.copy(files = input.snapshot.files.mapIndexed { index, file ->
                if (index == 0) file.copy(size = ModelLiveLimits.SOURCE_BYTES + 1) else file
            })))
        }
        val total = List(17) { SnapshotFile("source$it.kt", Digests.sha256("synthetic-large-$it"), ModelLiveLimits.SOURCE_BYTES) }
        expectModelLiveFailure("compiler_model_live_snapshot_limit") {
            requireModelLiveShape(input.copy(snapshot = input.snapshot.copy(files = total), context = input.context.copy(compilationFiles = listOf("source0.kt"))))
        }
    }

    @Test fun wholeSnapshotPathsAreCanonicalUniqueAndNeverBothFileAndParentDirectory() {
        val input = ModelRenderingControl().original
        val examples = listOf(input.snapshot.files + input.snapshot.files.first(),
            input.snapshot.files + SnapshotFile("src", Digests.sha256("ambiguous-file-parent"), 0))
        examples.forEach { files -> expectModelLiveFailure("compiler_model_live_snapshot_members_invalid") {
            requireModelLiveShape(input.copy(snapshot = input.snapshot.copy(files = files)))
        } }
        for (path in listOf("../escape.kt", "src/../A.kt", "src//A.kt", "src\\A.kt", "/absolute.kt", "src/./A.kt")) {
            expectModelLiveFailure("compiler_model_live_path_invalid") { modelRelativePath(path) }
        }
        expectModelLiveFailure("compiler_model_live_path_limit") { modelRelativePath("é".repeat(255) + ".kt") }
        expectModelLiveFailure("compiler_model_live_path_limit") { modelRelativePath(List(17) { "part" }.joinToString("/") + ".kt") }
    }

    @Test fun compilationSelectionMustBeNonemptyExactDuplicateFreeKotlinSubset() {
        val input = ModelRenderingControl().original
        val selections = listOf<List<String>?>(null, emptyList(), listOf("src/A.kt", "src/A.kt"),
            listOf("missing.kt"), listOf("build.gradle.kts"), listOf("resources/opaque.bin"))
        selections.forEach { paths -> expectModelLiveFailure("compiler_model_live_compilation_members_invalid") {
            requireModelLiveShape(input.copy(context = input.context.copy(compilationFiles = paths)))
        } }
        expectModelLiveFailure("compiler_model_live_input_limit") {
            freezeModelLiveInput(input.copy(context = input.context.copy(compilationFiles = List(65) { "synthetic$it.kt" })))
        }
    }

    @Test fun dependencyCountOptionTokensAndPolicyTextRemainBoundedBeforeDetachment() {
        val input = ModelRenderingControl().original
        expectModelLiveFailure("compiler_model_live_input_limit") {
            freezeModelLiveInput(input.copy(context = input.context.copy(classpath = List(17) {
                ArtifactIdentity("synthetic:$it", Digests.sha256("dependency-$it"), "/synthetic/$it.jar")
            })))
        }
        expectModelLiveFailure("compiler_model_live_input_limit") {
            freezeModelLiveInput(input.copy(context = input.context.copy(compilerArguments = List(ModelLiveLimits.ORIGINAL_ARGUMENTS + 1) { "-nowarn" })))
        }
        expectModelLiveFailure("compiler_model_live_input_limit") {
            freezeModelLiveInput(input.copy(context = input.context.copy(policies = mapOf("oversized" to "x".repeat(1_048_577)))))
        }
        expectModelLiveFailure("compiler_model_live_input_limit") {
            freezeModelLiveInput(input.copy(context = input.context.copy(policies = mapOf("first" to "x".repeat(1_048_576),
                "second" to "y".repeat(1_048_576)))))
        }
    }
}

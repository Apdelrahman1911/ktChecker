package dev.ktchecker.providers.runtime

import dev.ktchecker.contracts.*
import kotlin.test.*

/** Pure structural model/task tests; they neither execute Gradle nor claim source capture. */
class ModelBindingsTest {
    private val first = "src/not-an-ownership-prefix/Api.kt"
    private val second = "generated/Platform.kt"
    private val scope = RuleContext("iosArm64", "commonMain", ":library", "main")
    private val modelDigest = Digests.sha256("explicit imported model")
    private fun input(members: List<String>? = listOf(first, second)) = AnalysisInput(
        SnapshotManifest(Digests.sha256("synthetic descriptor"), "fixture", "binding-test",
            listOf(first, second).map { SnapshotFile(it, Digests.sha256(it), 1) }),
        "/unused-structural-test", AnalysisContext(scope, modelDigest = modelDigest, compilationFiles = members),
    )
    private fun model() = ExactCompilationModel(scope, modelDigest, mapOf(first to "commonMain", second to "iosMain"))

    @Test fun `exact imported file ownership never follows the directory name`() {
        val selected = ExactCompilationSources(input(), model())
        assertEquals("commonMain", selected.sourceSet(first))
        assertEquals("iosMain", selected.sourceSet(second))
        assertEquals("location_outside_compilation", assertFailsWith<ExecutionProblem> {
            selected.sourceSet("src/iosMain/Unimported.kt")
        }.code)
    }

    @Test fun `scope and model digest are bound to the complete selected compilation`() {
        for (selected in listOf(model().copy(scope = scope.copy(module = ":other")),
            model().copy(scope = scope.copy(target = "iosSimulatorArm64")),
            model().copy(scope = scope.copy(sourceSet = "iosMain")),
            model().copy(scope = scope.copy(compilation = "test")),
            model().copy(modelDigest = Digests.sha256("different model")))) {
            assertEquals("compilation_model_binding_mismatch", assertFailsWith<ExecutionProblem> {
                ExactCompilationSources(input(), selected)
            }.code)
        }
    }

    @Test fun `missing member or ownership models are unavailable not all captured files`() {
        assertEquals("compilation_model_unavailable", assertFailsWith<ExecutionProblem> {
            ExactCompilationSources(input(), null)
        }.code)
        assertEquals("compilation_members_unavailable", assertFailsWith<ExecutionProblem> {
            ExactCompilationSources(input(null), model())
        }.code)
        for (ownership in listOf(emptyMap(), mapOf(first to "commonMain"),
            model().sourceSetByFile + ("outside.kt" to "commonMain"),
            model().sourceSetByFile + (first to " "))) {
            assertEquals("compilation_source_ownership_unavailable", assertFailsWith<ExecutionProblem> {
                ExactCompilationSources(input(), model().copy(sourceSetByFile = ownership))
            }.code)
        }
    }

    @Test fun `duplicate unmanifested and unsafe model members fail closed`() {
        assertEquals("compilation_members_duplicate", assertFailsWith<ExecutionProblem> {
            ExactCompilationSources(input(listOf(first, first)), model())
        }.code)
        assertEquals("compilation_member_not_in_manifest", assertFailsWith<ExecutionProblem> {
            ExactCompilationSources(input(listOf("absent.kt")), model())
        }.code)
        assertEquals("relative_path_invalid", assertFailsWith<ExecutionProblem> {
            ExactCompilationSources(input(listOf("../outside.kt")), model())
        }.code)
    }

    @Test fun `retained mutable ownership cannot relabel a constructed selection`() {
        val ownership = model().sourceSetByFile.toMutableMap()
        val selected = ExactCompilationSources(input(), model().copy(sourceSetByFile = ownership))
        ownership[first] = "iosMain"
        assertEquals("commonMain", selected.sourceSet(first))
    }

    @Test fun `task selection requires exact qualified paths for the imported module`() {
        val required = setOf(":library:compileCommonMainKotlinMetadata", ":library:compileKotlinIosArm64")
        ExactGradleTaskProof.requireSelection(required.toList(), required, ":library")
        assertEquals("gradle_exact_task_not_selected", assertFailsWith<ExecutionProblem> {
            ExactGradleTaskProof.requireSelection(required.map { it.replace(":library:", ":other:") }, required, ":library")
        }.code)
        assertEquals("gradle_task_module_mismatch", assertFailsWith<ExecutionProblem> {
            ExactGradleTaskProof.requireSelection(required.toList(), required, ":other")
        }.code)
        for (bad in listOf(emptySet(), setOf("compileKotlinIosArm64"), setOf(":library:compileKotlin*"))) {
            assertEquals("gradle_exact_task_model_required", assertFailsWith<ExecutionProblem> {
                ExactGradleTaskProof.requireSelection(bad.toList(), bad, ":library")
            }.code)
        }
    }

    @Test fun `unrelated cached skipped no-source and duplicate tasks do not prove required execution`() {
        val required = setOf(":library:compileKotlinIosArm64")
        val observed = listOf(
            "> Task :other:compileKotlinIosArm64",
            "> Task :library:compileKotlinIosArm64 UP-TO-DATE",
        ).map { requireNotNull(ExactGradleTaskProof.parseLine(it)) }
        assertEquals(required.toList(), ExactGradleTaskProof.missingExecution(required, observed))
        for (outcome in listOf("FROM-CACHE", "SKIPPED", "NO-SOURCE")) {
            assertEquals(required.toList(), ExactGradleTaskProof.missingExecution(required,
                listOf(GradleTaskObservation(required.single(), outcome))))
        }
        val actual = GradleTaskObservation(required.single(), "EXECUTED")
        assertTrue(ExactGradleTaskProof.missingExecution(required, listOf(actual)).isEmpty())
        assertEquals(required.toList(), ExactGradleTaskProof.missingExecution(required, listOf(actual, actual)))
        assertTrue(ExactGradleTaskProof.hasUnrelatedFailure(required,
            listOf(actual, GradleTaskObservation(":other:compileKotlinIosArm64", "FAILED"))))
    }
}

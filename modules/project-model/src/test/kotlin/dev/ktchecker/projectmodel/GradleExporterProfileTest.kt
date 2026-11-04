package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.nio.file.Files
import kotlin.test.*

class GradleExporterProfileTest {
    @Test fun pinnedExporterUsesActualOwnerSelectedTasksAndNeverGrantsExecutionFromApprovedJson(): Unit {
        val fixture = ModelFixtures(); val capture = fixture.capture()
        // Synthetic paths are host-native absolute selections; no launcher or runtime is enrolled.
        val selected = fixture.work.resolve("owner-selected")
        val profile = GradleModelExporter.profile(selected.resolve("gradle"), Digests.sha256("launcher"),
            selected.resolve("jdk"), selected.resolve("gradle-home"), listOf(":produceExplicitInputs"))
        assertContains(profile.arguments, "--offline"); assertContains(profile.arguments, "--no-configuration-cache")
        assertContains(profile.arguments, "--project-dir"); assertContains(profile.arguments, "{snapshot}")
        assertEquals(listOf(":produceExplicitInputs", "ktCheckerModelExport"), profile.arguments.takeLast(2))
        val input = AnalysisInput(capture.snapshot, capture.root.toString(), execution = profile, outputRoot = fixture.work.resolve("outputs").toString())
        val result = IsolatedProcessRunner().run(input)
        assertEquals(listOf("execution_not_authorized"), result.reasons)
        assertFalse(result.successfulExecution)
        assertFailsWith<IllegalArgumentException> { GradleModelExporter.profile(selected.resolve("gradle"), Digests.sha256("launcher"),
            selected.resolve("jdk"), selected.resolve("gradle-home"), listOf("--injected-option")) }
    }

    @Test fun resourceIsExactPinnedActualGradleApiExporterNotCallerSuppliedGraphFlags(): Unit {
        val output = Files.createTempDirectory("ktchecker-model-script-").toRealPath()
        GradleModelExporter.prepareOutput(output)
        assertEquals(GradleModelExporter.scriptDigest(), Digests.sha256(Files.readAllBytes(output.resolve(GradleModelExporter.SCRIPT))))
        val script = GradleModelExporter.scriptBytes().toString(Charsets.UTF_8)
        assertContains(script, "root.allprojects"); assertContains(script, "allKotlinSourceSets")
        assertContains(script, "compileTaskProvider"); assertContains(script, "resolutionResult")
        assertContains(script, "KtCheckerPinnedModelApi.fullCompilerArguments(task)")
        assertFalse(script.contains("args = task.serializedCompilerArguments"), "Partial KGP options cannot stand in for full task arguments")
        assertContains(script, "projectBuildPath(resolutionRoot.id")
        assertContains(script, "selectedBuildPath != rootBuildPath")
        assertFalse(script.contains("selected.build.currentBuild"))
        assertFalse(script.contains("findByName('main')"))
        // Pin the resource's actual wiring; producer-boundary and ambiguity behavior
        // is exercised against these exact helpers by GradleModelProducerAssociationTest.
        assertContains(script, "new KtCheckerModelProducerIndex(")
        assertContains(script, "index.register(owner.project, owner.compilation, owner.id, owner.tasks)")
        assertContains(script, "KtCheckerPinnedModelApi.compilerTasks(api, p, target, entry.compilation, entry.task)")
        assertContains(script, "KtCheckerPinnedModelApi.kotlinUsages(api, p, target)")
        assertContains(script, "producerIndex.resolve(p, conf.name, seeds, usageRecords,")
        assertContains(script, "if (producer.state == 'unique')")
        assertFalse(script.contains("compilationByTask[task.path]"), "Task path equality must not confer producer ownership")
        assertContains(script, "getBuildJvm\$kotlin_gradle_plugin_common")
        assertContains(script, "generatedByTask"); assertContains(script, "includedBuilds")
    }
}

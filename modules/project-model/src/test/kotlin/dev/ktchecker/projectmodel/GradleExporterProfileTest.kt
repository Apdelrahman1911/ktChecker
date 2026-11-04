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
        assertContains(script, "producerTasks"); assertFalse(script.contains("findByName('main')"))
        assertContains(script, "found.add(compilationByTask[task.path])\n                        // Compilation prerequisites describe inputs, not additional producers of this artifact.\n                        continue")
        assertContains(script, "compilationModuleById[producers[0]] == p.path")
        assertContains(script, "getBuildJvm\$kotlin_gradle_plugin_common")
        assertContains(script, "generatedByTask"); assertContains(script, "includedBuilds")
    }
}

package dev.ktchecker.providers.runtime

import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class FixtureSnapshotsTest {
    @Test fun `explicit fixture producer freezes through integrated store and excludes generated inputs`() {
        val work = Files.createTempDirectory("ktchecker-fixture-freeze-")
        val fixture = work.resolve("fixture")
        Files.createDirectories(fixture.resolve("src/commonMain"))
        Files.writeString(fixture.resolve("src/commonMain/Api.kt"), "expect fun platformName(): String\n")
        Files.createDirectories(fixture.resolve("build"))
        Files.writeString(fixture.resolve("build/generated.kt"), "not producer input")
        val frozen = FixtureSnapshots.materialize(fixture, work.resolve("work"))
        assertEquals(setOf("src/commonMain/Api.kt"), frozen.manifest.files.map { it.path }.toSet())
        assertTrue(Digests.valid(frozen.manifest.snapshotId))
        assertEquals("immutable_overlay", frozen.manifest.captureKind)
        assertTrue(frozen.manifest.scopeExclusions.any { it.contains("build") })
        frozen.verify()
        Files.writeString(fixture.resolve("src/commonMain/Api.kt"), "producer changed after explicit submission\n")
        assertEquals("expect fun platformName(): String\n", Files.readString(frozen.root.resolve("src/commonMain/Api.kt")))
        frozen.verify()
    }

    @Test fun `fixture symlinks never become captured producer sources`() {
        val work = Files.createTempDirectory("ktchecker-fixture-link-")
        val fixture = Files.createDirectory(work.resolve("fixture"))
        Files.writeString(work.resolve("external.kt"), "outside")
        Files.createSymbolicLink(fixture.resolve("source.kt"), work.resolve("external.kt"))
        assertEquals("fixture_entry_unsupported", assertFailsWith<ExecutionProblem> {
            FixtureSnapshots.materialize(fixture, work.resolve("work"))
        }.code)
    }

    @Test fun `Gradle relocations are fixed driver inputs and project cache is outside the snapshot`() {
        val output = Files.createTempDirectory("ktchecker-relocate-")
        prepareGradleOutput(output)
        val script = Files.readString(output.resolve("relocate.gradle"))
        assertContains(script, "gradle.beforeProject")
        assertContains(script, output.resolve("build").toString())
        val args = gradleFixtureArguments(listOf("compileKotlinIosArm64"), "missing-actual")
        assertContains(args, "--rerun-tasks")
        assertContains(args, "{output}/project-cache")
        assertContains(args, "{output}/relocate.gradle")
        assertContains(args, "-PfixtureNegativeCase=missing-actual")
        assertEquals("compileKotlinIosArm64", args.last())
        assertContains(gradleFixtureArguments(listOf("lint")), "-PfixtureNegativeCase=none")
    }

    @Test fun `fixture argument duplicate keys are rejected rather than silently overriding selection`() {
        assertEquals(mapOf("case" to "positive"), parseFixtureArguments(arrayOf("--case", "positive")))
        assertFailsWith<IllegalArgumentException> { parseFixtureArguments(arrayOf("--case", "positive", "--case", "negative")) }
        assertFailsWith<IllegalArgumentException> { parseFixtureArguments(arrayOf("--case")) }
        assertFailsWith<IllegalArgumentException> { parseFixtureArguments(arrayOf("case", "positive")) }
    }

    @Test fun `explicit nonexistent project JDK never falls back to the broker Java home`() {
        val nonexistent = Files.createTempDirectory("ktchecker-jdk-selection-").resolve("missing")
        assertEquals("fixture_project_jdk_unavailable", assertFailsWith<ExecutionProblem> { fixtureProjectJava(nonexistent.toString()) }.code)
    }
}

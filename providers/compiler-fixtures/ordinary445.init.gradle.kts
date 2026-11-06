import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer

// Explicitly applied only. No modification/dependency of test, check, the legacy443 task,
// or the independently pinned current445 nine-observation build route.
gradle.projectsEvaluated {
    val compiler = rootProject.project(":provider-compiler")
    compiler.tasks.register("compilerOrdinary445Fixtures", JavaExec::class.java) {
        group = "verification"
        description = "Explicit one-cohort ordinary445 real compiler fixtures; no legacy fallback or qualification."
        dependsOn(compiler.tasks.named("testClasses"))
        classpath = compiler.extensions.getByType(SourceSetContainer::class.java).getByName("test").runtimeClasspath
        mainClass.set("dev.ktchecker.compiler.CompilerOrdinary445FixtureRunner")
        maxHeapSize = "384m"
        isIgnoreExitValue = false
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        workingDir(compiler.rootProject.projectDir)
        doFirst {
            fun required(name: String): String = compiler.providers.gradleProperty(name).orNull
                ?: error("Explicit owner property -P$name is required; no discovery or fallback")
            val consent = required("compilerOrdinary445Consent")
            require(consent == "ordinary445-serial-fixtures-not-qualification-v1")
            val cohort = required("compilerOrdinary445Cohort")
            require(cohort in (1..25).map { "ordinary445-" + it.toString().padStart(2, '0') })
            val binding = required("compilerOrdinary445BindingSha256")
            val snapshot = required("compilerOrdinary445SourceSnapshot")
            val snapshotSha = required("compilerOrdinary445SourceSnapshotSha256")
            require(listOf(binding, snapshotSha).all { it.matches(Regex("[0-9a-f]{64}")) })
            val home = Path.of(required("compilerFixtureJavaHome"))
            require(System.getProperty("os.name") == "Linux" && home.isAbsolute && home.normalize() == home && home.toRealPath() == home)
            val java = home.resolve("bin/java")
            require(Files.isRegularFile(java, NOFOLLOW_LINKS) && java.toRealPath() == java)
            val heap = compiler.providers.gradleProperty("compilerFixtureHeapMiB").orNull
            require(heap == null || heap == "512")
            fun limit(name: String, default: String, minimum: Long, maximum: Long): String {
                val value = compiler.providers.gradleProperty(name).getOrElse(default)
                require(value.matches(Regex("[1-9][0-9]*")) && (value.toLongOrNull() ?: 0) in minimum..maximum)
                return value
            }
            executable = java.toString() // Parent and compiler both use the explicit selected JDK21 image.
            systemProperty("ktchecker.compiler.java", java.toString())
            systemProperty("ktchecker.compiler.heapMiB", "512")
            systemProperty("ktchecker.compiler.classpath", compiler.configurations.getByName("compilerToolchain").asPath)
            setArgs(listOf(compiler.rootProject.projectDir.absolutePath, "--cohort=$cohort", "--consent=$consent",
                "--binding-sha256=$binding", "--source-snapshot=$snapshot", "--source-snapshot-sha256=$snapshotSha",
                "--overall-seconds=" + limit("compilerOrdinary445OverallSeconds", "1200", 1, 1200),
                "--per-job-seconds=" + limit("compilerOrdinary445PerJobSeconds", "90", 1, 90),
                "--evidence-mib=" + limit("compilerOrdinary445EvidenceMiB", "256", 64, 256)))
        }
    }
}

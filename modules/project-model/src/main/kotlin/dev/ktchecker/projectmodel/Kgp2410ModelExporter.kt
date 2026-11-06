package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.TrustedExecution
import java.nio.file.Files
import java.nio.file.Path

const val KGP_2410_MODEL_EXPORTER_ID = "ktchecker-gradle-kgp-2.4.10-model/1"

/** Explicit source-authored experiment, not an execution grant or configuration-read proof.
 * The original exporter resource/object and normal/observed selection remain unchanged.
 * This profile requests only its report task and exposes no prerequisite-task list.
 * Project configuration remains executable; this is not proof of what Gradle actually ran. */
object Kgp2410ModelExporter {
    const val SCRIPT = "ktchecker-model-kgp-2.4.10.init.gradle"
    const val REPORT = "model-export-kgp-2.4.10.json"
    const val TASK = "ktCheckerKgp2410ModelExport"
    fun scriptBytes(): ByteArray = requireNotNull(javaClass.getResourceAsStream("/$SCRIPT")).use { it.readBytes() }
    fun scriptDigest(): String = Digests.sha256(scriptBytes())

    fun prepareOutput(output: Path) {
        Files.createDirectories(output)
        Files.createDirectories(output.resolve("home"))
        Files.write(output.resolve(SCRIPT), scriptBytes())
    }

    fun profile(gradle: Path, gradleLauncherDigest: String, javaHome: Path, gradleUserHome: Path): TrustedExecution {
        require(gradle.isAbsolute && javaHome.isAbsolute && gradleUserHome.isAbsolute && Digests.valid(gradleLauncherDigest))
        return TrustedExecution(gradle.toString(), listOf("--no-daemon", "--offline", "--no-configuration-cache", "--console=plain", "--max-workers=1", "--rerun-tasks",
            "--project-dir", "{snapshot}", "--project-cache-dir", "{output}/gradle-project-cache",
            "--init-script", "{output}/$SCRIPT", "-PktcheckerModelOutput={output}/$REPORT",
            "-PktcheckerModelBuildRoot={output}/gradle-build", "-Pkotlin.project.persistent.dir={output}/kotlin-state",
            "-Dkotlin.project.persistent.dir={output}/kotlin-state", "-Pkotlin.compiler.execution.strategy=in-process",
            "-Dorg.gradle.java.installations.auto-detect=false", "-Dorg.gradle.java.installations.auto-download=false",
            "-Dorg.gradle.java.installations.paths=$javaHome", "-Duser.home={output}/home",
            "-Dorg.gradle.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=256m -Duser.home={output}/home -Djava.io.tmpdir={output}/tmp", TASK),
            approved = true, executableDigest = gradleLauncherDigest, timeoutMillis = 180_000, maxOutputBytes = 4_194_304,
            environment = mapOf("JAVA_HOME" to javaHome.toString(), "GRADLE_USER_HOME" to gradleUserHome.toString(),
                "PATH" to javaHome.resolve("bin").toString() + java.io.File.pathSeparator + "/usr/bin:/bin"))
    }
}

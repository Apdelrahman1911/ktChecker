val lintBaselineRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
repositories { google() }
dependencies {
    implementation(project(":contracts"))
    implementation(project(":provider-runtime"))
    lintBaselineRuntime("com.android.tools.lint:lint:32.3.1")
}
tasks.test {
    // Lint runs in a separate JVM with its own resolved implementation/compiler classpath.
    inputs.files(lintBaselineRuntime)
    doFirst {
        systemProperty("ktchecker.lint.classpath", lintBaselineRuntime.asPath)
        systemProperty("ktchecker.repository", rootProject.projectDir.absolutePath)
    }
}

tasks.register<JavaExec>("lintFixture") {
    group = "verification"
    description = "Execute explicitly selected frozen Android Lint XML or KMP NewApi controls."
    dependsOn(tasks.named("classes"))
    classpath = project.extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
    mainClass.set("dev.ktchecker.providers.lint.LintFixtureMain")
    doFirst {
        args = listOf(
            "--fixture", providers.gradleProperty("platformFixture").orElse(rootProject.file("providers/platform-fixtures/android").absolutePath).get(),
            "--gradle", providers.gradleProperty("platformGradle").orElse(rootProject.file("gradlew").absolutePath).get(),
            "--case", providers.gradleProperty("platformCase").orElse("positive").get(),
            "--result", providers.gradleProperty("platformResult").get(),
            "--lint-classpath", lintBaselineRuntime.asPath,
        ) + providers.gradleProperty("platformJavaHome").orNull?.let { listOf("--java-home", it) }.orEmpty() +
            providers.gradleProperty("platformLintAnalysisTask").orNull?.let { listOf("--lint-analysis-task", it) }.orEmpty() +
            providers.gradleProperty("platformLintReportTask").orNull?.let { listOf("--lint-report-task", it) }.orEmpty()
    }
}

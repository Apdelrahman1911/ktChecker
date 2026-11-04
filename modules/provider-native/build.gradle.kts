dependencies {
    implementation(project(":contracts"))
    implementation(project(":provider-runtime"))
    implementation(project(":snapshot"))
}
tasks.test { systemProperty("ktchecker.repository", rootProject.projectDir.absolutePath) }

tasks.register<JavaExec>("nativeFixture") {
    group = "verification"
    description = "Run an explicitly selected frozen common+iOS provider fixture (Apple execution only)."
    dependsOn(tasks.named("classes"))
    classpath = project.extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
    mainClass.set("dev.ktchecker.providers.native.NativeFixtureMain")
    doFirst {
        args = listOf(
            "--fixture", providers.gradleProperty("platformFixture").get(),
            "--gradle", providers.gradleProperty("platformGradle").get(),
            "--target", providers.gradleProperty("platformTarget").orElse("iosArm64").get(),
            "--case", providers.gradleProperty("platformCase").orElse("positive").get(),
            "--result", providers.gradleProperty("platformResult").get(),
        ) + providers.gradleProperty("platformNativeHome").orNull?.let { listOf("--native-home", it) }.orEmpty() +
            providers.gradleProperty("platformJavaHome").orNull?.let { listOf("--java-home", it) }.orEmpty()
    }
}

dependencies {
    implementation(project(":contracts"))
    // Observed one-shot import retains the SAME owner deadline; never a reconstructed timer.
    implementation(project(":provider-runtime"))
    testImplementation(project(":snapshot"))
    testImplementation(project(":provider-runtime"))
    // Exercise the pinned exporter helpers without configuring or executing a Gradle build.
    testImplementation(localGroovy())
}

tasks.withType<Test>().configureEach {
    systemProperty("ktchecker.model.fixtures", rootProject.file("providers/model-fixtures").absolutePath)
}

tasks.register<JavaExec>("ownerModelFixture") {
    description = "Execute the fixed owner exporter → trusted importer example; not runtime qualification."
    group = "verification"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.ktchecker.projectmodel.OwnerModelFixtureRunner")
    maxHeapSize = "384m"
    doFirst {
        args = listOf("--fixtures", rootProject.file("providers/model-fixtures").absolutePath,
            "--result", providers.gradleProperty("modelFixtureResult")
                .getOrElse(layout.buildDirectory.file("reports/owner-model-fixture/result.json").get().asFile.absolutePath)) +
            providers.gradleProperty("modelFixtureInputs").orNull?.let { listOf("--inputs", it) }.orEmpty()
    }
}

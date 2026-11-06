plugins { kotlin("jvm"); kotlin("plugin.serialization"); application }

val bundledCompiler = configurations.create("bundledCompiler") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// Packaging-only task output, never an engine/runtimeClasspath dependency.
// Defer lookup until the producer project has registered its reviewed task.
val bundledCompilerModel = files(providers.provider {
    project(":provider-compiler").tasks.named<Jar>("compilerModelApplicationWorkerJar").get().archiveFile.get()
}).builtBy(":provider-compiler:compilerModelApplicationWorkerJar")

dependencies {
    implementation(project(":worker-protocol"))
    implementation(project(path = ":worker-protocol", configuration = "modelWireElements"))
    implementation(project(":contracts"))
    implementation(project(":snapshot"))
    implementation(project(":kernel"))
    implementation(project(":config"))
    implementation(project(":project-model"))
    implementation(project(":review-controller"))
    implementation(project(":provider-runtime"))
    implementation(project(":provider-compiler"))
    implementation(project(":provider-detekt"))
    implementation(project(":provider-ktlint"))
    implementation(project(":provider-lint"))
    implementation(project(":provider-native"))
    bundledCompiler("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21")
    // Packaging-only roots complete the preselected owner-model compiler toolchain.
    // Keep these out of the host/worker runtimeClasspath; installed bytes are admitted separately.
    bundledCompiler("org.jetbrains.kotlin:kotlin-build-tools-compat:2.4.21")
    bundledCompiler("org.jetbrains.kotlin:kotlin-build-tools-impl:2.4.21")
}

distributions {
    main {
        distributionBaseName.set("ktchecker")
        contents {
            from(bundledCompiler) { into("providers/compiler") }
            from(bundledCompilerModel) { into("providers/compiler-model") }
            from(rootProject.file("THIRD_PARTY_NOTICES.md"))
            from(rootProject.file("third_party")) { into("third_party") }
            from(rootProject.file("docs/implementation-contract.md")) { into("docs") }
        }
    }
}

application {
    applicationName = "ktchecker"
    mainClass = "dev.ktchecker.cli.MainKt"
    applicationDefaultJvmArgs = listOf("-Xmx512m", "-XX:MaxMetaspaceSize=256m")
}

tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("data")) { include("rules-*.json", "catalog-baseline.json"); into("catalog") }
}

plugins { kotlin("jvm"); kotlin("plugin.serialization"); application }

val bundledCompiler = configurations.create("bundledCompiler") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
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
}

distributions {
    main {
        distributionBaseName.set("ktchecker")
        contents {
            from(bundledCompiler) { into("providers/compiler") }
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

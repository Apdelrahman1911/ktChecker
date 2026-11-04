plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":contracts"))
    implementation(project(":provider-runtime"))
    testImplementation(project(":snapshot"))
}

tasks.withType<Test>().configureEach {
    systemProperty("ktchecker.repositoryRoot", rootProject.projectDir.absolutePath)
    systemProperty("ktchecker.ktlint.integration", providers.gradleProperty("ktlintIntegration").getOrElse("false"))
    providers.gradleProperty("libraryFixtureEvidenceRoot").orNull?.let {
        systemProperty("ktchecker.libraryFixtureEvidenceRoot", rootProject.file(it).absolutePath)
    }
}

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
    systemProperty("ktchecker.detekt.integration", providers.gradleProperty("detektIntegration").getOrElse("false"))
}

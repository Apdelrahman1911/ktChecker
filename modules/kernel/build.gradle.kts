plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}
dependencies {
    implementation(project(":contracts"))
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
}
tasks.withType<Test>().configureEach {
    systemProperty("ktchecker.kernel.stressReport", layout.buildDirectory.file("reports/stress/kernel-100-patches.json").get().asFile.absolutePath)
}

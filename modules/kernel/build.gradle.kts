plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}
dependencies {
    implementation(project(":contracts"))
    // 3.50.3 is affected by SQLite's documented concurrent WAL-reset race.
    // Keep the actual embedded version covered by the kernel integration suite.
    implementation("org.xerial:sqlite-jdbc:3.54.1.0")
}
tasks.withType<Test>().configureEach {
    systemProperty("ktchecker.kernel.stressReport", layout.buildDirectory.file("reports/stress/kernel-100-patches.json").get().asFile.absolutePath)
}

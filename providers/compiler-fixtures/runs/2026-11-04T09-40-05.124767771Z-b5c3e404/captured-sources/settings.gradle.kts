pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
rootProject.name = "ktChecker"
listOf("contracts", "snapshot", "kernel", "config", "provider-runtime", "provider-compiler", "provider-detekt", "provider-ktlint", "provider-lint", "provider-native", "semantic-facts", "project-model", "provider-architecture", "cli").forEach {
    include(":$it")
    project(":$it").projectDir = file("modules/$it")
}
include(":audit")
project(":audit").projectDir = file("tests/audit")

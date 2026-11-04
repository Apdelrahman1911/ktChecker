plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":contracts"))
    implementation(project(":provider-runtime"))
}

val compilerToolchain by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    compilerToolchain("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21")
}

tasks.register<JavaExec>("compilerFixtures") {
    description = "Execute real, serial Kotlin/JVM compiler-provider fixtures; never a registry-count test."
    group = "verification"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.ktchecker.compiler.CompilerFixtureRunner")
    maxHeapSize = "384m"
    doFirst {
        systemProperty("ktchecker.compiler.classpath", compilerToolchain.asPath)
        systemProperty("ktchecker.compiler.java", javaLauncher.get().executablePath.asFile.absolutePath)
        systemProperty("ktchecker.compiler.heapMiB", providers.gradleProperty("compilerFixtureHeapMiB").getOrElse("512"))
        args(rootProject.file("providers/compiler-fixtures").absolutePath)
    }
}

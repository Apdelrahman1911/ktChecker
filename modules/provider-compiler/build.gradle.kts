import java.nio.file.Path

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":contracts"))
    implementation(project(":provider-runtime"))
    implementation(project(":project-model"))
    testImplementation(project(":snapshot"))
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
        val selectedJavaHome = providers.gradleProperty("compilerFixtureJavaHome").orNull
            ?: error("Explicit -PcompilerFixtureJavaHome=<absolute owner-prepared link-free JDK21 home> is required; no system-JDK fallback")
        val selectedHome = Path.of(selectedJavaHome)
        require(selectedHome.isAbsolute) { "compilerFixtureJavaHome must be absolute" }
        val javaName = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        systemProperty("ktchecker.compiler.classpath", compilerToolchain.asPath)
        systemProperty("ktchecker.compiler.java", selectedHome.resolve("bin").resolve(javaName).toString())
        systemProperty("ktchecker.compiler.heapMiB", providers.gradleProperty("compilerFixtureHeapMiB").getOrElse("512"))
        args(rootProject.file("providers/compiler-fixtures").absolutePath)
    }
}

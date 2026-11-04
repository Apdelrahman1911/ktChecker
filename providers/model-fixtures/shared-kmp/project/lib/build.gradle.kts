plugins { kotlin("multiplatform") }
kotlin {
    jvmToolchain(21)
    jvm("server") { attributes.attribute(Attribute.of("fixture.target", String::class.java), "server") }
    jvm("desktop") { attributes.attribute(Attribute.of("fixture.target", String::class.java), "desktop") }
    sourceSets {
        val commonMain by getting
        val sharedMain by creating { dependsOn(commonMain) }
        val serverMain by getting { dependsOn(sharedMain) }
        val desktopMain by getting { dependsOn(sharedMain) }
        all {
            kotlin.setSrcDirs(listOf("src/$name/kotlin"))
            resources.setSrcDirs(listOf("src/$name/resources"))
        }
    }
}

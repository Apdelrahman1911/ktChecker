plugins { kotlin("multiplatform") }
kotlin {
    jvmToolchain(21)
    jvm("server") { attributes.attribute(Attribute.of("fixture.target", String::class.java), "server") }
    sourceSets {
        val commonMain by getting { dependencies { implementation(project(":lib")) } }
        all {
            kotlin.setSrcDirs(listOf("src/$name/kotlin"))
            resources.setSrcDirs(listOf("src/$name/resources"))
        }
    }
}

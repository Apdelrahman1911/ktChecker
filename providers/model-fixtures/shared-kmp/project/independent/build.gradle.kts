plugins { kotlin("multiplatform") }
kotlin {
    jvmToolchain(21)
    jvm("server") { attributes.attribute(Attribute.of("fixture.target", String::class.java), "server") }
    sourceSets.all {
        kotlin.setSrcDirs(listOf("src/$name/kotlin"))
        resources.setSrcDirs(listOf("src/$name/resources"))
    }
}

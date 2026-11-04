plugins { kotlin("jvm") }
kotlin {
    jvmToolchain(21)
    sourceSets.all {
        kotlin.setSrcDirs(listOf("src/$name/kotlin"))
        resources.setSrcDirs(listOf("src/$name/resources"))
    }
}
dependencies { implementation(project(":lib")) }

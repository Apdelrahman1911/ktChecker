plugins { kotlin("multiplatform") version "2.4.21" apply false }
subprojects {
    repositories { mavenCentral() }
    tasks.withType<Jar>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

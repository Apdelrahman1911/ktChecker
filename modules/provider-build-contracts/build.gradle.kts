import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

dependencies {
    implementation(project(":contracts"))
}

// Reuse the already-tracked wrapper artifact as inert fixture bytes; never add a second binary.
val trackedWrapperFixture = rootProject.layout.projectDirectory.file("gradle/wrapper/gradle-wrapper.jar")
val trackedWrapperFixtureSha256 = "7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d"
val trackedWrapperFixtureBytes = 47505
val wrapperFixtureResource = "fixtures/gradle-wrapper-7a9ce74c.jar"

fun requirePinnedWrapperFixture(file: java.io.File) {
    require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        "Pinned wrapper fixture must be a regular file"
    }
    require(file.length() == trackedWrapperFixtureBytes.toLong()) { "Pinned wrapper fixture size mismatch" }
    val bytes = file.inputStream().use { it.readNBytes(trackedWrapperFixtureBytes + 1) }
    require(bytes.size == trackedWrapperFixtureBytes) { "Pinned wrapper fixture bounded read mismatch" }
    val observed = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0')
    }
    require(observed == trackedWrapperFixtureSha256) { "Pinned wrapper fixture checksum mismatch" }
}

sourceSets.named("test") {
    // Exclude only the old owned duplicate from the default resource tree; do not delete it.
    resources.exclude(wrapperFixtureResource)
}

tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processTestResources") {
    duplicatesStrategy = DuplicatesStrategy.FAIL
    inputs.file(trackedWrapperFixture).withPropertyName("trackedWrapperFixture")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("trackedWrapperFixtureSha256", trackedWrapperFixtureSha256)
    inputs.property("trackedWrapperFixtureBytes", trackedWrapperFixtureBytes)
    inputs.property("wrapperFixtureResource", wrapperFixtureResource)
    from(trackedWrapperFixture) {
        into("fixtures")
        rename { "gradle-wrapper-7a9ce74c.jar" }
    }
    doFirst { requirePinnedWrapperFixture(trackedWrapperFixture.asFile) }
    // Check the actual copied resource as well, not only a separate pre-copy source read.
    doLast { requirePinnedWrapperFixture(destinationDir.resolve(wrapperFixtureResource)) }
}

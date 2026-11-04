dependencies {
    implementation(project(":contracts"))
    implementation(project(":snapshot"))
}

// Preview distributions contain ONLY the helper built for their host/architecture.
// An unavailable manifest on Windows keeps non-executing commands portable; it is
// not a claim of Job Objects support. Compilation is an explicit build operation,
// never something a review or provider installation performs in the background.
val supervisorResources = layout.buildDirectory.dir("generated/nativeResources")
val supervisorPython = providers.gradleProperty("nativeSupervisorPython")
    .orElse(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3")
val supervisorCc = providers.gradleProperty("nativeSupervisorCc").orElse("/usr/bin/cc")

val buildOwnedProcessSupervisor by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the host-specific owned-child helper, or record unsupported containment."
    workingDir(rootProject.projectDir)
    inputs.files("native/owned_process_supervisor.c", "native/build_supervisor.py")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("python", supervisorPython)
    inputs.property("cc", supervisorCc)
    inputs.property("os", System.getProperty("os.name"))
    inputs.property("arch", System.getProperty("os.arch"))
    outputs.dir(supervisorResources)
    // A compiler driver digest alone does not bind its linker, SDK or libraries.
    // Rebuild the small helper instead of claiming correct reuse across an opaque
    // host toolchain change. The helper emits a separate bounded build receipt.
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Native transitive build-toolchain identity is not yet qualified") { true }
    doFirst {
        commandLine(supervisorPython.get(), file("native/build_supervisor.py").absolutePath,
            "--output", supervisorResources.get().asFile.absolutePath, "--cc", supervisorCc.get())
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildOwnedProcessSupervisor)
    from(supervisorResources)
}

// Preview-2 is an additive live-transport foundation, not a default provider route.
// Its installed tree and protocol are separate from preview-1; unsupported hosts
// get their own unavailable manifest. No cross-version fallback is permitted.
val liveSupervisorResources = layout.buildDirectory.dir("generated/nativeLiveResources")

val buildOwnedLiveProcessSupervisor by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the Linux-only preview-2 live helper, or record explicit unavailability."
    workingDir(rootProject.projectDir)
    inputs.files("native/owned_live_process_supervisor.c", "native/build_live_supervisor.py")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("python", supervisorPython)
    inputs.property("cc", supervisorCc)
    inputs.property("os", System.getProperty("os.name"))
    inputs.property("arch", System.getProperty("os.arch"))
    outputs.dir(liveSupervisorResources)
    // A build-driver pin does not identify its complete transitive toolchain.
    // As with v1, require a fresh bounded build rather than claiming opaque reuse.
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Native transitive build-toolchain identity is not yet qualified") { true }
    doFirst {
        commandLine(supervisorPython.get(), file("native/build_live_supervisor.py").absolutePath,
            "--output", liveSupervisorResources.get().asFile.absolutePath, "--cc", supervisorCc.get())
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildOwnedLiveProcessSupervisor)
    from(liveSupervisorResources)
}

// Track raw environment values: no trimming, case or probe-path normalization.
// Only exact "1" matches the existing JUnit opt-in; all other values disable that
// integration test, not the ordinary suite. Disabled runs retain normal caching.
// A selected path does not bind external probe bytes or the native toolchain,
// so enabled integration must execute, never replay results.
val ownedLiveIntegrationOptIn = providers.environmentVariable("KTC_RUN_OWNED_LIVE_INTEGRATION").orElse("")
val ownedLiveProbePath = providers.environmentVariable("KTC_OWNED_LIVE_PROBE").orElse("")

tasks.named<Test>("test") {
    inputs.property("ownedLiveIntegrationOptIn", ownedLiveIntegrationOptIn)
    inputs.property("ownedLiveProbePath", ownedLiveProbePath)
    outputs.upToDateWhen { ownedLiveIntegrationOptIn.get() != "1" }
    outputs.doNotCacheIf("Enabled live integration uses unmodeled external probe/toolchain inputs") {
        ownedLiveIntegrationOptIn.get() == "1"
    }
}

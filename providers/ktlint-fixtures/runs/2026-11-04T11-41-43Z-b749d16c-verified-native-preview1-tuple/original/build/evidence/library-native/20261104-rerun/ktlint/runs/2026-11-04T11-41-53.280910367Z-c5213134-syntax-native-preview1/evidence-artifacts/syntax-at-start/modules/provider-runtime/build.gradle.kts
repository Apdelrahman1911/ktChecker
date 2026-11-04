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

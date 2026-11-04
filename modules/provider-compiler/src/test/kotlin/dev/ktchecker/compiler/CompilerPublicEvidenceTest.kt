package dev.ktchecker.compiler

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.fileDigest
import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlin.test.*

/** Public/private projection regression only: the tiny synthetic JDK/compiler are NEVER launched. */
class CompilerPublicEvidenceTest {
    @Test fun exactPublicIdentitiesAndLimitationsDoNotDisclosePrivatePathsOrAlterPrivateBindings() {
        val root = Files.createTempDirectory("ktc-public-toolchain-evidence-").toRealPath()
        try {
            val home = root.resolve("PRIVATE_COMPILER_JDK_HOME")
            val java = SyntheticJdk.create(home)
            val artifact = root.resolve("kotlin-compiler-embeddable-2.4.21.jar")
            Files.writeString(artifact, "non-executed synthetic compiler identity bytes")
            val toolchain = KotlinJvmToolchain(java,
                listOf(ArtifactIdentity("unit:never-executed", fileDigest(artifact), artifact.toString())))
            val sourceRoot = Files.createDirectory(root.resolve("PRIVATE_SOURCE_MATERIALIZATION"))
            val source = "fun example() = 1\n"
            Files.writeString(sourceRoot.resolve("example.kt"), source)
            val input = AnalysisInput(SnapshotManifest(Digests.sha256("public-evidence-unit"), "workspace", "worktree",
                listOf(SnapshotFile("example.kt", Digests.sha256(source), source.toByteArray().size.toLong()))),
                sourceRoot.toString(), AnalysisContext(compilerVersion = "2.4.21", compilationFiles = listOf("example.kt")))
            val privateProfile = KotlinJvmCompilerProvider(toolchain).executionProfile(input)

            val evidence = KotlinJvmCompilerProvider.publicToolchainEvidence(toolchain)
            assertEquals(Evidence("toolchain",
                "Pinned ordered isolated compiler artifacts and whole selected link-free JDK21 image " +
                    "(${toolchain.runtimeImage.digest}); disk identity, not loaded-code or OS/JNI attestation",
                digest = toolchain.digest), evidence)
            val publicBytes = ContractJson.encodeToString(evidence)
            assertTrue(toolchain.digest in publicBytes)
            assertTrue(toolchain.runtimeImage.digest in publicBytes)
            for (privatePath in listOf(root.toString(), home.toString(), java.toString(), artifact.toString(), sourceRoot.toString(),
                "PRIVATE_COMPILER_JDK_HOME", "PRIVATE_SOURCE_MATERIALIZATION")) {
                assertFalse(privatePath in publicBytes, "Public evidence must not include private location data")
            }
            assertNull(evidence.artifactRef)

            // Removing public text does not normalize/rewrite the actual owner-private selection.
            assertEquals(home.toString(), toolchain.runtimeImage.description().getValue("root"))
            assertEquals(java.toString(), privateProfile.executable)
            assertEquals(sourceRoot.toString(), input.materializedRoot)
            assertTrue("-Dktchecker.compiler.toolchain=${toolchain.digest}" in privateProfile.arguments)
            toolchain.verify()
        } finally { root.toFile().deleteRecursively() }
    }
}

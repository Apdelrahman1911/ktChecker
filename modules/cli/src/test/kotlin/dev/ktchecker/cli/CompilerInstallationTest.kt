package dev.ktchecker.cli

import dev.ktchecker.compiler.CompilerRuleRegistry
import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Synthetic installation/abstention controls, not compiler or JDK qualification. */
class CompilerInstallationTest {
    private fun directory() = Files.createTempDirectory("ktchecker-compiler-selection-").toRealPath()
    private fun artifacts(): Path = directory().also {
        Files.writeString(it.resolve("kotlin-compiler-embeddable-2.4.21.jar"), "synthetic-not-a-compiler")
        Files.writeString(it.resolve("kotlin-stdlib-2.4.21.jar"), "synthetic-not-a-library")
    }

    @Test fun missingArtifactsStayUnavailableWithoutInventingRuntimeOrRuleEvidence() {
        val selected = selectCompiler(null, directory())
        assertNull(selected.toolchain)
        assertEquals("compiler_artifacts_unavailable", selected.unavailableReason)
        assertTrue(selected.artifacts.isEmpty())
        val empty = selectCompiler(directory(), directory())
        assertEquals("compiler_artifacts_invalid", empty.unavailableReason)
    }

    @Test fun explicitMissingJdkCannotSilentlyFallBackToBrokerRuntime() {
        val selected = selectCompiler(artifacts(), directory().resolve("does-not-exist"))
        assertNull(selected.toolchain)
        assertTrue(selected.unavailableReason!!.startsWith("compiler_java_runtime_"), selected.unavailableReason)
        assertEquals(2, selected.artifacts.size, "Runtime failure must not silently discard selected dependency identities")
        assertTrue(selected.artifacts.all { Digests.valid(it.digest) })
    }

    @Test fun directoryClasspathHasStableExplicitOrderingAndNoExternalMutableList() {
        val root = artifacts()
        Files.writeString(root.resolve("z.jar"), "z")
        Files.writeString(root.resolve("a.jar"), "a")
        Files.writeString(root.resolve("README.txt"), "not on classpath")
        val first = installedCompilerArtifacts(root)
        assertEquals(listOf("a.jar", "kotlin-compiler-embeddable-2.4.21.jar", "kotlin-stdlib-2.4.21.jar", "z.jar"),
            first.map { Path.of(it.path).fileName.toString() })
        assertEquals(first, installedCompilerArtifacts(root))
        assertFailsWith<UnsupportedOperationException> { (first as MutableList<ArtifactIdentity>).clear() }
        Files.writeString(root.resolve("z.jar"), "changed")
        assertNotEquals(first.last().digest, installedCompilerArtifacts(root).last().digest)
    }

    @Test fun artifactEntriesAreBoundedAndDirectoriesMasqueradingAsJarsAreRejected() {
        val root = artifacts()
        Files.createDirectory(root.resolve("bad.jar"))
        assertEquals("compiler_artifacts_invalid", selectCompiler(root, directory()).unavailableReason)
        val crowded = directory()
        repeat(257) { Files.writeString(crowded.resolve("entry-$it.txt"), "") }
        assertFailsWith<IllegalArgumentException> { installedCompilerArtifacts(crowded) }
    }

    @Test fun unavailableProviderReportsEverySelectedRuleAndKeepsCancellationDistinct() {
        val provider = UnavailableCompilerProvider("compiler_java_runtime_unsupported")
        val context = RuleContext(target = "jvm", module = ":app", sourceSet = "main")
        val rules = provider.supportedRuleIds.sorted().take(2)
        val input = AnalysisInput(SnapshotManifest("s", "p", "t", emptyList()), "/not-executed",
            AnalysisContext(scope = context), rules)
        val result = provider.analyze(input, CancellationToken { false })
        assertEquals(CompilerRuleRegistry.PROVIDER_ID, result.providerId)
        assertEquals("unavailable", result.status)
        assertEquals(rules, result.coverage.map { it.ruleId })
        assertTrue(result.coverage.all { it.context == context && it.state == "unavailable" &&
            it.reason == "compiler_java_runtime_unsupported" })
        assertTrue(result.findings.isEmpty())
        assertFalse(result.complete())
        val cancelled = provider.analyze(input, CancellationToken { true })
        assertEquals("cancelled", cancelled.status)
        assertTrue(cancelled.coverage.all { it.state == "cancelled" })
        assertNotEquals(provider.version, UnavailableCompilerProvider("compiler_artifacts_unavailable").version)
    }

    @Test fun compilerHomeArgumentIsExplicitAndDuplicateSelectionsAreRejected() {
        assertEquals("owner-jdk", Arguments(arrayOf("providers", "--compiler-java-home", "owner-jdk"))["compiler-java-home"])
        assertFailsWith<CliProblem> { Arguments(arrayOf("--compiler-java-home", "one", "--compiler-java-home", "two")) }
        assertFailsWith<CliProblem> { Arguments(arrayOf("--compiler-java-home")) }
    }
}

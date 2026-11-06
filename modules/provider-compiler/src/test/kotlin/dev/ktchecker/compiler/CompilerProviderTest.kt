package dev.ktchecker.compiler

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.nio.file.Files
import java.nio.file.Path
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import kotlin.test.*

class CompilerProviderTest {
    @Test fun exactManifestIsNotTargetCoverage() {
        val registry = CompilerRuleRegistry.load()
        assertEquals(445, registry.mappings.size)
        assertEquals(402, registry.jvmRuleIds.size)
        assertFalse("ktc.compiler.native.throws_list_empty" in registry.jvmRuleIds)
        assertEquals(43, registry.mappings.count { !it.jvmApplicable })
    }

    @Test fun actualHeaderProducesReportedPointNotGuessedTokenRange() {
        val path = Path.of("/isolated/source.kt")
        val source = "val x: Int = \"text\"\n"
        val digest = Digests.sha256(source)
        val parsed = CompilerDiagnosticParser().parse(
            "info: kotlinc-jvm 2.4.21 (JRE 21)\n$path:1:14: error: [INITIALIZER_TYPE_MISMATCH] Wrong type\n$source             ^\n",
            mapOf(path to CompilerSourceIdentity("source.kt", digest, source)),
        )
        assertFalse(parsed.malformed)
        assertEquals("2.4.21", parsed.reportedVersion)
        val diagnostic = parsed.diagnostics.single()
        assertEquals("INITIALIZER_TYPE_MISMATCH", diagnostic.upstreamId)
        assertEquals(13, diagnostic.location!!.column)
        assertEquals(diagnostic.location.column, diagnostic.location.endColumn)
        assertEquals(digest, diagnostic.location.fileDigest)
    }

    @Test fun quotesUnknownPathsAndUnnamedErrorsNeverNormalize() {
        val parser = CompilerDiagnosticParser()
        assertTrue(parser.parse("a string quotes [UNRESOLVED_REFERENCE]", emptyMap()).diagnostics.isEmpty())
        val unknown = parser.parse("/outside.kt:1:1: error: [UNRESOLVED_REFERENCE] Unknown", emptyMap())
        assertTrue(unknown.malformed)
        assertEquals("UNRESOLVED_REFERENCE", unknown.rawDiagnostics.single().upstreamId)
        val unnamed = parser.parse("error: compiler failed without an internal ID", emptyMap())
        assertTrue(unnamed.malformed)
        assertTrue(unnamed.diagnostics.isEmpty())
        val unknownInfo = parser.parse("info: unknown compiler transport row", emptyMap())
        assertTrue(unknownInfo.malformed)
        assertEquals("unknown compiler transport row", unknownInfo.rawDiagnostics.single().message)
    }

    @Test fun fileUrisDecodeWithoutGuessingCoordinatesOrPhase() {
        val path = Path.of("/isolated/space name.kt")
        val text = "val label = \"😀\"; val broken = absent\n"
        val point = text.indexOf("absent") + 1
        val parsed = CompilerDiagnosticParser().parse("${path.toUri()}:1:$point: error: [UNRESOLVED_REFERENCE{BACKEND}] Missing",
            mapOf(path to CompilerSourceIdentity("space name.kt", Digests.sha256(text), text)))
        assertFalse(parsed.malformed)
        assertEquals(point - 1, parsed.diagnostics.single().location!!.column)
        assertEquals("BACKEND", parsed.diagnostics.single().renderedPhase)
        assertNull(CompilerDiagnosticParser().parse("$path:1:$point: error: [UNRESOLVED_REFERENCE] Missing",
            mapOf(path to CompilerSourceIdentity("space name.kt", Digests.sha256(text), text))).diagnostics.single().renderedPhase)
    }

    @Test fun emittedWarningSeverityIsNotReplacedWithSourceDeclaration() {
        val path = Path.of("/isolated/warning.kt")
        val identity = CompilerSourceIdentity("warning.kt", Digests.sha256("val x = 1 / 0"), "val x = 1 / 0")
        val parsed = CompilerDiagnosticParser().parse("$path:1:9: error: [DIVISION_BY_ZERO] Division by zero", mapOf(path to identity))
        assertEquals("error", parsed.diagnostics.single().severity)
        assertEquals("probable_defect", CompilerRuleRegistry.load().byUpstreamId.getValue("DIVISION_BY_ZERO").kind)
    }

    @Test fun dangerousAndUnknownSettingsCannotEscapeIsolation() {
        for (flag in listOf("@options.txt", "-d", "-Xplugin=/tmp/untrusted.jar", "-J-Xmx9g", "-classpath", "-script", "-Xbuild-file=x")) {
            assertFails { CompilerFlags.validate(listOf(flag)) }
        }
        assertEquals(listOf("-jvm-target", "17", "-Xwarning-level=DIVISION_BY_ZERO:error"),
            CompilerFlags.validate(listOf("-jvm-target", "17", "-Xwarning-level=DIVISION_BY_ZERO:error")))
    }

    @Test fun sourceIdentityAndTraversalCheckedBeforeCompilerExecution() {
        assertFails { CompilerSnapshot.relative("../outside.kt") }
        assertFails { CompilerSnapshot.relative("C:\\outside.kt") }
        val root = Files.createTempDirectory("ktc-sealed-unit-")
        try {
            val source = "fun ok() = 1\n"
            Files.writeString(root.resolve("source.kt"), source)
            val manifest = SnapshotManifest(Digests.sha256("snapshot"), "workspace", "worktree",
                listOf(SnapshotFile("source.kt", Digests.sha256(source), source.toByteArray().size.toLong())))
            val input = AnalysisInput(manifest, root.toString())
            assertEquals(source, CompilerSnapshot.read(input).single().text)
            Files.writeString(root.resolve("source.kt"), "fun changed() = 2\n")
            assertFails { CompilerSnapshot.read(input) }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun compilationSelectionIsDeclaredNotInferredFromMixedSnapshot() {
        val root = Files.createTempDirectory("ktc-compiler-selection-")
        try {
            val texts = mapOf("common/main.kt" to "fun common() = 1\n", "jvm/main.kt" to "fun jvm() = 2\n",
                "build.gradle.kts" to "error(\"never execute or compile project configuration\")\n")
            texts.forEach { (path, text) -> Files.createDirectories(root.resolve(path).parent); Files.writeString(root.resolve(path), text) }
            val files = texts.map { (path, text) -> SnapshotFile(path, Digests.sha256(text), text.toByteArray().size.toLong()) }
            val input = AnalysisInput(SnapshotManifest(Digests.sha256("mixed"), "workspace", "worktree", files), root.toString())
            assertFails { CompilerSnapshot.read(input) }
            val selected = input.copy(context = input.context.copy(compilationFiles = listOf("jvm/main.kt")))
            assertEquals(listOf("jvm/main.kt"), CompilerSnapshot.compilationPaths(selected))
            assertEquals("fun jvm() = 2\n", CompilerSnapshot.read(selected).single().text)
            assertFails { CompilerSnapshot.compilationPaths(input.copy(context = input.context.copy(compilationFiles = listOf("build.gradle.kts")))) }
            assertFails { CompilerSnapshot.compilationPaths(input.copy(context = input.context.copy(compilationFiles = listOf("absent.kt")))) }
            assertFails { CompilerSnapshot.compilationPaths(input.copy(context = input.context.copy(compilationFiles = listOf("jvm/main.kt", "jvm/main.kt")))) }
            assertNotEquals(input.analysisKey(), selected.analysisKey())
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun artifactByteBoundsAndCancellationPrecedeLargeReads() {
        val root = Files.createTempDirectory("ktc-compiler-artifacts-")
        try {
            val large = (0..2).map { index ->
                val path = root.resolve("$index.jar")
                RandomAccessFile(path.toFile(), "rw").use { it.setLength(200L * 1024 * 1024) }
                ArtifactIdentity("fake:$index", Digests.sha256("not read"), path.toString())
            }
            assertFailsWith<IllegalArgumentException> { CompilerSnapshot.classpath(large) }
            val path = root.resolve("small.jar")
            Files.writeString(path, "sealed bytes")
            val small = ArtifactIdentity("fixture:small", Digests.sha256("sealed bytes"), path.toString())
            assertFailsWith<CancellationException> { CompilerSnapshot.copyArtifact(small, root.resolve("copy.jar")) { throw CancellationException("test") } }
            assertFalse(Files.exists(root.resolve("copy.jar")))
            CompilerSnapshot.copyArtifact(small, root.resolve("verified.jar"))
            assertEquals("sealed bytes", Files.readString(root.resolve("verified.jar")))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun disabledAndUnenabledCheckersDoNotMeanCleanCoverage() {
        val registry = CompilerRuleRegistry.load()
        val division = registry.byUpstreamId.getValue("DIVISION_BY_ZERO")
        assertNotNull(CompilerFlags.activationGap(division, listOf("-nowarn")))
        assertNotNull(CompilerFlags.activationGap(division, listOf("-Xwarning-level=DIVISION_BY_ZERO:disabled")))
        assertNull(CompilerFlags.activationGap(division, listOf("-Xwarning-level=DIVISION_BY_ZERO:error")))
        assertEquals("compiler_experimental_checker_not_enabled",
            CompilerFlags.activationGap(registry.byUpstreamId.getValue("EMPTY_RANGE"), listOf("-Wextra")))
        assertNull(CompilerFlags.activationGap(registry.byUpstreamId.getValue("EMPTY_RANGE"), listOf("-Xuse-fir-experimental-checkers")))
        assertNotNull(CompilerFlags.activationGap(registry.byUpstreamId.getValue("UNREACHABLE_CODE"), emptyList()))
        assertNull(CompilerFlags.activationGap(registry.byUpstreamId.getValue("UNREACHABLE_CODE"), listOf("-Wextra")))
        assertNotNull(CompilerFlags.activationGap(registry.byUpstreamId.getValue("RETURN_VALUE_NOT_USED"), emptyList()))
        assertNull(CompilerFlags.activationGap(registry.byUpstreamId.getValue("RETURN_VALUE_NOT_USED"), listOf("-Xreturn-value-checker=check")))
    }

    @Test fun verificationIsAnExactContextFutureCheckNotAnInventedShellCommand() {
        val source = "fun broken() = absent()\n"
        val input = AnalysisInput(SnapshotManifest(Digests.sha256("baseline"), "workspace", "worktree",
            listOf(SnapshotFile("source.kt", Digests.sha256(source), source.toByteArray().size.toLong()))), "/sealed/baseline",
            AnalysisContext(compilerVersion = "2.4.21", compilerArguments = listOf("-jvm-target", "17", "-Wextra"),
                compilationFiles = listOf("source.kt")))
        val toolchainDigest = Digests.sha256("pinned test identity; no execution")
        val verification = CompilerVerification.recompile(input, "UNRESOLVED_REFERENCE", toolchainDigest,
            listOf("source.kt")).single()
        assertEquals("exact_context_recompile", verification.kind)
        assertEquals("not_run", verification.state)
        assertTrue(verification.command.isEmpty())
        for (required in listOf("new immutable snapshot", "new non-serializable execution permit", "actual exit-zero",
                "UNRESOLVED_REFERENCE", "suppressions", "disabled checkers", "failed/unexecuted", "-Wextra",
                toolchainDigest, input.analysisKey())) assertTrue(required in verification.expected, required)
        assertTrue("same owner-imported compilation membership" in verification.expected)
        val limited = input.copy(context = input.context.copy(compilationFiles = null))
        assertTrue("same limited sealed source batch" in CompilerVerification.recompile(limited,
            "UNRESOLVED_REFERENCE", toolchainDigest, listOf("source.kt")).single().expected)
    }

    @Test fun provenanceReadsAreBoundedAndRejectSymlinkInputs() {
        val root = CompilerFixtureProvenance.canonicalOuterDirectory(Files.createTempDirectory("ktc-provenance-unit-"))
        try {
            val path = root.resolve("original.kt")
            Files.writeString(path, "sealed")
            assertEquals("sealed", CompilerFixtureProvenance.readStable(path, 6).toString(Charsets.UTF_8))
            assertFails { CompilerFixtureProvenance.readStable(path, 5) }
            if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
                val link = root.resolve("symlink.kt")
                Files.createSymbolicLink(link, path.fileName)
                assertFails { CompilerFixtureProvenance.readStable(link, 6) }
                val nested = Files.createDirectory(root.resolve("nested"))
                Files.writeString(nested.resolve("original.kt"), "sealed")
                val linkedDirectory = Files.createSymbolicLink(root.resolve("linked-directory"), nested.fileName)
                assertFails { CompilerFixtureProvenance.readStable(linkedDirectory.resolve("original.kt"), 6) }
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun provenanceCanonicalizesOnlyDeclaredOuterRootAliases() {
        val root = Files.createTempDirectory("ktc-provenance-alias-unit-").toRealPath()
        try {
            if (!root.fileSystem.supportedFileAttributeViews().contains("posix")) return
            val outer = Files.createDirectory(root.resolve("actual-outer"))
            val evidence = Files.createDirectory(outer.resolve("evidence"))
            Files.writeString(evidence.resolve("original.kt"), "sealed")
            // Simulate macOS /var -> /private/var above the configured ordinary root.
            val alias = Files.createSymbolicLink(root.resolve("outer-alias"), outer.fileName)
            val suppliedRoot = alias.resolve("evidence")
            val canonicalRoot = CompilerFixtureProvenance.canonicalOuterDirectory(suppliedRoot)
            assertEquals(evidence, canonicalRoot)
            assertEquals("sealed", CompilerFixtureProvenance.readStable(canonicalRoot.resolve("original.kt"), 6).toString(Charsets.UTF_8))
            // The reader must not silently turn arbitrary descendant paths into real paths.
            assertFails { CompilerFixtureProvenance.readStable(suppliedRoot.resolve("original.kt"), 6) }
            assertFails { CompilerFixtureProvenance.canonicalOuterDirectory(alias) }
            val leaf = Files.createSymbolicLink(evidence.resolve("leaf.kt"), Path.of("original.kt"))
            assertFails { CompilerFixtureProvenance.readStable(leaf, 6) }
            val ordinaryDescendant = Files.createDirectory(evidence.resolve("ordinary-descendant"))
            Files.writeString(ordinaryDescendant.resolve("original.kt"), "sealed")
            val descendant = Files.createSymbolicLink(evidence.resolve("descendant"), ordinaryDescendant.fileName)
            assertFails { CompilerFixtureProvenance.readStable(descendant.resolve("original.kt"), 6) }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun transportApprovalAndChangedContextNeverGrantCompilerExecution() {
        val root = Files.createTempDirectory("ktc-compiler-authority-")
        try {
            // This deliberately non-executable fake jar is only an authority-denial
            // input. No fake compiler execution or rule evidence is claimed.
            val artifact = root.resolve("kotlin-compiler-embeddable-2.4.21.jar")
            Files.writeString(artifact, "not a compiler; must never execute")
            val installation = KotlinJvmToolchain(SyntheticJdk.create(root.resolve("synthetic-jdk")), listOf(
                ArtifactIdentity("unit:never-run", Digests.sha256(Files.readAllBytes(artifact)), artifact.toString())))
            val source = "fun ok() = 1\n"
            val snapshot = SnapshotManifest(Digests.sha256("authority-input"), "workspace", "worktree", listOf(
                SnapshotFile("source.kt", Digests.sha256(source), source.toByteArray().size.toLong())))
            val mutableMembers = mutableListOf("source.kt")
            val base = AnalysisInput(snapshot, root.toString(), AnalysisContext(compilerVersion = "2.4.21",
                compilationFiles = mutableMembers), listOf("ktc.compiler.common.unresolved_reference"),
                outputRoot = root.resolveSibling(root.fileName.toString() + "-outputs").toString())
            val denied = KotlinJvmCompilerProvider(installation)
            val prepared = base.copy(execution = denied.executionProfile(base))
            assertTrue(prepared.execution!!.approved)
            assertEquals("execution_not_authorized", denied.analyze(prepared).reasons.single())
            var verified = false
            val authority = ExactExecutionAuthority(ExecutionPermit(prepared.execution!!, snapshot.snapshotId, root,
                prepared.analysisKey(), { verified = true }))
            val provider = KotlinJvmCompilerProvider(installation, authority)
            val changed = listOf(
                prepared.copy(context = prepared.context.copy(libraries = mapOf("unknown" to "changed"))),
                prepared.copy(context = prepared.context.copy(compilerArguments = listOf("-nowarn"))),
                prepared.copy(requiredRuleIds = emptyList()),
                prepared.copy(snapshot = snapshot.copy(scopeExclusions = listOf("consumer"))),
                prepared.copy(outputRoot = root.resolveSibling("other-output").toString()),
            )
            changed.forEach { request -> assertEquals("execution_not_authorized", provider.analyze(request).reasons.single()) }
            mutableMembers += "undeclared.kt"
            assertEquals("execution_not_authorized", provider.analyze(prepared).reasons.single())
            assertFalse(verified)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun compilerInstallationOrderAndPathsBindProfilesWithoutImportedToolchainDigest() {
        val root = Files.createTempDirectory("ktc-compiler-order-unit-")
        try {
            // These are authority/profile inputs only, never executable compiler evidence.
            val names = listOf("kotlin-compiler-embeddable-2.4.21.jar", "first.jar", "second.jar")
            val artifacts = names.map { name ->
                val path = root.resolve(name)
                Files.writeString(path, "not a compiler: $name")
                ArtifactIdentity("unit:$name", Digests.sha256(Files.readAllBytes(path)), path.toString())
            }
            val java = SyntheticJdk.create(root.resolve("synthetic-jdk"))
            val ordered = KotlinJvmToolchain(java, artifacts)
            val reversed = KotlinJvmToolchain(java, listOf(artifacts[0], artifacts[2], artifacts[1]))
            assertNotEquals(ordered.digest, reversed.digest)
            val relocated = root.resolve("relocated/first.jar")
            Files.createDirectory(relocated.parent)
            Files.copy(Path.of(artifacts[1].path), relocated)
            val differentPath = KotlinJvmToolchain(java, listOf(artifacts[0], artifacts[1].copy(path = relocated.toString()), artifacts[2]))
            assertNotEquals(ordered.digest, differentPath.digest)
            val source = "fun ok() = 1\n"
            val base = AnalysisInput(SnapshotManifest(Digests.sha256("ordered-unit"), "workspace", "worktree",
                listOf(SnapshotFile("source.kt", Digests.sha256(source), source.toByteArray().size.toLong()))), root.toString(),
                AnalysisContext(compilerVersion = "2.4.21", compilationFiles = listOf("source.kt")),
                listOf("ktc.compiler.common.unresolved_reference"))
            assertNull(base.context.toolchainDigest)
            val before = KotlinJvmCompilerProvider(ordered).executionProfile(base)
            val after = KotlinJvmCompilerProvider(reversed).executionProfile(base)
            assertNotEquals(before, after)
            assertTrue("-Dktchecker.compiler.toolchain=${ordered.digest}" in before.arguments)
            val prepared = base.copy(execution = before)
            assertNotEquals(prepared.analysisKey(), base.copy(execution = after).analysisKey())
            var snapshotChecked = false
            val authority = ExactExecutionAuthority(ExecutionPermit(before, prepared.snapshot.snapshotId, root,
                prepared.analysisKey(), { snapshotChecked = true }))
            assertEquals("execution_profile_mismatch", KotlinJvmCompilerProvider(reversed, authority).analyze(prepared).reasons.single())
            assertFalse(snapshotChecked)
            val wrongDeclared = prepared.copy(context = prepared.context.copy(toolchainDigest = ordered.digest))
            assertEquals("compiler_toolchain_identity_mismatch", KotlinJvmCompilerProvider(reversed, authority).analyze(wrongDeclared).reasons.single())
            assertFalse(snapshotChecked)
        } finally { root.toFile().deleteRecursively() }
    }
}

package dev.ktchecker.compiler

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Identity tests use tiny synthetic layouts; no synthetic JVM/compiler is ever launched. */
class KotlinJvmRuntimeImageTest {
    @Test fun fullFramedInventoryIncludesConfigurationNativeFilesAndEmptyDirectories() = withRoot { root ->
        val java = SyntheticJdk.create(root.resolve("jdk"))
        Files.createDirectory(java.parent.parent.resolve("empty-directory"))
        val image = KotlinJvmRuntimeImage.capture(java)
        image.verify()
        assertEquals(java, image.javaExecutable)
        assertEquals(java.parent.parent, image.root)
        assertEquals(fileDigest(java), image.executableDigest)
        assertTrue(Digests.valid(image.digest))
        assertEquals(6, image.fileCount)
        val description = image.description()
        @Suppress("UNCHECKED_CAST")
        val entries = description.getValue("entries") as List<Map<String, Any?>>
        assertEquals(image.entryCount, entries.size)
        assertEquals(image.byteCount, entries.filter { it["kind"] == "file" }.sumOf { (it["size"] as Number).toLong() })
        assertEquals("directory", entries.single { it["path"] == "empty-directory" }["kind"])
        assertTrue(entries.any { it["path"] == "conf/security/java.security" && it["sha256"] != null })
        assertTrue(entries.any { it["path"].toString().endsWith("libjvm.so") ||
            it["path"].toString().endsWith("libjvm.dylib") || it["path"].toString().endsWith("jvm.dll") })
        val identity = buildJsonObject {
            put("schema", description.getValue("schema").toString())
            put("java_version", description.getValue("java_version").toString())
            put("os_name", description.getValue("os_name").toString())
            put("architecture", description.getValue("architecture").toString())
            put("entries", JsonArray(entries.map { entry -> buildJsonObject {
                put("path", entry["path"].toString()); put("kind", entry["kind"].toString())
                put("size", (entry["size"] as Number).toLong())
                put("sha256", entry["sha256"]?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                put("executable", entry["executable"] as Boolean)
                put("executable_bits", (entry["executable_bits"] as? Number)?.let(::JsonPrimitive) ?: JsonNull)
            } }))
        }
        assertEquals(image.digest, Digests.sha256(Digests.canonical(identity)))
    }

    @Test fun sameSizeNonLauncherWritesWithRestoredMtimeCannotReuseCapturedIdentity() = withRoot { root ->
        val java = SyntheticJdk.create(root.resolve("jdk"))
        val image = KotlinJvmRuntimeImage.capture(java)
        val modules = image.root.resolve("lib/modules")
        val previous = Files.readAllBytes(modules)
        val timestamp = Files.getLastModifiedTime(modules)
        Files.write(modules, previous.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
        Files.setLastModifiedTime(modules, timestamp)
        assertEquals(previous.size.toLong(), Files.size(modules))
        assertEquals(timestamp, Files.getLastModifiedTime(modules))
        assertCode("changed") { image.verify() }
        val changed = KotlinJvmRuntimeImage.capture(java)
        assertNotEquals(image.digest, changed.digest)
        assertEquals(image.executableDigest, changed.executableDigest)
    }

    @Test fun runtimeImageDigestIntrinsicallyBindsProfilesAndExactPermitsWithoutImportedDigest() = withRoot { root ->
        val java = SyntheticJdk.create(root.resolve("jdk"))
        val artifact = root.resolve("kotlin-compiler-embeddable-2.4.21.jar")
        Files.writeString(artifact, "non-executed compiler identity input")
        val classpath = listOf(ArtifactIdentity("unit:never-run", fileDigest(artifact), artifact.toString()))
        val before = KotlinJvmToolchain(java, classpath)
        val source = "fun ok() = 1\n"
        val input = AnalysisInput(SnapshotManifest(Digests.sha256("runtime-identity-test"), "workspace", "worktree",
            listOf(SnapshotFile("source.kt", Digests.sha256(source), source.toByteArray().size.toLong()))), root.toString(),
            AnalysisContext(compilerVersion = "2.4.21", compilationFiles = listOf("source.kt")),
            listOf("ktc.compiler.common.unresolved_reference"))
        val oldProfile = KotlinJvmCompilerProvider(before).executionProfile(input)
        val prepared = input.copy(execution = oldProfile)
        var snapshotVerified = false
        val permit = ExecutionPermit(oldProfile, prepared.snapshot.snapshotId, root, prepared.analysisKey(), { snapshotVerified = true })
        Files.writeString(before.runtimeImage.root.resolve("lib/jvm.cfg"), "changed non-executed VM configuration\n")
        val after = KotlinJvmToolchain(java, classpath)
        val newProfile = KotlinJvmCompilerProvider(after).executionProfile(input)
        assertNull(input.context.toolchainDigest)
        assertEquals(before.executableDigest, after.executableDigest)
        assertNotEquals(before.runtimeImage.digest, after.runtimeImage.digest)
        assertNotEquals(before.digest, after.digest)
        assertNotEquals(oldProfile, newProfile)
        assertNotEquals(prepared.analysisKey(), input.copy(execution = newProfile).analysisKey())
        assertEquals("execution_profile_mismatch", KotlinJvmCompilerProvider(after, ExactExecutionAuthority(permit))
            .analyze(prepared).reasons.single())
        assertFalse(snapshotVerified)
        assertEquals("compiler_java_runtime_changed", KotlinJvmCompilerProvider(before, ExactExecutionAuthority(permit))
            .analyze(prepared).reasons.single())
        assertTrue(snapshotVerified)
        // Both attempts stopped before materialization; no synthetic launcher ran.
        assertFalse(Files.exists(root.resolve("source.kt")))
    }

    @Test fun addedRenamedEntriesAndEmptyDirectoriesChangeContentIdentity() = withRoot { root ->
        val java = SyntheticJdk.create(root.resolve("jdk"))
        val baseline = KotlinJvmRuntimeImage.capture(java)
        val notes = baseline.root.resolve("notes.txt")
        Files.writeString(notes, "outside the minimum layout, still in the image\n")
        assertCode("changed") { baseline.verify() }
        val added = KotlinJvmRuntimeImage.capture(java)
        assertNotEquals(baseline.digest, added.digest)
        Files.move(notes, notes.resolveSibling("renamed-notes.txt"))
        assertCode("changed") { added.verify() }
        val renamed = KotlinJvmRuntimeImage.capture(java)
        assertNotEquals(added.digest, renamed.digest)
        Files.createDirectory(baseline.root.resolve("empty-directory"))
        assertCode("changed") { renamed.verify() }
        val empty = KotlinJvmRuntimeImage.capture(java)
        assertNotEquals(renamed.digest, empty.digest)
        assertEquals(renamed.byteCount, empty.byteCount)
        Files.delete(baseline.root.resolve("empty-directory"))
        assertCode("changed") { empty.verify() }
    }

    @Test fun executablePolicyIsIncludedEvenWhenBytesAndMtimeAreUnchanged() = withRoot { root ->
        assumeTrue(root.fileSystem.supportedFileAttributeViews().contains("posix"), "POSIX executable-bit behavior requires a POSIX host")
        val java = SyntheticJdk.create(root.resolve("jdk"))
        val baseline = KotlinJvmRuntimeImage.capture(java)
        val modules = baseline.root.resolve("lib/modules")
        val timestamp = Files.getLastModifiedTime(modules)
        val bytes = fileDigest(modules)
        val permissions = Files.getPosixFilePermissions(modules).toMutableSet()
        permissions += PosixFilePermission.GROUP_EXECUTE
        Files.setPosixFilePermissions(modules, permissions)
        assertEquals(bytes, fileDigest(modules))
        assertEquals(timestamp, Files.getLastModifiedTime(modules))
        assertCode("changed") { baseline.verify() }
        assertNotEquals(baseline.digest, KotlinJvmRuntimeImage.capture(java).digest)
    }

    @Test fun linkedHomeLeafIsRejectedRatherThanSilentlyCanonicalized() = withRoot { root ->
        assumeTrue(root.fileSystem.supportedFileAttributeViews().contains("posix"), "Symlink creation control requires a POSIX host")
        val java = SyntheticJdk.create(root.resolve("jdk"))
        val link = Files.createSymbolicLink(root.resolve("linked-home"), Path.of("jdk"))
        try { assertCode("unsupported") { KotlinJvmRuntimeImage.capture(link.resolve("bin").resolve(java.fileName)) } }
        finally { Files.deleteIfExists(link) }
    }

    @Test fun linkedFilesAndDescendantDirectoriesAreRejectedThroughoutTheWholeImage() = withRoot { root ->
        assumeTrue(root.fileSystem.supportedFileAttributeViews().contains("posix"), "Symlink descendant controls require a POSIX host")
        val java = SyntheticJdk.create(root.resolve("jdk"))
        val home = java.parent.parent
        val fileLink = Files.createSymbolicLink(home.resolve("optional-file-link"), Path.of("lib/modules"))
        try { assertCode("unsupported") { KotlinJvmRuntimeImage.capture(java) } }
        finally { Files.deleteIfExists(fileLink) }
        val directoryLink = Files.createSymbolicLink(home.resolve("optional-directory-link"), Path.of("conf"))
        try { assertCode("unsupported") { KotlinJvmRuntimeImage.capture(java) } }
        finally { Files.deleteIfExists(directoryLink) }
        val launcherBytes = Files.readAllBytes(java)
        Files.delete(java)
        val launcherLink = Files.createSymbolicLink(java, Path.of("../lib/modules"))
        try { assertCode("unsupported") { KotlinJvmRuntimeImage.capture(java) } }
        finally { Files.deleteIfExists(launcherLink); Files.write(java, launcherBytes) }
    }

    @Test fun onlyOuterAncestorAliasesAreAcceptedAndTheirTargetsStayPinned() = withRoot { root ->
        assumeTrue(root.fileSystem.supportedFileAttributeViews().contains("posix"), "Ancestor-alias retarget control requires a POSIX host")
        val java = SyntheticJdk.create(root.resolve("actual/jdk"))
        val alias = Files.createSymbolicLink(root.resolve("ancestor-alias"), Path.of("actual"))
        try {
            val selected = alias.resolve("jdk/bin").resolve(java.fileName)
            val image = KotlinJvmRuntimeImage.capture(selected)
            assertEquals(java, image.javaExecutable)
            assertEquals(java.parent.parent, image.root)
            image.verify()
            SyntheticJdk.create(root.resolve("other/jdk"))
            Files.delete(alias)
            Files.createSymbolicLink(alias, Path.of("other"))
            assertCode("changed") { image.verify() }
        } finally { Files.deleteIfExists(alias) }
    }

    @Test fun unsupportedReleaseMetadataAndIncompleteLayoutsFailClosed() = withRoot { root ->
        val invalid = listOf(
            "JAVA_VERSION=\"17.0.1\"\nOS_NAME=\"Linux\"\nOS_ARCH=\"x86_64\"\n",
            "JAVA_VERSION=\"22\"\nOS_NAME=\"Linux\"\nOS_ARCH=\"x86_64\"\n",
            "JAVA_VERSION=\"21\"\nJAVA_VERSION=\"21\"\nOS_NAME=\"Linux\"\nOS_ARCH=\"x86_64\"\n",
            "JAVA_VERSION=\"21\"\nOS_NAME=\"Unknown\"\nOS_ARCH=\"x86_64\"\n",
            "JAVA_VERSION=\"21\"\nOS_NAME=\"Linux\"\nOS_ARCH=\"unknown\"\n",
            "JAVA_VERSION=\"21\"\nOS_NAME=\"Linux\"\n",
        )
        invalid.forEachIndexed { index, release ->
            val java = SyntheticJdk.create(root.resolve("invalid-$index"))
            Files.writeString(java.parent.parent.resolve("release"), release)
            assertCode("unsupported") { KotlinJvmRuntimeImage.capture(java) }
        }
        val malformed = SyntheticJdk.create(root.resolve("malformed"))
        Files.write(malformed.parent.parent.resolve("release"), byteArrayOf(0xc3.toByte(), 0x28))
        assertCode("unsupported") { KotlinJvmRuntimeImage.capture(malformed) }
        val incomplete = SyntheticJdk.create(root.resolve("incomplete"))
        Files.delete(incomplete.parent.parent.resolve("lib/modules"))
        assertCode("unreadable") { KotlinJvmRuntimeImage.capture(incomplete) }
        val missing = SyntheticJdk.create(root.resolve("missing-release"))
        Files.delete(missing.parent.parent.resolve("release"))
        assertCode("unreadable") { KotlinJvmRuntimeImage.capture(missing) }
        val empty = SyntheticJdk.create(root.resolve("empty-native"))
        val vm = when (SyntheticJdk.hostOsName()) {
            "Linux" -> "lib/server/libjvm.so"
            "Darwin" -> "lib/server/libjvm.dylib"
            else -> "bin/server/jvm.dll"
        }
        Files.write(empty.parent.parent.resolve(vm), byteArrayOf())
        assertCode("unsupported") { KotlinJvmRuntimeImage.capture(empty) }
    }

    @Test fun entryFileTotalAndReleaseByteBoundsAreEnforcedBeforeOversizedReads() = withRoot { root ->
        val java = SyntheticJdk.create(root.resolve("jdk"))
        val image = KotlinJvmRuntimeImage.capture(java)
        assertCode("limit") { KotlinJvmRuntimeImage.capture(java, KotlinJvmRuntimeLimits(maxEntries = image.entryCount - 1)) }
        assertCode("limit") { KotlinJvmRuntimeImage.capture(java, KotlinJvmRuntimeLimits(maxTotalBytes = image.byteCount - 1)) }
        val sizeLimit = Files.size(image.root.resolve("release"))
        RandomAccessFile(image.root.resolve("oversized-file").toFile(), "rw").use { it.setLength(sizeLimit + 1) }
        assertCode("limit") { KotlinJvmRuntimeImage.capture(java, KotlinJvmRuntimeLimits(maxFileBytes = sizeLimit)) }
        Files.delete(image.root.resolve("oversized-file"))
        RandomAccessFile(image.root.resolve("release").toFile(), "rw").use { it.setLength(65_537) }
        assertCode("limit") { KotlinJvmRuntimeImage.capture(java) }
    }

    @Test fun depthUtf8PathAndTotalNameByteBoundsAreEnforced() = withRoot { root ->
        val java = SyntheticJdk.create(root.resolve("jdk"))
        assertCode("limit") { KotlinJvmRuntimeImage.capture(java, KotlinJvmRuntimeLimits(maxDepth = 2)) }
        assertCode("limit") { KotlinJvmRuntimeImage.capture(java, KotlinJvmRuntimeLimits(maxTotalNameBytes = 1)) }
        Files.writeString(java.parent.parent.resolve("é".repeat(40)), "bounded UTF-8 path test")
        // Forty characters fit the character count but exceed the sixty-four-byte bound.
        assertCode("limit") { KotlinJvmRuntimeImage.capture(java, KotlinJvmRuntimeLimits(maxNameBytes = 64)) }
    }

    @Test fun scanCancellationDeadlinesAndStreamingCheckpointsDoNotUseACache() = withRoot { root ->
        val java = SyntheticJdk.create(root.resolve("jdk"))
        val payload = java.parent.parent.resolve("optional-payload")
        Files.write(payload, ByteArray(65_536))
        var shortChecks = 0
        KotlinJvmRuntimeImage.capture(java) { shortChecks++ }
        Files.write(payload, ByteArray(1_048_576))
        var longChecks = 0
        val image = KotlinJvmRuntimeImage.capture(java) { longChecks++ }
        assertTrue(longChecks >= shortChecks + 15, "Streaming reads must expose per-buffer cancellation checkpoints")
        var verificationChecks = 0
        image.verify { verificationChecks++ }
        assertTrue(verificationChecks >= longChecks - 1, "Reverification must rescan, not trust mtimes or a prior cache")
        assertFailsWith<CancellationException> { KotlinJvmRuntimeImage.capture(java) { throw CancellationException("test") } }
        var checks = 0
        assertFailsWith<CancellationException> { image.verify { if (++checks == 12) throw CancellationException("scan test") } }
        assertEquals(12, checks)
        val deadline = ExecutionProblem("test_deadline", "cooperative caller deadline")
        assertSame(deadline, assertFailsWith<ExecutionProblem> { image.verify { throw deadline } })
        assertCode("limit") { KotlinJvmRuntimeImage.capture(java, KotlinJvmRuntimeLimits(maxScanMillis = 1)) { Thread.sleep(5) } }
    }

    @Test fun selectionAndLimitParametersAreExplicitAndBounded() = withRoot { root ->
        assertCode("unsupported") { KotlinJvmRuntimeImage.capture(Path.of("relative/bin/java")) }
        val java = SyntheticJdk.create(root.resolve("jdk"))
        assertCode("unsupported") { KotlinJvmRuntimeImage.capture(java.parent.parent.resolve("./bin").resolve(java.fileName)) }
        if (root.fileSystem.supportedFileAttributeViews().contains("posix")) {
            SyntheticJdk.create(root.resolve("other/jdk"))
            Files.createDirectory(root.resolve("other/middle"))
            val alias = Files.createSymbolicLink(root.resolve("traversal-alias"), Path.of("other/middle"))
            try {
                // OS resolution reaches other/jdk; lexical normalization would
                // silently substitute root/jdk. Neither traversal is authorized.
                assertCode("unsupported") { KotlinJvmRuntimeImage.capture(alias.resolve("../jdk/bin").resolve(java.fileName)) }
            } finally { Files.deleteIfExists(alias) }
        }
        assertCode("unsupported") { KotlinJvmRuntimeImage.capture(java.parent.parent.resolve("java")) }
        assertCode("unreadable") { KotlinJvmRuntimeImage.capture(root.resolve("missing/bin/java")) }
        val alternate = java.resolveSibling(if (java.fileName.toString() == "java") "java.exe" else "java")
        Files.copy(java, alternate)
        assertCode("unsupported") { KotlinJvmRuntimeImage.capture(alternate) }
        assertFailsWith<IllegalArgumentException> { KotlinJvmRuntimeLimits(maxEntries = 0) }
        assertFailsWith<IllegalArgumentException> { KotlinJvmRuntimeLimits(maxFileBytes = 0) }
        assertFailsWith<IllegalArgumentException> { KotlinJvmRuntimeLimits(maxScanMillis = 60_001) }
    }

    @Test fun windowsJunctionHomeLeavesAndDescendantsAreRejectedWhenOnWindows() = withRoot { root ->
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "Native Windows junction control is unverified on other hosts")
        val java = SyntheticJdk.create(root.resolve("jdk"))
        val homeLink = root.resolve("junction-home")
        val descendant = java.parent.parent.resolve("junction-descendant")
        try {
            createWindowsJunction(homeLink, java.parent.parent)
            assertCode("unsupported") { KotlinJvmRuntimeImage.capture(homeLink.resolve("bin").resolve(java.fileName)) }
            createWindowsJunction(descendant, java.parent.parent.resolve("conf"))
            assertCode("unsupported") { KotlinJvmRuntimeImage.capture(java) }
        } finally {
            Files.deleteIfExists(descendant)
            Files.deleteIfExists(homeLink)
        }
    }

    private fun createWindowsJunction(link: Path, target: Path) {
        val systemRoot = System.getenv("SystemRoot") ?: error("Windows SystemRoot is unavailable for the junction test")
        val cmd = Path.of(systemRoot, "System32", "cmd.exe").toRealPath()
        // Only a fixed system directory-creation primitive and test-owned paths.
        val process = ProcessBuilder(cmd.toString(), "/d", "/c", "mklink", "/J", "\"$link\"", "\"$target\"")
            .redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Junction creation exceeded its bounded test deadline")
            assertEquals(0, process.exitValue(), "System junction primitive did not create the test-owned junction")
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.inputStream.close(); process.errorStream.close(); process.outputStream.close()
        }
    }

    private fun assertCode(suffix: String, action: () -> Unit) {
        assertEquals("compiler_java_runtime_$suffix", assertFailsWith<KotlinJvmRuntimeException> { action() }.code)
    }

    private inline fun withRoot(action: (Path) -> Unit) {
        val root = Files.createTempDirectory("ktc-runtime-image-unit-").toRealPath()
        try { action(root) } finally { root.toFile().deleteRecursively() }
    }
}

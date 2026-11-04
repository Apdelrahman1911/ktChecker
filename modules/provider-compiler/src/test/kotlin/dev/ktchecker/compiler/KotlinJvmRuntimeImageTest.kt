package dev.ktchecker.compiler

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Identity tests use tiny synthetic layouts; no synthetic JVM/compiler is ever launched. */
class KotlinJvmRuntimeImageTest {
    @Test fun lowercaseHexFormatMatchesThePreviousFormatterForEveryByteValue() {
        val values = ByteArray(256) { it.toByte() }
        assertEquals(values.joinToString("") { "%02x".format(it) }, HexFormat.of().formatHex(values))
        values.forEach { value -> assertEquals("%02x".format(value), HexFormat.of().formatHex(byteArrayOf(value))) }
        assertEquals("007f80ff", HexFormat.of().formatHex(byteArrayOf(0, 127, -128, -1)))
        assertEquals("", HexFormat.of().formatHex(byteArrayOf()))
    }

    @Test fun bufferBoundaryFilesAndWholeImageMatchIndependentReadAllDigests() = withRoot { root ->
        val java = SyntheticJdk.create(root.resolve("jdk"))
        val home = java.parent.parent
        Files.createDirectory(home.resolve("payloads"))
        Files.createDirectory(home.resolve("empty-directory"))
        val sizes = listOf(0, 1, 65_535, 65_536, 65_537, 131_072, 131_073)
        val authored = sizes.mapIndexed { index, size ->
            "payloads/$index-$size.bin" to ByteArray(size) { offset -> (offset * 37 + index * 19 + 127).toByte() }
        }.toMap()
        authored.forEach { (relative, bytes) -> Files.write(home.resolve(relative), bytes) }
        val expected = independentInventory(home)
        val image = KotlinJvmRuntimeImage.capture(java)
        assertEquals(expected, image.view().entries)
        assertEquals(independentImageDigest(expected), image.digest)
        assertEquals(expected.filter { it.kind == JvmRuntimeEntryKind.FILE }.sumOf { it.size }, image.byteCount)
        authored.forEach { (relative, bytes) ->
            assertEquals(independentDigest(bytes), image.view().entries.single { it.relativePath == relative }.digest)
        }
        image.verify()
        assertEquals(image.digest, KotlinJvmRuntimeImage.capture(java).digest)
    }

    @Test fun separateScansDoNotRetainOtherFilesOrOtherImagesScratchBytes() = withRoot { root ->
        val firstJava = SyntheticJdk.create(root.resolve("first-jdk"))
        val secondJava = SyntheticJdk.create(root.resolve("second-jdk"))
        for ((index, java) in listOf(firstJava, secondJava).withIndex()) {
            val home = java.parent.parent
            Files.write(home.resolve("large.bin"), ByteArray(65_537 + index * 65_536) { (it * 13 + index * 67).toByte() })
            Files.write(home.resolve("tiny.bin"), ByteArray(index + 1) { (0xff - index).toByte() })
            Files.write(home.resolve("empty.bin"), byteArrayOf())
        }
        val firstExpected = independentInventory(firstJava.parent.parent)
        val secondExpected = independentInventory(secondJava.parent.parent)
        val first = KotlinJvmRuntimeImage.capture(firstJava)
        val second = KotlinJvmRuntimeImage.capture(secondJava)
        assertEquals(firstExpected, first.view().entries)
        assertEquals(secondExpected, second.view().entries)
        assertEquals(independentImageDigest(firstExpected), first.digest)
        assertEquals(independentImageDigest(secondExpected), second.digest)
        assertNotEquals(first.digest, second.digest)
        first.verify()
        second.verify()
        assertEquals(first.digest, KotlinJvmRuntimeImage.capture(firstJava).digest)
    }

    @Test fun sameSizeRestoredMtimeMutationsAtAndBeyondBufferEdgesAreStillDetected() = withRoot { root ->
        val java = SyntheticJdk.create(root.resolve("jdk"))
        val payload = java.parent.parent.resolve("payload.bin")
        val original = ByteArray(131_073) { (it * 29 + 17).toByte() }
        Files.write(payload, original)
        val timestamp = Files.getLastModifiedTime(payload)
        val image = KotlinJvmRuntimeImage.capture(java)
        for (offset in listOf(65_535, 65_536, 131_072)) {
            val changed = original.copyOf().also { it[offset] = (it[offset].toInt() xor 0x5a).toByte() }
            Files.write(payload, changed)
            Files.setLastModifiedTime(payload, timestamp)
            assertEquals(original.size.toLong(), Files.size(payload))
            assertEquals(timestamp, Files.getLastModifiedTime(payload))
            assertCode("changed") { image.verify() }
            val current = KotlinJvmRuntimeImage.capture(java)
            assertNotEquals(image.digest, current.digest)
            assertEquals(independentDigest(changed), current.view().entries.single { it.relativePath == "payload.bin" }.digest)
            Files.write(payload, original)
            Files.setLastModifiedTime(payload, timestamp)
            image.verify()
        }
    }

    @Test fun exactBufferSizedReleaseAndFilesKeepReleaseFileAndTotalByteBounds() = withRoot { root ->
        val java = SyntheticJdk.create(root.resolve("jdk"))
        val release = java.parent.parent.resolve("release")
        val descriptor = Files.readString(release)
        // ASCII padding is an ignored descriptor line, not a changed version/layout field.
        Files.writeString(release, descriptor.padEnd(65_536, '#'))
        val payload = java.parent.parent.resolve("payload.bin")
        Files.write(payload, ByteArray(65_536) { (it * 31).toByte() })
        val limits = KotlinJvmRuntimeLimits(maxFileBytes = 65_536)
        val exact = KotlinJvmRuntimeImage.capture(java, limits)
        assertEquals(independentImageDigest(independentInventory(exact.root)), exact.digest)
        exact.verify()
        assertEquals(exact.digest, KotlinJvmRuntimeImage.capture(java, limits.copy(maxTotalBytes = exact.byteCount)).digest)
        assertCode("limit") { KotlinJvmRuntimeImage.capture(java, limits.copy(maxTotalBytes = exact.byteCount - 1)) }
        Files.write(payload, ByteArray(65_537))
        assertCode("limit") { KotlinJvmRuntimeImage.capture(java, limits) }
        Files.write(payload, byteArrayOf())
        Files.writeString(release, descriptor.padEnd(65_537, '#'))
        assertCode("limit") { KotlinJvmRuntimeImage.capture(java) }
    }

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

    /** Tiny authored files only; no scanner read helper, shared scratch buffer, or HexFormat. */
    private fun independentDigest(bytes: ByteArray): String = "sha256:" +
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun independentInventory(home: Path): List<JvmRuntimeEntryView> = Files.walk(home).use { paths ->
        paths.map { path ->
            val relative = home.relativize(path).toString().replace(java.io.File.separatorChar, '/')
            val directory = Files.isDirectory(path, NOFOLLOW_LINKS)
            val bytes = if (directory) null else Files.readAllBytes(path)
            val permissions = if (path.fileSystem.supportedFileAttributeViews().contains("posix"))
                Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) else null
            val executableBits = permissions?.let { set ->
                (if (PosixFilePermission.OWNER_EXECUTE in set) 4 else 0) +
                    (if (PosixFilePermission.GROUP_EXECUTE in set) 2 else 0) +
                    (if (PosixFilePermission.OTHERS_EXECUTE in set) 1 else 0)
            }
            JvmRuntimeEntryView(relative, if (directory) JvmRuntimeEntryKind.DIRECTORY else JvmRuntimeEntryKind.FILE,
                bytes?.size?.toLong() ?: 0L, bytes?.let(::independentDigest), Files.isExecutable(path), executableBits)
        }.toList().sortedBy { it.relativePath }
    }

    private fun independentImageDigest(entries: List<JvmRuntimeEntryView>): String = independentDigest(Digests.canonical(buildJsonObject {
        put("schema", "ktchecker/jdk-runtime-image-1")
        put("java_version", "21.0.9"); put("os_name", SyntheticJdk.hostOsName()); put("architecture", "x86_64")
        put("entries", JsonArray(entries.map { entry -> buildJsonObject {
            put("path", entry.relativePath); put("kind", if (entry.kind == JvmRuntimeEntryKind.FILE) "file" else "directory")
            put("size", entry.size); put("sha256", entry.digest?.let(::JsonPrimitive) ?: JsonNull)
            put("executable", entry.executable); put("executable_bits", entry.executableBits?.let(::JsonPrimitive) ?: JsonNull)
        } }))
    }).toByteArray(Charsets.UTF_8))

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

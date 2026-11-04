package dev.ktchecker.compiler

import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.*

/** Typed capture data tests only. The tiny text JDK/compiler fixtures are never executed. */
class KotlinJvmInstallationViewTest {
    @Test fun typedViewRetainsCompleteRuntimeAndOrderedCompilerData(): Unit = Installation().use { f ->
        val view = f.toolchain.installationView()
        assertEquals(f.toolchain.digest, view.toolchainDigest)
        assertEquals(f.toolchain.runtimeImage.digest, view.runtime.digest)
        assertEquals(f.toolchain.javaExecutable, view.runtime.javaExecutable)
        assertEquals(f.toolchain.executableDigest, view.runtime.executableDigest)
        assertEquals("21.0.9", view.runtime.javaVersion)
        assertEquals(f.toolchain.runtimeImage.entryCount, view.runtime.entries.size)
        assertEquals(f.toolchain.runtimeImage.fileCount, view.runtime.entries.count { it.kind == JvmRuntimeEntryKind.FILE })
        assertEquals(f.toolchain.runtimeImage.byteCount, view.runtime.entries.filter { it.kind == JvmRuntimeEntryKind.FILE }.sumOf { it.size })
        val empty = view.runtime.entries.single { it.relativePath == "empty-directory" }
        assertEquals(JvmRuntimeEntryKind.DIRECTORY, empty.kind)
        assertNull(empty.digest)
        assertEquals(listOf(0, 1), view.compilerClasspath.map { it.ordinal })
        assertEquals(f.artifacts, view.compilerClasspath.map { it.identity })
        assertEquals(f.artifacts.map { Files.size(Path.of(it.path)) }, view.compilerClasspath.map { it.size })
        f.artifacts.clear()
        assertEquals(2, view.compilerClasspath.size)
        assertEquals(2, f.toolchain.compilerClasspath.size)
        assertFailsWith<UnsupportedOperationException> { (view.runtime.entries as MutableList<JvmRuntimeEntryView>).clear() }
        assertFailsWith<UnsupportedOperationException> { (view.compilerClasspath as MutableList<InstalledCompilerJarView>).clear() }
        f.toolchain.verify()
    }

    @Test fun readingViewNeverRecapturesChangedRuntimeOrGrantsVerification(): Unit = Installation().use { f ->
        val before = f.toolchain.installationView()
        Files.createDirectory(f.home.resolve("unexpected-empty-directory"))
        val after = f.toolchain.installationView()
        assertEquals(before.runtime.entries, after.runtime.entries)
        assertEquals(before.runtime.digest, after.runtime.digest)
        assertEquals("compiler_java_runtime_changed", assertFailsWith<KotlinJvmRuntimeException> { f.toolchain.verify() }.code)
    }

    @Test fun completeInstallationStillBindsExecutableBitsBeyondModelFileProjection(): Unit = Installation().use { f ->
        assumeTrue("posix" in f.home.fileSystem.supportedFileAttributeViews(), "POSIX executable-bit case is inapplicable on this host")
        val entry = f.toolchain.installationView().runtime.entries.single { it.relativePath == "lib/modules" }
        assertNotNull(entry.executableBits)
        val path = f.home.resolve("lib/modules")
        val permissions = Files.getPosixFilePermissions(path).toMutableSet()
        permissions += PosixFilePermission.GROUP_EXECUTE
        Files.setPosixFilePermissions(path, permissions)
        assertEquals(entry.digest, Digests.sha256(Files.readAllBytes(path)))
        assertEquals("compiler_java_runtime_changed", assertFailsWith<KotlinJvmRuntimeException> { f.toolchain.verify() }.code)
    }

    @Test fun capturedCompilerSizesAndBytesRemainCheckedAfterTypedViewRead(): Unit = Installation().use { f ->
        val before = f.toolchain.installationView()
        val artifact = before.compilerClasspath.first()
        val path = Path.of(artifact.identity.path)
        val bytes = Files.readAllBytes(path)
        Files.write(path, bytes.map { (it.toInt() xor 1).toByte() }.toByteArray())
        assertEquals(artifact.size, Files.size(path))
        assertFailsWith<IllegalArgumentException> { f.toolchain.verify() }
        Files.write(path, bytes + byteArrayOf(1))
        assertEquals(artifact.size, f.toolchain.installationView().compilerClasspath.first().size)
        assertFailsWith<IllegalArgumentException> { f.toolchain.verify() }
    }

    private class Installation : AutoCloseable {
        val root: Path = Files.createTempDirectory("ktc-installation-view-unit-").toRealPath()
        val home: Path = root.resolve("jdk")
        private val java = SyntheticJdk.create(home)
        val artifacts = listOf("kotlin-compiler-embeddable-2.4.21.jar", "support.jar").map { name ->
            val path = root.resolve(name)
            Files.writeString(path, "non-executable unit compiler bytes: $name")
            ArtifactIdentity("installed:$name", Digests.sha256(Files.readAllBytes(path)), path.toString())
        }.toMutableList()
        val toolchain: KotlinJvmToolchain
        init { Files.createDirectory(home.resolve("empty-directory")); toolchain = KotlinJvmToolchain(java, artifacts) }
        override fun close() { root.toFile().deleteRecursively() }
    }
}

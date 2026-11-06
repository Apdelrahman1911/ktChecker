package dev.ktchecker.cli

import dev.ktchecker.compiler.CompilerCallObservation
import dev.ktchecker.compiler.CompilerObservationCallState
import dev.ktchecker.compiler.CompilerRuleRegistry
import dev.ktchecker.compiler.KotlinJvmCompilerProvider
import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.fileDigest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Owner-option/profile/preparation controls only; workerCount=0 and no submission/provider execution.
 * The synthetic compiler/JDK files are byte-layout inputs, never runnable tooling or qualification.
 * On/off comparisons are within one successor code/map image, not across historical mapping tokens.
 */
class WorkbenchCompilerObservationTest {
    @Test fun nullOptionKeepsTheOriginalProviderReferenceAndConcreteProfileGenerator() = withInstallation { fixture ->
        val options = fixture.options("null-owner")
        assertNull(options.compilerCallObservation)
        val provider = KotlinJvmCompilerProvider(assertNotNull(selectCompiler(fixture.compiler, fixture.javaHome).toolchain))
        val profile = provider.executionProfile(profileInput())
        val routed = ordinaryCompilerProviderWithObservation(provider, options.compilerCallObservation, digest('a'))
        assertSame(provider, routed)
        assertEquals(profile, provider.executionProfile(profileInput()))
        assertEquals(provider.id, routed.id)
        assertEquals(provider.version, routed.version)
        assertSame(provider.supportedRuleIds, routed.supportedRuleIds)
    }

    @Test fun enabledHelperDelegatesMetadataButDoesNotExposeOrReplaceProfileAuthority() = withInstallation { fixture ->
        val provider = KotlinJvmCompilerProvider(assertNotNull(selectCompiler(fixture.compiler, fixture.javaHome).toolchain))
        val profile = provider.executionProfile(profileInput())
        val observation = CompilerCallObservation()
        val routed = ordinaryCompilerProviderWithObservation(provider, observation, digest('a'))
        assertNotSame(provider, routed)
        assertFalse(routed is KotlinJvmCompilerProvider)
        assertEquals(provider.id, routed.id)
        assertEquals(provider.version, routed.version)
        assertSame(provider.supportedRuleIds, routed.supportedRuleIds)
        assertEquals(profile, provider.executionProfile(profileInput()), "The callback stays on the original compiler")
        val waiting = observation.snapshot()
        assertTrue(waiting.ownerAttached)
        assertEquals(digest('a'), waiting.ownerCodeDigest)
        assertEquals(CompilerObservationCallState.WAITING, waiting.state)
        assertEquals(0L, waiting.extraCallsAtRead)
        assertNull(waiting.call)
    }

    @Test fun ordinaryPreparationKeepsExactOnOffProfilesAndDoesNotInvokeTheObservedProvider() = withInstallation { fixture ->
        val disabled = Workbench(fixture.options("off-owner")).use { prepare(it) }
        val observation = CompilerCallObservation()
        Workbench(fixture.options("on-owner", observation)).use { work ->
            val enabled = prepare(work)
            assertEquals(disabled.profiles, enabled.profiles)
            assertEquals(setOf(CompilerRuleRegistry.PROVIDER_ID), enabled.profiles.keys)
            assertFalse(disabled.executionAuthorized)
            assertFalse(enabled.executionAuthorized, "An approved profile and an observation handle grant no execution")
            assertNull(enabled.input.execution)
            val profile = enabled.profiles.getValue(CompilerRuleRegistry.PROVIDER_ID)
            assertEquals(60_000L, profile.timeoutMillis)
            assertEquals(1_048_576, profile.maxOutputBytes)
            assertEquals(emptyMap(), profile.environment)
            assertEquals(fixture.java.toString(), profile.executable)
            assertEquals(fileDigest(fixture.java), profile.executableDigest)
            assertTrue(profile.approved)
            val map = CompilerRuleRegistry.load().mappingIdentity
            assertEquals(listOf("-Xmx768m", "-Dktchecker.compiler.toolchain=${enabled.input.context.toolchainDigest}",
                "-Dktchecker.compiler.mapping=${map.contractId}:${map.resourceDigest}", "-Dfile.encoding=UTF-8",
                "-Duser.language=en", "-Duser.country=US", "-Djava.io.tmpdir={output}/tmp", "-cp",
                listOf("{output}/compiler/0.jar", "{output}/compiler/1.jar").joinToString(File.pathSeparator),
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-version", "-Xrender-internal-diagnostic-names",
                "-no-stdlib", "-no-reflect", "-d", "{output}/classes", "{output}/sources/Main.kt"), profile.arguments)
            val snapshot = observation.snapshot()
            assertTrue(snapshot.ownerAttached)
            assertTrue(Digests.valid(assertNotNull(snapshot.ownerCodeDigest)))
            assertFalse(snapshot.ownerCodeDigestOmitted)
            assertEquals(CompilerObservationCallState.WAITING, snapshot.state)
            assertEquals(0L, snapshot.extraCallsAtRead)
            assertNull(snapshot.call)
        }
    }

    @Test fun missingOrdinaryInstallationLeavesTheObservationHandleUnattached() = withInstallation { fixture ->
        val observation = CompilerCallObservation()
        val options = fixture.options("missing-owner", observation).copy(compilerDirectory = fixture.root.resolve("missing"))
        Workbench(options).use { work ->
            val prepared = prepare(work)
            assertFalse(CompilerRuleRegistry.PROVIDER_ID in prepared.profiles)
            assertNull(prepared.input.context.toolchainDigest)
            val snapshot = observation.snapshot()
            assertFalse(snapshot.ownerAttached)
            assertEquals(CompilerObservationCallState.WAITING, snapshot.state)
            assertEquals(0L, snapshot.extraCallsAtRead)
            assertNull(snapshot.call)
        }
    }

    @Test fun modelOnlyInstallationDoesNotAttachOrdinaryObservationOrManufactureOrdinaryProfile() = withInstallation { fixture ->
        val observation = CompilerCallObservation()
        val modelInstallation = assertNotNull(selectCompiler(fixture.compiler, fixture.javaHome).toolchain)
        val options = fixture.options("model-owner", observation).copy(compilerDirectory = fixture.root.resolve("missing"),
            modelCompilerInstallation = modelInstallation)
        Workbench(options).use { work ->
            // This is ordinary preparation, NOT a live authorized model attempt or model execution.
            val prepared = prepare(work)
            assertFalse(CompilerRuleRegistry.PROVIDER_ID in prepared.profiles)
            assertFalse(observation.snapshot().ownerAttached)
            assertEquals(CompilerObservationCallState.WAITING, observation.snapshot().state)
            assertNull(observation.snapshot().call)
        }
    }

    @Test fun serializedPreparationCannotSelectAnOwnerObservationHandle() {
        val spec = PreparationSpec("client", digest('a'), "patch", "request", listOf("Main.kt"),
            requiredRuleIds = listOf(RULE), addKotlinStdlib = false)
        val encoded = ContractJson.encodeToJsonElement(PreparationSpec.serializer(), spec).jsonObject
        assertFalse("compilerCallObservation" in encoded)
        val forged = JsonObject(encoded + ("compilerCallObservation" to JsonPrimitive(true)))
        assertFailsWith<SerializationException> {
            ContractJson.decodeFromJsonElement(PreparationSpec.serializer(), forged)
        }
    }

    private fun prepare(work: Workbench): PreparedReview {
        work.register("project", "tree", "client", "session", "lineage")
        val committed = work.overlay(OverlayDocument("project", "tree", "client", "session", "patch",
            changes = listOf(OverlayChange("put", "Main.kt", content = "fun answer(): Int = 42\n"))))
        return work.prepare(PreparationSpec("client", committed.snapshotId, "patch", "request", listOf("Main.kt"),
            requiredRuleIds = listOf(RULE), addKotlinStdlib = false), false)
    }

    private fun profileInput(): AnalysisInput = AnalysisInput(
        SnapshotManifest(digest('a'), "unit", "unit", listOf(SnapshotFile("Main.kt", digest('b'), 1))),
        "/not-read-for-profile", AnalysisContext(compilerVersion = "2.4.21", compilationFiles = listOf("Main.kt")),
        requiredRuleIds = listOf(RULE))

    private data class Installation(val root: Path, val compiler: Path, val javaHome: Path, val java: Path) {
        fun options(owner: String, observation: CompilerCallObservation? = null): WorkbenchOptions = WorkbenchOptions(
            state = root.resolve(owner), compilerDirectory = compiler, compilerJavaHome = javaHome,
            workerCount = 0, compilerCallObservation = observation)
    }

    private fun withInstallation(action: (Installation) -> Unit) {
        val root = Files.createTempDirectory("ktc-workbench-observation-unit-").toRealPath()
        try {
            val compiler = Files.createDirectory(root.resolve("compiler"))
            Files.writeString(compiler.resolve("kotlin-compiler-embeddable-2.4.21.jar"), "synthetic, never executed compiler bytes")
            Files.writeString(compiler.resolve("kotlin-stdlib-2.4.21.jar"), "synthetic, never compiled library bytes")
            val home = Files.createDirectory(root.resolve("jdk"))
            val os = when {
                System.getProperty("os.name").startsWith("Windows") -> "Windows"
                System.getProperty("os.name").lowercase().let { "mac" in it || "darwin" in it } -> "Darwin"
                else -> "Linux"
            }
            val launcher = if (os == "Windows") "bin/java.exe" else "bin/java"
            val vm = when (os) {
                "Linux" -> "lib/server/libjvm.so"
                "Darwin" -> "lib/server/libjvm.dylib"
                else -> "bin/server/jvm.dll"
            }
            mapOf("release" to "JAVA_VERSION=\"21.0.9\"\nOS_NAME=\"$os\"\nOS_ARCH=\"x86_64\"\n",
                launcher to "NON-EXECUTED synthetic Java launcher input\n", "lib/modules" to "synthetic modules\n",
                "lib/jvm.cfg" to "synthetic VM selection\n", "conf/security/java.security" to "synthetic security configuration\n",
                vm to "synthetic VM library\n").forEach { (relative, text) ->
                val file = home.resolve(relative)
                Files.createDirectories(file.parent)
                Files.writeString(file, text)
                if (home.fileSystem.supportedFileAttributeViews().contains("posix")) {
                    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(
                        if (relative == launcher) "rwx------" else "rw-------"))
                }
            }
            val java = home.resolve(launcher)
            if (!home.fileSystem.supportedFileAttributeViews().contains("posix")) java.toFile().setExecutable(true, true)
            action(Installation(root, compiler, home, java))
        } finally { root.toFile().deleteRecursively() }
    }

    private fun digest(hex: Char): String = "sha256:" + hex.toString().repeat(64)

    companion object {
        private const val RULE = "ktc.compiler.common.abstract_member_not_implemented"
    }
}

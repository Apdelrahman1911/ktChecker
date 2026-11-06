package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.Digests
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Profile/resource controls only. These do not launch Gradle or compile project code. */
class Kgp2410ModelExporterTest {
    @Test fun oldResourceIsByteIdenticalAndTheNewResourceHasItsOwnDigestAndIdentity() {
        assertEquals("sha256:1ee54f787e1e960d020fdfec0ab7ed4c14129e7a8b029af508a18f65bae5447d", GradleModelExporter.scriptDigest())
        assertNotEquals(GradleModelExporter.scriptDigest(), Kgp2410ModelExporter.scriptDigest())
        assertNotEquals(GRADLE_MODEL_EXPORTER_ID, KGP_2410_MODEL_EXPORTER_ID)
        assertNotEquals(GradleModelExporter.SCRIPT, Kgp2410ModelExporter.SCRIPT)
        assertNotEquals(GradleModelExporter.REPORT, Kgp2410ModelExporter.REPORT)
        val original = Kgp2410ModelExporter.scriptBytes()
        val digest = Digests.sha256(original)
        original.fill(0)
        assertEquals(digest, Kgp2410ModelExporter.scriptDigest())
    }

    @Test fun newProfileIsExplicitOfflineSingleTaskWithoutAPrerequisiteParameter() {
        val profile = Kgp2410ModelExporter.profile(Path.of("/owner/gradle"), Digests.sha256("selected-launcher"),
            Path.of("/owner/jdk"), Path.of("/owner/gradle-home"))
        assertEquals(Kgp2410ModelExporter.TASK, profile.arguments.last())
        assertEquals(1, profile.arguments.count { it == Kgp2410ModelExporter.TASK })
        assertTrue(profile.arguments.containsAll(listOf("--offline", "--no-daemon", "--max-workers=1", "--no-configuration-cache")))
        assertEquals("{output}/" + Kgp2410ModelExporter.SCRIPT, profile.arguments[profile.arguments.indexOf("--init-script") + 1])
        assertTrue("-PktcheckerModelOutput={output}/" + Kgp2410ModelExporter.REPORT in profile.arguments)
        assertFalse(profile.arguments.any { it == "compileKotlin" || it == "ktCheckerModelExport" })
        assertEquals("/owner/jdk", profile.environment["JAVA_HOME"])
        assertEquals("/owner/gradle-home", profile.environment["GRADLE_USER_HOME"])
        assertEquals(180_000L, profile.timeoutMillis)
    }

    @Test fun outputPreparationWritesOnlyTheNewScriptAndNeverInventsAReport() {
        val output = Files.createTempDirectory("ktchecker-kgp2410-profile-only-")
        Kgp2410ModelExporter.prepareOutput(output)
        assertContentEquals(Kgp2410ModelExporter.scriptBytes(), Files.readAllBytes(output.resolve(Kgp2410ModelExporter.SCRIPT)))
        assertFalse(Files.exists(output.resolve(GradleModelExporter.SCRIPT)))
        assertFalse(Files.exists(output.resolve(Kgp2410ModelExporter.REPORT)))
        assertTrue(Files.isDirectory(output.resolve("home")))
    }

    @Test fun newResourceRetainsTotalCaptureAndExplicitUnsupportedIssuesWithoutLegacyRoleEmission() {
        val script = Kgp2410ModelExporter.scriptBytes().toString(Charsets.UTF_8)
        assertTrue("api.factory.invoke(null, api.types, false)" in script)
        assertTrue("['Primitive', 'PluginClasspath', 'DependencyClasspath', 'Sources']" in script)
        assertTrue("strings(api.serialize.invoke(null, value))" in script)
        assertTrue("verifyConfiguredJvmInputs(task, fullArguments, rootPath" in script)
        assertTrue("compilerVersion != '2.4.10'" in script)
        assertTrue("known(plugin, 'getPluginVersion') != '2.4.10'" in script)
        assertTrue("exporterId: '$KGP_2410_MODEL_EXPORTER_ID'" in script)
        assertTrue("!(pluginCapture.state in ['provider_absent', 'value_absent'])" in script)
        assertTrue("!(taskOptions instanceof List) || !taskOptions.isEmpty()" in script)
        assertTrue("kgp2410_produced_inputs_unsupported" in script)
        assertTrue("kgp2410_generated_inputs_unsupported" in script)
        assertTrue("kgp2410_source_domain_unsupported" in script)
        assertTrue("seen.size() + values.size() >= 20000" in script)
        assertFalse(JVM_ARGUMENT_ROLES_ADAPTER in script)
        assertFalse("static Map originalJvmArgumentRoles" in script)
        assertFalse("deferredJvmOutputRoles(" in script)
        assertFalse("getMethod('allOptions')" in script)
        assertFalse("getSerializedCompilerArguments" in script)
        assertFalse("contextClassLoader" in script)
    }
}

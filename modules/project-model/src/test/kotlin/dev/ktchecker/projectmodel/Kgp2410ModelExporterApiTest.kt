package dev.ktchecker.projectmodel

import groovy.lang.GroovyClassLoader
import groovy.lang.GroovyObject
import java.io.Closeable
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Path
import kotlin.test.*

/** Authored helper controls for the separately selected 2.4.10 resource, using public API fakes.
 * Running these tests is not a real KGP/Gradle export, compiler run, or configuration-read proof. */
class Kgp2410ModelExporterApiTest {
    @Test fun fullCaptureUsesEveryPinnedTypeAndStrictSignaturesWithoutRewritingArgv(): Unit = Harness().use { h ->
        val task = h.task()
        val original = listOf("-classpath", "/cp/z.jar:/cp/a.jar", "-Xplugin=/plugins/z.jar,/plugins/a.jar",
            "-Xfriend-paths=/friends/main", "-module-name", "fixture:lib", "/snapshot/Z.kt", "/snapshot/A.kt")
        task.setProperty("intended", original.toMutableList())
        val capture = h.call("fullCompilerArguments", task) as Map<*, *>
        assertEquals(original, capture["arguments"])
        assertEquals(listOf("Primitive", "PluginClasspath", "DependencyClasspath", "Sources"), task.getProperty("seenTypes"))
        assertEquals(false, task.getProperty("seenIgnoreErrors"))
        assertEquals(0, task.getProperty("partialReads"))
        assertEquals(1, task.getProperty("createCalls"))
        assertSame(task.getProperty("created"), capture["value"])
        assertSame(capture["value"], h.serializedValue(), "The exact created object, not a reconstructed one, is serialized")
        (task.getProperty("intended") as MutableList<*>).clear()
        assertEquals(original, capture["arguments"], "The exact vector is detached, not sorted or stripped")
    }

    @Test fun everyContributionFailureStaysNonlenientWithoutFallingBackToPartialOptions(): Unit = Harness().use { h ->
        for ((flag, kind) in listOf("failPrimitive" to "FixturePrimitiveFailure", "failPlugin" to "FixturePluginFailure",
            "failDependency" to "FixtureDependencyClasspathFailure", "failSources" to "FixtureSourcesFailure")) {
            val task = h.task().also { it.setProperty(flag, true) }
            val failure = h.failure("fullCompilerArguments", task)
            assertEquals("compiler-arguments-create", h.property(failure, "stage"))
            assertEquals(kind, h.property(failure, "failureKind"))
            assertEquals(0, task.getProperty("partialReads"))
        }
    }

    @Test fun failedSerializationNeverProducesAStringifiedOrTruncatedVector(): Unit = Harness().use { h ->
        for (invalid in listOf(listOf(42), listOf("bad\u0000argument"), listOf("x".repeat(8193)), List(16_385) { "" })) {
            val task = h.task().also { it.setProperty("intended", invalid) }
            assertEquals("compiler-arguments-serialize", h.property(h.failure("fullCompilerArguments", task), "stage"))
        }
    }

    @Test fun missingTaskLoaderApiNeverRetriesTheHelperOrContextClassloader(): Unit = Harness().use { h ->
        // This task lives in the test loader, while fake pinned APIs live only in h's child.
        // A helper-loader fallback could find them; the actual task-loader lookup must fail.
        val task = UnqualifiedTask()
        assertEquals("compiler-api-load", h.property(h.failure("fullCompilerArguments", task), "stage"))
        assertEquals(0, task.partialReads)
    }

    @Test fun changedArgumentDomainOrBoxedBooleanFactoryCannotMatchThePinnedApi(): Unit {
        for (h in listOf(Harness(argumentTypes = "Primitive, PluginClasspath, DependencyClasspath"),
            Harness(argumentTypes = "Primitive, DependencyClasspath, PluginClasspath, Sources"),
            Harness(argumentTypes = "Primitive, PluginClasspath, DependencyClasspath, Sources, Extra"), Harness(boxedFactory = true))) h.use {
            assertEquals("compiler-api-load", it.property(it.failure("fullCompilerArguments", it.task()), "stage"))
        }
    }

    @Test fun rootAndSelectedActualBuildPathsDistinguishTheSameProjectPathInAnIncludedBuild(): Unit = Harness().use { h ->
        val root = ProjectId(":", BuildId(":"))
        val current = ProjectId(":lib", BuildId(":"))
        val included = ProjectId(":lib", BuildId(":included"))
        assertEquals(current.getProjectPath(), included.getProjectPath())
        assertEquals(h.buildPath(root), h.buildPath(current))
        assertNotEquals(h.buildPath(root), h.buildPath(included))
    }

    @Test fun missingUnknownBlankAndWrongTypeBuildIdentityNeverDefaultsToRoot(): Unit = Harness().use { h ->
        val invalid = listOf(null, Any(), ProjectId(":lib", null), ProjectId(":lib", Any())) +
            listOf(null, "", " ", "\t", "\u0000", "x".repeat(4097), 42).map { ProjectId(":lib", BuildId(it)) }
        invalid.forEach { id ->
            assertIs<IllegalStateException>(assertFailsWith<InvocationTargetException> { h.buildPath(id) }.targetException)
        }
        assertEquals(":", h.buildPath(ProjectId(":", BuildId(":"))))
    }

    @Test fun nullablePluginProviderAndMissingValueRetainTheirDistinctUnknownStates(): Unit = Harness().use { h ->
        val providerAbsent = h.call("optionalPluginData", PluginTask(null)) as Map<*, *>
        assertEquals("provider_absent", providerAbsent["state"]); assertNull(providerAbsent["value"])
        val valueAbsent = h.call("optionalPluginData", PluginTask(PluginProvider(null))) as Map<*, *>
        assertEquals("value_absent", valueAbsent["state"]); assertNull(valueAbsent["value"])
        val observed = Any()
        val available = h.call("optionalPluginData", PluginTask(PluginProvider(observed))) as Map<*, *>
        assertEquals("available", available["state"]); assertSame(observed, available["value"])
        val failure = h.failure("optionalPluginData", PluginTask(PluginProvider(null, fail = true)))
        assertEquals("compiler-plugin-data-value", h.property(failure, "stage"))
        assertEquals("FixturePluginMetadataFailure", h.property(failure, "failureKind"))
    }

    @Test fun defaultScriptingFilesStayOrderedAndActualOperandMismatchRemainsAnError(): Unit = Harness().use { h ->
        val root = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()
        val z = root.resolve("default-z.jar").toFile(); val a = root.resolve("default-a.jar").toFile(); val extra = root.resolve("extra.jar").toFile()
        val task = PluginTask(null, PublicFiles(listOf(z, a)))
        assertEquals(listOf(z, a), h.call("effectivePluginFiles", task, null))
        val files = h.call("effectivePluginFiles", task, PluginData(PublicFiles(listOf(a, extra)))) as List<*>
        assertEquals(listOf(z, a, extra), files)
        h.call("samePaths", files.map { (it as File).absolutePath }, files)
        assertIs<IllegalStateException>(h.failure("samePaths", files.reversed().map { (it as File).absolutePath }, files))
    }

    @Test fun nullableArgumentArraysAreFactsNotMissingGetterOrWrongTypeRepair(): Unit = Harness().use { h ->
        assertEquals(emptyList<String>(), h.call("pathArray", null))
        assertEquals(listOf("z", "a"), h.call("pathArray", arrayOf("z", "a")))
        for (invalid in listOf(listOf("z"), arrayOf(42), arrayOf("bad\u0000path"))) {
            assertIs<IllegalStateException>(h.failure("pathArray", invalid))
        }
    }

    interface PublicBuildId { fun getBuildPath(): Any? }
    class BuildId(private val value: Any?) : PublicBuildId { override fun getBuildPath(): Any? = value }
    interface PublicProjectId { fun getBuild(): Any?; fun getProjectPath(): String }
    class ProjectId(private val path: String, private val build: Any?) : PublicProjectId {
        override fun getBuild(): Any? = build
        override fun getProjectPath(): String = path
    }
    class UnqualifiedTask {
        var partialReads = 0
        fun getSerializedCompilerArguments(): List<String> { partialReads++; return listOf("partial") }
    }
    class FixturePluginMetadataFailure : IllegalStateException()
    class PluginProvider(private val value: Any?, private val fail: Boolean = false) {
        fun getOrNull(): Any? { if (fail) throw FixturePluginMetadataFailure(); return value }
    }
    class PluginTask(private val provider: PluginProvider?, private val files: PublicFiles = PublicFiles(emptyList())) {
        fun `getKotlinPluginData$kotlin_gradle_plugin_common`(): PluginProvider? = provider
        fun getPluginClasspath(): PublicFiles = files
    }
    class PluginData(val classpath: PublicFiles)
    class PublicFiles(private val files: List<File>) : Iterable<File> {
        override fun iterator(): Iterator<File> = files.iterator()
        fun plus(other: PublicFiles): PublicFiles = PublicFiles((files + other.files).distinct())
    }

    private class Harness(argumentTypes: String = "Primitive, PluginClasspath, DependencyClasspath, Sources", boxedFactory: Boolean = false) : Closeable {
        private val loader = GroovyClassLoader(Kgp2410ModelExporterApiTest::class.java.classLoader)
        private val api: Class<*>
        init {
            val script = Kgp2410ModelExporter.scriptBytes().toString(Charsets.UTF_8)
            val helper = script.substringAfter("// BEGIN KTCHECKER_2410_PINNED_MODEL_API\n")
                .substringBefore("// END KTCHECKER_2410_PINNED_MODEL_API")
            check(helper.contains("class KtChecker2410PinnedModelApi"))
            loader.parseClass(helper, "Kgp2410PinnedModelApi.groovy")
            api = loader.loadClass("KtChecker2410PinnedModelApi")
            loader.parseClass("""
                package org.jetbrains.kotlin.cli.common.arguments
                class CommonToolArguments { List argv }
            """.trimIndent(), "CommonToolArguments.groovy")
            loader.parseClass("""
                package org.jetbrains.kotlin.gradle.plugin
                import org.jetbrains.kotlin.cli.common.arguments.CommonToolArguments
                interface KotlinCompilerArgumentsProducer {
                    enum ArgumentType { $argumentTypes }
                    interface CreateCompilerArgumentsContext { Set getArgumentTypes(); boolean getIgnoreErrors() }
                    CommonToolArguments createCompilerArguments(CreateCompilerArgumentsContext context)
                }
                class FixtureArgumentsContext implements KotlinCompilerArgumentsProducer.CreateCompilerArgumentsContext {
                    private final Set types
                    private final boolean ignore
                    FixtureArgumentsContext(Set types, boolean ignore) { this.types = types; this.ignore = ignore }
                    Set getArgumentTypes() { types }
                    boolean getIgnoreErrors() { ignore }
                }
                class KotlinCompilerArgumentsProducerKt {
                    static KotlinCompilerArgumentsProducer.CreateCompilerArgumentsContext CreateCompilerArgumentsContext(Set types, ${if (boxedFactory) "Boolean" else "boolean"} ignore) {
                        new FixtureArgumentsContext(types, ignore)
                    }
                }
            """.trimIndent(), "KotlinCompilerArgumentsProducer.groovy")
            loader.parseClass("""
                package org.jetbrains.kotlin.compilerRunner
                import org.jetbrains.kotlin.cli.common.arguments.CommonToolArguments
                class ArgumentUtils {
                    static CommonToolArguments lastSerialized
                    static List convertArgumentsToStringList(CommonToolArguments args) { lastSerialized = args; args.argv }
                }
            """.trimIndent(), "ArgumentUtils.groovy")
            loader.parseClass("""
                import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerArgumentsProducer
                import org.jetbrains.kotlin.cli.common.arguments.CommonToolArguments
                class FixturePrimitiveFailure extends IllegalStateException {}
                class FixturePluginFailure extends IllegalStateException {}
                class FixtureDependencyClasspathFailure extends IllegalStateException {}
                class FixtureSourcesFailure extends IllegalStateException {}
                class FixtureCompilerTask implements KotlinCompilerArgumentsProducer {
                    List intended = []
                    boolean failPrimitive = false
                    boolean failPlugin = false
                    boolean failDependency = false
                    boolean failSources = false
                    int partialReads = 0, createCalls = 0
                    CommonToolArguments created
                    List seenTypes
                    boolean seenIgnoreErrors
                    List getSerializedCompilerArguments() { partialReads++; ['partial'] }
                    List getSerializedCompilerArgumentsIgnoreClasspathIssues() { partialReads++; ['ignored-partial'] }
                    CommonToolArguments createCompilerArguments(KotlinCompilerArgumentsProducer.CreateCompilerArgumentsContext context) {
                        createCalls++
                        seenTypes = context.argumentTypes.collect { it.name() }
                        seenIgnoreErrors = context.ignoreErrors
                        if (failPrimitive) throw new FixturePrimitiveFailure()
                        if (failPlugin) throw new FixturePluginFailure()
                        if (failDependency) throw new FixtureDependencyClasspathFailure()
                        if (failSources) throw new FixtureSourcesFailure()
                        created = new CommonToolArguments(argv: intended)
                    }
                }
            """.trimIndent(), "FixtureCompilerTask.groovy")
        }
        fun serializedValue(): Any? = loader.loadClass("org.jetbrains.kotlin.compilerRunner.ArgumentUtils").getMethod("getLastSerialized").invoke(null)
        fun task(): GroovyObject = loader.loadClass("FixtureCompilerTask").getConstructor().newInstance() as GroovyObject
        fun call(name: String, vararg args: Any?): Any? {
            val types = when (name) {
                "samePaths" -> arrayOf(List::class.java, List::class.java)
                else -> Array(args.size) { Any::class.java }
            }
            return api.getMethod(name, *types).invoke(null, *args)
        }
        fun failure(name: String, vararg args: Any?): Throwable = assertFailsWith<InvocationTargetException> { call(name, *args) }.targetException
        fun property(instance: Throwable, name: String): Any? = (instance as GroovyObject).getProperty(name)
        fun buildPath(id: Any?): Any? = api.getMethod("projectBuildPath", Any::class.java, Class::class.java, Class::class.java)
            .invoke(null, id, PublicProjectId::class.java, PublicBuildId::class.java)
        override fun close() { loader.close() }
    }
}

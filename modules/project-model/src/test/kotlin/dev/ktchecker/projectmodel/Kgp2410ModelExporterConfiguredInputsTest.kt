package dev.ktchecker.projectmodel

import groovy.lang.GroovyClassLoader
import groovy.lang.GroovyObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.Closeable
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Source-authored controls for the exact new helper, with named public-signature fakes.
 * No real KGP/Gradle project, compiler, runtime image, owner closure or role authority is used. */
class Kgp2410ModelExporterConfiguredInputsTest {
    @Test fun ordinaryAndInheritedDecoratedProducersKeepTheTwoNullableStatesDistinct(): Unit = Harness().use { h ->
        for (name in listOf("KotlinCompile", "DecoratedKotlinCompile")) {
            val task = h.task(name)
            assertEquals("provider_absent", h.empty(task))
            task.setProperty("pluginData", h.value(null))
            assertEquals("value_absent", h.empty(task))
        }
    }

    @Test fun equalClassNamesDoNotPermitCustomProducerLoweringOrArgumentImplementations(): Unit = Harness().use { h ->
        for (name in listOf("CustomProducer", "CustomLowering")) {
            val task = h.task(name)
            val failure = h.failure("ordinaryJvmProducer", task, h.capture(task)) as GroovyObject
            assertEquals("compiler-jvm-original-producer", failure.getProperty("stage"))
        }
        val task = h.task()
        task.setProperty("arguments", h.instance("org.jetbrains.kotlin.cli.common.arguments.CustomArguments"))
        assertFailsWith<InvocationTargetException> { h.call("ordinaryJvmProducer", task, h.capture(task)) }
        assertFailsWith<InvocationTargetException> {
            h.call("ordinaryJvmProducer", Any(), mapOf("value" to Any(), "arguments" to emptyList<String>()))
        }
    }

    @Test fun metadataGetterErrorNeverBecomesAbsentOrEmpty(): Unit = Harness().use { h ->
        val task = h.task()
        task.setProperty("pluginData", h.value(null).also { it.setProperty("fail", true) })
        val failure = h.failure("optionalPluginData", task) as GroovyObject
        assertEquals("compiler-plugin-data-value", failure.getProperty("stage"))
        assertEquals("FixtureProviderFailure", failure.getProperty("failureKind"))
    }

    @Test fun nonnullApparentlyEmptyPluginDataIsAGapWithoutInspectingUnreviewedOptions(): Unit = Harness().use { h ->
        val task = h.task(); val data = h.pluginData()
        task.setProperty("pluginData", h.value(data))
        val observation = h.call("optionalPluginData", task) as Map<*, *>
        assertEquals("available", observation["state"])
        assertFailsWith<InvocationTargetException> { h.empty(task) }
        assertEquals(0, data.getProperty("optionReads"), "No unreviewed plugin-data option semantics are inferred")
    }

    @Test fun actualTaskPluginOptionsMustBeAnEmptyListNotEmptyLookingContainers(): Unit = Harness().use { h ->
        val task = h.task()
        task.setProperty("pluginClasspath", h.files(listOf(File("/default-scripting-plugin.jar"))))
        assertFailsWith<InvocationTargetException> { h.empty(task) }
        task.setProperty("pluginClasspath", h.files(emptyList()))
        val container = h.instance("org.jetbrains.kotlin.gradle.tasks.UnreviewedOptionContainer")
        for (value in listOf(null, emptySet<Any>(), listOf(container), listOf("plugin:option"))) {
            task.setProperty("pluginOptions", h.value(value))
            assertFailsWith<InvocationTargetException> { h.empty(task) }
        }
        assertEquals(0, container.getProperty("reads"), "Even empty-looking containers are rejected without allOptions()")
        task.setProperty("pluginOptions", h.value(emptyList<Any>()))
        assertEquals("provider_absent", h.empty(task))
    }

    @Test fun everyPluginArgumentArrayAndRawPluginOrResponseOperandRemainsAGap(): Unit = Harness().use { h ->
        for (field in listOf("pluginClasspaths", "pluginOptions", "pluginConfigurations", "pluginOrderConstraints")) {
            val task = h.task(); h.arguments(task).setProperty(field, arrayOf("nonempty"))
            assertFailsWith<InvocationTargetException> { h.empty(task) }
        }
        for (raw in listOf(listOf("@response"), listOf("-Xplugin=/plugin.jar"), listOf("-P", "plugin:option"),
            listOf("-P=plugin:option"), listOf("-Xcompiler-plugin=unexpected"), listOf("-Xintellij-plugin-root=/plugin"))) {
            val task = h.task(); h.arguments(task).setProperty("argv", raw)
            assertFailsWith<InvocationTargetException> { h.empty(task) }
        }
    }

    @Test fun scriptJavaRefinementAndAlternateFrontendInputsCannotBeDeclaredEmpty(): Unit = Harness().use { h ->
        for (field in listOf("javaSources", "scriptSources")) {
            val task = h.task().also { it.setProperty(field, h.files(listOf(File("/auxiliary.kt")))) }
            assertFailsWith<InvocationTargetException> { h.empty(task) }
        }
        for ((field, value) in listOf("useFirLT" to false, "script" to true, "repl" to true, "multiPlatform" to true,
            "javaSourceRoots" to arrayOf("/java"), "fragmentSources" to arrayOf("/common.kt"),
            "expression" to "expression()", "buildFile" to "/build.xml", "javaPackagePrefix" to "example")) {
            val task = h.task(); h.arguments(task).setProperty(field, value)
            assertFailsWith<InvocationTargetException> { h.empty(task) }
        }
        val task = h.task(); task.setProperty("multiPlatformEnabled", h.value(true))
        assertFailsWith<InvocationTargetException> { h.empty(task) }
    }

    @Test fun actualTaskFriendsAndArgumentFriendsAreUnsupportedSeparately(): Unit = Harness().use { h ->
        val task = h.task()
        task.setProperty("friendPaths", h.files(listOf(File("/main-output"))))
        assertFailsWith<InvocationTargetException> { h.empty(task) }
        task.setProperty("friendPaths", h.files(emptyList()))
        h.arguments(task).setProperty("friendPaths", arrayOf("/main-output"))
        assertFailsWith<InvocationTargetException> { h.empty(task) }
    }

    @Test fun configuredChecksPreserveDistinctSourceOrdersRawArgvAndNullableJdkWithoutReturningRoles(): Unit = Harness().use { h ->
        FilesFixture(h).use { f ->
            val capture = h.capture(f.task); val original = capture["arguments"]
            assertEquals(listOf("Z.kt", "A.kt"), f.sources.map { it.name })
            assertEquals(listOf("A.kt", "Z.kt"), f.sourceRecords.map { it.getValue("path") })
            assertTrue(f.verify(capture))
            assertSame(original, capture["arguments"], "Configuration checks must not rewrite or append compiler operands")
            assertNull(h.arguments(f.task).getProperty("jdkHome"), "A later runner default is not injected into the original object")
        }
    }

    @Test fun taskSourceOrderAndCompleteMembershipMustAgreeWithoutRepairOrSorting(): Unit = Harness().use { h ->
        FilesFixture(h).use { f ->
            h.arguments(f.task).setProperty("freeArgs", f.sources.reversed().map { it.absolutePath })
            assertFailsWith<InvocationTargetException> { f.verify() }
            h.arguments(f.task).setProperty("freeArgs", f.sources.map { it.absolutePath })
            assertFailsWith<InvocationTargetException> { f.verify(sourceRecords = f.sourceRecords.dropLast(1)) }
            assertFailsWith<InvocationTargetException> { f.verify(sourceRecords = f.sourceRecords + f.sourceRecords.first()) }
            assertFailsWith<InvocationTargetException> { f.verify(classpathRecords = f.classpathRecords.reversed()) }
            Files.delete(f.sources.first().toPath())
            assertFailsWith<InvocationTargetException> { f.verify() }
        }
    }

    @Test fun missingAndDirectoryClasspathEntriesCannotBeRepairedUsingKgpsExistsFilter(): Unit = Harness().use { h ->
        FilesFixture(h).use { f ->
            Files.delete(f.libraries.first().toPath())
            h.arguments(f.task).setProperty("classpath", f.libraries.drop(1).joinToString(":") { it.absolutePath })
            assertFailsWith<InvocationTargetException> { f.verify() }
            Files.createDirectory(f.libraries.first().toPath())
            h.arguments(f.task).setProperty("classpath", f.libraries.joinToString(":") { it.absolutePath })
            assertFailsWith<InvocationTargetException> { f.verify() }
        }
    }

    @Test fun originalDestinationAndOptionalJdkOperandMustMatchIndependentTaskSelection(): Unit = Harness().use { h ->
        FilesFixture(h).use { f ->
            val arguments = h.arguments(f.task)
            arguments.setProperty("jdkHome", f.jdk.toString())
            assertTrue(f.verify())
            arguments.setProperty("jdkHome", f.jdk.resolve("different").toString())
            assertFailsWith<InvocationTargetException> { f.verify() }
            arguments.setProperty("jdkHome", null)
            arguments.setProperty("destination", f.destination.toString() + "-different")
            assertFailsWith<InvocationTargetException> { f.verify() }
            arguments.setProperty("destination", f.destination.toString())
            assertFailsWith<InvocationTargetException> { f.verify(pluginDomain = "available_empty") }
        }
    }

    @Test fun linkedSourceClasspathAndSelectedJdkNeverGainNewCanonicalPathAuthority(): Unit = Harness().use { h ->
        FilesFixture(h).use { f ->
            val alias = Files.createSymbolicLink(f.sourceRoot.resolve("Alias.kt"), f.sources.first().toPath()).toFile()
            f.task.setProperty("sources", h.files(listOf(alias) + f.sources.drop(1)))
            h.arguments(f.task).setProperty("freeArgs", (listOf(alias) + f.sources.drop(1)).map { it.absolutePath })
            assertFailsWith<InvocationTargetException> {
                f.verify(sourceRecords = listOf(mapOf("path" to "Alias.kt"), mapOf("path" to "A.kt")))
            }
            f.task.setProperty("sources", h.files(f.sources))
            h.arguments(f.task).setProperty("freeArgs", f.sources.map { it.absolutePath })
            val linkedJar = Files.createSymbolicLink(f.jdk.parent.resolve("linked.jar"), f.libraries.first().toPath()).toFile()
            f.task.setProperty("libraries", h.files(listOf(linkedJar)))
            h.arguments(f.task).setProperty("classpath", linkedJar.absolutePath)
            assertFailsWith<InvocationTargetException> { f.verify(classpathRecords = listOf(mapOf("path" to linkedJar.absolutePath))) }
            f.task.setProperty("libraries", h.files(f.libraries))
            h.arguments(f.task).setProperty("classpath", f.libraries.joinToString(":") { it.absolutePath })
            val linkedJdk = Files.createSymbolicLink(f.jdk.parent.resolve("linked-jdk"), f.jdk)
            assertFailsWith<InvocationTargetException> { f.verify(selectedJdk = linkedJdk.toString()) }
        }
    }

    @Test fun absentAndEmptyClasspathAndSourceDataDoNotBecomeExecutionOrLayoutCapabilities(): Unit = Harness().use { h ->
        FilesFixture(h).use { f ->
            f.task.setProperty("libraries", h.files(emptyList()))
            for (operand in listOf(null, "")) {
                h.arguments(f.task).setProperty("classpath", operand)
                assertTrue(f.verify(classpathRecords = emptyList()))
                assertEquals(operand, h.arguments(f.task).getProperty("classpath"))
            }
            f.task.setProperty("sources", h.files(emptyList())); h.arguments(f.task).setProperty("freeArgs", emptyList<String>())
            assertTrue(f.verify(sourceRecords = emptyList(), classpathRecords = emptyList()))
        }
    }

    private class FilesFixture(private val h: Harness) : Closeable {
        private val root = Files.createTempDirectory("ktchecker-kgp2410-configured-api-").toRealPath()
        val sourceRoot: Path = Files.createDirectory(root.resolve("sources"))
        val jdk: Path = Files.createDirectory(root.resolve("jdk"))
        val destination: Path = root.resolve("output/classes")
        val sources: List<File> = listOf("Z.kt", "A.kt").map {
            Files.writeString(sourceRoot.resolve(it), "fun ${it.first().lowercase()}() = 1\n").toFile()
        }
        val libraries: List<File> = listOf("z.jar", "a.jar").map { Files.writeString(root.resolve(it), "fixture bytes, not a JAR").toFile() }
        val sourceRecords: List<Map<String, String>> = sources.map { mapOf("path" to it.name) }.sortedBy { it.getValue("path") }
        val classpathRecords: List<Map<String, String>> = libraries.map { mapOf("path" to it.absolutePath) }
        val task = h.task().also { task ->
            task.setProperty("sources", h.files(sources)); task.setProperty("libraries", h.files(libraries))
            task.setProperty("destinationDirectory", h.value(h.instance("org.jetbrains.kotlin.gradle.tasks.FixtureDirectory").also {
                it.setProperty("asFile", destination.toFile())
            }))
            h.arguments(task).also { arguments ->
                arguments.setProperty("freeArgs", sources.map { it.absolutePath })
                arguments.setProperty("classpath", libraries.joinToString(":") { it.absolutePath })
                arguments.setProperty("destination", destination.toString())
                arguments.setProperty("argv", listOf("-classpath", libraries.joinToString(":") { it.absolutePath },
                    "-d", destination.toString(), "-module-name", "fixture") + sources.map { it.absolutePath })
            }
        }
        fun verify(capture: Map<String, Any?> = h.capture(task), sourceRecords: List<Map<String, String>> = this.sourceRecords,
            classpathRecords: List<Map<String, String>> = this.classpathRecords, selectedJdk: String = jdk.toString(),
            pluginDomain: Any? = h.empty(task)): Boolean = h.call("verifyConfiguredJvmInputs", task, capture,
            sourceRoot, selectedJdk, sourceRecords, classpathRecords, pluginDomain) as Boolean
        override fun close() {
            Files.walk(root).use { files -> files.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private class Harness : Closeable {
        private val loader = GroovyClassLoader(Kgp2410ModelExporterConfiguredInputsTest::class.java.classLoader)
        private val api: Class<*>
        init {
            assumeTrue(File.separator == "/" && File.pathSeparator == ":", "This new configured-input check is POSIX-only")
            val script = Kgp2410ModelExporter.scriptBytes().toString(Charsets.UTF_8)
            loader.parseClass(script.substringAfter("// BEGIN KTCHECKER_2410_PINNED_MODEL_API\n")
                .substringBefore("// END KTCHECKER_2410_PINNED_MODEL_API"), "Kgp2410ConfiguredInputsApi.groovy")
            api = loader.loadClass("KtChecker2410PinnedModelApi")
            loader.parseClass("package org.gradle.api.provider; interface Provider<T> { T get(); T getOrNull() }", "Provider.groovy")
            loader.parseClass("package org.gradle.work; interface InputChanges {}", "InputChanges.groovy")
            loader.parseClass("""
                package org.jetbrains.kotlin.cli.common.arguments
                class CommonToolArguments { List argv = [] }
                class CommonCompilerArguments extends CommonToolArguments {
                    String[] pluginClasspaths = [], pluginOptions = [], pluginConfigurations = [], pluginOrderConstraints = []
                    String[] commonSources = [], fragments = [], fragmentSources = [], fragmentRefines = []
                    String[] fragmentDependencies = [], fragmentFriendDependencies = []
                    boolean multiPlatform = false, script = false, repl = false, useFirLT = true
                    String intellijPluginRoot
                }
                class K2JVMCompilerArguments extends CommonCompilerArguments {
                    List freeArgs = []
                    String classpath, destination, jdkHome, expression, buildFile, javaPackagePrefix
                    String[] friendPaths = [], javaSourceRoots = [], scriptTemplates = [], scriptResolverEnvironment = []
                }
                class CustomArguments extends K2JVMCompilerArguments {}
            """.trimIndent(), "JvmArguments.groovy")
            loader.parseClass("""
                package org.jetbrains.kotlin.gradle.plugin
                import org.jetbrains.kotlin.cli.common.arguments.CommonToolArguments
                interface KotlinCompilerArgumentsProducer {
                    interface CreateCompilerArgumentsContext {}
                    CommonToolArguments createCompilerArguments(CreateCompilerArgumentsContext context)
                }
            """.trimIndent(), "JvmProducer.groovy")
            loader.parseClass("""
                package org.jetbrains.kotlin.gradle.tasks
                import org.gradle.api.provider.Provider
                import org.gradle.work.InputChanges
                import org.jetbrains.kotlin.cli.common.arguments.*
                import org.jetbrains.kotlin.gradle.plugin.*
                class TaskOutputsBackup {}
                class FixtureProviderFailure extends IllegalStateException {}
                class FixtureValue implements Provider<Object> {
                    Object value
                    boolean fail = false
                    Object get() { if (fail) throw new FixtureProviderFailure(); value }
                    Object getOrNull() { get() }
                }
                class FixtureFiles implements Iterable<File> {
                    List<File> values = []
                    Set<File> getFiles() { new LinkedHashSet<File>(values) }
                    FixtureFiles getAsFileTree() { this }
                    Iterator<File> iterator() { values.iterator() }
                    FixtureFiles plus(FixtureFiles other) { new FixtureFiles(values: (values + other.values).unique()) }
                }
                class FixtureDirectory { File asFile }
                class UnreviewedOptionContainer {
                    int reads = 0
                    Map allOptions() { reads++; throw new IllegalStateException('must not read unreviewed option semantics') }
                }
                class FixtureUnreviewedPluginData {
                    FixtureFiles classpath = new FixtureFiles()
                    int optionReads = 0
                    Object getOptions() { optionReads++; throw new IllegalStateException('must not read unreviewed data options') }
                }
                abstract class AbstractKotlinCompile<T extends CommonCompilerArguments> implements KotlinCompilerArgumentsProducer {
                    Provider pluginData
                    final Provider 'getKotlinPluginData${'$'}kotlin_gradle_plugin_common'() { pluginData }
                    abstract void 'callCompilerAsync${'$'}kotlin_gradle_plugin_common'(T args, InputChanges changes, TaskOutputsBackup backup)
                }
                class KotlinCompile extends AbstractKotlinCompile<K2JVMCompilerArguments> {
                    K2JVMCompilerArguments arguments = new K2JVMCompilerArguments()
                    FixtureFiles pluginClasspath = new FixtureFiles(), javaSources = new FixtureFiles(), scriptSources = new FixtureFiles()
                    FixtureFiles sources = new FixtureFiles(), libraries = new FixtureFiles(), friendPaths = new FixtureFiles()
                    FixtureValue pluginOptions = new FixtureValue(value: []), multiPlatformEnabled = new FixtureValue(value: false)
                    FixtureValue destinationDirectory
                    String path = ':compileKotlin'
                    FixtureFiles 'getScriptSources${'$'}kotlin_gradle_plugin_common'() { scriptSources }
                    K2JVMCompilerArguments createCompilerArguments(KotlinCompilerArgumentsProducer.CreateCompilerArgumentsContext context) { arguments }
                    void 'callCompilerAsync${'$'}kotlin_gradle_plugin_common'(K2JVMCompilerArguments args, InputChanges changes, TaskOutputsBackup backup) {}
                }
                class DecoratedKotlinCompile extends KotlinCompile {}
                class CustomProducer extends KotlinCompile {
                    K2JVMCompilerArguments createCompilerArguments(KotlinCompilerArgumentsProducer.CreateCompilerArgumentsContext context) { arguments }
                }
                class CustomLowering extends KotlinCompile {
                    void 'callCompilerAsync${'$'}kotlin_gradle_plugin_common'(K2JVMCompilerArguments args, InputChanges changes, TaskOutputsBackup backup) {}
                }
            """.trimIndent(), "JvmTask.groovy")
        }
        fun instance(name: String): GroovyObject = loader.loadClass(name).getConstructor().newInstance() as GroovyObject
        fun task(name: String = "KotlinCompile"): GroovyObject = instance("org.jetbrains.kotlin.gradle.tasks.$name")
        fun arguments(task: GroovyObject): GroovyObject = task.getProperty("arguments") as GroovyObject
        fun capture(task: GroovyObject): Map<String, Any?> = mapOf("value" to arguments(task), "arguments" to arguments(task).getProperty("argv"))
        fun value(value: Any?): GroovyObject = instance("org.jetbrains.kotlin.gradle.tasks.FixtureValue").also { it.setProperty("value", value) }
        fun files(files: List<File>): GroovyObject = instance("org.jetbrains.kotlin.gradle.tasks.FixtureFiles").also { it.setProperty("values", files) }
        fun pluginData(): GroovyObject = instance("org.jetbrains.kotlin.gradle.tasks.FixtureUnreviewedPluginData")
        fun empty(task: GroovyObject): Any? {
            val metadata = call("optionalPluginData", task) as Map<*, *>
            val files = call("effectivePluginFiles", task, metadata["value"])
            return call("emptyJvmInputDomain", task, capture(task), metadata, files)
        }
        fun call(name: String, vararg args: Any?): Any? = api.getMethod(name, *Array(args.size) { Any::class.java }).invoke(null, *args)
        fun failure(name: String, vararg args: Any?): Throwable = assertFailsWith<InvocationTargetException> { call(name, *args) }.targetException
        override fun close() { loader.close() }
    }
}

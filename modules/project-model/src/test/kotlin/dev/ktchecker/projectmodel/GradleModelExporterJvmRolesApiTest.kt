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

/** Tests only the exact self-contained helper against named public-signature fakes.
 * No actual project, KGP, Gradle process, installed compiler, or execution authority is used. */
class GradleModelExporterJvmRolesApiTest {
    @Test fun ordinaryAndInheritedDecoratedProducersCanObserveDistinctEmptyNullableStates(): Unit = Harness().use { h ->
        for (name in listOf("KotlinCompile", "DecoratedKotlinCompile")) {
            val task = h.task(name)
            assertEquals("provider_absent", h.empty(task))
            task.setProperty("pluginData", h.value(null))
            assertEquals("value_absent", h.empty(task))
            task.setProperty("pluginData", h.value(h.pluginData()))
            assertEquals("available_empty", h.empty(task))
        }
    }

    @Test fun equalClassNamesDoNotPermitCustomProducerOrLoweringDispatch(): Unit = Harness().use { h ->
        for (name in listOf("CustomProducer", "CustomLowering")) {
            val task = h.task(name)
            val failure = h.failure("ordinaryJvmProducer", task, h.capture(task)) as GroovyObject
            assertEquals("compiler-jvm-original-producer", failure.getProperty("stage"))
        }
        assertFailsWith<InvocationTargetException> { h.call("ordinaryJvmProducer", Any(), mapOf("value" to Any(), "arguments" to emptyList<String>())) }
    }

    @Test fun metadataGetterErrorRemainsAFailureNotAnAbsentOrEmptyState(): Unit = Harness().use { h ->
        val task = h.task(); val provider = h.value(null).also { it.setProperty("fail", true) }
        task.setProperty("pluginData", provider)
        val failure = h.failure("optionalPluginData", task) as GroovyObject
        assertEquals("compiler-plugin-data-value", failure.getProperty("stage"))
        assertEquals("FixtureProviderFailure", failure.getProperty("failureKind"))
    }

    @Test fun actualPluginFilesOptionsAndEveryMetadataInputOutputCategoryMustBeEmpty(): Unit = Harness().use { h ->
        val task = h.task()
        task.setProperty("pluginClasspath", h.files(listOf(File("/plugin.jar"))))
        assertFailsWith<InvocationTargetException> { h.empty(task) }
        task.setProperty("pluginClasspath", h.files(emptyList()))
        val options = h.instance("org.jetbrains.kotlin.gradle.plugin.CompilerPluginConfig")
        options.setProperty("values", mapOf("plugin" to listOf("nonempty lazy option")))
        task.setProperty("pluginOptions", h.value(listOf(options)))
        assertFailsWith<InvocationTargetException> { h.empty(task) }
        task.setProperty("pluginOptions", h.value(emptyList<Any>()))
        for ((field, nonempty) in listOf("inputs" to mapOf("property" to "value"), "inputFiles" to setOf(File("/input")),
            "outputFiles" to setOf(File("/output")))) {
            val data = h.pluginData()
            (data.getProperty("inputsOutputsState") as GroovyObject).setProperty(field, nonempty)
            task.setProperty("pluginData", h.value(data))
            assertFailsWith<InvocationTargetException> { h.empty(task) }
        }
    }

    @Test fun emptyOptionContainersAreAllowedButNeitherArgumentArraysNorSerializationFailuresAreDropped(): Unit = Harness().use { h ->
        val task = h.task()
        val base = h.instance("org.jetbrains.kotlin.gradle.plugin.CompilerPluginConfig")
        base.setProperty("values", mapOf("empty-container" to emptyList<String>()))
        task.setProperty("pluginOptions", h.value(listOf(base)))
        assertEquals("provider_absent", h.empty(task), "Base CompilerPluginConfig has allOptions(), not getArguments()")
        for (field in listOf("pluginClasspaths", "pluginOptions", "pluginConfigurations", "pluginOrderConstraints")) {
            val args = h.arguments(task)
            args.setProperty(field, arrayOf("nonempty"))
            assertFailsWith<InvocationTargetException> { h.empty(task) }
            args.setProperty(field, emptyArray<String>())
        }
        val data = h.pluginData(); val serialized = data.getProperty("options") as GroovyObject
        serialized.setProperty("forcedArguments", listOf("plugin:unexpected:option=value"))
        task.setProperty("pluginData", h.value(data))
        assertFailsWith<InvocationTargetException> { h.empty(task) }
    }

    @Test fun scriptJavaRefinementAndAlternateFrontendDomainsCannotAttestAnEmptyOrdinaryJvmRoute(): Unit = Harness().use { h ->
        for (field in listOf("javaSources", "scriptSources")) {
            val task = h.task().also { it.setProperty(field, h.files(listOf(File("/auxiliary.kt")))) }
            assertFailsWith<InvocationTargetException> { h.empty(task) }
        }
        for ((field, value) in listOf("useFirLT" to false, "script" to true, "repl" to true, "multiPlatform" to true,
            "javaSourceRoots" to arrayOf("/java"), "fragmentSources" to arrayOf("/common.kt"))) {
            val task = h.task(); h.arguments(task).setProperty(field, value)
            assertFailsWith<InvocationTargetException> { h.empty(task) }
        }
        for (raw in listOf(listOf("@response"), listOf("-Xplugin=/plugin.jar"), listOf("-P", "plugin:option"))) {
            val task = h.task(); h.arguments(task).setProperty("argv", raw)
            assertFailsWith<InvocationTargetException> { h.empty(task) }
        }
    }

    @Test fun originalRolesRetainActualSourceAndClasspathOrderWithoutChangingRawCapture(): Unit = Harness().use { h ->
        val f = FilesFixture(h); val capture = h.capture(f.task)
        val original = capture["arguments"]
        val roles = f.roles(capture)
        assertEquals(listOf("Z.kt", "A.kt"), roles["sourcePaths"])
        assertEquals(f.libraries.joinToString(":") { it.absolutePath }, roles["classpathOperand"])
        assertEquals(f.destination.toString(), roles["destinationPath"])
        assertEquals(f.jdk.toString(), roles["selectedJdkHome"])
        assertNull(roles["jdkHomeOperand"])
        assertEquals(original, capture["arguments"], "Role extraction is not an argv rewrite")
    }

    @Test fun sourceTaskFreeArgsAndCompleteMembershipMustAgreeWhileTheirOrdersStayDistinct(): Unit = Harness().use { h ->
        val f = FilesFixture(h)
        h.arguments(f.task).setProperty("freeArgs", f.sources.reversed().map { it.absolutePath })
        assertFailsWith<InvocationTargetException> { f.roles() }
        h.arguments(f.task).setProperty("freeArgs", f.sources.map { it.absolutePath })
        assertFailsWith<InvocationTargetException> { f.roles(sourceRecords = f.sourceRecords.dropLast(1)) }
        assertFailsWith<InvocationTargetException> { f.roles(classpathRecords = f.classpathRecords.reversed()) }
        Files.delete(f.sources.first().toPath())
        assertFailsWith<InvocationTargetException> { f.roles() }
    }

    @Test fun absentAndEmptyClasspathAndSourceObservationsRemainDistinctDataNotImplicitExecutionSupport(): Unit = Harness().use { h ->
        val f = FilesFixture(h); f.task.setProperty("libraries", h.files(emptyList()))
        for (operand in listOf(null, "")) {
            h.arguments(f.task).setProperty("classpath", operand)
            assertEquals(operand, f.roles(classpathRecords = emptyList())["classpathOperand"])
        }
        f.task.setProperty("sources", h.files(emptyList())); h.arguments(f.task).setProperty("freeArgs", emptyList<String>())
        assertEquals(emptyList<String>(), f.roles(sourceRecords = emptyList(), classpathRecords = emptyList())["sourcePaths"])
    }

    private class FilesFixture(private val h: Harness) {
        private val root = Files.createTempDirectory("ktchecker-original-role-api-").toRealPath()
        val sourceRoot: Path = Files.createDirectory(root.resolve("sources"))
        val jdk: Path = Files.createDirectory(root.resolve("jdk"))
        val destination: Path = root.resolve("output/classes")
        val sources: List<File> = listOf("Z.kt", "A.kt").map { Files.writeString(sourceRoot.resolve(it), "fun ${it.first().lowercase()}() = 1\n").toFile() }
        val libraries: List<File> = listOf("z.jar", "a.jar").map { Files.writeString(root.resolve(it), "declaration-only bytes").toFile() }
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
                arguments.setProperty("argv", listOf("-classpath", libraries.joinToString(":") { it.absolutePath }, "-d", destination.toString()) +
                    sources.map { it.absolutePath })
            }
        }
        fun roles(capture: Map<String, Any?> = h.capture(task), sourceRecords: List<Map<String, String>> = this.sourceRecords,
            classpathRecords: List<Map<String, String>> = this.classpathRecords): Map<*, *> = h.call("originalJvmArgumentRoles", task, capture,
            sourceRoot, jdk.toString(), sourceRecords, classpathRecords, h.empty(task)) as Map<*, *>
    }

    private class Harness : Closeable {
        private val loader = GroovyClassLoader(GradleModelExporterJvmRolesApiTest::class.java.classLoader)
        private val api: Class<*>
        init {
            assumeTrue(File.separator == "/", "The reviewed role adapter is explicitly POSIX-only")
            val script = GradleModelExporter.scriptBytes().toString(Charsets.UTF_8)
            loader.parseClass(script.substringAfter("// BEGIN KTCHECKER_PINNED_MODEL_API\n")
                .substringBefore("// END KTCHECKER_PINNED_MODEL_API"), "PinnedJvmRoleApi.groovy")
            api = loader.loadClass("KtCheckerPinnedModelApi")
            loader.parseClass("package org.gradle.api.provider; interface Provider<T> { T get(); T getOrNull() }", "Provider.groovy")
            loader.parseClass("package org.gradle.work; interface InputChanges {}", "InputChanges.groovy")
            loader.parseClass("""
                package org.jetbrains.kotlin.cli.common.arguments
                class CommonToolArguments { List argv = [] }
                class CommonCompilerArguments extends CommonToolArguments {
                    String[] pluginClasspaths = [], pluginOptions = [], pluginConfigurations = [], pluginOrderConstraints = []
                    String[] commonSources = [], fragments = [], fragmentSources = [], fragmentRefines = []
                    String[] fragmentDependencies = [], fragmentFriendDependencies = [], fragmentIncrementalClasspath = []
                    boolean multiPlatform = false, script = false, repl = false, useFirLT = true
                    String intellijPluginRoot
                }
                class K2JVMCompilerArguments extends CommonCompilerArguments {
                    List freeArgs = []
                    String classpath, destination, jdkHome, expression, buildFile, javaPackagePrefix
                    String[] friendPaths = [], javaSourceRoots = [], scriptTemplates = [], scriptResolverEnvironment = []
                }
            """.trimIndent(), "JvmArguments.groovy")
            loader.parseClass("""
                package org.jetbrains.kotlin.gradle.plugin
                import org.jetbrains.kotlin.cli.common.arguments.CommonToolArguments
                interface KotlinCompilerArgumentsProducer {
                    interface CreateCompilerArgumentsContext {}
                    CommonToolArguments createCompilerArguments(CreateCompilerArgumentsContext context)
                }
                class CompilerPluginConfig {
                    Map values = [:]
                    final Map allOptions() { values }
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
                final class CompilerPluginOptions extends CompilerPluginConfig {
                    List forcedArguments
                    final List getArguments() { forcedArguments != null ? forcedArguments : values.values().flatten() }
                }
                final class KotlinCompilerPluginData {
                    FixtureFiles classpath = new FixtureFiles()
                    CompilerPluginOptions options = new CompilerPluginOptions()
                    InputsOutputsState inputsOutputsState = new InputsOutputsState()
                    static final class InputsOutputsState {
                        Map inputs = [:]
                        Set inputFiles = [] as Set
                        Set outputFiles = [] as Set
                    }
                }
                abstract class AbstractKotlinCompile<T extends CommonCompilerArguments> implements KotlinCompilerArgumentsProducer {
                    Provider pluginData
                    final Provider 'getKotlinPluginData${'$'}kotlin_gradle_plugin_common'() { pluginData }
                    abstract void 'callCompilerAsync${'$'}kotlin_gradle_plugin_common'(T args, InputChanges changes, TaskOutputsBackup backup)
                }
                class KotlinCompile extends AbstractKotlinCompile<K2JVMCompilerArguments> {
                    K2JVMCompilerArguments arguments = new K2JVMCompilerArguments()
                    FixtureFiles pluginClasspath = new FixtureFiles(), javaSources = new FixtureFiles(), scriptSources = new FixtureFiles()
                    FixtureFiles sources = new FixtureFiles(), libraries = new FixtureFiles()
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
        fun pluginData(): GroovyObject = instance("org.jetbrains.kotlin.gradle.tasks.KotlinCompilerPluginData")
        fun empty(task: GroovyObject): Any? {
            val metadata = call("optionalPluginData", task) as Map<*, *>
            val files = call("effectivePluginFiles", task, metadata["value"])
            return call("emptyJvmPluginDomain", task, capture(task), metadata, files)
        }
        fun call(name: String, vararg args: Any?): Any? = api.getMethod(name, *Array(args.size) { Any::class.java }).invoke(null, *args)
        fun failure(name: String, vararg args: Any?): Throwable = assertFailsWith<InvocationTargetException> { call(name, *args) }.targetException
        override fun close() { loader.close() }
    }
}

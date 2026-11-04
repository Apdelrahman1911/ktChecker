package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.Digests
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

/** No archive/class loading or real compiler execution: pins and synthetic metadata only. */
class OwnerFixtureDefaultScripting2421Test {
    private fun inspect(f: OwnerJvmSyntheticFixture, plugins: List<ModelArtifact> = f.plugins,
        arguments: List<String> = f.lib.compilerArguments,
        semantics: OwnerJvmLaunchSemantics = OwnerJvmLaunchSemantics.INTENDED_KGP_TASK_CONFIGURATION) =
        OwnerFixtureDefaultScripting2421.inspect(f.sourceContract, f.bindings.dependencyHome, plugins, arguments, semantics)

    @Test fun oneDefaultPluginHasSevenOrderedFilesAndReviewedNotLoadedServiceRequirements(): Unit {
        val f = OwnerJvmSyntheticFixture.create()
        val assessment = inspect(f)
        assertEquals("ktchecker/owner-default-scripting/jvm-2.4.21/1", assessment.adapterId)
        assertEquals("kotlin.scripting", assessment.pluginId)
        assertEquals("pinned_intended_classpath_only", assessment.state)
        assertEquals(7, assessment.classpathSize)
        assertEquals(listOf("kotlin-scripting-compiler-embeddable-2.4.21.jar", "kotlin-scripting-compiler-impl-embeddable-2.4.21.jar",
            "kotlin-scripting-jvm-2.4.21.jar", "kotlin-scripting-common-2.4.21.jar", "kotlin-stdlib-2.4.21.jar",
            "kotlin-script-runtime-2.4.21.jar", "annotations-13.0.jar"), f.plugins.map { Path.of(it.path).fileName.toString() })
        val requirements = assessment.reviewedServiceExpectations.associateBy { it.service }
        assertEquals(3, requirements.size)
        val registrar = requirements.getValue("org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar")
        assertEquals("org.jetbrains.kotlin.scripting.compiler.plugin.ScriptingK2CompilerPluginRegistrar", registrar.implementation)
        assertTrue(registrar.use.contains("reviewed PluginCliParser source"))
        assertTrue(registrar.use.contains("unobserved"))
        val processor = requirements.getValue("org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor")
        assertEquals("org.jetbrains.kotlin.scripting.compiler.plugin.ScriptingCommandLineProcessor", processor.implementation)
        assertTrue(processor.use.contains("reviewed PluginCliParser source"))
        assertTrue(processor.use.contains("unobserved"))
        val legacy = requirements.getValue("org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar")
        assertEquals("org.jetbrains.kotlin.scripting.compiler.plugin.ScriptingCompilerConfigurationComponentRegistrar", legacy.implementation)
        assertTrue(legacy.use.contains("not loaded as a service in reviewed PluginCliParser source"))
        assertTrue(legacy.use.contains("actual consumption unobserved"))
        assertTrue(assessment.missingFacts.any { it.role == OwnerJvmMissingFact.COMPILER_AND_PLUGIN_CLASSLOADER_ORIGINS })
        assertFailsWith<UnsupportedOperationException> { (assessment.reviewedServiceExpectations as MutableList<*>).clear() }
    }

    @Test fun defaultRequirementsDoNotSynthesizeDisablingFlagsOrEffectiveHostFacts(): Unit {
        val f = OwnerJvmSyntheticFixture.create()
        val before = f.lib.compilerArguments.toList()
        val assessment = inspect(f)
        val defaults = assessment.requiredDefaults.associate { it.key to it.requiredValue }
        assertEquals(15, defaults.size)
        for (key in listOf("kgp.scriptDefinitions", "kgp.scriptDefinitionsClasspath", "userScriptDefinitions",
            "userScriptDefinitionsClasspath", "userLegacyTemplates")) assertEquals("[]", defaults.getValue(key))
        assertEquals("{}", defaults.getValue("kgp.scriptResolverEnvironment"))
        for (key in listOf("kgp.disableScriptDefinitionsFromClasspath", "disableScriptingPlugin", "disableStandardScriptDefinition",
            "disableDefinitionsFromClasspath", "disableDefinitionsAutoloading", "enableScriptExplanation")) assertEquals("false", defaults.getValue(key))
        assertTrue(defaults.getValue("userResolverEnvironment").contains("effective projectRoot remains unknown"))
        assertTrue(defaults.getValue("disableScriptCompilationCache").startsWith("false;"))
        assertTrue(defaults.getValue("hostConfiguration").contains("no custom"))
        assertTrue(assessment.intendedPluginOptions.isEmpty())
        assertEquals(before, f.lib.compilerArguments)
        assertTrue(before.none { it.startsWith("-P") })
        assertFailsWith<UnsupportedOperationException> { (assessment.requiredDefaults as MutableList<*>).clear() }
    }

    @Test fun nullMetadataOrdinaryKtAndNoPluginOptionsLeaveAllFifteenExternalFactsMissing(): Unit {
        val f = OwnerJvmSyntheticFixture.create()
        val assessment = inspect(f)
        assertTrue(f.lib.sourceFiles.single().path.endsWith(".kt"))
        assertTrue(f.lib.auxiliaryInputFiles.isEmpty()); assertTrue(f.lib.auxiliaryArtifacts.isEmpty())
        assertEquals(OwnerJvmMissingFact.entries.toList(), assessment.missingFacts.map { it.role })
        val facts = assessment.missingFacts.associate { it.role to it.explanation }
        assertTrue(facts.getValue(OwnerJvmMissingFact.KGP_PLUGIN_INPUT_PROPERTIES).contains("not positive evidence"))
        assertTrue(facts.getValue(OwnerJvmMissingFact.KGP_SCRIPT_DEFINITION_FILES).contains("outside compiler argv"))
        assertTrue(facts.getValue(OwnerJvmMissingFact.KGP_SCRIPT_EXTENSIONS).contains("separately to BTA"))
        assertTrue(facts.getValue(OwnerJvmMissingFact.BTA_COMPILATION_OPERATION_FIELDS).contains("clears destination"))
        assertTrue(facts.getValue(OwnerJvmMissingFact.BTA_INCREMENTAL_AND_REFERENCE_INDEX_SETTINGS).contains("GENERATE_COMPILER_REF_INDEX"))
        assertTrue(facts.getValue(OwnerJvmMissingFact.BTA_RENDERER_LOGGING_AND_METRICS_SETTINGS).contains("separate operation inputs"))
        assertTrue(facts.getValue(OwnerJvmMissingFact.SCRIPTING_EFFECTIVE_PROJECT_ROOT).contains("basePath/baseDir"))
        val discovery = facts.getValue(OwnerJvmMissingFact.SCRIPTING_DISCOVERY_AND_FALLBACK_NAMESPACES)
        assertTrue(discovery.contains("template markers")); assertTrue(discovery.contains("siblings"))
        assertTrue(discovery.contains("cwd fallback")); assertTrue(discovery.contains("multi-release"))
        // An added sibling, host injection, manifest read or cache outside argv is still
        // unknown, not a negative observation silently inferred from an ordinary .kt file.
        assertTrue(facts.getValue(OwnerJvmMissingFact.SCRIPTING_AUXILIARY_READSET).contains("conditional"))
        assertTrue(facts.getValue(OwnerJvmMissingFact.COMPILER_ATTEMPT_FRESHNESS).contains("not a fresh compiler-attempt"))
        assertTrue(facts.getValue(OwnerJvmMissingFact.COMPILER_EXECUTION_AND_DERIVED_OUTPUT).contains("No compiler attempt"))
        assertIs<OwnerJvmPlanResult.Classified>(f.classify()).also { assertEquals(assessment, it.data.scripting) }
        assertFailsWith<UnsupportedOperationException> { (assessment.missingFacts as MutableList<*>).clear() }
    }

    @Test fun anyPluginOptionArgfileDuplicateAlternativeSpellingOrMissingTokenIsDenied(): Unit {
        val f = OwnerJvmSyntheticFixture.create()
        val argv = f.lib.compilerArguments
        val token = argv[11]
        val alternatives = listOf(argv.filterNot { it == token }, argv + token, argv + "-Xplugin-extra",
            argv.map { if (it == token) "-Xplugin" else it },
            argv.map { if (it == token) token.replace("-Xplugin=", "-Xcompiler-plugin=") else it },
            argv.map { if (it == token) "-Xplugin=" + f.plugins.reversed().joinToString(",") { artifact -> artifact.path } else it }) +
            listOf("-P", "-P=plugin:kotlin.scripting:script-templates=Other", "-Pplugin:kotlin.scripting:script-templates=Other",
                "plugin:kotlin.scripting:disable=true", "-Xcompiler-plugin=other", "@hidden-plugin-options.txt").map { argv + it }
        for (changed in alternatives) {
            assertEquals("owner_jvm_scripting_options_unmodeled", assertFailsWith<OwnerJvmPlanProblem> { inspect(f, arguments = changed) }.code)
        }
        assertEquals(argv, f.lib.compilerArguments)
    }

    @Test fun intendedGradleArgumentsCannotBeRelabeledAsDirectCliServicesEmptyReplay(): Unit {
        val f = OwnerJvmSyntheticFixture.create()
        assertEquals("owner_jvm_launch_semantics_unsupported", assertFailsWith<OwnerJvmPlanProblem> {
            inspect(f, semantics = OwnerJvmLaunchSemantics.DIRECT_K2_CLI_REPLAY)
        }.code)
        val rejected = assertIs<OwnerJvmPlanResult.Rejected>(f.classify(semantics = OwnerJvmLaunchSemantics.DIRECT_K2_CLI_REPLAY))
        assertEquals("owner_jvm_launch_semantics_unsupported", rejected.problem.code)
        assertEquals("launch", rejected.problem.role)
    }

    @Test fun wholeFilePinsDenyChangedDescriptorTemplateManifestIndexAndBasenameDomains(): Unit {
        val f = OwnerJvmSyntheticFixture.create()
        val first = f.plugins.first()
        // Synthetic ZIP bytes only, never opened as a JAR/classpath or executed. The
        // whole-file pin denies all changed member domains without a weaker name-only scan.
        val additions = listOf("META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar" to "different.Registrar\n",
            "META-INF/kotlin/script/templates/InjectedDefinition" to "injected marker",
            "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\r\nClass-Path: sibling.jar\r\n\r\n",
            "META-INF/INDEX.LIST" to "JarIndex-Version: 1.0\n\nsibling.jar\n")
        for ((name, content) in additions) {
            val bytes = ByteArrayOutputStream().let { buffer ->
                ZipOutputStream(buffer).use { zip -> zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry() }
                buffer.toByteArray()
            }
            val replacement = first.copy(digest = Digests.sha256(bytes), size = bytes.size.toLong())
            assertEquals("owner_jvm_scripting_classpath_changed", assertFailsWith<OwnerJvmPlanProblem> {
                inspect(f, plugins = listOf(replacement) + f.plugins.drop(1))
            }.code)
        }
        val renamed = f.plugins.mapIndexed { index, artifact -> artifact.copy(path = Path.of(artifact.path).resolveSibling("$index.jar").toString()) }
        val coherentArguments = f.lib.compilerArguments.mapIndexed { index, value -> if (index == 11) "-Xplugin=" + renamed.joinToString(",") { it.path } else value }
        assertEquals("owner_jvm_scripting_classpath_changed", assertFailsWith<OwnerJvmPlanProblem> { inspect(f, renamed, coherentArguments) }.code)
        for (plugins in listOf(f.plugins.drop(1), f.plugins + first, f.plugins.reversed(), f.plugins.map { it.copy(digest = null) })) {
            assertEquals("owner_jvm_scripting_classpath_changed", assertFailsWith<OwnerJvmPlanProblem> { inspect(f, plugins) }.code)
        }
    }
}

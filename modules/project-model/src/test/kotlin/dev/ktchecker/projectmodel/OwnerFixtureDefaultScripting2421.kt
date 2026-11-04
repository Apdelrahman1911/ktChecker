package dev.ktchecker.projectmodel

import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable internal enum class OwnerJvmLaunchSemantics {
    INTENDED_KGP_TASK_CONFIGURATION,
    // Distinct semantics, deliberately NOT implemented by this classifier.
    DIRECT_K2_CLI_REPLAY,
}

@Serializable internal enum class OwnerJvmMissingFact {
    KGP_PLUGIN_INPUT_PROPERTIES,
    KGP_SCRIPT_DEFINITION_FILES,
    KGP_SCRIPT_EXTENSIONS,
    BTA_COMPILATION_OPERATION_FIELDS,
    BTA_INCREMENTAL_AND_REFERENCE_INDEX_SETTINGS,
    BTA_RENDERER_LOGGING_AND_METRICS_SETTINGS,
    COMPILER_ENTRYPOINT_AND_SERVICES,
    COMPILER_AND_PLUGIN_CLASSLOADER_ORIGINS,
    SCRIPTING_HOST_CONFIGURATION,
    SCRIPTING_EFFECTIVE_PROJECT_ROOT,
    SCRIPTING_DISCOVERY_AND_FALLBACK_NAMESPACES,
    SCRIPTING_AUXILIARY_READSET,
    SCRIPTING_CACHE_AND_OUTPUT_EFFECTS,
    COMPILER_ATTEMPT_FRESHNESS,
    COMPILER_EXECUTION_AND_DERIVED_OUTPUT,
}

@Serializable internal data class OwnerJvmRequiredFact(val role: OwnerJvmMissingFact, val explanation: String)
@Serializable internal data class OwnerJvmScriptingDefault(val key: String, val requiredValue: String)
@Serializable internal data class OwnerJvmServiceExpectation(val service: String, val implementation: String, val use: String)
@Serializable internal data class OwnerJvmScriptingAssessment(val adapterId: String, val pluginId: String,
    val state: String, val classpathSize: Int, val intendedPluginOptions: List<String>,
    val requiredDefaults: List<OwnerJvmScriptingDefault>, val reviewedServiceExpectations: List<OwnerJvmServiceExpectation>,
    val missingFacts: List<OwnerJvmRequiredFact>)

/** Fixed requirements from retained 2.4.21 source and pinned-file descriptor review. No classloading or plugin execution.
 * File pins justify this particular descriptor catalog, not actual loader origins, effective host settings,
 * absence of discovery inputs, or source-to-binary attestation. Unknowns have no caller-supplied success flag. */
internal object OwnerFixtureDefaultScripting2421 {
    const val ADAPTER_ID = "ktchecker/owner-default-scripting/jvm-2.4.21/1"
    private const val PACKAGE = "org.jetbrains.kotlin.scripting.compiler.plugin."

    fun inspect(sourceContract: OwnerFixtureContract, dependencyHome: Path, actualClasspath: List<ModelArtifact>,
        intendedArguments: List<String>, semantics: OwnerJvmLaunchSemantics): OwnerJvmScriptingAssessment {
        ownerJvmRequire(semantics == OwnerJvmLaunchSemantics.INTENDED_KGP_TASK_CONFIGURATION,
            "owner_jvm_launch_semantics_unsupported", "launch")
        val expected = ownerJvmPinnedArtifacts(sourceContract.value.compilerPluginClasspath.map {
            OwnerJvmFilePin(it.seedRelativePath, it.coordinate, it.digest, it.size)
        }, dependencyHome, 7, "scripting-classpath")
        ownerJvmRequire(actualClasspath == expected, "owner_jvm_scripting_classpath_changed", "scripting-classpath")
        val pluginToken = "-Xplugin=" + expected.joinToString(",") { it.path }
        ownerJvmRequire(intendedArguments.filter { it.startsWith("-Xplugin") } == listOf(pluginToken) &&
            intendedArguments.none { it.startsWith("-P") || it.startsWith("plugin:") || it.startsWith("-Xcompiler-plugin") || it.startsWith('@') },
            "owner_jvm_scripting_options_unmodeled", "scripting-options")

        return OwnerJvmScriptingAssessment(ADAPTER_ID, "kotlin.scripting", "pinned_intended_classpath_only", 7,
            emptyList(), immutable(listOf(
                OwnerJvmScriptingDefault("kgp.scriptDefinitions", "[]"),
                OwnerJvmScriptingDefault("kgp.scriptDefinitionsClasspath", "[]"),
                OwnerJvmScriptingDefault("kgp.disableScriptDefinitionsFromClasspath", "false"),
                OwnerJvmScriptingDefault("kgp.scriptResolverEnvironment", "{}"),
                OwnerJvmScriptingDefault("userScriptDefinitions", "[]"),
                OwnerJvmScriptingDefault("userScriptDefinitionsClasspath", "[]"),
                OwnerJvmScriptingDefault("userLegacyTemplates", "[]"),
                OwnerJvmScriptingDefault("userResolverEnvironment", "{}; effective projectRoot remains unknown"),
                OwnerJvmScriptingDefault("disableScriptingPlugin", "false"),
                OwnerJvmScriptingDefault("disableStandardScriptDefinition", "false"),
                OwnerJvmScriptingDefault("disableDefinitionsFromClasspath", "false"),
                OwnerJvmScriptingDefault("disableDefinitionsAutoloading", "false"),
                OwnerJvmScriptingDefault("enableScriptExplanation", "false"),
                OwnerJvmScriptingDefault("disableScriptCompilationCache", "false; no supplied cache is NOT established by argv"),
                OwnerJvmScriptingDefault("hostConfiguration", "defaultJvmScriptingHostConfiguration; no custom provider/host injection"),
            )), immutable(listOf(
                OwnerJvmServiceExpectation("org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar",
                    PACKAGE + "ScriptingK2CompilerPluginRegistrar", "reviewed PluginCliParser source service; actual origin/consumption unobserved"),
                OwnerJvmServiceExpectation("org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor",
                    PACKAGE + "ScriptingCommandLineProcessor", "reviewed PluginCliParser source service; actual origin/consumption unobserved"),
                OwnerJvmServiceExpectation("org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar",
                    PACKAGE + "ScriptingCompilerConfigurationComponentRegistrar",
                    "legacy descriptor; not loaded as a service in reviewed PluginCliParser source; actual consumption unobserved"),
            )), requiredFacts())
    }

    fun requiredFacts(): List<OwnerJvmRequiredFact> = immutable(listOf(
        OwnerJvmRequiredFact(OwnerJvmMissingFact.KGP_PLUGIN_INPUT_PROPERTIES,
            "Missing kotlinPluginData is not positive evidence of an empty compiler-plugin property/input domain."),
        OwnerJvmRequiredFact(OwnerJvmMissingFact.KGP_SCRIPT_DEFINITION_FILES,
            "KotlinCompile.scriptDefinitions can contain discovery-result files read outside compiler argv."),
        OwnerJvmRequiredFact(OwnerJvmMissingFact.KGP_SCRIPT_EXTENSIONS,
            "KotlinCompile reads scriptDefinitions lines and supplies kotlinScriptExtensions separately to BTA."),
        OwnerJvmRequiredFact(OwnerJvmMissingFact.BTA_COMPILATION_OPERATION_FIELDS,
            "JvmBuildOperationFactory extracts sources/destination, clears destination, sets KOTLINSCRIPT_EXTENSIONS and reserializes options."),
        OwnerJvmRequiredFact(OwnerJvmMissingFact.BTA_INCREMENTAL_AND_REFERENCE_INDEX_SETTINGS,
            "BTA incremental configuration and GENERATE_COMPILER_REF_INDEX are not established by the intended thirteen tokens."),
        OwnerJvmRequiredFact(OwnerJvmMissingFact.BTA_RENDERER_LOGGING_AND_METRICS_SETTINGS,
            "BTA renderer, argument logging and optional KGP metrics settings are separate operation inputs."),
        OwnerJvmRequiredFact(OwnerJvmMissingFact.COMPILER_ENTRYPOINT_AND_SERVICES,
            "An intended KGP task is not an observed compiler invocation or an explicitly declared direct CLI Services.EMPTY recipe."),
        OwnerJvmRequiredFact(OwnerJvmMissingFact.COMPILER_AND_PLUGIN_CLASSLOADER_ORIGINS,
            "Pinned 14/7 classpath files do not establish actual parent loaders, code/resource origins or loaded memory."),
        OwnerJvmRequiredFact(OwnerJvmMissingFact.SCRIPTING_HOST_CONFIGURATION,
            "No custom scripting host/provider injection must be established independently of the absence of -P."),
        OwnerJvmRequiredFact(OwnerJvmMissingFact.SCRIPTING_EFFECTIVE_PROJECT_ROOT,
            "updateScriptingConfiguration can inject resolver environment projectRoot from IntelliJ project basePath/baseDir."),
        OwnerJvmRequiredFact(OwnerJvmMissingFact.SCRIPTING_DISCOVERY_AND_FALLBACK_NAMESPACES,
            "Default discovery reads classpath template markers and enumerates the implementing JAR's siblings, with cwd fallback; bind basenames, properties, JDK multi-release interpretation and closed fresh namespaces."),
        OwnerJvmRequiredFact(OwnerJvmMissingFact.SCRIPTING_AUXILIARY_READSET,
            "The fixed .kt is non-script under the default provider; this conditional fact does not prove the effective plugin readset."),
        OwnerJvmRequiredFact(OwnerJvmMissingFact.SCRIPTING_CACHE_AND_OUTPUT_EFFECTS,
            "Default refined-configuration cache is in memory; unknown supplied hosts/caches still prevent a complete effects claim."),
        OwnerJvmRequiredFact(OwnerJvmMissingFact.COMPILER_ATTEMPT_FRESHNESS,
            "Exporter input freshness is not a fresh compiler-attempt snapshot/namespace/output-absence witness."),
        OwnerJvmRequiredFact(OwnerJvmMissingFact.COMPILER_EXECUTION_AND_DERIVED_OUTPUT,
            "No compiler attempt, terminal-confirmed completion, derived class tree, produced variant or ABI proof exists here."),
    ))
}

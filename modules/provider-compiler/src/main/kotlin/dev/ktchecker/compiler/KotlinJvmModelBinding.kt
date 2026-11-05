package dev.ktchecker.compiler

import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*

/** Fixed by the installed owner route, NEVER selected from an AnalysisInput or profile flag. */
enum class KotlinJvmInputMode { LEGACY_EXPLICIT_INPUT, VERIFIED_MODEL }

class KotlinJvmModelException(val code: String, message: String) : IllegalArgumentException(message)

/** Data describing a checked relation. Possessing/copying its digest grants no authority. */
class JvmModelBindingIdentity internal constructor(
    val digest: String,
    val compilationId: String,
    val modelContextDigest: String,
    val modelToolchainDigest: String,
    val installedToolchainDigest: String,
    val runtimeImageDigest: String,
)

/**
 * Nonserialized relation between a live importer capability and the SAME installed toolchain.
 * It grants neither process execution nor rule qualification. Ingress remains responsible for
 * the complete independently observed model domain and approved immutable external artifacts.
 */
class KotlinJvmModelBinding private constructor(
    val model: VerifiedProjectModel,
    val compilationId: String,
    internal val installation: KotlinJvmToolchain,
    internal val compilation: ModelCompilation,
    internal val originalContext: AnalysisContext,
    internal val originalArgumentLayout: VerifiedJvmArgumentLayout?,
    val identity: JvmModelBindingIdentity,
) {
    /** Typed execution projections are DISTINCT from unchanged raw context/identity at P/K/C/E. */
    val normalizedCompilerOptions: List<String> = Collections.unmodifiableList(
        (originalArgumentLayout?.normalizedCompilerOptions ?: originalContext.compilerArguments).toList())
    val executableCompilationFiles: List<String> = Collections.unmodifiableList(
        (originalArgumentLayout?.executableCompilationFiles ?: requireNotNull(originalContext.compilationFiles)).toList())
    val argumentLayoutDigest: String? = originalArgumentLayout?.digest

    /** Never reads the old source/dependency paths; those may be gone after kernel acceptance. */
    fun verifyInstallation(checkpoint: () -> Unit = {}) = verifyModelInstallation(installation, checkpoint)

    /**
     * Owner retainForAdmission only. Final whole input/profile map, not a narrowed replacement
     * parent. The exact compiler rule projection comes from the installed coordinator route.
     */
    fun bindPreparedInput(
        original: AnalysisInput,
        compilerRuleIds: List<String>,
        provider: KotlinJvmCompilerProvider,
        profiles: Map<String, TrustedExecution>,
    ): PreparedJvmModelInput {
        val frozen = freezeJvmInput(original)
        requireOriginalInput(frozen, requireFinal = true)
        requireProvider(provider)
        val selected = Collections.unmodifiableList(compilerRuleIds.toList())
        modelCheck(frozen.requiredRuleIds.isNotEmpty() && frozen.requiredRuleIds.distinct().size == frozen.requiredRuleIds.size &&
            selected.isNotEmpty() && selected.distinct().size == selected.size &&
            selected.all { it in frozen.requiredRuleIds && it in provider.supportedRuleIds },
            "compiler_model_rule_projection_invalid", "Compiler rules must retain the exact nonempty owner route within the whole input.")
        val capturedProfiles = freezeJvmProfiles(profiles)
        modelCheck(frozen.context.policies[EXECUTION_POLICY] == jsonDigest(ContractJson.encodeToJsonElement(capturedProfiles)),
            "compiler_model_profiles_mismatch", "Final execution-plan identity differs from the complete captured profile map.")
        val profile = capturedProfiles[provider.id]
            ?: modelFailure("compiler_model_profile_missing", "The captured compiler route has no execution profile.")
        val expected = provider.executionProfile(frozen.copy(requiredRuleIds = selected), this,
            profile.timeoutMillis, profile.maxOutputBytes)
        modelCheck(profile == expected, "compiler_model_profile_mismatch", "Captured profile differs from this exact installed model-mode provider.")
        // Admission still verifies original artifacts. Attempt verification below deliberately does not.
        verifyInstallation()
        verifyDependencies(compilation.classpath, frozen.context.classpath)
        return PreparedJvmModelInput(this, frozen, selected, provider, profile)
    }

    internal fun requireProvider(provider: KotlinJvmCompilerProvider) {
        modelCheck(provider.inputMode == KotlinJvmInputMode.VERIFIED_MODEL && provider.usesInstallation(installation),
            "compiler_model_provider_mismatch", "Model preparation requires the same installation object and a fixed model-only provider route.")
    }

    internal fun requireOriginalInput(input: AnalysisInput, requireFinal: Boolean = false) {
        modelCheck(input.snapshot == model.snapshot && input.execution == null && input.outputRoot == null,
            "compiler_model_input_mismatch", "Preparation must retain the whole model snapshot without injected execution/output.")
        normalizedAbsolute(input.materializedRoot)
        originalArgumentLayout?.let { layout -> modelCheck(input.materializedRoot == layout.originalSourceRoot,
            "compiler_model_original_source_origin_mismatch", "Preparation must retain the exact registered original source root.") }
        val basePolicies = originalContext.policies
        val additions = input.context.policies.keys - basePolicies.keys
        modelCheck(input.context == originalContext.copy(policies = input.context.policies) &&
            basePolicies.all { (key, value) -> input.context.policies[key] == value } &&
            additions.all { it in CONTROLLER_POLICIES }, "compiler_model_context_mismatch",
            "Imported scope, model, arguments, classpath, configuration, membership and ingress policies cannot be rewritten.")
        if (requireFinal) {
            modelCheck(additions == CONTROLLER_POLICIES &&
                input.context.policies[CATALOG_POLICY]?.let(Digests::valid) == true &&
                !input.context.policies[ACTIVATION_POLICY].isNullOrBlank(), "compiler_model_preparation_incomplete",
                "Final owner preparation must retain its catalog, activation and full execution-plan identities.")
        }
    }

    companion object {
        const val SCHEMA = "ktchecker/jvm-model-binding-1"
        const val ORIGINAL_ARGV_SCHEMA = "ktchecker/jvm-model-binding-original-argv-2"
        const val PROFILE_PROPERTY = "-Dktchecker.compiler.modelBinding="
        private const val CATALOG_POLICY = "ktchecker.catalog-inventory"
        private const val ACTIVATION_POLICY = "ktchecker.activation-plan"
        private const val EXECUTION_POLICY = "ktchecker.execution-plan"
        private val CONTROLLER_POLICIES = setOf(CATALOG_POLICY, ACTIVATION_POLICY, EXECUTION_POLICY)

        /** A decoded model, artifact view, flag, or digest pair cannot call this as a live model. */
        fun verifyAndBind(
            model: VerifiedProjectModel,
            compilationId: String,
            installation: KotlinJvmToolchain,
            checkpoint: () -> Unit = {},
        ): KotlinJvmModelBinding {
            checkpoint()
            val verified = model.context(compilationId, model.identity.configurationDigest)
            val compilation = verified.compilation
                ?: modelFailure("compiler_model_compilation_unavailable", "No exact live compilation capability is available.")
            val context = verified.context
                ?: modelFailure("compiler_model_context_unavailable", "No verified compiler context is available.")
            modelCheck(verified.completeness == ModelCompleteness.COMPLETE && verified.certificateId != null &&
                model.issues.isEmpty() && context.fullProject && model.snapshot.scopeExclusions.isEmpty() &&
                model.snapshot.unknownInputs.isEmpty(), "compiler_model_incomplete", "Incomplete model domains cannot establish JVM execution semantics.")
            val chain = compilation.toolchain
                ?: modelFailure("compiler_model_toolchain_unavailable", "The compilation has no verified toolchain.")
            modelCheck(compilation.platform == "jvm" && chain.kind == "jvm", "compiler_model_platform_unsupported",
                "Only the actual exported JVM platform/kind is supported; target names grant no platform authority.")
            modelCheck(chain.compilerVersion == CompilerRuleRegistry.VERSION && context.compilerVersion == CompilerRuleRegistry.VERSION,
                "compiler_model_version_unsupported", "The model must retain the pinned Kotlin 2.4.21 compiler version.")
            modelCheck(chain.compilerPlugins.isEmpty() && compilation.friendArtifacts.isEmpty() &&
                compilation.auxiliaryArtifacts.isEmpty() && compilation.auxiliaryInputFiles.isEmpty() &&
                compilation.associatedCompilationIds.isEmpty(), "compiler_model_auxiliary_inputs_unsupported",
                "Plugins, friend/associated compilations and auxiliary inputs require separately verified execution semantics.")
            modelCheck(compilation.sourceSetIds.all { model.sourceSet(it)?.dependsOn?.isEmpty() == true } &&
                compilation.sourceFiles.all { it.path.endsWith(".kt") }, "compiler_model_source_roles_unsupported",
                "Common/actual refinement and Java source roles are not inferred from a flat JVM source list.")
            val layout = if (compilation.jvmArgumentRoles == null) {
                modelCheck(model.jvmArgumentLayout(compilationId) == null && pathFreeModelArguments(compilation.compilerArguments),
                    "compiler_model_arguments_unsupported", "Absent original role facts retain the strict historical scalar-only path.")
                null
            } else model.jvmArgumentLayout(compilationId)
                ?: modelFailure("compiler_model_arguments_unsupported", "No live importer/owner-verified total original-argument layout is available.")
            layout?.let { checked ->
                modelCheck(checked.originalArguments == compilation.compilerArguments && checked.renderOriginal() == compilation.compilerArguments &&
                    checked.modelSourceFiles == compilation.sourceFiles &&
                    checked.executableCompilationFiles == compilation.jvmArgumentRoles?.sourcePaths &&
                    checked.executableSourceFiles.size == compilation.sourceFiles.size &&
                    checked.executableSourceFiles == checked.executableCompilationFiles.map { path ->
                        compilation.sourceFiles.singleOrNull { it.path == path }
                    },
                    "compiler_model_argument_layout_mismatch", "Typed execution order must retain every exact original source/argument occurrence.")
                modelCheck(compilation.projectDependencies.isEmpty() && model.export.generatedInputs.none { generated ->
                    generated.sourceSetIds.any { it in compilation.sourceSetIds }
                }, "compiler_model_argument_provenance_unsupported",
                    "The original-argv adapter does not infer generated sources or project-produced classpath provenance.")
                modelCheck(chain.compilerArtifacts.singleOrNull {
                    it.coordinate == "org.jetbrains.kotlin:kotlin-compiler-embeddable:${CompilerRuleRegistry.VERSION}"
                }?.digest == VerifiedJvmArgumentLayout.COMPILER_DIGEST, "compiler_model_argument_compiler_unverified",
                    "Original-argument normalization requires the exact reviewed pinned compiler, not a version label.")
            }
            val normalizedOptions = layout?.normalizedCompilerOptions ?: compilation.compilerArguments
            try { CompilerFlags.validate(normalizedOptions) }
            catch (_: IllegalArgumentException) { modelFailure("compiler_model_arguments_unsupported", "The provider does not accept the original argument tokens.") }
            catch (_: IllegalStateException) { modelFailure("compiler_model_arguments_unsupported", "The provider does not accept the original argument tokens.") }
            for ((flag, value) in listOf("-language-version" to compilation.languageVersion, "-api-version" to compilation.apiVersion)) {
                val positions = compilation.compilerArguments.indices.filter { compilation.compilerArguments[it] == flag }
                modelCheck(value == null || positions.lastOrNull()?.let { compilation.compilerArguments.getOrNull(it + 1) } == value,
                    "compiler_model_language_options_mismatch", "Exported language/API selection must agree with the actual original arguments.")
            }
            CompilerSnapshot.compilationPaths(AnalysisInput(model.snapshot, "/unused-model-identity", context))
            verifyModelInstallation(installation, checkpoint)
            val view = installation.installationView()
            layout?.let { modelCheck(it.selectedJdkHome == view.runtime.root.toString(), "compiler_model_argument_jdk_mismatch",
                "Original optional JDK operand and exported task selection must bind the identical installed full JDK image.") }
            modelCheck(chain.compilerArtifacts.size == view.compilerClasspath.size && chain.compilerArtifacts.isNotEmpty(),
                "compiler_model_compiler_artifacts_mismatch", "Installed and modeled ordered compiler JAR domains differ.")
            modelCheck(chain.compilerArtifacts.count { it.coordinate == "org.jetbrains.kotlin:kotlin-compiler-embeddable:${CompilerRuleRegistry.VERSION}" } == 1,
                "compiler_model_compiler_coordinate_unverified", "A filename alone does not establish the resolved pinned compiler artifact.")
            chain.compilerArtifacts.zip(view.compilerClasspath).forEach { (declared, installed) ->
                checkpoint()
                modelCheck(declared.path == installed.identity.path && declared.digest == installed.identity.digest && declared.size == installed.size,
                    "compiler_model_compiler_artifacts_mismatch", "Compiler JAR path/bytes/size/order must match without relocation or sorting.")
            }
            val runtimeFiles = view.runtime.entries.filter { it.kind == JvmRuntimeEntryKind.FILE }.associateBy { it.relativePath }
            val runtimeNames = chain.runtimeArtifacts.map { it.coordinate.removePrefix("compilation-jdk:") }
            modelCheck(chain.runtimeArtifacts.all { it.coordinate.startsWith("compilation-jdk:") } &&
                runtimeNames.size == runtimeNames.toSet().size && runtimeNames.toSet() == runtimeFiles.keys,
                "compiler_model_runtime_domain_mismatch", "The model runtime must be the exact complete file projection of the selected JDK image.")
            chain.runtimeArtifacts.zip(runtimeNames).forEach { (declared, relative) ->
                checkpoint()
                val actual = runtimeFiles.getValue(relative)
                modelCheck(declared.path == view.runtime.root.resolve(relative).toString() &&
                    declared.digest == actual.digest && declared.size == actual.size, "compiler_model_runtime_artifact_mismatch",
                    "Modeled runtime path/bytes/size do not match the selected full runtime image.")
            }
            verifyDependencies(compilation.classpath, context.classpath, checkpoint)
            verifyModelInstallation(installation, checkpoint)
            val modelContextDigest = jsonDigest(ContractJson.encodeToJsonElement(context))
            val modelToolchainDigest = context.toolchainDigest
                ?: modelFailure("compiler_model_toolchain_unavailable", "The model has no exact per-compilation toolchain identity.")
            val relation = buildJsonObject {
                put("schema", if (layout == null) SCHEMA else ORIGINAL_ARGV_SCHEMA)
                layout?.let {
                    put("originalArgumentLayout", it.digest)
                    put("normalizedCompilerOptions", ContractJson.encodeToJsonElement(it.normalizedCompilerOptions))
                    put("executableCompilationFiles", ContractJson.encodeToJsonElement(it.executableCompilationFiles))
                }
                put("archiveBinding", "ktchecker/kernel-regular-jar-archive-1")
                put("mode", KotlinJvmInputMode.VERIFIED_MODEL.name)
                put("namespace", ContractJson.encodeToJsonElement(model.namespace))
                put("modelIdentity", ContractJson.encodeToJsonElement(model.identity))
                put("ingressCertificate", model.ingressCertificate)
                put("compilationId", compilationId)
                put("modelContext", modelContextDigest)
                put("modelToolchain", modelToolchainDigest)
                put("installedToolchain", installation.digest)
                put("runtimeImage", view.runtime.digest)
                putJsonArray("compilerArtifacts") { chain.compilerArtifacts.zip(view.compilerClasspath).forEach { (declared, installed) ->
                    add(buildJsonObject {
                        put("model", ContractJson.encodeToJsonElement(declared))
                        put("installed", ContractJson.encodeToJsonElement(installed.identity))
                        put("ordinal", installed.ordinal); put("size", installed.size)
                    })
                } }
                put("compilationFiles", ContractJson.encodeToJsonElement(compilation.sourceFiles))
                put("compilerArguments", ContractJson.encodeToJsonElement(compilation.compilerArguments))
                put("dependencies", ContractJson.encodeToJsonElement(compilation.classpath))
            }
            return KotlinJvmModelBinding(model, compilationId, installation, compilation, context, layout,
                JvmModelBindingIdentity(jsonDigest(relation), compilationId, modelContextDigest, modelToolchainDigest,
                    installation.digest, view.runtime.digest))
        }
    }
}

/** Exact retained owner input. This does not carry a client's admission approval or restore one. */
class PreparedJvmModelInput internal constructor(
    private val binding: KotlinJvmModelBinding,
    original: AnalysisInput,
    compilerRuleIds: List<String>,
    internal val provider: KotlinJvmCompilerProvider,
    compilerProfile: TrustedExecution,
) {
    val original: AnalysisInput = freezeJvmInput(original)
    val originalAnalysisKey: String = this.original.analysisKey()
    val compilerRuleIds: List<String> = Collections.unmodifiableList(compilerRuleIds.toList())
    val compilerProfile: TrustedExecution = freezeJvmProfile(compilerProfile)
    val bindingIdentity: JvmModelBindingIdentity get() = binding.identity

    /**
     * Only the trusted kernel observer may supply this callback/tuple. The callback verifies the
     * entire private archive. This fixed regular-JAR layout is not a general path-rewriting API.
     */
    fun bindAttempt(
        kernelOriginal: AnalysisInput,
        kernelEffective: AnalysisInput,
        compilerChild: AnalysisInput,
        verifyFrozen: () -> Unit,
    ): KotlinJvmModelPermit {
        val originalArgument = freezeJvmInput(kernelOriginal)
        val effective = freezeJvmInput(kernelEffective)
        val child = freezeJvmInput(compilerChild)
        modelCheck(originalArgument == original && originalArgument.analysisKey() == originalAnalysisKey,
            "compiler_model_original_mismatch", "Kernel original differs from the complete retained owner input.")
        binding.requireProvider(provider)
        requireJvmModelArchiveRelocation(original, effective)
        modelCheck(child == effective.copy(requiredRuleIds = compilerRuleIds, execution = compilerProfile),
            "compiler_model_child_mismatch", "Provider child differs from the exact retained model-mode route/profile projection.")
        verifyFrozen()
        binding.verifyInstallation()
        // Only frozen dependency paths are read here, NEVER the original model dependency paths.
        verifyDependencies(binding.compilation.classpath, effective.context.classpath)
        verifyFrozen()
        return KotlinJvmModelPermit(binding, this, child, verifyFrozen)
    }
}

/**
 * Shared, pure whole-input archive relation. This is not a model, application, or execution grant.
 * Both fixed cold and live model routes apply this SAME relation before reading any archive file.
 */
internal fun requireJvmModelArchiveRelocation(original: AnalysisInput, effective: AnalysisInput) {
    val root = normalizedAbsolute(effective.materializedRoot)
    modelCheck(root.fileName?.toString() == "sources" && root.parent != null && effective.outputRoot != null,
        "compiler_model_archive_layout_mismatch", "Expected the kernel's exact private source archive and per-attempt output parent.")
    val output = normalizedAbsolute(requireNotNull(effective.outputRoot))
    modelCheck(!output.startsWith(root.parent) && !root.parent.startsWith(output),
        "compiler_model_output_overlap", "Attempt output must be separate from the whole source/dependency archive.")
    modelCheck(effective.copy(materializedRoot = original.materializedRoot,
        context = effective.context.copy(classpath = original.context.classpath), outputRoot = original.outputRoot) == original,
        "compiler_model_effective_mismatch", "Only exact kernel source/dependency paths and output parent may change.")
    modelCheck(effective.context.classpath.size == original.context.classpath.size,
        "compiler_model_classpath_mapping_mismatch", "The archive cannot change dependency count/order.")
    original.context.classpath.zip(effective.context.classpath).forEachIndexed { index, (before, after) ->
        val expectedPath = root.parent.resolve("classpath").resolve("artifact-$index.jar").toString()
        modelCheck(after == before.copy(path = expectedPath), "compiler_model_classpath_mapping_mismatch",
            "Every dependency must retain its ordinal, coordinate and digest at its exact private archive location.")
    }
}

/** Per-attempt live capability. Owner registries must discard/close it when the attempt ends. */
class KotlinJvmModelPermit internal constructor(
    private val binding: KotlinJvmModelBinding,
    private val prepared: PreparedJvmModelInput,
    input: AnalysisInput,
    private val verifyFrozen: () -> Unit,
) : AutoCloseable {
    private val input = freezeJvmInput(input)
    private val active = AtomicBoolean(true)
    val expectedAnalysisKey: String = this.input.analysisKey()
    val bindingIdentity: JvmModelBindingIdentity get() = binding.identity

    /** Pure lookup check; malformed/foreign requests cannot trigger the authority callback. */
    fun accepts(input: AnalysisInput): Boolean = active.get() && input == this.input && input.analysisKey() == expectedAnalysisKey

    internal fun matches(provider: KotlinJvmCompilerProvider, input: AnalysisInput): Boolean =
        provider === prepared.provider && provider.inputMode == KotlinJvmInputMode.VERIFIED_MODEL &&
            provider.usesInstallation(binding.installation) && accepts(input)

    internal fun verify(provider: KotlinJvmCompilerProvider, input: AnalysisInput, checkpoint: () -> Unit) {
        modelCheck(matches(provider, input), "compiler_model_attempt_mismatch", "Model authority is revoked or belongs to another exact provider attempt.")
        checkpoint()
        verifyFrozen()
        binding.verifyInstallation(checkpoint)
        modelCheck(matches(provider, input), "compiler_model_attempt_mismatch", "Model authority changed while verifying this attempt.")
    }

    override fun close() { active.set(false) }
}

fun interface KotlinJvmModelAuthority {
    fun authorize(input: AnalysisInput): KotlinJvmModelPermit?
    companion object { val DENY = KotlinJvmModelAuthority { null } }
}

/** Owner in-process convenience only; equality of the serialized input cannot create a permit. */
class ExactKotlinJvmModelAuthority(private val permit: KotlinJvmModelPermit) : KotlinJvmModelAuthority {
    override fun authorize(input: AnalysisInput): KotlinJvmModelPermit? = permit.takeIf { it.accepts(input) }
}

/**
 * Workbench's preexisting limited-input label is NOT verified-model ingress. This exact
 * backwards-compatible shape may use only an owner-installed LEGACY route and can never
 * inherit qualification. Unknown model labels/keys, completeness, or binding properties
 * remain model-bearing. A VERIFIED_MODEL provider never uses this predicate to downgrade.
 */
internal fun legacyDeclaredSingleCompilation(input: AnalysisInput): Boolean =
    input.context.policies["ktchecker.model"] == "owner-declared-single-compilation" &&
        input.context.policies["ktchecker.coverage"] == "partial-project" && !input.context.fullProject &&
        input.context.policies.keys.none { it != "ktchecker.model" && it.startsWith("ktchecker.model") } &&
        input.execution?.arguments?.any { it.startsWith(KotlinJvmModelBinding.PROFILE_PROPERTY) } != true

internal fun modelBearingInput(input: AnalysisInput): Boolean =
    input.execution?.arguments?.any { it.startsWith(KotlinJvmModelBinding.PROFILE_PROPERTY) } == true ||
        input.context.policies.keys.any { it.startsWith("ktchecker.model") } && !legacyDeclaredSingleCompilation(input)

/**
 * The importer anchored compiler paths at ingress. Preserve their ordinary no-link location
 * at each bridge check rather than inheriting the legacy leaf-only artifact reader's aliases.
 * Pre/post file-key comparison detects ordinary replacement during verification; this is still
 * cooperative disk checking, not protection from hostile same-UID/fully restored transient writes.
 */
private fun verifyModelInstallation(installation: KotlinJvmToolchain, checkpoint: () -> Unit) {
    val before = compilerPathState(installation, checkpoint)
    installation.verify(checkpoint)
    modelCheck(before == compilerPathState(installation, checkpoint), "compiler_model_compiler_path_changed",
        "Selected compiler locations changed while the installed bytes were being verified.")
}

private fun compilerPathState(installation: KotlinJvmToolchain, checkpoint: () -> Unit): Map<Path, String?> {
    val result = linkedMapOf<Path, String?>()
    fun record(path: Path, file: Boolean) {
        checkpoint()
        if (path in result) return
        val attributes = try { Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
        catch (_: IOException) { modelFailure("compiler_model_compiler_path_unsafe", "Selected compiler location is absent or unsafe.") }
        catch (_: SecurityException) { modelFailure("compiler_model_compiler_path_unsafe", "Selected compiler location cannot be verified.") }
        modelCheck(!attributes.isSymbolicLink && !attributes.isOther &&
            (if (file) attributes.isRegularFile else attributes.isDirectory), "compiler_model_compiler_path_unsafe",
            "Model-bound compiler paths require ordinary no-link ancestors and regular JAR files.")
        result[path] = attributes.fileKey()?.toString()
    }
    installation.compilerClasspath.forEach { artifact ->
        val supplied = normalizedAbsolute(artifact.path)
        var current = supplied.root ?: modelFailure("compiler_model_compiler_path_unsafe", "Selected compiler path must have an absolute root.")
        record(current, false)
        supplied.forEach { name -> current = current.resolve(name); record(current, current == supplied) }
    }
    return result
}

internal fun verifyDependencies(model: List<ModelArtifact>, artifacts: List<ArtifactIdentity>, checkpoint: () -> Unit = {}) {
    modelCheck(model.size == artifacts.size, "compiler_model_dependency_mismatch", "Dependency domains differ.")
    model.zip(artifacts).forEach { (declared, actual) ->
        checkpoint()
        modelCheck(declared.digest != null && declared.size != null && actual.coordinate == declared.coordinate &&
            actual.digest == declared.digest && Files.size(Path.of(actual.path)) == declared.size,
            "compiler_model_dependency_mismatch", "Dependency coordinate/bytes/size must remain exact at every ordinal.")
    }
    CompilerSnapshot.classpath(artifacts, checkpoint)
}

private fun pathFreeModelArguments(arguments: List<String>): Boolean {
    val flags = setOf("-progressive", "-Werror", "-nowarn") // Exact intersection with current controller/provider grammars.
    val values = mapOf("-language-version" to Regex("[0-9]+(?:\\.[0-9]+){1,2}"),
        "-api-version" to Regex("[0-9]+(?:\\.[0-9]+){1,2}"), "-jvm-target" to Regex("(?:1\\.8|[1-9][0-9]?)"),
        "-module-name" to Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}"))
    var index = 0
    while (index < arguments.size) {
        val argument = arguments[index++]
        if (argument in flags) continue
        val validator = values[argument] ?: return false
        val value = arguments.getOrNull(index++) ?: return false
        if (!validator.matches(value)) return false
    }
    return true
}

private fun normalizedAbsolute(value: String): Path {
    val path = Path.of(value)
    modelCheck(path.isAbsolute && path.normalize() == path && path.toString() == value,
        "compiler_model_path_invalid", "Owner paths must be explicit normalized absolute paths.")
    return path
}

internal fun freezeJvmProfile(profile: TrustedExecution): TrustedExecution = profile.copy(
    arguments = Collections.unmodifiableList(profile.arguments.toList()), environment = Collections.unmodifiableMap(profile.environment.toMap()))

internal fun freezeJvmProfiles(profiles: Map<String, TrustedExecution>): Map<String, TrustedExecution> =
    Collections.unmodifiableMap(profiles.toSortedMap().mapValues { freezeJvmProfile(it.value) })

internal fun freezeJvmInput(input: AnalysisInput): AnalysisInput = input.copy(
    snapshot = input.snapshot.copy(files = Collections.unmodifiableList(input.snapshot.files.map { it.copy() }),
        declaredRoots = Collections.unmodifiableList(input.snapshot.declaredRoots.toList()),
        scopeExclusions = Collections.unmodifiableList(input.snapshot.scopeExclusions.toList()),
        unknownInputs = Collections.unmodifiableList(input.snapshot.unknownInputs.toList())),
    context = input.context.copy(classpath = Collections.unmodifiableList(input.context.classpath.map { it.copy() }),
        compilerArguments = Collections.unmodifiableList(input.context.compilerArguments.toList()),
        compilationFiles = input.context.compilationFiles?.let { Collections.unmodifiableList(it.toList()) },
        libraries = Collections.unmodifiableMap(input.context.libraries.toMap()), policies = Collections.unmodifiableMap(input.context.policies.toMap())),
    requiredRuleIds = Collections.unmodifiableList(input.requiredRuleIds.toList()),
    changedPaths = Collections.unmodifiableList(input.changedPaths.toList()), execution = input.execution?.let(::freezeJvmProfile))

private fun jsonDigest(value: JsonElement): String = Digests.sha256(Digests.canonical(value))
private fun modelCheck(valid: Boolean, code: String, message: String) { if (!valid) modelFailure(code, message) }
private fun modelFailure(code: String, message: String): Nothing = throw KotlinJvmModelException(code, message)

package dev.ktchecker.providers.detekt

import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.*
import dev.ktchecker.providers.runtime.ExecutionAuthority
import dev.ktchecker.providers.runtime.ExecutionPermit
import dev.ktchecker.providers.runtime.ExecutionProblem
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*

/** Installed by the owner, never selected by a request, policy tag, or profile property. */
enum class DetektInputMode { LEGACY_EXPLICIT_INPUT, VERIFIED_MODEL_LIGHT }

/** Auditable relation only. Copying this data cannot recreate its live model/attempt grant. */
class DetektModelBindingIdentity internal constructor(
    val digest: String,
    val compilationId: String,
    val modelContextDigest: String,
    val producedGraphDigest: String?,
)

/**
 * LIGHT observes syntax in an exact imported JVM compilation. It does NOT reinterpret the
 * original compiler argv, resolve types, qualify the 2.4.21/2.4.10 parser tuple, or discharge a
 * deferred producer obligation. Model identity, scope (including target=""), fullProject and
 * all original compiler arguments stay unchanged through parent and provider-child projection.
 */
class DetektVerifiedModelBinding private constructor(
    val model: VerifiedProjectModel,
    val compilationId: String,
    val identity: DetektModelBindingIdentity,
    internal val provider: DetektProvider,
) {
    companion object {
        const val SCHEMA = "ktchecker/detekt-verified-model-light/1"
        const val PROFILE_PROPERTY = "-Dktchecker.detekt.modelBinding="
        private val CONTROLLER_POLICIES = setOf("ktchecker.catalog-inventory", "ktchecker.activation-plan", "ktchecker.execution-plan")

        /**
         * Owner retain-for-admission hook after final activation capture. This function receives
         * the SAME importer object and the WHOLE parent, not a narrowed synthetic replacement.
         * The caller supplies its independently selected rule vector; JSON activation is identity,
         * not authority. The child profile lives here, NEVER in the live compiler's empty parent
         * execution-plan map. No client/RPC can enroll a model or execution permit with a digest.
         */
        fun prepare(
            model: VerifiedProjectModel,
            compilationId: String,
            original: AnalysisInput,
            provider: DetektProvider,
            detektRuleIds: List<String>,
            timeoutMillis: Long = 60_000,
            maxOutputBytes: Int = 1_048_576,
        ): PreparedDetektModelInput {
            detektModelCheck(provider.inputMode == DetektInputMode.VERIFIED_MODEL_LIGHT,
                "detekt_model_provider_mismatch", "Use the fixed owner-installed model-only LIGHT provider.")
            val parent = freezeDetektInput(original)
            val selected = immutableDetektList(detektRuleIds)
            val verified = model.context(compilationId, model.identity.configurationDigest)
            val compilation = verified.compilation
                ?: detektModelFailure("detekt_model_compilation_unavailable", "No exact actual compilation was imported.")
            val context = verified.context
                ?: detektModelFailure("detekt_model_context_unavailable", "The imported compilation context is unavailable.")
            // This field is an importer-created capability, not a decoded issue name or flag.
            val produced = model.jvmProducedGraph
            val pending = model.issues.isNotEmpty() && produced != null &&
                produced.modelDigest == model.identity.modelDigest && compilationId in produced.compilationIds &&
                model.compilationIds.toSet() == produced.compilationIds.toSet() &&
                model.issues == listOf(ModelIssue("jvm_produced_output_deferred", produced.consumerCompilationId,
                    "Deferred JVM output from ${produced.reference.producerCompilationId} at classpath ordinal ${produced.reference.classpathIndex}."))
            detektModelCheck((model.issues.isEmpty() && verified.completeness == ModelCompleteness.COMPLETE &&
                verified.certificateId != null && context.fullProject) ||
                (pending && verified.completeness == ModelCompleteness.UNAVAILABLE && verified.certificateId == null && !context.fullProject),
                "detekt_model_incomplete", "Only complete imported inputs or the SAME exact deferred-output graph may project syntax.")
            detektModelCheck(model.snapshot.unknownInputs.isEmpty() && model.snapshot.scopeExclusions.isEmpty(),
                "detekt_model_incomplete", "Unknown or excluded inputs are not discharged by syntax analysis.")
            val chain = compilation.toolchain
                ?: detektModelFailure("detekt_model_toolchain_unavailable", "The actual modeled toolchain is unavailable.")
            detektModelCheck(compilation.platform == "jvm" && chain.kind == "jvm",
                "detekt_model_platform_unsupported", "An actual JVM platform/kind is required; target spelling is not a platform grant.")
            detektModelCheck(chain.compilerVersion == "2.4.21" && context.compilerVersion == "2.4.21" &&
                context.toolchainDigest?.let(Digests::valid) == true,
                "detekt_model_compiler_unsupported", "This unqualified LIGHT boundary retains the modeled Kotlin 2.4.21 compiler.")
            detektModelCheck(chain.compilerPlugins.isEmpty() && compilation.auxiliaryArtifacts.isEmpty() &&
                compilation.auxiliaryInputFiles.isEmpty() && compilation.projectDependencies.isEmpty() &&
                (pending || (compilation.friendArtifacts.isEmpty() && compilation.associatedCompilationIds.isEmpty())) &&
                model.export.generatedInputs.none { generated -> generated.sourceSetIds.any { it in compilation.sourceSetIds } },
                "detekt_model_auxiliary_inputs_unsupported", "Plugins, Java/auxiliary/generated inputs and unproved compilation edges are outside this syntax slice.")
            val sourceSets = compilation.sourceSetIds.map { id -> model.sourceSet(id)
                ?: detektModelFailure("detekt_model_source_sets_unavailable", "An exact compilation source set is missing.") }
            detektModelCheck(sourceSets.isNotEmpty() && sourceSets.all { it.dependsOn.isEmpty() && it.resources.isEmpty() } &&
                compilation.sourceFiles.isNotEmpty() && compilation.sourceFiles.all { it.path.endsWith(".kt") } &&
                sourceSets.flatMap { it.sources }.sortedBy { it.path } == compilation.sourceFiles.sortedBy { it.path },
                "detekt_model_source_roles_unsupported", "Only the exact nonempty Kotlin-only source-set union is supported; no refinement or foreign file is inferred.")
            // Original role facts, when present, require the SAME importer-checked total layout.
            // Neither this checked layout nor scalar legacy arguments are forwarded to Detekt.
            if (compilation.jvmArgumentRoles != null) {
                val layout = model.jvmArgumentLayout(compilationId)
                    ?: detektModelFailure("detekt_model_argument_origin_unavailable", "Original role facts require their live checked importer layout.")
                detektModelCheck(layout.originalArguments == compilation.compilerArguments &&
                    layout.renderOriginal() == compilation.compilerArguments && layout.modelSourceFiles == compilation.sourceFiles &&
                    layout.originalSourceRoot == parent.materializedRoot,
                    "detekt_model_argument_layout_mismatch", "Original source origin, complete argv and modeled source order must stay exact.")
            }
            detektModelCheck(parent.snapshot == model.snapshot && parent.execution == null && parent.outputRoot == null,
                "detekt_model_input_mismatch", "Retain the whole imported snapshot without injected execution or output paths.")
            detektNormalizedAbsolute(parent.materializedRoot)
            val additions = parent.context.policies.keys - context.policies.keys
            detektModelCheck(parent.context == context.copy(policies = parent.context.policies) &&
                context.policies.all { (key, value) -> parent.context.policies[key] == value } && additions == CONTROLLER_POLICIES,
                "detekt_model_context_mismatch", "Imported scope, model, membership, classpath, argv and ingress policies cannot be rewritten.")
            detektModelCheck(parent.context.policies["ktchecker.catalog-inventory"]?.let(Digests::valid) == true &&
                !parent.context.policies["ktchecker.activation-plan"].isNullOrBlank() &&
                parent.context.policies["ktchecker.execution-plan"] == Digests.sha256("{}"),
                "detekt_model_parent_profiles_mismatch", "Final owner activation/catalog and the unchanged EMPTY parent execution-plan map are required.")
            detektModelCheck(parent.requiredRuleIds.isNotEmpty() && parent.requiredRuleIds.distinct().size == parent.requiredRuleIds.size &&
                selected.isNotEmpty() && selected.distinct().size == selected.size &&
                selected.all { it in provider.mappedRuleIds } && parent.requiredRuleIds.filter { it in selected } == selected,
                "detekt_model_rule_projection_invalid", "The owner-selected Detekt vector must be an exact ordered subset of the whole parent.")
            detektModelCheck(timeoutMillis in 1..3_600_000 && maxOutputBytes in 1..16_777_216,
                "detekt_model_execution_limits_invalid", "The exact child profile needs valid explicit limits.")
            val contextDigest = detektJsonDigest(ContractJson.encodeToJsonElement(context))
            val relation = buildJsonObject {
                put("schema", SCHEMA); put("mode", DetektInputMode.VERIFIED_MODEL_LIGHT.name)
                put("namespace", ContractJson.encodeToJsonElement(model.namespace))
                put("modelIdentity", ContractJson.encodeToJsonElement(model.identity))
                put("ingressCertificate", model.ingressCertificate); put("compilationId", compilationId)
                put("modelContext", contextDigest); put("wholeParent", parent.analysisKey())
                put("selectedRuleIds", ContractJson.encodeToJsonElement(selected))
                put("projectCompiler", "2.4.21"); put("embeddedParser", DETEKT_EMBEDDED_KOTLIN)
                put("parserCompatibilityQualified", false); put("semanticArgumentsForwarded", false)
                put("archiveBinding", "ktchecker/kernel-regular-jar-archive-1")
                if (pending) put("deferredProducedGraph", requireNotNull(produced).digest)
            }
            val identity = DetektModelBindingIdentity(detektJsonDigest(relation), compilationId, contextDigest,
                if (pending) requireNotNull(produced).digest else null)
            val binding = DetektVerifiedModelBinding(model, compilationId, identity, provider)
            val plan = provider.modelExecutionPlan(parent.copy(requiredRuleIds = selected), identity, timeoutMillis, maxOutputBytes)
            detektModelCheck(plan.analysisMode == "light" && plan.activeRuleIds.isNotEmpty() && plan.activeRuleIds.all {
                DetektCatalog.byId[it]?.let { rule -> rule.exact && rule.depth == "syntax" } == true
            }, "detekt_model_no_light_rules_active", "No exact owner-enabled LIGHT syntax rule is executable; do not launch an empty or semantic route.")
            return PreparedDetektModelInput(binding, parent, selected, plan)
        }
    }
}

/** Not an admission approval. Retain only in the authenticated owner's live plan lifecycle. */
class PreparedDetektModelInput internal constructor(
    internal val binding: DetektVerifiedModelBinding,
    original: AnalysisInput,
    detektRuleIds: List<String>,
    internal val plan: DetektExecutionPlan,
) {
    val original: AnalysisInput = freezeDetektInput(original)
    val originalAnalysisKey: String = this.original.analysisKey()
    val detektRuleIds: List<String> = immutableDetektList(detektRuleIds)
    val detektProfile: TrustedExecution = freezeDetektProfile(plan.execution)
    val bindingIdentity: DetektModelBindingIdentity get() = binding.identity

    /** Trusted kernel archive observer only; an arbitrary callback/JSON tuple is NOT that proof. */
    fun bindAttempt(kernelOriginal: AnalysisInput, kernelEffective: AnalysisInput, detektChild: AnalysisInput,
                    verifyFrozen: () -> Unit): DetektModelPermit {
        val received = freezeDetektInput(kernelOriginal)
        val effective = freezeDetektInput(kernelEffective)
        val child = freezeDetektInput(detektChild)
        detektModelCheck(received == original && received.analysisKey() == originalAnalysisKey,
            "detekt_model_original_mismatch", "The kernel original differs from the complete retained owner input.")
        requireDetektModelArchiveRelocation(original, effective)
        detektModelCheck(child == effective.copy(requiredRuleIds = detektRuleIds, execution = detektProfile),
            "detekt_model_child_mismatch", "Only the exact retained Detekt rule/profile projection may become this provider child.")
        verifyFrozen()
        val current = binding.provider.modelExecutionPlan(child, binding.identity, detektProfile.timeoutMillis, detektProfile.maxOutputBytes)
        detektModelCheck(current == plan, "detekt_model_profile_changed", "The installed configuration/artifacts/profile changed before this attempt.")
        verifyFrozen()
        return DetektModelPermit(this, child, verifyFrozen)
    }
}

/** SAME kernel regular-JAR relation as the compiler bridge; not a generic relocation capability. */
internal fun requireDetektModelArchiveRelocation(original: AnalysisInput, effective: AnalysisInput) {
    val root = detektNormalizedAbsolute(effective.materializedRoot)
    detektModelCheck(root.fileName?.toString() == "sources" && root.parent != null && effective.outputRoot != null,
        "detekt_model_archive_layout_mismatch", "Expected exact private sources and a per-attempt output parent.")
    val output = detektNormalizedAbsolute(requireNotNull(effective.outputRoot))
    detektModelCheck(!output.startsWith(root.parent) && !root.parent.startsWith(output),
        "detekt_model_output_overlap", "Outputs must be disjoint from the complete private source/dependency archive.")
    detektModelCheck(effective.copy(materializedRoot = original.materializedRoot,
        context = effective.context.copy(classpath = original.context.classpath), outputRoot = original.outputRoot) == original,
        "detekt_model_effective_mismatch", "Only exact kernel source/classpath paths and output parent may change.")
    detektModelCheck(effective.context.classpath.size == original.context.classpath.size,
        "detekt_model_classpath_mapping_mismatch", "The archive cannot change dependency count/order.")
    original.context.classpath.zip(effective.context.classpath).forEachIndexed { index, (before, after) ->
        detektModelCheck(after == before.copy(path = root.parent.resolve("classpath/artifact-$index.jar").toString()),
            "detekt_model_classpath_mapping_mismatch", "Every available dependency retains its exact ordinal, coordinate and digest.")
    }
}

/** Close on owner attempt finish, failure or cancellation; revoked grants can never be replayed. */
class DetektModelPermit internal constructor(
    private val prepared: PreparedDetektModelInput,
    input: AnalysisInput,
    private val verifyFrozen: () -> Unit,
) : AutoCloseable {
    private val input = freezeDetektInput(input)
    private val active = AtomicBoolean(true)
    val expectedAnalysisKey: String = this.input.analysisKey()
    val bindingIdentity: DetektModelBindingIdentity get() = prepared.bindingIdentity
    internal val plan: DetektExecutionPlan get() = prepared.plan
    val executionPermit = ExecutionPermit(prepared.detektProfile, this.input.snapshot.snapshotId,
        Path.of(this.input.materializedRoot), expectedAnalysisKey, { verify(prepared.binding.provider, this.input) })

    fun accepts(input: AnalysisInput): Boolean = active.get() && input == this.input && input.analysisKey() == expectedAnalysisKey

    internal fun matches(provider: DetektProvider, input: AnalysisInput): Boolean =
        provider === prepared.binding.provider && provider.inputMode == DetektInputMode.VERIFIED_MODEL_LIGHT && accepts(input)

    internal fun verify(provider: DetektProvider, input: AnalysisInput) {
        detektModelCheck(matches(provider, input), "detekt_model_attempt_mismatch", "The live grant was revoked or belongs to another provider/input.")
        verifyFrozen()
        val current = provider.modelExecutionPlan(this.input, bindingIdentity, plan.execution.timeoutMillis, plan.execution.maxOutputBytes)
        detektModelCheck(current == plan, "detekt_model_profile_changed", "The exact installed model LIGHT plan no longer matches.")
        verifyFrozen()
        detektModelCheck(matches(provider, input), "detekt_model_attempt_mismatch", "The grant changed while its archive/installation were verified.")
    }

    override fun close() { active.set(false) }
}

fun interface DetektModelAuthority {
    fun authorize(input: AnalysisInput): DetektModelPermit?
    companion object { val DENY = DetektModelAuthority { null } }
}

class ExactDetektModelAuthority(private val permit: DetektModelPermit) : DetektModelAuthority {
    override fun authorize(input: AnalysisInput): DetektModelPermit? = permit.takeIf { it.accepts(input) }
}

/** Convenience for the SAME grant; a separately equal ExecutionPermit is intentionally refused. */
class ExactDetektModelExecutionAuthority(private val permit: DetektModelPermit) : ExecutionAuthority {
    override fun authorize(input: AnalysisInput): ExecutionPermit? = permit.executionPermit.takeIf { permit.accepts(input) }
}

internal fun detektModelBearingInput(input: AnalysisInput): Boolean {
    // Either nominal model marker is a one-way refusal on the legacy route, even if the
    // caller erases model policies. Recognition grants neither FULL nor LIGHT authority.
    val profileBound = input.execution?.arguments?.any {
        it.startsWith(DetektVerifiedModelBinding.PROFILE_PROPERTY) ||
            it.startsWith(DetektVerifiedFullModelBinding.PROFILE_PROPERTY)
    } == true
    val policies = input.context.policies
    val legacyDeclared = policies["ktchecker.model"] == "owner-declared-single-compilation" &&
        policies["ktchecker.coverage"] == "partial-project" && !input.context.fullProject &&
        policies.keys.none { it != "ktchecker.model" && it.startsWith("ktchecker.model") } && !profileBound
    return profileBound || policies.keys.any { it.startsWith("ktchecker.model") } && !legacyDeclared
}

internal fun <T> immutableDetektList(values: Collection<T>): List<T> = Collections.unmodifiableList(values.toList())
internal fun freezeDetektProfile(profile: TrustedExecution): TrustedExecution = profile.copy(
    arguments = immutableDetektList(profile.arguments), environment = Collections.unmodifiableMap(profile.environment.toMap()))
internal fun freezeDetektInput(input: AnalysisInput): AnalysisInput = input.copy(
    snapshot = input.snapshot.copy(files = immutableDetektList(input.snapshot.files.map { it.copy() }),
        declaredRoots = immutableDetektList(input.snapshot.declaredRoots), scopeExclusions = immutableDetektList(input.snapshot.scopeExclusions),
        unknownInputs = immutableDetektList(input.snapshot.unknownInputs)),
    context = input.context.copy(classpath = immutableDetektList(input.context.classpath.map { it.copy() }),
        compilerArguments = immutableDetektList(input.context.compilerArguments),
        compilationFiles = input.context.compilationFiles?.let(::immutableDetektList),
        libraries = Collections.unmodifiableMap(input.context.libraries.toMap()), policies = Collections.unmodifiableMap(input.context.policies.toMap())),
    requiredRuleIds = immutableDetektList(input.requiredRuleIds), changedPaths = immutableDetektList(input.changedPaths),
    execution = input.execution?.let(::freezeDetektProfile))
private fun detektNormalizedAbsolute(value: String): Path = Path.of(value).also {
    detektModelCheck(it.isAbsolute && it.normalize() == it && it.toString() == value,
        "detekt_model_path_invalid", "Owner paths must be exact normalized absolute paths, not aliases.")
}
private fun detektJsonDigest(value: JsonElement): String = Digests.sha256(Digests.canonical(value))
internal fun detektModelCheck(valid: Boolean, code: String, message: String) { if (!valid) detektModelFailure(code, message) }
private fun detektModelFailure(code: String, message: String): Nothing = throw ExecutionProblem(code, message)

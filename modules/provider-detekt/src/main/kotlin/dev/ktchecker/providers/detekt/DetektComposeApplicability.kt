package dev.ktchecker.providers.detekt

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.ExecutionProblem
import dev.ktchecker.providers.runtime.fileDigest
import dev.ktchecker.providers.runtime.safeRelativePath
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*

/** A resolved JVM binary, never a SOURCE archive or a library/version string from a request. */
data class DetektComposeArtifact(val identity: ArtifactIdentity, val size: Long)

/**
 * Owner resolution result, not a capability by itself. Source-set IDs and compilation ID are
 * actual owner/importer identities, not names inferred from annotations, imports or folders.
 * Dependencies are the COMPLETE ordered compilation classpath, with independently selected
 * expected binary identities, not hashes first learned by reading untrusted request paths.
 */
data class DetektComposeCompilation(
    val compilationId: String,
    val scope: RuleContext,
    val platform: String,
    val sourceSetIds: List<String>,
    val sourceFiles: List<SnapshotFile>,
    val compilerVersion: String,
    val compilerArguments: List<String>,
    val dependencies: List<DetektComposeArtifact>,
)

/**
 * Installed trusted-owner code only. There is deliberately no request parameter to reflect.
 * Re-resolve the same actual compilation, source roles and repository/CAS-anchored binaries
 * on EVERY call. Returning serialized context.libraries/model tags is not such a resolution.
 * An importer-backed owner retains its live VerifiedProjectModel, not just its model digest.
 */
fun interface DetektComposeCompilationOwner { fun resolve(): DetektComposeCompilation }

/** Advertised published JVM binary pin. Presence here is NOT a verified installation. */
data class DetektComposeBinaryPin(val coordinate: String, val digest: String, val size: Long) {
    internal fun matches(artifact: DetektComposeArtifact): Boolean =
        artifact.identity.coordinate == coordinate && artifact.identity.digest == digest && artifact.size == size
}

/**
 * SOURCE-reviewed tuple selected from compose-rules v0.6.8's BOM 2026.09.00. The expected
 * sizes/SHA256s are from the exact published java-api JVM .module variants, NOT sources.jar.
 * No download/execution happens here. Live owner preparation must verify the actual bytes.
 * This does NOT qualify Detekt/embedded Kotlin2.4.10 against project Kotlin2.4.21.
 */
object DetektComposeLibraryPins {
    val runtime = DetektComposeBinaryPin("androidx.compose.runtime:runtime-desktop:1.12.1",
        "sha256:ed390d7bf7bdba951359d127f4c1ddd223f535f5a6a63078c5c5ac6b6924eb08", 1_948_637)
    val annotations = DetektComposeBinaryPin("androidx.compose.runtime:runtime-annotation-jvm:1.12.1",
        "sha256:e291a6ae01ab71a7f48a568ec9744c6bf9b3f46ef72fbcdeb28c3407ae531c5e", 9_379)
    val immutableCollections = DetektComposeBinaryPin("org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm:0.3.8",
        "sha256:72e9a9b0003ec5254b638186f7ca1675b08006bdde4c9c4c589970342ccd2e77", 246_751)
}

/** API families are interpretation obligations, not inferred semantic identities of PSI sites. */
enum class DetektComposeApiFamily {
    COMPOSITION, GENERIC_STATE, SCALAR_STATE, MOVABLE_CONTENT, RUNTIME_EFFECTS,
    PRIMITIVE_COLLECTION_STATE, LIFECYCLE_EFFECTS, CIRCUIT_EFFECTS, PROJECT_KEYED_EFFECTS,
    IMMUTABLE_CONTRACT, IMMUTABLE_COLLECTIONS,
    UNREVIEWED_RULE_SURFACE,
}

// No resolved-absence or resolved-site witness exists in this slice. UNKNOWN is not "absent"
// or "not applicable". Future widening must add a typed owner-bound absence/site relation,
// not universally require optional dependencies nor synthesize absence from this enum.
enum class DetektComposeApiState { REVIEWED_PRESENT, UNKNOWN }

/** Inert audit metadata; a copied state, digest or execution plan cannot mint a live grant. */
data class DetektComposeRuleApplicability(
    val apiStates: Map<DetektComposeApiFamily, DetektComposeApiState>,
    val executableFacets: Boolean,
    val completeApiSurface: Boolean,
    val reason: String?,
)

data class DetektComposeApplicability(
    val bindingDigest: String? = null,
    val rules: Map<String, DetektComposeRuleApplicability> = emptyMap(),
)

/** Only trusted owner code can install this; request profiles/tags cannot select an authority. */
fun interface DetektLibraryAuthority {
    fun authorize(input: AnalysisInput): DetektLibraryPermit?
    companion object { val DENY = DetektLibraryAuthority { null } }
}

class ExactDetektLibraryAuthority(private val permit: DetektLibraryPermit) : DetektLibraryAuthority {
    override fun authorize(input: AnalysisInput): DetektLibraryPermit? = permit.takeIf { it.accepts(input) }
}

/**
 * Nonserialized, provider-instance-bound, revocable applicability grant, NOT an execution grant.
 * The execution-null input is exact (including scope, membership, dependency order and paths,
 * options/policies, required vector and output root); only attaching an execution profile is
 * permitted later. Retain and close in the owner's attempt lifecycle, including cancellation.
 */
class DetektLibraryPermit private constructor(
    private val provider: DetektProvider,
    private val original: AnalysisInput,
    private val compilation: DetektComposeCompilation,
    private val owner: DetektComposeCompilationOwner,
    val bindingDigest: String,
) : AutoCloseable {
    private val active = AtomicBoolean(true)

    companion object {
        const val PROFILE_PROPERTY = "-Dktchecker.detekt.composeBinding="
        const val SCHEMA = "ktchecker/detekt-compose-applicability/1"

        /**
         * Structural/byte/owner failures throw. Missing/unsupported/unknown API surfaces instead
         * yield per-rule unavailable metadata so they remain visible, never pass or disappear.
         * Use the SAME provider for prepare, planning and analyze, with separately installed
         * execution/model authorities. This function alone cannot launch or project a finding.
         */
        fun prepare(provider: DetektProvider, input: AnalysisInput,
                    owner: DetektComposeCompilationOwner): DetektLibraryPermit {
            composeCheck(input.execution == null, "detekt_compose_preparation_profile_present",
                "Prepare the exact owner input before attaching its independently authorized execution profile.")
            val original = freezeDetektInput(input)
            val compilation = freezeComposeCompilation(owner.resolve())
            requireComposeCompilation(original, compilation)
            val identity = composeBindingDigest(original, compilation, provider.inputMode)
            val permit = DetektLibraryPermit(provider, original, compilation, owner, identity)
            permit.verify(provider, original)
            return permit
        }
    }

    fun accepts(input: AnalysisInput): Boolean = active.get() && input.copy(execution = null) == original

    internal fun matches(provider: DetektProvider, input: AnalysisInput): Boolean =
        this.provider === provider && accepts(input)

    internal fun verify(provider: DetektProvider, input: AnalysisInput): DetektComposeApplicability {
        composeCheck(matches(provider, input), "detekt_compose_binding_mismatch",
            "The live library grant was revoked or belongs to another provider, input or compilation.")
        composeCheck(freezeComposeCompilation(owner.resolve()) == compilation,
            "detekt_compose_owner_resolution_changed", "The independently resolved owner compilation changed.")
        requireComposeCompilation(input, compilation)
        val sourceRoot = exactComposePath(input.materializedRoot)
        compilation.sourceFiles.forEach { file ->
            requireComposeBytes(exactComposePath(sourceRoot.resolve(safeRelativePath(file.path)).toString()), file.digest, file.size,
                "detekt_compose_source_bytes_changed")
        }
        compilation.dependencies.forEach { artifact ->
            val path = exactComposePath(artifact.identity.path)
            composeCheck(path.fileName.toString().endsWith(".jar") &&
                !artifact.identity.coordinate.endsWith(":sources") && !path.fileName.toString().endsWith("-sources.jar"),
                "detekt_compose_binary_role_invalid", "Only actual resolved JVM classpath binaries are eligible, never SOURCE archives.")
            requireComposeBytes(path, artifact.identity.digest, artifact.size, "detekt_compose_dependency_bytes_changed")
        }
        composeCheck(freezeComposeCompilation(owner.resolve()) == compilation && matches(provider, input),
            "detekt_compose_owner_resolution_changed", "The owner/library grant changed during byte verification.")
        return DetektComposeSurfaces.assess(input, compilation, bindingDigest)
    }

    override fun close() { active.set(false) }
}

private fun freezeComposeCompilation(value: DetektComposeCompilation): DetektComposeCompilation = value.copy(
    sourceSetIds = immutableDetektList(value.sourceSetIds),
    sourceFiles = immutableDetektList(value.sourceFiles.map { it.copy() }),
    compilerArguments = immutableDetektList(value.compilerArguments),
    dependencies = immutableDetektList(value.dependencies.map { it.copy(identity = it.identity.copy()) }),
)

private fun requireComposeCompilation(input: AnalysisInput, compilation: DetektComposeCompilation) {
    composeCheck(input.requiredRuleIds.isNotEmpty() && input.requiredRuleIds.distinct().size == input.requiredRuleIds.size,
        "detekt_compose_rule_vector_invalid", "The exact owner-selected rule vector must be nonempty and unique.")
    composeCheck(compilation.compilationId.isNotBlank() && compilation.sourceSetIds.isNotEmpty() &&
        compilation.sourceSetIds.all { it.isNotBlank() } &&
        compilation.sourceSetIds.distinct().size == compilation.sourceSetIds.size,
        "detekt_compose_compilation_identity_invalid", "An actual compilation and exact nonempty source-set roster are required.")
    composeCheck(compilation.scope == input.context.scope && compilation.compilerVersion == input.context.compilerVersion &&
        compilation.compilerArguments == input.context.compilerArguments &&
        compilation.dependencies.map { it.identity } == input.context.classpath,
        "detekt_compose_compilation_mismatch", "Scope, compiler/argv and complete ordered dependencies must equal independent owner resolution.")
    val files = compilation.sourceFiles
    composeCheck(files.isNotEmpty() && files.map { it.path }.distinct().size == files.size &&
        files.map { it.path } == input.context.compilationFiles &&
        input.snapshot.files.map { it.path }.distinct().size == input.snapshot.files.size &&
        files.all { file -> (file.path.endsWith(".kt") || file.path.endsWith(".kts")) &&
            input.snapshot.files.singleOrNull { it.path == file.path } == file } &&
        input.snapshot.unknownInputs.isEmpty() && input.snapshot.scopeExclusions.isEmpty(),
        "detekt_compose_compilation_members_mismatch", "Exact captured owner compilation membership/order is required, not all files by inference.")
    files.forEach { safeRelativePath(it.path) }
    composeCheck(compilation.dependencies.map { it.identity.path }.distinct().size == compilation.dependencies.size &&
        compilation.dependencies.map { it.identity.coordinate }.distinct().size == compilation.dependencies.size,
        "detekt_compose_dependency_order_invalid", "Duplicate/aliased dependency slots are not a resolved compilation inventory.")
}

private fun exactComposePath(value: String): Path = Path.of(value).also { path ->
    composeCheck(path.isAbsolute && path.normalize() == path && path.toString() == value,
        "detekt_compose_path_invalid", "Owner source/dependency paths must be exact normalized absolute paths.")
    var ancestor: Path? = path
    while (ancestor != null) {
        composeCheck(!Files.isSymbolicLink(ancestor), "detekt_compose_path_invalid", "Symlink aliases are not owner compilation paths.")
        ancestor = ancestor.parent
    }
}

private fun requireComposeBytes(path: Path, digest: String, size: Long, code: String) {
    composeCheck(Digests.valid(digest) && size >= 0 && Files.isRegularFile(path, NOFOLLOW_LINKS) &&
        !Files.isSymbolicLink(path) && Files.size(path) == size && fileDigest(path) == digest,
        code, "Actual captured source/dependency bytes no longer equal their independently selected identities.")
}

private fun composeBindingDigest(input: AnalysisInput, compilation: DetektComposeCompilation, mode: DetektInputMode): String {
    // Path-independent relation permits the SAME model kernel archive to reproduce plan identity.
    // It grants no relocation: each live permit still accepts only its exact path-bearing input;
    // the separate existing model authority must prove the original->kernel archive relation.
    val logical = input.copy(materializedRoot = "", outputRoot = null, execution = null,
        context = input.context.copy(classpath = input.context.classpath.map { it.copy(path = "") }))
    return Digests.sha256(Digests.canonical(buildJsonObject {
        put("schema", DetektLibraryPermit.SCHEMA); put("inputMode", mode.name)
        put("logicalInput", logical.analysisKey()); put("compilationId", compilation.compilationId)
        put("platform", compilation.platform); put("sourceSetIds", JsonArray(compilation.sourceSetIds.map(::JsonPrimitive)))
        put("dependencies", JsonArray(compilation.dependencies.map { artifact -> buildJsonObject {
            put("coordinate", artifact.identity.coordinate); put("digest", artifact.identity.digest); put("size", artifact.size)
        } }))
        put("detekt", DETEKT_VERSION); put("embeddedKotlin", DETEKT_EMBEDDED_KOTLIN); put("composeRules", COMPOSE_RULES_VERSION)
        put("enginePins", JsonArray(DetektCatalog.pins.map { pin -> buildJsonObject {
            put("coordinate", pin.coordinate); put("sha256", pin.sha256); put("size", pin.size)
        } }))
        put("parserCompatibilityQualified", false); put("apiReview", "runtime-1.12.1-immutable-0.3.8-surface-1")
    }))
}

private fun composeCheck(valid: Boolean, code: String, message: String) {
    if (!valid) throw ExecutionProblem(code, message)
}

/** Closed SOURCE review, not a semantic resolver and not a reduced release/catalog inventory. */
internal object DetektComposeSurfaces {
    private val prefix = "ktc.compose.pack."
    private val base = setOf(DetektComposeApiFamily.COMPOSITION)
    private fun needs(vararg apis: DetektComposeApiFamily) = base + apis
    private val reviewed = mapOf(
        prefix + "RememberMissing" to needs(DetektComposeApiFamily.GENERIC_STATE, DetektComposeApiFamily.SCALAR_STATE,
            DetektComposeApiFamily.PRIMITIVE_COLLECTION_STATE),
        prefix + "RememberContentMissing" to needs(DetektComposeApiFamily.MOVABLE_CONTENT),
        prefix + "LambdaParameterInRestartableEffect" to needs(DetektComposeApiFamily.RUNTIME_EFFECTS,
            DetektComposeApiFamily.LIFECYCLE_EFFECTS, DetektComposeApiFamily.CIRCUIT_EFFECTS,
            DetektComposeApiFamily.PROJECT_KEYED_EFFECTS),
        prefix + "ContentSlotReused" to needs(DetektComposeApiFamily.MOVABLE_CONTENT),
        prefix + "MutableStateAutoboxing" to needs(DetektComposeApiFamily.GENERIC_STATE, DetektComposeApiFamily.SCALAR_STATE,
            DetektComposeApiFamily.PRIMITIVE_COLLECTION_STATE),
        prefix + "StateParam" to needs(DetektComposeApiFamily.GENERIC_STATE, DetektComposeApiFamily.SCALAR_STATE),
        prefix + "MutableStateParam" to needs(DetektComposeApiFamily.GENERIC_STATE, DetektComposeApiFamily.SCALAR_STATE,
            DetektComposeApiFamily.PRIMITIVE_COLLECTION_STATE),
        prefix + "UnstableCollections" to needs(DetektComposeApiFamily.IMMUTABLE_CONTRACT, DetektComposeApiFamily.IMMUTABLE_COLLECTIONS),
    )
    // Every other stable mapping remains an explicit UNKNOWN obligation (historical aliases
    // remain aliases in the catalog). Registration or default activation is not API review.
    private val unreviewed = setOf(
        "ktc.compose.pack.ComposableAnnotationNaming",
        "ktc.compose.pack.ComposableNaming",
        "ktc.compose.pack.ComposableNestingDepth",
        "ktc.compose.pack.ComposableParamOrder",
        "ktc.compose.pack.CompositionLocalAllowlist",
        "ktc.compose.pack.CompositionLocalNaming",
        "ktc.compose.pack.ConditionHoist",
        "ktc.compose.pack.ContentEmitterReturningValues",
        "ktc.compose.pack.ContentTrailingLambda",
        "ktc.compose.pack.DefaultsVisibility",
        "ktc.compose.pack.InvalidReadOnlyComposable",
        "ktc.compose.pack.LambdaParameterEventTrailing",
        "ktc.compose.pack.Material2",
        "ktc.compose.pack.MissingNonRestartableComposable",
        "ktc.compose.pack.MissingReadOnlyComposable",
        "ktc.compose.pack.ModifierClickableOrder",
        "ktc.compose.pack.ModifierComposed",
        "ktc.compose.pack.ModifierMissing",
        "ktc.compose.pack.ModifierNaming",
        "ktc.compose.pack.ModifierNotUsedAtRoot",
        "ktc.compose.pack.ModifierReused",
        "ktc.compose.pack.ModifierShouldBeForwardedToEveryBranch",
        "ktc.compose.pack.ModifierWithoutDefault",
        "ktc.compose.pack.MultipleEmitters",
        "ktc.compose.pack.MutableParams",
        "ktc.compose.pack.ParameterNaming",
        "ktc.compose.pack.PreviewAnnotationNaming",
        "ktc.compose.pack.PreviewNaming",
        "ktc.compose.pack.PreviewPublic",
        "ktc.compose.pack.StaleRememberUpdatedStateInRemember",
        "ktc.compose.pack.UnnecessaryComposable",
        "ktc.compose.pack.UnnecessaryLaunchedEffect",
        "ktc.compose.pack.VarsWithoutStateBacking",
        "ktc.compose.pack.ViewModelForwarding",
        "ktc.compose.pack.ViewModelInjection",
    )
    val ruleIds: Set<String> = Collections.unmodifiableSet(reviewed.keys + unreviewed)

    fun isCompose(ruleId: String): Boolean = DetektCatalog.byId[ruleId]?.upstream_id?.startsWith("Compose:") == true

    private fun required(ruleId: String): Set<DetektComposeApiFamily> =
        reviewed[ruleId] ?: setOf(DetektComposeApiFamily.UNREVIEWED_RULE_SURFACE)

    fun unavailable(input: AnalysisInput, reason: String): DetektComposeApplicability = DetektComposeApplicability(
        rules = Collections.unmodifiableMap(input.requiredRuleIds.distinct().filter(::isCompose).associateWith { id ->
            DetektComposeRuleApplicability(Collections.unmodifiableMap(required(id).associateWith { DetektComposeApiState.UNKNOWN }),
                executableFacets = false, completeApiSurface = false, reason = reason)
        }))

    /** Pure metadata classification only; provider use additionally needs the live checked permit. */
    fun assess(input: AnalysisInput, compilation: DetektComposeCompilation, bindingDigest: String): DetektComposeApplicability {
        val dependencies = compilation.dependencies
        val runtimeCoordinates = setOf("androidx.compose.runtime:runtime", "androidx.compose.runtime:runtime-desktop",
            "androidx.compose.runtime:runtime-android", "org.jetbrains.compose.runtime:runtime", "org.jetbrains.compose.runtime:runtime-desktop")
        val runtimes = dependencies.filter { it.identity.coordinate.substringBeforeLast(':') in runtimeCoordinates }
        val annotationArtifacts = dependencies.filter {
            it.identity.coordinate.substringBeforeLast(':') in setOf("androidx.compose.runtime:runtime-annotation",
                "androidx.compose.runtime:runtime-annotation-jvm")
        }
        val denial = when {
            compilation.platform != "jvm" || compilation.compilerVersion != "2.4.21" -> "detekt_compose_tuple_unsupported"
            runtimes.isEmpty() -> "detekt_compose_library_missing"
            runtimes.size != 1 || !DetektComposeLibraryPins.runtime.matches(runtimes.single()) -> "detekt_compose_tuple_unsupported"
            annotationArtifacts.isEmpty() -> "detekt_compose_library_missing"
            annotationArtifacts.size != 1 || !DetektComposeLibraryPins.annotations.matches(annotationArtifacts.single()) ->
                "detekt_compose_tuple_unsupported"
            else -> null
        }
        if (denial != null) return unavailable(input, denial).copy(bindingDigest = bindingDigest)
        // This reviewed common/JVM family must be unambiguous, just like runtime/annotations.
        // Wrong, missing or conflicting optional members withhold only immutable collection
        // advice; they do not invalidate unrelated runtime API families or prove API absence.
        val immutableCoordinates = setOf("org.jetbrains.kotlinx:kotlinx-collections-immutable",
            "org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm")
        val immutableArtifacts = dependencies.filter { it.identity.coordinate.substringBeforeLast(':') in immutableCoordinates }
        val present = setOf(DetektComposeApiFamily.COMPOSITION, DetektComposeApiFamily.GENERIC_STATE,
            DetektComposeApiFamily.SCALAR_STATE, DetektComposeApiFamily.MOVABLE_CONTENT,
            DetektComposeApiFamily.RUNTIME_EFFECTS, DetektComposeApiFamily.IMMUTABLE_CONTRACT) +
            if (immutableArtifacts.size == 1 && DetektComposeLibraryPins.immutableCollections.matches(immutableArtifacts.single()))
                setOf(DetektComposeApiFamily.IMMUTABLE_COLLECTIONS) else emptySet()
        // Primitive collection spellings in androidx.collection are NOT observable Compose state
        // declarations. Lifecycle/Circuit/arbitrary KeyedEffect surfaces are not proven absent
        // by missing imports or by an empty list of recognized dependency coordinates.
        // Neither unknown facet can silently disappear or authorize an unsafe recommendation.
        val rules = input.requiredRuleIds.distinct().filter(::isCompose).associateWith { id ->
            val states = required(id).associateWith {
                if (it in present) DetektComposeApiState.REVIEWED_PRESENT else DetektComposeApiState.UNKNOWN
            }
            val complete = states.values.all { it == DetektComposeApiState.REVIEWED_PRESENT }
            // These TWO rules have exact, distinct SOURCE-pinned generic/scalar message families.
            // Enabling the upstream detector is NOT a whole-rule eligibility or coverage grant.
            val partial = id in setOf(prefix + "RememberMissing", prefix + "MutableStateAutoboxing")
            DetektComposeRuleApplicability(Collections.unmodifiableMap(states), complete || partial, complete,
                if (complete) null else if (partial) "detekt_compose_partial_api_surface" else "detekt_compose_api_surface_unknown")
        }
        return DetektComposeApplicability(bindingDigest, Collections.unmodifiableMap(rules))
    }

    /**
     * Family interpretation only, never API symbol resolution. A full-surface rule still yields
     * a low-certainty syntax advisory. Partial rules accept exact reviewed message bytes, not
     * substrings, locations/annotations/imports, or arbitrary upstream recommendation text.
     */
    fun allowsDiagnostic(ruleId: String, message: String, applicability: DetektComposeApplicability): Boolean {
        val state = applicability.rules[ruleId] ?: return false
        if (!state.executableFacets) return false
        if (state.completeApiSurface) return true
        return when (ruleId) {
            prefix + "RememberMissing" -> message in rememberedStateMessages
            prefix + "MutableStateAutoboxing" -> message in scalarAutoboxingMessages
            else -> false
        }
    }

    // Exact messages from v0.6.8 RememberStateMissing.errorMessage, restricted to reviewed APIs.
    private val rememberedStateMessages = setOf("derivedStateOf", "mutableStateOf", "mutableIntStateOf",
        "mutableFloatStateOf", "mutableDoubleStateOf", "mutableLongStateOf").map { name -> """
            Using `$name` in a @Composable function without it being inside of a remember function.
            If you don't remember the state instance, a new state instance will be created when the function is recomposed.
            See https://mrmans0n.github.io/compose-rules/rules/#state-should-be-remembered-in-composables for more information.
        """.trimIndent() }.toSet()

    // All four scalar recommendations; visitFile AND visitFunction include non-composables.
    private val scalarAutoboxingMessages = setOf(
        """
            Using mutableIntStateOf is recommended over mutableStateOf<Int>, as it uses the Int primitive directly which is more performant.
            See https://mrmans0n.github.io/compose-rules/rules/#use-mutablestateof-type-specific-variants-when-possible for more information.
        """.trimIndent(),
        """
            Using mutableLongStateOf is recommended over mutableStateOf<Long>, as it uses the Long primitive directly which is more performant.
            See https://mrmans0n.github.io/compose-rules/rules/#use-mutablestateof-type-specific-variants-when-possible for more information.
        """.trimIndent(),
        """
            Using mutableDoubleStateOf is recommended over mutableStateOf<Double>, as it uses the Double primitive directly which is more performant.
            See https://mrmans0n.github.io/compose-rules/rules/#use-mutablestateof-type-specific-variants-when-possible for more information.
        """.trimIndent(),
        """
            Using mutableFloatStateOf is recommended over mutableStateOf<Float>, as it uses the Float primitive directly which is more performant.
            See https://mrmans0n.github.io/compose-rules/rules/#use-mutablestateof-type-specific-variants-when-possible for more information.
        """.trimIndent(),
    )
}

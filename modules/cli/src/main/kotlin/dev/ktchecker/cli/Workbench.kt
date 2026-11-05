package dev.ktchecker.cli

import dev.ktchecker.compiler.KotlinJvmCompilerProvider
import dev.ktchecker.compiler.KotlinJvmInputMode
import dev.ktchecker.compiler.KotlinJvmToolchain
import dev.ktchecker.compiler.CompilerRuleRegistry
import dev.ktchecker.config.*
import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.*
import dev.ktchecker.projectmodel.GradleModelExporter
import dev.ktchecker.projectmodel.ProjectModelImporter
import dev.ktchecker.projectmodel.VerifiedProjectModel
import dev.ktchecker.projectmodel.ObservedProjectModel
import dev.ktchecker.reviewcontroller.OwnerPlanAssembler
import dev.ktchecker.providers.detekt.DetektInstallation
import dev.ktchecker.providers.detekt.DetektProvider
import dev.ktchecker.providers.detekt.DetektCatalog
import dev.ktchecker.providers.ktlint.KtlintCatalog
import dev.ktchecker.providers.ktlint.KtlintInstallation
import dev.ktchecker.providers.ktlint.KtlintProvider
import dev.ktchecker.providers.ktlint.KTLINT_EMBEDDED_KOTLIN
import dev.ktchecker.providers.lint.AndroidLintProvider
import dev.ktchecker.providers.lint.LintCatalog
import dev.ktchecker.providers.native.KotlinNativeProvider
import dev.ktchecker.providers.native.NativeCatalog
import dev.ktchecker.providers.runtime.IsolatedProcessRunner
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import dev.ktchecker.providers.runtime.fileDigest
import dev.ktchecker.workerprotocol.ApplicationWire
import dev.ktchecker.workerprotocol.CompilerModelWire
import dev.ktchecker.snapshot.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable internal data class OverlayChange(
    val operation: String,
    val path: String,
    val content: String? = null,
    val contentBase64: String? = null,
    val destination: String? = null,
    val rootId: String = "project",
    val executable: Boolean = false,
)

@Serializable internal data class OverlayDocument(
    val workspaceId: String,
    val worktreeId: String,
    val clientId: String,
    val sessionId: String,
    val patchId: String,
    val baseSnapshotId: String? = null,
    val expectedHeadRevision: Long = 0,
    val mode: CommitMode = CommitMode.STRICT_HEAD,
    val changes: List<OverlayChange>,
    val scope: SnapshotScope = SnapshotScope(),
)

/** Explicit owner import of ONE compilation. This is not automatic Gradle project discovery. */
@Serializable internal data class PreparationSpec(
    val clientId: String,
    val snapshotId: String,
    val patchId: String,
    val idempotencyKey: String,
    val compilationFiles: List<String>,
    val scope: RuleContext = RuleContext(),
    val requiredRuleIds: List<String>? = null,
    val compilerArguments: List<String> = emptyList(),
    val classpath: List<ArtifactIdentity> = emptyList(),
    val addKotlinStdlib: Boolean = true,
    val libraries: Map<String, String> = emptyMap(),
    /** Owner-declared project version; this does not install or qualify that compiler. */
    val compilerVersion: String = "2.4.21",
)

@Serializable internal data class PreparedReview(
    val binding: ClientBinding,
    val patchId: String,
    val idempotencyKey: String,
    val input: AnalysisInput,
    val configuration: FrozenConfig,
    val profiles: Map<String, TrustedExecution>,
    val executionAuthorized: Boolean,
    val coverageScope: String,
    val contractVersion: String = PREVIEW_CONTRACT,
)

internal data class WorkbenchOptions(
    val state: Path = PrivateState.defaultPath(),
    val compilerDirectory: Path? = null,
    val detektDirectory: Path? = null,
    val workerCount: Int = 2,
    val ktlintDirectory: Path? = null,
    val compilerJavaHome: Path? = null,
    /** In-process owner selection only; paths/profile JSON cannot reconstruct this capability. */
    val modelCompilerInstallation: KotlinJvmToolchain? = null,
    /** Explicit owner runtime, selected by the owner model command or typed owner composition, never RPC. */
    val liveModelRuntime: OwnerLiveModelRuntime? = null,
) {
    init { require(modelCompilerInstallation == null || liveModelRuntime == null) { "Select one exact model execution mode; no fallback" } }
}

/** Trusted local composition root; ordinary MCP tools cannot call its owner-control methods. */
internal class Workbench(private val options: WorkbenchOptions) : AutoCloseable, RpcHandler {
    val state = PrivateState(options.state)
    private val catalog = Catalog()
    val snapshots = SnapshotStore(state.namespace("snapshots"), state.ownerId)
    private val grants = ExecutionGrants()
    private val modelGrants = ModelAttemptGrants()
    private val liveModelRuntime = options.liveModelRuntime.also {
        require(it == null || it.ownerId == state.ownerId) { "A live model runtime belongs to this exact local owner" }
    }
    private val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
    // Explicit shipped closure: core/policy/storage/normalization/provider adapters and their
    // data, Kotlin/serialization and SQLite. This is NOT automatic arbitrary-plugin discovery
    // or an attestation of already-loaded/transformed classes or of the external JDK image.
    private val codeImage = CodeImage.capture(listOf(
        Workbench::class.java, AnalysisInput::class.java, SnapshotStore::class.java,
        KernelService::class.java, ConfigStore::class.java, IsolatedProcessRunner::class.java,
        ProjectModelImporter::class.java, OwnerPlanAssembler::class.java,
        KotlinJvmCompilerProvider::class.java, DetektProvider::class.java, KtlintProvider::class.java,
        ApplicationWire::class.java, CompilerModelWire::class.java,
        AndroidLintProvider::class.java, KotlinNativeProvider::class.java,
        Unit::class.java, kotlinx.serialization.KSerializer::class.java, Json::class.java,
        Class.forName("org.sqlite.JDBC", false, Workbench::class.java.classLoader),
    ), mapOf(
        "catalog" to CodeImage.resourceRoot(Catalog::class.java, "/catalog/rules-core.json"),
        // Explicit resource closure also binds host-built native bytes in split
        // Gradle class/resource directories. In a distribution this is the same
        // provider-runtime JAR as the implementation, not a PATH-selected helper.
        "process-supervisor" to CodeImage.resourceRoot(IsolatedProcessRunner::class.java,
            "/dev/ktchecker/providers/runtime/native/manifest"),
        "compiler-mappings" to CodeImage.resourceRoot(CompilerRuleRegistry::class.java, "/compiler-rule-map.json"),
        "project-model-exporter" to CodeImage.resourceRoot(GradleModelExporter::class.java, "/${GradleModelExporter.SCRIPT}"),
        "owner-model-origins" to CodeImage.resourceRoot(Workbench::class.java, "/ktchecker-owner-model-origin.init.gradle"),
        "detekt-mappings" to CodeImage.resourceRoot(DetektCatalog::class.java, "/detekt-rule-map.json"),
        "detekt-artifact-pins" to CodeImage.resourceRoot(DetektCatalog::class.java, "/detekt-artifacts.json"),
        "ktlint-mappings" to CodeImage.resourceRoot(KtlintCatalog::class.java, "/ktlint-rule-map.json"),
        "ktlint-artifact-pins" to CodeImage.resourceRoot(KtlintCatalog::class.java, "/ktlint-artifacts.json"),
        "lint-mappings" to CodeImage.resourceRoot(LintCatalog::class.java, "/dev/ktchecker/providers/lint/android-lint-catalog.json"),
        "native-mappings" to CodeImage.resourceRoot(NativeCatalog::class.java, "/dev/ktchecker/providers/native/kotlin-native-catalog.json"),
    ))
    private val compilerSelection = selectCompiler(options.compilerDirectory ?: distributionHome()?.resolve("providers/compiler"),
        options.compilerJavaHome ?: Path.of(System.getProperty("java.home")))
    private val compilerInstallation = compilerSelection.toolchain
    private val compiler = compilerInstallation?.let { KotlinJvmCompilerProvider(it, grants) }
    // One fixed model provider per owner composition. A missing selection is unavailable, never
    // a downgrade to the bundled explicit-input compiler or a request-controlled profile loader.
    private val modelCompilerInstallation = options.modelCompilerInstallation
    private val modelCompiler = modelCompilerInstallation?.let { KotlinJvmCompilerProvider(it, modelGrants.executionAuthority,
        inputMode = KotlinJvmInputMode.VERIFIED_MODEL, modelAuthority = modelGrants.modelAuthority) }
    private val detektInstallation = (options.detektDirectory ?: state.root.resolve("providers/detekt"))
        .takeIf { Files.isDirectory(it) }?.let { DetektInstallation(java, it, Path.of(System.getProperty("java.home"))) }
    private val detekt = DetektProvider(detektInstallation, grants)
    private val ktlintInstallation = (options.ktlintDirectory ?: state.root.resolve("providers/ktlint"))
        .takeIf { Files.isDirectory(it) }?.let { KtlintInstallation(java, it, Path.of(System.getProperty("java.home"))) }
    private val ktlint = KtlintProvider(ktlintInstallation, grants)
    private val lint = AndroidLintProvider(authority = grants)
    private val native = KotlinNativeProvider(authority = grants)
    private val ktlintIdentity = ktlintInstallation?.let { installed ->
        runCatching { Digests.sha256(providerIdentity(ktlint) + Digests.canonical(
            ContractJson.encodeToJsonElement(KtlintCatalog.verifyArtifacts(installed.artifactDirectory)))) }.getOrNull()
    }
    private val routes = buildList {
        liveModelRuntime?.let { runtime -> add(ProviderRoute(runtime.routingProvider, runtime::routes,
            implementationIdentity = runtime.implementationIdentity, preparedChild = runtime::child,
            finishAttempt = runtime::finish)) }
        modelCompiler?.let { provider -> add(ProviderRoute(provider, modelGrants::routes,
            implementationIdentity = Digests.sha256(providerIdentity(provider) + modelCompilerInstallation!!.digest + Digests.canonical(
                ContractJson.encodeToJsonElement(modelCompilerInstallation.compilerClasspath))),
            preparedChild = modelGrants::child, finishAttempt = modelGrants::finish)) }
        compiler?.let { provider -> add(ProviderRoute(provider,
            { !isModelAttempt(it) && it.context.scope.target.lowercase() in setOf("jvm", "desktop", "android") && it.context.compilerVersion == "2.4.21" }, { provider.executionProfile(it) },
            Digests.sha256(providerIdentity(provider) + compilerInstallation!!.digest + Digests.canonical(
                ContractJson.encodeToJsonElement(compilerInstallation.compilerClasspath))))) }
            ?: add(ProviderRoute(UnavailableCompilerProvider(compilerSelection.unavailableReason!!),
                { !isModelAttempt(it) && it.context.scope.target.lowercase() in setOf("jvm", "desktop", "android") && it.context.compilerVersion == "2.4.21" }))
        // Some syntax/Compose catalog IDs have multiple upstream adapters. Prefer Ktlint only
        // on its explicitly installed, parser-compatible tuple; it must not steal the same IDs
        // from Detekt on a newer Kotlin project or an unavailable platform/profile.
        val ktlintRoute = ProviderRoute(ktlint, { true }, ktlint::executionProfile, ktlintIdentity)
        add(ktlintRoute.copy(applicable = { !isModelAttempt(it) && ktlintPreferred(it, ktlintIdentity != null) }))
        add(ProviderRoute(detekt, { !isModelAttempt(it) }, detekt::executionProfile, detektInstallation?.let { installed ->
            runCatching { Digests.sha256(providerIdentity(detekt) + Digests.canonical(
                ContractJson.encodeToJsonElement(DetektCatalog.verifyArtifacts(installed.artifactDirectory)))) }.getOrNull()
        }))
        // Keep Ktlint-only unsupported/missing tuples visible through its precise abstention.
        add(ktlintRoute.copy(applicable = { !isModelAttempt(it) && !ktlintPreferred(it, ktlintIdentity != null) }))
        add(ProviderRoute(lint, { !isModelAttempt(it) && it.context.scope.target.lowercase().contains("android") }))
        add(ProviderRoute(native, { !isModelAttempt(it) && it.context.scope.target in setOf("iosArm64", "iosSimulatorArm64") }))
    }
    private val coordinator = ReviewCoordinator(catalog, routes, grants, codeImage)
    private val routingAliases = policyAliases(catalog)
    private val aliases = RuleAliases(catalog.records.map { record ->
        val id = record.getValue("id").jsonPrimitive.content
        val protected = record.getValue("category").jsonPrimitive.content.startsWith("kotlin.compiler") &&
            record.getValue("severity").jsonPrimitive.content == "error"
        val implementingRoutes = routes.filter { id in it.provider.supportedRuleIds && it.implementationIdentity != null }
        // Catalog membership alone is not an implementation or a usable narrow-policy identity.
        PolicyRule(id, aliases = routingAliases[id].orEmpty(), failureProtected = protected, implementationDigest = implementingRoutes.takeIf { it.isNotEmpty() }?.let { Digests.sha256(
            Digests.canonical(record) + it.joinToString { it.implementationIdentity!! },
        ) })
    })
    val configs = ConfigStore(state.namespace("configuration"), aliases)
    private val modelPlans = OwnerModelPlans(state, snapshots, configs, catalog, modelCompilerInstallation, modelCompiler,
        modelGrants, codeImage::verify, liveRuntime = liveModelRuntime, parentProvider = coordinator)
    private val loadedPolicies = ConcurrentHashMap<String, ConfigSnapshot>()
    private val records = ConcurrentHashMap<String, PreparedReview>()
    /** The exact trusted policy object used by this composition, including lazy restart recovery. */
    internal val capturedResultPolicy: ResultPolicy = ResultPolicy.versioned("ktchecker/workbench-policy/1:${codeImage.digest}", object : ResultPolicy {
        override fun isReady(binding: ClientBinding, input: AnalysisInput): Boolean =
            modelPlans.policy(binding, input) != null || loadedPolicies.containsKey(policyKey(binding, input))

        override fun restoreCaptured(binding: ClientBinding, input: AnalysisInput) {
            codeImage.verify()
            // Model evidence restores its captured policy during OwnerModelPlans construction;
            // this path never manufactures the separate live importer/execution authority.
            if (modelPlans.policy(binding, input) != null) return
            val prepared = this@Workbench.prepared(input)
            if (prepared.binding != binding) throw CliProblem("prepared_binding_mismatch", "Captured policy belongs to another exact consumer binding.")
            validateSnapshot(prepared)
            cachePolicy(prepared) // Exact archived FrozenConfig/token; never configs.capture(current).
        }

        override fun apply(binding: ClientBinding, input: AnalysisInput, raw: ProviderResult): ProviderResult {
            val policy = modelPlans.policy(binding, input) ?: loadedPolicies[policyKey(binding, input)]
                ?: throw CliProblem("captured_policy_unavailable", "Captured policy was not restored for this consumer.")
            return configs.apply(policy, input, raw)
        }
    })
    val kernel: KernelService = KernelService.open(state.namespace("broker"), coordinator,
        config = KernelConfig(workerCount = options.workerCount, globalParallelism = 2, perWorktreeParallelism = 1),
        snapshotGate = sourceGate(),
        preparedInputObserver = object : PreparedInputObserver {
            override fun preparedProduced(attempt: ProducedExecutionAttempt) {
                codeImage.verify()
                modelPlans.observeProduced(attempt)
            }

            override fun prepared(original: AnalysisInput, effective: AnalysisInput, verifyFrozen: () -> Unit) {
                codeImage.verify()
                if (modelPlans.evidence(original) != null) modelPlans.observe(original, effective, verifyFrozen)
                else {
                    val prepared = this@Workbench.prepared(original)
                    validateSnapshot(prepared)
                    cachePolicy(prepared)
                    grants.prepared(AuthorizedAttempt(original, effective,
                        if (prepared.executionAuthorized) prepared.profiles else emptyMap(), verifyFrozen))
                }
            }

            override fun finished(effective: AnalysisInput) {
                // The worker owns the attempt lifetime, including cancellation after preparation
                // but before any provider is invoked. Coordinator teardown is an idempotent backup.
                try { liveModelRuntime?.finish(effective) } finally {
                    try { modelGrants.finish(effective) } finally { grants.finish(effective) }
                }
            }
        },
        planAuthority = modelPlans,
        findingProviders = coordinator.findingProviders,
        resultPolicy = capturedResultPolicy)

    override fun close() = kernel.close()

    internal val ownerCodeDigest: String get() = codeImage.digest
    internal fun verifyOwnerCode() = codeImage.verify()

    private fun isModelAttempt(input: AnalysisInput): Boolean =
        liveModelRuntime?.routes(input) == true || modelGrants.routes(input)

    fun register(workspaceId: String, worktreeId: String, clientId: String, sessionId: String, lineageId: String): ClientRegistration =
        register(ClientBinding(state.ownerId, workspaceId, worktreeId, clientId, lineageId, sessionId = sessionId))

    private fun register(binding: ClientBinding): ClientRegistration = state.locked("client:${binding.clientId}") {
        if (binding.ownerId != state.ownerId) throw CliProblem("permission_denied", "Client belongs to another user namespace.")
        val file = state.keyFile("clients", binding.clientId)
        if (Files.exists(file, NOFOLLOW_LINKS)) {
            val current = state.read<ClientRegistration>(file)
            if (current.binding != binding) throw CliProblem("client_binding_conflict", "Client is already registered to a different session or lineage.")
            kernel.registerClient(current)
        } else {
            fun token() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
            val registration = ClientRegistration(binding, ClientCredential(binding.clientId, token()), AdapterCredential(binding.clientId, token()))
            // Durable private enrollment intent precedes broker admission. A crash at either side
            // can replay the same exact binding and token hashes, never replace another client.
            state.write(file, registration)
            kernel.registerClient(registration)
        }
    }

    fun registration(clientId: String): ClientRegistration = state.read(state.keyFile("clients", clientId))

    /** Owner-only beginning of a model import; capture configuration and consent BEFORE Gradle. */
    fun beginModelReview(clientId: String, request: SubmitPlanRequest, authorizeExecution: Boolean,
        originalIngressDeadline: OwnedApplicationDeadline? = modelPlans.ingressDeadline()): OwnerModelIntent {
        return modelPlans.begin(registration(clientId).binding, request, authorizeExecution, originalIngressDeadline)
    }

    /** Typed live importer capability only. There is deliberately no model JSON/RPC counterpart. */
    fun prepareModelReview(intent: OwnerModelIntent, before: VerifiedProjectModel?, after: VerifiedProjectModel,
                           materialized: MaterializedSnapshot): PreparedOwnerModelPlan {
        if (registration(intent.binding.clientId).binding != intent.binding) {
            throw CliProblem("model_client_binding_changed", "Model intent belongs to another exact client session.")
        }
        return modelPlans.prepare(intent, before, after, materialized)
    }

    /** Nominal observed-only API. The earlier model is documentary; no previous grant/clock is
     * restored. Failure retains the entire attempted vector and registers no scalar/prefix plan. */
    fun prepareObservedModelReview(intent: OwnerModelIntent, before: ObservedProjectModel?, after: ObservedProjectModel,
        materialized: MaterializedSnapshot): PreparedOwnerModelPlan {
        if (registration(intent.binding.clientId).binding != intent.binding) {
            throw CliProblem("model_client_binding_changed", "Observed intent belongs to another exact client session.")
        }
        return modelPlans.prepareObserved(intent, before, after, materialized)
    }

    /** A parent reference resolves only through live owner authority; cached inputs are not plans. */
    fun submitModelPlan(credential: ClientCredential, request: SubmitPlanRequest): ParentPlanStatus {
        codeImage.verify()
        return kernel.submitPlan(credential, request)
    }

    fun overlay(document: OverlayDocument): SnapshotCommit {
        val identity = SnapshotIdentity(state.ownerId, document.workspaceId, document.worktreeId)
        snapshots.registerWorktree(identity)
        val changes = document.changes.map { change ->
            val path = SnapshotPath(change.rootId, change.path)
            when (change.operation) {
                "put" -> {
                    if ((change.content == null) == (change.contentBase64 == null)) throw CliProblem("invalid_overlay", "Put requires exactly one content representation.")
                    val bytes = change.content?.toByteArray(Charsets.UTF_8) ?: try { Base64.getDecoder().decode(change.contentBase64) }
                        catch (_: Exception) { throw CliProblem("invalid_overlay", "Invalid base64 source bytes.") }
                    SnapshotChange.PutFile(path, ImmutableBlob.copyOf(bytes), change.executable)
                }
                "delete" -> SnapshotChange.Delete(path)
                "rename" -> SnapshotChange.Rename(path, SnapshotPath(change.rootId, change.destination
                    ?: throw CliProblem("invalid_overlay", "Rename requires its destination.")))
                "symlink" -> SnapshotChange.PutSymlink(path, change.destination
                    ?: throw CliProblem("invalid_overlay", "Symlink requires a captured relative destination."))
                else -> throw CliProblem("invalid_overlay", "Unknown overlay operation.")
            }
        }
        return snapshots.sealOverlay(OverlayRequest(identity, document.clientId, document.sessionId, document.patchId,
            document.baseSnapshotId, document.expectedHeadRevision, changes, document.mode, document.scope))
    }

    fun prepare(spec: PreparationSpec, authorizeExecution: Boolean): PreparedReview {
        codeImage.verify()
        if (!spec.compilerVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?")) || spec.compilerVersion.length > 64) {
            throw CliProblem("invalid_compiler_version", "An explicit bounded project compiler version is required.")
        }
        if (spec.addKotlinStdlib && spec.compilerVersion != "2.4.21") {
            throw CliProblem("matching_stdlib_unavailable", "The bundled stdlib belongs to Kotlin 2.4.21; explicitly supply another version's exact classpath instead.")
        }
        val registration = registration(spec.clientId)
        val binding = registration.binding
        val identity = SnapshotIdentity(binding.ownerId, binding.workspaceId, binding.worktreeId)
        val manifest = snapshots.snapshot(identity, spec.snapshotId)
        if (spec.compilationFiles.isEmpty() || spec.compilationFiles.distinct().size != spec.compilationFiles.size ||
            spec.compilationFiles.any { path -> manifest.toContractManifest().files.none { it.path == path } }) {
            throw CliProblem("invalid_compilation_members", "Explicit nonempty captured compilation members are required.")
        }
        val rules = (spec.requiredRuleIds ?: catalog.applicableInventory(spec.scope.target)).distinct().sorted()
        if (rules.isEmpty() || rules.any { it !in catalog.ids }) throw CliProblem("invalid_rule_plan", "Required rule IDs must be a nonempty declared inventory subset.")
        val policy = configs.capture(configBinding(binding))
        val activation = activationPlan(catalog, rules, spec.requiredRuleIds != null,
            policy.ruleSettings.mapValues { it.value.enabled }, policy.policyDigest)
        val artifacts = (spec.classpath + if (spec.addKotlinStdlib) compilerSelection.artifacts.filter {
            Path.of(it.path).fileName.toString().startsWith("kotlin-stdlib-")
        } else emptyList())
        // Resolution precedence is ordered. Never sort by coordinate or silently choose one
        // of conflicting identities for the same path. Exact repeated entries stay explicit.
        if (artifacts.groupBy { Path.of(it.path).toAbsolutePath().normalize() }.values.any { rows ->
                rows.map { it.digest }.distinct().size != 1
            }) throw CliProblem("classpath_identity_conflict", "One classpath path has conflicting content identities.")
        artifacts.forEach { if (fileDigest(Path.of(it.path)) != it.digest) throw CliProblem("artifact_changed", "Declared classpath artifact differs from its digest.") }
        val destination = state.namespace("materializations").resolve(UUID.randomUUID().toString())
        val materialized = snapshots.materialize(identity, spec.snapshotId, destination)
        val modelDigest = Digests.sha256(Digests.canonical(buildJsonObject {
            put("kind", "owner-declared-single-compilation"); put("scope", ContractJson.encodeToJsonElement(spec.scope))
            put("compilerVersion", spec.compilerVersion)
            put("files", ContractJson.encodeToJsonElement(spec.compilationFiles.sorted()))
            put("arguments", ContractJson.encodeToJsonElement(spec.compilerArguments))
            put("libraries", ContractJson.encodeToJsonElement(spec.libraries))
        }))
        var input = AnalysisInput(materialized.manifest.toContractManifest(), materialized.root.toString(),
            AnalysisContext(scope = spec.scope, compilerVersion = spec.compilerVersion, classpath = artifacts,
                compilerArguments = spec.compilerArguments, libraries = spec.libraries,
                policies = mapOf("ktchecker.model" to "owner-declared-single-compilation", "ktchecker.coverage" to "partial-project",
                    ACTIVATION_POLICY_KEY to ContractJson.encodeToString(ActivationPlan.serializer(), activation)),
                modelDigest = modelDigest, dependencyDigest = Digests.sha256(ContractJson.encodeToJsonElement(artifacts).toString()),
                toolchainDigest = compilerInstallation?.digest?.takeIf { spec.compilerVersion == "2.4.21" },
                configurationDigest = policy.policyDigest, fullProject = false, compilationFiles = spec.compilationFiles.sorted()),
            requiredRuleIds = rules, changedPaths = manifest.delta.map { delta ->
                val root = manifest.roots.single { it.id == delta.rootId }
                if (root.mountPath.isEmpty()) delta.path else "${root.mountPath}/${delta.path}"
            }.distinct().sorted(), baselineSnapshotId = manifest.parentSnapshotId)
        val profiles = coordinator.profiles(input)
        input = input.copy(context = input.context.copy(policies = input.context.policies +
            ("ktchecker.execution-plan" to Digests.sha256(Digests.canonical(ContractJson.encodeToJsonElement(profiles))))))
        val prepared = PreparedReview(binding, spec.patchId, spec.idempotencyKey, input, configs.exportSnapshot(policy), profiles,
            authorizeExecution, if (spec.requiredRuleIds == null) "all-catalog-candidates-for-declared-target" else "explicit-rule-subset-not-full-release")
        codeImage.verify()
        state.locked("prepared:${input.analysisKey()}") {
            val file = state.keyFile("prepared", input.analysisKey())
            // Work sharing must never overwrite a different consumer's owner policy, patch or
            // execution consent. A general shared-work authorization registry is still separate
            // release work; this single-compilation controller rejects collisions instead.
            if (Files.exists(file, NOFOLLOW_LINKS) && state.read<PreparedReview>(file) != prepared) {
                throw CliProblem("prepared_binding_conflict", "Input already has a different immutable consumer/authorization binding.")
            }
            state.write(file, prepared)
        }
        records[input.analysisKey()] = prepared
        loadedPolicies[policyKey(binding, input)] = policy
        return prepared
    }

    fun submit(credential: ClientCredential, patchId: String, idempotencyKey: String, input: AnalysisInput): ReviewStatus {
        codeImage.verify()
        if (modelPlans.evidence(input) != null || input.context.policies["ktchecker.model"] == dev.ktchecker.projectmodel.GRADLE_MODEL_EXPORTER_ID) {
            throw CliProblem("model_parent_plan_required", "A verified model input cannot bypass its complete required parent plan.")
        }
        val prepared = prepared(input)
        if (prepared.binding.clientId != credential.clientId || prepared.patchId != patchId || prepared.idempotencyKey != idempotencyKey) {
            throw CliProblem("unprepared_submission", "Submission does not match this client's exact owner-prepared patch.")
        }
        validateSnapshot(prepared)
        cachePolicy(prepared)
        return kernel.submit(credential, SubmitRequest(patchId, idempotencyKey, input))
    }

    private fun prepared(input: AnalysisInput): PreparedReview = records.computeIfAbsent(input.analysisKey()) { key ->
        val file = state.keyFile("prepared", key)
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) throw CliProblem("unprepared_submission", "A trusted owner/controller must prepare this immutable review before submission.")
        state.read<PreparedReview>(file).also {
            if (it.input != input || it.binding.ownerId != state.ownerId) throw CliProblem("prepared_identity_mismatch", "Prepared input identity differs.")
        }
    }

    private fun validateSnapshot(record: PreparedReview) {
        val binding = record.binding
        val stored = snapshots.snapshot(SnapshotIdentity(binding.ownerId, binding.workspaceId, binding.worktreeId), record.input.snapshot.snapshotId)
        if (stored.toContractManifest() != record.input.snapshot) throw CliProblem("snapshot_identity_mismatch", "Submitted manifest differs from the immutable registered snapshot.")
    }

    private fun cachePolicy(record: PreparedReview) {
        if (record.configuration.binding != configBinding(record.binding) ||
            record.configuration.revisionToken.policyDigest != record.input.context.configurationDigest) {
            throw CliProblem("captured_policy_mismatch", "Archived policy namespace or digest differs from the accepted input.")
        }
        loadedPolicies.computeIfAbsent(policyKey(record.binding, record.input)) {
            configs.restoreSnapshot(record.configuration, record.configuration.revisionToken)
        }
    }

    private fun sourceGate(): SnapshotGate = object : SnapshotGate {
        override fun <T> withCurrentSnapshot(input: AnalysisInput, action: (SnapshotCheck) -> T): T {
            codeImage.verify() // Deliberately outside the short configuration/source fences.
            val model = modelPlans.evidence(input)
            if (model != null) return withModelSnapshot(model, input, action)
            val record = prepared(input)
            return configs.withCurrentConfig(configBinding(record.binding), record.configuration.revisionToken) { config ->
                snapshots.withVerifiedSnapshot(SnapshotIdentity(record.binding.ownerId, record.binding.workspaceId, record.binding.worktreeId),
                    input.snapshot.snapshotId) { capture ->
                    val head = capture.head
                    // This preview imports one compilation and cannot certify complete live project/model authority.
                    val reasons = config.reasons + listOf("owner_declared_compilation_not_full_project_model",
                        "immutable_overlay_does_not_certify_physical_live_worktree") +
                        listOfNotNull(capture.reason)
                    action(SnapshotCheck(current = config.matches && capture.valid,
                        snapshotId = head.headSnapshotId, sourceEpoch = head.sourceEpoch,
                        configurationDigest = config.current.policyDigest, modelDigest = input.context.modelDigest,
                        dependencyDigest = input.context.dependencyDigest, toolchainDigest = input.context.toolchainDigest,
                        analysisEpoch = Digests.sha256(ContractJson.encodeToJsonElement(record.configuration.revisionToken).toString() + head.sourceEpoch),
                        authoritative = false, reasons = reasons, validUntilEpochMillis = config.current.validUntilEpochMillis))
                }
            }
        }
    }

    private fun <T> withModelSnapshot(record: ModelInputEvidence, input: AnalysisInput, action: (SnapshotCheck) -> T): T =
        configs.withCurrentConfig(modelConfigBinding(record.binding), record.configuration.revisionToken) { config ->
            snapshots.withVerifiedSnapshot(SnapshotIdentity(record.binding.ownerId, record.binding.workspaceId, record.binding.worktreeId),
                input.snapshot.snapshotId) { capture ->
                val head = capture.head
                action(SnapshotCheck(current = config.matches && capture.valid,
                    snapshotId = head.headSnapshotId, sourceEpoch = head.sourceEpoch,
                    configurationDigest = config.current.policyDigest, modelDigest = input.context.modelDigest,
                    dependencyDigest = input.context.dependencyDigest, toolchainDigest = input.context.toolchainDigest,
                    analysisEpoch = Digests.sha256(ContractJson.encodeToJsonElement(record.configuration.revisionToken).toString() + head.sourceEpoch),
                    authoritative = false,
                    reasons = config.reasons + listOfNotNull(capture.reason) +
                        listOf("verified_model_parent_not_final_success", "live_project_model_completion_fence_unavailable"),
                    validUntilEpochMillis = config.current.validUntilEpochMillis))
            }
        }

    override fun call(method: String, params: JsonObject): JsonObject = try {
        when (method) {
            "doctor" -> { requireKeys(params, emptySet()); JsonObject(doctor() + mapOf(
                "installedProvidersInspected" to JsonPrimitive(true), "installedProviders" to providerAvailability())) }
            "client.register" -> { requireKeys(params, setOf("binding")); result(register(params.decode("binding"))) }
            "review.submit" -> {
                requireKeys(params, setOf("credential", "patchId", "idempotencyKey", "input"))
                result(submit(params.decode("credential"), params.decode("patchId"), params.decode("idempotencyKey"), params.decode("input")))
            }
            "review.status" -> { requireKeys(params, setOf("credential", "reviewId")); result(kernel.status(params.decode("credential"), params.decode("reviewId"))) }
            "review.cancel" -> { requireKeys(params, setOf("credential", "reviewId")); result(kernel.cancel(params.decode("credential"), params.decode("reviewId"))) }
            "review.events" -> {
                requireKeys(params, setOf("credential", "cursor", "limit"))
                result(kernel.events(params.decode("credential"), params["cursor"]?.jsonPrimitive?.contentOrNull, params["limit"]?.jsonPrimitive?.int ?: 100))
            }
            "review.ack" -> { requireKeys(params, setOf("credential", "eventIds", "receipt")); result(kernel.acknowledge(params.decode("credential"), params.decode("eventIds"), params.decode("receipt"))) }
            "review.observe" -> { requireKeys(params, setOf("credential", "eventIds", "receipt")); result(kernel.observe(params.decode("credential"), params.decode("eventIds"), params.decode("receipt"))) }
            "gate.prepare" -> { requireKeys(params, setOf("credential")); result(kernel.prepareGate(params.decode("credential"))) }
            "gate.complete" -> { requireKeys(params, setOf("credential", "cutId")); result(kernel.completeGate(params.decode("credential"), params.decode("cutId"))) }
            else -> throw CliProblem("method_not_found", "Unknown application method; owner controls are not review tools.")
        }
    } catch (e: KernelException) { throw CliProblem(e.code, e.message ?: "Review operation failed.") }

    fun inspectConfig(clientId: String): JsonObject = configView(configs.inspect(configBinding(registration(clientId).binding)))

    fun exportSharedConfig(clientId: String): JsonObject = result(configs.exportShared(configs.capture(configBinding(registration(clientId).binding))))

    fun mutateConfig(clientId: String, layer: ConfigLayer, expected: ConfigRevisionToken, action: ConfigAction): JsonObject {
        val binding = configBinding(registration(clientId).binding)
        val principal = ClientIdentity(state.ownerId, "explicit-owner-cli", UUID.randomUUID().toString())
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val authority = HmacOwnerAuthority(secret, listOf(OwnerSessionRegistration(principal, setOf(binding))))
        val store = ConfigStore(state.namespace("configuration"), aliases, authority)
        val request = ConfigMutationRequest(binding, layer, expected, action, UUID.randomUUID().toString())
        val permit = authority.issueForExplicitOwnerAction(principal, request)
        return configView(store.mutate(request, permit))
    }

    fun explain(clientId: String, input: AnalysisInput, finding: Finding): JsonObject {
        val record = prepared(input)
        if (record.binding.clientId != clientId) throw CliProblem("permission_denied", "Historical explanation belongs to another consumer.")
        cachePolicy(record)
        return result(configs.explain(loadedPolicies.getValue(policyKey(record.binding, input)), input, finding))
    }

    /** Read-only helper; returned selector grants no policy mutation or execution authority. */
    fun findingSelector(clientId: String, input: AnalysisInput, finding: Finding): JsonObject {
        val record = prepared(input)
        if (record.binding.clientId != clientId) throw CliProblem("permission_denied", "Finding selector belongs to another consumer.")
        validateSnapshot(record)
        cachePolicy(record)
        val selector: PolicySelector = configs.findingSelector(loadedPolicies.getValue(policyKey(record.binding, input)), input, finding)
        return result(selector)
    }

    private fun configView(snapshot: ConfigSnapshot): JsonObject = buildJsonObject {
        put("contractVersion", PREVIEW_CONTRACT); put("revision", ContractJson.encodeToJsonElement(snapshot.revisionToken))
        put("shared", ContractJson.encodeToJsonElement(snapshot.shared)); put("local", ContractJson.encodeToJsonElement(snapshot.local))
        put("effectiveSuppressions", ContractJson.encodeToJsonElement(snapshot.suppressions))
        put("effectiveRules", ContractJson.encodeToJsonElement(snapshot.ruleSettings))
    }

    private fun configBinding(binding: ClientBinding) = ConfigBinding(binding.ownerId, binding.workspaceId, binding.worktreeId, binding.lineageId)
    private fun policyKey(binding: ClientBinding, input: AnalysisInput) = Digests.sha256(ContractJson.encodeToJsonElement(binding).toString() + input.analysisKey())

    fun providerAvailability(): JsonObject = buildJsonObject {
        put("contractVersion", PREVIEW_CONTRACT)
        put("releaseQualified", false)
        put("doesNotEstablishTargetExecution", true)
        put("inspectionScope", "kotlin-compiler-jvm and bundled process containment; other providers not inspected by this command")
        put("processContainment", containmentAvailability())
        putJsonObject("compiler") {
            put("state", if (compilerInstallation == null) "unavailable" else "toolchain_bound")
            compilerSelection.unavailableReason?.let { put("reason", it) }
            compilerInstallation?.let { put("toolchainDigest", it.digest) }
            put("requiresExplicitExecutionAuthorization", true)
            put("javaHomeSelection", if (options.compilerJavaHome == null) "broker_default_no_fallback" else "explicit_owner_selection")
            put("runtimeRequirement", "complete_link_free_jdk21_image")
            put("scope", "Kotlin 2.4.21 JVM compiler only; no target or rule qualification implied")
        }
    }

    private fun providerIdentity(provider: AnalysisProvider): String = "${provider.id}:${provider.version}:${codeImage.digest}"

    companion object {
        internal fun distributionHome(): Path? {
            val location = Path.of(Workbench::class.java.protectionDomain.codeSource.location.toURI())
            return if (Files.isRegularFile(location) && location.parent.fileName.toString() == "lib") location.parent.parent else null
        }
    }
}

/** Preference only. The provider independently verifies all execution/config/artifact prerequisites. */
internal fun ktlintPreferred(input: AnalysisInput, installed: Boolean): Boolean = installed &&
    input.context.scope.target == "jvm" && input.context.compilerVersion == KTLINT_EMBEDDED_KOTLIN &&
    input.context.compilerArguments.isEmpty() && input.snapshot.unknownInputs.isEmpty() &&
    input.snapshot.files.any { it.path == ".editorconfig" } &&
    input.context.compilationFiles?.let { files -> files.isNotEmpty() && files.all { it.endsWith(".kt") || it.endsWith(".kts") } } == true

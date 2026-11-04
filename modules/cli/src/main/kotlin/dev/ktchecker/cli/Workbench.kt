package dev.ktchecker.cli

import dev.ktchecker.compiler.KotlinJvmCompilerProvider
import dev.ktchecker.compiler.KotlinJvmToolchain
import dev.ktchecker.config.*
import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.*
import dev.ktchecker.providers.detekt.DetektInstallation
import dev.ktchecker.providers.detekt.DetektProvider
import dev.ktchecker.providers.detekt.DetektCatalog
import dev.ktchecker.providers.lint.AndroidLintProvider
import dev.ktchecker.providers.native.KotlinNativeProvider
import dev.ktchecker.providers.runtime.fileDigest
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
)

/** Trusted local composition root; ordinary MCP tools cannot call its owner-control methods. */
internal class Workbench(private val options: WorkbenchOptions) : AutoCloseable, RpcHandler {
    val state = PrivateState(options.state)
    private val catalog = Catalog()
    val snapshots = SnapshotStore(state.namespace("snapshots"), state.ownerId)
    private val grants = ExecutionGrants()
    private val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
    private val compilerInstallation = installCompiler(options.compilerDirectory ?: distributionHome()?.resolve("providers/compiler"))
    private val compiler = compilerInstallation?.let { KotlinJvmCompilerProvider(it, grants) }
    private val detekt = DetektProvider((options.detektDirectory ?: state.root.resolve("providers/detekt"))
        .takeIf { Files.isDirectory(it) }?.let { DetektInstallation(java, it, Path.of(System.getProperty("java.home"))) }, grants)
    private val lint = AndroidLintProvider(authority = grants)
    private val native = KotlinNativeProvider(authority = grants)
    private val routes = buildList {
        compiler?.let { provider -> add(ProviderRoute(provider, { it.context.scope.target.lowercase() in setOf("jvm", "desktop", "android") }, { provider.executionProfile(it) })) }
        add(ProviderRoute(detekt, { true }, detekt::executionProfile))
        add(ProviderRoute(lint, { it.context.scope.target.lowercase().contains("android") }))
        add(ProviderRoute(native, { it.context.scope.target in setOf("iosArm64", "iosSimulatorArm64") }))
    }
    private val coordinator = ReviewCoordinator(catalog, routes, grants)
    private val aliases = RuleAliases(catalog.records.map { record ->
        val id = record.getValue("id").jsonPrimitive.content
        val protected = record.getValue("category").jsonPrimitive.content.startsWith("kotlin.compiler") &&
            record.getValue("severity").jsonPrimitive.content == "error"
        PolicyRule(id, failureProtected = protected, implementationDigest = Digests.sha256(
            Digests.canonical(record) + routes.filter { id in it.provider.supportedRuleIds }.joinToString {
                providerIdentity(it.provider) + when (it.provider.id) {
                    "kotlin-compiler-jvm" -> compilerInstallation?.digest.orEmpty()
                    "detekt" -> Digests.canonical(ContractJson.encodeToJsonElement(DetektCatalog.pins))
                    else -> "no-qualified-upstream-installation"
                }
            },
        ))
    })
    val configs = ConfigStore(state.namespace("configuration"), aliases)
    private val loadedPolicies = ConcurrentHashMap<String, ConfigSnapshot>()
    private val records = ConcurrentHashMap<String, PreparedReview>()
    val kernel: KernelService = KernelService.open(state.namespace("broker"), coordinator,
        config = KernelConfig(workerCount = options.workerCount, globalParallelism = 2, perWorktreeParallelism = 1),
        snapshotGate = sourceGate(),
        preparedInputObserver = PreparedInputObserver { original, effective, verify ->
            val prepared = prepared(original)
            validateSnapshot(prepared)
            cachePolicy(prepared)
            grants.prepared(AuthorizedAttempt(original, effective,
                if (prepared.executionAuthorized) prepared.profiles else emptyMap(), verify))
        },
        findingProviders = coordinator.findingProviders,
        resultPolicy = ResultPolicy { binding, input, raw ->
            val policy = loadedPolicies[policyKey(binding, input)]
                ?: throw CliProblem("captured_policy_unavailable", "Captured policy was not restored for this consumer.")
            configs.apply(policy, input, raw)
        })

    override fun close() = kernel.close()

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
        val artifacts = (spec.classpath + if (spec.addKotlinStdlib) compilerInstallation?.compilerClasspath.orEmpty().filter {
            Path.of(it.path).fileName.toString().startsWith("kotlin-stdlib-")
        } else emptyList()).distinctBy { it.path }.sortedBy { it.coordinate }
        artifacts.forEach { if (fileDigest(Path.of(it.path)) != it.digest) throw CliProblem("artifact_changed", "Declared classpath artifact differs from its digest.") }
        val destination = state.namespace("materializations").resolve(UUID.randomUUID().toString())
        val materialized = snapshots.materialize(identity, spec.snapshotId, destination)
        val modelDigest = Digests.sha256(Digests.canonical(buildJsonObject {
            put("kind", "owner-declared-single-compilation"); put("scope", ContractJson.encodeToJsonElement(spec.scope))
            put("files", ContractJson.encodeToJsonElement(spec.compilationFiles.sorted()))
            put("arguments", ContractJson.encodeToJsonElement(spec.compilerArguments))
            put("libraries", ContractJson.encodeToJsonElement(spec.libraries))
        }))
        var input = AnalysisInput(materialized.manifest.toContractManifest(), materialized.root.toString(),
            AnalysisContext(scope = spec.scope, compilerVersion = "2.4.21", classpath = artifacts,
                compilerArguments = spec.compilerArguments, libraries = spec.libraries,
                policies = mapOf("ktchecker.model" to "owner-declared-single-compilation", "ktchecker.coverage" to "partial-project",
                    ACTIVATION_POLICY_KEY to ContractJson.encodeToString(ActivationPlan.serializer(), activation)),
                modelDigest = modelDigest, dependencyDigest = Digests.sha256(ContractJson.encodeToJsonElement(artifacts).toString()),
                toolchainDigest = compilerInstallation?.digest,
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
        state.locked("prepared:${input.analysisKey()}") { state.write(state.keyFile("prepared", input.analysisKey()), prepared) }
        records[input.analysisKey()] = prepared
        loadedPolicies[policyKey(binding, input)] = policy
        return prepared
    }

    fun submit(credential: ClientCredential, patchId: String, idempotencyKey: String, input: AnalysisInput): ReviewStatus {
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

    override fun call(method: String, params: JsonObject): JsonObject = try {
        when (method) {
            "doctor" -> { requireKeys(params, emptySet()); doctor() }
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

    private fun configView(snapshot: ConfigSnapshot): JsonObject = buildJsonObject {
        put("contractVersion", PREVIEW_CONTRACT); put("revision", ContractJson.encodeToJsonElement(snapshot.revisionToken))
        put("shared", ContractJson.encodeToJsonElement(snapshot.shared)); put("local", ContractJson.encodeToJsonElement(snapshot.local))
        put("effectiveSuppressions", ContractJson.encodeToJsonElement(snapshot.suppressions))
        put("effectiveRules", ContractJson.encodeToJsonElement(snapshot.ruleSettings))
    }

    private fun configBinding(binding: ClientBinding) = ConfigBinding(binding.ownerId, binding.workspaceId, binding.worktreeId, binding.lineageId)
    private fun policyKey(binding: ClientBinding, input: AnalysisInput) = Digests.sha256(ContractJson.encodeToJsonElement(binding).toString() + input.analysisKey())

    private fun installCompiler(directory: Path?): KotlinJvmToolchain? {
        if (directory == null || !Files.isDirectory(directory, NOFOLLOW_LINKS)) return null
        val artifacts = Files.list(directory).use { files -> files.filter { it.fileName.toString().endsWith(".jar") }.sorted().map {
            ArtifactIdentity("installed:${it.fileName}", fileDigest(it), it.toAbsolutePath().normalize().toString())
        }.toList() }
        return KotlinJvmToolchain(java, artifacts)
    }

    companion object {
        private val providerDigests = ConcurrentHashMap<String, String>()
        private fun providerIdentity(provider: AnalysisProvider): String = providerDigests.computeIfAbsent(provider.javaClass.name) {
            val source = Path.of(provider.javaClass.protectionDomain.codeSource.location.toURI())
            val digest = if (Files.isRegularFile(source)) fileDigest(source) else {
                val pieces = Files.walk(source).use { paths -> paths.filter { Files.isRegularFile(it) }
                    .sorted().map { source.relativize(it).toString() + ":" + fileDigest(it) }.toList() }
                Digests.sha256(pieces.joinToString("\n"))
            }
            "${provider.id}:${provider.version}:$digest"
        }
        private fun distributionHome(): Path? {
            val location = Path.of(Workbench::class.java.protectionDomain.codeSource.location.toURI())
            return if (Files.isRegularFile(location) && location.parent.fileName.toString() == "lib") location.parent.parent else null
        }
    }
}

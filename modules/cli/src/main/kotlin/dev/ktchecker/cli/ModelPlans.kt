package dev.ktchecker.cli

import dev.ktchecker.compiler.*
import dev.ktchecker.compiler.live.model.PreparedJvmLiveModelInput
import dev.ktchecker.compiler.live.model.bindPreparedLiveInput
import dev.ktchecker.config.*
import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.*
import dev.ktchecker.projectmodel.VerifiedProjectModel
import dev.ktchecker.projectmodel.ObservedProjectModel
import dev.ktchecker.projectmodel.CONFIGURATION_INPUT_CLOSURE_UNPROVEN
import dev.ktchecker.providers.runtime.ExecutionAuthority
import dev.ktchecker.providers.runtime.ExecutionPermit
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import dev.ktchecker.reviewcontroller.*
import dev.ktchecker.snapshot.MaterializedSnapshot
import dev.ktchecker.snapshot.SnapshotIdentity
import dev.ktchecker.snapshot.SnapshotStore
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Durable owner intent is evidence, NOT a deserializable model or execution capability. */
@Serializable internal data class ModelIntentEvidence(
    val binding: ClientBinding,
    val request: SubmitPlanRequest,
    val configuration: FrozenConfig,
    val executionAuthorized: Boolean,
    val approvalId: String,
    val schemaVersion: Int = 1,
    val executionMode: String = "cold-model",
    val originalDeadlineIdentity: String? = null,
    val originalDeadlineMillis: Long? = null,
)

/** Capture this before the owner runs the importer. Ordinary RPC cannot create or restore it. */
internal class OwnerModelIntent internal constructor(
    internal val origin: Any,
    internal val evidence: ModelIntentEvidence,
    val configuration: ConfigSnapshot,
    internal val originalDeadline: OwnedApplicationDeadline? = null,
) {
    val binding: ClientBinding get() = evidence.binding
    val request: SubmitPlanRequest get() = evidence.request
    internal val used = AtomicBoolean()
}

/** Provisional until its ENTIRE parent plan is committed; never accepted by scalar review.submit. */
@Serializable internal data class ModelInputEvidence(
    val binding: ClientBinding,
    val request: SubmitPlanRequest,
    val intentDigest: String,
    val input: AnalysisInput,
    val configuration: FrozenConfig,
    val executionAuthorized: Boolean,
    val profiles: Map<String, TrustedExecution>,
    val compilerRuleIds: List<String>,
    val compilerBindingDigest: String? = null,
    val approvalDigest: String? = null,
    val schemaVersion: Int = 1,
)

@Serializable internal data class ModelPlanEvidence(
    val plan: RequiredReviewPlan,
    val intentDigest: String,
    val inputKeys: List<String>,
    val preparationReasons: Map<String, String>,
    val schemaVersion: Int = 1,
)

@Serializable private data class ModelInputOwner(val binding: ClientBinding, val inputKey: String, val recordKey: String)

internal class PreparedOwnerModelPlan internal constructor(
    val assembly: AssembledReviewPlan,
    preparationReasons: Map<String, String>,
    internal val produced: OwnerProducedPlan? = null,
) {
    val request: SubmitPlanRequest get() = assembly.plan.request
    val preparationReasons: Map<String, String> = Collections.unmodifiableMap(preparationReasons.toSortedMap())
}

/** Whole attempted observed vector only; no accepted parent/patch or restorable live authority. */
internal class ObservedModelPlanUnavailable(val attemptedPlan: RequiredReviewPlan, reasons: Collection<String>) :
    RuntimeException("The whole observed parent could not be declared; no subset or scalar review was registered.") {
    val code: String = "observed_model_complete_declaration_unavailable"
    val reasons: List<String> = Collections.unmodifiableList((reasons + CONFIGURATION_INPUT_CLOSURE_UNPROVEN).distinct().sorted())
}

/**
 * Two different capability interfaces deliberately do not share an authorize return type. Every
 * entry is issued by the trusted archive observer from a LIVE PreparedJvmModelInput. Serialized
 * profiles, model tags, restored intent files and digest equality cannot populate this registry.
 */
internal class ModelAttemptGrants(private val maximum: Int = 16) {
    private data class Attempt(
        val effective: AnalysisInput,
        val child: AnalysisInput,
        val model: KotlinJvmModelPermit,
        val execution: ExecutionPermit,
    )
    private val lock = Any()
    private val parents = mutableMapOf<String, Attempt>()
    private val children = mutableMapOf<String, Attempt>()
    private val outputs = mutableMapOf<String, Attempt>()

    init { require(maximum in 1..64) }

    val executionAuthority = ExecutionAuthority { input -> synchronized(lock) {
        children[input.analysisKey()]?.takeIf { it.child == input && it.model.accepts(input) }?.execution
    } }
    val modelAuthority = KotlinJvmModelAuthority { input -> synchronized(lock) {
        children[input.analysisKey()]?.takeIf { it.child == input && it.model.accepts(input) }?.model
    } }

    fun prepared(prepared: PreparedJvmModelInput, original: AnalysisInput, effective: AnalysisInput, verifyFrozen: () -> Unit) {
        val parent = freezeModelInput(effective)
        val child = parent.copy(requiredRuleIds = prepared.compilerRuleIds, execution = prepared.compilerProfile)
        val model = prepared.bindAttempt(original, parent, child, verifyFrozen)
        try {
            val execution = ExecutionPermit(prepared.compilerProfile, child.snapshot.snapshotId, Path.of(child.materializedRoot),
                child.analysisKey(), verifyFrozen)
            val attempt = Attempt(parent, child, model, execution)
            synchronized(lock) {
                val output = requireNotNull(parent.outputRoot)
                if (parents.size >= maximum || parent.analysisKey() in parents || child.analysisKey() in children || output in outputs) {
                    throw KernelException("model_attempt_capacity_or_identity_conflict", "An owner model attempt cannot replace another live attempt.")
                }
                parents[parent.analysisKey()] = attempt
                children[child.analysisKey()] = attempt
                outputs[output] = attempt
            }
        } catch (error: Exception) { model.close(); throw error }
    }

    /** Sticky owner routing by the kernel's registered attempt output, not editable model tags. */
    fun routes(input: AnalysisInput): Boolean = synchronized(lock) { input.outputRoot?.let(outputs::containsKey) == true }

    fun child(input: AnalysisInput, requestedRules: List<String>): AnalysisInput = synchronized(lock) {
        val attempt = input.outputRoot?.let(outputs::get)
            ?: throw CliProblem("model_live_attempt_unavailable", "A live owner archive-bound compiler attempt is required.")
        if (attempt.effective != input || requestedRules != attempt.child.requiredRuleIds || !attempt.model.accepts(attempt.child)) {
            throw CliProblem("model_attempt_projection_mismatch", "The compiler child must retain its exact owner rule vector and profile.")
        }
        attempt.child
    }

    fun finish(input: AnalysisInput) {
        val removed = synchronized(lock) {
            val attempt = parents.remove(input.analysisKey()) ?: return@synchronized null
            children.remove(attempt.child.analysisKey())
            outputs.remove(requireNotNull(attempt.effective.outputRoot))
            attempt
        }
        removed?.model?.close()
    }

    internal fun activeCount(): Int = synchronized(lock) { parents.size }
}

/**
 * Owner-only typed composition, not Gradle ingress and not final live-worktree authority. The
 * caller supplies a REAL importer-created model after begin() captured the client's policy and
 * explicit execution decision. One whole parent is the only admission path. Restarts restore
 * auditable policy/plan evidence, NEVER importer capabilities, approvals or prepared model grants.
 * The initial implementation deliberately denies cross-client sharing of one computation input.
 * An exact input is also pinned to its original parent request within one client: retry that
 * request idempotently rather than reusing its approval for a different parent. This is a preview
 * limitation, not a declaration that concurrent multi-client delivery is release-complete.
 */
internal class OwnerModelPlans(
    private val state: PrivateState,
    private val snapshots: SnapshotStore,
    private val configs: ConfigStore,
    private val catalog: Catalog,
    private val installation: KotlinJvmToolchain?,
    private val compiler: KotlinJvmCompilerProvider?,
    private val attempts: ModelAttemptGrants,
    private val verifyCode: () -> Unit,
    private val maxPlans: Int = 1024,
    private val maxInputs: Int = 4096,
    private val liveRuntime: OwnerLiveModelRuntime? = null,
    private val parentProvider: AnalysisProvider? = null,
) : ReviewPlanAuthority {
    private val origin = Any()
    private val publicationLock = Any()
    private val registry = OwnerReviewPlanRegistry(state.ownerId, maxPlans)
    private val intents = ConcurrentHashMap<String, OwnerModelIntent>()
    private val committed = ConcurrentHashMap<String, ModelPlanEvidence>()
    private val livePlans = ConcurrentHashMap<String, PreparedOwnerModelPlan>()
    private val records = ConcurrentHashMap<String, ModelInputEvidence>()
    private val policies = ConcurrentHashMap<String, ConfigSnapshot>()
    private val byOriginalInput = ConcurrentHashMap<String, String>()
    private val liveInputs = ConcurrentHashMap<String, PreparedJvmModelInput>()
    private val preparedLiveInputs = ConcurrentHashMap<String, PreparedJvmLiveModelInput>()
    private val intentLock = Any()
    // Failed/overlapping starts retain their original clock too; do not retry with a fresh budget.
    private val liveIntentStarts = mutableMapOf<String, OwnedApplicationDeadline>()
    private val inventory = OwnerCatalogInventory(modelDigest(JsonArray(catalog.records)), catalog.ids)

    init {
        require(maxPlans in 1..4096 && maxInputs in 1..16384)
        require((installation == null) == (compiler == null))
        require(compiler == null || compiler.inputMode == KotlinJvmInputMode.VERIFIED_MODEL)
        require(liveRuntime == null || (installation == null && compiler == null && liveRuntime.ownerId == state.ownerId))
        restoreEvidence()
    }

    /** Start at the owner API boundary, before registration IO or request-key serialization. */
    fun ingressDeadline(): OwnedApplicationDeadline? =
        liveRuntime?.let { OwnedApplicationDeadline.afterMillis(it.reviewTimeoutMillis) }

    /** Only the explicit owner control calls this, before model export/import or admission. */
    fun begin(binding: ClientBinding, request: SubmitPlanRequest, authorizeExecution: Boolean,
              originalIngressDeadline: OwnedApplicationDeadline? = ingressDeadline()): OwnerModelIntent {
        require((liveRuntime == null) == (originalIngressDeadline == null))
        require(liveRuntime == null || originalIngressDeadline?.durationMillis == liveRuntime.reviewTimeoutMillis)
        if (binding.ownerId != state.ownerId) throw CliProblem("permission_denied", "Model preparation belongs to another owner.")
        val key = planKey(binding, request)
        fun duplicate(it: OwnerModelIntent): OwnerModelIntent {
            if (it.evidence.executionAuthorized != authorizeExecution) throw CliProblem("model_intent_conflict", "Execution consent cannot be changed in place.")
            return it
        }
        // This is the ORIGINAL live ingress clock, before code/config verification and importer
        // work. An overlapping call gets an explicit pending result; it never refreshes the clock.
        val deadline = if (liveRuntime != null) synchronized(intentLock) {
            intents[key]?.let { return duplicate(it) }
            if (key in liveIntentStarts) throw CliProblem("model_live_intent_pending_or_unavailable", "Retry cannot replace an original live intent clock.")
            if (liveIntentStarts.size >= maxPlans) throw CliProblem("model_live_intent_capacity", "Live intent tombstones are bounded and not evicted.")
            requireNotNull(originalIngressDeadline).also { liveIntentStarts[key] = it }
        } else null
        checkDeadline(deadline)
        verifyCode()
        checkDeadline(deadline)
        intents[key]?.let { return duplicate(it) }
        // Capture outside controller file locks. Repeated explicit owner actions retain the old
        // immutable revision rather than silently replacing an already-bound policy with latest.
        val captured = configs.capture(modelConfigBinding(binding))
        checkDeadline(deadline)
        val candidate = ModelIntentEvidence(binding, request, configs.exportSnapshot(captured), authorizeExecution, UUID.randomUUID().toString(),
            executionMode = if (liveRuntime == null) "cold-model" else "live-model-preview",
            originalDeadlineIdentity = deadline?.identity, originalDeadlineMillis = deadline?.durationMillis)
        val evidence = state.locked("model-intents") {
            checkDeadline(deadline)
            val file = state.keyFile("model-intents", key)
            if (Files.exists(file, NOFOLLOW_LINKS)) state.read<ModelIntentEvidence>(file).also {
                if (deadline != null || it.executionMode != "cold-model") {
                    throw CliProblem("model_live_intent_not_restorable", "Saved intent evidence cannot recreate an original clock or live application authority.")
                }
                if (it.schemaVersion != 1 || it.binding != binding || it.request != request || it.executionAuthorized != authorizeExecution) {
                    throw CliProblem("model_intent_conflict", "A captured owner request cannot be rebound or escalated.")
                }
            } else {
                boundedFiles("model-intents", maxPlans - 1)
                state.write(file, candidate)
                candidate
            }
        }
        val policy = if (evidence == candidate) captured else configs.restoreSnapshot(evidence.configuration, evidence.configuration.revisionToken)
        checkDeadline(deadline)
        val intent = OwnerModelIntent(origin, evidence, policy, deadline)
        return intents.putIfAbsent(key, intent) ?: intent
    }

    fun prepare(intent: OwnerModelIntent, before: VerifiedProjectModel?, after: VerifiedProjectModel,
                materialized: MaterializedSnapshot): PreparedOwnerModelPlan {
        checkDeadline(intent.originalDeadline)
        if (intent.origin !== origin || intents[planKey(intent.binding, intent.request)] !== intent || !intent.used.compareAndSet(false, true)) {
            throw CliProblem("model_intent_invalid_or_replayed", "Use this owner's live, unused captured model intent.")
        }
        // Reserve BEFORE any source/config/compiler preparation IO, including for an import
        // which will remain unsupported. A slow old A cannot enroll after B's prepare reserved.
        val imported = liveRuntime?.reserveImport(intent, after)
        verifyCode()
        checkDeadline(intent.originalDeadline)
        if (after.identity.configurationDigest != intent.configuration.policyDigest ||
            intent.configuration.binding != modelConfigBinding(intent.binding)) {
            throw CliProblem("model_captured_policy_mismatch", "Model ingress must use the exact configuration captured before import.")
        }
        val source = materialization(after, materialized, intent.binding)
        val staged = ConcurrentHashMap<PolicyPreparationRequest, ExecutionPreparation>()
        val preparedInputs = ConcurrentHashMap<String, PreparedJvmModelInput>()
        val stagedLiveInputs = ConcurrentHashMap<String, PreparedJvmLiveModelInput>()
        val reasons = linkedMapOf<String, String>()
        val policyPreparer = OwnerPolicyPreparer { request ->
            val activation = activationPlan(catalog, request.input.requiredRuleIds, false,
                intent.configuration.ruleSettings.mapValues { it.value.enabled }, intent.configuration.policyDigest)
            val identity = CapturedPolicyIdentity(request.binding, intent.configuration.policyDigest,
                modelDigest(ContractJson.encodeToJsonElement(intent.configuration.revisionToken)))
            CapturedPlanPolicy(request, identity, request.withActivation(PlanActivation(activation.policyDigest,
                activation.decisions.map { PlanRuleActivation(it.ruleId, it.active, it.origin, it.reason) }))) { final ->
                val execution = staged[request]
                val evidence = ModelInputEvidence(request.binding, request.parentRequest, modelDigest(ContractJson.encodeToJsonElement(intent.evidence)),
                    freezeModelInput(final), intent.evidence.configuration, intent.evidence.executionAuthorized,
                    execution?.profiles.orEmpty(), execution?.rules.orEmpty(), execution?.binding?.identity?.digest,
                    execution?.identity?.approvalDigest)
                retainProvisional(evidence, intent.configuration)
                identity
            }
        }
        val executionPreparer = OwnerExecutionPreparer { request ->
            val id = requireNotNull(request.compilationId)
            val execution = try {
                if (!intent.evidence.executionAuthorized) throw CliProblem("model_execution_not_authorized", "Owner execution approval was not captured.")
                val selected = liveRuntime?.installation ?: installation
                    ?: throw CliProblem("model_compiler_installation_unavailable", "The exact selected compiler/JDK is unavailable.")
                val provider: AnalysisProvider = liveRuntime?.provider ?: requireNotNull(compiler)
                verifyCode()
                checkDeadline(intent.originalDeadline)
                val binding = KotlinJvmModelBinding.verifyAndBind(request.model, id, selected) { checkDeadline(intent.originalDeadline) }
                verifyCode()
                val active = validatedActivation(request.input, catalog)
                val rules = request.input.requiredRuleIds.filter { active[it]?.active == true && it in provider.supportedRuleIds }
                if (rules.isEmpty()) throw CliProblem("model_compiler_rules_inactive", "No compiler rules were explicitly enabled in the captured owner policy.")
                val profiles = if (liveRuntime != null) emptyMap() else mapOf(provider.id to
                    requireNotNull(compiler).executionProfile(request.input.copy(requiredRuleIds = rules), binding))
                val approval = modelDigest(buildJsonObject {
                    put("mode", if (liveRuntime == null) KotlinJvmInputMode.VERIFIED_MODEL.name else "live-model-preview")
                    liveRuntime?.let { put("runtimeOwner", it.owner.identity); put("implementation", it.implementationIdentity) }
                    intent.originalDeadline?.let { put("originalDeadline", it.identity) }
                    put("intent", modelDigest(ContractJson.encodeToJsonElement(intent.evidence)))
                    put("model", after.ingressCertificate); put("input", request.input.analysisKey())
                    put("binding", binding.identity.digest)
                    put("profiles", ContractJson.encodeToJsonElement(profiles))
                    put("compilerRuleIds", ContractJson.encodeToJsonElement(rules))
                })
                ExecutionPreparation(binding, rules, profiles, CapturedExecutionIdentity(request.binding,
                    request.input.analysisKey(), requireNotNull(request.input.context.toolchainDigest), approval))
            } catch (error: KotlinJvmModelException) { reasons[id] = error.code; null }
              catch (error: KotlinJvmRuntimeException) { reasons[id] = error.code; null }
              catch (error: CliProblem) { reasons[id] = error.code; null }
            if (execution == null) null else {
                staged[request.policyRequest] = execution
                CapturedPlanExecution(request, execution.identity, execution.profiles, request.withProfiles(execution.profiles)) { final ->
                    verifyCode()
                    checkDeadline(intent.originalDeadline)
                    if (liveRuntime != null) {
                        stagedLiveInputs[modelPolicyKey(intent.binding, final)] = execution.binding.bindPreparedLiveInput(
                            final, execution.rules, liveRuntime.provider, requireNotNull(intent.originalDeadline))
                    } else {
                        preparedInputs[modelPolicyKey(intent.binding, final)] = execution.binding.bindPreparedInput(
                            final, execution.rules, requireNotNull(compiler), execution.profiles)
                    }
                    execution.identity
                }
            }
        }
        var assembly = OwnerPlanAssembler(inventory, policyPreparer, executionPreparer).assemble(intent.binding, intent.request,
            before, after, source, listOf(intent.binding.clientId))
        var produced: OwnerProducedPlan? = null
        if (imported != null && intent.evidence.executionAuthorized && parentProvider != null && after.jvmProducedGraph != null) {
            try {
                val runtime = requireNotNull(liveRuntime)
                val selected = runtime.prepareProduced(imported)
                val deadline = requireNotNull(intent.originalDeadline)
                val declared = assembly.declareJvmProduced(modelDigest(ContractJson.encodeToJsonElement(intent.evidence)),
                    deadline.identity, deadline.durationMillis) { verifyCode(); runtime.verifyProduced(selected) }
                if (declared == null) {
                    after.jvmProducedGraph!!.compilationIds.forEach { reasons.putIfAbsent(it, "owner_produced_complete_declaration_unavailable") }
                } else {
                    produced = OwnerProducedPlan(declared, runtime, selected, catalog, parentProvider, verifyCode)
                    assembly = declared
                }
            } catch (_: Exception) {
                // Keep the ENTIRE original unsupported vector; never retry as a smaller/scalar
                // or ordinary cold plan. A partially enrolled preview is a retained tombstone.
                after.jvmProducedGraph!!.compilationIds.forEach { reasons.putIfAbsent(it, "owner_produced_preparation_unavailable") }
            }
        }
        registry.register(assembly)
        val evidence = ModelPlanEvidence(assembly.plan, modelDigest(ContractJson.encodeToJsonElement(intent.evidence)),
            assembly.plan.slots.map { modelPolicyKey(intent.binding, it.input) }, reasons.toSortedMap())
        val key = planKey(intent.binding, intent.request)
        state.locked("model-plans") {
            val file = state.keyFile("model-plans", key)
            if (Files.exists(file, NOFOLLOW_LINKS)) {
                if (state.read<ModelPlanEvidence>(file) != evidence) throw CliProblem("model_plan_conflict", "A committed whole parent cannot be replaced.")
            } else {
                boundedFiles("model-plans", maxPlans - 1)
                state.write(file, evidence)
            }
        }
        // Nothing before this whole-plan commit grants per-input admission or a live attempt.
        publishEvidence(evidence)
        liveInputs.putAll(preparedInputs)
        preparedLiveInputs.putAll(stagedLiveInputs)
        return PreparedOwnerModelPlan(assembly, reasons, produced).also { livePlans[key] = it }
    }

    /** Explicit observed-only route. Prior model facts are documentary; only the fresh after
     * token can enroll the original intent/generation and the whole two-slot declared parent. */
    fun prepareObserved(intent: OwnerModelIntent, before: ObservedProjectModel?, after: ObservedProjectModel,
        materialized: MaterializedSnapshot): PreparedOwnerModelPlan {
        checkDeadline(intent.originalDeadline)
        val runtime = liveRuntime ?: throw CliProblem("observed_model_live_route_required",
            "Observed export diagnostics require the explicitly installed owner live-model route.")
        if (intent.origin !== origin || intents[planKey(intent.binding, intent.request)] !== intent ||
            intent.originalDeadline !== after.invocation.originalDeadline || !intent.used.compareAndSet(false, true)) {
            throw CliProblem("model_intent_invalid_or_replayed", "Use this owner's live unused intent and the same pre-export clock.")
        }
        // Same prepare-time linearization boundary as verified imports. The namespace is fenced
        // before any code/config/source preparation, even if this vector cannot be declared.
        val imported = runtime.reserveObservedImport(intent, after)
        verifyCode()
        checkDeadline(intent.originalDeadline)
        after.invocation.verifyLive()
        if (after.identity.configurationDigest != intent.configuration.policyDigest ||
            intent.configuration.binding != modelConfigBinding(intent.binding)) {
            throw CliProblem("model_captured_policy_mismatch", "Observed import must retain the exact policy captured before export.")
        }
        val source = observedMaterialization(after, materialized, intent.binding)
        val policyPreparer = OwnerPolicyPreparer { request ->
            if (request.observedModel !== after) throw CliProblem("observed_model_policy_origin_mismatch",
                "The captured policy callback must retain the exact observed model token.")
            val activation = activationPlan(catalog, request.input.requiredRuleIds, false,
                intent.configuration.ruleSettings.mapValues { it.value.enabled }, intent.configuration.policyDigest)
            val identity = CapturedPolicyIdentity(request.binding, intent.configuration.policyDigest,
                modelDigest(ContractJson.encodeToJsonElement(intent.configuration.revisionToken)))
            CapturedPlanPolicy(request, identity, request.withActivation(PlanActivation(activation.policyDigest,
                activation.decisions.map { PlanRuleActivation(it.ruleId, it.active, it.origin, it.reason) }))) { final ->
                checkDeadline(intent.originalDeadline)
                val evidence = ModelInputEvidence(request.binding, request.parentRequest,
                    modelDigest(ContractJson.encodeToJsonElement(intent.evidence)), freezeModelInput(final),
                    intent.evidence.configuration, intent.evidence.executionAuthorized, emptyMap(), emptyList())
                retainProvisional(evidence, intent.configuration)
                identity
            }
        }
        val attempted = OwnerPlanAssembler(inventory, policyPreparer, OwnerExecutionPreparer.DENY).assembleObserved(
            intent.binding, intent.request, before, after, source, listOf(intent.binding.clientId))
        fun unavailable(reason: String): Nothing = throw ObservedModelPlanUnavailable(attempted.plan,
            attempted.plan.slots.flatMap { it.reasons } + reason)
        if (!intent.evidence.executionAuthorized || parentProvider == null || after.jvmProducedGraph == null) {
            unavailable("owner_observed_complete_declaration_unavailable")
        }
        val assembly: AssembledReviewPlan
        val produced: OwnerProducedPlan
        try {
            val selected = runtime.prepareProduced(imported)
            val deadline = requireNotNull(intent.originalDeadline)
            assembly = attempted.declareObservedJvmProduced(modelDigest(ContractJson.encodeToJsonElement(intent.evidence)),
                deadline.identity, deadline.durationMillis) { verifyCode(); runtime.verifyProduced(selected) }
                ?: unavailable("owner_observed_complete_declaration_unavailable")
            produced = OwnerProducedPlan(assembly, runtime, selected, catalog, parentProvider, verifyCode)
        } catch (failure: ObservedModelPlanUnavailable) { throw failure }
          catch (_: Exception) { unavailable("owner_observed_preparation_unavailable") }
        val reasons = after.compilationIds.associateWith { CONFIGURATION_INPUT_CLOSURE_UNPROVEN }
        // Register and commit ONLY the complete declared vector. Unsupported observed vectors
        // are diagnostic evidence above, never generic plans bearing reserved observed keys.
        registry.register(assembly)
        val evidence = ModelPlanEvidence(assembly.plan, modelDigest(ContractJson.encodeToJsonElement(intent.evidence)),
            assembly.plan.slots.map { modelPolicyKey(intent.binding, it.input) }, reasons.toSortedMap())
        val key = planKey(intent.binding, intent.request)
        state.locked("model-plans") {
            val file = state.keyFile("model-plans", key)
            if (Files.exists(file, NOFOLLOW_LINKS)) {
                if (state.read<ModelPlanEvidence>(file) != evidence) throw CliProblem("model_plan_conflict", "A committed whole parent cannot be replaced.")
            } else {
                boundedFiles("model-plans", maxPlans - 1)
                state.write(file, evidence)
            }
        }
        publishEvidence(evidence)
        // No ordinary cold/live prepared-input registry is populated by the observed route.
        return PreparedOwnerModelPlan(assembly, reasons, produced).also { livePlans[key] = it }
    }

    override fun resolve(binding: ClientBinding, request: SubmitPlanRequest): RequiredReviewPlan? {
        val key = planKey(binding, request)
        if (livePlans[key] == null) {
            if (committed.containsKey(key)) throw KernelException("model_live_authority_unavailable", "A restart retains obligations and policy evidence, not live model authority.")
            return null
        }
        verifyCode()
        return registry.resolve(binding, request)
    }

    override fun authorizeProduced(request: ProducedPlanAdmissionRequest): AuthorizedProducedPlan? {
        val key = planKey(request.binding, request.request)
        val plan = livePlans[key] ?: return null
        if (plan.assembly.plan != request.definition || committed[key]?.plan != request.definition) return null
        verifyCode()
        return plan.produced?.authorize(request)
    }

    /** No lookup by P or model tag: the entire committed kernel parent tuple is mandatory. */
    fun observeProduced(attempt: ProducedExecutionAttempt) {
        val key = planKey(attempt.admission.binding, attempt.admission.request)
        val plan = livePlans[key] ?: throw KernelException("model_live_authority_unavailable", "The live produced parent is unavailable.")
        if (plan.assembly.plan != attempt.admission.definition || committed[key]?.plan != attempt.admission.definition) {
            throw KernelException("model_produced_parent_mismatch", "A typed attempt belongs to another whole committed parent.")
        }
        val produced = plan.produced ?: throw KernelException("model_produced_authority_unavailable", "No live declared preparation exists.")
        verifyCode()
        produced.observe(attempt)
    }

    /** Kernel observer only. Source/dependency paths below are the archive, not old model inputs. */
    fun observe(original: AnalysisInput, effective: AnalysisInput, verifyFrozen: () -> Unit) {
        val evidence = evidence(original) ?: throw KernelException("model_plan_not_committed", "The whole owner model plan was not committed.")
        if (policies[modelPolicyKey(evidence.binding, original)] == null) {
            throw KernelException("model_captured_policy_unavailable", "The accepted model policy could not be restored.")
        }
        if (!evidence.executionAuthorized || !livePlans.containsKey(planKey(evidence.binding, evidence.request))) {
            throw KernelException("model_live_authority_unavailable", "An uncommitted or restored approval cannot authorize a compiler attempt.")
        }
        verifyCode()
        if (liveRuntime != null) {
            val prepared = preparedLiveInputs[modelPolicyKey(evidence.binding, original)]
                ?: throw KernelException("model_live_authority_unavailable", "A restart cannot recreate a live imported preparation or its original clock.")
            checkDeadline(prepared.originalDeadline)
            liveRuntime.prepared(prepared, original, effective, verifyFrozen)
        } else {
            val prepared = liveInputs[modelPolicyKey(evidence.binding, original)]
                ?: throw KernelException("model_live_authority_unavailable", "An accepted review survives restart; its live importer/compiler capability does not.")
            attempts.prepared(prepared, original, effective, verifyFrozen)
        }
    }

    /** Pure lookup for the kernel's publication transaction; all restoration occurs beforehand. */
    fun policy(binding: ClientBinding, input: AnalysisInput): ConfigSnapshot? {
        val key = modelPolicyKey(binding, input)
        // A sibling retained before a later preparation failure is not an admitted consumer.
        return if (byOriginalInput[input.analysisKey()] == key && records[key]?.input == input) policies[key] else null
    }

    fun evidence(input: AnalysisInput): ModelInputEvidence? = byOriginalInput[input.analysisKey()]?.let(records::get)?.also {
        if (it.input != input) throw CliProblem("model_input_identity_mismatch", "Accepted model input differs from its exact owner evidence.")
    }

    private data class ExecutionPreparation(val binding: KotlinJvmModelBinding, val rules: List<String>,
        val profiles: Map<String, TrustedExecution>, val identity: CapturedExecutionIdentity)

    private fun checkDeadline(deadline: OwnedApplicationDeadline?) {
        if (deadline == null) return
        if (deadline.isExpired()) throw CliProblem("model_live_original_deadline_expired", "Original intent budget was exhausted; no new clock is granted.")
        if (Thread.currentThread().isInterrupted) throw CliProblem("model_live_interrupted", "Live model preparation was interrupted.")
    }

    private fun retainProvisional(evidence: ModelInputEvidence, policy: ConfigSnapshot) {
        val key = modelPolicyKey(evidence.binding, evidence.input)
        if (evidence.configuration.binding != modelConfigBinding(evidence.binding) ||
            evidence.configuration.revisionToken.policyDigest != evidence.input.context.configurationDigest) {
            throw CliProblem("model_captured_policy_mismatch", "Every context requires its exact per-client captured policy.")
        }
        state.locked("model-inputs") {
            val ownerFile = state.keyFile("model-input-owners", evidence.input.analysisKey())
            val owner = ModelInputOwner(evidence.binding, evidence.input.analysisKey(), key)
            if (Files.exists(ownerFile, NOFOLLOW_LINKS) && state.read<ModelInputOwner>(ownerFile) != owner) {
                throw CliProblem("model_cross_client_authority_unavailable", "One computation cannot borrow another client's captured approval.")
            }
            val file = state.keyFile("model-inputs", key)
            if (Files.exists(file, NOFOLLOW_LINKS)) {
                if (state.read<ModelInputEvidence>(file) != evidence) throw CliProblem("model_prepared_input_conflict", "A client's captured input/approval cannot be overwritten.")
            } else {
                boundedFiles("model-inputs", maxInputs - 1)
                state.write(file, evidence)
            }
            if (!Files.exists(ownerFile, NOFOLLOW_LINKS)) state.write(ownerFile, owner)
        }
        records[key] = evidence
        policies[key] = policy
    }

    private fun publishEvidence(evidence: ModelPlanEvidence) {
        val expectedKeys = evidence.plan.slots.map { modelPolicyKey(evidence.plan.binding, it.input) }
        if (evidence.schemaVersion != 1 || evidence.plan.binding.ownerId != state.ownerId || evidence.inputKeys != expectedKeys ||
            evidence.plan.requiredRecipientClientIds != listOf(evidence.plan.binding.clientId)) {
            throw CliProblem("model_plan_evidence_invalid", "Only the complete same-client owner vector can be restored as evidence.")
        }
        val restored = evidence.plan.slots.zip(expectedKeys).map { (slot, key) ->
            val record = records[key] ?: state.read<ModelInputEvidence>(state.keyFile("model-inputs", key))
            if (record.schemaVersion != 1 || record.binding != evidence.plan.binding || record.request != evidence.plan.request ||
                record.intentDigest != evidence.intentDigest || record.input != slot.input || record.configuration.binding != modelConfigBinding(record.binding) ||
                record.configuration.revisionToken.policyDigest != slot.input.context.configurationDigest) {
                throw CliProblem("model_input_evidence_invalid", "Whole-plan evidence does not match every captured input/policy/approval.")
            }
            val owner = state.read<ModelInputOwner>(state.keyFile("model-input-owners", record.input.analysisKey()))
            if (owner != ModelInputOwner(record.binding, record.input.analysisKey(), key)) {
                throw CliProblem("model_cross_client_authority_unavailable", "A committed model input has a different captured consumer.")
            }
            key to record.copy(input = freezeModelInput(record.input), profiles = freezeModelProfiles(record.profiles),
                compilerRuleIds = Collections.unmodifiableList(record.compilerRuleIds.toList()))
        }
        // Cross-client shared authority is not implemented here. Do not select an arbitrary owner
        // approval when the worker observer (which has no client argument) names one computation.
        val restoredPolicies = restored.associate { (key, record) -> key to (policies[key] ?: runCatching {
            configs.restoreSnapshot(record.configuration, record.configuration.revisionToken)
        }.getOrNull()) }
        synchronized(publicationLock) {
            for ((key, record) in restored) {
                val old = byOriginalInput[record.input.analysisKey()]
                if (old != null && old != key) throw CliProblem("model_cross_client_authority_unavailable", "Separate clients cannot borrow one another's model approval.")
            }
            for ((key, record) in restored) {
                records[key] = record
                restoredPolicies[key]?.let { policies[key] = it }
                byOriginalInput[record.input.analysisKey()] = key
            }
            committed[planKey(evidence.plan.binding, evidence.plan.request)] = evidence
        }
    }

    private fun restoreEvidence() {
        // Snapshot the immutable file list under the same brief commit lock so another owner
        // instance's in-flight atomic-replacement temporary is not mistaken for corrupt state.
        val plans = state.locked("model-plans") { boundedFiles("model-plans", maxPlans) }
        plans.sortedBy { it.fileName.toString() }.forEach { path ->
            val evidence = state.read<ModelPlanEvidence>(path)
            if (state.keyFile("model-plans", planKey(evidence.plan.binding, evidence.plan.request)) != path) {
                throw CliProblem("model_plan_evidence_invalid", "Stored model plan has another reference identity.")
            }
            publishEvidence(evidence)
        }
    }

    private fun boundedFiles(namespace: String, maximum: Int): List<Path> {
        val selected = mutableListOf<Path>()
        Files.newDirectoryStream(state.namespace(namespace)).use { files -> files.forEach { path ->
            if (selected.size >= maximum || !path.fileName.toString().matches(Regex("[0-9a-f]{64}\\.json")) ||
                !Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                throw CliProblem("model_state_limit_or_invalid", "Bounded owner model state is full or contains an unexpected entry.")
            }
            selected.add(path)
        } }
        return selected
    }

    private fun observedMaterialization(model: ObservedProjectModel, materialized: MaterializedSnapshot,
        binding: ClientBinding): OwnerObservedPlanMaterialization {
        val identity = SnapshotIdentity(binding.ownerId, binding.workspaceId, binding.worktreeId)
        val root = materialized.root
        val registered = snapshots.snapshot(identity, model.snapshot.snapshotId)
        if (registered != materialized.manifest || registered.toContractManifest() != model.snapshot || !registered.capture.coherent ||
            !root.isAbsolute || root.normalize() != root) {
            throw CliProblem("model_materialization_mismatch", "Use the exact whole registered coherent SnapshotStore materialization.")
        }
        val key = rootIdentity(root)
        val authority = modelDigest(buildJsonObject {
            put("store", snapshots.root.toString()); put("root", root.toString()); put("rootIdentity", key)
            put("registered", ContractJson.encodeToJsonElement(registered))
        })
        return OwnerObservedPlanMaterialization(model, root) {
            if (snapshots.snapshot(identity, model.snapshot.snapshotId) != registered || rootIdentity(root) != key ||
                !snapshots.verifyMaterialization(materialized).valid || rootIdentity(root) != key) null
            else MaterializationVerification(model.snapshot, root.toString(), authority)
        }
    }

    private fun materialization(model: VerifiedProjectModel, materialized: MaterializedSnapshot, binding: ClientBinding): OwnerPlanMaterialization {
        val identity = SnapshotIdentity(binding.ownerId, binding.workspaceId, binding.worktreeId)
        val root = materialized.root
        val registered = snapshots.snapshot(identity, model.snapshot.snapshotId)
        if (registered != materialized.manifest || registered.toContractManifest() != model.snapshot || !registered.capture.coherent ||
            !root.isAbsolute || root.normalize() != root) {
            throw CliProblem("model_materialization_mismatch", "Use the exact whole registered coherent SnapshotStore materialization.")
        }
        val key = rootIdentity(root)
        val authority = modelDigest(buildJsonObject {
            put("store", snapshots.root.toString()); put("root", root.toString()); put("rootIdentity", key)
            put("registered", ContractJson.encodeToJsonElement(registered))
        })
        return OwnerPlanMaterialization(model, root) {
            if (snapshots.snapshot(identity, model.snapshot.snapshotId) != registered || rootIdentity(root) != key ||
                !snapshots.verifyMaterialization(materialized).valid || rootIdentity(root) != key) null
            else dev.ktchecker.reviewcontroller.MaterializationVerification(model.snapshot, root.toString(), authority)
        }
    }
}

private fun rootIdentity(root: Path): String {
    val attributes = Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    if (!attributes.isDirectory || attributes.isSymbolicLink || attributes.fileKey() == null) {
        throw CliProblem("model_materialization_unavailable", "A stable ordinary registered materialization is required.")
    }
    return attributes.fileKey().toString()
}

internal fun modelConfigBinding(binding: ClientBinding): ConfigBinding =
    ConfigBinding(binding.ownerId, binding.workspaceId, binding.worktreeId, binding.lineageId)

internal fun modelPolicyKey(binding: ClientBinding, input: AnalysisInput): String = modelDigest(buildJsonObject {
    put("binding", ContractJson.encodeToJsonElement(binding)); put("input", input.analysisKey())
})

private fun planKey(binding: ClientBinding, request: SubmitPlanRequest): String = modelDigest(buildJsonObject {
    put("binding", ContractJson.encodeToJsonElement(binding)); put("request", ContractJson.encodeToJsonElement(request))
})

private fun modelDigest(value: JsonElement): String = Digests.sha256(Digests.canonical(value))
private fun freezeModelProfile(profile: TrustedExecution): TrustedExecution = profile.copy(
    arguments = Collections.unmodifiableList(profile.arguments.toList()), environment = Collections.unmodifiableMap(profile.environment.toMap()))

private fun freezeModelProfiles(profiles: Map<String, TrustedExecution>): Map<String, TrustedExecution> =
    Collections.unmodifiableMap(profiles.toSortedMap().mapValues { freezeModelProfile(it.value) })

internal fun freezeModelInput(input: AnalysisInput): AnalysisInput = input.copy(
    snapshot = input.snapshot.copy(files = Collections.unmodifiableList(input.snapshot.files.map { it.copy() }),
        declaredRoots = Collections.unmodifiableList(input.snapshot.declaredRoots.toList()),
        scopeExclusions = Collections.unmodifiableList(input.snapshot.scopeExclusions.toList()),
        unknownInputs = Collections.unmodifiableList(input.snapshot.unknownInputs.toList())),
    context = input.context.copy(classpath = Collections.unmodifiableList(input.context.classpath.map { it.copy() }),
        compilerArguments = Collections.unmodifiableList(input.context.compilerArguments.toList()),
        compilationFiles = input.context.compilationFiles?.let { Collections.unmodifiableList(it.toList()) },
        libraries = Collections.unmodifiableMap(input.context.libraries.toMap()), policies = Collections.unmodifiableMap(input.context.policies.toMap())),
    requiredRuleIds = Collections.unmodifiableList(input.requiredRuleIds.toList()),
    changedPaths = Collections.unmodifiableList(input.changedPaths.toList()), execution = input.execution?.let(::freezeModelProfile))

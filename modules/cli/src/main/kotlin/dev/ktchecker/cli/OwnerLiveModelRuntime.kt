package dev.ktchecker.cli

import dev.ktchecker.compiler.KotlinJvmToolchain
import dev.ktchecker.compiler.live.CompilerApplicationNamespace
import dev.ktchecker.compiler.live.model.*
import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.ProducedExecutionAttempt
import dev.ktchecker.kernel.ProducedPlanValidity
import dev.ktchecker.projectmodel.ModelNamespace
import dev.ktchecker.projectmodel.VerifiedProjectModel
import dev.ktchecker.projectmodel.ObservedProjectModel
import dev.ktchecker.projectmodel.ObservedExportInvocation
import dev.ktchecker.workerprotocol.CompilerModelWire
import dev.ktchecker.providers.runtime.*
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

/** Mandatory trusted-owner custody hooks, not callbacks reconstructed from input/profile JSON. */
internal interface OwnerModelMaterializationCustody {
    /** Exact typed owner/kernel relation before any produced attempt materialization. */
    fun registerProduced(attempt: ProducedExecutionAttempt, compilation: JvmProducedPreviewCompilation,
        prepared: PreparedJvmLiveModelInput) {
        throw CliProblem("model_live_declared_custody_unavailable", "Declared attempts require explicit whole-plan owner custody.")
    }
    fun register(materialization: ModelCompilerJobMaterialization)
    fun verify(materialization: ModelCompilerJobMaterialization)
}

/** Detached status of the original open/retirement calls, never authority to create another one. */
internal data class OwnerLiveGenerationStatus(
    val generationId: String,
    val namespaceDigest: String,
    val state: String,
    val opened: ModelCompilerApplicationOpenResult?,
    val retirement: OwnedApplicationRetirement?,
    val reason: String?,
)

/** Epoch reserved inside OwnerModelPlans.prepare BEFORE its preparation IO, not at begin/export. */
internal class OwnerImportedModel internal constructor(val intent: OwnerModelIntent, internal val origin: OwnerModelOrigin) {
    constructor(intent: OwnerModelIntent, model: VerifiedProjectModel) : this(intent, OwnerModelOrigin.Verified(model))
    val model: VerifiedProjectModel get() = origin.verified
    val observedModel: ObservedProjectModel? get() = origin.observed
    val validity = ProducedPlanValidity()
    val namespace = CompilerApplicationNamespace(origin.namespace.ownerId, origin.namespace.workspaceId, origin.namespace.worktreeId)
}

/** Same runtime/import/clock/provider capabilities; serialized labels cannot enroll this record. */
internal class OwnerProducedModel internal constructor(val imported: OwnerImportedModel,
    val preview: KotlinJvmProducedOutputPreview, val provider: KotlinJvmModelLiveCompilerProvider) {
    val validity: ProducedPlanValidity get() = imported.validity
    val deadline: OwnedApplicationDeadline get() = preview.originalDeadline
}

/** Actual detached compiler return, captured before coordinator normalization and before teardown. */
internal class OwnerProducedObservation internal constructor(val attempt: ProducedExecutionAttempt,
    val child: AnalysisInput, val result: ProviderResult, val successfulOutput: ModelSuccessfulJvmOutput?,
    val callElapsedMillis: Long)

/**
 * Explicit in-process owner composition for the unqualified model application. No public CLI/MCP
 * input constructs this object, a generation permit, or its custody hooks. The owner starts the
 * generation deadline before preparing its launch; each model intent has another ORIGINAL clock.
 *
 * One application per namespace, bounded reservations and retained tombstones. Nothing evicts an
 * old reservation to make its identity reusable. Locks protect state only: compiler/native open,
 * retirement, model/archive validation and owner hooks run outside them. Broker restart retains
 * kernel obligations, NOT these live capabilities. Restart recovery needs new explicit authority.
 * Individual ACK/cancel/Workbench.close never retires a generation shared with another review.
 */
internal class OwnerLiveModelRuntime(
    val ownerId: String,
    val installation: KotlinJvmToolchain,
    /** Fixed actual selected worker archive digest; changing a generation may not change its code. */
    val workerBridgeDigest: String,
    val reviewTimeoutMillis: Long = 120_000,
    private val maximumGenerations: Int = 32,
    private val maximumActiveAttempts: Int = 16,
    private val maximumRetainedAttempts: Int = 1024,
) {
    val owner = OwnedApplicationOwner()
    private val lock = Any()

    private class Generation(
        val request: ModelCompilerGenerationRequest,
        val permit: ModelCompilerGenerationPermit,
        val custody: OwnerModelMaterializationCustody,
        val cancellation: CancellationToken,
    ) {
        var state = "opening"
        var opened: ModelCompilerApplicationOpenResult? = null
        var retirement: OwnedApplicationRetirement? = null
        var retirementRequested = false
        var reason: String? = null
    }

    private class Attempt(
        val effective: AnalysisInput,
        val child: AnalysisInput,
        val generation: Generation,
        val deadline: OwnedApplicationDeadline,
        val selectedProvider: KotlinJvmModelLiveCompilerProvider,
        val produced: OwnerProducedModel? = null,
        val declaredAttempt: ProducedExecutionAttempt? = null,
    ) {
        var state = "preparing"
        var permit: ModelCompilerAttemptPermit? = null
        val registered = AtomicReference<ModelCompilerJobMaterialization?>()
        var invoked = false
        var returned: ProviderResult? = null
        var callElapsedMillis: Long? = null
        var successfulOutput: ModelSuccessfulJvmOutput? = null
    }

    private val generations = linkedMapOf<String, Generation>()
    private val namespaces = mutableMapOf<String, Generation>()
    // Output reservations remain sticky even after failed preparation or exact-attempt cleanup.
    private val outputs = mutableMapOf<String, Attempt>()
    private val routeMarkers = mutableMapOf<String, AnalysisInput>()
    private val children = mutableMapOf<String, Attempt>()
    private val imports = mutableMapOf<String, OwnerImportedModel>()
    private val importedIntents = mutableSetOf<OwnerModelIntent>()
    private val observedInvocations = mutableSetOf<ObservedExportInvocation>()
    private val producedModels = mutableMapOf<OwnerProducedModel, Generation>()
    private var importReservations = 0

    private val authority: KotlinJvmModelApplicationAuthority = object : KotlinJvmModelApplicationAuthority {
        override fun authorizeGeneration(request: ModelCompilerGenerationRequest): ModelCompilerGenerationPermit? =
            synchronized(lock) {
                generations[request.launch.identity().generation()]?.takeIf {
                    it.request === request && it.state == "opening" && !it.retirementRequested
                }?.permit
            }

        override fun authorizeAttempt(provider: KotlinJvmModelLiveCompilerProvider,
            request: ModelCompilerAttemptRequest): ModelCompilerAttemptPermit? {
            val retained = synchronized(lock) {
                children[request.analysisKey]?.takeIf {
                    it.state == "ready" && it.selectedProvider === provider && it.child == request.input &&
                        isCurrentLocked(it) && it.generation.state == "ready" && !it.generation.retirementRequested
                }?.permit
            } ?: return null
            // Provider admission checks this again. Never call even a library gate under our lock.
            return retained.takeIf { it.accepts(request.input) }
        }
    }

    val provider: KotlinJvmModelLiveCompilerProvider = KotlinJvmModelLiveCompilerProvider(installation, authority)
    /** Fixed route identity. An exact live preparation selects its provider; no cold fallback. */
    val routingProvider: AnalysisProvider = object : AnalysisProvider {
        override val id: String get() = provider.id
        override val version: String get() = provider.version
        override val supportedRuleIds: Set<String> get() = provider.supportedRuleIds
        override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
            val selected = synchronized(lock) {
                val value = children[input.analysisKey()] ?: problem("model_live_attempt_unavailable")
                requireCurrentLocked(value)
                if (value.child != input || value.state != "ready" || value.invoked) problem("model_live_attempt_replayed")
                value.invoked = true
                value
            }
            val started = System.nanoTime()
            val actual = selected.selectedProvider.analyze(input, cancellation)
            val detached = ContractJson.decodeFromString<ProviderResult>(Digests.canonical(ContractJson.encodeToJsonElement(actual)))
            val elapsed = (System.nanoTime() - started) / 1_000_000
            synchronized(lock) {
                requireCurrentLocked(selected)
                if (selected.returned != null || elapsed < 0) problem("model_live_return_identity_invalid")
                selected.returned = detached
                selected.callElapsedMillis = elapsed
            }
            return detached
        }
    }
    val implementationIdentity: String = Digests.sha256(Digests.canonical(
        JsonArray(listOf("ktchecker/owner-live-model-runtime/2", provider.id,
            provider.version, installation.digest, workerBridgeDigest).map(::JsonPrimitive))))

    init {
        require(ownerId.isNotBlank() && ownerId.length <= 256 && ownerId.none { it in "\u0000\r\n" })
        require(Digests.valid(workerBridgeDigest))
        require(reviewTimeoutMillis in 1..3_600_000)
        require(maximumGenerations in 1..128 && maximumActiveAttempts in 1..64 &&
            maximumRetainedAttempts in maximumActiveAttempts..16_384)
    }

    /** Exact duplicates return the original state; they never relaunch or replace callbacks/clocks. */
    fun open(request: ModelCompilerGenerationRequest, permit: ModelCompilerGenerationPermit,
        custody: OwnerModelMaterializationCustody,
        cancellation: CancellationToken = CancellationToken.NONE): OwnerLiveGenerationStatus {
        requireOwner(request)
        if (permit.request !== request) problem("model_live_generation_permit_mismatch")
        val generationId = request.launch.identity().generation()
        val entry = synchronized(lock) {
            generations[generationId]?.let {
                if (it.request !== request || it.permit !== permit || it.custody !== custody || it.cancellation !== cancellation) {
                    problem("model_live_generation_conflict")
                }
                return view(it)
            }
            if (generations.size >= maximumGenerations) problem("model_live_generation_capacity")
            namespaces[request.namespace.digest]?.let {
                if (it.retirement?.disposition != OwnedApplicationCleanupDisposition.CLOSED) {
                    problem("model_live_namespace_occupied")
                }
            }
            checkpoint(request.originalDeadline)
            Generation(request, permit, custody, cancellation).also {
                generations[generationId] = it
                namespaces[request.namespace.digest] = it
            }
        }
        // Retain failures even if an unexpected exception escapes the lower public boundary.
        val opened = try { KotlinJvmModelCompilerApplication.open(request, authority, cancellation) }
        catch (failure: Throwable) {
            synchronized(lock) { entry.state = "unavailable"; entry.reason = "model_live_generation_open_threw" }
            throw failure
        }
        val retireAfterOpen = synchronized(lock) {
            check(generations[generationId] === entry)
            entry.opened = opened
            when (opened) {
                is ModelCompilerApplicationOpenResult.Unavailable -> {
                    entry.state = "unavailable"
                    entry.retirement = opened.cleanup
                    entry.reason = "model_live_generation_unavailable"
                    false
                }
                is ModelCompilerApplicationOpenResult.Ready -> {
                    if (entry.retirementRequested || request.originalDeadline.isExpired() || Thread.currentThread().isInterrupted) {
                        entry.retirementRequested = true
                        entry.state = "retiring"
                        entry.reason = "model_live_generation_publication_rejected"
                        true
                    } else { entry.state = "ready"; false }
                }
            }
        }
        if (retireAfterOpen) retireApplication(entry, (opened as ModelCompilerApplicationOpenResult.Ready).application)
        return synchronized(lock) { view(entry) }
    }

    fun status(request: ModelCompilerGenerationRequest): OwnerLiveGenerationStatus = synchronized(lock) {
        view(exactGeneration(request))
    }

    /** Only an explicit external owner operation. Opening/retiring duplicates retain obligations. */
    fun retire(request: ModelCompilerGenerationRequest): OwnerLiveGenerationStatus {
        val selected = synchronized(lock) {
            val entry = exactGeneration(request)
            // Sticky state-only fence precedes every external retirement callback.
            if (namespaces[request.namespace.digest] === entry) imports[request.namespace.digest]?.validity?.invalidate()
            producedModels.filterValues { it === entry }.keys.forEach { it.validity.invalidate() }
            entry.retirementRequested = true
            when (entry.state) {
                "ready" -> {
                    entry.state = "retiring"
                    entry to (entry.opened as ModelCompilerApplicationOpenResult.Ready).application
                }
                else -> return view(entry)
            }
        }
        retireApplication(selected.first, selected.second)
        return synchronized(lock) { view(selected.first) }
    }

    private fun retireApplication(entry: Generation, application: KotlinJvmModelCompilerApplication) {
        val retired = try { application.retire() }
        catch (failure: Throwable) {
            synchronized(lock) { entry.state = "retirement_unverified"; entry.reason = "model_live_retirement_threw" }
            throw failure
        }
        synchronized(lock) {
            entry.retirement = retired
            entry.state = if (retired.disposition == OwnedApplicationCleanupDisposition.CLOSED) "retired" else "retirement_unverified"
            if (entry.state != "retired") entry.reason = "model_live_retirement_unverified"
        }
    }

    /** Called at the real OwnerModelPlans.prepare boundary, not at late preview enrollment. */
    fun reserveImport(intent: OwnerModelIntent, model: VerifiedProjectModel): OwnerImportedModel =
        reserveImportChecked(intent, OwnerModelOrigin.Verified(model))

    fun reserveObservedImport(intent: OwnerModelIntent, model: ObservedProjectModel): OwnerImportedModel =
        reserveImportChecked(intent, OwnerModelOrigin.Observed(model))

    private fun reserveImportChecked(intent: OwnerModelIntent, origin: OwnerModelOrigin): OwnerImportedModel {
        val binding = intent.binding
        if (binding.ownerId != ownerId || origin.namespace != ModelNamespace(binding.ownerId, binding.workspaceId,
                binding.worktreeId, binding.lineageId)) problem("model_live_import_namespace_mismatch")
        val deadline = intent.originalDeadline ?: problem("model_live_original_deadline_missing")
        origin.requireOriginalClock(deadline)
        checkpoint(deadline)
        return synchronized(lock) {
            if (!intent.used.get() || intent in importedIntents || origin.observed?.invocation?.let { it in observedInvocations } == true) {
                problem("model_live_import_replayed")
            }
            val namespace = CompilerApplicationNamespace(binding.ownerId, binding.workspaceId, binding.worktreeId)
            // A genuinely newer preparation cannot leave old output current merely because
            // its own bounded enrollment fails. Replayed/foreign intents were rejected above.
            imports[namespace.digest]?.validity?.invalidate()
            if (importReservations >= maximumRetainedAttempts) problem("model_live_import_capacity")
            importedIntents.add(intent)
            origin.observed?.invocation?.let { observedInvocations.add(it) }
            val value = OwnerImportedModel(intent, origin)
            imports[value.namespace.digest] = value
            importReservations++
            value
        }
    }

    /** No binding/source IO here: both exact D archives must exist before producer binding. */
    fun prepareProduced(imported: OwnerImportedModel): OwnerProducedModel {
        val deadline = imported.intent.originalDeadline ?: problem("model_live_original_deadline_missing")
        val generation = synchronized(lock) {
            requireImportLocked(imported)
            val selected = namespaces[imported.namespace.digest] ?: problem("model_live_generation_unavailable")
            if (selected.state != "ready" || selected.retirementRequested ||
                selected.request.generation.profile() != CompilerModelWire.PRODUCED_PROFILE ||
                producedModels.keys.any { it.imported === imported }) problem("model_live_produced_generation_unavailable")
            imported.origin.requireOriginalClock(selected.request.originalDeadline)
            requireClockContained(selected.request.originalDeadline, deadline)
            selected
        }
        val preview = when (val origin = imported.origin) {
            is OwnerModelOrigin.Verified -> KotlinJvmProducedOutputPreview.create(origin.value, installation, deadline)
            is OwnerModelOrigin.Observed -> ObservedJvmProducedOutputPreview.create(origin.value, installation, deadline)
        }
        val value = OwnerProducedModel(imported, preview, KotlinJvmModelLiveCompilerProvider(installation, authority, preview))
        synchronized(lock) {
            requireImportLocked(imported)
            if (namespaces[imported.namespace.digest] !== generation || generation.state != "ready" || generation.retirementRequested ||
                producedModels.keys.any { it.imported === imported }) problem("model_live_import_superseded")
            producedModels[value] = generation
        }
        verifyProduced(value)
        return value
    }

    fun verifyProduced(value: OwnerProducedModel) = synchronized(lock) { requireProducedLocked(value) }

    /** Only the trusted kernel ordinary whole-archive observer invokes this. */
    fun prepared(prepared: PreparedJvmLiveModelInput, original: AnalysisInput, effective: AnalysisInput,
        verifyFrozen: () -> Unit) = prepareAttempt(prepared, effective, provider, null, null) { application, child, register, verify ->
            prepared.bindAttempt(original, effective, child, application, verifyFrozen, register, verify)
        }

    /** Caller first validates the complete kernel admission/preparation/slot/work/claim tuple. */
    fun preparedDeclared(produced: OwnerProducedModel, compilation: JvmProducedPreviewCompilation, prepared: PreparedJvmLiveModelInput,
        declaration: JvmProducedDeclaredArchive, attempt: ProducedExecutionAttempt) {
        verifyProduced(produced)
        val archive = attempt.preparation.archive(attempt.execution.slotId)
        if (declaration.original != archive.original || declaration.frozen != archive.frozen ||
            declaration.archiveKey != archive.identity.archiveKey || prepared.original != attempt.original ||
            prepared.originalDeadline !== produced.deadline || !produced.imported.origin.matches(compilation.binding) ||
            compilation.binding.identity != prepared.bindingIdentity) problem("model_live_declared_attempt_mismatch")
        prepareAttempt(prepared, attempt.effective, produced.provider, produced, attempt,
            { custody -> custody.registerProduced(attempt, compilation, prepared) }) { application, child, register, verify ->
            prepared.bindDeclaredAttempt(declaration, attempt.admission, archive, attempt.workKey, attempt.original,
                attempt.effective, child, application, attempt::verifyFrozen, register, verify)
        }
    }

    private fun prepareAttempt(prepared: PreparedJvmLiveModelInput, effective: AnalysisInput,
        selectedProvider: KotlinJvmModelLiveCompilerProvider, produced: OwnerProducedModel?, declared: ProducedExecutionAttempt?,
        beforeBind: (OwnerModelMaterializationCustody) -> Unit = {},
        bind: (KotlinJvmModelCompilerApplication, AnalysisInput, (ModelCompilerJobMaterialization) -> Unit,
            (ModelCompilerJobMaterialization) -> Unit) -> ModelCompilerAttemptPermit) {
        val parent = freezeModelInput(effective)
        val child = parent.copy(requiredRuleIds = prepared.compilerRuleIds, execution = null)
        val output = parent.outputRoot ?: problem("model_live_attempt_output_missing")
        val namespace = CompilerApplicationNamespace(parent.snapshot.ownerId, parent.snapshot.workspaceId, parent.snapshot.worktreeId)
        val reserved = synchronized(lock) {
            if (routeMarkers.size >= maximumRetainedAttempts) problem("model_live_attempt_capacity")
            if (output in routeMarkers) problem("model_live_attempt_identity_conflict")
            routeMarkers[output] = parent // Remains sticky even when subsequent preparation fails.
            val generation = namespaces[namespace.digest] ?: problem("model_live_generation_unavailable")
            if (generation.state != "ready" || generation.retirementRequested) problem("model_live_generation_unavailable")
            produced?.let { requireProducedLocked(it) }
            if (produced != null && producedModels[produced] !== generation) problem("model_live_produced_generation_unavailable")
            checkpoint(prepared.originalDeadline)
            if (outputs.values.count { it.state in setOf("preparing", "ready") } >= maximumActiveAttempts) {
                problem("model_live_attempt_capacity")
            }
            if (output in outputs || child.analysisKey() in children) problem("model_live_attempt_identity_conflict")
            Attempt(parent, child, generation, prepared.originalDeadline, selectedProvider, produced, declared).also {
                outputs[output] = it
                children[child.analysisKey()] = it
            }
        }
        var permit: ModelCompilerAttemptPermit? = null
        try {
            requireCurrent(reserved)
            beforeBind(reserved.generation.custody)
            requireCurrent(reserved)
            val application = synchronized(lock) {
                (reserved.generation.opened as ModelCompilerApplicationOpenResult.Ready).application
            }
            val admitted = bind(application, child,
                { materialized ->
                    checkpoint(prepared.originalDeadline)
                    if (materialized.original != prepared.original || materialized.archived != parent || materialized.input != child ||
                        !reserved.registered.compareAndSet(null, materialized)) problem("model_live_materialization_mismatch")
                    requireCurrent(reserved)
                    reserved.generation.custody.register(materialized)
                    requireCurrent(reserved)
                    checkpoint(prepared.originalDeadline)
                },
                { materialized ->
                    checkpoint(prepared.originalDeadline)
                    if (reserved.registered.get() !== materialized) problem("model_live_materialization_mismatch")
                    requireCustody(reserved, materialized)
                    reserved.generation.custody.verify(materialized)
                    requireCustody(reserved, materialized)
                    checkpoint(prepared.originalDeadline)
                })
            permit = admitted
            synchronized(lock) {
                requireCurrentLocked(reserved)
                checkpoint(prepared.originalDeadline)
                if (reserved.state != "preparing" || admitted.provider !== selectedProvider) problem("model_live_attempt_finished_during_preparation")
                reserved.permit = admitted
                reserved.state = "ready"
            }
        } catch (failure: Throwable) {
            synchronized(lock) { if (reserved.state == "preparing") reserved.state = "unavailable" }
            permit?.close() // Exact ticket only; never retires a later/foreign active attempt.
            throw failure
        }
    }

    /** Frozen compiler observation after teardown, never promotion/publication authority by itself. */
    fun producedObservation(produced: OwnerProducedModel, attempt: ProducedExecutionAttempt): OwnerProducedObservation {
        return synchronized(lock) {
            requireProducedLocked(produced)
            val retained = outputs[attempt.effective.outputRoot] ?: problem("model_live_produced_return_unavailable")
            if (retained.produced !== produced || retained.declaredAttempt !== attempt || retained.state != "finished" ||
                retained.effective != attempt.effective || retained.registered.get() == null) problem("model_live_produced_return_unavailable")
            val actual = retained.returned ?: problem("model_live_produced_return_unavailable")
            val elapsed = retained.callElapsedMillis ?: problem("model_live_produced_return_unavailable")
            OwnerProducedObservation(attempt, retained.child, actual, retained.successfulOutput, elapsed)
        }
    }

    fun routes(input: AnalysisInput): Boolean = synchronized(lock) { input.outputRoot?.let(routeMarkers::containsKey) == true }

    fun child(input: AnalysisInput, rules: List<String>): AnalysisInput {
        val selected = synchronized(lock) {
            val retained = outputs[input.outputRoot] ?: problem("model_live_attempt_unavailable")
            requireCurrentLocked(retained)
            if (retained.state != "ready" || retained.effective != input || retained.child.requiredRuleIds != rules) {
                problem("model_live_attempt_projection_mismatch")
            }
            checkpoint(retained.deadline)
            retained
        }
        if (selected.permit?.accepts(selected.child) != true) problem("model_live_attempt_unavailable")
        return selected.child
    }

    /** Exact object tuple comparison; a stale finally block cannot remove or revoke another attempt. */
    fun finish(input: AnalysisInput) {
        val permit = synchronized(lock) {
            val retained = outputs[input.outputRoot] ?: return
            if (retained.effective != input || retained.state == "finished") return
            // A token is a candidate only. The kernel's clean DURABLE publication is still
            // mandatory before the bridge may reserve a consumer or materialize this output.
            retained.successfulOutput = retained.permit?.successfulProducedOutput()
            retained.state = "finished"
            if (children[retained.child.analysisKey()] === retained) children.remove(retained.child.analysisKey())
            retained.permit
        }
        permit?.close()
    }

    internal fun retainedAttempts(): Int = synchronized(lock) { routeMarkers.size }
    internal fun retainedGenerations(): Int = synchronized(lock) { generations.size }

    private fun requireImportLocked(imported: OwnerImportedModel) {
        if (imports[imported.namespace.digest] !== imported || !imported.validity.isCurrent()) problem("model_live_import_superseded")
        val deadline = requireNotNull(imported.intent.originalDeadline)
        imported.origin.requireOriginalClock(deadline)
        checkpoint(deadline)
    }

    private fun requireProducedLocked(value: OwnerProducedModel) {
        requireImportLocked(value.imported)
        val generation = producedModels[value] ?: problem("model_live_produced_not_registered")
        if (namespaces[value.imported.namespace.digest] !== generation || generation.state != "ready" || generation.retirementRequested ||
            !value.imported.origin.matches(value.preview) || value.preview.originalDeadline !== value.imported.intent.originalDeadline) {
            value.validity.invalidate()
            problem("model_live_produced_generation_unavailable")
        }
        value.imported.origin.requireOriginalClock(generation.request.originalDeadline)
        requireClockContained(generation.request.originalDeadline, value.deadline)
    }

    /** Generation expiry cannot race later than the kernel's review clock fence. */
    private fun requireClockContained(generation: OwnedApplicationDeadline, review: OwnedApplicationDeadline) {
        val now = System.nanoTime()
        fun remaining(deadline: OwnedApplicationDeadline): Long {
            val elapsed = now - deadline.startedAtNanos
            if (elapsed < 0) problem("model_live_original_deadline_invalid")
            return deadline.durationMillis * 1_000_000 - elapsed
        }
        val required = remaining(review)
        if (required <= 0 || remaining(generation) < required) problem("model_live_generation_budget_insufficient")
    }

    private fun isCurrentLocked(attempt: Attempt): Boolean =
        outputs[attempt.effective.outputRoot] === attempt && children[attempt.child.analysisKey()] === attempt &&
            attempt.state in setOf("preparing", "ready") && attempt.generation.state == "ready" && !attempt.generation.retirementRequested &&
            (attempt.produced?.let { imports[it.imported.namespace.digest] === it.imported && it.validity.isCurrent() } != false)

    private fun requireCurrent(attempt: Attempt) = synchronized(lock) { requireCurrentLocked(attempt) }
    private fun requireCurrentLocked(attempt: Attempt) {
        if (!isCurrentLocked(attempt)) problem("model_live_attempt_unavailable")
        attempt.produced?.let { requireProducedLocked(it) }
    }

    /** Completed token custody is intentionally distinct from active-child/ticket authority.
     * The only post-finish caller is the retained compiler token's whole-K custody path. */
    private fun requireCustody(attempt: Attempt, materialized: ModelCompilerJobMaterialization) = synchronized(lock) {
        if (attempt.state != "finished") requireCurrentLocked(attempt) else {
            val produced = attempt.produced ?: problem("model_live_finished_custody_unavailable")
            requireProducedLocked(produced)
            val token = attempt.successfulOutput ?: problem("model_live_finished_custody_unavailable")
            if (outputs[attempt.effective.outputRoot] !== attempt || attempt.declaredAttempt == null ||
                children[attempt.child.analysisKey()] === attempt || attempt.registered.get() !== materialized ||
                attempt.permit?.successfulProducedOutput() !== token || attempt.returned == null ||
                materialized.archived != attempt.effective || materialized.input != attempt.child) {
                problem("model_live_finished_custody_unavailable")
            }
        }
    }

    private fun requireOwner(request: ModelCompilerGenerationRequest) {
        if (request.owner !== owner || request.installation !== installation || request.namespace.ownerId != ownerId ||
            request.launch.identity().bridgeDigest() != workerBridgeDigest) problem("model_live_generation_owner_mismatch")
    }
    private fun exactGeneration(request: ModelCompilerGenerationRequest): Generation =
        generations[request.launch.identity().generation()]?.takeIf { it.request === request }
            ?: problem("model_live_generation_not_registered")
    private fun view(value: Generation) = OwnerLiveGenerationStatus(value.request.launch.identity().generation(),
        value.request.namespace.digest, value.state, value.opened, value.retirement, value.reason)

    private fun checkpoint(deadline: OwnedApplicationDeadline) {
        if (deadline.isExpired()) problem("model_live_original_deadline_expired")
        if (Thread.currentThread().isInterrupted) problem("model_live_interrupted")
    }
    private fun problem(code: String): Nothing = throw CliProblem(code, "The exact live owner-model capability is unavailable; no ordinary compiler fallback is authorized.")
}

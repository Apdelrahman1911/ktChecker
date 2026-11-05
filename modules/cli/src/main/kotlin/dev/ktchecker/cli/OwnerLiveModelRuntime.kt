package dev.ktchecker.cli

import dev.ktchecker.compiler.KotlinJvmToolchain
import dev.ktchecker.compiler.live.CompilerApplicationNamespace
import dev.ktchecker.compiler.live.model.*
import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** Mandatory trusted-owner custody hooks, not callbacks reconstructed from input/profile JSON. */
internal interface OwnerModelMaterializationCustody {
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
    ) {
        var state = "preparing"
        var permit: ModelCompilerAttemptPermit? = null
    }

    private val generations = linkedMapOf<String, Generation>()
    private val namespaces = mutableMapOf<String, Generation>()
    // Output reservations remain sticky even after failed preparation or exact-attempt cleanup.
    private val outputs = mutableMapOf<String, Attempt>()
    private val routeMarkers = mutableMapOf<String, AnalysisInput>()
    private val children = mutableMapOf<String, Attempt>()

    private val authority: KotlinJvmModelApplicationAuthority = object : KotlinJvmModelApplicationAuthority {
        override fun authorizeGeneration(request: ModelCompilerGenerationRequest): ModelCompilerGenerationPermit? =
            synchronized(lock) {
                generations[request.launch.identity().generation()]?.takeIf {
                    it.request === request && it.state == "opening" && !it.retirementRequested
                }?.permit
            }

        override fun authorizeAttempt(provider: KotlinJvmModelLiveCompilerProvider,
            request: ModelCompilerAttemptRequest): ModelCompilerAttemptPermit? {
            if (provider !== this@OwnerLiveModelRuntime.provider) return null
            val retained = synchronized(lock) {
                children[request.analysisKey]?.takeIf {
                    it.state == "ready" && it.child == request.input && it.generation.state == "ready" &&
                        !it.generation.retirementRequested
                }?.permit
            } ?: return null
            // Provider admission checks this again. Never call even a library gate under our lock.
            return retained.takeIf { it.accepts(request.input) }
        }
    }

    val provider: KotlinJvmModelLiveCompilerProvider = KotlinJvmModelLiveCompilerProvider(installation, authority)
    val implementationIdentity: String = Digests.sha256(Digests.canonical(
        JsonArray(listOf("ktchecker/owner-live-model-runtime/1", provider.id,
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

    /** Only the trusted kernel whole-archive observer invokes this with a live imported preparation. */
    fun prepared(prepared: PreparedJvmLiveModelInput, original: AnalysisInput, effective: AnalysisInput,
        verifyFrozen: () -> Unit) {
        val parent = freezeModelInput(effective)
        val child = parent.copy(requiredRuleIds = prepared.compilerRuleIds, execution = null)
        val output = parent.outputRoot ?: problem("model_live_attempt_output_missing")
        val namespace = CompilerApplicationNamespace(parent.snapshot.ownerId, parent.snapshot.workspaceId, parent.snapshot.worktreeId)
        val reserved = synchronized(lock) {
            // The observer selected this live route even if its application/grant is unavailable.
            // Keep that decision sticky; an authorization failure must not enable a cold fallback.
            if (routeMarkers.size >= maximumRetainedAttempts) problem("model_live_attempt_capacity")
            if (output in routeMarkers) problem("model_live_attempt_identity_conflict")
            routeMarkers[output] = parent
            val generation = namespaces[namespace.digest] ?: problem("model_live_generation_unavailable")
            if (generation.state != "ready" || generation.retirementRequested) problem("model_live_generation_unavailable")
            checkpoint(prepared.originalDeadline)
            if (outputs.values.count { it.state in setOf("preparing", "ready") } >= maximumActiveAttempts) {
                problem("model_live_attempt_capacity")
            }
            if (output in outputs || child.analysisKey() in children) problem("model_live_attempt_identity_conflict")
            Attempt(parent, child, generation, prepared.originalDeadline).also {
                outputs[output] = it
                children[child.analysisKey()] = it
            }
        }
        val registered = AtomicReference<ModelCompilerJobMaterialization?>()
        var permit: ModelCompilerAttemptPermit? = null
        try {
            val application = synchronized(lock) {
                (reserved.generation.opened as ModelCompilerApplicationOpenResult.Ready).application
            }
            permit = prepared.bindAttempt(original, parent, child, application, verifyFrozen,
                registerMaterialization = { materialized ->
                    checkpoint(prepared.originalDeadline)
                    if (materialized.original != prepared.original || materialized.archived != parent || materialized.input != child ||
                        !registered.compareAndSet(null, materialized)) problem("model_live_materialization_mismatch")
                    requireCurrent(reserved)
                    reserved.generation.custody.register(materialized)
                    requireCurrent(reserved)
                    checkpoint(prepared.originalDeadline)
                },
                verifyMaterialization = { materialized ->
                    checkpoint(prepared.originalDeadline)
                    if (registered.get() !== materialized) problem("model_live_materialization_mismatch")
                    requireCurrent(reserved)
                    reserved.generation.custody.verify(materialized)
                    requireCurrent(reserved)
                    checkpoint(prepared.originalDeadline)
                })
            synchronized(lock) {
                requireCurrentLocked(reserved)
                checkpoint(prepared.originalDeadline)
                if (reserved.state != "preparing") problem("model_live_attempt_finished_during_preparation")
                reserved.permit = permit
                reserved.state = "ready"
            }
        } catch (failure: Throwable) {
            synchronized(lock) { if (reserved.state == "preparing") reserved.state = "unavailable" }
            permit?.close() // Exact ticket only; it cannot cancel a later/foreign active attempt.
            throw failure
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
            retained.state = "finished"
            if (children[retained.child.analysisKey()] === retained) children.remove(retained.child.analysisKey())
            retained.permit
        }
        permit?.close()
    }

    internal fun retainedAttempts(): Int = synchronized(lock) { routeMarkers.size }
    internal fun retainedGenerations(): Int = synchronized(lock) { generations.size }

    private fun requireCurrent(attempt: Attempt) = synchronized(lock) { requireCurrentLocked(attempt) }
    private fun requireCurrentLocked(attempt: Attempt) {
        if (outputs[attempt.effective.outputRoot] !== attempt || children[attempt.child.analysisKey()] !== attempt ||
            attempt.state !in setOf("preparing", "ready") || attempt.generation.state != "ready" || attempt.generation.retirementRequested) {
            problem("model_live_attempt_unavailable")
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

package dev.ktchecker.compiler

import dev.ktchecker.compilerworker.CompilerWarmWorkerProtocol as Wire
import dev.ktchecker.contracts.Digests
import dev.ktchecker.providers.runtime.OwnedSupervisorIdentity
import dev.ktchecker.providers.runtime.ProcessOutcome
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*

/** Actual component bytes, ordered closure, execution template and owner domain; never a version-only key. */
internal class WarmImplementationKey(
    hostComponents: String,
    bridge: String,
    compilerClosure: String,
    jdkImage: String,
    javaExecutable: String,
    supervisor: String,
    launchTemplate: String,
    val ownerDomain: String,
    val os: String,
    val architecture: String,
    val mode: String = "private-fixed-synthetic-jvm",
) {
    private val bytes = listOf(hostComponents, bridge, compilerClosure, jdkImage, javaExecutable, supervisor, launchTemplate)
    init {
        require(bytes.all(Digests::valid))
        require(ownerDomain.matches(Regex("[a-f0-9]{32}")))
        require(os.isNotBlank() && architecture.isNotBlank() && mode.isNotBlank())
    }
    val digest: String = Digests.sha256(Digests.canonical(warmJson(mapOf(
        "contract" to Wire.VERSION, "api" to Wire.API, "upstream_version" to "2.4.21",
        "component_digests_in_declared_order" to bytes, "owner_domain" to ownerDomain,
        "os" to os, "architecture" to architecture, "mode" to mode,
    ))))
}

/** Live in-process owner capability. Equal decoded descriptors cannot replace a registered object. */
internal class WarmJobPermit internal constructor(
    internal val owner: Any,
    val job: Wire.Job,
    val generation: String,
    val implementationKey: String,
    val deadlineNanos: Long,
    private val revoked: (WarmJobPermit) -> Unit,
) {
    private val live = AtomicBoolean(true)
    internal var consumed = false
        private set
    internal fun isLive(): Boolean = live.get()
    internal fun consume() { check(!consumed && live.get()); consumed = true }
    internal fun invalidate() { live.set(false) }
    fun revoke() = revoked(this)
}

internal enum class WarmGenerationState { PREPARED, AUTHORIZED, STARTING, DRAINING, QUIESCENT, POISONED, CLEANUP_PENDING }

/** A finite generation is one supervision unit. No per-client job cancellation or per-job FINAL exists. */
internal class CompilerWarmWorkerContract(
    val plan: Wire.Plan,
    jobDeadlines: List<Long>,
    private val clock: () -> Long = System::nanoTime,
) {
    private val owner = Any()
    private val deadlines = jobDeadlines.toList()
    private val registered = mutableListOf<WarmJobPermit>()
    private val failures = linkedSetOf<String>()
    val deadlineNanos: Long
    val planDigest: String = Wire.sha256(Wire.encodePlan(plan))
    var state = WarmGenerationState.PREPARED
        private set
    var jobAdmissionCount = 0
        private set
    var launchGrantCount = 0
        private set
    var generationQuiescenceVerified = false
        private set
    var observationsComplete = false
        private set
    private var finished = false
    private var outputRootKey: String? = null
    private var jobRootKeys = emptyList<String>()

    init {
        Wire.validatePlan(plan)
        require(deadlines.size == Wire.JOB_COUNT)
        deadlineNanos = deadlines.min()
        require(deadlineNanos > clock()) { "Generation preparation already exhausted the earliest owner deadline" }
    }

    @Synchronized fun grantJobs(): List<WarmJobPermit> {
        check(state == WarmGenerationState.PREPARED && registered.isEmpty()) { "No replayed or replacement generation" }
        require(clock() < deadlineNanos)
        plan.jobs().forEachIndexed { index, job ->
            registered += WarmJobPermit(owner, job, plan.generation(), plan.implementationKey(), deadlines[index], ::revokePermit)
        }
        return Collections.unmodifiableList(registered.toList())
    }

    @Synchronized private fun revokePermit(permit: WarmJobPermit) {
        check(registered.any { it === permit })
        permit.invalidate()
        poison("job_revoked")
    }

    /** Called only when the trusted host constructs this generation's distinct exact ExecutionPermit. */
    @Synchronized fun recordLaunchGrant() {
        verifyLive()
        check(launchGrantCount == 0 && state == WarmGenerationState.PREPARED) { "No extra prewarm or replacement launch" }
        launchGrantCount = 1
    }

    /** All four capabilities are consumed together after the owner's one fresh output callback registered actual keys. */
    @Synchronized fun admit(permits: List<WarmJobPermit>, rootKey: String, jobKeys: List<String>) {
        verifyLive()
        check(state == WarmGenerationState.PREPARED && launchGrantCount == 1 && jobAdmissionCount == 0)
        require(rootKey.isNotBlank() && jobKeys.size == Wire.JOB_COUNT && jobKeys.all { it.isNotBlank() }
            && jobKeys.toSet().size == Wire.JOB_COUNT && rootKey !in jobKeys)
        require(permits.size == Wire.JOB_COUNT && permits.indices.all { index ->
            val permit = permits[index]
            permit === registered[index] && permit.owner === owner && permit.job == plan.jobs()[index] &&
                permit.generation == plan.generation() && permit.implementationKey == plan.implementationKey() &&
                permit.deadlineNanos == deadlines[index] && permit.deadlineNanos >= deadlineNanos &&
                permit.isLive() && !permit.consumed
        }) { "Absent, foreign, replayed or changed warm-job capability" }
        permits.forEach(WarmJobPermit::consume)
        jobAdmissionCount = Wire.JOB_COUNT
        outputRootKey = rootKey
        jobRootKeys = jobKeys.toList()
        state = WarmGenerationState.AUTHORIZED
    }

    @Synchronized fun starting() {
        verifyLive()
        check(state == WarmGenerationState.AUTHORIZED && jobAdmissionCount == Wire.JOB_COUNT && launchGrantCount == 1)
        state = WarmGenerationState.STARTING // Dispatch intent, not an observation of exec/worker readiness.
    }

    @Synchronized fun verifyLive() {
        check(!finished && state !in setOf(WarmGenerationState.POISONED, WarmGenerationState.CLEANUP_PENDING)) { "Generation cannot be resurrected" }
        check(registered.size == Wire.JOB_COUNT && registered.all { it.isLive() }) { "All four live owner capabilities are required" }
        if (clock() >= deadlineNanos) { poison("earliest_deadline_elapsed"); error("Earliest warm-job deadline elapsed") }
        check(Wire.sha256(Wire.encodePlan(plan)) == planDigest) { "Frozen generation descriptor changed" }
    }

    @Synchronized fun cancelled(): Boolean {
        if (clock() >= deadlineNanos) poison("earliest_deadline_elapsed")
        if (registered.any { !it.isLive() }) poison("job_revoked")
        return state in setOf(WarmGenerationState.POISONED, WarmGenerationState.CLEANUP_PENDING)
    }

    /** Relative native lifetime plus the independent absolute owner cancellation token; no nanosecond dispatch claim. */
    @Synchronized fun remainingMillis(): Long {
        verifyLive()
        val remaining = (deadlineNanos - clock()) / 1_000_000
        if (remaining <= 0) { poison("no_whole_millisecond_remaining"); error("No native lifetime remains") }
        return remaining
    }

    @Synchronized fun poison(reason: String) {
        failures += reason
        observationsComplete = false
        if (state != WarmGenerationState.CLEANUP_PENDING) state = WarmGenerationState.POISONED
    }

    /** These are post-terminal observations from the actual runner, never worker-reported process ownership. */
    @Synchronized fun finish(actual: ProcessOutcome?, expectedSupervisor: OwnedSupervisorIdentity,
        receipts: List<Wire.Receipt>, ownerInputsVerified: Boolean, expectedOutcomes: Boolean) {
        check(!finished) { "Terminal joins cannot be replayed" }
        cancelled()
        val live = state != WarmGenerationState.POISONED && registered.all { it.isLive() } && clock() < deadlineNanos
        state = if (live) WarmGenerationState.DRAINING else WarmGenerationState.POISONED
        generationQuiescenceVerified = actual?.successfulExecution == true && actual.supervisorIdentity == expectedSupervisor &&
            "owned_process_group_shutdown_unverified" !in actual.reasons
        val matched = runCatching {
            check(receipts.size == Wire.JOB_COUNT && jobAdmissionCount == Wire.JOB_COUNT && launchGrantCount == 1)
            check(receipts.map { it.loaderToken() }.distinct().size == 1)
            var previousDisposed = -1L
            var stopped = false
            receipts.forEachIndexed { index, receipt ->
                Wire.matchReceipt(plan, planDigest, plan.jobs()[index], receipt, checkNotNull(outputRootKey), jobRootKeys[index])
                check(!stopped || receipt.state() == "not_run")
                if (receipt.state() != "not_run") {
                    check(receipt.startedNanos() >= previousDisposed)
                    previousDisposed = receipt.disposedNanos()
                }
                if (receipt.state() != "sealed") stopped = true
            }
            receipts.all { it.state() == "sealed" }
        }.getOrDefault(false)
        observationsComplete = live && generationQuiescenceVerified && actual?.exitCode == 0 && actual.stdout.isEmpty() &&
            actual.stderr.isEmpty() && !actual.cancelled && !actual.timedOut && !actual.truncated &&
            ownerInputsVerified && expectedOutcomes && matched
        if (!observationsComplete) failures += "generation_observations_incomplete"
        state = when {
            observationsComplete -> WarmGenerationState.QUIESCENT
            !generationQuiescenceVerified -> WarmGenerationState.CLEANUP_PENDING
            else -> WarmGenerationState.POISONED
        }
        finished = true
    }

    @Synchronized fun description(): Map<String, Any?> = mapOf(
        "contract" to Wire.VERSION, "api" to Wire.API, "generation" to plan.generation(), "plan_digest" to planDigest,
        "implementation_key" to plan.implementationKey(), "owner_domain" to plan.ownerDomain(),
        "state" to state.name.lowercase(), "job_admissions" to jobAdmissionCount, "generation_launch_grants" to launchGrantCount,
        "generation_quiescence_verified" to generationQuiescenceVerified, "observations_complete" to observationsComplete,
        "owner_deadlines_nanos" to deadlines, "earliest_owner_deadline_nanos" to deadlineNanos,
        "deadline_scope" to "whole_generation_including_preparation; relative_native_millis_plus_absolute_owner_postgates",
        "failures" to failures.toList(), "per_job_process_terminal" to false, "independent_job_cancellation" to false,
        "ordinary_fixture_run_complete" to false, "qualification" to "none", "replacement_generation_count" to 0,
    )
}

internal fun warmJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key.toString() to warmJson(item) })
    is Iterable<*> -> JsonArray(value.map(::warmJson))
    else -> error("Unsupported private warm-receipt value")
}

internal fun warmJsonBytes(value: Any?): ByteArray = (Json { prettyPrint = true }.encodeToString(
    JsonElement.serializer(), warmJson(value)) + "\n").toByteArray(Charsets.UTF_8)

package dev.ktchecker.cli

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import dev.ktchecker.kernel.ClientBinding
import dev.ktchecker.kernel.OwnerFullCatalog1665
import dev.ktchecker.kernel.RequiredPlanSlot
import dev.ktchecker.kernel.RequiredReviewPlan
import dev.ktchecker.projectmodel.CONFIGURATION_INPUT_CLOSURE_UNPROVEN
import dev.ktchecker.projectmodel.ObservedExportInvocation
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import dev.ktchecker.snapshot.*
import dev.ktchecker.workerprotocol.CompilerModelWire as ModelWire
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.text.Normalizer
import java.util.Base64
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

internal const val OWNER_MODEL_LINEAGE_PROFILE = "same-workbench-lineage-fresh-observed-export-per-patch/1"
internal const val OWNER_MODEL_REQUEST_BYTES = 8_388_608
internal const val OWNER_MODEL_MAX_PATCHES = 32 // SAME retained-generation bound; no eviction/rearm.

/** DATA only. A trusted live adapter supplies the SAME registration, never a decoded capability. */
@Serializable internal data class OwnerModelPatchRoot(val id: String, val mountPath: String)
@Serializable internal data class OwnerModelPatchFile(
    val rootId: String, val path: String, val contentBase64: String, val executable: Boolean,
)
@Serializable internal data class OwnerModelCompletedPatch(
    val patchId: String,
    val taskId: String,
    val ordinal: Long,
    val binding: ClientBinding,
    val baseSnapshotId: String?,
    val expectedHeadRevision: Long,
    val roots: List<OwnerModelPatchRoot>,
    val files: List<OwnerModelPatchFile>,
)
@Serializable internal data class OwnerModelInvocationSelection(
    val gradleHome: String,
    val gradleCacheSeed: String,
    val compilerJavaHome: String,
    val compilerArtifactPaths: List<String>,
    val authorizeExecution: Boolean,
)
@Serializable internal data class OwnerModelLineageRegistration(
    val workspaceId: String, val worktreeId: String, val clientId: String,
    val sessionId: String, val lineageId: String,
)

/**
 * Trusted in-process views of the ALREADY RUNNING host phase clocks. No start time/remaining-time
 * scalar creates a phase clock here. The adapter must close over its original overall900s and
 * original review/observation300s clock objects. These are DENIAL fences, not export authority.
 * Neither this interface nor a clock scope is serializable or accepted by ordinary model RPC.
 */
internal interface OwnerModelPhaseFence {
    val originalMillis: Long
    fun checkpoint()
}
internal class OwnerModelClockScope(
    val overall: OwnerModelPhaseFence,
    val reviewObservation: OwnerModelPhaseFence,
    val originalExportMillis: Long = OWNER_MODEL_ORIGINAL_MILLIS,
    /** Optional original pre-spawn transport DENIAL fence, never a replacement native clock. */
    val originalExport: OwnerModelPhaseFence? = null,
) {
    // Read external getters once at construction, never under the state-only owner gate.
    val shapeValid: Boolean = originalExportMillis == OWNER_MODEL_ORIGINAL_MILLIS &&
        overall !== reviewObservation && overall.originalMillis == 900_000L && reviewObservation.originalMillis == 300_000L &&
        (originalExport == null || originalExport !== overall && originalExport !== reviewObservation &&
            originalExport.originalMillis == OWNER_MODEL_ORIGINAL_MILLIS)
    fun checkpoint() {
        ownerModelRequire(shapeValid, "owner_model_original_phase_scope_invalid")
        overall.checkpoint(); reviewObservation.checkpoint(); originalExport?.checkpoint()
    }
}

/** One immutable original patch clock/invocation, with sticky phase denial. Never a renewed timer. */
internal class OwnerModelCommandClock(
    val originalDeadline: OwnedApplicationDeadline,
    phases: OwnerModelClockScope? = null,
    originalOverall: OwnerModelPhaseFence? = phases?.overall,
    originalExport: OwnerModelPhaseFence? = phases?.originalExport,
) {
    val invocation = ObservedExportInvocation(originalDeadline)
    private val phase = AtomicReference(phases)
    private val overall = AtomicReference(originalOverall)
    private val export = AtomicReference(originalExport)
    private val denied = AtomicBoolean()
    init {
        ownerModelRequire(originalDeadline.durationMillis == OWNER_MODEL_ORIGINAL_MILLIS, "owner_model_original_clock_invalid")
        // Owner installation may start BEFORE the host admits its first completed patch/300s.
        // The already-running900s nevertheless covers that installation; it is never restarted.
        // A later submit constructs this clock before caller detachment. A supplied scope has
        // ALREADY cached/validated its external getters; do not call any getter again here.
        ownerModelRequire(originalOverall == null || if (phases != null)
            phases.shapeValid && phases.overall === originalOverall else originalOverall.originalMillis == 900_000L,
            "owner_model_original_overall_invalid")
        ownerModelRequire(originalExport == null || if (phases != null)
            phases.shapeValid && phases.originalExport === originalExport else originalExport.originalMillis == OWNER_MODEL_ORIGINAL_MILLIS,
            "owner_model_original_export_fence_invalid")
        ownerModelRequire(phases == null || phases.shapeValid && phases.overall === originalOverall && phases.originalExport === originalExport,
            "owner_model_original_phase_scope_invalid")
    }

    /** First owner construction may precede host capture; attaching a fence cannot renew its120s. */
    fun attachOriginalPhases(scope: OwnerModelClockScope) {
        try {
            ownerModelRequire(!denied.get() && scope.shapeValid, "owner_model_attempt_fenced")
            val original = overall.get()
            ownerModelRequire(original === scope.overall || original == null && overall.compareAndSet(null, scope.overall),
                "owner_model_original_overall_replaced")
            val oldExport = export.get()
            ownerModelRequire(oldExport === scope.originalExport || oldExport == null && scope.originalExport != null &&
                export.compareAndSet(null, scope.originalExport), "owner_model_original_export_fence_replaced")
            val old = phase.get()
            ownerModelRequire(old === scope || old == null && phase.compareAndSet(null, scope), "owner_model_phase_scope_replaced")
            checkpoint()
        } catch (failure: Throwable) { denied.set(true); throw failure }
    }
    fun checkpoint() {
        try {
            ownerModelRequire(!denied.get(), "owner_model_attempt_fenced")
            ownerModelCheckpoint(originalDeadline)
            val scope = phase.get()
            if (scope != null) scope.checkpoint() else { overall.get()?.checkpoint(); export.get()?.checkpoint() }
            invocation.verifyLive()
        } catch (failure: Throwable) { denied.set(true); throw failure }
    }
    fun cancelled(): Boolean {
        if (denied.get() || originalDeadline.isExpired() || Thread.currentThread().isInterrupted) return true
        return try { checkpoint(); false } catch (_: Throwable) { denied.set(true); true }
    }
    fun deny() { denied.set(true) }
}

/** State only. IO, kernel calls, export, retire and owner callbacks MUST run outside this gate. */
internal class OwnerModelLineageGate {
    private val lock = Any()
    private var active: Any? = null
    private var stopped = false
    private var count = 0
    private var policyAttempted = false
    private var overall: OwnerModelPhaseFence? = null
    private var phaseMode: Boolean? = null
    private val reviewPhases = Collections.newSetFromMap(IdentityHashMap<OwnerModelPhaseFence, Boolean>())
    private val exportPhases = Collections.newSetFromMap(IdentityHashMap<OwnerModelPhaseFence, Boolean>())
    private var exportFenceMode: Boolean? = null
    private val deadlines = Collections.newSetFromMap(IdentityHashMap<OwnedApplicationDeadline, Boolean>())
    private val patches = mutableSetOf<String>()

    /** Explicit owner/config is a separate one-shot operation, NEVER part of a review submit. */
    fun enterPolicy(): Any = synchronized(lock) {
        ownerModelRequire(!stopped && active == null && count == 0 && !policyAttempted,
            "owner_model_policy_not_available")
        policyAttempted = true
        Any().also { active = it }
    }

    /** Owner cleanup/close also reserves the state gate, eliminating a check-idle/submit race. */
    fun enterMaintenance(): Any = synchronized(lock) {
        ownerModelRequire(active == null, "owner_model_lineage_busy")
        Any().also { active = it }
    }

    /** Reserve BEFORE copying/capturing. Failed admitted entries remain tombstones, never retries. */
    fun enter(patchId: String, ordinal: Long, clock: OwnerModelCommandClock, scope: OwnerModelClockScope?): Any = synchronized(lock) {
        ownerModelRequire(!stopped && active == null, "owner_model_lineage_busy_or_stopped")
        ownerModelRequire(count < OWNER_MODEL_MAX_PATCHES && ordinal == count + 1L && patchId !in patches,
            "owner_model_patch_replayed_or_reordered")
        ownerModelRequire(clock.originalDeadline !in deadlines, "owner_model_original_clock_replayed")
        ownerModelRequire(phaseMode == null || phaseMode == (scope != null), "owner_model_phase_scope_changed")
        if (scope != null) {
            ownerModelRequire(scope.shapeValid && (overall == null || overall === scope.overall) &&
                scope.reviewObservation !in reviewPhases, "owner_model_original_phase_scope_replayed_or_changed")
            ownerModelRequire(exportFenceMode == null || exportFenceMode == (scope.originalExport != null),
                "owner_model_original_export_fence_mode_changed")
            scope.originalExport?.let {
                ownerModelRequire(it !in exportPhases, "owner_model_original_export_fence_replayed")
                exportPhases.add(it)
            }
            exportFenceMode = scope.originalExport != null
            overall = scope.overall; reviewPhases.add(scope.reviewObservation)
        }
        phaseMode = scope != null
        patches.add(patchId); deadlines.add(clock.originalDeadline); count++
        Any().also { active = it }
    }
    fun leave(token: Any, failed: Boolean) = synchronized(lock) {
        ownerModelRequire(active === token, "owner_model_lineage_operation_mismatch")
        if (failed) stopped = true
        active = null
    }
    fun stop() = synchronized(lock) { stopped = true }
    val idle: Boolean get() = synchronized(lock) { active == null }
    val enteredCount: Int get() = synchronized(lock) { count }
}

internal fun ownerModelStrictBytes(value: JsonElement): ByteArray = try {
    val buffer = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(Digests.canonical(value)))
    ByteArray(buffer.remaining()).also { buffer.get(it) }
} catch (_: Exception) { throw CliProblem("owner_model_request_invalid", "Expected exact valid Unicode input.") }

internal fun ownerModelIdentity(value: String) {
    ownerModelRequire(value.isNotBlank() && value.toByteArray(Charsets.UTF_8).size <= 256 &&
        value.none { it.isISOControl() }, "owner_model_request_identity_invalid")
}

/** Same portable subset as SnapshotStore, checked BEFORE attempting any source mutation. */
internal fun ownerModelLogicalPath(value: String) {
    val path = try { Path.of(value) } catch (_: Exception) {
        throw CliProblem("owner_model_snapshot_file_invalid", "Expected a bounded canonical logical path.")
    }
    val parts = value.split('/')
    ownerModelRequire(value.isNotEmpty() && !path.isAbsolute && path.normalize() == path && path.toString() == value &&
        value.toByteArray(Charsets.UTF_8).size <= ModelWire.MAX_LOGICAL_PATH_BYTES && parts.size <= ModelWire.MAX_PATH_DEPTH &&
        Normalizer.isNormalized(value, Normalizer.Form.NFC) && parts.all { part ->
            part.isNotEmpty() && part !in setOf(".", "..") && part.toByteArray(Charsets.UTF_8).size <= 255 &&
                !part.endsWith('.') && !part.endsWith(' ') && part.none { it.isISOControl() || it in "\\:*?\"<>|" } &&
                !part.matches(Regex("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?"))
        }, "owner_model_snapshot_file_invalid")
}

internal fun ownerModelPortableInventory(names: Set<String>) {
    val directories = ownerModelDirectories(names)
    ownerModelRequire(directories.none { it in names }, "owner_model_snapshot_path_collision")
    val all = names + directories
    ownerModelRequire(all.map { Normalizer.normalize(it.uppercase(Locale.ROOT).lowercase(Locale.ROOT), Normalizer.Form.NFC) }
        .distinct().size == all.size, "owner_model_snapshot_case_collision")
}

/** Full caller document is copied before any await/IO. No root/file filtering or mutable scan. */
internal class OwnerModelFrozenPatch private constructor(
    val request: OwnerModelCompletedPatch,
    private val originalBytes: ByteArray,
    val entries: List<SnapshotEntry>,
    private val contents: List<ImmutableBlob>,
) {
    val digest: String = Digests.sha256(originalBytes)
    fun write(path: Path, checkpoint: () -> Unit): OwnerModelFilePin =
        ownerModelWrite(path, originalBytes, OWNER_MODEL_REQUEST_BYTES, checkpoint)

    fun overlay(previous: StoredSnapshotManifest?): OverlayRequest {
        ownerModelRequire(previous == null || previous.roots == listOf(SnapshotRoot()), "owner_model_previous_roots_unsupported")
        val names = entries.map { SnapshotPath(it.rootId, it.path) }.toSet()
        // Explicit deletes derive ONLY from the exact registered immutable prior manifest.
        val removed = previous?.entries.orEmpty().filter { SnapshotPath(it.rootId, it.path) !in names }
            .map { SnapshotChange.Delete(SnapshotPath(it.rootId, it.path)) }
        val puts = entries.mapIndexed { index, entry ->
            SnapshotChange.PutFile(SnapshotPath(entry.rootId, entry.path), contents[index], entry.executable)
        }
        return OverlayRequest(SnapshotIdentity(request.binding.ownerId, request.binding.workspaceId, request.binding.worktreeId),
            request.binding.clientId, request.binding.sessionId, request.patchId, request.baseSnapshotId,
            request.expectedHeadRevision, removed + puts, CommitMode.STRICT_HEAD,
            SnapshotScope("complete explicitly submitted immutable owner files"))
    }

    companion object {
        fun capture(input: OwnerModelCompletedPatch, binding: ClientBinding, checkpoint: () -> Unit): OwnerModelFrozenPatch {
            val bytes = ownerModelStrictBytes(ContractJson.encodeToJsonElement(input))
            checkpoint() // Whole caller DATA is already detached before any external callback/IO.
            ownerModelRequire(bytes.size in 1..OWNER_MODEL_REQUEST_BYTES, "owner_model_request_limit")
            val decoded = ContractJson.decodeFromString<OwnerModelCompletedPatch>(strictJsonDocument(bytes, "owner_model_request_invalid"))
            ownerModelRequire(decoded.binding == binding && binding.generation >= 1, "owner_model_patch_binding_mismatch")
            listOf(binding.ownerId, binding.workspaceId, binding.worktreeId, binding.clientId, binding.sessionId,
                binding.lineageId, decoded.patchId, decoded.taskId).forEach(::ownerModelIdentity)
            ownerModelRequire(decoded.ordinal in 1..OWNER_MODEL_MAX_PATCHES.toLong() && decoded.expectedHeadRevision >= 0 &&
                (decoded.baseSnapshotId == null || Digests.valid(decoded.baseSnapshotId)), "owner_model_patch_lineage_invalid")
            // Reject a whole extra-root/mount vector. Never select just its supported project prefix.
            ownerModelRequire(decoded.roots == listOf(OwnerModelPatchRoot("project", "")), "owner_model_snapshot_roots_unsupported")
            ownerModelRequire(decoded.files.size in 1..ModelWire.MAX_SNAPSHOT_FILES &&
                decoded.files.map { it.path } == decoded.files.map { it.path }.distinct().sorted(),
                "owner_model_snapshot_limit_duplicate_or_order")
            var total = 0L
            val blobs = decoded.files.map { file ->
                checkpoint()
                ownerModelRequire(file.rootId == "project", "owner_model_snapshot_file_invalid")
                ownerModelLogicalPath(file.path)
                val content = try { Base64.getDecoder().decode(file.contentBase64) }
                catch (_: IllegalArgumentException) { throw CliProblem("owner_model_snapshot_file_invalid", "Invalid immutable file encoding.") }
                ownerModelRequire(Base64.getEncoder().encodeToString(content) == file.contentBase64 &&
                    content.size <= ModelWire.MAX_SOURCE_BYTES && content.size.toLong() <= ModelWire.MAX_SNAPSHOT_BYTES - total,
                    "owner_model_snapshot_encoding_or_limit")
                total += content.size
                ImmutableBlob.copyOf(content)
            }
            val names = decoded.files.map { it.path }.toSet()
            ownerModelPortableInventory(names)
            val request = decoded.copy(roots = Collections.unmodifiableList(decoded.roots.toList()),
                files = Collections.unmodifiableList(decoded.files.toList()))
            val entries = request.files.mapIndexed { index, file ->
                SnapshotEntry(file.rootId, file.path, digest = blobs[index].digest, size = blobs[index].size, executable = file.executable)
            }
            checkpoint()
            return OwnerModelFrozenPatch(request, bytes, Collections.unmodifiableList(entries), Collections.unmodifiableList(blobs))
        }
    }
}

/** Only actual SnapshotStore returns populate this holder. Unknown history is sticky quarantine. */
internal class OwnerModelSnapshotLineage(
    private val patch: OwnerModelFrozenPatch,
    val previous: SnapshotCommit?,
) {
    private val attempted = AtomicBoolean()
    private val uncertain = AtomicBoolean()
    private val verified = AtomicBoolean()
    private val returned = AtomicReference<SnapshotCommit?>()
    val commit: SnapshotCommit? get() = returned.get()
    val mutationAttempted: Boolean get() = attempted.get()
    val mutationVerified: Boolean get() = verified.get()
    val historyUncertain: Boolean get() = uncertain.get() || attempted.get() && !verified.get()

    fun capture(store: SnapshotStore, checkpoint: () -> Unit): SnapshotCommit {
        ownerModelRequire(!attempted.get() && !uncertain.get() && !verified.get(), "owner_model_snapshot_submission_replayed")
        checkpoint()
        verifyRequest(patch.request, previous)
        val identity = SnapshotIdentity(patch.request.binding.ownerId, patch.request.binding.workspaceId, patch.request.binding.worktreeId)
        // An unknown intervening commit is not a fresh empty lineage or caller-selected baseline.
        try {
            verifyHead(patch.request, store.currentHead(identity), previous)
            previous?.let { ownerModelRequire(store.snapshot(identity, it.snapshotId) == it.manifest,
                "owner_model_registered_before_changed") }
        } catch (failure: Throwable) { uncertain.set(true); throw failure }
        checkpoint()
        val request = patch.overlay(previous?.manifest)
        ownerModelRequire(attempted.compareAndSet(false, true), "owner_model_snapshot_submission_replayed")
        val actual = store.sealOverlay(request)
        returned.set(actual) // BEFORE verification; an actual returned commit is never discarded.
        verifyCommit(patch, previous, actual)
        ownerModelRequire(store.snapshot(identity, actual.snapshotId) == actual.manifest, "owner_model_registered_after_changed")
        verifyHead(patch.request, store.currentHead(identity), actual, after = true)
        verified.set(true)
        checkpoint()
        return actual
    }

    companion object {
        fun verifyRequest(request: OwnerModelCompletedPatch, previous: SnapshotCommit?) {
            ownerModelRequire(request.ordinal == (previous?.headRevision ?: 0L) + 1L &&
                request.expectedHeadRevision == (previous?.headRevision ?: 0L) &&
                request.baseSnapshotId == previous?.snapshotId, "owner_model_actual_parent_required")
        }
        fun verifyHead(request: OwnerModelCompletedPatch, head: WorktreeState, previous: SnapshotCommit?, after: Boolean = false) {
            val binding = request.binding
            val expectedOrdinal = if (after) request.ordinal else request.ordinal - 1L
            ownerModelRequire(head.identity == SnapshotIdentity(binding.ownerId, binding.workspaceId, binding.worktreeId) &&
                head.roots == listOf(SnapshotRoot()) && head.headRevision == (previous?.headRevision ?: 0L) &&
                head.headSnapshotId == previous?.snapshotId && head.sourceEpoch == (previous?.sourceEpoch ?: 0L) &&
                head.headRevision == expectedOrdinal && head.sourceEpoch == expectedOrdinal &&
                head.acceptedPatchCount.toLong() == expectedOrdinal && head.activeLease == null && head.liveState == LiveState.UNVERIFIED,
                "owner_model_actual_head_unknown_or_changed")
        }
        fun verifyCommit(patch: OwnerModelFrozenPatch, previous: SnapshotCommit?, actual: SnapshotCommit) {
            val request = patch.request
            verifyRequest(request, previous)
            val m = actual.manifest
            ownerModelRequire(!actual.replayed && m.schemaVersion == 1 &&
                m.identity == SnapshotIdentity(request.binding.ownerId, request.binding.workspaceId, request.binding.worktreeId) &&
                m.roots == listOf(SnapshotRoot()) && m.entries == patch.entries &&
                m.clientId == request.binding.clientId && m.sessionId == request.binding.sessionId && m.patchId == request.patchId &&
                m.parentSnapshotId == previous?.snapshotId && m.producerBaseSnapshotId == previous?.snapshotId &&
                m.headRevision == request.ordinal && actual.headRevision == m.headRevision &&
                m.capture == CaptureProof(CaptureKind.IMMUTABLE_OVERLAY, (previous?.sourceEpoch ?: 0L) + 1L) &&
                actual.sourceEpoch == m.capture.sourceEpoch && m.scope == patch.overlay(previous?.manifest).scope &&
                m.delta == delta(previous?.manifest?.entries.orEmpty(), patch.entries), "owner_model_snapshot_submission_mismatch")
            val tree = buildJsonObject {
                put("roots", ContractJson.encodeToJsonElement(m.roots)); put("entries", ContractJson.encodeToJsonElement(m.entries))
            }
            ownerModelRequire(m.treeDigest == snapshotDigest("tree-v1", tree) &&
                m.snapshotId == snapshotDigest("manifest-v1", ContractJson.encodeToJsonElement(m.copy(snapshotId = ""))),
                "owner_model_snapshot_digest_mismatch")
        }
        private fun delta(before: List<SnapshotEntry>, after: List<SnapshotEntry>): List<SnapshotDelta> {
            val old = before.associateBy { it.rootId + '\u0000' + it.path }
            val current = after.associateBy { it.rootId + '\u0000' + it.path }
            return (old.keys + current.keys).sorted().mapNotNull { key ->
                val a = old[key]; val b = current[key]
                if (a == b) null else {
                    val entry = requireNotNull(b ?: a)
                    SnapshotDelta(entry.rootId, entry.path, if (a == null) "added" else if (b == null) "deleted" else "modified",
                        a?.let { snapshotDigest("entry-v1", ContractJson.encodeToJsonElement(it)) },
                        b?.let { snapshotDigest("entry-v1", ContractJson.encodeToJsonElement(it)) })
                }
            }
        }
        private fun snapshotDigest(tag: String, value: JsonElement): String =
            Digests.sha256("ktchecker:snapshot:$tag\u0000" + Digests.canonical(value))
    }
}

/** Identity-only custody index. Neither an equal DTO nor a serialized key can recover a value. */
internal class OwnerModelExactHandles<K : Any, V : Any> {
    private val values = IdentityHashMap<K, V>()
    @Synchronized fun retain(key: K, value: V) {
        ownerModelRequire(!values.containsKey(key), "owner_model_delivery_handle_replayed")
        values[key] = value
    }
    @Synchronized fun exact(key: K): V = values[key]
        ?: throw CliProblem("owner_model_delivery_handle_mismatch", "Use the exact retained owner handle.")
}

/** Pure unresolved DATA projection, never a made-up D or an executable observed model. */
internal fun ownerModelUnresolvedInventory(
    registeredSnapshot: StoredSnapshotManifest?,
    submittedFiles: List<SnapshotEntry>?,
    documentaryBefore: JsonElement,
    priorContextVector: List<RequiredPlanSlot>?,
    actualAttemptedPlan: RequiredReviewPlan?,
): JsonObject = buildJsonObject {
    val rules = Catalog().ids.sorted()
    ownerModelRequire(OwnerFullCatalog1665.accepts(rules), "owner_model_catalog_changed")
    put("schema", "ktchecker/owner-model-unresolved-source-inventory/1")
    put("catalogContract", OwnerFullCatalog1665.ID); put("catalogRuleIdsDigest", OwnerFullCatalog1665.RULE_IDS_DIGEST)
    put("dataOnly", true); put("admissionAuthority", false); put("newContextApplicability", "unknown")
    put("registeredSnapshot", registeredSnapshot?.let { ContractJson.encodeToJsonElement(it) } ?: JsonNull)
    put("completeSubmittedFileInventory", submittedFiles?.let { ContractJson.encodeToJsonElement(it) } ?: JsonNull)
    put("documentaryBefore", documentaryBefore)
    put("priorWholeContextVector", priorContextVector?.let { ContractJson.encodeToJsonElement(it) } ?: JsonNull)
    put("actualAttemptedWholePlan", actualAttemptedPlan?.let { ContractJson.encodeToJsonElement(it) } ?: JsonNull)
    put("unresolvedRequiredRuleIds", ContractJson.encodeToJsonElement(rules))
    put("unresolvedScope", "all_prior_contexts_and_unknown_new_applicability_no_supported_prefix")
    put("configurationInputClosureProven", false); put("mandatoryGap", CONFIGURATION_INPUT_CLOSURE_UNPROVEN)
    put("fullProject", false); put("complete", false); put("qualified", false)
}

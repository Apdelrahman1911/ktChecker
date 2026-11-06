package dev.ktchecker.cli

import dev.ktchecker.compiler.CompilerRuleRegistry
import dev.ktchecker.config.*
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import dev.ktchecker.kernel.ClientRegistration
import dev.ktchecker.snapshot.LiveState
import dev.ktchecker.snapshot.SnapshotIdentity
import dev.ktchecker.snapshot.SnapshotRoot
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Explicit owner/config DATA. Neither execution consent nor installed artifact order selects rules. */
@Serializable internal data class OwnerModelCompilerPolicySelection(
    val compilerRuleIds: List<String>,
    val reason: String,
    val authorizeOwnerPolicy: Boolean,
)

/** Pure validation/copy, before any policy file or mutation. Aliases are deliberately NOT resolved. */
internal fun ownerModelFreezeCompilerPolicy(
    input: OwnerModelCompilerPolicySelection,
    supportedRuleIds: Set<String>,
    catalog: Catalog = Catalog(),
): OwnerModelCompilerPolicySelection {
    val bytes = ownerModelStrictBytes(ContractJson.encodeToJsonElement(input))
    ownerModelRequire(bytes.size in 1..131_072, "owner_model_policy_selection_limit")
    val selected = ContractJson.decodeFromString<OwnerModelCompilerPolicySelection>(
        strictJsonDocument(bytes, "owner_model_policy_selection_invalid"))
    ownerModelRequire(selected.authorizeOwnerPolicy, "owner_model_explicit_policy_consent_required")
    ownerModelRequire(selected.reason.isNotBlank() && selected.reason.toByteArray(Charsets.UTF_8).size <= 1024 &&
        selected.reason.none { it.isISOControl() }, "owner_model_policy_reason_required")
    val stableJvmIds = CompilerRuleRegistry.load().jvmRuleIds
    val allowed = catalog.records.filter { it.getValue("default_activation").jsonPrimitive.content != "excluded" }
        .map { it.getValue("id").jsonPrimitive.content }.toSet()
    ownerModelRequire(selected.compilerRuleIds.isNotEmpty() && selected.compilerRuleIds.size <= supportedRuleIds.size &&
        selected.compilerRuleIds == selected.compilerRuleIds.distinct().sorted() &&
        selected.compilerRuleIds.all { it in supportedRuleIds && it in stableJvmIds && it in allowed },
        "owner_model_policy_rule_selection_invalid")
    return selected.copy(compilerRuleIds = Collections.unmodifiableList(selected.compilerRuleIds.toList()))
}

/**
 * Separate owner/config operation. The session gate permits it exactly once BEFORE any review.
 * Every real mutation goes through Workbench.mutateConfig's existing explicit-owner HMAC authority.
 * No review/submit/import callback can call this operation or silently enable a default subset.
 * This holder never issues an execution/model permit. Its returned JSON is audit DATA only.
 */
internal class OwnerModelCompilerPolicy(
    private val workbench: Workbench,
    private val registration: ClientRegistration,
    supportedRuleIds: Set<String>,
    private val root: OwnerModelRootPin,
    private val clock: OwnerModelCommandClock,
) {
    private val supported = Collections.unmodifiableSet(supportedRuleIds.toSet())
    private val attempted = AtomicBoolean()
    private val mutationInFlight = AtomicBoolean()
    private val captured = AtomicReference<ConfigSnapshot?>()
    @Volatile private var selection: OwnerModelCompilerPolicySelection? = null
    @Volatile private var initial: ConfigSnapshot? = null
    @Volatile private var lastReturned: JsonObject? = null
    @Volatile private var lastVerified: ConfigSnapshot? = null
    @Volatile private var requestPin: OwnerModelFilePin? = null
    @Volatile private var witnessPin: OwnerModelFilePin? = null
    @Volatile private var witness: JsonObject? = null
    private val steps = CopyOnWriteArrayList<JsonObject>()
    private val stepPins = CopyOnWriteArrayList<OwnerModelFilePin>()
    private val binding = modelConfigBinding(registration.binding)

    val historyUncertain: Boolean get() = mutationInFlight.get()
    val configured: Boolean get() = captured.get() != null && witnessPin != null
    val compilerRuleIds: List<String> get() {
        requireConfigured()
        return requireNotNull(selection).compilerRuleIds
    }

    fun configure(input: OwnerModelCompilerPolicySelection): JsonObject {
        ownerModelRequire(attempted.compareAndSet(false, true), "owner_model_policy_selection_replayed")
        val chosen = ownerModelFreezeCompilerPolicy(input, supported)
        checkpoint(); workbench.verifyOwnerCode(); checkpoint()
        val identity = SnapshotIdentity(registration.binding.ownerId, registration.binding.workspaceId, registration.binding.worktreeId)
        val head = workbench.snapshots.currentHead(identity)
        ownerModelRequire(head.identity == identity && head.roots == listOf(SnapshotRoot()) &&
            head.headRevision == 0L && head.headSnapshotId == null && head.sourceEpoch == 0L &&
            head.acceptedPatchCount == 0 && head.activeLease == null && head.liveState == LiveState.UNVERIFIED,
            "owner_model_policy_requires_fresh_lineage")
        val before = workbench.configs.capture(binding)
        ownerModelRequire(before.binding == binding && before.shared.revision.revision == 0L && before.local.revision.revision == 0L &&
            before.shared.revision.generationId == null && before.local.revision.generationId == null &&
            before.ruleSettings.isEmpty() && before.suppressions.isEmpty() && before.baselines.isEmpty() &&
            listOf(before.shared, before.local).all { it.auditEvents.isEmpty() && it.suppressionMasks.isEmpty() },
            "owner_model_policy_requires_fresh_configuration")
        initial = before; selection = chosen
        requestPin = ownerModelWrite(root.path.resolve("owner-policy-selection.json"), ownerModelStrictBytes(buildJsonObject {
            put("schema", "ktchecker/owner-compiler-policy-intent/1")
            put("binding", ContractJson.encodeToJsonElement(registration.binding))
            put("selection", ContractJson.encodeToJsonElement(chosen))
            put("beforeRevision", ContractJson.encodeToJsonElement(before.revisionToken))
        }), 262_144, ::checkpoint)
        var current = before
        for (id in chosen.compilerRuleIds) {
            checkpoint(); requestPin!!.verify(::checkpoint)
            val expected = current
            val action = ConfigAction.SetRuleEnabled(id, true)
            mutationInFlight.set(true) // BEFORE the real owner/config operation; failures never rearm.
            val returned = workbench.mutateConfig(registration.binding.clientId, ConfigLayer.LOCAL, expected.revisionToken, action)
            lastReturned = returned // Retain actual returned audit DATA before any verification.
            val after = workbench.configs.capture(binding)
            val event = after.local.auditEvents.lastOrNull()
                ?: throw CliProblem("owner_model_policy_audit_unavailable", "The actual owner configuration audit is unavailable.")
            // Workbench owns requestId/authority. Its actual audit retains the ID and digest, so
            // the exact known args can be reconstructed AS DATA and checked against that digest.
            val request = ConfigMutationRequest(binding, ConfigLayer.LOCAL, expected.revisionToken, action, event.requestId)
            ownerModelRequire(after.binding == binding &&
                returned["revision"] == ContractJson.encodeToJsonElement(after.revisionToken) &&
                returned["local"] == ContractJson.encodeToJsonElement(after.local) &&
                returned["shared"] == ContractJson.encodeToJsonElement(after.shared) &&
                returned["effectiveRules"] == ContractJson.encodeToJsonElement(after.ruleSettings) &&
                after.shared == expected.shared && after.revisionToken.shared == expected.revisionToken.shared &&
                after.local.revision.revision == expected.local.revision.revision + 1L &&
                after.local.auditEvents == expected.local.auditEvents + event && event.revision == after.local.revision.revision &&
                event.actor.ownerId == binding.ownerId && event.actor.clientId == "explicit-owner-cli" && event.actor.sessionId.isNotBlank() &&
                event.requestId.isNotBlank() && event.requestDigest == request.digest() &&
                event.previousEventDigest == expected.local.auditEvents.lastOrNull()?.let {
                    Digests.sha256(Digests.canonical(ContractJson.encodeToJsonElement(it)))
                } && after.local.ruleSettings == (expected.local.ruleSettings + RuleSetting(id, true)).sortedBy { it.ruleId } &&
                after.ruleSettings == expected.ruleSettings + (id to RuleSetting(id, true)) &&
                after.local.suppressions.isEmpty() && after.local.suppressionMasks.isEmpty() && after.local.baselines.isEmpty(),
                "owner_model_policy_audit_mismatch")
            lastVerified = after
            mutationInFlight.set(false)
            val step = buildJsonObject {
                put("request", ContractJson.encodeToJsonElement(request))
                put("beforeRevision", ContractJson.encodeToJsonElement(expected.revisionToken))
                put("afterRevision", ContractJson.encodeToJsonElement(after.revisionToken))
                put("auditEvent", ContractJson.encodeToJsonElement(event))
            }
            steps.add(step)
            stepPins.add(ownerModelWrite(root.path.resolve("owner-policy-step-${steps.size}.json"),
                ownerModelStrictBytes(step), 262_144, ::checkpoint))
            current = after
        }
        val data = buildJsonObject {
            put("schema", "ktchecker/owner-compiler-policy-selection/1")
            put("binding", ContractJson.encodeToJsonElement(registration.binding))
            put("selection", ContractJson.encodeToJsonElement(chosen))
            put("selectionDigest", Digests.sha256(ownerModelStrictBytes(ContractJson.encodeToJsonElement(chosen))))
            put("beforeRevision", ContractJson.encodeToJsonElement(before.revisionToken))
            put("afterRevision", ContractJson.encodeToJsonElement(current.revisionToken))
            put("policyDigest", current.policyDigest)
            put("auditEvents", ContractJson.encodeToJsonElement(current.local.auditEvents))
            put("mutationSteps", JsonArray(steps.toList()))
            put("afterLocal", ContractJson.encodeToJsonElement(current.local))
        }
        witness = data
        witnessPin = ownerModelWrite(root.path.resolve("owner-policy-witness.json"), ownerModelStrictBytes(data),
            OWNER_MODEL_REQUEST_BYTES, ::checkpoint)
        checkpoint()
        ownerModelRequire(workbench.configs.capture(binding).revisionToken == current.revisionToken,
            "owner_model_policy_changed_during_selection")
        ownerModelRequire(captured.compareAndSet(null, current), "owner_model_policy_selection_replayed")
        return data
    }

    fun requireConfigured() {
        ownerModelRequire(configured && !historyUncertain, "owner_model_explicit_policy_selection_required")
    }

    /** Read-only audit under THIS patch's clock. Never use the first/old120s on a fix. */
    fun verifyCurrent(checkpoint: () -> Unit) {
        requireConfigured(); checkpoint(); root.verify()
        requestPin!!.verify(checkpoint); witnessPin!!.verify(checkpoint); stepPins.forEach { it.verify(checkpoint) }
        ownerModelRequire(workbench.registration(registration.binding.clientId) == registration &&
            workbench.configs.capture(binding).revisionToken == requireNotNull(captured.get()).revisionToken,
            "owner_model_selected_policy_changed")
        checkpoint()
    }

    fun verifyIntent(intent: OwnerModelIntent) {
        requireConfigured()
        val policy = requireNotNull(captured.get())
        ownerModelRequire(intent.binding == registration.binding && intent.configuration.binding == binding &&
            intent.configuration.revisionToken == policy.revisionToken && intent.configuration.ruleSettings == policy.ruleSettings &&
            intent.evidence.configuration.revisionToken == policy.revisionToken &&
            intent.configuration.ruleSettings.keys.sorted() == compilerRuleIds &&
            intent.configuration.ruleSettings.values.all { it.enabled }, "owner_model_intent_policy_mismatch")
    }

    fun evidence(): JsonObject = buildJsonObject {
        put("selectionAttempted", attempted.get()); put("configured", configured); put("historyUncertain", historyUncertain)
        put("selection", selection?.let { ContractJson.encodeToJsonElement(it) } ?: JsonNull)
        put("initialRevision", initial?.let { ContractJson.encodeToJsonElement(it.revisionToken) } ?: JsonNull)
        put("lastReturnedAudit", lastReturned ?: JsonNull)
        put("lastVerifiedRevision", lastVerified?.let { ContractJson.encodeToJsonElement(it.revisionToken) } ?: JsonNull)
        put("witness", witness ?: JsonNull); put("mutationSteps", JsonArray(steps.toList()))
        put("policyMutationIsExecutionConsent", false); put("qualified", false)
    }

    private fun checkpoint() {
        clock.checkpoint(); root.verify()
        ownerModelRequire(workbench.registration(registration.binding.clientId) == registration, "owner_model_registration_changed")
    }
}

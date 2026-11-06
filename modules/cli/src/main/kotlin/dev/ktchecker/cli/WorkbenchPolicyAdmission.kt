package dev.ktchecker.cli

import dev.ktchecker.config.ConfigSnapshot
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement

/**
 * A retained owner-preparation record, NOT a signature, invocation certificate or capability.
 * The private owner registry and its immutable per-input association are the trust boundary.
 * No caller-supplied copy of this DTO is accepted by a review transport.
 */
@Serializable
internal data class WorkbenchPolicyAdmission(
    val codeImageDigest: String,
    val preparationDigest: String,
    val activation: PolicyActivationAuthority,
    val routes: List<PolicyRouteAuthority>,
    val format: String = POLICY_ADMISSION_FORMAT,
)

/** The old full policy remains in PreparedReview; this is its typed activation body. */
@Serializable
internal data class PolicyActivationAuthority(
    val explicitOwnerSubset: Boolean,
    val registryDigest: String,
    val effectiveRuleSettings: Map<String, Boolean>,
    val decisions: List<RuleActivation>,
    val activationSchemaVersion: Int,
)

/** Keep route order and duplicate provider routes; a Map keyed by provider ID would lose them. */
@Serializable
internal data class PolicyRouteAuthority(
    val ordinal: Int,
    val providerId: String,
    val providerVersion: String,
    val implementationIdentity: String?,
    val supportedRuleIds: List<String>,
    val applicable: Boolean,
    val selectedRuleIds: List<String>,
    val hasProfileGenerator: Boolean,
    val hasLivePreparedChild: Boolean,
    val hasFinishHook: Boolean,
)

internal const val POLICY_ADMISSION_FORMAT = "ktchecker/workbench-owner-policy-admission/1"

private fun preparationDigest(record: PreparedReview): String = Digests.sha256(
    POLICY_ADMISSION_FORMAT + "\n" + Digests.canonical(ContractJson.encodeToJsonElement(record)),
)

/**
 * Called only by the fixed ordinary Workbench owner-preparation path. This does not compare two
 * executions or erase any path, compiler key, opaque policy or original configuration field.
 * All original PreparedReview fields are covered by the preparation digest, including consent.
 */
internal fun describePolicyAdmission(
    record: PreparedReview,
    originalPolicy: ConfigSnapshot,
    explicitOwnerSubset: Boolean,
    codeImageDigest: String,
    catalog: Catalog,
    routes: List<ProviderRoute>,
): WorkbenchPolicyAdmission {
    val input = record.input
    if (!Digests.valid(codeImageDigest) || record.binding.ownerId != input.snapshot.ownerId ||
        record.binding.workspaceId != input.snapshot.workspaceId || record.binding.worktreeId != input.snapshot.worktreeId ||
        originalPolicy.binding != record.configuration.binding ||
        originalPolicy.binding.ownerId != record.binding.ownerId ||
        originalPolicy.binding.workspaceId != record.binding.workspaceId ||
        originalPolicy.binding.worktreeId != record.binding.worktreeId ||
        originalPolicy.binding.lineageId != record.binding.lineageId ||
        originalPolicy.revisionToken != record.configuration.revisionToken ||
        originalPolicy.policyDigest != input.context.configurationDigest) {
        throw CliProblem("policy_admission_identity_mismatch", "Owner preparation and original captured policy differ.")
    }
    val expectedScope = if (explicitOwnerSubset) "explicit-rule-subset-not-full-release"
        else "all-catalog-candidates-for-declared-target"
    val policyKeys = setOf("ktchecker.model", "ktchecker.coverage", ACTIVATION_POLICY_KEY, "ktchecker.execution-plan")
    if (record.coverageScope != expectedScope || input.context.policies.keys != policyKeys ||
        input.context.policies["ktchecker.model"] != "owner-declared-single-compilation" ||
        input.context.policies["ktchecker.coverage"] != "partial-project" || input.context.fullProject ||
        input.execution != null || input.outputRoot != null) {
        throw CliProblem("policy_admission_model_unsupported", "Only the explicit ordinary preparation schema has a policy-admission producer.")
    }
    val activation = activationPlan(catalog, input.requiredRuleIds, explicitOwnerSubset,
        originalPolicy.ruleSettings.mapValues { it.value.enabled }, originalPolicy.policyDigest)
    val retainedActivation = try {
        ContractJson.decodeFromString<ActivationPlan>(input.context.policies.getValue(ACTIVATION_POLICY_KEY))
    } catch (_: Exception) {
        throw CliProblem("policy_admission_activation_invalid", "Original activation capture is malformed.")
    }
    if (retainedActivation != activation || input.context.policies["ktchecker.execution-plan"] !=
        Digests.sha256(Digests.canonical(ContractJson.encodeToJsonElement(record.profiles)))) {
        throw CliProblem("policy_admission_activation_invalid", "Original activation or executable profiles differ from owner preparation.")
    }
    val remaining = activation.decisions.filter { it.active }.map { it.ruleId }.toMutableSet()
    val routeAuthority = routes.mapIndexed { index, route ->
        val applicable = route.applicable(input)
        val supported = route.provider.supportedRuleIds.toSortedSet().toList()
        val selected = if (applicable) remaining.intersect(supported.toSet()).sorted() else emptyList()
        remaining.removeAll(selected.toSet())
        PolicyRouteAuthority(index, route.provider.id, route.provider.version, route.implementationIdentity,
            supported, applicable, selected, route.executionProfile != null, route.preparedChild != null,
            route.finishAttempt != null)
    }
    if (routeAuthority.any { it.selectedRuleIds.isNotEmpty() && it.hasLivePreparedChild }) {
        throw CliProblem("policy_admission_live_authority_unsupported", "A retained model route needs a distinct live-authority policy relation.")
    }
    return WorkbenchPolicyAdmission(codeImageDigest, preparationDigest(record),
        PolicyActivationAuthority(explicitOwnerSubset, record.configuration.registryDigest,
            originalPolicy.ruleSettings.mapValues { it.value.enabled }, activation.decisions, activation.schemaVersion), routeAuthority)
}

/**
 * Forward-only companion to the existing immutable prepared:<input-key> registry.
 * A crash between the two durable writes can leave a normal preparation without an admission
 * descriptor. That record remains usable for normal review, but is NOT retroactively upgraded.
 * An orphan descriptor or conflicting prepared record is an explicit error, never repaired.
 * This is cooperative owner-private storage, not a hostile same-UID filesystem sandbox.
 */
internal class WorkbenchPolicyAdmissions(private val state: PrivateState) {
    fun publishNew(record: PreparedReview, admission: WorkbenchPolicyAdmission) {
        val frozen = ContractJson.decodeFromString<PreparedReview>(ContractJson.encodeToString(record))
        val descriptor = ContractJson.decodeFromString<WorkbenchPolicyAdmission>(ContractJson.encodeToString(admission))
        validateDescriptor(frozen, descriptor)
        val key = frozen.input.analysisKey()
        state.locked("prepared:$key") {
            val file = state.keyFile("prepared", key)
            // A matching legacy record is a retry, never permission to backfill provenance.
            if (Files.exists(file, NOFOLLOW_LINKS)) {
                if (state.read<PreparedReview>(file) != frozen) {
                    throw CliProblem("prepared_binding_conflict", "Input already has a different immutable consumer/authorization binding.")
                }
                val oldDescriptor = existingPath("policy-admissions", key)
                if (Files.exists(oldDescriptor, NOFOLLOW_LINKS) &&
                    state.read<WorkbenchPolicyAdmission>(oldDescriptor) != descriptor) {
                    throw CliProblem("policy_admission_conflict", "An existing preparation has a different retained policy-admission record.")
                }
                return@locked
            }
            val descriptorFile = state.keyFile("policy-admissions", key)
            if (Files.exists(descriptorFile, NOFOLLOW_LINKS)) {
                throw CliProblem("policy_admission_orphan", "Retained policy admission has no original preparation; preserve it for owner reconciliation.")
            }
            state.write(file, frozen)
            state.write(descriptorFile, descriptor)
        }
    }

    /**
     * Read-only lookup: no namespace/keyFile call, missing-file creation, migration or repair.
     * The stored PreparedReview must still equal the exact original record, including every
     * consent/profile/config field. A previously cached object does not hide on-disk drift.
     * The caller separately checks current code, typed authority and CONFIG -> SOURCE fences.
     */
    fun requireRetained(record: PreparedReview): WorkbenchPolicyAdmission {
        val frozen = ContractJson.decodeFromString<PreparedReview>(ContractJson.encodeToString(record))
        val key = frozen.input.analysisKey()
        val preparedPath = existingPath("prepared", key)
        val admissionPath = existingPath("policy-admissions", key)
        if (!Files.isRegularFile(preparedPath, NOFOLLOW_LINKS) || !Files.isRegularFile(admissionPath, NOFOLLOW_LINKS)) {
            throw CliProblem("policy_admission_unavailable", "This exact preparation has no retained policy-admission record; legacy history is not upgraded.")
        }
        if (state.read<PreparedReview>(preparedPath) != frozen) {
            throw CliProblem("policy_admission_preparation_changed", "Retained owner preparation no longer equals the original review binding.")
        }
        return state.read<WorkbenchPolicyAdmission>(admissionPath).also { validateDescriptor(frozen, it) }
    }

    private fun existingPath(namespace: String, key: String): Path = state.root.resolve(namespace)
        .resolve(Digests.sha256(key).removePrefix("sha256:") + ".json")

    private fun validateDescriptor(record: PreparedReview, admission: WorkbenchPolicyAdmission) {
        if (record.binding.ownerId != state.ownerId || admission.format != POLICY_ADMISSION_FORMAT ||
            !Digests.valid(admission.codeImageDigest) || admission.preparationDigest != preparationDigest(record) ||
            admission.activation.registryDigest != record.configuration.registryDigest ||
            admission.activation.activationSchemaVersion != 1 ||
            admission.routes.map { it.ordinal } != admission.routes.indices.toList()) {
            throw CliProblem("policy_admission_invalid", "Retained preparation descriptor has an invalid identity or schema.")
        }
    }
}

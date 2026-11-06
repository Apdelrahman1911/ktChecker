package dev.ktchecker.cli

import dev.ktchecker.config.ConfigBinding
import dev.ktchecker.config.ConfigStore
import dev.ktchecker.config.PolicyReassessmentSubject
import dev.ktchecker.contracts.AnalysisInput
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.ProviderResult
import dev.ktchecker.kernel.ClientBinding
import dev.ktchecker.kernel.CertifiedPolicyReview
import dev.ktchecker.kernel.PolicyReassessmentAuthority
import dev.ktchecker.kernel.PolicyReassessmentCapture
import dev.ktchecker.kernel.PreparedPolicyReassessment
import dev.ktchecker.kernel.SnapshotCheck
import dev.ktchecker.snapshot.SnapshotIdentity
import dev.ktchecker.snapshot.SnapshotStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * Ordinary Workbench policy-view adapter. Only the kernel supplies CertifiedPolicyReview, resolved
 * from its retained ledger certificates; no review RPC accepts this DTO, old captures or permits.
 * Reuses the EXACT executed input and raw result. No prepare/materialize/provider/profile/grant
 * path is called here, including restoration. Original configuration and execution identities
 * remain original. The present single-compilation/overlay integration is deliberately audit-only.
 */
internal class WorkbenchPolicyReassessments(
    private val state: PrivateState,
    private val configs: ConfigStore,
    private val snapshots: SnapshotStore,
    private val codeImageDigest: String,
    private val verifyCode: () -> Unit,
    private val originalPreparation: (AnalysisInput) -> PreparedReview,
    private val retainedAdmission: (PreparedReview) -> WorkbenchPolicyAdmission,
    private val providerId: String,
    private val providerVersion: String,
    private val archive: WorkbenchPolicyCaptureArchive = WorkbenchPolicyCaptureArchive(state),
) : PolicyReassessmentAuthority {
    override val evaluatorIdentity: String = "ktchecker/workbench-byte-bound-policy-view/1:$codeImageDigest"
    override val issuerIdentity: String = "ktchecker/workbench-owner-policy-view/1:$codeImageDigest"

    init { require(Digests.valid(codeImageDigest)) }

    override fun prepare(binding: ClientBinding, subject: CertifiedPolicyReview,
                         expectedPolicyDigest: String): PreparedPolicyReassessment {
        if (!Digests.valid(expectedPolicyDigest)) {
            throw CliProblem("policy_reassessment_expectation_invalid", "An exact expected current policy digest is required.")
        }
        val sealed = detached(subject)
        val original = requireOriginal(binding, sealed)
        val current = configs.exportSnapshot(configs.capture(configBinding(binding)))
        if (current.revisionToken.policyDigest != expectedPolicyDigest) {
            throw CliProblem("policy_reassessment_policy_changed", "Current policy differs from the explicit request expectation; no captures were retained.")
        }
        val pair = configs.prepareReassessment(sealed.input, original.record.configuration, current)
        // Preparation rejection does not publish a kernel operation or alter project policy.
        // Archives may retain harmless unused captures if a later currentness/CAS fence rejects.
        val (originalId, currentId) = archive.retain(pair.originalConfig, pair.currentConfig)
        val capture = PolicyReassessmentCapture(pair.originalConfig.revisionToken.policyDigest,
            pair.currentConfig.revisionToken.policyDigest, originalId, currentId, original.authorityDigest)
        return Prepared(binding, sealed, original, pair, capture)
    }

    override fun restore(binding: ClientBinding, subject: CertifiedPolicyReview,
                         capture: PolicyReassessmentCapture): PreparedPolicyReassessment {
        val sealed = detached(subject)
        val original = requireOriginal(binding, sealed)
        val old = archive.restore(capture.originalCaptureId)
        val current = archive.restore(capture.currentCaptureId)
        if (capture.format != PolicyReassessmentCapture.FORMAT || old != original.record.configuration ||
            capture.analysisAuthorityDigest != original.authorityDigest ||
            capture.originalPolicyDigest != old.revisionToken.policyDigest ||
            capture.currentPolicyDigest != current.revisionToken.policyDigest) {
            throw CliProblem("policy_reassessment_archive_mismatch", "Recorded captures no longer bind this exact original owner preparation and analysis authority.")
        }
        val pair = configs.prepareReassessment(sealed.input, old, current)
        // Neither capture(current) nor apply(raw) runs on historical restoration.
        return Prepared(binding, sealed, original, pair, capture)
    }

    private data class Original(val record: PreparedReview, val authorityDigest: String)

    private fun requireOriginal(binding: ClientBinding, subject: CertifiedPolicyReview): Original {
        verifyCode()
        val record = detached(originalPreparation(detached(subject.input)))
        if (binding.ownerId != state.ownerId || record.binding != binding || record.input != subject.input ||
            record.patchId != subject.patchId || subject.workInputKey != record.input.analysisKey() ||
            subject.raw.providerId != providerId || subject.raw.providerVersion != providerVersion ||
            subject.originalEffective.providerId != providerId || subject.originalEffective.providerVersion != providerVersion ||
            listOf(record.input.context.modelDigest, record.input.context.dependencyDigest,
                record.input.context.toolchainDigest).any { it == null || !Digests.valid(it) }) {
            throw CliProblem("policy_reassessment_original_mismatch", "Original ledger subject does not match this exact owner, consumer, preparation and installed provider.")
        }
        val admission = detached(retainedAdmission(detached(record)))
        if (admission.codeImageDigest != codeImageDigest) {
            throw CliProblem("policy_reassessment_authority_changed", "Original preparation belongs to another code image.")
        }
        val identity = SnapshotIdentity(binding.ownerId, binding.workspaceId, binding.worktreeId)
        if (snapshots.snapshot(identity, record.input.snapshot.snapshotId).toContractManifest() != record.input.snapshot) {
            throw CliProblem("policy_reassessment_snapshot_mismatch", "Original manifest differs from the owner's retained immutable snapshot.")
        }
        val authorityDigest = Digests.sha256(Digests.canonical(buildJsonObject {
            put("format", "ktchecker/workbench-policy-analysis-authority/1")
            put("consumer", ContractJson.encodeToJsonElement(binding))
            put("originalSubjectDigest", Digests.sha256(Digests.canonical(ContractJson.encodeToJsonElement(subject))))
            put("ownerAdmission", ContractJson.encodeToJsonElement(admission))
        }))
        verifyCode()
        return Original(record, authorityDigest)
    }

    private inner class Prepared(
        private val binding: ClientBinding,
        private val subject: CertifiedPolicyReview,
        private val original: Original,
        private val pair: PolicyReassessmentSubject,
        override val capture: PolicyReassessmentCapture,
    ) : PreparedPolicyReassessment {
        private val exactRaw = Digests.canonical(ContractJson.encodeToJsonElement(subject.raw))

        /** Pure in-memory classification; every other field, including execution-key evidence, survives. */
        override fun apply(raw: ProviderResult): ProviderResult {
            if (Digests.canonical(ContractJson.encodeToJsonElement(raw)) != exactRaw) {
                throw CliProblem("policy_reassessment_raw_changed", "Policy evaluation requires the same full certified raw result.")
            }
            return configs.applyReassessment(pair, raw)
        }

        override fun <T> withCurrent(action: (SnapshotCheck) -> T): T {
            // All archive/code/manifest checks run before acquiring CONFIG or SOURCE. Admission
            // records are immutable under the trusted owner protocol, not mutable live permits.
            val retained = try {
                requireOriginal(binding, subject) == original &&
                    archive.restore(capture.originalCaptureId) == pair.originalConfig &&
                    archive.restore(capture.currentCaptureId) == pair.currentConfig
            } catch (_: Exception) { false }
            // The user callback is outside the catch even for an unavailable retained record.
            if (!retained) return action(SnapshotCheck(false, reasons = listOf("policy_reassessment_retained_authority_unavailable")))
            val current = pair.currentConfig
            val input = subject.input
            var entered = false
            try {
                return configs.withCurrentConfig(configBinding(binding), current.revisionToken) { config ->
                    snapshots.withVerifiedSnapshot(SnapshotIdentity(binding.ownerId, binding.workspaceId, binding.worktreeId),
                        input.snapshot.snapshotId) { source ->
                        val head = source.head
                        val reasons = config.reasons + listOfNotNull(source.reason) +
                            listOf("owner_declared_compilation_not_full_project_model", "immutable_overlay_does_not_certify_physical_live_worktree")
                        val epoch = Digests.sha256(Digests.canonical(buildJsonObject {
                            put("format", "ktchecker/workbench-policy-view-fence/1")
                            put("configuration", ContractJson.encodeToJsonElement(current.revisionToken))
                            put("analysisAuthority", capture.analysisAuthorityDigest)
                            put("sourceEpoch", head.sourceEpoch)
                            put("sourceSnapshot", head.headSnapshotId)
                        }))
                        val check = SnapshotCheck(current = config.matches && source.valid,
                            snapshotId = head.headSnapshotId, sourceEpoch = head.sourceEpoch,
                            configurationDigest = config.current.policyDigest, modelDigest = input.context.modelDigest,
                            dependencyDigest = input.context.dependencyDigest, toolchainDigest = input.context.toolchainDigest,
                            analysisEpoch = epoch, authoritative = false, reasons = reasons,
                            validUntilEpochMillis = config.current.validUntilEpochMillis)
                        entered = true
                        action(check)
                    }
                }
            } catch (failure: Exception) {
                // A failed pre-action lock/read gets one negative observation. NEVER swallow,
                // retry or replace any callback failure, including failure after its side effect.
                if (entered) throw failure
                return action(SnapshotCheck(false, reasons = listOf("policy_reassessment_current_fence_unavailable")))
            }
        }
    }

    private fun configBinding(binding: ClientBinding) = ConfigBinding(binding.ownerId, binding.workspaceId, binding.worktreeId, binding.lineageId)
    private inline fun <reified T> detached(value: T): T = ContractJson.decodeFromString(ContractJson.encodeToString(value))
}

package dev.ktchecker.kernel

import dev.ktchecker.contracts.AnalysisInput
import dev.ktchecker.contracts.ProviderResult
import kotlinx.serialization.Serializable

/** Identifiers/expectations only. Neither a raw result, an input nor a certificate is caller-supplied. */
@Serializable data class PolicyReassessmentRequest(
    val reviewId: String,
    val idempotencyKey: String,
    val generation: Long,
    val expectedPolicyDigest: String,
)

/**
 * Detached, ledger-resolved original execution, supplied ONLY by the kernel to its trusted adapter.
 * No kernel API accepts this DTO as authority. The accepted input and both execution keys remain
 * original; reassessment neither executes a new input nor supplies a cache/execution capability.
 */
@Serializable data class CertifiedPolicyReview(
    val reviewId: String,
    val patchId: String,
    val reviewSequence: Long,
    val input: AnalysisInput,
    val workKey: String,
    val providerKey: String,
    val workInputKey: String,
    val effectiveInputKey: String,
    val workCertificateId: String,
    val originalPolicyCertificate: String,
    val raw: ProviderResult,
    val originalEffective: ProviderResult,
)

/**
 * Bounded content-addressed owner-archive references, NOT full configuration or authority documents.
 * The trusted adapter must durably archive the exact original/current full policy captures and
 * validate the same versioned analysis authority BEFORE returning. These hashes are identifiers,
 * never caller-granted equivalence, execution consent, source authority or completion permission.
 */
@Serializable data class PolicyReassessmentCapture(
    val originalPolicyDigest: String,
    val currentPolicyDigest: String,
    val originalCaptureId: String,
    val currentCaptureId: String,
    val analysisAuthorityDigest: String,
    val format: String = FORMAT,
) {
    companion object { const val FORMAT = "ktchecker/policy-reassessment-capture/1" }
}

/** All identities are of the original certified execution, never a rewritten current-policy input. */
@Serializable data class PolicyReassessmentReference(
    val reviewSequence: Long,
    val inputKey: String,
    val workKey: String,
    val effectiveInputKey: String,
    val workCertificateId: String,
    val originalPolicyCertificateDigest: String,
    val rawResultDigest: String,
    val originalEffectiveResultDigest: String,
    val subjectDigest: String,
)

/**
 * Immutable historical policy view, also the normal delivery-required event payload. The result
 * retains original evidence/coverage; state explicitly does NOT describe a fresh execution.
 * auditOnly=false is NOT an allow decision: every live source/config/coverage/history/delivery and
 * one-shot action fence is still required. A previously published view can become stale later.
 */
@Serializable data class PolicyReassessmentView(
    val viewId: String,
    val request: PolicyReassessmentRequest,
    val clientId: String,
    val patchId: String,
    val sourceSnapshotId: String,
    val sequence: Long,
    val generation: Long,
    val certified: PolicyReassessmentReference,
    val capture: PolicyReassessmentCapture,
    val evaluatorIdentity: String,
    val issuerIdentity: String,
    val result: ProviderResult,
    val auditOnly: Boolean,
    val reasons: List<String>,
    val certificateDigest: String,
    val reviewId: String = request.reviewId,
    val state: String = "policy_reassessed",
    val format: String = FORMAT,
) {
    companion object { const val FORMAT = "ktchecker/policy-reassessment-view/1" }
}

/**
 * Trusted immutable capture plus pure evaluator. apply runs in a short DB transaction and MUST NOT
 * do I/O, acquire config/source locks, reenter the kernel or obtain execution authority. withCurrent
 * holds CONFIG -> SOURCE across its callback, supplies independently checked original analysis
 * identity and the NEW full policy digest, and rechecks expiry/currentness. It must invoke action
 * exactly once, including a negative check, and must not catch/retry exceptions thrown by action.
 * Non-authoritative/partial views can be published for audit, but cannot acquire completion power.
 */
interface PreparedPolicyReassessment {
    val capture: PolicyReassessmentCapture
    fun apply(raw: ProviderResult): ProviderResult
    fun <T> withCurrent(action: (SnapshotCheck) -> T): T
}

/**
 * Trusted in-process owner integration, not model-facing permissions. prepare/restore run outside
 * all ledger locks. Both identities are stable implementation/schema identities, never policy
 * revisions supplied by callers. Missing, legacy or mismatched admission/authority proof MUST fail.
 * prepare checks the expected full policy digest BEFORE retaining new durable capture records;
 * that expectation is data, not equivalence/owner authority. Rejected or raced preparation can
 * leave bounded unused owner records, never an accepted kernel view.
 * restore loads EXACT recorded full captures from the owner archive; never substitute current
 * policy, rerun evaluation, prepare a provider, materialize source or acquire/revive worker grants.
 */
interface PolicyReassessmentAuthority {
    val evaluatorIdentity: String
    val issuerIdentity: String
    fun prepare(binding: ClientBinding, subject: CertifiedPolicyReview, expectedPolicyDigest: String): PreparedPolicyReassessment
    fun restore(binding: ClientBinding, subject: CertifiedPolicyReview,
                capture: PolicyReassessmentCapture): PreparedPolicyReassessment

    companion object {
        val DENY: PolicyReassessmentAuthority = object : PolicyReassessmentAuthority {
            override val evaluatorIdentity = "ktchecker/policy-reassessment/denied/1"
            override val issuerIdentity = "ktchecker/policy-reassessment/denied/1"
            override fun prepare(binding: ClientBinding, subject: CertifiedPolicyReview, expectedPolicyDigest: String): PreparedPolicyReassessment =
                fail("policy_reassessment_authority_unavailable", "An exact trusted same-raw policy authority is required")
            override fun restore(binding: ClientBinding, subject: CertifiedPolicyReview,
                                 capture: PolicyReassessmentCapture): PreparedPolicyReassessment =
                fail("policy_reassessment_authority_unavailable", "The exact recorded policy authority is unavailable")
        }
    }
}

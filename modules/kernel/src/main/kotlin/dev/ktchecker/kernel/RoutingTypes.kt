package dev.ktchecker.kernel

import kotlinx.serialization.Serializable

/**
 * Identity of an exact recipient's already captured policy and execution approval, NOT authority
 * by itself. planDigest covers the complete canonical local RequiredReviewPlan, including the
 * exact ClientBinding, request, verified model/impact identities and every required input.
 * The two preparation digests must include the recipient binding and revision/approval identity;
 * equivalent computation does not make one client's capture or approval another client's grant.
 */
@Serializable data class PlanRecipientPreparation(
    val planDigest: String,
    val capturedPolicyDigest: String,
    val executionApprovalDigest: String,
)

/**
 * Nonserialized trusted-owner input, never accepted from transport/model JSON. The actual adapter
 * capability authenticates the independently enrolled recipient; a client ID/model certificate is
 * not enough. verifyForAdmission must reverify the live owner-created model/materialization and
 * durably retain THIS recipient's captured policy and execution approval for every local slot.
 * It runs outside kernel/config/source locks and returns the same immutable preparation identity,
 * or null. Echoing the expected digests without those actual checks does not implement this SPI.
 * Per-attempt PreparedInputObserver/provider authority is still mandatory for process execution.
 */
class AuthorizedPlanRecipient(
    val plan: RequiredReviewPlan,
    val adapter: AdapterCredential,
    val preparation: PlanRecipientPreparation,
    val verifyForAdmission: () -> PlanRecipientPreparation?,
)

/**
 * Explicit whole-route owner grant. origin must equal the complete initiating authority response,
 * not a caller-selected projection. Each recipient has its own same-client local plan, private
 * request tuple, actual enrollment, model/context, policy capture and execution approval. No
 * constructor/decoded DTO is authority: only the installed ReviewPlanRoutingAuthority may supply
 * this object. The kernel never performs a recipient credential lookup based on caller JSON.
 */
class AuthorizedReviewRoute(val origin: RequiredReviewPlan, recipients: Collection<AuthorizedPlanRecipient>) {
    val recipients: List<AuthorizedPlanRecipient> = recipients.toList()
}

/** Called outside locks; default deny. Existing same-client plans do not call this authority. */
fun interface ReviewPlanRoutingAuthority {
    fun authorize(binding: ClientBinding, request: SubmitPlanRequest, declared: RequiredReviewPlan): AuthorizedReviewRoute?

    companion object { val DENY = ReviewPlanRoutingAuthority { _, _, _ -> null } }
}

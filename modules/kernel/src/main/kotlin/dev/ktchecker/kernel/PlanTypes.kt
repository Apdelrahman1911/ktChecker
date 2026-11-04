package dev.ktchecker.kernel

import dev.ktchecker.contracts.AnalysisInput
import dev.ktchecker.contracts.PREVIEW_CONTRACT
import dev.ktchecker.contracts.RuleContext
import kotlinx.serialization.Serializable

/** A reference into the trusted controller's immutable registry, never a caller-authored plan. */
@Serializable data class SubmitPlanRequest(val patchId: String, val idempotencyKey: String, val planRef: String)

/** Per-compilation identity. Different contexts may have different classpaths, compilers and policy. */
@Serializable data class PlanContextIdentity(
    val context: RuleContext,
    val modelDigest: String,
    val dependencyDigest: String,
    val toolchainDigest: String,
    val compilerVersion: String,
    val compilerArgumentsDigest: String,
    /** Ordered ArtifactIdentity records, including coordinates, digests and original paths. */
    val classpathDigest: String,
    val librariesDigest: String,
    val policiesDigest: String,
    val compilationFilesDigest: String,
    val configurationDigest: String,
)

/** Unsupported inputs are retained as administrative failures, NEVER executed or certified. */
@Serializable data class RequiredPlanSlot(
    val slotId: String,
    val input: AnalysisInput,
    val identity: PlanContextIdentity? = null,
    val state: String = "pending",
    val reasons: List<String> = emptyList(),
)

/**
 * Serializable evidence returned ONLY by ReviewPlanAuthority. Constructing/decoding this DTO is
 * not authority. The controller must derive the entire vector from verified model+impact facts,
 * including unavailable/removed contexts, declared roots and generated-source membership.
 */
@Serializable data class RequiredReviewPlan(
    val binding: ClientBinding,
    /** The immutable owner-prepared patch/ref/idempotency tuple; a ref cannot be retargeted. */
    val request: SubmitPlanRequest,
    val sourceBindingDigest: String,
    val generatedInputsDigest: String,
    val modelCertificateId: String,
    val impactCertificateId: String,
    val slots: List<RequiredPlanSlot>,
    /** Full required delivery-recipient set from the controller, not caller-selected narrowing. */
    val requiredRecipientClientIds: List<String>,
)

/**
 * Trusted controller SPI. Called outside project/config/DB locks; DENY is the default.
 * Resolve only a privately registered exact binding+request, not a plan submitted in request JSON.
 */
fun interface ReviewPlanAuthority {
    fun resolve(binding: ClientBinding, request: SubmitPlanRequest): RequiredReviewPlan?
    companion object { val DENY = ReviewPlanAuthority { _, _ -> null } }
}

@Serializable data class PlanSlotStatus(
    val slotId: String,
    val reviewId: String,
    val context: RuleContext,
    val requiredRuleIds: List<String>,
    val state: String,
    val reasons: List<String>,
)

/** complete means child computations finished; it is NOT a final-answer/gate authorization. */
@Serializable data class ParentPlanStatus(
    val planId: String,
    val clientId: String,
    val patchId: String,
    val sourceSnapshotId: String,
    val planDigest: String,
    val state: String,
    val slots: List<PlanSlotStatus>,
    val reasons: List<String> = emptyList(),
    val contractVersion: String = PREVIEW_CONTRACT,
)

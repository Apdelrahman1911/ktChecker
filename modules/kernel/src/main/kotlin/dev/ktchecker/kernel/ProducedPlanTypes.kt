package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** Narrow, unqualified declaration profile. Never an exception to ordinary pending/fullProject. */
const val PRODUCED_JVM_PLAN_PROFILE = "ktchecker/produced-jvm-plan/1"

/** Separate one-shot assurance. This evidence is never a verified/reusable project model. */
const val OBSERVED_EXPORT_PLAN_ASSURANCE = "ktchecker/observed-export-one-shot/1"
const val OBSERVED_EXPORT_CONFIGURATION_GAP = "configuration_input_closure_unproven"

/** Serializable identity only. An exact live owner grant is still required for each execution. */
@Serializable data class ObservedExportPlanEvidence(
    val binding: ClientBinding,
    val invocationId: String,
    val evidenceDigest: String,
    val assurance: String = OBSERVED_EXPORT_PLAN_ASSURANCE,
) {
    fun digest(): String = Digests.sha256(canonical(this))
    fun policies(): Map<String, String> = mapOf(
        "ktchecker.model-assurance" to assurance,
        "ktchecker.observed-export-invocation" to invocationId,
        "ktchecker.observed-model-evidence" to evidenceDigest,
        "ktchecker.observed-export-closure" to OBSERVED_EXPORT_CONFIGURATION_GAP,
        "ktchecker.observed-export-evidence" to digest(),
    )
}

internal val OBSERVED_EXPORT_POLICY_KEYS = setOf(
    "ktchecker.model-assurance", "ktchecker.observed-export-invocation", "ktchecker.observed-model-evidence",
    "ktchecker.observed-export-closure", "ktchecker.observed-export-evidence",
)

/** Also taint incomplete/corrupt marker combinations: absence of proof cannot become clean. */
internal fun observedExportReasons(input: AnalysisInput): List<String> =
    if (input.context.policies.keys.any { it in OBSERVED_EXPORT_POLICY_KEYS }) listOf(OBSERVED_EXPORT_CONFIGURATION_GAP) else emptyList()

@Serializable data class ProducedCompilationDeclaration(
    val slotId: String,
    val compilationId: String,
    val rawContextDigest: String,
    val argumentLayoutDigest: String,
)

/** Immutable unresolved graph evidence. A JSON instance grants neither model nor execution authority. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class JvmProducedPlanDeclaration(
    val graphDigest: String,
    val importRequestDigest: String,
    val modelDigest: String,
    val moduleId: String,
    val toolchainDigest: String,
    val producer: ProducedCompilationDeclaration,
    val consumer: ProducedCompilationDeclaration,
    val referenceDigest: String,
    val classpathIndex: Int,
    val friend: Boolean,
    val catalogDigest: String,
    val requiredRulesDigest: String,
    val intentDigest: String,
    val originalDeadlineIdentity: String,
    val originalDeadlineMillis: Long,
    val profile: String = PRODUCED_JVM_PLAN_PROFILE,
    /** Omitted for the old profile so existing schema3 canonical bytes and work keys stay exact. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val observedExport: ObservedExportPlanEvidence? = null,
)

/** Same-process original budget evidence copied from the owner's ORIGINAL clock, not a new timer.
 * Only a trusted live admission can enroll this object. Never serialized/restored from the ledger. */
class ProducedPlanClock(val ownerClock: Any, val identity: String, val startedAtNanos: Long, val durationMillis: Long) {
    init { require(identity.isNotBlank() && durationMillis in 1..3_600_000) }
    fun isExpired(): Boolean = isExpiredAt(System.nanoTime())
    fun remainingMillis(): Long = remainingMillisAt(System.nanoTime())
    internal fun remainingMillisAt(nowNanos: Long): Long {
        val elapsed = nowNanos - startedAtNanos // Same wrap-safe subtraction as the original owned deadline.
        return if (elapsed < 0) 0 else ((durationMillis * 1_000_000 - elapsed).coerceAtLeast(0) / 1_000_000)
    }
    internal fun isExpiredAt(nowNanos: Long): Boolean = remainingMillisAt(nowNanos) == 0L
}

/** Kernel-created once; exact object identity, not merely this frozen definition, scopes the grant. */
class ProducedPlanAdmissionRequest internal constructor(
    val definition: RequiredReviewPlan,
    val planId: String,
    val planDigest: String,
    val providerKey: String,
    val originInstance: String,
) {
    val binding: ClientBinding get() = definition.binding
    val request: SubmitPlanRequest get() = definition.request
    val declaration: JvmProducedPlanDeclaration get() = requireNotNull(definition.jvmProduced)
}

@Serializable data class DeclaredArchiveIdentity(
    val slotId: String,
    val archiveKey: String,
    val declarationInputKey: String,
    val frozenInput: AnalysisInput,
    val byteCount: Long,
) { fun digest(): String = Digests.sha256(canonical(this)) }

/** Retains sources and known dependencies from acceptance. No executable review/work or output yet. */
class DeclaredPlanArchive internal constructor(
    val admission: ProducedPlanAdmissionRequest,
    val identity: DeclaredArchiveIdentity,
    /** Exact retained read projection before the initial transaction installs its final path. */
    val held: AnalysisInput,
    private val currentRead: () -> AnalysisInput,
    private val verifier: () -> Unit,
) {
    val original: AnalysisInput get() = admission.definition.slots.single { it.slotId == identity.slotId }.input
    val frozen: AnalysisInput get() = identity.frozenInput
    /** Kernel-only held→final path projection; never a mutable D/P or restored authority. */
    fun readInput(): AnalysisInput = currentRead()
    /** May perform source IO. Kernel and owner invoke ONLY outside locks. */
    fun verify() = verifier()
}

class ProducedPlanPreparationRequest internal constructor(
    val admission: ProducedPlanAdmissionRequest,
    val archives: List<DeclaredPlanArchive>,
) {
    fun archive(slotId: String): DeclaredPlanArchive = archives.single { it.identity.slotId == slotId }
}

/** Owner's proposed actual P. Kernel constructs its immutable D→P certificate before K staging. */
class PreparedProducedExecution(
    val slotId: String,
    val input: AnalysisInput,
    val bindingDigest: String,
    val producedManifest: ProducedByteManifest? = null,
    val verifyForAdmission: () -> Unit,
)

/** Complete byte-tree seal plus deterministic complete JAR; all identity fields are mandatory. */
@Serializable data class ProducedByteManifest(
    val snapshotId: String,
    val moduleId: String,
    val producerCompilationId: String,
    val consumerCompilationId: String,
    val graphDigest: String,
    val toolchainDigest: String,
    val producerArgumentLayoutDigest: String,
    val consumerArgumentLayoutDigest: String,
    val producerBindingDigest: String,
    val applicationGeneration: String,
    val applicationSequence: Int,
    val compilerRequestDigest: String,
    val compilerResultDigest: String,
    val acceptedProviderResultDigest: String,
    val completeTreeDigest: String,
    val completeTreeMembersDigest: String,
    val completeTreeMemberCount: Int,
    val completeJar: ArtifactIdentity,
    val completeJarSize: Long,
    val format: String = "complete-sorted-stored-jar-dos1980/1",
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable data class PlanExecutionCertificate(
    val planId: String,
    val planDigest: String,
    val slotId: String,
    val reviewId: String,
    val declarationDigest: String,
    val declarationInputKey: String,
    val graphDigest: String,
    val declaredArchiveDigest: String,
    val resolvedInputKey: String,
    val context: PlanContextIdentity,
    val compilerBindingDigest: String,
    val providerKey: String,
    val originInstance: String,
    val originalDeadlineIdentity: String,
    val producerReviewId: String? = null,
    val producerWorkKey: String? = null,
    val producerPublicationCertificate: String? = null,
    val producedManifest: ProducedByteManifest? = null,
    val profile: String = PRODUCED_JVM_PLAN_PROFILE,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val observedExport: ObservedExportPlanEvidence? = null,
) {
    fun digest(): String = Digests.sha256(canonical(this))
    fun workKey(): String = Digests.sha256(canonical(listOf(profile, providerKey, digest(), resolvedInputKey)))
}

@Serializable data class ResolvedPlanExecution(
    val input: AnalysisInput,
    val certificate: PlanExecutionCertificate,
) {
    val slotId: String get() = certificate.slotId
    val reviewId: String get() = certificate.reviewId
    val workKey: String get() = certificate.workKey()
}

/** Final operational fence, NOT a rule-coverage or successful producer certificate by itself. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class ProducedWorkPublication(
    val workKey: String,
    val attempt: Long,
    val originInstance: String,
    val executionCertificate: String,
    val originalInputKey: String,
    val effectiveInputKey: String,
    val providerKey: String,
    val rawResultDigest: String,
    val operationalFaults: List<String>,
    val profile: String = "ktchecker/produced-work-publication/1",
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val observedExport: ObservedExportPlanEvidence? = null,
) { fun digest(): String = Digests.sha256(canonical(this)) }

class ProducedPublicationCheck internal constructor(
    val admission: ProducedPlanAdmissionRequest,
    val execution: ResolvedPlanExecution,
    val original: AnalysisInput,
    val effective: AnalysisInput,
    val result: ProviderResult,
)

class ProducedSlotResolutionRequest internal constructor(
    val preparation: ProducedPlanPreparationRequest,
    val producer: ResolvedPlanExecution,
    val publication: ProducedWorkPublication,
    val producerResult: ProviderResult,
    val completeJarDirectory: Path,
) {
    val admission: ProducedPlanAdmissionRequest get() = preparation.admission
    val consumerArchive: DeclaredPlanArchive get() = preparation.archive(admission.declaration.consumer.slotId)
}

sealed interface ProducedSlotResolution {
    class Ready(val execution: PreparedProducedExecution) : ProducedSlotResolution
    class Unavailable(val reasons: List<String>) : ProducedSlotResolution
}

/** Final, state-only publication fence. Invalidation is sticky and never invokes owner code. */
class ProducedPlanValidity {
    private val valid = AtomicBoolean(true)
    fun isCurrent(): Boolean = valid.get()
    fun invalidate() { valid.set(false) }
}

/** Owner-selected live capability. All callable members run outside kernel/config/source locks.
 * Nothing here can be restored from a certificate, status, flag or exported graph JSON. */
class AuthorizedProducedPlan(
    val request: ProducedPlanAdmissionRequest,
    val clock: ProducedPlanClock,
    val authorityIdentity: String,
    val validity: ProducedPlanValidity,
    val verifyForAdmission: () -> Unit,
    val prepareProducer: (ProducedPlanPreparationRequest) -> PreparedProducedExecution,
    /** Exact known coverage-gap shapes may pass; unknown/failed/cancelled/substituted rows MUST fail. */
    val operationalFaults: (ProducedPublicationCheck) -> List<String>,
    val resolveConsumer: (ProducedSlotResolutionRequest) -> ProducedSlotResolution,
)

/** Enriched typed observer request. A scalar submission of an equal P cannot borrow this grant. */
class ProducedExecutionAttempt internal constructor(
    val admission: ProducedPlanAdmissionRequest,
    val preparation: ProducedPlanPreparationRequest,
    val execution: ResolvedPlanExecution,
    val original: AnalysisInput,
    val effective: AnalysisInput,
    val attempt: Long,
    val originInstance: String,
    private val verifier: () -> Unit,
) {
    val workKey: String get() = execution.workKey
    val resolutionCertificateId: String get() = execution.certificate.digest()
    fun verifyFrozen() = verifier()
}

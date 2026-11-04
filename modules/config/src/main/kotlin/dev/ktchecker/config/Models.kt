package dev.ktchecker.config

import dev.ktchecker.contracts.ClientIdentity
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.Finding
import dev.ktchecker.contracts.RuleContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import java.time.Clock
import java.util.Collections

/** A trusted registration, not a path, identifies the policy namespace. */
@Serializable
data class ConfigBinding(
    val ownerId: String,
    val workspaceId: String,
    val worktreeId: String,
    val lineageId: String,
)

@Serializable
enum class ConfigLayer {
    @SerialName("shared") SHARED,
    @SerialName("local") LOCAL,
}

@Serializable
enum class SuppressionReason {
    @SerialName("accepted_risk") ACCEPTED_RISK,
    @SerialName("reported_false_positive") REPORTED_FALSE_POSITIVE,
}

@Serializable
enum class PolicyDisposition(val wireValue: String) {
    @SerialName("open") OPEN("open"),
    @SerialName("accepted") ACCEPTED("accepted"),
    @SerialName("suppressed") SUPPRESSED("suppressed"),
    @SerialName("disabled") DISABLED("disabled"),
    @SerialName("baselined") BASELINED("baselined"),
    @SerialName("fixed") FIXED("fixed"),
    @SerialName("unavailable") UNAVAILABLE("unavailable"),
}

/**
 * Detached, verified facts. A provider's symbol name or semanticDigest is NOT a proof.
 * Only a trusted FindingIdentityVerifier may supply this when evaluating a finding.
 * materialDigest covers the relevant symbol/body/bindings; evidenceKey distinguishes
 * separate occurrences, even when their displayed messages and excerpts are equal.
 */
@Serializable
data class VerifiedFindingIdentity(
    val certificateId: String,
    val snapshotId: String,
    val ownerId: String,
    val workspaceId: String,
    val worktreeId: String,
    val lineageId: String,
    val canonicalRuleId: String,
    val providerId: String,
    val providerArtifactDigest: String,
    val symbolKey: String,
    val materialDigest: String,
    val evidenceKey: String,
    val evidenceDigest: String,
)

/** Trusted lifecycle evidence, distinct from a provider's editable disposition label. */
data class VerifiedLifecycleDisposition(
    val certificateId: String,
    val snapshotId: String,
    val policyDigest: String,
    val canonicalRuleId: String,
    val providerId: String,
    val fingerprint: String,
    val disposition: PolicyDisposition,
)

@Serializable
data class FindingAnchor(
    val binding: ConfigBinding,
    val canonicalRuleId: String,
    val ruleIdentityDigest: String,
    val providerId: String,
    val context: RuleContext,
    val severity: String,
    val findingKind: String,
    val certainty: String,
    val path: String,
    val fileDigest: String,
    val line: Int,
    val column: Int,
    val endLine: Int,
    val endColumn: Int,
    val evidenceDigest: String,
    val verifiedIdentity: VerifiedFindingIdentity? = null,
)

@Serializable
sealed class PolicySelector {
    abstract val ruleId: String

    @Serializable
    @SerialName("rule")
    data class Rule(
        override val ruleId: String,
        /** Store pins this for narrow rule scopes; null is deliberate future/broad rule acceptance. */
        val ruleIdentityDigest: String? = null,
    ) : PolicySelector()

    @Serializable
    @SerialName("finding")
    data class ExactFinding(
        override val ruleId: String,
        val fingerprint: String,
        val anchor: FindingAnchor,
    ) : PolicySelector()
}

@Serializable
sealed class SuppressionScope {
    @Serializable
    @SerialName("project")
    data object Project : SuppressionScope()

    @Serializable
    @SerialName("target")
    data class Target(val target: String) : SuppressionScope()

    @Serializable
    @SerialName("module")
    data class Module(val module: String) : SuppressionScope()

    @Serializable
    @SerialName("source_set")
    data class SourceSet(
        val module: String,
        val sourceSet: String,
        val target: String? = null,
    ) : SuppressionScope()

    /** Raw-byte bound unless a verified, particular evidence identity is attached. */
    @Serializable
    @SerialName("file")
    data class File(
        val path: String,
        val fileDigest: String,
        val verifiedIdentity: VerifiedFindingIdentity? = null,
    ) : SuppressionScope()

    @Serializable
    @SerialName("symbol")
    data class Symbol(val identity: VerifiedFindingIdentity) : SuppressionScope()

    /** 1-based lines, 0-based UTF-16 columns, end-exclusive. */
    @Serializable
    @SerialName("range")
    data class Range(
        val path: String,
        val fileDigest: String,
        val line: Int,
        val column: Int,
        val endLine: Int,
        val endColumn: Int,
        val verifiedIdentity: VerifiedFindingIdentity? = null,
    ) : SuppressionScope()
}

@Serializable
data class SuppressionSpec(
    val id: String,
    val selector: PolicySelector,
    val scope: SuppressionScope,
    val reason: SuppressionReason,
    val justification: String,
    val expiresAtEpochMillis: Long? = null,
)

/** Baseline is separately accounted, and is never a proof that code was fixed. */
@Serializable
data class BaselineSpec(
    val id: String,
    val selector: PolicySelector.ExactFinding,
    val justification: String,
    val expiresAtEpochMillis: Long? = null,
)

@Serializable
data class SuppressionRecord(
    val spec: SuppressionSpec,
    val createdAtEpochMillis: Long,
    val createdBy: ClientIdentity,
)

@Serializable
data class BaselineRecord(
    val spec: BaselineSpec,
    val createdAtEpochMillis: Long,
    val createdBy: ClientIdentity,
)

@Serializable
data class RuleSetting(val ruleId: String, val enabled: Boolean)

/** Portable, explicitly imported project policy. Local overrides/credentials are never exported. */
@Serializable
data class SharedProjectConfig(
    val schemaVersion: Int = 1,
    val ruleSettings: List<RuleSetting> = emptyList(),
    val suppressions: List<SuppressionSpec> = emptyList(),
    val baselines: List<BaselineSpec> = emptyList(),
)

@Serializable
data class LayerRevision(
    val revision: Long,
    val generationId: String?,
    /** Exact on-disk bytes; a same-revision manual edit is still a conflict. */
    val documentDigest: String,
)

@Serializable
data class ConfigRevisionToken(
    val binding: ConfigBinding,
    val shared: LayerRevision,
    val local: LayerRevision,
    /** Includes registry identity, full contents and expiration activity. */
    val policyDigest: String,
)

@Serializable
sealed class ConfigAction {
    @Serializable
    @SerialName("add_suppression")
    data class AddSuppression(val spec: SuppressionSpec) : ConfigAction()

    @Serializable
    @SerialName("remove_suppression")
    data class RemoveSuppression(val id: String) : ConfigAction()

    /** Explicitly hide an inherited shared record without changing the shared file. */
    @Serializable
    @SerialName("mask_shared_suppression")
    data class MaskSharedSuppression(val id: String) : ConfigAction()

    @Serializable
    @SerialName("unmask_shared_suppression")
    data class UnmaskSharedSuppression(val id: String) : ConfigAction()

    @Serializable
    @SerialName("set_rule_enabled")
    data class SetRuleEnabled(val ruleId: String, val enabled: Boolean) : ConfigAction()

    @Serializable
    @SerialName("reset_rule_override")
    data class ResetRuleOverride(val ruleId: String) : ConfigAction()

    @Serializable
    @SerialName("add_baseline")
    data class AddBaseline(val spec: BaselineSpec) : ConfigAction()

    @Serializable
    @SerialName("remove_baseline")
    data class RemoveBaseline(val id: String) : ConfigAction()

    @Serializable
    @SerialName("import_shared_config")
    data class ImportSharedConfig(val config: SharedProjectConfig) : ConfigAction()
}

/** Untrusted request data cannot assert an owner role or grant authorization. */
@Serializable
data class ConfigMutationRequest(
    val binding: ConfigBinding,
    val layer: ConfigLayer,
    val expected: ConfigRevisionToken,
    val action: ConfigAction,
    val requestId: String,
) {
    fun digest(): String = Digests.sha256(Digests.canonical(ContractJson.encodeToJsonElement(this)))
}

@Serializable
data class ConfigAuditEvent(
    val revision: Long,
    val requestId: String,
    val requestDigest: String,
    val actor: ClientIdentity,
    val appliedAtEpochMillis: Long,
    val previousEventDigest: String?,
)

@Serializable
internal data class PolicyDocument(
    val schemaVersion: Int = 1,
    val layer: ConfigLayer,
    val ownerId: String,
    val workspaceId: String,
    val worktreeId: String? = null,
    val lineageId: String? = null,
    val revision: Long,
    val generationId: String,
    val ruleSettings: List<RuleSetting> = emptyList(),
    val suppressions: List<SuppressionRecord> = emptyList(),
    val suppressionMasks: List<String> = emptyList(),
    val baselines: List<BaselineRecord> = emptyList(),
    val auditEvents: List<ConfigAuditEvent> = emptyList(),
)

@Serializable
data class LayerInspection(
    val layer: ConfigLayer,
    val revision: LayerRevision,
    val ruleSettings: List<RuleSetting>,
    val suppressions: List<SuppressionRecord>,
    val suppressionMasks: List<String>,
    val baselines: List<BaselineRecord>,
    val auditEvents: List<ConfigAuditEvent>,
)

@Serializable
data class ResolvedSuppression(
    val record: SuppressionRecord,
    val layer: ConfigLayer,
    val activity: String,
)

@Serializable
data class ResolvedBaseline(val record: BaselineRecord, val layer: ConfigLayer, val active: Boolean)

/** Store beside the review in trusted durable state; never accept as model authorization. */
@Serializable
data class FrozenConfig(
    val schemaVersion: Int = 1,
    val binding: ConfigBinding,
    val revisionToken: ConfigRevisionToken,
    val registryDigest: String,
    val capturedAtEpochMillis: Long,
    val sharedDocument: String?,
    val localDocument: String?,
)

/** Created only by the store. Its effective policy is frozen for an active review. */
class ConfigSnapshot internal constructor(
    val binding: ConfigBinding,
    val revisionToken: ConfigRevisionToken,
    val shared: LayerInspection,
    val local: LayerInspection,
    ruleSettings: Map<String, RuleSetting>,
    suppressions: List<ResolvedSuppression>,
    baselines: List<ResolvedBaseline>,
    val capturedAtEpochMillis: Long,
    val validUntilEpochMillis: Long?,
    internal val registryDigest: String,
    internal val sharedDocument: String?,
    internal val localDocument: String?,
) {
    val policyDigest: String get() = revisionToken.policyDigest
    val ruleSettings: Map<String, RuleSetting> = Collections.unmodifiableMap(ruleSettings.toMap())
    val suppressions: List<ResolvedSuppression> = immutableList(suppressions)
    val baselines: List<ResolvedBaseline> = immutableList(baselines)
}

class ConfigFenceCheck internal constructor(
    val current: ConfigSnapshot,
    private val identityMatches: Boolean,
    private val identityReasons: List<String>,
    private val clock: Clock,
) {
    /** Read this inside the innermost final-gate callback, not before taking source locks. */
    val matches: Boolean get() = identityMatches && timeCurrent()
    val reasons: List<String> get() = if (timeCurrent()) identityReasons else immutableList(identityReasons + "policy_expiration_or_clock_drift")
    private fun timeCurrent(): Boolean {
        val now = clock.millis()
        return now >= current.capturedAtEpochMillis && (current.validUntilEpochMillis?.let { now < it } ?: true)
    }
}

@Serializable
data class MatchAttempt(val id: String, val layer: ConfigLayer, val state: String)

@Serializable
data class SuppressionExplanation(
    val finding: Finding,
    val canonicalRuleId: String?,
    val fingerprint: String?,
    val disposition: PolicyDisposition,
    val explanation: String,
    val policyEntryId: String? = null,
    val layer: ConfigLayer? = null,
    val candidates: List<MatchAttempt> = emptyList(),
) {
    fun classifiedFinding(): Finding = finding.copy(disposition = disposition.wireValue)
}

open class ConfigException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class ConfigValidationException(message: String) : ConfigException(message)
class ConfigAuthorizationException(message: String) : ConfigException(message)
class ConfigConflictException(val current: ConfigRevisionToken) : ConfigException("Configuration revision conflict; inspect again.")
class ConfigReadException(message: String, cause: Throwable? = null) : ConfigException(message, cause)
/** If possiblyApplied, inspect rather than blindly retrying an old request. */
class ConfigWriteException(message: String, val possiblyApplied: Boolean, cause: Throwable? = null) : ConfigException(message, cause)

internal fun <T> immutableList(items: Collection<T>): List<T> = Collections.unmodifiableList(items.toList())

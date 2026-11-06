package dev.ktchecker.config

import dev.ktchecker.contracts.AnalysisInput
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Finding
import dev.ktchecker.contracts.ProviderResult
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * A checked, frozen pair of policies for a view of the ORIGINAL analysis input.
 * Obtain this through [ConfigStore.prepareReassessment]. Getter values are fresh
 * detached copies: mutating their nested collections cannot alter this subject.
 *
 * This proves only configuration compatibility, not execution/raw-result provenance,
 * owner authorization, activation/profile equivalence, source or policy currentness,
 * or completion. Trusted kernel/Workbench admission must establish those separately.
 * "Current" names the requested captured policy, not a claim about the live store.
 * Identical captures are allowed for a pure historical/no-op view.
 */
class PolicyReassessmentSubject private constructor(
    private val inputDocument: String,
    private val originalConfigDocument: String,
    private val currentConfigDocument: String,
    private val originalPolicy: ConfigSnapshot,
    private val currentPolicy: ConfigSnapshot,
) {
    /** Includes the ORIGINAL configuration digest, paths and execution settings. */
    val originalInput: AnalysisInput get() = ContractJson.decodeFromString(inputDocument)
    val originalConfig: FrozenConfig get() = ContractJson.decodeFromString(originalConfigDocument)
    val currentConfig: FrozenConfig get() = ContractJson.decodeFromString(currentConfigDocument)

    internal fun explain(matcher: PolicyMatcher, registryDigest: String, finding: Finding): SuppressionExplanation {
        requireRegistry(registryDigest)
        return matcher.explainReassessment(originalPolicy, currentPolicy, originalInput, encodedCopy(finding).second)
    }

    internal fun apply(matcher: PolicyMatcher, registryDigest: String, result: ProviderResult): ProviderResult {
        // Check even an empty result; moving a subject to another registry is not a no-op.
        requireRegistry(registryDigest)
        val input = originalInput
        val detached = encodedCopy(result).second
        return detached.copy(findings = immutableList(detached.findings.map {
            matcher.explainReassessment(originalPolicy, currentPolicy, input, it).classifiedFinding()
        }))
    }

    private fun requireRegistry(registryDigest: String) {
        if (originalPolicy.registryDigest != registryDigest || currentPolicy.registryDigest != registryDigest) {
            throw ConfigValidationException("Reassessment subject belongs to another rule registry.")
        }
    }

    companion object {
        internal fun create(
            store: ConfigStore,
            originalInput: AnalysisInput,
            originalConfig: FrozenConfig,
            currentConfig: FrozenConfig,
        ): PolicyReassessmentSubject {
            // Freeze before checking; retain no caller-owned collection-bearing objects.
            val (inputDocument, input) = encodedCopy(originalInput)
            val (originalDocument, original) = encodedCopy(originalConfig)
            val (currentDocument, current) = encodedCopy(currentConfig)
            // Each exact document/token is restored at ITS capture time, never at wall time.
            val originalPolicy = store.restoreSnapshot(original, original.revisionToken)
            val currentPolicy = store.restoreSnapshot(current, current.revisionToken)
            if (originalPolicy.binding != currentPolicy.binding || originalPolicy.registryDigest != currentPolicy.registryDigest) {
                throw ConfigValidationException("Reassessment requires the same full configuration binding and rule registry.")
            }
            if (!inputMatchesPolicy(originalPolicy, input)) {
                throw ConfigValidationException("Original analysis input does not match its original captured policy.")
            }
            // Full effective settings, including unrelated/unselected rules and explicit
            // true overrides, must agree. Disabling/enabling is not policy-only reassessment.
            if (originalPolicy.ruleSettings != currentPolicy.ruleSettings) {
                throw ConfigValidationException("Reassessment cannot change effective rule settings.")
            }
            return PolicyReassessmentSubject(inputDocument, originalDocument, currentDocument, originalPolicy, currentPolicy)
        }
    }
}

private inline fun <reified T> encodedCopy(value: T): Pair<String, T> = try {
    val document = ContractJson.encodeToString(value)
    document to ContractJson.decodeFromString<T>(document)
} catch (e: Exception) {
    throw ConfigValidationException("Reassessment data could not be detached: ${e.javaClass.simpleName}")
}

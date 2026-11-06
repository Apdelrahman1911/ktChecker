package dev.ktchecker.kernel

import dev.ktchecker.contracts.AnalysisInput
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import kotlinx.serialization.Serializable

/**
 * Versioned contents of the existing schema2 policy_certificate TEXT, not a schema migration.
 * Admission captures the immutable evaluator identity before any worker can publish. Evaluation
 * replaces only that exact admission with its terminal certificate. Legacy null/plain hashes and
 * malformed/unknown formats remain untouched and cannot acquire the current evaluator's authority.
 * The kernel disposition guard is also versioned in the evaluator identity. Revision 2 refuses
 * pre-guard permissions without rewriting their history; fresh reviews may reuse the retained raw
 * result only through the current guard. This is not safe mixed-version operation: stop old binaries.
 */
@Serializable
internal data class PolicyCertificate(
    val evaluatorIdentity: String,
    val state: String,
    val digest: String? = null,
    val format: String = FORMAT,
) {
    companion object {
        private const val FORMAT = "ktchecker/result-policy-certificate/1"
        const val ADMITTED = "admitted"
        const val EVALUATED = "evaluated"

        fun decode(encoded: String?): PolicyCertificate? {
            if (encoded == null || encoded.length > 1024) return null
            return try {
                val value = ContractJson.decodeFromString<PolicyCertificate>(encoded)
                if (value.format != FORMAT || !Digests.valid(value.evaluatorIdentity) ||
                    when (value.state) {
                        ADMITTED -> value.digest != null
                        EVALUATED -> value.digest == null || !Digests.valid(value.digest)
                        else -> true
                    } || canonical(value) != encoded) null else value
            } catch (_: Exception) { null }
        }

        fun effectiveDigest(identity: String, binding: ClientBinding, input: AnalysisInput,
                            rawDigest: String, effectiveDigest: String): String =
            Digests.sha256(canonical(listOf(FORMAT, identity, canonical(binding), input.analysisKey(),
                input.context.configurationDigest.orEmpty(), rawDigest, effectiveDigest)))
    }
}

internal fun capturePolicyEvaluatorIdentity(policy: ResultPolicy): String {
    val declared = policy.evaluatorIdentity
    return if (declared == null) Digests.sha256(canonical(listOf("instance-result-policy/2", randomToken())))
    else {
        identifier(declared, "result policy evaluator identity")
        Digests.sha256(canonical(listOf("declared-result-policy/2", declared)))
    }
}

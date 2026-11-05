package dev.ktchecker.providers.buildcontracts

import dev.ktchecker.contracts.*
import java.util.concurrent.CancellationException

/** Useful exact-consumer snapshot observation, not wrapper execution or a clean coverage certificate. */
class GradleWrapperChecksumProvider(
    private val admission: WrapperContextAdmission = WrapperContextAdmission.Unavailable(
        WrapperUnavailableReason.SELECTED_CONSUMER_ATTESTATION_UNAVAILABLE),
) : AnalysisProvider {
    override val id = BUILD_CONTRACTS_PROVIDER_ID
    override val version = BUILD_CONTRACTS_PROVIDER_VERSION
    override val supportedRuleIds: Set<String> = setOf(WRAPPER_CHECKSUM_FORMAT)

    override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
        try {
            wrapperInputBounds(input)
        } catch (_: WrapperContractProblem) {
            return ProviderResult(id, "partial", reasons = wrapperList(listOf(
                WrapperUnavailableReason.ANALYSIS_ENVELOPE_BOUND_EXCEEDED.code)), providerVersion = version)
        }
        val frozen = freezeWrapperInput(input)
        val requested = frozen.requiredRuleIds.distinct()
        fun unavailable(reason: String, state: String = "unavailable"): ProviderResult = ProviderResult(id,
            if (state == "cancelled") "cancelled" else "partial",
            coverage = wrapperList(requested.map { rule -> Coverage(rule, frozen.context.scope,
                if (rule == WRAPPER_CHECKSUM_FORMAT || state == "cancelled") state else "unsupported",
                if (rule == WRAPPER_CHECKSUM_FORMAT || state == "cancelled") reason else "build_contract_rule_not_supported",
                depth = "syntax") }), reasons = wrapperList(listOf(reason)), providerVersion = version)
        if (cancellation.isCancelled()) return unavailable(WrapperUnavailableReason.CANCELLED.code, "cancelled")
        if (requested.isEmpty()) return unavailable("wrapper_no_explicit_rules_requested")
        if (requested.size != frozen.requiredRuleIds.size) return unavailable("wrapper_rule_selection_duplicate")
        if (WRAPPER_CHECKSUM_FORMAT !in requested) return unavailable("build_contract_requested_rules_not_supported", "unsupported")
        if (admission is WrapperContextAdmission.Unavailable) {
            return unavailable(admission.reason.code, if (admission.reason == WrapperUnavailableReason.CANCELLED) "cancelled" else "unavailable")
        }
        return try {
            val installed = (admission as WrapperContextAdmission.Installed).context
            installed.verify(frozen, cancellation)
            val request = installed.request
            val checksum = installed.properties.checksum
            val policy = request.policy
            val suppressed = policy.suppressions.any { it.ruleId == WRAPPER_CHECKSUM_FORMAT &&
                it.propertiesPath == request.propertiesFile.path && it.context == frozen.context.scope }
            val reason: String
            val state: String
            val findings: List<Finding>
            when {
                !policy.enabled -> { reason = "wrapper_rule_disabled_by_owner"; state = "disabled"; findings = emptyList() }
                suppressed -> { reason = "wrapper_exact_id_suppressed_by_owner"; state = "suppressed"; findings = emptyList() }
                checksum == null -> { reason = "wrapper_checksum_absent_optional_policy_separate"; state = "not_applicable"; findings = emptyList() }
                installed.pathReceipt.path == WrapperVerificationPath.CACHED_WITHOUT_COMPARISON -> {
                    reason = "wrapper_cached_path_without_verification"; state = "not_applicable"; findings = emptyList()
                }
                WrapperConsumerProfile.acceptsChecksumShape(checksum) -> {
                    reason = "wrapper_checksum_shape_accepted_integrity_unverified"; state = "unqualified"; findings = emptyList()
                }
                else -> {
                    reason = "wrapper_checksum_format_observed_unqualified"; state = "findings"
                    findings = listOf(finding(installed))
                }
            }
            cancellation.throwIfCancelled()
            ProviderResult(id, "partial", findings = wrapperList(findings), coverage = wrapperList(requested.map { rule ->
                if (rule != WRAPPER_CHECKSUM_FORMAT) Coverage(rule, frozen.context.scope, "unsupported",
                    "build_contract_rule_not_supported", depth = "syntax")
                else Coverage(rule, frozen.context.scope, state, reason, depth = "syntax", provenance = "not_executed",
                    certificateId = null, implemented = false, tested = false, qualified = false)
            }), reasons = wrapperList(listOf(reason)), providerVersion = version)
        } catch (_: CancellationException) {
            unavailable(WrapperUnavailableReason.CANCELLED.code, "cancelled")
        } catch (problem: WrapperContractProblem) {
            unavailable(problem.reason.code)
        } catch (_: Exception) {
            unavailable(WrapperUnavailableReason.OWNER_ATTESTATION_FAILED.code)
        }
    }

    private fun finding(installed: InstalledWrapperContext): Finding {
        val request = installed.request
        val file = request.propertiesFile
        val input = request.input
        val fingerprint = Digests.sha256(listOf(WRAPPER_CHECKSUM_FORMAT, input.snapshot.snapshotId, input.context.scope.id,
            file.path, file.digest, request.wrapperJarFile.digest, installed.pathReceipt.evidenceDigest,
            installed.policyReceipt.evidenceDigest).joinToString("\u0000"))
        return Finding(WRAPPER_CHECKSUM_FORMAT, "Declared wrapper checksum cannot match the selected consumer",
            "The parsed distributionSha256Sum declaration cannot equal the lowercase 64-ASCII-hex SHA-256 value compared by the exact selected wrapper.",
            "The owner attested a fresh/unverified selected path requiring checksum comparison. This is a format contradiction, not a claim that the wrapper ran or that a distribution's integrity was checked.",
            SourceLocation(file.path, file.digest), fingerprint, id, input.context.scope,
            severity = "error", kind = "probable_defect", certainty = "high", semanticDigest = request.digest,
            evidence = wrapperList(listOf(
                Evidence("immutable-wrapper-properties", "Exact bounded Properties.load(InputStream) semantics on hash-verified copied snapshot bytes; file-level location only.", file.path, file.digest),
                Evidence("selected-wrapper-consumer", "Exact inspected local consumer artifact; no version inferred from URL or artifact name.", request.wrapperJarFile.path, request.wrapperJarFile.digest),
                Evidence("owner-selected-wrapper", "Independent owner receipt binds the actual selected consumer, version provenance and exact context.", digest = installed.selectedReceipt.evidenceDigest),
                Evidence("owner-verification-path", "Independent owner receipt requires comparison on the selected fresh/unverified path; no cached-path failure is inferred.", digest = installed.pathReceipt.evidenceDigest),
                Evidence("owner-exact-rule-policy", "The entire exact-ID enable/suppression policy was independently admitted.", digest = installed.policyReceipt.evidenceDigest),
                Evidence("scope-limit", "Source-only unqualified observation. No wrapper execution, download, archive integrity check or cross-platform release qualification."),
            )), fixHint = "Use the independently verified lowercase SHA-256 for the exact trusted distribution; do not remove the security declaration or infer integrity from its shape.",
            verification = wrapperList(listOf(Verification(expected = "After repair, use only an owner-approved controlled fixture with the same pinned consumer and verification path; no automatic download."))))
    }
}

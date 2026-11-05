package dev.ktchecker.providers.buildcontracts

import dev.ktchecker.contracts.*
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Nonserialized, private-constructor, single-use owner capability. Mandatory callbacks must verify
 * independent registered immutable/fenced receipts, not return approval by echoing project JSON.
 * Reads are from the registered immutable snapshot, never a mutable materialized directory.
 * This cooperative owner API does not sandbox hostile code running with the same UID.
 */
class InstalledWrapperContext private constructor(
    internal val request: WrapperInstallationRequest,
    internal val properties: ParsedWrapperProperties,
    internal val selectedReceipt: OwnerSelectedWrapperReceipt,
    internal val pathReceipt: OwnerWrapperPathReceipt,
    internal val policyReceipt: OwnerWrapperPolicyReceipt,
    private val verifySnapshot: (AnalysisInput) -> Unit,
    private val verifySelectedWrapper: (WrapperInstallationRequest) -> OwnerSelectedWrapperReceipt?,
    private val verifyVerificationPath: (WrapperInstallationRequest) -> OwnerWrapperPathReceipt?,
    private val verifyRulePolicy: (WrapperInstallationRequest) -> OwnerWrapperPolicyReceipt?,
) {
    private val consumed = AtomicBoolean()
    private val inputDigest = request.input.analysisKey()

    internal fun verify(input: AnalysisInput, cancellation: CancellationToken) {
        cancellation.throwIfCancelled()
        requireWrapper(input.analysisKey() == inputDigest, WrapperUnavailableReason.INPUT_BINDING_MISMATCH)
        requireWrapper(consumed.compareAndSet(false, true), WrapperUnavailableReason.CONTEXT_REPLAY)
        wrapperOwnerCheck(WrapperUnavailableReason.SNAPSHOT_ATTESTATION_UNAVAILABLE, cancellation) { verifySnapshot(request.input) }
        val selected = wrapperOwnerCheck(WrapperUnavailableReason.SELECTED_CONSUMER_ATTESTATION_UNAVAILABLE, cancellation) {
            verifySelectedWrapper(request)
        }
        requireWrapper(selected == selectedReceipt, WrapperUnavailableReason.SELECTED_CONSUMER_ATTESTATION_UNAVAILABLE)
        val path = wrapperOwnerCheck(WrapperUnavailableReason.VERIFICATION_PATH_ATTESTATION_UNAVAILABLE, cancellation) {
            verifyVerificationPath(request)
        }
        requireWrapper(path == pathReceipt, WrapperUnavailableReason.VERIFICATION_PATH_ATTESTATION_CHANGED)
        val policy = wrapperOwnerCheck(WrapperUnavailableReason.POLICY_ATTESTATION_UNAVAILABLE, cancellation) {
            verifyRulePolicy(request)
        }
        requireWrapper(policy == policyReceipt, WrapperUnavailableReason.POLICY_ATTESTATION_UNAVAILABLE)
    }

    companion object {
        /** No Boolean/version/parser/cache fields from AnalysisInput can supply any callback. */
        fun install(
            input: AnalysisInput,
            selection: OwnerWrapperSelection,
            policy: WrapperRulePolicy,
            verifySnapshot: (AnalysisInput) -> Unit,
            verifySelectedWrapper: (WrapperInstallationRequest) -> OwnerSelectedWrapperReceipt?,
            verifyVerificationPath: (WrapperInstallationRequest) -> OwnerWrapperPathReceipt?,
            verifyRulePolicy: (WrapperInstallationRequest) -> OwnerWrapperPolicyReceipt?,
            readSnapshotBytes: (SnapshotFile) -> ByteArray,
            cancellation: CancellationToken = CancellationToken.NONE,
        ): WrapperContextAdmission = try {
            cancellation.throwIfCancelled()
            val frozen = freezeWrapperInput(input)
            val actualPolicy = freezeWrapperPolicy(policy)
            requireWrapper(frozen.snapshot.ownerId.isNotBlank() && frozen.snapshot.workspaceId.isNotBlank() &&
                frozen.snapshot.worktreeId.isNotBlank() && Digests.valid(frozen.snapshot.snapshotId) &&
                frozen.snapshot.captureKind in setOf("immutable_overlay", "coordinated_capture") &&
                frozen.snapshot.unknownInputs.isEmpty() && frozen.context.fullProject &&
                listOf(frozen.context.modelDigest, frozen.context.dependencyDigest, frozen.context.toolchainDigest,
                    frozen.context.configurationDigest).all { it != null && Digests.valid(it) } &&
                with(frozen.context.scope) { listOf(target, sourceSet, module, compilation).all { it.isNotBlank() } },
                WrapperUnavailableReason.VERIFIED_CONTEXT_UNAVAILABLE)
            requireWrapper(wrapperRelativePath(selection.propertiesPath) && wrapperRelativePath(selection.wrapperJarPath) &&
                selection.propertiesPath != selection.wrapperJarPath &&
                frozen.snapshot.files.map { it.path }.distinct().size == frozen.snapshot.files.size,
                WrapperUnavailableReason.SNAPSHOT_FILE_SELECTION_UNAVAILABLE)
            val propertiesFile = frozen.snapshot.files.singleOrNull { it.path == selection.propertiesPath }
                ?: throw WrapperContractProblem(WrapperUnavailableReason.SNAPSHOT_FILE_SELECTION_UNAVAILABLE)
            val jarFile = frozen.snapshot.files.singleOrNull { it.path == selection.wrapperJarPath }
                ?: throw WrapperContractProblem(WrapperUnavailableReason.SNAPSHOT_FILE_SELECTION_UNAVAILABLE)
            requireWrapper(Digests.valid(propertiesFile.digest) && propertiesFile.size in 0..MAX_PROPERTIES_BYTES.toLong() &&
                Digests.valid(jarFile.digest) && jarFile.size in 1..MAX_WRAPPER_JAR_BYTES.toLong(),
                WrapperUnavailableReason.SNAPSHOT_FILE_SELECTION_UNAVAILABLE)
            wrapperOwnerCheck(WrapperUnavailableReason.SNAPSHOT_ATTESTATION_UNAVAILABLE, cancellation) { verifySnapshot(frozen) }
            fun bytes(file: SnapshotFile, maximum: Int): ByteArray {
                cancellation.throwIfCancelled()
                val supplied = wrapperOwnerCheck(WrapperUnavailableReason.SNAPSHOT_BYTES_UNAVAILABLE, cancellation) {
                    readSnapshotBytes(file)
                }
                requireWrapper(supplied.size <= maximum && supplied.size.toLong() == file.size,
                    WrapperUnavailableReason.SNAPSHOT_CONTENT_MISMATCH)
                val captured = supplied.copyOf()
                requireWrapper(Digests.sha256(captured) == file.digest, WrapperUnavailableReason.SNAPSHOT_CONTENT_MISMATCH)
                return captured
            }
            val propertiesBytes = bytes(propertiesFile, MAX_PROPERTIES_BYTES)
            val jarBytes = bytes(jarFile, MAX_WRAPPER_JAR_BYTES)
            cancellation.throwIfCancelled()
            WrapperConsumerProfile.verify(jarBytes)
            val parsed = parseWrapperProperties(propertiesBytes)
            val request = WrapperInstallationRequest(frozen, selection.copy(), propertiesFile, jarFile, actualPolicy,
                parsed.distributionUrlDigest)
            cancellation.throwIfCancelled()
            val selected = wrapperOwnerCheck(WrapperUnavailableReason.SELECTED_CONSUMER_ATTESTATION_UNAVAILABLE, cancellation) {
                verifySelectedWrapper(request)
            }
                ?: throw WrapperContractProblem(WrapperUnavailableReason.SELECTED_CONSUMER_ATTESTATION_UNAVAILABLE)
            cancellation.throwIfCancelled()
            requireWrapper(selected.requestDigest == request.digest && Digests.valid(selected.evidenceDigest),
                WrapperUnavailableReason.SELECTED_CONSUMER_ATTESTATION_UNAVAILABLE)
            requireWrapper(selected.wrapperVersion == SELECTED_WRAPPER_VERSION &&
                selected.distributionVersion == SELECTED_WRAPPER_VERSION, WrapperUnavailableReason.SELECTED_VERSION_UNSUPPORTED)
            requireWrapper(selected.platform != WrapperBuildPlatform.UNKNOWN, WrapperUnavailableReason.PLATFORM_ATTESTATION_UNAVAILABLE)
            val path = wrapperOwnerCheck(WrapperUnavailableReason.VERIFICATION_PATH_ATTESTATION_UNAVAILABLE, cancellation) {
                verifyVerificationPath(request)
            }
                ?: throw WrapperContractProblem(WrapperUnavailableReason.VERIFICATION_PATH_ATTESTATION_UNAVAILABLE)
            cancellation.throwIfCancelled()
            requireWrapper(path.requestDigest == request.digest && Digests.valid(path.evidenceDigest) &&
                path.path != WrapperVerificationPath.UNKNOWN, WrapperUnavailableReason.VERIFICATION_PATH_ATTESTATION_UNAVAILABLE)
            val policyReceipt = wrapperOwnerCheck(WrapperUnavailableReason.POLICY_ATTESTATION_UNAVAILABLE, cancellation) {
                verifyRulePolicy(request)
            }
                ?: throw WrapperContractProblem(WrapperUnavailableReason.POLICY_ATTESTATION_UNAVAILABLE)
            cancellation.throwIfCancelled()
            requireWrapper(policyReceipt.requestDigest == request.digest && Digests.valid(policyReceipt.evidenceDigest),
                WrapperUnavailableReason.POLICY_ATTESTATION_UNAVAILABLE)
            WrapperContextAdmission.Installed(InstalledWrapperContext(request, parsed, selected, path, policyReceipt,
                verifySnapshot, verifySelectedWrapper, verifyVerificationPath, verifyRulePolicy))
        } catch (_: CancellationException) {
            WrapperContextAdmission.Unavailable(WrapperUnavailableReason.CANCELLED)
        } catch (problem: WrapperContractProblem) {
            WrapperContextAdmission.Unavailable(problem.reason)
        } catch (_: Exception) {
            // Never expose arbitrary exception/URI/log/source text.
            WrapperContextAdmission.Unavailable(WrapperUnavailableReason.OWNER_ATTESTATION_FAILED)
        }
    }
}

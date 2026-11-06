package dev.ktchecker.providers.buildcontracts

import dev.ktchecker.contracts.*
import kotlinx.serialization.json.*
import java.util.Collections
import java.util.concurrent.CancellationException

const val BUILD_CONTRACTS_PROVIDER_ID = "gradle-build-contracts"
const val BUILD_CONTRACTS_PROVIDER_VERSION = "wrapper-checksum-format/1"
const val WRAPPER_CHECKSUM_FORMAT = "ktc.gradle.wrapper_declared_checksum_format"
const val SELECTED_WRAPPER_VERSION = "9.6.1"

internal const val MAX_PROPERTIES_BYTES = 65_536
internal const val MAX_WRAPPER_JAR_BYTES = 1_048_576
internal const val WRAPPER_ACTIVATION_POLICY_KEY = "ktchecker.activation-plan"
internal const val MAX_WRAPPER_ACTIVATION_POLICY_UTF8_BYTES = 1_048_576

/** Selection data only. Paths, names and any serialized copy are not selection authority. */
data class OwnerWrapperSelection(val propertiesPath: String, val wrapperJarPath: String)

/** Exact owner policy, not a project comment or a suppress-all family switch. */
data class WrapperRuleSuppression(
    val ruleId: String,
    val propertiesPath: String,
    val context: RuleContext,
    val justification: String,
)

data class WrapperRulePolicy(
    val ruleId: String = WRAPPER_CHECKSUM_FORMAT,
    val enabled: Boolean = true,
    val suppressions: List<WrapperRuleSuppression> = emptyList(),
)

enum class WrapperBuildPlatform { COMMON, ANDROID, IOS, DESKTOP, JVM, UNKNOWN }

enum class WrapperVerificationPath {
    REQUIRED_FRESH_OR_UNVERIFIED,
    CACHED_WITHOUT_COMPARISON,
    UNKNOWN,
}

/** These nonserialized receipts must come from independent registered owner evidence. */
data class OwnerSelectedWrapperReceipt(
    val requestDigest: String,
    val evidenceDigest: String,
    val wrapperVersion: String,
    val distributionVersion: String,
    val platform: WrapperBuildPlatform,
)

data class OwnerWrapperPathReceipt(
    val requestDigest: String,
    val evidenceDigest: String,
    val path: WrapperVerificationPath,
)

data class OwnerWrapperPolicyReceipt(val requestDigest: String, val evidenceDigest: String)

/** No raw URI, declaration value, excerpt or exception is exposed by this request. */
class WrapperInstallationRequest internal constructor(
    val input: AnalysisInput,
    val selection: OwnerWrapperSelection,
    val propertiesFile: SnapshotFile,
    val wrapperJarFile: SnapshotFile,
    val policy: WrapperRulePolicy,
    val distributionUrlDigest: String,
) {
    val consumerProfileId: String = WrapperConsumerProfile.PROFILE_ID
    val digest: String = Digests.sha256("ktchecker/wrapper-installation/1\u0000" +
        Digests.canonical(buildJsonObject {
            put("inputDigest", input.analysisKey())
            put("propertiesPath", selection.propertiesPath)
            put("wrapperJarPath", selection.wrapperJarPath)
            put("propertiesDigest", propertiesFile.digest)
            put("wrapperJarDigest", wrapperJarFile.digest)
            put("distributionUrlDigest", distributionUrlDigest)
            put("consumerProfileId", consumerProfileId)
            put("policy", buildJsonObject {
                put("ruleId", policy.ruleId)
                put("enabled", policy.enabled)
                put("suppressions", buildJsonArray {
                    policy.suppressions.forEach { suppression ->
                        add(buildJsonObject {
                            put("ruleId", suppression.ruleId)
                            put("propertiesPath", suppression.propertiesPath)
                            put("context", ContractJson.encodeToJsonElement(suppression.context))
                            put("justification", suppression.justification)
                        })
                    }
                })
            })
        }))
}

enum class WrapperUnavailableReason(val code: String) {
    SELECTED_CONSUMER_ATTESTATION_UNAVAILABLE("wrapper_selected_consumer_attestation_unavailable"),
    ANALYSIS_ENVELOPE_BOUND_EXCEEDED("wrapper_analysis_envelope_bound_exceeded"),
    VERIFIED_CONTEXT_UNAVAILABLE("wrapper_verified_owner_context_unavailable"),
    SNAPSHOT_ATTESTATION_UNAVAILABLE("wrapper_snapshot_attestation_unavailable"),
    SNAPSHOT_FILE_SELECTION_UNAVAILABLE("wrapper_snapshot_file_selection_unavailable"),
    SNAPSHOT_BYTES_UNAVAILABLE("wrapper_snapshot_bytes_unavailable"),
    SNAPSHOT_CONTENT_MISMATCH("wrapper_snapshot_content_mismatch"),
    CONSUMER_PROFILE_UNAVAILABLE("wrapper_consumer_profile_unavailable"),
    PROPERTIES_PARSER_UNAVAILABLE("wrapper_properties_parser_unavailable"),
    DISTRIBUTION_SELECTION_UNAVAILABLE("wrapper_distribution_selection_unavailable"),
    SELECTED_VERSION_UNSUPPORTED("wrapper_selected_version_unsupported"),
    PLATFORM_ATTESTATION_UNAVAILABLE("wrapper_platform_attestation_unavailable"),
    VERIFICATION_PATH_ATTESTATION_UNAVAILABLE("wrapper_verification_path_attestation_unavailable"),
    VERIFICATION_PATH_ATTESTATION_CHANGED("wrapper_verification_path_attestation_changed"),
    POLICY_ATTESTATION_UNAVAILABLE("wrapper_policy_attestation_unavailable"),
    POLICY_UNSUPPORTED("wrapper_exact_rule_policy_unsupported"),
    INPUT_BINDING_MISMATCH("wrapper_installed_input_binding_mismatch"),
    CONTEXT_REPLAY("wrapper_installed_context_replay"),
    CANCELLED("wrapper_analysis_cancelled"),
    OWNER_ATTESTATION_FAILED("wrapper_owner_attestation_verification_failed"),
}

sealed interface WrapperContextAdmission {
    class Installed internal constructor(internal val context: InstalledWrapperContext) : WrapperContextAdmission
    data class Unavailable(val reason: WrapperUnavailableReason) : WrapperContextAdmission
}

internal class WrapperContractProblem(val reason: WrapperUnavailableReason) : IllegalArgumentException(reason.code)

internal fun requireWrapper(valid: Boolean, reason: WrapperUnavailableReason) {
    if (!valid) throw WrapperContractProblem(reason)
}

internal inline fun <T> wrapperOwnerCheck(reason: WrapperUnavailableReason, cancellation: CancellationToken,
                                        action: () -> T): T {
    cancellation.throwIfCancelled()
    val value = try { action() } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw WrapperContractProblem(reason)
    }
    cancellation.throwIfCancelled()
    return value
}

internal fun <T> wrapperList(values: Collection<T>): List<T> = Collections.unmodifiableList(values.toList())
internal fun <K, V> wrapperMap(values: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(values.toMap())

internal fun wrapperRelativePath(value: String): Boolean = value.length in 1..4096 &&
    !value.startsWith('/') && '\\' !in value && ':' !in value &&
    value.none { it.code < 32 || it.code == 127 } &&
    value.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

/** Aggregate text/count bounds precede copying, serialization, callbacks and coverage expansion. */
internal fun wrapperInputBounds(input: AnalysisInput) {
    val reason = WrapperUnavailableReason.ANALYSIS_ENVELOPE_BOUND_EXCEEDED
    var encodedUpperBound = 0L
    fun text(value: String?, maximum: Int = 4096) {
        if (value == null) return
        requireWrapper(value.length <= maximum, reason)
        encodedUpperBound += value.length.toLong() * 6 + 16
        requireWrapper(encodedUpperBound <= 8L * 1024 * 1024, reason)
    }
    fun strings(values: Collection<String>, maximum: Int) {
        requireWrapper(values.size <= maximum, reason)
        values.forEach { text(it) }
    }
    fun activationPolicyText(value: String) {
        // Count UTF-8 without allocating an encoded copy. Admission still treats the entire
        // value as opaque owner policy: this does not parse it, trim it, or confer authority.
        requireWrapper(value.length <= MAX_WRAPPER_ACTIVATION_POLICY_UTF8_BYTES, reason)
        var encodedBytes = 0
        var index = 0
        while (index < value.length) {
            val character = value[index]
            encodedBytes += when {
                character.code <= 0x7f -> 1
                character.code <= 0x7ff -> 2
                character.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate() -> {
                    index++
                    4
                }
                else -> {
                    // Replacement encoding could collapse distinct policy strings in a digest.
                    // Admit only well-formed UTF-16, without parsing serialized policy contents.
                    requireWrapper(!character.isSurrogate(), reason)
                    3
                }
            }
            requireWrapper(encodedBytes <= MAX_WRAPPER_ACTIVATION_POLICY_UTF8_BYTES, reason)
            index++
        }
        // Preserve the existing conservative JSON-envelope aggregate bound before freezing.
        text(value, MAX_WRAPPER_ACTIVATION_POLICY_UTF8_BYTES)
    }
    fun map(values: Map<String, String>, allowActivationPolicy: Boolean = false) {
        requireWrapper(values.size <= 4096, reason)
        values.forEach { (key, value) ->
            text(key)
            if (allowActivationPolicy && key == WRAPPER_ACTIVATION_POLICY_KEY) activationPolicyText(value) else text(value)
        }
    }
    requireWrapper(input.requiredRuleIds.size <= 4096 && input.snapshot.files.size <= 50_000 &&
        input.context.classpath.size <= 4096, reason)
    text(input.snapshot.snapshotId); text(input.snapshot.workspaceId); text(input.snapshot.worktreeId)
    text(input.snapshot.ownerId); text(input.snapshot.parentSnapshotId); text(input.snapshot.captureKind)
    strings(input.snapshot.declaredRoots, 256); strings(input.snapshot.scopeExclusions, 4096)
    strings(input.snapshot.unknownInputs, 4096)
    input.snapshot.files.forEach { text(it.path); text(it.digest) }
    text(input.materializedRoot); text(input.outputRoot); text(input.baselineSnapshotId)
    with(input.context) {
        listOf(scope.target, scope.sourceSet, scope.module, scope.compilation).forEach { text(it) }
        text(compilerVersion); text(modelDigest); text(dependencyDigest); text(toolchainDigest); text(configurationDigest)
        classpath.forEach { text(it.coordinate); text(it.digest); text(it.path) }
        strings(compilerArguments, 4096); map(libraries); map(policies, allowActivationPolicy = true)
        compilationFiles?.let { strings(it, 50_000) }
    }
    strings(input.requiredRuleIds, 4096); strings(input.changedPaths, 50_000)
    input.execution?.let { text(it.executable); text(it.executableDigest); strings(it.arguments, 4096); map(it.environment) }
}

internal fun freezeWrapperInput(input: AnalysisInput): AnalysisInput {
    wrapperInputBounds(input)
    return input.copy(
        snapshot = input.snapshot.copy(files = wrapperList(input.snapshot.files),
            declaredRoots = wrapperList(input.snapshot.declaredRoots), scopeExclusions = wrapperList(input.snapshot.scopeExclusions),
            unknownInputs = wrapperList(input.snapshot.unknownInputs)),
        context = input.context.copy(classpath = wrapperList(input.context.classpath),
            compilerArguments = wrapperList(input.context.compilerArguments), libraries = wrapperMap(input.context.libraries),
            policies = wrapperMap(input.context.policies), compilationFiles = input.context.compilationFiles?.let(::wrapperList)),
        requiredRuleIds = wrapperList(input.requiredRuleIds), changedPaths = wrapperList(input.changedPaths),
        execution = input.execution?.let { it.copy(arguments = wrapperList(it.arguments), environment = wrapperMap(it.environment)) },
    )
}

internal fun freezeWrapperPolicy(policy: WrapperRulePolicy): WrapperRulePolicy {
    val reason = WrapperUnavailableReason.POLICY_UNSUPPORTED
    requireWrapper(policy.ruleId == WRAPPER_CHECKSUM_FORMAT && policy.suppressions.size <= 256, reason)
    var textBytes = 0L
    policy.suppressions.forEach { suppression ->
        requireWrapper(suppression.ruleId == WRAPPER_CHECKSUM_FORMAT && wrapperRelativePath(suppression.propertiesPath) &&
            suppression.justification.isNotBlank() && suppression.justification.length <= 4096 &&
            with(suppression.context) { listOf(target, sourceSet, module, compilation).all { it.length in 1..4096 } }, reason)
        textBytes += (suppression.propertiesPath.length.toLong() + suppression.justification.length +
            with(suppression.context) { target.length.toLong() + sourceSet.length + module.length + compilation.length }) * 6
        requireWrapper(textBytes <= 1_048_576, reason)
    }
    requireWrapper(policy.suppressions.distinct().size == policy.suppressions.size, reason)
    return policy.copy(suppressions = wrapperList(policy.suppressions))
}

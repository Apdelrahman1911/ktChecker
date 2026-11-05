package dev.ktchecker.providers.buildcontracts

import dev.ktchecker.contracts.*
import kotlin.test.*

/** Synthetic registered-owner callbacks only. They do not attest real wrapper/platform execution. */
internal class WrapperContractFixture(
    propertiesText: String = properties("abc"),
    val scope: RuleContext = RuleContext(),
    var platform: WrapperBuildPlatform = WrapperBuildPlatform.JVM,
    requested: List<String> = listOf(WRAPPER_CHECKSUM_FORMAT),
    extraFiles: Map<String, ByteArray> = emptyMap(),
    inputPolicies: Map<String, String> = emptyMap(),
) {
    companion object {
        const val PROPERTIES_PATH = "gradle/wrapper/gradle-wrapper.properties"
        const val JAR_PATH = "gradle/wrapper/gradle-wrapper.jar"
        val ACCEPTED_DIGEST = "0123456789abcdef".repeat(4)
        const val BASE_URL = "distributionUrl=https\\://example.invalid/gradle-9.6.1-bin.zip\n"
        fun properties(checksum: String?): String = BASE_URL +
            if (checksum == null) "" else "distributionSha256Sum=$checksum\n"
    }

    private val jar = requireNotNull(javaClass.getResourceAsStream("/fixtures/gradle-wrapper-7a9ce74c.jar")) {
        "Pinned fixture resource is missing"
    }.use { it.readBytes() }
    val sourceBytes = propertiesText.toByteArray(Charsets.ISO_8859_1)
    private val registeredBytes = mapOf(PROPERTIES_PATH to sourceBytes.copyOf(), JAR_PATH to jar.copyOf()) +
        extraFiles.mapValues { it.value.copyOf() }
    val lastReadCopies = mutableMapOf<String, ByteArray>()
    private val snapshot = SnapshotManifest(Digests.sha256(propertiesText + scope.id +
        extraFiles.entries.sortedBy { it.key }.joinToString { it.key + Digests.sha256(it.value) }), "fixture-workspace", "fixture-worktree",
        registeredBytes.map { (path, bytes) -> SnapshotFile(path, Digests.sha256(bytes), bytes.size.toLong()) }, ownerId = "fixture-owner")
    val input = AnalysisInput(snapshot, "/synthetic-immutable-root-that-must-not-be-read",
        AnalysisContext(scope = scope, fullProject = true, modelDigest = Digests.sha256("fixture-model"),
            dependencyDigest = Digests.sha256("fixture-dependencies"), toolchainDigest = Digests.sha256("fixture-toolchain"),
            configurationDigest = Digests.sha256("fixture-configuration"), policies = inputPolicies), requiredRuleIds = requested)
    private val registeredInput = freezeWrapperInput(input)
    val selection = OwnerWrapperSelection(PROPERTIES_PATH, JAR_PATH)

    var snapshots = 0
    var selectedReceipts = 0
    var pathReceipts = 0
    var policyReceipts = 0
    var reads = 0
    var snapshotAllowed = true
    var selectedAllowed = true
    var pathAllowed = true
    var policyAllowed = true
    var wrapperVersion = SELECTED_WRAPPER_VERSION
    var distributionVersion = SELECTED_WRAPPER_VERSION
    var verificationPath = WrapperVerificationPath.REQUIRED_FRESH_OR_UNVERIFIED
    var selectedRequestDigest: String? = null
    var pathRequestDigest: String? = null
    var policyRequestDigest: String? = null
    var evidenceDigest: String = Digests.sha256("synthetic-owner-evidence")
    var afterSnapshot: () -> Unit = {}
    var afterSelected: () -> Unit = {}
    var reader: ((SnapshotFile) -> ByteArray)? = null

    fun install(policy: WrapperRulePolicy = WrapperRulePolicy(), actualInput: AnalysisInput = input,
                actualSelection: OwnerWrapperSelection = selection,
                cancellation: CancellationToken = CancellationToken.NONE): WrapperContextAdmission = InstalledWrapperContext.install(
        actualInput, actualSelection, policy,
        verifySnapshot = { value ->
            snapshots++
            check(snapshotAllowed) { "private-fixture-snapshot-error" }
            check(value.snapshot == registeredInput.snapshot && value.context == registeredInput.context)
            afterSnapshot()
        },
        verifySelectedWrapper = { request ->
            selectedReceipts++
            afterSelected()
            if (!selectedAllowed || request.selection != selection) null else OwnerSelectedWrapperReceipt(selectedRequestDigest ?: request.digest,
                evidenceDigest, wrapperVersion, distributionVersion, platform)
        },
        verifyVerificationPath = { request ->
            pathReceipts++
            if (!pathAllowed) null else OwnerWrapperPathReceipt(pathRequestDigest ?: request.digest,
                evidenceDigest, verificationPath)
        },
        verifyRulePolicy = { request ->
            policyReceipts++
            if (!policyAllowed) null else OwnerWrapperPolicyReceipt(policyRequestDigest ?: request.digest, evidenceDigest)
        },
        readSnapshotBytes = { file ->
            reads++
            reader?.invoke(file) ?: registeredBytes.getValue(file.path).copyOf().also { lastReadCopies[file.path] = it }
        }, cancellation = cancellation,
    )

    fun analyze(policy: WrapperRulePolicy = WrapperRulePolicy(), actualInput: AnalysisInput = input,
                actualSelection: OwnerWrapperSelection = selection): ProviderResult =
        GradleWrapperChecksumProvider(install(policy, actualInput, actualSelection)).analyze(actualInput)
}

internal fun assertNoncertifying(result: ProviderResult) {
    assertTrue(result.status in setOf("partial", "cancelled"))
    assertFalse(result.complete())
    assertTrue(result.coverage.all { it.certificateId == null && !it.successful() && !it.implemented && !it.tested && !it.qualified })
}

internal fun assertUnavailable(admission: WrapperContextAdmission, reason: WrapperUnavailableReason) {
    val unavailable = assertIs<WrapperContextAdmission.Unavailable>(admission)
    assertEquals(reason, unavailable.reason)
}

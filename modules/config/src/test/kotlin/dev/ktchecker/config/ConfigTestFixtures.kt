package dev.ktchecker.config

import dev.ktchecker.contracts.AnalysisContext
import dev.ktchecker.contracts.AnalysisInput
import dev.ktchecker.contracts.ClientIdentity
import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.Evidence
import dev.ktchecker.contracts.Finding
import dev.ktchecker.contracts.RuleContext
import dev.ktchecker.contracts.SnapshotFile
import dev.ktchecker.contracts.SnapshotManifest
import dev.ktchecker.contracts.SourceLocation
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal val TEST_BINDING = ConfigBinding("owner", "workspace", "worktree", "lineage")
internal val OWNER = ClientIdentity("owner", "explicit-owner-cli", "owner-session")
internal val TEST_CONTEXT = RuleContext(module = ":app", target = "jvm", sourceSet = "main", compilation = "main")
internal const val RULE = "ktc.demo.rule"
internal const val OTHER_RULE = "ktc.demo.other"
internal const val COMPILE_RULE = "kotlin.compile"
internal val TEST_ALIASES = RuleAliases(listOf(
    PolicyRule(RULE, setOf("upstream/demo", "old.rule"), implementationDigest = Digests.sha256("fixture-rule-1")),
    PolicyRule(OTHER_RULE, implementationDigest = Digests.sha256("fixture-rule-2")),
    PolicyRule(COMPILE_RULE, failureProtected = true, implementationDigest = Digests.sha256("fixture-compiler-1")),
))

internal class MutableClock(initial: Long = 1_000_000L) : Clock() {
    private val time = AtomicLong(initial)
    override fun instant(): Instant = Instant.ofEpochMilli(time.get())
    override fun millis(): Long = time.get()
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    fun advance(millis: Long) { time.addAndGet(millis) }
}

internal class ConfigFixture(
    val binding: ConfigBinding = TEST_BINDING,
    identityVerifier: FindingIdentityVerifier = FindingIdentityVerifier.NONE,
    lifecycleVerifier: FindingLifecycleVerifier = FindingLifecycleVerifier.NONE,
    writeObserver: ConfigWriteObserver = ConfigWriteObserver.NONE,
) : AutoCloseable {
    val root: Path = Files.createTempDirectory("ktchecker-config-test-")
    val clock = MutableClock()
    val authority = HmacOwnerAuthority(ByteArray(32) { it.toByte() }, listOf(OwnerSessionRegistration(OWNER, setOf(binding))))
    val store = ConfigStore(root.resolve("state"), TEST_ALIASES, authority, identityVerifier, lifecycleVerifier, clock, writeObserver)

    fun request(action: ConfigAction, layer: ConfigLayer = ConfigLayer.SHARED, expected: ConfigRevisionToken = store.capture(binding).revisionToken) =
        ConfigMutationRequest(binding, layer, expected, action, UUID.randomUUID().toString())

    fun mutate(action: ConfigAction, layer: ConfigLayer = ConfigLayer.SHARED): ConfigSnapshot {
        val request = request(action, layer)
        return store.mutate(request, authority.issueForExplicitOwnerAction(OWNER, request, clock.instant()))
    }

    fun suppress(
        id: String = "risk",
        selector: PolicySelector = PolicySelector.Rule(RULE),
        scope: SuppressionScope = SuppressionScope.Project,
        reason: SuppressionReason = SuppressionReason.ACCEPTED_RISK,
        layer: ConfigLayer = ConfigLayer.SHARED,
        expiresAt: Long? = null,
    ): ConfigSnapshot = mutate(ConfigAction.AddSuppression(SuppressionSpec(id, selector, scope, reason, "Explicit owner decision", expiresAt)), layer)

    fun finding(path: String = "src/Example.kt", code: String = "fun example() = 1", line: Int = 1,
                rule: String = RULE, context: RuleContext = TEST_CONTEXT): Finding = Finding(
        ruleId = rule, title = "Example finding", diagnosis = "Example diagnosis", whyItMatters = "Test fixture",
        location = SourceLocation(path, Digests.sha256(code), line, 0, line, 3, "example"),
        fingerprint = "do-not-trust-provider-fingerprint", providerId = "test-static", context = context,
        evidence = listOf(Evidence("static_rule", "Fixture evidence", digest = Digests.sha256("evidence"))),
    )

    fun input(snapshot: ConfigSnapshot = store.capture(binding), finding: Finding = finding()): AnalysisInput = AnalysisInput(
        snapshot = SnapshotManifest(
            snapshotId = Digests.sha256(finding.location.path + finding.location.fileDigest), workspaceId = binding.workspaceId,
            worktreeId = binding.worktreeId, ownerId = binding.ownerId,
            files = listOf(SnapshotFile(finding.location.path, finding.location.fileDigest, 20)),
        ),
        materializedRoot = root.resolve("source").toString(),
        context = AnalysisContext(scope = finding.context, configurationDigest = snapshot.policyDigest),
    )

    override fun close() {
        Files.walk(root).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
}

internal fun testIdentity(binding: ConfigBinding, input: AnalysisInput, finding: Finding, canonicalRule: String,
                          material: String = Digests.sha256("unchanged-symbol-body-and-bindings"),
                          evidence: String = Digests.sha256("unchanged-evidence"), evidenceKey: String = "occurrence-1",
                          artifact: String = Digests.sha256("provider-implementation")): VerifiedFindingIdentity = VerifiedFindingIdentity(
    "verified-fixture-certificate", input.snapshot.snapshotId, binding.ownerId, binding.workspaceId, binding.worktreeId, binding.lineageId,
    canonicalRule, finding.providerId, artifact, "verified-symbol-key", material, evidenceKey, evidence,
)

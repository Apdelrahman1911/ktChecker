package dev.ktchecker.audit

import dev.ktchecker.config.*
import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

private class AuditPolicyClock(initial: Instant = Instant.parse("2026-11-04T00:00:00Z")) : Clock() {
    private val time = AtomicLong(initial.toEpochMilli())
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(time.get())
    override fun millis(): Long = time.get()
    fun advance(millis: Long) { time.addAndGet(millis) }
}

/** Actual HMAC authority, policy files and locks. Finding/lifecycle inputs are explicitly fixtures. */
class ConfigAuditTest {
    @TempDir lateinit var temporary: Path
    private val binding = ConfigBinding("audit-owner", "project", "left", "lineage-a")
    private val other = binding.copy(worktreeId = "right", lineageId = "lineage-b")
    private val principal = ClientIdentity(binding.ownerId, "explicit-test-owner", "owner-session")
    private val clock = AuditPolicyClock()
    private val rule = "audit/policy-rule"
    private val aliases = registry("implementation-one")
    private val authority = HmacOwnerAuthority(ByteArray(32) { (it + 1).toByte() },
        listOf(OwnerSessionRegistration(principal, setOf(binding, other))))
    private val ids = AtomicInteger()

    private fun registry(version: String?): RuleAliases = RuleAliases(listOf(
        PolicyRule("audit/policy-rule", implementationDigest = version?.let(Digests::sha256)),
    ))

    private fun store(
        name: String = "policies",
        registry: RuleAliases = aliases,
        permitAuthority: MutationAuthority = authority,
        observer: ConfigWriteObserver = ConfigWriteObserver.NONE,
        lifecycle: FindingLifecycleVerifier = FindingLifecycleVerifier.NONE,
    ) = ConfigStore(temporary.resolve(name), registry, permitAuthority, lifecycleVerifier = lifecycle, clock = clock, writeObserver = observer)

    private fun suppression(id: String, selector: PolicySelector = PolicySelector.Rule(rule), expires: Long? = null) =
        SuppressionSpec(id, selector, SuppressionScope.Project, SuppressionReason.ACCEPTED_RISK,
            "Explicit test-owner decision; not a real defect waiver", expires)

    private fun mutation(snapshot: ConfigSnapshot, action: ConfigAction, layer: ConfigLayer = ConfigLayer.LOCAL) =
        ConfigMutationRequest(snapshot.binding, layer, snapshot.revisionToken, action, "request-${ids.incrementAndGet()}")

    private fun apply(actual: ConfigStore, snapshot: ConfigSnapshot, action: ConfigAction, layer: ConfigLayer = ConfigLayer.LOCAL): ConfigSnapshot {
        val request = mutation(snapshot, action, layer)
        return actual.mutate(request, authority.issueForExplicitOwnerAction(principal, request, clock.instant()))
    }

    private fun input(policy: ConfigSnapshot): AnalysisInput {
        val root = temporary.resolve("sources")
        Files.createDirectories(root)
        val text = "val fixtureValue = 1\n"
        Files.writeString(root.resolve("Fixture.kt"), text)
        return AnalysisInput(SnapshotManifest(
            Digests.sha256("test-snapshot:${policy.binding.worktreeId}"), policy.binding.workspaceId, policy.binding.worktreeId,
            listOf(SnapshotFile("Fixture.kt", Digests.sha256(text), text.toByteArray().size.toLong())), ownerId = policy.binding.ownerId),
            root.toString(), AnalysisContext(configurationDigest = policy.policyDigest), requiredRuleIds = listOf(rule))
    }

    private fun finding(input: AnalysisInput) = Finding(rule, "Test finding", "Test-only evidence", "Policy matching fixture only",
        SourceLocation("Fixture.kt", input.snapshot.files.single().digest), Digests.sha256("unchanged-provider-display-fingerprint"),
        "audit-provider", input.context.scope)

    @Test
    fun racingSharedAndLocalWritersCasTheWholePairAndCommitExactlyOneLayer() {
        val left = store()
        val right = store()
        val initial = left.capture(binding)
        val shared = mutation(initial, ConfigAction.AddSuppression(suppression("shared")), ConfigLayer.SHARED)
        val local = mutation(initial, ConfigAction.SetRuleEnabled(rule, false), ConfigLayer.LOCAL)
        val requests = listOf(left to shared, right to local).map { (instance, request) ->
            Triple(instance, request, authority.issueForExplicitOwnerAction(principal, request, clock.instant()))
        }
        val barrier = CyclicBarrier(3)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val attempts = requests.map { (instance, request, permit) -> pool.submit<Result<ConfigSnapshot>> {
                barrier.await(10, TimeUnit.SECONDS)
                runCatching { instance.mutate(request, permit) }
            } }
            barrier.await(10, TimeUnit.SECONDS)
            val results = attempts.map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.isSuccess })
            assertTrue(results.single { it.isFailure }.exceptionOrNull() is ConfigConflictException)
            val winner = results.single { it.isSuccess }.getOrThrow()
            val reopened = store().capture(binding)
            assertEquals(winner.revisionToken, reopened.revisionToken)
            assertEquals(1L, reopened.shared.revision.revision + reopened.local.revision.revision)
            assertEquals(1, reopened.shared.auditEvents.size + reopened.local.auditEvents.size)
            assertEquals(1, reopened.shared.suppressions.size + reopened.local.ruleSettings.size)
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    @Test
    fun ownerPermitIsRecheckedAfterWaitingForAnotherStoreInstancesFence() {
        val initial = store().capture(binding)
        val request = mutation(initial, ConfigAction.SetRuleEnabled(rule, false))
        val permit = authority.issueForExplicitOwnerAction(principal, request, clock.instant(), Duration.ofMillis(100))
        val firstVerification = CountDownLatch(1)
        val calls = AtomicInteger()
        val countedAuthority = MutationAuthority { candidate, presented, now ->
            authority.verify(candidate, presented, now).also {
                if (calls.incrementAndGet() == 1) firstVerification.countDown()
            }
        }
        val contender = store(permitAuthority = countedAuthority)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val holder = pool.submit {
                store().withCurrentConfig(binding, initial.revisionToken) { check ->
                    assertTrue(check.matches)
                    locked.countDown()
                    assertTrue(release.await(10, TimeUnit.SECONDS))
                }
            }
            assertTrue(locked.await(10, TimeUnit.SECONDS))
            val writer = pool.submit<Result<ConfigSnapshot>> { runCatching { contender.mutate(request, permit) } }
            assertTrue(firstVerification.await(10, TimeUnit.SECONDS), "Writer did not pass pre-lock authorization")
            assertFailsWith<TimeoutException> { writer.get(100, TimeUnit.MILLISECONDS) }
            clock.advance(100)
            release.countDown()
            holder.get(10, TimeUnit.SECONDS)
            assertTrue(writer.get(10, TimeUnit.SECONDS).exceptionOrNull() is ConfigAuthorizationException)
            assertEquals(initial.revisionToken, store().capture(binding).revisionToken)
        } finally {
            release.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun permitsCannotRetargetEvenAnotherEnrolledBindingOrAlterOnePayloadField() {
        val actual = store()
        val original = actual.capture(binding)
        val alternate = actual.capture(other)
        val request = mutation(original, ConfigAction.AddSuppression(suppression("one")))
        val permit = authority.issueForExplicitOwnerAction(principal, request, clock.instant())
        val altered = listOf(
            request.copy(binding = other, expected = alternate.revisionToken),
            request.copy(layer = ConfigLayer.SHARED),
            request.copy(action = ConfigAction.AddSuppression(suppression("different"))),
            request.copy(requestId = "different-request"),
        )
        altered.forEach { assertFailsWith<ConfigAuthorizationException> { actual.mutate(it, permit) } }
        assertFailsWith<ConfigAuthorizationException> {
            actual.mutate(request, permit.copy(principal = principal.copy(clientId = "ordinary-model")))
        }
        assertFailsWith<ConfigAuthorizationException> {
            actual.mutate(request, permit.copy(signature = "forged-owner-signature"))
        }
        assertEquals(original.revisionToken, actual.capture(binding).revisionToken)
        assertEquals(alternate.revisionToken, actual.capture(other).revisionToken)
    }

    @Test
    fun stalePolicyWrongScopeAndAnotherLineageCannotAuthorizeFindingSuppression() {
        val actual = store()
        val before = actual.capture(binding)
        val oldInput = input(before)
        val record = finding(oldInput)
        val selector = actual.findingSelector(before, oldInput, record)
        val policy = apply(actual, before, ConfigAction.AddSuppression(suppression("exact", selector)))
        val currentInput = oldInput.copy(context = oldInput.context.copy(configurationDigest = policy.policyDigest))
        assertEquals(PolicyDisposition.ACCEPTED, actual.explain(policy, currentInput, record).disposition)
        assertEquals(PolicyDisposition.OPEN, actual.explain(policy, oldInput, record).disposition)
        assertEquals(PolicyDisposition.OPEN, actual.explain(policy,
            currentInput.copy(context = currentInput.context.copy(configurationDigest = null)), record).disposition)
        assertEquals(PolicyDisposition.OPEN, actual.explain(policy, currentInput,
            record.copy(context = record.context.copy(target = "android"))).disposition)
        assertEquals(PolicyDisposition.OPEN, actual.explain(policy,
            currentInput.copy(snapshot = currentInput.snapshot.copy(ownerId = "different-owner")), record).disposition)
        val otherLineage = actual.capture(binding.copy(lineageId = "new-lineage"))
        val newInput = currentInput.copy(context = currentInput.context.copy(configurationDigest = otherLineage.policyDigest))
        assertEquals(PolicyDisposition.OPEN, actual.explain(otherLineage, newInput, record).disposition)
        assertTrue(otherLineage.local.suppressions.isEmpty())
    }

    @Test
    fun editableLifecycleLabelsNeedFreshBoundTrustedProofAndCannotSkipIdentityChecks() {
        val defaultStore = store()
        val empty = defaultStore.capture(binding)
        val analysis = input(empty)
        val record = finding(analysis)
        listOf("fixed", "baselined", "unavailable", "disabled", "accepted", "suppressed").forEach { label ->
            assertEquals(PolicyDisposition.OPEN, defaultStore.explain(empty, analysis, record.copy(disposition = label)).disposition, label)
        }
        var proof: VerifiedLifecycleDisposition? = null
        val verifierCalls = AtomicInteger()
        val actual = store(lifecycle = FindingLifecycleVerifier { _, _, _, _ -> verifierCalls.incrementAndGet(); proof })
        val selector = actual.findingSelector(empty, analysis, record)
        val good = VerifiedLifecycleDisposition("test-only-bound-lifecycle-proof", analysis.snapshot.snapshotId, empty.policyDigest,
            rule, record.providerId, selector.fingerprint, PolicyDisposition.FIXED)
        proof = good
        assertEquals(PolicyDisposition.FIXED, actual.explain(empty, analysis, record).disposition)
        val badProofs = listOf(good.copy(certificateId = " "), good.copy(snapshotId = Digests.sha256("other")),
            good.copy(policyDigest = Digests.sha256("other")), good.copy(providerId = "other"),
            good.copy(canonicalRuleId = "another-rule"), good.copy(fingerprint = Digests.sha256("other")))
        badProofs.forEach { bad -> proof = bad; assertEquals(PolicyDisposition.OPEN, actual.explain(empty, analysis, record).disposition) }
        proof = good
        val calls = verifierCalls.get()
        assertEquals(PolicyDisposition.OPEN, actual.explain(empty,
            analysis.copy(context = analysis.context.copy(configurationDigest = Digests.sha256("stale"))), record).disposition)
        assertEquals(calls, verifierCalls.get(), "Mismatched analysis must not reach lifecycle authority")
    }

    @Test
    fun expirationChangesTheLiveFenceButRestartRestoresTheExactHistoricalPolicy() {
        val actual = store()
        val policy = apply(actual, actual.capture(binding), ConfigAction.AddSuppression(
            suppression("temporary", expires = clock.millis() + 100)))
        val frozen = actual.exportSnapshot(policy)
        val historicalInput = input(policy)
        val record = finding(historicalInput)
        actual.withCurrentConfig(binding, policy.revisionToken) { check ->
            assertTrue(check.matches)
            clock.advance(100)
            assertFalse(check.matches, "Expiration must be rechecked inside the innermost callback")
            assertTrue("policy_expiration_or_clock_drift" in check.reasons)
        }
        val reopened = store()
        val current = reopened.capture(binding)
        assertEquals(policy.local.revision, current.local.revision, "Expiration is not a fabricated document write")
        assertNotEquals(policy.policyDigest, current.policyDigest)
        assertEquals(PolicyDisposition.OPEN, reopened.explain(current,
            historicalInput.copy(context = historicalInput.context.copy(configurationDigest = current.policyDigest)), record).disposition)
        val restored = reopened.restoreSnapshot(frozen, policy.revisionToken)
        assertEquals(policy.revisionToken, restored.revisionToken)
        assertEquals(PolicyDisposition.ACCEPTED, reopened.explain(restored, historicalInput, record).disposition,
            "Historical classification must not silently use the current policy; a live final gate remains invalid")
        reopened.withCurrentConfig(binding, restored.revisionToken) { assertFalse(it.matches) }
    }

    @Test
    fun sameRevisionByteChangesInvalidateTheFullTokenWithoutAlteringFrozenReviewEvidence() {
        val actual = store()
        val policy = apply(actual, actual.capture(binding), ConfigAction.SetRuleEnabled(rule, false), ConfigLayer.SHARED)
        val archived = actual.exportSnapshot(policy)
        val path = actual.storagePaths(binding).shared
        Files.writeString(path, Files.readString(path) + "\n ")
        val changed = store().capture(binding)
        assertEquals(policy.shared.revision.revision, changed.shared.revision.revision)
        assertEquals(policy.shared.revision.generationId, changed.shared.revision.generationId)
        assertNotEquals(policy.shared.revision.documentDigest, changed.shared.revision.documentDigest)
        actual.withCurrentConfig(binding, policy.revisionToken) {
            assertFalse(it.matches)
            assertTrue("shared_configuration_changed" in it.reasons)
        }
        assertEquals(policy.revisionToken, actual.restoreSnapshot(archived, policy.revisionToken).revisionToken)
        assertFailsWith<ConfigReadException> {
            actual.restoreSnapshot(archived.copy(sharedDocument = archived.sharedDocument + "\n"), policy.revisionToken)
        }
    }

    @Test
    fun atomicReplacementFailuresDistinguishUnappliedFromUnknownAndNeverHalfCommitLayers() {
        val base = store().capture(binding)
        val request = mutation(base, ConfigAction.SetRuleEnabled(rule, false))
        val permit = authority.issueForExplicitOwnerAction(principal, request, clock.instant())
        val before = store(observer = object : ConfigWriteObserver {
            override fun beforeAtomicReplace(temporary: Path, destination: Path) { throw IllegalStateException("before replace") }
        })
        assertFalse(assertFailsWith<ConfigWriteException> { before.mutate(request, permit) }.possiblyApplied)
        assertEquals(base.revisionToken, store().capture(binding).revisionToken)
        val after = store(observer = object : ConfigWriteObserver {
            override fun afterAtomicReplace(destination: Path) { throw IllegalStateException("after replace") }
        })
        assertTrue(assertFailsWith<ConfigWriteException> { after.mutate(request, permit) }.possiblyApplied)
        val current = store().capture(binding)
        assertEquals(base.shared.revision, current.shared.revision)
        assertEquals(base.local.revision.revision + 1, current.local.revision.revision)
        assertEquals(false, current.ruleSettings.getValue(rule).enabled)
        assertFailsWith<ConfigConflictException> { store().mutate(request, permit) }
        assertEquals(current.revisionToken, store().capture(binding).revisionToken)
    }

    @Test
    fun implementationUpgradeOrMissingIdentityReopensExactSuppressionDespiteUnchangedDisplayFingerprint() {
        val v1 = store()
        val before = v1.capture(binding)
        val originalInput = input(before)
        val record = finding(originalInput)
        val selector = v1.findingSelector(before, originalInput, record)
        val policy = apply(v1, before, ConfigAction.AddSuppression(suppression("version-bound", selector)))
        assertEquals(PolicyDisposition.ACCEPTED, v1.explain(policy,
            originalInput.copy(context = originalInput.context.copy(configurationDigest = policy.policyDigest)), record).disposition)
        for (version in listOf("implementation-two", null)) {
            val changed = store(registry = registry(version))
            val captured = changed.capture(binding)
            val analysis = originalInput.copy(context = originalInput.context.copy(configurationDigest = captured.policyDigest))
            assertEquals(PolicyDisposition.OPEN, changed.explain(captured, analysis, record).disposition)
            if (version == null) assertFailsWith<ConfigValidationException> { changed.findingSelector(captured, analysis, record) }
        }
    }
}

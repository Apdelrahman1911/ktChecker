package dev.ktchecker.config

import dev.ktchecker.contracts.ClientIdentity
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Real cooperative policy locks; disposable owner authority and deterministic test clock only. */
class ConfigFenceConcurrencyTest {
    @Test fun permitExpiryDuringAFinalFenceWaitIsReverifiedBeforeEitherLayerCanWrite() {
        for (layer in ConfigLayer.entries) ConfigFixture().use { fixture ->
            val captured = fixture.store.capture(fixture.binding)
            val request = fixture.request(ConfigAction.SetRuleEnabled(RULE, false), layer, captured.revisionToken)
            val permit = fixture.authority.issueForExplicitOwnerAction(OWNER, request, fixture.clock.instant(), Duration.ofMillis(100))
            val observed = PreflightAuthority(fixture.authority)
            val writer = ConfigStore(fixture.root.resolve("state"), TEST_ALIASES, observed, clock = fixture.clock)
            val pool = Executors.newSingleThreadExecutor()
            try {
                val pending = fixture.store.withCurrentConfig(fixture.binding, captured.revisionToken) { fence ->
                    assertTrue(fence.matches)
                    val task = pool.submit<ConfigAuthorizationException> {
                        assertFailsWith<ConfigAuthorizationException> { writer.mutate(request, permit) }
                    }
                    await(observed.firstVerified)
                    // The first authority check really succeeded. The common project fence is
                    // still held, so the second check must use the time AFTER this transition.
                    fixture.clock.advance(100)
                    assertTrue(fence.matches, "No policy expires in this control; only the permit does")
                    task
                }
                pending.get(WAIT_SECONDS, TimeUnit.SECONDS)
                assertEquals(listOf(permit.issuedAtEpochMillis, permit.expiresAtEpochMillis), observed.times.toList())
                assertEquals(captured.revisionToken, fixture.store.capture(fixture.binding).revisionToken)
                val paths = writer.storagePaths(fixture.binding)
                assertFalse(Files.exists(paths.shared))
                assertFalse(Files.exists(paths.local))
            } finally {
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS), "Synthetic owner worker must terminate")
            }
        }
    }

    @Test fun effectivePolicyExpiryDuringAFenceWaitRejectsCasDespiteUnchangedLayerBytes() {
        for (layer in ConfigLayer.entries) ConfigFixture().use { fixture ->
            val captured = fixture.suppress(expiresAt = fixture.clock.millis() + 100)
            val before = Files.readAllBytes(fixture.store.storagePaths(fixture.binding).shared)
            val request = fixture.request(ConfigAction.SetRuleEnabled(OTHER_RULE, false), layer, captured.revisionToken)
            val permit = fixture.authority.issueForExplicitOwnerAction(OWNER, request, fixture.clock.instant())
            val observed = PreflightAuthority(fixture.authority)
            val writer = ConfigStore(fixture.root.resolve("state"), TEST_ALIASES, observed, clock = fixture.clock)
            val pool = Executors.newSingleThreadExecutor()
            try {
                val pending = fixture.store.withCurrentConfig(fixture.binding, captured.revisionToken) { fence ->
                    assertTrue(fence.matches)
                    val task = pool.submit<ConfigConflictException> {
                        assertFailsWith<ConfigConflictException> { writer.mutate(request, permit) }
                    }
                    await(observed.firstVerified)
                    fixture.clock.advance(100)
                    assertFalse(fence.matches)
                    assertEquals(listOf("policy_expiration_or_clock_drift"), fence.reasons)
                    task
                }
                val conflict = pending.get(WAIT_SECONDS, TimeUnit.SECONDS)
                val current = fixture.store.capture(fixture.binding)
                assertEquals(2, observed.times.size)
                assertTrue(fixture.clock.millis() < permit.expiresAtEpochMillis, "The permit must remain valid")
                assertEquals(current.revisionToken, conflict.current)
                assertEquals(captured.shared.revision, current.shared.revision)
                assertEquals(captured.local.revision, current.local.revision)
                assertNotEquals(captured.policyDigest, current.policyDigest)
                assertContentEquals(before, Files.readAllBytes(writer.storagePaths(fixture.binding).shared))
                assertFalse(Files.exists(writer.storagePaths(fixture.binding).local))
                assertTrue(current.ruleSettings.isEmpty(), "Neither possible mutation layer may be partially committed")
                val finding = fixture.finding()
                assertEquals(PolicyDisposition.ACCEPTED, writer.explain(captured, fixture.input(captured, finding), finding).disposition)
                assertEquals(PolicyDisposition.OPEN, writer.explain(current, fixture.input(current, finding), finding).disposition)
            } finally {
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS), "Synthetic owner worker must terminate")
            }
        }
    }

    @Test fun signedImportIsDetachedBeforeVerificationAndCannotChangeWhileItsWriterWaits() = ConfigFixture().use { fixture ->
        val captured = fixture.store.capture(fixture.binding)
        val approved = SuppressionSpec("approved", PolicySelector.Rule(RULE), SuppressionScope.Project,
            SuppressionReason.ACCEPTED_RISK, "Only this exact synthetic owner payload is approved")
        val callerOwned = mutableListOf(approved)
        val request = fixture.request(ConfigAction.ImportSharedConfig(SharedProjectConfig(suppressions = callerOwned)),
            expected = captured.revisionToken)
        val permit = fixture.authority.issueForExplicitOwnerAction(OWNER, request, fixture.clock.instant())
        val observed = PreflightAuthority(fixture.authority)
        val writer = ConfigStore(fixture.root.resolve("state"), TEST_ALIASES, observed, clock = fixture.clock)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val pending = fixture.store.withCurrentConfig(fixture.binding, captured.revisionToken) { fence ->
                val task = pool.submit<ConfigSnapshot> { writer.mutate(request, permit) }
                await(observed.firstVerified)
                callerOwned += approved.copy(id = "not-approved", selector = PolicySelector.Rule(OTHER_RULE))
                assertNotEquals(permit.requestDigest, request.digest(), "The caller-owned request really changed after verification")
                assertTrue(fence.matches)
                task
            }
            val committed = pending.get(WAIT_SECONDS, TimeUnit.SECONDS)
            assertEquals(2, observed.times.size)
            assertEquals(listOf(approved), committed.shared.suppressions.map { it.spec })
            assertEquals(1L, committed.shared.revision.revision)
            assertEquals(0L, committed.local.revision.revision)
            assertEquals(permit.requestDigest, committed.shared.auditEvents.single().requestDigest)
            assertEquals(request.requestId, committed.shared.auditEvents.single().requestId)
            assertEquals(OWNER, committed.shared.auditEvents.single().actor)
            assertEquals(committed.revisionToken, fixture.store.capture(fixture.binding).revisionToken)
            assertEquals(2, callerOwned.size, "Storage must detach, not rewrite the client's mutable object")
            assertFalse(Files.exists(writer.storagePaths(fixture.binding).local))
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS), "Synthetic owner worker must terminate")
        }
    }

    private class PreflightAuthority(private val delegate: MutationAuthority) : MutationAuthority {
        val firstVerified = CountDownLatch(1)
        val times = ConcurrentLinkedQueue<Long>()
        override fun verify(request: ConfigMutationRequest, permit: MutationPermit, now: Instant): ClientIdentity {
            times += now.toEpochMilli()
            val principal = delegate.verify(request, permit, now)
            firstVerified.countDown()
            return principal
        }
    }

    private fun await(latch: CountDownLatch) = assertTrue(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), "Synthetic barrier timed out")

    companion object { private const val WAIT_SECONDS = 15L }
}

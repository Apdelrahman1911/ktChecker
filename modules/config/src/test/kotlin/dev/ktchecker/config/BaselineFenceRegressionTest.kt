package dev.ktchecker.config

import dev.ktchecker.contracts.Finding
import java.nio.file.Files
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Baselines have separate activity/deadline accounting from suppressions. All state is disposable. */
class BaselineFenceRegressionTest {
    @Test fun nonmatchingAndExpiredLocalBaselinesDoNotReviveTheSameIdSharedBaseline(): Unit = ConfigFixture().use { fixture ->
        val original = fixture.finding()
        val changed = fixture.finding(code = "fun example() = anotherDefect()")
        val shared = baseline(fixture, "same-id", original)
        fixture.mutate(ConfigAction.AddBaseline(shared))
        val expires = fixture.clock.millis() + 100
        val local = baseline(fixture, shared.id, changed, expires)
        val frozen = fixture.mutate(ConfigAction.AddBaseline(local), ConfigLayer.LOCAL)
        val paths = fixture.store.storagePaths(fixture.binding)
        val sharedBytes = Files.readAllBytes(paths.shared)
        val localBytes = Files.readAllBytes(paths.local)

        assertEquals(listOf(shared), frozen.shared.baselines.map { it.spec })
        assertEquals(listOf(local), frozen.local.baselines.map { it.spec })
        assertEquals(mapOf(ConfigLayer.SHARED to false, ConfigLayer.LOCAL to true), frozen.baselines.associate { it.layer to it.active })
        assertEquals(expires, frozen.validUntilEpochMillis)
        assertEquals(PolicyDisposition.OPEN, fixture.store.explain(frozen, fixture.input(frozen, original), original).disposition)
        val localExplanation = fixture.store.explain(frozen, fixture.input(frozen, changed), changed)
        assertEquals(PolicyDisposition.BASELINED, localExplanation.disposition)
        assertEquals(local.id, localExplanation.policyEntryId)
        assertEquals(ConfigLayer.LOCAL, localExplanation.layer)

        fixture.clock.advance(100)
        val expired = fixture.store.capture(fixture.binding)
        assertEquals(frozen.shared, expired.shared)
        assertEquals(frozen.local, expired.local)
        assertContentEquals(sharedBytes, Files.readAllBytes(paths.shared))
        assertContentEquals(localBytes, Files.readAllBytes(paths.local))
        assertNotEquals(frozen.policyDigest, expired.policyDigest)
        assertNull(expired.validUntilEpochMillis)
        assertTrue(expired.baselines.none { it.active }, "An expired local record still shadows its shared ID")
        for (finding in listOf(original, changed)) {
            val explanation = fixture.store.explain(expired, fixture.input(expired, finding), finding)
            assertEquals(PolicyDisposition.OPEN, explanation.disposition)
            assertNull(explanation.policyEntryId)
        }
        assertEquals(PolicyDisposition.BASELINED, fixture.store.explain(frozen, fixture.input(frozen, changed), changed).disposition)

        val removed = fixture.mutate(ConfigAction.RemoveBaseline(local.id), ConfigLayer.LOCAL)
        assertEquals(expired.shared, removed.shared)
        assertContentEquals(sharedBytes, Files.readAllBytes(paths.shared))
        assertTrue(removed.local.baselines.isEmpty())
        val restored = fixture.store.explain(removed, fixture.input(removed, original), original)
        assertEquals(PolicyDisposition.BASELINED, restored.disposition)
        assertEquals(shared.id, restored.policyEntryId)
        assertEquals(ConfigLayer.SHARED, restored.layer)
        assertEquals(PolicyDisposition.OPEN, fixture.store.explain(removed, fixture.input(removed, changed), changed).disposition)
    }

    @Test fun shadowedSharedBaselineExpiryDoesNotInvalidateTheEffectivePolicyFence(): Unit = ConfigFixture().use { fixture ->
        val finding = fixture.finding()
        val shared = baseline(fixture, "same-id", finding, fixture.clock.millis() + 100)
        fixture.mutate(ConfigAction.AddBaseline(shared))
        val local = shared.copy(expiresAtEpochMillis = null)
        val captured = fixture.mutate(ConfigAction.AddBaseline(local), ConfigLayer.LOCAL)
        val paths = fixture.store.storagePaths(fixture.binding)
        val sharedBytes = Files.readAllBytes(paths.shared)
        val localBytes = Files.readAllBytes(paths.local)
        val request = fixture.request(ConfigAction.RemoveBaseline(local.id), ConfigLayer.LOCAL, captured.revisionToken)
        val permit = fixture.authority.issueForExplicitOwnerAction(OWNER, request, fixture.clock.instant())

        assertNull(captured.validUntilEpochMillis, "A shadowed baseline cannot set an effective policy deadline")
        fixture.store.withCurrentConfig(fixture.binding, captured.revisionToken) { fence ->
            assertTrue(fence.matches)
            fixture.clock.advance(100)
            assertTrue(fence.matches, "Only an inactive shared baseline expired")
            assertTrue(fence.reasons.isEmpty())
        }
        val current = fixture.store.capture(fixture.binding)
        assertEquals(captured.revisionToken, current.revisionToken)
        assertEquals(captured.baselines, current.baselines)
        assertContentEquals(sharedBytes, Files.readAllBytes(paths.shared))
        assertContentEquals(localBytes, Files.readAllBytes(paths.local))
        val explanation = fixture.store.explain(current, fixture.input(current, finding), finding)
        assertEquals(PolicyDisposition.BASELINED, explanation.disposition)
        assertEquals(ConfigLayer.LOCAL, explanation.layer)

        // The original full-token request is still current; no effective policy changed.
        val removed = fixture.store.mutate(request, permit)
        assertEquals(captured.shared, removed.shared)
        assertContentEquals(sharedBytes, Files.readAllBytes(paths.shared))
        assertTrue(removed.local.baselines.isEmpty())
        assertTrue(removed.baselines.none { it.active }, "Removing the override must not reactivate an expired shared baseline")
        assertEquals(PolicyDisposition.OPEN, fixture.store.explain(removed, fixture.input(removed, finding), finding).disposition)
        assertEquals(request.requestId, removed.local.auditEvents.last().requestId)
    }

    @Test fun baselineOnlyExpiryRejectsAWaitingOtherLayerMutationUntilExplicitReinspection() {
        for (expiringLayer in ConfigLayer.entries) ConfigFixture().use { fixture ->
            val targetLayer = if (expiringLayer == ConfigLayer.SHARED) ConfigLayer.LOCAL else ConfigLayer.SHARED
            val finding = fixture.finding()
            val stableFinding = fixture.finding(rule = OTHER_RULE)
            val stable = baseline(fixture, "stable", stableFinding)
            fixture.mutate(ConfigAction.AddBaseline(stable), targetLayer)
            val expires = fixture.clock.millis() + 100
            val active = baseline(fixture, "active", finding, expires)
            val captured = fixture.mutate(ConfigAction.AddBaseline(active), expiringLayer)
            val paths = fixture.store.storagePaths(fixture.binding)
            val sharedBytes = Files.readAllBytes(paths.shared)
            val localBytes = Files.readAllBytes(paths.local)
            val request = fixture.request(ConfigAction.RemoveBaseline(stable.id), targetLayer, captured.revisionToken)
            val permit = fixture.authority.issueForExplicitOwnerAction(OWNER, request, fixture.clock.instant())
            val firstVerified = CountDownLatch(1)
            val times = ConcurrentLinkedQueue<Long>()
            val observed = MutationAuthority { approved, proof, now ->
                val principal = fixture.authority.verify(approved, proof, now)
                times += now.toEpochMilli()
                firstVerified.countDown()
                principal
            }
            val writer = ConfigStore(fixture.root.resolve("state"), TEST_ALIASES, observed, clock = fixture.clock)
            val pool = Executors.newSingleThreadExecutor()
            try {
                assertTrue(captured.suppressions.isEmpty(), "The expiration must come from the separate baseline deadline branch")
                assertEquals(expires, captured.validUntilEpochMillis)
                val pending = fixture.store.withCurrentConfig(fixture.binding, captured.revisionToken) { fence ->
                    assertTrue(fence.matches)
                    val task = pool.submit<ConfigConflictException> {
                        assertFailsWith<ConfigConflictException> { writer.mutate(request, permit) }
                    }
                    assertTrue(firstVerified.await(WAIT_SECONDS, TimeUnit.SECONDS), "Synthetic owner preflight must reach the held project fence")
                    fixture.clock.advance(100)
                    assertFalse(fence.matches)
                    assertEquals(listOf("policy_expiration_or_clock_drift"), fence.reasons)
                    task
                }
                val conflict = pending.get(WAIT_SECONDS, TimeUnit.SECONDS)
                val current = writer.capture(fixture.binding)
                assertEquals(listOf(captured.capturedAtEpochMillis, expires), times.toList())
                assertTrue(fixture.clock.millis() < permit.expiresAtEpochMillis, "This is a policy conflict, not an expired permit")
                assertEquals(current.revisionToken, conflict.current)
                assertEquals(captured.shared, current.shared)
                assertEquals(captured.local, current.local)
                assertContentEquals(sharedBytes, Files.readAllBytes(paths.shared))
                assertContentEquals(localBytes, Files.readAllBytes(paths.local))
                assertNotEquals(captured.policyDigest, current.policyDigest)
                assertNull(current.validUntilEpochMillis)
                assertEquals(mapOf(active.id to false, stable.id to true), current.baselines.associate { it.record.spec.id to it.active })
                assertEquals(PolicyDisposition.BASELINED, writer.explain(captured, fixture.input(captured, finding), finding).disposition)
                assertEquals(PolicyDisposition.OPEN, writer.explain(current, fixture.input(current, finding), finding).disposition)
                assertEquals(PolicyDisposition.BASELINED, writer.explain(current, fixture.input(current, stableFinding), stableFinding).disposition)

                val fresh = fixture.request(request.action, targetLayer, current.revisionToken)
                val freshPermit = fixture.authority.issueForExplicitOwnerAction(OWNER, fresh, fixture.clock.instant())
                assertNotEquals(permit.requestDigest, freshPermit.requestDigest)
                val committed = writer.mutate(fresh, freshPermit)
                val before = if (targetLayer == ConfigLayer.SHARED) current.shared else current.local
                val after = if (targetLayer == ConfigLayer.SHARED) committed.shared else committed.local
                assertEquals(before.revision.revision + 1, after.revision.revision)
                assertTrue(after.baselines.isEmpty())
                assertEquals(before.auditEvents, after.auditEvents.dropLast(1))
                assertEquals(fresh.requestId, after.auditEvents.last().requestId)
                assertEquals(fresh.digest(), after.auditEvents.last().requestDigest)
                assertEquals(OWNER, after.auditEvents.last().actor)
                if (expiringLayer == ConfigLayer.SHARED) {
                    assertEquals(current.shared, committed.shared)
                    assertContentEquals(sharedBytes, Files.readAllBytes(paths.shared))
                } else {
                    assertEquals(current.local, committed.local)
                    assertContentEquals(localBytes, Files.readAllBytes(paths.local))
                }
                assertEquals(listOf(active), committed.baselines.map { it.record.spec })
                assertTrue(committed.baselines.none { it.active })
                for (currentFinding in listOf(finding, stableFinding)) {
                    assertEquals(PolicyDisposition.OPEN, writer.explain(committed, fixture.input(committed, currentFinding), currentFinding).disposition)
                    assertEquals(PolicyDisposition.BASELINED, writer.explain(captured, fixture.input(captured, currentFinding), currentFinding).disposition)
                }
            } finally {
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS), "Synthetic owner worker must terminate")
            }
        }
    }

    private fun baseline(fixture: ConfigFixture, id: String, finding: Finding, expires: Long? = null): BaselineSpec {
        val snapshot = fixture.store.capture(fixture.binding)
        val selector = fixture.store.findingSelector(snapshot, fixture.input(snapshot, finding), finding)
        return BaselineSpec(id, selector, "Explicit synthetic owner baseline for $id", expires)
    }

    private companion object { const val WAIT_SECONDS = 15L }
}

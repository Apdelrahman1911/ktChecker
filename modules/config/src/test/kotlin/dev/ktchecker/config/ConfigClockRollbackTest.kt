package dev.ktchecker.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real policy capture/fences with a synthetic clock; no sleeps, threads or provider execution. */
class ConfigClockRollbackTest {
    @Test fun noExpiryFenceUsesItsOwnCaptureLowerBoundAndAllowsInWindowRollback() {
        for (layer in ConfigLayer.entries) ConfigFixture().use { fixture ->
            val review = fixture.suppress(layer = layer)
            val paths = fixture.store.storagePaths(fixture.binding)
            val policyFile = if (layer == ConfigLayer.SHARED) paths.shared else paths.local
            val before = Files.readAllBytes(policyFile)
            fixture.clock.advance(10)

            fixture.store.withCurrentConfig(fixture.binding, review.revisionToken) { fence ->
                val capturedAt = review.capturedAtEpochMillis + 10
                assertEquals(capturedAt, fence.current.capturedAtEpochMillis)
                assertEquals(review.revisionToken, fence.current.revisionToken)
                assertNull(fence.current.validUntilEpochMillis)
                assertCurrent(fence)

                fixture.clock.advance(10)
                assertCurrent(fence)
                fixture.clock.advance(-9)
                assertCurrent(fence) // A negative delta inside the capture window is not itself invalid.
                fixture.clock.advance(-1)
                assertEquals(capturedAt, fixture.clock.millis())
                assertCurrent(fence) // The lower endpoint is inclusive.
                fixture.clock.advance(-1)
                assertTrue(fixture.clock.millis() > review.capturedAtEpochMillis,
                    "The lower bound belongs to this fence, not the earlier review capture")
                assertClockDrift(fence)
                assertEquals(review.revisionToken, fence.current.revisionToken)
            }

            val current = fixture.store.capture(fixture.binding)
            assertEquals(review.revisionToken, current.revisionToken)
            assertEquals(review.shared, current.shared)
            assertEquals(review.local, current.local)
            assertContentEquals(before, Files.readAllBytes(policyFile))
            val finding = fixture.finding()
            assertEquals(PolicyDisposition.ACCEPTED,
                fixture.store.explain(review, fixture.input(review, finding), finding).disposition)
        }
    }

    @Test fun expiringAndExpiredPolicyFencesRejectRollbackWithoutInventingAStoredMutation() {
        for (layer in ConfigLayer.entries) ConfigFixture().use { fixture ->
            val expires = fixture.clock.millis() + 100
            val active = fixture.suppress(layer = layer, expiresAt = expires)
            val paths = fixture.store.storagePaths(fixture.binding)
            val policyFile = if (layer == ConfigLayer.SHARED) paths.shared else paths.local
            val before = Files.readAllBytes(policyFile)
            fixture.store.withCurrentConfig(fixture.binding, active.revisionToken) { fence ->
                assertEquals(expires, fence.current.validUntilEpochMillis)
                assertCurrent(fence)
                fixture.clock.advance(-1)
                assertClockDrift(fence) // The lower bound also applies when a deadline exists.
                fixture.clock.advance(1)
                assertCurrent(fence)
                fixture.clock.advance(99)
                assertCurrent(fence)
                fixture.clock.advance(1)
                assertEquals(expires, fixture.clock.millis())
                assertClockDrift(fence) // The expiration endpoint is exclusive.
            }

            val expired = fixture.store.capture(fixture.binding)
            assertEquals(expires, expired.capturedAtEpochMillis)
            assertEquals(active.shared, expired.shared)
            assertEquals(active.local, expired.local)
            assertNotEquals(active.policyDigest, expired.policyDigest)
            assertEquals("expired", expired.suppressions.single().activity)
            assertNull(expired.validUntilEpochMillis)
            fixture.store.withCurrentConfig(fixture.binding, expired.revisionToken) { fence ->
                assertCurrent(fence)
                fixture.clock.advance(-1)
                assertClockDrift(fence) // An expired snapshot has no future deadline, but still has a lower bound.
                assertEquals(expired.revisionToken, fence.current.revisionToken)
                assertEquals("expired", fence.current.suppressions.single().activity)
            }

            // A new capture evaluates its own instant; expiration is not a permanent mutation/latch.
            val recaptured = fixture.store.capture(fixture.binding)
            assertEquals(expires - 1, recaptured.capturedAtEpochMillis)
            assertEquals(active.revisionToken, recaptured.revisionToken)
            assertEquals(expires, recaptured.validUntilEpochMillis)
            fixture.store.withCurrentConfig(fixture.binding, expired.revisionToken) { fence ->
                assertFalse(fence.matches)
                assertEquals(listOf("effective_policy_changed"), fence.reasons)
                fixture.clock.advance(-1)
                assertFalse(fence.matches)
                assertEquals(listOf("effective_policy_changed", "policy_expiration_or_clock_drift"), fence.reasons)
            }
            fixture.store.withCurrentConfig(fixture.binding, recaptured.revisionToken) { assertCurrent(it) }
            assertContentEquals(before, Files.readAllBytes(policyFile))
            val finding = fixture.finding()
            assertEquals(PolicyDisposition.ACCEPTED,
                fixture.store.explain(active, fixture.input(active, finding), finding).disposition)
            assertEquals(PolicyDisposition.OPEN,
                fixture.store.explain(expired, fixture.input(expired, finding), finding).disposition)
        }
    }

    @Test fun restoringActiveAndExpiredReviewsAfterRollbackPreservesBothCapturedPolicyAccountings(): Unit = ConfigFixture().use { fixture ->
        val expires = fixture.clock.millis() + 100
        val risk = fixture.finding()
        val baselineFinding = fixture.finding(rule = OTHER_RULE)
        val suppression = fixture.suppress(expiresAt = expires)
        val baseline = BaselineSpec("review-baseline",
            fixture.store.findingSelector(suppression, fixture.input(suppression, baselineFinding), baselineFinding),
            "Explicit owner baseline for the other finding", expires)
        val active = fixture.mutate(ConfigAction.AddBaseline(baseline), ConfigLayer.LOCAL)
        val activeRiskInput = fixture.input(active, risk)
        val activeBaselineInput = fixture.input(active, baselineFinding)
        val activeArchive = fixture.store.exportSnapshot(active)
        val paths = fixture.store.storagePaths(fixture.binding)
        val sharedBytes = Files.readAllBytes(paths.shared)
        val localBytes = Files.readAllBytes(paths.local)
        assertEquals(PolicyDisposition.ACCEPTED, fixture.store.explain(active, activeRiskInput, risk).disposition)
        assertEquals(PolicyDisposition.BASELINED, fixture.store.explain(active, activeBaselineInput, baselineFinding).disposition)

        fixture.clock.advance(100)
        val expired = fixture.store.capture(fixture.binding)
        val expiredRiskInput = fixture.input(expired, risk)
        val expiredBaselineInput = fixture.input(expired, baselineFinding)
        val expiredArchive = fixture.store.exportSnapshot(expired)
        assertNotEquals(active.policyDigest, expired.policyDigest)
        assertNull(expired.validUntilEpochMillis)
        assertEquals(PolicyDisposition.OPEN, fixture.store.explain(expired, expiredRiskInput, risk).disposition)
        assertEquals(PolicyDisposition.OPEN, fixture.store.explain(expired, expiredBaselineInput, baselineFinding).disposition)
        assertEquals(PolicyDisposition.ACCEPTED, fixture.store.explain(active, activeRiskInput, risk).disposition)
        assertEquals(PolicyDisposition.BASELINED, fixture.store.explain(active, activeBaselineInput, baselineFinding).disposition)

        fixture.clock.advance(-50)
        val reopened = ConfigStore(fixture.root.resolve("state"), TEST_ALIASES, clock = fixture.clock)
        val restoredActive = reopened.restoreSnapshot(activeArchive, active.revisionToken)
        val restoredExpired = reopened.restoreSnapshot(expiredArchive, expired.revisionToken)
        for ((original, restored) in listOf(active to restoredActive, expired to restoredExpired)) {
            assertEquals(original.capturedAtEpochMillis, restored.capturedAtEpochMillis)
            assertEquals(original.revisionToken, restored.revisionToken)
            assertEquals(original.validUntilEpochMillis, restored.validUntilEpochMillis)
            assertEquals(original.suppressions, restored.suppressions)
            assertEquals(original.baselines, restored.baselines)
            assertEquals(fixture.store.exportSnapshot(original), reopened.exportSnapshot(restored))
        }
        assertEquals(PolicyDisposition.ACCEPTED, reopened.explain(restoredActive, activeRiskInput, risk).disposition)
        assertEquals(PolicyDisposition.BASELINED, reopened.explain(restoredActive, activeBaselineInput, baselineFinding).disposition)
        assertEquals(PolicyDisposition.OPEN, reopened.explain(restoredExpired, expiredRiskInput, risk).disposition)
        assertEquals(PolicyDisposition.OPEN, reopened.explain(restoredExpired, expiredBaselineInput, baselineFinding).disposition)
        assertTrue(restoredExpired.capturedAtEpochMillis > fixture.clock.millis(),
            "Trusted recovery must preserve the expired review's original capture, not today's clock")

        val current = reopened.capture(fixture.binding)
        assertEquals(fixture.clock.millis(), current.capturedAtEpochMillis)
        assertEquals(active.revisionToken, current.revisionToken)
        assertEquals(active.shared, current.shared)
        assertEquals(active.local, current.local)
        // Neither recovery nor a newly active policy may relabel the expired review's immutable input.
        assertEquals(PolicyDisposition.OPEN, reopened.explain(current, expiredRiskInput, risk).disposition)
        assertEquals(PolicyDisposition.OPEN, reopened.explain(current, expiredBaselineInput, baselineFinding).disposition)
        assertEquals(PolicyDisposition.OPEN, reopened.explain(restoredExpired, activeRiskInput, risk).disposition)
        assertEquals(PolicyDisposition.OPEN, reopened.explain(restoredExpired, activeBaselineInput, baselineFinding).disposition)
        assertContentEquals(sharedBytes, Files.readAllBytes(paths.shared))
        assertContentEquals(localBytes, Files.readAllBytes(paths.local))
    }

    private fun assertCurrent(fence: ConfigFenceCheck) {
        assertTrue(fence.matches)
        assertEquals(emptyList(), fence.reasons)
    }

    private fun assertClockDrift(fence: ConfigFenceCheck) {
        assertFalse(fence.matches)
        assertEquals(listOf("policy_expiration_or_clock_drift"), fence.reasons)
    }
}

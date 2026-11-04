package dev.ktchecker.config

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HistoryTest {
    @Test fun deterministicPropertyStyleOwnerHistoriesAgreeWithAnIndependentPolicyModel(): Unit {
        for (seed in 0..4) ConfigFixture().use { fixture ->
            val random = Random(seed)
            val shared = mutableMapOf<String, SuppressionSpec>()
            val local = mutableMapOf<String, SuppressionSpec>()
            val masks = mutableSetOf<String>()
            var sharedEnabled: Boolean? = null
            var localEnabled: Boolean? = null
            var sharedRevision = 0L
            var localRevision = 0L
            var current = fixture.store.capture(fixture.binding)
            val historical = mutableListOf(current)
            val finding = fixture.finding()

            fun expected(): PolicyDisposition {
                if ((localEnabled ?: sharedEnabled) == false) return PolicyDisposition.DISABLED
                val now = fixture.clock.millis()
                val localMatch = local.values.filter { it.expiresAtEpochMillis?.let { deadline -> now < deadline } ?: true }.minByOrNull { it.id }
                val sharedMatch = shared.values.filter { it.id !in masks && it.id !in local &&
                    (it.expiresAtEpochMillis?.let { deadline -> now < deadline } ?: true) }.minByOrNull { it.id }
                val chosen = localMatch ?: sharedMatch ?: return PolicyDisposition.OPEN
                return if (chosen.reason == SuppressionReason.ACCEPTED_RISK) PolicyDisposition.ACCEPTED else PolicyDisposition.SUPPRESSED
            }

            repeat(80) { step ->
                val layer = if (random.nextBoolean()) ConfigLayer.SHARED else ConfigLayer.LOCAL
                val map = if (layer == ConfigLayer.SHARED) shared else local
                val id = "record-${random.nextInt(6)}"
                val action: ConfigAction? = when (random.nextInt(8)) {
                    0, 1 -> if (id !in map) {
                        val spec = SuppressionSpec(id, PolicySelector.Rule(RULE), SuppressionScope.Project,
                            if (random.nextBoolean()) SuppressionReason.ACCEPTED_RISK else SuppressionReason.REPORTED_FALSE_POSITIVE,
                            "Explicit model-check history seed $seed step $step", if (random.nextBoolean()) fixture.clock.millis() + random.nextLong(1, 50) else null)
                        map[id] = spec
                        ConfigAction.AddSuppression(spec)
                    } else null
                    2 -> map.keys.sorted().randomOrNull(random)?.let { key -> map.remove(key); ConfigAction.RemoveSuppression(key) }
                    3 -> if (layer == ConfigLayer.LOCAL) shared.keys.filterNot { it in masks }.randomOrNull(random)?.let { key ->
                        masks += key; ConfigAction.MaskSharedSuppression(key)
                    } else null
                    4 -> if (layer == ConfigLayer.LOCAL) masks.sorted().randomOrNull(random)?.let { key ->
                        masks -= key; ConfigAction.UnmaskSharedSuppression(key)
                    } else null
                    5 -> {
                        val enabled = random.nextBoolean()
                        val old = if (layer == ConfigLayer.SHARED) sharedEnabled else localEnabled
                        if (old == enabled) null else {
                            if (layer == ConfigLayer.SHARED) sharedEnabled = enabled else localEnabled = enabled
                            ConfigAction.SetRuleEnabled(RULE, enabled)
                        }
                    }
                    6 -> {
                        val old = if (layer == ConfigLayer.SHARED) sharedEnabled else localEnabled
                        if (old == null) null else {
                            if (layer == ConfigLayer.SHARED) sharedEnabled = null else localEnabled = null
                            ConfigAction.ResetRuleOverride(RULE)
                        }
                    }
                    else -> null
                }
                if (action != null) {
                    val oldToken = current.revisionToken
                    current = fixture.mutate(action, layer)
                    if (layer == ConfigLayer.SHARED) sharedRevision++ else localRevision++
                    assertNotEquals(oldToken.policyDigest, current.policyDigest, "Every authorized config change invalidates identity")
                    historical += current
                }
                if (random.nextBoolean()) fixture.clock.advance(random.nextLong(1, 15))
                current = fixture.store.capture(fixture.binding)
                assertEquals(sharedRevision, current.shared.revision.revision)
                assertEquals(localRevision, current.local.revision.revision)
                assertEquals(shared.values.sortedBy { it.id }, current.shared.suppressions.map { it.spec })
                assertEquals(local.values.sortedBy { it.id }, current.local.suppressions.map { it.spec })
                assertEquals(masks.sorted(), current.local.suppressionMasks)
                assertEquals(expected(), fixture.store.explain(current, fixture.input(current, finding), finding).disposition, "seed=$seed step=$step")
                assertEquals(sharedRevision.toInt(), current.shared.auditEvents.size)
                assertEquals(localRevision.toInt(), current.local.auditEvents.size)

                if (step % 10 == 0) {
                    val stale = historical.firstOrNull { it.revisionToken != current.revisionToken }
                    if (stale != null) {
                        val request = fixture.request(ConfigAction.SetRuleEnabled(OTHER_RULE, false), expected = stale.revisionToken)
                        val permit = fixture.authority.issueForExplicitOwnerAction(OWNER, request, fixture.clock.instant())
                        assertFailsWith<ConfigConflictException> { fixture.store.mutate(request, permit) }
                        assertEquals(current.revisionToken, fixture.store.capture(fixture.binding).revisionToken)
                    }
                    val reopened = ConfigStore(fixture.root.resolve("state"), TEST_ALIASES, clock = fixture.clock)
                    assertEquals(current.revisionToken, reopened.capture(fixture.binding).revisionToken)
                    val archive = fixture.store.exportSnapshot(current)
                    val restored = reopened.restoreSnapshot(archive, current.revisionToken)
                    assertEquals(current.revisionToken, restored.revisionToken)
                }
            }
        }
    }

    @Test fun activeReviewPolicyNeverChangesInPlaceAndRemovingSuppressionDoesNotResolveIt(): Unit = ConfigFixture().use { fixture ->
        val accepted = fixture.suppress()
        val finding = fixture.finding()
        val frozenInput = fixture.input(accepted, finding)
        val prior = fixture.store.explain(accepted, frozenInput, finding)
        assertEquals(PolicyDisposition.ACCEPTED, prior.disposition)
        val current = fixture.mutate(ConfigAction.RemoveSuppression("risk"))
        assertNotEquals(accepted.policyDigest, current.policyDigest)
        assertEquals(PolicyDisposition.ACCEPTED, fixture.store.explain(accepted, frozenInput, finding).disposition)
        assertEquals(PolicyDisposition.OPEN, fixture.store.explain(current, fixture.input(current, finding), finding).disposition)
        assertEquals(PolicyDisposition.OPEN, fixture.store.explain(current, frozenInput, finding).disposition)
        fixture.store.withCurrentConfig(fixture.binding, accepted.revisionToken) { assertFalse(it.matches) }
        fixture.store.withCurrentConfig(fixture.binding, current.revisionToken) { assertTrue(it.matches) }
    }
}

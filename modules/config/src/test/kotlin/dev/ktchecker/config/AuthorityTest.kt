package dev.ktchecker.config

import dev.ktchecker.contracts.ClientIdentity
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AuthorityTest {
    @Test fun reviewClientsDoNotAcquireOwnerAuthority(): Unit = ConfigFixture().use { fixture ->
        val request = fixture.request(ConfigAction.SetRuleEnabled(RULE, false))
        val model = ClientIdentity(OWNER.ownerId, "model-client", "model-session")
        assertFailsWith<ConfigAuthorizationException> { fixture.authority.issueForExplicitOwnerAction(model, request, fixture.clock.instant()) }
        assertFailsWith<ConfigAuthorizationException> { fixture.authority.issueForExplicitOwnerAction(OWNER.copy(sessionId = "reconnected-unregistered-session"), request, fixture.clock.instant()) }
        val permit = fixture.authority.issueForExplicitOwnerAction(OWNER, request, fixture.clock.instant())
        assertFailsWith<ConfigAuthorizationException> { fixture.store.mutate(request, permit.copy(principal = model)) }
        val denyByDefault = ConfigStore(fixture.root.resolve("state"), TEST_ALIASES, clock = fixture.clock)
        assertFailsWith<ConfigAuthorizationException> { denyByDefault.mutate(request, permit) }
        assertFalse(Files.exists(fixture.store.storagePaths(fixture.binding).shared))
    }

    @Test fun permitBindsEveryAuthorityAndPayloadDimension(): Unit = ConfigFixture().use { fixture ->
        val request = fixture.request(ConfigAction.SetRuleEnabled(RULE, false))
        val permit = fixture.authority.issueForExplicitOwnerAction(OWNER, request, fixture.clock.instant())
        val retargets = listOf(
            fixture.binding.copy(ownerId = "another-owner"), fixture.binding.copy(workspaceId = "another-project"),
            fixture.binding.copy(worktreeId = "another-worktree"), fixture.binding.copy(lineageId = "another-lineage"),
        ).map { request.copy(binding = it, expected = request.expected.copy(binding = it)) }
        val changes = retargets + listOf(
            request.copy(layer = ConfigLayer.LOCAL), request.copy(action = ConfigAction.SetRuleEnabled(OTHER_RULE, false)),
            request.copy(action = ConfigAction.SetRuleEnabled(RULE, true)), request.copy(requestId = UUID.randomUUID().toString()),
            request.copy(expected = request.expected.copy(policyDigest = Digests.sha256("another-policy"))),
            request.copy(expected = request.expected.copy(shared = LayerRevision(1, UUID.randomUUID().toString(), Digests.sha256("another-document")))),
        )
        changes.forEach { tampered -> assertFailsWith<ConfigAuthorizationException> { fixture.store.mutate(tampered, permit) } }
        assertFailsWith<ConfigAuthorizationException> { fixture.store.mutate(request, permit.copy(signature = "forged")) }
        assertEquals(0L, fixture.store.capture(fixture.binding).shared.revision.revision)
    }

    @Test fun evenAnEnrolledOwnerCannotRetargetAnExistingPermit(): Unit = ConfigFixture().use { fixture ->
        val other = fixture.binding.copy(lineageId = "branch-2")
        val authority = HmacOwnerAuthority(ByteArray(32) { it.toByte() }, listOf(OwnerSessionRegistration(OWNER, setOf(fixture.binding, other))))
        val store = ConfigStore(fixture.root.resolve("state"), TEST_ALIASES, authority, clock = fixture.clock)
        val request = fixture.request(ConfigAction.SetRuleEnabled(RULE, false))
        val permit = authority.issueForExplicitOwnerAction(OWNER, request, fixture.clock.instant())
        val retargeted = request.copy(binding = other, expected = store.capture(other).revisionToken)
        assertFailsWith<ConfigAuthorizationException> { store.mutate(retargeted, permit) }
    }

    @Test fun permitsExpireAndReplayHitsFullCas(): Unit = ConfigFixture().use { fixture ->
        val request = fixture.request(ConfigAction.SetRuleEnabled(RULE, false))
        val permit = fixture.authority.issueForExplicitOwnerAction(OWNER, request, fixture.clock.instant(), Duration.ofMillis(100))
        fixture.clock.advance(100)
        assertFailsWith<ConfigAuthorizationException> { fixture.store.mutate(request, permit) }
        val freshPermit = fixture.authority.issueForExplicitOwnerAction(OWNER, request, fixture.clock.instant())
        fixture.store.mutate(request, freshPermit)
        assertFailsWith<ConfigConflictException> { fixture.store.mutate(request, freshPermit) }
        assertEquals(1L, fixture.store.inspect(fixture.binding).shared.revision.revision)
    }

    @Test fun reasonsAreRequiredAndModelApprovedFlagsAreNotInTheSchema(): Unit = ConfigFixture().use { fixture ->
        val bad = fixture.request(ConfigAction.AddSuppression(SuppressionSpec("bad", PolicySelector.Rule(RULE), SuppressionScope.Project,
            SuppressionReason.REPORTED_FALSE_POSITIVE, " ")))
        val permit = fixture.authority.issueForExplicitOwnerAction(OWNER, bad, fixture.clock.instant())
        assertFailsWith<ConfigValidationException> { fixture.store.mutate(bad, permit) }
        val json = ContractJson.encodeToString(bad).dropLast(1) + ",\"approved\":true}"
        assertFails { ContractJson.decodeFromString<ConfigMutationRequest>(json) }
    }
}

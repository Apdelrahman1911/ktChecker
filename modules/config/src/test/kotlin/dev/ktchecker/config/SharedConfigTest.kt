package dev.ktchecker.config

import dev.ktchecker.contracts.ClientIdentity
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SharedConfigTest {
    @Test fun portableProjectPolicyNeedsExplicitOwnerImportAndExcludesLocalOverrides(): Unit = ConfigFixture().use { fixture ->
        fixture.suppress()
        val source = fixture.suppress(id = "private-local", layer = ConfigLayer.LOCAL)
        val portable = ContractJson.decodeFromString<SharedProjectConfig>(ContractJson.encodeToString(fixture.store.exportShared(source)))
        assertEquals(listOf("risk"), portable.suppressions.map { it.id })
        val teammate = ClientIdentity("teammate", "owner-cli", "teammate-session")
        val binding = fixture.binding.copy(ownerId = teammate.ownerId)
        val authority = HmacOwnerAuthority(ByteArray(32) { (it + 7).toByte() }, listOf(OwnerSessionRegistration(teammate, setOf(binding))))
        val store = ConfigStore(fixture.root.resolve("state"), TEST_ALIASES, authority, clock = fixture.clock)
        val empty = store.capture(binding)
        assertTrue(empty.shared.suppressions.isEmpty())
        val request = ConfigMutationRequest(binding, ConfigLayer.SHARED, empty.revisionToken, ConfigAction.ImportSharedConfig(portable), UUID.randomUUID().toString())
        val imported = store.mutate(request, authority.issueForExplicitOwnerAction(teammate, request, fixture.clock.instant()))
        assertEquals(1, imported.shared.suppressions.size)
        assertTrue(imported.local.suppressions.isEmpty())
        assertEquals(teammate, imported.shared.suppressions.single().createdBy)
        val finding = fixture.finding()
        val input = fixture.input(imported, finding).let { it.copy(snapshot = it.snapshot.copy(ownerId = teammate.ownerId)) }
        assertEquals(PolicyDisposition.ACCEPTED, store.explain(imported, input, finding).disposition)
        assertNotEquals(source.policyDigest, imported.policyDigest)
    }

    @Test fun importCannotBeRetargetedOrMutatedAfterAnOwnerSignsItsPayload(): Unit = ConfigFixture().use { fixture ->
        val mutable = mutableListOf(SuppressionSpec("risk", PolicySelector.Rule(RULE), SuppressionScope.Project,
            SuppressionReason.ACCEPTED_RISK, "Only this exact policy was approved"))
        val request = fixture.request(ConfigAction.ImportSharedConfig(SharedProjectConfig(suppressions = mutable)))
        val permit = fixture.authority.issueForExplicitOwnerAction(OWNER, request, fixture.clock.instant())
        mutable += SuppressionSpec("other-risk", PolicySelector.Rule(OTHER_RULE), SuppressionScope.Project,
            SuppressionReason.ACCEPTED_RISK, "Unapproved additional policy")
        assertFailsWith<ConfigAuthorizationException> { fixture.store.mutate(request, permit) }
        assertFalse(java.nio.file.Files.exists(fixture.store.storagePaths(fixture.binding).shared))
    }

    @Test fun importIsOneLayerTransactionAndCannotDisableCompilerChecks(): Unit = ConfigFixture().use { fixture ->
        val original = fixture.suppress()
        val unsafe = fixture.request(ConfigAction.ImportSharedConfig(SharedProjectConfig(ruleSettings = listOf(RuleSetting(COMPILE_RULE, false)))))
        assertFailsWith<ConfigValidationException> { fixture.store.mutate(unsafe, fixture.authority.issueForExplicitOwnerAction(OWNER, unsafe, fixture.clock.instant())) }
        assertEquals(original.revisionToken, fixture.store.capture(fixture.binding).revisionToken)
        val local = fixture.request(ConfigAction.ImportSharedConfig(SharedProjectConfig()), ConfigLayer.LOCAL)
        assertFailsWith<ConfigValidationException> { fixture.store.mutate(local, fixture.authority.issueForExplicitOwnerAction(OWNER, local, fixture.clock.instant())) }
        assertEquals(original.revisionToken, fixture.store.capture(fixture.binding).revisionToken)
    }

    @Test fun missingOrChangedRuleImplementationNeverInheritsNarrowPolicy(): Unit = ConfigFixture().use { fixture ->
        val before = fixture.store.capture(fixture.binding)
        val finding = fixture.finding()
        val selector = fixture.store.findingSelector(before, fixture.input(before, finding), finding)
        fixture.suppress(selector = selector)
        val unversioned = ConfigStore(fixture.root.resolve("state"), RuleAliases.identities(listOf(RULE, OTHER_RULE, COMPILE_RULE)), clock = fixture.clock)
        val missing = unversioned.capture(fixture.binding)
        assertFailsWith<ConfigValidationException> { unversioned.findingSelector(missing, fixture.input(missing, finding), finding) }
        assertEquals(PolicyDisposition.OPEN, unversioned.explain(missing, fixture.input(missing, finding), finding).disposition)
        val changed = ConfigStore(fixture.root.resolve("state"), RuleAliases(listOf(PolicyRule(RULE,
            implementationDigest = Digests.sha256("provider-upgrade-with-new-rule-implementation")))), clock = fixture.clock)
        val changedSnapshot = changed.capture(fixture.binding)
        assertEquals(PolicyDisposition.OPEN, changed.explain(changedSnapshot, fixture.input(changedSnapshot, finding), finding).disposition)
    }

    @Test fun ruleScopedFileRangeAndSymbolAcceptancePinsTheOriginalImplementation(): Unit {
        val verifier = FindingIdentityVerifier { binding, input, finding, rule -> testIdentity(binding, input, finding, rule) }
        for (scopeKind in listOf("file", "range", "symbol")) ConfigFixture(identityVerifier = verifier).use { fixture ->
            val finding = fixture.finding()
            val before = fixture.store.capture(fixture.binding)
            val identity = fixture.store.findingSelector(before, fixture.input(before, finding), finding).anchor.verifiedIdentity!!
            val scope = when (scopeKind) {
                "file" -> SuppressionScope.File(finding.location.path, finding.location.fileDigest)
                "range" -> SuppressionScope.Range(finding.location.path, finding.location.fileDigest, 1, 0, 1, 3)
                else -> SuppressionScope.Symbol(identity)
            }
            val accepted = fixture.suppress(scope = scope)
            assertEquals(TEST_ALIASES.requireRule(RULE).implementationDigest,
                (accepted.shared.suppressions.single().spec.selector as PolicySelector.Rule).ruleIdentityDigest)
            assertEquals(PolicyDisposition.ACCEPTED, fixture.store.explain(accepted, fixture.input(accepted, finding), finding).disposition)
            val path = fixture.store.storagePaths(fixture.binding).shared
            val raw = java.nio.file.Files.readString(path)
            val document = ContractJson.decodeFromString<PolicyDocument>(raw)
            val unbound = document.copy(suppressions = document.suppressions.map { record ->
                record.copy(spec = record.spec.copy(selector = PolicySelector.Rule(RULE)))
            })
            java.nio.file.Files.writeString(path, ContractJson.encodeToString(unbound))
            val unboundSnapshot = fixture.store.capture(fixture.binding)
            val unboundExplanation = fixture.store.explain(unboundSnapshot, fixture.input(unboundSnapshot, finding), finding)
            assertEquals(PolicyDisposition.OPEN, unboundExplanation.disposition)
            assertEquals("rule_implementation_identity_unavailable", unboundExplanation.candidates.single().state)
            java.nio.file.Files.writeString(path, raw)
            val upgradedAliases = RuleAliases(TEST_ALIASES.allRules().map { rule ->
                if (rule.canonicalId == RULE) rule.copy(implementationDigest = Digests.sha256("upgraded-rule-implementation")) else rule
            })
            val upgraded = ConfigStore(fixture.root.resolve("state"), upgradedAliases, fixture.authority,
                identityVerifier = verifier, clock = fixture.clock)
            val current = upgraded.capture(fixture.binding)
            val explanation = upgraded.explain(current, fixture.input(current, finding), finding)
            assertEquals(PolicyDisposition.OPEN, explanation.disposition, scopeKind)
            assertEquals("rule_implementation_changed", explanation.candidates.single().state)
            val importRequest = ConfigMutationRequest(fixture.binding, ConfigLayer.SHARED, current.revisionToken,
                ConfigAction.ImportSharedConfig(fixture.store.exportShared(accepted)), UUID.randomUUID().toString())
            assertFailsWith<ConfigValidationException> {
                upgraded.mutate(importRequest, fixture.authority.issueForExplicitOwnerAction(OWNER, importRequest, fixture.clock.instant()))
            }
            assertEquals(current.revisionToken, upgraded.capture(fixture.binding).revisionToken)
        }
    }
}

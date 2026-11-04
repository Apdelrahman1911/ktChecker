package dev.ktchecker.config

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.RuleContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.*

class TargetIdentityTest {
    @Test fun emptyTargetIsLiteralAndOnlyNullSourceSetTargetIsAWildcard() {
        val scopes = listOf(
            SuppressionScope.Target("") to setOf(""),
            SuppressionScope.Target("jvm") to setOf("jvm"),
            SuppressionScope.SourceSet(":app", "main", "") to setOf(""),
            SuppressionScope.SourceSet(":app", "main", null) to setOf("", "jvm", "iosArm64"),
        )
        scopes.forEach { (scope, matchedTargets) -> ConfigFixture().use { fixture ->
            val snapshot = fixture.suppress(scope = scope)
            listOf("", "jvm", "iosArm64").forEach { target ->
                val finding = fixture.finding(context = TEST_CONTEXT.copy(target = target))
                assertEquals(if (target in matchedTargets) PolicyDisposition.ACCEPTED else PolicyDisposition.OPEN,
                    fixture.store.explain(snapshot, fixture.input(snapshot, finding), finding).disposition)
            }
            if (scope is SuppressionScope.SourceSet) {
                listOf(TEST_CONTEXT.copy(module = ":other", target = ""),
                    TEST_CONTEXT.copy(sourceSet = "test", target = "")).forEach { context ->
                    val finding = fixture.finding(context = context)
                    assertEquals(PolicyDisposition.OPEN,
                        fixture.store.explain(snapshot, fixture.input(snapshot, finding), finding).disposition)
                }
            }
        } }
    }

    @Test fun emptyAndWildcardScopesSurviveReloadArchiveAndSharedImportUnchanged(): Unit = ConfigFixture().use { fixture ->
        val expected = mapOf(
            "empty-target" to SuppressionScope.Target(""),
            "empty-source-target" to SuppressionScope.SourceSet(":app", "main", ""),
            "wildcard-source-target" to SuppressionScope.SourceSet(":app", "main", null),
        )
        expected.forEach { (id, scope) -> fixture.suppress(id = id, scope = scope) }
        val captured = fixture.store.capture(fixture.binding)
        val reopened = ConfigStore(fixture.root.resolve("state"), TEST_ALIASES, clock = fixture.clock)
        val loaded = reopened.capture(fixture.binding)
        assertEquals(captured.revisionToken, loaded.revisionToken)
        assertEquals(captured.policyDigest, loaded.policyDigest)
        assertEquals(expected, loaded.shared.suppressions.associate { it.spec.id to it.spec.scope })
        val frozen = ContractJson.decodeFromString<FrozenConfig>(ContractJson.encodeToString(fixture.store.exportSnapshot(captured)))
        val restored = reopened.restoreSnapshot(frozen, captured.revisionToken)
        assertEquals(captured.policyDigest, restored.policyDigest)
        assertEquals(expected, restored.shared.suppressions.associate { it.spec.id to it.spec.scope })
        val shared = ContractJson.decodeFromString<SharedProjectConfig>(ContractJson.encodeToString(fixture.store.exportShared(captured)))
        ConfigFixture().use { recipient ->
            val imported = recipient.mutate(ConfigAction.ImportSharedConfig(shared))
            assertEquals(expected, imported.shared.suppressions.associate { it.spec.id to it.spec.scope })
        }
    }

    @Test fun emptyTargetExactFindingKeepsItsFingerprintAndDoesNotMatchNamedTarget(): Unit = ConfigFixture().use { fixture ->
        val before = fixture.store.capture(fixture.binding)
        val empty = fixture.finding(context = TEST_CONTEXT.copy(target = ""))
        val named = fixture.finding(context = TEST_CONTEXT)
        val exact = fixture.store.findingSelector(before, fixture.input(before, empty), empty)
        val namedSelector = fixture.store.findingSelector(before, fixture.input(before, named), named)
        assertNotEquals(exact.fingerprint, namedSelector.fingerprint)
        assertNotEquals(empty.context.id, named.context.id)
        assertNotEquals(fixture.input(before, empty).analysisKey(), fixture.input(before, named).analysisKey())
        val saved = fixture.suppress(selector = exact)
        val reopened = ConfigStore(fixture.root.resolve("state"), TEST_ALIASES, clock = fixture.clock)
        val loaded = reopened.capture(fixture.binding)
        assertEquals(saved.revisionToken, loaded.revisionToken)
        val stored = loaded.shared.suppressions.single().spec.selector as PolicySelector.ExactFinding
        assertEquals(exact, stored)
        assertEquals("", stored.anchor.context.target)
        assertEquals(PolicyDisposition.ACCEPTED, reopened.explain(loaded, fixture.input(loaded, empty), empty).disposition)
        assertEquals(PolicyDisposition.OPEN, reopened.explain(loaded, fixture.input(loaded, named), named).disposition)
    }

    @Test fun emptyAndNullTargetsCannotReuseOneAnothersMutationPermit(): Unit = ConfigFixture().use { fixture ->
        val spec = SuppressionSpec("literal", PolicySelector.Rule(RULE), SuppressionScope.SourceSet(":app", "main", ""),
            SuppressionReason.ACCEPTED_RISK, "Explicit literal empty-target policy")
        val request = fixture.request(ConfigAction.AddSuppression(spec))
        val wildcard = request.copy(action = ConfigAction.AddSuppression(spec.copy(scope = SuppressionScope.SourceSet(":app", "main", null))))
        val named = request.copy(action = ConfigAction.AddSuppression(spec.copy(scope = SuppressionScope.SourceSet(":app", "main", "jvm"))))
        assertNotEquals(request.digest(), wildcard.digest())
        assertNotEquals(request.digest(), named.digest())
        val permit = fixture.authority.issueForExplicitOwnerAction(OWNER, request, fixture.clock.instant())
        listOf(wildcard, named).forEach { changed ->
            assertFailsWith<ConfigAuthorizationException> { fixture.store.mutate(changed, permit) }
        }
        assertEquals(0L, fixture.store.capture(fixture.binding).shared.revision.revision)
        val saved = fixture.store.mutate(request, permit)
        assertEquals(spec.scope, saved.shared.suppressions.single().spec.scope)
    }

    @Test fun whitespaceControlAndOverlongTargetsRemainInvalidAtEveryPolicySite(): Unit = ConfigFixture().use { fixture ->
        val before = fixture.store.capture(fixture.binding)
        validateTargetIdentifier("")
        validateTargetIdentifier("x".repeat(256))
        val invalid = listOf(" ", "\t", "\n", "\u2003", "\u0000", "jvm\u007f", "\u0085", "x".repeat(257))
        invalid.forEach { target ->
            listOf(SuppressionScope.Target(target), SuppressionScope.SourceSet(":app", "main", target)).forEach { scope ->
                assertFailsWith<ConfigValidationException> { fixture.suppress(scope = scope) }
            }
            val finding = fixture.finding(context = TEST_CONTEXT.copy(target = target))
            val selector = fixture.store.findingSelector(before, fixture.input(before, finding), finding)
            assertFailsWith<ConfigValidationException> { fixture.suppress(selector = selector) }
        }
        assertEquals(before.revisionToken, fixture.store.capture(fixture.binding).revisionToken)
    }

    @Test fun otherContextAndScopeIdentifiersStillRejectBlankValues(): Unit = ConfigFixture().use { fixture ->
        val before = fixture.store.capture(fixture.binding)
        val emptyTarget = TEST_CONTEXT.copy(target = "")
        listOf(emptyTarget.copy(module = ""), emptyTarget.copy(sourceSet = ""), emptyTarget.copy(compilation = "")).forEach { context ->
            val finding = fixture.finding(context = context)
            val selector = fixture.store.findingSelector(before, fixture.input(before, finding), finding)
            assertFailsWith<ConfigValidationException> { fixture.suppress(selector = selector) }
        }
        listOf(SuppressionScope.Module(""), SuppressionScope.SourceSet("", "main", ""),
            SuppressionScope.SourceSet(":app", "", "")).forEach { scope ->
            assertFailsWith<ConfigValidationException> { fixture.suppress(scope = scope) }
        }
        assertFailsWith<ConfigValidationException> { validateIdentifier("", "other identity") }
        assertEquals(before.revisionToken, fixture.store.capture(fixture.binding).revisionToken)
    }

    @Test fun nullIsNotAnAliasForAnEmptyNonnullableTarget() {
        val context = RuleContext(target = "")
        val encodedContext = ContractJson.encodeToString(context)
        assertEquals(context, ContractJson.decodeFromString<RuleContext>(encodedContext))
        assertFails { ContractJson.decodeFromString<RuleContext>(encodedContext.replace("\"target\":\"\"", "\"target\":null")) }
        val scope: SuppressionScope = SuppressionScope.Target("")
        val encodedScope = ContractJson.encodeToString(scope)
        assertEquals(scope, ContractJson.decodeFromString<SuppressionScope>(encodedScope))
        assertFails { ContractJson.decodeFromString<SuppressionScope>(encodedScope.replace("\"target\":\"\"", "\"target\":null")) }
    }
}

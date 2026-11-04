package dev.ktchecker.config

import dev.ktchecker.contracts.Coverage
import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.Evidence
import dev.ktchecker.contracts.ProviderResult
import dev.ktchecker.contracts.Verification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MatchingTest {
    @Test fun canonicalAliasesMatchAndReasonsRemainDistinct(): Unit = ConfigFixture().use { fixture ->
        var snapshot = fixture.suppress(selector = PolicySelector.Rule("upstream/demo"))
        val finding = fixture.finding(rule = "old.rule")
        var input = fixture.input(snapshot, finding)
        assertEquals(RULE, snapshot.shared.suppressions.single().spec.selector.ruleId)
        assertEquals(PolicyDisposition.ACCEPTED, fixture.store.explain(snapshot, input, finding).disposition)
        assertEquals(fixture.store.findingSelector(snapshot, input, finding).fingerprint,
            fixture.store.findingSelector(snapshot, input, finding.copy(ruleId = RULE)).fingerprint)
        fixture.mutate(ConfigAction.RemoveSuppression("risk"))
        snapshot = fixture.suppress(reason = SuppressionReason.REPORTED_FALSE_POSITIVE)
        input = fixture.input(snapshot, finding)
        assertEquals(PolicyDisposition.SUPPRESSED, fixture.store.explain(snapshot, input, finding).disposition)
        assertEquals(PolicyDisposition.OPEN, fixture.store.explain(snapshot, input, finding.copy(ruleId = OTHER_RULE)).disposition)
        assertFailsWith<ConfigValidationException> { fixture.suppress(id = "unknown", selector = PolicySelector.Rule("wildcard.*")) }
    }

    @Test fun broadScopesMatchOnlyTheirExplicitTargetModuleAndSourceSet(): Unit {
        val scopes = listOf(
            SuppressionScope.Project to true,
            SuppressionScope.Target("jvm") to true, SuppressionScope.Target("iosArm64") to false,
            SuppressionScope.Module(":app") to true, SuppressionScope.Module(":other") to false,
            SuppressionScope.SourceSet(":app", "main", "jvm") to true,
            SuppressionScope.SourceSet(":app", "test") to false,
            SuppressionScope.SourceSet(":other", "main") to false,
        )
        scopes.forEach { (scope, matches) -> ConfigFixture().use { fixture ->
            val snapshot = fixture.suppress(scope = scope)
            val finding = fixture.finding()
            assertEquals(if (matches) PolicyDisposition.ACCEPTED else PolicyDisposition.OPEN,
                fixture.store.explain(snapshot, fixture.input(snapshot, finding), finding).disposition)
        } }
    }

    @Test fun exactFindingFileAndRangeDoNotSurviveUnverifiedCodeMovement(): Unit {
        for (scopeKind in listOf("finding", "file", "range")) ConfigFixture().use { fixture ->
            val before = fixture.store.capture(fixture.binding)
            val finding = fixture.finding().copy(symbolId = "name-is-not-proof", semanticDigest = Digests.sha256("unverified-material"))
            val selector = if (scopeKind == "finding") fixture.store.findingSelector(before, fixture.input(before, finding), finding) else PolicySelector.Rule(RULE)
            val scope = when (scopeKind) {
                "file" -> SuppressionScope.File(finding.location.path, finding.location.fileDigest)
                "range" -> SuppressionScope.Range(finding.location.path, finding.location.fileDigest, 1, 0, 1, 3)
                else -> SuppressionScope.Project
            }
            val snapshot = fixture.suppress(selector = selector, scope = scope)
            assertEquals(PolicyDisposition.ACCEPTED, fixture.store.explain(snapshot, fixture.input(snapshot, finding), finding).disposition)
            val moved = fixture.finding(code = "\nfun example() = 1", line = 2).copy(symbolId = finding.symbolId, semanticDigest = finding.semanticDigest,
                fingerprint = finding.fingerprint)
            val renamed = finding.copy(location = finding.location.copy(path = "src/Renamed.kt"))
            val changed = fixture.finding(code = "fun example() = dangerous()")
            for (current in listOf(moved, renamed, changed)) {
                assertEquals(PolicyDisposition.OPEN, fixture.store.explain(snapshot, fixture.input(snapshot, current), current).disposition, scopeKind)
            }
        }
    }

    @Test fun rangeIsContainedAndEndExclusiveAndFileBytesAreSignificant(): Unit = ConfigFixture().use { fixture ->
        val finding = fixture.finding()
        val snapshot = fixture.suppress(scope = SuppressionScope.Range(finding.location.path, finding.location.fileDigest, 1, 1, 1, 2))
        assertEquals(PolicyDisposition.OPEN, fixture.store.explain(snapshot, fixture.input(snapshot, finding), finding).disposition)
        val point = finding.copy(location = finding.location.copy(column = 1, endColumn = 1))
        assertEquals(PolicyDisposition.ACCEPTED, fixture.store.explain(snapshot, fixture.input(snapshot, point), point).disposition)
        val endPoint = finding.copy(location = finding.location.copy(column = 2, endColumn = 2))
        assertEquals(PolicyDisposition.OPEN, fixture.store.explain(snapshot, fixture.input(snapshot, endPoint), endPoint).disposition)
        assertFailsWith<ConfigValidationException> { fixture.suppress(id = "traversal", scope = SuppressionScope.File("../outside.kt", finding.location.fileDigest)) }
    }

    @Test fun verifiedEvidenceIdentitySurvivesMovementButNotMaterialOrOccurrenceChanges(): Unit {
        var evidence = Digests.sha256("evidence-1")
        var occurrence = "occurrence-1"
        var artifact = Digests.sha256("provider-1")
        val verifier = FindingIdentityVerifier { binding, input, finding, rule ->
            testIdentity(binding, input, finding, rule, material = finding.semanticDigest!!, evidence = evidence, evidenceKey = occurrence, artifact = artifact)
        }
        ConfigFixture(identityVerifier = verifier).use { fixture ->
            val before = fixture.store.capture(fixture.binding)
            val finding = fixture.finding().copy(symbolId = "verified-symbol-key", semanticDigest = Digests.sha256("stable-material"))
            val selector = fixture.store.findingSelector(before, fixture.input(before, finding), finding)
            val identity = selector.anchor.verifiedIdentity!!
            val snapshot = fixture.suppress(selector = selector, scope = SuppressionScope.Symbol(identity))
            val moved = fixture.finding(path = "src/Renamed.kt", code = "\n\nfun example() = 1", line = 3).copy(
                symbolId = finding.symbolId, semanticDigest = finding.semanticDigest)
            val movedInput = fixture.input(snapshot, moved)
            assertEquals(PolicyDisposition.ACCEPTED, fixture.store.explain(snapshot, movedInput, moved).disposition)
            assertEquals(selector.fingerprint, fixture.store.findingSelector(snapshot, movedInput, moved).fingerprint)
            val changed = moved.copy(semanticDigest = Digests.sha256("changed-body-or-bindings"))
            assertEquals(PolicyDisposition.OPEN, fixture.store.explain(snapshot, fixture.input(snapshot, changed), changed).disposition)
            evidence = Digests.sha256("materially-different-evidence")
            assertEquals(PolicyDisposition.OPEN, fixture.store.explain(snapshot, movedInput, moved).disposition)
            evidence = identity.evidenceDigest
            occurrence = "second-occurrence-same-symbol"
            assertEquals(PolicyDisposition.OPEN, fixture.store.explain(snapshot, movedInput, moved).disposition)
            occurrence = identity.evidenceKey
            artifact = Digests.sha256("different-provider-build")
            assertEquals(PolicyDisposition.OPEN, fixture.store.explain(snapshot, movedInput, moved).disposition)
        }
    }

    @Test fun aStoredSymbolCertificateCannotAuthorizeItselfWhenVerificationIsUnavailable(): Unit = ConfigFixture().use { fixture ->
        val before = fixture.store.capture(fixture.binding)
        val finding = fixture.finding()
        val input = fixture.input(before, finding)
        val declared = testIdentity(fixture.binding, input, finding, RULE)
        val snapshot = fixture.suppress(scope = SuppressionScope.Symbol(declared))
        val explanation = fixture.store.explain(snapshot, fixture.input(snapshot, finding), finding)
        assertEquals(PolicyDisposition.OPEN, explanation.disposition)
        assertEquals("identity_unverified", explanation.candidates.single().state)
    }

    @Test fun localPrecedenceMasksAndExpirationAreExplicit(): Unit = ConfigFixture().use { fixture ->
        fixture.suppress(id = "same", reason = SuppressionReason.ACCEPTED_RISK)
        val local = fixture.suppress(id = "same", reason = SuppressionReason.REPORTED_FALSE_POSITIVE, layer = ConfigLayer.LOCAL,
            expiresAt = fixture.clock.millis() + 100)
        val finding = fixture.finding()
        assertEquals(PolicyDisposition.SUPPRESSED, fixture.store.explain(local, fixture.input(local, finding), finding).disposition)
        fixture.clock.advance(100)
        val expired = fixture.store.capture(fixture.binding)
        assertEquals(PolicyDisposition.OPEN, fixture.store.explain(expired, fixture.input(expired, finding), finding).disposition)
        assertTrue(expired.suppressions.any { it.activity == "overridden" })
        fixture.mutate(ConfigAction.RemoveSuppression("same"), ConfigLayer.LOCAL)
        var snapshot = fixture.store.capture(fixture.binding)
        assertEquals(PolicyDisposition.ACCEPTED, fixture.store.explain(snapshot, fixture.input(snapshot, finding), finding).disposition)
        snapshot = fixture.mutate(ConfigAction.MaskSharedSuppression("same"), ConfigLayer.LOCAL)
        assertEquals(PolicyDisposition.OPEN, fixture.store.explain(snapshot, fixture.input(snapshot, finding), finding).disposition)
        snapshot = fixture.mutate(ConfigAction.UnmaskSharedSuppression("same"), ConfigLayer.LOCAL)
        assertEquals(PolicyDisposition.ACCEPTED, fixture.store.explain(snapshot, fixture.input(snapshot, finding), finding).disposition)
    }

    @Test fun ruleDisablingIsNotFixedAndLocalEnablingOverridesShared(): Unit = ConfigFixture().use { fixture ->
        val finding = fixture.finding()
        var snapshot = fixture.mutate(ConfigAction.SetRuleEnabled("old.rule", false))
        assertEquals(PolicyDisposition.DISABLED, fixture.store.explain(snapshot, fixture.input(snapshot, finding), finding).disposition)
        snapshot = fixture.mutate(ConfigAction.SetRuleEnabled(RULE, true), ConfigLayer.LOCAL)
        assertEquals(PolicyDisposition.OPEN, fixture.store.explain(snapshot, fixture.input(snapshot, finding), finding).disposition)
        snapshot = fixture.mutate(ConfigAction.ResetRuleOverride(RULE), ConfigLayer.LOCAL)
        assertEquals(PolicyDisposition.DISABLED, fixture.store.explain(snapshot, fixture.input(snapshot, finding), finding).disposition)
        snapshot = fixture.mutate(ConfigAction.ResetRuleOverride(RULE))
        assertEquals(PolicyDisposition.OPEN, fixture.store.explain(snapshot, fixture.input(snapshot, finding), finding).disposition)
        assertFailsWith<ConfigValidationException> { fixture.mutate(ConfigAction.SetRuleEnabled(COMPILE_RULE, false)) }
    }

    @Test fun noCompilerBuildOrExecutionFailureStatusIsSuppressed(): Unit = ConfigFixture().use { fixture ->
        val snapshot = fixture.suppress()
        val normal = fixture.finding()
        val failures = listOf(
            normal.copy(severity = "error", providerId = "kotlin-compiler", evidence = listOf(Evidence("compiler_diagnostic", "Compiler error"))),
            normal.copy(evidence = listOf(Evidence("execution_failure", "Execution failed"))),
            normal.copy(verification = listOf(Verification("build", "Build must succeed", state = "failed"))),
        )
        for (finding in failures) {
            val input = fixture.input(snapshot, finding)
            val result = ProviderResult("fixture", "failed", listOf(finding.copy(disposition = "baselined")),
                listOf(Coverage(RULE, TEST_CONTEXT, "failed", "Failure cannot be hidden")), reasons = listOf("worker/build failure"))
            val applied = fixture.store.apply(snapshot, input, result)
            assertEquals(PolicyDisposition.OPEN.wireValue, applied.findings.single().disposition)
            assertEquals(result.status, applied.status)
            assertEquals(result.coverage, applied.coverage)
            assertEquals(result.reasons, applied.reasons)
            assertFalse(applied.complete())
        }
        val result = ProviderResult("fixture", "unavailable", listOf(normal), listOf(Coverage(RULE, TEST_CONTEXT, "unavailable")))
        val applied = fixture.store.apply(snapshot, fixture.input(snapshot, normal), result)
        assertEquals("unavailable", applied.status)
        assertEquals(result.coverage, applied.coverage)
        assertFalse(applied.complete())
    }

    @Test fun policyDigestContextAndLineageAreRequiredAndEditableLabelsCannotClearFindings(): Unit = ConfigFixture().use { fixture ->
        val snapshot = fixture.suppress()
        val finding = fixture.finding()
        val input = fixture.input(snapshot, finding)
        val mismatches = listOf(
            input.copy(context = input.context.copy(configurationDigest = null)),
            input.copy(context = input.context.copy(configurationDigest = Digests.sha256("another-lineage-policy"))),
            input.copy(snapshot = input.snapshot.copy(ownerId = "another-owner")),
            input.copy(snapshot = input.snapshot.copy(workspaceId = "another-project")),
            input.copy(snapshot = input.snapshot.copy(worktreeId = "another-worktree")),
            input.copy(context = input.context.copy(scope = TEST_CONTEXT.copy(target = "iosArm64"))),
            input.copy(snapshot = input.snapshot.copy(files = emptyList())),
        )
        mismatches.forEach { mismatch ->
            assertEquals(PolicyDisposition.OPEN, fixture.store.explain(snapshot, mismatch, finding.copy(disposition = "fixed")).disposition)
        }
        val otherLineage = fixture.store.capture(fixture.binding.copy(lineageId = "another-lineage"))
        assertEquals(PolicyDisposition.OPEN, fixture.store.explain(otherLineage, input, finding).disposition)
        val empty = fixture.mutate(ConfigAction.RemoveSuppression("risk"))
        val emptyInput = fixture.input(empty, finding)
        for (label in listOf("fixed", "resolved", "baselined", "disabled", "accepted", "suppressed", "unavailable")) {
            assertEquals(PolicyDisposition.OPEN, fixture.store.explain(empty, emptyInput, finding.copy(disposition = label)).disposition)
        }
    }

    @Test fun onlyBoundTrustedLifecycleCertificatesPreserveFixedUnavailableAndBaselined(): Unit = ConfigFixture().use { fixture ->
        val snapshot = fixture.store.capture(fixture.binding)
        val finding = fixture.finding()
        val input = fixture.input(snapshot, finding)
        val fingerprint = fixture.store.findingSelector(snapshot, input, finding).fingerprint
        for (disposition in listOf(PolicyDisposition.FIXED, PolicyDisposition.UNAVAILABLE, PolicyDisposition.BASELINED)) {
            var certificate = VerifiedLifecycleDisposition("trusted-coverage-certificate", input.snapshot.snapshotId, snapshot.policyDigest,
                RULE, finding.providerId, fingerprint, disposition)
            val store = ConfigStore(fixture.root.resolve("state"), TEST_ALIASES, fixture.authority,
                lifecycleVerifier = FindingLifecycleVerifier { _, _, _, _ -> certificate }, clock = fixture.clock)
            assertEquals(disposition, store.explain(snapshot, input, finding).disposition)
            certificate = certificate.copy(policyDigest = Digests.sha256("unrelated-policy"))
            assertEquals(PolicyDisposition.OPEN, store.explain(snapshot, input, finding).disposition)
        }
    }

    @Test fun baselineRequiresExplicitOwnerMutationAndDoesNotBecomeFixed(): Unit = ConfigFixture().use { fixture ->
        val before = fixture.store.capture(fixture.binding)
        val finding = fixture.finding()
        val selector = fixture.store.findingSelector(before, fixture.input(before, finding), finding)
        val snapshot = fixture.mutate(ConfigAction.AddBaseline(BaselineSpec("baseline", selector, "Existing warning accepted as a baseline")))
        assertEquals(PolicyDisposition.BASELINED, fixture.store.explain(snapshot, fixture.input(snapshot, finding), finding).disposition)
        val changed = fixture.finding(code = "fun example() = newDefect()")
        assertEquals(PolicyDisposition.OPEN, fixture.store.explain(snapshot, fixture.input(snapshot, changed), changed).disposition)
    }

    @Test fun expirationInvalidatesPolicyWithoutInventingAMutationAndFencesRecheckTime(): Unit = ConfigFixture().use { fixture ->
        val frozen = fixture.suppress(expiresAt = fixture.clock.millis() + 100)
        val finding = fixture.finding()
        val frozenInput = fixture.input(frozen, finding)
        fixture.store.withCurrentConfig(fixture.binding, frozen.revisionToken) { check ->
            assertTrue(check.matches)
            fixture.clock.advance(100)
            assertFalse(check.matches)
        }
        val current = fixture.store.capture(fixture.binding)
        assertEquals(frozen.shared.revision, current.shared.revision)
        assertNotEquals(frozen.policyDigest, current.policyDigest)
        assertEquals(PolicyDisposition.ACCEPTED, fixture.store.explain(frozen, frozenInput, finding).disposition)
        assertEquals(PolicyDisposition.OPEN, fixture.store.explain(current, fixture.input(current, finding), finding).disposition)
        fixture.store.withCurrentConfig(fixture.binding, frozen.revisionToken) { assertFalse(it.matches) }
    }
}

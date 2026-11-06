package dev.ktchecker.config

import dev.ktchecker.contracts.AnalysisInput
import dev.ktchecker.contracts.AnalysisMetrics
import dev.ktchecker.contracts.ArtifactIdentity
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Coverage
import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.Evidence
import dev.ktchecker.contracts.Finding
import dev.ktchecker.contracts.ProviderResult
import dev.ktchecker.contracts.RawDiagnostic
import dev.ktchecker.contracts.SnapshotFile
import dev.ktchecker.contracts.SourceLocation
import dev.ktchecker.contracts.TrustedExecution
import dev.ktchecker.contracts.Verification
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure reassessment of synthetic retained input/results; no provider runs in these tests. */
class PolicyReassessmentTest {
    @Test fun acceptedRiskUsesNewPolicyWithoutRetaggingOrChangingTheOriginal(): Unit = ConfigFixture().use { fixture ->
        val original = fixture.store.capture(fixture.binding)
        val finding = fixture.finding(rule = "old.rule")
        val input = fixture.input(original, finding)
        val raw = ProviderResult(finding.providerId, "partial", listOf(finding))
        val oldView = fixture.store.apply(original, input, raw)
        val oldCapture = fixture.store.exportSnapshot(original)
        val current = fixture.suppress(selector = PolicySelector.Rule("upstream/demo"))
        val currentCapture = fixture.store.exportSnapshot(current)
        val inputDocument = ContractJson.encodeToString(input)
        val subject = fixture.store.prepareReassessment(input, oldCapture, currentCapture)

        assertNotEquals(original.policyDigest, current.policyDigest)
        assertEquals(inputDocument, ContractJson.encodeToString(subject.originalInput))
        assertEquals(input.analysisKey(), subject.originalInput.analysisKey())
        assertEquals(original.policyDigest, subject.originalInput.context.configurationDigest)
        assertEquals(oldCapture, subject.originalConfig)
        assertEquals(currentCapture, subject.currentConfig)
        val explanation = fixture.store.explainReassessment(subject, finding)
        assertEquals(PolicyDisposition.ACCEPTED, explanation.disposition)
        assertEquals(RULE, explanation.canonicalRuleId)
        assertEquals("risk", explanation.policyEntryId)
        assertEquals(raw.copy(findings = listOf(finding.copy(disposition = "accepted"))), fixture.store.applyReassessment(subject, raw))
        assertEquals(oldView, fixture.store.apply(original, input, raw))
        assertEquals(PolicyDisposition.OPEN, fixture.store.explain(current, input, finding).disposition,
            "Ordinary policy matching must still reject an old configuration digest")
        assertEquals(inputDocument, ContractJson.encodeToString(input))
        assertEquals("open", raw.findings.single().disposition)
        assertEquals(oldCapture, fixture.store.exportSnapshot(original))
        assertEquals(currentCapture, fixture.store.exportSnapshot(fixture.store.capture(fixture.binding)))
    }

    @Test fun reportedFalsePositiveIsSuppressedNotAcceptedOrFixed(): Unit = ConfigFixture().use { fixture ->
        val original = fixture.store.capture(fixture.binding)
        val finding = fixture.finding().copy(disposition = "fixed")
        val current = fixture.suppress(reason = SuppressionReason.REPORTED_FALSE_POSITIVE)
        val subject = fixture.store.subject(original, current, fixture.input(original, finding))
        val explanation = fixture.store.explainReassessment(subject, finding)
        assertEquals(PolicyDisposition.SUPPRESSED, explanation.disposition)
        assertEquals(finding.copy(disposition = "suppressed"), explanation.classifiedFinding())
        assertEquals("fixed", finding.disposition)
    }

    @Test fun byteBoundScopesAndExactFindingsWorkWithoutEitherLegacyVerifier(): Unit {
        for (kind in listOf("project", "target", "module", "source-set", "file", "range", "finding")) {
            ConfigFixture().use { fixture ->
                val original = fixture.store.capture(fixture.binding)
                val finding = fixture.finding().copy(symbolId = "provider-symbol-is-not-proof", semanticDigest = Digests.sha256("provider-material"))
                val input = fixture.input(original, finding)
                val selector = if (kind == "finding") fixture.store.findingSelector(original, input, finding) else PolicySelector.Rule(RULE)
                val location = finding.location
                val scope = when (kind) {
                    "target" -> SuppressionScope.Target(finding.context.target)
                    "module" -> SuppressionScope.Module(finding.context.module)
                    "source-set" -> SuppressionScope.SourceSet(finding.context.module, finding.context.sourceSet, finding.context.target)
                    "file" -> SuppressionScope.File(location.path, location.fileDigest)
                    "range" -> SuppressionScope.Range(location.path, location.fileDigest, location.line, location.column, location.endLine, location.endColumn)
                    else -> SuppressionScope.Project
                }
                val current = fixture.suppress(selector = selector, scope = scope)
                val store = strictStore(fixture)
                val subject = store.subject(original, current, input)
                assertEquals(PolicyDisposition.ACCEPTED, store.explainReassessment(subject, finding).disposition, kind)
                assertEquals("accepted", store.applyReassessment(subject, ProviderResult(finding.providerId, "partial", listOf(finding))).findings.single().disposition, kind)
            }
        }
    }

    @Test fun reassessmentDoesNotWidenTargetModuleSourceSetFileOrRangeScopes(): Unit {
        ConfigFixture().use { fixture ->
            val finding = fixture.finding()
            val location = finding.location
            val scopes = listOf(
                SuppressionScope.Target("iosArm64"), SuppressionScope.Module(":other"),
                SuppressionScope.SourceSet(finding.context.module, "test", finding.context.target),
                SuppressionScope.SourceSet(finding.context.module, finding.context.sourceSet, ""),
                SuppressionScope.File(location.path, Digests.sha256("other-bytes")),
                SuppressionScope.Range(location.path, location.fileDigest, 1, 1, 1, 2),
            )
            val original = fixture.store.capture(fixture.binding)
            val input = fixture.input(original, finding)
            scopes.forEachIndexed { index, scope ->
                val current = fixture.suppress(id = "scope-$index", scope = scope)
                val subject = fixture.store.subject(original, current, input)
                assertEquals(PolicyDisposition.OPEN, fixture.store.explainReassessment(subject, finding).disposition, "$scope")
            }
        }
    }

    @Test fun rawExactSuppressionAndBaselineBindEvidenceAndAllRelatedMaterial(): Unit {
        for (baseline in listOf(false, true)) ConfigFixture().use { fixture ->
            val original = fixture.store.capture(fixture.binding)
            val base = fixture.finding()
            val related = base.location.copy(path = "src/Related.kt", fileDigest = Digests.sha256("related-bytes"), excerpt = "related")
            val finding = base.copy(evidence = base.evidence + base.evidence, relatedLocations = listOf(related, related))
            val input = fixture.input(original, finding).let { it.copy(snapshot = it.snapshot.copy(
                files = it.snapshot.files + SnapshotFile(related.path, related.fileDigest, 13))) }
            val selector = fixture.store.findingSelector(original, input, finding)
            assertNull(selector.anchor.verifiedIdentity)
            val current = if (baseline) fixture.mutate(ConfigAction.AddBaseline(BaselineSpec("baseline", selector, "Retained exact bytes")))
                else fixture.suppress(selector = selector)
            val store = strictStore(fixture)
            val subject = store.subject(original, current, input)
            assertEquals(if (baseline) PolicyDisposition.BASELINED else PolicyDisposition.ACCEPTED,
                store.explainReassessment(subject, finding).disposition)
            val changes = listOf(
                finding.copy(evidence = finding.evidence.dropLast(1)),
                finding.copy(evidence = finding.evidence + Evidence("static_rule", "different evidence")),
                finding.copy(relatedLocations = finding.relatedLocations.dropLast(1)),
                finding.copy(relatedLocations = listOf(related.copy(fileDigest = Digests.sha256("changed-related")), related)),
                finding.copy(location = finding.location.copy(fileDigest = Digests.sha256("changed-primary"))),
                finding.copy(providerId = "another-provider"), finding.copy(severity = "info"),
                finding.copy(kind = "another-kind"), finding.copy(certainty = "high"),
                finding.copy(ruleId = OTHER_RULE), finding.copy(context = finding.context.copy(target = "iosArm64")),
            )
            changes.forEach { changed -> assertEquals(PolicyDisposition.OPEN, store.explainReassessment(subject, changed).disposition) }
            assertEquals(PolicyDisposition.OPEN, fixture.store.explain(original, input, finding).disposition)
        }
    }

    @Test fun removingSuppressionReopensOnlyTheNewView(): Unit = ConfigFixture().use { fixture ->
        val original = fixture.suppress()
        val finding = fixture.finding().copy(disposition = "accepted")
        val input = fixture.input(original, finding)
        val oldCapture = fixture.store.exportSnapshot(original)
        val current = fixture.mutate(ConfigAction.RemoveSuppression("risk"))
        val subject = fixture.store.subject(original, current, input)
        assertEquals(PolicyDisposition.OPEN, fixture.store.explainReassessment(subject, finding).disposition)
        assertEquals(PolicyDisposition.ACCEPTED, fixture.store.explain(original, input, finding).disposition)
        assertEquals(oldCapture, subject.originalConfig)
        assertEquals("accepted", finding.disposition)
    }

    @Test fun removingBaselineReopensOnlyTheNewView(): Unit = ConfigFixture().use { fixture ->
        val before = fixture.store.capture(fixture.binding)
        val finding = fixture.finding().copy(disposition = "baselined")
        val selector = fixture.store.findingSelector(before, fixture.input(before, finding), finding)
        val original = fixture.mutate(ConfigAction.AddBaseline(BaselineSpec("baseline", selector, "Existing exact finding")))
        val input = fixture.input(original, finding)
        val current = fixture.mutate(ConfigAction.RemoveBaseline("baseline"))
        val subject = fixture.store.subject(original, current, input)
        assertEquals(PolicyDisposition.OPEN, fixture.store.explainReassessment(subject, finding).disposition)
        assertEquals(PolicyDisposition.BASELINED, fixture.store.explain(original, input, finding).disposition)
        assertEquals("baselined", finding.disposition)
    }

    @Test fun expiryUsesEachCaptureTimeAndOnlyAFreshExpiredCaptureReopens(): Unit {
        for (baseline in listOf(false, true)) ConfigFixture().use { fixture ->
            val original = fixture.store.capture(fixture.binding)
            val finding = fixture.finding()
            val input = fixture.input(original, finding)
            val expires = fixture.clock.millis() + 100
            val selector = fixture.store.findingSelector(original, input, finding)
            val current = if (baseline) fixture.mutate(ConfigAction.AddBaseline(BaselineSpec("baseline", selector, "Expires later", expires)))
                else fixture.suppress(expiresAt = expires)
            val originalCapture = fixture.store.exportSnapshot(original)
            val currentCapture = fixture.store.exportSnapshot(current)
            val store = strictStore(fixture)
            val historical = store.prepareReassessment(input, originalCapture, currentCapture)
            val activeDisposition = if (baseline) PolicyDisposition.BASELINED else PolicyDisposition.ACCEPTED
            assertEquals(activeDisposition, store.explainReassessment(historical, finding).disposition)
            fixture.clock.advance(100)
            val expired = fixture.store.capture(fixture.binding)
            assertNotEquals(current.policyDigest, expired.policyDigest)
            assertEquals(current.shared, expired.shared)
            assertEquals(activeDisposition, store.explainReassessment(historical, finding).disposition,
                "A frozen view is historical, not proof of currentness")
            val restoredHistorical = store.prepareReassessment(input, originalCapture, currentCapture)
            assertEquals(activeDisposition, store.explainReassessment(restoredHistorical, finding).disposition)
            val fresh = store.subject(original, expired, input)
            assertEquals(PolicyDisposition.OPEN, store.explainReassessment(fresh, finding).disposition)
            assertEquals(currentCapture, historical.currentConfig)
            assertEquals(originalCapture, historical.originalConfig)
        }
    }

    @Test fun expiredLocalSuppressionMasksAndRemovalPreserveLayerPrecedence(): Unit = ConfigFixture().use { fixture ->
        val original = fixture.store.capture(fixture.binding)
        val finding = fixture.finding()
        val input = fixture.input(original, finding)
        fixture.suppress(id = "same")
        val local = fixture.suppress(id = "same", reason = SuppressionReason.REPORTED_FALSE_POSITIVE,
            layer = ConfigLayer.LOCAL, expiresAt = fixture.clock.millis() + 100)
        assertEquals(PolicyDisposition.SUPPRESSED, fixture.store.explainReassessment(fixture.store.subject(original, local, input), finding).disposition)
        fixture.clock.advance(100)
        val expired = fixture.store.capture(fixture.binding)
        val explanation = fixture.store.explainReassessment(fixture.store.subject(original, expired, input), finding)
        assertEquals(PolicyDisposition.OPEN, explanation.disposition)
        assertEquals(setOf("overridden", "expired"), explanation.candidates.map { it.state }.toSet())
        val removed = fixture.mutate(ConfigAction.RemoveSuppression("same"), ConfigLayer.LOCAL)
        assertEquals(PolicyDisposition.ACCEPTED, fixture.store.explainReassessment(fixture.store.subject(original, removed, input), finding).disposition)
        val masked = fixture.mutate(ConfigAction.MaskSharedSuppression("same"), ConfigLayer.LOCAL)
        assertEquals(PolicyDisposition.OPEN, fixture.store.explainReassessment(fixture.store.subject(original, masked, input), finding).disposition)
    }

    @Test fun nonmatchingAndExpiredLocalBaselinesStillShadowTheSharedId(): Unit = ConfigFixture().use { fixture ->
        val original = fixture.store.capture(fixture.binding)
        val finding = fixture.finding()
        val input = fixture.input(original, finding)
        val selector = fixture.store.findingSelector(original, input, finding)
        fixture.mutate(ConfigAction.AddBaseline(BaselineSpec("same", selector, "Shared exact finding")))
        val different = fixture.finding(code = "different source bytes")
        val differentSelector = fixture.store.findingSelector(original, fixture.input(original, different), different)
        val local = fixture.mutate(ConfigAction.AddBaseline(BaselineSpec("same", differentSelector, "Local different finding", fixture.clock.millis() + 100)), ConfigLayer.LOCAL)
        assertEquals(PolicyDisposition.OPEN, fixture.store.explainReassessment(fixture.store.subject(original, local, input), finding).disposition)
        fixture.clock.advance(100)
        val expired = fixture.store.capture(fixture.binding)
        assertEquals(PolicyDisposition.OPEN, fixture.store.explainReassessment(fixture.store.subject(original, expired, input), finding).disposition)
        val removed = fixture.mutate(ConfigAction.RemoveBaseline("same"), ConfigLayer.LOCAL)
        assertEquals(PolicyDisposition.BASELINED, fixture.store.explainReassessment(fixture.store.subject(original, removed, input), finding).disposition)
    }

    @Test fun protectedAndIntrinsicFailuresStayOpenWithoutLosingFailureEvidence(): Unit = ConfigFixture().use { fixture ->
        val original = fixture.store.capture(fixture.binding)
        val normal = fixture.finding()
        val input = fixture.input(original, normal)
        val current = fixture.suppress()
        val failures = listOf(normal.copy(ruleId = COMPILE_RULE)) +
            listOf("build_failure", "execution_failure", "compiler_failure", "test_failure", "worker_failure").map {
                normal.copy(evidence = normal.evidence + Evidence(it, "Failure must stay visible"))
            } + listOf("failed", "error", "crashed").map {
                normal.copy(verification = listOf(Verification("build", "Must succeed", listOf("owner-command"), it)))
            } + listOf(
                normal.copy(severity = "error", providerId = "kotlin-compiler", evidence = listOf(Evidence("compiler_diagnostic", "Error"))),
                normal.copy(severity = "fatal", providerId = "native-worker"),
            )
        val raw = ProviderResult("retained-provider", "failed", failures.map { it.copy(disposition = "fixed") },
            listOf(Coverage(RULE, TEST_CONTEXT, "failed", "Original failure")), listOf("worker failure"),
            listOf(RawDiagnostic("Original raw error")), AnalysisMetrics(12, 8, 1024, 99), "retained-version")
        val store = strictStore(fixture)
        val applied = store.applyReassessment(store.subject(original, current, input), raw)
        assertEquals(raw.copy(findings = raw.findings.map { it.copy(disposition = "open") }), applied)
        assertFalse(applied.complete())
        assertTrue(raw.findings.all { it.disposition == "fixed" })
    }

    @Test fun unchangedDisabledSettingAndProviderLabelsCannotCreateLifecycleDispositions(): Unit = ConfigFixture().use { fixture ->
        val original = fixture.mutate(ConfigAction.SetRuleEnabled(RULE, false))
        val finding = fixture.finding()
        val input = fixture.input(original, finding)
        val current = fixture.suppress(selector = PolicySelector.Rule(OTHER_RULE))
        assertEquals(PolicyDisposition.DISABLED, fixture.store.explain(original, input, finding).disposition)
        val store = strictStore(fixture)
        val subject = store.subject(original, current, input)
        val raw = ProviderResult(finding.providerId, "unavailable", listOf("open", "accepted", "suppressed", "baselined", "disabled", "fixed", "unavailable", "resolved").map {
            finding.copy(disposition = it)
        })
        val applied = store.applyReassessment(subject, raw)
        assertEquals(raw.copy(findings = raw.findings.map { it.copy(disposition = "open") }), applied)
        val accepted = fixture.suppress(id = "accepted-disabled-rule")
        assertEquals(PolicyDisposition.ACCEPTED, store.explainReassessment(store.subject(original, accepted, input), finding).disposition,
            "An unchanged disabled setting is not a reassessment disposition")
    }

    @Test fun ordinaryLegacyLifecycleStillWorksButIsNeverCalledForReassessment(): Unit = ConfigFixture().use { fixture ->
        val original = fixture.store.capture(fixture.binding)
        val finding = fixture.finding()
        val input = fixture.input(original, finding)
        val anchor = fixture.store.findingSelector(original, input, finding)
        val current = fixture.suppress()
        for (disposition in listOf(PolicyDisposition.FIXED, PolicyDisposition.UNAVAILABLE, PolicyDisposition.BASELINED)) {
            var identityCalls = 0
            var lifecycleCalls = 0
            val store = ConfigStore(fixture.root.resolve("state"), TEST_ALIASES, fixture.authority,
                identityVerifier = FindingIdentityVerifier { _, _, _, _ -> identityCalls++; null },
                lifecycleVerifier = FindingLifecycleVerifier { _, _, _, _ ->
                    lifecycleCalls++
                    VerifiedLifecycleDisposition("old-lifecycle-certificate", input.snapshot.snapshotId, original.policyDigest,
                        RULE, finding.providerId, anchor.fingerprint, disposition)
                }, clock = fixture.clock)
            assertEquals(disposition, store.explain(original, input, finding).disposition)
            assertEquals(1, identityCalls)
            assertEquals(1, lifecycleCalls)
            val subject = store.subject(original, current, input)
            assertEquals(PolicyDisposition.ACCEPTED, store.explainReassessment(subject, finding).disposition)
            assertEquals("accepted", store.applyReassessment(subject, ProviderResult(finding.providerId, "partial", listOf(finding))).findings.single().disposition)
            assertEquals(1, identityCalls)
            assertEquals(1, lifecycleCalls)
        }
    }

    @Test fun unsupportedVerifiedIdentityNeverCallsLegacyVerifiersOrFallsBackToMatchingBytes(): Unit {
        for (kind in listOf("symbol", "file", "range", "finding", "baseline")) ConfigFixture().use { fixture ->
            val original = fixture.store.capture(fixture.binding)
            val finding = fixture.finding()
            val input = fixture.input(original, finding)
            val rawSelector = fixture.store.findingSelector(original, input, finding)
            val identity = testIdentity(fixture.binding, input, finding, RULE)
            val verifiedAnchor = rawSelector.anchor.copy(verifiedIdentity = identity)
            val verifiedSelector = PolicySelector.ExactFinding(RULE, fingerprint(verifiedAnchor), verifiedAnchor)
            val location = finding.location
            val scope = when (kind) {
                "symbol" -> SuppressionScope.Symbol(identity)
                "file" -> SuppressionScope.File(location.path, location.fileDigest, identity)
                "range" -> SuppressionScope.Range(location.path, location.fileDigest, 1, 0, 1, 3, identity)
                else -> SuppressionScope.Project
            }
            val current = if (kind == "baseline") fixture.mutate(ConfigAction.AddBaseline(BaselineSpec("verified", verifiedSelector, "Unsupported verified identity")))
                else fixture.suppress(selector = if (kind == "finding") verifiedSelector else PolicySelector.Rule(RULE), scope = scope)
            val store = strictStore(fixture)
            val subject = store.subject(original, current, input)
            val explanation = store.explainReassessment(subject, finding)
            assertEquals(PolicyDisposition.OPEN, explanation.disposition, kind)
            assertEquals(rawSelector.fingerprint, explanation.fingerprint, kind)
            assertNull(explanation.policyEntryId)
            if (kind != "baseline") assertEquals("identity_unverified", explanation.candidates.single().state, kind)
            assertEquals("open", store.applyReassessment(subject, ProviderResult(finding.providerId, "partial", listOf(finding.copy(disposition = "accepted")))).findings.single().disposition, kind)
        }
    }

    @Test fun everyEffectiveRuleSettingMustAgreeIncludingUnselectedRulesAndExplicitTrue(): Unit {
        for ((rule, enabled) in listOf(RULE to true, RULE to false, OTHER_RULE to true, OTHER_RULE to false)) ConfigFixture().use { fixture ->
            val original = fixture.store.capture(fixture.binding)
            val input = fixture.input(original).copy(requiredRuleIds = listOf(RULE))
            val current = fixture.mutate(ConfigAction.SetRuleEnabled(rule, enabled))
            assertFailsWith<ConfigValidationException> { fixture.store.subject(original, current, input) }
            val currentInput = fixture.input(current).copy(requiredRuleIds = listOf(RULE))
            assertFailsWith<ConfigValidationException> { fixture.store.subject(current, original, currentInput) }
        }
    }

    @Test fun effectiveSettingsMayRemainEqualAcrossDifferentFullLayerDocuments(): Unit = ConfigFixture().use { fixture ->
        fixture.mutate(ConfigAction.SetRuleEnabled(OTHER_RULE, false))
        val original = fixture.mutate(ConfigAction.SetRuleEnabled(OTHER_RULE, true), ConfigLayer.LOCAL)
        val finding = fixture.finding()
        val input = fixture.input(original, finding).copy(requiredRuleIds = listOf(RULE))
        fixture.mutate(ConfigAction.SetRuleEnabled(OTHER_RULE, true))
        val current = fixture.suppress()
        assertEquals(original.ruleSettings, current.ruleSettings)
        assertNotEquals(original.shared, current.shared)
        val subject = fixture.store.subject(original, current, input)
        assertEquals(PolicyDisposition.ACCEPTED, fixture.store.explainReassessment(subject, finding).disposition)
        assertEquals(fixture.store.exportSnapshot(original), subject.originalConfig)
        assertEquals(fixture.store.exportSnapshot(current), subject.currentConfig)
    }

    @Test fun originalInputMustMatchItsOriginalCaptureRatherThanTheRequestedCurrentPolicy(): Unit = ConfigFixture().use { fixture ->
        val original = fixture.store.capture(fixture.binding)
        val input = fixture.input(original)
        val current = fixture.suppress()
        val mismatches = listOf(
            input.copy(snapshot = input.snapshot.copy(ownerId = "foreign-owner")),
            input.copy(snapshot = input.snapshot.copy(workspaceId = "foreign-workspace")),
            input.copy(snapshot = input.snapshot.copy(worktreeId = "foreign-worktree")),
            input.copy(snapshot = input.snapshot.copy(snapshotId = "unverified-snapshot")),
            input.copy(context = input.context.copy(configurationDigest = null)),
            input.copy(context = input.context.copy(configurationDigest = current.policyDigest)),
            input.copy(context = input.context.copy(configurationDigest = Digests.sha256("foreign-policy"))),
        )
        mismatches.forEach { mismatch -> assertFailsWith<ConfigValidationException> { fixture.store.subject(original, current, mismatch) } }
    }

    @Test fun fullBindingRejectsForeignCapturesIncludingOtherwiseIdenticalLineage(): Unit = ConfigFixture().use { fixture ->
        val original = fixture.store.capture(fixture.binding)
        val input = fixture.input(original)
        val foreignBindings = listOf(
            fixture.binding.copy(ownerId = "foreign-owner"), fixture.binding.copy(workspaceId = "foreign-workspace"),
            fixture.binding.copy(worktreeId = "foreign-worktree"), fixture.binding.copy(lineageId = "foreign-lineage"),
        )
        for (binding in foreignBindings) {
            val foreign = fixture.store.capture(binding)
            assertFailsWith<ConfigValidationException> { fixture.store.subject(original, foreign, input) }
            assertFailsWith<ConfigValidationException> { fixture.store.subject(foreign, foreign, input) }
        }
    }

    @Test fun bothFullCapturesMustRestoreExactDocumentsTokensRegistryAndExpiryActivity(): Unit = ConfigFixture().use { fixture ->
        val expires = fixture.clock.millis() + 100
        fixture.suppress(id = "old", expiresAt = expires)
        val original = fixture.suppress(id = "local", layer = ConfigLayer.LOCAL)
        val input = fixture.input(original)
        val current = fixture.suppress(id = "new")
        val oldCapture = fixture.store.exportSnapshot(original)
        val newCapture = fixture.store.exportSnapshot(current)
        val corruptions = listOf<(FrozenConfig) -> FrozenConfig>(
            { it.copy(schemaVersion = 2) }, { it.copy(registryDigest = Digests.sha256("foreign-registry")) },
            { it.copy(capturedAtEpochMillis = -1) }, { it.copy(capturedAtEpochMillis = expires) },
            { it.copy(sharedDocument = null) }, { it.copy(localDocument = null) },
            { it.copy(sharedDocument = it.sharedDocument!! + " ") }, { it.copy(localDocument = it.localDocument!! + " ") },
            { it.copy(sharedDocument = "{}") }, { it.copy(localDocument = "{}") },
            { it.copy(binding = it.binding.copy(lineageId = "foreign-lineage")) },
            { it.copy(revisionToken = it.revisionToken.copy(binding = it.binding.copy(lineageId = "foreign-lineage"))) },
            { it.copy(revisionToken = it.revisionToken.copy(policyDigest = Digests.sha256("retagged-policy"))) },
            { it.copy(revisionToken = it.revisionToken.copy(shared = it.revisionToken.shared.copy(documentDigest = Digests.sha256("retagged-layer")))) },
        )
        for (corrupt in corruptions) {
            assertFailsWith<ConfigException> { fixture.store.prepareReassessment(input, corrupt(oldCapture), newCapture) }
            assertFailsWith<ConfigException> { fixture.store.prepareReassessment(input, oldCapture, corrupt(newCapture)) }
        }
    }

    @Test fun foreignRegistryIsRejectedAtPreparationAndConsumptionEvenForEmptyResults(): Unit = ConfigFixture().use { fixture ->
        val original = fixture.store.capture(fixture.binding)
        val finding = fixture.finding()
        val input = fixture.input(original, finding)
        val current = fixture.suppress()
        val subject = fixture.store.subject(original, current, input)
        val changedAliases = RuleAliases(TEST_ALIASES.allRules().map { if (it.canonicalId == RULE) it.copy(aliases = it.aliases + "extra-alias") else it })
        val changedImplementation = RuleAliases(TEST_ALIASES.allRules().map { if (it.canonicalId == RULE) it.copy(implementationDigest = Digests.sha256("changed-implementation")) else it })
        for (aliases in listOf(changedAliases, changedImplementation)) {
            val foreign = ConfigStore(fixture.root.resolve("state"), aliases, clock = fixture.clock)
            assertFailsWith<ConfigReadException> { foreign.prepareReassessment(input, subject.originalConfig, subject.currentConfig) }
            assertFailsWith<ConfigValidationException> { foreign.explainReassessment(subject, finding) }
            assertFailsWith<ConfigValidationException> { foreign.applyReassessment(subject, ProviderResult(finding.providerId, "partial")) }
        }
        assertEquals(PolicyDisposition.ACCEPTED, strictStore(fixture).explainReassessment(subject, finding).disposition,
            "A checked subject is portable to the same exact registry, not tied to a mutable store path")
    }

    @Test fun everyFindingStillNeedsOriginalScopeAndUnambiguousPrimaryAndRelatedSourceBytes(): Unit = ConfigFixture().use { fixture ->
        val original = fixture.store.capture(fixture.binding)
        val finding = fixture.finding()
        val input = fixture.input(original, finding)
        val current = fixture.suppress()
        val store = strictStore(fixture)
        val subject = store.subject(original, current, input)
        val badFindings = listOf(
            finding.copy(ruleId = "unknown.rule"), finding.copy(context = finding.context.copy(compilation = "other")),
            finding.copy(location = finding.location.copy(path = "../Example.kt")),
            finding.copy(location = finding.location.copy(fileDigest = Digests.sha256("different-bytes"))),
            finding.copy(location = finding.location.copy(fileDigest = "not-a-digest")),
            finding.copy(location = finding.location.copy(line = 0)),
            finding.copy(relatedLocations = listOf(finding.location.copy(path = "src/Missing.kt"))),
        )
        badFindings.forEach { assertEquals(PolicyDisposition.OPEN, store.explainReassessment(subject, it).disposition) }
        val duplicate = input.snapshot.files.single()
        for (files in listOf(emptyList(), listOf(duplicate, duplicate), listOf(duplicate, duplicate.copy(digest = Digests.sha256("conflicting-bytes"))))) {
            val ambiguous = input.copy(snapshot = input.snapshot.copy(files = files))
            val uncheckedSource = store.subject(original, current, ambiguous)
            assertEquals(PolicyDisposition.OPEN, store.explainReassessment(uncheckedSource, finding).disposition,
                "The config subject does not certify execution or invent missing source proof")
        }
    }

    @Test fun everyNonDispositionFieldAndAllOrderingAndMultiplicityAreRetained(): Unit = ConfigFixture().use { fixture ->
        val original = fixture.store.capture(fixture.binding)
        val base = fixture.finding()
        val related = base.location.copy(path = "src/Related.kt", fileDigest = Digests.sha256("related"), excerpt = "verbatim related excerpt")
        val evidence = Evidence("static_rule", "Full original evidence", "output/original.json", Digests.sha256("original evidence"))
        val verification = Verification("owner_recheck", "Original expectation", listOf("not-executed", "--owner-arg"), "not_run")
        val finding = base.copy(title = "Original title", diagnosis = "Original diagnosis", whyItMatters = "Original rationale",
            location = base.location.copy(excerpt = "verbatim original excerpt"), fingerprint = "original-provider-display-id",
            severity = "info", kind = "original_kind", certainty = "high", evidence = listOf(evidence, base.evidence.single(), evidence),
            fixHint = "Original hint", verification = listOf(verification, verification), upstreamId = "original-upstream", symbolId = "original-symbol",
            semanticDigest = Digests.sha256("original-semantic-material"), relatedLocations = listOf(related, related), disposition = "unavailable")
        val other = finding.copy(ruleId = OTHER_RULE, title = "Second original occurrence", disposition = "accepted")
        val input = fixture.input(original, finding).let { it.copy(snapshot = it.snapshot.copy(files = it.snapshot.files + SnapshotFile(related.path, related.fileDigest, 7))) }
        val current = fixture.suppress()
        val coverage = Coverage(RULE, TEST_CONTEXT, "findings", "Original coverage reason", true, "semantic", "executed", "original-certificate", true, true, true)
        val diagnostic = RawDiagnostic("Verbatim raw diagnostic", "warning", "raw-upstream", related)
        val raw = ProviderResult(finding.providerId, "partial", listOf(finding, other, finding),
            listOf(coverage, coverage.copy(ruleId = OTHER_RULE, state = "unavailable", required = false), coverage),
            listOf("first", "second", "first"), listOf(diagnostic, diagnostic.copy(message = "second raw"), diagnostic),
            AnalysisMetrics(91, 23, 4096, 122), "exact-provider-version")
        val rawDocument = ContractJson.encodeToString(raw)
        val expected = raw.copy(findings = listOf(finding.copy(disposition = "accepted"), other.copy(disposition = "open"), finding.copy(disposition = "accepted")))
        val applied = fixture.store.applyReassessment(fixture.store.subject(original, current, input), raw)
        assertEquals(expected, applied)
        assertEquals(ContractJson.encodeToString(expected), ContractJson.encodeToString(applied))
        assertEquals(rawDocument, ContractJson.encodeToString(raw))
        assertEquals(3, applied.findings.size)
        assertEquals(applied.findings[0], applied.findings[2])
        assertEquals(raw.complete(), applied.complete())
        assertFalse(applied.complete())
    }

    @Test fun allInputCollectionsAndExposedGettersAreDetachedFromTheCheckedSubject(): Unit = ConfigFixture().use { fixture ->
        val original = fixture.store.capture(fixture.binding)
        val finding = fixture.finding()
        val base = fixture.input(original, finding)
        val files = base.snapshot.files.toMutableList()
        val roots = mutableListOf("src")
        val exclusions = mutableListOf("excluded")
        val unknown = mutableListOf("retained-unknown-input")
        val classpath = mutableListOf(ArtifactIdentity("original:artifact:1", Digests.sha256("artifact"), "/original/artifact.jar"))
        val compilerArguments = mutableListOf("-original-compiler-argument")
        val libraries = mutableMapOf("original-library" to "1")
        val policies = mutableMapOf("original-policy" to "strict")
        val compilationFiles = mutableListOf(finding.location.path)
        val requiredRules = mutableListOf(RULE)
        val changedPaths = mutableListOf(finding.location.path)
        val executionArguments = mutableListOf("original-execution-argument")
        val environment = mutableMapOf("ORIGINAL_ENVIRONMENT" to "original-value")
        val input = base.copy(snapshot = base.snapshot.copy(files = files, parentSnapshotId = Digests.sha256("parent"), captureKind = "retained-overlay",
            declaredRoots = roots, scopeExclusions = exclusions, unknownInputs = unknown),
            context = base.context.copy(compilerVersion = "original-compiler", classpath = classpath, compilerArguments = compilerArguments,
                libraries = libraries, policies = policies, modelDigest = Digests.sha256("model"), dependencyDigest = Digests.sha256("dependencies"),
                toolchainDigest = Digests.sha256("toolchain"), fullProject = false, compilationFiles = compilationFiles),
            requiredRuleIds = requiredRules, changedPaths = changedPaths, baselineSnapshotId = Digests.sha256("baseline"),
            execution = TrustedExecution("/owner/approved/compiler", executionArguments, true, Digests.sha256("executable"), 1234, 4321, environment),
            outputRoot = "/retained/output-root")
        val expected = detached(input)
        val current = fixture.suppress(scope = SuppressionScope.File(finding.location.path, finding.location.fileDigest))
        val subject = fixture.store.subject(original, current, input)
        val oldCapture = fixture.store.exportSnapshot(original)
        val newCapture = fixture.store.exportSnapshot(current)
        files.clear(); roots.clear(); exclusions.clear(); unknown.clear(); classpath.clear(); compilerArguments.clear()
        libraries.clear(); policies.clear(); compilationFiles.clear(); requiredRules.clear(); changedPaths.clear()
        executionArguments.clear(); environment.clear()
        assertNotEquals(expected, input)
        assertEquals(expected, subject.originalInput)
        assertEquals(expected.analysisKey(), subject.originalInput.analysisKey())
        val exposed = subject.originalInput
        (exposed.snapshot.files as MutableList<SnapshotFile>).clear()
        (exposed.snapshot.declaredRoots as MutableList<String>).clear()
        (exposed.snapshot.scopeExclusions as MutableList<String>).clear()
        (exposed.snapshot.unknownInputs as MutableList<String>).clear()
        (exposed.context.classpath as MutableList<ArtifactIdentity>).clear()
        (exposed.context.compilerArguments as MutableList<String>).clear()
        (exposed.context.libraries as MutableMap<String, String>).clear()
        (exposed.context.policies as MutableMap<String, String>).clear()
        (exposed.context.compilationFiles as MutableList<String>).clear()
        (exposed.requiredRuleIds as MutableList<String>).clear()
        (exposed.changedPaths as MutableList<String>).clear()
        val exposedExecution = requireNotNull(exposed.execution)
        (exposedExecution.arguments as MutableList<String>).clear()
        (exposedExecution.environment as MutableMap<String, String>).clear()
        assertEquals(expected, subject.originalInput)
        assertEquals(expected.analysisKey(), subject.originalInput.analysisKey())
        assertNotSame(subject.originalInput, subject.originalInput)
        assertNotSame(subject.originalConfig, subject.originalConfig)
        assertNotSame(subject.currentConfig, subject.currentConfig)
        val alteredCapture = subject.currentConfig.copy(sharedDocument = null)
        assertNotEquals(newCapture, alteredCapture)
        assertEquals(oldCapture, subject.originalConfig)
        assertEquals(newCapture, subject.currentConfig)
        assertEquals(PolicyDisposition.ACCEPTED, fixture.store.explainReassessment(subject, finding).disposition)
    }

    @Test fun resultsAndExplanationsNeverShareMutableCollectionsWithCallersOrOtherViews(): Unit = ConfigFixture().use { fixture ->
        val original = fixture.store.capture(fixture.binding)
        val base = fixture.finding()
        val evidence = base.evidence.toMutableList()
        val commands = mutableListOf("retained-command")
        val verification = mutableListOf(Verification("recheck", "Retained expected result", commands))
        val related = mutableListOf(base.location)
        val finding = base.copy(evidence = evidence, verification = verification, relatedLocations = related)
        val input = fixture.input(original, finding)
        val current = fixture.suppress()
        val subject = fixture.store.subject(original, current, input)
        val findings = mutableListOf(finding, finding)
        val coverage = mutableListOf(Coverage(RULE, TEST_CONTEXT, "partial"))
        val reasons = mutableListOf("retained reason")
        val diagnostics = mutableListOf(RawDiagnostic("retained diagnostic", location = finding.location))
        val raw = ProviderResult(finding.providerId, "partial", findings, coverage, reasons, diagnostics)
        val originalRaw = detached(raw)
        val expected = originalRaw.copy(findings = originalRaw.findings.map { it.copy(disposition = "accepted") })
        val first = fixture.store.applyReassessment(subject, raw)
        val second = fixture.store.applyReassessment(subject, raw)
        val explanation = fixture.store.explainReassessment(subject, finding)
        evidence.clear(); commands.clear(); verification.clear(); related.clear()
        findings.clear(); coverage.clear(); reasons.clear(); diagnostics.clear()
        assertEquals(expected, first)
        assertEquals(expected, second)
        assertEquals(originalRaw.findings.first(), explanation.finding)
        (first.findings.first().evidence as MutableList<Evidence>).clear()
        (first.findings.first().verification.first().command as MutableList<String>).clear()
        (first.findings.first().verification as MutableList<Verification>).clear()
        (first.findings.first().relatedLocations as MutableList<SourceLocation>).clear()
        (first.coverage as MutableList<Coverage>).clear()
        (first.reasons as MutableList<String>).clear()
        (first.rawDiagnostics as MutableList<RawDiagnostic>).clear()
        assertEquals(expected, second)
        assertEquals(originalRaw.findings.first(), explanation.finding)
        (explanation.finding.evidence as MutableList<Evidence>).clear()
        (explanation.finding.verification.first().command as MutableList<String>).clear()
        (explanation.finding.relatedLocations as MutableList<SourceLocation>).clear()
        assertEquals(expected, second)
        assertEquals(expected, fixture.store.applyReassessment(subject, originalRaw))
        assertEquals(fixture.store.exportSnapshot(current), subject.currentConfig)
    }

    @Test fun identicalCapturedPoliciesAreOnlyAHistoricalViewAndNeverImplicitLiveCapture(): Unit = ConfigFixture().use { fixture ->
        val captured = fixture.suppress()
        val finding = fixture.finding()
        val input = fixture.input(captured, finding)
        val archived = fixture.store.exportSnapshot(captured)
        fixture.mutate(ConfigAction.RemoveSuppression("risk"))
        fixture.mutate(ConfigAction.SetRuleEnabled(OTHER_RULE, false))
        fixture.clock.advance(100)
        val historical = strictStore(fixture).prepareReassessment(input, archived, archived)
        assertEquals(archived, historical.originalConfig)
        assertEquals(archived, historical.currentConfig)
        assertEquals(PolicyDisposition.ACCEPTED, fixture.store.explainReassessment(historical, finding).disposition)
        assertNotEquals(archived.revisionToken, fixture.store.capture(fixture.binding).revisionToken)
    }

    private fun ConfigStore.subject(original: ConfigSnapshot, current: ConfigSnapshot, input: AnalysisInput): PolicyReassessmentSubject =
        prepareReassessment(input, exportSnapshot(original), exportSnapshot(current))

    private fun strictStore(fixture: ConfigFixture): ConfigStore = ConfigStore(fixture.root.resolve("state"), TEST_ALIASES, fixture.authority,
        identityVerifier = FindingIdentityVerifier { _, _, _, _ -> error("Legacy identity verifier must not run during reassessment") },
        lifecycleVerifier = FindingLifecycleVerifier { _, _, _, _ -> error("Legacy lifecycle verifier must not run during reassessment") },
        clock = fixture.clock)

    private inline fun <reified T> detached(value: T): T = ContractJson.decodeFromString(ContractJson.encodeToString(value))
}

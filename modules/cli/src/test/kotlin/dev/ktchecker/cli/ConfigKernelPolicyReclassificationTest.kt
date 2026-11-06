package dev.ktchecker.cli

import dev.ktchecker.config.*
import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

/**
 * Real ConfigStore owner permits/captured projection and KernelService public APIs/SQLite.
 * Sources, provider, source authority, qualification flags, lifecycle proof and receipts below
 * are SYNTHETIC test fixtures, not compiler execution, real model observation or qualification.
 * These tests deliberately do not instantiate Workbench: equivalence of its changing embedded
 * activation/execution-plan tokens and per-prepare UUID materialized roots remains unsupported.
 */
class ConfigKernelPolicyReclassificationTest {
    @TempDir lateinit var temp: Path

    @Test fun activeOldReviewKeepsCapturedPolicyAndLaterConsentRequiresEveryReceipt() = fixture("active", workers = 2).use { f ->
        val held = Hold()
        val first = f.submit(f.input(f.initial), hold = held)
        await(held.entered)
        try {
            val policyB = f.consent(selector = PolicySelector.Rule(ALIAS))
            assertEquals(RULE, policyB.shared.suppressions.single().spec.selector.ruleId)
            val second = f.review(f.input(policyB))
            f.assertComplete(second, "accepted")
            assertTrue(second.status.sequence > first.status.sequence)
            val pending = f.cut()
            assertFalse(pending.ready)
            assertTrue("review_pending:${first.status.reviewId}" in pending.reasons, pending.reasons.toString())

            held.release.countDown()
            val original = f.finish(first) // A finishes AFTER B, still under captured A.
            f.assertComplete(original, "open")
            assertEquals(original.raw, original.status.result)
            assertEquals(original.input.copy(context = original.input.context.copy(configurationDigest = policyB.policyDigest)), second.input)
            assertEquals(original.raw.findings, second.raw.findings)
            assertNotEquals(original.input.context.configurationDigest, second.input.context.configurationDigest)
            val events = original.events + second.events
            val undelivered = f.cut()
            assertFalse(undelivered.ready)
            assertFalse(undelivered.reasons.any { it.startsWith("open_finding:") }, undelivered.reasons.toString())
            events.forEach {
                assertTrue("insertion_missing:${it.eventId}" in undelivered.reasons)
                assertTrue("model_observation_missing:${it.eventId}" in undelivered.reasons)
            }
            f.deliver(original)
            val newDebt = f.cut()
            second.events.forEach { assertTrue("insertion_missing:${it.eventId}" in newDebt.reasons) }
            f.acknowledge(second)
            val notObserved = f.cut()
            assertFalse(notObserved.ready)
            second.events.forEach {
                assertFalse("insertion_missing:${it.eventId}" in notObserved.reasons)
                assertTrue("model_observation_missing:${it.eventId}" in notObserved.reasons)
            }
            f.observe(second)
            val ready = f.assertReady()
            var actions = 0
            val completed = f.kernel.completeGate(f.client.client, ready.cutId) { actions++ }
            assertTrue(completed.allowed, completed.reasons.toString())
            assertTrue(completed.actionPerformed)
            assertEquals(1, actions)
            f.assertHistory(original)
            f.assertHistory(second)
            f.assertReceiptHistory(original)
            f.assertReceiptHistory(second)
        } finally {
            held.release.countDown()
        }
    }

    @Test fun everyOwnerAccountedDispositionUsesFreshExactEvidenceNotAFixedLabel() {
        for (disposition in listOf("accepted", "suppressed", "disabled", "baselined")) fixture("accounted-$disposition").use { f ->
            val original = f.review(f.input(f.initial))
            f.assertComplete(original, "open")
            val policyB = when (disposition) {
                "accepted" -> f.consent(selector = PolicySelector.Rule(ALIAS))
                "suppressed" -> f.consent(reason = SuppressionReason.REPORTED_FALSE_POSITIVE)
                "disabled" -> f.ownerChange(ConfigAction.SetRuleEnabled(ALIAS, false))
                else -> f.ownerChange(ConfigAction.AddBaseline(BaselineSpec("baseline",
                    f.configs.findingSelector(f.initial, original.input, original.raw.findings.single()), "Explicit synthetic owner baseline")))
            }
            val fresh = f.review(f.input(policyB))
            f.assertComplete(fresh, disposition)
            assertEquals(RULE, fresh.status.result!!.findings.single().ruleId, "Config aliases do not rewrite provider evidence")
            assertEquals(original.raw.findings, fresh.raw.findings)
            f.deliver(original); f.deliver(fresh)
            f.assertReady()
            f.assertHistory(original)
            assertEquals("open", original.status.result!!.findings.single().disposition)
        }
    }

    @Test fun historicalAcceptanceNeedsFreshEvaluationAfterAnUnrelatedOwnerEdit() = fixture("accepted-history").use { f ->
        val policyA = f.consent()
        val original = f.review(f.input(policyA))
        f.assertComplete(original, "accepted")
        f.deliver(original)
        val before = f.assertReady()
        val policyB = f.ownerChange(ConfigAction.SetRuleEnabled(OTHER_RULE, true))
        assertNotEquals(policyA.policyDigest, policyB.policyDigest)
        val invalidated = f.kernel.completeGate(f.client.client, before.cutId) { fail("Stale consent must not run the action") }
        assertFalse(invalidated.allowed)
        assertTrue("effective_policy_changed" in invalidated.reasons, invalidated.reasons.toString())
        f.assertHistory(original)
        val fresh = f.review(f.input(policyB))
        f.assertComplete(fresh, "accepted")
        f.deliver(fresh)
        f.assertReady()
        f.assertHistory(original)
        f.assertReceiptHistory(original)
    }

    @Test fun crossPolicyAbsenceIsNotFreshAcceptance() = fixture("absence").use { f ->
        val original = f.review(f.input(f.initial))
        val policyB = f.consent()
        val absent = f.review(f.input(policyB)) { result(it, emptyList()) }
        f.assertComplete(original, "open")
        f.assertComplete(absent)
        assertTrue(absent.raw.findings.isEmpty())
        f.deliver(original); f.deliver(absent)
        f.assertOpen(original, exact = true)
        f.assertHistory(original)
    }

    @Test fun equalProviderFingerprintsCannotReplaceFullFindingEquality() {
        val variants: List<Pair<String, (Finding) -> Finding>> = listOf(
            "title" to { it.copy(title = "Changed title") },
            "diagnosis" to { it.copy(diagnosis = "Different diagnosis with the SAME config anchor") },
            "impact" to { it.copy(whyItMatters = "Different impact") },
            "excerpt" to { it.copy(location = it.location.copy(excerpt = "Different excerpt")) },
            "location" to { it.copy(location = it.location.copy(column = 1, endColumn = 2)) },
            "evidence" to { it.copy(evidence = listOf(Evidence("static_rule", "Different evidence", digest = Digests.sha256("different evidence")))) },
            "hint" to { it.copy(fixHint = "Different hint") },
            "verification" to { it.copy(verification = listOf(Verification(expected = "Different expectation", command = listOf("never-executed")))) },
            "upstream" to { it.copy(upstreamId = "different-upstream") },
            "symbol" to { it.copy(symbolId = "different-symbol") },
            "semantic" to { it.copy(semanticDigest = Digests.sha256("different semantic claim")) },
            "related" to { it.copy(relatedLocations = listOf(it.location.copy(line = 2, endLine = 2))) },
            "classification" to { it.copy(kind = "style", certainty = "high") },
            "provider" to { it.copy(providerId = OTHER_PROVIDER) },
            "rule-alias" to { it.copy(ruleId = ALIAS) },
        )
        for ((name, change) in variants) fixture("finding-$name").use { f ->
            val inputA = f.input(f.initial).copy(requiredRuleIds = listOf(RULE, ALIAS).sorted())
            val original = f.review(inputA)
            val policyB = f.consent()
            val inputB = inputA.copy(context = inputA.context.copy(configurationDigest = policyB.policyDigest))
            val fresh = f.review(inputB) { result(it, listOf(change(warning(it)))) }
            f.assertComplete(original, "open")
            f.assertComplete(fresh, "accepted") // Real ConfigStore accepts B, so the negative is not vacuous.
            val oldFinding = original.raw.findings.single()
            val newFinding = fresh.raw.findings.single()
            assertEquals(oldFinding.fingerprint, newFinding.fingerprint, name)
            assertNotEquals(oldFinding, newFinding, name)
            if (name == "diagnosis") {
                assertEquals(f.configs.findingSelector(f.initial, inputA, oldFinding).fingerprint,
                    f.configs.findingSelector(policyB, inputB, newFinding).fingerprint,
                    "Even equal trusted config anchors omit diagnostic fields required by the kernel comparison")
            }
            f.deliver(original); f.deliver(fresh)
            f.assertOpen(original, exact = true)
            f.assertHistory(original)
        }
    }

    @Test fun changedAcceptedInputsAreNotAnEquivalenceProofEvenWithIdenticalAcceptedFinding() {
        val variants: List<Pair<String, (Fixture, AnalysisInput) -> AnalysisInput>> = listOf(
            "compiler-version" to { _, i -> i.copy(context = i.context.copy(compilerVersion = "synthetic-2")) },
            "classpath-order" to { _, i -> i.copy(context = i.context.copy(classpath = i.context.classpath.reversed())) },
            "classpath-coordinate" to { _, i -> i.copy(context = i.context.copy(classpath = i.context.classpath.map { it.copy(coordinate = it.coordinate + "-other") })) },
            "classpath-path" to { f, i -> i.copy(context = i.context.copy(classpath = listOf(i.context.classpath.first().copy(path = f.sameBytesArtifact.path)) + i.context.classpath.drop(1))) },
            "classpath-bytes" to { f, i -> i.copy(context = i.context.copy(classpath = listOf(f.otherBytesArtifact) + i.context.classpath.drop(1))) },
            "argument-order" to { _, i -> i.copy(context = i.context.copy(compilerArguments = i.context.compilerArguments.reversed())) },
            "arguments" to { _, i -> i.copy(context = i.context.copy(compilerArguments = i.context.compilerArguments + "-different")) },
            "libraries" to { _, i -> i.copy(context = i.context.copy(libraries = mapOf("synthetic-library" to "2"))) },
            "policies" to { _, i -> i.copy(context = i.context.copy(policies = i.context.policies + ("synthetic-mode" to "different"))) },
            "model" to { _, i -> i.copy(context = i.context.copy(modelDigest = Digests.sha256("other model"))) },
            "dependency" to { _, i -> i.copy(context = i.context.copy(dependencyDigest = Digests.sha256("other dependencies"))) },
            "toolchain" to { _, i -> i.copy(context = i.context.copy(toolchainDigest = Digests.sha256("other toolchain"))) },
            "compilation-files" to { _, i -> i.copy(context = i.context.copy(compilationFiles = emptyList())) },
            "materialized-root" to { f, i -> i.copy(materializedRoot = f.otherSourceRoot.toString()) },
            "output-root" to { f, i -> i.copy(outputRoot = f.otherSourceRoot.resolve("never-used-output").toString()) },
            "execution" to { _, i -> i.copy(execution = TrustedExecution("synthetic-never-executed", arguments = listOf("different"), approved = false)) },
            "required-rules" to { _, i -> i.copy(requiredRuleIds = (i.requiredRuleIds + OTHER_RULE).sorted()) },
            "changed-paths" to { _, i -> i.copy(changedPaths = emptyList()) },
            "baseline" to { _, i -> i.copy(baselineSnapshotId = Digests.sha256("other baseline")) },
        )
        for ((name, change) in variants) fixture("input-$name").use { f ->
            val original = f.review(f.input(f.initial))
            val policyB = f.consent()
            val expected = f.input(policyB)
            val different = change(f, expected)
            assertNotEquals(expected, different, name)
            val fresh = f.review(different)
            f.assertComplete(original, "open")
            f.assertComplete(fresh, "accepted")
            assertEquals(original.raw.findings, fresh.raw.findings, name)
            f.deliver(original); f.deliver(fresh)
            f.assertOpen(original, exact = true)
            f.assertHistory(original)
        }
    }

    @Test fun fullManifestAndSameSnapshotAreRequiredNotJustMatchingFileBytesOrScalarId() {
        for (name in listOf("same-id-files", "same-id-roots", "same-id-parent", "new-id", "descendant")) fixture("snapshot-$name").use { f ->
            val original = f.review(f.input(f.initial))
            val policyB = f.consent()
            val exact = f.input(policyB)
            val manifest = when (name) {
                "same-id-files" -> exact.snapshot.copy(files = (exact.snapshot.files + f.extraFile).sortedBy { it.path })
                "same-id-roots" -> exact.snapshot.copy(declaredRoots = listOf(".", "synthetic-extra-root"))
                "same-id-parent" -> exact.snapshot.copy(parentSnapshotId = Digests.sha256("unrelated-parent"))
                "new-id" -> exact.snapshot.copy(snapshotId = Digests.sha256("new synthetic snapshot"))
                else -> exact.snapshot.copy(snapshotId = Digests.sha256("synthetic descendant"), parentSnapshotId = original.input.snapshot.snapshotId)
            }
            val fresh = f.review(exact.copy(snapshot = manifest))
            f.assertComplete(fresh, "accepted")
            assertEquals(original.raw.findings, fresh.raw.findings)
            assertNotEquals(original.input.snapshot, fresh.input.snapshot)
            if (name.startsWith("same-id")) assertEquals(original.input.snapshot.snapshotId, fresh.input.snapshot.snapshotId)
            f.deliver(original); f.deliver(fresh)
            f.assertOpen(original, exact = true)
        }
    }

    @Test fun anotherContextOrClientCannotSupplyThisLineagesFreshAccounting() {
        fixture("other-context").use { f ->
            val original = f.review(f.input(f.initial))
            val policyB = f.consent()
            val input = f.input(policyB)
            val fresh = f.review(input.copy(context = input.context.copy(scope = input.context.scope.copy(compilation = "test"))))
            f.assertComplete(fresh, "accepted")
            f.deliver(original); f.deliver(fresh)
            val cut = f.assertOpen(original)
            assertTrue(cut.reasons.any { it.startsWith("current_coverage_missing:") }, cut.reasons.toString())
        }
        fixture("other-client").use { f ->
            val original = f.review(f.input(f.initial))
            val policyB = f.consent()
            // This client is already current at B, with complete empty coverage, but no B acceptance.
            val absentInput = f.input(policyB).copy(requiredRuleIds = listOf(RULE, OTHER_RULE).sorted())
            val absent = f.review(absentInput) { result(it, emptyList()) }
            val other = f.kernel.registerClient(f.client.binding.copy(clientId = "other-review-client"))
            val foreign = f.review(f.input(policyB), registration = other)
            f.assertComplete(absent)
            f.assertComplete(foreign, "accepted")
            assertEquals(original.input.copy(context = original.input.context.copy(configurationDigest = policyB.policyDigest)), foreign.input)
            assertEquals(original.raw.findings, foreign.raw.findings)
            f.deliver(original); f.deliver(absent); f.deliver(foreign)
            f.assertOpen(original, exact = true)
            f.assertHistory(original)
        }
    }

    @Test fun incompleteCancelledOrWrongEvaluatorHistoryRetainsItsOwnObligation() {
        for (name in listOf("incomplete", "coverage", "cancelled", "policy-error", "evaluator-change")) fixture("old-$name").use { f ->
            if (name == "policy-error") f.policyErrors += f.initial.policyDigest
            var original = f.review(f.input(f.initial)) { input ->
                val raw = result(input, listOf(warning(input)))
                when (name) {
                    "incomplete" -> raw.copy(status = "incomplete", reasons = listOf("synthetic-incomplete"))
                    "coverage" -> raw.copy(coverage = emptyList())
                    else -> raw
                }
            }
            if (name == "cancelled") {
                f.kernel.cancel(f.client.client, original.status.reviewId)
                original = f.refresh(original)
            }
            if (name == "evaluator-change") {
                f.reopen("ktchecker/test/exact-policy-reclassification/changed-evaluator")
                original = f.refresh(original)
                assertTrue("policy_evaluator_mismatch" in original.status.reasons)
            }
            val policyB = f.consent()
            val fresh = f.review(f.input(policyB))
            f.assertComplete(fresh, "accepted")
            assertEquals(original.raw.findings, fresh.raw.findings)
            f.deliver(original); f.deliver(fresh)
            val cut = f.assertOpen(original)
            val stateReason = if (name == "cancelled") "review_cancelled" else "review_incomplete"
            assertTrue("$stateReason:${original.status.reviewId}" in cut.reasons, cut.reasons.toString())
            assertEquals(if (name == "cancelled") "cancelled" else "incomplete", original.status.state)
            f.assertHistory(original)
        }
    }

    @Test fun incompleteCancelledOrUncertifiedNewPolicyResultCannotCloseTheOldFinding() {
        for (name in listOf("incomplete", "coverage", "cancelled", "policy-error", "policy-not-ready")) fixture("new-$name").use { f ->
            val original = f.review(f.input(f.initial))
            val policyB = f.consent()
            if (name == "policy-error") f.policyErrors += policyB.policyDigest
            if (name == "policy-not-ready") f.policyNotReady += policyB.policyDigest
            var fresh = f.review(f.input(policyB)) { input ->
                val raw = result(input, listOf(warning(input)))
                when (name) {
                    "incomplete" -> raw.copy(status = "incomplete", reasons = listOf("synthetic-incomplete"))
                    "coverage" -> raw.copy(coverage = emptyList())
                    else -> raw
                }
            }
            if (name == "cancelled") {
                f.kernel.cancel(f.client.client, fresh.status.reviewId)
                fresh = f.refresh(fresh)
            }
            assertEquals("accepted", f.configs.apply(policyB, fresh.input, fresh.raw).findings.single().disposition,
                "Owner consent alone is insufficient without a complete evaluated certificate")
            if (name !in setOf("policy-error", "policy-not-ready")) {
                assertEquals("accepted", fresh.status.result!!.findings.single().disposition)
            }
            if (name == "policy-not-ready") assertTrue("policy_evaluation_missing" in fresh.status.reasons)
            if (name == "policy-error") assertTrue("result_policy_unavailable" in fresh.status.reasons)
            f.deliver(original); f.deliver(fresh)
            val cut = f.assertOpen(original)
            val stateReason = if (name == "cancelled") "review_cancelled" else "review_incomplete"
            assertTrue("$stateReason:${fresh.status.reviewId}" in cut.reasons, cut.reasons.toString())
            f.assertHistory(original)
        }
    }

    @Test fun unavailableAndIntrinsicFailureEvidenceCannotBeWaivedByFreshConsent() {
        for (name in listOf("unavailable", "execution-evidence", "failed-verification")) fixture("protected-$name").use { f ->
            if (name == "unavailable") f.unavailablePolicies += f.initial.policyDigest
            val produce: (AnalysisInput) -> ProviderResult = { input ->
                val finding = when (name) {
                    "execution-evidence" -> warning(input).copy(severity = "info", evidence = listOf(Evidence("execution_failure", "Synthetic failure")))
                    "failed-verification" -> warning(input).copy(severity = "info", verification = listOf(Verification(expected = "pass", state = "failed")))
                    else -> warning(input)
                }
                result(input, listOf(finding))
            }
            val original = f.review(f.input(f.initial), produce = produce)
            val policyB = f.consent()
            val fresh = f.review(f.input(policyB), produce = produce)
            f.assertComplete(original, if (name == "unavailable") "unavailable" else "open")
            f.assertComplete(fresh, if (name == "unavailable") "accepted" else "open")
            f.deliver(original); f.deliver(fresh)
            if (name == "unavailable") {
                val cut = f.cut()
                assertFalse(cut.ready)
                assertTrue("finding_unavailable:${original.raw.findings.single().fingerprint}" in cut.reasons, cut.reasons.toString())
            } else f.assertOpen(original, exact = true)
            f.assertHistory(original)
        }
    }

    @Test fun ownerPolicyDoesNotChangeFailedExecutionStatusCoverageOrRawDiagnostics() = fixture("failed-result").use { f ->
        val produce: (AnalysisInput) -> ProviderResult = { input ->
            val finding = warning(input).copy(evidence = listOf(Evidence("execution_failure", "Synthetic execution failed")))
            result(input, listOf(finding)).copy(status = "incomplete", reasons = listOf("synthetic-execution-failed"),
                coverage = listOf(Coverage(RULE, input.context.scope, "failed", reason = "synthetic-execution-failed")),
                rawDiagnostics = listOf(RawDiagnostic("Synthetic execution failed", location = finding.location)))
        }
        val original = f.review(f.input(f.initial), produce = produce)
        val policyB = f.consent()
        val fresh = f.review(f.input(policyB), produce = produce)
        for (review in listOf(original, fresh)) {
            assertEquals("incomplete", review.status.state)
            assertEquals(review.raw, review.status.result, "No status, coverage, raw diagnostics or failure may be rewritten by consent")
            assertEquals("open", review.status.result!!.findings.single().disposition)
            f.deliver(review)
        }
        val cut = f.assertOpen(original)
        for (review in listOf(original, fresh)) assertTrue("review_incomplete:${review.status.reviewId}" in cut.reasons)
        f.assertHistory(original)
    }

    @Test fun modelPrincipalDefaultDenyAndTamperedPermitCannotMutateOrUnblock() = fixture("authorization").use { f ->
        val original = f.review(f.input(f.initial))
        f.deliver(original)
        val request = f.request(ConfigAction.AddSuppression(f.suppression()))
        val permit = f.authority.issueForExplicitOwnerAction(f.owner, request, f.clock.instant())
        val model = f.owner.copy(clientId = "synthetic-model", sessionId = "not-enrolled")
        assertFailsWith<ConfigAuthorizationException> { f.authority.issueForExplicitOwnerAction(model, request, f.clock.instant()) }
        assertFailsWith<ConfigAuthorizationException> { f.configs.mutate(request, permit.copy(principal = model)) }
        assertFailsWith<ConfigAuthorizationException> { f.configs.mutate(request.copy(requestId = "tampered-payload"), permit) }
        val deny = ConfigStore(f.configRoot, f.aliases, clock = f.clock)
        assertFailsWith<ConfigAuthorizationException> { deny.mutate(request, permit) }
        assertEquals(f.initial.revisionToken, f.configs.capture(f.binding).revisionToken)
        f.assertOpen(original, exact = true)
        val policyB = f.remember(f.configs.mutate(request, permit))
        assertEquals(request.digest(), policyB.shared.auditEvents.single().requestDigest)
        assertEquals(f.owner, policyB.shared.auditEvents.single().actor)
        val fresh = f.review(f.input(policyB))
        f.assertComplete(fresh, "accepted")
        f.deliver(fresh)
        f.assertReady()
        f.assertHistory(original)
    }

    @Test fun removalMaskingAndExpiryInvalidateReadyCutsWithoutRewritingAcceptedHistory() {
        for (name in listOf("remove", "mask", "expire")) fixture("revocation-$name").use { f ->
            val original = f.review(f.input(f.initial))
            val policyB = f.consent(expiresAt = if (name == "expire") f.clock.millis() + TimeUnit.HOURS.toMillis(1) else null)
            val fresh = f.review(f.input(policyB))
            f.assertComplete(fresh, "accepted")
            f.deliver(original); f.deliver(fresh)
            val ready = f.assertReady()
            val policyC = when (name) {
                "remove" -> f.ownerChange(ConfigAction.RemoveSuppression("consent"))
                "mask" -> f.ownerChange(ConfigAction.MaskSharedSuppression("consent"), ConfigLayer.LOCAL)
                else -> { f.clock.advanceSeconds(TimeUnit.HOURS.toSeconds(2)); f.remember(f.configs.capture(f.binding)) }
            }
            assertNotEquals(policyB.policyDigest, policyC.policyDigest)
            var actions = 0
            val denied = f.kernel.completeGate(f.client.client, ready.cutId) { actions++ }
            assertFalse(denied.allowed)
            assertFalse(denied.actionPerformed)
            assertEquals(0, actions)
            assertTrue("effective_policy_changed" in denied.reasons, denied.reasons.toString())
            assertFalse(f.cut().ready)
            assertEquals("accepted", f.configs.apply(policyB, fresh.input, fresh.raw).findings.single().disposition,
                "The old captured policy remains frozen even when it expires or is removed")
            val reevaluated = f.review(f.input(policyC))
            f.assertComplete(reevaluated, "open")
            f.deliver(reevaluated)
            f.assertOpen(original, exact = true)
            f.assertHistory(original)
            f.assertHistory(fresh)
            f.assertReceiptHistory(fresh)
        }
    }

    @Test fun anEarlierEvaluatedBRowCannotResolveALaterCapturedAObligation() = fixture("sequence").use { f ->
        val policyB = f.consent()
        val earlierB = f.review(f.input(policyB))
        val laterA = f.review(f.input(f.initial))
        val last = f.review(f.input(policyB).copy(requiredRuleIds = listOf(RULE, OTHER_RULE).sorted())) { result(it, emptyList()) }
        f.assertComplete(earlierB, "accepted")
        f.assertComplete(laterA, "open")
        f.assertComplete(last)
        assertTrue(earlierB.status.sequence < laterA.status.sequence)
        assertEquals(laterA.raw.findings, earlierB.raw.findings)
        f.deliver(earlierB); f.deliver(laterA); f.deliver(last)
        f.assertOpen(laterA, exact = true)
        f.assertHistory(laterA)
    }

    @Test fun samePolicyComparableDescendantAbsenceStillWorksWithoutRewritingFailure() = fixture("same-policy-absence").use { f ->
        val original = f.review(f.input(f.initial)) { input ->
            result(input, listOf(warning(input).copy(severity = "info", evidence = listOf(Evidence("execution_failure", "Synthetic failure")))))
        }
        f.assertComplete(original, "open")
        f.deliver(original)
        f.assertOpen(original, exact = true)
        val input = original.input.copy(snapshot = original.input.snapshot.copy(snapshotId = Digests.sha256("same-policy-descendant"),
            parentSnapshotId = original.input.snapshot.snapshotId))
        val absent = f.review(input) { result(it, emptyList()) }
        f.assertComplete(absent)
        assertEquals(original.input.context, absent.input.context)
        f.deliver(absent)
        f.assertReady()
        f.assertHistory(original)
        assertEquals("open", original.status.result!!.findings.single().disposition)
    }

    private fun fixture(name: String, workers: Int = 1) = Fixture(temp.resolve(name), workers)

    private class Fixture(private val root: Path, private val workers: Int) : AutoCloseable {
        val binding = ConfigBinding("owner", "project", "tree", "lineage")
        val owner = ClientIdentity("owner", "synthetic-owner-control", "synthetic-owner-session")
        val clock = TestClock()
        val configRoot = root.resolve("configuration")
        val aliases = RuleAliases(listOf(
            PolicyRule(RULE, aliases = setOf(ALIAS), implementationDigest = Digests.sha256("synthetic-rule-v1")),
            PolicyRule(OTHER_RULE, implementationDigest = Digests.sha256("synthetic-other-v1")),
        ))
        val authority = HmacOwnerAuthority(ByteArray(32) { it.toByte() }, listOf(OwnerSessionRegistration(owner, setOf(binding))))
        private val captures = ConcurrentHashMap<String, ConfigSnapshot>()
        val policyErrors = ConcurrentHashMap.newKeySet<String>()
        val policyNotReady = ConcurrentHashMap.newKeySet<String>()
        val unavailablePolicies = ConcurrentHashMap.newKeySet<String>()
        val configs = ConfigStore(configRoot, aliases, authority,
            lifecycleVerifier = FindingLifecycleVerifier { _, input, finding, rule -> unavailableLifecycle(input, finding, rule) }, clock = clock)
        val initial = remember(configs.capture(binding))
        private val sourceBytes = "fun synthetic() = 1\n// synthetic related location\n".toByteArray()
        private val extraBytes = "val extraSynthetic = 2\n".toByteArray()
        private val sourceRoot = sourceDirectory("source")
        val otherSourceRoot = sourceDirectory("source-copy")
        val extraFile = SnapshotFile("Extra.kt", Digests.sha256(extraBytes), extraBytes.size.toLong())
        private val classpathA = artifact("a.bin", "synthetic-a")
        private val classpathB = artifact("b.bin", "synthetic-b")
        val sameBytesArtifact = artifact("a-copy.bin", "synthetic-a")
        val otherBytesArtifact = artifact("other.bin", "synthetic-other")
        private val template = AnalysisInput(
            SnapshotManifest(Digests.sha256("synthetic-policy-reclassification:${root.fileName}"), binding.workspaceId, binding.worktreeId,
                listOf(SnapshotFile("Main.kt", Digests.sha256(sourceBytes), sourceBytes.size.toLong())), ownerId = binding.ownerId, declaredRoots = listOf(".")),
            sourceRoot.toString(), AnalysisContext(scope = RuleContext(module = ":app"), compilerVersion = "synthetic-1",
                classpath = listOf(classpathA, classpathB), compilerArguments = listOf("-synthetic-a", "-synthetic-b"),
                libraries = mapOf("synthetic-library" to "1"), policies = mapOf("synthetic-mode" to "strict"),
                modelDigest = Digests.sha256("synthetic-model"), dependencyDigest = Digests.sha256("synthetic-dependencies"),
                toolchainDigest = Digests.sha256("synthetic-toolchain"), configurationDigest = initial.policyDigest,
                fullProject = true, compilationFiles = listOf("Main.kt")), requiredRuleIds = listOf(RULE),
            changedPaths = listOf("Main.kt"), baselineSnapshotId = Digests.sha256("synthetic-baseline"))
        private val provider = SyntheticProvider()
        private val fence = ConfigSourceFence(configs, captures)
        private val latest = mutableMapOf<String, AnalysisInput>()
        private var serial = 0
        var kernel = open("ktchecker/test/exact-policy-reclassification/1")
            private set
        val client = try {
            kernel.registerClient(ClientBinding(binding.ownerId, binding.workspaceId, binding.worktreeId, "review-client", binding.lineageId))
        } catch (failure: Throwable) { kernel.close(); throw failure }

        fun input(policy: ConfigSnapshot) = template.copy(context = template.context.copy(configurationDigest = policy.policyDigest))
        fun remember(policy: ConfigSnapshot): ConfigSnapshot = policy.also { captures[it.policyDigest] = it }
        fun suppression(selector: PolicySelector = PolicySelector.Rule(RULE), reason: SuppressionReason = SuppressionReason.ACCEPTED_RISK,
                        expiresAt: Long? = null) = SuppressionSpec("consent", selector, SuppressionScope.Project, reason,
            "Explicit synthetic owner decision", expiresAtEpochMillis = expiresAt)
        fun request(action: ConfigAction, layer: ConfigLayer = ConfigLayer.SHARED) = ConfigMutationRequest(binding, layer,
            configs.capture(binding).revisionToken, action, "owner-request-${++serial}")
        fun ownerChange(action: ConfigAction, layer: ConfigLayer = ConfigLayer.SHARED): ConfigSnapshot {
            val request = request(action, layer)
            return remember(configs.mutate(request, authority.issueForExplicitOwnerAction(owner, request, clock.instant())))
        }
        fun consent(selector: PolicySelector = PolicySelector.Rule(RULE), reason: SuppressionReason = SuppressionReason.ACCEPTED_RISK,
                    expiresAt: Long? = null) = ownerChange(ConfigAction.AddSuppression(suppression(selector, reason, expiresAt)))

        fun submit(input: AnalysisInput, registration: ClientRegistration = client, hold: Hold? = null,
                   produce: (AnalysisInput) -> ProviderResult = { result(it, listOf(warning(it))) }): Submission {
            // Match admission's existing normalization; no extra equality relaxation is used.
            val accepted = input.copy(snapshot = input.snapshot.copy(files = input.snapshot.files.sortedBy { it.path }),
                requiredRuleIds = input.requiredRuleIds.distinct().sorted(), changedPaths = input.changedPaths.distinct().sorted())
            val plan = Planned(produce, hold)
            check(provider.plans.putIfAbsent(executionKey(accepted), plan) == null) { "Synthetic plan key unexpectedly reused" }
            val id = "review-${++serial}"
            val status = kernel.submit(registration.client, SubmitRequest(id, id, accepted))
            latest[registration.binding.clientId] = accepted
            return Submission(accepted, registration, status, plan)
        }
        fun finish(submission: Submission): Retained {
            val status = awaitResult(kernel, submission.registration, submission.status.reviewId)
            val events = events(submission.registration).filter { it.reviewId == status.reviewId }
            assertEquals("accepted", events.first().kind)
            assertTrue(events.any { it.kind == "result" })
            return Retained(submission.input, submission.registration, status, assertNotNull(submission.plan.raw), events)
        }
        fun review(input: AnalysisInput, registration: ClientRegistration = client,
                   produce: (AnalysisInput) -> ProviderResult = { result(it, listOf(warning(it))) }) = finish(submit(input, registration, produce = produce))
        fun refresh(review: Retained) = review.copy(status = kernel.status(review.registration.client, review.status.reviewId),
            events = events(review.registration).filter { it.reviewId == review.status.reviewId })
        fun events(registration: ClientRegistration = client): List<ReviewEvent> = kernel.events(registration.client).let {
            assertFalse(it.hasMore, "This bounded fixture must fit in one event page"); it.events
        }
        fun cut(): GateCut {
            fence.select(checkNotNull(latest[client.binding.clientId]))
            return kernel.prepareGate(client.client)
        }
        fun assertReady(): GateCut = cut().also { assertTrue(it.ready, it.reasons.toString()) }
        fun assertOpen(review: Retained, exact: Boolean = false): GateCut = cut().also { cut ->
            val reason = "open_finding:${review.raw.findings.single().fingerprint}"
            assertFalse(cut.ready)
            assertTrue(reason in cut.reasons, cut.reasons.toString())
            if (exact) assertEquals(listOf(reason), cut.reasons)
        }
        fun assertComplete(review: Retained, disposition: String? = null) {
            assertEquals("complete", review.status.state, review.status.reasons.toString())
            assertTrue(review.status.reasons.isEmpty())
            assertTrue(review.raw.completeFor(review.input))
            assertTrue(review.raw.findings.all { it.disposition == "open" })
            val effective = assertNotNull(review.status.result)
            assertEquals(configs.apply(checkNotNull(captures[checkNotNull(review.input.context.configurationDigest)]), review.input, review.raw), effective)
            assertEquals(review.raw, effective.copy(findings = effective.findings.map { it.copy(disposition = "open") }))
            if (disposition != null) {
                assertTrue(effective.findings.isNotEmpty())
                assertTrue(effective.findings.all { it.disposition == disposition }, effective.findings.toString())
            }
        }
        fun acknowledge(review: Retained) { review.events.forEach { kernel.acknowledge(review.registration.adapter, listOf(it.eventId), insertion(it)) } }
        fun observe(review: Retained) { review.events.forEach { kernel.observe(review.registration.adapter, listOf(it.eventId), observation(it)) } }
        fun deliver(review: Retained) { acknowledge(review); observe(review) }
        fun assertHistory(review: Retained) {
            assertEquals(review.status, kernel.status(review.registration.client, review.status.reviewId))
            assertEquals(review.events, events(review.registration).filter { it.reviewId == review.status.reviewId })
            assertEquals(review.raw, provider.plans[executionKey(review.input)]?.raw)
            assertContentEquals(sourceBytes, Files.readAllBytes(sourceRoot.resolve("Main.kt")))
        }
        fun assertReceiptHistory(review: Retained) {
            for (event in review.events) {
                assertEquals("receipt_conflict", assertFailsWith<KernelException> {
                    kernel.acknowledge(review.registration.adapter, listOf(event.eventId), insertion(event).copy(contextItemId = "conflicting-item"))
                }.code)
                assertEquals("receipt_conflict", assertFailsWith<KernelException> {
                    kernel.observe(review.registration.adapter, listOf(event.eventId), observation(event).copy(modelRequestId = "conflicting-request"))
                }.code)
            }
        }
        fun reopen(identity: String) {
            kernel.close()
            kernel = open(identity)
            assertEquals(client, kernel.registerClient(client))
        }
        private fun open(identity: String): KernelService = KernelService.open(root.resolve("broker"), provider,
            KernelConfig(workerCount = workers, globalParallelism = workers, perWorktreeParallelism = workers,
                heartbeatMillis = 30, leaseMillis = 120, pollMillis = 5), snapshotGate = fence,
            findingProviders = provider.supportedRuleIds.associateWith { setOf(PROVIDER, OTHER_PROVIDER) },
            resultPolicy = ResultPolicy.versioned(identity, object : ResultPolicy {
                override fun isReady(binding: ClientBinding, input: AnalysisInput): Boolean =
                    input.context.configurationDigest?.let { captures.containsKey(it) && it !in policyNotReady } ?: false
                override fun apply(binding: ClientBinding, input: AnalysisInput, raw: ProviderResult): ProviderResult {
                    assertEquals(this@Fixture.binding, ConfigBinding(binding.ownerId, binding.workspaceId, binding.worktreeId, binding.lineageId))
                    val digest = checkNotNull(input.context.configurationDigest)
                    if (digest in policyErrors) throw ConfigException("Synthetic evaluator failure")
                    return configs.apply(checkNotNull(captures[digest]), input, raw) // Captured projection only; no current-config I/O under DB.
                }
            }))
        private fun unavailableLifecycle(input: AnalysisInput, finding: Finding, rule: String): VerifiedLifecycleDisposition? {
            val digest = input.context.configurationDigest ?: return null
            if (digest !in unavailablePolicies) return null
            val selector = configs.findingSelector(checkNotNull(captures[digest]), input, finding)
            return VerifiedLifecycleDisposition(Digests.sha256("synthetic-unavailable-certificate"), input.snapshot.snapshotId,
                digest, rule, finding.providerId, selector.fingerprint, PolicyDisposition.UNAVAILABLE)
        }
        private fun sourceDirectory(name: String): Path = root.resolve(name).also {
            Files.createDirectories(it); Files.write(it.resolve("Main.kt"), sourceBytes); Files.write(it.resolve("Extra.kt"), extraBytes)
        }
        private fun artifact(name: String, text: String): ArtifactIdentity {
            val bytes = text.toByteArray()
            val path = root.resolve("artifacts").resolve(name)
            Files.createDirectories(path.parent); Files.write(path, bytes)
            return ArtifactIdentity("synthetic:$name", Digests.sha256(bytes), path.toString())
        }
        override fun close() {
            provider.plans.values.forEach { it.hold?.release?.countDown() }
            kernel.close()
        }
    }

    /** Synthetic dispatch only, NOT an input-equivalence rule or a production cache key. */
    private data class ExecutionKey(val configuration: String?, val snapshot: String, val required: List<String>)
    private data class Submission(val input: AnalysisInput, val registration: ClientRegistration, val status: ReviewStatus, val plan: Planned)
    private data class Retained(val input: AnalysisInput, val registration: ClientRegistration, val status: ReviewStatus,
                                val raw: ProviderResult, val events: List<ReviewEvent>)
    private class Hold(val entered: CountDownLatch = CountDownLatch(1), val release: CountDownLatch = CountDownLatch(1))
    private class Planned(val produce: (AnalysisInput) -> ProviderResult, val hold: Hold?) { @Volatile var raw: ProviderResult? = null }
    private class SyntheticProvider : AnalysisProvider {
        override val id = PROVIDER
        override val version = "synthetic/1"
        override val supportedRuleIds = setOf(RULE, ALIAS, OTHER_RULE)
        val plans = ConcurrentHashMap<ExecutionKey, Planned>()
        override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
            val plan = checkNotNull(plans[executionKey(input)])
            plan.hold?.let { it.entered.countDown(); await(it.release) }
            cancellation.throwIfCancelled()
            return plan.produce(input).also { plan.raw = it }
        }
    }
    private class TestClock : Clock() {
        @Volatile private var now = Instant.now()
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
        fun advanceSeconds(seconds: Long) { now = now.plusSeconds(seconds) }
    }
    /** Test-only source authority. Actual policy fencing/expiration comes from ConfigStore. */
    private class ConfigSourceFence(private val configs: ConfigStore, private val captures: Map<String, ConfigSnapshot>) : SnapshotGate {
        private val source = ReentrantLock(true)
        @Volatile private var expected: AnalysisInput? = null
        fun select(input: AnalysisInput) { source.withLock { expected = input } }
        override fun <T> withCurrentSnapshot(input: AnalysisInput, action: (SnapshotCheck) -> T): T {
            val captured = checkNotNull(captures[checkNotNull(input.context.configurationDigest)])
            return configs.withCurrentConfig(captured.binding, captured.revisionToken) { check ->
                source.withLock {
                    val selected = checkNotNull(expected)
                    action(SnapshotCheck(current = check.matches && input == selected, snapshotId = selected.snapshot.snapshotId,
                        sourceEpoch = 1L, configurationDigest = check.current.policyDigest, modelDigest = selected.context.modelDigest,
                        dependencyDigest = selected.context.dependencyDigest, toolchainDigest = selected.context.toolchainDigest,
                        analysisEpoch = Digests.sha256("synthetic-source-config:${check.current.policyDigest}:${selected.analysisKey()}"),
                        authoritative = true, reasons = check.reasons, validUntilEpochMillis = check.current.validUntilEpochMillis))
                }
            }
        }
    }

    companion object {
        private const val RULE = "ktc.test.policy-reclassification"
        private const val ALIAS = "synthetic-warning-alias"
        private const val OTHER_RULE = "ktc.test.other-policy-rule"
        private const val PROVIDER = "synthetic-policy-reclassification"
        private const val OTHER_PROVIDER = "synthetic-other-provider"
        private const val WAIT_SECONDS = 15L
        private fun executionKey(input: AnalysisInput) = ExecutionKey(input.context.configurationDigest, input.snapshot.snapshotId, input.requiredRuleIds)
        private fun warning(input: AnalysisInput) = Finding(RULE, "Synthetic warning", "Synthetic diagnosis", "Synthetic policy accounting fixture only",
            SourceLocation("Main.kt", input.snapshot.files.single { it.path == "Main.kt" }.digest), Digests.sha256("constant-provider-fingerprint"), PROVIDER,
            context = input.context.scope, evidence = listOf(Evidence("static_rule", "Synthetic evidence", digest = Digests.sha256("synthetic-evidence"))))
        private fun result(input: AnalysisInput, findings: List<Finding>) = ProviderResult(PROVIDER, "complete", findings,
            input.requiredRuleIds.map { rule -> Coverage(rule, input.context.scope, if (findings.any { it.ruleId == rule }) "findings" else "passed",
                provenance = "executed", certificateId = Digests.sha256("synthetic-coverage:$rule:${input.context.scope.id}"),
                implemented = true, tested = true, qualified = true) }, providerVersion = "synthetic/1")
        private fun insertion(event: ReviewEvent) = ContextReceipt("ack-${event.eventId}", Digests.sha256("synthetic-context:${event.payloadDigest}"),
            "synthetic-item-${event.eventId}", Instant.EPOCH.toString())
        private fun observation(event: ReviewEvent) = ObservationReceipt("observation-${event.eventId}", "synthetic-request-${event.eventId}",
            listOf(insertion(event).contextDigest))
        private fun await(latch: CountDownLatch) = assertTrue(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), "Synthetic barrier timed out")
        private fun awaitResult(kernel: KernelService, client: ClientRegistration, id: String): ReviewStatus {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
            var status = kernel.status(client.client, id)
            while (status.result == null && System.nanoTime() < deadline) {
                Thread.sleep(5)
                status = kernel.status(client.client, id)
            }
            assertNotNull(status.result, "Synthetic provider did not publish within the bounded wait")
            return status
        }
    }
}

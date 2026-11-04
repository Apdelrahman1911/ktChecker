package dev.ktchecker.config

import dev.ktchecker.contracts.AnalysisInput
import dev.ktchecker.contracts.AnalysisMetrics
import dev.ktchecker.contracts.ClientIdentity
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Coverage
import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.Evidence
import dev.ktchecker.contracts.Finding
import dev.ktchecker.contracts.ProviderResult
import dev.ktchecker.contracts.RawDiagnostic
import dev.ktchecker.contracts.SnapshotFile
import dev.ktchecker.contracts.Verification
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cross-requirement acceptance controls, not detector execution or host/OS-owner isolation.
 * Every mutation is an explicit synthetic owner action inside a disposable ConfigFixture.
 * No real catalog setting, reviewed application source, provider or build is changed/run.
 */
class SuppressionScopeAcceptanceTest {
    @Test fun differentIdScopesFollowTheCompleteSpecificityOrderInEitherLayer() {
        for (layer in listOf(ConfigLayer.SHARED, ConfigLayer.LOCAL)) {
            ConfigFixture(identityVerifier = stableVerifier()).use { fixture ->
                val finding = fixture.finding()
                val before = fixture.store.capture(fixture.binding)
                val exact = fixture.store.findingSelector(before, fixture.input(before, finding), finding)
                val identity = assertNotNull(exact.anchor.verifiedIdentity)
                // IDs deliberately favor broad scopes if an implementation sorts by ID first.
                val ranked = listOf(
                    scopeSpec("z-finding", SuppressionScope.Project, selector = exact),
                    scopeSpec("y-range", containingRange(finding), reason = SuppressionReason.REPORTED_FALSE_POSITIVE),
                    scopeSpec("x-symbol", SuppressionScope.Symbol(identity)),
                    scopeSpec("w-file", SuppressionScope.File(finding.location.path, finding.location.fileDigest),
                        reason = SuppressionReason.REPORTED_FALSE_POSITIVE),
                    scopeSpec("v-source-set", SuppressionScope.SourceSet(":app", "main", "jvm")),
                    scopeSpec("u-module", SuppressionScope.Module(":app"), reason = SuppressionReason.REPORTED_FALSE_POSITIVE),
                    scopeSpec("t-target", SuppressionScope.Target("jvm")),
                    scopeSpec("a-project", SuppressionScope.Project, reason = SuppressionReason.REPORTED_FALSE_POSITIVE),
                )
                ranked.forEach { fixture.mutate(ConfigAction.AddSuppression(it), layer) }
                for (index in ranked.indices) {
                    val captured = fixture.store.capture(fixture.binding)
                    val expected = ranked[index]
                    val remaining = ranked.drop(index).map { it.id }.toSet()
                    assertSelected(fixture.store, captured, fixture.input(captured, finding), finding, expected, layer)
                    val explanation = fixture.store.explain(captured, fixture.input(captured, finding), finding)
                    assertEquals(remaining.size, explanation.candidates.size)
                    assertEquals(remaining, explanation.candidates.map { it.id }.toSet())
                    assertTrue(explanation.candidates.all { it.layer == layer && it.state == "matched" })

                    val reopened = ConfigStore(fixture.root.resolve("state"), TEST_ALIASES,
                        identityVerifier = stableVerifier(), clock = fixture.clock)
                    val loaded = reopened.capture(fixture.binding)
                    assertEquals(captured.revisionToken, loaded.revisionToken)
                    assertSelected(reopened, loaded, fixture.input(loaded, finding), finding, expected, layer)
                    val restored = reopened.restoreSnapshot(fixture.store.exportSnapshot(captured), captured.revisionToken)
                    assertSelected(reopened, restored, fixture.input(restored, finding), finding, expected, layer)
                    fixture.mutate(ConfigAction.RemoveSuppression(expected.id), layer)
                }
                val empty = fixture.store.capture(fixture.binding)
                val explanation = fixture.store.explain(empty, fixture.input(empty, finding), finding)
                assertEquals(PolicyDisposition.OPEN, explanation.disposition)
                assertTrue(explanation.candidates.isEmpty())
            }
        }
    }

    @Test fun localBroadRecordsBeatSharedExactRecordsAndRangeTiesUseIdNotInsertionOrder(): Unit =
        ConfigFixture().use { fixture ->
            val finding = fixture.finding()
            val before = fixture.store.capture(fixture.binding)
            val exact = fixture.store.findingSelector(before, fixture.input(before, finding), finding)
            val sharedExact = scopeSpec("a-shared-exact", SuppressionScope.Project, selector = exact)
            val sharedRange = scopeSpec("b-shared-range", containingRange(finding))
            val localProject = scopeSpec("z-local-project", SuppressionScope.Project,
                reason = SuppressionReason.REPORTED_FALSE_POSITIVE)
            val laterRange = scopeSpec("z-local-range", containingRange(finding))
            val earlierRange = scopeSpec("a-local-range", containingRange(finding),
                reason = SuppressionReason.REPORTED_FALSE_POSITIVE)
            fixture.mutate(ConfigAction.AddSuppression(sharedExact))
            fixture.mutate(ConfigAction.AddSuppression(sharedRange))
            var captured = fixture.mutate(ConfigAction.AddSuppression(localProject), ConfigLayer.LOCAL)
            assertSelected(fixture.store, captured, fixture.input(captured, finding), finding, localProject, ConfigLayer.LOCAL)
            assertEquals(3, fixture.store.explain(captured, fixture.input(captured, finding), finding).candidates.count { it.state == "matched" })
            captured = fixture.mutate(ConfigAction.AddSuppression(laterRange), ConfigLayer.LOCAL)
            assertSelected(fixture.store, captured, fixture.input(captured, finding), finding, laterRange, ConfigLayer.LOCAL)
            captured = fixture.mutate(ConfigAction.AddSuppression(earlierRange), ConfigLayer.LOCAL)
            assertSelected(fixture.store, captured, fixture.input(captured, finding), finding, earlierRange, ConfigLayer.LOCAL)
            assertEquals(5, fixture.store.explain(captured, fixture.input(captured, finding), finding).candidates.count { it.state == "matched" })
            val reopened = ConfigStore(fixture.root.resolve("state"), TEST_ALIASES, clock = fixture.clock)
            val loaded = reopened.capture(fixture.binding)
            assertEquals(captured.revisionToken, loaded.revisionToken)
            assertSelected(reopened, loaded, fixture.input(loaded, finding), finding, earlierRange, ConfigLayer.LOCAL)
            captured = fixture.mutate(ConfigAction.RemoveSuppression(earlierRange.id), ConfigLayer.LOCAL)
            assertSelected(fixture.store, captured, fixture.input(captured, finding), finding, laterRange, ConfigLayer.LOCAL)
            captured = fixture.mutate(ConfigAction.RemoveSuppression(laterRange.id), ConfigLayer.LOCAL)
            assertSelected(fixture.store, captured, fixture.input(captured, finding), finding, localProject, ConfigLayer.LOCAL)
            captured = fixture.mutate(ConfigAction.RemoveSuppression(localProject.id), ConfigLayer.LOCAL)
            assertSelected(fixture.store, captured, fixture.input(captured, finding), finding, sharedExact, ConfigLayer.SHARED)
        }

    @Test fun nonmatchingAndExpiredLocalOverridesShadowOnlyTheirIdAndRemovalDoesNotRemoveAMask(): Unit =
        ConfigFixture().use { fixture ->
            val finding = fixture.finding()
            val inherited = scopeSpec("same-id", SuppressionScope.Project)
            val unrelated = scopeSpec("other-shared", SuppressionScope.Project,
                reason = SuppressionReason.REPORTED_FALSE_POSITIVE)
            fixture.mutate(ConfigAction.AddSuppression(inherited))
            fixture.mutate(ConfigAction.AddSuppression(unrelated))
            val local = scopeSpec("same-id", SuppressionScope.File("src/Another.kt", Digests.sha256("different file")),
                reason = SuppressionReason.REPORTED_FALSE_POSITIVE).copy(expiresAtEpochMillis = fixture.clock.millis() + 10)
            var captured = fixture.mutate(ConfigAction.AddSuppression(local), ConfigLayer.LOCAL)
            assertSelected(fixture.store, captured, fixture.input(captured, finding), finding, unrelated, ConfigLayer.SHARED)
            assertEquals(mapOf(
                (ConfigLayer.SHARED to inherited.id) to "overridden",
                (ConfigLayer.SHARED to unrelated.id) to "matched",
                (ConfigLayer.LOCAL to local.id) to "source_changed",
            ), candidateStates(fixture, captured, finding))
            fixture.clock.advance(10)
            captured = fixture.store.capture(fixture.binding)
            assertSelected(fixture.store, captured, fixture.input(captured, finding), finding, unrelated, ConfigLayer.SHARED)
            assertEquals("overridden", candidateStates(fixture, captured, finding)[ConfigLayer.SHARED to inherited.id])
            assertEquals("expired", candidateStates(fixture, captured, finding)[ConfigLayer.LOCAL to local.id])
            captured = fixture.mutate(ConfigAction.RemoveSuppression(unrelated.id))
            assertEquals(PolicyDisposition.OPEN, fixture.store.explain(captured, fixture.input(captured, finding), finding).disposition)
            captured = fixture.mutate(ConfigAction.MaskSharedSuppression(inherited.id), ConfigLayer.LOCAL)
            assertEquals("masked", candidateStates(fixture, captured, finding)[ConfigLayer.SHARED to inherited.id])
            captured = fixture.mutate(ConfigAction.RemoveSuppression(local.id), ConfigLayer.LOCAL)
            assertEquals(listOf(inherited.id), captured.local.suppressionMasks)
            assertTrue(captured.local.suppressions.isEmpty())
            assertEquals(mapOf((ConfigLayer.SHARED to inherited.id) to "masked"), candidateStates(fixture, captured, finding))
            assertEquals(PolicyDisposition.OPEN, fixture.store.explain(captured, fixture.input(captured, finding), finding).disposition)
            captured = fixture.mutate(ConfigAction.UnmaskSharedSuppression(inherited.id), ConfigLayer.LOCAL)
            assertSelected(fixture.store, captured, fixture.input(captured, finding), finding, inherited, ConfigLayer.SHARED)
        }

    @Test fun verifiedFileAndRangeRequireFreshMaterialAndEvidenceEvenWhenRawBytesStillMatch() {
        for (scopeKind in listOf("file", "range")) {
            var available = true
            var material = Digests.sha256("stable material")
            var evidence = Digests.sha256("stable evidence")
            var occurrence = "occurrence-1"
            var artifact = Digests.sha256("stable provider artifact")
            var certificate = "first-certificate"
            val verifier = FindingIdentityVerifier { binding, input, finding, rule ->
                if (available) testIdentity(binding, input, finding, rule, material, evidence, occurrence, artifact)
                    .copy(certificateId = certificate) else null
            }
            ConfigFixture(identityVerifier = verifier).use { fixture ->
                val finding = fixture.finding()
                val before = fixture.store.capture(fixture.binding)
                val exact = fixture.store.findingSelector(before, fixture.input(before, finding), finding)
                val identity = assertNotNull(exact.anchor.verifiedIdentity)
                val scope = if (scopeKind == "file") {
                    SuppressionScope.File(finding.location.path, finding.location.fileDigest, identity)
                } else containingRange(finding).copy(verifiedIdentity = identity)
                val captured = fixture.suppress(scope = scope)
                assertEquals(PolicyDisposition.ACCEPTED, fixture.store.explain(captured, fixture.input(captured, finding), finding).disposition)
                certificate = "independently-renewed-certificate"
                val moved = fixture.finding(path = "src/Renamed.kt", code = "\nfun example() = 1", line = 2)
                val movedInput = fixture.input(captured, moved)
                assertNotEquals(identity.snapshotId, movedInput.snapshot.snapshotId)
                assertEquals(exact.fingerprint, fixture.store.findingSelector(captured, movedInput, moved).fingerprint)
                assertEquals(PolicyDisposition.ACCEPTED, fixture.store.explain(captured, movedInput, moved).disposition, scopeKind)

                fun assertRawAnchorDoesNotRescue(expectedState: String) {
                    val input = fixture.input(captured, finding)
                    val explanation = fixture.store.explain(captured, input, finding)
                    assertEquals(identity.providerId, finding.providerId)
                    assertEquals(exact.anchor.path, finding.location.path)
                    assertEquals(exact.anchor.fileDigest, finding.location.fileDigest)
                    assertEquals(PolicyDisposition.OPEN, explanation.disposition, scopeKind)
                    assertEquals(expectedState, explanation.candidates.single().state, scopeKind)
                }
                available = false
                assertRawAnchorDoesNotRescue("identity_unverified")
                available = true
                // Relevant bindings may change even when this source file's raw bytes do not.
                material = Digests.sha256("changed body or binding facts")
                assertRawAnchorDoesNotRescue("symbol_or_material_evidence_changed")
                material = identity.materialDigest
                evidence = Digests.sha256("changed evidence content")
                assertRawAnchorDoesNotRescue("symbol_or_material_evidence_changed")
                evidence = identity.evidenceDigest
                occurrence = "another occurrence of the same symbol"
                assertRawAnchorDoesNotRescue("symbol_or_material_evidence_changed")
                occurrence = identity.evidenceKey
                artifact = Digests.sha256("changed provider artifact")
                assertRawAnchorDoesNotRescue("symbol_or_material_evidence_changed")
                artifact = identity.providerArtifactDigest
                assertEquals(PolicyDisposition.ACCEPTED, fixture.store.explain(captured, fixture.input(captured, finding), finding).disposition)
                assertEquals(captured.revisionToken, fixture.store.capture(fixture.binding).revisionToken)
            }
        }
    }

    @Test fun enablingAVerifierCannotPromoteAnOldRawExactAcceptanceAcrossMovement(): Unit =
        ConfigFixture().use { fixture ->
            val finding = fixture.finding()
            val before = fixture.store.capture(fixture.binding)
            val raw = fixture.store.findingSelector(before, fixture.input(before, finding), finding)
            assertNull(raw.anchor.verifiedIdentity)
            val accepted = fixture.suppress(selector = raw)
            val bytes = Files.readAllBytes(fixture.store.storagePaths(fixture.binding).shared)
            val nowVerified = ConfigStore(fixture.root.resolve("state"), TEST_ALIASES,
                identityVerifier = stableVerifier(), clock = fixture.clock)
            val captured = nowVerified.capture(fixture.binding)
            assertEquals(accepted.revisionToken, captured.revisionToken)
            val verified = nowVerified.findingSelector(captured, fixture.input(captured, finding), finding)
            assertNotNull(verified.anchor.verifiedIdentity)
            assertNotEquals(raw.fingerprint, verified.fingerprint)
            assertEquals(PolicyDisposition.ACCEPTED, nowVerified.explain(captured, fixture.input(captured, finding), finding).disposition)
            val moved = fixture.finding(code = "\nfun example() = 1", line = 2)
            val renamed = fixture.finding(path = "src/Renamed.kt")
            for (current in listOf(moved, renamed)) {
                val input = fixture.input(captured, current)
                assertEquals(verified.fingerprint, nowVerified.findingSelector(captured, input, current).fingerprint)
                val explanation = nowVerified.explain(captured, input, current)
                assertEquals(PolicyDisposition.OPEN, explanation.disposition)
                assertEquals("fingerprint_changed", explanation.candidates.single().state)
            }
            assertEquals(raw, captured.shared.suppressions.single().spec.selector)
            assertTrue(bytes.contentEquals(Files.readAllBytes(nowVerified.storagePaths(fixture.binding).shared)))
        }

    @Test fun exactFingerprintProjectsContextClassificationAndCanonicalEvidenceNotDisplayLabels(): Unit =
        ConfigFixture().use { fixture ->
            val finding = fixture.finding().copy(evidence = listOf(
                Evidence("static_rule", "First evidence", digest = Digests.sha256("evidence one")),
                Evidence("related_rule", "Second evidence", "synthetic-evidence-ref", Digests.sha256("evidence two")),
            ))
            val before = fixture.store.capture(fixture.binding)
            val exact = fixture.store.findingSelector(before, fixture.input(before, finding), finding)
            val captured = fixture.suppress(selector = exact)
            val equivalent = listOf(
                finding.copy(evidence = finding.evidence.reversed()),
                finding.copy(fingerprint = "another provider-selected display fingerprint"),
                finding.copy(title = "Different title", diagnosis = "Different display diagnosis", whyItMatters = "Different display rationale",
                    fixHint = "Different display hint", upstreamId = "display-only-upstream", symbolId = "unverified-symbol",
                    semanticDigest = Digests.sha256("unverified semantic claim"), location = finding.location.copy(excerpt = "different excerpt")),
            )
            for (current in equivalent) {
                val input = fixture.input(captured, current)
                assertEquals(exact.fingerprint, fixture.store.findingSelector(captured, input, current).fingerprint)
                assertEquals(PolicyDisposition.ACCEPTED, fixture.store.explain(captured, input, current).disposition)
            }
            val changed = listOf(
                Triple("provider", finding.copy(providerId = "another-static-provider"), "identity_changed"),
                Triple("module", finding.copy(context = finding.context.copy(module = ":other")), "identity_changed"),
                Triple("source set", finding.copy(context = finding.context.copy(sourceSet = "test")), "identity_changed"),
                Triple("compilation", finding.copy(context = finding.context.copy(compilation = "integration")), "identity_changed"),
                Triple("severity", finding.copy(severity = "info"), "identity_changed"),
                Triple("kind", finding.copy(kind = "policy"), "identity_changed"),
                Triple("certainty", finding.copy(certainty = "high"), "identity_changed"),
                Triple("range", finding.copy(location = finding.location.copy(column = 1)), "fingerprint_changed"),
                Triple("evidence content", finding.copy(evidence = listOf(finding.evidence[0].copy(digest = Digests.sha256("changed evidence")),
                    finding.evidence[1])), "fingerprint_changed"),
                Triple("evidence occurrence", finding.copy(evidence = finding.evidence + finding.evidence[0]), "fingerprint_changed"),
            )
            for ((dimension, current, state) in changed) {
                val input = fixture.input(captured, current)
                assertEquals(finding.fingerprint, current.fingerprint, dimension)
                assertEquals(finding.location.fileDigest, current.location.fileDigest, dimension)
                assertNotEquals(exact.fingerprint, fixture.store.findingSelector(captured, input, current).fingerprint, dimension)
                val explanation = fixture.store.explain(captured, input, current)
                assertEquals(RULE, explanation.canonicalRuleId, dimension)
                assertEquals(PolicyDisposition.OPEN, explanation.disposition, dimension)
                assertEquals(state, explanation.candidates.single().state, dimension)
            }
        }

    @Test fun concurrentLocalOwnerEditsCommuteAcrossWorktreesLineagesAndOwnerNamespaces(): Unit =
        ConfigFixture().use { fixture ->
            val bindings = listOf(
                fixture.binding,
                fixture.binding.copy(worktreeId = "second-worktree"),
                fixture.binding.copy(lineageId = "second-lineage"),
                fixture.binding.copy(ownerId = "second-owner"),
            )
            val principals = bindings.mapIndexed { index, binding ->
                ClientIdentity(binding.ownerId, "explicit-owner-$index", "owner-session-$index")
            }
            val authority = enrolledAuthority(bindings, principals)
            val stores = bindings.map { ConfigStore(fixture.root.resolve("state"), TEST_ALIASES, authority, clock = fixture.clock) }
            val inherited = scopeSpec("team", SuppressionScope.Project)
            explicitMutation(fixture, stores[0], authority, principals[0], bindings[0],
                ConfigAction.AddSuppression(inherited), ConfigLayer.SHARED, "initial-shared")
            val initial = stores.mapIndexed { index, store -> store.capture(bindings[index]) }
            val paths = stores.mapIndexed { index, store -> store.storagePaths(bindings[index]) }
            assertEquals(1, paths.take(3).map { it.shared }.toSet().size)
            assertEquals(1, paths.take(3).map { it.lock }.toSet().size)
            assertEquals(4, paths.map { it.local }.toSet().size)
            assertNotEquals(paths[0].shared, paths[3].shared)
            assertNotEquals(paths[0].lock, paths[3].lock)
            val sharedBytes = Files.readAllBytes(paths[0].shared)
            val localSpecs = listOf(
                null,
                inherited.copy(reason = SuppressionReason.REPORTED_FALSE_POSITIVE, justification = "Second worktree owner report"),
                inherited.copy(scope = SuppressionScope.SourceSet(":app", "main", "jvm"), justification = "Separate lineage owner acceptance"),
                inherited.copy(reason = SuppressionReason.REPORTED_FALSE_POSITIVE, justification = "Other registered owner report"),
            )
            val requests = bindings.mapIndexed { index, binding ->
                val spec = localSpecs[index]
                val action = if (spec == null) ConfigAction.MaskSharedSuppression(inherited.id) else ConfigAction.AddSuppression(spec)
                ConfigMutationRequest(binding, ConfigLayer.LOCAL, initial[index].revisionToken, action, "local-owner-edit-$index")
            }
            val permits = requests.mapIndexed { index, request ->
                authority.issueForExplicitOwnerAction(principals[index], request, fixture.clock.instant())
            }
            val ready = CountDownLatch(bindings.size)
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(bindings.size)
            try {
                val futures = bindings.indices.map { index -> pool.submit<ConfigSnapshot> {
                    ready.countDown()
                    assertTrue(start.await(BARRIER_SECONDS, TimeUnit.SECONDS), "Concurrent owner start barrier timed out")
                    // No catches: unexpected conflicts, authorization or storage failures fail the test.
                    stores[index].mutate(requests[index], permits[index])
                } }
                assertTrue(ready.await(BARRIER_SECONDS, TimeUnit.SECONDS), "All explicit owner workers must reach the barrier")
                start.countDown()
                val committed = futures.map { it.get(BARRIER_SECONDS, TimeUnit.SECONDS) }
                val expected = listOf(PolicyDisposition.OPEN, PolicyDisposition.SUPPRESSED, PolicyDisposition.ACCEPTED, PolicyDisposition.SUPPRESSED)
                val finding = fixture.finding()
                committed.forEachIndexed { index, snapshot ->
                    val current = stores[index].capture(bindings[index])
                    assertEquals(snapshot.revisionToken, current.revisionToken)
                    assertEquals(1L, current.local.revision.revision)
                    assertEquals(initial[index].shared.revision, current.shared.revision)
                    assertEquals(principals[index], current.local.auditEvents.single().actor)
                    assertEquals(requests[index].requestId, current.local.auditEvents.single().requestId)
                    assertEquals(requests[index].digest(), current.local.auditEvents.single().requestDigest)
                    assertEquals(expected[index], stores[index].explain(current, inputForBinding(fixture, current, finding), finding).disposition)
                    if (index == 0) {
                        assertEquals(listOf(inherited.id), current.local.suppressionMasks)
                        assertTrue(current.local.suppressions.isEmpty())
                    } else {
                        assertTrue(current.local.suppressionMasks.isEmpty())
                        assertEquals(localSpecs[index], current.local.suppressions.single().spec)
                        assertEquals(principals[index], current.local.suppressions.single().createdBy)
                    }
                }
                assertTrue(sharedBytes.contentEquals(Files.readAllBytes(paths[0].shared)))
                assertFalse(Files.exists(paths[3].shared))
                val untouched = bindings[0].copy(worktreeId = "untouched-worktree", lineageId = "untouched-lineage")
                val untouchedPolicy = stores[0].capture(untouched)
                assertEquals(0L, untouchedPolicy.local.revision.revision)
                assertTrue(untouchedPolicy.local.suppressionMasks.isEmpty())
                assertTrue(untouchedPolicy.local.suppressions.isEmpty())
                assertEquals(PolicyDisposition.ACCEPTED,
                    stores[0].explain(untouchedPolicy, inputForBinding(fixture, untouchedPolicy, finding), finding).disposition)
            } finally {
                start.countDown()
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(BARRIER_SECONDS, TimeUnit.SECONDS), "All synthetic owner workers must terminate")
            }
        }

    @Test fun sharedCommitInvalidatesAnotherWorktreePendingLocalEditUntilExplicitReinspection(): Unit =
        ConfigFixture().use { fixture ->
            val bindings = listOf(fixture.binding, fixture.binding.copy(worktreeId = "other-worktree", lineageId = "other-lineage"))
            val principals = listOf(OWNER.copy(clientId = "shared-owner", sessionId = "shared-owner-session"),
                OWNER.copy(clientId = "local-owner", sessionId = "local-owner-session"))
            val authority = enrolledAuthority(bindings, principals)
            val sharedStore = ConfigStore(fixture.root.resolve("state"), TEST_ALIASES, authority, clock = fixture.clock)
            val localStore = ConfigStore(fixture.root.resolve("state"), TEST_ALIASES, authority, clock = fixture.clock)
            val inherited = scopeSpec("team", SuppressionScope.Project)
            explicitMutation(fixture, sharedStore, authority, principals[0], bindings[0],
                ConfigAction.AddSuppression(inherited), ConfigLayer.SHARED, "before-review")
            val oldShared = sharedStore.capture(bindings[0])
            val oldLocal = localStore.capture(bindings[1])
            val frozen = localStore.exportSnapshot(oldLocal)
            val localAction = ConfigAction.AddSuppression(inherited.copy(reason = SuppressionReason.REPORTED_FALSE_POSITIVE,
                justification = "Other worktree explicit owner report"))
            val pending = ConfigMutationRequest(bindings[1], ConfigLayer.LOCAL, oldLocal.revisionToken, localAction, "pending-local")
            val pendingPermit = authority.issueForExplicitOwnerAction(principals[1], pending, fixture.clock.instant())
            val sharedRequest = ConfigMutationRequest(bindings[0], ConfigLayer.SHARED, oldShared.revisionToken,
                ConfigAction.RemoveSuppression(inherited.id), "shared-changed")
            val sharedPermit = authority.issueForExplicitOwnerAction(principals[0], sharedRequest, fixture.clock.instant())
            val localReady = CountDownLatch(1)
            val sharedCommitted = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val localFuture = pool.submit<ConfigConflictException> {
                    localReady.countDown()
                    assertTrue(sharedCommitted.await(BARRIER_SECONDS, TimeUnit.SECONDS), "Shared commit barrier timed out")
                    assertFailsWith<ConfigConflictException> { localStore.mutate(pending, pendingPermit) }
                }
                val sharedFuture = pool.submit<ConfigSnapshot> {
                    assertTrue(localReady.await(BARRIER_SECONDS, TimeUnit.SECONDS), "Pending local worker must be ready")
                    val committed = sharedStore.mutate(sharedRequest, sharedPermit)
                    sharedCommitted.countDown()
                    committed
                }
                val committedShared = sharedFuture.get(BARRIER_SECONDS, TimeUnit.SECONDS)
                val conflict = localFuture.get(BARRIER_SECONDS, TimeUnit.SECONDS)
                val current = localStore.capture(bindings[1])
                assertEquals(current.revisionToken, conflict.current)
                assertEquals(committedShared.shared.revision, current.shared.revision)
                assertEquals(0L, current.local.revision.revision)
                assertFalse(Files.exists(localStore.storagePaths(bindings[1]).local))
                assertEquals(listOf("before-review", "shared-changed"), current.shared.auditEvents.map { it.requestId })
                assertTrue(current.shared.auditEvents.all { it.actor == principals[0] })
                val finding = fixture.finding()
                assertEquals(PolicyDisposition.OPEN, localStore.explain(current, inputForBinding(fixture, current, finding), finding).disposition)
                val restored = localStore.restoreSnapshot(frozen, oldLocal.revisionToken)
                assertEquals(PolicyDisposition.ACCEPTED, localStore.explain(restored, inputForBinding(fixture, restored, finding), finding).disposition)
                localStore.withCurrentConfig(bindings[1], oldLocal.revisionToken) { assertFalse(it.matches) }
                val sharedBytes = Files.readAllBytes(localStore.storagePaths(bindings[1]).shared)
                val fresh = ConfigMutationRequest(bindings[1], ConfigLayer.LOCAL, current.revisionToken, localAction, "reinspected-local")
                val freshPermit = authority.issueForExplicitOwnerAction(principals[1], fresh, fixture.clock.instant())
                assertNotEquals(pendingPermit.requestDigest, freshPermit.requestDigest)
                val applied = localStore.mutate(fresh, freshPermit)
                assertEquals(1L, applied.local.revision.revision)
                assertEquals(principals[1], applied.local.auditEvents.single().actor)
                assertEquals(fresh.requestId, applied.local.auditEvents.single().requestId)
                assertEquals(fresh.digest(), applied.local.auditEvents.single().requestDigest)
                assertEquals(PolicyDisposition.SUPPRESSED, localStore.explain(applied, inputForBinding(fixture, applied, finding), finding).disposition)
                assertTrue(sharedBytes.contentEquals(Files.readAllBytes(localStore.storagePaths(bindings[1]).shared)))
                assertEquals(PolicyDisposition.ACCEPTED, localStore.explain(restored, inputForBinding(fixture, restored, finding), finding).disposition)
            } finally {
                localReady.countDown()
                sharedCommitted.countDown()
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(BARRIER_SECONDS, TimeUnit.SECONDS), "All ordered owner workers must terminate")
            }
        }

    @Test fun disabledSuppressedAndBaselinedPoliciesRemainDistinctWhenAllArePresent(): Unit =
        ConfigFixture().use { fixture ->
            val finding = fixture.finding()
            val before = fixture.store.capture(fixture.binding)
            val exact = fixture.store.findingSelector(before, fixture.input(before, finding), finding)
            val baseline = BaselineSpec("explicit-baseline", exact, "Existing synthetic warning accounted separately")
            fixture.mutate(ConfigAction.AddBaseline(baseline))
            val suppression = scopeSpec("explicit-report", SuppressionScope.Project, reason = SuppressionReason.REPORTED_FALSE_POSITIVE)
            fixture.mutate(ConfigAction.AddSuppression(suppression))
            // This is a synthetic fixture rule in disposable state, never a real catalog disable.
            var captured = fixture.mutate(ConfigAction.SetRuleEnabled(RULE, false))
            var explanation = fixture.store.explain(captured, fixture.input(captured, finding), finding)
            assertEquals(PolicyDisposition.DISABLED, explanation.disposition)
            assertNull(explanation.policyEntryId)
            assertEquals(listOf(suppression), captured.shared.suppressions.map { it.spec })
            assertEquals(listOf(baseline), captured.shared.baselines.map { it.spec })
            captured = fixture.mutate(ConfigAction.SetRuleEnabled(RULE, true), ConfigLayer.LOCAL)
            assertSelected(fixture.store, captured, fixture.input(captured, finding), finding, suppression, ConfigLayer.SHARED)
            captured = fixture.mutate(ConfigAction.RemoveSuppression(suppression.id))
            explanation = fixture.store.explain(captured, fixture.input(captured, finding), finding)
            assertEquals(PolicyDisposition.BASELINED, explanation.disposition)
            assertEquals(baseline.id, explanation.policyEntryId)
            assertEquals(ConfigLayer.SHARED, explanation.layer)
            captured = fixture.mutate(ConfigAction.RemoveBaseline(baseline.id))
            assertEquals(PolicyDisposition.OPEN, fixture.store.explain(captured, fixture.input(captured, finding), finding).disposition)
            captured = fixture.mutate(ConfigAction.ResetRuleOverride(RULE), ConfigLayer.LOCAL)
            assertEquals(PolicyDisposition.DISABLED, fixture.store.explain(captured, fixture.input(captured, finding), finding).disposition)
            captured = fixture.mutate(ConfigAction.ResetRuleOverride(RULE))
            assertEquals(PolicyDisposition.OPEN, fixture.store.explain(captured, fixture.input(captured, finding), finding).disposition)
        }

    @Test fun mixedAccountingPreservesEveryFailureCancellationAndRawDiagnosticField(): Unit =
        ConfigFixture().use { fixture ->
            val accepted = fixture.finding(path = "src/Accepted.kt").copy(disposition = "fixed")
            val suppressed = fixture.finding(path = "src/Suppressed.kt").copy(disposition = "fixed")
            val baselined = fixture.finding(path = "src/Baselined.kt").copy(disposition = "accepted")
            val fixed = fixture.finding(path = "src/Fixed.kt")
            val unavailable = fixture.finding(path = "src/Unavailable.kt")
            val disabled = fixture.finding(path = "src/Disabled.kt", rule = OTHER_RULE).copy(disposition = "fixed")
            val open = fixture.finding(path = "src/Open.kt").copy(disposition = "baselined")
            val executionFailure = accepted.copy(title = "Current execution failed",
                evidence = listOf(Evidence("execution_failure", "Synthetic current execution failure")), disposition = "baselined")
            val failedVerification = baselined.copy(title = "Current verification failed",
                verification = listOf(Verification("build", "Synthetic build must succeed", state = "failed")), disposition = "suppressed")
            val disabledFailure = disabled.copy(title = "Current worker failed",
                evidence = listOf(Evidence("worker_failure", "Synthetic current worker failure")), disposition = "disabled")
            val compilerFailure = fixture.finding(path = "src/CompilerFailure.kt", rule = COMPILE_RULE).copy(
                severity = "error", providerId = "kotlin-compiler", evidence = listOf(Evidence("compiler_diagnostic", "Synthetic compiler error")),
                disposition = "accepted")
            val before = fixture.store.capture(fixture.binding)
            val baselineSelector = fixture.store.findingSelector(before, fixture.input(before, baselined), baselined)
            fixture.suppress(id = "file-risk", scope = SuppressionScope.File(accepted.location.path, accepted.location.fileDigest))
            fixture.suppress(id = "file-report", scope = SuppressionScope.File(suppressed.location.path, suppressed.location.fileDigest),
                reason = SuppressionReason.REPORTED_FALSE_POSITIVE)
            fixture.mutate(ConfigAction.AddBaseline(BaselineSpec("accounted-baseline", baselineSelector, "Explicit synthetic owner baseline")))
            val captured = fixture.mutate(ConfigAction.SetRuleEnabled(OTHER_RULE, false))
            val findings = listOf(accepted, suppressed, baselined, fixed, unavailable, disabled, open,
                executionFailure, failedVerification, disabledFailure, compilerFailure)
            val input = batchInput(fixture, captured, findings)
            val certificates = mapOf(
                fixed.location.path to lifecycleCertificate(fixture.store, captured, input, fixed, PolicyDisposition.FIXED),
                unavailable.location.path to lifecycleCertificate(fixture.store, captured, input, unavailable, PolicyDisposition.UNAVAILABLE),
            )
            // Ordinary historical warning proofs only. Current failure controls have no lifecycle proof.
            val store = ConfigStore(fixture.root.resolve("state"), TEST_ALIASES, fixture.authority,
                lifecycleVerifier = FindingLifecycleVerifier { _, _, finding, _ -> certificates[finding.location.path] }, clock = fixture.clock)
            val expected = listOf(PolicyDisposition.ACCEPTED, PolicyDisposition.SUPPRESSED, PolicyDisposition.BASELINED,
                PolicyDisposition.FIXED, PolicyDisposition.UNAVAILABLE, PolicyDisposition.DISABLED, PolicyDisposition.OPEN,
                PolicyDisposition.OPEN, PolicyDisposition.OPEN, PolicyDisposition.OPEN, PolicyDisposition.OPEN)
            val states = listOf("failed" to "failed", "unavailable" to "unavailable", "cancelled" to "cancelled",
                "incomplete" to "incomplete", "complete" to "failed")
            for ((status, compilerCoverage) in states) {
                val result = ProviderResult("synthetic-mixed-provider", status, findings,
                    coverage = listOf(
                        Coverage(RULE, TEST_CONTEXT, "findings", required = true, depth = "syntax", provenance = "executed",
                            certificateId = "synthetic-static-coverage"),
                        Coverage(OTHER_RULE, TEST_CONTEXT, "not_run", "Explicit fixture policy disabled", required = true, depth = "syntax"),
                        Coverage(COMPILE_RULE, TEST_CONTEXT, compilerCoverage, "Synthetic compiler execution remains $compilerCoverage"),
                    ),
                    reasons = listOf("Synthetic $status execution/accounting", "Raw failure diagnostics remain visible"),
                    rawDiagnostics = listOf(
                        RawDiagnostic("Synthetic raw compiler failure", "error", "FIXTURE_COMPILER_ERROR", compilerFailure.location),
                        RawDiagnostic("Synthetic raw worker failure", "fatal", "FIXTURE_WORKER_ERROR"),
                    ),
                    metrics = AnalysisMetrics(17, 11, 123_456, 99), providerVersion = "synthetic-policy-acceptance-1")
                val applied = store.apply(captured, input, result)
                assertEquals(result.copy(findings = result.findings.zip(expected).map { (finding, disposition) ->
                    finding.copy(disposition = disposition.wireValue)
                }), applied, status)
                assertEquals(result, applied.copy(findings = result.findings), "Only finding dispositions may change: $status")
                assertFalse(applied.complete(), status)
                assertFalse(applied.completeFor(input), status)
                assertEquals(expected.map { it.wireValue }, applied.findings.map { it.disposition }, status)
            }
            assertEquals(captured.revisionToken, store.capture(fixture.binding).revisionToken)
        }

    private fun scopeSpec(id: String, scope: SuppressionScope, selector: PolicySelector = PolicySelector.Rule(RULE),
                          reason: SuppressionReason = SuppressionReason.ACCEPTED_RISK) =
        SuppressionSpec(id, selector, scope, reason, "Explicit synthetic owner decision for $id")

    private fun containingRange(finding: Finding) = with(finding.location) {
        SuppressionScope.Range(path, fileDigest, line, column, endLine, endColumn)
    }

    private fun stableVerifier() = FindingIdentityVerifier { binding, input, finding, rule ->
        testIdentity(binding, input, finding, rule)
    }

    private fun assertSelected(store: ConfigStore, snapshot: ConfigSnapshot, input: AnalysisInput, finding: Finding,
                               expected: SuppressionSpec, layer: ConfigLayer) {
        val explanation = store.explain(snapshot, input, finding)
        assertEquals(if (expected.reason == SuppressionReason.ACCEPTED_RISK) PolicyDisposition.ACCEPTED else PolicyDisposition.SUPPRESSED,
            explanation.disposition, expected.id)
        assertEquals(RULE, explanation.canonicalRuleId, expected.id)
        assertEquals(expected.id, explanation.policyEntryId)
        assertEquals(layer, explanation.layer, expected.id)
        assertEquals(expected.justification, explanation.explanation, expected.id)
    }

    private fun candidateStates(fixture: ConfigFixture, snapshot: ConfigSnapshot, finding: Finding): Map<Pair<ConfigLayer, String>, String> {
        val attempts = fixture.store.explain(snapshot, fixture.input(snapshot, finding), finding).candidates
        val states = attempts.associate { (it.layer to it.id) to it.state }
        assertEquals(attempts.size, states.size, "Every candidate must retain its layer and ID")
        return states
    }

    private fun inputForBinding(fixture: ConfigFixture, snapshot: ConfigSnapshot, finding: Finding): AnalysisInput {
        val input = fixture.input(snapshot, finding)
        return input.copy(snapshot = input.snapshot.copy(ownerId = snapshot.binding.ownerId,
            workspaceId = snapshot.binding.workspaceId, worktreeId = snapshot.binding.worktreeId))
    }

    private fun enrolledAuthority(bindings: List<ConfigBinding>, principals: List<ClientIdentity>): HmacOwnerAuthority {
        assertEquals(bindings.size, principals.size)
        return HmacOwnerAuthority(ByteArray(32) { (it + 11).toByte() }, bindings.zip(principals).map { (binding, principal) ->
            OwnerSessionRegistration(principal, setOf(binding))
        })
    }

    private fun explicitMutation(fixture: ConfigFixture, store: ConfigStore, authority: HmacOwnerAuthority,
                                 principal: ClientIdentity, binding: ConfigBinding, action: ConfigAction,
                                 layer: ConfigLayer, requestId: String): ConfigSnapshot {
        val request = ConfigMutationRequest(binding, layer, store.capture(binding).revisionToken, action, requestId)
        return store.mutate(request, authority.issueForExplicitOwnerAction(principal, request, fixture.clock.instant()))
    }

    private fun batchInput(fixture: ConfigFixture, snapshot: ConfigSnapshot, findings: List<Finding>): AnalysisInput {
        val files = findings.map { SnapshotFile(it.location.path, it.location.fileDigest, 20) }.distinct().sortedBy { it.path }
        assertEquals(files.size, files.map { it.path }.toSet().size, "One raw-byte identity per logical file")
        val input = inputForBinding(fixture, snapshot, findings.first())
        return input.copy(snapshot = input.snapshot.copy(snapshotId = Digests.sha256(ContractJson.encodeToString(files)), files = files),
            requiredRuleIds = listOf(RULE, OTHER_RULE, COMPILE_RULE))
    }

    private fun lifecycleCertificate(store: ConfigStore, snapshot: ConfigSnapshot, input: AnalysisInput,
                                     finding: Finding, disposition: PolicyDisposition): VerifiedLifecycleDisposition =
        VerifiedLifecycleDisposition("synthetic-trusted-warning-proof", input.snapshot.snapshotId, snapshot.policyDigest,
            RULE, finding.providerId, store.findingSelector(snapshot, input, finding).fingerprint, disposition)

    private companion object {
        const val BARRIER_SECONDS = 10L
    }
}

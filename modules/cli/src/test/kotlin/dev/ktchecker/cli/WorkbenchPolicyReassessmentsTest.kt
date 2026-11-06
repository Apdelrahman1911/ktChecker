package dev.ktchecker.cli

import dev.ktchecker.config.*
import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.*
import dev.ktchecker.snapshot.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.*

/**
 * Actual owner registry, ConfigStore, SnapshotStore and the production reassessment adapter.
 * The last test uses the real kernel/SQLite/execution-key transformation/restart to reject an
 * unqualified actual catalog rule. Positive raw/audit-view publication remains separately covered
 * by KernelPolicyReassessmentTest with its explicitly synthetic-only test/policy-reassessment rule.
 * The provider/code image are synthetic: none of this is compiler, actual model delivery,
 * whole-project currentness, target, rule qualification or a production completion certificate.
 */
class WorkbenchPolicyReassessmentsTest {
    @Test fun exactOriginalInputExecutionEvidenceAndCoverageSurviveDispositionOnlyReassessment() {
        val f = Fixture()
        val subject = f.uncertifiedAdapterSubject()
        val current = f.accept()
        val policyBefore = f.policyBytes()
        val originalBefore = Files.readAllBytes(f.preparedPath)
        val prepared = f.prepare(subject)
        assertEquals(f.original.policyDigest, prepared.capture.originalPolicyDigest)
        assertEquals(current.policyDigest, prepared.capture.currentPolicyDigest)
        assertNotEquals(prepared.capture.originalPolicyDigest, prepared.capture.currentPolicyDigest)
        val evaluated = prepared.apply(subject.raw)
        assertEquals("accepted", evaluated.findings.single().disposition)
        assertEquals(subject.raw, evaluated.copy(findings = evaluated.findings.map { it.copy(disposition = "open") }))
        assertEquals(subject.raw.findings.single().semanticDigest, evaluated.findings.single().semanticDigest)
        assertEquals(subject.raw.findings.single().evidence, evaluated.findings.single().evidence)
        assertEquals(f.record.input, subject.input)
        assertEquals(f.original.policyDigest, subject.input.context.configurationDigest, "Never fabricate a current-policy AnalysisInput")
        assertEquals(policyBefore, f.policyBytes())
        assertContentEquals(originalBefore, Files.readAllBytes(f.preparedPath))
        assertEquals(0, f.provider.calls.get())
        assertEquals(0, f.profileCalls)
        var calls = 0
        prepared.withCurrent { check ->
            calls++
            assertTrue(check.current, check.reasons.toString())
            assertFalse(check.authoritative)
            assertEquals(f.record.input.snapshot.snapshotId, check.snapshotId)
            assertEquals(current.policyDigest, check.configurationDigest)
            assertEquals(f.record.input.context.modelDigest, check.modelDigest)
            assertTrue("owner_declared_compilation_not_full_project_model" in check.reasons)
        }
        assertEquals(1, calls)
        assertEquals("policy_reassessment_raw_changed", assertFailsWith<CliProblem> {
            prepared.apply(subject.raw.copy(rawDiagnostics = listOf(RawDiagnostic("different raw execution"))))
        }.code)
    }

    @Test fun invalidExpectationsCannotFillPrivateArchiveWithTimestampDistinctUnusedCaptures() {
        val f = Fixture()
        val subject = f.uncertifiedAdapterSubject()
        val current = f.accept()
        val policy = f.policyBytes()
        for (attempt in 1..5) {
            assertEquals("policy_reassessment_policy_changed", assertFailsWith<CliProblem> {
                f.prepare(subject, Digests.sha256("wrong-expected-policy-$attempt"))
            }.code)
            assertFalse(Files.exists(f.archiveRoot), "A refused expected digest cannot create an archive or fill its quota")
        }
        val good = f.prepare(subject, current.policyDigest)
        val captures = f.archiveBytes()
        assertEquals(2, captures.size)
        for (attempt in 6..10) {
            assertEquals("policy_reassessment_policy_changed", assertFailsWith<CliProblem> {
                f.prepare(subject, Digests.sha256("wrong-expected-policy-$attempt"))
            }.code)
            assertEquals(captures, f.archiveBytes())
        }
        assertEquals("policy_reassessment_expectation_invalid", assertFailsWith<CliProblem> {
            f.prepare(subject, "not-a-policy-digest")
        }.code)
        assertEquals(captures, f.archiveBytes())
        assertEquals(policy, f.policyBytes())
        assertEquals("accepted", good.apply(subject.raw).findings.single().disposition)
        assertEquals(0, f.provider.calls.get())
    }

    @Test fun laterPolicyDoesNotReinterpretFrozenViewAndRestorationNeverUsesCurrentCapture() {
        val f = Fixture()
        val subject = f.uncertifiedAdapterSubject()
        f.accept()
        val accepted = f.prepare(subject)
        val removed = f.mutate(ConfigAction.RemoveSuppression("owner-acceptance"))
        assertEquals("accepted", accepted.apply(subject.raw).findings.single().disposition)
        accepted.withCurrent { check ->
            assertFalse(check.current)
            assertEquals(removed.policyDigest, check.configurationDigest)
            assertTrue("effective_policy_changed" in check.reasons)
        }
        val before = f.archiveBytes()
        val restored = f.newAuthority().restore(f.binding, subject, accepted.capture)
        assertEquals(accepted.capture, restored.capture)
        assertEquals("accepted", restored.apply(subject.raw).findings.single().disposition)
        restored.withCurrent { assertFalse(it.current) }
        assertEquals(before, f.archiveBytes(), "Restoration neither captures current policy nor repairs history")
        val reopened = f.prepare(subject)
        assertEquals("open", reopened.apply(subject.raw).findings.single().disposition)
        assertEquals(accepted.capture.analysisAuthorityDigest, reopened.capture.analysisAuthorityDigest)
        assertNotEquals(accepted.capture.currentCaptureId, reopened.capture.currentCaptureId)
        assertEquals(subject.raw, subject.originalEffective)
    }

    @Test fun anotherAgentsIntegratedSourceChangeMakesOldViewStaleNotFixedOrCurrent() {
        val f = Fixture()
        val subject = f.uncertifiedAdapterSubject()
        f.accept()
        val prepared = f.prepare(subject)
        val next = f.snapshots.sealOverlay(OverlayRequest(f.identity, "peer-agent", "peer-session", "peer-patch",
            f.commit.snapshotId, f.commit.headRevision,
            listOf(SnapshotChange.PutFile(SnapshotPath(path = "Peer.kt"), ImmutableBlob.utf8("val peer = 7\n")))))
        prepared.withCurrent { check ->
            assertFalse(check.current)
            assertEquals(next.snapshotId, check.snapshotId)
            assertTrue("stale_snapshot" in check.reasons)
        }
        assertEquals("accepted", prepared.apply(subject.raw).findings.single().disposition)
        assertEquals("open", subject.raw.findings.single().disposition)
        assertEquals(f.commit.snapshotId, subject.input.snapshot.snapshotId)
        assertEquals(0, f.provider.calls.get())
    }

    @Test fun missingExactArchiveIsUnavailableAndNeverReconstructedFromLiveConfig() {
        val f = Fixture()
        val subject = f.uncertifiedAdapterSubject()
        f.accept()
        val prepared = f.prepare(subject)
        val held = f.state.root.resolve("held-current-capture.json")
        Files.move(f.archivePath(prepared.capture.currentCaptureId), held)
        val remaining = f.archiveBytes()
        assertEquals("policy_archive_missing", assertFailsWith<CliProblem> {
            f.newAuthority().restore(f.binding, subject, prepared.capture)
        }.code)
        assertEquals(remaining, f.archiveBytes())
        assertFalse(Files.exists(f.archivePath(prepared.capture.currentCaptureId)))
        prepared.withCurrent { assertFalse(it.current) }
        assertEquals("accepted", prepared.apply(subject.raw).findings.single().disposition,
            "An in-memory historical classification is not a currentness or completion grant")
    }

    @Test fun swappedPairReferencesOrChangedSubjectCannotAcquireAnOriginalAnalysisRelation() {
        val f = Fixture()
        val subject = f.uncertifiedAdapterSubject()
        f.accept()
        val prepared = f.prepare(subject)
        val before = f.archiveBytes()
        val alternatives = listOf(
            prepared.capture.copy(originalCaptureId = prepared.capture.currentCaptureId),
            prepared.capture.copy(analysisAuthorityDigest = Digests.sha256("another-analysis")),
            prepared.capture.copy(currentPolicyDigest = prepared.capture.originalPolicyDigest),
        )
        for (capture in alternatives) {
            assertEquals("policy_reassessment_archive_mismatch", assertFailsWith<CliProblem> {
                f.newAuthority().restore(f.binding, subject, capture)
            }.code)
        }
        val anotherRaw = subject.copy(raw = subject.raw.copy(metrics = AnalysisMetrics(elapsedMillis = 37)))
        assertEquals("policy_reassessment_archive_mismatch", assertFailsWith<CliProblem> {
            f.authority.restore(f.binding, anotherRaw, prepared.capture)
        }.code)
        assertEquals(before, f.archiveBytes())
    }

    @Test fun differentConsumerConsentOrMissingOriginalProvenanceDoesNotBackfillHistory() {
        val f = Fixture()
        val subject = f.uncertifiedAdapterSubject()
        f.accept()
        val bindings = listOf(f.binding.copy(clientId = "peer"), f.binding.copy(sessionId = "peer-session"),
            f.binding.copy(lineageId = "peer-lineage"), f.binding.copy(generation = 2),
            f.binding.copy(workspaceId = "peer-project"), f.binding.copy(worktreeId = "peer-tree"),
            f.binding.copy(ownerId = "peer-owner"))
        for (binding in bindings) {
            assertEquals("policy_reassessment_original_mismatch", assertFailsWith<CliProblem> {
                f.authority.prepare(binding, subject, f.configs.capture(f.configBinding).policyDigest)
            }.code)
        }
        assertFalse(Files.exists(f.archiveRoot))
        f.state.write(f.preparedPath, f.record.copy(executionAuthorized = true))
        assertEquals("policy_admission_preparation_changed", assertFailsWith<CliProblem> {
            f.prepare(subject)
        }.code)
        assertFalse(Files.exists(f.archiveRoot))
        f.state.write(f.preparedPath, f.record)
        Files.move(f.admissionPath, f.state.root.resolve("held-admission.json"))
        assertEquals("policy_admission_unavailable", assertFailsWith<CliProblem> {
            f.prepare(subject)
        }.code)
        assertFalse(Files.exists(f.admissionPath))
        assertFalse(Files.exists(f.archiveRoot))
    }

    @Test fun ruleSettingChangesAreRejectedRatherThanHidingRequiredProviderWork() {
        for (enabled in listOf(true, false)) {
            val f = Fixture()
            val subject = f.uncertifiedAdapterSubject()
            f.mutate(ConfigAction.SetRuleEnabled(RULE, enabled))
            val policy = f.policyBytes()
            assertFailsWith<ConfigValidationException> { f.prepare(subject) }
            assertEquals(policy, f.policyBytes())
            assertFalse(Files.exists(f.archiveRoot))
            assertEquals(0, f.provider.calls.get())
        }
    }

    @Test fun expiryBoundaryAndCodeDriftInvalidateCurrentnessWithoutRerunningTheOriginal() {
        val clock = MutableClock(System.currentTimeMillis())
        val f = Fixture(clock)
        val subject = f.uncertifiedAdapterSubject()
        f.accept(expires = clock.millis() + 1_000)
        val prepared = f.prepare(subject)
        prepared.withCurrent { assertTrue(it.current) }
        clock.now += 1_000
        prepared.withCurrent { assertFalse(it.current) }
        assertEquals("accepted", prepared.apply(subject.raw).findings.single().disposition)
        f.codeAvailable = false
        prepared.withCurrent { check ->
            assertFalse(check.current)
            assertEquals(listOf("policy_reassessment_retained_authority_unavailable"), check.reasons)
        }
        assertEquals(0, f.provider.calls.get())
    }

    @Test fun callbackFailureIsPropagatedExactlyOnceForPositiveAndNegativeFences() {
        val f = Fixture()
        val subject = f.uncertifiedAdapterSubject()
        f.accept()
        val prepared = f.prepare(subject)
        for (available in listOf(true, false)) {
            f.codeAvailable = available
            var entered = 0
            val sentinel = IllegalStateException("synthetic nonreplayable action")
            assertSame(sentinel, assertFailsWith<IllegalStateException> {
                prepared.withCurrent { check ->
                    entered++
                    assertEquals(available, check.current)
                    throw sentinel
                }
            })
            assertEquals(1, entered, "The failed action must never be retried as a negative callback")
        }
    }

    @Test fun currentConfigReadFailureHasOneNegativeCallbackAndNeverRetriesItsException() {
        val f = Fixture()
        val subject = f.uncertifiedAdapterSubject()
        f.accept()
        val prepared = f.prepare(subject)
        val path = f.configs.storagePaths(f.configBinding).shared
        val good = Files.readAllBytes(path)
        Files.writeString(path, "{") // Private fixture corruption, not an authorized policy update.
        var calls = 0
        val sentinel = IllegalStateException("synthetic failed negative-fence action")
        assertSame(sentinel, assertFailsWith<IllegalStateException> {
            prepared.withCurrent { check ->
                calls++
                assertFalse(check.current)
                assertEquals(listOf("policy_reassessment_current_fence_unavailable"), check.reasons)
                throw sentinel
            }
        })
        assertEquals(1, calls)
        Files.write(path, good)
        prepared.withCurrent { assertTrue(it.current, "Exact fixture restoration must recover the current fence") }
        assertEquals(0, f.provider.calls.get())
    }

    @Test fun actualKernelRetainsUnqualifiedRawAndRefusesReassessmentAcrossRestartWithoutRewrite() {
        val f = Fixture()
        val root = f.state.namespace("policy-view-test-broker")
        val limits = KernelConfig(workerCount = 1, globalParallelism = 1, perWorktreeParallelism = 1,
            heartbeatMillis = 30, leaseMillis = 120, pollMillis = 5)
        val policy = ResultPolicy.versioned("ktchecker/synthetic-owner-captured-policy/1") { binding, input, raw ->
            assertEquals(f.binding, binding)
            assertEquals(f.record.input, input)
            f.configs.apply(f.original, input, raw)
        }
        lateinit var client: ClientRegistration
        lateinit var original: ReviewStatus
        lateinit var firstEvents: List<ReviewEvent>
        lateinit var request: PolicyReassessmentRequest
        lateinit var raw: ProviderResult
        lateinit var originalEffective: ProviderResult
        val originalRecordBytes = Files.readAllBytes(f.preparedPath)

        fun assertRefused(kernel: KernelService, attempted: PolicyReassessmentRequest) {
            val eventsBefore = kernel.events(client.client)
            val archivesBefore = f.archiveBytes()
            val policyBefore = f.policyBytes()
            assertEquals("policy_reassessment_original_incomplete", assertFailsWith<KernelException> {
                kernel.reassessPolicy(client.client, attempted)
            }.code)
            assertEquals("policy_reassessment_not_found", assertFailsWith<KernelException> {
                kernel.policyReassessmentStatus(client.client, attempted.idempotencyKey)
            }.code)
            assertEquals(eventsBefore, kernel.events(client.client), "Refusal cannot publish an audit view or delivery obligation")
            assertEquals(archivesBefore, f.archiveBytes(), "Refusal precedes owner-capture allocation")
            assertEquals(policyBefore, f.policyBytes())
            assertEquals(original, kernel.status(client.client, original.reviewId))
        }

        KernelService.open(root, f.provider, limits, resultPolicy = policy,
            policyReassessmentAuthority = f.authority).use { kernel ->
            client = kernel.registerClient(f.binding)
            original = awaitResult(kernel, client, kernel.submit(client.client,
                SubmitRequest(f.record.patchId, f.record.idempotencyKey, f.record.input)).reviewId)
            // The actual catalog rule is not qualified; a completed provider call is not complete coverage.
            assertEquals("incomplete", original.state, original.reasons.toString())
            assertEquals(listOf("coverage_unqualified:$RULE"), original.reasons)
            raw = assertNotNull(f.provider.lastRaw)
            assertTrue(raw.coverage.single().implemented)
            assertFalse(raw.coverage.single().tested)
            assertFalse(raw.coverage.single().qualified)
            assertEquals("complete", raw.status)
            assertTrue(raw.completeFor(f.record.input), "Structural provider completeness is not qualified kernel coverage")
            originalEffective = f.configs.apply(f.original, f.record.input, raw)
            assertEquals(raw, originalEffective, "The original ConfigStore policy preserves every raw field")
            assertEquals(originalEffective, original.result)
            assertEquals("open", originalEffective.findings.single().disposition)
            assertNotEquals(f.record.input.analysisKey(), assertNotNull(f.provider.lastInput).analysisKey(),
                "The real kernel must have created an isolated effective execution input")
            assertTrue(raw.findings.single().evidence.single().summary.contains(assertNotNull(f.provider.lastInput).analysisKey()))
            firstEvents = kernel.events(client.client).events
            val accepted = f.accept()
            val rawFinding = raw.findings.single()
            assertNotEquals(f.record.input.context.configurationDigest, accepted.policyDigest)
            val ordinary = f.configs.explain(accepted, f.record.input, rawFinding)
            assertEquals(PolicyDisposition.OPEN, ordinary.disposition)
            assertEquals("Identity/registry mismatch: no policy matching was authorized for this finding.", ordinary.explanation)
            assertNull(ordinary.fingerprint, "Ordinary apply rejects the policy/input mismatch before anchor matching")
            assertTrue(ordinary.candidates.isEmpty())
            assertEquals(raw, f.configs.apply(accepted, f.record.input, raw))
            // This pure configuration pair is not a kernel certificate or a published audit view.
            // Keep the original input and actual isolated raw; never regenerate or retag either.
            val acceptedSubject = f.configs.prepareReassessment(f.record.input,
                f.configs.exportSnapshot(f.original), f.configs.exportSnapshot(accepted))
            assertEquals(f.record.input, acceptedSubject.originalInput)
            val acceptedExplanation = f.configs.explainReassessment(acceptedSubject, rawFinding)
            assertEquals(PolicyDisposition.ACCEPTED, acceptedExplanation.disposition)
            assertEquals("owner-acceptance", acceptedExplanation.policyEntryId)
            assertEquals(rawFinding, acceptedExplanation.finding)
            val originalFingerprint = assertNotNull(f.configs.explain(f.original, f.record.input, rawFinding).fingerprint)
            assertEquals(originalFingerprint, acceptedExplanation.fingerprint, "The exact raw anchor survives the policy-only pair")
            val localClassification = f.configs.applyReassessment(acceptedSubject, raw)
            assertEquals("accepted", localClassification.findings.single().disposition)
            assertEquals(raw, localClassification.copy(findings = localClassification.findings.map { it.copy(disposition = "open") }))
            assertTrue(localClassification.completeFor(f.record.input))
            assertFalse(localClassification.coverage.single().qualified, "Disposition-only classification cannot qualify coverage")
            request = PolicyReassessmentRequest(original.reviewId, "policy-request", client.binding.generation, accepted.policyDigest)
            assertRefused(kernel, request)
            assertRefused(kernel, request) // A refused exact retry does not become a stored positive view.
            assertEquals(firstEvents, kernel.events(client.client).events)
            assertTrue(firstEvents.none { it.kind == "policy_reassessed" })
            var finalActions = 0
            val cut = kernel.prepareGate(client.client)
            assertFalse(cut.ready, "This unqualified, delivery-pending fixture is not isolated completion qualification")
            assertFalse(kernel.completeGate(client.client, cut.cutId) { finalActions++ }.allowed)
            assertEquals(0, finalActions)
            assertEquals(1, f.provider.calls.get())
        }
        KernelService.open(root, f.provider, limits.copy(workerCount = 0), resultPolicy = policy,
            policyReassessmentAuthority = f.newAuthority()).use { kernel ->
            kernel.registerClient(client)
            assertEquals(original, kernel.status(client.client, original.reviewId))
            assertEquals(originalEffective, kernel.status(client.client, original.reviewId).result)
            val before = kernel.events(client.client)
            assertEquals(firstEvents, before.events)
            assertRefused(kernel, request)
            val removed = f.mutate(ConfigAction.RemoveSuppression("owner-acceptance"))
            val removedSubject = f.configs.prepareReassessment(f.record.input,
                f.configs.exportSnapshot(f.original), f.configs.exportSnapshot(removed))
            assertEquals(f.record.input, removedSubject.originalInput)
            val reopened = f.configs.applyReassessment(removedSubject, raw)
            assertEquals("open", reopened.findings.single().disposition)
            assertEquals(raw, reopened, "Policy removal still preserves the exact unqualified raw result")
            assertTrue(reopened.completeFor(f.record.input))
            assertFalse(reopened.coverage.single().qualified)
            assertRefused(kernel, PolicyReassessmentRequest(original.reviewId, "policy-remove", client.binding.generation, removed.policyDigest))
            assertEquals(before, kernel.events(client.client))
            assertEquals(original, kernel.status(client.client, original.reviewId))
            assertEquals(raw, f.provider.lastRaw)
            assertEquals(1, f.provider.calls.get(), "Policy-only restart and refusal cannot rerun providers")
            assertEquals(0, f.profileCalls)
            assertFalse(Files.exists(f.archiveRoot), "Incomplete originals cannot acquire reassessment capture history")
            assertContentEquals(originalRecordBytes, Files.readAllBytes(f.preparedPath))
        }
    }

    private class MutableClock(var now: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(now)
        override fun millis(): Long = now
    }

    private class Fixture(private val clock: Clock = Clock.systemUTC()) {
        val state = PrivateState(Files.createTempDirectory("ktchecker-owner-policy-view-"))
        val binding = ClientBinding(state.ownerId, "project", "tree", "agent", "lineage", sessionId = "session")
        val configBinding = ConfigBinding(binding.ownerId, binding.workspaceId, binding.worktreeId, binding.lineageId)
        val identity = SnapshotIdentity(binding.ownerId, binding.workspaceId, binding.worktreeId)
        private val owner = ClientIdentity(state.ownerId, "explicit-synthetic-owner", "owner-session")
        private val aliases = RuleAliases(listOf(PolicyRule(RULE, implementationDigest = Digests.sha256("synthetic-detekt-rule-identity"))))
        private val mutationAuthority = HmacOwnerAuthority(ByteArray(32) { it.toByte() },
            listOf(OwnerSessionRegistration(owner, setOf(configBinding))))
        val configs = ConfigStore(state.namespace("configuration"), aliases, mutationAuthority, clock = clock)
        val original = configs.capture(configBinding)
        val snapshots = SnapshotStore(state.namespace("snapshots"), state.ownerId, clock = clock)
        val commit: SnapshotCommit
        val provider = SyntheticWarning()
        val catalog = Catalog()
        val code = Digests.sha256("synthetic-fixed-owner-code-image-not-real-code-qualification")
        var codeAvailable = true
        var profileCalls = 0
        private var mutations = 0
        val routes = listOf(ProviderRoute(provider, { true }, {
            profileCalls++
            error("Policy reassessment cannot create a new execution profile")
        }, Digests.sha256("synthetic-fixed-provider-route")))
        val record: PreparedReview
        val admissions = WorkbenchPolicyAdmissions(state)
        val authority: WorkbenchPolicyReassessments
        val preparedPath: Path get() = state.keyFile("prepared", record.input.analysisKey())
        val admissionPath: Path get() = state.keyFile("policy-admissions", record.input.analysisKey())
        val archiveRoot: Path get() = state.root.resolve("policy-captures")

        init {
            snapshots.registerWorktree(identity)
            commit = snapshots.sealOverlay(OverlayRequest(identity, binding.clientId, binding.sessionId, "patch", null, 0,
                listOf(SnapshotChange.PutFile(SnapshotPath(path = "Main.kt"), ImmutableBlob.utf8("fun answer() = 42\n")))))
            val materialized = snapshots.materialize(identity, commit.snapshotId, state.root.resolve("original-source"))
            val profiles = emptyMap<String, TrustedExecution>()
            val activation = activationPlan(catalog, listOf(RULE), true, emptyMap(), original.policyDigest)
            val input = AnalysisInput(materialized.manifest.toContractManifest(), materialized.root.toString(),
                AnalysisContext(modelDigest = Digests.sha256("synthetic-model"), dependencyDigest = Digests.sha256("synthetic-dependencies"),
                    toolchainDigest = Digests.sha256("synthetic-toolchain"), configurationDigest = original.policyDigest,
                    compilationFiles = listOf("Main.kt"), fullProject = false, policies = mapOf(
                        "ktchecker.model" to "owner-declared-single-compilation", "ktchecker.coverage" to "partial-project",
                        ACTIVATION_POLICY_KEY to ContractJson.encodeToString(activation),
                        "ktchecker.execution-plan" to Digests.sha256(Digests.canonical(ContractJson.encodeToJsonElement(profiles))))),
                requiredRuleIds = listOf(RULE), changedPaths = listOf("Main.kt"))
            record = PreparedReview(binding, "patch", "review-request", input, configs.exportSnapshot(original), profiles,
                false, "explicit-rule-subset-not-full-release")
            admissions.publishNew(record, describePolicyAdmission(record, original, true, code, catalog, routes))
            authority = newAuthority()
        }

        fun newAuthority(): WorkbenchPolicyReassessments = WorkbenchPolicyReassessments(state, configs, snapshots, code,
            { if (!codeAvailable) throw CliProblem("synthetic_code_changed", "Synthetic owner code guard changed") },
            { input -> assertEquals(record.input, input); record },
            { requested ->
                val kept = admissions.requireRetained(requested)
                val expected = describePolicyAdmission(requested, original, true, code, catalog, routes)
                if (kept != expected) throw CliProblem("policy_admission_authority_changed", "Synthetic retained route changed")
                kept
            }, provider.id, provider.version)

        /** This direct unit DTO is not a kernel certificate; only the integration test resolves one. */
        fun uncertifiedAdapterSubject(): CertifiedPolicyReview {
            val raw = provider.result(record.input)
            return CertifiedPolicyReview("synthetic-review", record.patchId, 1, record.input,
                Digests.sha256("synthetic-work"), Digests.sha256("synthetic-provider-key"), record.input.analysisKey(),
                Digests.sha256("synthetic-effective-key"), Digests.sha256("synthetic-work-certificate"),
                "synthetic-original-policy-certificate-not-authority", raw, configs.apply(original, record.input, raw))
        }

        fun prepare(subject: CertifiedPolicyReview, expectedPolicyDigest: String = configs.capture(configBinding).policyDigest): PreparedPolicyReassessment =
            authority.prepare(binding, subject, expectedPolicyDigest)

        fun accept(expires: Long? = null): ConfigSnapshot = mutate(ConfigAction.AddSuppression(SuppressionSpec("owner-acceptance",
            PolicySelector.Rule(RULE), SuppressionScope.Project, SuppressionReason.ACCEPTED_RISK,
            "private-owner-justification-marker: explicit synthetic owner decision", expires)))

        fun mutate(action: ConfigAction): ConfigSnapshot {
            val current = configs.capture(configBinding)
            val request = ConfigMutationRequest(configBinding, ConfigLayer.SHARED, current.revisionToken, action, "owner-${++mutations}")
            return configs.mutate(request, mutationAuthority.issueForExplicitOwnerAction(owner, request, clock.instant()))
        }

        fun policyBytes(): Map<String, String> {
            val paths = configs.storagePaths(configBinding)
            return listOf(paths.shared, paths.local).filter { Files.exists(it) }.associate { it.toString() to Digests.sha256(Files.readAllBytes(it)) }
        }
        fun archivePath(id: String): Path = archiveRoot.resolve(id.removePrefix("sha256:") + ".json")
        fun archiveBytes(): Map<String, String> = if (!Files.exists(archiveRoot)) emptyMap() else Files.list(archiveRoot).use { entries ->
            entries.toList().associate { it.fileName.toString() to Digests.sha256(Files.readAllBytes(it)) }
        }
    }

    private class SyntheticWarning : AnalysisProvider {
        override val id = "synthetic-owner-policy-provider"
        override val version = "synthetic-owner-policy-provider/1"
        override val supportedRuleIds = setOf(RULE)
        val calls = AtomicInteger()
        @Volatile var lastInput: AnalysisInput? = null
        @Volatile var lastRaw: ProviderResult? = null
        override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
            calls.incrementAndGet()
            cancellation.throwIfCancelled()
            lastInput = input
            return result(input).also { lastRaw = it }
        }
        fun result(input: AnalysisInput): ProviderResult {
            val key = input.analysisKey()
            val finding = Finding(RULE, "Synthetic exact-key warning", "A synthetic preserved occurrence", "Reassessment integration fixture only",
                SourceLocation("Main.kt", input.snapshot.files.single().digest), Digests.sha256("synthetic-fingerprint:$key"), id,
                context = input.context.scope, severity = "warning", evidence = listOf(Evidence("synthetic-key", "Original execution key $key")),
                semanticDigest = Digests.sha256("synthetic-semantic:$key:${input.outputRoot}"))
            return ProviderResult(id, "complete", listOf(finding), listOf(Coverage(RULE, input.context.scope, "findings",
                provenance = "executed", certificateId = Digests.sha256("synthetic-coverage:$key"), implemented = true,
                tested = false, qualified = false)), providerVersion = version)
        }
    }

    private fun awaitResult(kernel: KernelService, client: ClientRegistration, reviewId: String): ReviewStatus {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        var result = kernel.status(client.client, reviewId)
        while (result.state in setOf("queued", "running") && System.nanoTime() < deadline) {
            Thread.sleep(5)
            result = kernel.status(client.client, reviewId)
        }
        assertTrue(result.state !in setOf("queued", "running"), "Synthetic worker failed to reach a terminal result")
        return result
    }

    companion object { private const val RULE = "ktc.kotlin.detekt.style.magic-number" }
}

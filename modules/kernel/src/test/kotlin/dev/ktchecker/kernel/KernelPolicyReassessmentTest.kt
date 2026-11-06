package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/** Real kernel/ledger/archive APIs, SYNTHETIC provider and owner fences. No real compiler/host proof. */
@Timeout(30)
class KernelPolicyReassessmentTest {
    @TempDir lateinit var temp: Path
    private val config = KernelConfig(workerCount = 1, globalParallelism = 1, perWorktreeParallelism = 1,
        heartbeatMillis = 50, leaseMillis = 200, pollMillis = 5, analysisTimeoutMillis = 15_000)

    @Test fun exactRawDispositionsPublishOwnRequiredEventAndNeverRewriteOrExecute() {
        for (disposition in listOf("accepted", "suppressed", "baselined")) {
            fixture("positive-$disposition").use { f ->
                val before = retained(f.service)
                val oldEvents = observeAll(f.service, f.client)
                val oldReceipts = receipts(f.service)
                val oldCut = f.service.prepareGate(f.client.client)
                assertFalse(oldCut.ready)
                assertTrue(oldCut.reasons.any { it.startsWith("open_finding:") })
                f.authority.current = B
                f.authority.disposition = disposition
                val request = request(f, "new-policy")
                val view = f.service.reassessPolicy(f.client.client, request)
                val original = assertNotNull(f.authority.original)
                assertEquals(f.input, original.input)
                assertEquals(f.status.reviewId, original.reviewId)
                assertEquals(f.input.analysisKey(), view.certified.inputKey)
                assertEquals(original.workCertificateId, view.certified.workCertificateId)
                assertEquals(original.effectiveInputKey, view.certified.effectiveInputKey)
                assertEquals(original.effectiveInputKey, original.raw.findings.first().semanticDigest)
                assertEquals(original.effectiveInputKey, original.raw.findings.first().evidence.first().digest)
                assertEquals(original.effectiveInputKey, original.raw.findings.first().verification.single().command.last())
                assertEquals(Digests.sha256(canonical(original.raw)), view.certified.rawResultDigest)
                assertEquals(original.raw, view.result.copy(findings = view.result.findings.map { it.copy(disposition = "open") }))
                assertTrue(view.result.findings.all { it.disposition == disposition })
                assertFalse(view.auditOnly)
                assertEquals(emptyList(), view.reasons)
                assertEquals("policy_reassessed", view.state)
                assertEquals(before, retained(f.service))
                assertEquals(f.status, f.service.status(f.client.client, f.status.reviewId))
                assertEquals(oldEvents, events(f.service, f.client).take(oldEvents.size))
                assertEquals(oldReceipts, receipts(f.service))
                assertEquals(1L, f.service.ledger.read { it.count("SELECT COUNT(*) FROM work") })
                assertEquals(1, f.provider.calls.get())
                assertEquals(1, f.observerCalls.get())
                val event = events(f.service, f.client).last()
                assertEquals(oldEvents.size + 1, events(f.service, f.client).size)
                assertEquals(view.viewId, event.eventId)
                assertEquals(view.sequence, event.sequence)
                assertEquals(PolicyReassessments.EVENT_KIND, event.kind)
                assertTrue(event.deliveryRequired)
                assertEquals(canonical(view), event.payload)
                assertEquals(Digests.sha256(event.payload), event.payloadDigest)
                assertTrue(f.service.prepareGate(f.client.client).reasons.none { it.startsWith("current_coverage_missing:") })
                var cut = f.service.prepareGate(f.client.client)
                assertFalse(cut.ready)
                assertTrue("insertion_missing:${event.eventId}" in cut.reasons)
                acknowledge(f.service, f.client, event)
                cut = f.service.prepareGate(f.client.client)
                assertFalse(cut.ready)
                assertTrue("model_observation_missing:${event.eventId}" in cut.reasons)
                observe(f.service, f.client, event)
                cut = f.service.prepareGate(f.client.client)
                assertTrue(cut.ready, cut.reasons.toString())
                val stale = f.service.completeGate(f.client.client, oldCut.cutId)
                assertFalse(stale.allowed)
                assertTrue("lineage_epoch_changed" in stale.reasons)
                val actions = AtomicInteger()
                assertTrue(f.service.completeGate(f.client.client, cut.cutId) { actions.incrementAndGet() }.actionPerformed)
                assertFalse(f.service.completeGate(f.client.client, cut.cutId) { actions.incrementAndGet() }.allowed)
                assertEquals(1, actions.get())
            }
        }
    }

    @Test fun removalReopensLatestViewWithoutLaunderingAnOlderAcceptanceOrDeletingEvents() {
        fixture("reopen").use { f ->
            val original = retained(f.service)
            f.authority.current = B
            val accepted = f.service.reassessPolicy(f.client.client, request(f, "B"))
            observeAll(f.service, f.client)
            val ready = f.service.prepareGate(f.client.client)
            assertTrue(ready.ready, ready.reasons.toString())
            val history = events(f.service, f.client)
            f.authority.current = C
            f.authority.disposition = "open"
            val reopened = f.service.reassessPolicy(f.client.client, request(f, "C", C))
            assertEquals(accepted.certified, reopened.certified)
            assertTrue(reopened.result.findings.all { it.disposition == "open" })
            assertEquals(history, events(f.service, f.client).take(history.size))
            observeAll(f.service, f.client)
            val cut = f.service.prepareGate(f.client.client)
            assertFalse(cut.ready)
            assertTrue(cut.reasons.any { it.startsWith("open_finding:") })
            assertFalse(f.service.completeGate(f.client.client, ready.cutId).allowed)
            assertEquals(original, retained(f.service))
            f.authority.current = A
            val returned = f.service.reassessPolicy(f.client.client, request(f, "return-A", A))
            assertEquals(A, returned.capture.currentPolicyDigest)
            assertEquals(accepted.certified, returned.certified)
            observeAll(f.service, f.client)
            assertFalse(f.service.prepareGate(f.client.client).ready)
            assertEquals(1, f.provider.calls.get())
        }
    }

    @Test fun partialOrNonauthoritativeViewStaysAuditOnlyAndStillAllowsLaterExplicitReassessment() {
        for (partial in listOf(true, false)) fixture("audit-$partial", fullProject = !partial).use { f ->
            f.authority.authoritative = partial // first: partial but authoritative; second: full but nonauthoritative
            f.authority.current = B
            val view = f.service.reassessPolicy(f.client.client, request(f, "B"))
            assertTrue(view.auditOnly)
            assertTrue(if (partial) "full_project_scope_unproven" in view.reasons else "current_source_configuration_authority_unavailable" in view.reasons)
            observeAll(f.service, f.client)
            var cut = f.service.prepareGate(f.client.client)
            assertFalse(cut.ready)
            assertTrue("policy_reassessment_audit_only:${view.viewId}" in cut.reasons)
            f.authority.current = C
            f.authority.disposition = "open"
            val next = f.service.reassessPolicy(f.client.client, request(f, "C", C))
            assertTrue(next.auditOnly)
            assertEquals(view.certified, next.certified)
            observeAll(f.service, f.client)
            cut = f.service.prepareGate(f.client.client)
            assertFalse(cut.ready)
            assertEquals(1, f.provider.calls.get())
        }
    }

    @Test fun exactReplayIsReadOnlyButKeyPairGenerationAndClientSubstitutionFail() {
        fixture("identity").use { f ->
            f.authority.current = B
            val request = request(f, "same")
            val view = f.service.reassessPolicy(f.client.client, request)
            val history = events(f.service, f.client)
            val frontier = frontier(f)
            val calls = f.authority.applies.get()
            assertEquals(view, f.service.reassessPolicy(f.client.client, request))
            assertEquals(view, f.service.policyReassessmentStatus(f.client.client, request.idempotencyKey))
            assertEquals(calls, f.authority.applies.get())
            reject("policy_reassessment_idempotency_conflict") { f.service.reassessPolicy(f.client.client, request.copy(expectedPolicyDigest = C)) }
            reject("policy_reassessment_generation_mismatch") { f.service.reassessPolicy(f.client.client, request.copy(generation = 2)) }
            val foreign = f.service.registerClient(binding("foreign"))
            reject("policy_reassessment_review_not_found") { f.service.reassessPolicy(foreign.client, request) }
            reject("policy_reassessment_not_found") { f.service.policyReassessmentStatus(foreign.client, request.idempotencyKey) }
            assertEquals(history, events(f.service, f.client))
            assertEquals(frontier, frontier(f))
            // A distinct consumer may reuse raw computation, never A's policy event or receipt.
            val other = await(f.service, foreign, f.service.submit(foreign.client, SubmitRequest("foreign-patch", "foreign-key", f.input)).reviewId)
            val own = f.service.reassessPolicy(foreign.client, request.copy(reviewId = other.reviewId))
            assertNotEquals(view.viewId, own.viewId)
            assertNotEquals(view.certified.subjectDigest, own.certified.subjectDigest)
            assertEquals(view.certified.workKey, own.certified.workKey)
            observeAll(f.service, f.client)
            assertFalse(f.service.prepareGate(foreign.client).ready)
            assertTrue(f.service.prepareGate(foreign.client).reasons.any { it.startsWith("insertion_missing:") })
            assertEquals(1, f.provider.calls.get())
        }
    }

    @Test fun unchangedOrMismatchedExpectationsDoNotAllocateOwnerCaptures() {
        fixture("expectation").use { f ->
            val history = events(f.service, f.client)
            val before = frontier(f)
            reject("invalid_policy_digest") { f.service.reassessPolicy(f.client.client, request(f, "invalid", "not-a-digest")) }
            repeat(3) { n ->
                reject("policy_reassessment_policy_unchanged") { f.service.reassessPolicy(f.client.client, request(f, "same-A-$n", A)) }
            }
            assertEquals(0, f.authority.prepares.get())
            assertTrue(f.authority.archive.isEmpty())
            f.authority.current = B
            repeat(3) { n ->
                reject("policy_reassessment_capture_mismatch") { f.service.reassessPolicy(f.client.client, request(f, "wrong-$n", C)) }
            }
            assertEquals(3, f.authority.prepares.get())
            assertEquals(0, f.authority.applies.get())
            assertTrue(f.authority.archive.isEmpty())
            assertEquals(history, events(f.service, f.client))
            assertEquals(before, frontier(f))
            f.service.reassessPolicy(f.client.client, request(f, "B"))
            val accepted = events(f.service, f.client)
            val acceptedFrontier = frontier(f)
            val captures = f.authority.archive.toMap()
            val prepares = f.authority.prepares.get()
            f.authority.current = C
            repeat(3) { n ->
                // Repetition of the last accepted policy is rejected before the owner even captures C.
                reject("policy_reassessment_policy_unchanged") { f.service.reassessPolicy(f.client.client, request(f, "same-B-$n", B)) }
            }
            assertEquals(prepares, f.authority.prepares.get())
            assertEquals(captures, f.authority.archive.toMap())
            assertEquals(accepted, events(f.service, f.client))
            assertEquals(acceptedFrontier, frontier(f))
            assertEquals(1, f.provider.calls.get())
        }
    }

    @Test fun fullResultAndOccurrenceGuardRejectsNonDispositionOrUnsupportedLabelChanges() {
        val faults: List<Pair<String, (ProviderResult) -> ProviderResult>> = listOf(
            "disabled" to { r -> r.copy(findings = r.findings.map { it.copy(disposition = "disabled") }) },
            "fixed" to { r -> r.copy(findings = r.findings.map { it.copy(disposition = "fixed") }) },
            "unavailable" to { r -> r.copy(findings = r.findings.map { it.copy(disposition = "unavailable") }) },
            "title" to { r -> r.copy(findings = r.findings.map { it.copy(title = "changed") }) },
            "evidence" to { r -> r.copy(findings = r.findings.map { it.copy(evidence = emptyList()) }) },
            "semantic" to { r -> r.copy(findings = r.findings.map { it.copy(semanticDigest = Digests.sha256("other")) }) },
            "verification" to { r -> r.copy(findings = r.findings.map { it.copy(verification = emptyList()) }) },
            "location" to { r -> r.copy(findings = r.findings.map { it.copy(location = it.location.copy(line = 2)) }) },
            "order" to { r -> r.copy(findings = r.findings.reversed()) },
            "missing" to { r -> r.copy(findings = r.findings.drop(1)) },
            "duplicate" to { r -> r.copy(findings = listOf(r.findings[0], r.findings[0])) },
            "coverage" to { r -> r.copy(coverage = emptyList()) },
            "metrics" to { r -> r.copy(metrics = r.metrics.copy(elapsedMillis = r.metrics.elapsedMillis + 1)) },
            "diagnostics" to { r -> r.copy(rawDiagnostics = listOf(RawDiagnostic("new"))) },
            "reasons" to { r -> r.copy(reasons = listOf("changed")) },
        )
        for ((name, transform) in faults) fixture("fault-$name").use { f ->
            val before = retained(f.service)
            val history = events(f.service, f.client)
            val frontier = frontier(f)
            f.authority.current = B
            f.authority.transform = transform
            reject("policy_reassessment_changed_protected_evidence") { f.service.reassessPolicy(f.client.client, request(f, name)) }
            assertEquals(before, retained(f.service))
            assertEquals(history, events(f.service, f.client))
            assertEquals(frontier, frontier(f))
            assertEquals(1, f.provider.calls.get())
        }
    }

    @Test fun intrinsicFailureCannotBeReclassifiedEvenWithWarningSeverity() {
        fixture("failure", intrinsicFailure = true).use { f ->
            f.authority.current = B
            reject("policy_reassessment_changed_protected_evidence") { f.service.reassessPolicy(f.client.client, request(f, "failure")) }
            assertTrue(events(f.service, f.client).none { it.kind == PolicyReassessments.EVENT_KIND })
            observeAll(f.service, f.client)
            assertFalse(f.service.prepareGate(f.client.client).ready)
            assertEquals("open", f.service.status(f.client.client, f.status.reviewId).result!!.findings.first().disposition)
        }
    }

    @Test fun currentnessFailuresRejectBeforeAcceptanceAndCannotLeaveFrontierFragments() {
        val faults: List<Pair<String, (SnapshotCheck) -> SnapshotCheck>> = listOf(
            "current" to { it.copy(current = false) }, "snapshot" to { it.copy(snapshotId = Digests.sha256("other")) },
            "policy" to { it.copy(configurationDigest = C) }, "model" to { it.copy(modelDigest = Digests.sha256("other")) },
            "dependency" to { it.copy(dependencyDigest = Digests.sha256("other")) }, "toolchain" to { it.copy(toolchainDigest = Digests.sha256("other")) },
            "source-epoch" to { it.copy(sourceEpoch = -1) }, "analysis-epoch" to { it.copy(analysisEpoch = "") },
            "expiry" to { it.copy(validUntilEpochMillis = 0) },
        )
        for ((name, transform) in faults) fixture("fence-$name").use { f ->
            val history = events(f.service, f.client)
            val frontier = frontier(f)
            f.authority.current = B
            f.authority.checkTransform = transform
            reject("policy_reassessment_currentness_unavailable") { f.service.reassessPolicy(f.client.client, request(f, name)) }
            assertEquals(history, events(f.service, f.client))
            assertEquals(frontier, frontier(f))
            assertEquals(0, f.authority.applies.get())
        }
    }

    @Test fun originalCertificateCoverageCancellationAndCallbackFailuresAreNotRepaired() {
        fixture("certificate").use { f ->
            f.authority.current = B
            f.service.ledger.transaction { c -> c.execute("UPDATE work_certificates SET certificate_id=?", Digests.sha256("tampered")) }
            val frontier = frontier(f)
            reject("policy_reassessment_certificate_invalid") { f.service.reassessPolicy(f.client.client, request(f, "bad-cert")) }
            assertEquals(frontier, frontier(f))
            assertEquals(0, f.authority.prepares.get())
        }
        fixture("incomplete", completeCoverage = false).use { f ->
            f.authority.current = B
            val before = frontier(f)
            reject("policy_reassessment_original_incomplete") { f.service.reassessPolicy(f.client.client, request(f, "incomplete")) }
            assertEquals(before, frontier(f))
            assertEquals(0, f.authority.prepares.get())
            assertTrue(events(f.service, f.client).none { it.kind == PolicyReassessments.EVENT_KIND })
        }
        fixture("cancelled").use { f ->
            f.authority.current = B
            f.service.cancel(f.client.client, f.status.reviewId)
            reject("policy_reassessment_original_incomplete") { f.service.reassessPolicy(f.client.client, request(f, "cancelled")) }
            assertEquals(0, f.authority.prepares.get())
            assertTrue(events(f.service, f.client).none { it.kind == PolicyReassessments.EVENT_KIND })
        }
        fixture("prepare-failure").use { f ->
            f.authority.current = B
            f.authority.prepareHook = { throw KernelException("synthetic_capture_missing", "Synthetic archive unavailable") }
            val before = frontier(f)
            reject("synthetic_capture_missing") { f.service.reassessPolicy(f.client.client, request(f, "missing")) }
            assertEquals(before, frontier(f))
            assertEquals(0, f.authority.applies.get())
        }
        fixture("evaluation-failure").use { f ->
            f.authority.current = B
            f.authority.transform = { throw IllegalStateException("synthetic evaluator failed") }
            val before = frontier(f)
            val history = events(f.service, f.client)
            reject("policy_reassessment_evaluator_failed") { f.service.reassessPolicy(f.client.client, request(f, "evaluation")) }
            assertEquals(before, frontier(f))
            assertEquals(history, events(f.service, f.client))
            assertEquals(1, f.authority.applies.get())
            // A preaccept archive entry may remain; it is not an accepted view, work item or permission.
            assertEquals(1, f.authority.archive.size)
        }
    }

    @Test fun newAcceptedReviewDuringPreparationRejectsTheStalePublicationCAS() {
        fixture("cas").use { f ->
            f.authority.current = B
            f.authority.prepareHook = { f.service.submit(f.client.client, SubmitRequest("racing-patch", "racing-key", f.input)) }
            reject("policy_reassessment_lineage_changed") { f.service.reassessPolicy(f.client.client, request(f, "raced")) }
            assertEquals(2, f.service.ledger.read { it.count("SELECT COUNT(*) FROM reviews") }.toInt())
            assertTrue(events(f.service, f.client).none { it.kind == PolicyReassessments.EVENT_KIND })
            assertEquals(0, f.authority.applies.get())
            assertEquals(1, f.provider.calls.get())
        }
    }

    @Test fun concurrentIndependentServicesPublishAnExactRequestOnce() {
        fixture("concurrent").use { f ->
            f.authority.current = B
            val prepared = CountDownLatch(2)
            f.authority.prepareHook = { prepared.countDown(); check(prepared.await(10, TimeUnit.SECONDS)) }
            KernelService.open(f.root, f.provider, config.copy(workerCount = 0), f.authority.legacyFence(f.input),
                policyReassessmentAuthority = f.authority).use { other ->
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val request = request(f, "same-race")
                    val a = pool.submit<PolicyReassessmentView> { f.service.reassessPolicy(f.client.client, request) }
                    val b = pool.submit<PolicyReassessmentView> { other.reassessPolicy(f.client.client, request) }
                    assertEquals(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS))
                    assertEquals(1, events(f.service, f.client).count { it.kind == PolicyReassessments.EVENT_KIND })
                    assertEquals(1, f.authority.applies.get())
                    assertEquals(1, f.provider.calls.get())
                } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
            }
        }
    }

    @Test fun restartRestoresExactSavedCaptureWithoutReevaluationAndMissingOrChangedAuthorityBlocks() {
        fixture("restart").use { f ->
            f.authority.current = B
            val view = f.service.reassessPolicy(f.client.client, request(f, "restart"))
            observeAll(f.service, f.client)
            val history = events(f.service, f.client)
            f.service.close()
            KernelService.open(f.root, f.provider, config.copy(workerCount = 0), f.authority.legacyFence(f.input),
                policyReassessmentAuthority = f.authority).use { restarted ->
                assertEquals(view, restarted.policyReassessmentStatus(f.client.client, "restart"))
                assertTrue(restarted.prepareGate(f.client.client).ready)
                assertEquals(1, f.authority.applies.get())
                assertTrue(f.authority.restores.get() > 0)
                f.authority.archive.clear()
                assertTrue("policy_reassessment_restore_unavailable" in restarted.prepareGate(f.client.client).reasons)
                assertEquals(history, events(restarted, f.client))
                assertEquals(1, f.provider.calls.get())
            }
            val changed = Authority().also { it.identitySuffix = "changed"; it.current = B }
            KernelService.open(f.root, f.provider, config.copy(workerCount = 0), changed.legacyFence(f.input),
                policyReassessmentAuthority = changed).use { restarted ->
                assertFalse(restarted.prepareGate(f.client.client).ready)
                assertTrue(restarted.prepareGate(f.client.client).reasons.any { it.startsWith("policy_reassessment_record_invalid:") })
                assertEquals(history, events(restarted, f.client))
                assertEquals(0, changed.applies.get())
            }
        }
    }

    @Test fun tamperedPublishedEventCannotBecomeAValidViewOrCurrentGate() {
        fixture("tampered-event").use { f ->
            f.authority.current = B
            val view = f.service.reassessPolicy(f.client.client, request(f, "event"))
            observeAll(f.service, f.client)
            assertTrue(f.service.prepareGate(f.client.client).ready)
            f.service.ledger.transaction { c ->
                c.execute("UPDATE events SET payload_digest=? WHERE event_id=?", Digests.sha256("tampered"), view.viewId)
            }
            reject("policy_reassessment_record_invalid") { f.service.policyReassessmentStatus(f.client.client, "event") }
            val cut = f.service.prepareGate(f.client.client)
            assertFalse(cut.ready)
            assertTrue("policy_reassessment_record_invalid:${view.viewId}" in cut.reasons)
            assertEquals(1, f.authority.applies.get())
        }
    }

    @Test fun noInstalledAuthorityCannotTurnIdentifiersOrExistingRawIntoReassessmentPermission() {
        fixture("deny").use { f ->
            f.service.close()
            KernelService.open(f.root, f.provider, config.copy(workerCount = 0), f.authority.legacyFence(f.input)).use { service ->
                val history = events(service, f.client)
                reject("policy_reassessment_authority_unavailable") { service.reassessPolicy(f.client.client, request(f, "deny")) }
                assertEquals(history, events(service, f.client))
                assertEquals(1, f.provider.calls.get())
            }
        }
    }

    @Test fun laterPlanCannotInheritScalarViewAuthorityAndFurtherReassessmentExplicitlyDenies() {
        fixture("plan").use { f ->
            f.authority.current = B
            f.service.reassessPolicy(f.client.client, request(f, "scalar"))
            val planRequest = SubmitPlanRequest("plan-patch", "plan-key", "owner-plan")
            val definition = RequiredReviewPlan(f.client.binding, planRequest, Digests.sha256(canonical(f.input.snapshot)),
                Digests.sha256(canonical(emptyList<SnapshotFile>())), Digests.sha256("model-cert"), Digests.sha256("impact-cert"),
                listOf(RequiredPlanSlot("slot", f.input, assertNotNull(contextIdentity(f.input)))), listOf(f.client.binding.clientId))
            f.plan[planRequest.planRef] = definition
            f.service.submitPlan(f.client.client, planRequest)
            reject("policy_reassessment_plan_lineage_unsupported") { f.service.reassessPolicy(f.client.client, request(f, "plan", C)) }
            observeAll(f.service, f.client)
            assertTrue("policy_reassessment_plan_lineage_unsupported" in f.service.prepareGate(f.client.client).reasons)
        }
    }

    @Test fun viewQuotaAndEventByteLimitsRejectWithoutDroppingAcceptedHistory() {
        fixture("quota", limits = config.copy(maxRetainedReviews = 1)).use { f ->
            f.authority.current = B
            f.service.reassessPolicy(f.client.client, request(f, "first"))
            val history = events(f.service, f.client)
            val frontier = frontier(f)
            f.authority.current = C
            reject("policy_reassessment_ledger_full") { f.service.reassessPolicy(f.client.client, request(f, "second", C)) }
            assertEquals(history, events(f.service, f.client))
            assertEquals(frontier, frontier(f))
        }
        fixture("bytes", limits = config.copy(maxEventPageBytes = 128)).use { f ->
            f.authority.current = B
            val history = events(f.service, f.client)
            val frontier = frontier(f)
            reject("policy_reassessment_event_limit") { f.service.reassessPolicy(f.client.client, request(f, "oversize")) }
            assertEquals(history, events(f.service, f.client))
            assertEquals(frontier, frontier(f))
        }
    }

    private data class Fixture(val root: Path, val input: AnalysisInput, val service: KernelService,
        val client: ClientRegistration, val status: ReviewStatus, val provider: Provider, val authority: Authority,
        val observerCalls: AtomicInteger, val plan: MutableMap<String, RequiredReviewPlan>) : AutoCloseable {
        override fun close() = service.close()
    }

    private fun fixture(name: String, fullProject: Boolean = true, intrinsicFailure: Boolean = false,
                        limits: KernelConfig = config, completeCoverage: Boolean = true): Fixture {
        val source = temp.resolve("source-$name")
        Files.createDirectories(source)
        val text = "val fixture = 1\n"
        Files.writeString(source.resolve("Main.kt"), text)
        val snapshot = SnapshotManifest(Digests.sha256("$name:$text"), "workspace", "tree",
            listOf(SnapshotFile("Main.kt", Digests.sha256(text), text.toByteArray().size.toLong())), ownerId = "owner", declaredRoots = listOf("."))
        val input = AnalysisInput(snapshot, source.toString(), AnalysisContext(compilerVersion = "synthetic/1",
            modelDigest = Digests.sha256("model"), dependencyDigest = Digests.sha256("dependencies"), toolchainDigest = Digests.sha256("toolchain"),
            configurationDigest = A, fullProject = fullProject, compilationFiles = listOf("Main.kt")), listOf(RULE))
        val authority = Authority()
        val provider = Provider(intrinsicFailure, completeCoverage)
        val observerCalls = AtomicInteger()
        val root = temp.resolve("ledger-$name")
        val plans = ConcurrentHashMap<String, RequiredReviewPlan>()
        val service = KernelService.open(root, provider, limits, authority.legacyFence(input),
            preparedInputObserver = PreparedInputObserver { _, _, _ -> observerCalls.incrementAndGet() },
            planAuthority = ReviewPlanAuthority { binding, request -> plans[request.planRef]?.takeIf { it.binding == binding && it.request == request } },
            policyReassessmentAuthority = authority)
        try {
            val client = service.registerClient(binding(name))
            val submitted = service.submit(client.client, SubmitRequest("patch-$name", "key-$name", input))
            val status = await(service, client, submitted.reviewId)
            assertEquals(if (completeCoverage) "complete" else "incomplete", status.state, status.reasons.toString())
            val findings = assertNotNull(status.result).findings
            assertEquals(2, findings.size)
            assertEquals(2, findings.map { it.fingerprint }.distinct().size)
            assertEquals(1, provider.calls.get())
            return Fixture(root, input, service, client, status, provider, authority, observerCalls, plans)
        } catch (failure: Throwable) { service.close(); throw failure }
    }

    private class Authority : PolicyReassessmentAuthority {
        var identitySuffix = "v1"
        override val evaluatorIdentity get() = "synthetic/reassessment/$identitySuffix"
        override val issuerIdentity get() = "synthetic/owner-analysis/$identitySuffix"
        var current = A
        var disposition = "suppressed"
        var authoritative = true
        var transform: ((ProviderResult) -> ProviderResult)? = null
        var checkTransform: (SnapshotCheck) -> SnapshotCheck = { it }
        var prepareHook: () -> Unit = { }
        val prepares = AtomicInteger()
        val applies = AtomicInteger()
        val restores = AtomicInteger()
        val archive = ConcurrentHashMap<String, PolicyReassessmentCapture>() // synthetic owner archive, not filesystem recovery proof
        @Volatile var original: CertifiedPolicyReview? = null
        private val lock = Any()
        override fun prepare(binding: ClientBinding, subject: CertifiedPolicyReview, expectedPolicyDigest: String): PreparedPolicyReassessment {
            prepares.incrementAndGet()
            original = subject
            val capture = PolicyReassessmentCapture(assertNotNull(subject.input.context.configurationDigest), current,
                Digests.sha256("original-capture:${canonical(binding)}:${subject.input.context.configurationDigest}"),
                Digests.sha256("current-capture:${canonical(binding)}:$current"),
                Digests.sha256("exact-synthetic-authority:${canonical(binding)}:${subject.input.analysisKey()}"))
            if (capture.currentPolicyDigest != expectedPolicyDigest) {
                throw KernelException("policy_reassessment_capture_mismatch", "Synthetic expected current capture differs")
            }
            archive[capture.currentCaptureId] = capture
            val result = prepared(subject, capture)
            prepareHook()
            return result
        }
        override fun restore(binding: ClientBinding, subject: CertifiedPolicyReview, capture: PolicyReassessmentCapture): PreparedPolicyReassessment {
            restores.incrementAndGet()
            check(archive[capture.currentCaptureId] == capture)
            return prepared(subject, capture)
        }
        private fun prepared(subject: CertifiedPolicyReview, saved: PolicyReassessmentCapture): PreparedPolicyReassessment {
            val label = disposition
            val evaluator = transform
            return object : PreparedPolicyReassessment {
                override val capture = saved
                override fun apply(raw: ProviderResult): ProviderResult {
                    applies.incrementAndGet()
                    return evaluator?.invoke(raw) ?: raw.copy(findings = raw.findings.map { it.copy(disposition = label) })
                }
                override fun <T> withCurrent(action: (SnapshotCheck) -> T): T = synchronized(lock) {
                    val input = subject.input
                    action(checkTransform(SnapshotCheck(current == saved.currentPolicyDigest, input.snapshot.snapshotId, 1, current,
                        input.context.modelDigest, input.context.dependencyDigest, input.context.toolchainDigest,
                        "synthetic-analysis-epoch:$current", authoritative = authoritative)))
                }
            }
        }
        fun legacyFence(expected: AnalysisInput): SnapshotGate = object : SnapshotGate {
            override fun <T> withCurrentSnapshot(input: AnalysisInput, action: (SnapshotCheck) -> T): T = synchronized(lock) {
                action(SnapshotCheck(input == expected && current == expected.context.configurationDigest,
                    expected.snapshot.snapshotId, 1, current, expected.context.modelDigest, expected.context.dependencyDigest,
                    expected.context.toolchainDigest, "synthetic-analysis-epoch:$current", authoritative = authoritative))
            }
        }
    }

    private class Provider(private val intrinsicFailure: Boolean, private val completeCoverage: Boolean) : AnalysisProvider {
        override val id = "synthetic-policy-reassessment"
        override val version = "1"
        override val supportedRuleIds = setOf(RULE)
        val calls = AtomicInteger()
        override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
            calls.incrementAndGet()
            cancellation.throwIfCancelled()
            val key = input.analysisKey()
            val one = Finding(RULE, "First synthetic occurrence", "Diagnostic", "Fixture only",
                SourceLocation("Main.kt", input.snapshot.files.single().digest), Digests.sha256("first-fingerprint"), id,
                context = input.context.scope, evidence = listOf(Evidence(if (intrinsicFailure) "build_failure" else "compiler-options", key, digest = key)),
                semanticDigest = key, verification = listOf(Verification(expected = "No invocation during reassessment", command = listOf("synthetic", key))))
            val two = one.copy(title = "Second distinct occurrence", fingerprint = Digests.sha256("second-fingerprint"),
                evidence = one.evidence + Evidence("occurrence", "second"))
            return ProviderResult(id, "complete", listOf(one, two), if (!completeCoverage) emptyList() else
                listOf(Coverage(RULE, input.context.scope, "findings", provenance = "executed",
                    certificateId = Digests.sha256("synthetic-coverage:$key"), implemented = true, tested = true, qualified = true)), providerVersion = version)
        }
    }

    private fun retained(service: KernelService): List<List<String?>> = service.ledger.read { c ->
        c.query("""SELECT r.review_id,r.input_json,r.work_key,r.effective_result_json,r.policy_certificate,r.policy_validation_json,
            w.input_json,w.frozen_json,w.result_json,w.validation_json,w.provider_key,wc.input_key,wc.effective_key,wc.result_digest,wc.certificate_id
            FROM reviews r JOIN work w ON w.work_key=r.work_key LEFT JOIN work_certificates wc ON wc.work_key=w.work_key ORDER BY r.review_id""") { row ->
            (1..15).map { row.getString(it) }
        }
    }
    private fun receipts(service: KernelService): List<List<String?>> = service.ledger.read { c ->
        listOf("receipts", "acknowledgements", "observations").flatMap { table ->
            c.query("SELECT * FROM $table ORDER BY rowid") { row ->
                listOf(table) + (1..row.metaData.columnCount).map { row.getString(it) }
            }
        }
    }
    private fun frontier(f: Fixture): Pair<Long, Long> = f.service.ledger.read { c ->
        assertNotNull(c.one("SELECT epoch,next_event FROM clients WHERE client_id=?", f.client.binding.clientId) { it.getLong(1) to it.getLong(2) })
    }
    private fun await(service: KernelService, client: ClientRegistration, reviewId: String): ReviewStatus {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        var status = service.status(client.client, reviewId)
        while (status.state in setOf("queued", "running") && System.nanoTime() < deadline) {
            Thread.sleep(5)
            status = service.status(client.client, reviewId)
        }
        assertFalse(status.state in setOf("queued", "running"), "Synthetic work did not terminalize")
        return status
    }
    private fun events(service: KernelService, client: ClientRegistration): List<ReviewEvent> = buildList {
        var cursor: String? = null
        var more: Boolean
        do {
            val page = service.events(client.client, cursor)
            addAll(page.events)
            cursor = page.cursor
            more = page.hasMore
        } while (more)
    }
    private fun observeAll(service: KernelService, client: ClientRegistration): List<ReviewEvent> = events(service, client).also { all ->
        all.forEach { acknowledge(service, client, it); observe(service, client, it) }
    }
    private fun contextDigest(event: ReviewEvent) = Digests.sha256("synthetic-context:${event.eventId}:${event.payloadDigest}")
    private fun acknowledge(service: KernelService, client: ClientRegistration, event: ReviewEvent) {
        service.acknowledge(client.adapter, listOf(event.eventId), ContextReceipt("ack-${event.eventId}", contextDigest(event), "item-${event.eventId}", Instant.EPOCH.toString()))
    }
    private fun observe(service: KernelService, client: ClientRegistration, event: ReviewEvent) {
        service.observe(client.adapter, listOf(event.eventId), ObservationReceipt("observation-${event.eventId}", "synthetic-model-${event.eventId}", listOf(contextDigest(event))))
    }
    private fun request(f: Fixture, key: String, policy: String = B) = PolicyReassessmentRequest(f.status.reviewId, key, f.client.binding.generation, policy)
    private fun reject(code: String, operation: () -> Unit) { assertEquals(code, assertFailsWith<KernelException> { operation() }.code) }
    companion object {
        private const val RULE = "test/policy-reassessment"
        private val A = Digests.sha256("policy-A")
        private val B = Digests.sha256("policy-B")
        private val C = Digests.sha256("policy-C")
        private fun binding(id: String) = ClientBinding("owner", "workspace", "tree", id, "lineage-$id", sessionId = "session-$id")
    }
}

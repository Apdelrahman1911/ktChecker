package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/** Synthetic kernel SPI only. No Gradle/model/compiler execution or qualified-rule evidence. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class KernelObservedProducedPlanTest {
    @TempDir lateinit var temp: Path

    companion object {
        // Deliberately independent of the implementation's marker set and reason helper.
        private const val GAP = "configuration_input_closure_unproven"
        private val MARKERS = listOf("ktchecker.model-assurance", "ktchecker.observed-export-invocation",
            "ktchecker.observed-model-evidence", "ktchecker.observed-export-closure", "ktchecker.observed-export-evidence")
    }

    private fun code(expected: String, body: () -> Unit) =
        assertEquals(expected, assertFailsWith<KernelException>(block = body).code)

    private fun evidence(definition: RequiredReviewPlan) = assertNotNull(assertNotNull(definition.jvmProduced).observedExport)
    private fun nonceKey(value: ObservedExportPlanEvidence) = "observed-export:${Digests.sha256(value.invocationId)}"
    private fun nonceParts(request: ProducedPlanAdmissionRequest) = listOf(request.planId, request.planDigest,
        canonical(request.binding), canonical(request.request), request.originInstance, request.providerKey,
        canonical(evidence(request.definition)))
    private fun nonceValue(service: KernelService, value: ObservedExportPlanEvidence): String = service.ledger.read { c ->
        assertNotNull(c.one("SELECT value FROM metadata WHERE key=?", nonceKey(value)) { it.getString(1) })
    }

    private fun declarations(service: KernelService, plan: ParentPlanStatus) = service.ledger.read { c ->
        c.query("""SELECT r.review_id,r.sequence,r.input_json FROM plan_slots s JOIN reviews r ON r.review_id=s.review_id
            WHERE s.plan_id=? ORDER BY s.slot_id""", plan.planId) { Triple(it.getString(1), it.getLong(2), it.getString(3)) }
    }
    private fun assertDeclarations(service: KernelService, definition: RequiredReviewPlan, plan: ParentPlanStatus, firstSequence: Long = 1) {
        val rows = declarations(service, plan)
        assertEquals(plan.slots.map { it.reviewId }, rows.map { it.first })
        assertEquals(listOf(firstSequence, firstSequence + 1), rows.map { it.second })
        assertEquals(definition.slots.map { canonical(it.input) }, rows.map { it.third })
        service.ledger.read { c ->
            assertEquals(firstSequence + 1, c.count("SELECT review_frontier FROM parent_plans WHERE plan_id=?", plan.planId))
            assertEquals(2L, c.count("""SELECT COUNT(*) FROM reviews r JOIN plan_slots s ON s.review_id=r.review_id
                WHERE s.plan_id=? AND r.kind='produced' AND r.work_key IS NULL""", plan.planId))
        }
    }
    private fun assertGapVector(definition: RequiredReviewPlan, plan: ParentPlanStatus) {
        assertEquals(definition.binding.clientId, plan.clientId)
        assertEquals(definition.slots.first().input.snapshot.snapshotId, plan.sourceSnapshotId)
        assertEquals(Digests.sha256(canonical(definition)), plan.planDigest)
        assertEquals(listOf(ProducedLaneFixture.PRODUCER, ProducedLaneFixture.CONSUMER), plan.slots.map { it.slotId })
        assertEquals(2, plan.slots.map { it.reviewId }.distinct().size)
        assertEquals(definition.slots.map { it.input.requiredRuleIds }, plan.slots.map { it.requiredRuleIds })
        assertTrue(plan.slots.all { it.requiredRuleIds.size == 1663 && GAP in it.reasons })
        assertTrue(GAP in plan.reasons)
        assertTrue("produced_plan_preview_unqualified" in plan.reasons)
    }
    private fun counts(service: KernelService): Map<String, Long> = service.ledger.read { c ->
        listOf("parent_plans", "produced_plan_admissions", "reviews", "plan_slots", "plan_slot_resolutions",
            "work", "work_certificates", "work_publications", "events").associateWith { c.count("SELECT COUNT(*) FROM $it") } +
            ("observed_nonces" to c.count("SELECT COUNT(*) FROM metadata WHERE key LIKE 'observed-export:%'"))
    }
    private fun assertNoAdmission(service: KernelService, f: ProducedLaneFixture, provider: ProducedLaneFixture.Provider) {
        assertTrue(counts(service).values.all { it == 0L })
        service.ledger.read { c ->
            assertEquals(0L, c.count("SELECT COUNT(*) FROM clients WHERE next_review<>1 OR next_event<>1 OR epoch<>0"))
            assertEquals(0L, service.archiveBytesUsed(c)); assertEquals(0L, service.queuedCapacityUsed(c))
            assertEquals(0L, c.count("SELECT COUNT(*) FROM plan_slot_resolution_claims"))
        }
        assertTrue(f.admissions.isEmpty()); assertTrue(f.preparations.isEmpty())
        assertEquals(0, provider.calls.get()); assertEquals(0, f.producerPreparations.get())
        assertEquals(0, f.ordinaryPreparations.get()); assertTrue(f.typedAttempts.isEmpty())
    }

    private data class Published(
        val slotId: String, val execution: ResolvedPlanExecution, val executionCertificateId: String,
        val rawJson: String, val validation: List<String>, val publication: ProducedWorkPublication,
        val publicationJson: String, val publicationId: String, val resultDigest: String,
    )
    private fun published(service: KernelService, plan: ParentPlanStatus): List<Published> = service.ledger.read { c ->
        c.query("""SELECT r.slot_id,r.record_json resolution_json,r.certificate_id execution_certificate_id,
            w.result_json,w.validation_json,p.record_json publication_json,p.certificate_id publication_id,wc.result_digest
            FROM plan_slot_resolutions r JOIN work w ON w.work_key=r.work_key
            JOIN work_publications p ON p.work_key=w.work_key JOIN work_certificates wc ON wc.work_key=w.work_key
            WHERE r.plan_id=? ORDER BY r.slot_id""", plan.planId) { row ->
            val execution = ContractJson.decodeFromString<ResolvedPlanExecution>(
                ContractJson.parseToJsonElement(row.getString("resolution_json")).jsonObject.getValue("execution").toString())
            val publicationJson = row.getString("publication_json")
            Published(row.getString("slot_id"), execution, row.getString("execution_certificate_id"), row.getString("result_json"),
                ContractJson.decodeFromString(row.getString("validation_json")), ContractJson.decodeFromString(publicationJson),
                publicationJson, row.getString("publication_id"), row.getString("result_digest"))
        }
    }
    private fun assertObservedPublication(value: ObservedExportPlanEvidence, row: Published) {
        assertEquals(value, row.execution.certificate.observedExport)
        assertEquals(value, row.publication.observedExport)
        assertEquals(row.execution.certificate.digest(), row.executionCertificateId)
        assertEquals(row.executionCertificateId, row.publication.executionCertificate)
        assertEquals(row.execution.workKey, row.publication.workKey)
        assertEquals(row.execution.input.analysisKey(), row.publication.originalInputKey)
        assertEquals(row.execution.certificate.providerKey, row.publication.providerKey)
        assertEquals(canonical(row.publication), row.publicationJson)
        assertEquals(row.publication.digest(), row.publicationId)
        assertEquals(Digests.sha256(row.rawJson), row.publication.rawResultDigest)
        assertEquals(row.publication.rawResultDigest, row.resultDigest)
        assertTrue(GAP in row.validation, "Broker validation must retain the gap independently of the raw result")
    }
    private fun assertGapEvents(events: List<ReviewEvent>, definition: RequiredReviewPlan, plan: ParentPlanStatus) {
        assertTrue(events.isNotEmpty())
        events.forEach { event ->
            assertEquals(Digests.sha256(event.payload), event.payloadDigest)
            val status = ContractJson.decodeFromString<ReviewStatus>(event.payload)
            assertTrue(status.reviewId in plan.slots.map { it.reviewId })
            assertTrue(GAP in status.reasons, event.kind)
            status.result?.let { raw -> assertEquals("partial", raw.status); assertTrue(GAP in raw.reasons) }
            status.parentPlan?.let { parent ->
                assertEquals(plan.slots.map { it.reviewId }, parent.slots.map { it.reviewId })
                assertGapVector(definition, parent)
            }
        }
    }
    private fun assertDeliveredButBlocked(service: KernelService, client: ClientRegistration, plan: ParentPlanStatus) {
        val cut = service.prepareGate(client.client)
        assertFalse(cut.ready)
        plan.slots.forEach { assertTrue("$GAP:${it.reviewId}" in cut.reasons, cut.reasons.toString()) }
        assertFalse(cut.reasons.any { it.startsWith("insertion_missing:") || it.startsWith("model_observation_missing:") ||
            it == "delivery_frontier_changed" }, cut.reasons.toString())
        val effects = AtomicInteger()
        val denied = service.completeGate(client.client, cut.cutId) { effects.incrementAndGet() }
        assertFalse(denied.allowed); assertFalse(denied.actionPerformed); assertEquals(0, effects.get())
    }

    @Test fun everyObservedPolicyKeyRejectsScalarAndEachOrdinarySlotBeforeAdmission() {
        val f = ProducedLaneFixture(temp.resolve("fixture")); val provider = f.Provider()
        KernelService.open(temp.resolve("ledger"), provider, ProducedLaneFixture.CONFIG.copy(workerCount = 0),
            planAuthority = f, preparedInputObserver = f.observer).use { service ->
            val client = service.registerClient(ProducedLaneFixture.binding("marker"))
            val base = f.definition(client.binding, "marker")
            // An otherwise executable ordinary vector, not a failure caused by declared slot state.
            val ordinary = base.copy(jvmProduced = null, slots = base.slots.map { slot ->
                val input = slot.input.copy(context = slot.input.context.copy(fullProject = true))
                slot.copy(input = input, identity = assertNotNull(contextIdentity(input)), state = "pending", reasons = emptyList())
            })
            MARKERS.forEachIndexed { index, marker ->
                // Presence is forbidden even with a blank or wrong value; no complete marker bundle is needed.
                for (value in listOf("", "synthetic-untrusted-marker")) {
                    val scalar = ordinary.slots.first().input.let { it.copy(context = it.context.copy(policies = it.context.policies + (marker to value))) }
                    code("observed_export_typed_plan_required") {
                        service.submit(client.client, SubmitRequest("scalar-$index", "scalar-$index", scalar))
                    }
                    ordinary.slots.indices.forEach { markedSlot ->
                        val candidate = ordinary.copy(slots = ordinary.slots.mapIndexed { position, slot ->
                            if (position != markedSlot) slot else slot.input.let { input ->
                                val marked = input.copy(context = input.context.copy(policies = input.context.policies + (marker to value)))
                                slot.copy(input = marked, identity = assertNotNull(contextIdentity(marked)))
                            }
                        })
                        f.definitions[base.request.planRef] = candidate
                        code("observed_export_typed_plan_required") { service.submitPlan(client.client, candidate.request) }
                    }
                }
            }
            assertNoAdmission(service, f, provider)
        }
    }

    @Test fun removingTypedEvidenceCannotDowngradeAnyObservedMarker() {
        val f = ProducedLaneFixture(temp.resolve("fixture")); val provider = f.Provider()
        KernelService.open(temp.resolve("ledger"), provider, ProducedLaneFixture.CONFIG.copy(workerCount = 0),
            planAuthority = f, preparedInputObserver = f.observer).use { service ->
            val client = service.registerClient(ProducedLaneFixture.binding("downgrade"))
            val observed = f.definition(client.binding, "downgrade", observedInvocation = "observed-downgrade")
            val graph = assertNotNull(observed.jvmProduced)
            // Removing the evidence object, or all but ONE raw/controller marker, never opens the old lane.
            for (remaining in MARKERS.map { setOf(it) } + listOf(MARKERS.toSet())) {
                val downgraded = observed.copy(jvmProduced = graph.copy(observedExport = null), slots = observed.slots.map { slot ->
                    slot.copy(input = slot.input.copy(context = slot.input.context.copy(
                        policies = slot.input.context.policies.filterKeys { it !in MARKERS || it in remaining })))
                })
                f.definitions[observed.request.planRef] = downgraded
                code("observed_export_typed_plan_required") { service.submitPlan(client.client, downgraded.request) }
            }
            assertNoAdmission(service, f, provider)
        }
    }

    @Test fun evidenceRequiresExactBindingAssuranceDigestAndEachDeclaredGap() {
        val f = ProducedLaneFixture(temp.resolve("fixture")); val provider = f.Provider()
        KernelService.open(temp.resolve("ledger"), provider, ProducedLaneFixture.CONFIG.copy(workerCount = 0),
            planAuthority = f, preparedInputObserver = f.observer).use { service ->
            val client = service.registerClient(ProducedLaneFixture.binding("evidence"))
            val base = f.definition(client.binding, "evidence", observedInvocation = "observed-evidence")
            val graph = assertNotNull(base.jvmProduced); val observed = evidence(base); val binding = client.binding
            val foreignBindings = listOf(binding.copy(ownerId = "other-owner"), binding.copy(workspaceId = "other-workspace"),
                binding.copy(worktreeId = "other-tree"), binding.copy(clientId = "other-client"), binding.copy(lineageId = "other-lineage"),
                binding.copy(generation = binding.generation + 1), binding.copy(sessionId = "other-session"))
            val invalid = foreignBindings.map { observed.copy(binding = it) } + listOf(
                observed.copy(assurance = "ktchecker/verified-model/1"), observed.copy(evidenceDigest = "not-a-digest"))
            invalid.forEach { value ->
                f.definitions[base.request.planRef] = base.copy(jvmProduced = graph.copy(observedExport = value))
                code("observed_export_evidence_invalid") { service.submitPlan(client.client, base.request) }
            }
            base.slots.indices.forEach { changed ->
                val withoutGap = base.copy(slots = base.slots.mapIndexed { index, slot ->
                    if (index == changed) slot.copy(reasons = slot.reasons.filterNot { it == GAP }) else slot
                })
                f.definitions[base.request.planRef] = withoutGap
                code("observed_export_declaration_gap_missing") { service.submitPlan(client.client, base.request) }
                MARKERS.forEach { marker ->
                    for (missing in listOf(true, false)) {
                        f.definitions[base.request.planRef] = base.copy(slots = base.slots.mapIndexed { index, slot ->
                            if (index != changed) slot else {
                                val policies = if (missing) slot.input.context.policies - marker
                                    else slot.input.context.policies + (marker to "synthetic-wrong-marker")
                                slot.copy(input = slot.input.copy(context = slot.input.context.copy(policies = policies)))
                            }
                        })
                        code("observed_export_declaration_gap_missing") { service.submitPlan(client.client, base.request) }
                    }
                }
            }
            assertNoAdmission(service, f, provider)
        }
    }

    @Test fun rawObservedContextCannotBeRetargetedOrHaveItsProvenanceStripped() {
        val f = ProducedLaneFixture(temp.resolve("fixture")); val provider = f.Provider()
        KernelService.open(temp.resolve("ledger"), provider, ProducedLaneFixture.CONFIG.copy(workerCount = 0),
            planAuthority = f, preparedInputObserver = f.observer).use { service ->
            val client = service.registerClient(ProducedLaneFixture.binding("raw-context"))
            val base = f.definition(client.binding, "raw-context", observedInvocation = "observed-raw-context")
            val graph = assertNotNull(base.jvmProduced)
            val controllerOnly = setOf("ktchecker.catalog-inventory", "ktchecker.activation-plan", "ktchecker.observed-export-evidence")
            base.slots.indices.forEach { changed ->
                val slot = base.slots[changed]
                val raw = slot.input.context.copy(policies = slot.input.context.policies - controllerOnly)
                assertEquals(MARKERS.take(4).toSet(), raw.policies.keys.intersect(MARKERS.toSet()))
                val declaration = if (changed == 0) graph.producer else graph.consumer
                assertEquals(Digests.sha256(canonical(raw)), declaration.rawContextDigest)
                val unobservedDigest = Digests.sha256(canonical(raw.copy(policies = raw.policies - MARKERS.toSet())))
                assertNotEquals(declaration.rawContextDigest, unobservedDigest)
                val stripped = declaration.copy(rawContextDigest = unobservedDigest)
                f.definitions[base.request.planRef] = base.copy(jvmProduced = if (changed == 0) graph.copy(producer = stripped) else graph.copy(consumer = stripped))
                code("produced_plan_declaration_mismatch") { service.submitPlan(client.client, base.request) }
                f.definitions[base.request.planRef] = base.copy(slots = base.slots.mapIndexed { index, current ->
                    if (index != changed) current else current.copy(input = current.input.copy(context = current.input.context.copy(
                        compilerArguments = current.input.context.compilerArguments + "-verbose")))
                })
                code("produced_plan_declaration_mismatch") { service.submitPlan(client.client, base.request) }
            }
            // Even internally consistent new evidence/markers cannot reuse the old raw-context digest.
            val retargeted = evidence(base).copy(invocationId = "another-observed-invocation", evidenceDigest = Digests.sha256("another-export"))
            f.definitions[base.request.planRef] = base.copy(jvmProduced = graph.copy(observedExport = retargeted), slots = base.slots.map { slot ->
                slot.copy(input = slot.input.copy(context = slot.input.context.copy(policies = slot.input.context.policies + retargeted.policies())))
            })
            code("produced_plan_declaration_mismatch") { service.submitPlan(client.client, base.request) }
            assertNoAdmission(service, f, provider)
        }
    }

    @Test fun twoObservedSlotsRetainGapThroughProgressResultsPublicationSuppressionAndIdleRestart() {
        val f = ProducedLaneFixture(temp.resolve("fixture"))
        val provider = f.Provider { input, progress -> progress(f.partial(input)) }
        val fence = ProducedLaneFixture.Fence(); val root = temp.resolve("ledger")
        val definition = f.definition(ProducedLaneFixture.binding("positive"), "positive", "suppressed", observedInvocation = "observed-positive")
        fence.install(definition)
        lateinit var client: ClientRegistration; lateinit var completed: ParentPlanStatus
        var results = emptyList<ReviewStatus>(); var rows = emptyList<Published>(); var events = emptyList<ReviewEvent>()
        var declared = emptyList<Triple<String, Long, String>>(); var nonce = ""; var policyCalls = 0
        KernelService.open(root, provider, ProducedLaneFixture.CONFIG, snapshotGate = fence,
            planAuthority = f, preparedInputObserver = f.observer, resultPolicy = f.policy).use { service ->
            client = service.registerClient(definition.binding)
            val accepted = service.submitPlan(client.client, definition.request)
            assertGapVector(definition, accepted); assertDeclarations(service, definition, accepted)
            results = accepted.slots.map { f.finished(service, client, it.reviewId) }
            results.forEach { review ->
                assertEquals("incomplete", review.state); assertTrue(GAP in review.reasons)
                val result = assertNotNull(review.result)
                assertEquals("partial", result.status); assertTrue(GAP in result.reasons)
                assertEquals(listOf("suppressed"), result.findings.map { it.disposition })
                assertEquals(f.rules, result.coverage.map { it.ruleId }); assertTrue(result.coverage.all { !it.qualified })
            }
            completed = service.planStatus(client.client, accepted.planId)
            assertEquals("incomplete", completed.state); assertGapVector(definition, completed)
            assertDeclarations(service, definition, completed); declared = declarations(service, completed)
            assertEquals(2, provider.calls.get()); assertEquals(2, f.typedAttempts.size)
            assertEquals(1, f.producerPreparations.get()); assertEquals(0, f.ordinaryPreparations.get())
            assertEquals(1, f.resolutionCalls.getValue(definition.request.planRef).get())
            rows = published(service, completed)
            assertEquals(definition.slots.map { it.slotId }, rows.map { it.slotId })
            rows.forEach { row ->
                assertObservedPublication(evidence(definition), row); assertTrue(row.publication.operationalFaults.isEmpty())
                val attempt = f.typedAttempts.single { it.workKey == row.execution.workKey }
                assertEquals(attempt.execution, row.execution)
                assertEquals(canonical(f.partial(attempt.effective)), row.rawJson)
                assertEquals("open", ContractJson.decodeFromString<ProviderResult>(row.rawJson).findings.single().disposition)
            }
            nonce = nonceValue(service, evidence(definition))
            assertEquals(canonical(nonceParts(f.admissions.getValue(definition.request.planRef))), nonce)
            assertEquals(1L, counts(service).getValue("observed_nonces"))
            assertEquals(definition.slots.map { definition.binding.clientId to it.input.analysisKey() }.toSet(), f.policyInputs.toSet())
            policyCalls = f.policyInputs.size
            events = f.events(service, client)
            assertEquals(2, events.count { it.kind == "accepted" }); assertEquals(1, events.count { it.kind == "plan_accepted" })
            assertEquals(2, events.count { it.kind == "progress" }); assertEquals(2, events.count { it.kind == "result" })
            assertGapEvents(events, definition, completed)
            f.deliver(service, client); assertDeliveredButBlocked(service, client, completed)
        }
        val replay = f.Provider()
        // Completed history is readable without a live grant; an idle restart is NOT execution authority.
        KernelService.open(root, replay, ProducedLaneFixture.CONFIG.copy(workerCount = 0), snapshotGate = fence,
            planAuthority = ReviewPlanAuthority.DENY, preparedInputObserver = f.observer, resultPolicy = f.policy).use { service ->
            service.registerClient(client)
            assertEquals(completed, service.planStatus(client.client, completed.planId))
            assertEquals(results, completed.slots.map { service.status(client.client, it.reviewId) })
            assertEquals(rows, published(service, completed)); assertEquals(declared, declarations(service, completed))
            assertEquals(events, f.events(service, client)); assertGapEvents(events, definition, completed)
            assertEquals(nonce, nonceValue(service, evidence(definition)))
            assertDeliveredButBlocked(service, client, completed)
            assertEquals(0, replay.calls.get()); assertEquals(2, f.typedAttempts.size)
            assertEquals(1, f.producerPreparations.get()); assertEquals(policyCalls, f.policyInputs.size)
            assertEquals(1, f.resolutionCalls.getValue(definition.request.planRef).get())
        }
    }

    @Test fun freshInvocationOnSameSnapshotExecutesTwiceInsteadOfReusingPublication() {
        val f = ProducedLaneFixture(temp.resolve("fixture")); val provider = f.Provider()
        KernelService.open(temp.resolve("ledger"), provider, ProducedLaneFixture.CONFIG, planAuthority = f,
            preparedInputObserver = f.observer, resultPolicy = f.policy).use { service ->
            val client = service.registerClient(ProducedLaneFixture.binding("fresh"))
            val a = f.definition(client.binding, "fresh-a", observedInvocation = "observed-fresh-a")
            val pa = service.submitPlan(client.client, a.request); pa.slots.forEach { f.finished(service, client, it.reviewId) }
            val first = published(service, pa); assertEquals(2, provider.calls.get())
            val b = f.definition(client.binding, "fresh-b", observedInvocation = "observed-fresh-b", sameSnapshotAs = a)
            assertEquals(a.slots.map { it.input.snapshot }, b.slots.map { it.input.snapshot })
            assertEquals(a.slots.map { it.input.materializedRoot }, b.slots.map { it.input.materializedRoot })
            assertNotEquals(evidence(a).invocationId, evidence(b).invocationId)
            val pb = service.submitPlan(client.client, b.request); pb.slots.forEach { f.finished(service, client, it.reviewId) }
            val second = published(service, pb)
            assertNotEquals(pa.planId, pb.planId)
            assertTrue(pa.slots.map { it.reviewId }.toSet().intersect(pb.slots.map { it.reviewId }.toSet()).isEmpty())
            assertDeclarations(service, a, pa); assertDeclarations(service, b, pb, firstSequence = 3)
            assertGapVector(a, service.planStatus(client.client, pa.planId)); assertGapVector(b, service.planStatus(client.client, pb.planId))
            assertEquals(4, provider.calls.get()); assertEquals(4, f.typedAttempts.size); assertEquals(2, f.producerPreparations.get())
            assertEquals(4, f.typedAttempts.map { it.workKey }.distinct().size)
            assertEquals(2, first.size); assertEquals(2, second.size)
            // Identical raw observations still require two independently executed, nonce-bound parents.
            assertEquals(first.associate { it.slotId to it.rawJson }, second.associate { it.slotId to it.rawJson })
            first.forEach { assertObservedPublication(evidence(a), it); assertTrue(it.publication.operationalFaults.isEmpty()) }
            second.forEach { assertObservedPublication(evidence(b), it); assertTrue(it.publication.operationalFaults.isEmpty()) }
            for (definition in listOf(a, b)) {
                assertEquals(1, f.resolutionCalls.getValue(definition.request.planRef).get())
                assertEquals(canonical(nonceParts(f.admissions.getValue(definition.request.planRef))), nonceValue(service, evidence(definition)))
            }
            assertEquals(2L, counts(service).getValue("observed_nonces")); assertEquals(4L, counts(service).getValue("work"))
        }
    }

    @Test fun usedInvocationIsDeniedForAnotherRequestClientAndAfterRestart() {
        val f = ProducedLaneFixture(temp.resolve("fixture")); val provider = f.Provider(); val root = temp.resolve("ledger")
        lateinit var client: ClientRegistration; lateinit var peer: ClientRegistration; lateinit var original: RequiredReviewPlan
        lateinit var accepted: ParentPlanStatus; var nonce = ""
        KernelService.open(root, provider, ProducedLaneFixture.CONFIG, planAuthority = f,
            preparedInputObserver = f.observer, resultPolicy = f.policy).use { service ->
            client = service.registerClient(ProducedLaneFixture.binding("used")); peer = service.registerClient(ProducedLaneFixture.binding("peer"))
            original = f.definition(client.binding, "used", observedInvocation = "observed-used-once")
            accepted = service.submitPlan(client.client, original.request); accepted.slots.forEach { f.finished(service, client, it.reviewId) }
            nonce = nonceValue(service, evidence(original)); val before = counts(service)
            for ((owner, id) in listOf(client to "another-request", peer to "another-client")) {
                val candidate = f.definition(owner.binding, id, observedInvocation = evidence(original).invocationId)
                assertEquals(owner.binding, evidence(candidate).binding)
                code("observed_export_already_admitted") { service.submitPlan(owner.client, candidate.request) }
                assertEquals(before, counts(service)); assertEquals(nonce, nonceValue(service, evidence(original)))
                assertEquals(1, f.producerPreparations.get()); assertFalse(f.preparations.containsKey(candidate.request.planRef))
                assertEquals(0, f.resolutionCalls.getValue(candidate.request.planRef).get())
            }
            assertEquals(2, provider.calls.get()); assertDeclarations(service, original, accepted)
            service.ledger.read { c -> assertEquals(1L, c.count("SELECT next_review FROM clients WHERE client_id=?", peer.binding.clientId)) }
        }
        val replay = f.Provider()
        KernelService.open(root, replay, ProducedLaneFixture.CONFIG.copy(workerCount = 0), planAuthority = f,
            preparedInputObserver = f.observer, resultPolicy = f.policy).use { service ->
            service.registerClient(client); service.registerClient(peer)
            val before = counts(service)
            for ((owner, id) in listOf(client to "restart-request", peer to "restart-client")) {
                val candidate = f.definition(owner.binding, id, observedInvocation = evidence(original).invocationId)
                code("observed_export_already_admitted") { service.submitPlan(owner.client, candidate.request) }
                assertEquals(before, counts(service)); assertEquals(nonce, nonceValue(service, evidence(original)))
                assertFalse(f.preparations.containsKey(candidate.request.planRef))
            }
            assertEquals(0, replay.calls.get()); assertEquals(1, f.producerPreparations.get()); assertEquals(2, f.typedAttempts.size)
            assertDeclarations(service, original, accepted); assertGapVector(original, service.planStatus(client.client, accepted.planId))
        }
    }

    @Test fun exactRetryNeverReauthorizesOrRearmsProducerEvenAfterRestart() {
        val f = ProducedLaneFixture(temp.resolve("fixture")); val provider = f.Provider(); val root = temp.resolve("ledger")
        val resolves = AtomicInteger(); val grants = AtomicInteger(); lateinit var grant: AuthorizedProducedPlan
        val authority = object : ReviewPlanAuthority {
            override fun resolve(binding: ClientBinding, request: SubmitPlanRequest): RequiredReviewPlan? {
                resolves.incrementAndGet(); return f.resolve(binding, request)
            }
            override fun authorizeProduced(request: ProducedPlanAdmissionRequest): AuthorizedProducedPlan {
                grants.incrementAndGet(); return f.authorizeProduced(request).also { grant = it }
            }
        }
        lateinit var client: ClientRegistration; lateinit var definition: RequiredReviewPlan; lateinit var completed: ParentPlanStatus
        var savedCounts = emptyMap<String, Long>(); var savedRows = emptyList<Published>(); var nonce = ""
        KernelService.open(root, provider, ProducedLaneFixture.CONFIG, planAuthority = authority,
            preparedInputObserver = f.observer, resultPolicy = f.policy).use { service ->
            client = service.registerClient(ProducedLaneFixture.binding("retry"))
            definition = f.definition(client.binding, "retry", observedInvocation = "observed-exact-retry")
            val accepted = service.submitPlan(client.client, definition.request)
            val preparation = f.preparations.getValue(definition.request.planRef)
            repeat(2) {
                val retry = service.submitPlan(client.client, definition.request)
                assertEquals(accepted.planId, retry.planId); assertEquals(accepted.slots.map { it.reviewId }, retry.slots.map { it.reviewId })
                assertSame(preparation, f.preparations.getValue(definition.request.planRef))
                assertEquals(1, resolves.get()); assertEquals(1, grants.get()); assertEquals(1, f.producerPreparations.get())
            }
            accepted.slots.forEach { f.finished(service, client, it.reviewId) }
            completed = service.planStatus(client.client, accepted.planId)
            savedCounts = counts(service); savedRows = published(service, completed); nonce = nonceValue(service, evidence(definition))
            // A retry reads history even when the original execution grant can no longer be used.
            grant.validity.invalidate(); assertFalse(grant.validity.isCurrent())
            assertEquals(completed, service.submitPlan(client.client, definition.request))
            assertEquals(savedCounts, counts(service)); assertEquals(savedRows, published(service, completed))
            assertEquals(1, resolves.get()); assertEquals(1, grants.get()); assertEquals(1, f.producerPreparations.get())
            assertSame(preparation, f.preparations.getValue(definition.request.planRef)); assertEquals(2, provider.calls.get())
        }
        val replay = f.Provider()
        KernelService.open(root, replay, ProducedLaneFixture.CONFIG.copy(workerCount = 0), planAuthority = ReviewPlanAuthority.DENY,
            preparedInputObserver = f.observer, resultPolicy = f.policy).use { service ->
            service.registerClient(client)
            assertEquals(completed, service.submitPlan(client.client, definition.request))
            assertEquals(savedCounts, counts(service)); assertEquals(savedRows, published(service, completed))
            assertEquals(nonce, nonceValue(service, evidence(definition))); assertDeclarations(service, definition, completed)
            assertEquals(0, replay.calls.get()); assertEquals(1, f.producerPreparations.get()); assertEquals(2, f.typedAttempts.size)
        }
    }

    @Test fun missingOrAlteredNonceMetadataFailsStatusAndLiveClaimWithoutExecution() {
        val f = ProducedLaneFixture(temp.resolve("fixture")); val provider = f.Provider()
        KernelService.open(temp.resolve("ledger"), provider, ProducedLaneFixture.CONFIG.copy(workerCount = 0),
            planAuthority = f, preparedInputObserver = f.observer, resultPolicy = f.policy).use { service ->
            val client = service.registerClient(ProducedLaneFixture.binding("nonce-corrupt"))
            val definition = f.definition(client.binding, "nonce-corrupt", observedInvocation = "observed-nonce-corrupt")
            val plan = service.submitPlan(client.client, definition.request); val observed = evidence(definition)
            val key = nonceKey(observed); val original = nonceValue(service, observed)
            val parts = nonceParts(f.admissions.getValue(definition.request.planRef)); assertEquals(canonical(parts), original)
            val work = service.ledger.read { c -> assertNotNull(c.one(
                "SELECT work_key FROM plan_slot_resolutions WHERE plan_id=? AND slot_id=?", plan.planId, ProducedLaneFixture.PRODUCER) { it.getString(1) }) }
            assertTrue(service.ledger.transaction { c -> service.producedPlans.mayClaim(c, work) })
            val mutations: List<String?> = listOf(null) + parts.indices.map { changed ->
                canonical(parts.mapIndexed { index, part -> if (index == changed) "$part:synthetic-altered" else part })
            }
            mutations.forEach { changed ->
                try {
                    service.ledger.transaction { c ->
                        if (changed == null) assertEquals(1, c.execute("DELETE FROM metadata WHERE key=?", key))
                        else assertEquals(1, c.execute("UPDATE metadata SET value=? WHERE key=?", changed, key))
                    }
                    code("produced_plan_corrupt") { service.planStatus(client.client, plan.planId) }
                    plan.slots.forEach { slot -> code("produced_plan_corrupt") { service.status(client.client, slot.reviewId) } }
                    // Same live origin/grant, not a foreign-origin early false: claim must actually check the nonce row.
                    code("produced_plan_corrupt") { service.ledger.transaction { c -> service.producedPlans.mayClaim(c, work) } }
                } finally {
                    service.ledger.transaction { c -> c.execute("INSERT OR REPLACE INTO metadata(key,value) VALUES(?,?)", key, original) }
                }
            }
            assertEquals(original, nonceValue(service, observed)); assertEquals(plan, service.planStatus(client.client, plan.planId))
            assertTrue(service.ledger.transaction { c -> service.producedPlans.mayClaim(c, work) })
            assertDeclarations(service, definition, plan)
            service.ledger.read { c ->
                assertEquals(1L, c.count("SELECT COUNT(*) FROM work WHERE state='queued' AND attempt=0 AND owner_instance IS NULL"))
                assertEquals(0L, c.count("SELECT COUNT(*) FROM work_publications"))
            }
            assertEquals(0, provider.calls.get()); assertTrue(f.typedAttempts.isEmpty())
            assertEquals(1, f.producerPreparations.get()); assertEquals(0, f.resolutionCalls.getValue(definition.request.planRef).get())
        }
    }

    @Test fun kernelRejectsRawTaintLossOrCleanStatusEvenWhenOwnerClassifierClaimsClean() {
        for (mode in listOf("missing-gap", "complete-status", "complete-without-gap")) {
            val f = ProducedLaneFixture(temp.resolve(mode)); val delegate = f.Provider()
            val emitted = ConcurrentLinkedQueue<ProviderResult>(); val ownerClassifications = AtomicInteger()
            val provider = object : AnalysisProvider {
                override val id = delegate.id
                override val supportedRuleIds = delegate.supportedRuleIds
                override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
                    val good = delegate.analyze(input, cancellation)
                    return when (mode) {
                        "missing-gap" -> good.copy(reasons = good.reasons.filterNot { it == GAP })
                        "complete-status" -> good.copy(status = "complete")
                        else -> good.copy(status = "complete", reasons = emptyList())
                    }.also { emitted += it }
                }
            }
            val authority = object : ReviewPlanAuthority {
                override fun resolve(binding: ClientBinding, request: SubmitPlanRequest) = f.resolve(binding, request)
                override fun authorizeProduced(request: ProducedPlanAdmissionRequest): AuthorizedProducedPlan {
                    val original = f.authorizeProduced(request)
                    // Intentionally wrong owner classifier: the fixture's stricter classifier must NOT be the protection under test.
                    return AuthorizedProducedPlan(original.request, original.clock, original.authorityIdentity, original.validity,
                        verifyForAdmission = original.verifyForAdmission, prepareProducer = original.prepareProducer,
                        operationalFaults = { ownerClassifications.incrementAndGet(); emptyList() }, resolveConsumer = original.resolveConsumer)
                }
            }
            KernelService.open(temp.resolve("ledger-$mode"), provider, ProducedLaneFixture.CONFIG, planAuthority = authority,
                preparedInputObserver = f.observer, resultPolicy = f.policy).use { service ->
                val client = service.registerClient(ProducedLaneFixture.binding(mode))
                val definition = f.definition(client.binding, mode, observedInvocation = "observed-$mode")
                val plan = service.submitPlan(client.client, definition.request)
                val producer = f.finished(service, client, plan.slots.first().reviewId)
                f.await("Bad raw producer must retain, but never invoke, the consumer") { f.resolved(service, plan.slots.last().reviewId) == "unavailable" }
                val raw = emitted.single(); val row = published(service, plan).single()
                assertEquals(raw, producer.result); assertEquals(canonical(raw), row.rawJson)
                assertEquals(if (mode == "missing-gap") "partial" else "complete", raw.status)
                assertEquals(mode == "complete-status", GAP in raw.reasons)
                assertObservedPublication(evidence(definition), row)
                val faults = buildList {
                    if (mode != "missing-gap") add("observed_export_raw_result_not_partial")
                    if (mode != "complete-status") add("observed_export_raw_configuration_gap_missing")
                }.sorted()
                assertEquals(faults, row.publication.operationalFaults)
                val consumer = service.status(client.client, plan.slots.last().reviewId)
                assertEquals("incomplete", producer.state); assertTrue(GAP in producer.reasons)
                assertEquals("incomplete", consumer.state); assertNull(consumer.result); assertTrue(GAP in consumer.reasons)
                assertTrue("produced_operational_publication_failed" in consumer.reasons)
                assertEquals(1, ownerClassifications.get()); assertEquals(1, delegate.calls.get()); assertEquals(1, f.typedAttempts.size)
                assertEquals(0, f.resolutionCalls.getValue(definition.request.planRef).get())
                assertEquals(1L, counts(service).getValue("work")); assertEquals(1L, counts(service).getValue("work_publications"))
                assertDeclarations(service, definition, plan); assertGapVector(definition, service.planStatus(client.client, plan.planId))
            }
        }
    }

    @Test fun resultPolicyCannotEraseObservedGapOrPromoteRawResultToComplete() {
        for (mode in listOf("erase-gap", "promote-complete")) {
            val f = ProducedLaneFixture(temp.resolve(mode)); val provider = f.Provider { input, progress -> progress(f.partial(input)) }
            val fence = ProducedLaneFixture.Fence()
            val policy = ResultPolicy.versioned("synthetic/observed-protected-$mode/1") { binding, input, raw ->
                val suppressed = f.policy.apply(binding, input, raw)
                if (mode == "erase-gap") suppressed.copy(reasons = suppressed.reasons.filterNot { it == GAP })
                else suppressed.copy(status = "complete")
            }
            KernelService.open(temp.resolve("ledger-$mode"), provider, ProducedLaneFixture.CONFIG, snapshotGate = fence,
                planAuthority = f, preparedInputObserver = f.observer, resultPolicy = policy).use { service ->
                val client = service.registerClient(ProducedLaneFixture.binding(mode))
                val definition = f.definition(client.binding, mode, "suppressed", observedInvocation = "observed-policy-$mode"); fence.install(definition)
                val plan = service.submitPlan(client.client, definition.request)
                plan.slots.forEach { slot ->
                    val status = f.finished(service, client, slot.reviewId)
                    assertEquals("incomplete", status.state); assertTrue(GAP in status.reasons)
                    assertTrue("result_policy_changed_protected_evidence" in status.reasons)
                    val raw = assertNotNull(status.result)
                    assertEquals("partial", raw.status); assertTrue(GAP in raw.reasons)
                    assertEquals(listOf("open"), raw.findings.map { it.disposition }, "A forbidden transformation is rejected as a whole")
                }
                val rows = published(service, plan); assertEquals(2, rows.size)
                rows.forEach { row ->
                    assertObservedPublication(evidence(definition), row)
                    assertTrue(row.publication.operationalFaults.isEmpty(), "Policy cannot rewrite the raw operational publication")
                    assertEquals(canonical(f.partial(f.typedAttempts.single { it.workKey == row.execution.workKey }.effective)), row.rawJson)
                }
                assertEquals(2, provider.calls.get()); assertEquals(1, f.resolutionCalls.getValue(definition.request.planRef).get())
                assertGapVector(definition, service.planStatus(client.client, plan.planId)); assertDeclarations(service, definition, plan)
                assertGapEvents(f.events(service, client), definition, plan)
                f.deliver(service, client); assertDeliveredButBlocked(service, client, plan)
            }
        }
    }
}

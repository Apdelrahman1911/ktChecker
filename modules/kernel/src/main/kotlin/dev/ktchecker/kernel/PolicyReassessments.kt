package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.sql.Connection

/** Existing immutable events are the durable view/index. No DDL, new work, raw-cache entry or receipt. */
internal class PolicyReassessments(
    private val service: KernelService,
    private val authority: PolicyReassessmentAuthority,
) {
    private val ledger get() = service.ledger
    private val evaluator = identity("evaluator", authority.evaluatorIdentity)
    private val issuer = identity("issuer", authority.issuerIdentity)

    internal data class Selection(
        val byReview: Map<String, PolicyReassessmentView> = emptyMap(),
        val active: PolicyReassessmentView? = null,
        val reasons: List<String> = emptyList(),
    )
    private data class StoredEvent(val id: String, val client: String, val sequence: Long, val review: String,
        val patch: String, val snapshot: String, val generation: Long, val required: Boolean,
        val digest: String, val payload: String)
    private data class Admission(val binding: ClientBinding, val subject: CertifiedPolicyReview, val epoch: Long, val nextEvent: Long)

    fun reassess(credential: ClientCredential, request: PolicyReassessmentRequest): PolicyReassessmentView {
        service.ensureOpen()
        val frozen = detach(request)
        identifier(frozen.reviewId, "review ID")
        identifier(frozen.idempotencyKey, "policy reassessment request ID")
        if (!Digests.valid(frozen.expectedPolicyDigest)) fail("invalid_policy_digest", "Expected full policy digest is required")
        val admission = ledger.read { c ->
            val binding = service.authenticate(c, credential)
            requireGeneration(binding, frozen)
            prior(c, binding, frozen)?.let { return@read it }
            preflight(c, binding)
            val subject = resolve(c, binding, frozen.reviewId)
            requireChangedPolicy(c, binding, subject, frozen.expectedPolicyDigest)
            Admission(binding, subject, epoch(c, binding), nextEvent(c, binding))
        }
        if (admission is PolicyReassessmentView) return admission
        admission as Admission
        // No accepted pending state exists: all preparation/archive work precedes atomic acceptance.
        val prepared = authority.prepare(detach(admission.binding), detach(admission.subject), frozen.expectedPolicyDigest)
        val capture = checkedCapture(detach(prepared.capture), admission.subject)
        if (capture.currentPolicyDigest != frozen.expectedPolicyDigest) {
            fail("policy_reassessment_capture_mismatch", "Prepared capture differs from the requested current policy")
        }
        return fenced(prepared) { supplied ->
            val check = detach(supplied)
            ledger.transaction { c ->
                service.ensureOpen()
                val binding = service.authenticate(c, credential)
                requireGeneration(binding, frozen)
                prior(c, binding, frozen)?.let { return@transaction it }
                preflight(c, binding)
                val subject = resolve(c, binding, frozen.reviewId)
                if (binding != admission.binding || epoch(c, binding) != admission.epoch ||
                    nextEvent(c, binding) != admission.nextEvent || subject != admission.subject) {
                    fail("policy_reassessment_lineage_changed", "Original execution or client frontier changed during preparation")
                }
                if (detach(prepared.capture) != capture) fail("policy_reassessment_capture_changed", "Prepared capture is not immutable")
                requireChangedPolicy(c, binding, subject, capture.currentPolicyDigest)
                requireCurrent(subject, capture, check)
                val transformed = try { detach(prepared.apply(detach(subject.raw))) }
                catch (_: Exception) { fail("policy_reassessment_evaluator_failed", "Pure policy reassessment failed before acceptance") }
                catch (_: AssertionError) { fail("policy_reassessment_evaluator_failed", "Pure policy reassessment failed before acceptance") }
                requireDispositionOnly(subject.raw, transformed)
                if (canonical(transformed).toByteArray().size > service.config.maxResultBytes) {
                    fail("policy_reassessment_result_limit", "Reassessed result exceeds its byte bound")
                }
                if (detach(prepared.capture) != capture) fail("policy_reassessment_capture_changed", "Evaluator changed its captured identity")
                val sequence = admission.nextEvent
                val auditReasons = auditReasons(subject, check)
                val unsigned = PolicyReassessmentView(eventId(binding, frozen.idempotencyKey), frozen, binding.clientId,
                    subject.patchId, subject.input.snapshot.snapshotId, sequence, binding.generation,
                    reference(binding, subject), capture, evaluator, issuer, transformed,
                    auditReasons.isNotEmpty(), auditReasons, "")
                val view = unsigned.copy(certificateDigest = certificate(unsigned))
                val payload = canonical(view)
                val payloadBytes = payload.toByteArray().size.toLong()
                if (payloadBytes > service.config.maxEventPageBytes) {
                    fail("policy_reassessment_event_limit", "The required policy event exceeds its delivery byte bound")
                }
                if (historyBytes(c, binding) + payloadBytes > historyByteLimit()) {
                    fail("policy_reassessment_history_limit", "Retained policy history exceeds the bounded read budget; no history was removed")
                }
                // Check close/expiry after both the DB wait and pure evaluation, immediately before publication.
                service.ensureOpen()
                requireCurrent(subject, capture, check)
                c.execute("""INSERT INTO events(event_id,client_id,sequence,review_id,patch_id,snapshot_id,kind,
                    delivery_required,payload_digest,payload,generation,created_at) VALUES(?,?,?,?,?,?,?,1,?,?,?,?)""",
                    view.viewId, binding.clientId, sequence, subject.reviewId, subject.patchId, subject.input.snapshot.snapshotId,
                    EVENT_KIND, Digests.sha256(payload), payload, binding.generation, System.currentTimeMillis())
                if (c.execute("""UPDATE clients SET next_event=next_event+1,epoch=epoch+1
                    WHERE client_id=? AND epoch=? AND next_event=? AND gate_state='open'""",
                    binding.clientId, admission.epoch, sequence) != 1) {
                    fail("policy_reassessment_lineage_changed", "Client epoch/event publication CAS did not hold")
                }
                view
            }
        }
    }

    /** Historical read only: no reevaluation, current capture substitution, observation or permission. */
    fun status(credential: ClientCredential, idempotencyKey: String): PolicyReassessmentView {
        service.ensureOpen()
        identifier(idempotencyKey, "policy reassessment request ID")
        return ledger.read { c ->
            val binding = service.authenticate(c, credential)
            val stored = stored(c, binding, eventId(binding, idempotencyKey))
                ?: fail("policy_reassessment_not_found", "No policy reassessment belongs to this client/request")
            validate(c, binding, stored)
        }
    }

    /** Pure ledger validation only. Last per-review record wins; an invalid record is NEVER silently skipped. */
    internal fun selection(c: Connection, binding: ClientBinding, latest: AnalysisInput?): Selection {
        if (historyBytes(c, binding) > historyByteLimit()) return Selection(reasons = listOf("policy_reassessment_history_limit"))
        val events = c.query("SELECT * FROM events WHERE client_id=? AND kind=? ORDER BY sequence", binding.clientId, EVENT_KIND) { row ->
            StoredEvent(row.getString("event_id"), row.getString("client_id"), row.getLong("sequence"), row.getString("review_id"),
                row.getString("patch_id"), row.getString("snapshot_id"), row.getLong("generation"), row.getInt("delivery_required") == 1,
                row.getString("payload_digest"), row.getString("payload"))
        }
        if (events.isEmpty()) return Selection()
        if (unsupportedLineage(c, binding)) return Selection(reasons = listOf("policy_reassessment_plan_lineage_unsupported"))
        val views = linkedMapOf<String, PolicyReassessmentView>()
        val reasons = mutableListOf<String>()
        for (event in events) {
            try {
                val view = validate(c, binding, event)
                views[view.reviewId] = view
            } catch (_: KernelException) {
                reasons += "policy_reassessment_record_invalid:${event.id}"
            }
        }
        val latestReview = c.one("SELECT review_id FROM reviews WHERE client_id=? ORDER BY sequence DESC LIMIT 1", binding.clientId) { it.getString(1) }
        val active = latestReview?.let { views[it] }?.takeIf { it.certified.inputKey == latest?.analysisKey() }
        return Selection(views, active, reasons.distinct().sorted())
    }

    /** Restores exact archived capture outside DB; this never reruns apply on an old view. */
    internal fun <T> withCurrent(view: PolicyReassessmentView, action: (SnapshotCheck) -> T): T {
        val restored = try {
            val original = ledger.read { c ->
                val binding = c.one("SELECT binding_json FROM clients WHERE client_id=?", view.clientId) {
                    ContractJson.decodeFromString<ClientBinding>(it.getString(1))
                } ?: fail("policy_reassessment_client_missing", "Original client is unavailable")
                val event = stored(c, binding, view.viewId) ?: fail("policy_reassessment_not_found", "Original policy event is unavailable")
                if (validate(c, binding, event) != view) fail("policy_reassessment_record_changed", "Exact policy view changed")
                binding to resolve(c, binding, view.reviewId)
            }
            authority.restore(detach(original.first), detach(original.second), detach(view.capture)).also {
                if (checkedCapture(detach(it.capture), original.second) != view.capture) {
                    fail("policy_reassessment_capture_changed", "Restoration did not recover the exact recorded capture")
                }
            }
        } catch (_: Exception) {
            return action(SnapshotCheck(false, reasons = listOf("policy_reassessment_restore_unavailable")))
        } catch (_: AssertionError) {
            return action(SnapshotCheck(false, reasons = listOf("policy_reassessment_restore_unavailable")))
        }
        // Never catch action: it can contain a DB transaction or one nonreplayable guarded effect.
        return fenced(restored) { supplied ->
            val check = detach(supplied)
            action(if (detach(restored.capture) == view.capture) check else
                check.copy(current = false, reasons = check.reasons + "policy_reassessment_capture_changed"))
        }
    }

    private fun <T> fenced(prepared: PreparedPolicyReassessment, action: (SnapshotCheck) -> T): T {
        var called = false
        val result = prepared.withCurrent { check ->
            if (called) fail("policy_reassessment_fence_reentered", "The currentness callback must run exactly once")
            called = true
            action(check)
        }
        if (!called) fail("policy_reassessment_fence_missing", "The trusted currentness callback did not run")
        return result
    }

    private fun historyByteLimit(): Long = service.config.maxEventPageBytes.toLong() * 4
    private fun historyBytes(c: Connection, binding: ClientBinding): Long = c.count(
        "SELECT COALESCE(SUM(length(CAST(payload AS BLOB))),0) FROM events WHERE client_id=? AND kind=?", binding.clientId, EVENT_KIND)

    private fun preflight(c: Connection, binding: ClientBinding) {
        if (unsupportedLineage(c, binding)) fail("policy_reassessment_plan_lineage_unsupported", "Plan/routed/produced reassessment is not supported")
        if (c.one("SELECT gate_state FROM clients WHERE client_id=?", binding.clientId) { it.getString(1) } != "open") {
            fail("lineage_finalized", "A final action was already claimed for this client generation")
        }
        if (c.count("SELECT COUNT(*) FROM events WHERE kind=?", EVENT_KIND) >= service.config.maxRetainedReviews) {
            fail("policy_reassessment_ledger_full", "Retained policy view quota is full; no history was removed")
        }
    }

    private fun requireChangedPolicy(c: Connection, binding: ClientBinding, subject: CertifiedPolicyReview, requested: String) {
        val last = selection(c, binding, subject.input)
        if (last.reasons.isNotEmpty()) fail("policy_reassessment_history_invalid", "Retained policy history is not valid")
        val priorPolicy = last.byReview[subject.reviewId]?.capture?.currentPolicyDigest ?: subject.input.context.configurationDigest
        if (requested == priorPolicy) {
            fail("policy_reassessment_policy_unchanged", "A new view requires a different policy from the preceding view")
        }
    }

    private fun unsupportedLineage(c: Connection, binding: ClientBinding): Boolean =
        c.count("SELECT COUNT(*) FROM parent_plans WHERE client_id=?", binding.clientId) != 0L ||
            c.count("SELECT COUNT(*) FROM reviews WHERE client_id=? AND kind<>'ordinary'", binding.clientId) != 0L ||
            c.count("SELECT routed_obligations FROM clients WHERE client_id=?", binding.clientId) != 0L ||
            c.count("SELECT COUNT(*) FROM routing_members WHERE client_id=?", binding.clientId) != 0L

    private fun epoch(c: Connection, binding: ClientBinding): Long = c.count("SELECT epoch FROM clients WHERE client_id=?", binding.clientId)
    private fun nextEvent(c: Connection, binding: ClientBinding): Long = c.count("SELECT next_event FROM clients WHERE client_id=?", binding.clientId)
    private fun requireGeneration(binding: ClientBinding, request: PolicyReassessmentRequest) {
        if (request.generation != binding.generation) fail("policy_reassessment_generation_mismatch", "Request belongs to another client generation")
    }

    private fun prior(c: Connection, binding: ClientBinding, request: PolicyReassessmentRequest): PolicyReassessmentView? {
        val event = stored(c, binding, eventId(binding, request.idempotencyKey)) ?: return null
        val view = validate(c, binding, event)
        if (view.request != request) fail("policy_reassessment_idempotency_conflict", "Request ID already names a different review/policy pair")
        return view
    }

    private fun stored(c: Connection, binding: ClientBinding, id: String): StoredEvent? =
        c.one("SELECT * FROM events WHERE client_id=? AND event_id=? AND kind=?", binding.clientId, id, EVENT_KIND) { row ->
            StoredEvent(row.getString("event_id"), row.getString("client_id"), row.getLong("sequence"), row.getString("review_id"),
                row.getString("patch_id"), row.getString("snapshot_id"), row.getLong("generation"), row.getInt("delivery_required") == 1,
                row.getString("payload_digest"), row.getString("payload"))
        }

    /** Reconstruct the exact original from ledger tables; no callback or request provides these values. */
    private fun resolve(c: Connection, binding: ClientBinding, reviewId: String): CertifiedPolicyReview {
        val subject = c.one("""SELECT r.*,w.provider_key,w.input_json work_input,w.result_json,w.state work_state,
            wc.input_key,wc.effective_key,wc.result_digest,wc.provider_key certificate_provider,wc.certificate_id
            FROM reviews r JOIN work w ON w.work_key=r.work_key LEFT JOIN work_certificates wc ON wc.work_key=r.work_key
            WHERE r.review_id=? AND r.client_id=? AND r.kind='ordinary'""", reviewId, binding.clientId) { row ->
            val status = service.readStatus(c, reviewId, binding.clientId)
            if (status.state != "complete" || status.cancelled || row.getString("work_state") != "complete") {
                fail("policy_reassessment_original_incomplete", "Original review must retain a complete noncancelled execution")
            }
            val input = ContractJson.decodeFromString<AnalysisInput>(row.getString("input_json"))
            val rawJson = row.getString("result_json") ?: fail("policy_reassessment_certificate_invalid", "Original raw result is unavailable")
            val effectiveJson = row.getString("effective_result_json") ?: fail("policy_reassessment_certificate_invalid", "Original effective result is unavailable")
            val raw = ContractJson.decodeFromString<ProviderResult>(rawJson)
            val effective = ContractJson.decodeFromString<ProviderResult>(effectiveJson)
            if (!raw.completeFor(input)) fail("policy_reassessment_original_incomplete", "Original raw coverage is not complete for its unchanged input")
            val inputKey = row.getString("input_key")
            val effectiveKey = row.getString("effective_key")
            val resultDigest = row.getString("result_digest")
            val certificateProvider = row.getString("certificate_provider")
            val workCertificate = row.getString("certificate_id")
            val policyEncoded = row.getString("policy_certificate")
            val policy = PolicyCertificate.decode(policyEncoded)
            val expectedWork = Digests.sha256(canonical(listOf(inputKey, effectiveKey, resultDigest, certificateProvider)))
            if (inputKey != input.analysisKey() || effectiveKey == null || !Digests.valid(effectiveKey) ||
                row.getString("work_input") != canonical(input) || row.getString("provider_key") != service.providerKey ||
                row.getString("work_key") != Digests.sha256(canonical(listOf(service.providerKey, input.analysisKey()))) ||
                certificateProvider != service.providerKey || resultDigest != Digests.sha256(rawJson) || workCertificate != expectedWork ||
                rawJson != canonical(raw) || effectiveJson != canonical(effective) || policy?.state != PolicyCertificate.EVALUATED ||
                policy?.evaluatorIdentity != service.policyEvaluatorIdentity || policy?.digest != PolicyCertificate.effectiveDigest(
                    service.policyEvaluatorIdentity, binding, input, Digests.sha256(rawJson), Digests.sha256(effectiveJson))) {
                fail("policy_reassessment_certificate_invalid", "Original work/input/raw/effective certificates do not match")
            }
            CertifiedPolicyReview(reviewId, status.patchId, status.sequence, input, row.getString("work_key"), service.providerKey,
                inputKey, effectiveKey, workCertificate, policyEncoded, raw, effective)
        } ?: fail("policy_reassessment_review_not_found", "An ordinary certified review is not available to this client")
        return subject
    }

    private fun checkedCapture(capture: PolicyReassessmentCapture, subject: CertifiedPolicyReview): PolicyReassessmentCapture {
        if (capture.format != PolicyReassessmentCapture.FORMAT || capture.originalPolicyDigest != subject.input.context.configurationDigest ||
            listOf(capture.originalPolicyDigest, capture.currentPolicyDigest, capture.originalCaptureId,
                capture.currentCaptureId, capture.analysisAuthorityDigest).any { !Digests.valid(it) }) {
            fail("policy_reassessment_capture_invalid", "Exact bounded policy/analysis capture references are required")
        }
        return capture
    }

    private fun requireCurrent(subject: CertifiedPolicyReview, capture: PolicyReassessmentCapture, check: SnapshotCheck) {
        val input = subject.input
        if (!check.current || check.snapshotId != input.snapshot.snapshotId || check.configurationDigest != capture.currentPolicyDigest ||
            check.modelDigest != input.context.modelDigest || check.dependencyDigest != input.context.dependencyDigest ||
            check.toolchainDigest != input.context.toolchainDigest || check.sourceEpoch == null || check.sourceEpoch < 0 ||
            check.analysisEpoch.isNullOrBlank() || listOf(input.context.modelDigest, input.context.dependencyDigest,
                input.context.toolchainDigest).any { it == null || !Digests.valid(it) } ||
            check.validUntilEpochMillis?.let { System.currentTimeMillis() >= it } == true) {
            fail("policy_reassessment_currentness_unavailable", "The exact current full policy and original source analysis fence is unavailable")
        }
    }

    private fun auditReasons(subject: CertifiedPolicyReview, check: SnapshotCheck): List<String> = buildList {
        if (!check.authoritative) add("current_source_configuration_authority_unavailable")
        if (!subject.input.context.fullProject) add("full_project_scope_unproven")
        if (subject.input.snapshot.scopeExclusions.isNotEmpty() || subject.input.snapshot.unknownInputs.isNotEmpty()) add("source_scope_incomplete")
        addAll(check.reasons)
    }.distinct().sorted()

    private fun requireDispositionOnly(raw: ProviderResult, proposed: ProviderResult) {
        val restored = proposed.copy(findings = proposed.findings.map { it.copy(disposition = "open") })
        if (raw.findings.any { it.disposition != "open" } || canonical(restored) != canonical(raw) ||
            proposed.findings.any { it.disposition !in setOf("open", "accepted", "suppressed", "baselined") ||
                (it.disposition != "open" && service.currentExecutionFailure(it)) }) {
            fail("policy_reassessment_changed_protected_evidence", "Only supported dispositions of the exact full raw result may change")
        }
    }

    private fun reference(binding: ClientBinding, subject: CertifiedPolicyReview) = PolicyReassessmentReference(
        subject.reviewSequence, subject.input.analysisKey(), subject.workKey, subject.effectiveInputKey, subject.workCertificateId,
        Digests.sha256(subject.originalPolicyCertificate), Digests.sha256(canonical(subject.raw)),
        Digests.sha256(canonical(subject.originalEffective)), Digests.sha256(canonical(listOf(canonical(binding), canonical(subject)))))

    private fun validate(c: Connection, binding: ClientBinding, event: StoredEvent): PolicyReassessmentView {
        if (event.payload.toByteArray().size > service.config.maxEventPageBytes) {
            fail("policy_reassessment_record_invalid", "Policy event exceeds its bounded format")
        }
        val view = try { ContractJson.decodeFromString<PolicyReassessmentView>(event.payload) }
            catch (_: Exception) { fail("policy_reassessment_record_invalid", "Policy event format is unavailable") }
        if (view.format != PolicyReassessmentView.FORMAT || view.state != EVENT_KIND || canonical(view) != event.payload ||
            !event.required ||
            event.digest != Digests.sha256(event.payload) || event.client != binding.clientId || event.client != view.clientId ||
            event.generation != binding.generation || view.generation != binding.generation || view.request.generation != binding.generation ||
            event.id != view.viewId || view.viewId != eventId(binding, view.request.idempotencyKey) || event.sequence != view.sequence ||
            event.review != view.reviewId || view.reviewId != view.request.reviewId || event.patch != view.patchId ||
            event.snapshot != view.sourceSnapshotId || view.evaluatorIdentity != evaluator || view.issuerIdentity != issuer ||
            view.request.expectedPolicyDigest != view.capture.currentPolicyDigest || view.certificateDigest != certificate(view)) {
            fail("policy_reassessment_record_invalid", "Policy event/certificate/client/pair identity is invalid")
        }
        val subject = resolve(c, binding, view.reviewId)
        checkedCapture(view.capture, subject)
        if (view.certified != reference(binding, subject) || view.patchId != subject.patchId || view.sourceSnapshotId != subject.input.snapshot.snapshotId ||
            (!view.auditOnly && (!subject.input.context.fullProject || subject.input.snapshot.scopeExclusions.isNotEmpty() ||
                subject.input.snapshot.unknownInputs.isNotEmpty() || view.reasons.isNotEmpty()))) {
            fail("policy_reassessment_certificate_invalid", "Policy relation does not bind the original certified subject")
        }
        requireDispositionOnly(subject.raw, view.result)
        return view
    }

    private fun eventId(binding: ClientBinding, key: String): String = "policy-" + Digests.sha256(canonical(listOf(
        EVENT_KIND, canonical(binding), key)))
    private fun certificate(view: PolicyReassessmentView): String = Digests.sha256(canonical(view.copy(certificateDigest = "")))
    private fun identity(kind: String, declared: String): String {
        identifier(declared, "policy reassessment $kind identity")
        return Digests.sha256(canonical(listOf("ktchecker/policy-reassessment-$kind/1", declared)))
    }
    private inline fun <reified T> detach(value: T): T = ContractJson.decodeFromString(canonical(value))

    companion object { const val EVENT_KIND = "policy_reassessed" }
}

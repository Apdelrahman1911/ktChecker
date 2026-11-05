package dev.ktchecker.kernel

import dev.ktchecker.contracts.AnalysisInput
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private typealias Audit = IntegratedCompletionAudit

/**
 * Optional owner-installed completion boundary. Route admission and the legacy scalar gate are
 * unchanged. A final pass must be ONE full routed integration batch over the complete historical
 * route closure. Nothing in this class clears routed_obligations or deletes an accepted record.
 *
 * All owner callbacks, including the irreversible final effect, execute outside DB transactions.
 * Persistent per-client gate_state, not the audit metadata, is the one-shot admission authority.
 * There is deliberately no auto-recovery, reset, replay or conversion of unknown into complete.
 */
class IntegratedCompletionBarrier(
    private val service: KernelService,
    private val authority: IntegratedCompletionAuthority = IntegratedCompletionAuthority.DENY,
    private val limits: IntegratedCompletionLimits = IntegratedCompletionLimits(),
) {
    private val ledger get() = service.ledger
    private val auditCodec = IntegratedCompletionAuditCodec(limits)

    private class Grant(val value: AuthorizedIntegratedCompletion, val actionIdentity: String)
    private data class MemberFacts(
        val member: AuthorizedIntegratedMember,
        val plan: ParentPlans.Stored,
        val frontier: IntegratedClientFrontier,
        val obligations: List<IntegratedPatchObligation>,
        val reasons: List<String>,
        val routingId: String,
    )
    private data class Facts(val request: IntegratedFenceRequest, val members: List<MemberFacts>, val reasons: List<String>)
    private data class EventFacts(val id: String, val sequence: Long, val digest: String, val required: Boolean, val valid: Boolean)

    fun prepare(credential: ClientCredential, completionRef: String): IntegratedCompletionCut {
        service.ensureOpen()
        identifier(completionRef, "joint completion reference")
        val origin = ledger.read { service.authenticate(it, credential) }
        val grant = resolve(origin, completionRef)
        authenticateMembers(credential, grant)
        verifyReadiness(grant)
        val facts = ledger.read { readFacts(it, credential, grant) }
        return withFence(grant, facts) { check ->
            ledger.transaction { c ->
                service.ensureOpen()
                val reasons = facts.reasons + currentReasons(c, credential, grant, facts) + fenceReasons(facts, check) +
                    deliveryReasons(c, facts.request.frontiers)
                if (c.count("SELECT COUNT(*) FROM metadata WHERE key GLOB ? OR key GLOB ?",
                        "${IntegratedCompletionAuditCodec.LEGACY_KEY_PREFIX}*", "${IntegratedCompletionAuditCodec.CURRENT_KEY_PREFIX}*") >= limits.maxCuts) {
                    fail("joint_cut_limit", "Joint audit capacity is full; no retained cut or obligation was removed")
                }
                val cut = IntegratedCompletionCut("${IntegratedCompletionAuditCodec.CURRENT_ID_PREFIX}${UUID.randomUUID()}", completionRef, facts.request.scope,
                    facts.request.scopeDigest, reasons.isEmpty(), reasons.distinct().sorted())
                val audit = Audit(IntegratedCompletionAuditCodec.CURRENT_FORMAT, cut, grant.value.registrationDigest, grant.actionIdentity, facts.request, check)
                c.execute("INSERT INTO metadata(key,value) VALUES(?,?)", key(cut.cutId), encode(audit))
                cut
            }
        }
    }

    /**
     * No model-facing callback parameter. Only the retained owner action can run. A successful
     * return reports this one guarded effect in this declared scope, not permission for a later
     * final answer. Historical complete/claimed/unknown cuts are NEVER re-executed.
     */
    fun complete(credential: ClientCredential, cutId: String): IntegratedCompletionResult {
        service.ensureOpen()
        val saved = ledger.read { c -> load(c, service.authenticate(c, credential), cutId) }
        // Every legacy state is audit-only, even prepared. Do not migrate or resolve its owner
        // capability, and never turn a historical DTO into a new claim or replay permission.
        if (saved.format == IntegratedCompletionAuditCodec.LEGACY_FORMAT) return result(saved, false, "joint_legacy_audit_only_no_permission")
        if (saved.state != "prepared") return result(saved, false, "joint_cut_not_replayable")
        if (!saved.cut.ready) return result(saved, false, "joint_cut_was_not_ready")
        val grant = resolve(saved.cut.scope.origin, saved.cut.completionRef)
        if (grant.value.registrationDigest != saved.registrationDigest || grant.actionIdentity != saved.actionIdentity) {
            return result(saved, false, "joint_owner_authority_changed")
        }
        authenticateMembers(credential, grant)
        verifyReadiness(grant)
        val facts = ledger.read { readFacts(it, credential, grant) }
        if (facts.request != saved.request) return result(saved, false, "joint_frontier_or_declared_scope_changed")
        var claimed = false
        return try {
            withFence(grant, facts) { check ->
                val reasons = ledger.transaction { c ->
                    service.ensureOpen()
                    val origin = service.authenticate(c, credential)
                    val fresh = load(c, origin, cutId)
                    val issues = mutableListOf<String>()
                    if (fresh != saved || fresh.state != "prepared") issues += "joint_cut_changed_or_consumed"
                    if (check != saved.check) issues += "joint_authority_fence_changed"
                    issues += facts.reasons
                    issues += currentReasons(c, credential, grant, facts)
                    issues += deliveryReasons(c, facts.request.frontiers)
                    // Expiry is checked AFTER writer acquisition and all ledger/receipt work.
                    issues += fenceReasons(facts, check)
                    if (issues.isEmpty()) {
                        facts.members.forEach { member ->
                            val f = member.frontier
                            if (c.execute("""UPDATE clients SET gate_state='action_claimed',active_cut_id=?
                                WHERE client_id=? AND gate_state='open' AND epoch=? AND next_review=? AND next_event=?""",
                                    cutId, f.clientId, f.lineageEpoch, f.reviewFrontier + 1, f.eventFrontier + 1) != 1) {
                                fail("joint_claim_conflict", "The entire joint claim rolled back; no member prefix was authorized")
                            }
                        }
                        replace(c, saved.copy(state = "action_claimed"))
                    }
                    issues.distinct().sorted()
                }
                if (reasons.isNotEmpty()) return@withFence result(saved, false, reasons)
                claimed = true // The all-member intent is already durably committed.
                if (service.isStopped() || Thread.currentThread().isInterrupted || fenceReasons(facts, check).isNotEmpty()) {
                    markUnknown(saved)
                    return@withFence result(saved.copy(state = "outcome_unknown"), false, "joint_authority_unavailable_after_claim")
                }

                // NO DB transaction is open over this callback. Terminal/evaluated historical
                // work plus all-member gate_state freezes admission, cancellation and publication.
                // The actual composite source/config/integration fence is still held here.
                val started = System.nanoTime()
                val permit = IntegratedCompletionPermit(cutId, detach(saved.cut.scope), saved.cut.scopeDigest)
                val receipt = grant.value.action.perform(permit)
                val elapsed = System.nanoTime() - started
                val receiptValid = receipt != null && receipt.cutId == cutId && receipt.scopeDigest == saved.cut.scopeDigest &&
                    Digests.valid(receipt.receiptDigest)
                if (!receiptValid || elapsed < 0 || elapsed > limits.maxActionMillis * 1_000_000 ||
                    service.isStopped() || Thread.currentThread().isInterrupted || fenceReasons(facts, check).isNotEmpty()) {
                    markUnknown(saved)
                    return@withFence result(saved.copy(state = "outcome_unknown"), false, "joint_guarded_action_outcome_unknown")
                }
                val detachedReceipt = detach(receipt!!)
                ledger.transaction { c ->
                    val fresh = load(c, service.authenticate(c, credential), cutId)
                    val claims = facts.members.all { member ->
                        val f = member.frontier
                        c.count("""SELECT COUNT(*) FROM clients WHERE client_id=? AND gate_state='action_claimed'
                            AND active_cut_id=? AND epoch=? AND next_review=? AND next_event=?""",
                            f.clientId, cutId, f.lineageEpoch, f.reviewFrontier + 1, f.eventFrontier + 1) == 1L
                    }
                    if (fresh.state != "action_claimed" || fresh.copy(state = "prepared") != saved || !claims ||
                        fenceReasons(facts, check).isNotEmpty()) {
                        fail("joint_final_claim_changed", "The guarded effect cannot be recorded as complete")
                    }
                    facts.members.forEach { member ->
                        if (c.execute("UPDATE clients SET gate_state='complete' WHERE client_id=? AND active_cut_id=? AND gate_state='action_claimed'",
                                member.frontier.clientId, cutId) != 1) fail("joint_final_claim_changed", "Joint completion was not recorded")
                    }
                    replace(c, saved.copy(state = "complete", receipt = detachedReceipt))
                }
                result(saved.copy(state = "complete"), true, emptyList())
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            failedCompletion(saved, "joint_callback_interrupted")
        } catch (_: Exception) {
            // An uncertain commit still leaves persistent action_claimed; never reset or retry it.
            failedCompletion(saved,
                if (claimed) "joint_guarded_action_outcome_unknown" else "joint_completion_callback_failed")
        } catch (_: AssertionError) {
            failedCompletion(saved, "joint_completion_callback_failed")
        }
    }

    /** Audit only: even a historical complete result does not authorize another send or replay. */
    fun status(credential: ClientCredential, cutId: String): IntegratedCompletionResult {
        service.ensureOpen()
        return ledger.read { c ->
            val saved = load(c, service.authenticate(c, credential), cutId)
            result(saved, false, when {
                saved.format == IntegratedCompletionAuditCodec.LEGACY_FORMAT -> "joint_legacy_audit_only_no_permission"
                saved.state == "action_claimed" -> "joint_claim_outcome_unknown_no_replay"
                else -> "joint_audit_only_no_permission"
            })
        }
    }

    private fun resolve(origin: ClientBinding, reference: String): Grant {
        val value = authority.authorize(origin, reference)
            ?: fail("joint_completion_authority_unavailable", "No live owner joint completion authority is registered")
        if (value.origin != origin || value.completionRef != reference || value.members.size !in 2..limits.maxMembers ||
            value.members.map { it.binding.clientId }.distinct().size != value.members.size ||
            value.members.none { it.binding == origin } || value.members.none { it.binding.clientId == value.integrationClientId }) {
            fail("joint_owner_scope_mismatch", "Owner authority does not cover the exact initiating binding and complete distinct member set")
        }
        identifier(value.integrationClientId, "integration client")
        val actionIdentity = value.action.identity // Owner code: outside every DB transaction.
        if (!Digests.valid(actionIdentity) || !Digests.valid(value.registrationDigest) || !Digests.valid(value.catalogDigest) ||
            value.requiredRuleIds != value.requiredRuleIds.distinct().sorted()) {
            fail("joint_owner_identity_invalid", "An exact owner registration/action/catalog identity is required")
        }
        value.requiredRuleIds.forEach { identifier(it, "joint required rule") }
        value.members.forEach { member ->
            if (member.binding.ownerId != origin.ownerId || member.binding.workspaceId != origin.workspaceId ||
                member.adapter.clientId != member.binding.clientId || member.adapter.token.length !in 43..128 ||
                !Digests.valid(member.planDigest) || !Digests.valid(member.readinessIdentity)) {
                fail("joint_member_identity_invalid", "Every recipient requires its own exact same-owner/workspace live plan and capability")
            }
            listOf(member.request.patchId, member.request.idempotencyKey, member.request.planRef).forEach { identifier(it, "joint plan request") }
        }
        return Grant(value, actionIdentity)
    }

    private fun authenticateMembers(credential: ClientCredential, grant: Grant) = ledger.read { c ->
        if (service.authenticate(c, credential) != grant.value.origin) fail("joint_origin_mismatch", "The initiating binding changed")
        grant.value.members.forEach { member ->
            if (service.authenticate(c, member.adapter) != member.binding) fail("joint_member_binding_mismatch", "A recipient capability belongs to another binding")
        }
    }

    private fun verifyReadiness(grant: Grant) {
        grant.value.members.forEach { member ->
            if (member.verifyForCompletion() != member.readinessIdentity) {
                fail("joint_member_authority_changed", "A retained source/policy/execution preparation is unavailable or changed")
            }
        }
    }

    /** Bounded graph expansion BEFORE loading any route JSON, parent, result or event payload. */
    private fun closure(c: Connection, origin: String): Pair<List<String>, List<String>> {
        val members = linkedSetOf(origin)
        val routes = linkedSetOf<String>()
        val todo = ArrayDeque<String>().apply { add(origin) }
        while (todo.isNotEmpty()) {
            val client = todo.removeFirst()
            val linked = c.query("""SELECT routing_id FROM routing_members WHERE client_id=?
                UNION SELECT routing_id FROM parent_plans WHERE client_id=? AND routing_id IS NOT NULL
                UNION SELECT routing_id FROM routing_batches WHERE origin_client_id=? ORDER BY routing_id LIMIT ?""",
                client, client, client, limits.maxRoutes + 1) { it.getString(1) }
            if (linked.size > limits.maxRoutes) limit()
            linked.forEach { route ->
                if (routes.add(route)) {
                    if (routes.size > limits.maxRoutes) limit()
                    val recipients = c.query("""SELECT client_id FROM routing_members WHERE routing_id=?
                        UNION SELECT client_id FROM parent_plans WHERE routing_id=?
                        UNION SELECT origin_client_id FROM routing_batches WHERE routing_id=? ORDER BY client_id LIMIT ?""",
                        route, route, route, limits.maxMembers + 1) { it.getString(1) }
                    if (recipients.size !in 2..limits.maxMembers) fail("joint_route_unavailable", "A whole historical route is missing or oversized")
                    recipients.forEach { id ->
                        if (members.add(id)) {
                            if (members.size > limits.maxMembers) limit()
                            todo.add(id)
                        }
                    }
                }
            }
        }
        if (members.size < 2 || routes.isEmpty()) fail("joint_routed_scope_missing", "Joint completion requires a declared routed integration scope")
        return members.sorted() to routes.sorted()
    }

    private fun boundHistory(c: Connection, members: List<String>, routes: List<String>) {
        var reviews = 0L; var parents = 0L; var events = 0L; var bytes = 0L
        fun charge(value: Long) {
            if (value < 0 || value > limits.maxHistoryBytes - bytes) limit()
            bytes += value
        }
        members.forEach { client ->
            reviews += c.count("SELECT COUNT(*) FROM reviews WHERE client_id=?", client)
            parents += c.count("SELECT COUNT(*) FROM parent_plans WHERE client_id=?", client)
            events += c.count("SELECT COUNT(*) FROM events WHERE client_id=?", client)
            if (reviews > limits.maxReviews || parents > limits.maxParents || events > limits.maxEvents) limit()
            // D remains chargeable before a P/work row exists. EACH nullable operand is zeroed:
            // COALESCE(SUM(a+b),0) alone silently drops an entire declaration when b is NULL.
            charge(c.count("""SELECT COALESCE(SUM(COALESCE(length(CAST(r.input_json AS BLOB)),0)+
                COALESCE(length(CAST(r.request_json AS BLOB)),0)+COALESCE(length(CAST(r.execution_input_json AS BLOB)),0)+
                COALESCE(length(CAST(w.frozen_json AS BLOB)),0)+COALESCE(length(CAST(w.result_json AS BLOB)),0)+
                COALESCE(length(CAST(r.effective_result_json AS BLOB)),0)+COALESCE(length(CAST(w.validation_json AS BLOB)),0)+
                COALESCE(length(CAST(w.progress_faults_json AS BLOB)),0)+COALESCE(length(CAST(r.policy_validation_json AS BLOB)),0)+
                COALESCE(length(CAST(r.policy_certificate AS BLOB)),0)+COALESCE(length(CAST(r.execution_certificate AS BLOB)),0)+
                COALESCE(length(CAST(r.resolution_reasons_json AS BLOB)),0)),0)
                FROM review_executions r LEFT JOIN work w ON w.work_key=r.work_key WHERE r.client_id=?""", client))
            charge(c.count("SELECT COALESCE(SUM(length(CAST(definition_json AS BLOB))),0) FROM parent_plans WHERE client_id=?", client))
            // Complete retained D archives, one-use claims (including failed/unknown resolution),
            // append-only relations and operational publications cannot evade the history bound.
            charge(c.count("""SELECT COALESCE(SUM(length(CAST(a.archives_json AS BLOB))+length(CAST(a.admission_digest AS BLOB))+
                length(CAST(a.origin_instance AS BLOB))+length(CAST(a.authority_identity AS BLOB))+length(CAST(a.provider_key AS BLOB))+
                length(CAST(a.producer_slot_id AS BLOB))+length(CAST(a.consumer_slot_id AS BLOB))+length(CAST(a.fair_key AS BLOB))),0)
                FROM produced_plan_admissions a JOIN parent_plans p ON p.plan_id=a.plan_id WHERE p.client_id=?""", client))
            charge(c.count("""SELECT COALESCE(SUM(length(CAST(x.claim_nonce AS BLOB))+length(CAST(x.origin_instance AS BLOB))+
                length(CAST(x.slot_id AS BLOB))+length(CAST(x.reserved_archive_bytes AS BLOB))),0)
                FROM plan_slot_resolution_claims x JOIN parent_plans p ON p.plan_id=x.plan_id WHERE p.client_id=?""", client))
            charge(c.count("""SELECT COALESCE(SUM(length(CAST(x.record_json AS BLOB))+length(CAST(x.record_digest AS BLOB))+
                COALESCE(length(CAST(x.resolved_input_json AS BLOB)),0)+COALESCE(length(CAST(x.certificate_id AS BLOB)),0)+
                COALESCE(length(CAST(x.work_key AS BLOB)),0)+length(CAST(x.reasons_json AS BLOB))),0)
                FROM plan_slot_resolutions x JOIN parent_plans p ON p.plan_id=x.plan_id WHERE p.client_id=?""", client))
            charge(c.count("""SELECT COALESCE(SUM(length(CAST(p.record_json AS BLOB))+length(CAST(p.certificate_id AS BLOB))+
                length(CAST(p.origin_instance AS BLOB))),0) FROM work_publications p
                JOIN review_executions r ON r.work_key=p.work_key WHERE r.client_id=?""", client))
            charge(c.count("SELECT COALESCE(SUM(length(CAST(payload AS BLOB))),0) FROM events WHERE client_id=?", client))
        }
        routes.forEach { route ->
            charge(c.count("SELECT COALESCE(length(CAST(definition_json AS BLOB)),0) FROM routing_batches WHERE routing_id=?", route))
        }
    }

    private fun readFacts(c: Connection, credential: ClientCredential, grant: Grant): Facts {
        val origin = service.authenticate(c, credential)
        if (origin != grant.value.origin) fail("joint_origin_mismatch", "The initiating binding changed")
        val (clientIds, routeIds) = closure(c, origin.clientId)
        if (clientIds != grant.value.members.map { it.binding.clientId }.sorted()) {
            fail("joint_route_closure_mismatch", "Owner authority must cover the entire transitive historical route closure")
        }
        boundHistory(c, clientIds, routeIds)
        val members = grant.value.members.sortedBy { it.binding.clientId }.map { readMember(c, it) }
        if (members.map { it.routingId }.distinct().size != 1) {
            fail("joint_latest_integration_batch_missing", "Every member needs its newest whole plan in the same integrated route")
        }
        val finalRoute = members.first().routingId
        val finalMembers = c.query("SELECT client_id FROM routing_members WHERE routing_id=? ORDER BY client_id LIMIT ?",
            finalRoute, limits.maxMembers + 1) { it.getString(1) }
        if (finalMembers != clientIds) fail("joint_final_batch_scope_mismatch", "The final integrated batch must cover the exact entire route closure")
        val routes = routeIds.map { id ->
            c.one("SELECT definition_json,routing_digest FROM routing_batches WHERE routing_id=?", id) { row ->
                val encoded = row.getString(1); val digest = row.getString(2)
                if (!Digests.valid(digest) || Digests.sha256(encoded) != digest) fail("joint_route_corrupt", "A retained routing identity is corrupt")
                IntegratedRouteIdentity(id, digest)
            } ?: fail("joint_route_corrupt", "A retained routing identity is unavailable")
        }
        val reasons = members.flatMap { it.reasons }.toMutableList()
        val contexts = members.flatMap { it.plan.definition.slots.map { slot -> slot.input.context.scope } }.distinct().sortedBy { canonical(it) }
        if (contexts.size.toLong() * grant.value.requiredRuleIds.size > limits.maxCoveragePairs) limit()
        members.forEach { member ->
            val definition = member.plan.definition
            if (definition.slots.map { it.input.context.scope }.toSet() != contexts.toSet()) {
                reasons += "joint_combined_context_vector_missing:${member.member.binding.clientId}"
            }
            if (definition.slots.any { it.input.requiredRuleIds != grant.value.requiredRuleIds }) {
                reasons += "joint_complete_catalog_missing:${member.member.binding.clientId}"
            }
        }
        val integration = members.single { it.member.binding.clientId == grant.value.integrationClientId }
        val scope = IntegratedCompletionScope(origin, integration.member.binding.clientId, integration.plan.id,
            integration.plan.definition.slots.first().input.snapshot.snapshotId, finalRoute, routes.single { it.routingId == finalRoute }.routingDigest,
            grant.value.catalogDigest, members.map { member ->
                val definition = member.plan.definition
                IntegratedClientScope(member.member.binding, member.plan.id, member.member.planDigest, definition.slots.first().input.snapshot.snapshotId,
                    definition.sourceBindingDigest, definition.generatedInputsDigest)
            }, contexts.flatMap { context -> grant.value.requiredRuleIds.map { IntegratedCoverageRequirement(context, it) } })
        val request = IntegratedFenceRequest(scope, integratedScopeDigest(scope), members.map { it.frontier },
            members.flatMap { it.obligations }.sortedWith(compareBy({ it.clientId }, { it.kind }, { it.sequence }, { it.obligationId })), routes)
        boundedEncoding(canonical(request))
        return Facts(request, members, reasons.distinct().sorted())
    }

    private fun readMember(c: Connection, member: AuthorizedIntegratedMember): MemberFacts {
        val binding = service.authenticate(c, member.adapter)
        if (binding != member.binding) fail("joint_member_binding_mismatch", "A recipient's full enrolled binding changed")
        val client = binding.clientId
        val epoch = c.count("SELECT epoch FROM clients WHERE client_id=?", client)
        val reviewFrontier = c.count("SELECT next_review-1 FROM clients WHERE client_id=?", client)
        val eventFrontier = c.count("SELECT next_event-1 FROM clients WHERE client_id=?", client)
        if (reviewFrontier != c.count("SELECT COUNT(*) FROM reviews WHERE client_id=?", client) ||
            eventFrontier != c.count("SELECT COUNT(*) FROM events WHERE client_id=?", client) ||
            reviewFrontier < 1 || eventFrontier < 1 || c.count("SELECT routed_obligations FROM clients WHERE client_id=?", client) != 1L) {
            fail("joint_history_unavailable", "Retained review/event history or permanent route markers are incomplete")
        }
        val plan = service.plans.latest(c, client) ?: fail("joint_latest_whole_plan_missing", "A current integrated whole plan is required")
        val definition = plan.definition
        if (definition.binding != binding || definition.request != member.request || Digests.sha256(canonical(definition)) != member.planDigest) {
            fail("joint_latest_plan_mismatch", "Owner authority must retain this exact latest accepted whole plan")
        }
        val routingId = c.one("SELECT routing_id FROM parent_plans WHERE plan_id=?", plan.id) { it.getString(1) }
            ?: fail("joint_latest_integration_batch_missing", "The latest whole plan is not part of a joint integration batch")
        val reasons = mutableListOf<String>()
        val latest = c.one("SELECT input_json FROM reviews WHERE client_id=? ORDER BY sequence DESC LIMIT 1", client) {
            ContractJson.decodeFromString<AnalysisInput>(it.getString(1))
        }
        if (plan.frontier != reviewFrontier || latest != definition.slots.last().input) reasons += "joint_current_parent_plan_missing:$client"
        if (definition.slots.any { it.state != "pending" || it.identity == null || it.reasons.isNotEmpty() ||
                !it.input.context.fullProject || it.input.snapshot.scopeExclusions.isNotEmpty() || it.input.snapshot.unknownInputs.isNotEmpty() }) {
            reasons += "joint_final_scope_incomplete:$client"
        }
        val obligations = mutableListOf<IntegratedPatchObligation>()
        val history = mutableListOf<String>()
        var requiredPairs = 0L
        c.query("""SELECT r.review_id,r.patch_id,r.request_hash,r.input_json,r.sequence,r.work_key,r.cancelled,r.kind,
            r.execution_input_json,r.execution_certificate,r.resolution_state,r.resolution_reasons_json,
            r.effective_result_json,r.policy_certificate,r.policy_validation_json,w.state,w.result_json,w.validation_json,w.progress_faults_json
            FROM review_executions r LEFT JOIN work w ON w.work_key=r.work_key WHERE r.client_id=? ORDER BY r.sequence""", client) { row ->
            service.producedPlans.requireReviewRelation(c, row.getString("review_id"))
            if (row.getString("kind") == "produced") reasons += "joint_produced_profile_unavailable:$client"
            if (row.getString("resolution_state") == "deferred") reasons += "joint_slot_deferred:$client:${row.getString("review_id")}"
            if (row.getString("state") == null) reasons += "joint_execution_work_unavailable:$client:${row.getString("review_id")}"
            if (row.getLong("sequence") != obligations.size + 1L) reasons += "joint_review_history_incomplete:$client"
            // Historical obligations and captured policy ALWAYS identify D, never substituted P/K.
            val input = ContractJson.decodeFromString<AnalysisInput>(row.getString("input_json"))
            requiredPairs += input.requiredRuleIds.size
            if (requiredPairs > limits.maxCoveragePairs) limit()
            val obligation = IntegratedPatchObligation(client, "review", row.getString("review_id"), row.getString("patch_id"),
                row.getString("request_hash"), input.analysisKey(), input.snapshot.snapshotId, row.getLong("sequence"))
            obligations += obligation
            history += Digests.sha256(canonical(listOf(integratedObligationDigest(obligation), row.getString("work_key").orEmpty(),
                row.getString("kind"), row.getString("resolution_state"), row.getString("resolution_reasons_json"),
                row.getString("execution_certificate").orEmpty(), Digests.sha256(row.getString("execution_input_json")),
                row.getInt("cancelled").toString(), row.getString("state").orEmpty(),
                Digests.sha256(row.getString("effective_result_json").orEmpty()), Digests.sha256(row.getString("result_json").orEmpty()),
                row.getString("policy_certificate").orEmpty(), row.getString("policy_validation_json"),
                row.getString("validation_json").orEmpty(), row.getString("progress_faults_json").orEmpty())))
        }
        if (obligations.size.toLong() != reviewFrontier) reasons += "joint_review_history_incomplete:$client"
        c.query("SELECT plan_id,patch_id,request_hash,plan_digest,definition_json,review_frontier,cancelled FROM parent_plans WHERE client_id=? ORDER BY rowid", client) { row ->
            val id = row.getString("plan_id")
            val status = service.plans.status(c, client, id) // Validates every durable route; no owner callback.
            if (status.state != "complete") reasons += "joint_parent_${status.state}:$client:$id"
            val obligation = IntegratedPatchObligation(client, "parent_plan", id, row.getString("patch_id"), row.getString("request_hash"),
                row.getString("plan_digest"), status.sourceSnapshotId, row.getLong("review_frontier"))
            obligations += obligation
            history += Digests.sha256(canonical(listOf(integratedObligationDigest(obligation), row.getInt("cancelled").toString(),
                Digests.sha256(row.getString("definition_json")))))
        }
        val events = c.query("SELECT event_id,sequence,payload_digest,payload,generation,delivery_required FROM events WHERE client_id=? ORDER BY sequence", client) { row ->
            val digest = row.getString("payload_digest")
            EventFacts(row.getString("event_id"), row.getLong("sequence"), digest, row.getInt("delivery_required") == 1,
                row.getLong("generation") == binding.generation && Digests.valid(digest) && Digests.sha256(row.getString("payload")) == digest)
        }
        if (events.any { !it.required || !it.valid } || events.map { it.sequence } != (1L..eventFrontier).toList()) {
            reasons += "joint_event_history_incomplete:$client"
        }
        history += events.map { Digests.sha256(canonical(listOf(it.id, it.sequence.toString(), it.digest, it.required.toString()))) }
        // EXACT existing historical certificate/finding/coverage/ancestry logic, not a fork or a
        // filter of cross_client_finalization_unavailable. All parent statuses were checked above.
        reasons += service.lineageReasons(c, binding, latest, plan).map { "$it:$client" }
        val frontier = IntegratedClientFrontier(client, epoch, reviewFrontier, eventFrontier, events.filter { it.required }.map { it.id },
            Digests.sha256(canonical(history)))
        return MemberFacts(member, plan, frontier, obligations, reasons.distinct().sorted(), routingId)
    }

    /** Short claim validation. Readiness + these counters fences every supported mutation path. */
    private fun currentReasons(c: Connection, credential: ClientCredential, grant: Grant, facts: Facts): List<String> = buildList {
        if (service.authenticate(c, credential) != grant.value.origin) add("joint_origin_binding_changed")
        val (members, routes) = closure(c, grant.value.origin.clientId)
        if (members != facts.members.map { it.member.binding.clientId } || routes != facts.request.routes.map { it.routingId }) add("joint_route_closure_changed")
        facts.members.forEach { member ->
            val f = member.frontier
            if (service.authenticate(c, member.member.adapter) != member.member.binding) add("joint_member_binding_changed:${f.clientId}")
            if (c.count("""SELECT COUNT(*) FROM clients WHERE client_id=? AND gate_state='open' AND epoch=?
                AND next_review=? AND next_event=? AND routed_obligations=1""", f.clientId, f.lineageEpoch,
                f.reviewFrontier + 1, f.eventFrontier + 1) != 1L) add("joint_member_frontier_changed_or_claimed:${f.clientId}")
            // Required even after full certificate verification: no in-flight publisher/restorer
            // may have work that can mutate this client's frozen result frontier after the claim.
            if (c.count("SELECT COUNT(*) FROM reviews WHERE client_id=? AND kind='produced'", f.clientId) != 0L) {
                add("joint_produced_profile_unavailable:${f.clientId}")
            }
            if (c.count("""SELECT COUNT(*) FROM review_executions r LEFT JOIN work w ON w.work_key=r.work_key WHERE r.client_id=?
                AND (r.cancelled<>0 OR r.resolution_state IN ('deferred','unavailable') OR w.work_key IS NULL OR
                    w.state<>'complete' OR w.result_json IS NULL OR r.effective_result_json IS NULL)""", f.clientId) != 0L) {
                add("joint_publication_not_quiescent:${f.clientId}")
            }
        }
    }

    private fun deliveryReasons(c: Connection, frontiers: List<IntegratedClientFrontier>): List<String> = buildList {
        frontiers.forEach { f ->
            val actual = c.query("""SELECT e.event_id,a.event_id acked,
                EXISTS(SELECT 1 FROM observations o WHERE o.event_id=e.event_id AND o.client_id=e.client_id
                    AND o.context_digest=a.context_digest) observed
                FROM events e LEFT JOIN acknowledgements a ON a.event_id=e.event_id AND a.client_id=e.client_id
                WHERE e.client_id=? AND e.sequence<=? AND e.delivery_required=1 ORDER BY e.sequence LIMIT ?""",
                f.clientId, f.eventFrontier, limits.maxEvents + 1) { row ->
                val id = row.getString("event_id")
                if (row.getString("acked") == null) add("joint_insertion_missing:${f.clientId}:$id")
                if (row.getInt("observed") == 0) add("joint_model_observation_missing:${f.clientId}:$id")
                id
            }
            if (actual.isEmpty() || actual.size > limits.maxEvents || actual != f.requiredEventIds) add("joint_delivery_frontier_changed:${f.clientId}")
        }
    }

    private fun fenceReasons(facts: Facts, check: IntegratedSnapshotCheck): List<String> = buildList {
        if (!check.current || !check.authoritative) add("joint_current_authority_unavailable")
        if (check.integrationSnapshotId != facts.request.scope.integrationSnapshotId || check.integrationEpoch.isNullOrBlank() ||
            check.integrationEpoch.length > 512 || !Digests.valid(check.compositionProofDigest.orEmpty())) add("joint_integration_authority_mismatch")
        if (check.validUntilEpochMillis != null && System.currentTimeMillis() >= check.validUntilEpochMillis) add("joint_authority_expired")
        if (check.members.size != facts.members.size || check.members.map { it.binding.clientId }.distinct().size != check.members.size ||
            check.members.map { it.binding.clientId }.sorted() != facts.members.map { it.member.binding.clientId }) {
            add("joint_source_vector_incomplete")
        } else facts.members.forEach { member ->
            val attested = check.members.single { it.binding.clientId == member.member.binding.clientId }
            val plan = member.plan.definition
            val input = plan.slots.last().input
            val local = attested.check
            if (attested.binding != member.member.binding || attested.planDigest != member.member.planDigest || !local.current || !local.authoritative ||
                local.snapshotId != input.snapshot.snapshotId || local.sourceEpoch == null || local.sourceEpoch < 0 || local.analysisEpoch.isNullOrBlank() ||
                local.analysisEpoch.length > 512 || local.sourceBindingDigest != plan.sourceBindingDigest || local.generatedInputsDigest != plan.generatedInputsDigest ||
                local.configurationDigest != input.context.configurationDigest || local.modelDigest != input.context.modelDigest ||
                local.dependencyDigest != input.context.dependencyDigest || local.toolchainDigest != input.context.toolchainDigest ||
                local.contextIdentities.map { it.context }.distinct().size != local.contextIdentities.size ||
                local.contextIdentities.toSet() != plan.slots.mapNotNull { it.identity }.toSet() || local.reasons.isNotEmpty()) {
                add("joint_member_source_configuration_mismatch:${member.member.binding.clientId}")
            }
            if (local.validUntilEpochMillis != null && System.currentTimeMillis() >= local.validUntilEpochMillis) add("joint_member_authority_expired:${member.member.binding.clientId}")
        }
        val expected = facts.request.historicalObligations.map(::integratedObligationDigest).toSet()
        if (check.patchDispositions.size != expected.size || check.patchDispositions.map { it.obligationDigest }.toSet() != expected ||
            check.patchDispositions.any { it.disposition !in DISPOSITIONS || !Digests.valid(it.evidenceDigest) }) {
            add("joint_historical_patch_dispositions_incomplete")
        }
        if (check.reasons.isNotEmpty()) add("joint_authority_reported_unavailable")
    }

    private fun <T> withFence(grant: Grant, facts: Facts, block: (IntegratedSnapshotCheck) -> T): T {
        val ownerThread = Thread.currentThread()
        val active = AtomicBoolean(true)
        val entered = AtomicBoolean(false)
        val violated = AtomicBoolean(false)
        var outcome: Result<T>? = null
        // Detach the request: a callback cannot mutate the facts later used in the claim.
        try {
            grant.value.fence.withCurrentIntegratedSnapshot(detach(facts.request)) { supplied ->
                // A callback is an ephemeral same-stack lease, not a capability that the owner
                // can retain, dispatch asynchronously, race, or invoke after this method exits.
                // Foreign threads never read/write outcome or begin any DB/action work.
                if (Thread.currentThread() !== ownerThread || !active.get() || violated.get() || !entered.compareAndSet(false, true)) {
                    violated.set(true)
                    fail("joint_fence_callback_invalid", "The joint fence callback is closed, foreign-thread or already used")
                }
                if (supplied.members.size > limits.maxMembers || supplied.patchDispositions.size > limits.maxReviews + limits.maxParents ||
                    supplied.reasons.size > 64 || supplied.members.any { it.check.contextIdentities.size > 512 || it.check.reasons.size > 64 }) {
                    fail("joint_attestation_limit", "The complete joint attestation exceeds its bound")
                }
                val check = detach(supplied)
                boundedEncoding(canonical(check))
                block(check).also { outcome = Result.success(it) }
            }
        } finally {
            active.set(false) // Also close when the owner fence or guarded callback throws.
        }
        if (violated.get()) fail("joint_fence_callback_invalid", "The joint fence attempted an invalid callback invocation")
        // Do not accept a fabricated return when the installed fence omitted/caught the callback.
        val completed = outcome ?: fail("joint_fence_not_completed", "The actual joint fenced callback did not complete")
        return completed.getOrThrow()
    }

    private fun load(c: Connection, origin: ClientBinding, cutId: String): Audit {
        val size = c.one("SELECT length(CAST(value AS BLOB)) FROM metadata WHERE key=?", key(cutId)) { it.getLong(1) }
            ?: fail("joint_cut_not_found", "Joint cut is not available to this client")
        if (size > limits.maxAuditBytes) fail("joint_audit_limit", "The retained joint audit exceeds this boundary's safe limit")
        val encoded = c.one("SELECT value FROM metadata WHERE key=?", key(cutId)) { it.getString(1) }!!
        val audit = auditCodec.decode(cutId, encoded)
        if (audit.cut.scope.origin != origin) fail("joint_cut_not_found", "Joint cut is not available to this client")
        if (audit.cut.cutId != cutId) fail("joint_audit_invalid", "The joint audit identity is unavailable or inconsistent")
        return audit
    }

    private fun replace(c: Connection, value: Audit) {
        if (c.execute("UPDATE metadata SET value=? WHERE key=?", encode(value), key(value.cut.cutId)) != 1) {
            fail("joint_audit_missing", "The durable joint intent is unavailable")
        }
    }

    private fun markUnknown(saved: Audit) {
        // Best effort label only; action_claimed is already a non-replayable durable freeze even
        // if disk failure prevents this transaction. Never reopen ANY member's generation.
        runCatching { ledger.transaction { c ->
            var changed = 0
            saved.request.frontiers.forEach { member ->
                changed += c.execute("""UPDATE clients SET gate_state='outcome_unknown' WHERE client_id=? AND active_cut_id=?
                    AND gate_state IN ('action_claimed','complete')""", member.clientId, saved.cut.cutId)
            }
            if (changed > 0) {
                val current = load(c, saved.cut.scope.origin, saved.cut.cutId)
                replace(c, current.copy(state = "outcome_unknown"))
            }
        } }
    }

    private fun failedCompletion(saved: Audit, reason: String): IntegratedCompletionResult {
        markUnknown(saved)
        // In particular, do not label an uncertain successful claim COMMIT as still prepared just
        // because the local flag was not assigned. If even audit readback is unavailable, unknown.
        val durable = runCatching { ledger.read { c -> load(c, saved.cut.scope.origin, saved.cut.cutId) } }.getOrNull()
            ?: saved.copy(state = "outcome_unknown")
        return result(durable, false, reason)
    }

    private fun result(saved: Audit, allowed: Boolean, reason: String): IntegratedCompletionResult = result(saved, allowed, listOf(reason))
    private fun result(saved: Audit, allowed: Boolean, reasons: List<String>): IntegratedCompletionResult =
        IntegratedCompletionResult(saved.cut.cutId, saved.cut.scope, saved.cut.scopeDigest, allowed, saved.state, reasons, actionPerformed = allowed)
    private inline fun <reified T> detach(value: T): T = ContractJson.decodeFromString(canonical(value))
    private fun encode(value: Audit): String = auditCodec.encode(value)
    private fun boundedEncoding(value: String) {
        if (value.length > limits.maxAuditBytes || value.toByteArray(Charsets.UTF_8).size > limits.maxAuditBytes) {
            fail("joint_audit_limit", "The complete bounded joint audit is too large; no prefix was retained")
        }
    }
    private fun limit(): Nothing = fail("joint_history_limit", "The complete routed history exceeds the safe bound; no obligations were dropped")
    private fun key(cutId: String): String = IntegratedCompletionAuditCodec.key(cutId)

    private companion object {
        val DISPOSITIONS = setOf("incorporated", "superseded_by_verified_integration")
    }
}

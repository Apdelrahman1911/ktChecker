package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Durable per-user preview broker. Public credentials are capabilities, not a hostile same-UID
 * sandbox. Registration and PreparedInputObserver belong only to the authenticated trusted host.
 * Every accepted request retains its own obligation/outbox even when computation is shared.
 */
class KernelService private constructor(
    dataDir: Path,
    internal val provider: AnalysisProvider,
    internal val config: KernelConfig,
    private val snapshotGate: SnapshotGate,
    internal val preparedInputObserver: PreparedInputObserver,
    findingProviders: Map<String, Set<String>>,
    private val resultPolicy: ResultPolicy,
    planAuthority: ReviewPlanAuthority,
    routingAuthority: ReviewPlanRoutingAuthority,
) : AutoCloseable {
    internal val policyEvaluatorIdentity = capturePolicyEvaluatorIdentity(resultPolicy)
    internal val admittedPolicyCertificate = canonical(PolicyCertificate(policyEvaluatorIdentity, PolicyCertificate.ADMITTED))
    internal val root = dataDir.toAbsolutePath().normalize()
    internal val ledger = Ledger(root, config)
    internal val archive = InputArchive(root.resolve("inputs"), config)
    internal val providerId = provider.id
    internal val providerVersion = provider.version
    internal val supportedRules = provider.supportedRuleIds.toSortedSet().toSet()
    internal val authorizedFindingProviders = findingProviders.mapValues { it.value.toSet() }.toMap()
    // A new raw-publication contract cannot reuse a pre-fence execution certificate. Old rows
    // remain audited under their old key; upgrading never invents historical deadline evidence.
    private val rawPublicationValidationProfile = "ktchecker/kernel/raw-publication-validation/3"
    internal val providerKey = Digests.sha256(canonical(listOf(rawPublicationValidationProfile, providerId, providerVersion,
        canonical(supportedRules.toList()), canonical(authorizedFindingProviders.mapValues { it.value.sorted() }))))
    private val stopped = AtomicBoolean(false)
    internal val ingestion = Semaphore(maxOf(1, config.workerCount))
    private val cursorSecret: ByteArray
    private val scheduler: WorkScheduler
    internal val instanceId: String = UUID.randomUUID().toString()
    internal val producedPlans = ProducedPlans(this, planAuthority)
    internal val plans = ParentPlans(this, planAuthority, routingAuthority)

    init {
        require(providerId.isNotBlank() && providerVersion.isNotBlank())
        cursorSecret = ledger.transaction { c ->
            val existing = c.one("SELECT value FROM metadata WHERE key='cursor_secret'") { it.getString(1) }
            if (existing != null) Base64.getDecoder().decode(existing) else {
                val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
                c.execute("INSERT INTO metadata(key,value) VALUES('cursor_secret',?)", Base64.getEncoder().encodeToString(secret))
                secret
            }
        }
        scheduler = WorkScheduler(this)
        scheduler.start()
    }

    companion object {
        fun open(
            dataDir: Path,
            provider: AnalysisProvider,
            config: KernelConfig = KernelConfig(),
            snapshotGate: SnapshotGate = SnapshotGate.UNAVAILABLE,
            preparedInputObserver: PreparedInputObserver = PreparedInputObserver.NONE,
            findingProviders: Map<String, Set<String>> = provider.supportedRuleIds.associateWith { setOf(provider.id) },
            resultPolicy: ResultPolicy = ResultPolicy.NONE,
            planAuthority: ReviewPlanAuthority = ReviewPlanAuthority.DENY,
            routingAuthority: ReviewPlanRoutingAuthority = ReviewPlanRoutingAuthority.DENY,
        ): KernelService = KernelService(dataDir, provider, ContractJson.decodeFromString<KernelConfig>(canonical(config)),
            snapshotGate, preparedInputObserver, findingProviders, resultPolicy, planAuthority, routingAuthority)
    }

    fun registerClient(binding: ClientBinding): ClientRegistration = registerClient(ClientRegistration(binding,
        ClientCredential(binding.clientId, randomToken()), AdapterCredential(binding.clientId, randomToken())))

    /**
     * Trusted host enrollment recovery: persist this private random-capability intent BEFORE calling.
     * Exact binding+both token hashes is idempotent; no token/generation replacement or auto-claim.
     */
    fun registerClient(registration: ClientRegistration): ClientRegistration {
        ensureOpen()
        val binding = registration.binding
        listOf(binding.ownerId, binding.workspaceId, binding.worktreeId, binding.clientId, binding.lineageId, binding.sessionId)
            .forEach { identifier(it, "binding identity") }
        if (binding.generation !in 1..9_007_199_254_740_991L) fail("invalid_binding", "Generation must be a positive interoperable integer")
        val client = registration.client
        val adapter = registration.adapter
        if (registration.contractVersion != PREVIEW_CONTRACT || client.clientId != binding.clientId || adapter.clientId != binding.clientId ||
            client.token == adapter.token || !client.token.matches(Regex("[A-Za-z0-9_-]{43,128}")) || !adapter.token.matches(Regex("[A-Za-z0-9_-]{43,128}"))) {
            fail("invalid_enrollment", "Enrollment requires distinct random capabilities for the exact bound client")
        }
        val clientHash = Digests.sha256(client.token)
        val adapterHash = Digests.sha256(adapter.token)
        ledger.transaction { c ->
            val existing = c.one("SELECT binding_json,client_hash,adapter_hash FROM clients WHERE client_id=?", binding.clientId) {
                Triple(it.getString(1), it.getString(2), it.getString(3))
            }
            if (existing != null) {
                val same = existing.first == canonical(binding) &&
                    MessageDigest.isEqual(existing.second.toByteArray(), clientHash.toByteArray()) &&
                    MessageDigest.isEqual(existing.third.toByteArray(), adapterHash.toByteArray())
                if (!same) fail("client_exists", "Client is already bound; restore its exact stored enrollment intent")
            } else c.execute("INSERT INTO clients(client_id,binding_json,client_hash,adapter_hash) VALUES(?,?,?,?)",
                binding.clientId, canonical(binding), clientHash, adapterHash)
        }
        return registration
    }

    fun submit(credential: ClientCredential, request: SubmitRequest): ReviewStatus {
        ensureOpen()
        // Freeze caller-owned collections before queueing work on another thread.
        val encoded = canonical(request)
        if (encoded.toByteArray().size > config.maxInputBytes) fail("input_limit", "Request metadata exceeds its byte limit")
        val frozenRequest = ContractJson.decodeFromString<SubmitRequest>(encoded)
        val binding = ledger.read { authenticate(it, credential) }
        validateInput(binding, frozenRequest)
        val normalized = frozenRequest.copy(input = frozenRequest.input.copy(
            snapshot = frozenRequest.input.snapshot.copy(files = frozenRequest.input.snapshot.files.sortedBy { it.path }),
            requiredRuleIds = (frozenRequest.input.requiredRuleIds.ifEmpty { supportedRules.toList() }).distinct().sorted(),
            changedPaths = frozenRequest.input.changedPaths.distinct().sorted(),
        ))
        val requestJson = canonical(normalized)
        if (normalized.input.requiredRuleIds.size > 50_000 || requestJson.toByteArray().size > config.maxInputBytes) {
            fail("input_limit", "Expanded required plan exceeds the metadata bound")
        }
        normalized.input.requiredRuleIds.forEach { identifier(it, "rule ID") }
        val requestHash = Digests.sha256(requestJson)
        val key = Digests.sha256(canonical(listOf(providerKey, normalized.input.analysisKey())))
        val prior = ledger.read { c ->
            authenticate(c, credential)
            idempotent(c, binding.clientId, normalized.idempotencyKey, requestHash)
        }
        if (prior != null) return prior
        if (!ingestion.tryAcquire()) fail("ingestion_busy", "Bounded input ingestion is busy; retry before acceptance")
        try {
            val existingWork = ledger.read { c ->
                preflightAdmission(c, binding.clientId)
                c.count("SELECT COUNT(*) FROM work WHERE work_key=?", key) != 0L
            }
            val staged = if (existingWork) null else archive.stage(key, normalized.input)
            try {
                return ledger.transaction { c ->
                    ensureOpen()
                    authenticate(c, credential)
                    val duplicate = idempotent(c, binding.clientId, normalized.idempotencyKey, requestHash)
                    if (duplicate != null) return@transaction duplicate
                    preflightAdmission(c, binding.clientId)
                    if (c.count("SELECT COUNT(*) FROM reviews WHERE client_id=? AND patch_id=?", binding.clientId, normalized.patchId) != 0L ||
                        c.count("SELECT COUNT(*) FROM parent_plans WHERE client_id=? AND patch_id=?", binding.clientId, normalized.patchId) != 0L) {
                        fail("patch_id_conflict", "Patch ID already has a distinct accepted request in this client lineage")
                    }
                    val workExists = c.count("SELECT COUNT(*) FROM work WHERE work_key=?", key) != 0L
                    if (!workExists) {
                        val ready = staged ?: fail("archive_missing", "Prepared input is unavailable")
                        if (queuedCapacityUsed(c) >= config.maxQueuedWork) {
                            fail("queue_full", "Durable work queue is full; request was not accepted")
                        }
                        val stored = archiveBytesUsed(c)
                        if (ready.bytes > config.maxArchiveBytes - stored) fail("archive_full", "Durable archive quota is full; request was not accepted")
                        val fairKey = Digests.sha256(canonical(listOf(binding.ownerId, binding.workspaceId, binding.worktreeId)))
                        ready.install()
                        // New queues join the current round, rather than jumping in front of all
                        // previously served queues with an artificial service age of zero.
                        c.execute("""INSERT OR IGNORE INTO fair_queues(fair_key,last_served)
                            VALUES(?,(SELECT CAST(value AS INTEGER) FROM metadata WHERE key='dispatch_counter'))""", fairKey)
                        c.execute("""INSERT INTO work(work_key,input_json,frozen_json,provider_key,fair_key,state,archive_bytes,created_at)
                            VALUES(?,?,?,?,?,'queued',?,?)""", key, canonical(normalized.input), canonical(ready.input),
                            providerKey, fairKey, ready.bytes, System.currentTimeMillis())
                    }
                    val sequence = c.count("SELECT next_review FROM clients WHERE client_id=?", binding.clientId)
                    val reviewId = UUID.randomUUID().toString()
                    c.execute("""INSERT INTO reviews(review_id,client_id,patch_id,idempotency_key,request_hash,request_json,
                        input_json,work_key,sequence,policy_certificate,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                        reviewId, binding.clientId, normalized.patchId, normalized.idempotencyKey, requestHash, requestJson,
                        canonical(normalized.input), key, sequence, admittedPolicyCertificate, System.currentTimeMillis())
                    c.execute("""INSERT OR IGNORE INTO fair_clients(client_id,fair_key,last_served)
                        SELECT ?,fair_key,(SELECT CAST(value AS INTEGER) FROM metadata WHERE key='dispatch_counter')
                        FROM work WHERE work_key=?""", binding.clientId, key)
                    c.execute("UPDATE clients SET next_review=next_review+1,epoch=epoch+1 WHERE client_id=?", binding.clientId)
                    val raw = c.one("SELECT result_json FROM work WHERE work_key=? AND state='complete'", key) {
                        it.getString(1)?.let { json -> ContractJson.decodeFromString<ProviderResult>(json) }
                    }
                    if (raw != null) applyResultPolicy(c, binding, reviewId, normalized.input, raw)
                    val status = readStatus(c, reviewId, binding.clientId)
                    appendEvent(c, binding, status.copy(state = "accepted", result = null, reasons = emptyList()), "accepted")
                    val priorProgress = c.query("SELECT payload_json FROM work_progress WHERE work_key=? ORDER BY sequence", key) {
                        ContractJson.decodeFromString<ProviderResult>(it.getString(1))
                    }
                    priorProgress.forEach { partial ->
                        val (classified, issues) = classifyResult(c, binding, reviewId, normalized.input, partial)
                        appendEvent(c, binding, status.copy(state = if (status.cancelled) "cancelled" else "running",
                            result = classified, reasons = issues), "progress")
                    }
                    if (status.result != null) appendEvent(c, binding, status, "result")
                    status
                }
            } finally { staged?.close() }
        } finally { ingestion.release() }
    }

    fun status(credential: ClientCredential, reviewId: String): ReviewStatus {
        ensureOpen()
        return ledger.read { c ->
            val binding = authenticate(c, credential)
            readStatus(c, reviewId, binding.clientId)
        }
    }

    fun submitPlan(credential: ClientCredential, request: SubmitPlanRequest): ParentPlanStatus = plans.submit(credential, request)
    fun planStatus(credential: ClientCredential, planId: String): ParentPlanStatus = plans.status(credential, planId)
    fun cancelPlan(credential: ClientCredential, planId: String): ParentPlanStatus = plans.cancel(credential, planId)

    fun cancel(credential: ClientCredential, reviewId: String): ReviewStatus {
        ensureOpen()
        return ledger.transaction { c ->
            val binding = authenticate(c, credential)
            val old = readStatus(c, reviewId, binding.clientId)
            if (c.one("SELECT gate_state FROM clients WHERE client_id=?", binding.clientId) { it.getString(1) } != "open") {
                fail("lineage_finalized", "A final action was already claimed for this client generation")
            }
            if (!old.cancelled) {
                c.execute("UPDATE reviews SET cancelled=1 WHERE review_id=? AND client_id=?", reviewId, binding.clientId)
                c.execute("UPDATE clients SET epoch=epoch+1 WHERE client_id=?", binding.clientId)
                val result = readStatus(c, reviewId, binding.clientId)
                appendEvent(c, binding, result, "cancelled")
                result
            } else old
        }
    }

    /** Fetch cursors are scoped signed high-water marks; reading never ACKs or observes an event. */
    fun events(credential: ClientCredential, cursor: String? = null, limit: Int = 100): EventPage {
        ensureOpen()
        if (limit !in 1..config.maxEventPageSize) fail("invalid_limit", "Event page size is outside the configured bound")
        return ledger.read { c ->
            val binding = authenticate(c, credential)
            val after = if (cursor == null) 0L else decodeCursor(cursor, binding)
            val rows = mutableListOf<ReviewEvent>()
            var bytes = 0L
            c.prepareStatement("SELECT * FROM events WHERE client_id=? AND sequence>? ORDER BY sequence LIMIT ?").use { query ->
                query.setString(1, binding.clientId); query.setLong(2, after); query.setInt(3, limit)
                query.executeQuery().use { result ->
                    while (result.next()) {
                        val event = eventRow(result)
                        val eventBytes = event.payload.toByteArray().size.toLong()
                        // One bounded result is always deliverable; don't create an unpageable hole.
                        if (rows.isNotEmpty() && bytes + eventBytes > config.maxEventPageBytes) break
                        rows += event
                        bytes += eventBytes
                    }
                }
            }
            val last = rows.lastOrNull()?.sequence ?: after
            val more = c.count("SELECT COUNT(*) FROM events WHERE client_id=? AND sequence>?", binding.clientId, last) != 0L
            EventPage(rows, encodeCursor(binding, last), more)
        }
    }

    fun acknowledge(credential: AdapterCredential, eventIds: List<String>, receipt: ContextReceipt): ReceiptResult {
        ensureOpen()
        val ids = receiptEventIds(eventIds)
        identifier(receipt.receiptId, "receipt ID")
        identifier(receipt.contextItemId, "context item ID")
        if (!Digests.valid(receipt.contextDigest)) fail("invalid_receipt", "Context digest must be SHA-256")
        try { Instant.parse(receipt.insertedAt) } catch (_: Exception) { fail("invalid_receipt", "Insertion time must be an ISO-8601 instant") }
        val receiptJson = canonical(receipt)
        return ledger.transaction { c ->
            val binding = authenticate(c, credential)
            validateOwnedEvents(c, binding, ids)
            recordReceipt(c, binding.clientId, "ack", receipt.receiptId, canonical(listOf(canonical(ids), receiptJson)))
            for (id in ids) {
                val existing = c.one("SELECT receipt_json FROM acknowledgements WHERE event_id=?", id) { it.getString(1) }
                if (existing != null && existing != receiptJson) fail("receipt_conflict", "Event already has a different insertion receipt")
                c.execute("""INSERT OR IGNORE INTO acknowledgements(event_id,client_id,receipt_id,context_digest,context_item_id,inserted_at,receipt_json)
                    VALUES(?,?,?,?,?,?,?)""", id, binding.clientId, receipt.receiptId, receipt.contextDigest,
                    receipt.contextItemId, receipt.insertedAt, receiptJson)
            }
            ReceiptResult(binding.clientId, ids, "acknowledged")
        }
    }

    fun observe(credential: AdapterCredential, eventIds: List<String>, receipt: ObservationReceipt): ReceiptResult {
        ensureOpen()
        val ids = receiptEventIds(eventIds)
        identifier(receipt.observationId, "observation ID")
        identifier(receipt.modelRequestId, "model request ID")
        if (receipt.contextDigests.isEmpty() || receipt.contextDigests.size > config.maxEventPageSize ||
            receipt.contextDigests.any { !Digests.valid(it) } || receipt.contextDigests.distinct().size != receipt.contextDigests.size) {
            fail("invalid_observation", "Observation requires a bounded set of valid context digests")
        }
        val normalized = receipt.copy(contextDigests = receipt.contextDigests.sorted())
        val receiptJson = canonical(normalized)
        return ledger.transaction { c ->
            val binding = authenticate(c, credential)
            validateOwnedEvents(c, binding, ids)
            recordReceipt(c, binding.clientId, "observe", receipt.observationId, canonical(listOf(canonical(ids), receiptJson)))
            for (id in ids) {
                val context = c.one("SELECT context_digest FROM acknowledgements WHERE event_id=? AND client_id=?", id, binding.clientId) { it.getString(1) }
                    ?: fail("insertion_required", "Observation requires the event's durable insertion receipt")
                if (context !in normalized.contextDigests) fail("observation_mismatch", "Observed request does not contain this event's inserted context digest")
                c.execute("""INSERT OR IGNORE INTO observations(event_id,client_id,observation_id,model_request_id,context_digest,receipt_json)
                    VALUES(?,?,?,?,?,?)""", id, binding.clientId, receipt.observationId, receipt.modelRequestId, context, receiptJson)
            }
            ReceiptResult(binding.clientId, ids, "model_observed")
        }
    }

    /** Finite work/result frontier; ACK/observation/gate control traffic never extends it. */
    fun prepareGate(credential: ClientCredential): GateCut {
        ensureOpen()
        val expected = ledger.read { c ->
            val binding = authenticate(c, credential)
            lineageFacts(c, binding)
        }
        return withSnapshotCheck(expected) { check ->
            ledger.transaction { c ->
                val binding = authenticate(c, credential)
                val input = latestInput(c, binding.clientId)
                val reasons = mutableListOf<String>()
                if (input != expected.input || expected.epoch != c.count("SELECT epoch FROM clients WHERE client_id=?", binding.clientId)) {
                    reasons += "lineage_changed_during_prepare"
                }
                reasons += expected.reasons
                reasons += sourceReasons(input, check)
                reasons += planSourceReasons(expected, check)
                if (c.one("SELECT gate_state FROM clients WHERE client_id=?", binding.clientId) { it.getString(1) } != "open") {
                    reasons += "lineage_finalization_already_claimed"
                }
                val frontier = c.count("SELECT next_event-1 FROM clients WHERE client_id=?", binding.clientId)
                val required = c.query("SELECT event_id FROM events WHERE client_id=? AND sequence<=? AND delivery_required=1 ORDER BY sequence",
                    binding.clientId, frontier) { it.getString(1) }
                reasons += deliveryReasons(c, binding.clientId, required, frontier)
                val cut = GateCut(UUID.randomUUID().toString(), binding.clientId, binding.lineageId, binding.generation,
                    c.count("SELECT epoch FROM clients WHERE client_id=?", binding.clientId), input?.snapshot?.snapshotId,
                    frontier, required, reasons.isEmpty(), reasons.distinct().sorted(), check.sourceEpoch, check.analysisEpoch)
                c.execute("INSERT INTO gate_cuts(cut_id,client_id,cut_json,input_json,check_json) VALUES(?,?,?,?,?)",
                    cut.cutId, binding.clientId, canonical(cut), input?.let { canonical(it) }, canonical(check))
                cut
            }
        }
    }

    /**
     * Rechecks all authorities and current obligations at one action fence. allowed is a momentary
     * preview decision, NOT a transferable permit to send a later final answer. A real integrated
     * host must supply a short synchronous guardedAction; it runs inside config/source/DB fences.
     * Do not perform unbounded network work or re-enter the kernel in that callback.
     */
    fun completeGate(
        credential: ClientCredential,
        cutId: String,
        guardedAction: ((GateResult) -> Unit)? = null,
    ): GateResult {
        ensureOpen()
        val prepared = ledger.read { c ->
            val binding = authenticate(c, credential)
            loadCut(c, binding.clientId, cutId) to lineageFacts(c, binding)
        }
        return withSnapshotCheck(prepared.second) { check ->
            // Persist a non-replayable intent BEFORE any irreversible host effect. The source/config
            // fence remains held across both short DB transactions. Crash/throw after this point
            // makes the outcome unknown and freezes this client generation instead of replaying it.
            val decision = ledger.transaction { c ->
                val binding = authenticate(c, credential)
                val saved = loadCut(c, binding.clientId, cutId)
                val reasons = gateReasons(c, binding, saved, check, prepared.second).toMutableList()
                if (saved.consumed) reasons += "gate_cut_consumed"
                if (c.one("SELECT gate_state FROM clients WHERE client_id=?", binding.clientId) { it.getString(1) } != "open") {
                    reasons += "lineage_finalization_already_claimed"
                }
                val result = GateResult(reasons.isEmpty(), binding.clientId, binding.lineageId, binding.generation,
                    cutId, reasons.distinct().sorted())
                if (result.allowed) {
                    c.execute("UPDATE gate_cuts SET consumed=1 WHERE cut_id=?", cutId)
                    if (guardedAction != null) c.execute("UPDATE clients SET gate_state='action_claimed',active_cut_id=? WHERE client_id=?", cutId, binding.clientId)
                }
                result
            }
            if (!decision.allowed || guardedAction == null) return@withSnapshotCheck decision
            try {
                ledger.transaction { c ->
                    val binding = authenticate(c, credential)
                    val saved = loadCut(c, binding.clientId, cutId)
                    val reasons = gateReasons(c, binding, saved, check, prepared.second).toMutableList()
                    val claimed = c.count("SELECT COUNT(*) FROM clients WHERE client_id=? AND gate_state='action_claimed' AND active_cut_id=?",
                        binding.clientId, cutId) == 1L
                    if (!claimed || !saved.consumed) reasons += "finalization_intent_mismatch"
                    if (reasons.isNotEmpty()) {
                        c.execute("UPDATE clients SET gate_state='outcome_unknown' WHERE client_id=?", binding.clientId)
                        decision.copy(allowed = false, reasons = reasons.distinct().sorted())
                    } else {
                        val result = decision.copy(actionPerformed = true)
                        guardedAction(result)
                        c.execute("UPDATE clients SET gate_state='complete' WHERE client_id=?", binding.clientId)
                        result
                    }
                }
            } catch (_: Exception) {
                // The intent was durably committed before the callback. Even if this best-effort
                // label cannot be written, action_claimed still prohibits another action attempt.
                runCatching { ledger.transaction { c ->
                    c.execute("UPDATE clients SET gate_state='outcome_unknown' WHERE client_id=? AND active_cut_id=?", decision.clientId, cutId)
                } }
                decision.copy(allowed = false, reasons = listOf("guarded_action_outcome_unknown"))
            }
        }
    }

    private data class LineageFacts(val input: AnalysisInput?, val epoch: Long, val reasons: List<String>,
        val plan: ParentPlans.Stored?, val reviewFrontier: Long)
    private fun lineageFacts(c: Connection, binding: ClientBinding): LineageFacts {
        val input = latestInput(c, binding.clientId)
        val plan = plans.latest(c, binding.clientId)
        return LineageFacts(input, c.count("SELECT epoch FROM clients WHERE client_id=?", binding.clientId),
            lineageReasons(c, binding, input, plan) + plans.lineageReasons(c, binding.clientId), plan,
            c.count("SELECT next_review-1 FROM clients WHERE client_id=?", binding.clientId))
    }

    private fun gateReasons(c: Connection, binding: ClientBinding, saved: SavedCut, check: SnapshotCheck, facts: LineageFacts): List<String> = buildList {
        val epoch = c.count("SELECT epoch FROM clients WHERE client_id=?", binding.clientId)
        if (saved.cut.lineageEpoch != epoch) add("lineage_epoch_changed")
        if (facts.epoch != epoch) add("lineage_changed_during_gate_verification")
        if (saved.cut.generation != binding.generation || saved.cut.lineageId != binding.lineageId) add("lineage_binding_changed")
        if (saved.input != facts.input || saved.input != latestInput(c, binding.clientId)) add("latest_input_changed")
        if (saved.cut.sourceEpoch != check.sourceEpoch || saved.cut.analysisEpoch != check.analysisEpoch) add("authority_epoch_changed")
        if (saved.check != check) add("authority_fence_changed")
        addAll(facts.reasons)
        addAll(deliveryReasons(c, binding.clientId, saved.cut.requiredEventIds, saved.cut.eventFrontier))
        // Check expiry last, immediately before the guarded action, not before a possibly long DB wait.
        addAll(sourceReasons(saved.input, check))
        addAll(planSourceReasons(facts, check))
    }

    override fun close() {
        if (stopped.compareAndSet(false, true)) scheduler.close()
    }

    internal fun isStopped(): Boolean = stopped.get()
    internal fun ensureOpen() { if (stopped.get()) fail("service_closed", "Kernel service is closed") }

    internal fun validateInput(binding: ClientBinding, request: SubmitRequest) {
        identifier(request.patchId, "patch ID")
        identifier(request.idempotencyKey, "idempotency key")
        val input = request.input
        if (input.snapshot.ownerId != binding.ownerId || input.snapshot.workspaceId != binding.workspaceId || input.snapshot.worktreeId != binding.worktreeId) {
            fail("snapshot_scope_mismatch", "Snapshot does not belong to the bound owner/workspace/worktree")
        }
        if (!Digests.valid(input.snapshot.snapshotId)) fail("invalid_snapshot", "A sealed snapshot digest is required")
        if (input.snapshot.files.size > config.maxSnapshotFiles || input.snapshot.files.map { it.path }.distinct().size != input.snapshot.files.size) {
            fail("input_limit", "Snapshot file inventory is too large or duplicated")
        }
        var total = 0L
        input.snapshot.files.forEach {
            InputArchive.safeRelative(it.path)
            if (it.size < 0 || it.size > config.maxSnapshotBytes - total || !Digests.valid(it.digest)) fail("input_limit", "Snapshot metadata exceeds its size or digest bounds")
            total += it.size
        }
        if (input.requiredRuleIds.size > 50_000) fail("input_limit", "Required rule inventory is too large")
        input.requiredRuleIds.forEach { identifier(it, "rule ID") }
        input.changedPaths.forEach { InputArchive.safeRelative(it) }
        targetIdentifier(input.context.scope.target)
        listOf(input.context.scope.module, input.context.scope.sourceSet, input.context.scope.compilation)
            .forEach { identifier(it, "analysis context") }
    }

    internal fun preflightAdmission(c: Connection, clientId: String) {
        if (c.one("SELECT gate_state FROM clients WHERE client_id=?", clientId) { it.getString(1) } != "open") {
            fail("lineage_finalized", "A final action was already claimed for this client generation")
        }
        if (c.count("SELECT COUNT(*) FROM reviews") >= config.maxRetainedReviews) fail("ledger_full", "Retained review quota is full; no accepted patch was removed")
        if (pendingReviews(c, clientId) >= config.maxPendingPerClient) fail("client_queue_full", "Client's pending obligation limit is reached")
    }

    internal fun archiveBytesUsed(c: Connection): Long {
        val work = c.count("SELECT COALESCE(SUM(archive_bytes),0) FROM work")
        val declarations = c.count("SELECT COALESCE(SUM(archive_bytes),0) FROM produced_plan_admissions")
        val reservations = c.count("SELECT COALESCE(SUM(reserved_archive_bytes),0) FROM plan_slot_resolution_claims")
        if (work < 0 || declarations < 0 || reservations < 0 || declarations > Long.MAX_VALUE - work ||
            reservations > Long.MAX_VALUE - work - declarations) fail("archive_accounting_corrupt", "Retained archive totals overflowed")
        return work + declarations + reservations // Conservative cap remains charged, including actual ready K.
    }

    internal fun queuedCapacityUsed(c: Connection): Long = c.count("SELECT COUNT(*) FROM work WHERE state='queued'") +
        c.count("""SELECT COUNT(*) FROM produced_plan_admissions a WHERE NOT EXISTS(
            SELECT 1 FROM plan_slot_resolutions r WHERE r.plan_id=a.plan_id AND r.slot_id=a.consumer_slot_id)""")

    internal fun pendingReviews(c: Connection, clientId: String): Long = c.count("""SELECT COUNT(*) FROM review_executions r
        LEFT JOIN work w ON w.work_key=r.work_key WHERE r.client_id=? AND r.resolution_state<>'unavailable'
        AND (w.work_key IS NULL OR w.state IN ('queued','running') OR r.effective_result_json IS NULL)""", clientId)

    private fun idempotent(c: Connection, clientId: String, key: String, hash: String): ReviewStatus? {
        if (c.count("SELECT COUNT(*) FROM parent_plans WHERE client_id=? AND idempotency_key=?", clientId, key) != 0L) {
            fail("idempotency_conflict", "Idempotency key already belongs to an atomic parent plan")
        }
        val prior = c.one("SELECT review_id,request_hash FROM reviews WHERE client_id=? AND idempotency_key=?", clientId, key) {
            it.getString(1) to it.getString(2)
        } ?: return null
        if (prior.second != hash) fail("idempotency_conflict", "Idempotency key is already bound to a different request")
        return readStatus(c, prior.first, clientId)
    }

    internal fun readStatus(c: Connection, reviewId: String, clientId: String): ReviewStatus {
        producedPlans.requireReviewRelation(c, reviewId)
        return c.one("""SELECT r.*,w.state work_state,w.result_json,w.validation_json FROM review_executions r LEFT JOIN work w
            ON w.work_key=r.work_key WHERE r.review_id=? AND r.client_id=?""", reviewId, clientId, map = ::statusRow)
            ?: fail("review_not_found", "Review is not available to this client")
    }

    private fun statusRow(row: ResultSet): ReviewStatus {
        val input = ContractJson.decodeFromString<AnalysisInput>(row.getString("input_json"))
        val rawJson = row.getString("result_json")
        val effectiveJson = row.getString("effective_result_json")
        val result = (effectiveJson ?: rawJson)?.let { ContractJson.decodeFromString<ProviderResult>(it) }
        val validation = ContractJson.decodeFromString<List<String>>(row.getString("validation_json") ?: "[]") +
            ContractJson.decodeFromString<List<String>>(row.getString("resolution_reasons_json")) +
            (if (row.getString("kind") == "produced") listOf("produced_plan_preview_unqualified") else emptyList()) +
            (if (row.getString("resolution_state") == "deferred") listOf("produced_slot_deferred") else emptyList()) +
            ContractJson.decodeFromString<List<String>>(row.getString("policy_validation_json")) +
            evaluatorReasons(row.getString("policy_certificate")) +
            (if (rawJson != null && effectiveJson == null) listOf("policy_evaluation_missing") else emptyList()) +
            (if (effectiveJson != null && PolicyCertificate.decode(row.getString("policy_certificate"))?.state != PolicyCertificate.EVALUATED)
                listOf("policy_certificate_invalid") else emptyList())
        val cancelled = row.getInt("cancelled") != 0
        val state = when {
            cancelled -> "cancelled"
            row.getString("resolution_state") == "deferred" -> "deferred"
            row.getString("resolution_state") == "unavailable" -> "incomplete"
            row.getString("work_state") == null -> "incomplete"
            row.getString("work_state") != "complete" -> row.getString("work_state")
            validation.isNotEmpty() -> "incomplete"
            else -> "complete"
        }
        return ReviewStatus(row.getString("review_id"), row.getString("client_id"), row.getString("patch_id"),
            row.getLong("sequence"), input.snapshot.snapshotId, state, result,
            (validation + if (cancelled) listOf("client_cancelled_obligation_retained") else emptyList()).distinct(), cancelled)
    }

    private fun evaluatorReasons(encoded: String?): List<String> {
        val certificate = PolicyCertificate.decode(encoded) ?: return listOf("policy_evaluator_identity_unknown")
        return if (certificate.evaluatorIdentity != policyEvaluatorIdentity) listOf("policy_evaluator_mismatch") else emptyList()
    }

    internal fun classifyResult(c: Connection, binding: ClientBinding, reviewId: String, input: AnalysisInput,
                                raw: ProviderResult): Pair<ProviderResult, List<String>> {
        val encoded = c.one("SELECT policy_certificate FROM reviews WHERE review_id=? AND client_id=?", reviewId, binding.clientId) {
            it.getString(1)
        }
        val issues = evaluatorReasons(encoded)
        // In-flight raw work is shared independently of evaluator identity. A publisher must not
        // run its callback for a subscription admitted by a different evaluator, even for progress.
        return if (issues.isEmpty()) evaluateReadyPolicy(binding, input, raw)
            ?: (raw to listOf("captured_policy_not_ready")) else raw to issues
    }

    /** Null is recoverable missing preparation; arbitrary pure-callback failures are terminal. */
    private fun evaluateReadyPolicy(binding: ClientBinding, input: AnalysisInput, raw: ProviderResult): Pair<ProviderResult, List<String>>? = try {
        if (resultPolicy.isReady(binding, input)) evaluateResultPolicy(binding, input, raw) else null
    } catch (_: Exception) {
        raw to listOf("result_policy_unavailable")
    } catch (_: AssertionError) {
        raw to listOf("result_policy_unavailable")
    }

    private fun evaluateResultPolicy(binding: ClientBinding, input: AnalysisInput, raw: ProviderResult): Pair<ProviderResult, List<String>> {
        val rawJson = canonical(raw)
        // Retaining failure bytes is not enough: accounted dispositions bypass the finding gate.
        // These intrinsic signals need no catalog/config lookup under the publication transaction.
        // Keep them aligned with config's isProtectedFailure; registered protected rules remain the
        // trusted policy adapter's responsibility. This handles current results, not absence proof.
        fun currentExecutionFailure(finding: Finding): Boolean {
            val kinds = finding.evidence.map { it.kind.lowercase() }
            if (kinds.any { it in setOf("build_failure", "execution_failure", "compiler_failure", "test_failure", "worker_failure") }) return true
            if (finding.verification.any { it.state.lowercase() in setOf("failed", "error", "crashed") }) return true
            return finding.severity.lowercase() in setOf("error", "fatal") && (
                kinds.any { it in setOf("compiler_diagnostic", "build_result", "test_result", "execution_result") } ||
                    finding.providerId.lowercase().let { "compiler" in it || "native" in it || "build" in it || "runtime" in it }
                )
        }
        val issues = mutableListOf<String>()
        val transformed = try {
            // Never let mutable collections owned by a policy callback rewrite raw evidence.
            val detached = ContractJson.decodeFromString<ProviderResult>(rawJson)
            val proposed = ContractJson.decodeFromString<ProviderResult>(canonical(resultPolicy.apply(binding, input, detached)))
            val restored = proposed.copy(findings = proposed.findings.map { it.copy(disposition = "open") })
            if (raw.findings.any { it.disposition != "open" } || canonical(restored) != rawJson ||
                proposed.findings.any { it.disposition !in setOf("open", "accepted", "suppressed", "disabled", "baselined", "unavailable") ||
                    (it.disposition != "open" && currentExecutionFailure(it)) }) {
                issues += "result_policy_changed_protected_evidence"
                raw
            } else proposed
        } catch (_: Exception) {
            issues += "result_policy_unavailable"
            raw
        } catch (_: AssertionError) {
            issues += "result_policy_unavailable"
            raw
        }
        return transformed to issues
    }

    /** True means a durable change, including a new pending audit marker, not necessarily evaluation. */
    internal fun applyResultPolicy(c: Connection, binding: ClientBinding, reviewId: String, input: AnalysisInput, raw: ProviderResult): Boolean {
        if (isStopped()) return false
        // Never rewrite an evaluated result, adopt a legacy/unknown identity, or stamp this
        // evaluator onto another evaluator's admission. The same predicate fences the commit.
        if (c.count("""SELECT COUNT(*) FROM reviews WHERE review_id=? AND client_id=?
            AND effective_result_json IS NULL AND policy_certificate=?""", reviewId, binding.clientId, admittedPolicyCertificate) != 1L) return false
        val (transformed, issues) = evaluateReadyPolicy(binding, input, raw)
            ?: return markPolicyPending(c, binding, reviewId, "captured_policy_not_ready")
        val json = canonical(transformed)
        val digest = PolicyCertificate.effectiveDigest(policyEvaluatorIdentity, binding, input, Digests.sha256(canonical(raw)), Digests.sha256(json))
        val certificate = canonical(PolicyCertificate(policyEvaluatorIdentity, PolicyCertificate.EVALUATED, digest))
        if (isStopped()) return false
        return c.execute("""UPDATE reviews SET effective_result_json=?,policy_validation_json=?,policy_certificate=?
            WHERE review_id=? AND client_id=? AND effective_result_json IS NULL AND policy_certificate=?""",
            json, canonical(issues), certificate, reviewId, binding.clientId, admittedPolicyCertificate) == 1
    }

    private fun markPolicyPending(c: Connection, binding: ClientBinding, reviewId: String, reason: String): Boolean {
        if (isStopped()) return false
        val old = c.one("""SELECT policy_validation_json FROM reviews WHERE review_id=? AND client_id=?
            AND effective_result_json IS NULL AND policy_certificate=?""", reviewId, binding.clientId, admittedPolicyCertificate) {
            it.getString(1)
        } ?: return false
        // Only two fixed markers can accumulate. Alternating restore failures/not-ready states must
        // not grow the outbox on every retry; successful evaluation replaces these pending markers.
        val issues = ContractJson.decodeFromString<List<String>>(old)
        if (reason in issues || isStopped()) return false
        return c.execute("""UPDATE reviews SET policy_validation_json=? WHERE review_id=? AND client_id=?
            AND effective_result_json IS NULL AND policy_certificate=? AND policy_validation_json=?""",
            canonical((issues + reason).distinct().sorted()), reviewId, binding.clientId, admittedPolicyCertificate, old) == 1
    }

    /** One bounded pass on a dedicated lane, never under the scheduler heartbeat transaction.
     * Only IDs are batched; restoration and each raw-result publication have separate lifetimes. */
    internal fun recoverPolicyEvaluations(): Int {
        if (isStopped()) return 0
        val pending = ledger.transaction(::selectPolicyRecovery)
        for (id in pending) {
            if (isStopped()) break
            // The read closes before any adapter restoration I/O or config/source locks begin.
            val captured = ledger.read { c -> c.one("""SELECT r.work_key,r.input_json,client.binding_json FROM review_executions r
                JOIN work w ON w.work_key=r.work_key JOIN clients client ON client.client_id=r.client_id
                WHERE r.review_id=? AND w.provider_key=? AND w.state='complete' AND w.result_json IS NOT NULL
                    AND r.effective_result_json IS NULL AND r.policy_certificate=?""", id, providerKey, admittedPolicyCertificate) {
                PolicyRecovery(it.getString("work_key"), it.getString("binding_json"), it.getString("input_json"))
            } } ?: continue
            val binding = ContractJson.decodeFromString<ClientBinding>(captured.bindingJson)
            val input = ContractJson.decodeFromString<AnalysisInput>(captured.inputJson)
            val restored = try {
                resultPolicy.restoreCaptured(binding, input)
                true
            } catch (_: InterruptedException) {
                if (isStopped()) break
                false
            } catch (_: Exception) {
                false
            } catch (_: AssertionError) {
                false
            }
            if (isStopped()) break
            ledger.transaction { c ->
                if (isStopped()) return@transaction
                // Re-read and detach after restoration. The adapter cannot mutate an input or bind
                // a different consumer, evaluator, provider or raw work between the two phases.
                val raw = c.one("""SELECT w.result_json FROM review_executions r JOIN work w ON w.work_key=r.work_key
                    JOIN clients client ON client.client_id=r.client_id WHERE r.review_id=? AND r.work_key=?
                        AND r.input_json=? AND client.binding_json=? AND w.provider_key=? AND w.state='complete'
                        AND w.result_json IS NOT NULL AND r.effective_result_json IS NULL AND r.policy_certificate=?""",
                    id, captured.workKey, captured.inputJson, captured.bindingJson, providerKey, admittedPolicyCertificate) {
                    ContractJson.decodeFromString<ProviderResult>(it.getString(1))
                } ?: return@transaction
                val exactBinding = ContractJson.decodeFromString<ClientBinding>(captured.bindingJson)
                val exactInput = ContractJson.decodeFromString<AnalysisInput>(captured.inputJson)
                val changed = if (restored) applyResultPolicy(c, exactBinding, id, exactInput, raw)
                    else markPolicyPending(c, exactBinding, id, "captured_policy_restore_unavailable")
                if (changed) {
                    appendEvent(c, exactBinding, readStatus(c, id, exactBinding.clientId), "result")
                    c.execute("UPDATE clients SET epoch=epoch+1 WHERE client_id=?", exactBinding.clientId)
                }
            }
        }
        return pending.size
    }

    private fun selectPolicyRecovery(c: Connection): List<String> {
        if (isStopped()) return emptyList()
        val key = "policy_recovery_cursor:" + Digests.sha256(canonical(listOf(providerKey, policyEvaluatorIdentity)))
        val cursor = c.one("SELECT value FROM metadata WHERE key=?", key) { it.getString(1).toLongOrNull() } ?: 0L
        fun select(after: Boolean, limit: Int): List<Pair<Long, String>> = c.query("""SELECT r.rowid,r.review_id FROM review_executions r
            JOIN work w ON w.work_key=r.work_key WHERE r.rowid ${if (after) ">" else "<="} ?
                AND w.provider_key=? AND w.state='complete' AND w.result_json IS NOT NULL
                AND r.effective_result_json IS NULL AND r.policy_certificate=? ORDER BY r.rowid LIMIT ?""",
            cursor, providerKey, admittedPolicyCertificate, limit) { it.getLong(1) to it.getString(2) }
        val selected = select(after = true, limit = 32).toMutableList()
        if (selected.size < 32) selected += select(after = false, limit = 32 - selected.size)
        if (selected.isEmpty()) c.execute("DELETE FROM metadata WHERE key=?", key)
        else c.execute("INSERT OR REPLACE INTO metadata(key,value) VALUES(?,?)", key, selected.last().first.toString())
        // Advance on selection, including not-ready rows, and persist across short-lived services.
        // A crashed batch remains admitted and is revisited on wrap, never silently discharged.
        return selected.map { it.second }
    }

    private data class PolicyRecovery(val workKey: String, val bindingJson: String, val inputJson: String)

    internal fun appendEvent(c: Connection, binding: ClientBinding, status: ReviewStatus, kind: String) {
        val payload = canonical(status)
        val sequence = c.count("SELECT next_event FROM clients WHERE client_id=?", binding.clientId)
        c.execute("""INSERT INTO events(event_id,client_id,sequence,review_id,patch_id,snapshot_id,kind,
            delivery_required,payload_digest,payload,generation,created_at) VALUES(?,?,?,?,?,?,?,1,?,?,?,?)""",
            UUID.randomUUID().toString(), binding.clientId, sequence, status.reviewId, status.patchId,
            status.sourceSnapshotId, kind, Digests.sha256(payload), payload, binding.generation, System.currentTimeMillis())
        c.execute("UPDATE clients SET next_event=next_event+1 WHERE client_id=?", binding.clientId)
    }

    private fun eventRow(row: ResultSet) = ReviewEvent(row.getString("event_id"), row.getString("client_id"),
        row.getLong("sequence"), row.getString("review_id"), row.getString("patch_id"), row.getString("snapshot_id"),
        row.getString("kind"), row.getInt("delivery_required") != 0, row.getString("payload_digest"),
        row.getString("payload"), row.getLong("generation"))

    internal fun authenticate(c: Connection, credential: ClientCredential): ClientBinding =
        authenticate(c, credential.clientId, credential.token, adapter = false)
    internal fun authenticate(c: Connection, credential: AdapterCredential): ClientBinding =
        authenticate(c, credential.clientId, credential.token, adapter = true)
    private fun authenticate(c: Connection, clientId: String, token: String, adapter: Boolean): ClientBinding {
        val row = c.one("SELECT binding_json,${if (adapter) "adapter_hash" else "client_hash"} FROM clients WHERE client_id=?", clientId) {
            it.getString(1) to it.getString(2)
        }
        val expected = row?.second ?: Digests.sha256("invalid-capability")
        val valid = MessageDigest.isEqual(expected.toByteArray(), Digests.sha256(token).toByteArray())
        if (!valid || row == null) fail("unauthorized", "Capability does not authorize this operation")
        return ContractJson.decodeFromString(row.first)
    }

    private fun receiptEventIds(ids: List<String>): List<String> {
        if (ids.isEmpty() || ids.size > config.maxEventPageSize || ids.distinct().size != ids.size) {
            fail("invalid_receipt", "Receipt must name a nonempty bounded distinct event set")
        }
        ids.forEach { identifier(it, "event ID") }
        return ids.sorted()
    }
    private fun validateOwnedEvents(c: Connection, binding: ClientBinding, ids: List<String>) {
        ids.forEach { id ->
            if (c.count("SELECT COUNT(*) FROM events WHERE event_id=? AND client_id=? AND generation=?",
                    id, binding.clientId, binding.generation) != 1L) fail("event_not_found", "Receipt event is not in this client's bound generation")
        }
    }
    private fun recordReceipt(c: Connection, client: String, kind: String, id: String, json: String) {
        val existing = c.one("SELECT request_json FROM receipts WHERE client_id=? AND kind=? AND receipt_id=?", client, kind, id) { it.getString(1) }
        if (existing != null && existing != json) fail("receipt_conflict", "Receipt ID is bound to different evidence")
        c.execute("INSERT OR IGNORE INTO receipts(client_id,kind,receipt_id,request_json) VALUES(?,?,?,?)", client, kind, id, json)
    }

    @Serializable private data class Cursor(val clientId: String, val sessionId: String, val generation: Long, val after: Long)
    private fun encodeCursor(binding: ClientBinding, after: Long): String {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(canonical(Cursor(binding.clientId, binding.sessionId, binding.generation, after)).toByteArray())
        return payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(payload))
    }
    private fun decodeCursor(encoded: String, binding: ClientBinding): Long {
        try {
            if (encoded.length > 2048) throw IllegalArgumentException()
            val parts = encoded.split('.')
            if (parts.size != 2 || !MessageDigest.isEqual(mac(parts[0]), Base64.getUrlDecoder().decode(parts[1]))) throw IllegalArgumentException()
            val cursor = ContractJson.decodeFromString<Cursor>(String(Base64.getUrlDecoder().decode(parts[0]), Charsets.UTF_8))
            if (cursor.clientId != binding.clientId || cursor.sessionId != binding.sessionId || cursor.generation != binding.generation || cursor.after < 0) throw IllegalArgumentException()
            return cursor.after
        } catch (_: Exception) { fail("invalid_cursor", "Cursor is invalid or belongs to another client generation") }
    }
    private fun mac(value: String): ByteArray = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(cursorSecret, "HmacSHA256"))
    }.doFinal(value.toByteArray())

    private fun latestInput(c: Connection, clientId: String): AnalysisInput? = c.one(
        "SELECT input_json FROM reviews WHERE client_id=? ORDER BY sequence DESC LIMIT 1", clientId,
    ) { ContractJson.decodeFromString<AnalysisInput>(it.getString(1)) }
    private data class SavedCut(val cut: GateCut, val input: AnalysisInput?, val check: SnapshotCheck?, val consumed: Boolean)
    private fun loadCut(c: Connection, clientId: String, cutId: String): SavedCut = c.one(
        "SELECT * FROM gate_cuts WHERE cut_id=? AND client_id=?", cutId, clientId,
    ) { SavedCut(ContractJson.decodeFromString(it.getString("cut_json")),
        it.getString("input_json")?.let { text -> ContractJson.decodeFromString<AnalysisInput>(text) },
        it.getString("check_json")?.let { text -> ContractJson.decodeFromString<SnapshotCheck>(text) }, it.getInt("consumed") != 0) }
        ?: fail("gate_cut_not_found", "Gate cut is not available to this client")

    private fun <T> withSnapshotCheck(facts: LineageFacts, action: (SnapshotCheck) -> T): T {
        val input = facts.input
        if (input == null) return action(SnapshotCheck(false, reasons = listOf("no_review_input")))
        // Do not catch the callback: DB/auth/action failures must not execute it for a second time.
        val detached: (SnapshotCheck) -> T = { check -> action(ContractJson.decodeFromString(canonical(check))) }
        return if (facts.plan == null) snapshotGate.withCurrentSnapshot(input, detached)
        else snapshotGate.withCurrentSnapshots(facts.plan.definition.slots.asReversed().map { it.input }, detached)
    }

    private fun planSourceReasons(facts: LineageFacts, check: SnapshotCheck): List<String> = buildList {
        val plan = facts.plan ?: return@buildList
        val definition = plan.definition
        // A newer manual submission must not inherit an older plan's gate authority, even on the
        // same snapshot with identical scalar digests but changed compiler/classpath/policy inputs.
        if (facts.reviewFrontier != plan.frontier || facts.input?.snapshot != definition.slots.first().input.snapshot) add("current_parent_plan_missing")
        if (check.sourceBindingDigest != definition.sourceBindingDigest) add("plan_source_binding_authority_mismatch")
        if (check.generatedInputsDigest != definition.generatedInputsDigest) add("plan_generated_input_authority_mismatch")
        val required = definition.slots.filter { it.state == "pending" }.mapNotNull { it.identity }
        if (check.contextIdentities.map { it.context }.distinct().size != check.contextIdentities.size ||
            check.contextIdentities.toSet() != required.toSet()) add("plan_context_vector_authority_mismatch")
    }

    private fun sourceReasons(input: AnalysisInput?, check: SnapshotCheck): List<String> = buildList {
        if (input == null) { add("no_accepted_reviews"); return@buildList }
        if (!check.authoritative || !check.current) add("current_source_configuration_authority_unavailable")
        if (check.validUntilEpochMillis != null && System.currentTimeMillis() >= check.validUntilEpochMillis) add("configuration_authority_expired")
        if (check.snapshotId != input.snapshot.snapshotId) add("current_snapshot_mismatch")
        if (check.sourceEpoch == null || check.sourceEpoch < 0 || check.analysisEpoch.isNullOrBlank()) add("authority_epoch_unavailable")
        val context = input.context
        listOf("configuration" to (context.configurationDigest to check.configurationDigest),
            "model" to (context.modelDigest to check.modelDigest), "dependency" to (context.dependencyDigest to check.dependencyDigest),
            "toolchain" to (context.toolchainDigest to check.toolchainDigest)).forEach { (name, pair) ->
            if (pair.first == null || !Digests.valid(pair.first!!) || pair.first != pair.second) add("${name}_authority_mismatch_or_unknown")
        }
        if (!context.fullProject) add("full_project_scope_unproven")
        if (input.snapshot.scopeExclusions.isNotEmpty() || input.snapshot.unknownInputs.isNotEmpty()) add("source_scope_incomplete")
        addAll(check.reasons)
    }

    private data class ReviewRecord(val status: ReviewStatus, val input: AnalysisInput, val certificateValid: Boolean)
    internal fun lineageReasons(c: Connection, binding: ClientBinding, latest: AnalysisInput?, plan: ParentPlans.Stored?): List<String> {
        val records = c.query("""SELECT r.*,w.state work_state,w.result_json,w.validation_json FROM review_executions r LEFT JOIN work w
            ON w.work_key=r.work_key WHERE r.client_id=? ORDER BY r.sequence""", binding.clientId) {
            producedPlans.requireReviewRelation(c, it.getString("review_id"))
            val input = ContractJson.decodeFromString<AnalysisInput>(it.getString("input_json"))
            val executionInput = ContractJson.decodeFromString<AnalysisInput>(it.getString("execution_input_json"))
            val rawJson = it.getString("result_json")
            val effectiveJson = it.getString("effective_result_json")
            val raw = rawJson?.let { text -> ContractJson.decodeFromString<ProviderResult>(text) }
            val certificate = PolicyCertificate.decode(it.getString("policy_certificate"))
            val policyDigest = if (raw == null || effectiveJson == null || certificate?.state != PolicyCertificate.EVALUATED) null else
                PolicyCertificate.effectiveDigest(certificate.evaluatorIdentity, binding, input,
                    Digests.sha256(canonical(raw)), Digests.sha256(effectiveJson))
            val workCertificate = c.one("SELECT * FROM work_certificates WHERE work_key=?", it.getString("work_key")) { cert ->
                val expected = Digests.sha256(canonical(listOf(cert.getString("input_key"), cert.getString("effective_key"),
                    cert.getString("result_digest"), cert.getString("provider_key"))))
                cert.getString("certificate_id") == expected && cert.getString("input_key") == executionInput.analysisKey() &&
                    rawJson != null && cert.getString("result_digest") == Digests.sha256(rawJson) &&
                    cert.getString("provider_key") == providerKey
            } ?: false
            val relationValid = it.getString("kind") != "produced" || producedPlans.executionCertificateValid(c, it.getString("review_id"), executionInput)
            ReviewRecord(statusRow(it), input, workCertificate && relationValid && certificate?.evaluatorIdentity == policyEvaluatorIdentity &&
                policyDigest != null && policyDigest == certificate?.digest)
        }
        val reasons = mutableListOf<String>()
        if (records.isEmpty() || latest == null) return listOf("no_accepted_reviews")
        for (record in records) {
            record.status.reasons.filter { it in setOf("policy_evaluator_identity_unknown", "policy_evaluator_mismatch") }
                .forEach { reasons += "$it:${record.status.reviewId}" }
            when (record.status.state) {
                "queued", "running", "deferred" -> reasons += "review_pending:${record.status.reviewId}"
                "cancelled" -> reasons += "review_cancelled:${record.status.reviewId}"
                "complete" -> if (!record.certificateValid) reasons += "review_certificate_invalid:${record.status.reviewId}"
                else -> reasons += "review_incomplete:${record.status.reviewId}"
            }
        }
        val current = records.filter { record ->
            record.status.state == "complete" && record.certificateValid && record.input.snapshot.snapshotId == latest.snapshot.snapshotId &&
                (if (plan == null) record.input.context.configurationDigest == latest.context.configurationDigest &&
                    record.input.context.modelDigest == latest.context.modelDigest &&
                    record.input.context.dependencyDigest == latest.context.dependencyDigest &&
                    record.input.context.toolchainDigest == latest.context.toolchainDigest
                else plan.definition.slots.any { it.state == "pending" && it.identity == contextIdentity(record.input) }) &&
                record.input.context.fullProject && record.input.snapshot.scopeExclusions.isEmpty() && record.input.snapshot.unknownInputs.isEmpty()
        }
        val obligations = records.flatMap { record -> record.input.requiredRuleIds.map { record.input.context.scope to it } }.distinct()
        for ((context, rule) in obligations) {
            if (current.none { it.input.context.scope == context && rule in it.input.requiredRuleIds }) reasons += "current_coverage_missing:${context.id}:$rule"
        }
        val snapshots = records.associate { it.input.snapshot.snapshotId to it.input.snapshot.parentSnapshotId }
        fun descends(new: String, old: String): Boolean {
            var id: String? = new
            val visited = mutableSetOf<String>()
            while (id != null && visited.add(id)) {
                if (id == old) return true
                id = snapshots[id]
            }
            return false
        }
        // Historical findings are not replaced wholesale by whichever worker finished last. Only a
        // later full-scope complete certificate in the same context/rule and proven ancestry closes
        // absence; incomplete/cancelled/different-policy/different-context reports never resolve it.
        for (record in records) {
            for (finding in record.status.result?.findings.orEmpty()) {
                if (finding.disposition == "unavailable") {
                    reasons += "finding_unavailable:${finding.fingerprint}"
                    continue
                }
                if (finding.severity !in config.blockingSeverities) continue
                val currentConfiguration = if (plan == null) latest.context.configurationDigest else
                    plan.definition.slots.firstOrNull { it.identity?.context == record.input.context.scope }?.identity?.configurationDigest
                if (finding.disposition in setOf("accepted", "suppressed", "disabled", "baselined") &&
                    record.certificateValid && record.input.context.configurationDigest == currentConfiguration) continue
                val recheck = current.lastOrNull {
                    it.status.sequence >= record.status.sequence && it.input.context.scope == finding.context &&
                        finding.ruleId in it.input.requiredRuleIds &&
                        it.input.context.configurationDigest == record.input.context.configurationDigest &&
                        descends(it.input.snapshot.snapshotId, record.input.snapshot.snapshotId)
                }
                val unresolved = recheck == null || recheck.status.result!!.findings.any {
                    it.ruleId == finding.ruleId && it.context == finding.context && it.fingerprint == finding.fingerprint &&
                        it.disposition !in setOf("accepted", "suppressed", "disabled", "baselined")
                }
                if (unresolved) reasons += "open_finding:${finding.fingerprint}"
            }
        }
        return reasons.distinct().sorted()
    }

    private fun deliveryReasons(c: Connection, clientId: String, ids: List<String>, frontier: Long): List<String> {
        val reasons = mutableListOf<String>()
        val actual = mutableSetOf<String>()
        c.query("""SELECT e.event_id,a.event_id acked,
            EXISTS(SELECT 1 FROM observations o WHERE o.event_id=e.event_id AND o.client_id=e.client_id
                AND o.context_digest=a.context_digest) observed
            FROM events e LEFT JOIN acknowledgements a ON a.event_id=e.event_id AND a.client_id=e.client_id
            WHERE e.client_id=? AND e.sequence<=? AND e.delivery_required=1 ORDER BY e.sequence""", clientId, frontier) { row ->
            val id = row.getString("event_id")
            actual += id
            if (row.getString("acked") == null) reasons += "insertion_missing:$id"
            if (row.getInt("observed") == 0) reasons += "model_observation_missing:$id"
        }
        if (ids.toSet() != actual || ids.size != actual.size) reasons += "delivery_frontier_changed"
        return reasons
    }

}

internal inline fun <reified T> canonical(value: T): String = Digests.canonical(ContractJson.encodeToJsonElement(value))
internal fun identifier(value: String, name: String) {
    if (value.isBlank() || value.length > 512 || value.any { it == '\u0000' || it.isISOControl() }) fail("invalid_identifier", "$name is empty or invalid")
}
/** Exact empty is a target name, not a wildcard or a claim of JVM/model authority. */
internal fun targetIdentifier(value: String) {
    if (value.isNotEmpty()) identifier(value, "analysis target")
}
internal fun randomToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

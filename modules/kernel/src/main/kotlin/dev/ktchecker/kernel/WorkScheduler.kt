package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Fixed workers and two-level durable worktree/client fair queues, coordinated across JVMs.
 * A dispatch spends only its selected sponsor client's turn. Other subscribers receive shared
 * computation without losing priority for their own older work. Fairness bounds dispatch chances
 * among a finite set of eligible queues, not provider CPU seconds or submission latency.
 * A live hung provider retains its slot: a lease timeout NEVER spawns unlimited replacement work.
 * Cancellation is a shutdown/deadline token, not any one consumer's subscription cancellation.
 * External process hard limits/sandboxing are the provider host's separate responsibility.
 */
internal class WorkScheduler(private val service: KernelService) : AutoCloseable {
    private val ledger get() = service.ledger
    private val config get() = service.config
    private val instanceId = service.instanceId
    private val process = ProcessHandle.current()
    private val instanceStart = process.info().startInstant().orElse(null)?.toString()
    private val stopped = AtomicBoolean(false)
    private val active = ConcurrentHashMap<String, Claim>()
    private val workers = mutableListOf<Thread>()
    private var heartbeat: Thread? = null
    private var policyRecovery: Thread? = null
    private var producedResolver: Thread? = null
    private val resolving = AtomicBoolean(false)

    private data class Claim(
        val key: String,
        val original: AnalysisInput,
        val frozen: AnalysisInput,
        val attempt: Long,
        val deadline: Long,
        val startedNanos: Long,
        val sponsorClient: String,
        val produced: Boolean = false,
        val progress: ClaimProgress = ClaimProgress(),
    )

    /**
     * Observed diagnostics belong to the attempt, not to a rollbackable progress transaction.
     * Admission linearizes at the open check under the monitor. finish() ends the finite
     * callback window before owner teardown; invocations not yet admitted (even queued ones)
     * become no-ops, while every admitted invocation drains and contributes its observed faults.
     */
    private class ClaimProgress {
        private val open = AtomicBoolean(true)
        private val monitor = Any()
        private val faults = linkedSetOf<String>()
        private var finishedFaults: List<String>? = null

        fun accept(callback: (MutableSet<String>) -> Unit) = synchronized(monitor) {
            if (!open.get()) return@synchronized
            try { callback(faults) }
            catch (failure: Throwable) {
                // No accepted callback failure may disappear when its provider catches it,
                // including failures before SQL entry. Fatal errors are recorded, not handled.
                faults += "progress_publication_failed"
                throw failure
            }
        }

        fun finish() {
            // Close before waiting: queued/late callbacks cannot extend this attempt's lifetime.
            // An already accepted callback drains before teardown or any final publication.
            open.set(false)
            synchronized(monitor) { finishedFaults = faults.sorted() }
        }

        // Only the execution thread finishes/reads this snapshot. Final SQLite writers never
        // acquire the callback monitor, so a progress writer cannot invert their lock order.
        fun completedFaults(): List<String> = checkNotNull(finishedFaults)
    }

    fun start() {
        ledger.transaction { c ->
            c.execute("INSERT INTO instances(instance_id,pid,process_start,heartbeat) VALUES(?,?,?,?)",
                instanceId, process.pid(), instanceStart, System.currentTimeMillis())
            // Restore durable sponsors if needed within schema3; this is not a schema upgrade.
            // New admission creates these rows atomically with the individual subscription.
            c.execute("""INSERT OR IGNORE INTO fair_clients(client_id,fair_key,last_served)
                SELECT DISTINCT r.client_id,w.fair_key,
                    (SELECT CAST(value AS INTEGER) FROM metadata WHERE key='dispatch_counter')
                FROM review_executions r JOIN work w ON w.work_key=r.work_key""")
            recoverDead(c)
        }
        repeat(config.workerCount) { index ->
            workers += thread(name = "ktchecker-worker-$instanceId-$index", isDaemon = true) { workerLoop() }
        }
        heartbeat = thread(name = "ktchecker-heartbeat-$instanceId", isDaemon = true) {
            while (!stopped.get()) {
                try {
                    ledger.transaction { c ->
                        val now = System.currentTimeMillis()
                        c.execute("UPDATE instances SET heartbeat=? WHERE instance_id=?", now, instanceId)
                        c.execute("UPDATE work SET lease_until=? WHERE state='running' AND owner_instance=?", now + config.leaseMillis, instanceId)
                        recoverDead(c)
                    }
                } catch (_: SQLException) {
                    // No data is acknowledged or discarded while persistence is unavailable.
                }
                if (!sleep(config.heartbeatMillis)) break
            }
        }
        // Separate bounded resolution lane, never a work claim: per-worktree parallelism1
        // cannot deadlock an unresolved consumer behind its own completed producer.
        producedResolver = thread(name = "ktchecker-produced-resolver-$instanceId", isDaemon = true) {
            try {
                while (!stopped.get()) {
                    resolving.set(true)
                    val progressed = try { service.producedPlans.resolveNext() } catch (_: SQLException) { false }
                        finally { resolving.set(false) }
                    if (!sleep(if (progressed) maxOf(1L, config.pollMillis) else maxOf(config.pollMillis, 100L))) break
                }
            } finally {
                resolving.set(false)
                if (stopped.get()) closeIfQuiescent()
            }
        }
        // Policy-only recovery also runs with workerCount=0. Restoration cannot hold up raw-work
        // leases/heartbeats, and one lane cannot build an unbounded queue of duplicate retries.
        policyRecovery = thread(name = "ktchecker-policy-recovery-$instanceId", isDaemon = true) {
            while (!stopped.get()) {
                val selected = try { service.recoverPolicyEvaluations() } catch (_: SQLException) { 0 }
                if (stopped.get()) break
                val delay = if (selected == 0) maxOf(config.heartbeatMillis, 1_000L) else maxOf(config.heartbeatMillis, 50L)
                if (!sleep(delay)) break
            }
        }
    }

    private fun workerLoop() {
        try {
            while (!stopped.get()) {
                val claim = try { claim() } catch (_: SQLException) { null }
                if (claim == null) {
                    if (!sleep(config.pollMillis)) break
                    continue
                }
                active[claim.key] = claim
                try { execute(claim) } finally {
                    active.remove(claim.key)
                    // Providers may set the interrupt bit after cleaning up an owned child.
                    Thread.interrupted()
                }
            }
        } finally {
            if (stopped.get()) closeIfQuiescent()
        }
    }

    private fun claim(): Claim? = ledger.transaction { c ->
        if (stopped.get()) return@transaction null
        recoverDead(c)
        if (c.count("SELECT COUNT(*) FROM work WHERE state='running'") >= config.globalParallelism) return@transaction null
        var candidate = c.one("""SELECT w.*,r.client_id AS sponsor_client FROM work w
            JOIN fair_queues f ON f.fair_key=w.fair_key
            JOIN review_executions r ON r.work_key=w.work_key
            JOIN fair_clients client_queue ON client_queue.client_id=r.client_id AND client_queue.fair_key=w.fair_key
            WHERE w.state='queued' AND w.provider_key=? AND
                NOT EXISTS(SELECT 1 FROM plan_slot_resolutions x JOIN produced_plan_admissions a ON a.plan_id=x.plan_id
                    WHERE x.work_key=w.work_key AND a.origin_instance<>?) AND
                (SELECT COUNT(*) FROM work occupied WHERE occupied.state='running' AND occupied.fair_key=w.fair_key)<?
            ORDER BY f.last_served,client_queue.last_served,r.sequence,r.rowid,w.rowid LIMIT 1""", service.providerKey, instanceId, config.perWorktreeParallelism) {
            Claim(it.getString("work_key"), ContractJson.decodeFromString(it.getString("input_json")),
                ContractJson.decodeFromString(it.getString("frozen_json")), it.getLong("attempt") + 1,
                System.currentTimeMillis() + config.analysisTimeoutMillis, System.nanoTime(), it.getString("sponsor_client"))
        } ?: return@transaction null
        if (!service.producedPlans.mayClaim(c, candidate.key)) return@transaction null
        if (service.producedPlans.isProduced(c, candidate.key)) {
            val clock = service.producedPlans.clock(candidate.key) ?: return@transaction null
            candidate = candidate.copy(produced = true, deadline = System.currentTimeMillis() + clock.remainingMillis())
        }
        val now = System.currentTimeMillis()
        c.execute("""UPDATE work SET state='running',owner_instance=?,attempt=?,lease_until=?,deadline=?
            WHERE work_key=? AND state='queued'""", instanceId, candidate.attempt, now + config.leaseMillis, candidate.deadline, candidate.key)
        c.execute("UPDATE metadata SET value=CAST(value AS INTEGER)+1 WHERE key='dispatch_counter'")
        c.execute("""UPDATE fair_queues SET last_served=(SELECT CAST(value AS INTEGER) FROM metadata WHERE key='dispatch_counter')
            WHERE fair_key=(SELECT fair_key FROM work WHERE work_key=?)""", candidate.key)
        c.execute("""UPDATE fair_clients SET last_served=(SELECT CAST(value AS INTEGER) FROM metadata WHERE key='dispatch_counter')
            WHERE client_id=?""", candidate.sponsorClient)
        candidate
    }

    private fun execute(claim: Claim) {
        if (stopped.get()) { release(claim); return }
        var effective = claim.frozen
        var result: ProviderResult
        var preparationStarted = false
        var preparationCleanupFailed = false
        val operational = mutableListOf<String>()
        try {
            InputArchive.verifyFrozen(claim.frozen, config.maxSnapshotBytes)
            val output = service.root.resolve("outputs").resolve(claim.key.removePrefix("sha256:"))
                .resolve("attempt-${claim.attempt}-$instanceId")
            privateDirectory(output)
            effective = claim.frozen.copy(outputRoot = output.toString())
            preparationStarted = true
            val verifyFrozen = { InputArchive.verifyFrozen(claim.frozen, config.maxSnapshotBytes) }
            if (claim.produced) service.preparedInputObserver.preparedProduced(
                service.producedPlans.attempt(claim.key, claim.original, effective, claim.attempt, verifyFrozen))
            else service.preparedInputObserver.prepared(claim.original, effective, verifyFrozen)
            val token = CancellationToken { stopped.get() || deadlineExceeded(claim) }
            token.throwIfCancelled()
            result = if (service.provider is ProgressiveAnalysisProvider) {
                service.provider.analyzeProgressive(effective, token) { partial -> progress(claim, effective, partial) }
            } else service.provider.analyze(effective, token)
            InputArchive.verifyFrozen(claim.frozen, config.maxSnapshotBytes)
            if (deadlineExceeded(claim)) {
                operational += "analysis_deadline_exceeded"
                result = result.copy(status = "incomplete", reasons = (result.reasons + "analysis_deadline_exceeded").distinct())
            }
        } catch (_: java.util.concurrent.CancellationException) {
            operational += "analysis_cancelled_or_deadline_exceeded"
            result = failure("analysis_cancelled_or_deadline_exceeded")
        } catch (_: InterruptedException) {
            operational += "analysis_interrupted"
            result = failure("analysis_interrupted")
        } catch (e: Exception) {
            val reason = if (e is KernelException) e.code else "provider_exception"
            operational += reason
            result = failure(reason)
        } catch (_: AssertionError) {
            operational += "provider_assertion_failed"
            result = failure("provider_assertion_failed")
        } finally {
            // Providers need not propagate progress exceptions or join their callback threads.
            // Retain all accepted callback faults before the result can acquire a certificate.
            claim.progress.finish()
            // Coordinator/provider finally blocks cannot clean up a permit when preparation only
            // partly succeeds or cancellation prevents provider entry. Teardown is exact-attempt
            // scoped and never runs while a ledger/config/source lock is held.
            if (preparationStarted) {
                try { service.preparedInputObserver.finished(effective) }
                catch (_: Exception) { preparationCleanupFailed = true }
                catch (_: AssertionError) { preparationCleanupFailed = true }
            }
        }
        if (stopped.get()) { release(claim); return }
        var json = try { canonical(result) } catch (_: Exception) {
            operational += "provider_result_serialization_failed"
            result = failure("provider_result_serialization_failed")
            canonical(result)
        }
        if (json.toByteArray().size > config.maxResultBytes) {
            operational += "provider_result_limit_exceeded"
            result = failure("provider_result_limit_exceeded")
            json = canonical(result)
        }
        // Freeze all provider-owned collections before publishing to multiple consumers.
        result = ContractJson.decodeFromString(json)
        // A teardown error must not erase an earlier provider result, finding or execution
        // failure. Keep raw evidence intact and fail this attempt's broker validation closed.
        if (preparationCleanupFailed) operational += "prepared_input_cleanup_failed"
        operational += validateFindings(effective, result)
        if (result.providerId != service.providerId || result.providerVersion != service.providerVersion) operational += "provider_identity_mismatch"
        if (claim.produced) operational += service.producedPlans.operationalFaults(claim.key, claim.original, effective, result)
        val validation = validateFinal(effective, result) + if (preparationCleanupFailed) listOf("prepared_input_cleanup_failed") else emptyList()
        while (!stopped.get()) {
            try {
                complete(claim, effective, result, json, validation, operational.distinct().sorted())
                return
            } catch (_: SQLException) {
                // Preserve the execution reservation and retry durable publication; no new work is
                // launched by this thread until the result is either committed or safely requeued.
                if (!sleep(100)) break
            }
        }
        release(claim)
    }

    private fun progress(claim: Claim, effective: AnalysisInput, raw: ProviderResult) = claim.progress.accept { observedFaults ->
        if (stopped.get()) return@accept
        var partial = try { ContractJson.decodeFromString<ProviderResult>(canonical(raw)) }
            catch (fault: Throwable) {
                observedFaults += "progress_serialization_failed"
                // Provider-owned collections can throw AssertionError while being detached.
                // Other fatal errors keep propagating, but cannot erase the observed fault.
                if (fault !is Exception && fault !is AssertionError) throw fault
                failure("progress_serialization_failed")
            }
        val issues = validateFindings(effective, partial)
        if (partial.providerId != service.providerId || partial.providerVersion != service.providerVersion) {
            observedFaults += "progress_provider_identity_mismatch"
            partial = failure("progress_provider_identity_mismatch")
        } else if (issues.isNotEmpty()) {
            observedFaults += issues + "invalid_progress_evidence"
            partial = failure("invalid_progress_evidence").copy(reasons = issues + "invalid_progress_evidence")
        }
        var json = canonical(partial)
        if (json.toByteArray().size > config.maxResultBytes) {
            observedFaults += "progress_result_limit_exceeded"
            partial = failure("progress_result_limit_exceeded"); json = canonical(partial)
        }
        // Freeze the exact canonical payload once; provider-owned lists cannot diverge between the
        // durable progress journal and independently emitted per-client events.
        partial = ContractJson.decodeFromString<ProviderResult>(json)
        val bytes = json.toByteArray().size
        val digest = Digests.sha256(json)
        // Faults were recorded before SQL: an aborted progress/event INSERT also rolls back its work UPDATE.
        // Even if the provider swallows that exception, this claim can never certify it clean.
        try {
            ledger.transaction { c ->
                if (!owns(c, claim)) return@transaction
                if (observedFaults.isNotEmpty()) {
                    val old = c.one("SELECT progress_faults_json FROM work WHERE work_key=?", claim.key) {
                        ContractJson.decodeFromString<List<String>>(it.getString(1))
                    }.orEmpty()
                    c.execute("UPDATE work SET progress_faults_json=? WHERE work_key=?", canonical((old + observedFaults).distinct().sorted()), claim.key)
                }
                if (c.count("SELECT COUNT(*) FROM work_progress WHERE work_key=? AND payload_digest=?", claim.key, digest) != 0L) return@transaction
                val count = c.count("SELECT COUNT(*) FROM work_progress WHERE work_key=?", claim.key)
                val total = c.count("SELECT COALESCE(SUM(byte_count),0) FROM work_progress WHERE work_key=?", claim.key)
                if (count >= config.maxProgressEventsPerWork || bytes > config.maxProgressBytesPerWork - total) {
                    c.execute("UPDATE work SET progress_limited=1 WHERE work_key=?", claim.key)
                    return@transaction
                }
                c.execute("INSERT INTO work_progress(work_key,payload_digest,payload_json,sequence,byte_count) VALUES(?,?,?,?,?)",
                    claim.key, digest, json, count + 1, bytes)
                for ((reviewId, binding) in subscriptions(c, claim.key)) {
                    val status = service.readStatus(c, reviewId, binding.clientId)
                    val original = c.one("SELECT input_json FROM reviews WHERE review_id=?", reviewId) {
                        ContractJson.decodeFromString<AnalysisInput>(it.getString(1))
                    }!!
                    val (classified, policyIssues) = service.classifyResult(c, binding, reviewId, original, partial)
                    service.appendEvent(c, binding, status.copy(result = classified, reasons = status.reasons + policyIssues), "progress")
                    c.execute("UPDATE clients SET epoch=epoch+1 WHERE client_id=?", binding.clientId)
                }
            }
        } catch (failure: Throwable) {
            observedFaults += "progress_publication_failed"
            throw failure
        }
    }

    private fun complete(claim: Claim, effective: AnalysisInput, result: ProviderResult, json: String,
        validation: List<String>, operational: List<String>) = ledger.transaction { c ->
        if (!owns(c, claim)) return@transaction
        // On every publication retry, merge the drained claim snapshot with current durable
        // faults only after acquiring the writer. A rollback cannot erase either source.
        val progressFaults = (c.one("SELECT progress_faults_json FROM work WHERE work_key=?", claim.key) {
            ContractJson.decodeFromString<List<String>>(it.getString(1))
        }.orEmpty() + claim.progress.completedFaults()).distinct().sorted()
        val publicationFaults = progressFaults.toMutableList()
        if (c.count("SELECT progress_limited FROM work WHERE work_key=?", claim.key) != 0L) publicationFaults += "progress_delivery_limit_exceeded"
        // The exact claim still owns this writer transaction. Teardown, detachment/validation
        // and waiting for this writer do not renew its original execution budget. Keep raw
        // provider evidence intact; a failed publication fence makes every retained review
        // incomplete instead of certifying late/interrupted shared execution as successful.
        if (deadlineExceeded(claim)) publicationFaults += "analysis_deadline_exceeded"
        if (Thread.currentThread().isInterrupted) publicationFaults += "analysis_interrupted"
        val errors = validation + publicationFaults
        c.execute("""UPDATE work SET state='complete',result_json=?,validation_json=?,progress_faults_json=?,owner_instance=NULL,lease_until=NULL,deadline=NULL
            WHERE work_key=? AND owner_instance=? AND attempt=? AND state='running'""",
            json, canonical(errors.distinct().sorted()), canonical(progressFaults), claim.key, instanceId, claim.attempt)
        val inputKey = claim.original.analysisKey()
        val effectiveKey = effective.analysisKey()
        val resultDigest = Digests.sha256(json)
        val certificate = Digests.sha256(canonical(listOf(inputKey, effectiveKey, resultDigest, service.providerKey)))
        c.execute("""INSERT OR REPLACE INTO work_certificates(work_key,input_key,effective_key,result_digest,provider_key,certificate_id)
            VALUES(?,?,?,?,?,?)""", claim.key, inputKey, effectiveKey, resultDigest, service.providerKey, certificate)
        if (claim.produced) service.producedPlans.publish(c, claim.key, claim.attempt, claim.original, effective, resultDigest,
            (operational + publicationFaults).distinct().sorted())
        for ((reviewId, binding) in subscriptions(c, claim.key)) {
            val reviewInput = c.one("SELECT input_json FROM reviews WHERE review_id=?", reviewId) {
                ContractJson.decodeFromString<AnalysisInput>(it.getString(1))
            }!!
            service.applyResultPolicy(c, binding, reviewId, reviewInput, result)
            service.appendEvent(c, binding, service.readStatus(c, reviewId, binding.clientId), "result")
            c.execute("UPDATE clients SET epoch=epoch+1 WHERE client_id=?", binding.clientId)
        }
    }

    private fun validateFinal(input: AnalysisInput, result: ProviderResult): List<String> = buildList {
        // Unknown configuration reads permanently deny full completion, even if provider or
        // policy code returns a clean-looking aggregate. Raw provider bytes remain unchanged.
        addAll(observedExportReasons(input))
        if (result.providerId != service.providerId || result.providerVersion != service.providerVersion) add("provider_identity_mismatch")
        if (!result.completeFor(input)) add("required_coverage_incomplete_or_wrong_context")
        if (input.requiredRuleIds.isEmpty()) add("required_rule_plan_empty")
        if (input.requiredRuleIds.any { it !in service.supportedRules }) add("required_rule_unimplemented")
        val coverageKeys = result.coverage.map { it.context.id to it.ruleId }
        if (coverageKeys.distinct().size != coverageKeys.size) add("duplicate_coverage")
        for (coverage in result.coverage) {
            if (coverage.ruleId !in input.requiredRuleIds || coverage.context != input.context.scope || !coverage.required) add("coverage_outside_required_plan")
            if (!coverage.implemented) add("coverage_unimplemented:${coverage.ruleId}")
            if (!coverage.tested || !coverage.qualified) add("coverage_unqualified:${coverage.ruleId}")
            if (!coverage.successful()) add("coverage_unsuccessful:${coverage.ruleId}")
        }
        if (result.reasons.isNotEmpty()) add("provider_reported_incompleteness")
        if (result.rawDiagnostics.any { it.severity in setOf("error", "warning") }) add("unaccounted_raw_diagnostics")
        addAll(validateFindings(input, result))
    }.distinct().sorted()

    private fun validateFindings(input: AnalysisInput, result: ProviderResult): List<String> = buildList {
        val files = input.snapshot.files.associateBy { it.path }
        val fingerprints = mutableSetOf<String>()
        for (finding in result.findings) {
            if (finding.ruleId !in input.requiredRuleIds || finding.context != input.context.scope) add("finding_outside_required_plan")
            if (finding.providerId !in service.authorizedFindingProviders[finding.ruleId].orEmpty()) add("finding_provider_identity_mismatch")
            if (!Digests.valid(finding.fingerprint) || !fingerprints.add(finding.fingerprint)) add("finding_fingerprint_invalid")
            if (finding.severity !in setOf("error", "warning", "info", "hint") || finding.disposition != "open") add("finding_disposition_or_severity_untrusted")
            for (location in listOf(finding.location) + finding.relatedLocations) {
                if (files[location.path]?.digest != location.fileDigest || !Digests.valid(location.fileDigest)) add("finding_source_identity_mismatch")
                if (location.line < 1 || location.column < 0 || location.endLine < location.line || location.endColumn < 0 ||
                    (location.endLine == location.line && location.endColumn < location.column)) add("finding_location_invalid")
            }
        }
    }.distinct().sorted()

    private fun deadlineExceeded(claim: Claim): Boolean =
        if (claim.produced) !service.producedPlans.isCurrent(claim.key)
        else System.nanoTime() - claim.startedNanos >= config.analysisTimeoutMillis * 1_000_000

    private fun failure(reason: String): ProviderResult = ProviderResult(service.providerId, "incomplete", reasons = listOf(reason), providerVersion = service.providerVersion)

    private fun owns(c: Connection, claim: Claim): Boolean = c.count(
        "SELECT COUNT(*) FROM work WHERE work_key=? AND state='running' AND owner_instance=? AND attempt=?",
        claim.key, instanceId, claim.attempt,
    ) == 1L

    private fun subscriptions(c: Connection, key: String): List<Pair<String, ClientBinding>> = c.query(
        "SELECT r.review_id,c.binding_json FROM review_executions r JOIN clients c ON c.client_id=r.client_id WHERE r.work_key=? ORDER BY r.rowid", key,
    ) { it.getString(1) to ContractJson.decodeFromString<ClientBinding>(it.getString(2)) }

    private fun release(claim: Claim) {
        try {
            ledger.transaction { c ->
                if (owns(c, claim)) {
                    if (claim.produced) {
                        service.producedPlans.failWorkWithoutExecution(c, claim.key, "produced_service_stopped_no_replay", allowRunning = true)
                        return@transaction
                    }
                    c.execute("""UPDATE work SET state='queued',owner_instance=NULL,lease_until=NULL,deadline=NULL,attempt=attempt+1
                        WHERE work_key=? AND owner_instance=? AND attempt=?""", claim.key, instanceId, claim.attempt)
                    recoveryEvents(c, claim.key, "service_stopped_work_retained")
                }
            }
        } catch (_: SQLException) {
            // Disk/DB failure cannot justify discarding an accepted obligation or reclaiming live
            // execution. The process identity remains open; restart/death recovery will fence it.
        }
    }

    private data class InstanceRecord(val id: String, val pid: Long, val start: String?, val closed: Boolean)

    private fun recoverDead(c: Connection) {
        val candidates = c.query("SELECT instance_id,pid,process_start,closed FROM instances WHERE instance_id<>? AND closed=0", instanceId) {
            InstanceRecord(it.getString(1), it.getLong(2), it.getString(3), it.getInt(4) != 0)
        }
        for (candidate in candidates) {
            val id = candidate.id
            val pid = candidate.pid
            val expectedStart = candidate.start
            val closed = candidate.closed
            val handle = ProcessHandle.of(pid).orElse(null)
            val actualStart = handle?.info()?.startInstant()?.orElse(null)?.toString()
            val dead = closed || handle == null || !handle.isAlive ||
                (expectedStart != null && actualStart != null && expectedStart != actualStart)
            if (!dead) continue // heartbeat expiry alone is NOT proof the old execution stopped.
            val keys = c.query("SELECT work_key FROM work WHERE state='running' AND owner_instance=?", id) { it.getString(1) }
            val ordinary = keys.filterNot { service.producedPlans.isProduced(c, it) }
            ordinary.forEach { key ->
                c.execute("""UPDATE work SET state='queued',owner_instance=NULL,lease_until=NULL,deadline=NULL,attempt=attempt+1
                    WHERE work_key=? AND state='running' AND owner_instance=?""", key, id)
                recoveryEvents(c, key, "dead_process_work_recovered")
            }
            c.execute("UPDATE instances SET closed=1 WHERE instance_id=?", id)
        }
        service.producedPlans.retireClosedOrigins(c)
    }

    private fun recoveryEvents(c: Connection, key: String, reason: String) {
        for ((reviewId, binding) in subscriptions(c, key)) {
            val status = service.readStatus(c, reviewId, binding.clientId)
            service.appendEvent(c, binding, status.copy(reasons = status.reasons + reason), "recovered")
            c.execute("UPDATE clients SET epoch=epoch+1 WHERE client_id=?", binding.clientId)
        }
    }

    private fun closeIfQuiescent() {
        if (active.isNotEmpty() || resolving.get()) return
        try {
            ledger.transaction { c ->
                val outstanding = c.query("SELECT work_key FROM work WHERE state='running' AND owner_instance=?", instanceId) { it.getString(1) }
                // Only call after provider executions have ended, never on an expired live lease.
                outstanding.forEach { key ->
                    if (service.producedPlans.isProduced(c, key)) {
                        service.producedPlans.failWorkWithoutExecution(c, key, "produced_service_stopped_no_replay", allowRunning = true)
                    } else {
                        c.execute("""UPDATE work SET state='queued',owner_instance=NULL,lease_until=NULL,deadline=NULL,attempt=attempt+1
                            WHERE work_key=? AND state='running' AND owner_instance=?""", key, instanceId)
                        recoveryEvents(c, key, "service_stopped_work_retained")
                    }
                }
                c.execute("UPDATE instances SET closed=1 WHERE instance_id=?", instanceId)
                service.producedPlans.retireClosedOrigins(c)
            }
        } catch (_: SQLException) { }
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        heartbeat?.interrupt()
        policyRecovery?.interrupt()
        producedResolver?.interrupt()
        workers.forEach { it.interrupt() }
        val deadline = System.nanoTime() + 2_000_000_000L
        (workers + listOfNotNull(policyRecovery, heartbeat, producedResolver)).forEach { worker ->
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            if (remaining > 0 && worker != Thread.currentThread()) {
                try { worker.join(remaining) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
        }
        // A provider ignoring cancellation keeps its durable reservation until it actually exits.
        if (workers.none { it.isAlive } && producedResolver?.isAlive != true && active.isEmpty() && !resolving.get()) closeIfQuiescent()
    }

    private fun sleep(millis: Long): Boolean = try {
        Thread.sleep(millis)
        !stopped.get()
    } catch (_: InterruptedException) { !stopped.get() }
}

package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/** Real kernel/SQLite/archive/scheduler paths; ALL owner/provider/host authorities are synthetic. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class KernelProducedPlanIntegrationTest {
    @TempDir lateinit var temp: Path

    private fun code(expected: String, body: () -> Unit) = assertEquals(expected, assertFailsWith<KernelException>(block = body).code)
    private fun declarations(service: KernelService, plan: ParentPlanStatus) = service.ledger.read { c ->
        c.query("""SELECT r.review_id,r.sequence,r.input_json FROM plan_slots s JOIN reviews r ON r.review_id=s.review_id
            WHERE s.plan_id=? ORDER BY s.slot_id""", plan.planId) { Triple(it.getString(1), it.getLong(2), it.getString(3)) }
    }
    private fun retained(service: KernelService, client: ClientRegistration, accepted: ParentPlanStatus, f: ProducedLane1665Fixture): ParentPlanStatus {
        val current = service.planStatus(client.client, accepted.planId)
        assertEquals(accepted.slots.map { it.reviewId }, current.slots.map { it.reviewId })
        current.slots.forEach { assertEquals(f.rules, it.requiredRuleIds); service.status(client.client, it.reviewId) }
        assertTrue("produced_plan_preview_unqualified" in current.reasons)
        service.ledger.read { c -> assertEquals(2L, c.count("""SELECT COUNT(*) FROM reviews r JOIN plan_slots s ON s.review_id=r.review_id
            WHERE s.plan_id=? AND r.kind='produced' AND r.work_key IS NULL""", accepted.planId)) }
        return current
    }

    @Test fun wholeParentReservesBothReviewsAndLateAResolutionCannotRetargetLatestBOrItsCapturedSuppression() {
        val f = ProducedLane1665Fixture(temp.resolve("fixture")); val provider = f.Provider(); val fence = ProducedLane1665Fixture.Fence()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val pool = Executors.newSingleThreadExecutor()
        KernelService.open(temp.resolve("ledger"), provider, ProducedLane1665Fixture.CONFIG, snapshotGate = fence,
            planAuthority = f, preparedInputObserver = f.observer, resultPolicy = f.policy).use { service ->
            try {
                val client = service.registerClient(ProducedLane1665Fixture.binding("alpha"))
                val peer = service.registerClient(ProducedLane1665Fixture.binding("beta"))
                val a = f.definition(client.binding, "a"); fence.install(a)
                f.resolver[a.request.planRef] = { request ->
                    entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS))
                    ProducedSlotResolution.Ready(f.consumer(request))
                }
                val acceptedA = service.submitPlan(client.client, a.request)
                assertTrue(entered.await(5, TimeUnit.SECONDS), "An incomplete coverage parent must still resolve its producer publication")
                val aRows = declarations(service, acceptedA)
                assertEquals(listOf(1L, 2L), aRows.map { it.second })
                assertEquals(a.slots.map { canonical(it.input) }, aRows.map { it.third })
                assertNull(f.resolved(service, acceptedA.slots.last().reviewId))
                assertNull(service.status(client.client, acceptedA.slots.last().reviewId).result)
                assertEquals("incomplete", retained(service, client, acceptedA, f).state)
                val acceptance = f.events(service, client).single { it.kind == "plan_accepted" }
                assertEquals(acceptedA.slots.map { it.reviewId }, assertNotNull(
                    ContractJson.decodeFromString<ReviewStatus>(acceptance.payload).parentPlan).slots.map { it.reviewId })

                // B changes policy while A is mid-resolution. No callback may hold the SQL writer.
                val b = f.definition(client.binding, "b", "suppressed", a.slots.first().input.snapshot.snapshotId); fence.install(b)
                val acceptedB = pool.submit<ParentPlanStatus> { service.submitPlan(client.client, b.request) }.get(5, TimeUnit.SECONDS)
                assertEquals(listOf(3L, 4L), declarations(service, acceptedB).map { it.second })
                assertEquals(aRows, declarations(service, acceptedA))
                // The consumer MUST read retained D, not the now-changed original source tree.
                a.slots.first().input.snapshot.files.forEach { Files.writeString(Path.of(a.slots.first().input.materializedRoot).resolve(it.path), "changed after acceptance\n") }
                release.countDown()
                val c = f.definition(peer.binding, "peer-open")
                val acceptedC = service.submitPlan(peer.client, c.request)
                listOf(Triple(client, acceptedA, "open"), Triple(client, acceptedB, "suppressed"), Triple(peer, acceptedC, "open")).forEach { (owner, plan, disposition) ->
                    plan.slots.forEach { slot ->
                        val review = f.finished(service, owner, slot.reviewId)
                        assertEquals("incomplete", review.state)
                        assertEquals(listOf(disposition), assertNotNull(review.result).findings.map { it.disposition })
                        assertEquals(f.rules, review.result!!.coverage.map { it.ruleId })
                        assertTrue(review.result!!.coverage.all { !it.qualified })
                    }
                    assertEquals("incomplete", retained(service, owner, plan, f).state)
                }
                assertEquals(aRows, declarations(service, acceptedA))
                assertEquals(6, provider.calls.get()); assertEquals(6, f.typedAttempts.size)
                assertTrue(f.resolutionCalls.values.all { it.get() == 1 })
                service.ledger.read { connection ->
                    assertEquals(2L, connection.count("SELECT review_frontier FROM parent_plans WHERE plan_id=?", acceptedA.planId))
                    assertEquals(4L, connection.count("SELECT review_frontier FROM parent_plans WHERE plan_id=?", acceptedB.planId))
                    f.typedAttempts.forEach { attempt ->
                        connection.one("""SELECT r.input_json,r.execution_input_json,r.direct_work_key,r.work_key,r.effective_result_json,
                            r.policy_certificate,w.result_json,wc.input_key FROM review_executions r JOIN work w ON w.work_key=r.work_key
                            JOIN work_certificates wc ON wc.work_key=w.work_key WHERE r.review_id=?""", attempt.execution.reviewId) { row ->
                            val d = ContractJson.decodeFromString<AnalysisInput>(row.getString("input_json"))
                            assertNull(row.getString("direct_work_key")); assertEquals(attempt.workKey, row.getString("work_key"))
                            assertEquals(canonical(attempt.original), row.getString("execution_input_json"))
                            assertEquals(attempt.original.analysisKey(), row.getString("input_key")); assertNotEquals(d.analysisKey(), row.getString("input_key"))
                            val raw = row.getString("result_json"); val effective = row.getString("effective_result_json")
                            assertEquals(f.partial(attempt.effective), ContractJson.decodeFromString<ProviderResult>(raw))
                            val certificate = assertNotNull(PolicyCertificate.decode(row.getString("policy_certificate")))
                            assertEquals(PolicyCertificate.effectiveDigest(certificate.evaluatorIdentity, attempt.admission.binding, d,
                                Digests.sha256(raw), Digests.sha256(effective)), certificate.digest)
                        } ?: fail("Missing immutable D/execution/raw-policy relation")
                    }
                }
                assertTrue(f.policyInputs.all { (id, key) -> listOf(a, b, c).any { it.binding.clientId == id && it.slots.any { slot -> slot.input.analysisKey() == key } } })
                f.deliver(service, client); f.deliver(service, peer)
                val cut = service.prepareGate(client.client)
                assertEquals(b.slots.first().input.snapshot.snapshotId, cut.sourceSnapshotId)
                assertFalse(cut.ready); assertTrue("produced_plan_preview_unqualified" in cut.reasons)
                assertTrue("review_incomplete:${acceptedA.slots.last().reviewId}" in cut.reasons)
                assertTrue("parent_plan_incomplete:${acceptedA.planId}" in cut.reasons)
                val historical = assertNotNull(service.status(client.client, acceptedA.slots.last().reviewId).result).findings.single()
                assertTrue("open_finding:${historical.fingerprint}" in cut.reasons)
                assertFalse(cut.reasons.any { it.startsWith("insertion_missing:") || it.startsWith("model_observation_missing:") ||
                    it == "delivery_frontier_changed" }, cut.reasons.toString())
                val effects = AtomicInteger()
                val denied = service.completeGate(client.client, cut.cutId) { effects.incrementAndGet() }
                assertFalse(denied.allowed); assertFalse(denied.actionPerformed); assertEquals(0, effects.get())
                assertEquals(aRows, declarations(service, acceptedA))
            } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
        }
    }

    @Test fun twoClientsKeepDeferredQuotaAndCancellationWhileForeignBrokerCannotClaimAndRestartCannotReplay() {
        val f = ProducedLane1665Fixture(temp.resolve("fixture")); val config = ProducedLane1665Fixture.CONFIG.copy(workerCount = 0, maxPendingPerClient = 2, maxQueuedWork = 5)
        val root = temp.resolve("ledger"); lateinit var a: ClientRegistration; lateinit var b: ClientRegistration
        lateinit var pa: ParentPlanStatus; lateinit var pb: ParentPlanStatus
        var originalA = emptyList<Triple<String, Long, String>>()
        KernelService.open(root, f.Provider(), config, planAuthority = f, preparedInputObserver = f.observer, resultPolicy = f.policy).use { origin ->
            a = origin.registerClient(ProducedLane1665Fixture.binding("alpha")); b = origin.registerClient(ProducedLane1665Fixture.binding("beta"))
            val da = f.definition(a.binding, "a"); val db = f.definition(b.binding, "b")
            pa = origin.submitPlan(a.client, da.request); pb = origin.submitPlan(b.client, db.request); originalA = declarations(origin, pa)
            origin.ledger.read { c ->
                assertEquals(2L, origin.pendingReviews(c, a.binding.clientId)); assertEquals(2L, origin.pendingReviews(c, b.binding.clientId))
                assertEquals(4L, origin.queuedCapacityUsed(c))
                assertTrue(origin.archiveBytesUsed(c) > c.count("SELECT COALESCE(SUM(archive_bytes),0) FROM work"))
                assertEquals(4L, c.count("SELECT COUNT(*) FROM reviews")); assertEquals(2L, c.count("SELECT COUNT(*) FROM work"))
            }
            val extra = f.definition(a.binding, "too-many")
            code("client_queue_full") { origin.submitPlan(a.client, extra.request) }
            code("client_queue_full") { origin.submit(a.client, SubmitRequest("extra", "extra", da.slots.first().input)) }
            assertTrue(origin.cancel(b.client, pb.slots.last().reviewId).cancelled)
            assertEquals("cancelled", retained(origin, b, pb, f).state)
            assertEquals("pending", retained(origin, a, pa, f).state)
            val foreignProvider = f.Provider()
            KernelService.open(root, foreignProvider, config.copy(workerCount = 1), preparedInputObserver = f.observer, resultPolicy = f.policy).use { foreign ->
                foreign.registerClient(a); foreign.registerClient(b)
                val sentinel = foreign.registerClient(ProducedLane1665Fixture.binding("sentinel"))
                val ordinary = f.definition(sentinel.binding, "ordinary").slots.first().input
                f.finished(foreign, sentinel, foreign.submit(sentinel.client, SubmitRequest("ordinary", "ordinary", ordinary)).reviewId)
                assertEquals(1, foreignProvider.calls.get(), "Sentinel proves the foreign worker actually ran")
                foreign.ledger.read { c ->
                    assertEquals(1L, c.count("""SELECT COUNT(*) FROM work w JOIN plan_slot_resolutions r ON r.work_key=w.work_key
                        WHERE r.plan_id=? AND r.slot_id=? AND w.state='queued' AND w.attempt=0 AND w.owner_instance IS NULL""", pa.planId, ProducedLane1665Fixture.PRODUCER))
                }
                assertEquals(originalA, declarations(foreign, pa)); retained(foreign, a, pa, f)
                assertEquals(0, f.typedAttempts.size)
            }
        } // Only these newly owned synthetic services close; not an owner/compiler CLOSED assertion.
        val replay = f.Provider()
        KernelService.open(root, replay, config.copy(workerCount = 1), planAuthority = ReviewPlanAuthority.DENY,
            preparedInputObserver = f.observer, resultPolicy = f.policy).use { restarted ->
            restarted.registerClient(a); restarted.registerClient(b)
            f.await("Closed origin consumer did not become retained unavailable") { f.resolved(restarted, pa.slots.last().reviewId) == "unavailable" }
            assertEquals(originalA, declarations(restarted, pa))
            assertEquals("incomplete", retained(restarted, a, pa, f).state)
            assertEquals("cancelled", retained(restarted, b, pb, f).state)
            assertEquals(0, replay.calls.get()); assertEquals(0, f.typedAttempts.size)
            assertTrue(f.resolutionCalls.values.all { it.get() == 0 })
            assertFalse(restarted.prepareGate(a.client).ready)
            assertTrue(f.events(restarted, b).any { it.kind == "cancelled" && it.reviewId == pb.slots.last().reviewId })
        }
    }

    @Test fun authorizationAndOriginalClockFailBeforeAnyAcceptanceOrProducerPreparation() {
        for (mode in listOf("default-deny", "expired", "wrong-duration", "wrong-identity")) {
            val f = ProducedLane1665Fixture(temp.resolve(mode)); val clientBinding = ProducedLane1665Fixture.binding(mode)
            val definition = f.definition(clientBinding, mode); val original = f.clocks.getValue(definition.request.planRef)
            if (mode != "default-deny") f.clocks[definition.request.planRef] = ProducedPlanClock(original.ownerClock,
                if (mode == "wrong-identity") "another-original-budget" else original.identity,
                if (mode == "expired") System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(original.durationMillis + 1) else original.startedAtNanos,
                if (mode == "wrong-duration") original.durationMillis + 1 else original.durationMillis)
            val authority = if (mode == "default-deny") ReviewPlanAuthority { binding, request -> f.resolve(binding, request) } else f
            KernelService.open(temp.resolve("ledger-$mode"), f.Provider(), ProducedLane1665Fixture.CONFIG.copy(workerCount = 0),
                planAuthority = authority, preparedInputObserver = f.observer).use { service ->
                val client = service.registerClient(clientBinding)
                val expected = when (mode) { "default-deny" -> "produced_plan_authority_unavailable"; "expired" -> "produced_live_origin_or_budget_unavailable"; else -> "produced_plan_grant_mismatch" }
                code(expected) { service.submitPlan(client.client, definition.request) }
                service.ledger.read { c ->
                    for (table in listOf("parent_plans", "reviews", "plan_slots", "work", "events")) assertEquals(0L, c.count("SELECT COUNT(*) FROM $table"))
                    assertEquals(1L, c.count("SELECT next_review FROM clients")); assertEquals(1L, c.count("SELECT next_event FROM clients"))
                }
                assertEquals(0, f.producerPreparations.get())
            }
        }
        val clock = ProducedPlanClock(Any(), "boundary", 1_000_000, 5)
        assertTrue(clock.isExpiredAt(999_999)); assertFalse(clock.isExpiredAt(5_000_000)); assertTrue(clock.isExpiredAt(5_000_001))
    }

    @Test fun ordinaryObserverDoesNotAuthorizeTypedWorkAndScalarEqualPDoesNotBorrowAnEarlierTypedGrant() {
        val denied = ProducedLane1665Fixture(temp.resolve("sam")); val deniedProvider = denied.Provider(); val samCalls = AtomicInteger()
        KernelService.open(temp.resolve("sam-ledger"), deniedProvider, ProducedLane1665Fixture.CONFIG, planAuthority = denied,
            preparedInputObserver = PreparedInputObserver { _, _, _ -> samCalls.incrementAndGet() }).use { service ->
            val client = service.registerClient(ProducedLane1665Fixture.binding("sam")); val definition = denied.definition(client.binding, "sam")
            val plan = service.submitPlan(client.client, definition.request)
            val result = assertNotNull(denied.finished(service, client, plan.slots.first().reviewId).result)
            assertTrue("produced_plan_observer_unavailable" in result.reasons)
            denied.await("Default denied producer must retain unavailable consumer") { denied.resolved(service, plan.slots.last().reviewId) == "unavailable" }
            assertEquals(0, deniedProvider.calls.get()); assertEquals(0, samCalls.get()); retained(service, client, plan, denied)
        }
        val f = ProducedLane1665Fixture(temp.resolve("scalar")); val provider = f.Provider()
        // First-profile deadlock control: a single worker/tree slot must reach BOTH publications.
        KernelService.open(temp.resolve("scalar-ledger"), provider, ProducedLane1665Fixture.CONFIG.copy(workerCount = 1, perWorktreeParallelism = 1), planAuthority = f,
            preparedInputObserver = f.observer, resultPolicy = f.policy).use { service ->
            val client = service.registerClient(ProducedLane1665Fixture.binding("scalar")); val definition = f.definition(client.binding, "scalar")
            val plan = service.submitPlan(client.client, definition.request)
            plan.slots.forEach { f.finished(service, client, it.reviewId) }
            val producer = f.typedAttempts.single { it.execution.slotId == ProducedLane1665Fixture.PRODUCER }
            val scalar = service.submit(client.client, SubmitRequest("equal-P", "equal-P", producer.original))
            val result = assertNotNull(f.finished(service, client, scalar.reviewId).result)
            assertTrue("synthetic_scalar_borrow_denied" in result.reasons)
            assertEquals(2, provider.calls.get()); assertEquals(2, f.typedAttempts.size); assertEquals(1, f.ordinaryPreparations.get())
            service.ledger.read { c ->
                assertNotEquals(producer.workKey, c.one("SELECT work_key FROM reviews WHERE review_id=?", scalar.reviewId) { it.getString(1) })
                assertEquals(0L, c.count("SELECT COUNT(*) FROM plan_slot_resolutions WHERE review_id=?", scalar.reviewId))
            }
        }
    }

    @Test fun malformedReadyAndMismatchedDurableCertificateFailClosedWithoutLosingDeclaredReviews() {
        for (mode in listOf("slot", "source", "manifest")) {
            val f = ProducedLane1665Fixture(temp.resolve(mode)); val provider = f.Provider()
            KernelService.open(temp.resolve("ledger-$mode"), provider, ProducedLane1665Fixture.CONFIG, planAuthority = f,
                preparedInputObserver = f.observer).use { service ->
                val client = service.registerClient(ProducedLane1665Fixture.binding(mode)); val definition = f.definition(client.binding, mode)
                f.resolver[definition.request.planRef] = { request ->
                    val good = f.consumer(request)
                    ProducedSlotResolution.Ready(PreparedProducedExecution(if (mode == "slot") ProducedLane1665Fixture.PRODUCER else good.slotId,
                        if (mode == "source") good.input.copy(changedPaths = listOf("Main.kt")) else good.input, good.bindingDigest,
                        if (mode == "manifest") good.producedManifest!!.copy(producerBindingDigest = Digests.sha256("foreign-producer")) else good.producedManifest,
                        good.verifyForAdmission))
                }
                val plan = service.submitPlan(client.client, definition.request); val before = declarations(service, plan)
                f.await("Malformed Ready was not retained unavailable: $mode") { f.resolved(service, plan.slots.last().reviewId) == "unavailable" }
                assertEquals(before, declarations(service, plan)); assertEquals("incomplete", retained(service, client, plan, f).state)
                assertEquals(1, provider.calls.get()); assertEquals(1, f.resolutionCalls.getValue(definition.request.planRef).get())
                service.ledger.read { c -> assertEquals(1L, c.count("SELECT COUNT(*) FROM work")) }
                assertFalse(service.prepareGate(client.client).ready)
            }
        }
        val f = ProducedLane1665Fixture(temp.resolve("corrupt"))
        KernelService.open(temp.resolve("corrupt-ledger"), f.Provider(), ProducedLane1665Fixture.CONFIG.copy(workerCount = 0), planAuthority = f).use { service ->
            val client = service.registerClient(ProducedLane1665Fixture.binding("corrupt")); val definition = f.definition(client.binding, "corrupt")
            val plan = service.submitPlan(client.client, definition.request); val producer = plan.slots.first().reviewId
            val certificate = service.ledger.read { c -> assertNotNull(c.one("SELECT certificate_id FROM plan_slot_resolutions WHERE review_id=?", producer) { it.getString(1) }) }
            try {
                service.ledger.transaction { c -> c.execute("UPDATE plan_slot_resolutions SET certificate_id=? WHERE review_id=?", Digests.sha256("wrong-certificate"), producer) }
                code("produced_plan_corrupt") { service.status(client.client, producer) }
                code("produced_plan_corrupt") { service.planStatus(client.client, plan.planId) }
                code("produced_plan_corrupt") { service.prepareGate(client.client) }
                assertEquals(2, declarations(service, plan).size)
            } finally { service.ledger.transaction { c -> c.execute("UPDATE plan_slot_resolutions SET certificate_id=? WHERE review_id=?", certificate, producer) } }
        }
    }

    @Test fun foreignMalformedAndOversizedProgressRemainOperationalFaultsEvenAfterExactPartialFinalResult() {
        for (mode in listOf("foreign", "malformed", "oversized")) {
            val f = ProducedLane1665Fixture(temp.resolve(mode))
            val provider = f.Provider { input, emit ->
                if (input.context.scope.compilation == "main") {
                    val partial = f.partial(input)
                    emit(when (mode) {
                        "foreign" -> partial.copy(providerId = "foreign-provider")
                        "malformed" -> partial.copy(findings = partial.findings.map { it.copy(location = it.location.copy(fileDigest = Digests.sha256("foreign-source"))) })
                        else -> partial.copy(rawDiagnostics = listOf(RawDiagnostic("x".repeat(ProducedLane1665Fixture.CONFIG.maxResultBytes + 1))))
                    })
                }
            }
            KernelService.open(temp.resolve("ledger-$mode"), provider, ProducedLane1665Fixture.CONFIG, planAuthority = f,
                preparedInputObserver = f.observer).use { service ->
                val client = service.registerClient(ProducedLane1665Fixture.binding(mode)); val definition = f.definition(client.binding, mode)
                val plan = service.submitPlan(client.client, definition.request)
                f.await("Bad progress must deny consumer operation: $mode") { f.resolved(service, plan.slots.last().reviewId) == "unavailable" }
                val attempt = f.typedAttempts.single()
                assertEquals(f.partial(attempt.effective), f.finished(service, client, plan.slots.first().reviewId).result)
                service.ledger.read { c ->
                    val publication = assertNotNull(c.one("SELECT record_json FROM work_publications WHERE work_key=?", attempt.workKey) {
                        ContractJson.decodeFromString<ProducedWorkPublication>(it.getString(1)) })
                    assertTrue(publication.operationalFaults.isNotEmpty(), mode)
                    assertEquals(Digests.sha256(canonical(f.partial(attempt.effective))), publication.rawResultDigest)
                }
                assertEquals(0, f.resolutionCalls.getValue(definition.request.planRef).get()); assertEquals(1, provider.calls.get())
                retained(service, client, plan, f); assertFalse(service.prepareGate(client.client).ready)
            }
        }
    }

    @Test fun consumerReservationDeniesBeforeIoWhenFullAndIsNotReclaimedAfterFailedCallback() {
        for (fits in listOf(false, true)) {
            val name = if (fits) "callback-fails" else "no-fit"; val f = ProducedLane1665Fixture(temp.resolve(name))
            val config = if (fits) ProducedLane1665Fixture.CONFIG else ProducedLane1665Fixture.CONFIG.copy(maxArchiveBytes = 1024L * 1024)
            KernelService.open(temp.resolve("ledger-$name"), f.Provider(), config, planAuthority = f, preparedInputObserver = f.observer).use { service ->
                val client = service.registerClient(ProducedLane1665Fixture.binding(name)); val definition = f.definition(client.binding, name)
                f.resolver[definition.request.planRef] = { throw IllegalStateException("Synthetic callback failure; no output created") }
                val plan = service.submitPlan(client.client, definition.request)
                f.await("Consumer reservation failure not retained") { f.resolved(service, plan.slots.last().reviewId) == "unavailable" }
                val status = retained(service, client, plan, f)
                assertEquals(if (fits) 1 else 0, f.resolutionCalls.getValue(definition.request.planRef).get())
                if (!fits) {
                    assertTrue("produced_consumer_archive_capacity_unavailable" in status.slots.last().reasons)
                    assertFalse(Files.exists(temp.resolve("ledger-$name").resolve("produced-output").resolve(plan.planId)))
                }
                service.ledger.read { c ->
                    val archives = assertNotNull(c.one("SELECT archives_json FROM produced_plan_admissions WHERE plan_id=?", plan.planId) {
                        ContractJson.decodeFromString<List<DeclaredArchiveIdentity>>(it.getString(1)) })
                    val expected = if (fits) archives.single { it.slotId == ProducedLane1665Fixture.CONSUMER }.byteCount + 134_217_728L else 0L
                    assertEquals(expected, c.count("SELECT reserved_archive_bytes FROM plan_slot_resolution_claims WHERE plan_id=?", plan.planId))
                    val stored = c.count("SELECT COALESCE(SUM(archive_bytes),0) FROM produced_plan_admissions") + c.count("SELECT COALESCE(SUM(archive_bytes),0) FROM work")
                    assertEquals(stored + expected, service.archiveBytesUsed(c))
                    assertEquals(1L, c.count("SELECT COUNT(*) FROM work")); assertEquals(2L, c.count("SELECT COUNT(*) FROM reviews"))
                }
            }
        }
    }

    @Test fun schema2HistoryAndWalAreReadOnlyRejectedRatherThanMigratedOrReset() {
        Class.forName("org.sqlite.JDBC")
        for (wal in listOf(false, true)) {
            val root = temp.resolve(if (wal) "legacy-wal" else "legacy-delete"); Files.createDirectories(root)
            val url = "jdbc:sqlite:${root.resolve("ledger.sqlite").toUri()}"
            val owner = DriverManager.getConnection("$url?mode=rwc")
            try {
                if (wal) owner.createStatement().use { it.execute("PRAGMA journal_mode=WAL"); it.execute("PRAGMA wal_autocheckpoint=0") }
                owner.execute("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
                owner.execute("INSERT INTO metadata VALUES('schema_version','2')")
                owner.execute("CREATE TABLE retained_obligations(id TEXT PRIMARY KEY,payload TEXT NOT NULL)")
                owner.execute("INSERT INTO retained_obligations VALUES('required-history','must-not-be-discharged')")
                DriverManager.getConnection("$url?mode=ro").use { reader ->
                    reader.execute("BEGIN"); assertEquals("2", reader.one("SELECT value FROM metadata") { it.getString(1) })
                    owner.close() // Read-only last close retains WAL; no raw fd while JDBC owns locks.
                }
            } finally { owner.close() }
            fun bytes(): Map<String, ByteArray> = Files.list(root).use { files -> files.filter {
                it.fileName.toString() == "ledger.sqlite" || (it.fileName.toString() == "ledger.sqlite-wal" && Files.size(it) > 0)
            }.toList().associate { it.fileName.toString() to Files.readAllBytes(it) } }
            val before = bytes(); if (wal) assertTrue("ledger.sqlite-wal" in before)
            repeat(2) {
                code("unsupported_schema") { Ledger(root, ProducedLane1665Fixture.CONFIG.copy(workerCount = 0)) }
                val after = bytes(); assertEquals(before.keys, after.keys)
                before.forEach { (name, content) -> assertContentEquals(content, after.getValue(name), name) }
            }
            DriverManager.getConnection("$url?mode=ro").use { reader ->
                assertEquals("2", reader.one("SELECT value FROM metadata") { it.getString(1) })
                assertEquals("must-not-be-discharged", reader.one("SELECT payload FROM retained_obligations") { it.getString(1) })
            }
        }
    }
}

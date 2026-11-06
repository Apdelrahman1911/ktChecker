package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * UNRUN boundary controls authored against schema4 and raw-publication profile4.
 * SQLite, threads and files would be real when authorized to run; every provider result, coverage
 * flag, policy and source fence below is synthetic, not host/compiler/qualification evidence.
 * The profile3 cache is explicit historical DATA, not a migration or a claim of prior execution.
 */
@Timeout(40)
class FindingRetrievalAttachmentBoundaryTest {
    @TempDir lateinit var temp: Path
    private val config = KernelConfig(workerCount = 1, globalParallelism = 1, perWorktreeParallelism = 1,
        heartbeatMillis = 50, leaseMillis = 1_000, pollMillis = 5, analysisTimeoutMillis = 15_000)

    @Test fun mutatingMaterialEvidenceAfterConstructionBecomesDurableIncompleteNotADeadWorker() {
        finalMutation("evidence") { evidence, _ -> evidence.clear() }
    }

    @Test fun mutatingRetrievalLinksAfterConstructionBecomesDurableIncompleteNotADeadWorker() {
        finalMutation("attachment") { _, attachments ->
            attachments[0] = attachments.single().copy(digest = Digests.sha256("different unlinked report"))
        }
    }

    private fun finalMutation(name: String,
                              mutate: (MutableList<Evidence>, MutableList<EvidenceRetrievalAttachment>) -> Unit) {
        val badInput = input("$name-bad")
        val goodInput = input("$name-followup")
        val fence = SyntheticFence(badInput)
        val provider = Provider { effective, _ ->
            if (effective.snapshot.snapshotId == badInput.snapshot.snapshotId) {
                val material = mutableListOf(reportEvidence())
                val attachments = mutableListOf(reportAttachment())
                val finding = linkedFinding(effective, material, attachments)
                val result = complete(effective, listOf(finding))
                // Both DTO constructors succeeded while the link was valid. Mutate ONLY afterward;
                // reconstructing Finding here would exercise provider_exception, not final decode.
                assertTrue(result.completeFor(effective))
                mutate(material, attachments)
                result
            } else complete(effective)
        }
        KernelService.open(temp.resolve("ledger-$name"), provider, config, fence).use { service ->
            val client = service.registerClient(binding(name))
            val accepted = service.submit(client.client, request("$name-bad", badInput))
            val failed = await(service, client, accepted.reviewId)
            val result = assertNotNull(failed.result)
            assertEquals("incomplete", failed.state)
            assertEquals("incomplete", result.status)
            assertEquals(listOf("provider_result_deserialization_failed"), result.reasons)
            assertTrue(result.findings.isEmpty())
            assertTrue(result.coverage.isEmpty())
            assertFalse(result.completeFor(badInput))

            val work = published(service, failed.reviewId)
            assertReleased(work)
            assertEquals(1, work.attempt)
            assertEquals(encoded(result), work.resultJson, "Fallback object AND durable JSON must agree")
            assertEquals(result, ContractJson.decodeFromString<ProviderResult>(work.resultJson))
            val validation = ContractJson.decodeFromString<List<String>>(work.validationJson)
            assertTrue("required_coverage_incomplete_or_wrong_context" in validation)
            assertTrue("provider_reported_incompleteness" in validation)
            assertTrue(failed.reasons.containsAll(validation))
            // Invalid executions still receive audit certificates. Their existence is NOT success.
            val certificate = certificate(service, work.key)
            assertEquals(Digests.sha256(work.resultJson), certificate.resultDigest)
            assertEquals(service.providerKey, certificate.providerKey)
            assertEquals(failed, payload(resultEvent(service, client, failed.reviewId)))

            // Sole configured worker must remain usable after the rejected final DTO.
            fence.update(goodInput)
            val next = service.submit(client.client, request("$name-followup", goodInput))
            val finished = await(service, client, next.reviewId)
            assertEquals("complete", finished.state, finished.reasons.toString())
            assertTrue(assertNotNull(finished.result).completeFor(goodInput))
            assertEquals(2, provider.calls.get())
            assertNotEquals(work.key, published(service, next.reviewId).key)
            assertEquals(work, published(service, failed.reviewId), "A later claim cannot rewrite the failed audit row")
        }
    }

    @Test fun rejectedProgressDecodeRemainsDurableEvenWhenFinalProviderResultIsClean() {
        val original = input("progress")
        val calls = AtomicInteger()
        val provider = object : ProgressiveAnalysisProvider {
            override val id = PROVIDER
            override val version = VERSION
            override val supportedRuleIds = setOf(RULE)
            override fun analyzeProgressive(input: AnalysisInput, cancellation: CancellationToken,
                                            onProgress: (ProviderResult) -> Unit): ProviderResult {
                calls.incrementAndGet()
                cancellation.throwIfCancelled()
                val evidence = mutableListOf(reportEvidence())
                val finding = linkedFinding(input, evidence, mutableListOf(reportAttachment()))
                val partial = complete(input, listOf(finding)).copy(status = "partial")
                evidence.clear() // Invalidates an already-constructed partial DTO, before callback detachment.
                onProgress(partial)
                return complete(input) // Deliberately clean raw final; progress fault must not disappear.
            }
        }
        KernelService.open(temp.resolve("ledger-progress"), provider, config, SyntheticFence(original)).use { service ->
            val client = service.registerClient(binding("progress"))
            val accepted = service.submit(client.client, request("progress", original))
            val status = await(service, client, accepted.reviewId)
            val work = published(service, status.reviewId)
            val raw = ContractJson.decodeFromString<ProviderResult>(work.resultJson)
            assertReleased(work)
            assertEquals(1, calls.get())
            assertEquals("complete", raw.status)
            assertTrue(raw.completeFor(original), "Only the synthetic raw provider result is structurally complete")
            assertTrue(raw.reasons.isEmpty())
            assertEquals(raw, status.result)
            assertEquals("incomplete", status.state)
            assertTrue("progress_serialization_failed" in status.reasons)
            assertTrue("progress_serialization_failed" in ContractJson.decodeFromString<List<String>>(work.validationJson))
            assertEquals(listOf("progress_serialization_failed"),
                ContractJson.decodeFromString<List<String>>(work.progressFaultsJson))
            assertEquals(Digests.sha256(work.resultJson), certificate(service, work.key).resultDigest)

            val journal = service.ledger.read { c ->
                c.query("SELECT payload_json,payload_digest FROM work_progress WHERE work_key=? ORDER BY sequence", work.key) {
                    it.getString(1) to it.getString(2)
                }
            }.single()
            assertEquals(Digests.sha256(journal.first), journal.second)
            val rejected = ContractJson.decodeFromString<ProviderResult>(journal.first)
            assertEquals("incomplete", rejected.status)
            assertEquals(listOf("progress_serialization_failed"), rejected.reasons)
            assertTrue(rejected.findings.isEmpty())
            val progress = events(service, client).single { it.reviewId == status.reviewId && it.kind == "progress" }
            assertEquals(rejected, payload(progress).result)
            assertEquals(status, payload(resultEvent(service, client, status.reviewId)))
        }
    }

    @Test fun sharedRawAttachmentsSurviveIndependentConsumerPolicyAndFullEventBytes() {
        val original = input("shared")
        val material = mutableListOf(reportEvidence())
        val attachments = mutableListOf(reportAttachment())
        val expectedAttachments = attachments.toList()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val provider = Provider { effective, cancellation ->
            val result = complete(effective, listOf(linkedFinding(effective, material, attachments)))
            entered.countDown()
            assertTrue(release.await(10, TimeUnit.SECONDS), "Bounded synthetic provider release")
            cancellation.throwIfCancelled()
            result
        }
        val policy = ResultPolicy.versioned("test/retrieval-attachment/consumer-policy/1") { bound, _, raw ->
            raw.copy(findings = raw.findings.map {
                it.copy(disposition = if (bound.clientId == "consumer-a") "accepted" else "open")
            })
        }
        KernelService.open(temp.resolve("ledger-shared"), provider, config, SyntheticFence(original),
            resultPolicy = policy).use { service ->
            try {
                val a = service.registerClient(binding("consumer-a"))
                val b = service.registerClient(binding("consumer-b"))
                val ar = service.submit(a.client, request("shared-a", original))
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val br = service.submit(b.client, request("shared-b", original))
                release.countDown()
                val asr = await(service, a, ar.reviewId)
                val bsr = await(service, b, br.reviewId)
                assertEquals("complete", asr.state, asr.reasons.toString())
                assertEquals("complete", bsr.state, bsr.reasons.toString())
                assertEquals(1, provider.calls.get())
                val aFinding = assertNotNull(asr.result).findings.single()
                val bFinding = assertNotNull(bsr.result).findings.single()
                assertEquals("accepted", aFinding.disposition)
                assertEquals("open", bFinding.disposition)
                assertEquals(expectedAttachments, aFinding.retrievalAttachments)
                assertEquals(expectedAttachments, bFinding.retrievalAttachments)
                assertNotSame(aFinding, bFinding)
                assertNotSame(aFinding.retrievalAttachments, bFinding.retrievalAttachments)
                assertNotSame(attachments, aFinding.retrievalAttachments)

                val rawWork = published(service, ar.reviewId)
                assertEquals(rawWork, published(service, br.reviewId))
                val rawFinding = ContractJson.decodeFromString<ProviderResult>(rawWork.resultJson).findings.single()
                assertEquals("open", rawFinding.disposition)
                assertEquals(expectedAttachments, rawFinding.retrievalAttachments)
                val aEvent = resultEvent(service, a, ar.reviewId)
                val bEvent = resultEvent(service, b, br.reviewId)
                assertNotEquals(aEvent.eventId, bEvent.eventId)
                assertEquals(asr, payload(aEvent))
                assertEquals(bsr, payload(bEvent))
                assertEquals(expectedAttachments, assertNotNull(payload(aEvent).result).findings.single().retrievalAttachments)
                assertEquals(expectedAttachments, assertNotNull(payload(bEvent).result).findings.single().retrievalAttachments)

                // Publication has completed. Mutating the provider's original lists cannot rewrite
                // either consumer's captured policy view, shared raw row, or checksum-bound outbox.
                material.clear()
                attachments.clear()
                assertEquals(asr, service.status(a.client, ar.reviewId))
                assertEquals(bsr, service.status(b.client, br.reviewId))
                assertEquals(rawWork, published(service, ar.reviewId))
                assertEquals(aEvent, resultEvent(service, a, ar.reviewId))
                assertEquals(bEvent, resultEvent(service, b, br.reviewId))
            } finally { release.countDown() }
        }
    }

    @Test fun schema4Profile3CacheCannotCertifyProfile4AndHistoricalObligationsStayUnchanged() {
        val requested = input("profile-cache", noncanonical = true)
        val normalized = requested.copy(
            snapshot = requested.snapshot.copy(files = requested.snapshot.files.sortedBy { it.path }),
            requiredRuleIds = listOf(RULE), // Empty requested plan expands to this fixture provider's exact support.
            changedPaths = requested.changedPaths.distinct().sorted(),
        )
        assertNotEquals(requested.analysisKey(), normalized.analysisKey())
        val provider = Provider { effective, _ -> complete(effective, listOf(linkedFinding(effective))) }
        val oldProviderKey = profileKey("ktchecker/kernel/raw-publication-validation/3", provider)
        val newProviderKey = profileKey("ktchecker/kernel/raw-publication-validation/4", provider)
        val oldWorkKey = Digests.sha256(encoded(listOf(oldProviderKey, normalized.analysisKey())))
        val newWorkKey = Digests.sha256(encoded(listOf(newProviderKey, normalized.analysisKey())))
        assertNotEquals(oldWorkKey, newWorkKey)
        val ledgerRoot = temp.resolve("ledger-profile-cache")
        val oldReviewId = "synthetic-profile3-review"
        val oldRequest = request("profile3-history", normalized)
        val fence = SyntheticFence(requested)

        // Explicit fixture DATA in a schema4 ledger: no profile3 service, migration, historical
        // provider execution, capability rebinding or production override is used to make it.
        val historical = KernelService.open(ledgerRoot, provider, config.copy(workerCount = 0), fence).use { seed ->
            val client = seed.registerClient(binding("profile-cache"))
            seed.ledger.read { c -> assertEquals("4", c.one("SELECT value FROM metadata WHERE key='schema_version'") { it.getString(1) }) }
            val fairKey = Digests.sha256(encoded(listOf(client.binding.ownerId, client.binding.workspaceId, client.binding.worktreeId)))
            seed.archive.stage(oldWorkKey, normalized).use { staged ->
                val effective = staged.input.copy(outputRoot = temp.resolve("synthetic-historical-output").toString())
                val oldFinding = linkedFinding(effective).copy(retrievalAttachments = emptyList(),
                    evidence = listOf(reportEvidence().copy(artifactRef = "fixture://legacy/profile3-report")))
                val oldResult = complete(effective, listOf(oldFinding))
                val oldJson = encoded(oldResult)
                assertFalse("\"retrievalAttachments\"" in oldJson, "Legacy/default Finding bytes omit the new empty field")
                val inputKey = normalized.analysisKey()
                val effectiveKey = effective.analysisKey()
                val resultDigest = Digests.sha256(oldJson)
                val certificateId = Digests.sha256(encoded(listOf(inputKey, effectiveKey, resultDigest, oldProviderKey)))
                seed.ledger.transaction { c ->
                    // Initialize the retained OLD provider domain before its first work/review relation.
                    seed.ledger.admitProviderFairness(c, oldProviderKey, fairKey, client.binding.clientId)
                    staged.install()
                    c.execute("""INSERT INTO work(work_key,input_json,frozen_json,provider_key,fair_key,state,attempt,
                        result_json,validation_json,progress_faults_json,archive_bytes,created_at)
                        VALUES(?,?,?,?,?,'complete',1,?,'[]','[]',?,?)""", oldWorkKey, encoded(normalized),
                        encoded(staged.input), oldProviderKey, fairKey, oldJson, staged.bytes, 1L)
                    c.execute("""INSERT INTO work_certificates(work_key,input_key,effective_key,result_digest,provider_key,certificate_id)
                        VALUES(?,?,?,?,?,?)""", oldWorkKey, inputKey, effectiveKey, resultDigest, oldProviderKey, certificateId)
                    val sequence = c.count("SELECT next_review FROM clients WHERE client_id=?", client.binding.clientId)
                    c.execute("""INSERT INTO reviews(review_id,client_id,patch_id,idempotency_key,request_hash,request_json,
                        input_json,work_key,sequence,policy_certificate,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                        oldReviewId, client.binding.clientId, oldRequest.patchId, oldRequest.idempotencyKey,
                        Digests.sha256(encoded(oldRequest)), encoded(oldRequest), encoded(normalized), oldWorkKey,
                        sequence, seed.admittedPolicyCertificate, 1L)
                    c.execute("UPDATE clients SET next_review=next_review+1,epoch=epoch+1 WHERE client_id=?", client.binding.clientId)
                    assertTrue(seed.applyResultPolicy(c, client.binding, oldReviewId, normalized, oldResult))
                    val status = seed.readStatus(c, oldReviewId, client.binding.clientId)
                    assertEquals("complete", status.state, "Synthetic historical DATA must be a usable cache control")
                    seed.appendEvent(c, client.binding, status.copy(state = "accepted", result = null, reasons = emptyList()), "accepted")
                    seed.appendEvent(c, client.binding, status, "result")
                }
            }
            val rows = retainedRows(seed, oldWorkKey, oldReviewId, oldProviderKey)
            assertEquals(1, rows.getValue("work").size)
            assertEquals(1, rows.getValue("certificate").size)
            assertEquals(1, rows.getValue("review").size)
            assertEquals(2, rows.getValue("events").size)
            assertEquals(1, rows.getValue("provider_tree").size)
            assertEquals(1, rows.getValue("provider_client").size)
            Historical(client, rows, seed.status(client.client, oldReviewId))
        }
        assertEquals(0, provider.calls.get(), "The historical cache/obligation was seeded, not executed")
        KernelService.open(ledgerRoot, provider, config, fence).use { upgraded ->
            assertEquals(historical.rows, retainedRows(upgraded, oldWorkKey, oldReviewId, oldProviderKey))
            val oldStoredInput = upgraded.ledger.read { c ->
                assertEquals(1L, c.count("SELECT COUNT(*) FROM work"))
                assertEquals(0L, c.count("SELECT COUNT(*) FROM work WHERE work_key=?", newWorkKey),
                    "No pre-existing profile4 row may make this cache witness vacuous")
                assertNotNull(c.one("SELECT input_json FROM work WHERE work_key=?", oldWorkKey) { it.getString(1) })
            }
            assertEquals(encoded(normalized), oldStoredInput)
            assertEquals(normalized.analysisKey(), ContractJson.decodeFromString<AnalysisInput>(oldStoredInput).analysisKey())
            val newRequest = request("profile4-new-obligation", requested)
            assertNotEquals(oldRequest.patchId, newRequest.patchId)
            assertNotEquals(oldRequest.idempotencyKey, newRequest.idempotencyKey)
            val admitted = upgraded.submit(historical.client.client, newRequest)
            val status = await(upgraded, historical.client, admitted.reviewId)
            assertEquals(1, provider.calls.get(), "Without the profile fence the old complete row is reused with zero calls")
            assertEquals(newProviderKey, upgraded.providerKey)
            assertEquals("complete", status.state, status.reasons.toString())
            assertNotEquals(oldReviewId, status.reviewId)
            val newWork = published(upgraded, status.reviewId)
            assertEquals(newWorkKey, newWork.key)
            assertNotEquals(oldWorkKey, newWork.key)
            assertEquals(newProviderKey, newWork.providerKey)
            assertEquals("[]", newWork.validationJson)
            assertEquals(listOf(reportAttachment()), assertNotNull(status.result).findings.single().retrievalAttachments)
            val newCertificate = certificate(upgraded, newWork.key)
            assertEquals(newProviderKey, newCertificate.providerKey)
            assertEquals(normalized.analysisKey(), newCertificate.inputKey)
            assertEquals(Digests.sha256(newWork.resultJson), newCertificate.resultDigest)
            assertNotEquals(certificate(upgraded, oldWorkKey).certificateId, newCertificate.certificateId)
            upgraded.ledger.read { c ->
                assertEquals("4", c.one("SELECT value FROM metadata WHERE key='schema_version'") { it.getString(1) })
                assertEquals(encoded(normalized), c.one("SELECT input_json FROM reviews WHERE review_id=?", status.reviewId) { it.getString(1) })
                val newStoredInput = assertNotNull(c.one("SELECT input_json FROM work WHERE work_key=?", newWorkKey) { it.getString(1) })
                assertEquals(oldStoredInput, newStoredInput, "Exact stored normalized bytes differ only in the enclosing provider-domain work key")
                assertEquals(normalized.analysisKey(), ContractJson.decodeFromString<AnalysisInput>(newStoredInput).analysisKey())
                assertEquals(2L, c.count("SELECT COUNT(*) FROM work"))
                assertEquals(2L, c.count("SELECT COUNT(*) FROM work_certificates"))
                assertEquals(2L, c.count("SELECT COUNT(*) FROM reviews"))
            }
            assertEquals(historical.status, upgraded.status(historical.client.client, oldReviewId))
            assertEquals(historical.rows, retainedRows(upgraded, oldWorkKey, oldReviewId, oldProviderKey),
                "Old work/certificate/review/outbox/provider ages stay literal; no rebinding or historical rewrite")
        }
    }

    private fun input(name: String, noncanonical: Boolean = false): AnalysisInput {
        val root = temp.resolve("source-$name")
        Files.createDirectories(root)
        val main = "fun main() = Unit // $name\n"
        Files.writeString(root.resolve("Main.kt"), main)
        val mainFile = SnapshotFile("Main.kt", Digests.sha256(main), main.toByteArray().size.toLong())
        val files = if (noncanonical) {
            val z = "class Z // $name\n"
            Files.writeString(root.resolve("Z.kt"), z)
            listOf(SnapshotFile("Z.kt", Digests.sha256(z), z.toByteArray().size.toLong()), mainFile)
        } else listOf(mainFile)
        return AnalysisInput(SnapshotManifest(Digests.sha256("snapshot:$name"), "workspace", "tree", files,
            ownerId = "owner", declaredRoots = listOf(".")), root.toString(),
            AnalysisContext(modelDigest = Digests.sha256("synthetic-model"), dependencyDigest = Digests.sha256("synthetic-deps"),
                toolchainDigest = Digests.sha256("synthetic-toolchain"), configurationDigest = Digests.sha256("synthetic-config"),
                fullProject = true), if (noncanonical) emptyList() else listOf(RULE),
            changedPaths = if (noncanonical) listOf("Z.kt", "Main.kt", "Z.kt") else emptyList())
    }

    private fun reportEvidence() = Evidence(REPORT_KIND, "Synthetic report content identity", digest = REPORT_DIGEST)
    private fun reportAttachment() = EvidenceRetrievalAttachment(REPORT_KIND, REPORT_DIGEST, "fixture://report/attachment")
    private fun linkedFinding(input: AnalysisInput, evidence: List<Evidence> = listOf(reportEvidence()),
                              attachments: List<EvidenceRetrievalAttachment> = listOf(reportAttachment())) =
        Finding(RULE, "Synthetic finding", "Boundary control", "Fixture only",
            SourceLocation("Main.kt", input.snapshot.files.single { it.path == "Main.kt" }.digest),
            Digests.sha256("finding:${input.snapshot.snapshotId}:$RULE"), PROVIDER, context = input.context.scope,
            evidence = evidence, retrievalAttachments = attachments)

    private fun complete(input: AnalysisInput, findings: List<Finding> = emptyList()) = ProviderResult(PROVIDER, "complete", findings,
        input.requiredRuleIds.map { rule -> Coverage(rule, input.context.scope,
            if (findings.any { it.ruleId == rule }) "findings" else "passed", provenance = "executed",
            certificateId = Digests.sha256("synthetic-coverage:${input.analysisKey()}:$rule"),
            implemented = true, tested = true, qualified = true) }, providerVersion = VERSION)

    private class Provider(private val body: (AnalysisInput, CancellationToken) -> ProviderResult) : AnalysisProvider {
        override val id = PROVIDER
        override val version = VERSION
        override val supportedRuleIds = setOf(RULE)
        val calls = AtomicInteger()
        override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
            calls.incrementAndGet()
            cancellation.throwIfCancelled()
            return body(input, cancellation)
        }
    }

    /** A controlled source authority only; no test claims a live owner fence or terminal readiness. */
    private class SyntheticFence(initial: AnalysisInput) : SnapshotGate {
        private val lock = Any()
        private var current = initial
        private var epoch = 1L
        fun update(input: AnalysisInput) = synchronized(lock) { current = input; epoch++ }
        override fun <T> withCurrentSnapshot(input: AnalysisInput, action: (SnapshotCheck) -> T): T = synchronized(lock) {
            action(SnapshotCheck(current.snapshot.snapshotId == input.snapshot.snapshotId, current.snapshot.snapshotId, epoch,
                current.context.configurationDigest, current.context.modelDigest, current.context.dependencyDigest,
                current.context.toolchainDigest, "synthetic-attachment-fence:$epoch", authoritative = true))
        }
    }

    private fun binding(id: String) = ClientBinding("owner", "workspace", "tree", id, "lineage-$id", 1, "session-$id")
    private fun request(id: String, input: AnalysisInput) = SubmitRequest("patch-$id", "idempotency-$id", input)
    private inline fun <reified T> encoded(value: T): String = Digests.canonical(ContractJson.encodeToJsonElement(value))

    private fun profileKey(profile: String, provider: AnalysisProvider): String {
        val rules = provider.supportedRuleIds.toSortedSet().toList()
        val findingProviders = provider.supportedRuleIds.associateWith { setOf(provider.id) }
        return Digests.sha256(encoded(listOf(profile, provider.id, provider.version, encoded(rules),
            encoded(findingProviders.mapValues { it.value.sorted() }))))
    }

    private fun await(service: KernelService, client: ClientRegistration, reviewId: String): ReviewStatus {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var status = service.status(client.client, reviewId)
        while (status.result == null && System.nanoTime() < deadline) {
            Thread.sleep(5)
            status = service.status(client.client, reviewId)
        }
        assertNotNull(status.result, "Bounded wait did not publish a durable result for $reviewId")
        return status
    }

    private data class Published(val key: String, val providerKey: String, val state: String, val attempt: Int,
                                 val resultJson: String, val validationJson: String, val progressFaultsJson: String,
                                 val owner: String?, val lease: String?, val deadline: String?)
    private fun published(service: KernelService, reviewId: String): Published = service.ledger.read { c ->
        assertNotNull(c.one("SELECT w.* FROM reviews r JOIN work w ON w.work_key=r.work_key WHERE r.review_id=?", reviewId) {
            Published(it.getString("work_key"), it.getString("provider_key"), it.getString("state"), it.getInt("attempt"),
                it.getString("result_json"), it.getString("validation_json"), it.getString("progress_faults_json"),
                it.getString("owner_instance"), it.getString("lease_until"), it.getString("deadline"))
        })
    }
    private fun assertReleased(work: Published) {
        assertEquals("complete", work.state)
        assertNull(work.owner)
        assertNull(work.lease)
        assertNull(work.deadline)
    }
    private data class Certificate(val inputKey: String, val effectiveKey: String, val resultDigest: String,
                                   val providerKey: String, val certificateId: String)
    private fun certificate(service: KernelService, workKey: String): Certificate = service.ledger.read { c ->
        assertNotNull(c.one("SELECT * FROM work_certificates WHERE work_key=?", workKey) {
            Certificate(it.getString("input_key"), it.getString("effective_key"), it.getString("result_digest"),
                it.getString("provider_key"), it.getString("certificate_id"))
        })
    }

    private fun events(service: KernelService, client: ClientRegistration): List<ReviewEvent> {
        val result = mutableListOf<ReviewEvent>()
        var cursor: String? = null
        repeat(10) {
            val page = service.events(client.client, cursor, 10)
            result += page.events
            if (!page.hasMore) return result
            cursor = page.cursor
        }
        error("Synthetic fixture exceeded bounded event pagination")
    }
    private fun resultEvent(service: KernelService, client: ClientRegistration, reviewId: String): ReviewEvent =
        events(service, client).single { it.reviewId == reviewId && it.kind == "result" }
    private fun payload(event: ReviewEvent): ReviewStatus {
        assertEquals(Digests.sha256(event.payload), event.payloadDigest)
        val status = ContractJson.decodeFromString<ReviewStatus>(event.payload)
        assertEquals(encoded(status), event.payload, "Full payload checksum includes every nonempty retrieval attachment")
        assertEquals(event.reviewId, status.reviewId)
        assertEquals(event.clientId, status.clientId)
        return status
    }

    private data class Historical(val client: ClientRegistration, val rows: Map<String, List<List<String?>>>, val status: ReviewStatus)
    private fun rows(c: Connection, sql: String, vararg args: Any?): List<List<String?>> = c.query(sql, *args) { row ->
        (1..row.metaData.columnCount).map { row.getString(it) }
    }
    private fun retainedRows(service: KernelService, workKey: String, reviewId: String,
                             providerKey: String): Map<String, List<List<String?>>> = service.ledger.read { c ->
        linkedMapOf(
            "work" to rows(c, "SELECT * FROM work WHERE work_key=?", workKey),
            "certificate" to rows(c, "SELECT * FROM work_certificates WHERE work_key=?", workKey),
            "review" to rows(c, "SELECT * FROM reviews WHERE review_id=?", reviewId),
            "events" to rows(c, "SELECT * FROM events WHERE review_id=? ORDER BY sequence,event_id", reviewId),
            "provider_tree" to rows(c, "SELECT * FROM provider_fair_queues WHERE provider_key=? ORDER BY fair_key", providerKey),
            "provider_client" to rows(c, "SELECT * FROM provider_fair_clients WHERE provider_key=? ORDER BY client_id", providerKey),
        )
    }

    private companion object {
        const val PROVIDER = "synthetic-retrieval-attachment"
        const val VERSION = "attachment-boundary-fixture-1"
        const val RULE = "test/retrieval-attachment"
        const val REPORT_KIND = "synthetic_report"
        val REPORT_DIGEST = Digests.sha256("synthetic report bytes, not production evidence")
    }
}

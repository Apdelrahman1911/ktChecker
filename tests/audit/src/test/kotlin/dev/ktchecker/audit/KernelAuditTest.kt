package dev.ktchecker.audit

import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

private const val AUDIT_RULE_A = "audit/mechanical-a"
private const val AUDIT_RULE_B = "audit/mechanical-b"
private const val AUDIT_PROVIDER = "audit/mechanical-provider"
private const val AUDIT_PROVIDER_VERSION = "mechanics-only-1"

private fun auditKernelConfig(workers: Int) = KernelConfig(
    workerCount = workers, globalParallelism = 2, perWorktreeParallelism = 1,
    heartbeatMillis = 40, leaseMillis = 160, pollMillis = 5, analysisTimeoutMillis = 15_000,
)

/** These deliberately qualified cells test broker mechanics, not any actual diagnostic rule. */
private fun mechanicalResult(input: AnalysisInput) = ProviderResult(
    AUDIT_PROVIDER, "complete", coverage = input.requiredRuleIds.map {
        Coverage(it, input.context.scope, "passed", provenance = "executed",
            certificateId = Digests.sha256("TEST-ONLY:${input.analysisKey()}:$it"),
            implemented = true, tested = true, qualified = true)
    }, providerVersion = AUDIT_PROVIDER_VERSION,
)

private class MechanicalProvider(
    private val body: (AnalysisInput, CancellationToken) -> ProviderResult = { input, _ -> mechanicalResult(input) },
) : AnalysisProvider {
    override val id = AUDIT_PROVIDER
    override val version = AUDIT_PROVIDER_VERSION
    override val supportedRuleIds = setOf(AUDIT_RULE_A, AUDIT_RULE_B)
    val calls = AtomicInteger()
    override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
        calls.incrementAndGet()
        return body(input, cancellation)
    }
}

/** Controlled source/model authority: explicitly NOT a live capture or production host witness. */
private class MechanicalFence(initial: AnalysisInput) : SnapshotGate {
    private var current = initial
    private var epoch = 1L
    @Synchronized fun replace(input: AnalysisInput) { current = input; epoch++ }
    @Synchronized override fun <T> withCurrentSnapshot(input: AnalysisInput, action: (SnapshotCheck) -> T): T =
        action(SnapshotCheck(
            current = current.snapshot.snapshotId == input.snapshot.snapshotId,
            snapshotId = current.snapshot.snapshotId, sourceEpoch = epoch,
            configurationDigest = current.context.configurationDigest, modelDigest = current.context.modelDigest,
            dependencyDigest = current.context.dependencyDigest, toolchainDigest = current.context.toolchainDigest,
            analysisEpoch = "test-only-authority-$epoch", authoritative = true,
        ))
}

/** Actual SQLite, archives, concurrency and restart; providers/receipts/source authority are test doubles. */
class KernelAuditTest {
    @TempDir lateinit var temporary: Path

    private fun input(name: String, parent: String? = null): AnalysisInput {
        val source = temporary.resolve("source-$name")
        Files.createDirectories(source)
        val text = "val auditValue = 1\n"
        Files.writeString(source.resolve("Audit.kt"), text)
        return AnalysisInput(
            SnapshotManifest(Digests.sha256("audit-snapshot:$name:$parent:$text"), "project", "tree",
                listOf(SnapshotFile("Audit.kt", Digests.sha256(text), text.toByteArray().size.toLong())),
                ownerId = "audit-owner", parentSnapshotId = parent, declaredRoots = listOf(".")),
            source.toString(), AnalysisContext(
                modelDigest = Digests.sha256("test-model"), dependencyDigest = Digests.sha256("test-dependencies"),
                toolchainDigest = Digests.sha256("test-toolchain"), configurationDigest = Digests.sha256("test-policy"),
                fullProject = true, compilationFiles = listOf("Audit.kt")),
            requiredRuleIds = listOf(AUDIT_RULE_A),
        )
    }

    private fun binding(client: String) = ClientBinding("audit-owner", "project", "tree", client, "lineage-$client", sessionId = "session-$client")
    private fun request(input: AnalysisInput, patch: String = "local-patch-1") = SubmitRequest(patch, "idempotency-$patch", input)

    private fun terminal(service: KernelService, registration: ClientRegistration, reviewId: String): ReviewStatus {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        var status = service.status(registration.client, reviewId)
        while (status.result == null && System.nanoTime() < deadline) {
            Thread.sleep(5)
            status = service.status(registration.client, reviewId)
        }
        assertNotNull(status.result, "No durable final result: ${status.state}")
        return status
    }

    private fun events(service: KernelService, registration: ClientRegistration): List<ReviewEvent> = buildList {
        var cursor: String? = null
        var pages = 0
        do {
            assertTrue(++pages <= 100, "Event pagination failed to terminate")
            val page = service.events(registration.client, cursor, limit = 3)
            page.events.forEach { assertEquals(Digests.sha256(it.payload), it.payloadDigest) }
            addAll(page.events)
            cursor = page.cursor
        } while (page.hasMore)
    }

    /** These receipts attest a fixture only. No model request or real host is involved. */
    private fun acknowledge(service: KernelService, registration: ClientRegistration, observe: Boolean = true): List<ReviewEvent> =
        events(service, registration).also { events ->
            events.forEach { event ->
                val digest = Digests.sha256("not-a-model-context:${event.payloadDigest}")
                service.acknowledge(registration.adapter, listOf(event.eventId), ContextReceipt(
                    "ack-${event.eventId}", digest, "item-${event.eventId}", Instant.EPOCH.toString()))
                if (observe) service.observe(registration.adapter, listOf(event.eventId), ObservationReceipt(
                    "observe-${event.eventId}", "test-only-request-${event.eventId}", listOf(digest)))
            }
        }

    private fun code(expected: String, block: () -> Unit) = assertEquals(expected, assertFailsWith<KernelException>(block = block).code)

    @Test
    fun separateServicesKeepSharedWorkButIndependentCancellationReceiptsAndRestartedObligations() {
        val sealed = input("shared")
        val provider = MechanicalProvider { effective, token ->
            assertFalse(token.isCancelled(), "A single consumer must not cancel shared execution")
            mechanicalResult(effective)
        }
        val root = temporary.resolve("ledger")
        lateinit var a: ClientRegistration
        lateinit var b: ClientRegistration
        lateinit var ar: ReviewStatus
        lateinit var br: ReviewStatus
        var acceptedPayload = ""
        KernelService.open(root, provider, auditKernelConfig(0)).use { first ->
            KernelService.open(root, provider, auditKernelConfig(0)).use { second ->
                a = first.registerClient(binding("a"))
                b = second.registerClient(binding("b"))
                val pool = Executors.newFixedThreadPool(2)
                val barrier = CyclicBarrier(3)
                try {
                    val left = pool.submit<ReviewStatus> { barrier.await(10, TimeUnit.SECONDS); first.submit(a.client, request(sealed)) }
                    val right = pool.submit<ReviewStatus> { barrier.await(10, TimeUnit.SECONDS); second.submit(b.client, request(sealed)) }
                    barrier.await(10, TimeUnit.SECONDS)
                    ar = left.get(15, TimeUnit.SECONDS)
                    br = right.get(15, TimeUnit.SECONDS)
                } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
                assertNotEquals(ar.reviewId, br.reviewId)
                first.cancel(a.client, ar.reviewId)
                acceptedPayload = events(second, b).single().payload
            }
        }
        KernelService.open(root, provider, auditKernelConfig(1), MechanicalFence(sealed)).use { reopened ->
            assertEquals("complete", terminal(reopened, b, br.reviewId).state)
            assertEquals("cancelled", terminal(reopened, a, ar.reviewId).state)
            assertEquals(1, provider.calls.get(), "Two admitted reviews may share only the computation")
            assertEquals(acceptedPayload, events(reopened, b).single { it.kind == "accepted" }.payload)
            val aEvents = acknowledge(reopened, a)
            val bEvents = events(reopened, b)
            assertTrue(aEvents.map { it.eventId }.toSet().intersect(bEvents.map { it.eventId }.toSet()).isEmpty())
            code("review_not_found") { reopened.status(a.client, br.reviewId) }
            code("invalid_cursor") { reopened.events(b.client, reopened.events(a.client, limit = 1).cursor) }
            code("event_not_found") {
                reopened.acknowledge(a.adapter, listOf(bEvents.first().eventId), ContextReceipt("foreign", Digests.sha256("x"), "item", Instant.EPOCH.toString()))
            }
            code("unauthorized") {
                reopened.acknowledge(AdapterCredential(b.client.clientId, b.client.token), listOf(bEvents.first().eventId),
                    ContextReceipt("forged-role", Digests.sha256("x"), "item", Instant.EPOCH.toString()))
            }
            assertTrue(reopened.prepareGate(b.client).reasons.any { it.startsWith("model_observation_missing:") })
            acknowledge(reopened, b)
            val cut = reopened.prepareGate(b.client)
            assertTrue(cut.ready, cut.reasons.toString())
            assertTrue(reopened.completeGate(b.client, cut.cutId).allowed)
            assertTrue(reopened.prepareGate(a.client).reasons.any { it.startsWith("review_cancelled:") })
        }
    }

    @Test
    fun idempotentAdmissionAcrossServicesFreezesCallerCollectionsAndOriginalSourceBytes() {
        val original = input("owned")
        val files = original.snapshot.files.toMutableList()
        val rules = original.requiredRuleIds.toMutableList()
        val supplied = original.copy(snapshot = original.snapshot.copy(files = files), requiredRuleIds = rules)
        val observed = AtomicReference<String>()
        val provider = MechanicalProvider { effective, _ ->
            observed.set(Files.readString(Path.of(effective.materializedRoot).resolve("Audit.kt")))
            mechanicalResult(effective)
        }
        val root = temporary.resolve("ledger")
        lateinit var registration: ClientRegistration
        var reviewId = ""
        KernelService.open(root, provider, auditKernelConfig(0)).use { first ->
            KernelService.open(root, provider, auditKernelConfig(0)).use { second ->
                registration = first.registerClient(binding("a"))
                val barrier = CyclicBarrier(3)
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val jobs = listOf(first, second).map { service -> pool.submit<ReviewStatus> {
                        barrier.await(10, TimeUnit.SECONDS)
                        service.submit(registration.client, request(supplied))
                    } }
                    barrier.await(10, TimeUnit.SECONDS)
                    val outcomes = jobs.map { it.get(15, TimeUnit.SECONDS) }
                    assertEquals(1, outcomes.map { it.reviewId }.distinct().size)
                    reviewId = outcomes.first().reviewId
                } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
                assertEquals(1, events(first, registration).size)
            }
        }
        files.clear(); rules.clear()
        Files.writeString(Path.of(original.materializedRoot).resolve("Audit.kt"), "outside writer changed original\n")
        KernelService.open(root, provider, auditKernelConfig(1)).use { reopened ->
            val result = terminal(reopened, registration, reviewId)
            assertEquals("complete", result.state, result.reasons.toString())
            assertEquals(listOf(AUDIT_RULE_A), result.result!!.coverage.map { it.ruleId })
            assertEquals("val auditValue = 1\n", observed.get())
            assertEquals(1, provider.calls.get())
        }
    }

    @Test
    fun malformedUnqualifiedOrWrongContextResultsNeverDischargeTheRequiredPlan() {
        val variants: List<Pair<String, (ProviderResult) -> ProviderResult>> = listOf(
            "valid-control" to { it },
            "missing" to { it.copy(coverage = it.coverage.take(1)) },
            "duplicate" to { it.copy(coverage = listOf(it.coverage.first(), it.coverage.first())) },
            "wrong-context" to { it.copy(coverage = it.coverage.map { row -> row.copy(context = row.context.copy(sourceSet = "other")) }) },
            "optional-only" to { it.copy(coverage = it.coverage.map { row -> row.copy(required = false) }) },
            "unqualified" to { it.copy(coverage = it.coverage.map { row -> row.copy(qualified = false) }) },
            "empty-certificate" to { it.copy(coverage = it.coverage.map { row -> row.copy(certificateId = " ") }) },
            "wrong-provider-version" to { it.copy(providerVersion = "another-engine") },
            "raw-warning" to { it.copy(rawDiagnostics = listOf(RawDiagnostic("unaccounted warning", "warning"))) },
            "unexecuted" to { it.copy(coverage = it.coverage.map { row -> row.copy(provenance = "not_executed") }) },
        )
        for ((name, transform) in variants) {
            val sealed = input(name).copy(requiredRuleIds = listOf(AUDIT_RULE_A, AUDIT_RULE_B))
            val provider = MechanicalProvider { effective, _ -> transform(mechanicalResult(effective)) }
            KernelService.open(temporary.resolve("ledger-$name"), provider, auditKernelConfig(1), MechanicalFence(sealed)).use { service ->
                val registration = service.registerClient(binding("a"))
                val submitted = service.submit(registration.client, request(sealed))
                val final = terminal(service, registration, submitted.reviewId)
                val expected = name == "valid-control"
                assertEquals(if (expected) "complete" else "incomplete", final.state, "$name: ${final.reasons}")
                acknowledge(service, registration)
                val cut = service.prepareGate(registration.client)
                assertEquals(expected, cut.ready, "$name: ${cut.reasons}")
                var actions = 0
                val result = service.completeGate(registration.client, cut.cutId) { actions++ }
                assertEquals(expected, result.allowed, name)
                assertEquals(if (expected) 1 else 0, actions, name)
            }
        }
    }

    @Test
    fun newestContextDoesNotEraseHistoricalRuleContextObligations() {
        val old = input("old")
        val current = input("current", parent = old.snapshot.snapshotId)
        val otherScope = current.copy(context = current.context.copy(scope = RuleContext(target = "android", sourceSet = "androidMain")),
            requiredRuleIds = listOf(AUDIT_RULE_B))
        val fence = MechanicalFence(old)
        KernelService.open(temporary.resolve("ledger"), MechanicalProvider(), auditKernelConfig(1), fence).use { service ->
            val registration = service.registerClient(binding("a"))
            terminal(service, registration, service.submit(registration.client, request(old, "old")).reviewId)
            fence.replace(otherScope)
            terminal(service, registration, service.submit(registration.client, request(otherScope, "new-android")).reviewId)
            acknowledge(service, registration)
            val missing = service.prepareGate(registration.client)
            assertFalse(missing.ready)
            assertTrue(missing.reasons.any { it.startsWith("current_coverage_missing:") && it.endsWith(AUDIT_RULE_A) }, missing.reasons.toString())
            fence.replace(current)
            terminal(service, registration, service.submit(registration.client, request(current, "new-main")).reviewId)
            acknowledge(service, registration)
            val complete = service.prepareGate(registration.client)
            assertTrue(complete.ready, complete.reasons.toString())
        }
    }

    @Test
    fun receiptTrafficDoesNotExtendTheFrontierButAuthorityAbaInvalidatesAnOldCut() {
        val sealed = input("frontier")
        val fence = MechanicalFence(sealed)
        KernelService.open(temporary.resolve("ledger"), MechanicalProvider(), auditKernelConfig(1), fence).use { service ->
            val registration = service.registerClient(binding("a"))
            terminal(service, registration, service.submit(registration.client, request(sealed)).reviewId)
            val initial = events(service, registration)
            val cut = service.prepareGate(registration.client)
            acknowledge(service, registration, observe = false)
            val inserted = service.completeGate(registration.client, cut.cutId)
            assertFalse(inserted.allowed)
            assertTrue(inserted.reasons.any { it.startsWith("model_observation_missing:") })
            acknowledge(service, registration)
            assertEquals(initial, events(service, registration), "Receipt/control traffic must not recursively create delivery obligations")
            fence.replace(sealed.copy(context = sealed.context.copy(configurationDigest = Digests.sha256("changed-policy"))))
            fence.replace(sealed)
            val stale = service.completeGate(registration.client, cut.cutId)
            assertFalse(stale.allowed, "Byte equality after authority ABA must not revive the old cut")
            assertTrue(stale.reasons.any { it.startsWith("authority_") }, stale.reasons.toString())
            val fresh = service.prepareGate(registration.client)
            assertTrue(fresh.ready, fresh.reasons.toString())
            assertEquals(cut.eventFrontier, fresh.eventFrontier)
            assertEquals(cut.requiredEventIds, fresh.requiredEventIds)
            assertTrue(service.completeGate(registration.client, fresh.cutId).allowed)
        }
    }

    @Test
    fun damagedDurableWorkCertificateCannotBeReplacedByACompleteStatusLabel() {
        val sealed = input("certificate")
        val root = temporary.resolve("ledger")
        KernelService.open(root, MechanicalProvider(), auditKernelConfig(1), MechanicalFence(sealed)).use { service ->
            val registration = service.registerClient(binding("a"))
            val review = service.submit(registration.client, request(sealed))
            assertEquals("complete", terminal(service, registration, review.reviewId).state)
            acknowledge(service, registration)
            assertTrue(service.prepareGate(registration.client).ready)
            // Deliberate stored-evidence corruption, not a claimed same-UID security boundary.
            DriverManager.getConnection("jdbc:sqlite:${root.resolve("ledger.sqlite")}").use { db ->
                db.prepareStatement("UPDATE work_certificates SET input_key=?").use { statement ->
                    statement.setString(1, Digests.sha256("different-admission"))
                    assertEquals(1, statement.executeUpdate())
                }
            }
            val rejected = service.prepareGate(registration.client)
            assertFalse(rejected.ready)
            assertTrue(rejected.reasons.any { it.startsWith("review_certificate_invalid:") }, rejected.reasons.toString())
            assertFalse(service.completeGate(registration.client, rejected.cutId).allowed)
        }
    }

    @Test
    fun externalActionExceptionCannotReplayTheCutOrMintANewOneAfterRestart() {
        val sealed = input("action")
        val fence = MechanicalFence(sealed)
        val root = temporary.resolve("ledger")
        val effects = AtomicInteger()
        lateinit var registration: ClientRegistration
        var cutId = ""
        KernelService.open(root, MechanicalProvider(), auditKernelConfig(1), fence).use { service ->
            registration = service.registerClient(binding("a"))
            terminal(service, registration, service.submit(registration.client, request(sealed)).reviewId)
            acknowledge(service, registration)
            val cut = service.prepareGate(registration.client)
            assertTrue(cut.ready, cut.reasons.toString())
            cutId = cut.cutId
            // The callback might have externally acted before losing its return path.
            runCatching { service.completeGate(registration.client, cutId) {
                effects.incrementAndGet()
                throw IllegalStateException("test-only external action outcome is unknown")
            } }
            assertEquals(1, effects.get())
        }
        KernelService.open(root, MechanicalProvider(), auditKernelConfig(1), fence).use { reopened ->
            val replay = runCatching { reopened.completeGate(registration.client, cutId) { effects.incrementAndGet() } }
            assertTrue(replay.isFailure || replay.getOrThrow().allowed.not())
            val replacement = runCatching { reopened.prepareGate(registration.client) }
            if (replacement.isSuccess) {
                assertFalse(replacement.getOrThrow().ready)
                runCatching { reopened.completeGate(registration.client, replacement.getOrThrow().cutId) { effects.incrementAndGet() } }
            }
            assertEquals(1, effects.get(), "Outcome-unknown must not authorize another external effect")
        }
    }

    @Test
    fun abruptWorkerJvmDeathRecoversTheAcceptedPatchFromArchivedBytes() {
        val sealed = input("crash")
        val root = temporary.resolve("ledger")
        val inputFile = temporary.resolve("child-input.json")
        val registrationFile = temporary.resolve("child-registration.json")
        val reviewFile = temporary.resolve("child-review.json")
        val started = temporary.resolve("provider-started")
        val crash = temporary.resolve("crash-now")
        Files.writeString(inputFile, ContractJson.encodeToString(sealed))
        val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val classpath = requireNotNull(System.getProperty("ktchecker.audit.classpath"))
        val child = ProcessBuilder(java.toString(), "-Xmx64m", "-XX:MaxMetaspaceSize=96m", "-cp", classpath,
            KernelAuditChild::class.java.name, root.toString(), inputFile.toString(), registrationFile.toString(), reviewFile.toString(),
            started.toString(), crash.toString()).redirectErrorStream(true).start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while ((!Files.exists(started) || !Files.exists(reviewFile)) && child.isAlive && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue(Files.exists(started) && Files.exists(reviewFile), "Child did not reach admitted running work")
            Files.writeString(crash, "halt without service close")
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "Child JVM did not terminate")
            assertEquals(73, child.exitValue(), child.inputStream.bufferedReader().readText())
            val registration = ContractJson.decodeFromString<ClientRegistration>(Files.readString(registrationFile))
            val review = ContractJson.decodeFromString<ReviewStatus>(Files.readString(reviewFile))
            Files.writeString(Path.of(sealed.materializedRoot).resolve("Audit.kt"), "changed after durable admission\n")
            val seen = AtomicReference<String>()
            val provider = MechanicalProvider { effective, _ ->
                seen.set(Files.readString(Path.of(effective.materializedRoot).resolve("Audit.kt")))
                mechanicalResult(effective)
            }
            KernelService.open(root, provider, auditKernelConfig(1)).use { reopened ->
                assertEquals("complete", terminal(reopened, registration, review.reviewId).state)
                assertEquals("val auditValue = 1\n", seen.get())
                val recovered = events(reopened, registration)
                assertEquals(1, recovered.count { it.kind == "accepted" })
                assertEquals(1, recovered.count { it.kind == "result" })
                assertTrue(recovered.any { it.kind == "recovered" && it.payload.contains("dead_process_work_recovered") })
                assertEquals(1, provider.calls.get())
            }
        } finally {
            if (child.isAlive) child.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
        }
    }
}

/** Real independent JVM and SQLite reservation; this child does not run a compiler or a model host. */
object KernelAuditChild {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 6)
        val root = Path.of(args[0])
        val input = ContractJson.decodeFromString<AnalysisInput>(Files.readString(Path.of(args[1])))
        val started = Path.of(args[4])
        val crash = Path.of(args[5])
        val provider = MechanicalProvider { _, _ ->
            Files.writeString(started, "real worker owns durable reservation")
            while (true) Thread.sleep(1000)
            @Suppress("UNREACHABLE_CODE") mechanicalResult(input)
        }
        val service = KernelService.open(root, provider, auditKernelConfig(1))
        val registration = service.registerClient(ClientBinding("audit-owner", "project", "tree", "child", "child-lineage", sessionId = "child-session"))
        Files.writeString(Path.of(args[2]), ContractJson.encodeToString(registration))
        val review = service.submit(registration.client, SubmitRequest("child-patch", "child-key", input))
        Files.writeString(Path.of(args[3]), ContractJson.encodeToString(review))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!Files.exists(crash) && System.nanoTime() < deadline) Thread.sleep(5)
        check(Files.exists(crash)) { "Parent did not request bounded crash" }
        Runtime.getRuntime().halt(73)
    }
}

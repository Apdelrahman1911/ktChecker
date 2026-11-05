package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

/** Synthetic KERNEL SPI only: no model/compiler success token, compiler process or qualified rule. */
internal class ProducedLaneFixture(private val root: Path) : ReviewPlanAuthority {
    companion object {
        const val PROVIDER = "synthetic-deferred-lane"
        const val PRODUCER = "slot-000-main"
        const val CONSUMER = "slot-001-test"
        const val PREVIEW = "ktchecker.jvm-produced-preview"
        val CONFIG = KernelConfig(workerCount = 2, globalParallelism = 2, perWorktreeParallelism = 2,
            heartbeatMillis = 40, leaseMillis = 160, pollMillis = 5, analysisTimeoutMillis = 15_000)
        fun binding(id: String) = ClientBinding("owner", "workspace", "tree-$id", id, "lineage-$id", 1, "session-$id")
    }

    val rules: List<String> = assertNotNull(javaClass.getResourceAsStream("/joint-completion-full-catalog-1663.txt")).use {
        val bytes = it.readBytes()
        assertEquals("sha256:4c00ec83059325b8ee2fac672e6c53948746bda6920d900a9d1942ce1b03520d", Digests.sha256(bytes))
        bytes.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).toList().also { ids ->
            assertEquals(1663, ids.size); assertEquals(ids.distinct().sorted(), ids)
        }
    }
    val definitions = ConcurrentHashMap<String, RequiredReviewPlan>()
    val clocks = ConcurrentHashMap<String, ProducedPlanClock>()
    val admissions = ConcurrentHashMap<String, ProducedPlanAdmissionRequest>()
    val preparations = ConcurrentHashMap<String, ProducedPlanPreparationRequest>()
    val resolver = ConcurrentHashMap<String, (ProducedSlotResolutionRequest) -> ProducedSlotResolution>()
    val resolutionCalls = ConcurrentHashMap<String, AtomicInteger>()
    val typedAttempts = ConcurrentLinkedQueue<ProducedExecutionAttempt>()
    private val active = ConcurrentHashMap<String, ProducedExecutionAttempt>()
    private val capturedDisposition = ConcurrentHashMap<Pair<String, String>, String>()
    val ordinaryPreparations = AtomicInteger()
    val producerPreparations = AtomicInteger()
    val policyInputs = ConcurrentLinkedQueue<Pair<String, String>>()

    fun definition(binding: ClientBinding, id: String, disposition: String = "open", parent: String? = null): RequiredReviewPlan {
        val source = root.resolve("source-$id"); Files.createDirectories(source)
        val members = listOf("Main.kt" to "package fixture\nval main = 1\n", "Test.kt" to "package fixture\nval test = 2\n")
        val files = members.map { (path, text) ->
            Files.writeString(source.resolve(path), text)
            SnapshotFile(path, Digests.sha256(text), text.toByteArray().size.toLong())
        }
        val snapshot = SnapshotManifest(Digests.sha256("synthetic-source:$id"), binding.workspaceId, binding.worktreeId,
            files, binding.ownerId, parentSnapshotId = parent, declaredRoots = listOf("."))
        val catalogDigest = Digests.sha256(canonical(rules))
        val raw = listOf("main", "test").mapIndexed { index, compilation -> AnalysisContext(
            scope = RuleContext(target = "", sourceSet = compilation, module = ":fixture", compilation = compilation),
            compilerVersion = "2.4.21", compilerArguments = listOf("-module-name", "fixture-$compilation"),
            libraries = mapOf("kotlin" to "2.4.21"), policies = mapOf("fixture-disposition" to disposition),
            modelDigest = Digests.sha256("synthetic-model"), dependencyDigest = Digests.sha256("unresolved:$compilation"),
            toolchainDigest = Digests.sha256("synthetic-toolchain"), configurationDigest = Digests.sha256("capture:$id:$disposition"),
            fullProject = false, compilationFiles = listOf(files[index].path)) }
        val inputs = raw.map { context -> AnalysisInput(snapshot, source.toString(), context.copy(policies = context.policies + mapOf(
            "ktchecker.catalog-inventory" to catalogDigest, "ktchecker.activation-plan" to Digests.sha256("activation:$id"))), rules) }
        val request = SubmitPlanRequest("patch-$id", "key-$id", "ref-$id")
        val clock = ProducedPlanClock(Any(), "original-budget-$id", System.nanoTime(), CONFIG.analysisTimeoutMillis)
        val declaration = JvmProducedPlanDeclaration(Digests.sha256("graph:$id"), Digests.sha256("import:$id"),
            assertNotNull(raw.first().modelDigest), ":fixture", assertNotNull(raw.first().toolchainDigest),
            ProducedCompilationDeclaration(PRODUCER, "fixture-main", Digests.sha256(canonical(raw[0])), Digests.sha256("main-layout")),
            ProducedCompilationDeclaration(CONSUMER, "fixture-test", Digests.sha256(canonical(raw[1])), Digests.sha256("test-layout")),
            Digests.sha256("main-to-test-friend"), 0, true, catalogDigest, Digests.sha256(canonical(rules)),
            Digests.sha256("intent:$id"), clock.identity, clock.durationMillis)
        return RequiredReviewPlan(binding, request, Digests.sha256(canonical(snapshot)), Digests.sha256(canonical(emptyList<SnapshotFile>())),
            Digests.sha256("synthetic-model-certificate"), Digests.sha256("synthetic-impact:$id"),
            listOf(PRODUCER, CONSUMER).mapIndexed { index, slot -> RequiredPlanSlot(slot, inputs[index], state = "declared",
                reasons = listOf("verified_compilation_context_unavailable", "jvm_produced_output_deferred")) },
            listOf(binding.clientId), declaration).also { plan ->
            assertNull(definitions.putIfAbsent(request.planRef, plan)); clocks[request.planRef] = clock
            resolutionCalls[request.planRef] = AtomicInteger()
            plan.slots.forEach { capturedDisposition[binding.clientId to it.input.analysisKey()] = disposition }
        }
    }

    override fun resolve(binding: ClientBinding, request: SubmitPlanRequest): RequiredReviewPlan? =
        definitions[request.planRef]?.takeIf { it.binding == binding && it.request == request }

    override fun authorizeProduced(request: ProducedPlanAdmissionRequest): AuthorizedProducedPlan {
        val ref = request.request.planRef
        assertEquals(definitions.getValue(ref), request.definition)
        admissions[ref] = request
        return AuthorizedProducedPlan(request, clocks.getValue(ref), Digests.sha256("synthetic-live-authority:$ref"),
            ProducedPlanValidity(), verifyForAdmission = { assertSame(request, admissions.getValue(ref)) },
            prepareProducer = { preparation ->
                assertSame(request, preparation.admission); preparations[ref] = preparation
                producerPreparations.incrementAndGet()
                preparation.archives.forEach { archive ->
                    assertSame(request, archive.admission); archive.verify()
                    // Held D must be readable BEFORE acceptance installs its immutable final path.
                    assertFalse(Files.exists(Path.of(archive.frozen.materializedRoot)))
                    assertEquals(archive.held, archive.readInput()); verifySources(archive.readInput())
                }
                val archive = preparation.archive(PRODUCER)
                PreparedProducedExecution(PRODUCER, resolvedInput(request, archive.original), Digests.sha256("producer-binding:$ref"),
                    verifyForAdmission = { archive.verify() })
            }, operationalFaults = { check ->
                assertSame(request, check.admission)
                // Exact synthetic partial/unqualified shape only; not a general unknown-coverage waiver.
                if (check.original == check.execution.input && check.result == partial(check.effective)) emptyList()
                else listOf("synthetic_operational_shape_mismatch")
            }, resolveConsumer = { resolution ->
                assertSame(request, resolution.admission); assertSame(preparations.getValue(ref), resolution.preparation)
                resolutionCalls.getValue(ref).incrementAndGet()
                resolver[ref]?.invoke(resolution) ?: ProducedSlotResolution.Ready(consumer(resolution))
            })
    }

    private fun resolvedInput(request: ProducedPlanAdmissionRequest, declared: AnalysisInput, jar: ArtifactIdentity? = null): AnalysisInput {
        val dependency = Digests.sha256(canonical(listOf(request.planId, declared.analysisKey(), jar?.digest)))
        val classpath = declared.context.classpath.toMutableList().also { if (jar != null) it.add(request.declaration.classpathIndex, jar) }
        return declared.copy(context = declared.context.copy(classpath = classpath, dependencyDigest = dependency,
            policies = declared.context.policies + mapOf("ktchecker.execution-plan" to Digests.sha256(canonical(emptyMap<String, TrustedExecution>())),
                PREVIEW to request.declaration.graphDigest, "ktchecker.jvm-produced-resolution" to dependency)))
    }

    /** A tiny complete synthetic byte tree, NEVER a genuine compiler result/capability. */
    fun consumer(request: ProducedSlotResolutionRequest): PreparedProducedExecution {
        request.consumerArchive.verify(); verifySources(request.consumerArchive.readInput())
        Files.createDirectories(request.completeJarDirectory)
        val jarPath = request.completeJarDirectory.resolve("complete-output.jar")
        val memberName = "META-INF/synthetic-fixture.txt"
        val bytes = "synthetic kernel fixture, not compiled classes\n".toByteArray()
        ZipOutputStream(Files.newOutputStream(jarPath, java.nio.file.StandardOpenOption.CREATE_NEW)).use { zip ->
            val entry = ZipEntry(memberName).apply {
                method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size
                crc = CRC32().apply { update(bytes) }.value
                setTimeLocal(java.time.LocalDateTime.of(1980, 1, 1, 0, 0))
            }
            zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
        }
        val graph = request.admission.declaration
        val jar = ArtifactIdentity("synthetic:complete-fixture", Digests.sha256(Files.readAllBytes(jarPath)), jarPath.toString())
        val memberDigest = Digests.sha256(canonical(listOf(SnapshotFile(memberName, Digests.sha256(bytes), bytes.size.toLong()))))
        val manifest = ProducedByteManifest(request.consumerArchive.original.snapshot.snapshotId, graph.moduleId,
            graph.producer.compilationId, graph.consumer.compilationId, graph.graphDigest, graph.toolchainDigest,
            graph.producer.argumentLayoutDigest, graph.consumer.argumentLayoutDigest, request.producer.certificate.compilerBindingDigest,
            "synthetic-generation", 1, Digests.sha256("synthetic-request"), Digests.sha256("synthetic-result"),
            request.publication.rawResultDigest, memberDigest, memberDigest, 1, jar, Files.size(jarPath))
        return PreparedProducedExecution(CONSUMER, resolvedInput(request.admission, request.consumerArchive.original, jar),
            Digests.sha256("consumer-binding:${request.admission.planId}"), manifest) {
            request.consumerArchive.verify(); assertEquals(jar.digest, Digests.sha256(Files.readAllBytes(jarPath)))
        }
    }

    val observer = object : PreparedInputObserver {
        override fun prepared(original: AnalysisInput, effective: AnalysisInput, verifyFrozen: () -> Unit) {
            ordinaryPreparations.incrementAndGet()
            if (PREVIEW in original.context.policies) throw KernelException("synthetic_scalar_borrow_denied", "Scalar input is not typed authority")
            verifyFrozen(); verifySources(effective)
        }
        override fun preparedProduced(attempt: ProducedExecutionAttempt) {
            val ref = attempt.admission.request.planRef
            assertSame(admissions.getValue(ref), attempt.admission); assertSame(preparations.getValue(ref), attempt.preparation)
            assertEquals(attempt.execution.input, attempt.original); assertEquals(attempt.execution.workKey, attempt.workKey)
            assertEquals(1L, attempt.attempt); assertEquals(attempt.admission.originInstance, attempt.originInstance)
            assertEquals(rules, attempt.original.requiredRuleIds); assertEquals(rules, attempt.effective.requiredRuleIds)
            assertNotEquals(attempt.original.analysisKey(), attempt.effective.analysisKey())
            assertEquals(attempt.execution.certificate.digest(), attempt.resolutionCertificateId)
            attempt.verifyFrozen(); verifySources(attempt.effective)
            assertNull(active.putIfAbsent(attempt.effective.analysisKey(), attempt)); typedAttempts += attempt
        }
        override fun finished(effective: AnalysisInput) { active.remove(effective.analysisKey()) }
    }

    val policy = ResultPolicy.versioned("synthetic/deferred-D-capture/1", object : ResultPolicy {
        override fun apply(binding: ClientBinding, input: AnalysisInput, raw: ProviderResult): ProviderResult {
            // Resolution/execution keys may never replace immutable declaration-policy identity.
            assertFalse(PREVIEW in input.context.policies)
            val key = binding.clientId to input.analysisKey(); policyInputs += key
            val disposition = assertNotNull(capturedDisposition[key], "Only a registered immutable D capture may be evaluated")
            return raw.copy(findings = raw.findings.map { it.copy(disposition = disposition) })
        }
    })

    inner class Provider(
        private val progress: ((AnalysisInput, (ProviderResult) -> Unit) -> Unit)? = null,
    ) : ProgressiveAnalysisProvider {
        override val id = PROVIDER
        override val supportedRuleIds = rules.toSet()
        val calls = AtomicInteger()
        override fun analyzeProgressive(input: AnalysisInput, cancellation: CancellationToken, onProgress: (ProviderResult) -> Unit): ProviderResult {
            cancellation.throwIfCancelled(); verifySources(input)
            if (PREVIEW in input.context.policies) assertEquals(input, assertNotNull(active[input.analysisKey()]).effective)
            calls.incrementAndGet(); progress?.invoke(input, onProgress)
            return partial(input)
        }
    }

    fun partial(input: AnalysisInput): ProviderResult {
        val file = input.snapshot.files.single { it.path == input.context.compilationFiles!!.single() }
        val finding = Finding(rules.first(), "Synthetic retained observation", "Public fixture observation, not a compiler diagnostic",
            "Exercises immutable history only", SourceLocation(file.path, file.digest),
            Digests.sha256("synthetic-finding:${input.snapshot.snapshotId}:${input.context.scope.id}"), PROVIDER,
            input.context.scope, evidence = listOf(Evidence("synthetic_fixture", "No compiler/model capability")),
            fixHint = "Synthetic fixture only")
        return ProviderResult(PROVIDER, "partial", listOf(finding), rules.map { rule -> Coverage(rule, input.context.scope,
            "unknown", reason = "synthetic_catalog_execution_not_qualified") }, reasons = listOf("synthetic_full_catalog_unqualified"))
    }

    private fun verifySources(input: AnalysisInput) = input.snapshot.files.forEach { file ->
        val bytes = Files.readAllBytes(Path.of(input.materializedRoot).resolve(file.path))
        assertEquals(file.size, bytes.size.toLong()); assertEquals(file.digest, Digests.sha256(bytes))
    }

    /** Independently selected owner snapshot, never an echo of the kernel callback arguments. */
    class Fence : SnapshotGate {
        private var check = SnapshotCheck(false)
        @Synchronized fun install(plan: RequiredReviewPlan) {
            val anchor = plan.slots.last().input
            check = SnapshotCheck(true, anchor.snapshot.snapshotId, (check.sourceEpoch ?: 0) + 1,
                anchor.context.configurationDigest, anchor.context.modelDigest, anchor.context.dependencyDigest,
                anchor.context.toolchainDigest, "synthetic-config:${plan.request.planRef}", authoritative = true,
                contextIdentities = plan.slots.map { assertNotNull(contextIdentity(it.input)) },
                sourceBindingDigest = plan.sourceBindingDigest, generatedInputsDigest = plan.generatedInputsDigest)
        }
        @Synchronized override fun <T> withCurrentSnapshot(input: AnalysisInput, action: (SnapshotCheck) -> T): T = action(check)
        @Synchronized override fun <T> withCurrentSnapshots(inputs: List<AnalysisInput>, action: (SnapshotCheck) -> T): T = action(check)
    }

    fun events(service: KernelService, client: ClientRegistration): List<ReviewEvent> = buildList {
        var cursor: String? = null
        do { val page = service.events(client.client, cursor, 3); addAll(page.events); cursor = page.cursor } while (page.hasMore)
    }
    fun deliver(service: KernelService, client: ClientRegistration) = events(service, client).forEach { event ->
        assertEquals(Digests.sha256(event.payload), event.payloadDigest)
        val digest = Digests.sha256("synthetic-context:${event.payload}")
        service.acknowledge(client.adapter, listOf(event.eventId), ContextReceipt("ack-${event.eventId}", digest,
            "item-${event.eventId}", Instant.EPOCH.toString()))
        service.observe(client.adapter, listOf(event.eventId), ObservationReceipt("observe-${event.eventId}", "request-${event.eventId}", listOf(digest)))
    }
    fun await(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) { assertTrue(System.nanoTime() < deadline, description); Thread.sleep(5) }
    }
    fun finished(service: KernelService, client: ClientRegistration, review: String): ReviewStatus {
        await("Review never published: $review") { service.status(client.client, review).result != null }
        return service.status(client.client, review)
    }
    fun resolved(service: KernelService, review: String) = service.ledger.read { c ->
        c.one("SELECT state FROM plan_slot_resolutions WHERE review_id=?", review) { it.getString(1) }
    }
}

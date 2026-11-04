package dev.ktchecker.compiler

import dev.ktchecker.compilerworker.CompilerWarmWorkerMain as Worker
import dev.ktchecker.compilerworker.CompilerWarmWorkerProtocol as Wire
import dev.ktchecker.contracts.Digests
import dev.ktchecker.providers.runtime.OwnedSupervisorIdentity
import dev.ktchecker.providers.runtime.ProcessOutcome
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/** Pure protocol/authority and fake-session tests, never a real compiler/worker/process qualification. */
class CompilerWarmWorkerContractTest {
    @TempDir lateinit var temporary: Path
    private val digest = Digests.sha256("value-only-unit-fixture")
    private val source = "fun privateFake() = 1\n".toByteArray()
    private val supervisor = OwnedSupervisorIdentity("ktchecker-owned-child-supervisor/preview-1", "linux-x86_64", digest, digest)

    private fun plan(generation: String = "1".repeat(32)): Wire.Plan {
        val dependencies = listOf(1)
        val jobs = (0..3).map { index ->
            val draft = Wire.Job(index, "$generation-job-$index", Wire.KINDS[index], digest, digest, digest, digest, digest, digest,
                digest, Digests.sha256(source), source.size.toLong())
            Wire.Job(index, draft.jobId(), draft.kind(), digest, digest, digest, digest, digest, digest,
                Wire.profileDigest(draft, dependencies), draft.sourceDigest(), draft.sourceSize())
        }
        // These are descriptors only. No fake byte is asserted to have the reviewed compiler digest.
        return Wire.Plan(generation, "2".repeat(32), digest, digest, digest,
            listOf(Wire.Artifact("kotlin-compiler-embeddable-2.4.21.jar", Wire.COMPILER_SHA256, 1),
                Wire.Artifact("kotlin-stdlib-2.4.21.jar", digest, 1)), dependencies, jobs)
    }

    private fun copyPlan(plan: Wire.Plan, jobs: List<Wire.Job> = plan.jobs(), artifacts: List<Wire.Artifact> = plan.compilerArtifacts()) =
        Wire.Plan(plan.generation(), plan.ownerDomain(), plan.implementationKey(), plan.bridgeDigest(), plan.toolchainDigest(), artifacts,
            plan.dependencyIndexes(), jobs)

    private fun contract(plan: Wire.Plan = plan(), clock: () -> Long = { 0 }) =
        CompilerWarmWorkerContract(plan, List(4) { 120_000_000_000L }, clock)

    private fun admitted(contract: CompilerWarmWorkerContract): CompilerWarmWorkerContract {
        val permits = contract.grantJobs()
        contract.recordLaunchGrant()
        contract.admit(permits, "root-key", (0..3).map { "job-$it-key" })
        contract.starting()
        return contract
    }

    private fun receipt(plan: Wire.Plan, index: Int, root: String = "root-key", code: Int = if (index in setOf(0, 3)) 1 else 0): Wire.Receipt {
        val job = plan.jobs()[index]
        return Wire.Receipt(plan.generation(), Wire.sha256(Wire.encodePlan(plan)), plan.implementationKey(), "3".repeat(32), index,
            job.jobId(), job.originalKey(), job.effectiveKey(), job.sourceDigest(), root, "job-$index-key", "2.4.21", "sealed", code,
            index * 10L, index * 10L + 3, index * 10L + 4, true, false, "", emptyList())
    }

    private fun process(exec: Boolean = true, status: String = "executed", exit: Int = 0, reasons: List<String> = emptyList()) =
        ProcessOutcome(status, reasons, exitCode = exit, executionConfirmed = exec, supervisorIdentity = supervisor)

    private fun copyReceipt(value: Wire.Receipt, loader: String = value.loaderToken(), jobKey: String = value.jobRootKey(),
        code: Int = value.compilerCode(), state: String = value.state(), started: Long = value.startedNanos(),
        returned: Long = value.returnedNanos(), disposed: Long = value.disposedNanos(), disposal: Boolean = value.disposalObserved(),
        cancelled: Boolean = value.cancellationObserved(), failure: String = value.failureCode(), diagnostics: List<Wire.Diagnostic> = value.diagnostics()) =
        Wire.Receipt(value.generation(), value.planDigest(), value.implementationKey(), loader, value.ordinal(), value.jobId(),
            value.originalKey(), value.effectiveKey(), value.sourceDigest(), value.outputRootKey(), jobKey, value.compilerVersion(),
            state, code, started, returned, disposed, disposal, cancelled, failure, diagnostics)

    @Test fun `plan and receipt round trips preserve exact version order and bytes`() {
        val plan = plan()
        val bytes = Wire.encodePlan(plan)
        assertEquals(plan, Wire.decodePlan(bytes))
        assertContentEquals(bytes, Wire.encodePlan(Wire.decodePlan(bytes)))
        val receipt = receipt(plan, 0)
        assertEquals(receipt, Wire.decodeReceipt(Wire.encodeReceipt(receipt)))
        assertEquals(4, plan.jobs().size)
        assertEquals(listOf("positive", "clean", "clean", "positive"), plan.jobs().map { it.kind() })
    }

    @Test fun `malformed version utf8 lengths truncation and trailing bytes are rejected`() {
        val bytes = Wire.encodePlan(plan())
        val malformedUtf8 = bytes.clone().also { it[4] = 0xc3.toByte(); it[5] = 0x28 }
        val excessiveLength = bytes.clone().also { ByteBuffer.wrap(it).putInt(Wire.MAX_PLAN_BYTES + 1) }
        val wrongVersion = bytes.clone().also { it[4] = 'x'.code.toByte() }
        for (bad in listOf(malformedUtf8, excessiveLength, wrongVersion, bytes.copyOf(bytes.size - 1), bytes + 0)) assertFails { Wire.decodePlan(bad) }
        val receipt = Wire.encodeReceipt(receipt(plan(), 0))
        assertFails { Wire.decodeReceipt(receipt + 0) }
        assertFails { Wire.decodeReceipt(receipt.copyOf(receipt.size - 1)) }
    }

    @Test fun `duplicate omitted reordered jobs and same version unreviewed compiler bytes are rejected`() {
        val plan = plan()
        for (jobs in listOf(plan.jobs().dropLast(1), plan.jobs() + plan.jobs()[0], plan.jobs().reversed(), List(4) { plan.jobs()[0] })) {
            assertFails { Wire.encodePlan(copyPlan(plan, jobs = jobs)) }
        }
        assertFails { Wire.encodePlan(copyPlan(plan, artifacts = listOf(
            Wire.Artifact("kotlin-compiler-embeddable-2.4.21.jar", digest, 1), plan.compilerArtifacts()[1]))) }
    }

    @Test fun `receipt booleans byte limits and diagnostic utf8 cannot be coerced`() {
        val bytes = Wire.encodeReceipt(receipt(plan(), 0))
        val buffer = ByteBuffer.wrap(bytes)
        fun skipString() { val length = buffer.int; buffer.position(buffer.position() + length) }
        repeat(7) { skipString() }
        buffer.int // Ordinal.
        repeat(8) { skipString() }
        buffer.int // Compiler API code.
        repeat(3) { buffer.long }
        val firstBoolean = buffer.position()
        for (offset in listOf(firstBoolean, firstBoolean + 1)) {
            assertFails { Wire.decodeReceipt(bytes.clone().also { it[offset] = 2 }) }
        }
        assertFails { Wire.decodePlan(ByteArray(Wire.MAX_PLAN_BYTES + 1)) }
        assertFails { Wire.decodeReceipt(ByteArray(Wire.MAX_RECEIPT_BYTES + 1)) }
        for (text in listOf("nul\u0000value", "unpaired\uD800")) {
            val sink = Wire.DiagnosticSink()
            assertFails { sink.report(Wire.Diagnostic("ERROR", text, "", -1, -1, "")) }
            assertTrue(sink.invalidObserved())
        }
    }

    @Test fun `only fixed profile and bounded ordered stdlib dependencies are accepted`() {
        val original = plan()
        for (indexes in listOf(emptyList(), listOf(-1), listOf(0), listOf(2), listOf(1, 1))) {
            assertFails { Wire.encodePlan(Wire.Plan(original.generation(), original.ownerDomain(), original.implementationKey(),
                original.bridgeDigest(), original.toolchainDigest(), original.compilerArtifacts(), indexes, original.jobs())) }
        }
        val first = original.jobs()[0]
        val changed = Wire.Job(first.ordinal(), first.jobId(), first.kind(), first.originalKey(), first.effectiveKey(),
            first.snapshotDigest(), first.modelDigest(), first.dependencyDigest(), first.configurationDigest(), digest,
            first.sourceDigest(), first.sourceSize())
        assertFails { Wire.encodePlan(copyPlan(original, jobs = listOf(changed) + original.jobs().drop(1))) }
    }

    @Test fun `descriptors snapshot incoming collections and expose no mutable lists`() {
        val original = plan()
        val jobs = original.jobs().toMutableList()
        val snapshot = copyPlan(original, jobs)
        jobs.clear()
        assertEquals(4, snapshot.jobs().size)
        assertFails { (snapshot.jobs() as MutableList).clear() }
        val sink = Wire.DiagnosticSink()
        sink.report(Wire.Diagnostic("ERROR", "kept", "", -1, -1, ""))
        assertFails { (sink.values() as MutableList).clear() }
        assertEquals(1, sink.values().size)
        assertFails { (snapshot.compilerArtifacts() as MutableList).clear() }
        assertFails { (snapshot.dependencyIndexes() as MutableList).clear() }
    }

    @Test fun `implementation key includes actual component order jdk mode backend and owner`() {
        val fields = (0..6).map { Digests.sha256("component-$it") }
        fun key(values: List<String> = fields, owner: String = "2".repeat(32), os: String = "Linux", architecture: String = "amd64", mode: String = "private-fixed-synthetic-jvm") =
            WarmImplementationKey(values[0], values[1], values[2], values[3], values[4], values[5], values[6], owner, os, architecture, mode).digest
        val baseline = key()
        fields.indices.forEach { index -> assertNotEquals(baseline, key(fields.toMutableList().also { it[index] = digest })) }
        assertNotEquals(baseline, key(fields.toMutableList().also { it[2] = Digests.sha256("same-label-reordered-closure") }))
        assertNotEquals(baseline, key(owner = "4".repeat(32)))
        assertNotEquals(baseline, key(os = "Other"))
        assertNotEquals(baseline, key(architecture = "aarch64"))
        assertNotEquals(baseline, key(mode = "verified-model"))
    }

    @Test fun `decoded descriptors and another owner cannot substitute for live registered capabilities`() {
        val left = contract()
        val right = contract(Wire.decodePlan(Wire.encodePlan(left.plan)))
        val owned = left.grantJobs()
        val foreign = right.grantJobs()
        left.recordLaunchGrant()
        assertFails { left.admit(emptyList(), "root-key", List(4) { "job-$it-key" }) }
        assertFails { left.admit(foreign, "root-key", List(4) { "job-$it-key" }) }
        assertEquals(0, left.jobAdmissionCount)
        assertTrue(owned.none { it.consumed })
        left.admit(owned, "root-key", List(4) { "job-$it-key" })
        assertEquals(4, left.jobAdmissionCount)
        assertTrue(owned.all { it.consumed })
    }

    @Test fun `old epoch replay extra launch and output alias are denied before dispatch`() {
        val selected = contract()
        val permits = selected.grantJobs()
        selected.recordLaunchGrant()
        val previous = contract(plan("4".repeat(32))).grantJobs()
        assertFails { selected.admit(previous, "root-key", List(4) { "job-$it-key" }) }
        assertFails { selected.admit(permits, "root-key", List(4) { "same-key" }) }
        assertFails { selected.admit(permits, "root-key", listOf("root-key", "a", "b", "c")) }
        selected.admit(permits, "root-key", List(4) { "job-$it-key" })
        selected.starting()
        assertFails { selected.admit(permits, "root-key", List(4) { "job-$it-key" }) }
        assertFails { selected.recordLaunchGrant() }
        assertFails { selected.grantJobs() }
        assertEquals(1, selected.launchGrantCount)
    }

    @Test fun `even a copied permit with the same owner and values is not the registered capability`() {
        val selected = contract()
        val permits = selected.grantJobs()
        selected.recordLaunchGrant()
        val first = permits[0]
        val copied = WarmJobPermit(first.owner, first.job, first.generation, first.implementationKey, first.deadlineNanos) {}
        assertFails { selected.admit(listOf(copied) + permits.drop(1), "root-key", List(4) { "job-$it-key" }) }
        assertTrue(permits.none { it.consumed })
        assertFalse(copied.consumed)
    }

    @Test fun `single job revocation poisons whole generation and consumes no rejected admissions`() {
        val selected = contract()
        val permits = selected.grantJobs()
        selected.recordLaunchGrant()
        permits[2].revoke()
        assertTrue(selected.cancelled())
        assertEquals(WarmGenerationState.POISONED, selected.state)
        assertFails { selected.admit(permits, "root-key", List(4) { "job-$it-key" }) }
        assertTrue(permits.none { it.consumed })
        assertFails { selected.starting() }
    }

    @Test fun `common earliest absolute deadline charges preparation and rejects expiry`() {
        var now = 0L
        val selected = CompilerWarmWorkerContract(plan(), listOf(10_000_000L, 40_000_000L, 30_000_000L, 20_000_000L)) { now }
        selected.grantJobs()
        now = 6_000_000
        assertEquals(4L, selected.remainingMillis())
        now = 10_000_000
        assertTrue(selected.cancelled())
        assertFails { selected.recordLaunchGrant() }
        assertFails { CompilerWarmWorkerContract(plan(), List(4) { now }) { now } }
    }

    @Test fun `revocation and all four admissions share one owner monitor without partial consumption`() {
        val selected = contract()
        val permits = selected.grantJobs()
        selected.recordLaunchGrant()
        val ready = CountDownLatch(1)
        val threadFailure = AtomicReference<Throwable?>()
        val revoker = synchronized(selected) {
            val launched = thread(name = "fake-warm-capability-revoker", isDaemon = true) {
                ready.countDown()
                runCatching { permits[2].revoke() }.onFailure(threadFailure::set)
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            selected.admit(permits, "root-key", List(4) { "job-$it-key" })
            assertTrue(permits.all { it.consumed })
            launched
        }
        revoker.join(2_000)
        assertFalse(revoker.isAlive)
        assertNull(threadFailure.get())
        assertTrue(selected.cancelled())
        assertEquals(4, selected.jobAdmissionCount)
        assertFails { selected.starting() }
    }

    @Test fun `no fractional native lifetime or late receipt restores authorization`() {
        var now = 0L
        val selected = CompilerWarmWorkerContract(plan(), List(4) { 10_000_000L }) { now }
        admitted(selected)
        now = 9_500_000
        assertFails { selected.remainingMillis() }
        assertEquals(WarmGenerationState.POISONED, selected.state)
        now = 10_000_000
        selected.finish(process(), supervisor, selected.plan.jobs().indices.map { receipt(selected.plan, it) }, true, true)
        assertFalse(selected.observationsComplete)
        assertTrue(selected.generationQuiescenceVerified)
    }

    @Test fun `compiler error is not worker failure and no job gets a process final or qualification`() {
        val selected = admitted(contract())
        val observations = selected.plan.jobs().indices.map { receipt(selected.plan, it) }
        selected.finish(process(), supervisor, observations, ownerInputsVerified = true, expectedOutcomes = true)
        assertTrue(selected.observationsComplete)
        assertTrue(selected.generationQuiescenceVerified)
        assertEquals(1, observations[0].compilerCode())
        assertEquals(0, observations[1].compilerCode())
        assertEquals(false, selected.description()["per_job_process_terminal"])
        assertEquals(false, selected.description()["ordinary_fixture_run_complete"])
        assertEquals("none", selected.description()["qualification"])
        assertFails { selected.finish(process(), supervisor, observations, true, true) }
    }

    @Test fun `worker exit and all receipts without actual native execution proof are insufficient`() {
        for (outcome in listOf(process(exec = false), process(status = "failed", reasons = listOf("owned_process_group_shutdown_unverified")))) {
            val selected = admitted(contract())
            selected.finish(outcome, supervisor, selected.plan.jobs().indices.map { receipt(selected.plan, it) }, true, true)
            assertFalse(selected.observationsComplete)
            assertFalse(selected.generationQuiescenceVerified)
            assertEquals(WarmGenerationState.CLEANUP_PENDING, selected.state)
        }
    }

    @Test fun `duplicate reordered stale or foreign output receipts cannot join current authority`() {
        val original = plan()
        val valid = original.jobs().indices.map { receipt(original, it) }
        val variants = listOf(valid.reversed(), listOf(valid[0], valid[0], valid[2], valid[3]), valid.dropLast(1),
            listOf(receipt(original, 0, root = "replacement-root")) + valid.drop(1),
            listOf(copyReceipt(valid[0], jobKey = "replacement-job")) + valid.drop(1),
            listOf(copyReceipt(valid[0], loader = "5".repeat(32))) + valid.drop(1),
            listOf(valid[0], copyReceipt(valid[1], started = 2)) + valid.drop(2),
            plan("4".repeat(32)).let { other -> other.jobs().indices.map { receipt(other, it) } })
        variants.forEach { receipts ->
            val selected = admitted(contract(original))
            selected.finish(process(), supervisor, receipts, true, true)
            assertFalse(selected.observationsComplete)
            assertEquals(WarmGenerationState.POISONED, selected.state)
        }
    }

    @Test fun `postlaunch revocation late results drift and worker failure cannot restore eligibility`() {
        val selected = contract()
        val permits = selected.grantJobs()
        selected.recordLaunchGrant()
        selected.admit(permits, "root-key", List(4) { "job-$it-key" })
        selected.starting()
        permits[0].revoke()
        selected.finish(process(), supervisor, selected.plan.jobs().indices.map { receipt(selected.plan, it) }, true, true)
        assertFalse(selected.observationsComplete)
        for ((outcome, intact) in listOf(process(exit = 70) to true, process() to false)) {
            val changed = admitted(contract())
            changed.finish(outcome, supervisor, changed.plan.jobs().indices.map { receipt(changed.plan, it) }, intact, true)
            assertFalse(changed.observationsComplete)
            assertTrue(changed.generationQuiescenceVerified)
        }
    }

    @Test fun `diagnostic limits clear and cancellation cannot erase observed failure`() {
        val sink = Wire.DiagnosticSink()
        sink.report(Wire.Diagnostic("INFO", "Compilation was canceled", "", -1, -1, ""))
        sink.report(Wire.Diagnostic("ERROR", "known", "", -1, -1, "UNRESOLVED_REFERENCE"))
        assertTrue(sink.cancelled())
        assertTrue(sink.hasErrors())
        assertFails { sink.clear() }
        assertTrue(sink.invalidObserved())
        assertEquals(2, sink.values().size)
        assertFails { sink.report(Wire.Diagnostic("ERROR", "x".repeat(16_385), "", -1, -1, "")) }
        repeat(Wire.MAX_DIAGNOSTICS - 2) { sink.report(Wire.Diagnostic("INFO", "bounded", "", -1, -1, "")) }
        assertFails { sink.report(Wire.Diagnostic("INFO", "overflow", "", -1, -1, "")) }
    }

    @Test fun `receipt state clocks and clean observations cannot hide known errors or cancellation`() {
        val receipt = receipt(plan(), 0)
        val cancelled = Wire.Diagnostic("INFO", "Compilation was canceled", "", -1, -1, "")
        val error = Wire.Diagnostic("ERROR", "observed error", "", -1, -1, "UNRESOLVED_REFERENCE")
        val rejected = listOf(copyReceipt(receipt, disposal = false), copyReceipt(receipt, disposed = 1),
            copyReceipt(receipt, cancelled = true), copyReceipt(receipt, diagnostics = listOf(cancelled)),
            copyReceipt(receipt, code = 0, diagnostics = listOf(error)), copyReceipt(receipt, code = 99),
            copyReceipt(receipt, state = "outcome_unknown"), copyReceipt(receipt, state = "not_run"))
        rejected.forEach { assertFails { Wire.encodeReceipt(it) } }
        val unknown = copyReceipt(receipt, code = 99, state = "outcome_unknown", failure = "compiler_internal_error")
        assertEquals(99, Wire.decodeReceipt(Wire.encodeReceipt(unknown)).compilerCode())
    }

    @Test fun `aggregate diagnostic bytes are bounded even below the diagnostic count limit`() {
        val sink = Wire.DiagnosticSink()
        repeat(3) { sink.report(Wire.Diagnostic("INFO", "x".repeat(16_384), "", -1, -1, "")) }
        assertFails { sink.report(Wire.Diagnostic("INFO", "x".repeat(16_384), "", -1, -1, "")) }
        assertEquals(3, sink.values().size)
        assertTrue(sink.invalidObserved())
    }

    @Test fun `fresh fake compiler sessions are serial disposed and compilation errors do not stop schedule`() {
        val plan = plan()
        val output = output(plan)
        val fake = FakeFactory()
        assertTrue(Worker.runSessionSequence(plan, output, fake, AtomicLong()::incrementAndGet) { true })
        assertEquals(4, fake.opens)
        assertEquals(4, fake.closes)
        assertEquals(1, fake.maximumActive)
        assertEquals(0, fake.active)
        val receipts = readReceipts(plan, output)
        assertEquals(listOf(1, 0, 0, 1), receipts.map { it.compilerCode() })
        assertTrue(receipts.all { it.state() == "sealed" && it.disposalObserved() })
        assertEquals(1, receipts.map { it.loaderToken() }.distinct().size)
        assertTrue(receipts.zipWithNext().all { (left, right) -> left.disposedNanos() <= right.startedNanos() })
    }

    @Test fun `fake disposal uncertainty preserves returned compiler code and forbids every later job`() {
        val plan = plan()
        val output = output(plan)
        val fake = FakeFactory(disposalFailure = true)
        assertFalse(Worker.runSessionSequence(plan, output, fake, AtomicLong()::incrementAndGet) { true })
        assertEquals(1, fake.opens)
        assertEquals(1, fake.closes)
        val receipts = readReceipts(plan, output)
        assertEquals(1, receipts[0].compilerCode())
        assertEquals("outcome_unknown", receipts[0].state())
        assertFalse(receipts[0].disposalObserved())
        assertTrue(receipts.drop(1).all { it.state() == "not_run" && it.compilerCode() == -1 })
    }

    @Test fun `fake api success with cancellation diagnostics is unknown not clean or reusable`() {
        val plan = plan()
        val output = output(plan)
        val fake = FakeFactory(cancelled = true)
        assertFalse(Worker.runSessionSequence(plan, output, fake, AtomicLong()::incrementAndGet) { true })
        val receipts = readReceipts(plan, output)
        assertEquals(0, receipts[0].compilerCode())
        assertTrue(receipts[0].cancellationObserved())
        assertEquals("outcome_unknown", receipts[0].state())
        assertEquals(1, fake.opens)
    }

    @Test fun `unexplained raw output poisons fake schedule even after a known compiler return`() {
        val plan = plan()
        val output = output(plan)
        val quiet = AtomicBoolean(true)
        val fake = FakeFactory(afterExecute = { quiet.set(false) })
        assertFalse(Worker.runSessionSequence(plan, output, fake, AtomicLong()::incrementAndGet, quiet::get))
        val receipts = readReceipts(plan, output)
        assertEquals(1, receipts[0].compilerCode())
        assertEquals("outcome_unknown", receipts[0].state())
        assertTrue(receipts.drop(1).all { it.state() == "not_run" })
    }

    @Test fun `unexpected fake api result is retained and poisons all following jobs`() {
        for (code in listOf(2, 3, 137, 99, -7)) {
            val plan = plan()
            val output = output(plan)
            val fake = FakeFactory(codeOverride = code)
            assertFalse(Worker.runSessionSequence(plan, output, fake, AtomicLong()::incrementAndGet) { true })
            val receipts = readReceipts(plan, output)
            assertEquals(code, receipts[0].compilerCode())
            assertEquals("outcome_unknown", receipts[0].state())
            assertEquals(1, fake.opens)
            assertTrue(receipts.drop(1).all { it.state() == "not_run" })
        }
    }

    @Test fun `swallowed fake collector rejection cannot become a sealed result`() {
        val plan = plan()
        val output = output(plan)
        val fake = FakeFactory(swallowedDiagnosticFailure = true)
        assertFalse(Worker.runSessionSequence(plan, output, fake, AtomicLong()::incrementAndGet) { true })
        val receipts = readReceipts(plan, output)
        assertEquals(1, receipts[0].compilerCode())
        assertEquals("outcome_unknown", receipts[0].state())
        assertEquals(1, fake.opens)
    }

    @Test fun `fake thrown compiler call still closes its root and dispatches no later session`() {
        val plan = plan()
        val output = output(plan)
        val fake = FakeFactory(afterExecute = { error("fake compiler call failure") })
        assertFalse(Worker.runSessionSequence(plan, output, fake, AtomicLong()::incrementAndGet) { true })
        val receipt = readReceipts(plan, output)[0]
        assertEquals(-1, receipt.compilerCode())
        assertEquals(-1L, receipt.returnedNanos())
        assertTrue(receipt.disposalObserved())
        assertEquals(1, fake.opens)
        assertEquals(1, fake.closes)
    }

    @Test fun `classes root replacement is not accepted as a fresh owned result directory`() {
        val plan = plan()
        val output = output(plan)
        val classes = output.resolve(plan.jobs()[0].outputPath())
        val fake = FakeFactory(afterExecute = {
            Files.move(classes, temporary.resolve("preserved-original-classes"))
            Files.createDirectory(classes)
        })
        assertFails { Worker.runSessionSequence(plan, output, fake, AtomicLong()::incrementAndGet) { true } }
        val receipts = readReceipts(plan, output)
        assertEquals(1, receipts[0].compilerCode())
        assertEquals("outcome_unknown", receipts[0].state())
        assertTrue(receipts.drop(1).all { it.state() == "not_run" })
        assertEquals(1, fake.opens)
    }

    @Test fun `unsafe relative paths symlinks source mutation and output bounds are rejected`() {
        val plan = plan()
        val output = output(plan)
        for (path in listOf("../outside", "/absolute", "jobs\\0", "jobs/../0", "jobs//0")) assertFails { Wire.safePath(output, path) }
        Files.write(output.resolve(plan.jobs()[0].sourcePath()), source + 1)
        assertFails { Worker.verifySource(plan.jobs()[0], output) }
        Files.createSymbolicLink(output.resolve("alias"), output.resolve("jobs"))
        assertFails { Wire.safePath(output, "alias/0") }
        assertFails { Worker.boundedTree(output, 1, 100) }
        assertFails { Worker.boundedTree(output, 1_048_576, 1) }
    }

    private fun output(plan: Wire.Plan): Path {
        assumeTrue(temporary.fileSystem.supportedFileAttributeViews().contains("posix"), "The private worker receipt writer is POSIX-only; pure codec/capability tests remain portable")
        val root = Files.createTempDirectory(temporary, "fake-session-").toRealPath()
        Files.createDirectory(root.resolve("tmp"))
        Files.createDirectory(root.resolve("compiler"))
        Files.createDirectory(root.resolve("jobs"))
        Files.write(root.resolve("worker.jar"), byteArrayOf(0))
        Files.write(root.resolve("plan.bin"), Wire.encodePlan(plan))
        plan.jobs().forEach { job ->
            Files.createDirectories(root.resolve("jobs/${job.ordinal()}/sources"))
            Files.createDirectory(root.resolve(job.outputPath()))
            Files.write(root.resolve(job.sourcePath()), source)
        }
        return root
    }

    private fun readReceipts(plan: Wire.Plan, output: Path) = plan.jobs().map {
        Wire.decodeReceipt(Files.readAllBytes(output.resolve(it.receiptPath())))
    }

    private class FakeFactory(val disposalFailure: Boolean = false, val cancelled: Boolean = false,
        val swallowedDiagnosticFailure: Boolean = false, val codeOverride: Int? = null, val afterExecute: () -> Unit = {}) : Worker.SessionFactory {
        var opens = 0
        var closes = 0
        var active = 0
        var maximumActive = 0
        override fun compilerVersion() = "2.4.21"
        override fun open(): Worker.Session {
            check(active == 0)
            val index = opens++
            active++
            maximumActive = maxOf(maximumActive, active)
            return object : Worker.Session {
                var disposed = false
                override fun execute(argv: List<String>, sink: Wire.DiagnosticSink): Int {
                    assertTrue(argv.contains("-no-stdlib") && argv.contains("-no-reflect") && argv.contains("-jdk-home"))
                    if (cancelled) sink.report(Wire.Diagnostic("INFO", "Compilation was canceled", "", -1, -1, ""))
                    if (swallowedDiagnosticFailure) runCatching {
                        sink.report(Wire.Diagnostic("INFO", "x".repeat(16_385), "", -1, -1, ""))
                    }
                    afterExecute()
                    return codeOverride ?: if (cancelled || index in setOf(1, 2)) 0 else 1
                }
                override fun disposalObserved() = disposed
                override fun close() {
                    check(!disposed)
                    closes++
                    active--
                    if (disposalFailure) error("fake disposal failure")
                    disposed = true
                }
            }
        }
        override fun close() { check(active == 0) }
    }
}

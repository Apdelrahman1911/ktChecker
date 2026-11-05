package dev.ktchecker.providers.detekt

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.OwnedSupervisorIdentity
import dev.ktchecker.providers.runtime.ProcessOutcome
import java.io.IOException
import java.lang.reflect.Proxy
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.extension.ExtensionContext
import kotlin.test.*

/** Non-process controls only: fake outcomes never invoke a provider, runner, helper or PID operation. */
class RetainedFixtureSuiteTest {
    @TempDir lateinit var repository: Path
    private val provider = "detekt"
    private val rule = "ktc.synthetic.fixture-lifecycle-control"
    private val identity = OwnedSupervisorIdentity("ktchecker-owned-child-supervisor/preview-1", "linux-x86_64",
        "sha256:${"a".repeat(64)}", "sha256:${"b".repeat(64)}")
    private val destination get() = repository.resolve("build/evidence/$provider")

    @BeforeEach fun requireAnchoredPrivateFilesystem() {
        assumeTrue(Files.getFileStore(repository).supportsFileAttributeView("posix"), "Private POSIX control fixture required")
        Files.newDirectoryStream(repository).use { assumeTrue(it is SecureDirectoryStream<Path>, "Anchored control I/O required") }
    }

    private fun newSuite() = RetainedFixtureSuite(provider)
    private fun newJob(suite: RetainedFixtureSuite) = suite.startJob(repository, destination, "control")
    private fun native() = ProcessOutcome("executed", exitCode = 1, supervisorIdentity = identity, executionConfirmed = true)
    private fun denial() = ProcessOutcome("unavailable", reasons = listOf("execution_not_authorized"))
    private fun input(target: String = "common") = AnalysisInput(
        SnapshotManifest("synthetic", "synthetic", "synthetic", emptyList()), repository.toString(),
        AnalysisContext(scope = RuleContext(target = target)), listOf(rule))
    private fun abstention(input: AnalysisInput) = ProviderResult(provider, "partial",
        coverage = listOf(Coverage(rule, input.context.scope, "unsupported")))
    private fun checkedNative(suite: RetainedFixtureSuite) {
        val ticket = suite.begin("native")
        suite.observe(ticket, native())
        suite.checkedNative(ticket, identity)
    }
    private fun assertRetained(job: RetainedFixtureSuite.Job) {
        assertTrue(Files.isDirectory(job.scratchDirectory, NOFOLLOW_LINKS))
        var path = job.scratchDirectory
        while (path != job.repository) { assertTrue(Files.isDirectory(path, NOFOLLOW_LINKS)); path = path.parent }
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(job.scratchDirectory, NOFOLLOW_LINKS))
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(job.runDirectory, NOFOLLOW_LINKS))
    }

    @Test fun `success retains private scratch and all ancestors without permission restoration`() {
        val suite = newSuite(); val job = newJob(suite)
        val immutable = job.scratchDirectory.resolve("registered-source.kt")
        Files.writeString(immutable, "fun retained() = Unit\n")
        val permissions = PosixFilePermissions.fromString("r--r--r--")
        Files.setPosixFilePermissions(immutable, permissions)
        checkedNative(suite) // A real engine's nonzero finding exit still needs a validated cleanup terminal.
        val summary = suite.finishJob()
        suite.close()
        assertRetained(job)
        assertEquals(permissions, Files.getPosixFilePermissions(immutable, NOFOLLOW_LINKS))
        assertEquals("fun retained() = Unit\n", Files.readString(immutable))
        assertTrue(summary["scratch_created"]!!.jsonPrimitive.boolean)
        assertEquals("retain_created_job_scratch_and_ancestors", summary["retention_policy"]!!.jsonPrimitive.content)
        assertTrue(summary["paths_are_last_known_not_current_filesystem_proof"]!!.jsonPrimitive.boolean)
        assertFalse(summary["automatic_cleanup"]!!.jsonPrimitive.boolean)
        assertFalse(summary["ownership_unsettled"]!!.jsonPrimitive.boolean)
    }

    @Test fun `callback does not admit another invocation or fixture method`() {
        val suite = newSuite(); val job = newJob(suite)
        val ticket = suite.begin("native"); suite.observe(ticket, native())
        assertFailsWith<IllegalStateException> { suite.begin("native") }
        assertFailsWith<IllegalStateException> { suite.startJob(repository, destination, "next-method") }
        assertEquals(0, suite.summary()["checked_calls"]!!.jsonPrimitive.int)
        suite.close(); assertRetained(job)
    }

    @Test fun `ordinary operation return without callback remains quarantined`() {
        val suite = newSuite(); val job = newJob(suite)
        var entered = 0
        assertFailsWith<IllegalStateException> { suite.native(identity) { entered++; "synthetic return only" } }
        assertEquals(1, entered)
        assertTrue(suite.summary()["suite_admission_stopped"]!!.jsonPrimitive.boolean)
        assertTrue(suite.summary()["ownership_unsettled"]!!.jsonPrimitive.boolean)
        suite.close(); assertRetained(job)
    }

    @Test fun `confirmed fork cleanup poison and uncertain terminals permanently stop admission`() {
        val outcomes = listOf(
            native().copy(executionConfirmed = false),
            native().copy(status = "failed", reasons = listOf("owned_process_group_shutdown_unverified")),
            native().copy(reasons = listOf("process_supervisor_protocol_error")),
            native().copy(reasons = listOf("process_supervisor_transport_timeout")),
            native().copy(supervisorIdentity = identity.copy(binaryDigest = "sha256:${"c".repeat(64)}")),
            native().copy(supervisorIdentity = null), native().copy(exitCode = null),
            native().copy(cancelled = true), native().copy(timedOut = true), native().copy(truncated = true),
        )
        for (outcome in outcomes) {
            val suite = newSuite(); val job = newJob(suite); val ticket = suite.begin("native")
            suite.observe(ticket, outcome)
            assertFailsWith<IllegalStateException> { suite.checkedNative(ticket, identity) }
            assertFailsWith<IllegalStateException> { suite.begin("native") }
            assertFailsWith<IllegalStateException> { suite.startJob(repository, destination, "retry") }
            suite.close(); assertRetained(job)
        }
    }

    @Test fun `false confirmation or partial refusal setup is not no-execution proof`() {
        val outcomes = listOf(
            denial().copy(exitCode = 0), denial().copy(outputDirectory = repository),
            denial().copy(command = listOf("not-a-refusal-before-setup")), denial().copy(stdout = "partial output"),
            denial().copy(stderr = "partial error"), denial().copy(supervisorIdentity = identity),
            denial().copy(reasons = listOf("process_supervisor_capacity_unavailable")),
            denial().copy(cancelled = true), denial().copy(metrics = AnalysisMetrics(outputBytes = 1)),
            denial().copy(metrics = AnalysisMetrics(cpuMillis = 0)), denial().copy(metrics = AnalysisMetrics(peakRssBytes = 0)),
        )
        for (outcome in outcomes) {
            val suite = newSuite(); val job = newJob(suite); val ticket = suite.begin("denial")
            suite.observe(ticket, outcome)
            assertFailsWith<IllegalStateException> { suite.checkedDenial(ticket) }
            assertFailsWith<IllegalStateException> { suite.begin("native") }
            suite.close(); assertRetained(job)
        }
    }

    @Test fun `full refusal valid native terminal and no-callback abstention allow only fresh retained jobs`() {
        val suite = newSuite(); val first = newJob(suite)
        val refusal = suite.begin("denial"); suite.observe(refusal, denial()); suite.checkedDenial(refusal)
        checkedNative(suite)
        val request = input(); val unsupported = suite.begin("abstention")
        suite.checkedAbstention(unsupported, request, abstention(request))
        val summary = suite.finishJob()
        assertEquals(3, summary["checked_calls"]!!.jsonPrimitive.int)
        assertEquals(1, summary["native_terminals"]!!.jsonPrimitive.int)
        assertEquals(1, summary["refusal_terminals"]!!.jsonPrimitive.int)
        assertEquals(1, summary["no_callback_abstentions"]!!.jsonPrimitive.int)
        val next = suite.startJob(repository, destination, "next-method")
        assertNotEquals(first.runDirectory, next.runDirectory)
        suite.close(); assertRetained(first); assertRetained(next)
    }

    @Test fun `unsupported result with a runtime callback is permanently quarantined`() {
        val suite = newSuite(); val job = newJob(suite); val request = input()
        val ticket = suite.begin("abstention"); suite.observe(ticket, denial())
        assertFailsWith<IllegalStateException> { suite.checkedAbstention(ticket, request, abstention(request)) }
        assertFailsWith<IllegalStateException> { suite.begin("native") }
        suite.close(); assertRetained(job)
    }

    @Test fun `unsupported flags rule identity and target must match the explicit request`() {
        val request = input()
        val correct = abstention(request)
        val wrong = listOf(
            correct.copy(providerId = "different"), correct.copy(coverage = emptyList()),
            correct.copy(coverage = listOf(correct.coverage.single().copy(ruleId = "different"))),
            correct.copy(coverage = listOf(correct.coverage.single().copy(context = RuleContext(target = "native")))),
            correct.copy(coverage = listOf(correct.coverage.single().copy(implemented = true))),
            correct.copy(coverage = listOf(correct.coverage.single().copy(tested = true))),
        )
        for (result in wrong) {
            val suite = newSuite(); val job = newJob(suite); val ticket = suite.begin("abstention")
            assertFailsWith<IllegalStateException> { suite.checkedAbstention(ticket, request, result) }
            suite.close(); assertRetained(job)
        }
    }

    @Test fun `uncertain throw is sticky across both methods and close performs no cleanup`() {
        val suite = newSuite(); val job = newJob(suite)
        val failure = IllegalStateException("synthetic uncertain throw")
        val actual = assertFailsWith<IllegalStateException> { suite.native<Unit>(identity) { throw failure } }
        assertSame(failure, actual)
        assertFailsWith<IllegalStateException> { suite.startJob(repository, destination, "other-method") }
        suite.close(); assertRetained(job)
    }

    @Test fun `failure after observed confirmation cannot turn the pending call into cleanup proof`() {
        val suite = newSuite(); val job = newJob(suite)
        val ticket = suite.begin("native"); suite.observe(ticket, native())
        suite.failed(IllegalStateException("synthetic failure before driver check"))
        assertFailsWith<IllegalStateException> { suite.checkedNative(ticket, identity) }
        assertEquals(0, suite.summary()["native_terminals"]!!.jsonPrimitive.int)
        suite.close(); assertRetained(job)
    }

    @Test fun `checkpoint I-O failure prevents provider entry and quarantine writer cannot reopen admission`() {
        var failWrites = false
        val suite = RetainedFixtureSuite(provider) { if (failWrites) throw java.io.IOException("synthetic checkpoint failure") }
        val job = newJob(suite); failWrites = true
        var entered = 0
        val failure = assertFailsWith<java.io.IOException> { suite.native(identity) { entered++; Unit } }
        assertEquals(0, entered)
        assertTrue(failure.suppressed.isEmpty(), "A rejected writer must not be retried by failure reporting")
        assertEquals("unknown", suite.summary()["custody_observation"]!!.jsonPrimitive.content)
        assertTrue(suite.summary()["suite_admission_stopped"]!!.jsonPrimitive.boolean)
        assertFailsWith<IllegalStateException> { suite.startJob(repository, destination, "retry") }
        suite.close(); assertRetained(job)
    }

    @Test fun `close with unresolved ownership does not write restore permissions or delete ancestors`() {
        var gates = 0
        val suite = RetainedFixtureSuite(provider) { ++gates }; val job = newJob(suite)
        val immutable = job.scratchDirectory.resolve("immutable.kt"); Files.writeString(immutable, "retained\n")
        val permissions = PosixFilePermissions.fromString("r--r--r--"); Files.setPosixFilePermissions(immutable, permissions)
        suite.begin("native"); val before = gates; suite.close()
        assertEquals(before, gates)
        assertEquals(permissions, Files.getPosixFilePermissions(immutable, NOFOLLOW_LINKS))
        assertRetained(job)
    }

    @Test fun `symlink ancestor is rejected before outside job creation`() {
        val outside = Files.createDirectory(repository.resolve("outside"))
        Files.createSymbolicLink(repository.resolve("build"), outside)
        val suite = newSuite()
        assertFailsWith<IllegalArgumentException> { newJob(suite) }
        Files.list(outside).use { assertEquals(0L, it.count()) }
        suite.close()
    }

    @Test fun `replaced ancestor stops before another provider entry and keeps original job bytes`() {
        val suite = newSuite(); val job = newJob(suite)
        val source = job.scratchDirectory.resolve("keep.kt"); Files.writeString(source, "retained original\n")
        val runs = job.runDirectory.parent; val retainedRuns = runs.resolveSibling("retained-runs")
        Files.move(runs, retainedRuns); Files.createDirectory(runs)
        var entered = 0
        assertFails { suite.native(identity) { entered++; Unit } }
        assertEquals(0, entered)
        assertEquals("retained original\n", Files.readString(retainedRuns.resolve(job.runDirectory.fileName).resolve("scratch/keep.kt")))
        suite.close()
        assertTrue(Files.isDirectory(retainedRuns.resolve(job.runDirectory.fileName).resolve("scratch")))
    }

    @Test fun `existing checkpoint never overwritten and late JUnit failure keeps checked method quarantined`() {
        val suite = newSuite(); val job = newJob(suite)
        val reservedName = job.runDirectory.resolve("scratch-lifecycle-0002.json")
        Files.writeString(reservedName, "preserve exact original\n")
        assertFails { suite.begin("native") }
        assertEquals("preserve exact original\n", Files.readString(reservedName))
        suite.close(); assertRetained(job)

        val lateSuite = newSuite(); val checked = newJob(lateSuite)
        checkedNative(lateSuite); lateSuite.finishJob()
        lateSuite.failed(IllegalStateException("synthetic late JUnit failure"), checked)
        assertTrue(lateSuite.summary(checked)["quarantined"]!!.jsonPrimitive.boolean)
        assertFailsWith<IllegalStateException> { lateSuite.startJob(repository, destination, "next-method") }
        lateSuite.close(); assertRetained(checked)
    }

    private fun context(id: String): ExtensionContext = Proxy.newProxyInstance(
        ExtensionContext::class.java.classLoader, arrayOf(ExtensionContext::class.java),
    ) { proxy, method, args -> when (method.name) {
        "getUniqueId" -> id
        "toString" -> "synthetic-fixture-method:$id"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.singleOrNull()
        else -> error("Control must not need another JUnit context API: ${method.name}")
    } } as ExtensionContext

    private fun start(evidence: RetainedFixtureEvidence, id: String): RetainedFixtureEvidence.Run {
        evidence.beforeEach(context(id))
        return evidence.startRun(repository, destination, "control")
    }

    private fun sidecar(tested: Boolean = false) = buildJsonObject {
        put("rules", JsonArray(listOf(buildJsonObject {
            put("rule_id", rule); put("tested", tested); put("qualified", false)
            put("method_cases_checked", true)
        })))
    }

    private fun complete(suite: RetainedFixtureSuite, evidence: RetainedFixtureEvidence, id: String): RetainedFixtureEvidence.Run {
        val run = start(evidence, id)
        run.artifact("start/synthetic-source.kt", "retained first method\n".toByteArray())
        checkedNative(suite)
        run.sidecarCandidate("control", sidecar())
        run.finishMethod()
        evidence.testSuccessful(context(id))
        return run
    }

    /** No link following: compare both the byte set and directory/link footprint. */
    private fun tree(directory: Path): Map<String, String> = Files.walk(directory).use { paths ->
        paths.sorted().toList().associate { path ->
            directory.relativize(path).toString() to when {
                Files.isSymbolicLink(path) -> "link:${Files.readSymbolicLink(path)}"
                Files.isDirectory(path, NOFOLLOW_LINKS) -> "directory"
                else -> Digests.sha256(Files.readAllBytes(path))
            }
        }
    }

    @Test fun `actual adapter keeps first completed job immutable when second initial checkpoint fails`() {
        var failStart = false
        val suite = RetainedFixtureSuite(provider, checkpointGate = { event ->
            if (failStart && event["phase"]!!.jsonPrimitive.content == "retained_private_job_created") throw IOException("second initial checkpoint")
        })
        val evidence = RetainedFixtureEvidence(suite)
        val first = complete(suite, evidence, "first")
        val original = tree(first.runDirectory)
        failStart = true
        evidence.beforeEach(context("second"))
        val failure = assertFailsWith<IOException> { evidence.startRun(repository, destination, "second") }
        val second = assertNotNull(evidence.runFor("second"))
        assertNotEquals(first.runDirectory, second.runDirectory)
        evidence.testFailed(context("second"), failure)
        assertEquals(original, tree(first.runDirectory))
        assertEquals("second", suite.summary(second.job)["junit_method_id"]!!.jsonPrimitive.content)
        assertTrue(suite.summary(second.job)["job_initialized"]!!.jsonPrimitive.boolean)
        assertEquals(0, second.job.sequence)
        assertFailsWith<IllegalStateException> { evidence.close() }
        assertEquals(original, tree(first.runDirectory))
        assertRetained(second.job)
    }

    @Test fun `actual adapter records partial second setup without borrowing first paths or nonexistent scratch`() {
        for (stage in listOf("run_created_before_scratch", "scratch_created_before_artifacts")) {
            var failSetup = false
            val suite = RetainedFixtureSuite(provider, setupGate = { job, actual ->
                if (failSetup && actual == stage) {
                    if (stage == "run_created_before_scratch") throw IOException("scratch not created")
                    Files.writeString(job.artifactDirectory, "preserve partial setup collision\n")
                }
            })
            val evidence = RetainedFixtureEvidence(suite)
            val first = complete(suite, evidence, "first-$stage")
            val original = tree(first.runDirectory)
            failSetup = true
            val id = "second-$stage"
            evidence.beforeEach(context(id))
            val failure = assertFailsWith<IOException> { evidence.startRun(repository, destination, "second") }
            val second = assertNotNull(evidence.runFor(id))
            evidence.testFailed(context(id), failure)
            val summary = suite.summary(second.job)
            assertTrue(summary["run_created"]!!.jsonPrimitive.boolean)
            assertEquals(stage != "run_created_before_scratch", summary["scratch_created"]!!.jsonPrimitive.boolean)
            assertFalse(summary["artifact_directory_created"]!!.jsonPrimitive.boolean)
            assertFalse(summary["job_initialized"]!!.jsonPrimitive.boolean)
            if (stage == "run_created_before_scratch") assertFalse(Files.exists(second.scratchDirectory, NOFOLLOW_LINKS))
            else assertEquals("preserve partial setup collision\n", Files.readString(second.job.artifactDirectory))
            assertEquals(original, tree(first.runDirectory))
            val partial = tree(second.runDirectory)
            assertFailsWith<IllegalStateException> { evidence.close() }
            assertEquals(partial, tree(second.runDirectory))
            assertEquals(original, tree(first.runDirectory))
        }
    }

    @Test fun `actual adapter first artifact failure in second method never rewrites first completion`() {
        var failArtifact = false
        val suite = RetainedFixtureSuite(provider, ioGate = { _, phase, name ->
            if (failArtifact && phase == "before_open" && name.startsWith("artifact-")) throw IOException("first artifact unavailable")
        })
        val evidence = RetainedFixtureEvidence(suite)
        val first = complete(suite, evidence, "first")
        val original = tree(first.runDirectory)
        val second = start(evidence, "second")
        failArtifact = true
        val failure = assertFailsWith<IOException> { second.artifact("nested/first-start.kt", byteArrayOf(1)) }
        evidence.testFailed(context("second"), failure)
        assertEquals(0, suite.summary(second.job)["artifact_count"]!!.jsonPrimitive.int)
        assertTrue(tree(second.job.artifactDirectory).values.all { it == "directory" })
        assertEquals(original, tree(first.runDirectory))
        assertFailsWith<IllegalStateException> { evidence.close() }
        assertEquals(original, tree(first.runDirectory))
    }

    @Test fun `actual artifact and failure pathways stop on run scratch or artifact replacement without outside mutation`() {
        for (kind in listOf("run", "scratch", "artifacts")) for (symlink in listOf(false, true)) {
            val suite = newSuite(); val evidence = RetainedFixtureEvidence(suite)
            val run = start(evidence, "$kind-$symlink")
            run.artifact("before/record.json", "retained".toByteArray())
            Files.writeString(run.scratchDirectory.resolve("source.kt"), "immutable input\n")
            val target = when (kind) { "run" -> run.runDirectory; "scratch" -> run.scratchDirectory; else -> run.job.artifactDirectory }
            val retainedPath = target.resolveSibling("retained-${target.fileName}")
            val originalTarget = tree(target)
            Files.move(target, retainedPath)
            val outside = Files.createTempDirectory(repository, "outside-control-")
            Files.writeString(outside.resolve("sentinel"), "outside untouched\n")
            if (symlink) Files.createSymbolicLink(target, outside)
            else { Files.createDirectory(target); Files.writeString(target.resolve("sentinel"), "replacement untouched\n") }
            val outsideBefore = tree(outside); val replacementBefore = tree(target)
            val keptRun = if (kind == "run") retainedPath else run.runDirectory
            val beforeFailure = tree(keptRun)
            val failure = assertFails { run.artifact("after/rejected.json", byteArrayOf(2)) }
            evidence.testFailed(context("$kind-$symlink"), failure)
            assertEquals("unknown", suite.summary(run.job)["custody_observation"]!!.jsonPrimitive.content)
            assertEquals(outsideBefore, tree(outside)); assertEquals(replacementBefore, tree(target))
            assertEquals(originalTarget, tree(retainedPath)); assertEquals(beforeFailure, tree(keptRun))
            assertFailsWith<IllegalStateException> { evidence.close() }
            assertEquals(outsideBefore, tree(outside)); assertEquals(beforeFailure, tree(keptRun))
        }
    }

    @Test fun `actual success and failure watchers do not reopen a replaced completion destination`() {
        for (successCallback in listOf(false, true)) {
            val suite = newSuite(); val evidence = RetainedFixtureEvidence(suite)
            val id = "watcher-$successCallback"; val run = start(evidence, id)
            checkedNative(suite); run.finishMethod()
            val original = tree(run.runDirectory)
            val retainedPath = run.runDirectory.resolveSibling("retained-${run.runDirectory.fileName}")
            Files.move(run.runDirectory, retainedPath)
            val outside = Files.createTempDirectory(repository, "outside-watcher-")
            Files.writeString(outside.resolve("completion-junit-method-success-observed.json"), "outside prior attempt\n")
            Files.writeString(outside.resolve("completion.json"), "outside historical completion\n")
            Files.createSymbolicLink(run.runDirectory, outside)
            val before = tree(outside)
            if (successCallback) evidence.testSuccessful(context(id))
            else evidence.testFailed(context(id), AssertionError("late method failure"))
            assertEquals(before, tree(outside)); assertEquals(original, tree(retainedPath))
            assertEquals("unknown", suite.summary(run.job)["custody_observation"]!!.jsonPrimitive.content)
            assertFailsWith<IllegalStateException> { evidence.close() }
            assertEquals(before, tree(outside)); assertEquals(original, tree(retainedPath))
        }
    }

    @Test fun `actual flat artifact mapping retains distinct logical names and never overwrites an existing target or symlink`() {
        for (symlink in listOf(false, true)) {
            val suite = newSuite(); val evidence = RetainedFixtureEvidence(suite)
            val id = "collision-$symlink"; val run = start(evidence, id)
            val logical = "start/nested/source.kt"
            val target = run.job.artifactDirectory.resolve(RetainedFixtureSuite.artifactName(logical))
            val outside = repository.resolve("outside-file-$symlink")
            Files.writeString(outside, "outside original\n")
            if (symlink) Files.createSymbolicLink(target, outside)
            else {
                run.artifact(logical, "first original\n".toByteArray())
                val other = run.artifact("start-nested/source.kt", "distinct logical name\n".toByteArray())
                assertNotEquals(repository.relativize(target).toString(), other["path"]!!.jsonPrimitive.content)
            }
            val before = tree(run.runDirectory)
            val failure = assertFails { run.artifact(logical, "must not replace\n".toByteArray()) }
            evidence.testFailed(context(id), failure)
            assertEquals("outside original\n", Files.readString(outside))
            assertEquals(before, tree(run.runDirectory))
            if (!symlink) assertEquals("first original\n", Files.readString(target))
            assertFailsWith<IllegalStateException> { evidence.close() }
        }
    }

    @Test fun `actual watcher target failure and candidate collision retain all old bytes instead of replacing targets`() {
        for (candidateCollision in listOf(false, true)) {
            val suite = newSuite(); val evidence = RetainedFixtureEvidence(suite)
            val id = "candidate-$candidateCollision"; val run = start(evidence, id)
            run.sidecarCandidate("control", sidecar())
            val candidate = run.runDirectory.resolve("sidecar-candidate-control.json")
            val candidateBytes = Files.readAllBytes(candidate)
            if (candidateCollision) {
                val failure = assertFails { run.sidecarCandidate("control", sidecar()) }
                evidence.testFailed(context(id), failure)
            } else {
                checkedNative(suite); run.finishMethod()
                val outside = repository.resolve("outside-completion")
                Files.writeString(outside, "outside completion bytes\n")
                Files.createSymbolicLink(run.runDirectory.resolve("completion-junit-method-success-observed.json"), outside)
                val before = tree(run.runDirectory)
                evidence.testSuccessful(context(id))
                assertEquals(before, tree(run.runDirectory))
                assertEquals("outside completion bytes\n", Files.readString(outside))
            }
            assertContentEquals(candidateBytes, Files.readAllBytes(candidate))
            assertFailsWith<IllegalStateException> { evidence.close() }
            assertContentEquals(candidateBytes, Files.readAllBytes(candidate))
        }
    }

    @Test fun `terminal and method counters stay uncommitted when actual channel close throws after writing`() {
        for (finishing in listOf(false, true)) {
            var failClose = false
            val suite = RetainedFixtureSuite(provider, channelDecorator = { channel ->
                object : SeekableByteChannel by channel {
                    override fun close() { channel.close(); if (failClose) throw IOException("synthetic channel close failure") }
                }
            })
            val job = newJob(suite); val ticket = suite.begin("native"); suite.observe(ticket, native())
            if (finishing) suite.checkedNative(ticket, identity)
            val sequence = job.sequence; failClose = true
            assertFailsWith<IOException> { if (finishing) suite.finishJob() else suite.checkedNative(ticket, identity) }
            val summary = suite.summary(job)
            assertEquals(if (finishing) 1 else 0, summary["checked_calls"]!!.jsonPrimitive.int)
            assertEquals(if (finishing) 1 else 0, summary["native_terminals"]!!.jsonPrimitive.int)
            assertFalse(summary["method_assertions_checked"]!!.jsonPrimitive.boolean)
            assertEquals(!finishing, summary["ownership_unsettled"]!!.jsonPrimitive.boolean)
            assertEquals(sequence, job.sequence)
            val provisional = job.runDirectory.resolve("scratch-lifecycle-${(sequence + 1).toString().padStart(4, '0')}.json")
            val event = Json.parseToJsonElement(Files.readString(provisional)).jsonObject
            assertTrue(event["candidate_only"]!!.jsonPrimitive.boolean)
            assertNotNull(event["proposed_state_after_successful_write_close_and_validation"])
            val before = tree(job.runDirectory); suite.close(); assertEquals(before, tree(job.runDirectory))
        }
    }

    @Test fun `terminal and finish post-write custody loss does not commit prospective state or write replacement`() {
        for (finishing in listOf(false, true)) {
            var replaceAfterClose = false
            var moved: Path? = null
            val suite = RetainedFixtureSuite(provider, ioGate = { job, phase, _ ->
                if (replaceAfterClose && phase == "after_close") {
                    replaceAfterClose = false
                    val kept = job.runDirectory.resolveSibling("retained-${job.runDirectory.fileName}")
                    Files.move(job.runDirectory, kept); Files.createDirectory(job.runDirectory); moved = kept
                }
            })
            val job = newJob(suite); val ticket = suite.begin("native"); suite.observe(ticket, native())
            if (finishing) suite.checkedNative(ticket, identity)
            val sequence = job.sequence; replaceAfterClose = true
            assertFails { if (finishing) suite.finishJob() else suite.checkedNative(ticket, identity) }
            val summary = suite.summary(job)
            assertEquals(if (finishing) 1 else 0, summary["checked_calls"]!!.jsonPrimitive.int)
            assertFalse(summary["method_assertions_checked"]!!.jsonPrimitive.boolean)
            assertEquals(sequence, job.sequence)
            assertEquals("unknown", summary["custody_observation"]!!.jsonPrimitive.content)
            assertEquals(mapOf("" to "directory"), tree(job.runDirectory))
            val kept = assertNotNull(moved)
            assertTrue(Files.isRegularFile(kept.resolve("scratch-lifecycle-${(sequence + 1).toString().padStart(4, '0')}.json")))
            val before = tree(kept); suite.failed(IOException("watcher after identity loss"), job); suite.close()
            assertEquals(before, tree(kept)); assertEquals(mapOf("" to "directory"), tree(job.runDirectory))
        }
    }

    @Test fun `all terminal checkpoint failures and finish failure preserve only earlier committed counters`() {
        for (kind in listOf("native", "denial", "abstention", "finish")) {
            var failCheckpoint = false
            val suite = RetainedFixtureSuite(provider) { if (failCheckpoint) throw IOException("terminal checkpoint refused") }
            val job = newJob(suite)
            val ticket = suite.begin(if (kind == "finish") "native" else kind)
            if (kind != "abstention") suite.observe(ticket, if (kind == "denial") denial() else native())
            if (kind == "finish") suite.checkedNative(ticket, identity)
            val before = tree(job.runDirectory); val sequence = job.sequence
            failCheckpoint = true
            assertFailsWith<IOException> { when (kind) {
                "native" -> suite.checkedNative(ticket, identity)
                "denial" -> suite.checkedDenial(ticket)
                "abstention" -> input().let { suite.checkedAbstention(ticket, it, abstention(it)) }
                else -> suite.finishJob()
            } }
            assertEquals(if (kind == "finish") 1 else 0, suite.summary(job)["checked_calls"]!!.jsonPrimitive.int)
            assertFalse(suite.summary(job)["method_assertions_checked"]!!.jsonPrimitive.boolean)
            assertEquals(sequence, job.sequence); assertEquals(before, tree(job.runDirectory))
            suite.close()
        }
    }

    @Test fun `first execution binds ownership not discovery and foreign callbacks cannot perform path I-O`() {
        for (watcher in listOf(false, true)) {
            val discovered = AtomicReference<RetainedFixtureSuite>()
            val discovery = Thread { discovered.set(newSuite()) }
            discovery.start(); discovery.join(5_000); assertFalse(discovery.isAlive)
            val suite = assertNotNull(discovered.get()); val evidence = RetainedFixtureEvidence(suite)
            val run = start(evidence, "owner-$watcher")
            val ticket = suite.begin("native")
            val before = tree(run.runDirectory)
            val failure = AtomicReference<Throwable>()
            val foreign = Thread {
                try {
                    if (watcher) evidence.testFailed(context("owner-$watcher"), IOException("foreign watcher"))
                    else suite.observe(ticket, native())
                } catch (cause: Throwable) { failure.set(cause) }
            }
            foreign.start(); foreign.join(5_000); assertFalse(foreign.isAlive)
            assertTrue(failure.get() is IllegalStateException)
            assertEquals(before, tree(run.runDirectory))
            assertEquals(0, suite.summary(run.job)["checked_calls"]!!.jsonPrimitive.int)
            assertFailsWith<IllegalStateException> { suite.checkedNative(ticket, identity) }
            evidence.testFailed(context("owner-$watcher"), assertNotNull(failure.get()))
            assertFailsWith<IllegalStateException> { evidence.close() }
            assertEquals(before, tree(run.runDirectory))
        }
    }

    @Test fun `reentrant checkpoint and duplicate or late callback stop without any extra path I-O`() {
        for (mode in listOf("reentrant", "duplicate", "late")) {
            var reenter = false
            lateinit var suite: RetainedFixtureSuite
            suite = RetainedFixtureSuite(provider) { if (reenter) suite.begin("native") }
            val first = newJob(suite); val ticket = suite.begin("native"); suite.observe(ticket, native())
            if (mode == "late") { suite.checkedNative(ticket, identity); suite.finishJob(); newJob(suite) }
            val before = tree(destination)
            if (mode == "reentrant") {
                reenter = true
                assertFailsWith<IllegalStateException> { suite.checkedNative(ticket, identity) }
                assertEquals(0, suite.summary(first)["checked_calls"]!!.jsonPrimitive.int)
            } else assertFailsWith<IllegalStateException> { suite.observe(ticket, native()) }
            assertEquals(before, tree(destination))
            assertFailsWith<IllegalStateException> { suite.begin("native") }
            suite.close(); assertEquals(before, tree(destination))
        }
    }

    @Test fun `late or duplicate actual JUnit observations cannot write another method job`() {
        for (late in listOf(false, true)) {
            val suite = newSuite(); val evidence = RetainedFixtureEvidence(suite)
            complete(suite, evidence, "first")
            if (late) start(evidence, "second")
            val before = tree(destination)
            assertFailsWith<IllegalStateException> { evidence.testSuccessful(context("first")) }
            assertEquals(before, tree(destination))
            assertFailsWith<IllegalStateException> { evidence.close() }
            assertEquals(before, tree(destination))
        }
    }

    @Test fun `success failure abort watcher failure and after-all retain only candidates without touching live sidecars`() {
        val live = Files.createDirectories(destination.resolve("evidence"))
        val names = listOf("verified-jvm-syntax-native-preview1.json", "verified-jvm-coroutine-upstream-native-preview1.json")
        for (name in names) Files.writeString(live.resolve(name), "unrelated owner attempt $name\n")
        val historical = tree(live)
        for (outcome in listOf("success", "failure", "abort", "watcher-failure", "missing-watcher")) {
            val suite = newSuite(); val evidence = RetainedFixtureEvidence(suite)
            val run = start(evidence, outcome)
            checkedNative(suite)
            val reference = run.sidecarCandidate("control", sidecar())
            val candidatePath = repository.resolve(reference["path"]!!.jsonPrimitive.content)
            val candidateBytes = Files.readAllBytes(candidatePath)
            val candidate = Json.parseToJsonElement(candidateBytes.toString(Charsets.UTF_8)).jsonObject
            assertTrue(candidate["candidate_only"]!!.jsonPrimitive.boolean)
            assertFalse(candidate["run_complete"]!!.jsonPrimitive.boolean)
            assertFalse(candidate["publication_committed"]!!.jsonPrimitive.boolean)
            assertFalse(candidate["tested"]!!.jsonPrimitive.boolean)
            assertFalse(candidate["sidecar"]!!.jsonObject["rules"]!!.jsonArray.single().jsonObject["tested"]!!.jsonPrimitive.boolean)
            if (outcome == "abort") {
                suite.begin("native") // Still a synthetic pending call, not a child or a cleanup claim.
                evidence.testAborted(context(outcome), IOException("abort after allocation"))
            } else {
                run.finishMethod()
                when (outcome) {
                    "success" -> evidence.testSuccessful(context(outcome))
                    "failure" -> evidence.testFailed(context(outcome), AssertionError("late assertions failed"))
                    "watcher-failure" -> {
                        Files.writeString(run.runDirectory.resolve("completion-junit-method-success-observed.json"), "prior immutable target\n")
                        evidence.testSuccessful(context(outcome))
                    }
                }
            }
            assertEquals(historical, tree(live))
            if (outcome == "success") evidence.close() else assertFailsWith<IllegalStateException> { evidence.close() }
            assertEquals(historical, tree(live)); assertContentEquals(candidateBytes, Files.readAllBytes(candidatePath))
        }
    }

    @Test fun `opt-out callbacks allocate no jobs and tested candidates are rejected without live mutation`() {
        val suite = newSuite(); val evidence = RetainedFixtureEvidence(suite)
        for (id in listOf("disabled-first", "disabled-second")) {
            evidence.beforeEach(context(id))
            evidence.testAborted(context(id), IOException("synthetic pre-start opt-out assumption"))
            assertNull(evidence.runFor(id))
        }
        evidence.close()
        assertFalse(Files.exists(destination, NOFOLLOW_LINKS))

        val rejectingSuite = newSuite(); val rejecting = RetainedFixtureEvidence(rejectingSuite)
        val run = start(rejecting, "cannot-publish-tested")
        val failure = assertFailsWith<IllegalArgumentException> { run.sidecarCandidate("control", sidecar(tested = true)) }
        rejecting.testFailed(context("cannot-publish-tested"), failure)
        assertFalse(Files.exists(run.runDirectory.resolve("sidecar-candidate-control.json"), NOFOLLOW_LINKS))
        assertFalse(Files.exists(destination.resolve("evidence"), NOFOLLOW_LINKS))
        assertFailsWith<IllegalStateException> { rejecting.close() }
    }
}

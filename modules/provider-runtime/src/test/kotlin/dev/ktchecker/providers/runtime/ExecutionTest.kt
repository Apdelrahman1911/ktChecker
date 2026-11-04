package dev.ktchecker.providers.runtime

import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.*

class ExecutionTest {
    private fun fixture(mode: String, vararg args: String): Pair<AnalysisInput, ExecutionAuthority> {
        val work = Files.createTempDirectory("ktchecker-runtime-test-")
        val root = work.resolve("snapshot").createDirectories()
        root.resolve("Main.kt").writeText("fun main() = Unit\n")
        val file = root.resolve("Main.kt")
        val snapshot = SnapshotManifest(Digests.sha256("runtime-test-" + fileDigest(file)), "w", "t",
            listOf(SnapshotFile("Main.kt", fileDigest(file), Files.size(file))), "owner")
        val java = Path.of(System.getProperty("java.home"), "bin",
            if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toRealPath()
        val classpath = listOf(RuntimeProbe::class.java, kotlin.Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct()
            .joinToString(File.pathSeparator)
        val execution = TrustedExecution(java.toString(), listOf("-cp", classpath,
            RuntimeProbe::class.java.name, mode) + args, true, fileDigest(java), 3000, 32768)
        val input = AnalysisInput(snapshot, root.toString(), execution = execution,
            outputRoot = work.resolve("outputs").toString())
        val authority = ExactExecutionAuthority(ExecutionPermit(execution, snapshot.snapshotId, root, input.analysisKey(),
            verifySnapshot = { SnapshotSources(input).verify() }))
        return input to authority
    }

    @Test fun `approved transport flag never grants authority`() {
        val (input, _) = fixture("echo")
        val result = IsolatedProcessRunner().run(input)
        assertEquals("unavailable", result.status)
        assertEquals(listOf("execution_not_authorized"), result.reasons)
        assertFalse(result.executed)
    }

    @Test fun `argv and immutable identity are bound to exact owner profile`() {
        val (input, authority) = fixture("echo")
        assertEquals("unavailable", IsolatedProcessRunner().run(input.copy(
            execution = input.execution!!.copy(arguments = listOf("different"))), authority).status)
        assertEquals("unavailable", IsolatedProcessRunner().run(input.copy(
            snapshot = input.snapshot.copy(snapshotId = Digests.sha256("other"))), authority).status)
        assertEquals("unavailable", IsolatedProcessRunner().run(input.copy(
            context = input.context.copy(scope = input.context.scope.copy(target = "iosArm64"))), authority).status)
    }

    @Test fun `retained mutable transport collections cannot rewrite the granted execution profile`() {
        val (initial, _) = fixture("echo")
        val arguments = initial.execution!!.arguments.toMutableList()
        val environment = mutableMapOf("FIXTURE_MODE" to "original")
        val execution = initial.execution!!.copy(arguments = arguments, environment = environment)
        val input = initial.copy(execution = execution)
        val permit = ExecutionPermit(execution, input.snapshot.snapshotId, Path.of(input.materializedRoot), input.analysisKey(), {})
        val authority = ExactExecutionAuthority(permit)
        arguments += "later-argument"
        environment["FIXTURE_MODE"] = "changed"
        assertEquals("original", permit.execution.environment["FIXTURE_MODE"])
        assertFalse("later-argument" in permit.execution.arguments)
        assertNull(authority.authorize(input))
        assertFailsWith<UnsupportedOperationException> { (permit.execution.arguments as MutableList<String>).add("forbidden") }
    }

    @Test fun `descriptor mutation during verification invalidates an otherwise successful process`() {
        val (initial, _) = fixture("echo")
        val policies = mutableMapOf("fixture" to "before")
        val input = initial.copy(context = initial.context.copy(policies = policies))
        val calls = AtomicInteger()
        val authority = ExactExecutionAuthority(ExecutionPermit(input.execution!!, input.snapshot.snapshotId,
            Path.of(input.materializedRoot), input.analysisKey(), {
                SnapshotSources(input).verify()
                if (calls.incrementAndGet() == 2) policies["fixture"] = "after"
            }))
        val result = IsolatedProcessRunner().run(input, authority)
        if (unsupported(result)) return
        assertEquals("failed", result.status)
        assertContains(result.reasons, "analysis_descriptor_changed")
    }

    @Test fun `slow preparation overruns budget without ever launching the report writer`() {
        val (initial, _) = fixture("report", "{output}/should-not-exist.xml", "{snapshot}/Main.kt")
        val input = initial.copy(execution = initial.execution!!.copy(timeoutMillis = 100))
        val authority = ExactExecutionAuthority(ExecutionPermit(input.execution!!, input.snapshot.snapshotId,
            Path.of(input.materializedRoot), input.analysisKey(), {
                SnapshotSources(input).verify()
                Thread.sleep(200)
            }))
        val result = IsolatedProcessRunner().run(input, authority)
        if (unsupported(result)) return
        assertEquals("failed", result.status)
        assertTrue(result.timedOut)
        assertFalse(result.executed)
        assertFalse(Files.exists(result.outputDirectory!!.resolve("should-not-exist.xml")))
    }

    @Test fun `post verification overrun fails even when a quick process exited successfully`() {
        val (initial, _) = fixture("echo")
        val input = initial.copy(execution = initial.execution!!.copy(timeoutMillis = 800))
        val calls = AtomicInteger()
        val authority = ExactExecutionAuthority(ExecutionPermit(input.execution!!, input.snapshot.snapshotId,
            Path.of(input.materializedRoot), input.analysisKey(), {
                SnapshotSources(input).verify()
                if (calls.incrementAndGet() == 2) Thread.sleep(900)
            }))
        val result = IsolatedProcessRunner().run(input, authority)
        if (unsupported(result)) return
        assertEquals("failed", result.status)
        assertEquals(0, result.exitCode)
        assertTrue(result.timedOut)
        assertContains(result.reasons, "execution_timeout")
    }

    @Test fun `cancellation during output preparation prevents the process from spawning`() {
        val (input, _) = fixture("echo")
        val cancelled = AtomicBoolean()
        val authority = ExactExecutionAuthority(ExecutionPermit(input.execution!!, input.snapshot.snapshotId,
            Path.of(input.materializedRoot), input.analysisKey(), { SnapshotSources(input).verify() },
            { cancelled.set(true) }))
        val result = IsolatedProcessRunner().run(input, authority, CancellationToken { cancelled.get() })
        if (unsupported(result)) return
        assertEquals("cancelled", result.status)
        assertFalse(result.executed)
    }

    @Test fun `unique reports use only snapshot and output placeholders`() {
        val (input, authority) = fixture("report", "{output}/report.xml", "{snapshot}/Main.kt")
        val runner = IsolatedProcessRunner()
        val first = runner.run(input, authority)
        if (unsupported(first)) return
        val second = runner.run(input, authority)
        assertEquals("executed", first.status, first.reasons.toString())
        assertEquals(0, first.exitCode)
        assertNotEquals(first.outputDirectory, second.outputDirectory)
        assertEquals("report", first.report("report.xml").toString(Charsets.UTF_8))
        assertContains(first.stdout, input.materializedRoot)
        assertFailsWith<ExecutionProblem> { first.report("../report.xml") }
        assertFailsWith<ExecutionProblem> { first.report("missing.xml") }
        assertFailsWith<ExecutionProblem> { first.report("report.xml", 2) }
        Files.createSymbolicLink(first.outputDirectory!!.resolve("alias.xml"), Path.of("report.xml"))
        assertFailsWith<ExecutionProblem> { first.report("alias.xml") }
    }

    @Test fun `source writes invalidate otherwise successful execution`() {
        val (input, authority) = fixture("write", "{snapshot}/Main.kt")
        val result = IsolatedProcessRunner().run(input, authority)
        if (unsupported(result)) return
        assertEquals("failed", result.status)
        assertContains(result.reasons, "snapshot_content_mismatch")
    }

    @Test fun `executable drift is not an executed check`() {
        val (input, _) = fixture("echo")
        val bad = input.copy(execution = input.execution!!.copy(executableDigest = Digests.sha256("bad")))
        val authority = ExactExecutionAuthority(ExecutionPermit(bad.execution!!, bad.snapshot.snapshotId,
            Path.of(bad.materializedRoot), bad.analysisKey(), { SnapshotSources(bad).verify() }))
        val result = IsolatedProcessRunner().run(bad, authority)
        assertEquals("unavailable", result.status)
        assertContains(result.reasons, "executable_digest_mismatch")
    }

    @Test fun `bounded output and timeout are failures not empty success`() {
        val (spam, spamAuthority) = fixture("spam")
        val overflow = IsolatedProcessRunner().run(spam, spamAuthority)
        if (unsupported(overflow)) return
        assertEquals("failed", overflow.status)
        assertTrue(overflow.truncated)
        assertTrue(overflow.stdout.toByteArray().size <= spam.execution!!.maxOutputBytes)

        val (sleep, _) = fixture("sleep")
        val timed = sleep.copy(execution = sleep.execution!!.copy(timeoutMillis = 120))
        val authority = ExactExecutionAuthority(ExecutionPermit(timed.execution!!, timed.snapshot.snapshotId,
            Path.of(timed.materializedRoot), timed.analysisKey(), { SnapshotSources(timed).verify() }))
        val result = IsolatedProcessRunner().run(timed, authority)
        assertEquals("failed", result.status)
        assertTrue(result.timedOut)
        assertFalse(result.successfulExecution)
    }

    @Test fun `invalid UTF-8 is not silently repaired into provider evidence`() {
        val (input, authority) = fixture("invalid-utf8")
        val result = IsolatedProcessRunner().run(input, authority)
        if (unsupported(result)) return
        assertEquals("failed", result.status)
        assertContains(result.reasons, "process_output_invalid_utf8")
    }

    @Test fun `quick parent cannot leave same-group orphan writing a late report`() {
        val (input, authority) = fixture("orphan", "{output}/late.txt", "{output}/child.pid")
        val result = IsolatedProcessRunner().run(input, authority)
        if (unsupported(result)) return
        assertEquals("executed", result.status, result.reasons.toString())
        assertTrue(result.report("child.pid").isNotEmpty())
        Thread.sleep(1300)
        assertFalse(Files.exists(result.outputDirectory!!.resolve("late.txt")))
    }

    @Test fun `cancellation terminates observed owned descendants`() {
        val (input, authority) = fixture("child", "{output}/child.pid")
        val start = System.nanoTime()
        val result = IsolatedProcessRunner().run(input, authority,
            CancellationToken { (System.nanoTime() - start) / 1_000_000 > 900 })
        if (unsupported(result)) return
        assertEquals("cancelled", result.status, result.reasons.toString())
        val pid = result.report("child.pid").toString(Charsets.UTF_8).toLong()
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
    }

    private fun unsupported(result: ProcessOutcome): Boolean {
        if (result.reasons != listOf("process_group_containment_unavailable")) return false
        assertEquals("unavailable", result.status)
        assertFalse(result.executed)
        return true
    }

    @Test fun `locations cannot refer to uncaptured or outside sources`() {
        val (input, _) = fixture("echo")
        val sources = SnapshotSources(input)
        sources.verify()
        assertEquals("Main.kt", sources.location("Main.kt", 1, 4).path)
        assertEquals(input.snapshot.files.single().digest,
            sources.location(Path.of(input.materializedRoot, "Main.kt").toUri().toString(), 1, 4).fileDigest)
        assertFailsWith<ExecutionProblem> { sources.location("../Main.kt") }
        assertFailsWith<ExecutionProblem> { sources.location("missing.kt") }
        assertFailsWith<ExecutionProblem> { sources.location("Main.kt", 10, 0) }
        assertFailsWith<ExecutionProblem> { sources.location("Main.kt", 1, -1) }
    }

    @Test fun `binary resource no-range locations stay file anchors and never fabricated text ranges`() {
        val (initial, _) = fixture("echo")
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0xff.toByte())
        Files.write(Path.of(initial.materializedRoot, "image.png"), bytes)
        val input = initial.copy(snapshot = initial.snapshot.copy(files = initial.snapshot.files +
            SnapshotFile("image.png", Digests.sha256(bytes), bytes.size.toLong())))
        val sources = SnapshotSources(input)
        sources.verify()
        val anchor = sources.location("image.png")
        assertEquals("image.png", anchor.path)
        assertEquals(Digests.sha256(bytes), anchor.fileDigest)
        assertEquals("", anchor.excerpt)
        assertEquals("location_source_not_utf8", assertFailsWith<ExecutionProblem> { sources.location("image.png", 1, 0) }.code)
        assertEquals("location_range_incomplete", assertFailsWith<ExecutionProblem> { sources.location("Main.kt", column = 0) }.code)
    }
}

object RuntimeProbe {
    @JvmStatic fun main(args: Array<String>) {
        when (args[0]) {
            "echo" -> println("probe")
            "report" -> { Files.writeString(Path.of(args[1]), "report"); println(args[2]) }
            "write" -> Files.writeString(Path.of(args[1]), "modified")
            "spam" -> repeat(100000) { println("x".repeat(1024)) }
            "invalid-utf8" -> System.out.write(byteArrayOf(0xC3.toByte(), 0x28))
            "sleep" -> Thread.sleep(20000)
            "child" -> {
                val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
                val child = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                    RuntimeProbe::class.java.name, "sleep").start()
                Files.writeString(Path.of(args[1]), child.pid().toString())
                Thread.sleep(20000)
            }
            "orphan" -> {
                val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
                val child = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                    RuntimeProbe::class.java.name, "late-write", args[1]).start()
                Files.writeString(Path.of(args[2]), child.pid().toString())
            }
            "late-write" -> { Thread.sleep(1000); Files.writeString(Path.of(args[1]), "late") }
        }
    }
}

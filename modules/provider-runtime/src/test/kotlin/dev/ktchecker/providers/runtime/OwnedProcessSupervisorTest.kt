package dev.ktchecker.providers.runtime

import dev.ktchecker.contracts.CancellationToken
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class OwnedProcessSupervisorTest {
    private fun frame(tag: Int, payload: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size).array() + payload

    private fun terminal(cause: Int = 0, flags: Int = SupervisorFlags.COMPLETED, exit: Int = 0,
                         observed: Long = 0, captured: Long = observed, reserved: Int = 0): ByteArray =
        frame(3, ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN).putInt(cause).putInt(flags).putInt(exit)
            .putInt(reserved).putLong(observed).putLong(captured).array())

    private val magic = "KTCOWN1\n".toByteArray(Charsets.US_ASCII)
    private fun wire(output: ByteArray = byteArrayOf(), error: ByteArray = byteArrayOf(), cause: Int = 0,
                     flags: Int = SupervisorFlags.COMPLETED, exit: Int = 0, observed: Long = output.size.toLong() + error.size): ByteArray =
        magic + (if (output.isEmpty()) byteArrayOf() else frame(1, output)) +
            (if (error.isEmpty()) byteArrayOf() else frame(2, error)) +
            terminal(cause, flags, exit, observed, output.size.toLong() + error.size)

    private fun parse(bytes: ByteArray, maximum: Int = 32768, input: InputStream = ByteArrayInputStream(bytes)): SupervisorWireSnapshot =
        SupervisorFrameReader(maximum).also { it.read(input); assertTrue(it.done.get()) }.snapshot()

    private fun invalid(bytes: ByteArray, maximum: Int = 32768): SupervisorWireSnapshot = parse(bytes, maximum).also {
        assertEquals("process_supervisor_protocol_error", it.problem)
    }

    @Test fun `binary framing preserves separated stdout stderr and measured counts`() {
        val result = parse(wire("abc".toByteArray(), "defg".toByteArray(), exit = 7))
        assertNull(result.problem)
        assertTrue(result.eof)
        assertContentEquals("abc".toByteArray(), result.stdout)
        assertContentEquals("defg".toByteArray(), result.stderr)
        assertEquals(7, result.terminal!!.childExit)
        assertEquals(7L, result.terminal.observedOutput)
        assertEquals(7L, result.captured)
    }

    @Test fun `every field can be fragmented across transport reads`() {
        val expected = wire("split".toByteArray())
        val input = object : ByteArrayInputStream(expected) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, minOf(length, 1))
        }
        assertNull(parse(expected, input = input).problem)
    }

    @Test fun `all truncated prefixes lack complete framed evidence`() {
        val complete = wire("captured".toByteArray())
        for (length in complete.indices) invalid(complete.copyOf(length))
        assertNull(parse(complete).problem)
    }

    @Test fun `magic unknown tags unsigned lengths and zero sized output fail closed`() {
        invalid("KTCOWN2\n".toByteArray() + terminal())
        invalid(magic + frame(0, byteArrayOf()))
        invalid(magic + frame(255, byteArrayOf()))
        invalid(magic + byteArrayOf(1, -1, -1, -1, -1))
        invalid(magic + frame(1, byteArrayOf()))
        invalid(magic + frame(2, byteArrayOf()))
        invalid(magic + frame(1, ByteArray(8193)))
        invalid(magic + frame(3, ByteArray(31)))
        invalid(magic + frame(3, ByteArray(33)))
    }

    @Test fun `combined output bound applies across both streams before allocation`() {
        val excess = magic + frame(1, ByteArray(8)) + frame(2, ByteArray(3)) + terminal(observed = 11)
        val result = invalid(excess, maximum = 10)
        assertEquals(8L, result.captured)
        assertEquals(8, result.stdout.size)
        assertTrue(result.stderr.isEmpty())
        assertNull(parse(wire(ByteArray(7), ByteArray(3)), maximum = 10).problem)
    }

    @Test fun `terminal reserved bits unknown flags and status scalars are rejected`() {
        for (bad in listOf(
            terminal(reserved = 1), terminal(flags = SupervisorFlags.COMPLETED or (1 shl 14)),
            terminal(cause = -1), terminal(cause = 11), terminal(exit = -2), terminal(exit = 256),
            terminal(exit = -1), terminal(cause = 7, flags = SupervisorFlags.QUIESCENT, exit = 0),
            terminal(observed = -1), terminal(captured = -1), terminal(observed = 1, captured = 1),
        )) invalid(magic + bad)
    }

    @Test fun `successful final requires exact completion flags and cannot omit diagnostic bytes`() {
        for (flag in listOf(SupervisorFlags.EXIT_OBSERVED, SupervisorFlags.LEADER_REAPED, SupervisorFlags.QUIESCENT,
            SupervisorFlags.GROUP_READY, SupervisorFlags.EXEC_CONFIRMED)) {
            invalid(magic + terminal(flags = SupervisorFlags.COMPLETED xor flag))
        }
        invalid(magic + terminal(observed = 1, captured = 0))
        invalid(magic + terminal(flags = SupervisorFlags.COMPLETED or SupervisorFlags.CANCELLED))
    }

    @Test fun `failure terminal preserves unknown child exit and actual observed count`() {
        val result = parse(magic + terminal(cause = 7, flags = SupervisorFlags.QUIESCENT, exit = -1))
        assertNull(result.problem)
        assertNull(result.terminal!!.childExit)
        val overflow = parse(wire("abc".toByteArray(), cause = 3,
            flags = SupervisorFlags.COMPLETED or SupervisorFlags.OUTPUT_LIMIT, observed = 900))
        assertNull(overflow.problem)
        assertEquals(900L, overflow.terminal!!.observedOutput)
        assertEquals(3L, overflow.captured)
    }

    @Test fun `failure causes require matching flags before accepting terminal evidence`() {
        val required = mapOf(1 to SupervisorFlags.CANCELLED, 2 to SupervisorFlags.TIMED_OUT,
            3 to SupervisorFlags.OUTPUT_LIMIT, 4 to SupervisorFlags.PARENT_LOST,
            5 to SupervisorFlags.IO_FAILED, 6 to SupervisorFlags.EXEC_FAILED,
            8 to SupervisorFlags.SHUTDOWN_FAILED, 9 to SupervisorFlags.PROTOCOL_FAILED,
            10 to (SupervisorFlags.OWNERSHIP_LOST or SupervisorFlags.SHUTDOWN_FAILED))
        for ((cause, flags) in required) {
            val base = if (cause == 6) SupervisorFlags.GROUP_READY else 0
            assertNull(parse(magic + terminal(cause = cause, flags = base or flags, exit = -1)).problem)
            for (bit in 0..13) {
                val flag = 1 shl bit
                if ((flags and flag) == 0) continue
                val result = invalid(magic + terminal(cause = cause, flags = base or (flags xor flag), exit = -1))
                assertNull(result.terminal, "cause=$cause missing=$flag")
            }
        }
    }

    @Test fun `execution confirmation requires group and observed nonreserved exit without exec failure`() {
        val confirmed = SupervisorFlags.GROUP_READY or SupervisorFlags.EXIT_OBSERVED or
            SupervisorFlags.EXEC_CONFIRMED or SupervisorFlags.CANCELLED
        assertNull(parse(magic + terminal(cause = 1, flags = confirmed, exit = 137)).problem)
        assertNull(parse(wire(exit = 137)).problem) // Numeric >=128 alone does not distinguish a signal.
        for (record in listOf(
            terminal(cause = 1, flags = confirmed xor SupervisorFlags.GROUP_READY),
            terminal(cause = 1, flags = confirmed xor SupervisorFlags.EXIT_OBSERVED, exit = -1),
            terminal(cause = 1, flags = confirmed, exit = 126),
            terminal(cause = 1, flags = confirmed or SupervisorFlags.EXEC_FAILED),
            terminal(cause = 7, flags = SupervisorFlags.COMPLETED),
            terminal(cause = 6, flags = SupervisorFlags.EXEC_FAILED, exit = -1),
            terminal(exit = 126),
        )) assertNull(invalid(magic + record).terminal)
    }

    @Test fun `quiescence and ownership flags distinguish prefork cleanup from unresolved children`() {
        val lifecycle = listOf(SupervisorFlags.GROUP_READY, SupervisorFlags.EXIT_OBSERVED,
            SupervisorFlags.LEADER_REAPED, SupervisorFlags.EXEC_CONFIRMED)
        for (flag in lifecycle) {
            val flags = SupervisorFlags.QUIESCENT or SupervisorFlags.CANCELLED or flag
            assertNull(invalid(magic + terminal(cause = 1, flags = flags,
                exit = if (flag == SupervisorFlags.EXIT_OBSERVED) 0 else -1)).terminal)
        }
        val ownership = SupervisorFlags.OWNERSHIP_LOST or SupervisorFlags.SHUTDOWN_FAILED
        assertNull(parse(magic + terminal(cause = 10, flags = ownership or SupervisorFlags.LEADER_REAPED, exit = -1)).problem)
        assertNull(invalid(magic + terminal(cause = 1, flags = SupervisorFlags.CANCELLED or SupervisorFlags.OWNERSHIP_LOST, exit = -1)).terminal)
        assertNull(invalid(magic + terminal(cause = 10, flags = ownership or SupervisorFlags.QUIESCENT, exit = -1)).terminal)
        assertNull(parse(magic + terminal(cause = 8, flags = SupervisorFlags.QUIESCENT or SupervisorFlags.SHUTDOWN_FAILED, exit = -1)).problem)
    }

    @Test fun `duplicate final or trailing bytes invalidate but preserve earlier exit and output`() {
        for (tail in listOf(terminal(), frame(1, byteArrayOf(7)), byteArrayOf(0))) {
            val result = invalid(wire("kept".toByteArray(), exit = 3) + tail)
            assertEquals(3, result.terminal!!.childExit)
            assertContentEquals("kept".toByteArray(), result.stdout)
        }
    }

    @Test fun `UTF8 is decoded only by the execution adapter and not silently repaired here`() {
        val invalidUtf8 = byteArrayOf(0xc3.toByte(), 0x28)
        val result = parse(wire(invalidUtf8))
        assertNull(result.problem)
        assertContentEquals(invalidUtf8, result.stdout)
        assertFails { strictUtf8(result.stdout) }
        val scalar = "\uD83D\uDE80".toByteArray()
        val split = magic + frame(1, scalar.copyOfRange(0, 2)) + frame(1, scalar.copyOfRange(2, 4)) + terminal(observed = 4)
        assertEquals("\uD83D\uDE80", strictUtf8(parse(split).stdout))
    }

    @Test fun `read failures and no progress cannot become empty successful output`() {
        val broken = object : InputStream() { override fun read(): Int = throw java.io.IOException("private text") }
        val result = parse(byteArrayOf(), input = broken)
        assertEquals("process_output_unreliable", result.problem)
        assertFalse(result.toString().contains("private text"))
        val stalled = object : InputStream() {
            override fun read(): Int = 0
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = 0
        }
        assertEquals("process_supervisor_protocol_error", parse(byteArrayOf(), input = stalled).problem)
    }

    private val identity = OwnedSupervisorIdentity(SupervisorBundleFormat.VERSION, "linux-x86_64", "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64))
    private val targetCommand = listOf(Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().toString(), "unused")
    private val cwd = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()

    private inner class FakeImage : SupervisorImageHandle {
        override val executable: Path = cwd.resolve("never-launched-supervisor")
        override val identity = this@OwnedProcessSupervisorTest.identity
        val verified = AtomicInteger()
        val closed = AtomicInteger()
        override fun verify() { verified.incrementAndGet() }
        override fun closeSafely(): Boolean { closed.incrementAndGet(); return true }
    }

    private class MemoryChannel(bytes: ByteArray, private val helperExit: Int = 0,
                                override val frames: InputStream = ByteArrayInputStream(bytes), diagnostics: ByteArray = byteArrayOf(),
                                val running: AtomicBoolean = AtomicBoolean(false), onCancel: (() -> Unit)? = null,
                                onControlClose: (() -> Unit)? = null) : SupervisorChannel {
        override val diagnostics = ByteArrayInputStream(diagnostics)
        val written = ByteArrayOutputStream()
        val controlClosed = AtomicBoolean()
        override val control = object : OutputStream() {
            override fun write(byte: Int) { synchronized(written) { written.write(byte) }; onCancel?.invoke() }
            override fun close() {
                if (controlClosed.compareAndSet(false, true)) onControlClose?.invoke()
            }
        }
        override fun alive() = running.get()
        override fun exitCode(): Int? = if (running.get()) null else helperExit
        override fun awaitExit(millis: Long): Boolean {
            if (running.get()) Thread.sleep(minOf(10, millis))
            return !running.get()
        }
        fun cancellations(): ByteArray = synchronized(written) { written.toByteArray() }
    }

    private class GateInput(private val closeOpens: Boolean = true) : InputStream() {
        private val gate = CountDownLatch(1)
        val readStarted = CountDownLatch(1)
        @Volatile private var selected: ByteArrayInputStream? = null
        fun open(bytes: ByteArray) { selected = ByteArrayInputStream(bytes); gate.countDown() }
        override fun read(): Int { readStarted.countDown(); gate.await(); return selected?.read() ?: -1 }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            readStarted.countDown(); gate.await(); return selected?.read(bytes, offset, length) ?: -1
        }
        override fun close() { if (closeOpens) gate.countDown() }
    }

    private fun run(channel: SupervisorChannel, image: FakeImage = FakeImage(), slots: SupervisorSlots = SupervisorSlots(4),
                    cancellation: CancellationToken = CancellationToken.NONE, timeout: Long = 1000, grace: Long = 100): SupervisedProcessResult =
        SupervisorTransport({ image }, SupervisorLauncher { _, _, _, _, _, _ -> channel }, slots, grace)
            .run(targetCommand, cwd, emptyMap(), timeout, 32768, cancellation)

    private fun eventually(test: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!test()) {
            if (System.nanoTime() >= deadline) fail("Bounded fake transport cleanup did not finish")
            Thread.sleep(5)
        }
    }

    @Test fun `only valid final EOF and helper exit zero produce executed`() {
        val image = FakeImage()
        val channel = MemoryChannel(wire("out".toByteArray(), "err".toByteArray(), exit = 9))
        val slots = SupervisorSlots(1)
        val result = run(channel, image, slots)
        assertEquals("executed", result.status, result.reasons.toString())
        assertEquals(9, result.exitCode)
        assertTrue(result.executionConfirmed)
        assertEquals(identity, result.supervisorIdentity)
        assertEquals(6L, result.outputBytes)
        assertContentEquals("out".toByteArray(), result.stdout)
        assertContentEquals("err".toByteArray(), result.stderr)
        assertTrue(result.reasons.isEmpty())
        assertTrue(channel.cancellations().isEmpty())
        eventually { slots.remaining() == 1 && image.closed.get() == 1 }
    }

    @Test fun `helper exit failure does not erase valid earlier target exit or output`() {
        val result = run(MemoryChannel(wire("kept".toByteArray(), exit = 11), helperExit = 4))
        assertEquals("failed", result.status)
        assertEquals(11, result.exitCode)
        assertTrue(result.executionConfirmed)
        assertContentEquals("kept".toByteArray(), result.stdout)
        assertContains(result.reasons, "process_supervisor_exit_failed")
        assertContains(result.reasons, "owned_process_group_shutdown_unverified")
    }

    @Test fun `post-exit image drift fails without replacing actual identity exit or captured output`() {
        val checks = AtomicInteger()
        val changedImage = object : SupervisorImageHandle {
            override val executable = cwd.resolve("never-launched-supervisor")
            override val identity = this@OwnedProcessSupervisorTest.identity
            override fun verify() {
                if (checks.incrementAndGet() > 1) throw ExecutionProblem("process_supervisor_image_changed", "changed")
            }
            override fun closeSafely() = true
        }
        val slots = SupervisorSlots(1)
        val closed = AtomicBoolean()
        val channel = MemoryChannel(wire("kept".toByteArray(), exit = 7))
        val observedImage = object : SupervisorImageHandle by changedImage {
            override fun closeSafely(): Boolean { closed.set(true); return changedImage.closeSafely() }
        }
        val runner = SupervisorTransport({ observedImage }, SupervisorLauncher { _, _, _, _, _, _ -> channel }, slots)
        val result = runner.run(targetCommand, cwd, emptyMap(), 1000, 32768, CancellationToken.NONE)
        assertEquals("failed", result.status)
        assertContains(result.reasons, "process_supervisor_image_changed")
        assertContains(result.reasons, "owned_process_group_shutdown_unverified")
        assertEquals(7, result.exitCode)
        assertEquals(identity, result.supervisorIdentity)
        assertContentEquals("kept".toByteArray(), result.stdout)
        assertEquals(2, checks.get())
        assertTrue(result.executionConfirmed) // Earlier valid telemetry stays failed evidence, not ACK.
        eventually { closed.get() }
        assertEquals(0, slots.remaining())
        assertEquals("unavailable", run(MemoryChannel(wire()), slots = slots).status)
    }

    @Test fun `missing or malformed final never gets rescued by helper exit zero`() {
        for (bytes in listOf(byteArrayOf(), magic, magic + frame(1, "observed".toByteArray()), wire() + byteArrayOf(1))) {
            val channel = MemoryChannel(bytes)
            val result = run(channel)
            assertEquals("failed", result.status)
            assertContains(result.reasons, "process_supervisor_protocol_error")
            assertContains(result.reasons, "owned_process_group_shutdown_unverified")
            assertEquals(bytes.contentEquals(wire() + byteArrayOf(1)), result.executionConfirmed)
            eventually { channel.controlClosed.get() }
            assertTrue(channel.cancellations().size <= 1)
        }
    }

    @Test fun `lack of terminal EOF is unverified even after valid zero exit final`() {
        val afterFinal = GateInput()
        val input = java.io.SequenceInputStream(ByteArrayInputStream(wire("kept".toByteArray())), afterFinal)
        val channel = MemoryChannel(byteArrayOf(), frames = input)
        val result = run(channel, timeout = 100, grace = 50)
        assertEquals("failed", result.status)
        assertEquals(0, result.exitCode)
        assertContentEquals("kept".toByteArray(), result.stdout)
        assertContains(result.reasons, "process_supervisor_transport_timeout")
        assertContains(result.reasons, "owned_process_group_shutdown_unverified")
        eventually { channel.controlClosed.get() }
        afterFinal.close()
    }

    @Test fun `native causes retain distinct cancellation timeout output and failure states`() {
        data class Case(val cause: Int, val flags: Int, val exit: Int, val reason: String)
        val completed = SupervisorFlags.COMPLETED
        val unconfirmed = completed xor SupervisorFlags.EXEC_CONFIRMED
        val cases = listOf(
            Case(1, completed or SupervisorFlags.CANCELLED, 0, "execution_cancelled"),
            Case(2, completed or SupervisorFlags.TIMED_OUT, 0, "execution_timeout"),
            Case(3, completed or SupervisorFlags.OUTPUT_LIMIT, 0, "process_output_limit"),
            Case(4, completed or SupervisorFlags.PARENT_LOST, 0, "process_supervisor_parent_disconnected"),
            Case(5, completed or SupervisorFlags.IO_FAILED, 0, "process_output_unreliable"),
            Case(6, unconfirmed or SupervisorFlags.EXEC_FAILED, 126, "process_start_failed"),
            Case(7, SupervisorFlags.QUIESCENT, -1, "process_supervisor_start_failed"),
            Case(8, completed or SupervisorFlags.SHUTDOWN_FAILED, 0, "owned_process_group_shutdown_unverified"),
            Case(9, completed or SupervisorFlags.PROTOCOL_FAILED, 0, "process_supervisor_protocol_error"),
            Case(10, SupervisorFlags.GROUP_READY or SupervisorFlags.LEADER_REAPED or
                SupervisorFlags.OWNERSHIP_LOST or SupervisorFlags.SHUTDOWN_FAILED, -1, "process_supervisor_ownership_lost"),
        )
        for ((cause, flags, exit, reason) in cases) {
            val slots = SupervisorSlots(1)
            val image = FakeImage()
            val result = run(MemoryChannel(wire(cause = cause, flags = flags, exit = exit)), image, slots)
            assertEquals(if (cause == 1) "cancelled" else "failed", result.status, result.reasons.toString())
            assertContains(result.reasons, reason)
            assertEquals((flags and SupervisorFlags.EXEC_CONFIRMED) != 0, result.executionConfirmed)
            assertEquals(cause == 1, result.cancelled)
            assertEquals(cause == 2, result.timedOut)
            assertEquals(cause == 3, result.truncated)
            eventually { image.closed.get() == 1 }
            if (cause == 8 || cause == 10) {
                assertEquals(0, slots.remaining())
                assertContains(result.reasons, "owned_process_group_shutdown_unverified")
            } else eventually { slots.remaining() == 1 }
        }
    }

    @Test fun `cancellation without quiescence is failed rather than an acknowledged cancellation`() {
        val flags = (SupervisorFlags.COMPLETED xor SupervisorFlags.QUIESCENT) or SupervisorFlags.CANCELLED
        val result = run(MemoryChannel(wire(cause = 1, flags = flags)))
        assertTrue(result.cancelled)
        assertEquals("failed", result.status)
        assertContains(result.reasons, "owned_process_group_shutdown_unverified")
    }

    @Test fun `signaled or reserved-exit child without exec proof remains start unconfirmed`() {
        val unconfirmed = SupervisorFlags.COMPLETED xor SupervisorFlags.EXEC_CONFIRMED
        for (exit in listOf(126, 137, 143)) {
            val result = run(MemoryChannel(wire("observed".toByteArray(), cause = 7, flags = unconfirmed, exit = exit)))
            assertEquals("failed", result.status)
            assertEquals(listOf("process_start_unconfirmed"), result.reasons)
            assertEquals(exit, result.exitCode)
            assertFalse(result.executionConfirmed)
            assertContentEquals("observed".toByteArray(), result.stdout)
            assertEquals(identity, result.supervisorIdentity)
        }
        val cancelled = run(MemoryChannel(wire(cause = 1, flags = unconfirmed or SupervisorFlags.CANCELLED, exit = 143)))
        assertEquals("cancelled", cancelled.status)
        assertTrue(cancelled.cancelled)
        assertFalse(cancelled.executionConfirmed)
        val timeout = run(MemoryChannel(wire(cause = 2, flags = unconfirmed or SupervisorFlags.TIMED_OUT, exit = 137)))
        assertEquals("failed", timeout.status)
        assertTrue(timeout.timedOut)
        assertFalse(timeout.executionConfirmed)
        val knownExecFailure = run(MemoryChannel(wire(cause = 6, flags = unconfirmed or SupervisorFlags.EXEC_FAILED, exit = 126)))
        assertEquals(listOf("process_start_failed"), knownExecFailure.reasons)
        assertFalse(knownExecFailure.executionConfirmed)
    }

    @Test fun `output limit reports native observed count rather than captured length`() {
        val result = run(MemoryChannel(wire("prefix".toByteArray(), cause = 3,
            flags = SupervisorFlags.COMPLETED or SupervisorFlags.OUTPUT_LIMIT, observed = 100000)))
        assertEquals("failed", result.status)
        assertTrue(result.truncated)
        assertEquals(100000L, result.outputBytes)
        assertEquals(6, result.stdout.size)
    }

    @Test fun `cancellation writes one byte plus EOF and awaits native cleanup`() {
        val input = GateInput()
        val running = AtomicBoolean(true)
        val channel = MemoryChannel(byteArrayOf(), frames = input, running = running, onCancel = {
            input.open(wire(cause = 1, flags = SupervisorFlags.COMPLETED or SupervisorFlags.CANCELLED))
            running.set(false)
        })
        val calls = AtomicInteger()
        val result = run(channel, cancellation = CancellationToken { calls.incrementAndGet() >= 3 })
        assertEquals("cancelled", result.status, result.reasons.toString())
        assertTrue(result.cancelled)
        assertEquals(listOf("execution_cancelled"), result.reasons)
        eventually { channel.controlClosed.get() }
        assertContentEquals(byteArrayOf('C'.code.toByte()), channel.cancellations())
    }

    @Test fun `late control write failure preserves timeout FINAL and acknowledged capacity`() {
        assertLateControlWriteFailure(quiescent = true)
    }

    @Test fun `late control write failure without quiescence keeps capacity poisoned`() {
        assertLateControlWriteFailure(quiescent = false)
    }

    private fun assertLateControlWriteFailure(quiescent: Boolean) {
        val afterFinal = GateInput(closeOpens = false)
        val running = AtomicBoolean(true)
        val stdout = "synthetic-out".toByteArray()
        val stderr = "synthetic-err".toByteArray()
        val privateFailure = "private synthetic control write failure"
        val timeoutFlags = (SupervisorFlags.COMPLETED xor SupervisorFlags.EXEC_CONFIRMED) or SupervisorFlags.TIMED_OUT
        val flags = if (quiescent) timeoutFlags else timeoutFlags xor SupervisorFlags.QUIESCENT
        val frames = java.io.SequenceInputStream(ByteArrayInputStream(wire(stdout, stderr,
            cause = 2, flags = flags, exit = 137)), afterFinal)
        val channel = MemoryChannel(byteArrayOf(), frames = frames, running = running,
            onCancel = { throw java.io.IOException(privateFailure) },
            onControlClose = {
                // cancelControl records the write exception before entering its finally/close.
                afterFinal.open(byteArrayOf())
                running.set(false)
            })
        val image = FakeImage()
        val slots = SupervisorSlots(1)
        try {
            // SequenceInputStream reaches this gate only after the reader validated and stored
            // FINAL. Local cancellation is the synthetic cleanup trigger, not an observed B cause.
            val result = run(channel, image, slots,
                cancellation = CancellationToken { afterFinal.readStarted.count == 0L }, timeout = 5000, grace = 4000)
            assertEquals(0L, afterFinal.readStarted.count)
            assertTrue(channel.controlClosed.get())
            assertContentEquals(byteArrayOf('C'.code.toByte()), channel.cancellations())
            assertFalse(running.get())
            assertEquals(0, channel.exitCode())
            assertEquals("failed", result.status)
            val expectedReasons = listOf("execution_timeout", "process_supervisor_control_failed", "execution_cancelled") +
                (if (quiescent) emptyList() else listOf("owned_process_group_shutdown_unverified"))
            assertEquals(expectedReasons, result.reasons)
            assertTrue(result.cancelled)
            assertTrue(result.timedOut)
            assertFalse(result.truncated)
            assertFalse(result.executionConfirmed)
            assertEquals(137, result.exitCode)
            assertContentEquals(stdout, result.stdout)
            assertContentEquals(stderr, result.stderr)
            assertEquals(stdout.size.toLong() + stderr.size, result.outputBytes)
            assertEquals(identity, result.supervisorIdentity)
            assertFalse(result.toString().contains(privateFailure))
            assertEquals(2, image.verified.get())
            eventually { image.closed.get() == 1 && slots.remaining() == (if (quiescent) 1 else 0) }

            val nextImage = FakeImage()
            val nextChannel = MemoryChannel(wire("separate".toByteArray()))
            val next = run(nextChannel, nextImage, slots)
            if (quiescent) {
                assertEquals("executed", next.status, next.reasons.toString())
                assertTrue(next.reasons.isEmpty())
                assertTrue(next.executionConfirmed)
                assertContentEquals("separate".toByteArray(), next.stdout)
                eventually { nextImage.closed.get() == 1 && slots.remaining() == 1 }
            } else {
                assertEquals("unavailable", next.status)
                assertEquals(listOf("process_supervisor_capacity_unavailable"), next.reasons)
                assertFalse(next.executionConfirmed)
                assertNull(next.supervisorIdentity)
                assertEquals(0, nextImage.verified.get())
                assertEquals(0, nextImage.closed.get())
                assertEquals(0, slots.remaining())
            }
            assertTrue(nextChannel.cancellations().isEmpty())
        } finally {
            running.set(false)
            afterFinal.open(byteArrayOf())
        }
    }

    @Test fun `prefork cancelled and setup failure cleanup acknowledgments release capacity`() {
        val slots = SupervisorSlots(1)
        for (cause in listOf(1, 7)) {
            val flags = SupervisorFlags.QUIESCENT or if (cause == 1) SupervisorFlags.CANCELLED else 0
            val image = FakeImage()
            val result = run(MemoryChannel(wire(cause = cause, flags = flags, exit = -1)), image, slots)
            assertEquals(if (cause == 1) "cancelled" else "failed", result.status)
            assertEquals(listOf(if (cause == 1) "execution_cancelled" else "process_supervisor_start_failed"), result.reasons)
            assertNull(result.exitCode)
            assertFalse(result.executionConfirmed)
            eventually { image.closed.get() == 1 && slots.remaining() == 1 }
        }
    }

    @Test fun `delayed cleanup acknowledgment within cancellation grace releases capacity`() {
        val executor = Executors.newSingleThreadExecutor()
        val input = GateInput()
        val running = AtomicBoolean(true)
        val cancellationSeen = CountDownLatch(1)
        val channel = MemoryChannel(byteArrayOf(), frames = input, running = running,
            onCancel = { cancellationSeen.countDown() })
        val slots = SupervisorSlots(1)
        val image = FakeImage()
        val calls = AtomicInteger()
        try {
            val pending = executor.submit<SupervisedProcessResult> {
                run(channel, image, slots, CancellationToken { calls.incrementAndGet() >= 3 }, timeout = 2000, grace = 1000)
            }
            assertTrue(cancellationSeen.await(1, TimeUnit.SECONDS))
            assertFalse(pending.isDone)
            assertEquals(0, slots.remaining())
            input.open(wire(cause = 1, flags = SupervisorFlags.COMPLETED or SupervisorFlags.CANCELLED))
            running.set(false)
            val result = pending.get(2, TimeUnit.SECONDS)
            assertEquals("cancelled", result.status, result.reasons.toString())
            assertEquals(listOf("execution_cancelled"), result.reasons)
            eventually { image.closed.get() == 1 && slots.remaining() == 1 }
            assertContentEquals(byteArrayOf('C'.code.toByte()), channel.cancellations())
        } finally {
            running.set(false); input.close(); executor.shutdownNow()
        }
    }

    @Test fun `pre-cancellation never loads image or launches a helper`() {
        val calls = AtomicInteger()
        val runner = SupervisorTransport({ calls.incrementAndGet(); FakeImage() },
            SupervisorLauncher { _, _, _, _, _, _ -> calls.incrementAndGet(); MemoryChannel(wire()) })
        val result = runner.run(targetCommand, cwd, emptyMap(), 1000, 32768, CancellationToken { true })
        assertEquals("cancelled", result.status)
        assertEquals(0, calls.get())
        assertNull(result.supervisorIdentity)
        assertNull(result.exitCode)
        assertFalse(result.executionConfirmed)
    }

    @Test fun `missing image remains unavailable with no PATH launch fallback`() {
        val launches = AtomicInteger()
        val slots = SupervisorSlots(1)
        val runner = SupervisorTransport({ throw ExecutionProblem("process_group_containment_unavailable", "missing") },
            SupervisorLauncher { _, _, _, _, _, _ -> launches.incrementAndGet(); MemoryChannel(wire()) }, slots)
        val result = runner.run(targetCommand, cwd, emptyMap(), 1000, 32768, CancellationToken.NONE)
        assertEquals("unavailable", result.status)
        assertEquals(listOf("process_group_containment_unavailable"), result.reasons)
        assertEquals(0, launches.get())
        assertFalse(result.executionConfirmed)
        assertEquals(1, slots.remaining())
    }

    @Test fun `failed helper start releases only its own prepared image and slot`() {
        val image = FakeImage()
        val slots = SupervisorSlots(1)
        val runner = SupervisorTransport({ image }, SupervisorLauncher { _, _, _, _, _, _ -> throw java.io.IOException("private executable path") }, slots)
        val result = runner.run(targetCommand, cwd, emptyMap(), 1000, 32768, CancellationToken.NONE)
        assertEquals("failed", result.status)
        assertEquals(listOf("process_supervisor_start_failed"), result.reasons)
        assertFalse(result.toString().contains("private executable"))
        assertEquals(1, image.closed.get())
        assertEquals(1, slots.remaining())
        assertNull(result.supervisorIdentity)
    }

    @Test fun `request arguments are bounded scalar UTF8 and absolute executable without shells`() {
        val preparations = AtomicInteger()
        val runner = SupervisorTransport({ preparations.incrementAndGet(); FakeImage() })
        val cases = listOf(emptyList(), listOf("relative"), targetCommand + "bad\u0000arg", targetCommand + "\uD800",
            targetCommand + "x".repeat(65537), targetCommand + List(8192) { "a" }, targetCommand + List(32) { "z".repeat(65536) })
        for (command in cases) {
            assertEquals("unavailable", runner.run(command, cwd, emptyMap(), 1000, 32768, CancellationToken.NONE).status)
        }
        for (env in listOf(mapOf("" to "x"), mapOf("x=y" to "z"), mapOf("x" to "\u0000"), mapOf("x" to "\uD800"))) {
            assertEquals("unavailable", runner.run(targetCommand, cwd, env, 1000, 32768, CancellationToken.NONE).status)
        }
        assertEquals("unavailable", runner.run(targetCommand, Path.of("relative"), emptyMap(), 1000, 32768, CancellationToken.NONE).status)
        assertEquals("unavailable", runner.run(targetCommand, cwd, emptyMap(), 0, 32768, CancellationToken.NONE).status)
        assertEquals("unavailable", runner.run(targetCommand, cwd, emptyMap(), 1000, 0, CancellationToken.NONE).status)
        assertEquals(0, preparations.get())
    }

    @Test fun `launcher gets detached exact argv environment and authorized scalars`() {
        val originalCommand = targetCommand.toMutableList()
        val environment = linkedMapOf("EXACT" to "first")
        val image = FakeImage()
        val runner = SupervisorTransport({ image }, SupervisorLauncher { observedImage, observedCommand, directory, observedEnvironment, timeout, output ->
            assertSame(image, observedImage)
            originalCommand.add("mutated")
            environment["EXACT"] = "changed"
            assertEquals(targetCommand, observedCommand)
            assertEquals(mapOf("EXACT" to "first"), observedEnvironment)
            assertEquals(cwd, directory)
            assertEquals(1000L, timeout)
            assertEquals(32768, output)
            MemoryChannel(wire())
        })
        assertEquals("executed", runner.run(originalCommand, cwd, environment, 1000, 32768, CancellationToken.NONE).status)
    }

    @Test fun `loader and libc interposition environment is unavailable before load without mutation`() {
        val loads = AtomicInteger()
        val launches = AtomicInteger()
        val runner = SupervisorTransport({ loads.incrementAndGet(); FakeImage() }, SupervisorLauncher { _, _, _, _, _, _ ->
            launches.incrementAndGet(); MemoryChannel(wire())
        })
        val forbidden = listOf("LD_PRELOAD", "LD_AUDIT", "LD_LIBRARY_PATH", "LD_DEBUG", "LD_", "DYLD_INSERT_LIBRARIES",
            "DYLD_LIBRARY_PATH", "DYLD_", "GLIBC_TUNABLES", "GCONV_PATH", "LOCPATH")
        for (name in forbidden) {
            val environment = linkedMapOf("PATH" to "exact", name to "owner-selected-value")
            val original = environment.toMap()
            val result = runner.run(targetCommand, cwd, environment, 1000, 32768, CancellationToken.NONE)
            assertEquals("unavailable", result.status)
            assertEquals(listOf("process_supervisor_environment_unavailable"), result.reasons)
            assertFalse(result.executionConfirmed)
            assertEquals(original, environment)
        }
        assertEquals(0, loads.get())
        assertEquals(0, launches.get())
        val ordinary = linkedMapOf("PATH" to "exact", "ld_preload" to "not-a-loader-key", "XLD_PRELOAD" to "not-a-prefix",
            "DYLD" to "not-a-prefix", "GCONV_PATH_EXTRA" to "not-the-key", "JAVA_TOOL_OPTIONS" to "-Xmx64m")
        val unchanged = ordinary.toMap()
        assertEquals("executed", runner.run(targetCommand, cwd, ordinary, 1000, 32768, CancellationToken.NONE).status)
        assertEquals(unchanged, ordinary)
        assertEquals(1, launches.get())
    }

    @Test fun `unexpected supervisor diagnostics are bounded private and never provider stderr`() {
        for (diagnostic in listOf("do not expose this helper text".toByteArray(), ByteArray(70_000) { 120 })) {
            val result = run(MemoryChannel(wire("provider".toByteArray()), diagnostics = diagnostic))
            assertEquals("failed", result.status)
            assertTrue(result.reasons.any { it.startsWith("process_supervisor_diagnostics_") })
            assertFalse(result.toString().contains("do not expose"))
            assertTrue(result.stderr.isEmpty())
            assertEquals(8L, result.outputBytes)
        }
    }

    @Test fun `lost cleanup acknowledgment poisons capacity even after helper exit and never reconnects`() {
        val slots = SupervisorSlots(1)
        val running = AtomicBoolean(true)
        val channel = MemoryChannel(wire("received".toByteArray()), running = running)
        val image = FakeImage()
        val result = run(channel, image, slots, timeout = 100, grace = 50)
        try {
            assertEquals("failed", result.status)
            assertContains(result.reasons, "owned_process_group_shutdown_unverified")
            assertContains(result.reasons, "process_supervisor_transport_timeout")
            assertTrue(result.timedOut)
            assertEquals(0, result.exitCode)
            assertEquals(8L, result.outputBytes)
            assertEquals(0, image.closed.get())
            assertEquals(0, slots.remaining())
            val next = run(MemoryChannel(wire()), slots = slots)
            assertEquals("unavailable", next.status)
            assertEquals(listOf("process_supervisor_capacity_unavailable"), next.reasons)
            eventually { channel.controlClosed.get() }
            assertContentEquals(byteArrayOf('C'.code.toByte()), channel.cancellations())
        } finally { running.set(false) }
        eventually { image.closed.get() == 1 }
        assertEquals(0, slots.remaining())
        val independent = MemoryChannel(wire("new".toByteArray()))
        assertEquals("unavailable", run(independent, slots = slots).status)
        assertTrue(independent.cancellations().isEmpty())
    }

    @Test fun `already ended helpers with missing or invalid ACK exhaust capacity without reacquisition`() {
        val slots = SupervisorSlots(2)
        for ((index, bytes) in listOf(magic, wire(cause = 7)).withIndex()) {
            val image = FakeImage()
            val result = run(MemoryChannel(bytes), image, slots)
            assertEquals("failed", result.status)
            assertContains(result.reasons, "process_supervisor_protocol_error")
            assertContains(result.reasons, "owned_process_group_shutdown_unverified")
            assertFalse(result.executionConfirmed)
            assertNull(result.exitCode)
            eventually { image.closed.get() == 1 }
            assertEquals(1 - index, slots.remaining())
        }
        repeat(5) {
            val image = FakeImage()
            val result = run(MemoryChannel(wire()), image, slots)
            assertEquals("unavailable", result.status)
            assertEquals(listOf("process_supervisor_capacity_unavailable"), result.reasons)
            assertEquals(0, image.verified.get())
            assertEquals(0, image.closed.get())
            assertEquals(0, slots.remaining())
        }
    }

    @Test fun `a valid terminal received after returning without ACK cannot resurrect its slot`() {
        val slots = SupervisorSlots(1)
        val image = FakeImage()
        val input = GateInput(closeOpens = false)
        val running = AtomicBoolean(true)
        val channel = MemoryChannel(byteArrayOf(), frames = input, running = running)
        try {
            val result = run(channel, image, slots, timeout = 100, grace = 50)
            assertEquals("failed", result.status)
            assertContains(result.reasons, "owned_process_group_shutdown_unverified")
            assertFalse(result.executionConfirmed)
            assertNull(result.exitCode)
            assertEquals(0, slots.remaining())
            assertEquals(0, image.closed.get())
            input.open(wire(cause = 1, flags = SupervisorFlags.COMPLETED or SupervisorFlags.CANCELLED))
            running.set(false)
            eventually { image.closed.get() == 1 }
            assertEquals(0, slots.remaining())
            assertFalse(result.executionConfirmed)
            val next = run(MemoryChannel(wire()), slots = slots)
            assertEquals(listOf("process_supervisor_capacity_unavailable"), next.reasons)
        } finally {
            running.set(false); input.open(byteArrayOf())
        }
    }

    @Test fun `simultaneous reviews have independent delivery and cancellation channels`() {
        val executor = Executors.newFixedThreadPool(2)
        val firstInput = GateInput()
        val secondInput = GateInput()
        val firstRunning = AtomicBoolean(true)
        val secondRunning = AtomicBoolean(true)
        val firstCancel = AtomicBoolean()
        val launched = CountDownLatch(2)
        val first = MemoryChannel(byteArrayOf(), frames = firstInput, running = firstRunning, onCancel = {
            firstInput.open(wire(cause = 1, flags = SupervisorFlags.COMPLETED or SupervisorFlags.CANCELLED))
            firstRunning.set(false)
        })
        val second = MemoryChannel(byteArrayOf(), frames = secondInput, running = secondRunning)
        val slots = SupervisorSlots(2)
        fun submit(channel: MemoryChannel, token: CancellationToken) = executor.submit<SupervisedProcessResult> {
            val transport = SupervisorTransport({ FakeImage() }, SupervisorLauncher { _, _, _, _, _, _ -> launched.countDown(); channel }, slots, 100)
            transport.run(targetCommand, cwd, emptyMap(), 2000, 32768, token)
        }
        try {
            val a = submit(first, CancellationToken { firstCancel.get() })
            val b = submit(second, CancellationToken.NONE)
            assertTrue(launched.await(1, TimeUnit.SECONDS))
            firstCancel.set(true)
            assertEquals("cancelled", a.get(2, TimeUnit.SECONDS).status)
            assertTrue(second.cancellations().isEmpty())
            assertFalse(b.isDone)
            secondInput.open(wire("only-second".toByteArray()))
            secondRunning.set(false)
            val observed = b.get(2, TimeUnit.SECONDS)
            assertEquals("executed", observed.status, observed.reasons.toString())
            assertContentEquals("only-second".toByteArray(), observed.stdout)
            assertTrue(second.cancellations().isEmpty())
        } finally {
            firstRunning.set(false); secondRunning.set(false)
            firstInput.close(); secondInput.close()
            executor.shutdownNow()
        }
    }

    @Test fun `interruption requests IPC cleanup and restores interrupt status only afterward`() {
        val input = GateInput()
        val running = AtomicBoolean(true)
        val ready = CountDownLatch(1)
        val channel = MemoryChannel(byteArrayOf(), frames = input, running = running, onCancel = {
            input.open(wire(cause = 1, flags = SupervisorFlags.COMPLETED or SupervisorFlags.CANCELLED))
            running.set(false)
        })
        val result = java.util.concurrent.atomic.AtomicReference<SupervisedProcessResult>()
        val interrupted = AtomicBoolean()
        val worker = Thread {
            val transport = SupervisorTransport({ FakeImage() }, SupervisorLauncher { _, _, _, _, _, _ -> ready.countDown(); channel }, cleanupGraceMillis = 100)
            result.set(transport.run(targetCommand, cwd, emptyMap(), 1000, 32768, CancellationToken.NONE))
            interrupted.set(Thread.currentThread().isInterrupted)
        }
        worker.start()
        try {
            assertTrue(ready.await(1, TimeUnit.SECONDS))
            worker.interrupt()
            worker.join(2000)
            assertFalse(worker.isAlive)
            assertEquals("cancelled", result.get()?.status)
            assertTrue(interrupted.get())
        } finally { running.set(false); input.close(); worker.interrupt(); worker.join(2000) }
    }
}

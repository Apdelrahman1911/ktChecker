package dev.ktchecker.cli

import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*
import kotlin.test.*

/** Actual transport on INERT memory pipes, never Main/Java/Workbench/owner/model execution.
 * No export1 reply is ever supplied. The selected path is asserted absent before a worker
 * starts, so even a regression crossing the boundary cannot open an owner from this fixture.
 * Only NEW local inert pipes are closed in test finalizers; production never closes them.
 */
class OwnerModelTransportPreownerLifecycleTest {
    // NEW inert parent, retained rather than cleaned up; its selection leaf is NEVER created.
    private val absentSelection = Files.createTempDirectory("ktc-preowner-inert-control-").resolve("never-create-owner-selection.json")

    private fun runInert(input: InputStream, output: OutputStream): Int {
        assertFalse(Files.exists(absentSelection))
        val transport = OwnerModelSessionTransport(OwnerModelTransportCliSelection(absentSelection, "sha256:" + "0".repeat(64)),
            OwnedApplicationDeadline.afterMillis(5_000), input, output)
        val result = FutureTask { transport.serve() }
        val worker = Thread(result, "inert-preowner-only-lifetime-worker").apply { isDaemon = true }
        worker.start()
        val returned = result.get(6, TimeUnit.SECONDS) // Does NOT interrupt/cancel an uncertain worker.
        worker.join(1_000); assertFalse(worker.isAlive)
        return returned
    }

    @Test fun eofBeforeAnyRequestReturns78WithoutClosingEitherStream() {
        var inputClosed = false; var outputClosed = false
        val input = object : ByteArrayInputStream(byteArrayOf()) { override fun close() { inputClosed = true } }
        val output = object : ByteArrayOutputStream() { override fun close() { outputClosed = true } }
        assertEquals(78, runInert(input, output))
        assertFalse(inputClosed); assertFalse(outputClosed)
    }

    @Test fun helloWriteFailureReturns78WithoutOutputRetryOrInputClose() {
        val input = PipedInputStream(4096); val peer = PipedOutputStream(input)
        var writes = 0; var closes = 0
        val output = object : OutputStream() {
            override fun write(value: Int) = error("bulk writes required")
            override fun write(bytes: ByteArray, offset: Int, length: Int) { writes++; throw IOException("SYNTHETIC hello write denial") }
            override fun close() { closes++ }
        }
        try { assertEquals(78, runInert(input, output)); assertEquals(1, writes); assertEquals(0, closes) }
        finally { peer.close(); input.close() } // Inert DATA pipe only; no child, shutdown or cleanup authority.
    }

    @Test fun actualHelloOverallAndExportChallengeThenDenyReturns78WithoutAResponse() {
        val reader = AtomicReference<Thread?>()
        val dialogueFailure = AtomicReference<Throwable?>()
        var inputClosed = false; var outputClosed = false
        val input = object : PipedInputStream(4096) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                reader.compareAndSet(null, Thread.currentThread()); return super.read(bytes, offset, length)
            }
            override fun close() { inputClosed = true; super.close() }
        }
        val peer = PipedOutputStream(input)
        val bytes = ByteArrayOutputStream(); val frames = mutableListOf<JsonObject>()
        var session: String? = null
        val output = object : OutputStream() {
            override fun write(value: Int) = error("bulk writes required")
            override fun write(value: ByteArray, offset: Int, length: Int) { bytes.write(value, offset, length) }
            override fun close() { outputClosed = true }
            override fun flush() {
                try {
                    val frameInput = ByteArrayInputStream(bytes.toByteArray())
                    val frame = OwnerModelTransportFrames.read(frameInput)
                    assertEquals(0, frameInput.available()); bytes.reset(); frames.add(frame)
                    val reply = when (frames.size) {
                        1 -> {
                            assertEquals("hello", frame["kind"]?.jsonPrimitive?.content)
                            assertEquals(OWNER_MODEL_TRANSPORT_PROTOCOL, frame["protocol"]?.jsonPrimitive?.content)
                            session = ownerModelTransportString(frame, "session")
                            buildJsonObject { put("kind", "request"); put("session", session); put("id", 1)
                                put("method", "owner.open"); put("params", JsonObject(emptyMap())) }
                        }
                        2 -> {
                            assertEquals("clock.challenge", frame["kind"]?.jsonPrimitive?.content)
                            assertEquals("overall", frame["phase"]?.jsonPrimitive?.content)
                            assertEquals(0L, ownerModelTransportInteger(frame, "ordinal", 0, 0))
                            buildJsonObject { put("kind", "clock.reply"); put("session", session); put("requestId", 1)
                                put("challenge", frame.getValue("challenge")); put("phase", "overall"); put("ordinal", 0)
                                put("remainingMillis", 5_000) } // INERT narrowing scalar, not an original TS authority fixture.
                        }
                        3 -> {
                            assertEquals("clock.challenge", frame["kind"]?.jsonPrimitive?.content)
                            assertEquals("export", frame["phase"]?.jsonPrimitive?.content)
                            assertEquals(1L, ownerModelTransportInteger(frame, "ordinal", 1, 1))
                            assertNotEquals(frames[1]["challenge"], frame["challenge"])
                            buildJsonObject { put("kind", "deny"); put("session", session) }
                        }
                        else -> error("No owner response or further frame is permitted in preowner control")
                    }
                    peer.write(transportTestFrame(reply)); peer.flush()
                } catch (failure: Throwable) {
                    dialogueFailure.compareAndSet(null, failure) // serve catches all Throwable; test must retain its own assertion.
                    throw failure
                }
            }
        }
        try {
            assertEquals(78, runInert(input, output))
            assertNull(dialogueFailure.get())
            assertEquals(3, frames.size); assertEquals(0, bytes.size())
            assertTrue(assertNotNull(reader.get()).isDaemon) // Actual reader reference, not process/thread discovery.
            assertFalse(inputClosed); assertFalse(outputClosed)
            assertFalse(Files.exists(absentSelection))
        } finally { peer.close(); input.close() }
    }

    @Test fun queuedMalformedPreownerRequestCannotReachSetupOrEmitFailureFrame() {
        val input = PipedInputStream(4096); val peer = PipedOutputStream(input)
        val bytes = ByteArrayOutputStream(); var frames = 0
        val dialogueFailure = AtomicReference<Throwable?>()
        val output = object : OutputStream() {
            override fun write(value: Int) = error("bulk writes required")
            override fun write(value: ByteArray, offset: Int, length: Int) { bytes.write(value, offset, length) }
            override fun flush() {
                try {
                    assertEquals(0, frames++)
                    val hello = OwnerModelTransportFrames.read(ByteArrayInputStream(bytes.toByteArray())); bytes.reset()
                    assertEquals("hello", hello["kind"]?.jsonPrimitive?.content)
                    peer.write(transportTestFrame(buildJsonObject {
                        put("kind", "request"); put("session", hello.getValue("session")); put("id", 1)
                        put("method", "owner.open"); putJsonObject("params") { put("unexpected", true) }
                    })); peer.flush()
                } catch (failure: Throwable) { dialogueFailure.compareAndSet(null, failure); throw failure }
            }
        }
        try {
            assertEquals(78, runInert(input, output)); assertNull(dialogueFailure.get())
            assertEquals(1, frames); assertEquals(0, bytes.size())
        }
        finally { peer.close(); input.close() }
    }
}

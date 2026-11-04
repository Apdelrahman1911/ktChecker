package dev.ktchecker.providers.runtime

import java.nio.file.Path
import kotlin.test.*

class OwnedProcessSessionTypesTest {
    @Test fun `deadlines retain original monotonic instant and never round up remaining budget`() {
        val deadline = OwnedSessionDeadline.afterMillis(100)
        assertEquals(100L, deadline.remainingMillis(deadline.startedNanos))
        assertEquals(99L, deadline.remainingMillis(deadline.startedNanos + 1))
        assertEquals(0L, deadline.remainingMillis(deadline.startedNanos + 99_000_001))
        assertEquals(0L, deadline.remainingMillis(deadline.startedNanos + 100_000_000))
        assertEquals(0L, deadline.remainingMillis(deadline.startedNanos - 1))
        assertFailsWith<IllegalArgumentException> { OwnedSessionDeadline.afterMillis(0) }
        assertFailsWith<IllegalArgumentException> { OwnedSessionDeadline.afterMillis(3_600_001) }
    }

    @Test fun `configuration collections and returned observations are detached immutable copies`() {
        val path = Path.of("worker").toAbsolutePath().toString()
        val command = mutableListOf(path, "original")
        val environment = linkedMapOf("APPROVED" to "original")
        val configuration = OwnedSessionConfiguration(command, Path.of(".").toAbsolutePath(), environment, OwnedSessionDeadline.afterMillis(1000))
        command[1] = "changed"
        environment["APPROVED"] = "changed"
        assertEquals("original", configuration.command[1])
        assertEquals("original", configuration.environment["APPROVED"])
        assertFailsWith<UnsupportedOperationException> { (configuration.command as MutableList<String>)[1] = "change" }
        assertFailsWith<UnsupportedOperationException> { (configuration.environment as MutableMap<String, String>)["APPROVED"] = "change" }
        val original = byteArrayOf(1, 2, 3)
        val observation = OwnedSessionOutput(OwnedSessionStream.STDOUT, original)
        original[0] = 9
        observation.bytes[1] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), observation.bytes)
    }

    @Test fun `owner prelaunch validation rejects loader overrides malformed UTF8 and size bounds`() {
        val worker = Path.of("worker").toAbsolutePath().toString()
        fun config(argv: List<String> = listOf(worker), env: Map<String, String> = emptyMap()) =
            OwnedSessionConfiguration(argv, Path.of(".").toAbsolutePath(), env, OwnedSessionDeadline.afterMillis(1000))
        for (key in listOf("LD_PRELOAD", "DYLD_INSERT_LIBRARIES", "GLIBC_TUNABLES", "GCONV_PATH", "LOCPATH")) {
            assertFailsWith<IllegalArgumentException> { config(env = mapOf(key to "not permitted")) }
        }
        assertEquals("permitted", config(env = mapOf("APP_LD_PRELOAD" to "permitted")).environment["APP_LD_PRELOAD"])
        assertFailsWith<IllegalArgumentException> { config(listOf("relative-worker")) }
        assertFailsWith<IllegalArgumentException> { config(listOf(worker, "a\u0000b")) }
        assertFails { config(listOf(worker, "\uD800")) }
        assertFailsWith<IllegalArgumentException> { config(listOf(worker, "a".repeat(65_537))) }
        assertFailsWith<IllegalArgumentException> { config(listOf(worker, "é".repeat(32_769))) }
        assertFailsWith<IllegalArgumentException> { config(listOf(worker) + List(4096) { "x" }) }
        assertFailsWith<IllegalArgumentException> { config(listOf(worker) + List(17) { "x".repeat(65_536) }) }
        assertFailsWith<IllegalArgumentException> { config(env = mapOf("BAD=NAME" to "x")) }
        assertFailsWith<IllegalArgumentException> { config(env = mapOf("" to "x")) }
    }

    @Test fun `fixed reason codes are bounded deduplicated detached and never free form histories`() {
        assertTrue(OwnedSessionReason.entries.size <= OwnedSessionLimits.REASONS)
        val source = mutableListOf(OwnedSessionReason.CANCELLED)
        val failed = OwnedSessionOperation.Failed(source + List(10_000) { OwnedSessionReason.CANCELLED },
            1, OwnedSessionDelivery.MAY_HAVE_BEEN_WRITTEN)
        source.add(OwnedSessionReason.FRAME_IO)
        assertEquals(listOf(OwnedSessionReason.CANCELLED), failed.reasons)
        assertFailsWith<UnsupportedOperationException> { (failed.reasons as MutableList<OwnedSessionReason>).clear() }
    }

    @Test fun `provisional native exec bit is not execution evidence without validated generation closure`() {
        val native = OwnedSessionNativeFinal(0, OwnedSessionFlags.COMPLETED, 23, 0, 0, 0, 0, 0, 0, 0, 0)
        val failed = OwnedSessionTermination(OwnedSessionState.QUARANTINED, listOf(OwnedSessionReason.CLEANUP_UNVERIFIED),
            native, liveTestIdentity, cleanupAcknowledged = false, captureComplete = false)
        assertFalse(failed.terminalExecutionConfirmed)
        assertFalse(failed.cleanupAcknowledged)
    }
}

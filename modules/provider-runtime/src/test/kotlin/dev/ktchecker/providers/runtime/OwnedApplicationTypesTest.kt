package dev.ktchecker.providers.runtime

import dev.ktchecker.contracts.CancellationToken
import dev.ktchecker.workerprotocol.ApplicationWire
import kotlin.test.*

class OwnedApplicationTypesTest {
    @Test fun `opaque deadline retains the original clock and has a detached non-authorizing identity`() {
        val deadline = OwnedApplicationDeadline.afterMillis(1000)
        val start = deadline.original.startedNanos
        assertEquals(1000L, deadline.durationMillis)
        assertEquals(1000L, deadline.original.remainingMillis(start))
        assertEquals(999L, deadline.original.remainingMillis(start + 1))
        assertEquals(0L, deadline.original.remainingMillis(start + 1_000_000_000))
        assertEquals(0L, deadline.original.remainingMillis(start - 1))
        assertTrue(Regex("[0-9a-f]{32}").matches(deadline.identity))
        assertNotEquals(deadline.identity, OwnedApplicationDeadline.afterMillis(1000).identity)
        assertFailsWith<IllegalArgumentException> { OwnedApplicationDeadline.afterMillis(0) }
        assertFailsWith<IllegalArgumentException> { OwnedApplicationDeadline.afterMillis(3_600_001) }
    }

    @Test fun `owner tokens use actual object identity and cannot adopt a serialized label`() {
        val first = OwnedApplicationOwner()
        val second = OwnedApplicationOwner()
        assertNotSame(first, second)
        assertNotEquals(first.identity, second.identity)
        assertTrue(Regex("[0-9a-f]{32}").matches(first.identity))
        assertEquals(0, OwnedApplicationOwner::class.java.constructors.single().parameterCount)
    }

    @Test fun `generation and job grants freeze canonical descriptors and retain exact deadline objects`() {
        val rig = ApplicationTestRig()
        val grant = rig.generationPermit()
        assertNotSame(rig.launch, grant.launch)
        assertEquals(rig.launch, grant.launch)
        assertSame(rig.deadline, grant.originalDeadline)
        val session = rig.open(grant)
        try {
            val deadline = OwnedApplicationDeadline.afterMillis(5000)
            val bytes = byteArrayOf(7, 8)
            val job = rig.job(session, 1, deadline, bytes)
            val jobGrant = OwnedApplicationJobPermit(session, job, deadline, {}, { "complete" }, {})
            bytes[0] = 99
            job.payload()[0] = 98
            assertContentEquals(byteArrayOf(7, 8), jobGrant.job.payload())
            assertNotSame(job, jobGrant.job)
            assertSame(session, jobGrant.generation)
            assertSame(deadline, jobGrant.originalDeadline)
        } finally { session.retire() }
    }

    @Test fun `fixed reasons and cleanup codes are detached deduplicated and not arbitrary text`() {
        val reasons = mutableListOf(OwnedApplicationReason.RESULT)
        val native = mutableListOf(OwnedSessionReason.NATIVE_OWNERSHIP.code)
        val result = OwnedApplicationRetirement(OwnedApplicationCleanupDisposition.UNVERIFIED, reasons + reasons, native)
        reasons.clear(); native.clear()
        assertEquals(listOf(OwnedApplicationReason.RESULT), result.reasons)
        assertEquals(listOf(OwnedSessionReason.NATIVE_OWNERSHIP.code), result.transportReasonCodes)
        assertFailsWith<UnsupportedOperationException> { (result.reasons as MutableList<OwnedApplicationReason>).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.transportReasonCodes as MutableList<String>).clear() }
        assertFailsWith<IllegalArgumentException> {
            OwnedApplicationRetirement(OwnedApplicationCleanupDisposition.UNVERIFIED, emptyList(), listOf("private exception text"))
        }
    }

    @Test fun `public factory exposes capabilities but no launcher or fake session parameter`() {
        val opens = OwnedApplicationSessions::class.java.methods.filter { it.name == "open" }
        assertEquals(1, opens.size)
        assertEquals(listOf(OwnedApplicationGenerationPermit::class.java, CancellationToken::class.java),
            opens.single().parameterTypes.toList())
        val names = OwnedApplicationSession::class.java.methods.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("getHello", "submit", "retire", "cancel")))
        assertFalse(names.any { it in setOf("write", "arm", "disarm", "snapshot", "closeStdin", "pollOutput") })
    }

    @Test fun `generic limits allow more than the initial four-job execution profile`() {
        assertEquals(5, ApplicationWire.Limits(5, ApplicationWire.MAX_STREAM_BYTES, ApplicationWire.MAX_STREAM_BYTES).maxJobs())
    }
}

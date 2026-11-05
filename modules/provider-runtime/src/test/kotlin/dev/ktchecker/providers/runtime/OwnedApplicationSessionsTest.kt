package dev.ktchecker.providers.runtime

import dev.ktchecker.contracts.CancellationToken
import dev.ktchecker.workerprotocol.ApplicationWire
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** These are fake-session contract tests, not compiler tests or native execution/cleanup qualification. */
class OwnedApplicationSessionsTest {
    @Test fun `native transport and HELLO must both be ready before a session is returned`() {
        val rig = ApplicationTestRig()
        val validates = AtomicInteger()
        val session = rig.open(rig.generationPermit(validateHello = { validates.incrementAndGet() }))
        assertEquals(1, validates.get())
        assertEquals(0, rig.fake.arms.get())
        assertEquals(0, rig.fake.writes.get())
        assertEquals(rig.fake.hello, session.hello)
        assertSame(rig.deadline.original, rig.configuration.generationDeadline)
        assertEquals(OwnedApplicationCleanupDisposition.CLOSED, session.retire().disposition)
    }

    @Test fun `BOUND without application HELLO never becomes Ready`() {
        val rig = ApplicationTestRig(generationMillis = 2000) { it.autoHello = false }
        val result = assertIs<OwnedApplicationOpenResult.Unavailable>(rig.factory.open(rig.generationPermit(), CancellationToken.NONE))
        assertTrue(OwnedApplicationReason.GENERATION_DEADLINE in result.reasons)
        assertEquals(0, rig.fake.arms.get())
        assertTrue(rig.fake.cancels.get() > 0)
    }

    @Test fun `wrong owner fails before any transport opening`() {
        val rig = ApplicationTestRig()
        val callbacks = AtomicInteger()
        val grant = rig.generationPermit(owner = OwnedApplicationOwner(), verifyBeforeStart = { callbacks.incrementAndGet() })
        val result = assertIs<OwnedApplicationOpenResult.Unavailable>(rig.factory.open(grant, CancellationToken.NONE))
        assertEquals(listOf(OwnedApplicationReason.OWNER_MISMATCH), result.reasons)
        assertEquals(OwnedApplicationCleanupDisposition.NOT_REQUESTED, result.cleanup.disposition)
        assertTrue(result.cleanup.transportReasonCodes.isEmpty())
        assertEquals(0, callbacks.get())
        assertEquals(0, rig.starts.get())
        // The invalid owner binding was rejected before touching even this grant's one-shot state.
        assertTrue(grant.consume())
        assertFalse(grant.consume())
    }

    @Test fun `prestart callback failure consumes generation permit without retaining exception text`() {
        val rig = ApplicationTestRig()
        val calls = AtomicInteger()
        val grant = rig.generationPermit(verifyBeforeStart = { calls.incrementAndGet(); error("private owner path and transcript") })
        val first = assertIs<OwnedApplicationOpenResult.Unavailable>(rig.factory.open(grant, CancellationToken.NONE))
        val second = assertIs<OwnedApplicationOpenResult.Unavailable>(rig.factory.open(grant, CancellationToken.NONE))
        assertTrue(OwnedApplicationReason.AUTHORITY in first.reasons)
        assertTrue(OwnedApplicationReason.PERMIT_USED in second.reasons)
        assertEquals(1, calls.get())
        assertEquals(0, rig.starts.get())
        assertFalse(first.reasons.any { "private" in it.code })
    }

    @Test fun `reentry with a generation permit is local denial and the original prestart consumer succeeds`() {
        val rig = ApplicationTestRig()
        lateinit var grant: OwnedApplicationGenerationPermit
        grant = rig.generationPermit(verifyBeforeStart = {
            assertOpenReplayIsLocal(rig.factory.open(grant, CancellationToken { true }))
        })
        val session = rig.open(grant)
        try {
            assertEquals(1, rig.starts.get())
            assertIs<OwnedApplicationJobResult.Accepted<String>>(session.submit(rig.permit(session)))
            assertEquals(0, rig.fake.cancels.get())
            assertEquals(OwnedApplicationCleanupDisposition.CLOSED, session.retire().disposition)
        } finally { session.cancel(); session.retire() }
    }

    @Test fun `reused generation permit cannot launch again or revoke the existing idle generation`() {
        val rig = ApplicationTestRig()
        val grant = rig.generationPermit()
        val session = rig.open(grant)
        try {
            assertOpenReplayIsLocal(rig.factory.open(grant, CancellationToken { true }))
            assertEquals(0, rig.fake.cancels.get())
            assertEquals(0, rig.fake.arms.get())
            assertIs<OwnedApplicationJobResult.Accepted<String>>(session.submit(rig.permit(session)))
            assertEquals(1, rig.starts.get())
            assertEquals(0, rig.fake.cancels.get())
            assertEquals(OwnedApplicationCleanupDisposition.CLOSED, session.retire().disposition)
        } finally { session.cancel(); session.retire() }
    }

    @Test fun `HELLO identity mismatch bypasses no owner validator`() {
        val rig = ApplicationTestRig { fake ->
            fake.firstOutput = { ApplicationWire.frame(ApplicationWire.Hello(fake.hello.identity(), applicationTestDigest('f'),
                fake.hello.loaderToken(), fake.hello.observedVersion(), true, true)) }
        }
        val calls = AtomicInteger()
        val result = assertIs<OwnedApplicationOpenResult.Unavailable>(rig.factory.open(
            rig.generationPermit(validateHello = { calls.incrementAndGet() }), CancellationToken.NONE))
        assertTrue(OwnedApplicationReason.HELLO in result.reasons)
        assertEquals(0, calls.get())
    }

    @Test fun `a duplicate HELLO coalesced with the first one poisons opening`() {
        val rig = ApplicationTestRig { fake -> fake.firstOutput = { ApplicationWire.frame(fake.hello) + ApplicationWire.frame(fake.hello) } }
        val result = assertIs<OwnedApplicationOpenResult.Unavailable>(rig.factory.open(rig.generationPermit(), CancellationToken.NONE))
        assertTrue(result.reasons.any { it == OwnedApplicationReason.PROTOCOL || it == OwnedApplicationReason.UNEXPECTED_OUTPUT })
    }

    @Test fun `real-time sequential API creates second request only after first Accepted`() {
        val rig = ApplicationTestRig(maxJobs = 2)
        val session = rig.open()
        val first = assertIs<OwnedApplicationJobResult.Accepted<String>>(session.submit(rig.permit(session)))
        assertEquals("detached-result-1", first.value)
        assertEquals(listOf(1), rig.fake.jobs().map { it.sequence() })
        val secondDeadline = OwnedApplicationDeadline.afterMillis(5000)
        val second = assertIs<OwnedApplicationJobResult.Accepted<String>>(session.submit(rig.permit(session, 2, secondDeadline)))
        assertEquals("detached-result-2", second.value)
        assertSame(secondDeadline.original, rig.fake.lastDeadline)
        assertEquals(listOf(1, 2), rig.fake.jobs().map { it.sequence() })
        assertEquals(2, rig.fake.disarms.get())
        assertEquals(OwnedApplicationCleanupDisposition.CLOSED, session.retire().disposition)
        assertEquals(1, rig.fake.closes.get())
    }

    @Test fun `a five-job generic session is not hard coded to the initial small experiment`() {
        val rig = ApplicationTestRig(maxJobs = 5)
        val session = rig.open()
        for (sequence in 1..5) assertIs<OwnedApplicationJobResult.Accepted<String>>(session.submit(rig.permit(session, sequence)))
        assertEquals(5, rig.fake.jobs().size)
        session.retire()
    }

    @Test fun `the complete return value is constructed while ARMed and before DISARM`() {
        val rig = ApplicationTestRig()
        val session = rig.open()
        val publishedChecks = AtomicInteger()
        val result = assertIs<OwnedApplicationJobResult.Accepted<String>>(session.submit(rig.permit(session,
            validate = {
                assertTrue(rig.fake.armed)
                assertEquals(0, rig.fake.disarms.get())
                "complete diagnostics plus all required coverage"
            }, beforePublication = {
                val check = publishedChecks.incrementAndGet()
                assertEquals(check == 1, rig.fake.armed)
            })))
        assertEquals("complete diagnostics plus all required coverage", result.value)
        assertEquals(2, publishedChecks.get())
        assertEquals(1, rig.fake.disarms.get())
        assertFalse(rig.fake.armed)
        session.retire()
    }

    @Test fun `job deadline identity must bind the actual retained ingress object`() {
        val rig = ApplicationTestRig()
        val session = rig.open()
        val named = OwnedApplicationDeadline.afterMillis(5000)
        val supplied = OwnedApplicationDeadline.afterMillis(5000)
        val grant = OwnedApplicationJobPermit(session, rig.job(session, 1, named), supplied, {}, { "not accepted" }, {})
        val result = assertIs<OwnedApplicationJobResult.Unavailable>(session.submit(grant))
        assertTrue(OwnedApplicationReason.BINDING in result.reasons)
        assertEquals(OwnedApplicationDelivery.NOT_SENT, result.delivery)
        assertEquals(0, rig.fake.arms.get())
    }

    @Test fun `job permits are scoped to the actual session object rather than descriptor equality`() {
        val rig = ApplicationTestRig()
        val session = rig.open()
        try {
            val forged = object : OwnedApplicationSession by session { }
            assertNotAdmitted(session.submit(rig.permit(forged)), OwnedApplicationReason.WRONG_GENERATION)
            assertEquals(0, rig.fake.writes.get())
            assertEquals(0, rig.fake.cancels.get())
            assertIs<OwnedApplicationJobResult.Accepted<String>>(session.submit(rig.permit(session)))
            assertEquals(OwnedApplicationCleanupDisposition.CLOSED, session.retire().disposition)
        } finally { session.cancel(); session.retire() }
    }

    @Test fun `same job permit has no retry even after a prior accepted result`() {
        val rig = ApplicationTestRig()
        val session = rig.open()
        try {
            val grant = rig.permit(session)
            assertIs<OwnedApplicationJobResult.Accepted<String>>(session.submit(grant))
            val writes = rig.fake.writes.get()
            assertNotAdmitted(session.submit(grant, CancellationToken { true }), OwnedApplicationReason.PERMIT_USED)
            assertEquals(writes, rig.fake.writes.get())
            assertEquals(0, rig.fake.cancels.get())
            assertIs<OwnedApplicationJobResult.Accepted<String>>(session.submit(rig.permit(session, 2)))
            assertEquals(listOf(1, 2), rig.fake.jobs().map { it.sequence() })
            assertEquals(0, rig.fake.cancels.get())
            assertEquals(OwnedApplicationCleanupDisposition.CLOSED, session.retire().disposition)
        } finally { session.cancel(); session.retire() }
    }

    @Test fun `out of sequence job fails before ARM or DATA`() {
        val rig = ApplicationTestRig()
        val session = rig.open()
        val failed = assertIs<OwnedApplicationJobResult.Unavailable>(session.submit(rig.permit(session, 2)))
        assertTrue(OwnedApplicationReason.BINDING in failed.reasons)
        assertEquals(0, rig.fake.arms.get())
        assertEquals(0, rig.fake.writes.get())
    }

    @Test fun `every RESULT identity field and sealed bit is checked before validation`() {
        val variants: List<(ApplicationWire.Result) -> ApplicationWire.Result> = listOf(
            { r -> changedResult(r, generation = "3".repeat(32)) },
            { r -> changedResult(r, helloDigest = applicationTestDigest('f')) },
            { r -> changedResult(r, sequence = 2) },
            { r -> changedResult(r, requestDigest = applicationTestDigest('f')) },
            { r -> changedResult(r, originalKey = applicationTestDigest('f')) },
            { r -> changedResult(r, effectiveKey = applicationTestDigest('f')) },
            { r -> changedResult(r, loaderToken = "3".repeat(32)) },
            { r -> changedResult(r, state = ApplicationWire.ResultState.OUTCOME_UNKNOWN) },
        )
        for (variant in variants) {
            val rig = ApplicationTestRig { fake -> fake.onJob = { fake.emit(ApplicationWire.frame(variant(fake.result(it)))) } }
            val session = rig.open()
            val calls = AtomicInteger()
            val result = assertIs<OwnedApplicationJobResult.Unavailable>(session.submit(rig.permit(session, validate = { calls.incrementAndGet(); "bad" })))
            assertTrue(OwnedApplicationReason.RESULT in result.reasons)
            assertEquals(0, calls.get())
            assertEquals(0, rig.fake.disarms.get())
        }
    }

    @Test fun `native FINAL without an application result is not per-job evidence`() {
        val rig = ApplicationTestRig { fake -> fake.onJob = { fake.end() } }
        val session = rig.open()
        val called = AtomicBoolean()
        val result = assertIs<OwnedApplicationJobResult.Unavailable>(session.submit(rig.permit(session, validate = { called.set(true); "bad" })))
        assertFalse(called.get())
        assertEquals(OwnedApplicationDelivery.MAY_HAVE_BEEN_WRITTEN, result.delivery)
        assertEquals(0, rig.fake.disarms.get())
    }

    @Test fun `a RESULT with a trailing partial frame cannot publish its valid prefix`() {
        val rig = ApplicationTestRig { fake -> fake.onJob = { fake.emit(ApplicationWire.frame(fake.result(it)) + byteArrayOf(0)) } }
        val session = rig.open()
        assertIs<OwnedApplicationJobResult.Unavailable>(session.submit(rig.permit(session)))
        assertEquals(0, rig.fake.disarms.get())
    }

    @Test fun `raw stderr and malformed stdout both discard an attempted result`() {
        for (stderr in listOf(true, false)) {
            val rig = ApplicationTestRig { fake -> fake.onJob = {
                fake.emit(byteArrayOf(127, 0, 0, 0), if (stderr) OwnedSessionStream.STDERR else OwnedSessionStream.STDOUT)
            } }
            val session = rig.open()
            assertIs<OwnedApplicationJobResult.Unavailable>(session.submit(rig.permit(session)))
            assertEquals(0, rig.fake.disarms.get())
        }
    }

    @Test fun `write authority failure consumes a job but submits no bytes`() {
        val rig = ApplicationTestRig()
        val session = rig.open()
        val grant = rig.permit(session, beforeWrite = { error("owner closure revoked") })
        val failure = assertIs<OwnedApplicationJobResult.Unavailable>(session.submit(grant))
        assertEquals(OwnedApplicationDelivery.NOT_SENT, failure.delivery)
        assertTrue(OwnedApplicationReason.AUTHORITY in failure.reasons)
        assertEquals(0, rig.fake.arms.get())
        assertIs<OwnedApplicationJobResult.Unavailable>(session.submit(grant))
        assertEquals(0, rig.fake.writes.get())
    }

    @Test fun `post-DISARM authority failure discards the already constructed return value`() {
        val rig = ApplicationTestRig()
        val session = rig.open()
        val checks = AtomicInteger()
        val built = AtomicBoolean()
        val result = assertIs<OwnedApplicationJobResult.Unavailable>(session.submit(rig.permit(session,
            validate = { built.set(true); "fully constructed but discarded" },
            beforePublication = { if (checks.incrementAndGet() == 2) error("revoked after ACK") })))
        assertTrue(built.get())
        assertTrue(OwnedApplicationReason.AUTHORITY in result.reasons)
        assertEquals(1, rig.fake.disarms.get())
    }

    @Test fun `cancellation from result construction discards findings before DISARM`() {
        val rig = ApplicationTestRig()
        val session = rig.open()
        val cancelled = AtomicBoolean()
        val result = assertIs<OwnedApplicationJobResult.Unavailable>(session.submit(rig.permit(session,
            validate = { cancelled.set(true); "discarded findings" }), CancellationToken { cancelled.get() }))
        assertTrue(OwnedApplicationReason.CANCELLED in result.reasons)
        assertEquals(0, rig.fake.disarms.get())
    }

    @Test fun `expired ingress deadline is not replaced by a fresh submit budget`() {
        val rig = ApplicationTestRig()
        val session = rig.open()
        val deadline = OwnedApplicationDeadline.afterMillis(10)
        applicationEventually { deadline.isExpired() }
        val result = assertIs<OwnedApplicationJobResult.Unavailable>(session.submit(rig.permit(session, deadline = deadline)))
        assertTrue(OwnedApplicationReason.REQUEST_DEADLINE in result.reasons)
        assertEquals(0, rig.fake.arms.get())
    }

    @Test fun `preparation time is charged to the actual original request deadline`() {
        val rig = ApplicationTestRig()
        val session = rig.open()
        val deadline = OwnedApplicationDeadline.afterMillis(60)
        val result = assertIs<OwnedApplicationJobResult.Unavailable>(session.submit(rig.permit(session, deadline = deadline,
            beforeWrite = { applicationEventually { deadline.isExpired() } })))
        assertTrue(OwnedApplicationReason.REQUEST_DEADLINE in result.reasons)
        assertEquals(0, rig.fake.writes.get())
    }

    @Test fun `a result received in time but validated after deadline remains unavailable`() {
        val rig = ApplicationTestRig()
        val session = rig.open()
        val deadline = OwnedApplicationDeadline.afterMillis(500)
        val entered = AtomicBoolean()
        val result = assertIs<OwnedApplicationJobResult.Unavailable>(session.submit(rig.permit(session, deadline = deadline,
            validate = { entered.set(true); applicationEventually { deadline.isExpired() }; "late output" })))
        assertTrue(entered.get())
        assertTrue(OwnedApplicationReason.REQUEST_DEADLINE in result.reasons)
        assertEquals(0, rig.fake.disarms.get())
    }

    @Test fun `known NOT_SENT data failure stays not sent and has no same-grant retry`() {
        val rig = ApplicationTestRig { it.beforeWrite = { OwnedSessionDelivery.NOT_SENT } }
        val session = rig.open()
        val grant = rig.permit(session)
        val failed = assertIs<OwnedApplicationJobResult.Unavailable>(session.submit(grant))
        assertEquals(OwnedApplicationDelivery.NOT_SENT, failed.delivery)
        assertIs<OwnedApplicationJobResult.Unavailable>(session.submit(grant))
        assertEquals(1, rig.fake.writes.get())
    }

    @Test fun `ambiguous delivery and earlier acknowledged chunks are never rewritten as NOT_SENT`() {
        for (partial in listOf(false, true)) {
            val rig = ApplicationTestRig { fake -> fake.beforeWrite = { call ->
                if (!partial) OwnedSessionDelivery.MAY_HAVE_BEEN_WRITTEN else if (call == 2) OwnedSessionDelivery.NOT_SENT else null
            } }
            val session = rig.open()
            val result = assertIs<OwnedApplicationJobResult.Unavailable>(session.submit(rig.permit(session,
                payload = if (partial) ByteArray(20_000) else byteArrayOf(1))))
            assertEquals(OwnedApplicationDelivery.MAY_HAVE_BEEN_WRITTEN, result.delivery)
            assertEquals(if (partial) 2 else 1, rig.fake.writes.get())
            assertEquals(1, rig.fake.cancels.get())
        }
    }

    @Test fun `default fake transport counts every DATA call without an installed hook`() {
        val rig = ApplicationTestRig()
        val session = rig.open()
        try {
            val grant = rig.permit(session, payload = ByteArray(20_000))
            val expectedWrites = (ApplicationWire.frame(grant.job).size + OwnedSessionLimits.CHUNK - 1) / OwnedSessionLimits.CHUNK
            assertTrue(expectedWrites > 1)
            assertIs<OwnedApplicationJobResult.Accepted<String>>(session.submit(grant))
            assertEquals(expectedWrites, rig.fake.writes.get())
            assertEquals(1, rig.fake.jobs().size)
        } finally { session.retire() }
    }

    @Test fun `optional fake write hook sees the same unconditional one based DATA count`() {
        val calls = mutableListOf<Int>()
        val rig = ApplicationTestRig { fake -> fake.beforeWrite = { call -> calls.add(call); null } }
        val session = rig.open()
        try {
            val grant = rig.permit(session, payload = ByteArray(20_000))
            val expectedWrites = (ApplicationWire.frame(grant.job).size + OwnedSessionLimits.CHUNK - 1) / OwnedSessionLimits.CHUNK
            assertTrue(expectedWrites > 1)
            assertIs<OwnedApplicationJobResult.Accepted<String>>(session.submit(grant))
            assertEquals((1..expectedWrites).toList(), calls)
            assertEquals(expectedWrites, rig.fake.writes.get())
        } finally { session.retire() }
    }

    @Test fun `draining continues while DATA acknowledgement is blocked behind a large result`() {
        val rig = ApplicationTestRig { fake ->
            fake.blockAckUntilOutputDrained = true
            fake.onJob = { fake.emit(ApplicationWire.frame(fake.result(it, ByteArray(120_000)))) }
        }
        val session = rig.open()
        try {
            val result = assertIs<OwnedApplicationJobResult.Accepted<String>>(session.submit(rig.permit(session, payload = ByteArray(60_000))))
            assertEquals("detached-result-1", result.value)
            assertTrue(rig.fake.writes.get() > 1)
            assertEquals(rig.fake.receivedBytes(), rig.fake.deliveredBytes())
        } finally { session.retire() }
    }

    @Test fun `continuous drain poisons unexpected output while a validator is still running`() {
        val rig = ApplicationTestRig()
        val session = rig.open()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = AtomicReference<OwnedApplicationJobResult<String>>()
        val thread = Thread {
            result.set(session.submit(rig.permit(session, validate = { entered.countDown(); applicationAwait(release); "discarded" })))
        }.also { it.isDaemon = true; it.start() }
        try {
            applicationAwait(entered)
            rig.fake.emit(byteArrayOf(0))
            applicationAwait(rig.fake.cancelled)
            assertTrue(thread.isAlive)
        } finally { release.countDown(); thread.join(3000); session.cancel() }
        assertFalse(thread.isAlive)
        assertIs<OwnedApplicationJobResult.Unavailable>(result.get())
        assertEquals(0, rig.fake.disarms.get())
    }

    @Test fun `a concurrent submit is denied without revoking the active or next request`() {
        assertIndependentDenialWhileActive(ActiveDenial.FRESH_JOB)
    }

    @Test fun `reentrant submit from validator cannot admit another job or revoke the outer result`() {
        val rig = ApplicationTestRig()
        val session = rig.open()
        try {
            val result = assertIs<OwnedApplicationJobResult.Accepted<String>>(session.submit(rig.permit(session, validate = {
                assertNotAdmitted(session.submit(rig.permit(session, 2)), OwnedApplicationReason.CONCURRENT_OPERATION)
                "outer output remains independently accepted"
            })))
            assertEquals("outer output remains independently accepted", result.value)
            assertEquals(1, rig.fake.jobs().size)
            assertEquals(1, rig.fake.disarms.get())
            assertEquals(0, rig.fake.cancels.get())
            assertIs<OwnedApplicationJobResult.Accepted<String>>(session.submit(rig.permit(session, 2)))
            assertEquals(OwnedApplicationCleanupDisposition.CLOSED, session.retire().disposition)
        } finally { session.cancel(); session.retire() }
    }

    @Test fun `generation cancellation is observed even while the ordinary write caller is blocked`() {
        val revoked = AtomicBoolean()
        val rig = ApplicationTestRig { fake -> fake.beforeWrite = {
            revoked.set(true)
            applicationAwait(fake.cancelled)
            OwnedSessionDelivery.MAY_HAVE_BEEN_WRITTEN
        } }
        val session = rig.open(cancellation = CancellationToken { revoked.get() })
        val result = assertIs<OwnedApplicationJobResult.Unavailable>(session.submit(rig.permit(session)))
        assertTrue(OwnedApplicationReason.CANCELLED in result.reasons)
        assertEquals(OwnedApplicationDelivery.MAY_HAVE_BEEN_WRITTEN, result.delivery)
    }

    @Test fun `cancellation revokes admission before invoking the transport`() {
        val rig = ApplicationTestRig()
        val session = rig.open()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        rig.fake.onCancel = { entered.countDown(); applicationAwait(release) }
        val cancel = Thread { session.cancel() }.also { it.isDaemon = true; it.start() }
        val attempted = AtomicReference<OwnedApplicationJobResult<String>>()
        var submit: Thread? = null
        try {
            applicationAwait(entered)
            val submitting = Thread { attempted.set(session.submit(rig.permit(session))) }.also { it.isDaemon = true; it.start() }
            submit = submitting
            applicationEventually { attempted.get() != null }
            submitting.join(1000)
            assertFalse(submitting.isAlive)
            // The denied call completes while the actual cancellation is still blocked, with no cleanup wait.
            assertTrue(cancel.isAlive)
            assertNotAdmitted(attempted.get(), OwnedApplicationReason.NOT_READY)
            assertEquals(0, rig.fake.arms.get())
            assertEquals(1, rig.fake.cancels.get())
        } finally { release.countDown(); cancel.join(3000); submit?.join(3000); session.cancel(); session.retire() }
    }

    @Test fun `idle native termination denies the next job without treating FINAL as readiness`() {
        val rig = ApplicationTestRig()
        val session = rig.open()
        rig.fake.end()
        val result = assertIs<OwnedApplicationJobResult.Unavailable>(session.submit(rig.permit(session)))
        assertEquals(OwnedApplicationDelivery.NOT_SENT, result.delivery)
        assertEquals(0, rig.fake.writes.get())
    }

    @Test fun `maxJobs is an admission cap and healthy retirement uses idle EOF without another ARM`() {
        val rig = ApplicationTestRig(maxJobs = 1)
        val session = rig.open()
        assertIs<OwnedApplicationJobResult.Accepted<String>>(session.submit(rig.permit(session)))
        assertEquals(0, rig.fake.closes.get())
        assertEquals(1, rig.fake.arms.get())
        val first = session.retire()
        val again = session.retire()
        assertSame(first, again)
        assertEquals(OwnedApplicationCleanupDisposition.CLOSED, first.disposition)
        assertEquals(1, rig.fake.closes.get())
        assertEquals(1, rig.fake.arms.get())
        assertNotAdmitted(session.submit(rig.permit(session, 1)), OwnedApplicationReason.NOT_READY)
        assertEquals(0, rig.fake.cancels.get())
        assertSame(first, session.retire())
        assertEquals(1, rig.fake.jobs().size)
    }

    @Test fun `attempt beyond maxJobs is denied without prequeueing or changing custody`() {
        val rig = ApplicationTestRig(maxJobs = 1)
        val session = rig.open()
        try {
            assertIs<OwnedApplicationJobResult.Accepted<String>>(session.submit(rig.permit(session)))
            assertNotAdmitted(session.submit(rig.permit(session, 2)), OwnedApplicationReason.JOB_LIMIT)
            assertEquals(1, rig.fake.jobs().size)
            assertEquals(1, rig.fake.arms.get())
            assertEquals(0, rig.fake.closes.get())
            assertEquals(0, rig.fake.cancels.get())
            assertEquals(OwnedApplicationCleanupDisposition.CLOSED, session.retire().disposition)
        } finally { session.cancel(); session.retire() }
    }

    @Test fun `quarantined custody is not upgraded by a later fake cleanup observation`() {
        val rig = ApplicationTestRig { it.quarantineOnCancel = true }
        val session = rig.open()
        session.cancel()
        val first = session.retire()
        assertEquals(OwnedApplicationCleanupDisposition.QUARANTINED, first.disposition)
        assertTrue(OwnedSessionReason.CLEANUP_UNVERIFIED.code in first.transportReasonCodes)
        rig.fake.end(quarantine = false) // Fake late hygiene cannot rehabilitate the sealed application result.
        assertSame(first, session.retire())
        assertEquals(OwnedApplicationCleanupDisposition.QUARANTINED, session.retire().disposition)
    }

    @Test fun `unresolved prestart transport custody is not classified as nothing started`() {
        val rig = ApplicationTestRig()
        val factory = OwnedApplicationSessionFactory {
            OwnedSessionOpenResult.Rejected(listOf(OwnedSessionReason.START_UNKNOWN, OwnedSessionReason.CLEANUP_UNVERIFIED))
        }
        val result = assertIs<OwnedApplicationOpenResult.Unavailable>(factory.open(rig.generationPermit(), CancellationToken.NONE))
        assertEquals(OwnedApplicationCleanupDisposition.UNVERIFIED, result.cleanup.disposition)
        assertTrue(OwnedSessionReason.CLEANUP_UNVERIFIED.code in result.cleanup.transportReasonCodes)
    }

    @Test fun `interrupted owner callback preserves interruption and cannot publish`() {
        val rig = ApplicationTestRig()
        val session = rig.open()
        try {
            val result = assertIs<OwnedApplicationJobResult.Unavailable>(session.submit(rig.permit(session,
                validate = { throw InterruptedException("must not be retained") })))
            assertTrue(OwnedApplicationReason.INTERRUPTED in result.reasons)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted(); session.cancel(); session.retire() }
    }

    @Test fun `duplicate complete RESULT in one chunk never exposes its otherwise valid prefix`() {
        val rig = ApplicationTestRig { fake -> fake.onJob = {
            val frame = ApplicationWire.frame(fake.result(it))
            fake.emit(frame + frame)
        } }
        val session = rig.open()
        val validates = AtomicInteger()
        val failed = assertIs<OwnedApplicationJobResult.Unavailable>(session.submit(rig.permit(session,
            validate = { validates.incrementAndGet(); "must not be exposed" })))
        assertTrue(OwnedApplicationReason.PROTOCOL in failed.reasons)
        assertEquals(0, validates.get())
        assertEquals(0, rig.fake.disarms.get())
    }

    @Test fun `drain remains live during HELLO validation and rejects any subsequent output`() {
        val rig = ApplicationTestRig()
        val grant = rig.generationPermit(validateHello = {
            rig.fake.emit(byteArrayOf(0))
            applicationAwait(rig.fake.cancelled)
        })
        val result = assertIs<OwnedApplicationOpenResult.Unavailable>(rig.factory.open(grant, CancellationToken.NONE))
        assertTrue(OwnedApplicationReason.UNEXPECTED_OUTPUT in result.reasons)
    }

    @Test fun `output during otherwise healthy idle EOF is recorded as a protocol failure not hidden`() {
        val rig = ApplicationTestRig()
        val session = rig.open()
        rig.fake.beforeClose = { rig.fake.emit(byteArrayOf(0)) }
        val result = session.retire()
        assertTrue(OwnedApplicationReason.UNEXPECTED_OUTPUT in result.reasons)
        // Any CLOSED disposition remains custody-only and cannot rescue that protocol failure.
        assertEquals(0, rig.fake.jobs().size)
    }

    @Test fun `unknown transport opening exception is unverified and has no second launch attempt`() {
        val rig = ApplicationTestRig()
        val attempts = AtomicInteger()
        val factory = OwnedApplicationSessionFactory { attempts.incrementAndGet(); error("possibly after actual start") }
        val grant = rig.generationPermit()
        val failed = assertIs<OwnedApplicationOpenResult.Unavailable>(factory.open(grant, CancellationToken.NONE))
        assertEquals(OwnedApplicationCleanupDisposition.UNVERIFIED, failed.cleanup.disposition)
        assertIs<OwnedApplicationOpenResult.Unavailable>(factory.open(grant, CancellationToken.NONE))
        assertEquals(1, attempts.get())
    }

    @Test fun `prelaunch cancellation consumes the grant before callbacks or any transport start`() {
        val rig = ApplicationTestRig()
        val callbacks = AtomicInteger()
        val grant = rig.generationPermit(verifyBeforeStart = { callbacks.incrementAndGet() })
        val failed = assertIs<OwnedApplicationOpenResult.Unavailable>(rig.factory.open(grant, CancellationToken { true }))
        assertTrue(OwnedApplicationReason.CANCELLED in failed.reasons)
        assertEquals(OwnedApplicationCleanupDisposition.NOT_STARTED, failed.cleanup.disposition)
        assertIs<OwnedApplicationOpenResult.Unavailable>(rig.factory.open(grant, CancellationToken.NONE))
        assertEquals(0, callbacks.get())
        assertEquals(0, rig.starts.get())
    }

    @Test fun `a completed request cancellation check cannot revoke the idle generation`() {
        assertCompletedRequestCheckIsIgnored(expireDeadline = false, nextJobAdmitted = false)
    }

    @Test fun `a completed request cancellation check cannot revoke the next admitted request`() {
        assertCompletedRequestCheckIsIgnored(expireDeadline = false, nextJobAdmitted = true)
    }

    @Test fun `a completed request deadline check cannot revoke the idle generation`() {
        assertCompletedRequestCheckIsIgnored(expireDeadline = true, nextJobAdmitted = false)
    }

    @Test fun `a completed request deadline check cannot revoke the next admitted request`() {
        assertCompletedRequestCheckIsIgnored(expireDeadline = true, nextJobAdmitted = true)
    }

    @Test fun `a completed request throwing InterruptedException cannot poison the idle drainer`() {
        assertCompletedRequestCheckIsIgnored(expireDeadline = false, nextJobAdmitted = false, throwInterrupted = true)
    }

    @Test fun `a completed request throwing InterruptedException cannot poison the next request`() {
        assertCompletedRequestCheckIsIgnored(expireDeadline = false, nextJobAdmitted = true, throwInterrupted = true)
    }

    @Test fun `an active request callback InterruptedException still revokes and restores the pump flag`() {
        val rig = ApplicationTestRig()
        val session = rig.open()
        val owner = Thread.currentThread()
        val failPumpCheck = AtomicBoolean()
        val interruptedAtCancel = AtomicBoolean()
        rig.fake.onCancel = { interruptedAtCancel.set(Thread.currentThread().isInterrupted) }
        val token = CancellationToken {
            if (Thread.currentThread() !== owner && failPumpCheck.get()) throw InterruptedException("not retained")
            false
        }
        try {
            val result = assertIs<OwnedApplicationJobResult.Unavailable>(session.submit(rig.permit(session, validate = {
                failPumpCheck.set(true)
                applicationAwait(rig.fake.cancelled)
                "must not publish after active watchdog failure"
            }), token))
            assertTrue(OwnedApplicationReason.INTERRUPTED in result.reasons)
            assertTrue(interruptedAtCancel.get())
            assertFalse(owner.isInterrupted)
            assertEquals(0, rig.fake.disarms.get())
        } finally { session.cancel(); session.retire() }
    }

    @Test fun `generation callback InterruptedException is not deferred behind request identity`() {
        val rig = ApplicationTestRig()
        val owner = Thread.currentThread()
        val failPumpCheck = AtomicBoolean()
        val interruptedAtCancel = AtomicBoolean()
        val token = CancellationToken {
            if (Thread.currentThread() !== owner && failPumpCheck.get()) throw InterruptedException("not retained")
            false
        }
        val session = rig.open(cancellation = token)
        rig.fake.onCancel = { interruptedAtCancel.set(Thread.currentThread().isInterrupted) }
        try {
            val result = assertIs<OwnedApplicationJobResult.Unavailable>(session.submit(rig.permit(session,
                beforeWrite = {
                    failPumpCheck.set(true)
                    applicationAwait(rig.fake.cancelled)
                })))
            assertTrue(OwnedApplicationReason.INTERRUPTED in result.reasons)
            assertTrue(interruptedAtCancel.get())
            assertFalse(owner.isInterrupted)
            assertEquals(0, rig.fake.writes.get())
        } finally { session.cancel(); session.retire() }
    }

    @Test fun `ignoring a stale callback exception never clears an independently present pump interrupt`() {
        val rig = ApplicationTestRig(maxJobs = 2, generationMillis = 15_000)
        val session = rig.open()
        val owner = Thread.currentThread()
        val captureNext = AtomicBoolean()
        val captured = CountDownLatch(1)
        val release = CountDownLatch(1)
        val token = CancellationToken {
            if (Thread.currentThread() !== owner && captureNext.compareAndSet(true, false)) {
                captured.countDown()
                assertTrue(release.await(6, TimeUnit.SECONDS))
                // Model a flag already present when exception classification starts. No attempt
                // is made to recover a historical interrupt consumed by trusted callback code.
                Thread.currentThread().interrupt()
                throw InterruptedException("not retained")
            }
            false
        }
        try {
            assertIs<OwnedApplicationJobResult.Accepted<String>>(session.submit(rig.permit(session, validate = {
                captureNext.set(true)
                applicationAwait(captured)
                "already accepted A"
            }), token))
            release.countDown()
            applicationAwait(rig.fake.cancelled)
            assertTrue(rig.fake.interruptedOutputPolls.get() > 0)
            assertNotAdmitted(session.submit(rig.permit(session, 2)), OwnedApplicationReason.NOT_READY)
            assertEquals(listOf(1), rig.fake.jobs().map { it.sequence() })
            assertFalse(owner.isInterrupted)
        } finally { release.countDown(); session.cancel(); session.retire() }
    }

    @Test fun `wrong actual session cannot consume a foreign grant and both sessions stay usable`() {
        val firstRig = ApplicationTestRig()
        val secondRig = ApplicationTestRig()
        val first = firstRig.open()
        val second = secondRig.open()
        try {
            val originalGrant = firstRig.permit(first)
            assertNotAdmitted(second.submit(originalGrant, CancellationToken { true }), OwnedApplicationReason.WRONG_GENERATION)
            assertEquals(0, firstRig.fake.arms.get())
            assertEquals(0, secondRig.fake.arms.get())
            assertEquals(0, firstRig.fake.cancels.get())
            assertEquals(0, secondRig.fake.cancels.get())
            // EXACT same grant, not a replacement: the erroneous target could not steal its consumption.
            assertIs<OwnedApplicationJobResult.Accepted<String>>(first.submit(originalGrant))
            assertIs<OwnedApplicationJobResult.Accepted<String>>(second.submit(secondRig.permit(second)))
            assertEquals(1, firstRig.fake.jobs().size)
            assertEquals(1, secondRig.fake.jobs().size)
            assertEquals(0, firstRig.fake.cancels.get())
            assertEquals(0, secondRig.fake.cancels.get())
            assertEquals(OwnedApplicationCleanupDisposition.CLOSED, first.retire().disposition)
            assertEquals(OwnedApplicationCleanupDisposition.CLOSED, second.retire().disposition)
        } finally { first.cancel(); second.cancel(); first.retire(); second.retire() }
    }

    @Test fun `parallel generation replay while prestart is blocked cannot revoke the original open`() {
        val rig = ApplicationTestRig()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancellationChecks = AtomicInteger()
        val grant = rig.generationPermit(verifyBeforeStart = { entered.countDown(); applicationAwait(release) })
        val opened = AtomicReference<OwnedApplicationOpenResult>()
        val thread = Thread { opened.set(rig.factory.open(grant, CancellationToken.NONE)) }
            .also { it.isDaemon = true; it.start() }
        var session: OwnedApplicationSession? = null
        try {
            applicationAwait(entered)
            assertOpenReplayIsLocal(rig.factory.open(grant, CancellationToken { cancellationChecks.incrementAndGet(); true }))
            assertEquals(0, cancellationChecks.get())
            assertEquals(0, rig.starts.get())
            assertTrue(thread.isAlive)
            release.countDown()
            thread.join(3000)
            assertFalse(thread.isAlive)
            val active = assertIs<OwnedApplicationOpenResult.Ready>(opened.get()).session.also { session = it }
            assertIs<OwnedApplicationJobResult.Accepted<String>>(active.submit(rig.permit(active)))
            assertEquals(1, rig.starts.get())
            assertEquals(0, rig.fake.cancels.get())
            assertEquals(OwnedApplicationCleanupDisposition.CLOSED, active.retire().disposition)
        } finally {
            release.countDown(); thread.join(3000)
            (session ?: (opened.get() as? OwnedApplicationOpenResult.Ready)?.session)?.let { it.cancel(); it.retire() }
        }
    }

    @Test fun `job permit replay while its original call is active cannot revoke or resubmit it`() {
        assertIndependentDenialWhileActive(ActiveDenial.REPLAY_JOB)
    }

    @Test fun `generation permit replay while a job is active cannot revoke it or the next request`() {
        assertIndependentDenialWhileActive(ActiveDenial.REPLAY_GENERATION)
    }

    @Test fun `cancelled concurrent request is denied without polling it or cancelling other work`() {
        assertIndependentDenialWhileActive(ActiveDenial.CANCELLED_JOB)
    }

    private enum class ActiveDenial { FRESH_JOB, REPLAY_JOB, REPLAY_GENERATION, CANCELLED_JOB }

    private fun assertIndependentDenialWhileActive(denial: ActiveDenial) {
        val rig = ApplicationTestRig()
        val prestarts = AtomicInteger()
        val generationGrant = rig.generationPermit(verifyBeforeStart = { prestarts.incrementAndGet() })
        val session = rig.open(generationGrant)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicReference<OwnedApplicationJobResult<String>>()
        val originalGrant = rig.permit(session, validate = { entered.countDown(); applicationAwait(release); "first stays accepted" })
        val thread = Thread { first.set(session.submit(originalGrant)) }.also { it.isDaemon = true; it.start() }
        val deniedCancellationChecks = AtomicInteger()
        val deniedOwnerHooks = AtomicInteger()
        val deniedCancellation = CancellationToken { deniedCancellationChecks.incrementAndGet(); true }
        var deniedFreshGrant: OwnedApplicationJobPermit<String>? = null
        try {
            applicationAwait(entered)
            val writes = rig.fake.writes.get()
            when (denial) {
                ActiveDenial.FRESH_JOB, ActiveDenial.CANCELLED_JOB -> {
                    val fresh = rig.permit(session, 2, beforeWrite = { deniedOwnerHooks.incrementAndGet() })
                    deniedFreshGrant = fresh
                    assertNotAdmitted(session.submit(fresh,
                        if (denial == ActiveDenial.CANCELLED_JOB) deniedCancellation else CancellationToken.NONE),
                        OwnedApplicationReason.CONCURRENT_OPERATION)
                }
                ActiveDenial.REPLAY_JOB -> assertNotAdmitted(session.submit(originalGrant, deniedCancellation), OwnedApplicationReason.PERMIT_USED)
                ActiveDenial.REPLAY_GENERATION -> assertOpenReplayIsLocal(rig.factory.open(generationGrant, deniedCancellation))
            }
            assertEquals(0, deniedCancellationChecks.get())
            assertEquals(0, deniedOwnerHooks.get())
            assertEquals(writes, rig.fake.writes.get())
            assertEquals(1, rig.fake.arms.get())
            assertEquals(0, rig.fake.cancels.get())
            assertEquals(0, rig.fake.closes.get())
            assertTrue(thread.isAlive)
            release.countDown()
            thread.join(3000)
            assertFalse(thread.isAlive)
            assertEquals("first stays accepted", assertIs<OwnedApplicationJobResult.Accepted<String>>(first.get()).value)
            assertEquals(listOf(1), rig.fake.jobs().map { it.sequence() })
            // A correct-session denied call used its own grant, but never a permission to cancel A.
            deniedFreshGrant?.let { assertNotAdmitted(session.submit(it), OwnedApplicationReason.PERMIT_USED) }
            assertIs<OwnedApplicationJobResult.Accepted<String>>(session.submit(rig.permit(session, 2)))
            assertEquals(listOf(1, 2), rig.fake.jobs().map { it.sequence() })
            assertEquals(1, rig.starts.get())
            assertEquals(1, prestarts.get())
            assertEquals(0, deniedOwnerHooks.get())
            assertEquals(0, rig.fake.cancels.get())
            assertEquals(OwnedApplicationCleanupDisposition.CLOSED, session.retire().disposition)
        } finally { release.countDown(); thread.join(3000); session.cancel(); session.retire() }
    }

    private fun assertNotAdmitted(result: OwnedApplicationJobResult<*>, reason: OwnedApplicationReason) {
        val denied = assertIs<OwnedApplicationJobResult.Unavailable>(result)
        assertEquals(listOf(reason), denied.reasons)
        assertEquals(OwnedApplicationDelivery.NOT_SENT, denied.delivery)
        assertEquals(OwnedApplicationCleanupDisposition.NOT_REQUESTED, denied.cleanup.disposition)
        assertEquals(denied.reasons, denied.cleanup.reasons)
        assertTrue(denied.cleanup.transportReasonCodes.isEmpty())
    }

    private fun assertOpenReplayIsLocal(result: OwnedApplicationOpenResult) {
        val denied = assertIs<OwnedApplicationOpenResult.Unavailable>(result)
        assertEquals(listOf(OwnedApplicationReason.PERMIT_USED), denied.reasons)
        assertEquals(OwnedApplicationCleanupDisposition.NOT_REQUESTED, denied.cleanup.disposition)
        assertEquals(denied.reasons, denied.cleanup.reasons)
        assertTrue(denied.cleanup.transportReasonCodes.isEmpty())
    }

    private fun assertCompletedRequestCheckIsIgnored(expireDeadline: Boolean, nextJobAdmitted: Boolean,
                                                   throwInterrupted: Boolean = false) {
        val rig = ApplicationTestRig(maxJobs = 2, generationMillis = 15_000)
        val session = rig.open()
        val ownerThread = Thread.currentThread()
        val captureNextPumpCheck = AtomicBoolean()
        val captured = CountDownLatch(1)
        val release = CountDownLatch(1)
        val revoked = AtomicBoolean()
        val firstDeadline = OwnedApplicationDeadline.afterMillis(if (expireDeadline) 2000 else 5000)
        val firstCancellation = CancellationToken {
            // The owner remains unblocked. Only the already sampled drainer check is suspended.
            if (Thread.currentThread() !== ownerThread && captureNextPumpCheck.compareAndSet(true, false)) {
                captured.countDown()
                assertTrue(release.await(6, TimeUnit.SECONDS))
                if (throwInterrupted) throw InterruptedException("obsolete request callback text is not retained")
            }
            revoked.get()
        }
        try {
            assertIs<OwnedApplicationJobResult.Accepted<String>>(session.submit(rig.permit(session,
                deadline = firstDeadline, validate = {
                    captureNextPumpCheck.set(true)
                    applicationAwait(captured)
                    "first result accepted before an obsolete watchdog observation"
                }), firstCancellation))
            // A is fully published and no longer current before its watchdog can observe failure.
            if (expireDeadline) applicationEventually { firstDeadline.isExpired() } else revoked.set(true)
            val beforePoll = rig.fake.outputPolls.get()
            val releaseAndObserve = {
                release.countDown()
                // The next poll happens only after that obsolete check and any attempted poison.
                applicationEventually { rig.fake.outputPolls.get() > beforePoll }
                assertEquals(0, rig.fake.cancels.get())
            }
            if (!nextJobAdmitted) releaseAndObserve()
            assertIs<OwnedApplicationJobResult.Accepted<String>>(session.submit(rig.permit(session, sequence = 2,
                beforeWrite = { if (nextJobAdmitted) releaseAndObserve() })))
            assertEquals(listOf(1, 2), rig.fake.jobs().map { it.sequence() })
            assertEquals(0, rig.fake.cancels.get())
            assertEquals(0, rig.fake.interruptedOutputPolls.get())
            assertEquals(OwnedApplicationCleanupDisposition.CLOSED, session.retire().disposition)
        } finally {
            release.countDown()
            session.cancel()
            session.retire()
        }
    }

    private fun changedResult(
        r: ApplicationWire.Result,
        generation: String = r.generation(),
        sequence: Int = r.sequence(),
        helloDigest: String = r.helloDigest(),
        requestDigest: String = r.requestDigest(),
        originalKey: String = r.originalKey(),
        effectiveKey: String = r.effectiveKey(),
        loaderToken: String = r.loaderToken(),
        state: ApplicationWire.ResultState = r.state(),
    ) = ApplicationWire.Result(generation, helloDigest, sequence, ApplicationWire.jobId(generation, sequence), requestDigest,
        originalKey, effectiveKey, loaderToken, state, r.payload())
}

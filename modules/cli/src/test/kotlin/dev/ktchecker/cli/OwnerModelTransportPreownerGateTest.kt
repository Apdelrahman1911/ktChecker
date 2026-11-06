package dev.ktchecker.cli

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** INERT local CAS controls. No owner/Workbench, file selection, process or model is created.
 * A false terminal claim tests retention eligibility; it does not execute an infinite owner wait.
 */
class OwnerModelTransportPreownerGateTest {
    @Test fun untouchedFramingCanClaimTerminalExactlyOnce() {
        val gate = OwnerModelTransportPreownerGate()
        assertTrue(gate.claimTerminalBeforeOwner())
        repeat(3) { assertFalse(gate.claimTerminalBeforeOwner()) }
        assertEquals(78, OWNER_MODEL_TRANSPORT_PREOWNER_EXIT)
    }

    @Test fun terminalClaimPermanentlyRejectsEveryLaterSetupBeforeEffects() {
        val gate = OwnerModelTransportPreownerGate()
        assertTrue(gate.claimTerminalBeforeOwner())
        var effects = 0
        repeat(3) {
            assertEquals("owner_transport_setup_replayed_or_terminal", assertFailsWith<CliProblem> {
                gate.beginOwnerSetup()
                effects++ // Represents an effect AFTER the actual production gate, never owner IO.
            }.code)
        }
        assertEquals(0, effects)
        assertFalse(gate.claimTerminalBeforeOwner())
    }

    @Test fun setupClaimPermanentlyDeniesTerminalAndSecondSetup() {
        val gate = OwnerModelTransportPreownerGate()
        gate.beginOwnerSetup()
        repeat(3) {
            assertFalse(gate.claimTerminalBeforeOwner())
            assertFailsWith<CliProblem> { gate.beginOwnerSetup() }
        }
    }

    @Test fun partialSetupThrowWithNoReturnedOwnerNeverMakesTerminalEligible() {
        val gate = OwnerModelTransportPreownerGate()
        val retainedOwner: Any? = null // Deliberately no actual owner construction in this control.
        assertFailsWith<IOException> {
            gate.beginOwnerSetup()
            throw IOException("SYNTHETIC selection failure before any owner return")
        }
        assertNull(retainedOwner)
        assertFalse(gate.claimTerminalBeforeOwner())
        assertFailsWith<CliProblem> { gate.beginOwnerSetup() }
        assertFalse(gate.claimTerminalBeforeOwner())
    }

    @Test fun arbitraryPostSetupThrowableAlsoCannotRearmTerminal() {
        val gate = OwnerModelTransportPreownerGate()
        assertFailsWith<AssertionError> {
            gate.beginOwnerSetup()
            throw AssertionError("SYNTHETIC ambiguous setup")
        }
        assertFalse(gate.claimTerminalBeforeOwner())
        assertFalse(gate.claimTerminalBeforeOwner())
    }

    @Test fun racingSetupAndTerminalHaveExactlyOneWinnerAndNoReentry() {
        repeat(16) {
            val gate = OwnerModelTransportPreownerGate()
            val ready = CountDownLatch(2); val start = CountDownLatch(1)
            val setup = AtomicReference<Boolean?>(); val terminal = AtomicReference<Boolean?>()
            val failure = AtomicReference<Throwable?>()
            fun participant(name: String, action: () -> Unit) = Thread({
                try {
                    ready.countDown(); check(start.await(2, TimeUnit.SECONDS))
                    action()
                } catch (error: Throwable) { failure.compareAndSet(null, error) }
            }, name).apply { isDaemon = true }
            val setupThread = participant("inert-preowner-setup-CAS") {
                try { gate.beginOwnerSetup(); setup.set(true) }
                catch (_: CliProblem) { setup.set(false) }
            }
            val terminalThread = participant("inert-preowner-terminal-CAS") { terminal.set(gate.claimTerminalBeforeOwner()) }
            setupThread.start(); terminalThread.start()
            try { assertTrue(ready.await(2, TimeUnit.SECONDS)) }
            finally { start.countDown() } // Local inert barrier only, never a process/lifetime signal.
            setupThread.join(2_000); terminalThread.join(2_000)
            assertFalse(setupThread.isAlive); assertFalse(terminalThread.isAlive); assertNull(failure.get())
            assertNotNull(setup.get()); assertNotNull(terminal.get())
            assertNotEquals(setup.get(), terminal.get())
            assertFalse(gate.claimTerminalBeforeOwner())
            assertFailsWith<CliProblem> { gate.beginOwnerSetup() }
        }
    }

    @Test fun queuedSetupTasksCannotCrossAnAlreadyClaimedTerminal() {
        val gate = OwnerModelTransportPreownerGate()
        val start = CountDownLatch(1); val effects = AtomicInteger(); val denied = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val queued = List(4) { number -> Thread({
            try {
                check(start.await(2, TimeUnit.SECONDS))
                try { gate.beginOwnerSetup(); effects.incrementAndGet() }
                catch (_: CliProblem) { denied.incrementAndGet() }
            } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }, "inert-preowner-queued-$number").apply { isDaemon = true } }
        queued.forEach(Thread::start)
        try { assertTrue(gate.claimTerminalBeforeOwner()) }
        finally { start.countDown() }
        queued.forEach { it.join(2_000) }
        assertTrue(queued.none(Thread::isAlive)); assertNull(failure.get())
        assertEquals(0, effects.get()); assertEquals(4, denied.get())
        assertFalse(gate.claimTerminalBeforeOwner())
    }
}

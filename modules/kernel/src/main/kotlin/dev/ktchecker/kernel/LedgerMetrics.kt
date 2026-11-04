package dev.ktchecker.kernel

import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded, process-local diagnostics. No source, identities, credentials or samples are retained.
 * Timings use a monotonic clock; they are observations, never scheduler/permission authority.
 * A snapshot is approximate while operations run and exact after this instance is quiescent.
 */
internal class LedgerMetrics {
    val connection = TimingCounter()
    val read = TimingCounter()
    val writerWait = TimingCounter()
    val writerHold = TimingCounter()

    fun snapshot() = LedgerTimingSnapshot(connection.snapshot(), read.snapshot(), writerWait.snapshot(), writerHold.snapshot())
}

internal class TimingCounter {
    private val count = AtomicLong()
    private val total = AtomicLong()
    private val maximum = AtomicLong()

    fun record(nanos: Long) {
        val elapsed = nanos.coerceAtLeast(0)
        total.addAndGet(elapsed)
        maximum.accumulateAndGet(elapsed, ::maxOf)
        count.incrementAndGet()
    }

    fun snapshot() = TimingSnapshot(count.get(), total.get(), maximum.get())
}

internal data class TimingSnapshot(val count: Long, val totalNanos: Long, val maximumNanos: Long)
internal data class LedgerTimingSnapshot(
    val connection: TimingSnapshot,
    val read: TimingSnapshot,
    val writerWait: TimingSnapshot,
    val writerHold: TimingSnapshot,
)

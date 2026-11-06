package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable

/** Private test input, written with writeDurable; never an owner/model/transport authority. */
@Serializable internal data class CancellationAckCrashSpec(
    val config: KernelConfig,
    val clients: List<ClientRegistration>,
    val historicalPlanId: String,
    val sharedSnapshotId: String,
    val checkpoint: String,
)

/** Match the suite's stable insertion recipe so a lost reply has an exact, idempotent retry. */
internal fun cancellationAckCrashReceipt(event: ReviewEvent) = ContextReceipt("ack-${event.eventId}",
    Digests.sha256("synthetic-context:${event.payload}"), "item-${event.eventId}", Instant.EPOCH.toString())

/**
 * Fresh subprocess fixture only. Real SQLite/claim/cancel/ACK commits, SYNTHETIC provider and
 * delivery evidence. The parent forcibly terminates only its fresh child after the phase marker
 * and before provider return; no receipt response/new cursor is checkpointed. No real network.
 */
object KernelCancellationAckCrashChild {
    @JvmStatic fun main(args: Array<String>) {
        check(args.size == 3)
        val spec = ContractJson.decodeFromString<CancellationAckCrashSpec>(Files.readString(Path.of(args[1])))
        check(spec.clients.size == 2 && spec.clients.map { it.binding.clientId }.distinct().size == 2)
        check(spec.config.workerCount == 1)
        val entered = CountDownLatch(1)
        val hold = CountDownLatch(1)
        val provider = PlanTestProvider { input, cancellation ->
            check(input.snapshot.snapshotId == spec.sharedSnapshotId) { "The owned child claimed the wrong fixture history" }
            cancellation.throwIfCancelled()
            entered.countDown()
            check(hold.await(45, TimeUnit.SECONDS)) { "The fresh crash fixture was not terminated in its finite phase" }
            error("The fresh crash fixture must never return provider evidence")
        }
        KernelService.open(Path.of(args[0]), provider, spec.config).use { service ->
            check(entered.await(15, TimeUnit.SECONDS)) { "The fresh fixture did not enter its shared durable claim" }
            val a = spec.clients[0]
            val b = spec.clients[1]
            check(service.cancelPlan(a.client, spec.historicalPlanId).state == "cancelled")
            val page = service.events(b.client, spec.checkpoint, 1)
            val event = page.events.single()
            check(event.kind == "plan_accepted")
            // Deliberately discard the returned ReceiptResult and page.cursor. Both transactions
            // committed before the marker, but the application retains only its old checkpoint.
            service.acknowledge(b.adapter, listOf(event.eventId), cancellationAckCrashReceipt(event))
            writeDurable(Path.of(args[2]), "committed-local-cancel-and-peer-ack\n".toByteArray())
            check(hold.await(45, TimeUnit.SECONDS)) { "The fresh crash fixture was not terminated after its committed phase" }
            error("The parent must terminate only this freshly owned process")
        }
    }
}

package dev.ktchecker.kernel

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

/** Test-only real child JVM; all owner/model/approval authority and provider results are synthetic. */
object KernelRoutingCrashChild {
    @JvmStatic fun main(args: Array<String>) {
        val config = ContractJson.decodeFromString<KernelConfig>(Files.readString(Path.of(args[1])))
        val plans = ContractJson.decodeFromString<List<RequiredReviewPlan>>(Files.readString(Path.of(args[2])))
        val clients = ContractJson.decodeFromString<List<ClientRegistration>>(Files.readString(Path.of(args[3])))
        val origin = plans.first().copy(requiredRecipientClientIds = plans.map { it.binding.clientId })
        val recipients = plans.zip(clients).map { (plan, client) ->
            val digest = Digests.sha256(canonical(plan))
            val preparation = PlanRecipientPreparation(digest,
                Digests.sha256("synthetic-child-policy:${canonical(client.binding)}:$digest"),
                Digests.sha256("synthetic-child-approval:${canonical(client.binding)}:$digest"))
            AuthorizedPlanRecipient(plan, client.adapter, preparation) {
                plan.slots.forEach { InputArchive.verifyFrozen(it.input) }
                preparation
            }
        }
        val route = AuthorizedReviewRoute(origin, recipients)
        KernelService.open(Path.of(args[0]), PlanTestProvider(), config,
            planAuthority = ReviewPlanAuthority { binding, request -> origin.takeIf { it.binding == binding && it.request == request } },
            routingAuthority = ReviewPlanRoutingAuthority { binding, request, declared ->
                route.takeIf { origin.binding == binding && origin.request == request && origin == declared }
            }).use { service ->
            service.plans.afterSlotInsert = { count -> if (count == 3) {
                writeDurable(Path.of(args[4]), "synthetic-uncommitted-routed-prefix".toByteArray())
                CountDownLatch(1).await()
            } }
            service.submitPlan(clients.first().client, origin.request)
        }
    }
}

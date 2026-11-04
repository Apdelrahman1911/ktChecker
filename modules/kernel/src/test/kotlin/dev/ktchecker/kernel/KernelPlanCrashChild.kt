package dev.ktchecker.kernel

import dev.ktchecker.contracts.ContractJson
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

/** Subprocess-only fixture: kill the JVM after its first SQL child insert but BEFORE acceptance. */
object KernelPlanCrashChild {
    @JvmStatic fun main(args: Array<String>) {
        val config = ContractJson.decodeFromString<KernelConfig>(Files.readString(Path.of(args[1])))
        val definition = ContractJson.decodeFromString<RequiredReviewPlan>(Files.readString(Path.of(args[2])))
        val registration = ContractJson.decodeFromString<ClientRegistration>(Files.readString(Path.of(args[3])))
        val authority = ReviewPlanAuthority { binding, request ->
            definition.takeIf { it.binding == binding && it.request == request }
        }
        KernelService.open(Path.of(args[0]), PlanTestProvider(), config, planAuthority = authority).use { service ->
            service.registerClient(registration)
            service.plans.afterSlotInsert = { count -> if (count == 1) {
                writeDurable(Path.of(args[4]), "uncommitted-prefix".toByteArray())
                CountDownLatch(1).await()
            } }
            service.submitPlan(registration.client, definition.request)
            error("This subprocess should have been killed before committing a parent plan")
        }
    }
}

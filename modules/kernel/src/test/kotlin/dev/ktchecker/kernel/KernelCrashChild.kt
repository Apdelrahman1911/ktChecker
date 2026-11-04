package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

/** Subprocess-only integration fixture: SIGKILL after a real durable running claim. */
object KernelCrashChild {
    @JvmStatic fun main(args: Array<String>) {
        val config = ContractJson.decodeFromString<KernelConfig>(Files.readString(Path.of(args[1])))
        val provider = object : AnalysisProvider {
            override val id = "synthetic-test"
            override val supportedRuleIds = setOf("test/no-defect")
            override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
                Files.writeString(Path.of(args[2]), "started")
                CountDownLatch(1).await()
                error("Process should have been killed")
            }
        }
        KernelService.open(Path.of(args[0]), provider, config).use { CountDownLatch(1).await() }
    }
}

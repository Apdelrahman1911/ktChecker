package dev.ktchecker.config

import dev.ktchecker.contracts.ClientIdentity
import dev.ktchecker.contracts.Digests
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Independent JVM writer used by the process-lock/CAS regression, not a CLI API. */
object ConfigProcessWorker {
    @JvmStatic fun main(args: Array<String>) {
        val root = Path.of(args[0])
        val barrier = Path.of(args[1])
        val index = args[2]
        val binding = ConfigBinding("owner", "workspace", "worktree", "lineage")
        val principal = ClientIdentity("owner", "explicit-owner-cli", "owner-session")
        val authority = HmacOwnerAuthority(ByteArray(32) { it.toByte() }, listOf(OwnerSessionRegistration(principal, setOf(binding))))
        val store = ConfigStore(root, RuleAliases(listOf(PolicyRule("ktc.demo.rule", setOf("upstream/demo", "old.rule"), implementationDigest = Digests.sha256("fixture-rule-1")),
            PolicyRule("ktc.demo.other", implementationDigest = Digests.sha256("fixture-rule-2")),
            PolicyRule("kotlin.compile", failureProtected = true, implementationDigest = Digests.sha256("fixture-compiler-1")))), authority)
        val expected = store.capture(binding).revisionToken
        val request = ConfigMutationRequest(binding, ConfigLayer.SHARED, expected, ConfigAction.AddSuppression(
            SuppressionSpec("process-$index", PolicySelector.Rule("ktc.demo.rule"), SuppressionScope.Project,
                SuppressionReason.ACCEPTED_RISK, "Independent process owner edit")), "process-request-$index")
        val permit = authority.issueForExplicitOwnerAction(principal, request)
        Files.createFile(barrier.resolve("ready-$index"))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!Files.exists(barrier.resolve("go"))) {
            check(System.nanoTime() < deadline) { "Process barrier timed out" }
            Thread.sleep(10)
        }
        try { store.mutate(request, permit); println("APPLIED") } catch (_: ConfigConflictException) { println("STALE") }
    }
}

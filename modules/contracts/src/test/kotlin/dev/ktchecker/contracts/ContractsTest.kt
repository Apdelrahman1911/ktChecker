package dev.ktchecker.contracts

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class ContractsTest {
    private fun input(): AnalysisInput = AnalysisInput(
        SnapshotManifest(Digests.sha256("tree"), "project", "worktree", listOf(SnapshotFile("src/雪.kt", Digests.sha256("val x = 1"), 9)), "owner"),
        "/isolated/input", AnalysisContext(modelDigest = Digests.sha256("model"), dependencyDigest = Digests.sha256("deps"),
            toolchainDigest = Digests.sha256("tools"), configurationDigest = Digests.sha256("policy"), compilationFiles = listOf("src/雪.kt")),
        listOf("ktc.test"),
    )

    @Test fun canonicalObjectOrderIsStableButArraysRetainMeaning() {
        assertEquals(Digests.canonical(ContractJson.parseToJsonElement("""{"b":{"x":2,"a":1},"a":[]}""")),
            Digests.canonical(ContractJson.parseToJsonElement("""{"a":[],"b":{"a":1,"x":2}}""")))
        assertNotEquals(Digests.canonical(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2)))),
            Digests.canonical(JsonArray(listOf(JsonPrimitive(2), JsonPrimitive(1)))))
    }

    @Test fun immutableContextInputsAllInvalidateTheConservativeKey() {
        val before = input()
        listOf(
            before.copy(snapshot = before.snapshot.copy(snapshotId = Digests.sha256("new bytes"))),
            before.copy(context = before.context.copy(modelDigest = Digests.sha256("new graph"))),
            before.copy(context = before.context.copy(dependencyDigest = Digests.sha256("new artifact"))),
            before.copy(context = before.context.copy(configurationDigest = Digests.sha256("new policy"))),
            before.copy(context = before.context.copy(toolchainDigest = Digests.sha256("new compiler"))),
            before.copy(context = before.context.copy(compilerArguments = listOf("-language-version", "2.4"))),
            before.copy(context = before.context.copy(compilationFiles = listOf("other.kt"))),
            before.copy(context = before.context.copy(scope = RuleContext("iosArm64", "iosMain", ":shared", "main"))),
            before.copy(requiredRuleIds = listOf("ktc.other")),
            before.copy(snapshot = before.snapshot.copy(unknownInputs = listOf("generator output"))),
        ).forEach { assertNotEquals(before.analysisKey(), it.analysisKey()) }
    }

    @Test fun completedStatusWithoutExactRequiredContextDoesNotPass() {
        val before = input()
        val row = Coverage("ktc.test", state = "passed", provenance = "executed", certificateId = Digests.sha256("certificate"))
        val result = ProviderResult("test", "complete", coverage = listOf(row))
        assertTrue(result.completeFor(before))
        assertFalse(result.copy(coverage = emptyList()).completeFor(before))
        assertFalse(result.copy(coverage = listOf(row, row)).completeFor(before))
        assertFalse(result.copy(coverage = listOf(row.copy(context = RuleContext("iosArm64")))).completeFor(before))
        assertFalse(result.copy(status = "failed").completeFor(before))
        assertFalse(result.copy(coverage = listOf(row.copy(provenance = "not_executed"))).completeFor(before))
    }

    @Test fun serializationPreservesScopeOmissionsAndUnicodeSourceIdentity() {
        val before = input().copy(snapshot = input().snapshot.copy(declaredRoots = listOf("project:"),
            scopeExclusions = listOf("build/generated: not captured"), unknownInputs = listOf("ksp")))
        val restored = ContractJson.decodeFromString<AnalysisInput>(ContractJson.encodeToString(before))
        assertEquals(before, restored)
        assertEquals(before.analysisKey(), restored.analysisKey())
        assertTrue(Digests.valid(before.analysisKey()))
    }

    @Test fun cancellationIsExplicitAndDoesNotProducePassingCoverage() {
        assertFailsWith<java.util.concurrent.CancellationException> { CancellationToken { true }.throwIfCancelled() }
        CancellationToken.NONE.throwIfCancelled()
        assertFalse(Coverage("ktc.test", state = "unavailable").successful())
        assertFalse(Coverage("ktc.test", state = "passed", certificateId = " ", provenance = "executed").successful())
    }
}

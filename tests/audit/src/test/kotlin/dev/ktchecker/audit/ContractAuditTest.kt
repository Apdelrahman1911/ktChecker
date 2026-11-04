package dev.ktchecker.audit

import dev.ktchecker.contracts.AnalysisContext
import dev.ktchecker.contracts.AnalysisInput
import dev.ktchecker.contracts.ArtifactIdentity
import dev.ktchecker.contracts.Coverage
import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.ProviderResult
import dev.ktchecker.contracts.RuleContext
import dev.ktchecker.contracts.SnapshotFile
import dev.ktchecker.contracts.SnapshotManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Independent contract tests; these do not execute a diagnostic provider or host. */
class ContractAuditTest {
    private val sourceDigest = Digests.sha256("fun answer() = 42\n")
    private val certificate = Digests.sha256("independent audit test certificate")

    private fun successfulCoverage(required: Boolean = true, certificateId: String? = certificate) = Coverage(
        ruleId = "ktc.audit.rule",
        state = "passed",
        required = required,
        provenance = "executed",
        certificateId = certificateId,
        implemented = true,
    )

    @Test
    fun optionalOnlyCoverageCannotCompleteARequiredReview() {
        val result = ProviderResult(
            providerId = "audit",
            status = "complete",
            coverage = listOf(successfulCoverage(required = false)),
        )
        assertFalse(result.complete(), "An empty required-coverage set must not pass by vacuous truth")
    }

    @Test
    fun absentOrBlankCertificatesCannotCertifyAbsence() {
        for (badCertificate in listOf(null, "", " ", "\n\t")) {
            val attempt = runCatching { successfulCoverage(certificateId = badCertificate).successful() }
            if (attempt.isSuccess) {
                assertFalse(attempt.getOrThrow(), "Invalid certificate was accepted: ${badCertificate?.length}")
            } else {
                assertTrue(attempt.exceptionOrNull() is IllegalArgumentException)
            }
        }
    }

    @Test
    fun partialFailureAndUnexecutedCellsCannotComplete() {
        val good = successfulCoverage()
        for (state in listOf("pending", "failed", "cancelled", "unsupported", "unknown", "timeout")) {
            val failed = good.copy(ruleId = "ktc.audit.second", state = state)
            assertFalse(ProviderResult("audit", "complete", coverage = listOf(good, failed)).complete())
        }
        for (provenance in listOf("not_executed", "source_checked", "mock_host", "")) {
            assertFalse(good.copy(provenance = provenance).successful())
        }
        assertFalse(ProviderResult("audit", "complete").complete())
        assertFalse(ProviderResult("audit", "failed", coverage = listOf(good)).complete())
    }

    @Test
    fun completionRequiresExactlyTheRequestedRulesInTheRequestedContext() {
        val input = AnalysisInput(
            SnapshotManifest(Digests.sha256("snapshot"), "project", "worktree", emptyList(), ownerId = "owner"),
            "/audit/immutable",
            requiredRuleIds = listOf("ktc.audit.rule", "ktc.audit.second"),
        )
        val first = successfulCoverage()
        val second = first.copy(ruleId = "ktc.audit.second")
        fun result(cells: List<Coverage>) = ProviderResult("audit", "complete", coverage = cells)
        assertTrue(result(listOf(first, second)).completeFor(input))
        for (incomplete in listOf(
            emptyList(),
            listOf(first),
            listOf(first, first),
            listOf(first, second.copy(required = false)),
            listOf(first, second.copy(context = second.context.copy(target = "iosArm64"))),
            listOf(first, second, first.copy(ruleId = "ktc.audit.unrequested")),
        )) {
            assertFalse(result(incomplete).completeFor(input), incomplete.toString())
        }
        assertFalse(result(listOf(first, second)).completeFor(input.copy(requiredRuleIds = emptyList())))
    }

    @Test
    fun contextTupleCannotAliasByMovingAPathDelimiter() {
        val first = runCatching { RuleContext(module = ":a/b", sourceSet = "c") }
        val second = runCatching { RuleContext(module = ":a", sourceSet = "b/c") }
        if (first.isSuccess && second.isSuccess) {
            assertNotEquals(
                first.getOrThrow().id,
                second.getOrThrow().id,
                "Distinct contexts must not share an identity used for coverage or deduplication",
            )
        } else {
            for (attempt in listOf(first, second).filter { it.isFailure }) {
                assertTrue(attempt.exceptionOrNull() is IllegalArgumentException)
            }
        }
    }

    @Test
    fun everyIntegratedAnalysisAssumptionChangesTheAnalysisKey() {
        val snapshot = SnapshotManifest(
            snapshotId = Digests.sha256("snapshot"),
            ownerId = "owner-a",
            workspaceId = "project-a",
            worktreeId = "worktree-a",
            files = listOf(SnapshotFile("src/Answer.kt", sourceDigest, 18)),
        )
        val context = AnalysisContext(
            compilerVersion = "2.4.21",
            modelDigest = Digests.sha256("model-a"),
            dependencyDigest = Digests.sha256("dependencies-a"),
            toolchainDigest = Digests.sha256("toolchain-a"),
            configurationDigest = Digests.sha256("configuration-a"),
        )
        val base = AnalysisInput(
            snapshot = snapshot,
            materializedRoot = "/audit/immutable-a",
            context = context,
            requiredRuleIds = listOf("ktc.audit.rule"),
        )
        val variants = listOf(
            base.copy(snapshot = snapshot.copy(snapshotId = Digests.sha256("snapshot-b"))),
            base.copy(snapshot = snapshot.copy(ownerId = "owner-b")),
            base.copy(snapshot = snapshot.copy(workspaceId = "project-b")),
            base.copy(snapshot = snapshot.copy(worktreeId = "worktree-b")),
            base.copy(context = context.copy(modelDigest = Digests.sha256("model-b"))),
            base.copy(context = context.copy(dependencyDigest = Digests.sha256("dependencies-b"))),
            base.copy(context = context.copy(toolchainDigest = Digests.sha256("toolchain-b"))),
            base.copy(context = context.copy(configurationDigest = Digests.sha256("configuration-b"))),
            base.copy(context = context.copy(compilerVersion = "another-version")),
            base.copy(context = context.copy(compilerArguments = listOf("-Werror"))),
            base.copy(context = context.copy(scope = context.scope.copy(target = "iosArm64"))),
            base.copy(context = context.copy(classpath = listOf(ArtifactIdentity("test:api:1", Digests.sha256("jar"), "/audit/api.jar")))),
            base.copy(context = context.copy(libraries = mapOf("library" to "1"))),
            base.copy(context = context.copy(policies = mapOf("rule" to "enabled"))),
            base.copy(requiredRuleIds = listOf("ktc.audit.rule", "ktc.audit.second")),
            base.copy(baselineSnapshotId = Digests.sha256("baseline")),
            base.copy(changedPaths = listOf("src/Answer.kt")),
        )
        for (variant in variants) assertNotEquals(base.analysisKey(), variant.analysisKey())
        assertEquals(variants.size + 1, (variants + base).map { it.analysisKey() }.toSet().size)
    }

    @Test
    fun mapInsertionOrderDoesNotChangeEquivalentAnalysisIdentity() {
        val snapshot = SnapshotManifest(
            Digests.sha256("snapshot"), "project", "worktree", emptyList(), ownerId = "owner",
        )
        val first = AnalysisInput(
            snapshot,
            "/audit/immutable",
            AnalysisContext(policies = linkedMapOf("a" to "1", "b" to "2")),
        )
        val second = first.copy(context = first.context.copy(policies = linkedMapOf("b" to "2", "a" to "1")))
        assertEquals(first.analysisKey(), second.analysisKey())
    }
}

package dev.ktchecker.compiler.live.model

import dev.ktchecker.contracts.*
import kotlin.test.*
import kotlinx.serialization.encodeToString

/** Exact result-digest helper used UNDER ARM by production. Synthetic DTOs are not an Accepted,
 * code0 token, producer output, coordinator approval or coverage qualification. */
class ProducedProviderResultIdentityTest {
    private fun result() = ProviderResult("compiler", "partial", coverage = listOf(
        Coverage("catalog.compiler.rule", RuleContext("", "main", ":", "main"), "unqualified", reason = "explicit gap")),
        reasons = listOf("explicit unqualified semantic boundary"), metrics = AnalysisMetrics(elapsedMillis = 7), providerVersion = "2.4.21")

    @Test fun canonicalRoundTripKeepsIdentityWithoutErasingHonestCoverageGaps() {
        val raw = result()
        val frozen = ContractJson.decodeFromString<ProviderResult>(ContractJson.encodeToString(raw))
        assertEquals(producedProviderResultDigest(raw), producedProviderResultDigest(frozen))
        assertFalse(raw.complete())
        assertEquals("unqualified", frozen.coverage.single().state)
        assertNull(frozen.coverage.single().certificateId)
    }

    @Test fun substitutionsOmissionsDiagnosticsAndMetricsAreNotTheAcceptedChildResult() {
        val raw = result()
        val variants = listOf(raw.copy(providerId = "coordinator"), raw.copy(providerVersion = "other"),
            raw.copy(status = "incomplete"), raw.copy(coverage = emptyList()), raw.copy(reasons = emptyList()),
            raw.copy(coverage = raw.coverage + raw.coverage), raw.copy(metrics = raw.metrics.copy(elapsedMillis = 8)),
            raw.copy(rawDiagnostics = listOf(RawDiagnostic("unaccounted failure"))),
            raw.copy(coverage = listOf(raw.coverage.single().copy(context = RuleContext("jvm", "main", ":", "main")))))
        variants.forEach { assertNotEquals(producedProviderResultDigest(raw), producedProviderResultDigest(it)) }
    }

    @Test fun storedDigestCannotBeChangedByLaterMutableProviderCollections() {
        val reasons = result().reasons.toMutableList()
        val rows = result().coverage.toMutableList()
        val raw = result().copy(reasons = reasons, coverage = rows)
        val stored = producedProviderResultDigest(raw)
        reasons.clear(); rows.clear()
        assertEquals(producedProviderResultDigest(result()), stored)
        assertNotEquals(stored, producedProviderResultDigest(raw))
    }
}

package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/** Synthetic kernel SPI only; supplements, and never modifies, the sealed five-method packet. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class KernelProducedProgressAssertionTest {
    @TempDir lateinit var temp: Path

    @Test fun assertingProgressCollectionCannotBeSwallowedIntoCleanPublicationWhileValidProgressStillResolves() {
        for (asserting in listOf(false, true)) {
            val name = if (asserting) "asserting-progress" else "valid-progress"
            val f = ProducedLane1665Fixture(temp.resolve("fixture-$name"))
            val accesses = AtomicInteger(); val providerCatches = AtomicInteger()
            val sentinel = AssertionError("synthetic provider-owned collection assertion")
            val provider = f.Provider { input, emit ->
                if (input.context.scope.compilation == "main") {
                    val valid = f.partial(input)
                    val observation = if (!asserting) valid else valid.copy(findings = object : AbstractList<Finding>() {
                        override val size: Int get() { accesses.incrementAndGet(); throw sentinel }
                        override fun get(index: Int): Finding { accesses.incrementAndGet(); throw sentinel }
                    })
                    try { emit(observation) }
                    catch (fault: AssertionError) {
                        // Legacy red path: provider swallows the exact pre-SQL assertion, then returns normal final raw.
                        assertSame(sentinel, fault); providerCatches.incrementAndGet()
                    }
                }
            }
            KernelService.open(temp.resolve("ledger-$name"), provider, ProducedLane1665Fixture.CONFIG,
                planAuthority = f, preparedInputObserver = f.observer, resultPolicy = f.policy).use { service ->
                val client = service.registerClient(ProducedLane1665Fixture.binding(name)); val definition = f.definition(client.binding, name)
                val plan = service.submitPlan(client.client, definition.request)
                val result = f.finished(service, client, plan.slots.first().reviewId)
                val producer = f.typedAttempts.single { it.execution.slotId == ProducedLane1665Fixture.PRODUCER }
                val expectedRaw = f.partial(producer.effective)
                val expectedFaults = if (asserting) listOf("progress_serialization_failed") else emptyList()
                assertEquals(if (asserting) 1 else 0, accesses.get(), "The negative control must really encounter the hostile collection")
                assertEquals(expectedRaw, result.result); assertEquals("incomplete", result.state)
                assertEquals(f.rules, expectedRaw.coverage.map { it.ruleId }); assertTrue(expectedRaw.coverage.all { !it.qualified })
                val expectedProgress = if (asserting) ProviderResult(ProducedLane1665Fixture.PROVIDER, "incomplete",
                    reasons = expectedFaults, providerVersion = provider.version) else expectedRaw
                service.ledger.read { c ->
                    assertNotNull(c.one("SELECT result_json,progress_faults_json,validation_json FROM work WHERE work_key=?", producer.workKey) { row ->
                        assertEquals(canonical(expectedRaw), row.getString(1), "Normalized progress must never replace final raw evidence")
                        assertEquals(expectedFaults, ContractJson.decodeFromString<List<String>>(row.getString(2)))
                        val validation = ContractJson.decodeFromString<List<String>>(row.getString(3))
                        assertEquals(asserting, "progress_serialization_failed" in validation)
                        assertFalse("progress_publication_failed" in validation)
                        true
                    })
                    val publication = assertNotNull(c.one("SELECT record_json FROM work_publications WHERE work_key=?", producer.workKey) {
                        ContractJson.decodeFromString<ProducedWorkPublication>(it.getString(1)) })
                    assertEquals(expectedFaults, publication.operationalFaults)
                    assertEquals(Digests.sha256(canonical(expectedRaw)), publication.rawResultDigest)
                    val progress = c.query("SELECT payload_json FROM work_progress WHERE work_key=?", producer.workKey) { it.getString(1) }
                    assertEquals(listOf(canonical(expectedProgress)), progress, "Serialization failure is normalized and retained, not silently discarded")
                }
                val progressEvent = f.events(service, client).single { it.kind == "progress" }
                assertEquals(expectedProgress, ContractJson.decodeFromString<ReviewStatus>(progressEvent.payload).result)
                // Correction2 normalizes AssertionError; catching is present to reproduce the old swallowed-fault path.
                assertEquals(0, providerCatches.get())
                f.await("Consumer resolution was never published: $name") { f.resolved(service, plan.slots.last().reviewId) != null }
                if (asserting) {
                    assertEquals("unavailable", f.resolved(service, plan.slots.last().reviewId))
                    assertTrue("produced_operational_publication_failed" in service.planStatus(client.client, plan.planId).slots.last().reasons)
                    assertEquals(0, f.resolutionCalls.getValue(definition.request.planRef).get())
                    assertEquals(1, provider.calls.get()); assertEquals(1, f.typedAttempts.size)
                    assertFalse(Files.exists(service.root.resolve("produced-output").resolve(plan.planId)))
                } else {
                    assertEquals("ready", f.resolved(service, plan.slots.last().reviewId))
                    val consumerResult = f.finished(service, client, plan.slots.last().reviewId)
                    val consumer = f.typedAttempts.single { it.execution.slotId == ProducedLane1665Fixture.CONSUMER }
                    assertEquals(f.partial(consumer.effective), consumerResult.result)
                    assertEquals(1, f.resolutionCalls.getValue(definition.request.planRef).get())
                    assertEquals(2, provider.calls.get()); assertEquals(2, f.typedAttempts.size)
                }
                val retained = service.planStatus(client.client, plan.planId)
                assertEquals(plan.slots.map { it.reviewId }, retained.slots.map { it.reviewId })
                retained.slots.forEach { assertEquals(f.rules, it.requiredRuleIds) }
                assertEquals("incomplete", retained.state); assertTrue("produced_plan_preview_unqualified" in retained.reasons)
                service.ledger.read { c ->
                    assertEquals(2L, c.count("SELECT COUNT(*) FROM reviews WHERE kind='produced' AND work_key IS NULL"))
                    assertEquals(listOf(1L, 2L), c.query("SELECT sequence FROM reviews ORDER BY sequence") { it.getLong(1) })
                    assertEquals(if (asserting) 1L else 2L, c.count("SELECT COUNT(*) FROM work"))
                }
            }
        }
    }
}

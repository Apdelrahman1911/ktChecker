package dev.ktchecker.providers.library

import dev.ktchecker.contracts.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * SYNTHETIC callback/selection controls only. Existing fixture bytes, artifacts, facts and owner
 * receipts are fabricated test inputs, never real compiler/host-exploit or qualification evidence.
 * No source grammar or applicability is widened; the caller's entry vector stays the authority
 * request, dispatch, evidence and complete required-coverage vector despite a callback mutation.
 */
class RunBlockingRuleSelectionRegressionTest {
    private val unsupported = listOf(
        "ktc.coroutines.flowon-job-context",
        "ktc.serialization.unavailable-serializer",
        "ktc.ktor.owned-client-lifecycle-policy",
    )

    private fun noncertifying(result: ProviderResult) {
        assertEquals("partial", result.status)
        assertFalse(result.complete())
        assertTrue(result.coverage.all {
            it.required && !it.successful() && it.certificateId == null &&
                !it.implemented && !it.tested && !it.qualified
        })
    }

    private fun noReceipts(receipts: LibraryRuleFixtures.Receipts) {
        assertEquals(0, receipts.snapshots)
        assertEquals(0, receipts.extractions)
        assertEquals(0, receipts.artifacts)
        assertTrue(receipts.ownedSymbols.isEmpty())
        assertEquals(0, receipts.policies)
        assertEquals(0, receipts.reads)
    }

    private fun checkMutation(original: List<String>, replacement: List<String>,
                              mutate: (MutableList<String>) -> Unit) {
        assertTrue(RUNBLOCKING_IN_SUSPEND in original)
        assertTrue(RUNBLOCKING_IN_SUSPEND !in replacement)
        assertEquals(original.size, original.distinct().size)
        assertEquals(replacement.size, replacement.distinct().size)
        LibraryRuleFixtures().use { f ->
            val model = f.model()
            val selected = f.selection(model)
            // Independent one-shot permit for each side: only the entry vector may be admitted.
            for (reviewedRules in listOf(original, replacement)) {
                val reviewed = RunBlockingInSuspendProvider(model, selected)
                    .authorizationRequest(f.input(model, reviewedRules))
                val receipts = LibraryRuleFixtures.Receipts()
                val permit = f.syntheticPermit(model, reviewed, receipts)
                val exact = ExactLibraryFactAuthority(permit)
                var observedRequest: LibraryFactAuthorizationRequest? = null
                val provider = RunBlockingInSuspendProvider(model, selected, LibraryFactAuthority { actual ->
                    observedRequest = actual
                    exact.authorize(actual)
                })
                val callerRules = original.toMutableList()
                val input = f.input(model, callerRules)
                var checks = 0
                val result = provider.analyze(input, CancellationToken {
                    if (checks++ == 0) mutate(callerRules)
                    false
                })
                assertTrue(checks > 0)
                assertEquals(replacement, callerRules)
                assertSame(callerRules, input.requiredRuleIds) // The DTO deliberately retains this mutable test input.
                assertEquals(original, observedRequest?.input?.requiredRuleIds)
                assertEquals(original, result.coverage.map { it.ruleId })
                assertTrue(result.coverage.all { it.context == input.context.scope })
                val others = result.coverage.filter { it.ruleId != RUNBLOCKING_IN_SUSPEND }
                assertEquals(original.size - 1, others.size)
                assertTrue(others.all { it.state == "unsupported" && it.provenance == "not_executed" })
                noncertifying(result)
                if (reviewedRules == original) {
                    assertEquals(listOf(RUNBLOCKING_IN_SUSPEND), result.findings.map { it.ruleId })
                    assertEquals(reviewed.digest(), result.findings.single().evidence
                        .single { it.kind == "owner-extraction-receipt" }.digest)
                    val row = result.coverage.single { it.ruleId == RUNBLOCKING_IN_SUSPEND }
                    assertEquals("findings", row.state)
                    assertEquals("executed", row.provenance) // Synthetic in this test, never catalog-stage credit.
                    assertEquals(1, receipts.snapshots)
                    assertEquals(1, receipts.extractions)
                    assertEquals(1, receipts.reads)
                } else {
                    // Matching the callback's replacement vector must not authorize the entry dispatch.
                    assertTrue(result.findings.isEmpty())
                    assertEquals(listOf("library_extraction_not_authorized"), result.reasons)
                    val row = result.coverage.single { it.ruleId == RUNBLOCKING_IN_SUSPEND }
                    assertEquals("unavailable", row.state)
                    assertEquals("not_executed", row.provenance)
                    noReceipts(receipts)
                }
            }
        }
    }

    @Test fun same_length_replacement_cannot_split_exact_authority_from_dispatch_or_evidence() {
        val original = listOf(RUNBLOCKING_IN_SUSPEND) + unsupported.take(2)
        val replacement = listOf(unsupported.last()) + unsupported.take(2)
        checkMutation(original, replacement) { rules -> rules[0] = unsupported.last() }
    }

    @Test fun clear_and_add_mutation_cannot_revoke_or_replace_the_detached_entry_selection() {
        val original = listOf(unsupported[0], RUNBLOCKING_IN_SUSPEND, unsupported[1])
        val replacement = listOf(unsupported.last())
        checkMutation(original, replacement) { rules ->
            rules.clear()
            rules.addAll(replacement)
        }
    }

    @Test fun complete_current_scope_remains_visible_when_a_callback_clears_the_caller_vector() {
        // Successor vector only: the retained1663 source resource and its prior oracle stay unchanged.
        val bytes = requireNotNull(javaClass.getResourceAsStream("/coroutine-refinement/retained-rule-ids.txt"))
            .use { it.readBytes() }
        assertEquals("sha256:4c00ec83059325b8ee2fac672e6c53948746bda6920d900a9d1942ce1b03520d", Digests.sha256(bytes))
        val old = bytes.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).toList()
        val added = listOf("ktc.compiler.common.error_suppression", "ktc.compiler.common.inapplicable_operator_modifier")
        assertEquals(1663, old.size)
        assertEquals(old.distinct().sorted(), old)
        assertTrue(added.none { it in old })
        val original = (old + added).sorted()
        assertEquals(1665, original.size)
        assertEquals("sha256:121660fc2a5aed084688d0c549f9e1bece2ec514e9ace4748211b55fa87cc1c5",
            Digests.sha256(Digests.canonical(JsonArray(original.map(::JsonPrimitive)))))
        assertEquals(old, original - added.toSet())
        val replacement = listOf(unsupported.last())
        checkMutation(original, replacement) { rules ->
            rules.clear()
            rules.addAll(replacement)
        }
    }

    @Test fun ordinary_matching_vector_still_yields_only_the_unqualified_runblocking_observation() {
        LibraryRuleFixtures().use { f ->
            val model = f.model()
            val rules = listOf(unsupported[0], RUNBLOCKING_IN_SUSPEND) + unsupported.drop(1)
            val receipts = LibraryRuleFixtures.Receipts()
            val result = f.analyze(model, f.selection(model), f.input(model, rules), receipts)
            assertEquals(rules, result.coverage.map { it.ruleId })
            assertEquals(listOf(RUNBLOCKING_IN_SUSPEND), result.findings.map { it.ruleId })
            assertTrue(result.coverage.filter { it.ruleId != RUNBLOCKING_IN_SUSPEND }.all { it.state == "unsupported" })
            assertEquals(1, receipts.extractions)
            assertEquals(1, receipts.reads)
            noncertifying(result)
        }
    }

    @Test fun a_callback_cannot_enable_runblocking_when_the_entry_vector_is_unsupported() {
        LibraryRuleFixtures().use { f ->
            val model = f.model()
            val selected = f.selection(model)
            val reviewed = RunBlockingInSuspendProvider(model, selected)
                .authorizationRequest(f.input(model, listOf(RUNBLOCKING_IN_SUSPEND)))
            val receipts = LibraryRuleFixtures.Receipts()
            val exact = ExactLibraryFactAuthority(f.syntheticPermit(model, reviewed, receipts))
            var authorizations = 0
            val provider = RunBlockingInSuspendProvider(model, selected, LibraryFactAuthority {
                authorizations++
                exact.authorize(it)
            })
            val original = listOf(unsupported.first())
            val callerRules = original.toMutableList()
            var checks = 0
            val result = provider.analyze(f.input(model, callerRules), CancellationToken {
                if (checks++ == 0) callerRules[0] = RUNBLOCKING_IN_SUSPEND
                false
            })
            assertEquals(listOf(RUNBLOCKING_IN_SUSPEND), callerRules)
            assertEquals(original, result.coverage.map { it.ruleId })
            assertTrue(result.coverage.all { it.state == "unsupported" })
            assertTrue(result.findings.isEmpty())
            assertEquals(listOf("library_requested_rules_not_implemented"), result.reasons)
            assertEquals(0, authorizations)
            noReceipts(receipts)
            noncertifying(result)
        }
    }

    @Test fun ordinary_unsupported_vector_never_authorizes_or_retires_its_obligations() {
        var authorizations = 0
        val input = AnalysisInput(SnapshotManifest("absent", "absent", "absent", emptyList()),
            "/not-used", requiredRuleIds = unsupported)
        val provider = RunBlockingInSuspendProvider(authority = LibraryFactAuthority { authorizations++; null })
        val result = provider.analyze(input)
        assertEquals(unsupported, result.coverage.map { it.ruleId })
        assertTrue(result.coverage.all { it.state == "unsupported" && it.provenance == "not_executed" })
        assertTrue(result.findings.isEmpty())
        assertEquals(0, authorizations)
        noncertifying(result)
    }
}

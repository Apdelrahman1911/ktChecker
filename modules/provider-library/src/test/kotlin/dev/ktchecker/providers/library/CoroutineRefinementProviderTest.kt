package dev.ktchecker.providers.library

import dev.ktchecker.contracts.*
import dev.ktchecker.semantic.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Authored SYNTHETIC source/algorithm/boundary controls. Not genuine-engine integration evidence. */
class CoroutineRefinementProviderTest {
    private fun fixture(caseId: String = "sleep_direct_suspend", block: (CoroutineRefinementControls) -> Unit) =
        LibraryRuleFixtures(CoroutineRefinementControls.source(caseId)).use { block(CoroutineRefinementControls(it)) }

    private fun textFixture(text: String, block: (CoroutineRefinementControls) -> Unit) =
        LibraryRuleFixtures(text).use { block(CoroutineRefinementControls(it)) }

    private fun noncertifying(result: ProviderResult) {
        assertTrue(result.status in setOf("partial", "cancelled"))
        assertFalse(result.complete())
        assertTrue(result.coverage.all { !it.successful() && it.certificateId == null && !it.implemented && !it.tested && !it.qualified })
        assertTrue(result.rawDiagnostics.isEmpty()) // There is deliberately no raw-provider promotion path.
        assertTrue(result.findings.all { it.upstreamId == null && it.providerId == COROUTINE_REFINEMENT_PROVIDER_ID })
    }

    private fun anchor(caseId: String, finding: Finding, path: String) {
        val expected = CoroutineRefinementControls.sealedCases.single { it.getValue("id").jsonPrimitive.content == caseId }
            .getValue("canonical").jsonObject.getValue("anchors").jsonArray.single().jsonObject
        val actual = finding.location
        assertEquals(path, actual.path) // Synthetic main-model relocation, NOT original source membership.
        assertEquals(expected.getValue("line").jsonPrimitive.int, actual.line)
        assertEquals(expected.getValue("column").jsonPrimitive.int, actual.column)
        assertEquals(expected.getValue("endLine").jsonPrimitive.int, actual.endLine)
        assertEquals(expected.getValue("endColumn").jsonPrimitive.int, actual.endColumn)
    }

    @Test fun original_nineteen_case_bytes_and_unexecuted_oracles_are_retained_without_rewriting() {
        assertEquals("sha256:a3003b4ee85c77c6fc63c0b212ed86ae5fbfff9deb1f0eaa2e37b0b9bf3b5391",
            Digests.sha256(CoroutineRefinementControls.sealedBytes))
        val cases = CoroutineRefinementControls.sealedCases
        assertEquals(19, cases.size)
        assertEquals(19, cases.map { it.getValue("id").jsonPrimitive.content }.toSet().size)
        assertEquals(listOf(1, 1, 0, 0, 0, 1, 1, 1, 0, 1, 1, 0, 0, 0, 1, 1, 0, 1, 0),
            cases.map { it.getValue("rawExpectedFindingCount").jsonPrimitive.int })
        assertTrue(cases.all { it.getValue("executionStatus").jsonPrimitive.content == "NOT_COMPILED_NOT_EXECUTED_NOT_ADMITTED" })
        assertTrue(cases.all { it.getValue("canonical").jsonObject.getValue("actualCanonicalFindingCount") == JsonNull })
        assertEquals(setOf(SLEEP_IN_COROUTINE, RESULT_SWALLOWING_CANCELLATION,
            "ktc.coroutines.swallowed-cancellation", "ktc.librarytest.test-coroutine-ownership-policy"),
            cases.map { it.getValue("canonical").jsonObject.getValue("ruleId").jsonPrimitive.content }.toSet())
    }

    @Test fun genuine_selected_sleep_and_import_alias_have_exact_noncertifying_canonical_anchors() {
        listOf("sleep_direct_suspend", "sleep_imported_static_alias").forEach { caseId -> fixture(caseId) { c ->
            val result = c.analyze(c.sleep(alias = caseId == "sleep_imported_static_alias"))
            noncertifying(result)
            val finding = result.findings.single()
            assertEquals(SLEEP_IN_COROUTINE, finding.ruleId)
            assertEquals("medium", finding.certainty)
            assertEquals(c.f.source.digest, finding.location.fileDigest)
            anchor(caseId, finding, c.f.source.path)
        } }
    }

    @Test fun same_sleep_bytes_change_outcome_only_with_an_exact_owner_policy_receipt() = fixture { c ->
        assertEquals(CoroutineRefinementControls.source("sleep_direct_suspend"),
            CoroutineRefinementControls.source("sleep_approved_blocking_bridge_policy"))
        val selected = c.sleep()
        val exempt = c.sleep(exempt = true)
        assertEquals(selected.shards, exempt.shards)
        assertEquals(1, c.analyze(selected).findings.size)
        val result = c.analyze(exempt)
        assertTrue(result.findings.isEmpty()); noncertifying(result)
        val plainRequest = CoroutineRefinementProvider(c.model, selected).authorizationRequest(c.input())
        val plainPermit = c.permit(plainRequest)
        val rejected = CoroutineRefinementProvider(c.model, exempt, ExactCoroutineRefinementAuthority(plainPermit)).analyze(c.input())
        assertEquals("coroutine_refinement_extraction_not_authorized", rejected.reasons.single())
        assertTrue(rejected.findings.isEmpty())
    }

    @Test fun ordinary_synchronous_context_is_not_reinterpreted_as_coroutine_context() =
        fixture("sleep_ordinary_synchronous_context") { c ->
            val result = c.analyze(c.sleep(suspended = false))
            assertTrue(result.findings.isEmpty())
            assertContains(result.reasons, "coroutine_refinement_nonsuspend_owner_not_reinterpreted")
            noncertifying(result)
        }

    @Test fun local_thread_homonym_is_not_a_jdk_call_and_cannot_be_enrolled_as_one() =
        fixture("sleep_local_thread_homonym") { c ->
            val result = c.analyze(c.sleepHomonym())
            assertTrue(result.findings.isEmpty()); noncertifying(result)
            val rejected = CoroutineRefinementProvider(c.model, c.sleepHomonym(selectAsJdk = true)).analyze(c.input())
            assertEquals("coroutine_refinement_selected_signature_unsupported", rejected.reasons.single())
            assertTrue(rejected.findings.isEmpty())
        }

    @Test fun genuine_delay_alternative_is_never_a_sleep_alias() = fixture("sleep_real_delay_alternative") { c ->
        val result = c.analyze(c.sleep(role = CoroutineCallableRole.DELAY_LONG))
        assertTrue(result.findings.isEmpty()); noncertifying(result)
    }

    @Test fun genuine_constant_result_recovery_is_not_equated_with_raw_run_catching_presence() =
        fixture("result_terminal_consumes_cancellation") { c ->
            val result = c.analyze(c.result())
            val finding = result.findings.single()
            assertEquals(RESULT_SWALLOWING_CANCELLATION, finding.ruleId)
            assertTrue(finding.evidence.any { it.kind == "resolved-cancellable-operation" })
            assertTrue(finding.evidence.any { it.kind == "resolved-result-consumer" })
            anchor("result_terminal_consumes_cancellation", finding, c.f.source.path)
            noncertifying(result)
        }

    @Test fun get_or_throw_preserves_the_exception_despite_the_same_raw_positive_oracle() =
        fixture("result_get_or_throw_preserves_cancellation") { c ->
            val result = c.analyze(c.result(CoroutineCallableRole.GET_OR_THROW))
            assertTrue(result.findings.isEmpty()); noncertifying(result)
            val cases = CoroutineRefinementControls.sealedCases.filter { it.getValue("id").jsonPrimitive.content in
                setOf("result_terminal_consumes_cancellation", "result_get_or_throw_preserves_cancellation") }
            assertEquals(listOf(1, 1), cases.map { it.getValue("rawExpectedFindingCount").jsonPrimitive.int })
        }

    @Test fun source_defined_preserving_run_catching_homonym_does_not_gain_stdlib_semantics() =
        fixture("result_local_preserving_homonym") { c ->
            val result = c.analyze(c.result(terminal = null, homonym = true))
            assertTrue(result.findings.isEmpty()); noncertifying(result)
        }

    @Test fun opaque_result_escape_stays_unknown_not_a_clean_negative() = fixture("result_opaque_escape_abstains") { c ->
        val result = c.analyze(c.result(terminal = null))
        assertTrue(result.findings.isEmpty())
        assertEquals("unavailable", result.coverage.single { it.ruleId == RESULT_SWALLOWING_CANCELLATION }.state)
        assertContains(result.reasons, "coroutine_refinement_result_escape_consumption_unknown")
        noncertifying(result)
    }

    @Test fun raw_dto_shape_even_after_real_source_validation_is_not_a_live_authority() = fixture { c ->
        val selected = c.sleep()
        val bundle = SemanticFactsValidation.assemble(selected.request, selected.shards, 0,
            sourceBytes = { c.f.sourceBytes.copyOf() })
        assertTrue(bundle.completeForSupportedVocabulary)
        assertFalse(bundle.tupleQualified)
        val result = CoroutineRefinementProvider(c.model, selected).analyze(c.input())
        assertEquals("coroutine_refinement_extraction_not_authorized", result.reasons.single())
        assertTrue(result.findings.isEmpty()); assertTrue(c.trace.isEmpty()); noncertifying(result)
    }

    @Test fun serialized_authorization_metadata_cannot_recreate_the_nonserialized_permit() = fixture { c ->
        val selected = c.sleep()
        val metadata = CoroutineRefinementProvider(c.model, selected).authorizationRequest(c.input())
        val decoded = ContractJson.decodeFromString<CoroutineRefinementAuthorizationRequest>(ContractJson.encodeToString(metadata))
        assertEquals(metadata, decoded)
        val result = CoroutineRefinementProvider(c.model, selected).analyze(decoded.input)
        assertTrue(result.findings.isEmpty()); noncertifying(result)
    }

    @Test fun every_receipt_precedes_the_first_source_reader_and_the_jdk_is_separately_checked() = fixture { c ->
        val result = c.analyze(c.sleep())
        assertEquals(1, result.findings.size)
        assertEquals(listOf("snapshot", "extraction", "artifact", "artifact", "artifact", "artifact", "artifact",
            "runtime", "ownership", "policy", "source"), c.trace)
        noncertifying(result)
    }

    @Test fun failures_at_each_mandatory_receipt_abstain_without_private_exception_text() = fixture { c ->
        listOf("snapshot", "extraction", "artifact", "runtime", "ownership", "policy", "source").forEach { stage ->
            c.trace.clear()
            val result = c.analyze(c.sleep(), failAt = stage)
            assertTrue(result.findings.isEmpty())
            assertTrue(result.reasons.none { "confidential" in it })
            if (stage != "source") assertFalse("source" in c.trace)
            noncertifying(result)
        }
    }

    @Test fun a_permit_is_consumed_once_even_for_the_same_exact_request() = fixture { c ->
        val selected = c.sleep()
        val request = CoroutineRefinementProvider(c.model, selected).authorizationRequest(c.input())
        val provider = CoroutineRefinementProvider(c.model, selected, ExactCoroutineRefinementAuthority(c.permit(request)))
        assertEquals(1, provider.analyze(c.input()).findings.size)
        val readCount = c.trace.count { it == "source" }
        val replay = provider.analyze(c.input())
        assertTrue(replay.findings.isEmpty())
        assertEquals("coroutine_refinement_authorization_mismatch_or_replay", replay.reasons.single())
        assertEquals(readCount, c.trace.count { it == "source" })
        noncertifying(replay)
    }

    @Test fun callback_mutation_cannot_split_authorized_and_dispatched_rule_vectors() = fixture { c ->
        // SYNTHETIC callback control, not an assertion that the owner kernel exposes mutable input.
        for (authorizedRule in listOf(RESULT_SWALLOWING_CANCELLATION, SLEEP_IN_COROUTINE)) {
            c.trace.clear()
            val selected = c.sleep()
            val request = CoroutineRefinementProvider(c.model, selected)
                .authorizationRequest(c.input(listOf(authorizedRule)))
            val provider = CoroutineRefinementProvider(c.model, selected,
                ExactCoroutineRefinementAuthority(c.permit(request)))
            val callerRules = mutableListOf(SLEEP_IN_COROUTINE)
            val input = c.input(callerRules)
            var checks = 0
            val cancellation = CancellationToken {
                if (checks++ == 0) callerRules[0] = RESULT_SWALLOWING_CANCELLATION
                false
            }
            val result = provider.analyze(input, cancellation)
            assertEquals(listOf(RESULT_SWALLOWING_CANCELLATION), callerRules)
            assertEquals(listOf(SLEEP_IN_COROUTINE), result.coverage.map { it.ruleId })
            if (authorizedRule == SLEEP_IN_COROUTINE) {
                assertEquals(listOf(SLEEP_IN_COROUTINE), result.findings.map { it.ruleId })
                assertTrue("source" in c.trace)
            } else {
                assertTrue(result.findings.isEmpty())
                assertEquals(listOf("coroutine_refinement_extraction_not_authorized"), result.reasons)
                assertTrue(c.trace.isEmpty())
            }
            noncertifying(result)
        }
    }

    @Test fun runtime_source_configuration_and_ordered_dependency_identity_drift_fail_closed() = fixture { c ->
        val selected = c.sleep()
        val provider = CoroutineRefinementProvider(c.model, selected)
        val input = c.input()
        val variants = listOf(
            input.copy(context = input.context.copy(configurationDigest = Digests.sha256("different-owner-config"))),
            input.copy(context = input.context.copy(modelDigest = Digests.sha256("different-model"))),
            input.copy(context = input.context.copy(classpath = input.context.classpath.reversed())),
            input.copy(context = input.context.copy(compilationFiles = emptyList())),
            input.copy(materializedRoot = input.materializedRoot + "/different"),
            input.copy(context = input.context.copy(scope = input.context.scope.copy(target = "iosArm64"))),
        )
        variants.forEach { altered ->
            val result = provider.analyze(altered)
            assertTrue(result.findings.isEmpty()); noncertifying(result)
            assertEquals(altered.context.scope, result.coverage.first().context)
        }
        val request = provider.authorizationRequest(input)
        assertFailsWith<CoroutineRefinementProblem> { c.permit(request.copy(runtimeArtifacts = emptyList())) }
        assertTrue(c.trace.isEmpty())
    }

    @Test fun missing_model_fact_selection_failed_compiler_unknown_facts_and_missing_sources_are_not_clean() = fixture { c ->
        val input = c.input()
        val selected = c.sleep()
        val providers = listOf(CoroutineRefinementProvider(), CoroutineRefinementProvider(c.model),
            CoroutineRefinementProvider(c.model, selected.copy(compilerExitCode = 1)),
            CoroutineRefinementProvider(c.model, selected.copy(shards = emptyList())),
            CoroutineRefinementProvider(c.model, selected.copy(shards = selected.shards.map { it.copy(
                unknown = listOf(UnknownSemanticFact("call", "SYNTHETIC-unresolved", it.calls.first().source))) })))
        providers.forEach { provider ->
            val result = provider.analyze(input)
            assertTrue(result.findings.isEmpty()); noncertifying(result)
            assertTrue(result.coverage.all { it.state == "unavailable" })
        }
        assertTrue(c.trace.isEmpty())
    }

    @Test fun immutable_source_bytes_must_match_the_registered_snapshot_after_all_receipts() = fixture { c ->
        val result = c.analyze(c.sleep(), bytes = { c.f.sourceBytes.copyOf().also { it[0] = 'X'.code.toByte() } })
        assertTrue(result.findings.isEmpty())
        assertContains(result.reasons, "coroutine_refinement_snapshot_facts_unavailable")
        noncertifying(result)
    }

    @Test fun changed_fact_data_cannot_reuse_a_previously_reviewed_request_digest() = fixture { c ->
        val original = c.sleep()
        val calls = original.shards.single().calls.toMutableList()
        val selected = original.copy(shards = listOf(original.shards.single().copy(calls = calls)))
        val provider = CoroutineRefinementProvider(c.model, selected)
        val request = provider.authorizationRequest(c.input())
        val authority = ExactCoroutineRefinementAuthority(c.permit(request))
        calls[0] = calls[0].copy(containingSymbolId = null)
        val result = CoroutineRefinementProvider(c.model, selected, authority).analyze(c.input())
        assertTrue(result.findings.isEmpty()); assertTrue(c.trace.isEmpty()); noncertifying(result)
    }

    @Test fun authorization_collections_are_recursively_detached_and_immutable() = fixture { c ->
        val selected = c.sleep()
        val request = CoroutineRefinementProvider(c.model, selected).authorizationRequest(c.input())
        assertFailsWith<UnsupportedOperationException> { (request.applicability.callables as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (request.semanticRequest.compilerArtifacts as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (request.runtimeArtifacts as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (request.input.requiredRuleIds as MutableList<*>).clear() }
    }

    @Test fun unimplemented_catalog_ids_and_each_input_context_remain_explicit_obligations() = fixture { c ->
        val rules = listOf(SLEEP_IN_COROUTINE, RESULT_SWALLOWING_CANCELLATION, "ktc.coroutines.swallowed-cancellation",
            "ktc.coroutines.suspending-finally-cleanup", "ktc.librarytest.test-coroutine-ownership-policy",
            "ktc.resources.owned_autocloseable_exit")
        val input = c.input(rules)
        val result = c.analyze(c.sleep(), input)
        assertEquals(rules, result.coverage.map { it.ruleId })
        assertTrue(result.coverage.drop(2).all { it.state == "unsupported" && it.context == input.context.scope })
        assertEquals(setOf(SLEEP_IN_COROUTINE, RESULT_SWALLOWING_CANCELLATION), CoroutineRefinementProvider().supportedRuleIds)
        noncertifying(result)
        val other = input.copy(context = input.context.copy(scope = input.context.scope.copy(sourceSet = "test")))
        val rejected = CoroutineRefinementProvider(c.model, c.sleep()).analyze(other)
        assertEquals(rules, rejected.coverage.map { it.ruleId })
        assertTrue(rejected.coverage.all { it.context == other.context.scope })
        assertTrue(rejected.coverage.drop(2).all { it.state == "unsupported" })
    }

    @Test fun no_supported_request_never_reads_sources_or_needs_a_fake_available_model() {
        val input = AnalysisInput(SnapshotManifest("absent", "absent", "absent", emptyList()), "/not-used",
            requiredRuleIds = listOf("ktc.coroutines.swallowed-cancellation", "ktc.librarytest.test-coroutine-ownership-policy"))
        val result = CoroutineRefinementProvider().analyze(input)
        assertEquals(input.requiredRuleIds, result.coverage.map { it.ruleId })
        assertTrue(result.coverage.all { it.state == "unsupported" }); noncertifying(result)
    }

    @Test fun full_retained_1663_rule_vector_never_shrinks_to_the_two_supported_slices() = fixture { c ->
        val bytes = requireNotNull(javaClass.getResourceAsStream("/coroutine-refinement/retained-rule-ids.txt")).use { it.readBytes() }
        assertEquals("sha256:4c00ec83059325b8ee2fac672e6c53948746bda6920d900a9d1942ce1b03520d", Digests.sha256(bytes))
        val rules = bytes.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).toList()
        assertEquals(1663, rules.size)
        assertEquals(rules.distinct().sorted(), rules)
        val input = c.input(rules)
        val result = c.analyze(c.sleep(), input)
        assertEquals(rules, result.coverage.map { it.ruleId })
        assertEquals(1661, result.coverage.count { it.state == "unsupported" })
        assertFalse(result.completeFor(input)); noncertifying(result)
    }

    /** Explicit successor vector, not a rewrite or reinterpretation of the frozen1663 control above. */
    @Test fun full_current_1665_successor_keeps_both_new_unqualified_rows_and_all_unsupported_obligations() = fixture { c ->
        val oldBytes = requireNotNull(javaClass.getResourceAsStream("/coroutine-refinement/retained-rule-ids.txt")).use { it.readBytes() }
        assertEquals("sha256:4c00ec83059325b8ee2fac672e6c53948746bda6920d900a9d1942ce1b03520d", Digests.sha256(oldBytes))
        val old = oldBytes.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).toList()
        val added = listOf("ktc.compiler.common.error_suppression", "ktc.compiler.common.inapplicable_operator_modifier")
        assertEquals(1663, old.size); assertTrue(added.none { it in old })
        val rules = (old + added).sorted()
        assertEquals(1665, rules.size); assertEquals(rules.distinct().sorted(), rules)
        assertEquals("sha256:121660fc2a5aed084688d0c549f9e1bece2ec514e9ace4748211b55fa87cc1c5",
            Digests.sha256(Digests.canonical(JsonArray(rules.map(::JsonPrimitive)))))
        assertEquals(old, rules - added.toSet())
        val input = c.input(rules); val result = c.analyze(c.sleep(), input)
        assertEquals(rules, result.coverage.map { it.ruleId })
        assertEquals(1663, result.coverage.count { it.state == "unsupported" })
        added.forEach { rule ->
            val row = result.coverage.single { it.ruleId == rule }
            assertEquals("unsupported", row.state); assertTrue(row.required)
            assertFalse(row.tested); assertFalse(row.qualified); assertNull(row.certificateId)
        }
        assertFalse(result.completeFor(input)); noncertifying(result)
    }

    @Test fun an_immediate_captured_suspend_lambda_is_not_an_invented_lexical_ancestor() {
        textFixture("val body: suspend () -> Unit = { java.lang.Thread.sleep(1L) }\n") { c ->
            val span = c.f.span("{ java.lang.Thread.sleep(1L) }")
            val original = c.lambda("body", span, CoroutineRefinementControls.plain("kotlin.Unit"))
            val owner = original.copy(flags = original.flags + "suspend").let { it.copy(id = resolvedSymbolIdentity(it)) }
            val target = c.target(CoroutineCallableRole.THREAD_SLEEP_LONG)
            val call = c.call(target, owner, c.f.span("java.lang.Thread.sleep(1L)"), listOf(c.f.span("1L")))
            val selected = c.selection(listOf(owner), listOf(target), listOf(call),
                listOf(c.selected(CoroutineCallableRole.THREAD_SLEEP_LONG, target)))
            val result = c.analyze(selected)
            assertEquals(owner.id, result.findings.single().symbolId); noncertifying(result)
        }
    }

    @Test fun a_callable_reference_is_not_a_sleep_invocation() = fixture { c ->
        val selected = c.sleep().let { it.copy(shards = it.shards.map { shard ->
            shard.copy(calls = shard.calls.map { call -> call.copy(kind = "callable_reference", arguments = emptyList()) })
        }) }
        val result = c.analyze(selected)
        assertTrue(result.findings.isEmpty()); noncertifying(result)
    }

    @Test fun contradictory_utf16_byte_coordinates_are_not_repaired_from_spellings() = fixture { c ->
        val selected = c.sleep().let { it.copy(shards = it.shards.map { shard ->
            shard.copy(calls = shard.calls.map { call -> call.copy(source = call.source.copy(startColumn = call.source.startColumn + 1)) })
        }) }
        val result = c.analyze(selected)
        assertTrue(result.findings.isEmpty())
        assertContains(result.reasons, "coroutine_refinement_snapshot_facts_unavailable")
        noncertifying(result)
    }

    @Test fun cancelled_and_oversized_envelopes_cannot_produce_findings_or_unbounded_rows() = fixture { c ->
        val provider = CoroutineRefinementProvider(c.model, c.sleep())
        val cancelled = provider.analyze(c.input(), CancellationToken { true })
        assertEquals("cancelled", cancelled.status)
        assertTrue(cancelled.coverage.all { it.state == "cancelled" }); noncertifying(cancelled)
        val oversized = provider.analyze(c.input().copy(requiredRuleIds = List(4097) { SLEEP_IN_COROUTINE }))
        assertTrue(oversized.findings.isEmpty()); assertTrue(oversized.coverage.isEmpty()); assertFalse(oversized.complete())
        assertTrue(c.trace.isEmpty())
    }

    @Test fun unresolved_or_wrong_selected_signature_is_not_rescued_by_a_matching_source_spelling() = fixture { c ->
        val selected = c.sleep()
        val originalTarget = selected.shards.single().symbols.single { it.id == selected.applicability.callables.single().symbolId }
        val malformed = originalTarget.copy(returnType = CoroutineRefinementControls.plain("kotlin.Int"))
            .let { it.copy(id = resolvedSymbolIdentity(it)) }
        val changed = selected.copy(shards = selected.shards.map { shard -> shard.copy(
            symbols = shard.symbols.map { if (it.id == originalTarget.id) malformed else it },
            calls = shard.calls.map { it.copy(targetSymbolId = malformed.id) }) },
            applicability = selected.applicability.copy(callables = listOf(c.selected(CoroutineCallableRole.THREAD_SLEEP_LONG, malformed))))
        val result = CoroutineRefinementProvider(c.model, changed).analyze(c.input())
        assertEquals("coroutine_refinement_selected_signature_unsupported", result.reasons.single())
        assertTrue(result.findings.isEmpty()); noncertifying(result)
    }

    @Test fun synthetic_or_missing_direct_owners_never_gain_suspend_context_from_an_ancestor() = fixture { c ->
        val selected = c.sleep()
        val variants = listOf(
            selected.copy(shards = selected.shards.map { s -> s.copy(calls = s.calls.map { it.copy(containingSymbolId = null) }) }),
            selected.copy(shards = selected.shards.map { s -> s.copy(calls = s.calls.map { it.copy(source = it.source.copy(synthetic = true)) }) }),
            selected.copy(shards = selected.shards.map { it.copy(declarations = emptyList()) }),
        )
        variants.forEach { value ->
            val result = c.analyze(value)
            assertTrue(result.findings.isEmpty())
            assertContains(result.reasons, "coroutine_refinement_captured_direct_owner_unavailable")
            noncertifying(result)
        }
    }

    @Test fun a_stale_nonlocal_exemption_needs_the_exact_current_owner_body_span() = fixture { c ->
        val selected = c.sleep(exempt = true)
        val exemption = selected.applicability.blockingBridgeExemptions.single()
        val changed = selected.copy(applicability = selected.applicability.copy(blockingBridgeExemptions = listOf(
            exemption.copy(source = exemption.source.copy(digest = Digests.sha256("stale-body"))))))
        val result = CoroutineRefinementProvider(c.model, changed).analyze(c.input())
        assertEquals("coroutine_refinement_exemption_not_source_bound", result.reasons.single())
        assertTrue(result.findings.isEmpty()); noncertifying(result)
    }

    @Test fun result_terminals_require_exact_receiver_argument_range_joins_not_nearby_names() =
        fixture("result_terminal_consumes_cancellation") { c ->
            val selected = c.result()
            val runId = selected.applicability.callables.single { it.role == CoroutineCallableRole.RUN_CATCHING }.symbolId
            val terminalId = selected.applicability.callables.single { it.role == CoroutineCallableRole.GET_OR_ELSE }.symbolId
            val run = selected.shards.single().calls.single { it.targetSymbolId == runId }
            val changed = selected.copy(shards = selected.shards.map { shard -> shard.copy(calls = shard.calls.map {
                if (it.targetSymbolId == terminalId) it.copy(source = run.source) else it
            }) })
            val result = c.analyze(changed)
            assertTrue(result.findings.isEmpty())
            assertContains(result.reasons, "coroutine_refinement_result_terminal_resolution_unavailable")
            noncertifying(result)
        }

    @Test fun result_delay_must_belong_to_the_exact_run_catching_argument_lambda() =
        fixture("result_terminal_consumes_cancellation") { c ->
            val selected = c.result()
            val owner = selected.shards.single().symbols.single { it.kind == "function" && it.origin == "Source" }
            val delayId = selected.applicability.callables.single { it.role == CoroutineCallableRole.DELAY_LONG }.symbolId
            val changed = selected.copy(shards = selected.shards.map { shard -> shard.copy(calls = shard.calls.map {
                if (it.targetSymbolId == delayId) it.copy(containingSymbolId = owner.id) else it
            }) })
            val result = c.analyze(changed)
            assertTrue(result.findings.isEmpty())
            assertContains(result.reasons, "coroutine_refinement_result_inline_argument_owner_unavailable")
            noncertifying(result)
        }

    @Test fun zero_duration_delay_is_not_treated_as_a_guaranteed_cancellable_operation() {
        val text = CoroutineRefinementControls.source("result_terminal_consumes_cancellation").replace("delay(1L)", "delay(0L)")
        textFixture(text) { c ->
            val result = c.analyze(c.result(positiveLiteral = "0L"))
            assertTrue(result.findings.isEmpty())
            assertContains(result.reasons, "coroutine_refinement_result_closed_grammar_unavailable")
            noncertifying(result)
        }
    }

    @Test fun aliases_change_only_syntax_spelling_not_the_enrolled_resolved_api_roles() {
        val text = """
            package fixture.aliases
            import kotlin.runCatching as attempt
            import kotlinx.coroutines.delay as pause
            import kotlin.getOrElse as recover
            suspend fun recoverOrPropagate(): Int =
                attempt {
                    pause(1L)
                    7
                }.recover { 0 }
        """.trimIndent() + "\n"
        textFixture(text) { c ->
            val result = c.analyze(c.result(runName = "attempt", delayName = "pause", terminalName = "recover"))
            assertEquals(1, result.findings.size)
            assertEquals(RESULT_SWALLOWING_CANCELLATION, result.findings.single().ruleId)
            noncertifying(result)
        }
    }
}

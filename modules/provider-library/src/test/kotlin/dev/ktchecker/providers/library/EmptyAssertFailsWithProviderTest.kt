package dev.ktchecker.providers.library

import dev.ktchecker.contracts.*
import dev.ktchecker.semantic.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.*

/** Executable detector/boundary controls with explicitly SYNTHETIC facts and owner receipts. */
class EmptyAssertFailsWithProviderTest {
    private fun noncertifying(result: ProviderResult) {
        assertEquals("partial", result.status)
        assertTrue(result.coverage.all { it.state != "clean" && it.certificateId == null && !it.implemented && !it.tested && !it.qualified })
    }

    @Test fun emitsOneHighCertaintyExistingRuleOnlyAfterEveryReceipt() {
        EmptyAssertFailsWithControls().use { f ->
            val model = f.model()
            val s = f.scenario(model)
            val result = f.analyze(model, s.selection)
            noncertifying(result)
            val finding = result.findings.single()
            assertEquals(EMPTY_ASSERT_FAILS_WITH, finding.ruleId)
            assertEquals("correctness", finding.kind)
            assertEquals("high", finding.certainty)
            assertEquals(s.owner.id, finding.symbolId)
            assertEquals("findings", result.coverage.single().state)
            assertEquals("executed", result.coverage.single().provenance)
            assertTrue(f.trace.indexOf("read") > f.trace.indexOf("policy"))
            assertTrue(f.trace.indexOf("policy") > f.trace.indexOf("ownership"))
            assertEquals(1, f.trace.count { it == "read" })
        }
    }

    @Test fun commentsQualifiedNamesAndImportAliasesDoNotReplaceResolvedIdentity() {
        listOf(
            "assertFailsWith<IllegalArgumentException> { /* outer /* nested */ end */ }" to "{ /* outer /* nested */ end */ }",
            "expectedFailure<IllegalArgumentException>(){ }" to "{ }",
            "kotlin.test.assertFailsWith<IllegalArgumentException> { }" to "{ }",
            "assertFailsWith<IllegalArgumentException> { // just a comment\n }" to "{ // just a comment\n }",
        ).forEach { (call, block) ->
            EmptyAssertFailsWithControls("@Test fun missingAction() { $call }\n").use { f ->
                val model = f.model()
                val result = f.analyze(model, f.scenario(model, call, block).selection)
                assertEquals(1, result.findings.size, call)
                noncertifying(result)
            }
        }
    }

    @Test fun nonemptyBodiesAndEnclosingExpectedFailureOrConditionalContextsAbstain() {
        val call = "assertFailsWith<IllegalArgumentException> { }"
        listOf(
            "@Test fun missingAction() { assertFailsWith<AssertionError> { $call } }",
            "@Test fun missingAction() { try { $call } catch (expected: AssertionError) { } }",
            "@Test fun missingAction() { if (false) $call }",
            "@Test fun missingAction() { return; $call }",
            "@Test fun missingAction() { before(); $call }",
            "@Test fun missingAction() { $call; after() }",
        ).forEach { text ->
            EmptyAssertFailsWithControls(text).use { f ->
                val model = f.model()
                // Even with forged direct ownership, the complete source grammar rejects wrappers.
                val result = f.analyze(model, f.scenario(model).selection)
                assertTrue(result.findings.isEmpty(), text)
                assertTrue("empty_assertion_closed_ordinary_body_unavailable" in result.reasons)
                noncertifying(result)
            }
        }
        listOf("Unit", "kotlin.Unit", "throw IllegalArgumentException()", "unknownEffect()", "getter", "return@assertFailsWith").forEach { body ->
            val block = "{ $body }"
            val expression = "assertFailsWith<IllegalArgumentException> $block"
            EmptyAssertFailsWithControls("@Test fun missingAction() { $expression }").use { f ->
                val model = f.model()
                val result = f.analyze(model, f.scenario(model, expression, block).selection)
                assertTrue(result.findings.isEmpty(), body)
                noncertifying(result)
            }
        }
    }

    @Test fun actualNestedLambdaOwnershipDoesNotConfuseAnInnerCalibrationWithATestEntry() {
        val inner = "assertFailsWith<IllegalArgumentException> { }"
        val outer = "assertFailsWith<AssertionError> { $inner }"
        EmptyAssertFailsWithControls("@Test fun missingAction() { $outer }").use { f ->
            val model = f.model()
            val owner = f.owner()
            val target = f.target()
            val outerBlock = f.lambda(f.span("{ $inner }"))
            val innerBlock = f.lambda(f.span("{ }"))
            val calls = listOf(
                f.call(target, owner, f.span(outer), requireNotNull(outerBlock.source)),
                f.call(target, outerBlock, f.span(inner), requireNotNull(innerBlock.source)),
            )
            val selection = f.selection(model, listOf(owner, outerBlock, innerBlock), listOf(target), calls,
                listOf(target.id), listOf(f.entry(owner)))
            val result = f.analyze(model, selection)
            assertTrue(result.findings.isEmpty())
            assertTrue("empty_assertion_direct_test_owner_unavailable" in result.reasons)
            noncertifying(result)
        }
    }

    @Test fun missingOrExcludedTestContractNeverMeansARequiredPassingTest() {
        EmptyAssertFailsWithControls().use { f ->
            val model = f.model()
            val s = f.scenario(model)
            val missing = f.analyze(model, s.selection.copy(applicability = s.selection.applicability.copy(testEntries = emptyList())))
            assertTrue(missing.findings.isEmpty())
            assertTrue("empty_assertion_required_test_contract_unavailable" in missing.reasons)
            EmptyAssertionTestDisposition.entries.filter { it != EmptyAssertionTestDisposition.REQUIRED_TO_PASS }.forEach { disposition ->
                val selection = s.selection.copy(applicability = s.selection.applicability.copy(testEntries = listOf(f.entry(s.owner, disposition))))
                val result = f.analyze(model, selection)
                assertTrue(result.findings.isEmpty(), disposition.toString())
                assertTrue("empty_assertion_owner_test_policy_excluded" in result.reasons)
                noncertifying(result)
            }
        }
    }

    @Test fun exactOwnerExclusionDoesNotHideAdjacentOrdinaryViolation() {
        val first = "@Test fun calibrated() { assertFailsWith<IllegalArgumentException> { } }"
        val second = "@Test fun missingAction() { assertFailsWith<IllegalArgumentException> { } }"
        EmptyAssertFailsWithControls("$first\n$second\n").use { f ->
            val model = f.model()
            val target = f.target()
            val owners = listOf(f.owner("calibrated", f.span(first)), f.owner("missingAction", f.span(second)))
            val blocks = listOf(f.lambda(f.span("{ }", 0)), f.lambda(f.span("{ }", 1)))
            val calls = owners.indices.map { f.call(target, owners[it], f.span("assertFailsWith<IllegalArgumentException> { }", it), requireNotNull(blocks[it].source)) }
            val selection = f.selection(model, owners + blocks, listOf(target), calls, listOf(target.id),
                listOf(f.entry(owners[0], EmptyAssertionTestDisposition.CALIBRATION), f.entry(owners[1])))
            val result = f.analyze(model, selection)
            assertEquals(owners[1].id, result.findings.single().symbolId)
            noncertifying(result)
        }
    }

    @Test fun hiddenUnitKClassWrongOriginAndSourceHomonymsCannotBeSelectedAsTheApi() {
        EmptyAssertFailsWithControls().use { f ->
            val model = f.model()
            val s = f.scenario(model)
            val transformations: List<(SymbolFact) -> SymbolFact> = listOf(
                { it.copy(qualifiedName = "fixture.assertFailsWith") },
                { it.copy(origin = "Source", source = s.owner.source) },
                { it.copy(origin = "Java.Library") },
                { it.copy(flags = listOf("visibility:public")) },
                { it.copy(typeParameterCount = 0) },
                { it.copy(parameters = it.parameters.map { p -> if (p.index == 1) p.copy(type = p.type.copy(arguments =
                    listOf(TypeProjectionFact("invariant", EmptyAssertFailsWithControls.plain("kotlin.Unit"))))) else p }) },
                { it.copy(parameters = listOf(it.parameters[0].copy(name = "exceptionClass", type = TypeFact("class", "kotlin.reflect.KClass", false)), it.parameters[1])) },
            )
            transformations.forEach { transform ->
                val target = f.target(transform)
                val selection = f.selection(model, listOf(s.owner, s.block), listOf(target), listOf(s.call.copy(targetSymbolId = target.id)),
                    listOf(target.id), listOf(f.entry(s.owner)))
                f.trace.clear()
                val result = f.analyze(model, selection)
                assertTrue(result.findings.isEmpty())
                assertTrue("empty_assertion_selected_signature_unsupported" in result.reasons)
                assertTrue(f.trace.isEmpty())
            }
            val homonym = f.target { it.copy(qualifiedName = "fixture.assertFailsWith", origin = "Source", source = s.owner.source) }
            val unselected = f.selection(model, listOf(s.owner, s.block), listOf(s.target, homonym),
                listOf(s.call.copy(targetSymbolId = homonym.id)), listOf(s.target.id), listOf(f.entry(s.owner)))
            assertTrue(f.analyze(model, unselected).findings.isEmpty())
        }
    }

    @Test fun sourceArgumentAndLambdaDeclarationsMustActuallyJoin() {
        EmptyAssertFailsWithControls().use { f ->
            val model = f.model()
            val s = f.scenario(model)
            val shard = s.selection.shards.single()
            val variants = listOf(
                shard.copy(declarations = listOf(s.owner.id)),
                shard.copy(calls = listOf(s.call.copy(containingSymbolId = s.block.id))),
                shard.copy(calls = listOf(s.call.copy(arguments = emptyList()))),
                shard.copy(calls = listOf(s.call.copy(arguments = listOf(ArgumentBindingFact(s.call.source, 1, "block"))))),
                shard.copy(calls = listOf(s.call.copy(arguments = listOf(ArgumentBindingFact(s.block.source, 0, "message"))))),
                shard.copy(calls = listOf(s.call.copy(source = requireNotNull(s.block.source)))),
                shard.copy(calls = listOf(s.call.copy(extensionReceiverType = EmptyAssertFailsWithControls.plain("fixture.Receiver")))),
            )
            variants.forEach { changed ->
                val result = f.analyze(model, s.selection.copy(shards = listOf(changed)))
                assertTrue(result.findings.isEmpty())
                noncertifying(result)
            }
            val nothing = f.bind(s.block.copy(returnType = EmptyAssertFailsWithControls.plain("kotlin.Nothing")))
            assertTrue(f.analyze(model, f.selection(model, listOf(s.owner, nothing), listOf(s.target), listOf(s.call),
                listOf(s.target.id), listOf(f.entry(s.owner)))).findings.isEmpty())
        }
    }

    @Test fun serializedRequestsAndRawExitFieldsAreNotAuthorityAndEveryReceiptCanDeny() {
        EmptyAssertFailsWithControls().use { f ->
            val model = f.model()
            val s = f.scenario(model)
            val input = f.input(model)
            val provider = EmptyAssertFailsWithProvider(model, s.selection)
            val request = provider.authorizationRequest(input)
            assertEquals(request, ContractJson.decodeFromString<EmptyAssertionAuthorizationRequest>(ContractJson.encodeToString(request)))
            assertTrue(provider.analyze(input, CancellationToken.NONE).findings.isEmpty())
            assertTrue(f.trace.isEmpty())
            listOf("snapshot", "extraction", "artifact", "ownership", "policy").forEach { stage ->
                f.trace.clear()
                val result = f.analyze(model, s.selection, failAt = stage)
                assertTrue(result.findings.isEmpty(), stage)
                assertTrue("read" !in f.trace, stage)
                assertFalse(result.toString().contains("confidential"))
            }
        }
    }

    @Test fun oneUsePermitBindsExactModelConfigurationSourceAndPolicy() {
        EmptyAssertFailsWithControls().use { f ->
            val model = f.model()
            val s = f.scenario(model)
            val input = f.input(model)
            val request = EmptyAssertFailsWithProvider(model, s.selection).authorizationRequest(input)
            val permit = f.permit(model, request)
            val provider = EmptyAssertFailsWithProvider(model, s.selection, ExactEmptyAssertionAuthority(permit))
            assertEquals(1, provider.analyze(input, CancellationToken.NONE).findings.size)
            val reads = f.trace.count { it == "read" }
            val replay = provider.analyze(input, CancellationToken.NONE)
            assertTrue(replay.findings.isEmpty())
            assertTrue("empty_assertion_authorization_mismatch_or_replay" in replay.reasons)
            assertEquals(reads, f.trace.count { it == "read" })
            val otherModel = f.model()
            val otherPermit = f.permit(model, request)
            val crossModel = EmptyAssertFailsWithProvider(otherModel, s.selection, ExactEmptyAssertionAuthority(otherPermit))
                .analyze(f.input(otherModel), CancellationToken.NONE)
            assertTrue(crossModel.findings.isEmpty())
            assertTrue("empty_assertion_authorization_mismatch_or_replay" in crossModel.reasons)
            assertTrue(f.analyze(model, s.selection, input.copy(context = input.context.copy(configurationDigest = Digests.sha256("changed policy"))))
                .findings.isEmpty())
            assertTrue(f.analyze(model, s.selection, bytes = { f.sourceBytes.copyOf().also { it[0] = '!'.code.toByte() } }).findings.isEmpty())
            val moved = s.selection.applicability.copy(testEntries = listOf(f.entry(s.owner).copy(source = requireNotNull(s.block.source))))
            assertTrue(f.analyze(model, s.selection.copy(applicability = moved)).findings.isEmpty())
        }
    }

    @Test fun callbacksCannotMutateRequestedRulesOrPolicyAfterPreparation() {
        EmptyAssertFailsWithControls().use { f ->
            val model = f.model()
            val s = f.scenario(model)
            val rules = mutableListOf(EMPTY_ASSERT_FAILS_WITH)
            val entries = mutableListOf(f.entry(s.owner))
            val selection = s.selection.copy(applicability = s.selection.applicability.copy(testEntries = entries))
            val result = f.analyze(model, selection, f.input(model, rules), stageHook = { stage ->
                if (stage == "snapshot") { rules.clear(); rules += "not-implemented"; entries.clear() }
            })
            assertEquals(EMPTY_ASSERT_FAILS_WITH, result.coverage.single().ruleId)
            assertEquals(1, result.findings.size)
        }
    }

    @Test fun failedIncompleteUnsupportedAndCancelledRunsNeverReportAResultAsClean() {
        EmptyAssertFailsWithControls().use { f ->
            val model = f.model()
            val s = f.scenario(model)
            listOf(s.selection.copy(compilerExitCode = null), s.selection.copy(compilerExitCode = 1),
                s.selection.copy(shards = emptyList()), s.selection.copy(shards = listOf(s.selection.shards.single().copy(
                    unknown = listOf(UnknownSemanticFact("unknown", "SYNTHETIC unresolved body", s.block.source)))))).forEach { selection ->
                f.trace.clear()
                val result = f.analyze(model, selection)
                assertTrue(result.findings.isEmpty())
                assertTrue(f.trace.isEmpty())
                noncertifying(result)
            }
            val cancelled = f.analyze(model, s.selection, cancellation = object : CancellationToken { override fun isCancelled() = true })
            assertEquals("cancelled", cancelled.status)
            assertTrue(cancelled.findings.isEmpty())
            val duplicate = f.analyze(model, s.selection, f.input(model, listOf(EMPTY_ASSERT_FAILS_WITH, EMPTY_ASSERT_FAILS_WITH)))
            assertTrue("empty_assertion_duplicate_rule_selection" in duplicate.reasons)
            val mixed = f.analyze(model, s.selection, f.input(model, listOf(EMPTY_ASSERT_FAILS_WITH, "another.catalog.rule")))
            assertEquals("unsupported", mixed.coverage.single { it.ruleId == "another.catalog.rule" }.state)
            assertEquals(1, mixed.findings.size)
            val wrongTarget = f.model(target = "desktop")
            assertTrue(f.analyze(wrongTarget, f.scenario(wrongTarget).selection).findings.isEmpty())
            val missingLibrary = f.model(dependencies = listOf(f.stdlib))
            assertTrue(f.analyze(missingLibrary, f.scenario(missingLibrary).selection).findings.isEmpty())
        }
    }
}

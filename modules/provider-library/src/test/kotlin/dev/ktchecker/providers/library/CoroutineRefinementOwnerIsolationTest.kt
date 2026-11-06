package dev.ktchecker.providers.library

import dev.ktchecker.contracts.AnalysisInput
import dev.ktchecker.contracts.Coverage
import dev.ktchecker.contracts.ProviderResult
import dev.ktchecker.contracts.SourceLocation
import dev.ktchecker.semantic.SemanticParameter
import dev.ktchecker.semantic.SemanticSpan
import dev.ktchecker.semantic.SymbolFact
import dev.ktchecker.semantic.resolvedSymbolIdentity
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Two SYNTHETIC provider controls, not compiler/engine integration evidence or new rule breadth.
 * Existing fixtures fabricate facts, execution/ownership/policy receipts and non-JAR artifacts.
 * Source spans below describe the supplied bytes exactly; expected anchors are independent literals.
 * No parser output supplies these facts or expectations. No fixture program is invoked.
 */
class CoroutineRefinementOwnerIsolationTest {
    private val intType = CoroutineRefinementControls.plain("kotlin.Int")
    private val resultType = CoroutineRefinementControls.applied("kotlin.Result", intType)
    private val sliceReason = "coroutine_refinement_bounded_slice_executed_unqualified"

    private fun intOwner(c: CoroutineRefinementControls, name: String, fragment: String): SymbolFact {
        // Do not use c.owner(): its end-of-file span would incorrectly include the next function.
        val descriptor = c.f.owner(name, source = c.f.span(fragment)).copy(returnType = intType)
        return descriptor.copy(id = resolvedSymbolIdentity(descriptor))
    }

    private fun failureOwner(c: CoroutineRefinementControls, name: String, span: SemanticSpan): SymbolFact {
        val descriptor = c.lambda(name, span).copy(parameters = listOf(
            SemanticParameter(0, "it", CoroutineRefinementControls.plain("kotlin.Throwable"))))
        return descriptor.copy(id = resolvedSymbolIdentity(descriptor))
    }

    private fun literalSpan(f: LibraryRuleFixtures, line: Int, column: Int, endLine: Int,
                            endColumn: Int, startByte: Int, endByte: Int): SemanticSpan =
        SemanticSpan("library/src/Main.kt", f.source.digest, ":library|main",
            line, column, endLine, endColumn, startByte, endByte)

    private fun noncertifying(result: ProviderResult, input: AnalysisInput, state: String,
                              expectedReasons: List<String>) {
        assertEquals(COROUTINE_REFINEMENT_PROVIDER_ID, result.providerId)
        assertEquals(COROUTINE_REFINEMENT_VERSION, result.providerVersion)
        assertEquals("partial", result.status)
        assertFalse(result.complete())
        assertFalse(result.completeFor(input))
        assertTrue(result.rawDiagnostics.isEmpty())
        assertEquals(expectedReasons, result.reasons)
        val coverage = result.coverage.single()
        // The row reason is the bounded-slice reason; applicability limits are in result.reasons.
        assertEquals(Coverage(RESULT_SWALLOWING_CANCELLATION, input.context.scope, state,
            reason = sliceReason, required = true, depth = "graph", provenance = "executed",
            certificateId = null, implemented = false, tested = false, qualified = false), coverage)
        assertNull(coverage.certificateId)
        assertFalse(coverage.implemented)
        assertFalse(coverage.tested)
        assertFalse(coverage.qualified)
        assertFalse(coverage.successful())
    }

    @Test fun qualified_recovery_and_propagation_keep_exact_distinct_owner_spans() {
        val source = """
            package fixture

            import kotlinx.coroutines.delay

            suspend fun recover(): Int =
                kotlin.runCatching { kotlinx.coroutines.delay(1L); 7 }.getOrElse { 0 }

            suspend fun propagate(): Int =
                kotlin.runCatching { delay(1L); 7 }.getOrThrow()
        """.trimIndent() + "\n"
        LibraryRuleFixtures(source).use { f ->
            val c = CoroutineRefinementControls(f)
            val recover = intOwner(c, "recover", "suspend fun recover(): Int =\n" +
                "    kotlin.runCatching { kotlinx.coroutines.delay(1L); 7 }.getOrElse { 0 }")
            val propagate = intOwner(c, "propagate", "suspend fun propagate(): Int =\n" +
                "    kotlin.runCatching { delay(1L); 7 }.getOrThrow()")
            val recoverBlock = c.lambda("recoverBlock", f.span("{ kotlinx.coroutines.delay(1L); 7 }"))
            val propagateBlock = c.lambda("propagateBlock", f.span("{ delay(1L); 7 }"))
            val fallback = failureOwner(c, "recoverFallback", f.span("{ 0 }"))
            assertEquals(literalSpan(f, 4, 0, 5, 74, 50, 153), recover.source)
            assertEquals(literalSpan(f, 7, 0, 8, 52, 155, 238), propagate.source)
            assertEquals(literalSpan(f, 5, 23, 5, 58, 102, 137), recoverBlock.source)
            assertEquals(literalSpan(f, 8, 23, 8, 39, 209, 225), propagateBlock.source)
            assertEquals(literalSpan(f, 5, 69, 5, 74, 148, 153), fallback.source)
            assertNotEquals(recover.id, propagate.id)
            assertNotEquals(recoverBlock.id, propagateBlock.id)
            assertTrue(requireNotNull(recover.source).endByte < requireNotNull(propagate.source).startByte)

            val run = c.target(CoroutineCallableRole.RUN_CATCHING)
            val delay = c.target(CoroutineCallableRole.DELAY_LONG)
            val recoverTerminal = c.target(CoroutineCallableRole.GET_OR_ELSE)
            val propagateTerminal = c.target(CoroutineCallableRole.GET_OR_THROW)
            // Both chains use whole expression spans. No claim about every real FIR representation.
            val selection = c.selection(
                owners = listOf(recover, propagate, recoverBlock, propagateBlock, fallback),
                targets = listOf(run, delay, recoverTerminal, propagateTerminal),
                calls = listOf(
                    c.call(run, recover, f.span("kotlin.runCatching { kotlinx.coroutines.delay(1L); 7 }"),
                        listOf(requireNotNull(recoverBlock.source)), result = resultType),
                    c.call(delay, recoverBlock, f.span("kotlinx.coroutines.delay(1L)"),
                        listOf(f.span("1L", occurrence = 0))),
                    c.call(recoverTerminal, recover,
                        f.span("kotlin.runCatching { kotlinx.coroutines.delay(1L); 7 }.getOrElse { 0 }"),
                        listOf(requireNotNull(fallback.source)), result = intType, extensionReceiver = resultType),
                    c.call(run, propagate, f.span("kotlin.runCatching { delay(1L); 7 }"),
                        listOf(requireNotNull(propagateBlock.source)), result = resultType),
                    c.call(delay, propagateBlock, f.span("delay(1L)", occurrence = 1),
                        listOf(f.span("1L", occurrence = 1))),
                    c.call(propagateTerminal, propagate,
                        f.span("kotlin.runCatching { delay(1L); 7 }.getOrThrow()"),
                        emptyList(), result = intType, extensionReceiver = resultType),
                ),
                selections = listOf(c.selected(CoroutineCallableRole.RUN_CATCHING, run),
                    c.selected(CoroutineCallableRole.DELAY_LONG, delay),
                    c.selected(CoroutineCallableRole.GET_OR_ELSE, recoverTerminal),
                    c.selected(CoroutineCallableRole.GET_OR_THROW, propagateTerminal)),
            )
            val input = c.input(listOf(RESULT_SWALLOWING_CANCELLATION))
            val result = c.analyze(selection, input)
            noncertifying(result, input, "findings", listOf(sliceReason))
            val finding = result.findings.single()
            assertEquals(RESULT_SWALLOWING_CANCELLATION, finding.ruleId)
            assertEquals(COROUTINE_REFINEMENT_PROVIDER_ID, finding.providerId)
            assertEquals(input.context.scope, finding.context)
            assertEquals("open", finding.disposition)
            assertEquals("warning", finding.severity)
            assertEquals("probable_defect", finding.kind)
            assertEquals("medium", finding.certainty)
            assertNull(finding.upstreamId)
            assertEquals(recover.id, finding.symbolId)
            assertEquals(SourceLocation("library/src/Main.kt", f.source.digest, 6, 59, 6, 74), finding.location)
            assertTrue(result.findings.none { it.symbolId == propagate.id })
            assertTrue(finding.evidence.any { it.kind == "resolved-cancellable-operation" && it.digest == delay.id })
            assertTrue(finding.evidence.any { it.kind == "resolved-result-consumer" && it.digest == recoverTerminal.id })
        }
    }

    @Test fun cancellation_rethrowing_complex_fallback_remains_unavailable_not_certified_clean() {
        val source = """
            package fixture

            import kotlinx.coroutines.CancellationException
            import kotlinx.coroutines.delay

            suspend fun recoverOrPropagate(): Int =
                runCatching { delay(1L); 7 }.getOrElse { if (it is CancellationException) throw it; 0 }
        """.trimIndent() + "\n"
        LibraryRuleFixtures(source).use { f ->
            val c = CoroutineRefinementControls(f)
            val owner = intOwner(c, "recoverOrPropagate", "suspend fun recoverOrPropagate(): Int =\n" +
                "    runCatching { delay(1L); 7 }.getOrElse { if (it is CancellationException) throw it; 0 }")
            val block = c.lambda("runBlock", f.span("{ delay(1L); 7 }"))
            val fallback = failureOwner(c, "preservingFallback",
                f.span("{ if (it is CancellationException) throw it; 0 }"))
            assertEquals(literalSpan(f, 5, 0, 6, 91, 98, 229), owner.source)
            assertEquals(literalSpan(f, 6, 16, 6, 32, 154, 170), block.source)
            assertEquals(literalSpan(f, 6, 43, 6, 91, 181, 229), fallback.source)
            val run = c.target(CoroutineCallableRole.RUN_CATCHING)
            val delay = c.target(CoroutineCallableRole.DELAY_LONG)
            val terminal = c.target(CoroutineCallableRole.GET_OR_ELSE)
            val selection = c.selection(
                owners = listOf(owner, block, fallback), targets = listOf(run, delay, terminal),
                calls = listOf(
                    c.call(run, owner, f.span("runCatching { delay(1L); 7 }"),
                        listOf(requireNotNull(block.source)), result = resultType),
                    c.call(delay, block, f.span("delay(1L)"), listOf(f.span("1L"))),
                    c.call(terminal, owner,
                        f.span("runCatching { delay(1L); 7 }.getOrElse { if (it is CancellationException) throw it; 0 }"),
                        listOf(requireNotNull(fallback.source)), result = intType, extensionReceiver = resultType),
                ),
                selections = listOf(c.selected(CoroutineCallableRole.RUN_CATCHING, run),
                    c.selected(CoroutineCallableRole.DELAY_LONG, delay),
                    c.selected(CoroutineCallableRole.GET_OR_ELSE, terminal)),
            )
            val input = c.input(listOf(RESULT_SWALLOWING_CANCELLATION))
            val result = c.analyze(selection, input)
            assertTrue(result.findings.isEmpty())
            noncertifying(result, input, "unavailable", listOf(sliceReason,
                "coroutine_refinement_result_closed_grammar_unavailable"))
            // Correct handling of this branch is not implemented or certified by this control.
        }
    }
}

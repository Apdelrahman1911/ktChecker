package dev.ktchecker.providers.library

import dev.ktchecker.contracts.Digests
import dev.ktchecker.semantic.SemanticSource
import dev.ktchecker.semantic.SemanticSpan
import dev.ktchecker.semantic.SourceCoordinates
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Parser controls prove no library symbol, execution, source-set membership or canonical rule. */
class CoroutineRefinementSyntaxTest {
    private val body = """
        suspend fun recoverOrPropagate(): Int =
            runCatching {
                delay(1L)
                7
            }.getOrElse { 0 }
    """.trimIndent() + "\n"

    private class Source(val text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val source = SemanticSource("syntax-control/Source.kt", Digests.sha256(bytes), bytes.size.toLong(), "synthetic/main")
        private val coordinates = SourceCoordinates(source, bytes)
        val parser = CoroutineRefinementSyntax(source, bytes)
        fun span(start: Int = 0, end: Int = text.trimEnd().length): SemanticSpan {
            fun point(offset: Int): Pair<Int, Int> {
                val prefix = text.substring(0, offset)
                return prefix.count { it == '\n' } to (offset - prefix.lastIndexOf('\n') - 1)
            }
            val (line, column) = point(start)
            val (endLine, endColumn) = point(end)
            return coordinates.span(line, column, endLine, endColumn)
        }
        fun fragment(span: SemanticSpan): String = bytes.copyOfRange(span.startByte, span.endByte).toString(Charsets.UTF_8)
    }

    @Test fun closed_shape_exposes_call_receiver_and_exact_argument_ranges_not_symbol_names() {
        val s = Source(body)
        val shape = assertNotNull(s.parser.resultFunction(s.span()))
        assertEquals("runCatching", s.fragment(shape.runCatching.callee))
        assertEquals("delay(1L)", s.fragment(shape.delay.call.element))
        assertEquals("1L", s.fragment(shape.delay.call.arguments.single()))
        assertEquals(1L, shape.delay.value)
        assertEquals("getOrElse", s.fragment(assertNotNull(shape.terminal).callee))
        assertEquals("{ 0 }", s.fragment(assertNotNull(shape.failureBlock)))
        assertEquals(shape.block, shape.runCatching.arguments.single())
    }

    @Test fun syntactically_identical_homonyms_and_aliases_are_intentionally_indistinguishable_to_this_parser() {
        val changed = body.replace("runCatching", "attempt").replace("delay", "pause").replace("getOrElse", "recover")
        val s = Source(changed)
        val shape = assertNotNull(s.parser.resultFunction(s.span()))
        assertEquals("attempt", s.fragment(shape.runCatching.callee))
        assertEquals("pause", s.fragment(shape.delay.call.callee))
        assertEquals("recover", s.fragment(assertNotNull(shape.terminal).callee))
        // Semantic roles are deliberately absent. Only separately admitted resolved facts choose APIs.
    }

    @Test fun get_or_throw_and_opaque_return_are_different_syntax_shapes_not_both_swallowing() {
        val throwing = Source(body.replace("getOrElse { 0 }", "getOrThrow()"))
        val a = assertNotNull(throwing.parser.resultFunction(throwing.span()))
        assertNotNull(a.terminal); assertNull(a.failureBlock)
        val opaque = Source(body.replace(": Int", ": Result<Int>").replace(".getOrElse { 0 }", ""))
        val b = assertNotNull(opaque.parser.resultFunction(opaque.span()))
        assertNull(b.terminal); assertNull(b.failureBlock)
    }

    @Test fun simple_qualified_sleep_call_exposes_call_element_not_qualifier_as_anchor() {
        val s = Source("java.lang.Thread.sleep(1L)")
        val call = assertNotNull(s.parser.simpleLongCall(s.span()))
        assertEquals("sleep(1L)", s.fragment(call.call.element))
        assertEquals("java.lang.Thread.sleep(1L)", s.fragment(call.call.whole))
        assertEquals("sleep", s.fragment(call.call.callee))
        val alias = Source("pause(1L)")
        assertEquals("pause(1L)", alias.fragment(assertNotNull(alias.parser.simpleLongCall(alias.span())).call.element))
    }

    @Test fun comments_strings_templates_escaped_names_and_extra_statements_all_abstain() {
        val variants = listOf(
            body.replace("delay(1L)", "delay(/* not semantic authority */1L)"),
            body.replace("delay(1L)", "delay(1L) // getOrThrow()"),
            body.replace("{ 0 }", "{ \"getOrThrow()\" }"),
            body.replace("{ 0 }", "{ throw it }"),
            body.replace("{ 0 }", "{ if (it is CancellationException) throw it; 0 }"),
            body.replace("runCatching", "`runCatching`"),
            body.replace("recoverOrPropagate", "récupérer"),
            body.replace("delay(1L)", "delay(1L)\n        sideEffect()"),
            body.replace("runCatching {", "runCatching<Int> {"),
            body.replace("suspend fun", "@Deprecated(\"example\") suspend fun"),
            body.trimEnd() + "\ngetOrThrow()",
        )
        variants.forEach { text ->
            val s = Source(text)
            assertNull(s.parser.resultFunction(s.span()), "Outside closed grammar must remain unknown")
        }
    }

    @Test fun a_positive_decimal_duration_is_required_and_general_expressions_are_not_guessed() {
        listOf("0L", "-1L", "timeMillis", "1 + 1", "1_000L", "0x10L", "9223372036854775808L").forEach { literal ->
            val s = Source(body.replace("1L", literal))
            assertNull(s.parser.resultFunction(s.span()))
        }
    }

    @Test fun statement_separator_is_required_even_when_all_other_tokens_match() {
        val separated = Source(body.replace("delay(1L)\n        7", "delay(1L);7"))
        assertNotNull(separated.parser.resultFunction(separated.span()))
        val joined = Source(body.replace("delay(1L)\n        7", "delay(1L) 7"))
        assertNull(joined.parser.resultFunction(joined.span()))
    }

    @Test fun crlf_and_supplementary_unicode_before_the_owner_preserve_utf8_and_utf16_ranges() {
        val prefix = "// immutable prefix \uD83D\uDE00\r\n"
        val s = Source(prefix + body.replace("\n", "\r\n"))
        val shape = assertNotNull(s.parser.resultFunction(s.span(prefix.length)))
        assertEquals("runCatching", s.fragment(shape.runCatching.callee))
        assertEquals(2, shape.runCatching.callee.startLine)
        assertEquals(4, shape.runCatching.callee.startColumn)
        assertTrue(shape.runCatching.callee.startByte > prefix.length)
    }

    @Test fun synthetic_or_mismatched_source_spans_are_not_parser_inputs() {
        val s = Source(body)
        assertNull(s.parser.resultFunction(s.span().copy(synthetic = true)))
        assertNull(s.parser.resultFunction(s.span().copy(digest = Digests.sha256("different-source"))))
        assertNull(s.parser.resultFunction(s.span().copy(sourceSet = "different-source-set")))
        assertNull(s.parser.resultFunction(s.span().copy(path = "different/Source.kt")))
    }

    @Test fun bounded_token_and_owner_sizes_abstain_instead_of_truncating_to_a_valid_prefix() {
        val tooLarge = Source(body.replace("    runCatching", " ".repeat(16_385) + "runCatching"))
        assertNull(tooLarge.parser.resultFunction(tooLarge.span()))
        val tooMany = Source(body.replace("runCatching", "a.".repeat(300) + "runCatching"))
        assertNull(tooMany.parser.resultFunction(tooMany.span()))
    }
}

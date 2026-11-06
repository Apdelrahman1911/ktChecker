package dev.ktchecker.providers.library

import dev.ktchecker.contracts.Digests
import dev.ktchecker.semantic.SemanticSource
import dev.ktchecker.semantic.SemanticSpan
import dev.ktchecker.semantic.SourceCoordinates
import kotlin.test.*

/** Syntax-only controls; none of these names, bodies or hashes grants semantic/API authority. */
class EmptyAssertFailsWithSyntaxTest {
    private data class Input(val source: SemanticSource, val bytes: ByteArray, val whole: SemanticSpan)
    private fun input(text: String): Input {
        val bytes = text.toByteArray()
        val source = SemanticSource("src/Test.kt", Digests.sha256(bytes), bytes.size.toLong(), ":fixture|test")
        val coordinates = SourceCoordinates(source, bytes)
        val lines = text.split(Regex("\\r\\n|\\r|\\n"))
        return Input(source, bytes, coordinates.span(0, 0, lines.lastIndex, lines.last().length))
    }
    private fun parse(text: String): EmptyAssertFailsWithSyntax.Shape? {
        val input = input(text)
        return EmptyAssertFailsWithSyntax(input.source, input.bytes).function(input.whole)
    }

    @Test fun acceptsOnlyClosedDirectEmptyAndCommentTriviaForms() {
        listOf(
            "fun missingAction() { assertFailsWith<IllegalArgumentException> { } }",
            "@Test fun missingAction() { alias<IllegalArgumentException>(){}; }",
            "@org.junit.jupiter.api.Test() public fun missingAction(): kotlin.Unit { kotlin.test.assertFailsWith<java.lang.IllegalArgumentException> {} }",
            "fun missingAction() { alias<Failure> { /* outer /* nested */ end */ } }",
            "fun missingAction() { alias<Failure> { // comment with } and {\n } }",
            "fun missingAction() { alias<Failure> {\r\n\t/* text */\r\n} }",
        ).forEach { assertNotNull(parse(it), it) }
    }

    @Test fun commentsDoNotConcealAnOperationOrBecomeStringSyntax() {
        listOf(
            "Unit", "kotlin.Unit", "throw IllegalArgumentException()", "operation()", "getter", "return@alias",
            "/* nothing? */ operation()", "/* close */ Unit /* reopen */", "\"/* */\"", "\"${'$'}{operation()}\"",
            "{ }", ";", "->", "_ ->", "// }\n operation()",
        ).forEach { body -> assertNull(parse("fun f() { alias<Failure> { $body } }"), body) }
        assertNull(parse("fun f() { alias<Failure> { /* unterminated } }"))
        assertNull(parse("fun f() { alias<Failure> { /* nested /* */ } }"))
        assertNull(parse("fun f() { alias<Failure> { // no closing body } }"))
    }

    @Test fun expectedFailureCatchesReachabilityAndAdditionalStatementsAbstain() {
        listOf(
            "fun f() { outer<AssertionError> { alias<Failure> { } } }",
            "fun f() { try { alias<Failure> { } } catch (expected: AssertionError) { } }",
            "fun f() { if (false) alias<Failure> { } }",
            "fun f() { return; alias<Failure> { } }",
            "fun f() { operation(); alias<Failure> { } }",
            "fun f() { alias<Failure> { }; operation() }",
            "fun f() = alias<Failure> { }",
            "fun f() { val action = { alias<Failure> { } } }",
            "fun f() { run { alias<Failure> { } } }",
            "fun f() { alias(Failure::class) { } }",
            "fun f() { alias<Failure>(message = operation()) { } }",
            "fun f() { alias<Failure>(block = { }) }",
            "suspend fun f() { alias<Failure> { } }",
            "fun f(value: Int) { alias<Failure> { } }",
            "fun Receiver.f() { alias<Failure> { } }",
            "fun <T> f() { alias<T> { } }",
            "@Test(expected = AssertionError::class) fun f() { alias<Failure> { } }",
            "fun `escaped test`() { alias<Failure> { } }",
        ).forEach { assertNull(parse(it), it) }
    }

    @Test fun exactUtf16Utf8AndCrlfSpansRetainTriviaWithoutNormalization() {
        val text = "/* \u00e9\ud83d\ude80 */\r\n@Test fun f() { kotlin.test.alias<Failure> { /* \u00e9 */ } }"
        val input = input(text)
        val shape = assertNotNull(EmptyAssertFailsWithSyntax(input.source, input.bytes).function(input.whole))
        assertEquals("{ /* \u00e9 */ }", input.bytes.copyOfRange(shape.block.startByte.toInt(), shape.block.endByte.toInt()).toString(Charsets.UTF_8))
        assertEquals(1, shape.block.startLine)
        assertTrue(shape.block.startByte > shape.block.startColumn)
        assertTrue(shape.matches(shape.call))
        assertTrue(shape.matches(shape.callElement))
        assertFalse(shape.matches(shape.block))
    }

    @Test fun mismatchedSpanCoordinatesDigestSourceSetAndBoundsDoNotParse() {
        val input = input("fun f() { alias<Failure> { } }")
        val parser = EmptyAssertFailsWithSyntax(input.source, input.bytes)
        listOf(input.whole.copy(digest = Digests.sha256("other")), input.whole.copy(path = "src/Other.kt"),
            input.whole.copy(sourceSet = ":fixture|main"), input.whole.copy(synthetic = true),
            input.whole.copy(endByte = input.whole.endByte - 1)).forEach { assertNull(parser.function(it)) }
        assertNull(parse("fun f() { alias<Failure> { /*" + "x".repeat(16_384) + "*/ } }"))
        assertNull(parse("fun f() { alias<Failure> { " + "/*".repeat(33) + "*/".repeat(33) + " } }"))
        assertFails { EmptyAssertFailsWithSyntax(input.source, input.bytes + byteArrayOf(0)) }
    }
}

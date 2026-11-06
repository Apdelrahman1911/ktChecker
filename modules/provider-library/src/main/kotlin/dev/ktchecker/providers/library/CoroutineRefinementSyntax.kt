package dev.ktchecker.providers.library

import dev.ktchecker.semantic.SemanticSource
import dev.ktchecker.semantic.SemanticSpan
import dev.ktchecker.semantic.SourceCoordinates

/**
 * A CLOSED syntax-only grammar, not a general Kotlin parser or an authority for any symbol.
 * Resolved facts and exact owner receipts select APIs. This parser supplies only source ranges,
 * immediate receiver/argument structure and positive decimal literals in those resolved ranges.
 * Anything outside the documented small grammar abstains: comments, strings/templates, Unicode
 * identifiers, escaped identifiers, annotations, control flow, locals, wrappers, extra statements,
 * explicit type arguments, named arguments, operators and general Kotlin expressions.
 */
internal class CoroutineRefinementSyntax(private val source: SemanticSource, bytes: ByteArray) {
    private val coordinates = SourceCoordinates(source, bytes)
    private val text = coordinates.text
    private val lineStarts = buildList {
        add(0)
        var at = 0
        while (at < text.length) {
            if (text[at] == '\r') {
                if (text.getOrNull(at + 1) == '\n') at++
                add(at + 1)
            } else if (text[at] == '\n') add(at + 1)
            at++
        }
    }

    data class ParsedCall(
        val whole: SemanticSpan,
        val element: SemanticSpan,
        val callee: SemanticSpan,
        val arguments: List<SemanticSpan>,
    ) {
        fun matches(span: SemanticSpan): Boolean = span == whole || span == element
    }

    data class LongCall(val call: ParsedCall, val value: Long)
    data class ResultShape(
        val runCatching: ParsedCall,
        val delay: LongCall,
        val block: SemanticSpan,
        val terminal: ParsedCall?,
        val failureBlock: SemanticSpan?,
    )

    fun simpleLongCall(span: SemanticSpan): LongCall? = parse(span) { parser ->
        val result = parser.longCall()
        parser.end()
        result
    }

    /** Only a captured zero-parameter suspend named function with an explicit expression body. */
    fun resultFunction(span: SemanticSpan): ResultShape? = parse(span) { parser ->
        parser.optionalVisibility()
        parser.take("suspend"); parser.take("fun"); parser.word()
        parser.take("("); parser.take(")"); parser.take(":"); parser.typeName(0); parser.take("=")
        val name = parser.qualifiedName()
        val open = parser.take("{")
        val delay = parser.longCall()
        parser.separatorAfter(delay.call.whole)
        parser.intConstant()
        val close = parser.take("}")
        val block = range(open.start, close.end)
        val run = ParsedCall(range(name.first.start, close.end), range(name.last.start, close.end),
            range(name.last.start, name.last.end), listOf(block))
        var terminal: ParsedCall? = null
        var failure: SemanticSpan? = null
        if (parser.optional(".")) {
            val member = parser.word()
            val end: Int
            val arguments: List<SemanticSpan>
            if (parser.optional("(")) {
                end = parser.take(")").end
                arguments = emptyList()
            } else {
                val start = parser.take("{").start
                parser.intConstant()
                end = parser.take("}").end
                failure = range(start, end)
                arguments = listOf(requireNotNull(failure))
            }
            terminal = ParsedCall(range(name.first.start, end), range(member.start, end),
                range(member.start, member.end), arguments)
        }
        parser.end()
        ResultShape(run, delay, block, terminal, failure)
    }

    private class OutsideGrammar : RuntimeException()
    private data class Token(val value: String, val start: Int, val end: Int, val kind: Kind)
    private enum class Kind { WORD, NUMBER, PUNCTUATION }
    private data class Name(val first: Token, val last: Token)

    private inline fun <T> parse(span: SemanticSpan, operation: (Parser) -> T): T? {
        if (span.synthetic || span.path != source.path || span.digest != source.digest || span.sourceSet != source.sourceSet ||
            span.endByte - span.startByte !in 1..16_384) return null
        return try { operation(Parser(lex(span))) } catch (_: OutsideGrammar) { null }
    }

    private fun offset(line: Int, column: Int): Int {
        val start = lineStarts.getOrNull(line) ?: throw OutsideGrammar()
        val result = start + column
        if (column < 0 || result !in 0..text.length) throw OutsideGrammar()
        return result
    }

    private fun range(start: Int, end: Int): SemanticSpan {
        fun point(at: Int): Pair<Int, Int> {
            var low = 0
            var high = lineStarts.lastIndex
            while (low < high) {
                val mid = (low + high + 1) / 2
                if (lineStarts[mid] <= at) low = mid else high = mid - 1
            }
            return low to (at - lineStarts[low])
        }
        val (line, column) = point(start)
        val (endLine, endColumn) = point(end)
        return coordinates.span(line, column, endLine, endColumn)
    }

    private fun lex(span: SemanticSpan): List<Token> {
        var at = offset(span.startLine, span.startColumn)
        val end = offset(span.endLine, span.endColumn)
        if (range(at, end) != span) throw OutsideGrammar()
        val tokens = mutableListOf<Token>()
        fun letter(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c == '_'
        while (at < end) {
            if (tokens.size >= 512) throw OutsideGrammar()
            val c = text[at]
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') { at++; continue }
            val start = at++
            val kind = when {
                letter(c) -> {
                    while (at < end && (letter(text[at]) || text[at] in '0'..'9')) at++
                    Kind.WORD
                }
                c in '0'..'9' -> {
                    while (at < end && text[at] in '0'..'9') at++
                    if (at < end && text[at] == 'L') at++
                    Kind.NUMBER
                }
                c in ".(){}:=;<>" -> Kind.PUNCTUATION
                else -> throw OutsideGrammar()
            }
            tokens += Token(text.substring(start, at), start, at, kind)
        }
        if (tokens.isEmpty()) throw OutsideGrammar()
        return tokens
    }

    private inner class Parser(private val tokens: List<Token>) {
        private var at = 0
        fun take(value: String): Token {
            val token = tokens.getOrNull(at) ?: throw OutsideGrammar()
            if (token.value != value) throw OutsideGrammar()
            at++
            return token
        }
        fun optional(value: String): Boolean = if (tokens.getOrNull(at)?.value == value) { at++; true } else false
        fun word(): Token {
            val token = tokens.getOrNull(at) ?: throw OutsideGrammar()
            if (token.kind != Kind.WORD) throw OutsideGrammar()
            at++
            return token
        }
        fun qualifiedName(): Name {
            val first = word()
            var last = first
            while (optional(".")) last = word()
            return Name(first, last)
        }
        fun optionalVisibility() {
            if (tokens.getOrNull(at)?.value in setOf("public", "private", "internal")) at++
        }
        fun typeName(depth: Int) {
            if (depth > 4) throw OutsideGrammar()
            qualifiedName()
            if (optional("<")) { typeName(depth + 1); take(">") }
        }
        fun intConstant(): Token {
            val token = tokens.getOrNull(at) ?: throw OutsideGrammar()
            if (token.kind != Kind.NUMBER || token.value.endsWith('L') || token.value.toIntOrNull() == null) throw OutsideGrammar()
            at++
            return token
        }
        fun longCall(): LongCall {
            val name = qualifiedName()
            take("(")
            val literal = tokens.getOrNull(at) ?: throw OutsideGrammar()
            if (literal.kind != Kind.NUMBER) throw OutsideGrammar()
            val value = literal.value.removeSuffix("L").toLongOrNull() ?: throw OutsideGrammar()
            if (value <= 0) throw OutsideGrammar()
            at++
            val close = take(")")
            return LongCall(ParsedCall(range(name.first.start, close.end), range(name.last.start, close.end),
                range(name.last.start, name.last.end), listOf(range(literal.start, literal.end))), value)
        }
        fun separatorAfter(span: SemanticSpan) {
            if (optional(";")) return
            val next = tokens.getOrNull(at) ?: throw OutsideGrammar()
            val previous = offset(span.endLine, span.endColumn)
            if ((previous until next.start).none { text[it] == '\n' || text[it] == '\r' }) throw OutsideGrammar()
        }
        fun end() { if (at != tokens.size) throw OutsideGrammar() }
    }
}

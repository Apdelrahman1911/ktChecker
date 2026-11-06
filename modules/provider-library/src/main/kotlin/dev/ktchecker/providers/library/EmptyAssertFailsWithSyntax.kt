package dev.ktchecker.providers.library

import dev.ktchecker.semantic.SemanticSource
import dev.ktchecker.semantic.SemanticSpan
import dev.ktchecker.semantic.SourceCoordinates

/**
 * CLOSED grammar, not Kotlin resolution or general effect/reachability analysis. An eligible
 * parameterless named function has a block body consisting solely of one reified call with one
 * empty trailing lambda, optionally an empty argument list and one trailing semicolon. Optional
 * no-argument annotations/visibility and an explicit simple return type are syntactic only.
 * Whitespace and properly terminated line/nested block comments are trivia. Strings, templates,
 * escaped/non-ASCII identifiers, Unit expressions, labels, branches, catches, wrappers, message
 * arguments, KClass overloads, lambda parameters and any other statement fail closed.
 *
 * No spelling here authorizes a callable/test: the provider joins every returned span to actual
 * resolved facts, immutable source bytes and owner-selected library/test-policy receipts.
 */
internal class EmptyAssertFailsWithSyntax(private val source: SemanticSource, bytes: ByteArray) {
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

    data class Shape(val call: SemanticSpan, val callElement: SemanticSpan, val block: SemanticSpan) {
        fun matches(span: SemanticSpan): Boolean = span == call || span == callElement
    }

    fun function(span: SemanticSpan): Shape? {
        if (span.synthetic || span.path != source.path || span.digest != source.digest || span.sourceSet != source.sourceSet ||
            span.endByte - span.startByte !in 1..16_384) return null
        return try {
            val parser = Parser(lex(span))
            var annotations = 0
            while (parser.optional("@")) {
                if (++annotations > 16) throw OutsideGrammar()
                parser.name()
                if (parser.optional("(")) parser.take(")")
            }
            parser.visibility()
            parser.take("fun"); parser.word(); parser.take("("); parser.take(")")
            if (parser.optional(":")) parser.name()
            parser.take("{")
            val callee = parser.name()
            parser.take("<"); parser.name(); parser.take(">")
            if (parser.optional("(")) parser.take(")")
            val open = parser.take("{")
            val close = parser.take("}")
            parser.optional(";")
            parser.take("}"); parser.end()
            Shape(range(callee.first.start, close.end), range(callee.second.start, close.end), range(open.start, close.end))
        } catch (_: OutsideGrammar) { null }
    }

    private class OutsideGrammar : RuntimeException()
    private data class Token(val value: String, val start: Int, val end: Int, val word: Boolean)

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
            if (c == '/' && at + 1 < end && text[at + 1] == '/') {
                at += 2
                while (at < end && text[at] != '\r' && text[at] != '\n') at++
                continue
            }
            if (c == '/' && at + 1 < end && text[at + 1] == '*') {
                at += 2
                var depth = 1
                while (depth > 0) {
                    if (at >= end) throw OutsideGrammar()
                    when {
                        at + 1 < end && text[at] == '/' && text[at + 1] == '*' -> {
                            if (++depth > 32) throw OutsideGrammar()
                            at += 2
                        }
                        at + 1 < end && text[at] == '*' && text[at + 1] == '/' -> { depth--; at += 2 }
                        else -> at++
                    }
                }
                continue
            }
            val start = at++
            val word = letter(c)
            if (word) while (at < end && (letter(text[at]) || text[at] in '0'..'9')) at++
            else if (c !in "@.(){}<>:;") throw OutsideGrammar()
            tokens += Token(text.substring(start, at), start, at, word)
        }
        return tokens
    }

    private class Parser(private val tokens: List<Token>) {
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
            if (!token.word) throw OutsideGrammar()
            at++
            return token
        }
        fun name(): Pair<Token, Token> {
            val first = word()
            var last = first
            var parts = 1
            while (optional(".")) {
                if (++parts > 16) throw OutsideGrammar()
                last = word()
            }
            return first to last
        }
        fun visibility() { if (tokens.getOrNull(at)?.value in setOf("public", "private", "internal")) at++ }
        fun end() { if (at != tokens.size) throw OutsideGrammar() }
    }
}

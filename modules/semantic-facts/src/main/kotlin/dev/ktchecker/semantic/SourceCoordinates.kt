package dev.ktchecker.semantic

import dev.ktchecker.contracts.Digests
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Maps compiler-provided line/UTF-16 columns, not guessed newline-normalized offsets. */
class SourceCoordinates(private val input: SemanticSource, bytes: ByteArray) {
    val text: String
    private val byteOffsets: IntArray
    private val lineStarts = mutableListOf(0)
    private val lineEnds = mutableListOf<Int>()

    init {
        // Detach before hashing/decoding so post-submission mutations cannot change the mapping.
        val frozen = bytes.copyOf()
        if (frozen.size.toLong() != input.size || Digests.sha256(frozen) != input.digest) {
            throw SemanticFactException("semantic_source_digest_mismatch", "Source bytes differ from the frozen request")
        }
        text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(frozen)).toString()
        } catch (e: Exception) {
            throw SemanticFactException("semantic_source_encoding_unavailable", "Only strict UTF-8 is currently supported", e)
        }
        if (text.startsWith('\uFEFF')) {
            throw SemanticFactException("semantic_source_bom_unqualified", "BOM-to-compiler coordinate mapping is not qualified")
        }
        byteOffsets = IntArray(text.length + 1) { -1 }
        var at = 0
        var byte = 0
        while (at < text.length) {
            byteOffsets[at] = byte
            val character = text[at]
            when {
                character.isHighSurrogate() -> { at += 2; byte += 4 }
                character.code < 0x80 -> { at++; byte++ }
                character.code < 0x800 -> { at++; byte += 2 }
                else -> { at++; byte += 3 }
            }
        }
        byteOffsets[text.length] = byte
        at = 0
        while (at < text.length) {
            if (text[at] == '\r' || text[at] == '\n') {
                lineEnds += at
                if (text[at] == '\r' && text.getOrNull(at + 1) == '\n') at++
                lineStarts += ++at
            } else at++
        }
        lineEnds += text.length
    }

    fun span(startLine: Int, startColumn: Int, endLine: Int, endColumn: Int, synthetic: Boolean = false): SemanticSpan {
        val start = point(startLine, startColumn)
        val end = point(endLine, endColumn)
        if (start > end) throw SemanticFactException("semantic_source_range_invalid", "Compiler range is reversed")
        return SemanticSpan(input.path, input.digest, input.sourceSet, startLine, startColumn, endLine, endColumn,
            start, end, synthetic)
    }

    private fun point(line: Int, column: Int): Int {
        if (line !in lineStarts.indices || column < 0 || column > lineEnds[line] - lineStarts[line]) {
            throw SemanticFactException("semantic_source_range_invalid", "Compiler line/column is outside exact input bytes")
        }
        val byte = byteOffsets[lineStarts[line] + column]
        if (byte < 0) throw SemanticFactException("semantic_source_range_invalid", "Compiler location bisects a surrogate pair")
        return byte
    }
}

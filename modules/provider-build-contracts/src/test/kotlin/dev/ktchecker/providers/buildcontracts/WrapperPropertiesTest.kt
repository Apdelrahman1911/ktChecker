package dev.ktchecker.providers.buildcontracts

import dev.ktchecker.contracts.Digests
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Parser/representation controls only; no selected wrapper consumer is executed. */
class WrapperPropertiesTest {
    private fun parse(body: String): ParsedWrapperProperties = parseWrapperProperties(
        (WrapperContractFixture.BASE_URL + body).toByteArray(Charsets.ISO_8859_1))

    @Test fun exact_declared_key_is_consumed() {
        assertEquals("abc", parse("distributionSha256Sum=abc\n").checksum)
    }

    @Test fun absence_is_not_an_empty_declared_value() {
        assertNull(parse("# no declaration\n").checksum)
        assertEquals("", parse("distributionSha256Sum=\n").checksum)
    }

    @Test fun escaped_unicode_key_is_recognized() {
        assertEquals("abc", parse("distributionSha\\u003256Sum=abc\n").checksum)
    }

    @Test fun escaped_non_special_key_character_uses_properties_semantics() {
        assertEquals("abc", parse("distributionSha256\\Sum=abc\n").checksum)
    }

    @Test fun a_continued_key_is_not_missed_by_line_scanning() {
        assertEquals("abc", parse("distributionSha256\\\n    Sum=abc\n").checksum)
    }

    @Test fun continuation_discards_indentation_without_inserting_a_character() {
        val digest = WrapperContractFixture.ACCEPTED_DIGEST
        assertEquals(digest, parse("distributionSha256Sum=${digest.take(32)}\\\n \t${digest.drop(32)}\n").checksum)
    }

    @Test fun unicode_escaped_value_is_compared_after_parsing() {
        val parsed = parse("distributionSha256Sum=" + "\\u0061".repeat(64) + "\n")
        assertEquals("a".repeat(64), parsed.checksum)
        assertTrue(WrapperConsumerProfile.acceptsChecksumShape(requireNotNull(parsed.checksum)))
    }

    @Test fun duplicate_key_last_value_wins() {
        val digest = WrapperContractFixture.ACCEPTED_DIGEST
        assertEquals(digest, parse("distributionSha256Sum=abc\ndistributionSha256Sum=$digest\n").checksum)
        assertEquals("abc", parse("distributionSha256Sum=$digest\ndistributionSha256Sum=abc\n").checksum)
    }

    @Test fun colon_and_whitespace_separators_are_supported() {
        assertEquals("abc", parse("distributionSha256Sum:abc\n").checksum)
        assertEquals("abc", parse("distributionSha256Sum abc\n").checksum)
    }

    @Test fun delimiter_whitespace_is_removed_but_trailing_value_whitespace_is_not() {
        assertEquals("abc ", parse(" \tdistributionSha256Sum \t= \tabc \n").checksum)
        assertFalse(WrapperConsumerProfile.acceptsChecksumShape(WrapperContractFixture.ACCEPTED_DIGEST + " "))
    }

    @Test fun escaped_leading_whitespace_is_part_of_the_value() {
        assertEquals(" abc", parse("distributionSha256Sum=\\ abc\n").checksum)
    }

    @Test fun comment_characters_inside_a_value_are_not_inline_comments() {
        assertEquals("abc#not-a-comment", parse("distributionSha256Sum=abc#not-a-comment\n").checksum)
        assertNull(parse("  # distributionSha256Sum=abc\n !distributionSha256Sum=abc\n").checksum)
    }

    @Test fun CR_and_CRLF_are_properties_line_endings() {
        assertEquals("abc", parse("distributionSha256Sum=abc\r\nother=x\r").checksum)
    }

    @Test fun raw_bytes_follow_ISO88591_not_UTF8_guessing() {
        val parsed = parse("distributionSha256Sum=\u00e9\n")
        assertEquals("\u00e9", parsed.checksum)
        assertFalse(WrapperConsumerProfile.acceptsChecksumShape(requireNotNull(parsed.checksum)))
    }

    @Test fun unknown_escapes_and_even_backslashes_are_not_normalized_by_a_regex() {
        assertEquals("abc\\", parse("distributionSha256Sum=abc\\\\\n").checksum)
        assertEquals("abcq", parse("distributionSha256Sum=abc\\q\n").checksum)
    }

    @Test fun malformed_unicode_abstains_instead_of_salvaging_a_declaration() {
        val problem = assertFailsWith<WrapperContractProblem> { parse("unrelated=\\u00zz\ndistributionSha256Sum=abc\n") }
        assertEquals(WrapperUnavailableReason.PROPERTIES_PARSER_UNAVAILABLE, problem.reason)
    }

    @Test fun missing_or_invalid_distribution_selection_abstains() {
        assertEquals(WrapperUnavailableReason.DISTRIBUTION_SELECTION_UNAVAILABLE,
            assertFailsWith<WrapperContractProblem> { parseWrapperProperties("distributionSha256Sum=abc".toByteArray()) }.reason)
        assertEquals(WrapperUnavailableReason.DISTRIBUTION_SELECTION_UNAVAILABLE,
            assertFailsWith<WrapperContractProblem> { parse("distributionUrl=not a URI\n") }.reason)
    }

    @Test fun URI_credentials_are_only_hashed_not_returned_by_the_parser() {
        val value = "https://private-user:private-password@example.invalid/gradle-9.6.1-bin.zip"
        val parsed = parse("distributionUrl=$value\ndistributionSha256Sum=abc\n")
        assertEquals(Digests.sha256(value), parsed.distributionUrlDigest)
        assertFalse(parsed.toString().contains("private-password"))
    }

    @Test fun oversize_properties_are_explicitly_unavailable() {
        val problem = assertFailsWith<WrapperContractProblem> { parseWrapperProperties(ByteArray(MAX_PROPERTIES_BYTES + 1)) }
        assertEquals(WrapperUnavailableReason.ANALYSIS_ENVELOPE_BOUND_EXCEEDED, problem.reason)
    }

    @Test fun exact_consumer_shape_is_lowercase_fixed_width_ASCII_only() {
        assertTrue(WrapperConsumerProfile.acceptsChecksumShape(WrapperContractFixture.ACCEPTED_DIGEST))
        assertTrue(WrapperConsumerProfile.acceptsChecksumShape("0".repeat(64)))
        listOf("abc", "", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64),
            "\uff10".repeat(64), "a".repeat(63) + "\t").forEach {
            assertFalse(WrapperConsumerProfile.acceptsChecksumShape(it))
        }
    }
}

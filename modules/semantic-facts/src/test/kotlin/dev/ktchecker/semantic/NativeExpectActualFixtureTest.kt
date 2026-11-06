package dev.ktchecker.semantic

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Decoder/serialization controls only: these strings and DTOs are NOT Native execution evidence. */
class NativeExpectActualFixtureTest {
    private val root = Path.of("synthetic Native fixture").toAbsolutePath().normalize()
    private val actualText = "package fixture.semantic.expectactual\n\nactual fun mismatchValue(): Int = 1\n"

    @Test fun exactLocatedNameBindsSelectedFileDigestAndReportedPoint() {
        val expectedByte = actualText.substringBefore("mismatchValue").toByteArray().size
        for (path in listOf(root.resolve(NATIVE_ACTUAL_SOURCE).toString(), NATIVE_ACTUAL_SOURCE,
            root.resolve(NATIVE_ACTUAL_SOURCE).toUri().toString())) {
            val observed = assertNotNull(parse(header(path = path)))
            assertEquals("stderr", observed.stream)
            assertEquals(NATIVE_EXPECT_ACTUAL_DIAGNOSTIC, observed.upstreamId)
            assertEquals("error", observed.severity)
            assertEquals("compiler_reported_point", observed.rangeKind)
            assertEquals(SemanticSpan(NATIVE_ACTUAL_SOURCE, source().digest, "nativeMain", 2, 11, 2, 11,
                expectedByte, expectedByte), observed.reportedPoint)
        }
    }

    @Test fun caretTextNeverInventsANumericDiagnosticEndpoint() {
        val point = assertNotNull(parse(header())).reportedPoint
        for (caret in listOf("^", "           ^^^^^^^^^^^^^", " ".repeat(11) + "^".repeat(80))) {
            val observed = assertNotNull(parse(header() + "\n" + actualText.lineSequence().elementAt(2) + "\n" + caret))
            assertEquals(point, observed.reportedPoint)
            assertEquals(observed.reportedPoint.startByte, observed.reportedPoint.endByte)
        }
    }

    @Test fun stdoutAndOptionalRenderedPhaseArePreservedWithoutMessageText() {
        val observed = assertNotNull(parse("", stdout = header(id = NATIVE_EXPECT_ACTUAL_DIAGNOSTIC + "{Frontend}")))
        assertEquals("stdout", observed.stream)
        assertEquals("Frontend", observed.renderedPhase)
        val encoded = ContractJson.encodeToString(observed)
        assertFalse(encoded.contains("PRIVATE diagnostic body"))
    }

    @Test fun messageMentionsQuotedNamesAndNonErrorHeadersDoNotCertifyTheMismatch() {
        val outputs = listOf(
            "PRIVATE diagnostic body mentions [" + NATIVE_EXPECT_ACTUAL_DIAGNOSTIC + "]",
            "error: [" + NATIVE_EXPECT_ACTUAL_DIAGNOSTIC + "] PRIVATE diagnostic body",
            header().replace("error: [", "error: quoted ["),
            header(severity = "warning"),
            header(severity = "info"),
            header(id = "ACTUAL_WITHOUT_EXPECT"),
            "val quoted = \"" + header() + "\"",
            "    " + header(),
            "",
        )
        for (output in outputs) assertNull(parse(output), output)
    }

    @Test fun wrongOrUnavailableSourceCoordinatesStayAbsent() {
        for (path in listOf(NATIVE_EXPECT_SOURCE, "other/nativeMain/Actual.kt",
            root.resolve("../Actual.kt").normalize().toString(), "file:///unrelated/Actual.kt")) {
            assertNull(parse(header(path = path)), path)
        }
        for ((line, column) in listOf(0 to 12, 3 to 0, 99 to 12, 3 to 100, 1 to 100)) {
            assertNull(parse(header(line = line, column = column)))
        }
        assertNull(parse(header().replace(":3:12:", ":2147483648:12:")))
        assertNull(parse(header().replace(":3:12:", ":3:2147483648:")))
        assertNull(parse(header().replace(":3:12:", ":three:12:")))
        assertNull(parseNativeExpectActualDiagnostic("", header(), Path.of("relative"),
            source(), actualText.toByteArray()))
        assertNull(parse(header(), input = source().copy(sourceSet = "commonMain")))
    }

    @Test fun multipleOrMixedUnlocatableTargetHeadersAreAmbiguous() {
        assertNull(parse(header() + "\n" + header()))
        assertNull(parse(header(), stdout = header()))
        assertNull(parse(header() + "\n" + header(column = 13)))
        assertNull(parse(header() + "\nerror: [" + NATIVE_EXPECT_ACTUAL_DIAGNOSTIC + "] PRIVATE diagnostic body"))
        assertNull(parse(header() + "\n" + header(path = NATIVE_EXPECT_SOURCE)))
    }

    @Test fun immutableSourceRowsCannotBePromotedFromAnEcho() {
        val output = header()
        val text = actualText + output + "\n"
        assertNull(parse(output, text = text))
        assertNull(parse(output + "\n" + header(column = 13), text = text))
    }

    @Test fun exactBytesAreCheckedBeforeAnyCoordinateIsAccepted() {
        val different = actualText.replace("= 1", "= 2")
        val problem = assertFailsWith<SemanticFactException> { parse(header(), text = different, input = source()) }
        assertEquals("semantic_source_digest_mismatch", problem.code)
    }

    @Test fun syntheticCrLfUtf16ControlMapsBytesButDoesNotClaimNativeUnicodeExecution() {
        val text = "// é🙂\r\n" + actualText.replace("\n", "\r\n")
        val observed = assertNotNull(parse(header(line = 4), text = text))
        val expectedByte = text.substringBefore("mismatchValue").toByteArray().size
        assertEquals(3, observed.reportedPoint.startLine)
        assertEquals(11, observed.reportedPoint.startColumn)
        assertEquals(expectedByte, observed.reportedPoint.startByte)
        assertEquals(expectedByte, observed.reportedPoint.endByte)
        assertNull(parse(header(line = 1, column = 6), text = text)) // Inside the surrogate pair.
    }

    @Test fun decoderBoundsNeverCertifyAnAcceptedPrefix() {
        assertNull(parse(header() + "\n" + "x".repeat(16_385)))
        assertNull(parse(header(), stdout = "x".repeat(2_097_153)))
    }

    @Test fun defaultJvmCaseWireRemainsUnchangedWhenDiagnosticsAreAbsent() {
        val value = SemanticFixtureRunner.CaseEvidence("positive", false, emptyMap(), syntheticBundle())
        val encoded = ContractJson.encodeToString(value)
        assertEquals(setOf("name", "passed", "assertions", "bundle"), ContractJson.parseToJsonElement(encoded).jsonObject.keys)
        assertEquals(encoded, ContractJson.encodeToString(value.copy(locatedDiagnostics = emptyList())))
        assertEquals(value, ContractJson.decodeFromString<SemanticFixtureRunner.CaseEvidence>(encoded))
    }

    @Test fun locatedDiagnosticStaysPrivateAndCannotLeakIntoMetadataSummary() {
        val diagnostic = assertNotNull(parse(header()))
        val case = SemanticFixtureRunner.CaseEvidence(NATIVE_EXPECT_ACTUAL_CASE, false,
            mapOf("exact_incompatible_return_type_diagnostic_point" to false), syntheticBundle("iosSimulatorArm64"), listOf(diagnostic))
        val privateValue = SemanticFixtureRunner.SuiteEvidence(state = "failed", mode = "native", target = "iosSimulatorArm64",
            observedAt = "2026-11-06T00:00:00Z", testedCommit = null, runner = emptyMap(), artifacts = emptyList(),
            cases = listOf(case), executions = emptyList(), reasons = emptyList())
        val privateWire = ContractJson.encodeToString(privateValue)
        assertTrue(privateWire.contains("locatedDiagnostics"))
        assertTrue(privateWire.contains(NATIVE_ACTUAL_SOURCE))
        assertTrue(privateWire.contains(NATIVE_EXPECT_ACTUAL_DIAGNOSTIC))
        val summaryWire = ContractJson.encodeToString(SemanticFixtureSummary.from(privateValue, Digests.sha256(privateWire)))
        for (privateField in listOf("locatedDiagnostics", "reportedPoint", NATIVE_ACTUAL_SOURCE,
            NATIVE_EXPECT_ACTUAL_DIAGNOSTIC, "PRIVATE diagnostic body")) assertFalse(summaryWire.contains(privateField))
    }

    private fun source(text: String = actualText): SemanticSource =
        SemanticSource(NATIVE_ACTUAL_SOURCE, Digests.sha256(text.toByteArray()), text.toByteArray().size.toLong(), "nativeMain")

    private fun header(path: String = root.resolve(NATIVE_ACTUAL_SOURCE).toString(), line: Int = 3, column: Int = 12,
                       severity: String = "error", id: String = NATIVE_EXPECT_ACTUAL_DIAGNOSTIC): String =
        "$path:$line:$column: $severity: [$id] PRIVATE diagnostic body"

    private fun parse(stderr: String, stdout: String = "", text: String = actualText, input: SemanticSource = source(text)) =
        parseNativeExpectActualDiagnostic(stdout, stderr, root, input, text.toByteArray())

    private fun syntheticBundle(target: String = "jvm") = SemanticFactBundle(
        Digests.sha256("request"), Digests.sha256("snapshot"), Digests.sha256("analysis"), target, "synthetic-no-execution",
        "incomplete", 1, listOf("semantic_compilation_failed"), emptyList(), false)
}

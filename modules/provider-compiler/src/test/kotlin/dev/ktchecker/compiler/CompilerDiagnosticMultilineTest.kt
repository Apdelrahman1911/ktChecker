package dev.ktchecker.compiler

import dev.ktchecker.contracts.*
import java.nio.file.Path
import kotlin.test.*

/**
 * Parser-unit strings derived from Kotlin v2.4.21 renderer SOURCE, plus
 * adversarial mutations. They are not retained stderr, compiler executions,
 * per-rule fixture qualification, or proof that a suggested fix was applied.
 */
class CompilerDiagnosticMultilineTest {
    @Test fun sourceDerivedP6ShapeKeepsMemberAndReportedPoint() {
        val parsed = parseRows(frame())
        assertFalse(parsed.malformed)
        assertTrue(parsed.rawDiagnostics.isEmpty())
        assertEquals("2.4.21", parsed.reportedVersion)
        val diagnostic = parsed.diagnostics.single()
        assertEquals(ID, diagnostic.upstreamId)
        assertEquals("error", diagnostic.severity)
        assertEquals("$SINGULAR\n$MEMBER", diagnostic.message)
        assertNull(diagnostic.renderedPhase)
        assertEquals(SourceLocation("agent-a/Main.kt", Digests.sha256(SOURCE_TEXT), 2, 0, 2, 0), diagnostic.location)
        assertEquals(HINT, CompilerDiagnosticParser.fixHint(diagnostic))
    }

    @Test fun sourceDerivedSimpleFunctionsPropertiesAndSuspendMembersKeepOrder() {
        val members = listOf("fun answer(value: Int, label: String?): Int", "val count: Int",
            "var label: String?", "suspend fun fetch(): String")
        val parsed = parseRows(frame(members, PLURAL), sourceFor(members))
        assertFalse(parsed.malformed)
        assertTrue(parsed.rawDiagnostics.isEmpty())
        assertEquals(PLURAL + "\n" + members.joinToString("\n"), parsed.diagnostics.single().message)
        assertEquals(HINT, CompilerDiagnosticParser.fixHint(parsed.diagnostics.single()))
    }

    @Test fun sourceDerivedMixedNewlinesAndUtf16CaretDoNotInventAnEndSpan() {
        val row = "/* 😀 */ class Broken1 : Contract1"
        val column = row.indexOf("class") + 1
        val text = "interface Contract1 { fun answer(): Int }\r\n$row\r\n"
        // The symbol-list separator is literal LF; CLI line separators may be CRLF.
        val output = BANNER + "\r\n" + header(column = column) + "\n" + MEMBER +
            "\r\n" + row + "\r\n" + " ".repeat(column - 1) + CARET + "\r\n"
        val parsed = CompilerDiagnosticParser().parse(output, mapOf(PATH to identity(text)))
        assertFalse(parsed.malformed)
        assertTrue(parsed.rawDiagnostics.isEmpty())
        val location = parsed.diagnostics.single().location!!
        assertEquals(column - 1, location.column)
        assertEquals(location.column, location.endColumn)
        assertEquals(location.line, location.endLine)
        assertEquals(Digests.sha256(text), location.fileDigest)
        assertEquals("$SINGULAR\n$MEMBER", parsed.diagnostics.single().message)
    }

    @Test fun fileUriParenthesizedHeaderAndEmittedPhaseStayCompatible() {
        val parsed = parseRows(listOf(BANNER,
            "${PATH.toUri()}: (2, 1): error: [$ID{FRONTEND}] $SINGULAR", MEMBER, SOURCE_ROW, "^"))
        assertFalse(parsed.malformed)
        assertTrue(parsed.rawDiagnostics.isEmpty())
        assertEquals("FRONTEND", parsed.diagnostics.single().renderedPhase)
        assertEquals("agent-a/Main.kt", parsed.diagnostics.single().location!!.path)
        assertEquals("$SINGULAR\n$MEMBER", parsed.diagnostics.single().message)
    }

    @Test fun emptyVersionOnlyAndOrdinarySingleLineOutputsStayUnchanged() {
        for (rows in listOf(emptyList(), listOf("", " "), listOf(BANNER))) {
            val parsed = parseRows(rows)
            assertFalse(parsed.malformed)
            assertTrue(parsed.diagnostics.isEmpty())
            assertTrue(parsed.rawDiagnostics.isEmpty())
        }
        for (tail in listOf(emptyList(), listOf(SOURCE_ROW, CARET))) {
            val parsed = parseRows(listOf(BANNER, header("UNRESOLVED_REFERENCE", message = "Unresolved reference 'x'.")) + tail)
            assertFalse(parsed.malformed)
            assertTrue(parsed.rawDiagnostics.isEmpty())
            assertEquals("Unresolved reference 'x'.", parsed.diagnostics.single().message)
            assertNull(CompilerDiagnosticParser.fixHint(parsed.diagnostics.single()))
        }
        val warning = parseRows(listOf(header("DIVISION_BY_ZERO", "warning", "Division by zero")))
        assertFalse(warning.malformed)
        assertEquals("warning", warning.diagnostics.single().severity)
        assertNull(CompilerDiagnosticParser.fixHint(warning.diagnostics.single()))
    }

    @Test fun quotesAndUnframedSignaturesRemainRawNotNamedEmission() {
        val rows = listOf("quoted [$ID] text, not a diagnostic header", MEMBER, SOURCE_ROW, CARET)
        val parsed = parseRows(rows)
        assertTrue(parsed.malformed)
        assertTrue(parsed.diagnostics.isEmpty())
        assertEquals(rows, parsed.rawDiagnostics.map { it.message })
        assertTrue(parsed.rawDiagnostics.all { it.upstreamId == null && it.location == null })
    }

    @Test fun unrelatedUnmappedAndRelatedAbstractIdsCannotOpenThisGrammar() {
        for (id in listOf("UNRESOLVED_REFERENCE", "FUTURE_UNKNOWN_DIAGNOSTIC",
                "ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED", "ABSTRACT_MEMBER_NOT_IMPLEMENTED_BY_ENUM_ENTRY")) {
            val parsed = parseRows(listOf(header(id), MEMBER, SOURCE_ROW, CARET))
            assertIncomplete(parsed)
            assertEquals(id, parsed.diagnostics.single().upstreamId)
            assertEquals(listOf(MEMBER, SOURCE_ROW, CARET), parsed.rawDiagnostics.map { it.message })
        }
    }

    @Test fun warningAndInfoSeveritiesDoNotOpenAnErrorMemberBlock() {
        for (severity in listOf("warning", "info")) {
            val parsed = parseRows(listOf(header(severity = severity), MEMBER, SOURCE_ROW, CARET))
            assertIncomplete(parsed)
            assertEquals(severity, parsed.diagnostics.single().severity)
            assertEquals(listOf(MEMBER, SOURCE_ROW, CARET), parsed.rawDiagnostics.map { it.message })
        }
    }

    @Test fun approximateHeaderWordingDoesNotAuthorizeContinuationSwallowing() {
        val messages = listOf(SINGULAR.replace("Class", "class"), SINGULAR.removeSuffix(":"),
            "$SINGULAR extra prose", SINGULAR.replace("abstract member:", "abstract class member:"),
            SINGULAR.replace("is not abstract", "is  not abstract"), "Unrecognized message ending in:")
        for (message in messages) {
            val parsed = parseRows(listOf(header(message = message), MEMBER, SOURCE_ROW, CARET))
            assertIncomplete(parsed, message)
            assertEquals(listOf(MEMBER, SOURCE_ROW, CARET), parsed.rawDiagnostics.map { it.message }, message)
        }
    }

    @Test fun malformedAndUnsupportedSignatureSyntaxStaysFailClosed() {
        val signatures = listOf("    $MEMBER", "\t$MEMBER", "$MEMBER = 42", "$MEMBER // note",
            "fun answer(: Int", "fun answer() Int", "val answer", "fun answer(value:Int): Int",
            "fun answer(value: Int,other: Int): Int", "fun answer(value: Int, ): Int",
            "fun answer(): List<Int>", "fun <T> answer(value: T): T", "fun String.answer(): Int",
            "context(value: Int) fun answer(): Int", "fun answer(block: () -> Int): Int",
            "fun answer(value: Int = ...): Int", "fun `answer`(): Int", "fun réponse(): Int",
            "fun answer(): String!", "fun answer(): ??? (error type)", "fun answer(): Outer.Inner",
            "@Marker $MEMBER", "compiler worker finished", "$MEMBER [UNRESOLVED_REFERENCE]")
        for (signature in signatures) {
            val parsed = parseRows(frame(listOf(signature)))
            assertIncomplete(parsed)
            assertEquals(listOf(SINGULAR, signature, SOURCE_ROW, CARET), parsed.rawDiagnostics.map { it.message }, signature)
        }
    }

    @Test fun anUnknownContinuationRollsBackAllBufferedMembers() {
        val members = listOf(MEMBER, "unknown compiler transport row", "val count: Int")
        val parsed = parseRows(frame(members, PLURAL))
        assertIncomplete(parsed, PLURAL)
        assertEquals(members + listOf(SOURCE_ROW, CARET), parsed.rawDiagnostics.map { it.message })
    }

    @Test fun adjacentSourceDiagnosticsSurviveAtEveryPendingStage() {
        val next = header("UNRESOLVED_REFERENCE", message = "Unresolved reference 'x'.")
        for (buffer in listOf(emptyList(), listOf(MEMBER), listOf(MEMBER, SOURCE_ROW))) {
            val parsed = parseRows(listOf(BANNER, header()) + buffer + listOf(next, SOURCE_ROW, CARET))
            assertTrue(parsed.malformed)
            assertEquals(listOf(ID, "UNRESOLVED_REFERENCE"), parsed.diagnostics.map { it.upstreamId })
            assertEquals(listOf(SINGULAR, "Unresolved reference 'x'."), parsed.diagnostics.map { it.message })
            assertEquals(buffer.ifEmpty { listOf(SINGULAR) }, parsed.rawDiagnostics.map { it.message })
            assertTrue(parsed.diagnostics.all { CompilerDiagnosticParser.fixHint(it) == null })
        }
    }

    @Test fun anInterruptedBlockDoesNotPreventTheNextCompleteMemberBlock() {
        val parsed = parseRows(listOf(BANNER, header(), MEMBER) + frame().drop(1))
        assertTrue(parsed.malformed)
        assertEquals(listOf(SINGULAR, "$SINGULAR\n$MEMBER"), parsed.diagnostics.map { it.message })
        assertEquals(listOf(MEMBER), parsed.rawDiagnostics.map { it.message })
        assertNull(CompilerDiagnosticParser.fixHint(parsed.diagnostics.first()))
        assertEquals(HINT, CompilerDiagnosticParser.fixHint(parsed.diagnostics.last()))
    }

    @Test fun globalDiagnosticsAndUnknownPathsAreNeverDowngradedOrSwallowed() {
        val interruptions = listOf(
            Triple("warning: [UNRECOGNIZED_WARNING] A warning", "warning", "UNRECOGNIZED_WARNING"),
            Triple("error: [$ID] A locationless error", "error", ID),
            Triple("info: [UNRECOGNIZED_INFO] Some info", "info", "UNRECOGNIZED_INFO"),
            Triple(header("UNRESOLVED_REFERENCE", path = "/outside/Main.kt", message = "Outside input"), "error", "UNRESOLVED_REFERENCE"),
        )
        for ((interruption, severity, upstreamId) in interruptions) {
            for (buffer in listOf(listOf(MEMBER), listOf(MEMBER, SOURCE_ROW))) {
                val parsed = parseRows(listOf(header()) + buffer + interruption)
                assertIncomplete(parsed)
                assertEquals(buffer, parsed.rawDiagnostics.dropLast(1).map { it.message })
                val raw = parsed.rawDiagnostics.last()
                assertEquals(severity, raw.severity)
                assertEquals(upstreamId, raw.upstreamId)
                assertNull(raw.location)
            }
        }
    }

    @Test fun versionBannersInterruptInsteadOfJoiningTheMemberMessage() {
        for (version in listOf("2.4.21", "2.4.22")) {
            val parsed = parseRows(listOf(BANNER, header(), MEMBER, "info: kotlinc-jvm $version (JRE 21)", SOURCE_ROW, CARET))
            assertIncomplete(parsed)
            assertEquals(version, parsed.reportedVersion)
            assertEquals(listOf(MEMBER, SOURCE_ROW, CARET), parsed.rawDiagnostics.map { it.message })
        }
    }

    @Test fun wrongSourceOrNonRendererCaretFencesNeverCommit() {
        val tails = listOf(listOf("class Different : Contract1", CARET), listOf(SOURCE_ROW, "~"),
            listOf(SOURCE_ROW, "^ ^"), listOf(SOURCE_ROW, " ^"), listOf(SOURCE_ROW, "\t^"),
            listOf(SOURCE_ROW, "^x"), listOf(SOURCE_ROW, "^^ "), listOf(SOURCE_ROW, " "),
            listOf(SOURCE_ROW, "^".repeat(SOURCE_ROW.length + 1)))
        for (tail in tails) {
            val parsed = parseRows(listOf(header(), MEMBER) + tail)
            assertIncomplete(parsed)
            assertEquals((listOf(MEMBER) + tail).filter { it.isNotBlank() }, parsed.rawDiagnostics.map { it.message }, tail.toString())
        }
    }

    @Test fun eofMissingMembersAndBlankTruncationsAreIncomplete() {
        val tails = listOf(emptyList(), listOf(MEMBER), listOf(MEMBER, SOURCE_ROW), listOf(SOURCE_ROW, CARET),
            listOf(MEMBER, "", SOURCE_ROW, CARET), listOf(MEMBER, SOURCE_ROW, ""))
        for (tail in tails) {
            val parsed = parseRows(listOf(header()) + tail)
            assertIncomplete(parsed)
            val headerEvidence = if (tail.firstOrNull() == MEMBER) emptyList() else listOf(SINGULAR)
            assertEquals(headerEvidence + tail.filter { it.isNotBlank() }, parsed.rawDiagnostics.map { it.message }, tail.toString())
            if (headerEvidence.isNotEmpty()) {
                assertEquals("error", parsed.rawDiagnostics.first().severity)
                assertEquals(ID, parsed.rawDiagnostics.first().upstreamId)
            }
        }
    }

    @Test fun singularPluralCountMismatchesNeverPartiallyAttach() {
        for ((headline, members) in listOf(SINGULAR to listOf(MEMBER, "val count: Int"),
                PLURAL to listOf(MEMBER), PLURAL to emptyList())) {
            val parsed = parseRows(frame(members, headline))
            assertIncomplete(parsed, headline)
            val headerEvidence = if (members.isEmpty()) listOf(headline) else emptyList()
            assertEquals(headerEvidence + members + listOf(SOURCE_ROW, CARET), parsed.rawDiagnostics.map { it.message })
        }
    }

    @Test fun exactSourceTextCannotMasqueradeAsTheFirstMember() {
        val parsed = parseRows(listOf(header(line = 1), MEMBER, CARET), "$MEMBER\n")
        assertIncomplete(parsed)
        assertEquals(listOf(SINGULAR, MEMBER, CARET), parsed.rawDiagnostics.map { it.message })
    }

    @Test fun headerShapedFrozenSourceDoesNotHideAGlobalWarning() {
        val row = "warning: [UNRECOGNIZED_WARNING] Keep this warning"
        val parsed = parseRows(listOf(header(), MEMBER, row, CARET), "interface Contract1 { fun answer(): Int }\n$row\n")
        assertIncomplete(parsed)
        assertEquals(listOf(MEMBER, "[UNRECOGNIZED_WARNING] Keep this warning", CARET), parsed.rawDiagnostics.map { it.message })
        assertEquals("warning", parsed.rawDiagnostics[1].severity)
        assertEquals("UNRECOGNIZED_WARNING", parsed.rawDiagnostics[1].upstreamId)
    }

    @Test fun completedBlocksDoNotHideLaterUnknownOutput() {
        val parsed = parseRows(frame() + "unrecognized output after a complete diagnostic")
        assertTrue(parsed.malformed)
        assertEquals("$SINGULAR\n$MEMBER", parsed.diagnostics.single().message)
        assertEquals(listOf("unrecognized output after a complete diagnostic"), parsed.rawDiagnostics.map { it.message })
        assertEquals(HINT, CompilerDiagnosticParser.fixHint(parsed.diagnostics.single()))
    }

    @Test fun memberCountLimitIsInclusiveAndOverflowRetainsEveryRow() {
        val members = (1..32).map { "fun answer$it(): Int" }
        val accepted = parseRows(frame(members, PLURAL), sourceFor(members))
        assertFalse(accepted.malformed)
        assertTrue(accepted.rawDiagnostics.isEmpty())
        assertEquals(PLURAL + "\n" + members.joinToString("\n"), accepted.diagnostics.single().message)
        val overflow = members + "fun answer33(): Int"
        val rejected = parseRows(frame(overflow, PLURAL), sourceFor(overflow))
        assertIncomplete(rejected, PLURAL)
        assertEquals(overflow + listOf(SOURCE_ROW, CARET), rejected.rawDiagnostics.map { it.message })
    }

    @Test fun aggregateMessageLimitIsInclusiveAndCheckedBeforeSignatureMatching() {
        val nameLength = CompilerDiagnosticParser.MAX_MESSAGE - SINGULAR.length - "\nfun ".length - "(): Int".length
        val member = "fun " + "a".repeat(nameLength) + "(): Int"
        val accepted = parseRows(frame(listOf(member)), sourceFor(listOf(member)))
        assertFalse(accepted.malformed)
        assertTrue(accepted.rawDiagnostics.isEmpty())
        assertEquals(CompilerDiagnosticParser.MAX_MESSAGE, accepted.diagnostics.single().message.length)
        assertEquals(HINT, CompilerDiagnosticParser.fixHint(accepted.diagnostics.single()))
        for (longMember in listOf(member.replace("(): Int", "a(): Int"), "fun " + "a".repeat(CompilerDiagnosticParser.MAX_MESSAGE) + "(): Int")) {
            val rejected = parseRows(frame(listOf(longMember)))
            assertIncomplete(rejected)
            assertEquals(listOf(SINGULAR, longMember.take(CompilerDiagnosticParser.MAX_MESSAGE), SOURCE_ROW, CARET),
                rejected.rawDiagnostics.map { it.message })
        }
    }

    @Test fun overlongHeadersUnknownPathsAndInvalidPointsNeverGainAHint() {
        val oversized = SINGULAR.replace("Broken1", "B".repeat(CompilerDiagnosticParser.MAX_MESSAGE))
        val longHeader = parseRows(listOf(header(message = oversized), MEMBER, SOURCE_ROW, CARET))
        assertTrue(longHeader.malformed)
        assertNull(CompilerDiagnosticParser.fixHint(longHeader.diagnostics.single()))
        for (badHeader in listOf(header(path = "/outside/Main.kt"), header(line = 0), header(line = 99),
                header(column = 0), header(column = SOURCE_ROW.length + 2))) {
            val parsed = parseRows(listOf(badHeader, MEMBER, SOURCE_ROW, CARET))
            assertTrue(parsed.malformed)
            assertTrue(parsed.diagnostics.isEmpty())
            assertEquals(ID, parsed.rawDiagnostics.first().upstreamId)
        }
    }

    @Test fun hintIsLimitedToACompleteErrorMemberListWithALocation() {
        val diagnostic = parseRows(frame()).diagnostics.single()
        assertEquals(HINT, CompilerDiagnosticParser.fixHint(diagnostic))
        for (ineligible in listOf(diagnostic.copy(upstreamId = "UNRESOLVED_REFERENCE"),
                diagnostic.copy(severity = "warning"), diagnostic.copy(severity = "info"), diagnostic.copy(location = null),
                diagnostic.copy(message = SINGULAR), diagnostic.copy(message = "$PLURAL\n$MEMBER"),
                diagnostic.copy(message = "$SINGULAR\n$MEMBER\nunknown"))) {
            assertNull(CompilerDiagnosticParser.fixHint(ineligible))
        }
    }

    @Test fun objectHintDoesNotRecommendMakingAnObjectAbstract() {
        val row = "object Broken1 : Contract1"
        val headline = SINGULAR.replace("Class", "Object")
        val parsed = parseRows(listOf(header(message = headline), MEMBER, row, CARET),
            "interface Contract1 { fun answer(): Int }\n$row\n")
        assertFalse(parsed.malformed)
        assertTrue(parsed.rawDiagnostics.isEmpty())
        assertEquals(HINT, CompilerDiagnosticParser.fixHint(parsed.diagnostics.single()))
        assertFalse(HINT.contains("make abstract"))
        assertFalse(HINT.contains("mark abstract"))
    }

    @Test fun aUsefulHintDoesNotTurnFutureVerificationIntoAnExecutedFix() {
        val diagnostic = parseRows(frame()).diagnostics.single()
        val input = AnalysisInput(SnapshotManifest(Digests.sha256("parser-only snapshot"), "workspace", "worktree",
            listOf(SnapshotFile("agent-a/Main.kt", Digests.sha256(SOURCE_TEXT), SOURCE_TEXT.toByteArray().size.toLong()))),
            "/sealed/parser-only", AnalysisContext(compilerVersion = "2.4.21", compilationFiles = listOf("agent-a/Main.kt")))
        val verification = CompilerVerification.recompile(input, diagnostic.upstreamId,
            Digests.sha256("parser-only toolchain identity; not executed"), listOf("agent-a/Main.kt")).single()
        assertEquals(HINT, CompilerDiagnosticParser.fixHint(diagnostic))
        assertEquals("not_run", verification.state)
        assertTrue(verification.command.isEmpty())
        for (required in listOf(ID, "new immutable snapshot", "actual exit-zero", "failed/unexecuted")) {
            assertTrue(required in verification.expected, required)
        }
    }

    @Test fun contradictoryBannersEndingInExpectedVersionFailTheProductionCoverageBarrier() {
        val parsed = parseRows(listOf("info: kotlinc-jvm 2.4.20 (JRE 21)", BANNER))
        assertEquals("2.4.21", parsed.reportedVersion)
        assertTrue(parsed.malformed)
        assertTrue(parsed.rawDiagnostics.isEmpty())
        assertTrue(parsed.diagnostics.isEmpty())
        assertTrue(KotlinJvmCompilerProvider.incompleteCompilerTask(0, parsed, parsed.rawDiagnostics))
    }

    @Test fun ordinaryValidAndNoDiagnosticOutputClearOnlyThisOneCoverageBarrier() {
        val cases = listOf(emptyList(), listOf(BANNER), listOf(BANNER, BANNER),
            listOf(BANNER, header("UNUSED_VARIABLE", "warning", "Variable is never used"), SOURCE_ROW, CARET))
        for (rows in cases) {
            val parsed = parseRows(rows)
            assertFalse(parsed.malformed)
            assertTrue(parsed.rawDiagnostics.isEmpty())
            // A false predicate is NOT a normalized status or certificate: the
            // actual provider still checks version/trust/context/qualification.
            assertFalse(KotlinJvmCompilerProvider.incompleteCompilerTask(0, parsed, parsed.rawDiagnostics))
        }
    }

    @Test fun malformedOutputRetainsParsedDiagnosticWhileTheCoverageBarrierFails() {
        val parsed = parseRows(listOf("info: kotlinc-jvm 2.4.20 (JRE 21)") + frame().drop(1) + BANNER)
        assertEquals("2.4.21", parsed.reportedVersion)
        assertTrue(parsed.malformed)
        assertTrue(parsed.rawDiagnostics.isEmpty())
        val diagnostic = parsed.diagnostics.single()
        assertEquals("$SINGULAR\n$MEMBER", diagnostic.message)
        assertEquals(HINT, CompilerDiagnosticParser.fixHint(diagnostic))
        assertTrue(KotlinJvmCompilerProvider.incompleteCompilerTask(0, parsed, parsed.rawDiagnostics))
        assertSame(diagnostic, parsed.diagnostics.single())
        // This checks predicate + parsed diagnostic retention, not a fabricated
        // ProviderResult, executed normalization, status or certificate.
    }

    @Test fun productionCoverageBarrierUsesNullableExitAndPostNormalizationRawEvidence() {
        val parsed = parseRows(listOf(BANNER))
        assertFalse(KotlinJvmCompilerProvider.incompleteCompilerTask(0, parsed, parsed.rawDiagnostics))
        assertTrue(KotlinJvmCompilerProvider.incompleteCompilerTask(1, parsed, parsed.rawDiagnostics))
        assertTrue(KotlinJvmCompilerProvider.incompleteCompilerTask(null, parsed, parsed.rawDiagnostics))
        for (severity in listOf("info", "warning", "error")) {
            val normalizedRaw = parsed.rawDiagnostics + RawDiagnostic("Unmapped diagnostic after normalization", severity, "FUTURE_UNKNOWN_DIAGNOSTIC")
            assertTrue(KotlinJvmCompilerProvider.incompleteCompilerTask(0, parsed, normalizedRaw))
            assertTrue(parsed.rawDiagnostics.isEmpty())
        }
    }

    private fun assertIncomplete(parsed: CompilerParseResult, headline: String = SINGULAR) {
        assertTrue(parsed.malformed)
        assertTrue(parsed.rawDiagnostics.isNotEmpty())
        assertEquals(headline, parsed.diagnostics.single().message)
        assertNull(CompilerDiagnosticParser.fixHint(parsed.diagnostics.single()))
    }

    private fun parseRows(rows: List<String>, text: String = SOURCE_TEXT): CompilerParseResult =
        CompilerDiagnosticParser().parse(rows.joinToString("\n"), mapOf(PATH to identity(text)))

    private fun identity(text: String): CompilerSourceIdentity =
        CompilerSourceIdentity("agent-a/Main.kt", Digests.sha256(text), text)

    private fun frame(members: List<String> = listOf(MEMBER), headline: String = SINGULAR): List<String> =
        listOf(BANNER, header(message = headline)) + members + listOf(SOURCE_ROW, CARET)

    private fun sourceFor(members: List<String>): String =
        "interface Contract1 { ${members.joinToString("; ")} }\n$SOURCE_ROW\n"

    private fun header(upstreamId: String = ID, severity: String = "error", message: String = SINGULAR,
        path: String = PATH.toString(), line: Int = 2, column: Int = 1): String =
        "$path:$line:$column: $severity: [$upstreamId] $message"

    companion object {
        private val PATH = Path.of("/isolated/agent-a/Main.kt")
        private const val BANNER = "info: kotlinc-jvm 2.4.21 (JRE 21)"
        private const val ID = "ABSTRACT_MEMBER_NOT_IMPLEMENTED"
        private const val SINGULAR = "Class 'Broken1' is not abstract and does not implement abstract member:"
        private const val PLURAL = "Class 'Broken1' is not abstract and does not implement abstract members:"
        private const val MEMBER = "fun answer(): Int"
        private const val SOURCE_ROW = "class Broken1 : Contract1"
        private const val SOURCE_TEXT = "interface Contract1 { fun answer(): Int }\n$SOURCE_ROW\n"
        private const val CARET = "^^^^^^^^^^^^^"
        private const val HINT = "Implement the listed abstract members with compatible overrides, or inherit compatible concrete " +
            "implementations. Recompile in the same context to verify."
    }
}

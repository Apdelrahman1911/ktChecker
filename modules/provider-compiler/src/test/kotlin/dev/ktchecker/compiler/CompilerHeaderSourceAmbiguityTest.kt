package dev.ktchecker.compiler

import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.SourceLocation
import java.nio.file.Path
import kotlin.test.*

/**
 * Pure parser-unit synthetic strings derived from renderer SOURCE and adversarial
 * mutations. The first fixture uses syntactically valid raw-string interpolation
 * with an intentionally unimplemented member. These are not observed compiler
 * output, compiler executions, provider normalization or fixture qualification.
 */
class CompilerHeaderSourceAmbiguityTest {
    @Test fun rawStringInterpolationCannotFabricateAnEarlierLocatedFinding() {
        val interpolation = "\${object : Contract1 {}}"
        val echo = header(upstreamId = FAKE_ID, line = 1, message = interpolation)
        val text = listOf("interface Contract1 { fun answer(): Int }", "val quoted = \"\"\"",
            echo, "\"\"\"").joinToString("\n", postfix = "\n")
        val column = echo.indexOf("object") + 1
        val parsed = parseRows(listOf(BANNER, memberHeader(line = 3, column = column,
            headline = OBJECT_SINGULAR), MEMBER, echo, " ".repeat(column - 1) + "^"), text)
        assertTrue(parsed.malformed)
        assertEquals("2.4.21", parsed.reportedVersion)
        val diagnostic = parsed.diagnostics.single()
        assertEquals(ABSTRACT_ID, diagnostic.upstreamId)
        assertEquals(OBJECT_SINGULAR, diagnostic.message)
        assertEquals(SourceLocation(RELATIVE_PATH, Digests.sha256(text), 3, column - 1, 3, column - 1),
            diagnostic.location)
        assertNull(CompilerDiagnosticParser.fixHint(diagnostic))
        assertClaimRaw(parsed, "[$FAKE_ID] $interpolation")
        assertTrue(parsed.rawDiagnostics.any { it.message == MEMBER })
        assertTrue(parsed.rawDiagnostics.any { it.message == " ".repeat(column - 1) + "^" })
        assertNoLimitMarker(parsed)
    }

    @Test fun colonParenthesizedAbsoluteAndUriEchoesAreAmbiguousInBothActiveBranches() {
        for (path in listOf(PATH.toString(), PATH.toUri().toString())) {
            for (parenthesized in listOf(false, true)) {
                for (memberBlock in listOf(false, true)) {
                    val echo = header(upstreamId = FAKE_ID, line = 1, message = QUOTED_MESSAGE,
                        path = path, parenthesized = parenthesized)
                    val first = if (memberBlock) memberHeader() else header()
                    val rows = listOf(BANNER, first) + (if (memberBlock) listOf(MEMBER) else emptyList()) +
                        listOf(echo, "^")
                    val parsed = parseRows(rows, sourceForRows(listOf(echo)))
                    assertTrue(parsed.malformed)
                    assertEquals(if (memberBlock) ABSTRACT_ID else REAL_ID, parsed.diagnostics.single().upstreamId)
                    assertEquals(2, parsed.diagnostics.single().location!!.line)
                    assertNull(CompilerDiagnosticParser.fixHint(parsed.diagnostics.single()))
                    assertClaimRaw(parsed)
                    // Quarantine is not a successful ordinary source/caret fence.
                    assertTrue(parsed.rawDiagnostics.any { it.message == "^" })
                }
            }
        }
    }

    @Test fun emptyPartialCompleteMismatchedAndOverfullMemberBuffersCannotCommitOnAnAmbiguousRow() {
        val echo = quotedHeader()
        val cases = listOf(
            SINGULAR to emptyList(), SINGULAR to listOf(MEMBER),
            SINGULAR to listOf(MEMBER, "val count: Int"),
            PLURAL to emptyList(), PLURAL to listOf(MEMBER),
            PLURAL to listOf(MEMBER, "val count: Int"),
            PLURAL to (1..33).map { "fun answer$it(): Int" },
        )
        for ((headline, members) in cases) {
            val parsed = parseRows(listOf(BANNER, memberHeader(headline = headline)) + members +
                listOf(echo, "^"), sourceForRows(listOf(echo)))
            assertTrue(parsed.malformed)
            assertEquals(headline, parsed.diagnostics.single().message)
            assertNull(CompilerDiagnosticParser.fixHint(parsed.diagnostics.single()))
            assertClaimRaw(parsed)
            if (members.isEmpty()) {
                val original = parsed.rawDiagnostics.single { it.message == headline }
                assertEquals(ABSTRACT_ID, original.upstreamId)
                assertEquals(2, original.location!!.line)
            }
        }
    }

    @Test fun unsupportedSignatureAbandonmentDoesNotForgetTheEchoOrHideALaterIndependentHeader() {
        val echo = quotedHeader()
        val unsupported = "fun answer(): List<Int>"
        val parsed = parseRows(listOf(BANNER, memberHeader(), unsupported, echo, "^",
            header(line = 1, message = "Later independent diagnostic")), sourceForRows(listOf(echo)))
        assertTrue(parsed.malformed)
        assertEquals(listOf(ABSTRACT_ID, REAL_ID), parsed.diagnostics.map { it.upstreamId })
        assertEquals(listOf(2, 1), parsed.diagnostics.map { it.location!!.line })
        assertEquals(SINGULAR, parsed.diagnostics.first().message)
        assertTrue(parsed.diagnostics.all { CompilerDiagnosticParser.fixHint(it) == null })
        assertTrue(parsed.rawDiagnostics.any { it.message == unsupported })
        assertClaimRaw(parsed)
    }

    @Test fun unknownContinuationAbandonmentDoesNotForgetEitherKindOfExpectedSourceRow() {
        val echo = quotedHeader()
        for (memberBlock in listOf(false, true)) {
            val first = if (memberBlock) memberHeader() else header()
            val rows = listOf(BANNER, first) + (if (memberBlock) listOf(MEMBER) else emptyList()) +
                listOf("unknown transport continuation", echo, "^", header(line = 1, message = "Independent tail"))
            val parsed = parseRows(rows, sourceForRows(listOf(echo)))
            assertTrue(parsed.malformed)
            assertEquals(listOf(if (memberBlock) ABSTRACT_ID else REAL_ID, REAL_ID),
                parsed.diagnostics.map { it.upstreamId })
            assertEquals("Independent tail", parsed.diagnostics.last().message)
            assertTrue(parsed.rawDiagnostics.any { it.message == "unknown transport continuation" })
            assertClaimRaw(parsed)
        }
    }

    @Test fun laterLocatedWitnessQuarantinesEarlierAndRepeatedEchoesButNotIndependentNeighbors() {
        val echo = quotedHeader()
        val rows = listOf(BANNER, echo, header(line = 1, message = "Independent prefix"),
            header(message = "Later located witness"), header(line = 1, message = "Independent middle"),
            echo, echo, header(line = 1, message = "Independent suffix"))
        val parsed = parseRows(rows, sourceForRows(listOf(echo)))
        assertTrue(parsed.malformed)
        assertEquals("2.4.21", parsed.reportedVersion)
        assertEquals(listOf("Independent prefix", "Later located witness", "Independent middle", "Independent suffix"),
            parsed.diagnostics.map { it.message })
        assertTrue(parsed.diagnostics.all { it.upstreamId == REAL_ID })
        assertClaimRaw(parsed, occurrences = 3)
        assertNoLimitMarker(parsed)
    }

    @Test fun evenALaterUnnamedResolvedHeaderSuppliesAWitnessWithoutDonatingItsLocationToTheEcho() {
        val echo = quotedHeader()
        val unnamed = "$PATH:2:1: warning: Unnamed located diagnostic"
        for (backward in listOf(false, true)) {
            val rows = listOf(BANNER) + (if (backward) listOf(echo, unnamed) else listOf(unnamed, echo)) +
                header(line = 1, message = "Independent tail")
            val parsed = parseRows(rows, sourceForRows(listOf(echo)))
            assertTrue(parsed.malformed)
            assertEquals("Independent tail", parsed.diagnostics.single().message)
            val original = parsed.rawDiagnostics.single { it.message == "Unnamed located diagnostic" }
            assertEquals("warning", original.severity)
            assertNull(original.upstreamId)
            assertEquals(2, original.location!!.line)
            assertClaimRaw(parsed)
        }
    }

    @Test fun aLocatedHeaderEqualToItsOwnResolvedSourceRowNeverPromotesEvenOnFirstOccurrence() {
        for (path in listOf(PATH.toString(), PATH.toUri().toString())) {
            for (parenthesized in listOf(false, true)) {
                val echo = header(upstreamId = FAKE_ID, message = QUOTED_MESSAGE,
                    path = path, parenthesized = parenthesized)
                val parsed = parseRows(listOf(BANNER, echo, echo, header(line = 1, message = "Independent tail")),
                    sourceForRows(listOf(echo)))
                assertTrue(parsed.malformed)
                assertEquals("Independent tail", parsed.diagnostics.single().message)
                assertEquals(1, parsed.diagnostics.single().location!!.line)
                assertClaimRaw(parsed, occurrences = 2)
            }
        }
    }

    @Test fun globalEchoesPreserveBoundedBodySeverityAndClaimedNameWithoutALocation() {
        for (severity in listOf("error", "warning", "info")) {
            for (memberBlock in listOf(false, true)) {
                val body = "[UNRECOGNIZED_GLOBAL] Keep this $severity"
                val echo = "$severity: $body"
                val first = if (memberBlock) memberHeader() else header()
                val rows = listOf(BANNER, first) + (if (memberBlock) listOf(MEMBER) else emptyList()) +
                    listOf(echo, "^")
                val parsed = parseRows(rows, sourceForRows(listOf(echo)))
                assertTrue(parsed.malformed)
                assertEquals(1, parsed.diagnostics.size)
                assertClaimRaw(parsed, body, severity, "UNRECOGNIZED_GLOBAL")
                assertNoLimitMarker(parsed)
            }
        }
    }

    @Test fun versionShapedSourceCannotEstablishOrReplaceVersionInEitherOrderingOrActiveBranch() {
        for (withInfo in listOf(false, true)) {
            for (memberBlock in listOf(false, true)) {
                for (withEarlierBanner in listOf(false, true)) {
                    for (backward in listOf(false, true)) {
                        val echo = (if (withInfo) "info: " else "") + "kotlinc-jvm 2.4.22 (JRE 21)"
                        val first = if (memberBlock) memberHeader() else header()
                        val witness = listOf(first) + (if (memberBlock) listOf(MEMBER) else emptyList())
                        val rows = (if (withEarlierBanner) listOf(BANNER) else emptyList()) +
                            (if (backward) listOf(echo) + witness else witness + echo) + "^"
                        val parsed = parseRows(rows, sourceForRows(listOf(echo)))
                        assertTrue(parsed.malformed)
                        assertEquals(if (withEarlierBanner) "2.4.21" else null, parsed.reportedVersion)
                        assertEquals(1, parsed.diagnostics.size)
                        val evidence = parsed.rawDiagnostics.single { it.message == echo }
                        assertEquals("info", evidence.severity)
                        assertNull(evidence.upstreamId)
                        assertNull(evidence.location)
                    }
                }
            }
        }
    }

    @Test fun aLaterWitnessAlsoQuarantinesAnIdenticalApparentlyGenuineEarlierBanner() {
        val parsed = parseRows(listOf(BANNER, header(message = "Later version-row witness"), BANNER),
            sourceForRows(listOf(BANNER)))
        assertTrue(parsed.malformed)
        assertNull(parsed.reportedVersion)
        assertEquals("Later version-row witness", parsed.diagnostics.single().message)
        assertEquals(2, parsed.rawDiagnostics.count { it.message == BANNER && it.location == null })
        assertNoLimitMarker(parsed)
    }

    @Test fun longEchoBodiesAndHugeClaimedIdentifiersCannotEscapeRawFieldBounds() {
        val hugeName = "A".repeat(CompilerDiagnosticParser.MAX_MESSAGE + 7)
        val cases = listOf(quotedHeader("x".repeat(2 * CompilerDiagnosticParser.MAX_MESSAGE)),
            header(upstreamId = hugeName, line = 1, message = "Oversized claimed name"))
        for (echo in cases) {
            val parsed = parseRows(listOf(BANNER, header(), echo), sourceForRows(listOf(echo)))
            assertNoPromotionOnOverflow(parsed)
            val evidence = parsed.rawDiagnostics.single { it.message.startsWith(if (echo == cases.first()) "[$FAKE_ID]" else "[AAAA") }
            assertEquals(CompilerDiagnosticParser.MAX_MESSAGE, evidence.message.length)
            if (echo == cases.last()) assertNull(evidence.upstreamId)
        }
    }

    @Test fun perRowLimitIsInclusiveAndOverflowQuarantinesBothSidesWithoutInventingAnUnseenEcho() {
        val rows = listOf(BANNER, header(line = 1, message = "Independent prefix"),
            header(message = "Located witness"), header(line = 1, message = "Independent suffix"))
        val exact = headerShapedRow(CompilerDiagnosticParser.MAX_MESSAGE, "exact row")
        val accepted = parseRows(rows, sourceForRows(listOf(exact)))
        assertFalse(accepted.malformed)
        assertEquals(3, accepted.diagnostics.size)
        assertTrue(accepted.rawDiagnostics.isEmpty())
        assertEquals("2.4.21", accepted.reportedVersion)
        val tooLong = exact + "x"
        val rejected = parseRows(rows, sourceForRows(listOf(tooLong)))
        assertNoPromotionOnOverflow(rejected)
        // Only actual output and the fixed parser-control marker become raw.
        assertEquals(rows.size + 1, rejected.rawDiagnostics.size)
        assertTrue(rejected.rawDiagnostics.none { it.upstreamId == FAKE_ID })
    }

    @Test fun distinctRowCountLimitIsInclusiveAndSixtyFifthRowQuarantinesTheWholeParse() {
        for (count in listOf(64, 65)) {
            val candidates = (1..count).map { quotedHeader("Count witness $it") }
            val messages = listOf(header(line = 1, message = "Independent prefix")) +
                candidates.indices.map { header(line = it + 2, message = "Located witness $it") } +
                header(line = 1, message = "Independent suffix")
            val parsed = parseRows(listOf(BANNER) + messages, sourceForRows(candidates))
            if (count == 64) {
                assertFalse(parsed.malformed)
                assertEquals(messages.size, parsed.diagnostics.size)
                assertTrue(parsed.rawDiagnostics.isEmpty())
                assertEquals("2.4.21", parsed.reportedVersion)
            } else {
                assertNoPromotionOnOverflow(parsed)
                assertEquals(messages.size + 2, parsed.rawDiagnostics.size)
            }
        }
    }

    @Test fun aggregateUtf16LimitIsInclusiveAndIndependentOfTheDistinctRowCount() {
        val exact = (1..4).map { headerShapedRow(CompilerDiagnosticParser.MAX_MESSAGE, "Aggregate witness $it") }
        assertEquals(4 * CompilerDiagnosticParser.MAX_MESSAGE, exact.sumOf { it.length })
        for (candidates in listOf(exact, exact + quotedHeader("Fifth small witness"))) {
            val messages = listOf(header(line = 1, message = "Independent prefix")) +
                candidates.indices.map { header(line = it + 2, message = "Located witness $it") } +
                header(line = 1, message = "Independent suffix")
            val parsed = parseRows(listOf(BANNER) + messages, sourceForRows(candidates))
            if (candidates.size == 4) {
                assertFalse(parsed.malformed)
                assertEquals(messages.size, parsed.diagnostics.size)
                assertTrue(parsed.rawDiagnostics.isEmpty())
            } else {
                assertNoPromotionOnOverflow(parsed)
            }
        }
    }

    @Test fun limitsCountUtf16UnitsNotUtf8BytesOrUnicodeCodePoints() {
        val prefix = quotedHeader("Unicode witness ")
        val remaining = CompilerDiagnosticParser.MAX_MESSAGE - prefix.length
        val exact = prefix + "😀".repeat(remaining / 2) + "x".repeat(remaining % 2)
        assertEquals(CompilerDiagnosticParser.MAX_MESSAGE, exact.length)
        assertTrue(exact.toByteArray(Charsets.UTF_8).size > CompilerDiagnosticParser.MAX_MESSAGE)
        val rows = listOf(BANNER, header(), header(line = 1, message = "Independent tail"))
        val accepted = parseRows(rows, sourceForRows(listOf(exact)))
        assertFalse(accepted.malformed)
        assertEquals(2, accepted.diagnostics.size)
        assertTrue(accepted.rawDiagnostics.isEmpty())
        assertNoPromotionOnOverflow(parseRows(rows, sourceForRows(listOf(exact + "😀"))))
    }

    @Test fun duplicateWitnessesDoNotSpendCountOrAggregateBudget() {
        val candidates = listOf(headerShapedRow(CompilerDiagnosticParser.MAX_MESSAGE, "Repeated witness"),
            quotedHeader("Second distinct witness"))
        val messages = listOf(header(line = 1, message = "Independent prefix")) +
            (1..130).map { header(message = "Repeated located witness $it") } +
            listOf(header(line = 3, message = "Second distinct located witness"),
                header(line = 1, message = "Independent suffix"))
        val parsed = parseRows(listOf(BANNER) + messages, sourceForRows(candidates))
        assertFalse(parsed.malformed)
        assertEquals(messages.size, parsed.diagnostics.size)
        assertTrue(parsed.rawDiagnostics.isEmpty())
        assertEquals("2.4.21", parsed.reportedVersion)
    }

    @Test fun overflowMakesEverySupportedMessageShapeRawIncludingVersionsAndUriHeaders() {
        val oversized = headerShapedRow(CompilerDiagnosticParser.MAX_MESSAGE + 1, "Oversized witness")
        val tail = listOf(header(line = 1, message = "Colon tail"),
            header(line = 1, message = "Parenthesized tail", parenthesized = true),
            header(line = 1, message = "URI colon tail", path = PATH.toUri().toString()),
            header(line = 1, message = "URI parenthesized tail", path = PATH.toUri().toString(), parenthesized = true),
            "warning: [UNRECOGNIZED_WARNING] Global tail", "info: kotlinc-jvm 2.4.22 (JRE 21)",
            "kotlinc-jvm 2.4.23 (JRE 21)")
        val parsed = parseRows(listOf(BANNER, header(line = 1, message = "Independent prefix"), header()) + tail,
            sourceForRows(listOf(oversized)))
        assertNoPromotionOnOverflow(parsed)
        assertClaimRaw(parsed, "[UNRECOGNIZED_WARNING] Global tail", "warning", "UNRECOGNIZED_WARNING")
        assertTrue(parsed.rawDiagnostics.any { it.message == BANNER })
        assertTrue(parsed.rawDiagnostics.any { it.message == "info: kotlinc-jvm 2.4.22 (JRE 21)" })
        assertTrue(parsed.rawDiagnostics.any { it.message == "kotlinc-jvm 2.4.23 (JRE 21)" })
        for (message in listOf("Colon tail", "Parenthesized tail", "URI colon tail", "URI parenthesized tail")) {
            assertClaimRaw(parsed, "[$REAL_ID] $message", upstreamId = REAL_ID)
        }
    }

    @Test fun aLateOverBudgetWitnessCannotLeaveItsEarlierMatchingHeaderActionable() {
        val shortEcho = quotedHeader("Backward over-budget collision")
        val longEcho = headerShapedRow(CompilerDiagnosticParser.MAX_MESSAGE + 1, "Backward oversized collision")
        val cases = listOf(
            (1..64).map { quotedHeader("Earlier count witness $it") } + shortEcho,
            (1..4).map { headerShapedRow(CompilerDiagnosticParser.MAX_MESSAGE, "Earlier aggregate witness $it") } + shortEcho,
            listOf(longEcho),
        )
        for (candidates in cases) {
            val earlierEcho = candidates.last()
            val rows = listOf(BANNER, earlierEcho, header(line = 1, message = "Apparently independent prefix")) +
                candidates.indices.map { header(line = it + 2, message = "Later located witness $it") } +
                header(line = 1, message = "Independent suffix")
            val parsed = parseRows(rows, sourceForRows(candidates))
            assertNoPromotionOnOverflow(parsed)
            assertEquals(1, parsed.rawDiagnostics.count { it.upstreamId == FAKE_ID })
            assertTrue(parsed.rawDiagnostics.any { it.message == "[$REAL_ID] Apparently independent prefix" })
            assertTrue(parsed.rawDiagnostics.any { it.message == "[$REAL_ID] Independent suffix" })
        }
    }

    @Test fun noncollidingAdjacentHeadersStillWinAtEveryPendingMemberStage() {
        val next = header(message = "Independent adjacent diagnostic")
        for (buffer in listOf(emptyList(), listOf(MEMBER), listOf(MEMBER, MEMBER_SOURCE))) {
            val parsed = parseRows(listOf(BANNER, memberHeader()) + buffer + listOf(next, MEMBER_SOURCE, "^"))
            assertTrue(parsed.malformed)
            assertEquals(listOf(ABSTRACT_ID, REAL_ID), parsed.diagnostics.map { it.upstreamId })
            assertEquals(listOf(SINGULAR, "Independent adjacent diagnostic"), parsed.diagnostics.map { it.message })
            assertEquals(buffer.ifEmpty { listOf(SINGULAR) }, parsed.rawDiagnostics.map { it.message })
            assertTrue(parsed.diagnostics.all { CompilerDiagnosticParser.fixHint(it) == null })
            assertNoLimitMarker(parsed)
        }
    }

    @Test fun unrelatedUnknownOutputDoesNotRemoveAnAlreadyCompletedMemberHint() {
        val parsed = parseRows(listOf(BANNER, memberHeader(), MEMBER, MEMBER_SOURCE, "^",
            "unrecognized transport trailer", header(line = 1, message = "Independent tail")))
        assertTrue(parsed.malformed)
        assertEquals(listOf(ABSTRACT_ID, REAL_ID), parsed.diagnostics.map { it.upstreamId })
        assertEquals("$SINGULAR\n$MEMBER", parsed.diagnostics.first().message)
        assertEquals(HINT, CompilerDiagnosticParser.fixHint(parsed.diagnostics.first()))
        assertEquals(listOf("unrecognized transport trailer"), parsed.rawDiagnostics.map { it.message })
        assertNoLimitMarker(parsed)
    }

    @Test fun fittingCensusPreservesIndependentHintsBeforeAndAfterAnAmbiguousRow() {
        val echo = quotedHeader()
        val text = sourceForRows(listOf(MEMBER_SOURCE, echo))
        val frame = listOf(memberHeader(), MEMBER, MEMBER_SOURCE, "^")
        val parsed = parseRows(listOf(BANNER) + frame + echo + header(line = 3, message = "Located echo witness") + frame, text)
        assertTrue(parsed.malformed)
        assertEquals(listOf(ABSTRACT_ID, REAL_ID, ABSTRACT_ID), parsed.diagnostics.map { it.upstreamId })
        assertEquals(HINT, CompilerDiagnosticParser.fixHint(parsed.diagnostics.first()))
        assertEquals(HINT, CompilerDiagnosticParser.fixHint(parsed.diagnostics.last()))
        assertClaimRaw(parsed)
        assertNoLimitMarker(parsed)
    }

    @Test fun censusOverflowAlsoQuarantinesAnApparentlyCompletedPrefixMemberBlock() {
        val oversized = headerShapedRow(CompilerDiagnosticParser.MAX_MESSAGE + 1, "Late oversized witness")
        val parsed = parseRows(listOf(BANNER, memberHeader(), MEMBER, MEMBER_SOURCE, "^",
            header(line = 3, message = "Late overflowing witness"), header(line = 1, message = "Independent tail")),
            sourceForRows(listOf(MEMBER_SOURCE, oversized)))
        assertNoPromotionOnOverflow(parsed)
        assertClaimRaw(parsed, "[$ABSTRACT_ID] $SINGULAR", upstreamId = ABSTRACT_ID)
        assertTrue(parsed.rawDiagnostics.any { it.message == MEMBER })
        assertTrue(parsed.rawDiagnostics.any { it.message == MEMBER_SOURCE })
        assertTrue(parsed.rawDiagnostics.any { it.message == "^" })
    }

    @Test fun noncollidingVersionAndGlobalInterruptionsKeepTheirExistingSemantics() {
        val changedBanner = "info: kotlinc-jvm 2.4.22 (JRE 21)"
        val version = parseRows(listOf(BANNER, memberHeader(), MEMBER, changedBanner, MEMBER_SOURCE, "^"))
        assertTrue(version.malformed)
        assertEquals("2.4.22", version.reportedVersion)
        assertEquals(SINGULAR, version.diagnostics.single().message)
        assertEquals(listOf(MEMBER, MEMBER_SOURCE, "^"), version.rawDiagnostics.map { it.message })
        assertNoLimitMarker(version)
        val global = parseRows(listOf(BANNER, memberHeader(), MEMBER,
            "warning: [UNRECOGNIZED_WARNING] Genuine global interruption", MEMBER_SOURCE, "^"))
        assertTrue(global.malformed)
        assertEquals("2.4.21", global.reportedVersion)
        assertEquals(SINGULAR, global.diagnostics.single().message)
        assertClaimRaw(global, "[UNRECOGNIZED_WARNING] Genuine global interruption", "warning", "UNRECOGNIZED_WARNING")
        assertNoLimitMarker(global)
    }

    @Test fun candidatesAndOverflowLatchBelongToOneParseNotTheParserInstance() {
        val parser = CompilerDiagnosticParser()
        for (echo in listOf(quotedHeader(), headerShapedRow(CompilerDiagnosticParser.MAX_MESSAGE + 1, "Overflowing prior parse"))) {
            val first = parseRows(listOf(BANNER, header(), echo), sourceForRows(listOf(echo)), parser)
            assertTrue(first.malformed)
            val independent = parseRows(listOf(BANNER, quotedHeader()), SOURCE_TEXT, parser)
            assertFalse(independent.malformed)
            assertEquals(FAKE_ID, independent.diagnostics.single().upstreamId)
            assertEquals(1, independent.diagnostics.single().location!!.line)
            assertEquals("2.4.21", independent.reportedVersion)
            assertTrue(independent.rawDiagnostics.isEmpty())
        }
    }

    @Test fun unverifiedLocationsAndUnreferencedSourceRowsDoNotPopulateTheCensus() {
        val echo = quotedHeader()
        val text = sourceForRows(listOf(echo))
        val invalid = listOf(header(line = 0), header(line = 99), header(column = 0),
            header(column = echo.length + 2), header(path = "/outside/Main.kt"),
            "$PATH:999999999999999999:1: error: [$REAL_ID] Unrepresentable line")
        for (bad in invalid) {
            val parsed = parseRows(listOf(BANNER, bad, echo), text)
            assertTrue(parsed.malformed)
            assertEquals(FAKE_ID, parsed.diagnostics.single().upstreamId)
            assertEquals(1, parsed.diagnostics.single().location!!.line)
            assertTrue(parsed.rawDiagnostics.all { it.location == null })
            assertNoLimitMarker(parsed)
        }
        // An unreferenced header-shaped source row is not a global source scan
        // witness. These synthetic strings do not authenticate arbitrary output.
        val unreferenced = parseRows(listOf(BANNER, echo), text)
        assertFalse(unreferenced.malformed)
        assertEquals(FAKE_ID, unreferenced.diagnostics.single().upstreamId)
        assertTrue(unreferenced.rawDiagnostics.isEmpty())
    }

    @Test fun exactRowEqualityRatherThanAnUpstreamIdFilterDeterminesQuarantine() {
        val echo = header(upstreamId = ABSTRACT_ID, line = 1, message = QUOTED_MESSAGE)
        val parsed = parseRows(listOf(BANNER, memberHeader(), MEMBER, echo, "^"), sourceForRows(listOf(echo)))
        assertTrue(parsed.malformed)
        assertEquals(ABSTRACT_ID, parsed.diagnostics.single().upstreamId)
        assertEquals(2, parsed.diagnostics.single().location!!.line)
        assertEquals(SINGULAR, parsed.diagnostics.single().message)
        assertNull(CompilerDiagnosticParser.fixHint(parsed.diagnostics.single()))
        assertClaimRaw(parsed, "[$ABSTRACT_ID] $QUOTED_MESSAGE", upstreamId = ABSTRACT_ID)
    }

    private fun assertClaimRaw(parsed: CompilerParseResult, body: String = "[$FAKE_ID] $QUOTED_MESSAGE",
        severity: String = "error", upstreamId: String = FAKE_ID, occurrences: Int = 1) {
        val matching = parsed.rawDiagnostics.filter {
            it.message == body.take(CompilerDiagnosticParser.MAX_MESSAGE) && it.severity == severity && it.upstreamId == upstreamId
        }
        assertEquals(occurrences, matching.size)
        assertTrue(matching.all { it.location == null })
    }

    private fun assertNoLimitMarker(parsed: CompilerParseResult) {
        assertTrue(parsed.rawDiagnostics.none { it.message == LIMIT_MESSAGE })
    }

    private fun assertNoPromotionOnOverflow(parsed: CompilerParseResult) {
        assertTrue(parsed.malformed)
        assertTrue(parsed.diagnostics.isEmpty())
        assertNull(parsed.reportedVersion)
        val marker = parsed.rawDiagnostics.single { it.message == LIMIT_MESSAGE }
        assertEquals("info", marker.severity)
        assertNull(marker.upstreamId)
        assertNull(marker.location)
        assertTrue(parsed.rawDiagnostics.all { it.location == null })
        assertTrue(parsed.rawDiagnostics.all { it.message.length <= CompilerDiagnosticParser.MAX_MESSAGE })
        assertTrue(parsed.rawDiagnostics.all { (it.upstreamId?.length ?: 0) <= CompilerDiagnosticParser.MAX_MESSAGE })
    }

    private fun parseRows(rows: List<String>, text: String = SOURCE_TEXT,
        parser: CompilerDiagnosticParser = CompilerDiagnosticParser()): CompilerParseResult =
        parser.parse(rows.joinToString("\n"), mapOf(PATH to CompilerSourceIdentity(RELATIVE_PATH, Digests.sha256(text), text)))

    private fun sourceForRows(rows: List<String>): String =
        (listOf(PLAIN_ROW) + rows).joinToString("\n", postfix = "\n")

    private fun memberHeader(line: Int = 2, column: Int = 1, headline: String = SINGULAR): String =
        header(upstreamId = ABSTRACT_ID, message = headline, line = line, column = column)

    private fun quotedHeader(message: String = QUOTED_MESSAGE): String =
        header(upstreamId = FAKE_ID, line = 1, message = message)

    private fun headerShapedRow(length: Int, marker: String): String {
        val prefix = quotedHeader(marker + " ")
        require(length >= prefix.length)
        return prefix + "x".repeat(length - prefix.length)
    }

    private fun header(upstreamId: String = REAL_ID, severity: String = "error", message: String = "Located witness",
        path: String = PATH.toString(), line: Int = 2, column: Int = 1, parenthesized: Boolean = false): String =
        if (parenthesized) "$path: ($line, $column): $severity: [$upstreamId] $message"
        else "$path:$line:$column: $severity: [$upstreamId] $message"

    companion object {
        private val PATH = Path.of("/isolated/agent-a/Main.kt")
        private const val RELATIVE_PATH = "agent-a/Main.kt"
        private const val BANNER = "info: kotlinc-jvm 2.4.21 (JRE 21)"
        private const val REAL_ID = "DIVISION_BY_ZERO"
        private const val FAKE_ID = "UNRESOLVED_REFERENCE"
        private const val ABSTRACT_ID = "ABSTRACT_MEMBER_NOT_IMPLEMENTED"
        private const val QUOTED_MESSAGE = "Quoted source, not an independent emission"
        private const val SINGULAR = "Class 'Broken1' is not abstract and does not implement abstract member:"
        private const val PLURAL = "Class 'Broken1' is not abstract and does not implement abstract members:"
        private const val OBJECT_SINGULAR = "Object '<anonymous>' is not abstract and does not implement abstract member:"
        private const val MEMBER = "fun answer(): Int"
        private const val MEMBER_SOURCE = "class Broken1 : Contract1"
        private const val PLAIN_ROW = "val independent = 1"
        private const val SOURCE_TEXT = "$PLAIN_ROW\n$MEMBER_SOURCE\n"
        private const val LIMIT_MESSAGE = "Compiler header/source ambiguity tracking limit exceeded"
        private const val HINT = "Implement the listed abstract members with compatible overrides, or inherit compatible concrete " +
            "implementations. Recompile in the same context to verify."
    }
}

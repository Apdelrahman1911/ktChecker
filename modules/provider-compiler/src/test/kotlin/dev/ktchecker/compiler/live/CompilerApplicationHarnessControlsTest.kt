package dev.ktchecker.compiler.live

import dev.ktchecker.compiler.CompilerRuleMapping
import dev.ktchecker.compiler.CompilerRuleRegistry
import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import dev.ktchecker.workerprotocol.CompilerJobWire as CompilerWire
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.*

/**
 * SYNTHETIC controls only. No test invokes runOnce/open/analyze, a native helper, a worker/compiler,
 * ProcessBuilder, a process factory, a toolchain capture or a selectable fake execution backend.
 * Passing these controls supplies no real compiler, deadline enforcement, custody or launch credit.
 */
class CompilerApplicationHarnessControlsTest {
    @Test fun syntheticConsentIsOneShot() {
        val ingress = syntheticIngress()
        val consent = CompilerApplicationHarnessConsent.confirm(ingress, CompilerApplicationHarnessConsent.CONFIRMATION)
        consent.consume(ingress)
        fails(HarnessCode.CONSENT_USED) { consent.consume(ingress) }
    }

    @Test fun syntheticEqualIngressDoesNotTransferConsent() {
        val original = syntheticIngress()
        val equalData = syntheticIngress()
        val consent = CompilerApplicationHarnessConsent.confirm(original, CompilerApplicationHarnessConsent.CONFIRMATION)
        fails(HarnessCode.CONSENT_BINDING) { consent.consume(equalData) }
        consent.consume(original) // A wrong target did not consume someone else's retained grant.
    }

    @Test fun syntheticConfirmationIsExactNotTruthy() {
        for (text in listOf("true", "yes", CompilerApplicationHarnessConsent.CONFIRMATION + " ", "")) {
            fails(HarnessCode.CONSENT_TEXT) { CompilerApplicationHarnessConsent.confirm(syntheticIngress(), text) }
        }
    }

    @Test fun syntheticIngressRejectsFreshBudgetWorkaroundsAndUnpinnedPaths() {
        fails(HarnessCode.INGRESS) { CompilerApplicationHarnessIngress(syntheticPath("/synthetic/manifest.json"), digest('1'), 0, 1) }
        fails(HarnessCode.INGRESS) { CompilerApplicationHarnessIngress(syntheticPath("/synthetic/manifest.json"), digest('1'), 1, 3_600_001) }
        fails(HarnessCode.INGRESS) { CompilerApplicationHarnessIngress(syntheticPath("/synthetic/manifest.json"), "not-pinned", 1, 1) }
        fails(HarnessCode.PATH) { CompilerApplicationHarnessIngress("relative.json", digest('1'), 1, 1) }
        fails(HarnessCode.PATH) { CompilerApplicationHarnessIngress(syntheticPath("/synthetic/../manifest.json"), digest('1'), 1, 1) }
    }

    @Test fun syntheticClockRetainsTheOriginalDeadlineObject() {
        val original = OwnedApplicationDeadline.afterMillis(60_000)
        val clock = HarnessClock(original, CancellationToken.NONE)
        assertSame(original, clock.generation)
        clock.check(original) // Synthetic deadline data only; no application/provider call.
    }

    @Test fun syntheticClockRejectsCancellationAndThrowingCallbacks() {
        val original = OwnedApplicationDeadline.afterMillis(60_000)
        fails(HarnessCode.CANCELLED) { HarnessClock(original, CancellationToken { true }).check() }
        fails(HarnessCode.CANCELLATION_CHECK) {
            HarnessClock(original, CancellationToken { throw IllegalStateException("SYNTHETIC callback failure") }).check()
        }
    }

    @Test fun syntheticClockRejectsInterruptSetByFalseCancellationCallback() {
        assertFalse(Thread.currentThread().isInterrupted)
        val original = OwnedApplicationDeadline.afterMillis(60_000)
        val clock = HarnessClock(original, CancellationToken { Thread.currentThread().interrupt(); false })
        try {
            fails(HarnessCode.INTERRUPTED) { clock.check() }
            assertTrue(Thread.currentThread().isInterrupted) // The production checkpoint did not clear it.
        } finally {
            Thread.interrupted() // Test-only restoration of the interrupt THIS test set above.
        }
    }

    @Test fun syntheticSecondCannotBeConstructedInitially() {
        val order = HarnessOrder()
        assertFalse(order.mayConstructSecond())
        fails(HarnessCode.ATTEMPT_BINDING) { order.begin(2) }
    }

    @Test fun syntheticEnteredCallIsNotReturnedOrPassed() {
        val order = HarnessOrder()
        order.begin(1)
        assertFalse(order.mayConstructSecond())
        fails(HarnessCode.RESULT_PROFILE) { order.passed(1) }
        fails(HarnessCode.ATTEMPT_BINDING) { order.begin(2) }
    }

    @Test fun syntheticReturnedCallStillRequiresFirstGate() {
        val order = HarnessOrder()
        order.begin(1); order.returned(1)
        assertFalse(order.mayConstructSecond())
        fails(HarnessCode.ATTEMPT_BINDING) { order.begin(2) }
        order.passed(1)
        assertTrue(order.mayConstructSecond())
    }

    @Test fun syntheticOrderingRejectsDuplicateForeignAndThirdCalls() {
        val order = HarnessOrder()
        fails(HarnessCode.ATTEMPT_BINDING) { order.returned(1) }
        order.begin(1)
        fails(HarnessCode.ATTEMPT_BINDING) { order.begin(1) }
        fails(HarnessCode.ATTEMPT_BINDING) { order.returned(2) }
        order.returned(1)
        fails(HarnessCode.ATTEMPT_BINDING) { order.returned(1) }
        order.passed(1); order.begin(2); order.returned(2); order.passed(2)
        assertFalse(order.mayConstructSecond())
        fails(HarnessCode.ATTEMPT_BINDING) { order.begin(3) }
        fails(HarnessCode.ATTEMPT_BINDING) { order.begin(1) }
    }

    @Test fun syntheticCanonicalPinnedManifestParsesAsDataOnly() {
        val manifest = parseSynthetic(syntheticManifest())
        assertEquals(listOf(1), manifest.dependencies)
        assertEquals("kotlin-compiler-embeddable-2.4.21.jar", manifest.compiler[0].path.fileName.toString())
        assertEquals(hostAnchors().keys, manifest.anchors.keys)
        assertTrue(Digests.valid(manifest.implementationDigest))
    }

    @Test fun syntheticRawManifestDigestCannotBeLearnedAsApproval() {
        val bytes = canonical(syntheticManifest())
        fails(HarnessCode.EXPECTED_PIN) { HarnessManifest.parse(bytes, digest('0')) }
        val changed = bytes.clone().also { it[it.lastIndex] = ' '.code.toByte() }
        fails(HarnessCode.EXPECTED_PIN) { HarnessManifest.parse(changed, Digests.sha256(bytes)) }
    }

    @Test fun syntheticDuplicateJsonKeysFailEvenWithMatchingRawDigest() {
        val raw = Digests.canonical(syntheticManifest()).replace("\"schema\":", "\"schema\":\"duplicate\",\"schema\":").toByteArray()
        fails(HarnessCode.MANIFEST) { HarnessManifest.parse(raw, Digests.sha256(raw)) }
    }

    @Test fun syntheticNoncanonicalWhitespaceFailsEvenWhenPinned() {
        val raw = (" " + Digests.canonical(syntheticManifest()) + "\n").toByteArray()
        fails(HarnessCode.MANIFEST) { HarnessManifest.parse(raw, Digests.sha256(raw)) }
    }

    @Test fun syntheticUtf8AndDepthAreBoundedBeforeJsonRecursion() {
        val invalidUtf8 = byteArrayOf(0xc3.toByte(), 0x28)
        fails(HarnessCode.MANIFEST) { HarnessManifest.parse(invalidUtf8, Digests.sha256(invalidUtf8)) }
        val nested = ("[".repeat(17) + "0" + "]".repeat(17)).toByteArray()
        fails(HarnessCode.LIMIT) { HarnessManifest.parse(nested, Digests.sha256(nested)) }
        fails(HarnessCode.LIMIT) { boundJsonLexically("\"" + "x".repeat(24_577) + "\"") }
        fails(HarnessCode.MANIFEST) { boundJsonLexically("{\"unclosed") }
    }

    @Test fun syntheticOversizedManifestFailsBeforeParsing() {
        val bytes = ByteArray(HarnessManifest.MAX_BYTES + 1) { ' '.code.toByte() }
        fails(HarnessCode.EXPECTED_PIN) { HarnessManifest.parse(bytes, Digests.sha256(bytes)) }
    }

    @Test fun syntheticUnknownAndMissingManifestFieldsAreNotIgnored() {
        val base = syntheticManifest()
        fails(HarnessCode.MANIFEST) { parseSynthetic(edit(base, "extra_permission", JsonPrimitive(true))) }
        fails(HarnessCode.MANIFEST) { parseSynthetic(JsonObject(base - "host")) }
        val ns = base["namespace"]!!.jsonObject
        fails(HarnessCode.MANIFEST) { parseSynthetic(edit(base, "namespace", edit(ns, "runtime_authorized", JsonPrimitive(true)))) }
    }

    @Test fun syntheticNumericStringsAndNoncanonicalNumbersFail() {
        val base = syntheticManifest()
        val bridge = base["bridge"]!!.jsonObject
        fails(HarnessCode.MANIFEST) { parseSynthetic(edit(base, "bridge", edit(bridge, "size", JsonPrimitive("128")))) }
        val raw = Digests.canonical(base).replace("\"size\":128", "\"size\":1.28e2").toByteArray()
        fails(HarnessCode.MANIFEST) { HarnessManifest.parse(raw, Digests.sha256(raw)) }
    }

    @Test fun syntheticCompilerVersionAndExactDigestAreMandatory() {
        val base = syntheticManifest()
        val entries = base["compiler"]!!.jsonArray
        fails(HarnessCode.TOOLCHAIN) { parseSynthetic(edit(base, "compiler", JsonArray(listOf(
            edit(entries[0].jsonObject, "digest", JsonPrimitive(digest('5'))), entries[1])))) }
        fails(HarnessCode.TOOLCHAIN) { parseSynthetic(edit(base, "compiler", JsonArray(listOf(
            edit(entries[0].jsonObject, "path", JsonPrimitive(syntheticPath("/approved/compiler/kotlin-compiler-embeddable-2.4.20.jar"))), entries[1])))) }
    }

    @Test fun syntheticDependencyIndexesAreOrderedUniqueAndAllowed() {
        val base = syntheticManifest()
        fails(HarnessCode.MANIFEST) { parseSynthetic(edit(base, "dependencies", JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(1))))) }
        fails(HarnessCode.MANIFEST) { parseSynthetic(edit(base, "dependencies", JsonArray(listOf(JsonPrimitive(2))))) }
        fails(HarnessCode.MANIFEST) { parseSynthetic(edit(base, "dependencies", JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(0))))) }
        fails(HarnessCode.TOOLCHAIN) { parseSynthetic(edit(base, "dependencies", JsonArray(listOf(JsonPrimitive(0))))) }
    }

    @Test fun syntheticClasspathOrderChangesImplementationIdentity() {
        val base = syntheticManifest()
        val host = base["host"]!!.jsonObject
        val first = host["classpath"]!!.jsonArray.single().jsonObject
        val other = edit(first, "path", JsonPrimitive(syntheticPath("/approved/host-other")))
        val forward = parseSynthetic(edit(base, "host", edit(host, "classpath", JsonArray(listOf(first, other)))))
        val reverse = parseSynthetic(edit(base, "host", edit(host, "classpath", JsonArray(listOf(other, first)))))
        assertNotEquals(forward.implementationDigest, reverse.implementationDigest)
    }

    @Test fun syntheticUnknownOrOutOfRangeCodeAnchorsFail() {
        val base = syntheticManifest()
        val host = base["host"]!!.jsonObject
        val anchors = host["anchors"]!!.jsonObject
        fails(HarnessCode.HOST_CLOSURE) { parseSynthetic(edit(base, "host", edit(host, "anchors", edit(anchors, "foreign", JsonPrimitive(0))))) }
        fails(HarnessCode.HOST_CLOSURE) { parseSynthetic(edit(base, "host", edit(host, "anchors", edit(anchors, "harness", JsonPrimitive(1))))) }
        fails(HarnessCode.HOST_CLOSURE) { parseSynthetic(edit(base, "host", edit(host, "registry_index", JsonPrimitive(1)))) }
    }

    @Test fun syntheticOverlappingHostEntriesFailClosed() {
        val base = syntheticManifest()
        val host = base["host"]!!.jsonObject
        val entry = host["classpath"]!!.jsonArray.single().jsonObject
        fails(HarnessCode.HOST_CLOSURE) { parseSynthetic(edit(base, "host", edit(host, "classpath", JsonArray(listOf(
            entry, edit(entry, "path", JsonPrimitive(syntheticPath("/approved/host/nested")))))))) }
    }

    @Test fun syntheticRetainedWritesCannotOverlapApprovedCodeOrJdk() {
        val base = syntheticManifest()
        for (path in listOf("/approved/host", "/approved", "/approved/jdk/inside")) {
            fails(HarnessCode.ALIAS) { parseSynthetic(edit(base, "retained_parent", edit(base["retained_parent"]!!.jsonObject, "path", JsonPrimitive(syntheticPath(path))))) }
        }
    }

    @Test fun syntheticJdkExecutableHasNoWindowsOrPathFallback() {
        val base = syntheticManifest()
        val jdk = base["jdk"]!!.jsonObject
        fails(HarnessCode.TOOLCHAIN) { parseSynthetic(edit(base, "jdk", edit(jdk, "java_executable", JsonPrimitive(syntheticPath("/approved/jdk/bin/java.exe"))))) }
        fails(HarnessCode.TOOLCHAIN) { parseSynthetic(edit(base, "jdk", edit(jdk, "java_executable", JsonPrimitive(syntheticPath("/usr/java"))))) }
    }

    @Test fun syntheticHostVmAllowsOnlyFixedProfileArguments() {
        assertTrue(allowedHostVmArgument("-Xmx512m"))
        assertTrue(allowedHostVmArgument("-Dfile.encoding=UTF-8"))
        for (argument in listOf("-javaagent:/owner/agent.jar", "-agentpath:/owner/native.so", "--patch-module=x=y",
            "--module-path=/owner/modules", "-Xbootclasspath/a:/owner/code", "@/owner/args", "-Djava.system.class.loader=OwnerLoader")) {
            assertFalse(allowedHostVmArgument(argument))
        }
    }

    @Test fun syntheticBridgeAllowlistNamesOnlyFourReviewedClassFamilies() {
        assertTrue(allowedHarnessBridgeEntry("dev/ktchecker/workerprotocol/ApplicationWire.class", false))
        assertTrue(allowedHarnessBridgeEntry("dev/ktchecker/workerprotocol/ApplicationWire\$Hello.class", false))
        assertTrue(allowedHarnessBridgeEntry("dev/ktchecker/compilerworker/live/PinnedKotlinCompilerAdapter\$1.class", false))
        assertTrue(allowedHarnessBridgeEntry("META-INF/MANIFEST.MF", false))
        assertTrue(allowedHarnessBridgeEntry("dev/ktchecker/compilerworker/live/", true))
    }

    @Test fun syntheticBridgeAllowlistRejectsCompilerPayloadAndSuffixLookalikes() {
        for (entry in listOf("org/jetbrains/kotlin/cli/jvm/K2JVMCompiler.class", "META-INF/INDEX.LIST", "extra.jar",
            "dev/ktchecker/workerprotocol/ApplicationWireFake.class", "dev/ktchecker/workerprotocol/ApplicationWire\$.class",
            "dev/ktchecker/workerprotocol/ApplicationWire\$Inner/Other.class", "dev/ktchecker/compilerworker/CompilerWarmWorkerMain.class")) {
            assertFalse(allowedHarnessBridgeEntry(entry, false), entry)
        }
        assertFalse(allowedHarnessBridgeEntry("unreviewed/", true))
    }

    @Test fun syntheticRegistrySizeAboveOneMiBRemainsAdmissible() {
        assertTrue(harnessRegistrySizeAllowed(1_048_577L))
    }

    @Test fun syntheticPinnedCurrentRegistrySizeFitsNamedBound() {
        // Exact committed source-data size pinned in source-inputs.json, not a run admission.
        val registrySizeAtPinnedBaseline = 1_376_206L
        assertEquals(2_097_152L, HARNESS_MAX_REGISTRY_BYTES)
        assertTrue(harnessRegistrySizeAllowed(registrySizeAtPinnedBaseline))
    }

    @Test fun syntheticRegistryNamedBoundaryIsInclusive() {
        assertTrue(harnessRegistrySizeAllowed(1L))
        assertTrue(harnessRegistrySizeAllowed(HARNESS_MAX_REGISTRY_BYTES))
    }

    @Test fun syntheticRegistryRejectsZeroNegativeAndOverLimitSizes() {
        for (size in listOf(0L, -1L, HARNESS_MAX_REGISTRY_BYTES + 1L, Long.MAX_VALUE)) {
            assertFalse(harnessRegistrySizeAllowed(size))
        }
    }

    @Test fun syntheticArchiveManifestSpellingRejectsCaseAliases() {
        assertTrue(allowedHarnessManifestEntrySpelling("META-INF/MANIFEST.MF"))
        for (name in listOf("META-INF/manifest.mf", "meta-inf/MANIFEST.MF", "Meta-Inf/Manifest.Mf", "meta-inf/manifest.mf")) {
            assertFalse(allowedHarnessManifestEntrySpelling(name), name)
        }
    }

    @Test fun syntheticArchiveManifestSpellingRejectsDirectoryAliases() {
        for (name in listOf("META-INF/MANIFEST.MF/", "META-INF/manifest.mf/", "Meta-Inf/Manifest.Mf///")) {
            assertFalse(allowedHarnessManifestEntrySpelling(name), name)
        }
    }

    @Test fun syntheticArchiveCaseVariantManifestPairCannotPassNameGate() {
        val entries = listOf("META-INF/MANIFEST.MF", "META-INF/manifest.mf")
        assertFalse(entries.all(::allowedHarnessManifestEntrySpelling))
        assertFalse(entries.reversed().all(::allowedHarnessManifestEntrySpelling))
    }

    @Test fun syntheticArchiveManifestSpellingAllowsUnrelatedNames() {
        for (name in listOf("META-INF/", "compiler-rule-map.json", "example/Main.class", "META-INF/other.mf",
            "META-INF/MANIFEST.MF.backup")) {
            assertTrue(allowedHarnessManifestEntrySpelling(name), name)
        }
    }

    @Test fun syntheticDiagnosticProfileUsesActualRegistryMapping() {
        val mapping = mapping()
        val input = syntheticInput(1, mapping)
        val value = syntheticResult(input, mapping, 1)
        assertEquals("UNRESOLVED_REFERENCE", mapping.upstreamId)
        verifyHarnessPublicProfile(1, input, value, mapping)
        assertEquals("failed", value.status) // Semantic diagnostic failure, NOT transport failure.
        assertFalse(value.complete())
        assertFalse(value.coverage.single().qualified)
    }

    @Test fun syntheticActualRequestEvidenceIsPreservedNotReconstructed() {
        val mapping = mapping()
        val input = syntheticInput(1, mapping)
        val value = syntheticResult(input, mapping, 1)
        val row = CompilerApplicationHarnessAttempt(1, null, input, null, null, true, value, false, null)
        assertSame(value, row.providerResult)
        val before = ContractJson.encodeToJsonElement(value)
        verifyHarnessPublicProfile(1, input, value, mapping)
        assertEquals(before, ContractJson.encodeToJsonElement(checkNotNull(row.providerResult)))
        assertEquals(digest('d'), row.providerResult!!.findings.single().evidence.single { it.kind == "application-request" }.digest)
    }

    @Test fun syntheticCleanProfileCreatesNoRequestDigestEvidence() {
        val mapping = mapping()
        val input = syntheticInput(2, mapping)
        val clean = syntheticResult(input, mapping, 2)
        verifyHarnessPublicProfile(2, input, clean, mapping)
        assertEquals("partial", clean.status)
        assertTrue(clean.findings.isEmpty())
        assertFalse(Digests.canonical(ContractJson.encodeToJsonElement(clean)).contains("application-request"))
    }

    @Test fun syntheticCompleteOrQualifiedResultCannotPass() {
        val mapping = mapping()
        val input = syntheticInput(1, mapping)
        val value = syntheticResult(input, mapping, 1)
        fails(HarnessCode.RESULT_PROFILE) { verifyHarnessPublicProfile(1, input, value.copy(status = "complete"), mapping) }
        fails(HarnessCode.RESULT_PROFILE) { verifyHarnessPublicProfile(1, input, value.copy(coverage = listOf(value.coverage.single().copy(qualified = true))), mapping) }
        fails(HarnessCode.RESULT_PROFILE) { verifyHarnessPublicProfile(1, input, value.copy(coverage = listOf(value.coverage.single().copy(certificateId = "synthetic-certificate"))), mapping) }
    }

    @Test fun syntheticUnavailableOrCountsOnlyResultCannotPass() {
        val mapping = mapping()
        val input = syntheticInput(1, mapping)
        val value = syntheticResult(input, mapping, 1)
        fails(HarnessCode.RESULT_PROFILE) { verifyHarnessPublicProfile(1, input, value.copy(status = "unavailable"), mapping) }
        fails(HarnessCode.RESULT_PROFILE) { verifyHarnessPublicProfile(1, input, value.copy(findings = emptyList()), mapping) }
        fails(HarnessCode.RESULT_PROFILE) { verifyHarnessPublicProfile(1, input, value.copy(coverage = emptyList()), mapping) }
    }

    @Test fun syntheticForeignSourceOrAnalysisKeyCannotPass() {
        val mapping = mapping()
        val input = syntheticInput(1, mapping)
        val value = syntheticResult(input, mapping, 1)
        val finding = value.findings.single()
        fails(HarnessCode.RESULT_PROFILE) { verifyHarnessPublicProfile(1, input,
            value.copy(findings = listOf(finding.copy(location = finding.location.copy(fileDigest = digest('f'))))), mapping) }
        fails(HarnessCode.RESULT_PROFILE) { verifyHarnessPublicProfile(1, input,
            value.copy(findings = listOf(finding.copy(semanticDigest = digest('f')))), mapping) }
        fails(HarnessCode.RESULT_PROFILE) { verifyHarnessPublicProfile(1, input,
            value.copy(findings = listOf(finding.copy(location = finding.location.copy(column = 14)))), mapping) }
    }

    @Test fun syntheticMissingOrInvalidApplicationRequestEvidenceCannotPass() {
        val mapping = mapping()
        val input = syntheticInput(1, mapping)
        val value = syntheticResult(input, mapping, 1)
        val finding = value.findings.single()
        fails(HarnessCode.RESULT_PROFILE) { verifyHarnessPublicProfile(1, input,
            value.copy(findings = listOf(finding.copy(evidence = finding.evidence.filterNot { it.kind == "application-request" }))), mapping) }
        fails(HarnessCode.RESULT_PROFILE) { verifyHarnessPublicProfile(1, input, value.copy(findings = listOf(finding.copy(evidence =
            finding.evidence.map { if (it.kind == "application-request") it.copy(digest = null) else it }))), mapping) }
    }

    @Test fun syntheticUnknownReasonsOrRawErrorsDoNotBecomeClean() {
        val mapping = mapping()
        val input = syntheticInput(2, mapping)
        val value = syntheticResult(input, mapping, 2)
        fails(HarnessCode.RESULT_PROFILE) { verifyHarnessPublicProfile(2, input, value.copy(reasons = value.reasons + "unknown"), mapping) }
        fails(HarnessCode.RESULT_PROFILE) { verifyHarnessPublicProfile(2, input,
            value.copy(rawDiagnostics = listOf(RawDiagnostic("SYNTHETIC error", "error"))), mapping) }
        fails(HarnessCode.RESULT_PROFILE) { verifyHarnessPublicProfile(2, input,
            value.copy(rawDiagnostics = listOf(RawDiagnostic("SYNTHETIC warning", "warning"))), mapping) }
    }

    @Test fun syntheticLateHarnessFailureRetainsTheUnchangedProviderResult() {
        val mapping = mapping()
        val input = syntheticInput(1, mapping)
        val value = syntheticResult(input, mapping, 1)
        val row = CompilerApplicationHarnessAttempt(1, null, input, null, null, true, value, true, null)
        val report = syntheticReport(row, listOf(HarnessCode.EVIDENCE_WRITE))
        assertSame(value, report.attempts[0].providerResult)
        assertFalse(report.verified)
        assertEquals(listOf("evidence_write"), report.failureCodes)
        assertEquals(ContractJson.encodeToJsonElement(value), report.json()["attempts"]!!.jsonArray[0].jsonObject["provider_result"])
    }

    @Test fun syntheticUncreatedSecondObligationStaysVisible() {
        val first = CompilerApplicationHarnessAttempt(1, null, null, null, null, false, null, false, null)
        val report = syntheticReport(first, listOf(HarnessCode.OPEN_UNAVAILABLE))
        assertEquals(2, report.attempts.size)
        assertNull(report.attempts[1].providerInput)
        assertNull(report.attempts[1].originalDeadline)
        assertFalse(report.attempts[1].providerCallEntered)
        assertEquals("planned_not_constructed_unresolved", report.json()["attempts"]!!.jsonArray[1].jsonObject["obligation"]!!.jsonPrimitive.content)
    }

    @Test fun syntheticStartedIngressWithoutInputIsNotReportedAsNeverStarted() {
        val started = OwnedApplicationDeadline.afterMillis(60_000)
        val first = CompilerApplicationHarnessAttempt(1, started, null, null, null, false, null, false, null)
        val report = syntheticReport(first, listOf(HarnessCode.FILE_DRIFT))
        assertSame(started, report.attempts[0].originalDeadline)
        assertEquals("ingress_started_input_not_constructed_unresolved",
            report.json()["attempts"]!!.jsonArray[0].jsonObject["obligation"]!!.jsonPrimitive.content)
        assertFalse(report.verified)
    }

    @Test fun syntheticProviderFlagsWithoutActualObjectsAndCustodyNeverVerify() {
        val mapping = mapping()
        val input = syntheticInput(1, mapping)
        val first = CompilerApplicationHarnessAttempt(1, null, input, null, null, true, syntheticResult(input, mapping, 1), true, null)
        val report = syntheticReport(first, emptyList())
        assertNull(report.application); assertNull(report.retirement)
        assertFalse(report.retirementCallEntered)
        assertFalse(report.verified)
        assertEquals(JsonNull, report.json()["native_retirement"])
    }

    @Test fun syntheticThrowableTextIsNeverARecordedFailureCode() {
        val secretLikeText = "SYNTHETIC private path and diagnostic content must not become a failure code"
        assertEquals(HarnessCode.UNEXPECTED, harnessCode(IllegalStateException(secretLikeText)))
        assertEquals(HarnessCode.FILE_DRIFT, harnessCode(HarnessProblem(HarnessCode.FILE_DRIFT)))
        val row = CompilerApplicationHarnessAttempt(1, null, null, null, null, false, null, false, null)
        assertFalse(Digests.canonical(syntheticReport(row, listOf(harnessCode(IllegalStateException(secretLikeText)))).json()).contains(secretLikeText))
    }

    private fun fails(code: HarnessCode, action: () -> Unit) = assertEquals(code, assertThrows(HarnessProblem::class.java) { action() }.code)
    private fun digest(char: Char): String = "sha256:" + char.toString().repeat(64)
    // Current-filesystem path SYNTAX only. No existence/attribute/toolchain/native feature probe.
    private fun syntheticPath(posix: String): String = Path.of("").toAbsolutePath().root.resolve(posix.removePrefix("/")).toString()
    private fun syntheticIngress() = CompilerApplicationHarnessIngress(syntheticPath("/synthetic/manifest.json"), digest('1'), 60_000, 30_000)
    private fun canonical(value: JsonObject): ByteArray = Digests.canonical(value).toByteArray(Charsets.UTF_8)
    private fun parseSynthetic(value: JsonObject): HarnessManifest = canonical(value).let { HarnessManifest.parse(it, Digests.sha256(it)) }
    private fun edit(value: JsonObject, key: String, element: JsonElement): JsonObject = JsonObject(value + (key to element))

    private fun syntheticManifest(): JsonObject = buildJsonObject {
        put("schema", "ktchecker/compiler-application-harness-inputs/1")
        put("namespace", buildJsonObject { put("owner_id", "synthetic-owner"); put("workspace_id", "synthetic-workspace"); put("worktree_id", "synthetic-worktree") })
        put("retained_parent", buildJsonObject { put("path", syntheticPath("/owner/retained")); put("file_key", "SYNTHETIC-key-not-a-custody-receipt") })
        fun artifact(path: String, coordinate: String, digest: String): JsonObject = buildJsonObject {
            put("path", syntheticPath(path)); put("coordinate", coordinate); put("digest", digest); put("size", 128)
        }
        put("bridge", artifact("/approved/bridge/bridge.jar", "synthetic:bridge:1", digest('2')))
        put("jdk", buildJsonObject {
            put("java_executable", syntheticPath("/approved/jdk/bin/java")); put("executable_digest", digest('3'))
            put("runtime_digest", digest('4')); put("toolchain_digest", digest('5'))
        })
        put("compiler", JsonArray(listOf(
            artifact("/approved/compiler/kotlin-compiler-embeddable-2.4.21.jar", "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21", CompilerWire.COMPILER_SHA256),
            artifact("/approved/compiler/kotlin-stdlib-2.4.21.jar", "org.jetbrains.kotlin:kotlin-stdlib:2.4.21", digest('6')))))
        put("dependencies", JsonArray(listOf(JsonPrimitive(1))))
        put("host", buildJsonObject {
            put("classpath", JsonArray(listOf(buildJsonObject {
                put("path", syntheticPath("/approved/host")); put("kind", "directory"); put("digest", digest('7')); put("size", 1024); put("entries", 4)
            })))
            put("vm_arguments", JsonArray(listOf(JsonPrimitive("-Xmx512m"))))
            put("anchors", JsonObject(hostAnchors().keys.associateWith { JsonPrimitive(0) }))
            put("registry_index", 0)
        })
    }

    private fun mapping(): CompilerRuleMapping = checkNotNull(CompilerRuleRegistry.load().byUpstreamId["UNRESOLVED_REFERENCE"])
    private fun syntheticInput(sequence: Int, mapping: CompilerRuleMapping): AnalysisInput {
        // Test-only detached data; never passed to the real entry or to a fake-selectable backend.
        val text = if (sequence == 1) "package fixtures.unresolved_reference\nfun broken() = absentSymbol()\n"
            else "package fixtures.unresolved_reference\nfun absentSymbol() = 1\nfun broken() = absentSymbol()\n"
        val bytes = text.toByteArray(Charsets.UTF_8)
        return AnalysisInput(SnapshotManifest(digest('8'), "synthetic-workspace", "synthetic-worktree",
            listOf(SnapshotFile("Fixture.kt", Digests.sha256(bytes), bytes.size.toLong())), "synthetic-owner", declaredRoots = listOf(".")),
            syntheticPath("/synthetic/snapshot-$sequence"), AnalysisContext(RuleContext("jvm", "main", ":synthetic", "main")),
            requiredRuleIds = listOf(mapping.ruleId))
    }
    private fun syntheticResult(input: AnalysisInput, mapping: CompilerRuleMapping, sequence: Int): ProviderResult {
        val findings = if (sequence == 1) listOf(Finding(mapping.ruleId, "SYNTHETIC unit diagnostic", "SYNTHETIC unresolved reference",
            "SYNTHETIC control, not compiler execution", SourceLocation("Fixture.kt", input.snapshot.files.single().digest, 2, 15),
            digest('9'), KotlinJvmLiveCompilerProvider.PROVIDER_ID, input.context.scope, "error", evidence = listOf(
                Evidence("compiler-application-emission", "SYNTHETIC control only", digest = digest('c')),
                Evidence("application-request", "SYNTHETIC digest preservation control, not an observed wire request", digest = digest('d'))),
            upstreamId = "UNRESOLVED_REFERENCE", semanticDigest = input.analysisKey())) else emptyList()
        return ProviderResult(KotlinJvmLiveCompilerProvider.PROVIDER_ID, if (sequence == 1) "failed" else "partial", findings,
            listOf(Coverage(mapping.ruleId, input.context.scope, "unqualified", "compiler_live_preview_not_qualified",
                provenance = "executed", implemented = sequence == 1, tested = false, qualified = false)),
            listOf("compiler_live_preview_not_qualified", "compiler_live_semantic_extraction_not_authorized") +
                if (sequence == 1) listOf("compiler_live_api_diagnostic_failure") else emptyList(),
            providerVersion = KotlinJvmLiveCompilerProvider.VERSION)
    }
    private fun syntheticReport(first: CompilerApplicationHarnessAttempt, failures: List<HarnessCode>): CompilerApplicationHarnessReport =
        CompilerApplicationHarnessReport(syntheticIngress(), null, OwnedApplicationDeadline.afterMillis(60_000),
            null, null, null, null, null, null, listOf(first,
                CompilerApplicationHarnessAttempt(2, null, null, null, null, false, null, false, null)),
            null, false, failures, true)
}

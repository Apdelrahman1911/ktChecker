package dev.ktchecker.cli

import dev.ktchecker.config.*
import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** Real coordinator/config/kernel plumbing with synthetic diagnostics only. No compiler, library
 * rule implementation, semantic movement proof, release qualification or successful final gate.
 */
class CoordinatorFindingIdentityTest {
    @TempDir lateinit var temp: Path

    @Test fun materialSiblingsSurviveProgressAndFinalWithStableIndependentRawIdentities() {
        val f = Fixture(temp.resolve("material"))
        val base = f.finding()
        val siblings = listOf(base,
            base.copy(evidence = listOf(Evidence("synthetic_material", "Different cause", digest = Digests.sha256("different cause")))),
            base.copy(relatedLocations = listOf(f.related("CauseB.kt"))),
            base.copy(severity = "error"),
            base.copy(kind = "advisory"),
            base.copy(certainty = "high"),
            base.copy(evidence = base.evidence + base.evidence.single()),
            base.copy(verification = listOf(Verification(expected = "Synthetic failure", state = "failed"))),
        )
        // The previous aggregate's exact key collapsed ALL of these to its first row.
        assertEquals(1, siblings.map(::legacyDisplayKey).distinct().size)
        assertEquals(1, siblings.map { it.fingerprint }.distinct().size)
        val result = f.aggregate(siblings)
        assertEquals(siblings, result.findings.map { it.copy(fingerprint = base.fingerprint) })
        assertEquals(siblings.size, result.findings.map { it.fingerprint }.distinct().size)
        assertTrue(result.findings.all { Digests.valid(it.fingerprint) })
        assertPreview(result)
        siblings.forEachIndexed { index, sibling ->
            val alone = f.aggregate(listOf(sibling)).findings.single()
            assertEquals(result.findings[index], alone, "Neither adding nor removing a sibling may change the surviving raw identity")
        }
        assertEquals(result.findings.reversed(), f.aggregate(siblings.reversed()).findings)
        val anotherDiagnosis = base.copy(diagnosis = "Another complete displayed diagnostic")
        assertEquals(2, f.aggregate(listOf(base, anotherDiagnosis)).findings.size)
    }

    @Test fun onlyEquivalentAliasDiagnosticsCoalesceAndLinkedLocatorsRemainRetrievable() {
        val f = Fixture(temp.resolve("aliases"))
        val base = f.finding()
        val material = base.evidence.single()
        val locatorA = EvidenceRetrievalAttachment(material.kind, checkNotNull(material.digest), "synthetic-report:a")
        val locatorB = locatorA.copy(artifactRef = "synthetic-report:b")
        val first = base.copy(upstreamId = "synthetic-alias-a", retrievalAttachments = listOf(locatorA))
        val alias = first.copy(upstreamId = "synthetic-alias-b", fingerprint = Digests.sha256("another upstream display key"),
            retrievalAttachments = listOf(locatorB))
        val merged = f.aggregate(listOf(first, alias, first)).findings.single()
        assertEquals(first.copy(fingerprint = merged.fingerprint, retrievalAttachments = listOf(locatorA, locatorB)), merged)
        assertEquals(merged.fingerprint, f.aggregate(listOf(alias)).findings.single().fingerprint)
        assertEquals(merged.fingerprint, f.aggregate(listOf(first)).findings.single().fingerprint)
        assertEquals(merged, f.aggregate(listOf(alias, first)).findings.single(),
            "Equivalent alias order cannot choose different retained labels or locator ordering")
        // The special locator field is not a blanket license to ignore ordinary material refs.
        val changed = first.copy(evidence = listOf(material.copy(artifactRef = "synthetic-material:different")))
        val distinct = f.aggregate(listOf(first, changed)).findings
        assertEquals(2, distinct.size)
        assertNotEquals(distinct[0].fingerprint, distinct[1].fingerprint)
    }

    @Test fun malformedProviderFingerprintCannotBeLaunderedByCoordinatorNormalization() {
        val f = Fixture(temp.resolve("malformed"))
        val result = f.aggregate(listOf(f.finding().copy(fingerprint = "not-an-authentic-digest")))
        assertTrue(result.findings.isEmpty())
        assertEquals("failed", result.coverage.single().state)
        assertEquals("provider_diagnostic_identity_mismatch", result.coverage.single().reason)
        assertTrue(result.reasons.any { it.endsWith(":provider_diagnostic_identity_mismatch") })
        assertPreview(result)
    }

    @Test fun escapedLoneSurrogatesDecodedFromProviderEnvelopesFailTheWholeChildInEitherOrder() {
        val f = Fixture(temp.resolve("decoded-surrogates"))
        val marker = "synthetic-json-diagnosis-marker"
        val question = f.finding().copy(diagnosis = "?")
        val raw = f.raw(f.input(), listOf(question.copy(diagnosis = marker)))
        // An untrusted structural success claim must be discarded, not merely relabeled partial.
        // This synthetic certificate text is not registered with a kernel or qualification store.
        val claimed = raw.copy(status = "complete", coverage = raw.coverage.map {
            it.copy(certificateId = "synthetic-untrusted-certificate-claim")
        })
        for ((escape, unit) in listOf("\\ud800" to 0xd800, "\\udc00" to 0xdc00)) {
            val wire = ContractJson.encodeToString(claimed).replace("\"$marker\"", "\"$escape\"")
            assertTrue(escape in wire, "The provider envelope must contain a JSON escape, not a Kotlin surrogate literal")
            val decoded = ContractJson.decodeFromString<ProviderResult>(wire)
            val malformed = decoded.findings.single()
            assertEquals(1, malformed.diagnosis.length, "The parser must actually accept the escaped lone unit for this control")
            assertEquals(unit, malformed.diagnosis[0].code)
            assertTrue(decoded.coverage.single().successful())
            val badMaterial = materialDescriptor(malformed)
            val questionMaterial = materialDescriptor(question)
            assertNotEquals(questionMaterial, badMaterial)
            // Witness the former replacement-based UTF-8 collision using the selected serializer.
            // Production normalization must refuse this text BEFORE calling the lossy digest API.
            assertEquals(Digests.sha256(questionMaterial), Digests.sha256(badMaterial))
            for (siblings in listOf(listOf(malformed), listOf(malformed, question), listOf(question, malformed))) {
                val progress = mutableListOf<ProviderResult>()
                val result = f.aggregateRaw(decoded.copy(findings = siblings)) { progress += it }
                assertEquals(1, progress.size)
                (progress + result).forEach { assertChildFailed(it, "coordinator_finding_text_invalid") }
                assertPreview(result)
            }
        }
    }

    @Test fun validReplacementQuestionAndSupplementaryUnicodeKeepDistinctStableIdentities() {
        val f = Fixture(temp.resolve("valid-unicode"))
        val marker = "synthetic-valid-json-marker"
        val raw = f.raw(f.input(), listOf(f.finding().copy(diagnosis = marker)))
        val findings = listOf("?" to "?", "\\ufffd" to "\uFFFD", "\\ud83d\\ude00" to String(Character.toChars(0x1f600)))
            .map { (escape, expected) ->
                val wire = ContractJson.encodeToString(raw).replace("\"$marker\"", "\"$escape\"")
                ContractJson.decodeFromString<ProviderResult>(wire).findings.single().also {
                    assertEquals(expected, it.diagnosis)
                }
            }
        val result = f.aggregate(findings)
        assertEquals(3, result.findings.size)
        assertEquals(3, result.findings.map { it.fingerprint }.distinct().size)
        assertEquals(findings.map { it.diagnosis }, result.findings.map { it.diagnosis })
        findings.forEachIndexed { index, finding ->
            assertEquals(result.findings[index], f.aggregate(listOf(finding)).findings.single())
        }
        assertEquals(result.findings.reversed(), f.aggregate(findings.reversed()).findings)
        assertPreview(result)
    }

    @Test fun decodedMalformedNestedAndExcludedTextIsNotAllowedToBypassTheFindingBoundary() {
        val f = Fixture(temp.resolve("nested-surrogates"))
        val base = f.finding()
        val marker = "synthetic-nested-json-marker"
        val material = base.evidence.single()
        data class TextCase(val name: String, val marked: Finding, val text: (Finding) -> String)
        val cases = listOf(
            TextCase("title", base.copy(title = marker)) { it.title },
            TextCase("evidence", base.copy(evidence = listOf(material.copy(summary = marker)))) { it.evidence.single().summary },
            TextCase("verification expected", base.copy(verification = listOf(Verification(expected = marker)))) {
                it.verification.single().expected
            },
            TextCase("verification command", base.copy(verification = listOf(Verification(expected = "Synthetic check",
                command = listOf(marker))))) { it.verification.single().command.single() },
            TextCase("primary excerpt", base.copy(location = base.location.copy(excerpt = marker))) { it.location.excerpt },
            TextCase("related excerpt", base.copy(relatedLocations = listOf(base.relatedLocations.single().copy(excerpt = marker)))) {
                it.relatedLocations.single().excerpt
            },
            TextCase("excluded upstream alias", base.copy(upstreamId = marker)) { checkNotNull(it.upstreamId) },
            TextCase("semantic claim", base.copy(semanticDigest = marker)) { checkNotNull(it.semanticDigest) },
            TextCase("excluded retrieval locator", base.copy(retrievalAttachments = listOf(
                EvidenceRetrievalAttachment(material.kind, checkNotNull(material.digest), marker)))) {
                it.retrievalAttachments.single().artifactRef
            },
        )
        cases.forEach { case ->
            val wire = ContractJson.encodeToString(f.raw(f.input(), listOf(case.marked)))
                .replace("\"$marker\"", "\"\\ud800\"")
            assertTrue("\\ud800" in wire, case.name)
            val decoded = ContractJson.decodeFromString<ProviderResult>(wire)
            assertEquals(listOf(0xd800), case.text(decoded.findings.single()).map { it.code }, case.name)
            val progress = mutableListOf<ProviderResult>()
            val result = f.aggregateRaw(decoded.copy(findings = listOf(base) + decoded.findings)) { progress += it }
            (progress + result).forEach { assertChildFailed(it, "coordinator_finding_text_invalid") }
            assertPreview(result)
        }
    }

    @Test fun injectedDigestCollisionRejectsDifferentExactDescriptorsWithoutRekeyingOrOverwriting() {
        val f = Fixture(temp.resolve("digest-collision"))
        val base = f.finding()
        val left = materialDescriptor(base)
        val right = materialDescriptor(base.copy(evidence = listOf(base.evidence.single().copy(summary = "Different material"))))
        assertNotEquals(left, right)
        assertEquals(left, materialDescriptor(base.copy(upstreamId = "otherwise-exact-alias",
            fingerprint = Digests.sha256("synthetic other raw fingerprint"))))
        // Inject only DATA into the exact-descriptor guard. There is deliberately no injected
        // digest callback on the production coordinator, normalizer or policy identity API.
        val forcedDigest = Digests.sha256("synthetic exact-descriptor collision control")
        for ((first, second) in listOf(left to right, right to left)) {
            val descriptors = CoordinatorFindingDescriptors()
            descriptors.verifyAndRemember(forcedDigest, first)
            descriptors.verifyAndRemember(forcedDigest, first)
            val problem = assertFailsWith<CoordinatorFindingIdentityProblem> {
                descriptors.verifyAndRemember(forcedDigest, second)
            }
            assertEquals("coordinator_finding_identity_collision", problem.code)
            // A rejected collision cannot overwrite the original descriptor or install a suffix.
            descriptors.verifyAndRemember(forcedDigest, first)
            assertEquals("coordinator_finding_identity_collision", assertFailsWith<CoordinatorFindingIdentityProblem> {
                descriptors.verifyAndRemember(forcedDigest, second)
            }.code)
        }
    }

    @Test fun capturedFindingConstructorFailureDiscardsAllChildRowsBeforeProgress() {
        val f = Fixture(temp.resolve("capture-failure"))
        val base = f.finding()
        val evidence = base.evidence.toMutableList()
        val material = evidence.single()
        val linked = base.copy(evidence = evidence, retrievalAttachments = listOf(
            EvidenceRetrievalAttachment(material.kind, checkNotNull(material.digest), "synthetic-report:linked")))
        evidence.clear() // A provider-owned list invalidates a previously valid constructor link.
        val progress = mutableListOf<ProviderResult>()
        val result = f.aggregateRaw(f.raw(f.input(), listOf(base, linked))) { progress += it }
        (progress + result).forEach { assertChildFailed(it, "coordinator_finding_capture_failed") }
        assertPreview(result)
    }

    @Test fun providerAndProgressMutationCannotChangeCapturedMaterialOrFinalCombinedState() {
        val f = Fixture(temp.resolve("immutable-capture"))
        val base = f.finding()
        val evidence = base.evidence.toMutableList()
        val command = mutableListOf("synthetic-check", "--strict")
        val verification = mutableListOf(Verification(expected = "Synthetic verification", command = command))
        val related = base.relatedLocations.toMutableList()
        val material = evidence.single()
        val retrieval = mutableListOf(EvidenceRetrievalAttachment(material.kind, checkNotNull(material.digest), "synthetic-report:retained"))
        val findings = mutableListOf(base.copy(evidence = evidence, verification = verification,
            relatedLocations = related, retrievalAttachments = retrieval))
        val initial = f.raw(f.input(), findings)
        val coverage = initial.coverage.toMutableList()
        val reasons = initial.reasons.toMutableList()
        val rawDiagnostics = mutableListOf(RawDiagnostic("Synthetic observed informational text", "info"))
        val raw = initial.copy(coverage = coverage, reasons = reasons, rawDiagnostics = rawDiagnostics)
        val expected = ContractJson.decodeFromString<ProviderResult>(ContractJson.encodeToString(raw))
        val expectedFinding = expected.findings.single().let { it.copy(fingerprint = Digests.sha256(materialDescriptor(it))) }
        var callbacks = 0
        val result = f.aggregateRaw(raw) { published ->
            callbacks++
            findings.clear()
            evidence[0] = material.copy(summary = "Changed provider-owned evidence")
            command[0] = "changed-provider-command"
            verification.clear()
            related[0] = f.related("CauseB.kt")
            retrieval.clear()
            coverage.clear()
            reasons.clear()
            rawDiagnostics.clear()
            assertEquals(listOf(expectedFinding), published.findings)
            assertEquals(expected.coverage, published.coverage)
            assertEquals(expected.reasons, published.reasons)
            assertEquals(expected.rawDiagnostics, published.rawDiagnostics)
            // Publication itself cannot expose a write route into the retained child or final.
            assertFrozenList(published.findings)
            assertFrozenList(published.coverage)
            assertFrozenList(published.reasons)
            assertFrozenList(published.rawDiagnostics)
            val captured = published.findings.single()
            assertFrozenList(captured.evidence)
            assertFrozenList(captured.verification)
            assertFrozenList(captured.verification.single().command)
            assertFrozenList(captured.relatedLocations)
            assertFrozenList(captured.retrievalAttachments)
        }
        assertEquals(1, callbacks)
        assertTrue(raw.findings.isEmpty() && raw.coverage.isEmpty() && raw.reasons.isEmpty() && raw.rawDiagnostics.isEmpty())
        assertEquals(listOf(expectedFinding), result.findings)
        assertEquals(expected.coverage, result.coverage)
        assertEquals(expected.rawDiagnostics, result.rawDiagnostics)
        assertEquals(expected.reasons.map { "$PROVIDER:$it" } + "preview_not_release_qualified", result.reasons)
        assertEquals(Digests.sha256(materialDescriptor(result.findings.single())), result.findings.single().fingerprint)
        assertPreview(result)
    }

    @Test fun exactOwnerAcceptanceCannotHideAMaterialSiblingInTheRetainedKernelResult() {
        val f = Fixture(temp.resolve("kernel"))
        val base = f.finding()
        val sibling = base.copy(relatedLocations = listOf(f.related("CauseB.kt")),
            evidence = listOf(Evidence("synthetic_material", "Independent related cause", digest = Digests.sha256("independent cause"))))
        assertEquals(legacyDisplayKey(base), legacyDisplayKey(sibling))
        assertEquals(base.fingerprint, sibling.fingerprint)
        val captures = ConcurrentHashMap<String, ConfigSnapshot>()
        captures[f.initial.policyDigest] = f.initial
        val plans = ConcurrentHashMap<String, List<Finding>>()
        plans[f.initial.policyDigest] = listOf(base)
        val childResults = ConcurrentHashMap<String, ProviderResult>()
        val finalRaw = ConcurrentHashMap<String, ProviderResult>()
        val calls = AtomicInteger()
        val provider = f.provider { input ->
            calls.incrementAndGet()
            val digest = checkNotNull(input.context.configurationDigest)
            f.raw(input, checkNotNull(plans[digest])).also { childResults[digest] = it }
        }
        val coordinator = f.coordinator(provider)
        val policy = ResultPolicy.versioned("ktchecker/test/coordinator-material-policy/1") { binding, input, raw ->
            assertEquals(f.clientBinding, binding)
            val digest = checkNotNull(input.context.configurationDigest)
            if ("preview_not_release_qualified" in raw.reasons) finalRaw[digest] = raw
            f.configs.apply(checkNotNull(captures[digest]), input, raw)
        }
        KernelService.open(f.root.resolve("broker"), coordinator,
            KernelConfig(workerCount = 1, globalParallelism = 1, perWorktreeParallelism = 1,
                heartbeatMillis = 30, leaseMillis = 120, pollMillis = 5),
            findingProviders = coordinator.findingProviders, resultPolicy = policy).use { kernel ->
            val client = kernel.registerClient(f.clientBinding)
            val originalInput = f.input(f.initial)
            val before = awaitResult(kernel, client, kernel.submit(client.client,
                SubmitRequest("before-consent", "before-consent", originalInput)).reviewId)
            val beforeResult = assertNotNull(before.result)
            assertEquals(listOf("open"), beforeResult.findings.map { it.disposition })
            assertEquals(finalRaw[f.initial.policyDigest], beforeResult)
            assertEquals("incomplete", before.state)
            val beforeEvents = events(kernel, client)
            val selector = f.configs.findingSelector(f.initial, originalInput, beforeResult.findings.single())
            val consent = f.accept(selector.copy(ruleId = ALIAS))
            assertEquals(RULE, consent.local.suppressions.single().spec.selector.ruleId)
            captures[consent.policyDigest] = consent
            plans[consent.policyDigest] = listOf(base, sibling)
            val currentInput = f.input(consent)
            val after = awaitResult(kernel, client, kernel.submit(client.client,
                SubmitRequest("after-consent", "after-consent", currentInput)).reviewId)
            val effective = assertNotNull(after.result)
            val raw = assertNotNull(finalRaw[consent.policyDigest])
            assertEquals(listOf(base, sibling), childResults[consent.policyDigest]?.findings)
            assertEquals(listOf("accepted", "open"), effective.findings.map { it.disposition })
            assertEquals(raw, effective.copy(findings = effective.findings.map { it.copy(disposition = "open") }))
            assertEquals(beforeResult.findings.single().fingerprint, effective.findings.first().fingerprint)
            assertEquals(2, effective.findings.map { it.fingerprint }.distinct().size)
            assertFalse("finding_fingerprint_invalid" in after.reasons, after.reasons.toString())
            assertEquals("incomplete", after.state)
            assertPreview(raw)
            assertPreview(effective)
            assertEquals(2, calls.get())
            // A later unqualified result no longer reports the sibling. Its disappearance may
            // neither change the surviving raw ID nor become a certified fix of retained history.
            val pairEvents = events(kernel, client)
            plans[consent.policyDigest] = listOf(base)
            val laterInput = currentInput.copy(snapshot = currentInput.snapshot.copy(
                snapshotId = Digests.sha256("later synthetic snapshot with identical source bytes"),
                parentSnapshotId = currentInput.snapshot.snapshotId))
            val later = awaitResult(kernel, client, kernel.submit(client.client,
                SubmitRequest("later-single-row", "later-single-row", laterInput)).reviewId)
            val laterResult = assertNotNull(later.result)
            assertEquals(listOf(effective.findings.first()), laterResult.findings)
            assertEquals("incomplete", later.state)
            assertPreview(laterResult)
            assertEquals(3, calls.get())
            // Publication and the original frozen policy are immutable after later owner consent.
            assertEquals(before, kernel.status(client.client, before.reviewId))
            assertEquals(after, kernel.status(client.client, after.reviewId))
            val allEvents = events(kernel, client)
            assertEquals(pairEvents, allEvents.take(pairEvents.size))
            assertEquals(beforeEvents, allEvents.take(beforeEvents.size))
            val updates = allEvents.filter { it.reviewId == after.reviewId && it.kind in setOf("progress", "result") }
            assertEquals(setOf("progress", "result"), updates.map { it.kind }.toSet())
            updates.forEach { event ->
                val published = ContractJson.decodeFromString<ReviewStatus>(event.payload)
                assertEquals(effective.findings, published.result?.findings)
            }
            allEvents.forEach { event ->
                val context = Digests.sha256("synthetic-delivery:${event.payloadDigest}")
                kernel.acknowledge(client.adapter, listOf(event.eventId), ContextReceipt("ack-${event.eventId}", context,
                    "synthetic-item-${event.eventId}", Instant.EPOCH.toString()))
                kernel.observe(client.adapter, listOf(event.eventId), ObservationReceipt("obs-${event.eventId}",
                    "synthetic-request-${event.eventId}", listOf(context)))
            }
            val gate = kernel.prepareGate(client.client)
            assertFalse(gate.ready)
            assertTrue("open_finding:${effective.findings[1].fingerprint}" in gate.reasons, gate.reasons.toString())
            assertFalse(gate.reasons.any { it.startsWith("insertion_missing:") || it.startsWith("model_observation_missing:") })
            var effects = 0
            val completed = kernel.completeGate(client.client, gate.cutId) { effects++ }
            assertFalse(completed.allowed)
            assertFalse(completed.actionPerformed)
            assertEquals(0, effects)
            assertEquals(consent.revisionToken, f.configs.capture(f.binding).revisionToken)
            assertContentEquals(f.sourceBytes, Files.readAllBytes(f.sourceRoot.resolve("Main.kt")))
        }
    }

    private class Fixture(val root: Path) {
        val catalog = Catalog()
        val image = CodeImage.capture(listOf(ReviewCoordinator::class.java))
        val binding = ConfigBinding("synthetic-owner", "synthetic-workspace", "synthetic-worktree", "synthetic-lineage")
        val clientBinding = ClientBinding(binding.ownerId, binding.workspaceId, binding.worktreeId, "synthetic-client", binding.lineageId)
        val owner = ClientIdentity(binding.ownerId, "synthetic-explicit-owner", "synthetic-owner-session")
        private val implementation = Digests.sha256("synthetic coordinator identity fixture; no actual library rule")
        private val authority = HmacOwnerAuthority(ByteArray(32) { it.toByte() }, listOf(OwnerSessionRegistration(owner, setOf(binding))))
        val configs = ConfigStore(root.resolve("configuration"), RuleAliases(listOf(PolicyRule(RULE,
            aliases = setOf(ALIAS), implementationDigest = implementation))), authority)
        val initial = configs.capture(binding)
        val sourceBytes = "fun synthetic() = 1\n".toByteArray()
        private val causes = mapOf("CauseA.kt" to "val causeA = 1\n".toByteArray(), "CauseB.kt" to "val causeB = 2\n".toByteArray())
        val sourceRoot = Files.createDirectories(root.resolve("source")).toRealPath().also { directory ->
            Files.write(directory.resolve("Main.kt"), sourceBytes)
            causes.forEach { (path, bytes) -> Files.write(directory.resolve(path), bytes) }
        }
        private val files = (listOf(SnapshotFile("Main.kt", Digests.sha256(sourceBytes), sourceBytes.size.toLong())) +
            causes.map { (path, bytes) -> SnapshotFile(path, Digests.sha256(bytes), bytes.size.toLong()) }).sortedBy { it.path }

        fun input(policy: ConfigSnapshot = initial): AnalysisInput = AnalysisInput(
            SnapshotManifest(Digests.sha256("synthetic coordinator snapshot"), binding.workspaceId, binding.worktreeId,
                files, ownerId = binding.ownerId), sourceRoot.toString(),
            AnalysisContext(configurationDigest = policy.policyDigest, compilationFiles = files.map { it.path },
                modelDigest = Digests.sha256("synthetic model"), dependencyDigest = Digests.sha256("synthetic dependencies"),
                toolchainDigest = Digests.sha256("synthetic toolchain"), fullProject = false,
                policies = mapOf(ACTIVATION_POLICY_KEY to Digests.canonical(ContractJson.encodeToJsonElement(
                    activationPlan(catalog, listOf(RULE), true, emptyMap(), policy.policyDigest))))), listOf(RULE))

        fun finding() = Finding(RULE, "Synthetic co-located finding", "Same synthetic display diagnosis", "Aggregation fixture only",
            SourceLocation("Main.kt", Digests.sha256(sourceBytes), 1, 0, 1, 3), Digests.sha256("coarse synthetic upstream key"), PROVIDER,
            evidence = listOf(Evidence("synthetic_material", "Original cause", digest = Digests.sha256("original cause"))),
            relatedLocations = listOf(related("CauseA.kt")))

        fun related(path: String) = SourceLocation(path, Digests.sha256(causes.getValue(path)), 1, 0, 1, 3)

        fun raw(input: AnalysisInput, findings: List<Finding>) = ProviderResult(PROVIDER, "partial", findings,
            listOf(Coverage(RULE, input.context.scope, "findings", "synthetic_aggregation_unqualified", depth = "semantic",
                provenance = "executed", implemented = true, tested = false, qualified = false)),
            reasons = listOf("synthetic_aggregation_unqualified"), providerVersion = VERSION)

        fun provider(produce: (AnalysisInput) -> ProviderResult) = object : AnalysisProvider {
            override val id = PROVIDER
            override val version = VERSION
            override val supportedRuleIds = setOf(RULE)
            override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
                cancellation.throwIfCancelled()
                return produce(input)
            }
        }

        fun coordinator(provider: AnalysisProvider) = ReviewCoordinator(catalog,
            listOf(ProviderRoute(provider, { true }, implementationIdentity = implementation)), ExecutionGrants(), image)

        fun aggregate(findings: List<Finding>): ProviderResult = aggregateRaw(raw(input(), findings))

        fun aggregateRaw(raw: ProviderResult, onProgress: (ProviderResult) -> Unit = {}): ProviderResult {
            val progress = mutableListOf<ProviderResult>()
            val coordinator = coordinator(provider { raw })
            val result = coordinator.analyzeProgressive(input(), CancellationToken.NONE) {
                progress += it
                onProgress(it)
            }
            assertEquals(result.findings, progress.single().findings, "Progress and final must retain the same diagnostic set and identities")
            return result
        }

        fun accept(selector: PolicySelector.ExactFinding): ConfigSnapshot {
            val request = ConfigMutationRequest(binding, ConfigLayer.LOCAL, initial.revisionToken,
                ConfigAction.AddSuppression(SuppressionSpec("one-exact-owner-acceptance", selector, SuppressionScope.Project,
                    SuppressionReason.ACCEPTED_RISK, "Synthetic explicit owner acceptance of only the original cause")), "synthetic-owner-mutation")
            return configs.mutate(request, authority.issueForExplicitOwnerAction(owner, request))
        }
    }

    companion object {
        private const val RULE = "ktc.coroutines.runblocking-in-suspend"
        private const val ALIAS = "synthetic-exact-rule-alias"
        private const val PROVIDER = "synthetic-coordinator-material"
        private const val VERSION = "synthetic/1"

        private fun legacyDisplayKey(finding: Finding): List<Any> = with(finding) {
            listOf(ruleId, context.id, location.path, location.fileDigest, location.line, location.column,
                location.endLine, location.endColumn, diagnosis)
        }

        private fun materialDescriptor(finding: Finding): String = Digests.canonical(buildJsonObject {
            put("format", "ktchecker/coordinator-finding/1")
            put("diagnostic", JsonObject(ContractJson.encodeToJsonElement(finding).jsonObject.filterKeys {
                it !in setOf("fingerprint", "upstreamId", "retrievalAttachments")
            }))
        })

        private fun assertChildFailed(result: ProviderResult, reason: String) {
            assertEquals("partial", result.status, "A progressive/final aggregate remains incomplete")
            assertTrue(result.findings.isEmpty(), "One invalid diagnostic must discard benign siblings too")
            assertEquals(listOf(RULE), result.coverage.map { it.ruleId })
            assertTrue(result.coverage.all { it.state == "failed" && it.reason == reason })
            assertFalse(result.coverage.any { it.successful() || it.qualified || it.tested || it.certificateId != null })
            assertTrue(result.reasons.any { it == reason || it == "$PROVIDER:$reason" })
            assertFalse(result.complete())
        }

        private fun <T> assertFrozenList(values: List<T>) {
            assertTrue(values.isNotEmpty(), "This mutation control needs an occupied list")
            assertFailsWith<UnsupportedOperationException> { (values as MutableList<T>).clear() }
            assertFailsWith<UnsupportedOperationException> { (values as MutableList<T>)[0] = values[0] }
        }

        private fun assertPreview(result: ProviderResult) {
            assertEquals("partial", result.status)
            assertTrue("preview_not_release_qualified" in result.reasons)
            assertFalse(result.coverage.any { it.qualified || it.tested || it.certificateId != null })
            assertFalse(result.complete())
        }

        private fun events(kernel: KernelService, client: ClientRegistration): List<ReviewEvent> = kernel.events(client.client).let {
            assertFalse(it.hasMore); it.events
        }

        private fun awaitResult(kernel: KernelService, client: ClientRegistration, reviewId: String): ReviewStatus {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            var result = kernel.status(client.client, reviewId)
            while (result.result == null && System.nanoTime() < deadline) {
                Thread.sleep(5)
                result = kernel.status(client.client, reviewId)
            }
            assertNotNull(result.result, "Synthetic coordinator did not publish in the bounded wait")
            return result
        }
    }
}

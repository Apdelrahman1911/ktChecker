package dev.ktchecker.semantic

import dev.ktchecker.contracts.ArtifactIdentity
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Synthetic DATA controls only: none authenticates a compiler, model, owner or supplying binary. */
class ObservedSemanticFactsTest {
    private val bytes = "fun f() = 1\n".toByteArray()
    private val source = SemanticSource("src/A.kt", Digests.sha256(bytes), bytes.size.toLong(), "main")
    private val artifact = ArtifactIdentity("fixture-only", Digests.sha256("fixture"), "/observed/compiler.jar")
    private fun legacy() = SemanticFactsRequest(Digests.sha256("snapshot"), Digests.sha256("analysis"),
        Digests.sha256("tools"), listOf(artifact), artifact, "/observed/source", listOf(source),
        listOf(SemanticSourceSet("main")), "main", "jvm", "fixture", listOf("-language-version", "2.4"))
    private fun request() = with(legacy()) {
        ObservedSemanticFactsRequest(snapshotId, analysisId, toolchainId, compilerArtifacts, pluginArtifact,
            sourceRoot, sources, sourceSets, leafSourceSet, "", "jvm", compilationId, compilerArguments,
            Digests.sha256("model-evidence"), "a".repeat(32), "b".repeat(32), 60_000)
    }
    private fun shard(request: ObservedSemanticFactsRequest) = SemanticFactShard(request.digest(), request.snapshotId,
        request.analysisId, request.toolchainId, request.pluginArtifact.digest, SEMANTIC_COMPILER_VERSION, source,
        CompilerSourceContext("exact-original-module", "JVM", false, emptyList()), emptyList(), emptyList(),
        emptyList(), emptyList(), emptyList(), emptyList(), jvmBinaryOrigins = JvmBinaryOriginSurvey(emptyList()))
    private fun assembled(request: ObservedSemanticFactsRequest = request(), shards: List<SemanticFactShard> = listOf(shard(request)),
                          exit: Int? = 0, executed: Boolean = true) =
        SemanticFactsValidation.assembleObserved(request, shards, exit, executed, sourceBytes = { bytes })

    @Test fun `empty raw target is separate from the platform in data digest and facts`() {
        val input = request()
        assertTrue(SemanticFactsValidation.observedRequestProblems(input).isEmpty())
        val bundle = assembled(input)
        assertEquals("", bundle.request.target)
        assertEquals("", bundle.facts.target)
        assertEquals("jvm", bundle.request.platform)
        assertEquals(input.digest(), bundle.facts.requestDigest)
        assertNotEquals(input.digest(), input.copy(target = "jvm").digest())
        assertNotEquals(input.digest(), input.copy(platform = "native").digest())
        assertContains(SemanticFactsValidation.requestProblems(legacy().copy(target = "")), "semantic_context_missing")
    }

    @Test fun `legacy encoding and digest retain the original schema and domain`() {
        val input = legacy()
        val encoded = ContractJson.encodeToString(input)
        val artifactJson = """{"coordinate":"fixture-only","digest":"${artifact.digest}","path":"/observed/compiler.jar"}"""
        val expected = """{"snapshotId":"${input.snapshotId}","analysisId":"${input.analysisId}","toolchainId":"${input.toolchainId}","compilerArtifacts":[$artifactJson],"pluginArtifact":$artifactJson,"sourceRoot":"/observed/source","sources":[{"path":"src/A.kt","digest":"${source.digest}","size":${source.size},"sourceSet":"main"}],"sourceSets":[{"id":"main","dependsOn":[]}],"leafSourceSet":"main","target":"jvm","compilationId":"fixture","compilerArguments":["-language-version","2.4"],"dependencyArtifacts":[],"compilerVersion":"2.4.21","adapterId":"kotlin-fir-2.4.21/v1","schemaVersion":1}"""
        assertEquals(expected, encoded)
        assertEquals(Digests.sha256("ktchecker:semantic-request:v1\u0000" +
            Digests.canonical(ContractJson.parseToJsonElement(expected))), input.digest())
        assertFalse("platform" in encoded)
        assertFalse("observeJvmBinaryOrigins" in encoded)
        assertNull(decodeSelectedSemanticRequest(null, encoded).observed)
    }

    @Test fun `separate observed encoding includes raw context profile and origin observation opt in`() {
        val input = request()
        val encoded = ContractJson.encodeToString(input)
        assertContains(encoded, "\"target\":\"\"")
        assertContains(encoded, "\"platform\":\"jvm\"")
        assertContains(encoded, OBSERVED_SEMANTIC_REQUEST_PROFILE)
        assertContains(encoded, "\"observeJvmBinaryOrigins\":true")
        val selected = decodeSelectedSemanticRequest(OBSERVED_SEMANTIC_REQUEST_KIND, encoded)
        assertEquals(input, selected.observed)
        assertEquals(input.digest(), selected.digest())
        assertEquals("", selected.target)
        assertEquals(input.compilerArguments, selected.compilerArguments)
        assertEquals(Digests.sha256("ktchecker:observed-semantic-request:v1\u0000" +
            Digests.canonical(ContractJson.parseToJsonElement(encoded))), input.digest())
    }

    @Test fun `decoder requires exact explicit kind and never retries another schema`() {
        val observed = ContractJson.encodeToString(request())
        val legacy = ContractJson.encodeToString(legacy())
        assertFailsWith<SerializationException> { decodeSelectedSemanticRequest(null, observed) }
        assertFailsWith<SerializationException> { decodeSelectedSemanticRequest(OBSERVED_SEMANTIC_REQUEST_KIND, legacy) }
        listOf("", "legacy", "OBSERVED-JVM-V1", "observed-jvm-v2").forEach {
            assertEquals("semantic_request_kind_unsupported", assertFailsWith<SemanticFactException> {
                decodeSelectedSemanticRequest(it, "not-json")
            }.code)
        }
        assertFailsWith<SerializationException> {
            decodeSelectedSemanticRequest(OBSERVED_SEMANTIC_REQUEST_KIND, observed.dropLast(1) + ",\"foreign\":true}")
        }
    }

    @Test fun `decoder text bound precedes decoding`() {
        for (kind in listOf(null, OBSERVED_SEMANTIC_REQUEST_KIND)) {
            assertEquals("semantic_request_unsafe", assertFailsWith<SemanticFactException> {
                decodeSelectedSemanticRequest(kind, " ".repeat(1_048_577))
            }.code)
        }
    }

    @Test fun `legacy and observed shards cannot be cross fed even with matching named target`() {
        val old = legacy().copy(observeJvmBinaryOrigins = true)
        val observed = request().copy(target = old.target)
        val shared = shard(observed)
        val oldShard = shared.copy(requestDigest = old.digest())
        assertTrue(SemanticFactsValidation.assemble(old, listOf(oldShard), 0, sourceBytes = { bytes }).completeForSupportedVocabulary)
        assertTrue(assembled(observed).facts.completeForSupportedVocabulary)
        val fromObserved = SemanticFactsValidation.assemble(old, listOf(shared), 0, sourceBytes = { bytes })
        val fromLegacy = assembled(observed, listOf(oldShard)).facts
        for (facts in listOf(fromObserved, fromLegacy)) {
            assertEquals("failed", facts.state)
            assertFalse(facts.completeForSupportedVocabulary)
            assertTrue(facts.shards.isEmpty())
            assertContains(facts.reasons, "semantic_evidence_binding_invalid")
        }
    }

    @Test fun `every original model clock argv and raw context label remains digest bound`() {
        val original = request()
        val mutations = listOf(original.copy(modelEvidenceIdentity = Digests.sha256("another-model")),
            original.copy(observedInvocationIdentity = "c".repeat(32)),
            original.copy(originalDeadlineIdentity = "d".repeat(32)), original.copy(originalDeadlineMillis = 60_001),
            original.copy(compilerArguments = original.compilerArguments + "-nowarn"),
            original.copy(target = "jvm"), original.copy(compilationId = "other"),
            original.copy(platform = "native"), original.copy(profile = "another-profile"))
        mutations.forEach {
            assertNotEquals(original.digest(), it.digest())
            val facts = assembled(it, listOf(shard(original))).facts
            assertEquals("failed", facts.state)
            assertContains(facts.reasons, "semantic_evidence_binding_invalid")
        }
    }

    @Test fun `invalid origin or tuple data cannot reach the source byte reader`() {
        val base = request()
        val invalid = listOf(base.copy(modelEvidenceIdentity = "not-a-digest"),
            base.copy(observedInvocationIdentity = "a".repeat(31)), base.copy(observedInvocationIdentity = "A".repeat(32)),
            base.copy(originalDeadlineIdentity = "b".repeat(33)), base.copy(originalDeadlineMillis = 0),
            base.copy(originalDeadlineMillis = 3_600_001), base.copy(target = " "), base.copy(target = "bad\u0000target"),
            base.copy(platform = "JVM"), base.copy(profile = "legacy"), base.copy(schemaVersion = 2),
            base.copy(adapterId = "other"), base.copy(compilerVersion = "2.4.20"),
            base.copy(sourceRoot = "bad\u0000root"))
        invalid.forEach { input ->
            var reads = 0
            assertTrue(SemanticFactsValidation.observedRequestProblems(input).isNotEmpty())
            val bundle = SemanticFactsValidation.assembleObserved(input, listOf(shard(input)), 0, true,
                sourceBytes = { reads++; bytes })
            assertEquals(0, reads)
            assertFalse(bundle.facts.completeForSupportedVocabulary)
            assertContains(bundle.facts.reasons, "semantic_source_bytes_unverified")
            assertFalse(bundle.completeProject)
        }
        assertTrue(SemanticFactsValidation.observedRequestProblems(base.copy(originalDeadlineMillis = 1)).isEmpty())
        assertTrue(SemanticFactsValidation.observedRequestProblems(base.copy(originalDeadlineMillis = 3_600_000)).isEmpty())
    }

    @Test fun `structurally complete vocabulary permanently retains the observed closure gap`() {
        val bundle = assembled()
        assertTrue(bundle.facts.completeForSupportedVocabulary)
        assertEquals("extracted", bundle.facts.state)
        assertTrue(bundle.facts.reasons.isEmpty())
        assertEquals(OBSERVED_SEMANTIC_CONFIGURATION_GAP, bundle.configurationInputClosure)
        assertFalse(bundle.completeProject)
        assertFalse(bundle.tupleQualified)
        assertFalse(bundle.customRulesQualified)
        assertFalse(bundle.facts.tupleQualified)
        assertFalse(bundle.facts.customRulesQualified)
    }

    @Test fun `emitted facts cannot hide failed absent or unavailable compiler completion`() {
        val cases = listOf(assembled(exit = 1) to "semantic_compilation_failed",
            assembled(exit = null) to "semantic_compiler_completion_missing",
            assembled(executed = false) to "semantic_execution_unavailable")
        cases.forEach { (bundle, reason) ->
            assertFalse(bundle.facts.completeForSupportedVocabulary)
            assertContains(bundle.facts.reasons, reason)
            assertEquals(OBSERVED_SEMANTIC_CONFIGURATION_GAP, bundle.configurationInputClosure)
            assertFalse(bundle.completeProject)
        }
        assertEquals("unavailable", assembled(executed = false).facts.state)
    }

    @Test fun `execution reasons missing source and unverified bytes never become clean`() {
        val input = request()
        val stopped = SemanticFactsValidation.assembleObserved(input, listOf(shard(input)), 0, true,
            executionReasons = listOf("original-deadline-expired"), sourceBytes = { bytes })
        assertFalse(stopped.facts.completeForSupportedVocabulary)
        assertContains(stopped.facts.reasons, "original-deadline-expired")
        assertContains(assembled(input, emptyList()).facts.reasons, "semantic_sources_not_visited")
        val unread = SemanticFactsValidation.assembleObserved(input, listOf(shard(input)), 0, true)
        assertContains(unread.facts.reasons, "semantic_source_bytes_unverified")
        val changed = SemanticFactsValidation.assembleObserved(input, listOf(shard(input)), 0, true,
            sourceBytes = { "changed".toByteArray() })
        assertEquals("failed", changed.facts.state)
        assertContains(changed.facts.reasons, "semantic_source_digest_mismatch")
    }

    @Test fun `requested origin survey cannot be omitted or smuggled on unrequested route`() {
        val input = request()
        assertContains(assembled(input, listOf(shard(input).copy(jvmBinaryOrigins = null))).facts.reasons,
            "semantic_jvm_binary_origin_survey_missing")
        val notRequested = input.copy(observeJvmBinaryOrigins = false)
        assertContains(assembled(notRequested).facts.reasons, "semantic_jvm_binary_origin_not_requested")
        val facts = assembled(notRequested, listOf(shard(notRequested).copy(jvmBinaryOrigins = null))).facts
        assertTrue(facts.completeForSupportedVocabulary) // vocabulary DATA only; no binary owner capability
    }

    @Test fun `observed validation detaches every request collection and nested facts`() {
        val artifacts = mutableListOf(artifact)
        val sources = mutableListOf(source)
        val dependsOn = mutableListOf<String>()
        val sets = mutableListOf(SemanticSourceSet("main", dependsOn))
        val arguments = mutableListOf("-language-version", "2.4")
        val dependencies = mutableListOf(artifact)
        val input = request().copy(compilerArtifacts = artifacts, sources = sources, sourceSets = sets,
            compilerArguments = arguments, dependencyArtifacts = dependencies)
        val typeArguments = mutableListOf(TypeProjectionFact("star"))
        val types = mutableListOf(TypeUseFact(SourceCoordinates(source, bytes).span(0, 0, 0, 1),
            TypeFact("class", "kotlin.List", false, typeArguments)))
        val shards = mutableListOf(shard(input).copy(types = types))
        val reasons = mutableListOf<String>()
        val originalDigest = input.digest()
        val bundle = SemanticFactsValidation.assembleObserved(input, shards, 0, true, reasons, sourceBytes = { bytes })
        artifacts.clear(); sources.clear(); dependsOn += "foreign"; sets.clear(); arguments.clear()
        dependencies.clear(); typeArguments.clear(); types.clear(); shards.clear(); reasons += "foreign"
        assertEquals(originalDigest, bundle.request.digest())
        assertTrue(bundle.facts.completeForSupportedVocabulary)
        assertEquals(1, bundle.facts.shards.single().types.single().type.arguments.size)
        for (values in listOf(bundle.request.compilerArtifacts, bundle.request.sources, bundle.request.sourceSets,
            bundle.request.sourceSets.single().dependsOn, bundle.request.compilerArguments, bundle.request.dependencyArtifacts,
            bundle.facts.shards, bundle.facts.shards.single().types, bundle.facts.shards.single().types.single().type.arguments,
            bundle.facts.reasons, bundle.facts.shards.single().jvmBinaryOrigins!!.observations)) {
            assertFailsWith<UnsupportedOperationException> { (values as MutableList<*>).clear() }
        }
    }

    @Test fun `request shape bounds precede digest freeze and callback`() {
        val input = request()
        val invalid = listOf(input.copy(sources = List(1025) { source }),
            input.copy(sourceSets = List(257) { SemanticSourceSet("main") }),
            input.copy(sourceSets = listOf(SemanticSourceSet("main", List(257) { "main" }))),
            input.copy(compilerArtifacts = List(257) { artifact }),
            input.copy(dependencyArtifacts = List(4097) { artifact }),
            input.copy(compilerArguments = List(4097) { "x" }),
            input.copy(target = "x".repeat(4097)), input.copy(compilerArguments = listOf("x".repeat(16_385))),
            input.copy(compilerArguments = List(128) { "x".repeat(16_384) }))
        invalid.forEach {
            assertEquals(listOf("observed_semantic_request_bound"), SemanticFactsValidation.observedRequestProblems(it))
            assertEquals("observed_semantic_request_bound", assertFailsWith<SemanticFactException> { it.digest() }.code)
            var reads = 0
            assertEquals("observed_semantic_request_bound", assertFailsWith<SemanticFactException> {
                SemanticFactsValidation.assembleObserved(it, emptyList(), 0, true, sourceBytes = { reads++; bytes })
            }.code)
            assertEquals(0, reads)
        }
        assertTrue(SemanticFactsValidation.observedRequestProblems(input.copy(target = "x".repeat(4096))).isEmpty())
    }

    @Test fun `overbound or duplicate output shapes preserve the outer taint and fail closed`() {
        val input = request()
        val overbound = assembled(input, List(5) { shard(input) })
        assertEquals("failed", overbound.facts.state)
        assertContains(overbound.facts.reasons, "semantic_fact_shape_limit")
        assertTrue(overbound.facts.shards.isEmpty())
        assertFalse(overbound.completeProject)
        assertEquals(OBSERVED_SEMANTIC_CONFIGURATION_GAP, overbound.configurationInputClosure)
        val duplicate = assembled(input, List(2) { shard(input) })
        assertEquals("failed", duplicate.facts.state)
        assertContains(duplicate.facts.reasons, "semantic_duplicate_source_evidence")
    }

    @Test fun `every observed string rejects unpaired UTF16 before digest freeze or source callbacks`() {
        val input = request()
        for (bad in listOf("\uD800", "\uDC00", "x\uD800y", "x\uDC00y")) {
            val variants = listOf(input.copy(snapshotId = bad), input.copy(analysisId = bad), input.copy(toolchainId = bad),
                input.copy(sourceRoot = bad), input.copy(leafSourceSet = bad), input.copy(target = bad),
                input.copy(platform = bad), input.copy(compilationId = bad), input.copy(modelEvidenceIdentity = bad),
                input.copy(observedInvocationIdentity = bad), input.copy(originalDeadlineIdentity = bad),
                input.copy(compilerVersion = bad), input.copy(adapterId = bad), input.copy(profile = bad),
                input.copy(compilerArguments = listOf(bad)), input.copy(sources = listOf(source.copy(path = bad))),
                input.copy(sources = listOf(source.copy(digest = bad))), input.copy(sources = listOf(source.copy(sourceSet = bad))),
                input.copy(sourceSets = listOf(SemanticSourceSet(bad))),
                input.copy(sourceSets = listOf(SemanticSourceSet("main", listOf(bad))))) +
                listOf(artifact.copy(coordinate = bad), artifact.copy(path = bad), artifact.copy(digest = bad)).flatMap {
                    listOf(input.copy(compilerArtifacts = listOf(it)), input.copy(pluginArtifact = it),
                        input.copy(dependencyArtifacts = listOf(it)))
                }
            variants.forEach { variant ->
                assertEquals(listOf("observed_semantic_request_bound"), SemanticFactsValidation.observedRequestProblems(variant))
                assertEquals("observed_semantic_request_bound", assertFailsWith<SemanticFactException> { variant.digest() }.code)
                var reads = 0
                assertEquals("observed_semantic_request_bound", assertFailsWith<SemanticFactException> {
                    SemanticFactsValidation.assembleObserved(variant, emptyList(), 0, true, sourceBytes = { reads++; bytes })
                }.code)
                assertEquals(0, reads)
            }
        }
    }

    @Test fun `observed JSON refuses escaped lone surrogates without repairing valid Unicode`() {
        val encoded = ContractJson.encodeToString(request())
        for (escaped in listOf("\\ud800", "\\udfff", "a\\ud800b", "a\\udfffb")) {
            val malformed = encoded.replace("\"target\":\"\"", "\"target\":\"$escaped\"")
            assertNotEquals(encoded, malformed)
            assertEquals("observed_semantic_request_bound", assertFailsWith<SemanticFactException> {
                decodeSelectedSemanticRequest(OBSERVED_SEMANTIC_REQUEST_KIND, malformed)
            }.code)
        }
        val valid = listOf("?", "\uFFFD", "\uD83D\uDE42", "a\uD83D\uDE42b")
        val digests = valid.map { target ->
            val input = request().copy(target = target)
            assertTrue(SemanticFactsValidation.observedRequestProblems(input).isEmpty())
            val parsed = decodeSelectedSemanticRequest(OBSERVED_SEMANTIC_REQUEST_KIND, ContractJson.encodeToString(input))
            assertEquals(target, parsed.target)
            assertEquals(input.digest(), parsed.digest())
            input.digest()
        }
        assertEquals(valid.size, digests.distinct().size)
    }

}
